package app.fluxa

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** In-app log viewer: refresh / copy / share / clear, optionally warnings + errors only. */
class LogActivity : Activity() {
    private lateinit var kit: Kit
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var filterSeg: Segmented
    private var rawText = ""
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.installCrashHandler(this)
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

        val head = LinearLayout(kit.ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(4))
        }
        head.addView(kit.text("LOG", 20f, Palette.TEXT, bold = true, spacing = 0.16f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(button("ปิด", Palette.MUTED) { finish() })
        page.addView(head, LinearLayout.LayoutParams(-1, -2))

        filterSeg = Segmented(kit, listOf("ทั้งหมด", "Warn + Error"), 0, textSp = 13f) { load(); true }
        page.addView(filterSeg, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(kit.dp(16), kit.dp(4), kit.dp(16), kit.dp(8))
        })

        logView = TextView(kit.ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Palette.MUTED)
            setTextIsSelectable(true)
            setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10))
            background = kit.rounded(Palette.SURFACE, 14, Palette.STROKE, 1)
            text = "กำลังโหลด…"
        }
        scroll = ScrollView(kit.ctx).apply { addView(logView) }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(kit.dp(12), 0, kit.dp(12), 0)
        })

        val bar = LinearLayout(kit.ctx).apply { setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(12)) }
        val actions = listOf<Pair<String, () -> Unit>>(
            "รีเฟรช" to { load() },
            "คัดลอก" to { copyAll() },
            "แชร์" to { shareAll() },
            "ล้าง" to { clearAll() },
        )
        actions.forEachIndexed { i, (label, action) ->
            bar.addView(
                button(label, if (label == "ล้าง") Palette.DANGER else Palette.AMBER, action),
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = kit.dp(8) },
            )
        }
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))

        setContentView(page)
        load()
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): View =
        kit.text(label, 14f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12))
            background = kit.rounded(Palette.SURFACE_HI, 14, Palette.STROKE, 1)
            setOnClickListener { onClick() }
        }

    private fun load() {
        if (loading) return
        loading = true
        val warnOnly = filterSeg.selected == 1
        Thread({
            val text = LogStore.read(this, warnOnly)
            runOnUiThread {
                loading = false
                rawText = text
                logView.text = colorize(text)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }, "fluxa-log-read").start()
    }

    /** E = red, W = amber, crash block = red, rest muted. threadtime lines: "date time pid tid L tag: msg". */
    private fun colorize(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        var inCrash = false
        for (line in text.lineSequence()) {
            val start = sb.length
            sb.append(line).append('\n')
            if (line.startsWith("=====")) inCrash = line.contains("CRASH")
            val level = line.split(' ').filter { it.isNotEmpty() }.getOrNull(4)
            val color = when {
                inCrash || level == "E" || level == "F" -> Palette.DANGER
                level == "W" -> Palette.AMBER
                else -> Palette.MUTED
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun copyAll() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", rawText))
        Toast.makeText(this, "คัดลอก log แล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun shareAll() {
        // keep under the binder limit: the newest part of the log is the useful part
        val text = if (rawText.length > MAX_SHARE_CHARS) rawText.takeLast(MAX_SHARE_CHARS) else rawText
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "ส่ง log"))
    }

    private fun clearAll() {
        Thread({
            LogStore.clear(this)
            runOnUiThread { load() }
        }, "fluxa-log-clear").start()
    }

    companion object {
        private const val MAX_SHARE_CHARS = 150_000
    }
}
