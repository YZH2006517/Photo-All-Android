package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The blackout at the moment of a shot: the whole viewfinder goes black while the
 * sensor is exposing (as an SLR's does while its mirror is up), then comes back. Drawn
 * over the picture (the GL surface cannot be faded), under the corner mask.
 */
class ShutterCurtain(context: Context) : View(context) {
    private val paint = Paint().apply { color = 0xff000000.toInt() }
    private var dark = 0f // 0 the picture .. 1 black
    private var running: ValueAnimator? = null
    private val failsafe = Runnable { if (dark > 0f) open() }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun animateTo(target: Float, ms: Long) {
        running?.cancel()
        running = ValueAnimator.ofFloat(dark, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { dark = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** The exposure begins: black at once, and it stays. Comes back by itself after 4 s. */
    fun close() {
        removeCallbacks(failsafe)
        postDelayed(failsafe, 4000)
        running?.cancel()
        dark = 1f // no fade in: a blackout is instant
        invalidate()
    }

    /** The exposure is over: the picture comes back. */
    fun open() {
        removeCallbacks(failsafe)
        animateTo(0f, 150)
    }

    /** One blink, for a shot with no exposure to wait for. */
    fun fire() {
        close()
        postDelayed({ open() }, 120)
    }

    override fun onDraw(canvas: Canvas) {
        if (dark <= 0f) return
        paint.alpha = (255 * dark).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
