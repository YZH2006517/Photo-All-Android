package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An upright thumb wheel of matte metal, its scale engraved round the rim: a third of a
 * stop between ticks, a longer tick and its figure at each full stop. Rolled up or down
 * with the thumb, one tick per [stepAngle] of turn; the value at the index mark (the
 * accent notch on the housing, at the middle) is the one in use.
 */
class ScaleWheel(context: Context) : View(context) {
    /** Called with +1 / -1 for each tick the wheel is turned past (up = +1). */
    var onStep: (Int) -> Unit = {}
    var onLongPress: () -> Unit = {}
    /** The figure engraved at value [t], or null for a plain tick. */
    var labelOf: (Int) -> String? = { null }
    var range: IntRange = 0..0
        set(value) { field = value; invalidate() }

    /** The value at the index mark. Set from outside, the wheel turns to it. */
    var value = 0
        set(v) {
            if (v == field) return
            val from = field
            field = v
            if (dragging || !isAttachedToWindow) { invalidate(); return }
            // turned from outside: roll the scale over
            settle?.cancel()
            offset = (v - from).toFloat() + offset
            settle = ValueAnimator.ofFloat(offset, 0f).apply {
                duration = 260
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { offset = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    private val density = resources.displayMetrics.density
    private val stepAngle = Math.toRadians(9.0)    // one third of a stop round the rim
    private var offset = 0f                        // fraction of a tick the wheel is turned off the value
    private var dragging = false
    private var lastY = 0f
    private var acc = 0f
    private var settle: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val rect = RectF()
    private val clip = Path()
    private val cavity = Well(density, 12f)
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) { if (!dragging) onLongPress() }
    })

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** The rim's travel for one tick, in pixels of thumb movement. */
    private fun pitch(): Float = ((height / 2f - 6 * density) * stepAngle).toFloat().coerceAtLeast(6 * density)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                settle?.cancel()
                lastY = e.y; acc = 0f; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - lastY
                lastY = e.y
                dragging = dragging || abs(acc + dy) > 4 * density
                acc += dy
                val p = pitch()
                // thumb up rolls the scale up: the next value below comes to the mark
                offset = acc / p
                while (abs(acc) >= p) {
                    val s = if (acc < 0) 1 else -1
                    val next = value + s
                    if (next !in range) { acc = 0f; offset = 0f; onStep(s); break } // the end stop
                    acc += s * p
                    offset = acc / p
                    onStep(s)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                settle = ValueAnimator.ofFloat(offset, 0f).apply {
                    duration = 200
                    interpolator = DecelerateInterpolator(1.4f)
                    addUpdateListener { offset = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        cavity.setBounds(0, 0, width, height)
        cavity.draw(canvas)
        val inset = 5 * density
        val left = inset
        val right = w - inset
        val top = inset
        val bottom = h - inset
        val r = 10 * density
        val light = Toy.light
        // the wheel's shadow in its cavity
        paint.shader = null
        paint.maskFilter = Toy.blur(4 * density)
        paint.color = Toy.SHADOW
        canvas.drawRoundRect(left + 2 * density, top + 3 * density, right + 1 * density, bottom + 3 * density, r, r, paint)
        paint.maskFilter = null
        rect.set(left, top, right, bottom)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        // matte metal: bead-blasted grey, lit from the upper left, rounding away at both ends
        val (hi, mid, lo) = if (light) Triple(0xffdedcd7.toInt(), 0xffb9b7b1.toInt(), 0xff8e8c86.toInt())
            else Triple(0xff7a7b80.toInt(), 0xff55565b.toInt(), 0xff303135.toInt())
        paint.color = 0xffffffff.toInt()
        paint.shader = LinearGradient(left, 0f, right, 0f, intArrayOf(hi, mid, lo), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        paint.shader = null

        val cy = (top + bottom) / 2
        val radius = (bottom - top) / 2
        // knurl on the left half: fine ridges at equal angles (they crowd at the ends)
        val knurlRight = left + (right - left) * 0.34f
        val ridge = Math.toRadians(5.5)
        val turn = offset * stepAngle
        var k = Math.floor((-Math.PI / 2 - turn) / ridge).toInt()
        while (true) {
            val a = turn + k * ridge
            if (a > Math.PI / 2) break
            if (a >= -Math.PI / 2) {
                val y = cy + radius * sin(a).toFloat()
                val facing = cos(a).toFloat().coerceAtLeast(0f)
                paint.color = ((0x26 + (0x40 * (1 - facing)).toInt()) shl 24)
                canvas.drawRect(left, y, knurlRight, y + maxOf(0.8f * density, 1.6f * density * facing), paint)
                paint.color = ((0x10 + (0x28 * facing).toInt()) shl 24) or 0xffffff
                canvas.drawRect(left, y + 1.6f * density * facing, knurlRight, y + 2.2f * density * facing, paint)
            }
            k++
        }
        // the knurled band ends in a groove
        paint.color = if (light) 0x40000000 else 0x66000000
        canvas.drawRect(knurlRight, top, knurlRight + 1.2f * density, bottom, paint)
        paint.color = if (light) 0x66ffffff else 0x22ffffff
        canvas.drawRect(knurlRight + 1.2f * density, top, knurlRight + 2f * density, bottom, paint)

        // the engraved scale on the right: ticks every third, figures at full stops
        val tickX = knurlRight + 4 * density
        val ink = if (light) 0xff2a2826.toInt() else 0xff101012.toInt()
        val glint = if (light) 0x99ffffff.toInt() else 0x33ffffff
        val span = (Math.PI / 2 / stepAngle).toInt() + 1
        for (d in -span..span) {
            val t = value + d
            if (t !in range) continue
            // the value at the mark sits at angle 0; larger values lie below it
            val a = (d + offset) * stepAngle
            if (abs(a) >= Math.PI / 2) continue
            val y = cy + radius * sin(a).toFloat()
            val facing = cos(a).toFloat()
            val label = labelOf(t)
            val len = if (label != null) 10 * density else 6 * density
            val alpha = (255 * facing.coerceIn(0f, 1f)).toInt()
            val thick = maxOf(0.6f, facing) * 1.3f * density
            paint.color = glint; paint.alpha = alpha * (glint ushr 24) / 255
            canvas.drawRect(tickX, y + thick / 2, tickX + len, y + thick / 2 + 0.8f * density, paint)
            paint.color = ink; paint.alpha = alpha
            canvas.drawRect(tickX, y - thick / 2, tickX + len, y + thick / 2, paint)
            if (label != null && facing > 0.35f) {
                text.textSize = 12f * density
                // foreshortened toward the ends
                canvas.save()
                canvas.scale(1f, facing, 0f, y)
                val tx = tickX + len + 3 * density
                val maxW = right - tx - 2 * density
                val tw = text.measureText(label)
                if (tw > maxW && maxW > 0) text.textSize *= maxW / tw
                text.color = glint; text.alpha = alpha * (glint ushr 24) / 255
                canvas.drawText(label, tx, y + text.textSize * 0.36f + 0.8f * density, text)
                text.color = ink; text.alpha = alpha
                canvas.drawText(label, tx, y + text.textSize * 0.36f, text)
                canvas.restore()
            }
        }
        // the ends turning away into shade, the top catching the light
        paint.alpha = 255
        paint.shader = LinearGradient(0f, top, 0f, bottom,
            intArrayOf(0x66000000, 0x00000000, 0x00000000, 0x8c000000.toInt()),
            floatArrayOf(0f, 0.22f, 0.72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        paint.shader = LinearGradient(0f, top, 0f, top + 6 * density, 0x40ffffff, 0x00ffffff, Shader.TileMode.CLAMP)
        canvas.drawRect(left, top, right, top + 6 * density, paint)
        paint.shader = null
        canvas.restore()

        // the index: an accent notch on the housing at the right, pointing at the middle tick
        paint.color = Toy.AMBER
        val notch = Path().apply {
            moveTo(w - 0.5f * density, cy - 4.5f * density)
            lineTo(w - 6 * density, cy)
            lineTo(w - 0.5f * density, cy + 4.5f * density)
            close()
        }
        canvas.drawPath(notch, paint)
        // a fine lit line across the face at the mark
        paint.color = Toy.AMBER
        paint.alpha = 150
        canvas.drawRect(tickX, cy - 0.5f * density, right - 2 * density, cy + 0.5f * density, paint)
        paint.alpha = 255
    }
}
