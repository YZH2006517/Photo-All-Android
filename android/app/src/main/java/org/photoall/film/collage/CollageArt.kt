package org.photoall.film.collage

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.DiscretePathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * How a collage is drawn: the prints a photo sits on (相纸) with their own colour and surface
 * (质感), the words laid over it (文字), the ground under all (背景). Everything is measured in
 * shares of the board's width [W], so the screen and the saved picture are the same drawing.
 */
object CollageArt {

    /**
     * A print's make: margins (top, side, bottom; shares of its width), a window shape of its
     * own (height over width) or null, its usual colour and surface.
     */
    enum class Paper(val label: String, val top: Float, val side: Float, val bottom: Float, val window: Float?,
                     val color: Int, val surface: Surface) {
        CARD("白卡", 0.15f, 0.09f, 0.19f, null, 0xfff1ede4.toInt(), Surface.LINEN),
        THIN("细边", 0.035f, 0.035f, 0.035f, null, 0xfffbfaf7.toInt(), Surface.MATTE),
        INSTANT("拍立得", 0.06f, 0.06f, 0.24f, 0.97f, 0xfff7f6f1.toInt(), Surface.MATTE),
        MINI("迷你拍立得", 0.075f, 0.065f, 0.22f, 62f / 46f, 0xfff7f6f1.toInt(), Surface.MATTE),
        SQUARE("方形拍立得", 0.065f, 0.065f, 0.2f, 1f, 0xfff7f6f1.toInt(), Surface.MATTE),
        WIDE("宽幅拍立得", 0.045f, 0.045f, 0.15f, 62f / 99f, 0xfff7f6f1.toInt(), Surface.MATTE),
        FILM("135 底片", 0f, 0f, 0f, null, 0xff151311.toInt(), Surface.GLOSS),
        MOUNT("幻灯片", 0.13f, 0.13f, 0.13f, null, 0xffdcd8cf.toInt(), Surface.MATTE),
        POSTCARD("明信片", 0.055f, 0.055f, 0.28f, null, 0xfff2ecdd.toInt(), Surface.FIBRE),
        DECKLE("毛边相纸", 0.065f, 0.065f, 0.065f, null, 0xfff4f0e6.toInt(), Surface.FIBRE),
        TAPE("胶带", 0.022f, 0.022f, 0.022f, null, 0xfffdfcf9.toInt(), Surface.MATTE),
        BARE("无边", 0f, 0f, 0f, null, 0x00000000, Surface.GLOSS),
    }

    /** A paper's surface, laid over its colour. */
    enum class Surface(val label: String) { GLOSS("光面"), MATTE("亚光"), LINEN("亚麻"), FIBRE("纸纹"), GRAIN("颗粒") }

    /** Paper colours to choose from (the photo's own tones are offered beside them). */
    val PAPER_COLORS = intArrayOf(
        0xfffbfaf7.toInt(), 0xfff1ede4.toInt(), 0xffe7dcc6.toInt(), 0xffb48a5c.toInt(), 0xff9a9a96.toInt(),
        0xff2b2b2d.toInt(), 0xff151311.toInt(), 0xff2f4a6d.toInt(), 0xff3f5640.toInt(), 0xff6e2a2e.toInt(), 0xffe9c9c4.toInt(),
    )

    /** The ground under the board. */
    enum class Ground(val label: String, val light: Int, val dark: Int) {
        AUTO("照片色", 0, 0),
        GRAPHITE("石墨", 0xff6b6d6e.toInt(), 0xff1d1e1f.toInt()),
        WARM("暖棕", 0xffa06a45.toInt(), 0xff2a211c.toInt()),
        MIST("雾蓝", 0xff7f939c.toInt(), 0xff2b3337.toInt()),
        OLIVE("橄榄", 0xff7b7a5c.toInt(), 0xff24241b.toInt()),
        NIGHT("夜", 0xff2e2e32.toInt(), 0xff060607.toInt()),
        CREAM("米白", 0xfff1ece2.toInt(), 0xffc9c1b2.toInt()),
    }

