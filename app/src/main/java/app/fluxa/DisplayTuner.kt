package app.fluxa

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import kotlin.math.roundToInt

/**
 * Changes the screen resolution + DPI through the window manager (needs the WRITE_SECURE_SETTINGS
 * permission, granted once over adb) and remembers the original values so they can be restored.
 *
 * The first time the app opens, the current size + DPI are stored as the "original". The service
 * restores them when it stops; if the process dies while a change is active, the next app start
 * (or the crash handler) restores them.
 */
object DisplayTuner {
    private const val TAG = "FluxaDisplay"
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    const val GRANT_COMMAND = "adb shell pm grant app.fluxa $PERMISSION"

    const val MIN_PERCENT = 40
    const val MAX_PERCENT = 100
    const val PERCENT_STEP = 5
    const val DEFAULT_PERCENT = 75
    const val MIN_DPI = 100
    const val MAX_DPI = 800
    const val DPI_STEP = 5

    private const val PREFS = "fluxa"
    const val K_ENABLED = "disp_enabled"
    const val K_PERCENT = "disp_percent"
    const val K_DPI_AUTO = "disp_dpi_auto"
    const val K_DPI = "disp_dpi"
    const val K_UPSCALE = "disp_upscale"
    const val K_UPSCALE_FILTER = "disp_upscale_filter"
    const val K_UPSCALE_SHARP = "disp_upscale_sharp"
    const val K_UPSCALE_SHARP_METHOD = "disp_upscale_sharp_method"
    const val K_OUT_PERCENT = "disp_out_percent"
    const val K_CAP_CUBIC = "cap_cubic"

    // Upscale modes: ids shared with the native side (FX_UP_*)
    const val FILTER_NEAREST = 0
    const val FILTER_LINEAR = 1
    const val FILTER_CUBIC = 2
    const val FILTER_SGSR = 3
    const val FILTER_FSR1 = 4
    const val FILTER_LANCZOS = 5
    const val FILTER_CATROM = 6
    const val FILTER_NIS = 7
    const val FILTER_ANIME = 8
    const val FILTER_MITCHELL = 9
    const val FILTER_LANCZOS3 = 10

    // Sharpen passes that follow the upscale: ids shared with the native side (FX_SHARP_*)
    const val SHARP_RCAS = 0
    const val SHARP_CAS = 1
    const val SHARP_ADAPTIVE = 2

    const val MIN_OUT_PERCENT = 50
    const val MAX_OUT_PERCENT = 100
    const val OUT_PERCENT_STEP = 5
    private const val K_APPLIED = "disp_applied"
    private const val K_ORIG_W = "disp_orig_w"
    private const val K_ORIG_H = "disp_orig_h"
    private const val K_ORIG_DPI = "disp_orig_dpi"
    private const val K_ORIG_HAD_SIZE = "disp_orig_had_size"
    private const val K_ORIG_HAD_DPI = "disp_orig_had_dpi"

    /** Size in the display's natural orientation (what `wm size` uses) + density. */
    data class Original(val width: Int, val height: Int, val dpi: Int, val hadForcedSize: Boolean, val hadForcedDpi: Boolean)

    data class Config(
        val enabled: Boolean,
        val percent: Int,
        val dpiAuto: Boolean,
        val dpi: Int,
        val upscale: Boolean,
        /** One of the FILTER_* modes (nearest, linear, cubic, Catmull-Rom, Lanczos, SGSR-style, NIS-style, FSR 1). */
        val upscaleFilter: Int = FILTER_LINEAR,
        /** Sharpen strength after upscaling: 0 = off, 1..100. */
        val upscaleSharp: Int = 0,
        /** Which sharpen pass the strength drives: [SHARP_RCAS], [SHARP_CAS] or [SHARP_ADAPTIVE]. */
        val upscaleSharpMethod: Int = SHARP_RCAS,
        /** Output (shown) resolution as a percentage of the original screen resolution. */
        val outPercent: Int = MAX_OUT_PERCENT,
    )

