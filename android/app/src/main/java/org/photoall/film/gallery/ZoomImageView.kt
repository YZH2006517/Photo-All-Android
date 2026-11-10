package org.photoall.film.gallery

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * Fit-to-screen image with pinch zoom, pan and double-tap. When not zoomed, a sideways
 * drag is reported as it goes ([onDrag], the distance so far) and when let go ([onDragEnd],
 * with the fling's speed), so the page can follow the finger; a vertical fling goes to
 * [onSwipeVertical] (+1 up, -1 down).
 */
class ZoomImageView(context: Context) : ImageView(context) {
    var onSwipe: (Int) -> Unit = {}
    var onSwipeVertical: (Int) -> Unit = {}
    var onDrag: (Float) -> Unit = {}
    var onDragEnd: (dx: Float, vx: Float) -> Unit = { _, _ -> }
    /** What is shown changed: another picture, or the zoom and pan (a frosted copy follows). */
    var onDisplayChanged: () -> Unit = {}
    private var downX = 0f
    private var downY = 0f
    private var paging = false     // this gesture moves the page sideways
    private var decided = false    // sideways or not has been settled for this gesture
    private var velocity: android.view.VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
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
            if (paging) return true // the drag's end decides the page
            if (scale <= 1.01f && Math.abs(vy) > Math.abs(vx) && Math.abs(vy) > 800) onSwipeVertical(if (vy < 0) 1 else -1)
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
        // fit the new picture before it is drawn: a frame drawn with the last picture's
        // matrix (a preview giving way to the full size) flashes it at the wrong scale
        if (width > 0) reset() else post { reset() }
        onDisplayChanged()
    }

    override fun setImageMatrix(matrix: Matrix?) {
        super.setImageMatrix(matrix)
        onDisplayChanged()
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // screen coordinates: the view itself moves with the finger while paging, so
                // its own coordinates would feed the movement back and shake
                downX = event.rawX; downY = event.rawY; paging = false; decided = false
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (paging) { paging = false; onDragEnd(0f, 0f) } else decided = true
        }
        velocity?.let { v ->
            val screen = MotionEvent.obtain(event)
            screen.setLocation(event.rawX, event.rawY)
            v.addMovement(screen)
            screen.recycle()
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !scaler.isInProgress && scale <= 1.01f && event.pointerCount == 1) {
            val dx = event.rawX - downX
            val dy = event.rawY - downY
            if (!decided && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                decided = true
                paging = Math.abs(dx) > Math.abs(dy)
            }
            if (paging) {
                parent?.requestDisallowInterceptTouchEvent(true)
                onDrag(dx)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (paging) {
                velocity?.computeCurrentVelocity(1000)
                onDragEnd(event.rawX - downX, velocity?.xVelocity ?: 0f)
            }
            velocity?.recycle(); velocity = null
        }
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) paging = false
        return true
    }
}
