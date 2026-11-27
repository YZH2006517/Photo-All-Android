package org.photoall.film.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * A collage's ground from its photo: the photo's main colours picked out (hue by hue,
 * weighted by how much and how vivid each is), deepened, and laid as a smooth gradient with
 * a soft glow, so the print sits on its own colours with no trace of the photo's shapes.
 */
object Tones {
    /** Three colours: the main one, the second, a light for the glow. */
    fun of(photo: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(photo, 32, 32, true)
        val px = IntArray(32 * 32); s.getPixels(px, 0, 32, 0, 0, 32, 32)
        if (s !== photo) s.recycle()
        // twelve hue bins plus one for greys, each weighted by saturation x brightness
        val weight = FloatArray(13); val sumR = FloatArray(13); val sumG = FloatArray(13); val sumB = FloatArray(13)
        val hsv = FloatArray(3)
        for (c in px) {
            Color.colorToHSV(c, hsv)
            val bin = if (hsv[1] < 0.15f) 12 else ((hsv[0] / 30f).toInt() % 12)
            val w = if (bin == 12) 0.15f * hsv[2] else hsv[1] * (0.3f + hsv[2])
            weight[bin] += w
            sumR[bin] += Color.red(c) * w; sumG[bin] += Color.green(c) * w; sumB[bin] += Color.blue(c) * w
        }
        val order = (0 until 13).sortedByDescending { weight[it] }.filter { weight[it] > 0f }
        fun mean(b: Int) = Color.rgb((sumR[b] / weight[b]).toInt(), (sumG[b] / weight[b]).toInt(), (sumB[b] / weight[b]).toInt())
        val first = order.getOrNull(0)?.let { mean(it) } ?: 0xff3a3a40.toInt()
        val second = order.getOrNull(1)?.let { mean(it) } ?: first
        return intArrayOf(tone(first, 0.42f, 0.75f), tone(second, 0.22f, 0.7f), tone(first, 0.7f, 0.5f))
    }

    /**
     * The photo's colours for a wallpaper: up to five, the most present first — each hue that holds
     * a fair share of the picture (weighted by how vivid), averaged; a near-grey picture gives its
     * greys. Never fewer than three (neighbours of the main one fill in).
     */
    fun palette(photo: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(photo, 48, 48, true)
        val px = IntArray(48 * 48); s.getPixels(px, 0, 48, 0, 0, 48, 48)
        if (s !== photo) s.recycle()
        val weight = FloatArray(13); val sumR = FloatArray(13); val sumG = FloatArray(13); val sumB = FloatArray(13)
        val hsv = FloatArray(3)
        for (c in px) {
            Color.colorToHSV(c, hsv)
            val bin = if (hsv[1] < 0.12f || hsv[2] < 0.08f) 12 else ((hsv[0] / 30f).toInt() % 12)
            val w = if (bin == 12) 0.12f else hsv[1] * (0.25f + hsv[2])
            weight[bin] += w
            sumR[bin] += Color.red(c) * w; sumG[bin] += Color.green(c) * w; sumB[bin] += Color.blue(c) * w
        }
        val total = weight.sum().coerceAtLeast(1e-3f)
        fun mean(b: Int) = Color.rgb((sumR[b] / weight[b]).toInt(), (sumG[b] / weight[b]).toInt(), (sumB[b] / weight[b]).toInt())
        val out = (0 until 13).sortedByDescending { weight[it] }.filter { weight[it] / total > 0.04f }.take(5).map { mean(it) }.toMutableList()
        if (out.isEmpty()) out += 0xff4a4b4e.toInt()
        while (out.size < 3) {
            val h2 = FloatArray(3); Color.colorToHSV(out[0], h2)
            h2[0] = (h2[0] + (if (out.size == 1) 24f else 336f)) % 360f
            out += Color.HSVToColor(h2)
        }
        return out.toIntArray()
    }

    /** [c] at brightness [v], its saturation capped at [maxSat] (a ground, not a poster). */
    private fun tone(c: Int, v: Float, maxSat: Float): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[1] = minOf(hsv[1], maxSat); hsv[2] = v
        return Color.HSVToColor(hsv)
    }

    /** The ground over [W] x [H]: main colour at the top into the second below, a glow up left. */
    fun paint(c: Canvas, W: Float, H: Float, tones: IntArray?) {
        val t = tones ?: intArrayOf(0xff34343a.toInt(), 0xff18181c.toInt(), 0xff5a5a62.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, W * 0.3f, H, t[0], t[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = RadialGradient(W * 0.25f, H * 0.18f, maxOf(W, H) * 0.7f,
            intArrayOf(t[2] and 0x00ffffff or 0x66000000, t[2] and 0x00ffffff), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = RadialGradient(W * 0.5f, H * 0.55f, maxOf(W, H) * 0.75f, intArrayOf(0x00000000, 0x59000000), floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
    }
}
