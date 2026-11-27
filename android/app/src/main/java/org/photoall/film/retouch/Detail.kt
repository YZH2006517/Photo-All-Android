package org.photoall.film.retouch

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * AI 清晰: fine detail brought back. Real-ESRGAN general x4v3 (Wang et al., xinntao, BSD-3;
 * the ONNX export from Qualcomm AI Hub) restores and enlarges a half-size copy four times,
 * the result is brought back to the picture's own size and mixed over it by [strength]:
 * a cleaner, sharper picture at the same size. Tile by tile, with an overlap so no seams.
 */
class Detail(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    // the weights sit beside the graph in a file of their own: both copied out, the graph opened by its path
    private val session: OrtSession = env.createSession(unpack(context).path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
    private val input = session.inputNames.first()
    /** The model's tile size (a fixed input), or 128 when it takes any. */
    private val tile: Int = (session.inputInfo[input]?.info as? ai.onnxruntime.TensorInfo)?.shape
        ?.let { s -> s.getOrNull(2)?.toInt()?.takeIf { it > 0 } } ?: 128
    /** Whether the model takes channels last (N H W C) rather than first. */
    private val channelsLast: Boolean = (session.inputInfo[input]?.info as? ai.onnxruntime.TensorInfo)?.shape
        ?.let { it.size == 4 && it[3] == 3L } ?: false

    /** [photo] with its detail restored, same size. [progress] gets 0..1 as tiles finish. Worker thread. */
    fun enhance(photo: Bitmap, strength: Float, progress: (Float) -> Unit = {}): Bitmap {
        val half = Bitmap.createScaledBitmap(photo, maxOf(1, photo.width / 2), maxOf(1, photo.height / 2), true)
        val w = half.width; val h = half.height
        val src = IntArray(w * h); half.getPixels(src, 0, w, 0, 0, w, h); half.recycle()
        // the restored picture at twice the half size (the photo's own), built tile by tile
        val W = w * 2; val H = h * 2
        val out = IntArray(W * H)
        val pad = 8
        val step = tile - 2 * pad
        val tilesX = (w + step - 1) / step; val tilesY = (h + step - 1) / step
        var done = 0
        val buf = FloatBuffer.allocate(3 * tile * tile)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val x0 = tx * step - pad; val y0 = ty * step - pad
            buf.clear()
            for (c in 0 until 3) for (y in 0 until tile) for (x in 0 until tile) {
                val sx = (x0 + x).coerceIn(0, w - 1); val sy = (y0 + y).coerceIn(0, h - 1)
                val v = (src[sy * w + sx] shr (16 - 8 * c) and 255) / 255f
                if (channelsLast) buf.put((y * tile + x) * 3 + c, v) else buf.put(c * tile * tile + y * tile + x, v)
            }
            val shape = if (channelsLast) longArrayOf(1, tile.toLong(), tile.toLong(), 3) else longArrayOf(1, 3, tile.toLong(), tile.toLong())
            OnnxTensor.createTensor(env, buf, shape).use { t ->
                session.run(mapOf(input to t)).use { r ->
                    val o = (r[0] as OnnxTensor).floatBuffer
                    val T = tile * 4
                    // keep the middle of the tile (its overlap dropped), shrunk from 4x to 2x
                    for (y in pad until tile - pad) for (x in pad until tile - pad) {
                        val gx = x0 + x; val gy = y0 + y
                        if (gx !in 0 until w || gy !in 0 until h) continue
                        for (dy in 0..1) for (dx in 0..1) {
                            var r0 = 0f; var g0 = 0f; var b0 = 0f
                            for (k in 0..3) { // a 2x2 average of the 4x output = the 2x pixel
                                val ox = x * 4 + dx * 2 + (k and 1); val oy = y * 4 + dy * 2 + (k shr 1)
                                if (channelsLast) {
                                    val i = (oy * T + ox) * 3
                                    r0 += o.get(i); g0 += o.get(i + 1); b0 += o.get(i + 2)
                                } else {
                                    val i = oy * T + ox
                                    r0 += o.get(i); g0 += o.get(T * T + i); b0 += o.get(2 * T * T + i)
                                }
                            }
                            fun ch(v: Float) = (v / 4f * 255f + 0.5f).toInt().coerceIn(0, 255)
                            out[(gy * 2 + dy) * W + gx * 2 + dx] = (0xff shl 24) or (ch(r0) shl 16) or (ch(g0) shl 8) or ch(b0)
                        }
                    }
                }
            }
            progress(++done / (tilesX * tilesY).toFloat())
        }
        // mixed over the original by strength (the original's size may be one pixel odd)
        val pw = photo.width; val ph = photo.height
        val orig = IntArray(pw * ph); photo.getPixels(orig, 0, pw, 0, 0, pw, ph)
        val k = strength.coerceIn(0f, 1f)
        for (y in 0 until ph) for (x in 0 until pw) {
            val a = orig[y * pw + x]
            val b = out[minOf(y, H - 1) * W + minOf(x, W - 1)]
            fun mix(s: Int) = ((a shr s and 255) * (1 - k) + (b shr s and 255) * k).toInt()
            orig[y * pw + x] = (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        return Bitmap.createBitmap(orig, pw, ph, Bitmap.Config.ARGB_8888)
    }

    override fun close() = session.close()

    companion object {
        const val MODEL = "retouch/real_esrgan_general_x4v3.onnx"
        private const val WEIGHTS = "retouch/real_esrgan_general_x4v3.data"
        fun available(context: Context) = runCatching { context.assets.open(MODEL).close(); context.assets.open(WEIGHTS).close(); true }.getOrDefault(false)

        /** The graph and its weights in the app's files (copied once per install); the graph's file. */
        private fun unpack(context: Context): java.io.File {
            val dir = java.io.File(context.filesDir, "models").apply { mkdirs() }
            var graph = java.io.File(dir, "x")
            for (name in listOf(WEIGHTS, MODEL)) {
                val f = java.io.File(dir, name.substringAfterLast('/'))
                val size = context.assets.openFd(name).use { it.length }
                if (f.length() != size) context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
                if (name == MODEL) graph = f
            }
            return graph
        }
    }
}
