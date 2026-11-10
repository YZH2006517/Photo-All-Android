package org.photoall.film.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The landscape screen as a matte, toy-like camera body (2.5D). Everything is lit softly
 * from above with no gloss: surfaces only darken toward their lower edges and rims.
 * Keys sit in sockets and throw their shadow down into them; rollers lie in cavities with
 * notched teeth; the shutter is a matte white disc in a dark ring. Shadows are drawn
 * inside each view's own bounds, so no parent clips them. Fixed colours: a body, not
 * themed chrome.
 */
object Toy {
    // All from the theme in use (the activity is rebuilt when the theme changes).
    private val p get() = Style.palette
    val light get() = p.light
    val BODY get() = p.surface
    val BODY_TOP get() = lighten(p.surface, if (light) 0.25f else 0.05f)
    val BODY_BOTTOM get() = darken(p.surface, if (light) 0.06f else 0.3f)
    val SOCKET get() = if (light) darken(p.surface, 0.14f) else darken(p.bg, 0.25f)
    val INK get() = p.text
    val INK2 get() = p.text2
    val SHADOW get() = if (light) 0x66000000 else 0xcc000000.toInt()
    val LIP_TOP get() = if (light) 0x1f000000 else 0x4d000000
    /** The soft shade inside a cavity, all round its wall. */
    val INSET get() = if (light) 0x47000000 else 0x8c000000.toInt()
    val LIP_BOTTOM get() = if (light) 0xb3ffffff.toInt() else 0x1cffffff
    /** Key faces: lightest above the centre, darker toward the rim. */
    fun keyFace() = if (light) intArrayOf(lighten(p.surface, 0.55f), lighten(p.surface, 0.2f), p.surface2)
        else intArrayOf(lighten(p.surface2, 0.16f), lighten(p.surface2, 0.05f), p.surface2)
    val AMBER get() = p.accent
    /** The look roller in the theme's accent; the exposure roller in a hue across from it. */
    fun lookRoller() = Triple(lighten(p.accent, 0.3f), p.accent, darken(p.accent, 0.3f))
    fun exposureRoller(): Triple<Int, Int, Int> {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(p.accent, hsv)
        // a red-only theme (night) keeps both in red; otherwise turn the hue well away
        val shift = if (p.name == "暗房红灯") 0f else 205f
        hsv[0] = (hsv[0] + shift) % 360f
        hsv[1] = maxOf(hsv[1], 0.55f)
        hsv[2] = if (shift == 0f) hsv[2] * 0.7f else maxOf(hsv[2], 0.85f)
        val mid = android.graphics.Color.HSVToColor(hsv)
        return Triple(lighten(mid, 0.28f), mid, darken(mid, 0.3f))
    }

    fun lighten(c: Int, k: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xff) + (255 - ((c shr s) and 0xff)) * k).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun darken(c: Int, k: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xff) * (1 - k)).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** A key in a socket: moves down into it while pressed. */
    fun press(view: View, depth: Float) {
        val down = ObjectAnimator.ofPropertyValuesHolder(PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, depth)).setDuration(70)
        val up = ObjectAnimator.ofPropertyValuesHolder(PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f)).setDuration(240).apply {
            interpolator = OvershootInterpolator(2.2f)
        }
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), up)
        }
    }

    private val shadowBlur = HashMap<Float, BlurMaskFilter>()
    fun blur(radius: Float) = shadowBlur.getOrPut(radius) { BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
}

/**
 * A matte key (dome or rounded slab): the face fills the top of its bounds and the soft
 * shadow it throws falls in the [depth] below it, inside the bounds. Lit from above with
 * no shine: the face is lightest just above its centre and darkens toward its rim.
 * [radius] < 0 makes it round. Pressed, the shadow tightens (the view moves down too).
 */
