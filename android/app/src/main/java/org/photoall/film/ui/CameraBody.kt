package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * The landscape screen as a camera body (2.5D): the top plate with ISO, shutter and
 * exposure-compensation dials in the manner of dial-driven film-style cameras, a window
 * holding the film's end tab like a memo holder, a chrome shutter button, leatherette.
 * Original drawing; colours follow the theme only for the top plate (silver for the
 * light theme, black chrome otherwise); dials are always black with white engraving.
 */

/**
 * A top-plate dial seen from behind and a little above: the knurled edge, the engraved
 * values on its face (the one at the index mark in front largest), the index mark and the
 * dial's name on the plate. Drag sideways to turn it: it clicks from value to value
 * ([onTurn] at each), and springs to the nearest when let go. Index 0 is engraved in red
 * (the "A" of automatic).
 */
class CameraDial(context: Context, private val title: String, private val labels: List<String>,
                 private val redFirst: Boolean = true) : View(context) {
    var onTurn: (Int) -> Unit = {}
    /** Dimmed, and turning it only reports (e.g. exposure compensation under manual). */
    var live = true
        set(value) { field = value; invalidate() }
    /** Plate ink for the name under the dial (dark on silver, light on black). */
    var plateInk = 0xffcfcac0.toInt()
        set(value) { field = value; invalidate() }
    var selected = 0
        private set

    private val density = resources.displayMetrics.density
    private var position = 0f // the value under the index mark, fractional while turning
    private var animator: ValueAnimator? = null
    private val stepPx = 24 * density
    private val stepDeg = 55.0 // only the neighbours either side show, well apart
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knurl = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.1f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 9 * density
        letterSpacing = 0.12f
    }
    private val path = Path()
    private val rect = RectF()

    init {
        isClickable = true
        contentDescription = "$title ${labels[0]}"
    }

    fun select(index: Int, animate: Boolean = false) {
        val i = index.coerceIn(0, labels.lastIndex)
        selected = i
        contentDescription = "$title ${labels[i]}"
        if (dragging) return
        animator?.cancel()
        if (!animate) { position = i.toFloat(); invalidate(); return }
        animator = ValueAnimator.ofFloat(position, i.toFloat()).apply {
            duration = 200
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private var dragging = false
    private var downX = 0f
    private var startPos = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                animator?.cancel()
                dragging = true
                downX = e.x
                startPos = position
            }
            MotionEvent.ACTION_MOVE -> {
                // a thumb rolling the dial: leftward brings the next value to the mark
                var p = startPos + (downX - e.x) / stepPx
                val max = labels.lastIndex.toFloat()
                if (p < 0f) p *= 0.25f else if (p > max) p = max + (p - max) * 0.25f // stiff past the ends
                position = p
                val i = p.roundToInt().coerceIn(0, labels.lastIndex)
                if (i != selected) {
                    selected = i
                    contentDescription = "$title ${labels[i]}"
                    onTurn(i)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                select(selected, animate = true)
                performClick()
            }
        }
        return true
    }

    override fun performClick() = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2
        // the dial as large as the cell allows, leaving room under it for the mark and name
        val rx = min(w * 0.46f, (height - 26 * density) / 1.25f)
        val ry = rx * 0.47f                 // the face seen from above at an angle
        val side = rx * 0.3f                // the knurled edge's height
        val top = 3 * density + ry           // centre of the face
        // shadow on the plate
        face.shader = RadialGradient(cx, top + side + ry * 0.6f, rx * 1.15f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawOval(cx - rx * 1.15f, top + side - ry * 0.2f, cx + rx * 1.15f, top + side + ry * 1.6f, face)
        // the knurled edge: a band from the face's rim down to the lower rim
        path.reset()
        path.moveTo(cx - rx, top)
        path.lineTo(cx - rx, top + side)
        rect.set(cx - rx, top + side - ry, cx + rx, top + side + ry)
        path.arcTo(rect, 180f, -180f)
        path.lineTo(cx + rx, top)
        rect.set(cx - rx, top - ry, cx + rx, top + ry)
        path.arcTo(rect, 0f, 180f)
        path.close()
        face.shader = LinearGradient(cx - rx, 0f, cx + rx, 0f,
            intArrayOf(0xff0b0b0b.toInt(), 0xff4a4a48.toInt(), 0xff2a2a29.toInt(), 0xff111111.toInt(), 0xff3b3f44.toInt(), 0xff0d0d0d.toInt()),
            floatArrayOf(0f, 0.28f, 0.42f, 0.75f, 0.94f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, face)
        // ridges, turning with the dial
        val phase = (position * stepDeg) % 9.0
        var a = phase
        while (a < 180.0) {
            val th = Math.toRadians(a)
            val x = cx + rx * cos(th).toFloat()
            val depth = sin(th).toFloat()
            knurl.color = if (((a - phase) / 9.0).roundToInt() % 2 == 0) 0x55ffffff else 0x66000000
            knurl.alpha = (40 + 120 * depth).toInt()
            canvas.drawLine(x, top + ry * depth, x, top + side + ry * depth, knurl)
            a += 4.5
        }
        // the face
        face.shader = RadialGradient(cx - rx * 0.35f, top - ry * 0.6f, rx * 1.4f, 0xff3a3a39.toInt(), 0xff121212.toInt(), Shader.TileMode.CLAMP)
        canvas.drawOval(cx - rx, top - ry, cx + rx, top + ry, face)
        face.shader = null
        face.style = Paint.Style.STROKE
        face.strokeWidth = 1.2f * density
        face.color = 0x66ffffff
        canvas.drawArc(cx - rx, top - ry, cx + rx, top + ry, 190f, 120f, false, face) // lit rim, far side
        face.color = 0x33ffffff
        canvas.drawOval(cx - rx * 0.86f, top - ry * 0.86f, cx + rx * 0.86f, top + ry * 0.86f, face)
        face.style = Paint.Style.FILL
        // engraved values around the face; the one at the front at the index mark
        val r = rx * 0.7f
        for ((i, label) in labels.withIndex()) {
            val deg = 90.0 + (i - position) * stepDeg
            if (deg < 25 || deg > 155) continue
            val th = Math.toRadians(deg)
            val depth = sin(th).toFloat() // 1 at the front
            val x = cx + r * cos(th).toFloat()
            val y = top + r * (ry / rx) * depth
            text.textSize = (6.5f + 5f * depth * depth) * density
            text.color = if (i == 0 && redFirst) 0xffe0452b.toInt() else 0xfff2f0ea.toInt()
            text.alpha = ((if (live) 255 else 110) * (0.25f + 0.75f * depth * depth)).toInt()
            canvas.save()
            canvas.scale(1f, 0.62f + 0.38f * depth, x, y) // engraved on a tilted face
            canvas.drawText(label, x, y + text.textSize * 0.35f, text)
            canvas.restore()
        }
        // the index mark on the plate and the dial's name
        val base = top + side + ry + 5 * density
        path.reset()
        path.moveTo(cx, base - 3 * density)
        path.lineTo(cx - 3.5f * density, base + 2.5f * density)
        path.lineTo(cx + 3.5f * density, base + 2.5f * density)
        path.close()
        face.color = if (live) 0xffe0452b.toInt() else plateInk
        canvas.drawPath(path, face)
        name.color = plateInk
        canvas.drawText(title, cx, base + 14 * density, name)
    }
}

