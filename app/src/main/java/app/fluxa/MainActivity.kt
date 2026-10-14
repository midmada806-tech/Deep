package app.fluxa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
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

    // Quality = optical-flow resolution (short side, px), passed as FluxaOptions.flowSide.
    private val prefs by lazy { getSharedPreferences("fluxa", Context.MODE_PRIVATE) }

    private fun saveInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun saveBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val content = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(16))
        }
        fun add(v: View, top: Int = 14) =
            content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(top) })

        content.addView(buildHero())

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
        add(modeCard)

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

        val outCard = kit.card("เอาต์พุต", Palette.EMBER)
        outCard.addView(kit.caption("ตัวคูณเฟรม"))
        outCard.addView(multiplierSlider)
        outCard.addView(kit.caption("คุณภาพ (DIS flow)"))
        outCard.addView(qualitySlider)
        outCard.addView(kit.caption("โหมดแสดงผล"))
        outCard.addView(presentSeg)
        add(outCard)

        // ---- screen resolution + DPI (WRITE_SECURE_SETTINGS)
        add(buildDisplayCard())

        // ---- how-to
        val help = kit.card("วิธีใช้", Palette.RUST)
        listOf(
            "เปิดบริการ Accessibility ของ ${getString(R.string.app_name)} (ครั้งแรกครั้งเดียว)",
            "กด เริ่ม แล้วในหน้าต่างแชร์หน้าจอเลือก \"แอพเดียว\" → เลือกเกม",
            "สลับไปเล่นเกม · ปัดจากขอบขวาเพื่อเปิดแผงควบคุม",
        ).forEachIndexed { i, step ->
            val row = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                kit.text("${i + 1}", 12f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = kit.gradient(intArrayOf(Palette.EMBER, Palette.RUST), 11)
                },
                LinearLayout.LayoutParams(kit.dp(22), kit.dp(22)).apply { marginEnd = kit.dp(12) },
            )
            row.addView(kit.text(step, 13.5f, Palette.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            help.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = kit.dp(12) })
        }
        help.addView(
            kit.text("⚠ ห้ามเลือกทั้งหน้าจอ ไม่งั้น overlay จะถูกจับซ้ำ · ต้องใช้ Android 14 ขึ้นไป", 12f, Palette.DIM)
                .apply { setPadding(0, kit.dp(14), 0, 0) },
        )
        add(help)

        val homeView = ScrollView(kit.ctx).apply { isVerticalScrollBarEnabled = false; addView(content) }
        page.addView(homeView, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- sticky action bar
        val bar = LinearLayout(kit.ctx).apply {
            setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(14))
            background = kit.rounded(Palette.BG, 0)
        }
        bar.addView(
            kit.text("เริ่ม", 17f, Color.WHITE, bold = true, spacing = 0.06f).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(16), 0, kit.dp(16))
                background = kit.gradient(intArrayOf(Palette.AMBER, Palette.ORANGE, Palette.EMBER), 18)
                setShadowLayer(kit.dpf(2), 0f, kit.dpf(1), 0x66000000)
                setOnClickListener { requestStart() }
            },
            LinearLayout.LayoutParams(0, -2, 2f),
        )
        bar.addView(
            kit.text("หยุด", 16f, Palette.DANGER, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(16), 0, kit.dp(16))
                background = kit.rounded(0x1AEF4444, 18, 0x66EF4444, 1)
                setOnClickListener {
                    startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10) },
        )
        bar.addView(
            kit.text("Log", 16f, Palette.AMBER, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(16), 0, kit.dp(16))
                background = kit.rounded(0x1AFFA63D, 18, 0x66FFA63D, 1)
                setOnClickListener { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10) },
        )
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))

        setContentView(page)
        restoreSettings()
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

        dispOutSlider = Slider(
            kit, DisplayTuner.MIN_OUT_PERCENT, DisplayTuner.MAX_OUT_PERCENT, DisplayTuner.MAX_OUT_PERCENT,
            step = DisplayTuner.OUT_PERCENT_STEP, format = { "$it%" },
        ) { saveInt(DisplayTuner.K_OUT_PERCENT, it) }
        card.addView(kit.caption("ความละเอียดเอาต์พุต (% ของความละเอียดเดิม · ต้องมากกว่าความละเอียดที่เรนเดอร์ ไม่งั้นไม่อัปสเกล · 101–200% = ซูเปอร์แซมเปิล หนัก VRAM จำกัดพื้นที่รวมไม่เกิน 16 ล้านพิกเซลอัตโนมัติ)"))
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

    private fun buildHero(): View {
        val hero = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(18), kit.dp(20), kit.dp(18), kit.dp(20))
            background = kit.gradient(
                intArrayOf(0xFF2A1407L.toInt(), 0xFF40200AL.toInt(), 0xFF592B0CL.toInt()),
                28,
                GradientDrawable.Orientation.TL_BR,
                strokeColor = 0x55FFA63D,
                strokeDp = 1,
            )
        }
        hero.addView(
            ImageView(kit.ctx).apply {
                setImageResource(R.drawable.ic_launcher_foreground)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = kit.gradient(intArrayOf(0xFF120E0AL.toInt(), 0xFF271809L.toInt()), 18, GradientDrawable.Orientation.TL_BR, 0x66FFA63D, 1)
                setPadding(kit.dp(2), kit.dp(2), kit.dp(2), kit.dp(2))
            },
            LinearLayout.LayoutParams(kit.dp(68), kit.dp(68)),
        )
        val col = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(kit.text(getString(R.string.app_name).uppercase(), 26f, Palette.TEXT, bold = true, spacing = 0.18f))
        col.addView(
            kit.text("เพิ่มเฟรมเรตแบบเรียลไทม์ ด้วย Vulkan compute", 12.5f, 0xFFDCCBB4L.toInt()),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(5) },
        )
        hero.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(16) })
        return hero
    }

    /** Re-applies the choices saved on the last run (programmatic select never fires listeners). */
    private fun restoreSettings() {
        multiplierSlider.set(FluxaOptions.clampMultiplier(prefs.getInt(KEY_MULT_X, FluxaOptions.DEFAULT_MULTIPLIER)))
        presentSeg.select(if (prefs.getInt(KEY_PRESENT, 0) == 1) 1 else 0)
        qualitySlider.set(FluxaOptions.clampFlowSide(prefs.getInt(KEY_FLOW_SIDE, FluxaOptions.DEFAULT_FLOW_SIDE)))
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
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
        private const val REQ_AUDIO = 3

        private const val KEY_MULT_X = "ui_mult_x"
        private const val KEY_PRESENT = "ui_present"
        private const val KEY_FLOW_SIDE = "ui_flow_side"
        private const val KEY_REC_MODE = "ui_rec_mode"
        private const val KEY_REC_AUDIO = "ui_rec_audio"
        private const val KEY_REC_ORIENT = "ui_rec_orient"
    }
}
