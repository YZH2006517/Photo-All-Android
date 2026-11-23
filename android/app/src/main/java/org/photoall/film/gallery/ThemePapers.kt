package org.photoall.film.gallery

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The camera themes' papers, made as posters: a 4:5 sheet, the photo set off-centre as
 * its subject, and a composition built round it in one restrained language: paper with
 * real tooth, stippled shading instead of gradients, halftone, graphite hairlines and
 * pencil-drawn architecture, one strong accent colour, a serif italic and a small mono.
 * Nothing touches the photo's middle.
 *
 *  - 光 · LUMEN: a cathedral of light in pale blue and gold. A stained-glass rose in
 *    mosaic breaking behind the photo's corner, its light falling in soft shafts, a film
 *    strip arcing through, wireframe solids, a gilt hairline border with a fine pattern.
 *  - 飞鸟 · ORBITAL LETTER: graphite on warm grey, cobalt the only colour. A gothic
 *    elevation pencilled in, a stippled cobalt eye, a stream of swallows pouring into it,
 *    a black panel dissolving into dither, orbits with their stations named in serif.
 *  - 漫 · POP SIGNAL: a cobalt field with grain, a halftone curtain drawn back on one side,
 *    a hairline grid with sparks at its crossings, focus lines, a coral accent.
 *  - 拟真 · OPTICAL: the camera as a patent drawing. A lens in section with stippled
 *    glass, rays meeting at the focal plane, an iris in hairline, its parts named.
 *
 * Everything is measured in u = a hundredth of the sheet's width.
 */
object ThemePapers {
    enum class Theme { SACRED, FATE, ANIME, CAMERA, FILM }

    class Plan(val w: Int, val h: Int, val photo: RectF, val text: RectF, val keep: List<RectF>) {
        val u get() = w / 100f
        val sheet get() = RectF(0f, 0f, w.toFloat(), h.toFloat())
    }

    /** Where the user put the photo by hand: its centre and width as shares of the sheet. */
    class Place(val x: Float, val y: Float, val size: Float)

    fun plan(t: Theme, pw: Int, ph: Int, maxLong: Int, place: Place? = null): Plan {
        // laid out in units first (the sheet 100 x 125); its size in pixels after, so the photo
        // keeps its own resolution on it (it used to come out at about 0.6 of it)
        val H = 125f
        val (region, ax, ay) = when (t) {
            Theme.SACRED -> Triple(RectF(25f, 47f, 75f, H * 0.7f), 0.5f, 0.5f)
            Theme.FATE -> Triple(RectF(8f, 16f, 70f, H * 0.66f), 0f, 0.3f)
            Theme.ANIME -> Triple(RectF(10f, 14f, 90f, H * 0.64f), 0.5f, 0.25f)
            Theme.CAMERA -> Triple(RectF(10f, 12f, 90f, H * 0.58f), 0.5f, 0f)
            Theme.FILM -> Triple(RectF(9f, 8f, 91f, H * 0.53f), 0.5f, 0f)
        }
        val k = min(region.width() / pw, region.height() / ph)
        var pwU = pw * k
        var phU = ph * k
        var left = region.left + (region.width() - pwU) * ax
        var top = region.top + (region.height() - phU) * ay
        if (place != null) {
            // placed by hand: as wide as asked (kept on the sheet), centred where it was let go
            pwU = (place.size * 100f).coerceIn(15f, 100f)
            phU = pwU * ph / pw
            if (phU > H) { phU = H; pwU = phU * pw / ph }
            left = (place.x * 100f - pwU / 2).coerceIn(0f, 100f - pwU)
            top = (place.y * H - phU / 2).coerceIn(0f, H - phU)
        }
        val h = min(maxLong.toFloat(), pw / pwU * H).coerceAtLeast(800f).toInt()
        val w = (h * 0.8f).toInt()
        val u = w / 100f
        val photo = RectF(left * u, top * u, (left + pwU) * u, (top + phU) * u)
        fun r(l: Float, t2: Float, rr: Float, b: Float) = RectF(l * u, t2 * u, rr * u, b * u)
        val textTop = max(top + phU + 8f, H * 0.84f)
        val text = when (t) {
            Theme.SACRED -> r(30f, H - 25f, 70f, H - 17.5f)
            Theme.FATE -> r(8f, textTop, 58f, min(H - 6f, textTop + 11f))
            Theme.ANIME -> r(12f, textTop + 3f, 60f, min(H - 9f, textTop + 12f))
            Theme.CAMERA -> r(10f, textTop, 60f, min(H - 6f, textTop + 11f))
            Theme.FILM -> r(42f, H - 50f, 92f, H - 40f)
        }
        val keep = when (t) {
            Theme.SACRED -> emptyList()
            Theme.FATE -> listOf(r(62f, H - 40f, 100f, H))
            Theme.ANIME -> emptyList()
            Theme.CAMERA -> listOf(r(62f, H - 44f, 100f, H))
            Theme.FILM -> listOf(r(0f, H - 52f, 40f, H), r(0f, H - 36f, 100f, H))
        }
        return Plan(w, h, photo, text, keep)
    }

    fun ink(t: Theme): Int = when (t) {
        Theme.SACRED -> 0xff2b2925.toInt()
        Theme.FATE -> 0xff262626.toInt()
        Theme.ANIME -> 0xff1b2a4a.toInt()
        Theme.CAMERA -> 0xff1c1c1c.toInt()
        Theme.FILM -> 0xff2a2724.toInt()
    }

    fun drawUnder(c: Canvas, t: Theme, p: Plan, seed: Int, words: Boolean = true) {
        val rnd = Random(seed * 7349 + t.ordinal)
        when (t) {
            Theme.SACRED -> churchUnder(c, p, rnd)
            Theme.FATE -> orbitUnder(c, p, rnd)
            Theme.ANIME -> skyUnder(c, p, rnd, words)
            Theme.CAMERA -> opticalUnder(c, p, rnd)
            Theme.FILM -> studioUnder(c, p)
        }
    }

    fun drawOver(c: Canvas, t: Theme, p: Plan, seed: Int, date: String, frame: Int, film: String = "") {
        val rnd = Random(seed * 104729 + t.ordinal * 31)
        when (t) {
            Theme.SACRED -> churchOver(c, p, rnd)
            Theme.FATE -> orbitOver(c, p, rnd, date)
            Theme.ANIME -> skyOver(c, p, rnd, date)
            Theme.CAMERA -> opticalOver(c, p, frame)
            Theme.FILM -> studioOver(c, p, film, frame)
        }
        tooth(c, p, rnd, if (t == Theme.ANIME) 0.7f else 1f)
    }