class SoftKey(private val density: Float, private val radius: Float = -1f, private val depth: Float = 5f,
              private val face: IntArray = Toy.keyFace(),
              @Suppress("unused") private val side: Int = 0) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pressed = false

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p; invalidateSelf(); return true
    }

    override fun draw(canvas: Canvas) {
        val d = depth * density
        val b = bounds
        rect.set(b.left + d * 0.6f, b.top + d * 0.4f, b.right - d * 0.6f, b.bottom - d * 0.6f)
        val faceH = rect.height()
        val r = if (radius < 0) min(rect.width(), faceH) / 2f else radius * density
        // the shadow below: blurred, darker and longer when the key is up
        val drop = if (pressed) d * 0.25f else d * 0.7f
        paint.shader = null
        paint.maskFilter = Toy.blur(if (pressed) d * 0.5f else d * 0.9f)
        paint.color = if (pressed) (Toy.SHADOW and 0x00ffffff) or (((Toy.SHADOW ushr 24) * 3 / 4) shl 24) else Toy.SHADOW
        canvas.drawRoundRect(rect.left + d * 0.15f, rect.top + drop, rect.right - d * 0.15f, rect.bottom + drop, r, r, paint)
        paint.maskFilter = null
        // the face: matte, lightest above the centre
        paint.color = 0xffffffff.toInt()
        paint.shader = RadialGradient(rect.centerX(), rect.top + faceH * 0.32f, maxOf(rect.width(), faceH) * 0.78f,
            face, floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        // the rim turning away: a soft darkening at the lower edge
        paint.shader = LinearGradient(0f, rect.top + faceH * 0.55f, 0f, rect.bottom, 0x00000000, 0x55000000, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        // where light grazes the top edge: a faint, wide lightening (no gloss)
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.top + faceH * 0.25f, 0x18ffffff, 0x00ffffff, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A cavity cut into the body (a socket for a key, the slot of a slider, the frame round
 * the picture): its floor a shade darker than the body, a soft shade all round its wall
 * (a touch more under the upper edge, the light being above), and the lower rim of the
 * cut catching a little light.
 */
class Well(private val density: Float, private val radius: Float = -1f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = android.graphics.Path()
    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = if (radius < 0) min(rect.width(), rect.height()) / 2 else radius * density
        // the cut edge: barely shaded above, catching light below
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, Toy.LIP_TOP, Toy.LIP_BOTTOM, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        rect.inset(1.2f * density, 1.2f * density)
        val ri = (r - 1.2f * density).coerceAtLeast(0f)
        paint.shader = null
        paint.color = Toy.SOCKET
        canvas.drawRoundRect(rect, ri, ri, paint)
        // the inset shade: a blurred ring just outside the floor, seen only inside it,
        // nudged down a little so the upper wall shades more than the lower
        clip.reset()
        clip.addRoundRect(rect, ri, ri, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val ring = 7 * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        paint.color = Toy.INSET
        paint.maskFilter = Toy.blur(4.5f * density)
        canvas.drawRoundRect(rect.left - ring / 2, rect.top - ring / 2 + 1.6f * density,
            rect.right + ring / 2, rect.bottom + ring / 2 + 1.6f * density, ri + ring / 2, ri + ring / 2, paint)
        paint.maskFilter = null
        paint.style = Paint.Style.FILL
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A knurled roller lying in a cavity, matte: a cylinder lit from above, lighter bands for
 * its ridges, and notched teeth along its lower edge between the ridges. Roll it with the
 * thumb (sideways): each ridge's worth is one step ([onStep]: +1 rolling left, -1 rolling
 * right); the ridges follow the finger and settle when let go. Tap and long press too.
 */
class Roller(context: Context, private val light: Int, private val mid: Int, private val dark: Int) : View(context) {
    var onStep: (Int) -> Unit = {}
    var onTap: () -> Unit = {}
    var onLongPress: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val step = 22 * density
    private val ridgeGap = 12 * density
    private var phase = 0f
    private var acc = 0f
    private var lastX = 0f
    private var rolling = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val cavity = Well(context.resources.displayMetrics.density, 10f)
    private var settle: ValueAnimator? = null
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(); return true }
        override fun onLongPress(e: MotionEvent) { if (!rolling) onLongPress() }
    })

    init { isClickable = true }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                settle?.cancel()
                lastX = e.x; acc = 0f; rolling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                lastX = e.x
                rolling = rolling || abs(acc + dx) > 6 * density
                phase += dx
                acc += dx
                while (abs(acc) >= step) {
                    val s = if (acc < 0) 1 else -1
                    acc += s * step
                    onStep(s)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // settle with a ridge facing straight out (one ridge = 10 degrees at the rim)
                val unit = ((width / 2f - 4 * density) * Math.PI / 18).toFloat().coerceAtLeast(1f)
                val target = (phase / unit).roundToInt() * unit
                settle = ValueAnimator.ofFloat(phase, target).apply {
                    duration = 220
                    interpolator = OvershootInterpolator(1.6f)
                    addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        cavity.setBounds(0, 0, width, height)
        cavity.draw(canvas)
        val inset = 4 * density
        val left = inset
        val right = w - inset
        val top = inset
        val bottom = h - inset - 1.5f * density
        val r = 9 * density
        // the wheel's shadow in its cavity
        paint.shader = null
        paint.maskFilter = Toy.blur(4 * density)
        paint.color = Toy.SHADOW
        canvas.drawRoundRect(left + 2 * density, top + 4 * density, right - 2 * density, bottom + 2 * density, r, r, paint)
        paint.maskFilter = null
        rect.set(left, top, right, bottom)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        // the wheel's face, matte: a lighter band where its top edge meets the light, shade below
        paint.color = 0xffffffff.toInt() // opaque: a shader is drawn at the paint's alpha
        paint.shader = LinearGradient(0f, top, 0f, bottom, intArrayOf(light, mid, mid, dark),
            floatArrayOf(0f, 0.18f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        // the knurl: ridges at equal angles around the wheel, so they crowd together toward
        // both ends where the cylinder turns away; each ridge a lit face and a shaded groove
        val cx = (left + right) / 2
        val half = (right - left) / 2
        val stepAngle = Math.PI / 18                  // 10 degrees between ridges
        val turn = phase / half                      // the roll, in radians at the rim
        val startK = Math.floor((-Math.PI / 2 - turn) / stepAngle).toInt()
        val endK = Math.ceil((Math.PI / 2 - turn) / stepAngle).toInt()
        for (k in startK..endK) {
            val a0 = turn + k * stepAngle
            val a1 = a0 + stepAngle
            if (a1 < -Math.PI / 2 || a0 > Math.PI / 2) continue
            val x0 = cx + half * Math.sin(a0.coerceIn(-Math.PI / 2, Math.PI / 2)).toFloat()
            val x1 = cx + half * Math.sin(a1.coerceIn(-Math.PI / 2, Math.PI / 2)).toFloat()
            val xm = cx + half * Math.sin(((a0 + a1) / 2).coerceIn(-Math.PI / 2, Math.PI / 2)).toFloat()
            val facing = Math.cos((a0 + a1) / 2).toFloat().coerceAtLeast(0f) // 1 facing us
            // lit half of the ridge (the light comes from the upper left)
            paint.shader = null
            paint.color = ((0x10 + (0x30 * facing).toInt()) shl 24) or 0xffffff
            canvas.drawRect(x0, top, xm, bottom, paint)
            // groove at its far edge
            paint.color = ((0x30 + (0x30 * (1 - facing)).toInt()) shl 24)
            canvas.drawRect(x1 - maxOf(0.8f * density, (x1 - x0) * 0.18f), top, x1, bottom, paint)
        }
        // the ends turning away into shade
        paint.shader = LinearGradient(left, 0f, right, 0f, intArrayOf(0x80000000.toInt(), 0x00000000, 0x00000000, 0x80000000.toInt()),
            floatArrayOf(0f, 0.22f, 0.78f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        // the top edge: a thin light rim (the wheel's upper face seen edge-on)
        paint.shader = LinearGradient(0f, top, 0f, top + 5 * density, 0x55ffffff, 0x00ffffff, Shader.TileMode.CLAMP)
        canvas.drawRect(left, top, right, top + 5 * density, paint)
        paint.shader = null
        // the teeth along the lower edge: small notches at the grooves facing us
        paint.color = Toy.SOCKET
        for (k in startK..endK) {
            val a = turn + (k + 1) * stepAngle
            if (a < -Math.PI / 2 * 0.85 || a > Math.PI / 2 * 0.85) continue
            val x = cx + half * Math.sin(a).toFloat()
            val nw = 2.2f * density * Math.cos(a).toFloat()
            canvas.drawRect(x - nw, bottom - 2.6f * density, x + nw, bottom, paint)
        }
        canvas.restore()
    }
}

/**
 * The lens slot: a cavity with a dot for each lens and a matte knob, showing the focal
 * length, on the one in use. Drag the knob (or tap a dot) and it springs to the nearest.
 */
class LensWell(context: Context) : View(context) {
    var onSelect: (Int) -> Unit = {}
    private val density = resources.displayMetrics.density
    private var stops: List<String> = emptyList()
    private var current = 0
    private var knob = 0f
    private var dragging = false
    private var animator: ValueAnimator? = null
    private val well = Well(density)
    private val key = SoftKey(density, depth = 4f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private var knobLabel = ""

    init { isClickable = true }

    fun show(labels: List<String>, selected: Int, shown: String) {
        stops = labels
        knobLabel = shown
        if (!dragging && selected != current) moveTo(selected) else if (!dragging) knob = selected.toFloat()
        current = selected
        invalidate()
    }

    private fun x(i: Float): Float {
        val pad = height * 0.5f
        return if (stops.size <= 1) width / 2f else pad + (width - 2 * pad) * i / (stops.size - 1)
    }

    private fun index(px: Float): Float {
        val pad = height * 0.5f
        return if (stops.size <= 1) 0f else ((px - pad) / (width - 2 * pad) * (stops.size - 1)).coerceIn(0f, stops.size - 1f)
    }

    private fun moveTo(i: Int) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(knob, i.toFloat()).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { knob = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (stops.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); animator?.cancel(); dragging = true; knob = index(e.x); invalidate() }
            MotionEvent.ACTION_MOVE -> { knob = index(e.x); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val i = knob.roundToInt().coerceIn(0, stops.lastIndex)
                moveTo(i)
                if (i != current) { current = i; onSelect(i) }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (stops.isEmpty()) return
        val h = height.toFloat()
        well.setBounds(0, 0, width, height)
        well.draw(canvas)
        paint.color = Toy.INK2
        paint.alpha = 140
        for (i in stops.indices) canvas.drawCircle(x(i.toFloat()), h / 2, 2.2f * density, paint)
        paint.alpha = 255
        val kr = h / 2 - 2 * density
        val kx = x(knob)
        key.setBounds((kx - kr).roundToInt(), (2 * density).roundToInt(), (kx + kr).roundToInt(), (h - 1 * density).roundToInt())
        key.draw(canvas)
        text.textSize = 12 * density
        text.color = Toy.INK
        val label = if (dragging) stops[knob.roundToInt().coerceIn(0, stops.lastIndex)] else knobLabel
        canvas.drawText(label, kx, h / 2 + 1 * density, text)
        text.textSize = 7 * density
        text.color = Toy.INK2
        canvas.drawText("mm", kx, h / 2 + 9 * density, text)
    }
}

/** A slide switch: a matte knob in a slot; on, a small amber light glows at its end. */
class SlideSwitch(context: Context) : View(context) {
    var onToggle: (Boolean) -> Unit = {}
    var on = false
        private set
    private val density = resources.displayMetrics.density
    private var pos = 0f
    private val well = Well(density)
    private val key = SoftKey(density, depth = 3f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener { set(!on, animate = true); onToggle(on) }
    }

    fun set(value: Boolean, animate: Boolean = false) {
        on = value
        contentDescription = if (on) "HDR 开" else "HDR 关"
        if (!animate) { pos = if (on) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(pos, if (on) 1f else 0f).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.8f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        well.setBounds(0, 0, width, height)
        well.draw(canvas)
        val lx = width - height / 2f
        paint.color = Toy.AMBER
        val glow = pos.coerceIn(0f, 1f)
        if (glow > 0.05f) {
            paint.alpha = (200 * glow).toInt()
            paint.maskFilter = Toy.blur(4 * density)
            canvas.drawCircle(lx, height / 2f, 4.5f * density, paint)
            paint.maskFilter = null
        }
        paint.alpha = (70 + 185 * glow).toInt()
        canvas.drawCircle(lx, height / 2f, 2.4f * density, paint)
        paint.alpha = 255
        val kr = height / 2f - 2 * density
        val kx = kr + 2 * density + (width - 2 * kr - 4 * density) * pos
        key.setBounds((kx - kr).roundToInt(), (2 * density).roundToInt(), (kx + kr).roundToInt(), (height - 1 * density).roundToInt())
        key.draw(canvas)
    }
}

/** The body: matte near-black, a little lighter toward the top. */
class ToyBackground(@Suppress("unused") private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(Toy.BODY_TOP, Toy.BODY, Toy.BODY_BOTTOM), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Swaps a readout's text with a little slide. */
fun android.widget.TextView.swapText(value: CharSequence, up: Boolean = true) {
    if (text.toString() == value.toString()) return
    if (!isAttachedToWindow || width == 0) { text = value; return }
    val dy = 6 * resources.displayMetrics.density * if (up) 1 else -1
    animate().cancel()
    animate().alpha(0f).translationY(-dy).setDuration(90).withEndAction {
        text = value
        translationY = dy
        animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }.start()
}
