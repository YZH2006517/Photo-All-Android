package org.photoall.film.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import org.photoall.film.ui.Style
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A hue as a colour to show (degrees), at [s] saturation and [l] lightness. */
internal fun hueColour(h: Float, s: Float = 0.75f, l: Float = 0.55f): Int {
    val c = FloatArray(3)
    Grade.hslToRgb(((h % 360f) + 360f) % 360f, s, l, c)
    return Color.rgb((c[0] * 255).roundToInt(), (c[1] * 255).roundToInt(), (c[2] * 255).roundToInt())
}

/**
 * 曲线: the tone curve of one channel over the picture's histogram. Drag a point to move it,
 * touch the line to add one, double tap a point (or drag it off the top or bottom) to take it
 * away. The ends stay at the ends but move up and down (and inwards).
 */
class CurveView(context: Context, private val look: Look, private val onChange: () -> Unit,
                private val onTick: () -> Unit) : View(context) {
    var channel = 0
        set(v) { field = v; invalidate() }
    var histogram: IntArray? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val box = RectF()
    private var held = -1          // the point under the finger
    private var leaving = false    // dragged off: goes on lifting the finger
    private var grabDX = 0f
    private var grabDY = 0f

    private val colours get() = intArrayOf(Style.TEXT, 0xffe5534b.toInt(), 0xff57ab5a.toInt(), 0xff539bf5.toInt())

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val i = nearest(e.x, e.y)
            val pts = look.curves[channel]
            if (i > 0 && i < pts.size / 2 - 1) {
                remove(i); held = -1
                onChange(); onTick(); invalidate()
            }
            return true
        }
    })

    private fun px(x: Float) = box.left + x * box.width()
    private fun py(y: Float) = box.bottom - y * box.height()

    private fun nearest(x: Float, y: Float): Int {
        val pts = look.curves[channel]
        var best = -1; var bestD = 26 * d
        for (i in 0 until pts.size / 2) {
            val dd = hypot(x - px(pts[2 * i]), y - py(pts[2 * i + 1]))
            if (dd < bestD) { bestD = dd; best = i }
        }
        return best
    }

    private fun remove(i: Int) {
        val pts = look.curves[channel]
        look.curves[channel] = FloatArray(pts.size - 2).also { out ->
            var o = 0
            for (k in 0 until pts.size / 2) if (k != i) { out[o++] = pts[2 * k]; out[o++] = pts[2 * k + 1] }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                held = nearest(e.x, e.y)
                leaving = false
                if (held < 0) {
                    // on the line: a new point there
                    val x = ((e.x - box.left) / box.width()).coerceIn(0f, 1f)
                    val cy = py(Spline(look.curves[channel]).at(x))
                    val pts = look.curves[channel]
                    val n = pts.size / 2
                    if (abs(e.y - cy) < 30 * d && n < 12 && x > pts[0] + 0.03f && x < pts[2 * n - 2] - 0.03f &&
                        (0 until n).none { abs(pts[2 * it] - x) < 0.03f }) {
                        var at = 0
                        while (at < n && pts[2 * at] < x) at++
                        val out = FloatArray(pts.size + 2)
                        System.arraycopy(pts, 0, out, 0, 2 * at)
                        out[2 * at] = x; out[2 * at + 1] = ((box.bottom - cy) / box.height()).coerceIn(0f, 1f)
                        System.arraycopy(pts, 2 * at, out, 2 * at + 2, pts.size - 2 * at)
                        look.curves[channel] = out
                        held = at
                        onTick()
                    }
                }
                if (held >= 0) {
                    val pts = look.curves[channel]
                    grabDX = px(pts[2 * held]) - e.x; grabDY = py(pts[2 * held + 1]) - e.y
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (held >= 0) {
                val pts = look.curves[channel]
                val n = pts.size / 2
                val gap = 0.02f
                val lo = if (held == 0) 0f else pts[2 * (held - 1)] + gap
                val hi = if (held == n - 1) 1f else pts[2 * (held + 1)] - gap
                val tx = (e.x + grabDX - box.left) / box.width()
                val ty = (box.bottom - (e.y + grabDY)) / box.height()
                val inner = held in 1 until n - 1
                val off = inner && (ty < -0.12f || ty > 1.12f)
                if (off != leaving) { leaving = off; onTick() }
                pts[2 * held] = tx.coerceIn(lo, max(lo, hi))
                pts[2 * held + 1] = ty.coerceIn(0f, 1f)
                onChange()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (held >= 0 && leaving) { remove(held); onChange() }
                held = -1; leaving = false
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val pad = 12 * d
        box.set(pad, pad, width - pad, height - pad)
        // the frame and its quarters
        p.style = Paint.Style.FILL; p.color = Style.SURFACE
        c.drawRoundRect(box.left - 6 * d, box.top - 6 * d, box.right + 6 * d, box.bottom + 6 * d, 12 * d, 12 * d, p)
        histogram?.let { h ->
            val top = h.maxOrNull()?.takeIf { it > 0 } ?: 1
            path.reset(); path.moveTo(box.left, box.bottom)
            for (i in 0..255) path.lineTo(px(i / 255f), box.bottom - box.height() * 0.92f * (h[i].toFloat() / top).let { kotlin.math.sqrt(it) })
            path.lineTo(box.right, box.bottom); path.close()
            p.color = Style.TEXT_3; p.alpha = 60
            c.drawPath(path, p)
            p.alpha = 255
        }
        line.strokeWidth = maxOf(1f, 0.6f * d); line.color = Style.HAIRLINE
        for (i in 1..3) {
            c.drawLine(px(i / 4f), box.top, px(i / 4f), box.bottom, line)
            c.drawLine(box.left, py(i / 4f), box.right, py(i / 4f), line)
        }
        c.drawRect(box, line)
        line.pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
        c.drawLine(box.left, box.bottom, box.right, box.top, line)
        line.pathEffect = null
        // the other channels, faint, when they are bent
        for (ch in 0..3) if (ch != channel && look.curveEdited(ch)) drawCurve(c, ch, 1.2f * d, 110)
        drawCurve(c, channel, 2.2f * d, 255)
        // the points
        val pts = look.curves[channel]
        for (i in 0 until pts.size / 2) {
            val x = px(pts[2 * i]); val y = py(pts[2 * i + 1])
            val gone = i == held && leaving
            p.color = if (i == held) Style.ACCENT else colours[channel]
            p.alpha = if (gone) 70 else 255
            c.drawCircle(x, y, (if (i == held) 7f else 5.5f) * d, p)
            p.color = Style.SURFACE; p.alpha = if (gone) 70 else 255
            c.drawCircle(x, y, 2.4f * d, p)
            p.alpha = 255
        }
        // while a point moves: its input and output
        if (held >= 0) {
            val label = if (leaving) "松手删除这个点" else
                "输入 ${(pts[2 * held] * 255).roundToInt()}  →  输出 ${(pts[2 * held + 1] * 255).roundToInt()}"
            p.textSize = 11 * d; p.typeface = Style.medium; p.color = Style.TEXT_2; p.textAlign = Paint.Align.LEFT
            p.fontFeatureSettings = "tnum"
            c.drawText(label, box.left + 8 * d, box.top + 16 * d, p)
        }
    }

    private fun drawCurve(c: Canvas, ch: Int, width: Float, alpha: Int) {
        val s = Spline(look.curves[ch])
        path.reset()
        val steps = 96
        for (i in 0..steps) {
            val x = i / steps.toFloat()
            val y = s.at(x).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y))
        }
        line.strokeWidth = width; line.color = colours[ch]; line.alpha = alpha
        c.drawPath(path, line)
        line.alpha = 255
    }
}

