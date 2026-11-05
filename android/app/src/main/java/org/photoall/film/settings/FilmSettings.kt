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
    const val HDR = "app.hdr"
    const val HDR_FRAMES = "app.hdrframes"
    const val HDR_UNDER = "app.hdrunder"
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
    fun shutterLabel(v: Float): String = secondsLabel(shutterSeconds(v))
    fun secondsLabel(s: Double): String = if (s >= 0.3) "%.1f s".format(s) else "1/%d".format(Math.round(1 / s))
    fun iso(v: Float): Int = Math.round(100 * Math.pow(2.0, v.toDouble() / 3.0)).toInt()

    val all: List<Setting> by lazy {
        val films = FilmEngine.filmStocks
        val papers = FilmEngine.papers
        listOf(
            Setting.Toggle(RAW, "RAW（DNG）拍摄并从 RAW 冲洗", "相机", true),
            Setting.Toggle(KEEP_RAW, "冲洗后保留 RAW 原片（关闭则只留 JPG）", "相机", false),
            Setting.Slider(RAW_EV, "RAW 冲洗曝光 EV", "相机", -3f, 3f, 0f),
            Setting.Toggle(HDR, "HDR 连拍合成（需 RAW）", "相机", false),
            Setting.Slider(HDR_FRAMES, "HDR 帧数", "相机", 2f, 8f, 5f, integer = true, format = "%.0f"),
            Setting.Slider(HDR_UNDER, "HDR 欠曝档数（保护高光）", "相机", 0f, 3f, 1.7f, format = "%.1f"),
            Setting.Choice(LONG_EDGE, "冲洗尺寸（长边）", "相机", longEdges.map { if (it >= 8192) "原尺寸" else "$it" }, 2),
            Setting.Slider(JPEG_QUALITY, "JPEG 质量", "相机", 70f, 100f, 95f, integer = true, format = "%.0f"),
            Setting.Toggle(SOFT_3A, "胶片测光规则（手机测光引擎 + 测光模式/高光保护/胶片 ISO/标定白平衡）", "相机", false),
            Setting.Choice(METERING, "测光模式", "相机", listOf("手机智能测光", "中央重点", "点测光（跟随点按）"), 0),
            Setting.Toggle(HIGHLIGHT, "高光保护", "相机", false),
            Setting.Choice(FLICKER, "防频闪", "相机", listOf("50 Hz", "60 Hz", "关"), 0),
            Setting.Toggle(FILM_ISO, "按胶片 ISO 曝光", "相机", false),
            Setting.Choice(GUIDES, "取景参考线", "相机", guideNames, 1),
            Setting.Toggle(HISTOGRAM, "实时直方图（按胶片效果）", "相机", true),
            Setting.Choice(FOCUS, "对焦", "相机", listOf("连续自动对焦 AF-C", "点按锁定 AF-L", "手动对焦 MF"), 0),
            Setting.Slider(FOCUS_DISTANCE, "手动对焦距离（0 = 无穷远）", "相机", 0f, 1f, 0f),
            Setting.Toggle(PEAKING, "手动对焦时显示峰值", "相机", true),
            Setting.Toggle(MANUAL, "手动曝光", "相机", false),
            Setting.Slider(ISO, "ISO", "相机", 0f, 18f, 3f, integer = true),
            Setting.Slider(SHUTTER, "快门", "相机", 0f, 39f, 15f, integer = true),
            Setting.Choice(WHITE_BALANCE, "白平衡", "相机", whiteBalances, 0),

            // Chosen from the camera screen's selector (hidden here: 348 entries).
            Setting.Slider(LUT, "LUT", "", 0f, 1000f, 0f, integer = true),
            Setting.Toggle(LUT_ON, "使用 LUT", "LUT（在胶片之后）", true),
            Setting.Slider(LUT_STRENGTH, "LUT 强度", "LUT（在胶片之后）", 0f, 1f, 0.8f),

            Setting.Toggle(FILM_ON, "胶片模拟（关闭则直出，只保留 LUT）", "胶片", true),
            Setting.Choice("film", "胶片", "胶片", films, 4),
            Setting.Slider("ev film", "胶片曝光 EV", "胶片", -5f, 5f, 0f),
            Setting.Slider("g film", "胶片反差", "胶片", 0.5f, 1.5f, 1f),
            Setting.Slider("f base", "片基密度", "胶片", 0f, 1.5f, 1f),
            Setting.Slider("g fast", "快感光层反差", "胶片", 0.5f, 1.5f, 1f),
            Setting.Slider("g slow", "慢感光层反差", "胶片", 0.5f, 1.5f, 1f),
            Setting.Slider("exhaust", "显影液衰竭", "胶片", 0f, 1f, 0f),
            Setting.Slider("hl boost", "高光增强", "胶片", 0f, 10f, 0f),

            Setting.Toggle(PRINT_ON, "印放到相纸（关闭则直接扫描底片）", "冲印", true),
            Setting.Choice("paper", "相纸", "冲印", papers, 3),
            Setting.Slider("ev paper", "印放曝光 EV", "冲印", -5f, 5f, 0f),
            Setting.Slider("g paper", "相纸反差", "冲印", 0.5f, 1.5f, 1f),
            Setting.Slider("p base", "相纸片基", "冲印", 0f, 1.5f, 1f),
            Setting.Slider("g fast p", "相纸快层反差", "冲印", 0.5f, 1.5f, 1f),
            Setting.Slider("g slow p", "相纸慢层反差", "冲印", 0.5f, 1.5f, 1f),
            Setting.Slider("p exh", "相纸药液衰竭", "冲印", 0f, 1f, 0f),
            Setting.Slider("glare", "眩光", "冲印", 0f, 0.5f, 0f),

            Setting.Choice(BALANCE, "滤色方式", "放大机滤色",
                listOf("保留胶片本色（统一滤色）", "自动中性（逐卷校正）", "手动"), 0),
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

/** Current values, persisted on the device. */
class SettingsStore(context: Context) {
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
    }

    operator fun get(key: String): Float = prefs.getFloat(key, Settings.byKey.getValue(key).default)

    operator fun set(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
        listeners.forEach { it(key) }
    }

    fun flag(key: String) = get(key) >= 0.5f

    fun resetAll() {
        prefs.edit().clear().apply()
        listeners.forEach { it("") }
    }

    fun onChange(listener: (String) -> Unit) { listeners += listener }

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

    val longEdge: Int get() = Settings.longEdges[get(Settings.LONG_EDGE).toInt().coerceIn(0, Settings.longEdges.lastIndex)]

    private companion object {
        const val SCHEMA = "schema"
    }
}
