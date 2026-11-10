package org.photoall.film.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A strip of looks laid over the viewfinder's lower edge: scroll it and the one in the
 * middle is chosen (it grows and brightens, the others recede); it snaps to the nearest
 * when let go, and a tap brings one to the middle. [onHover] reports each new middle
 * while scrolling (for a tick and a live preview), [onSettle] the one it came to rest on.
 * Colours are fixed: it sits on the picture.
 */
class LookPicker(context: Context) : HorizontalScrollView(context) {
    class Item(val title: String, val icon: (Context) -> View)

    var onHover: (Int) -> Unit = {}
    var onSettle: (Int) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val itemW = (70 * density).roundToInt()
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private var count = 0
    var centered = -1
        private set
    private var lastX = -1
    private val watch = object : Runnable {
        override fun run() {
            // wait for a fling to finish, then snap to the nearest look and settle on it
            if (scrollX != lastX) { lastX = scrollX; postDelayed(this, 50); return }
            val target = centered.coerceIn(0, count - 1) * itemW
            if (scrollX != target) { smoothScrollTo(target, 0); lastX = -1; postDelayed(this, 120); return }
            onSettle(centered)
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /** New looks, with [selected] in the middle. */
    fun show(items: List<Item>, selected: Int) {
        row.removeAllViews()
        count = items.size
        for ((i, item) in items.withIndex()) {
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                contentDescription = item.title
                addView(item.icon(context), LinearLayout.LayoutParams((34 * density).roundToInt(), (44 * density).roundToInt()))
                addView(TextView(context).apply {
                    text = item.title
                    textSize = 10f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(0xfff2f0ea.toInt())
                    setShadowLayer(4f, 0f, 0f, 0xcc000000.toInt())
                    setPadding(0, (5 * density).roundToInt(), 0, 0)
                }, LinearLayout.LayoutParams(itemW - (6 * density).roundToInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
                setOnClickListener { smoothScrollTo(i * itemW, 0); lastX = -1; removeCallbacks(watch); postDelayed(watch, 120) }
            }, LinearLayout.LayoutParams(itemW, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        centered = selected.coerceIn(0, maxOf(0, count - 1))
        post {
            scrollTo(centered * itemW, 0)
            shade()
        }
    }

    /** Straight to look [index] (a category's first): a quick fade, no fling past the others. */
    fun jumpTo(index: Int) {
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        removeCallbacks(watch)
        animate().cancel()
        animate().alpha(0f).setDuration(90).withEndAction {
            scrollTo(target * itemW, 0)
            shade()
            bloom(10 * density, quick = true)
            animate().alpha(1f).setDuration(180).start()
            lastX = -1
            postDelayed(watch, 60)
        }.start()
    }

    /** The looks rise into place, the middle one first and the rest spreading out from it. */
    fun bloom(rise: Float, quick: Boolean = false) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            v.animate().cancel()
            val d = abs(i - centered)
            if (d > 4) { v.translationY = 0f; continue }
            v.translationY = rise
            v.animate().translationY(0f).setStartDelay((if (quick) 0L else 50L) + d * 32L).setDuration(if (quick) 240L else 380L)
                .setInterpolator(EASE).start()
        }
    }

    /** The looks drop away, the outer ones first. */
    fun sink(fall: Float, ms: Long) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            val d = abs(i - centered)
            if (d > 4) continue
            v.animate().cancel()
            v.animate().translationY(fall).setStartDelay((4 - d) * 14L).setDuration(ms)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // side padding so the first and the last can stand in the middle
        val side = (w - itemW) / 2
        row.setPadding(side, 0, side, 0)
        post { scrollTo(centered.coerceAtLeast(0) * itemW, 0); shade() }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val i = (l.toFloat() / itemW).roundToInt().coerceIn(0, maxOf(0, count - 1))
        if (i != centered && count > 0) {
            centered = i
            onHover(i)
        }
        shade()
    }

    /** Size and brightness by distance from the middle. */
    private fun shade() {
        val mid = scrollX.toFloat() / itemW
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            val d = abs(i - mid).coerceAtMost(2f)
            val s = 1.08f - 0.14f * d
            v.scaleX = s
            v.scaleY = s
            v.alpha = 1f - 0.28f * d
            (v as? LinearLayout)?.getChildAt(1)?.let { label ->
                (label as TextView).setTextColor(if (d < 0.5f) Style.ACCENT else 0xfff2f0ea.toInt())
                label.typeface = if (d < 0.5f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(watch)
            lastX = -1
            postDelayed(watch, 60)
        }
        return handled
    }

    /** A thin mark over the middle, where the chosen look stands. */
    class Marker(context: Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            paint.color = Style.ACCENT
            val d = resources.displayMetrics.density
            canvas.drawRoundRect(width / 2f - 9 * d, 0f, width / 2f + 9 * d, 2.5f * d, d, d, paint)
        }
    }

    companion object {
        /** Emphasised deceleration: quick off the mark, a long soft landing. */
        val EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

        fun panelParams(density: Float) = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
    }
}
