package org.photoall.film.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color

/**
 * AI 相机's filter, chosen from the picture: what the scene is (night, people, black and white,
 * high key, overcast, warm light, rich and soft colour, leaves, sky and water, the city, the
 * street) read from a
 * small copy — brightness, how vivid, which hues and how much of each, a face or not — and for each
 * scene a natural film stock or two from the bundled LUTs, the one that fits the picture's warmth,
 * light and colour taken, laid at half strength: the picture stays itself.
 */
object SceneLook {
    class Look(val scene: String, val lutId: String, val label: String, val strength: Float = 0.7f)

    /** What a picture is like, read from a small copy. Shares are of the picture; hue shares of its colour. */
    class Traits(
        val luma: Float, val sat: Float, val contrast: Float, val warmth: Float, val vivid: Float,
        val green: Float, val blue: Float, val cyan: Float, val warm: Float, val yellow: Float, val red: Float, val pink: Float,
        val face: Boolean,
    )

    private fun look(scene: String, id: String, label: String, k: Float = 0.5f) = Look(scene, id, label, k)

    /** The scene and its look, from [photo] (any size). */
    fun choose(photo: Bitmap): Look = choose(traits(photo))

    /**
     * The scenes, each with natural film looks only (colour negative and slide stocks, two quiet
     * black-and-whites; no cinema grades, no neon, no fades), all laid at half strength (black
     * and white at 70 %) so the picture stays itself. The first scene that fits is taken.
     */
    fun choose(t: Traits): Look = when {
        // ---- the dark
        t.luma < 0.22f -> when {
            t.warmth < -0.02f -> look("夜景", "negative_new/fuji_800z", "Fuji 800Z")
            else -> look("夜景", "negative_new/kodak_portra_800", "Portra 800")
        }
        // ---- people
        t.face -> when {
            t.luma < 0.35f -> look("人像", "negative_new/kodak_portra_800", "Portra 800")
            t.luma > 0.62f -> look("人像", "negative_new/kodak_portra_160", "Portra 160")
            t.green > 0.4f -> look("人像", "negative_new/fuji_400h", "Fuji 400H")
            t.sat < 0.25f -> look("人像", "negative_color/kodak_portra_160_nc", "Portra 160 NC")
            else -> look("人像", "negative_new/kodak_portra_400", "Portra 400")
        }
        // ---- tone more than colour
        t.sat < 0.1f && t.contrast > 0.25f -> when {
            t.luma > 0.55f -> look("黑白光影", "bw/fuji_neopan_acros_100", "Acros 100", 0.7f)
            else -> look("黑白光影", "bw/ilford_hp5_plus_400", "Ilford HP5", 0.7f)
        }
        t.luma > 0.72f && t.sat < 0.18f -> when {
            t.warmth < 0f -> look("高调", "negative_new/fuji_160c", "Fuji 160C")
            else -> look("高调", "colorslide/fuji_astia_100f", "Astia 100F")
        }
        t.sat < 0.16f && t.contrast < 0.14f -> when {
            t.blue + t.cyan > 0.4f -> look("阴天", "negative_new/fuji_160c", "Fuji 160C")
            else -> look("阴天", "negative_color/kodak_portra_160_nc", "Portra 160 NC")
        }
        // ---- warm light
        t.warm > 0.55f && t.luma < 0.6f -> when {
            t.sat > 0.35f -> look("暖光", "negative_new/kodak_portra_400", "Portra 400")
            else -> look("暖光", "negative_color/kodak_elite_color_200", "Kodak Elite 200")
        }
        t.warm + t.yellow > 0.6f && t.blue < 0.15f -> look("暖色", "negative_color/kodak_elite_color_200", "Kodak Elite 200")
        // ---- rich colour, soft colour
        t.red > 0.25f && t.sat > 0.4f && t.luma > 0.4f -> look("浓郁色彩", "colorslide/fuji_provia_100f", "Provia 100F")
        t.pink > 0.2f || (t.luma > 0.6f && t.sat in 0.15f..0.35f && t.pink + t.blue + t.cyan > 0.55f) ->
            look("柔和色彩", "negative_new/fuji_400h", "Fuji 400H")
        // ---- leaves
        t.green > 0.45f -> when {
            t.luma < 0.4f -> look("绿植", "negative_new/fuji_160c", "Fuji 160C")
            t.warmth > 0.05f -> look("绿植", "negative_new/kodak_portra_400", "Portra 400")
            else -> look("绿植", "negative_new/fuji_400h", "Fuji 400H")
        }
        // ---- sky and water (a cool cast on grey is not sky: the blues must be blue enough)
        t.blue + t.cyan > 0.45f && t.sat > 0.2f -> when {
            t.luma < 0.38f -> look("天空与水", "negative_new/fuji_160c", "Fuji 160C")
            else -> look("天空与水", "colorslide/fuji_provia_100f", "Provia 100F")
        }
        // ---- the city, the street
        t.blue + t.cyan > 0.3f && t.sat < 0.3f -> look("城市", "negative_color/fuji_superia_reala_100", "Superia Reala 100")
        t.warmth > 0.05f -> look("街景", "negative_color/kodak_elite_color_200", "Kodak Elite 200")
        t.green + t.blue > 0.45f -> look("街景", "negative_color/fuji_superia_200", "Superia 200")
        else -> look("街景", "negative_color/fuji_superia_reala_100", "Superia Reala 100")
    }

