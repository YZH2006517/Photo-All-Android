package org.photoall.film.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import org.photoall.film.engine.DevelopOptions
import org.photoall.film.engine.Digicam
import org.photoall.film.engine.FilmEngine
import org.photoall.film.settings.LookFamilies
import org.photoall.film.settings.LutLibrary
import org.photoall.film.settings.Setting
import org.photoall.film.settings.SettingValues
import org.photoall.film.settings.Settings
import org.photoall.film.settings.SettingsPanel
import org.photoall.film.settings.SettingsStore
import org.photoall.film.ui.CanisterView
import org.photoall.film.ui.Haptics
import org.photoall.film.ui.Icon
import org.photoall.film.ui.LookPicker
import org.photoall.film.ui.RulerDial
import org.photoall.film.ui.Segmented
import org.photoall.film.ui.Style
import org.photoall.film.ui.Swatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Developing the user's own pictures: the system photo picker first, then the picture over
 * a dark ground with the camera's look strip on it (film, paper or LUT, by family), the
 * exposure and the LUT's strength below, and one button that develops every picture chosen
 * with that recipe into the album. The preview is the engine's own develop, small.
 */
class ImportActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: FilmEngine? = null
    private lateinit var store: SettingsStore
    private lateinit var haptics: Haptics

    private var uris: List<Uri> = emptyList()
    private var current = 0

    // the recipe, starting from the camera's (changing it here leaves the camera's alone)
    private var filmOn = true
    private var film = 0
    private var printOn = true
    private var paper = 0
    private var lut = 0
    private var lutStrength = 1f
    private var evThirds = 0
    private var page = 0
    // fine adjustments (微调): white balance, how much of the phone's rendering is taken
    // back off, and the EDR headroom; kept between imports
    private val tunePrefs by lazy { getSharedPreferences("import_tune", MODE_PRIVATE) }
    private var temp = 0f
    private var tint = 0f
    private var restore = 1f
    private var edr = 1.5f
    private lateinit var tuneBox: LinearLayout
    private lateinit var tuneToggle: TextView
    /** 测光: the develop's own exposure judgement (a RAW metered to mid-grey, a picture's scene restore exposure); off, the picture's own brightness. */
    private var meter = true
    private lateinit var meterChip: TextView
    /** The pictures just developed, each to the border page in turn (see [FrameActivity.BLANK]). */
    private val toFrame = java.util.Collections.synchronizedList(mutableListOf<Intent>())
    private var framed = false
    private lateinit var frameChip: TextView
    private lateinit var colourChips: List<TextView>
    private lateinit var scope: ScopeView

    private lateinit var preview: ImageView
    private lateinit var busy: TextView
    private lateinit var thumbs: LinearLayout
    private lateinit var thumbScroll: HorizontalScrollView
    private lateinit var picker: LookPicker
    private lateinit var groupTabs: LinearLayout
    private lateinit var groupScroll: HorizontalScrollView
    private lateinit var pages: Segmented
    private lateinit var strengthRow: View
    private lateinit var strength: SeekBar
    private lateinit var strengthValue: TextView
    private lateinit var evDial: RulerDial
    private lateinit var evValue: TextView
    private lateinit var developButton: TextView
    private lateinit var adjustButton: TextView
    private lateinit var root: LinearLayout
    private lateinit var pickerStrip: LinearLayout
    private lateinit var mainPanel: LinearLayout
    private var paramsPanel: SettingsPanel? = null
    private var bottomInset = 0

    /**
     * The engine's parameters for this import: the camera's to begin with, changed here
     * only (the camera keeps its own). Film and paper come from the strip.
     */
    private val local = HashMap<String, Float>()
    private val values = object : SettingValues {
        override fun get(key: String) = local[key] ?: store[key]
        override fun set(key: String, value: Float) {
            if (local[key] == value) return
            local[key] = value
            syncPanel()
            schedulePreview(250L)
        }
        override fun resetAll() {
            for (setting in Settings.all) if (engineParam(setting)) local[setting.key] = setting.default
            paramsPanel?.refresh()
            syncPanel()
            schedulePreview(60L)
        }
    }
    /** The parameters as they were when the screen opened: "adjusted" is a difference from them. */
    private val initial = HashMap<String, Float>()
    private val openSections = mutableSetOf<String>()

    private val closeParams = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideParams()
    }
    private lateinit var recipeLine: TextView

    private var source: Bitmap? = null      // the picture shown, small
    /** The picture's RAW (a DNG from the phone's own camera), developed as RAW; null for a JPEG/HEIC. */
    private var sourceDng: java.nio.ByteBuffer? = null
    private var developedPreview: Bitmap? = null
    private var request = 0
    private var developing = false
    private var groups: List<Pair<String, Int>> = emptyList()
    private var groupOf: (Int) -> String? = { null }
    private var shownGroup: String? = null

    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX)) { chosen ->
        if (chosen.isNotEmpty()) choose(chosen)
        else if (uris.isEmpty()) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        LutLibrary.load(this)
        haptics = Haptics(window.decorView) { store[Settings.HAPTICS].toInt() }
        filmOn = store.flag(Settings.FILM_ON)
        film = store["film"].toInt().coerceIn(0, FilmEngine.filmStocks.lastIndex)
        printOn = store.flag(Settings.PRINT_ON)
        paper = store["paper"].toInt().coerceIn(0, FilmEngine.papers.lastIndex)
        lut = if (store.flag(Settings.LUT_ON)) store[Settings.LUT].toInt().coerceIn(0, LutLibrary.count - 1) else 0
        lutStrength = store[Settings.LUT_STRENGTH].takeIf { it > 0f } ?: 1f
        meter = tunePrefs.getBoolean("meter", true)
        framed = tunePrefs.getBoolean("frame", false)
        // white balance belongs to one picture: each import starts neutral (it was kept, and a
        // cold setting from an earlier photo turned the next ones cold without a word)
        temp = 0f
        tint = 0f
        restore = tunePrefs.getFloat("restore", 1f)
        edr = if (Edr.supported) tunePrefs.getFloat("edr", 1.5f) else 0f
        // an HDR window while the preview has a gain map (Edr.fitWindow): its highlights shown,
        // and the original (no gain map) not dimmed when held up for comparison
        for (setting in Settings.all) if (engineParam(setting)) initial[setting.key] = store[setting.key]
        onBackPressedDispatcher.addCallback(this, closeParams)
        build()
        @Suppress("DEPRECATION")
        val saved = savedInstanceState?.getParcelableArrayList<Uri>(URIS)
        if (!saved.isNullOrEmpty()) choose(saved) else pickPictures()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(URIS, ArrayList(uris))
    }

    override fun onDestroy() {
        worker.later { engine?.close(); engine = null }
        worker.shutdown()
        super.onDestroy()
    }

    private fun pickPictures() = runCatching {
        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }.onFailure { busy.text = "打不开相册选择器"; busy.visibility = View.VISIBLE }

    private fun engine(): FilmEngine = engine ?: FilmEngine.create(assets).also { engine = it }

    // ---- the screen ------------------------------------------------------------------------

    private fun build() {
        val back = ImageView(this).apply {
            contentDescription = "返回"
            setImageDrawable(Icon(Icon.Kind.BACK, LIGHT))
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "导入冲洗"
            textSize = 17f
            typeface = Style.medium
            setTextColor(LIGHT)
        }
        val again = TextView(this).apply {
            text = "换照片"
            textSize = 14f
            setTextColor(LIGHT)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { if (!developing) pickPictures() }
        }
        // the histogram / waveform sits small in the title bar, off the picture
        scope = ScopeView(this, compact = true).apply {
            mode = ScopeView.Mode.entries.getOrElse(tunePrefs.getInt("scope", 0)) { ScopeView.Mode.HISTOGRAM }
            onModeChanged = { m -> haptics.play(Haptics.Event.SEGMENT); tunePrefs.edit().putInt("scope", m.ordinal).apply() }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(scope, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
            addView(again, LinearLayout.LayoutParams(-2, dp(48)))
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // hold to see the picture as it came
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> postDelayed(showOriginal, 180)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(showOriginal)
                        developedPreview?.let { setImageBitmap(it); Edr.fitWindow(window, it) }
                    }
                }
                true
            }
        }
        busy = TextView(this).apply {
            textSize = 12f
            setTextColor(LIGHT)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x99000000.toInt()) }
            visibility = View.GONE
        }
        picker = LookPicker(this).apply {
            onHover = { i -> haptics.play(Haptics.Event.TICK); applyPick(i, settled = false); markGroup(i) }
            onSettle = { i -> applyPick(i, settled = true) }
        }
        groupTabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        groupScroll = HorizontalScrollView(this).apply { isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength((28 * resources.displayMetrics.density).toInt());
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(groupTabs, FrameLayout.LayoutParams(-2, -1))
        }
        val strip = LinearLayout(this).apply {
            pickerStrip = this
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x8c000000.toInt(), 0xcc000000.toInt()))
            setPadding(0, dp(18), 0, dp(6))
            addView(LookPicker.Marker(this@ImportActivity), LinearLayout.LayoutParams(-1, dp(4)))
            addView(picker, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(2) })
            addView(groupScroll, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(2) })
        }
        val stage = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(STRIP) })
            addView(busy, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
            addView(strip, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        thumbs = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
        thumbScroll = HorizontalScrollView(this).apply { isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength((28 * resources.displayMetrics.density).toInt());
            isHorizontalScrollBarEnabled = false
            addView(thumbs)
            visibility = View.GONE
        }

        // the light panel: film / paper / LUT, exposure, the LUT's strength, develop
        pages = Segmented(this, listOf("胶卷", "相纸", "LUT")).apply {
            onPick = { p -> if (p != page) { page = p; haptics.play(Haptics.Event.SEGMENT); fillPicker(animate = true); syncPanel() } }
        }
        recipeLine = TextView(this).apply {
            textSize = 12f
            setTextColor(Style.TEXT_2)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        evValue = TextView(this).apply { textSize = 13f; setTextColor(Style.TEXT); gravity = Gravity.END; maxLines = 1; Style.numeric(this) }
        evDial = RulerDial(this).apply {
            range = -9..9
            onStep = { s ->
                val next = (evThirds + s).coerceIn(-9, 9)
                if (next != evThirds) { evThirds = next; thirds = next; haptics.play(Haptics.Event.TICK); syncPanel(); schedulePreview() }
            }
            onReset = { evThirds = 0; thirds = 0; syncPanel(); schedulePreview() }
        }
        strengthValue = TextView(this).apply { textSize = 13f; setTextColor(Style.TEXT); gravity = Gravity.END; maxLines = 1; Style.numeric(this) }
        strength = SeekBar(this).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Style.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Style.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    lutStrength = value / 100f
                    syncPanel()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) = schedulePreview()
            })
        }
        strengthRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("LUT 强度"), LinearLayout.LayoutParams(dp(SIDE), -2))
            addView(strength, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(strengthValue, LinearLayout.LayoutParams(dp(SIDE), -2))
        }
        buildTune()
        // one press develops with the recipe as it stands; the other opens every engine parameter
        developButton = TextView(this).apply {
            textSize = 15f
            typeface = Style.medium
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(Style.ACCENT) }
            Style.pressable(this)
            setOnClickListener { developAll() }
        }
        adjustButton = TextView(this).apply {
            text = "调整全部参数"
            textSize = 15f
            typeface = Style.medium
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Style.TEXT)
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat(); setColor(Style.SURFACE); setStroke(dp(1), Style.TEXT_3)
            }
            Style.pressable(this)
            setOnClickListener { if (!developing) showParams() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(pages, LinearLayout.LayoutParams(-1, dp(40)))
            addView(LinearLayout(this@ImportActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(recipeLine, LinearLayout.LayoutParams(0, -2, 1f))
                addView(tuneToggle, LinearLayout.LayoutParams(-2, dp(30)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            // one row: 胶片色彩 on the left, 测光 and 边框 on the right; the chips a little narrower so
            // all of it fits a 360 dp screen
            addView(LinearLayout(this@ImportActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("胶片色彩").apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                colourChips = listOf("校色" to 1f, "原色" to 0f).map { (name, value) ->
                    chip(name) {
                        if (values[Settings.BALANCE] != value) { haptics.play(Haptics.Event.SEGMENT); values[Settings.BALANCE] = value }
                    }.also {
                        it.setPadding(dp(10), 0, dp(10), 0)
                        addView(it, LinearLayout.LayoutParams(-2, dp(28)).apply { marginEnd = dp(5) })
                    }
                }
                addView(View(this@ImportActivity), LinearLayout.LayoutParams(0, 1, 1f))
                meterChip = chip("测光") {
                    meter = !meter
                    tunePrefs.edit().putBoolean("meter", meter).apply()
                    haptics.play(Haptics.Event.SEGMENT)
                    syncPanel(); schedulePreview()
                }
                meterChip.setPadding(dp(10), 0, dp(10), 0)
                meterChip.maxLines = 1
                addView(meterChip, LinearLayout.LayoutParams(-2, dp(28)))
                // 边框: on, the border page follows the develop; off, straight back
                frameChip = chip("边框") {
                    framed = !framed
                    tunePrefs.edit().putBoolean("frame", framed).apply()
                    haptics.play(Haptics.Event.SEGMENT)
                    syncPanel()
                }
                frameChip.setPadding(dp(10), 0, dp(10), 0)
                frameChip.maxLines = 1
                addView(frameChip, LinearLayout.LayoutParams(-2, dp(28)).apply { marginStart = dp(5) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(tuneBox, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(this@ImportActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                // label and value equally wide: the ruler, and its 0, sit in the middle of the
                // screen, over the gap between the two buttons
                addView(label("曝光"), LinearLayout.LayoutParams(dp(SIDE), -2))
                addView(evDial, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(evValue, LinearLayout.LayoutParams(dp(SIDE), -2))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(strengthRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(LinearLayout(this@ImportActivity).apply {
                addView(developButton, LinearLayout.LayoutParams(0, -1, 1f))
                addView(adjustButton, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        }
        mainPanel = panel
        root = LinearLayout(this)
        setContentView(root.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DARK)
            addView(top, LinearLayout.LayoutParams(-1, dp(52)))
            addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(thumbScroll, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(6); bottomMargin = dp(6) })
            addView(panel, LinearLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, 0)
                panel.setPadding(dp(16), dp(12), dp(16), dp(12) + bars.bottom)
                bottomInset = bars.bottom
                paramsPanel?.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        })
        window.statusBarColor = DARK
        window.decorView.post { window.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) }
        pages.select(page, animate = false)
        fillPicker(animate = false)
        syncPanel()
    }

    /** A small rounded choice under the recipe line. */
    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Style.medium
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        Style.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun styleChip(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Style.ON_ACCENT else Style.TEXT_2)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (on) setColor(Style.ACCENT) else { setColor(Style.SURFACE); setStroke(dp(1), Style.TEXT_3) }
        }
    }

    /** 微调: four sliders under the recipe line, folded away until asked for. */
    private fun buildTune() {
        tuneToggle = TextView(this).apply {
            textSize = 12f
            typeface = Style.medium
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(Style.TEXT)
            background = GradientDrawable().apply { cornerRadius = dp(15).toFloat(); setColor(Style.SURFACE); setStroke(dp(1), Style.TEXT_3) }
            Style.pressable(this)
            setOnClickListener {
                haptics.play(Haptics.Event.SEGMENT)
                tuneBox.visibility = if (tuneBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                syncTune()
            }
        }
        tuneBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(4), 0, 0) }
        tuneBox.addView(tuneRow("色温", -100, 100, { (temp * 100).roundToInt() }, { v -> temp = v / 100f },
            { v -> if (v == 0) "±0" else (if (v > 0) "暖 " else "冷 ") + kotlin.math.abs(v) }))
        tuneBox.addView(tuneRow("色调", -100, 100, { (tint * 100).roundToInt() }, { v -> tint = v / 100f },
            { v -> if (v == 0) "±0" else (if (v > 0) "绿 " else "品 ") + kotlin.math.abs(v) }))
        tuneBox.addView(tuneRow("场景还原", 0, 100, { (restore * 100).roundToInt() }, { v -> restore = v / 100f },
            { v -> "$v%" }))
        if (Edr.supported) tuneBox.addView(tuneRow("EDR 高光", 0, 25, { (edr * 10).roundToInt() }, { v -> edr = v / 10f },
            { v -> if (v == 0) "关" else "+%.1f 档".format(Locale.US, v / 10f) }))
        syncTune()
    }

    /** One slider: label, bar, value; a double tap on the label puts it back to its default. */
    private fun tuneRow(name: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit, show: (Int) -> String): View {
        val value = TextView(this).apply { textSize = 13f; setTextColor(Style.TEXT); gravity = Gravity.END; maxLines = 1; Style.numeric(this) }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = get() - min
            progressTintList = android.content.res.ColorStateList.valueOf(Style.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Style.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = show(p + min)
                    if (!fromUser) return
                    set(p + min)
                    syncTune()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { saveTune(); syncPanel(); schedulePreview() }
            })
        }
        value.text = show(get())
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(name), LinearLayout.LayoutParams(dp(SIDE), -2))
            addView(bar, LinearLayout.LayoutParams(0, dp(34), 1f))
            addView(value, LinearLayout.LayoutParams(dp(SIDE), -2))
        }
    }

    private fun syncTune() {
        val changed = temp != 0f || tint != 0f || restore != 1f || edr != (if (Edr.supported) 1.5f else 0f)
        tuneToggle.text = (if (tuneBox.visibility == View.VISIBLE) "收起" else "微调") + (if (changed) " ·" else "")
    }

    private fun saveTune() = tunePrefs.edit().remove("temp").remove("tint")
        .putFloat("restore", restore).putFloat("edr", edr).apply()

    private val showOriginal = Runnable { source?.let { preview.setImageBitmap(it); Edr.fitWindow(window, it) } }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        maxLines = 1
        textSize = 13f
        setTextColor(Style.TEXT_2)
    }

    /** The pictures chosen: thumbnails to switch between, the first one previewed. */
    private fun choose(chosen: List<Uri>) {
        uris = chosen
        current = 0
        thumbs.removeAllViews()
        thumbScroll.visibility = if (uris.size > 1) View.VISIBLE else View.GONE
        for ((i, uri) in uris.withIndex()) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xff2a2826.toInt())
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                }
                setOnClickListener { if (i != current) { current = i; markThumb(); loadCurrent() } }
            }
            thumbs.addView(thumb, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) })
            if (uris.size > 1) worker.later {
                val small = decode(uri, 160)
                runOnUiThread { thumb.setImageBitmap(small) }
            }
        }
        markThumb()
        syncPanel()
        loadCurrent()
    }

    private fun markThumb() {
        for (i in 0 until thumbs.childCount) {
            val v = thumbs.getChildAt(i)
            v.alpha = if (i == current) 1f else 0.5f
            v.foreground = if (i == current) GradientDrawable().apply {
                cornerRadius = dp(6).toFloat(); setStroke(dp(2), Style.ACCENT)
            } else null
        }
    }

    private fun loadCurrent() {
        val uri = uris.getOrNull(current) ?: return
        val ticket = ++request
        busy.text = "读取中…"
        busy.visibility = View.VISIBLE
        worker.later {
            val small = decode(uri, PREVIEW_EDGE)
            val dng = readDng(uri)
            runOnUiThread {
                if (ticket != request) return@runOnUiThread
                if (small == null) { busy.text = "读不出这张照片"; return@runOnUiThread }
                source = small
                sourceDng = dng
                developedPreview = null
                preview.setImageBitmap(small)
                Edr.fitWindow(window, small)
                schedulePreview()
            }
        }
    }

    // ---- the look strip ----------------------------------------------------------------------

    private fun fillPicker(animate: Boolean) {
        val (items, selected) = when (page) {
            0 -> (listOf(LookPicker.Item("直出") { c -> CanisterView(c) }) + FilmEngine.filmStocks.map { stock ->
                LookPicker.Item(CanisterView.displayName(stock)) { c -> CanisterView(c).apply { this.stock = stock } }
            }) to (if (filmOn) film + 1 else 0)
            1 -> (listOf(LookPicker.Item("直接扫描") { c -> Swatch(c).apply { show(Swatch.Kind.SCAN) } }) + FilmEngine.papers.map { p ->
                LookPicker.Item(LookFamilies.paperName(p)) { c -> Swatch(c).apply { show(Swatch.Kind.PAPER, p) } }
            }) to (if (printOn) paper + 1 else 0)
            else -> (0 until LutLibrary.count).map { i ->
                LookPicker.Item(LutLibrary.name(i)) { c -> Swatch(c).apply { show(if (i == 0) Swatch.Kind.NONE else Swatch.Kind.LUT, LutLibrary.name(i)) } }
            } to lut
        }
        groupOf = when (page) {
            0 -> { i -> FilmEngine.filmStocks.getOrNull(i - 1)?.let { LookFamilies.film(it) } }
            1 -> { i -> FilmEngine.papers.getOrNull(i - 1)?.let { LookFamilies.paper(it) } }
            else -> { i -> if (i == 0) null else LutLibrary.group(i).ifEmpty { null } }
        }
        val firsts = LinkedHashMap<String, Int>()
        for (i in items.indices) groupOf(i)?.let { g -> if (g !in firsts) firsts[g] = i }
        groups = (if (page == 2) firsts.entries.sortedBy { if (it.key == LutLibrary.USER_GROUP) 0 else 1 } else firsts.entries)
            .map { it.key to it.value }
        shownGroup = null
        groupTabs.removeAllViews()
        groupScroll.scrollTo(0, 0)
        groupScroll.visibility = if (groups.size < 2) View.INVISIBLE else View.VISIBLE
        for ((g, first) in groups) groupTabs.addView(TextView(this).apply {
            text = g
            tag = g
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setPadding(dp(11), 0, dp(11), 0)
            styleTab(this, false)
            setOnClickListener { haptics.play(Haptics.Event.SEGMENT); picker.jumpTo(first) }
        }, LinearLayout.LayoutParams(-2, dp(28)).apply { marginEnd = dp(4) })
        picker.show(items, selected)
        markGroup(selected)
        if (animate) {
            picker.animate().cancel()
            picker.alpha = 0f
            picker.translationX = dp(36).toFloat()
            picker.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(LookPicker.EASE).start()
            picker.bloom(dp(10).toFloat(), quick = true)
        } else picker.bloom(dp(22).toFloat())
    }

    private fun styleTab(v: TextView, on: Boolean) {
        v.setTextColor(if (on) 0xff1a1712.toInt() else 0xffd8d4cc.toInt())
        v.typeface = if (on) Style.medium else android.graphics.Typeface.DEFAULT
        v.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (on) setColor(LIGHT) else { setColor(0x33000000); setStroke(1, 0x40f2f0ea) }
        }
    }

    private fun markGroup(index: Int) {
        val g = groupOf(index)
        if (g == shownGroup) return
        shownGroup = g
        for (i in 0 until groupTabs.childCount) {
            val v = groupTabs.getChildAt(i) as? TextView ?: continue
            styleTab(v, v.tag == g)
            if (v.tag == g) groupScroll.post {
                groupScroll.smoothScrollTo((v.left - (groupScroll.width - v.width) / 2).coerceAtLeast(0), 0)
            }
        }
    }

    /** Look [index] of the page shown (0: straight out, scan, no LUT). */
    private fun applyPick(index: Int, settled: Boolean) {
        when (page) {
            0 -> if (index == 0) filmOn = false else { filmOn = true; film = index - 1 }
            1 -> if (index == 0) printOn = false else { printOn = true; paper = index - 1 }
            else -> lut = index
        }
        syncPanel()
        // while the strip still moves, the preview waits for a pause
        schedulePreview(if (settled) 60L else 260L)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val previewSoon = Runnable { developPreview() }

    private fun schedulePreview(delay: Long = 60L) {
        main.removeCallbacks(previewSoon)
        main.postDelayed(previewSoon, delay)
    }

    private fun syncPanel() {
        val stock = FilmEngine.filmStocks[film]
        val paperName = FilmEngine.papers[paper]
        recipeLine.text = "配方：" + LookFamilies.recipeText(filmOn, printOn, CanisterView.displayName(stock),
            LookFamilies.paperName(paperName), lut) +
            (if (temp != 0f) " · 色温 %+.0f".format(Locale.US, temp * 100) else "") +
            (if (tint != 0f) " · 色调 %+.0f".format(Locale.US, tint * 100) else "")
        evValue.text = if (evThirds == 0) "±0" else "%+.1f".format(Locale.US, evThirds / 3f)
        // 校色: each stock balanced to neutral (as a lab's printer would); 原色: its own colour; 手动 neither
        val balance = values[Settings.BALANCE].roundToInt()
        styleChip(colourChips[0], balance == 1)
        styleChip(colourChips[1], balance == 0)
        meterChip.text = if (meter) "测光 · 自动" else "测光 · 关"
        styleChip(meterChip, meter)
        frameChip.text = if (framed) "边框 · 开" else "边框 · 关"
        styleChip(frameChip, framed)
        evDial.thirds = evThirds
        strengthRow.visibility = if (lut != 0) View.VISIBLE else View.GONE
        strength.progress = (lutStrength * 100).roundToInt()
        strengthValue.text = "${(lutStrength * 100).roundToInt()}%"
        val adjusted = local.any { (k, v) -> initial[k] != v }
        val count = if (uris.size > 1) " ${uris.size} 张" else ""
        developButton.text = when {
            developing -> "冲洗中…"
            adjusted -> "按调整冲洗$count"
            else -> "直接冲洗$count"
        }
        developButton.isEnabled = !developing && uris.isNotEmpty()
        developButton.alpha = if (developButton.isEnabled) 1f else 0.5f
        adjustButton.text = if (adjusted) "参数（已调整）" else "调整全部参数"
        adjustButton.isEnabled = !developing
        adjustButton.alpha = if (developing) 0.5f else 1f
    }

    // ---- developing ----------------------------------------------------------------------

    private data class Recipe(val filmOn: Boolean, val film: Int, val printOn: Boolean, val paper: Int,
                              val lut: Int, val strength: Float, val ev: Float,
                              val temp: Float, val tint: Float, val restore: Float, val edr: Float,
                              val meter: Boolean = true)

    private fun recipe() = Recipe(filmOn, film, printOn, paper, lut, lutStrength, evThirds / 3f, temp, tint, restore, edr, meter)

    private fun params(r: Recipe): Map<String, Float> = values.filmParams().toMutableMap().apply {
        this["film"] = r.film.toFloat()
        this["paper"] = r.paper.toFloat()
        this["stage film"] = if (r.filmOn) 1f else 0f
        this["stage print"] = if (r.printOn) 1f else 0f
        // a phone photo is already rendered: take its own contrast, saturation and clipped
        // shoulder back off before the film, which adds its own (the engine's scene restore)
        this["stage restore"] = if (r.filmOn) r.restore else 0f
        this["stage temp"] = r.temp
        this["stage tint"] = r.tint
        // a phone photo is a finished picture: without film it leaves as it came
        this["stage display"] = 1f
        // 测光 off: the picture's own brightness (no scene-restore auto exposure)
        this["stage meter"] = if (r.meter) 1f else 0f
    }

    /**
     * 直出 with nothing on it (no film, no LUT, no exposure or colour change): the picture is
     * not developed at all — shown as it is, and kept as the very file (a JPEG copied byte for
     * byte; another format decoded at full size and saved as JPEG, untouched). For adding only
     * a border. A RAW still needs developing, and does.
     */
    private fun straight(r: Recipe) = !r.filmOn && (r.lut == 0 || r.strength <= 0f) &&
        r.ev == 0f && r.temp == 0f && r.tint == 0f

    /** Worker thread: [bitmap] through the recipe (a CCD camera LUT runs on the CPU after it). */
    private fun develop(bitmap: Bitmap, r: Recipe, longEdge: Int, iso: Int,
                        dng: java.nio.ByteBuffer? = null, file: String? = null): Pair<Bitmap, org.photoall.film.engine.Developed> {
        val digicam = LutLibrary.digicam(r.lut)?.takeIf { r.strength > 0f }
        fun prepared(): FilmEngine = engine().also { e ->
            if (digicam != null || r.lut == 0) e.setCreativeLut(null, LutLibrary.size, 0f)
            else e.setCreativeLut(LutLibrary.table(this, r.lut), LutLibrary.size, r.strength)
        }
        // A camera RAW: scene light as it was, so no scene restore; exposed as the reference
        // spektrafilm exposes (its own meter), the slider on top; the highlights held by the
        // tone compression a camera RAW kept low for. Whatever goes wrong on the way falls
        // back to the picture's way (see rawDevelop).
        // without film (直出, or a LUT alone) a RAW is the phone's own rendering of it, as the
        // album shows it (the system's decoder), not this engine's develop without film
        val raw = if (r.filmOn) dng?.let { rawDevelop(it, bitmap, r, longEdge, ::prepared, file) } else null
        lastRawUsed = raw != null
        val developed = raw ?: prepared().develop(bitmap, params(r), DevelopOptions(maxLongEdge = longEdge, exposureEv = r.ev,
            seed = Random.nextInt(), displayP3 = false))
        val image = if (digicam == null) developed.bitmap else
            Digicam.apply(this, developed.bitmap, digicam, iso = iso, strength = r.strength, seed = Random.nextInt())
                .also { if (it !== developed.bitmap) developed.bitmap.recycle() }
        // a RAW's gain map too: worked out from the phone's own rendering of it (what it pressed
        // under white is what the scene had above), as for any picture
        Edr.attach(image, bitmap, r.edr)
        return image to developed
    }

    /** Whether the last develop took the RAW route, and why not when it did not. */
    @Volatile private var lastRawUsed = false
    private var saidFallback: String? = null
    @Volatile private var lastRawFallback: String? = null

    /**
     * Per file, whether its RAW develop passed the check against the phone's own rendering
     * ("" passed, else why not), decided by its first develop and kept: checked again at
     * every slider move, a file near the line went back and forth between the two ways
     * (a Xiaomi 14 Ultra: an exposure slide jumped from dark to bright and noisy). Worker thread.
     */
    private val rawVerdict = HashMap<String, String>()

    /**
     * A phone's own RAW through the engine, guarded the way the camera's RAW had to be on
     * phones we do not have (docs/COMPATIBILITY.md): sealed for good after the app died in
     * one; refused when too large for the memory left; any engine error, and a result that
     * is plainly wrong next to the phone's own rendering of the same file (blown out, black,
     * a strong colour cast: a DNG this reader misread), fall back to the picture's way.
     * Each fallback is noted for the diagnostics and said once.
     */
    private fun rawDevelop(buffer: java.nio.ByteBuffer, reference: Bitmap, r: Recipe, longEdge: Int,
                           prepared: () -> FilmEngine, file: String? = null): org.photoall.film.engine.Developed? {
        val quirks = getSharedPreferences("camera_quirks", MODE_PRIVATE)
        fun fallback(reason: String, e: Throwable? = null): org.photoall.film.engine.Developed? {
            lastRawFallback = reason
            org.photoall.film.CrashReport.note(this, "导入 RAW 改按图片冲洗：$reason", e ?: IllegalStateException(reason))
            return null
        }
        lastRawFallback = null
        // decided already for this file: the picture's way again, said and noted the first time
        file?.let { rawVerdict[it] }?.takeIf { it.isNotEmpty() }?.let { why -> lastRawFallback = why; return null }
        // A RAW develop that took the app down seals RAW imports, but only for the build it
        // happened on: an update may well have fixed it, so the next build tries once more
        // (and seals again if it goes down too).
        val build = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (quirks.getBoolean("import_raw_inflight", false))
            quirks.edit().remove("import_raw_inflight").putBoolean("import_raw_sealed", true)
                .putLong("import_raw_sealed_build", build).commit()
        if (quirks.getBoolean("import_raw_sealed", false) && quirks.getLong("import_raw_sealed_build", -1L) != build)
            quirks.edit().remove("import_raw_sealed").remove("import_raw_sealed_build").commit()
        if (quirks.getBoolean("import_raw_sealed", false)) return fallback("上次按 RAW 冲洗时应用退出了，这台手机已停用 RAW 导入（更新应用后会再试）")
        val bytes = buffer.capacity().toLong()
        if (bytes > 200_000_000L) return fallback("文件太大（${bytes / 1_048_576} MB）")
        val memory = android.app.ActivityManager.MemoryInfo().also {
            getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
        }
        if (memory.availMem < bytes * 6 + 300_000_000L) return fallback("可用内存不足（${memory.availMem / 1_048_576} MB）")
        quirks.edit().putBoolean("import_raw_inflight", true).commit()
        org.photoall.film.CrashReport.stage(this, "导入 RAW 冲洗（${bytes / 1_048_576} MB）")
        val developed = try {
            buffer.rewind()
            // 测光 off: the RAW at its own exposure (and the file's baseline), not metered to mid-grey
            prepared().developDng(listOf(buffer), params(r) + ("stage restore" to 0f), DevelopOptions(maxLongEdge = longEdge,
                matchKey = if (r.meter) org.photoall.film.engine.REFERENCE_EXPOSURE else 0f,
                exposureEv = r.ev, seed = Random.nextInt(), displayP3 = false, localTone = 0.6f,
                chromaDenoise = store[Settings.CHROMA_NR], rawDenoise = store[Settings.RAW_DENOISE]))
        } catch (e: Throwable) {
            // a failed submission leaves the engine unusable: the picture's way gets a new one
            if (e.message?.contains("DNG") != true) { runCatching { engine?.close() }; engine = null }
            // a linear DNG (Samsung Expert RAW, Apple ProRAW): told in plain words, not as a failure
            val msg = e.message.orEmpty()
            // a format the engine cannot read stays so: decided once per file, not tried again
            // (and the engine rebuilt) at every slider move
            if (msg.contains("not supported") && file != null) rawVerdict[file] = when {
                msg.contains("X-Trans") -> "富士 X-Trans 的 RAF 引擎暂不能按 RAW 冲洗，已用它内置的成片冲洗，EDR 照常可调"
                else -> "引擎读不了这张 RAW：$msg"
            }
            file?.let { rawVerdict[it] }?.let { return fallback(it, e) }
            if (msg.contains("linear DNG")) return fallback(if (msg.contains("52546"))
                "这是线性 DNG（如三星 Expert RAW，JPEG XL 压缩），引擎暂不能按 RAW 冲洗；已用它内置的全尺寸成片冲洗，效果不受影响"
                else "这是线性 DNG（如三星 Expert RAW、苹果 ProRAW），引擎暂不能按 RAW 冲洗；已用它内置的全尺寸成片冲洗，效果不受影响", e)
            return fallback("引擎读不了这张 RAW：${e.message}", e)
        } finally {
            quirks.edit().remove("import_raw_inflight").commit()
        }
        if (file != null && rawVerdict[file] == "") return developed
        val why = misread(developed.bitmap, reference)
        if (file != null) rawVerdict[file] = why ?: ""
        if (why != null) {
            developed.bitmap.recycle()
            return fallback(why)
        }
        return developed
    }

    /** A RAW develop plainly wrong next to the phone's own rendering, or null. */
    private fun misread(out: Bitmap, reference: Bitmap): String? {
        fun stats(b: Bitmap): FloatArray {
            val small = Bitmap.createScaledBitmap(if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b, 48, 48, true)
            val px = IntArray(48 * 48).also { small.getPixels(it, 0, 48, 0, 0, 48, 48) }
            var r = 0.0; var g = 0.0; var bl = 0.0; var white = 0
            for (c in px) {
                val cr = Color.red(c) / 255.0; val cg = Color.green(c) / 255.0; val cb = Color.blue(c) / 255.0
                r += cr; g += cg; bl += cb
                if (cr > 0.97 && cg > 0.97 && cb > 0.97) white++
            }
            val n = px.size.toDouble()
            return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (bl / n).toFloat(), (white / n).toFloat())
        }
        val a = stats(out)
        val b = stats(reference)
        val luma = 0.2126f * a[0] + 0.7152f * a[1] + 0.0722f * a[2]
        if (a[3] > 0.5f && a[3] > b[3] + 0.3f) return "成片大面积过曝（${(a[3] * 100).toInt()}%）"
        if (luma < 0.02f) return "成片几乎全黑"
        fun cast(x: Float, y: Float) = kotlin.math.abs(kotlin.math.ln((x + 0.01f) / (y + 0.01f)))
        val limit = kotlin.math.ln(2.2f)
        if (cast(a[0] / (a[1] + 0.01f), b[0] / (b[1] + 0.01f)) > limit ||
            cast(a[2] / (a[1] + 0.01f), b[2] / (b[1] + 0.01f)) > limit) return "成片严重偏色"
        return null
    }

    private fun developPreview() {
        val src = source ?: return
        val r = recipe()
        val ticket = ++request
        busy.text = "预览中…"
        busy.visibility = View.VISIBLE
        worker.later {
            val file = uris.getOrNull(current)?.toString()
            val result = runCatching { if (straight(r)) src else develop(src, r, PREVIEW_EDGE, 200, sourceDng, file).first }
            val measured = result.getOrNull()?.let { runCatching { ScopeView.measure(it) }.getOrNull() }
            runOnUiThread {
                if (ticket != request) return@runOnUiThread
                result.onSuccess {
                    developedPreview = it
                    preview.setImageBitmap(it)
                    Edr.fitWindow(window, it)
                    scope.show(measured)
                    busy.visibility = View.GONE
                    val why = lastRawFallback
                    if (sourceDng != null && why != null && why != saidFallback) {
                        saidFallback = why
                        android.widget.Toast.makeText(this, "这张 RAW 改按普通照片冲洗：$why", android.widget.Toast.LENGTH_LONG).show()
                    }
                }.onFailure { busy.text = "预览失败：${it.message}" }
            }
        }
    }

    private fun developAll() {
        if (developing || uris.isEmpty()) return
        developing = true
        haptics.play(Haptics.Event.SEGMENT)
        syncPanel()
        val r = recipe()
        val list = uris
        val quality = store[Settings.JPEG_QUALITY].toInt()
        val private = store.flag(Settings.PRIVATE_PHOTOS)
        val longEdge = store.longEdge
        toFrame.clear()
        worker.later {
            var done = 0
            var failed = 0
            for ((i, uri) in list.withIndex()) {
                runOnUiThread { busy.text = "冲洗中 ${i + 1} / ${list.size}…"; busy.visibility = View.VISIBLE }
                val ok = runCatching { developOne(uri, r, quality, private, longEdge) }
                    .onFailure { android.util.Log.e("FilmImport", "develop $uri", it) }.isSuccess
                if (ok) done++ else failed++
            }
            runOnUiThread {
                developing = false
                syncPanel()
                haptics.play(if (failed == 0) Haptics.Event.DEVELOPED else Haptics.Event.ERROR)
                busy.text = if (failed == 0) "已冲洗 $done 张，存进相册" else "冲洗 $done 张，$failed 张失败"
                busy.visibility = View.VISIBLE
                // straight on to the border page, the pictures one after another
                val frames = if (framed) ArrayList(toFrame) else ArrayList()
                if (frames.isNotEmpty()) main.postDelayed({
                    if (isFinishing) return@postDelayed
                    startActivity(frames.first().putParcelableArrayListExtra(FrameActivity.QUEUE, ArrayList(frames.drop(1))))
                    if (failed == 0) finish()
                }, 500)
                else if (failed == 0) main.postDelayed({ if (!isFinishing) finish() }, 700)
            }
        }
    }

    /** Worker thread: one picture, full size, into the album with its recipe and a log. */
    private fun developOne(uri: Uri, r: Recipe, quality: Int, private: Boolean, longEdge: Int) {
        val start = System.nanoTime()
        // 直出 with nothing on it: kept as it is (a RAW as the system renders it, see keepAsItIs)
        if (straight(r)) return keepAsItIs(uri, r, quality, private, start)
        // an import comes out at the original's own size (up to the engine's 原尺寸, 8192), not
        // at the camera's 冲洗尺寸 (4096 by default, a 12 MP result); that setting is only the
        // step-down when the memory left would not hold the original, and the step-down is told
        val (edge, reduced) = importEdge(uri, longEdge)
        if (reduced != null) runOnUiThread {
            android.widget.Toast.makeText(this, reduced, android.widget.Toast.LENGTH_LONG).show()
        }
        val bitmap = decode(uri, edge) ?: error("读不出照片")
        val shot = ShotInfo.read(this, uri)
        val (image, developed) = develop(bitmap, r, maxOf(bitmap.width, bitmap.height), shot?.iso ?: 200, readDng(uri), uri.toString())
        if (image !== bitmap) bitmap.recycle()
        val stock = FilmEngine.filmStocks[r.film]
        val paperName = FilmEngine.papers[r.paper]
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "FILM_${stamp}_${(if (r.filmOn) stock else "digital").replace(Regex("[^a-z0-9]+"), "-")}.jpg"
        val text = LookFamilies.recipeText(r.filmOn, r.printOn, stock, paperName, r.lut)
        val saved = Photos.saveJpeg(this, image, name, quality, "$text · 导入", shot, private)
        toFrame += frameIntent(saved, name, if (r.filmOn) stock else "", "$text · 导入", shot, private)
        val log = ShotLog()
        log.put("拍摄", "来源", "导入的照片")
        log.put("拍摄", "冲洗方式", if (lastRawUsed) "按 RAW" else lastRawFallback?.let { "按图片（$it）" } ?: "按图片")
        log.put("拍摄", "原图尺寸", "${bitmap.width}x${bitmap.height}（按原图尺寸冲洗，长边上限 $edge${reduced?.let { "；$it" } ?: ""}）")
        log.put("胶片配方", "配方", text)
        log.put("胶片配方", "曝光", "%+.2f EV".format(Locale.US, r.ev))
        if (r.lut != 0) log.put("胶片配方", "LUT 强度", "%.0f%%".format(Locale.US, r.strength * 100))
        log.put("胶片配方", "微调", "色温 %+.0f · 色调 %+.0f · 场景还原 %.0f%% · EDR %s".format(Locale.US,
            r.temp * 100, r.tint * 100, r.restore * 100, if (Edr.supported && image.hasGainmap()) "+%.1f 档".format(Locale.US, r.edr) else "关"))
        log.putEngine(developed.info)
        log.put("冲洗结果", "文件", name)
        log.put("冲洗结果", "成片尺寸", "${image.width}x${image.height}")
        log.put("冲洗结果", "冲洗用时", "%.2f 秒".format(Locale.US, (System.nanoTime() - start) / 1e9))
        log.put("冲洗结果", "GPU 时间", "%.1f ms".format(Locale.US, developed.gpuMs))
        log.save(this, name)
        image.recycle()
    }

    /**
     * The long edge an import develops at: the original's (at most [IMPORT_MAX_EDGE], the
     * engine's 原尺寸). When the memory left would not hold that develop, the camera's
     * 冲洗尺寸 [fallback] instead, with the words saying so (null when not reduced).
     */
    private fun importEdge(uri: Uri, fallback: Int): Pair<Int, String?> {
        val size = runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }.getOrNull() ?: runCatching {
            // HEIC / DNG the bounds reader may not know: the decoder's header
            var wh: Pair<Int, Int>? = null
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                wh = info.size.width to info.size.height
                d.setTargetSize(1, 1)
            }.recycle()
            wh
        }.getOrNull() ?: return IMPORT_MAX_EDGE to null
        val long = maxOf(size.first, size.second)
        val edge = minOf(long, IMPORT_MAX_EDGE)
        val k = edge.toDouble() / long
        val pixels = (size.first * k) * (size.second * k)
        // the decoded picture, the engine's copies and the result: ~6 RGBA copies, plus headroom
        val need = (pixels * 4 * 6).toLong() + 300_000_000L
        val memory = android.app.ActivityManager.MemoryInfo().also {
            getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
        }
        if (edge > fallback && memory.availMem < need)
            return fallback to "内存不足，这张按长边 $fallback 冲洗（原图长边 $long）"
        return edge to null
    }

    /** The border page for a picture just saved: blank to begin with (no border, no layout). */
    private fun frameIntent(uri: Uri, name: String, film: String, description: String, shot: ShotInfo?, private: Boolean) =
        Intent(this, FrameActivity::class.java)
            .putExtra(FrameActivity.URI, uri)
            .putExtra(FrameActivity.NAME, name)
            .putExtra(FrameActivity.FRAME, 1)
            .putExtra(FrameActivity.FILM, film)
            .putExtra(FrameActivity.TAKEN, shot?.takenMs ?: System.currentTimeMillis())
            .putExtra(FrameActivity.PRIVATE, private)
            .putExtra(FrameActivity.DESCRIPTION, description)
            .putExtra(FrameActivity.BLANK, true)

    /** 直出 with nothing on it: the picture into the album untouched (see [straight]). */
    private fun keepAsItIs(uri: Uri, r: Recipe, quality: Int, private: Boolean, start: Long) {
        val shot = ShotInfo.read(this, uri)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "FILM_${stamp}_straight.jpg"
        val text = "直出 · 导入"
        val mime = contentResolver.getType(uri) ?: ""
        val copied = mime == "image/jpeg"
        val saved = if (copied) Photos.saveCopy(this, uri, name, "image/jpeg", text, private)
        else {
            // not a JPEG (HEIC, PNG…): its pixels as they are, at full size, as a JPEG
            val bitmap = decode(uri, 8192) ?: error("读不出照片")
            Photos.saveJpeg(this, bitmap, name, quality, text, shot, private).also { bitmap.recycle() }
        }
        toFrame += frameIntent(saved, name, "", text, shot, private)
        val log = ShotLog()
        log.put("拍摄", "来源", "导入的照片")
        log.put("拍摄", "冲洗方式", if (copied) "直出：原文件原样存入，未经任何处理" else "直出：原图像素原样存为 JPEG（原格式 ${mime.ifEmpty { "未知" }}）")
        log.put("胶片配方", "配方", "直出（不用胶卷、LUT、相纸）")
        log.put("冲洗结果", "文件", name)
        log.put("冲洗结果", "用时", "%.2f 秒".format(Locale.US, (System.nanoTime() - start) / 1e9))
        log.save(this, name)
    }

    /** The file as a DNG in native memory when it is one (a TIFF), else null. At most 400 MB. */
    private fun readDng(uri: Uri): java.nio.ByteBuffer? = RawSource.read(contentResolver, uri)

    /** At most [edge] px on the long side, upright, 8-bit sRGB in memory the engine can read. */
    private fun decode(uri: Uri, edge: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val s = minOf(1f, edge.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * s).roundToInt()), maxOf(1, (info.size.height * s).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    }.getOrNull()

    /** The settings the engine develops with, apart from the film and paper the strip picks. */
    private fun engineParam(setting: Setting) = setting.group.isNotEmpty() &&
        (!setting.key.startsWith("app.") || setting.key in setOf(Settings.BALANCE, Settings.PUSH, Settings.FORMAT)) && setting.key != "film" && setting.key != "paper"

    // ---- every parameter, in a panel of at most half the screen ----------------------------

    private fun showParams() {
        if (paramsPanel?.visibility == View.VISIBLE) return
        val panel = paramsPanel ?: SettingsPanel(this, values, onBench = null, onClose = { hideParams() },
            title = "全部参数", only = ::engineParam, open = openSections, tabbed = true).also {
            paramsPanel = it
            it.elevation = dp(8).toFloat()
            root.addView(it, LinearLayout.LayoutParams(-1, 0))
        }
        panel.refresh()
        panel.setPadding(0, 0, 0, bottomInset)
        // half of the screen at most: the picture stays in view above it, changing as it is set
        (panel.layoutParams as LinearLayout.LayoutParams).height = root.height / 2
        panel.requestLayout()
        haptics.play(Haptics.Event.SEGMENT)
        mainPanel.visibility = View.GONE
        thumbScroll.visibility = View.GONE
        // film and paper are chosen by now: the picture takes the strip's room too
        pickerStrip.visibility = View.GONE
        (preview.layoutParams as FrameLayout.LayoutParams).bottomMargin = 0
        preview.requestLayout()
        panel.visibility = View.VISIBLE
        panel.translationY = (root.height / 2).toFloat()
        panel.animate().translationY(0f).setDuration(320).setInterpolator(LookPicker.EASE).start()
        closeParams.isEnabled = true
    }

    private fun hideParams() {
        val panel = paramsPanel ?: return
        closeParams.isEnabled = false
        panel.animate().translationY(panel.height.toFloat()).setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).withEndAction {
                panel.visibility = View.GONE
                mainPanel.visibility = View.VISIBLE
                pickerStrip.visibility = View.VISIBLE
                (preview.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(STRIP)
                preview.requestLayout()
                thumbScroll.visibility = if (uris.size > 1) View.VISIBLE else View.GONE
                syncPanel()
            }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** The look strip's height over the picture's foot: its fade, marker, cans and family tabs. */
    private val STRIP = 18 + 4 + 2 + 88 + 2 + 36 + 6 - 24

    companion object {
        const val URIS = "uris"
        private const val MAX = 30
        private const val SIDE = 72 // dp either side of the exposure ruler and the strength bar
        private const val PREVIEW_EDGE = 1280
        /** An import's largest long edge: the engine's 原尺寸 (the camera's largest 冲洗尺寸). */
        private val IMPORT_MAX_EDGE = org.photoall.film.settings.Settings.longEdges.last()
        private const val DARK = 0xff121110.toInt()
        private const val LIGHT = 0xfff2f0ea.toInt()
    }
}
