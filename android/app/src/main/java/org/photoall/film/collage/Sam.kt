package org.photoall.film.collage

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.FloatBuffer

/**
 * Cutting out what the user points at (框选 / 涂抹 in 修整): MobileSAM (Zhang et al. 2023, MIT;
 * Segment Anything's mask decoder, Apache-2.0), as ONNX from Acly/MobileSAM. Unlike the
 * saliency model ([Segmenter]), which finds whatever stands out, it takes a prompt — a box,
 * points on the subject — and returns that object, so a subject the first pass missed can be
 * shown to it. The photo is encoded once (longest side 1024; the model normalises), then each
 * prompt is a quick decode.
 */
object Sam {
    private const val SIDE = 1024
    @Volatile private var encoder: OrtSession? = null
    @Volatile private var decoder: OrtSession? = null
    /** The last photo's encoding: every prompt on the same photo reuses it. */
    private var cached: Pair<Bitmap, FloatArray>? = null

    private fun sessions(context: Context): Pair<OrtSession, OrtSession> = synchronized(this) {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val e = encoder ?: env.createSession(context.assets.open("segment/mobile_sam_encoder.onnx").use { it.readBytes() }, opts).also { encoder = it }
        val d = decoder ?: env.createSession(context.assets.open("segment/sam_decoder.onnx").use { it.readBytes() }, opts).also { decoder = it }
        e to d
    }

    private fun embed(context: Context, photo: Bitmap, enc: OrtSession): FloatArray = synchronized(this) {
        cached?.let { (b, e) -> if (b === photo) return e }
        val k = SIDE.toFloat() / maxOf(photo.width, photo.height)
        val w = maxOf(1, Math.round(photo.width * k)); val h = maxOf(1, Math.round(photo.height * k))
        val small = Bitmap.createScaledBitmap(photo, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== photo) small.recycle()
        val input = FloatBuffer.allocate(w * h * 3)
        for (p in px) { input.put(((p shr 16) and 255).toFloat()); input.put(((p shr 8) and 255).toFloat()); input.put((p and 255).toFloat()) }
        input.rewind()
        val env = OrtEnvironment.getEnvironment()
        val emb = OnnxTensor.createTensor(env, input, longArrayOf(h.toLong(), w.toLong(), 3)).use { t ->
            enc.run(mapOf(enc.inputNames.first() to t)).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        cached = photo to emb
        return emb
    }

    /**
     * The object the user showed: in [box] (photo pixels), or, given [points] on it (x, y pairs,
     * from 涂抹's strokes), the object under them. A mask [photo]'s size, white, its alpha the
     * object. Worker thread.
     */
    fun mask(context: Context, photo: Bitmap, box: Rect, points: FloatArray = FloatArray(0)): Bitmap {
        val (enc, dec) = sessions(context)
        val emb = embed(context, photo, enc)
        val k = SIDE.toFloat() / maxOf(photo.width, photo.height)
        // points (label 1) and SAM's empty-box pad (label -1); or the box as two corners (labels 2, 3)
        val usePoints = points.size >= 2
        val n = if (usePoints) points.size / 2 + 1 else 2
        val coords = FloatArray(n * 2); val labels = FloatArray(n)
        if (usePoints) {
            for (i in 0 until points.size / 2) { coords[2 * i] = points[2 * i] * k; coords[2 * i + 1] = points[2 * i + 1] * k; labels[i] = 1f }
            labels[n - 1] = -1f
        } else {
            coords[0] = box.left * k; coords[1] = box.top * k; labels[0] = 2f
            coords[2] = box.right * k; coords[3] = box.bottom * k; labels[1] = 3f
        }
        val env = OrtEnvironment.getEnvironment()
        val W = photo.width; val H = photo.height
        val logits: FloatArray
        val tensors = listOf(
            OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 256, 64, 64)),
            OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n.toLong(), 2)),
            OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n.toLong())),
            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256)),
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(H.toFloat(), W.toFloat())), longArrayOf(2)),
        )
        try {
            val names = listOf("image_embeddings", "point_coords", "point_labels", "mask_input", "has_mask_input", "orig_im_size")
            logits = dec.run(names.zip(tensors).toMap()).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                FloatArray(W * H).also { fb.get(it) }
            }
        } finally { tensors.forEach { it.close() } }
        // logits to alpha: a narrow soft step about zero, so the edge is clean but not jagged
        val row = IntArray(W)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (y in 0 until H) {
            for (x in 0 until W) {
                val v = ((logits[y * W + x] + 0.6f) / 1.2f).coerceIn(0f, 1f)
                row[x] = ((v * v * (3 - 2 * v) * 255).toInt() shl 24) or 0xffffff
            }
            out.setPixels(row, 0, W, 0, y, W, 1)
        }
        return out
    }

    /** The encoding dropped (the photo changed or the screen closed). */
    fun forget() = synchronized(this) { cached = null }
}
