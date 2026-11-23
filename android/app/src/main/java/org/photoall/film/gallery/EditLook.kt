package org.photoall.film.gallery

import org.photoall.film.gallery.EditActivity.Adjust as A
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Everything an edit does to tone and colour (the crop and turns are kept apart): the 调整
 * sliders, the tone curves (曲线), the colour mixer (混色: eight bands by hue, saturation,
 * luminance) and colour grading (分级: shadow, midtone and highlight wheels, blending, balance).
 */
class Look(
    /** The 调整 values, in [EditActivity.Adjust] order. */
    val v: FloatArray = FloatArray(EditActivity.Adjust.entries.size),
    /** Tone curves: 0 RGB, 1 red, 2 green, 3 blue; each x0, y0, x1, y1… in 0..1, x rising. */
    val curves: Array<FloatArray> = Array(4) { IDENTITY.copyOf() },
    /** The mixer: per band (hue, saturation, luminance), each -100..100. */
    val hsl: FloatArray = FloatArray(BANDS.size * 3),
    /** Grading: shadows, midtones, highlights × (hue 0..360, amount 0..100, luminance -100..100), blending, balance. */
    val wheels: FloatArray = WHEELS_DEFAULT.copyOf(),
) {
    fun copy() = Look(v.copyOf(), Array(4) { curves[it].copyOf() }, hsl.copyOf(), wheels.copyOf())

    fun curveEdited(i: Int) = !curves[i].contentEquals(IDENTITY)
    val curvesEdited get() = (0..3).any { curveEdited(it) }
    val hslEdited get() = hsl.any { it != 0f }
    fun bandEdited(b: Int) = hsl[b * 3] != 0f || hsl[b * 3 + 1] != 0f || hsl[b * 3 + 2] != 0f
    val wheelsEdited get() = !wheels.contentEquals(WHEELS_DEFAULT)
    val edited get() = v.any { it != 0f } || curvesEdited || hslEdited || wheelsEdited

    fun resetCurves() { for (i in 0..3) curves[i] = IDENTITY.copyOf() }
    fun resetAll() {
        v.fill(0f); resetCurves(); hsl.fill(0f)
        WHEELS_DEFAULT.copyInto(wheels)
    }

    /** One band of the mixer: its name, the hue it is centred on, the colour shown for it. */
    class Band(val label: String, val hue: Float)

    companion object {
        val IDENTITY = floatArrayOf(0f, 0f, 1f, 1f)
        val BANDS = listOf(Band("红", 0f), Band("橙", 30f), Band("黄", 58f), Band("绿", 115f),
            Band("青", 180f), Band("蓝", 225f), Band("紫", 275f), Band("洋红", 320f))
        val ZONES = listOf("阴影", "中间调", "高光")
        const val BLEND = 9
        const val BALANCE = 10
        val WHEELS_DEFAULT = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 50f, 0f)
    }
}

/**
 * A tone curve through its points: monotone cubic (Fritsch–Carlson), so it never overshoots
 * between two points; flat beyond the first and the last.
 */
class Spline(private val p: FloatArray) {
    private val n = p.size / 2
    private val m = FloatArray(n)

    init {
        if (n >= 2) {
            val d = FloatArray(n - 1) { k -> (y(k + 1) - y(k)) / max(1e-6f, x(k + 1) - x(k)) }
            m[0] = d[0]; m[n - 1] = d[n - 2]
            for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
            for (k in 0 until n - 1) {
                if (d[k] == 0f) { m[k] = 0f; m[k + 1] = 0f; continue }
                val a = m[k] / d[k]; val b = m[k + 1] / d[k]
                val s = a * a + b * b
                if (s > 9f) { val t = 3f / sqrt(s); m[k] = t * a * d[k]; m[k + 1] = t * b * d[k] }
            }
        }
    }

    private fun x(k: Int) = p[2 * k]
    private fun y(k: Int) = p[2 * k + 1]

