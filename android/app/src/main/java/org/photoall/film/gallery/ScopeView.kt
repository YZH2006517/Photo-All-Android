package org.photoall.film.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.ln
import kotlin.math.max

/**
 * Over the import's preview: the developed picture's histogram (red, green, blue and
 * luminance) or its waveform (luminance per column, left to right as in the picture, black
 * at the foot). A tap goes histogram → waveform → off (a small tab then, to bring it back).
 */
class ScopeView(context: Context, private val compact: Boolean = false) : View(context) {
    enum class Mode(val label: String) { HISTOGRAM("直方图"), WAVEFORM("波形"), OFF("示波") }

    /** What [measure] makes of a picture: 64 bins each of R, G, B, luminance; a 160 × 64 waveform. */
    class Scope(val r: IntArray, val g: IntArray, val b: IntArray, val luma: IntArray, val wave: IntArray)

    var mode = Mode.HISTOGRAM
        set(v) { field = v; requestLayout(); invalidate() }
    var onModeChanged: (Mode) -> Unit = {}
    private var scope: Scope? = null

    private val d = resources.displayMetrics.density
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xb3000000.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xccffffff.toInt() }
    private val path = Path()
    private val rect = RectF()

    init {
        isClickable = true
        contentDescription = "直方图与波形：点按切换"
        setOnClickListener {
            mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
            onModeChanged(mode)
        }
    }

    /** Main thread: the scope of the picture now shown. */
    fun show(s: Scope?) { scope = s; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // compact: a small chip in a title bar (no caption; the shape says which scope it is)
        if (mode == Mode.OFF) setMeasuredDimension((44 * d).toInt(), ((if (compact) 28 else 24) * d).toInt())
        else if (compact) setMeasuredDimension((104 * d).toInt(), (36 * d).toInt())
        else setMeasuredDimension((168 * d).toInt(), (92 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, 8 * d, 8 * d, box)
        text.textSize = 9.5f * d
        if (mode == Mode.OFF) {
            text.textAlign = Paint.Align.CENTER
            c.drawText(Mode.OFF.label, w / 2, h / 2 + 3.5f * d, text)
            return
        }
        val s = scope ?: return
        val pad = (if (compact) 4 else 6) * d
        val top = pad; val bottom = h - pad - (if (compact) 0f else 10 * d)
        val left = pad; val right = w - pad
        if (mode == Mode.HISTOGRAM) {
            val peak = max(1, listOf(s.r, s.g, s.b).maxOf { a -> a.drop(1).dropLast(1).maxOrNull() ?: 1 })
            fun curve(a: IntArray, colour: Int, filled: Boolean) {
                path.reset()
                path.moveTo(left, bottom)
                for (i in a.indices) {
                    val x = left + (right - left) * i / (a.size - 1)
                    val y = bottom - (bottom - top) * (a[i].toFloat() / peak).coerceAtMost(1f)
                    path.lineTo(x, y)
                }
                path.lineTo(right, bottom); path.close()
                if (filled) { fill.color = colour; c.drawPath(path, fill) }
                else { line.color = colour; line.strokeWidth = 1.2f * d; c.drawPath(path, line) }
            }
            curve(s.r, 0x66ff3b30, true)
            curve(s.g, 0x6634c759, true)
            curve(s.b, 0x660a84ff, true)
            curve(s.luma, 0xddffffff.toInt(), false)
        } else {
            // the waveform: each column's luminance spread, brighter where more of it falls
            val cols = 160; val rows = 64
            val peak = max(1, s.wave.maxOrNull() ?: 1)
            val cw = (right - left) / cols; val rh = (bottom - top) / rows
            for (x in 0 until cols) for (y in 0 until rows) {
                val n = s.wave[x * rows + y]
                if (n == 0) continue
                val a = (ln(1f + n) / ln(1f + peak)).coerceIn(0f, 1f)
                fill.color = ((40 + 215 * a).toInt() shl 24) or 0x9cf0b4
                c.drawRect(left + x * cw, bottom - (y + 1) * rh, left + (x + 1) * cw + 0.5f, bottom - y * rh + 0.5f, fill)
            }
            // guides at 10 %, 50 %, 90 %
            line.color = 0x33ffffff; line.strokeWidth = 0.6f * d
            for (k in floatArrayOf(0.1f, 0.5f, 0.9f)) c.drawLine(left, bottom - (bottom - top) * k, right, bottom - (bottom - top) * k, line)
        }
        if (compact) return
        text.textAlign = Paint.Align.LEFT
        c.drawText(mode.label, left, h - pad + 1 * d, text)
    }

    companion object {
        /** Any thread: the histogram and waveform of [b] (its display values, as shown). */
        fun measure(b: Bitmap): Scope {
            val sw = 160
            val sh = max(1, b.height * sw / max(1, b.width))
            val small = Bitmap.createScaledBitmap(if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (small !== b) small.recycle()
            val r = IntArray(64); val g = IntArray(64); val bl = IntArray(64); val l = IntArray(64)
            val wave = IntArray(160 * 64)
            for (y in 0 until sh) for (x in 0 until sw) {
                val p = px[y * sw + x]
                val cr = (p shr 16) and 255; val cg = (p shr 8) and 255; val cb = p and 255
                val y8 = (cr * 54 + cg * 183 + cb * 19) shr 8
                r[cr shr 2]++; g[cg shr 2]++; bl[cb shr 2]++; l[y8 shr 2]++
                wave[x * 64 + (y8 shr 2)]++
            }
            return Scope(r, g, bl, l, wave)
        }
    }
}
