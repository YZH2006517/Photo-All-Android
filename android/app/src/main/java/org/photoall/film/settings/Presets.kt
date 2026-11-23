package org.photoall.film.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shooting presets: chosen parts of the look and the exposure, kept under a name, put back in
 * one tap (or by sliding onto it from a long press on the tools capsule). A preset holds only
 * the parts ticked when it was saved, and putting it back changes only those. Never in a
 * preset: the device, screen and storage settings (theme, haptics, RAW and its keeping, size
 * and quality, the viewfinder's own display, guides, focus, flash mode, the reel, the retired
 * HDR+). Kept as JSON in their own preferences.
 */
object Presets {
    private const val PREFS = "presets"
    private const val KEY = "list"
    private const val LAST_PARTS = "last_parts"

    /** What a preset never touches. */
    private val EXCLUDED = setOf(
        Settings.THEME, Settings.HAPTICS, Settings.FRAME_AUTO,
        Settings.RAW, Settings.KEEP_RAW, Settings.PRIVATE_PHOTOS, Settings.LONG_EDGE, Settings.JPEG_QUALITY,
        Settings.FINDER_EV, Settings.FINDER_LUT, Settings.FINDER_SOFT, Settings.FINDER_SAT, Settings.FINDER_GRAIN,
        Settings.REEL_ON, Settings.REEL_BEFORE, Settings.REEL_AFTER,
        Settings.HDR, Settings.HDR_FRAMES, Settings.HDR_UNDER, Settings.HDR_LONG, Settings.HDR_TONE,
        Settings.FLASH, Settings.FLICKER, Settings.GUIDES, Settings.HISTOGRAM, Settings.PEAKING,
        Settings.FOCUS, Settings.FOCUS_DISTANCE,
    )

    /** The parts a preset may hold, in the order they are offered. */
    enum class Part(val label: String) { FILM("胶片"), PAPER("相纸"), LUT("LUT 与强度"), DEVELOP("冲洗参数"), EXPOSURE("曝光与测光"), DENOISE("降噪"), FRAME("边框") }

    private val PART_KEYS: Map<Part, Set<String>> = mapOf(
        Part.FILM to setOf(Settings.FILM_ON, "film"),
        Part.PAPER to setOf(Settings.PRINT_ON, "paper"),
        Part.LUT to setOf(Settings.LUT, Settings.LUT_ON, Settings.LUT_STRENGTH),
        Part.EXPOSURE to setOf(Settings.MANUAL, Settings.ISO, Settings.SHUTTER, Settings.WHITE_BALANCE, Settings.METERING,
            Settings.RAW_EV, Settings.FILM_ISO, Settings.SOFT_3A, Settings.HIGHLIGHT,
            Settings.FLASH_UNDER, Settings.FLASH_FILL, Settings.FLASH_WHITE),
        Part.DENOISE to setOf(Settings.CHROMA_NR, Settings.RAW_DENOISE),
        Part.FRAME to setOf(Settings.FRAME, Settings.FRAME_TEXT, Settings.FRAME_DATE, Settings.FRAME_INFO, Settings.FRAME_DEVICE, Settings.FRAME_RECIPE),
    )

    /** Every key a preset may keep. */
    val keys: List<String> by lazy { Settings.all.map { it.key }.filter { it !in EXCLUDED } }

    /** The part a key belongs to: the development parameters are everything not named. */
    fun partOf(key: String): Part = PART_KEYS.entries.firstOrNull { key in it.value }?.key ?: Part.DEVELOP

    class Preset(val name: String, val created: Long, val values: Map<String, Float>) {
        /** The parts this preset holds. */
        val parts: Set<Part> get() = values.keys.map { partOf(it) }.toSet()
    }

