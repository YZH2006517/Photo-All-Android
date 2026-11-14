package org.photoall.film.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.photoall.film.gallery.FrameDesign.Arrange
import org.photoall.film.gallery.FrameDesign.Border
import org.photoall.film.gallery.FrameDesign.Font
import org.photoall.film.gallery.FrameDesign.Item
import org.photoall.film.gallery.FrameDesign.Logo
import org.photoall.film.gallery.FrameDesign.Stamp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a [FrameDesign] round a photo. Sizes go by the photo's short side, so a preview
 * and the full-size copy are laid out alike. Text is set in blocks of lines; a block
 * turned 90 degrees runs down an edge, read from the top, like the spine of a magazine.
 */
object FrameDesigner {
    /** What can be written: the camera, the exposure, the film, the day. */
    class Data(
        val brand: String, val model: String, val lens: String?, val params: String?, val film: String?,
        val date: String, val stampText: String, val edgeName: String, val frame: Int,
        /** The stock as the engine names it ("kodak portra 400"), for the film edge. */
        val filmKey: String = "",
        /** The maker, normalised ("xiaomi", "samsung" ...), for its wordmark style. */
        val maker: String = "",
        /** A maker's wordmark image or typeface the user has put in the app's assets. */
        val brandImage: Bitmap? = null,
        val brandFont: Typeface? = null,
    )

