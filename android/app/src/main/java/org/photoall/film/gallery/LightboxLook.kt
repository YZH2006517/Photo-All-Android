package org.photoall.film.gallery

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import org.photoall.film.ui.Skin

/**
 * The light table dressed for the camera's style (光, 经典, 漫, 风): its housing, the table's
 * colour with the lamp off, the china marker, the faces of its words, its keys. The plain themes
 * keep the table as it was: a dark housing, a red marker.
 */
object LightboxLook {
    private const val INK = 0xff161514.toInt()
    private const val GOLD = 0xffa88435.toInt()
    private const val BONE = 0xffe6e1d4.toInt()
    private const val NAVY = 0xff1b1464.toInt()
    private const val ULTRA = 0xff1f2bd0.toInt()

    val skinned get() = Skin.active

    /** The light table's own styles (not the app's theme): chosen in its settings, 经典 first. */
    val SKINS = listOf(Skin.CAMERA to "经典", Skin.SACRED to "光", Skin.ANIME to "漫", Skin.FATE to "风", Skin.RANGEFINDER to "旁轴")

    fun skin(context: android.content.Context) =
        context.getSharedPreferences("lightbox", android.content.Context.MODE_PRIVATE).getInt("skin", Skin.CAMERA)
            .takeIf { k -> SKINS.any { it.first == k } } ?: Skin.CAMERA

    /** The style as a theme index for [org.photoall.film.ui.Style.use]. */
    fun theme(context: android.content.Context) = org.photoall.film.ui.Style.palettes.size + skin(context) - 1

    /**
     * 经典's arrangement, now every style's: the modes up top between two round keys, the
     * table on its card, the rolls under it, the tools as one capsule of words, the blurred
     * table behind it all. Each style dresses it in its own materials (see [glass], [card],
     * the colours below and [Backdrop]'s ornaments).
     */
    val capsule get() = true

    private const val RUBRIC = 0xffb23a25.toInt()
    private const val PAPER = 0xffefede6.toInt()
    private const val PINK = 0xffff3d86.toInt()
    private const val CHROME_INK = 0xffe8e6df.toInt()
    private const val SIGNAL = 0xffe0362a.toInt()

    /** Words on a panel. */
    val ink: Int get() = when (Skin.kind) {
        Skin.SACRED, Skin.FATE -> INK
        Skin.ANIME -> NAVY
        Skin.RANGEFINDER -> CHROME_INK
        else -> 0xf2ffffff.toInt()
    }
    /** Words not chosen. */
    val inkSoft: Int get() = when (Skin.kind) {
        Skin.SACRED, Skin.FATE -> 0x9e161514.toInt()
        Skin.ANIME -> 0xa61b1464.toInt()
        Skin.RANGEFINDER -> 0xa3e8e6df.toInt()
        else -> 0xb3ffffff.toInt()
    }
    /** The style's accent: a key that is on, 自选, 选片, 清理. */
    val accent: Int get() = when (Skin.kind) {
        Skin.SACRED -> RUBRIC
        Skin.ANIME -> PINK
        Skin.FATE -> ULTRA
        Skin.RANGEFINDER -> SIGNAL
        else -> CLASSIC_GOLD
    }
    /** Words on a chosen panel. */
    val chosenInk: Int get() = when (Skin.kind) {
        Skin.SACRED -> BONE
        Skin.ANIME -> 0xffffffff.toInt()
        Skin.FATE -> PAPER
        Skin.RANGEFINDER -> INK
        else -> 0xffffffff.toInt()
    }
    /** The modes' sliding thumb. */
    val indicator: Int get() = when (Skin.kind) {
        Skin.SACRED -> INK
        Skin.ANIME -> PINK
        Skin.FATE -> ULTRA
        Skin.RANGEFINDER -> 0xffd2d0c9.toInt()
        else -> 0x47ffffff
    }

