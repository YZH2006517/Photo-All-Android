package org.photoall.film.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How a 135 cartridge of a film stock looks: its label's colours and wording, and the drawing of it. */
object FilmLabel {
    class Look(val base: Int, val band: Int, val ink: Int, val maker: String, val title: String, val iso: String)

    private val face: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun pretty(name: String) = name.replace(Regex("""\s*(push|pull)\s*\d+""", RegexOption.IGNORE_CASE), "").trim()
        .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    fun of(name: String): Look {
        val n = name.lowercase().replace(Regex("""\s*(push|pull)\s*\d+"""), "").trim()
        val iso = Regex("\\d{2,4}").findAll(n).lastOrNull()?.value ?: ""
        val maker = n.substringBefore(' ').uppercase()
        val title = n.substringAfter(' ', n).replace(iso, "").trim().uppercase().ifEmpty { maker }
        fun look(base: Long, band: Long, ink: Long) = Look(base.toInt(), band.toInt(), ink.toInt(), maker, title, iso)
        return when {
            "portra" in n -> look(0xffe9dfc6, 0xff5d4a8f, 0xff1c1a1a)
            "ektar" in n -> look(0xffecb220, 0xffb9262b, 0xff1a1a1a)
            "gold" in n -> look(0xffe2a31c, 0xff7f1d1d, 0xff1a1a1a)
            "ultramax" in n || "colorplus" in n -> look(0xffecb220, 0xff1f4fa8, 0xff1a1a1a)
            "tri-x" in n || "tmax" in n || "t-max" in n -> look(0xffecb220, 0xff161616, 0xff161616)
            "vision" in n -> look(0xff1b1b1c, 0xffecb220, 0xfff2eedf)
            "kodak" in n -> look(0xffecb220, 0xffb9262b, 0xff1a1a1a)
            "velvia" in n -> look(0xff1f7a45, 0xffa82a66, 0xfff7f4ea)
            "provia" in n || "astia" in n -> look(0xff1f7a45, 0xff2a5fb0, 0xfff7f4ea)
            "acros" in n || "neopan" in n -> look(0xff171717, 0xff1f7a45, 0xfff2eedf)
            "fuji" in n -> look(0xff1f7a45, 0xfff1efe8, 0xfff7f4ea)
            "ilford" in n || "hp5" in n || "fp4" in n || "delta" in n -> look(0xfff1efe8, 0xff161616, 0xff161616)
            "agfa" in n -> look(0xffd95f2a, 0xff16406e, 0xfff7f4ea)
            "cinestill" in n -> look(0xff171717, 0xffb9262b, 0xfff2eedf)
            "polaroid" in n -> look(0xfff1efe8, 0xff2a5fb0, 0xff161616)
            else -> {
                val hue = ((n.hashCode() and 0x7fffffff) % 360).toFloat()
                Look(Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.78f)), Color.HSVToColor(floatArrayOf((hue + 150) % 360, 0.6f, 0.4f)),
                    0xff161616.toInt(), maker, title, iso)
            }
        }
    }

    private const val BW = 320
    private const val BH = 236
    private val flats = HashMap<String, android.graphics.Bitmap>()
    private val src = android.graphics.Rect()
    private val dst = RectF()

    /** The label unrolled: half the way round the shell, the part the front shows. */
    /** The flat label for [Cartridge3D] to wrap round its shell. */
    fun flatFor(name: String): android.graphics.Bitmap = flat(name)

    private fun flat(name: String): android.graphics.Bitmap = flats.getOrPut(name) {
        val look = of(name)
        val b = android.graphics.Bitmap.createBitmap(BW, BH, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val mid = BW / 2f
        c.drawColor(look.base)
        p.color = look.band
        c.drawRect(0f, 0f, BW.toFloat(), BH * 0.17f, p)
        c.drawRect(0f, BH * 0.94f, BW.toFloat(), BH.toFloat(), p)
        p.typeface = face
        p.textAlign = Paint.Align.CENTER
        // the maker's name runs round the band
        p.color = if (Color.luminance(look.band) > 0.5f) 0xff161616.toInt() else 0xfff4f1e8.toInt()
        p.textSize = BH * 0.085f
        for (x in listOf(mid - BW * 0.36f, mid, mid + BW * 0.36f)) c.drawText(look.maker, x, BH * 0.125f, p)
        p.color = look.ink
        p.textSize = BH * 0.17f
        val tw = p.measureText(look.title)
        if (tw > BW * 0.44f) p.textSize *= BW * 0.44f / tw
        c.drawText(look.title, mid, BH * 0.42f, p)
        p.textSize = BH * 0.31f
        c.drawText(look.iso, mid, BH * 0.72f, p)
        p.textSize = BH * 0.075f
        c.drawText("135 · 36 EXP", mid, BH * 0.86f, p)
        val code = name.hashCode() and 0xfff
        // DX code toward the lip: bare metal and black paint, two columns of six
        val cell = BH * 0.085f
        val dx0 = BW * 0.79f
        val dy0 = BH * 0.27f
        p.color = 0xffc4c3be.toInt()
        c.drawRect(dx0, dy0, dx0 + cell * 2, dy0 + cell * 6, p)
        p.color = 0xff141415.toInt()
        for (i in 0 until 12) if ((code shr i) and 1 == 1)
            c.drawRect(dx0 + (i % 2) * cell, dy0 + (i / 2) * cell, dx0 + (i % 2 + 1) * cell, dy0 + (i / 2 + 1) * cell, p)
        // a bar code on the other side
        p.color = look.ink
        var x = BW * 0.1f
        var i = 0
        while (x < BW * 0.21f) {
            val bar = 1.5f + ((code shr (i % 11)) and 3)
            c.drawRect(x, BH * 0.3f, x + bar, BH * 0.78f, p)
            x += bar + 2.5f
            i++
        }
        // a hairline rule under the band, and a tiny printed line along the foot, as on real stock
        p.color = look.ink
        p.alpha = 150
        c.drawRect(BW * 0.08f, BH * 0.205f, BW * 0.92f, BH * 0.212f, p)
        p.alpha = 255
        p.textSize = BH * 0.045f
        p.textAlign = Paint.Align.LEFT
        c.drawText("FOR COLOR PRINTS · PROCESS C-41 · ISO ${look.iso}/${(10 * kotlin.math.log10((look.iso.toFloatOrNull() ?: 100f).toDouble()) + 1).toInt()}°", BW * 0.06f, BH * 0.915f, p)
        p.textAlign = Paint.Align.CENTER
        // wear: hairline scuffs through the print, where it has been handled
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.color = 0x38ffffff
        for (k in 0 until 5) {
            val sx = BW * (((code shr k) and 15) / 15f)
            val sy = BH * (0.2f + 0.7f * (((code shr (k + 3)) and 15) / 15f))
            c.drawLine(sx, sy, sx + BW * 0.16f, sy + BH * 0.03f * (k - 2), p)
        }
        b
    }

    private fun metal(p: Paint, x0: Float, x1: Float) {
        p.shader = LinearGradient(x0, 0f, x1, 0f, intArrayOf(0xff3a3936.toInt(), 0xffa5a49f.toInt(), 0xff8d8c87.toInt(), 0xff34332f.toInt()),
            floatArrayOf(0f, 0.34f, 0.62f, 1f), Shader.TileMode.CLAMP)
    }

    /**
     * A cartridge standing on [base], [h] tall, centred on [cx]: the spool's knob, the crimped
     * steel caps, the printed shell with its DX code, the felt lip and [leader] of film out of
     * it. The light is fixed upper left; a thin reflection on the steel moves with the phone.
     */
    // ----- light: one lamp, above and to the left, a little in front; the viewer straight on -----
    private const val LX = -0.52f
    private const val LY = -0.38f
    private const val LZ = 0.76f

    /**
     * How a vertical cylinder is lit across its width, as two ramps sampled at [n] points:
     * the shade it gets (black, its alpha), and the reflection it shows (white, its alpha).
     * [gloss] is how tight the reflection is (printed varnish ~ 18, satin steel ~ 9 drawn wide),
     * [spec] how strong. Lambert for the shade with some light bounced back from the room; Blinn-Phong for the reflection.
     */
    private fun lighting(l: Float, r: Float, gloss: Float, spec: Float, ambient: Float, n: Int = 24): Pair<LinearGradient, LinearGradient> {
        val shade = IntArray(n)
        val shine = IntArray(n)
        val pos = FloatArray(n)
        // the half vector between the light and the eye
        var hx = LX; var hy = LY * 0.35f; var hz = LZ + 1f
        val hl = kotlin.math.sqrt(hx * hx + hy * hy + hz * hz)
        hx /= hl; hy /= hl; hz /= hl
        for (i in 0 until n) {
            val u = -1f + 2f * i / (n - 1)
            val nx = u
            val nz = kotlin.math.sqrt(maxOf(0f, 1f - u * u))
            val diffuse = maxOf(0f, nx * LX + nz * LZ)
            val lit = (ambient + (1f - ambient) * diffuse).coerceIn(0f, 1f)
            // the rim facing away picks up a little of the room again
            val rim = 0.12f * maxOf(0f, u) * maxOf(0f, u) * maxOf(0f, u)
            shade[i] = Color.argb(((1f - lit - rim).coerceIn(0f, 1f) * 235).toInt(), 0, 0, 0)
            val nh = maxOf(0f, nx * hx + nz * hz)
            shine[i] = Color.argb((spec * Math.pow(nh.toDouble(), gloss.toDouble()).toFloat() * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            pos[i] = i / (n - 1f)
        }
        return LinearGradient(l, 0f, r, 0f, shade, pos, Shader.TileMode.CLAMP) to
            LinearGradient(l, 0f, r, 0f, shine, pos, Shader.TileMode.CLAMP)
    }

    private val lights = HashMap<Long, Pair<LinearGradient, LinearGradient>>()

    internal fun lit(c: Canvas, p: Paint, l: Float, top: Float, r: Float, bottom: Float, gloss: Float, spec: Float, ambient: Float, radius: Float = 0f) {
        val key = (l.toBits().toLong() shl 32) xor r.toBits().toLong() xor (gloss.toBits().toLong() shl 7) xor spec.toBits().toLong()
        val (shade, shine) = lights.getOrPut(key) { if (lights.size > 400) lights.clear(); lighting(l, r, gloss, spec, ambient) }
        p.style = Paint.Style.FILL
        p.shader = shade
        if (radius > 0f) c.drawRoundRect(l, top, r, bottom, radius, radius, p) else c.drawRect(l, top, r, bottom, p)
        p.shader = shine
        if (radius > 0f) c.drawRoundRect(l, top, r, bottom, radius, radius, p) else c.drawRect(l, top, r, bottom, p)
        p.shader = null
    }

    /**
     * A 135 cartridge standing on [base], [h] tall, centred on [cx], straight on:
     * the black plastic spool end with its slot, the tinplate shell closed by two crimped
     * steel caps with their ribs, the printed label round the shell (varnished, so it has
     * a soft reflection), the DX code in bare metal, the light trap in black velvet on the
     * right with [leader] of film out of it, and a short stub of spool under the bottom cap.
     */
    fun draw(c: Canvas, p: Paint, path: Path, cx: Float, base: Float, h: Float, name: String, d: Float, leader: Float = 0.14f, shadow: Float = 1f) {
        val w = h * 0.56f
        val top = base - h
        val knobH = h * 0.07f
        val capH = h * 0.072f
        val stubH = h * 0.022f
        val bodyTop = top + knobH + capH
        val bodyBottom = base - capH - stubH
        val l = cx - w / 2
        val r = cx + w / 2
        val capL = l - w * 0.035f
        val capR = r + w * 0.035f
        p.shader = null
        p.style = Paint.Style.FILL

        // contact shadow on the shelf: tight and dark under it, wider and soft away from the lamp
        p.color = Color.argb((60 * shadow).toInt().coerceIn(0, 255), 0, 0, 0)
        c.drawOval(l - w * 0.1f, base - 4 * d, r + w * 0.75f, base + 6 * d, p)
        p.color = Color.argb((170 * shadow).toInt().coerceIn(0, 255), 0, 0, 0)
        c.drawOval(l + w * 0.02f, base - 2 * d, r + w * 0.22f, base + 2.4f * d, p)

        // the light trap: a lip standing off the shell on the right, faced with black velvet
        val lipL = r - w * 0.06f
        val lipR = r + w * 0.17f
        val lipTop = bodyTop + capH * 0.15f
        val lipBottom = bodyBottom - capH * 0.15f
        p.color = 0xff5d5c58.toInt()
        c.drawRect(lipL, lipTop, lipR - w * 0.05f, lipBottom, p) // the tinplate of the lip
        lit(c, p, lipL, lipTop, lipR - w * 0.05f, lipBottom, 9f, 0.35f, 0.5f)
        p.color = 0xff0c0c0d.toInt()
        c.drawRoundRect(lipR - w * 0.07f, lipTop + 1.5f * d, lipR, lipBottom - 1.5f * d, 1.5f * d, 1.5f * d, p)
        p.color = 0xff1c1b1d.toInt() // the velvet's pile, catching a little light
        var vy = lipTop + 3 * d
        while (vy < lipBottom - 3 * d) { c.drawRect(lipR - w * 0.06f, vy, lipR - w * 0.015f, vy + 0.6f * d, p); vy += 1.8f * d }
        if (leader > 0f) {
            val fy0 = bodyTop + (bodyBottom - bodyTop) * 0.1f
            val fy1 = bodyBottom - (bodyBottom - bodyTop) * 0.1f
            val mid = (fy0 + fy1) / 2
            val end = lipR + w * leader * 2.2f
            path.reset()
            path.moveTo(lipR - d, fy0)
            path.lineTo(lipR + w * leader * 0.5f, fy0)
            path.quadTo(lipR + w * leader * 0.9f, fy0, lipR + w * leader * 1.1f, mid)
            path.lineTo(end - 3 * d, mid)
            path.quadTo(end, mid, end, mid + 3 * d)
            path.lineTo(end, fy1)
            path.lineTo(lipR - d, fy1)
            path.close()
            p.color = 0xff5a3a22.toInt() // the leader is film base: amber-brown, a little translucent
            c.drawPath(path, p)
            p.color = 0x40ffd9a8
            c.drawRect(lipR, fy0 + 1 * d, end - 4 * d, fy0 + 2.2f * d, p)
            p.color = 0xff1a120c.toInt()
            val pitch = h * 0.05f
            var x = lipR + 2 * d
            while (x < end - pitch) { c.drawRoundRect(x, fy1 - h * 0.042f, x + pitch * 0.48f, fy1 - h * 0.016f, 0.8f * d, 0.8f * d, p); x += pitch }
        }

        // the stub of spool under the bottom cap
        p.color = 0xff171718.toInt()
        c.drawRect(cx - w * 0.11f, base - stubH - 1 * d, cx + w * 0.11f, base, p)
        lit(c, p, cx - w * 0.11f, base - stubH - 1 * d, cx + w * 0.11f, base, 14f, 0.22f, 0.45f)

        // the printed shell: the label wrapped round it, then the lamp on it
        val bmp = flat(name)
        val radius = w / 2
        val strips = 40
        p.isFilterBitmap = true
        for (k in 0 until strips) {
            val xa = l + w * k / strips
            val xb = l + w * (k + 1) / strips
            val ua = kotlin.math.asin(((xa - cx) / radius).coerceIn(-1f, 1f)) / Math.PI.toFloat() + 0.5f
            val ub = kotlin.math.asin(((xb - cx) / radius).coerceIn(-1f, 1f)) / Math.PI.toFloat() + 0.5f
            src.set((ua * BW).toInt(), 0, maxOf((ub * BW).toInt(), (ua * BW).toInt() + 1), BH)
            dst.set(xa, bodyTop, xb + 0.6f, bodyBottom)
            c.drawBitmap(bmp, src, dst, p)
        }
        lit(c, p, l, bodyTop, r, bodyBottom, 22f, 0.5f, 0.62f)
        // the shell's seam: the tinplate is rolled and locked down one side, under the label's edge
        p.color = 0x33000000
        c.drawRect(l + w * 0.11f, bodyTop, l + w * 0.115f, bodyBottom, p)

        // the caps: steel, a deep crimp onto the shell, two ribs pressed round them, their edge rolled
        for (bottomCap in listOf(false, true)) {
            val ct = if (bottomCap) bodyBottom - 1 * d else top + knobH
            val cb = if (bottomCap) base - stubH else bodyTop + 1 * d
            p.color = 0xffa9a8a3.toInt()
            c.drawRoundRect(capL, ct, capR, cb, 2.2f * d, 2.2f * d, p)
            // the ribs, and the crimp where the cap bites the shell
            val crimpY = if (bottomCap) ct + 0.8f * d else cb - 1.6f * d
            p.color = 0xff4e4d49.toInt()
            c.drawRect(capL, crimpY, capR, crimpY + 0.9f * d, p)
            for (k in 1..2) {
                val y = ct + (cb - ct) * (if (bottomCap) 0.3f + k * 0.2f else 0.12f + k * 0.2f)
                p.color = 0xff6f6e6a.toInt()
                c.drawRect(capL, y, capR, y + 0.7f * d, p)
                p.color = 0xffd2d1cc.toInt()
                c.drawRect(capL, y + 0.7f * d, capR, y + 1.2f * d, p)
            }
            lit(c, p, capL, ct, capR, cb, 7f, 0.75f, 0.42f, 2.2f * d)
            // the rolled edge: lit on top, dark below
            p.color = 0x66ffffff
            c.drawRect(capL + 2 * d, ct, capR - 2 * d, ct + 0.6f * d, p)
            p.color = 0x80000000.toInt()
            c.drawRect(capL + 2 * d, cb - 0.6f * d, capR - 2 * d, cb, p)
        }

        // the spool end: black plastic, a slot across it for the camera's rewind fork
        val kL = cx - w * 0.16f
        val kR = cx + w * 0.16f
        p.color = 0xff1c1c1e.toInt()
        c.drawRoundRect(kL, top, kR, top + knobH + 1 * d, 1.5f * d, 1.5f * d, p)
        lit(c, p, kL, top, kR, top + knobH + 1 * d, 16f, 0.3f, 0.5f, 1.5f * d)
        p.color = 0xff050506.toInt()
        c.drawRect(cx - w * 0.03f, top, cx + w * 0.03f, top + knobH * 0.62f, p)
        p.color = 0x38ffffff
        c.drawRect(kL + 1 * d, top + 0.3f * d, kR - 1 * d, top + 0.9f * d, p)
    }

}

/**
 * The film wall of the 经典 body: shelves of cartridges to choose a roll from, then loading
 * it by hand. The back swings open on its hinge; the cartridge is dragged into its chamber
 * and drops home; the leader is drawn across the gate, against rising tension, until the
 * take-up spool catches it; the back swings shut and latches; the camera opens up from the
 * film reminder on its back. Every step can be let go of and taken up again.
 */
class FilmWall(context: Context, private val names: List<String>, current: Int) : View(context) {
    private enum class Stage { PICK, OPEN, SEAT, THREAD, CLOSE, REVEAL }

    /** A damped spring: mechanical parts settle, they don't stop dead. */
    private class Spring(var x: Float, var k: Float, var c: Float) {
        var v = 0f
        var target = x
        fun step(dt: Float) { v += (-k * (x - target) - c * v) * dt; x += v * dt }
        fun set(value: Float) { x = value; target = value; v = 0f }
        fun still(eps: Float) = abs(x - target) < eps && abs(v) < eps * 8
    }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val face: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val wallGrain = grain(0xff2c2620.toInt(), 0xff332c25.toInt(), 0xff241f1a.toInt())
    private val leather = grain(0xff161617.toInt(), 0xff212122.toInt(), 0xff0c0c0d.toInt())
    private val scroller = OverScroller(context)
    private var tracker: VelocityTracker? = null
    private var scroll = 0f
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var selected = -1
    private var lift = 0f
    private val loadKey = RectF()
    private val skipKey = RectF()

    private var stage = Stage.PICK
    private var last = 0L

    // The cartridges stand straight on until something moves them: the phone turning (the
    // gyroscope's rate pushes them round and up) or a cartridge picked up (a little spin).
    // A spring brings them back to the front view.
    private var yaw = 0f
    private var yawVel = 0f
    private var tilt = 0f
    private var tiltVel = 0f
    private val gyro = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            // y: turning the phone left / right; x: tipping it toward / away
            yawVel += e.values[1] * 0.05f
            tiltVel += -e.values[0] * 0.02f
            postInvalidateOnAnimation()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(gyro, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onDetachedFromWindow() {
        context.getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(gyro)
        super.onDetachedFromWindow()
    }

    /** One step of the spring: toward the front view, a little underdamped so it settles alive. */
    private fun settle(dt: Float): Boolean {
        val k = 38f
        val damp = 7.5f
        yawVel += (-k * yaw - damp * yawVel) * dt
        tiltVel += (-k * tilt - damp * tiltVel) * dt
        yaw = (yaw + yawVel * dt).coerceIn(-1.1f, 1.1f)
        tilt = (tilt + tiltVel * dt).coerceIn(-0.05f, 0.34f)
        val moving = kotlin.math.abs(yaw) > 0.002f || kotlin.math.abs(yawVel) > 0.002f || kotlin.math.abs(tilt) > 0.002f
        if (!moving) { yaw = 0f; yawVel = 0f; tilt = 0f; tiltVel = 0f }
        return moving
    }
    private val scene = Spring(0f, 70f, 15f)      // 0 the wall .. 1 the open camera
    private val door = Spring(0f, 46f, 8.5f)      // degrees open; it swings a little past and back
    private val cartX = Spring(0f, 90f, 17f)
    private val cartY = Spring(0f, 90f, 17f)
    private val cartH = Spring(0f, 90f, 17f)
    private val pull = Spring(0.1f, 120f, 17f)    // how far the leader's tip is from the lip to the spool
    private val tipDy = Spring(0f, 140f, 14f)
    private var dragCart = false
    private var grabX = 0f
    private var grabY = 0f
    private var seating = false
    private var seated = false
    private var pullHeld = false
    private var caught = false
    private var spoolTurn = 0f
    private var tension = 0f
    private var lastPerf = 0
    private var shake = 0f
    private var clock = 0f
    private var latched = false
    private var done = false

    var onTick: (strong: Boolean) -> Unit = {}
    var onLoaded: (Int) -> Unit = {}
    var onSkip: () -> Unit = {}
    /** The picked cartridge, to see as a solid (Cartridge3DActivity). */
    var onInspect: (String) -> Unit = {}
    /** Loading the picked film in 3D (CameraBackActivity) instead of the drawn scene here. */
    var onLoadScene: ((Int) -> Unit)? = null
    private val inspectKey = RectF()

    private val padX = 56 * d
    private val head = 58 * d
    private val plank = 16 * d
    private val cellW = 86 * d

    // the open camera's geometry, set by geo()
    private var bl = 0f; private var bt = 0f; private var bw = 0f; private var bh = 0f
    private val rr = RectF()
    private var fh = 0f; private var fy0 = 0f
    private var chamberR = 0f; private var chamberCx = 0f
    private var lipX = 0f; private var spoolCx = 0f; private var spoolR = 0f
    private var seatH = 0f; private var seatBase = 0f; private var seatCx = 0f
    private var hingeX = 0f; private var doorW = 0f

    init {
        isClickable = true
        contentDescription = "胶卷墙：选一卷胶卷，装进相机"
        if (current in names.indices) post { scroll = ((current / 2) * cellW - innerW() / 2f + padX + cellW / 2).coerceIn(0f, maxScroll()) }
    }

    private fun rowH() = (innerH() - head - 10 * d) / 2
    private fun maxScroll() = max(0f, ((names.size + 1) / 2) * cellW + padX * 2 - innerW())
    private fun cxOf(i: Int) = padX + (i / 2) * cellW + cellW / 2 - scroll
    private fun baseOf(i: Int) = head + (i % 2 + 1) * rowH() - plank
    private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    /**
     * The open camera, laid out in millimetres as a real 35 mm body seen from behind:
     * 144 x 96 body; film chamber centred 23 mm in; a 36 x 24 gate between polished rails 35 mm
     * apart; the sprocket at 104; the take-up spool at 122. One mm is [k] pixels.
     */
    private var k = 1f
    private fun mx(mm: Float) = bl + mm * k
    private fun my(mm: Float) = bt + mm * k

    private fun geo() {
        val w = innerW()
        val h = innerH()
        k = min(w * 0.64f / 144f, (h - 96 * d) / 112f)
        bw = 144f * k
        bh = 96f * k
        bl = (w - bw) / 2
        bt = (h - bh) / 2 + 10 * d
        rr.set(mx(6f), my(12f), mx(138f), my(84f))
        fh = 35f * k
        fy0 = my(32.5f)
        chamberCx = mx(23f)
        chamberR = mx(37f)
        seatH = 46f * k
        seatBase = my(73f)
        seatCx = chamberCx
        lipX = seatCx + seatH * 0.56f * 0.5f + seatH * 0.56f * 0.17f
        spoolCx = mx(122f)
        spoolR = 8.5f * k
        hingeX = bl
        doorW = bw
    }

    /** Matte black body covering: a fine pebble grain, darker toward the lower right (light from the upper left). */
    private fun leatherette(canvas: Canvas, r: RectF, radius: Float) {
        p.style = Paint.Style.FILL
        p.shader = leather
        canvas.drawRoundRect(r, radius, radius, p)
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(0x14ffffff, 0x00000000, 0x40000000), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, p)
        p.shader = null
    }

    /** A polished steel bar seen straight on: lit along its upper edge, a hard reflection, its lower edge in shade. */
    private fun rail(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, t, 0f, b, intArrayOf(0xffdcdbd6.toInt(), 0xffa9a8a3.toInt(), 0xff76756f.toInt(), 0xff4c4b47.toInt()),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(l, t, r, b, p)
        p.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(0x00ffffff, 0x50ffffff, 0x00ffffff), floatArrayOf(0f, 0.3f + Skin.tiltX * 0.2f, 0.75f), Shader.TileMode.CLAMP)
        canvas.drawRect(l, t + (b - t) * 0.15f, r, t + (b - t) * 0.42f, p) // the polish, sliding with the light
        p.shader = null
        p.color = 0xb3000000.toInt()
        canvas.drawRect(l, b - 0.35f * k, r, b, p)
    }

    /** A round part standing upright (spool, sprocket, fork), shaded as a cylinder lit from the upper left. */
    private fun upright(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, gloss: Float, spec: Float, radius: Float = 0f) {
        p.style = Paint.Style.FILL
        p.color = color
        if (radius > 0f) canvas.drawRoundRect(l, t, r, b, radius, radius, p) else canvas.drawRect(l, t, r, b, p)
        FilmLabel.lit(canvas, p, l, t, r, b, gloss, spec, 0.45f, radius)
    }

    /** The camera from behind, back open: top-plate silhouettes, body, the inside, the door. */
    private fun drawBack(canvas: Canvas) {
        p.shader = null
        p.style = Paint.Style.FILL
        // what stands on the top plate, seen from behind: rewind crank, prism, speed dial, wind lever
        p.color = 0xff000000.toInt()
        p.setShadowLayer(18 * d, 0f, 10 * d, 0xc8000000.toInt())
        canvas.drawRoundRect(bl, bt, bl + bw, bt + bh, 7f * k, 7f * k, p)
        p.clearShadowLayer()
        upright(canvas, mx(14f), my(-9f), mx(32f), my(-1f), 0xff2a2a2c.toInt(), 12f, 0.35f, 1.2f * k) // rewind knob
        upright(canvas, mx(21.5f), my(-12f), mx(24.5f), my(-9f), 0xff8d8c87.toInt(), 8f, 0.5f)
        path.reset() // the prism housing
        path.moveTo(mx(52f), my(-0.5f)); path.lineTo(mx(58f), my(-15f)); path.lineTo(mx(86f), my(-15f)); path.lineTo(mx(92f), my(-0.5f)); path.close()
        p.color = 0xff1d1d1f.toInt()
        canvas.drawPath(path, p)
        p.shader = LinearGradient(mx(52f), 0f, mx(92f), 0f, intArrayOf(0x30ffffff, 0x00000000, 0x55000000), null, Shader.TileMode.CLAMP)
        canvas.drawPath(path, p)
        p.shader = null
        p.color = 0x40ffffff
        canvas.drawRect(mx(58.5f), my(-15f), mx(85.5f), my(-14.5f), p)
        upright(canvas, mx(102f), my(-7f), mx(122f), my(-1f), 0xff2c2c2e.toInt(), 10f, 0.3f, 1f * k) // speed dial
        upright(canvas, mx(126f), my(-6f), mx(136f), my(-1f), 0xffa5a49f.toInt(), 8f, 0.55f, 1f * k) // wind lever hub
        // the top plate: brushed chrome along the body's top edge
        p.shader = LinearGradient(0f, my(-1.5f), 0f, my(2f), intArrayOf(0xffe8e7e2.toInt(), 0xffb5b4af.toInt(), 0xff6f6e69.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(mx(3f), my(-1.5f), mx(141f), my(2.5f), 1.2f * k, 1.2f * k, p)
        p.shader = null

        // the body
        leatherette(canvas, RectF(bl, bt, bl + bw, bt + bh), 7f * k)
        p.color = 0xff0b0b0c.toInt()
        canvas.drawRect(mx(2f), my(90f), mx(142f), my(91f), p) // the seam above the base plate

        // the inside: a light-seal groove round it in black foam, then the recess
        p.color = 0xff050506.toInt()
        canvas.drawRoundRect(rr.left - 2.2f * k, rr.top - 2.2f * k, rr.right + 2.2f * k, rr.bottom + 2.2f * k, 3.5f * k, 3.5f * k, p)
        p.shader = wallGrain
        p.alpha = 70
        canvas.drawRoundRect(rr.left - 2.2f * k, rr.top - 2.2f * k, rr.right + 2.2f * k, rr.bottom + 2.2f * k, 3.5f * k, 3.5f * k, p)
        p.alpha = 255
        p.shader = null
        p.color = 0xff121214.toInt()
        canvas.drawRoundRect(rr, 2.5f * k, 2.5f * k, p)

        // the film chamber: a hollow, so the wall away from the lamp catches it; the rewind fork's shaft at its top
        p.shader = LinearGradient(mx(9f), 0f, chamberR, 0f, intArrayOf(0xff020203.toInt(), 0xff0a0a0b.toInt(), 0xff1c1c1f.toInt(), 0xff0a0a0b.toInt()),
            floatArrayOf(0f, 0.35f, 0.82f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(mx(9f), my(15f), chamberR, my(81f), 3f * k, 3f * k, p)
        p.shader = null
        // its floor: the spool's bearing
        upright(canvas, mx(19f), my(78f), mx(27f), my(80.5f), 0xff3a3a3c.toInt(), 10f, 0.3f)

        // the gate: a matte black mask, and behind it the shutter's metal blades
        p.color = 0xff060607.toInt()
        canvas.drawRoundRect(mx(50f), my(35f), mx(94f), my(65f), 1.5f * k, 1.5f * k, p)
        val blades = 5
        for (i in 0 until blades) {
            val t = my(38f + 24f * i / blades)
            val b = my(38f + 24f * (i + 1) / blades)
            p.shader = LinearGradient(0f, t, 0f, b, intArrayOf(0xff3a3b3f.toInt(), 0xff26272a.toInt(), 0xff1b1c1e.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(mx(54f), t, mx(90f), b, p)
            p.shader = null
            p.color = 0x2effffff
            canvas.drawRect(mx(54f), t, mx(90f), t + 0.3f * k, p)
            p.color = 0x99000000.toInt()
            canvas.drawRect(mx(54f), b - 0.4f * k, mx(90f), b, p)
        }
        p.shader = LinearGradient(0f, my(38f), 0f, my(42f), 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(mx(54f), my(38f), mx(90f), my(42f), p) // the mask's edge shades the blades
        p.shader = null
        // rails: outer ones at the film's edges, inner ones guiding it, a screw at each end
        rail(canvas, mx(44f), my(30.5f), mx(100f), my(33.8f))
        rail(canvas, mx(44f), my(66.2f), mx(100f), my(69.5f))
        rail(canvas, mx(50f), my(35.2f), mx(94f), my(36.6f))
        rail(canvas, mx(50f), my(63.4f), mx(94f), my(64.8f))
        for (sx in listOf(46f, 98f)) for (sy in listOf(28.6f, 71.4f)) {
            upright(canvas, mx(sx - 1.2f), my(sy - 1.2f), mx(sx + 1.2f), my(sy + 1.2f), 0xff7b7a75.toInt(), 8f, 0.4f, 1.2f * k)
            p.color = 0xff1d1d1f.toInt()
            canvas.drawRect(mx(sx - 0.9f), my(sy - 0.15f), mx(sx + 0.9f), my(sy + 0.15f), p)
        }

        // the take-up spool in its chamber, slotted for the leader, turning as it winds
        p.shader = LinearGradient(mx(109f), 0f, mx(136f), 0f, intArrayOf(0xff1a1a1c.toInt(), 0xff09090a.toInt(), 0xff020203.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(mx(109f), my(15f), mx(136f), my(81f), 3f * k, 3f * k, p)
        p.shader = null
        upright(canvas, spoolCx - spoolR - 1.5f * k, my(25f), spoolCx + spoolR + 1.5f * k, my(28.5f), 0xff4a4a4d.toInt(), 9f, 0.35f) // flanges
        upright(canvas, spoolCx - spoolR - 1.5f * k, my(71.5f), spoolCx + spoolR + 1.5f * k, my(75f), 0xff4a4a4d.toInt(), 9f, 0.35f)
        upright(canvas, spoolCx - spoolR, my(28.5f), spoolCx + spoolR, my(71.5f), 0xff8b8a85.toInt(), 9f, 0.6f)
        p.color = 0xcc0a0a0b.toInt()
        for (j in 0 until 4) {
            val a = spoolTurn + j * Math.PI.toFloat() / 2
            if (cos(a) > 0.05f) {
                val x = spoolCx + spoolR * sin(a)
                val half = 1.2f * k * cos(a)
                canvas.drawRect(x - half, my(33f), x + half, my(67f), p)
            }
        }
        // the sprocket: a steel drum, its teeth in two rows where the perforations run
        val sprL = mx(100.5f)
        val sprR = mx(107.5f)
        upright(canvas, sprL, my(29.5f), sprR, my(70.5f), 0xff7f7e79.toInt(), 9f, 0.55f)
        val roll = pull.x * (spoolCx - lipX) / (3.5f * k) + spoolTurn * 1.4f
        for (j in 0 until 8) {
            val a = roll + j * Math.PI.toFloat() / 4
            if (cos(a) > 0.1f) {
                val x = (sprL + sprR) / 2 + 3.5f * k * sin(a)
                val half = 0.75f * k * cos(a)
                for ((t, b) in listOf(33.8f to 36.2f, 63.8f to 66.2f)) {
                    p.color = 0xffcfcec9.toInt()
                    canvas.drawRect(x - half, my(t), x + half, my(b), p)
                    p.color = 0x66000000
                    canvas.drawRect(x + half * 0.3f, my(t), x + half, my(b), p)
                }
            }
        }

        if (stage.ordinal >= Stage.THREAD.ordinal) drawFilm(canvas)
        if (seated) {
            CartridgeSprites.draw(canvas, cartX.x, cartY.x, cartH.x, names[selected]) { postInvalidateOnAnimation() }
            // the rewind fork, come down into the spool from the top plate
            upright(canvas, chamberCx - 1.4f * k, my(15f), chamberCx + 1.4f * k, cartY.x - cartH.x + 2.5f * k, 0xffa5a49f.toInt(), 8f, 0.5f)
        }
        // the chamber's upper lip and the recess's upper edge shade what is in them
        p.shader = LinearGradient(0f, rr.top, 0f, rr.top + 6f * k, 0xcc000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(rr.left, rr.top, rr.right, rr.top + 6f * k, p)
        p.shader = LinearGradient(rr.left, 0f, rr.left + 4f * k, 0f, 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(rr.left, rr.top, rr.left + 4f * k, rr.bottom, p)
        p.shader = null
        drawDoor(canvas)
    }

    /** The film from the cartridge's lip to its tip: it sags when slack, follows the hand, pulls straight under tension. */
    private fun drawFilm(canvas: Canvas) {
        val reach = spoolCx - lipX
        val tipX = lipX + reach * pull.x.coerceIn(0.04f, 1f)
        val mid = fy0 + fh / 2
        val sag = (1 - tension) * fh * 0.08f * smooth(pull.x * 2f)
        val tongue = 22f * k
        val n = 26
        // once the spool has it, the tongue goes round with the spool: carried right, into the wrap, out of sight
        val tongueEnd = tipX + (if (caught) spoolTurn * spoolR * 1.6f else 0f)
        fun cy(u: Float) = mid + (1 - u) * u * 2 * (sag * 2 + tipDy.x * 0.4f) + u * u * tipDy.x
        path.reset()
        for (i in 0..n) {
            val u = i / n.toFloat()
            val x = lipX + (tipX - lipX) * u
            val cut = smooth((x - (tongueEnd - tongue)) / (tongue * 0.4f)) * fh * 0.46f
            if (i == 0) path.moveTo(x, cy(u) - fh / 2 + cut) else path.lineTo(x, cy(u) - fh / 2 + cut)
        }
        for (i in n downTo 0) {
            val u = i / n.toFloat()
            path.lineTo(lipX + (tipX - lipX) * u, cy(u) + fh / 2)
        }
        path.close()
        p.style = Paint.Style.FILL
        p.color = 0xff5a3a22.toInt()
        p.setShadowLayer((2 + 5 * (1 - tension)) * d, 1.5f * d, (1.5f + 3.5f * (1 - tension)) * d, 0xb0000000.toInt()) // lifted film throws a longer shadow
        canvas.drawPath(path, p)
        p.clearShadowLayer()
        canvas.save()
        canvas.clipPath(path)
        val pitch = 4.75f * k // the real perforation pitch
        // once the spool has it, the film runs right as it winds: the perforations travel across the gate
        val travel = if (caught) spoolTurn * spoolR else 0f
        var x = lipX - pitch + (travel % pitch) + (if (caught) 0f else ((tipX - lipX) % pitch) - pitch * 0.8f)
        while (x < tipX + pitch) {
            val u = ((x - lipX) / (tipX - lipX)).coerceIn(0f, 1f)
            val y = cy(u)
            p.color = 0xff3b2516.toInt() // the emulsion side is darker than the base along the edges
            canvas.drawRect(x, y - fh * 0.31f, x + pitch + 1, y + fh * 0.31f, p)
            p.color = 0xff100b07.toInt()
            canvas.drawRoundRect(x + 0.6f * k, y - fh * 0.455f, x + 2.6f * k, y - fh * 0.395f, 0.4f * k, 0.4f * k, p)
            canvas.drawRoundRect(x + 0.6f * k, y + fh * 0.395f, x + 2.6f * k, y + fh * 0.455f, 0.4f * k, 0.4f * k, p)
            x += pitch
        }
        // the base side is glossy: a soft band where it faces the lamp, wider where it bends
        val gx = lipX + (tipX - lipX) * (0.3f + Skin.tiltX * 0.2f)
        p.shader = LinearGradient(gx - 9 * k, 0f, gx + 9 * k, 0f, intArrayOf(0x00ffe4c0, Color.argb((40 + 50 * (1 - tension)).toInt(), 255, 228, 192), 0x00ffe4c0), null, Shader.TileMode.CLAMP)
        canvas.drawRect(gx - 9 * k, fy0 - fh, gx + 9 * k, fy0 + fh * 2, p)
        p.shader = null
        canvas.restore()
        if (caught) drawWrap(canvas)
    }

    /**
     * The film going onto the take-up spool: the tongue is carried round, then each turn lays
     * another wrap of film over the spool, a little thicker each time, its perforations
     * travelling across the front as it turns.
     */
    private fun drawWrap(canvas: Canvas) {
        val turns = spoolTurn / (2 * Math.PI.toFloat())
        if (turns <= 0f) return
        val first = (turns * 1.3f).coerceAtMost(1f) // the first wrap closes round the spool
        val thick = 0.25f * k * turns.coerceAtMost(4f)
        val r = spoolR + thick
        val l = spoolCx - r
        val right = l + 2 * r * first
        val t = fy0
        val b = fy0 + fh
        p.style = Paint.Style.FILL
        p.color = 0xff5a3a22.toInt()
        canvas.drawRect(l, t, right, b, p)
        canvas.save()
        canvas.clipRect(l, t, right, b)
        // the perforations on the wrap: they move across the front as the spool turns, crowding toward its edges
        p.color = 0xff100b07.toInt()
        val n = 10
        for (j in 0 until n) {
            val a = spoolTurn * 1.0f + j * 2 * Math.PI.toFloat() / n
            val c = cos(a)
            if (c <= 0.05f) continue
            val x = spoolCx + r * sin(a)
            val half = 1.0f * k * c
            canvas.drawRect(x - half, t + fh * 0.045f, x + half, t + fh * 0.105f, p)
            canvas.drawRect(x - half, b - fh * 0.105f, x + half, b - fh * 0.045f, p)
        }
        p.color = 0xff3b2516.toInt()
        canvas.drawRect(l, t + fh * 0.19f, right, b - fh * 0.19f, p)
        canvas.restore()
        // the wrap is round: lit from the left, its base glossy
        FilmLabel.lit(canvas, p, l, t, l + 2 * r, b, 14f, 0.45f, 0.5f)
        // where the film comes off the sprocket onto the spool, a dark line at the tangent
        p.color = 0x99000000.toInt()
        canvas.drawRect(l, t, l + 0.4f * k, b, p)
    }

    /**
     * The back, hinged on the body's left edge (the film chamber's side). Seen straight on, a
     * door at angle a shows cos(a) of its width; open, its inside never turns to the viewer.
     */
    private fun drawDoor(canvas: Canvas) {
        val a = Math.toRadians(min(door.x, 89.3f).toDouble())
        val c = cos(a).toFloat()
        val wide = doorW * c
        if (wide < 1f) return
        val top = my(6f)
        val bottom = my(90f)
        val right = hingeX + wide
        p.style = Paint.Style.FILL
        // what the door, coming over, takes the light from: a soft shadow beyond its edge
        p.shader = LinearGradient(right, 0f, right + 30 * k * sin(a).toFloat() + 2 * k, 0f, Color.argb((210 * c).toInt(), 0, 0, 0), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(right, rr.top, right + 30 * k * sin(a).toFloat() + 2 * k, rr.bottom, p)
        p.shader = null
        val face = RectF(hingeX, top, right, bottom)
        leatherette(canvas, face, 6f * k * c)
        // turning toward the lamp on the left it brightens a little, then goes edge-on
        p.color = Color.argb((90 * sin(a) * sin(a)).toInt(), 0, 0, 0)
        canvas.drawRoundRect(face, 6f * k * c, 6f * k * c, p)
        // its rim in black metal, the latch at its free edge
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.6f * k
        p.color = 0xff2a2a2c.toInt()
        canvas.drawRoundRect(face.left + 1.2f * k * c, face.top + 1.2f * k, face.right - 1.2f * k * c, face.bottom - 1.2f * k, 5f * k * c, 5f * k * c, p)
        p.style = Paint.Style.FILL
        if (c > 0.5f) {
            // the film reminder holder, and in it the end of the roll's box
            val hl = hingeX + 104f * k * c
            val hr = hingeX + 128f * k * c
            val ht = my(38f)
            val hb = my(58f)
            upright(canvas, hl - 1.5f * k * c, ht - 1.5f * k, hr + 1.5f * k * c, hb + 1.5f * k, 0xffa5a49f.toInt(), 8f, 0.45f, 1f * k)
            if (latched || door.x < 5f) {
                val look = FilmLabel.of(names[selected])
                p.color = look.base
                canvas.drawRect(hl, ht, hr, hb, p)
                p.color = look.band
                canvas.drawRect(hl, ht, hr, ht + (hb - ht) * 0.26f, p)
                write(look.title, (hl + hr) / 2, ht + (hb - ht) * 0.62f, 8f, look.ink, Paint.Align.CENTER, canvas)
                write(look.iso, (hl + hr) / 2, ht + (hb - ht) * 0.92f, 9f, look.ink, Paint.Align.CENTER, canvas)
                p.color = 0x22ffffff // its window
                canvas.drawRect(hl, ht, hr, ht + (hb - ht) * 0.45f, p)
            } else {
                p.color = 0xff0c0c0d.toInt()
                canvas.drawRect(hl, ht, hr, hb, p)
            }
            p.color = 0xff1b1b1d.toInt() // the latch
            canvas.drawRoundRect(right - 6f * k * c, my(44f), right - 2f * k * c, my(56f), 1f * k, 1f * k, p)
        }
        if (stage == Stage.CLOSE && clock in 0.85f..1.45f) {
            // shut: one soft band of light crosses the leather
            val u = (clock - 0.85f) / 0.6f
            val x = face.left + face.width() * (u * 1.4f - 0.2f)
            canvas.save()
            canvas.clipRect(face)
            p.shader = LinearGradient(x - 50 * k, 0f, x + 50 * k, 0f, intArrayOf(0x00ffffff, 0x1cffffff, 0x00ffffff), null, Shader.TileMode.CLAMP)
            canvas.rotate(-18f, x, face.centerY())
            canvas.drawRect(x - 50 * k, face.top - 60 * k, x + 50 * k, face.bottom + 60 * k, p)
            canvas.restore()
            p.shader = null
        }
        // the hinge pin along the body's left edge
        upright(canvas, hingeX - 1.2f * k, my(10f), hingeX + 1.2f * k, my(86f), 0xff8d8c87.toInt(), 8f, 0.5f)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) scroll = scroller.currX.toFloat().coerceIn(0f, maxScroll())
    }

    // ----- hands ---------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; moved = false
                when (stage) {
                    Stage.PICK -> {
                        scroller.forceFinished(true)
                        tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                    }
                    Stage.SEAT -> if (!seating && abs(e.x - cartX.x) < cartH.x * 0.5f && e.y < cartY.x + 20 * d && e.y > cartY.x - cartH.x - 20 * d) {
                        dragCart = true
                        grabX = cartX.x - e.x
                        grabY = cartY.x - e.y
                    }
                    Stage.THREAD -> if (!caught && e.x > lipX - 30 * d && e.x < spoolCx + 40 * d && e.y > fy0 - 30 * d && e.y < fy0 + fh + 30 * d) pullHeld = true
                    else -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > 8 * d) moved = true
                when {
                    stage == Stage.PICK -> {
                        tracker?.addMovement(e)
                        if (moved) scroll = (scroll - (e.x - lastX)).coerceIn(0f, maxScroll())
                    }
                    dragCart -> {
                        cartX.set(e.x + grabX)
                        cartY.set(e.y + grabY)
                        // nearer the chamber it is nearer the camera: a little smaller, its shadow tighter
                        val far = (hypot(cartX.x - seatCx, cartY.x - seatBase) / (bw * 0.5f)).coerceIn(0f, 1f)
                        cartH.set(seatH * (1.04f + 0.08f * far))
                    }
                    pullHeld -> {
                        // the film comes easily at first, then the spool's side asks for more hand
                        val raw = ((e.x - lipX) / (spoolCx - lipX)).coerceIn(0.06f, 1.1f)
                        pull.set(if (raw < 0.45f) raw else 0.45f + (raw - 0.45f) * 0.72f)
                        tipDy.set((e.y - (fy0 + fh / 2)).coerceIn(-fh * 0.34f, fh * 0.34f) * (1 - pull.x * 0.7f))
                        tension = smooth((pull.x - 0.3f) / 0.5f) * 0.7f
                        val perf = (pull.x * (spoolCx - lipX) / (fh * 0.135f)).toInt()
                        if (perf != lastPerf) { lastPerf = perf; if (pull.x > 0.62f) onTick(false) } // the sprocket's teeth, one by one
                        if (pull.x >= 0.8f) catchLeader()
                    }
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when {
                    stage == Stage.PICK -> {
                        tracker?.let {
                            it.addMovement(e)
                            it.computeCurrentVelocity(1000)
                            if (moved) scroller.fling(scroll.toInt(), 0, -it.xVelocity.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                            it.recycle()
                        }
                        tracker = null
                        if (!moved) tap(e.x, e.y)
                    }
                    dragCart -> {
                        dragCart = false
                        if (!moved || hypot(cartX.x - seatCx, cartY.x - (seatBase - 30f * k)) < 34f * k) seat() else hover()
                    }
                    pullHeld -> {
                        pullHeld = false
                        if (!caught) { pull.target = 0.1f; tipDy.target = 0f; tension = 0f } // let go: the leader springs back
                    }
                    stage != Stage.PICK && skipKey.contains(e.x, e.y) && !moved -> onSkip()
                }
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun tap(x: Float, y: Float) {
        if (skipKey.contains(x, y)) { onSkip(); return }
        if (selected >= 0 && loadKey.contains(x, y)) { load(); return }
        if (selected >= 0 && inspectKey.contains(x, y)) { onInspect(names[selected]); return }
        val hit = names.indices.firstOrNull { abs(x - cxOf(it)) < cellW / 2 && y < baseOf(it) + plank && y > baseOf(it) - rowH() + plank }
        when {
            hit == null -> {}
            hit == selected -> load()
            else -> {
                selected = hit
                yawVel += 4.5f // picked up: it turns a little in the hand
                CartridgeSprites.prefetch(names[hit], rowH() * 0.7f * 1.08f, 0.5f * 25f)
                onTick(false)
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 260
                    addUpdateListener { lift = it.animatedValue as Float }
                    start()
                }
            }
        }
    }

    private fun load() {
        if (stage != Stage.PICK || selected < 0) return
        if (Skin.calm) { onLoaded(selected); return } // reduced motion: no ceremony
        onLoadScene?.let { it(selected); return }
        geo()
        cartX.set(cxOf(selected)); cartY.set(baseOf(selected) - 16 * d); cartH.set(rowH() * 0.7f * 1.08f)
        hover()
        scene.target = 1f
        stage = Stage.OPEN
        onTick(false)
    }

    /** Held over the chamber, waiting to be put in. */
    private fun hover() {
        cartX.target = seatCx; cartY.target = seatBase - 30f * k; cartH.target = seatH * 1.06f
        cartX.k = 90f; cartX.c = 17f; cartY.k = 90f; cartY.c = 17f
    }

    /** Let go over the chamber: it drops home, heavy, and the camera feels it. */
    private fun seat() {
        seating = true
        cartX.target = seatCx; cartY.target = seatBase; cartH.target = seatH
        cartX.k = 200f; cartX.c = 22f
        cartY.k = 260f; cartY.c = 15f // a little under-damped: it lands and settles
        cartH.k = 200f; cartH.c = 22f
    }

    /** The take-up spool has the leader: it winds the slack on and the film pulls taut. */
    private fun catchLeader() {
        if (caught) return
        caught = true
        pullHeld = false
        pull.target = 1f
        tipDy.target = 0f
        onTick(true)
    }

    // ----- time ----------------------------------------------------------------------------

    private fun update(dt: Float) {
        scene.step(dt)
        if (stage != Stage.CLOSE && stage != Stage.REVEAL) door.step(dt)
        if (!dragCart) { cartX.step(dt); cartY.step(dt); cartH.step(dt) }
        if (!pullHeld) { pull.step(dt); tipDy.step(dt) }
        shake *= exp(-dt * 9f)
        when (stage) {
            Stage.OPEN -> {
                if (scene.x > 0.55f) door.target = 88f
                if (door.x > 84f && cartY.still(1.5f * d)) stage = Stage.SEAT
            }
            Stage.SEAT -> if (seating) {
                if (!seated && cartY.x >= seatBase - 1.5f * d) { seated = true; shake = 5 * d; onTick(true) }
                if (seated && cartY.still(0.6f * d)) { cartY.set(seatBase); stage = Stage.THREAD }
            }
            Stage.THREAD -> if (caught) {
                tension += (1f - tension) * min(1f, dt * 5f)
                if (spoolTurn < 22f) spoolTurn += dt * (6f + 10f * smooth(spoolTurn / 6f))
                else if (pull.still(0.01f)) { stage = Stage.CLOSE; clock = 0f }
            }
            Stage.CLOSE -> {
                clock += dt
                // the back falls shut faster and faster, meets its latch, gives a little, and is still
                door.x = when {
                    clock < 0.62f -> 88f * (1f - Math.pow((clock / 0.62f).toDouble(), 2.2).toFloat())
                    clock < 0.82f -> 2.6f * sin((clock - 0.62f) / 0.2f * Math.PI.toFloat()) * (1f - (clock - 0.62f) / 0.2f)
                    else -> 0f
                }
                if (!latched && clock >= 0.62f) { latched = true; shake = 4 * d; onTick(true) }
                if (clock > 1.55f) { stage = Stage.REVEAL; clock = 0f }
            }
            Stage.REVEAL -> {
                clock += dt / 0.75f
                alpha = 1f - smooth(clock)
                if (clock >= 1f && !done) { done = true; onLoaded(selected) }
            }
            else -> {}
        }
    }

    private fun write(s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align, canvas: Canvas, alpha: Float = 1f) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.typeface = face
        p.textSize = size * d
        p.textAlign = align
        p.color = color
        p.alpha = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        canvas.drawText(s, x, y, p)
        p.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        // the backdrop runs edge to edge; everything else keeps inside the padding (bars, cutout)
        if (paddingLeft or paddingTop or paddingRight or paddingBottom != 0) {
            p.style = Paint.Style.FILL
            p.shader = wallGrain
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.shader = null
        }
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.clipRect(0, 0, width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
        drawInside(canvas)
        canvas.restore()
    }

    private fun innerW() = (width - paddingLeft - paddingRight).toFloat()
    private fun innerH() = (height - paddingTop - paddingBottom).toFloat()

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        e.offsetLocation(-paddingLeft.toFloat(), -paddingTop.toFloat())
        val r = super.dispatchTouchEvent(e)
        e.offsetLocation(paddingLeft.toFloat(), paddingTop.toFloat())
        return r
    }

    private fun drawInside(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.034f)
        if (settle(dt)) postInvalidateOnAnimation()
        last = now
        val w = innerW()
        val h = innerH()
        if (stage != Stage.PICK) { geo(); update(dt) }
        // the wall falls back and darkens as the camera comes up in front of it
        val s = scene.x.coerceIn(0f, 1f)
        canvas.save()
        canvas.scale(1 - 0.07f * s, 1 - 0.07f * s, w / 2, h / 2)
        drawWall(canvas, w, h)
        canvas.restore()
        if (stage != Stage.PICK) {
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = Color.argb((236 * smooth(s)).toInt(), 0x12, 0x11, 0x11)
            canvas.drawRect(0f, 0f, w, h, p)
            canvas.save()
            val rise = (1 - s) * (1 - s) * h * 0.7f
            canvas.translate(sin(now / 1e9f * 83f) * shake, rise + cos(now / 1e9f * 67f) * shake * 0.6f)
            canvas.scale(0.9f + 0.1f * s, 0.9f + 0.1f * s, w / 2, h / 2)
            if (stage == Stage.REVEAL) {
                // loaded: the camera draws back and down a little as the scene goes
                val e = smooth(clock)
                canvas.translate(0f, 22 * d * e)
                canvas.scale(1f - 0.08f * e, 1f - 0.08f * e, w / 2, h / 2)
            }
            drawBack(canvas)
            canvas.restore()
            if (!seated) CartridgeSprites.draw(canvas, cartX.x, cartY.x, cartH.x, names[selected], leader = 0.14f,
                shadow = if (stage == Stage.OPEN) 0.3f else (1.2f - hypot(cartX.x - seatCx, cartY.x - seatBase) / (60f * k)).coerceIn(0.25f, 1f)) { postInvalidateOnAnimation() }
            val hint = when (stage) {
                Stage.SEAT -> if (seating) "" else "把暗盒拖进左边的片仓"
                Stage.THREAD -> if (caught) "" else "捏住片头，向右拉到收片轴"
                Stage.CLOSE -> if (clock > 0.9f) "已装片 · 第 1 张" else ""
                else -> ""
            }
            if (hint.isNotEmpty()) write(hint, bl + bw * 0.5f, bt + bh + 28 * d, 15f, 0xffe6e3d9.toInt(), Paint.Align.CENTER, canvas)
            skipKey.set(w - padX - 64 * d, 12 * d, w - padX, 44 * d)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1 * d
            p.color = 0xff5f5d58.toInt()
            canvas.drawRoundRect(skipKey, 16 * d, 16 * d, p)
            write("跳过", skipKey.centerX(), skipKey.centerY() + 5 * d, 13f, 0xffe6e3d9.toInt(), Paint.Align.CENTER, canvas)
        }
        postInvalidateOnAnimation()
    }

    private fun drawWall(canvas: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        p.shader = wallGrain
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
        val rh = rowH()
        for (row in 0 until 2) {
            val top = head + row * rh
            val bottom = top + rh
            // a strip lamp under the shelf above: a warm wash down the back board, brightest at its top
            p.shader = LinearGradient(0f, top, 0f, bottom - plank, intArrayOf(0x78f1cf98, 0x30e8c48a, 0x0ae8c48a), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top, w, bottom - plank, p)
            // and the lamp falls off between the bays, as from a row of small lights
            val bay = cellW * 2
            var bx = padX + cellW - (scroll % bay) - bay
            while (bx < w + bay) {
                p.shader = android.graphics.RadialGradient(bx, top, rh * 0.9f, intArrayOf(0x2efff0d0, 0x00fff0d0), null, Shader.TileMode.CLAMP)
                canvas.drawRect(bx - rh, top, bx + rh, bottom - plank, p)
                bx += bay
            }
            // the shelf above throws a short dark band right under itself
            p.shader = LinearGradient(0f, top, 0f, top + 10 * d, 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top, w, top + 10 * d, p)
            p.shader = null
            // the plank: its top, lit by the lamp, with the grain; its front edge in shade
            val pt = bottom - plank
            p.shader = LinearGradient(0f, pt, 0f, pt + 7 * d, 0xff7a6650.toInt(), 0xff5d4c3a.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, pt, w, pt + 7 * d, p)
            p.shader = null
            p.color = 0x2a000000
            var gx = -scroll * 0.5f % (90 * d)
            while (gx < w) { canvas.drawRect(gx, pt + 2 * d, gx + 54 * d, pt + 2.7f * d, p); canvas.drawRect(gx + 30 * d, pt + 4.4f * d, gx + 70 * d, pt + 5f * d, p); gx += 90 * d }
            p.color = 0x55fff1d8
            canvas.drawRect(0f, pt + 7 * d, w, pt + 7.8f * d, p) // the arris catches the lamp
            p.shader = LinearGradient(0f, pt + 7.8f * d, 0f, bottom, 0xff3a3028.toInt(), 0xff221c17.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, pt + 7.8f * d, w, bottom, p)
            p.shader = null
        }
        val ch = rh * 0.7f
        for (i in names.indices) {
            if (i == selected) continue
            val cx = cxOf(i)
            if (cx < -cellW || cx > w + cellW) continue
            CartridgeSprites.draw(canvas, cx, baseOf(i), ch, names[i], leader = 0.3f) { postInvalidateOnAnimation() }
        }
        if (stage != Stage.PICK) return
        write("选一卷胶卷", padX, 30 * d, 20f, 0xffe6e3d9.toInt(), Paint.Align.LEFT, canvas)
        write(if (selected < 0) "左右滑动货架 · 点一卷把它拿起来" else "再点一次这卷，或点右边的按键，开始装片", padX, 48 * d, 12f, 0xffa9a59b.toInt(), Paint.Align.LEFT, canvas)
        skipKey.set(w - padX - 64 * d, 12 * d, w - padX, 44 * d)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1 * d
        p.color = 0xff5f5d58.toInt()
        canvas.drawRoundRect(skipKey, 16 * d, 16 * d, p)
        write("跳过", skipKey.centerX(), skipKey.centerY() + 5 * d, 13f, 0xffe6e3d9.toInt(), Paint.Align.CENTER, canvas)
        if (selected >= 0) {
            p.style = Paint.Style.FILL
            p.color = Color.argb((110 * lift).toInt(), 0, 0, 0)
            canvas.drawRect(0f, head, w, h, p)
            val bob = sin(System.nanoTime() / 6e8).toFloat() * 2 * d
            CartridgeSprites.draw(canvas, cxOf(selected), baseOf(selected) - 16 * d * lift + bob * lift, ch * (1 + 0.08f * lift), names[selected],
                yaw = yaw, leader = 0.5f, shadow = 1 - 0.5f * lift) { postInvalidateOnAnimation() }
            val look = FilmLabel.of(names[selected])
            loadKey.set(skipKey.left - 150 * d, 12 * d, skipKey.left - 12 * d, 44 * d)
            p.style = Paint.Style.FILL
            p.color = 0xffe3b341.toInt()
            canvas.drawRoundRect(loadKey, 16 * d, 16 * d, p)
            write("装进相机  ▸", loadKey.centerX(), loadKey.centerY() + 5 * d, 14f, 0xff141414.toInt(), Paint.Align.CENTER, canvas)
            // a second key under it: the cartridge as a solid, to turn in the hand
            inspectKey.set(loadKey.left, loadKey.bottom + 10 * d, loadKey.right, loadKey.bottom + 42 * d)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1 * d
            p.color = 0xffa9a59b.toInt()
            canvas.drawRoundRect(inspectKey, 16 * d, 16 * d, p)
            p.style = Paint.Style.FILL
            write("3D 细看", inspectKey.centerX(), inspectKey.centerY() + 5 * d, 13f, 0xffe6e3d9.toInt(), Paint.Align.CENTER, canvas)
            write(FilmLabel.pretty(names[selected]), loadKey.left - 16 * d, 28 * d, 16f, 0xffe6e3d9.toInt(), Paint.Align.RIGHT, canvas)
            write((if (look.iso.isEmpty()) "" else "ISO ${look.iso} · ") + "135 · 36 张", loadKey.left - 16 * d, 46 * d, 12f, 0xffa9a59b.toInt(), Paint.Align.RIGHT, canvas)
        }
    }

}
