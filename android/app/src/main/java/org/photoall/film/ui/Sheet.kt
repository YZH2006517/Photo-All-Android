package org.photoall.film.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A panel that rises from the bottom over a dimmed screen: a handle, a title, a close
 * button and the caller's [content]. Drag the handle down, tap outside or press back
 * to close it. Built on a Dialog so it sits above the camera's GL surface.
 *
 * In landscape it comes in from the left instead, the screen's full height and under
 * half its width, so the picture stays in view (and barely dimmed) while settings change;
 * swipe it back to the left to close it.
 */
class Sheet(context: Context, title: String, content: View, header: View? = null,
            /** In portrait, a fixed share of the screen's height (0: fit the content, up to 3/4). */
            private val fixedHeight: Float = 0f) : Dialog(context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()
    private val panel: LinearLayout
    private var closing = false
    private val side = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val dimTo = if (side) 0.22f else 0.45f

    init {
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(if (side) 14f else 10f), dp(16f), dp(16f))
            background = GradientDrawable().apply {
                setColor(Style.SURFACE)
                val r = dp(22f).toFloat()
                cornerRadii = if (side) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            elevation = dp(12f).toFloat()
            isClickable = true
            if (side) {
                setOnTouchListener(SwipeToClose())
                // clear of a camera cutout on the left edge
                setOnApplyWindowInsetsListener { v, insets ->
                    val cut = insets.getInsets(android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.systemBars())
                    v.setPadding(dp(16f) + cut.left, dp(14f) + cut.top, dp(16f), dp(12f) + cut.bottom)
                    insets
                }
            }
        }
        val handle = FrameLayout(context).apply {
            addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2f).toFloat(); setColor(Style.HAIRLINE) }
            }, FrameLayout.LayoutParams(dp(36f), dp(4f), Gravity.CENTER))
            setOnTouchListener(DragToClose())
        }
        if (!side) panel.addView(handle, LinearLayout.LayoutParams(-1, dp(20f)))
        panel.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 17f
                typeface = Style.medium
                setTextColor(Style.TEXT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                contentDescription = "关闭"
                setImageDrawable(CloseIcon(Style.TEXT))
                background = Style.surface(context, fill = Style.SURFACE_2, radiusDp = 18f)
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
        // A fixed part above the scrolling one (a search field stays in view).
        if (header != null) panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        // At most three quarters of the screen; longer content scrolls. From the side: the
        // full height, the rest scrolling under the title.
        val cap = (context.resources.displayMetrics.heightPixels * 0.75f).toInt()
        panel.addView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                if (side || fixedHeight > 0f) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                else super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }.apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = side
            setFadingEdgeLength(dp(18f))
            addView(content)
        }, if (side || fixedHeight > 0f) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -2))
        setContentView(panel)
        setCanceledOnTouchOutside(true)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (side) {
                val metrics = context.resources.displayMetrics
                val width = minOf((metrics.widthPixels * 0.46f).toInt(), dp(430f)).coerceAtLeast(dp(320f))
                setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.START or Gravity.TOP)
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                setDecorFitsSystemWindows(false)
                insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.statusBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    if (fixedHeight > 0f) (context.resources.displayMetrics.heightPixels * fixedHeight).toInt()
                    else ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply { windowAnimations = 0 } // the panel animates itself
        }
    }

    /** Rises from below while the screen behind dims. */
    override fun show() {
        super.show()
        panel.alpha = 0f
        panel.post {
            if (side) panel.translationX = -panel.width.toFloat() else panel.translationY = panel.height.toFloat()
            panel.alpha = 1f
            panel.animate().translationX(0f).translationY(0f).setDuration(if (side) 300 else 280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
            dim(0f, dimTo, 280)
        }
    }

    /** Sinks away, then closes. */
    override fun dismiss() {
        if (closing || !isShowing) return super.dismiss()
        closing = true
        dim(dimTo, 0f, 200)
        val away = panel.animate().setDuration(200)
        if (side) away.translationX(-panel.width.toFloat()) else away.translationY(panel.height.toFloat())
        away
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.6f))
            .withEndAction { super.dismiss() }.start()
    }

    private fun dim(from: Float, to: Float, ms: Long) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            addUpdateListener { window?.setDimAmount(it.animatedValue as Float) }
            start()
        }
    }

    /** The handle: dragging the panel down past a third of its height closes it. */
    /** From the side: a swipe back to the left past a quarter of its width closes it. */
    private inner class SwipeToClose : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    if (!dragging && dx < -dp(12f) && Math.abs(dx) > Math.abs(e.rawY - startY)) dragging = true
                    if (dragging) panel.translationX = dx.coerceAtMost(0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    if (-panel.translationX > panel.width / 4f) dismiss()
                    else panel.animate().translationX(0f).setDuration(160).start()
                }
            }
            return dragging
        }
    }

    private inner class DragToClose : View.OnTouchListener {
        private var startY = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val panel = v.parent as View
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = e.rawY
                MotionEvent.ACTION_MOVE -> panel.translationY = (e.rawY - startY).coerceAtLeast(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (panel.translationY > panel.height / 4f) dismiss()
                    else panel.animate().translationY(0f).setDuration(160).start()
                }
            }
            return true
        }
    }

    private class CloseIcon(color: Int) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            paint.strokeWidth = b.width() / 9f
            canvas.drawLine(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
            canvas.drawLine(b.right.toFloat(), b.top.toFloat(), b.left.toFloat(), b.bottom.toFloat(), paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
