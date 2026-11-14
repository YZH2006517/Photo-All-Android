package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The landscape 相机拟物 style: a mechanical 35 mm body in matte black paint and
 * bead-blasted aluminium, worked like one. The lens's focus ring (AF at its far end) and
 * aperture ring, the rewind crank and the cartridge on the left; the light meter, the
 * shutter-speed dial with the ASA window, the frame counter and the wind lever on the
 * right. After each shot the shutter stays locked until the lever is pulled all the way.
 */
private const val PAINT = 0xff1c1c1d.toInt()
private const val PAINT_DEEP = 0xff0e0e0f.toInt()
private const val ALU = 0xffa5a49f.toInt()
private const val ALU_DARK = 0xff6a6964.toInt()
private const val ALU_DEEP = 0xff4a4945.toInt()
private const val ENGRAVE = 0xffe6e3d9.toInt()
private const val FEET = 0xffe08a2e.toInt()
private const val RED = 0xffb5301f.toInt()
private const val NEEDLE = 0xffe8503f.toInt()
private const val AMBER = 0xffe3b341.toInt()

private val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

internal fun grain(base: Int, light: Int, dark: Int): BitmapShader {
    val b = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
    val rnd = java.util.Random(5)
    for (y in 0 until 48) for (x in 0 until 48) {
        val r = rnd.nextFloat()
        b.setPixel(x, y, if (r < 0.08f) light else if (r > 0.9f) dark else base)
    }
    return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}

internal val aluGrain by lazy { grain(ALU, 0xffb2b1ac.toInt(), 0xff95948f.toInt()) }
internal val paintGrain by lazy { grain(PAINT, 0xff252526.toInt(), 0xff141415.toInt()) }

abstract class BodyPart(context: Context) : View(context) {
    protected val d = resources.displayMetrics.density
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val path = Path()
    /** A detent or a limit was felt (the activity plays the haptics). */
    var onTick: (strong: Boolean) -> Unit = {}

    init { isFocusable = true }

    protected fun text(canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        p.style = Paint.Style.FILL
        p.shader = null
        p.typeface = condensed
        p.textSize = size * d
        p.textAlign = align
        p.color = color
        canvas.drawText(s, x, y, p)
    }

    /** A soft shadow under what is drawn next, cast away from the light as the phone tilts (2.5D). */
    protected fun lift(radius: Float = 7f, depth: Float = 5f) {
        p.setShadowLayer(radius * d, -Skin.tiltX * 3 * d, (depth + Skin.tiltY * 2) * d, 0xb0000000.toInt())
    }

    protected fun land() = p.clearShadowLayer()

    /** A matte cylinder seen side on: darker toward both edges, no highlight. */
    protected fun cylinder(canvas: Canvas, r: RectF) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, 0f, r.right, 0f,
            intArrayOf(0x9e000000.toInt(), 0x2e000000, 0x00000000, 0x00000000, 0x2e000000, 0x9e000000.toInt()),
            floatArrayOf(0f, 0.16f, 0.34f, 0.66f, 0.84f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(r, p)
        p.shader = null
    }

    /**
     * The wall of a round part standing [heightDp] off the plate, and its shadow: the base sits
     * below the top face and slides with the tilt, so the part reads as a solid (2.5D).
     */
    protected fun wall(canvas: Canvas, cx: Float, cy: Float, r: Float, heightDp: Float, light: Int = 0x6a, dark: Int = 0x26) {
        val hx = 0f
        val hy = 0f
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = 0xff000000.toInt()
        p.setShadowLayer((5 + heightDp * 0.6f) * d, -Skin.tiltX * 4 * d, (3 + heightDp * 0.3f) * d, 0xc0000000.toInt())
        canvas.drawCircle(cx + hx, cy + hy, r, p)
        p.clearShadowLayer()
        val n = (heightDp * 2).toInt().coerceAtLeast(4)
        for (k in n downTo 1) {
            val f = k / n.toFloat()
            val v = (light - (light - dark) * f).toInt()
            p.color = android.graphics.Color.rgb(v, v, v - 4)
            canvas.drawCircle(cx + hx * f, cy + hy * f, r, p)
        }
    }

