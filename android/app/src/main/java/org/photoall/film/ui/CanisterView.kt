package org.photoall.film.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/**
 * A 135 film cartridge drawn from the front, as the film card's picture: spool post
 * up, the light-trap lip on the right with the leader out, end caps in brushed metal.
 * The label is this app's own art (stock colours, name, ISO), never a maker's logo.
 * At larger sizes the DX CAS contacts are drawn as the standard sets them for the
 * stock's ISO, 36 exposures and +3/-1 latitude. Motion-picture stocks (no DX) get a
 * reloadable cassette with a taped, hand-written label.
 *
 * Geometry is in a 124 x 160 box, scaled to fit the view.
 */
class CanisterView(context: Context) : View(context) {
    /** The engine's stock name ("kodak portra 400"); null draws an empty, dim cartridge. */
    var stock: String? = null
        set(value) {
            if (field == value) return
            field = value
            look = value?.let { lookOf(it) }
            invalidate()
        }

    private class Look(val name: String, val iso: Int, val base: Int, val band: Int, val rule: Int,
                       val ink: Int, val bandInk: Int, val cine: String? = null)

    private var look: Look? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; isFakeBoldText = true }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("casual", Typeface.BOLD) }
    private val rect = RectF()
    private var metal: Shader? = null
    private var shade: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Gradients in box units (the canvas is scaled to the box).
        metal = LinearGradient(12f, 0f, 104f, 0f, intArrayOf(0xff5a5853.toInt(), 0xffe6e3dc.toInt(), 0xffa9a69f.toInt(), 0xff4a4843.toInt()),
            floatArrayOf(0f, 0.3f, 0.55f, 1f), Shader.TileMode.CLAMP)
        shade = LinearGradient(16f, 0f, 98f, 0f, intArrayOf(0x73000000, 0x38ffffff, 0x00ffffff, 0x80000000.toInt()),
            floatArrayOf(0f, 0.28f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width / BOX_W, height / BOX_H)
        canvas.save()
        canvas.translate((width - BOX_W * s) / 2, (height - BOX_H * s) / 2)
        canvas.scale(s, s)
        val look = look
        if (look == null) canvas.saveLayerAlpha(0f, 0f, BOX_W, BOX_H, 90)
        drawShell(canvas)
        if (look?.cine != null) drawCassette(canvas, look) else drawLabel(canvas, look, detailed = width >= 56 * resources.displayMetrics.density)
        // cylinder shading over the body
        paint.shader = shade
        canvas.drawRect(16f, 22f, 98f, 138f, paint)
        paint.shader = null
        if (look == null) canvas.restore()
        canvas.restore()
    }

    private fun drawShell(c: Canvas) {
        // leader (C-41 base) out of the lip, with perforations
        paint.color = 0xffb8642a.toInt()
        rect.set(100f, 54f, 123f, 104f); c.drawRoundRect(rect, 5f, 5f, paint)
        paint.color = 0xff3a1d0c.toInt()
        var y = 58f
        while (y < 100f) {
            rect.set(104f, y, 107.4f, y + 4.4f); c.drawRoundRect(rect, 0.8f, 0.8f, paint)
            rect.set(114f, y, 117.4f, y + 4.4f); c.drawRoundRect(rect, 0.8f, 0.8f, paint)
            y += 7f
        }
        paint.shader = metal
        rect.set(49f, 0f, 71f, 13f); c.drawRoundRect(rect, 2f, 2f, paint)       // spool post
        rect.set(12f, 10f, 104f, 23f); c.drawRoundRect(rect, 3.5f, 3.5f, paint) // top cap
        rect.set(12f, 137f, 104f, 149f); c.drawRoundRect(rect, 3.5f, 3.5f, paint)
        rect.set(53f, 148f, 67f, 155f); c.drawRoundRect(rect, 2f, 2f, paint)
        paint.shader = null
        paint.color = 0xff2a2926.toInt(); c.drawRect(58f, 0f, 62f, 7f, paint)    // rewind fork slot
        paint.color = 0xff151412.toInt()
        rect.set(96f, 26f, 106f, 134f); c.drawRoundRect(rect, 2f, 2f, paint)     // lip, black velvet
        paint.color = 0xff2e2b27.toInt(); c.drawRect(104f, 28f, 106f, 132f, paint)
    }

    private fun drawLabel(c: Canvas, look: Look?, detailed: Boolean) {
        val l = look ?: Look("", 0, 0xff3a3733.toInt(), 0xff2c2a27.toInt(), 0xff22201e.toInt(), 0, 0)
        paint.color = l.base; c.drawRect(16f, 22f, 98f, 138f, paint)
        paint.color = l.band; c.drawRect(16f, 30f, 98f, 52f, paint)
        paint.color = l.rule; c.drawRect(16f, 52f, 98f, 55f, paint)
        if (look == null) return
        text.color = l.bandInk
        text.textSize = if (l.name.length < 7) 12f else 9.5f
        text.letterSpacing = 0.05f
        c.drawText(l.name, 22f, 46f, text)
        mono.color = l.ink
        mono.textSize = 20f
        c.drawText(l.iso.toString(), 22f, 76f, mono)
        if (!detailed) return
        mono.textSize = 6.5f
        mono.alpha = 190
        c.drawText("135-36 · ISO ${l.iso}", 22f, 88f, mono)
        mono.alpha = 255
        // DX CAS: two columns of six along the axis, ground at the spool-post end.
        val (speed, rest) = cas(l.iso)
        for (i in 0 until 6) for ((col, row) in listOf(speed, rest).withIndex()) {
            paint.color = if (row[i]) 0xffd8d6cf.toInt() else 0xff121110.toInt()
            c.drawRect(78f + col * 7, 98f + i * 6, 84.4f + col * 7, 103.4f + i * 6, paint)
        }
        // the cartridge barcode between the contacts and the lip
        paint.color = 0xff141210.toInt()
        var y = 98f
        var bit = l.iso * 7 + l.name.length
        while (y < 133f) {
            val h = if (bit and 1 == 0) 0.8f else 2f
            c.drawRect(91f, y, 96f, y + h, paint)
            y += h + if (bit and 2 == 0) 0.8f else 1.6f
            bit = bit * 1103515245 + 12345 ushr 3
        }
    }

    private fun drawCassette(c: Canvas, look: Look) {
        paint.shader = metal; c.drawRect(16f, 22f, 98f, 138f, paint); paint.shader = null
        paint.color = 0xff3b3935.toInt()
        var x = 18f
        while (x < 98f) { c.drawRect(x, 23f, x + 1.4f, 28f, paint); c.drawRect(x, 132f, x + 1.4f, 137f, paint); x += 4f }
        c.save()
        c.rotate(-3f, 57f, 78f)
        paint.color = 0xffe8ddb5.toInt()
        rect.set(22f, 58f, 92f, 98f); c.drawRoundRect(rect, 1.5f, 1.5f, paint)
        hand.color = 0xff2a2622.toInt(); hand.textSize = 16f; hand.textAlign = Paint.Align.CENTER
        c.drawText(look.name, 57f, 79f, hand)
        hand.color = 0xff5a524a.toInt(); hand.textSize = 10f
        c.drawText("${look.cine} · 36", 57f, 92f, hand)
        c.restore()
    }

    companion object {
        private const val BOX_W = 124f
        private const val BOX_H = 160f

        // DX CAS (I3A table): which speed contacts S1..S5 are bare metal, for the speeds our
        // stocks use; another speed draws its nearest. 36 exposures, +3/-1 latitude.
        private val SPEED = mapOf(50 to "14", 100 to "24", 160 to "245", 200 to "124", 250 to "125",
            400 to "34", 500 to "35", 800 to "134")

        fun cas(iso: Int): Pair<BooleanArray, BooleanArray> {
            val on = SPEED[iso] ?: SPEED.minByOrNull { kotlin.math.abs(it.key - iso) }!!.value
            val speed = BooleanArray(6) { i -> i == 0 || on.contains(('0' + i)) }
            // G, L1 L2 L3 (36 = L3), T1 T2 (+3/-1 = both)
            val rest = booleanArrayOf(true, false, false, true, true, true)
            return speed to rest
        }

        /** Our label colours for the engine's stocks: a family look, no maker's artwork. */
        private fun lookOf(stock: String): Look {
            val s = stock.lowercase()
            val iso = Regex("(\\d+)[a-z]?$").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 400
            val cream = 0xffefe7d4.toInt()
            val dark = 0xff2a1e14.toInt()
            val light = 0xfff6efe3.toInt()
            val ink = 0xff141210.toInt()
            Regex("vision3 (\\d+[dt])").find(s)?.let { m ->
                val speed = m.groupValues[1].uppercase()
                val code = mapOf("50D" to "5203", "250D" to "5207", "200T" to "5213", "500T" to "5219")[speed] ?: "52xx"
                return Look(speed, iso, 0, 0, 0, 0, 0, cine = code)
            }
            return when {
                "portra" in s -> Look("PORTRA", iso, cream, when (iso) { 160 -> 0xffe9c27a.toInt(); 800 -> 0xffd9762b.toInt(); else -> 0xffe0a030.toInt() },
                    0xff6b3a1e.toInt(), dark, ink)
                "ektar" in s -> Look("EKTAR", iso, 0xffb8322a.toInt(), 0xff141210.toInt(), 0xffe9b323.toInt(), light, light)
                "gold" in s -> Look("GOLD", iso, 0xffe9b323.toInt(), 0xffc8372d.toInt(), dark, dark, light)
                "ultramax" in s -> Look("ULTRAMAX", iso, 0xff3e4fa0.toInt(), 0xffe9b323.toInt(), 0xffc8372d.toInt(), light, ink)
                "xtra" in s || "superia" in s -> Look("X-TRA", iso, 0xff2e7d4f.toInt(), 0xfff4f1ea.toInt(), 0xffc8372d.toInt(), light, dark)
                "400h" in s -> Look("PRO 400H", iso, 0xffdde8da.toInt(), 0xff2e8a7a.toInt(), 0xff1e4d44.toInt(), 0xff14302a.toInt(), light)
                else -> {
                    // any other stock: a neutral label with its own name
                    val name = s.split(' ').filter { it.isNotEmpty() && it !in setOf("kodak", "fujifilm", "fuji", "ilford") }
                        .dropLast(1).joinToString(" ").uppercase().ifEmpty { "FILM" }
                    Look(name, iso, 0xffe9e4da.toInt(), 0xff6f6a62.toInt(), 0xff2a2622.toInt(), dark, light)
                }
            }
        }

        /** "kodak portra 400" -> "Portra 400", "kodak vision3 500t" -> "Vision3 500T". */
        fun displayName(stock: String): String = stock.split(' ')
            .filter { it.isNotEmpty() && it !in setOf("kodak", "fujifilm", "fuji", "ilford") }
            .joinToString(" ") { w ->
                when {
                    w.first().isDigit() -> w.uppercase()
                    w == "xtra" -> "X-TRA"
                    else -> w.replaceFirstChar { it.uppercase() }
                }
            }
    }
}