    /** The picture's traits from a 64 px copy. */
    fun traits(photo: Bitmap): Traits {
        val n = 64
        val s = Bitmap.createScaledBitmap(photo, n, (n * photo.height / photo.width.coerceAtLeast(1)).coerceAtLeast(1), true)
        val px = IntArray(s.width * s.height); s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        if (s !== photo) s.recycle()
        val hsv = FloatArray(3)
        var luma = 0f; var sat = 0f; var warmth = 0f; var vivid = 0; var lo = 0; var hi = 0
        var green = 0f; var blue = 0f; var cyan = 0f; var warm = 0f; var yellow = 0f; var red = 0f; var pink = 0f
        for (c in px) {
            Color.colorToHSV(c, hsv)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val l = (0.3f * r + 0.59f * g + 0.11f * b) / 255f
            luma += l; sat += hsv[1]; warmth += (r - b) / 255f
            if (l < 0.12f) lo++; if (l > 0.85f) hi++
            if (hsv[1] > 0.55f && hsv[2] > 0.5f) vivid++
            val w = hsv[1] * hsv[2]
            when (hsv[0]) {
                in 10f..40f -> warm += w
                in 40f..65f -> yellow += w
                in 75f..165f -> green += w
                in 165f..200f -> cyan += w
                in 200f..255f -> blue += w
                in 290f..345f -> pink += w
                else -> if (hsv[0] < 10f || hsv[0] >= 345f) red += w
            }
        }
        val count = px.size.toFloat()
        val colour = (green + blue + cyan + warm + yellow + red + pink).coerceAtLeast(1e-3f)
        return Traits(luma / count, sat / count, (lo + hi) / count, warmth / count, vivid / count,
            green / colour, blue / colour, cyan / colour, warm / colour, yellow / colour, red / colour, pink / colour,
            hasFace(photo))
    }

    /**
     * A face in the picture (the system's own detector, on a small copy): a portrait. Colour alone
     * cannot tell skin from a beige curtain.
     */
    private fun hasFace(photo: Bitmap): Boolean = runCatching {
        val w = 320 and 1.inv() // the detector wants an even width
        val h = (w * photo.height / photo.width.coerceAtLeast(1))
        val small = Bitmap.createScaledBitmap(photo, w, h, true).copy(Bitmap.Config.RGB_565, false)
        val found = android.media.FaceDetector(w, h, 3).findFaces(small, arrayOfNulls(3))
        small.recycle()
        found > 0
    }.getOrDefault(false)

    /**
     * [photo] through [look]'s LUT (33 points, RGB8, red fastest), mixed at its strength, in place
     * of a copy. Worker thread.
     */
    fun apply(context: Context, photo: Bitmap, look: Look): Bitmap {
        val table = context.assets.open("luts/${look.lutId}.rgb").use { it.readBytes() }
        val n = 33
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width; val h = out.height
        val row = IntArray(w)
        val k = look.strength
        val f = (n - 1) / 255f
        fun at(r: Int, g: Int, b: Int, ch: Int) = table[((b * n + g) * n + r) * 3 + ch].toInt() and 255
        for (y in 0 until h) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val R = (c shr 16 and 255); val G = (c shr 8 and 255); val B = (c and 255)
                val fr = R * f; val fg = G * f; val fb = B * f
                val r0 = fr.toInt().coerceAtMost(n - 2); val g0 = fg.toInt().coerceAtMost(n - 2); val b0 = fb.toInt().coerceAtMost(n - 2)
                val dr = fr - r0; val dg = fg - g0; val db = fb - b0
                var o = 0
                for (ch in 0..2) {
                    // trilinear between the eight neighbours
                    val c00 = at(r0, g0, b0, ch) * (1 - dr) + at(r0 + 1, g0, b0, ch) * dr
                    val c10 = at(r0, g0 + 1, b0, ch) * (1 - dr) + at(r0 + 1, g0 + 1, b0, ch) * dr
                    val c01 = at(r0, g0, b0 + 1, ch) * (1 - dr) + at(r0 + 1, g0, b0 + 1, ch) * dr
                    val c11 = at(r0, g0 + 1, b0 + 1, ch) * (1 - dr) + at(r0 + 1, g0 + 1, b0 + 1, ch) * dr
                    val v = (c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db
                    val src = when (ch) { 0 -> R; 1 -> G; else -> B }
                    val m = (src + (v - src) * k).toInt().coerceIn(0, 255)
                    o = o or (m shl (16 - 8 * ch))
                }
                row[x] = (c and 0xff000000.toInt()) or o
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }
}
