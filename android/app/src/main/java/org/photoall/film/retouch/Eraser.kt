package org.photoall.film.retouch

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * AI 消除: what was painted over, filled in from what is round it. MI-GAN (Sargsyan et al.,
 * ICCV 2023, Picsart AI Research, MIT), its whole pipeline in one ONNX file: the photo and a
 * mask in (uint8, 0 where to fill), the photo out with the hole filled, any size (it works
 * on a crop round the hole). One instance per worker thread.
 */
class Eraser(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open(MODEL).use { env.createSession(it.readBytes(), OrtSession.SessionOptions()) }

    /** [photo] with the pixels where [mask] is opaque (alpha > 127) filled in. Same size; [mask] is scaled to it. */
    fun erase(photo: Bitmap, mask: Bitmap): Bitmap {
        val w = photo.width; val h = photo.height
        val m = if (mask.width == w && mask.height == h) mask else Bitmap.createScaledBitmap(mask, w, h, true)
        val px = IntArray(w * h); photo.getPixels(px, 0, w, 0, 0, w, h)
        val mk = IntArray(w * h); m.getPixels(mk, 0, w, 0, 0, w, h)
        if (m !== mask) m.recycle()
        val plane = w * h
        val image = ByteBuffer.allocateDirect(3 * plane)
        val holes = ByteBuffer.allocateDirect(plane)
        for (i in 0 until plane) {
            val c = px[i]
            image.put(i, (c shr 16 and 255).toByte())
            image.put(plane + i, (c shr 8 and 255).toByte())
            image.put(2 * plane + i, (c and 255).toByte())
            holes.put(i, if ((mk[i] ushr 24) > 127) 0 else 255.toByte())
        }
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        val out = OnnxTensor.createTensor(env, image, shape, ai.onnxruntime.OnnxJavaType.UINT8).use { img ->
            OnnxTensor.createTensor(env, holes, longArrayOf(1, 1, h.toLong(), w.toLong()), ai.onnxruntime.OnnxJavaType.UINT8).use { msk ->
                session.run(mapOf("image" to img, "mask" to msk)).use { r ->
                    val b = (r[0] as OnnxTensor).byteBuffer
                    IntArray(plane) { i ->
                        if ((mk[i] ushr 24) <= 127) px[i] // outside the hole the photo stays exactly itself
                        else (0xff shl 24) or ((b.get(i).toInt() and 255) shl 16) or ((b.get(plane + i).toInt() and 255) shl 8) or (b.get(2 * plane + i).toInt() and 255)
                    }
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() = session.close()

    companion object {
        const val MODEL = "retouch/migan.onnx"
        fun available(context: Context) = runCatching { context.assets.open(MODEL).close(); true }.getOrDefault(false)
    }
}
