package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One of a few, drawn as a track with a raised indicator that slides to the choice.
 * Its height follows the text (any system font size), and a label too wide for its
 * cell is drawn smaller rather than cut off.
 */
class Segmented(context: Context, private val options: List<String>) : View(context) {
    var selected = 0
        private set
    /** Whether a choice can be taken (dimmed when not; tapping it calls [onBlocked]). */
    var enabled: (Int) -> Boolean = { true }
    var onPick: (Int) -> Unit = {}
    var onBlocked: (Int) -> Unit = {}
    var trackColor = Style.BG
    var indicatorColor = Style.SURFACE_2

    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var position = 0f // the indicator, in cells (animated)
    private var animator: ValueAnimator? = null
    private val pad = 3 * density

    init {
        isClickable = true
        isFocusable = true
    }

    /** Choose [index]; [animate] slides the indicator there. */
    fun select(index: Int, animate: Boolean = true) {
        selected = index.coerceIn(0, options.lastIndex)
        contentDescription = options[selected]
        animator?.cancel()
        if (!animate || !isAttachedToWindow || width == 0) {
            position = selected.toFloat()
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, selected.toFloat()).apply {
            duration = 220
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = text.fontMetrics
        val h = max(40 * density, (fm.descent - fm.ascent) + 18 * density)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 11 * density
        fill.color = trackColor
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, fill)
        val cell = (w - 2 * pad) / options.size
        fill.color = indicatorColor
        rect.set(pad + position * cell, pad, pad + (position + 1) * cell, h - pad)
        canvas.drawRoundRect(rect, r - pad, r - pad, fill)
        val base = 14 * resources.displayMetrics.scaledDensity
        for ((i, label) in options.withIndex()) {
            text.textSize = base
            val room = cell - 10 * density
            val wide = text.measureText(label)
            if (wide > room) text.textSize = base * room / wide
            // the label brightens as the indicator arrives
            val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            text.color = when {
                !enabled(i) -> Style.TEXT_3
                else -> blend(Style.TEXT_2, Style.TEXT, near)
            }
            text.typeface = if (i == selected) Style.medium else Style.regular
            val fm = text.fontMetrics
            canvas.drawText(label, pad + (i + 0.5f) * cell, h / 2 - (fm.ascent + fm.descent) / 2, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val cell = (width - 2 * pad) / options.size
            val i = ((event.x - pad) / cell).toInt().coerceIn(0, options.lastIndex)
            if (!enabled(i)) onBlocked(i)
            else if (i != selected) { select(i); onPick(i) }
            performClick()
        }
        return true
    }

    override fun performClick() = super.performClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.RadioGroup::class.java.name
        info.text = options.joinToString("、") + "，当前：" + options[selected]
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xff) + (((b shr shift) and 0xff) - ((a shr shift) and 0xff)) * t).roundToInt()
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