    fun at(xv: Float): Float {
        if (n < 2) return xv
        if (xv <= x(0)) return y(0)
        if (xv >= x(n - 1)) return y(n - 1)
        var k = 0
        while (k < n - 2 && xv > x(k + 1)) k++
        val h = x(k + 1) - x(k)
        val t = (xv - x(k)) / h
        val t2 = t * t; val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * y(k) + (t3 - 2 * t2 + t) * h * m[k] +
            (-2 * t3 + 3 * t2) * y(k + 1) + (t3 - t2) * h * m[k + 1]
    }

    /** The curve as a table of 8-bit values. */
    fun table(): IntArray = IntArray(256) { i -> (at(i / 255f) * 255f).roundToInt().coerceIn(0, 255) }
}

/**
 * Tone and colour, as three tables (one a channel: white balance, exposure, the tone sliders,
 * fade, then the curves) and, when anything acts on colour, a 33³ colour cube after them
 * (saturation, vibrance, the mixer, grading). Vignette is drawn apart, over the crop.
 */
class Grade(look: Look) {
    private val lr = IntArray(256)
    private val lg = IntArray(256)
    private val lb = IntArray(256)
    private val cube: FloatArray?
    val identity: Boolean

    init {
        val v = look.v
        identity = A.entries.all { it == A.VIGNETTE || v[it.ordinal] == 0f } &&
            !look.curvesEdited && !look.hslEdited && !look.wheelsEdited

        val ev = v[A.EXPOSURE.ordinal] / 100f * 2f
        val warm = v[A.WARMTH.ordinal] / 100f
        val tint = v[A.TINT.ordinal] / 100f
        var gr = 1f + 0.16f * warm + 0.05f * tint
        var gg = 1f - 0.10f * tint
        var gb = 1f - 0.16f * warm + 0.05f * tint
        val norm = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb
        gr /= norm; gg /= norm; gb /= norm
        val k = 2f.pow(ev)
        val roll = ((k * max(gr, max(gg, gb)) - 1f) * 2f).coerceIn(0f, 1f)
        val con = v[A.CONTRAST.ordinal] / 100f
        val hi = v[A.HIGHLIGHTS.ordinal] / 100f
        val sh = v[A.SHADOWS.ordinal] / 100f
        val wh = v[A.WHITES.ordinal] / 100f
        val bl = v[A.BLACKS.ordinal] / 100f
        val fade = v[A.FADE.ordinal] / 100f
        val p = if (con >= 0) 1f + con * 1.3f else 1f / (1f - con * 1.3f)
        fun curve(lin: Float): Int {
            var y = lin
            if (roll > 0f && y > 0.6f) { // highlights roll off instead of clipping when brightened
                val soft = 0.6f + 0.4f * (1f - exp(-(y - 0.6f) / 0.4f))
                y += (soft - y) * roll
            }
            var e = toSrgb(y.coerceIn(0f, 1f))
            e += sh * 0.45f * 2.25f * e * (1 - e) * (1 - e)
            e += hi * 0.45f * 2.25f * e * e * (1 - e)
            e += wh * 0.15f * e * e * e
            e += bl * 0.12f * (1 - e) * (1 - e) * (1 - e)
            e = e.coerceIn(0f, 1f)
            if (con != 0f) e = if (e < 0.5f) 0.5f * (2 * e).pow(p) else 1 - 0.5f * (2 * (1 - e)).pow(p)
            if (fade != 0f) e = fade * 0.16f + e * (1 - fade * 0.21f)
            return (e * 255f).roundToInt().coerceIn(0, 255)
        }
        for (i in 0..255) {
            val lin = toLin(i / 255f)
            lr[i] = curve(lin * gr * k)
            lg[i] = curve(lin * gg * k)
            lb[i] = curve(lin * gb * k)
        }
        // the curves: the RGB one on every channel, then each channel's own
        if (look.curvesEdited) {
            val master = Spline(look.curves[0]).table()
            val cr = Spline(look.curves[1]).table()
            val cg = Spline(look.curves[2]).table()
            val cb = Spline(look.curves[3]).table()
            for (i in 0..255) {
                lr[i] = cr[master[lr[i]]]
                lg[i] = cg[master[lg[i]]]
                lb[i] = cb[master[lb[i]]]
            }
        }
        val sat = 1f + v[A.SATURATION.ordinal] / 100f
        val vib = v[A.VIBRANCE.ordinal] / 100f
        cube = if (sat != 1f || vib != 0f || look.hslEdited || look.wheelsEdited)
            Colour(sat, vib, look.hsl, look.wheels).cube() else null
    }

