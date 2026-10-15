package app.fluxa

import eu.kanade.tachiyomi.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast

/**
 * Screen (single app) -> MediaProjection -> Fluxa producer surface -> native DIS presenter
 * -> output SurfaceView in a click-through overlay window.
 *
 * Mode "อัดจอ": the same presenter additionally feeds every frame it shows (generated + real)
 * into a [ScreenRecorder] (mp4). No extra capture path is created.
 */
class CaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var windowManager: WindowManager? = null
    private var overlay: SurfaceView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var drawer: DrawerOverlay? = null
    private var pipelineUp = false
    private var stopping = false
    private var modeId = 0
    private var recordOptions = RecordOptions()
    private var recorder: ScreenRecorder? = null
    private var overlayW = 0 // logical display size = frame generation (source) size
    private var overlayH = 0
    private var outW = 0 // upscaled output size (0 = no upscale)
    private var outH = 0
    private var tuned = false // we changed the display size / DPI and must restore it
    private var overlayGen = 0 // bumped on every overlay rebuild so stale surface callbacks are ignored
    private var resizingTo: Pair<Int, Int>? = null // surface size we are waiting for after a live output-% change

    // 0-fps watchdog
    private var lastReal = -1L
    private var lastChangeAt = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            handler.removeCallbacks(rotationCheck)
            handler.postDelayed(rotationCheck, 200)
        }
    }

    private val outputResize = Runnable { applyOutputPercentNow() }

    private val rotationCheck = Runnable {
        if (stopping || overlay == null) return@Runnable
        val (w, h) = currentSize()
        if (w != overlayW || h != overlayH) rebuildOverlay()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopping) return
            val now = SystemClock.elapsedRealtime()
            if (Fluxa.active) {
                val real = Fluxa.realFrames()
                if (real != lastReal) {
                    lastReal = real
                    lastChangeAt = now
                } else if (now - lastChangeAt >= ZERO_FPS_TIMEOUT_MS) {
                    Log.w(TAG, "0 fps for ${ZERO_FPS_TIMEOUT_MS / 1000}s, stopping")
                    toast("FPS เป็น 0 นานเกิน 20 วินาที หยุด ${getString(R.string.fluxa_app_name)} อัตโนมัติ")
                    shutdown()
                    return
                }
            } else {
                // presenter being rebuilt (rotation etc.): don't count it as a freeze
                lastReal = -1L
                lastChangeAt = now
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LogStore.installCrashHandler(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (projection != null) return
        running = true
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            stopSelf()
            return
        }

        recordOptions = RecordOptions.fromIntent(intent)
        startForegroundWithNotification()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data)
        if (mp == null) {
            toast("เริ่มจับภาพหน้าจอไม่ได้")
            shutdown()
            return
        }
        // Android 14+: the callback must be registered before createVirtualDisplay.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { shutdown() }
            }
        }, handler)
        projection = mp

        modeId = intent.getIntExtra(EXTRA_DISPLAY_MODE_ID, 0)

        // optional: lower the screen resolution + DPI first (needs WRITE_SECURE_SETTINGS)
        val plan = DisplayTuner.planForStart(this)
        if (plan != null) {
            if (DisplayTuner.apply(this, plan)) {
                tuned = true
                waitForDisplay(plan) { continueStart(mp) }
                return
            }
            toast("ปรับความละเอียด/DPI ไม่ได้ (ดู Log) · ใช้ค่าเดิมต่อ")
        } else if (DisplayTuner.config(this).enabled && !DisplayTuner.canWrite(this)) {
            toast("ยังไม่ได้ให้สิทธิ์ WRITE_SECURE_SETTINGS · ข้ามการปรับความละเอียด")
        }
        continueStart(mp)
    }

    /** Waits (max ~3 s) until the display reports the new size, then runs [then]. */
    private fun waitForDisplay(plan: DisplayTuner.Plan, then: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        val check = object : Runnable {
            override fun run() {
                if (stopping) return
                val (w, h) = currentSize()
                val match = maxOf(w, h) == maxOf(plan.width, plan.height) &&
                    minOf(w, h) == minOf(plan.width, plan.height)
                if (match || SystemClock.elapsedRealtime() >= deadline) {
                    // let the window manager / SurfaceFlinger settle before the overlay is created
                    handler.postDelayed({ if (!stopping) then() }, 300)
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.post(check)
    }

    private fun continueStart(mp: MediaProjection) {
        if (stopping) return
        addOverlay()
        if (stopping) return
        if (recordOptions.enabled) startRecorder(mp)

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        lastReal = -1L
        lastChangeAt = SystemClock.elapsedRealtime()
        handler.postDelayed(watchdog, 1000)
    }

    /**
     * Output size the frames are upscaled to: the original resolution times the chosen output
     * percentage, or 0 x 0 for none (also when that is not larger than the render size).
     */
    private fun outputSizeFor(w: Int, h: Int): Pair<Int, Int> {
        val cfg = DisplayTuner.config(this)
        if (!cfg.upscale) return 0 to 0
        val o = DisplayTuner.original(this) ?: return 0 to 0
        val long = maxOf(o.width, o.height)
        val short = minOf(o.width, o.height)
        val (bw, bh) = if (w >= h) long to short else short to long
        var ow = maxOf(2, (bw * cfg.outPercent / 100) / 2 * 2)
        var oh = maxOf(2, (bh * cfg.outPercent / 100) / 2 * 2)
        // 200 % outputs are 4x the pixels: keep the area (and the GPU memory) bounded, same aspect
        val area = ow.toLong() * oh
        if (area > DisplayTuner.MAX_OUT_PIXELS) {
            val k = Math.sqrt(DisplayTuner.MAX_OUT_PIXELS.toDouble() / area)
            ow = maxOf(2, (ow * k).toInt() / 2 * 2)
            oh = maxOf(2, (oh * k).toInt() / 2 * 2)
            Log.w(TAG, "output capped to ${ow}x$oh (limit ${DisplayTuner.MAX_OUT_PIXELS} px)")
        }
        return if (ow > w && oh > h) ow to oh else 0 to 0
    }

    private fun currentDpi(): Int {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return resources.displayMetrics.densityDpi
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m.densityDpi
    }

    /** Mode "อัดจอ": opens the mp4 + encoders; the presenter then feeds them (see Fluxa.setRecordTarget). */
    private fun startRecorder(mp: MediaProjection) {
        val (cw, ch) = currentSize()
        val (ow, oh) = outputSizeFor(cw, ch)
        val w = if (ow > 0) ow else cw // record the upscaled picture when upscaling
        val h = if (ow > 0) oh else ch
        val rec = ScreenRecorder(applicationContext, mp, recordOptions, w, h) { toast(it) }
        if (rec.start()) {
            recorder = rec
        } else {
            toast("อัดจอไม่ได้ ทำงานในโหมดปกติแทน")
        }
    }

    /** Real display size in the current rotation (maximumWindowMetrics can lag behind on non-visual contexts). */
    private fun currentSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** Screen rotated: rebuild overlay + presenter at the new size. The projection/VirtualDisplay stay alive. */
    private fun rebuildOverlay() {
        overlayGen++ // ignore callbacks from the view we're about to remove
        resizingTo = null
        drawer?.detach()
        drawer = null
        stopPresenter()
        overlay?.let { v -> runCatching { windowManager?.removeViewImmediate(v) } }
        overlay = null
        addOverlay()
    }

    /**
     * Drawer changed the output % live. Debounced; the presenter is rebuilt at the new output size
     * on the same overlay view (the drawer stays open, the VirtualDisplay is reused like on rotation).
     */
    private fun onOutputPercentChanged() {
        handler.removeCallbacks(outputResize)
        handler.postDelayed(outputResize, 250)
    }

    private fun applyOutputPercentNow() {
        val view = overlay ?: return
        if (stopping || recorder != null) return // the recording encoder has a fixed size
        val (ow, oh) = outputSizeFor(overlayW, overlayH)
        if (ow == outW && oh == outH) return
        Log.i(TAG, "output % changed live: ${outW}x$outH -> ${ow}x$oh")
        val gen = overlayGen
        stopPresenter()
        outW = ow
        outH = oh
        resizingTo = if (ow > 0) ow to oh else overlayW to overlayH
        if (ow > 0) view.holder.setFixedSize(ow, oh) else view.holder.setSizeFromLayout()
        // surfaceChanged should rebuild the pipeline; if it never comes, fall back to the rotation path
        handler.postDelayed({
            if (stopping || gen != overlayGen || pipelineUp) return@postDelayed
            Log.w(TAG, "no surface change after output % switch, rebuilding overlay")
            resizingTo = null
            rebuildOverlay()
        }, 1500)
    }

    /** Picks a display mode that fits the (possibly changed) multiplier and applies it to the overlay window. */
    private fun applyDisplayMode() {
        val view = overlay ?: return
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val id = Fluxa.selectDisplayMode(display)
        if (id == 0 || id == modeId) return
        modeId = id
        overlayParams?.let { lp ->
            lp.preferredDisplayModeId = id
            runCatching { windowManager?.updateViewLayout(view, lp) }
        }
    }

    private fun addOverlay() {
        val host = OverlayAccessibilityService.instance
        if (host == null) {
            toast("ยังไม่ได้เปิดบริการการช่วยเหลือพิเศษของ ${getString(R.string.fluxa_app_name)}")
            shutdown()
            return
        }
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val (w, h) = currentSize()
        overlayW = w
        overlayH = h
        val (ow, oh) = outputSizeFor(w, h)
        outW = ow
        outH = oh
        val gen = ++overlayGen

        val view = SurfaceView(host)
        // fixed buffer size larger than the window: the presenter renders at the original resolution
        if (ow > 0) view.holder.setFixedSize(ow, oh)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (gen != overlayGen) return
                resizingTo?.let { want ->
                    if (width != want.first || height != want.second) return // stale size, wait for the new one
                    resizingTo = null
                }
                if (width > 0 && height > 0) beginPipeline(holder, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (gen != overlayGen) return
                stopPresenter()
            }
        })

        val lp = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // touches go to the app underneath
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (modeId != 0) lp.preferredDisplayModeId = modeId

        overlay = view
        overlayParams = lp
        wm.addView(view, lp)

        // added after the output overlay so the drawer sits above it
        drawer = DrawerOverlay(
            host, wm, w, h,
            onStop = { handler.post { shutdown() } },
            onSettingsChanged = { applyDisplayMode() },
            onOutputPercentChanged = { onOutputPercentChanged() },
            outputLocked = { recorder != null },
            recordElapsedMs = { recorder?.takeIf { it.isRecording }?.elapsedMs ?: -1L },
        ).also { it.attach() }
    }

    private fun beginPipeline(holder: SurfaceHolder, width: Int, height: Int) {
        if (pipelineUp) return
        val mp = projection ?: return
        val output = holder.surface

        // upscaling: the surface is the (large) output size, generation runs at the logical display size
        val upscaled = outW > 0 && width == outW && height == outH
        val srcW = if (upscaled) overlayW else width
        val srcH = if (upscaled) overlayH else height
        val producer = Fluxa.wrap(
            output, srcW, srcH, if (upscaled) width else 0, if (upscaled) height else 0,
            DisplayTuner.config(this).upscaleFilter,
            DisplayTuner.config(this).upscaleSharp,
            DisplayTuner.config(this).upscaleSharpMethod,
        )
        if (producer == null || producer === output) {
            // wrap() hands the output back untouched when the presenter could not start.
            Log.w(TAG, "frame generation presenter did not start")
            toast("เริ่ม Fluxa ไม่ได้ (ดู logcat tag Fluxa)")
            handler.post { shutdown() }
            return
        }

        val dpi = currentDpi()
        val existing = virtualDisplay
        if (existing == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "fluxa-capture", srcW, srcH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                producer, null, null,
            )
        } else {
            // Android 14+ allows only one createVirtualDisplay per projection token -> reuse it on rotation.
            existing.resize(srcW, srcH, dpi)
            existing.setSurface(producer)
        }
        pipelineUp = virtualDisplay != null
        if (!pipelineUp) {
            toast("สร้าง virtual display ไม่ได้")
            handler.post { shutdown() }
            return
        }
    }

    /** Detaches the capture from the presenter and destroys the presenter; keeps the VirtualDisplay. */
    private fun stopPresenter() {
        runCatching { virtualDisplay?.setSurface(null) }
        Fluxa.release()
        pipelineUp = false
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        overlayGen++
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(rotationCheck)
        handler.removeCallbacks(outputResize)
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        drawer?.detach()
        drawer = null
        // stop feeding the encoder while the presenter still exists, then tear the pipeline down
        val rec = recorder
        recorder = null
        rec?.detachFeed()
        stopPresenter()
        virtualDisplay?.release()
        virtualDisplay = null
        overlay?.let { v -> runCatching { windowManager?.removeViewImmediate(v) } }
        overlay = null
        if (rec == null) {
            projection?.stop()
            projection = null
            finishService()
            return
        }
        // finish the mp4 first (app-audio capture needs the projection alive until the last sample)
        rec.stopAsync { uri ->
            handler.post {
                if (uri != null) {
                    toast("บันทึกวิดีโอแล้ว (Movies/${getString(R.string.fluxa_app_name)})")
                }
                projection?.stop()
                projection = null
                finishService()
            }
        }
    }

    private fun finishService() {
        if (tuned) {
            tuned = false
            DisplayTuner.restore(this) // put the original resolution + DPI back
        }
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) shutdown()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.fluxa_app_name), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val recording = recordOptions.enabled
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fluxa)
            .setColor(0xFFF27A1A.toInt())
            .setContentTitle("${getString(R.string.fluxa_app_name)} ${if (recording) "กำลังอัดจอ" else "กำลังทำงาน"}")
            .setContentText("แตะเพื่อหยุด")
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "หยุด", stop).build())
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (recording &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, notification, type)
    }

    private fun toast(text: String) {
        Log.w(TAG, "toast: $text") // errors shown as toasts also land in the log viewer
        showToast(text)
    }

    private fun showToast(text: String) =
        handler.post { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }

    companion object {
        private const val TAG = "FluxaService"
        private const val CHANNEL = "fluxa"
        private const val NOTIF_ID = 1
        private const val ZERO_FPS_TIMEOUT_MS = 20_000L

        /** True from start until the service has finished (used to tell a dead session from a live one). */
        @Volatile var running = false
            private set
        const val ACTION_START = "app.fluxa.START"
        const val ACTION_STOP = "app.fluxa.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DISPLAY_MODE_ID = "display_mode_id"
    }
}
