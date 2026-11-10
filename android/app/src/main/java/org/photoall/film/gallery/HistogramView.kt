package org.photoall.film.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import org.photoall.film.camera.PreviewMeter
import org.photoall.film.ui.Style
import kotlin.math.sqrt

/**
 * A photo's histogram, fine: 256 levels drawn as smooth curves. The three channels are
 * translucent fills that add where they overlap (white where all three agree), the luma a
 * line over them, quarter-stop guides under them, and a mark at either end when shadows or
 * highlights are clipped (with the share of the picture). Heights are square-root scaled so
 * tails stay visible beside a tall peak. Hidden while there is none.
 */
class HistogramView(context: Context) : View(context) {
    var histogram: PreviewMeter.Histogram? = null
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val guide = Paint().apply { strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * resources.displayMetrics.density }
    private val path = Path()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) } // the additive blend needs a layer of its own

    override fun onDraw(canvas: Canvas) {
        val h = histogram ?: return
        val bins = h.luma.size
        val left = 0f
        val top = 4 * density
        val w = width.toFloat()
        val bottom = height - 14 * density
        val ht = bottom - top
        // guides: quarters of the range
        guide.color = Style.HAIRLINE
        for (q in 1..3) canvas.drawLine(left + w * q / 4, top, left + w * q / 4, bottom, guide)
        canvas.drawLine(left, bottom, left + w, bottom, guide)
        val smooth = { v: IntArray -> FloatArray(bins) { i ->
            // a light 5-tap smoothing: the fine bins without the noise of a sample
            var s = 0f; var n = 0f
            for (k in -2..2) { val j = i + k; if (j in 0 until bins) { val wgt = 3f - kotlin.math.abs(k); s += v[j] * wgt; n += wgt } }
            s / n
        } }
        val r = smooth(h.r); val g = smooth(h.g); val b = smooth(h.b); val l = smooth(h.luma)
        val peak = maxOf(1f, (1 until bins - 1).maxOf { maxOf(r[it], g[it], b[it], l[it]) })
        val scale = ht / sqrt(peak)
        fun y(v: Float) = (bottom - sqrt(v) * scale).coerceAtLeast(top)
        fun curve(v: FloatArray, closed: Boolean) {
            path.reset()
            path.moveTo(left, if (closed) bottom else y(v[0]))
            if (closed) path.lineTo(left, y(v[0]))
            for (i in 1 until bins) path.lineTo(left + w * i / (bins - 1), y(v[i]))
            if (closed) { path.lineTo(left + w, bottom); path.close() }
        }
        val a = if (Style.palette.light) 0x70 else 0x80
        for ((v, rgb) in listOf(r to 0xff3b30, g to 0x34c759, b to 0x0a84ff)) {
            fill.color = (a shl 24) or rgb
            curve(v, closed = true)
            canvas.drawPath(path, fill)
        }
        line.color = Style.TEXT
        line.strokeWidth = 1.2f * density
        curve(l, closed = false)
        canvas.drawPath(path, line)
        // clipping: the share of the picture at either end
        val total = h.luma.sum().coerceAtLeast(1)
        val dark = h.luma[0] * 100f / total
        val light = h.luma[bins - 1] * 100f / total
        label.color = Style.TEXT_2
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(if (dark >= 0.1f) "▲ 暗部溢出 %.1f%%".format(dark) else "暗部", left, height - 2 * density, label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(if (light >= 0.1f) "高光溢出 %.1f%% ▲".format(light) else "高光", left + w, height - 2 * density, label)
        label.textAlign = Paint.Align.CENTER
        canvas.drawText("中间调", left + w / 2, height - 2 * density, label)
    }
}
