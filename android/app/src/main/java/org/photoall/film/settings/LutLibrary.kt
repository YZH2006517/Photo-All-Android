package org.photoall.film.settings

import android.content.Context
import android.net.Uri
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The creative LUTs: the bundled ones (assets/luts) and the user's own.
 *
 * Bundled: first the CCD compact cameras of Marcs Digicam Emulator (MIT, converted by
 * tools/convert_digicam.py), then Editor Hub's CC0 looks (tools/convert_editorhub.py), then
 * the retro camera looks of ganjmeng/dazz-retro-camera (MIT, tools/convert_dazzretro.py) -
 * together the manifest's "leading" count - then the film emulations converted from the
 * NatronGitHub/clut HaldCLUT presets (CC BY-SA 4.0). After them the
 * user's imported 3D LUTs (.cube), kept in files/luts. Index 0 is "no LUT"; entry
 * i is luts[i - 1]. Every table is 33^3 RGB8, red fastest, sRGB in and out.
 */
object LutLibrary {
    class Lut(val id: String, val label: String, val group: String, val user: Boolean = false)

    const val USER_GROUP = "我的 LUT"

    @Volatile private var builtin: List<Lut> = emptyList()
    @Volatile private var mine: List<Lut> = emptyList()
    private val luts get() = builtin + mine
    var size = 33
        private set
    /** How many bundled LUTs were put ahead of the film LUTs (indexes shift by this). */
    var leading = 0
        private set
    private val cache = LruCache<String, ByteArray>(8)