/**
 * A thin slider with its name and number above it: drag anywhere on it (finely, by how far the
 * finger moves), double tap to put it back. The track may show what it does (a colour ramp).
 */
class LineSlider(context: Context, var label: String, var min: Float = -100f, var max: Float = 100f,
                 var rest: Float = 0f) : View(context) {
    var value = rest
        private set
    var ramp: IntArray? = null
        set(v) { field = v; shader = null; invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onTick: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null
    private var shaderW = 0
    private var lastX = 0f
    private var raw = 0f
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (value != rest) { set(rest); onTick?.invoke() }
            return true
        }
    })

    fun setValueQuiet(v: Float) { value = v.coerceIn(min, max); invalidate() }

    private fun set(v: Float) {
        val q = v.roundToInt().toFloat().coerceIn(min, max)
        if (q == value) return
        value = q
        onChange?.invoke(q)
        invalidate()
    }

    private val left get() = 20 * d
    private val right get() = width - 20 * d

    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); lastX = e.x; raw = value }
            MotionEvent.ACTION_MOVE -> {
                val was = raw
                raw = (raw + (e.x - lastX) / max(1f, right - left) * (max - min)).coerceIn(min, max)
                lastX = e.x
                // the resting value holds a moment as the finger passes it
                val snapped = if (abs(raw - rest) < (max - min) * 0.012f) rest else raw
                if (snapped == rest && value != rest && was != raw) onTick?.invoke()
                set(snapped)
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        p.textSize = 12 * d; p.typeface = Style.regular; p.color = Style.TEXT_2; p.textAlign = Paint.Align.LEFT
        c.drawText(label, left, 15 * d, p)
        p.textAlign = Paint.Align.RIGHT; p.typeface = Style.medium; p.fontFeatureSettings = "tnum"
        p.color = if (value != rest) Style.ACCENT else Style.TEXT_3
        val n = value.roundToInt()
        c.drawText(if (min < 0f && n > 0) "+$n" else "$n", right, 15 * d, p)
        p.fontFeatureSettings = null
        val y = 30 * d
        val k = (value - min) / (max - min)
        val x = left + (right - left) * k
        val ramp = ramp
        p.strokeCap = Paint.Cap.ROUND
        if (ramp != null) {
            if (shader == null || shaderW != width) {
                shader = LinearGradient(left, 0f, right, 0f, ramp, null, Shader.TileMode.CLAMP); shaderW = width
            }
            p.shader = shader; p.strokeWidth = 4 * d; p.alpha = 255
            c.drawLine(left, y, right, y, p)
            p.shader = null
        } else {
            p.color = Style.HAIRLINE; p.strokeWidth = 3 * d
            c.drawLine(left, y, right, y, p)
            val x0 = left + (right - left) * (rest - min) / (max - min)
            if (value != rest) {
                p.color = Style.ACCENT; p.alpha = 200
                c.drawLine(x0, y, x, y, p)
                p.alpha = 255
            }
        }
        // the rest mark and the knob
        val x0 = left + (right - left) * (rest - min) / (max - min)
        p.color = Style.TEXT_3; p.strokeWidth = 1.2f * d; p.strokeCap = Paint.Cap.BUTT
        c.drawLine(x0, y - 6 * d, x0, y - 4 * d, p)
        p.style = Paint.Style.FILL
        p.color = 0x33000000; c.drawCircle(x, y + 0.8f * d, 8.5f * d, p)
        p.color = 0xfff4f2ee.toInt(); c.drawCircle(x, y, 8 * d, p)
        p.color = if (value != rest) Style.ACCENT else 0xff8a8680.toInt(); c.drawCircle(x, y, 2.5f * d, p)
    }
}

