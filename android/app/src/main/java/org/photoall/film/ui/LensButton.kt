package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A lens on the viewfinder: a round button with its 35 mm equivalent focal length
 * ("24" over "mm"). The lens in use is a light disc with dark figures; the others are
 * dark glass with light figures, a little smaller. Colours are fixed (it sits on the
 * picture, which no theme tints); figures are sized in dp, capped for large fonts, so
 * they always fit the circle.
 */
class LensButton(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fontScale = min(resources.configuration.fontScale, 1.15f)
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 12.5f * density * fontScale
    }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 7.5f * density * fontScale
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mm = ""
    private var active = false
    private var t = 0f // 0 idle .. 1 in use (animated)
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        Style.pressable(this)
    }

    /** Show [focalMm]; [inUse] lights the button (animated when it changes). */
    fun show(focalMm: Int, inUse: Boolean) {
        val text = focalMm.toString()
        if (text != mm) { mm = text; invalidate() }
        contentDescription = "$focalMm 毫米" + if (inUse) "，使用中" else ""
        if (inUse == active) return
        active = inUse
        animator?.cancel()
        animator = ValueAnimator.ofFloat(t, if (inUse) 1f else 0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * (0.86f + 0.14f * t)
        disc.color = mix(0x80151515.toInt(), 0xf2e6e4dc.toInt(), t)
        canvas.drawCircle(cx, cy, r, disc)
        val ink = mix(0xfff2f0ea.toInt(), 0xff1c1c1a.toInt(), t)
        number.color = ink
        unit.color = ink
        val nh = number.descent() - number.ascent()
        val uh = unit.descent() - unit.ascent()
        val top = cy - (nh + uh) / 2 + 1.5f * density
        canvas.drawText(mm, cx, top - number.ascent(), number)
        canvas.drawText("mm", cx, top + nh - unit.ascent() - 2.5f * density, unit)
    }

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = (((a ushr s) and 0xff) + (((b ushr s) and 0xff) - ((a ushr s) and 0xff)) * k).roundToInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
