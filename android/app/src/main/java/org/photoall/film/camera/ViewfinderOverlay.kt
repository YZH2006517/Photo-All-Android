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

    /**
     * Rounded picture corners: the GL surface cannot be clipped, so the corners are painted
     * over in [cornerColor] (the well around it). 0 = square.
     */
    var cornerRadius = 0f
        set(value) { field = value; invalidate() }
    var cornerColor = 0xff0b0b0d.toInt()
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cornerPath = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipRect = android.graphics.RectF()
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11.5f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffa8a39b.toInt()
        textSize = 9f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        letterSpacing = 0.06f
    }

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
        if (info.isNotEmpty() && readoutAlpha > 0.01f) faded(canvas, readoutAlpha) { drawReadout(canvas) }
        histogram?.let { h -> if (histogramAlpha > 0.01f) faded(canvas, histogramAlpha) { drawHistogram(canvas, h) } }

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
    /**
     * The live readout as a glass pill: each part's name small and grey ("ISO", "胶片 ISO"),
     * its value in bold figures; "手动" as an amber tag.
     */
    private fun drawReadout(canvas: Canvas) {
        val parts = info.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val pad = 8 * density
        val gap = 10 * density
        // measure
        var width = pad
        val layout = parts.map { part ->
            val split = part.indexOfFirst { it.isDigit() }
            val key = if (split > 0 && !part.startsWith("1/")) part.substring(0, split).trim() else if (part == "手动") part else ""
            val value = if (key == part) "" else part.substring(key.length).trim()
            Triple(key, value, part == "手动")
        }
        for ((i, p) in layout.withIndex()) {
            if (i > 0) width += gap
            if (p.first.isNotEmpty()) width += keyPaint.measureText(p.first) + if (p.second.isNotEmpty()) 3 * density else 0f
            width += valuePaint.measureText(p.second)
        }
        width += pad
        val left = 10 * density
        val top = 10 * density
        chipRect.set(left, top, left + width, top + 22 * density)
        chipPaint.color = 0x8c000000.toInt()
        canvas.drawRoundRect(chipRect, 11 * density, 11 * density, chipPaint)
        chipPaint.style = Paint.Style.STROKE
        chipPaint.strokeWidth = density * 0.8f
        chipPaint.color = 0x22ffffff
        canvas.drawRoundRect(chipRect, 11 * density, 11 * density, chipPaint)
        chipPaint.style = Paint.Style.FILL
        var x = left + pad
        val base = top + 15.5f * density
        for ((i, p) in layout.withIndex()) {
            if (i > 0) {
                chipPaint.color = 0x55ffffff
                canvas.drawCircle(x + gap / 2, top + 11 * density, 1.2f * density, chipPaint)
                x += gap
            }
            if (p.first.isNotEmpty()) {
                keyPaint.color = if (p.third) org.photoall.film.ui.Style.ACCENT else 0xffa8a39b.toInt()
                canvas.drawText(p.first, x, base - 0.5f * density, keyPaint)
                x += keyPaint.measureText(p.first) + if (p.second.isNotEmpty()) 3 * density else 0f
            }
            canvas.drawText(p.second, x, base, valuePaint)
            x += valuePaint.measureText(p.second)
        }
    }

    /**
     * Frosted glass over the picture while the screen turns: a blurred still of it
     * ([frost], centre-cropped), or with none the plain well colour (an empty slot).
     */
    var frost: android.graphics.Bitmap? = null
        set(value) { field = value; invalidate() }
    var frostAlpha = 0f
        set(value) { field = value; invalidate() }
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frostSrc = android.graphics.Rect()
    private val frostDst = android.graphics.RectF()

    private fun drawFrost(canvas: Canvas) {
        if (frostAlpha <= 0f) return
        val a = (frostAlpha * 255).toInt().coerceIn(0, 255)
        val f = frost
        if (f == null || f.isRecycled) {
            frostPaint.color = cornerColor
            frostPaint.alpha = a
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), frostPaint)
            return
        }
        // centre-crop the still into the frame
        val viewAspect = width.toFloat() / height.coerceAtLeast(1)
        val bw = f.width.toFloat()
        val bh = f.height.toFloat()
        if (bw / bh > viewAspect) {
            val cw = bh * viewAspect
            frostSrc.set(((bw - cw) / 2).toInt(), 0, ((bw + cw) / 2).toInt(), f.height)
        } else {
            val ch = bw / viewAspect
            frostSrc.set(0, ((bh - ch) / 2).toInt(), f.width, ((bh + ch) / 2).toInt())
        }
        frostDst.set(0f, 0f, width.toFloat(), height.toFloat())
        frostPaint.color = Color.WHITE
        frostPaint.alpha = a
        canvas.drawBitmap(f, frostSrc, frostDst, frostPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawFrost(canvas)
        if (cornerRadius <= 0f) return
        cornerPath.reset()
        cornerPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), android.graphics.Path.Direction.CW)
        cornerPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
        cornerPaint.color = cornerColor
        canvas.drawPath(cornerPath, cornerPaint)
    }

    /** Where the readout pill is drawn (empty when there is none). */
    fun readoutRect(): android.graphics.RectF =
        if (info.isNotEmpty() && chipRect.width() > 0f) android.graphics.RectF(chipRect) else android.graphics.RectF()

    /** Where the histogram is drawn (empty when it is off). */
    fun histogramRect(): android.graphics.RectF =
        if (histogram != null) android.graphics.RectF(width - 130 * density, 10 * density, width - 10 * density, 66 * density)
        else android.graphics.RectF()

    /** The readout and the histogram give way (fade) while a strip lies over them. */
    var readoutAlpha = 1f
        set(v) { field = v; invalidate() }
    var histogramAlpha = 1f
        set(v) { field = v; invalidate() }

    private inline fun faded(canvas: Canvas, alpha: Float, draw: () -> Unit) {
        if (alpha >= 0.99f) return draw()
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
        draw()
        canvas.restoreToCount(save)
    }

    private fun drawHistogram(canvas: Canvas, h: PreviewMeter.Histogram) {
        val ht = 56 * density
        // top right: the bottom edge holds the lens buttons
        histogramPainter.draw(canvas, h, width - 130 * density, 10 * density, 120 * density, ht)
    }

    private fun grid(canvas: Canvas, w: Float, h: Float, a: Float, b: Float) {
        for (f in floatArrayOf(a, b)) {
            canvas.drawLine(w * f, 0f, w * f, h, guidePaint)
            canvas.drawLine(0f, h * f, w, h * f, guidePaint)
        }
    }
}
