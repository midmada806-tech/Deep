package app.fluxa

import android.content.Context
import android.content.res.ColorStateList
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
 *  - a thin strip on the right edge: swipe right -> left opens the drawer
 *  - the drawer: header with live fps, four tabs (frames / picture / colour / HUD), pinned stop button
 * Dragging any slider fades the drawer out so the change can be judged on the live game; it comes
 * back on release. Tapping the dim area, the X, or swiping the header to the right closes it.
 */
class DrawerOverlay(
    private val host: Context,
    private val wm: WindowManager,
    private val screenW: Int,
    private val screenH: Int,
    private val onStop: () -> Unit,
    private val onSettingsChanged: () -> Unit,
    /** Output % changed (already saved to prefs): the service rebuilds the presenter at the new size. */
    private val onOutputPercentChanged: () -> Unit = {},
    /** True while the output size cannot change (an mp4 is being recorded at a fixed size). */
    private val outputLocked: () -> Boolean = { false },
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
    private var dim: View? = null
    private var tab = 0
    private var statsText: TextView? = null
    private var hud: View? = null
    private var hudText: TextView? = null
    private var hudGraph: FrameGraphView? = null
    private val frameBuf = IntArray(FrameGraphView.SHOWN)
    private var open = false
    private val prefs = host.getSharedPreferences("fluxa", Context.MODE_PRIVATE)
    private var hudEnabled = prefs.getBoolean("hud", true)
    /** Burn the fps HUD into the recorded video too (live toggle, independent of the on-screen HUD). */
    private var hudInVideo = prefs.getBoolean("hud_rec", true)
    private var hudSentToVideo = false
    /** Frametime graph under the fps counter (live toggle). */
    private var hudGraphEnabled = prefs.getBoolean("hud_graph", true)

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
        val label = text("--/--", 12f, bold = true, color = Palette.AMBER)
        val graph = FrameGraphView(ctx).apply { visibility = View.GONE }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = kit.rounded(0xB3101215.toInt(), 9, 0x55FFFFFF, 1)
            visibility = if (hudEnabled) View.VISIBLE else View.GONE
            addView(label)
            addView(graph, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        }
        hudText = label
        hudGraph = graph
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
        dim = null
        statsText = null
        edge?.let { runCatching { wm.removeViewImmediate(it) } }
        edge = null
        hud?.let { runCatching { wm.removeViewImmediate(it) } }
        hud = null
        hudText = null
        hudGraph = null
    }

    // ---------------------------------------------------------------- drawer

    // ---------------------------------------------------------------- drawer

    private fun show() {
        if (open) return
        open = true

        val root = FrameLayout(ctx)
        val dimView = View(ctx).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { hide() }
        }
        root.addView(dimView, FrameLayout.LayoutParams(-1, -1))
        val panelWidth = minOf(dp(340), (screenW * 0.8f).toInt())
        val card = buildPanel()
        root.addView(card, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))

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
        panel = card
        dim = dimView
        if (runCatching { wm.addView(root, lp) }.isFailure) {
            scrim = null
            panel = null
            dim = null
            open = false
            return
        }

        card.translationX = panelWidth.toFloat()
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(160).start()
        card.animate().translationX(0f).setDuration(200).start()
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
                dim = null
                statsText = null
            }
        }.start()
    }

    /** While a slider is held the drawer and scrim fade out, so the game underneath shows the change. */
    private fun peek(on: Boolean) {
        panel?.animate()?.alpha(if (on) 0.14f else 1f)?.setDuration(120)?.start()
        dim?.animate()?.alpha(if (on) 0f else 1f)?.setDuration(120)?.start()
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
                hudText?.text = line
                lastHudLine = line
                updateVideoHud()
            }
            updateGraph()
            lastReal = real
            lastGen = gen
            lastTime = now
            handler.postDelayed(this, 500)
        }
    }

    private var lastHudLine = ""

    /** Refreshes the frametime graph from the presenter's ring (skipped while hidden). */
    private fun updateGraph() {
        val g = hudGraph ?: return
        if (!hudEnabled || !hudGraphEnabled) {
            g.visibility = View.GONE
            return
        }
        val n = Fluxa.frameTimes(frameBuf)
        if (n < 2) {
            g.visibility = View.GONE
            return
        }
        g.setSamples(frameBuf, n)
        g.visibility = View.VISIBLE
    }

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
        c.drawColor(0xFF101215.toInt())
        val border = Paint().apply {
            style = Paint.Style.STROKE
            color = 0xFF3A3F46.toInt()
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

    private val tabNames = listOf("เฟรม", "ภาพ", "สี", "HUD")

    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h)

    private fun buildPanel(): LinearLayout {
        val r = dp(22).toFloat()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF4181A1E.toInt())
                setStroke(dp(1), 0x33FFFFFF)
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            isClickable = true // swallow taps so they don't reach the scrim
        }

        // ---- header: title, close, live stats (swipe right on it to close)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(8), dp(10))
        }
        val titleRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(View(ctx).apply {
            background = kit.gradient(intArrayOf(Palette.AMBER, Palette.EMBER, Palette.RUST), 3, GradientDrawable.Orientation.TOP_BOTTOM)
        }, lp(dp(4), dp(22)).apply { marginEnd = dp(10) })
        titleRow.addView(text(appName, 19f, bold = true), lp(0, -2).apply { weight = 1f })
        titleRow.addView(text("✕", 17f, color = Palette.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { hide() }
        })
        header.addView(titleRow)
        statsText = text("กำลังวัด fps…", 12.5f, color = Palette.AMBER, bold = true).apply {
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = kit.rounded(0x1AFFA63D, 10, 0x44FFA63D, 1)
        }
        header.addView(statsText, lp(-2, -2).apply { topMargin = dp(10) })
        var downX = 0f
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = e.rawX
                MotionEvent.ACTION_MOVE -> if (e.rawX - downX > dp(70)) {
                    downX = Float.MAX_VALUE
                    hide()
                }
            }
            true
        }
        card.addView(header)

        // ---- tabs
        val page = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val tabViews = ArrayList<Pair<TextView, View>>()
        fun paintTabs() = tabViews.forEachIndexed { i, (label, bar) ->
            val on = i == tab
            label.setTextColor(if (on) Palette.AMBER else Palette.MUTED)
            bar.setBackgroundColor(if (on) Palette.AMBER else 0)
        }
        fun openTab(i: Int) {
            tab = i
            paintTabs()
            page.removeAllViews()
            page.addView(buildPage(i))
            page.scrollTo(0, 0)
        }
        val tabRow = LinearLayout(ctx).apply { setPadding(dp(10), 0, dp(10), 0) }
        tabNames.forEachIndexed { i, name ->
            val label = text(name, 14f, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
            }
            val bar = View(ctx)
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { openTab(i) }
                addView(label, lp())
                addView(bar, lp(-1, dp(2)).apply { marginStart = dp(12); marginEnd = dp(12) })
            }
            tabViews.add(label to bar)
            tabRow.addView(cell, lp(0, -2).apply { weight = 1f })
        }
        card.addView(tabRow)
        card.addView(View(ctx).apply { setBackgroundColor(0x22FFFFFF) }, lp(-1, dp(1)))

        card.addView(page, lp(-1, 0).apply { weight = 1f })
        openTab(tab.coerceIn(0, tabNames.size - 1))

        // ---- footer: stop, always reachable
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        footer.addView(
            text("หยุด $appName", 15f, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                background = kit.rounded(0x33D64541, 12, 0x99D64541.toInt(), 1)
                setTextColor(0xFFFF8A80.toInt())
                setOnClickListener { onStop() }
            },
            lp(),
        )
        card.addView(View(ctx).apply { setBackgroundColor(0x22FFFFFF) }, lp(-1, dp(1)))
        card.addView(footer)
        return card
    }

    // ---- building blocks

    private fun group(page: LinearLayout, title: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.rounded(0xFF202328.toInt(), 14, 0xFF2C3036.toInt(), 1)
        setPadding(dp(14), dp(12), dp(14), dp(16))
        addView(text(title, 12f, bold = true, color = Palette.MUTED).apply { letterSpacing = 0.06f })
        page.addView(this, lp().apply { topMargin = dp(12) })
    }

    private fun gap(g: LinearLayout, v: View, top: Int = 16) = g.addView(v, lp().apply { topMargin = dp(top) })

    private fun slider(
        g: LinearLayout, title: String, min: Int, max: Int, value: Int, step: Int = 1,
        format: (Int) -> String = { it.toString() }, onCommit: (Int) -> Unit,
    ): Slider = Slider(
        kit, min, max, value, step = step, format = format,
        title = title, showRange = false, onDrag = ::peek, onCommit = onCommit,
    ).also { gap(g, it) }

    private fun chips(
        g: LinearLayout, title: String, labels: List<String>, selected: Int, perRow: Int = labels.size,
        onPick: (Int) -> Boolean,
    ): Segmented {
        gap(g, text(title, 13.5f, bold = true))
        val seg = Segmented(kit, labels, selected, perRow = perRow, textSp = 13f, onPick = onPick)
        g.addView(seg, lp().apply { topMargin = dp(8) })
        return seg
    }

    private fun toggle(g: LinearLayout, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(title, 14f), lp(0, -2).apply { weight = 1f; marginEnd = dp(10) })
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        row.addView(Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(states, intArrayOf(Palette.AMBER, 0xFF9AA0A8.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x66FFA83D, 0xFF3A3F46.toInt()))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        gap(g, row, 12)
    }

    private fun note(g: LinearLayout, value: String) =
        g.addView(text(value, 11.5f, color = Palette.DIM), lp().apply { topMargin = dp(8) })

    // ---- pages

    private fun buildPage(index: Int): LinearLayout {
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(18))
        }
        when (index) {
            0 -> framesPage(page)
            1 -> picturePage(page)
            2 -> colourPage(page)
            else -> hudPage(page)
        }
        return page
    }

    private fun framesPage(page: LinearLayout) {
        val g = group(page, "การสร้างเฟรม")
        slider(
            g, "ตัวคูณเฟรม", FluxaOptions.MIN_MULTIPLIER, FluxaOptions.MAX_MULTIPLIER,
            FluxaOptions.clampMultiplier(Fluxa.multiplier), format = FluxaOptions::multiplierLabel,
        ) { v ->
            Fluxa.reconfigure(v, Fluxa.flowSide)
            prefs.edit().putInt("ui_mult_x", v).apply()
            onSettingsChanged()
        }
        slider(
            g, "คุณภาพ (DIS flow)", FluxaOptions.MIN_FLOW_SIDE, FluxaOptions.MAX_FLOW_SIDE,
            FluxaOptions.clampFlowSide(Fluxa.flowSide), step = FluxaOptions.FLOW_SIDE_STEP, format = { "$it px" },
        ) { v ->
            Fluxa.reconfigure(Fluxa.multiplier, v)
            prefs.edit().putInt("ui_flow_side", v).apply()
            onSettingsChanged()
        }
        chips(
            g, "อัลกอริทึมเจนเฟรม", FluxaOptions.GEN_MODE_LABELS,
            FluxaOptions.clampGenMode(Fluxa.genMode),
        ) { i ->
            Fluxa.setGenMode(i)
            prefs.edit().putInt("ui_gen_mode", i).apply()
            true
        }
        chips(
            g, "โหมดแสดงผล", listOf("FIFO", "MAILBOX"),
            if (Fluxa.presentMode == FluxaOptions.PRESENT_MAILBOX) 1 else 0,
        ) { i ->
            Fluxa.setPresentMode(if (i == 1) FluxaOptions.PRESENT_MAILBOX else FluxaOptions.PRESENT_FIFO)
            prefs.edit().putInt("ui_present", i).apply()
            true
        }
    }

    private fun picturePage(page: LinearLayout) {
        val cfg = DisplayTuner.config(host)
        var upMode = cfg.upscaleFilter
        var upSharp = cfg.upscaleSharp
        var upSharpMethod = cfg.upscaleSharpMethod
        fun applyUpscale() {
            Fluxa.setUpscale(upMode, upSharp, upSharpMethod)
            DisplayTuner.saveUpscale(host, upMode, upSharp, upSharpMethod)
        }

        val up = group(page, "อัปสเกล")
        val outLocked = outputLocked() || !cfg.upscale
        slider(
            up, "ความละเอียดเอาต์พุต", DisplayTuner.MIN_OUT_PERCENT, DisplayTuner.MAX_OUT_PERCENT, cfg.outPercent,
            step = DisplayTuner.OUT_PERCENT_STEP, format = { "$it%" },
        ) { v ->
            if (v != DisplayTuner.config(host).outPercent) {
                DisplayTuner.saveOutPercent(host, v)
                onOutputPercentChanged()
            }
        }.setLocked(outLocked)
        if (outLocked) {
            note(up, if (!cfg.upscale) "ปิดอัปสเกลอยู่ (เปิดได้ในหน้าแอป)" else "ล็อกระหว่างอัดจอ (วิดีโอขนาดคงที่)")
        }
        val upModes = DisplayTuner.upscaleModes(host)
        chips(up, "ฟิลเตอร์", upModes.map { it.second }, upModes.indexOfFirst { it.first == upMode }.coerceAtLeast(0), perRow = 3) { i ->
            upMode = upModes[i].first
            applyUpscale()
            true
        }
        slider(up, "ความคมหลังอัปสเกล", 0, DisplayTuner.STRENGTH_MAX, upSharp, step = 5, format = { if (it == 0) "ปิด" else "$it%" }) { v ->
            upSharp = v
            applyUpscale()
        }
        chips(
            up, "วิธีเพิ่มความคม", DisplayTuner.sharpMethods.map { it.second },
            DisplayTuner.sharpMethods.indexOfFirst { it.first == upSharpMethod }.coerceAtLeast(0),
        ) { i ->
            upSharpMethod = DisplayTuner.sharpMethods[i].first
            applyUpscale()
            true
        }
        note(up, "ฟิลเตอร์และความคมใช้เมื่ออัปสเกลอยู่")

        var enDeband = cfg.deband
        var enDenoise = cfg.denoise
        var enAa = cfg.aa
        var enDither = cfg.dither
        fun applyEnhance() {
            Fluxa.setEnhance(enDeband, enDenoise, enAa, enDither)
            DisplayTuner.saveEnhance(host, enDeband, enDenoise, enAa, enDither)
        }
        val fx = group(page, "ปรับคุณภาพภาพ")
        slider(fx, "ลดสัญญาณรบกวน/บล็อก", 0, DisplayTuner.STRENGTH_MAX, enDenoise, step = 5, format = { if (it == 0) "ปิด" else "$it%" }) { v ->
            enDenoise = v
            applyEnhance()
        }
        chips(
            fx, "ลดฟันปลา (Anti-aliasing)", DisplayTuner.aaModes.map { it.second },
            DisplayTuner.aaModes.indexOfFirst { it.first == enAa }.coerceAtLeast(0),
        ) { i ->
            enAa = DisplayTuner.aaModes[i].first
            applyEnhance()
            true
        }
        slider(fx, "ลดแถบสี (Deband)", 0, DisplayTuner.STRENGTH_MAX, enDeband, step = 5, format = { if (it == 0) "ปิด" else "$it%" }) { v ->
            enDeband = v
            applyEnhance()
        }
        toggle(fx, "Dither", enDither) { on ->
            enDither = on
            applyEnhance()
        }
    }

    private fun colourPage(page: LinearLayout) {
        var look = DisplayTuner.look(host)
        val lookSliders = ArrayList<Pair<Slider, (DisplayTuner.Look) -> Int>>()
        fun applyLook() {
            Fluxa.setLook(look)
            DisplayTuner.saveLook(host, look)
        }
        fun signed(v: Int) = if (v > 0) "+$v" else "$v"
        fun lookSlider(
            g: LinearLayout, label: String, min: Int, max: Int, read: (DisplayTuner.Look) -> Int,
            format: (Int) -> String, write: (DisplayTuner.Look, Int) -> DisplayTuner.Look,
        ) {
            val s = slider(g, label, min, max, read(look), step = if (max - min > 10) 5 else 1, format = format) { v ->
                look = write(look, v)
                applyLook()
            }
            lookSliders.add(s to read)
        }

        val gr = DisplayTuner.GRADE_RANGE
        val color = group(page, "ปรับสี")
        lookSlider(color, "ความสว่าง", -gr, gr, { it.brightness }, ::signed) { l, v -> l.copy(brightness = v) }
        lookSlider(color, "คอนทราสต์", -gr, gr, { it.contrast }, ::signed) { l, v -> l.copy(contrast = v) }
        lookSlider(color, "ความอิ่มสี", -gr, gr, { it.saturation }, ::signed) { l, v -> l.copy(saturation = v) }
        lookSlider(color, "Vibrance (เร่งสีที่จืด)", -gr, gr, { it.vibrance }, ::signed) { l, v -> l.copy(vibrance = v) }
        lookSlider(color, "อุณหภูมิสี (เย็น ↔ อุ่น)", -gr, gr, { it.temperature }, ::signed) { l, v -> l.copy(temperature = v) }
        lookSlider(color, "Tint (เขียว ↔ ม่วงแดง)", -gr, gr, { it.tint }, ::signed) { l, v -> l.copy(tint = v) }

        val grain = group(page, "ฟิล์มเกรน")
        lookSlider(grain, "ปริมาณเกรน", 0, DisplayTuner.GRAIN_MAX, { it.grain }, { if (it == 0) "ปิด" else "$it%" }) { l, v -> l.copy(grain = v) }
        lookSlider(grain, "ขนาดเกรน", DisplayTuner.GRAIN_SIZE_MIN, DisplayTuner.GRAIN_SIZE_MAX, { it.grainSize }, { "$it" }) { l, v -> l.copy(grainSize = v) }

        page.addView(
            text("รีเซ็ตสี/เกรน", 14f, bold = true, color = Palette.AMBER).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                background = kit.rounded(Palette.SURFACE_HI, 14, Palette.STROKE, 1)
                setOnClickListener {
                    look = DisplayTuner.Look(grainSize = look.grainSize)
                    lookSliders.forEach { (slider, read) -> slider.set(read(look)) }
                    applyLook()
                }
            },
            lp().apply { topMargin = dp(14) },
        )
    }

    private fun hudPage(page: LinearLayout) {
        val g = group(page, "HUD")
        toggle(g, "แสดง fps + ความละเอียด บนจอ (เข้า/ออก)", hudEnabled) { on ->
            hudEnabled = on
            prefs.edit().putBoolean("hud", on).apply()
            hud?.visibility = if (on) View.VISIBLE else View.GONE
        }
        toggle(g, "แสดงกราฟ frametime บน HUD", hudGraphEnabled) { on ->
            hudGraphEnabled = on
            prefs.edit().putBoolean("hud_graph", on).apply()
            updateGraph()
        }
        toggle(g, "ใส่ HUD ลงในวิดีโอที่อัด", hudInVideo) { on ->
            hudInVideo = on
            prefs.edit().putBoolean("hud_rec", on).apply()
            updateVideoHud()
        }
    }
}
