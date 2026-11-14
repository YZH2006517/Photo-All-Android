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
    /** A single tap (not the first of a double tap, not a drag). */
    var onTap: () -> Unit = {}
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

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap()
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
        // a relayout in the middle of a glide: start again from where the picture is now
        if (glide?.isRunning == true) { glide?.cancel(); glideFrom = RectF(lastOnScreen); glideUntil = android.os.SystemClock.uptimeMillis() + 600 }
        reset()
        if (glideFrom != null && android.os.SystemClock.uptimeMillis() < glideUntil) glidePending = true
    }

    private var glidePending = false

    // The glide starts here, in this layout pass (every parent is already in its new place, so
    // the window position is right) and before the frame is drawn: posted, it came a frame late
    // and the picture showed once at its new size first, then jumped back to glide (a flicker).
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (glidePending) { glidePending = false; startGlide() }
    }

    private var glideFrom: RectF? = null
    private var glideUntil = 0L
    private var glide: android.animation.ValueAnimator? = null
    private val lastOnScreen = RectF()

    /** The picture where it is on screen now, in window coordinates. */
    private fun onScreen(): RectF? {
        val dr = drawable ?: return null
        val r = RectF(0f, 0f, dr.intrinsicWidth.toFloat(), dr.intrinsicHeight.toFloat())
        imageMatrix.mapRect(r)
        val at = IntArray(2)
        getLocationInWindow(at)
        r.offset(at[0].toFloat(), at[1].toFloat())
        return r
    }

    /**
     * The layout is about to change (bars going or coming): keep the picture where it is on
     * screen, and once the new layout is in, ease it to its new fit.
     */
    fun holdPlace() {
        glideFrom = onScreen() ?: return
        lastOnScreen.set(glideFrom!!)
        glideUntil = android.os.SystemClock.uptimeMillis() + 600
    }

    private fun startGlide() {
        val from = glideFrom ?: return
        val dr = drawable ?: return
        glideFrom = null
        val at = IntArray(2)
        getLocationInWindow(at)
        val dw = dr.intrinsicWidth.toFloat()
        val target = Matrix(m)
        val fromScale = from.width() / dw
        val fromX = from.left - at[0]
        val fromY = from.top - at[1]
        val v = FloatArray(9)
        target.getValues(v)
        val toScale = v[Matrix.MSCALE_X]
        val toX = v[Matrix.MTRANS_X]
        val toY = v[Matrix.MTRANS_Y]
        glide = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val sc = fromScale + (toScale - fromScale) * t
                m.reset()
                m.postScale(sc, sc)
                m.postTranslate(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t)
                imageMatrix = m
                onScreen()?.let { r -> lastOnScreen.set(r) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (glideFrom == null) { m.set(target); imageMatrix = m }
                }
            })
            start()
        }
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
