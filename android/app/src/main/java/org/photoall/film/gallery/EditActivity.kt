package org.photoall.film.gallery

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.photoall.film.settings.Settings
import org.photoall.film.settings.SettingsStore
import org.photoall.film.ui.Haptics
import org.photoall.film.ui.Icon
import org.photoall.film.ui.Style
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The photo editor: tone and colour (调整), crop, quarter turns, mirror and straighten (裁切).
 * The preview is graded on a small copy as the slider moves; on 存储 the original is drawn
 * again at full size through the same steps and kept as a new photo beside it.
 */
class EditActivity : Activity() {

    /** The adjustments, in the order of the strip; each runs -100..100 (褪色 0..100). */
    enum class Adjust(val label: String, val min: Float = -100f) {
        EXPOSURE("曝光"), CONTRAST("对比度"), HIGHLIGHTS("高光"), SHADOWS("阴影"), WHITES("白色"), BLACKS("黑色"),
        SATURATION("饱和度"), VIBRANCE("自然饱和度"), WARMTH("色温"), TINT("色调"), FADE("褪色", 0f), VIGNETTE("暗角"),
    }

    /** Crop shapes: width over height (null: free; 0: the photo's own). */
    private class Aspect(val label: String, val ratio: Float?)

    private val aspects = listOf(
        Aspect("自由", null), Aspect("原比例", 0f), Aspect("1:1", 1f), Aspect("4:5", 4f / 5), Aspect("3:4", 3f / 4),
        Aspect("2:3", 2f / 3), Aspect("9:16", 9f / 16), Aspect("4:3", 4f / 3), Aspect("5:4", 5f / 4), Aspect("3:2", 3f / 2), Aspect("16:9", 16f / 9),
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val grader = Executors.newSingleThreadExecutor()
    private lateinit var store: SettingsStore
    private lateinit var haptics: Haptics
    private val uri by lazy { intent.getParcelableExtra<Uri>(URI)!! }

    // the edit: tone and colour in [look] (调整 / 曲线 / 混色 / 分级), the geometry beside it
    private val look = Look()
    private val values = look.v
    private var turns = 0
    private var flip = false
    private var angle = 0f
    private val crop = RectF(0f, 0f, 1f, 1f)
    private var aspect: Aspect = aspects[0]

    // the preview
    private var base: Bitmap? = null
    private var basePx: IntArray? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var nextBuffer = 0
    private val wanted = AtomicReference<Look?>(null)
    private var work: IntArray? = null
    private var grading = false

    private lateinit var editor: EditView
    private lateinit var slider: TickSlider
    private lateinit var straighten: TickSlider
    private lateinit var adjustPanel: View
    private lateinit var cropPanel: View
    private val chips = mutableListOf<ParamChip>()
    private val aspectChips = mutableListOf<TextView>()
    private val tabs = mutableListOf<TextView>()
    private var selected = Adjust.EXPOSURE
    /** The tool on show: [TOOLS] in order; the last is 裁切. */
    private var mode = 0
    private val cropMode get() = mode == TOOLS.lastIndex
    private val panels = mutableListOf<View>()
    private lateinit var curveView: CurveView
    private val channelChips = mutableListOf<TextView>()
    private lateinit var bandDots: BandDots
    private var band = 0
    private lateinit var hueSlider: LineSlider
    private lateinit var bandSatSlider: LineSlider
    private lateinit var bandLumSlider: LineSlider
    private val wheelViews = mutableListOf<GradeWheel>()
    private val wheelNotes = mutableListOf<TextView>()
    private var zone = 0
    private lateinit var zoneLumSlider: LineSlider
    private lateinit var blendSlider: LineSlider
    private lateinit var balanceSlider: LineSlider
    private var saving: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        haptics = Haptics(window.decorView) { store[Settings.HAPTICS].toInt() }

        editor = EditView(this)

        val top = FrameLayout(this).apply {
            addView(ImageView(this@EditActivity).apply {
                contentDescription = "返回"
                setImageDrawable(Icon(Icon.Kind.BACK, Style.TEXT))
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = Style.surface(this@EditActivity, fill = Style.SURFACE, radiusDp = 22f)
                Style.pressable(this)
                setOnClickListener { leave() }
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(16) })
            addView(TextView(this@EditActivity).apply {
                text = "编辑"; textSize = 16f; typeface = Style.medium; setTextColor(Style.TEXT)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(LinearLayout(this@EditActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
                background = Style.surface(this@EditActivity, fill = Style.SURFACE, radiusDp = 22f)
                addView(capsule("还原", Style.TEXT_2, false) { resetAll() })
                addView(View(this@EditActivity).apply { setBackgroundColor(Style.HAIRLINE) }, LinearLayout.LayoutParams(dp(1), dp(18)))
                addView(capsule("存储", Style.ACCENT, true) { save() })
            }, FrameLayout.LayoutParams(-2, dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
        }

        // 调整: the slider for the chosen adjustment over the strip of them
        slider = TickSlider(this).apply {
            onChange = { v ->
                values[selected.ordinal] = v
                chips[selected.ordinal].invalidate()
                if (selected == Adjust.VIGNETTE) editor.invalidate() else regrade()
            }
            onTick = { stop -> haptics.play(if (stop) Haptics.Event.SEGMENT else Haptics.Event.TICK) }
        }
        val strip = LinearLayout(this).apply {
            setPadding(dp(8), 0, dp(8), 0)
            for (a in Adjust.entries) addView(ParamChip(this@EditActivity, a).also { chip ->
                chips += chip
                chip.setOnClickListener { choose(a) }
            }, LinearLayout.LayoutParams(dp(66), dp(78)))
        }
        adjustPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(slider, LinearLayout.LayoutParams(-1, dp(66)))
            addView(HorizontalScrollView(this@EditActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24))
                addView(strip)
            }, LinearLayout.LayoutParams(-1, dp(82)))
        }

        // 裁切: straighten, turn and mirror, the crop's shape
        straighten = TickSlider(this).apply {
            min = -45f; max = 45f; step = 1f; spacingDp = 7f; majorEvery = 5
            format = { v -> if (abs(v) < 0.05f) "0°" else String.format("%+.1f°", v) }
            fine = true
            onStart = { editor.grid = true; editor.invalidate() }
            onEnd = { editor.grid = false; editor.invalidate() }
            onChange = { v -> angle = v; editor.invalidate() }
            onTick = { stop -> haptics.play(if (stop) Haptics.Event.SEGMENT else Haptics.Event.TICK) }
        }
        val turnRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            addView(roundTool(Icon.Kind.ROTATE, "旋转 90°") { turn() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(roundTool(Icon.Kind.MIRROR, "镜像") { mirror() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
            addView(View(this@EditActivity).apply { setBackgroundColor(Style.HAIRLINE) },
                LinearLayout.LayoutParams(dp(1), dp(24)).apply { marginStart = dp(12); marginEnd = dp(4) })
            addView(HorizontalScrollView(this@EditActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(20))
                addView(LinearLayout(this@EditActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), 0, dp(8), 0)
                    for (a in aspects) addView(TextView(this@EditActivity).apply {
                        text = a.label; textSize = 13f
                        Style.compact(this, 13f)
                        Style.numeric(this)
                        setOnClickListener { chooseAspect(a) }
                        Style.pressable(this)
                        aspectChips += this
                    }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
                })
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        cropPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(straighten, LinearLayout.LayoutParams(-1, dp(66)))
            addView(turnRow, LinearLayout.LayoutParams(-1, dp(82)))
        }

        val curvePanel = curvePanel()
        val mixPanel = mixPanel()
        val gradePanel = gradePanel()
        panels += listOf(adjustPanel, curvePanel, mixPanel, gradePanel, cropPanel)
        for (p in panels) if (p !== adjustPanel) p.visibility = View.GONE

        val tabBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            for ((i, label) in TOOLS.withIndex()) addView(TextView(this@EditActivity).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setOnClickListener { setMode(i) }
                tabs += this
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(-1, dp(64)))
            addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(FrameLayout(this@EditActivity).apply {
                for (p in panels) addView(p, FrameLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(Style.hairline(this@EditActivity), LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)))
            addView(tabBar, LinearLayout.LayoutParams(-1, dp(52)))
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Style.BG)
            addView(column, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        choose(Adjust.EXPOSURE)
        paintTabs()
        paintAspects()

        worker.later {
            val b = decode(1600)?.let { srgb(it) }
            val px = b?.let { IntArray(it.width * it.height).also { a -> it.getPixels(a, 0, it.width, 0, 0, it.width, it.height) } }
            // the brightness histogram the curves are drawn over
            val hist = px?.let { a -> IntArray(256).also { h -> for (c in a) h[((c shr 16 and 255) * 54 + (c shr 8 and 255) * 183 + (c and 255) * 19) shr 8]++ } }
            runOnUiThread {
                if (b == null) { Toast.makeText(this, "读不出这张照片", Toast.LENGTH_SHORT).show(); finish(); return@runOnUiThread }
                base = b; basePx = px
                curveView.histogram = hist
                editor.shown = b
                editor.invalidate()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        grader.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Back: ask before throwing an edit away")
    override fun onBackPressed() = leave()

    // ---- the edit ------------------------------------------------------------------------------

    private fun edited() = look.edited || turns != 0 || flip || angle != 0f ||
        crop.left > 0.0005f || crop.top > 0.0005f || crop.right < 0.9995f || crop.bottom < 0.9995f

    private fun choose(a: Adjust) {
        selected = a
        slider.min = a.min; slider.max = 100f
        slider.setValueQuiet(values[a.ordinal])
        chips.forEach { it.invalidate() }
    }

    private fun setMode(m: Int) {
        if (mode == m) return
        val wasCrop = cropMode
        mode = m
        haptics.play(Haptics.Event.SEGMENT)
        for ((i, p) in panels.withIndex()) if (i != m) p.visibility = View.GONE
        val show = panels[m]
        show.visibility = View.VISIBLE
        show.alpha = 0f; show.translationY = dp(10).toFloat()
        show.animate().alpha(1f).translationY(0f).setDuration(220).start()
        straighten.setValueQuiet(angle)
        if (wasCrop != cropMode) editor.animateMode(cropMode)
        paintTabs()
    }

    // ---- 曲线 / 混色 / 分级 -----------------------------------------------------------------------

    private fun tick() = haptics.play(Haptics.Event.SEGMENT)

    /** 曲线: the curve over the histogram, the channel under it, and a way back to straight. */
    private fun curvePanel(): View {
        curveView = CurveView(this, look, onChange = { paintChannels(); regrade() }, onTick = { tick() })
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            for ((i, label) in listOf("RGB", "红", "绿", "蓝").withIndex()) addView(TextView(this@EditActivity).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                Style.compact(this, 13f)
                setOnClickListener { curveView.channel = i; tick(); paintChannels() }
                Style.pressable(this)
                minWidth = dp(54); setPadding(dp(12), 0, dp(12), 0)
                channelChips += this
            }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
            addView(View(this@EditActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(this@EditActivity).apply {
                text = "重置曲线"; textSize = 13f; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(4), 0)
                Style.pressable(this)
                setOnClickListener {
                    if (!look.curvesEdited) return@setOnClickListener
                    look.resetCurves(); curveView.invalidate(); paintChannels(); regrade(); tick()
                }
            }, LinearLayout.LayoutParams(-2, dp(34)))
        }
        paintChannels()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(curveView, LinearLayout.LayoutParams(-1, dp(176)).apply { leftMargin = dp(10); rightMargin = dp(10) })
            addView(row, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun paintChannels() {
        for ((i, t) in channelChips.withIndex()) {
            val on = curveView.channel == i
            t.setTextColor(if (on) Style.ON_ACCENT else Style.TEXT_2)
            t.background = GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                if (on) setColor(Style.ACCENT) else { setColor(Style.SURFACE); setStroke(maxOf(1, dp(1) / 2), Style.HAIRLINE) }
            }
            // a bent channel says so
            t.text = listOf("RGB", "红", "绿", "蓝")[i] + if (look.curveEdited(i)) " •" else ""
        }
    }

    /** 混色: eight colours, each with its hue, saturation and luminance. */
    private fun mixPanel(): View {
        bandDots = BandDots(this, look, onSelect = { b -> band = b; tick(); syncBand() }, onReset = { b ->
            for (q in 0..2) look.hsl[b * 3 + q] = 0f
            band = b; syncBand(); regrade(); tick()
        })
        fun slider(label: String, q: Int) = LineSlider(this, label).apply {
            onChange = { v -> look.hsl[band * 3 + q] = v; bandDots.invalidate(); regrade() }
            onTick = { tick() }
        }
        hueSlider = slider("色相", 0)
        bandSatSlider = slider("饱和度", 1)
        bandLumSlider = slider("明亮度", 2)
        syncBand()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bandDots, LinearLayout.LayoutParams(-1, dp(62)).apply { leftMargin = dp(10); rightMargin = dp(10) })
            for (s in listOf(hueSlider, bandSatSlider, bandLumSlider)) addView(s, LinearLayout.LayoutParams(-1, dp(44)))
            addView(View(this@EditActivity), LinearLayout.LayoutParams(-1, dp(6)))
        }
    }

    private fun syncBand() {
        val h = Look.BANDS[band].hue
        bandDots.chosen = band
        hueSlider.ramp = intArrayOf(hueColour(h - 30f), hueColour(h), hueColour(h + 30f))
        bandSatSlider.ramp = intArrayOf(0xff8c8a86.toInt(), hueColour(h, 0.9f, 0.5f))
        bandLumSlider.ramp = intArrayOf(hueColour(h, 0.7f, 0.18f), hueColour(h, 0.7f, 0.5f), hueColour(h, 0.7f, 0.85f))
        hueSlider.label = "色相 · ${Look.BANDS[band].label}"
        hueSlider.setValueQuiet(look.hsl[band * 3])
        bandSatSlider.setValueQuiet(look.hsl[band * 3 + 1])
        bandLumSlider.setValueQuiet(look.hsl[band * 3 + 2])
    }

    /** 分级: shadow, midtone and highlight wheels; the chosen one's luminance; blending and balance. */
    private fun gradePanel(): View {
        val wheels = LinearLayout(this).apply {
            setPadding(dp(10), 0, dp(10), 0)
            for ((z, name) in Look.ZONES.withIndex()) addView(LinearLayout(this@EditActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(GradeWheel(this@EditActivity, look, z, onSelect = { chooseZone(it) },
                    onChange = { paintWheels(); regrade() }, onTick = { tick() }).also { wheelViews += it },
                    LinearLayout.LayoutParams(dp(96), dp(96)))
                addView(TextView(this@EditActivity).apply {
                    textSize = 11f; gravity = Gravity.CENTER; setTextColor(Style.TEXT_2)
                    Style.numeric(this)
                    tag = name
                    setOnClickListener { chooseZone(z) }
                    wheelNotes += this
                }, LinearLayout.LayoutParams(-1, dp(20)))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        zoneLumSlider = LineSlider(this, "明亮度").apply {
            onChange = { v -> look.wheels[zone * 3 + 2] = v; paintWheels(); regrade() }
            onTick = { tick() }
        }
        blendSlider = LineSlider(this, "混合", 0f, 100f, 50f).apply {
            onChange = { v -> look.wheels[Look.BLEND] = v; regrade() }
            onTick = { tick() }
        }
        balanceSlider = LineSlider(this, "平衡（偏阴影 ← → 偏高光）").apply {
            onChange = { v -> look.wheels[Look.BALANCE] = v; regrade() }
            onTick = { tick() }
        }
        chooseZone(0, quiet = true)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(wheels, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            for (s in listOf(zoneLumSlider, blendSlider, balanceSlider)) addView(s, LinearLayout.LayoutParams(-1, dp(44)))
            addView(View(this@EditActivity), LinearLayout.LayoutParams(-1, dp(4)))
        }
    }

    private fun chooseZone(z: Int, quiet: Boolean = false) {
        if (!quiet && z != zone) tick()
        zone = z
        zoneLumSlider.label = "明亮度 · ${Look.ZONES[z]}"
        zoneLumSlider.setValueQuiet(look.wheels[z * 3 + 2])
        paintWheels()
    }

    private fun paintWheels() {
        for ((z, w) in wheelViews.withIndex()) { w.chosen = z == zone; w.invalidate() }
        for ((z, t) in wheelNotes.withIndex()) {
            val amount = look.wheels[z * 3 + 1].roundToInt()
            val lum = look.wheels[z * 3 + 2].roundToInt()
            t.text = listOfNotNull(Look.ZONES[z],
                if (amount > 0) "${look.wheels[z * 3].roundToInt()}° $amount" else null,
                if (lum != 0) (if (lum > 0) "+$lum" else "$lum") else null).joinToString(" · ")
            t.setTextColor(if (z == zone) Style.TEXT else Style.TEXT_2)
            t.typeface = if (z == zone) Style.medium else Style.regular
        }
    }

    /** The colour tools showing the look as it is now (after 还原). */
    private fun syncColourPanels() {
        curveView.invalidate(); paintChannels()
        syncBand(); bandDots.invalidate()
        blendSlider.setValueQuiet(look.wheels[Look.BLEND]); balanceSlider.setValueQuiet(look.wheels[Look.BALANCE])
        chooseZone(zone, quiet = true)
    }

    private fun paintTabs() {
        for ((i, t) in tabs.withIndex()) {
            val on = i == mode
            t.setTextColor(if (on) Style.TEXT else Style.TEXT_2)
            t.typeface = if (on) Style.medium else Style.regular
            t.background = if (on) UnderDot(Style.ACCENT, dp(3).toFloat(), dp(9).toFloat()) else null
        }
    }

    private fun paintAspects() {
        for ((i, t) in aspectChips.withIndex()) {
            val on = aspects[i] === aspect
            t.setTextColor(if (on) Style.ON_ACCENT else Style.TEXT_2)
            t.background = GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                if (on) setColor(Style.ACCENT) else { setColor(Style.SURFACE); setStroke(maxOf(1, dp(1) / 2), Style.HAIRLINE) }
            }
        }
    }

    /** The frame's size (the photo after its quarter turns), in preview pixels. */
    private fun frameW() = base?.let { if (turns % 2 == 1) it.height else it.width } ?: 1
    private fun frameH() = base?.let { if (turns % 2 == 1) it.width else it.height } ?: 1

    /** The chosen shape as a share of the frame: width over height of the crop in 0..1 units. */
    private fun normRatio(): Float? {
        val r = aspect.ratio ?: return null
        val px = if (r == 0f) frameW().toFloat() / frameH() else r
        return px * frameH() / frameW()
    }

    private fun chooseAspect(a: Aspect) {
        aspect = a
        paintAspects()
        haptics.play(Haptics.Event.SEGMENT)
        val rn = normRatio() ?: return
        // the largest crop of that shape, round the present crop's centre
        var w = 1f; var h = w / rn
        if (h > 1f) { h = 1f; w = rn }
        val cx = crop.centerX().coerceIn(w / 2, 1 - w / 2)
        val cy = crop.centerY().coerceIn(h / 2, 1 - h / 2)
        editor.animateCrop(RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2))
    }

    /** A quarter turn clockwise: the crop turns with the picture. */
    private fun turn() {
        turns = (turns + 1) % 4
        crop.set(1 - crop.bottom, crop.left, 1 - crop.top, crop.right)
        // a fixed shape turns too: 4:5 becomes 5:4
        aspect.ratio?.takeIf { it > 0f && it != 1f }?.let { r ->
            aspect = aspects.firstOrNull { o -> o.ratio != null && abs(o.ratio * r - 1f) < 0.01f } ?: aspects[0]
            paintAspects()
        }
        haptics.play(Haptics.Event.SEGMENT)
        editor.spin(-90f)
    }

    /** Mirrored left to right; the straightening keeps its look. */
    private fun mirror() {
        flip = !flip
        angle = -angle
        straighten.setValueQuiet(angle)
        crop.set(1 - crop.right, crop.top, 1 - crop.left, crop.bottom)
        haptics.play(Haptics.Event.SEGMENT)
        editor.flipIn()
    }

    private fun resetAll() {
        if (!edited()) return
        look.resetAll()
        syncColourPanels()
        turns = 0; flip = false; angle = 0f
        aspect = aspects[0]
        crop.set(0f, 0f, 1f, 1f)
        slider.setValueQuiet(0f); straighten.setValueQuiet(0f)
        chips.forEach { it.invalidate() }
        paintAspects()
        regrade()
        editor.invalidate()
        haptics.play(Haptics.Event.SEGMENT)
    }

    private fun leave() {
        if (saving != null) return
        if (!edited()) { finish(); return }
        lateinit var sheet: org.photoall.film.ui.Sheet
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(18))
            addView(TextView(this@EditActivity).apply {
                text = "这次的调整还没有存储，退出后不会保留。"
                textSize = 13f; setTextColor(Style.TEXT_2)
            })
            addView(LinearLayout(this@EditActivity).apply {
                addView(sheetButton("放弃修改", Style.SURFACE_2, Style.TEXT) { sheet.dismiss(); finish() },
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
                addView(sheetButton("存储", Style.ACCENT, Style.ON_ACCENT) { sheet.dismiss(); save() },
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }
        sheet = org.photoall.film.ui.Sheet(this, "放弃修改？", body)
        sheet.show()
    }

    // ---- grading the preview ---------------------------------------------------------------------

    /** Grade the preview again with the present values; requests while one runs fold into the next. */
    private fun regrade() {
        wanted.set(look.copy())
        if (grading) return
        grading = true
        grader.later { gradeLoop() }
    }

    private fun gradeLoop() {
        while (true) {
            val v = wanted.getAndSet(null) ?: break
            val b = base ?: break
            val px = basePx ?: break
            val g = Grade(v)
            if (g.identity) { handOff(b); continue }
            val out = work?.takeIf { it.size == px.size } ?: IntArray(px.size).also { work = it }
            System.arraycopy(px, 0, out, 0, px.size)
            g.apply(out, 0, out.size)
            val target = buffers[nextBuffer] ?: Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { buffers[nextBuffer] = it }
            target.setPixels(out, 0, b.width, 0, 0, b.width, b.height)
            nextBuffer = 1 - nextBuffer
            handOff(target)
        }
        runOnUiThread {
            grading = false
            if (wanted.get() != null) { grading = true; grader.later { gradeLoop() } }
        }
    }

    /** Shows [b] and waits until it is on screen, so the next grade never paints into a shown buffer. */
    private fun handOff(b: Bitmap) {
        val latch = CountDownLatch(1)
        runOnUiThread { editor.shown = b; editor.invalidate(); latch.countDown() }
        latch.await(600, TimeUnit.MILLISECONDS)
    }

    // ---- saving --------------------------------------------------------------------------------------

    private fun save() {
        if (saving != null) return
        if (!edited()) { Toast.makeText(this, "还没有修改", Toast.LENGTH_SHORT).show(); return }
        val v = look.copy()
        val t = turns; val f = flip; val a = angle; val c = RectF(crop)
        val name = intent.getStringExtra(NAME) ?: "FILM.jpg"
        val stem = name.substringBeforeLast('.').replace(Regex("_edit\\d*$"), "")
        val newName = "${stem}_edit${(System.currentTimeMillis() / 1000) % 1000000}.jpg"
        val private = intent.getBooleanExtra(PRIVATE, false)
        val description = intent.getStringExtra(DESCRIPTION) ?: ""
        saving = veil()
        worker.later {
            val result = runCatching {
                val src = decode(8192)?.let { srgb(it) } ?: error("读不出原图")
                val out = render(src, v, t, f, a, c)
                src.recycle()
                Photos.saveJpeg(this, out, newName, store[Settings.JPEG_QUALITY].roundToInt(),
                    listOf(description, "已编辑").filter { it.isNotEmpty() }.joinToString(" · "),
                    ShotInfo.read(this, uri), private = private)
                out.recycle()
            }
            runOnUiThread {
                saving?.let { (it.parent as? ViewGroup)?.removeView(it) }
                saving = null
                result.onSuccess {
                    haptics.play(Haptics.Event.DEVELOPED)
                    Toast.makeText(this, "已存为一张新照片", Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { Toast.makeText(this, "存储失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun veil(): View {
        val v = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            alpha = 0f
            addView(LinearLayout(this@EditActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(22), dp(28), dp(22))
                background = GradientDrawable().apply { setColor(Style.SURFACE); cornerRadius = dp(22).toFloat() }
                elevation = dp(12).toFloat()
                addView(android.widget.ProgressBar(this@EditActivity).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Style.ACCENT)
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(this@EditActivity).apply {
                    text = "正在按原图尺寸存储…"; textSize = 15f; setTextColor(Style.TEXT); typeface = Style.medium
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        addContentView(v, ViewGroup.LayoutParams(-1, -1))
        v.animate().alpha(1f).setDuration(160).start()
        return v
    }

    private fun decode(maxLong: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val s = minOf(1f, maxLong.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    // ---- views ----------------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun capsule(text: String, colour: Int, bold: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(colour)
        typeface = if (bold) Style.medium else Style.regular
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(-2, -1) }

    private fun roundTool(kind: Icon.Kind, label: String, onClick: () -> Unit) = ImageView(this).apply {
        contentDescription = label
        setImageDrawable(Icon(kind, Style.TEXT))
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = Style.surface(this@EditActivity, fill = Style.SURFACE, radiusDp = 22f)
        Style.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun sheetButton(text: String, fill: Int, colour: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(colour); typeface = Style.medium; gravity = Gravity.CENTER
        background = Style.surface(this@EditActivity, fill = fill, radiusDp = 14f)
        Style.pressable(this)
        setOnClickListener { onClick() }
    }

    /** A small dot under the chosen tab. */
    private class UnderDot(val colour: Int, val r: Float, val below: Float) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
        override fun draw(c: Canvas) { c.drawCircle(bounds.exactCenterX(), bounds.bottom - below, r / 1.4f, p) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * The picture. In 调整 the crop fills it; in 裁切 the whole frame shows, the cut away part
     * dimmed, with corners to pull, edges to push and the inside to move. Held down in 调整,
     * it shows the photo as it was.
     */
    private inner class EditView(context: Context) : View(context) {
        var shown: Bitmap? = null
        var grid = false
        private var showOriginal = false
        private var t = 0f              // 0: 调整, 1: 裁切
        private var spin = 0f           // a quarter turn as it happens
        private var mirrorK = 1f        // the mirror as it happens: -1..1 across
        private val d = resources.displayMetrics.density
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val m = Matrix()
        private val view = Matrix()
        private val full = RectF()
        private val cropPx = RectF()
        private val win = RectF()
        private val frameV = RectF()
        private val cropV = RectF()
        private val path = Path()
        private var scale = 1f
        private val showOld = Runnable { showOriginal = true; haptics.play(Haptics.Event.SEGMENT); invalidate() }

        fun animateMode(on: Boolean) {
            ValueAnimator.ofFloat(t, if (on) 1f else 0f).apply {
                duration = 320; interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
            }.start()
        }

        fun animateCrop(to: RectF) {
            val from = RectF(crop)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260; interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
                addUpdateListener {
                    val k = it.animatedValue as Float
                    crop.set(from.left + (to.left - from.left) * k, from.top + (to.top - from.top) * k,
                        from.right + (to.right - from.right) * k, from.bottom + (to.bottom - from.bottom) * k)
                    invalidate()
                }
            }.start()
        }

        fun spin(from: Float) {
            ValueAnimator.ofFloat(from, 0f).apply {
                duration = 280; interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
                addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            }.start()
        }

        fun flipIn() {
            ValueAnimator.ofFloat(-1f, 1f).apply {
                duration = 300; interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
                addUpdateListener { mirrorK = it.animatedValue as Float; invalidate() }
            }.start()
        }

        override fun onDraw(c: Canvas) {
            val b0 = base ?: return
            val b = if (showOriginal) b0 else (shown ?: b0)
            val fw = frameW().toFloat(); val fh = frameH().toFloat()
            full.set(0f, 0f, fw, fh)
            cropPx.set(crop.left * fw, crop.top * fh, crop.right * fw, crop.bottom * fh)
            win.set(cropPx.left + (full.left - cropPx.left) * t, cropPx.top + (full.top - cropPx.top) * t,
                cropPx.right + (full.right - cropPx.right) * t, cropPx.bottom + (full.bottom - cropPx.bottom) * t)
            val pad = (14f + 18f * t) * d
            scale = min((width - 2 * pad) / win.width(), (height - 2 * pad) / win.height())
            view.setTranslate(-win.centerX(), -win.centerY())
            view.postScale(scale, scale)
            view.postTranslate(width / 2f, height / 2f)
            frameMatrix(b.width, b.height, turns, flip, angle, m)
            m.postConcat(view)
            frameV.set(full); view.mapRect(frameV)
            cropV.set(cropPx); view.mapRect(cropV)

            c.save()
            c.rotate(spin, width / 2f, height / 2f)
            if (mirrorK != 1f) c.scale(abs(mirrorK).coerceAtLeast(0.02f), 1f, width / 2f, height / 2f)
            c.save()
            c.clipRect(frameV)
            c.drawBitmap(b, m, bitmapPaint)
            c.restore()
            if (!showOriginal) vignette(c, cropV, values[Adjust.VIGNETTE.ordinal])

            // outside the crop: gone in 调整, dimmed in 裁切
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(frameV.left - 2, frameV.top - 2, frameV.right + 2, frameV.bottom + 2, Path.Direction.CW)
            path.addRect(cropV, Path.Direction.CW)
            p.color = Style.BG; p.alpha = (255 - 120 * t).roundToInt()
            c.drawPath(path, p)

            if (t > 0.01f) cropMarks(c)
            c.restore()

            if (showOriginal) {
                p.color = 0xb0000000.toInt()
                p.textSize = 12 * d; p.typeface = Style.medium
                val label = "原图"
                val tw = p.measureText(label)
                val r = RectF(width / 2f - tw / 2 - 12 * d, 10 * d, width / 2f + tw / 2 + 12 * d, 36 * d)
                c.drawRoundRect(r, 13 * d, 13 * d, p)
                p.color = 0xffffffff.toInt(); p.textAlign = Paint.Align.CENTER
                c.drawText(label, width / 2f, 27.5f * d, p)
                p.textAlign = Paint.Align.LEFT
            }
        }

        private fun cropMarks(c: Canvas) {
            val a = (t * 255).roundToInt()
            // the thirds (fine while straightening), while a finger is on it
            if (grid || dragging) {
                val n = if (grid) 9 else 3
                line.color = 0xffffffff.toInt(); line.alpha = (a * (if (grid) 0.35f else 0.55f)).roundToInt()
                line.strokeWidth = 0.75f * d
                for (i in 1 until n) {
                    val x = cropV.left + cropV.width() * i / n
                    val y = cropV.top + cropV.height() * i / n
                    c.drawLine(x, cropV.top, x, cropV.bottom, line)
                    c.drawLine(cropV.left, y, cropV.right, y, line)
                }
            }
            line.color = 0xffffffff.toInt(); line.alpha = (a * 0.9f).roundToInt(); line.strokeWidth = 1f * d
            c.drawRect(cropV, line)
            // the corners, as thick L marks; a short bar mid each edge
            line.strokeWidth = 3f * d; line.alpha = a; line.strokeCap = Paint.Cap.SQUARE
            val k = 18 * d; val o = 1.5f * d
            for ((x, y, sx, sy) in listOf(
                floatArrayOf(cropV.left - o, cropV.top - o, 1f, 1f), floatArrayOf(cropV.right + o, cropV.top - o, -1f, 1f),
                floatArrayOf(cropV.left - o, cropV.bottom + o, 1f, -1f), floatArrayOf(cropV.right + o, cropV.bottom + o, -1f, -1f))) {
                c.drawLine(x, y, x + k * sx, y, line)
                c.drawLine(x, y, x, y + k * sy, line)
            }
            if (normRatio() == null) {
                val e = 10 * d
                c.drawLine(cropV.centerX() - e, cropV.top - o, cropV.centerX() + e, cropV.top - o, line)
                c.drawLine(cropV.centerX() - e, cropV.bottom + o, cropV.centerX() + e, cropV.bottom + o, line)
                c.drawLine(cropV.left - o, cropV.centerY() - e, cropV.left - o, cropV.centerY() + e, line)
                c.drawLine(cropV.right + o, cropV.centerY() - e, cropV.right + o, cropV.centerY() + e, line)
            }
            line.strokeCap = Paint.Cap.BUTT
        }

        // ---- touch
        private var dragging = false
        private var handle = -1         // 0..3 corners (TL TR BL BR), 4..7 edges (T B L R), 8 move
        private var downX = 0f
        private var downY = 0f
        private val start = RectF()

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (base == null) return false
            if (!cropMode) return holdToCompare(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handle = hit(e.x, e.y)
                    if (handle < 0) return false
                    dragging = true
                    downX = e.x; downY = e.y; start.set(crop)
                    parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    drag((e.x - downX) / (scale * frameW()), (e.y - downY) / (scale * frameH()))
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; handle = -1; invalidate() }
            }
            return true
        }

        private fun holdToCompare(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> postDelayed(showOld, 160)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(showOld)
                    if (showOriginal) { showOriginal = false; invalidate() }
                }
            }
            return true
        }

        private fun hit(x: Float, y: Float): Int {
            val r = 30 * d
            val corners = listOf(cropV.left to cropV.top, cropV.right to cropV.top, cropV.left to cropV.bottom, cropV.right to cropV.bottom)
            var best = -1; var bestD = r
            for ((i, pt) in corners.withIndex()) {
                val dd = hypot(x - pt.first, y - pt.second)
                if (dd < bestD) { bestD = dd; best = i }
            }
            if (best >= 0) return best
            val inX = x > cropV.left && x < cropV.right
            val inY = y > cropV.top && y < cropV.bottom
            if (normRatio() == null) {
                val er = 22 * d
                if (inX && abs(y - cropV.top) < er) return 4
                if (inX && abs(y - cropV.bottom) < er) return 5
                if (inY && abs(x - cropV.left) < er) return 6
                if (inY && abs(x - cropV.right) < er) return 7
            }
            return if (inX && inY) 8 else -1
        }

        /** The crop pulled by (dx, dy), in shares of the frame, from where it was when the finger came down. */
        private fun drag(dx: Float, dy: Float) {
            val minS = 0.08f
            val s = start
            when (handle) {
                8 -> {
                    val ox = dx.coerceIn(-s.left, 1 - s.right)
                    val oy = dy.coerceIn(-s.top, 1 - s.bottom)
                    crop.set(s.left + ox, s.top + oy, s.right + ox, s.bottom + oy)
                }
                4 -> crop.top = (s.top + dy).coerceIn(0f, s.bottom - minS)
                5 -> crop.bottom = (s.bottom + dy).coerceIn(s.top + minS, 1f)
                6 -> crop.left = (s.left + dx).coerceIn(0f, s.right - minS)
                7 -> crop.right = (s.right + dx).coerceIn(s.left + minS, 1f)
                in 0..3 -> {
                    val right = handle == 1 || handle == 3
                    val bottom = handle == 2 || handle == 3
                    val ax = if (right) s.left else s.right      // the corner that stays
                    val ay = if (bottom) s.top else s.bottom
                    val px = (if (right) s.right else s.left) + dx
                    val py = (if (bottom) s.bottom else s.top) + dy
                    val maxW = if (right) 1 - ax else ax
                    val maxH = if (bottom) 1 - ay else ay
                    var w = (if (right) px - ax else ax - px).coerceIn(minS, maxW)
                    var h = (if (bottom) py - ay else ay - py).coerceIn(minS, maxH)
                    val rn = normRatio()
                    if (rn != null) {
                        if (w / h > rn) w = h * rn else h = w / rn
                        if (w > maxW) { w = maxW; h = w / rn }
                        if (h > maxH) { h = maxH; w = h * rn }
                    }
                    val l = if (right) ax else ax - w
                    val tp = if (bottom) ay else ay - h
                    crop.set(l, tp, l + w, tp + h)
                }
            }
        }
    }

    /**
     * One adjustment in the strip: a ring that fills with the value (clockwise up, the other way
     * down), its sign or number inside, the name under it.
     */
    private inner class ParamChip(context: Context, val adjust: Adjust) : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val oval = RectF()
        private val path = Path()

        init {
            contentDescription = adjust.label
            Style.pressable(this)
            setOnLongClickListener {
                values[adjust.ordinal] = 0f
                if (selected == adjust) slider.setValueQuiet(0f)
                invalidate()
                if (adjust == Adjust.VIGNETTE) editor.invalidate() else regrade()
                haptics.play(Haptics.Event.SEGMENT)
                true
            }
        }

        override fun onDraw(c: Canvas) {
            val on = selected == adjust
            val v = values[adjust.ordinal]
            val cx = width / 2f; val cy = 28 * d; val r = 19 * d
            ring.strokeWidth = 1.4f * d
            ring.color = if (on) Style.TEXT else Style.TEXT_3
            c.drawCircle(cx, cy, r, ring)
            if (v != 0f) {
                ring.color = Style.ACCENT; ring.strokeWidth = 2.6f * d; ring.strokeCap = Paint.Cap.ROUND
                oval.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(oval, -90f, 360f * v / 100f, false, ring)
                ring.strokeCap = Paint.Cap.BUTT
                p.color = if (on) Style.TEXT else Style.TEXT_2
                p.textSize = 12 * d; p.typeface = Style.medium; p.textAlign = Paint.Align.CENTER
                p.fontFeatureSettings = "tnum"
                c.drawText(v.roundToInt().let { if (it > 0) "+$it" else "$it" }, cx, cy + 4.2f * d, p)
            } else glyph(c, cx, cy, if (on) Style.TEXT else Style.TEXT_2)
            p.color = if (on) Style.TEXT else Style.TEXT_2
            p.textSize = 11 * d; p.typeface = if (on) Style.medium else Style.regular; p.textAlign = Paint.Align.CENTER
            c.drawText(adjust.label, cx, 66 * d, p)
        }

        /** A small sign for each: a sun, half moons, a drop, a thermometer… */
        private fun glyph(c: Canvas, cx: Float, cy: Float, colour: Int) {
            val u = d
            ring.color = colour; ring.strokeWidth = 1.3f * u
            p.color = colour; p.style = Paint.Style.FILL
            when (adjust) {
                Adjust.EXPOSURE -> {
                    c.drawCircle(cx, cy, 3.6f * u, ring)
                    for (i in 0 until 8) {
                        val a = Math.PI * i / 4
                        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                        c.drawLine(cx + ca * 6 * u, cy + sa * 6 * u, cx + ca * 8.2f * u, cy + sa * 8.2f * u, ring)
                    }
                }
                Adjust.CONTRAST -> {
                    c.drawCircle(cx, cy, 7 * u, ring)
                    oval.set(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u); c.drawArc(oval, -90f, 180f, true, p)
                }
                Adjust.HIGHLIGHTS -> {
                    c.drawCircle(cx, cy, 7 * u, ring)
                    oval.set(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u); c.drawArc(oval, 180f, 180f, true, p)
                }
                Adjust.SHADOWS -> {
                    c.drawCircle(cx, cy, 7 * u, ring)
                    oval.set(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u); c.drawArc(oval, 0f, 180f, true, p)
                }
                Adjust.WHITES -> { c.drawCircle(cx, cy, 7 * u, ring); c.drawCircle(cx, cy, 3.2f * u, ring) }
                Adjust.BLACKS -> c.drawCircle(cx, cy, 7 * u, p)
                Adjust.SATURATION, Adjust.VIBRANCE -> {
                    path.reset()
                    path.moveTo(cx, cy - 8 * u)
                    path.cubicTo(cx + 3 * u, cy - 3.5f * u, cx + 6 * u, cy, cx + 6 * u, cy + 2.5f * u)
                    path.cubicTo(cx + 6 * u, cy + 6 * u, cx + 3 * u, cy + 8 * u, cx, cy + 8 * u)
                    path.cubicTo(cx - 3 * u, cy + 8 * u, cx - 6 * u, cy + 6 * u, cx - 6 * u, cy + 2.5f * u)
                    path.cubicTo(cx - 6 * u, cy, cx - 3 * u, cy - 3.5f * u, cx, cy - 8 * u)
                    if (adjust == Adjust.SATURATION) c.drawPath(path, p) else {
                        c.drawPath(path, ring)
                        c.drawLine(cx - 3 * u, cy + 3 * u, cx + 3 * u, cy + 3 * u, ring)
                    }
                }
                Adjust.WARMTH -> {
                    c.drawLine(cx, cy - 7.5f * u, cx, cy + 2 * u, ring)
                    c.drawCircle(cx, cy + 4.5f * u, 3 * u, p)
                    for (i in 0..2) c.drawLine(cx + 2.5f * u, cy - 6 * u + i * 2.8f * u, cx + 4.5f * u, cy - 6 * u + i * 2.8f * u, ring)
                }
                Adjust.TINT -> {
                    c.drawCircle(cx - 2.6f * u, cy, 5.2f * u, ring)
                    c.drawCircle(cx + 2.6f * u, cy, 5.2f * u, ring)
                }
                Adjust.FADE -> for (i in 0..2) {
                    ring.alpha = 255 - i * 80
                    c.drawLine(cx - 7 * u, cy - 4.5f * u + i * 4.5f * u, cx + 7 * u, cy - 4.5f * u + i * 4.5f * u, ring)
                }
                Adjust.VIGNETTE -> {
                    oval.set(cx - 8.5f * u, cy - 6.5f * u, cx + 8.5f * u, cy + 6.5f * u)
                    c.drawRoundRect(oval, 2 * u, 2 * u, ring)
                    c.drawCircle(cx, cy, 3.6f * u, p)
                }
            }
            ring.alpha = 255
        }
    }

    companion object {
        const val URI = "uri"
        const val NAME = "name"
        const val PRIVATE = "private"
        const val DESCRIPTION = "description"
        /** The tools along the bottom; 裁切 last. */
        val TOOLS = listOf("调整", "曲线", "混色", "分级", "裁切")

        /**
         * The frame (the photo after its quarter turns, fw × fh) from a w × h picture: turned,
         * mirrored, straightened by [angle] and enlarged just enough that no corner shows empty.
         */
        fun frameMatrix(w: Int, h: Int, turns: Int, flip: Boolean, angle: Float, out: Matrix): Matrix {
            val odd = turns % 2 == 1
            val fw = (if (odd) h else w).toFloat(); val fh = (if (odd) w else h).toFloat()
            val a = Math.toRadians(abs(angle).toDouble())
            val cover = (cos(a) + max(fw / fh, fh / fw) * sin(a)).toFloat()
            out.reset()
            out.postTranslate(-w / 2f, -h / 2f)
            out.postRotate(90f * turns)
            if (flip) out.postScale(-1f, 1f)
            out.postRotate(angle)
            out.postScale(cover, cover)
            out.postTranslate(fw / 2f, fh / 2f)
            return out
        }

        /** Darker (or lighter) towards the corners of [r]: the same in the preview and the saved photo. */
        fun vignette(c: Canvas, r: RectF, amount: Float) {
            if (amount == 0f) return
            val k = abs(amount) / 100f
            val colour = if (amount < 0) 0x000000 else 0xffffff
            val edge = ((if (amount < 0) 0.78f else 0.6f) * k * 255).roundToInt()
            val radius = hypot(r.width(), r.height()) / 2
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(r.centerX(), r.centerY(), radius,
                intArrayOf(colour, colour, (edge / 3 shl 24) or colour, (edge shl 24) or colour),
                floatArrayOf(0f, 0.42f - 0.12f * k, 0.75f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(r, p)
        }

        /** [b] in sRGB, 8 bits a channel (the grade works on those numbers). */
        fun srgb(b: Bitmap): Bitmap {
            val sRGB = ColorSpace.get(ColorSpace.Named.SRGB)
            if (b.config == Bitmap.Config.ARGB_8888 && (b.colorSpace == null || b.colorSpace == sRGB)) return b
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888, true, sRGB)
            Canvas(out).drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            b.recycle()
            return out
        }

        /** The edit at the picture's own size. */
        fun render(src: Bitmap, look: Look, turns: Int, flip: Boolean, angle: Float, crop: RectF): Bitmap {
            val odd = turns % 2 == 1
            val fw = if (odd) src.height else src.width
            val fh = if (odd) src.width else src.height
            val ow = maxOf(1, (crop.width() * fw).roundToInt())
            val oh = maxOf(1, (crop.height() * fh).roundToInt())
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val m = frameMatrix(src.width, src.height, turns, flip, angle, Matrix())
            m.postTranslate(-crop.left * fw, -crop.top * fh)
            val c = Canvas(out)
            c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val g = Grade(look)
            if (!g.identity) {
                val rows = maxOf(1, 1_000_000 / ow)
                val buf = IntArray(ow * rows)
                var y = 0
                while (y < oh) {
                    val n = min(rows, oh - y)
                    out.getPixels(buf, 0, ow, 0, y, ow, n)
                    g.apply(buf, 0, ow * n)
                    out.setPixels(buf, 0, ow, 0, y, ow, n)
                    y += n
                }
            }
            vignette(c, RectF(0f, 0f, ow.toFloat(), oh.toFloat()), look.v[Adjust.VIGNETTE.ordinal])
            return out
        }
    }
}

/**
 * A ruler under a fixed needle: drag it sideways to change the value, ticks pass under the
 * needle; zero holds a moment as it goes by; a double tap puts it back at zero.
 */
class TickSlider(context: Context) : View(context) {
    var min = -100f
    var max = 100f
    var step = 5f
    var spacingDp = 8f
    var majorEvery = 5
    /** Values in tenths (straightening), not whole numbers. */
    var fine = false
    var format: (Float) -> String = { v -> v.roundToInt().let { if (it > 0) "+$it" else "$it" } }
    var onChange: ((Float) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onTick: ((Boolean) -> Unit)? = null
    var value = 0f
        private set

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var raw = 0f
    private var lastX = 0f
    private var lastTick = 0
    private val unit get() = spacingDp * d / step
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (value != 0f) { set(0f); onTick?.invoke(true) }
            return true
        }
    })

    fun setValueQuiet(v: Float) { value = v.coerceIn(min, max); raw = value; invalidate() }

    private fun set(v: Float) {
        val q = if (fine) (v * 10).roundToInt() / 10f else v.roundToInt().toFloat()
        if (q == value) return
        value = q
        onChange?.invoke(q)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; raw = value; lastTick = floor(value / step).toInt()
                parent.requestDisallowInterceptTouchEvent(true)
                onStart?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val was = raw
                raw = (raw - (e.x - lastX) / unit).coerceIn(min, max)
                lastX = e.x
                // zero holds while the finger passes it
                val snapped = if (abs(raw) < step * 0.4f && min < 0f) 0f else raw
                if (snapped == 0f && value != 0f && was != raw) onTick?.invoke(true)
                val tick = floor(snapped / step).toInt()
                if (tick != lastTick) { lastTick = tick; if (snapped != 0f) onTick?.invoke(false) }
                if ((raw <= min || raw >= max) && was != raw) onTick?.invoke(true)
                set(snapped)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onEnd?.invoke()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val base = height - 16 * d
        // the value above the needle
        p.textAlign = Paint.Align.CENTER
        p.textSize = 13 * d; p.typeface = Style.medium; p.fontFeatureSettings = "tnum"
        p.color = if (value != 0f) Style.ACCENT else Style.TEXT_2
        c.drawText(format(value), cx, 18 * d, p)
        // ticks
        val first = kotlin.math.ceil(min / step).toInt()
        val last = floor(max / step).toInt()
        p.strokeWidth = 1.2f * d
        for (i in first..last) {
            val tv = i * step
            val x = cx + (tv - value) * unit
            if (x < -4 || x > width + 4) continue
            val fadeK = (1f - (abs(x - cx) / (width / 2f)).pow(2)).coerceIn(0f, 1f)
            val major = i % majorEvery == 0
            val h = if (major) 14 * d else 8 * d
            p.color = if (major) Style.TEXT_2 else Style.TEXT_3
            p.alpha = (255 * fadeK).roundToInt()
            c.drawLine(x, base - h, x, base, p)
            if (i == 0) {
                p.color = Style.TEXT; p.alpha = (255 * fadeK).roundToInt()
                c.drawCircle(x, base + 6 * d, 2 * d, p)
            }
        }
        // the stretch from zero to the value
        if (value != 0f && min < 0f) {
            p.color = Style.ACCENT; p.alpha = 90; p.strokeWidth = 3 * d; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx - value * unit, base + 1.5f * d, cx, base + 1.5f * d, p)
            p.strokeCap = Paint.Cap.BUTT
        }
        // the needle
        p.alpha = 255
        p.color = Style.ACCENT; p.strokeWidth = 2 * d; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, base - 22 * d, cx, base + 2 * d, p)
        p.strokeCap = Paint.Cap.BUTT
    }
}
