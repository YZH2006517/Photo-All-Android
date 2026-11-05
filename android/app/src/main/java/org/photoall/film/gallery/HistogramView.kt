package org.photoall.film.gallery

import android.content.Context
import android.graphics.Canvas
import android.view.View
import org.photoall.film.camera.HistogramPainter
import org.photoall.film.camera.PreviewMeter

/** A photo's histogram, drawn like the viewfinder's; hidden while there is none. */
class HistogramView(context: Context) : View(context) {
    private val painter = HistogramPainter(resources.displayMetrics.density)

    var histogram: PreviewMeter.Histogram? = null
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val h = histogram ?: return
        painter.draw(canvas, h, 0f, 0f, width.toFloat(), height.toFloat())
    }
}
