package org.photoall.film.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import org.photoall.film.engine.DevelopOptions
import org.photoall.film.engine.FilmEngine
import org.photoall.film.engine.REFERENCE_EXPOSURE
import org.photoall.film.settings.LutLibrary
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 冲洗过程动画: one photo walked through the film engine, nothing but the pictures, each the
 * engine's own output with the later stages switched off: the scene, the exposed negative
 * (scanned as it is, orange base and all), the print (or the lab scan), halation, then grain
 * and the LUT, the finished picture. The steps are developed one after another (an engine of
 * the show's own, on a thread of its own) while the first ones already play. Shown full screen
 * for an import ([DevelopShowActivity]) and over the viewfinder for a shot.
 *
 * [spec] carries what to show (see [DevelopShowActivity]'s extras); [onClose] is called when
 * it is done with (a tap after the last picture).
 */
class DevelopShowView(context: Context, private val spec: Intent, private val onClose: () -> Unit) : FrameLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var engine: FilmEngine? = null

    private val steps: List<() -> Bitmap?>
    private val frames = HashMap<Int, Bitmap>()
    private var shown = -1
    private var waiting = false
    private var done = false

    private var front = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private var back = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }

    // Worker thread: the source, read once.
    private var picture: Bitmap? = null
    private var raw: Any? = null // a DNG path, or a camera RAW's bytes

    init {
        setBackgroundColor(0xff000000.toInt())
        addView(back, LayoutParams(-1, -1))
        addView(front, LayoutParams(-1, -1))
        // a tap: on to the next step (when it is developed); at the end, closed
        setOnClickListener { if (done) close() else { main.removeCallbacks(next); advance() } }
        LutLibrary.load(context)
        val keys = spec.getStringArrayExtra(DevelopShowActivity.EXTRA_KEYS) ?: emptyArray()
        val values = spec.getFloatArrayExtra(DevelopShowActivity.EXTRA_VALUES) ?: FloatArray(0)
        steps = plan(keys.indices.associate { keys[it] to values.getOrElse(it) { 0f } })
        worker.execute { renderAll() }
    }

    /** Stops the developing and lets the engine go; the shot's own copy of its DNG goes too. */
    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.execute {
            runCatching { engine?.close() }
            engine = null
            if (spec.getBooleanExtra(DevelopShowActivity.EXTRA_DELETE, false))
                spec.getStringExtra(DevelopShowActivity.EXTRA_DNG)?.let { File(it).delete() }
        }
        worker.shutdown()
        onClose()
    }

    // ---- the steps ---------------------------------------------------------------------------

    private fun plan(params: Map<String, Float>): List<() -> Bitmap?> {
        val filmOn = (params["stage film"] ?: 1f) != 0f
        val film = (params["film"] ?: 0f).toInt()
        val positive = FilmEngine.filmStocks.getOrNull(film)?.contains("positive") == true
        val halation = (params["halation"] ?: 1f) != 0f
        fun dev(extra: Map<String, Float>, withLut: Boolean = false) = { develop(params + extra, withLut) }
        val list = mutableListOf<() -> Bitmap?>()
        // the scene (a RAW: its light without film; a picture: as it came)
        list += { if (raw != null) develop(params + mapOf("stage film" to 0f, "stage print" to 0f), false) else picture }
        if (!filmOn) return list + dev(emptyMap(), true)
        // the exposed film: a negative scanned as it is, a slide as it is
        list += if (positive) dev(mapOf("stage print" to 0f, "grain" to 0f, "halation" to 0f))
            else dev(mapOf("stage print" to 0f, "stage negative" to 1f, "grain" to 0f, "halation" to 0f))
        // the print (or the lab scan, or the slide itself)
        list += dev(mapOf("grain" to 0f, "halation" to 0f))
        if (halation) list += dev(mapOf("grain" to 0f))
        // grain and the LUT: the finished picture
        list += dev(emptyMap(), true)
        return list
    }

    /** Worker thread: one step's picture from the engine, at the show's size and one seed (the grain stays put). */
    private fun develop(params: Map<String, Float>, withLut: Boolean): Bitmap? {
        val e = engine ?: FilmEngine.create(context.assets).also { engine = it }
        val lut = spec.getIntExtra(DevelopShowActivity.EXTRA_LUT, 0)
        val strength = spec.getFloatExtra(DevelopShowActivity.EXTRA_LUT_STRENGTH, 0f)
        if (withLut && lut > 0 && LutLibrary.digicam(lut) == null && strength > 0f)
            e.setCreativeLut(LutLibrary.table(context, lut), LutLibrary.size, strength)
        else e.setCreativeLut(null, LutLibrary.size, 0f)
        val ev = spec.getFloatExtra(DevelopShowActivity.EXTRA_EV, 0f)
        val meter = spec.getBooleanExtra(DevelopShowActivity.EXTRA_METER, true)
        raw?.let { source ->
            (source as? java.nio.ByteBuffer)?.rewind()
            val got = runCatching {
                e.developDng(listOf(source), params + ("stage restore" to 0f), DevelopOptions(maxLongEdge = EDGE, exposureEv = ev,
                    seed = SEED, displayP3 = false, matchKey = if (meter) REFERENCE_EXPOSURE else 0f, localTone = 0.6f)).bitmap
            }.getOrNull()
            if (got != null || picture == null) return got
            raw = null // the picture's way from here on, as the import itself falls back
        }
        val p = picture ?: return null
        return e.develop(p, params, DevelopOptions(maxLongEdge = EDGE, exposureEv = ev, seed = SEED, displayP3 = false)).bitmap
    }

    private fun renderAll() {
        spec.getStringExtra(DevelopShowActivity.EXTRA_DNG)?.let { if (File(it).exists()) raw = it }
        spec.getStringExtra(DevelopShowActivity.EXTRA_URI)?.let { s ->
            val uri = Uri.parse(s)
            picture = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                    val k = minOf(1f, EDGE.toFloat() / max(info.size.width, info.size.height))
                    d.setTargetSize(max(1, (info.size.width * k).roundToInt()), max(1, (info.size.height * k).roundToInt()))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    d.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
            }.getOrNull()
            if (raw == null && spec.getBooleanExtra(DevelopShowActivity.EXTRA_AS_RAW, false)) raw = RawSource.read(context.contentResolver, uri)
        }
        if (picture == null && raw == null) {
            main.post { close() }
            return
        }
        for ((i, step) in steps.withIndex()) {
            if (closed) return
            val bitmap = runCatching { step() }.onFailure { android.util.Log.w("DevelopShow", "step $i", it) }.getOrNull()
            main.post {
                if (closed) return@post
                frames[i] = bitmap ?: frames[i - 1] ?: return@post // a step that failed shows the one before
                if (shown < 0 || waiting) advance()
            }
        }
    }

    // ---- the player --------------------------------------------------------------------------

    private val next = Runnable { advance() }

    /** The next step, when it is developed; until then the player waits on the one shown. */
    private fun advance() {
        val i = shown + 1
        if (i > steps.lastIndex) { done = true; return }
        val frame = frames[i]
        if (frame == null) { waiting = true; return }
        waiting = false
        shown = i
        // the new picture fades in over the last, which then steps behind it
        back.animate().cancel()
        back.setImageBitmap(frame)
        back.alpha = 0f
        back.bringToFront()
        back.animate().alpha(1f).setDuration(FADE).withEndAction {
            val was = front
            front = back
            back = was
        }.start()
        main.removeCallbacks(next)
        main.postDelayed(next, if (i == 0) HOLD_FIRST else HOLD)
    }

    companion object {
        private const val EDGE = 1280
        private const val SEED = 7
        private const val HOLD = 900L
        private const val HOLD_FIRST = 700L
        private const val FADE = 380L
    }
}
