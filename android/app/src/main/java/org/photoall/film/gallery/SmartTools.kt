package org.photoall.film.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.photoall.film.retouch.Detail
import org.photoall.film.retouch.Eraser
import org.photoall.film.retouch.Retouch
import org.photoall.film.ui.Haptics
import org.photoall.film.ui.Style
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The editor's 智能 tool: AI on the photo before the hand edits go over it.
 *
 *  - 一键: a sensible mix at once (colour by the adaptive LUT model, a little denoise, light
 *    when the picture is dark).
 *  - 调色 / 光线 / 降噪 / 仿色: [Retouch] (the ZRetouch engine): five colour models, three light
 *    models, FFDNet, and a reference photo's colours (classic, FastPhotoStyle, WCT2), each at
 *    its own strength; a classic 仿色 can be kept as a 3D LUT for the film camera.
 *  - 清晰: [Detail] (Real-ESRGAN). 消除: [Eraser] (MI-GAN), painted on the picture.
 *
 * Everything is worked out on the preview while you choose, and again at full size when the
 * photo is saved ([full]); the order is always 消除 → 清晰 → the rest.
 */
class SmartTools(
    private val a: Activity,
    private val haptics: Haptics,
    /** A new picture under the hand edits (the editor grades it again). Main thread. */
    private val onBase: (Bitmap) -> Unit,
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val ticket = AtomicInteger()
    private var retouch: Retouch? = null
    private var eraser: Eraser? = null
    private var detailModel: Detail? = null

    private val plan = Retouch.Plan()
    private var detail = 0f
    private val erases = mutableListOf<Bitmap>() // masks the size of [origin]

    /** The photo as decoded for the screen, before any of this. */
    var origin: Bitmap? = null
        set(v) { field = v; erasedCache = null }
    private var erasedCache: Pair<Int, Bitmap>? = null
    private var detailCache: Triple<Int, Float, Bitmap>? = null
    private var current: Bitmap? = null

    val edited get() = plan.any() || detail > 0f || erases.isNotEmpty()

    /** What this tool has set, for the editor's 撤销 (erases only ever undone from the end). */
    data class State(val color: Float, val light: Float, val noise: Float, val match: Float,
                     val colorModel: Retouch.Color, val lightModel: Retouch.Light, val matchModel: Retouch.Match,
                     val detail: Float, val erases: Int)

    fun state() = State(plan.color, plan.light, plan.noise, plan.match, plan.colorModel, plan.lightModel, plan.matchModel, detail, erases.size)

    fun restore(s: State) {
        if (s == state()) return
        plan.color = s.color; plan.light = s.light; plan.noise = s.noise; plan.match = s.match
        plan.colorModel = s.colorModel; plan.lightModel = s.lightModel; plan.matchModel = s.matchModel
        detail = s.detail
        while (erases.size > s.erases) { erases.removeAt(erases.lastIndex); erasedCache = null }
        if (!edited) { paint(); origin?.let { onBase(it) }; return }
        changed()
    }

    private enum class Section(val label: String) { AUTO("一键"), COLOR("调色"), LIGHT("光线"), NOISE("降噪"), MATCH("仿色"), DETAIL("清晰"), ERASE("消除") }
    private var section = Section.COLOR

    private fun dp(v: Int) = Style.dp(a, v.toFloat())

    // ---- the panel ------------------------------------------------------------------------------

    private val chips = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
    private val status = TextView(a).apply { textSize = 11f; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER; visibility = View.GONE }
    private val slider = TickSlider(a).apply {
        min = 0f; max = 100f
        format = { v -> "${v.toInt()}" }
        onChange = { v -> setStrength(v / 100f) }
        onTick = { stop -> haptics.play(if (stop) Haptics.Event.SEGMENT else Haptics.Event.TICK) }
    }
    private val sections = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }

    val panel: View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(20))
                addView(chips)
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6); marginEnd = dp(14) })
        }, LinearLayout.LayoutParams(-1, dp(42)))
        addView(slider, LinearLayout.LayoutParams(-1, dp(62)))
        addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(sections)
        }, LinearLayout.LayoutParams(-1, dp(44)))
    }

    init { paint() }

    private fun pill(label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) = TextView(a).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = Style.medium
        Style.compact(this, 13f)
        setTextColor(if (!enabled) Style.TEXT_3 else if (on) Style.ON_ACCENT else Style.TEXT)
        background = Style.surface(a, fill = if (on) Style.ACCENT else Style.SURFACE, radiusDp = 16f)
        Style.pressable(this)
        setOnClickListener { if (enabled) onClick() else Toast.makeText(a, "这个模型没有装进这个版本", Toast.LENGTH_SHORT).show() }
    }

    private fun paint() {
        sections.removeAllViews()
        for (s in Section.entries) sections.addView(TextView(a).apply {
            text = s.label; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(if (s == section) Style.TEXT else Style.TEXT_2)
            typeface = if (s == section) Style.medium else Style.regular
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { if (s == Section.AUTO) auto() else { section = s; haptics.play(Haptics.Event.SEGMENT); paint() } }
        }, LinearLayout.LayoutParams(-2, -1))
        chips.removeAllViews()
        fun add(v: View) = chips.addView(v, LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(6) })
        slider.visibility = View.VISIBLE
        when (section) {
            Section.AUTO, Section.COLOR -> for (m in Retouch.Color.entries) add(pill(m.label, plan.color > 0 && plan.colorModel == m) {
                if (plan.color > 0 && plan.colorModel == m) plan.color = 0f
                else { plan.colorModel = m; if (plan.color == 0f) plan.color = 0.8f }
                changed()
            })
            Section.LIGHT -> for (m in Retouch.Light.entries) add(pill(m.label, plan.light > 0 && plan.lightModel == m) {
                if (plan.light > 0 && plan.lightModel == m) plan.light = 0f
                else { plan.lightModel = m; if (plan.light == 0f) plan.light = 0.7f }
                changed()
            })
            Section.NOISE -> add(pill("FFDNet", plan.noise > 0) { plan.noise = if (plan.noise > 0) 0f else 0.6f; changed() })
            Section.MATCH -> {
                add(pill(if (retouch?.hasReference() == true) "换参考图" else "选参考图", false) {
                    a.startActivityForResult(org.photoall.film.collage.PhotoPickerActivity.intent(a, "选一张参考图"), REQUEST_REFERENCE)
                })
                for (m in Retouch.Match.entries) add(pill(m.label, plan.match > 0 && plan.matchModel == m, retouch?.hasReference() == true) {
                    if (plan.match > 0 && plan.matchModel == m) plan.match = 0f
                    else { plan.matchModel = m; if (plan.match == 0f) plan.match = 0.8f }
                    changed()
                })
                if (plan.match > 0 && plan.matchModel == Retouch.Match.CLASSIC) add(pill("存为 LUT", false) { saveLut() })
            }
            Section.DETAIL -> add(pill("Real-ESRGAN", detail > 0, Detail.available(a)) { detail = if (detail > 0) 0f else 0.7f; changed() })
            Section.ERASE -> {
                slider.visibility = View.INVISIBLE
                add(pill("涂抹消除", false, Eraser.available(a)) { openEraser() })
                if (erases.isNotEmpty()) add(pill("撤销上一次", false) { erases.removeAt(erases.lastIndex); erasedCache = null; changed() })
            }
        }
        slider.setValueQuiet(strength() * 100f)
    }

    private fun strength() = when (section) {
        Section.AUTO, Section.COLOR -> plan.color; Section.LIGHT -> plan.light; Section.NOISE -> plan.noise
        Section.MATCH -> plan.match; Section.DETAIL -> detail; Section.ERASE -> 0f
    }

    private fun setStrength(v: Float) {
        when (section) {
            Section.AUTO, Section.COLOR -> plan.color = v; Section.LIGHT -> plan.light = v; Section.NOISE -> plan.noise = v
            Section.MATCH -> plan.match = v; Section.DETAIL -> detail = v; Section.ERASE -> {}
        }
        changed(repaint = false)
    }

    /**
     * 一键: the adaptive colour model, and light only for a dark picture. No denoise: at full size
     * it is a minute's work on a 12 MP photo (71 s, S23 Ultra) for a change hardly seen; it stays
     * one tap away under 降噪.
     */
    fun auto() {
        val o = origin ?: return
        plan.colorModel = Retouch.Color.ADAPTIVE; plan.color = 0.85f
        plan.noise = 0f
        plan.light = if (meanLuma(o) < 0.3f) 0.6f else 0f
        section = Section.COLOR
        haptics.play(Haptics.Event.SEGMENT)
        changed()
    }

    private fun meanLuma(b: Bitmap): Float {
        val s = Bitmap.createScaledBitmap(b, 32, 32, true)
        val px = IntArray(32 * 32); s.getPixels(px, 0, 32, 0, 0, 32, 32); s.recycle()
        return px.sumOf { ((it shr 16 and 255) * 0.3 + (it shr 8 and 255) * 0.59 + (it and 255) * 0.11) } .toFloat() / px.size / 255f
    }

    fun reset() {
        plan.color = 0f; plan.light = 0f; plan.noise = 0f; plan.match = 0f
        detail = 0f
        erases.clear(); erasedCache = null; detailCache = null
        paint()
        origin?.let { onBase(it) }
    }

    // ---- working it out ---------------------------------------------------------------------------

    private fun changed(repaint: Boolean = true) {
        if (repaint) paint()
        val o = origin ?: return
        val n = ticket.incrementAndGet()
        val p = plan.copy(); val d = detail; val masks = erases.toList()
        busy(true)
        exec.execute {
            if (n != ticket.get()) return@execute
            val result = runCatching { preview(o, p, d, masks) }
            a.runOnUiThread {
                if (n != ticket.get() || a.isFinishing) return@runOnUiThread
                busy(false)
                result.onSuccess { current = it; onBase(it) }
                    .onFailure { Toast.makeText(a, "AI 处理失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun busy(on: Boolean) {
        status.text = "AI 处理中…"
        status.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Worker: the preview through 消除, 清晰 and [Retouch], each step's result kept while its inputs stay. */
    private fun preview(o: Bitmap, p: Retouch.Plan, d: Float, masks: List<Bitmap>): Bitmap {
        val erased = if (masks.isEmpty()) o else erasedCache?.takeIf { it.first == masks.size }?.second
            ?: masks.fold(o) { acc, m -> eraser().erase(acc, m) }.also { erasedCache = masks.size to it }
        val sharp = if (d <= 0f) erased else detailCache?.takeIf { it.first == masks.size && it.second == d }?.third
            ?: detail().enhance(erased, d).also { detailCache = Triple(masks.size, d, it) }
        return if (p.any()) engine().preview(sharp, p) else sharp
    }

    /** Worker (the editor's save): [src] at full size through the same steps. */
    /**
     * [full] on the models' own thread, waited for: the models are never run from two threads at
     * once (a preview still working when 存储 is pressed), nor made twice. Any worker thread.
     */
    fun fullOnModels(src: Bitmap, progress: (String) -> Unit): Bitmap =
        try { exec.submit<Bitmap> { full(src, progress) }.get() }
        catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }

    /** The last [full] left AI 清晰 out (it had brought the app down at full size the time before). */
    var detailSkipped = false
        private set

    fun full(src: Bitmap, progress: (String) -> Unit): Bitmap {
        var b = src
        detailSkipped = false
        if (erases.isNotEmpty()) { progress("AI 消除…"); for (m in erases.toList()) b = eraser().erase(b, m) }
        if (detail > 0f) {
            // a native crash cannot be caught: a mark left while 清晰 runs at full size, so the next
            // save after one leaves 清晰 out once (and says so) instead of going down again
            val prefs = a.getSharedPreferences("smart_tools", Activity.MODE_PRIVATE)
            if (prefs.getBoolean(CRASH_MARK, false)) {
                prefs.edit().putBoolean(CRASH_MARK, false).commit()
                detailSkipped = true
            } else {
                prefs.edit().putBoolean(CRASH_MARK, true).commit()
                b = detail().enhance(b, detail) { f -> progress("AI 清晰 ${(f * 100).toInt()}%") }
                prefs.edit().putBoolean(CRASH_MARK, false).commit()
            }
        }
        if (plan.any()) {
            progress("AI 调色…")
            b = engine().full(b, plan.copy()) { step -> progress(step) }
        }
        return b
    }

    private fun engine() = retouch ?: Retouch(a).also { retouch = it }
    private fun eraser() = eraser ?: Eraser(a).also { eraser = it }
    private fun detail() = detailModel ?: Detail(a).also { detailModel = it }

    fun close() {
        exec.execute { runCatching { retouch?.close(); eraser?.close(); detailModel?.close() } }
        exec.shutdown()
    }

    // ---- 仿色 -------------------------------------------------------------------------------------

    /** The editor passes results on; true when it was this tool's. */
    fun onResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != REQUEST_REFERENCE) return false
        val uri = data?.data ?: return true
        if (result != Activity.RESULT_OK) return true
        busy(true)
        exec.execute {
            val r = runCatching {
                val ref = org.photoall.film.collage.Stickers.decode(a, uri, 1024) ?: error("读不出参考图")
                engine().setReference(ref)
            }
            a.runOnUiThread {
                busy(false)
                r.onSuccess {
                    section = Section.MATCH
                    if (plan.match == 0f) plan.match = 0.8f
                    changed()
                }.onFailure { Toast.makeText(a, "参考图不能用：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        return true
    }

    /**
     * The classic 仿色 kept as a 33-point 3D LUT (.cube) in the film camera's own LUTs: every
     * colour of a 33^3 grid sent through the same match (it is the same for every pixel).
     */
    private fun saveLut() {
        val p = Retouch.Plan().apply { match = plan.match; matchModel = Retouch.Match.CLASSIC }
        busy(true)
        exec.execute {
            val r = runCatching {
                val n = 33
                val grid = IntArray(n * n * n)
                for (b in 0 until n) for (g in 0 until n) for (rr in 0 until n)
                    grid[(b * n + g) * n + rr] = (0xff shl 24) or ((rr * 255 / (n - 1)) shl 16) or ((g * 255 / (n - 1)) shl 8) or (b * 255 / (n - 1))
                val img = Bitmap.createBitmap(grid, n * n, n, Bitmap.Config.ARGB_8888)
                val mapped = engine().full(img, p)
                val px = IntArray(n * n * n); mapped.getPixels(px, 0, n * n, 0, 0, n * n, n)
                val stamp = java.text.SimpleDateFormat("MMdd HHmm", java.util.Locale.US).format(java.util.Date())
                val file = java.io.File(a.cacheDir, "match.cube")
                file.bufferedWriter().use { w ->
                    w.write("TITLE \"仿色 $stamp\"\nLUT_3D_SIZE $n\n")
                    for (c in px) w.write("%.6f %.6f %.6f\n".format(java.util.Locale.US, (c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f))
                }
                org.photoall.film.settings.LutLibrary.importCube(a, android.net.Uri.fromFile(file), "仿色 $stamp")
            }
            a.runOnUiThread {
                busy(false)
                Toast.makeText(a, if (r.isSuccess) "已存为 LUT：拍摄时在 LUT 风格里能选到" else "存储 LUT 失败：${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- 消除 -------------------------------------------------------------------------------------

    /** Paint over what should go, then 消除: the picture full screen, a brush, its size. */
    private fun openEraser() {
        val shown = current ?: origin ?: return
        val o = origin ?: return
        val view = EraseView(a, shown, o.width, o.height)
        val size = TickSlider(a).apply {
            min = 10f; max = 120f; step = 5f
            format = { v -> "笔刷 ${v.toInt()}" }
            setValueQuiet(40f)
            onChange = { v -> view.brushDp = v }
        }
        lateinit var layer: FrameLayout
        fun close() { (layer.parent as? ViewGroup)?.removeView(layer) }
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(pill("取消", false) { close() }, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(View(a), LinearLayout.LayoutParams(dp(8), 1))
            addView(pill("重画", false) { view.clear() }, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(View(a), LinearLayout.LayoutParams(dp(8), 1))
            addView(pill("消除", true) {
                if (view.empty) { Toast.makeText(a, "先在要去掉的地方涂抹", Toast.LENGTH_SHORT).show(); return@pill }
                erases += view.mask(); erasedCache = null
                close(); changed()
            }, LinearLayout.LayoutParams(0, dp(40), 1.3f))
        }
        layer = FrameLayout(a).apply {
            background = Style.ground()
            isClickable = true
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(a).apply {
                    text = "涂抹要去掉的地方"; textSize = 15f; typeface = Style.medium; setTextColor(Style.TEXT); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, dp(52)))
                addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(size, LinearLayout.LayoutParams(-1, dp(62)))
                addView(bar, LinearLayout.LayoutParams(-1, -2))
            }, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom); insets
            }
        }
        a.addContentView(layer, ViewGroup.LayoutParams(-1, -1))
        layer.requestApplyInsets()
    }

    /** The picture fitted to the view, a translucent red where the finger has been; the mask the photo's size. */
    private class EraseView(context: Context, private val picture: Bitmap, mw: Int, mh: Int) : View(context) {
        private val maskBmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        private val maskCanvas = Canvas(maskBmp)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val tint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(0x99ff453a.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()
        private val path = Path()
        var brushDp = 40f
        var empty = true
            private set

        fun clear() { maskBmp.eraseColor(0); empty = true; invalidate() }
        fun mask(): Bitmap = maskBmp.copy(Bitmap.Config.ARGB_8888, false)

        override fun onDraw(c: Canvas) {
            val k = minOf(width.toFloat() / picture.width, height.toFloat() / picture.height)
            val w = picture.width * k; val h = picture.height * k
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawBitmap(picture, null, dst, p)
            c.drawBitmap(maskBmp, null, dst, tint)
        }

        private fun toMask(x: Float, y: Float) = floatArrayOf((x - dst.left) / dst.width() * maskBmp.width, (y - dst.top) / dst.height() * maskBmp.height)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val m = toMask(e.x, e.y)
            ink.strokeWidth = brushDp * resources.displayMetrics.density / dst.width() * maskBmp.width
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { path.reset(); path.moveTo(m[0], m[1]); path.lineTo(m[0] + 0.1f, m[1]) }
                MotionEvent.ACTION_MOVE -> path.lineTo(m[0], m[1])
                else -> return true
            }
            maskCanvas.drawPath(path, ink)
            empty = false
            invalidate()
            return true
        }
    }

    companion object {
        private const val CRASH_MARK = "detail_full_running"
        const val REQUEST_REFERENCE = 7301
    }
}