    // ============================================================ the toolkit

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun a(alpha: Float, rgb: Int) = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xffffff)
    private fun hair(u: Float, color: Int, w: Float = 0.08f) = paint().apply {
        style = Paint.Style.STROKE; strokeWidth = w * u; this.color = color; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** The paper's tooth: a fine dither of light and dark specks, densest off the photo. */
    private fun tooth(c: Canvas, p: Plan, rnd: Random, k: Float) {
        val u = p.u
        val n = (p.w.toLong() * p.h / 900).toInt().coerceAtMost(24000)
        val dark = FloatArray(n)
        val light = FloatArray(n)
        var nd = 0
        var nl = 0
        val core = core(p)
        repeat(n / 2) {
            val x = rnd.nextFloat() * p.w
            val y = rnd.nextFloat() * p.h
            if (core.contains(x, y) && rnd.nextFloat() < 0.85f) return@repeat
            if (rnd.nextBoolean()) { dark[nd++] = x; dark[nd++] = y } else { light[nl++] = x; light[nl++] = y }
        }
        val q = paint().apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 0.09f * u }
        q.color = a(0.11f * k, 0x000000)
        c.drawPoints(dark, 0, nd, q)
        q.color = a(0.16f * k, 0xffffff)
        c.drawPoints(light, 0, nl, q)
    }

    /**
     * Stippled shading: dots scattered over [bounds] (inside [clip] when given), each kept
     * with the probability [density] gives at its place. Tone by how many, not how dark.
     */
    private fun stipple(c: Canvas, bounds: RectF, n: Int, dot: Float, color: Int, rnd: Random, clip: Path? = null,
                        density: (Float, Float) -> Float) {
        val pts = FloatArray(n * 2)
        var k = 0
        repeat(n) {
            val x = bounds.left + rnd.nextFloat() * bounds.width()
            val y = bounds.top + rnd.nextFloat() * bounds.height()
            if (rnd.nextFloat() < density(x, y)) { pts[k++] = x; pts[k++] = y }
        }
        c.save()
        clip?.let { c.clipPath(it) }
        c.drawPoints(pts, 0, k, paint().apply { this.color = color; strokeWidth = dot; strokeCap = Paint.Cap.ROUND })
        c.restore()
    }

    /** A pencil line: drawn twice, a little off each time, as a hand draws it. */
    private fun pencil(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, q: Paint, rnd: Random, wob: Float) {
        for (pass in 0..1) {
            val path = Path()
            val n = max(2, (hypot(x1 - x0, y1 - y0) / (wob * 40f)).toInt())
            for (i in 0..n) {
                val t = i / n.toFloat()
                val j = if (i == 0 || i == n) 0f else (rnd.nextFloat() - 0.5f) * wob
                val x = x0 + (x1 - x0) * t + j
                val y = y0 + (y1 - y0) * t + j
                if (i == 0) path.moveTo(x + pass * wob * 0.3f, y) else path.lineTo(x, y + pass * wob * 0.3f)
            }
            c.drawPath(path, q)
        }
    }

    /** A pointed gothic window in pencil: jambs, a two-centred arch, a roundel in its head, hatching inside. */
    private fun lancet(c: Canvas, x: Float, bottom: Float, w: Float, h: Float, q: Paint, rnd: Random, hatch: Boolean) {
        val spring = bottom - h + w * 0.9f
        val top = bottom - h
        pencil(c, x, bottom, x, spring, q, rnd, w * 0.02f)
        pencil(c, x + w, bottom, x + w, spring, q, rnd, w * 0.02f)
        val arch = Path().apply {
            moveTo(x, spring)
            cubicTo(x, spring - w * 0.5f, x + w * 0.3f, top + w * 0.1f, x + w / 2, top)
            cubicTo(x + w * 0.7f, top + w * 0.1f, x + w, spring - w * 0.5f, x + w, spring)
        }
        c.drawPath(arch, q)
        c.drawCircle(x + w / 2, spring - w * 0.25f, w * 0.18f, q)
        if (hatch) {
            val hq = Paint(q).apply { alpha = (q.alpha * 0.45f).toInt() }
            var yy = spring + w * 0.2f
            while (yy < bottom) { c.drawLine(x + w * 0.1f, yy, x + w * 0.9f, yy - w * 0.35f, hq); yy += w * 0.22f }
        }
    }

    /** A four-pointed spark, as at a grid's crossings. */
    private fun spark(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val path = Path().apply {
            moveTo(x, y - r); quadTo(x + r * 0.06f, y - r * 0.06f, x + r, y)
            quadTo(x + r * 0.06f, y + r * 0.06f, x, y + r); quadTo(x - r * 0.06f, y + r * 0.06f, x - r, y)
            quadTo(x - r * 0.06f, y - r * 0.06f, x, y - r); close()
        }
        c.drawPath(path, paint().apply { this.color = color })
    }

    private fun serif(size: Float, color: Int, italic: Boolean = true) = paint().apply {
        typeface = Typeface.create(Typeface.SERIF, if (italic) Typeface.ITALIC else Typeface.NORMAL)
        textSize = size; this.color = color
    }

    private fun mono(size: Float, color: Int) = paint().apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textSize = size; this.color = color; letterSpacing = 0.18f
    }

    /** A named station: a dot, the word in a serif italic, the Chinese spaced out under it. */
    private fun station(c: Canvas, x: Float, y: Float, en: String, zh: String, u: Float, ink: Int, dot: Int, r: Float = 0.7f) {
        c.drawCircle(x, y, r * u, paint().apply { color = dot })
        c.drawText(en, x + 1.4f * u, y - 0.4f * u, serif(1.9f * u, ink))
        c.drawText(zh, x + 1.5f * u, y + 1.7f * u, paint().apply {
            textSize = 1.15f * u; color = a(0.75f, ink); letterSpacing = 0.5f
        })
    }

    /** A swallow in flight: swept wings, a forked tail. */
    private fun swallow(c: Canvas, x: Float, y: Float, s: Float, heading: Float, q: Paint) {
        c.save()
        c.translate(x, y)
        c.rotate(heading)
        val path = Path().apply {
            moveTo(s * 1.0f, 0f)                                   // beak
            quadTo(s * 0.55f, -s * 0.12f, s * 0.25f, -s * 0.1f)
            quadTo(-s * 0.1f, -s * 0.95f, -s * 0.9f, -s * 1.25f)   // upper wing, swept back
            quadTo(-s * 0.35f, -s * 0.45f, -s * 0.2f, -s * 0.12f)
            lineTo(-s * 0.55f, -s * 0.06f)
            lineTo(-s * 1.35f, -s * 0.42f)                         // tail fork
            lineTo(-s * 0.75f, 0f)
            lineTo(-s * 1.35f, s * 0.3f)
            lineTo(-s * 0.55f, s * 0.08f)
            quadTo(-s * 0.3f, s * 0.35f, -s * 0.6f, s * 0.9f)      // lower wing
            quadTo(-s * 0.05f, s * 0.4f, s * 0.25f, s * 0.1f)
            quadTo(s * 0.6f, s * 0.12f, s * 1.0f, 0f)
            close()
        }
        c.drawPath(path, q)
        c.restore()
    }

    private fun core(p: Plan) = RectF(p.photo).apply { inset(p.photo.width() * 0.12f, p.photo.height() * 0.12f) }

    private fun cropTicks(c: Canvas, r: RectF, u: Float, q: Paint, gap: Float = 1.2f, len: Float = 2.4f) {
        for ((x, y, sx, sy) in listOf(floatArrayOf(r.left, r.top, -1f, -1f), floatArrayOf(r.right, r.top, 1f, -1f),
                                     floatArrayOf(r.left, r.bottom, -1f, 1f), floatArrayOf(r.right, r.bottom, 1f, 1f))) {
            c.drawLine(x + sx * gap * u, y, x + sx * (gap + len) * u, y, q)
            c.drawLine(x, y + sy * gap * u, x, y + sy * (gap + len) * u, q)
        }
    }

    /**
     * A gothic rose window: a roundel at the heart with an eight-pointed star, eight petals
     * round it, sixteen lancets radiating out, sixteen quatrefoil oculi at the rim, all in
     * dark lead with glass that glows lighter at each pane's middle; a gilt rim round it.
     */
    private fun rose(c: Canvas, x: Float, y: Float, r: Float, u: Float) {
        val q = paint()
        // its light on the wall round it
        q.shader = RadialGradient(x, y, r * 2.4f, intArrayOf(a(0.55f, 0xfff1cf), a(0.18f, 0xffe7b0), a(0f, 0xffe7b0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r * 2.4f, q)
        q.shader = null
        val leadC = 0xff16193a.toInt()
        q.color = leadC
        c.drawCircle(x, y, r, q)
        val lead = hair(u, leadC, 0.22f)
        fun glass(path: Path, cx: Float, cy: Float, rr: Float, col: Int) {
            val light = blend(col, 0xffffffff.toInt(), 0.45f)
            q.shader = RadialGradient(cx, cy, rr, intArrayOf(light, col, blend(col, 0xff000000.toInt(), 0.25f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, q)
            q.shader = null
            c.drawPath(path, lead)
        }
        val ruby = 0xffb3242c.toInt()
        val blueD = 0xff1d3a9e.toInt()
        val blueL = 0xff3b6fd1.toInt()
        val amber = 0xffd99a2b.toInt()
        val green = 0xff1f7a55.toInt()
        val tau = 2 * PI.toFloat()
        // sixteen oculi at the rim, each a quatrefoil
        for (k in 0 until 16) {
            val ang = k * tau / 16 + tau / 32
            val ox = x + cos(ang) * r * 0.865f
            val oy = y + sin(ang) * r * 0.865f
            val orr = r * 0.105f
            val ring = Path().apply { addCircle(ox, oy, orr, Path.Direction.CW) }
            glass(ring, ox, oy, orr, if (k % 2 == 0) ruby else amber)
            val f = orr * 0.42f
            for ((dx, dy) in listOf(-f to 0f, f to 0f, 0f to -f, 0f to f)) c.drawCircle(ox + dx, oy + dy, f, hair(u, leadC, 0.12f))
        }
        // sixteen lancets pointing outward
        for (k in 0 until 16) {
            val ang = k * tau / 16
            val half = tau / 16 * 0.36f
            val r0 = r * 0.47f
            val r1 = r * 0.74f
            val path = Path().apply {
                moveTo(x + cos(ang - half) * r0, y + sin(ang - half) * r0)
                lineTo(x + cos(ang - half) * r1, y + sin(ang - half) * r1)
                quadTo(x + cos(ang - half * 0.4f) * (r1 + r * 0.06f), y + sin(ang - half * 0.4f) * (r1 + r * 0.06f), x + cos(ang) * (r1 + r * 0.09f), y + sin(ang) * (r1 + r * 0.09f))
                quadTo(x + cos(ang + half * 0.4f) * (r1 + r * 0.06f), y + sin(ang + half * 0.4f) * (r1 + r * 0.06f), x + cos(ang + half) * r1, y + sin(ang + half) * r1)
                lineTo(x + cos(ang + half) * r0, y + sin(ang + half) * r0)
                close()
            }
            val mx = x + cos(ang) * (r0 + r1) / 2
            val my = y + sin(ang) * (r0 + r1) / 2
            glass(path, mx, my, r * 0.18f, if (k % 2 == 0) blueD else blueL)
            // a small roundel in each lancet's head
            val hx = x + cos(ang) * r1 * 0.97f
            val hy = y + sin(ang) * r1 * 0.97f
            glass(Path().apply { addCircle(hx, hy, r * 0.028f, Path.Direction.CW) }, hx, hy, r * 0.028f, if (k % 4 == 0) green else amber)
        }
        // eight petals round the heart
        for (k in 0 until 8) {
            val ang = k * tau / 8
            val pr = r * 0.13f
            val px = x + cos(ang) * r * 0.32f
            val py = y + sin(ang) * r * 0.32f
            val petal = Path().apply { addCircle(px, py, pr, Path.Direction.CW) }
            glass(petal, px, py, pr, if (k % 2 == 0) ruby else blueL)
        }
        // the heart: amber, an eight-pointed star in it
        val heart = Path().apply { addCircle(x, y, r * 0.17f, Path.Direction.CW) }
        glass(heart, x, y, r * 0.17f, amber)
        val star = Path()
        for (i in 0 until 16) {
            val ang = i * tau / 16
            val rr = if (i % 2 == 0) r * 0.13f else r * 0.06f
            if (i == 0) star.moveTo(x + cos(ang) * rr, y + sin(ang) * rr) else star.lineTo(x + cos(ang) * rr, y + sin(ang) * rr)
        }
        star.close()
        glass(star, x, y, r * 0.13f, blueD)
        // the gilt rim
        q.style = Paint.Style.STROKE
        q.shader = LinearGradient(x - r, y - r, x + r, y + r, intArrayOf(0xff8a6424.toInt(), 0xfff2d48a.toInt(), 0xffa37a2e.toInt(), 0xfff6e2a8.toInt()), null, Shader.TileMode.MIRROR)
        q.strokeWidth = 1.4f * u
        c.drawCircle(x, y, r + 0.7f * u, q)
        q.strokeWidth = 0.14f * u
        c.drawCircle(x, y, r + 2f * u, q)
        // small cusps on the outer gilt ring
        for (k in 0 until 48) {
            val ang = k * tau / 48
            c.drawCircle(x + cos(ang) * (r + 2f * u), y + sin(ang) * (r + 2f * u), 0.35f * u, q)
        }
        q.shader = null
        q.style = Paint.Style.FILL
    }

    private fun blend(a0: Int, b0: Int, k: Float): Int {
        fun ch(sh: Int) = (((a0 shr sh) and 255) * (1 - k) + ((b0 shr sh) and 255) * k).toInt()
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ============================================================ 教堂 · an altar in glory, engraved

    private const val GRAPH = 0xff2b2925.toInt()
    private const val CHURCH_PAPER = 0xffebe7df.toInt()

    private fun gilt(x0: Float, y0: Float, x1: Float, y1: Float): Shader = LinearGradient(x0, y0, x1, y1,
        intArrayOf(0xff7a5418.toInt(), 0xfff2d78e.toInt(), 0xffb8862c.toInt(), 0xfffbe9b8.toInt(), 0xff8a5e1a.toInt(), 0xffd9ae55.toInt()),
        floatArrayOf(0f, 0.18f, 0.4f, 0.52f, 0.78f, 1f), Shader.TileMode.MIRROR)

    /**
     * The sheet as an engraving of a baroque altar: warm grey paper; a coffered half dome
     * drawn in hairline and shaded in stipple; gilt rays breaking out from behind the photo
     * (the one colour); stipple clouds banked round them that come apart into dust at their
     * edges; columns pencilled and stippled round, dissolving into dither toward the floor;
     * a stair climbing out of the altar into the light and stopping in mid air.
     */
    private fun churchUnder(c: Canvas, p: Plan, rnd: Random) {
        val u = p.u
        val P = p.photo
        val W = p.w.toFloat()
        val Hh = p.h.toFloat()
        val cx = P.centerX()
        c.drawColor(CHURCH_PAPER)

        // the half dome: coffers in hairline perspective, darker toward its rim
        val dy = 34f * u
        val dr = 46f * u
        val pen = hair(u, a(0.55f, GRAPH), 0.06f)
        val domeClip = Path().apply { addArc(RectF(cx - dr, dy - dr, cx + dr, dy + dr), 180f, 180f); close() }
        stipple(c, RectF(cx - dr, dy - dr, cx + dr, dy), 52000, 0.13f * u, a(0.8f, GRAPH), rnd, domeClip) { x, y ->
            val d = kotlin.math.hypot(x - cx, y - dy) / dr
            (d * d * 0.55f).coerceIn(0f, 0.6f)
        }
        for (row in 0..4) {
            val r = dr * (0.4f + row * 0.15f)
            c.drawArc(RectF(cx - r, dy - r, cx + r, dy + r), 180f, 180f, false, pen)
        }
        for (k in 0..18) {
            val ang = (180f + k * 10f) * PI.toFloat() / 180f
            c.drawLine(cx + cos(ang) * dr * 0.4f, dy + sin(ang) * dr * 0.4f, cx + cos(ang) * dr, dy + sin(ang) * dr, pen)
        }
        c.drawArc(RectF(cx - dr, dy - dr, cx + dr, dy + dr), 180f, 180f, false, hair(u, GRAPH, 0.18f))
        // the cornice under it, ruled
        for ((k, yy) in listOf(dy, dy + 0.8f * u, dy + 2.6f * u, dy + 3.2f * u).withIndex())
            pencil(c, 2f * u, yy, W - 2f * u, yy, hair(u, a(if (k % 2 == 0) 0.7f else 0.35f, GRAPH), 0.07f), rnd, 0.06f * u)

        // the columns, pencilled and stippled round, coming apart toward the floor
        val colTop = dy + 3.2f * u
        val colBottom = Hh - 14f * u
        for ((l, w) in listOf(4f to 7f, 13f to 5.5f, 100f - 11f to 7f, 100f - 18.5f to 5.5f))
            engravedColumn(c, l * u, colTop, (l + w) * u, colBottom, u, rnd)

        // the glory: graphite hairlines far out, gilt rays nearer, all from behind the photo
        val gx = cx
        val gy = P.centerY() - P.height() * 0.06f
        val inner = max(P.width(), P.height()) * 0.38f
        val fine = hair(u, a(0.4f, GRAPH), 0.05f)
        for (k in 0 until 220) {
            val ang = k / 220f * 2 * PI.toFloat()
            val len = (30f + rnd.nextFloat() * 40f) * u * (if (sin(ang) > 0.3f) 0.55f else 1f)
            c.drawLine(gx + cos(ang) * inner, gy + sin(ang) * inner, gx + cos(ang) * len, gy + sin(ang) * len, fine)
        }
        val rq = paint()
        for (k in 0 until 120) {
            val ang = (k + rnd.nextFloat() * 0.7f) / 120f * 2 * PI.toFloat()
            val up = (1f - sin(ang)) * 0.5f
            val len = (20f + 26f * up + rnd.nextFloat() * 16f) * u * (if (k % 6 == 0) 1.2f else 1f)
            val wid = (if (k % 6 == 0) 0.55f else 0.18f + rnd.nextFloat() * 0.2f) * u
            val x0 = gx + cos(ang) * inner * 0.8f
            val y0 = gy + sin(ang) * inner * 0.8f
            val x1 = gx + cos(ang) * len
            val y1 = gy + sin(ang) * len
            val nx = -sin(ang) * wid
            val ny = cos(ang) * wid
            rq.shader = LinearGradient(x0, y0, x1, y1, intArrayOf(0xfffbe9b8.toInt(), 0xffc8953a.toInt(), a(0f, 0x9a6a1e)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(Path().apply { moveTo(x0 - nx * 0.4f, y0 - ny * 0.4f); lineTo(x1 - nx, y1 - ny); lineTo(x1 + nx, y1 + ny); lineTo(x0 + nx * 0.4f, y0 + ny * 0.4f); close() }, rq)
        }

        // a stair out of the altar, climbing into the light and stopping in the air
        val stair = hair(u, a(0.75f, GRAPH), 0.08f)
        var sx = P.right - 4f * u
        var sy = P.bottom + 12f * u
        for (k in 0 until 9) {
            c.drawLine(sx, sy, sx + 2.4f * u, sy, stair)
            c.drawLine(sx + 2.4f * u, sy, sx + 2.4f * u, sy - 2f * u, stair)
            c.drawLine(sx + 1.2f * u, sy + 0.7f * u, sx + 3.6f * u, sy + 0.7f * u, Paint(stair).apply { alpha = 70 })
            sx += 2.4f * u; sy -= 2f * u
        }
        // a sun in stipple, low on the left, as if a second light had wandered in
        val ox = 15f * u
        val oy = P.bottom + 16f * u
        val orb = Path().apply { addCircle(ox, oy, 5f * u, Path.Direction.CW) }
        stipple(c, RectF(ox - 5f * u, oy - 5f * u, ox + 5f * u, oy + 5f * u), 9000, 0.14f * u, a(0.9f, GRAPH), rnd, orb) { x, y ->
            (((x - ox) + (y - oy)) / (10f * u) + 0.45f).coerceIn(0.03f, 0.85f)
        }
        c.drawCircle(ox, oy, 5.6f * u, hair(u, a(0.6f, 0xb8862c), 0.08f))

        // the altar: a halftone block that thins out at its ends, a gilt line along its top
        val altar = RectF(p.text).apply { inset(-4f * u, -2.4f * u) }
        val pitch = 0.62f * u
        val dot = paint().apply { color = GRAPH }
        var yy = altar.top
        var row = 0
        while (yy < altar.bottom) {
            var xx = altar.left + if (row % 2 == 1) pitch / 2 else 0f
            while (xx < altar.right) {
                val edge = min(xx - altar.left, altar.right - xx) / (6f * u)
                val r = pitch * 0.42f * sqrt(edge.coerceIn(0f, 1f)) * 0.28f
                if (r > 0.02f * u) c.drawCircle(xx, yy, r, dot)
                xx += pitch
            }
            yy += pitch * 0.866f; row++
        }
        c.drawRect(altar.left, altar.top - 0.5f * u, altar.right, altar.top, paint().apply { shader = gilt(altar.left, 0f, altar.right, 0f) })
        // small numbered stations on the floor, as on a plan
        val small = mono(0.85f * u, a(0.6f, GRAPH))
        for ((k, n) in listOf("I", "III", "VII", "XII").withIndex()) {
            val xx = (14f + k * 24f) * u
            val y2 = Hh - 5.5f * u
            c.drawCircle(xx, y2, 0.45f * u, hair(u, a(0.7f, GRAPH), 0.07f))
            c.drawText(n, xx + 1.2f * u, y2 + 0.35f * u, small)
        }
        c.drawText("Gloria", 9f * u, 8.6f * u, serif(2.6f * u, GRAPH))
        c.drawText("荣 耀 · 祭 坛", 9.2f * u, 11.2f * u, paint().apply { textSize = 1.1f * u; color = a(0.7f, GRAPH); letterSpacing = 0.4f })
    }

    /** A column drawn as an engraver would: contours in pencil, its roundness in stipple, its foot dissolving. */
    private fun engravedColumn(c: Canvas, l: Float, top: Float, r: Float, bottom: Float, u: Float, rnd: Random) {
        val w = r - l
        val capH = w * 1.2f
        val shaft = RectF(l + w * 0.08f, top + capH, r - w * 0.08f, bottom)
        val fade0 = bottom - (bottom - top) * 0.3f
        stipple(c, shaft, ((shaft.width() * shaft.height()) / (u * u) * 30).toInt().coerceAtMost(30000), 0.13f * u, a(0.85f, GRAPH), rnd) { x, y ->
            val across = (x - shaft.left) / shaft.width()
            val round = (kotlin.math.abs(across - 0.32f) * 1.3f).pow(1.4f) * 0.75f
            val fade = if (y < fade0) 1f else (1f - (y - fade0) / (bottom - fade0)).coerceIn(0f, 1f)
            round * fade
        }
        // dust falling off the foot
        stipple(c, RectF(shaft.left - w * 0.4f, fade0, shaft.right + w * 0.4f, bottom + 6f * u), 2500, 0.12f * u, a(0.7f, GRAPH), rnd) { _, y ->
            ((y - fade0) / (bottom + 6f * u - fade0)).coerceIn(0f, 1f) * 0.25f
        }
        val pen = hair(u, a(0.75f, GRAPH), 0.07f)
        pencil(c, shaft.left, shaft.top, shaft.left, fade0 + (bottom - fade0) * 0.3f, pen, rnd, 0.05f * u)
        pencil(c, shaft.right, shaft.top, shaft.right, fade0 + (bottom - fade0) * 0.3f, pen, rnd, 0.05f * u)
        // flutes
        val flute = hair(u, a(0.3f, GRAPH), 0.05f)
        for (k in 1..3) { val fx = shaft.left + shaft.width() * k / 4f; c.drawLine(fx, shaft.top + w * 0.2f, fx, fade0, flute) }
        // the capital: abacus, acanthus in pencil, volutes; the gilt only on its abacus edge
        c.drawRect(l - w * 0.1f, top, r + w * 0.1f, top + capH * 0.16f, pen)
        c.drawRect(l - w * 0.1f, top + capH * 0.16f - 0.3f * u, r + w * 0.1f, top + capH * 0.16f, paint().apply { shader = gilt(l, 0f, r, 0f) })
        for (row in 0..1) for (k in 0 until 4) {
            val lx = l + w * (0.14f + k * 0.24f)
            val ly = top + capH * (0.5f + row * 0.28f)
            val leaf = Path().apply {
                moveTo(lx, ly + capH * 0.22f)
                quadTo(lx - w * 0.15f, ly, lx + w * 0.04f, ly - capH * 0.18f)
                quadTo(lx + w * 0.18f, ly, lx + w * 0.06f, ly + capH * 0.22f)
            }
            c.drawPath(leaf, pen)
        }
        for (vx in floatArrayOf(l + w * 0.06f, r - w * 0.06f)) { c.drawCircle(vx, top + capH * 0.3f, w * 0.12f, pen); c.drawCircle(vx, top + capH * 0.3f, w * 0.05f, pen) }
    }

    /** A cloud in stipple: flattened puffs shaded from the upper left, their dots scattering away at the edge. */
    private fun stippleCloud(c: Canvas, x: Float, y: Float, s: Float, u: Float, rnd: Random) {
        repeat(14) {
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            val d = s * sqrt(rnd.nextFloat()) * 0.75f
            val px = x + cos(ang) * d * 1.6f
            val py = y + sin(ang) * d * 0.55f
            val r = s * (0.2f + rnd.nextFloat() * 0.22f)
            val box = RectF(px - r * 1.5f, py - r * 1.2f, px + r * 1.5f, py + r * 1.2f)
            stipple(c, box, (r * r / (u * u) * 700).toInt().coerceAtMost(24000), 0.12f * u, a(0.88f, GRAPH), rnd) { qx, qy ->
                val ex = (qx - px) / (r * 1.1f)
                val ey = (qy - py) / (r * 0.8f)
                val dd = sqrt(ex * ex + ey * ey)
                if (dd > 1.25f) 0f else {
                    // lit from the upper left: the lit side left as paper, the dots gathering in the shadow
                    val shade = ((ex + ey) * 0.55f + 0.12f).coerceIn(0f, 0.7f).pow(1.3f)
                    val edge = if (dd < 0.85f) 1f else (1.25f - dd) / 0.4f      // the edge comes apart
                    shade * edge
                }
            }
        }
    }

    private fun churchOver(c: Canvas, p: Plan, rnd: Random) {
        val u = p.u
        val P = p.photo
        c.drawRect(RectF(P).apply { inset(-0.7f * u, -0.7f * u) }, paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 0.35f * u; shader = gilt(P.left, P.top, P.right, P.bottom)
        })
        cropTicks(c, P, u, hair(u, a(0.8f, GRAPH), 0.07f), 2f, 2.4f)
    }

    // ============================================================ 晴空 · an anime sky

    private const val SKY_NAVY = 0xff1b2a4a.toInt()

    /**
     * Summer in an anime's establishing shot: a deep sky in cel bands, the sun's flare running
     * down it, a contrail, cumulonimbus lit warm on the sun's side, a hazy town on the horizon,
     * a utility pole and its wires across the sky with sparrows on them. The photo is a print
     * taped up on all that (the tape in [skyOver]), the words in a speech bubble.
     */
    private fun skyUnder(c: Canvas, p: Plan, rnd: Random, words: Boolean) {
        val u = p.u
        val P = p.photo
        val W = p.w.toFloat()
        val Hh = p.h.toFloat()
        // the sky: deep at the top, pale at the horizon, in soft cel bands
        c.drawRect(p.sheet, paint().apply {
            shader = LinearGradient(0f, 0f, 0f, Hh, intArrayOf(0xff1846c4.toInt(), 0xff2f74e2.toInt(), 0xff62a8f2.toInt(), 0xffa9dafb.toInt(), 0xffeaf7ff.toInt()),
                floatArrayOf(0f, 0.28f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
        })
        val band = paint()
        for (k in 0 until 4) {
            val y0 = Hh * (0.12f + k * 0.17f)
            band.color = a(0.05f + 0.015f * k, 0xffffff)
            c.drawRect(0f, y0, W, y0 + Hh * 0.09f, band)
        }
        // the sun beyond the top right: rays, a bloom
        val sx = W * 0.86f
        val sy = 7f * u
        val rayQ = paint()
        for (k in 0 until 16) {
            val ang = (92f + k * 8f + rnd.nextFloat() * 4f) * PI.toFloat() / 180f
            val sp = (1f + rnd.nextFloat() * 1.6f) * PI.toFloat() / 180f
            val far = 140f * u
            rayQ.shader = RadialGradient(sx, sy, far, intArrayOf(a(0.32f, 0xffffff), a(0f, 0xffffff)), null, Shader.TileMode.CLAMP)
            c.drawPath(Path().apply {
                moveTo(sx, sy); lineTo(sx + cos(ang - sp) * far, sy + sin(ang - sp) * far); lineTo(sx + cos(ang + sp) * far, sy + sin(ang + sp) * far); close()
            }, rayQ)
        }
        c.drawCircle(sx, sy, 26f * u, paint().apply {
            shader = RadialGradient(sx, sy, 26f * u, intArrayOf(a(1f, 0xffffff), a(0.8f, 0xfffbe8), a(0.25f, 0xfff4d0), a(0f, 0xfff4d0)), floatArrayOf(0f, 0.14f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        // the flare running down the sky: a halo with coloured edges, then ghosts
        val halo = paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.9f * u }
        for ((k, col) in listOf(0xff8a9e, 0xfff2a0, 0x9ef2ff).withIndex()) {
            halo.color = a(0.18f, col)
            c.drawCircle(sx, sy, (31f + k * 1.1f) * u, halo)
        }
        for ((k, f) in listOf(0.28f, 0.46f, 0.62f, 0.78f, 0.92f).withIndex()) {
            val gx = sx + (W * 0.12f - sx) * f
            val gy = sy + (Hh * 0.8f - sy) * f
            val gr = (1.4f + k * 1.1f) * u
            val hex = Path()
            for (i in 0 until 6) {
                val ang = i * PI.toFloat() / 3 + 0.3f
                if (i == 0) hex.moveTo(gx + cos(ang) * gr, gy + sin(ang) * gr) else hex.lineTo(gx + cos(ang) * gr, gy + sin(ang) * gr)
            }
            hex.close()
            c.drawPath(hex, paint().apply { color = a(0.14f, if (k % 2 == 0) 0xbff2ff else 0xffd6ec) })
            c.drawPath(hex, paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.15f * u; color = a(0.25f, 0xffffff) })
        }
        // a contrail, high, with the plane a glint at its head
        val cx0 = W * 0.02f; val cy0 = 30f * u; val cx1 = W * 0.62f; val cy1 = 17f * u
        c.drawLine(cx0, cy0, cx1, cy1, paint().apply {
            strokeWidth = 0.55f * u; strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(cx0, cy0, cx1, cy1, a(0f, 0xffffff), a(0.85f, 0xffffff), Shader.TileMode.CLAMP)
        })
        c.drawLine(cx0, cy0 + 0.9f * u, cx1, cy1 + 0.9f * u, paint().apply {
            strokeWidth = 0.35f * u; strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(cx0, cy0, cx1, cy1, a(0f, 0xffffff), a(0.55f, 0xffffff), Shader.TileMode.CLAMP)
        })
        spark(c, cx1 + 0.6f * u, cy1 - 0.1f * u, 0.9f * u, 0xffffffff.toInt())
        // high wisps
        val wisp = paint().apply { color = a(0.5f, 0xffffff) }
        for (k in 0 until 5) {
            val wy = (10f + k * 6f) * u
            val wx = rnd.nextFloat() * W * 0.6f
            c.drawOval(wx, wy, wx + (16f + rnd.nextFloat() * 22f) * u, wy + (0.7f + rnd.nextFloat()) * u, wisp)
        }
        // cumulonimbus, far: low behind the town, rising at the sides, lit warm toward the sun
        thunderhead(c, W * 0.1f, P.bottom + 7f * u, 17f * u, rnd, sx)
        thunderhead(c, W * 0.84f, P.bottom + 5f * u, 20f * u, rnd, sx)
        thunderhead(c, W * 0.45f, Hh - 4f * u, 16f * u, rnd, sx)
        // the town on the horizon, in haze: blocks, a tower with its light, windows catching the sun
        val ground = Hh - 9f * u
        val town = paint().apply { color = 0xffb7d3f0.toInt() }
        val lit = paint().apply { color = a(0.7f, 0xffffff) }
        var x = -2f * u
        while (x < W) {
            val bw = (3f + rnd.nextFloat() * 6f) * u
            val bh = (4f + rnd.nextFloat() * 10f) * u
            c.drawRect(x, ground - bh, x + bw, Hh, town)
            if (rnd.nextFloat() < 0.6f) {
                var wy = ground - bh + 1.2f * u
                while (wy < ground - 0.8f * u) { c.drawRect(x + 0.8f * u, wy, x + bw - 0.8f * u, wy + 0.25f * u, lit); wy += 1.6f * u }
            }
            x += bw + rnd.nextFloat() * 1.2f * u
        }
        val tx = W * 0.3f
        c.drawRect(tx - 0.35f * u, ground - 24f * u, tx + 0.35f * u, ground, paint().apply { color = 0xffa9c6e6.toInt() })
        c.drawLine(tx - 2.6f * u, ground - 12f * u, tx + 2.6f * u, ground - 12f * u, hair(u, 0xffa9c6e6.toInt(), 0.4f))
        c.drawCircle(tx, ground - 24.3f * u, 0.55f * u, paint().apply { color = 0xffff6a5c.toInt() })
        // a utility pole at the right, its wires across the sky, sparrows sitting on them
        val px = W * 0.9f
        val pole = paint().apply { color = 0xff20304e.toInt() }
        c.drawRect(px - 0.7f * u, 14f * u, px + 0.7f * u, Hh, pole)
        for ((k, ay) in listOf(18f, 23.5f).withIndex()) {
            val arm = (if (k == 0) 7f else 5.5f) * u
            c.drawRect(px - arm, ay * u, px + arm, ay * u + 0.7f * u, pole)
            for (ix in listOf(-0.85f, -0.35f, 0.35f, 0.85f)) {
                c.drawRoundRect(px + ix * arm - 0.35f * u, ay * u - 1.2f * u, px + ix * arm + 0.35f * u, ay * u, 0.2f * u, 0.2f * u, pole)
            }
        }
        c.drawRect(px - 2f * u, 30f * u, px + 2f * u, 33f * u, pole) // a transformer
        val wire = paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.28f * u; color = 0xff20304e.toInt(); strokeCap = Paint.Cap.ROUND }
        val wires = listOf(-0.85f to 18f, -0.35f to 18f, -0.85f to 23.5f, -0.35f to 23.5f)
        for ((k, wpair) in wires.withIndex()) {
            val (ix, ay) = wpair
            val arm = (if (ay < 20f) 7f else 5.5f) * u
            val wx0 = px + ix * arm; val wy0 = ay * u - 1.2f * u
            val sag = (8f + k * 1.5f) * u
            val endY = wy0 + (12f + k * 4f) * u
            val path = Path().apply { moveTo(wx0, wy0); quadTo(W * 0.42f, (wy0 + endY) / 2 + sag, -2f * u, endY) }
            c.drawPath(path, wire)
            if (k == 1) {
                // sparrows along this one
                val pm = PathMeasure(path, false)
                val pos = FloatArray(2)
                for (f in listOf(0.22f, 0.27f, 0.58f)) {
                    pm.getPosTan(pm.length * f, pos, null)
                    sparrow(c, pos[0], pos[1], 1.1f * u, pole)
                }
            }
        }
        // the photo: a print with a white edge and a soft shadow on the sky
        c.drawRect(P.left - 0.4f * u, P.top + 1f * u, P.right + 1.6f * u, P.bottom + 2.8f * u, paint().apply {
            color = a(0.28f, 0x0e1a3a); maskFilter = android.graphics.BlurMaskFilter(1.8f * u, android.graphics.BlurMaskFilter.Blur.NORMAL)
        })
        c.drawRect(RectF(P).apply { inset(-1.4f * u, -1.4f * u) }, paint().apply { color = 0xfffffffd.toInt() })
        // the speech bubble for the words (none without words): a halftone shadow, a double outline, its tail toward the photo
        if (!words) return
        val b = RectF(p.text).apply { inset(-2.8f * u, -2f * u) }
        val tail = Path().apply { moveTo(b.left + 8f * u, b.top + 0.3f * u); lineTo(b.left + 10.5f * u, b.top - 4f * u); lineTo(b.left + 13f * u, b.top + 0.3f * u); close() }
        val dots = paint().apply { color = a(0.35f, SKY_NAVY) }
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(b).apply { offset(1.1f * u, 1.1f * u) }, 3.4f * u, 3.4f * u, Path.Direction.CW) })
        var dy = b.top
        var row = 0
        while (dy < b.bottom + 2f * u) {
            var dx = b.left + (row % 2) * 0.6f * u
            while (dx < b.right + 2f * u) { c.drawCircle(dx, dy, 0.22f * u, dots); dx += 1.2f * u }
            dy += 1.05f * u; row++
        }
        c.restore()
        val bubble = Path().apply { addRoundRect(b, 3.4f * u, 3.4f * u, Path.Direction.CW); op(tail, Path.Op.UNION) }
        c.drawPath(bubble, paint().apply { color = 0xffffffff.toInt() })
        c.drawPath(bubble, hair(u, SKY_NAVY, 0.32f))
        c.drawRoundRect(RectF(b).apply { inset(0.8f * u, 0.8f * u) }, 2.6f * u, 2.6f * u, hair(u, a(0.35f, SKY_NAVY), 0.12f))
    }

    /** A sparrow perched, from the side: a round body, a head, a tail cocked. */
    private fun sparrow(c: Canvas, x: Float, y: Float, s: Float, q: Paint) {
        c.drawOval(x - s * 0.7f, y - s * 1.05f, x + s * 0.55f, y - s * 0.1f, q)
        c.drawCircle(x + s * 0.5f, y - s * 1.05f, s * 0.36f, q)
        c.drawPath(Path().apply { moveTo(x - s * 0.55f, y - s * 0.5f); lineTo(x - s * 1.25f, y - s * 0.95f); lineTo(x - s * 1.05f, y - s * 0.35f); close() }, q)
        c.drawPath(Path().apply { moveTo(x + s * 0.82f, y - s * 1.1f); lineTo(x + s * 1.15f, y - s * 0.98f); lineTo(x + s * 0.82f, y - s * 0.88f); close() }, q)
    }

    /**
     * A cumulonimbus as an anime paints it: puffs in three flat tones (cool shadow, body,
     * highlight), a warm rim on the side toward the sun at [sunX], crisp edges.
     */
    private fun thunderhead(c: Canvas, x: Float, y: Float, s: Float, rnd: Random, sunX: Float) {
        val puffs = (0 until 16).map {
            val ang = PI.toFloat() * (1f + rnd.nextFloat())
            val d = s * (0.15f + rnd.nextFloat() * 0.85f)
            Triple(x + cos(ang) * d * 1.25f, y + sin(ang) * d * 0.95f, s * (0.22f + rnd.nextFloat() * 0.3f))
        }
        val toward = if (sunX > x) 1f else -1f
        val deep = paint().apply { color = 0xffa9c0e2.toInt() }
        val shadow = paint().apply { color = 0xffcadbf2.toInt() }
        val body = paint().apply { color = 0xfff4f8ff.toInt() }
        val white = paint().apply { color = 0xffffffff.toInt() }
        val warm = paint().apply { color = 0xfffff1d8.toInt() }
        for ((px, py, r) in puffs) c.drawCircle(px, py + r * 0.3f, r * 1.02f, deep)
        for ((px, py, r) in puffs) c.drawCircle(px, py + r * 0.14f, r * 0.96f, shadow)
        for ((px, py, r) in puffs) c.drawCircle(px - r * 0.06f, py - r * 0.06f, r * 0.86f, body)
        for ((px, py, r) in puffs) c.drawCircle(px + toward * r * 0.22f, py - r * 0.24f, r * 0.52f, warm)
        for ((px, py, r) in puffs) c.drawCircle(px + toward * r * 0.12f, py - r * 0.3f, r * 0.42f, white)
    }

    private fun skyOver(c: Canvas, p: Plan, rnd: Random, date: String) {
        val u = p.u
        val P = p.photo
        val core = core(p)
        // the print taped up: washi tape across two corners, striped, its ends torn
        washi(c, P.left + 1.5f * u, P.top + 0.5f * u, 11f * u, 3.2f * u, -38f, 0xffff9fbd.toInt(), rnd)
        washi(c, P.right - 1.5f * u, P.top + 0.5f * u, 11f * u, 3.2f * u, 41f, 0xff8fd3ff.toInt(), rnd)
        washi(c, P.right - 2f * u, P.bottom - 0.2f * u, 9f * u, 3f * u, -34f, 0xffffe08a.toInt(), rnd)
        // a paper plane coming in from the left, its loop dashed behind it
        val planeX = P.left - 4f * u; val planeY = P.top - 5f * u
        if (planeY > 3f * u) {
            c.drawPath(Path().apply {
                moveTo(2f * u, planeY + 9f * u); cubicTo(8f * u, planeY + 14f * u, 3f * u, planeY + 2f * u, planeX - 3f * u, planeY + 1f * u)
            }, paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.25f * u; color = a(0.85f, 0xffffff); pathEffect = DashPathEffect(floatArrayOf(0.9f * u, 0.7f * u), 0f) })
            paperPlane(c, planeX, planeY, 3.6f * u, -12f, u)
        }
        // star stickers at the photo's corners
        sticker(c, P.left - 1.5f * u, P.bottom + 0.5f * u, 2.6f * u, -14f, 0xffffd23a.toInt(), u)
        sticker(c, P.right + 1.6f * u, P.top + 7f * u, 1.8f * u, 18f, 0xffff8fb4.toInt(), u)
        // petals adrift, some across the photo's edges, never its middle
        val petal = paint()
        repeat(26) {
            val x = rnd.nextFloat() * p.w
            val y = rnd.nextFloat() * p.h * 0.9f
            if (core.contains(x, y)) return@repeat
            val s = (0.7f + rnd.nextFloat() * 1.3f) * u
            c.save()
            c.translate(x, y)
            c.rotate(rnd.nextFloat() * 360f)
            c.scale(1f, 0.55f + rnd.nextFloat() * 0.45f)
            val path = Path().apply {
                moveTo(0f, -s); cubicTo(s * 0.9f, -s * 0.9f, s * 0.8f, s * 0.6f, 0f, s)
                cubicTo(-s * 0.8f, s * 0.6f, -s * 0.9f, -s * 0.9f, -s * 0.15f, -s * 0.92f)
                lineTo(0f, -s * 0.62f); close()
            }
            petal.shader = LinearGradient(0f, -s, 0f, s, 0xffffe6ee.toInt(), 0xffff9fbd.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(path, petal)
            c.restore()
        }
        // sparkles
        for (k in 0 until 11) {
            val x = rnd.nextFloat() * p.w
            val y = rnd.nextFloat() * p.h * 0.8f
            if (RectF(P).apply { inset(-1f * u, -1f * u) }.contains(x, y)) continue
            val r = (0.6f + rnd.nextFloat() * 1.3f) * u
            c.drawCircle(x, y, r * 2.4f, paint().apply { shader = RadialGradient(x, y, r * 2.4f, intArrayOf(a(0.6f, 0xffffff), 0), null, Shader.TileMode.CLAMP) })
            spark(c, x, y, r, 0xffffffff.toInt())
        }
        // the date on a little tag, a sun drawn beside it
        val tag = RectF(7f * u, 5.5f * u, 7f * u + 1.7f * u * 0.62f * date.length + 6.5f * u, 10.2f * u)
        c.drawRoundRect(tag, 2.3f * u, 2.3f * u, paint().apply { color = a(0.9f, 0xffffff) })
        c.drawRoundRect(tag, 2.3f * u, 2.3f * u, hair(u, SKY_NAVY, 0.2f))
        val sunC = tag.left + 2.4f * u
        c.drawCircle(sunC, tag.centerY(), 0.9f * u, paint().apply { color = 0xffffb13b.toInt() })
        for (k in 0 until 8) {
            val ang = k * PI.toFloat() / 4
            c.drawLine(sunC + cos(ang) * 1.3f * u, tag.centerY() + sin(ang) * 1.3f * u, sunC + cos(ang) * 1.75f * u, tag.centerY() + sin(ang) * 1.75f * u,
                hair(u, 0xffffb13b.toInt(), 0.18f))
        }
        c.drawText(date, tag.left + 4.6f * u, tag.centerY() + 0.6f * u, paint().apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 1.7f * u; color = SKY_NAVY; letterSpacing = 0.1f
        })
    }

    /** A strip of washi tape at (x, y), [len] long and [w] wide, turned [deg]: striped, torn at both ends. */
    private fun washi(c: Canvas, x: Float, y: Float, len: Float, w: Float, deg: Float, color: Int, rnd: Random) {
        c.save()
        c.translate(x, y); c.rotate(deg)
        val path = Path()
        path.moveTo(-len / 2, -w / 2)
        path.lineTo(len / 2, -w / 2)
        var yy = -w / 2
        while (yy < w / 2) { yy += w / 6; path.lineTo(len / 2 + (if (rnd.nextBoolean()) 0.35f else -0.35f) * w / 3, yy.coerceAtMost(w / 2)) }
        path.lineTo(-len / 2, w / 2)
        yy = w / 2
        while (yy > -w / 2) { yy -= w / 6; path.lineTo(-len / 2 + (if (rnd.nextBoolean()) 0.35f else -0.35f) * w / 3, yy.coerceAtLeast(-w / 2)) }
        path.close()
        c.drawPath(path, paint().apply { this.color = a(0.78f, color) })
        c.save(); c.clipPath(path)
        val stripe = paint().apply { this.color = a(0.45f, 0xffffff); strokeWidth = w * 0.16f }
        var sx = -len
        while (sx < len) { c.drawLine(sx, -w, sx + w * 1.2f, w, stripe); sx += w * 0.55f }
        c.restore()
        c.drawPath(path, paint().apply { style = Paint.Style.STROKE; strokeWidth = w * 0.03f; this.color = a(0.25f, 0x000000) })
        c.restore()
    }

    /** A paper plane, white with a navy line, nose toward the top right. */
    private fun paperPlane(c: Canvas, x: Float, y: Float, s: Float, deg: Float, u: Float) {
        c.save(); c.translate(x, y); c.rotate(deg)
        val top = Path().apply { moveTo(s, 0f); lineTo(-s, -s * 0.55f); lineTo(-s * 0.45f, 0f); close() }
        val under = Path().apply { moveTo(s, 0f); lineTo(-s * 0.45f, 0f); lineTo(-s * 0.8f, s * 0.42f); close() }
        c.drawPath(under, paint().apply { color = 0xffd9e6f8.toInt() })
        c.drawPath(top, paint().apply { color = 0xffffffff.toInt() })
        val ink = hair(u, SKY_NAVY, 0.2f)
        c.drawPath(top, ink); c.drawPath(under, ink)
        c.restore()
    }

    /** A star sticker: a white die-cut edge, the star in [color], a navy outline. */
    private fun sticker(c: Canvas, x: Float, y: Float, s: Float, deg: Float, color: Int, u: Float) {
        c.save(); c.translate(x, y); c.rotate(deg)
        fun star(r: Float) = Path().apply {
            for (i in 0 until 10) {
                val ang = (PI / 5 * i - PI / 2).toFloat()
                val rr = if (i % 2 == 0) r else r * 0.46f
                if (i == 0) moveTo(cos(ang) * rr, sin(ang) * rr) else lineTo(cos(ang) * rr, sin(ang) * rr)
            }
            close()
        }
        c.drawPath(star(s * 1.3f), paint().apply { this.color = a(0.25f, 0x0e1a3a); maskFilter = android.graphics.BlurMaskFilter(0.5f * u, android.graphics.BlurMaskFilter.Blur.NORMAL) })
        c.drawPath(star(s * 1.25f), paint().apply { this.color = 0xffffffff.toInt(); strokeJoin = Paint.Join.ROUND })
        c.drawPath(star(s), paint().apply { this.color = color })
        c.drawPath(star(s), hair(u, SKY_NAVY, 0.18f))
        c.drawCircle(-s * 0.22f, -s * 0.25f, s * 0.13f, paint().apply { this.color = a(0.8f, 0xffffff) })
        c.restore()
    }

    // ============================================================ 飞鸟 · ORBITAL LETTER

    private const val PAPER = 0xffeceae4.toInt()
    private const val GRAPHITE = 0xff3a3a3a.toInt()
    private const val BLUE = 0xff2338c8.toInt()

    private fun orbitUnder(c: Canvas, p: Plan, rnd: Random) {
        val u = p.u
        val P = p.photo
        c.drawColor(PAPER)
        // the black panel on the right, dissolving downward into dither
        val panel = RectF(66f * u, 0f, p.w.toFloat(), p.h * 0.52f)
        c.drawRect(panel.left, panel.top, panel.right, panel.bottom - 14f * u, paint().apply { color = 0xff111111.toInt() })
        stipple(c, RectF(panel.left, panel.bottom - 22f * u, panel.right, panel.bottom + 10f * u), 60000, 0.34f * u, 0xff111111.toInt(), rnd) { _, y ->
            (1f - (y - (panel.bottom - 22f * u)) / (32f * u)).coerceIn(0f, 1f).pow(1.6f)
        }
        // white dither inside the panel's lower edge
        stipple(c, RectF(panel.left, panel.bottom - 30f * u, panel.right, panel.bottom - 10f * u), 9000, 0.2f * u, a(0.8f, 0xffffff), rnd) { _, y ->
            ((y - (panel.bottom - 30f * u)) / (20f * u)).coerceIn(0f, 1f) * 0.6f
        }
        // gothic tracery in white hairline on the panel
        val chalk = hair(u, a(0.55f, 0xffffff), 0.08f)
        for (k in 0 until 3) lancet(c, panel.left + 4f * u + k * 9f * u, panel.top + 30f * u, 7f * u, 26f * u, chalk, rnd, false)
        c.drawCircle(panel.left + 22f * u, panel.top + 46f * u, 9f * u, chalk)
        c.drawCircle(panel.left + 22f * u, panel.top + 46f * u, 6f * u, chalk)

        // a cathedral elevation pencilled across the left and under the photo
        val pen = hair(u, a(0.38f, GRAPHITE), 0.08f)
        val ground = p.h * 0.76f
        for (k in 0 until 6) lancet(c, 4f * u + k * 8.5f * u, ground, 6f * u, (20f + (k % 3) * 6f) * u, pen, rnd, k % 2 == 0)
        pencil(c, 2f * u, ground, 60f * u, ground, pen, rnd, 0.1f * u)
        val sx = 14f * u
        pencil(c, sx - 4f * u, ground - 34f * u, sx, ground - 62f * u, pen, rnd, 0.1f * u)
        pencil(c, sx + 4f * u, ground - 34f * u, sx, ground - 62f * u, pen, rnd, 0.1f * u)
        pencil(c, sx, ground - 62f * u, sx, ground - 66f * u, pen, rnd, 0.05f * u)
        pencil(c, sx - 1f * u, ground - 64.5f * u, sx + 1f * u, ground - 64.5f * u, pen, rnd, 0.05f * u)
        // hatched perspective lines toward a point on the right
        for (k in 0 until 9) pencil(c, 0f, ground - k * 4f * u, 62f * u, ground - 22f * u - k * 1.2f * u, Paint(pen).apply { alpha = 40 }, rnd, 0.12f * u)

        // orbits: dotted and dashed ellipses round the eye, with tick marks
        val ex = P.right + 2f * u
        val ey = P.top + P.height() * 0.42f
        val orbitQ = hair(u, a(0.6f, GRAPHITE), 0.07f)
        orbitQ.pathEffect = DashPathEffect(floatArrayOf(0.15f * u, 0.9f * u), 0f)
        c.drawOval(RectF(ex - 46f * u, ey - 30f * u, ex + 46f * u, ey + 52f * u), orbitQ)
        orbitQ.pathEffect = DashPathEffect(floatArrayOf(2.2f * u, 1.1f * u), 0f)
        c.drawOval(RectF(ex - 62f * u, ey - 14f * u, ex + 40f * u, ey + 64f * u), orbitQ)
        orbitQ.pathEffect = null

        // the eye: a stippled cobalt disc, darker toward its lower right, a pale ring for a pupil
        val er = 15f * u
        val disc = Path().apply { addCircle(ex, ey, er, Path.Direction.CW) }
        c.drawCircle(ex, ey, er, paint().apply { color = BLUE })
        stipple(c, RectF(ex - er, ey - er, ex + er, ey + er), 26000, 0.22f * u, a(0.9f, 0xffffff), rnd, disc) { x, y ->
            val d = ((x - ex) * -0.7f + (y - ey) * -0.7f) / er
            (d * 0.9f - 0.1f).coerceIn(0f, 0.85f)
        }
        c.drawCircle(ex + 1f * u, ey + 1f * u, er, hair(u, a(0.45f, BLUE), 0.6f))
        c.drawCircle(ex, ey, 3.2f * u, paint().apply { color = PAPER })
        c.drawCircle(ex, ey, 1.6f * u, paint().apply { color = 0xff111111.toInt() })
        // a plumb line from the eye down to the ground, beaded
        val line = hair(u, BLUE, 0.12f)
        c.drawLine(ex, ey + 3.2f * u, ex, p.h * 0.86f, line)
        val bead = paint().apply { color = BLUE }
        for (k in 0..5) c.drawCircle(ex, ey + er + 6f * u + k * 6.5f * u, if (k == 5) 0.7f * u else 0.3f * u, bead)
        c.drawPath(Path().apply { val yy = p.h * 0.86f; moveTo(ex - 0.8f * u, yy - 1.4f * u); lineTo(ex, yy); lineTo(ex + 0.8f * u, yy - 1.4f * u); close() }, bead)
        // a pale moon on the panel
        c.drawCircle(panel.left + 12f * u, P.top + 2f * u, 2.8f * u, paint().apply { color = 0xffe6e2d6.toInt() })
        c.drawCircle(panel.left + 13.2f * u, P.top + 1.2f * u, 2.6f * u, paint().apply { color = 0xff111111.toInt() })

        // the stations along the lower orbit, named
        val ink = 0xff4a4a6a.toInt()
        station(c, 10f * u, P.bottom + 7f * u, "Moon", "月 相", u, ink, BLUE)
        station(c, 26f * u, P.bottom + 12f * u, "Stair", "阶 梯", u, ink, BLUE)
        station(c, 44f * u, P.bottom + 9f * u, "Letter", "来 信", u, BLUE, BLUE, 1.2f)
        station(c, 60f * u, P.bottom + 13f * u, "Loop", "回 环", u, ink, BLUE)
        // small numbered marks low on the sheet, like a star chart's
        val small = mono(0.85f * u, a(0.6f, GRAPHITE))
        for ((k, n) in listOf(9, 21, 37, 58, 64).withIndex()) {
            val x = (12f + k * 15f) * u
            val y = p.h - (9f + (k % 2) * 4f) * u
            c.drawCircle(x, y, (0.5f + (k % 3) * 0.6f) * u, hair(u, a(0.7f, GRAPHITE), 0.07f))
            c.drawText("$n", x + 1.6f * u, y + 0.4f * u, small)
        }
    }

    private fun orbitOver(c: Canvas, p: Plan, rnd: Random, date: String) {
        val u = p.u
        val P = p.photo
        // the photo's line and its ticks
        c.drawRect(P, hair(u, a(0.6f, GRAPHITE), 0.07f))
        cropTicks(c, P, u, hair(u, a(0.75f, GRAPHITE), 0.07f))
        // the flock: a stream of swallows from the left, over the photo's lower edge, into the eye
        val ex = P.right + 2f * u
        val ey = P.top + P.height() * 0.42f
        val stream = Path().apply {
            moveTo(-4f * u, P.bottom + 4f * u)
            cubicTo(P.left + P.width() * 0.3f, P.bottom + 9f * u, P.right - 10f * u, P.bottom + 2f * u, ex - 4f * u, ey + 6f * u)
        }
        val pm = PathMeasure(stream, false)
        val pos = FloatArray(2); val tan = FloatArray(2)
        val keepOff = core(p)
        val blue = paint().apply { color = BLUE }
        val white = paint().apply { color = 0xfff4f2ec.toInt() }
        val n = 34
        for (i in 0 until n) {
            val f = i / (n - 1f)
            pm.getPosTan(pm.length * f, pos, tan)
            val jitter = (rnd.nextFloat() - 0.5f) * 4f * u * (1f - f * 0.5f)
            val bx = pos[0] - tan[1] * jitter
            val by = pos[1] + tan[0] * jitter
            if (keepOff.contains(bx, by)) continue
            val heading = Math.toDegrees(kotlin.math.atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat() + (rnd.nextFloat() - 0.5f) * 18f
            val size = (0.8f + 3.4f * (1f - abs(f - 0.55f) * 1.4f).coerceAtLeast(0.2f)) * u
            swallow(c, bx, by, size, heading, if (P.contains(bx, by) || i % 5 == 0) white else blue)
        }
        // three large swallows peeling off above the eye
        for ((k, d) in listOf(Triple(ex - 18f * u, ey - 12f * u, -24f), Triple(ex - 30f * u, ey - 4f * u, -14f), Triple(ex + 6f * u, ey - 18f * u, 20f)).withIndex()) {
            if (keepOff.contains(d.first, d.second)) continue
            swallow(c, d.first, d.second, (4.5f - k) * u, d.third, if (k == 2) white else blue)
        }
        // the time, as the letter was dated
        c.drawText(date, 8f * u, 9.6f * u, serif(2.6f * u, 0xff2a2a2a.toInt(), italic = false))
        c.drawText("来信", 8f * u, 12.4f * u, mono(0.95f * u, a(0.7f, 0x2a2a2a)))
        c.drawLine(8f * u, 5.6f * u, 30f * u, 5.6f * u, hair(u, BLUE, 0.3f))
    }

    // ============================================================ 拟真 · OPTICAL

    private const val DRAFT = 0xfff0eee8.toInt()
    private const val INKB = 0xff1c1c1c.toInt()
    private const val SIGNAL = 0xffe8501d.toInt()

    private fun opticalUnder(c: Canvas, p: Plan, rnd: Random) {
        val u = p.u
        val P = p.photo
        c.drawColor(DRAFT)
        // a faint drafting grid
        val g = hair(u, a(0.06f, INKB), 0.05f)
        var x = 0f
        while (x < p.w) { c.drawLine(x, 0f, x, p.h.toFloat(), g); x += 2.5f * u }
        var y = 0f
        while (y < p.h) { c.drawLine(0f, y, p.w.toFloat(), y, g); y += 2.5f * u }

        // the lens in section below the photo
        val ay = P.bottom + (p.text.top - P.bottom) * 0.5f
        val half = min(8f * u, (p.text.top - P.bottom) * 0.36f)
        val axis = hair(u, a(0.8f, SIGNAL), 0.08f).apply { pathEffect = DashPathEffect(floatArrayOf(2.4f * u, 0.6f * u, 0.3f * u, 0.6f * u), 0f) }
        c.drawLine(4f * u, ay, 96f * u, ay, axis)
        val fx = 64f * u
        val ray = hair(u, a(0.75f, SIGNAL), 0.07f)
        for (k in -3..3) {
            if (k == 0) continue
            val h0 = half * k / 3.3f
            c.drawLine(4f * u, ay + h0, 14f * u, ay + h0, ray)
            c.drawLine(14f * u, ay + h0, 34f * u, ay + h0 * 0.8f, ray)
            c.drawLine(34f * u, ay + h0 * 0.8f, fx, ay, ray)
        }
        fun element(cx: Float, w: Float, bl: Float, br: Float) {
            val e = Path().apply {
                moveTo(cx - w, ay - half); quadTo(cx - w - bl, ay, cx - w, ay + half)
                lineTo(cx + w, ay + half); quadTo(cx + w + br, ay, cx + w, ay - half); close()
            }
            // the glass stippled, denser toward its edges
            val b = RectF(); e.computeBounds(b, true)
            stipple(c, b, 2600, 0.14f * u, a(0.8f, INKB), rnd, e) { _, yy -> (abs(yy - ay) / half).pow(2f) * 0.55f + 0.05f }
            c.drawPath(e, hair(u, INKB, 0.1f))
        }
        element(14f * u, 1f * u, 2.6f * u, 0.6f * u)
        element(17.3f * u, 0.6f * u, -0.4f * u, -1.5f * u)
        element(30f * u, 0.7f * u, -1.7f * u, 0.9f * u)
        element(34f * u, 1.1f * u, 1.9f * u, 1.9f * u)
        val stop = hair(u, INKB, 0.25f)
        c.drawLine(24f * u, ay - half * 1.2f, 24f * u, ay - half * 0.55f, stop)
        c.drawLine(24f * u, ay + half * 0.55f, 24f * u, ay + half * 1.2f, stop)
        c.drawCircle(fx, ay, 0.5f * u, paint().apply { color = SIGNAL })
        // leader lines to the parts' names
        val lead = hair(u, a(0.7f, INKB), 0.06f)
        c.drawLine(24f * u, ay - half * 1.2f, 24f * u, ay - half - 4f * u, lead)
        c.drawText("Stop", 24.6f * u, ay - half - 3.4f * u, serif(1.6f * u, INKB))
        c.drawText("光 阑", 24.6f * u, ay - half - 1.7f * u, paint().apply { textSize = 1f * u; color = a(0.7f, INKB); letterSpacing = 0.5f })
        c.drawLine(fx, ay, fx + 4f * u, ay - half - 2f * u, lead)
        c.drawText("Focal plane", fx + 4.6f * u, ay - half - 1.6f * u, serif(1.6f * u, INKB))
        c.drawText("焦 平 面", fx + 4.7f * u, ay - half + 0.1f * u, paint().apply { textSize = 1f * u; color = a(0.7f, INKB); letterSpacing = 0.5f })

        // the iris, lower right, in hairline with stippled blades
        val ix = p.w - 14f * u
        val iy = p.h - 18f * u
        iris(c, ix, iy, 20f * u, u, rnd)
        val speeds = listOf("B", "1", "2", "4", "8", "15", "30", "60", "125", "250", "500", "1000")
        val sp = mono(0.95f * u, INKB).apply { textAlign = Paint.Align.CENTER }
        for ((k, s) in speeds.withIndex()) {
            val ang = (196f + k * 7.4f) * PI.toFloat() / 180f
            val tx = ix + cos(ang) * 24.5f * u
            val ty = iy + sin(ang) * 24.5f * u
            sp.color = if (s == "60") SIGNAL else INKB
            c.save(); c.rotate(ang * 180f / PI.toFloat() + 90f, tx, ty); c.drawText(s, tx, ty, sp); c.restore()
            c.drawLine(ix + cos(ang) * 21.2f * u, iy + sin(ang) * 21.2f * u, ix + cos(ang) * 22.6f * u, iy + sin(ang) * 22.6f * u, hair(u, INKB, 0.07f))
        }
        c.drawText("Aperture", ix - 22f * u, iy - 24f * u, serif(1.6f * u, INKB))
        c.drawText("光 圈 · f/2.8", ix - 21.9f * u, iy - 22.3f * u, paint().apply { textSize = 1f * u; color = a(0.7f, INKB); letterSpacing = 0.3f })

        // header and registration marks
        c.drawText("OPTICAL DIAGRAM", 10f * u, 7.4f * u, serif(2.4f * u, INKB, italic = false))
        c.drawText("FIG. 04 — 光学结构图 · SHEET 1 OF 1", 10f * u, 9.8f * u, mono(0.9f * u, a(0.7f, INKB)))
        for (tx in floatArrayOf(4f * u, p.w - 4f * u)) {
            val t = hair(u, INKB, 0.07f)
            c.drawCircle(tx, 4f * u, 1.4f * u, t); c.drawCircle(tx, 4f * u, 0.6f * u, t)
            c.drawLine(tx - 2.2f * u, 4f * u, tx + 2.2f * u, 4f * u, t); c.drawLine(tx, 1.8f * u, tx, 6.2f * u, t)
        }
    }

    private fun iris(c: Canvas, x: Float, y: Float, r: Float, u: Float, rnd: Random) {
        val open = r * 0.34f
        val blades = 7
        val line = hair(u, INKB, 0.08f)
        for (i in 0 until blades) {
            val a0 = i * 2 * PI.toFloat() / blades
            val a1 = a0 + 2 * PI.toFloat() / blades
            val leaf = Path().apply {
                moveTo(x + cos(a0) * open, y + sin(a0) * open)
                lineTo(x + cos(a1) * open, y + sin(a1) * open)
                quadTo(x + cos(a1 + 0.5f) * r * 0.75f, y + sin(a1 + 0.5f) * r * 0.75f, x + cos(a1 + 0.9f) * r, y + sin(a1 + 0.9f) * r)
                lineTo(x + cos(a0 + 0.9f) * r, y + sin(a0 + 0.9f) * r)
                quadTo(x + cos(a0 + 0.4f) * r * 0.7f, y + sin(a0 + 0.4f) * r * 0.7f, x + cos(a0) * open, y + sin(a0) * open)
                close()
            }
            val b = RectF(); leaf.computeBounds(b, true)
            val shade = 0.12f + 0.08f * (i % 3)
            stipple(c, b, 5000, 0.13f * u, a(0.85f, INKB), rnd, leaf) { px, py -> shade + 0.25f * (hypot(px - x, py - y) / r) }
            c.drawPath(leaf, line)
        }
        c.drawCircle(x, y, r, hair(u, INKB, 0.3f))
        c.drawCircle(x, y, r + 1.2f * u, line)
        // the opening, and one signal-orange point at its heart
        c.drawCircle(x, y, 0.45f * u, paint().apply { color = SIGNAL })
    }

    private fun opticalOver(c: Canvas, p: Plan, frame: Int) {
        val u = p.u
        val P = p.photo
        c.drawRect(P, hair(u, a(0.8f, INKB), 0.06f))
        cropTicks(c, P, u, hair(u, INKB, 0.07f), 1.4f, 3f)
        // dimension line across the top of the photo
        val dim = hair(u, a(0.7f, INKB), 0.06f)
        val dy = P.top - 2.6f * u
        c.drawLine(P.left, dy, P.right, dy, dim)
        c.drawLine(P.left, dy - 0.8f * u, P.left, dy + 0.8f * u, dim)
        c.drawLine(P.right, dy - 0.8f * u, P.right, dy + 0.8f * u, dim)
        c.drawText("36 × 24 mm", P.centerX() - 4f * u, dy - 0.6f * u, mono(0.9f * u, a(0.8f, INKB)))
        if (frame > 0) c.drawText("№ $frame", p.w - 10f * u, 7.4f * u, serif(2.4f * u, SIGNAL, italic = false).apply { textAlign = Paint.Align.RIGHT })
    }

    // ============================================================ 胶卷 · CARTRIDGE

    private const val STUDIO = 0xffece9e3.toInt()

    /** A seamless backdrop: light from above, falling off to a soft floor; the photo a print lifted off it. */
    private fun studioUnder(c: Canvas, p: Plan) {
        val u = p.u
        c.drawRect(p.sheet, paint().apply {
            shader = LinearGradient(0f, 0f, 0f, p.h.toFloat(), intArrayOf(0xfff6f4ef.toInt(), STUDIO, 0xffd9d5cd.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        val P = p.photo
        val sh = paint().apply { color = a(0.28f, 0x000000); maskFilter = android.graphics.BlurMaskFilter(2.2f * u, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        c.drawRect(P.left + 0.4f * u, P.top + 1.4f * u, P.right + 0.4f * u, P.bottom + 2.2f * u, sh)
        val m = 1.6f * u
        c.drawRect(P.left - m, P.top - m, P.right + m, P.bottom + m, paint().apply { color = 0xfffbfaf7.toInt() })
    }

    /** [color] darker (k < 1) or lighter (k > 1), its alpha kept. */
    private fun shade(color: Int, k: Float): Int {
        fun ch(v: Int) = (v * k).toInt().coerceIn(0, 255)
        return (color and 0xff000000.toInt()) or (ch(color shr 16 and 0xff) shl 16) or (ch(color shr 8 and 0xff) shl 8) or ch(color and 0xff)
    }

    /** The cartridge the photo was shot on, standing on the floor, its film drawn out right across the sheet. */
    private fun studioOver(c: Canvas, p: Plan, film: String, frame: Int) {
        val u = p.u
        val H = p.h / u
        val name = film.ifBlank { "kodak portra 400" }
        val ch = 36f * u
        val base = (H - 12f) * u
        val look = org.photoall.film.ui.FilmLabel.of(name)
        val stripTop = base - ch * 0.66f
        val stripH = ch * 0.5f
        val sc0 = stripH / 35f
        // the cartridge straight on, its film drawn out a whole number of frames and cut between
        // two; the two together centred on the sheet, the same margin either side
        val bodyLeft = 0.25f * ch  // its axis to the shell's left
        val lips = 0.22f * ch      // its axis to where the film comes out (under the lips)
        val margin = 8f * u
        val room = p.w - 2 * margin - bodyLeft - lips
        val frames = ((room - 9f * sc0 - 4f * sc0 + 2f * sc0) / (38f * sc0)).toInt().coerceAtLeast(1)
        val stripLen = 9f * sc0 + frames * 38f * sc0 - 2f * sc0 + 4f * sc0
        val cx = (p.w - (bodyLeft + lips + stripLen)) / 2f + bodyLeft
        val strip = RectF(cx + lips, stripTop, cx + lips + stripLen, stripTop + stripH)
        val q = paint()
        q.color = a(0.22f, 0x000000)
        q.maskFilter = android.graphics.BlurMaskFilter(1.2f * u, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawRect(strip.left, strip.top + 1.2f * u, strip.right + 0.6f * u, strip.bottom + 2.4f * u, q)
        q.maskFilter = null
        q.color = 0xff000000.toInt() // opaque: a shader is drawn at the paint's alpha
        q.shader = LinearGradient(0f, strip.top, 0f, strip.bottom, intArrayOf(0xff8a4f22.toInt(), 0xff6e3a16.toInt(), 0xff52290e.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(strip, 0.8f * sc0, 0.8f * sc0, q)
        q.shader = null
        val sc = stripH / 35f // the strip's mm in pixels
        // the frames: 36 x 24 mm, 38 mm apart (8 perforations), the base clear between them; the
        // first one a little way out of the lips, as a developed negative is cut
        val pitch = 38f * sc
        val first = strip.left + 9f * sc
        val fTop = strip.top + 5.5f * sc
        val fBottom = strip.bottom - 5.5f * sc
        var fx = first
        var n = if (frame > 0) frame else 1
        val dense = paint().apply { color = 0xff000000.toInt() }
        while (fx + 36f * sc <= strip.right) {
            val r = RectF(fx, fTop, fx + 36f * sc, fBottom)
            // an exposed frame: denser than the base, its density uneven as a scene's is
            val k = 0.85f + 0.3f * ((n * 7919 % 13) / 13f)
            dense.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(shade(0xff4a2410.toInt(), k), shade(0xff3a1b0a.toInt(), k * 0.92f), shade(0xff52290e.toInt(), k)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 0.6f * sc, 0.6f * sc, dense)
            dense.shader = null
            // the frame line: a hair of clear base at each end
            q.color = a(0.35f, 0xd99a5c)
            c.drawRect(r.left - 0.25f * sc, fTop, r.left, fBottom, q)
            c.drawRect(r.right, fTop, r.right + 0.25f * sc, fBottom, q)
            fx += pitch; n++
        }
        // the perforations, in step with the frames (8 to a frame)
        q.color = STUDIO
        var x = first - 0.4f * sc - pitch
        while (x + 1.98f * sc < strip.right - 0.5f * sc) {
            if (x > strip.left + 0.3f * sc)
                for (y in floatArrayOf(strip.top + 2.01f * sc, strip.bottom - 2.01f * sc - 2.794f * sc))
                    c.drawRoundRect(x, y, x + 1.98f * sc, y + 2.794f * sc, 0.5f * sc, 0.5f * sc, q)
            x += 4.75f * sc
        }
        // the edge printing: the stock along the top, each frame's number and its A at the foot
        val edge = paint().apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 1.7f * sc; color = a(0.75f, 0xf2c98a)
            letterSpacing = 0.05f
        }
        val label = "${look.maker} ${look.title} ${look.iso}".trim()
        fx = first
        n = if (frame > 0) frame else 1
        while (fx + 36f * sc <= strip.right) {
            c.drawText(label, fx + 2f * sc, strip.top + 1.55f * sc, edge)
            c.drawText("$n", fx + 17f * sc, strip.bottom - 0.25f * sc, edge)
            c.drawText("${n}A", fx + 36f * sc - 1f * sc, strip.bottom - 0.25f * sc, edge.apply { textAlign = Paint.Align.CENTER })
            edge.textAlign = Paint.Align.LEFT
            c.drawText("\u25B6", fx + 26f * sc, strip.bottom - 0.25f * sc, edge)
            fx += pitch; n++
        }
        org.photoall.film.ui.CartridgeSprites.drawWaiting(c, cx, base, ch, name, yaw = 0f, leader = 0f)
        c.drawText(org.photoall.film.ui.FilmLabel.pretty(name), 8f * u, (H - 4.6f) * u, serif(1.9f * u, 0xff2a2724.toInt()))
        c.drawText("135 · 36 EXP · ISO ${look.iso} · 拍摄所用胶卷", 8f * u, (H - 2.4f) * u, mono(0.85f * u, a(0.65f, 0x2a2724)))
        if (frame > 0) c.drawText("№ $frame", p.w - 8f * u, (H - 4.6f) * u, serif(1.9f * u, 0xff2a2724.toInt(), italic = false).apply { textAlign = Paint.Align.RIGHT })
    }

}
