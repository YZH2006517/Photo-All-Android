package org.photoall.film.gallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A border as the user puts it together: the frame round the photo, its colours, which
 * lines of text (and which mark) go on it, how they are arranged and where, and an old
 * camera's date stamp. The watermark is part of the layout, not a line stuck on the end:
 * it can stand upright along an edge, sit in the picture, or split to both ends of a band.
 *
 * Kept as JSON in preferences; the camera's 拍摄时套用 uses the same design.
 */
class FrameDesign {
    enum class Border(val label: String, val kind: Frames.Kind? = null) {
        NONE("无边框"), CUSTOM("自定义"), EVEN("等宽"), BOTTOM("底栏"), SIDE("侧栏"), INSTANT("宝丽来"),
        NEG135("135 负片", Frames.Kind.NEG135), SLIDE135("135 反转", Frames.Kind.SLIDE135),
        HALF135("135 半格", Frames.Kind.HALF135), XPAN("135 宽幅", Frames.Kind.XPAN),
        M645("645", Frames.Kind.MEDIUM645), MEDIUM120("6×6", Frames.Kind.MEDIUM120),
        M67("6×7", Frames.Kind.MEDIUM67), M69("6×9", Frames.Kind.MEDIUM69),
        SHEET45("4×5 页片", Frames.Kind.SHEET45), FILED("底片夹", Frames.Kind.FILED);
        val film get() = kind != null
    }

    /** The card's proportions for a custom border (width : height); 0 is the photo's own. */
    enum class Ratio(val label: String, val w: Float, val h: Float) {
        PHOTO("原比例", 0f, 0f), SQUARE("1:1", 1f, 1f), R45("4:5", 4f, 5f), R34("3:4", 3f, 4f), R23("2:3", 2f, 3f),
        R916("9:16", 9f, 16f), R43("4:3", 4f, 3f), R32("3:2", 3f, 2f), R169("16:9", 16f, 9f), R1911("1.91:1", 1.91f, 1f),
    }

    enum class Item(val label: String) {
        BRAND("品牌"), MODEL("型号"), LENS("焦段"), PARAMS("参数"), FILM("胶片配方"), DATE("日期"), CAPTION("我的话")
    }

    /** How the chosen lines are put together. */
    enum class Arrange(val label: String) {
        STACK("堆叠"),     // one under another
        LINE("单行"),      // all on one line
        ENDS("两端"),      // the name at one end of the band, the data at the other
        DIVIDER("分隔"),   // name | data, as one centred line
    }

    enum class Font(val label: String) { SERIF("衬线"), SANS("无衬线"), MONO("等宽"), LIGHT("细体"), CONDENSED("窄体"), SCRIPT("手写") }

    /** Small marks of our own drawing (no makers' logos: those are their trademarks). */
    enum class Logo(val label: String) { NONE("无"), APERTURE("光圈"), SPROCKET("齿孔"), STAR("星芒"), DOT("圆点") }

    /** Date stamps as old cameras burnt them in. */
    enum class Stamp(val label: String) { OFF("关"), LED("LED 数码"), DOTS("点阵"), PRINT("打印"), THIN("细体") }

    var border = Border.SIDE
    var thickness = 0.5f       // 0..1
    var corner = 0f            // the photo's corner rounding, 0..1
    var shadow = false         // the photo lifted off the card
    var background = WHITE     // a colour, or BLUR
    var ink = AUTO             // a colour, or AUTO (contrast with what is under it)
    val items = linkedSetOf(Item.BRAND, Item.MODEL, Item.LENS, Item.PARAMS)
    var caption = ""
    var inPhoto = false        // on the border, or over the picture
    var anchor = 5             // 3 x 3 grid, row by row: 0 top left .. 5 middle right .. 8 bottom right
    var vertical = true        // text turned 90 degrees, running down the edge
    var arrange = Arrange.ENDS
    var font = Font.SERIF
    var size = 0.5f            // 0..1
    var logo = Logo.NONE
    var stamp = Stamp.OFF
    var stampColor = LED_ORANGE
    var stampCorner = 3        // 0 top left, 1 top right, 2 bottom left, 3 bottom right
    var turn = 0               // film borders: which way the strip runs (clockwise degrees)
    var fog = false            // film borders: base fog on the rebate and the picture's edges
    var leak = 0f              // film borders: light leaked in at the spool end, 0 none .. 1
    var leakSeed = 0           // which leak (换一种 draws another)
    var brandMark = true       // the phone's name set in its maker's own style
    var ratio = Ratio.PHOTO    // custom border: the card's proportions
    var photoScale = 0.84f     // custom border: how much of the card the photo takes, 0.4..1
    var photoAnchor = 4        // custom border: where the photo sits on the card (3 x 3)