    /** The shade inside a recess: its upper lip and its lit-side wall throw a shadow on what lies in it. */
    protected fun recess(canvas: Canvas, r: RectF, radius: Float) {
        canvas.save()
        path.reset()
        path.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        p.style = Paint.Style.FILL
        val deep = (11 + Skin.tiltY * 4) * d
        p.shader = LinearGradient(0f, r.top, 0f, r.top + deep, 0xd9000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(r.left, r.top, r.right, r.top + deep, p)
        val side = (7 - Skin.tiltX * 4) * d
        p.shader = LinearGradient(r.left, 0f, r.left + side, 0f, 0xa6000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(r.left, r.top, r.left + side, r.bottom, p)
        p.shader = null
        canvas.restore()
    }

    /**
     * Turned aluminium: the fine rings the lathe left, two faint lobes of reflection that swing
     * as the light moves with the phone, and a chamfer lit on the upper left, shaded lower right.
     */
    protected fun sheen(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val g = android.graphics.SweepGradient(cx, cy, intArrayOf(0x00ffffff, 0x30ffffff, 0x00ffffff, 0x24000000, 0x00ffffff, 0x30ffffff, 0x00ffffff, 0x24000000, 0x00ffffff), null)
        val m = android.graphics.Matrix()
        m.setRotate(-38f + Skin.tiltX * 55f + Skin.tiltY * 25f, cx, cy)
        g.setLocalMatrix(m)
        p.style = Paint.Style.FILL
        p.shader = g
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.5f * d
        p.color = 0x1a000000
        for (k in 1..5) canvas.drawCircle(cx, cy, r * k / 6f, p)
        val box = RectF(cx - r + 0.5f * d, cy - r + 0.5f * d, cx + r - 0.5f * d, cy + r - 0.5f * d)
        p.strokeWidth = 1f * d
        p.color = 0x59ffffff
        canvas.drawArc(box, 195f, 80f, false, p)
        p.color = 0x73000000
        canvas.drawArc(box, 15f, 80f, false, p)
        p.style = Paint.Style.FILL
    }

    protected fun knurl(canvas: Canvas, cx: Float, cy: Float, r: Float, teeth: Int, heightDp: Float = 8f) {
        wall(canvas, cx, cy, r, heightDp)
        // the knurling runs down the wall
        val hx = 0f
        val hy = 0f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * d
        p.color = 0xff1e1d1b.toInt()
        for (i in 0 until teeth) {
            val a = 2 * Math.PI.toFloat() * i / teeth
            if (sin(a) < -0.15f) continue // the far side is hidden by the top
            canvas.drawLine(cx + r * cos(a), cy + r * sin(a), cx + hx + r * cos(a), cy + hy + r * sin(a), p)
        }
        p.style = Paint.Style.FILL
        p.color = ALU_DARK
        canvas.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.1f * d
        p.color = ALU_DEEP
        for (i in 0 until teeth) {
            val a = 2 * Math.PI.toFloat() * i / teeth
            canvas.drawLine(cx + r * 0.84f * cos(a), cy + r * 0.84f * sin(a), cx + r * cos(a), cy + r * sin(a), p)
        }
        // a chamfer where the top meets the wall
        p.color = 0x73000000
        p.strokeWidth = 1.2f * d
        canvas.drawCircle(cx, cy, r - 0.6f * d, p)
        p.style = Paint.Style.FILL
    }
}

// ----- the lens: focus ring (AF at the end), depth-of-field scale, aperture ring ------------------

class LensBarrel(context: Context) : BodyPart(context) {
    val distances = listOf("AF", "∞", "10", "5", "3", "2", "1.5", "1", "0.7", "0.5")
    private val feet = listOf("", "∞", "30", "15", "10", "7", "5", "3.3", "2.3", "1.7")
    val stops = listOf("1.4", "2", "2.8", "4", "5.6", "8", "11", "16")
    /** 0 = AF; 1 = ∞ .. 9 = 0.5 m, continuous. */
    var focus = 0f
        set(value) { field = value; invalidate() }
    var aperture = 4
        set(value) { field = value; invalidate() }
    var onFocus: (Float) -> Unit = {}
    var onAperture: (Int) -> Unit = {}

    private var dragging = 0 // 1 focus, 2 aperture
    private var lastX = 0f
    private var acc = 0f

    init { contentDescription = "镜头：对焦环与光圈环，左右拖动" }

    private val bands get() = run {
        val h = height.toFloat()
        val front = h * 0.12f
        val name = h * 0.1f
        val focusH = h * 0.32f
        val dof = h * 0.13f
        floatArrayOf(front, front + name, front + name + focusH, front + name + focusH + dof, h - h * 0.06f, h)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bands
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = when { e.y < b[2] && e.y >= b[0] -> 1; e.y >= b[3] -> 2; else -> 0 }
                lastX = e.x
                acc = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                lastX = e.x
                val step = 46 * d
                if (dragging == 1) {
                    val was = focus
                    focus = (focus - dx / step).coerceIn(0f, 9f)
                    if (was.toInt() != focus.toInt() || (was > 0.5f) != (focus > 0.5f)) onTick(focus < 0.5f)
                    onFocus(focus)
                } else if (dragging == 2) {
                    acc -= dx / (40 * d)
                    val n = acc.roundToInt()
                    if (n != 0) {
                        acc -= n
                        val next = (aperture + n).coerceIn(0, stops.lastIndex)
                        onTick(next == aperture)
                        if (next != aperture) { aperture = next; onAperture(next) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // the AF stop at the end of the ring catches
                if (dragging == 1 && focus < 0.5f && focus > 0f) { focus = 0f; onFocus(0f) }
                dragging = 0
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val b = bands
        val cx = w / 2
        // Each ring is a cylinder seen side on: a mark at angle t round it sits at cx + R sin t,
        // so marks crowd and fade toward the edges, and the light (from the upper left) shades it.
        fun ring(top: Float, bottom: Float, inset: Float, base: Int, shader: Shader? = null) {
            val r = RectF(w * inset, top, w * (1 - inset), bottom)
            p.style = Paint.Style.FILL
            p.shader = shader
            p.color = base
            canvas.drawRect(r, p)
            p.shader = null
            // the step down to the next ring: a dark line below, a thin lit edge above
            p.color = 0xcc000000.toInt()
            canvas.drawRect(r.left, bottom - 1.2f * d, r.right, bottom, p)
            p.color = 0x22ffffff
            canvas.drawRect(r.left, top, r.right, top + 0.8f * d, p)
        }
        fun shade(top: Float, bottom: Float, inset: Float) {
            val l = w * inset
            val rr = w * (1 - inset)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(l, 0f, rr, 0f,
                intArrayOf(0xd9000000.toInt(), 0x59000000, 0x00000000, 0x14ffffff, 0x00000000, 0x4d000000, 0xe6000000.toInt()),
                floatArrayOf(0f, 0.1f, 0.24f, 0.34f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(l, top, rr, bottom, p)
            p.shader = null
        }
        /** Marks [count] apart by [stepDeg] round the ring, turned by [turn] steps; [draw] gets x and how face-on it is. */
        fun around(count: Int, stepDeg: Float, turn: Float, inset: Float, draw: (i: Int, x: Float, facing: Float) -> Unit) {
            val radius = w * (0.5f - inset)
            for (i in 0 until count) {
                val t = Math.toRadians(((i - turn) * stepDeg).toDouble())
                if (abs(t) > Math.PI / 2 * 0.97) continue
                draw(i, cx + radius * sin(t).toFloat(), cos(t).toFloat())
            }
        }
        fun label(s: String, x: Float, y: Float, size: Float, color: Int, facing: Float) {
            canvas.save()
            canvas.scale(facing.coerceAtLeast(0.15f), 1f, x, y)
            text(canvas, s, x, y, size, color)
            canvas.restore()
            p.alpha = 255
        }
        // the barrel's shadow on the body
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = 0xff000000.toInt()
        lift(8f, 6f)
        canvas.drawRect(w * 0.02f, b[0], w * 0.98f, b[4], p)
        land()

        // front: filter thread, then the name ring
        ring(0f, b[0], 0.07f, 0xff151516.toInt())
        p.color = 0xff2c2c2e.toInt()
        around(60, 6f, 0f, 0.07f) { _, x, f -> p.alpha = (255 * f).toInt(); canvas.drawRect(x - 0.5f * d, 1 * d, x + 0.5f * d, b[0] - 2 * d, p) }
        p.alpha = 255
        shade(0f, b[0], 0.07f)
        ring(b[0], b[1], 0.03f, 0xff1a1a1b.toInt())
        label("PHOTO ALL  MC 1:1.4  50mm", cx, b[1] - (b[1] - b[0]) * 0.22f, 10f, 0xffd9d6cc.toInt(), 1f)
        shade(b[0], b[1], 0.03f)

        // focus ring: a rubber grip of ribs, then the distance scale
        val grip = b[1] + (b[2] - b[1]) * 0.32f
        ring(b[1], b[2], 0f, 0xff111112.toInt())
        p.color = 0xff2e2e30.toInt()
        around(72, 5f, focus * 5.2f, 0f) { _, x, f -> canvas.drawRect(x - 1.2f * d * f, b[1] + 1.5f * d, x + 1.2f * d * f, grip - 1.5f * d, p) }
        around(distances.size, 24f, focus, 0f) { i, x, f ->
            label(distances[i], x, grip + (b[2] - grip) * 0.52f, 16f, if (i == 0) AMBER else ENGRAVE, f)
            if (feet[i].isNotEmpty()) label(feet[i], x, b[2] - 2 * d, 11f, FEET, f)
        }
        shade(b[1], b[2], 0f)

        // depth-of-field scale: brushed metal, fixed, the index in the middle
        ring(b[2], b[3], 0.04f, ALU, aluGrain)
        p.color = RED
        canvas.drawRect(cx - 1 * d, b[2], cx + 1 * d, b[2] + (b[3] - b[2]) * 0.45f, p)
        for ((k, col) in listOf(0xff1d1d1b.toInt(), 0xff8e1f12.toInt(), 0xff173e8e.toInt()).withIndex()) {
            for (side in listOf(-1, 1)) {
                val t = Math.toRadians((side * (9 + k * 9)).toDouble())
                val x = cx + w * 0.46f * sin(t).toFloat()
                p.color = col
                canvas.drawRect(x - 0.65f * d, b[2], x + 0.65f * d, b[2] + (b[3] - b[2]) * 0.4f, p)
                label(listOf("4", "8", "16")[k], x, b[3] - 2 * d, 8.5f, col, cos(t).toFloat())
            }
        }
        shade(b[2], b[3], 0.04f)

        // aperture ring: the f-numbers, then fine ribs
        val numY = b[3] + (b[4] - b[3]) * 0.55f
        ring(b[3], b[4], 0.01f, 0xff141415.toInt())
        around(stops.size, 22f, aperture.toFloat(), 0.01f) { i, x, f ->
            label(stops[i], x, numY, 15f, if (i == aperture) 0xffff8a7e.toInt() else ENGRAVE, f)
        }
        p.color = 0xff2e2e30.toInt()
        around(90, 4f, aperture * 5.5f, 0.01f) { _, x, f -> canvas.drawRect(x - 0.8f * d * f, numY + 3 * d, x + 0.8f * d * f, b[4] - 1.5f * d, p) }
        shade(b[3], b[4], 0.01f)

        // the mount: brushed metal, the red dot
        ring(b[4], b[5], -0.01f, ALU, aluGrain)
        p.color = RED
        canvas.drawCircle(cx, (b[4] + b[5]) / 2, 1.8f * d, p)
        shade(b[4], b[5], -0.01f)
    }
}

// ----- rewind crank: three turns take the roll back into its cartridge ----------------------------

class RewindCrank(context: Context) : BodyPart(context) {
    var turns = 0
        set(value) { field = value; invalidate() }
    private var angle = 0f
    var onTurn: () -> Unit = {}

    init {
        contentDescription = "倒片摇把：拍完一卷后摇三圈"
        setOnClickListener {
            ValueAnimator.ofFloat(angle, angle - 360f).apply {
                duration = 520
                interpolator = DecelerateInterpolator()
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                start()
            }
            onTurn()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width.toFloat(), height - 14 * d) / 2f - 6 * d
        val cx = width / 2f
        val cy = r + 2 * d
        knurl(canvas, cx, cy, r, 72)
        p.shader = aluGrain
        canvas.drawCircle(cx, cy, r * 0.82f, p)
        p.shader = null
        sheen(canvas, cx, cy, r * 0.82f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * d
        p.color = 0xff2a2a2b.toInt()
        canvas.drawArc(RectF(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f), 200f, 70f, false, p)
        p.style = Paint.Style.FILL
        canvas.save()
        canvas.rotate(angle, cx, cy)
        p.color = 0xffb9b8b3.toInt()
        lift(3f, 2.5f)
        canvas.drawRoundRect(RectF(cx - r * 0.11f, cy - r * 0.72f, cx + r * 0.11f, cy + r * 0.05f), r * 0.1f, r * 0.1f, p)
        p.color = 0xff0c0c0d.toInt()
        canvas.drawCircle(cx, cy - r * 0.72f, r * 0.18f, p)
        land()
        p.color = 0xff161617.toInt()
        canvas.drawCircle(cx, cy - r * 0.72f, r * 0.18f, p)
        p.color = 0xff2c2c2e.toInt()
        canvas.drawCircle(cx, cy - r * 0.72f, r * 0.1f, p)
        p.color = 0xff8d8c87.toInt()
        canvas.drawCircle(cx, cy, r * 0.2f, p)
        p.color = 0xff2a2a2b.toInt()
        p.strokeWidth = 1.6f * d
        canvas.drawLine(cx - r * 0.14f, cy, cx + r * 0.14f, cy, p)
        canvas.restore()
        text(canvas, "倒片 $turns/3", cx, height - 2 * d, 11f, ENGRAVE)
    }
}

// ----- the cartridge in its chamber, its leader running to the take-up spool ----------------------

class Cartridge(context: Context) : BodyPart(context) {
    var film = "Kodak Portra 400"
        set(value) { field = value; invalidate() }
    var frame = 1
        set(value) { field = value; invalidate() }
    private var pull = 0f
    var onTap: () -> Unit = {}

    init {
        contentDescription = "胶片仓：点按换胶片"
        setOnClickListener { onTap() }
    }

    /** One frame wound on: the film moves eight perforations. */
    fun wind() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { pull = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun tint(): Pair<Int, Int> {
        val base = FilmLabel.of(film).base
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        hsv[2] *= 0.6f
        return base to android.graphics.Color.HSVToColor(hsv)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height - 14 * d
        val win = RectF(3 * d, 2 * d, w - 3 * d, h - 3 * d)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = PAINT_DEEP
        lift(5f, 3f)
        canvas.drawRoundRect(win, 6 * d, 6 * d, p)
        land()
        canvas.save()
        canvas.clipRect(win)
        // the cartridge lying in the window, the 3D model filling it, knob to the left;
        // a frame wound on rocks it a little on its axis
        val hc = minOf(win.width() * 0.96f, win.height() * 0.96f / 0.62f)
        canvas.translate(win.centerX(), win.centerY())
        canvas.rotate(-90f + 3f * kotlin.math.sin(pull * Math.PI.toFloat()))
        CartridgeSprites.draw(canvas, 0f, hc / 2, hc, film.lowercase(), yaw = 0.12f, shadow = 0f) { invalidate() }
        canvas.restore()
        recess(canvas, win, 6 * d)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = 0xff55544f.toInt()
        canvas.drawRoundRect(win, 6 * d, 6 * d, p)
        p.style = Paint.Style.FILL
        text(canvas, "第 $frame 张", w / 2, height - 2 * d, 11f, ENGRAVE)
    }
}

// ----- the light meter: a needle that centres when the exposure is right ------------------------

class LightMeter(context: Context) : BodyPart(context) {
    private var shown = 0f
    var stops = 0f
        set(value) {
            field = value.coerceIn(-3f, 3f)
            ValueAnimator.ofFloat(shown, field).apply {
                duration = 380
                interpolator = OvershootInterpolator(2.4f)
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    init { contentDescription = "测光表" }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = 0xff0a0a0b.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 4 * d, 4 * d, p)
        val cx = w / 2
        val span = (w - 24 * d) / 6
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.color = AMBER
        canvas.drawCircle(cx, h / 2, 6 * d, p)
        for (i in -3..3) if (i != 0) {
            p.alpha = 130
            canvas.drawLine(cx + i * span, h * 0.32f, cx + i * span, h * 0.68f, p)
        }
        p.alpha = 255
        text(canvas, "−", 7 * d, h * 0.68f, 12f, ENGRAVE)
        text(canvas, "+", w - 7 * d, h * 0.68f, 12f, ENGRAVE)
        recess(canvas, RectF(0f, 0f, w, h), 4 * d)
        p.style = Paint.Style.FILL
        p.color = NEEDLE
        val nx = cx + shown * span
        canvas.drawRect(nx - 1.5f * d, h * 0.14f, nx + 1.5f * d, h * 0.86f, p)
    }
}

// ----- shutter-speed dial, with the ASA window in its middle --------------------------------------

class SpeedDial(context: Context) : BodyPart(context) {
    val speeds = listOf("1000", "500", "250", "125", "60", "30", "15", "8", "4", "2", "1")
    var index = 3
        set(value) { field = value; invalidate() }
    var asa = "400"
        set(value) { field = value; invalidate() }
    var onSpeed: (Int) -> Unit = {}
    var onAsa: () -> Unit = {}
    private var shownDeg = -90f
    private var lastX = 0f
    private var acc = 0f
    private var moved = false

    init { contentDescription = "快门速度盘：左右拖动转动；点中间的 ASA 窗换感光度" }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); lastX = e.x; acc = 0f; moved = false }
            MotionEvent.ACTION_MOVE -> {
                acc -= (e.x - lastX) / (16 * d)
                lastX = e.x
                val n = acc.roundToInt()
                if (n != 0) {
                    moved = true
                    acc -= n
                    val next = (index + n).coerceIn(0, speeds.lastIndex)
                    onTick(next == index)
                    if (next != index) { index = next; onSpeed(next) }
                }
            }
            MotionEvent.ACTION_UP -> {
                val r = min(width, height) / 2f
                if (!moved && hypot(e.x - width / 2f, e.y - height / 2f) < r * 0.42f) onAsa()
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f - 9 * d
        val cx = width / 2f
        val cy = height / 2f - 1 * d
        // the index above the dial
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = RED
        path.reset()
        path.moveTo(cx - 4 * d, 0f); path.lineTo(cx + 4 * d, 0f); path.lineTo(cx, 7 * d); path.close()
        canvas.drawPath(path, p)
        knurl(canvas, cx, cy, r, 120)
        p.shader = paintGrain
        canvas.drawCircle(cx, cy, r * 0.84f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = 0x80000000.toInt()
        canvas.drawCircle(cx, cy + 0.8f * d, r * 0.84f - 1 * d, p)
        p.style = Paint.Style.FILL
        // The speeds are engraved round the whole top of the dial, 30 degrees apart, each
        // standing on its radius with its top to the rim (they read outward, as on a real dial),
        // with B after the slowest; the dial turns the chosen one under the index.
        val step = 30f
        val target = -index * step
        shownDeg += (target - shownDeg) * 0.32f
        if (abs(target - shownDeg) > 0.2f) postInvalidateOnAnimation() else shownDeg = target
        val marks = speeds + "B"
        val face = r * 0.84f
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        for ((i, sp) in marks.withIndex()) {
            canvas.save()
            canvas.rotate(i * step + shownDeg, cx, cy)
            // a short rule from the rim to the number, a dot between this speed and the next
            p.style = Paint.Style.FILL
            p.color = if (sp == "60") 0xfff0705f.toInt() else ENGRAVE
            p.alpha = 215
            canvas.drawRect(cx - 0.6f * d, cy - face + 1.5f * d, cx + 0.6f * d, cy - face + 4.5f * d, p)
            p.alpha = 120
            canvas.save()
            canvas.rotate(step / 2, cx, cy)
            canvas.drawCircle(cx, cy - face + 3 * d, 0.8f * d, p)
            canvas.restore()
            p.alpha = 255
            // the number itself: condensed, engraved white, filled with a touch of shadow under it
            p.textSize = (if (sp.length >= 4) 9f else 10.5f) * d
            p.textScaleX = if (sp.length >= 4) 0.82f else 0.9f
            val y = cy - face + 6 * d + p.textSize * 0.8f
            p.color = 0x99000000.toInt()
            canvas.drawText(sp, cx, y + 0.7f * d, p)
            p.color = when (sp) { "60" -> 0xfff0705f.toInt(); "B" -> 0xffe3b341.toInt(); else -> ENGRAVE }
            canvas.drawText(sp, cx, y, p)
            canvas.restore()
        }
        p.textScaleX = 1f
        // the ASA window
        p.color = 0xff0a0a0b.toInt()
        canvas.drawRoundRect(RectF(cx - r * 0.36f, cy - r * 0.24f, cx + r * 0.36f, cy + r * 0.24f), r * 0.24f, r * 0.24f, p)
        recess(canvas, RectF(cx - r * 0.36f, cy - r * 0.24f, cx + r * 0.36f, cy + r * 0.24f), r * 0.24f)
        text(canvas, "ASA", cx, cy - r * 0.02f, 8.5f, 0xffc9c5b8.toInt())
        text(canvas, asa, cx, cy + r * 0.2f, 12.5f, ENGRAVE)
    }
}

// ----- frame counter -----------------------------------------------------------------------------

class FrameCounter(context: Context) : BodyPart(context) {
    var frame = 1
        set(value) { field = value; invalidate() }
    var locked = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = min(width.toFloat(), height - 14 * d) / 2f - 4 * d
        val cx = width / 2f
        val cy = r + 2 * d
        wall(canvas, cx, cy, r, 3.5f)
        p.shader = aluGrain
        p.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null
        sheen(canvas, cx, cy, r)
        p.color = 0xff0a0a0b.toInt()
        canvas.drawCircle(cx, cy, r * 0.76f, p)
        recess(canvas, RectF(cx - r * 0.76f, cy - r * 0.76f, cx + r * 0.76f, cy + r * 0.76f), r * 0.76f)
        text(canvas, frame.toString(), cx, cy + 6 * d, 17f, if (locked) 0xffff8a7e.toInt() else ENGRAVE)
        p.color = FEET
        path.reset()
        path.moveTo(cx - 3 * d, cy - r * 0.76f); path.lineTo(cx + 3 * d, cy - r * 0.76f); path.lineTo(cx, cy - r * 0.6f); path.close()
        canvas.drawPath(path, p)
        text(canvas, if (locked) "未上弦" else "张数", cx, height - 2 * d, 11f, if (locked) 0xffff8a7e.toInt() else ENGRAVE)
    }
}

// ----- the wind lever: one stroke winds a frame on and cocks the shutter --------------------------

class WindLever(context: Context) : BodyPart(context) {
    /** The shutter is cocked: the lever only gives a little, against its stop. */
    var cocked = true
        set(value) { field = value; invalidate() }
    var onWound: () -> Unit = {}

    private val travel = 130f // a full stroke
    private var deg = 0f
    private var vel = 0f
    private var target = 0f
    private var held = false
    private var completing = false
    private var grabA = 0f
    private var grabDeg = 0f
    private var notch = 0
    private var last = 0L

    init { contentDescription = "过片扳手：按住向上扳，过了八成它会自己走完，卷到下一张并给快门上弦" }

    // the hub low on the left; at rest the arm lies to the right, under the thumb
    private val hubX get() = minOf(50 * d, width * 0.36f)
    private val hubY get() = height - 34 * d

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = Math.toDegrees(atan2((e.y - hubY).toDouble(), (e.x - hubX).toDouble())).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (completing) return true
                held = true
                grabA = a
                grabDeg = deg
                vel = 0f
            }
            MotionEvent.ACTION_MOVE -> if (held) {
                var delta = grabA - a // the stroke goes up and over: anticlockwise on screen
                if (delta > 180) delta -= 360
                if (delta < -180) delta += 360
                val limit = if (cocked) 9f else travel
                // the spring winds up as it goes: the last part of the stroke asks for more hand;
                // against the stop it only gives a little
                val x = ((grabDeg + delta) / limit).coerceIn(0f, 1.3f)
                deg = limit * (if (x <= 1f) 1f - (1f - x).pow(1.45f) else 1f + (x - 1f) * 0.08f)
                val n = (deg / 13f).toInt()
                if (n != notch) { notch = n; onTick(false) } // the ratchet, tooth by tooth
                if (!cocked && deg >= travel * 0.8f) {
                    // far enough: the stroke carries itself home
                    held = false
                    completing = true
                    target = travel
                    onTick(true)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (held) { held = false; target = 0f }
                performClick()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.034f, (now - last) / 1e9f)
        if (!held && (abs(deg - target) > 0.05f || abs(vel) > 0.5f)) {
            last = now
            val k = if (completing) 420f else 190f
            val c = if (completing) 34f else 17f
            vel += (-k * (deg - target) - c * vel) * dt
            deg += vel * dt
            if (completing && deg >= travel - 1.5f) {
                // the end of the stroke: the frame is wound, the shutter cocked, the lever comes back
                completing = false
                target = 0f
                vel = 0f
                onTick(true)
                onWound()
            }
            if (deg < 0f) { deg = 0f; vel = -vel * 0.3f } // back against its rest stop, a small bounce
            postInvalidateOnAnimation()
        } else last = 0L

        val px = hubX
        val py = hubY
        val r = 24 * d
        val reach = (width - hubX - 18 * d).coerceIn(40 * d, 74 * d)
        // the stroke, engraved on the plate: ticks, and a mark where it carries itself home
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * d
        p.color = 0x47e6e3d9
        var t = 0f
        while (t <= travel) {
            val a = Math.toRadians(-t.toDouble()).toFloat()
            canvas.drawLine(px + (reach + 6 * d) * cos(a), py + (reach + 6 * d) * sin(a), px + (reach + 10 * d) * cos(a), py + (reach + 10 * d) * sin(a), p)
            t += 13f
        }
        p.style = Paint.Style.FILL
        p.color = AMBER
        val mark = Math.toRadians(-(travel * 0.8).toDouble()).toFloat()
        canvas.drawCircle(px + (reach + 8 * d) * cos(mark), py + (reach + 8 * d) * sin(mark), 2 * d, p)

        canvas.save()
        canvas.rotate(-deg, px, py)
        // the blade: wide at the hub, narrowing, a slight crank in it, a rounded tip
        path.reset()
        path.moveTo(px + r * 0.2f, py - 9 * d)
        path.cubicTo(px + reach * 0.45f, py - 9 * d, px + reach * 0.75f, py - 6 * d, px + reach - 10 * d, py - 5 * d)
        path.quadTo(px + reach + 2 * d, py - 4 * d, px + reach + 2 * d, py + 2 * d)
        path.quadTo(px + reach + 2 * d, py + 7 * d, px + reach - 10 * d, py + 6 * d)
        path.cubicTo(px + reach * 0.75f, py + 5 * d, px + reach * 0.45f, py + 9 * d, px + r * 0.2f, py + 9 * d)
        path.close()
        // its thickness, straight below it, and its shadow on the plate
        p.color = 0xff2a2927.toInt()
        lift(6f, 6f)
        canvas.drawPath(path, p)
        land()
        p.shader = aluGrain
        canvas.drawPath(path, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.9f * d
        p.color = ALU_DEEP
        canvas.drawPath(path, p)
        p.color = 0x73ffffff // the chamfer along its upper edge catches the light
        p.strokeWidth = 0.9f * d
        canvas.drawLine(px + r, py - 7.4f * d, px + reach - 12 * d, py - 4 * d, p)
        p.style = Paint.Style.FILL
        // the thumb pad: black plastic, ribbed, standing proud of the blade
        val pad = RectF(px + reach - 26 * d, py - 10 * d, px + reach + 6 * d, py + 11 * d)
        p.color = 0xff050506.toInt()
        lift(4f, 3f)
        canvas.drawRoundRect(pad, 10 * d, 10 * d, p)
        land()
        p.color = 0xff1a1a1b.toInt()
        canvas.drawRoundRect(pad, 10 * d, 10 * d, p)
        p.strokeWidth = 1.6f * d
        for (i in 0 until 5) {
            val lx = pad.left + 6 * d + i * 5 * d
            p.color = 0xff38383b.toInt()
            canvas.drawLine(lx, pad.top + 4 * d, lx, pad.bottom - 4 * d, p)
            p.color = 0xff0a0a0b.toInt()
            canvas.drawLine(lx + 1.4f * d, pad.top + 4 * d, lx + 1.4f * d, pad.bottom - 4 * d, p)
        }
        canvas.restore()
        // the hub: knurled collar, a turned face, its screw
        knurl(canvas, px, py, r, 72)
        p.shader = aluGrain
        canvas.drawCircle(px, py, r * 0.8f, p)
        p.shader = null
        sheen(canvas, px, py, r * 0.8f)
        p.color = PAINT
        canvas.drawCircle(px, py, r * 0.36f, p)
        p.color = ALU_DEEP
        p.strokeWidth = 1.8f * d
        canvas.drawLine(px - r * 0.24f, py, px + r * 0.24f, py, p)
        text(canvas, if (cocked) "已上弦" else "向上扳过片", width - 2 * d, height - 3 * d, 12f, if (cocked) ENGRAVE else 0xffff8a7e.toInt(), Paint.Align.RIGHT)
    }
}

// ----- the plate a group of parts stands on -----------------------------------------------------------

/** A panel of the body in matte black paint: it has an edge and throws a shadow that follows the tilt. */
class BodyPlate(private val d: Float) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        r.inset(4 * d, 5 * d)
        val rad = 14 * d
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = 0xff000000.toInt()
        p.setShadowLayer(12 * d, -Skin.tiltX * 5 * d, (7 + Skin.tiltY * 3) * d, 0xd0000000.toInt())
        canvas.drawRoundRect(r, rad, rad, p)
        p.clearShadowLayer()
        // its edge, seen as the phone tilts
        p.color = 0xff070708.toInt()
        canvas.drawRoundRect(r, rad, rad, p)
        p.shader = paintGrain
        canvas.drawRoundRect(r, rad, rad, p)
        p.shader = null
        // paint worn to brass along the top edge, a darker line where the plate turns under
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1 * d
        p.color = 0x38b08a46
        canvas.drawLine(r.left + rad, r.top + 0.5f * d, r.right - rad, r.top + 0.5f * d, p)
        p.color = 0x99000000.toInt()
        canvas.drawLine(r.left + rad, r.bottom - 0.5f * d, r.right - rad, r.bottom - 0.5f * d, p)
        p.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
