package org.photoall.film.camera

import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rangefinder's optical viewfinder, over the picture (the 旁轴 theme):
 *
 * - the bright-line frames, LED-lit and a little warm, in pairs as on the classic bodies
 *   (28 and 90, 35 and 135, 50 and 75): the lens in use as a full frame just inside the
 *   picture (the finder's frames are drawn a little tight: what is taken runs slightly past
 *   them), its partner as four corner marks at its true size, when it falls inside;
 * - the rangefinder patch in the middle, a brighter, faintly yellow rectangle;
 * - under the frame, red seven-segment figures: the shutter speed the meter chose (1/125 as
 *   "125", two seconds as "2s"); with manual exposure, the meter's two triangles and dot
 *   (▶ under, ● right, ◀ over; one triangle and the dot for half a stop);
 * - the eyepiece's dark falling off towards the corners.
 */
object Rangefinder {
    private val pairs = listOf(28 to 90, 35 to 135, 50 to 75)
    private const val LINE = 0xfff7f2e6.toInt()      // the LEDs' white, a touch warm
    private const val LED = 0xffff2b1c.toInt()       // the display's red
    private const val LED_GLOW = 0x99ff2b1c.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var glowFor = 0f
    private var vignette: RadialGradient? = null
    private var vignetteFor = 0f

    /** The frame partner of the lens in use, and the frame the lens in use is drawn as. */
    private fun pairFor(focal: Int): Pair<Int, Int> {
        val all = pairs.flatMap { listOf(it.first to it.second, it.second to it.first) }
        val nearest = all.minByOrNull { abs(Math.log(it.first.toDouble() / focal.coerceAtLeast(1))) } ?: (50 to 75)
        return nearest
    }

    /**
     * [focal]: the lens in use, 35 mm equivalent; [seconds]: the metered shutter time (auto),
     * [meterStops]: how far the manual setting is from mid-grey (manual), else null.
     */
    /** The patch's size as shares of the picture's short side: across the long side, along the short one. */
    const val PATCH_LONG = 0.21f
    const val PATCH_SHORT = 0.15f

    /** Through the eyepiece (the 旁轴 theme's long press): the field round the frame, the eyepiece's edge. */
    var eye = false

    /**
     * Where the eye is behind the eyepiece, -1..1 each way (0 on the axis), from the phone's
     * motion: the near things (the body, the rim, the tube) move against the far image.
     */
    var eyeX = 0f
    var eyeY = 0f

    /**
     * How far each layer slides against the rim (which is the body, fixed to the screen), as
     * shares of the short side per unit of eye offset: the deeper, the further — the step in
     * the barrel, the glass at its end, and most, the finder's image far beyond.
     */
    const val STEP_SHIFT = 0.04f
    const val TUBE_SHIFT = 0.075f
    const val IMAGE_SHIFT = 0.13f
    const val STEP_SCALE = 0.9f
    const val GLASS_SCALE = 0.76f
    /** The finder's image seen through the window from a distance: just filling it (the frame then matches the picture). */
    const val IMAGE_SCALE = 0.8f

    /** The body round the eyepiece, drawn in the screen's frame (set by the overlay). */
    var body: android.graphics.drawable.Drawable? = null
    var bodyOrigin = floatArrayOf(0f, 0f)
    var bodySize = floatArrayOf(0f, 0f)

    fun draw(c: Canvas, w: Float, h: Float, d: Float, focal: Int, seconds: Double?, meterStops: Float?) {
        if (w <= 0f || h <= 0f) return
        if (h > w) {
            // held upright, as the camera turned on its side: the finder turns with it, its
            // display (the lower edge) along the left, clear of the lens buttons below
            c.save()
            c.rotate(90f, w / 2, h / 2)
            c.translate(w / 2 - h / 2, h / 2 - w / 2)
            drawLevel(c, h, w, d, focal, seconds, meterStops)
            c.restore()
        } else drawLevel(c, w, h, d, focal, seconds, meterStops)
    }

