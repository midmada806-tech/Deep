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
    // Neutral graphite surfaces; the amber accent is reserved for "active" (selected, live value,
    // start) so everything else stays quiet.
    const val BG = 0xFF15171BL.toInt()
    const val SURFACE = 0xFF1D2025L.toInt()
    const val SURFACE_HI = 0xFF262A30L.toInt()
    const val STROKE = 0xFF30353CL.toInt()
    const val TEXT = 0xFFE8EAEDL.toInt()
    const val MUTED = 0xFF9AA0A8L.toInt()
    const val DIM = 0xFF6C727BL.toInt()
    const val AMBER = 0xFFFFA83DL.toInt()
    const val ORANGE = 0xFFF59122L.toInt()
    const val EMBER = 0xFFE8741AL.toInt()
    const val RUST = 0xFFB85A12L.toInt()
    const val OK = 0xFF4FD08AL.toInt()
    const val DANGER = 0xFFEF5350L.toInt()
    /** Text colour on top of the amber accent (dark, for contrast). */
    const val ON_ACCENT = 0xFF1A1305L.toInt()
    const val AMBER_SOFT = 0x26FFA83D
    const val AMBER_LINE = 0x99FFA83D.toInt()

    val ACCENT = intArrayOf(0xFFF59A2BL.toInt(), 0xFFEA7F1CL.toInt())
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
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, 600, false) // semibold, not heavy
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
            background = rounded(Palette.SURFACE, 14, Palette.STROKE, 1)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            if (title != null) {
                addView(text(title, 16f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(-1, -2))
            }
        }

    /** 1 dp hairline between rows. */
    fun divider() = View(ctx).apply { setBackgroundColor(Palette.STROKE) }

    /**
     * Setting label. "Title · explanation" is split into a primary line and a quieter description
     * (a " · " inside brackets is not a split point).
     */
    fun caption(value: String): View {
        val i = value.indexOf(" · ")
        if (i > 0) {
            val head = value.substring(0, i)
            if (head.count { it == '(' } == head.count { it == ')' }) {
                return caption(head, value.substring(i + 3))
            }
        }
        return caption(value, null)
    }

    fun caption(title: String, desc: String?): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(18), 0, dp(8))
        addView(text(title, 14f, Palette.TEXT, bold = true))
        if (!desc.isNullOrEmpty()) {
            addView(
                text(desc, 12f, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) },
            )
        }
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
                setPadding(kit.dp(4), kit.dp(11), kit.dp(4), kit.dp(11))
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
            if (on) kit.rounded(Palette.AMBER_SOFT, 10, Palette.AMBER_LINE, 1)
            else kit.rounded(Palette.SURFACE_HI, 10, Palette.STROKE, 1)
        c.setTextColor(if (on) Palette.TEXT else Palette.MUTED)
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
    /** Optional left-hand label on the value line (the in-game drawer uses it instead of a caption above). */
    private val title: String? = null,
    /** Hide the min / max labels under the track (compact layouts). */
    private val showRange: Boolean = true,
    /** True while a finger is on the thumb (the drawer fades out so the effect is visible live). */
    private val onDrag: (Boolean) -> Unit = {},
    private val onCommit: (Int) -> Unit,
) : LinearLayout(kit.ctx) {
    private val label = kit.text(format(value), 14f, Palette.AMBER, bold = true).apply { fontFeatureSettings = "tnum" }
    private val bar = SeekBar(kit.ctx)
    private var dragging = false

    var value: Int = value
        private set

    init {
        orientation = VERTICAL
        // live value on the right, range ends under the track
        val head = LinearLayout(kit.ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        if (title != null) head.addView(kit.text(title, 13.5f, Palette.TEXT, bold = true), LayoutParams(0, -2, 1f))
        else head.addView(View(kit.ctx), LayoutParams(0, 1, 1f))
        head.addView(label)
        addView(head, LayoutParams(-1, -2))

        val track = kit.rounded(0xFF2D3138.toInt(), 3).apply { setSize(0, kit.dp(4)) }
        val fill = ClipDrawable(kit.gradient(Palette.ACCENT, 3).apply { setSize(0, kit.dp(4)) }, Gravity.START, ClipDrawable.HORIZONTAL)
        bar.progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(kit.dp(1), 0x33000000)
            setSize(kit.dp(22), kit.dp(22))
        }
        bar.thumb = thumb
        bar.splitTrack = false
        bar.thumbOffset = 0
        bar.setPadding(kit.dp(11), kit.dp(8), kit.dp(11), kit.dp(8))
        bar.max = (max - min) / step
        bar.progress = (value - min) / step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                this@Slider.value = min + p * step
                label.text = format(this@Slider.value)
                onPreview(this@Slider.value)
                if (!fromUser && !dragging) onCommit(this@Slider.value)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true; onDrag(true) }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                onDrag(false)
                onCommit(this@Slider.value)
            }
        })
        addView(bar, LayoutParams(-1, -2).apply { topMargin = kit.dp(2) })
        val foot = LinearLayout(kit.ctx)
        foot.addView(kit.text(format(min), 11f, Palette.DIM).apply { fontFeatureSettings = "tnum" }, LayoutParams(0, -2, 1f))
        foot.addView(kit.text(format(max), 11f, Palette.DIM).apply { gravity = Gravity.END; fontFeatureSettings = "tnum" }, LayoutParams(0, -2, 1f))
        if (showRange) addView(foot, LayoutParams(-1, -2).apply { marginStart = kit.dp(2); marginEnd = kit.dp(2) })
    }

    /** Greys the slider out and ignores touches (e.g. a setting that cannot change right now). */
    fun setLocked(locked: Boolean) {
        bar.isEnabled = !locked
        alpha = if (locked) 0.4f else 1f
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
