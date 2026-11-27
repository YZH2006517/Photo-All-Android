package org.photoall.film.collage

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.nio.FloatBuffer

/**
 * The subject of a photo cut out, on the phone: U²-Net's small model (u2netp, Qin et al.
 * 2020, Apache-2.0, as packed by rembg) run by ONNX Runtime. The photo goes in at 320 x 320,
 * the saliency map comes out, is stretched back over the photo, its edge eased, and the
 * photo's pixels kept where it says; then cropped to the subject.
 */
object Segmenter {
    private const val SIZE = 320
    @Volatile private var session: OrtSession? = null

    private fun session(context: Context): OrtSession = session ?: synchronized(this) {
        session ?: run {
            val env = OrtEnvironment.getEnvironment()
            val model = context.assets.open("segment/u2netp.onnx").use { it.readBytes() }
            env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }).also { session = it }
        }
    }

    /** The subject of [photo] on a clear ground, cropped to it (null: nothing found). Worker thread. */
    fun cut(context: Context, photo: Bitmap): Bitmap? = compose(photo, mask(context, photo))

    /**
     * Where the subject is: a picture [photo]'s size, white, its alpha the subject (255 in it,
     * eased at the edge). Worker thread.
     */
    fun mask(context: Context, photo: Bitmap): Bitmap {
        val s = session(context)
        val env = OrtEnvironment.getEnvironment()
        val small = Bitmap.createScaledBitmap(photo, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE); small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        var max = 1f
        for (p in px) max = maxOf(max, ((p shr 16) and 255).toFloat(), ((p shr 8) and 255).toFloat(), (p and 255).toFloat())
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatBuffer.allocate(3 * SIZE * SIZE)
        for (c in 0 until 3) for (i in px.indices) {
            val v = ((px[i] shr (16 - 8 * c)) and 255) / max
            input.put(c * SIZE * SIZE + i, (v - mean[c]) / std[c])
        }
        val name = s.inputNames.first()
        val map = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            s.run(mapOf(name to tensor)).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                FloatArray(SIZE * SIZE) { fb.get(it) }
            }
        }
        // normalised as rembg does, then the edge eased: a soft step between 0.3 and 0.7
        val lo = map.minOrNull() ?: 0f; val hi = map.maxOrNull() ?: 1f
        val mask = IntArray(SIZE * SIZE)
        if (hi - lo > 1e-4f) for (i in map.indices) {
            val v = ((map[i] - lo) / (hi - lo) - 0.3f) / 0.4f
            val a = (v.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } * 255).toInt()
            mask[i] = (a shl 24) or 0xffffff
        }
        val m = Bitmap.createBitmap(mask, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(m, photo.width, photo.height, true).copy(Bitmap.Config.ARGB_8888, true).also { m.recycle() }
    }

    /**
     * The subject inside [box] only (框选 / 涂抹 in 修整: the user shows where to look when the
     * whole photo finds nothing, or the wrong thing): the model run on that part alone, its
     * answer placed back on a mask [photo]'s size, clear outside the box. Worker thread.
     */
    fun maskIn(context: Context, photo: Bitmap, box: Rect): Bitmap {
        val out = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val r = Rect(box)
        if (!r.intersect(0, 0, photo.width, photo.height) || r.width() < 8 || r.height() < 8) return out
        val part = Bitmap.createBitmap(photo, r.left, r.top, r.width(), r.height())
        val m = mask(context, part)
        Canvas(out).drawBitmap(m, r.left.toFloat(), r.top.toFloat(), null)
        m.recycle(); if (part !== photo) part.recycle()
        return out
    }

    /** [photo] kept where [mask] is opaque, cropped to it with a little room (null: nothing kept). */
    fun compose(photo: Bitmap, mask: Bitmap): Bitmap? {
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        // a photo decoded from a JPEG is marked opaque, and a copy keeps the mark: without this the
        // cut-out is saved and drawn as a square (its alpha ignored)
        out.setHasAlpha(true)
        Canvas(out).drawBitmap(mask, null, android.graphics.RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        // the faintest haze round the subject dropped, so the border follows the subject alone
        val w = out.width; val row = IntArray(w)
        for (y in 0 until out.height) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) if ((row[x] ushr 24) < 20) row[x] = 0
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        val box = bounds(out) ?: return null.also { out.recycle() }
        val pad = (maxOf(box.width(), box.height()) * 0.02f).toInt()
        box.inset(-pad, -pad); box.intersect(0, 0, out.width, out.height)
        return Bitmap.createBitmap(out, box.left, box.top, box.width(), box.height()).also { if (it !== out) out.recycle() }
    }

    /** Where anything opaque lies (alpha over a quarter), or null. */
    fun bounds(b: Bitmap): Rect? {
        val w = b.width; val h = b.height
        val row = IntArray(w)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) if ((row[x] ushr 24) > 64) {
                if (x < l) l = x; if (x > r) r = x
                if (y < t) t = y; bt = y
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, bt + 1)
    }
}
