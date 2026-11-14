package org.photoall.film.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 135 cartridge rendered as a solid: seen from a little above and in front, so its top
 * cap shows as an ellipse with the spool's knob standing in it. The shell is a cylinder
 * lit by one lamp (upper left, a little in front) with light bounced back from the room:
 * its printed label is wrapped round it strip by strip, each strip shaded by its normal
 * (Lambert) with the varnish's highlight on top (Blinn-Phong); the crimped steel caps are
 * satin metal with a tighter, softer highlight and the crimp's ridges; the light trap's
 * lip stands off the shell on the right with its black velvet, and the leader comes out
 * of it, amber film base with its perforations, curling toward the viewer. A soft shadow
 * falls on the surface it stands on.
 */
object Cartridge3D {
    // the lamp and the eye
    private const val LX = -0.55f
    private const val LY = 0.45f   // up
    private const val LZ = 0.70f
    private val L = norm(LX, LY, LZ)
    private val H = norm(L[0], L[1], L[2] + 1f) // half vector with the eye along +z

    private fun norm(x: Float, y: Float, z: Float): FloatArray {
        val n = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / n, y / n, z / n)
    }

    private val label = HashMap<String, Bitmap>()
    private val src = Rect()
    private val dst = RectF()

    /**
     * The label all the way round: the printed front (the flat label, half the shell) in the
     * middle, the shell's back continuing its colour and bands either side.
     */
    private fun labelOf(name: String): Bitmap = label.getOrPut(name) {
        val front = FilmLabel.flatFor(name)
        val look = FilmLabel.of(name)
        val full = Bitmap.createBitmap(front.width * 2, front.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(look.base)
        p.color = look.band
        c.drawRect(0f, 0f, full.width.toFloat(), front.height * 0.17f, p)
        c.drawRect(0f, front.height * 0.94f, full.width.toFloat(), front.height.toFloat(), p)
        c.drawBitmap(front, front.width / 2f, 0f, p)
        full
    }

    /**
     * The cartridge standing on [base] (screen y of the surface under it), [h] tall,
     * centred on [cx]. [tilt]: how much of the top shows (0 straight on .. 0.35 from well
     * above). [leader]: how far the film comes out, in cartridge widths.
     */
    fun draw(c: Canvas, cx: Float, base: Float, h: Float, name: String, tilt: Float = 0f, yaw: Float = 0f, leader: Float = 0.9f,
             shadow: Float = 1f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val R = h * 0.29f           // the shell's radius
        val Rc = R * 1.045f         // the caps stand a little proud of it
        val e = tilt.coerceIn(0f, 0.4f) // ellipse ratio of a circle seen from above: 0 is straight on
        val knobH = h * 0.075f
        val capH = h * 0.07f
        val stub = h * 0.03f
        val bottom = base - stub
        val bodyBottom = bottom - capH
        val top = base - h + knobH
        val bodyTop = top + capH

        // ---------------------------------------------------------------- the shadow it casts
        if (shadow > 0f) {
            p.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb((110 * shadow).toInt(), 0, 0, 0), Color.argb((40 * shadow).toInt(), 0, 0, 0), 0),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            p.shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(R * 2.6f, R * e * 2.2f + h * 0.04f); postTranslate(cx + R * 0.9f, base) })
            c.drawRect(cx - R * 2f, base - R * e * 3f - h * 0.05f, cx + R * 4f, base + R * e * 3f + h * 0.05f, p)
            p.shader = RadialGradient(cx, base, Rc * 1.15f, intArrayOf(Color.argb((150 * shadow).toInt(), 0, 0, 0), 0), null, Shader.TileMode.CLAMP)
            c.save(); c.scale(1f, e.coerceAtLeast(0.08f), cx, base)
            c.drawCircle(cx, base, Rc * 1.15f, p)
            c.restore()
            p.shader = null
        }

        // ---------------------------------------------------------------- the leader, behind the lip at first
        // where the lip leaves the shell: 62 degrees round to the right of the front, turned with the cartridge
        val lipAngle = 62f * PI.toFloat() / 180f + yaw
        val lipShows = lipAngle < 86f * PI.toFloat() / 180f && lipAngle > -86f * PI.toFloat() / 180f
        val lipX = cx + R * sin(lipAngle)
        val lipZ = R * cos(lipAngle)

        // ---------------------------------------------------------------- the stub and the bottom cap
        steelBand(c, p, cx, bottom, stub, R * 0.42f, e, dark = true)
        steelBand(c, p, cx, bodyBottom, capH, Rc, e, crimp = true)
        // ---------------------------------------------------------------- the shell, its label wrapped round it
        shell(c, p, cx, bodyTop, bodyBottom, R, e, labelOf(name), yaw)
        // the lip: a band of tinplate standing off the shell, its velvet facing the right
        if (lipShows) lip(c, p, cx, bodyTop, bodyBottom, R, e, lipAngle)
        // ---------------------------------------------------------------- the top cap and the knob
        steelBand(c, p, cx, top, capH, Rc, e, crimp = true) // the top cap fills from its top down to the shell
        steelFace(c, p, cx, top, Rc, e)
        // the spool's knob
        val kr = R * 0.36f
        steelBand(c, p, cx, top - knobH, knobH, kr, e) // the knob: turned steel, not a black band
        steelFace(c, p, cx, top - knobH, kr, e, knob = true)
        // ---------------------------------------------------------------- the leader, out of the lip and curling toward us
        if (leader > 0f && lipShows) film(c, p, lipX + R * 0.2f, bodyTop + (bodyBottom - bodyTop) * 0.06f, bodyBottom - (bodyBottom - bodyTop) * 0.06f, R, e, leader, lipZ)
    }

    // ---------------------------------------------------------------- shading

    /** The shade (0 dark .. 1 lit) and the highlight of a vertical surface whose normal faces [theta] (0 = toward us). */
    private fun light(theta: Float, gloss: Float): Pair<Float, Float> {
        val nx = sin(theta); val nz = cos(theta)
        val diffuse = max(0f, nx * L[0] + nz * L[2])
        val shade = 0.32f + 0.68f * diffuse
        val spec = max(0f, nx * H[0] + nz * H[2]).pow(gloss)
        return shade to spec
    }

    private fun shell(c: Canvas, p: Paint, cx: Float, top: Float, bottom: Float, R: Float, e: Float, art: Bitmap, yaw: Float) {
        val n = 140
        val bw = art.width
        val bh = art.height
        val tau = 2 * PI.toFloat()
        for (i in 0 until n) {
            val t0 = -PI.toFloat() / 2 + PI.toFloat() * i / n
            val t1 = -PI.toFloat() / 2 + PI.toFloat() * (i + 1) / n
            val x0 = cx + R * sin(t0)
            val x1 = cx + R * sin(t1)
            if (x1 - x0 < 0.05f) continue
            val tm = (t0 + t1) / 2
            // the rims bow down toward us: a circle seen from above
            val yt = top + R * e * cos(tm)
            val yb = bottom + R * e * cos(tm)
            // the shell's own angle under this strip: the label goes round with the cartridge
            fun u(t: Float): Int {
                val phi = ((t - yaw) / tau + 0.5f).let { it - kotlin.math.floor(it) } // 0..1 round the shell, front at 0.5
                return (phi * bw).toInt().coerceIn(0, bw - 1)
            }
            val u0 = u(t0)
            val u1 = u(t1).let { if (it <= u0) u0 + 1 else it }.coerceAtMost(bw)
            src.set(u0, 0, u1, bh)
            dst.set(x0 - 0.5f, yt, x1 + 0.5f, yb)
            p.shader = null
            p.alpha = 255
            c.drawBitmap(art, src, dst, p)
            val (shade, spec) = light(tm, 26f)
            p.color = Color.argb(((1f - shade) * 235).toInt().coerceIn(0, 255), 8, 6, 4)
            c.drawRect(dst, p)
            p.color = Color.argb((spec * 150).toInt().coerceIn(0, 255), 255, 252, 244)
            c.drawRect(dst, p)
        }
        // a soft broad sheen from the room on the varnish, and the darkness where the caps meet it
        p.color = Color.argb(70, 0, 0, 0)
        c.drawOval(cx - R, top + R * e - R * e * 0.25f, cx + R, top + R * e + R * e * 0.25f, p.apply { alpha = 0 })
        p.shader = LinearGradient(0f, top, 0f, top + (bottom - top) * 0.08f, Color.argb(90, 0, 0, 0), 0, Shader.TileMode.CLAMP)
        c.drawRect(cx - R, top, cx + R, top + (bottom - top) * 0.1f + R * e, p)
        p.shader = LinearGradient(0f, bottom + R * e, 0f, bottom + R * e - (bottom - top) * 0.08f, Color.argb(80, 0, 0, 0), 0, Shader.TileMode.CLAMP)
        c.drawRect(cx - R, bottom - (bottom - top) * 0.1f, cx + R, bottom + R * e, p)
        p.shader = null
    }

    /**
     * A vertical band of steel [height] tall whose top rim is at [top]: satin metal,
     * the crimp's ridges round it when [crimp], its lower rim the visible front half-ellipse.
     */
    private fun steelBand(c: Canvas, p: Paint, cx: Float, top: Float, height: Float, r: Float, e: Float, crimp: Boolean = false, dark: Boolean = false) {
        val n = 96
        for (i in 0 until n) {
            val t0 = -PI.toFloat() / 2 + PI.toFloat() * i / n
            val t1 = -PI.toFloat() / 2 + PI.toFloat() * (i + 1) / n
            val tm = (t0 + t1) / 2
            val x0 = cx + r * sin(t0)
            val x1 = cx + r * sin(t1)
            val yt = top + r * e * cos(tm)
            val yb = top + height + r * e * cos(tm)
            val (shade, spec) = light(tm, 9f)
            val base = if (dark) 0.22f else 0.62f
            val v = (base * shade * 255 + spec * (if (dark) 70 else 150)).toInt().coerceIn(0, 255)
            p.shader = null
            p.color = Color.rgb(v, v, (v * 0.98f).toInt())
            c.drawRect(x0, yt, x1 + 0.6f, yb, p)
            if (crimp) {
                // the rolled edge at the top and the bottom of the cap: a bright line and its shadow
                p.color = Color.argb(110, 255, 255, 255)
                c.drawRect(x0, yt + height * 0.12f, x1 + 0.6f, yt + height * 0.2f, p)
                // the crimp's groove: a soft shade, not a drawn line
                p.color = Color.argb(34, 0, 0, 0)
                c.drawRect(x0, yt + height * 0.22f, x1 + 0.6f, yt + height * 0.34f, p)
                c.drawRect(x0, yb - height * 0.26f, x1 + 0.6f, yb - height * 0.14f, p)
            }
        }
    }

    /** The flat top of a cap (or the knob): an ellipse of spun steel with its turned rings. */
    private fun steelFace(c: Canvas, p: Paint, cx: Float, y: Float, r: Float, e: Float, knob: Boolean = false) {
        if (e < 0.05f) {
            // straight on, the top is edge-on: no face to see, only the rolled edge catching the lamp
            p.shader = LinearGradient(cx - r, 0f, cx + r, 0f, intArrayOf(0x66ffffff, 0xddffffff.toInt(), 0x55ffffff), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(cx - r, y, cx + r, y + r * 0.035f, p)
            p.shader = null
            return
        }
        val oval = RectF(cx - r, y - r * e, cx + r, y + r * e)
        p.shader = LinearGradient(oval.left, oval.top, oval.right, oval.bottom,
            intArrayOf(0xffe9e8e4.toInt(), 0xffb4b3ae.toInt(), 0xff7d7c78.toInt(), 0xffa9a8a3.toInt()),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(oval, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.02f
        for (k in 1..(if (knob) 2 else 4)) {
            val rr = r * (1f - k * (if (knob) 0.3f else 0.17f))
            p.color = Color.argb(70, 0, 0, 0)
            c.drawOval(cx - rr, y - rr * e, cx + rr, y + rr * e, p)
            p.color = Color.argb(60, 255, 255, 255)
            c.drawOval(cx - rr, y - rr * e + r * 0.02f, cx + rr, y + rr * e + r * 0.02f, p)
        }
        p.style = Paint.Style.FILL
        if (knob) {
            // the spool's slot
            p.color = 0xff2a2927.toInt()
            c.drawRoundRect(cx - r * 0.55f, y - r * e * 0.14f, cx + r * 0.55f, y + r * e * 0.14f, r * 0.05f, r * 0.05f, p)
        } else {
            // the cap's edge catches the lamp on its far side
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.025f
            p.color = Color.argb(150, 255, 255, 255)
            c.drawArc(oval, 180f, 120f, false, p)
            p.style = Paint.Style.FILL
        }
    }

    /** The light trap's lip: tinplate leaving the shell at [angle] toward the right, its black velvet on the outside. */
    private fun lip(c: Canvas, p: Paint, cx: Float, top: Float, bottom: Float, R: Float, e: Float, angle: Float) {
        val x0 = cx + R * sin(angle)
        val y0t = top + R * e * cos(angle)
        val y0b = bottom + R * e * cos(angle)
        val out = R * 0.32f
        val x1 = x0 + out
        val drop = R * e * 0.5f
        val plate = Path().apply {
            moveTo(x0, y0t + R * 0.06f); lineTo(x1, y0t + drop + R * 0.06f); lineTo(x1, y0b + drop - R * 0.06f); lineTo(x0, y0b - R * 0.06f); close()
        }
        p.shader = LinearGradient(x0, 0f, x1, 0f, 0xff8b8a85.toInt(), 0xff4d4c48.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(plate, p)
        p.shader = null
        // the velvet on its outer face
        val vel = Path().apply {
            moveTo(x1 - R * 0.06f, y0t + drop + R * 0.09f); lineTo(x1 + R * 0.02f, y0t + drop + R * 0.12f)
            lineTo(x1 + R * 0.02f, y0b + drop - R * 0.12f); lineTo(x1 - R * 0.06f, y0b + drop - R * 0.09f); close()
        }
        p.color = 0xff0d0c0e.toInt()
        c.drawPath(vel, p)
        p.color = Color.argb(40, 255, 255, 255)
        var y = y0t + drop + R * 0.15f
        while (y < y0b + drop - R * 0.15f) { c.drawRect(x1 - R * 0.04f, y, x1 + R * 0.01f, y + R * 0.012f, p); y += R * 0.05f }
    }

    /** The leader: film base out of the lip, a gentle curl toward the viewer, its perforations along the lower edge. */
    private fun film(c: Canvas, p: Paint, x0: Float, y0: Float, y1: Float, R: Float, e: Float, leader: Float, z: Float) {
        val len = R * 2f * leader
        val h = y1 - y0
        // the strip as a ribbon: its upper and lower edges follow the same gentle curve
        fun at(t: Float, edge: Float): FloatArray {
            val x = x0 + len * t
            val curl = sin(t * PI.toFloat() * 0.5f) * R * e * 0.9f
            return floatArrayOf(x, edge + curl)
        }
        val ribbon = Path()
        val n = 24
        for (i in 0..n) { val q = at(i / n.toFloat(), y0 + h * 0.08f); if (i == 0) ribbon.moveTo(q[0], q[1]) else ribbon.lineTo(q[0], q[1]) }
        // the leader's cut end: the shaped tongue
        val endTop = at(1f, y0 + h * 0.08f)
        val endBot = at(1f, y1)
        ribbon.quadTo(endTop[0] + R * 0.12f, endTop[1], endTop[0] + R * 0.12f, endTop[1] + h * 0.12f)
        ribbon.lineTo(endBot[0], endBot[1])
        for (i in n downTo 0) { val q = at(i / n.toFloat(), y1); ribbon.lineTo(q[0], q[1]) }
        ribbon.close()
        p.shader = LinearGradient(x0, y0, x0, y1, intArrayOf(0xe0704a2c.toInt(), 0xe85a3a20.toInt(), 0xf0402814.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(ribbon, p)
        p.shader = null
        // the base's sheen, a line along it
        p.color = Color.argb(70, 255, 220, 170)
        for (i in 0 until n) {
            val a0 = at(i / n.toFloat(), y0 + h * 0.2f)
            val a1 = at((i + 1) / n.toFloat(), y0 + h * 0.2f)
            c.drawRect(a0[0], a0[1], a1[0] + 0.5f, a0[1] + h * 0.025f, p)
        }
        // perforations along its lower edge, foreshortened by nothing: it faces us
        p.color = 0xff1a110a.toInt()
        val pitch = h * 0.135f
        var t = 0.06f
        while (t < 0.95f) {
            val q = at(t, y1 - h * 0.12f)
            c.drawRoundRect(q[0], q[1] - h * 0.075f, q[0] + pitch * 0.42f, q[1], h * 0.012f, h * 0.012f, p)
            t += pitch / len
        }
    }
}
