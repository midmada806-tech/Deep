package app.fluxa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Before / after upscale comparison: the picked image is rendered at "render %" (what the game
 * would draw), then upscaled to "output %" by the presenter's own upscaler (same shaders, run on a
 * still). Left of the divider = the low-res frame, right = the upscaled result. Every change is
 * saved to the same settings the session uses, so the choice made by eye carries over.
 */
class CompareActivity : Activity() {
    private lateinit var kit: Kit
    private lateinit var view: CompareView
    private lateinit var info: TextView
    private lateinit var modeBox: FrameLayout
    private lateinit var modeSeg: Segmented
    private lateinit var sharpSlider: Slider
    private lateinit var methodSeg: Segmented
    private lateinit var percentSlider: Slider
    private lateinit var outSlider: Slider
    private lateinit var denoiseSlider: Slider
    private lateinit var aaSeg: Segmented
    private lateinit var debandSlider: Slider
    private lateinit var ditherSeg: Segmented

    private val prefs by lazy { getSharedPreferences("fluxa", Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "fluxa-compare") }
    private val generation = AtomicInteger()

    private var base: Bitmap? = null           // the "full resolution" reference frame
    private var curMode = DisplayTuner.FILTER_LINEAR
    private var vulkanOk = true
    private var loading = false
    private var restoring = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.installCrashHandler(this)
        kit = Kit(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val cfg = DisplayTuner.config(this)
        curMode = cfg.upscaleFilter

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val page = LinearLayout(kit.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        // ---- header
        val header = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(8))
        }
        header.addView(
            kit.text("‹ กลับ", 15f, Palette.AMBER, bold = true).apply {
                setPadding(0, kit.dp(6), kit.dp(14), kit.dp(6))
                setOnClickListener { finish() }
            },
        )
        header.addView(
            kit.text("เทียบภาพ ก่อน / หลังอัปสเกล", 15f, Palette.TEXT, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        page.addView(header, LinearLayout.LayoutParams(-1, -2))

        // ---- viewer + hint
        view = CompareView(kit.ctx, kit)
        val viewer = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
        viewer.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        viewer.addView(
            kit.text("ลากเส้นแบ่งเพื่อเลื่อน · บีบนิ้วซูม · ลากเพื่อเลื่อนภาพ · แตะสองครั้งรีเซ็ต", 11f, Palette.DIM)
                .apply { gravity = Gravity.CENTER; setPadding(kit.dp(8), kit.dp(6), kit.dp(8), kit.dp(6)) },
            LinearLayout.LayoutParams(-1, -2),
        )

        // ---- controls
        val card = kit.card("ตั้งค่าอัปสเกล", Palette.EMBER)
        info = kit.text("", 12f, Palette.AMBER, bold = true)
        card.addView(info)

        card.addView(kit.caption("ฟิลเตอร์อัปสเกล"))
        modeBox = FrameLayout(kit.ctx)
        buildModeSeg()
        card.addView(modeBox)

        sharpSlider = Slider(
            kit, 0, 100, cfg.upscaleSharp, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
            onPreview = { if (!restoring) { prefs.edit().putInt(DisplayTuner.K_UPSCALE_SHARP, it).apply(); schedule() } },
        ) { prefs.edit().putInt(DisplayTuner.K_UPSCALE_SHARP, it).apply() }
        card.addView(kit.caption("ความคมหลังอัปสเกล"))
        card.addView(sharpSlider)

        methodSeg = Segmented(
            kit, DisplayTuner.sharpMethods.map { it.second },
            DisplayTuner.sharpMethods.indexOfFirst { it.first == cfg.upscaleSharpMethod }.coerceAtLeast(0),
            textSp = 13f,
        ) { i ->
            prefs.edit().putInt(DisplayTuner.K_UPSCALE_SHARP_METHOD, DisplayTuner.sharpMethods[i].first).apply()
            schedule()
            true
        }
        card.addView(kit.caption("วิธีเพิ่มความคม"))
        card.addView(methodSeg)

        percentSlider = Slider(
            kit, DisplayTuner.MIN_PERCENT, DisplayTuner.MAX_PERCENT, cfg.percent,
            step = DisplayTuner.PERCENT_STEP, format = { "$it%" },
            onPreview = { if (!restoring) { prefs.edit().putInt(DisplayTuner.K_PERCENT, it).apply(); schedule() } },
        ) { prefs.edit().putInt(DisplayTuner.K_PERCENT, it).apply() }
        card.addView(kit.caption("ความละเอียดที่เรนเดอร์ (% ของภาพเต็ม)"))
        card.addView(percentSlider)

        outSlider = Slider(
            kit, DisplayTuner.MIN_OUT_PERCENT, DisplayTuner.MAX_OUT_PERCENT, cfg.outPercent,
            step = DisplayTuner.OUT_PERCENT_STEP, format = { "$it%" },
            onPreview = { if (!restoring) { prefs.edit().putInt(DisplayTuner.K_OUT_PERCENT, it).apply(); schedule() } },
        ) { prefs.edit().putInt(DisplayTuner.K_OUT_PERCENT, it).apply() }
        card.addView(kit.caption("ความละเอียดเอาต์พุต (% ของภาพเต็ม)"))
        card.addView(outSlider)

        // ---- extra enhancement passes: compared with exactly the same chain the session runs ----
        denoiseSlider = Slider(
            kit, 0, 100, cfg.denoise, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
            onPreview = { if (!restoring) { prefs.edit().putInt(DisplayTuner.K_ENH_DENOISE, it).apply(); schedule() } },
        ) { prefs.edit().putInt(DisplayTuner.K_ENH_DENOISE, it).apply() }
        card.addView(kit.caption("ลดสัญญาณรบกวน (Denoise) · ก่อนอัปสเกล"))
        card.addView(denoiseSlider)

        aaSeg = Segmented(
            kit, DisplayTuner.aaModes.map { it.second },
            DisplayTuner.aaModes.indexOfFirst { it.first == cfg.aa }.coerceAtLeast(0),
            textSp = 13f,
        ) { i ->
            prefs.edit().putInt(DisplayTuner.K_ENH_AA, DisplayTuner.aaModes[i].first).apply()
            schedule()
            true
        }
        card.addView(kit.caption("ลดฟันปลา (Anti-aliasing) · ก่อนอัปสเกล"))
        card.addView(aaSeg)

        debandSlider = Slider(
            kit, 0, 100, cfg.deband, step = 5, format = { if (it == 0) "ปิด" else "$it%" },
            onPreview = { if (!restoring) { prefs.edit().putInt(DisplayTuner.K_ENH_DEBAND, it).apply(); schedule() } },
        ) { prefs.edit().putInt(DisplayTuner.K_ENH_DEBAND, it).apply() }
        card.addView(kit.caption("ลดแถบสี (Deband) · ท้ายสุดที่ความละเอียดเอาต์พุต"))
        card.addView(debandSlider)

        ditherSeg = Segmented(kit, listOf("ปิด", "เปิด"), if (cfg.dither) 1 else 0, textSp = 13f) { i ->
            prefs.edit().putBoolean(DisplayTuner.K_ENH_DITHER, i == 1).apply()
            schedule()
            true
        }
        card.addView(kit.caption("Dither"))
        card.addView(ditherSeg)

        fun action(label: String, color: Int, onClick: () -> Unit) = kit.text(label, 13.5f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(6), kit.dp(12), kit.dp(6), kit.dp(12))
            background = kit.rounded(Palette.SURFACE_HI, 14, Palette.STROKE, 1)
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(kit.ctx).apply { setPadding(0, kit.dp(16), 0, 0) }
        row.addView(action("เลือกภาพ", Palette.AMBER) { pickImage() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(
            action("ภาพทดสอบ", Palette.TEXT) {
                prefs.edit().remove(KEY_URI).apply()
                loadSource()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(8) },
        )
        row.addView(
            action("รีเซ็ตซูม", Palette.TEXT) { view.resetZoom() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(8) },
        )
        card.addView(row)
        card.addView(
            kit.text(
                "ค่าที่เลือกบันทึกให้อัตโนมัติ · ใช้ค่าเดียวกับตอนรันจริง (โหมด/ความคม/Denoise/AA/Deband/Dither/% เรนเดอร์/% เอาต์พุต) · " +
                    "ภาพเต็ม = ภาพที่เลือก (ย่อให้ด้านยาวไม่เกิน $MAX_SIDE px)",
                11.5f, Palette.DIM,
            ).apply { setPadding(0, kit.dp(12), 0, 0) },
        )

        val controls = ScrollView(kit.ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(
                LinearLayout(kit.ctx).apply {
                    setPadding(kit.dp(16), kit.dp(4), kit.dp(16), kit.dp(16))
                    addView(card, LinearLayout.LayoutParams(-1, -2))
                },
            )
        }

        if (landscape) {
            val body = LinearLayout(kit.ctx)
            body.addView(viewer, LinearLayout.LayoutParams(0, -1, 1.5f))
            body.addView(controls, LinearLayout.LayoutParams(0, -1, 1f))
            page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            page.addView(viewer, LinearLayout.LayoutParams(-1, 0, 5f))
            page.addView(controls, LinearLayout.LayoutParams(-1, 0, 4f))
        }
        setContentView(page)
        restoring = false

        if (CaptureService.running) {
            view.message = "หยุดเซสชันก่อน ถึงจะเทียบภาพได้"
            return
        }
        view.message = "กำลังโหลด…"
        worker.execute {
            val caps = FluxaNative.ensureLoaded().let { if (it) FluxaNative.nativeStillInfo() else -1 }
            runOnUiThread {
                if (caps < 0) {
                    vulkanOk = false
                    view.message = "Vulkan ใช้ไม่ได้บนเครื่องนี้ (ดู Log)"
                    return@runOnUiThread
                }
                val cubic = (caps and 2) != 0
                if (cubic && !DisplayTuner.cubicSupported(this)) {
                    DisplayTuner.setCubicSupported(this, true)
                    buildModeSeg()
                }
                loadSource()
            }
        }
    }

    private fun buildModeSeg() {
        val modes = DisplayTuner.upscaleModes(this)
        val at = modes.indexOfFirst { it.first == curMode }
        if (at < 0) curMode = DisplayTuner.FILTER_LINEAR
        modeSeg = Segmented(
            kit, modes.map { it.second }, modes.indexOfFirst { it.first == curMode }.coerceAtLeast(0),
            perRow = 3, textSp = 13f,
        ) { i ->
            curMode = modes[i].first
            prefs.edit().putInt(DisplayTuner.K_UPSCALE_FILTER, curMode).apply()
            schedule()
            true
        }
        modeBox.removeAllViews()
        modeBox.addView(modeSeg)
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
        loadSource()
    }

    /** Decodes the saved image (or builds the test pattern) off the UI thread, then renders. */
    private fun loadSource() {
        if (CaptureService.running || !vulkanOk) return
        loading = true
        view.message = "กำลังโหลดภาพ…"
        val uriString = prefs.getString(KEY_URI, null)
        worker.execute {
            var bmp: Bitmap? = null
            var failed = false
            if (uriString != null) {
                bmp = runCatching { decode(Uri.parse(uriString)) }
                    .onFailure { Log.w(TAG, "decode failed: $it") }
                    .getOrNull()
                if (bmp == null) failed = true
            }
            if (bmp == null) bmp = testPattern()
            val loaded = bmp
            runOnUiThread {
                base = loaded
                loading = false
                if (failed) Toast.makeText(this, "เปิดภาพไม่ได้ · ใช้ภาพทดสอบแทน", Toast.LENGTH_SHORT).show()
                schedule()
            }
        }
    }

    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val longSide = max(raw.width, raw.height)
        if (longSide <= MAX_SIDE) return raw
        val f = MAX_SIDE.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(
            raw, (raw.width * f).roundToInt().coerceAtLeast(2), (raw.height * f).roundToInt().coerceAtLeast(2), true,
        )
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    /** Edges, hairlines, text, a zone plate and a gradient: things that show resampling differences. */
    private fun testPattern(): Bitmap {
        val w = 1920
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1B2A41.toInt(), 0xFF324A5F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // fine grid
        p.strokeWidth = 1f
        p.color = 0x55FFFFFF
        var x = 0
        while (x < w) { c.drawLine(x.toFloat(), 0f, x.toFloat(), 300f, p); x += 12 }
        var y = 0
        while (y < 300) { c.drawLine(0f, y.toFloat(), w.toFloat() * 0.5f, y.toFloat(), p); y += 12 }

        // diagonal + curved edges
        p.color = Color.WHITE
        p.strokeWidth = 2f
        for (i in 0 until 24) c.drawLine(980f + i * 14f, 20f, 1180f + i * 14f, 280f, p)
        p.style = Paint.Style.STROKE
        for (i in 1..10) c.drawCircle(1620f, 160f, i * 14f, p)
        p.style = Paint.Style.FILL

        // zone plate
        val zs = 380
        val zone = IntArray(zs * zs)
        val k = (2.0 * PI / zs).toFloat() // reaches Nyquist (0.5 cycle/px) at the plate's edge
        for (j in 0 until zs) for (i in 0 until zs) {
            val dx = (i - zs / 2).toFloat()
            val dy = (j - zs / 2).toFloat()
            val v = (0.5f + 0.5f * cos(k * (dx * dx + dy * dy) / 2f)).coerceIn(0f, 1f)
            val g = (v * 255f).toInt()
            zone[j * zs + i] = Color.rgb(g, g, g)
        }
        c.drawBitmap(Bitmap.createBitmap(zone, zs, zs, Bitmap.Config.ARGB_8888), 60f, 360f, null)

        // text at several sizes
        p.typeface = Typeface.DEFAULT_BOLD
        var ty = 400f
        for (sz in intArrayOf(64, 44, 32, 24, 18, 14, 11)) {
            p.textSize = sz.toFloat()
            p.color = Color.WHITE
            c.drawText("Deepdrop อัปสเกล ความคม 0123456789", 520f, ty, p)
            ty += sz * 1.35f
        }
        p.typeface = Typeface.DEFAULT

        // colour bars and hard edges
        val cols = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.BLACK)
        for ((i, col) in cols.withIndex()) {
            p.color = col
            c.drawRect(1100f + i * 90f, 380f, 1100f + i * 90f + 90f, 560f, p)
        }
        // checkerboard (1 px and 4 px cells)
        for (cell in intArrayOf(1, 4)) {
            val ox = if (cell == 1) 1100 else 1280
            for (j in 0 until 40) for (i in 0 until 40) {
                p.color = if ((i + j) % 2 == 0) Color.WHITE else Color.BLACK
                c.drawRect((ox + i * cell).toFloat(), (600 + j * cell).toFloat(), (ox + i * cell + cell).toFloat(), (600 + j * cell + cell).toFloat(), p)
            }
        }
        // soft gradient ramp + thin black text-like strokes on white
        p.shader = LinearGradient(60f, 0f, 1860f, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
        c.drawRect(60f, 800f, 1860f, 860f, p)
        p.shader = null
        p.color = Color.WHITE
        c.drawRect(60f, 900f, 1860f, 1040f, p)
        p.color = Color.BLACK
        p.strokeWidth = 1f
        for (i in 0 until 120) c.drawLine(80f + i * 15f, 910f, 80f + i * 15f + (i % 7) * 3f, 1030f, p)
        return bmp
    }

    private fun schedule() {
        val src = base ?: return
        if (CaptureService.running || !vulkanOk || loading) return
        val mode = curMode
        val sharp = sharpSlider.value
        val method = DisplayTuner.sharpMethods.getOrNull(methodSeg.selected)?.first ?: DisplayTuner.SHARP_RCAS
        val percent = percentSlider.value
        val outPercent = outSlider.value
        val aaId = DisplayTuner.aaModes.getOrNull(aaSeg.selected)?.first ?: DisplayTuner.AA_OFF
        val enh = DisplayTuner.packEnhance(debandSlider.value, denoiseSlider.value, aaId, ditherSeg.selected == 1)
        val my = generation.incrementAndGet()
        worker.execute {
            if (my != generation.get()) return@execute // a newer request is queued
            val bw = src.width
            val bh = src.height
            val rw = (bw * percent / 100f).roundToInt().coerceAtLeast(2)
            val rh = (bh * percent / 100f).roundToInt().coerceAtLeast(2)
            val ow = (bw * outPercent / 100f).roundToInt().coerceAtLeast(2)
            val oh = (bh * outPercent / 100f).roundToInt().coerceAtLeast(2)

            val low = if (rw == bw && rh == bh) src else Bitmap.createScaledBitmap(src, rw, rh, true)
            val buf = ByteBuffer.allocate(rw * rh * 4)
            low.copyPixelsToBuffer(buf)
            val t0 = SystemClock.elapsedRealtimeNanos()
            val out = FluxaNative.nativeStillUpscale(buf.array(), rw, rh, ow, oh, mode,
                sharp or (method shl 8) or enh)
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            var result: Bitmap? = null
            if (out != null && out.size >= ow * oh * 4) {
                result = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                result.copyPixelsFromBuffer(ByteBuffer.wrap(out))
            }
            if (my != generation.get()) return@execute
            runOnUiThread {
                view.beforeLabel = "ก่อน · ${rw}×${rh}"
                view.afterLabel = "หลัง · ${ow}×${oh}"
                view.setImages(low, result, bw, bh)
                view.message = if (result == null) "อัปสเกลไม่สำเร็จ (ดู Log)" else null
                val modeName = DisplayTuner.upscaleModes(this).firstOrNull { it.first == mode }?.second ?: "?"
                info.text = buildString {
                    append(modeName)
                    if (sharp > 0) {
                        append(" + ${DisplayTuner.sharpMethods.firstOrNull { it.first == method }?.second ?: "RCAS"} $sharp%")
                    }
                    val extras = buildList {
                        if (denoiseSlider.value > 0) add("Denoise ${denoiseSlider.value}%")
                        if (aaId != DisplayTuner.AA_OFF) add(DisplayTuner.aaModes.first { it.first == aaId }.second)
                        if (debandSlider.value > 0) add("Deband ${debandSlider.value}%")
                        if (ditherSeg.selected == 1) add("Dither")
                    }
                    if (extras.isNotEmpty()) append(" + " + extras.joinToString(" + "))
                    append(" · ${rw}×${rh} → ${ow}×${oh} · ${"%.1f".format(ms)} ms")
                    if (ow <= rw) append("\nเอาต์พุตไม่เกินความละเอียดที่เรนเดอร์ → ตอนรันจริงจะไม่อัปสเกล")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            worker.execute { FluxaNative.nativeStillRelease() }
        }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "FluxaCompare"
        private const val REQ_PICK = 11
        private const val KEY_URI = "cmp_uri"
        private const val MAX_SIDE = 2400
    }
}
