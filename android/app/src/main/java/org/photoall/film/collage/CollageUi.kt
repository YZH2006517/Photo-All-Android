package org.photoall.film.collage

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/**
 * The collage editor's controls, after Apple's: line icons, a segmented control whose thumb
 * slides, preview tiles (a thing shown as it will look, its name under it), round colour wells.
 */
object CollageUi {
    /** Apple's ease for things settling into place. */
    val EASE = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
    /** A settle with a touch of spring past the mark. */
    val SPRING = PathInterpolator(0.25f, 1.15f, 0.35f, 1f)

    /** Line icons in a 24 box, 1.7 stroke, round ends; one colour. */
    class Icon(private val kind: Kind, var color: Int) : Drawable() {
        enum class Kind { PHOTO, STYLE, TEXT, STICKER, GROUND, FRONT, BACK, TRASH, CROP, SWAP, MAGIC, CLOSE, PEN }

        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val path = Path()

        override fun draw(c: Canvas) {
            val b = bounds; val size = minOf(b.width(), b.height()).toFloat()
            c.save(); c.translate(b.exactCenterX() - size / 2, b.exactCenterY() - size / 2); c.scale(size / 24f, size / 24f)
            p.color = color; p.strokeWidth = 1.7f; p.style = Paint.Style.STROKE
            path.reset()
            when (kind) {
                Kind.PHOTO -> {
                    c.drawRoundRect(RectF(3f, 5f, 21f, 19f), 3f, 3f, p)
                    path.moveTo(3.5f, 17f); path.lineTo(9f, 11.5f); path.lineTo(13f, 15f); path.lineTo(15.5f, 12.5f); path.lineTo(20.5f, 17f)
                    c.drawPath(path, p)
                    p.style = Paint.Style.FILL; c.drawCircle(16f, 9f, 1.6f, p)
                }
                Kind.STYLE -> {
                    // a print: its card, the photo's window, the wide foot
                    c.drawRoundRect(RectF(5f, 3f, 19f, 21f), 2f, 2f, p)
                    c.drawRect(RectF(8f, 6f, 16f, 14.5f), p)
                    path.moveTo(9f, 18f); path.lineTo(15f, 18f); c.drawPath(path, p)
                }
                Kind.TEXT -> {
                    path.moveTo(4f, 19f); path.lineTo(9f, 5f); path.lineTo(14f, 19f); path.moveTo(6f, 14f); path.lineTo(12f, 14f)
                    path.moveTo(20f, 12f); path.lineTo(20f, 19f)
                    c.drawPath(path, p)
                    c.drawCircle(17.8f, 16.2f, 2.6f, p)
                }
                Kind.STICKER -> {
                    // a square with its corner peeled
                    path.moveTo(14f, 20f); path.lineTo(7f, 20f); path.quadTo(4f, 20f, 4f, 17f); path.lineTo(4f, 7f); path.quadTo(4f, 4f, 7f, 4f)
                    path.lineTo(17f, 4f); path.quadTo(20f, 4f, 20f, 7f); path.lineTo(20f, 14f); path.close()
                    c.drawPath(path, p)
                    path.reset(); path.moveTo(14f, 20f); path.lineTo(14f, 16.5f); path.quadTo(14f, 14f, 16.5f, 14f); path.lineTo(20f, 14f)
                    c.drawPath(path, p)
                }
                Kind.GROUND -> {
                    c.drawCircle(12f, 12f, 8.5f, p)
                    path.addArc(RectF(3.5f, 3.5f, 20.5f, 20.5f), 90f, 180f); path.close()
                    p.style = Paint.Style.FILL; c.drawPath(path, p)
                }
                Kind.FRONT -> {
                    c.drawRoundRect(RectF(4f, 9f, 15f, 20f), 2.5f, 2.5f, p)
                    p.style = Paint.Style.FILL; c.drawRoundRect(RectF(9f, 4f, 20f, 15f), 2.5f, 2.5f, p)
                }
                Kind.BACK -> {
                    p.style = Paint.Style.FILL; c.drawRoundRect(RectF(4f, 9f, 15f, 20f), 2.5f, 2.5f, p)
                    p.style = Paint.Style.STROKE; c.drawRoundRect(RectF(9f, 4f, 20f, 15f), 2.5f, 2.5f, p)
                }
                Kind.TRASH -> {
                    path.moveTo(4f, 6.5f); path.lineTo(20f, 6.5f); path.moveTo(9.5f, 6.5f); path.lineTo(10f, 4f); path.lineTo(14f, 4f); path.lineTo(14.5f, 6.5f)
                    path.moveTo(6f, 6.5f); path.lineTo(7f, 19f); path.quadTo(7.2f, 20.5f, 8.8f, 20.5f); path.lineTo(15.2f, 20.5f); path.quadTo(16.8f, 20.5f, 17f, 19f); path.lineTo(18f, 6.5f)
                    path.moveTo(10.2f, 10f); path.lineTo(10.5f, 17f); path.moveTo(13.8f, 10f); path.lineTo(13.5f, 17f)
                    c.drawPath(path, p)
                }
                Kind.CROP -> {
                    path.moveTo(7f, 3f); path.lineTo(7f, 17f); path.lineTo(21f, 17f); path.moveTo(3f, 7f); path.lineTo(17f, 7f); path.lineTo(17f, 21f)
                    c.drawPath(path, p)
                }
                Kind.SWAP -> {
                    path.moveTo(4f, 9f); path.lineTo(18f, 9f); path.moveTo(14.5f, 5.5f); path.lineTo(18f, 9f); path.lineTo(14.5f, 12.5f)
                    path.moveTo(20f, 15f); path.lineTo(6f, 15f); path.moveTo(9.5f, 11.5f); path.lineTo(6f, 15f); path.lineTo(9.5f, 18.5f)
                    c.drawPath(path, p)
                }
                Kind.MAGIC -> {
                    path.moveTo(4f, 20f); path.lineTo(14f, 10f); c.drawPath(path, p)
                    p.style = Paint.Style.FILL
                    for ((x, y, r) in listOf(Triple(17f, 6.5f, 3.2f), Triple(9f, 5f, 1.8f), Triple(19f, 14f, 1.8f))) {
                        path.reset(); path.moveTo(x, y - r); path.quadTo(x, y, x + r, y); path.quadTo(x, y, x, y + r); path.quadTo(x, y, x - r, y); path.quadTo(x, y, x, y - r)
                        c.drawPath(path, p)
                    }
                }
                Kind.CLOSE -> { path.moveTo(7f, 7f); path.lineTo(17f, 17f); path.moveTo(17f, 7f); path.lineTo(7f, 17f); c.drawPath(path, p) }
                Kind.PEN -> {
                    path.moveTo(5f, 19f); path.lineTo(5.8f, 15.4f); path.lineTo(15.5f, 5.7f); path.quadTo(16.8f, 4.4f, 18.1f, 5.7f); path.lineTo(18.3f, 5.9f)
                    path.quadTo(19.6f, 7.2f, 18.3f, 8.5f); path.lineTo(8.6f, 18.2f); path.close()
                    path.moveTo(13.5f, 7.7f); path.lineTo(16.3f, 10.5f)
                    c.drawPath(path, p)
                }
            }
            c.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * Liquid glass, as Apple makes it: the ground behind bent at the pane's edges and frosted a
     * little ([org.photoall.film.ui.Style.Glass], [tint] of white in it), a long soft shadow under a
     * floating pane, a hairline of dark parting it from the ground, a specular rim bright where the
     * light from above-left meets it, and a sheen across its upper part.
     */
    class LiquidGlass(private val d: Float, private val tint: Int, private val radius: Float, private val shadow: Boolean = true) : Drawable() {
        /** The pane itself: the ground behind bent at its edges and frosted a little (Android 13+), else a tint. */
        private val body = org.photoall.film.ui.Style.Glass(d, tint, radius, rim = false)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var pressed = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            body.state = state
            if (now == pressed) return false
            pressed = now; invalidateSelf(); return true
        }
        override fun onBoundsChange(bounds: android.graphics.Rect) { body.bounds = bounds }

        override fun draw(c: Canvas) {
            r.set(bounds); r.inset(0.5f * d, 0.5f * d)
            val rad = minOf(radius, r.height() / 2, r.width() / 2)
            p.style = Paint.Style.FILL; p.shader = null
            if (shadow) {
                p.color = 0x01000000
                p.setShadowLayer(18f * d, 0f, 8f * d, 0x73000000); c.drawRoundRect(r, rad, rad, p)
                p.clearShadowLayer()
            }
            // the pane: what lies behind, bent and frosted through it (the view is its callback, for where it lies)
            body.callback = callback
            body.draw(c)
            // light from above: a faint sheen on the upper part (a shader takes the paint's alpha: full)
            p.style = Paint.Style.FILL; p.color = 0xff000000.toInt()
            p.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.top + minOf(r.height(), 120f * d),
                intArrayOf(if (pressed) 0x26ffffff else 0x14ffffff, 0x00ffffff), null, android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p)
            // the hairline of dark that parts it from the ground
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x4d000000
            c.drawRoundRect(r, rad, rad, p)
            // the rim: a hairline catching the light at the top, nearly gone at the foot
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0xff000000.toInt()
            p.shader = android.graphics.LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(0x99ffffff.toInt(), 0x24ffffff, 0x0affffff, 0x33ffffff), floatArrayOf(0f, 0.3f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
            val rim = RectF(r).apply { inset(0.5f * d, 0.5f * d) }
            c.drawRoundRect(rim, rad - 0.5f * d, rad - 0.5f * d, p)
            p.shader = null
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * A wallpaper of the photo's colours ([Tones.palette]): each colour a wide soft pool of light,
     * laid where a seed from the colours puts it, flowing into one another over a deep ground; the
     * corners fall away, a fine grain frosts it. Kept dark (white words read over it) and never a
     * poster: the colours are deepened and their saturation held.
     */
    class ToneGround(palette: IntArray?) : Drawable() {
        /** Its colour at the top (for what fades into it). */
        val top: Int
        private val base: Int
        private val pools: List<Triple<Int, Float, Float>> // colour, x, y (shares of the screen)
        init {
            val pal = palette ?: intArrayOf(0xff3a3b3e.toInt(), 0xff26272a.toInt(), 0xff55565b.toInt())
            fun tone(c: Int, v: Float, sat: Float): Int {
                val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
                hsv[1] = minOf(hsv[1] * 1.1f, sat); hsv[2] = v; return android.graphics.Color.HSVToColor(hsv)
            }
            // where the pools lie, turned a little by the colours themselves (each photo its own picture)
            val seed = pal.fold(17) { acc, c -> acc * 31 + c }
            val rnd = java.util.Random(seed.toLong())
            val spots = listOf(0.2f to 0.12f, 0.85f to 0.3f, 0.15f to 0.62f, 0.78f to 0.82f, 0.5f to 0.46f)
            pools = pal.take(5).mapIndexed { i, c ->
                val (x, y) = spots[i]
                Triple(tone(c, if (i == 0) 0.46f else 0.38f, 0.62f), x + (rnd.nextFloat() - 0.5f) * 0.2f, y + (rnd.nextFloat() - 0.5f) * 0.14f)
            }
            base = tone(pal.last(), 0.1f, 0.45f)
            top = tone(pal[0], 0.3f, 0.55f)
        }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat(); val m = maxOf(w, h)
            p.shader = null; p.color = base; c.drawRect(0f, 0f, w, h, p)
            p.color = 0xff000000.toInt() // the pools' shaders take the paint's alpha: full
            // the pools, the most present last (on top) and widest
            for ((i, pool) in pools.withIndex().reversed()) {
                val (col, x, y) = pool
                val r = m * (if (i == 0) 0.72f else 0.55f)
                p.shader = android.graphics.RadialGradient(x * w, y * h, r,
                    intArrayOf((col and 0x00ffffff) or 0xe6000000.toInt(), (col and 0x00ffffff) or 0x73000000, col and 0x00ffffff),
                    floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
            }
            // the corners fall away
            p.shader = android.graphics.RadialGradient(w * 0.5f, h * 0.42f, m * 0.85f,
                intArrayOf(0x00000000, 0x14000000, 0x8c000000.toInt()), floatArrayOf(0.3f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
            CollageArt.texture(c, RectF(0f, 0f, w, h), CollageArt.Surface.GRAIN, w, 0.7f)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** [ToneGround] as a small picture (the screen's shape), for the ground the glass bends. */
    fun toneImage(tones: IntArray?, aspect: Float): android.graphics.Bitmap {
        val w = 120; val h = (w * aspect).toInt().coerceAtLeast(1)
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        ToneGround(tones).apply { setBounds(0, 0, w, h) }.draw(Canvas(b))
        return b
    }

    fun glass(c: Context, radiusDp: Float, tint: Int = 0x1fffffff, shadow: Boolean = true) =
        LiquidGlass(c.resources.displayMetrics.density, tint, radiusDp * c.resources.displayMetrics.density, shadow)

    /** A segmented control: a frosted track, the chosen segment's thumb sliding to it. */
    class Segments(context: Context, private val labels: List<String>, index: Int, private val onPick: (Int) -> Unit) : View(context) {
        private val d = resources.displayMetrics.density
        private var at = index.toFloat()
        var index = index
            private set
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 13 * d }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val n = labels.size
            p.style = Paint.Style.FILL
            p.color = 0x3d767680; c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p) // tertiary fill
            val sw = (w - 4 * d) / n
            val l = 2 * d + at * sw
            val thumb = RectF(l, 2 * d, l + sw, h - 2 * d)
            p.color = 0x1a000000; p.setShadowLayer(6 * d, 0f, 2 * d, 0x4d000000)
            c.drawRoundRect(thumb, thumb.height() / 2, thumb.height() / 2, p); p.clearShadowLayer()
            p.color = 0xff636366.toInt(); c.drawRoundRect(thumb, thumb.height() / 2, thumb.height() / 2, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d
            p.shader = android.graphics.LinearGradient(0f, thumb.top, 0f, thumb.bottom, intArrayOf(0x33ffffff, 0x00ffffff), null, android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(thumb, thumb.height() / 2, thumb.height() / 2, p); p.shader = null; p.style = Paint.Style.FILL
            for ((i, t) in labels.withIndex()) {
                val near = 1f - kotlin.math.abs(at - i).coerceAtMost(1f)
                p.typeface = if (near > 0.5f) Glassy.bold else Glassy.semibold
                p.color = CollageArt.mix(0xffa8a8b0.toInt(), Glassy.TEXT, near)
                c.drawText(t, 2 * d + sw * (i + 0.5f), h / 2 + p.textSize * 0.36f, p)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val i = ((e.x - 2 * d) / ((width - 4 * d) / labels.size)).toInt().coerceIn(0, labels.lastIndex)
                if (i != index) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    ValueAnimator.ofFloat(at, i.toFloat()).apply {
                        duration = 320; interpolator = EASE
                        addUpdateListener { at = it.animatedValue as Float; invalidate() }
                    }.start()
                    index = i; onPick(i)
                }
            }
            return true
        }
    }

    /** A preview tile: [art] drawn in a rounded well, [label] under it, a ring when [chosen]. */
    class Tile(context: Context, private val label: String, private val chosen: Boolean, private val wellW: Float, private val wellH: Float,
               private val art: (Canvas, RectF) -> Unit) : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { org.photoall.film.ui.Style.pressable(this) }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(((wellW + 8) * d).toInt(), ((wellH + 26) * d).toInt())

        override fun onDraw(c: Canvas) {
            val well = RectF(4 * d, 4 * d, (4 + wellW) * d, (4 + wellH) * d)
            val r = 12 * d
            c.save(); c.clipPath(Path().apply { addRoundRect(well, r, r, Path.Direction.CW) })
            p.style = Paint.Style.FILL; p.color = 0xff2c2c2e.toInt(); c.drawRect(well, p)
            art(c, well)
            c.restore()
            p.style = Paint.Style.STROKE
            if (chosen) { p.strokeWidth = 2.2f * d; p.color = Glassy.YELLOW; c.drawRoundRect(RectF(well.left - 2.2f * d, well.top - 2.2f * d, well.right + 2.2f * d, well.bottom + 2.2f * d), r + 2 * d, r + 2 * d, p) }
            else { p.strokeWidth = 1f; p.color = 0x14ffffff; c.drawRoundRect(well, r, r, p) }
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.textSize = 11 * d
            p.typeface = if (chosen) Glassy.bold else Glassy.regular
            p.color = if (chosen) Glassy.TEXT else 0xff8e8e93.toInt()
            c.drawText(label, width / 2f, well.bottom + 16 * d, p)
        }
    }

    /** A colour well: a filled circle, ringed in the accent when chosen. */
    class Well(context: Context, private val color: Int, private val chosen: Boolean) : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { org.photoall.film.ui.Style.pressable(this) }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - 3 * d
            p.style = Paint.Style.FILL; p.color = color; c.drawCircle(cx, cy, if (chosen) r - 3 * d else r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x33ffffff; c.drawCircle(cx, cy, if (chosen) r - 3 * d else r, p)
            if (chosen) { p.strokeWidth = 2.2f * d; p.color = Glassy.YELLOW; c.drawCircle(cx, cy, r + 1.5f * d, p) }
        }
    }
}