/**
 * One colour wheel of 分级: the hue around, the amount out from the middle. Drag (finely) to
 * move the point; double tap to put it back in the middle.
 */
class GradeWheel(context: Context, private val look: Look, val zone: Int, private val onSelect: (Int) -> Unit,
                 private val onChange: () -> Unit, private val onTick: () -> Unit) : View(context) {
    var chosen = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var disc: Shader? = null
    private var fade: Shader? = null
    private var size = 0
    private var lastX = 0f
    private var lastY = 0f
    private var px = 0f
    private var py = 0f
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (look.wheels[zone * 3 + 1] != 0f) {
                look.wheels[zone * 3] = 0f; look.wheels[zone * 3 + 1] = 0f
                onChange(); onTick(); invalidate()
            }
            return true
        }
    })

    private val radius get() = min(width, height) / 2f - 6 * d

    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        val r = radius
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                onSelect(zone)
                lastX = e.x; lastY = e.y
                val a = Math.toRadians(look.wheels[zone * 3].toDouble())
                val m = look.wheels[zone * 3 + 1] / 100f * r
                px = (cos(a) * m).toFloat(); py = (sin(a) * m).toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                px += (e.x - lastX) * 0.6f; py += (e.y - lastY) * 0.6f
                lastX = e.x; lastY = e.y
                val m = hypot(px, py)
                if (m > r) { px *= r / m; py *= r / m }
                val amount = (min(m, r) / r * 100f).roundToInt().toFloat()
                var hue = Math.toDegrees(atan2(py.toDouble(), px.toDouble())).toFloat()
                if (hue < 0f) hue += 360f
                val wasZero = look.wheels[zone * 3 + 1] == 0f
                look.wheels[zone * 3] = if (amount == 0f) 0f else hue.roundToInt().toFloat() % 360f
                look.wheels[zone * 3 + 1] = amount
                if (wasZero != (amount == 0f)) onTick()
                onChange()
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = radius
        if (disc == null || size != width) {
            size = width
            // the hue around (sweep goes clockwise on screen, as the angle does), greyer inwards
            disc = SweepGradient(cx, cy, IntArray(13) { hueColour(it * 30f, 0.7f, 0.55f) }, null)
            fade = RadialGradient(cx, cy, r, intArrayOf(0xff8c8a86.toInt(), 0x008c8a86), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        p.style = Paint.Style.FILL
        p.shader = disc; c.drawCircle(cx, cy, r, p)
        p.shader = fade; c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = (if (chosen) 2.2f else 1f) * d
        p.color = if (chosen) Style.ACCENT else Style.HAIRLINE
        c.drawCircle(cx, cy, r + 2.5f * d, p)
        // the middle and the point
        p.strokeWidth = 1f * d; p.color = 0x66ffffff
        c.drawLine(cx - 4 * d, cy, cx + 4 * d, cy, p); c.drawLine(cx, cy - 4 * d, cx, cy + 4 * d, p)
        val a = Math.toRadians(look.wheels[zone * 3].toDouble())
        val m = look.wheels[zone * 3 + 1] / 100f * r
        val x = cx + (cos(a) * m).toFloat(); val y = cy + (sin(a) * m).toFloat()
        p.style = Paint.Style.FILL
        p.color = 0x55000000; c.drawCircle(x, y + 0.8f * d, 7.5f * d, p)
        p.color = 0xfff4f2ee.toInt(); c.drawCircle(x, y, 7 * d, p)
        p.color = if (look.wheels[zone * 3 + 1] > 0f) hueColour(look.wheels[zone * 3], 0.85f, 0.5f) else 0xff8c8a86.toInt()
        c.drawCircle(x, y, 4.5f * d, p)
    }
}

