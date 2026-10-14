package app.fluxa

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Tab-bar icon drawn with the canvas (no image assets) that plays a small animation every time it
 * is tapped: the house hops, the film strip scrolls, the screen's picture grows, the colour
 * circles spin. At rest (progress 0) every animation ends exactly on the static icon.
 */
class TabIcon(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { HOME, FRAME, SCREEN, COLOR }

    private var p = 0f // animation progress 0..1 (0 = at rest)
    private var active = false
    private var anim: ValueAnimator? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f // icon units (24 x 24 box)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()
    private val screenMode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        invalidate()
    }

    /** Runs the tap animation once (restarts it if it is already playing). */
    fun play() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 720
            interpolator = LinearInterpolator()
            addUpdateListener {
                p = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    p = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun ease(t: Float) = t * t * (3f - 2f * t)

    override fun onDraw(c: Canvas) {
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val col = if (active) Palette.AMBER else Palette.MUTED
        stroke.color = col
        c.save()
        c.translate((width - side) / 2f, (height - side) / 2f)
        c.scale(side / 24f, side / 24f)
        when (kind) {
            Kind.HOME -> drawHome(c, col)
            Kind.FRAME -> drawFrame(c, col)
            Kind.SCREEN -> drawScreen(c, col)
            Kind.COLOR -> drawColor(c)
        }
        c.restore()
    }

    /** House that hops; the door lights up at the top of the jump. */
    private fun drawHome(c: Canvas, col: Int) {
        val e = sin(PI * p).toFloat()
        c.translate(0f, -2.4f * e)
        path.reset()
        path.moveTo(2.5f, 11.5f); path.lineTo(12f, 3.5f); path.lineTo(21.5f, 11.5f)
        c.drawPath(path, stroke)
        path.reset()
        path.moveTo(5f, 10f); path.lineTo(5f, 20.5f); path.lineTo(19f, 20.5f); path.lineTo(19f, 10f)
        c.drawPath(path, stroke)
        fill.color = col
        fill.alpha = (70 + 150 * e).toInt()
        rect.set(9.8f, 14f, 14.2f, 20.5f)
        c.drawRoundRect(rect, 1f, 1f, fill)
    }

    /** Film strip that scrolls one frame to the left (the last frame lands where the first was). */
    private fun drawFrame(c: Canvas, col: Int) {
        val e = ease(p)
        c.clipRect(0f, 0f, 24f, 24f)
        fill.color = col
        fill.alpha = 55
        for (i in 0..3) {
            val x = 1f + i * 8f - 8f * e
            rect.set(x, 6f, x + 6f, 18f)
            c.drawRoundRect(rect, 1.6f, 1.6f, fill)
            c.drawRoundRect(rect, 1.6f, 1.6f, stroke)
        }
    }

    /** Monitor whose picture swells and fades back (upscale). */
    private fun drawScreen(c: Canvas, col: Int) {
        val e = sin(PI * p).toFloat()
        rect.set(2.5f, 4f, 21.5f, 16.5f)
        c.drawRoundRect(rect, 2.2f, 2.2f, stroke)
        c.drawLine(12f, 16.5f, 12f, 20f, stroke)
        c.drawLine(8f, 20.5f, 16f, 20.5f, stroke)
        val k = 1f + 0.7f * e
        val hw = 4.5f * k
        val hh = 2.6f * k
        fill.color = col
        fill.alpha = (90 + 120 * e).toInt()
        rect.set(12f - hw, 10.25f - hh, 12f + hw, 10.25f + hh)
        c.drawRoundRect(rect, 1f, 1f, fill)
    }

    /** Three RGB circles (screen-blended) that spin a full turn. */
    private fun drawColor(c: Canvas) {
        c.rotate(ease(p) * 360f, 12f, 12.5f)
        val cols = intArrayOf(0xFFFF5A5A.toInt(), 0xFF4ADE80.toInt(), 0xFF5AA0FF.toInt())
        fill.xfermode = screenMode
        for (i in 0..2) {
            val a = Math.toRadians(-90.0 + 120.0 * i)
            fill.color = cols[i]
            fill.alpha = if (active) 235 else 150
            c.drawCircle(12f + 3.6f * cos(a).toFloat(), 12.5f + 3.6f * sin(a).toFloat(), 5.4f, fill)
        }
        fill.xfermode = null
    }
}
