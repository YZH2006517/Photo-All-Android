package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.hypot
import kotlin.math.min

/**
 * Offered when the phone is turned: a quarter disc of tinted, frosted glass in the
 * viewfinder's lower left corner, glowing in the theme's accent, with a phone in it that
 * turns to the new way up, its name, and a lit edge that runs down over the seconds the offer
 * stays. Tapping it switches the layout. The glass stays in its corner; only what is drawn on
 * it turns upright for the user ([contentRotation]).
 */
class RotateHint(context: Context) : View(context) {
    var label = ""
        set(value) { field = value; contentDescription = "切换到$value"; invalidate() }
    /** True when the offer is for landscape (the phone in the icon ends up lying down). */
    var toLandscape = true
    /** The icon and its name, turned upright for the way the phone is held. */
    var contentRotation = 0f
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(7 * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11 * density
        isFakeBoldText = true
    }
    // frost: a fine fixed grain over the tint
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val n = 64
        val px = IntArray(n * n) { val v = (Math.random() * 255).toInt(); Color.argb(18, v, v, v) }
        shader = BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val rect = RectF()
    private val path = Path()
    private val sector = Path()
    private var turn = 0f      // 0..1: the phone in the icon turning
    private var remaining = 1f // the lit edge
    private var breath = 1f    // the glow breathing while it waits
    private val animators = mutableListOf<ValueAnimator>()

    init {
        isClickable = true
        visibility = GONE
        // the glow is drawn past the glass; the parent must not clip it
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Spreads out of the corner, turns its phone, and runs its edge down over [millis]. */
    fun appear(millis: Long) {
        animators.forEach { it.cancel() }
        animators.clear()
        visibility = VISIBLE
        pivotX = 0f
        pivotY = height.toFloat().takeIf { it > 0f } ?: (96 * density)
        alpha = 0f
        scaleX = 0.4f
        scaleY = 0.4f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(OvershootInterpolator(1.4f)).start()
        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            startDelay = 180
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { turn = it.animatedValue as Float; invalidate() }
            start()
        }
        animators += ValueAnimator.ofFloat(1f, 0f).apply {
            duration = millis
            interpolator = LinearInterpolator()
            addUpdateListener { remaining = it.animatedValue as Float; invalidate() }
            start()
        }
        animators += ValueAnimator.ofFloat(0.7f, 1f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { breath = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun dismiss() {
        if (visibility != VISIBLE) return
        animators.forEach { it.cancel() }
        pivotX = 0f
        pivotY = height.toFloat()
        animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(180).withEndAction { visibility = GONE }.start()
    }

    /** Only the quarter disc takes taps; the square's other corner is the picture's. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && hypot(event.x, height - event.y) > radius()) return false
        return super.onTouchEvent(event)
    }

    private fun radius() = min(width, height).toFloat() - 6 * density

    override fun onDraw(canvas: Canvas) {
        val r = radius()
        val ox = 0f
        val oy = height.toFloat()
        val accent = Style.ACCENT
        sector.reset()
        sector.moveTo(ox, oy)
        rect.set(ox - r, oy - r, ox + r, oy + r)
        sector.arcTo(rect, -90f, 90f, false)
        sector.close()
        // the tinted glass: the accent strongest at the corner, fading out to smoked glass
        paint.style = Paint.Style.FILL
        val lit = lighten(accent, 0.25f)
        paint.shader = RadialGradient(ox, oy, r, intArrayOf(
            withAlpha(lit, 0.8f * breath), withAlpha(accent, 0.52f), withAlpha(accent, 0.28f), withAlpha(0xff121214.toInt(), 0.4f)),
            floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(sector, paint)
        paint.shader = null
        // frost: a milky veil and a grain
        paint.color = 0x24ffffff
        canvas.drawPath(sector, paint)
        canvas.drawPath(sector, grain)
        // the glowing edge, and a crisp line on it
        glow.color = withAlpha(lighten(accent, 0.35f), breath)
        glow.strokeWidth = 7 * density
        rect.set(ox - r, oy - r, ox + r, oy + r)
        canvas.drawArc(rect, -90f, 90f, false, glow)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = 0x80ffffff.toInt()
        canvas.drawArc(rect, -90f, 90f, false, paint)
        // the edge running down: the time the offer has left
        paint.strokeWidth = 2.5f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xfff2f0ea.toInt()
        val inset = 5 * density
        rect.set(ox - r + inset, oy - r + inset, ox + r - inset, oy + r - inset)
        canvas.drawArc(rect, -90f, 90f * remaining, false, paint)

        // the phone and its name, upright for the user, in the glass's middle
        val cx = r * 0.4f
        val cy = oy - r * 0.44f
        canvas.save()
        canvas.rotate(contentRotation, cx, cy)
        val s = r * 0.3f
        canvas.save()
        val from = if (toLandscape) 0f else 90f
        canvas.rotate(from + (if (toLandscape) 90f else -90f) * turn, cx, cy - 4 * density)
        rect.set(cx - s * 0.36f, cy - 4 * density - s * 0.58f, cx + s * 0.36f, cy - 4 * density + s * 0.58f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        paint.color = 0xfff2f0ea.toInt()
        canvas.drawRoundRect(rect, 4 * density, 4 * density, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, rect.bottom - 3.5f * density, 1.1f * density, paint)
        canvas.restore()
        // a curved arrow round it
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = 0xccf2f0ea.toInt()
        val ar = s * 0.9f
        rect.set(cx - ar, cy - 4 * density - ar, cx + ar, cy - 4 * density + ar)
        canvas.drawArc(rect, 200f, 70f, false, paint)
        val ax = cx
        val ay = cy - 4 * density - ar
        path.reset()
        path.moveTo(ax - 4 * density, ay - 3 * density)
        path.lineTo(ax, ay)
        path.lineTo(ax - 4 * density, ay + 3 * density)
        canvas.drawPath(path, paint)
        text.color = 0xfff2f0ea.toInt()
        text.setShadowLayer(3 * density, 0f, 0f, 0x99000000.toInt())
        canvas.drawText(label, cx, cy + s * 0.58f + 12 * density, text)
        canvas.restore()
    }

    /** [color] mixed toward white by [k]: the glow reads as light, also from a dark accent. */
    private fun lighten(color: Int, k: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * k).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00ffffff) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}
