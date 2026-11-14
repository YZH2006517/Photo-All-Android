package org.photoall.film.settings

import android.content.Context
import org.photoall.film.engine.FilmEngine

/** One adjustable value. Film parameters use filmsim's names; camera ones start with "app.". */
sealed class Setting(val key: String, val label: String, val group: String) {
    abstract val default: Float

    class Slider(
        key: String, label: String, group: String,
        val min: Float, val max: Float, override val default: Float,
        val integer: Boolean = false, val format: String = "%.2f",
    ) : Setting(key, label, group)

    class Toggle(key: String, label: String, group: String, on: Boolean) : Setting(key, label, group) {
        override val default = if (on) 1f else 0f
    }

    class Choice(key: String, label: String, group: String, val options: List<String>, index: Int) :
        Setting(key, label, group) {
        override val default = index.toFloat()
    }
}

/** Every setting, in panel order. Film ranges follow filmsim's params.ui. */
object Settings {
    const val RAW = "app.raw"
    const val RAW_EV = "app.rawev"
    const val KEEP_RAW = "app.keepraw"
    const val FINDER_SOFT = "app.finder.soft" // viewfinder: how much softer than the camera's picture
    const val FINDER_SAT = "app.finder.sat"   // viewfinder: saturation, 1 = as the camera gives it
    const val FINDER_EV = "app.finder.ev"     // viewfinder only: brighter or darker to look at, the photo unchanged
    const val FINDER_GRAIN = "app.finder.grain" // viewfinder only: film grain over the live picture
    const val FINDER_LUT = "app.finder.lut"   // viewfinder: show the film simulation (off: the camera's own picture)
    const val REEL_ON = "app.reel.on"         // 拍摄录屏: off hides every record button
    const val REEL_BEFORE = "app.reel.before" // 拍摄录屏: seconds kept from before the shutter
    const val REEL_AFTER = "app.reel.after"   // and recorded after it
    const val PRIVATE_PHOTOS = "app.privatephotos" // photos only in the app's own gallery
    const val SKIN = "app.skin"       // ui.Skin: 0 classic (the theme's), 1 sacred, 2 camera body, 3 anime
    const val THEME = "app.theme"     // index into ui.Style.palettes
    const val HAPTICS = "app.haptics" // 0 off, 1 light, 2 standard
    const val FRAME = "app.frame"           // index into gallery.Frames.Kind
    const val FRAME_TEXT = "app.frametext"  // 0 the film's name on the edge, 1 nothing
    const val FRAME_DATE = "app.framedate"  // a date-back stamp
    const val FRAME_AUTO = "app.frameauto"  // every shot also saved with the border
    const val FRAME_INFO = "app.frameinfo"  // white borders: the shooting data
    const val FRAME_DEVICE = "app.framedevice" // white borders: the phone's make and model
    const val FRAME_RECIPE = "app.framerecipe" // white borders: film, paper and LUT
    const val HDR = "app.hdr"
    const val HDR_FRAMES = "app.hdrframes"
    const val HDR_UNDER = "app.hdrunder"
    const val HDR_LONG = "app.hdrlong"
    const val HDR_TONE = "app.hdrtone"
    const val FLASH = "app.flash"
    const val FLASH_UNDER = "app.flashunder"
    const val FLASH_FILL = "app.flashfill" // the steady fill light's share of the LED's strength
    const val FLASH_WHITE = "app.flashwhite" // 0 per area (auto), 1 the flash's (daylight), 2 the room's
    /**
     * 0 off; 1 a real flash in one frame; 2 a steady fill light (the LED lit as a torch
     * while the mode is on); 3 overpower: an ambient frame plus a flash frame, mixed.
     */
    val flashModes = listOf("关", "闪光", "补光", "压光")
    const val FLASH_STEADY = 2
    const val FLASH_OVERPOWER = 3
    const val CHROMA_NR = "app.chromanr"
    const val RAW_DENOISE = "app.rawdenoise"
    const val LONG_EDGE = "app.longedge"
    const val JPEG_QUALITY = "app.jpegq"
    const val MANUAL = "app.manual"
    const val ISO = "app.iso"
    const val SHUTTER = "app.shutter"
    const val WHITE_BALANCE = "app.wb"
    const val BALANCE = "app.balance" // 0 film colour (reference filter pack), 1 auto neutral, 2 manual

