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
 *  - the fraction of near-white samples, for highlight protection ([onHighlights]).
 * Runs every third frame.
 */
class PreviewMeter : ImageAnalysis.Analyzer {
    @Volatile var key = 0f
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

    override fun analyze(image: ImageProxy) {
        image.use { if (counter++ % 3 == 0) measure(it) }
    }

    private fun measure(image: ImageProxy) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val stride = plane.rowStride
        val columns = 128
        val rows = 96
        var logSum = 0.0
        var weightSum = 0.0
        var clipped = 0
        val wantHistogram = onHistogram != null
        val hr = IntArray(BINS); val hg = IntArray(BINS); val hb = IntArray(BINS); val hl = IntArray(BINS)
        val table = lut
        val n = lutSize
        for (r in 0 until rows) {
            val y = (r * image.height) / rows
            val dy = (r + 0.5) / rows - 0.5
            for (c in 0 until columns) {
                val x = (c * image.width) / columns
                val at = y * stride + x * 4
                val r8 = buffer.get(at).toInt() and 0xff
                val g8 = buffer.get(at + 1).toInt() and 0xff
                val b8 = buffer.get(at + 2).toInt() and 0xff
                if (maxOf(r8, g8, b8) >= 250) clipped++
                // Rec.709 / sRGB luminance of the linearised pixel
                val luminance = 0.2126f * linear[r8] + 0.7152f * linear[g8] + 0.0722f * linear[b8]
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
        onHighlights(clipped.toFloat() / (columns * rows))
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
