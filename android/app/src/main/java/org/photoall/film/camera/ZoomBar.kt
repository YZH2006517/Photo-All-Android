package org.photoall.film.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import org.photoall.film.ui.Style
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The zoom as a ruler, for a finger sliding along the lens buttons: a fixed needle in the
 * middle, the focal lengths on a log scale moving under it (slide left to go longer). The
 * lenses are long ticks with their numbers; the classic primes (28, 35, 50, 85, 135) are marked
 * small between them. Above the needle, the focal length the picture is at.
 */
class ZoomBar(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val box = RectF()
    private val path = Path()

    /** The bound camera's zoom range shown, its lens stops (zoom to focal length), and and how the zoom reads in millimetres. */
    var lo = 1f; var hi = 2f
    var stops: List<Pair<Float, Int>> = emptyList()
    /** Millimetres (35 mm equivalent) per unit of this camera's zoom. */
    var mmPerZoom = 24f
    var zoom = 1f
        set(v) { field = v.coerceIn(lo, hi); invalidate() }

    /** One doubling of the focal length, in pixels along the ruler. */
    val octave get() = 62 * d

    private fun xOf(z: Float) = width / 2f + (ln(z / zoom) / ln(2f)) * octave

    /** The zoom a drag of [dx] pixels from [from] lands on. */
    fun dragged(from: Float, dx: Float) = (from * 2f.pow(-dx / octave)).coerceIn(lo, hi)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // the readout floats above; the plate below it: a dark glass pill
        val top = READOUT * d
        val gh = h - top
        box.set(0f, top, w, h)
        fill.color = 0xb3101010.toInt()
        c.drawRoundRect(box, gh / 2, gh / 2, fill)
        val base = top + gh * 0.80f
        fun fade(x: Float) = (1f - abs(x - w / 2) / (w / 2 - gh * 0.35f)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

        // fine ticks: six to a doubling, from the low end up
        var z = lo
        val step = 2f.pow(1f / 6f)
        while (z <= hi * 1.001f) {
            val x = xOf(z)
            val a = fade(x)
            if (a > 0f) {
                tick.color = white((a * 0.45f * 255).toInt()); tick.strokeWidth = 1.2f * d
                c.drawLine(x, base - 6 * d, x, base, tick)
            }
            z *= step
        }
        // the classic primes, small, where no lens sits close
        num.textSize = 9.5f * d
        for (mm in intArrayOf(28, 35, 50, 85, 135, 200)) {
            val zz = zoomFor(mm)
            if (zz < lo || zz > hi || stops.any { abs(ln(it.first / zz)) < 0.18f }) continue
            val x = xOf(zz); val a = fade(x)
            if (a <= 0f) continue
            tick.color = white((a * 0.7f * 255).toInt()); tick.strokeWidth = 1.4f * d
            c.drawLine(x, base - 9 * d, x, base, tick)
            num.color = white((a * 0.55f * 255).toInt())
            c.drawText(mm.toString(), x, base - 12 * d, num)
        }
        // the lenses: long ticks, their numbers bold
        num.textSize = 11.5f * d
        for ((zz, mm) in stops) {
            val x = xOf(zz); val a = fade(x)
            if (a <= 0f) continue
            tick.color = white((a * 255).toInt()); tick.strokeWidth = 2f * d
            c.drawLine(x, base - 12 * d, x, base, tick)
            num.color = white((a * 0.95f * 255).toInt())
            c.drawText(mm.toString(), x, base - 15 * d, num)
        }
        // the needle, and where the picture is
        val cx = w / 2
        tick.color = Style.ACCENT; tick.strokeWidth = 2.4f * d
        c.drawLine(cx, base - 16 * d, cx, base + 3 * d, tick)
        path.reset(); path.moveTo(cx - 4.5f * d, h - 2.5f * d); path.lineTo(cx + 4.5f * d, h - 2.5f * d); path.lineTo(cx, h - 7.5f * d); path.close()
        fill.color = Style.ACCENT; c.drawPath(path, fill)
        val label = (zoom * mmPerZoom).roundToInt().toString()
        big.textSize = 15f * d; big.color = Style.ACCENT
        small.textSize = 9f * d; small.color = Style.ACCENT
        val bw = big.measureText(label); val sw = small.measureText("mm")
        val left = cx - (bw + 2 * d + sw) / 2
        // above the ruler, clear of its numbers: a small callout pointing down at the needle
        val pillBottom = top - 6 * d
        box.set(left - 10 * d, pillBottom - 26 * d, left + bw + 2 * d + sw + 10 * d, pillBottom)
        fill.color = 0xe6121212.toInt()
        c.drawRoundRect(box, 13 * d, 13 * d, fill)
        path.reset(); path.moveTo(cx - 5 * d, pillBottom - 0.5f * d); path.lineTo(cx + 5 * d, pillBottom - 0.5f * d); path.lineTo(cx, top - 1 * d); path.close()
        c.drawPath(path, fill)
        val ty = pillBottom - 8.5f * d
        big.textAlign = Paint.Align.LEFT
        c.drawText(label, left, ty, big)
        c.drawText("mm", left + bw + 2 * d, ty, small)
    }

    private fun zoomFor(mm: Int) = mm / mmPerZoom

    companion object {
        /** The room above the ruler for the readout, in dp; the view is this much taller than the ruler. */
        const val READOUT = 36f
    }
    private fun white(a: Int) = (a.coerceIn(0, 255) shl 24) or 0xffffff
}
