package org.photoall.film.settings

import android.content.Context
import android.util.LruCache
import org.json.JSONObject

/**
 * The bundled creative LUTs (assets/luts, converted from the NatronGitHub/clut
 * HaldCLUT presets, CC BY-SA 4.0). Index 0 is "no LUT"; entry i is luts[i - 1].
 */
object LutLibrary {
    class Lut(val id: String, val label: String, val group: String)

    @Volatile private var luts: List<Lut> = emptyList()
    var size = 33
        private set
    private val cache = LruCache<String, ByteArray>(8)

    fun load(context: Context) {
        if (luts.isNotEmpty()) return
        val json = JSONObject(context.assets.open("luts/manifest.json").bufferedReader().use { it.readText() })
        size = json.optInt("size", 33)
        val array = json.getJSONArray("luts")
        luts = List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Lut(o.getString("id"), o.getString("label"), o.getString("group"))
        }
    }

    val count: Int get() = luts.size + 1

    /** Display name for a selection index (0 = none). */
    fun name(index: Int): String = luts.getOrNull(index - 1)?.label ?: "无 LUT"
    fun group(index: Int): String = luts.getOrNull(index - 1)?.group ?: ""

    /** Every selectable entry as "group · label" (index 0 first). */
    fun entries(): List<String> = listOf("无 LUT") + luts.map { "${it.group} · ${it.label}" }

    /** The table for a selection index, or null for none. */
    fun table(context: Context, index: Int): ByteArray? {
        val lut = luts.getOrNull(index - 1) ?: return null
        cache.get(lut.id)?.let { return it }
        val bytes = context.assets.open("luts/${lut.id}.rgb").use { it.readBytes() }
        cache.put(lut.id, bytes)
        return bytes
    }
}
