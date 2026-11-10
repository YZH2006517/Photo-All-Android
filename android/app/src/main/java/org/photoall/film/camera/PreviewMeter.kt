package org.photoall.film.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Measures what the viewfinder shows from the (sRGB tone-curve) analysis frames:
 *  - [key]: centre-weighted log-average linear luminance. A RAW develop scales its
 *    exposure to it, so the film sees the brightness the preview showed. Weighting
 *    matches the engine's raw_key().
 *  - the fraction of near-white samples, for highlight protection ([onHighlights]);
 *  - [highlight] and [clipped]: how bright the brightest part is, for the HDR burst's
 *    underexposure.
 * Runs every third frame.
 */
class PreviewMeter : ImageAnalysis.Analyzer {
    @Volatile var key = 0f
        private set
    /** Linear level (0..1) of the brightest channel at the 99.5th percentile of samples. */
    @Volatile var highlight = 0f
        private set
    /** Fraction of samples with a channel at white (nothing tells how far over they are). */
    @Volatile var clipped = 0f
        private set
    @Volatile var onHighlights: (Float) -> Unit = {}

    /** Live histogram of what the viewfinder shows: 64 bins each of R, G, B, luma. */
    class Histogram(val r: IntArray, val g: IntArray, val b: IntArray, val luma: IntArray)
    @Volatile var onHistogram: ((Histogram) -> Unit)? = null

