package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.floor

/**
 * Manual focus in landscape: a vertical rail of smoked glass along the picture's right edge,
 * infinity at the top and near at the bottom, a thumb to drag, the distance beside it, and a
 * peaking switch under it. [value] is 0 (infinity) .. 1 (nearest).
 *
 * Held, it comes alive: the thumb swells and glows, the glass brightens, and the ticks near
 * the thumb stand out longer and brighter, like a scale under a loupe. Dragging clicks at
 * each fine step ([onDetent] 0), harder at the ten marked ones (1) and at either end (2).
 * Fixed light-on-glass colours: it sits on the picture.
 */
class FocusRail(context: Context) : View(context) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var label = ""
        set(v) { field = v; invalidate() }
    var peaking = false
        set(v) { field = v; invalidate() }
    var onChange: (Float) -> Unit = {}
    var onPeak: () -> Unit = {}
    /** A detent passed while dragging: 0 a fine step, 1 a marked step, 2 an end. */
    var onDetent: (Int) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(9 * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11 * density
        isFakeBoldText = true
        setShadowLayer(3 * density, 0f, 0f, 0x99000000.toInt())
    }
    private val rect = RectF()
    private var dragging = false
    private var active = 0f           // 0 resting .. 1 held: drives the swell and the glow
    private var activeAnim: ValueAnimator? = null
    private var lastStep = -1         // the fine step the thumb was last on

    init {
        contentDescription = "手动对焦：上下拖动，上为无穷远，下为近处"
        setLayerType(LAYER_TYPE_HARDWARE, null) // the halo's blur
    }

    // the track's ends, leaving room for "∞" above, "近" and the peaking switch below
    private fun top() = 34 * density
    private fun bottom() = height - 58 * density

    override fun onDraw(canvas: Canvas) {
        val cx = width - 22 * density
        val t = top()
        val b = bottom()
        val y = t + (b - t) * value
        // smoked glass capsule behind the rail, a little lighter while held
        paint.style = Paint.Style.FILL
        paint.color = mix(0x8c121214.toInt(), 0xa6242428.toInt(), active)
        rect.set(cx - 15 * density, t - 32 * density, cx + 15 * density, b + 18 * density)
        canvas.drawRoundRect(rect, 15 * density, 15 * density, paint)
        // ticks: near the thumb they grow and brighten (more so while held)
        for (i in 0..STEPS) {
            val major = i % (STEPS / 10) == 0
            if (!major && active < 0.05f) continue // fine ticks only show while held
            val ty = t + (b - t) * i / STEPS
            val near = (1f - abs(ty - y) / (40 * density)).coerceIn(0f, 1f) * (0.4f + 0.6f * active)
            val base = if (major) 7f else 3.5f * active
            val w = (base + 5f * near) * density
            paint.color = withAlpha(0xfff2f0ea.toInt(), (if (major) 0.5f else 0.3f * active) + 0.5f * near)
            rect.set(cx - w, ty - 0.5f * density, cx + w, ty + 0.5f * density)
            canvas.drawRect(rect, paint)
        }
        // the track
        paint.color = 0x59f2f0ea
        rect.set(cx - 1.5f * density, t, cx + 1.5f * density, b)
        canvas.drawRoundRect(rect, density, density, paint)
        // the part already turned toward near, in the accent
        paint.color = withAlpha(Style.ACCENT, 0.35f + 0.4f * active)
        rect.set(cx - 1.5f * density, t, cx + 1.5f * density, y)
        canvas.drawRoundRect(rect, density, density, paint)
        // ends
        text.color = 0xfff2f0ea.toInt()
        canvas.drawText("∞", cx, t - 14 * density, text)
        canvas.drawText("近", cx, b + 14 * density, text)
        // the thumb: a glow under it while held, and it swells
        val accent = if (isEnabled) Style.ACCENT else 0xff8a867e.toInt()
        val hw = (11 + 4 * active) * density
        val hh = (4 + 1.5f * active) * density
        if (active > 0.01f) {
            halo.color = withAlpha(accent, 0.75f * active.coerceAtMost(1f))
            rect.set(cx - hw - 4 * density, y - hh - 4 * density, cx + hw + 4 * density, y + hh + 4 * density)
            canvas.drawRoundRect(rect, hh + 4 * density, hh + 4 * density, halo)
        }
        paint.color = accent
        rect.set(cx - hw, y - hh, cx + hw, y + hh)
        canvas.drawRoundRect(rect, hh, hh, paint)
        paint.color = withAlpha(0xffffffff.toInt(), 0.6f)
        rect.set(cx - hw * 0.45f, y - 0.6f * density, cx + hw * 0.45f, y + 0.6f * density)
        canvas.drawRect(rect, paint)
        // the distance, to the thumb's left on a small glass pill (larger while held)
        if (label.isNotEmpty()) {
            text.textSize = (11 + 2 * active) * density
            val tw = text.measureText(label) + 12 * density
            val right = cx - hw - 7 * density
            val ph = (10 + 2 * active) * density
            rect.set(right - tw, y - ph, right, y + ph)
            paint.color = mix(0x99121214.toInt(), 0xcc121214.toInt(), active)
            canvas.drawRoundRect(rect, ph, ph, paint)
            canvas.drawText(label, rect.centerX(), y + text.textSize * 0.36f, text)
            text.textSize = 11 * density
        }
        // the peaking switch at the foot
        val py = height - 18 * density
        rect.set(cx - 18 * density, py - 12 * density, cx + 18 * density, py + 12 * density)
        paint.color = if (peaking) Style.ACCENT else 0x73121214
        canvas.drawRoundRect(rect, 12 * density, 12 * density, paint)
        text.textSize = 10 * density
        canvas.drawText("峰值", cx, py + 3.5f * density, text)
        text.textSize = 11 * density
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // only the right part of the view is the rail (the label area lets taps through)
                if (e.x < width - 44 * density) return false
                // the foot toggles peaking; the rest of the column is the rail
                if (e.y > height - 34 * density) { onPeak(); return true }
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                lastStep = step(value)
                animateActive(1f)
                move(e.y)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) move(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                animateActive(0f)
            }
        }
        return true
    }

    private fun step(v: Float) = floor(v * STEPS + 0.5f).toInt()

    private fun move(y: Float) {
        val v = ((y - top()) / (bottom() - top())).coerceIn(0f, 1f)
        if (v == value) return
        value = v
        onChange(v)
        val s = step(v)
        if (s != lastStep) {
            lastStep = s
            onDetent(when {
                s == 0 || s == STEPS -> 2
                s % (STEPS / 10) == 0 -> 1
                else -> 0
            })
        }
    }

    private fun animateActive(to: Float) {
        activeAnim?.cancel()
        activeAnim = ValueAnimator.ofFloat(active, to).apply {
            duration = if (to > active) 220 else 320
            interpolator = if (to > active) OvershootInterpolator(1.6f) else android.view.animation.DecelerateInterpolator()
            addUpdateListener { active = (it.animatedValue as Float).coerceAtLeast(0f); invalidate() }
            start()
        }
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00ffffff) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun mix(a: Int, b: Int, k: Float): Int {
        val t = k.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xff
            val y = (b shr shift) and 0xff
            return (x + (y - x) * t).toInt() and 0xff
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private companion object {
        const val STEPS = 40 // fine detents along the rail; every fourth is marked
    }
}
