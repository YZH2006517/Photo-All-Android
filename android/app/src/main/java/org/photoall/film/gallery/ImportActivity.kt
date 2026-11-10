package org.photoall.film.gallery

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
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
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
                        developedPreview?.let { setImageBitmap(it) }
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
        groupScroll = HorizontalScrollView(this).apply {
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
            addView(preview, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(120) })
            addView(busy, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
            addView(strip, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        thumbs = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
        thumbScroll = HorizontalScrollView(this).apply {
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
            addView(recipeLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
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

    private val showOriginal = Runnable { source?.let { preview.setImageBitmap(it) } }

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
            runOnUiThread {
                if (ticket != request) return@runOnUiThread
                if (small == null) { busy.text = "读不出这张照片"; return@runOnUiThread }
                source = small
                developedPreview = null
                preview.setImageBitmap(small)
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
            LookFamilies.paperName(paperName), lut)
        evValue.text = if (evThirds == 0) "±0" else "%+.1f".format(Locale.US, evThirds / 3f)
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
                              val lut: Int, val strength: Float, val ev: Float)

    private fun recipe() = Recipe(filmOn, film, printOn, paper, lut, lutStrength, evThirds / 3f)

    private fun params(r: Recipe): Map<String, Float> = values.filmParams().toMutableMap().apply {
        this["film"] = r.film.toFloat()
        this["paper"] = r.paper.toFloat()
        this["stage film"] = if (r.filmOn) 1f else 0f
        this["stage print"] = if (r.printOn) 1f else 0f
    }

    /** Worker thread: [bitmap] through the recipe (a CCD camera LUT runs on the CPU after it). */
    private fun develop(bitmap: Bitmap, r: Recipe, longEdge: Int, iso: Int): Pair<Bitmap, org.photoall.film.engine.Developed> {
        val engine = engine()
        val digicam = LutLibrary.digicam(r.lut)?.takeIf { r.strength > 0f }
        if (digicam != null || r.lut == 0) engine.setCreativeLut(null, LutLibrary.size, 0f)
        else engine.setCreativeLut(LutLibrary.table(this, r.lut), LutLibrary.size, r.strength)
        val developed = engine.develop(bitmap, params(r), DevelopOptions(maxLongEdge = longEdge, exposureEv = r.ev,
            seed = Random.nextInt(), displayP3 = false))
        val image = if (digicam == null) developed.bitmap else
            Digicam.apply(this, developed.bitmap, digicam, iso = iso, strength = r.strength, seed = Random.nextInt())
                .also { if (it !== developed.bitmap) developed.bitmap.recycle() }
        return image to developed
    }

    private fun developPreview() {
        val src = source ?: return
        val r = recipe()
        val ticket = ++request
        busy.text = "预览中…"
        busy.visibility = View.VISIBLE
        worker.later {
            val result = runCatching { develop(src, r, PREVIEW_EDGE, 200).first }
            runOnUiThread {
                if (ticket != request) return@runOnUiThread
                result.onSuccess {
                    developedPreview = it
                    preview.setImageBitmap(it)
                    busy.visibility = View.GONE
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
                if (failed == 0) main.postDelayed({ if (!isFinishing) finish() }, 700)
            }
        }
    }

    /** Worker thread: one picture, full size, into the album with its recipe and a log. */
    private fun developOne(uri: Uri, r: Recipe, quality: Int, private: Boolean, longEdge: Int) {
        val start = System.nanoTime()
        val bitmap = decode(uri, longEdge) ?: error("读不出照片")
        val shot = ShotInfo.read(this, uri)
        val (image, developed) = develop(bitmap, r, longEdge, shot?.iso ?: 200)
        if (image !== bitmap) bitmap.recycle()
        val stock = FilmEngine.filmStocks[r.film]
        val paperName = FilmEngine.papers[r.paper]
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "FILM_${stamp}_${(if (r.filmOn) stock else "digital").replace(Regex("[^a-z0-9]+"), "-")}.jpg"
        val text = LookFamilies.recipeText(r.filmOn, r.printOn, stock, paperName, r.lut)
        Photos.saveJpeg(this, image, name, quality, "$text · 导入", shot, private)
        val log = ShotLog()
        log.put("拍摄", "来源", "导入的照片")
        log.put("拍摄", "原图尺寸", "${bitmap.width}x${bitmap.height}（冲洗长边上限 $longEdge）")
        log.put("胶片配方", "配方", text)
        log.put("胶片配方", "曝光", "%+.2f EV".format(Locale.US, r.ev))
        if (r.lut != 0) log.put("胶片配方", "LUT 强度", "%.0f%%".format(Locale.US, r.strength * 100))
        log.putEngine(developed.info)
        log.put("冲洗结果", "文件", name)
        log.put("冲洗结果", "成片尺寸", "${image.width}x${image.height}")
        log.put("冲洗结果", "冲洗用时", "%.2f 秒".format(Locale.US, (System.nanoTime() - start) / 1e9))
        log.put("冲洗结果", "GPU 时间", "%.1f ms".format(Locale.US, developed.gpuMs))
        log.save(this, name)
        image.recycle()
    }

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
        (!setting.key.startsWith("app.") || setting.key == Settings.BALANCE) && setting.key != "film" && setting.key != "paper"

    // ---- every parameter, in a panel of at most half the screen ----------------------------

    private fun showParams() {
        if (paramsPanel?.visibility == View.VISIBLE) return
        val panel = paramsPanel ?: SettingsPanel(this, values, onBench = null, onClose = { hideParams() },
            title = "全部参数", only = ::engineParam, open = openSections).also {
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
                (preview.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(120)
                preview.requestLayout()
                thumbScroll.visibility = if (uris.size > 1) View.VISIBLE else View.GONE
                syncPanel()
            }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val URIS = "uris"
        private const val MAX = 30
        private const val SIDE = 72 // dp either side of the exposure ruler and the strength bar
        private const val PREVIEW_EDGE = 1280
        private const val DARK = 0xff121110.toInt()
        private const val LIGHT = 0xfff2f0ea.toInt()
    }
}
