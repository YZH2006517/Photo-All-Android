package org.photoall.film.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The way into the tools (presets, the light table, the film shelf, what comes next), beside
 * the flash key: a capsule as round at its ends as the flash key, with no words on it — a small
 * window, set in a bezel, onto a scene after the theme in use, each a little story told again:
 *  0 暗盒  a cartridge winding its film a frame at a time, dust turning in a light leak
 *  1 红灯  a safelight flickering over a tray, a print rocking in the developer as it comes up
 *  2 相纸  a strip on a light box: a loupe goes over it, a grease pencil rings the frame chosen
 *  3 黄铜  a shutter-speed drum clicking from stop to stop under its index
 *  4 冷银  an iris of curved blades stepping through the f-stops on an icy light
 *  5 光    guilloché rosettes turning against each other round an embossed gold sun
 *  6 经典  a lens: knurled focus ring, aperture scale, coatings and a window's reflection
 *  7 漫    a comic burst: speed lines, halftone, a star with its glint and sparkles
 *  8 风    lines and ultramarine blocks: a diamond lattice drifting, a light running through it
 * Static layers (textures, the bezel's glass) are drawn once per size and kept; only what moves
 * is drawn each frame, about 30 a second while shown, one composed still when animations are off.
 */
class StudioEntry(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    /** Before a gradient: a paint keeps the last colour's alpha, which would fade the gradient too. */
    private val OPAQUE = 0xff000000.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()      // the capsule
    private val win = RectF()    // the window inside the bezel
    private val tmp = RectF()
    private val tmp2 = RectF()
    private val path = Path()
    private val path2 = Path()
    private val clip = Path()
    private var pressed = false
    private val born = System.nanoTime()
    private var under: Bitmap? = null   // the scene's still background, kept
    private var over: Bitmap? = null    // the bezel and its glass, kept
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    /** Which face: 0-4 the themes, 5.. the camera styles (Style.themeIndex). */
    private val face = if (Skin.active) 4 + Skin.kind else Style.themeIndex.coerceIn(0, 4)

    init {
        isClickable = true
        contentDescription = "工具：预设、观片台、胶卷架与更多"
        Style.pressable(this)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val now = isPressed
        if (now != pressed) { pressed = now; invalidate() }
    }

    private val moving get() = !Skin.calm && isAttachedToWindow && isShown && windowVisibility == VISIBLE

    /**
     * Glass (the default theme): the capsule as liquid glass, a thick clear pane over its
     * scene: what is inside bends inward towards the rim, magnified a touch, a hair of
     * colour split at the edge, light caught along the top rim and glowing up from the
     * bottom one (Android 13+; before it the frosted pane alone).
     */
    private fun liquid(w: Int, h: Int) {
        if (android.os.Build.VERSION.SDK_INT < 33 || w <= 0 || h <= 0) return
        runCatching {
            val rs = android.graphics.RuntimeShader(LIQUID)
            val lip = 2 * d
            rs.setFloatUniform("box", lip, lip, w - lip, h - lip)
            rs.setFloatUniform("radius", (h - 2 * lip) / 2f)
            rs.setFloatUniform("edge", (h - 2 * lip) * 0.42f)
            rs.setFloatUniform("bend", 7f * d)
            setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(rs, "content"))
        }.onFailure { android.util.Log.w("StudioEntry", "liquid glass", it) }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        under?.recycle(); over?.recycle(); under = null; over = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        under?.recycle(); over?.recycle(); under = null; over = null
    }

    // ------------------------------------------------------------------ the frame

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (face == 0 && Style.glass) { flat(c, w, h); return }
        val t = if (Skin.calm) 3.1f else ((System.nanoTime() - born) / 1e9).toFloat()
        val lip = 2 * d
        r.set(lip, lip, w - lip, h - lip)
        val bezel = bezelWidth()
        win.set(r.left + bezel, r.top + bezel, r.right - bezel, r.bottom - bezel)
        if (under == null) under = layer { cv -> background(cv) }
        if (over == null) over = layer { cv -> frame(cv) }
        under?.let { c.drawBitmap(it, 0f, 0f, p) }
        c.save()
        clip.reset(); clip.addRoundRect(win, win.height() / 2, win.height() / 2, Path.Direction.CW)
        c.clipPath(clip)
        p.shader = null; line.shader = null
        when (face) {
            0 -> cartridgeScene(c, t)
            1 -> darkroomScene(c, t)
            2 -> lightboxScene(c, t)
            3 -> drumScene(c, t)
            4 -> irisScene(c, t)
            5 -> gloryScene(c, t)
            6 -> lensScene(c, t)
            7 -> comicScene(c, t)
            9 -> finderScene(c, t)
            else -> latticeScene(c, t)
        }
        if (pressed) { p.shader = null; p.color = 0x26000000; c.drawRect(win, p) }
        c.restore()
        over?.let { c.drawBitmap(it, 0f, 0f, p) }
        if (moving) postInvalidateDelayed(33)
    }

    private val pane by lazy { Style.Glass(d, 0x1fffffff, 999f) }
    private val faded = Paint().apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.55f) })
        alpha = 225
    }

    /**
     * Glass (the default theme), flat: a frosted pill and in it the film in use, its 3D
     * cartridge lying on its side, its colours softened. Still; looked at again now and
     * then for a change of film.
     */
    private fun flat(c: Canvas, w: Float, h: Float) {
        val lip = 2 * d
        r.set(lip, lip, w - lip, h - lip)
        pane.state = drawableState
        pane.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        pane.callback = this
        pane.draw(c)
        val store = org.photoall.film.settings.SettingsStore(context)
        val name = if (store.flag(org.photoall.film.settings.Settings.FILM_ON))
            org.photoall.film.engine.FilmEngine.filmStocks.getOrNull(store["film"].toInt()) ?: "" else ""
        val stock = name.ifBlank { "kodak portra 400" }
        // lying down: the cartridge's height runs across the pill
        val len = minOf(r.width() * 0.62f, r.height() * 1.9f)
        c.saveLayer(r.left, r.top, r.right, r.bottom, faded)
        c.rotate(-90f, r.centerX(), r.centerY())
        CartridgeSprites.draw(c, r.centerX(), r.centerY() + len / 2, len, stock, shadow = 0f) { invalidate() }
        c.restore()
        if (moving) postInvalidateDelayed(1500)
    }

    private fun layer(draw: (Canvas) -> Unit): Bitmap? = runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
    }.getOrNull()

    private fun bezelWidth() = if (face == 0 && Style.glass) 1.4f * d else when (face) { 7 -> 3.2f * d; 2, 8 -> 2.2f * d; else -> 2.6f * d }

    /** The still part under the scene: the window's ground, its texture. */
    private fun background(c: Canvas) {
        val wr = win.height() / 2
        c.save()
        clip.reset(); clip.addRoundRect(win, wr, wr, Path.Direction.CW); c.clipPath(clip)
        when (face) {
            0 -> if (Style.glass) {
                // glass: the ground shows through a frosted pane
                p.color = 0x24ffffff; c.drawRect(win, p)
            } else { vertical(c, 0xff1d1915.toInt(), 0xff0c0a08.toInt()); grain(c, 0x14ffffff, 900, 7) }
            1 -> {
                vertical(c, 0xff1a0605.toInt(), 0xff0a0202.toInt())
                // darkroom tiles on the wall
                line.color = 0x22ff4a30; line.strokeWidth = 0.6f * d
                var y = win.top + win.height() * 0.1f
                while (y < win.top + win.height() * 0.62f) { c.drawLine(win.left, y, win.right, y, line); y += win.height() * 0.17f }
                var x = win.left
                while (x < win.right) { c.drawLine(x, win.top, x, win.top + win.height() * 0.62f, line); x += win.height() * 0.26f }
                grain(c, 0x10ff8a70, 500, 3)
            }
            2 -> { vertical(c, 0xfffdfbf5.toInt(), 0xffeee8da.toInt()); grain(c, 0x0c000000, 700, 11) }
            3 -> brushed(c, 0xffcaa45a.toInt(), 0xff8a6a2c.toInt(), 0x22fff2cc, 0x18241a08)
            4 -> brushed(c, 0xffe8edf1.toInt(), 0xff8f979f.toInt(), 0x30ffffff, 0x14000000)
            5 -> { vertical(c, 0xfff3eee2.toInt(), 0xffe4dcc8.toInt()); grain(c, 0x0e6a5020, 600, 5) }
            6 -> { p.shader = leather; c.drawRect(win, p); p.shader = null }
            7 -> { p.color = 0xffffffff.toInt(); c.drawRect(win, p) }
            9 -> vertical(c, 0xff1c2129.toInt(), 0xff08090b.toInt()) // dusk through the finder
            else -> {
                vertical(c, 0xfff1eee6.toInt(), 0xffe4e0d4.toInt())
                line.color = 0x1a121212; line.strokeWidth = 0.5f * d
                val g = win.height() / 5
                var x = win.centerX() - g * 12
                while (x < win.right) { c.drawLine(x, win.top, x, win.bottom, line); x += g }
                var y = win.centerY() - g * 3
                while (y < win.bottom) { c.drawLine(win.left, y, win.right, y, line); y += g }
                line.color = 0x55121212; line.strokeWidth = 0.6f * d
                var tx = win.left + win.height() * 0.4f; var k = 0
                while (tx < win.right - win.height() * 0.4f) { c.drawLine(tx, win.bottom, tx, win.bottom - (if (k % 5 == 0) 3.2f else 1.8f) * d, line); tx += g / 2; k++ }
                grain(c, 0x0a000000, 500, 13)
            }
        }
        c.restore()
    }

    /** The bezel round the window and the glass over it, drawn once. */
    private fun frame(c: Canvas) {
        val rr = r.height() / 2
        val wr = win.height() / 2
        when (face) {
            7 -> { // comic: a thick ink outline with its offset shadow
                val o = 2.4f * d
                p.color = 0xff1b1464.toInt()
                path.reset(); path.addRoundRect(RectF(r.left + o, r.top + o, r.right, r.bottom), rr, rr, Path.Direction.CW)
                path2.reset(); path2.addRoundRect(win, wr, wr, Path.Direction.CW)
                path.op(path2, Path.Op.DIFFERENCE); c.drawPath(path, p)
                line.color = 0xff1b1464.toInt(); line.strokeWidth = 2.6f * d
                c.drawRoundRect(RectF(r.left + 1.3f * d, r.top + 1.3f * d, r.right - o, r.bottom - o), rr - 1.3f * d, rr - 1.3f * d, line)
                return
            }
            2, 8 -> { // a print's white border and a fine ink line
                path.reset(); path.addRoundRect(r, rr, rr, Path.Direction.CW)
                path2.reset(); path2.addRoundRect(win, wr, wr, Path.Direction.CW)
                path.op(path2, Path.Op.DIFFERENCE)
                p.color = if (face == 2) 0xfffbf8f1.toInt() else 0xfff3f1ea.toInt(); c.drawPath(path, p)
                line.color = 0xff1d1b18.toInt(); line.strokeWidth = 1.1f * d
                c.drawRoundRect(r, rr, rr, line)
                line.color = 0x33000000; line.strokeWidth = 0.6f * d
                c.drawRoundRect(win, wr, wr, line)
                glass(c, 0.45f)
                return
            }
        }
        if (face == 0 && Style.glass) { // glass: a rim of light, no metal
            line.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0x66ffffff, 0x14ffffff, 0x0affffff, 0x33ffffff),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
            line.color = OPAQUE; line.strokeWidth = 1f * d
            c.drawRoundRect(RectF(r.left + 0.5f * d, r.top + 0.5f * d, r.right - 0.5f * d, r.bottom - 0.5f * d), rr, rr, line)
            line.shader = null
            glass(c, 0.55f)
            return
        }
        // a machined bezel: metal lit from above, a dark seat inside it
        val (lit, dark) = when (face) {
            0 -> 0xff8a6a3a.toInt() to 0xff2a2016.toInt()
            1 -> 0xff6a2a22.toInt() to 0xff1a0605.toInt()
            3 -> 0xfff0d79a.toInt() to 0xff5a4318.toInt()
            4 -> 0xfffbfdff.toInt() to 0xff5b636c.toInt()
            5 -> 0xffd9b866.toInt() to 0xff5e4416.toInt()
            else -> 0xffe8e7e2.toInt() to 0xff3c3b38.toInt()
        }
        path.reset(); path.addRoundRect(r, rr, rr, Path.Direction.CW)
        path2.reset(); path2.addRoundRect(win, wr, wr, Path.Direction.CW)
        path.op(path2, Path.Op.DIFFERENCE)
        p.color = OPAQUE; p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(lit, mix(lit, dark, 0.5f), dark), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        line.color = 0x66000000; line.strokeWidth = 0.8f * d
        c.drawRoundRect(r, rr, rr, line)
        line.color = 0x55ffffff; line.strokeWidth = 0.6f * d
        c.drawRoundRect(RectF(r.left + 0.7f * d, r.top + 0.7f * d, r.right - 0.7f * d, r.bottom - 0.7f * d), rr, rr, line)
        // the seat: the window sunk in, its upper edge in shadow
        c.save(); c.clipPath(path2)
        p.color = OPAQUE; p.shader = LinearGradient(0f, win.top, 0f, win.top + win.height() * 0.35f, intArrayOf(0x99000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(win, p); p.shader = null
        c.restore()
        line.color = 0xaa000000.toInt(); line.strokeWidth = 0.9f * d
        c.drawRoundRect(win, wr, wr, line)
        glass(c, 1f)
    }

    /** The glass over the window: a soft bow of light across its top, a small bright streak. */
    private fun glass(c: Canvas, k: Float) {
        val wr = win.height() / 2
        c.save()
        clip.reset(); clip.addRoundRect(win, wr, wr, Path.Direction.CW); c.clipPath(clip)
        tmp.set(win.left - win.width() * 0.1f, win.top - win.height() * 0.95f, win.right + win.width() * 0.1f, win.top + win.height() * 0.42f)
        p.color = OPAQUE; p.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, intArrayOf(0x00ffffff, (((0x30 * k).toInt()) shl 24) or 0xffffff), null, Shader.TileMode.CLAMP)
        c.drawOval(tmp, p); p.shader = null
        line.color = (((0x70 * k).toInt()) shl 24) or 0xffffff; line.strokeWidth = 1.2f * d
        tmp.set(win.left + wr * 0.35f, win.top + win.height() * 0.12f, win.left + wr * 1.6f, win.top + win.height() * 1.2f)
        c.drawArc(tmp, 200f, 40f, false, line)
        c.restore()
    }

    // ------------------------------------------------------------------ helpers

    private fun vertical(c: Canvas, top: Int, bottom: Int) {
        p.color = OPAQUE; p.shader = LinearGradient(0f, win.top, 0f, win.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(win, p); p.shader = null
    }

    private fun grain(c: Canvas, color: Int, count: Int, seed: Long) {
        val rnd = java.util.Random(seed)
        p.color = color
        val n = (count * win.width() * win.height() / (90 * d * 44 * d)).toInt()
        repeat(n) { c.drawCircle(win.left + rnd.nextFloat() * win.width(), win.top + rnd.nextFloat() * win.height(), (0.3f + rnd.nextFloat() * 0.5f) * d, p) }
    }

    private fun brushed(c: Canvas, top: Int, bottom: Int, light: Int, shade: Int) {
        vertical(c, top, bottom)
        val rnd = java.util.Random(4)
        line.strokeWidth = 0.5f * d
        var y = win.top
        while (y < win.bottom) { line.color = if (rnd.nextBoolean()) light else shade; c.drawLine(win.left, y, win.right, y, line); y += (0.8f + rnd.nextFloat()) * d }
    }

    private fun glow(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        if (radius <= 0f) return
        p.color = OPAQUE; p.shader = RadialGradient(x, y, radius, intArrayOf(color, color and 0xffffff), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, radius, p); p.shader = null
    }

    private fun alpha(color: Int, a: Float) = (((color ushr 24) * a.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0xffffff)

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xff) * (1 - k) + ((b shr s) and 0xff) * k).toInt() shl s
        return (0xff shl 24) or ch(16) or ch(8) or ch(0)
    }

    private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    /** A step motion: [period] seconds per step, the move taking [move] of it, eased; returns steps done. */
    private fun stepped(t: Float, period: Float, move: Float): Float {
        val n = floor(t / period)
        val k = (t - n * period) / period
        return n + ease(k / move)
    }

    /** Dust in a beam: motes drifting slowly, twinkling. */
    private fun motes(c: Canvas, t: Float, count: Int, color: Int, area: RectF, seed: Int) {
        val rnd = java.util.Random(seed.toLong())
        repeat(count) {
            val bx = rnd.nextFloat(); val by = rnd.nextFloat(); val sp = 0.04f + rnd.nextFloat() * 0.06f; val ph = rnd.nextFloat() * 6.28f
            val x = area.left + ((bx + t * sp * 0.5f) % 1f) * area.width()
            val y = area.top + ((by + 0.06f * sin(t * 0.7f + ph)) % 1f + 1f) % 1f * area.height()
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2.3f + ph))
            p.color = alpha(color, tw)
            c.drawCircle(x, y, (0.45f + 0.5f * rnd.nextFloat()) * d, p)
        }
    }

    // ------------------------------------------------------------------ 0 暗盒

    private fun cartridgeScene(c: Canvas, t: Float) {
        val H = win.height(); val cy = win.centerY()
        // a light leak from the upper right: a warm shaft with dust turning in it
        val breath = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 4.2f)
        path.reset()
        path.moveTo(win.right - H * 0.2f, win.top - H * 0.2f); path.lineTo(win.right + H * 0.4f, win.top)
        path.lineTo(win.left + win.width() * 0.45f, win.bottom + H * 0.2f); path.lineTo(win.left + win.width() * 0.22f, win.bottom + H * 0.2f); path.close()
        p.color = OPAQUE; p.shader = LinearGradient(win.right, win.top, win.left + win.width() * 0.3f, win.bottom,
            intArrayOf(alpha(0xffffb347.toInt(), 0.30f + 0.15f * breath), 0x00ffb347), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        glow(c, win.right - H * 0.25f, win.top + H * 0.1f, H * (0.9f + 0.2f * breath), alpha(0xffffa040.toInt(), 0.35f + 0.2f * breath))
        // the film: from the cartridge's lip to the right, a frame advanced every 2.4 s
        val wound = stepped(t, 2.4f, 0.42f)
        val pitch = H * 0.15f
        val run = (wound * pitch * 4) % (pitch * 400)
        val sx = win.left + H * 0.95f
        val sh = H * 0.56f
        val top = cy - sh / 2
        // the strip, with a gentle curl up toward its tongue
        path.reset()
        path.moveTo(sx, top); path.quadTo(win.right - H * 0.6f, top - H * 0.02f, win.right + H * 0.1f, top - H * 0.10f)
        path.lineTo(win.right + H * 0.1f, top + sh - H * 0.06f); path.quadTo(win.right - H * 0.6f, top + sh, sx, top + sh); path.close()
        p.color = OPAQUE; p.shader = LinearGradient(0f, top, 0f, top + sh, intArrayOf(0xff4a3820.toInt(), 0xff2c2012.toInt(), 0xff3e2f1a.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        c.save(); c.clipPath(path)
        // frames on the film, the latent pictures faintly amber
        val fw = H * 0.62f; val gap = H * 0.08f
        var fx = sx + ((-run) % (fw + gap)) - (fw + gap)
        while (fx < win.right + fw) {
            val lit = 0.22f + 0.1f * sin(fx * 0.05f)
            p.color = alpha(0xffe3a84a.toInt(), lit)
            c.drawRoundRect(fx, top + sh * 0.24f, fx + fw, top + sh * 0.76f, 1.4f * d, 1.4f * d, p)
            p.color = alpha(0xfffff0c0.toInt(), lit * 0.6f)
            c.drawCircle(fx + fw * 0.68f, top + sh * 0.42f, sh * 0.07f, p)
            fx += fw + gap
        }
        // sprocket holes, both edges, moving with it
        p.color = 0xff0c0a08.toInt()
        val hp = H * 0.15f
        var hx = sx + ((-run) % hp) - hp
        while (hx < win.right + hp) {
            c.drawRoundRect(hx, top + sh * 0.05f, hx + hp * 0.5f, top + sh * 0.16f, 0.6f * d, 0.6f * d, p)
            c.drawRoundRect(hx, top + sh * 0.84f, hx + hp * 0.5f, top + sh * 0.95f, 0.6f * d, 0.6f * d, p)
            hx += hp
        }
        // the edge print running past
        text.typeface = mono; text.textSize = sh * 0.085f; text.color = alpha(0xffe39a36.toInt(), 0.85f)
        text.textAlign = Paint.Align.LEFT
        val label = "PHOTO ALL 400  ▸ 12A    ▸ 13    ▸ 13A    ▸ 14    "
        val lw = text.measureText(label)
        var tx = sx - (run % lw)
        while (tx < win.right) { c.drawText(label, tx, top + sh * 0.215f, text); tx += lw }
        text.textAlign = Paint.Align.CENTER
        c.restore()
        // the cartridge, its spool turning as the film winds in
        cartridge(c, win.left + H * 0.62f, cy, H, wound * 1.3f)
        // the beam over everything, and its dust
        tmp.set(win.left + win.width() * 0.3f, win.top, win.right, win.bottom)
        motes(c, t, 14, 0xffffe0b0.toInt(), tmp, 3)
    }

    private fun cartridge(c: Canvas, cx: Float, cy: Float, H: Float, spin: Float) {
        val bw = H * 0.62f; val bh = H * 0.78f
        val bx = cx - bw / 2; val by = cy - bh / 2
        val cap = bh * 0.12f
        // the felt lip on the film side
        p.color = 0xff0a0908.toInt()
        c.drawRect(bx + bw - 1.2f * d, cy - H * 0.31f, bx + bw + 1.6f * d, cy + H * 0.31f, p)
        // the shell, round: lit from the left with a bright stripe
        p.color = OPAQUE; p.shader = LinearGradient(bx, 0f, bx + bw, 0f,
            intArrayOf(0xffa9781f.toInt(), 0xfff7d77a.toInt(), 0xffe9b941.toInt(), 0xffc8952e.toInt(), 0xff7a5414.toInt()),
            floatArrayOf(0f, 0.22f, 0.45f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(bx, by + cap * 0.8f, bx + bw, by + bh - cap * 0.8f, p); p.shader = null
        // the label's print: a black band, the speed in yellow, a red rule
        p.color = 0xff15120e.toInt()
        c.drawRect(bx, cy - bh * 0.13f, bx + bw, cy + bh * 0.13f, p)
        text.typeface = condensed; text.textSize = bh * 0.2f; text.color = 0xfff2c94c.toInt()
        c.drawText("400", cx, cy + bh * 0.07f, text)
        p.color = 0xffc8321f.toInt()
        c.drawRect(bx, cy + bh * 0.19f, bx + bw, cy + bh * 0.24f, p)
        p.color = 0x66ffffff; c.drawRect(bx + bw * 0.18f, by + cap, bx + bw * 0.24f, by + bh - cap, p)
        // ribbed caps
        for (top in booleanArrayOf(true, false)) {
            val y0 = if (top) by - cap * 0.3f else by + bh - cap * 0.9f
            p.color = OPAQUE; p.shader = LinearGradient(bx, 0f, bx + bw, 0f, intArrayOf(0xff3d3c39.toInt(), 0xffa9a7a1.toInt(), 0xff55534f.toInt(), 0xff1c1b1a.toInt()),
                floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(bx - cap * 0.25f, y0, bx + bw + cap * 0.25f, y0 + cap * 1.2f, cap * 0.35f, cap * 0.35f, p); p.shader = null
            line.color = 0x55000000; line.strokeWidth = 0.5f * d
            var x = bx; while (x < bx + bw) { c.drawLine(x, y0 + cap * 0.2f, x, y0 + cap, line); x += bw / 9 }
        }
        // the spool's nub, its key slot turning
        val nx = cx; val ny = by - cap * 0.55f; val nr = bw * 0.2f
        tmp.set(nx - nr, ny - nr * 0.55f, nx + nr, ny + nr * 0.55f)
        p.color = 0xff201f1d.toInt(); c.drawOval(tmp, p)
        p.color = 0xff4a4844.toInt(); c.drawRect(nx - nr, ny, nx + nr, ny + cap * 0.6f, p)
        val a = spin * PI.toFloat()
        line.color = 0xff8a8780.toInt(); line.strokeWidth = 1.1f * d
        c.drawLine(nx - nr * 0.7f * cos(a), ny - nr * 0.35f * sin(a), nx + nr * 0.7f * cos(a), ny + nr * 0.35f * sin(a), line)
    }

    // ------------------------------------------------------------------ 1 暗房

    private fun darkroomScene(c: Canvas, t: Float) {
        val H = win.height()
        val flick = 0.92f + 0.08f * sin(t * 37f) * sin(t * 13f)
        val breath = (0.55f + 0.45f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 3.8f))) * flick
        // the lamp, upper left, its cone falling on the tray
        val lx = win.left + win.width() * 0.3f; val ly = win.top + H * 0.3f
        path.reset()
        path.moveTo(lx - H * 0.22f, ly + H * 0.1f); path.lineTo(lx + H * 0.22f, ly + H * 0.1f)
        path.lineTo(win.right, win.bottom); path.lineTo(win.left + win.width() * 0.25f, win.bottom); path.close()
        p.color = OPAQUE; p.shader = LinearGradient(lx, ly, lx + H, win.bottom, intArrayOf(alpha(0xffff3a22.toInt(), 0.45f * breath), 0x00ff3a22), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        glow(c, lx, ly, H * 1.1f, alpha(0xffd01e10.toInt(), 0.55f * breath))
        // the tray, in perspective, and the developer in it
        val tl = win.left + win.width() * 0.42f; val tr = win.right - H * 0.25f
        val tt = win.top + H * 0.6f; val tb = win.bottom - H * 0.05f
        path.reset(); path.moveTo(tl + H * 0.12f, tt); path.lineTo(tr - H * 0.05f, tt); path.lineTo(tr + H * 0.08f, tb); path.lineTo(tl, tb); path.close()
        p.color = 0xff2a0c0a.toInt(); c.drawPath(path, p)
        line.color = alpha(0xffff6a4d.toInt(), 0.5f * breath); line.strokeWidth = 0.9f * d; c.drawPath(path, line)
        val inset = H * 0.05f
        path2.reset(); path2.moveTo(tl + H * 0.12f + inset, tt + inset * 0.6f); path2.lineTo(tr - H * 0.05f - inset, tt + inset * 0.6f)
        path2.lineTo(tr + H * 0.08f - inset * 1.4f, tb - inset * 0.6f); path2.lineTo(tl + inset * 1.4f, tb - inset * 0.6f); path2.close()
        p.color = OPAQUE; p.shader = LinearGradient(0f, tt, 0f, tb, intArrayOf(0xff5a120c.toInt(), 0xff2a0605.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path2, p); p.shader = null
        c.save(); c.clipPath(path2)
        // the print rocking in it, its picture coming up
        val rock = sin(t * 2f * PI.toFloat() / 2.2f) * 4f
        val pcx = (tl + tr) / 2 + H * 0.03f; val pcy = (tt + tb) / 2
        c.save(); c.rotate(rock, pcx, pcy)
        val pw = (tr - tl) * 0.62f; val ph = (tb - tt) * 0.6f
        tmp.set(pcx - pw / 2, pcy - ph / 2, pcx + pw / 2, pcy + ph / 2)
        p.color = 0xffd9a59a.toInt(); c.drawRect(tmp, p)
        val dev = ((t % 7f) / 5f).coerceIn(0f, 1f)
        p.color = alpha(0xff3a0806.toInt(), dev)
        c.drawCircle(tmp.left + pw * 0.32f, tmp.top + ph * 0.45f, ph * 0.2f, p)
        c.drawRect(tmp.left, tmp.bottom - ph * 0.3f, tmp.right, tmp.bottom - ph * 0.08f, p)
        c.restore()
        // ripples over the liquid
        line.strokeWidth = 0.6f * d
        for (k in 0 until 3) {
            val y = tt + (tb - tt) * (0.25f + 0.25f * k)
            line.color = alpha(0xffff8a6a.toInt(), 0.35f * breath)
            path.reset()
            for (s in 0..16) {
                val x = tl + (tr - tl + H * 0.1f) * s / 16f
                val yy = y + H * 0.012f * sin(s * 0.9f + t * 3f + k)
                if (s == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            }
            c.drawPath(path, line)
        }
        c.restore()
        // the lamp: a housing with screws, a hood, the ruby glass with its ribs and filament
        val hw = H * 0.5f; val hh = H * 0.36f
        tmp.set(lx - hw / 2, ly - hh / 2, lx + hw / 2, ly + hh / 2)
        p.color = OPAQUE; p.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, intArrayOf(0xff3a1a16.toInt(), 0xff140605.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(tmp, 2.2f * d, 2.2f * d, p); p.shader = null
        tmp2.set(tmp); tmp2.inset(H * 0.05f, H * 0.05f)
        p.color = OPAQUE; p.shader = RadialGradient(lx, ly, hw * 0.6f, intArrayOf(alpha(0xffffb090.toInt(), breath), 0xffe0281a.toInt(), 0xff6a0a06.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(tmp2, p); p.shader = null
        line.color = 0x66300806; line.strokeWidth = 0.6f * d
        var gx = tmp2.left + tmp2.width() / 5
        while (gx < tmp2.right - 1) { c.drawLine(gx, tmp2.top, gx, tmp2.bottom, line); gx += tmp2.width() / 5 }
        p.color = 0xff9a8a86.toInt()
        for ((sx2, sy2) in listOf(tmp.left + 1.4f * d to tmp.top + 1.4f * d, tmp.right - 1.4f * d to tmp.top + 1.4f * d, tmp.left + 1.4f * d to tmp.bottom - 1.4f * d, tmp.right - 1.4f * d to tmp.bottom - 1.4f * d))
            c.drawCircle(sx2, sy2, 0.7f * d, p)
        p.color = 0xff2a100c.toInt()
        c.drawRoundRect(tmp.left - H * 0.05f, tmp.top - H * 0.09f, tmp.right + H * 0.05f, tmp.top + H * 0.01f, 1.2f * d, 1.2f * d, p)
        line.color = 0xff2a100c.toInt(); line.strokeWidth = 1f * d
        c.drawLine(lx, tmp.top - H * 0.09f, lx, win.top, line)
        tmp.set(win.left, win.top, win.right, win.top + H * 0.75f)
        motes(c, t, 10, 0xffff9a80.toInt(), tmp, 9)
    }

    // ------------------------------------------------------------------ 2 相纸

    private fun lightboxScene(c: Canvas, t: Float) {
        val H = win.height()
        // the light box's glow
        glow(c, win.centerX(), win.centerY(), win.width() * 0.7f, 0x55fff6dc)
        // a strip of three frames across it
        val sh = H * 0.62f; val top = win.centerY() - sh / 2
        val sl = win.left + H * 0.25f; val sr = win.right - H * 0.25f
        p.color = 0xff2b2118.toInt(); c.drawRect(sl, top, sr, top + sh, p)
        p.color = 0xfff6f1e4.toInt()
        val hp = H * 0.13f
        var hx = sl + hp * 0.3f
        while (hx < sr - hp * 0.3f) {
            c.drawRoundRect(hx, top + sh * 0.05f, hx + hp * 0.45f, top + sh * 0.15f, 0.5f * d, 0.5f * d, p)
            c.drawRoundRect(hx, top + sh * 0.85f, hx + hp * 0.45f, top + sh * 0.95f, 0.5f * d, 0.5f * d, p)
            hx += hp
        }
        val fw = (sr - sl - H * 0.16f) / 3
        val frames = Array(3) { RectF() }
        for (i in 0 until 3) {
            frames[i].set(sl + H * 0.04f + i * (fw + H * 0.04f), top + sh * 0.2f, sl + H * 0.04f + i * (fw + H * 0.04f) + fw, top + sh * 0.8f)
            miniPicture(c, frames[i], i)
        }
        // the story: the loupe travels over the frames; the pencil rings the middle one
        val period = 8f
        val k = (t % period) / period
        val loupeX = when {
            k < 0.55f -> sl + (sr - sl) * (0.15f + 0.7f * ease(k / 0.55f))
            k < 0.7f -> sl + (sr - sl) * (0.85f - 0.35f * ease((k - 0.55f) / 0.15f))
            else -> sl + (sr - sl) * 0.5f
        }
        val loupeOn = if (k < 0.72f) 1f else (1f - (k - 0.72f) / 0.06f).coerceIn(0f, 1f)
        // the grease pencil's ring round the chosen frame
        val ring = ((k - 0.74f) / 0.14f).coerceIn(0f, 1f)
        if (ring > 0f) {
            val f = frames[1]
            path.reset(); tmp.set(f.left - H * 0.06f, f.top - H * 0.08f, f.right + H * 0.06f, f.bottom + H * 0.08f)
            path.addOval(tmp, Path.Direction.CW)
            val pm = PathMeasure(path, false)
            path2.reset(); pm.getSegment(0f, pm.length * ring * 1.08f, path2, true)
            val fade = if (k > 0.96f) (1f - (k - 0.96f) / 0.04f) else 1f
            line.color = alpha(0xffd8271d.toInt(), 0.9f * fade); line.strokeWidth = 1.7f * d
            c.drawPath(path2, line)
            if (ring < 1f) {
                val pos = FloatArray(2); pm.getPosTan(pm.length * ring, pos, null)
                pencil(c, pos[0], pos[1])
            }
        }
        if (loupeOn > 0f) loupe(c, loupeX, win.centerY(), H * 0.42f, frames, loupeOn)
    }

    private fun miniPicture(c: Canvas, f: RectF, kind: Int) {
        c.save(); c.clipRect(f)
        val sky = intArrayOf(0xff7a9cbc.toInt(), 0xffd99a70.toInt(), 0xff8aa7a0.toInt())[kind]
        val ground = intArrayOf(0xff3e4b38.toInt(), 0xff5a3a2a.toInt(), 0xff2e3a40.toInt())[kind]
        p.color = OPAQUE; p.shader = LinearGradient(0f, f.top, 0f, f.bottom, sky, mix(sky, 0xffffffff.toInt(), 0.4f), Shader.TileMode.CLAMP)
        c.drawRect(f, p); p.shader = null
        p.color = 0xfff8e2a8.toInt(); c.drawCircle(f.left + f.width() * (0.3f + 0.2f * kind), f.top + f.height() * 0.35f, f.height() * 0.12f, p)
        path.reset(); path.moveTo(f.left, f.bottom); path.lineTo(f.left, f.top + f.height() * (0.65f - 0.05f * kind))
        path.quadTo(f.centerX(), f.top + f.height() * (0.45f + 0.08f * kind), f.right, f.top + f.height() * 0.7f); path.lineTo(f.right, f.bottom); path.close()
        p.color = ground; c.drawPath(path, p)
        c.restore()
    }

    private fun loupe(c: Canvas, x: Float, y: Float, radius: Float, frames: Array<RectF>, a: Float) {
        // its shadow, the magnified view inside, the chrome rim and a glint
        p.color = alpha(0x33000000, a); c.drawCircle(x + radius * 0.12f, y + radius * 0.16f, radius * 1.02f, p)
        c.save()
        clip.reset(); clip.addCircle(x, y, radius, Path.Direction.CW); c.clipPath(clip)
        p.color = 0xff2b2118.toInt(); c.drawCircle(x, y, radius, p)
        c.save(); c.scale(1.9f, 1.9f, x, y)
        for ((i, f) in frames.withIndex()) miniPicture(c, f, i)
        c.restore()
        glow(c, x - radius * 0.3f, y - radius * 0.35f, radius * 0.8f, alpha(0x55ffffff, a))
        c.restore()
        line.color = OPAQUE; line.strokeWidth = radius * 0.16f
        line.shader = LinearGradient(x - radius, y - radius, x + radius, y + radius, intArrayOf(0xfff4f4f2.toInt(), 0xff8a8a86.toInt(), 0xffdadad6.toInt(), 0xff5a5a56.toInt()),
            floatArrayOf(0f, 0.4f, 0.7f, 1f), Shader.TileMode.CLAMP)
        line.alpha = (255 * a).toInt()
        c.drawCircle(x, y, radius, line); line.shader = null; line.alpha = 255
        line.color = alpha(0xccffffff.toInt(), a); line.strokeWidth = 1f * d
        tmp.set(x - radius * 0.75f, y - radius * 0.75f, x + radius * 0.75f, y + radius * 0.75f)
        c.drawArc(tmp, 200f, 50f, false, line)
    }

    private fun pencil(c: Canvas, x: Float, y: Float) {
        c.save(); c.rotate(-35f, x, y)
        p.color = 0xffd8271d.toInt(); c.drawRect(x - 1.1f * d, y - 9 * d, x + 1.1f * d, y - 2.5f * d, p)
        p.color = 0xfff1d6b0.toInt()
        path.reset(); path.moveTo(x - 1.1f * d, y - 2.5f * d); path.lineTo(x + 1.1f * d, y - 2.5f * d); path.lineTo(x, y); path.close(); c.drawPath(path, p)
        p.color = 0xffd8271d.toInt(); c.drawCircle(x, y - 0.6f * d, 0.5f * d, p)
        c.restore()
    }

    // ------------------------------------------------------------------ 3 黄铜

    private fun drumScene(c: Canvas, t: Float) {
        val H = win.height()
        val cx = win.centerX(); val cy = win.centerY()
        val speeds = arrayOf("B", "1", "2", "4", "8", "15", "30", "60", "125", "250", "500", "1000")
        // the drum: a cylinder seen side on, numbers wrapping round it, a click a stop
        val stop = stepped(t, 1.6f, 0.28f)
        val dw = win.width() * 0.62f; val dh = H * 0.62f
        tmp.set(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2)
        p.color = OPAQUE; p.shader = LinearGradient(tmp.left, 0f, tmp.right, 0f,
            intArrayOf(0xff3a2c10.toInt(), 0xffb8954a.toInt(), 0xfff6e2a8.toInt(), 0xffb8954a.toInt(), 0xff3a2c10.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(tmp, 2 * d, 2 * d, p); p.shader = null
        val R = dw / 2
        text.typeface = condensed
        val n = speeds.size
        for (j in -2..3) {
            val i = floor(stop).toInt() + j
            val ang = (i - stop) * 0.78f
            if (abs(ang) > 1.45f) continue
            val x = cx + R * sin(ang)
            val squeeze = cos(ang)
            text.textSize = dh * 0.42f
            text.color = alpha(0xff1e1608.toInt(), 0.15f + 0.85f * squeeze * squeeze)
            c.save(); c.scale(squeeze.coerceAtLeast(0.05f), 1f, x, cy)
            c.drawText(speeds[((i % n) + n) % n], x, cy + dh * 0.18f, text)
            c.restore()
            line.color = alpha(0xff1e1608.toInt(), 0.5f * squeeze); line.strokeWidth = 0.6f * d
            val tx = cx + R * sin(ang + 0.39f)
            c.drawLine(tx, tmp.top + dh * 0.12f, tx, tmp.top + dh * 0.24f, line)
        }
        // knurled rims either side of the drum, turning with it
        for (side in floatArrayOf(-1f, 1f)) {
            val kx0 = if (side < 0) win.left + H * 0.35f else tmp.right + H * 0.06f
            val kx1 = if (side < 0) tmp.left - H * 0.06f else win.right - H * 0.35f
            tmp2.set(kx0, cy - dh * 0.55f, kx1, cy + dh * 0.55f)
            p.color = OPAQUE; p.shader = LinearGradient(0f, tmp2.top, 0f, tmp2.bottom, intArrayOf(0xff5a4318.toInt(), 0xffe8cf8e.toInt(), 0xff5a4318.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(tmp2, 1.5f * d, 1.5f * d, p); p.shader = null
            c.save(); c.clipRect(tmp2)
            line.strokeWidth = 0.7f * d
            val pitch = H * 0.07f
            var y = tmp2.top - pitch + (stop * pitch * 2.5f) % pitch
            while (y < tmp2.bottom + pitch) {
                line.color = 0x88241a08.toInt(); c.drawLine(tmp2.left, y, tmp2.right, y + pitch * 0.6f, line)
                line.color = 0x55fff2cc; c.drawLine(tmp2.left, y + 0.8f * d, tmp2.right, y + pitch * 0.6f + 0.8f * d, line)
                y += pitch
            }
            c.restore()
        }
        // the index: a red triangle above the drum
        path.reset(); path.moveTo(cx - H * 0.08f, win.top + H * 0.04f); path.lineTo(cx + H * 0.08f, win.top + H * 0.04f); path.lineTo(cx, win.top + H * 0.17f); path.close()
        p.color = 0xffb8321f.toInt(); c.drawPath(path, p)
        // a glint crossing the brass after each click
        val since = (t % 1.6f) / 1.6f
        if (since < 0.5f) {
            val gx = tmp.left + tmp.width() * ease(since / 0.5f)
            p.color = OPAQUE; p.shader = LinearGradient(gx - H * 0.2f, 0f, gx + H * 0.2f, 0f, intArrayOf(0x00ffffff, 0x66fffaf0, 0x00ffffff), null, Shader.TileMode.CLAMP)
            c.drawRect(win, p); p.shader = null
        }
        // screws at the ends of the plate
        for (sx in floatArrayOf(win.left + H * 0.2f, win.right - H * 0.2f)) {
            p.color = 0xff5a4318.toInt(); c.drawCircle(sx, cy, H * 0.085f, p)
            p.color = OPAQUE; p.shader = RadialGradient(sx - H * 0.02f, cy - H * 0.02f, H * 0.08f, 0xfff4e0a8.toInt(), 0xffa07c34.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(sx, cy, H * 0.068f, p); p.shader = null
            line.color = 0xff4a3818.toInt(); line.strokeWidth = 0.8f * d
            c.drawLine(sx - H * 0.05f, cy + H * 0.02f, sx + H * 0.05f, cy - H * 0.02f, line)
        }
    }

    // ------------------------------------------------------------------ 4 冷银

    private fun irisScene(c: Canvas, t: Float) {
        val H = win.height()
        // the iris left of centre, its f-number and scale in the room to the right
        val cx = win.centerX() - H * 0.3f; val cy = win.centerY()
        val stops = floatArrayOf(1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)
        // f/1.4 to f/16 and back, a stop every 0.9 s
        val s = stepped(t, 0.9f, 0.4f)
        val n = stops.size - 1
        val pos = (s % (2 * n)).let { if (it <= n) it else 2 * n - it }
        val open = 1f - pos / n
        // the scale to the right: the current f-number engraved
        val i0 = Math.round(pos).coerceIn(0, n)
        text.typeface = condensed; text.textSize = H * 0.2f
        text.color = 0xff2b3036.toInt()
        val label = "f/" + stops[i0].let { if (it == floor(it)) it.toInt().toString() else it.toString() }
        val lx = win.right - H * 0.62f
        c.drawText(label, lx, cy + H * 0.02f, text)
        line.color = 0x882b3036.toInt(); line.strokeWidth = 0.6f * d
        for (k in 0..n) {
            val x = lx - H * 0.3f + k * H * 0.6f / n
            c.drawLine(x, cy + H * 0.14f, x, cy + H * (if (k == i0) 0.28f else 0.2f), line)
        }
        // the mount, black, with a white rim of dots
        val R = H * 0.44f
        p.color = OPAQUE; p.shader = RadialGradient(cx, cy - R * 0.2f, R * 1.3f, 0xff3a4048.toInt(), 0xff111418.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R, p); p.shader = null
        p.color = 0x99e8eef4.toInt()
        for (k in 0 until 20) {
            val a = (2 * PI * k / 20).toFloat() + t * 0.1f
            c.drawCircle(cx + R * 0.9f * cos(a), cy + R * 0.9f * sin(a), 0.45f * d, p)
        }
        // the light beyond: icy, with soft bokeh drifting
        val inner = R * 0.78f
        c.save(); clip.reset(); clip.addCircle(cx, cy, inner, Path.Direction.CW); c.clipPath(clip)
        p.color = OPAQUE; p.shader = RadialGradient(cx, cy, inner, intArrayOf(0xffe8f7ff.toInt(), 0xff8fd0ff.toInt(), 0xff2c5a80.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, inner, p); p.shader = null
        val rnd = java.util.Random(12)
        repeat(6) {
            val bx = cx + (rnd.nextFloat() - 0.5f) * inner * 1.6f + sin(t * 0.5f + it) * inner * 0.15f
            val by = cy + (rnd.nextFloat() - 0.5f) * inner * 1.6f + cos(t * 0.4f + it) * inner * 0.12f
            glow(c, bx, by, inner * (0.18f + 0.12f * rnd.nextFloat()), 0x66ffffff)
        }
        // the blades: seven curved leaves, each shaded, overlapping round the opening
        val hole = inner * (0.12f + 0.78f * open)
        val turn = (1f - open) * 0.9f
        for (i in 0 until 7) {
            val a = (2 * PI * i / 7).toFloat() + turn
            val ex = cos(a); val ey = sin(a); val nx = -ey; val ny = ex
            path.reset()
            val ax = cx + ex * hole + nx * inner * 1.3f; val ay = cy + ey * hole + ny * inner * 1.3f
            val bx = cx + ex * hole - nx * inner * 0.25f; val by = cy + ey * hole - ny * inner * 0.25f
            path.moveTo(ax, ay)
            path.quadTo(cx + ex * (hole - inner * 0.08f) + nx * inner * 0.5f, cy + ey * (hole - inner * 0.08f) + ny * inner * 0.5f, bx, by)
            path.lineTo(cx + ex * inner * 1.6f - nx * inner * 0.25f, cy + ey * inner * 1.6f - ny * inner * 0.25f)
            path.lineTo(cx + ex * inner * 1.6f + nx * inner * 1.3f, cy + ey * inner * 1.6f + ny * inner * 1.3f)
            path.close()
            p.color = OPAQUE; p.shader = LinearGradient(bx, by, bx + ex * inner, by + ey * inner, 0xff4a525c.toInt(), 0xff1a1e23.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(path, p); p.shader = null
            line.color = 0xaa8a949e.toInt(); line.strokeWidth = 0.5f * d
            c.drawPath(path, line)
        }
        c.restore()
        line.color = 0xffd8dee4.toInt(); line.strokeWidth = 1f * d
        c.drawCircle(cx, cy, R, line)
        glow(c, cx - R * 0.35f, cy - R * 0.4f, R * 0.35f, 0x66ffffff)
    }

    // ------------------------------------------------------------------ 5 光

    private val rosette = Path()
    private var rosetteFor = 0f

    /** A guilloché rosette (an epitrochoid), built once per size. */
    private fun rosette(radius: Float): Path {
        if (rosetteFor == radius) return rosette
        rosetteFor = radius
        rosette.reset()
        val R = radius * 0.62f; val rr = radius * 0.11f; val dd = radius * 0.38f
        val k = (R + rr) / rr
        for (s in 0..720) {
            val th = (s / 720f) * 2f * PI.toFloat()
            val x = (R + rr) * cos(th) - dd * cos(k * th)
            val y = (R + rr) * sin(th) - dd * sin(k * th)
            if (s == 0) rosette.moveTo(x, y) else rosette.lineTo(x, y)
        }
        rosette.close()
        return rosette
    }

    private fun gloryScene(c: Canvas, t: Float) {
        val H = win.height()
        val cx = win.centerX(); val cy = win.centerY()
        val breath = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 4.5f)
        glow(c, cx, cy, win.width() * 0.55f, alpha(0xffe9c766.toInt(), 0.35f + 0.2f * breath))
        // rays, long and short, turning slowly
        val spin = t * 0.18f
        for (i in 0 until 64) {
            val a = (2 * PI * i / 64).toFloat() + spin
            val long = i % 2 == 0
            line.color = if (long) 0x99a88435.toInt() else 0x33161514
            line.strokeWidth = (if (long) 0.7f else 0.45f) * d
            val len = win.width() * (if (long) 0.6f else 0.4f)
            c.drawLine(cx + H * 0.3f * cos(a), cy + H * 0.3f * sin(a), cx + len * cos(a), cy + len * sin(a), line)
        }
        // two rosettes, engraved, turning against each other
        val ros = rosette(H * 0.62f)
        line.strokeWidth = 0.5f * d
        c.save(); c.translate(cx, cy); c.rotate(t * 9f)
        line.color = 0x88a88435.toInt(); c.drawPath(ros, line)
        c.restore()
        c.save(); c.translate(cx, cy); c.rotate(-t * 6f + 7f); c.scale(0.78f, 0.78f)
        line.color = 0x66161514; c.drawPath(ros, line)
        c.restore()
        // the embossed gold sun: a rim, a dome, a red stone
        val R = H * 0.24f * (1f + 0.03f * breath)
        p.color = OPAQUE; p.shader = LinearGradient(cx - R, cy - R, cx + R, cy + R, intArrayOf(0xfff6dd8a.toInt(), 0xffa88435.toInt(), 0xff6a4c14.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R, p); p.shader = null
        p.color = OPAQUE; p.shader = RadialGradient(cx - R * 0.3f, cy - R * 0.35f, R * 1.1f, intArrayOf(0xfffff0c0.toInt(), 0xffd2a84c.toInt(), 0xff8a6420.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 0.8f, p); p.shader = null
        line.color = 0x885a3c0c.toInt(); line.strokeWidth = 0.5f * d
        for (k in 0 until 16) {
            val a = (2 * PI * k / 16).toFloat()
            c.drawLine(cx + R * 0.82f * cos(a), cy + R * 0.82f * sin(a), cx + R * 0.97f * cos(a), cy + R * 0.97f * sin(a), line)
        }
        p.color = OPAQUE; p.shader = RadialGradient(cx - R * 0.08f, cy - R * 0.1f, R * 0.35f, intArrayOf(0xffff8a6a.toInt(), 0xffb23a25.toInt(), 0xff5a120a.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 0.3f, p); p.shader = null
        // sparkles going round
        for (k in 0 until 4) {
            val a = t * 0.8f + k * PI.toFloat() / 2
            val sx = cx + win.width() * 0.32f * cos(a); val sy = cy + H * 0.3f * sin(a)
            val s = (0.5f + 0.5f * sin(t * 3f + k * 1.7f)) * 2.6f * d
            line.color = 0xccfff4c8.toInt(); line.strokeWidth = 0.8f * d
            c.drawLine(sx - s, sy, sx + s, sy, line); c.drawLine(sx, sy - s, sx, sy + s, line)
        }
        // the gold rule inside the window
        line.color = 0xffa88435.toInt(); line.strokeWidth = 0.6f * d
        val g = 2.2f * d
        val wr = win.height() / 2
        c.drawRoundRect(win.left + g, win.top + g, win.right - g, win.bottom - g, wr - g, wr - g, line)
    }

    // ------------------------------------------------------------------ 6 经典

    private val leather: BitmapShader by lazy {
        val s = 48
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(5)
        for (y in 0 until s) for (x in 0 until s) {
            val v = rnd.nextFloat()
            b.setPixel(x, y, if (v < 0.12f) 0xff2e2e2f.toInt() else if (v > 0.86f) 0xff0d0d0e.toInt() else 0xff1b1b1c.toInt())
        }
        BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /**
     * 旁轴: through a rangefinder's finder at dusk — a street's lights; the bright-line frame,
     * shifting a little as the focus racks (parallax); in the patch a lamp post and its ghost
     * drawing together into one; the red display stepping through shutter speeds.
     */
    private fun finderScene(c: Canvas, t: Float) {
        val H = win.height(); val W = win.width()
        val cx = win.centerX(); val cy = win.centerY()
        // the street: a skyline's dark and its lit windows
        p.color = 0xff0d0f13.toInt()
        val rnd = java.util.Random(3)
        var x = win.left
        while (x < win.right) {
            val bw = H * (0.35f + rnd.nextFloat() * 0.5f)
            val top = win.top + H * (0.15f + rnd.nextFloat() * 0.35f)
            c.drawRect(x, top, x + bw, win.bottom, p)
            for (k in 0 until 6) {
                val wx = x + bw * (0.15f + 0.7f * rnd.nextFloat()); val wy = top + (win.bottom - top) * (0.1f + 0.75f * rnd.nextFloat())
                val on = sin(t * 0.7f + k + x) > -0.6f
                if (on) { p.color = if (rnd.nextBoolean()) 0xffffd59a.toInt() else 0xffe8eef7.toInt(); c.drawRect(wx, wy, wx + H * 0.035f, wy + H * 0.05f, p); p.color = 0xff0d0f13.toInt() }
            }
            x += bw + H * 0.04f
        }
        // the focus racks back and forth: 0 far off, 1 in focus
        val k = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 4.5f)
        // the bright-line frame, shifting with the focus
        val shift = (1 - k) * H * 0.05f
        val fl = win.left + H * 0.55f + shift; val fr = win.right - H * 0.55f + shift
        val ft = win.top + H * 0.14f + shift * 0.5f; val fb = win.bottom - H * 0.16f + shift * 0.5f
        line.color = 0xfff7f2e6.toInt(); line.strokeWidth = 1.1f * d
        val gap = (fr - fl) * 0.3f
        path.reset()
        path.moveTo(cx - gap / 2 + shift, fb); path.lineTo(fl, fb); path.lineTo(fl, ft); path.lineTo(fr, ft); path.lineTo(fr, fb); path.lineTo(cx + gap / 2 + shift, fb)
        c.drawPath(path, line)
        // the patch: the lamp post and its ghost coming together
        val pw = H * 0.5f; val ph = H * 0.36f
        tmp.set(cx - pw / 2, cy - ph / 2 - H * 0.04f, cx + pw / 2, cy + ph / 2 - H * 0.04f)
        p.color = 0x33fff1c2; c.drawRect(tmp, p)
        c.save(); c.clipRect(tmp)
        line.strokeWidth = 1.6f * d
        line.color = 0xddf3ead6.toInt(); c.drawLine(cx, tmp.top, cx, tmp.bottom, line)
        line.color = 0x88f3ead6.toInt(); c.drawLine(cx + (1 - k) * H * 0.22f, tmp.top, cx + (1 - k) * H * 0.22f, tmp.bottom, line)
        c.restore()
        // the display: the speed the meter finds, stepping
        val speeds = arrayOf("60", "125", "250", "500", "250", "125")
        val s = speeds[((t / 1.1f).toInt()) % speeds.size]
        org.photoall.film.camera.Rangefinder.figures(c, cx + shift, fb, H * 0.16f, s)
    }

    private fun lensScene(c: Canvas, t: Float) {
        val H = win.height()
        val cx = win.centerX(); val cy = win.centerY()
        val R = H * 0.47f
        val focus = sin(t * 2f * PI.toFloat() / 6f) * 0.5f
        // chrome screws either side, set in the leatherette
        for (sx in floatArrayOf(win.left + H * 0.36f, win.right - H * 0.36f)) {
            p.color = 0xff0a0a0a.toInt(); c.drawCircle(sx, cy, H * 0.1f, p)
            p.color = OPAQUE; p.shader = RadialGradient(sx - H * 0.03f, cy - H * 0.03f, H * 0.1f, intArrayOf(0xfff6f5f0.toInt(), 0xff9a9994.toInt(), 0xff4a4945.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(sx, cy, H * 0.082f, p); p.shader = null
            line.color = 0xff2a2a28.toInt(); line.strokeWidth = 0.8f * d
            c.drawLine(sx - H * 0.06f, cy + H * 0.025f, sx + H * 0.06f, cy - H * 0.025f, line)
        }
        // the focus ring: knurled, turning a little back and forth
        p.color = OPAQUE; p.shader = RadialGradient(cx, cy, R, intArrayOf(0xff2a2a2a.toInt(), 0xff111111.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R, p); p.shader = null
        line.color = 0x55ffffff; line.strokeWidth = 0.6f * d
        for (k in 0 until 40) {
            val a = (2 * PI * k / 40).toFloat() + focus
            c.drawLine(cx + R * 0.88f * cos(a), cy + R * 0.88f * sin(a), cx + R * 0.99f * cos(a), cy + R * 0.99f * sin(a), line)
        }
        // the aperture scale ring, chrome, with its numbers going round
        val R2 = R * 0.84f
        p.color = OPAQUE; p.shader = LinearGradient(cx - R2, cy - R2, cx + R2, cy + R2, intArrayOf(0xfff2f1ec.toInt(), 0xff8c8b86.toInt(), 0xffe0dfda.toInt(), 0xff5a5955.toInt()),
            floatArrayOf(0f, 0.4f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R2, p); p.shader = null
        text.typeface = condensed; text.textSize = R * 0.13f; text.color = 0xff1a1a1a.toInt()
        val marks = arrayOf("2", "2.8", "4", "5.6", "8", "11", "16")
        for ((k, m) in marks.withIndex()) {
            val a = -PI.toFloat() * 0.85f + k * 0.26f - focus * 0.4f
            c.save(); c.rotate(Math.toDegrees(a.toDouble() + PI / 2).toFloat(), cx, cy)
            c.drawText(m, cx, cy - R2 * 0.8f, text)
            c.restore()
        }
        // the front element: dark glass with the coatings' colours and a window reflected
        val gR = R * 0.64f
        p.color = 0xff050608.toInt(); c.drawCircle(cx, cy, gR * 1.04f, p)
        p.color = OPAQUE; p.shader = RadialGradient(cx - gR * 0.25f, cy - gR * 0.3f, gR * 1.2f, intArrayOf(0xff26364a.toInt(), 0xff0a0e16.toInt(), 0xff020305.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, gR, p); p.shader = null
        val spin = t * 0.7f
        line.strokeWidth = 1.3f * d
        for ((k, col) in intArrayOf(0x999b6bff.toInt(), 0x7758e0a0, 0x77ffb050).withIndex()) {
            val rr = gR * (0.82f - k * 0.2f)
            tmp.set(cx - rr, cy - rr, cx + rr, cy + rr)
            line.color = col
            c.drawArc(tmp, Math.toDegrees((spin * (1f + k * 0.4f)).toDouble()).toFloat() + k * 70f, 60f - k * 10f, false, line)
        }
        // the blades inside, faint
        line.color = 0x332a3a4a; line.strokeWidth = 0.6f * d
        for (k in 0 until 6) {
            val a = (2 * PI * k / 6).toFloat() + spin * 0.2f
            c.drawLine(cx + gR * 0.25f * cos(a), cy + gR * 0.25f * sin(a), cx + gR * 0.55f * cos(a + 0.9f), cy + gR * 0.55f * sin(a + 0.9f), line)
        }
        // a window's reflection sliding over the glass
        val wx = cx - gR * 0.45f + gR * 0.15f * sin(t * 0.6f)
        val wy = cy - gR * 0.42f
        p.color = 0xbbffffff.toInt()
        c.save(); c.rotate(-12f, wx, wy)
        tmp.set(wx - gR * 0.16f, wy - gR * 0.12f, wx + gR * 0.16f, wy + gR * 0.12f)
        c.drawRoundRect(tmp, 1 * d, 1 * d, p)
        line.color = 0xff10141a.toInt(); line.strokeWidth = 0.7f * d
        c.drawLine(tmp.centerX(), tmp.top, tmp.centerX(), tmp.bottom, line)
        c.drawLine(tmp.left, tmp.centerY(), tmp.right, tmp.centerY(), line)
        c.restore()
        glow(c, cx + gR * 0.4f, cy + gR * 0.4f, gR * 0.25f, 0x55ffffff)
    }

    // ------------------------------------------------------------------ 7 漫

    private fun comicScene(c: Canvas, t: Float) {
        val H = win.height()
        val cx = win.centerX(); val cy = win.centerY()
        // halftone: a pink screen growing toward the ends, pulsing like a beat
        val beat = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 1.4f)
        p.color = 0xffff6fa3.toInt()
        val step = 4.2f * d
        var y = win.top + 2 * d; var row = 0
        while (y < win.bottom) {
            var x = win.left + (row % 2) * step / 2
            while (x < win.right) {
                val e = abs(x - cx) / (win.width() / 2)
                val size = ((e - 0.35f) / 0.65f).coerceIn(0f, 1f) * (0.75f + 0.25f * beat) * 1.8f * d
                if (size > 0.25f * d) c.drawCircle(x, y, size, p)
                x += step
            }
            y += step * 0.86f; row++
        }
        // speed lines radiating, turning slowly
        line.color = 0xff1b1464.toInt()
        for (k in 0 until 18) {
            val a = (2 * PI * k / 18).toFloat() + t * 0.15f
            line.strokeWidth = (if (k % 3 == 0) 1.1f else 0.6f) * d
            val r0 = H * (0.55f + 0.1f * ((k * 7) % 3))
            c.drawLine(cx + r0 * cos(a), cy + r0 * sin(a) * 0.6f, cx + win.width() * cos(a), cy + win.width() * sin(a) * 0.6f, line)
        }
        // the burst behind the star: a jagged balloon, pulsing
        val sc = 1f + 0.06f * beat
        path.reset()
        val spikes = 14
        for (i in 0 until spikes * 2) {
            val a = (PI * i / spikes).toFloat() + 0.1f
            val rr = (if (i % 2 == 0) H * 0.46f else H * 0.32f) * sc * (1f + 0.06f * sin(i * 1.7f))
            val x = cx + rr * cos(a) * 1.35f; val yy = cy + rr * sin(a)
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        path.close()
        p.color = 0xffffe14a.toInt(); c.drawPath(path, p)
        line.color = 0xff1b1464.toInt(); line.strokeWidth = 1.6f * d; c.drawPath(path, line)
        // the star: tilted, swelling, with its highlight
        val tw = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 1.4f + 0.6f)
        val s = H * (0.3f + 0.04f * tw)
        val turn = sin(t * 1.1f) * 10f
        star(c, cx, cy, s, turn, 0xffffffff.toInt(), 0xff1b1464.toInt())
        star(c, cx, cy, s * 0.72f, turn, 0xffffd23a.toInt(), 0x00000000)
        p.color = 0xccffffff.toInt(); c.drawCircle(cx - s * 0.18f, cy - s * 0.22f, s * 0.12f, p)
        // sparkles popping
        for (k in 0 until 4) {
            val life = (t * 0.9f + k / 4f) % 1f
            val a = k * 1.9f + 0.4f
            val px = cx + cos(a) * win.width() * 0.33f; val py = cy + sin(a) * H * 0.34f
            val sz = sin(life * PI.toFloat()) * 3f * d
            if (sz > 0.3f * d) {
                path.reset(); path.moveTo(px, py - sz); path.lineTo(px + sz * 0.25f, py - sz * 0.25f); path.lineTo(px + sz, py)
                path.lineTo(px + sz * 0.25f, py + sz * 0.25f); path.lineTo(px, py + sz); path.lineTo(px - sz * 0.25f, py + sz * 0.25f)
                path.lineTo(px - sz, py); path.lineTo(px - sz * 0.25f, py - sz * 0.25f); path.close()
                p.color = 0xff1b1464.toInt(); c.drawPath(path, p)
            }
        }
    }

    private fun star(c: Canvas, x: Float, y: Float, s: Float, turnDeg: Float, fillColor: Int, ink: Int) {
        path.reset()
        val turn = Math.toRadians(turnDeg.toDouble()).toFloat()
        for (i in 0 until 10) {
            val a = (PI / 5 * i - PI / 2).toFloat() + turn
            val rr = if (i % 2 == 0) s else s * 0.45f
            if (i == 0) path.moveTo(x + rr * cos(a), y + rr * sin(a)) else path.lineTo(x + rr * cos(a), y + rr * sin(a))
        }
        path.close()
        p.color = fillColor; c.drawPath(path, p)
        if ((ink ushr 24) != 0) { line.color = ink; line.strokeWidth = 1.5f * d; c.drawPath(path, line) }
    }

    // ------------------------------------------------------------------ 8 风

    /**
     * Lines and ultramarine blocks on bone paper, as a survey sheet: an ink axis with its ticks,
     * a diagonal that draws itself and is wiped again; a large block filled with a diamond
     * lattice drifting along its diagonal, a light running through its diamonds; a small block
     * sliding along the axis, easing to rest at each end; a diamond outline turning slowly.
     */
    private fun latticeScene(c: Canvas, t: Float) {
        val H = win.height(); val W = win.width()
        val cy = win.centerY()
        val ultra = 0xff1f2bd0.toInt()
        val ink = 0xff121212.toInt()

        // the axis through the middle, ticks along it
        line.color = ink; line.strokeWidth = 0.8f * d
        c.drawLine(win.left + H * 0.25f, cy, win.right - H * 0.25f, cy, line)
        line.strokeWidth = 0.6f * d
        var tx = win.left + H * 0.35f; var k = 0
        while (tx < win.right - H * 0.3f) {
            val long = k % 4 == 0
            c.drawLine(tx, cy - (if (long) H * 0.1f else H * 0.05f), tx, cy, line)
            tx += H * 0.12f; k++
        }

        // the diagonal, drawn and wiped in turn
        val dk = (t % 4.6f) / 4.6f
        val head = ease((dk / 0.4f).coerceIn(0f, 1f))
        val tail = ease(((dk - 0.6f) / 0.35f).coerceIn(0f, 1f))
        val bx0 = win.left + W * 0.1f + W * 0.3f
        val x0 = bx0 + H * 0.12f; val y0 = win.bottom - H * 0.1f
        val x1 = bx0 + H * 0.5f; val y1 = cy + H * 0.04f
        if (head > tail) {
            line.color = ink; line.strokeWidth = 0.9f * d
            c.drawLine(x0 + (x1 - x0) * tail, y0 + (y1 - y0) * tail, x0 + (x1 - x0) * head, y0 + (y1 - y0) * head, line)
        }

        // the large block, its diamond lattice drifting, a light running through it
        val bw = W * 0.3f; val bh = H * 0.66f
        val bx = win.left + W * 0.1f; val by = cy - bh / 2
        tmp.set(bx, by, bx + bw, by + bh)
        p.color = 0xff8e93c8.toInt(); c.drawRect(tmp.left + 2 * d, tmp.top + 2 * d, tmp.right + 2 * d, tmp.bottom + 2 * d, p)
        p.color = ultra; c.drawRect(tmp, p)
        c.save(); c.clipRect(tmp)
        val cell = H * 0.11f             // a diamond's half-diagonal
        val drift = (t * 0.25f % 1f) * cell * 2
        val sweep = (t % 3.2f) / 3.2f    // the light's place across the block, 0..1 (and beyond, to leave)
        val lightX = tmp.left - bw * 0.3f + (bw * 1.6f) * sweep
        var row = -1
        var yy = tmp.top - cell * 2 + drift
        while (yy < tmp.bottom + cell * 2) {
            var xx = tmp.left - cell * 2 + drift + (if (row % 2 == 0) cell else 0f)
            while (xx < tmp.right + cell * 2) {
                // a diamond: its outline in white, lit as the light passes
                val near = (1f - abs(xx - lightX) / (bw * 0.28f)).coerceIn(0f, 1f)
                path.reset()
                path.moveTo(xx, yy - cell); path.lineTo(xx + cell, yy); path.lineTo(xx, yy + cell); path.lineTo(xx - cell, yy); path.close()
                if (near > 0f) { p.color = alpha(0xff9fb0ff.toInt(), 0.55f * near * near); c.drawPath(path, p) }
                line.color = alpha(0xffffffff.toInt(), if (pressed) 0.9f else 0.55f); line.strokeWidth = 0.6f * d
                c.drawPath(path, line)
                // a small dot at each diamond's heart
                p.color = alpha(0xffffffff.toInt(), 0.35f + 0.6f * near)
                c.drawCircle(xx, yy, 0.7f * d, p)
                xx += cell * 2
            }
            yy += cell; row++
        }
        c.restore()
        line.color = ink; line.strokeWidth = 1f * d; c.drawRect(tmp, line)

        // the small block sliding on the axis, resting a moment at each end
        val sk = (t % 5f) / 5f
        val along = when {
            sk < 0.4f -> ease(sk / 0.4f)
            sk < 0.5f -> 1f
            sk < 0.9f -> 1f - ease((sk - 0.5f) / 0.4f)
            else -> 0f
        }
        val sw = H * 0.24f
        val from = tmp.right + H * 0.2f
        val to = win.right - H * 0.5f - sw
        val sx = from + (to - from) * along
        p.color = ultra
        c.drawRect(sx, cy - sw / 2, sx + sw, cy + sw / 2, p)
        line.color = ink; line.strokeWidth = 0.8f * d
        c.drawRect(sx, cy - sw / 2, sx + sw, cy + sw / 2, line)

        // a diamond outline turning slowly at the top right
        val ox = to + sw / 2; val oy = win.top + H * 0.2f; val os = H * 0.1f
        c.save(); c.rotate(t * 30f, ox, oy)
        path.reset(); path.moveTo(ox, oy - os); path.lineTo(ox + os, oy); path.lineTo(ox, oy + os); path.lineTo(ox - os, oy); path.close()
        line.color = ultra; line.strokeWidth = 1.1f * d; c.drawPath(path, line)
        c.restore()
        p.color = ultra; c.drawCircle(ox, oy, 1.2f * d, p)
    }
}

private const val LIQUID = """
    uniform shader content;
    uniform float4 box;
    uniform float radius;
    uniform float edge;
    uniform float bend;
    float sd(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + r;
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    }
    half4 main(float2 p) {
        float2 c = (box.xy + box.zw) * 0.5;
        float2 h = (box.zw - box.xy) * 0.5;
        float2 lp = p - c;
        float d = sd(lp, h, radius);
        if (d > 0.0) return content.eval(p);
        float t = clamp(1.0 + d / edge, 0.0, 1.0);
        float2 n = float2(sd(lp + float2(1, 0), h, radius) - sd(lp - float2(1, 0), h, radius),
                          sd(lp + float2(0, 1), h, radius) - sd(lp - float2(0, 1), h, radius));
        n = n / max(length(n), 1e-4);
        float k = t * t * t * bend;
        float2 q = c + lp * 0.94 - n * k;
        half4 col = content.eval(q);
        col.r = content.eval(q - n * k * 0.3).r;
        col.b = content.eval(q + n * k * 0.3).b;
        // light on the rim: bright along the top, a softer glow up from the bottom
        float top = pow(t, 6.0) * max(0.0, -n.y);
        float bottom = pow(t, 5.0) * max(0.0, n.y);
        col.rgb += half3(0.55 * top + 0.22 * bottom);
        // the pane's body: a faint cool lift over the middle
        col.rgb = mix(col.rgb, half3(1.0), half(0.05 + 0.08 * t));
        return half4(col.rgb, max(col.a, half(0.9)));
    }
"""