    fun copy(): FrameDesign = fromJson(toJson())

    /** Nothing would be added to the photo. */
    val empty get() = border == Border.NONE && items.none { it != Item.CAPTION || caption.isNotBlank() } &&
        logo == Logo.NONE && stamp == Stamp.OFF

    fun toJson(): JSONObject = JSONObject().apply {
        put("border", border.name); put("thickness", thickness.toDouble()); put("corner", corner.toDouble())
        put("shadow", shadow); put("background", background); put("ink", ink)
        put("items", JSONArray(items.map { it.name })); put("caption", caption)
        put("inPhoto", inPhoto); put("anchor", anchor); put("vertical", vertical)
        put("arrange", arrange.name); put("font", font.name); put("size", size.toDouble()); put("logo", logo.name)
        put("stamp", stamp.name); put("stampColor", stampColor); put("stampCorner", stampCorner); put("turn", turn)
        put("fog", fog); put("leak", leak.toDouble()); put("leakSeed", leakSeed); put("brandMark", brandMark)
        put("ratio", ratio.name); put("photoScale", photoScale.toDouble()); put("photoAnchor", photoAnchor)
    }

    companion object {
        const val WHITE = 0xffffffff.toInt()
        const val PAPER = 0xfff4f0e8.toInt()
        const val BLACK = 0xff121212.toInt()
        /** Not colours: a blurred copy of the photo behind it / ink chosen for contrast. */
        const val BLUR = 1
        const val AUTO = 0
        const val LED_ORANGE = 0xffff8a1e.toInt()
        val STAMP_COLOURS = intArrayOf(LED_ORANGE, 0xffff3b2f.toInt(), 0xffffd23f.toInt(), 0xfff4f1e8.toInt())

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(fallback)

        fun fromJson(j: JSONObject): FrameDesign = FrameDesign().apply {
            border = enumOr(j.optString("border"), border)
            thickness = j.optDouble("thickness", thickness.toDouble()).toFloat()
            corner = j.optDouble("corner", corner.toDouble()).toFloat()
            shadow = j.optBoolean("shadow", shadow)
            background = j.optInt("background", background)
            ink = j.optInt("ink", ink)
            j.optJSONArray("items")?.let { a ->
                items.clear()
                for (i in 0 until a.length()) runCatching { items += Item.valueOf(a.getString(i)) }
            }
            caption = j.optString("caption", caption)
            inPhoto = j.optBoolean("inPhoto", inPhoto)
            anchor = j.optInt("anchor", anchor).coerceIn(0, 8)
            vertical = j.optBoolean("vertical", vertical)
            arrange = enumOr(j.optString("arrange"), arrange)
            font = enumOr(j.optString("font"), font)
            size = j.optDouble("size", size.toDouble()).toFloat()
            logo = enumOr(j.optString("logo"), logo)
            stamp = enumOr(j.optString("stamp"), stamp)
            stampColor = j.optInt("stampColor", stampColor)
            stampCorner = j.optInt("stampCorner", stampCorner).coerceIn(0, 3)
            turn = j.optInt("turn", 0)
            fog = j.optBoolean("fog", fog)
            leak = j.optDouble("leak", leak.toDouble()).toFloat()
            leakSeed = j.optInt("leakSeed", 0)
            brandMark = j.optBoolean("brandMark", brandMark)
            ratio = enumOr(j.optString("ratio"), ratio)
            photoScale = j.optDouble("photoScale", photoScale.toDouble()).toFloat()
            photoAnchor = j.optInt("photoAnchor", photoAnchor).coerceIn(0, 8)
        }

        private fun prefs(context: Context) = context.getSharedPreferences("frames", Context.MODE_PRIVATE)

        fun load(context: Context): FrameDesign =
            prefs(context).getString("design", null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
                ?: presets[0].build(emptyList())

        fun save(context: Context, design: FrameDesign) =
            prefs(context).edit().putString("design", design.toJson().toString()).apply()

        /** A starting point: sets everything but the user's own words. */
        class Preset(val name: String, private val setup: FrameDesign.(palette: List<Int>) -> Unit) {
            fun build(palette: List<Int>, caption: String = ""): FrameDesign =
                FrameDesign().apply { items.clear(); this.caption = caption; setup(palette) }
        }

        val presets = listOf(
            // film: the stock's own edge, in each format
            Preset("135 负片") { border = Border.NEG135 },
            Preset("135 反转") { border = Border.SLIDE135 },
            Preset("135 半格") { border = Border.HALF135 },
            Preset("135 宽幅") { border = Border.XPAN },
            Preset("645") { border = Border.M645 },
            Preset("6×6") { border = Border.MEDIUM120 },
            Preset("6×7") { border = Border.M67 },
            Preset("6×9") { border = Border.M69 },
            Preset("4×5 页片") { border = Border.SHEET45 },
            Preset("底片夹") { border = Border.FILED },
            // cards: the exposure, not the phone
            Preset("杂志竖排") {
                border = Border.SIDE; thickness = 0.5f; background = WHITE
                items += listOf(Item.LENS, Item.PARAMS)
                vertical = true; arrange = Arrange.ENDS; anchor = 5; font = Font.SERIF
            },
            Preset("经典底栏") {
                border = Border.BOTTOM; thickness = 0.45f; background = WHITE
                items += listOf(Item.LENS, Item.PARAMS)
                vertical = false; arrange = Arrange.ENDS; anchor = 7; font = Font.SANS
            },
            Preset("居中签名") {
                border = Border.BOTTOM; thickness = 0.55f; background = PAPER
                items += listOf(Item.PARAMS)
                vertical = false; arrange = Arrange.DIVIDER; anchor = 7; font = Font.SERIF; logo = Logo.APERTURE
            },
            Preset("4:5 留白") {
                border = Border.CUSTOM; ratio = Ratio.R45; photoScale = 0.86f; photoAnchor = 1; background = WHITE
                items += listOf(Item.PARAMS)
                vertical = false; arrange = Arrange.STACK; anchor = 7; font = Font.MONO; size = 0.35f
            },
            Preset("画内角标") {
                border = Border.NONE; inPhoto = true; ink = WHITE
                items += listOf(Item.PARAMS)
                vertical = false; arrange = Arrange.STACK; anchor = 6; font = Font.SANS; size = 0.4f
            },
            Preset("暗夜海报") {
                border = Border.EVEN; thickness = 0.75f; background = BLACK
                items += listOf(Item.PARAMS, Item.DATE)
                vertical = true; arrange = Arrange.ENDS; anchor = 3; font = Font.CONDENSED; logo = Logo.STAR
            },
            Preset("照片取色") { palette ->
                border = Border.EVEN; thickness = 0.6f; background = palette.firstOrNull() ?: PAPER
                items += listOf(Item.PARAMS)
                vertical = false; arrange = Arrange.STACK; anchor = 7; font = Font.SANS
            },
            Preset("毛玻璃") {
                border = Border.EVEN; thickness = 0.6f; background = BLUR; corner = 0.6f; shadow = true; ink = WHITE
                items += listOf(Item.LENS, Item.PARAMS)
                vertical = false; arrange = Arrange.ENDS; anchor = 7; font = Font.LIGHT
            },
            Preset("老相机") {
                border = Border.NONE; stamp = Stamp.LED; stampColor = LED_ORANGE; stampCorner = 3
            },
            Preset("宝丽来") {
                border = Border.INSTANT; thickness = 0.5f; background = PAPER
                items += listOf(Item.CAPTION, Item.DATE)
                vertical = false; arrange = Arrange.STACK; anchor = 7; font = Font.SCRIPT
            },
        )
    }
}
