package org.photoall.film.gallery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A light table, as on the desktop Photo All: a diffuser lit from below (or a plain table
 * with the lamp off), the roll's frames cut and laid on it. Two ways of looking:
 *
 *  - 台面: the frames lie where the user put them. Drag a frame to move it, pinch on it to
 *    size it, drag or pinch the bare table to move or zoom the whole view.
 *  - 联排: the roll as one uncut strip running down the table; it scrolls, and plays on its
 *    own at a steady pace.
 *
 * Holding a finger still brings a refracting loupe up above it, showing the photo's own
 * pixels under the finger; the loupe can also be left lying on the table. Marks are drawn
 * as a grease pencil would: a circle round a keeper (the favourite), a cross on a reject.
 * Light and loupe change only what is shown, never the photos.
 */
class LightTable(context: Context) : View(context) {

    class Frame(var photo: Photos.Photo, val number: Int, val aspect: Float, val orientation: Int) {
        var bitmap: Bitmap? = null
        // on the table, in world units (the default layout is 1000 wide): centre and picture width
        var x = 0f
        var y = 0f
        var w = 0f
        var z = 0
        val h get() = w / aspect
    }

    enum class Mode { TABLE, STRIP }

    var frames: List<Frame> = emptyList()
        private set
    var mode = Mode.TABLE
        private set
    var current: Frame? = null
        private set
    var filmName: (Frame) -> String = { "" }
    var rejected: (Frame) -> Boolean = { false }
    var onCurrent: (Frame) -> Unit = {}
    var onDoubleTap: (Frame) -> Unit = {}
    /** Two quick taps on the table where no frame lies. */
    var onDoubleTapBlank: () -> Unit = {}
    private var lastBlankTap = 0L
    private var lastBlankX = 0f
    private var lastBlankY = 0f
    var onArranged: () -> Unit = {}
    var onPlaying: (Boolean) -> Unit = {}
    var onLoupe: (Boolean) -> Unit = {}
    var onTick: () -> Unit = {}
    /** The loupe's source of real pixels (set by the activity, which owns the threads). */
    var hiRes: HiRes? = null

    private val d = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ----- the table's view (台面) -----------------------------------------------------
    var viewScale = 0f   // screen px per world unit; 0 until laid out
    var viewX = 0f
    var viewY = 0f

    // ----- the strip (联排) ---------------------------------------------------------------
    private var stripScroll = 0f
    private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
    private var stripTops = FloatArray(0)
    private var stripHeights = FloatArray(0)
    var playing = false
        private set

    /** 联排 across the screen (frames side by side, scrolling sideways) instead of down it. */
    var stripHorizontal = false
        set(v) { field = v; layoutStrip(); if (mode == Mode.STRIP) scrollStripTo(current, animate = false); invalidate() }

    /** The strip's running direction on the screen: its length in view pixels. */
    private fun alongSize() = if (stripHorizontal) width.toFloat() else height.toFloat()
    private var lastPlayFrame = 0L

    // ----- light ---------------------------------------------------------------------------
    var lampOn = true
        private set
    private var lamp = 1f // 0 off .. 1 on, flickers on the way up
    private var lampAnimator: ValueAnimator? = null

    // ----- loupe ---------------------------------------------------------------------------
    var magnification = 3f
    var loupeLocked = false
        private set
    private var loupeHeld = false
    private var lensX = 0f   // where the glass is drawn
    private var lensY = 0f
    private var sampleX = 0f // the point it magnifies
    private var sampleY = 0f
    private var lensAppear = 0f
    private var lensAnimator: ValueAnimator? = null
    private val refraction = 0.14f
    /** The glass's radius as a share of the table's shorter side (设置 → 放大镜大小). */
    var lensSize = 0.35f
        set(v) { field = v.coerceIn(0.12f, 0.45f); invalidate() }
    private val lensRadius get() = min(width, height) * lensSize

    // ----- the user's settings ---------------------------------------------------------------
    /** The table's colour with the lamp off (设置 → 关灯底色). */
    var offColor = OFF_BASE
    /** The china marker's colour (the style may change it). */
    var pencilColor = PENCIL
        set(v) { field = v; invalidate() }
    /** Backlight brightness, 40..160 (%), as the desktop table's: the panel and the light through the frames. */
    var brightness = 100
        set(v) { field = v.coerceIn(40, 160); invalidate() }
    /** Film around each frame (perforations, edge print), or the bare pictures. */
    var borders = true
        set(v) { field = v; layoutStrip(); if (mode == Mode.STRIP) scrollStripTo(current, animate = false); invalidate() }
    /** 联排 playback: seconds each frame takes to pass. */
    var secondsPerFrame = 6f
    var onOpen: (Frame) -> Unit = {}
    private fun onBase(): Int {
        val v = brightness * 0.52f
        return Color.rgb((182 + v).toInt().coerceAtMost(255), (185 + v).toInt().coerceAtMost(255), (182 + v).toInt().coerceAtMost(255))
    }
    private val lens: LensRenderer = if (android.os.Build.VERSION.SDK_INT >= 33) RefractingLens() else FlatLens()
    val refracts get() = lens is RefractingLens

    // ----- intro ---------------------------------------------------------------------------
    private var introStart = 0L
    private var introPlaying = false

