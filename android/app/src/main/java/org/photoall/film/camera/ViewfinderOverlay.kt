package org.photoall.film.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout

/** Largest 3:4 frame (4:3 when [landscape]) in the offered space; children fill it. */
class ViewfinderFrame(context: Context) : FrameLayout(context) {
    var landscape = false
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (aw, ah) = if (landscape) 4 to 3 else 3 to 4
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) width * ah / aw
        else MeasureSpec.getSize(heightMeasureSpec)
        val w = minOf(width, height * aw / ah)
        val h = minOf(height, width * ah / aw)
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

/**
 * Drawn over the viewfinder: composition guides, the tap-to-focus mark and a line
 * of live info. Not clickable, so touches reach the viewfinder underneath.
 */
class ViewfinderOverlay(context: Context) : View(context) {
    enum class Focus { Metering, Focusing, Focused, Failed, Locked }

    /** 0 off, 1 rule of thirds, 2 golden ratio, 3 diagonals, 4 centre cross. */
    var guides = 0
        set(value) { field = value; invalidate() }
    var info = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Live histogram (bottom-left); null hides it. */
    var histogram: PreviewMeter.Histogram? = null
        set(value) { field = value; invalidate() }
    private val histogramPainter by lazy { HistogramPainter(density) } // after density is set

    private val density = resources.displayMetrics.density
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66ffffff
        strokeWidth = density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * density
        setShadowLayer(2 * density, 0f, 0f, Color.BLACK)
    }

    private var focusX = 0f
    private var focusY = 0f
    private var focus: Focus? = null
    private var ringScale = 1f
    private var ringAlpha = 0f
    private var animator: ValueAnimator? = null
    private val hide = Runnable { fade() }

    /** Shows the focus mark at view coordinates; [Focus.Locked] stays until cleared. */
    fun showFocus(x: Float, y: Float, state: Focus) {
        val moved = x != focusX || y != focusY || focus == null
        focusX = x
        focusY = y
        focus = state
        removeCallbacks(hide)
        animator?.cancel()
        ringAlpha = 1f
        if (state == Focus.Focusing && moved) {
            // Contract onto the point, like a lens hunting.
            animator = ValueAnimator.ofFloat(1.3f, 1f).setDuration(220).apply {
                addUpdateListener { ringScale = it.animatedValue as Float; invalidate() }
                start()
            }
        } else ringScale = 1f
        if (state != Focus.Locked && state != Focus.Focusing) postDelayed(hide, 1500)
        invalidate()
    }

    fun hideFocus() {
        removeCallbacks(hide)
        animator?.cancel()
        focus = null
        invalidate()
    }

    private fun fade() {
        animator = ValueAnimator.ofFloat(1f, 0f).setDuration(300).apply {
            addUpdateListener {
                ringAlpha = it.animatedValue as Float
                if (ringAlpha == 0f) focus = null
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (guides) {
            1 -> grid(canvas, w, h, 1f / 3, 2f / 3)
            2 -> grid(canvas, w, h, 0.382f, 0.618f)
            3 -> {
                grid(canvas, w, h, 1f / 3, 2f / 3)
                canvas.drawLine(0f, 0f, w, h, guidePaint)
                canvas.drawLine(w, 0f, 0f, h, guidePaint)
            }
            4 -> {
                val arm = 14 * density
                canvas.drawLine(w / 2 - arm, h / 2, w / 2 + arm, h / 2, guidePaint)
                canvas.drawLine(w / 2, h / 2 - arm, w / 2, h / 2 + arm, guidePaint)
            }
        }
        if (info.isNotEmpty()) canvas.drawText(info, 10 * density, 20 * density, textPaint)
        histogram?.let { drawHistogram(canvas, it) }

        val state = focus ?: return
        ringPaint.color = when (state) {
            Focus.Metering, Focus.Focusing -> Color.WHITE
            Focus.Focused, Focus.Locked -> org.photoall.film.ui.Style.ACCENT
            Focus.Failed -> 0xffff5040.toInt()
        }
        ringPaint.alpha = (ringAlpha * 255).toInt()
        val half = (if (state == Focus.Metering) 22 else 34) * density * ringScale
        if (state == Focus.Metering) {
            // Exposure point only (manual focus): a plain small circle.
            canvas.drawCircle(focusX, focusY, half, ringPaint)
        } else {
            // Corner brackets of a square, the usual AF-frame shape.
            val arm = half * 0.45f
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                val cx = focusX + sx * half
                val cy = focusY + sy * half
                canvas.drawLine(cx, cy, cx - sx * arm, cy, ringPaint)
                canvas.drawLine(cx, cy, cx, cy - sy * arm, ringPaint)
            }
            canvas.drawCircle(focusX, focusY, 1.5f * density, ringPaint)
        }
        if (state == Focus.Locked || state == Focus.Failed) {
            val label = if (state == Focus.Locked) "AF-L" else "未合焦"
            textPaint.alpha = ringPaint.alpha
            canvas.drawText(label, focusX - textPaint.measureText(label) / 2, focusY + half + 16 * density, textPaint)
            textPaint.alpha = 255
        }
    }

    /**
     * Luma filled, R/G/B as lines; square-root scale so shadows and highlights stay
     * visible beside a tall midtone peak. Edge bins are drawn as-is: a spike at the
     * right means clipped highlights.
     */
    private fun drawHistogram(canvas: Canvas, h: PreviewMeter.Histogram) {
        val ht = 56 * density
        histogramPainter.draw(canvas, h, 10 * density, height - 10 * density - ht, 120 * density, ht)
    }

    private fun grid(canvas: Canvas, w: Float, h: Float, a: Float, b: Float) {
        for (f in floatArrayOf(a, b)) {
            canvas.drawLine(w * f, 0f, w * f, h, guidePaint)
            canvas.drawLine(0f, h * f, w, h * f, guidePaint)
        }
    }
}