    // The viewfinder's film LUT (RGBA8, size^3, r fastest), so the histogram shows the
    // film rendering rather than the camera frame.
    @Volatile private var lut: ByteArray? = null
    @Volatile private var lutSize = 0
    fun setLut(data: ByteArray?, size: Int) {
        lut = data
        lutSize = if (data != null && data.size >= size * size * size * 4) size else 0
    }
    private var counter = 0
    private val linear = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    /**
     * Linear gain the viewfinder applies (a burst held under the meter is shown brighter):
     * the key and the histogram are of what is shown; [highlight] and [clipped] stay the
     * sensor's own headroom.
     */
    @Volatile var gain = 1f
        set(value) {
            field = value
            shownTable = IntArray(256) { i ->
                val l = (linear[i] * value).coerceIn(0f, 1f)
                val e = if (l <= 0.0031308f) 12.92f * l else 1.055f * l.pow(1f / 2.4f) - 0.055f
                (e * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
    private var shownTable = IntArray(256) { it }

    override fun analyze(image: ImageProxy) {
        image.use {
            if (counter++ % 3 != 0) return@use
            val plane = it.planes[0]
            val buffer = plane.buffer
            val stride = plane.rowStride
            measure(it.width, it.height) { x, y, out ->
                val at = y * stride + x * 4
                out[0] = buffer.get(at).toInt() and 0xff
                out[1] = buffer.get(at + 1).toInt() and 0xff
                out[2] = buffer.get(at + 2).toInt() and 0xff
            }
        }
    }

    /** A YUV_420_888 frame straight from a Camera2 ImageReader (full-range BT.601). */
    fun analyzeYuv(image: android.media.Image) {
        if (counter++ % 3 != 0) return
        val yp = image.planes[0]; val up = image.planes[1]; val vp = image.planes[2]
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        measure(image.width, image.height) { x, y, out ->
            val l = (yb.get(y * yp.rowStride + x * yp.pixelStride).toInt() and 0xff).toFloat()
            val ci = (y / 2) * up.rowStride + (x / 2) * up.pixelStride
            val u = (ub.get(ci).toInt() and 0xff) - 128f
            val v = (vb.get((y / 2) * vp.rowStride + (x / 2) * vp.pixelStride).toInt() and 0xff) - 128f
            out[0] = (l + 1.402f * v + 0.5f).toInt().coerceIn(0, 255)
            out[1] = (l - 0.344136f * u - 0.714136f * v + 0.5f).toInt().coerceIn(0, 255)
            out[2] = (l + 1.772f * u + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    private val rgb = IntArray(3)

    private fun measure(width: Int, height: Int, pixel: (Int, Int, IntArray) -> Unit) {
        val shownBy = shownTable
        val g = gain
        val columns = 128
        val rows = 96
        var logSum = 0.0
        var weightSum = 0.0
        var clipped = 0
        val wantHistogram = onHistogram != null
        val brightest = IntArray(256) // the largest channel per sample
        val hr = IntArray(BINS); val hg = IntArray(BINS); val hb = IntArray(BINS); val hl = IntArray(BINS)
        val table = lut
        val n = lutSize
        for (r in 0 until rows) {
            val y = (r * height) / rows
            val dy = (r + 0.5) / rows - 0.5
            for (c in 0 until columns) {
                val x = (c * width) / columns
                pixel(x, y, rgb)
                val top = maxOf(rgb[0], rgb[1], rgb[2])
                if (top >= 250) clipped++
                brightest[top]++
                // Rec.709 / sRGB luminance of the linearised pixel
                val luminance = g * (0.2126f * linear[rgb[0]] + 0.7152f * linear[rgb[1]] + 0.0722f * linear[rgb[2]])
                val r8 = shownBy[rgb[0]]; val g8 = shownBy[rgb[1]]; val b8 = shownBy[rgb[2]]
                val dx = (c + 0.5) / columns - 0.5
                val w = 1.0 + 3.0 * exp(-(dx * dx + dy * dy) / (2 * 0.18 * 0.18))
                logSum += w * ln(luminance + 1e-4)
                weightSum += w
                if (wantHistogram) {
                    // Through the film LUT, trilinear like the GPU: nearest grid points would
                    // leave only ~33 output levels and comb the 64-bin histogram.
                    var shownR = r8; var shownG = g8; var shownB = b8
                    if (table != null && n > 1) {
                        lookup(table, n, r8, g8, b8, shown)
                        shownR = shown[0]; shownG = shown[1]; shownB = shown[2]
                    }
                    hr[shownR shr 2]++; hg[shownG shr 2]++; hb[shownB shr 2]++
                    hl[(77 * shownR + 150 * shownG + 29 * shownB) shr 10]++ // Rec.601 luma of the display values
                }
            }
        }
        key = (exp(logSum / weightSum) - 1e-4).toFloat()
        val samples = columns * rows
        var above = 0
        var level = 255
        while (level > 0 && above + brightest[level] <= samples / 200) { above += brightest[level]; level-- }
        highlight = linear[level]
        this.clipped = clipped.toFloat() / samples
        onHighlights(clipped.toFloat() / samples)
        if (wantHistogram) onHistogram?.invoke(Histogram(hr, hg, hb, hl))
    }

    private val shown = IntArray(3)

    /** Trilinear lookup of an 8-bit RGB in an RGBA8 size^3 LUT (r fastest). */
    private fun lookup(table: ByteArray, n: Int, r8: Int, g8: Int, b8: Int, out: IntArray) {
        val scale = (n - 1) / 255f
        val fr = r8 * scale; val fg = g8 * scale; val fb = b8 * scale
        val r0 = minOf(fr.toInt(), n - 2); val g0 = minOf(fg.toInt(), n - 2); val b0 = minOf(fb.toInt(), n - 2)
        val tr = fr - r0; val tg = fg - g0; val tb = fb - b0
        for (c in 0 until 3) {
            fun v(dr: Int, dg: Int, db: Int) =
                (table[((r0 + dr) + (g0 + dg) * n + (b0 + db) * n * n) * 4 + c].toInt() and 0xff).toFloat()
            val c00 = v(0, 0, 0) + (v(1, 0, 0) - v(0, 0, 0)) * tr
            val c10 = v(0, 1, 0) + (v(1, 1, 0) - v(0, 1, 0)) * tr
            val c01 = v(0, 0, 1) + (v(1, 0, 1) - v(0, 0, 1)) * tr
            val c11 = v(0, 1, 1) + (v(1, 1, 1) - v(0, 1, 1)) * tr
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            out[c] = (c0 + (c1 - c0) * tb + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    private companion object {
        const val BINS = 64
    }
}
