package org.photoall.film.ui

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
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The camera screen's style, over the themes. 经典 leaves everything to the theme; the
 * others bring their colours, a backdrop, a shutter, keys and what a shot looks like:
 *
 * - 神圣: a manuscript page. Bone paper, ink, matte gold leaf, rubric red; heavenly spheres
 *   in pencil, a rose-window shutter of gold leaf with ink tracery.
 * - 相机拟物: a mechanical camera in matte black paint and bead-blasted aluminium. No shine.
 * - 二次元: stickers with a heavy ink outline.
 * - 宿命: survey and letters. Bone paper, ink, one ultramarine disc with a non-human eye,
 *   orbits that never meet, a flock crossing.
 *
 * The viewfinder is never covered: a style draws only a hairline at the picture's edge.
 * 2.5D: backdrop layers and the eye follow the phone's tilt ([tiltX], [tiltY]).
 */
object Skin {
    const val CLASSIC = 0
    const val SACRED = 1
    const val CAMERA = 2
    const val ANIME = 3
    const val FATE = 4
    val names = listOf("", "光", "经典", "漫", "风")

    var kind = CLASSIC
        private set
    val active get() = kind != CLASSIC

    /** The phone's tilt, -1..1 each way, smoothed; and the running time in seconds. */
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    @Volatile var time = 0f
    /** The system's animations are off: no ambient motion, no transitions. */
    @Volatile var calm = false

    /** The screen as it was before a change of theme, for [ThemeReveal]. */
    var lastFrame: Bitmap? = null
    /** The viewfinder frosted over, as the old screen left it. */
    var lastFinder: Bitmap? = null

