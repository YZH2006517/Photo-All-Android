package org.photoall.film.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Borders from real film and prints, drawn in millimetres from their true dimensions
 * and scaled to the photo's resolution: one template serves the editor's preview, its
 * thumbnails and the saved file alike. A border is added after the develop and saved
 * as a separate photo; the original stays as it was.
 *
 * Each template has a native orientation (135 runs sideways, prints stand upright). A
 * photo the other way round is turned to fit, framed, and turned back, so a portrait
 * frame on 135 comes out as a vertical strip with its edge print running along it.
 * Instant cards stay upright (their wide margin below, its words level); a 4 x 6 print
 * lies down for a landscape photo. The photo itself always stays upright: turning a
 * frame ([render]'s rotation) turns the border around it.
 */
object Frames {
    enum class Kind(val label: String) {
        NONE("无"),
        NEG135("135 负片"),
        SLIDE135("135 反转"),
        MEDIUM120("120 方画幅"),
        FILED("底片夹黑边"),
        PRINT("相纸白边"),
        INSTANT_SQUARE("即时 · 方"),
        INSTANT_MINI("即时 · 迷你"),
        // added later (after the saved choices' indexes)
        HALF135("135 半格"),
        XPAN("135 宽幅"),
        MEDIUM645("645"),
        MEDIUM67("6×7"),
        MEDIUM69("6×9"),
        SHEET45("4×5 页片"),
    }

    /** Borders that are film, which can fog and leak. */
    private val FILM = setOf(Kind.NEG135, Kind.SLIDE135, Kind.HALF135, Kind.XPAN, Kind.MEDIUM120,
        Kind.MEDIUM645, Kind.MEDIUM67, Kind.MEDIUM69, Kind.SHEET45, Kind.FILED)

    /**
     * What is printed: the film's name on the edge (null: nothing), the frame number, a
     * date-back stamp; on white borders, a line of the user's own ([caption], also a
     * watermark), the shooting data ([info]) and the phone ([device]).
     */
    class Options(val edgeText: String?, val frame: Int, val date: String?,
                  val caption: String? = null, val info: String? = null, val device: String? = null,
                  val recipe: String? = null,
                  /** The stock the photo was developed as ("kodak portra 400"): its edge print. */
                  val film: String = "",
                  /** Base fog on the rebate and creeping into the picture's edges, 0..1. */
                  val fog: Float = 0f,
                  /** Light leaked in at the spool end of the strip, 0..1. */
                  val leak: Float = 0f,
                  /** Which leak: the same seed draws the same one. */
                  val seed: Int = 0) {
        /** The data lines, each printed on its own line: shooting data, phone, film recipe. */
        val lines get() = listOfNotNull(info, device, recipe).filter { it.isNotBlank() }
        val hasText get() = !caption.isNullOrBlank() || lines.isNotEmpty()
    }

    /** White borders, which can carry a caption and the shooting data. */
    fun writable(kind: Kind) = kind == Kind.PRINT || kind == Kind.INSTANT_SQUARE || kind == Kind.INSTANT_MINI

    // Film geometry in mm (ISO 1007 for 135: 4.75 mm perforation pitch, KS holes).
    private const val PITCH = 4.75f
    private const val HOLE_ALONG = 1.98f
    private const val HOLE_ACROSS = 2.79f
    private const val HOLE_EDGE = 2.0f

    /** Template layout in mm: the whole sheet and the picture's place in it (native orientation). */
    private class Layout(val w: Float, val h: Float, val image: RectF)

    private fun layout(kind: Kind, o: Options? = null, landscape: Boolean = false): Layout = when (kind) {
        // 4 x 6 print with words under the picture: a deeper lower margin for them; lying
        // down for a landscape photo, so the words stay level
        Kind.PRINT -> when {
            landscape && o?.hasText == true -> Layout(152f, 118f, RectF(5f, 5f, 147f, 101f))
            landscape -> Layout(152f, 102f, RectF(5f, 5f, 147f, 97f))
            o?.hasText == true -> Layout(102f, 152f, RectF(5f, 5f, 97f, 128f))
            else -> Layout(102f, 152f, RectF(5f, 5f, 97f, 147f))
        }
        Kind.NONE -> Layout(36f, 24f, RectF(0f, 0f, 36f, 24f))
        // 36 x 24 frame on 35 mm film, cut at the frame line
        Kind.NEG135, Kind.SLIDE135 -> Layout(37.2f, 35f, RectF(0.6f, 5.5f, 36.6f, 29.5f))
        // 18 x 24 half frames on 35 mm film: two to each full frame's length, upright
        Kind.HALF135 -> Layout(19f, 35f, RectF(0.5f, 5.5f, 18.5f, 29.5f))
        // 65 x 24 panoramic frames on 35 mm film
        Kind.XPAN -> Layout(66.4f, 35f, RectF(0.7f, 5.5f, 65.7f, 29.5f))
        // 56 x 56 on 61.5 mm roll film, 5 mm either side
        Kind.MEDIUM120 -> Layout(66f, 61.5f, RectF(5f, 2.75f, 61f, 58.75f))
        // roll film's other frames: 56 high, 41.5 / 69.5 / 84 along the film
        Kind.MEDIUM645 -> Layout(48.5f, 61.5f, RectF(3.5f, 2.75f, 45f, 58.75f))
        Kind.MEDIUM67 -> Layout(77.5f, 61.5f, RectF(4f, 2.75f, 73.5f, 58.75f))
        Kind.MEDIUM69 -> Layout(92f, 61.5f, RectF(4f, 2.75f, 88f, 58.75f))
        // a 4 x 5 inch sheet, the holder's lip hiding a few millimetres all round
        Kind.SHEET45 -> Layout(127f, 102f, RectF(4.5f, 4.5f, 122.5f, 97.5f))
        // a 36 x 24 negative in a filed-out carrier, printed with a paper margin
        Kind.FILED -> Layout(46f, 34f, RectF(5f, 5f, 41f, 29f))
        // instant film: square 88 x 107 card with a 79 x 79 picture; mini 54 x 86 with 46 x 62
        Kind.INSTANT_SQUARE -> Layout(88f, 107f, RectF(4.5f, 6f, 83.5f, 85f))
        Kind.INSTANT_MINI -> Layout(54f, 86f, RectF(4f, 7f, 50f, 69f))
    }

    /**
     * The framed photo, its long side at most [maxLong] pixels. [rotation] (degrees,
     * clockwise) turns the border around the photo, which stays upright: which edge of
     * the film runs where, which way an instant card or a print stands.
     */
    fun render(photo: Bitmap, kind: Kind, options: Options, maxLong: Int = 4096, rotation: Int = 0): Bitmap {
        val photoLandscape = photo.width > photo.height
        val l = layout(kind, options, landscape = photoLandscape)
        val img = l.image
        // The template's own turn: to the photo's orientation, except instant cards, which
        // stand upright whatever the photo (square pictures take either).
        val slotLandscape = img.width() >= img.height()
        val square = img.width() == img.height()
        val instant = kind == Kind.INSTANT_MINI || kind == Kind.INSTANT_SQUARE
        val fit = if (!square && !instant && photoLandscape != slotLandscape) 270 else 0
        // The sheet is drawn in the template's orientation, then turned by [sheetTurn];
        // the photo goes in turned the other way, so it comes out upright.
        val sheetTurn = ((fit + rotation) % 360 + 360) % 360
        val turn = sheetTurn != 0
        val src = if (turn) rotate(photo, (360 - sheetTurn).toFloat()) else photo

        // Scale: the photo's own resolution across the picture, unless the sheet gets too big.
        val crop = centreCrop(src.width, src.height, img.width() / img.height())
        var s = crop.width() / img.width()
        s = min(s, maxLong / max(l.w, l.h))
        val out = Bitmap.createBitmap(ceil(l.w * s).toInt(), ceil(l.h * s).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(s, s)
        draw(c, kind, l, src, crop, options)
        if (kind in FILM) filmEffects(c, kind, l, options)
        if (turn && src !== photo) src.recycle()
        return if (turn) rotate(out, sheetTurn.toFloat()).also { out.recycle() } else out
    }

    private fun draw(c: Canvas, kind: Kind, l: Layout, photo: Bitmap, crop: Rect, o: Options) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val img = l.image
        when (kind) {
            Kind.NONE -> c.drawBitmap(photo, crop, img, paint)
            Kind.NEG135, Kind.SLIDE135, Kind.HALF135, Kind.XPAN -> {
                // As a scan of one frame cut from the strip: the picture runs the strip's whole
                // length, perforations above and below, the edge print in the light lettering
                // a scan shows (name at top left, frame numbers top right and bottom).
                val slide = kind == Kind.SLIDE135
                val half = kind == Kind.HALF135
                val st = edgeStyle(o.film, o.edgeText)
                val ink = if (slide) 0xffefe9dc.toInt() else st.ink
                c.drawColor(if (slide) 0xff070707.toInt() else st.base)
                val corner = 0.25f
                c.save()
                c.clipPath(Path().apply { addRoundRect(img, corner, corner, Path.Direction.CW) })
                c.drawBitmap(photo, crop, img, paint)
                c.restore()
                // perforations: light through the holes, 8 to the frame
                paint.color = 0xffe9e8e6.toInt()
                var x = 1.45f
                val r = RectF()
                while (x + HOLE_ALONG <= l.w) {
                    r.set(x, HOLE_EDGE, x + HOLE_ALONG, HOLE_EDGE + HOLE_ACROSS); c.drawRoundRect(r, 0.4f, 0.4f, paint)
                    r.set(x, l.h - HOLE_EDGE - HOLE_ACROSS, x + HOLE_ALONG, l.h - HOLE_EDGE); c.drawRoundRect(r, 0.4f, 0.4f, paint)
                    x += PITCH
                }
                val big = edgeFont(ink, if (half) 1.05f else 1.55f)
                if (o.edgeText != null) {
                    // a half frame is too short for the whole print: it shows a piece of it
                    c.drawText(if (half) st.name.substringAfter(' ') else st.name, if (half) 0.9f else 2.4f, 1.85f, big)
                    // the stock's own codes on the lower edge: a DX barcode (Kodak's still
                    // films), the key code (motion picture stock), the emulsion code (Fuji)
                    if (!slide && !half) when {
                        st.dx -> dxBars(c, ink, o.frame + o.seed, l.w * 0.52f, l.h - 1.55f)
                        st.code != null -> c.drawText(st.code, l.w * 0.5f, l.h - 0.45f, mono(ink, 1.05f))
                    }
                }
                if (o.frame > 0 && half) {
                    // half frames are numbered in halves: 12, 12A
                    c.drawText("${o.frame}", l.w - 1f, l.h - 0.45f, edgeFont(ink, 1.3f).apply { textAlign = Paint.Align.RIGHT })
                } else if (o.frame > 0) {
                    c.drawText("${o.frame}", l.w - 2f, 1.85f, edgeFont(ink, 1.55f).apply { textAlign = Paint.Align.RIGHT })
                    // bottom: the half-frame mark with its arrow, and the frame number
                    val small = edgeFont(ink, 1.3f).apply { textAlign = Paint.Align.CENTER }
                    c.drawText("${o.frame}A", 13.3f, l.h - 0.95f, small)
                    paint.color = ink
                    paint.strokeWidth = 0.13f
                    c.drawLine(11.8f, l.h - 0.45f, 15.4f, l.h - 0.45f, paint)
                    c.drawPath(Path().apply {
                        moveTo(15.6f, l.h - 0.45f); lineTo(15.0f, l.h - 0.7f); lineTo(15.0f, l.h - 0.2f); close()
                    }, paint)
                    c.drawText("${o.frame}", l.w - 3.4f, l.h - 0.4f, edgeFont(ink, 1.7f).apply { textAlign = Paint.Align.RIGHT })
                }
                o.date?.let { dateStamp(c, it, img) }
            }
            Kind.MEDIUM120, Kind.MEDIUM645, Kind.MEDIUM67, Kind.MEDIUM69 -> {
                val st = edgeStyle(o.film, o.edgeText)
                c.drawColor(st.base)
                c.drawBitmap(photo, crop, img, paint)
                val text = mono(st.ink, 1.6f)
                val format = when (kind) { Kind.MEDIUM645 -> "645"; Kind.MEDIUM67 -> "6×7"; Kind.MEDIUM69 -> "6×9"; else -> "120" }
                if (o.edgeText != null) {
                    val label = "${st.name}   $format"
                    val room = l.w - 14f
                    val w = text.measureText(label)
                    if (w > room) text.textSize *= room / w
                    c.drawText(label, img.left + 2f, 2.0f, text)
                }
                if (o.frame > 0) c.drawText("▸${o.frame}", l.w - 10f, 2.0f, mono(st.ink, 1.6f))
                // the notch code at the film's edge
                paint.color = 0xffe8a64a.toInt()
                val notch = Path().apply { moveTo(1.2f, l.h); lineTo(2.2f, l.h - 1.4f); lineTo(3.2f, l.h); close() }
                c.drawPath(notch, paint)
                o.date?.let { dateStamp(c, it, img) }
            }
            Kind.SHEET45 -> {
                // As a scan of the sheet: the film base round the picture, the holder's shadow
                // just inside it, the code notches cut into one edge, the maker's name
                val st = edgeStyle(o.film, o.edgeText)
                c.drawColor(st.base)
                c.save()
                c.clipPath(Path().apply { addRoundRect(img, 1.2f, 1.2f, Path.Direction.CW) })
                c.drawBitmap(photo, crop, img, paint)
                c.restore()
                paint.color = 0xffe9e8e6.toInt()
                // the notch code (a V, a round and a square cut), top edge by the right corner
                val nx = l.w - 16f
                c.drawPath(Path().apply { moveTo(nx, 0f); lineTo(nx + 1.4f, 2.2f); lineTo(nx + 2.8f, 0f); close() }, paint)
                c.drawCircle(nx + 6f, 0f, 1.3f, paint)
                c.drawRect(nx + 9f, 0f, nx + 11.4f, 1.8f, paint)
                if (o.edgeText != null) c.drawText(st.name, img.left + 1f, l.h - 1.4f, edgeFont(st.ink, 1.8f))
                if (o.frame > 0) c.drawText("${o.frame}", l.w - 6f, l.h - 1.4f, edgeFont(st.ink, 1.8f).apply { textAlign = Paint.Align.RIGHT })
                o.date?.let { dateStamp(c, it, img) }
            }
            Kind.FILED -> {
                c.drawColor(0xfff6f3ec.toInt())
                // the carrier's filed edge: rough, a little different on every frame
                val rnd = Random(o.frame * 31 + 7)
                val p = Path()
                val outer = RectF(img.left - 2.6f, img.top - 2.6f, img.right + 2.6f, img.bottom + 2.6f)
                fun jag() = rnd.nextFloat() * 1.2f - 0.5f
                p.moveTo(outer.left, outer.top)
                var x = outer.left
                while (x < outer.right) { x += 0.6f; p.lineTo(min(x, outer.right), outer.top + jag()) }
                var y = outer.top
                while (y < outer.bottom) { y += 0.6f; p.lineTo(outer.right - jag(), min(y, outer.bottom)) }
                x = outer.right
                while (x > outer.left) { x -= 0.6f; p.lineTo(max(x, outer.left), outer.bottom - jag()) }
                y = outer.bottom
                while (y > outer.top) { y -= 0.6f; p.lineTo(outer.left + jag(), max(y, outer.top)) }
                p.close()
                paint.color = 0xff0a0a0a.toInt()
                c.drawPath(p, paint)
                c.drawBitmap(photo, crop, img, paint)
                o.date?.let { dateStamp(c, it, img) }
            }
            Kind.PRINT -> {
                c.drawColor(0xfff6f3ec.toInt())
                c.drawBitmap(photo, crop, img, paint)
                if (o.hasText) {
                    // the caption on the left, written large; the data small on the right
                    val y = img.bottom + (l.h - img.bottom) / 2
                    o.caption?.takeIf { it.isNotBlank() }?.let {
                        c.drawText(it, img.left + 1f, y + 1.6f, script(0xff2a2826.toInt(), 5f, img.width() * 0.55f, it))
                    }
                    val lines = o.lines
                    val size = if (lines.size > 2) 2.0f else 2.3f
                    val gap = size * 1.45f
                    val right = mono(0xff6b665e.toInt(), size).apply { textAlign = Paint.Align.RIGHT }
                    val maxW = if (o.caption.isNullOrBlank()) img.width() else img.width() * 0.5f
                    lines.forEachIndexed { i, t ->
                        right.color = if (i == 0) 0xff6b665e.toInt() else 0xff9a948a.toInt()
                        right.textSize = size
                        val w = right.measureText(t)
                        if (w > maxW) right.textSize = size * maxW / w
                        c.drawText(t, img.right - 0.5f, y - (lines.size - 1) * gap / 2 + i * gap + size * 0.35f, right)
                    }
                } else {
                    val caption = listOfNotNull(if (o.frame > 0) "${o.frame}" else null, o.edgeText).joinToString(" · ")
                    if (caption.isNotEmpty()) c.drawText(caption, img.right, l.h - 1.8f,
                        mono(0xff9a948a.toInt(), 2.2f).apply { textAlign = Paint.Align.RIGHT })
                }
                o.date?.let { dateStamp(c, it, img) }
            }
            Kind.INSTANT_SQUARE, Kind.INSTANT_MINI -> {
                c.drawColor(0xfff4f1ea.toInt())
                c.drawBitmap(photo, crop, img, paint)
                // the picture sits a little below the card's surface
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.3f
                paint.color = 0x22000000
                c.drawRect(img, paint)
                paint.style = Paint.Style.FILL
                // instant film's wide lower margin: the caption written in it, the data beneath
                if (o.hasText) {
                    val margin = l.h - img.bottom
                    val cx = l.w / 2
                    o.caption?.takeIf { it.isNotBlank() }?.let {
                        c.drawText(it, cx, img.bottom + margin * 0.5f,
                            script(0xff2a2826.toInt(), margin * 0.24f, img.width() * 0.9f, it).apply { textAlign = Paint.Align.CENTER })
                    }
                    val lines = o.lines
                    if (lines.isNotEmpty()) {
                        val size = margin * if (lines.size > 2) 0.075f else 0.09f
                        val gap = size * 1.5f
                        val first = if (o.caption.isNullOrBlank()) img.bottom + margin * 0.5f - (lines.size - 1) * gap / 2
                            else img.bottom + margin * 0.72f
                        lines.forEachIndexed { i, t ->
                            val pnt = mono(if (i == 0) 0xff6b665e.toInt() else 0xff8a847c.toInt(), size).apply { textAlign = Paint.Align.CENTER }
                            val w = pnt.measureText(t)
                            if (w > img.width()) pnt.textSize = size * img.width() / w
                            c.drawText(t, cx, first + i * gap + size * 0.35f, pnt)
                        }
                    }
                }
                o.date?.let { dateStamp(c, it, img) }
            }
        }
    }

    /** The orange LCD date of a date back, in the picture's lower right corner. */
    private fun dateStamp(c: Canvas, date: String, img: RectF) {
        val size = img.width() * 0.05f
        val x = img.right - size * 1.4f
        val y = img.bottom - size * 1.2f
        // the LCD's glow (a blur in mm, which follows the canvas scale), then the digits
        val glow = mono(0xccff5a00.toInt(), size).apply {
            textAlign = Paint.Align.RIGHT
            maskFilter = android.graphics.BlurMaskFilter(size * 0.3f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        c.drawText(date, x, y, glow)
        c.drawText(date, x, y, mono(0xffff8a2a.toInt(), size).apply { textAlign = Paint.Align.RIGHT })
    }

    /**
     * How a stock prints its edge, as a scan of the strip shows it (the negative inverted:
     * the rebate near black, the printing light): Kodak's still films in amber with a DX
     * barcode, Kodak motion picture stock with its stock number and key code, Fujicolor in
     * a yellow green. The base keeps a trace of each film's own tint.
     */
    private class EdgeStyle(val name: String, val ink: Int, val base: Int, val dx: Boolean = false, val code: String? = null)

    private fun edgeStyle(film: String, edgeText: String?): EdgeStyle {
        val f = film.lowercase()
        val name = org.photoall.film.ui.CanisterView.displayName(film).uppercase()
        return when {
            f.isEmpty() || f == "直出" -> EdgeStyle(edgeText ?: "PHOTO ALL", 0xffe2e0dc.toInt(), 0xff0d0d0d.toInt())
            "vision3" in f -> {
                val stock = when {
                    "50d" in f -> "5203"; "250d" in f -> "5207"; "200t" in f -> "5213"; else -> "5219"
                }
                val key = (film.hashCode() and 0x7fffffff) % 9000 + 1000
                EdgeStyle("KODAK $stock  ${name.removePrefix("VISION3 ")}", 0xffffb35c.toInt(), 0xff0f0a07.toInt(),
                    code = "KN41 $key •")
            }
            f.startsWith("kodak") -> EdgeStyle("KODAK $name", 0xffffcb6b.toInt(), 0xff0d0a09.toInt(), dx = true)
            f.startsWith("fuji") -> EdgeStyle(if ("pro" in f) "FUJI $name" else "FUJICOLOR $name",
                0xffcdeb8b.toInt(), 0xff090c0a.toInt(), code = "CA ${(film.hashCode() and 0x7fffffff) % 900 + 100}  ▸")
            else -> EdgeStyle(edgeText ?: name, 0xffe2e0dc.toInt(), 0xff0d0d0d.toInt())
        }
    }

    /**
     * What happens to real film round the picture. Fog: the unexposed rebate is never
     * quite black (base fog, denser and mottled toward the outer edges), and creeps a
     * little into the picture's long edges. A leak: light that got in at the spool end
     * of the strip, a red-orange glow spreading in from that end, brightest outside.
     */
    private fun filmEffects(c: Canvas, kind: Kind, l: Layout, o: Options) {
        val img = l.image
        val rnd = Random(o.seed * 7919 + 13)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (o.fog > 0f && kind != Kind.FILED) {
            val a = (o.fog * 0x34).toInt()
            val fog = (a shl 24) or 0xcfc8bc
            c.save()
            c.clipOutRect(img)
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, l.h, intArrayOf(fog, fog and 0x00ffffff, fog and 0x00ffffff, fog),
                floatArrayOf(0f, 0.2f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, l.w, l.h, p)
            p.shader = null
            // mottling: soft patches of a little more density
            repeat(9) {
                p.color = ((a * (0.3f + rnd.nextFloat() * 0.6f)).toInt() shl 24) or 0xd8d0c4
                p.maskFilter = android.graphics.BlurMaskFilter(0.8f + rnd.nextFloat() * 1.6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                val top = rnd.nextBoolean()
                c.drawCircle(rnd.nextFloat() * l.w, if (top) rnd.nextFloat() * img.top else img.bottom + rnd.nextFloat() * (l.h - img.bottom),
                    0.8f + rnd.nextFloat() * 1.8f, p)
            }
            p.maskFilter = null
            c.restore()
            // the picture's long edges, a little lifted
            p.color = 0xffffffff.toInt() // opaque: a shader is drawn at the paint's alpha
            c.save()
            c.clipRect(img)
            val band = img.height() * 0.08f
            val lift = ((o.fog * 0x22).toInt() shl 24) or 0xd9d3c8
            p.shader = android.graphics.LinearGradient(0f, img.top, 0f, img.top + band, lift, lift and 0x00ffffff, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(img.left, img.top, img.right, img.top + band, p)
            p.shader = android.graphics.LinearGradient(0f, img.bottom, 0f, img.bottom - band, lift, lift and 0x00ffffff, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(img.left, img.bottom - band, img.right, img.bottom, p)
            p.shader = null
            c.restore()
        }
        if (o.leak > 0f) {
            val k = o.leak.coerceIn(0f, 1f)
            val glow = minOf(1f, k * 2.2f)   // brightness: strong from the middle of the slider on
            fun warm(alpha: Float, r: Int, g: Int, b: Int) = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
            val right = rnd.nextBoolean()
            val endX = if (right) l.w + l.h * 0.12f else -l.h * 0.12f
            val endY = l.h * (0.2f + rnd.nextFloat() * 0.6f)
            val radius = l.h * (0.5f + 0.75f * k) * (0.8f + rnd.nextFloat() * 0.4f)
            p.color = 0xffffffff.toInt() // opaque: a shader is drawn at the paint's alpha
            p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
            // the glow, from the strip's end
            p.shader = android.graphics.RadialGradient(endX, endY, radius,
                intArrayOf(warm(glow, 255, 64, 18), warm(0.8f * glow, 255, 110, 36), warm(0.4f * glow, 255, 170, 80), 0),
                floatArrayOf(0f, 0.35f, 0.68f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, l.w, l.h, p)
            // a streak right along the end, where the light came straight in
            val w = l.w * (0.08f + 0.16f * k * (0.5f + rnd.nextFloat()))
            val x0 = if (right) l.w else 0f
            val x1 = if (right) l.w - w else w
            p.shader = android.graphics.LinearGradient(x0, 0f, x1, 0f, warm(0.9f * glow, 255, 82, 24), 0,
                android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(minOf(x0, x1), 0f, maxOf(x0, x1), l.h, p)
            // and sometimes a second, fainter yellow bloom further in
            if (rnd.nextFloat() < 0.6f) {
                val bx = if (right) l.w * (0.7f - rnd.nextFloat() * 0.15f) else l.w * (0.3f + rnd.nextFloat() * 0.15f)
                p.shader = android.graphics.RadialGradient(bx, l.h * rnd.nextFloat(), l.h * 0.35f * (0.6f + k),
                    intArrayOf(warm(0.45f * glow, 255, 200, 110), 0), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, l.w, l.h, p)
            }
            p.shader = null
            p.xfermode = null
        }
    }

    /** A DX edge barcode: a clock track and a data track, the data from the frame number. */
    private fun dxBars(c: Canvas, ink: Int, frame: Int, x0: Float, y: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        val rnd = Random(frame)
        for (i in 0 until 14) {
            val x = x0 + i * 0.42f
            c.drawRect(x, y, x + 0.22f, y + 0.3f, p)
            if (rnd.nextFloat() > 0.45f) c.drawRect(x, y + 0.55f, x + 0.22f, y + 1.05f, p)
        }
    }

    /** The edge print's lettering: a bold grotesque, as film makers print it. */
    private fun edgeFont(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.04f
    }

    // Text in mm: linear (unhinted) so sizes of a millimetre or two scale cleanly.
    private fun mono(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.08f
    }

    /** Handwriting-like text for captions, shrunk to fit [maxWidth]. */
    private fun script(color: Int, size: Float, maxWidth: Float, text: String) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        this.color = color
        typeface = Typeface.create("serif", Typeface.ITALIC)
        textSize = size
        val w = measureText(text)
        if (w > maxWidth) textSize = size * maxWidth / w
    }

    private fun centreCrop(w: Int, h: Int, aspect: Float): Rect {
        return if (w.toFloat() / h > aspect) {
            val cw = (h * aspect).roundToInt()
            Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
        } else {
            val ch = (w / aspect).roundToInt()
            Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
        }
    }

    private fun rotate(b: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees) }, true)

    /** The date as a date back prints it: '25 11 9. */
    fun dateText(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return "'%02d %d %d".format(cal.get(java.util.Calendar.YEAR) % 100, cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** The shooting data printed on a white border: "ƒ1.7  1/50  ISO 400  24mm". */
    fun infoText(shot: ShotInfo?): String? = shot?.let { s ->
        listOfNotNull(s.fNumber?.let { "ƒ%.1f".format(it) }, s.shutterText, s.iso?.let { "ISO $it" },
            s.focal35?.let { "${it}mm" } ?: s.focalMm?.let { "%.0fmm".format(it) }).joinToString("  ").ifEmpty { null }
    }

    /**
     * The film recipe printed on a white border, from the recipe saved with the photo
     * ("kodak vision3 500t · kodak endura premier · <LUT> · <how it was shot>"):
     * "Vision3 500T · Endura Premier · LUT <name>".
     */
    fun recipeText(context: android.content.Context, description: String): String? {
        val parts = description.split(" · ").map { it.trim() }
        if (parts.isEmpty() || parts[0].isEmpty()) return null
        org.photoall.film.settings.LutLibrary.load(context)
        val luts = (1 until org.photoall.film.settings.LutLibrary.count).map { org.photoall.film.settings.LutLibrary.name(it) }.toSet()
        val out = mutableListOf<String>()
        out += if (parts[0].startsWith("无胶片")) "直出" else org.photoall.film.ui.CanisterView.displayName(parts[0])
        parts.getOrNull(1)?.let { out += if (it == "直接扫描") it else org.photoall.film.ui.CanisterView.displayName(it) }
        parts.getOrNull(2)?.takeIf { it in luts }?.let { out += "LUT $it" }
        return out.joinToString(" · ")
    }

    /** The phone printed on a white border: "Samsung SM-S9180". */
    fun deviceText(shot: ShotInfo?): String {
        val make = (shot?.make ?: android.os.Build.MANUFACTURER).trim().replaceFirstChar { it.uppercase() }
        val model = (shot?.model ?: android.os.Build.MODEL).trim()
        return if (model.startsWith(make, ignoreCase = true)) model else "$make $model"
    }

    /** The user's own words for white borders (a caption or watermark), kept between uses. */
    fun caption(context: android.content.Context): String =
        context.getSharedPreferences("frames", android.content.Context.MODE_PRIVATE).getString("caption", "") ?: ""

    fun setCaption(context: android.content.Context, text: String) =
        context.getSharedPreferences("frames", android.content.Context.MODE_PRIVATE).edit().putString("caption", text).apply()

    /** The edge print for a film name ("kodak portra 400" -> "PORTRA 400"; none for a digital photo). */
    fun edgeName(film: String): String =
        if (film.isEmpty() || film == "直出") "PHOTO ALL" else org.photoall.film.ui.CanisterView.displayName(film).uppercase()
}