    /** The words' hands. */
    enum class Hand(val label: String) { CHALK("粉笔"), MARKER("马克笔"), SCRIPT("手写"), TYPE("打字机"), SERIF("衬线") }

    val INK_COLORS = intArrayOf(0xfff5f2ea.toInt(), 0xff1c1c1e.toInt(), 0xffffd60a.toInt(), 0xffe0623a.toInt(), 0xff6fa8dc.toInt(), 0xffb5d99c.toInt(), 0xffe9a3b5.toInt())

    // ---- the ground -------------------------------------------------------------------------------

    /** The ground: a pool of light high in the middle falling to dark corners, and a fine grain. */
    fun ground(c: Canvas, W: Float, H: Float, g: Ground, tones: IntArray?) {
        val (light, dark) = if (g == Ground.AUTO) {
            val t = tones ?: intArrayOf(0xff4a4b4e.toInt(), 0xff18181a.toInt(), 0xff6a6b70.toInt())
            lift(t[0], 1.25f) to t[1]
        } else g.light to g.dark
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = RadialGradient(W * 0.5f, H * 0.4f, maxOf(W, H) * 0.78f,
            intArrayOf(light, mix(light, dark, 0.55f), dark), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        // the foot a shade deeper, as a lit wall meets the floor
        p.shader = LinearGradient(0f, H * 0.55f, 0f, H, 0x00000000, 0x40000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        texture(c, RectF(0f, 0f, W, H), Surface.GRAIN, W, 0.55f)
    }

    // ---- prints -----------------------------------------------------------------------------------

    /** Where a print's parts lie about its centre: the card and the photo's window. */
    class Geometry(val card: RectF, val window: RectF, val portraitFilm: Boolean)

    /** A print [cw] wide whose window is [aspect] (height over width). */
    fun geometry(paper: Paper, cw: Float, aspect: Float): Geometry {
        if (paper == Paper.FILM) {
            // the strip runs along the frame's long side: holes beside a portrait frame, above and below a landscape one
            val portrait = aspect > 1f
            val margin = 0.15f; val end = 0.045f
            return if (portrait) {
                val iw = cw * (1 - 2 * margin); val ih = iw * aspect; val ch = ih + 2 * cw * end
                Geometry(RectF(-cw / 2, -ch / 2, cw / 2, ch / 2), RectF(-iw / 2, -ih / 2, iw / 2, ih / 2), true)
            } else {
                val iw = cw * (1 - 2 * end); val ih = iw * aspect; val ch = ih + 2 * ih * margin * 1.4f
                Geometry(RectF(-cw / 2, -ch / 2, cw / 2, ch / 2), RectF(-iw / 2, -ih / 2, iw / 2, ih / 2), false)
            }
        }
        val iw = cw * (1 - 2 * paper.side); val ih = iw * aspect
        val ch = ih + cw * (paper.top + paper.bottom)
        val card = RectF(-cw / 2, -ch / 2, cw / 2, ch / 2)
        return Geometry(card, RectF(-iw / 2, card.top + cw * paper.top, iw / 2, card.top + cw * paper.top + ih), false)
    }

    /**
     * A print drawn about (0, 0): shadow, paper (its colour, surface, light across it), the photo in
     * its window (moved by [cropX]/[cropY], closer by [zoom]), the print's own marks and words.
     */
    fun print(c: Canvas, W: Float, g: Geometry, paper: Paper, color: Int, surface: Surface, photo: Bitmap?,
              cropX: Float, cropY: Float, zoom: Float, frame: Int, overflow: FloatArray?) {
        val card = g.card; val img = g.window
        val cw = card.width()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val outline = when (paper) {
            Paper.BARE -> Path().apply { addRoundRect(img, W * 0.012f, W * 0.012f, Path.Direction.CW) }
            Paper.DECKLE -> deckle(card, cw * 0.012f)
            else -> Path().apply { val r = if (paper == Paper.MOUNT) cw * 0.035f else cw * 0.006f; addRoundRect(card, r, r, Path.Direction.CW) }
        }
        shadow(c, outline, W)
        // the card in a layer of its own: film's holes are cut through it to the ground
        val layer = c.saveLayer(card.left - 2, card.top - 2, card.right + 2, card.bottom + 2, null)
        if (paper != Paper.BARE) {
            p.color = color
            c.drawPath(outline, p)
            c.save(); c.clipPath(outline)
            texture(c, card, surface, W, if (luma(color) > 0.5f) 1f else 0.8f)
            // light falls from above: the top a touch brighter, the foot a touch deeper
            p.shader = LinearGradient(0f, card.top, 0f, card.bottom, intArrayOf(0x14ffffff, 0x00ffffff, 0x12000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(card, p); p.shader = null
            c.restore()
        }
        if (paper == Paper.FILM) holes(c, g, W)
        c.restoreToCount(layer)
        // the photo in its window
        val ir = when (paper) { Paper.MOUNT -> cw * 0.04f; Paper.BARE -> W * 0.012f; Paper.CARD -> cw * 0.006f; else -> 0f }
        c.save()
        c.clipPath(Path().apply { addRoundRect(img, ir, ir, Path.Direction.CW) })
        if (photo != null) {
            val k = maxOf(img.width() / photo.width, img.height() / photo.height) * zoom
            val dw = photo.width * k; val dh = photo.height * k
            val ox = dw - img.width(); val oy = dh - img.height()
            overflow?.let { it[0] = ox / W; it[1] = oy / W }
            val cx = img.centerX() - cropX * ox; val cy = img.centerY() - cropY * oy
            c.drawBitmap(photo, null, RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), p)
        } else {
            p.color = 0x26000000; c.drawRect(img, p)
        }
        // a print's photo sits in the paper: a hair of shade at the window's edge
        if (paper != Paper.BARE && paper != Paper.FILM) {
            p.style = Paint.Style.STROKE; p.strokeWidth = W * 0.0025f; p.color = 0x2a000000
            c.drawRoundRect(img, ir, ir, p); p.style = Paint.Style.FILL
        }
        c.restore()
        if (paper == Paper.TAPE) tape(c, card, W)
        marks(c, W, g, paper, color, frame)
    }

    /** Two shadows under a print: a wide soft one and a close dark one where it lies on the board. */
    private fun shadow(c: Canvas, outline: Path, W: Float) {
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x01000000 }
        s.setShadowLayer(W * 0.035f, 0f, W * 0.016f, 0x59000000); c.drawPath(outline, s)
        s.setShadowLayer(W * 0.006f, 0f, W * 0.003f, 0x66000000); c.drawPath(outline, s)
    }

    /** The print's words and marks, in an ink that reads on its colour. */
    private fun marks(c: Canvas, W: Float, g: Geometry, paper: Paper, color: Int, frame: Int) {
        val card = g.card; val img = g.window; val cw = card.width()
        val ink = if (luma(color) > 0.5f) 0xff3a3833.toInt() else 0xffe6e1d6.toInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; this.color = ink }
        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        val month = java.text.SimpleDateFormat("yyyy.MM", java.util.Locale.US).format(java.util.Date())
        val below = img.bottom + (card.bottom - img.bottom) * 0.58f
        when (paper) {
            Paper.CARD -> {
                p.typeface = mono; p.textSize = cw * 0.032f; p.letterSpacing = 0.14f
                c.drawText("PHOTO ALL   ·   $month", 0f, card.top + (img.top - card.top) * 0.56f, p)
                p.letterSpacing = 0f; p.typeface = Glassy.regular; p.textSize = cw * 0.034f
                c.drawText("第 $frame 格", 0f, below, p)
            }
            Paper.MOUNT -> {
                p.typeface = mono; p.textSize = cw * 0.026f; p.letterSpacing = 0.24f; p.color = mix(ink, color, 0.35f)
                c.drawText("PHOTO ALL  ·  %02d".format(frame % 100), 0f, card.bottom - (card.bottom - img.bottom) * 0.38f, p)
            }
            Paper.INSTANT, Paper.MINI, Paper.SQUARE, Paper.WIDE -> {
                p.typeface = Glassy.regular; p.textSize = cw * 0.03f; p.color = (ink and 0x00ffffff) or 0x88000000.toInt()
                p.textAlign = Paint.Align.RIGHT
                c.drawText(month, img.right - cw * 0.01f, below + p.textSize * 0.4f, p)
            }
            Paper.POSTCARD -> {
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (ink and 0x00ffffff) or 0x55000000; strokeWidth = cw * 0.002f }
                val l = card.left + cw * 0.07f; val r = card.right - cw * 0.3f
                for (i in 1..3) { val y = img.bottom + (card.bottom - img.bottom) * i / 4.2f; c.drawLine(l, y, r, y, line) }
                val stamp = RectF(card.right - cw * 0.22f, img.bottom + cw * 0.045f, card.right - cw * 0.07f, img.bottom + cw * 0.225f)
                c.drawRect(stamp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = cw * 0.003f; this.color = (ink and 0x00ffffff) or 0x99000000.toInt()
                    pathEffect = DashPathEffect(floatArrayOf(cw * 0.01f, cw * 0.006f), 0f)
                })
                p.typeface = mono; p.textSize = cw * 0.022f; p.letterSpacing = 0.3f; p.color = (ink and 0x00ffffff) or 0xaa000000.toInt()
                c.drawText("POST", stamp.centerX(), stamp.centerY() - p.textSize * 0.15f, p)
                c.drawText("CARD", stamp.centerX(), stamp.centerY() + p.textSize * 1.05f, p)
            }
            Paper.FILM -> {
                // the edge's printing: the stock, the frame's number and its half, in the film's own orange
                p.typeface = mono; p.color = 0xffd98a3a.toInt(); p.letterSpacing = 0.12f
                if (g.portraitFilm) {
                    val x = img.right + (card.right - img.right) * 0.5f
                    p.textSize = (card.right - img.right) * 0.2f
                    for ((t, at) in listOf("PHOTO 400" to 0.2f, "▸ ${frame % 37}" to 0.55f, "${frame % 37}A" to 0.82f)) {
                        c.save(); c.translate(x, img.top + img.height() * at); c.rotate(90f); c.drawText(t, 0f, p.textSize * 0.35f, p); c.restore()
                    }
                } else {
                    val y = img.bottom + (card.bottom - img.bottom) * 0.5f + (card.bottom - img.bottom) * 0.08f
                    p.textSize = (card.bottom - img.bottom) * 0.2f
                    for ((t, at) in listOf("PHOTO 400" to 0.18f, "▸ ${frame % 37}" to 0.52f, "${frame % 37}A" to 0.82f))
                        c.drawText(t, img.left + img.width() * at, y, p)
                }
            }
            else -> {}
        }
    }

    /** 135 film's sprocket holes, cut clean through (the ground shows in them). */
    private fun holes(c: Canvas, g: Geometry, W: Float) {
        val card = g.card; val img = g.window
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        if (g.portraitFilm) {
            val band = img.left - card.left
            val hw = band * 0.36f; val hh = band * 0.26f
            var y = card.top + hh
            while (y < card.bottom - hh) {
                for (x in listOf(card.left + band * 0.42f, card.right - band * 0.42f))
                    c.drawRoundRect(RectF(x - hw / 2, y - hh / 2, x + hw / 2, y + hh / 2), hh * 0.28f, hh * 0.28f, cut)
                y += hh * 2.35f
            }
        } else {
            val band = img.top - card.top
            val hw = band * 0.26f; val hh = band * 0.36f
            var x = card.left + hw
            while (x < card.right - hw) {
                for (y in listOf(card.top + band * 0.42f, card.bottom - band * 0.42f))
                    c.drawRoundRect(RectF(x - hw / 2, y - hh / 2, x + hw / 2, y + hh / 2), hw * 0.28f, hw * 0.28f, cut)
                x += hw * 2.35f
            }
        }
    }

    /** A hand-torn edge: the sides shaken a little, the same each time. */
    private fun deckle(r: RectF, amp: Float): Path {
        val rnd = Random(7)
        val path = Path()
        fun side(x0: Float, y0: Float, x1: Float, y1: Float, nx: Float, ny: Float, first: Boolean) {
            val n = 90
            var j = 0f
            for (i in 0..n) {
                val t = i / n.toFloat()
                j = j * 0.55f + (rnd.nextFloat() - 0.5f) * 2 * amp * 0.9f // rough, not jagged
                val x = x0 + (x1 - x0) * t + nx * j; val y = y0 + (y1 - y0) * t + ny * j
                if (first && i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        side(r.left, r.top, r.right, r.top, 0f, 1f, true)
        side(r.right, r.top, r.right, r.bottom, 1f, 0f, false)
        side(r.right, r.bottom, r.left, r.bottom, 0f, 1f, false)
        side(r.left, r.bottom, r.left, r.top, 1f, 0f, false)
        path.close()
        return path
    }

    /** Paper tape over the two top corners: translucent, its ends torn. */
    private fun tape(c: Canvas, card: RectF, W: Float) {
        val cw = card.width()
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xb8efe6cf.toInt() }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000; style = Paint.Style.STROKE; strokeWidth = W * 0.001f }
        for ((x, a) in listOf(card.left + cw * 0.02f to -40f, card.right - cw * 0.02f to 40f)) {
            c.save(); c.translate(x, card.top + cw * 0.01f); c.rotate(a)
            val l = cw * 0.13f; val h = cw * 0.045f
            val path = Path().apply {
                moveTo(-l, -h); lineTo(l, -h)
                // torn ends: small teeth
                for (i in 0..6) lineTo(l + if (i % 2 == 0) h * 0.12f else -h * 0.1f, -h + 2 * h * i / 6f)
                lineTo(-l, h)
                for (i in 6 downTo 0) lineTo(-l + if (i % 2 == 0) -h * 0.12f else h * 0.1f, -h + 2 * h * i / 6f)
                close()
            }
            c.drawPath(path, t); c.drawPath(path, edge)
            c.restore()
        }
    }

    // ---- surfaces -----------------------------------------------------------------------------------

    private val tiles = HashMap<Surface, Bitmap>()

    /** [surface]'s pattern over [r] at [strength], scaled with the board (same on screen and saved). */
    fun texture(c: Canvas, r: RectF, surface: Surface, W: Float, strength: Float) {
        if (surface == Surface.GLOSS) return
        val tile = synchronized(tiles) { tiles.getOrPut(surface) { makeTile(surface) } }
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { val k = W / 1080f * (if (surface == Surface.GRAIN) 1f else 1.4f); setScale(k, k) })
        }
        p.alpha = (255 * strength).toInt().coerceIn(0, 255)
        c.drawRect(r, p)
    }

    /** A 256 px repeating tile of a surface: light and dark specks, threads or fibres, mostly clear. */
    private fun makeTile(s: Surface): Bitmap {
        val n = 256
        val px = IntArray(n * n)
        val rnd = Random(s.ordinal * 31L + 5)
        fun put(i: Int, v: Float) { // v -1..1: dark .. light
            val a = (kotlin.math.abs(v) * 255).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (if (v > 0) 0xffffff else 0x000000)
        }
        when (s) {
            Surface.MATTE -> for (i in px.indices) put(i, (rnd.nextFloat() - 0.5f) * 0.09f)
            Surface.GRAIN -> for (y in 0 until n) for (x in 0 until n) {
                val v = (rnd.nextGaussian().toFloat() * 0.045f).coerceIn(-0.14f, 0.14f)
                put(y * n + x, v)
            }
            Surface.LINEN -> {
                // threads each way, each its own weight, crossing over and under
                val rows = FloatArray(n) { rnd.nextFloat() }; val cols = FloatArray(n) { rnd.nextFloat() }
                for (y in 0 until n) for (x in 0 until n) {
                    val weave = if (((x / 2) + (y / 2)) % 2 == 0) 1f else -1f
                    val h = (rows[y] - 0.5f) * 0.11f + weave * 0.02f
                    val v = (cols[x] - 0.5f) * 0.11f - weave * 0.02f
                    put(y * n + x, (h + v) * 0.9f + (rnd.nextFloat() - 0.5f) * 0.03f)
                }
            }
            Surface.FIBRE -> {
                for (i in px.indices) put(i, (rnd.nextFloat() - 0.5f) * 0.05f)
                val b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
                val c = Canvas(b)
                val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f }
                repeat(140) {
                    val x = rnd.nextFloat() * n; val y = rnd.nextFloat() * n
                    val a = rnd.nextFloat() * 6.28f; val l = 6f + rnd.nextFloat() * 18f
                    f.color = if (rnd.nextBoolean()) 0x1e000000 else 0x26ffffff
                    val path = Path().apply { moveTo(x, y); quadTo(x + cos(a) * l * 0.5f + rnd.nextFloat() * 4, y + sin(a) * l * 0.5f, x + cos(a) * l, y + sin(a) * l) }
                    // across the tile's edge too, so it repeats without a seam
                    for (dx in listOf(-n, 0, n)) for (dy in listOf(-n, 0, n)) { c.save(); c.translate(dx.toFloat(), dy.toFloat()); c.drawPath(path, f); c.restore() }
                }
                return b
            }
            Surface.GLOSS -> {}
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    // ---- words --------------------------------------------------------------------------------------

    /**
     * Words drawn about (0, 0), [size] tall: in [hand], [color], with a white glow round them when
     * [glow] (as words written on a dark board seem lit). Returns their half width and half height.
     */
    fun words(c: Canvas?, text: String, hand: Hand, color: Int, glow: Boolean, size: Float): Pair<Float, Float> {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = Paint.Align.CENTER
            typeface = when (hand) {
                Hand.CHALK -> Typeface.create(Typeface.create("casual", Typeface.BOLD), 700, false)
                Hand.MARKER -> Typeface.create(Typeface.SANS_SERIF, 900, false)
                Hand.SCRIPT -> Typeface.create("cursive", Typeface.BOLD)
                Hand.TYPE -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                Hand.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            }
            letterSpacing = when (hand) { Hand.CHALK, Hand.MARKER -> 0.06f; Hand.TYPE -> 0.12f; else -> 0.02f }
        }
        val lines = text.split('\n')
        val hw = (lines.maxOfOrNull { p.measureText(it) } ?: 0f) / 2
        val lh = size * 1.15f
        val hh = lh * lines.size / 2
        if (c == null) return hw to hh
        val top = -hh + lh * 0.8f
        for ((i, line) in lines.withIndex()) {
            val y = top + i * lh
            if (glow) {
                // a soft light behind, then a tight halo
                val g = Paint(p).apply { this.color = 0xffffffff.toInt(); maskFilter = BlurMaskFilter(size * 0.32f, BlurMaskFilter.Blur.NORMAL); alpha = 120 }
                c.drawText(line, 0f, y, g)
                g.maskFilter = BlurMaskFilter(size * 0.07f, BlurMaskFilter.Blur.NORMAL); g.alpha = 150
                c.drawText(line, 0f, y, g)
            }
            when (hand) {
                Hand.CHALK -> {
                    // chalk: the strokes' edges rough, a little of the board showing through
                    val rough = Paint(p).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = size * 0.035f; pathEffect = DiscretePathEffect(size * 0.05f, size * 0.03f) }
                    c.drawText(line, 0f, y, rough)
                }
                Hand.MARKER -> {
                    val ink = Paint(p).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = size * 0.02f; pathEffect = DiscretePathEffect(size * 0.12f, size * 0.012f) }
                    c.drawText(line, 0f, y, ink)
                }
                else -> c.drawText(line, 0f, y, p)
            }
        }
        return hw to hh
    }

    // ---- colour -------------------------------------------------------------------------------------

    fun luma(c: Int) = (0.3f * Color.red(c) + 0.59f * Color.green(c) + 0.11f * Color.blue(c)) / 255f

    fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun lift(c: Int, k: Float): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv); hsv[2] = (hsv[2] * k).coerceAtMost(0.62f); return Color.HSVToColor(hsv)
    }
}
