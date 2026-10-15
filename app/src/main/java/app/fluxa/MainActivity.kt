package app.fluxa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var kit: Kit
    private lateinit var multiplierSlider: Slider
    private lateinit var presentSeg: Segmented
    private lateinit var qualitySlider: Slider
    private lateinit var genModeSeg: Segmented
    private lateinit var targetFpsSlider: Slider
    private lateinit var outModeSeg: Segmented
    private lateinit var multCaption: View
    private lateinit var fpsCaption: View
    private lateinit var modeSeg: Segmented
    private lateinit var audioSeg: Segmented
    private lateinit var orientSeg: Segmented
    private lateinit var recordBox: LinearLayout
    private lateinit var dispEnableSeg: Segmented
    private lateinit var dispPctSlider: Slider
    private lateinit var dispDpiSeg: Segmented
    private lateinit var dispDpiSlider: Slider
    private lateinit var dispUpscaleSeg: Segmented
    private lateinit var dispFilterSeg: Segmented
    private lateinit var dispSharpSlider: Slider
    private lateinit var dispSharpMethodSeg: Segmented
    private lateinit var dispOutSlider: Slider
    private lateinit var dispDenoiseSlider: Slider
    private lateinit var dispAaSeg: Segmented
    private lateinit var dispDebandSlider: Slider
    private lateinit var dispDitherSeg: Segmented
    private lateinit var dispGrainSlider: Slider
    private lateinit var dispGrainSizeSlider: Slider
    private lateinit var dispBrightSlider: Slider
    private lateinit var dispContrastSlider: Slider
    private lateinit var dispSatSlider: Slider
    private lateinit var dispVibSlider: Slider
    private lateinit var dispTempSlider: Slider
    private lateinit var dispTintSlider: Slider
    private var dispFilterModes: List<Pair<Int, String>> = emptyList()
    private lateinit var dispDpiBox: LinearLayout
    private lateinit var dispInfo: TextView
    private lateinit var dispSummary: TextView

    // ---- tabs (หลัก / เฟรม / จอ / สี)
    private class TabItem(val root: View, val icon: TabIcon, val label: TextView) {
        fun setActive(on: Boolean) {
            label.setTextColor(if (on) Palette.AMBER else Palette.MUTED)
            icon.setActive(on)
        }
    }
    private val tabs = ArrayList<TabItem>()
    private var tabPages: List<View> = emptyList()
    private var curTab = 0
    private lateinit var tabRow: LinearLayout

    // ---- readiness checklist on the home tab
    private class StatusRow(val dot: View, val state: TextView)
    private val statusRows = ArrayList<StatusRow>()
    private lateinit var headerChip: TextView
    private lateinit var tabIndicator: View

    // Quality = optical-flow resolution (short side, px), passed as FluxaOptions.flowSide.
    private val prefs by lazy { getSharedPreferences("fluxa", Context.MODE_PRIVATE) }

    private fun saveInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun saveBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        curTab = (savedInstanceState?.getInt(STATE_TAB) ?: 0).coerceIn(0, 3)
        LogStore.installCrashHandler(this)
        // first start: remember the current resolution + DPI so they can always be restored
        DisplayTuner.captureOriginalIfNeeded(this)
        // a previous session died while the screen was reduced -> put it back
        if (DisplayTuner.isApplied(this) && !CaptureService.running) {
            Thread({
                if (DisplayTuner.restore(this)) {
                    runOnUiThread { Toast.makeText(this, "คืนค่าความละเอียด/DPI เดิมแล้ว", Toast.LENGTH_SHORT).show() }
                }
            }, "fluxa-display-restore").start()
        }
        kit = Kit(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val page = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        val homeCards = ArrayList<View>()
        val frameCards = ArrayList<View>()
        val screenCards = ArrayList<View>()
        val colorCards = ArrayList<View>()
        homeCards.add(buildBrandHeader())
        homeCards.add(buildStatusCard())

        // ---- mode: ปกติ (as before) / อัดจอ (also save the shown frames as mp4)
        modeSeg = Segmented(kit, listOf("ปกติ", "อัดจอ"), 0) { i ->
            saveInt(KEY_REC_MODE, i)
            recordBox.visibility = if (i == 1) View.VISIBLE else View.GONE
            true
        }
        audioSeg = Segmented(
            kit, listOf("เสียงแอพ", "เสียงแอพ + เสียงนอก"), 0, textSp = 13f,
        ) { i -> saveInt(KEY_REC_AUDIO, i); true }
        orientSeg = Segmented(kit, listOf("แนวนอน", "แนวตั้ง"), 0) { i -> saveInt(KEY_REC_ORIENT, i); true }

        recordBox = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        recordBox.addView(kit.caption("เสียงที่อัด"))
        recordBox.addView(audioSeg)
        recordBox.addView(kit.caption("แนววิดีโอ"))
        recordBox.addView(orientSeg)
        recordBox.addView(
            kit.text(
                "อัดเฟรมที่แสดงบนจอ (รวมเฟรมที่เจนเพิ่ม) เป็น mp4 · ไฟล์อยู่ใน Movies/${getString(R.string.app_name)} · " +
                    "\"เสียงนอก\" = ไมโครโฟน · ถ้าแนวไม่ตรงกับเกมจะมีขอบดำ",
                12f, Palette.DIM,
            ).apply { setPadding(0, kit.dp(12), 0, 0) },
        )

        val modeCard = kit.card("โหมด", Palette.AMBER)
        modeCard.addView(modeSeg)
        modeCard.addView(recordBox)
        homeCards.add(modeCard)

        // ---- output
        multiplierSlider = Slider(
            kit, FluxaOptions.MIN_MULTIPLIER, FluxaOptions.MAX_MULTIPLIER, FluxaOptions.DEFAULT_MULTIPLIER,
            format = FluxaOptions::multiplierLabel,
        ) { saveInt(KEY_MULT_X, it) }
        presentSeg = Segmented(kit, listOf("FIFO · vsync", "MAILBOX · latency ต่ำ"), 0) { i -> saveInt(KEY_PRESENT, i); true }
        qualitySlider = Slider(
            kit, FluxaOptions.MIN_FLOW_SIDE, FluxaOptions.MAX_FLOW_SIDE, FluxaOptions.DEFAULT_FLOW_SIDE,
            step = FluxaOptions.FLOW_SIDE_STEP, format = { "$it px" },
        ) { saveInt(KEY_FLOW_SIDE, it) }

        genModeSeg = Segmented(kit, FluxaOptions.GEN_MODE_LABELS, FluxaOptions.GEN_MODE_FULL) { i ->
            saveInt(KEY_GEN_MODE, i); true
        }

        targetFpsSlider = Slider(
            kit, FluxaOptions.MIN_TARGET_FPS, FluxaOptions.MAX_TARGET_FPS, FluxaOptions.DEFAULT_TARGET_FPS,
            step = FluxaOptions.TARGET_FPS_STEP, format = FluxaOptions::targetFpsLabel,
        ) { saveInt(KEY_TARGET_FPS, it) }
        outModeSeg = Segmented(kit, FluxaOptions.OUT_MODE_LABELS, FluxaOptions.OUT_MODE_MULTIPLIER) { i ->
            saveInt(KEY_OUT_MODE, i); updateOutModeViews(i); true
        }
        multCaption = kit.caption("ตัวคูณเฟรม (เจนจำนวนเฟรมคงที่)")
        fpsCaption = kit.caption("เป้าหมาย fps (เจนเท่าที่จำเป็นเพื่อให้ถึงเป้า)")

        val outCard = kit.card("เอาต์พุต", Palette.EMBER)
        outCard.addView(kit.caption("โหมดเอาต์พุต"))
        outCard.addView(outModeSeg)
        outCard.addView(multCaption)
        outCard.addView(multiplierSlider)
        outCard.addView(fpsCaption)
        outCard.addView(targetFpsSlider)
        updateOutModeViews(FluxaOptions.OUT_MODE_MULTIPLIER)
        outCard.addView(kit.caption("คุณภาพ (DIS flow)"))
        outCard.addView(qualitySlider)
        outCard.addView(kit.caption("อัลกอริทึมเจนเฟรม (ยิ่งเบา GPU ยิ่งน้อย)"))
        outCard.addView(genModeSeg)
        outCard.addView(kit.caption("โหมดแสดงผล"))
        outCard.addView(presentSeg)
        frameCards.add(pageTitle("เฟรม", "ตั้งค่าการเจนเฟรมและโหมดแสดงผล"))
        frameCards.add(outCard)
        val tips = kit.card("เคล็ดลับ", Palette.AMBER)
        listOf(
            "ตัวคูณสูง = ภาพลื่นขึ้น แต่ GPU ทำงานหนักขึ้น",
            "คุณภาพ (DIS flow) สูง = เฟรมที่เจนละเอียดขึ้น แต่หนักขึ้นตาม",
            "อัลกอริทึมเจนเฟรม: เต็ม = ภาพดีสุด · สมดุล/ประหยัด = ตัดการปรับ flow ละเอียด GPU ลดลงมาก · เบาสุด = ตัดทุกอย่างที่ไม่จำเป็น (ขอบวัตถุอาจเบลอ/มี halo เล็กน้อยตอนเคลื่อนที่เร็ว)",
            "ค่าในหน้านี้ปรับสดได้ในแผงควบคุมขณะเล่น (ปัดจากขอบขวา)",
        ).forEachIndexed { i, t ->
            tips.addView(
                kit.text(t, 13f, Palette.MUTED),
                LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = kit.dp(10) },
            )
        }
        frameCards.add(tips)

        // ---- screen resolution + DPI (WRITE_SECURE_SETTINGS)
        screenCards.add(pageTitle("จอ", "ความละเอียด อัปสเกล และความคม"))
        screenCards.add(buildDisplayCard())
        colorCards.add(pageTitle("สี", "ลดสัญญาณรบกวน เกรน และปรับสีภาพ"))
        colorCards.add(buildColorCard())

        // ---- how-to
        val help = kit.card("วิธีใช้", Palette.RUST)
        listOf(
            "เปิดบริการ Accessibility ของ ${getString(R.string.app_name)} (ครั้งแรกครั้งเดียว)",
            "กด เริ่ม แล้วในหน้าต่างแชร์หน้าจอเลือก \"แอพเดียว\" → เลือกเกม",
            "สลับไปเล่นเกม · ปัดจากขอบขวาเพื่อเปิดแผงควบคุม",
        ).forEachIndexed { i, step ->
            val row = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                kit.text("${i + 1}", 12f, Palette.AMBER, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = kit.rounded(Palette.AMBER_SOFT, 11)
                },
                LinearLayout.LayoutParams(kit.dp(22), kit.dp(22)).apply { marginEnd = kit.dp(12) },
            )
            row.addView(kit.text(step, 13.5f, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            help.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = kit.dp(12) })
        }
        help.addView(
            kit.text("ห้ามเลือกทั้งหน้าจอ ไม่งั้น overlay จะถูกจับซ้ำ ต้องใช้ Android 14 ขึ้นไป", 12f, Palette.MUTED)
                .apply { setPadding(0, kit.dp(14), 0, 0) },
        )
        homeCards.add(help)

        fun pageOf(cards: List<View>): ScrollView {
            val box = LinearLayout(kit.ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(16))
            }
            cards.forEachIndexed { i, v ->
                box.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = kit.dp(12) })
            }
            return ScrollView(kit.ctx).apply { isVerticalScrollBarEnabled = false; addView(box) }
        }
        val host = FrameLayout(kit.ctx)
        tabPages = listOf(pageOf(homeCards), pageOf(frameCards), pageOf(screenCards), pageOf(colorCards))
        tabPages.forEachIndexed { i, v ->
            v.visibility = if (i == curTab) View.VISIBLE else View.GONE
            host.addView(v, FrameLayout.LayoutParams(-1, -1))
        }
        page.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- sticky action bar
        val bar = LinearLayout(kit.ctx).apply {
            setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(10))
        }
        bar.addView(
            kit.text("เริ่ม", 16f, Palette.ON_ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(13), 0, kit.dp(13))
                background = kit.gradient(Palette.ACCENT, 12)
                setOnClickListener { requestStart() }
            },
            LinearLayout.LayoutParams(0, -2, 2f),
        )
        bar.addView(
            kit.text("หยุด", 16f, Palette.DANGER, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(13), 0, kit.dp(13))
                background = kit.rounded(0x14EF5350, 12, 0x55EF5350, 1)
                setOnClickListener {
                    startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10) },
        )
        bar.addView(
            kit.text("Log", 16f, Palette.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(13), 0, kit.dp(13))
                background = kit.rounded(Palette.SURFACE_HI, 12, Palette.STROKE, 1)
                setOnClickListener { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10) },
        )
        val dock = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(Palette.SURFACE, 0)
        }
        dock.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)))
        dock.addView(bar, LinearLayout.LayoutParams(-1, -2))
        dock.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)))
        dock.addView(buildTabBar(), LinearLayout.LayoutParams(-1, -2))
        page.addView(dock, LinearLayout.LayoutParams(-1, -2))

        setContentView(page)
        restoreSettings()
        refreshStatus()
    }

    private fun currentDispConfig() = DisplayTuner.Config(
        enabled = dispEnableSeg.selected == 1,
        percent = dispPctSlider.value,
        dpiAuto = dispDpiSeg.selected == 0,
        dpi = dispDpiSlider.value,
        upscale = dispUpscaleSeg.selected == 0,
        upscaleFilter = dispFilterModes.getOrNull(dispFilterSeg.selected)?.first ?: DisplayTuner.FILTER_LINEAR,
        upscaleSharp = dispSharpSlider.value,
        upscaleSharpMethod = DisplayTuner.sharpMethods.getOrNull(dispSharpMethodSeg.selected)?.first
            ?: DisplayTuner.SHARP_RCAS,
        outPercent = dispOutSlider.value,
        deband = dispDebandSlider.value,
        denoise = dispDenoiseSlider.value,
        aa = DisplayTuner.aaModes.getOrNull(dispAaSeg.selected)?.first ?: DisplayTuner.AA_OFF,
        dither = dispDitherSeg.selected == 1,
    )

    private fun refreshDisplayInfo() {
        if (!::dispSummary.isInitialized || !::dispPctSlider.isInitialized || !::dispDpiSlider.isInitialized) return
        val orig = DisplayTuner.original(this)
        val perm = DisplayTuner.canWrite(this)
        dispInfo.text = buildString {
            append(if (orig != null) "เดิม: ${orig.width}×${orig.height} · ${orig.dpi} dpi (บันทึกไว้แล้ว)" else "ยังไม่ได้บันทึกค่าเดิม")
            append('\n')
            append(
                if (perm) "✓ มีสิทธิ์ WRITE_SECURE_SETTINGS"
                else "✗ ยังไม่มีสิทธิ์ · แตะเพื่อคัดลอกคำสั่ง แล้วรันผ่าน adb:\n${DisplayTuner.GRANT_COMMAND}",
            )
        }
        dispInfo.setTextColor(if (perm) Palette.OK else Palette.AMBER)
        dispSummary.text =
            if (orig == null) ""
            else {
                val p = DisplayTuner.plan(orig, currentDispConfig())
                "ตอนเริ่มจะตั้งเป็น ${p.width}×${p.height} · ${p.dpi} dpi"
            }
    }

    private fun buildDisplayCard(): View {
        val card = kit.card("หน้าจอ · ความละเอียด / DPI", Palette.RUST)

        dispInfo = kit.text("", 12f, Palette.MUTED).apply {
            setOnClickListener {
                if (!DisplayTuner.canWrite(this@MainActivity)) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("adb", DisplayTuner.GRANT_COMMAND))
                    Toast.makeText(this@MainActivity, "คัดลอกคำสั่ง adb แล้ว", Toast.LENGTH_SHORT).show()
                }
            }
        }
        card.addView(dispInfo)

        dispEnableSeg = Segmented(kit, listOf("ปิด", "เปิดตอนกดเริ่ม"), 0) { i ->
            saveBool(DisplayTuner.K_ENABLED, i == 1)
            true
        }
        card.addView(kit.caption("ปรับความละเอียด/DPI ตอนเริ่ม · คืนค่าเดิมตอนหยุด"))
        card.addView(dispEnableSeg)

        dispPctSlider = Slider(
            kit, DisplayTuner.MIN_PERCENT, DisplayTuner.MAX_PERCENT, DisplayTuner.DEFAULT_PERCENT,
            step = DisplayTuner.PERCENT_STEP, format = { "$it%" },
            onPreview = { refreshDisplayInfo() },
        ) { saveInt(DisplayTuner.K_PERCENT, it); refreshDisplayInfo() }
        card.addView(kit.caption("ความละเอียด (% ของเดิม) · 101–200% = เรนเดอร์เกินความละเอียดจอจริง (Android จำกัดสูงสุด 2 เท่า) ภาพเนียนขึ้นแต่หนัก GPU/แรมมาก"))
        card.addView(dispPctSlider)

        dispDpiSeg = Segmented(kit, listOf("อัตโนมัติ (ตามสัดส่วน)", "กำหนดเอง"), 0, textSp = 13f) { i ->
            saveBool(DisplayTuner.K_DPI_AUTO, i == 0)
            dispDpiBox.visibility = if (i == 1) View.VISIBLE else View.GONE
            refreshDisplayInfo()
            true
        }
        dispDpiSlider = Slider(
            kit, DisplayTuner.MIN_DPI, DisplayTuner.MAX_DPI, 400,
            step = DisplayTuner.DPI_STEP, format = { "$it dpi" },
            onPreview = { refreshDisplayInfo() },
        ) { saveInt(DisplayTuner.K_DPI, it); refreshDisplayInfo() }
        dispDpiBox = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        dispDpiBox.addView(dispDpiSlider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
        card.addView(kit.caption("DPI"))
        card.addView(dispDpiSeg)
        card.addView(dispDpiBox)

        dispUpscaleSeg = Segmented(kit, listOf("เปิด · ขยายเป็นความละเอียดเดิม", "ปิด"), 0, textSp = 13f) { i ->
            saveBool(DisplayTuner.K_UPSCALE, i == 0)
            true
        }
        card.addView(kit.caption("อัปสเกลเฟรมที่แสดง"))
        card.addView(dispUpscaleSeg)

        dispFilterModes = DisplayTuner.upscaleModes(this)
        val savedMode = prefs.getInt(DisplayTuner.K_UPSCALE_FILTER, DisplayTuner.FILTER_LINEAR)
        dispFilterSeg = Segmented(
            kit, dispFilterModes.map { it.second },
            dispFilterModes.indexOfFirst { it.first == savedMode }.coerceAtLeast(0),
            perRow = 3, textSp = 13f,
        ) { i ->
            saveInt(DisplayTuner.K_UPSCALE_FILTER, dispFilterModes[i].first)
            true
        }
        card.addView(kit.caption("ฟิลเตอร์อัปสเกล · Nearest คม/เป็นบล็อก · Linear นุ่ม · Mitchell นุ่มไม่มีขอบขาว · Catmull-Rom/Lanczos-2/3 คมกว่า Linear · Spline36 ใกล้ Lanczos-3 แต่ขอบขาวน้อยกว่า · EWA Lanczos ถ่วงน้ำหนักแบบวงกลม เส้นเฉียง/เส้นโค้งสะอาดสุด (ไม่มีอคติตามแกน) · SGSR/NIS/FSR1 เน้นขอบ (NIS หนักสุด) · Anime ดันเส้นให้บางคม เหมาะภาพสไตล์อนิเมะ (เปลี่ยนสดได้ในแผงขณะทำงาน)"))
        card.addView(dispFilterSeg)

        dispSharpSlider = Slider(
            kit, 0, DisplayTuner.STRENGTH_MAX, 0, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
        ) { saveInt(DisplayTuner.K_UPSCALE_SHARP, it) }
        card.addView(kit.caption("ความคมหลังอัปสเกล · 0–100% ปกติ · 101–200% = โอเวอร์ไดรฟ์ ขอบอาจเริ่มมีฮาโล"))
        card.addView(dispSharpSlider)

        val savedSharpMethod = prefs.getInt(DisplayTuner.K_UPSCALE_SHARP_METHOD, DisplayTuner.SHARP_RCAS)
        dispSharpMethodSeg = Segmented(
            kit, DisplayTuner.sharpMethods.map { it.second },
            DisplayTuner.sharpMethods.indexOfFirst { it.first == savedSharpMethod }.coerceAtLeast(0),
            textSp = 13f,
        ) { i ->
            saveInt(DisplayTuner.K_UPSCALE_SHARP_METHOD, DisplayTuner.sharpMethods[i].first)
            true
        }
        card.addView(kit.caption("วิธีเพิ่มความคม · RCAS (FSR1) · CAS (ปรับตามคอนทราสต์ของแต่ละช่องสี) · Adaptive (ไม่ยุ่งกับพื้นเรียบ ลดฮาโลที่ขอบ) · Luma (คมเฉพาะความสว่าง ไม่มีขอบสี) · Clarity (เพิ่มคอนทราสต์ระดับกลาง เน้นมิติ/พื้นผิวกว้าง)"))
        card.addView(dispSharpMethodSeg)

        dispOutSlider = Slider(
            kit, DisplayTuner.MIN_OUT_PERCENT, DisplayTuner.MAX_OUT_PERCENT, DisplayTuner.MAX_OUT_PERCENT,
            step = DisplayTuner.OUT_PERCENT_STEP, format = { "$it%" },
        ) { saveInt(DisplayTuner.K_OUT_PERCENT, it) }
        card.addView(kit.caption("ความละเอียดเอาต์พุต (% ของความละเอียดเดิม)", "ต้องมากกว่าความละเอียดที่เรนเดอร์ ไม่งั้นไม่อัปสเกล · 101–200% = ซูเปอร์แซมเปิล หนัก VRAM จำกัดพื้นที่รวมไม่เกิน 16 ล้านพิกเซลอัตโนมัติ"))
        card.addView(dispOutSlider)

        card.addView(
            kit.text("เทียบภาพ ก่อน / หลังอัปสเกล", 14f, Palette.AMBER, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(kit.dp(8), kit.dp(13), kit.dp(8), kit.dp(13))
                background = kit.rounded(0x1AFFA63D, 14, 0x66FFA63D, 1)
                setOnClickListener { startActivity(Intent(this@MainActivity, CompareActivity::class.java)) }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) },
        )
        card.addView(
            kit.text("แบ่งจอครึ่ง ซ้าย = ก่อนอัปสเกล · ขวา = หลัง · ใช้ตาเลือกโหมดและความคม (ใช้ได้ตอนยังไม่รันเซสชัน)", 11.5f, Palette.DIM)
                .apply { setPadding(0, kit.dp(8), 0, 0) },
        )

        dispSummary = kit.text("", 13f, Palette.AMBER, bold = true).apply { setPadding(0, kit.dp(14), 0, 0) }
        card.addView(dispSummary)

        val row = LinearLayout(kit.ctx).apply { setPadding(0, kit.dp(14), 0, 0) }
        fun action(label: String, color: Int, onClick: () -> Unit) = kit.text(label, 14f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(8), kit.dp(12), kit.dp(8), kit.dp(12))
            background = kit.rounded(Palette.SURFACE_HI, 14, Palette.STROKE, 1)
            setOnClickListener { onClick() }
        }
        row.addView(action("ทดลองใช้ตอนนี้", Palette.AMBER) {
            val orig = DisplayTuner.original(this)
            if (orig == null || !DisplayTuner.canWrite(this)) {
                Toast.makeText(this, "ต้องมีสิทธิ์ WRITE_SECURE_SETTINGS ก่อน", Toast.LENGTH_LONG).show()
            } else {
                val plan = DisplayTuner.plan(orig, currentDispConfig())
                // manual try: not auto-restored, use "คืนค่าเดิม" to go back
                if (!DisplayTuner.apply(this, plan, autoRestore = false)) {
                    Toast.makeText(this, "ปรับไม่ได้ (ดู Log)", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(action("คืนค่าเดิม", Palette.OK) {
            val ok = DisplayTuner.restore(this)
            Toast.makeText(this, if (ok) "คืนค่าเดิมแล้ว" else "คืนค่าไม่ได้ (ดู Log)", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10) })
        card.addView(row)
        card.addView(
            kit.text(
                "เกมเรนเดอร์ที่ความละเอียดต่ำลง → เจนเฟรมเร็วขึ้น แล้ว ${getString(R.string.app_name)} ขยายภาพกลับเป็นความละเอียดเดิมก่อนแสดง " +
                    "(ปรับสัดส่วน DPI ให้ UI ขนาดเท่าเดิม) · ถ้าแอพค้าง/ปิดผิดปกติ จะคืนค่าเดิมให้ตอนเปิดแอพครั้งถัดไป",
                11.5f, Palette.DIM,
            ).apply { setPadding(0, kit.dp(12), 0, 0) },
        )
        return card
    }

    /** "สี" tab: denoise / anti-aliasing / deband / dither, film grain and the colour grade. */
    private fun buildColorCard(): View {
        val card = kit.card("ภาพ · สี", Palette.EMBER)

        // ---- extra enhancement passes (also work without upscaling) ----
        dispDenoiseSlider = Slider(
            kit, 0, DisplayTuner.STRENGTH_MAX, 0, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
        ) { saveInt(DisplayTuner.K_ENH_DENOISE, it) }
        card.addView(kit.caption("ลดสัญญาณรบกวน/บล็อก (Denoise/Deblock) · ทำที่ความละเอียดต้นทางก่อนอัปสเกล รักษาขอบไว้ · ส่วน Deblock ได้ผลเต็มที่กับวิดีโอที่แสดงขนาดจริง 1:1 (เปลี่ยนสดได้ในแผง)"))
        card.addView(dispDenoiseSlider)

        val savedAa = prefs.getInt(DisplayTuner.K_ENH_AA, DisplayTuner.AA_OFF)
        dispAaSeg = Segmented(
            kit, DisplayTuner.aaModes.map { it.second },
            DisplayTuner.aaModes.indexOfFirst { it.first == savedAa }.coerceAtLeast(0),
            textSp = 13f,
        ) { i ->
            saveInt(DisplayTuner.K_ENH_AA, DisplayTuner.aaModes[i].first)
            true
        }
        card.addView(kit.caption("ลดฟันปลา (Anti-aliasing) ก่อนอัปสเกล · FXAA นุ่มทั้งภาพ · SMAA-lite แตะเฉพาะพิกเซลที่เป็นขอบ ภาพยังคม (เป็นแบบ 1 pass ไม่ใช่ SMAA เต็ม)"))
        card.addView(dispAaSeg)

        dispDebandSlider = Slider(
            kit, 0, DisplayTuner.STRENGTH_MAX, 0, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
        ) { saveInt(DisplayTuner.K_ENH_DEBAND, it) }
        card.addView(kit.caption("ลดแถบสี (Deband) · ทำท้ายสุดที่ความละเอียดเอาต์พุต ช่วยฉากไล่เฉดที่เป็นขั้น"))
        card.addView(dispDebandSlider)

        val savedDither = prefs.getBoolean(DisplayTuner.K_ENH_DITHER, false)
        dispDitherSeg = Segmented(
            kit, listOf("ปิด", "เปิด"), if (savedDither) 1 else 0, textSp = 13f,
        ) { i ->
            saveBool(DisplayTuner.K_ENH_DITHER, i == 1)
            true
        }
        card.addView(kit.caption("Dither · เติมนอยส์ละเอียดก่อนลงค่า 8-bit ให้แถบสีที่เหลือกลืนไปกับภาพ"))
        card.addView(dispDitherSeg)

        // ---- film grain + colour grade (final pass at output size, also work without upscaling) ----
        dispGrainSlider = Slider(
            kit, 0, DisplayTuner.GRAIN_MAX, 0, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
        ) { saveInt(DisplayTuner.K_LOOK_GRAIN, it) }
        card.addView(kit.caption("ฟิล์มเกรน (Film grain) · เม็ดเกรนนุ่ม ๆ เห็นชัดสุดที่โทนกลาง ขยับใหม่ทุกเฟรม · ทำท้ายสุดที่ความละเอียดเอาต์พุต (เปลี่ยนสดได้ในแผง)"))
        card.addView(dispGrainSlider)

        dispGrainSizeSlider = Slider(
            kit, DisplayTuner.GRAIN_SIZE_MIN, DisplayTuner.GRAIN_SIZE_MAX, DisplayTuner.GRAIN_SIZE_DEFAULT,
            format = { "$it" },
        ) { saveInt(DisplayTuner.K_LOOK_GRAIN_SIZE, it) }
        card.addView(kit.caption("ขนาดเม็ดเกรน · 1 = ละเอียด · 4 = หยาบ (ปรับตามความละเอียดเอาต์พุตให้อัตโนมัติ)"))
        card.addView(dispGrainSizeSlider)

        fun signed(v: Int) = if (v > 0) "+$v" else "$v"
        fun gradeSlider(caption: String, key: String): Slider {
            val slider = Slider(
                kit, -DisplayTuner.GRADE_RANGE, DisplayTuner.GRADE_RANGE, 0, step = 5, format = ::signed,
            ) { saveInt(key, it) }
            card.addView(kit.caption(caption))
            card.addView(slider)
            return slider
        }
        dispBrightSlider = gradeSlider("ความสว่าง (Brightness) · ยกโทนกลางแบบเส้นโค้ง ไม่ทำให้ขาวไหม้หรือดำจม", DisplayTuner.K_LOOK_BRIGHT)
        dispContrastSlider = gradeSlider("คอนทราสต์ (Contrast) · + = โค้งตัว S ไม่ตัดค่า · − = ภาพนุ่มเรียบขึ้น", DisplayTuner.K_LOOK_CONTRAST)
        dispSatSlider = gradeSlider("ความอิ่มสี (Saturation) · −100 = ขาวดำ · +100 = สีจัดเป็นสองเท่า", DisplayTuner.K_LOOK_SAT)
        dispVibSlider = gradeSlider("Vibrance · เร่งเฉพาะสีที่จืด สีที่จัดอยู่แล้วแทบไม่เปลี่ยน", DisplayTuner.K_LOOK_VIB)
        dispTempSlider = gradeSlider("อุณหภูมิสี (White balance) · − = เย็น/ฟ้า · + = อุ่น/ส้ม (รักษาความสว่างเดิม)", DisplayTuner.K_LOOK_TEMP)
        dispTintSlider = gradeSlider("Tint · − = เขียว · + = ม่วงแดง", DisplayTuner.K_LOOK_TINT)
        card.addView(
            kit.text("รีเซ็ตสี/เกรน", 14f, Palette.AMBER, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(kit.dp(8), kit.dp(12), kit.dp(8), kit.dp(12))
                background = kit.rounded(Palette.SURFACE_HI, 14, Palette.STROKE, 1)
                setOnClickListener {
                    prefs.edit()
                        .putInt(DisplayTuner.K_LOOK_GRAIN, 0)
                        .putInt(DisplayTuner.K_LOOK_BRIGHT, 0)
                        .putInt(DisplayTuner.K_LOOK_CONTRAST, 0)
                        .putInt(DisplayTuner.K_LOOK_SAT, 0)
                        .putInt(DisplayTuner.K_LOOK_VIB, 0)
                        .putInt(DisplayTuner.K_LOOK_TEMP, 0)
                        .putInt(DisplayTuner.K_LOOK_TINT, 0)
                        .apply()
                    restoreDisplaySettings()
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) },
        )

        return card
    }

    /** Large title + one-line subtitle at the top of the frame / screen / colour tabs. */
    private fun pageTitle(title: String, subtitle: String): View = LinearLayout(kit.ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(2), kit.dp(4), 0, kit.dp(4))
        addView(kit.text(title, 26f, Palette.TEXT, bold = true))
        addView(
            kit.text(subtitle, 13f, Palette.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(4) },
        )
    }

    /** Home header: logo, app name, and a chip that says whether the system is ready to start. */
    private fun buildBrandHeader(): View {
        val row = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(2), kit.dp(4), 0, kit.dp(4))
        }
        val logo = FrameLayout(kit.ctx).apply {
            background = kit.rounded(0xFF121418.toInt(), 12, Palette.STROKE, 1)
            clipToOutline = true
            addView(
                ImageView(kit.ctx).apply {
                    setImageResource(R.drawable.ic_launcher_foreground)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    scaleX = 1.5f
                    scaleY = 1.5f
                },
                FrameLayout.LayoutParams(-1, -1),
            )
        }
        row.addView(logo, LinearLayout.LayoutParams(kit.dp(46), kit.dp(46)))
        val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.text(getString(R.string.app_name), 22f, Palette.TEXT, bold = true))
        col.addView(
            kit.text("เพิ่มเฟรมเรตแบบเรียลไทม์", 12.5f, Palette.MUTED),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(2) },
        )
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(12) })
        headerChip = kit.text("", 12f, Palette.MUTED, bold = true).apply {
            setPadding(kit.dp(10), kit.dp(6), kit.dp(10), kit.dp(6))
        }
        row.addView(headerChip)
        return row
    }

    /** What has to be set up before "เริ่ม" works; rows are tappable when they can fix themselves. */
    private fun buildStatusCard(): View {
        val card = kit.card("ความพร้อมของระบบ")
        fun row(title: String, sub: String, onClick: (() -> Unit)?) {
            if (statusRows.isNotEmpty()) card.addView(kit.divider(), LinearLayout.LayoutParams(-1, kit.dp(1)))
            val r = LinearLayout(kit.ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, kit.dp(12), 0, kit.dp(12))
                if (onClick != null) setOnClickListener { onClick() }
            }
            val dot = View(kit.ctx)
            r.addView(dot, LinearLayout.LayoutParams(kit.dp(8), kit.dp(8)).apply { marginEnd = kit.dp(12) })
            val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(kit.text(title, 14f, Palette.TEXT, bold = true))
            col.addView(
                kit.text(sub, 12f, Palette.MUTED),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(2) },
            )
            r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val state = kit.text("", 12.5f, Palette.MUTED, bold = true)
            r.addView(state, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(10) })
            card.addView(r, LinearLayout.LayoutParams(-1, -2).apply { if (statusRows.isEmpty()) topMargin = kit.dp(4) })
            statusRows.add(StatusRow(dot, state))
        }
        row("บริการ Accessibility", "ใช้แสดงภาพและแผงควบคุมเหนือเกม") {
            if (OverlayAccessibilityService.instance == null) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        row("สิทธิ์ WRITE_SECURE_SETTINGS", "ใช้ปรับความละเอียด/DPI (ไม่บังคับ) แตะเพื่อคัดลอกคำสั่ง adb") {
            if (!DisplayTuner.canWrite(this)) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", DisplayTuner.GRANT_COMMAND))
                Toast.makeText(this, "คัดลอกคำสั่ง adb แล้ว", Toast.LENGTH_SHORT).show()
            }
        }
        row("Android 14 ขึ้นไป", "จำเป็นสำหรับการจับภาพแอปเดียว", null)
        return card
    }

    /** Dots + state text of the checklist, and the ready / needs-setup chip in the header. */
    private fun refreshStatus() {
        if (statusRows.size < 3 || !::headerChip.isInitialized) return
        val acc = OverlayAccessibilityService.instance != null
        val secure = DisplayTuner.canWrite(this)
        val sdk = Build.VERSION.SDK_INT >= 34
        fun set(i: Int, ok: Boolean, good: String, bad: String, badColor: Int) {
            val color = if (ok) Palette.OK else badColor
            statusRows[i].dot.background = kit.rounded(color, 4)
            statusRows[i].state.text = if (ok) good else bad
            statusRows[i].state.setTextColor(if (ok) Palette.OK else Palette.MUTED)
        }
        set(0, acc, "เปิดแล้ว", "ยังไม่เปิด", Palette.AMBER)
        set(1, secure, "ให้แล้ว", "ยังไม่ให้", Palette.DIM)
        set(2, sdk, "รองรับ", "ไม่รองรับ", Palette.DANGER)
        val ready = acc && sdk
        headerChip.text = if (ready) "พร้อมใช้งาน" else "ต้องตั้งค่า"
        headerChip.setTextColor(if (ready) Palette.OK else Palette.AMBER)
        headerChip.background = kit.rounded(
            if (ready) 0x1F4FD08A else 0x1FFFA83D, 10,
            if (ready) 0x554FD08A else 0x55FFA83D, 1,
        )
    }

    private fun buildTabBar(): View {
        val specs = listOf(
            "หลัก" to TabIcon.Kind.HOME,
            "เฟรม" to TabIcon.Kind.FRAME,
            "จอ" to TabIcon.Kind.SCREEN,
            "สี" to TabIcon.Kind.COLOR,
        )
        val bar = FrameLayout(kit.ctx)
        val row = LinearLayout(kit.ctx)
        specs.forEachIndexed { i, (name, kind) ->
            val icon = TabIcon(kit.ctx, kind)
            val label = kit.text(name, 11f, Palette.MUTED, bold = true)
            val item = LinearLayout(kit.ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, kit.dp(10), 0, kit.dp(8))
                addView(icon, LinearLayout.LayoutParams(kit.dp(26), kit.dp(26)))
                addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(3) })
                setOnClickListener {
                    showTab(i)
                    icon.play() // plays on every tap, also on the tab that is already open
                }
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }
                    false
                }
            }
            tabs.add(TabItem(item, icon, label))
            row.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bar.addView(row, FrameLayout.LayoutParams(-1, -2))
        tabIndicator = View(kit.ctx).apply { background = kit.gradient(Palette.ACCENT, 2) }
        bar.addView(tabIndicator, FrameLayout.LayoutParams(kit.dp(32), kit.dp(3), Gravity.TOP))
        tabRow = row
        row.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> if (r - l > 0) moveIndicator(false) }
        tabs.forEachIndexed { k, t -> t.setActive(k == curTab) }
        return bar
    }

    private fun moveIndicator(animate: Boolean) {
        val w = tabRow.width / tabs.size.toFloat()
        if (w <= 0f) return
        val x = w * curTab + w / 2f - kit.dp(32) / 2f
        tabIndicator.animate().cancel()
        if (animate) {
            tabIndicator.animate().translationX(x).setDuration(280)
                .setInterpolator(OvershootInterpolator(1.2f)).start()
        } else {
            tabIndicator.translationX = x
        }
    }

    /** Switches page: the old one slides/fades out, the new one slides/fades in from the tapped side. */
    private fun showTab(i: Int) {
        if (i == curTab) return
        val from = curTab
        curTab = i
        tabs.forEachIndexed { k, t -> t.setActive(k == i) }
        moveIndicator(true)
        // settle any page left over from a quick earlier switch
        tabPages.forEachIndexed { k, v ->
            v.animate().cancel()
            if (k != i && k != from) {
                v.visibility = View.GONE
                v.alpha = 1f
                v.translationX = 0f
            }
        }
        val dir = if (i > from) 1f else -1f
        val shift = kit.dp(28).toFloat()
        val outV = tabPages[from]
        val inV = tabPages[i]
        outV.animate().alpha(0f).translationX(-dir * shift).setDuration(140).withEndAction {
            outV.visibility = View.GONE
            outV.alpha = 1f
            outV.translationX = 0f
        }.start()
        inV.alpha = 0f
        inV.translationX = dir * shift
        inV.visibility = View.VISIBLE
        inV.animate().alpha(1f).translationX(0f).setDuration(200).start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, curTab)
    }

    /** Shows the slider that belongs to the selected output mode. */
    private fun updateOutModeViews(mode: Int) {
        val target = mode == FluxaOptions.OUT_MODE_TARGET
        multCaption.visibility = if (target) View.GONE else View.VISIBLE
        multiplierSlider.visibility = if (target) View.GONE else View.VISIBLE
        fpsCaption.visibility = if (target) View.VISIBLE else View.GONE
        targetFpsSlider.visibility = if (target) View.VISIBLE else View.GONE
    }

    /** Re-applies the choices saved on the last run (programmatic select never fires listeners). */
    private fun restoreSettings() {
        multiplierSlider.set(FluxaOptions.clampMultiplier(prefs.getInt(KEY_MULT_X, FluxaOptions.DEFAULT_MULTIPLIER)))
        presentSeg.select(if (prefs.getInt(KEY_PRESENT, 0) == 1) 1 else 0)
        qualitySlider.set(FluxaOptions.clampFlowSide(prefs.getInt(KEY_FLOW_SIDE, FluxaOptions.DEFAULT_FLOW_SIDE)))
        genModeSeg.select(FluxaOptions.clampGenMode(prefs.getInt(KEY_GEN_MODE, FluxaOptions.GEN_MODE_FULL)))
        targetFpsSlider.set(FluxaOptions.clampTargetFps(prefs.getInt(KEY_TARGET_FPS, FluxaOptions.DEFAULT_TARGET_FPS)))
        val outMode = if (prefs.getInt(KEY_OUT_MODE, 0) == FluxaOptions.OUT_MODE_TARGET) FluxaOptions.OUT_MODE_TARGET else FluxaOptions.OUT_MODE_MULTIPLIER
        outModeSeg.select(outMode)
        updateOutModeViews(outMode)
        val mode = if (prefs.getInt(KEY_REC_MODE, 0) == 1) 1 else 0
        modeSeg.select(mode)
        recordBox.visibility = if (mode == 1) View.VISIBLE else View.GONE
        audioSeg.select(RecordOptions.clampAudio(prefs.getInt(KEY_REC_AUDIO, RecordOptions.AUDIO_APP)))
        orientSeg.select(if (prefs.getInt(KEY_REC_ORIENT, 0) == 1) 1 else 0)

        restoreDisplaySettings()
    }

    /** Display-card controls from the saved prefs (also re-run on resume: the compare screen edits them). */
    private fun restoreDisplaySettings() {
        val dc = DisplayTuner.config(this)
        dispEnableSeg.select(if (dc.enabled) 1 else 0)
        dispPctSlider.set(dc.percent)
        dispDpiSeg.select(if (dc.dpiAuto) 0 else 1)
        dispDpiSlider.set(dc.dpi)
        dispDpiBox.visibility = if (dc.dpiAuto) View.GONE else View.VISIBLE
        dispUpscaleSeg.select(if (dc.upscale) 0 else 1)
        dispFilterSeg.select(dispFilterModes.indexOfFirst { it.first == dc.upscaleFilter }.coerceAtLeast(0))
        dispSharpSlider.set(dc.upscaleSharp)
        dispSharpMethodSeg.select(
            DisplayTuner.sharpMethods.indexOfFirst { it.first == dc.upscaleSharpMethod }.coerceAtLeast(0),
        )
        dispOutSlider.set(dc.outPercent)
        dispDenoiseSlider.set(dc.denoise)
        dispAaSeg.select(DisplayTuner.aaModes.indexOfFirst { it.first == dc.aa }.coerceAtLeast(0))
        dispDebandSlider.set(dc.deband)
        dispDitherSeg.select(if (dc.dither) 1 else 0)
        dispGrainSlider.set(dc.grain)
        dispGrainSizeSlider.set(dc.grainSize)
        dispBrightSlider.set(dc.brightness)
        dispContrastSlider.set(dc.contrast)
        dispSatSlider.set(dc.saturation)
        dispVibSlider.set(dc.vibrance)
        dispTempSlider.set(dc.temperature)
        dispTintSlider.set(dc.tint)
        refreshDisplayInfo()
    }

    override fun onResume() {
        super.onResume()
        if (::dispOutSlider.isInitialized) {
            // the compare screen may have learned cubic support (new chip) and changed the saved choices
            if (DisplayTuner.upscaleModes(this).size != dispFilterModes.size) {
                recreate()
                return
            }
            restoreDisplaySettings()
        }
        refreshDisplayInfo() // permission may have been granted meanwhile
        refreshStatus()
    }

    private val recording: Boolean get() = modeSeg.selected == 1

    private fun requestStart() {
        if (OverlayAccessibilityService.instance == null) {
            Toast.makeText(this, "เปิด ${getString(R.string.app_name)} overlay ในการช่วยเหลือพิเศษ (Accessibility) ก่อน", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (recording &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            // needed for app-sound capture and the microphone; asked before the share dialog
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        launchCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "ไม่อนุญาตเสียง · จะอัดแค่ภาพ", Toast.LENGTH_LONG).show()
        }
        launchCapture()
    }

    private fun launchCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "ยกเลิกการแชร์หน้าจอ", Toast.LENGTH_SHORT).show()
            return
        }

        val multiplier = multiplierSlider.value
        val presentMode =
            if (presentSeg.selected == 1) FluxaOptions.PRESENT_MAILBOX
            else FluxaOptions.PRESENT_FIFO
        val options = FluxaOptions(
            enabled = true,
            multiplier = multiplier,
            flowSide = qualitySlider.value,
            driverName = null,
            presentMode = presentMode,
            genMode = genModeSeg.selected,
            targetFps = FluxaOptions.clampTargetFps(targetFpsSlider.value),
            targetMode = outModeSeg.selected == FluxaOptions.OUT_MODE_TARGET,
        )
        Fluxa.install(this, options)
        // Picks the display mode that fits multiplier x source rate, stores the refresh rate in Fluxa.
        Fluxa.applyDisplayMode(this)
        val modeId = window.attributes.preferredDisplayModeId

        val service = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureService.EXTRA_DISPLAY_MODE_ID, modeId)
        RecordOptions(
            enabled = recording,
            audio = if (audioSeg.selected == 1) RecordOptions.AUDIO_APP_MIC else RecordOptions.AUDIO_APP,
            portrait = orientSeg.selected == 1,
        ).writeTo(service)
        startForegroundService(service)
        moveTaskToBack(true)
    }

    companion object {
        private const val STATE_TAB = "tab"
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
        private const val REQ_AUDIO = 3

        private const val KEY_MULT_X = "ui_mult_x"
        private const val KEY_PRESENT = "ui_present"
        private const val KEY_GEN_MODE = "ui_gen_mode"
        private const val KEY_TARGET_FPS = "ui_target_fps"
        private const val KEY_OUT_MODE = "ui_out_mode"
        private const val KEY_FLOW_SIDE = "ui_flow_side"
        private const val KEY_REC_MODE = "ui_rec_mode"
        private const val KEY_REC_AUDIO = "ui_rec_audio"
        private const val KEY_REC_ORIENT = "ui_rec_orient"
    }
}
