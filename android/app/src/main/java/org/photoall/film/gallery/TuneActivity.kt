package org.photoall.film.gallery

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import org.photoall.film.engine.DevelopOptions
import org.photoall.film.engine.FilmEngine
import org.photoall.film.engine.REFERENCE_EXPOSURE
import org.photoall.film.settings.LutLibrary
import org.photoall.film.settings.Setting
import org.photoall.film.settings.SettingValues
import org.photoall.film.settings.Settings
import org.photoall.film.settings.SettingsPanel
import org.photoall.film.settings.SettingsStore
import org.photoall.film.ui.Icon
import org.photoall.film.ui.Segmented
import org.photoall.film.ui.Skin
import org.photoall.film.ui.Style
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 调参台 (from the tools page): a picture of the user's own (from the album, a RAW file, or one
 * shot on the main camera as RAW) developed with the camera's film engine while its settings
 * change. The original and the develop share the top half, split by a divider to drag; the
 * settings are below, in plain words with what each end does (标准) or all of them, a group to
 * a tab (Pro). Nothing reaches the camera until 应用到拍摄; then the next shots use them.
 * The look follows the theme: the frame round the picture, the faces and the keys.
 */
class TuneActivity : ComponentActivity() {
    private lateinit var store: SettingsStore
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var engine: FilmEngine? = null

    // the picture being tuned: a bitmap (any image) and, for a RAW, its bytes
    private var source: Bitmap? = null
    private var sourceRaw: java.nio.ByteBuffer? = null
    private var sourceName = ""
    private var sourceUri: Uri? = null
    /** The develop's long edge: the preview's, or the detail's while zoomed in. */
    private var edge = PREVIEW_EDGE

    /** The settings being tuned: the camera's, changed here only until applied. */
    private val local = HashMap<String, Float>()
    private val values = object : SettingValues {
        override fun get(key: String) = local[key] ?: store[key]
        override fun set(key: String, value: Float) {
            if (get(key) == value) return
            local[key] = value
            changed()
        }
        override fun resetAll() {
            for (s in Settings.all) if (engineParam(s)) local[s.key] = s.default
            refreshControls()
            changed()
        }
    }

