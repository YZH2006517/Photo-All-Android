package org.photoall.film.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A horizontal ruler in thirds of a stop: drag it under the centre mark; a double tap
 * returns to zero. Each third crossed is reported as one [onStep] (the caller changes
 * the value, plays the haptic tick and sets [thirds] back); pushing past an end reports
 * one step beyond the range per drag, so the caller can answer with the end-stop feel.
 */
class RulerDial(context: Context) : View(context) {
    companion object {
        const val HOLD_MS = 3000L
    }

    /** The value shown, in thirds of a stop. */
    var thirds = 0
        set(value) { field = value; invalidate() }
    var range = -9..9
    var onStep: (Int) -> Unit = {}
    var onReset: () -> Unit = {}
    /**
     * Held still for [HOLD_MS] (a finger on the ruler, not dragging it): a line along its foot
     * fills as the time runs, and this runs at the end. Null: no hold.
     */
    var onHold: (() -> Unit)? = null
    private var holdFrom = 0L
    private var downX = 0f
    private val holding = Runnable { holdFrom = 0L; invalidate(); onHold?.invoke() }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    /** The label under a tick (null: a minor tick). EV by default: whole stops, "-1 0 +1". */
    var labelOf: (Int) -> String? = { t -> if (t % 3 == 0) (t / 3).let { if (it == 0) "0" else "%+d".format(it) } else null }

    private val density = resources.displayMetrics.density
    private val spacing = 18 * density // one third
    private var drag = 0f              // pixels dragged and not yet turned into steps
    private var pushedEnd = false
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density; strokeCap = Paint.Cap.ROUND }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f * density; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val fadePaint = Paint()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { onReset(); return true }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // the ruler fades out towards both ends, into the panel
        // glass: the panel is see-through, so the ticks themselves fade (erased towards the ends)
        val edge = if (Style.glass) 0xff000000.toInt() else Style.SURFACE
        fadePaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(edge, edge and 0x00ffffff, edge and 0x00ffffff, edge),
            floatArrayOf(0f, 0.18f, 0.82f, 1f), Shader.TileMode.CLAMP)
        fadePaint.xfermode = if (Style.glass) android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                downX = event.x
                drag = 0f
                pushedEnd = false
                if (onHold != null) {
                    holdFrom = android.os.SystemClock.uptimeMillis()
                    postDelayed(holding, HOLD_MS)
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // a drag is not a hold
                if (holdFrom != 0L && abs(event.x - downX) > 8 * density) { holdFrom = 0L; removeCallbacks(holding) }
                // dragging left moves the ruler left: larger values come under the mark
                drag += lastX - event.x
                lastX = event.x
                while (abs(drag) >= spacing) {
                    val step = if (drag > 0) 1 else -1
                    drag -= step * spacing
                    if (thirds + step in range) onStep(step)
                    else if (!pushedEnd) { pushedEnd = true; onStep(step) }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = 0f; holdFrom = 0L; removeCallbacks(holding); invalidate() }
        }
        return true
    }

    private var lastX = 0f

    override fun onDraw(canvas: Canvas) {
        val layer = if (Style.glass) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) else -1
        val cx = width / 2f
        val base = height * 0.6f
        val offset = -drag.coerceIn(-spacing, spacing) * 0.5f // a little give while dragging
        for (t in range) {
            val x = cx + (t - thirds) * spacing + offset
            if (x < -spacing || x > width + spacing) continue
            val text = labelOf(t)
            val whole = text != null
            tick.color = if (whole) Style.TEXT_2 else Style.TEXT_3
            canvas.drawLine(x, base - if (whole) 14 * density else 8 * density, x, base, tick)
            if (text != null) {
                label.color = Style.TEXT_2
                canvas.drawText(text, x, base + 13 * density, label)
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
        if (layer >= 0) canvas.restoreToCount(layer)
        // the hold: after a moment's grace, a line filling along the foot
        if (holdFrom != 0L) {
            val held = android.os.SystemClock.uptimeMillis() - holdFrom
            if (held > 500) {
                val k = ((held - 500f) / (HOLD_MS - 500f)).coerceIn(0f, 1f)
                holdPaint.color = Style.ACCENT
                holdPaint.strokeWidth = 2.5f * density
                val y = height - 3 * density
                val half = width * 0.4f * k
                canvas.drawLine(width / 2f - half, y, width / 2f + half, y, holdPaint)
            }
            postInvalidateOnAnimation()
        }
        mark.color = Style.ACCENT
        canvas.drawLine(cx, base - 20 * density, cx, base + 2 * density, mark)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), (40 * density).roundToInt())
    }
}