    fun capture(v: View) {
        if (calm || v.width == 0 || v.height == 0) return
        val b = Bitmap.createBitmap(v.width / 2, v.height / 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(0.5f, 0.5f)
        v.draw(c)
        lastFrame = b
    }

    /** Draws as another style for a moment (the theme picker's live cards). Main thread only. */
    fun <T> preview(k: Int, block: () -> T): T {
        val old = kind
        kind = k
        try { return block() } finally { kind = old }
    }

    private const val BONE = 0xffdedad0.toInt()
    private const val BONE_2 = 0xffefebe1.toInt()
    private const val INK = 0xff161514.toInt()
    private const val PENCIL = 0xff5e5a52.toInt()
    private const val GOLD = 0xffa88435.toInt()
    private const val RUBRIC = 0xffb23a25.toInt()
    private const val PAPER = 0xffd9d7cf.toInt()
    private const val ULTRA = 0xff1f2bd0.toInt()
    private const val ULTRA_SHADOW = 0xff8e93c8.toInt()
    private const val SAKURA = 0xffff6fa3.toInt()
    private const val NAVY = 0xff1b1464.toInt()
    private const val YELLOW = 0xffffe14a.toInt()
    private const val CYAN = 0xff3fd8f4.toInt()

    private val palettes = mapOf(
        SACRED to Palette("神圣", BONE, BONE_2, 0xffe6e1d4.toInt(), 0xffb9b3a5.toInt(),
            INK, 0xff4a463f.toInt(), 0xff8e8778.toInt(), GOLD, INK, 0xd9161514.toInt(), light = true),
        CAMERA to Palette("相机拟物", 0xff151414.toInt(), 0xff1c1c1d.toInt(), 0xff262628.toInt(), 0xff3a3a3c.toInt(),
            0xffe6e3d9.toInt(), 0xffa9a59b.toInt(), 0xff5f5d58.toInt(), 0xffe3b341.toInt(), 0xff141414.toInt(), 0xd90b0b0c.toInt()),
        ANIME to Palette("二次元", 0xfffff7fb.toInt(), 0xffffffff.toInt(), YELLOW, NAVY,
            NAVY, 0xff4a438c.toInt(), 0xffa9a5cc.toInt(), SAKURA, NAVY, 0xd91b1464.toInt(), light = true),
        FATE to Palette("宿命", PAPER, 0xffefede6.toInt(), 0xffe4e2da.toInt(), 0xffb5b3ab.toInt(),
            0xff121212.toInt(), 0xff4a4944.toInt(), 0xff8a8983.toInt(), ULTRA, 0xffffffff.toInt(), 0xd9121212.toInt(), light = true),
    )

    /** Chooses the style; returns its palette, or null for the theme's own. */
    fun load(index: Int): Palette? {
        kind = index.coerceIn(CLASSIC, FATE)
        return palettes[kind]
    }

    /** The face of figures (the frame counter) in this style. */
    fun figures(): Typeface = when (kind) {
        SACRED, FATE -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        CAMERA -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        ANIME -> Typeface.create("sans-serif-black", Typeface.ITALIC)
        else -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /** A fine stipple of dark dots, densest at the top: grain for a band fading into paper. */
    private fun stipple(size: Int, ink: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        val c = (ink and 0x00ffffff)
        for (y in 0 until size) for (x in 0 until size) {
            val keep = 1f - y / size.toFloat()
            if (rnd.nextFloat() < keep * keep) b.setPixel(x, y, c or 0xff000000.toInt())
        }
        return b
    }

    private fun grain(size: Int, base: Int, light: Int, dark: Int): BitmapShader {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(3)
        for (y in 0 until size) for (x in 0 until size) {
            val r = rnd.nextFloat()
            b.setPixel(x, y, if (r < 0.08f) light else if (r > 0.9f) dark else base)
        }
        return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** A bird in flight seen from below: body, a pair of wings at [beat] -1 (down) .. 1 (up), a forked tail. */
    private fun flyingBird(canvas: Canvas, paint: Paint, path: Path, x: Float, y: Float, span: Float, beat: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        val lift = beat * span * 0.32f
        for (side in listOf(-1f, 1f)) {
            path.reset()
            path.moveTo(x, y)
            path.quadTo(x + side * span * 0.28f, y - lift - span * 0.1f, x + side * span * 0.5f, y - lift * 1.4f + span * 0.04f)
            path.quadTo(x + side * span * 0.26f, y - lift * 0.4f + span * 0.08f, x + side * span * 0.06f, y + span * 0.06f)
            path.close()
            canvas.drawPath(path, paint)
        }
        path.reset()
        path.addOval(x - span * 0.05f, y - span * 0.04f, x + span * 0.13f, y + span * 0.08f, Path.Direction.CW)
        path.moveTo(x - span * 0.04f, y + span * 0.02f)
        path.lineTo(x - span * 0.2f, y - span * 0.02f)
        path.lineTo(x - span * 0.13f, y + span * 0.03f)
        path.lineTo(x - span * 0.2f, y + span * 0.09f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun bird(path: Path, x: Float, y: Float, s: Float) {
        // a swift: two swept wings and a forked tail, drawn on a 30 x 16 box
        val u = s / 30f
        path.moveTo(x, y + 9 * u)
        path.cubicTo(x + 6 * u, y + 3 * u, x + 10 * u, y + 3 * u, x + 14 * u, y + 8 * u)
        path.cubicTo(x + 18 * u, y + 3 * u, x + 24 * u, y + u, x + 30 * u, y + 4 * u)
        path.cubicTo(x + 24 * u, y + 5 * u, x + 20 * u, y + 9 * u, x + 17 * u, y + 13 * u)
        path.lineTo(x + 14 * u, y + 10 * u)
        path.lineTo(x + 11 * u, y + 13 * u)
        path.cubicTo(x + 9 * u, y + 10 * u, x + 5 * u, y + 8 * u, x, y + 9 * u)
        path.close()
    }

    // ----- backdrop ---------------------------------------------------------------------

    class Backdrop(private val density: Float, private val landscape: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val path = Path()
        private val band: BitmapShader? = when (kind) {
            SACRED, FATE -> BitmapShader(stipple((40 * density).toInt(), INK), Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
            else -> null
        }
        private val texture: BitmapShader? = when (kind) {
            CAMERA -> grain(64, 0xff1c1c1d.toInt(), 0xff252526.toInt(), 0xff121213.toInt())
            ANIME -> {
                val s = (14 * density).toInt()
                val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                Canvas(b).drawCircle(s / 2f, s / 2f, 2 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffc2da.toInt() })
                BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
            else -> null
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val d = density
            val sx = -tiltX * 10 * d
            val sy = tiltY * 10 * d
            when (kind) {
                SACRED -> {
                    canvas.drawColor(BONE)
                    // the light enters above the upper left; the page is warmer near it
                    val lx = w * 0.1f + tiltX * 30 * d
                    val ly = -h * 0.08f
                    paint.shader = RadialGradient(lx, ly, maxOf(w, h) * 0.95f, intArrayOf(0x70fff4d6, 0x26f3deb0, 0x00f3deb0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, w, h, paint)
                    // shafts of it, slowly crossing the page: each a long wedge laid down three times, narrower each time, so its edges are soft
                    val reach = kotlin.math.hypot(w, h) * 1.15f
                    val shafts = FloatArray(4)
                    for (i in 0 until 4) {
                        val a = 0.4f + i * 0.21f + 0.07f * sin(time * 0.11f + i * 1.7f) + tiltX * 0.12f
                        shafts[i] = a
                        val breadth = 0.05f + 0.02f * sin(time * 0.17f + i * 2.3f)
                        val strength = 0.5f + 0.5f * sin(time * 0.09f + i * 2.9f)
                        for (layer in 0 until 3) {
                            val half = breadth * (1f - layer * 0.3f)
                            path.reset()
                            path.moveTo(lx, ly)
                            path.lineTo(lx + reach * cos(a - half), ly + reach * sin(a - half))
                            path.lineTo(lx + reach * cos(a + half), ly + reach * sin(a + half))
                            path.close()
                            val alpha = (10 + 16 * strength).toInt()
                            paint.shader = LinearGradient(lx, ly, lx + reach * cos(a), ly + reach * sin(a),
                                intArrayOf((alpha shl 24) or 0xfff3d2, (alpha shl 24) or 0xf0d79a, 0x00f0d79a), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                            canvas.drawPath(path, paint)
                        }
                    }
                    paint.shader = null
                    // dust in the air: near motes larger and quicker to drift with the tilt, far ones small; each lights up as a shaft passes over it
                    for (i in 0 until 34) {
                        val z = 0.3f + 0.7f * ((i * 0.618034f) % 1f)
                        val u = (time * (0.008f + 0.02f * z) + i * 0.137f) % 1f
                        val mx = w * (((i * 0.371f) % 1f + 0.05f * sin(time * 0.2f + i)) % 1f) - tiltX * 18 * z * d
                        val my = h * (1.05f - 1.1f * u) + tiltY * 12 * z * d
                        val am = kotlin.math.atan2(my - ly, mx - lx)
                        var lit = 0f
                        for (sa in shafts) { val q = (am - sa) / 0.07f; lit = maxOf(lit, kotlin.math.exp(-q * q)) }
                        paint.color = if (lit > 0.4f) 0xffe2c06a.toInt() else GOLD
                        paint.alpha = ((36 + 190 * lit) * sin(Math.PI.toFloat() * u)).toInt().coerceIn(0, 255)
                        canvas.drawCircle(mx, my, (0.8f + 1.8f * z) * (1f + 0.5f * lit) * d, paint)
                    }
                    paint.alpha = 255
                    // the spheres round the shutter: hairlines, turning against each other
                    val cx = (if (landscape) w * 0.88f else w / 2) + sx
                    val cy = (if (landscape) h / 2 else h * 0.9f) + sy
                    line.color = INK
                    line.strokeWidth = 0.7f * d
                    for ((i, r) in listOf(96f, 128f, 164f).withIndex()) {
                        line.alpha = listOf(110, 70, 46)[i]
                        line.pathEffect = android.graphics.DashPathEffect(
                            if (i == 1) floatArrayOf(400 * d, 0.01f) else floatArrayOf((1 + i * 5) * d, (5 + i) * d), time * 5 * d * (if (i == 1) -1 else 1))
                        canvas.drawCircle(cx, cy, r * d, line)
                    }
                    line.pathEffect = null
                    line.alpha = 255
                }
                FATE -> {
                    canvas.drawColor(PAPER)
                    // the air: currents crossing the page, each a slow wave with strokes running along it
                    line.strokeCap = Paint.Cap.ROUND
                    for (i in 0 until 9) {
                        val y0 = h * (0.06f + 0.88f * ((i * 0.618034f) % 1f)) + sy * (0.5f + i % 3)
                        val amp = (10 + (i % 4) * 9) * d
                        val k = (1.2f + (i % 3) * 0.5f) * 2 * Math.PI.toFloat() / w
                        val ph = time * (0.25f + (i % 5) * 0.06f) + i * 1.3f
                        path.reset()
                        for (st in 0..36) {
                            val x = -20 * d + (w + 40 * d) * st / 36f
                            val y = y0 + amp * sin(k * x + ph) + amp * 0.4f * sin(2.3f * k * x - ph * 0.7f)
                            if (st == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        line.color = if (i % 3 == 0) ULTRA else 0xff121212.toInt()
                        line.alpha = 38 + (i % 3) * 22
                        line.strokeWidth = (0.7f + (i % 3) * 0.5f) * d
                        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf((30 + i * 9) * d, (50 + i * 13) * d), -time * (26 + i * 7) * d)
                        canvas.drawPath(path, line)
                    }
                    line.pathEffect = null
                    // a flight riding one current: only its strokes, pairs of quills opening and closing, strung out along the wind
                    val ride = h * (if (landscape) 0.34f else 0.44f)
                    fun fx(u: Float) = -0.1f * w + 1.2f * w * u
                    fun fy(u: Float) = ride - h * 0.14f * u + 22 * d * sin(u * 7f + time * 0.5f) + sy
                    for (i in 0 until 17) {
                        val u = (time * 0.045f + i * 0.023f + 0.012f * sin(i * 12.9898f)) % 1f
                        val x = fx(u)
                        val y = fy(u) + (i % 3 - 1) * 15 * d + 6 * d * sin(time * 1.3f + i)
                        val dir = kotlin.math.atan2(fy(u + 0.01f) - fy(u), fx(u + 0.01f) - fx(u))
                        val fade = sin(Math.PI.toFloat() * u)
                        val size = (9 + (i % 5) * 2.4f) * d * (0.4f + 0.6f * fade)
                        val open = 0.55f + 0.38f * sin(time * (8f + i % 4) + i * 2.1f)
                        line.color = if (i % 4 == 0) 0xff121212.toInt() else ULTRA
                        line.alpha = (230 * fade).toInt().coerceIn(0, 255)
                        line.strokeWidth = (1.1f + (i % 3) * 0.4f) * d
                        for (side in listOf(-1f, 1f)) {
                            val a = dir + Math.PI.toFloat() + side * open
                            path.reset()
                            path.moveTo(x, y)
                            path.quadTo(x + size * 0.6f * cos(a - side * 0.35f), y + size * 0.6f * sin(a - side * 0.35f), x + size * cos(a), y + size * sin(a))
                            canvas.drawPath(path, line)
                        }
                    }
                    // rings round the shutter, a segment travelling on each: they never meet
                    val cx = (if (landscape) w * 0.88f else w / 2) + sx
                    val cy = (if (landscape) h / 2 else h * 0.9f) + sy
                    for ((i, r) in listOf(96f, 128f, 164f).withIndex()) {
                        val box = android.graphics.RectF(cx - r * d, cy - r * d, cx + r * d, cy + r * d)
                        line.color = 0xff121212.toInt()
                        line.alpha = listOf(90, 60, 40)[i]
                        line.strokeWidth = 0.7f * d
                        canvas.drawCircle(cx, cy, r * d, line)
                        line.color = ULTRA
                        line.alpha = 255
                        line.strokeWidth = 2f * d
                        val speed = listOf(14f, -9f, 5f)[i]
                        for (kk in 0 until 2) canvas.drawArc(box, time * speed + kk * 180f + i * 40f, 16f - i * 3f, false, line)
                    }
                    line.strokeCap = Paint.Cap.BUTT
                }
                CAMERA -> {
                    paint.shader = texture
                    canvas.drawRect(0f, 0f, w, h, paint)
                    paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x00000000, 0x59000000), null, Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, w, h, paint)
                    paint.shader = null
                }
                ANIME -> {
                    canvas.drawColor(0xfffff7fb.toInt())
                    paint.shader = texture
                    canvas.drawRect(0f, 0f, w, h, paint)
                    paint.shader = null
                    val lean = if (landscape) h * 0.22f else h * 0.09f
                    paint.color = SAKURA
                    path.reset(); path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(w, lean * 0.7f + sy); path.lineTo(0f, lean * 1.9f + sy); path.close()
                    canvas.drawPath(path, paint)
                    paint.color = CYAN
                    path.reset(); path.moveTo(0f, h - lean * 0.9f - sy); path.lineTo(w, h - lean * 2.2f - sy); path.lineTo(w, h); path.lineTo(0f, h); path.close()
                    canvas.drawPath(path, paint)
                    for (i in 0 until 5) {
                        val px = w * (0.12f + 0.78f * ((i * 0.43f + 0.2f) % 1f)) + sx * 1.6f
                        val py = h * (0.14f + 0.6f * ((i * 0.57f + 0.4f) % 1f)) + sy * 1.6f
                        val pulse = 0.5f + 0.5f * sin(time * 2.4f + i * 2.1f)
                        val r = (6 + 5 * pulse) * d
                        canvas.save()
                        canvas.rotate(time * 40f + i * 30f, px, py)
                        path.reset()
                        for (k in 0 until 8) {
                            val a = Math.PI.toFloat() * k / 4
                            val rr = if (k % 2 == 0) r else r * 0.38f
                            if (k == 0) path.moveTo(px + rr * sin(a), py - rr * cos(a)) else path.lineTo(px + rr * sin(a), py - rr * cos(a))
                        }
                        path.close()
                        paint.color = if (i % 2 == 0) YELLOW else 0xffffffff.toInt()
                        canvas.drawPath(path, paint)
                        line.color = NAVY
                        line.strokeWidth = 1.8f * d
                        line.strokeJoin = Paint.Join.ROUND
                        canvas.drawPath(path, line)
                        canvas.restore()
                    }
                    paint.color = YELLOW
                    path.reset(); path.moveTo(0f, h - lean * 1.2f - sy); path.lineTo(w, h - lean * 2.5f - sy); path.lineTo(w, h - lean * 2.2f - sy); path.lineTo(0f, h - lean * 0.9f - sy); path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    // ----- shutter ----------------------------------------------------------------------

    class Shutter(private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var pressed = false
        private val leaf = grain(48, GOLD, 0xffbf9a48.toInt(), 0xff8c6c28.toInt())
        private val blasted = grain(48, 0xffa5a49f.toInt(), 0xffb2b1ac.toInt(), 0xff95948f.toInt())
        /** The moment of the last shot (seconds of [time]), for the blink and the turn. */
        var shotAt = -10f

        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            if (now == pressed) return false
            pressed = now
            invalidateSelf()
            return true
        }

        override fun draw(canvas: Canvas) {
            val d = density
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val r = min(bounds.width(), bounds.height()) / 2f * (if (pressed) 0.95f else 1f)
            val since = time - shotAt
            paint.style = Paint.Style.FILL
            paint.shader = null
            when (kind) {
                SACRED -> {
                    paint.color = 0xff8e8778.toInt()
                    canvas.drawCircle(cx - 4 * d, cy + 4 * d, r * 0.92f, paint)
                    paint.shader = leaf
                    canvas.drawCircle(cx, cy, r * 0.92f, paint)
                    paint.shader = null
                    // ink tracery: twelve lobes in a ring, turning; a shot turns it a step further
                    canvas.save()
                    val turn = time * 6f + (if (since in 0f..0.9f) 30f * (1 - (1 - since / 0.9f) * (1 - since / 0.9f)) else if (since > 0.9f) 30f else 0f)
                    canvas.rotate(turn, cx, cy)
                    paint.style = Paint.Style.STROKE
                    paint.color = INK
                    paint.strokeWidth = 0.9f * d
                    canvas.drawCircle(cx, cy, r * 0.82f, paint)
                    canvas.drawCircle(cx, cy, r * 0.24f, paint)
                    for (i in 0 until 12) {
                        canvas.save()
                        canvas.rotate(i * 30f, cx, cy)
                        path.reset()
                        val bloom = if (since in 0f..0.9f) 1f + 0.12f * sin(since / 0.9f * Math.PI.toFloat()) else 1f
                        val top = cy - r * 0.72f * bloom
                        path.moveTo(cx, cy - r * 0.26f)
                        path.cubicTo(cx - r * 0.12f, cy - r * 0.42f, cx - r * 0.12f, top + r * 0.1f, cx, top)
                        path.cubicTo(cx + r * 0.12f, top + r * 0.1f, cx + r * 0.12f, cy - r * 0.42f, cx, cy - r * 0.26f)
                        canvas.drawPath(path, paint)
                        canvas.restore()
                    }
                    canvas.restore()
                    if (since in 0f..0.9f) {
                        val u = since / 0.9f
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (3 - 2 * u) * d
                        paint.color = 0xfffffbef.toInt()
                        paint.alpha = (255 * (1 - u)).toInt()
                        canvas.drawCircle(cx, cy, r * (0.15f + 0.75f * u), paint)
                        paint.alpha = 255
                    }
                    paint.style = Paint.Style.FILL
                    paint.color = RUBRIC
                    canvas.drawCircle(cx, cy, r * (0.11f + (if (since in 0f..0.4f) 0.05f * sin(since / 0.4f * Math.PI.toFloat()) else 0f)), paint)
                }
                CAMERA -> {
                    // the collar stands off the plate: its wall below it, a shadow under that
                    val wallH = 0f
                    val wx = 0f
                    canvas.save()
                    canvas.scale(0.84f, 0.84f, cx, cy)
                    canvas.translate(0f, -wallH * 0.6f)
                    paint.color = 0xff000000.toInt()
                    paint.setShadowLayer(9 * d, -tiltX * 4 * d, 5 * d, 0xc0000000.toInt())
                    canvas.drawCircle(cx + wx, cy + wallH, r, paint)
                    paint.clearShadowLayer()
                    for (k in 14 downTo 1) {
                        val f = k / 14f
                        val v = (0x6a - 0x44 * f).toInt()
                        paint.color = android.graphics.Color.rgb(v, v, v - 4)
                        canvas.drawCircle(cx + wx * f, cy + wallH * f, r, paint)
                    }
                    // knurled collar, a ring of blasted aluminium, the button with its cable-release socket
                    paint.color = 0xff2a2a2b.toInt()
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.color = 0xff6a6964.toInt()
                    canvas.drawCircle(cx, cy, r * 0.97f, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.1f * d
                    paint.color = 0xff4a4945.toInt()
                    for (i in 0 until 90) {
                        val a = i * 4f * Math.PI.toFloat() / 180f
                        canvas.drawLine(cx + r * 0.82f * cos(a), cy + r * 0.82f * sin(a), cx + r * 0.96f * cos(a), cy + r * 0.96f * sin(a), paint)
                    }
                    paint.style = Paint.Style.FILL
                    paint.shader = blasted
                    canvas.drawCircle(cx, cy, r * 0.8f, paint)
                    // the ring's turned face: two faint lobes of reflection, swinging with the light
                    val lobes = android.graphics.SweepGradient(cx, cy, intArrayOf(0x00ffffff, 0x30ffffff, 0x00ffffff, 0x24000000, 0x00ffffff, 0x30ffffff, 0x00ffffff, 0x24000000, 0x00ffffff), null)
                    val turn = android.graphics.Matrix()
                    turn.setRotate(-38f + tiltX * 55f + tiltY * 25f, cx, cy)
                    lobes.setLocalMatrix(turn)
                    paint.shader = lobes
                    canvas.drawCircle(cx, cy, r * 0.8f, paint)
                    paint.shader = null
                    paint.color = 0xff2a2a2b.toInt()
                    canvas.drawCircle(cx, cy, r * 0.6f, paint)
                    // the button stands above the ring, and goes down into it when pressed
                    val up = 0f
                    paint.color = 0xff55544f.toInt()
                    canvas.drawCircle(cx, cy, r * 0.55f, paint)
                    paint.shader = blasted
                    canvas.drawCircle(cx, cy - up, r * 0.55f, paint)
                    paint.shader = null
                    canvas.translate(0f, -up)
                    paint.color = 0xff161617.toInt()
                    canvas.drawCircle(cx, cy, r * 0.2f, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.9f * d
                    paint.color = 0xff3a3a3c.toInt()
                    canvas.drawCircle(cx, cy, r * 0.13f, paint)
                    canvas.drawCircle(cx, cy, r * 0.07f, paint)
                    paint.style = Paint.Style.FILL
                    canvas.restore()
                }
                ANIME -> {
                    val off = 3.5f * d
                    val squash = if (since in 0f..0.5f) 1f + 0.14f * sin(since / 0.5f * Math.PI.toFloat() * 3) * (1 - since / 0.5f) else 1f
                    canvas.save()
                    canvas.scale(squash, 2 - squash, cx, cy)
                    // the star burst behind, turning
                    path.reset()
                    for (k in 0 until 24) {
                        val a = Math.PI.toFloat() * k / 12 + time * 0.8f
                        val rr = if (k % 2 == 0) r else r * 0.8f
                        if (k == 0) path.moveTo(cx + rr * sin(a), cy - rr * cos(a)) else path.lineTo(cx + rr * sin(a), cy - rr * cos(a))
                    }
                    path.close()
                    paint.color = SAKURA
                    canvas.drawPath(path, paint)
                    paint.color = NAVY
                    canvas.drawCircle(cx + off, cy + off, (r - off) * 0.84f, paint)
                    paint.color = YELLOW
                    canvas.drawCircle(cx, cy, (r - off) * 0.84f, paint)
                    paint.color = SAKURA
                    canvas.drawCircle(cx, cy, (r - off) * 0.52f, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3 * d
                    paint.color = NAVY
                    canvas.drawCircle(cx, cy, (r - off) * 0.84f, paint)
                    canvas.drawCircle(cx, cy, (r - off) * 0.52f, paint)
                    paint.style = Paint.Style.FILL
                    canvas.restore()
                }
                FATE -> {
                    // the ultramarine disc, and on it an eye that is not quite human: it follows the
                    // phone, blinks now and then, and closes slowly for a shot
                    val disc = r * 0.92f
                    paint.color = ULTRA_SHADOW
                    canvas.drawCircle(cx - 3 * d, cy + 3 * d, disc, paint)
                    paint.color = ULTRA
                    canvas.drawCircle(cx, cy, disc, paint)
                    val idle = (time % 4.3f).let { if (it < 0.16f) it / 0.16f else 1f } // 0 shut .. 1 open, idle blink
                    val shot = if (since in 0f..0.6f) (if (since < 0.14f) 1 - since / 0.14f else if (since < 0.26f) 0f else (since - 0.26f) / 0.34f) else 1f
                    val open = min(abs(idle * 2 - 1).coerceAtLeast(0f).let { if (time % 4.3f < 0.16f) it else 1f }, shot).coerceIn(0.02f, 1f)
                    val ew = disc * 0.78f
                    val eh = disc * 0.44f * open
                    // the almond: two arcs, lids meeting at the corners
                    path.reset()
                    path.moveTo(cx - ew, cy)
                    path.quadTo(cx, cy - eh * 2f, cx + ew, cy)
                    path.quadTo(cx, cy + eh * 2f, cx - ew, cy)
                    path.close()
                    paint.color = 0xffedebe2.toInt()
                    canvas.drawPath(path, paint)
                    canvas.save()
                    canvas.clipPath(path)
                    val px = cx + tiltX * ew * 0.35f
                    val py = cy - tiltY * eh * 0.5f
                    val pupil = disc * (if (since in 0f..0.8f) 0.13f else 0.2f)
                    paint.color = 0xff0e1660.toInt()
                    canvas.drawCircle(px, py, disc * 0.33f, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.9f * d
                    paint.color = 0x80d9d7cf.toInt()
                    for (k in 0 until 16) {
                        val a = 2 * Math.PI.toFloat() * k / 16 + time * 0.2f
                        canvas.drawLine(px + pupil * 1.2f * cos(a), py + pupil * 1.2f * sin(a), px + disc * 0.3f * cos(a), py + disc * 0.3f * sin(a), paint)
                    }
                    paint.style = Paint.Style.FILL
                    paint.color = 0xff050508.toInt()
                    // a slit, not a round pupil
                    canvas.drawRoundRect(px - pupil * 0.42f, py - pupil * 1.25f, px + pupil * 0.42f, py + pupil * 1.25f, pupil * 0.4f, pupil * 0.4f, paint)
                    paint.color = 0xccffffff.toInt()
                    canvas.drawCircle(px - disc * 0.12f, py - disc * 0.12f, disc * 0.045f, paint)
                    canvas.restore()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.4f * d
                    paint.color = 0xff121212.toInt()
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // ----- keys -------------------------------------------------------------------------

    /** A small key (settings, flash, the sheet, A/M): [radiusDp] < 0 makes it round. */
    class Key(private val density: Float, private val radiusDp: Float = -1f) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()
        private var pressed = false

        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            if (now == pressed) return false
            pressed = now
            invalidateSelf()
            return true
        }

        override fun draw(canvas: Canvas) {
            val d = density
            val lip = (if (kind == ANIME) 3f else 2f) * d
            rect.set(bounds)
            rect.inset(lip, lip)
            val radius = if (radiusDp < 0) min(rect.width(), rect.height()) / 2 else radiusDp * d
            paint.style = Paint.Style.FILL
            when (kind) {
                SACRED, FATE -> {
                    paint.color = if (pressed) (if (kind == SACRED) 0xffe6e1d4.toInt() else 0xffe4e2da.toInt()) else (if (kind == SACRED) BONE_2 else 0xffefede6.toInt())
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f * d
                    paint.color = INK
                    canvas.drawRoundRect(rect, radius, radius, paint)
                }
                CAMERA -> {
                    paint.color = 0xff050505.toInt()
                    canvas.drawRoundRect(rect.left, rect.top + lip, rect.right, rect.bottom + lip, radius, radius, paint)
                    if (pressed) rect.offset(0f, lip * 0.7f)
                    paint.color = 0xff232324.toInt()
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f * d
                    paint.color = 0xff55544f.toInt()
                    canvas.drawRoundRect(rect, radius, radius, paint)
                }
                ANIME -> {
                    paint.color = NAVY
                    if (!pressed) canvas.drawRoundRect(rect.left + lip, rect.top + lip, rect.right + lip, rect.bottom + lip, radius, radius, paint)
                    else rect.offset(lip, lip)
                    paint.color = 0xffffffff.toInt()
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.6f * d
                    paint.color = NAVY
                    canvas.drawRoundRect(rect, radius, radius, paint)
                }
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // ----- the picture's edge: a hairline, nothing over the picture --------------------------

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun drawFinderFrame(canvas: Canvas, w: Float, h: Float, density: Float, around: Int) {
        edgePaint.strokeWidth = (if (kind == ANIME) 3f else 1.2f) * density
        edgePaint.color = when (kind) {
            CAMERA -> 0xff000000.toInt()
            ANIME -> NAVY
            else -> INK
        }
        val half = edgePaint.strokeWidth / 2
        canvas.drawRect(half, half, w - half, h - half, edgePaint)
    }

    // ----- what a shot looks like ---------------------------------------------------------

    /** Over the viewfinder, for an instant: warm light (神圣), ink (宿命), speed lines (二次元). */
    class ShotFx(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var t = 1f
        private val run = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }

        init {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        /** Also tells the shutter, which blinks (宿命) or turns its tracery (神圣). */
        var onFire: () -> Unit = {}

        fun fire() {
            onFire()
            if (kind != SACRED && kind != ANIME && kind != FATE) return
            run.cancel()
            run.start()
        }

        override fun onDraw(canvas: Canvas) {
            if (t >= 1f) return
            val w = width.toFloat()
            val h = height.toFloat()
            val glow = if (t < 0.25f) t / 0.25f else 1f - (t - 0.25f) / 0.75f
            when (kind) {
                SACRED -> {
                    paint.shader = RadialGradient(w / 2, h * 0.3f, maxOf(w, h) * 0.85f,
                        intArrayOf(0xfffffbef.toInt(), 0xb3e8ce8c.toInt(), 0x00e8ce8c), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                    paint.alpha = (217 * glow).toInt()
                    canvas.drawRect(0f, 0f, w, h, paint)
                    paint.shader = null
                }
                FATE -> {
                    paint.color = 0xff121212.toInt()
                    paint.alpha = (235 * glow).toInt()
                    canvas.drawRect(0f, 0f, w, h, paint)
                }
                ANIME -> {
                    paint.color = 0xffffffff.toInt()
                    paint.alpha = (255 * glow).toInt()
                    val cx = w / 2
                    val cy = h * 0.45f
                    val reach = maxOf(w, h)
                    val hole = min(w, h) * (0.26f + 0.1f * t)
                    path.reset()
                    for (i in 0 until 40) {
                        val a = 2 * Math.PI.toFloat() * i / 40 + (i % 3) * 0.04f
                        val half = 0.018f + (i % 4) * 0.006f
                        val inner = hole * (1f + (i % 5) * 0.12f)
                        path.moveTo(cx + inner * cos(a), cy + inner * sin(a))
                        path.lineTo(cx + reach * cos(a - half), cy + reach * sin(a - half))
                        path.lineTo(cx + reach * cos(a + half), cy + reach * sin(a + half))
                        path.close()
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}


/** The change of theme: the old screen leaves in the new theme's own manner. */
class ThemeReveal(context: Context, private val old: Bitmap) : View(context) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private var t = 0f

    private var pending: (() -> Unit)? = null
    private var started = false

    /**
     * Runs once the new screen is really on the glass. Started at once, most of it ran while
     * the rebuilt screen was still drawing its first frame (~0.7 s on a camera screen) and
     * the change looked like a cut; until then the old screen simply stays.
     */
    fun play(done: () -> Unit) {
        pending = done
        invalidate()
    }

    private fun start() {
        val done = pending ?: return
        pending = null
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 950
            startDelay = 40
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate(); if (t >= 1f) done() }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // the first frame drawn: a frame more for the screen under it, then the clock starts
        if (!started && pending != null) {
            started = true
            postOnAnimation { postOnAnimation { start() } }
        }
    }

    private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dst = android.graphics.RectF(0f, 0f, w, h)
        val far = kotlin.math.hypot(w, h)
        when (Skin.kind) {
            Skin.SACRED -> {
                // taken into the light: the old page whitens, then opens from where the light enters
                val r = ease((t - 0.22f) / 0.78f) * far * 1.1f
                path.reset()
                path.addCircle(w * 0.1f, 0f, r, Path.Direction.CW)
                c.save()
                c.clipOutPath(path)
                c.drawBitmap(old, null, dst, p)
                p.color = android.graphics.Color.argb((235 * ease(t / 0.3f)).toInt(), 0xff, 0xf6, 0xdc)
                c.drawRect(dst, p)
                c.restore()
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3 * d
                p.color = android.graphics.Color.argb((255 * (1 - t)).toInt(), 0xa8, 0x84, 0x35)
                c.drawCircle(w * 0.1f, 0f, r, p)
                p.style = Paint.Style.FILL
            }
            Skin.FATE -> {
                // carried off by the wind: bands of the old page, the upper ones first, each on its own gust
                val n = 11
                for (i in 0 until n) {
                    val e = ease(t * 1.6f - i * 0.055f)
                    c.save()
                    c.clipRect(0f, h * i / n, w, h * (i + 1) / n + 1)
                    c.translate(e * e * w * 1.25f, -e * 22 * d * (if (i % 2 == 0) 1 else -1))
                    c.skew(-0.25f * e, 0f)
                    p.alpha = (255 * (1 - e * 0.7f)).toInt()
                    c.drawBitmap(old, null, dst, p)
                    c.restore()
                }
                p.alpha = 255
            }
            Skin.CAMERA -> {
                // the two curtains of a focal-plane shutter, parting from the middle
                val e = ease(t * 1.15f)
                for (side in listOf(-1f, 1f)) {
                    c.save()
                    if (side < 0) c.clipRect(0f, 0f, w / 2 - e * w * 0.52f, h) else c.clipRect(w / 2 + e * w * 0.52f, 0f, w, h)
                    c.translate(side * e * w * 0.52f, 0f)
                    c.drawBitmap(old, null, dst, p)
                    c.restore()
                    val edge = w / 2 + side * e * w * 0.52f
                    p.shader = android.graphics.LinearGradient(edge, 0f, edge - side * 26 * d, 0f, 0xd9000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
                    c.drawRect(minOf(edge, edge - side * 26 * d), 0f, maxOf(edge, edge - side * 26 * d), h, p)
                    p.shader = null
                }
            }
            Skin.ANIME -> {
                // popped: a ring of ink bursts out from the middle, a little past and back
                val o = ease(t).let { it + 0.12f * sin(it * Math.PI.toFloat()) }
                val r = o * far * 0.62f
                path.reset()
                path.addCircle(w / 2, h / 2, r, Path.Direction.CW)
                c.save()
                c.clipOutPath(path)
                c.drawBitmap(old, null, dst, p)
                c.restore()
                p.style = Paint.Style.STROKE
                p.strokeWidth = 12 * d
                p.color = 0xff1b1464.toInt()
                c.drawCircle(w / 2, h / 2, r, p)
                p.strokeWidth = 5 * d
                p.color = 0xffffe14a.toInt()
                c.drawCircle(w / 2, h / 2, r + 10 * d, p)
                p.style = Paint.Style.FILL
            }
            else -> {
                p.alpha = (255 * (1 - ease(t))).toInt()
                c.drawBitmap(old, null, dst, p)
                p.alpha = 255
            }
        }
    }
}

/** Choosing a theme by what it looks like: a row of small living screens, named with a word each. */
class ThemePicker(context: Context, current: Int, onPick: (Int) -> Unit) : android.widget.HorizontalScrollView(context) {
    init {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((36 * resources.displayMetrics.density).toInt())
        val d = resources.displayMetrics.density
        val row = android.widget.LinearLayout(context)
        val titles = Style.palettes.map { it.name } + Skin.names.drop(1)
        titles.forEachIndexed { i, title ->
            row.addView(Card(context, i, title, i == current).apply { setOnClickListener { onPick(i) } },
                android.widget.LinearLayout.LayoutParams((94 * d).toInt(), -1).apply { marginEnd = (10 * d).toInt() })
        }
        addView(row)
        post { scrollTo(((current - 1) * 104 * d).toInt().coerceAtLeast(0), 0) }
    }

    private class Card(context: Context, private val index: Int, private val title: String, private val chosen: Boolean) : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val skin = index - Style.palettes.size + 1 // above 0: a camera style
        private val backdrop = if (skin > 0) Skin.preview(skin) { Skin.Backdrop(d * 0.5f, false) } else null
        private val shutter = if (skin > 0) Skin.preview(skin) { Skin.Shutter(d * 0.55f) } else null

        init {
            contentDescription = title
            isClickable = true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val box = android.graphics.RectF(2 * d, 2 * d, w - 2 * d, h - 24 * d)
            clip.reset()
            clip.addRoundRect(box, 12 * d, 12 * d, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            val finder = android.graphics.RectF(box.left + 9 * d, box.top + 9 * d, box.right - 9 * d, box.top + box.height() * 0.52f)
            val r = w * 0.19f
            if (skin > 0) {
                if (!Skin.active && !Skin.calm) Skin.time = (android.os.SystemClock.uptimeMillis() % 100_000_000L) / 1000f
                Skin.preview(skin) {
                    backdrop!!.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
                    backdrop.draw(c)
                    p.color = 0xff0b0b0d.toInt()
                    c.drawRect(finder, p)
                    shutter!!.setBounds((w / 2 - r).toInt(), (box.bottom - 2 * r - 9 * d).toInt(), (w / 2 + r).toInt(), (box.bottom - 9 * d).toInt())
                    shutter.draw(c)
                }
            } else {
                val pal = Style.palettes[index]
                c.drawColor(pal.bg)
                p.color = 0xff0b0b0d.toInt()
                c.drawRect(finder, p)
                p.color = pal.surface2
                c.drawCircle(w / 2, box.bottom - r - 9 * d, r, p)
                p.color = pal.accent
                c.drawCircle(w / 2, box.bottom - r - 9 * d, r * 0.62f, p)
            }
            c.restore()
            p.style = Paint.Style.STROKE
            p.strokeWidth = (if (chosen) 2.4f else 1f) * d
            p.color = if (chosen) Style.ACCENT else Style.HAIRLINE
            c.drawRoundRect(box, 12 * d, 12 * d, p)
            p.style = Paint.Style.FILL
            p.color = if (chosen) Style.TEXT else Style.TEXT_2
            p.textAlign = Paint.Align.CENTER
            p.typeface = Style.medium
            p.textSize = 13 * d
            c.drawText(title, w / 2, h - 7 * d, p)
            if (skin > 0 && !Skin.calm) postInvalidateOnAnimation()
        }
    }
}