    fun apply(px: IntArray, from: Int, to: Int) {
        val cube = cube
        val n = N
        val n2 = N * N
        val kq = (N - 1) / 255f
        for (i in from until to) {
            val c = px[i]
            var r = lr[(c shr 16) and 255]
            var g = lg[(c shr 8) and 255]
            var b = lb[c and 255]
            if (cube != null) {
                // trilinear in the cube
                val fr = r * kq; val fg = g * kq; val fb = b * kq
                val ir = min(fr.toInt(), n - 2); val ig = min(fg.toInt(), n - 2); val ib = min(fb.toInt(), n - 2)
                val dr = fr - ir; val dg = fg - ig; val db = fb - ib
                val i000 = (ib * n2 + ig * n + ir) * 3
                val i100 = i000 + 3
                val i010 = i000 + n * 3
                val i110 = i010 + 3
                val i001 = i000 + n2 * 3
                val i101 = i001 + 3
                val i011 = i001 + n * 3
                val i111 = i011 + 3
                val w000 = (1 - dr) * (1 - dg) * (1 - db); val w100 = dr * (1 - dg) * (1 - db)
                val w010 = (1 - dr) * dg * (1 - db); val w110 = dr * dg * (1 - db)
                val w001 = (1 - dr) * (1 - dg) * db; val w101 = dr * (1 - dg) * db
                val w011 = (1 - dr) * dg * db; val w111 = dr * dg * db
                fun at(o: Int) = cube[i000 + o] * w000 + cube[i100 + o] * w100 + cube[i010 + o] * w010 + cube[i110 + o] * w110 +
                    cube[i001 + o] * w001 + cube[i101 + o] * w101 + cube[i011 + o] * w011 + cube[i111 + o] * w111
                r = (at(0) * 255f + 0.5f).toInt().coerceIn(0, 255)
                g = (at(1) * 255f + 0.5f).toInt().coerceIn(0, 255)
                b = (at(2) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            px[i] = (c and 0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    /** What acts on colour, worked out once a point of the cube. Values are sRGB-encoded 0..1. */
    private class Colour(val sat: Float, val vib: Float, val hsl: FloatArray, val wheels: FloatArray) {
        private val mixer = hsl.any { it != 0f }
        private val grading = !wheels.contentEquals(Look.WHEELS_DEFAULT)
        private val centres = Look.BANDS.map { it.hue }
        // each zone's push: a colour of no luminance of its own, and a lift or fall
        private val push = Array(3) { z ->
            val hc = hueRgb(wheels[z * 3])
            val y = luma(hc[0], hc[1], hc[2])
            val a = wheels[z * 3 + 1] / 100f * 0.22f
            floatArrayOf((hc[0] - y) * a, (hc[1] - y) * a, (hc[2] - y) * a, wheels[z * 3 + 2] / 100f * 0.12f)
        }
        private val mid = 0.5f - 0.22f * wheels[Look.BALANCE] / 100f
        private val width = 0.12f + 0.33f * wheels[Look.BLEND] / 100f

        fun cube(): FloatArray {
            val out = FloatArray(N * N * N * 3)
            val rgb = FloatArray(3)
            var o = 0
            for (b in 0 until N) for (g in 0 until N) for (r in 0 until N) {
                rgb[0] = r / (N - 1f); rgb[1] = g / (N - 1f); rgb[2] = b / (N - 1f)
                at(rgb)
                out[o++] = rgb[0]; out[o++] = rgb[1]; out[o++] = rgb[2]
            }
            return out
        }

        fun at(c: FloatArray) {
            var r = c[0]; var g = c[1]; var b = c[2]
            if (sat != 1f || vib != 0f) {
                val y = luma(r, g, b)
                var s = sat
                if (vib != 0f) {
                    val chroma = max(r, max(g, b)) - min(r, min(g, b))
                    s *= 1f + vib * (1f - chroma) * (if (vib > 0) 1f else 0.9f)
                }
                r = (y + (r - y) * s).coerceIn(0f, 1f)
                g = (y + (g - y) * s).coerceIn(0f, 1f)
                b = (y + (b - y) * s).coerceIn(0f, 1f)
            }
            if (mixer) {
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val l = (mx + mn) / 2f
                val d = mx - mn
                if (d > 1e-4f) {
                    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
                    var h = when (mx) {
                        r -> ((g - b) / d).let { if (it < 0) it + 6f else it }
                        g -> (b - r) / d + 2f
                        else -> (r - g) / d + 4f
                    } * 60f
                    // the two bands either side of the hue, smoothly between them
                    var i = centres.size - 1
                    for (k in centres.indices) if (h >= centres[k]) i = k
                    val j = (i + 1) % centres.size
                    val from = centres[i]
                    val to = if (j == 0) 360f else centres[j]
                    val hh = if (h < from) h + 360f else h
                    var t = ((hh - from) / (to - from)).coerceIn(0f, 1f)
                    t = t * t * (3 - 2 * t)
                    fun band(q: Int) = (hsl[i * 3 + q] + (hsl[j * 3 + q] - hsl[i * 3 + q]) * t) / 100f
                    val dh = band(0); val ds = band(1); val dl = band(2)
                    // how strongly a colour belongs to its hue: greys are left alone
                    val w = (s * 2.5f).coerceAtMost(1f) * (d * 4f).coerceAtMost(1f)
                    h = (h + dh * 30f * w + 360f) % 360f
                    val s2 = (s * (1f + ds * w)).coerceIn(0f, 1f)
                    val l2 = (if (dl >= 0f) l + (1f - l) * dl * 0.45f * w else l * (1f + dl * 0.5f * w)).coerceIn(0f, 1f)
                    hslToRgb(h, s2, l2, c)
                    r = c[0]; g = c[1]; b = c[2]
                }
            }
            if (grading) {
                val y = luma(r, g, b)
                val ws = (1f - y / (mid + width)).coerceIn(0f, 1f).let { it * it }
                val wh = ((y - (mid - width)) / (1f - (mid - width))).coerceIn(0f, 1f).let { it * it }
                val dm = abs(y - mid) / (0.3f + width)
                val wm = if (dm < 1f) (1f - dm * dm).let { it * it } else 0f
                for ((z, w) in floatArrayOf(ws, wm, wh).withIndex()) if (w > 0f) {
                    val p = push[z]
                    r += w * (p[0] + p[3]); g += w * (p[1] + p[3]); b += w * (p[2] + p[3])
                }
            }
            c[0] = r.coerceIn(0f, 1f); c[1] = g.coerceIn(0f, 1f); c[2] = b.coerceIn(0f, 1f)
        }
    }

    companion object {
        private const val N = 33

        fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

        /** The pure colour of a hue (degrees), full saturation and value. */
        fun hueRgb(h: Float): FloatArray {
            val out = FloatArray(3)
            hslToRgb(((h % 360f) + 360f) % 360f, 1f, 0.5f, out)
            return out
        }

        fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            fun ch(t0: Float): Float {
                var t = t0
                if (t < 0) t += 1f
                if (t > 1) t -= 1f
                return when {
                    t < 1f / 6 -> p + (q - p) * 6 * t
                    t < 0.5f -> q
                    t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                    else -> p
                }
            }
            val hn = h / 360f
            out[0] = ch(hn + 1f / 3); out[1] = ch(hn); out[2] = ch(hn - 1f / 3)
        }

        private fun toLin(e: Float) = if (e <= 0.04045f) e / 12.92f else ((e + 0.055f) / 1.055f).pow(2.4f)
        private fun toSrgb(l: Float) = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
    }
}
