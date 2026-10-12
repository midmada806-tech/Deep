package app.fluxa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager

object Fluxa {
    private const val TAG = "Fluxa"
    private const val CADENCE_EPSILON = 0.01f
    private const val SOURCE_WINDOW_NANOS = 500_000_000L
    private const val SOURCE_RATE_EPSILON = 1.5f
    // Assumed game fps until the real one has been measured (then the measured value takes over).
    private const val ASSUMED_SOURCE_RATE = 60f

    private var appContext: Context? = null
    private var options = FluxaOptions()
    private var handle = 0L
    private var producer: Surface? = null
    private var boundOutput: Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0
    private var boundOutWidth = 0
    private var boundOutHeight = 0
    private var upscaleMode = DisplayTuner.FILTER_LINEAR
    private var upscaleSharp = 0
    private var upscaleSharpMethod = DisplayTuner.SHARP_RCAS
    private var enhDeband = 0
    private var enhDenoise = 0
    private var enhAa = DisplayTuner.AA_OFF
    private var enhDither = false
    private var refreshRate = 0f
    private var generatingEnabled = true
    private var rebinder: Runnable? = null
    private var stateListener: Runnable? = null
    private var liveSourceRate = 0f
    private var sourceFrames = 0L
    private var sourceWindow = 0L

    // Screen recording target (encoder input surface). Survives presenter rebuilds (rotation):
    // wrap() re-attaches it to every new presenter.
    private var recordSurface: Surface? = null
    private var recordWidth = 0
    private var recordHeight = 0

    val requested: Boolean
        @Synchronized get() = options.usable

    val active: Boolean
        @Synchronized get() = handle != 0L

    val generating: Boolean
        @Synchronized get() = generatingEnabled

    val multiplier: Int
        @Synchronized get() = options.multiplier

    val flowSide: Int
        @Synchronized get() = options.flowSide

    val presentMode: Int
        @Synchronized get() = options.presentMode

    @JvmStatic
    @Synchronized
    fun install(context: Context, source: FluxaOptions) {
        appContext = context.applicationContext
        if (options != source) release()
        options = source
        generatingEnabled = true
        if (options.usable) FluxaNative.ensureLoaded()
        notifyState()
    }

    @JvmStatic
    fun installFromIntent(context: Context, intent: Intent?) {
        install(context, FluxaOptions.fromIntent(intent))
    }

