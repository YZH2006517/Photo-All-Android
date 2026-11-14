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

    fun plan(t: Theme, pw: Int, ph: Int, maxLong: Int): Plan {
        val h = min(maxLong, (max(pw, ph) * 1.45f).toInt()).coerceAtLeast(800)
        val w = (h * 0.8f).toInt()
        val u = w / 100f
        val H = h / u
        val (region, ax, ay) = when (t) {
            Theme.SACRED -> Triple(RectF(25f, 47f, 75f, H * 0.7f), 0.5f, 0.5f)
            Theme.FATE -> Triple(RectF(8f, 16f, 70f, H * 0.66f), 0f, 0.3f)
            Theme.ANIME -> Triple(RectF(10f, 14f, 90f, H * 0.64f), 0.5f, 0.25f)
            Theme.CAMERA -> Triple(RectF(10f, 12f, 90f, H * 0.58f), 0.5f, 0f)
            Theme.FILM -> Triple(RectF(9f, 8f, 91f, H * 0.53f), 0.5f, 0f)
        }
        val k = min(region.width() / pw, region.height() / ph)
        val pwU = pw * k
        val phU = ph * k
        val left = region.left + (region.width() - pwU) * ax
        val top = region.top + (region.height() - phU) * ay
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

    fun drawUnder(c: Canvas, t: Theme, p: Plan, seed: Int) {
        val rnd = Random(seed * 7349 + t.ordinal)
        when (t) {
            Theme.SACRED -> churchUnder(c, p, rnd)
            Theme.FATE -> orbitUnder(c, p, rnd)
            Theme.ANIME -> skyUnder(c, p, rnd)
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

    private fun skyUnder(c: Canvas, p: Plan, rnd: Random) {
        val u = p.u
        val P = p.photo
        val W = p.w.toFloat()
        val Hh = p.h.toFloat()
        c.drawRect(p.sheet, paint().apply {
            shader = LinearGradient(0f, 0f, 0f, Hh, intArrayOf(0xff2a5fd4.toInt(), 0xff4f97ec.toInt(), 0xff9fd3fb.toInt(), 0xffe6f5ff.toInt()),
                floatArrayOf(0f, 0.35f, 0.72f, 1f), Shader.TileMode.CLAMP)
        })
        // the sun beyond the top right, its flare: rays, a bloom, ghosts down across the sky
        val sx = W * 0.86f
        val sy = 7f * u
        val rayQ = paint()
        for (k in 0 until 14) {
            val ang = (95f + k * 9f + rnd.nextFloat() * 4f) * PI.toFloat() / 180f
            val sp = (1.2f + rnd.nextFloat() * 1.6f) * PI.toFloat() / 180f
            val far = 130f * u
            rayQ.shader = RadialGradient(sx, sy, far, intArrayOf(a(0.35f, 0xffffff), a(0f, 0xffffff)), null, Shader.TileMode.CLAMP)
            c.drawPath(Path().apply {
                moveTo(sx, sy); lineTo(sx + cos(ang - sp) * far, sy + sin(ang - sp) * far); lineTo(sx + cos(ang + sp) * far, sy + sin(ang + sp) * far); close()
            }, rayQ)
        }
        c.drawCircle(sx, sy, 22f * u, paint().apply {
            shader = RadialGradient(sx, sy, 22f * u, intArrayOf(a(1f, 0xffffff), a(0.75f, 0xfffbe8), a(0f, 0xfff4d0)), floatArrayOf(0f, 0.18f, 1f), Shader.TileMode.CLAMP)
        })
        for ((k, f) in listOf(0.32f, 0.55f, 0.7f, 0.9f).withIndex()) {
            val gx = sx + (W * 0.2f - sx) * f
            val gy = sy + (Hh * 0.75f - sy) * f
            val gr = (1.6f + k * 1.2f) * u
            val hex = Path()
            for (i in 0 until 6) {
                val ang = i * PI.toFloat() / 3
                if (i == 0) hex.moveTo(gx + cos(ang) * gr, gy + sin(ang) * gr) else hex.lineTo(gx + cos(ang) * gr, gy + sin(ang) * gr)
            }
            hex.close()
            c.drawPath(hex, paint().apply { color = a(0.16f, if (k % 2 == 0) 0xbff2ff else 0xffd6ec) })
        }
        // high wisps
        val wisp = paint().apply { color = a(0.55f, 0xffffff) }
        for (k in 0 until 6) {
            val wy = (12f + k * 5.5f) * u
            val wx = rnd.nextFloat() * W * 0.7f
            c.drawOval(wx, wy, wx + (18f + rnd.nextFloat() * 22f) * u, wy + (0.8f + rnd.nextFloat()) * u, wisp)
        }
        // a few birds far off
        val far = paint().apply { color = a(0.55f, SKY_NAVY) }
        for ((bx, by, s) in listOf(Triple(W * 0.2f, 11f * u, 1f * u), Triple(W * 0.26f, 13.5f * u, 0.7f * u), Triple(W * 0.31f, 10f * u, 0.55f * u)))
            swallow(c, bx, by, s, 8f, far)
        // towering cumulus behind the photo's foot and along the bottom, cel-shaded
        cumulus(c, W * 0.18f, P.bottom + 6f * u, 22f * u, rnd)
        cumulus(c, W * 0.78f, P.bottom + 2f * u, 26f * u, rnd)
        cumulus(c, W * 0.45f, Hh + 2f * u, 34f * u, rnd)
        cumulus(c, W * 0.05f, Hh - 4f * u, 20f * u, rnd)
        cumulus(c, W * 0.98f, Hh - 8f * u, 24f * u, rnd)
        // the photo: a print with a white edge and a soft shadow on the sky
        c.drawRect(P.left - 0.6f * u, P.top + 0.8f * u, P.right + 1.4f * u, P.bottom + 2.4f * u, paint().apply {
            color = a(0.25f, 0x1b2a4a); maskFilter = android.graphics.BlurMaskFilter(1.6f * u, android.graphics.BlurMaskFilter.Blur.NORMAL)
        })
        c.drawRect(RectF(P).apply { inset(-1.2f * u, -1.2f * u) }, paint().apply { color = 0xffffffff.toInt() })
        // the speech bubble for the words, its tail toward the photo
        val b = RectF(p.text).apply { inset(-2.6f * u, -1.8f * u) }
        val bubble = Path().apply {
            addRoundRect(b, 3f * u, 3f * u, Path.Direction.CW)
            moveTo(b.left + 8f * u, b.top + 0.2f * u); lineTo(b.left + 10f * u, b.top - 3.6f * u); lineTo(b.left + 12.5f * u, b.top + 0.2f * u); close()
        }
        c.drawPath(bubble, paint().apply { color = 0xffffffff.toInt() })
        c.drawRoundRect(b, 3f * u, 3f * u, hair(u, SKY_NAVY, 0.28f))
        c.drawLine(b.left + 8.2f * u, b.top, b.left + 10f * u, b.top - 3.6f * u, hair(u, SKY_NAVY, 0.28f))
        c.drawLine(b.left + 12.3f * u, b.top, b.left + 10f * u, b.top - 3.6f * u, hair(u, SKY_NAVY, 0.28f))
    }

    /** A cumulus heap: white puffs lit from above, their undersides in a cool shadow, crisp edged as a cel. */
    private fun cumulus(c: Canvas, x: Float, y: Float, s: Float, rnd: Random) {
        val puffs = (0 until 11).map {
            val ang = PI.toFloat() * (1f + rnd.nextFloat())
            val d = s * (0.2f + rnd.nextFloat() * 0.75f)
            Triple(x + cos(ang) * d * 1.3f, y + sin(ang) * d * 0.7f, s * (0.28f + rnd.nextFloat() * 0.3f))
        }
        val shadow = paint().apply { color = 0xffc4d6ef.toInt() }
        val body = paint().apply { color = 0xfffdfeff.toInt() }
        val rim = paint().apply { color = 0xffe7f0fb.toInt() }
        for ((px, py, r) in puffs) c.drawCircle(px, py + r * 0.22f, r, shadow)
        for ((px, py, r) in puffs) c.drawCircle(px, py, r * 0.92f, rim)
        for ((px, py, r) in puffs) c.drawCircle(px - r * 0.12f, py - r * 0.16f, r * 0.8f, body)
    }

    private fun skyOver(c: Canvas, p: Plan, rnd: Random, date: String) {
        val u = p.u
        val P = p.photo
        val core = core(p)
        // petals adrift, some across the photo's edges, never its middle
        val petal = paint()
        repeat(34) {
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
            petal.shader = LinearGradient(0f, -s, 0f, s, 0xffffe0ea.toInt(), 0xffff9fbd.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(path, petal)
            c.restore()
        }
        // sparkles
        for (k in 0 until 9) {
            val x = rnd.nextFloat() * p.w
            val y = rnd.nextFloat() * p.h * 0.8f
            if (RectF(P).apply { inset(-1f * u, -1f * u) }.contains(x, y)) continue
            val r = (0.6f + rnd.nextFloat() * 1.2f) * u
            c.drawCircle(x, y, r * 2.2f, paint().apply { shader = RadialGradient(x, y, r * 2.2f, intArrayOf(a(0.6f, 0xffffff), 0), null, Shader.TileMode.CLAMP) })
            spark(c, x, y, r, 0xffffffff.toInt())
        }
        // the date, small, as written on the back of a print
        c.drawText(date, 9f * u, 9f * u, paint().apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 1.7f * u; color = 0xffffffff.toInt(); letterSpacing = 0.12f
        })
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
