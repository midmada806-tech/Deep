package app.fluxa

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Fluxa design tokens: warm near-black surfaces with an amber -> orange -> ember accent (low-glare, easy on the eyes). */
object Palette {
    const val BG = 0xFF0B0907L.toInt()
    const val SURFACE = 0xFF14110EL.toInt()
    const val SURFACE_HI = 0xFF1D1914L.toInt()
    const val STROKE = 0xFF2F2821L.toInt()
    const val TEXT = 0xFFEFE8DFL.toInt()
    const val MUTED = 0xFFA89C8CL.toInt()
    const val DIM = 0xFF6F6559L.toInt()
    const val AMBER = 0xFFFFA63DL.toInt()
    const val ORANGE = 0xFFF27A1AL.toInt()
    const val EMBER = 0xFFE0560FL.toInt()
    const val RUST = 0xFFB8420AL.toInt()
    const val OK = 0xFF5BD68AL.toInt()
    const val DANGER = 0xFFEF4444L.toInt()

    val ACCENT = intArrayOf(0xFFD9480FL.toInt(), 0xFFF28C28L.toInt())
}

/** Small view-building kit shared by the launcher screen and the in-game drawer. */
class Kit(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Int) = v * density

    fun text(
        value: String,
        sizeSp: Float,
        color: Int = Palette.TEXT,
        bold: Boolean = false,
        spacing: Float = 0f,
    ) = TextView(ctx).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        if (spacing != 0f) letterSpacing = spacing
        includeFontPadding = false
    }

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    fun gradient(
        colors: IntArray,
        radiusDp: Int,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT,
        strokeColor: Int = 0,
        strokeDp: Int = 0,
    ) = GradientDrawable(orientation, colors).apply {
        cornerRadius = dpf(radiusDp)
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    /** Surface card with a small caps title; children go into the returned box. */
    fun card(title: String? = null, accent: Int = Palette.AMBER): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 22, Palette.STROKE, 1)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            if (title != null) {
                val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                head.addView(View(ctx).apply { background = rounded(accent, 2) }, LinearLayout.LayoutParams(dp(14), dp(4)))
                head.addView(
                    text(title.uppercase(), 11f, Palette.MUTED, bold = true, spacing = 0.14f),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
                )
                addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            }
        }

    fun caption(value: String) = text(value, 12.5f, Palette.MUTED, bold = true).apply {
        setPadding(0, dp(14), 0, dp(8))
    }
}

/**
 * Pill segmented control. [perRow] > labels.size/… wraps into a grid. [onPick] returns false to
 * reject a selection (the previous one stays highlighted).
 */
class Segmented(
    private val kit: Kit,
    labels: List<String>,
    selected: Int,
    private val perRow: Int = labels.size,
    private val textSp: Float = 14f,
    private val onPick: (Int) -> Boolean,
) : LinearLayout(kit.ctx) {
    private val chips = ArrayList<TextView>()
    var selected = selected
        private set

    init {
        orientation = VERTICAL
        var row: LinearLayout? = null
        labels.forEachIndexed { i, label ->
            if (i % perRow == 0) {
                row = LinearLayout(kit.ctx)
                addView(row, LayoutParams(-1, -2).apply { if (i > 0) topMargin = kit.dp(8) })
            }
            val chip = kit.text(label, textSp, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(kit.dp(4), kit.dp(12), kit.dp(4), kit.dp(12))
                setOnClickListener { pick(i) }
            }
            chips.add(chip)
            row?.addView(chip, LayoutParams(0, -2, 1f).apply { if (i % perRow > 0) marginStart = kit.dp(8) })
        }
        val rem = labels.size % perRow
        if (rem != 0) for (k in rem until perRow) {
            row?.addView(View(kit.ctx), LayoutParams(0, 1, 1f).apply { marginStart = kit.dp(8) })
        }
        paint()
    }

    private fun pick(i: Int) {
        if (i == selected) return
        val prev = selected
        selected = i
        if (onPick(i)) paint() else selected = prev
    }

    /** Programmatic selection: repaints without calling [onPick]. */
    fun select(i: Int) {
        selected = i.coerceIn(0, chips.size - 1)
        paint()
    }

    private fun paint() = chips.forEachIndexed { i, c ->
        val on = i == selected
        c.background =
            if (on) kit.gradient(Palette.ACCENT, 14)
            else kit.rounded(Palette.SURFACE_HI, 14, Palette.STROKE, 1)
        c.setTextColor(if (on) Color.WHITE else Palette.MUTED)
    }
}


/**
 * Slider with a live value label. Values run [min]..[max] in [step] increments.
 * [onPreview] fires while dragging (cheap UI only); [onCommit] fires once when the
 * finger lifts (or on a non-touch change) so heavy work like reconfiguring runs once.
 */
class Slider(
    private val kit: Kit,
    private val min: Int,
    private val max: Int,
    value: Int,
    private val step: Int = 1,
    private val format: (Int) -> String = { it.toString() },
    private val onPreview: (Int) -> Unit = {},
    private val onCommit: (Int) -> Unit,
) : LinearLayout(kit.ctx) {
    private val label = kit.text(format(value), 16f, Palette.AMBER, bold = true)
    private val bar = SeekBar(kit.ctx)
    private var dragging = false

    var value: Int = value
        private set

    init {
        orientation = VERTICAL
        val head = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(kit.text(format(min), 11f, Palette.DIM), LayoutParams(0, -2, 1f))
        head.addView(label)
        head.addView(kit.text(format(max), 11f, Palette.DIM).apply { gravity = Gravity.END }, LayoutParams(0, -2, 1f))
        addView(head, LayoutParams(-1, -2))

        val track = kit.rounded(Palette.SURFACE_HI, 4, Palette.STROKE, 1).apply { setSize(0, kit.dp(8)) }
        val fill = ClipDrawable(kit.gradient(Palette.ACCENT, 4).apply { setSize(0, kit.dp(8)) }, Gravity.START, ClipDrawable.HORIZONTAL)
        bar.progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(kit.dp(3), Palette.ORANGE)
            setSize(kit.dp(26), kit.dp(26))
        }
        bar.thumb = thumb
        bar.splitTrack = false
        bar.thumbOffset = 0
        bar.setPadding(kit.dp(13), kit.dp(10), kit.dp(13), kit.dp(10))
        bar.max = (max - min) / step
        bar.progress = (value - min) / step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                this@Slider.value = min + p * step
                label.text = format(this@Slider.value)
                onPreview(this@Slider.value)
                if (!fromUser && !dragging) onCommit(this@Slider.value)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                onCommit(this@Slider.value)
            }
        })
        addView(bar, LayoutParams(-1, -2).apply { topMargin = kit.dp(4) })
    }

    /** Programmatic set: moves the thumb and label without calling [onCommit]. */
    fun set(v: Int) {
        val c = v.coerceIn(min, max)
        value = c
        label.text = format(c)
        dragging = true
        bar.progress = (c - min) / step
        dragging = false
    }
}