/** The shutter button: a chrome dome in a dark collar, machined rings, a threaded socket. */
class ChromeShutter(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = false

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = min(b.width(), b.height()) / 2f
        // shadow and collar
        paint.shader = RadialGradient(cx + r * 0.08f, cy + r * 0.12f, r, intArrayOf(0x88000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx + r * 0.06f, cy + r * 0.1f, r, paint)
        paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, intArrayOf(0xff4a4a49.toInt(), 0xff141414.toInt(), 0xff2c2c2c.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.94f, paint)
        // the dome: lit from the top left, a little lower when pressed
        val k = if (pressed) 0.8f else 1f
        val dr = r * 0.74f
        paint.shader = RadialGradient(cx - dr * 0.35f, cy - dr * 0.4f, dr * 1.5f,
            intArrayOf(blend(0xfff4f2ee.toInt(), k), blend(0xffb9b7b2.toInt(), k), blend(0xff6d6b67.toInt(), k), blend(0xff3a3937.toInt(), k)),
            floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy + if (pressed) density else 0f, dr, paint)
        paint.shader = null
        // machined rings and the rim catching the light
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.7f * density
        for (f in listOf(0.58f, 0.44f)) {
            paint.color = 0x22000000
            canvas.drawCircle(cx, cy, dr * f, paint)
        }
        paint.strokeWidth = 1.2f * density
        paint.color = 0x88ffffff.toInt()
        canvas.drawArc(cx - dr, cy - dr, cx + dr, cy + dr, 200f, 90f, false, paint)
        // the cable-release socket
        paint.style = Paint.Style.FILL
        paint.color = blend(0xff2a2928.toInt(), k)
        canvas.drawCircle(cx, cy, dr * 0.2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = 0x66ffffff
        canvas.drawCircle(cx, cy, dr * 0.2f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun blend(c: Int, k: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xff) * k).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The body behind the controls: a top plate of [plateHeight] (silver or black chrome,
 * brushed, a bevel and two screws) over pebbled leatherette.
 */
class BodyPanel(private val density: Float, private val plateHeight: Float, private val silver: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leather: BitmapShader by lazy {
        // a pebbled grain, generated once: dark base, soft light and dark specks
        val size = (40 * density).roundToInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xff1b1a18.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(11)
        repeat(size * size / 14) {
            val x = rnd.nextFloat() * size
            val y = rnd.nextFloat() * size
            val rr = (0.6f + rnd.nextFloat() * 1.4f) * density * 0.6f
            p.color = if (rnd.nextBoolean()) 0x0affffff else 0x22000000
            c.drawCircle(x, y, rr * 1.6f, p)
        }
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val brushed: BitmapShader by lazy {
        val w = (64 * density).roundToInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(w, 4, Bitmap.Config.ARGB_8888)
        val rnd = Random(5)
        for (x in 0 until w) for (y in 0 until 4) bmp.setPixel(x, y, ((rnd.nextInt(18)) shl 24) or if (silver) 0x000000 else 0xffffff)
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val plate = plateHeight.coerceAtMost(b.height().toFloat())
        paint.shader = leather
        canvas.drawRect(0f, plate, w, b.height().toFloat(), paint)
        // top plate: metal lit from above, brushed, a bevel where it meets the leather
        paint.shader = LinearGradient(0f, 0f, 0f, plate,
            if (silver) intArrayOf(0xffe9e7e2.toInt(), 0xffc9c6bf.toInt(), 0xffa9a69f.toInt())
            else intArrayOf(0xff3a3937.toInt(), 0xff232221.toInt(), 0xff161615.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, plate, paint)
        paint.shader = brushed
        canvas.drawRect(0f, 0f, w, plate, paint)
        paint.shader = null
        paint.color = if (silver) 0x99ffffff.toInt() else 0x33ffffff
        canvas.drawRect(0f, 0f, w, density, paint)
        paint.color = 0x99000000.toInt()
        canvas.drawRect(0f, plate - density, w, plate + 2 * density, paint)
        paint.color = if (silver) 0x55ffffff else 0x1fffffff
        canvas.drawRect(0f, plate + 2 * density, w, plate + 3 * density, paint)
        // two screws in the plate's corners
        for (x in listOf(10 * density, w - 10 * density)) {
            val y = plate - 9 * density
            paint.shader = RadialGradient(x - density, y - density, 3.2f * density,
                if (silver) intArrayOf(0xfff6f5f2.toInt(), 0xff8f8c86.toInt()) else intArrayOf(0xff6a6966.toInt(), 0xff1a1a1a.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, 3f * density, paint)
            paint.shader = null
            paint.color = 0x88000000.toInt()
            paint.strokeWidth = 0.8f * density
            canvas.drawLine(x - 2 * density, y + density, x + 2 * density, y - density, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** A small dark glass window (the frame counter, the status LCD): sunk into the body. */
class GlassWindow(private val density: Float, private val radiusDp: Float = 6f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val r = radiusDp * density
        rect.set(bounds)
        paint.color = 0x55000000
        canvas.drawRoundRect(rect, r, r, paint)
        rect.inset(density, density)
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xff0d0f0e.toInt(), 0xff1f2321.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        // a sheen across the glass
        paint.color = 0x14ffffff
        canvas.drawRect(rect.left + r, rect.top + density, rect.right - r, rect.top + rect.height() * 0.42f, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The memo holder: a metal-framed slot in the leatherette, holding the film card the way
 * a film camera held the end tab of the film box.
 */
class MemoFrame(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        val p = (6 * density).roundToInt()
        setPadding(p, p, p, p)
    }

    override fun onDraw(canvas: Canvas) {
        val r = 16 * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xff8d8a84.toInt(), 0xff4a4845.toInt(), 0xff2a2928.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        rect.inset(2.5f * density, 2.5f * density)
        paint.color = 0xff0c0c0c.toInt()
        canvas.drawRoundRect(rect, r - 2 * density, r - 2 * density, paint)
        // inner shadow along the top edge of the slot
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.top + 8 * density, 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r - 2 * density, r - 2 * density, paint)
        paint.shader = null
    }
}
