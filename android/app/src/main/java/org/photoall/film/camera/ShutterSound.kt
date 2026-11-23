package org.photoall.film.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The shutter's sound, the user's choice (工具 → 快门声): silence (the default, as it always
 * was), the phone's own click, five sounds made here — a leaf shutter, an SLR's mirror and
 * curtain, a rangefinder's cloth curtain, a shot and the film wound on, an instant camera's
 * motor — or a short sound of their own. Synthesised once into WAV files in the cache and kept
 * loaded, so a press sounds at once.
 *
 * Every sound, the phone's own click too, plays on the shutter's stream at the sheet's volume alone, and
 * the made ones are evened to the click's loudness, so none is much louder than another. The
 * phone set to silent or vibrate keeps quiet.
 */
object ShutterSound {
    enum class Kind(val label: String, val about: String) {
        OFF("静音", "只有震动"),
        SYSTEM("系统快门声", "手机自带的那一声"),
        LEAF("镜间快门", "叶片开合，轻而脆"),
        SLR("单反", "反光板起落，咔嚓有份量"),
        CLOTH("旁轴布帘", "很轻的一声"),
        ADVANCE("快门与过片", "咔嚓之后，扳手过片的棘轮声"),
        INSTANT("拍立得", "咔嚓，然后马达吐出相纸"),
        CUSTOM("自定义", "手机里的一段音频"),
    }

    private const val PREFS = "shutter_sound"
    private const val SR = 44100
    /** Longest and largest sound of one's own. */
    const val CUSTOM_MAX_MS = 5000L
    const val CUSTOM_MAX_BYTES = 4L * 1024 * 1024

    private var pool: SoundPool? = null
    private val loaded = HashMap<String, Int>()   // file path -> sound id, once loaded
    private val loading = HashMap<String, Int>()
    private var system: MediaActionSound? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun kind(c: Context): Kind {
        val k = Kind.values().getOrNull(prefs(c).getInt("kind", 0)) ?: Kind.OFF
        if (k == Kind.CUSTOM && customFile(c) == null) return Kind.OFF
        return k
    }

    fun setKind(c: Context, k: Kind) { prefs(c).edit().putInt("kind", k.ordinal).apply(); prepare(c) }
    fun volume(c: Context) = prefs(c).getFloat("volume", 0.5f)
    fun setVolume(c: Context, v: Float) = prefs(c).edit().putFloat("volume", v.coerceIn(0f, 1f)).apply()
    fun customName(c: Context): String? = prefs(c).getString("custom_name", null)?.takeIf { customFile(c) != null }

    fun customFile(c: Context): File? = File(c.filesDir, "shutter_custom").takeIf { it.isFile && it.length() > 0 }

