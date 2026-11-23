package org.photoall.film.gallery

import android.content.ContentResolver
import android.net.Uri

/**
 * A picked file's bytes when it is a RAW the engine may read: a DNG, or a camera's own RAW
 * (the engine reads those through LibRaw): TIFF-based ones (NEF, CR2, ARW, PEF ...), Olympus
 * ORF, Panasonic RW2, Fujifilm RAF and Canon CR3, told by their first bytes. Null otherwise.
 */
object RawSource {
    fun read(resolver: ContentResolver, uri: Uri): java.nio.ByteBuffer? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(16)
            var got = 0
            while (got < head.size) { val n = input.read(head, got, head.size - got); if (n <= 0) break; got += n }
            if (got < 16) return@use null
            fun at(i: Int, s: String) = s.indices.all { head[i + it] == s[it].code.toByte() }
            val ii = at(0, "II"); val mm = at(0, "MM")
            val tiff = (ii && head[2] == 42.toByte() && head[3] == 0.toByte()) || (mm && head[2] == 0.toByte() && head[3] == 42.toByte())
            val orf = (ii && (at(2, "RO") || at(2, "RS"))) || (mm && at(2, "OR"))
            val rw2 = ii && head[2] == 'U'.code.toByte() && head[3] == 0.toByte()
            val raf = at(0, "FUJIFILM")
            val cr3 = at(4, "ftypcrx ")
            if (!(tiff || orf || rw2 || raf || cr3)) return@use null
            val rest = input.readBytes()
            if (rest.size + head.size > 400_000_000) return@use null
            java.nio.ByteBuffer.allocateDirect(rest.size + head.size).apply { put(head); put(rest); flip() }
        }
    }.getOrNull()
}