    // ----- paints --------------------------------------------------------------------------
    private val basePaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint()
    private val grainPaint = Paint().apply { shader = BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); alpha = 26 }
    private val filmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint().apply {
        // 135°: from the top-left corner across the picture, in a unit square scaled to each frame
        shader = LinearGradient(0f, 0f, 1f, 1f, intArrayOf(0x0dffffff, 0x00ffffff, 0x04ffffff, 0x00ffffff),
            floatArrayOf(0f, 0.36f, 0.75f, 1f), Shader.TileMode.CLAMP)
    }
    private val glossEdge = Paint().apply { style = Paint.Style.STROKE; color = 0x44ffffff }
    private val dimPaint = Paint().apply { color = 0x99101010.toInt() }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = GalleryActivity.FILM_INK
    }
    private val pencil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GalleryActivity.FILM_INK }
    private val emptyPaint = Paint().apply { color = 0xff2a2724.toInt() }
    private val rect = RectF()
    private val rect2 = RectF()
    private val pieceR = RectF()
    private val imageR = RectF()
    private val holeR = RectF()
    private val shadowR = RectF()
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    // ===== state from the activity ===========================================================

    /** A new roll: frames placed already (by the activity, from what it saved), the view fitted. */
    fun show(frames: List<Frame>, mode: Mode, current: Frame?, savedView: FloatArray?, intro: Boolean) {
        stop()
        scroller.forceFinished(true)
        this.frames = frames
        this.mode = mode
        stowed = false; stowPlaying = false
        this.current = current ?: frames.firstOrNull()
        pendingView = savedView
        viewScale = 0f
        if (width > 0) fitView()
        layoutStrip()
        if (mode == Mode.STRIP) scrollStripTo(this.current, animate = false)
        introPlaying = intro && ValueAnimator.areAnimatorsEnabled() && frames.isNotEmpty()
        introStart = android.os.SystemClock.uptimeMillis() + 60
        this.current?.let(onCurrent)
        invalidate()
    }

    /** The same frames, with fresh photo records (favourites changed) or bitmaps arrived. */
    fun refresh() = invalidate()

    private var pendingView: FloatArray? = null

    private fun fitView() {
        val v = pendingView
        pendingView = null
        if (v != null && v.size == 3 && v[0] > 0) {
            viewScale = v[0] * width / 1000f; viewX = v[1] * width; viewY = v[2] * width
        } else {
            viewScale = width / 1000f
            viewX = 0f
            viewY = 16 * d - (frames.minOfOrNull { it.y - pieceHalfH(it) } ?: 0f) * viewScale
        }
    }

    /** The table's view, for saving: scale and offsets in widths. */
    fun savedView() = floatArrayOf(viewScale * 1000f / max(1, width), viewX / max(1, width), viewY / max(1, width))

    fun setMode(m: Mode) {
        if (m == mode) return
        stop()
        mode = m
        if (m == Mode.STRIP) scrollStripTo(current, animate = false)
        introPlaying = ValueAnimator.areAnimatorsEnabled()
        introStart = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    fun select(frame: Frame, scroll: Boolean = true) {
        current = frame
        onCurrent(frame)
        if (mode == Mode.STRIP && scroll) scrollStripTo(frame, animate = true)
        invalidate()
    }

    fun setLamp(on: Boolean, animate: Boolean = true) {
        lampOn = on
        lampAnimator?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { lamp = if (on) 1f else 0f; invalidate(); return }
        // a tube light: a stutter or two before it holds
        lampAnimator = (if (on) ValueAnimator.ofFloat(lamp, 0.75f, 0.12f, 0.9f, 0.45f, 1f).setDuration(520)
        else ValueAnimator.ofFloat(lamp, 0f).setDuration(260)).apply {
            addUpdateListener { lamp = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Back to the default spread, moving each frame there. */
    fun spread(targets: Map<Frame, Triple<Float, Float, Float>>, fit: Boolean = true, ms: Long = 420) {
        val from = frames.associateWith { Triple(it.x, it.y, it.w) }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = if (fit) DecelerateInterpolator(2f) else android.view.animation.PathInterpolator(0.3f, 0f, 0.15f, 1f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                for (f in frames) {
                    val s = from[f] ?: continue
                    val e = targets[f] ?: continue
                    f.x = s.first + (e.first - s.first) * t
                    f.y = s.second + (e.second - s.second) * t
                    f.w = s.third + (e.third - s.third) * t
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onArranged() }
            })
            start()
        }
        if (fit && width > 0) {
            viewScale = width / 1000f; viewX = 0f
            viewY = 16 * d - (targets.values.minOfOrNull { it.second - it.third * 0.75f } ?: 0f) * viewScale
        }
    }

    /**
     * 收回: the table cleared into the roll's box under it, the pieces going down to the bottom
     * middle one after another (the top one first), smaller as they go; then nothing lies on it.
     */
    var stowed = false
        private set
    private var stowPlaying = false
    private var stowStart = 0L
    private fun stowStep() = min(26f, 520f / max(1, frames.size))

    fun stow() {
        if (stowed || frames.isEmpty()) return
        stowed = true
        introPlaying = false
        stowPlaying = ValueAnimator.areAnimatorsEnabled() && mode == Mode.TABLE
        stowStart = android.os.SystemClock.uptimeMillis()
        if (stowPlaying) postDelayed({ stowPlaying = false; invalidate(); onArranged() },
            (stowStep() * frames.size + 400).toLong()) else onArranged()
        invalidate()
    }

    /** 平铺 out of the box: the pieces dealt from below to their places (as a roll comes in). */
    fun unstow(targets: Map<Frame, Triple<Float, Float, Float>>) {
        stowed = false
        stowPlaying = false
        for (f in frames) targets[f]?.let { f.x = it.first; f.y = it.second; f.w = it.third }
        if (width > 0) {
            viewScale = width / 1000f; viewX = 0f
            viewY = 16 * d - (frames.minOfOrNull { it.y - pieceHalfH(it) } ?: 0f) * viewScale
        }
        introPlaying = ValueAnimator.areAnimatorsEnabled()
        introStart = android.os.SystemClock.uptimeMillis()
        onArranged()
        invalidate()
    }

    /** Kept from before (a roll put away stays in its box). */
    fun setStowed(on: Boolean) { stowed = on && frames.isNotEmpty(); stowPlaying = false; invalidate() }

    // ===== auto play (联排) ===================================================================

    fun play() {
        if (mode != Mode.STRIP || frames.isEmpty()) return
        if (stripScroll >= stripMax() - 1) stripScroll = stripMin()
        scroller.forceFinished(true)
        playing = true
        lastPlayFrame = 0L
        onPlaying(true)
        postInvalidateOnAnimation()
    }

    fun stop() {
        if (!playing) return
        playing = false
        onPlaying(false)
    }

    // About six seconds a frame, as on the desktop table, never crawling or racing.
    private fun playSpeed(): Float {
        val pitch = if (stripHeights.isEmpty()) 300 * d else stripHeights.average().toFloat() + stripGap()
        return (pitch / secondsPerFrame).coerceIn(20 * d, 240 * d) // px per second
    }

    // ===== loupe ==============================================================================

    fun toggleLoupe() {
        loupeLocked = !loupeLocked
        if (loupeLocked) {
            stop()
            lensX = width / 2f; lensY = height * 0.42f
            sampleX = lensX; sampleY = lensY
            appear(true)
        } else if (!loupeHeld) appear(false)
        onLoupe(loupeLocked)
    }

    private fun appear(show: Boolean) {
        lensAnimator?.cancel()
        lensAnimator = ValueAnimator.ofFloat(lensAppear, if (show) 1f else 0f).apply {
            duration = if (show) 180 else 140
            addUpdateListener { lensAppear = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun holdLoupe(x: Float, y: Float) {
        // the glass sits above the finger, which then never covers what it shows
        sampleX = x; sampleY = y
        val r = lensRadius
        lensX = x.coerceIn(r * 0.6f, width - r * 0.6f)
        lensY = y - r - 36 * d
        if (lensY < r * 0.7f) lensY = y + r + 36 * d
    }

    // ===== geometry ===========================================================================

    // A cut piece of film round a picture: perforated rails along its long sides.
    private fun railOf(short: Float) = if (borders) short * 0.16f else 0f
    private fun lineOf(short: Float) = if (borders) short * 0.045f else 0f
    private fun pieceHalfH(f: Frame): Float {
        val short = min(f.w, f.h)
        return f.h / 2 + if (f.h >= f.w) lineOf(short) else railOf(short)
    }

    /**
     * A piece's whole size (picture, rails, frame lines) in world units for a picture [w] wide:
     * the spread lays pieces out with the very geometry they are drawn with.
     */
    fun pieceSize(w: Float, aspect: Float): Pair<Float, Float> {
        val h = w / aspect
        val short = min(w, h)
        val rail = railOf(short)
        val line = lineOf(short)
        return if (h >= w) Pair(w + 2 * rail, h + 2 * line) else Pair(w + 2 * line, h + 2 * rail)
    }

    /** How far a frame's piece reaches below its centre, world units. */
    fun pieceBelow(f: Frame) = pieceSize(f.w, f.aspect).second / 2

    private fun pieceRect(f: Frame, out: RectF, image: RectF) {
        val cx = viewX + f.x * viewScale
        val cy = viewY + f.y * viewScale
        val w = f.w * viewScale
        val h = f.h * viewScale
        image.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val short = min(w, h)
        val rail = railOf(short)
        val line = lineOf(short)
        if (h >= w) out.set(image.left - rail, image.top - line, image.right + rail, image.bottom + line)
        else out.set(image.left - line, image.top - rail, image.right + line, image.bottom + rail)
    }

    // across the strip: the screen's width when it runs down, a share of its height when across
    private fun stripWidth() = if (stripHorizontal) min(height - 2 * 14 * d, width * 0.9f) else width - 2 * 14 * d
    private fun stripRail() = if (borders) stripWidth() * 0.1f else 0f
    private fun stripImageWidth() = stripWidth() - 2 * stripRail()
    private fun stripGap() = stripImageWidth() * (if (borders) 0.045f else 0.06f)

    private fun layoutStrip() {
        if (width == 0) return
        val iw = stripImageWidth()
        stripTops = FloatArray(frames.size)
        stripHeights = FloatArray(frames.size)
        var y = 0f
        for ((i, f) in frames.withIndex()) {
            stripTops[i] = y
            stripHeights[i] = if (stripHorizontal) iw * f.aspect else iw / f.aspect
            y += stripHeights[i] + stripGap()
        }
    }

    private fun stripCenter(i: Int) = stripTops[i] + stripHeights[i] / 2
    private fun stripMin() = if (frames.isEmpty()) 0f else stripCenter(0) - alongSize() / 2f
    private fun stripMax() = if (frames.isEmpty()) 0f else stripCenter(frames.lastIndex) - alongSize() / 2f

    private fun scrollStripTo(f: Frame?, animate: Boolean) {
        val i = frames.indexOf(f)
        if (i < 0 || stripTops.size != frames.size || height == 0) return
        val target = stripCenter(i) - alongSize() / 2f
        if (animate) {
            scroller.forceFinished(true)
            scroller.startScroll(0, stripScroll.toInt(), 0, (target - stripScroll).toInt(), 380)
            postInvalidateOnAnimation()
        } else stripScroll = target
    }

    private fun stripFrameAt(along: Float): Int {
        val s = along + stripScroll
        for (i in frames.indices) if (s >= stripTops[i] - stripGap() / 2 && s < stripTops[i] + stripHeights[i] + stripGap() / 2) return i
        return -1
    }

    private fun tableFrameAt(x: Float, y: Float): Frame? {
        if (stowed) return null
        for (f in frames.sortedByDescending { it.z }) {
            pieceRect(f, rect, rect2)
            if (rect.contains(x, y)) return f
        }
        return null
    }

    private fun frameAt(x: Float, y: Float): Frame? =
        if (mode == Mode.TABLE) tableFrameAt(x, y) else stripFrameAt(if (stripHorizontal) x else y).takeIf { it >= 0 }?.let { frames[it] }

    // The frame nearest the middle of the screen, as the strip goes by.
    private fun updateStripCurrent() {
        if (frames.isEmpty() || stripTops.size != frames.size) return
        val mid = stripScroll + alongSize() / 2f
        var best = 0
        for (i in frames.indices) if (abs(stripCenter(i) - mid) < abs(stripCenter(best) - mid)) best = i
        val f = frames[best]
        if (f != current) { current = f; onCurrent(f); onTick() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        makeLightPaints()
        if (viewScale == 0f || oldw != w) fitView()
        layoutStrip()
        // playing: the strip goes on from where it is (the view grows as the bars go)
        if (mode == Mode.STRIP && !playing) scrollStripTo(current, animate = false)
        else if (playing) stripScroll = stripScroll.coerceIn(stripMin(), stripMax())
    }

    // ===== drawing ============================================================================

    private fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xff) + ((((b shr s) and 0xff) - ((a shr s) and 0xff)) * t)).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun makeLightPaints() {
        if (width == 0 || height == 0) return
        glowPaint.shader = RadialGradient(width * 0.5f, height * 0.4f, max(width, height) * 0.8f,
            intArrayOf(0xb0ffffff.toInt(), 0x30ffffff, 0x00ffffff), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        vignettePaint.shader = RadialGradient(width * 0.5f, height * 0.45f, hypot(width.toFloat(), height.toFloat()) * 0.62f,
            intArrayOf(0x00000000, 0x00000000, 0x80000000.toInt()), floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP)
    }

    // The diffuser (or bare table): its colour, the light pooling a little above centre, the
    // falloff into the corners, and the acrylic's faint texture.
    private fun drawTable(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        basePaint.color = lerp(offColor, onBase(), lamp)
        c.drawRect(l, t, r, b, basePaint)
        // the pool of light while the tube is lit; unlit, only the room's faint brightening
        glowPaint.alpha = (14 + 241 * lamp).toInt()
        c.drawRect(l, t, r, b, glowPaint)
        vignettePaint.alpha = 45
        c.drawRect(l, t, r, b, vignettePaint)
        c.drawRect(l, t, r, b, grainPaint)
    }

    /**
     * A transparency shows its picture only with light behind it. Unlit (as on the desktop
     * table) it passes 12% of it: the frames go near black on the pale table, the scene just
     * readable in them.
     */
    private fun photoFilter() {
        val t = TRANSMISSION_OFF + (brightness / 100f - TRANSMISSION_OFF) * lamp
        if (abs(t - 1f) < 0.003f) { photoPaint.colorFilter = null; return }
        photoPaint.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(t, t, t, 1f) })
    }

    private fun drawTransparency(c: Canvas, bmp: Bitmap, dst: RectF) = c.drawBitmap(bmp, null, dst, photoPaint)

    // The emulsion's gloss, as the desktop table draws it: a faint diagonal sheen from the
    // top-left corner and a hairline catching the light at the edge.
    private val glossMatrix = android.graphics.Matrix()
    private fun drawGloss(c: Canvas, image: RectF) {
        glossMatrix.setScale(image.width(), image.height())
        glossMatrix.postTranslate(image.left, image.top)
        glossPaint.shader.setLocalMatrix(glossMatrix)
        c.drawRect(image, glossPaint)
        glossEdge.strokeWidth = max(1f, d * 0.4f)
        c.drawRect(image, glossEdge)
    }

    override fun onDraw(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        if (playing) {
            if (lastPlayFrame != 0L) stripScroll += playSpeed() * (now - lastPlayFrame) / 1000f
            lastPlayFrame = now
            if (stripScroll >= stripMax()) { stripScroll = stripMax(); stop() }
            updateStripCurrent()
        }
        if (scroller.computeScrollOffset()) {
            stripScroll = scroller.currY.toFloat()
            if (mode == Mode.STRIP) updateStripCurrent()
        }
        photoFilter()
        drawTable(canvas, 0f, 0f, width.toFloat(), height.toFloat())
        drawScene(canvas, inLens = false, now = now)
        if (lensAppear > 0.01f) drawLoupe(canvas)
        if (playing || !scroller.isFinished || introPlaying || stowPlaying) postInvalidateOnAnimation()
        onFrame?.invoke()
    }

    /** Called after each frame the table draws (the 经典 backdrop follows it). */
    var onFrame: (() -> Unit)? = null

    private fun ease(t: Float) = 1 - (1 - t) * (1 - t) * (1 - t)

    private fun drawScene(c: Canvas, inLens: Boolean, now: Long) {
        if (frames.isEmpty()) return
        var introDone = true
        if (mode == Mode.TABLE && stowed) {
            if (!stowPlaying) return
            // into the box below: the top piece first, each sinking to the bottom middle and shrinking
            val order = frames.sortedBy { it.z }
            val n = order.size
            for ((i, f) in order.withIndex()) {
                val t = ((now - stowStart - (n - 1 - i) * stowStep()) / 380f).coerceIn(0f, 1f)
                if (t >= 1f) continue
                pieceRect(f, pieceR, imageR)
                val e = t * t * t
                val sx = width / 2f; val sy = height + pieceR.height() * 0.6f
                c.save()
                c.translate((sx - pieceR.centerX()) * e, (sy - pieceR.centerY()) * e)
                val k = 1f - 0.65f * e
                c.scale(k, k, pieceR.centerX(), pieceR.centerY())
                drawPiece(c, f, pieceR, imageR, imageR.height() >= imageR.width(), inLens)
                c.restore()
            }
        } else if (mode == Mode.TABLE) {
            for ((i, f) in frames.sortedBy { it.z }.withIndex()) {
                var t = 1f
                if (introPlaying && !inLens) {
                    t = ((now - introStart - i * 22f) / 420f).coerceIn(0f, 1f)
                    if (t < 1f) introDone = false
                }
                pieceRect(f, pieceR, imageR)
                val vertical = imageR.height() >= imageR.width()
                if (t < 1f) {
                    // dealt from the roll shelf below, one after another
                    val e = ease(t)
                    val sx = width / 2f; val sy = height + pieceR.height()
                    c.save()
                    c.translate((sx - pieceR.centerX()) * (1 - e), (sy - pieceR.centerY()) * (1 - e))
                    c.scale(0.4f + 0.6f * e, 0.4f + 0.6f * e, pieceR.centerX(), pieceR.centerY())
                    drawPiece(c, f, pieceR, imageR, vertical, inLens)
                    c.restore()
                } else if (!c.quickReject(pieceR)) drawPiece(c, f, pieceR, imageR, vertical, inLens)
            }
        } else {
            var lift = 0f
            if (introPlaying && !inLens) {
                val t = ((now - introStart) / 460f).coerceIn(0f, 1f)
                if (t < 1f) introDone = false
                lift = (1 - ease(t)) * alongSize() * 0.7f
            }
            drawStrip(c, lift, inLens)
        }
        if (!inLens && introDone) introPlaying = false
    }

    private fun drawPiece(c: Canvas, f: Frame, piece: RectF, image: RectF, vertical: Boolean, inLens: Boolean) {
        val short = min(image.width(), image.height())
        val rail = railOf(short)
        if (lamp < 0.98f) { // a shadow only once the room light is the light
            shadowPaint.color = 0x55000000
            shadowPaint.alpha = (0x55 * (1 - lamp)).toInt()
            if (short > 8) shadowPaint.maskFilter = BlurMaskFilter(max(1f, short * 0.05f), BlurMaskFilter.Blur.NORMAL)
            shadowR.set(piece); shadowR.offset(0f, short * 0.03f)
            c.drawRect(shadowR, shadowPaint)
        }
        if (borders) {
            filmPaint.color = lerp(OFF_FILM, GalleryActivity.FILM_BASE, lamp)
            c.drawRect(piece, filmPaint)
            drawPerforations(c, piece, image, vertical, rail)
            drawEdgePrint(c, f, piece, image, vertical, rail)
        }
        drawPhoto(c, f, image, inLens)
        if (f == current && mode == Mode.TABLE && !inLens) {
            currentPaint.strokeWidth = max(1.5f * d, short * 0.012f)
            val o = currentPaint.strokeWidth
            shadowR.set(piece.left - o, piece.top - o, piece.right + o, piece.bottom + o)
            c.drawRect(shadowR, currentPaint)
        }
        drawMarks(c, f, image)
    }

    // Perforations along the rails, showing the light through them.
    private fun drawPerforations(c: Canvas, piece: RectF, image: RectF, vertical: Boolean, rail: Float) {
        holePaint.color = lerp(offColor, ON_HOLE, lamp) // the table seen through the perforations
        val length = if (vertical) piece.height() else piece.width()
        val pitch = rail * 0.86f
        val along = pitch * 0.55f
        val across = rail * 0.36f
        val n = (length / pitch).toInt().coerceAtLeast(1)
        val start = (length - n * pitch) / 2 + (pitch - along) / 2
        val corner = across * 0.22f
        for (side in 0..1) for (k in 0 until n) {
            val a = start + k * pitch
            if (vertical) {
                val x = if (side == 0) piece.left + rail * 0.38f else piece.right - rail * 0.38f - across
                holeR.set(x, piece.top + a, x + across, piece.top + a + along)
            } else {
                val y = if (side == 0) piece.top + rail * 0.38f else piece.bottom - rail * 0.38f - across
                holeR.set(piece.left + a, y, piece.left + a + along, y + across)
            }
            c.drawRoundRect(holeR, corner, corner, holePaint)
        }
    }

    // The latent edge print, as on a developed roll: frame number with its arrow, the film.
    private fun drawEdgePrint(c: Canvas, f: Frame, piece: RectF, image: RectF, vertical: Boolean, rail: Float) {
        val size = rail * 0.24f
        if (size < 3.2f * d * 0.6f) return
        inkPaint.textSize = size
        inkInk()
        val film = filmName(f).uppercase()
        val number = "${f.number}"
        c.save()
        if (vertical) {
            // outside the holes on the right rail, reading downwards
            c.translate(piece.right - rail * 0.33f, piece.top) // glyphs stand outward from here
            c.rotate(90f)
            inkPaint.textAlign = Paint.Align.LEFT
            c.drawText("$number  ▸ ${number}A", piece.height() * 0.06f, 0f, inkPaint)
            inkPaint.textAlign = Paint.Align.RIGHT
            c.drawText(film, piece.height() * 0.94f, 0f, inkPaint)
        } else {
            c.translate(piece.left, piece.bottom - rail * 0.1f)
            inkPaint.textAlign = Paint.Align.LEFT
            c.drawText("$number  ▸ ${number}A", piece.width() * 0.05f, 0f, inkPaint)
            inkPaint.textAlign = Paint.Align.RIGHT
            c.drawText(film, piece.width() * 0.95f, 0f, inkPaint)
        }
        c.restore()
    }

    // The edge print is a latent image in the dyes: it glows on a lit panel, all but vanishes unlit.
    private fun inkInk() {
        inkPaint.color = GalleryActivity.FILM_INK
        inkPaint.alpha = (46 + 194 * lamp).toInt() // 0.18 unlit, as the desktop's rails
    }

    private fun drawPhoto(c: Canvas, f: Frame, image: RectF, inLens: Boolean) {
        val bmp = f.bitmap
        if (bmp == null) c.drawRect(image, emptyPaint) else drawTransparency(c, bmp, image)
        if (inLens) hiRes?.let { hr ->
            val got = hr.ready(f) ?: return@let
            holeR.set(image.left + got.u0 * image.width(), image.top + got.v0 * image.height(),
                image.left + got.u1 * image.width(), image.top + got.v1 * image.height())
            c.save()
            c.clipRect(image)
            drawTransparency(c, got.bitmap, holeR)
            c.restore()
        }
        drawGloss(c, image)
        if (rejected(f)) c.drawRect(image, dimPaint)
    }

    // Grease pencil: a loose circle round a keeper, a cross on a reject; never quite the same twice.
    private fun drawMarks(c: Canvas, f: Frame, image: RectF) {
        val fav = f.photo.favorite
        val rej = rejected(f)
        if (!fav && !rej) return
        val short = min(image.width(), image.height())
        val rnd = java.util.Random(f.photo.name.hashCode().toLong())
        pencil.color = pencilColor
        pencil.strokeWidth = max(1.6f * d, short * 0.03f)
        if (fav) {
            path.reset()
            val cx = image.centerX() + (rnd.nextFloat() - 0.5f) * short * 0.04f
            val cy = image.centerY() + (rnd.nextFloat() - 0.5f) * short * 0.04f
            val rx = image.width() * 0.6f
            val ry = image.height() * 0.6f
            val a0 = rnd.nextFloat() * 6.28f
            val wobble = rnd.nextFloat() * 6.28f
            val steps = 48
            for (i in 0..steps + 5) { // a little past the start, as a hand overshoots
                val a = a0 + i * 6.2832f / steps
                val k = 1f + 0.035f * sin(a * 2.3f + wobble) + 0.02f * i / steps
                val x = cx + rx * k * cos(a)
                val y = cy + ry * k * sin(a)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            pencil.alpha = 235
            c.drawPath(path, pencil)
            pencil.alpha = 90 // the wax's second pass
            pencil.strokeWidth *= 0.55f
            c.save(); c.translate(pencil.strokeWidth * 0.6f, pencil.strokeWidth * 0.4f); c.drawPath(path, pencil); c.restore()
        }
        if (rej) {
            pencil.alpha = 235
            pencil.strokeWidth = max(1.6f * d, short * 0.035f)
            val m = short * 0.22f
            for (k in 0..1) {
                path.reset()
                val x0 = if (k == 0) image.left + m else image.right - m
                val x1 = if (k == 0) image.right - m else image.left + m
                val y0 = image.top + m + (rnd.nextFloat() - 0.5f) * m * 0.3f
                val y1 = image.bottom - m + (rnd.nextFloat() - 0.5f) * m * 0.3f
                path.moveTo(x0, y0)
                path.quadTo((x0 + x1) / 2 + (rnd.nextFloat() - 0.5f) * m * 0.4f, (y0 + y1) / 2, x1, y1)
                c.drawPath(path, pencil)
            }
        }
    }

    /** 联排 across the screen: the rails above and below, the edge print along the lower one. */
    private fun drawStripAcross(c: Canvas, lift: Float, inLens: Boolean) {
        if (stripTops.size != frames.size) layoutStrip()
        if (frames.isEmpty()) return
        val sw = stripWidth()
        val rail = stripRail()
        val top = (height - sw) / 2
        val gap = stripGap()
        val start = -stripScroll + lift - gap
        val end = -stripScroll + lift + stripTops.last() + stripHeights.last() + gap
        rect.set(max(start, -width.toFloat()), top, min(end, width * 2f), top + sw)
        if (borders) {
            filmPaint.color = lerp(OFF_FILM, GalleryActivity.FILM_BASE, lamp)
            c.drawRect(rect, filmPaint)
            holePaint.color = lerp(offColor, ON_HOLE, lamp)
            val pitch = rail * 0.86f
            val along = pitch * 0.55f
            val across = rail * 0.36f
            val corner = across * 0.22f
            var k = max(0, ((rect.left - start) / pitch).toInt() - 1)
            while (true) {
                val x = start + k * pitch + (pitch - along) / 2
                if (x > rect.right || x + along > end) break
                for (y in floatArrayOf(top + rail * 0.38f, top + sw - rail * 0.38f - across)) {
                    rect2.set(x, y, x + along, y + across)
                    c.drawRoundRect(rect2, corner, corner, holePaint)
                }
                k++
            }
        }
        val ih = stripImageWidth()
        inkPaint.textSize = rail * 0.24f
        inkInk()
        for ((i, f) in frames.withIndex()) {
            val x = start + gap + stripTops[i]
            rect2.set(x, top + rail, x + stripHeights[i], top + rail + ih)
            if (rect2.right < -width || rect2.left > width * 2) continue
            if (!c.quickReject(rect2.left, rect2.top - rail, rect2.right, rect2.bottom + rail)) {
                drawPhoto(c, f, rect2, inLens)
                if (borders) {
                    val base = top + sw - rail * 0.33f + inkPaint.textSize * 0.35f
                    inkPaint.textAlign = Paint.Align.LEFT
                    c.drawText("${f.number}  ▸ ${f.number}A", x + stripHeights[i] * 0.08f, base, inkPaint)
                    inkPaint.textAlign = Paint.Align.RIGHT
                    c.drawText(filmName(f).uppercase(), x + stripHeights[i] * 0.92f, base, inkPaint)
                }
                drawMarks(c, f, rect2)
            }
        }
    }

    private fun drawStrip(c: Canvas, lift: Float, inLens: Boolean) {
        if (stripHorizontal) { drawStripAcross(c, lift, inLens); return }
        if (stripTops.size != frames.size) layoutStrip()
        val sw = stripWidth()
        val rail = stripRail()
        val left = (width - sw) / 2
        val gap = stripGap()
        val top = -stripScroll + lift - gap
        val bottom = -stripScroll + lift + stripTops.last() + stripHeights.last() + gap
        rect.set(left, max(top, -height.toFloat()), left + sw, min(bottom, height * 2f))
        if (borders) {
        filmPaint.color = lerp(OFF_FILM, GalleryActivity.FILM_BASE, lamp)
        c.drawRect(rect, filmPaint)
        // perforations at a fixed pitch down the whole roll
        holePaint.color = lerp(offColor, ON_HOLE, lamp) // the table seen through the perforations
        val pitch = rail * 0.86f
        val along = pitch * 0.55f
        val across = rail * 0.36f
        val corner = across * 0.22f
        val firstK = max(0, ((rect.top - top) / pitch).toInt() - 1)
        var k = firstK
        while (true) {
            val y = top + k * pitch + (pitch - along) / 2
            if (y > rect.bottom || y + along > bottom) break
            for (x in floatArrayOf(left + rail * 0.38f, left + sw - rail * 0.38f - across)) {
                rect2.set(x, y, x + across, y + along)
                c.drawRoundRect(rect2, corner, corner, holePaint)
            }
            k++
        }
        }
        val iw = stripImageWidth()
        inkPaint.textSize = rail * 0.24f
        inkInk()
        for ((i, f) in frames.withIndex()) {
            val y = top + gap + stripTops[i]
            rect2.set(left + rail, y, left + rail + iw, y + stripHeights[i])
            if (rect2.bottom < -height || rect2.top > height * 2) continue
            if (!c.quickReject(rect2.left - rail, rect2.top, rect2.right + rail, rect2.bottom)) {
                drawPhoto(c, f, rect2, inLens)
                // edge print on the rail beside each frame
                if (borders) {
                c.save()
                c.translate(left + sw - rail * 0.33f, rect2.top)
                c.rotate(90f)
                inkPaint.textAlign = Paint.Align.LEFT
                c.drawText("${f.number}  ▸ ${f.number}A", stripHeights[i] * 0.08f, 0f, inkPaint)
                inkPaint.textAlign = Paint.Align.RIGHT
                c.drawText(filmName(f).uppercase(), stripHeights[i] * 0.92f, 0f, inkPaint)
                c.restore()
                }
                drawMarks(c, f, rect2)
            }
        }
    }

    // ----- the loupe ------------------------------------------------------------------------

    private val lensShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val reflectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private var labelUntil = 0L

    private fun drawLoupe(c: Canvas) {
        val r = lensRadius * (0.85f + 0.15f * lensAppear)
        val a = lensAppear
        // the shadow it casts: soft, and lighter with the lamp on (light comes from beneath)
        lensShadow.color = Color.BLACK
        lensShadow.alpha = ((0x40 - 0x18 * lamp) * a).toInt()
        lensShadow.maskFilter = BlurMaskFilter(r * 0.18f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(lensX, lensY + r * 0.1f, r * 1.02f, lensShadow)

        hiRes?.want(this, frameAt(sampleX, sampleY), sampleX, sampleY, r * (1 + refraction) / magnification, magnification)
        lens.draw(c, lensX, lensY, r, a) { lc ->
            lc.translate(r, r)
            lc.scale(magnification, magnification)
            lc.translate(-sampleX, -sampleY)
            drawTable(lc, sampleX - r, sampleY - r, sampleX + r, sampleY + r)
            drawScene(lc, inLens = true, now = 0L)
        }
        // the glass: a bright bevel just inside the edge, a darker edge, a sky reflection
        rimPaint.shader = null
        rimPaint.strokeWidth = r * 0.05f
        rimPaint.color = Color.argb((110 * a).toInt(), 255, 255, 250)
        c.drawCircle(lensX, lensY, r - rimPaint.strokeWidth * 0.9f, rimPaint)
        rimPaint.strokeWidth = r * 0.022f
        rimPaint.color = Color.argb((120 * a).toInt(), 40, 46, 42)
        c.drawCircle(lensX, lensY, r, rimPaint)
        reflectPaint.shader = LinearGradient(lensX - r * 0.7f, lensY - r * 0.75f, lensX + r * 0.7f, lensY + r * 0.75f,
            intArrayOf(0x55ffffff, 0x14ffffff, 0x00ffffff, 0x00ffffff, 0x22dfeef3),
            floatArrayOf(0f, 0.1f, 0.3f, 0.75f, 1f), Shader.TileMode.CLAMP)
        reflectPaint.alpha = (255 * a).toInt()
        c.drawCircle(lensX, lensY, r * 0.98f, reflectPaint)
        if (android.os.SystemClock.uptimeMillis() < labelUntil) {
            labelPaint.textSize = 11 * d
            labelPaint.color = 0xccf2efe4.toInt()
            rect.set(lensX - 22 * d, lensY + r * 0.62f, lensX + 22 * d, lensY + r * 0.62f + 18 * d)
            lensShadow.maskFilter = null; lensShadow.alpha = 140
            c.drawRoundRect(rect, 9 * d, 9 * d, lensShadow)
            c.drawText("%.1f×".format(magnification).replace(".0×", "×"), lensX, rect.bottom - 5 * d, labelPaint)
            postInvalidateDelayed(120)
        }
    }

    private interface LensRenderer {
        fun draw(c: Canvas, x: Float, y: Float, r: Float, alpha: Float, content: (Canvas) -> Unit)
    }

    /** Android 13 and up: the magnified table bent by the glass, the colours parting at its edge. */
    @android.annotation.TargetApi(33)
    private inner class RefractingLens : LensRenderer {
        private val node = android.graphics.RenderNode("loupe")
        private val shader = android.graphics.RuntimeShader(LENS_SHADER)
        override fun draw(c: Canvas, x: Float, y: Float, r: Float, alpha: Float, content: (Canvas) -> Unit) {
            if (!c.isHardwareAccelerated) { FlatLens().draw(c, x, y, r, alpha, content); return }
            // The glass reaches past its own rim for what it bends in (up to 1 + strength x 1.1 of
            // the radius): the table is recorded with that much room round it, or the bent edge
            // would sample nothing (flat sides on the lens, a coloured fringe where r and b part).
            val pad = r * (refraction * 1.2f + 0.04f)
            val size = (2 * (r + pad)).toInt().coerceAtLeast(2)
            node.setPosition(0, 0, size, size)
            shader.setFloatUniform("center", r + pad, r + pad)
            shader.setFloatUniform("radius", r)
            shader.setFloatUniform("strength", refraction)
            node.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
            node.alpha = alpha
            val rc = node.beginRecording()
            rc.translate(pad, pad)
            content(rc)
            node.endRecording()
            c.save()
            c.translate(x - r - pad, y - r - pad)
            c.drawRenderNode(node)
            c.restore()
        }
    }

    /** Older systems: the same magnified table, round, without the bending. */
    private inner class FlatLens : LensRenderer {
        private val clip = Path()
        override fun draw(c: Canvas, x: Float, y: Float, r: Float, alpha: Float, content: (Canvas) -> Unit) {
            clip.reset(); clip.addCircle(x, y, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            c.translate(x - r, y - r)
            content(c)
            c.restore()
        }
    }

    // ===== touch ==============================================================================

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var touched: Frame? = null
    private var dragFrame = false
    private var pinchFrame: Frame? = null
    private var pinching = false
    private var pinchSpan = 0f
    private var pinchCx = 0f
    private var pinchCy = 0f
    private var lastTap = 0L
    private var lastTapFrame: Frame? = null
    private var velocity: android.view.VelocityTracker? = null
    private val longPress = Runnable {
        if (moved || pinching) return@Runnable
        loupeHeld = true
        stop()
        holdLoupe(lastX, lastY)
        appear(true)
        onTick()
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun span(e: MotionEvent) = if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = android.view.VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stop()
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; dragFrame = false; pinching = false; pinchFrame = null
                touched = if (loupeLocked) null else frameAt(e.x, e.y)
                if (!loupeLocked) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (loupeHeld) return true
                pinching = true
                pinchSpan = span(e)
                pinchCx = (e.getX(0) + e.getX(1)) / 2; pinchCy = (e.getY(0) + e.getY(1)) / 2
                // a pinch begun with a finger on a frame sizes that frame; on the bare table, the view
                pinchFrame = if (mode == Mode.TABLE && !loupeLocked) touched else null
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop) { moved = true; removeCallbacks(longPress); removeCallbacks(openLater) }
                when {
                    loupeHeld -> { holdLoupe(e.x, e.y); invalidate() }
                    loupeLocked && pinching && e.pointerCount >= 2 -> {
                        val s = span(e)
                        if (pinchSpan > 0 && s > 0) magnification = (magnification * s / pinchSpan).coerceIn(1.5f, 8f)
                        pinchSpan = s
                        labelUntil = android.os.SystemClock.uptimeMillis() + 900
                        invalidate()
                    }
                    loupeLocked -> if (moved && e.pointerCount == 1) {
                        lensX = (lensX + dx).coerceIn(0f, width.toFloat())
                        lensY = (lensY + dy).coerceIn(0f, height.toFloat())
                        sampleX = lensX; sampleY = lensY
                        invalidate()
                    }
                    pinching && e.pointerCount >= 2 -> pinch(e)
                    mode == Mode.TABLE && moved && e.pointerCount == 1 && !pinching -> {
                        val f = touched
                        if (f != null) {
                            if (!dragFrame) { dragFrame = true; f.z = (frames.maxOfOrNull { it.z } ?: 0) + 1; select(f) }
                            f.x += dx / viewScale; f.y += dy / viewScale
                        } else { viewX += dx; viewY += dy; clampView() }
                        invalidate()
                    }
                    mode == Mode.STRIP && moved && e.pointerCount == 1 && !pinching -> {
                        stripScroll = (stripScroll - if (stripHorizontal) dx else dy).coerceIn(stripMin(), stripMax())
                        updateStripCurrent()
                        invalidate()
                    }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // the finger that stays carries on from where it is, without a jump
                val stay = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(stay); lastY = e.getY(stay)
                // the pinch is over; the finger left does nothing more until it lifts
                if (e.pointerCount <= 2) { if (mode == Mode.TABLE) onArranged(); pinchFrame = null }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                if (loupeHeld) {
                    loupeHeld = false
                    if (!loupeLocked) appear(false)
                } else if (!moved && !cancelled && !pinching && !loupeLocked) tap(e.x, e.y)
                else if (!moved && !cancelled && !pinching) loupeTap(e.x, e.y)
                else if (mode == Mode.STRIP && moved && !loupeLocked && !cancelled) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = (if (stripHorizontal) velocity?.xVelocity else velocity?.yVelocity) ?: 0f
                    if (abs(vy) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                        scroller.fling(0, stripScroll.toInt(), 0, -vy.toInt(), 0, 0, stripMin().toInt(), stripMax().toInt())
                        postInvalidateOnAnimation()
                    }
                }
                if (dragFrame || (mode == Mode.TABLE && moved)) onArranged()
                dragFrame = false; pinching = false; pinchFrame = null
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    private fun pinch(e: MotionEvent) {
        val s = span(e)
        if (pinchSpan <= 0f || s <= 0f) { pinchSpan = s; return }
        val k = s / pinchSpan
        pinchSpan = s
        val cx = (e.getX(0) + e.getX(1)) / 2
        val cy = (e.getY(0) + e.getY(1)) / 2
        val f = pinchFrame
        if (mode == Mode.TABLE && f != null) {
            f.w = (f.w * k).coerceIn(90f, 1600f)
        } else if (mode == Mode.TABLE) {
            val base = width / 1000f
            val ns = (viewScale * k).coerceIn(base * 0.25f, base * 4f)
            val real = ns / viewScale
            // zoom about the fingers, and follow them as they move
            viewX = cx - (pinchCx - viewX) * real
            viewY = cy - (pinchCy - viewY) * real
            viewScale = ns
            clampView()
        }
        pinchCx = cx; pinchCy = cy
        invalidate()
    }

    // The frames never all leave the screen.
    private fun clampView() {
        if (frames.isEmpty()) return
        val l = frames.minOf { it.x - it.w / 2 } * viewScale
        val r = frames.maxOf { it.x + it.w / 2 } * viewScale
        val t = frames.minOf { it.y - it.h / 2 } * viewScale
        val b = frames.maxOf { it.y + it.h / 2 } * viewScale
        val m = 60 * d
        viewX = viewX.coerceIn(min(m - r, width - m - l), max(m - r, width - m - l))
        viewY = viewY.coerceIn(min(m - b, height - m - t), max(m - b, height - m - t))
    }

    // A tap chooses a frame; a tap on the chosen one opens it (once it is clear no second tap
    // follows); two quick taps keep it.
    private var toOpen: Frame? = null
    private val openLater = Runnable { toOpen?.let(onOpen); toOpen = null }

    /** With the loupe out the table is all lens: two quick taps anywhere count as the empty table's. */
    private fun loupeTap(x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBlankTap < ViewConfiguration.getDoubleTapTimeout() &&
            kotlin.math.hypot((x - lastBlankX).toDouble(), (y - lastBlankY).toDouble()) < 60 * d) {
            lastBlankTap = 0L
            onDoubleTapBlank()
        } else { lastBlankTap = now; lastBlankX = x; lastBlankY = y }
    }

    private fun tap(x: Float, y: Float) {
        val f = frameAt(x, y)
        if (f == null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastBlankTap < ViewConfiguration.getDoubleTapTimeout() &&
                kotlin.math.hypot((x - lastBlankX).toDouble(), (y - lastBlankY).toDouble()) < 60 * d) {
                lastBlankTap = 0L
                onDoubleTapBlank()
            } else { lastBlankTap = now; lastBlankX = x; lastBlankY = y }
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        removeCallbacks(openLater)
        if (f == lastTapFrame && now - lastTap < ViewConfiguration.getDoubleTapTimeout()) {
            lastTap = 0L
            toOpen = null
            onDoubleTap(f)
        } else {
            val chosen = f == current
            lastTap = now
            lastTapFrame = f
            if (mode == Mode.TABLE) f.z = (frames.maxOfOrNull { it.z } ?: 0) + 1
            if (chosen) { toOpen = f; postDelayed(openLater, ViewConfiguration.getDoubleTapTimeout().toLong()) }
            else select(f)
            invalidate()
        }
    }

    // ===== real pixels under the loupe =======================================================

    /** A part of one photo decoded at (near) full size: where it sits in the photo, 0..1. */
    class Region(val uri: android.net.Uri, val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                 val sample: Int, val bitmap: Bitmap)

    /**
     * Decodes the part of the photo under the loupe from the file itself, on its own thread;
     * the newest request wins, and a region already covering what is asked is kept.
     */
    class HiRes(private val context: Context) {
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        @Volatile private var region: Region? = null
        @Volatile private var wanted: Request? = null
        private var busy = false
        private var decoderUri: android.net.Uri? = null
        private var decoder: android.graphics.BitmapRegionDecoder? = null

        private class Request(val frame: Frame, val u0: Float, val v0: Float, val u1: Float, val v1: Float, val sample: Int, val view: View)

        fun ready(f: Frame): Region? = region?.takeIf { it.uri == f.photo.uri }

        /** Called as the loupe draws: the screen point it shows, how far round it reaches, its power. */
        fun want(view: LightTable, f: Frame?, sx: Float, sy: Float, reach: Float, mag: Float) {
            if (f == null) return
            val image = view.imageRectOf(f) ?: return
            val (fullW, fullH) = view.fullSize(f) ?: return
            val shownPerPixel = mag * image.width() / fullW  // screen px per photo px in the glass
            var sample = 1
            while (sample * 2 * shownPerPixel <= 1f && sample < 16) sample *= 2
            val nu0 = (sx - reach - image.left) / image.width()
            val nu1 = (sx + reach - image.left) / image.width()
            val nv0 = (sy - reach - image.top) / image.height()
            val nv1 = (sy + reach - image.top) / image.height()
            val have = region
            if (have != null && have.uri == f.photo.uri && have.sample == sample &&
                have.u0 <= max(0f, nu0) && have.u1 >= min(1f, nu1) && have.v0 <= max(0f, nv0) && have.v1 >= min(1f, nv1)) return
            // ask for twice the reach, so small moves need nothing new
            val pu = (nu1 - nu0) / 2; val pv = (nv1 - nv0) / 2
            val req = Request(f, max(0f, nu0 - pu), max(0f, nv0 - pv), min(1f, nu1 + pu), min(1f, nv1 + pv), sample, view)
            if (req.u1 <= req.u0 || req.v1 <= req.v0) return
            if (fullH <= 0) return
            synchronized(this) {
                wanted = req
                if (busy) return
                busy = true
            }
            if (!worker.isShutdown) worker.later { run() }
        }

        private fun run() {
            while (true) {
                val req = synchronized(this) { wanted.also { wanted = null } ?: run { busy = false; null } } ?: return
                val got = runCatching { decode(req) }.getOrNull() ?: continue
                region = got
                req.view.postInvalidateOnAnimation()
            }
        }

        private fun decode(req: Request): Region? {
            val uri = req.frame.photo.uri
            if (decoderUri != uri) {
                decoder?.recycle()
                decoder = context.contentResolver.openInputStream(uri)?.use {
                    if (android.os.Build.VERSION.SDK_INT >= 31) android.graphics.BitmapRegionDecoder.newInstance(it)
                    else @Suppress("DEPRECATION") android.graphics.BitmapRegionDecoder.newInstance(it, false)
                }
                decoderUri = uri
            }
            val dec = decoder ?: return null
            val rw = dec.width; val rh = dec.height
            // the asked part is in the photo as shown; the file may be stored turned
            val (a0, b0, a1, b1) = when (req.frame.orientation) {
                6 -> listOf(req.v0, 1 - req.u1, req.v1, 1 - req.u0)
                3 -> listOf(1 - req.u1, 1 - req.v1, 1 - req.u0, 1 - req.v0)
                8 -> listOf(1 - req.v1, req.u0, 1 - req.v0, req.u1)
                else -> listOf(req.u0, req.v0, req.u1, req.v1)
            }
            val area = android.graphics.Rect((a0 * rw).toInt(), (b0 * rh).toInt(), (a1 * rw).toInt().coerceAtMost(rw), (b1 * rh).toInt().coerceAtMost(rh))
            if (area.width() < 2 || area.height() < 2) return null
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = req.sample }
            var bmp = dec.decodeRegion(area, opts) ?: return null
            val turn = when (req.frame.orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            if (turn != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(turn) }, true)
            // back to the shown photo's coordinates, from the whole pixels decoded
            val u0 = area.left.toFloat() / rw; val u1 = area.right.toFloat() / rw
            val v0 = area.top.toFloat() / rh; val v1 = area.bottom.toFloat() / rh
            val shown = when (req.frame.orientation) {
                6 -> floatArrayOf(1 - v1, u0, 1 - v0, u1)
                3 -> floatArrayOf(1 - u1, 1 - v1, 1 - u0, 1 - v0)
                8 -> floatArrayOf(v0, 1 - u1, v1, 1 - u0)
                else -> floatArrayOf(u0, v0, u1, v1)
            }
            return Region(uri, shown[0], shown[1], shown[2], shown[3], req.sample, bmp)
        }

        fun close() {
            worker.later { decoder?.recycle(); decoder = null }
            worker.shutdown()
        }
    }

    // where a frame's picture is on screen now
    internal fun imageRectOf(f: Frame): RectF? {
        if (mode == Mode.TABLE) { pieceRect(f, RectF(), rect2); return RectF(rect2) }
        val i = frames.indexOf(f)
        if (i < 0 || stripTops.size != frames.size) return null
        if (stripHorizontal) {
            val top = (height - stripWidth()) / 2 + stripRail()
            val x = -stripScroll + stripTops[i]
            return RectF(x, top, x + stripHeights[i], top + stripImageWidth())
        }
        val left = (width - stripWidth()) / 2 + stripRail()
        val y = -stripScroll + stripTops[i]
        return RectF(left, y, left + stripImageWidth(), y + stripHeights[i])
    }

    /** The photo's size as shown (after its orientation), from the system's record or its file. */
    var fullSize: (Frame) -> Pair<Int, Int>? = { null }

    companion object {
        private const val ON_BASE = 0xffe9ece9.toInt()
        // unlit, as the desktop table: its pale 米黄 surface, the film's near-black base
        private const val OFF_BASE = 0xffdfd5b5.toInt()
        private const val OFF_FILM = 0xff090c08.toInt()
        private const val TRANSMISSION_OFF = 0.12f
        private const val ON_HOLE = 0xfffbfcf8.toInt()
        private const val PENCIL = 0xffc4282c.toInt() // a red china marker

        private val LENS_SHADER = """
            uniform shader content;
            uniform float2 center;
            uniform float radius;
            uniform float strength;
            half4 main(float2 p) {
                float2 d = (p - center) / radius;
                float r2 = dot(d, d);
                if (r2 > 1.0) return half4(0.0);
                // toward the rim the glass bends in more of the table, and parts the colours a little
                float k = strength * r2 * r2;
                half4 g = content.eval(center + d * radius * (1.0 + k));
                half r = content.eval(center + d * radius * (1.0 + k * 1.10)).r;
                half b = content.eval(center + d * radius * (1.0 + k * 0.90)).b;
                // a soft falloff right at the edge, where the glass is thickest
                half edge = half(1.0 - smoothstep(0.94, 1.0, sqrt(r2)));
                return half4(r, g.g, b, 1.0) * edge;
            }
        """.trimIndent()

        // Fine, quiet noise for the diffuser.
        private fun grain(): Bitmap {
            val n = 96
            val rnd = java.util.Random(7)
            val px = IntArray(n * n) {
                val v = rnd.nextInt(256)
                if (v > 128) Color.argb((v - 128) / 3, 255, 255, 255) else Color.argb((128 - v) / 3, 60, 58, 50)
            }
            return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }
    }
}
