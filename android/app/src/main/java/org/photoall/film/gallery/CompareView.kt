package org.photoall.film.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.photoall.film.ui.Style
import kotlin.math.abs

/**
 * The tuning bench's picture: the original and the develop in one frame, the original left
 * of a divider and the develop right of it, at the same place on the screen.
 * One finger on the divider (or anywhere at 1x) moves it; two fingers zoom (up to 8x) and a
 * double tap goes to 3x there and back; zoomed in, one finger away from the divider pans.
 */
class CompareView(context: Context) : View(context) {
    var original: Bitmap? = null
        set(v) { field = v; invalidate() }
    var developed: Bitmap? = null
        set(v) { field = v; invalidate() }
    /** 0..1 across the frame on the screen; where the original ends and the develop begins. */
    var split = 0.5f
        private set
    var labelFace: Typeface = Style.medium
    var busy = false
        set(v) { field = v; invalidate() }
    /** The zoom changed (1 = the whole picture). */
    var onZoom: (Float) -> Unit = {}

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val d = resources.displayMetrics.density
    private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()   // the whole picture fitted in the view
    private val shown = RectF()   // the picture as drawn, zoomed and panned
    private val src = android.graphics.Rect()
    private var dragging = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class Drag { NONE, PENDING, DIVIDER, PAN }
    private var downX = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: ScaleGestureDetector): Boolean {
            zoomBy(g.scaleFactor, g.focusX, g.focusY)
            return true
        }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) { zoom = 1f; panX = 0f; panY = 0f; changedZoom() }
            else zoomBy(3f, e.x, e.y)
            return true
        }
    })

    init {
        contentDescription = "原图与冲洗对比：拖动分割线对比，双指缩放，双击放大"
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        if (frame.isEmpty) return
        val next = (zoom * factor).coerceIn(1f, 8f)
        val f = next / zoom
        if (f == 1f) return
        layoutShown()
        // the point under the fingers stays put
        val left = fx - (fx - shown.left) * f
        val top = fy - (fy - shown.top) * f
        zoom = next
        panX = left - (frame.centerX() - frame.width() * zoom / 2)
        panY = top - (frame.centerY() - frame.height() * zoom / 2)
        changedZoom()
    }

    private fun changedZoom() {
        clampPan()
        invalidate()
        onZoom(zoom)
    }

    private fun clampPan() {
        val mx = (frame.width() * zoom - frame.width()) / 2
        val my = (frame.height() * zoom - frame.height()) / 2
        panX = panX.coerceIn(-mx, mx)
        panY = panY.coerceIn(-my, my)
    }

    private fun layoutShown() {
        val w = frame.width() * zoom
        val h = frame.height() * zoom
        val l = frame.centerX() - w / 2 + panX
        val t = frame.centerY() - h / 2 + panY
        shown.set(l, t, l + w, t + h)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (original == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        if (e.pointerCount > 1 || scaler.isInProgress) { dragging = Drag.NONE; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cut = frame.left + frame.width() * split
                // on the divider: it moves; elsewhere at 1x a drag (not a tap, nor a double
                // tap's first touch) takes it along; zoomed in, a drag pans
                dragging = if (abs(e.x - cut) < 28 * d) Drag.DIVIDER else if (zoom <= 1.05f) Drag.PENDING else Drag.PAN
                lastX = e.x; lastY = e.y; downX = e.x
            }
            MotionEvent.ACTION_MOVE -> when (dragging) {
                Drag.DIVIDER -> moveDivider(e.x)
                Drag.PENDING -> if (abs(e.x - downX) > slop) { dragging = Drag.DIVIDER; moveDivider(e.x) }
                Drag.PAN -> { panX += e.x - lastX; panY += e.y - lastY; clampPan(); invalidate(); lastX = e.x; lastY = e.y }
                Drag.NONE -> { lastX = e.x; lastY = e.y }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = Drag.NONE
        }
        return true
    }

    private fun moveDivider(x: Float) {
        if (frame.width() <= 0) return
        split = ((x - frame.left) / frame.width()).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val a = original ?: return
        val b = developed ?: a
        // the whole picture fitted in the view, centred; drawn zoomed into that frame
        val s = minOf(width.toFloat() / a.width, height.toFloat() / a.height)
        val w = a.width * s; val h = a.height * s
        frame.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        clampPan()
        layoutShown()
        val cut = frame.left + frame.width() * split
        c.save(); c.clipRect(frame.left, frame.top, cut, frame.bottom)
        src.set(0, 0, a.width, a.height); c.drawBitmap(a, src, shown, image)
        c.restore()
        c.save(); c.clipRect(cut, frame.top, frame.right, frame.bottom)
        src.set(0, 0, b.width, b.height); c.drawBitmap(b, src, shown, image)
        c.restore()
        // the divider and its grip
        line.color = 0xffffffff.toInt(); line.strokeWidth = 1.5f * d
        c.drawLine(cut, frame.top, cut, frame.bottom, line)
        line.color = 0x66000000; line.strokeWidth = 0.6f * d
        c.drawLine(cut + 1.2f * d, frame.top, cut + 1.2f * d, frame.bottom, line)
        val cy = frame.centerY()
        pill.color = 0xffffffff.toInt()
        c.drawCircle(cut, cy, 13 * d, pill)
        line.color = 0xff333333.toInt(); line.strokeWidth = 1.6f * d
        for (sign in floatArrayOf(-1f, 1f)) {
            val x = cut + sign * 5 * d
            c.drawLine(x, cy - 4 * d, x + sign * 3 * d, cy, line)
            c.drawLine(x, cy + 4 * d, x + sign * 3 * d, cy, line)
        }
        // labels, and the zoom when there is one
        text.textSize = 11 * d; text.typeface = labelFace
        label(c, "原图", frame.left + 8 * d, frame.top + 8 * d, left = true, show = split > 0.12f)
        label(c, if (busy) "冲洗中…" else "冲洗后", frame.right - 8 * d, frame.top + 8 * d, left = false, show = split < 0.88f)
        if (zoom > 1.05f) label(c, "%.1f×".format(zoom), frame.right - 8 * d, frame.bottom - 28 * d, left = false, show = true)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, left: Boolean, show: Boolean) {
        if (!show) return
        val tw = text.measureText(s)
        val padX = 8 * d; val h = 20 * d
        val l = if (left) x else x - tw - 2 * padX
        pill.color = 0x99000000.toInt()
        c.drawRoundRect(l, y, l + tw + 2 * padX, y + h, h / 2, h / 2, pill)
        text.color = 0xffffffff.toInt()
        c.drawText(s, l + padX, y + h / 2 + 4 * d, text)
    }
}