    /** The table's corner radius, and how far its card reaches round it (dp). */
    val tableCornerDp: Float get() = when (Skin.kind) {
        Skin.SACRED -> 2f
        Skin.ANIME -> 12f
        Skin.FATE -> 0f
        Skin.RANGEFINDER -> 5f
        else -> 16f
    }
    val cardPadDp: Int get() = when (Skin.kind) {
        Skin.SACRED -> 9
        Skin.ANIME -> 9   // room for the card's offset shadow
        Skin.FATE -> 8
        Skin.RANGEFINDER -> 6
        else -> 6
    }

    /**
     * A panel in the style: a key, a chip, the capsule. [tint]'s alpha says how strong it is in
     * 经典's glass (0x4d and up: chosen); the other styles read the same alpha as chosen or not.
     */
    fun glass(d: Float, tint: Int = 0x1fffffff, radiusDp: Float = -1f, shadow: Boolean = true): Drawable =
        if (Skin.kind == Skin.CAMERA || !skinned) Glass(d, tint, radiusDp, shadow)
        else Panel(Skin.kind, d, (tint ushr 24) >= 0x40, radiusDp, shadow)

    /** The card under the table. */
    fun card(d: Float): Drawable =
        if (Skin.kind == Skin.CAMERA || !skinned) Glass(d, 0x14ffffff, radiusDp = 22f)
        else Housing(Skin.kind, d, tableCornerDp + cardPadDp)

