package app.fluxa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Settings drawer drawn above the game as accessibility overlays.
 *  - a thin invisible-ish strip on the right edge: swipe right -> left opens the drawer
 *  - the drawer itself (dim scrim + panel): live multiplier / quality / on-off / stop
 * Tapping the dim area or swiping the panel to the right closes it.
 */
class DrawerOverlay(
    private val host: Context,
    private val wm: WindowManager,
    private val screenW: Int,
    private val screenH: Int,
    private val onStop: () -> Unit,
    private val onSettingsChanged: () -> Unit,
    /** Elapsed recording time in ms, or -1 when not recording (mode "ปกติ"). */
    private val recordElapsedMs: () -> Long = { -1L },
) {
    private val ctx = ContextThemeWrapper(host, android.R.style.Theme_DeviceDefault)
    private val handler = Handler(Looper.getMainLooper())
    private val density = host.resources.displayMetrics.density
    private val kit = Kit(ctx)
    private val appName = host.getString(R.string.app_name)

    private var edge: View? = null
    private var scrim: FrameLayout? = null
    private var panel: View? = null
    private var statsText: TextView? = null
    private var hud: TextView? = null
    private var open = false
    private val prefs = host.getSharedPreferences("fluxa", Context.MODE_PRIVATE)
    private var hudEnabled = prefs.getBoolean("hud", true)
    /** Burn the fps HUD into the recorded video too (live toggle, independent of the on-screen HUD). */
    private var hudInVideo = prefs.getBoolean("hud_rec", true)
    private var hudSentToVideo = false

    private var lastReal = 0L
    private var lastGen = 0L
    private var lastTime = 0L

    private fun dp(v: Int) = (v * density).toInt()

    // ---------------------------------------------------------------- edge strip

    fun attach() {
        if (edge != null) return
        val strip = FrameLayout(ctx)
        val pill = View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x88FFA63D.toInt(), 0x88E0560F.toInt()),
            ).apply { cornerRadius = dp(2).toFloat() }
        }
        strip.addView(pill, FrameLayout.LayoutParams(dp(4), dp(56), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(3)
        })

        var downX = 0f
        var triggered = false
        strip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    triggered = false
                }
                MotionEvent.ACTION_MOVE -> if (!triggered && downX - e.rawX > dp(40)) {
                    triggered = true
                    show()
                }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            dp(20),
            (screenH * 0.5f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        edge = strip
        runCatching { wm.addView(strip, lp) }
        addHud()
        lastTime = 0L
        handler.post(ticker)
    }

    /** Small click-through "in/out" fps counter in the top-left corner. */
    private fun addHud() {
        val view = text("--/--", 12f, bold = true, color = Palette.AMBER).apply {
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = kit.rounded(0xB30B0907.toInt(), 9, 0x66FFA63D, 1)
            visibility = if (hudEnabled) View.VISIBLE else View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(24)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        hud = view
        if (runCatching { wm.addView(view, lp) }.isFailure) hud = null
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        open = false
        scrim?.let { runCatching { wm.removeViewImmediate(it) } }
        scrim = null
        panel = null
        statsText = null
        edge?.let { runCatching { wm.removeViewImmediate(it) } }
        edge = null
        hud?.let { runCatching { wm.removeViewImmediate(it) } }
        hud = null
    }

    // ---------------------------------------------------------------- drawer

    private fun show() {
        if (open) return
        open = true

        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0x80000000.toInt())
            setOnClickListener { hide() }
        }
        val content = buildPanelContent()
        val panelWidth = minOf(dp(300), (screenW * 0.75f).toInt())
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(0xF40D0B09.toInt())
                setStroke(dp(1), 0x44FFA63D)
                cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f, dp(24).toFloat(), dp(24).toFloat())
            }
            setOnClickListener { } // swallow taps so they don't close the drawer
            addView(content)
        }
        // swipe the panel to the right to close
        var downX = 0f
        scroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = e.rawX
                MotionEvent.ACTION_MOVE -> if (e.rawX - downX > dp(80)) {
                    downX = Float.MAX_VALUE
                    hide()
                }
            }
            false
        }
        root.addView(scroll, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))

        val lp = WindowManager.LayoutParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        scrim = root
        panel = scroll
        if (runCatching { wm.addView(root, lp) }.isFailure) {
            scrim = null
            panel = null
            open = false
            return
        }

        scroll.translationX = panelWidth.toFloat()
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(160).start()
        scroll.animate().translationX(0f).setDuration(200).start()
    }

    private fun hide() {
        if (!open) return
        open = false
        val root = scrim
        val p = panel
        if (root == null || p == null) return
        root.animate().alpha(0f).setDuration(160).start()
        p.animate().translationX(p.width.toFloat()).setDuration(180).withEndAction {
            if (scrim === root) {
                runCatching { wm.removeViewImmediate(root) }
                scrim = null
                panel = null
                statsText = null
            }
        }.start()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val real = Fluxa.realFrames()
            val gen = Fluxa.generatedFrames()
            val now = SystemClock.elapsedRealtime()
            if (lastTime != 0L && now > lastTime && real >= lastReal && gen >= lastGen) {
                val dt = (now - lastTime) / 1000f
                val realFps = (real - lastReal) / dt
                val outFps = (real - lastReal + gen - lastGen) / dt
                val rec = recordLabel()
                val res = Fluxa.resolutionLabel().let { if (it.isEmpty()) "" else " · $it" }
                statsText?.text = String.format("%s%.0f → %.0f fps%s", rec, realFps, outFps, res)
                val line = String.format("%s%.0f/%.0f%s", rec, realFps, outFps, res)
                hud?.text = line
                lastHudLine = line
                updateVideoHud()
            }
            lastReal = real
            lastGen = gen
            lastTime = now
            handler.postDelayed(this, 500)
        }
    }

    private var lastHudLine = ""

    /** Draws the HUD into a small opaque bitmap and hands it to the presenter (or hides it). */
    private fun updateVideoHud() {
        if (!hudInVideo || recordElapsedMs() < 0 || lastHudLine.isEmpty()) {
            if (hudSentToVideo) {
                Fluxa.setRecordHud(null, 0, 0, 0, 0)
                hudSentToVideo = false
            }
            return
        }
        val (vw, vh) = Fluxa.recordSize()
        if (vw <= 0 || vh <= 0 || screenW <= 0 || screenH <= 0) return
        // the shown frame is aspect-fit inside the video, so one screen px = `scale` video px
        val scale = min(vw.toFloat() / screenW, vh.toFloat() / screenH)
        val bmp = renderHud(lastHudLine, scale) ?: return
        val buf = ByteBuffer.allocate(bmp.width * bmp.height * 4)
        bmp.copyPixelsToBuffer(buf)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        Fluxa.setRecordHud(buf.array(), w, h, (8 * density * scale).toInt(), (24 * density * scale).toInt())
        hudSentToVideo = true
    }

    /** Same look as the on-screen HUD (amber bold text on dark, amber hairline), solid background. */
    private fun renderHud(line: String, scale: Float): Bitmap? {
        val px = density * scale // dp -> video px
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.AMBER
            typeface = Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, host.resources.displayMetrics) * scale
        }
        val padX = 9 * px
        val padY = 4 * px
        val fm = textPaint.fontMetrics
        var w = (textPaint.measureText(line) + 2 * padX).toInt() + 1
        var h = (fm.descent - fm.ascent + 2 * padY).toInt() + 1
        // the native overlay buffer holds at most 640 x 160 px
        val fit = min(1f, min(640f / w, 160f / h))
        if (fit < 1f) {
            textPaint.textSize *= fit
            w = (w * fit).toInt()
            h = (h * fit).toInt()
        }
        w = max(8, min(640, w))
        h = max(8, min(160, h))
        val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val c = Canvas(bmp)
        c.drawColor(0xFF0B0907.toInt())
        val border = Paint().apply {
            style = Paint.Style.STROKE
            color = 0xFF5A4020.toInt()
            strokeWidth = max(1f, px)
        }
        c.drawRect(border.strokeWidth / 2, border.strokeWidth / 2, w - border.strokeWidth / 2, h - border.strokeWidth / 2, border)
        val tp = textPaint.fontMetrics
        c.drawText(line, (w - textPaint.measureText(line)) / 2f, (h - (tp.descent - tp.ascent)) / 2f - tp.ascent, textPaint)
        return bmp
    }

    /** "● 01:23 " while recording, empty otherwise. */
    private fun recordLabel(): String {
        val ms = recordElapsedMs()
        if (ms < 0) return ""
        val sec = ms / 1000
        return String.format("● %02d:%02d  ", sec / 60, sec % 60)
    }

    // ---------------------------------------------------------------- panel content

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, color: Int = Palette.TEXT) =
        TextView(ctx).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun buildPanelContent(): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(ctx).apply {
            background = kit.gradient(intArrayOf(Palette.AMBER, Palette.EMBER, Palette.RUST), 3, GradientDrawable.Orientation.TOP_BOTTOM)
        }, LinearLayout.LayoutParams(dp(4), dp(26)).apply { marginEnd = dp(10) })
        head.addView(text(appName.uppercase(), 20f, bold = true).apply { letterSpacing = 0.16f })
        box.addView(head)

        statsText = text("กำลังวัด fps…", 13f, color = Palette.AMBER, bold = true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = kit.rounded(0x1AFFA63D, 10, 0x44FFA63D, 1)
        }
        box.addView(statsText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })

        // hud toggle
        val hudRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        hudRow.addView(text("แสดง fps + ความละเอียด บนจอ (เข้า/ออก)", 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hudRow.addView(Switch(ctx).apply {
            isChecked = hudEnabled
            setOnCheckedChangeListener { _, on ->
                hudEnabled = on
                prefs.edit().putBoolean("hud", on).apply()
                hud?.visibility = if (on) View.VISIBLE else View.GONE
            }
        })
        box.addView(hudRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })

        // hud in recorded video
        val hudVideoRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        hudVideoRow.addView(text("ใส่ HUD ลงในวิดีโอที่อัด", 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hudVideoRow.addView(Switch(ctx).apply {
            isChecked = hudInVideo
            setOnCheckedChangeListener { _, on ->
                hudInVideo = on
                prefs.edit().putBoolean("hud_rec", on).apply()
                updateVideoHud()
            }
        })
        box.addView(hudVideoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })

        // frame multiplier
        box.addView(text("ตัวคูณเฟรม", 13f, color = Palette.MUTED).apply { setPadding(0, dp(16), 0, dp(6)) })
        box.addView(Slider(
            kit, FluxaOptions.MIN_MULTIPLIER, FluxaOptions.MAX_MULTIPLIER,
            FluxaOptions.clampMultiplier(Fluxa.multiplier), format = FluxaOptions::multiplierLabel,
        ) { v ->
            Fluxa.reconfigure(v, Fluxa.flowSide)
            prefs.edit().putInt("ui_mult_x", v).apply()
            onSettingsChanged()
        })

        // quality = optical-flow resolution (short side, px)
        box.addView(text("คุณภาพ (DIS flow)", 13f, color = Palette.MUTED).apply { setPadding(0, dp(16), 0, dp(6)) })
        box.addView(Slider(
            kit, FluxaOptions.MIN_FLOW_SIDE, FluxaOptions.MAX_FLOW_SIDE,
            FluxaOptions.clampFlowSide(Fluxa.flowSide),
            step = FluxaOptions.FLOW_SIDE_STEP, format = { "$it px" },
        ) { v ->
            Fluxa.reconfigure(Fluxa.multiplier, v)
            prefs.edit().putInt("ui_flow_side", v).apply()
            onSettingsChanged()
        })

        // present mode (FIFO / MAILBOX), switched live
        box.addView(text("โหมดแสดงผล", 13f, color = Palette.MUTED).apply { setPadding(0, dp(16), 0, dp(6)) })
        box.addView(chipRow(
            listOf("FIFO", "MAILBOX"),
            if (Fluxa.presentMode == FluxaOptions.PRESENT_MAILBOX) 1 else 0,
        ) { i ->
            Fluxa.setPresentMode(if (i == 1) FluxaOptions.PRESENT_MAILBOX else FluxaOptions.PRESENT_FIFO)
            prefs.edit().putInt("ui_present", i).apply()
            true
        })

        // upscale filter + sharpness, switched live (only matters while the output is upscaled)
        val upCfg = DisplayTuner.config(host)
        var upMode = upCfg.upscaleFilter
        var upSharp = upCfg.upscaleSharp
        var upSharpMethod = upCfg.upscaleSharpMethod
        val upModes = DisplayTuner.upscaleModes(host)
        box.addView(text("ฟิลเตอร์อัปสเกล (ใช้เมื่ออัปสเกลอยู่)", 13f, color = Palette.MUTED).apply { setPadding(0, dp(16), 0, dp(6)) })
        box.addView(chipRow(
            upModes.map { it.second },
            upModes.indexOfFirst { it.first == upMode }.coerceAtLeast(0),
            perRow = 3,
        ) { i ->
            upMode = upModes[i].first
            Fluxa.setUpscale(upMode, upSharp, upSharpMethod)
            DisplayTuner.saveUpscale(host, upMode, upSharp, upSharpMethod)
            true
        })
        box.addView(text("ความคมหลังอัปสเกล", 13f, color = Palette.MUTED).apply { setPadding(0, dp(16), 0, dp(6)) })
        box.addView(Slider(
            kit, 0, 100, upSharp, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
        ) { v ->
            upSharp = v
            Fluxa.setUpscale(upMode, upSharp, upSharpMethod)
            DisplayTuner.saveUpscale(host, upMode, upSharp, upSharpMethod)
        })
        box.addView(text("วิธีเพิ่มความคม", 13f, color = Palette.MUTED).apply { setPadding(0, dp(16), 0, dp(6)) })
        box.addView(chipRow(
            DisplayTuner.sharpMethods.map { it.second },
            DisplayTuner.sharpMethods.indexOfFirst { it.first == upSharpMethod }.coerceAtLeast(0),
        ) { i ->
            upSharpMethod = DisplayTuner.sharpMethods[i].first
            Fluxa.setUpscale(upMode, upSharp, upSharpMethod)
            DisplayTuner.saveUpscale(host, upMode, upSharp, upSharpMethod)
            true
        })

        // stop
        val stop = text("หยุด $appName", 16f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = kit.gradient(intArrayOf(0xFFEF4444.toInt(), 0xFFB91C1C.toInt()), 16)
            setOnClickListener { onStop() }
        }
        box.addView(stop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(26) })

        box.addView(text("ปัดไปทางขวาหรือแตะข้างนอกเพื่อปิด", 11f, color = Palette.DIM)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        return box
    }

    private fun chipRow(
        labels: List<String>, selected: Int, perRow: Int = labels.size, onPick: (Int) -> Boolean,
    ): Segmented = Segmented(kit, labels, selected, perRow = perRow, textSp = 13.5f, onPick = onPick)
}