    private fun drawLevel(c: Canvas, w: Float, h: Float, d: Float, focal: Int, seconds: Double?, meterStops: Float?) {
        val short = min(w, h)
        if (eye) {
            // the far image first: moved a little and enlarged with the eye, as the viewfinder's picture is
            c.save()
            c.translate(eyeX * IMAGE_SHIFT * short, eyeY * IMAGE_SHIFT * short)
            c.scale(IMAGE_SCALE, IMAGE_SCALE, w / 2, h / 2)
            finderImage(c, w, h, d, focal, seconds, meterStops, short)
            c.restore()
            eyepiece3d(c, w, h, d, short)
            return
        }
        finderImage(c, w, h, d, focal, seconds, meterStops, short)
    }

    /** The finder's own image: its fall-off, the bright-lines, the patch, the red display. */
    private fun finderImage(c: Canvas, w: Float, h: Float, d: Float, focal: Int, seconds: Double?, meterStops: Float?, short: Float) {
        val stroke = (1.6f * d).coerceAtLeast(short * 0.0045f)

        // the eyepiece: falling off towards the corners (through the eyepiece itself, more)
        val radius = Math.hypot(w / 2.0, h / 2.0).toFloat()
        val key = w * 10000 + h + (if (eye) 0.5f else 0f)
        if (vignette == null || vignetteFor != key) {
            vignette = if (eye) RadialGradient(w / 2, h / 2, radius, intArrayOf(0x00000000, 0x00000000, 0x40000000, 0xe0000000.toInt()),
                floatArrayOf(0f, 0.55f, 0.8f, 0.97f), Shader.TileMode.CLAMP)
            else RadialGradient(w / 2, h / 2, radius, intArrayOf(0x00000000, 0x00000000, 0x55000000, 0xb0000000.toInt()),
                floatArrayOf(0f, 0.62f, 0.86f, 1f), Shader.TileMode.CLAMP)
            vignetteFor = key
        }
        fill.shader = vignette
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // the frame for the lens in use, a little inside the picture; through the eyepiece the
        // finder shows more round it, as a real one does
        val inset = short * (if (eye) 0.09f else 0.055f)
        val frame = RectF(inset, inset, w - inset, h - inset)
        val (_, partner) = pairFor(focal)
        if (glowFor != stroke) {
            glow.maskFilter = BlurMaskFilter(stroke * 2.6f, BlurMaskFilter.Blur.NORMAL)
            glowFor = stroke
        }
        brightFrame(c, frame, stroke, full = true)
        // the partner at its size relative to the lens in use (a 75 inside a 50; a 50 round a 75 does not fit)
        val k = focal.toFloat() / partner
        if (k < 0.97f) {
            val pw = frame.width() * k
            val ph = frame.height() * k
            val inner = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
            brightFrame(c, inner, stroke * 0.9f, full = false)
        }

        // the rangefinder patch (its second image is drawn by the viewfinder, on the GPU)
        val pw = short * PATCH_LONG
        val ph = short * PATCH_SHORT
        rect.set(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        fill.maskFilter = BlurMaskFilter(short * 0.012f, BlurMaskFilter.Blur.NORMAL)
        fill.color = 0x30fff1c2
        c.drawRoundRect(rect, short * 0.01f, short * 0.01f, fill)
        fill.maskFilter = null
        fill.color = 0x14ffffff
        rect.inset(short * 0.008f, short * 0.008f)
        c.drawRoundRect(rect, short * 0.006f, short * 0.006f, fill)

        // the red display in the gap of the frame's lower edge
        val digitH = short * 0.052f
        val cy = frame.bottom
        if (meterStops != null) meter(c, w / 2, cy, digitH, meterStops)
        else if (seconds != null && seconds > 0) figures(c, w / 2, cy, digitH, speedText(seconds))
    }

    private val tube = Path()
    private val back = RectF()
    private val front = RectF()
    private val outer = RectF()

    /**
     * The eyepiece seen from a hand's breadth away, in depth: the body round it (near, out of
     * focus, its texture soft), the rim of the eyepiece, the barrel's inner wall where the eye
     * sees past the axis, the glass at its far end, and the finder's image beyond it.
     */
    private fun eyepiece3d(c: Canvas, w: Float, h: Float, d: Float, short: Float) {
        val ex = eyeX; val ey = eyeY
        // the rim: part of the body, so fixed to the screen; a rounded rectangle wider than tall
        val ow = w * 0.86f
        val oh = min(h * 0.84f, ow / 1.38f)
        outer.set(w / 2 - ow / 2, h / 2 - oh / 2, w / 2 + ow / 2, h / 2 + oh / 2)
        val ring = short * 0.05f
        front.set(outer); front.inset(ring, ring)
        val ro = short * 0.09f
        val rf = ro - ring * 0.6f
        // deeper in: a step half way down the barrel, then the glass at its end; the further back,
        // the smaller, and the further it slides against the rim as the eye moves off the axis
        fun deep(scale: Float, shift: Float, into: RectF) {
            val cx = w / 2 + ex * shift * short
            val cy = h / 2 + ey * shift * short
            into.set(cx - front.width() * scale / 2, cy - front.height() * scale / 2, cx + front.width() * scale / 2, cy + front.height() * scale / 2)
        }
        deep(STEP_SCALE, STEP_SHIFT, step)
        deep(GLASS_SCALE, TUBE_SHIFT, back)
        val rs = rf * STEP_SCALE
        val rb = rf * GLASS_SCALE
        val grain = matte()

        // the barrel's wall: one matte surface from under the rim to under the glass's edge (each
        // layer reaches under the next, so no seam ever opens to the bright image behind)
        val under = short * 0.012f
        rect.set(front); rect.inset(-under, -under)
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRoundRect(rect, rf + under, rf + under, Path.Direction.CW)
        val glassUnder = RectF(back); glassUnder.inset(under, under)
        path.addRoundRect(glassUnder, (rb - under).coerceAtLeast(0f), (rb - under).coerceAtLeast(0f), Path.Direction.CW)
        c.save()
        c.clipPath(path)
        path.fillType = Path.FillType.WINDING
        // diffuse light from above: the upper wall faces it, the lower one turns away
        fill.color = 0xff000000.toInt()
        fill.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(0xff2c2b29.toInt(), 0xff1a1918.toInt(), 0xff0e0e0d.toInt(), 0xff090909.toInt()), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(rect, fill)
        fill.shader = null
        // the side the eye looks across is seen more face-on, so a touch lighter
        // (across the whole width, fading to nothing in the middle: no edge, whatever the eye does)
        val lit = { k: Float -> ((k.coerceIn(0f, 1f) * 14).toInt() shl 24) or 0xffffff }
        fill.shader = android.graphics.LinearGradient(front.left, 0f, front.right, 0f,
            intArrayOf(lit(-ex), 0x00ffffff, lit(ex)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(rect, fill)
        fill.shader = null
        // depth: darker towards the glass (light reaches the far end of a barrel least)
        glow.maskFilter = BlurMaskFilter(short * 0.05f, BlurMaskFilter.Blur.NORMAL)
        glow.color = 0xd0000000.toInt()
        glow.strokeWidth = short * 0.09f
        c.drawRoundRect(back, rb, rb, glow)
        // the step half way: a soft change in the wall, lit faintly along its upper side
        glow.maskFilter = BlurMaskFilter(short * 0.012f, BlurMaskFilter.Blur.NORMAL)
        glow.strokeWidth = short * 0.016f
        glow.shader = android.graphics.LinearGradient(0f, step.top, 0f, step.bottom, intArrayOf(0x22ffffff, 0x08ffffff, 0x00ffffff), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        glow.color = 0xff000000.toInt()
        c.drawRoundRect(step, rs, rs, glow)
        glow.shader = null
        // the rim's shadow cast down onto the wall
        glow.maskFilter = BlurMaskFilter(short * 0.03f, BlurMaskFilter.Blur.NORMAL)
        glow.color = 0xb0000000.toInt()
        glow.strokeWidth = short * 0.05f
        c.drawRoundRect(front.left, front.top + short * 0.01f, front.right, front.bottom + short * 0.01f, rf, rf, glow)
        // the material's fine grain
        fill.shader = grain
        fill.alpha = 255
        c.drawRect(rect, fill)
        fill.shader = null
        c.restore()
        glowFor = -1f

        // the glass's edge, soft, over the wall's last stretch; the image beyond is cut by it
        glow.maskFilter = BlurMaskFilter(short * 0.02f, BlurMaskFilter.Blur.NORMAL)
        glow.color = 0xf0000000.toInt()
        glow.strokeWidth = short * 0.04f
        c.drawRoundRect(back, rb, rb, glow)
        // a faint sheen on the glass, moving against the eye
        c.save()
        tube.reset(); tube.addRoundRect(back, rb, rb, Path.Direction.CW)
        c.clipPath(tube)
        val sx = back.left + back.width() * (0.25f - 0.3f * ex)
        fill.color = 0xff000000.toInt()
        fill.shader = android.graphics.LinearGradient(sx, back.top, sx + back.width() * 0.35f, back.bottom,
            intArrayOf(0x00ffffff, 0x0bffffff, 0x00ffffff), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(back, fill)
        fill.shader = null
        c.restore()

        // the body and the rim, which do not move with the eye: laid over the moving inside (the
        // wall stays inside the rim, so the order makes no difference)
        eyepieceStill(c, w, h, short, ro, rf, ring, grain)
    }

    /** Out of the eyepiece (nothing held for it now). */
    fun releaseEye() {}

    /** What stays put through the eyepiece: the body round it and the rubber rim (geometry in [outer], [front]). */
    private fun eyepieceStill(c: Canvas, w: Float, h: Float, short: Float, ro: Float, rf: Float, ring: Float, grain: BitmapShader) {
        // the body: everything outside the rim, the screen-wide vulcanite under a veil (near the eye,
        // not in focus); reaching a little in under the rim, so their two edges never leave a gap
        val tuck = short * 0.015f
        val bodyHole = RectF(outer); bodyHole.inset(tuck, tuck)
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(-w, -h, 2 * w, 2 * h, Path.Direction.CW)
        path.addRoundRect(bodyHole, ro - tuck, ro - tuck, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        val b = body
        if (b != null && bodySize[0] > 0f) {
            c.save()
            c.translate(-bodyOrigin[0], -bodyOrigin[1])
            b.setBounds(0, 0, bodySize[0].toInt(), bodySize[1].toInt())
            b.draw(c)
            c.restore()
            c.drawColor(0x8a000000.toInt())
        } else c.drawColor(0xff0b0b0b.toInt())
        c.restore()
        path.fillType = Path.FillType.WINDING

        // the rim: matte black rubber, a broad soft light on its upper face, no glints; its soft shadow on the body
        fill.maskFilter = BlurMaskFilter(short * 0.04f, BlurMaskFilter.Blur.OUTER)
        fill.color = 0xe0000000.toInt()
        c.drawRoundRect(outer.left, outer.top + short * 0.018f, outer.right, outer.bottom + short * 0.018f, ro, ro, fill)
        fill.maskFilter = null
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRoundRect(outer, ro, ro, Path.Direction.CW)
        path.addRoundRect(front, rf, rf, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        path.fillType = Path.FillType.WINDING
        fill.color = 0xff000000.toInt() // opaque: a paint's alpha also weighs its shader
        fill.shader = android.graphics.LinearGradient(0f, outer.top, 0f, outer.bottom,
            intArrayOf(0xff2a2a29.toInt(), 0xff1b1b1a.toInt(), 0xff121212.toInt(), 0xff0e0e0e.toInt()), floatArrayOf(0f, 0.25f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(outer, fill)
        fill.shader = null
        // the rounded lip's light: broad and soft along the top, as on rubber, not a line
        glow.maskFilter = BlurMaskFilter(short * 0.02f, BlurMaskFilter.Blur.NORMAL)
        glow.color = 0xff000000.toInt()
        glow.shader = android.graphics.LinearGradient(0f, outer.top, 0f, outer.top + ring * 2, intArrayOf(0x1cffffff, 0x00ffffff), null, Shader.TileMode.CLAMP)
        glow.strokeWidth = ring * 0.6f
        c.drawRoundRect(outer.left + ring * 0.45f, outer.top + ring * 0.45f, outer.right - ring * 0.45f, outer.bottom - ring * 0.45f, ro - ring * 0.45f, ro - ring * 0.45f, glow)
        glow.shader = null
        // the inner edge turning down into the barrel, darker
        glow.color = 0x80000000.toInt()
        glow.strokeWidth = ring * 0.35f
        glow.maskFilter = BlurMaskFilter(short * 0.01f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(front, rf, rf, glow)
        fill.shader = grain
        c.drawRect(outer, fill)
        fill.shader = null
        c.restore()
        glowFor = -1f
    }

    private val step = RectF()
    private var grainShader: BitmapShader? = null

    /** A fine, faint grain, light and dark specks: the look of a matte surface. One tile, made once. */
    private fun matte(): BitmapShader {
        grainShader?.let { return it }
        val n = 96
        val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(23)
        for (y in 0 until n) for (x in 0 until n) {
            val v = rnd.nextFloat()
            b.setPixel(x, y, when {
                v < 0.18f -> 0x0d000000
                v > 0.86f -> 0x0affffff
                else -> 0
            })
        }
        return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { grainShader = it }
    }

    /** A bright-line frame: full (the lens in use: whole top and sides, the bottom with a gap) or corner marks. */
    private fun brightFrame(c: Canvas, r: RectF, stroke: Float, full: Boolean) {
        path.reset()
        if (full) {
            val gap = r.width() * 0.34f
            path.moveTo(r.left + r.width() / 2 - gap / 2, r.bottom)
            path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.bottom)
            path.lineTo(r.left + r.width() / 2 + gap / 2, r.bottom)
        } else {
            val l = min(r.width(), r.height()) * 0.14f
            for ((x, y, sx, sy) in listOf(floatArrayOf(r.left, r.top, 1f, 1f), floatArrayOf(r.right, r.top, -1f, 1f),
                floatArrayOf(r.left, r.bottom, 1f, -1f), floatArrayOf(r.right, r.bottom, -1f, -1f))) {
                path.moveTo(x + sx * l, y); path.lineTo(x, y); path.lineTo(x, y + sy * l)
            }
        }
        glow.color = 0x66fff4dc
        glow.strokeWidth = stroke * 2.2f
        c.drawPath(path, glow)
        line.color = LINE
        line.strokeWidth = stroke
        c.drawPath(path, line)
    }

    /** The speed as the display shows it: the denominator for fractions, seconds with an "s". */
    fun speedText(seconds: Double): String = when {
        seconds >= 0.95 -> if (seconds < 9.5) "%.0fs".format(seconds) else "%ds".format(seconds.roundToInt())
        else -> {
            val den = 1 / seconds
            val nominal = intArrayOf(2, 4, 8, 15, 30, 60, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
            val near = nominal.minByOrNull { abs(Math.log(it / den)) } ?: den.roundToInt()
            if (abs(Math.log(near / den)) < 0.18) "$near" else "${den.roundToInt()}"
        }
    }

    // ---- the seven-segment display ------------------------------------------------------------

    //  a
    // f b
    //  g
    // e c
    //  d
    private val glyphs = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg", '5' to "acdfg",
        '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg", '-' to "g", 's' to "acdfg",
    )

    fun figures(c: Canvas, cx: Float, cy: Float, h: Float, text: String) {
        val dw = h * 0.56f           // a digit's width
        val gap = h * 0.22f
        val dot = h * 0.16f
        var total = 0f
        for (ch in text) total += if (ch == '.') dot else dw + gap
        total -= gap
        var x = cx - total / 2
        val top = cy - h / 2
        for (ch in text) {
            if (ch == '.') {
                ledRect(c, x - gap * 0.6f, top + h - h * 0.11f, h * 0.11f, h * 0.11f)
                x += dot
                continue
            }
            digit(c, x, top, dw, h, glyphs[ch] ?: "")
            x += dw + gap
        }
    }

    private fun digit(c: Canvas, x: Float, y: Float, w: Float, h: Float, on: String) {
        val t = h * 0.11f            // a segment's thickness
        val half = h / 2
        val slant = h * 0.08f        // the figures lean, as the LEDs do
        fun sx(px: Float, py: Float) = px + slant * (1 - (py - y) / h)
        fun bar(x0: Float, y0: Float, x1: Float, y1: Float) {
            path.reset()
            if (y0 == y1) { // horizontal
                path.moveTo(sx(x0 + t * 0.6f, y0), y0 - t / 2); path.lineTo(sx(x1 - t * 0.6f, y0), y0 - t / 2)
                path.lineTo(sx(x1 - t * 0.1f, y0), y0); path.lineTo(sx(x1 - t * 0.6f, y0), y0 + t / 2)
                path.lineTo(sx(x0 + t * 0.6f, y0), y0 + t / 2); path.lineTo(sx(x0 + t * 0.1f, y0), y0); path.close()
            } else {
                path.moveTo(sx(x0 - t / 2, y0 + t * 0.6f), y0 + t * 0.6f); path.lineTo(sx(x0, y0 + t * 0.1f), y0 + t * 0.1f)
                path.lineTo(sx(x0 + t / 2, y0 + t * 0.6f), y0 + t * 0.6f); path.lineTo(sx(x0 + t / 2, y1 - t * 0.6f), y1 - t * 0.6f)
                path.lineTo(sx(x0, y1 - t * 0.1f), y1 - t * 0.1f); path.lineTo(sx(x0 - t / 2, y1 - t * 0.6f), y1 - t * 0.6f); path.close()
            }
            led(c, path, t)
        }
        if ('a' in on) bar(x, y, x + w, y)
        if ('b' in on) bar(x + w, y, x + w, y + half)
        if ('c' in on) bar(x + w, y + half, x + w, y + h)
        if ('d' in on) bar(x, y + h, x + w, y + h)
        if ('e' in on) bar(x, y + half, x, y + h)
        if ('f' in on) bar(x, y, x, y + half)
        if ('g' in on) bar(x, y + half, x + w, y + half)
    }

    /** The meter in manual: ▶ under, ● right, ◀ over (a triangle with the dot: half a stop). */
    private fun meter(c: Canvas, cx: Float, cy: Float, h: Float, stops: Float) {
        val s = h * 0.62f
        val spacing = h * 1.25f
        val dotOn = abs(stops) < 0.9f
        val under = stops < -0.3f
        val over = stops > 0.3f
        if (under) { // ▶ left of the dot: turn towards more light
            path.reset()
            path.moveTo(cx - spacing - s / 2, cy - s / 2); path.lineTo(cx - spacing + s / 2, cy); path.lineTo(cx - spacing - s / 2, cy + s / 2); path.close()
            led(c, path, s * 0.2f)
        }
        if (dotOn) {
            path.reset(); path.addCircle(cx, cy, s * 0.3f, Path.Direction.CW)
            led(c, path, s * 0.2f)
        }
        if (over) { // ◀ right of the dot
            path.reset()
            path.moveTo(cx + spacing + s / 2, cy - s / 2); path.lineTo(cx + spacing - s / 2, cy); path.lineTo(cx + spacing + s / 2, cy + s / 2); path.close()
            led(c, path, s * 0.2f)
        }
    }

    private fun ledRect(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        path.reset(); path.addRect(x, y, x + w, y + h, Path.Direction.CW)
        led(c, path, w)
    }

    /** A lit segment: its red glow, then the segment itself. */
    private fun led(c: Canvas, p: Path, t: Float) {
        seg.style = Paint.Style.FILL
        seg.color = LED_GLOW
        seg.maskFilter = BlurMaskFilter(t * 1.4f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(p, seg)
        seg.maskFilter = null
        seg.color = LED
        c.drawPath(p, seg)
    }

    // ---- the theme picker's card --------------------------------------------------------------

    /** The bright-lines and patch, small, in a theme card's finder. */
    fun drawMini(c: Canvas, finder: RectF, d: Float) {
        val inset = min(finder.width(), finder.height()) * 0.1f
        val r = RectF(finder.left + inset, finder.top + inset, finder.right - inset, finder.bottom - inset)
        brightFrame(c, r, 1.1f * d, full = true)
        val k = 50f / 75
        val inner = RectF(r.centerX() - r.width() * k / 2, r.centerY() - r.height() * k / 2, r.centerX() + r.width() * k / 2, r.centerY() + r.height() * k / 2)
        brightFrame(c, inner, 1f * d, full = false)
        fill.color = 0x40fff1c2
        val pw = r.width() * 0.2f; val ph = r.height() * 0.2f
        c.drawRect(r.centerX() - pw / 2, r.centerY() - ph / 2, r.centerX() + pw / 2, r.centerY() + ph / 2, fill)
        figures(c, r.centerX(), r.bottom, r.height() * 0.14f, "125")
    }
}