    fun load(context: Context) {
        if (builtin.isNotEmpty()) return
        val json = JSONObject(context.assets.open("luts/manifest.json").bufferedReader().use { it.readText() })
        size = json.optInt("size", 33)
        leading = json.optInt("leading", json.optInt("camera", 0))
        val array = json.getJSONArray("luts")
        builtin = List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Lut(o.getString("id"), o.getString("label"), o.getString("group"))
        }
        mine = readMine(context)
    }

    val count: Int get() = luts.size + 1

    /** Display name for a selection index (0 = none). */
    fun name(index: Int): String = luts.getOrNull(index - 1)?.label ?: "无 LUT"
    fun group(index: Int): String = luts.getOrNull(index - 1)?.group ?: ""
    fun isUser(index: Int): Boolean = luts.getOrNull(index - 1)?.user == true

    /** The CCD camera behind a selection index ("compact2004"), or null for any other LUT. */
    fun digicam(index: Int): String? = luts.getOrNull(index - 1)?.id?.takeIf { it.startsWith("digicam/") }?.removePrefix("digicam/")

    /** Every selectable entry as "group · label" (index 0 first). */
    fun entries(): List<String> = listOf("无 LUT") + luts.map { "${it.group} · ${it.label}" }

    /** The table for a selection index, or null for none. */
    fun table(context: Context, index: Int): ByteArray? {
        val lut = luts.getOrNull(index - 1) ?: return null
        cache.get(lut.id)?.let { return it }
        val bytes = if (lut.user) File(dir(context), "${lut.id}.rgb").readBytes()
            else context.assets.open("luts/${lut.id}.rgb").use { it.readBytes() }
        cache.put(lut.id, bytes)
        return bytes
    }

    // ---- the user's own -----------------------------------------------------------------------

    private fun dir(context: Context) = File(context.filesDir, "luts").apply { mkdirs() }

    private fun readMine(context: Context): List<Lut> = runCatching {
        val a = JSONArray(File(dir(context), "mine.json").readText())
        List(a.length()) { i -> a.getJSONObject(i).let { Lut(it.getString("id"), it.getString("label"), USER_GROUP, user = true) } }
    }.getOrDefault(emptyList())

    private fun writeMine(context: Context) {
        val a = JSONArray()
        for (l in mine) a.put(JSONObject().put("id", l.id).put("label", l.label))
        File(dir(context), "mine.json").writeText(a.toString())
    }

    /**
     * What a LUT expects as input. Camera LUTs are mostly made for a log recording: the
     * photo (sRGB, its linear light taken as the scene's, grey at 0.18) is then turned into
     * that camera's gamut and log curve first, and the LUT's Rec.709 (BT.1886) output back
     * into sRGB.
     */
    enum class Input(val label: String, val hint: Regex?) {
        DISPLAY("普通照片（Rec.709 / sRGB）", null),
        VLOG("松下 V-Log / V-Gamut", Regex("(?i)v[-_ ]?log")),
        SLOG3("索尼 S-Log3 / S-Gamut3.Cine", Regex("(?i)s[-_ ]?log ?3")),
        FLOG2("富士 F-Log2 / F-Gamut", Regex("(?i)f[-_ ]?log ?2(?!c)")),
        NLOG("尼康 N-Log / BT.2020", Regex("(?i)n[-_ ]?log")),
        LOGC3("ARRI LogC3 / Wide Gamut 3", Regex("(?i)log ?c ?3|logc(?!4)|alexa")),
        ;

        companion object {
            /** A guess from the file's name and title (V-Log LUTs usually say so). */
            fun guess(name: String): Input = entries.firstOrNull { it.hint?.containsMatchIn(name) == true } ?: DISPLAY
        }
    }

    private fun srgbToLinear(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun linearToSrgb(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f else (1.055 * Math.pow(x.toDouble(), 1 / 2.4) - 0.055).toFloat()
    }

    /** Linear Rec.709 / sRGB primaries to [primaries] (xy of R, G, B; D65 white), as a 3x3 row-major matrix. */
    private fun fromRec709(primaries: FloatArray): FloatArray {
        fun toXyz(p: FloatArray): DoubleArray {
            val xr = p[0].toDouble(); val yr = p[1].toDouble(); val xg = p[2].toDouble(); val yg = p[3].toDouble()
            val xb = p[4].toDouble(); val yb = p[5].toDouble()
            val m = doubleArrayOf(xr / yr, xg / yg, xb / yb, 1.0, 1.0, 1.0, (1 - xr - yr) / yr, (1 - xg - yg) / yg, (1 - xb - yb) / yb)
            val w = doubleArrayOf(0.3127 / 0.3290, 1.0, (1 - 0.3127 - 0.3290) / 0.3290)
            val s = solve(m, w)
            return DoubleArray(9) { i -> m[i] * s[i % 3] }
        }
        val a = toXyz(floatArrayOf(0.64f, 0.33f, 0.30f, 0.60f, 0.15f, 0.06f))
        val b = invert(toXyz(primaries))
        return FloatArray(9) { i -> (0..2).sumOf { k -> b[i / 3 * 3 + k] * a[k * 3 + i % 3] }.toFloat() }
    }

    private fun invert(m: DoubleArray): DoubleArray {
        val d = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / d, (m[2] * m[7] - m[1] * m[8]) / d, (m[1] * m[5] - m[2] * m[4]) / d,
            (m[5] * m[6] - m[3] * m[8]) / d, (m[0] * m[8] - m[2] * m[6]) / d, (m[2] * m[3] - m[0] * m[5]) / d,
            (m[3] * m[7] - m[4] * m[6]) / d, (m[1] * m[6] - m[0] * m[7]) / d, (m[0] * m[4] - m[1] * m[3]) / d)
    }

    private fun solve(m: DoubleArray, v: DoubleArray): DoubleArray {
        val i = invert(m)
        return DoubleArray(3) { r -> i[r * 3] * v[0] + i[r * 3 + 1] * v[1] + i[r * 3 + 2] * v[2] }
    }

    /** The photo's colour [rgb] (sRGB-encoded) as the camera's log signal for [input]. */
    private fun encodeFor(input: Input, rgb: FloatArray, matrix: FloatArray?): FloatArray {
        if (input == Input.DISPLAY) return rgb
        val lin = FloatArray(3) { srgbToLinear(rgb[it]) }
        val g = FloatArray(3) { r -> matrix!![r * 3] * lin[0] + matrix[r * 3 + 1] * lin[1] + matrix[r * 3 + 2] * lin[2] }
        return FloatArray(3) { k ->
            val x = g[k].toDouble()
            when (input) {
                Input.VLOG -> if (x < 0.01) 5.6 * x + 0.125 else 0.241514 * Math.log10(x + 0.00873) + 0.598206
                Input.SLOG3 -> if (x >= 0.01125) (420 + Math.log10((x + 0.01) / 0.19) * 261.5) / 1023
                    else (x * (171.2102946929 - 95) / 0.01125 + 95) / 1023
                Input.FLOG2 -> if (x >= 0.000889) 0.245281 * Math.log10(5.555556 * x + 0.064829) + 0.384316
                    else 8.799461 * x + 0.092864
                Input.NLOG -> if (x < 0.328) 650 * Math.cbrt(maxOf(x + 0.0075, 0.0)) / 1023 else (150 * Math.log(x) + 619) / 1023
                Input.LOGC3 -> if (x > 0.010591) 0.247190 * Math.log10(5.555556 * x + 0.052272) + 0.385537
                    else 5.367655 * x + 0.092809
                Input.DISPLAY -> x
            }.toFloat()
        }
    }

    private fun primariesOf(input: Input) = when (input) {
        Input.VLOG -> floatArrayOf(0.730f, 0.280f, 0.165f, 0.840f, 0.100f, -0.030f)
        Input.SLOG3 -> floatArrayOf(0.766f, 0.275f, 0.225f, 0.800f, 0.089f, -0.087f)
        Input.FLOG2, Input.NLOG -> floatArrayOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f) // BT.2020
        Input.LOGC3 -> floatArrayOf(0.6840f, 0.3130f, 0.2210f, 0.8480f, 0.0861f, -0.1020f)
        Input.DISPLAY -> null
    }

    /**
     * Reads a 3D LUT in Adobe / Resolve .cube form (any size, DOMAIN_MIN / MAX or
     * LUT_3D_INPUT_RANGE honoured), resamples it to 33^3 (through [input]'s log encoding
     * for a camera LUT) and adds it to the user's LUTs. Returns its selection index. Call
     * off the main thread.
     */
    fun importCube(context: Context, uri: Uri, fallbackName: String, input: Input = Input.DISPLAY): Int {
        var n = 0
        var title: String? = null
        val lo = floatArrayOf(0f, 0f, 0f)
        val hi = floatArrayOf(1f, 1f, 1f)
        var values = FloatArray(0)
        var at = 0
        context.contentResolver.openInputStream(uri)!!.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val first = line[0]
                if (first.isLetter()) {
                    val parts = line.split(Regex("\\s+"))
                    when (parts[0].uppercase()) {
                        "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"').ifBlank { null }
                        "LUT_3D_SIZE" -> { n = parts[1].toInt(); values = FloatArray(n * n * n * 3) }
                        "LUT_1D_SIZE" -> error("这是 1D LUT，只支持 3D LUT")
                        "DOMAIN_MIN" -> for (k in 0..2) lo[k] = parts[k + 1].toFloat()
                        "DOMAIN_MAX" -> for (k in 0..2) hi[k] = parts[k + 1].toFloat()
                        "LUT_3D_INPUT_RANGE" -> { lo.fill(parts[1].toFloat()); hi.fill(parts[2].toFloat()) }
                    }
                    continue
                }
                if (n == 0) error("缺少 LUT_3D_SIZE")
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3 || at + 3 > values.size) continue
                for (k in 0..2) values[at++] = parts[k].toFloat()
            }
        }
        if (n < 2 || at != values.size) error("文件不完整（${at / 3} / ${n * n * n} 个点）")
        val out = ByteArray(33 * 33 * 33 * 3)
        var o = 0
        fun cell(r: Int, g: Int, b: Int, k: Int) = values[((b * n + g) * n + r) * 3 + k]
        val matrix = primariesOf(input)?.let { fromRec709(it) }
        for (bi in 0 until 33) for (gi in 0 until 33) for (ri in 0 until 33) {
            val signal = encodeFor(input, floatArrayOf(ri / 32f, gi / 32f, bi / 32f), matrix)
            val pos = FloatArray(3) { k ->
                ((signal[k] - lo[k]) / (hi[k] - lo[k]) * (n - 1)).coerceIn(0f, (n - 1).toFloat())
            }
            val i0 = IntArray(3) { minOf(pos[it].toInt(), n - 2) }
            val f = FloatArray(3) { pos[it] - i0[it] }
            for (k in 0..2) {
                var v = 0f
                for (dr in 0..1) for (dg in 0..1) for (db in 0..1) {
                    val w = (if (dr == 1) f[0] else 1 - f[0]) * (if (dg == 1) f[1] else 1 - f[1]) * (if (db == 1) f[2] else 1 - f[2])
                    v += w * cell(i0[0] + dr, i0[1] + dg, i0[2] + db, k)
                }
                // a log LUT's output is Rec.709 for a BT.1886 (2.4) display: into sRGB
                if (input != Input.DISPLAY) v = linearToSrgb(Math.pow(v.coerceIn(0f, 1f).toDouble(), 2.4).toFloat())
                out[o++] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
        }
        val id = "user_${System.currentTimeMillis()}"
        File(dir(context), "$id.rgb").writeBytes(out)
        mine = mine + Lut(id, title ?: fallbackName, USER_GROUP, user = true)
        writeMine(context)
        return count - 1
    }

    /** Removes one of the user's LUTs; the ones after it move up one. */
    fun delete(context: Context, index: Int) {
        val lut = luts.getOrNull(index - 1)?.takeIf { it.user } ?: return
        File(dir(context), "${lut.id}.rgb").delete()
        cache.remove(lut.id)
        mine = mine.filter { it.id != lut.id }
        writeMine(context)
    }
}
