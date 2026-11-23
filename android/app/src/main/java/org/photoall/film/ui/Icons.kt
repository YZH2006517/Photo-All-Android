package org.photoall.film.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The app's line icons, drawn in a 24 x 24 box with a 1.6 stroke and round ends (the
 * design's icon set), in one colour. No icon font or vector resources: the same
 * strokes on every phone.
 */
class Icon(private val kind: Kind, color: Int, private val stroke: Float = 1.6f, private val filled: Boolean = false) : Drawable() {
    enum class Kind { FLASH, FLASH_OFF, HISTOGRAM, TUNE, GRID, CHEVRON, BACK, FLIP, HEART, SHARE, INFO, TRASH, OPEN, CHECK, FRAME, ROTATE,
        LOUPE, CROSS, PLAY, PAUSE, LAMP, LIGHTBOX, SPREAD, GATHER, EDIT, MIRROR }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val s = size / 24f
        canvas.save()
        canvas.translate(b.exactCenterX() - size / 2, b.exactCenterY() - size / 2)
        canvas.scale(s, s)
        paint.strokeWidth = stroke
        path.reset()
        when (kind) {
            Kind.FLASH, Kind.FLASH_OFF -> {
                path.moveTo(13f, 2f); path.lineTo(4f, 14f); path.lineTo(11f, 14f); path.lineTo(10f, 22f)
                path.lineTo(19f, 10f); path.lineTo(12f, 10f); path.close()
                canvas.drawPath(path, paint)
                if (kind == Kind.FLASH_OFF) canvas.drawLine(3f, 3f, 21f, 21f, paint)
            }
            Kind.HISTOGRAM -> {
                canvas.drawLine(3f, 20f, 21f, 20f, paint)
                canvas.drawLine(6f, 20f, 6f, 15f, paint)
                canvas.drawLine(10f, 20f, 10f, 9f, paint)
                canvas.drawLine(14f, 20f, 14f, 13f, paint)
                canvas.drawLine(18f, 20f, 18f, 6f, paint)
            }
            Kind.TUNE -> {
                canvas.drawLine(4f, 7f, 14f, 7f, paint); canvas.drawLine(18f, 7f, 20f, 7f, paint)
                canvas.drawCircle(16f, 7f, 2f, paint)
                canvas.drawLine(4f, 17f, 8f, 17f, paint); canvas.drawLine(12f, 17f, 20f, 17f, paint)
                canvas.drawCircle(10f, 17f, 2f, paint)
            }
            Kind.GRID -> for ((x, y) in listOf(4f to 4f, 13.5f to 4f, 4f to 13.5f, 13.5f to 13.5f)) {
                rect.set(x, y, x + 6.5f, y + 6.5f)
                canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
            }
            Kind.CHEVRON -> { path.moveTo(9f, 6f); path.lineTo(15f, 12f); path.lineTo(9f, 18f); canvas.drawPath(path, paint) }
            Kind.BACK -> { path.moveTo(15f, 6f); path.lineTo(9f, 12f); path.lineTo(15f, 18f); canvas.drawPath(path, paint) }
            Kind.FLIP -> { // front / back camera: two arcs with arrow heads
                rect.set(5f, 5f, 19f, 19f)
                canvas.drawArc(rect, 200f, 140f, false, paint)
                canvas.drawArc(rect, 20f, 140f, false, paint)
                path.moveTo(16.5f, 4.5f); path.lineTo(18.6f, 8.4f); path.lineTo(14.4f, 8.6f)
                path.moveTo(7.5f, 19.5f); path.lineTo(5.4f, 15.6f); path.lineTo(9.6f, 15.4f)
                canvas.drawPath(path, paint)
            }
            Kind.ROTATE -> { // a quarter turn clockwise: an open arc, its arrow at the top, and a frame turning
                rect.set(4.5f, 6f, 17.5f, 19f)
                canvas.drawArc(rect, 160f, 250f, false, paint)
                path.moveTo(14.2f, 3.6f); path.lineTo(17.4f, 6.4f); path.lineTo(13.6f, 8.4f)
                canvas.drawPath(path, paint)
                rect.set(9f, 10.5f, 13f, 14.5f)
                canvas.drawRoundRect(rect, 0.8f, 0.8f, paint)
            }
            Kind.HEART -> {
                path.moveTo(12f, 20f)
                path.cubicTo(8f, 17.5f, 5f, 14.5f, 5f, 10f)
                path.cubicTo(5f, 6.5f, 9.5f, 5.2f, 12f, 7.6f)
                path.cubicTo(14.5f, 5.2f, 19f, 6.5f, 19f, 10f)
                path.cubicTo(19f, 14.5f, 16f, 17.5f, 12f, 20f)
                path.close()
                if (filled) {
                    paint.style = Paint.Style.FILL_AND_STROKE
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.STROKE
                } else canvas.drawPath(path, paint)
            }
            Kind.SHARE -> {
                canvas.drawLine(12f, 3f, 12f, 15f, paint)
                path.moveTo(7f, 8f); path.lineTo(12f, 3f); path.lineTo(17f, 8f)
                path.moveTo(5f, 14f); path.lineTo(5f, 20f); path.lineTo(19f, 20f); path.lineTo(19f, 14f)
                canvas.drawPath(path, paint)
            }
            Kind.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawLine(12f, 11f, 12f, 17f, paint)
                canvas.drawLine(12f, 7.5f, 12f, 8f, paint)
            }
            Kind.TRASH -> {
                canvas.drawLine(4f, 7f, 20f, 7f, paint)
                path.moveTo(9f, 7f); path.lineTo(9f, 4f); path.lineTo(15f, 4f); path.lineTo(15f, 7f)
                path.moveTo(6f, 7f); path.lineTo(7f, 20f); path.lineTo(17f, 20f); path.lineTo(18f, 7f)
                canvas.drawPath(path, paint)
            }
            Kind.OPEN -> {
                path.moveTo(11f, 5f); path.lineTo(5f, 5f); path.lineTo(5f, 19f); path.lineTo(19f, 19f); path.lineTo(19f, 13f)
                path.moveTo(14f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 10f)
                canvas.drawPath(path, paint)
                canvas.drawLine(20f, 4f, 11f, 13f, paint)
            }
            Kind.EDIT -> { // three upright sliders, their knobs at different heights
                for ((x, k) in listOf(6f to 9f, 12f to 15.5f, 18f to 7.5f)) {
                    canvas.drawLine(x, 3.5f, x, k - 2.4f, paint)
                    canvas.drawCircle(x, k, 2.4f, paint)
                    canvas.drawLine(x, k + 2.4f, x, 20.5f, paint)
                }
            }
            Kind.MIRROR -> { // a dashed axis and a triangle either side of it, one of them solid
                for (y in listOf(3f, 7f, 11f, 15f, 19f)) canvas.drawLine(12f, y, 12f, y + 2f, paint)
                path.moveTo(9.5f, 6f); path.lineTo(9.5f, 18f); path.lineTo(3f, 18f); path.close()
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(14.5f, 6f); path.lineTo(14.5f, 18f); path.lineTo(21f, 18f); path.close()
                paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
            }
            Kind.FRAME -> { // a film frame: border, picture, perforations either side
                rect.set(3f, 3f, 21f, 21f); canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
                rect.set(7f, 6f, 17f, 18f); canvas.drawRect(rect, paint)
                for (y in listOf(6f, 10f, 14f, 18f)) {
                    canvas.drawLine(4.6f, y, 5.4f, y, paint)
                    canvas.drawLine(18.6f, y, 19.4f, y, paint)
                }
            }
            Kind.LOUPE -> { // a hand lens: the glass, a highlight, the handle
                canvas.drawCircle(10.5f, 10.5f, 6.5f, paint)
                rect.set(6.6f, 6.6f, 14.4f, 14.4f); canvas.drawArc(rect, 200f, 60f, false, paint)
                canvas.drawLine(15.3f, 15.3f, 20f, 20f, paint)
            }
            Kind.CROSS -> { canvas.drawLine(6f, 6f, 18f, 18f, paint); canvas.drawLine(18f, 6f, 6f, 18f, paint) }
            Kind.PLAY -> {
                path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close()
                canvas.drawPath(path, paint)
            }
            Kind.PAUSE -> { canvas.drawLine(9f, 6f, 9f, 18f, paint); canvas.drawLine(15f, 6f, 15f, 18f, paint) }
            Kind.LAMP -> { // a tube light: the tube, its glow, the switch below
                rect.set(4f, 5f, 20f, 9f); canvas.drawRoundRect(rect, 2f, 2f, paint)
                if (filled) for (x in listOf(7f, 12f, 17f)) canvas.drawLine(x, 12f, x, 14f, paint)
                rect.set(9f, 16f, 15f, 20f); canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
            }
            Kind.LIGHTBOX -> { // a light table with two cut frames on it
                rect.set(3f, 4f, 21f, 17f); canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
                rect.set(6f, 7f, 11f, 13.5f); canvas.drawRect(rect, paint)
                rect.set(13f, 7.5f, 18f, 14f); canvas.drawRect(rect, paint)
                canvas.drawLine(8f, 20f, 16f, 20f, paint)
            }
            Kind.SPREAD -> for ((x, y) in listOf(4f to 5f, 10.5f to 5f, 17f to 5f, 4f to 13.5f, 10.5f to 13.5f, 17f to 13.5f)) {
                rect.set(x, y, x + 3.5f, y + 5.5f)
                canvas.drawRect(rect, paint)
            }
            Kind.GATHER -> {
                // a little stack in the middle, four arrows coming in to it
                rect.set(9f, 9.5f, 15f, 14.5f); canvas.drawRect(rect, paint)
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    val tx = 12f + sx * 4.6f; val ty = 12f + sy * 4.4f   // the tip, near the stack
                    val ox = 12f + sx * 9.5f; val oy = 12f + sy * 9f     // the tail, out at the corner
                    canvas.drawLine(ox, oy, tx, ty, paint)
                    canvas.drawLine(tx, ty, tx + sx * 3f, ty, paint)
                    canvas.drawLine(tx, ty, tx, ty + sy * 3f, paint)
                }
            }
            Kind.CHECK -> {
                if (filled) {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(12f, 12f, 10f, paint)
                    paint.style = Paint.Style.STROKE
                    val fill = paint.color
                    paint.color = Style.ON_ACCENT // the mark in the text colour for an accent fill
                    path.moveTo(7.5f, 12.5f); path.lineTo(10.5f, 15.5f); path.lineTo(16.5f, 9f)
                    canvas.drawPath(path, paint)
                    paint.color = fill
                } else canvas.drawCircle(12f, 12f, 10f, paint)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
