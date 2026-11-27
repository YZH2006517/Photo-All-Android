package org.photoall.film.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Back lenses the system does not list to apps but describes when asked by id; some phones
 * also open them for any app (the idea PhotonCamera works by: ids tried one by one, plus a
 * table of ids per model). Each found lens is a candidate only: it is offered for RAW at its
 * focal length, and the first time it will not open it is put aside for good (lens_dead_<id>,
 * the same record as a listed lens that will not open), that focal length then shot as JPEG
 * by the phone's own zoom. Looked for once per system build, kept in preferences.
 *
 * A candidate must: answer getCameraCharacteristics, face back, offer RAW, be a single lens
 * (not a logical camera), and sit at a focal length no listed lens already covers.
 */
object LensProbe {
    class Hidden(val id: String, val mm: Float)

    /** Ids known to hold the hidden lenses, by brand / device (Build.BRAND / Build.DEVICE, lower case). */
    private val TABLE = mapOf(
        "samsung/dm3q" to listOf("52", "54", "56", "58"), // Galaxy S23 Ultra
        "xiaomi/aurora" to listOf("2", "3", "4", "5"),     // Xiaomi 14 Ultra
        "xiaomi/ishtar" to listOf("2", "3", "4", "5"),     // Xiaomi 13 Ultra
    )
    private const val SCAN = 64 // ids 0..63; above that phones keep their private (depth, macro) streams

    /** The candidates for this phone, by focal length; [listed] are the ids apps see, [mainMm] the main lens. */
    fun find(context: Context, listed: Set<String>, listedMm: List<Float>, mainMm: Float): List<Hidden> {
        val prefs = context.getSharedPreferences("lens_probe", Context.MODE_PRIVATE)
        val stamp = Build.FINGERPRINT
        if (prefs.getString("stamp", null) == stamp) return read(prefs.getString("found", "[]"))
        val manager = context.getSystemService(CameraManager::class.java)
        val device = "${Build.BRAND}/${Build.DEVICE}".lowercase()
        val ids = (TABLE[device].orEmpty() + (0 until SCAN).map { it.toString() }).distinct().filter { it !in listed }
        val found = ArrayList<Hidden>()
        // why each id that answered was left out, for the log (日志)
        val notes = JSONObject()
        for (id in ids) {
            val got = runCatching { manager.getCameraCharacteristics(id) }
            val c = got.getOrNull()
            if (c == null) { if (id in TABLE[device].orEmpty()) notes.put(id, "无法读取：${got.exceptionOrNull()?.message}"); continue }
            val mm = equivalentMm(c)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val why = when {
                c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK -> "不是后置"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps -> "不给 RAW"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps -> "多镜头合成相机"
                mm <= 0f -> "没有焦距"
                // a focal length a listed lens (or one found before) already gives is not wanted twice
                (listedMm + mainMm + found.map { it.mm }).any { abs(it / mm - 1f) < 0.08f } -> "焦距和已有镜头重复"
                else -> null
            }
            if (why == null) found += Hidden(id, mm)
            notes.put(id, (why ?: "可用") + "（%.0fmm）".format(mm))
        }
        prefs.edit().putString("stamp", stamp).putString("notes", notes.toString()).putString("found", JSONArray().apply {
            for (h in found) put(JSONObject().put("id", h.id).put("mm", h.mm.toDouble()))
        }.toString()).apply()
        return found
    }

    /** What the last look found, id by id, for the log. */
    fun notes(context: Context): String =
        context.getSharedPreferences("lens_probe", Context.MODE_PRIVATE).getString("notes", null) ?: "还没找过"

    private fun read(json: String?): List<Hidden> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Hidden(o.getString("id"), o.getDouble("mm").toFloat()) } }
    }.getOrDefault(emptyList())

    /** 35 mm equivalent focal length from the sensor's diagonal (as CameraActivity measures listed lenses). */
    fun equivalentMm(c: CameraCharacteristics): Float {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return focal
        return (focal * 43.27 / hypot(sensor.width.toDouble(), sensor.height.toDouble())).toFloat()
    }
}
