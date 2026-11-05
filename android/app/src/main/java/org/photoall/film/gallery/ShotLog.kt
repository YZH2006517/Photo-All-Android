package org.photoall.film.gallery

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Everything known about one photo, for comparing test shots: the camera, every
 * exposure and white balance value at the shot, each frame, what the develop decided,
 * and all settings. Sections and keys keep their insertion order. Saved per photo
 * (by file name) in the app's own storage.
 */
class ShotLog {
    val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()

    fun put(section: String, key: String, value: Any?) {
        val text = when (value) {
            null -> return
            is Float -> "%.4g".format(java.util.Locale.US, value)
            is Double -> "%.4g".format(java.util.Locale.US, value)
            is FloatArray -> value.joinToString(" ") { "%.4f".format(java.util.Locale.US, it) }
            is DoubleArray -> value.joinToString(" ") { "%.4f".format(java.util.Locale.US, it) }
            is IntArray -> value.joinToString(" ")
            is Boolean -> if (value) "是" else "否"
            else -> value.toString()
        }
        if (text.isEmpty()) return
        sections.getOrPut(section) { LinkedHashMap() }[key] = text
    }

    /** The engine's record: "section\tkey\tvalue" lines. */
    fun putEngine(note: String) {
        for (line in note.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size == 3) put(parts[0], parts[1], parts[2])
        }
    }

    fun toText(): String = buildString {
        for ((section, rows) in sections) {
            append("【").append(section).append("】\n")
            for ((k, v) in rows) append(k).append("：").append(v).append('\n')
            append('\n')
        }
    }.trimEnd()

    private fun toJson(): String = JSONArray().also { root ->
        for ((section, rows) in sections) {
            val items = JSONArray()
            for ((k, v) in rows) items.put(JSONArray().put(k).put(v))
            root.put(JSONArray().put(section).put(items))
        }
    }.toString()

    fun save(context: Context, photoName: String) {
        runCatching { file(context, photoName).also { it.parentFile?.mkdirs() }.writeText(toJson()) }
    }

    companion object {
        private fun file(context: Context, photoName: String) = File(File(context.filesDir, "photo_info"), "$photoName.json")

        fun load(context: Context, photoName: String): ShotLog? = runCatching {
            val f = file(context, photoName)
            if (!f.exists()) return null
            val root = JSONArray(f.readText())
            ShotLog().also { log ->
                for (i in 0 until root.length()) {
                    val section = root.getJSONArray(i)
                    val items = section.getJSONArray(1)
                    for (j in 0 until items.length()) {
                        val kv = items.getJSONArray(j)
                        log.put(section.getString(0), kv.getString(0), kv.getString(1))
                    }
                }
            }
        }.getOrNull()
    }
}