    /** Copies a picked sound into the app; null when it is fine, else why not. */
    fun importCustom(c: Context, uri: Uri): String? {
        val size = runCatching { c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (size > CUSTOM_MAX_BYTES) return "这段音频太大了（最多 4 MB）"
        val ms = runCatching {
            android.media.MediaMetadataRetriever().run {
                try { setDataSource(c, uri); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() } finally { release() }
            }
        }.getOrNull() ?: return "读不出这段音频，换一个文件试试"
        if (ms > CUSTOM_MAX_MS) return "快门声最长 5 秒，这段有 ${ms / 1000} 秒"
        val out = File(c.filesDir, "shutter_custom")
        val ok = runCatching {
            c.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it) } } != null
        }.getOrDefault(false)
        if (!ok) { out.delete(); return "没能读取这段音频" }
        val name = runCatching {
            c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull() ?: "我的快门声"
        // the old one out of memory: the same path now holds the new sound
        loaded.remove(out.path)?.let { id -> pool?.unload(id) }
        loading.remove(out.path)
        prefs(c).edit().putString("custom_name", name).putInt("kind", Kind.CUSTOM.ordinal).apply()
        prepare(c)
        return null
    }

    /** Loads what the chosen sound needs, so the next shot is heard at once. */
    fun prepare(c: Context) {
        val k = kind(c)
        if (k != Kind.OFF) fileFor(c, k)?.let { load(it) }
    }

    /** A shot: the chosen sound, unless the phone is set to be quiet (and the law allows it). */
    fun shot(c: Context) {
        val k = kind(c)
        if (k == Kind.OFF) return
        val ringer = runCatching { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)
        if (ringer != AudioManager.RINGER_MODE_NORMAL) return
        play(c, k, volume(c))
    }

    /** Plays [k] now (a preview in the sheet, or a shot). */
    fun play(c: Context, k: Kind, vol: Float = volume(c)) {
        if (k == Kind.OFF) return
        val f = fileFor(c, k)
        if (f == null) { if (k == Kind.SYSTEM) systemSound().play(MediaActionSound.SHUTTER_CLICK); return }
        val gain = gain(vol)
        val p = pool()
        val id = loaded[f.path]
        if (id != null) p.play(id, gain, gain, 1, 0, 1f)
        else load(f, playWhenLoaded = gain)
    }

    /**
     * The slider to a playing level: squared (the ear hears in ratios, so the lower half gets
     * the finer steps), and at most 0.4 — the shutter's stream is loud, a full 1.0 too much.
     */
    private const val MAX_GAIN = 0.4f
    private fun gain(v: Float) = MAX_GAIN * v.coerceIn(0f, 1f).let { it * it }

    private fun systemSound(): MediaActionSound =
        system ?: MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK); system = it }

    private fun pool(): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(AudioAttributes.Builder()
            // the shutter's own stream (audibility enforced, as the phone's click): on most phones
            // at a fixed level, so only the sheet's volume sets how loud it is — not the media,
            // ring or system volume (a Samsung's system sounds were muted, its media low)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).build())
        .build().also { sp ->
            pool = sp
            sp.setOnLoadCompleteListener { s, id, status ->
                val path = loading.entries.firstOrNull { it.value == id }?.key ?: return@setOnLoadCompleteListener
                loading.remove(path)
                if (status == 0) {
                    loaded[path] = id
                    pendingPlay.remove(path)?.let { v -> s.play(id, v, v, 1, 0, 1f) }
                }
            }
        }

    private val pendingPlay = HashMap<String, Float>()

    private fun load(f: File, playWhenLoaded: Float? = null) {
        if (loaded.containsKey(f.path)) return
        playWhenLoaded?.let { pendingPlay[f.path] = it }
        if (loading.containsKey(f.path)) return
        loading[f.path] = pool().load(f.path, 1)
    }

    /** The phone's own click, read from the system's sounds so it plays at our volume. */
    private val systemClick: File? by lazy {
        listOf("/system/media/audio/ui/camera_click.ogg", "/product/media/audio/ui/camera_click.ogg", "/system_ext/media/audio/ui/camera_click.ogg")
            .map { File(it) }.firstOrNull { runCatching { it.isFile && it.canRead() }.getOrDefault(false) }
    }

    /** The WAV for a made sound (written once to the cache), the system's click, or the user's own file. */
    private fun fileFor(c: Context, k: Kind): File? {
        if (k == Kind.CUSTOM) return customFile(c)
        // where the click cannot be read, the phone plays its own (at its own volume)
        if (k == Kind.SYSTEM) return systemClick
        val f = File(c.cacheDir, "shutter_${k.name.lowercase()}_v3.wav")
        if (!f.isFile) runCatching { writeWav(f, synth(k)) }.onFailure { return null }
        return f
    }

    // ------------------------------------------------------------------ the made sounds

    private fun synth(k: Kind): FloatArray = when (k) {
        Kind.LEAF -> FloatArray((0.10f * SR).toInt()).also { leaf(it, 0f, 1f) }
        Kind.SLR -> FloatArray((0.28f * SR).toInt()).also { o ->
            noise(o, 0f, 0.018f, 1f, 0.16f, false, 1)            // the mirror flies up
            tone(o, 0f, 260f, 0.03f, 0.5f, 0.75f)
            noise(o, 0.032f, 0.006f, 0.75f, 0.5f, true, 2)        // the curtains run
            tone(o, 0.032f, 2200f, 0.008f, 0.15f)
            noise(o, 0.088f, 0.022f, 0.8f, 0.14f, false, 3)       // the mirror comes down
            tone(o, 0.088f, 220f, 0.035f, 0.42f, 0.75f)
            noise(o, 0.115f, 0.004f, 0.22f, 0.4f, true, 4)        // and settles
        }
        Kind.CLOTH -> FloatArray((0.10f * SR).toInt()).also { o ->
            noise(o, 0f, 0.006f, 0.55f, 0.25f, false, 5)
            noise(o, 0.024f, 0.005f, 0.38f, 0.3f, false, 6)
        }
        Kind.ADVANCE -> FloatArray((0.62f * SR).toInt()).also { o ->
            leaf(o, 0f, 1f)
            for (i in 0 until 7) {                                  // the lever's ratchet
                val at = 0.18f + i * 0.045f
                noise(o, at, 0.0016f, 0.5f, 0.7f, true, 10 + i)
                tone(o, at, 4200f, 0.004f, 0.15f)
            }
            noise(o, 0.18f + 7 * 0.045f + 0.03f, 0.01f, 0.45f, 0.2f, false, 20) // home
        }
        Kind.INSTANT -> FloatArray((1.0f * SR).toInt()).also { o ->
            leaf(o, 0f, 0.85f)
            motor(o, 0.12f, 0.95f)
        }
        else -> FloatArray(1)
    }.also { even(it) }

    private fun leaf(o: FloatArray, at: Float, g: Float) {
        noise(o, at, 0.004f, 0.9f * g, 0.6f, true, 30)
        tone(o, at, 3400f, 0.012f, 0.25f * g)
        noise(o, at + 0.019f, 0.003f, 0.6f * g, 0.7f, true, 31)
        tone(o, at + 0.019f, 2700f, 0.01f, 0.18f * g)
    }

    /** A burst of noise decaying from [at]: low-passed ([lp] 0..1, smaller = darker), or high-passed. */
    private fun noise(o: FloatArray, at: Float, decay: Float, gain: Float, lp: Float, highPass: Boolean, seed: Int) {
        val rnd = java.util.Random(seed.toLong())
        val start = (at * SR).toInt()
        var y = 0f
        var i = start
        while (i < o.size) {
            val t = (i - start).toFloat() / SR
            val env = exp(-t / decay)
            if (env < 0.001f) break
            val x = rnd.nextFloat() * 2 - 1
            y += lp * (x - y)
            o[i] += (if (highPass) x - y else y) * env * gain
            i++
        }
    }

    /** A ringing partial from [at]; [drop] lowers its pitch as it fades (1 = steady). */
    private fun tone(o: FloatArray, at: Float, freq: Float, decay: Float, gain: Float, drop: Float = 1f) {
        val start = (at * SR).toInt()
        var phase = 0.0
        var i = start
        while (i < o.size) {
            val t = (i - start).toFloat() / SR
            val env = exp(-t / decay)
            if (env < 0.001f) break
            val f = freq * (drop + (1 - drop) * env)
            phase += 2 * PI * f / SR
            o[i] += (sin(phase) * env * gain).toFloat()
            i++
        }
    }

    /** An instant camera's motor pushing the print out: a buzzing hum, rising and falling. */
    private fun motor(o: FloatArray, from: Float, to: Float) {
        val a = (from * SR).toInt(); val b = (to * SR).toInt().coerceAtMost(o.size)
        val rnd = java.util.Random(40)
        var phase = 0.0
        var y = 0f
        for (i in a until b) {
            val t = (i - a).toFloat() / SR
            val len = (b - a).toFloat() / SR
            val env = (t / 0.05f).coerceAtMost(1f) * ((len - t) / 0.12f).coerceIn(0f, 1f)
            val f = 95.0 * (1 + 0.02 * sin(2 * PI * 7 * t)) * (if (len - t < 0.15f) 0.85 + 0.15 * (len - t) / 0.15 else 1.0)
            phase += 2 * PI * f / SR
            var s = 0.0
            for (h in 1..6) s += sin(phase * h) / h
            y += 0.15f * ((rnd.nextFloat() * 2 - 1) - y)
            o[i] += ((s * 0.2 + y * 0.08) * env).toFloat()
        }
    }

    /**
     * Evens a sound to the phone's click: its loudest 50 ms brought to the click's (an RMS of
     * about 0.13), its peak never past 0.9.
     */
    private fun even(o: FloatArray) {
        var peak = 0f
        for (v in o) peak = maxOf(peak, kotlin.math.abs(v))
        if (peak <= 0f) return
        val w = (0.05f * SR).toInt().coerceAtLeast(1)
        var loudest = 0.0
        var i = 0
        while (i + w <= o.size) {
            var sum = 0.0
            for (j in i until i + w) sum += (o[j] * o[j]).toDouble()
            loudest = maxOf(loudest, kotlin.math.sqrt(sum / w))
            i += w / 4
        }
        if (loudest <= 0.0) loudest = peak.toDouble()
        val gain = minOf(0.13f / loudest.toFloat(), 0.9f / peak)
        for (k in o.indices) o[k] *= gain
    }

    private fun writeWav(f: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray()); head.putInt(36 + samples.size * 2); head.put("WAVE".toByteArray())
        head.put("fmt ".toByteArray()); head.putInt(16); head.putShort(1); head.putShort(1)
        head.putInt(SR); head.putInt(SR * 2); head.putShort(2); head.putShort(16)
        head.put("data".toByteArray()); head.putInt(samples.size * 2)
        val tmp = File(f.path + ".part")
        FileOutputStream(tmp).use { it.write(head.array()); it.write(data.array()) }
        tmp.renameTo(f)
    }
}
