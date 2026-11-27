package org.photoall.film.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The sticker library: each sticker is the cut-out subject (kept as it came, cut_<id>.png)
 * and the sticker drawn from it in its edge style (stk_<id>.png), in the app's own files;
 * the list (style, width, favourite, when made and last used) in preferences.
 */
object Stickers {
    enum class Edge(val label: String) { WHITE("白边"), THICK("粗白边"), TORN("撕纸"), LINE("描线"), NONE("无边") }

    /** [auto]: cut out by 一键拼贴, kept in a folder of its own in the library. */
    class Sticker(val id: String, val created: Long, var used: Long, var fav: Boolean, var edge: Edge, var width: Float, val auto: Boolean = false)

    private fun dir(c: Context) = File(c.filesDir, "stickers").apply { mkdirs() }
    fun cutFile(c: Context, id: String) = File(dir(c), "cut_$id.png")
    fun file(c: Context, id: String) = File(dir(c), "stk_$id.png")
    private fun prefs(c: Context) = c.getSharedPreferences("stickers", Context.MODE_PRIVATE)

    fun list(c: Context): List<Sticker> = runCatching {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Sticker(o.getString("id"), o.optLong("created"), o.optLong("used"), o.optBoolean("fav"),
                runCatching { Edge.valueOf(o.optString("edge", "WHITE")) }.getOrDefault(Edge.WHITE), o.optDouble("width", 1.0).toFloat(),
                o.optBoolean("auto"))
        }.filter { file(c, it.id).exists() }
    }.getOrDefault(emptyList())

    private fun write(c: Context, list: List<Sticker>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("id", s.id).put("created", s.created).put("used", s.used).put("fav", s.fav)
            .put("edge", s.edge.name).put("width", s.width.toDouble()).put("auto", s.auto))
        prefs(c).edit().putString("list", a.toString()).apply()
    }

    /** A new sticker from a cut-out subject, in [edge] at [width]. Worker thread. */
    fun add(c: Context, cut: Bitmap, edge: Edge = Edge.WHITE, width: Float = 1f, auto: Boolean = false): Sticker {
        cut.setHasAlpha(true)
        val id = System.currentTimeMillis().toString(36) + (0..999).random()
        cutFile(c, id).outputStream().use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val s = Sticker(id, System.currentTimeMillis(), System.currentTimeMillis(), false, edge, width, auto)
        save(c, s, cut)
        write(c, listOf(s) + list(c))
        return s
    }

    /** The sticker drawn again in its (changed) style. Worker thread. */
    fun restyle(c: Context, s: Sticker) {
        val cut = BitmapFactory.decodeFile(cutFile(c, s.id).path) ?: return
        save(c, s, cut)
        cut.recycle()
        write(c, list(c).map { if (it.id == s.id) s else it })
    }

    fun update(c: Context, s: Sticker) = write(c, list(c).map { if (it.id == s.id) s else it })

    fun touch(c: Context, id: String) = write(c, list(c).map { if (it.id == id) it.apply { used = System.currentTimeMillis() } else it })

    fun duplicate(c: Context, s: Sticker): Sticker? {
        val cut = BitmapFactory.decodeFile(cutFile(c, s.id).path) ?: return null
        return add(c, cut, s.edge, s.width).also { cut.recycle() }
    }

    fun delete(c: Context, id: String) {
        file(c, id).delete(); cutFile(c, id).delete()
        write(c, list(c).filter { it.id != id })
    }

    fun load(c: Context, id: String): Bitmap? = BitmapFactory.decodeFile(file(c, id).path)

    private fun save(c: Context, s: Sticker, cut: Bitmap) {
        val out = render(cut, s.edge, s.width)
        file(c, s.id).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
    }

    /**
     * The sticker: the subject on a border of white card that follows its outline (rounded,
     * or torn with a rough edge, or a fine ink line, or none), a soft shadow under it all.
     * The border is the subject's shape grown by [width] x 2.5 % of its size: its alpha
     * blurred and cut at a low level, on a small copy, then laid over at full size.
     */
    fun render(cut: Bitmap, edge: Edge, width: Float): Bitmap {
        val size = max(cut.width, cut.height)
        val grow = when (edge) { Edge.WHITE -> 0.026f; Edge.THICK -> 0.05f; Edge.TORN -> 0.045f; Edge.LINE -> 0.012f; Edge.NONE -> 0f } * width * size
        val shadowR = size * 0.02f
        val pad = (grow + shadowR * 2.2f).roundToInt() + 2
        val W = cut.width + 2 * pad; val H = cut.height + 2 * pad
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shape = if (grow > 0f) outline(cut, pad, W, H, grow, torn = edge == Edge.TORN) else
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(cut, pad.toFloat(), pad.toFloat(), null) }
        // the shadow: the whole shape, blurred, a little below
        val off = IntArray(2)
        val shade = shape.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(shadowR, BlurMaskFilter.Blur.NORMAL) }, off)
        c.drawBitmap(shade, off[0].toFloat(), off[1] + shadowR * 0.5f, Paint().apply { color = 0x4d000000 })
        shade.recycle()
        if (grow > 0f) {
            val card = if (edge == Edge.LINE) 0xff1c1c1e.toInt() else 0xfffbfaf7.toInt()
            c.drawBitmap(shape, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(card, PorterDuff.Mode.SRC_IN) })
            if (edge == Edge.LINE) {
                // a fine line: white just inside the ink, so the subject keeps a clean edge
                val inner = outline(cut, pad, W, H, grow * 0.45f, torn = false)
                c.drawBitmap(inner, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(0xfffbfaf7.toInt(), PorterDuff.Mode.SRC_IN) })
                inner.recycle()
            }
        }
        shape.recycle()
        c.drawBitmap(cut, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** The subject's shape grown by [grow] px (alpha only, white), as a W x H picture. */
    private fun outline(cut: Bitmap, pad: Int, W: Int, H: Int, grow: Float, torn: Boolean): Bitmap {
        val k = minOf(1f, 360f / max(W, H))
        val w = max(8, (W * k).roundToInt()); val h = max(8, (H * k).roundToInt())
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).apply { scale(k, k); drawBitmap(cut, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG)) }
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        var a = FloatArray(w * h) { (px[it] ushr 24) / 255f }
        val r = max(1, (grow * k / 2.2f).roundToInt())
        repeat(3) { a = boxBlur(a, w, h, r) }
        val rnd = java.util.Random(7)
        // torn: the cut line wanders (coarse noise) and frays (fine noise)
        val gx = 9; val gy = 9
        val coarse = FloatArray((gx + 1) * (gy + 1)) { rnd.nextFloat() }
        for (y in 0 until h) for (x in 0 until w) {
            var lvl = 0.06f
            if (torn) {
                val fx = x.toFloat() / w * gx; val fy = y.toFloat() / h * gy
                val ix = fx.toInt().coerceAtMost(gx - 1); val iy = fy.toInt().coerceAtMost(gy - 1)
                val tx = fx - ix; val ty = fy - iy
                val n = (coarse[iy * (gx + 1) + ix] * (1 - tx) + coarse[iy * (gx + 1) + ix + 1] * tx) * (1 - ty) +
                    (coarse[(iy + 1) * (gx + 1) + ix] * (1 - tx) + coarse[(iy + 1) * (gx + 1) + ix + 1] * tx) * ty
                lvl = 0.03f + 0.16f * n + 0.05f * rnd.nextFloat()
            }
            val v = ((a[y * w + x] - lvl) / 0.04f).coerceIn(0f, 1f)
            px[y * w + x] = ((v * 255).toInt() shl 24) or 0xffffff
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        val big = Bitmap.createScaledBitmap(small, W, H, true)
        small.recycle()
        return big
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(w * h)
        for (y in 0 until h) {
            var s = 0f
            for (x in -r..r) s += a[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                t[y * w + x] = s / (2 * r + 1)
                s += a[y * w + (x + r + 1).coerceAtMost(w - 1)] - a[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        val o = FloatArray(w * h)
        for (x in 0 until w) {
            var s = 0f
            for (y in -r..r) s += t[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                o[y * w + x] = s / (2 * r + 1)
                s += t[(y + r + 1).coerceAtMost(h - 1) * w + x] - t[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return o
    }

    /** A photo from the album, its long side at most [edge] px, upright. */
    fun decode(c: Context, uri: android.net.Uri, edge: Int = 1600): Bitmap? = runCatching {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
            val k = minOf(1f, edge.toFloat() / max(info.size.width, info.size.height))
            d.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()
}
