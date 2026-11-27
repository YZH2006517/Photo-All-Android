package org.photoall.film.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * One theme: the same named colours, different values. Photos, the viewfinder and the
 * histogram are never tinted by a theme; readouts over the viewfinder keep a dark [scrim].
 */
class Palette(
    val name: String,
    val bg: Int, val surface: Int, val surface2: Int, val hairline: Int,
    val text: Int, val text2: Int, val text3: Int,
    val accent: Int, val onAccent: Int, val scrim: Int,
    val light: Boolean = false,
)

/**
 * The app's one visual language: minimal and professional. A warm ground, hairlines,
 * no decoration; a single accent marks only what is on or selected. Numbers use
 * tabular figures so readouts do not jitter. The colours come from the chosen theme
 * ([apply] at each screen's start); a theme change recreates the screens.
 */
object Style {
    /** In the order of the settings' theme choice. Text pairs keep a contrast of 4.5 or more. */
    val palettes = listOf(
        // The default, in Apple's manner: a light frosted grey (the latest photo's hue faint in
        // it), white frosted glass for every panel and key, ink text, Apple's blue for what is on.
        // The camera and 拼贴 keep the dark glass ([GLASS_DARK]), as Apple's Camera is black.
        Palette("暗盒", 0xfff2f2f7.toInt(), 0x99ffffff.toInt(), 0xe6ffffff.toInt(), 0x1a000000,
            0xff1c1c1e.toInt(), 0xff6c6c70.toInt(), 0xffaeaeb2.toInt(), 0xff007aff.toInt(), 0xffffffff.toInt(), 0xcc1c1c1e.toInt(),
            light = true),
        // Night: only red light, so the eye stays dark-adapted.
        Palette("暗房红灯", 0xff000000.toInt(), 0xff140504.toInt(), 0xff230907.toInt(), 0xff3a0e0a.toInt(),
            0xffff6a55.toInt(), 0xffd9503c.toInt(), 0xff6a1e15.toInt(), 0xffff3b22.toInt(), 0xff000000.toInt(), 0xd9000000.toInt()),
        // Daylight: paper white and ink.
        Palette("相纸", 0xffefeae0.toInt(), 0xffe2dbcd.toInt(), 0xffd4cbb9.toInt(), 0xffc9c0ae.toInt(),
            0xff1b1a18.toInt(), 0xff5e574d.toInt(), 0xff9a9286.toInt(), 0xff9a360b.toInt(), 0xffffffff.toInt(), 0xd90b0b0a.toInt(),
            light = true),
        Palette("黄铜", 0xff161813.toInt(), 0xff20231c.toInt(), 0xff2b2f25.toInt(), 0xff363a2f.toInt(),
            0xffece6d6.toInt(), 0xffa9a390.toInt(), 0xff5f5c50.toInt(), 0xffc9a45c.toInt(), 0xff161813.toInt(), 0xd9101210.toInt()),
        Palette("冷银", 0xff0e1012.toInt(), 0xff171a1d.toInt(), 0xff21252a.toInt(), 0xff2b3036.toInt(),
            0xffeef1f4.toInt(), 0xff9aa3ad.toInt(), 0xff525a63.toInt(), 0xff8cc8e6.toInt(), 0xff0e1012.toInt(), 0xd90e1012.toInt()),
    )

    /** 暗盒 in the dark: the camera's screen and 拼贴's (white words on a dark photo ground). */
    val GLASS_DARK = Palette("暗盒", 0xff101012.toInt(), 0x17ffffff, 0x38ffffff, 0x1affffff,
        0xfff5f5f7.toInt(), 0xffa1a1a6.toInt(), 0xff5c5c61.toInt(), 0xffffd60a.toInt(), 0xff1c1c1e.toInt(), 0xcc101012.toInt())

    /** Screens that keep the dark liquid glass in 暗盒: the camera (black, as Apple's) and the editor. */
    private val DARK_GLASS = setOf("EditActivity")

    /**
     * 暗盒 on the camera: the frosted grey of the chooser (Photo All's first screen) — a lit grey
     * ground, panes of frosted glass a little lighter than it, white words, yellow for what is on.
     */
    val GLASS_CAMERA = Palette("暗盒", 0xff2a2a2d.toInt(), 0x2effffff, 0x47ffffff, 0x1fffffff,
        0xfff5f5f7.toInt(), 0xffaeaeb2.toInt(), 0xff636366.toInt(), 0xffffd60a.toInt(), 0xff1c1c1e.toInt(), 0xcc1c1c1e.toInt())