/** The mixer's eight colours: tap one to work on it; a dot under the ones changed; long press puts one back. */
class BandDots(context: Context, private val look: Look, private val onSelect: (Int) -> Unit,
               private val onReset: (Int) -> Unit) : View(context) {
    var chosen = 0
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f
    private val longPress = Runnable { onReset(slot(downX)); performHapticFeedback(HAPTIC_FEEDBACK_ENABLED) }

    private fun slot(x: Float) = (x / (width / Look.BANDS.size.toFloat())).toInt().coerceIn(0, Look.BANDS.size - 1)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; postDelayed(longPress, 500) }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > 12 * d) removeCallbacks(longPress)
            MotionEvent.ACTION_UP -> { removeCallbacks(longPress); onSelect(slot(e.x)) }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val n = Look.BANDS.size
        val w = width / n.toFloat()
        val cy = 18 * d
        for ((i, b) in Look.BANDS.withIndex()) {
            val cx = w * i + w / 2
            p.style = Paint.Style.FILL; p.color = hueColour(b.hue, 0.78f, 0.52f)
            c.drawCircle(cx, cy, 11 * d, p)
            if (i == chosen) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = Style.TEXT
                c.drawCircle(cx, cy, 15 * d, p)
            }
            p.style = Paint.Style.FILL
            if (look.bandEdited(i)) { p.color = Style.ACCENT; c.drawCircle(cx, cy + 22 * d, 2.2f * d, p) }
            p.textSize = 10 * d; p.textAlign = Paint.Align.CENTER
            p.typeface = if (i == chosen) Style.medium else Style.regular
            p.color = if (i == chosen) Style.TEXT else Style.TEXT_3
            c.drawText(b.label, cx, cy + 39 * d, p)
        }
    }
}
