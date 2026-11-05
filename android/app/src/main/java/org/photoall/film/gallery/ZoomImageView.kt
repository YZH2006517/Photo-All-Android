package org.photoall.film.gallery

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * Fit-to-screen image with pinch zoom, pan and double-tap. When not zoomed, a
 * horizontal fling is reported through [onSwipe] (-1 previous, +1 next).
 */
class ZoomImageView(context: Context) : ImageView(context) {
    var onSwipe: (Int) -> Unit = {}
    private val m = Matrix()
    private var fit = 1f
    private var scale = 1f // relative to fit

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (scale * d.scaleFactor).coerceIn(1f, 8f)
            val f = target / scale
            scale = target
            m.postScale(f, f, d.focusX, d.focusY)
            clamp()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale > 1f) {
                m.postTranslate(-dx, -dy)
                clamp()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scale <= 1.01f && Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 800) onSwipe(if (vx < 0) 1 else -1)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1f) reset() else {
                scale = 2.5f
                m.postScale(2.5f, 2.5f, e.x, e.y)
                clamp()
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post { reset() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    fun reset() {
        val d = drawable ?: return
        if (width == 0) return
        fit = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        scale = 1f
        m.reset()
        m.postScale(fit, fit)
        m.postTranslate((width - d.intrinsicWidth * fit) / 2, (height - d.intrinsicHeight * fit) / 2)
        imageMatrix = m
    }

    // Keep the image covering the view where it can, centred where it cannot.
    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2 - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2 - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
