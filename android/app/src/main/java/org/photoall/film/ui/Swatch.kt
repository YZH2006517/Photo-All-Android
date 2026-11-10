package org.photoall.film.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * A small picture of a paper or a LUT, beside the cartridge that stands for a film:
 * a paper is a little print (its base white and the way it renders a scene: warm,
 * neutral, cool, punchy; a straight scan is a strip of orange negative); a LUT is three
 * colour chips taken from its name, or a crossed ring for none.
 */
class Swatch(context: Context) : View(context) {
    enum class Kind { PAPER, SCAN, LUT, NONE }

    private var kind = Kind.NONE
    private var name = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    fun show(kind: Kind, name: String = "") {
        if (kind == this.kind && name == this.name) return
        this.kind = kind
        this.name = name
        contentDescription = name
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // a 3:4 card centred in the view
        val cw = min(w, h * 0.78f)
        val ch = cw / 0.78f
        val l = (w - cw) / 2
        val t = (h - ch) / 2
        val r = 3 * density
        paint.shader = null
        when (kind) {
            Kind.PAPER -> {
                val (base, sky, ground) = paperLook(name)
                paint.color = base
                rect.set(l, t, l + cw, t + ch); canvas.drawRoundRect(rect, r, r, paint)
                val m = cw * 0.12f
                rect.set(l + m, t + m, l + cw - m, t + ch - m * 2.2f)
                paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, sky, ground, Shader.TileMode.CLAMP)
                canvas.drawRect(rect, paint)
                paint.shader = null
            }
            Kind.SCAN -> {
                paint.color = 0xffb8642a.toInt()
                rect.set(l, t, l + cw, t + ch); canvas.drawRoundRect(rect, r, r, paint)
                paint.color = 0xff3a1d0c.toInt()
                var y = t + ch * 0.08f
                while (y < t + ch * 0.9f) {
                    rect.set(l + cw * 0.08f, y, l + cw * 0.2f, y + ch * 0.06f); canvas.drawRoundRect(rect, 1f, 1f, paint)
                    rect.set(l + cw * 0.8f, y, l + cw * 0.92f, y + ch * 0.06f); canvas.drawRoundRect(rect, 1f, 1f, paint)
                    y += ch * 0.13f
                }
                paint.color = 0xff8a4a20.toInt()
                rect.set(l + cw * 0.27f, t + ch * 0.1f, l + cw * 0.73f, t + ch * 0.9f); canvas.drawRect(rect, paint)
            }
            Kind.LUT -> {
                val seed = abs(name.hashCode())
                val gap = cw * 0.06f
                val chip = (ch - 2 * gap) / 3
                for (i in 0 until 3) {
                    val hue = ((seed % 360) + i * (40 + seed % 50)) % 360f
                    paint.color = Color.HSVToColor(floatArrayOf(hue, 0.45f + 0.1f * i, 0.85f - 0.12f * i))
                    rect.set(l, t + i * (chip + gap), l + cw, t + i * (chip + gap) + chip)
                    canvas.drawRoundRect(rect, r, r, paint)
                }
            }
            Kind.NONE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * density
                paint.color = 0xaaffffff.toInt()
                val rr = min(cw, ch) * 0.38f
                canvas.drawCircle(w / 2, h / 2, rr, paint)
                canvas.drawLine(w / 2 - rr * 0.7f, h / 2 + rr * 0.7f, w / 2 + rr * 0.7f, h / 2 - rr * 0.7f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    /** A paper's base white and how it renders a scene (sky to ground). */
    private fun paperLook(paper: String): Triple<Int, Int, Int> {
        val p = paper.lowercase()
        return when {
            "b/w" in p || "bw" in p || "black" in p -> Triple(0xfff2f0ec.toInt(), 0xffc9c9c6.toInt(), 0xff3b3b3a.toInt()) // b&w paper: greys
            "2383" in p || "2393" in p -> Triple(0xff1a1a1a.toInt(), 0xff2e6f86.toInt(), 0xffc8762e.toInt()) // cine print: teal and orange
            "crystal" in p -> Triple(0xfff4f6f6.toInt(), 0xff7fb0d6.toInt(), 0xff5f8a5a.toInt())          // cool
            "supra" in p -> Triple(0xfff6f3ec.toInt(), 0xff95b8cf.toInt(), 0xff8a7a58.toInt())            // neutral, soft
            "ultra" in p -> Triple(0xfff6f3ec.toInt(), 0xff3f86c9.toInt(), 0xffc6532b.toInt())            // punchy
            "portra" in p -> Triple(0xfff7f1e6.toInt(), 0xffa9c1cf.toInt(), 0xffc59a6a.toInt())           // soft, warm skin
            else -> Triple(0xfff7f2e8.toInt(), 0xff9cbcd2.toInt(), 0xffb88a52.toInt())                    // endura: warm
        }
    }
}
