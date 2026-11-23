package org.photoall.film.camera

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The user's own focal lengths (35 mm equivalent, whole millimetres) and the focal lengths
 * kept off the row under the viewfinder. Whatever is read back is checked again: a damaged
 * or hand-edited pref, or one restored onto another phone, never reaches the camera as a
 * nonsense value (unreadable entries are dropped, out-of-range ones are skipped when the
 * buttons are built).
 */
object FocalLengths {
    const val MAX_CUSTOM = 8
    /** Two focal lengths closer than this share a button. */
    const val SAME = 0.04f
    private const val PREFS = "focal_lengths"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ints(text: String?): List<Int> =
        (text ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..5000 }

    fun custom(c: Context): List<Int> = ints(prefs(c).getString("custom", null)).distinct().sorted().take(MAX_CUSTOM)

    fun hidden(c: Context): Set<Int> = ints(prefs(c).getString("hidden", null)).toSet()

    fun setCustom(c: Context, list: List<Int>) {
        prefs(c).edit().putString("custom", list.distinct().sorted().take(MAX_CUSTOM).joinToString(",")).apply()
    }

    fun setHidden(c: Context, set: Set<Int>) {
        prefs(c).edit().putString("hidden", set.sorted().joinToString(",")).apply()
    }

    fun same(a: Int, b: Int) = abs(a.toFloat() / b - 1f) < SAME

    /**
     * What the user typed, as a focal length this phone can take, or why not. [lo]..[hi]:
     * the widest lens to the longest zoom; [taken]: the focal lengths already on the list.
     */
    fun check(input: String, lo: Int, hi: Int, taken: List<Int>, customCount: Int): Result {
        val text = input.trim().lowercase().removeSuffix("mm").removeSuffix("毫米").trim()
        if (text.isEmpty()) return Result(null, "先填一个焦段，比如 50")
        val value = text.toDoubleOrNull()
        if (value == null || value.isNaN() || value.isInfinite()) return Result(null, "只能填数字（单位是 mm）")
        val mm = value.roundToInt()
        if (mm <= 0) return Result(null, "焦段要大于 0")
        if (mm < lo) return Result(null, "这台手机最广是 $lo mm，再广拍不到")
        if (mm > hi) return Result(null, "这台手机最长只能变焦到 $hi mm")
        taken.firstOrNull { same(it, mm) }?.let { return Result(null, "已经有 $it mm 了") }
        if (customCount >= MAX_CUSTOM) return Result(null, "最多 $MAX_CUSTOM 个自定义焦段，先删掉一个")
        return Result(mm, null)
    }

    class Result(val mm: Int?, val error: String?)
}
