package app.fluxa

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the video player has to offer so [PlayerFrameGen] can re-route mpv without knowing the player classes.
 */
interface FrameGenSurfaceHost {
    /** The view mpv normally draws into; with frame generation on it becomes the presenter's output. */
    val outputView: SurfaceView

    /** Stops mpv drawing and detaches whatever surface it holds (vo = null). */
    fun detachMpvSurface()

    /** Makes mpv draw into [surface] (the presenter's producer), which is [width] x [height] pixels. */
    fun attachMpvSurface(surface: Surface, width: Int, height: Int)

    /** Gives mpv the output view's own surface again (frame generation off). */
    fun attachMpvDirect()

    /** Frame rate of the file being played, 0 when unknown. */
    fun videoFps(): Float
}

/**
 * Frame generation inside the video player. Same engine and same settings drawer as the screen-capture
 * path; only the plumbing differs:
 *
 *   capture path : MediaProjection -> producer surface -> presenter -> full-screen overlay SurfaceView
 *   player path  : mpv             -> producer surface -> presenter -> the player's own SurfaceView
 *
 * No screen capture, no accessibility overlay, no display-size change. mpv still lays out the video,
 * subtitles and OSD exactly as before; it just draws into the presenter's input instead of the screen.
 */
class PlayerFrameGen(
    private val activity: Activity,
    private val host: FrameGenSurfaceHost,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("fluxa", Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(false)

    /** True while the user has frame generation switched on for the player (drives the button). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The user wants it on; survives the surface coming and going (background, rotation, PiP). */
    @Volatile
    var wanted = false
        private set

    /** The presenter owns the output surface and mpv draws into its producer. */
    @Volatile
    var routing = false
        private set

    private var pipelineUp = false
    private var generation = 0 // bumped on every teardown so delayed steps of an older run are ignored
    private var attempts = 0
    private var srcW = 0
    private var srcH = 0
    private var outW = 0
    private var outH = 0
    private var target: Pair<Int, Int>? = null // surface size we are waiting for after setFixedSize
    private var drawer: DrawerOverlay? = null
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var lastViewW = 0
    private var lastViewH = 0

    /** Android 11+ on an arm64 device whose libfluxa loaded. */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && FluxaNative.ensureLoaded()

    // ---------------------------------------------------------------- user actions

    fun toggle() {
        if (wanted) {
            if (drawer?.isOpen == true) drawer?.closeDrawer() else drawer?.openDrawer()
        } else {
            start(openDrawer = true)
        }
    }

    /** Called when the player starts: comes back on if it was left on. */
    fun restoreIfWanted() {
        if (!wanted && prefs.getBoolean(K_AUTO, false) && supported) start(openDrawer = false)
    }

    fun start(openDrawer: Boolean) {
        if (wanted) {
            if (openDrawer) drawer?.openDrawer()
            return
        }
        if (!supported) {
            toast("เฟรมเจนใช้ได้บน Android 11 ขึ้นไป (arm64) เท่านั้น")
            return
        }
        if (CaptureService.running) {
            toast("เฟรมเจนแบบจับหน้าจอกำลังทำงานอยู่ · หยุดก่อนแล้วค่อยเปิดในเครื่องเล่น")
            return
        }
        Fluxa.install(activity, readOptions())
        Fluxa.applyDisplayMode(activity)
        wanted = true
        _active.value = true
        prefs.edit().putBoolean(K_AUTO, true).apply()
        watchLayout()
        attachDrawer()
        beginRouting()
        if (openDrawer) drawer?.openDrawer()
    }

    /** Turns it off for good (the drawer's stop button, or a failure). mpv gets its own surface back. */
    fun stop() {
        if (!wanted && !routing) return
        wanted = false
        _active.value = false
        prefs.edit().putBoolean(K_AUTO, false).apply()
        generation++
        handler.removeCallbacksAndMessages(null)
        unwatchLayout()
        drawer?.detach()
        drawer = null
        val g = generation
        val wasRouting = routing
        routing = false
        pipelineUp = false
        target = null
        if (wasRouting) host.detachMpvSurface()
        handler.postDelayed({
            if (g != generation) return@postDelayed
            Fluxa.release()
            Fluxa.install(activity, FluxaOptions()) // not requested any more: also drops the display-mode vote
            Fluxa.applyDisplayMode(activity)
            val holder = host.outputView.holder
            holder.setSizeFromLayout()
            if (holder.surface.isValid) host.attachMpvDirect()
        }, DETACH_MS)
    }

    /** Activity going away: free the native side without touching the saved "on" preference. */
    fun release() {
        generation++
        handler.removeCallbacksAndMessages(null)
        unwatchLayout()
        drawer?.detach()
        drawer = null
        wanted = false
        routing = false
        pipelineUp = false
        _active.value = false
        Fluxa.release()
    }

    /** Back button while the drawer is open closes it instead of leaving the player. */
    fun handleBack(): Boolean {
        val d = drawer ?: return false
        if (!d.isOpen) return false
        d.closeDrawer()
        return true
    }

    /** Entering picture-in-picture etc.: the drawer must not stay on screen. */
    fun hideDrawer() {
        drawer?.closeDrawer()
    }

    /** The file changed or restarted: pick up its real frame rate. */
    fun refreshSourceRate() {
        if (!pipelineUp) return
        val fps = host.videoFps()
        if (fps > 1f) {
            Fluxa.setSourceRate(fps)
            Fluxa.applyDisplayMode(activity)
        }
    }

    // ---------------------------------------------------------------- surface callbacks (from the player view)

    fun onOutputSurfaceCreated() {
        if (wanted && !routing) beginRouting()
    }

    fun onOutputSurfaceChanged(width: Int, height: Int) {
        if (!wanted) return
        val t = target
        if (t != null && width == t.first && height == t.second) {
            target = null
            buildPipeline(generation)
            return
        }
        if (!routing) beginRouting()
    }

    fun onOutputSurfaceDestroyed() {
        if (!wanted) return
        // the activity is stopping or the view was recreated: drop the presenter, come back on create
        generation++
        handler.removeCallbacksAndMessages(null)
        routing = false
        pipelineUp = false
        target = null
        Fluxa.release()
    }

    // ---------------------------------------------------------------- pipeline

    private fun beginRouting() {
        val view = host.outputView
        val holder = view.holder
        if (!holder.surface.isValid || view.width <= 0 || view.height <= 0) return // wait for the surface callbacks
        generation++
        val g = generation
        attempts = 0
        routing = true
        pipelineUp = false
        target = null
        computeSizes(view.width, view.height)
        lastViewW = view.width
        lastViewH = view.height
        // mpv must let go of the surface before the presenter's swapchain can connect to it
        host.detachMpvSurface()
        handler.postDelayed({
            if (g != generation || !wanted) return@postDelayed
            Fluxa.release() // old presenter (rotation / size change) goes before the surface size changes
            applySurfaceSize(g)
        }, DETACH_MS)
    }

    /** Surface buffer size the presenter should output at: the upscaled size, the source size, or the view's own. */
    private fun desiredSurface(viewW: Int, viewH: Int): Pair<Int, Int>? = when {
        outW > 0 -> outW to outH
        srcW != viewW || srcH != viewH -> srcW to srcH
        else -> null // size from layout
    }

    private fun applySurfaceSize(g: Int) {
        if (g != generation || !wanted) return
        val view = host.outputView
        val holder = view.holder
        if (!holder.surface.isValid) return
        val want = desiredSurface(view.width, view.height)
        val frame = holder.surfaceFrame
        val matches = if (want == null) {
            frame.width() == view.width && frame.height() == view.height
        } else {
            frame.width() == want.first && frame.height() == want.second
        }
        if (matches) {
            buildPipeline(g)
            return
        }
        target = want ?: (view.width to view.height)
        if (want != null) holder.setFixedSize(want.first, want.second) else holder.setSizeFromLayout()
        // surfaceChanged should deliver the size; if it never does, go ahead with what we have
        handler.postDelayed({
            if (g == generation && wanted && !pipelineUp) {
                target = null
                buildPipeline(g)
            }
        }, 1500)
    }

    private fun buildPipeline(g: Int) {
        if (g != generation || !wanted || pipelineUp) return
        val holder = host.outputView.holder
        val output = holder.surface
        if (!output.isValid) return
        val frame = holder.surfaceFrame
        val sw = frame.width()
        val sh = frame.height()
        if (sw <= 0 || sh <= 0) return

        val cfg = DisplayTuner.config(activity)
        val upscaled = outW > 0 && sw == outW && sh == outH
        val sW = if (upscaled) srcW else sw
        val sH = if (upscaled) srcH else sh
        val producer = Fluxa.wrap(
            output, sW, sH, if (upscaled) sw else 0, if (upscaled) sh else 0,
            cfg.upscaleFilter, cfg.upscaleSharp, cfg.upscaleSharpMethod,
        )
        if (producer == null || producer === output) {
            // mpv may not have released the surface yet: retry a few times before giving up
            if (++attempts <= MAX_ATTEMPTS) {
                Log.w(TAG, "presenter did not start (attempt $attempts), retrying")
                handler.postDelayed({ buildPipeline(g) }, RETRY_MS)
                return
            }
            Log.w(TAG, "frame generation presenter did not start for the player")
            toast("เริ่มเฟรมเจนในเครื่องเล่นไม่ได้ (ดู logcat tag Fluxa)")
            handler.post { stop() }
            return
        }

        host.attachMpvSurface(producer, sW, sH)
        pipelineUp = true
        Fluxa.applyDisplayMode(activity)
        refreshSourceRate()
        // the container fps is not always known right away
        handler.postDelayed({ if (g == generation) refreshSourceRate() }, 1500)
        handler.postDelayed({ if (g == generation) refreshSourceRate() }, 5000)
    }

    /**
     * Render (source) size = view x render %, only when the app's "narrow the screen" switch is on;
     * otherwise the view size. Output size = view x output %, used when it is larger than the source.
     */
    private fun computeSizes(viewW: Int, viewH: Int) {
        val cfg = DisplayTuner.config(activity)
        fun even(v: Int) = maxOf(2, v / 2 * 2)
        val renderPct = if (cfg.enabled) cfg.percent else 100
        val sW = even(viewW * renderPct / 100)
        val sH = even(viewH * renderPct / 100)
        var oW = 0
        var oH = 0
        if (cfg.upscale) {
            var w = even(viewW * cfg.outPercent / 100)
            var h = even(viewH * cfg.outPercent / 100)
            val area = w.toLong() * h
            if (area > DisplayTuner.MAX_OUT_PIXELS) {
                val k = Math.sqrt(DisplayTuner.MAX_OUT_PIXELS.toDouble() / area)
                w = even((w * k).toInt())
                h = even((h * k).toInt())
            }
            if (w > sW && h > sH) {
                oW = w
                oH = h
            }
        }
        srcW = sW
        srcH = sH
        outW = oW
        outH = oH
    }

    /** Rebuilds the pipeline (rotation, output % changed in the drawer, view resized). */
    private fun rebuild() {
        if (!wanted || !routing) return
        val view = host.outputView
        if (!view.holder.surface.isValid || view.width <= 0 || view.height <= 0) return
        pipelineUp = false
        beginRouting()
    }

    private val rebuildRunnable = Runnable { rebuild() }

    private fun scheduleRebuild() {
        handler.removeCallbacks(rebuildRunnable)
        handler.postDelayed(rebuildRunnable, REBUILD_DEBOUNCE_MS)
    }

    private fun watchLayout() {
        if (layoutListener != null) return
        val l = View.OnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (wanted && routing && (w != lastViewW || h != lastViewH) && w > 0 && h > 0) {
                scheduleRebuild()
            }
        }
        layoutListener = l
        host.outputView.addOnLayoutChangeListener(l)
    }

    private fun unwatchLayout() {
        layoutListener?.let { host.outputView.removeOnLayoutChangeListener(it) }
        layoutListener = null
    }

    // ---------------------------------------------------------------- drawer + options

    private fun attachDrawer() {
        if (drawer != null) return
        val (w, h) = windowSize()
        val d = DrawerOverlay(
            host = activity,
            wm = activity.windowManager,
            screenW = w,
            screenH = h,
            onStop = { handler.post { stop() } },
            onSettingsChanged = { Fluxa.applyDisplayMode(activity) },
            onOutputPercentChanged = { scheduleRebuild() },
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            showEdge = false,
            sizeProvider = { windowSize() },
        )
        d.attach()
        drawer = d
    }

    private fun windowSize(): Pair<Int, Int> {
        val decor = activity.window.decorView
        if (decor.width > 0 && decor.height > 0) return decor.width to decor.height
        val m = activity.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /** Same keys the Fluxa app screen saves, so what was set there applies here too. */
    private fun readOptions(): FluxaOptions = FluxaOptions(
        enabled = true,
        multiplier = FluxaOptions.clampMultiplier(prefs.getInt(KEY_MULT_X, FluxaOptions.DEFAULT_MULTIPLIER)),
        flowSide = FluxaOptions.clampFlowSide(prefs.getInt(KEY_FLOW_SIDE, FluxaOptions.DEFAULT_FLOW_SIDE)),
        driverName = null,
        presentMode = if (prefs.getInt(KEY_PRESENT, 0) == 1) FluxaOptions.PRESENT_MAILBOX else FluxaOptions.PRESENT_FIFO,
        genMode = FluxaOptions.clampGenMode(prefs.getInt(KEY_GEN_MODE, FluxaOptions.GEN_MODE_FULL)),
        targetFps = FluxaOptions.clampTargetFps(prefs.getInt(KEY_TARGET_FPS, FluxaOptions.DEFAULT_TARGET_FPS)),
        targetMode = prefs.getInt(KEY_OUT_MODE, 0) == FluxaOptions.OUT_MODE_TARGET,
    )

    private fun toast(text: String) {
        Log.w(TAG, "toast: $text")
        handler.post { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val TAG = "FluxaPlayer"
        const val K_AUTO = "pl_auto"
        const val KEY_MULT_X = "ui_mult_x"
        const val KEY_PRESENT = "ui_present"
        const val KEY_GEN_MODE = "ui_gen_mode"
        const val KEY_TARGET_FPS = "ui_target_fps"
        const val KEY_OUT_MODE = "ui_out_mode"
        const val KEY_FLOW_SIDE = "ui_flow_side"

        const val DETACH_MS = 150L
        const val RETRY_MS = 200L
        const val MAX_ATTEMPTS = 5
        const val REBUILD_DEBOUNCE_MS = 250L
    }
}
