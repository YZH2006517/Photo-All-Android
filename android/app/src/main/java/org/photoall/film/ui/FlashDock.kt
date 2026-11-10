package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The flash modes on a strip of frosted glass laid along the viewfinder's edge: the picture
 * behind it blurred ([frost], a still taken as it opens, with [frostOrigin] where the strip
 * sits on it), a smoked tint and a light rim; the chosen mode on a lit accent plate that
 * slides to a new choice. Horizontal along the top or bottom edge, vertical along a side.
 * Fixed light-on-glass colours: it sits on the picture.
 */
class FlashDock(context: Context, private val vertical: Boolean) : LinearLayout(context) {
    var onPick: (Int) -> Unit = {}
    var onBlocked: (Int) -> Unit = {}
    /** The blurred still of the picture, and the strip's top left on it (in the picture's pixels). */
    var frost: Bitmap? = null
        set(v) { field = v; invalidate() }
    var frostOrigin = android.graphics.PointF()
    /** The picture's size, which the still is stretched to. */
    var frostSize = android.graphics.PointF()

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val radius = 18 * density
    private var plate = RectF()        // the accent plate, where it is now
    private var plateAnim: ValueAnimator? = null
    private var selected = -1
    private var enabledOf: (Int) -> Boolean = { true }
    private val items = mutableListOf<LinearLayout>()

    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        val pad = (5 * density).toInt()
        setPadding(pad, pad, pad, pad)
        setWillNotDraw(false)
        isClickable = true // taps between the items stay on the glass
        elevation = 6 * density
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    fun setModes(names: List<String>, chosen: Int, enabled: (Int) -> Boolean) {
        enabledOf = enabled
        removeAllViews()
        items.clear()
        for ((i, name) in names.withIndex()) {
            val item = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                contentDescription = "闪光：$name"
                addView(ImageView(context).apply {
                    setImageDrawable(Icon(if (i == 0) Icon.Kind.FLASH_OFF else Icon.Kind.FLASH, 0xfff2f0ea.toInt(), 1.8f))
                }, LayoutParams((20 * density).toInt(), (20 * density).toInt()))
                addView(TextView(context).apply {
                    text = name
                    textSize = 11f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTextColor(0xfff2f0ea.toInt())
                    setPadding(0, (4 * density).toInt(), 0, 0)
                })
                alpha = if (enabled(i)) 1f else 0.38f
                setOnClickListener { if (enabledOf(i)) { select(i, animate = true); onPick(i) } else onBlocked(i) }
            }
            items += item
            addView(item, LayoutParams((56 * density).toInt(), (54 * density).toInt()))
        }
        selected = chosen
        post { plate = plateFor(selected); tint(); invalidate() }
    }

    /** Moves the accent plate to mode [i]. */
    fun select(i: Int, animate: Boolean) {
        if (i == selected) return
        selected = i
        val from = RectF(plate)
        val to = plateFor(i)
        plateAnim?.cancel()
        if (!animate || from.isEmpty) { plate = to; tint(); invalidate(); return }
        plateAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val k = it.animatedValue as Float
                plate.set(from.left + (to.left - from.left) * k, from.top + (to.top - from.top) * k,
                    from.right + (to.right - from.right) * k, from.bottom + (to.bottom - from.bottom) * k)
                invalidate()
            }
            start()
        }
        tint()
    }

    /** The modes come in one after another, from the end nearest the flash button. */
    fun stagger(fromStart: Boolean) {
        items.forEachIndexed { i, v ->
            val order = if (fromStart) i else items.lastIndex - i
            val target = if (enabledOf(i)) 1f else 0.38f
            v.alpha = 0f
            if (vertical) v.translationY = -10 * density else v.translationX = -10 * density
            v.animate().alpha(target).translationX(0f).translationY(0f).setStartDelay(80L + order * 35L).setDuration(260)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        }
    }

    private fun plateFor(i: Int): RectF {
        val v = items.getOrNull(i) ?: return RectF()
        val inset = 1.5f * density
        return RectF(v.left + inset, v.top + inset, v.right - inset, v.bottom - inset)
    }

    // the chosen mode's icon and label in the dark of the plate's light
    private fun tint() {
        items.forEachIndexed { i, v ->
            val on = i == selected
            val colour = if (on) 0xff1a1712.toInt() else 0xfff2f0ea.toInt()
            ((v.getChildAt(0)) as ImageView).setImageDrawable(Icon(if (i == 0) Icon.Kind.FLASH_OFF else Icon.Kind.FLASH, colour, 1.8f, filled = on && i != 0))
            ((v.getChildAt(1)) as TextView).apply { setTextColor(colour); typeface = if (on) Style.medium else android.graphics.Typeface.DEFAULT }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (plateAnim?.isRunning != true) plate = plateFor(selected)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        // the picture behind, blurred
        frost?.let { f ->
            canvas.save()
            canvas.translate(-frostOrigin.x, -frostOrigin.y)
            canvas.scale(frostSize.x / f.width, frostSize.y / f.height)
            canvas.drawBitmap(f, 0f, 0f, paint)
            canvas.restore()
        }
        // smoked tint and a little milk: glass, not a window
        paint.style = Paint.Style.FILL
        paint.color = if (frost != null) 0x66101012 else 0xb3101012.toInt()
        canvas.drawRect(rect, paint)
        paint.color = 0x14ffffff
        canvas.drawRect(rect, paint)
        // the accent plate under the chosen mode, with a soft glow
        if (!plate.isEmpty) {
            paint.color = (Style.ACCENT and 0x00ffffff) or 0x55000000
            canvas.drawRoundRect(RectF(plate).apply { inset(-2.5f * density, -2.5f * density) }, radius, radius, paint)
            paint.color = lighten(Style.ACCENT, 0.35f)
            canvas.drawRoundRect(plate, radius - 4 * density, radius - 4 * density, paint)
        }
        canvas.restore()
        // a light rim
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 0.8f
        paint.color = 0x40ffffff
        rect.inset(0.4f * density, 0.4f * density)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun lighten(c: Int, k: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * k).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(ch(android.graphics.Color.red(c)), ch(android.graphics.Color.green(c)), ch(android.graphics.Color.blue(c)))
    }
}