    const val SOFT_3A = "app.soft3a"
    const val METERING = "app.metering"
    const val HIGHLIGHT = "app.highlight"
    const val FLICKER = "app.flicker"
    const val FILM_ISO = "app.filmiso"
    const val FOCUS = "app.focus"            // 0 continuous AF, 1 tap-and-lock AF, 2 manual
    const val FOCUS_DISTANCE = "app.focusdist" // manual: 0 = infinity .. 1 = closest
    const val PEAKING = "app.peaking"
    val focusModes = listOf("AF-C", "AF-L", "MF")
    const val GUIDES = "app.guides"
    const val HISTOGRAM = "app.histogram"
    const val LUT = "app.lut"                 // LutLibrary index, 0 = none
    const val LUT_ON = "app.luton"            // off: keep the chosen LUT but skip it
    const val FILM_ON = "app.filmon"          // off: no film simulation at all
    const val PRINT_ON = "app.printon"        // off: the film scanned directly (negatives inverted)
    const val LUT_STRENGTH = "app.lutstrength"
    val guideNames = listOf("关", "三分线", "黄金比例", "对角线", "中心")

    val longEdges = listOf(2048, 3072, 4096, 8192)
    val whiteBalances = listOf("自动", "按胶片", "日光", "阴天", "白炽灯", "荧光灯", "阴影")
    /** Fixed colour temperature for a white balance choice; null = automatic. */
    fun whiteBalanceKelvin(choice: Int, film: String): Double? = when (choice) {
        1 -> if (Regex("\\d+t\\b").containsMatchIn(film)) 3200.0 else 5500.0 // tungsten stocks end in "t"
        2 -> 5500.0
        3 -> 6500.0
        4 -> 3200.0
        5 -> 4000.0
        6 -> 7500.0
        else -> null
    }
    /** Box speed from the stock name, doubled per push ("portra 800 push 1" -> 1600). */
    fun filmIso(film: String): Int {
        val speed = Regex("(\\d{2,4})(?:[dt]\\b)?").findAll(film).map { it.groupValues[1].toInt() }
            .firstOrNull { it in 25..3200 } ?: 400
        val push = Regex("push (\\d)").find(film)?.groupValues?.get(1)?.toInt() ?: 0
        return speed shl push
    }
    // 1/8000 s .. 1 s in third stops for the shutter slider
    fun shutterSeconds(v: Float): Double = Math.pow(2.0, v.toDouble() / 3.0) / 8000.0
    /** The marking a camera's dial shows for a third-stop step (1/60, not 1/63). */
    fun shutterLabel(v: Float): String = NOMINAL_SHUTTER.getOrNull(Math.round(v)) ?: secondsLabel(shutterSeconds(v))
    private val NOMINAL_SHUTTER = listOf(
        "1/8000", "1/6400", "1/5000", "1/4000", "1/3200", "1/2500", "1/2000", "1/1600", "1/1250", "1/1000",
        "1/800", "1/640", "1/500", "1/400", "1/320", "1/250", "1/200", "1/160", "1/125", "1/100",
        "1/80", "1/60", "1/50", "1/40", "1/30", "1/25", "1/20", "1/15", "1/13", "1/10",
        "1/8", "1/6", "1/5", "1/4", "0.3″", "0.4″", "0.5″", "0.6″", "0.8″", "1″")
    fun secondsLabel(s: Double): String = if (s >= 0.3) "%.1f s".format(s) else "1/%d".format(Math.round(1 / s))
    /** ISO for a third-stop step: the standard markings (320, not 317), as sent to the sensor. */
    fun iso(v: Float): Int = NOMINAL_ISO.getOrNull(Math.round(v)) ?: Math.round(100 * Math.pow(2.0, v.toDouble() / 3.0)).toInt()
    private val NOMINAL_ISO = listOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)

    val all: List<Setting> by lazy {
        val films = FilmEngine.filmStocks
        val papers = FilmEngine.papers
        listOf(
            Setting.Choice(THEME, "主题", "界面", listOf("暗盒（暖黑 · 片盒黄）", "暗房红灯（夜拍，只用红光）",
                "相纸（白天户外，浅色）", "黄铜（旁轴蒙皮与黄铜）", "冷银（冷灰 · 冰蓝）") + org.photoall.film.ui.Skin.names.drop(1), 0),
            Setting.Choice(HAPTICS, "震动反馈（快门、拨盘、对焦确认）", "界面", listOf("关", "轻", "标准"), 2),
            Setting.Choice(FRAME, "边框样式", "边框", org.photoall.film.gallery.Frames.Kind.entries.map { it.label }, 1),
            Setting.Choice(FRAME_TEXT, "片边文字", "边框", listOf("跟随胶片", "不印"), 0),
            Setting.Toggle(FRAME_DATE, "日期印（橙色数码）", "边框", false),
            Setting.Toggle(FRAME_AUTO, "拍摄时另存一张带边框的照片（原图照常保存）", "", false),
            Setting.Toggle(FRAME_INFO, "白边印拍摄信息（光圈 · 快门 · ISO · 焦距）", "边框", false),
            Setting.Toggle(FRAME_DEVICE, "白边印手机型号", "边框", false),
            Setting.Toggle(FRAME_RECIPE, "白边印胶片配方（胶卷 · 相纸 · LUT）", "边框", false),
            Setting.Toggle(RAW, "RAW（DNG）拍摄并从 RAW 冲洗", "相机", true),
            Setting.Slider(FINDER_EV, "取景画面曝光（只调取景显示，不影响照片）", "取景", -2f, 2f, 0f, format = "%+.1f EV"),
            Setting.Toggle(FINDER_LUT, "取景画面套用胶片模拟", "取景", false),
            Setting.Slider(FINDER_SOFT, "取景画面柔化（降低锐度，0 = 原样）", "取景", 0f, 1f, 0.5f),
            Setting.Slider(FINDER_SAT, "取景画面饱和度（1 = 原样）", "取景", 0f, 1f, 0.7f),
            Setting.Slider(FINDER_GRAIN, "取景画面颗粒感（只调取景显示，0 = 无）", "取景", 0f, 1f, 0f),
            Setting.Toggle(REEL_ON, "开启拍摄录屏（关闭时隐藏所有录屏按钮）", "拍摄录屏", false),
            Setting.Slider(REEL_BEFORE, "拍摄录屏：保留按快门前的秒数", "拍摄录屏", 0f, 20f, 10f, integer = true, format = "%.0f 秒"),
            Setting.Slider(REEL_AFTER, "拍摄录屏：按快门后继续录的秒数", "拍摄录屏", 1f, 20f, 10f, integer = true, format = "%.0f 秒"),
            Setting.Toggle(KEEP_RAW, "保存 RAW 格式（DNG 原片，可重新冲洗；打开会同时开启上面的 RAW 拍摄）", "相机", false),
            Setting.Toggle(PRIVATE_PHOTOS, "照片只存在本应用相册（不出现在手机相册；卸载或清除应用数据会一起删除）", "相机", false),
            Setting.Slider(RAW_EV, "RAW 冲洗曝光 EV", "相机", -3f, 3f, 0f),
            Setting.Toggle(HDR, "HDR+ 多帧合成（实验 · 需 RAW · 部分机型可能不兼容）", "相机", false),
            Setting.Slider(HDR_FRAMES, "HDR 短曝光帧数（加长曝光共最多 6 帧）", "相机", 2f, 6f, 5f, integer = true, format = "%.0f"),
            Setting.Slider(HDR_UNDER, "HDR 最多欠曝档数（按取景器高光自动决定，不超过这个值）", "相机", 0f, 3f, 2f, format = "%.1f"),
            Setting.Slider(HDR_LONG, "HDR 包围长曝光：比短曝光亮几档（暗部细节，0 = 不拍）", "相机", 0f, 4f, 2f, format = "%.1f"),
            Setting.Slider(HDR_TONE, "HDR 影调：提亮暗部、收住高光（曝光融合，0 = 关）", "相机", 0f, 1f, 0.5f, format = "%.2f"),
            Setting.Choice(FLASH, "闪光", "",
                listOf("关", "闪光（单张，真闪光）", "补光（闪光灯常亮）", "压光（两帧合成，需 RAW）"), 0),
            Setting.Slider(FLASH_UNDER, "压光：环境光压暗档数", "闪光", 0.5f, 4f, 2f, format = "%.1f"),
            Setting.Slider(FLASH_FILL, "补光亮度（常亮档位，部分机型不可调）", "闪光", 0.1f, 1f, 1f, format = "%.2f"),
            Setting.Choice(FLASH_WHITE, "闪光白平衡", "闪光",
                listOf("自动（闪光照到的地方按闪光，其余按室内光）", "统一按闪光（日光）", "统一按室内光"), 0),
            Setting.Slider(CHROMA_NR, "RAW 彩色降噪（只平滑颜色，不动亮度细节；0 = 关）", "相机", 0f, 1f, 1f, format = "%.2f"),
            Setting.Slider(RAW_DENOISE, "RAW 降噪（vkdt 小波降噪，去马赛克前按传感器噪声模型；0 = 关）", "相机", 0f, 0.6f, 0.3f, format = "%.2f"),
            Setting.Choice(LONG_EDGE, "冲洗尺寸（长边）", "相机", longEdges.map { if (it >= 8192) "原尺寸" else "$it" }, 2),
            Setting.Slider(JPEG_QUALITY, "JPEG 质量", "相机", 70f, 100f, 95f, integer = true, format = "%.0f"),
            Setting.Toggle(SOFT_3A, "胶片测光规则（手机测光引擎 + 测光模式/高光保护/胶片 ISO/标定白平衡）", "", false),
            Setting.Choice(METERING, "测光模式", "相机", listOf("手机智能测光", "中央重点", "点测光（跟随点按）"), 0),
            Setting.Toggle(HIGHLIGHT, "高光保护", "", false),
            Setting.Choice(FLICKER, "防频闪", "相机", listOf("50 Hz", "60 Hz", "关"), 0),
            Setting.Toggle(FILM_ISO, "按胶片 ISO 曝光", "", false),
            Setting.Choice(GUIDES, "取景参考线", "", guideNames, 1),
            Setting.Toggle(HISTOGRAM, "实时直方图（按胶片效果）", "", true),
            Setting.Choice(FOCUS, "对焦", "相机", listOf("连续自动对焦 AF-C", "点按锁定 AF-L", "手动对焦 MF"), 0),
            Setting.Slider(FOCUS_DISTANCE, "手动对焦距离（0 = 无穷远）", "相机", 0f, 1f, 0f),
            Setting.Toggle(PEAKING, "手动对焦时显示峰值", "", true),
            Setting.Toggle(MANUAL, "手动曝光", "相机", false),
            Setting.Slider(ISO, "ISO", "相机", 0f, 18f, 3f, integer = true),
            Setting.Slider(SHUTTER, "快门", "相机", 0f, 39f, 15f, integer = true),
            Setting.Choice(WHITE_BALANCE, "白平衡", "相机", whiteBalances, 0),

            // Chosen from the camera screen's selector (hidden here: 348 entries).
            // Hidden here (group ""): set on the camera screen, in its shooting sheet, or by the
            // film card and the LUT list. They keep their values and defaults.
            Setting.Slider(LUT, "LUT", "", 0f, 1000f, 0f, integer = true),
            Setting.Toggle(LUT_ON, "使用 LUT", "", true),
            Setting.Slider(LUT_STRENGTH, "LUT 强度", "", 0f, 1f, 0.8f),

            Setting.Toggle(FILM_ON, "胶片模拟（关闭则直出，只保留 LUT）", "", true),
            Setting.Choice("film", "胶片", "", films, 4),
            Setting.Slider("ev film", "胶片曝光 EV", "胶片", -5f, 5f, 0f),
            Setting.Slider("g film", "胶片反差", "胶片", 0.5f, 1.5f, 1f),
            Setting.Slider("f base", "片基密度", "胶片", 0f, 1.5f, 1f),
            Setting.Slider("g fast", "快感光层反差", "胶片", 0.5f, 1.5f, 1f),
            Setting.Slider("g slow", "慢感光层反差", "胶片", 0.5f, 1.5f, 1f),
            Setting.Slider("exhaust", "显影液衰竭", "胶片", 0f, 1f, 0f),
            Setting.Slider("hl boost", "高光增强", "胶片", 0f, 10f, 0f),

            Setting.Toggle(PRINT_ON, "印放到相纸（关闭则直接扫描底片）", "", true),
            Setting.Choice("paper", "相纸", "", papers, 3),
            Setting.Slider("ev paper", "印放曝光 EV", "冲印", -5f, 5f, 0f),
            Setting.Slider("g paper", "相纸反差", "冲印", 0.5f, 1.5f, 1f),
            Setting.Slider("p base", "相纸片基", "冲印", 0f, 1.5f, 1f),
            Setting.Slider("g fast p", "相纸快层反差", "冲印", 0.5f, 1.5f, 1f),
            Setting.Slider("g slow p", "相纸慢层反差", "冲印", 0.5f, 1.5f, 1f),
            Setting.Slider("p exh", "相纸药液衰竭", "冲印", 0f, 1f, 0f),
            Setting.Slider("glare", "眩光", "冲印", 0f, 0.5f, 0f),

            Setting.Choice(BALANCE, "滤色方式", "放大机滤色",
                listOf("保留胶片本色（统一滤色）", "自动中性（逐卷校正）", "手动"), 1),
            Setting.Slider("filter c", "青滤色（手动）", "放大机滤色", 0f, 1f, 0f),
            Setting.Slider("filter m", "品滤色（手动）", "放大机滤色", 0f, 1f, 0f),
            Setting.Slider("filter y", "黄滤色（手动）", "放大机滤色", 0f, 1f, 0f),
            Setting.Slider("tune m", "品红微调", "放大机滤色", -1f, 1f, 0f),
            Setting.Slider("tune y", "黄微调", "放大机滤色", -1f, 1f, 0f),

            Setting.Toggle("preflash", "预闪光", "预闪", false),
            Setting.Slider("pf ev", "预闪曝光 EV", "预闪", -10f, -3f, -10f),
            Setting.Slider("pf m", "预闪品红", "预闪", -1f, 1f, 0f),
            Setting.Slider("pf y", "预闪黄", "预闪", -1f, 1f, 0f),

            Setting.Toggle("couplers", "DIR 耦合剂", "耦合剂", true),
            Setting.Slider("cp amt", "耦合剂强度", "耦合剂", 0f, 1f, 0.5f),
            Setting.Slider("lang r", "红层抑制", "耦合剂", 0.01f, 2f, 1f),
            Setting.Slider("lang g", "绿层抑制", "耦合剂", 0.01f, 2f, 1f),
            Setting.Slider("lang b", "蓝层抑制", "耦合剂", 0.01f, 2f, 1f),
            Setting.Slider("cp rad", "扩散半径 µm", "耦合剂", 0f, 200f, 20f, format = "%.0f"),

            Setting.Toggle("halation", "光晕", "光晕", true),
            Setting.Slider("radius", "光晕半径 µm", "光晕", 0f, 200f, 65f, format = "%.0f"),
            Setting.Slider("hal amt", "光晕强度", "光晕", 0f, 10f, 1f),
            Setting.Slider("hal mids", "中间调保护", "光晕", 0f, 1f, 0f),
            Setting.Slider("hal bnc", "反射次数", "光晕", 1f, 6f, 3f, integer = true, format = "%.0f"),
            Setting.Slider("hal dec", "反射衰减", "光晕", 0f, 1f, 0.5f),
            Setting.Slider("scat amt", "乳剂散射", "光晕", 0f, 1f, 0.2f),
            Setting.Slider("strength r", "红通道", "光晕", 0f, 2f, 1f),
            Setting.Slider("strength g", "绿通道", "光晕", 0f, 2f, 1f),
            Setting.Slider("strength b", "蓝通道", "光晕", 0f, 2f, 1f),

            Setting.Toggle("grain", "颗粒", "颗粒", true),
            Setting.Slider("size", "颗粒大小", "颗粒", 0.01f, 2.5f, 1f),
            Setting.Slider("uniform", "颗粒均匀度", "颗粒", 0f, 1f, 1f),

            Setting.Slider("scan ill", "扫描光源 K", "光源", 2000f, 10000f, 5000f, format = "%.0f"),
            Setting.Slider("scne ill", "场景光源 K（0=不校正）", "光源", 0f, 10000f, 0f, format = "%.0f"),
            Setting.Slider("tint", "色调", "光源", -1f, 1f, 0f),
            Setting.Slider("film ill", "胶片平衡光源 K（0=默认）", "光源", 0f, 10000f, 0f, format = "%.0f"),
        )
    }

    val byKey: Map<String, Setting> by lazy { all.associateBy { it.key } }
}