    data class Plan(val width: Int, val height: Int, val dpi: Int)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun config(ctx: Context): Config {
        val p = prefs(ctx)
        return Config(
            enabled = p.getBoolean(K_ENABLED, false),
            percent = p.getInt(K_PERCENT, DEFAULT_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT),
            dpiAuto = p.getBoolean(K_DPI_AUTO, true),
            dpi = p.getInt(K_DPI, 400).coerceIn(MIN_DPI, MAX_DPI),
            upscale = p.getBoolean(K_UPSCALE, true),
            upscaleFilter = p.getInt(K_UPSCALE_FILTER, FILTER_LINEAR).coerceIn(FILTER_NEAREST, FILTER_LANCZOS3),
            upscaleSharp = p.getInt(K_UPSCALE_SHARP, 0).coerceIn(0, 100),
            upscaleSharpMethod = p.getInt(K_UPSCALE_SHARP_METHOD, SHARP_RCAS).coerceIn(SHARP_RCAS, SHARP_ADAPTIVE),
            outPercent = p.getInt(K_OUT_PERCENT, MAX_OUT_PERCENT).coerceIn(MIN_OUT_PERCENT, MAX_OUT_PERCENT),
        )
    }

    /** (mode id, short label) the user can pick; cubic only appears once a run reported support. */
    fun upscaleModes(ctx: Context): List<Pair<Int, String>> = buildList {
        add(FILTER_NEAREST to "Nearest")
        add(FILTER_LINEAR to "Linear")
        if (cubicSupported(ctx)) add(FILTER_CUBIC to "Cubic")
        add(FILTER_MITCHELL to "Mitchell")
        add(FILTER_CATROM to "Catmull-Rom")
        add(FILTER_LANCZOS to "Lanczos-2")
        add(FILTER_LANCZOS3 to "Lanczos-3")
        add(FILTER_SGSR to "SGSR")
        add(FILTER_NIS to "NIS")
        add(FILTER_FSR1 to "FSR1")
        add(FILTER_ANIME to "Anime")
    }

    /** Whether the GPU the presenter ran on can sample with the cubic filter (learned on a run). */
    fun cubicSupported(ctx: Context): Boolean = prefs(ctx).getBoolean(K_CAP_CUBIC, false)

    fun setCubicSupported(ctx: Context, value: Boolean) {
        if (cubicSupported(ctx) != value) prefs(ctx).edit().putBoolean(K_CAP_CUBIC, value).apply()
    }

    /** Persists the live upscale choice (the drawer changes it while a session runs). */
    fun saveUpscale(ctx: Context, mode: Int, sharp: Int, sharpMethod: Int = SHARP_RCAS) {
        prefs(ctx).edit()
            .putInt(K_UPSCALE_FILTER, mode)
            .putInt(K_UPSCALE_SHARP, sharp)
            .putInt(K_UPSCALE_SHARP_METHOD, sharpMethod)
            .apply()
    }

    /** (method id, short label) for the sharpen pass picker. */
    val sharpMethods: List<Pair<Int, String>> = listOf(SHARP_RCAS to "RCAS", SHARP_CAS to "CAS", SHARP_ADAPTIVE to "Adaptive")

    fun isApplied(ctx: Context): Boolean = prefs(ctx).getBoolean(K_APPLIED, false)

    fun original(ctx: Context): Original? {
        val p = prefs(ctx)
        val w = p.getInt(K_ORIG_W, 0)
        val h = p.getInt(K_ORIG_H, 0)
        val dpi = p.getInt(K_ORIG_DPI, 0)
        if (w <= 0 || h <= 0 || dpi <= 0) return null
        return Original(w, h, dpi, p.getBoolean(K_ORIG_HAD_SIZE, false), p.getBoolean(K_ORIG_HAD_DPI, false))
    }

    /** Stores the current size + DPI the first time (never while a change of ours is active). */
    fun captureOriginalIfNeeded(ctx: Context) {
        if (original(ctx) != null || isApplied(ctx)) return
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        val landscapeNow = display.rotation == android.view.Surface.ROTATION_90 ||
            display.rotation == android.view.Surface.ROTATION_270
        val w = if (landscapeNow) m.heightPixels else m.widthPixels
        val h = if (landscapeNow) m.widthPixels else m.heightPixels
        val cr = ctx.contentResolver
        val hadSize = runCatching { !Settings.Global.getString(cr, "display_size_forced").isNullOrEmpty() }.getOrDefault(false)
        val hadDpi = runCatching { !Settings.Secure.getString(cr, "display_density_forced").isNullOrEmpty() }.getOrDefault(false)
        prefs(ctx).edit()
            .putInt(K_ORIG_W, w).putInt(K_ORIG_H, h).putInt(K_ORIG_DPI, m.densityDpi)
            .putBoolean(K_ORIG_HAD_SIZE, hadSize).putBoolean(K_ORIG_HAD_DPI, hadDpi)
            .apply()
        Log.i(TAG, "original display saved ${w}x$h @${m.densityDpi}dpi")
    }

