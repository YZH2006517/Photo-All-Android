package org.photoall.film.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws a 64-bin RGB + luma histogram (the viewfinder's and the viewer's). Heights are
 * square-root scaled so shadow and highlight tails stay visible beside a tall midtone
 * peak. Edge bins are drawn as-is: a spike at the right means clipped highlights.
 */
class HistogramPainter(private val density: Float) {
    private val back = Paint().apply { color = 0x66000000 }
    private val lumaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99ffffff.toInt() }
    private val channelLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun draw(canvas: Canvas, h: PreviewMeter.Histogram, left: Float, top: Float, w: Float, ht: Float) {
        val bottom = top + ht
        canvas.drawRect(left, top, left + w, bottom, back)
        val bins = h.luma.size
        val peak = maxOf(1, (0 until bins).maxOf { maxOf(h.luma[it], h.r[it], h.g[it], h.b[it]) })
        val scale = ht / Math.sqrt(peak.toDouble()).toFloat()
        val step = w / bins
        val path = Path().apply { moveTo(left, bottom) }
        for (i in 0 until bins) {
            val y = bottom - Math.sqrt(h.luma[i].toDouble()).toFloat() * scale
            path.lineTo(left + i * step, y)
            path.lineTo(left + (i + 1) * step, y)
        }
        path.lineTo(left + w, bottom)
        path.close()
        canvas.drawPath(path, lumaFill)
        channelLine.strokeWidth = density
        for ((values, colour) in listOf(h.r to 0xccff5050.toInt(), h.g to 0xcc50e070.toInt(), h.b to 0xcc5090ff.toInt())) {
            channelLine.color = colour
            var x0 = left
            var y0 = bottom - Math.sqrt(values[0].toDouble()).toFloat() * scale
            for (i in 1 until bins) {
                val x1 = left + (i + 0.5f) * step
                val y1 = bottom - Math.sqrt(values[i].toDouble()).toFloat() * scale
                canvas.drawLine(x0, y0, x1, y1, channelLine)
                x0 = x1
                y0 = y1
            }
        }
    }

    companion object {
        /** Histogram of a decoded photo's display values, sampled on a grid of about 256x256. */
        fun of(bitmap: Bitmap): PreviewMeter.Histogram {
            val hr = IntArray(64); val hg = IntArray(64); val hb = IntArray(64); val hl = IntArray(64)
            val columns = minOf(256, bitmap.width)
            val rows = minOf(256, bitmap.height)
            val line = IntArray(bitmap.width)
            for (r in 0 until rows) {
                bitmap.getPixels(line, 0, bitmap.width, 0, (r * bitmap.height) / rows, bitmap.width, 1)
                for (c in 0 until columns) {
                    val p = line[(c * bitmap.width) / columns]
                    val r8 = (p shr 16) and 0xff
                    val g8 = (p shr 8) and 0xff
                    val b8 = p and 0xff
                    hr[r8 shr 2]++; hg[g8 shr 2]++; hb[b8 shr 2]++
                    hl[(77 * r8 + 150 * g8 + 29 * b8) shr 10]++ // Rec.601 luma, as the viewfinder
                }
            }
            return PreviewMeter.Histogram(hr, hg, hb, hl)
        }
    }
}