    fun list(context: Context): List<Preset> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val v = o.optJSONObject("values") ?: return@mapNotNull null
                val values = HashMap<String, Float>()
                for (k in v.keys()) values[k] = v.optDouble(k).toFloat()
                Preset(o.optString("name", "预设"), o.optLong("created"), values)
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, presets: List<Preset>) {
        val array = JSONArray()
        for (p in presets) array.put(JSONObject().apply {
            put("name", p.name); put("created", p.created)
            put("values", JSONObject().apply { for ((k, v) in p.values) put(k, v.toDouble()) })
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    /** The parts ticked the last time a preset was saved (all of them the first time). */
    fun lastParts(context: Context): Set<Part> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(LAST_PARTS, null)
            ?: return Part.values().toSet()
        return Part.values().filter { it.name in saved }.toSet().ifEmpty { Part.values().toSet() }
    }

    /** The settings as they are now, in [parts]. */
    fun capture(store: SettingValues, parts: Set<Part> = Part.values().toSet()): Map<String, Float> =
        keys.filter { partOf(it) in parts }.associateWith { store[it] }

    fun add(context: Context, name: String, store: SettingValues, parts: Set<Part>, remember: Boolean = true) {
        if (remember) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(LAST_PARTS, parts.map { it.name }.toSet()).apply()
        write(context, list(context) + Preset(name, System.currentTimeMillis(), capture(store, parts)))
    }

    /** The preset's own parts, taken again from the settings now. */
    fun update(context: Context, index: Int, store: SettingValues) = write(context, list(context).mapIndexed { i, p ->
        if (i == index) Preset(p.name, p.created, capture(store, p.parts)) else p
    })

    fun rename(context: Context, index: Int, name: String) = write(context, list(context).mapIndexed { i, p ->
        if (i == index) Preset(name, p.created, p.values) else p
    })

    fun delete(context: Context, index: Int) = write(context, list(context).filterIndexed { i, _ -> i != index })

    /**
     * Puts the preset's values back: only those that differ, and only keys still known (a
     * preset from an older version may hold one that is gone). Values out of a setting's range
     * (a LUT since removed) are clamped. Returns how many changed.
     */
    fun apply(preset: Preset, store: SettingValues): Int {
        var changed = 0
        for ((k, v) in preset.values) {
            if (k !in keys) continue
            val setting = Settings.byKey[k] ?: continue
            val value = when (setting) {
                is Setting.Slider -> v.coerceIn(setting.min, setting.max)
                is Setting.Choice -> v.coerceIn(0f, (setting.options.size - 1).toFloat())
                else -> v
            }
            if (store[k] != value) { store[k] = value; changed++ }
        }
        return changed
    }

    /** What an engine preset holds: the development parameters and the denoise (the 调参台's). */
    val ENGINE_PARTS = setOf(Part.DEVELOP, Part.DENOISE)

    /** The engine presets (those holding only engine parameters), with their places in the list. */
    fun engine(context: Context) = list(context).withIndex().filter { (_, p) -> p.parts.isNotEmpty() && ENGINE_PARTS.containsAll(p.parts) }

    private fun engineKeys() = keys.filter { partOf(it) in ENGINE_PARTS }

    /** The engine parameters all at their defaults. */
    fun engineDefault(store: SettingValues) = engineKeys().all { k -> Settings.byKey[k]?.let { store[k] == it.default } ?: true }

    /** Every engine parameter back to its default; how many changed. */
    fun resetEngine(store: SettingValues): Int {
        var changed = 0
        for (k in engineKeys()) {
            val d = Settings.byKey[k]?.default ?: continue
            if (store[k] != d) { store[k] = d; changed++ }
        }
        return changed
    }

    private const val ENGINE_ACTIVE = "engine_active"

    /** Marks the engine chip last tapped: 0 for 默认, else the preset's `created`. */
    fun setEngineActive(context: Context, id: Long) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(ENGINE_ACTIVE, id).apply()

    /**
     * The one engine chip that is lit (single choice): the one last tapped while the settings
     * are still its, else the first whose values match (默认 first); -1 when none matches. Two
     * presets with the same values (one saved without changes equals 默认) no longer light
     * together.
     */
    fun engineSelected(context: Context, store: SettingValues): Long {
        val engine = engine(context).map { it.value }
        fun holds(id: Long) = if (id == 0L) engineDefault(store) else engine.any { it.created == id && matches(it, store) }
        val active = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(ENGINE_ACTIVE, -1L)
        if (active >= 0 && holds(active)) return active
        if (engineDefault(store)) return 0L
        return engine.firstOrNull { matches(it, store) }?.created ?: -1L
    }

    /** Whether the settings now are this preset's (in the parts it holds). */
    fun matches(preset: Preset, store: SettingValues) =
        preset.values.all { (k, v) -> k !in keys || Settings.byKey[k] == null || store[k] == v }
}