    @JvmStatic
    @Synchronized
    fun wrap(output: Surface?, width: Int, height: Int, outWidth: Int = 0, outHeight: Int = 0,
             mode: Int = DisplayTuner.FILTER_LINEAR, sharp: Int = 0,
             sharpMethod: Int = DisplayTuner.SHARP_RCAS): Surface? {
        if (output == null || !output.isValid) return output
        if (!options.usable || width <= 0 || height <= 0) return output
        if (handle != 0L && boundOutput === output && boundWidth == width && boundHeight == height &&
            boundOutWidth == outWidth && boundOutHeight == outHeight
        ) {
            return producer ?: output
        }

        releaseLocked()
        upscaleMode = mode.coerceIn(DisplayTuner.FILTER_NEAREST, DisplayTuner.FILTER_LANCZOS3)
        upscaleSharp = sharp.coerceIn(0, 100)
        upscaleSharpMethod = sharpMethod.coerceIn(DisplayTuner.SHARP_RCAS, DisplayTuner.SHARP_ADAPTIVE)

        val context = appContext ?: return output
        DisplayTuner.config(context).let {
            enhDeband = it.deband
            enhDenoise = it.denoise
            enhAa = it.aa
            enhDither = it.dither
        }
        if (!FluxaNative.ensureLoaded()) {
            Log.w(TAG, "native library unavailable; frame generation stays off")
            return output
        }

        val created =
            runCatching {
                FluxaNative.nativeCreate(
                    context,
                    output,
                    width,
                    height,
                    outWidth,
                    outHeight,
                    options.driverName,
                    options.multiplier,
                    options.flowSide,
                    refreshRate,
                    sourceRate(),
                    options.presentMode,
                    upscaleMode,
                    packedSharp(),
                )
            }.getOrElse {
                Log.w(TAG, "frame generation could not start: ${it.message}")
                0L
            }
        if (created == 0L) {
            Log.w(TAG, "frame generation presenter unavailable for ${width}x$height; frames pass through")
            return output
        }

        val surface = runCatching { FluxaNative.nativeProducerSurface(created) }.getOrNull()
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "frame generation producer surface invalid; frames pass through")
            FluxaNative.nativeDestroy(created)
            return output
        }

        handle = created
        producer = surface
        boundOutput = output
        boundWidth = width
        boundHeight = height
        boundOutWidth = outWidth
        boundOutHeight = outHeight
        runCatching { FluxaNative.nativeCapabilities(created) }.onSuccess { caps ->
            appContext?.let { DisplayTuner.setCubicSupported(it, (caps and 1) != 0) }
        }
        voteFrameRate()
        attachRecordLocked()
        Log.i(TAG, "frame generation active ${width}x$height -> ${if (outWidth > 0) outWidth else width}x${if (outHeight > 0) outHeight else height} multiplier=${options.multiplier} " +
            "flowSide=${options.flowSide} refresh=$refreshRate " +
            "present=${if (options.presentMode == FluxaOptions.PRESENT_MAILBOX) "MAILBOX" else "FIFO"}")
        return surface
    }

    @JvmStatic
    @Synchronized
    fun noteSourceFrames(count: Int) {
        if (count <= 0 || handle == 0L) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (sourceWindow == 0L) {
            sourceWindow = now
            sourceFrames = 0L
            return
        }
        sourceFrames += count.toLong()
        val elapsed = now - sourceWindow
        if (elapsed < SOURCE_WINDOW_NANOS) return
        val rate = sourceFrames * 1_000_000_000f / elapsed
        sourceWindow = now
        sourceFrames = 0L
        if (rate <= 1f || Math.abs(rate - liveSourceRate) < SOURCE_RATE_EPSILON) return
        liveSourceRate = rate
        pushConfig()
    }

    @JvmStatic
    @Synchronized
    fun setSurfaceRebinder(value: Runnable?) {
        rebinder = value
    }

    @JvmStatic
    @Synchronized
    fun setStateListener(value: Runnable?) {
        stateListener = value
    }

    private fun notifyState() {
        val target = synchronized(this) { stateListener } ?: return
        runCatching { target.run() }
    }

    @JvmStatic
    fun rebindSurface() {
        val target = synchronized(this) { rebinder } ?: return
        runCatching { target.run() }
            .onFailure { Log.w(TAG, "frame generation could not rebind the surface: ${it.message}") }
    }

    @JvmStatic
    @Synchronized
    fun setGenerating(on: Boolean) {
        generatingEnabled = on
        pushConfig()
        notifyState()
    }

    @JvmStatic
    @Synchronized
    fun reconfigure(multiplier: Int, flowSide: Int) {
        options = options.copy(
            multiplier = FluxaOptions.clampMultiplier(multiplier),
            flowSide = FluxaOptions.clampFlowSide(flowSide),
        )
        pushConfig()
    }

    /** Switches FIFO / MAILBOX live; the native side rebuilds the swapchain. */
    @JvmStatic
    @Synchronized
    fun setPresentMode(mode: Int) {
        options = options.copy(presentMode = FluxaOptions.clampPresentMode(mode))
        pushConfig()
    }

    /** Switches the upscale filter / sharpen strength / sharpen pass live (only matters while upscaling). */
    @JvmStatic
    @Synchronized
    fun setUpscale(mode: Int, sharp: Int, sharpMethod: Int = upscaleSharpMethod) {
        upscaleMode = mode.coerceIn(DisplayTuner.FILTER_NEAREST, DisplayTuner.FILTER_LANCZOS3)
        upscaleSharp = sharp.coerceIn(0, 100)
        upscaleSharpMethod = sharpMethod.coerceIn(DisplayTuner.SHARP_RCAS, DisplayTuner.SHARP_ADAPTIVE)
        pushConfig()
    }

    /**
     * Switches deband / denoise-deblock / anti-aliasing / dither live. Unlike the upscale filter
     * these also run when the output is not upscaled.
     */
    @JvmStatic
    @Synchronized
    fun setEnhance(deband: Int, denoise: Int, aa: Int, dither: Boolean) {
        enhDeband = deband.coerceIn(0, 100)
        enhDenoise = denoise.coerceIn(0, 100)
        enhAa = aa.coerceIn(DisplayTuner.AA_OFF, DisplayTuner.AA_SMAA)
        enhDither = dither
        pushConfig()
    }

    /**
     * Native side takes strength (bits 0..7), sharpen pass (bits 8..11) and the enhancement fields
     * (deband 12..18, denoise 19..25, AA 26..27, dither 28) in one int.
     */
    private fun packedSharp(): Int =
        upscaleSharp or (upscaleSharpMethod shl 8) or
            DisplayTuner.packEnhance(enhDeband, enhDenoise, enhAa, enhDither)

    val currentUpscaleMode: Int
        @Synchronized get() = upscaleMode

    val currentUpscaleSharp: Int
        @Synchronized get() = upscaleSharp

    val currentUpscaleSharpMethod: Int
        @Synchronized get() = upscaleSharpMethod

    /** Output rate the current settings ask for, using the live source rate. */
    @JvmStatic
    @Synchronized
    fun wantedHz(): Int = wantedRate()

    private fun wantedRate(): Int = Math.round(sourceRate() * options.multiplier)

    private fun voteFrameRate() {
        val output = boundOutput ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val wanted = if (generatingEnabled) wantedRate().toFloat() else refreshRate
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                output.setFrameRate(
                    wanted,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                output.setFrameRate(wanted, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }

    private fun sourceRate(): Float =
        if (liveSourceRate > 1f) liveSourceRate else ASSUMED_SOURCE_RATE

    private fun pushConfig() {
        voteFrameRate()
        if (handle == 0L) return
        val multiplier = if (generatingEnabled) options.multiplier else 1
        val outcome =
            runCatching {
                FluxaNative.nativeConfigure(
                    handle,
                    multiplier,
                    options.flowSide,
                    refreshRate,
                    sourceRate(),
                    options.presentMode,
                    upscaleMode,
                    packedSharp(),
                )
            }
        Log.i(TAG, "frame generation reconfigured multiplier=$multiplier " +
            "flowSide=${options.flowSide} refresh=$refreshRate ok=${outcome.isSuccess}")
    }

    /**
     * Starts (non-null [surface]) or stops (null) recording the frames the presenter shows.
     * Uses the existing presenter path: no second capture. Safe to call before the presenter exists.
     */
    @JvmStatic
    @Synchronized
    fun setRecordTarget(surface: Surface?, width: Int, height: Int) {
        if (surface == null || !surface.isValid) {
            recordSurface = null
            recordWidth = 0
            recordHeight = 0
            if (handle != 0L) runCatching { FluxaNative.nativeRecordStop(handle) }
            return
        }
        recordSurface = surface
        recordWidth = width
        recordHeight = height
        attachRecordLocked()
    }

    private fun attachRecordLocked() {
        val target = recordSurface ?: return
        if (handle == 0L || !target.isValid) return
        val live = runCatching {
            FluxaNative.nativeRecordStart(handle, target, recordWidth, recordHeight)
        }.getOrDefault(false)
        Log.i(TAG, "screen record feed ${recordWidth}x$recordHeight live=$live")
    }

    /** Size of the video being recorded (0 x 0 when not recording). */
    @JvmStatic
    @Synchronized
    fun recordSize(): Pair<Int, Int> = recordWidth to recordHeight

    /** Burns the HUD bitmap into the recorded video; null hides it. Safe before the presenter exists. */
    @JvmStatic
    @Synchronized
    fun setRecordHud(rgba: ByteArray?, width: Int, height: Int, marginX: Int, marginY: Int) {
        if (handle == 0L) return
        runCatching { FluxaNative.nativeRecordHud(handle, rgba, width, height, marginX, marginY) }
    }

    @JvmStatic
    @Synchronized
    fun recordedFrames(): Long =
        if (handle == 0L) 0L
        else runCatching { FluxaNative.nativeRecordedFrames(handle) }.getOrDefault(0L)

    /** Rate the screen shows frames at (source x multiplier when generating), for video timing. */
    @JvmStatic
    @Synchronized
    fun outputHz(): Int = if (generatingEnabled) wantedRate() else Math.round(sourceRate())

    @JvmStatic
    @Synchronized
    fun realFrames(): Long =
        if (handle == 0L) 0L else runCatching { FluxaNative.nativeRealFrames(handle) }.getOrDefault(0L)

    @JvmStatic
    @Synchronized
    fun generatedFrames(): Long =
        if (handle == 0L) 0L
        else runCatching { FluxaNative.nativeGeneratedFrames(handle) }.getOrDefault(0L)

    @JvmStatic
    @Synchronized
    fun release() = releaseLocked()

    private fun releaseLocked() {
        if (handle != 0L) {
            runCatching { FluxaNative.nativeDestroy(handle) }
            handle = 0L
        }
        producer?.let { runCatching { it.release() } }
        producer = null
        boundOutput = null
        liveSourceRate = 0f
        sourceFrames = 0L
        sourceWindow = 0L
        boundWidth = 0
        boundHeight = 0
        boundOutWidth = 0
        boundOutHeight = 0
    }

    /** "720p→1080p" (frame generation size in → shown size out); one value when they match, "" before start. */
    @JvmStatic
    @Synchronized
    fun resolutionLabel(): String {
        if (boundWidth <= 0 || boundHeight <= 0) return ""
        val inP = Math.min(boundWidth, boundHeight)
        val outP = if (boundOutWidth > 0 && boundOutHeight > 0) Math.min(boundOutWidth, boundOutHeight) else inP
        return if (inP == outP) "${inP}p" else "${inP}p→${outP}p"
    }

    @JvmStatic
    fun applyDisplayMode(activity: Activity?): Float {
        val window = activity?.window ?: return 0f
        val params = window.attributes
        if (!requested) {
            if (params.preferredDisplayModeId != 0) {
                params.preferredDisplayModeId = 0
                window.attributes = params
            }
            synchronized(this) { refreshRate = 0f }
            return 0f
        }
        if (!generating) return synchronized(this) { refreshRate }

        val display = displayOf(activity) ?: return 0f
        val active = display.mode
        val source = Math.round(sourceRate())
        val wanted = wantedRate()

        val selected = pickMode(display)
        if (selected.modeId != params.preferredDisplayModeId || params.preferredRefreshRate != 0f) {
            params.preferredDisplayModeId = selected.modeId
            params.preferredRefreshRate = 0f
            window.attributes = params
        }

        val rate = selected.refreshRate
        synchronized(this) {
            refreshRate = rate
            pushConfig()
        }
        Log.i(TAG, "frame generation display mode: wanted ${wanted}Hz, selected ${Math.round(rate)}Hz " +
            "from ${display.supportedModes.joinToString(" ") {
                "${it.physicalWidth}x${it.physicalHeight}@${Math.round(it.refreshRate)}"
            }} active ${active.physicalWidth}x${active.physicalHeight}@${Math.round(active.refreshRate)}")
        return rate
    }

    private fun pickMode(display: Display): Display.Mode {
        val active = display.mode
        val source = Math.round(sourceRate())
        val wanted = wantedRate()
        var best: Display.Mode? = null
        for (mode in display.supportedModes) {
            if (!sameSize(mode, active)) continue
            if (best == null || betterMode(mode, best!!, wanted, source)) best = mode
        }
        return best ?: active
    }

    /**
     * Re-picks the display mode after a live multiplier change (no Activity needed).
     * Updates the stored refresh rate and returns the chosen modeId, or 0 when frame gen is off.
     */
    @JvmStatic
    fun selectDisplayMode(display: Display): Int {
        if (!requested) return 0
        val selected = pickMode(display)
        synchronized(this) {
            refreshRate = selected.refreshRate
            pushConfig()
        }
        return selected.modeId
    }

    private fun sameSize(mode: Display.Mode, active: Display.Mode): Boolean =
        (mode.physicalWidth == active.physicalWidth && mode.physicalHeight == active.physicalHeight) ||
            (mode.physicalWidth == active.physicalHeight && mode.physicalHeight == active.physicalWidth)

    private fun betterMode(
        candidate: Display.Mode,
        current: Display.Mode,
        wanted: Int,
        sourceRate: Int,
    ): Boolean {
        val a = candidate.refreshRate
        val b = current.refreshRate
        val aMeets = a + 0.5f >= wanted
        val bMeets = b + 0.5f >= wanted
        if (aMeets != bMeets) return aMeets
        if (!aMeets) return a > b
        val aCadence = cadenceFits(a, sourceRate)
        val bCadence = cadenceFits(b, sourceRate)
        if (aCadence != bCadence) return aCadence
        return a < b
    }

    private fun cadenceFits(refreshRate: Float, sourceRate: Int): Boolean {
        if (refreshRate <= 0f || sourceRate <= 0 || refreshRate < sourceRate) return false
        val ratio = refreshRate / sourceRate
        val nearest = Math.round(ratio)
        return nearest >= 1 && Math.abs(ratio - nearest) <= CADENCE_EPSILON
    }

    private fun displayOf(activity: Activity): Display? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.let { return it }
        }
        val manager = activity.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        return manager?.defaultDisplay
    }
}
