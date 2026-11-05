package org.photoall.film.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A light slider for long scrolling lists. The platform SeekBar publishes its thumb as a
 * system gesture exclusion (and keep-clear) rect; inside a scrolling list every one of
 * them updates on every scrolled frame, which floods the main thread with window
 * messages (thousands per second with ~30 sliders) and drops frames. This one draws a
 * hairline track, the filled part and a round thumb, and decides by the first movement
 * whether a touch is a horizontal drag (the value) or a vertical one (the list scrolls).
 * A tap sets the value where it lands.
 */
class Slider(context: Context) : View(context) {
    var max = 1000
        set(value) { field = maxOf(1, value); invalidate() }
    var progress = 0
        set(value) { field = value.coerceIn(0, max); invalidate() }
    /** Called with the new progress when the user changes it. */
    var onChange: (Int) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.HAIRLINE; strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.TEXT; strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.TEXT }
    private val radius = 8 * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var undecided = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (32 * density).roundToInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private val left get() = paddingLeft + radius
    private val right get() = width - paddingRight - radius

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        val x = left + (right - left) * progress / max
        canvas.drawLine(left, y, right, y, track)
        canvas.drawLine(left, y, x, y, fill)
        canvas.drawCircle(x, y, radius * (if (dragging) 1.15f else 1f), thumb)
    }

    private fun setFrom(x: Float) {
        val p = (((x - left) / (right - left)).coerceIn(0f, 1f) * max).roundToInt()
        if (p != progress) {
            progress = p
            onChange(p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                undecided = true
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (undecided) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dy > slop && dy > dx) {
                        // vertical: let the list scroll
                        undecided = false
                        return false
                    }
                    if (dx > slop) {
                        undecided = false
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (dragging) setFrom(event.x)
                return dragging || undecided
            }
            MotionEvent.ACTION_UP -> {
                if (undecided) setFrom(event.x) // a tap
                val was = dragging
                dragging = false
                undecided = false
                invalidate()
                if (!was) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                undecided = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
