package org.photoall.film.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 修整 / 手动抠图: the photo with what the sticker keeps shown as it is and the rest under a
 * dim checker. One finger either shows where to look again (框选: drag a box; 涂抹: a few
 * strokes over the subject, the area they cover, with room) and [onRegion] runs the model
 * there, its answer [merge]d in; or paints with a round brush, erasing (擦除) or bringing back
 * (恢复) what the sticker keeps. Two fingers zoom and move the picture for small edges. Every
 * change can be undone. The mask is the photo's own size, white, its alpha what is kept.
 */
class MaskEditor(context: Context, private val photo: Bitmap, val mask: Bitmap) : View(context) {
    enum class Tool(val region: Boolean) { BOX(true), SMEAR(true), ERASE(false), RESTORE(false) }

    var tool = Tool.ERASE
        set(v) { field = v; clearRegion() }
    var brushDp = 28f
    var onChange: () -> Unit = {}
    /**
     * 框选 / 涂抹 let go: look again in this part of the photo (its pixels); for 涂抹 also points
     * along the strokes (x, y pairs; empty for 框选).
     */
    var onRegion: (android.graphics.Rect, FloatArray) -> Unit = { _, _ -> }
    /** The model is looking: the region pulses and the picture takes no touches. */
    var busy = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val canvasMask = Canvas(mask)
    private val undo = ArrayDeque<Bitmap>()
    private val view = Matrix()
    private val inverse = Matrix()
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val checker = Paint().apply {
        val t = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        t.setPixel(0, 0, 0xff3a3a3c.toInt()); t.setPixel(1, 1, 0xff3a3a3c.toInt())
        t.setPixel(1, 0, 0xff2c2c2e.toInt()); t.setPixel(0, 1, 0xff2c2c2e.toInt())
        shader = BitmapShader(t, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(10 * d, 10 * d) })
        }
    }
    private var cursor: FloatArray? = null
    private val blue = 0xff0a84ff.toInt()
    private var boxFrom: FloatArray? = null
    private var boxTo: FloatArray? = null
    private val trail = Path()
    private var trailOn = false
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** What 框选 / 涂抹 drew, gone (after the model answered, or the tool changed). */
    fun clearRegion() { boxFrom = null; boxTo = null; trail.reset(); trailOn = false; invalidate() }

    /** The model's answer for a region (a mask the photo's size) added to what is kept; undoable. */
    fun merge(part: Bitmap) {
        pushUndo()
        canvasMask.drawBitmap(part, 0f, 0f, null)
        invalidate(); onChange()
    }

    private fun pushUndo() { undo.addLast(mask.copy(Bitmap.Config.ARGB_8888, false)); while (undo.size > 12) undo.removeFirst().recycle() }

    val canUndo get() = undo.isNotEmpty()

    fun undoLast() { undo.removeLastOrNull()?.let { canvasMask.drawColor(0, PorterDuff.Mode.CLEAR); canvasMask.drawBitmap(it, 0f, 0f, null); it.recycle() }; invalidate(); onChange() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

    private fun fit() {
        if (width == 0) return
        val k = minOf(width.toFloat() / photo.width, height.toFloat() / photo.height) * 0.94f
        view.setScale(k, k)
        view.postTranslate((width - photo.width * k) / 2, (height - photo.height * k) / 2)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        view.mapRect(r)
        c.drawRect(r, checker)
        // the photo dimmed everywhere, then kept parts at full strength
        c.save(); c.concat(view)
        p.alpha = 70; c.drawBitmap(photo, 0f, 0f, p); p.alpha = 255
        val layer = c.saveLayer(0f, 0f, photo.width.toFloat(), photo.height.toFloat(), null)
        c.drawBitmap(photo, 0f, 0f, p)
        c.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        c.restoreToCount(layer)
        // 涂抹's strokes, in the photo's frame
        val pulse = if (busy) (0.55f + 0.45f * kotlin.math.sin(android.os.SystemClock.uptimeMillis() / 180.0).toFloat()) else 1f
        if (trailOn) {
            val scale = FloatArray(9).also { view.getValues(it) }[Matrix.MSCALE_X]
            regionPaint.apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = brushDp * d / scale; color = blue; alpha = (110 * pulse).toInt(); pathEffect = null }
            c.drawPath(trail, regionPaint)
        }
        c.restore()
        // 框选's box, on screen
        val f = boxFrom; val t = boxTo
        if (f != null && t != null) {
            val r0 = RectF(minOf(f[0], t[0]), minOf(f[1], t[1]), maxOf(f[0], t[0]), maxOf(f[1], t[1]))
            view.mapRect(r0)
            regionPaint.apply { style = Paint.Style.FILL; color = blue; alpha = (40 * pulse).toInt(); pathEffect = null }
            c.drawRoundRect(r0, 6 * d, 6 * d, regionPaint)
            regionPaint.apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; alpha = (255 * pulse).toInt(); pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * d, 5 * d), 0f) }
            c.drawRoundRect(r0, 6 * d, 6 * d, regionPaint)
        }
        if (busy) postInvalidateOnAnimation()
        cursor?.let { (x, y) ->
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.5f * d
                color = when (tool) { Tool.ERASE -> 0xffff453a.toInt(); Tool.RESTORE -> 0xff30d158.toInt(); else -> blue }
            }
            if (tool != Tool.BOX) c.drawCircle(x, y, brushDp * d / 2, ring)
        }
    }

    // ---- touch: one finger paints, two zoom and move ----
    private var painting = false
    private var lastDist = 0f
    private var lastMid = floatArrayOf(0f, 0f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        if (busy) return true
        if (tool.region && regionTouch(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                painting = true
                pushUndo()
                path.reset(); val m = toPhoto(e.x, e.y); path.moveTo(m[0], m[1]); path.lineTo(m[0] + 0.1f, m[1])
                stroke(); cursor = floatArrayOf(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (painting) { painting = false; undoLast() } // the first finger was the start of a pinch
                lastDist = dist(e); lastMid = mid(e); cursor = null
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) {
                val dd = dist(e); val m = mid(e)
                val k = (dd / lastDist).coerceIn(0.5f, 2f)
                view.postScale(k, k, m[0], m[1]); view.postTranslate(m[0] - lastMid[0], m[1] - lastMid[1])
                lastDist = dd; lastMid = m
                invalidate()
            } else if (painting) {
                val m = toPhoto(e.x, e.y); path.lineTo(m[0], m[1]); stroke(); cursor = floatArrayOf(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (painting) onChange(); painting = false; cursor = null; invalidate() }
        }
        return true
    }

    /** 框选 / 涂抹 with one finger (false: two fingers, left to zooming). */
    private fun regionTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                painting = true
                val m = toPhoto(e.x, e.y)
                if (tool == Tool.BOX) { boxFrom = m; boxTo = m }
                else { if (!trailOn) trail.reset(); trailOn = true; trail.moveTo(m[0], m[1]); trail.lineTo(m[0] + 0.1f, m[1]) }
                cursor = floatArrayOf(e.x, e.y); invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> { if (painting) { painting = false; clearRegion() }; return false }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2 || !painting) return false
                val m = toPhoto(e.x, e.y)
                if (tool == Tool.BOX) boxTo = m else trail.lineTo(m[0], m[1])
                cursor = floatArrayOf(e.x, e.y); invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = painting
                painting = false; cursor = null
                if (was && e.actionMasked == MotionEvent.ACTION_UP) regionDone() else invalidate()
            }
            else -> return false
        }
        return true
    }

    /** The box, or the strokes' cover with room round it, handed to [onRegion] when big enough. */
    private fun regionDone() {
        val r = RectF()
        var points = FloatArray(0)
        if (tool == Tool.BOX) {
            val f = boxFrom ?: return; val t = boxTo ?: return
            r.set(minOf(f[0], t[0]), minOf(f[1], t[1]), maxOf(f[0], t[0]), maxOf(f[1], t[1]))
        } else {
            trail.computeBounds(r, true)
            // up to twelve points spread along the strokes: where the subject is
            val pm = android.graphics.PathMeasure(trail, false)
            var total = 0f
            do { total += pm.length } while (pm.nextContour())
            val pts = ArrayList<Float>()
            val step = maxOf(total / 12f, 1f)
            val pos = FloatArray(2)
            val pm2 = android.graphics.PathMeasure(trail, false)
            var carry = 0f
            do {
                var at = carry
                while (at <= pm2.length && pts.size < 24) { pm2.getPosTan(at, pos, null); pts += pos[0]; pts += pos[1]; at += step }
                carry = (at - pm2.length).coerceAtLeast(0f)
            } while (pm2.nextContour() && pts.size < 24)
            points = pts.toFloatArray()
            val scale = FloatArray(9).also { view.getValues(it) }[Matrix.MSCALE_X]
            val room = maxOf(r.width(), r.height()) * 0.25f + brushDp * d / scale
            r.inset(-room, -room)
        }
        r.intersect(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        val least = 24 * d / FloatArray(9).also { view.getValues(it) }[Matrix.MSCALE_X]
        if (r.width() < least || r.height() < least) { clearRegion(); return }
        invalidate()
        onRegion(android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()), points)
    }

    private fun stroke() {
        val scale = FloatArray(9).also { view.getValues(it) }[Matrix.MSCALE_X]
        brush.strokeWidth = brushDp * d / scale
        if (tool == Tool.ERASE) { brush.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); brush.color = Color.TRANSPARENT }
        else { brush.xfermode = null; brush.color = Color.WHITE }
        canvasMask.drawPath(path, brush)
        invalidate()
    }

    private fun toPhoto(x: Float, y: Float): FloatArray { view.invert(inverse); return floatArrayOf(x, y).also { inverse.mapPoints(it) } }
    private fun dist(e: MotionEvent) = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
    private fun mid(e: MotionEvent) = floatArrayOf((e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2)
}
