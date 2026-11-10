package org.photoall.film.settings

/**
 * The families the looks are grouped in, wherever they are picked (the camera's strip and
 * sheet, the import screen): film stocks and papers by their engine names, LUTs by their
 * manifest group.
 */
object LookFamilies {
    val FILM = listOf("彩色负片", "反转片", "黑白", "电影卷")

    /** A stock's family: slides, black and white, motion picture, colour negative. */
    fun film(name: String) = when {
        name.contains("(positive)") -> "反转片"
        name.contains("(b/w)") -> "黑白"
        name.contains("vision3") -> "电影卷"
        else -> "彩色负片"
    }

    /** A paper's family: the cinema print stocks apart from the photographic papers. */
    fun paper(name: String) = if (Regex("""\b23(83|93|02)\b""").containsMatchIn(name)) "电影拷贝" else "彩色相纸"

    /** "Endura Premier", "2383": the maker left out. */
    fun paperName(name: String) = name.split(' ')
        .filter { it.isNotEmpty() && it !in setOf("kodak", "fujifilm", "fuji") }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /** "film · paper · LUT", or what replaces them when a stage is off. */
    fun recipeText(filmOn: Boolean, printOn: Boolean, stock: String, paper: String, lut: Int): String {
        val parts = when {
            !filmOn -> listOf("无胶片（直出）")
            !printOn -> listOf(stock, "直接扫描")
            else -> listOf(stock, paper)
        }
        return (parts + if (lut != 0) LutLibrary.name(lut) else "").filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
