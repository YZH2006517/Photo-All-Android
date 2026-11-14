package org.photoall.film.gallery

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * EDR: the developed picture's highlights above SDR white, saved as an Ultra HDR JPEG
 * (an SDR picture plus a gain map, Android 14 and later). The SDR picture is unchanged;
 * an HDR screen multiplies it by the map, up to [stops] above white.
 *
 * Where the headroom goes is decided after the print, before the file: a part of the
 * picture is lifted only where the print rendered it bright AND the scene behind it was
 * brighter than the print can hold (a clipped sky, a lamp, a sunlit edge; the photo's own
 * gain map when it has one). A white wall that was simply white stays at paper white,
 * so the lift reads as light, not as a brighter picture.
 */
object Edr {
    val supported get() = Build.VERSION.SDK_INT >= 34

    /** Gives [out] a gain map worked out from [source] (the picture before the film); [stops] 0 = none. */
    fun attach(out: Bitmap, source: Bitmap, stops: Float) {
        if (!supported || stops <= 0.01f || out.config != Bitmap.Config.ARGB_8888) return
        runCatching { attach34(out, source, stops) }
            .onFailure { android.util.Log.w("FilmEdr", "gain map", it) }
    }

    private val linear = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    @RequiresApi(34)
    private fun attach34(out: Bitmap, source: Bitmap, stops: Float) {
        // half resolution: smooth enough that its upscale never draws a seam, fine enough not to halo
        val gw = max(1, (out.width + 1) / 2)
        val gh = max(1, (out.height + 1) / 2)
        val o = Bitmap.createScaledBitmap(out, gw, gh, true)
        val s = Bitmap.createScaledBitmap(source, gw, gh, true)
        val op = IntArray(gw * gh).also { o.getPixels(it, 0, gw, 0, 0, gw, gh) }
        val sp = IntArray(gw * gh).also { s.getPixels(it, 0, gw, 0, 0, gw, gh) }
        if (o !== out) o.recycle()
        if (s !== source) s.recycle()
        val own = source.gainmap?.let { readMap(it.gainmapContents, gw, gh) }

        val map = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
        val rb = map.rowBytes
        val bytes = ByteArray(rb * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x
            val c = op[i]
            val lum = 0.2126f * linear[(c shr 16) and 255] + 0.7152f * linear[(c shr 8) and 255] + 0.0722f * linear[c and 255]
            // only what the print made bright is lifted
            val gate = smooth((lum - 0.30f) / 0.50f)
            // how far the scene went past what an 8-bit picture holds
            val sc = sp[i]
            val n = max((sc shr 16) and 255, max((sc shr 8) and 255, sc and 255)) / 255f
            var excess = smooth((n - 0.80f) / 0.20f)
            if (own != null) excess = max(excess, own[i])
            bytes[y * rb + x] = (255f * excess * gate).roundToInt().coerceIn(0, 255).toByte()
        }
        map.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        val peak = 2f.pow(stops)
        out.gainmap = android.graphics.Gainmap(map).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(peak, peak, peak)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            minDisplayRatioForHdrTransition = 1f
            displayRatioForFullHdr = peak
        }
    }

    /** The source's own gain map at gw x gh, 0..1 (its log-encoded lift). */
    private fun readMap(contents: Bitmap, gw: Int, gh: Int): FloatArray? {
        val m = Bitmap.createScaledBitmap(
            if (contents.config == Bitmap.Config.ALPHA_8 || contents.config == Bitmap.Config.ARGB_8888) contents
            else contents.copy(Bitmap.Config.ARGB_8888, false) ?: return null, gw, gh, true)
        val out = FloatArray(gw * gh)
        if (m.config == Bitmap.Config.ALPHA_8) {
            val rb = m.rowBytes
            val b = ByteArray(rb * gh)
            m.copyPixelsToBuffer(ByteBuffer.wrap(b))
            for (y in 0 until gh) for (x in 0 until gw) out[y * gw + x] = (b[y * rb + x].toInt() and 255) / 255f
        } else {
            val px = IntArray(gw * gh)
            m.getPixels(px, 0, gw, 0, 0, gw, gh)
            for (i in px.indices) out[i] = ((px[i] shr 8) and 255) / 255f
        }
        if (m !== contents) m.recycle()
        return out
    }
}
