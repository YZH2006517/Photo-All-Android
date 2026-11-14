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
    private const val HOLE_ACROSS = 2.794f
    private const val HOLE_EDGE = 2.01f   // film edge to the perforation
    private const val HOLE_RADIUS = 0.51f // KS corners
    /** The scanner's light seen past the film's two cut edges (135 strips), mm. */
    private const val PAST = 0f

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
        // the 35 mm strip (34.98 mm) with the scanner's light just past both cut edges
        Kind.NEG135, Kind.SLIDE135 -> Layout(38f, 35f + 2 * PAST, RectF(0.875f, 5.34f + PAST, 37.125f, 29.64f + PAST))
        // 18 x 24 half frames on 35 mm film: two to each full frame's length, upright
        Kind.HALF135 -> Layout(19.6f, 35f + 2 * PAST, RectF(0.8f, 5.49f + PAST, 18.8f, 29.49f + PAST))
        // 65 x 24 panoramic frames on 35 mm film
        Kind.XPAN -> Layout(67.2f, 35f + 2 * PAST, RectF(1.1f, 5.49f + PAST, 66.1f, 29.49f + PAST))
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
    fun render(photo: Bitmap, kind: Kind, options: Options, maxLong: Int = 4096, rotation: Int = 0,
               imageOut: RectF? = null): Bitmap {
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
        imageOut?.let { r ->
            val m = Matrix().apply { postRotate(sheetTurn.toFloat()) }
            val full = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
            r.set(img.left * s, img.top * s, img.right * s, img.bottom * s)
            m.mapRect(r); m.mapRect(full)
            r.offset(-full.left, -full.top)
        }
        return if (turn) rotate(out, sheetTurn.toFloat()).also { out.recycle() } else out
    }

    private fun draw(c: Canvas, kind: Kind, l: Layout, photo: Bitmap, crop: Rect, o: Options) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val img = l.image
        when (kind) {
            Kind.NONE -> c.drawBitmap(photo, crop, img, paint)
            Kind.NEG135, Kind.SLIDE135, Kind.HALF135, Kind.XPAN -> strip135(c, kind, l, photo, crop, o)
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

    // ============================================================ 135: the strip as scanned

    /**
     * How a maker printed the rebate, from measurements of real strips (cap heights,
     * baselines and pitches in film millimetres, read off scans at a known perforation
     * pitch): which layout, and the printer light's colour as a scan of the negative shows
     * it (Kodak negatives tan to peach, Fujifilm's yellow, its slides orange, Vision3 a
     * bluish white, E100 white).
     */
    private enum class Layout135 { KODAK, FUJI_NEG, FUJI_SLIDE, CINE, CINESTILL, PLAIN }

    private class EdgeLook(val layout: Layout135, val ink: Int, val base: Int, val name: String, val dx: Int,
                           val light: Boolean = false)

    private fun edgeLook(film: String, edgeText: String?): EdgeLook {
        val f = film.lowercase().replace('_', ' ')
        val shown = org.photoall.film.ui.CanisterView.displayName(film).uppercase()
            .replace(Regex("""\s*(PUSH|PULL)\s*\d+"""), "").trim()
        val negBase = 0xff0d0a08.toInt()
        fun dx(vararg pairs: Pair<String, Int>) = pairs.firstOrNull { it.first in f }?.second ?: -1
        return when {
            f.isEmpty() || f == "直出" -> EdgeLook(Layout135.KODAK, 0xffd9d2c4.toInt(), 0xff0c0c0c.toInt(), edgeText ?: "PHOTO ALL", -1)
            "cinestill" in f -> EdgeLook(Layout135.CINESTILL, 0xffdba542.toInt(), negBase, shown, -1)
            "vision3" in f -> {
                val n = when { "250d" in f -> "5207"; "50d" in f -> "5203"; "200t" in f -> "5213"; else -> "5219" }
                EdgeLook(Layout135.CINE, 0xffbdbedb.toInt(), 0xff0a0a0d.toInt(), "EASTMAN $n ${shown.removePrefix("VISION3 ")}", -1)
            }
            f.startsWith("fuji") && ("provia" in f || "velvia" in f || "astia" in f) ->
                EdgeLook(Layout135.FUJI_SLIDE, 0xffdfb256.toInt(), 0xff050505.toInt(), "FUJICHROME $shown", -1)
            f.startsWith("fuji") && "c200" in f ->
                EdgeLook(Layout135.KODAK, 0xffdba76f.toInt(), negBase, "FUJI $shown", 1550, light = true)
            f.startsWith("fuji") -> EdgeLook(Layout135.FUJI_NEG, if ("400h" in f) 0xffdba82e.toInt() else 0xffdbd07d.toInt(), negBase,
                "FUJICOLOR $shown", dx("xtra" to 628, "x-tra" to 628, "400h" to 584, "superia" to 628))
            f.startsWith("kodak") && ("ektachrome" in f || "e100" in f) ->
                EdgeLook(Layout135.KODAK, 0xffe6dcd6.toInt(), 0xff050505.toInt(), "KODAK $shown", 382)
            f.startsWith("kodak") && ("tri-x" in f || "tri x" in f || "tmax" in f || "t-max" in f) ->
                EdgeLook(Layout135.KODAK, 0xffd8d8d6.toInt(), 0xff0b0b0b.toInt(), "KODAK $shown", -1, light = "tri" in f)
            f.startsWith("kodak") -> EdgeLook(Layout135.KODAK, 0xffdbab63.toInt(), negBase, "KODAK $shown",
                dx("portra 400" to 1277, "portra 800" to 1278, "portra 160" to 1534, "gold" to 1548, "ektar" to 1307, "ultramax" to 1313))
            f.startsWith("ilford") -> EdgeLook(Layout135.KODAK, 0xffd8d8d6.toInt(), 0xff0b0b0b.toInt(), shown, 1753)
            else -> EdgeLook(Layout135.KODAK, 0xffdbab63.toInt(), negBase, edgeText ?: shown, -1)
        }
    }

    // The edge printers' faces: Helvetica (TeX Gyre Heros, its free twin), its condensed
    // cut, and a heavy monospace for CineStill's numbers; Android's own as a fallback.
    @Volatile private var heros: Typeface? = null
    @Volatile private var herosRegular: Typeface? = null
    @Volatile private var herosCondensed: Typeface? = null
    @Volatile private var monoBold: Typeface? = null

    private fun face(which: Int): Typeface {
        if (heros == null) org.photoall.film.CrashReport.context?.assets?.let { a ->
            runCatching {
                heros = Typeface.createFromAsset(a, "fonts/edge/texgyreheros-bold.otf")
                herosRegular = Typeface.createFromAsset(a, "fonts/edge/texgyreheros-regular.otf")
                herosCondensed = Typeface.createFromAsset(a, "fonts/edge/texgyreheroscn-regular.otf")
                monoBold = Typeface.createFromAsset(a, "fonts/edge/DejaVuSansMono-Bold.ttf")
            }
        }
        return when (which) {
            1 -> herosRegular ?: Typeface.create("sans-serif", Typeface.NORMAL)
            2 -> herosCondensed ?: Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            3 -> monoBold ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> heros ?: Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    /** A face set by its cap height in mm (Helvetica's caps are 0.714 em), widened and tracked as printed. */
    private fun capPaint(ink: Int, which: Int, capMm: Float, widen: Float = 1f, trackMm: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = ink
            typeface = face(which)
            textSize = capMm / 0.714f
            textScaleX = widen
            letterSpacing = trackMm / textSize
        }

    private const val FILM_W = 34.98f

    /**
     * One frame of a 35 mm strip as a film scanner sees it, inverted: the scanner's light
     * past the cut edges; the rebate near black with its grain; KS perforations (ISO 1007);
     * the picture in the camera's gate (36.25 x 24.3 measured on real strips, not ISO's
     * 36 x 24) with the gate's soft shadow; the maker's edge print laid out as measured,
     * printed as light, so it blooms a little; base fog along the long edges, where light
     * pipes in from the cassette's lips; and leaks, which come in at those same edges.
     */
    private fun strip135(c: Canvas, kind: Kind, l: Layout, photo: Bitmap, crop: Rect, o: Options) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val img = l.image
        val slide = kind == Kind.SLIDE135
        val half = kind == Kind.HALF135
        val look = edgeLook(o.film, o.edgeText)
        val ink = if (slide && look.layout != Layout135.FUJI_SLIDE) 0xffe9e2d4.toInt() else look.ink
        val top = PAST
        val bottom = top + FILM_W
        val rnd = Random(o.frame * 977 + o.seed)
        // the strip fills the picture edge to edge, as a cropped scan of it does
        val film = Path().apply { addRect(0f, top, l.w, bottom, Path.Direction.CW) }
        paint.color = if (slide) 0xff050505.toInt() else look.base
        c.drawPath(film, paint)
        c.save()
        c.clipPath(film)
        rebateGrain(c, l.w, top, bottom, o.frame + o.seed, slide)
        edgeFog(c, l.w, top, bottom, o, slide)

        // the picture: the gate's shadow a soft edge, its flare a faint rim just inside
        c.save()
        c.clipPath(Path().apply { addRoundRect(img, 0.18f, 0.18f, Path.Direction.CW) })
        c.drawBitmap(photo, crop, img, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.12f
        paint.color = 0x70000000
        c.drawRoundRect(img, 0.18f, 0.18f, paint)
        paint.strokeWidth = 0.05f
        paint.color = 0x1effffff
        c.drawRect(RectF(img).apply { inset(0.09f, 0.09f) }, paint)
        paint.style = Paint.Style.FILL
        c.restore()

        // perforations: clean light through the holes, a hair of shadow at their walls
        val r = RectF()
        val phase = 1.4f
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfff4f4f1.toInt() }
        val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.06f; color = 0x40000000 }
        var x = phase - PITCH
        while (x < l.w) {
            for (y in floatArrayOf(top + HOLE_EDGE, bottom - HOLE_EDGE - HOLE_ACROSS)) {
                r.set(x, y, x + HOLE_ALONG, y + HOLE_ACROSS)
                c.drawRoundRect(r, HOLE_RADIUS, HOLE_RADIUS, hole)
                r.inset(0.03f, 0.03f)
                c.drawRoundRect(r, HOLE_RADIUS * 0.94f, HOLE_RADIUS * 0.94f, wall)
            }
            x += PITCH
        }

        // the edge print, as the maker's layout puts it
        if (o.edgeText != null || o.frame > 0) edgePrint135(c, look, ink, top, l.w, phase, if (o.frame > 0) o.frame else 1,
            o.edgeText != null, half)
        edgeLeaks(c, l.w, top, bottom, o)
        c.restore()
        o.date?.let { dateStamp(c, it, img) }
    }

    /**
     * The printed marks. Film millimetres: s along the strip from the view's left, t across
     * from the top edge. The print repeats every half frame (19.00 mm, 4 perforations),
     * fixed to the perforations; frame n shows "nA" under its middle, n and n+1 at its ends.
     */
    private fun edgePrint135(c: Canvas, look: EdgeLook, ink: Int, top: Float, w: Float, phase: Float, n: Int,
                             name: Boolean, half: Boolean) {
        val W = FILM_W
        val grid0 = phase + 0.62f
        fun text(t: String, s: Float, tFromTop: Float, p: Paint) = printed(c, t, s, top + tFromTop, p)
        when (look.layout) {
            Layout135.KODAK, Layout135.PLAIN -> {
                val cap = if (look.light) 1.07f else 1.22f
                val base = if (look.light) 1.69f else 1.53f
                val num = capPaint(ink, if (look.light) 1 else 0, cap, if (look.light) 1f else 1.25f, if (look.light) 0.04f else 0.25f)
                val title = capPaint(ink, 0, cap, 1f, 0.06f)
                for (m in -2..2) {
                    // the A cell under the frame's middle at m = 0, full numbers either side
                    val aHalf = m % 2 == 0
                    val s = grid0 + 19f * m
                    val number = n + (if (aHalf) m / 2 else (m + 1) / 2)
                    if (!aHalf) text("$number", s + 16.9f, base, num)
                    else if (name && !half) {
                        val s0 = s + if (look.light) 5.1f else 6.4f
                        var label = look.name
                        while (title.measureText(label) > 19f + 16.9f - (s0 - s) - 2f && label.contains(' ')) label = label.substringBeforeLast(' ')
                        text(label, s0, base, title)
                    }
                    // the bottom band: the DX code, then "n" or "nA" over the wind-on arrow
                    if (look.dx >= 0 && !half) dxCode(c, look.dx, number, aHalf, s, top, ink)
                    if (aHalf) {
                        text("${number}A", s + 15.3f, W - 0.84f, capPaint(ink, if (look.light) 1 else 0, 0.90f, if (look.light) 1f else 1.25f, 0.25f))
                        windArrow(c, s + 14.47f, s + 18.07f, top + W - 0.28f, 0.6f, 0.26f, ink)
                    } else text("$number", s + 16.0f, W - 0.14f, capPaint(ink, if (look.light) 1 else 0, 1.40f, if (look.light) 1f else 1.25f, 0.25f))
                }
            }
            Layout135.FUJI_NEG -> {
                // numbers on both bands, condensed; the stock's name on the top band of every A cell
                for (m in -2..2) {
                    val aHalf = m % 2 == 0
                    val s = grid0 + 19f * m
                    val number = n + (if (aHalf) m / 2 else (m + 1) / 2)
                    val label = "$number" + if (aHalf) "A" else ""
                    val p = capPaint(ink, 2, 1.2f, 1.05f, 0.08f)
                    text(label, s + if (aHalf) 16.38f else 15.75f, 1.60f, p)
                    if (aHalf && name) text(look.name, s + 5.5f, 1.50f, capPaint(ink, 2, 1.05f, 1f, 0.1f))
                    if (look.dx >= 0) dxCode(c, look.dx, number, aHalf, s, top, ink, 14.1f)
                    text(label, s + if (aHalf) 16.1f else 15.25f, W - if (aHalf) 0.41f else 0.31f, p)
                    if (aHalf) windArrow(c, s + 14.6f, s + 15.85f, top + W - 1.0f, 0.55f, 0.3f, ink)
                }
            }
            Layout135.FUJI_SLIDE -> {
                // no bars: the number on both bands, the name 16 mm after it, "36 -> nA" at the half
                for (m in -2..2) {
                    val sn = phase + 0.56f + 3.5f + 19f * m
                    val full = m % 2 == 0
                    val number = n + m / 2
                    val p = capPaint(ink, 0, 1.3f, 1.05f, 0.12f)
                    if (full) {
                        text("$number", sn, 1.80f, p)
                        text("$number", sn + 0.1f, W - 0.27f, p)
                        if (name) text(look.name, sn + 16.1f, 1.70f, capPaint(ink, 2, 1.04f, 1.05f, 0.14f))
                    } else {
                        text("36", sn - 2.6f, W - 0.27f, capPaint(ink, 2, 0.9f))
                        windArrow(c, sn - 0.6f, sn + 0.9f, top + W - 0.62f, 0.5f, 0.25f, ink)
                        text("${n + (m + 1) / 2 - 1}A", sn + 1.18f, W - 0.27f, capPaint(ink, 2, 0.9f, 1.05f, 0.1f))
                    }
                }
            }
            Layout135.CINE -> {
                // one line beside the lower perforations in the edge printer's 5x7 face, a dash every 38 mm
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
                for (k in -1..1) {
                    val dash = phase + 2.1f + 38f * k
                    c.drawRect(dash, top + W - 0.80f - 0.11f, dash + 1.16f, top + W - 0.80f + 0.11f, p)
                    if (name) matrix(c, look.name, dash + 10.1f, top + W - 0.47f, 0.88f, 0.70f, ink)
                }
            }
            Layout135.CINESTILL -> {
                for (k in -1..1) {
                    val sN = phase + 6.34f + 38f * k
                    val number = n + k
                    text("$number", sN, W - 0.02f, capPaint(ink, 3, 1.68f, 1.28f))
                    if (name) matrix(c, look.name, sN - 6.21f, top + 1.64f, 1.38f, 1.38f, ink)
                    // the stepped arrow and "nA"
                    val sa = sN + 18.59f
                    val tc = top + W - 0.915f
                    val tiers = floatArrayOf(0.37f, 0.455f, 0.80f, 0.365f, 1.08f, 0.235f, 1.50f, 0.094f)
                    var a0 = 0f
                    val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
                    for (i in 0 until 4) { c.drawRect(sa + a0, tc - tiers[i * 2 + 1], sa + tiers[i * 2] + 0.01f, tc + tiers[i * 2 + 1], q); a0 = tiers[i * 2] }
                    text("${number}A", sa + 1.75f, W - 0.50f, capPaint(ink, 3, 1.0f, 1.25f))
                }
            }
        }
    }

    /** Marks printed by light: a slight bloom round each (the develop spreads it ~0.02 mm), then the mark. */
    private fun printed(c: Canvas, text: String, x: Float, y: Float, p: Paint) {
        // (no blur filter: on a canvas scaled to millimetres a tiny blur radius smears the
        // marks into blots; a faint outline spreads them by the develop's ~0.03 mm instead)
        val bloom = Paint(p).apply { alpha = 0x40; style = Paint.Style.STROKE; strokeWidth = 0.06f; strokeJoin = Paint.Join.ROUND }
        c.drawText(text, x, y, bloom)
        c.drawText(text, x, y, Paint(p).apply { alpha = 0xee })
    }

    private fun windArrow(c: Canvas, a0: Float, a1: Float, tc: Float, head: Float, halfH: Float, ink: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 0xe6 }
        c.drawRect(a0, tc - 0.09f, a1 - 0.75f * head, tc + 0.09f, p)
        c.drawPath(Path().apply { moveTo(a1 - head, tc - halfH); lineTo(a1, tc); lineTo(a1 - head, tc + halfH); close() }, p)
    }

    /**
     * The DX film-edge code (ISO 1007, with the frame number): 31 modules. The clock track
     * nearer the perforations (a 5-module bar, 23 single modules alternating from white, a
     * 3-module bar); the data track at the film's edge (start b/w/b/w/b, 23 bits, stop
     * b/w/b): 0, the DX number's 7 + 4 bits, the frame number's 6, the half-frame bit, a
     * parity bit. 12.7 mm long on Kodak-made film, 14.1 on Fujifilm's.
     */
    private fun dxCode(c: Canvas, dx: Int, frame: Int, halfFrame: Boolean, s: Float, top: Float, ink: Int, length: Float = 12.7f) {
        val bits = IntArray(23)
        val p1 = (dx shr 4) and 0x7f
        val p2 = dx and 0xf
        for (i in 0 until 7) bits[1 + i] = (p1 shr (6 - i)) and 1
        for (i in 0 until 4) bits[9 + i] = (p2 shr (3 - i)) and 1
        val f = frame.coerceIn(0, 63)
        for (i in 0 until 6) bits[13 + i] = (f shr (5 - i)) and 1
        bits[19] = if (halfFrame) 1 else 0
        bits[21] = bits.take(21).sum() % 2
        val mod = length / 31f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; alpha = 0xdc
        }
        val clockT0 = top + FILM_W - 2.17f
        val dataT0 = top + FILM_W - 0.87f
        val dataT1 = top + FILM_W + 0.05f
        for (track in 0..1) {
            var i = 0
            while (i < 31) {
                val on = if (track == 0) (i < 5 || i >= 28 || (i - 5) % 2 == 1)
                    else if (i < 5) i % 2 == 0 else if (i >= 28) (i - 28) % 2 == 0 else bits[i - 5] == 1
                if (!on) { i++; continue }
                var j = i
                while (j < 31) {
                    val next = if (track == 0) (j < 5 || j >= 28 || (j - 5) % 2 == 1)
                        else if (j < 5) j % 2 == 0 else if (j >= 28) (j - 28) % 2 == 0 else bits[j - 5] == 1
                    if (!next) break
                    j++
                }
                if (track == 0) c.drawRect(s + i * mod, clockT0, s + j * mod, dataT0 - 0.1f, p)
                else c.drawRect(s + i * mod, dataT0, s + j * mod, dataT1, p)
                i = j
            }
        }
    }

    /** The edge printer's 5 x 7 face (Eastman, CineStill): each lit cell a near-square block. */
    private fun matrix(c: Canvas, text: String, x0: Float, baseline: Float, cap: Float, advance: Float, ink: Int) {
        val cell = cap / 7f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; alpha = 0xe6
        }
        var x = x0
        for (ch in text.uppercase()) {
            val rows = GLYPHS[ch]
            if (rows != null) for ((ry, bitsRow) in rows.withIndex()) for (cx in 0 until 5)
                if ((bitsRow shr (4 - cx)) and 1 == 1) {
                    val col = advance / 6f // five columns and a column of space
                    val left = x + cx * col
                    val y = baseline - cap + ry * cell
                    c.drawRect(left + col * 0.04f, y + cell * 0.06f, left + col * 0.96f, y + cell * 0.94f, p)
                }
            x += advance
        }
    }

    private val GLYPHS: Map<Char, IntArray> = mapOf(
        '0' to intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E), '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
        '2' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F), '3' to intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
        '4' to intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02), '5' to intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
        '6' to intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E), '7' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
        '8' to intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E), '9' to intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
        'A' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x1F, 0x11, 0x11), 'B' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E),
        'C' to intArrayOf(0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E), 'D' to intArrayOf(0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C),
        'E' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F), 'F' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10),
        'G' to intArrayOf(0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F), 'H' to intArrayOf(0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
        'I' to intArrayOf(0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E), 'J' to intArrayOf(0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C),
        'K' to intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11), 'L' to intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F),
        'M' to intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11), 'N' to intArrayOf(0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11),
        'O' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E), 'P' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
        'Q' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D), 'R' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11),
        'S' to intArrayOf(0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E), 'T' to intArrayOf(0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
        'U' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E), 'V' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04),
        'W' to intArrayOf(0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A), 'X' to intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11),
        'Y' to intArrayOf(0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04), 'Z' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F),
        '-' to intArrayOf(0, 0, 0, 0x1F, 0, 0, 0), '.' to intArrayOf(0, 0, 0, 0, 0, 0x0C, 0x0C), '+' to intArrayOf(0, 0x04, 0x04, 0x1F, 0x04, 0x04, 0),
    )

    /**
     * Base fog along the film's long edges, where light pipes in at the cassette's lips:
     * a band 0.8-1.8 mm deep, its strength wandering along the strip every 5-11 mm. In a
     * scan of the negative it lifts the rebate toward brown.
     */
    private fun edgeFog(c: Canvas, w: Float, top: Float, bottom: Float, o: Options, slide: Boolean) {
        if (o.fog <= 0f) return
        val rnd = Random(o.seed * 4111 + 3)
        val depth = 0.8f + rnd.nextFloat() * 1.0f
        val period = 5f + rnd.nextFloat() * 6f
        val tone = if (slide) 0x2a2018 else 0x6b4428
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val step = 0.25f
        var x = 0f
        while (x < w) {
            val k = (0.55f + 0.45f * kotlin.math.sin(x / period * 6.283f + rnd.nextFloat() * 0.4f)) * o.fog
            val a = (k * 0x9c).toInt().coerceIn(0, 255)
            for ((y0, y1) in listOf(top to top + depth, bottom to bottom - depth)) {
                p.shader = android.graphics.LinearGradient(0f, y0, 0f, y1, (a shl 24) or tone, tone, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(x, min(y0, y1), x + step + 0.02f, max(y0, y1), p)
            }
            x += step
        }
        p.shader = null
    }

    /**
     * Light leaks as they really get in: at the long edges, a few soft patches 1.2-4 mm
     * along and 0.4-1.3 mm deep, most on one side (the spool's), each its own strength.
     * Light from behind the film has no blue (the yellow filter stops it), so a leak prints
     * red, and orange to yellow where it is strong; it reaches over the rebate and a little
     * into the picture.
     */
    private fun edgeLeaks(c: Canvas, w: Float, top: Float, bottom: Float, o: Options) {
        if (o.leak <= 0f) return
        val rnd = Random(o.seed * 7919 + 13)
        val k = o.leak.coerceIn(0f, 1f)
        val onTop = rnd.nextBoolean()
        val edge = if (onTop) top else bottom
        val into = if (onTop) 1f else -1f
        val centre = w * (0.15f + 0.7f * rnd.nextFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        fun argb(a: Float, rgb: Int) = ((a * 255).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xffffff)
        // the leak's body: a broad red glow from the edge, deeper where it is stronger
        val reach = (3f + 9f * k) * (0.8f + 0.4f * rnd.nextFloat())
        val along = (8f + 16f * k)
        val body = android.graphics.RadialGradient(0f, 0f, 1f,
            intArrayOf(argb(0.85f * k + 0.1f, 0xff5a1e), argb(0.6f * k + 0.08f, 0xe0301a), argb(0.25f * k, 0xa01810), 0),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
        body.setLocalMatrix(android.graphics.Matrix().apply { setScale(along, reach); postTranslate(centre, edge) })
        p.shader = body
        c.drawRect(centre - along, edge - reach, centre + along, edge + reach, p)
        // its lobes, where the light came in strongest: orange to yellow at their cores
        val lobes = 2 + (3 * k).toInt()
        repeat(lobes) {
            val s = centre + (rnd.nextFloat() - 0.5f) * along * 1.2f
            val sa = (1.5f + rnd.nextFloat() * 3.5f) * (1f + k)
            val st = (0.8f + rnd.nextFloat() * 1.8f) * (1f + 1.5f * k)
            val a = (0.35f + 0.6f * rnd.nextFloat()) * (0.4f + 0.6f * k)
            val lobe = android.graphics.RadialGradient(0f, 0f, 1f,
                intArrayOf(argb(a, if (a > 0.6f) 0xffd27a else 0xff8a3a), argb(a * 0.6f, 0xff5a20), 0),
                floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            lobe.setLocalMatrix(android.graphics.Matrix().apply { setScale(sa, st); postTranslate(s, edge + into * st * 0.15f) })
            p.shader = lobe
            c.drawRect(s - sa, edge - st, s + sa, edge + st, p)
        }
        p.shader = null
        p.xfermode = null
    }

    /** The rebate's own grain and a few specks: film is never a flat black. */
    private fun rebateGrain(c: Canvas, w: Float, top: Float, bottom: Float, seed: Int, slide: Boolean) {
        val rnd = Random(seed * 131 + 5)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // fine and faint: grain, not snow
        val n = (w * (bottom - top) * 7).toInt().coerceAtMost(20000)
        for (i in 0 until n) {
            val light = rnd.nextFloat()
            p.color = if (light > 0.55f) ((0x06 + (light * 0x0a).toInt()) shl 24) or 0xffffff else 0x10000000
            c.drawCircle(rnd.nextFloat() * w, top + rnd.nextFloat() * (bottom - top), 0.012f + rnd.nextFloat() * 0.022f, p)
        }
        // dust: a few bright specks (the scan sees dust on a negative as light)
        p.color = if (slide) 0x99000000.toInt() else 0x88f4f0e8.toInt()
        repeat(2 + rnd.nextInt(3)) {
            c.drawCircle(rnd.nextFloat() * w, top + rnd.nextFloat() * (bottom - top), 0.025f + rnd.nextFloat() * 0.04f, p)
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
                    "250d" in f -> "5207"; "50d" in f -> "5203"; "200t" in f -> "5213"; else -> "5219"
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
        if (kind == Kind.FILED || kind in STRIP135) return
        edgeFog(c, l.w, 0f, l.h, o, slide = false)
        edgeLeaks(c, l.w, 0f, l.h, o)
    }

    private val STRIP135 = setOf(Kind.NEG135, Kind.SLIDE135, Kind.HALF135, Kind.XPAN)

    /** A DX edge barcode: a clock track and a data track, the data from the frame number. */
    private fun dxBars(c: Canvas, ink: Int, frame: Int, x0: Float, y: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        val rnd = Random(frame)
        p.maskFilter = android.graphics.BlurMaskFilter(0.04f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        p.alpha = 0xd8
        for (i in 0 until 23) {
            val x = x0 + i * 0.38f
            c.drawRect(x, y, x + 0.19f, y + 0.42f, p)                                  // clock track
            if (i == 0 || i == 22 || rnd.nextFloat() > 0.48f) c.drawRect(x, y + 0.62f, x + 0.19f, y + 1.12f, p) // data
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