    private lateinit var compare: CompareView
    private lateinit var emptyState: View
    private lateinit var recipeLine: TextView
    private lateinit var controls: FrameLayout
    private lateinit var applyButton: TextView
    private var mode = 0
    private val standardRefreshers = mutableListOf<() -> Unit>()
    private var proPanel: SettingsPanel? = null
    private val proOpen = mutableSetOf<String>()

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { load(it) } }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { load(it) } }

    private val dp get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * dp).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        mode = getSharedPreferences("tune_bench", MODE_PRIVATE).getInt("mode", 0).coerceIn(0, 1)
        build()
        intent.getStringExtra(EXTRA_DNG)?.let { path -> loadFile(java.io.File(path)) }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.execute { runCatching { engine?.close() }; engine = null }
        worker.shutdown()
    }

    // ---- the screen ----------------------------------------------------------------------

    private fun build() {
        val titleFace = org.photoall.film.gallery.LightboxLook.titleFace(Style.medium)
        val labelFace = org.photoall.film.gallery.LightboxLook.labelFace(Style.regular)
        val back = ImageView(this).apply {
            contentDescription = "返回"
            setImageDrawable(Icon(Icon.Kind.BACK, Style.TEXT))
            setPadding(dp(13f), dp(13f), dp(13f), dp(13f))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "调参台"; textSize = 21f; typeface = titleFace; setTextColor(Style.TEXT)
        }
        val swap = TextView(this).apply {
            text = "换素材"; textSize = 14f; typeface = labelFace; setTextColor(Style.TEXT_2)
            gravity = Gravity.CENTER; setPadding(dp(14f), 0, dp(14f), 0)
            Style.pressable(this)
            setOnClickListener { chooseSource() }
        }
        val save = TextView(this).apply {
            text = "存为预设"; textSize = 14f; typeface = labelFace; setTextColor(Style.ACCENT)
            gravity = Gravity.CENTER; setPadding(dp(12f), 0, dp(14f), 0)
            Style.pressable(this)
            setOnClickListener { savePreset() }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(swap, LinearLayout.LayoutParams(-2, dp(44f)))
            addView(save, LinearLayout.LayoutParams(-2, dp(44f)))
        }

        // the picture, in the theme's frame
        compare = CompareView(this).apply {
            this.labelFace = labelFace
            // looked at closely: developed at a size near the photo's own, so grain and detail
            // are what the shot will have
            onZoom = { z -> setEdge(if (z >= 1.8f) DETAIL_EDGE else if (z <= 1.2f) PREVIEW_EDGE else edge) }
        }
        emptyState = emptyState(labelFace)
        val frame = FrameLayout(this).apply {
            background = LightboxLook.housing(dp) ?: PlainFrame(dp)
            val pad = dp(if (Skin.active) 10f else 6f)
            setPadding(pad, pad, pad + if (Skin.kind == Skin.ANIME) dp(4f) else 0, pad + if (Skin.kind == Skin.ANIME) dp(4f) else 0)
            addView(compare, FrameLayout.LayoutParams(-1, -1))
            addView(emptyState, FrameLayout.LayoutParams(-1, -1))
        }
        recipeLine = TextView(this).apply {
            textSize = 12f; typeface = labelFace; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER_VERTICAL
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val modes = Segmented(this, listOf("标准", "Pro")).apply {
            onPick = { m ->
                if (m != mode) {
                    mode = m
                    getSharedPreferences("tune_bench", MODE_PRIVATE).edit().putInt("mode", m).apply()
                    showControls()
                }
            }
        }
        controls = FrameLayout(this)

        // the bottom: back to the camera's settings, every default, apply
        fun key(textValue: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = textValue; textSize = 14f; gravity = Gravity.CENTER; maxLines = 1
            typeface = if (primary) Style.medium else labelFace
            setTextColor(if (primary) Style.ON_ACCENT else Style.TEXT)
            background = if (primary) GradientDrawable().apply { cornerRadius = dp(22f).toFloat(); setColor(Style.ACCENT) }
                else LightboxLook.key(dp, 22f) ?: GradientDrawable().apply {
                    cornerRadius = dp(22f).toFloat(); setColor(Style.SURFACE); setStroke(dp(1f), Style.HAIRLINE)
                }
            Style.pressable(this)
            setOnClickListener { onClick() }
        }
        applyButton = key("应用到拍摄", primary = true) { applyToCamera() }
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(10f))
            addView(key("还原", false) { local.clear(); refreshControls(); changed() }, LinearLayout.LayoutParams(0, dp(44f), 1f))
            addView(key("恢复默认", false) { values.resetAll() }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(8f) })
            addView(applyButton, LinearLayout.LayoutParams(0, dp(44f), 1.5f).apply { marginStart = dp(8f) })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = org.photoall.film.ui.Style.ground()
            addView(top, LinearLayout.LayoutParams(-1, dp(52f)))
            // the picture a fixed share of the screen, whatever the display size: the settings
            // below keep the rest
            addView(frame, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.33f).roundToInt())
                .apply { setMargins(dp(14f), dp(2f), dp(14f), 0) })
            addView(LinearLayout(this@TuneActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(recipeLine, LinearLayout.LayoutParams(0, -2, 1f))
                addView(modes, LinearLayout.LayoutParams(dp(150f), dp(34f)).apply { marginStart = dp(10f) })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16f), dp(8f), dp(16f), dp(4f)) })
            addView(controls, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(Style.hairline(this@TuneActivity), LinearLayout.LayoutParams(-1, 1))
            addView(bottom, LinearLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        modes.select(mode, animate = false)
        showControls()
        syncRecipe()
        syncApply()
    }

    /** Before a picture: how to get one, in the frame (scrolls when a large display size makes it tall). */
    private fun emptyState(face: android.graphics.Typeface): View = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(LinearLayout(this@TuneActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            addView(TextView(this@TuneActivity).apply {
                text = "选一张照片，边调边看"; textSize = 15f; typeface = Style.medium; setTextColor(Style.TEXT); gravity = Gravity.CENTER
            })
            addView(TextView(this@TuneActivity).apply {
                text = "左边原图，右边是冲洗效果，拖动中间的线对比，双指可放大"
                textSize = 12f; typeface = face; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER
                setPadding(0, dp(4f), 0, dp(8f))
            })
            for ((label, action) in sourceChoices()) addView(TextView(this@TuneActivity).apply {
                text = label; textSize = 14f; typeface = Style.medium; setTextColor(Style.TEXT); gravity = Gravity.CENTER
                maxLines = 1; setPadding(dp(14f), dp(8f), dp(14f), dp(8f)); minHeight = dp(38f)
                background = LightboxLook.key(dp, 19f) ?: GradientDrawable().apply {
                    cornerRadius = dp(19f).toFloat(); setColor(Style.SURFACE_2); setStroke(dp(1f), Style.HAIRLINE)
                }
                Style.pressable(this)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f); marginStart = dp(12f); marginEnd = dp(12f) })
        }, FrameLayout.LayoutParams(-1, -2))
    }

    private fun sourceChoices(): List<Pair<String, () -> Unit>> = listOf(
        "相册选图" to { runCatching { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }; Unit },
        "选 RAW 文件" to { runCatching { pickFile.launch(arrayOf("image/*", "application/octet-stream")) }; Unit },
        "主摄拍一张 RAW" to { setResult(RESULT_SHOOT); finish() },
    )

    private fun chooseSource() {
        val choices = sourceChoices()
        AlertDialog.Builder(this).setTitle("换素材")
            .setItems(choices.map { it.first }.toTypedArray()) { _, i -> choices[i].second() }
            .show()
    }

    // ---- the controls ----------------------------------------------------------------------

    private fun showControls() {
        controls.removeAllViews()
        standardRefreshers.clear()
        proPanel = null
        if (mode == 0) controls.addView(ScrollView(this).apply { addView(standardControls()) }, FrameLayout.LayoutParams(-1, -1))
        else SettingsPanel(this, values, onBench = null, onClose = {}, title = "", only = ::engineParam,
            open = proOpen, tabbed = true, groupOrder = ORDER, showHeader = false).also {
            proPanel = it
            controls.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun refreshControls() {
        standardRefreshers.forEach { it() }
        proPanel?.refresh()
    }

    /** One plain-words setting: what it is, what it does, what each end of it looks like. */
    private class Knob(val key: String, val title: String, val what: String, val low: String, val high: String,
                       val min: Float, val max: Float, val steps: Int = 200, val format: String = "%.2f")

    private val knobs = listOf(
        Knob("grain amt", "颗粒", "胶片的颗粒感。1 是原版胶片的颗粒，0 没有颗粒", "细腻", "粗糙", 0f, 2f),
        Knob("size", "颗粒大小", "颗粒一粒粒的大小", "细密", "粗大", 0.3f, 2f),
        Knob("ev film", "胶片曝光", "冲洗时给底片的光：整体变亮变暗，高光和暗部的层次也跟着变", "暗", "亮", -2f, 2f, format = "%+.1f"),
        Knob("g film", "反差", "明暗之间的对比", "柔和", "硬朗", 0.6f, 1.4f),
        Knob("cp amt", "色彩浓度", "胶片各层之间互相抑制，越强颜色越纯、越浓", "淡雅", "浓郁", 0f, 2f),
        Knob("hal amt", "光晕", "亮光边缘泛出的红晕，拍灯光和逆光最明显", "无", "明显", 0f, 4f),
        Knob("stage scan sharp", "锐度", "扫描底片时的锐化", "柔和", "清晰", 0f, 1.5f),
        Knob(Settings.PUSH, "推拉冲", "推冲：反差更大、颗粒更粗；拉冲：更柔和、颗粒更细", "拉冲", "推冲", -2f, 2f, steps = 4, format = "%+.0f 档"),
        Knob(Settings.CHROMA_NR, "降噪", "只在噪点多的照片上起作用（暗光、高感光度），干净的照片自动少降", "留细节", "更干净", 0f, 1f),
    )

    private fun standardControls(): View {
        val face = LightboxLook.labelFace(Style.regular)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), dp(6f), dp(14f), dp(18f)) }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(8f))
            background = Style.surface(this@TuneActivity, fill = Style.SURFACE, radiusDp = if (Skin.kind == Skin.ANIME) 16f else 12f, stroke = Style.HAIRLINE)
        }
        for (k in knobs) {
            if (k.key == "stage scan sharp") list.addView(filterCard(face), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
            list.addView(card().apply { knob(this, k, face) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        }
        list.addView(card().apply { formatRow(this, face) }, LinearLayout.LayoutParams(-1, -2))
        return list
    }

    private fun knob(card: LinearLayout, k: Knob, face: android.graphics.Typeface) {
        val value = TextView(this).apply { textSize = 13f; setTextColor(Style.TEXT_2); Style.numeric(this) }
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@TuneActivity).apply {
                text = k.title; textSize = 15.5f; typeface = Style.medium; setTextColor(Style.TEXT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value)
            // hold to put it back to its default
            setOnLongClickListener { set(k, default(k)); true }
        })
        card.addView(TextView(this).apply {
            text = k.what; textSize = 12f; typeface = face; setTextColor(Style.TEXT_2); setPadding(0, dp(2f), 0, 0)
        })
        val bar = SeekBar(this).apply {
            max = k.steps
            progressTintList = android.content.res.ColorStateList.valueOf(Style.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Style.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Style.TEXT_3)
        }
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(36f)))
        card.addView(LinearLayout(this).apply {
            setPadding(dp(14f), 0, dp(14f), 0)
            addView(TextView(this@TuneActivity).apply { text = k.low; textSize = 11.5f; typeface = face; setTextColor(Style.TEXT_3) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@TuneActivity).apply { text = k.high; textSize = 11.5f; typeface = face; setTextColor(Style.TEXT_3); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        fun current() = if (k.key == Settings.CHROMA_NR) values[Settings.CHROMA_NR] else values[k.key]
        fun show() {
            val v = current()
            bar.progress = (((v - k.min) / (k.max - k.min)) * k.steps).roundToInt().coerceIn(0, k.steps)
            value.text = if (v == default(k)) "默认" else k.format.format(Locale.US, v)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                set(k, k.min + (k.max - k.min) * p / k.steps)
                value.text = k.format.format(Locale.US, current())
            }
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) = show()
        })
        Style.doubleTapReset(bar) { set(k, default(k)); show() }
        show()
        standardRefreshers += ::show
    }

    private fun default(k: Knob) = Settings.all.firstOrNull { it.key == k.key }?.default ?: 0f

    private fun set(k: Knob, v: Float) {
        if (k.key == Settings.CHROMA_NR) {
            // one 降噪 for both: the colour denoise, and the RAW denoise in the same proportion
            // (0.3 of it, the default pair at 1)
            values[Settings.CHROMA_NR] = v
            values[Settings.RAW_DENOISE] = 0.3f * v
        } else values[k.key] = v
        if (k.steps <= 8) refreshControls()
    }

    /** 柔光滤镜: none or one of four, and how strong. */
    private fun filterCard(face: android.graphics.Typeface) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14f), dp(10f), dp(14f), dp(8f))
        background = Style.surface(this@TuneActivity, fill = Style.SURFACE, radiusDp = if (Skin.kind == Skin.ANIME) 16f else 12f, stroke = Style.HAIRLINE)
        addView(TextView(this@TuneActivity).apply { text = "柔光滤镜"; textSize = 15.5f; typeface = Style.medium; setTextColor(Style.TEXT) })
        addView(TextView(this@TuneActivity).apply {
            text = "镜头前加一片柔光镜：亮处泛起一层光，人像和逆光更柔"; textSize = 12f; typeface = face; setTextColor(Style.TEXT_2)
            setPadding(0, dp(2f), 0, dp(6f))
        })
        val names = listOf("无", "柔焦", "黑柔", "微光", "泛光")
        addView(chips(names, { values["stage diffusion"].toInt() }) { i -> values["stage diffusion"] = i.toFloat(); refreshControls() })
        val strength = LinearLayout(this@TuneActivity).apply { orientation = LinearLayout.VERTICAL }
        knob(strength, Knob("stage diff amt", "滤镜强度", "这片柔光镜有多强", "淡", "浓", 0f, 1f), face)
        addView(strength, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        val show = { strength.visibility = if (values["stage diffusion"] >= 0.5f) View.VISIBLE else View.GONE }
        show(); standardRefreshers += show
    }

    /** 画幅: the negative's size (grain and halation at their size on it). */
    private fun formatRow(card: LinearLayout, face: android.graphics.Typeface) {
        card.addView(TextView(this).apply { text = "画幅"; textSize = 15.5f; typeface = Style.medium; setTextColor(Style.TEXT) })
        card.addView(TextView(this).apply {
            text = "底片越大，颗粒和光晕在照片上越细小"; textSize = 12f; typeface = face; setTextColor(Style.TEXT_2)
            setPadding(0, dp(2f), 0, dp(6f))
        })
        card.addView(chips(listOf("135", "半格", "6×4.5", "6×6", "6×7", "6×9"), { values[Settings.FORMAT].toInt() }) { i ->
            values[Settings.FORMAT] = i.toFloat(); refreshControls()
        })
    }

    private fun chips(names: List<String>, current: () -> Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this)
        val views = names.mapIndexed { i, n ->
            TextView(this).apply {
                text = n; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(12f), 0, dp(12f), 0)
                Style.pressable(this)
                setOnClickListener { onPick(i) }
            }.also { row.addView(it, LinearLayout.LayoutParams(-2, dp(32f)).apply { marginEnd = dp(6f) }) }
        }
        val paint = {
            val c = current()
            views.forEachIndexed { i, v ->
                val on = i == c
                v.setTextColor(if (on) Style.ON_ACCENT else Style.TEXT_2)
                v.typeface = if (on) Style.medium else Style.regular
                v.background = GradientDrawable().apply {
                    cornerRadius = dp(16f).toFloat()
                    if (on) setColor(Style.ACCENT) else { setColor(Style.SURFACE_2); setStroke(dp(1f), Style.HAIRLINE) }
                }
            }
        }
        paint(); standardRefreshers += paint
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    // ---- the picture ---------------------------------------------------------------------

    private fun load(uri: Uri) {
        compare.busy = true
        worker.execute {
            val raw = RawSource.read(contentResolver, uri)
            val bitmap = decode(uri, edge)
            main.post { sourceUri = uri; useSource(bitmap, raw, uri.lastPathSegment ?: "") }
        }
    }

    private fun loadFile(file: java.io.File) {
        compare.busy = true
        worker.execute {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            val raw = bytes?.let { java.nio.ByteBuffer.allocateDirect(it.size).apply { put(it); flip() } }
            main.post { sourceUri = null; useSource(null, raw, "主摄 RAW") }
        }
    }

    /** A picture to tune: a bitmap, a RAW, or both (a RAW the system can also show). */
    private fun useSource(bitmap: Bitmap?, raw: java.nio.ByteBuffer?, name: String) {
        if (bitmap == null && raw == null) {
            compare.busy = false
            Toast.makeText(this, "读不出这张照片", Toast.LENGTH_SHORT).show()
            return
        }
        source = bitmap
        sourceRaw = raw
        sourceName = name
        emptyState.visibility = View.GONE
        compare.original = bitmap
        compare.developed = null
        original = null
        changed(0L)
    }

    /** For a RAW: its neutral develop (no film), the "original" shown left of the divider. */
    private var original: Bitmap? = null
    private var ticket = 0
    private val develop = Runnable { developNow() }

    private fun changed(delay: Long = 220L) {
        syncRecipe()
        syncApply()
        if (source == null && sourceRaw == null) return
        main.removeCallbacks(develop)
        main.postDelayed(develop, delay)
    }

    private fun developNow() {
        val t = ++ticket
        compare.busy = true
        val params = values.filmParams()
        val raw = sourceRaw
        val bitmap = source
        val lut = store[Settings.LUT].toInt()
        val lutOn = store.flag(Settings.LUT_ON) && lut != 0 && LutLibrary.digicam(lut) == null
        val strength = store[Settings.LUT_STRENGTH]
        val chroma = values[Settings.CHROMA_NR]
        val rawNr = values[Settings.RAW_DENOISE]
        val size = edge
        worker.execute {
            if (t != ticket) return@execute
            val result = runCatching {
                val e = engine ?: FilmEngine.create(assets).also { engine = it }
                e.setCreativeLut(if (lutOn) LutLibrary.table(this, lut) else null, LutLibrary.size, if (lutOn) strength else 0f)
                if (raw != null) {
                    val options = DevelopOptions(maxLongEdge = size, matchKey = REFERENCE_EXPOSURE, seed = SEED,
                        displayP3 = false, chromaDenoise = chroma, rawDenoise = rawNr)
                    if (original == null) {
                        raw.rewind()
                        e.setCreativeLut(null, LutLibrary.size, 0f)
                        val plain = e.developDng(listOf(raw), params + mapOf("stage film" to 0f, "stage print" to 0f), options).bitmap
                        main.post { original = plain; compare.original = plain }
                        e.setCreativeLut(if (lutOn) LutLibrary.table(this, lut) else null, LutLibrary.size, if (lutOn) strength else 0f)
                    }
                    raw.rewind()
                    e.developDng(listOf(raw), params, options).bitmap
                } else e.develop(bitmap!!, params + mapOf("stage restore" to 1f, "stage display" to 1f, "stage meter" to 1f),
                    DevelopOptions(maxLongEdge = size, seed = SEED, displayP3 = false)).bitmap
            }
            main.post {
                if (t != ticket || isFinishing) return@post
                compare.busy = false
                result.onSuccess { compare.developed = it }.onFailure {
                    if (raw != null && bitmap != null) { sourceRaw = null; changed(0L) } // a RAW the engine cannot read: its picture
                    else Toast.makeText(this, "冲洗失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** A new develop size: the picture (and a RAW's plain develop) again at that size. */
    private fun setEdge(next: Int) {
        if (next == edge || (source == null && sourceRaw == null)) return
        edge = next
        val uri = sourceUri
        if (uri != null && source != null) worker.execute {
            val bitmap = decode(uri, next)
            main.post { if (bitmap != null && edge == next) { source = bitmap; if (sourceRaw == null) compare.original = bitmap } }
        }
        original = null
        changed(0L)
    }

    private fun decode(uri: Uri, edge: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val s = minOf(1f, edge.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * s).roundToInt()), maxOf(1, (info.size.height * s).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    }.getOrNull()

    private fun syncRecipe() {
        val film = org.photoall.film.ui.CanisterView.displayName(FilmEngine.filmStocks.getOrNull(values["film"].toInt()) ?: "")
        val paper = org.photoall.film.settings.LookFamilies.paperName(FilmEngine.papers.getOrNull(values["paper"].toInt()) ?: "")
        recipeLine.text = when {
            !values.flag(Settings.FILM_ON) -> "当前：直出（胶片模拟关，在相机上开）"
            values.flag(Settings.PRINT_ON) -> "当前：$film · $paper"
            else -> "当前：$film · 直接扫描"
        }
    }

    /** 应用到拍摄 lit while something here differs from the camera's settings. */
    private fun syncApply() {
        val differs = local.any { (k, v) -> store[k] != v }
        applyButton.text = if (differs) "应用到拍摄" else "与相机一致"
        applyButton.alpha = if (differs) 1f else 0.55f
    }

    /**
     * 存为预设: the parameters on the bench now (tried or applied) kept under a name, as an
     * engine preset: the camera's 拍摄设置 switches between them in one tap.
     */
    private fun savePreset() {
        val count = org.photoall.film.settings.Presets.engine(this).size
        val field = android.widget.EditText(this).apply {
            setText("调参 ${count + 1}"); selectAll()
            setSingleLine(); textSize = 16f
            setTextColor(Style.TEXT); setHintTextColor(Style.TEXT_3)
            hint = "给这套参数起个名字"
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22f), dp(8f), dp(22f), 0)
            addView(field, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("存为引擎预设")
            .setMessage("存下现在调参台上的全部引擎参数（含降噪），不含胶卷与相纸。之后在拍摄设置的「引擎参数」里一键切换。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = field.text.toString().trim().ifEmpty { "调参 ${count + 1}" }.take(16)
                org.photoall.film.settings.Presets.add(this, name, values, org.photoall.film.settings.Presets.ENGINE_PARTS, remember = false)
                Toast.makeText(this, "已存为预设「$name」：在拍摄设置的「引擎参数」里切换", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            field.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    /** The tuned settings become the camera's. */
    private fun applyToCamera() {
        val changedKeys = local.filter { (k, v) -> store[k] != v }
        for ((k, v) in changedKeys) store[k] = v
        local.clear()
        refreshControls()
        syncApply()
        Toast.makeText(this, if (changedKeys.isEmpty()) "参数和相机一致，没有要应用的" else "已应用 ${changedKeys.size} 项：接下来拍的照片按这套参数冲洗", Toast.LENGTH_LONG).show()
    }

    /** The frame round the picture in the plain themes: the panel colour, a hairline, crop marks in the accent. */
    private class PlainFrame(private val d: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun draw(c: Canvas) {
            r.set(bounds)
            p.style = Paint.Style.FILL; p.color = Style.SURFACE
            c.drawRoundRect(r, 10 * d, 10 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d; p.color = Style.HAIRLINE
            r.inset(0.5f * d, 0.5f * d); c.drawRoundRect(r, 10 * d, 10 * d, p)
            // crop marks at the corners, as on a contact sheet
            p.color = Style.ACCENT; p.strokeWidth = 1.6f * d
            val m = 3 * d; val l = 12 * d
            for ((x, y, sx, sy) in listOf(
                floatArrayOf(r.left + m, r.top + m, 1f, 1f), floatArrayOf(r.right - m, r.top + m, -1f, 1f),
                floatArrayOf(r.left + m, r.bottom - m, 1f, -1f), floatArrayOf(r.right - m, r.bottom - m, -1f, -1f))) {
                c.drawLine(x, y, x + sx * l, y, p); c.drawLine(x, y, x, y + sy * l, p)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val EXTRA_DNG = "tune_dng"
        /** The bench asks the camera for a RAW shot (returned by the camera to [EXTRA_DNG]). */
        const val RESULT_SHOOT = 2
        private const val PREVIEW_EDGE = 1280
        private const val DETAIL_EDGE = 3072
        private const val SEED = 7 // the same grain at every change: only the settings move

        /** Every engine parameter (film and paper are picked on the camera itself). */
        fun engineParam(setting: Setting) = setting.group.isNotEmpty() && setting.key != "film" && setting.key != "paper" &&
            (!setting.key.startsWith("app.") ||
                setting.key in setOf(Settings.BALANCE, Settings.PUSH, Settings.FORMAT, Settings.RAW_DENOISE, Settings.CHROMA_NR))

        /** The look first, the darkroom's finer steps after, the RAW denoise last. */
        val ORDER = listOf("颗粒", "胶片", "光学", "光晕", "耦合剂", "冲印", "放大机滤色", "预闪", "扫描", "尺度", "光源", "相机")
    }
}
