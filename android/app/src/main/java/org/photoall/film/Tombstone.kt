package org.photoall.film

/**
 * The parts of an Android native crash record (a tombstone, protobuf, Android 12+;
 * system/core/debuggerd/proto/tombstone.proto) a report needs: the signal, the abort
 * message, the crashing thread's backtrace (library and function per frame) and the
 * app's last log lines. A minimal protobuf reader: unknown fields are skipped.
 */
internal object Tombstone {

    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (p < end) {
                val x = b[p++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl s)
                if (x < 0x80) break
                s += 7
            }
            return r
        }
        /** The next field as (number, wire type); for length-delimited ones the payload's range follows. */
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun bytes(): Reader { val n = varint().toInt(); val r = Reader(b, p, minOf(end, p + n)); p = minOf(end, p + n); return r }
        fun string(): String { val r = bytes(); return String(b, r.p, r.end - r.p, Charsets.UTF_8) }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> bytes()
                5 -> p += 4
                else -> p = end
            }
        }
    }

    private class Frame(val pc: Long, val function: String, val offset: Long, val file: String)
    private class Thread(val id: Int, val name: String, val frames: List<Frame>)

    fun describe(bytes: ByteArray, maxFrames: Int = 28, maxLogs: Int = 40): String? = runCatching {
        val r = Reader(bytes)
        var tid = 0
        var signal = ""
        var faultAddress: Long? = null
        var abort = ""
        val causes = mutableListOf<String>()
        val threads = mutableMapOf<Int, Thread>()
        val logs = mutableListOf<String>()
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 6 && wire == 0 -> tid = r.varint().toInt()
                field == 10 && wire == 2 -> {
                    val s = r.bytes()
                    var name = ""; var code = ""
                    while (s.more()) {
                        val (f, w) = s.tag()
                        when {
                            f == 2 && w == 2 -> name = s.string()
                            f == 4 && w == 2 -> code = s.string()
                            f == 9 && w == 0 -> faultAddress = s.varint()
                            else -> s.skip(w)
                        }
                    }
                    signal = "$name $code".trim()
                }
                field == 14 && wire == 2 -> abort = r.string()
                field == 15 && wire == 2 -> {
                    val c = r.bytes()
                    while (c.more()) { val (f, w) = c.tag(); if (f == 1 && w == 2) causes += c.string() else c.skip(w) }
                }
                field == 16 && wire == 2 -> {
                    // map<uint32, Thread>: entries of {1: key, 2: Thread}
                    val e = r.bytes()
                    while (e.more()) {
                        val (f, w) = e.tag()
                        if (f == 2 && w == 2) thread(e.bytes())?.let { threads[it.id] = it } else e.skip(w)
                    }
                }
                field == 18 && wire == 2 -> {
                    val buffer = r.bytes()
                    while (buffer.more()) {
                        val (f, w) = buffer.tag()
                        if (f == 2 && w == 2) {
                            val m = buffer.bytes()
                            var time = ""; var priority = 0; var tag = ""; var message = ""
                            while (m.more()) {
                                val (mf, mw) = m.tag()
                                when {
                                    mf == 1 && mw == 2 -> time = m.string()
                                    mf == 4 && mw == 0 -> priority = m.varint().toInt()
                                    mf == 5 && mw == 2 -> tag = m.string()
                                    mf == 6 && mw == 2 -> message = m.string()
                                    else -> m.skip(mw)
                                }
                            }
                            val level = "??VDIWEF".getOrElse(priority) { '?' }
                            logs += "${time.substringAfter(' ').take(12)} $level $tag: ${message.trim().take(300)}"
                        } else buffer.skip(w)
                    }
                }
                else -> r.skip(wire)
            }
        }
        buildString {
            append("信号：").append(signal.ifEmpty { "?" })
            faultAddress?.let { append(" · 地址 0x").append(java.lang.Long.toHexString(it)) }
            append('\n')
            if (abort.isNotBlank()) append("中止信息：").append(abort.trim().take(600)).append('\n')
            causes.forEach { append("可能原因：").append(it).append('\n') }
            val crashed = threads[tid]
            append("\n—— 崩溃线程 ").append(crashed?.name ?: "tid $tid").append(" ——\n")
            crashed?.frames?.take(maxFrames)?.forEachIndexed { i, f ->
                append("#%02d ".format(i)).append(f.file.substringAfterLast('/').ifEmpty { "?" })
                if (f.function.isNotEmpty()) append(" · ").append(f.function.take(160)).append("+").append(f.offset)
                append('\n')
            }
            // a hint where the other threads stood: those inside our own code
            threads.values.filter { it.id != tid && it.frames.any { f -> f.file.contains("filmjni") || f.file.contains("vulkan") } }
                .take(3).forEach { t ->
                    append("\n—— 线程 ").append(t.name).append("（涉及引擎 / 驱动）——\n")
                    t.frames.take(10).forEach { f ->
                        append("   ").append(f.file.substringAfterLast('/')).append(" · ").append(f.function.take(120)).append('\n')
                    }
                }
            if (logs.isNotEmpty()) {
                append("\n—— 崩溃前的日志 ——\n")
                logs.takeLast(maxLogs).forEach { append(it).append('\n') }
            }
        }
    }.getOrNull()

    private fun thread(r: Reader): Thread? {
        var id = 0; var name = ""
        val frames = mutableListOf<Frame>()
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 0 -> id = r.varint().toInt()
                f == 2 && w == 2 -> name = r.string()
                f == 4 && w == 2 -> {
                    val fr = r.bytes()
                    var pc = 0L; var fn = ""; var off = 0L; var file = ""
                    while (fr.more()) {
                        val (ff, fw) = fr.tag()
                        when {
                            ff == 1 && fw == 0 -> pc = fr.varint()
                            ff == 4 && fw == 2 -> fn = fr.string()
                            ff == 5 && fw == 0 -> off = fr.varint()
                            ff == 6 && fw == 2 -> file = fr.string()
                            else -> fr.skip(fw)
                        }
                    }
                    frames += Frame(pc, fn, off, file)
                }
                else -> r.skip(w)
            }
        }
        return Thread(id, name, frames)
    }
}