/** Values for the settings, wherever they are kept (the device's, or an import's own copy). */
interface SettingValues {
    operator fun get(key: String): Float
    operator fun set(key: String, value: Float)
    fun flag(key: String) = get(key) >= 0.5f
    /** Every setting back to its default. */
    fun resetAll()

    /** filmsim parameters for the engine, with the enlarger filter mode resolved. */
    fun filmParams(): Map<String, Float> {
        val params = Settings.all.filter { !it.key.startsWith("app.") }.associate { it.key to get(it.key) }.toMutableMap()
        // Engine sentinels: -2 every stock under the reference (Portra 400) filter pack,
        // -1 each stock balanced to neutral. Manual keeps the sliders.
        when (get(Settings.BALANCE).toInt()) {
            0 -> params["filter c"] = -2f
            1 -> params["filter c"] = -1f
        }
        // filmsim treats illuminants below its slider range as "off"
        for (key in listOf("scne ill", "film ill")) if (params.getValue(key) < 2000f) params[key] = 0f
        // Which stages run (read by the engine bridge, not filmsim parameters).
        params["stage film"] = if (flag(Settings.FILM_ON)) 1f else 0f
        params["stage print"] = if (flag(Settings.PRINT_ON)) 1f else 0f
        return params
    }
}

/** Current values, persisted on the device. */
class SettingsStore(context: Context) : SettingValues {
    private val prefs = context.getSharedPreferences("film-settings", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<(String) -> Unit>()

    init {
        // Schema 2: system 3A by default, RAW capture developed to JPG without keeping the DNG.
        // Earlier builds may have stored other choices for these; reset them once.
        if (prefs.getInt(SCHEMA, 1) < 2)
            prefs.edit().remove(Settings.SOFT_3A).remove(Settings.RAW).remove(Settings.KEEP_RAW).putInt(SCHEMA, 2).apply()
        // Schema 3: the "scan negative" process choice became the print switch.
        if (prefs.getInt(SCHEMA, 1) < 3) {
            val scanned = prefs.getFloat("process", 0f) >= 0.5f
            prefs.edit().remove("process").apply { if (scanned) putFloat(Settings.PRINT_ON, 0f) }.putInt(SCHEMA, 3).apply()
        }
        if (prefs.getInt(SCHEMA, 1) < 4) { // a single-frame flash mode came in at index 1
            val flash = prefs.getFloat(Settings.FLASH, 0f)
            prefs.edit().apply { if (flash >= 1f) putFloat(Settings.FLASH, flash + 1f) }.putInt(SCHEMA, 4).apply()
        }
        if (prefs.getInt(SCHEMA, 1) < 5) { // balanced went; fill became a steady light; overpower moved to 3
            val flash = prefs.getFloat(Settings.FLASH, 0f).toInt()
            prefs.edit().apply {
                when (flash) {
                    3 -> putFloat(Settings.FLASH, 0f)
                    4 -> putFloat(Settings.FLASH, 3f)
                }
            }.putInt(SCHEMA, 5).apply()
        }
        if (prefs.getInt(SCHEMA, 1) < 6) { // the HDR tone step became exposure fusion: start from its default
            prefs.edit().remove(Settings.HDR_TONE).putInt(SCHEMA, 6).apply()
        }
        if (prefs.getInt(SCHEMA, 1) < 7) { // autofocus and auto exposure by default, whatever was left set
            prefs.edit().remove(Settings.MANUAL).remove(Settings.FOCUS).putInt(SCHEMA, 7).apply()
        }
        if (prefs.getInt(SCHEMA, 1) < 8) { // HDR+ only by choice in the settings, after its warning: off for everyone
            prefs.edit().putFloat(Settings.HDR, 0f).putInt(SCHEMA, 8).apply()
        }
    }

    override operator fun get(key: String): Float = prefs.getFloat(key, Settings.byKey.getValue(key).default)

    override operator fun set(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
        listeners.forEach { it(key) }
        // keeping the DNG needs RAW capture, and without it there is none to keep
        if (key == Settings.KEEP_RAW && value >= 0.5f && !flag(Settings.RAW)) set(Settings.RAW, 1f)
        if (key == Settings.RAW && value < 0.5f && flag(Settings.KEEP_RAW)) set(Settings.KEEP_RAW, 0f)
    }

    override fun flag(key: String) = get(key) >= 0.5f

    override fun resetAll() {
        prefs.edit().clear().apply()
        listeners.forEach { it("") }
    }

    fun onChange(listener: (String) -> Unit) { listeners += listener }

    val longEdge: Int get() = Settings.longEdges[get(Settings.LONG_EDGE).toInt().coerceIn(0, Settings.longEdges.lastIndex)]

    private companion object {
        const val SCHEMA = "schema"
    }
}
