package org.photoall.film.collage

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * AI 相机's guide over the picture: the framing the composition model would choose, drawn as
 * corner marks that glide to each new answer; a word on what to do (往右一点, 靠近一点 …, or 构图
 * 不错 when the frame as it is is as good); and a level — a line through the middle that turns
 * with the phone and lights when it is level.
 */
class ComposeGuide(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shown = RectF(0f, 0f, 1f, 1f)
    private val target = RectF(0f, 0f, 1f, 1f)
    private var framed = 0f // 0: no framing to show .. 1: shown
    private var hint = ""
    private var good = false
    /** The phone's roll, degrees (0 level). */
    var roll = 0f
        set(v) { field = v; invalidate() }
    private var anim: ValueAnimator? = null
    private val smooth = ArrayDeque<Composition.Result>()

    /** A new answer: averaged with the last two (the guide does not jitter), then glided to. */
    fun show(r: Composition.Result) {
        smooth.addLast(r); while (smooth.size > 3) smooth.removeFirst()
        val box = RectF(smooth.map { it.box.left }.average().toFloat(), smooth.map { it.box.top }.average().toFloat(),
            smooth.map { it.box.right }.average().toFloat(), smooth.map { it.box.bottom }.average().toFloat())
        val gain = smooth.map { it.gain }.average().toFloat()
        val wasGood = good
        good = gain < 0.35f
        hint = if (good) "构图不错" else advice(box)
        val from = RectF(shown); val toFramed = if (good) 0f else 1f; val f0 = framed
        target.set(box)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420; interpolator = CollageUi.EASE
            addUpdateListener {
                val k = it.animatedValue as Float
                shown.set(from.left + (target.left - from.left) * k, from.top + (target.top - from.top) * k,
                    from.right + (target.right - from.right) * k, from.bottom + (target.bottom - from.bottom) * k)
                framed = f0 + (toFramed - f0) * k
                invalidate()
            }
            start()
        }
        if (good && !wasGood) performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    }

    /** What moves the picture toward [box]: the camera turns toward its centre, closes in if it is small. */
    private fun advice(box: RectF): String {
        val dx = box.centerX() - 0.5f; val dy = box.centerY() - 0.5f
        val area = box.width() * box.height()
        val parts = ArrayList<String>()
        if (kotlin.math.abs(dx) > 0.04f) parts += if (dx > 0) "往右一点" else "往左一点"
        if (kotlin.math.abs(dy) > 0.04f) parts += if (dy > 0) "往下一点" else "往上一点"
        if (area < 0.72f) parts += "靠近一点"
        return parts.take(2).joinToString("，").ifEmpty { "按框里构图" }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // the level: a line through the middle, turned with the phone; yellow when level
        val level = kotlin.math.abs(roll) < 1.2f
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 1.5f * d; p.color = if (level) Glassy.YELLOW else 0xb3ffffff.toInt()
        c.save(); c.rotate(-roll, w / 2, h / 2)
        c.drawLine(w / 2 - 70 * d, h / 2, w / 2 - 22 * d, h / 2, p)
        c.drawLine(w / 2 + 22 * d, h / 2, w / 2 + 70 * d, h / 2, p)
        c.restore()
        if (!level) { p.color = 0x66ffffff; c.drawLine(w / 2 - 12 * d, h / 2, w / 2 + 12 * d, h / 2, p) }
        // the framing: corner marks
        if (framed > 0.01f) {
            val r = RectF(shown.left * w, shown.top * h, shown.right * w, shown.bottom * h)
            p.color = (Glassy.YELLOW and 0x00ffffff) or ((framed * 255).toInt() shl 24)
            p.strokeWidth = 2.5f * d
            val k = minOf(r.width(), r.height()) * 0.12f
            for ((x, y, sx, sy) in listOf(floatArrayOf(r.left, r.top, 1f, 1f), floatArrayOf(r.right, r.top, -1f, 1f),
                    floatArrayOf(r.left, r.bottom, 1f, -1f), floatArrayOf(r.right, r.bottom, -1f, -1f))) {
                c.drawLine(x, y, x + k * sx, y, p); c.drawLine(x, y, x, y + k * sy, p)
            }
            p.strokeWidth = 1f * d; p.color = (0xffffff) or ((framed * 70).toInt() shl 24)
            c.drawRect(r, p)
        }
        // the word, in a dark pill at the top
        if (hint.isNotEmpty()) {
            p.style = Paint.Style.FILL; p.textSize = 13 * d; p.typeface = Glassy.semibold; p.textAlign = Paint.Align.CENTER
            val tw = p.measureText(hint)
            val pill = RectF(w / 2 - tw / 2 - 12 * d, 12 * d, w / 2 + tw / 2 + 12 * d, 40 * d)
            p.color = 0x8c000000.toInt(); c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, p)
            p.color = if (good) Glassy.YELLOW else Glassy.TEXT
            c.drawText(hint, w / 2, pill.centerY() + p.textSize * 0.36f, p)
        }
    }
}