    private fun even(v: Double): Int = maxOf(2, (v / 2.0).roundToInt() * 2)

    fun plan(orig: Original, cfg: Config): Plan {
        val f = cfg.percent / 100.0
        val dpi = if (cfg.dpiAuto) (orig.dpi * f).roundToInt() else cfg.dpi
        return Plan(even(orig.width * f), even(orig.height * f), dpi.coerceIn(MIN_DPI, MAX_DPI))
    }

    /** Plan for the next start, or null when the feature is off / not permitted / nothing to change. */
    fun planForStart(ctx: Context): Plan? {
        val cfg = config(ctx)
        if (!cfg.enabled || !canWrite(ctx)) return null
        captureOriginalIfNeeded(ctx)
        val orig = original(ctx) ?: return null
        return plan(orig, cfg)
    }

    /**
     * Applies [plan]. [autoRestore] = true marks the change as owned by a running session, so it is
     * undone automatically if that session dies (manual "try now" passes false).
     */
    fun apply(ctx: Context, plan: Plan, autoRestore: Boolean = true): Boolean {
        captureOriginalIfNeeded(ctx)
        if (!canWrite(ctx)) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted")
            return false
        }
        if (autoRestore) prefs(ctx).edit().putBoolean(K_APPLIED, true).commit()
        return runCatching {
            val wm = windowManager()
            setSize(wm, plan.width, plan.height)
            setDensity(wm, plan.dpi)
            Log.i(TAG, "display set to ${plan.width}x${plan.height} @${plan.dpi}dpi")
            true
        }.getOrElse {
            Log.w(TAG, "could not change display: ${unwrap(it)}")
            false
        }
    }

    /** Puts the stored original size + DPI back. */
    fun restore(ctx: Context): Boolean {
        val orig = original(ctx)
        val ok = runCatching {
            val wm = windowManager()
            if (orig != null && orig.hadForcedSize) setSize(wm, orig.width, orig.height) else clearSize(wm)
            if (orig != null && orig.hadForcedDpi) setDensity(wm, orig.dpi) else clearDensity(wm)
            Log.i(TAG, "display restored to ${orig?.width}x${orig?.height} @${orig?.dpi}dpi")
            true
        }.getOrElse {
            Log.w(TAG, "could not restore display: ${unwrap(it)}")
            false
        }
        if (ok) prefs(ctx).edit().putBoolean(K_APPLIED, false).commit()
        return ok
    }

    /** Safety net: undo a change left behind by a session that died. */
    fun restoreIfApplied(ctx: Context) {
        if (isApplied(ctx) && canWrite(ctx)) restore(ctx)
    }

    // ---------------------------------------------------------------- IWindowManager (hidden API)

    @Volatile private var exempted = false

    private fun windowManager(): Any {
        if (!exempted) {
            HiddenApiBypass.addHiddenApiExemptions("L")
            exempted = true
        }
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "window") as IBinder
        return Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private fun call(wm: Any, name: String, vararg args: Int) {
        val types = Array<Class<*>>(args.size) { Int::class.java }
        val method = Class.forName("android.view.IWindowManager").getMethod(name, *types)
        method.invoke(wm, *args.toTypedArray())
    }

    private val userId get() = Process.myUid() / 100000

    private fun setSize(wm: Any, w: Int, h: Int) = call(wm, "setForcedDisplaySize", Display.DEFAULT_DISPLAY, w, h)
    private fun clearSize(wm: Any) = call(wm, "clearForcedDisplaySize", Display.DEFAULT_DISPLAY)
    private fun setDensity(wm: Any, dpi: Int) = call(wm, "setForcedDisplayDensityForUser", Display.DEFAULT_DISPLAY, dpi, userId)
    private fun clearDensity(wm: Any) = call(wm, "clearForcedDisplayDensityForUser", Display.DEFAULT_DISPLAY, userId)

    private fun unwrap(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t
}
