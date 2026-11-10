package org.photoall.film.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The shutter's face while a LUT is being picked: a ring round it whose lit arc is the LUT's
 * strength (from the top, clockwise), ticks every tenth, and the figure in the middle.
 * [reveal] sweeps it in and out (the arc grows from nothing, the figure fades); [active]
 * (0..1, while the finger turns it) thickens the arc and lights a glow under it.
 */
class StrengthDial(private val density: Float) : Drawable() {
    var strength = 0.8f
        set(v) { field = v.coerceIn(0f, 1f); invalidateSelf() }
    var reveal = 0f
        set(v) { field = v; invalidateSelf() }
    var active = 0f
        set(v) { field = v; invalidateSelf() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(6 * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        if (reveal <= 0.001f) return
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = min(b.width(), b.height()) / 2f - 4.5f * density
        val accent = Style.ACCENT
        // a veil over the button: the release is now a dial
        face.color = withAlpha(0xff101012.toInt(), 0.55f * reveal)
        canvas.drawCircle(cx, cy, r - 6 * density, face)
        // ticks every tenth
        ring.strokeWidth = 1.2f * density
        ring.color = withAlpha(0xfff2f0ea.toInt(), 0.45f * reveal)
        for (i in 0 until 10) {
            val a = Math.toRadians(-90.0 + i * 36.0)
            val r0 = r - 1.5f * density
            val r1 = r + 1.5f * density
            canvas.drawLine(cx + (r0 * Math.cos(a)).toFloat(), cy + (r0 * Math.sin(a)).toFloat(),
                cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(), ring)
        }
        // the track and the lit arc (it sweeps in with the reveal)
        val w = (3 + 2.5f * active) * density
        oval.set(cx - r, cy - r, cx + r, cy + r)
        ring.strokeWidth = w
        ring.color = withAlpha(0xfff2f0ea.toInt(), 0.18f * reveal)
        canvas.drawArc(oval, 0f, 360f, false, ring)
        val sweep = 360f * strength * reveal
        if (active > 0.01f) {
            glow.strokeWidth = w + 6 * density
            glow.color = withAlpha(accent, 0.7f * active)
            canvas.drawArc(oval, -90f, sweep, false, glow)
        }
        ring.color = withAlpha(accent, reveal)
        canvas.drawArc(oval, -90f, sweep, false, ring)
        // the figure, and what it is
        text.color = withAlpha(0xfff2f0ea.toInt(), reveal)
        text.textSize = (17 + 4 * active) * density
        canvas.drawText("${(strength * 100).roundToInt()}%", cx, cy + 3 * density, text)
        text.textSize = 9 * density
        text.color = withAlpha(0xfff2f0ea.toInt(), 0.7f * reveal)
        canvas.drawText("LUT 强度", cx, cy + 16 * density, text)
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00ffffff) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
