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
    private val histogramBack = Paint().apply { color = 0x66000000 }
    private val lumaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99ffffff.toInt() }
    private val channelLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

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
            Focus.Focused, Focus.Locked -> 0xffffd040.toInt()
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
        val w = 120 * density
        val ht = 56 * density
        val left = 10 * density
        val bottom = height - 10 * density
        val top = bottom - ht
        canvas.drawRect(left, top, left + w, bottom, histogramBack)
        val bins = h.luma.size
        val peak = maxOf(1, (0 until bins).maxOf { maxOf(h.luma[it], h.r[it], h.g[it], h.b[it]) })
        val scale = ht / Math.sqrt(peak.toDouble()).toFloat()
        val step = w / bins
        val path = android.graphics.Path().apply { moveTo(left, bottom) }
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

    private fun grid(canvas: Canvas, w: Float, h: Float, a: Float, b: Float) {
        for (f in floatArrayOf(a, b)) {
            canvas.drawLine(w * f, 0f, w * f, h, guidePaint)
            canvas.drawLine(0f, h * f, w, h * f, guidePaint)
        }
    }
}