    fun data(context: Context, shot: ShotInfo?, description: String, film: String, frame: Int, taken: Long): Data {
        val make = (shot?.make ?: android.os.Build.MANUFACTURER).trim()
        // this phone's own photo: its market name ("Xiaomi 14 Ultra") instead of the code
        val shotModel = shot?.model
        val ownPhone = shotModel == null || shotModel.equals(android.os.Build.MODEL, ignoreCase = true)
        val fullModel = (if (ownPhone) marketName() else null) ?: (shot?.model ?: android.os.Build.MODEL).trim()
        val maker = makerKey(make, fullModel)
        val model = fullModel.let { m ->
            val words = listOf(make, maker, BRANDS[maker]?.text ?: "").filter { it.isNotBlank() }
            words.fold(m) { acc, w -> if (acc.startsWith(w, ignoreCase = true)) acc.substring(w.length).trim() else acc }
        }.ifBlank { fullModel }
        val lens = shot?.let { s -> s.focal35?.let { "${it}mm" } ?: s.focalMm?.let { "%.0fmm".format(it) } }
        val params = shot?.let { s ->
            listOfNotNull(
                s.fNumber?.let { "ƒ/%.1f".format(it) },
                s.shutterText?.let { if (it.endsWith("s") || it.endsWith("\"")) it else "${it}s" },
                s.iso?.let { "ISO $it" },
            ).joinToString("  ").ifEmpty { null }
        }
        val date = java.text.SimpleDateFormat("yyyy.MM.dd  HH:mm", java.util.Locale.US).format(java.util.Date(taken))
        return Data(
            brand = make.uppercase(), model = model, lens = lens, params = params,
            film = description.takeIf { it.isNotBlank() }?.let { Frames.recipeText(context, it) },
            date = date, stampText = Frames.dateText(taken), edgeName = Frames.edgeName(film), frame = frame,
            filmKey = film, maker = maker,
            brandImage = runCatching { context.assets.open("brands/$maker.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull(),
            brandFont = listOf("ttf", "otf").firstNotNullOfOrNull { ext ->
                runCatching { Typeface.createFromAsset(context.assets, "fonts/$maker.$ext") }.getOrNull()
            },
        )
    }

    // ---- makers' wordmarks ---------------------------------------------------------------------

    /**
     * How each maker sets its name: case, weight, spacing and width, in the phone's own
     * system face (on most phones that is the maker's typeface: MiSans, HarmonyOS Sans, OPPO
     * Sans, SamsungOne...). The makers' logos and typefaces are their property and are not
     * copied into the app; a licensed wordmark image (assets/brands/<maker>.png) or typeface
     * (assets/fonts/<maker>.ttf) put there is used instead.
     */
    class BrandStyle(val text: String, val family: String, val style: Int, val spacing: Float, val scaleX: Float = 1f)

    val BRANDS = mapOf(
        "samsung" to BrandStyle("SAMSUNG", "sans-serif", Typeface.BOLD, 0.16f, 1.18f),
        "xiaomi" to BrandStyle("Xiaomi", "sans-serif-medium", Typeface.NORMAL, 0f),
        "redmi" to BrandStyle("Redmi", "sans-serif", Typeface.BOLD, 0f),
        "poco" to BrandStyle("POCO", "sans-serif-black", Typeface.NORMAL, 0.04f, 1.1f),
        "huawei" to BrandStyle("HUAWEI", "sans-serif", Typeface.BOLD, 0.1f),
        "honor" to BrandStyle("HONOR", "sans-serif-light", Typeface.NORMAL, 0.32f),
        "oppo" to BrandStyle("oppo", "sans-serif-medium", Typeface.NORMAL, 0.06f, 1.12f),
        "oneplus" to BrandStyle("OnePlus", "sans-serif", Typeface.BOLD, 0f),
        "vivo" to BrandStyle("vivo", "sans-serif", Typeface.BOLD, 0.02f),
        "iqoo" to BrandStyle("iQOO", "sans-serif", Typeface.BOLD_ITALIC, 0.02f),
        "realme" to BrandStyle("realme", "sans-serif-medium", Typeface.NORMAL, 0f),
        "google" to BrandStyle("Google", "sans-serif", Typeface.NORMAL, 0f),
        "apple" to BrandStyle("iPhone", "sans-serif-light", Typeface.NORMAL, 0f),
        "sony" to BrandStyle("SONY", "serif", Typeface.BOLD, 0.18f, 1.05f),
        "motorola" to BrandStyle("motorola", "sans-serif-medium", Typeface.NORMAL, 0f),
        "nothing" to BrandStyle("NOTHING", "monospace", Typeface.BOLD, 0.22f),
        "meizu" to BrandStyle("MEIZU", "sans-serif-light", Typeface.NORMAL, 0.3f),
        "nubia" to BrandStyle("nubia", "sans-serif-medium", Typeface.NORMAL, 0.02f),
        "redmagic" to BrandStyle("REDMAGIC", "sans-serif-black", Typeface.ITALIC, 0.04f),
        "asus" to BrandStyle("ASUS", "sans-serif", Typeface.BOLD_ITALIC, 0.12f, 1.1f),
        "rog" to BrandStyle("ROG", "sans-serif-black", Typeface.ITALIC, 0.1f, 1.15f),
        "zte" to BrandStyle("ZTE", "sans-serif", Typeface.BOLD, 0.06f, 1.1f),
        "lenovo" to BrandStyle("Lenovo", "sans-serif-medium", Typeface.NORMAL, 0f),
        "tecno" to BrandStyle("TECNO", "sans-serif", Typeface.BOLD, 0.14f),
        "infinix" to BrandStyle("Infinix", "sans-serif-medium", Typeface.NORMAL, 0f),
    )

    /** The maker under its sub-brands (Redmi and POCO are Xiaomi's, iQOO is vivo's ...). */
    fun makerKey(make: String, model: String): String {
        val m = make.lowercase().replace(" ", "")
        val first = model.trim().substringBefore(' ').lowercase()
        if (first in setOf("redmi", "poco", "iqoo", "redmagic", "rog")) return first
        return when {
            "samsung" in m -> "samsung"
            "xiaomi" in m -> "xiaomi"
            "huawei" in m -> "huawei"
            "honor" in m -> "honor"
            "oneplus" in m -> "oneplus"
            "oppo" in m -> "oppo"
            "vivo" in m -> "vivo"
            "realme" in m -> "realme"
            "google" in m -> "google"
            "apple" in m -> "apple"
            "sony" in m -> "sony"
            "motorola" in m || m == "moto" -> "motorola"
            "nothing" in m -> "nothing"
            "meizu" in m -> "meizu"
            "nubia" in m -> "nubia"
            "asus" in m -> "asus"
            "zte" in m -> "zte"
            "lenovo" in m -> "lenovo"
            "tecno" in m -> "tecno"
            "infinix" in m -> "infinix"
            else -> m
        }
    }

    /** The name this phone is sold under, where the system says it. */
    private fun marketName(): String? {
        val get = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        }.getOrNull() ?: return null
        for (key in listOf("ro.product.marketname", "ro.product.vendor.marketname", "ro.vendor.oplus.market.name",
            "ro.oppo.market.name", "ro.vivo.market.name", "ro.config.marketing_name")) {
            val v = runCatching { get.invoke(null, key) as String }.getOrNull()?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    // ---- the picture as a whole --------------------------------------------------------------

    fun render(source: Bitmap, d: FrameDesign, data: Data, maxLong: Int = 4096): Bitmap {
        val sheet = renderSheet(source, d, data, maxLong)
        if (!d.cartridge) return sheet
        val out = if (sheet.isMutable) sheet else sheet.copy(Bitmap.Config.ARGB_8888, true).also { sheet.recycle() }
        drawCartridge(Canvas(out), out.width, out.height, d, data.filmKey)
        return out
    }

    /** The cartridge on a sheet [w] x [h]: where the design puts it, as big as it says, straight on. */
    fun drawCartridge(c: Canvas, w: Int, h: Int, d: FrameDesign, film: String, onReady: (() -> Unit)? = null) {
        val name = film.takeIf { it.isNotBlank() && it != "直出" } ?: "kodak portra 400"
        val ch = d.cartSize * min(w, h)
        // on screen: whatever picture there is, redrawn when the 3D one comes; in an export: wait for it
        if (onReady != null) org.photoall.film.ui.CartridgeSprites.draw(c, d.cartX * w, d.cartY * h, ch, name, leader = 0.35f, onReady = onReady)
        else org.photoall.film.ui.CartridgeSprites.drawWaiting(c, d.cartX * w, d.cartY * h, ch, name, leader = 0.35f)
    }

    private fun renderSheet(source: Bitmap, d: FrameDesign, data: Data, maxLong: Int): Bitmap {
        d.border.kind?.let { kind -> return renderFilm(source, kind, d, data, maxLong) }
        d.border.theme?.let { theme -> return renderTheme(source, theme, d, data, maxLong) }
        val k = min(1f, maxLong.toFloat() / max(source.width, source.height))
        val pw = source.width * k
        val ph = source.height * k
        val s = min(pw, ph)
        val t = d.thickness
        // the margins round the photo: left, top, right, bottom
        val (l, top, r, b) = when (d.border) {
            Border.EVEN -> (s * (0.025f + 0.085f * t)).let { listOf(it, it, it, it) }
            Border.BOTTOM -> (s * (0.02f + 0.03f * t)).let { e -> listOf(e, e, e, s * (0.09f + 0.13f * t)) }
            Border.SIDE -> (s * (0.02f + 0.03f * t)).let { e -> listOf(e, e, s * (0.09f + 0.13f * t), e) }
            Border.INSTANT -> (s * 0.055f).let { e -> listOf(e, e, e, s * (0.18f + 0.1f * t)) }
            Border.CUSTOM -> customMargins(pw, ph, d)
            else -> listOf(0f, 0f, 0f, 0f)
        }
        val w = (pw + l + r).roundToInt()
        val h = (ph + top + b).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // the card
        val cardLuma: Float
        if (d.background == FrameDesign.BLUR && d.border != Border.NONE) {
            val blur = blurred(source, 64)
            val cover = coverRect(blur.width, blur.height, w, h)
            c.drawBitmap(blur, null, cover, paint)
            // frosted: a pale mist over the blur, so the card reads apart from the photo
            c.drawColor(0x47ffffff)
            cardLuma = luma(source) * 0.72f + 0.28f
            blur.recycle()
        } else {
            val bg = if (d.background == FrameDesign.BLUR) FrameDesign.WHITE else d.background
            c.drawColor(bg)
            cardLuma = luma(bg)
        }
        val photo = RectF(l, top, l + pw, top + ph)
        val radius = if (d.border == Border.NONE) 0f else s * 0.045f * d.corner
        // the photo lifted off the card
        if (d.shadow && d.border != Border.NONE) {
            paint.color = 0x59000000
            paint.maskFilter = BlurMaskFilter(s * 0.03f, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(photo.left, photo.top + s * 0.014f, photo.right, photo.bottom + s * 0.014f, radius, radius, paint)
            paint.maskFilter = null
        }
        c.save()
        if (radius > 0f) c.clipPath(Path().apply { addRoundRect(photo, radius, radius, Path.Direction.CW) })
        c.drawBitmap(source, null, photo, paint)
        c.restore()
        val words = drawMarks(c, d, data, photo, RectF(0f, 0f, w.toFloat(), h.toFloat()), s, cardLuma, source)
        drawStamp(c, d, data, photo, s, avoid = words)
        return out
    }

    /**
     * A theme's own paper ([ThemePapers]): the paper and its ornament under the photo, the
     * photo in the theme's shape, the ornament that goes over it, then the words in the
     * paper's own field (clear of what the ornament keeps), and the stamp clear of both.
     */
    private fun renderTheme(source: Bitmap, t: ThemePapers.Theme, d: FrameDesign, data: Data, maxLong: Int): Bitmap {
        val plan = ThemePapers.plan(t, source.width, source.height, maxLong)
        val out = Bitmap.createBitmap(plan.w, plan.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val photo = plan.photo
        val s = min(photo.width(), photo.height())
        ThemePapers.drawUnder(c, t, plan, d.leakSeed)
        c.drawBitmap(source, null, photo, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        ThemePapers.drawOver(c, t, plan, d.leakSeed, data.date, data.frame, data.filmKey)
        val keep = plan.keep
        val words = if (d.inPhoto) drawMarks(c, d, data, photo, plan.sheet, s, 0.5f, source, keepOut = keep)
            else drawMarks(c, d.copy().apply { vertical = false }, data, photo, plan.sheet, s, 0.9f,
                source, given = plan.text, keepOut = keep, inkOverride = if (d.ink == FrameDesign.AUTO) ThemePapers.ink(t) else null)
        drawStamp(c, d, data, photo, s, avoid = words + keep)
        return out
    }

    /**
     * A custom card: its proportions ([FrameDesign.ratio]), the photo taking [FrameDesign.photoScale]
     * of it where it fits tightest, and placed by [FrameDesign.photoAnchor] in the room left
     * (the smaller margin kept on every side, so a photo pushed to an edge still has one).
     */
    private fun customMargins(pw: Float, ph: Float, d: FrameDesign): List<Float> {
        val aspect = if (d.ratio == FrameDesign.Ratio.PHOTO) pw / ph else d.ratio.w / d.ratio.h
        val k = d.photoScale.coerceIn(0.3f, 1f)
        var w = pw / k
        var h = w / aspect
        if (ph > h * k) { h = ph / k; w = h * aspect }
        val fx = (d.photoAnchor % 3) / 2f
        val fy = (d.photoAnchor / 3) / 2f
        val m = (min(w - pw, h - ph) / 2).coerceAtLeast(0f)
        val left = m + (w - pw - 2 * m) * fx
        val top = m + (h - ph - 2 * m) * fy
        return listOf(left, top, w - pw - left, h - ph - top)
    }

    /** Film edges come from [Frames]; over them only what goes on the picture. */
    private fun renderFilm(source: Bitmap, kind: Frames.Kind, d: FrameDesign, data: Data, maxLong: Int): Bitmap {
        val picture = RectF()
        val base = Frames.render(source, kind, Frames.Options(edgeText = data.edgeName, frame = data.frame, date = null,
            film = data.filmKey, fog = if (d.fog) 1f else 0f, leak = d.leak, seed = d.leakSeed * 101 + data.frame),
            maxLong = maxLong, rotation = d.turn, imageOut = picture)
        val out = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true).also { base.recycle() }
        val c = Canvas(out)
        val all = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        if (picture.isEmpty) picture.set(all)
        val s = min(picture.width(), picture.height())
        val inside = d.copy().apply { inPhoto = true }
        // the photo as it lies in the picture's place (centre-cropped to it), for what is under the words
        val words = drawMarks(c, inside, data, picture, all, s, 0.2f, source)
        drawStamp(c, d, data, picture, s, avoid = words)
        return out
    }

    // ---- words and marks ---------------------------------------------------------------------

    private class Run(val text: String, val face: Typeface, val size: Float, val alpha: Float, val spacing: Float = 0f,
                      val scaleX: Float = 1f, val image: Bitmap? = null)
    private class Line(val runs: List<Run>, val logo: Logo = Logo.NONE) {
        val size get() = runs.maxOfOrNull { it.size } ?: 0f
    }
    private class Block(val lines: List<Line>)

    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun runWidth(r: Run): Float {
        r.image?.let { return r.size * 1.05f * it.width / it.height.coerceAtLeast(1) }
        measure.typeface = r.face; measure.textSize = r.size; measure.letterSpacing = r.spacing; measure.textScaleX = r.scaleX
        return measure.measureText(r.text)
    }

    private fun lineWidth(line: Line): Float {
        var w = line.runs.sumOf { runWidth(it).toDouble() }.toFloat() + max(0, line.runs.size - 1) * line.size * 0.5f
        if (line.logo != Logo.NONE) w += logoWidth(line.logo, line.size) + if (line.runs.isEmpty()) 0f else line.size * 0.45f
        return w
    }

    private fun lineHeight(line: Line) = line.size * 1.32f
    private fun blockWidth(b: Block) = b.lines.maxOfOrNull { lineWidth(it) } ?: 0f
    private fun blockHeight(b: Block) = b.lines.sumOf { lineHeight(it).toDouble() }.toFloat()

    private fun brandFace(f: Font): Typeface = when (f) {
        Font.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        Font.SANS -> Typeface.create("sans-serif", Typeface.BOLD)
        Font.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        Font.LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        Font.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        Font.SCRIPT -> Typeface.create("cursive", Typeface.NORMAL)
    }

    private fun detailFace(f: Font): Typeface = when (f) {
        Font.SERIF, Font.MONO -> Typeface.MONOSPACE
        Font.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        Font.SCRIPT -> Typeface.create("cursive", Typeface.NORMAL)
        else -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    /** The chosen lines, in their order: the name (with the mark), then the data. */
    private fun lines(d: FrameDesign, data: Data, base: Float): Pair<Line?, List<Line>> {
        val brandSize = base * if (d.font == Font.SCRIPT) 1.9f else 1.6f
        val spacing = when (d.font) { Font.SERIF, Font.SANS, Font.CONDENSED -> 0.14f; Font.LIGHT -> 0.2f; else -> 0f }
        // the name: in its maker's style (or their licensed wordmark), or in the chosen face
        val style = BRANDS[data.maker]
        val name = when {
            !d.brandMark -> Run(data.brand, brandFace(d.font), brandSize, 1f, spacing)
            data.brandImage != null -> Run(data.brand, brandFace(d.font), brandSize, 1f, image = data.brandImage)
            style != null -> Run(style.text, data.brandFont ?: Typeface.create(style.family, style.style), brandSize, 1f,
                style.spacing, style.scaleX)
            else -> Run(data.brand, data.brandFont ?: brandFace(d.font), brandSize, 1f, spacing)
        }
        val head = if (Item.BRAND in d.items || d.logo != Logo.NONE)
            Line(if (Item.BRAND in d.items) listOf(name) else emptyList(), d.logo)
        else null
        val detail = detailFace(d.font)
        val sub = mutableListOf<Line>()
        fun add(text: String?, size: Float = base, alpha: Float = 0.62f, face: Typeface = detail) {
            if (!text.isNullOrBlank()) sub += Line(listOf(Run(text, face, size, alpha)))
        }
        // always in this order (the name first, the model under it), whatever order they were picked in
        for (item in Item.entries.filter { it in d.items }) when (item) {
            Item.MODEL -> add(data.model, base * 1.02f, 0.85f)
            Item.LENS -> add(data.lens)
            Item.PARAMS -> add(data.params)
            Item.FILM -> add(data.film)
            Item.DATE -> add(data.date)
            Item.CAPTION -> add(d.caption, base * if (d.font == Font.SCRIPT) 1.5f else 1.1f, 0.9f,
                if (d.font == Font.SCRIPT) detail else Typeface.create(Typeface.SERIF, Typeface.ITALIC))
            else -> {}
        }
        return head to sub
    }

    /** The blocks to place, with how each sits in the area (fx, fy: 0 start, 0.5 middle, 1 end). */
    private class Placed(val block: Block, val fx: Float, val fy: Float)

    private fun drawMarks(c: Canvas, d: FrameDesign, data: Data, photo: RectF, sheet: RectF, s: Float, cardLuma: Float, source: Bitmap?,
                          given: RectF? = null, keepOut: List<RectF> = emptyList(), inkOverride: Int? = null): List<RectF> {
        val row = d.anchor / 3
        val col = d.anchor % 3
        val m = s * 0.045f
        val onPhoto = given == null && (d.inPhoto || d.border == Border.NONE)
        // the area the words go in, and whether its long side runs across
        val area: RectF
        val across: Boolean
        if (given != null) {
            area = RectF(given)
            across = true
        } else if (onPhoto) {
            area = RectF(photo).apply { inset(m, m) }
            across = row != 1 || col == 1
        } else {
            val band = when (d.border) {
                Border.BOTTOM, Border.INSTANT -> 2
                Border.SIDE -> 3
                else -> when { row == 0 -> 0; row == 2 -> 2; col == 0 -> 1; col == 2 -> 3; else -> 2 }
            }
            area = when (band) {
                0 -> RectF(photo.left, sheet.top, photo.right, photo.top)
                1 -> RectF(sheet.left, photo.top, photo.left, photo.bottom)
                3 -> RectF(photo.right, photo.top, sheet.right, photo.bottom)
                else -> RectF(photo.left, photo.bottom, photo.right, sheet.bottom)
            }
            across = band == 0 || band == 2
            if (across) area.inset(m * 0.4f, area.height() * 0.14f) else area.inset(area.width() * 0.14f, m * 0.4f)
        }
        if (area.width() <= 1f || area.height() <= 1f) return emptyList()

        fun build(base: Float): List<Placed> {
            val (head, sub) = lines(d, data, base)
            val all = listOfNotNull(head) + sub
            if (all.isEmpty()) return emptyList()
            val atStart = if (onPhoto) (if (across) col / 2f else row / 2f) else if (across) col / 2f else row / 2f
            val edge = if (onPhoto) (if (across) row / 2f else col / 2f) else 0.5f
            fun at(along: Float, block: Block) =
                if (across) Placed(block, along, edge) else Placed(block, edge, along)
            return when (d.arrange) {
                Arrange.STACK -> listOf(at(atStart, Block(all)))
                Arrange.LINE, Arrange.DIVIDER -> {
                    val runs = mutableListOf<Run>()
                    head?.runs?.let { runs += it }
                    val rest = sub.flatMap { it.runs }
                    if (d.arrange == Arrange.DIVIDER && runs.isNotEmpty() && rest.isNotEmpty())
                        runs += Run("|", detailFace(d.font), base * 1.3f, 0.35f)
                    runs += rest
                    val line = Line(runs, head?.logo ?: Logo.NONE)
                    listOf(at(if (d.arrange == Arrange.DIVIDER) 0.5f else atStart, Block(listOf(line))))
                }
                Arrange.ENDS -> {
                    var a = listOfNotNull(head) + sub.take(if (Item.MODEL in d.items && data.model.isNotBlank()) 1 else 0)
                    var bLines = sub.drop(a.size - (if (head != null) 1 else 0))
                    // no name to lead with: the first line at one end, the rest at the other
                    if (a.isEmpty() && bLines.size >= 2) { a = bLines.take(1); bLines = bLines.drop(1) }
                    listOfNotNull(
                        a.takeIf { it.isNotEmpty() }?.let { at(0f, Block(it)) },
                        bLines.takeIf { it.isNotEmpty() }?.let { at(1f, Block(it)) },
                    )
                }
            }
        }

        // size: by the photo, then shrunk to fit the area
        var base = s * (0.017f + 0.017f * d.size)
        var placed = build(base)
        if (placed.isEmpty()) return emptyList()
        fun extent(p: Placed) = if (d.vertical) blockHeight(p.block) to blockWidth(p.block) else blockWidth(p.block) to blockHeight(p.block)
        val alongTotal = placed.sumOf { (if (across) extent(it).first else extent(it).second).toDouble() }.toFloat() *
            (if (placed.size > 1) 1.12f else 1f)
        val crossMax = placed.maxOf { if (across) extent(it).second else extent(it).first }
        val alongRoom = if (across) area.width() else area.height()
        val crossRoom = if (across) area.height() else area.width()
        val k = min(1f, min(alongRoom / alongTotal, crossRoom / crossMax))
        if (k < 1f) { base *= k * 0.98f; placed = build(base) }

        fun boxOf(p: Placed): RectF {
            val (bw, bh) = extent(p)
            return RectF(area.left + (area.width() - bw) * p.fx, area.top + (area.height() - bh) * p.fy, 0f, 0f).also {
                it.right = it.left + bw; it.bottom = it.top + bh
            }
        }
        // The automatic layout: the words try the places they could go (and a size or two
        // smaller), and take the one where nothing covers them: not the ornament, the date
        // stamp's corner or each other, and on a picture, the calmest part of it.
        if (d.autoPlace) placed = autoPlace(placed, base, ::build, ::boxOf, area, keepOut, onPhoto, across, source, photo, d)

        val inkDefault = inkOverride ?: d.ink
        val boxes = mutableListOf<RectF>()
        for (p in placed) {
            val (bw, bh) = extent(p)
            val box = boxOf(p)
            boxes += RectF(box)
            // what the words sit on decides their colour when it is left to us
            val under = if (onPhoto && source != null) regionLuma(source, photo, box) else cardLuma
            val ink = if (inkDefault == FrameDesign.AUTO) (if (under > 0.56f) 0xff1c1c1e.toInt() else 0xfff5f5f7.toInt()) else inkDefault
            val shadow = onPhoto && luma(ink) > 0.5f
            val alongAlign = if (d.vertical) p.fy else p.fx
            c.save()
            if (d.vertical) {
                c.translate(box.right, box.top)
                c.rotate(90f)
            } else c.translate(box.left, box.top)
            drawBlock(c, p.block, if (d.vertical) bh else bw, alongAlign, ink, shadow, custom = inkDefault != FrameDesign.AUTO)
            c.restore()
        }
        return boxes
    }

    /**
     * Each candidate is scored: what it covers of [keepOut] and of the other blocks (heavily),
     * how busy the picture is under it (on a photo), how much it had to shrink, and how far
     * it moved from where the user put it. The lowest score wins.
     */
    private fun autoPlace(start: List<Placed>, base: Float, build: (Float) -> List<Placed>, boxOf: (Placed) -> RectF,
                          area: RectF, keepOut: List<RectF>, onPhoto: Boolean, across: Boolean, source: Bitmap?,
                          photo: RectF, d: FrameDesign): List<Placed> {
        if (keepOut.isEmpty() && !onPhoto && start.size < 2) return start
        val spots = if (onPhoto) listOf(0f, 0.5f, 1f) else listOf(0.5f)
        val alongs = listOf(0f, 0.5f, 1f)
        var best = start
        var bestCost = Float.MAX_VALUE
        for (scale in floatArrayOf(1f, 0.86f, 0.74f)) {
            val blocks = if (scale == 1f) start else build(base * scale)
            if (blocks.size != start.size) continue
            val variants = mutableListOf<List<Placed>>()
            if (blocks.size == 1) {
                val b = blocks[0]
                for (a in alongs) for (e in spots)
                    variants += listOf(if (across) Placed(b.block, a, if (onPhoto) e else b.fy) else Placed(b.block, if (onPhoto) e else b.fx, a))
            } else {
                // the two ends stay ends: only the row (on a photo) and which end each takes
                for (e in spots) for (swap in listOf(false, true)) {
                    variants += blocks.mapIndexed { i, b ->
                        val along = if (across) b.fx else b.fy
                        val a = if (swap && blocks.size == 2) (if (i == 0) 1f else 0f) else along
                        if (across) Placed(b.block, a, if (onPhoto) e else b.fy) else Placed(b.block, if (onPhoto) e else b.fx, a)
                    }
                }
            }
            for (v in variants) {
                val boxes = v.map(boxOf)
                var cost = (1f - scale) * 0.8f
                for (bx in boxes) {
                    val ba = (bx.width() * bx.height()).coerceAtLeast(1f)
                    for (k in keepOut) cost += 6f * overlap(bx, k) / ba
                    if (!area.contains(bx)) cost += 4f
                    if (onPhoto && source != null) cost += 1.6f * regionDetail(source, photo, bx)
                }
                for (i in boxes.indices) for (j in i + 1 until boxes.size)
                    cost += 6f * overlap(boxes[i], boxes[j]) / (boxes[i].width() * boxes[i].height()).coerceAtLeast(1f)
                // where the user put it counts a little
                for ((i, p) in v.withIndex()) {
                    val o = start.getOrNull(i) ?: continue
                    cost += 0.12f * (abs(p.fx - o.fx) + abs(p.fy - o.fy))
                }
                if (cost < bestCost - 1e-4f) { bestCost = cost; best = v }
            }
        }
        return best
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (w > 0 && h > 0) w * h else 0f
    }

    /** How busy the picture is under [box]: the spread of its brightness, 0 (flat) .. ~1. */
    private fun regionDetail(photo: Bitmap, at: RectF, box: RectF): Float {
        val x0 = ((box.left - at.left) / at.width()).coerceIn(0f, 1f)
        val x1 = ((box.right - at.left) / at.width()).coerceIn(0f, 1f)
        val y0 = ((box.top - at.top) / at.height()).coerceIn(0f, 1f)
        val y1 = ((box.bottom - at.top) / at.height()).coerceIn(0f, 1f)
        if (x1 <= x0 || y1 <= y0) return 0f
        var sum = 0f; var sq = 0f; var edges = 0f; var n = 0
        var prev = -1f
        for (iy in 0 until 8) {
            prev = -1f
            for (ix in 0 until 10) {
                val px = ((x0 + (x1 - x0) * (ix + 0.5f) / 10) * (photo.width - 1)).toInt().coerceIn(0, photo.width - 1)
                val py = ((y0 + (y1 - y0) * (iy + 0.5f) / 8) * (photo.height - 1)).toInt().coerceIn(0, photo.height - 1)
                val l = luma(photo.getPixel(px, py))
                sum += l; sq += l * l; n++
                if (prev >= 0f) edges += abs(l - prev)
                prev = l
            }
        }
        val mean = sum / n
        val sd = kotlin.math.sqrt((sq / n - mean * mean).coerceAtLeast(0f))
        return (sd * 2.2f + edges / n * 3f).coerceAtMost(1.5f)
    }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** Lines one under another in a box [width] wide, aligned by [align] (0 left .. 1 right). */
    private fun drawBlock(c: Canvas, b: Block, width: Float, align: Float, color: Int, shadow: Boolean, custom: Boolean) {
        var y = 0f
        for (line in b.lines) {
            val lw = lineWidth(line)
            var x = (width - lw) * align
            val lh = lineHeight(line)
            val baseline = y + lh * 0.5f + line.size * 0.36f
            if (line.logo != Logo.NONE) {
                val z = line.size
                drawLogo(c, line.logo, x, y + lh / 2, z, color, shadow)
                x += logoWidth(line.logo, z) + if (line.runs.isEmpty()) 0f else z * 0.45f
            }
            for ((i, r) in line.runs.withIndex()) {
                r.image?.let { img ->
                    // a wordmark image, in the ink's colour
                    val w = runWidth(r)
                    val h = r.size * 1.05f
                    val top = y + (lh - h) / 2
                    val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                    c.drawBitmap(img, null, RectF(x, top, x + w, top + h), tint)
                    x += w + if (i < line.runs.lastIndex) line.size * 0.5f else 0f
                    continue
                }
                ink.typeface = r.face; ink.textSize = r.size; ink.letterSpacing = r.spacing; ink.textScaleX = r.scaleX
                ink.color = color
                ink.alpha = (255 * if (custom) max(r.alpha, 0.75f) else r.alpha).toInt()
                if (shadow) ink.setShadowLayer(r.size * 0.22f, 0f, r.size * 0.05f, 0x73000000) else ink.clearShadowLayer()
                c.drawText(r.text, x, baseline, ink)
                x += runWidth(r) + if (i < line.runs.lastIndex) line.size * 0.5f else 0f
            }
            y += lh
        }
    }

    private fun logoWidth(logo: Logo, z: Float) = when (logo) { Logo.SPROCKET -> z * 1.35f; Logo.DOT -> z * 0.62f; else -> z }

    /** Our own small marks, [z] tall, left edge at [x], centred on [cy]. */
    private fun drawLogo(c: Canvas, logo: Logo, x: Float, cy: Float, z: Float, color: Int, shadow: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        if (shadow) p.setShadowLayer(z * 0.2f, 0f, z * 0.05f, 0x73000000)
        when (logo) {
            Logo.APERTURE -> {
                // an iris: six blades round a hexagonal opening
                val r = z * 0.46f
                val cx = x + z / 2
                p.style = Paint.Style.STROKE
                p.strokeWidth = z * 0.07f
                c.drawCircle(cx, cy, r, p)
                val inner = r * 0.42f
                for (i in 0 until 6) {
                    val a = Math.toRadians(60.0 * i - 90)
                    val vx = cx + inner * cos(a).toFloat()
                    val vy = cy + inner * sin(a).toFloat()
                    val b = a + Math.toRadians(70.0)
                    c.drawLine(vx, vy, cx + r * cos(b).toFloat(), cy + r * sin(b).toFloat(), p)
                }
            }
            Logo.SPROCKET -> {
                // a frame of film: the picture between two rows of holes
                val w = z * 1.35f
                p.style = Paint.Style.STROKE
                p.strokeWidth = z * 0.07f
                val r = RectF(x, cy - z * 0.45f, x + w, cy + z * 0.45f)
                c.drawRoundRect(r, z * 0.08f, z * 0.08f, p)
                p.style = Paint.Style.FILL
                val hole = z * 0.11f
                for (i in 0 until 5) {
                    val hx = x + w * (0.12f + 0.19f * i)
                    c.drawRect(hx, r.top + z * 0.08f, hx + hole, r.top + z * 0.08f + hole, p)
                    c.drawRect(hx, r.bottom - z * 0.08f - hole, hx + hole, r.bottom - z * 0.08f, p)
                }
            }
            Logo.STAR -> {
                // a four-pointed glint
                val cx = x + z / 2
                val r = z * 0.5f
                val q = r * 0.2f
                c.drawPath(Path().apply {
                    moveTo(cx, cy - r); quadTo(cx + q, cy - q, cx + r, cy); quadTo(cx + q, cy + q, cx, cy + r)
                    quadTo(cx - q, cy + q, cx - r, cy); quadTo(cx - q, cy - q, cx, cy - r); close()
                }, p)
            }
            Logo.DOT -> c.drawCircle(x + z * 0.31f, cy, z * 0.3f, p)
            Logo.NONE -> {}
        }
    }

    // ---- the date stamp ----------------------------------------------------------------------

    private fun drawStamp(c: Canvas, d: FrameDesign, data: Data, photo: RectF, s: Float, avoid: List<RectF> = emptyList()) {
        if (d.stamp == Stamp.OFF) return
        val h = s * 0.036f
        val text = data.stampText
        val w = stampWidth(d.stamp, text, h)
        val m = s * 0.055f
        fun rect(corner: Int): RectF {
            val x = if (corner % 2 == 1) photo.right - m - w else photo.left + m
            val y = if (corner >= 2) photo.bottom - m - h else photo.top + m
            return RectF(x - h * 0.3f, y - h * 0.3f, x + w + h * 0.3f, y + h * 1.3f)
        }
        // the chosen corner, unless the words are there: then the nearest one that is free
        val corner = if (!d.autoPlace || avoid.none { RectF.intersects(it, rect(d.stampCorner)) }) d.stampCorner
            else listOf(d.stampCorner xor 1, d.stampCorner xor 2, d.stampCorner xor 3)
                .firstOrNull { k -> avoid.none { RectF.intersects(it, rect(k)) } } ?: d.stampCorner
        val right = corner % 2 == 1
        val bottom = corner >= 2
        val x = if (right) photo.right - m - w else photo.left + m
        val y = if (bottom) photo.bottom - m - h else photo.top + m
        val color = d.stampColor
        when (d.stamp) {
            Stamp.LED -> {
                // the glow burnt into the film round each lit segment, then the segments
                c.save(); c.translate(x, y); c.skew(-0.1f, 0f); c.translate(h * 0.1f, 0f)
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; alpha = 200; maskFilter = BlurMaskFilter(h * 0.28f, BlurMaskFilter.Blur.NORMAL)
                }
                sevenSegments(c, text, h, glow)
                sevenSegments(c, text, h, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
                sevenSegments(c, text, h, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = mix(color, Color.WHITE, 0.45f); alpha = 170 }, core = true)
                c.restore()
            }
            Stamp.DOTS -> {
                c.save(); c.translate(x, y)
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; alpha = 170; maskFilter = BlurMaskFilter(h * 0.12f, BlurMaskFilter.Blur.NORMAL)
                }
                dotMatrix(c, text, h, glow)
                dotMatrix(c, text, h, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
                c.restore()
            }
            Stamp.PRINT, Stamp.THIN -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = if (d.stamp == Stamp.PRINT) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.create("sans-serif-light", Typeface.NORMAL)
                    textSize = h * 1.25f
                    letterSpacing = if (d.stamp == Stamp.PRINT) 0.06f else 0.12f
                    this.color = color
                }
                if (d.stamp == Stamp.PRINT) {
                    val glow = Paint(p).apply { maskFilter = BlurMaskFilter(h * 0.2f, BlurMaskFilter.Blur.NORMAL); alpha = 190 }
                    c.drawText(text, x, y + h, glow)
                } else p.setShadowLayer(h * 0.25f, 0f, 0f, 0x66000000)
                c.drawText(text, x, y + h, p)
            }
            Stamp.OFF -> {}
        }
    }

    private fun stampWidth(stamp: Stamp, text: String, h: Float): Float = when (stamp) {
        Stamp.LED -> text.sumOf { (if (it == ' ') h * 0.42f else if (it == '\'') h * 0.28f else h * 0.72f).toDouble() }.toFloat() + h * 0.2f
        Stamp.DOTS -> text.sumOf { (if (it == ' ') 3.0 else 6.0) * h / 7.0 }.toFloat()
        else -> Paint().apply {
            typeface = if (stamp == Stamp.PRINT) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = h * 1.25f; letterSpacing = if (stamp == Stamp.PRINT) 0.06f else 0.12f
        }.measureText(text)
    }

    // segments: a top, b upper right, c lower right, d bottom, e lower left, f upper left, g middle
    private val SEGMENTS = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
        '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg",
    )

    private fun sevenSegments(c: Canvas, text: String, h: Float, p: Paint, core: Boolean = false) {
        var x = 0f
        val w = h * 0.56f
        val t = h * 0.13f
        val r = RectF()
        for (ch in text) {
            when (ch) {
                ' ' -> { x += h * 0.42f; continue }
                '\'' -> {
                    r.set(x, 0f, x + t, h * 0.34f)
                    if (core) r.inset(t * 0.3f, t * 0.3f)
                    c.drawRoundRect(r, t / 2, t / 2, p)
                    x += h * 0.28f; continue
                }
            }
            val segs = SEGMENTS[ch] ?: ""
            val mid = h / 2
            for (sg in segs) {
                when (sg) {
                    'a' -> r.set(x + t * 0.7f, 0f, x + w - t * 0.7f, t)
                    'b' -> r.set(x + w - t, t * 0.7f, x + w, mid - t * 0.25f)
                    'c' -> r.set(x + w - t, mid + t * 0.25f, x + w, h - t * 0.7f)
                    'd' -> r.set(x + t * 0.7f, h - t, x + w - t * 0.7f, h)
                    'e' -> r.set(x, mid + t * 0.25f, x + t, h - t * 0.7f)
                    'f' -> r.set(x, t * 0.7f, x + t, mid - t * 0.25f)
                    'g' -> r.set(x + t * 0.7f, mid - t / 2, x + w - t * 0.7f, mid + t / 2)
                }
                if (core) r.inset(t * 0.3f, t * 0.3f)
                c.drawRoundRect(r, t / 2, t / 2, p)
            }
            x += h * 0.72f
        }
    }

    private val DOT_FONT = mapOf(
        '0' to "01110100011001110101110011000101110", '1' to "00100011000010000100001000010001110",
        '2' to "01110100010000100010001000100011111", '3' to "11111000100010000010000011000101110",
        '4' to "00010001100101010010111110001000010", '5' to "11111100001111000001000011000101110",
        '6' to "00110010001000011110100011000101110", '7' to "11111000010001000100010000100001000",
        '8' to "01110100011000101110100011000101110", '9' to "01110100011000101111000010001001100",
        '\'' to "00100001000100000000000000000000000",
    )

    private fun dotMatrix(c: Canvas, text: String, h: Float, p: Paint) {
        val pitch = h / 7f
        var x = 0f
        for (ch in text) {
            if (ch == ' ') { x += pitch * 3; continue }
            val bits = DOT_FONT[ch] ?: continue
            for (i in bits.indices) if (bits[i] == '1') {
                val cx = x + (i % 5) * pitch + pitch / 2
                val cy = (i / 5) * pitch + pitch / 2
                c.drawCircle(cx, cy, pitch * 0.36f, p)
            }
            x += pitch * 6
        }
    }

    // ---- colour ------------------------------------------------------------------------------

    /** A few colours of the photo: the most present ones, and its most vivid. */
    fun palette(photo: Bitmap, n: Int = 5): List<Int> {
        val w = 48
        val h = max(1, 48 * photo.height / max(1, photo.width))
        val small = Bitmap.createScaledBitmap(photo, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== photo) small.recycle()
        val count = IntArray(512); val sr = LongArray(512); val sg = LongArray(512); val sb = LongArray(512)
        for (c in px) {
            val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
            val i = (r shr 5 shl 6) or (g shr 5 shl 3) or (b shr 5)
            count[i]++; sr[i] += r.toLong(); sg[i] += g.toLong(); sb[i] += b.toLong()
        }
        fun colour(i: Int) = Color.rgb((sr[i] / count[i]).toInt(), (sg[i] / count[i]).toInt(), (sb[i] / count[i]).toInt())
        fun distance(a: Int, b: Int): Float {
            val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
            return sqrt((dr * dr + dg * dg + db * db).toFloat())
        }
        val order = (0 until 512).filter { count[it] > 0 }.sortedByDescending { count[it] }
        val chosen = mutableListOf<Int>()
        for (i in order) {
            val c = colour(i)
            if (chosen.all { distance(it, c) > 56f }) chosen += c
            if (chosen.size >= n - 1) break
        }
        // the most vivid colour that is more than a speck
        order.filter { count[it] >= px.size / 100 }.maxByOrNull { saturation(colour(it)) }?.let { i ->
            val c = colour(i)
            if (chosen.all { distance(it, c) > 40f }) chosen += c
        }
        return chosen.take(n)
    }

    private fun saturation(c: Int): Float {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv); return hsv[1] * hsv[2]
    }

    fun luma(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    /** The photo's mean brightness. */
    private fun luma(photo: Bitmap): Float = regionLuma(photo, RectF(0f, 0f, 1f, 1f), RectF(0f, 0f, 1f, 1f))

    /** Mean brightness of the part of [photo] (drawn at [at]) under [box]. */
    private fun regionLuma(photo: Bitmap, at: RectF, box: RectF): Float {
        val x0 = ((box.left - at.left) / at.width()).coerceIn(0f, 1f)
        val x1 = ((box.right - at.left) / at.width()).coerceIn(0f, 1f)
        val y0 = ((box.top - at.top) / at.height()).coerceIn(0f, 1f)
        val y1 = ((box.bottom - at.top) / at.height()).coerceIn(0f, 1f)
        var sum = 0f
        var n = 0
        for (iy in 0 until 6) for (ix in 0 until 6) {
            val fx = x0 + (x1 - x0) * (ix + 0.5f) / 6
            val fy = y0 + (y1 - y0) * (iy + 0.5f) / 6
            val px = (fx * (photo.width - 1)).toInt().coerceIn(0, photo.width - 1)
            val py = (fy * (photo.height - 1)).toInt().coerceIn(0, photo.height - 1)
            sum += luma(photo.getPixel(px, py)); n++
        }
        return if (n == 0) 0.5f else sum / n
    }

    private fun mix(a: Int, b: Int, k: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())

    // ---- the frosted card --------------------------------------------------------------------

    /** The photo [width] pixels across, box-blurred three times. */
    fun blurred(photo: Bitmap, width: Int): Bitmap {
        val w = width
        val h = max(1, (photo.height * w.toFloat() / photo.width).roundToInt())
        val small = Bitmap.createScaledBitmap(photo, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== photo) small.recycle()
        val tmp = IntArray(w * h)
        repeat(3) {
            box(px, tmp, w, h, 4, true)
            box(tmp, px, w, h, 4, false)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val n = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) { val c = at(i); r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff }
            for (i in 0 until len) {
                dst[if (horizontal) line * w + i else i * w + line] = (0xff shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                val add = at(i + radius + 1); val sub = at(i - radius)
                r += ((add shr 16) and 0xff) - ((sub shr 16) and 0xff)
                g += ((add shr 8) and 0xff) - ((sub shr 8) and 0xff)
                b += (add and 0xff) - (sub and 0xff)
            }
        }
    }

    private fun coverRect(bw: Int, bh: Int, w: Int, h: Int): RectF {
        val k = max(w.toFloat() / bw, h.toFloat() / bh)
        val cw = bw * k
        val ch = bh * k
        return RectF((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
