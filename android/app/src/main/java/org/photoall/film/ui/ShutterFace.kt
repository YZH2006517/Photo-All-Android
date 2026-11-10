package org.photoall.film.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * The portrait shutter in the theme's own material, flat and matte: no shine and no dome.
 * A collar of the theme's metal, a gap, and a level release button with a fine grain, as a
 * bead-blasted or anodised part would have, ringed by a thin line of the theme's accent.
 * Each theme has its metal and its button: gunmetal and cream with the cartridge yellow,
 * smoked red under the safelight, ink on paper, brass, silver with ice blue.
 */
class ShutterFace(private val density: Float, private val m: Material) : Drawable() {
    class Material(
        /** The collar's metal: light, dark, light, dark (the collar takes the second, matte). */
        val metal: IntArray,
        /** The button: light, body, dark (the body is used, flat). */
        val dome: IntArray,
        val rim: Int,
        val gap: Int,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** A fine, even grain over the matte surfaces: texture without any highlight. */
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val n = 48
        val random = java.util.Random(7)
        val px = IntArray(n * n) {
            val v = if (random.nextBoolean()) 255 else 0
            Color.argb(10 + random.nextInt(10), v, v, v)
        }
        shader = BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    override fun onBoundsChange(bounds: Rect) {
        cx = bounds.exactCenterX()
        cy = bounds.exactCenterY()
        r = min(bounds.width(), bounds.height()) / 2f
    }

    override fun draw(canvas: Canvas) {
        if (r <= 0f) return
        val collarW = 4.5f * density
        val inner = r - 8.5f * density
        paint.style = Paint.Style.FILL
        // the collar: one matte metal tone
        paint.color = mix(m.metal[0], m.metal[1], 0.45f)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawCircle(cx, cy, r, grain)
        // the gap the button sits in
        paint.color = m.gap
        canvas.drawCircle(cx, cy, r - collarW, paint)
        // the button: level, one colour, grained
        paint.color = m.dome[1]
        canvas.drawCircle(cx, cy, inner, paint)
        canvas.drawCircle(cx, cy, inner, grain)
        // the accent line round it
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = m.rim
        canvas.drawCircle(cx, cy, inner - 0.6f * density, paint)
    }

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * k).toInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** By theme, in the order of [Style.palettes]. */
        val materials = listOf(
            // 暗盒: gunmetal collar, a cream button, the cartridge yellow round it
            Material(intArrayOf(0xffb9b3a6.toInt(), 0xff4a4640.toInt(), 0xffe6e0d2.toInt(), 0xff2c2a26.toInt()),
                intArrayOf(0xfffffcf4.toInt(), 0xffeae4d6.toInt(), 0xffb4ad9e.toInt()), 0xfff2b544.toInt(), 0xff0b0b0a.toInt()),
            // 暗房红灯: smoked red metal, a dim red button (nothing bright)
            Material(intArrayOf(0xff7a2418.toInt(), 0xff2a0806.toInt(), 0xffa3321f.toInt(), 0xff1a0504.toInt()),
                intArrayOf(0xffe0563f.toInt(), 0xffa8331f.toInt(), 0xff4a120b.toInt()), 0xffff3b22.toInt(), 0xff000000.toInt()),
            // 相纸: dark ink metal, a glossy ink button with a rust rim
            Material(intArrayOf(0xff8d877d.toInt(), 0xff2a2825.toInt(), 0xffc6c0b4.toInt(), 0xff1b1a18.toInt()),
                intArrayOf(0xff5a5650.toInt(), 0xff24221f.toInt(), 0xff0c0b0a.toInt()), 0xff9a360b.toInt(), 0xffefeae0.toInt()),
            // 黄铜: a brass collar and a brass release
            Material(intArrayOf(0xfff1dca4.toInt(), 0xff7a5d24.toInt(), 0xffe2c47e.toInt(), 0xff5a4318.toInt()),
                intArrayOf(0xfff7e6b8.toInt(), 0xffc9a45c.toInt(), 0xff7a5d24.toInt()), 0xffece6d6.toInt(), 0xff161813.toInt()),
            // 冷银: brushed silver, a pale silver button, an ice-blue rim
            Material(intArrayOf(0xfff3f6f9.toInt(), 0xff6f7882.toInt(), 0xffdde3e9.toInt(), 0xff4a525b.toInt()),
                intArrayOf(0xffffffff.toInt(), 0xffd4dae0.toInt(), 0xff8b949e.toInt()), 0xff8cc8e6.toInt(), 0xff0e1012.toInt()),
        )
    }
}
