package org.photoall.film.collage

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import java.nio.FloatBuffer

/**
 * Where a picture is best framed: GAIC (Zeng et al., grid anchor based image cropping; MIT), a
 * MobileNetV2 trained on photographers' ratings of thousands of crops, scores the candidate
 * framings of a picture (a 12 x 12 grid's corners, each over half the picture, no thinner than
 * 1:2) and the whole frame. The best framing, and how much better than the frame as it is, guide
 * the AI camera while it frames and crop its shots after.
 */
object Composition {
    /** [box] in shares of the picture; [gain]: its score over the whole frame's (0: no better). */
    class Result(val box: RectF, val gain: Float)

    private var session: OrtSession? = null
    private val boxes = HashMap<Long, FloatArray>()

    private fun session(context: Context): OrtSession = synchronized(this) {
        session ?: OrtEnvironment.getEnvironment().createSession(
            context.assets.open("compose/gaic_mobilenet.onnx").use { it.readBytes() },
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }).also { session = it }
    }

    /** The repo's candidates for a [w] x [h] input (x1, y1, x2, y2 each), then the whole frame last. */
    private fun candidates(w: Int, h: Int): FloatArray = boxes.getOrPut((w.toLong() shl 32) or h.toLong()) {
        val bins = 12f; val sh = h / bins; val sw = w / bins
        val out = ArrayList<Float>()
        for (x1 in 0 until 4) for (y1 in 0 until 4) for (x2 in 8 until 12) for (y2 in 8 until 12) {
            val ratio = (y2 - y1) * sw / (x2 - x1) / sh
            if ((x2 - x1) * (y2 - y1) > 0.4999f * bins * bins && ratio > 0.5f && ratio < 2f)
                out += listOf(sw * (0.5f + y1), sh * (0.5f + x1), sw * (0.5f + y2), sh * (0.5f + x2))
        }
        out += listOf(0f, 0f, w - 1f, h - 1f)
        out.toFloatArray()
    }

    /** The best framing of [picture] (any size; read at 256 on its short side). Worker thread. */
    fun analyze(context: Context, picture: Bitmap): Result {
        val portrait = picture.height >= picture.width
        val w = if (portrait) 256 else 352; val h = if (portrait) 352 else 256
        val small = Bitmap.createScaledBitmap(picture, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== picture) small.recycle()
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatBuffer.allocate(3 * w * h)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in px.indices) input.put(c * w * h + i, (((px[i] shr shift) and 255) / 256f - mean[c]) / std[c])
        }
        val b = candidates(w, h)
        val n = b.size / 4
        val env = OrtEnvironment.getEnvironment()
        val s = session(context)
        val scores = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { img ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(b), longArrayOf(n.toLong(), 4)).use { bx ->
                s.run(mapOf("image" to img, "boxes" to bx)).use { r ->
                    val fb = (r[0] as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        }
        var best = 0
        for (i in 0 until n - 1) if (scores[i] > scores[best]) best = i
        val box = RectF(b[best * 4] / w, b[best * 4 + 1] / h, (b[best * 4 + 2] + 1) / w, (b[best * 4 + 3] + 1) / h)
        return Result(box, scores[best] - scores[n - 1])
    }

    /**
     * [r]'s framing for a shot: the picture's own shape kept, about the framing's centre, no less
     * than [least] of the picture; null when the framing is not worth a crop.
     */
    fun cropFor(r: Result, aspect: Float, least: Float = 0.85f): RectF? {
        if (r.gain < 0.35f) return null
        val area = (r.box.width() * r.box.height()).coerceIn(least, 1f)
        if (area > 0.985f) return null
        // the picture's own shape: equal shares each way
        val side = kotlin.math.sqrt(area)
        val cx = r.box.centerX().coerceIn(side / 2, 1 - side / 2)
        val cy = r.box.centerY().coerceIn(side / 2, 1 - side / 2)
        return RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    }
}
