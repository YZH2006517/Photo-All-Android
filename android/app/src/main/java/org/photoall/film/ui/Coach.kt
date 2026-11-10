package org.photoall.film.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The hands-on tour. Nothing is dimmed (what a control does to the picture must stay in
 * view): the control in question is marked with a viewfinder's corner brackets, and the
 * words sit on a frame of negative, sprocket holes and edge print, that winds on to the
 * next frame at every step. Touches on the marked control reach it, and the step moves on
 * when the app reports that the user did the thing ([event]); touches elsewhere are held
 * back, and the frame's button flares so no one taps a dead spot for long. The marks
 * follow their control every frame (animations, sheets closing, the phone turning).
 *
 * Added over the whole activity (addContentView); dialogs (the sheets) open above it.
 */
class Coach(
    context: Context,
    private val steps: List<Step>,
    private val onAdvance: (() -> Unit)? = null,
    private val onEnd: (finished: Boolean) -> Unit,
) : FrameLayout(context) {

    enum class Gesture { NONE, TAP, HOLD, SWIPE, DRAG, CIRCLE }

    /** Where the frame goes: wherever it fits, or kept below the marked control. */
    enum class Place { AUTO, BELOW }

    class Step(
        val title: String,
        val body: String,
        /** The control to mark; none (or one not on screen) centres the frame. */
        val target: (() -> View?)? = null,
        /** The [event] that completes the step. */
        val waitFor: String? = null,
        val gesture: Gesture = Gesture.NONE,
        /** A button that moves on; without one a waiting step offers to skip it. */
        val button: String? = null,
        /** Where the ghost finger plays, when not on the target itself. */
        val anchor: (() -> View?)? = null,
        /** Touches on the control reach it (a step that waits for one, by default). */
        val through: Boolean = waitFor != null,
        val padDp: Float = 6f,
        val onEnter: (() -> Unit)? = null,
        val onLeave: (() -> Unit)? = null,
        /** Run by the button before moving on (a link to open). */
        val action: (() -> Unit)? = null,
        /** How long the result stays in view after [waitFor] before the film winds on. */
        val dwellMs: Long = 320,
        val place: Place = Place.AUTO,
    )

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // the frame's materials: paper, ink, film base, the edge print's amber
    private val paper = 0xfff3ede0.toInt()
    private val ink = 0xff211e1a.toInt()
    private val ink2 = 0xff5c554b.toInt()
    private val base = 0xff1c1916.toInt()
    private val amber = 0xfff2b544.toInt()
    private val edge = 0xffe39a36.toInt()

    private var index = -1
    private val step get() = steps.getOrNull(index)

    private val want = RectF()
    private val shown = RectF()
    private var hasMark = false
    private var markAlpha = 0f
    private var ending = false
    private var nudge = 0f
    private var stepStarted = 0L
    private var cardY = Float.NaN
    private var cardX = Float.NaN

    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val frame = FilmFrame()

    private val title = TextView(context).apply {
        textSize = 19f; setTextColor(ink); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); letterSpacing = 0.04f
    }
    private val body = TextView(context).apply {
        textSize = 14f; setTextColor(ink2); setLineSpacing(0f, 1.38f)
    }
    private val quit = TextView(context).apply {
        textSize = 13f; setTextColor(ink2); gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, dp(12f).toInt(), 0)
        setOnClickListener { end(finished = index >= steps.lastIndex) }
    }
    private val primary = TextView(context).apply {
        textSize = 14f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); gravity = Gravity.CENTER; letterSpacing = 0.06f
        setPadding(dp(20f).toInt(), 0, dp(20f).toInt(), 0)
        setOnClickListener { step?.action?.invoke(); next() }
    }
    private val rule = View(context).apply { setBackgroundColor(0x33211e1a) }

    /** The words scroll when the screen is short (landscape); the buttons never leave the frame. */
    private inner class BodyScroll : android.widget.ScrollView(context) {
        var maxHeight = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }
    private val bodyScroll = BodyScroll().apply {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(18f).toInt())
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(body, LayoutParams(-1, -2))
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(rule, LinearLayout.LayoutParams(dp(28f).toInt(), dp(1.5f).toInt()).apply { topMargin = dp(8f).toInt() })
        addView(bodyScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9f).toInt() })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(quit, LinearLayout.LayoutParams(-2, dp(40f).toInt()))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(primary, LinearLayout.LayoutParams(-2, dp(40f).toInt()))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f).toInt() })
    }
    private val card = FrameLayout(context).apply {
        isClickable = true // a touch on the frame stays on the frame
        background = frame
        elevation = dp(14f)
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val band = frame.band.toInt()
        setPadding(dp(22f).toInt(), band + dp(14f).toInt(), dp(18f).toInt(), band + dp(8f).toInt())
        addView(content, LayoutParams(-1, -2))
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(card, LayoutParams(cardWidth(resources.displayMetrics.widthPixels), LayoutParams.WRAP_CONTENT))
        card.alpha = 0f
    }

    fun start() {
        stepStarted = System.currentTimeMillis()
        index = 0
        fill(steps[0])
        steps[0].onEnter?.invoke()
        // the leader comes up: the frame rises and settles as the film is drawn in
        card.translationY = dp(48f)
        card.rotation = -2.5f
        card.animate().alpha(1f).translationY(0f).rotation(0f).setDuration(620)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1.05f)).start()
        frame.wind(-5f, 760)
        invalidate()
    }

    /** The app reports something the user did; the step waiting for it moves on. */
    fun event(key: String) {
        val s = step ?: return
        if (!ending && s.waitFor == key) postDelayed({ if (step === s) next() }, s.dwellMs)
    }

    /** Out of the way while a sheet is open, back when it closes. */
    fun pause() { visibility = View.INVISIBLE }
    fun resume() { if (!ending) { visibility = View.VISIBLE; invalidate() } }

    private fun fill(s: Step) {
        title.text = s.title
        body.text = s.body
        bodyScroll.scrollTo(0, 0)
        quit.text = if (index >= steps.lastIndex) "完成" else "退出教程"
        frame.frameNo = index + 1
        frame.frames = steps.size
        primaryLabel = null
        updatePrimary()
        cardY = Float.NaN
    }

    /** The film winds on one frame: the words slide off with it, the next ones come in. */
    private fun next() {
        step?.onLeave?.invoke()
        if (index + 1 >= steps.size) { index++; return end(finished = true) }
        index++
        val s = steps[index]
        stepStarted = System.currentTimeMillis()
        onAdvance?.invoke()
        frame.wind(frame.phase + 3f, 460)
        content.animate().cancel()
        content.animate().translationX(-dp(36f)).alpha(0f).setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.6f)).withEndAction {
                fill(s)
                content.translationX = dp(40f)
                content.animate().translationX(0f).alpha(1f).setDuration(320)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
            }.start()
        s.onEnter?.invoke()
        invalidate()
    }

    private var primaryLabel: String? = null

    /** A step that waits offers to skip; one whose control is not on screen just moves on. */
    private fun updatePrimary() {
        val s = step ?: return
        val label = s.button ?: if (s.waitFor != null) "跳过这步" else "下一步"
        if (label == primaryLabel) return
        primaryLabel = label
        primary.text = label
        val filled = isFilled()
        primary.setTextColor(ink)
        primary.background = GradientDrawable().apply {
            cornerRadius = dp(20f)
            if (filled) setColor(amber) else setStroke(dp(1.2f).toInt(), 0x66211e1a)
        }
    }

    private fun isFilled() = step?.button != null || primaryLabel == "下一步"

    private fun end(finished: Boolean) {
        if (ending) return
        ending = true
        step?.onLeave?.invoke()
        // the strip is drawn out: the frame slides away as it winds
        frame.wind(frame.phase + 6f, 420)
        card.animate().alpha(0f).translationY(dp(36f)).rotation(1.5f).setDuration(320)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).withEndAction {
                (parent as? android.view.ViewGroup)?.removeView(this)
                onEnd(finished)
            }.start()
        invalidate()
    }

    private val at = IntArray(2)
    private val me = IntArray(2)

    private fun rectOf(v: View?, pad: Float, out: RectF): Boolean {
        if (v == null || !v.isAttachedToWindow || !v.isShown || v.width == 0 || v.height == 0 || v.alpha < 0.05f) return false
        v.getLocationInWindow(at)
        getLocationInWindow(me)
        val x = (at[0] - me[0]).toFloat()
        val y = (at[1] - me[1]).toFloat()
        out.set(x - pad, y - pad, x + v.width * v.scaleX + pad, y + v.height * v.scaleY + pad)
        return true
    }

    private enum class Route { CARD, THROUGH, BLOCK }
    private var route = Route.BLOCK

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (visibility != View.VISIBLE || ending) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val inCard = card.alpha > 0.5f && e.x >= card.x && e.x <= card.x + card.width && e.y >= card.y && e.y <= card.y + card.height
            route = when {
                inCard -> Route.CARD
                hasMark && step?.through == true && want.contains(e.x, e.y) -> Route.THROUGH
                else -> Route.BLOCK
            }
            if (route == Route.BLOCK) {
                nudge = 1f
                // the frame gives a small shake; the button (or the marks) flare
                card.animate().cancel()
                card.translationX = dp(5f)
                card.animate().translationX(0f).setDuration(420)
                    .setInterpolator(android.view.animation.OvershootInterpolator(6f)).start()
                primary.animate().cancel()
                primary.scaleX = 1.08f; primary.scaleY = 1.08f
                primary.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                invalidate()
            }
        }
        return when (route) {
            Route.CARD -> super.dispatchTouchEvent(e)
            Route.THROUGH -> false // the view underneath takes the whole gesture
            Route.BLOCK -> true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = step
        val found = s != null && !ending && rectOf(s.target?.invoke(), dp(s.padDp), want)
        if (found != hasMark) { hasMark = found; updatePrimary() }
        if (found) {
            if (shown.isEmpty || markAlpha < 0.02f) shown.set(want)
            else {
                val k = 0.25f
                shown.left += (want.left - shown.left) * k; shown.top += (want.top - shown.top) * k
                shown.right += (want.right - shown.right) * k; shown.bottom += (want.bottom - shown.bottom) * k
            }
        }
        markAlpha += ((if (found) 1f else 0f) - markAlpha) * 0.18f
        nudge *= 0.92f

        val t = (System.currentTimeMillis() - stepStarted) / 1000f
        if (markAlpha > 0.02f) drawMarks(canvas, t)
        if (s != null && s.gesture != Gesture.NONE && markAlpha > 0.5f) {
            val zone = RectF()
            if (!rectOf(s.anchor?.invoke(), 0f, zone)) zone.set(shown)
            drawFinger(canvas, s.gesture, zone, t)
        }
        placeCard()
        if (!ending || card.alpha > 0f) postInvalidateOnAnimation()
    }

    /**
     * A viewfinder's corner brackets round the control, breathing, with a hairline between
     * them and a soft amber halo; they close in once as the step begins.
     */
    private fun drawMarks(canvas: Canvas, t: Float) {
        val breath = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 1.5f)
        val closing = (1f - (t / 0.45f).coerceIn(0f, 1f)).let { it * it }
        val out = dp(3f + 3f * breath + 8f * nudge) + dp(22f) * closing
        val r = RectF(shown.left - out, shown.top - out, shown.right + out, shown.bottom + out)
        // a control as wide as the screen keeps its brackets on the screen
        val m = dp(5f)
        r.set(maxOf(r.left, m), maxOf(r.top, m), minOf(r.right, width - m), minOf(r.bottom, height - m))
        val a = markAlpha * (1f - closing * 0.7f)
        // the halo
        mark.color = amber
        mark.strokeWidth = dp(10f)
        mark.alpha = ((26 + 30 * breath + 90 * nudge) * a).toInt().coerceIn(0, 255)
        val round = abs(shown.width() - shown.height()) < 0.15f * maxOf(shown.width(), shown.height())
        val radius = if (round) r.width() / 2f else dp(14f)
        canvas.drawRoundRect(r, radius, radius, mark)
        // the hairline
        mark.strokeWidth = dp(1f)
        mark.alpha = ((90 + 60 * nudge) * a).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(r, radius, radius, mark)
        // the brackets
        val len = min(dp(18f), min(r.width(), r.height()) / 3f)
        mark.strokeWidth = dp(3f)
        mark.alpha = (255 * a).toInt().coerceIn(0, 255)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            canvas.drawLine(x, y, x + dx * len, y, mark)
            canvas.drawLine(x, y, x, y + dy * len, mark)
        }
        corner(r.left, r.top, 1f, 1f); corner(r.right, r.top, -1f, 1f)
        corner(r.left, r.bottom, 1f, -1f); corner(r.right, r.bottom, -1f, -1f)
    }

    /** A step moving on by its button: the button glows, breathing, and flares on a held-back touch. */
    private val btnAt = IntArray(2)
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (step == null || !isFilled() || card.alpha < 0.3f || primary.width == 0 || content.alpha < 0.5f) return
        primary.getLocationInWindow(btnAt)
        getLocationInWindow(me)
        val l = (btnAt[0] - me[0]).toFloat()
        val tp = (btnAt[1] - me[1]).toFloat()
        val r = RectF(l, tp, l + primary.width * primary.scaleX, tp + primary.height * primary.scaleY)
        val t = (System.currentTimeMillis() - stepStarted) / 1000f
        val breath = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 1.4f)
        glow.color = amber
        for ((w, a) in listOf(14f to 0.16f, 8f to 0.3f, 2.5f to 0.75f)) {
            val grow = dp(2f + 4f * breath + 8f * nudge)
            val rr = RectF(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
            glow.strokeWidth = dp(w * (0.6f + 0.4f * breath))
            glow.alpha = ((a * (0.55f + 0.45f * breath) + 0.3f * nudge) * 255 * card.alpha * content.alpha).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rr, rr.height() / 2f, rr.height() / 2f, glow)
        }
    }

    /** The ghost finger: a soft disc acting the gesture on a loop. */
    private fun drawFinger(canvas: Canvas, g: Gesture, zone: RectF, t: Float) {
        val cx = zone.centerX()
        val cy = zone.centerY()
        val r = dp(15f)
        fun disc(x: Float, y: Float, a: Float, scale: Float = 1f) {
            finger.style = Paint.Style.FILL
            finger.color = Color.WHITE
            finger.alpha = (a * 170).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r * scale, finger)
            finger.style = Paint.Style.STROKE
            finger.strokeWidth = dp(2f)
            finger.color = amber
            finger.alpha = (a * 240).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r * scale, finger)
        }
        fun ripple(x: Float, y: Float, p: Float) {
            if (p <= 0f || p >= 1f) return
            finger.style = Paint.Style.STROKE
            finger.strokeWidth = dp(2f)
            finger.color = amber
            finger.alpha = ((1f - p) * 220).toInt()
            canvas.drawCircle(x, y, r + dp(26f) * p, finger)
        }
        fun ease(x: Float) = (1f - cos(x.coerceIn(0f, 1f) * PI.toFloat())) / 2f
        when (g) {
            Gesture.TAP -> {
                val p = (t % 1.4f) / 1.4f
                val press = if (p in 0.25f..0.4f) 0.82f else 1f
                disc(cx, cy, if (p < 0.15f) p / 0.15f else if (p > 0.8f) (1f - p) / 0.2f else 1f, press)
                ripple(cx, cy, (p - 0.3f) / 0.5f)
            }
            Gesture.HOLD -> {
                val p = (t % 2.2f) / 2.2f
                val a = if (p < 0.1f) p / 0.1f else if (p > 0.85f) (1f - p) / 0.15f else 1f
                disc(cx, cy, a, if (p in 0.15f..0.75f) 0.86f else 1f)
                val fill = ((p - 0.15f) / 0.5f).coerceIn(0f, 1f)
                if (fill > 0f && p < 0.85f) {
                    finger.style = Paint.Style.STROKE; finger.strokeWidth = dp(3f); finger.color = amber
                    finger.alpha = (230 * a).toInt()
                    val o = r + dp(9f)
                    canvas.drawArc(cx - o, cy - o, cx + o, cy + o, -90f, 360f * fill, false, finger)
                }
            }
            Gesture.SWIPE, Gesture.DRAG -> {
                val period = if (g == Gesture.SWIPE) 1.6f else 2.4f
                val cycle = (t / period).toInt()
                val p = (t % period) / period
                val span = min(zone.width() * 0.32f, dp(if (g == Gesture.SWIPE) 70f else 90f))
                val dir = if (g == Gesture.SWIPE || cycle % 2 == 0) -1f else 1f // a swipe shows the way: leftwards
                val x = cx - dir * span + dir * 2f * span * ease((p - 0.15f) / 0.6f)
                val a = if (p < 0.12f) p / 0.12f else if (p > 0.82f) (1f - p) / 0.18f else 1f
                finger.style = Paint.Style.FILL; finger.color = amber
                for (i in 1..5) {
                    val tx = x - dir * dp(7f) * i
                    finger.alpha = (a * (90 - i * 16)).toInt().coerceAtLeast(0)
                    if (abs(tx - cx) <= span) canvas.drawCircle(tx, cy, r * (1f - i * 0.12f), finger)
                }
                disc(x, cy, a, 0.9f)
            }
            Gesture.CIRCLE -> {
                val p = (t % 2.0f) / 2.0f
                val o = zone.width() / 2f + dp(16f)
                val ang = -PI.toFloat() / 2f + 2f * PI.toFloat() * ease(p)
                finger.style = Paint.Style.STROKE; finger.strokeWidth = dp(3f); finger.color = amber
                finger.alpha = 210
                canvas.drawArc(cx - o, cy - o, cx + o, cy + o, -90f, 360f * ease(p), false, finger)
                disc(cx + o * cos(ang), cy + o * sin(ang), if (p > 0.9f) (1f - p) / 0.1f else 1f, 0.8f)
            }
            Gesture.NONE -> {}
        }
    }

    /** Narrow and tall in portrait; wide in landscape, where height is short. */
    private fun cardWidth(w: Int): Int {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return min(dp(if (landscape) 560f else 360f), w - dp(28f)).toInt()
    }

    /** Below the marks if it fits, else above, else beside; centred with no marks. */
    private fun placeCard() {
        if (card.width == 0 || card.height == 0) return
        val insets = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())
        val top = dp(14f) + (insets?.top ?: 0)
        val bottom = height - dp(14f) - (insets?.bottom ?: 0)
        val gap = dp(26f)
        var x = (width - card.width) / 2f
        val y: Float
        val below = step?.place == Place.BELOW
        if (markAlpha < 0.5f) y = (height - card.height) / 2f
        else if (below) y = (shown.bottom + gap).coerceAtMost(bottom - card.height)
        else if (bottom - (shown.bottom + gap) >= card.height) y = shown.bottom + gap
        else if (shown.top - gap - top >= card.height) y = shown.top - gap - card.height
        else {
            x = if (shown.left > width - shown.right) (shown.left - gap - card.width).coerceAtLeast(dp(14f))
                else (shown.right + gap).coerceAtMost(width - card.width - dp(14f))
            y = (shown.centerY() - card.height / 2f).coerceIn(top, bottom - card.height)
        }
        if (cardY.isNaN()) { cardY = y; cardX = x } else { cardY += (y - cardY) * 0.2f; cardX += (x - cardX) * 0.2f }
        card.x = cardX
        card.y = cardY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            card.layoutParams = (card.layoutParams as LayoutParams).apply { width = cardWidth(w) }
            // what is left of the height once the film's bands, the title and the buttons are in
            val insets = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())
            val room = h - (insets?.top ?: 0) - (insets?.bottom ?: 0) - dp(28f) - frame.band * 2 - dp(22f) - dp(124f)
            bodyScroll.maxHeight = maxOf(dp(60f).toInt(), room.toInt())
            bodyScroll.requestLayout()
        }
        cardY = Float.NaN
    }

    /**
     * A frame of 135 negative as the card: the film base with its sprocket holes cut
     * through (what is behind shows in them), edge print in amber between holes and frame,
     * and the frame itself, a sheet of paper. [wind] runs the holes along like film
     * advancing in a camera.
     */
    private inner class FilmFrame : Drawable() {
        val band = dp(26f)
        var phase = 0f
        var frameNo = 1
        var frames = 1
        private var windAnim: android.animation.ValueAnimator? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val print = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = dp(8.5f)
            letterSpacing = 0.12f
        }
        private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        private val rect = RectF()

        fun wind(to: Float, ms: Long) {
            windAnim?.cancel()
            windAnim = android.animation.ValueAnimator.ofFloat(phase, to).apply {
                duration = ms
                interpolator = android.view.animation.PathInterpolator(0.3f, 0f, 0.15f, 1f)
                addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
                start()
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val radius = dp(9f)
            // the base, with the holes cut out of it
            path.reset()
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            val pitch = dp(17f)
            val hw = dp(8.5f)
            val hh = dp(6.5f)
            val shift = -(((phase * pitch) % pitch + pitch) % pitch)
            var x = b.left + dp(6f) + shift
            while (x < b.right) {
                if (x > b.left + dp(3f) && x + hw < b.right - dp(3f)) {
                    for (cy in floatArrayOf(b.top + dp(9f), b.bottom - dp(9f))) {
                        val hole = RectF(x, cy - hh / 2f, x + hw, cy + hh / 2f)
                        path.addRoundRect(hole, dp(1.6f), dp(1.6f), Path.Direction.CW)
                    }
                }
                x += pitch
            }
            fill.color = base
            canvas.drawPath(path, fill)
            // the frame: paper, a hair inside the base
            rect.set(b.left + dp(7f), b.top + band, b.right - dp(7f), b.bottom - band)
            fill.color = paper
            canvas.drawRoundRect(rect, dp(3f), dp(3f), fill)
            // edge print, between the holes and the frame
            print.color = edge
            val yTop = b.top + band - dp(4.5f)
            val yBot = b.bottom - band + dp(11f)
            print.textAlign = Paint.Align.LEFT
            canvas.drawText("PHOTO ALL  400", b.left + dp(16f), yTop, print)
            canvas.drawText("%02d / %02d".format(frameNo, frames), b.left + dp(16f), yBot, print)
            print.textAlign = Paint.Align.RIGHT
            canvas.drawText("▸ ${frameNo}A", b.right - dp(16f), yTop, print)
            // the roll's progress as frame ticks
            val n = frames.coerceAtMost(24)
            val tick = dp(7f)
            val gap = dp(3f)
            var tx = b.right - dp(16f) - n * (tick + gap) + gap
            for (i in 1..n) {
                fill.color = if (i <= frameNo) edge else 0x55e39a36
                canvas.drawRoundRect(tx, yBot - dp(6f), tx + tick, yBot - dp(1f), dp(1f), dp(1f), fill)
                tx += tick + gap
            }
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, dp(9f))
            outline.alpha = 1f
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