    var palette: Palette = palettes[0]
        private set

    val BG get() = palette.bg
    val SURFACE get() = palette.surface        // buttons, panels
    val SURFACE_2 get() = palette.surface2     // selected / raised
    /** The chosen entry of the theme list: 0-4 the themes, past them the camera styles (Skin). */
    var themeIndex = 0
        private set
    val HAIRLINE get() = palette.hairline
    val TEXT get() = palette.text
    val TEXT_2 get() = palette.text2           // secondary, inactive controls
    val TEXT_3 get() = palette.text3           // disabled
    val ACCENT get() = palette.accent          // on / selected only
    val ON_ACCENT get() = palette.onAccent     // text on an accent fill
    val SCRIM get() = palette.scrim            // panels over a photo: always dark

    /** The default theme: frosted glass, a lit ground, round corners (see [Glass], [ground]). */
    val glass get() = themeIndex == 0

    /**
     * The ground of a screen. Glass: the latest photo, blurred to colour and dimmed, so the
     * frosted panels over it take its hues (a blue photo, a blue screen; a warm one, warm);
     * before any photo, a neutral dark. Other themes: the flat [BG].
     */
    fun ground(): Drawable = if (!glass) android.graphics.drawable.ColorDrawable(BG)
        else if (fixedGround) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (palette.light) intArrayOf(0xffd1d1d6.toInt(), 0xffc7c7cc.toInt(), 0xffbdbdc2.toInt())
            else intArrayOf(0xff48484b.toInt(), 0xff2e2e31.toInt(), 0xff1b1b1d.toInt())) // the camera: the chooser's frosted grey, lit from above, fixed
        else Ambient(dark = !palette.light)

    /** This screen keeps a fixed grey ground (the camera: nothing re-rendered behind it). */
    private var fixedGround = false

    /** The photo the glass ground is made of: tiny, blurred, a little deeper in colour. */
    @Volatile var ambient: android.graphics.Bitmap? = null
        private set
    /** The same photo for the light glass: a pale frosted grey with its hue faint in it. */
    @Volatile var ambientLight: android.graphics.Bitmap? = null
        private set
    private val grounds = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Ambient, Boolean>())
    private var ambientKey: Any? = null

    /** A new photo for the ground (any size; [key] skips the same photo twice). Any thread. */
    fun setAmbient(photo: android.graphics.Bitmap, key: Any? = null) {
        if (key != null && key == ambientKey) return
        ambientKey = key
        // a thumbnail may be a hardware bitmap (no pixel access): a software copy first
        val soft = if (photo.config == android.graphics.Bitmap.Config.HARDWARE) photo.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return else photo
        val small = android.graphics.Bitmap.createScaledBitmap(soft, 24, maxOf(8, 24 * soft.height / maxOf(1, soft.width)), true)
        val w = small.width; val h = small.height
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        // a box blur, three passes each way on the tiny copy: only the colours stay
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        for (i in px.indices) { r[i] = ((px[i] shr 16) and 255).toFloat(); g[i] = ((px[i] shr 8) and 255).toFloat(); b[i] = (px[i] and 255).toFloat() }
        repeat(3) { for (ch in listOf(r, g, b)) blur(ch, w, h) }
        val pl = IntArray(w * h)
        for (i in px.indices) {
            // deeper and a little richer, dark enough for white words on it
            val l = 0.3f * r[i] + 0.59f * g[i] + 0.11f * b[i]
            fun c(v: Float) = ((l + (v - l) * 1.5f) * 0.56f).coerceIn(0f, 255f).toInt()
            px[i] = (0xff shl 24) or (c(r[i]) shl 16) or (c(g[i]) shl 8) or c(b[i])
            // light: Apple's pale grey, the photo's hue only a breath in it, ink-dark words readable
            fun q(v: Float) = (236f + (v - l) * 0.16f + (l - 128f) * 0.03f).coerceIn(0f, 255f).toInt()
            pl[i] = (0xff shl 24) or (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i])
        }
        val out = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val outLight = android.graphics.Bitmap.createBitmap(pl, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ambientLight = outLight
            ambient = out
            grounds.toList().forEach { it.invalidateSelf() }
        }
    }

    private fun blur(ch: FloatArray, w: Int, h: Int) {
        val t = ch.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dx in -2..2) { val xx = x + dx; if (xx in 0 until w) { s += t[y * w + xx]; n++ } }
            ch[y * w + x] = s / n
        }
        val u = ch.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in -2..2) { val yy = y + dy; if (yy in 0 until h) { s += u[yy * w + x]; n++ } }
            ch[y * w + x] = s / n
        }
    }

    /** Where [bmp] lies when it covers a [w] x [h] window (centred, cropped). */
    fun coverMatrix(bmp: android.graphics.Bitmap, w: Float, h: Float) = android.graphics.Matrix().apply {
        val k = maxOf(w / bmp.width, h / bmp.height)
        setScale(k, k)
        postTranslate((w - bmp.width * k) / 2, (h - bmp.height * k) / 2)
    }

    /** The glass ground: [ambient] stretched to cover (its bilinear scaling is the blur), shaded below. */
    class Ambient(val dark: Boolean = true) : Drawable() {
        /** This screen's own ground (the editor: its photo's colours), in place of the latest photo's. */
        var own: android.graphics.Bitmap? = null
            set(v) { field = v; invalidateSelf() }
        /** What this ground shows (and the glass over it bends). */
        fun image() = own ?: if (dark) ambient else ambientLight
        private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val shade = android.graphics.Paint()
        private val dst = android.graphics.RectF()
        init { grounds += this }
        override fun draw(c: android.graphics.Canvas) {
            val bmp = image()
            val bw = bounds.width().toFloat(); val bh = bounds.height().toFloat()
            if (bmp == null) {
                c.drawColor(if (dark) 0xff121214.toInt() else 0xfff2f2f7.toInt())
            } else {
                val k = maxOf(bw / bmp.width, bh / bmp.height)
                val dw = bmp.width * k; val dh = bmp.height * k
                dst.set(bounds.left + (bw - dw) / 2, bounds.top + (bh - dh) / 2, bounds.left + (bw + dw) / 2, bounds.top + (bh + dh) / 2)
                c.drawBitmap(bmp, null, dst, paint)
            }
            shade.shader = android.graphics.LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
                if (dark) intArrayOf(0x14000000, 0x33000000, 0x8c000000.toInt()) else intArrayOf(0x59ffffff, 0x14ffffff, 0x0a000000),
                floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(bounds, shade)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    /** At an activity's start: the latest photo as the ground, read once in the background. */
    fun loadAmbient(context: Context) {
        if (ambient != null || ambientKey != null) return
        ambientKey = "loading"
        val app = context.applicationContext
        Thread {
            runCatching {
                val p = org.photoall.film.gallery.Photos.list(app).firstOrNull() ?: return@runCatching
                val thumb = app.contentResolver.loadThumbnail(p.uri, android.util.Size(96, 96), null)
                setAmbient(thumb, p.uri)
            }
        }.start()
    }

    /**
     * Frosted glass: a thin white tint over what lies behind, light caught across its top,
     * a rim bright at the top and faint below. Brightens a little while pressed.
     */
    class Glass(private val d: Float, private val tint: Int, private val radius: Float,
                private val rim: Boolean = true) : Drawable() {
        /** Over the light ground (fixed when made: a screen behind keeps its own look). */
        private val light = palette.light
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()
        private var pressed = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            if (now == pressed) return false
            pressed = now; invalidateSelf(); return true
        }
        override fun draw(c: android.graphics.Canvas) {
            r.set(bounds); r.inset(0.5f * d, 0.5f * d)
            val rad = minOf(radius, r.height() / 2, r.width() / 2)
            paint.style = android.graphics.Paint.Style.FILL
            if (tint ushr 24 != 0 && refract(c, rad)) { edges(c, rad); return }
            val a = ((tint ushr 24) + if (pressed) 0x16 else 0).coerceAtMost(0xff)
            // lifted on the light ground: a soft shadow under the pane. Not on the dark one: there it
            // hardly shows, and what spills past the view is cut square by its parent (a box round
            // the rounded corners)
            if (rim && tint ushr 24 != 0 && light) {
                // the pane itself drawn over it
                paint.color = 0x01000000
                paint.setShadowLayer(if (light) 14f * d else 9f * d, 0f, 3f * d, if (light) 0x1f000000 else 0x40000000)
                c.drawRoundRect(r, rad, rad, paint)
                paint.clearShadowLayer()
            }
            paint.color = (a shl 24) or (tint and 0xffffff)
            c.drawRoundRect(r, rad, rad, paint)
            if (tint ushr 24 == 0 && !pressed) return // a clear key: nothing but its press
            edges(c, rad)
        }

        /** The sheen across the top and the rim of light. */
        private fun edges(c: android.graphics.Canvas, rad: Float) {
            paint.style = android.graphics.Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom,
                intArrayOf(0x24ffffff, 0x06ffffff, 0x00ffffff, 0x08ffffff), floatArrayOf(0f, 0.45f, 0.75f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, paint)
            if (rim) {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 1f * d
                paint.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom,
                    if (light) intArrayOf(0xe6ffffff.toInt(), 0x66ffffff, 0x0f000000, 0x1a000000)
                    else intArrayOf(0x73ffffff, 0x1affffff, 0x0affffff, 0x2effffff), floatArrayOf(0f, 0.3f, 0.7f, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
                c.drawRoundRect(r, rad, rad, paint)
            }
            paint.shader = null
        }
        private var shader: Any? = null

        /**
         * Liquid glass (Android 13+, over a photo ground): the ground under the pane drawn
         * again through it, bent inward near its edges as a thick pane's rim bends light,
         * a hair of colour split at the very edge, frosted a little. False: plain frosted.
         */
        private fun refract(c: android.graphics.Canvas, rad: Float): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 33 || !c.isHardwareAccelerated) return false
            val view = callback as? View ?: return false
            val act = view.context as? android.app.Activity ?: return false
            val ground = act.window?.decorView?.background as? Ambient ?: return false
            val bmp = ground.image() ?: return false
            val root = view.rootView
            if (root.width == 0) return false
            val loc = IntArray(2); view.getLocationInWindow(loc)
            if (shader == false) return false // the shader would not compile here: plain frosted
            val rs = (shader as? android.graphics.RuntimeShader)
                ?: runCatching { android.graphics.RuntimeShader(REFRACT) }
                    .onFailure { android.util.Log.w("Glass", "refraction shader", it); shader = false }.getOrNull()?.also { shader = it }
                ?: return false
            val img = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            img.setLocalMatrix(coverMatrix(bmp, root.width.toFloat(), root.height.toFloat()).apply {
                postTranslate(-loc[0].toFloat(), -loc[1].toFloat()) // into the view's own coordinates
            })
            img.filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR
            rs.setInputShader("img", img)
            rs.setFloatUniform("box", r.left, r.top, r.right, r.bottom)
            rs.setFloatUniform("radius", rad)
            rs.setFloatUniform("edge", minOf(16f * d, r.height() / 2))
            rs.setFloatUniform("bend", 10f * d)
            rs.setFloatUniform("winY", loc[1].toFloat(), root.height.toFloat())
            rs.setFloatUniform("frost", if (ground.dark) (if (pressed) 0.2f else 0.1f) else (if (pressed) 0.62f else 0.5f))
            rs.setFloatUniform("lit", if (ground.dark) 0f else 1f)
            paint.shader = rs
            paint.color = 0xffffffff.toInt()
            c.drawRoundRect(r, rad, rad, paint)
            paint.shader = null
            return true
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private const val REFRACT = """
        uniform shader img;
        uniform float4 box;
        uniform float radius;
        uniform float edge;
        uniform float bend;
        uniform float2 winY;
        uniform float frost;
        uniform float lit;
        float sd(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }
        float shade(float y) { // the ground's own darkening, top to bottom (see Ambient)
            float t = clamp((y + winY.x) / winY.y, 0.0, 1.0);
            return t < 0.5 ? mix(0.078, 0.2, t * 2.0) : mix(0.2, 0.55, (t - 0.5) * 2.0);
        }
        half4 main(float2 p) {
            float2 c = (box.xy + box.zw) * 0.5;
            float2 h = (box.zw - box.xy) * 0.5;
            float2 lp = p - c;
            float d = sd(lp, h, radius);
            float t = clamp(1.0 + d / edge, 0.0, 1.0);
            float2 n = float2(sd(lp + float2(1, 0), h, radius) - sd(lp - float2(1, 0), h, radius),
                              sd(lp + float2(0, 1), h, radius) - sd(lp - float2(0, 1), h, radius));
            n = n / max(length(n), 1e-4);
            float k = t * t * t * bend;
            float2 q = p - n * k - lp * 0.05;
            half3 col = half3(img.eval(q - n * k * 0.25).r, img.eval(q).g, img.eval(q + n * k * 0.25).b);
            col *= half(1.0 - shade(p.y) * (1.0 - lit));
            col = mix(col, half3(1.0), half(frost + t * 0.06));
            return half4(col, 1.0);
        }
    """

    /**
     * Before super.onCreate: the chosen theme's colours, the matching platform theme
     * (dialogs, switches) and window / system-bar colours.
     */
    fun apply(activity: android.app.Activity, darkGlass: Boolean = false) {
        val index = org.photoall.film.settings.SettingsStore(activity)[org.photoall.film.settings.Settings.THEME].toInt()
        // The theme list runs on into the camera styles: past the themes, a style with its own colours.
        val store = org.photoall.film.settings.SettingsStore(activity)
        var theme = index
        val raw = activity.getSharedPreferences("film-settings", android.content.Context.MODE_PRIVATE)
        val oldSkin = raw.getFloat(org.photoall.film.settings.Settings.SKIN, 0f).toInt()
        if (oldSkin > 0) { // from the build that kept them apart
            theme = palettes.size + oldSkin - 1
            raw.edit().remove(org.photoall.film.settings.Settings.SKIN).apply()
            store[org.photoall.film.settings.Settings.THEME] = theme.toFloat()
        }
        applyTheme(activity, theme, darkGlass)
    }

    /** The colours and style of theme [theme] (an index into the themes, then the styles), nothing else. */
    fun use(theme: Int) {
        palette = palettes[theme.coerceIn(0, palettes.lastIndex)]
        themeIndex = theme.coerceAtLeast(0)
        Skin.load(if (theme >= palettes.size) theme - palettes.size + 1 else 0)?.let { palette = it }
    }

    /** The theme chosen in the settings (the app's own; a screen with a style of its own puts it back with [use]). */
    fun storedTheme(context: android.content.Context) =
        org.photoall.film.settings.SettingsStore(context)[org.photoall.film.settings.Settings.THEME].toInt()

    /** [apply] with a given theme: a screen with a style of its own (the light table). */
    fun applyTheme(activity: android.app.Activity, theme: Int, darkGlass: Boolean = false) {
        use(theme)
        if (theme == 0 && (darkGlass || activity.javaClass.simpleName in DARK_GLASS)) palette = GLASS_DARK
        // the camera and the album: the chooser's frosted grey (white words on glass over a deep ground)
        if (theme == 0 && activity.javaClass.simpleName in setOf("CameraActivity", "GalleryActivity")) palette = GLASS_CAMERA
        activity.setTheme(if (palette.light) org.photoall.film.R.style.FilmThemeLight else org.photoall.film.R.style.FilmTheme)
        fixedGround = activity.javaClass.simpleName == "CameraActivity"
        if (glass) loadAmbient(activity)
        activity.window.setBackgroundDrawable(ground())
        // glass: the bars see-through, the photo's colour runs under them
        activity.window.navigationBarColor = if (glass) Color.TRANSPARENT else BG
        activity.window.statusBarColor = if (glass) Color.TRANSPARENT else BG
        val appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.decorView.post {
            activity.window.insetsController?.setSystemBarsAppearance(if (palette.light) appearance else 0, appearance)
        }
    }

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).roundToInt()

    /** A rounded fill with a ripple in the text colour; [stroke] draws a hairline outline. */
    fun surface(context: Context, fill: Int = SURFACE, radiusDp: Float = 8f, stroke: Int = 0): Drawable {
        if (glass) {
            // glass: corners rounder, the fill as frosted glass (an opaque fill, the accent, kept solid)
            val d = context.resources.displayMetrics.density
            val radius = maxOf(radiusDp * 1.5f, 12f) * d
            if ((fill ushr 24) == 0xff) return GradientDrawable().apply { cornerRadius = radius; setColor(fill) }
            return Glass(d, if (fill == Color.TRANSPARENT && stroke != 0) (if (palette.light) 0x66ffffff else 0x0dffffff) else fill, radius, rim = fill != Color.TRANSPARENT || stroke != 0)
        }
        val shape = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, dp(context, 0.75f)), stroke)
        }
        val mask = GradientDrawable().apply { cornerRadius = dp(context, radiusDp).toFloat(); setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(TEXT and 0x00ffffff or 0x33000000), shape, mask)
    }

    /**
     * Press feedback for anything tappable: it sinks a little under the finger and comes
     * back with a slight spring. Replaces the platform's elevation animator.
     */
    /**
     * A double tap on [bar] calls [reset] (that one setting back to its default); drags and
     * single taps stay the bar's own.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun doubleTapReset(bar: android.widget.SeekBar, reset: () -> Unit) {
        // the second tap is the reset's alone: handed on, the bar would move to the finger after it
        var eaten = false
        val taps = android.view.GestureDetector(bar.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean { eaten = true; reset(); return true }
        })
        bar.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) eaten = false
            taps.onTouchEvent(e)
            val swallow = eaten
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) eaten = false
            swallow
        }
    }

    fun pressable(view: View) {
        val down = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.95f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.95f)).setDuration(90)
        val up = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)).setDuration(180).apply {
            interpolator = android.view.animation.OvershootInterpolator(2f)
        }
        view.stateListAnimator = android.animation.StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), up)
        }
    }

    /**
     * A text button that never cuts its text: no font padding, no vertical padding, centred,
     * one line; the caller's height then fits any system font size.
     */
    fun compact(b: TextView, horizontalDp: Float = 12f) {
        b.includeFontPadding = false
        b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
        val h = dp(b.context, horizontalDp)
        b.setPadding(h, 0, h, 0)
        b.gravity = android.view.Gravity.CENTER
        b.isSingleLine = true
        b.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** Readouts: tabular figures, so changing numbers keep their width. */
    fun numeric(view: TextView) {
        view.fontFeatureSettings = "tnum"
    }

    /** A control button: surface, medium weight, secondary text until it is on. */
    fun button(b: Button, sizeSp: Float = 13f) {
        b.includeFontPadding = false
        b.background = surface(b.context)
        b.setTextColor(TEXT_2)
        b.textSize = sizeSp
        b.typeface = medium
        b.isAllCaps = false
        b.stateListAnimator = null
        b.elevation = 0f
        numeric(b)
    }

    /** A plain text action (bars, dialogs): no fill. */
    fun textButton(b: Button, sizeSp: Float = 14f) {
        b.includeFontPadding = false
        b.background = surface(b.context, fill = Color.TRANSPARENT)
        b.setTextColor(TEXT)
        b.textSize = sizeSp
        b.typeface = medium
        b.isAllCaps = false
        b.stateListAnimator = null
        b.elevation = 0f
    }

    /** On / off controls: the accent when on, secondary grey when off. */
    fun active(view: TextView, on: Boolean) {
        view.setTextColor(if (on) ACCENT else TEXT_2)
    }

    /** One of a set (a lens): raised surface and accent text when chosen. */
    fun selected(b: Button, chosen: Boolean) {
        b.background = surface(b.context, fill = if (chosen) SURFACE_2 else Color.TRANSPARENT, radiusDp = 18f)
        b.setTextColor(if (chosen) ACCENT else TEXT_2)
    }

    /** Text that only applies when something is on: full when it does, dim when not. */
    fun enabled(view: TextView, on: Boolean, normal: Int = TEXT) {
        view.setTextColor(if (on) normal else TEXT_3)
    }

    /** A section title: small, spaced, grey. */
    fun sectionTitle(view: TextView) {
        view.textSize = 11f
        view.letterSpacing = 0.12f
        view.typeface = medium
        view.setTextColor(TEXT_2)
    }

    fun hairline(context: Context) = View(context).apply { setBackgroundColor(HAIRLINE) }

    /** The shutter, in the theme's metal and finish (see [ShutterFace]). */
    fun shutter(context: Context): Drawable = if (glass && !Skin.active) AppleShutter(context.resources.displayMetrics.density) else ShutterFace(context.resources.displayMetrics.density,
        ShutterFace.materials[palettes.indexOf(palette).coerceIn(0, ShutterFace.materials.lastIndex)])

    /**
     * Apple's shutter. On the dark camera: a white ring, a dark gap, a white disc. On the light grey
     * one: a ring of white frosted glass lifted off the ground by a soft shadow, a fine groove
     * inside it, and a raised white disc lit from above; the disc gives a little while pressed.
     */
    class AppleShutter(private val d: Float) : Drawable() {
        private val light = palette.light
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var pressed = false
        private var k = 1f
        private var anim: android.animation.ValueAnimator? = null
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed)
            if (now == pressed) return false
            pressed = now
            anim?.cancel()
            anim = android.animation.ValueAnimator.ofFloat(k, if (now) 0.88f else 1f).apply {
                duration = if (now) 90 else 180
                addUpdateListener { k = it.animatedValue as Float; invalidateSelf() }
                start()
            }
            return true
        }
        override fun draw(c: android.graphics.Canvas) {
            val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
            val r = minOf(bounds.width(), bounds.height()) / 2f - 1f * d
            if (light) { frosted(c, cx, cy, r - 3f * d); return }
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 4f * d
            paint.color = 0xffffffff.toInt()
            c.drawCircle(cx, cy, r - 2f * d, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = if (pressed) 0xffd1d1d6.toInt() else 0xffffffff.toInt()
            c.drawCircle(cx, cy, (r - 7.5f * d) * k, paint)
        }

        private fun frosted(c: android.graphics.Canvas, cx: Float, cy: Float, r: Float) {
            val G = android.graphics.Shader.TileMode.CLAMP
            paint.shader = null; paint.style = android.graphics.Paint.Style.FILL
            // the ring: white glass, lifted
            paint.color = 0x01000000
            paint.setShadowLayer(10f * d, 0f, 4f * d, 0x38000000); c.drawCircle(cx, cy, r, paint)
            paint.setShadowLayer(1.5f * d, 0f, 0.8f * d, 0x26000000); c.drawCircle(cx, cy, r, paint)
            paint.clearShadowLayer()
            paint.color = 0xff000000.toInt() // a shader takes the paint's alpha: full for the fills below
            paint.shader = android.graphics.LinearGradient(0f, cy - r, 0f, cy + r, 0xffffffff.toInt(), 0xfff7f7f9.toInt(), G)
            c.drawCircle(cx, cy, r, paint)
            // its rim catching the light at the top
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1f * d
            paint.shader = android.graphics.LinearGradient(0f, cy - r, 0f, cy + r, intArrayOf(0xffffffff.toInt(), 0x00ffffff, 0x1a000000), null, G)
            c.drawCircle(cx, cy, r - 0.5f * d, paint)
            // the groove
            val inner = r - 6f * d
            paint.shader = null; paint.strokeWidth = 1.2f * d; paint.color = 0x24000000
            c.drawCircle(cx, cy, inner + 0.6f * d, paint)
            // the disc: raised, lit from above; darker while pressed
            val dr = (inner - 1.5f * d) * k
            paint.style = android.graphics.Paint.Style.FILL; paint.color = 0x01000000
            paint.setShadowLayer(3f * d, 0f, 1.5f * d, 0x40000000); c.drawCircle(cx, cy, dr, paint); paint.clearShadowLayer()
            paint.color = 0xff000000.toInt()
            paint.shader = android.graphics.LinearGradient(0f, cy - dr, 0f, cy + dr,
                if (pressed) intArrayOf(0xffececf0.toInt(), 0xffdcdce1.toInt()) else intArrayOf(0xffffffff.toInt(), 0xfff9f9fb.toInt()), null, G)
            c.drawCircle(cx, cy, dr, paint)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 0.8f * d
            paint.shader = android.graphics.LinearGradient(0f, cy - dr, 0f, cy + dr, intArrayOf(0xffffffff.toInt(), 0x14000000), null, G)
            c.drawCircle(cx, cy, dr - 0.4f * d, paint)
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun slider(seek: SeekBar) {
        seek.progressTintList = ColorStateList.valueOf(TEXT)
        seek.progressBackgroundTintList = ColorStateList.valueOf(HAIRLINE)
        seek.thumbTintList = ColorStateList.valueOf(TEXT)
    }

    fun switch(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(TEXT, TEXT_2))
        sw.trackTintList = ColorStateList(states, intArrayOf(ACCENT, HAIRLINE))
    }
}