    /** The other styles' panels: their own materials, rimmed as their keys are. */
    private class Panel(private val kind: Int, private val d: Float, private val chosen: Boolean,
                        private val radiusDp: Float, private val shadow: Boolean) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var pressed = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            if (now == pressed) return false
            pressed = now; invalidateSelf(); return true
        }
        override fun draw(c: Canvas) {
            r.set(bounds); r.inset(1.2f * d, 1.2f * d)
            if (r.width() <= 0 || r.height() <= 0) return
            var radius = if (radiusDp < 0) r.height() / 2 else radiusDp * d
            p.shader = null; p.style = Paint.Style.FILL
            val wide = r.width() > r.height() * 3
            when (kind) {
                Skin.SACRED -> {
                    // bone (ink once chosen), an ink rule and a gold one inside it; the long
                    // capsules end in a small gold lozenge each side
                    if (shadow) { p.color = 0x01000000; p.setShadowLayer(5f * d, 0f, 1.5f * d, 0x2e3b2a12); c.drawRoundRect(r, radius, radius, p); p.clearShadowLayer() }
                    p.color = if (chosen) INK else if (pressed) 0xffd9d2c1.toInt() else BONE
                    c.drawRoundRect(r, radius, radius, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = INK
                    c.drawRoundRect(r, radius, radius, p)
                    if (r.height() > 20 * d) {
                        p.strokeWidth = 0.7f * d; p.color = GOLD
                        val i = 2.8f * d
                        c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, (radius - i).coerceAtLeast(0f), (radius - i).coerceAtLeast(0f), p)
                    }
                    p.style = Paint.Style.FILL
                    if (wide) {
                        p.color = GOLD
                        for (x in floatArrayOf(r.left + radius * 0.62f, r.right - radius * 0.62f)) {
                            val cy = r.centerY(); val k = 2.2f * d
                            val path = android.graphics.Path().apply { moveTo(x, cy - k); lineTo(x + k * 0.7f, cy); lineTo(x, cy + k); lineTo(x - k * 0.7f, cy); close() }
                            c.drawPath(path, p)
                        }
                    }
                }
                Skin.ANIME -> {
                    // a sticker: white (pink once chosen) in a thick navy line, its hard shadow
                    // offset down and to the right
                    val lip = 2.6f * d
                    r.right -= lip; r.bottom -= lip
                    radius = if (radiusDp < 0) r.height() / 2 else radius
                    if (shadow || chosen) { p.color = NAVY; c.drawRoundRect(r.left + lip, r.top + lip, r.right + lip, r.bottom + lip, radius, radius, p) }
                    val dx = if (pressed) lip * 0.6f else 0f
                    r.offset(dx, dx)
                    p.color = if (chosen) PINK else if (pressed) 0xffffe1ee.toInt() else 0xffffffff.toInt()
                    c.drawRoundRect(r, radius, radius, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = NAVY
                    c.drawRoundRect(r, radius, radius, p)
                    if (chosen) { // the shine a comic gives a glossy thing
                        p.strokeWidth = 1.6f * d; p.color = 0xccffffff.toInt(); p.strokeCap = Paint.Cap.ROUND
                        val k = minOf(radius, r.height() / 2)
                        c.drawArc(r.left + 3 * d, r.top + 3 * d, r.left + 3 * d + 2 * k, r.top + 3 * d + 2 * k, 190f, 50f, false, p)
                    }
                    p.style = Paint.Style.FILL
                }
                Skin.FATE -> {
                    // a slip of paper (ultramarine once chosen): squarer corners, an ink hairline,
                    // an ultramarine rule inside it
                    radius = minOf(radius, 3f * d)
                    if (shadow) { p.color = 0x01000000; p.setShadowLayer(4f * d, 0f, 1.2f * d, 0x26101030); c.drawRoundRect(r, radius, radius, p); p.clearShadowLayer() }
                    p.color = if (chosen) ULTRA else if (pressed) 0xffe2dfd5.toInt() else PAPER
                    c.drawRoundRect(r, radius, radius, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 0.9f * d; p.color = INK
                    c.drawRoundRect(r, radius, radius, p)
                    if (r.height() > 20 * d) {
                        p.strokeWidth = 0.6f * d; p.color = if (chosen) 0x99efede6.toInt() else ULTRA
                        val i = 2.6f * d
                        c.drawRect(r.left + i, r.top + i, r.right - i, r.bottom - i, p)
                    }
                    p.style = Paint.Style.FILL
                }
                Skin.RANGEFINDER -> {
                    // vulcanite in a chrome rim; chosen: bright chrome
                    if (shadow) { p.color = 0x01000000; p.setShadowLayer(6f * d, 0f, 2f * d, 0x66000000); c.drawRoundRect(r, radius, radius, p); p.clearShadowLayer() }
                    if (chosen) p.shader = LinearGradient(0f, r.top, 0f, r.bottom,
                        intArrayOf(0xfff4f3ee.toInt(), 0xffc9c8c1.toInt(), 0xff9d9c95.toInt()), null, Shader.TileMode.CLAMP)
                    else { p.shader = vulcanite; p.color = 0xff000000.toInt() }
                    c.drawRoundRect(r, radius, radius, p)
                    if (pressed && !chosen) { p.shader = null; p.color = 0x1fffffff; c.drawRoundRect(r, radius, radius, p) }
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * d
                    p.shader = LinearGradient(0f, r.top, 0f, r.bottom,
                        intArrayOf(0xfff1f0eb.toInt(), 0xff8f8e88.toInt(), 0xff4c4b47.toInt(), 0xffbdbcb6.toInt()), floatArrayOf(0f, 0.4f, 0.75f, 1f), Shader.TileMode.CLAMP)
                    c.drawRoundRect(r, radius, radius, p)
                    p.shader = null; p.style = Paint.Style.FILL
                }
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Black vulcanite's pebbled grain, shared by the 旁轴 panels and housing. */
    private val vulcanite: BitmapShader by lazy {
        val s = 48
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(5)
        for (y in 0 until s) for (x in 0 until s) {
            val v = rnd.nextFloat()
            b.setPixel(x, y, if (v < 0.12f) 0xff2a2a2b.toInt() else if (v > 0.86f) 0xff101011.toInt() else 0xff1b1b1c.toInt())
        }
        BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** The wash over the blurred table behind everything: dark for 经典 and 旁轴, the style's paper otherwise. */
    val wash: Int get() = when (Skin.kind) {
        Skin.SACRED -> 0xdbe6e1d4.toInt()
        Skin.ANIME -> 0xd1fff1f6.toInt()
        Skin.FATE -> 0xdbefede6.toInt()
        Skin.RANGEFINDER -> 0xbf0d0d0e.toInt()
        else -> 0x99000000.toInt()
    }

    /** The capsule's track: a pale pill the keys sit in. */
    fun capsuleTrack(density: Float): Drawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(0xffe4e4e9.toInt())
        cornerRadius = 100 * density
    }

    /** One key in the capsule: a white pill lifted a little off the track, greyed while pressed. */
    class Pill(private val d: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()
        private var pressed = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            if (now == pressed) return false
            pressed = now; invalidateSelf(); return true
        }
        override fun draw(c: Canvas) {
            r.set(bounds); r.inset(1.5f * d, 2f * d)
            val radius = r.height() / 2
            paint.color = if (pressed) 0xffefeff3.toInt() else 0xffffffff.toInt()
            paint.setShadowLayer(if (pressed) 1f * d else 3.5f * d, 0f, if (pressed) 0.5f * d else 1.5f * d, 0x30000000)
            c.drawRoundRect(r, radius, radius, paint)
            paint.clearShadowLayer()
            // a hairline of light along the top, as on a raised key
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.8f * d; paint.color = 0x14000000
            c.drawRoundRect(r, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** 经典 on its dark ground, as Apple's dark screens: raised fills, soft words, the card under the table. */
    const val CLASSIC_FILL = 0xff2c2c2e.toInt()
    const val CLASSIC_FILL_ON = 0xfff2f2f7.toInt()
    const val CLASSIC_SOFT = 0xffc7c7cc.toInt()
    const val CLASSIC_CARD = 0xff1c1c1e.toInt()
    const val CLASSIC_GOLD = 0xffffb340.toInt()

    /**
     * Liquid glass (经典): a thin tint over what lies behind (the blurred table), a sheen
     * across its top, a rim bright at the top and again faintly at the bottom, a soft shadow
     * under it. [radiusDp] < 0: round ends. Brightens a little while pressed.
     */
    class Glass(private val d: Float, private val tint: Int = 0x1fffffff, private val radiusDp: Float = -1f,
                private val shadow: Boolean = true) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()
        private var pressed = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            if (now == pressed) return false
            pressed = now; invalidateSelf(); return true
        }
        override fun draw(c: Canvas) {
            r.set(bounds); r.inset(1f * d, 1f * d)
            val radius = if (radiusDp < 0) r.height() / 2 else radiusDp * d
            paint.style = Paint.Style.FILL
            if (shadow) {
                paint.color = 0x01000000
                paint.setShadowLayer(8f * d, 0f, 3f * d, 0x38000000)
                c.drawRoundRect(r, radius, radius, paint)
                paint.clearShadowLayer()
            }
            val a = (tint ushr 24) + if (pressed) 0x18 else 0
            paint.color = (a.coerceAtMost(0xff) shl 24) or (tint and 0xffffff)
            c.drawRoundRect(r, radius, radius, paint)
            // the sheen: light caught across the top half
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0x26ffffff, 0x05ffffff, 0x00ffffff, 0x0dffffff),
                floatArrayOf(0f, 0.45f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, radius, radius, paint)
            // the rim
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * d
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0x8cffffff.toInt(), 0x1affffff, 0x0dffffff, 0x40ffffff),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, radius, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * Behind the whole screen in 经典: the table itself, blown up to cover it, blurred and
     * dimmed, so the glass has the photos' colours to frost (Android 12+; before it, plain).
     */
    class Backdrop(context: android.content.Context, private val source: android.view.View) : android.view.View(context) {
        private var mirroring = false
        private var last = 0L
        private val again = Runnable { invalidate() }
        val live = android.os.Build.VERSION.SDK_INT >= 31

        init {
            if (live) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                48f * resources.displayMetrics.density, 48f * resources.displayMetrics.density, Shader.TileMode.CLAMP))
        }

        /** The table drew a frame: follow it, at most about fifteen times a second. */
        fun poke() {
            if (!live || mirroring) return
            val now = android.os.SystemClock.uptimeMillis()
            removeCallbacks(again)
            if (now - last >= 66) invalidate() else postDelayed(again, 66 - (now - last))
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(org.photoall.film.ui.Style.BG)
            if (!live || source.width == 0 || source.height == 0) { if (Skin.kind != Skin.CAMERA) { c.drawColor(wash or 0xff000000.toInt()); ornament(c) }; return }
            last = android.os.SystemClock.uptimeMillis()
            mirroring = true
            try {
                val k = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
                c.save()
                c.translate((width - source.width * k) / 2, (height - source.height * k) / 2)
                c.scale(k, k)
                source.draw(c)
                c.restore()
            } finally { mirroring = false }
            c.drawColor(wash) // the glass (or the style's paper) and its words stay readable
            ornament(c)
        }

        // The style's ornaments over the wash, drawn once per size.
        private var ornaments: Bitmap? = null
        private fun ornament(c: Canvas) {
            if (Skin.kind == Skin.CAMERA || width == 0 || height == 0) return
            val b = ornaments?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { drawOrnaments(Canvas(it), width.toFloat(), height.toFloat(), resources.displayMetrics.density); ornaments = it }
            c.drawBitmap(b, 0f, 0f, null)
        }
    }

    /**
     * Each style's ornament for the ground behind the light box: 光 an engraved glory of rays
     * from the top and a ruled border; 漫 halftone dots in two corners and a few sparkles;
     * 风 青海波 waves along the bottom and ruled margins; 旁轴 the pebbled vulcanite.
     */
    private fun drawOrnaments(c: Canvas, w: Float, h: Float, d: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (Skin.kind) {
            Skin.SACRED -> {
                p.style = Paint.Style.STROKE
                val cx = w / 2; val cy = -h * 0.04f
                for (i in 0 until 72) {
                    val a = Math.PI * (0.06 + 0.88 * i / 71.0)
                    val len = h * (if (i % 2 == 0) 0.34f else 0.24f)
                    p.strokeWidth = if (i % 2 == 0) 0.9f * d else 0.6f * d
                    p.shader = LinearGradient(cx, cy, cx + (Math.cos(a) * len).toFloat(), cy + (Math.sin(a) * len).toFloat(),
                        0x4da88435, 0x00a88435, Shader.TileMode.CLAMP)
                    c.drawLine(cx, cy, cx + (Math.cos(a) * len).toFloat(), cy + (Math.sin(a) * len).toFloat(), p)
                }
                p.shader = null
                p.color = 0x66161514; p.strokeWidth = 0.8f * d
                c.drawRect(5 * d, 5 * d, w - 5 * d, h - 5 * d, p)
                p.color = 0x99a88435.toInt(); p.strokeWidth = 0.6f * d
                c.drawRect(8 * d, 8 * d, w - 8 * d, h - 8 * d, p)
            }
            Skin.ANIME -> {
                // halftone: dots shrinking away from the top-left and bottom-right corners
                val step = 9 * d
                var y = 0f
                while (y < h) {
                    var x = 0f
                    while (x < w) {
                        val tl = 1f - kotlin.math.hypot(x / w, y / (h * 0.55f)).coerceAtMost(1f)
                        val br = 1f - kotlin.math.hypot((w - x) / w, (h - y) / (h * 0.55f)).coerceAtMost(1f)
                        val k = maxOf(tl, br)
                        if (k > 0.05f) {
                            p.color = if (tl > br) 0x40ff3d86 else 0x2e1b1464
                            c.drawCircle(x + (if (((y / step).toInt()) % 2 == 0) 0f else step / 2), y, 3.2f * d * k, p)
                        }
                        x += step
                    }
                    y += step * 0.866f
                }
                // sparkles
                p.color = 0xccff3d86.toInt()
                for ((fx, fy, s) in listOf(Triple(0.86f, 0.05f, 9f), Triple(0.93f, 0.085f, 5f), Triple(0.08f, 0.93f, 7f))) {
                    val x = w * fx; val yy = h * fy; val k = s * d
                    val path = android.graphics.Path().apply {
                        moveTo(x, yy - k); quadTo(x, yy, x + k, yy); quadTo(x, yy, x, yy + k); quadTo(x, yy, x - k, yy); quadTo(x, yy, x, yy - k); close()
                    }
                    c.drawPath(path, p)
                }
            }
            Skin.FATE -> {
                // 青海波: overlapping arcs of ultramarine rings along the bottom
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.9f * d
                val r = 18 * d
                val band = h * 0.22f
                var row = 0
                var y = h + r * 0.2f
                while (y > h - band) {
                    val fade = ((y - (h - band)) / band).coerceIn(0f, 1f)
                    p.color = ((0x40 * fade).toInt() shl 24) or (ULTRA and 0xffffff)
                    var x = if (row % 2 == 0) 0f else r
                    while (x < w + r) {
                        for (k in 1..3) c.drawArc(x - r * k / 3, y - r * k / 3, x + r * k / 3, y + r * k / 3, 180f, 180f, false, p)
                        x += 2 * r
                    }
                    y -= r * 0.5f; row++
                }
                p.color = 0x59161514; p.strokeWidth = 0.7f * d
                c.drawLine(7 * d, 0f, 7 * d, h, p); c.drawLine(w - 7 * d, 0f, w - 7 * d, h, p)
                p.color = 0x731f2bd0; p.strokeWidth = 0.6f * d
                c.drawLine(10 * d, 0f, 10 * d, h, p); c.drawLine(w - 10 * d, 0f, w - 10 * d, h, p)
            }
            Skin.RANGEFINDER -> {
                p.shader = vulcanite; p.alpha = 150
                c.drawRect(0f, 0f, w, h, p)
            }
        }
    }

    /** Words on the capsule: ink, and the accent deepened for a key that is on. */
    const val CAPSULE_INK = 0xff1c1c1e.toInt()
    const val CAPSULE_ON = 0xffb4690e.toInt()

    /** The table's colour with the lamp off, before the user picks one. */
    fun offDefault(fallback: Int): Int = when (Skin.kind) {
        Skin.SACRED -> 0xffe2dccb.toInt()
        Skin.CAMERA -> 0xff272c2e.toInt()
        Skin.RANGEFINDER -> 0xff1d1d1d.toInt()
        Skin.ANIME -> 0xffffe6f0.toInt()
        Skin.FATE -> 0xffd9d7cf.toInt()
        else -> fallback
    }

    /** The china marker's colour. */
    fun pencil(fallback: Int): Int = when (Skin.kind) {
        Skin.SACRED -> 0xffb23a25.toInt()   // rubric
        Skin.CAMERA -> 0xffe3b341.toInt()   // amber grease pencil
        Skin.RANGEFINDER -> 0xffe0362a.toInt()
        Skin.ANIME -> 0xffff3d86.toInt()
        Skin.FATE -> ULTRA
        else -> fallback
    }

    /** The roll's title. */
    fun titleFace(fallback: Typeface): Typeface = when (Skin.kind) {
        Skin.SACRED, Skin.FATE -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        Skin.CAMERA -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        Skin.RANGEFINDER -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        Skin.ANIME -> Typeface.create("sans-serif-black", Typeface.ITALIC)
        else -> fallback
    }

    /** The small words under the tools and on the chips. */
    fun labelFace(fallback: Typeface): Typeface = when (Skin.kind) {
        Skin.SACRED, Skin.FATE -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        Skin.CAMERA -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        Skin.RANGEFINDER -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        Skin.ANIME -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        else -> fallback
    }

    /** A key in the style (round when [radiusDp] < 0), or null for the plain themes. */
    fun key(density: Float, radiusDp: Float = -1f): Drawable? = if (skinned) Skin.Key(density, radiusDp) else null

    /** The housing round the lit diffuser, or null for the plain themes' own. */
    fun housing(density: Float): Drawable? = if (skinned) Housing(Skin.kind, density) else null

    private class Housing(private val kind: Int, private val d: Float, private val cornerDp: Float = -1f) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val leather: BitmapShader? = if (kind == Skin.CAMERA || kind == Skin.RANGEFINDER) {
            val s = 48
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val rnd = java.util.Random(5)
            for (y in 0 until s) for (x in 0 until s) {
                val v = rnd.nextFloat()
                b.setPixel(x, y, if (v < 0.12f) 0xff2a2a2b.toInt() else if (v > 0.86f) 0xff101011.toInt() else 0xff1b1b1c.toInt())
            }
            BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        } else null

        override fun draw(c: Canvas) {
            r.set(bounds)
            p.shader = null
            p.style = Paint.Style.FILL
            when (kind) {
                Skin.SACRED -> {
                    // bone, an ink rule and a gold one inside it, a gold stud at each corner
                    val k = if (cornerDp >= 0) cornerDp * d else 4 * d
                    p.color = BONE
                    c.drawRoundRect(r, k, k, p)
                    p.style = Paint.Style.STROKE
                    p.color = INK; p.strokeWidth = 1.1f * d
                    r.inset(0.6f * d, 0.6f * d); c.drawRoundRect(r, k, k, p)
                    p.color = GOLD; p.strokeWidth = 0.7f * d
                    r.inset(3 * d, 3 * d); c.drawRoundRect(r, 2 * d, 2 * d, p)
                    p.style = Paint.Style.FILL
                    for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom))
                        c.drawCircle(x, y, 2.2f * d, p)
                }
                Skin.CAMERA -> {
                    // black leatherette in a satin chrome bezel
                    p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xff8d8c87.toInt(), 0xff4a4945.toInt(), 0xff2b2b2a.toInt()), null, Shader.TileMode.CLAMP)
                    c.drawRoundRect(r, 12 * d, 12 * d, p)
                    p.shader = leather
                    r.inset(2.2f * d, 2.2f * d)
                    c.drawRoundRect(r, 10 * d, 10 * d, p)
                    p.shader = null
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d; p.color = 0xff55544f.toInt()
                    c.drawRoundRect(r, 10 * d, 10 * d, p)
                }
                Skin.RANGEFINDER -> {
                    // vulcanite in a bright chrome edge, as a rangefinder's top plate meets its body
                    val k = if (cornerDp >= 0) cornerDp * d else 8 * d
                    p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xffefeee9.toInt(), 0xff9a9993.toInt(), 0xff55544f.toInt()), null, Shader.TileMode.CLAMP)
                    c.drawRoundRect(r, k, k, p)
                    p.shader = leather
                    r.inset(2 * d, 2 * d)
                    c.drawRoundRect(r, k - 2 * d, k - 2 * d, p)
                    p.shader = null
                }
                Skin.ANIME -> {
                    // white, a thick navy line, its shadow offset down and right
                    val lip = 4 * d
                    val k = if (cornerDp >= 0) cornerDp * d else 18 * d
                    p.color = NAVY
                    c.drawRoundRect(r.left + lip, r.top + lip, r.right, r.bottom, k, k, p)
                    p.color = 0xffffffff.toInt()
                    c.drawRoundRect(r.left, r.top, r.right - lip, r.bottom - lip, k, k, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * d; p.color = NAVY
                    c.drawRoundRect(r.left + 1.3f * d, r.top + 1.3f * d, r.right - lip - 1.3f * d, r.bottom - lip - 1.3f * d, k - d, k - d, p)
                }
                Skin.FATE -> {
                    // paper, an ink hairline, an ultramarine one inside it
                    val k = if (cornerDp >= 0) minOf(cornerDp, 3f) * d else 2 * d
                    p.color = 0xffefede6.toInt()
                    c.drawRoundRect(r, k, k, p)
                    p.style = Paint.Style.STROKE
                    p.color = INK; p.strokeWidth = 1 * d
                    r.inset(0.5f * d, 0.5f * d); c.drawRoundRect(r, k, k, p)
                    p.color = ULTRA; p.strokeWidth = 0.7f * d
                    r.inset(3.2f * d, 3.2f * d); c.drawRect(r, p)
                }
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
