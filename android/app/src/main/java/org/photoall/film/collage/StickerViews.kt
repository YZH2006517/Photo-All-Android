package org.photoall.film.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/** Pieces the sticker screens share: their ground and the folders. */
object StickerViews {
    /**
     * A screen's ground in the stickers' own colour, soft as frosted glass under a lamp: the
     * stickers' main colour (their opaque, coloured pixels only: no white card, no clear
     * ground), a smooth fall from a light top to a deep foot in that hue, and a wide soft
     * glow at the upper left. Gradients only, so there are no blocks or bands.
     */
    fun ground(context: Context, stickers: List<Bitmap>): Drawable {
        val base = mainColour(stickers) ?: 0xff8a6a4e.toInt()
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(base, it) }
        fun tone(sat: Float, value: Float) = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * sat).coerceIn(0f, 0.75f), value))
        val fall = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(tone(0.75f, 0.52f), tone(0.85f, 0.36f), tone(0.9f, 0.2f), tone(0.9f, 0.1f)))
        val w = context.resources.displayMetrics.widthPixels.toFloat()
        val glow = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = w * 1.1f
            setGradientCenter(0.25f, 0.08f)
            colors = intArrayOf((tone(0.6f, 0.85f) and 0x00ffffff) or 0x59000000, 0x00000000)
        }
        return LayerDrawable(arrayOf(fall, glow))
    }

    /** The stickers' main colour: their opaque pixels weighted by how coloured they are; null when there is none. */
    private fun mainColour(stickers: List<Bitmap>): Int? {
        var r = 0.0; var g = 0.0; var b = 0.0; var wsum = 0.0
        val hsv = FloatArray(3)
        for (s in stickers) {
            val small = Bitmap.createScaledBitmap(s, 24, maxOf(1, 24 * s.height / s.width), true)
            val px = IntArray(small.width * small.height); small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            for (c in px) {
                if ((c ushr 24) < 200) continue
                android.graphics.Color.colorToHSV(c, hsv)
                val w = hsv[1] * hsv[1] * (0.3f + hsv[2]) // vivid pixels count, the white card and greys hardly
                r += (c shr 16 and 255) * w; g += (c shr 8 and 255) * w; b += (c and 255) * w; wsum += w
            }
        }
        if (wsum < 1e-3) return null
        return android.graphics.Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (b / wsum).toInt())
    }

    /**
     * A folder: a back plate with a tab, the stickers standing in it and peeking over the
     * front, a frosted front plate with the name and the count.
     */
    class Folder(context: Context, private val label: String, private val count: Int, private val peek: List<Bitmap>, private val chosen: Boolean) : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = 12 * d
            // the back plate and its tab
            p.color = if (chosen) 0x59ffffff else 0x33ffffff
            val back = Path().apply {
                addRoundRect(RectF(0f, h * 0.16f, w, h), r, r, Path.Direction.CW)
                addRoundRect(RectF(0f, h * 0.08f, w * 0.42f, h * 0.3f), r * 0.7f, r * 0.7f, Path.Direction.CW)
            }
            c.drawPath(back, p)
            // the stickers, standing in it
            for ((i, b) in peek.take(3).withIndex()) {
                val sh = h * 0.52f; val sw = sh * b.width / b.height
                val cx = w * (0.28f + i * 0.24f); val top = h * 0.04f + (i % 2) * 4 * d
                c.save(); c.rotate(-8f + i * 8f, cx, top + sh / 2)
                c.drawBitmap(b, null, RectF(cx - sw / 2, top, cx + sw / 2, top + sh), p)
                c.restore()
            }
            // the front plate, frosted, with its words
            val front = RectF(0f, h * 0.44f, w, h)
            p.shader = LinearGradient(0f, front.top, 0f, front.bottom, if (chosen) 0xb3ffffff.toInt() else 0x803a3532.toInt(),
                if (chosen) 0x99f2f2f7.toInt() else 0x99221e1c.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(front, r, r, p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d; p.color = 0x40ffffff
            c.drawRoundRect(front, r, r, p)
            p.style = Paint.Style.FILL
            p.color = if (chosen) 0xff1c1c1e.toInt() else Glassy.TEXT
            p.textSize = 14 * d; p.typeface = Glassy.semibold
            c.drawText(label, 10 * d, front.top + (front.height()) * 0.52f, p)
            p.color = if (chosen) 0x991c1c1e.toInt() else Glassy.TEXT_2
            p.textSize = 10 * d; p.typeface = Glassy.regular
            c.drawText("$count 张", 10 * d, front.top + front.height() * 0.82f, p)
        }
    }
}
