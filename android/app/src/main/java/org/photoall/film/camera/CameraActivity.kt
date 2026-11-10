package org.photoall.film.camera

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.graphics.ImageFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import org.photoall.film.MainActivity
import org.photoall.film.engine.DevelopOptions
import org.photoall.film.engine.Developed
import org.photoall.film.engine.FilmEngine
import org.photoall.film.gallery.GalleryActivity
import org.photoall.film.gallery.Photos
import org.photoall.film.settings.LutLibrary
import org.photoall.film.settings.Setting
import org.photoall.film.gallery.Frames
import org.photoall.film.ui.CanisterView
import org.photoall.film.ui.LensWell
import org.photoall.film.ui.Roller
import org.photoall.film.ui.SlideSwitch
import org.photoall.film.ui.SoftKey
import org.photoall.film.ui.Toy
import org.photoall.film.ui.swapText
import org.photoall.film.ui.Haptics
import org.photoall.film.ui.Icon
import org.photoall.film.ui.LensButton
import org.photoall.film.ui.LookPicker
import org.photoall.film.ui.Swatch
import org.photoall.film.ui.RulerDial
import org.photoall.film.ui.Segmented
import org.photoall.film.ui.Sheet
import org.photoall.film.ui.Style
import org.photoall.film.settings.Settings
import org.photoall.film.settings.SettingsStore
import java.io.File
import org.photoall.film.gallery.ShotInfo
import org.photoall.film.gallery.later
import org.photoall.film.gallery.ShotLog
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Film camera. The viewfinder shows the film's per-pixel response live; the shutter
 * captures JPEG or RAW (DNG), develops it with the full spectral model and saves it.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraActivity : ComponentActivity() {
    private val engineThread = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: SettingsStore
    private var engine: FilmEngine? = null
    private lateinit var viewfinder: FilmViewfinder
    private lateinit var overlay: ViewfinderOverlay
    private lateinit var status: TextView
    private lateinit var modeLabel: TextView
    private lateinit var recipeSummary: TextView // the film card's title
    private lateinit var recipeSub: TextView     // paper / LUT under it
    private lateinit var canister: CanisterView
    private lateinit var recipeCard: LinearLayout
    // Landscape: the toy camera body around the viewfinder.
    private lateinit var exposureRoller: Roller
    // landscape, manual exposure: two upright engraved wheels, ISO and shutter
    private lateinit var isoWheel: org.photoall.film.ui.ScaleWheel
    private lateinit var shutterWheel: org.photoall.film.ui.ScaleWheel
    private var exposureBlock: View? = null
    private var manualBlock: View? = null
    private var blocksManual: Boolean? = null
    private lateinit var lookRoller: Roller
    private lateinit var lensWell: LensWell
    private lateinit var hdrSwitch: SlideSwitch
    private lateinit var amPill: TextView
    private lateinit var exposureReadout: TextView
    private lateinit var lookReadout: TextView
    private lateinit var counterReadout: TextView
    private val lookCaption by lazy { TextView(this) }
    // The look card pages through film, paper and LUT; a long press opens the picker strip.
    private var lookPage = 0
    private lateinit var swatch: Swatch
    private lateinit var cardContent: LinearLayout
    private lateinit var pageDots: LinearLayout
    private lateinit var pickerPanel: LinearLayout
    private lateinit var picker: LookPicker
    private lateinit var pickerTitle: TextView
    /** Under the strip: the families of the page shown (a tap jumps to its first). Film / paper / LUT is the card's swipe. */
    private lateinit var pickerTabs: LinearLayout       // the whole row (it fades as one)
    private lateinit var pickerGroupTabs: LinearLayout  // the families of the page shown
    private lateinit var pickerTabScroll: android.widget.HorizontalScrollView
    private var pickerGroups: List<Pair<String, Int>> = emptyList()
    private var pickerGroupOf: (Int) -> String? = { null }
    private var shownPickerGroup: String? = null
    private var pickerOpen = false
    private var pendingPick: Runnable? = null
    private val pickerBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closePicker()
    }
    private lateinit var controls: LinearLayout
    private lateinit var viewfinderFrame: ViewfinderFrame
    // Top bar: flash, HDR, the frame counter, histogram, settings.
    private lateinit var topBar: LinearLayout
    private lateinit var flashIcon: ImageView
    private lateinit var histogramIcon: ImageView
    private lateinit var settingsIcon: ImageView
    private lateinit var frameCount: TextView
    private lateinit var frameNote: TextView
    // Beside the film card: the EV button, which opens the ruler dial below.
    private lateinit var evButton: LinearLayout
    private lateinit var evDial: RulerDial
    private lateinit var dialRow: LinearLayout
    private var evOpen = false
    // Manual exposure in the band: ISO / shutter chips and one ruler for the chosen one.
    private lateinit var manualRow: LinearLayout
    private lateinit var manualDial: RulerDial
    private lateinit var isoChip: TextView
    private lateinit var shutterChip: TextView
    private var manualKey = Settings.SHUTTER
    /** Portrait with height to spare under the controls: the EV ruler stays out, no empty band. */
    private var dialPinned = false
    private lateinit var quickButton: ImageView
    /** The shooting-settings sheet while open: rebuilds itself when a setting changes. */
    private var sheetRefresh: ((String) -> Unit)? = null
    private lateinit var rotateButton: org.photoall.film.ui.RotateHint
    // Orientation the rotate button would switch to (ActivityInfo.SCREEN_ORIENTATION_*).
    private var offeredOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    // The layout the user chose. Portrait-only screens opened over the camera (settings,
    // the album) and a rebuild for a new theme must not lose it.
    private var chosenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private val hideRotate = Runnable { rotateButton.dismiss() }
    private lateinit var orientationListener: OrientationEventListener
    // The creative LUT last sent to the engine (engine thread), to skip re-uploads.
    private var appliedLut: Pair<Int, Float>? = null
    private lateinit var evLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var facingButton: ImageView
    private lateinit var hdrButton: Button
    private lateinit var peakButton: Button
    private lateinit var distanceLabel: TextView
    private lateinit var focusSeek: SeekBar
    /**
     * While the strip shows the LUTs and one is chosen, the shutter is also the LUT's
     * strength: a ring round it, turned by drawing circles on the shutter (clockwise for more). A
     * tap still takes the picture.
     */
    private val lutDial by lazy { org.photoall.film.ui.StrengthDial(resources.displayMetrics.density) }
    private var lutDialOn = false
    private var lutDialDragging = false
    private var lutDialAnim: android.animation.ValueAnimator? = null
    private var lutDialActiveAnim: android.animation.ValueAnimator? = null
    /** Landscape: manual focus as a rail on the picture's right edge (portrait has the row). */
    private lateinit var focusRail: org.photoall.film.ui.FocusRail
    private lateinit var mfRow: LinearLayout
    private lateinit var thumbnail: ImageView
    private lateinit var shutter: View
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    /** HDR: a zero-lag Camera2 session in place of CameraX (see ZslCamera). */
    private var zsl: ZslCamera? = null
    private var zslPaused = false
    /** The lens in use (CameraX lists it whether bound or not). */
    private var boundInfo: CameraInfo? = null
    private var lastUnderChange = 0L
    private var underWanted: Double? = null
    private var underWantedAt = 0L
    private var rawCapture = false
    private var front = false
    private var lensId: String? = null
    private var pendingZoom: Float? = null // applied once a lens switch has bound
    private val analysisThread = Executors.newSingleThreadExecutor()
    private var exposure: FilmExposure? = null
    private var meter: PreviewMeter? = null
    @Volatile private var lastLut: ByteArray? = null // the viewfinder's film LUT, for the histogram
    private var evStops = 0.0
    // Settings live on their own screen; these decide what to redo on return.
    private var settingsOpen = false
    private var bindSnapshot = emptyList<Float>()
    private var developing = 0
    private var themeShown = 0f // the theme the views were built in
    private lateinit var haptics: Haptics
    private val rebake = Runnable { engineThread.later { bakeLut() } }
    /**
     * A re-bake as soon as the engine is free, at most one waiting: while a value keeps
     * changing (a dial being turned) the preview follows it frame by frame and ends on the
     * last value, where the debounced [rebake] would wait for the change to stop.
     */
    @Volatile private var bakeWaiting = false
    private fun bakeSoon() {
        main.removeCallbacks(rebake)
        if (bakeWaiting) return
        bakeWaiting = true
        engineThread.later { bakeWaiting = false; bakeLut() }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCamera() else status.text = "需要相机权限才能取景"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        org.photoall.film.ui.Style.apply(this)
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        themeShown = store[Settings.THEME]
        haptics = Haptics(window.decorView) { store[Settings.HAPTICS].toInt() }
        viewfinder = FilmViewfinder(this).apply {
            onPinch = { factor -> zoomBy(factor) }
            onTap = { x, y -> if (flashDock != null) closeFlashModes() else if (pickerOpen) closePicker() else focusAt(x, y) }
            onDoubleTap = { x, y -> focusAt(x, y, lock = true) }
        }
        overlay = ViewfinderOverlay(this)

        // One grid for every control row: same side padding, same row height, and
        // each row split into equal cells, so columns line up from row to row.
        status = label(12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Style.TEXT_2) }
        modeLabel = label(12f).apply { gravity = Gravity.END; setTextColor(Style.TEXT_2); typeface = Style.medium }
        evLabel = label(15f).apply { gravity = Gravity.CENTER; typeface = android.graphics.Typeface.MONOSPACE; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        zoomLabel = label(14f).apply { gravity = Gravity.CENTER; minWidth = dp(48) }
        distanceLabel = label(13f).apply { gravity = Gravity.CENTER; minWidth = dp(64) }
        // The lenses sit on the viewfinder's lower edge, as round focal-length buttons.
        lensRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        facingButton = ImageView(this).apply {
            contentDescription = "切换前后镜头"
            setImageDrawable(Icon(Icon.Kind.FLIP, 0xfff2f0ea.toInt(), 1.8f))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80151515.toInt()) }
            Style.pressable(this)
            setOnClickListener { front = !front; lensId = null; pendingZoom = null; haptics.play(Haptics.Event.SEGMENT); bindCamera() }
        }
        hdrButton = button("HDR") {
            store[Settings.HDR] = if (store.flag(Settings.HDR)) 0f else 1f
            haptics.play(Haptics.Event.SEGMENT)
        }.apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; Style.compact(this, 10f); Style.pressable(this) }
        flashIcon = iconButton("闪光") { showFlashModes(flashIcon) }
        histogramIcon = iconButton("直方图") {
            store[Settings.HISTOGRAM] = if (store.flag(Settings.HISTOGRAM)) 0f else 1f
            haptics.play(Haptics.Event.SEGMENT)
        }
        settingsIcon = iconButton("参数") { openSettings() }.apply { setImageDrawable(Icon(Icon.Kind.TUNE, Style.TEXT)) }
        frameCount = label(20f).apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD) }
        frameNote = label(11f).apply { setTextColor(Style.TEXT_2); typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.06f }
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        quickButton = ImageView(this).apply {
            contentDescription = "拍摄设置"
            setImageDrawable(Icon(Icon.Kind.GRID, Style.TEXT))
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = Style.surface(this@CameraActivity, radiusDp = 26f)
            Style.pressable(this)
            setOnClickListener { showQuickSheet() }
        }
        peakButton = button("峰值") { store[Settings.PEAKING] = if (store.flag(Settings.PEAKING)) 0f else 1f }
        focusSeek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) store[Settings.FOCUS_DISTANCE] = progress / 1000f
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        focusRail = org.photoall.film.ui.FocusRail(this).apply {
            visibility = View.GONE
            onChange = { v -> store[Settings.FOCUS_DISTANCE] = v }
            onPeak = { haptics.play(Haptics.Event.SEGMENT); store[Settings.PEAKING] = if (store.flag(Settings.PEAKING)) 0f else 1f }
            onDetent = { kind -> haptics.play(when (kind) { 2 -> Haptics.Event.LIMIT; 1 -> Haptics.Event.STOP; else -> Haptics.Event.TICK }) }
        }
        thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "相册"
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Style.SURFACE); setStroke(1, Style.HAIRLINE) }
            clipToOutline = true
            setOnClickListener { coach?.event("album"); startActivity(Intent(this@CameraActivity, GalleryActivity::class.java)) }
        }
        shutter = View(this).apply {
            background = Style.shutter(this@CameraActivity)
            foreground = lutDial // drawn only while a LUT is being picked
            setOnClickListener { takePicture() }
            setOnTouchListener(lutDialTouch())
        }

        mfRow = gridRow(
            cell(label(13f).apply { text = "∞"; gravity = Gravity.CENTER; setTextColor(Style.TEXT_2) }, 0.6f),
            cell(focusSeek.also { Style.slider(it) }, 3.4f),
            cell(label(13f).apply { text = "近"; gravity = Gravity.CENTER; setTextColor(Style.TEXT_2) }, 0.6f),
            cell(distanceLabel, 1.2f),
            cell(peakButton, 1.2f),
        )
        // Film / paper / LUT: the film card (the stock's cartridge, its name, the paper and
        // LUT); tap to show the three switch rows, tap a name there for the full list.
        LutLibrary.load(this)
        // Bundled LUTs put ahead of the others shift every index after them: the LUT chosen
        // before follows. "leading" is the shift the stored choice already has (one build
        // had 22 such LUTs, marked by order 2).
        getSharedPreferences("luts", MODE_PRIVATE).let { prefs ->
            val applied = prefs.getInt("leading", if (prefs.getInt("order", 1) >= 2) 22 else 0)
            if (applied != LutLibrary.leading) {
                val chosen = store[Settings.LUT].toInt()
                if (chosen > applied) store[Settings.LUT] = (chosen - applied + LutLibrary.leading).toFloat()
                else if (chosen > 0) { store[Settings.LUT] = 0f; store[Settings.LUT_ON] = 0f }
            }
            prefs.edit().putInt("leading", LutLibrary.leading).putInt("order", 3).apply()
        }
        recipeSummary = label(15f).apply {
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Style.medium
            maxLines = 1
        }
        recipeSub = label(12f).apply {
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Style.TEXT_2)
            maxLines = 1
        }
        canister = CanisterView(this)
        swatch = Swatch(this).apply { visibility = View.GONE }
        // three dots down the right edge: which of film / paper / LUT the card shows
        pageDots = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            repeat(3) {
                addView(View(this@CameraActivity), LinearLayout.LayoutParams(dp(4), dp(4)).apply { topMargin = dp(2); bottomMargin = dp(2) })
            }
        }
        cardContent = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(this@CameraActivity).apply {
                addView(canister, FrameLayout.LayoutParams(-1, -1))
                addView(swatch, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(30), dp(40)))
            addView(LinearLayout(this@CameraActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(6), 0)
                addView(recipeSummary, LinearLayout.LayoutParams(-1, -2))
                addView(recipeSub, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        recipeCard = LinearLayout(this).apply {
            Style.pressable(this)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(10), 0)
            background = Style.surface(this@CameraActivity, radiusDp = 14f)
            addView(cardContent, LinearLayout.LayoutParams(0, -1, 1f))
            addView(pageDots, LinearLayout.LayoutParams(dp(8), -2))
            contentDescription = "胶片、相纸、LUT：左右滑动切换，长按在取景框里挑选"
            setOnClickListener { if (lookPage == 2) showLutSheet() else showFilmSheet() }
            setOnLongClickListener { if (pickerOpen) closePicker() else openPicker(); true }
            setOnTouchListener(cardSwipe())
        }
        // The picker strip, over the viewfinder's lower edge in place of the lenses.
        pickerTitle = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xffe8e4dc.toInt())
            setShadowLayer(4f, 0f, 0f, 0xcc000000.toInt())
        }
        picker = LookPicker(this).apply {
            onHover = { i -> haptics.play(Haptics.Event.TICK); schedulePick(i); markPickerGroup(i) }
            onSettle = { i -> commitPick(i) }
        }
        pickerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps on its edges do not reach the viewfinder
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x8c000000.toInt(), 0xcc000000.toInt()))
            setPadding(0, dp(22), 0, dp(6))
            addView(pickerTitle, LinearLayout.LayoutParams(-1, -2))
            addView(LookPicker.Marker(this@CameraActivity), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
            addView(picker, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(2) })
            pickerGroupTabs = LinearLayout(this@CameraActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }
            pickerTabScroll = android.widget.HorizontalScrollView(this@CameraActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(16))
                addView(pickerGroupTabs, android.widget.FrameLayout.LayoutParams(-2, -1))
            }
            pickerTabs = LinearLayout(this@CameraActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(pickerTabScroll, LinearLayout.LayoutParams(0, -1, 1f))
            }
            addView(pickerTabs, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })
            visibility = View.GONE
        }
        onBackPressedDispatcher.addCallback(this, pickerBack)
        onBackPressedDispatcher.addCallback(this, flashBack)
        // Landscape body: violet roller = exposure, amber roller = the look, a lens well, an HDR switch.
        val (eLight, eMid, eDark) = Toy.exposureRoller()
        val (lLight, lMid, lDark) = Toy.lookRoller()
        exposureRoller = Roller(this, eLight, eMid, eDark).apply {
            contentDescription = "曝光滚轮：左右滚动调整，点按切换或归零，长按切换自动 / 手动"
            onStep = { s -> if (store.flag(Settings.MANUAL)) stepManual(s) else changeEv(s) }
            onTap = {
                if (store.flag(Settings.MANUAL)) chooseManual(if (manualKey == Settings.ISO) Settings.SHUTTER else Settings.ISO)
                else if (evStops != 0.0) changeEv(-(evStops * 3).roundToInt())
            }
            onLongPress = { toggleManual() }
        }
        fun wheel(key: String, what: String) = org.photoall.film.ui.ScaleWheel(this).apply {
            val setting = Settings.byKey.getValue(key) as org.photoall.film.settings.Setting.Slider
            range = setting.min.toInt()..setting.max.toInt()
            value = store[key].toInt()
            labelOf = if (key == Settings.ISO) { t -> if (t % 3 == 0) "${Settings.iso(t.toFloat())}" else null }
                else { t -> if (t % 3 == 0) Settings.shutterLabel(t.toFloat()).replace("1/", "") else null }
            contentDescription = "$what：上下拨动调整，长按切换自动 / 手动"
            onStep = { s -> stepManual(s, key) }
            onLongPress = { toggleManual() }
        }
        isoWheel = wheel(Settings.ISO, "ISO")
        shutterWheel = wheel(Settings.SHUTTER, "快门")
        lookRoller = Roller(this, lLight, lMid, lDark).apply {
            contentDescription = "胶卷滚轮：左右滚动换片，点按换类别，长按在取景框里挑选"
            onStep = { s -> stepLook(s) }
            onTap = {
                lookPage = (lookPage + 1) % 3
                haptics.play(Haptics.Event.SEGMENT)
                coach?.event("page")
                updateLabels()
                if (pickerOpen) fillPicker(animate = true)
            }
            onLongPress = { if (pickerOpen) closePicker() else openPicker() }
        }
        lensWell = LensWell(this).apply {
            contentDescription = "焦距：拖动旋钮选择镜头"
            onSelect = { i -> options.getOrNull(i)?.let { selectLens(it) } }
        }
        hdrSwitch = SlideSwitch(this).apply {
            onToggle = { on -> store[Settings.HDR] = if (on) 1f else 0f; haptics.play(Haptics.Event.SEGMENT) }
        }
        amPill = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, 0, 0, dp(5)) // the key's face sits above its side
            setOnClickListener { toggleManual() }
        }
        fun readout(color: Int) = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setTextColor(color)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        exposureReadout = readout(eMid)
        lookReadout = readout(lMid)
        counterReadout = readout(Toy.INK).apply { textSize = 16f }
        // EV: a button that shows the bias and opens a ruler in thirds of a stop.
        evButton = LinearLayout(this).apply {
            Style.pressable(this)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Style.surface(this@CameraActivity, radiusDp = 14f)
            contentDescription = "曝光补偿"
            addView(evLabel, LinearLayout.LayoutParams(-2, -2))
            addView(label(11f).apply { text = "曝光"; setTextColor(Style.TEXT_2) }, LinearLayout.LayoutParams(-2, -2))
            setOnLongClickListener {
                // hold to switch between automatic and manual exposure
                val manual = !store.flag(Settings.MANUAL)
                store[Settings.MANUAL] = if (manual) 1f else 0f
                status.text = if (manual) "手动曝光：左右拖动刻度调 ISO 和快门（长按此键回到自动）" else "已回到自动曝光"
                haptics.play(Haptics.Event.LOCKED)
                true
            }
            setOnClickListener {
                if (store.flag(Settings.MANUAL)) {
                    if (dialPinned) status.text = "长按“曝光”回到自动曝光"
                    else { evOpen = !evOpen; haptics.play(Haptics.Event.SEGMENT); updateLabels() }
                    return@setOnClickListener
                }
                if (dialPinned) {
                    // the ruler is always out: the button returns the bias to zero
                    if (evStops != 0.0) changeEv(-(evStops * 3).roundToInt())
                    return@setOnClickListener
                }
                evOpen = !evOpen
                haptics.play(Haptics.Event.SEGMENT)
                updateLabels()
            }
        }
        evDial = RulerDial(this).apply {
            onStep = { changeEv(it) }
            onReset = { changeEv(-(evStops * 3).roundToInt()) }
        }
        dialRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.surface(this@CameraActivity, radiusDp = 14f)
            setPadding(0, 0, 0, 0)
            contentDescription = "曝光刻度：左右拖动按 1/3 档调整，双击归零"
            addView(evDial, LinearLayout.LayoutParams(-1, -2))
            visibility = View.GONE
        }
        manualDial = RulerDial(this).apply {
            onStep = { stepManual(it) }
            onReset = {
                // double tap: back to the settings' defaults (ISO 200, 1/125)
                store[manualKey] = Settings.byKey.getValue(manualKey).default
                haptics.play(Haptics.Event.STOP)
            }
        }
        isoChip = manualChip("ISO") { chooseManual(Settings.ISO) }
        shutterChip = manualChip("快门") { chooseManual(Settings.SHUTTER) }
        manualRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Style.surface(this@CameraActivity, radiusDp = 14f)
            setPadding(dp(6), 0, 0, 0)
            contentDescription = "手动曝光：点 ISO 或快门选择，左右拖动刻度调整"
            // the two values as a little segmented pill; the ruler moves the raised one
            addView(LinearLayout(this@CameraActivity).apply {
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = GradientDrawable().apply { cornerRadius = dp(17).toFloat(); setColor(Style.BG) }
                addView(isoChip, LinearLayout.LayoutParams(-2, dp(28)))
                addView(shutterChip, LinearLayout.LayoutParams(-2, dp(28)).apply { marginStart = dp(2) })
            }, LinearLayout.LayoutParams(-2, -2))
            addView(manualDial, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            visibility = View.GONE
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
            // the EV ruler and manual focus rows open and close smoothly
            layoutTransition = android.animation.LayoutTransition().apply {
                enableTransitionType(android.animation.LayoutTransition.CHANGING)
                setDuration(220)
            }
        }
        // Shown for a few seconds when the phone is turned; tapping it switches layout.
        rotateButton = org.photoall.film.ui.RotateHint(this).apply {
            setOnClickListener { haptics.play(Haptics.Event.LOCKED); rotateTo(offeredOrientation) }
        }
        viewfinderFrame = ViewfinderFrame(this).apply {
            addView(viewfinder, FrameLayout.LayoutParams(-1, -1))
            addView(curtain, FrameLayout.LayoutParams(-1, -1)) // under the corner mask
            addView(this@CameraActivity.overlay, FrameLayout.LayoutParams(-1, -1)) // not View.overlay
            addView(lensRow, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
            addView(facingButton, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(12); marginEnd = dp(12)
            })
            addView(pickerPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            // landscape manual focus: along the right edge, below the histogram
            // its top clears the histogram (10 dp down, 56 dp tall), it runs to the bottom
            addView(focusRail, FrameLayout.LayoutParams(dp(120), -1, Gravity.END).apply {
                topMargin = dp(76); bottomMargin = dp(10); marginEnd = dp(6)
            })
            // the rotate offer: a quarter of glass in the picture's lower left corner
            addView(rotateButton, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.BOTTOM or Gravity.START))
        }
        attachLayout()
        startOrientationWatch()
        sweepLeftovers()
        // adb: `am start ... --ei orientation 0` opens straight in landscape (layout checks).
        if (intent.hasExtra("orientation")) chosenOrientation = intent.getIntExtra("orientation", 1)
        savedInstanceState?.let { chosenOrientation = it.getInt("orientation", chosenOrientation) }
        if (chosenOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) requestedOrientation = chosenOrientation

        store.onChange { key -> onSettingChanged(key) }
        updateLabels()
        status.text = "正在启动胶片引擎…"
        engineThread.later {
            try {
                engine = FilmEngine.create(assets)
                runOnUiThread { status.text = "GPU：${engine?.deviceName}" }
                bakeLut()
            } catch (e: Throwable) {
                runOnUiThread { status.text = "引擎无法启动：${e.message}" }
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    /**
     * Portrait (the design's 取景 board): the top bar, the 3:4 viewfinder, then the lens
     * chips, the film card with the EV button, and the thumbnail / shutter / settings row
     * in the thumb's reach. Landscape: viewfinder | scrolling column of the same controls
     * (top bar first) | shutter rail. The views are built once and re-parented.
     */
    private fun attachLayout() {
        flashDock?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        flashDock = null
        flashBack.isEnabled = false
        overlay.readoutAlpha = 1f
        overlay.histogramAlpha = 1f
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        fillTopBar(landscape)
        fillControls(landscape)
        if (provider != null) buildZoomButtons() // lens button widths follow the orientation
        (controls.parent as? android.view.ViewGroup)?.removeView(controls)
        (topBar.parent as? android.view.ViewGroup)?.removeView(topBar)
        viewfinderFrame.landscape = landscape
        // The live viewfinder never leaves the stage (taking a GL surface out of the window
        // ends it, and the camera with it); each layout holds an empty slot of its shape,
        // and the stage lays the viewfinder over the slot.
        if (!::stage.isInitialized) {
            stage = Stage()
            setContentView(stage)
        }
        val slot = ViewfinderFrame(this).also { it.landscape = landscape }
        val finderHost = FrameLayout(this).apply {
            // portrait: right under the top bar (spare height goes below, toward the controls)
            addView(slot, FrameLayout.LayoutParams(-2, -2,
                if (landscape) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        for (v in listOf(thumbnail, shutter, quickButton)) (v.parent as? android.view.ViewGroup)?.removeView(v)
        styleForBody(landscape)
        frameCount.setTextColor(if (landscape) 0xfff2f0ea.toInt() else Style.TEXT)
        frameCount.textSize = if (landscape) 16f else 20f
        frameNote.setTextColor(if (landscape) 0xffb4ada3.toInt() else Style.TEXT_2)
        status.setTextColor(if (landscape) 0xffd8d4cc.toInt() else Style.TEXT_2)
        modeLabel.setTextColor(if (landscape) 0xffb4ada3.toInt() else Style.TEXT_2)
        stage.show(slot, LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            if (landscape) {
                // the phone as a toy camera: the viewfinder sunk in the middle, controls either side
                background = org.photoall.film.ui.ToyBackground(resources.displayMetrics.density)
                clipChildren = false
                addView(FitColumn(this@CameraActivity, toySide(left = true)), LinearLayout.LayoutParams(dp(158), -1))
                // the well wraps the picture exactly (no bands either side); the space left over
                // is body, not black
                finderHost.removeView(slot)
                finderHost.clipChildren = false
                finderHost.addView(FrameLayout(this@CameraActivity).apply {
                    background = org.photoall.film.ui.Well(resources.displayMetrics.density, 22f)
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    addView(slot, FrameLayout.LayoutParams(-2, -2))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                addView(finderHost, LinearLayout.LayoutParams(0, -1, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
                addView(FitColumn(this@CameraActivity, toySide(left = false)), LinearLayout.LayoutParams(dp(158), -1))
            } else {
                addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))
                // viewfinder and controls together under the top bar; spare height goes
                // below them, toward the shutter
                finderHost.removeView(slot)
                addView(FinderColumn(this@CameraActivity, slot, controls).apply {
                    onSpare = { spare, finder -> pinDial(spare, finder) }
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(gridRow(
                    cell(thumbnail, 1f, dp(52)),
                    cell(shutter, 1f, dp(80)),
                    cell(quickButton, 1f, dp(52)),
                    height = dp(96),
                ).apply { setPadding(dp(20), 0, dp(20), dp(4)) })
            }
            setOnApplyWindowInsetsListener { view, insets ->
                // Keep clear of the navigation bar and the camera cutout; the status bar is hidden.
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                if (landscape) view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                else {
                    // Portrait: the top bar uses the band beside the camera hole instead of
                    // sitting under it; only a wide notch in the middle pushes it down.
                    val top = topBarClearance(insets)
                    view.setPadding(bars.left, top, bars.right, bars.bottom)
                }
                insets
            }
        })
        updateFocusControls() // portrait shows the focus row, landscape the rail
        hideStatusBar()
    }

    // ----- landscape: the toy camera body ---------------------------------------------------------

    /**
     * Left hand: settings and flash, A/M and the HDR switch, the exposure roller, the album
     * and the settings sheet. Right hand: the frame counter and lens flip, the lens well,
     * the shutter, the look roller.
     */
    private fun toySide(left: Boolean): View {
        val density = resources.displayMetrics.density
        fun detach(vararg v: View) = v.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        fun caption(text: String) = TextView(this).apply {
            this.text = text
            textSize = 9.5f
            letterSpacing = 0.06f
            setTextColor(Toy.INK2)
            maxLines = 1
        }
        /** A key sitting in its socket: the socket a little larger, the key free to sink into it. */
        fun socket(v: View, key: Int, radiusDp: Float = -1f): View {
            detach(v)
            return FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                background = org.photoall.film.ui.Well(density, radiusDp)
                addView(v, FrameLayout.LayoutParams(key, key, Gravity.CENTER))
            }
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            views.forEachIndexed { i, v ->
                val lp = v.layoutParams as? LinearLayout.LayoutParams
                addView(v, (lp ?: LinearLayout.LayoutParams(-2, -2)).apply { if (i > 0) marginStart = dp(10) })
            }
        }
        val keySize = dp(50)
        val sock = dp(58)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        if (left) {
            detach(exposureReadout, exposureRoller, hdrSwitch, isoWheel, shutterWheel)
            val settings = socket(settingsIcon, keySize).apply { layoutParams = LinearLayout.LayoutParams(sock, sock) }
            val flash = socket(flashIcon, keySize).apply { layoutParams = LinearLayout.LayoutParams(sock, sock) }
            val am = socket(amPill, keySize).apply { layoutParams = LinearLayout.LayoutParams(sock, sock) }
            hdrSwitch.layoutParams = LinearLayout.LayoutParams(sock, dp(34))
            val album = socket(thumbnail, keySize, 16f).apply { layoutParams = LinearLayout.LayoutParams(sock, sock) }
            val sheet = socket(quickButton, keySize).apply { layoutParams = LinearLayout.LayoutParams(sock, sock) }
            col.addView(row(settings, flash))
            col.addView(row(am, hdrSwitch), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            col.addView(caption("A / M          HDR"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            // auto: the exposure roller; manual: ISO and shutter wheels side by side, in the same place
            val auto = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                addView(exposureReadout, LinearLayout.LayoutParams(-1, -2))
                addView(exposureRoller, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(3) })
                addView(caption("曝光 · 长按切 A/M"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
            // the value is read off the wheel at its index mark: no figures above it
            fun wheelColumn(wheel: View, name: String) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                clipChildren = false
                addView(wheel, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(caption(name).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
            val manual = LinearLayout(this).apply {
                clipChildren = false
                addView(wheelColumn(isoWheel, "ISO"), LinearLayout.LayoutParams(0, -1, 1f))
                addView(wheelColumn(shutterWheel, "快门 S"), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(6) })
            }
            val block = FrameLayout(this).apply {
                clipChildren = false
                addView(auto, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
                addView(manual, FrameLayout.LayoutParams(-1, -1).apply { marginStart = -dp(6); marginEnd = -dp(6) })
            }
            exposureBlock = auto
            manualBlock = manual
            blocksManual = null
            col.addView(block, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
            col.addView(row(album, sheet), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            syncExposureBlocks(animate = false)
        } else {
            detach(counterReadout, lensWell, lookReadout, lookRoller, lookCaption)
            lookCaption.textSize = 9.5f
            lookCaption.letterSpacing = 0.06f
            lookCaption.setTextColor(Toy.INK2)
            lookCaption.maxLines = 1
            val flip = socket(facingButton, dp(38)).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
            col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                addView(counterReadout, LinearLayout.LayoutParams(0, -2, 1f))
                addView(flip)
            })
            col.addView(caption("焦距"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            col.addView(lensWell, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3) })
            // the shutter: a matte white disc in a dark ring
            col.addView(FrameLayout(this).apply {
                clipChildren = false
                addView(socket(shutter, dp(96)), FrameLayout.LayoutParams(dp(106), dp(106), Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, dp(114)).apply { topMargin = dp(8) })
            col.addView(lookReadout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            col.addView(lookRoller, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(3) })
            col.addView(lookCaption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }
        return col
    }

    /** Raised keys, the chrome shutter and light ink on the body; the flat look in portrait. */
    private fun styleForBody(body: Boolean) {
        val density = resources.displayMetrics.density
        for (v in listOf(settingsIcon, flashIcon, quickButton, amPill)) {
            v.background = if (body) SoftKey(density, depth = 5f) else when (v) {
                quickButton -> Style.surface(this, radiusDp = 26f)
                else -> Style.surface(this, fill = Color.TRANSPARENT, radiusDp = 24f)
            }
            if (body) Toy.press(v, 2.5f * density) else v.stateListAnimator = null
        }
        if (!body) Style.pressable(quickButton)
        // portrait: the front / back switch goes back onto the picture's lower right
        if (!body && facingButton.parent !== viewfinderFrame) {
            (facingButton.parent as? android.view.ViewGroup)?.removeView(facingButton)
            viewfinderFrame.addView(facingButton, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(12); marginEnd = dp(12)
            })
        }
        facingButton.background = if (body) SoftKey(density, depth = 4f) else
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80151515.toInt()) }
        if (body) Toy.press(facingButton, 2 * density) else Style.pressable(facingButton)
        // its icon in the body's ink (dark on a light theme's keys), light on the picture in portrait
        facingButton.setImageDrawable(Icon(Icon.Kind.FLIP, if (body) Toy.INK else 0xfff2f0ea.toInt(), 1.8f))
        // icons sit a little higher on a raised key (its face is above its side)
        for (v in listOf(settingsIcon, flashIcon, quickButton))
            if (body) v.setPadding(dp(15), dp(14), dp(15), dp(16)) else v.setPadding(dp(13), dp(13), dp(13), dp(13))
        quickButton.setImageDrawable(Icon(Icon.Kind.GRID, if (body) Toy.INK else Style.TEXT))
        settingsIcon.setImageDrawable(Icon(Icon.Kind.TUNE, if (body) Toy.INK else Style.TEXT))
        shutter.background = if (body) SoftKey(density, depth = 7f,
            face = intArrayOf(0xfff6f5f2.toInt(), 0xffe4e3df.toInt(), 0xffc7c6c1.toInt())) else Style.shutter(this)
        if (body) Toy.press(shutter, 3 * density) else shutter.stateListAnimator = null
        // the thumbnail in its socket: inset a little, rounded like it
        thumbnail.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Style.SURFACE); if (!body) setStroke(1, Style.HAIRLINE) }
        if (body) Toy.press(thumbnail, 2 * density) else thumbnail.stateListAnimator = null
        // in landscape the lens well replaces the lens buttons on the picture
        lensRow.visibility = if (body) View.GONE else View.VISIBLE
        // the status line rides on the picture's lower left in landscape
        (status.parent as? android.view.ViewGroup)?.removeView(status)
        status.background = null
        status.setPadding(0, 0, 0, 0)
        // landscape: the picture's corners follow the well's (the overlay masks the GL
        // surface; the frame's own children are clipped to the same shape)
        overlay.cornerRadius = if (body) dp(17).toFloat() else 0f
        overlay.cornerColor = Toy.SOCKET
        viewfinderFrame.outlineProvider = if (body) object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(17).toFloat())
        } else android.view.ViewOutlineProvider.BACKGROUND
        viewfinderFrame.clipToOutline = body
    }

    private fun toggleManual() {
        haptics.play(Haptics.Event.LOCKED)
        if (store.flag(Settings.MANUAL)) leaveManual() else enterManual()
    }

    /** The look roller: one step through films, papers or LUTs (the card's category). */
    private fun stepLook(step: Int) {
        val count = when (lookPage) { 0 -> FilmEngine.filmStocks.size + 1; 1 -> FilmEngine.papers.size + 1; else -> LutLibrary.count }
        val now = lookIndex(lookPage)
        val next = now + step
        if (next < 0 || next >= count) { haptics.play(Haptics.Event.LIMIT); return }
        haptics.play(Haptics.Event.TICK)
        applyPick(lookPage, next)
    }

    /** Where the look in use sits in the picker's list for [page] (0 = straight / scan / none). */
    private fun lookIndex(page: Int) = when (page) {
        0 -> if (store.flag(Settings.FILM_ON)) store["film"].toInt() + 1 else 0
        1 -> if (store.flag(Settings.PRINT_ON)) store["paper"].toInt() + 1 else 0
        else -> if (store.flag(Settings.LUT_ON)) chosenLut() else 0
    }

    /**
     * Manual exposure starts from what the meter reads now, so nothing jumps when it begins.
     */
    private fun enterManual() {
        if (store.flag(Settings.MANUAL)) return
        exposure?.live?.let { live ->
            val iso = (3 * ln(live.iso / 100.0) / ln(2.0)).roundToInt().coerceIn(0, 18)
            val shutter = (3 * ln(live.timeNs / 1e9 * 8000) / ln(2.0)).roundToInt().coerceIn(0, 39)
            store[Settings.ISO] = iso.toFloat()
            store[Settings.SHUTTER] = shutter.toFloat()
        }
        store[Settings.MANUAL] = 1f
    }

    private fun leaveManual() {
        if (store.flag(Settings.MANUAL)) store[Settings.MANUAL] = 0f
    }

    /** The body's readouts, switch and A/M key follow the settings. */
    private fun showBody(manual: Boolean, hdrOn: Boolean) {
        amPill.text = if (manual) "M" else "A"
        amPill.setTextColor(if (manual) Toy.AMBER else Toy.INK)
        amPill.contentDescription = if (manual) "手动曝光（点按回到自动）" else "自动曝光（点按切到手动）"
        if (hdrSwitch.on != hdrOn) hdrSwitch.set(hdrOn, animate = hdrSwitch.isAttachedToWindow)
        syncExposureBlocks()
        exposureReadout.swapText(if (manual) {
            if (manualKey == Settings.ISO) "ISO ${Settings.iso(store[Settings.ISO])}" else Settings.shutterLabel(store[Settings.SHUTTER])
        } else if (evStops == 0.0) "EV ±0.0" else "EV %+.1f".format(evStops))
        val name = when (lookPage) {
            0 -> if (store.flag(Settings.FILM_ON)) CanisterView.displayName(filmName()) else "直出"
            1 -> if (store.flag(Settings.PRINT_ON)) paperName(FilmEngine.papers[store["paper"].toInt().coerceIn(0, FilmEngine.papers.lastIndex)]) else "直接扫描"
            else -> if (store.flag(Settings.LUT_ON) && chosenLut() != 0) LutLibrary.name(chosenLut()) else "无 LUT"
        }
        lookReadout.swapText(name)
        lookCaption.text = "${lookNames[lookPage]} · 点按换类"
        counterReadout.swapText("${rollFrame() + 1}/$ROLL_LENGTH")
    }

    // ----- the look card and its picker -------------------------------------------------------

    private val lookNames = listOf("胶卷", "相纸", "LUT")

    /** The card's page: its picture, name, and a line naming the category in the accent. */
    private fun fillCard(filmOn: Boolean, printOn: Boolean, paper: String, lut: Pair<Int, Float>) {
        canister.visibility = if (lookPage == 0) View.VISIBLE else View.GONE
        swatch.visibility = if (lookPage == 0) View.GONE else View.VISIBLE
        val detail = when (lookPage) {
            0 -> {
                recipeSummary.text = if (filmOn) CanisterView.displayName(filmName()) else "直出（无胶片）"
                if (filmOn) Regex("(\\d+)[a-z]?$").find(filmName())?.groupValues?.get(1)?.let { "ISO $it" } ?: "" else "不套胶片"
            }
            1 -> {
                swatch.show(if (printOn) Swatch.Kind.PAPER else Swatch.Kind.SCAN, paper)
                recipeSummary.text = if (printOn) paperName(paper) else "直接扫描"
                if (!filmOn) "直出时不用" else if (printOn) "印放" else "不印放，扫描底片"
            }
            else -> {
                val chosen = lut.first
                swatch.show(if (chosen == 0) Swatch.Kind.NONE else Swatch.Kind.LUT, LutLibrary.name(chosen))
                recipeSummary.text = if (chosen == 0) "无 LUT" else LutLibrary.name(chosen)
                if (chosen == 0) "不套 LUT" else "${LutLibrary.group(chosen)} · %.0f%%".format(lut.second * 100)
            }
        }
        recipeSub.text = android.text.SpannableStringBuilder().apply {
            append(lookNames[lookPage])
            setSpan(android.text.style.ForegroundColorSpan(Style.ACCENT), 0, length, 0)
            if (detail.isNotEmpty()) append("  ").append(detail)
        }
        for (i in 0 until pageDots.childCount) pageDots.getChildAt(i).background =
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == lookPage) Style.ACCENT else Style.TEXT_3) }
    }

    /** Sideways drags on the card turn its page; taps and long presses stay the card's. */
    private fun cardSwipe() = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val slop = android.view.ViewConfiguration.get(this@CameraActivity).scaledTouchSlop

        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(e.rawY - downY)) {
                        dragging = true
                        v.cancelLongPress()
                        v.isPressed = false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        cardContent.translationX = dx * 0.6f
                        cardContent.alpha = 1f - (abs(dx) / v.width).coerceAtMost(0.6f)
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    val dx = e.rawX - downX
                    turnPage(if (e.actionMasked == android.view.MotionEvent.ACTION_UP && abs(dx) > dp(44)) (if (dx < 0) 1 else -1) else 0)
                    // the card must not take the end of a drag as a tap
                    val cancel = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    v.onTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
            }
            return false
        }
    }

    /** The next (1) or previous (-1) page slides in; 0 springs the card back. */
    private fun turnPage(step: Int) {
        if (step == 0) {
            cardContent.animate().translationX(0f).alpha(1f).setDuration(180)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
            return
        }
        haptics.play(Haptics.Event.SEGMENT)
        coach?.event("page")
        val w = recipeCard.width.toFloat()
        cardContent.animate().translationX(-step * w * 0.5f).alpha(0f).setDuration(110)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
                lookPage = (lookPage + step + 3) % 3
                updateLabels()
                cardContent.translationX = step * w * 0.35f
                cardContent.animate().translationX(0f).alpha(1f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
                if (pickerOpen) fillPicker(animate = true)
            }.start()
    }

    /** Long press: the lenses step aside and the looks of the card's category rise in their place. */
    private fun openPicker() {
        pickerOpen = true
        pickerBack.isEnabled = true
        haptics.play(Haptics.Event.LOCKED)
        fillPicker(animate = false)
        val portraitNow = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        for (v in if (portraitNow) listOf(lensRow, facingButton) else emptyList())
            v.animate().alpha(0f).translationY(dp(10).toFloat()).scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(140)
                .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction { v.visibility = View.INVISIBLE }.start()
        // The shade rises first; the title, the looks (from the middle outwards) and the
        // tabs follow it in turn, each landing softly.
        val ease = LookPicker.EASE
        pickerPanel.animate().cancel()
        pickerPanel.visibility = View.VISIBLE
        pickerPanel.alpha = 0f
        pickerPanel.translationY = dp(28).toFloat()
        pickerPanel.animate().alpha(1f).translationY(0f).setStartDelay(40).setDuration(320).setInterpolator(ease).start()
        fun rise(v: View, delay: Long, by: Int) {
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp(by).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(340).setInterpolator(ease).start()
        }
        rise(pickerTitle, 90, 10)
        picker.bloom(dp(26).toFloat())
        rise(pickerTabs, 200, 14)
        coach?.event("picker_open")
    }

    private fun closePicker() {
        if (!pickerOpen) return
        pickerOpen = false
        coach?.event("picker_close")
        main.removeCallbacks(fadePickerHint)
        syncLutDial()
        pickerBack.isEnabled = false
        pendingPick?.let { main.removeCallbacks(it); it.run() }
        pendingPick = null
        // the reverse, quicker: tabs and title go first, the looks drop, the shade sinks last
        val accel = android.view.animation.AccelerateInterpolator(1.4f)
        pickerTabs.animate().cancel()
        pickerTabs.animate().alpha(0f).translationY(dp(10).toFloat()).setStartDelay(0).setDuration(120).setInterpolator(accel).start()
        pickerTitle.animate().cancel()
        pickerTitle.animate().alpha(0f).translationY(dp(6).toFloat()).setStartDelay(0).setDuration(120).setInterpolator(accel).start()
        picker.sink(dp(22).toFloat(), 150)
        pickerPanel.animate().cancel()
        pickerPanel.animate().alpha(0f).translationY(dp(24).toFloat()).setStartDelay(70).setDuration(190)
            .setInterpolator(accel).withEndAction { pickerPanel.visibility = View.GONE }.start()
        val portrait = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        for (v in if (portrait) listOf(lensRow, facingButton) else emptyList()) {
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(170).setDuration(300)
                .setInterpolator(LookPicker.EASE).start()
        }
    }

    /** The strip for the card's category, with the look in use in the middle. */
    private fun fillPicker(animate: Boolean) {
        val (items, selected) = when (lookPage) {
            0 -> (listOf(LookPicker.Item("直出") { c -> CanisterView(c) }) + FilmEngine.filmStocks.map { stock ->
                LookPicker.Item(CanisterView.displayName(stock)) { c -> CanisterView(c).apply { this.stock = stock } }
            }) to (if (store.flag(Settings.FILM_ON)) store["film"].toInt() + 1 else 0)
            1 -> (listOf(LookPicker.Item("直接扫描") { c -> Swatch(c).apply { show(Swatch.Kind.SCAN) } }) + FilmEngine.papers.map { paper ->
                LookPicker.Item(paperName(paper)) { c -> Swatch(c).apply { show(Swatch.Kind.PAPER, paper) } }
            }) to (if (store.flag(Settings.PRINT_ON)) store["paper"].toInt() + 1 else 0)
            else -> (0 until LutLibrary.count).map { i ->
                LookPicker.Item(LutLibrary.name(i)) { c -> Swatch(c).apply { show(if (i == 0) Swatch.Kind.NONE else Swatch.Kind.LUT, LutLibrary.name(i)) } }
            } to (if (store.flag(Settings.LUT_ON)) chosenLut() else 0)
        }
        pickerTitle.text = if (lookPage == 2) "LUT · 左右滑动挑选 · 在快门上画圈调强度" else "${lookNames[lookPage]} · 左右滑动挑选 · 点画面收起"
        showPickerHint()
        // the families of this page, each with its first look
        pickerGroupOf = when (lookPage) {
            0 -> { i -> FilmEngine.filmStocks.getOrNull(i - 1)?.let { org.photoall.film.settings.LookFamilies.film(it) } }
            1 -> { i -> FilmEngine.papers.getOrNull(i - 1)?.let { org.photoall.film.settings.LookFamilies.paper(it) } }
            else -> { i -> if (i == 0) null else LutLibrary.group(i).ifEmpty { null } }
        }
        val firsts = LinkedHashMap<String, Int>()
        for (i in items.indices) pickerGroupOf(i)?.let { g -> if (g !in firsts) firsts[g] = i }
        pickerGroups = (if (lookPage == 2) firsts.entries.sortedBy { if (it.key == LutLibrary.USER_GROUP) 0 else 1 } else firsts.entries)
            .map { it.key to it.value }
        shownPickerGroup = null
        fillPickerTabs()
        picker.show(items, selected)
        markPickerGroup(selected)
        syncLutDial()
        if (animate) {
            // the new page slides in
            picker.animate().cancel()
            picker.alpha = 0f
            picker.translationX = dp(36).toFloat()
            picker.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(260).setInterpolator(LookPicker.EASE).start()
            picker.bloom(dp(10).toFloat(), quick = true)
        }
    }

    /** The strip's how-to line: shown as the strip opens or changes page, gone after four seconds. */
    private val fadePickerHint = Runnable {
        pickerTitle.animate().alpha(0f).setStartDelay(0).setDuration(600)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun showPickerHint() {
        main.removeCallbacks(fadePickerHint)
        if (pickerTitle.alpha < 1f) pickerTitle.animate().alpha(1f).setStartDelay(0).setDuration(200).start()
        main.postDelayed(fadePickerHint, 4000)
    }

    /** The shutter's strength ring comes in (a LUT being picked) or goes, sweeping. */
    private fun syncLutDial() {
        val want = pickerOpen && lookPage == 2 && store.flag(Settings.LUT_ON) && chosenLut() != 0
        if (!lutDialDragging) lutDial.strength = store[Settings.LUT_STRENGTH]
        if (want == lutDialOn) return
        lutDialOn = want
        lutDialAnim?.cancel()
        lutDialAnim = android.animation.ValueAnimator.ofFloat(lutDial.reveal, if (want) 1f else 0f).apply {
            duration = if (want) 460 else 240
            interpolator = if (want) LookPicker.EASE else android.view.animation.AccelerateInterpolator(1.4f)
            addUpdateListener { lutDial.reveal = it.animatedValue as Float; shutter.invalidate() }
            start()
        }
        if (want) {
            // the ring's glow reaches past the button: its holders must not clip it
            var holder = shutter.parent as? android.view.ViewGroup
            repeat(3) { holder?.clipChildren = false; holder?.clipToPadding = false; holder = holder?.parent as? android.view.ViewGroup }
            // the button gives a small turn as it becomes a dial
            shutter.animate().cancel()
            shutter.rotation = -20f
            shutter.animate().rotation(0f).setDuration(460).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        }
    }

    private fun animateDialActive(to: Float) {
        lutDialActiveAnim?.cancel()
        lutDialActiveAnim = android.animation.ValueAnimator.ofFloat(lutDial.active, to).apply {
            duration = if (to > 0f) 180 else 320
            addUpdateListener { lutDial.active = it.animatedValue as Float; shutter.invalidate() }
            start()
        }
    }

    /**
     * Circles drawn on the shutter turn the strength ring while it is out: clockwise for
     * more, anticlockwise for less, a whole turn for the whole range, so the arc's end
     * follows the finger round. The finger may wander past the button; near its centre
     * nothing turns (the angle there is noise). A tap is still the shutter.
     */
    private fun lutDialTouch() = object : View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var cx = 0f
        var cy = 0f
        var lastAngle: Double? = null
        var turned = 0.0 // degrees, clockwise positive
        var start = 0f
        var lastStep = 0
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            if (!lutDialOn && !lutDialDragging) return false
            val slop = android.view.ViewConfiguration.get(v.context).scaledTouchSlop
            fun angle(): Double? {
                val rx = e.rawX - cx
                val ry = e.rawY - cy
                return if (Math.hypot(rx.toDouble(), ry.toDouble()) < dp(10)) null
                else Math.toDegrees(Math.atan2(ry.toDouble(), rx.toDouble())) // screen y down: + is clockwise
            }
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val at = IntArray(2).also { v.getLocationOnScreen(it) }
                    cx = at[0] + v.width / 2f
                    cy = at[1] + v.height / 2f
                    downX = e.rawX; downY = e.rawY
                    turned = 0.0
                    lastAngle = angle()
                    start = store[Settings.LUT_STRENGTH]
                    lastStep = (start * 20).roundToInt()
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val a = angle()
                    val prev = lastAngle
                    if (a != null && prev != null) {
                        var d = a - prev
                        if (d > 180) d -= 360
                        if (d < -180) d += 360
                        turned += d
                    }
                    lastAngle = a
                    val moved = Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble())
                    if (!lutDialDragging && (Math.abs(turned) > 6 || moved > slop * 2)) {
                        lutDialDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        animateDialActive(1f)
                    }
                    if (lutDialDragging) {
                        val strength = (start + turned / 360.0).toFloat().coerceIn(0f, 1f)
                        lutDial.strength = strength
                        shutter.invalidate()
                        val step = (strength * 20).roundToInt() // a click every 5 %
                        if (step != lastStep) {
                            haptics.play(if (step == 0 || step == 20) Haptics.Event.LIMIT else if (step % 2 == 0) Haptics.Event.STOP else Haptics.Event.TICK)
                            lastStep = step
                        }
                        // past an end the turn does not pile up: turning back moves at once
                        if (start + turned / 360.0 > 1.0) turned = (1.0 - start) * 360.0
                        if (start + turned / 360.0 < 0.0) turned = -start * 360.0
                        val q = (strength * 100).roundToInt() / 100f
                        if (store[Settings.LUT_STRENGTH] != q) store[Settings.LUT_STRENGTH] = q
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
                    if (lutDialDragging) {
                        lutDialDragging = false
                        animateDialActive(0f)
                        status.text = "LUT 强度 ${(store[Settings.LUT_STRENGTH] * 100).roundToInt()}%"
                        coach?.event("lut_strength")
                    } else if (e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                }
            }
            return true
        }
    }

    /** The page's families; a page without them keeps the row's room, empty (no jump in height). */
    private fun fillPickerTabs() {
        pickerGroupTabs.removeAllViews()
        pickerTabScroll.scrollTo(0, 0)
        pickerTabScroll.visibility = if (pickerGroups.size < 2) View.INVISIBLE else View.VISIBLE
        fun tab(text: String, on: Boolean, strong: Boolean, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            tag = text
            textSize = if (strong) 12.5f else 12f
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setPadding(dp(11), 0, dp(11), 0)
            setOnClickListener { onClick() }
            styleTab(this, on, strong)
        }
        if (pickerGroups.size < 2) return
        for ((g, first) in pickerGroups)
            pickerGroupTabs.addView(tab(g, false, false) {
                haptics.play(Haptics.Event.SEGMENT)
                picker.jumpTo(first)
            }, LinearLayout.LayoutParams(-2, dp(28)).apply { marginEnd = dp(4) })
    }

    private fun styleTab(v: TextView, on: Boolean, strong: Boolean) {
        v.setTextColor(if (on) 0xff1a1712.toInt() else if (strong) 0xfff2f0ea.toInt() else 0xffd8d4cc.toInt())
        v.typeface = if (on || strong) Style.medium else android.graphics.Typeface.DEFAULT
        v.setShadowLayer(if (on) 0f else 4f, 0f, 0f, 0xcc000000.toInt())
        v.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (on) setColor(0xfff2f0ea.toInt()) else { setColor(0x33000000); setStroke(1, 0x40f2f0ea) }
        }
    }

    /** The family of the look in the middle lights up, and its tab comes into view. */
    private fun markPickerGroup(index: Int) {
        val g = pickerGroupOf(index)
        if (g == shownPickerGroup) return
        shownPickerGroup = g
        for (i in 0 until pickerGroupTabs.childCount) {
            val v = pickerGroupTabs.getChildAt(i) as? TextView ?: continue
            styleTab(v, v.tag == g, false)
            if (v.tag == g) pickerTabScroll.post {
                pickerTabScroll.smoothScrollTo((v.left - (pickerTabScroll.width - v.width) / 2).coerceAtLeast(0), 0)
            }
        }
    }

    /** While scrolling: the look in the middle goes to the viewfinder after a short pause. */
    private fun schedulePick(index: Int) {
        pendingPick?.let { main.removeCallbacks(it) }
        val page = lookPage
        pendingPick = Runnable { pendingPick = null; applyPick(page, index) }.also { main.postDelayed(it, 160) }
    }

    private fun commitPick(index: Int) {
        pendingPick?.let { main.removeCallbacks(it) }
        pendingPick = null
        applyPick(lookPage, index)
    }

    /** Item [index] of the picker for [page] (0 is always "none": straight out, scan, no LUT). */
    private fun applyPick(page: Int, index: Int) {
        coach?.event("pick")
        fun set(key: String, value: Float) { if (store[key] != value) store[key] = value }
        when (page) {
            0 -> if (index == 0) set(Settings.FILM_ON, 0f) else { set("film", (index - 1).toFloat()); set(Settings.FILM_ON, 1f) }
            1 -> if (index == 0) set(Settings.PRINT_ON, 0f) else { set("paper", (index - 1).toFloat()); set(Settings.PRINT_ON, 1f) }
            else -> if (index == 0) set(Settings.LUT_ON, 0f) else if (!store.flag(Settings.LUT_ON) || chosenLut() != index) pickLut(index)
        }
    }

    /** One half of the ISO / shutter pill: its name and value on one line. */
    private fun manualChip(title: String, onClick: () -> Unit) = TextView(this).apply {
        tag = title
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(10), 0, dp(10), 0)
        Style.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun chooseManual(key: String) {
        if (manualKey == key) return
        manualKey = key
        haptics.play(Haptics.Event.SEGMENT)
        showManual()
    }

    /** The chips' values and the ruler for the chosen one (ISO 100..6400, 1/8000..1 s in thirds). */
    private fun showManual() {
        val iso = store[Settings.ISO].toInt()
        val shutter = store[Settings.SHUTTER].toInt()
        for ((chip, key, text) in listOf(Triple(isoChip, Settings.ISO, "${Settings.iso(iso.toFloat())}"),
            Triple(shutterChip, Settings.SHUTTER, Settings.shutterLabel(shutter.toFloat())))) {
            val on = key == manualKey
            chip.background = Style.surface(this, fill = if (on) Style.SURFACE_2 else Color.TRANSPARENT, radiusDp = 14f)
            // sizes in dp: the pill stays one line at any system font size
            chip.text = android.text.SpannableStringBuilder().apply {
                append(chip.tag as String, android.text.style.AbsoluteSizeSpan(10, true), 0)
                setSpan(android.text.style.ForegroundColorSpan(Style.TEXT_2), 0, length, 0)
                append(" ")
                val start = length
                append(text)
                setSpan(android.text.style.AbsoluteSizeSpan(13, true), start, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(if (on) Style.ACCENT else Style.TEXT), start, length, 0)
                setSpan(android.text.style.TypefaceSpan("monospace"), start, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
            }
        }
        val setting = Settings.byKey.getValue(manualKey) as org.photoall.film.settings.Setting.Slider
        manualDial.range = setting.min.toInt()..setting.max.toInt()
        manualDial.labelOf = if (manualKey == Settings.ISO) { t -> if (t % 3 == 0) "${Settings.iso(t.toFloat())}" else null }
            else { t -> if (t % 3 == 0) Settings.shutterLabel(t.toFloat()).replace("1/", "") else null }
        manualDial.thirds = store[manualKey].toInt()
    }

    /** One third of a stop on the manual ruler; past an end, the end-stop feel. */
    private fun stepManual(step: Int, key: String = manualKey) {
        val setting = Settings.byKey.getValue(key) as org.photoall.film.settings.Setting.Slider
        val wanted = store[key].toInt() + step
        if (wanted < setting.min.toInt() || wanted > setting.max.toInt()) {
            haptics.play(Haptics.Event.LIMIT)
            return
        }
        haptics.play(if (wanted % 3 == 0) Haptics.Event.STOP else Haptics.Event.TICK)
        store[key] = wanted.toFloat()
    }

    /**
     * Landscape: the exposure roller in auto, the ISO and shutter wheels in manual; on a
     * switch the one goes down into the body and the other comes up out of it.
     */
    private fun syncExposureBlocks(animate: Boolean = true) {
        val auto = exposureBlock ?: return
        val manual = manualBlock ?: return
        val on = store.flag(Settings.MANUAL)
        isoWheel.value = store[Settings.ISO].toInt()
        shutterWheel.value = store[Settings.SHUTTER].toInt()
        if (blocksManual == on) return
        blocksManual = on
        val show = if (on) manual else auto
        val hide = if (on) auto else manual
        show.animate().cancel(); hide.animate().cancel()
        if (!animate || !auto.isAttachedToWindow) {
            show.visibility = View.VISIBLE; show.alpha = 1f; show.translationY = 0f; show.scaleY = 1f
            hide.visibility = View.INVISIBLE
            return
        }
        val d = dp(26).toFloat()
        hide.animate().alpha(0f).translationY(d).scaleY(0.9f).setStartDelay(0).setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { hide.visibility = View.INVISIBLE; hide.translationY = 0f; hide.scaleY = 1f }.start()
        show.visibility = View.VISIBLE
        show.alpha = 0f; show.translationY = d; show.scaleY = 0.9f
        show.animate().alpha(1f).translationY(0f).scaleY(1f).setStartDelay(110).setDuration(300)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
    }

    /**
     * The EV ruler stays out under the controls whenever it fits: in the height left empty
     * there, or by letting the viewfinder give up at most a seventh of its height (a tall
     * screen at a large display size has no band to spare). Decided on the height without
     * the ruler, so showing it does not flip the decision back.
     */
    private fun pinDial(spare: Int, finder: Int) {
        val dial = listOf(dialRow, manualRow).filter { it.visibility == View.VISIBLE }.sumOf { it.height + dp(4) }
        val short = dp(48) - spare - dial
        val room = short <= 0 || short <= (finder + dial) / 7
        if (room == dialPinned) return
        dialPinned = room
        main.post { updateLabels() }
    }

    /**
     * Flash and HDR on the left; the frame counter and settings on the right. The middle
     * stays free for a centred camera hole (the histogram switch is in the settings sheet).
     */
    private fun fillTopBar(landscape: Boolean) {
        for (v in listOf(flashIcon, hdrButton, frameCount, frameNote, histogramIcon, settingsIcon))
            (v.parent as? android.view.ViewGroup)?.removeView(v)
        (frameCount.parent as? android.view.ViewGroup)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        topBar.removeAllViews()
        val icon = dp(if (landscape) 40 else 48)
        topBar.addView(flashIcon, LinearLayout.LayoutParams(icon, icon))
        topBar.addView(hdrButton, LinearLayout.LayoutParams(-2, dp(28)).apply { marginStart = dp(2) })
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (landscape) {
                // a frame counter window in the top plate
                background = org.photoall.film.ui.GlassWindow(resources.displayMetrics.density)
                setPadding(dp(10), 0, dp(10), 0)
            }
            addView(frameCount)
            addView(frameNote, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
        }, LinearLayout.LayoutParams(-2, if (landscape) dp(32) else -1).apply { marginEnd = dp(4) })
        topBar.addView(settingsIcon, LinearLayout.LayoutParams(icon, icon))
    }

    /**
     * Portrait: how far down the screen content starts. The top bar shares the status
     * band with a camera hole at the side or in the middle (its items step around a side
     * hole); a wide notch in the middle would cover the bar, so then it moves below.
     */
    private fun topBarClearance(insets: WindowInsets): Int {
        val cut = insets.displayCutout ?: return 0
        val width = window.decorView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        var left = dp(4)
        var right = dp(4)
        var below = 0
        for (r in cut.boundingRects) {
            if (r.top > dp(8)) continue // not at the top edge
            when {
                r.right < width * 0.4f -> left = maxOf(left, r.right + dp(4))
                r.left > width * 0.6f -> right = maxOf(right, width - r.left + dp(4))
                r.width() > width * 0.2f -> below = maxOf(below, r.bottom) // a wide notch
            }
        }
        topBar.setPadding(left, 0, right, 0)
        return below
    }

    /**
     * The control rows under the viewfinder: status, lenses, the film card with EV, the
     * film / paper / LUT rows when the card is open, the EV ruler, manual focus.
     */
    private fun fillControls(landscape: Boolean) {
        // No animation while the layout is rebuilt: a row removed with an animation keeps its
        // old parent until the animation ends, and could not be placed anywhere else.
        val transition = controls.layoutTransition
        controls.layoutTransition = null
        try { fillControlsNow(landscape) } finally { controls.layoutTransition = transition }
    }

    private fun fillControlsNow(landscape: Boolean) {
        // Rows wrap each view in a fresh cell: release the views from the old ones.
        val shared = listOf(status, modeLabel, recipeCard, evButton, dialRow, manualRow, mfRow)
        for (v in shared) (v.parent as? android.view.ViewGroup)?.removeView(v)
        controls.removeAllViews()
        if (landscape) return // the camera body holds them
        controls.setPadding(dp(if (landscape) 6 else 16), dp(4), dp(if (landscape) 6 else 16), dp(4))
        controls.apply {
            if (landscape) { // the column is narrow: status and mode get a line each
                addView(gridRow(cell(status, 1f), height = dp(24)))
                addView(gridRow(cell(modeLabel, 1f), height = dp(24)))
            } else addView(gridRow(cell(status, 1f), cell(modeLabel, 1f), height = dp(24)))
            addView(LinearLayout(this@CameraActivity).apply {
                addView(recipeCard, LinearLayout.LayoutParams(0, -1, 1f))
                addView(evButton, LinearLayout.LayoutParams(dp(if (landscape) 72 else 84), -1).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(4); bottomMargin = dp(4) })
            addView(dialRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(manualRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(mfRow)
        }
    }

    /** An icon in a 48dp touch target, with a ripple. */
    private fun iconButton(description: String, onClick: () -> Unit) = ImageView(this).apply {
        contentDescription = description
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = Style.surface(this@CameraActivity, fill = Color.TRANSPARENT, radiusDp = 24f)
        setOnClickListener { onClick() }
    }

    /**
     * Landscape: the viewfinder gets the full height (4:3, so 4/3 of it in width) and the
     * control column what is left beside it and the shutter rail, within 196-280 dp.
     */
    private fun landscapeColumnWidth(): Int {
        val dm = resources.displayMetrics
        val long = maxOf(dm.widthPixels, dm.heightPixels)
        val short = minOf(dm.widthPixels, dm.heightPixels)
        val finder = short * 4 / 3
        val spare = long - finder - dp(LANDSCAPE_RAIL_DP) - dp(40) // cutout and gaps
        return spare.coerceIn(dp(196), dp(280))
    }

    /** Full screen at the top: status bar hidden, swipe down to peek at it. */
    private fun hideStatusBar() {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // The turn animates itself (a frosted still flies into the new viewfinder): the
            // screen must change at once underneath. SEAMLESS falls back to the system's
            // crossfade or spin whenever a skin decides it cannot rotate seamlessly (RedMagic
            // does); JUMPCUT is a plain cut everywhere. Set ahead of any turn, so it has
            // reached the window manager when the orientation is requested.
            rotationAnimation = android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val generation = viewfinder.surfaceGeneration
        attachLayout()
        coach?.event("turn")
        // The viewfinder kept its surface and the camera keeps running: only the turn from
        // sensor to screen (preview, stills, metering) changes.
        applyDisplayRotation()
        if (turning) turnIn()
        // should the GL surface have been made anew on the way, the camera needs it handed over
        main.postDelayed({ if (viewfinder.surfaceGeneration != generation) bindCamera() }, 400)
    }

    /** The screen turned: the running camera's pictures turn with it, without a rebind. */
    private fun applyDisplayRotation() {
        val rotation = viewfinder.display?.rotation ?: android.view.Surface.ROTATION_0
        zsl?.let { session ->
            val r = zslRotation(session.characteristics)
            viewfinder.setDirectRotation(r)
            exposure?.sensorRotation = r
            return
        }
        previewCase?.targetRotation = rotation
        capture?.targetRotation = rotation
        analysisCase?.targetRotation = rotation
        camera?.let { exposure?.sensorRotation = it.cameraInfo.getSensorRotationDegrees(rotation) }
    }

    // ----- orientation: the layout only turns when asked ----------------------

    /**
     * The screen stays put when the phone turns; instead a small button appears in the
     * viewfinder corner for three seconds, upright for the way the phone is held, and
     * switches the layout when tapped. Turning back offers the way back.
     */
    /** The snapped device orientation last acted on (-1: act on the next reading). */
    private var orientationHeld = -1
    /** The tour waits for the phone to be turned: the rotate offer stays out. */
    private var coachTurn = false

    private fun startOrientationWatch() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                // Snap with a margin so a phone held near 45 degrees does not flicker.
                val snapped = when (degrees) {
                    in 300..360, in 0..60 -> 0
                    in 70..110 -> 90
                    in 250..290 -> 270
                    else -> return
                }
                if (snapped == orientationHeld) return
                orientationHeld = snapped
                // Device 270 (right side up) is LANDSCAPE, 90 (left side up) REVERSE_LANDSCAPE.
                val wanted = when (snapped) {
                    270 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                if (wanted == currentOrientation()) {
                    main.removeCallbacks(hideRotate)
                    rotateButton.dismiss()
                    return
                }
                offeredOrientation = wanted
                rotateButton.label = if (wanted == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "竖屏" else "横屏"
                rotateButton.toLandscape = wanted != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                // Upright for the user: undo both how the phone is held and how the UI is turned.
                val display = (viewfinder.display?.rotation ?: android.view.Surface.ROTATION_0) * 90
                rotateButton.contentRotation = ((360 - snapped - display) % 360 + 360) % 360f
                // the tour's turn step holds the offer out until it is taken
                val ms = if (coachTurn) 120_000L else 3000L
                rotateButton.appear(ms)
                main.removeCallbacks(hideRotate)
                main.postDelayed(hideRotate, ms)
            }
        }
    }

    /**
     * Files a shot leaves behind when it never finishes (the app closed or killed while
     * developing, a capture that failed half way): the burst's DNGs in the cache, 20-30 MB
     * each, and the JPEG being written. Anything there older than ten minutes is such a
     * leftover. Earlier builds' debug burst copies go too.
     */
    private fun sweepLeftovers() = Thread {
        val old = System.currentTimeMillis() - 10 * 60_000L
        cacheDir.listFiles()?.filter { f ->
            f.isFile && f.lastModified() < old && (f.name.endsWith(".dng") || (f.name.startsWith("save") && f.name.endsWith(".jpg")))
        }?.forEach { it.delete() }
        // the debug burst copies of earlier builds
        getExternalFilesDir("bursts")?.takeIf { it.exists() }?.deleteRecursively()
    }.apply { priority = Thread.MIN_PRIORITY; start() }

    private fun currentOrientation() = when (viewfinder.display?.rotation) {
        android.view.Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        android.view.Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun rotateTo(orientation: Int) {
        main.removeCallbacks(hideRotate)
        rotateButton.dismiss()
        chosenOrientation = orientation
        Log.i(TAG, "turn to $orientation (turning $turning)")
        // a turn that never finished (a callback a skin never delivered, the screen left
        // mid-way) must not block every later one
        if (turning && android.os.SystemClock.uptimeMillis() - turnStarted < 2500) return
        turning = true
        turnStarted = android.os.SystemClock.uptimeMillis()
        // A still of the picture frosts over it while the controls draw back; the screen
        // turns at once (no system animation) under the frost; the frosted still flies
        // from where it was on the glass into the new viewfinder, and clears to the live
        // picture there. The live viewfinder itself never moves (no stutter, no flash).
        val at = IntArray(2)
        viewfinderFrame.getLocationOnScreen(at)
        turnFrom = android.graphics.Rect(at[0], at[1], at[0] + viewfinderFrame.width, at[1] + viewfinderFrame.height)
        turnFromRotation = viewfinder.display?.rotation ?: android.view.Surface.ROTATION_0
        turnFromScreen = screenSize()
        turnFromCorner = overlay.cornerRadius
        (stage.body as? android.view.ViewGroup)?.let { turnParts(it, out = true) }
        fun frostAndTurn(still: android.graphics.Bitmap?) {
            turnStill = still?.let { frosted(it) }
            overlay.frost = turnStill
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TURN_OUT
                addUpdateListener { overlay.frostAlpha = it.animatedValue as Float }
            }.start()
            main.postDelayed({ requestedOrientation = orientation }, TURN_OUT + 20) // fixed until the next tap
            // a half turn (landscape to landscape) brings no configuration change
            main.postDelayed(turnStuck, TURN_OUT + 900)
        }
        val vw = viewfinder.width
        val vh = viewfinder.height
        // The still is a nicety: some skins never answer PixelCopy on a camera surface, so
        // the turn goes ahead without it after a short wait.
        var went = false
        fun go(still: android.graphics.Bitmap?) {
            if (went) return
            went = true
            Log.i(TAG, "turn: still ${if (still != null) "copied" else "skipped"}")
            frostAndTurn(still)
        }
        if (vw > 0 && vh > 0) {
            val still = android.graphics.Bitmap.createBitmap(maxOf(1, vw / 4), maxOf(1, vh / 4), android.graphics.Bitmap.Config.ARGB_8888)
            main.postDelayed({ go(null) }, 200)
            runCatching {
                android.view.PixelCopy.request(viewfinder, still, { result ->
                    go(if (result == android.view.PixelCopy.SUCCESS) still else null)
                }, main)
            }.onFailure { go(null) }
        } else go(null)
    }

    private var turnStill: android.graphics.Bitmap? = null

    /**
     * Frosted glass: the still shrunk to ~72 pixels across, box-blurred three times (close
     * to a gaussian), misted a little; drawn back up with filtering it is smooth frost.
     */
    private fun frosted(still: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = 72
        val h = maxOf(1, (still.height * w.toFloat() / still.width).roundToInt())
        val small = android.graphics.Bitmap.createScaledBitmap(still, w, h, true)
        if (small !== still) still.recycle()
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxBlur(px, tmp, w, h, 3, horizontal = true)
            boxBlur(tmp, px, w, h, 3, horizontal = false)
        }
        // mist: lift toward white a little
        val mist = if (Style.palette.light) 0.20f else 0.12f
        for (i in px.indices) {
            val c = px[i]
            fun ch(v: Int) = (v + (255 - v) * mist).toInt().coerceIn(0, 255)
            px[i] = (0xff shl 24) or (ch((c shr 16) and 0xff) shl 16) or (ch((c shr 8) and 0xff) shl 8) or ch(c and 0xff)
        }
        val out = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        small.recycle()
        return out
    }

    /** One pass of a box blur of [radius] along rows or columns, edges clamped. */
    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val n = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val c = at(i)
                r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
            }
            for (i in 0 until len) {
                val o = if (horizontal) line * w + i else i * w + line
                dst[o] = (0xff shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                val add = at(i + radius + 1)
                val sub = at(i - radius)
                r += ((add shr 16) and 0xff) - ((sub shr 16) and 0xff)
                g += ((add shr 8) and 0xff) - ((sub shr 8) and 0xff)
                b += (add and 0xff) - (sub and 0xff)
            }
        }
    }

    private fun rotateStill(b: android.graphics.Bitmap, degrees: Float): android.graphics.Bitmap =
        if (degrees % 360f == 0f) b
        else android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height,
            android.graphics.Matrix().apply { postRotate(degrees) }, true)

    @Suppress("DEPRECATION")
    private fun screenSize() = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }

    private var turnFrom: android.graphics.Rect? = null
    private var turnFromRotation = 0
    private var turnFromScreen = android.graphics.Point()
    private var turnFromCorner = 0f

    /**
     * Where a rectangle of the glass, in the screen's coordinates at rotation [from] on a
     * screen [size] (as it was), lies in the coordinates at rotation [to].
     */
    private fun mapAcrossTurn(r: android.graphics.Rect, from: Int, to: Int, size: android.graphics.Point): android.graphics.RectF {
        val w = size.x.toFloat()
        val h = size.y.toFloat()
        fun map(x: Float, y: Float): Pair<Float, Float> = when ((to - from + 4) % 4) {
            1 -> y to (w - x)
            2 -> (w - x) to (h - y)
            3 -> (h - y) to x
            else -> x to y
        }
        val a = map(r.left.toFloat(), r.top.toFloat())
        val b = map(r.right.toFloat(), r.bottom.toFloat())
        return android.graphics.RectF(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    // ----- the tour -----------------------------------------------------------------------

    private var coach: org.photoall.film.ui.Coach? = null

    /** The hands-on tour over the real controls; shown once by itself, again from the quick sheet. */
    private fun startCoach() {
        if (coach != null || isFinishing || isDestroyed) return
        if (pickerOpen) closePicker()
        closeFlashModes()
        // the card starts on the paper page: one swipe left reaches the styles, where the tour goes on
        if (lookPage != 1) { lookPage = 1; updateLabels() }
        val c = org.photoall.film.ui.Coach(this, coachSteps(), onAdvance = { haptics.play(Haptics.Event.SEGMENT) }) { _ ->
            coach = null
            getSharedPreferences("coach", MODE_PRIVATE).edit().putBoolean("camera", true).apply()
        }
        coach = c
        addContentView(c, android.view.ViewGroup.LayoutParams(-1, -1))
        c.start()
    }

    private fun openGithub() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(org.photoall.film.settings.GuideActivity.GITHUB))) }
    }

    private var coachPage = -1
    private var coachLutWas: Pair<Float, Float>? = null

    /** The tour's LUT step: the strip turns to the LUT page with a look on, so the ring is out. */
    private fun coachShowLut() {
        coachPage = lookPage
        coachLutWas = if (store.flag(Settings.LUT_ON) && chosenLut() != 0) null else store[Settings.LUT] to store[Settings.LUT_ON]
        if (!pickerOpen) openPicker()
        if (lookPage == 2 && coachLutWas == null) return // already a style on, the ring is out
        lookPage = 2
        coachLutWas?.let { pickLut(1.coerceAtMost(LutLibrary.count - 1)) }
        updateLabels()
        fillPicker(animate = true)
    }

    /** Back as it was: the page, and no LUT if there was none before the tour put one on. */
    private fun coachRestoreLut() {
        coachLutWas?.let { (lut, on) -> store[Settings.LUT] = lut; store[Settings.LUT_ON] = on }
        coachLutWas = null
        if (coachPage >= 0 && lookPage != coachPage) {
            lookPage = coachPage
            updateLabels()
            if (pickerOpen) fillPicker(animate = true)
        }
        coachPage = -1
    }

    private fun coachSteps(): List<org.photoall.film.ui.Coach.Step> {
        fun step(title: String, body: String, target: (() -> View?)? = null, waitFor: String? = null,
                 gesture: org.photoall.film.ui.Coach.Gesture = org.photoall.film.ui.Coach.Gesture.NONE, button: String? = null,
                 anchor: (() -> View?)? = null, through: Boolean = waitFor != null, pad: Float = 6f,
                 onEnter: (() -> Unit)? = null, onLeave: (() -> Unit)? = null, action: (() -> Unit)? = null,
                 dwell: Long = 320, place: org.photoall.film.ui.Coach.Place = org.photoall.film.ui.Coach.Place.AUTO) =
            org.photoall.film.ui.Coach.Step(title, body, target, waitFor, gesture, button, anchor, through, pad, onEnter, onLeave,
                action, dwell, place)
        val landscape = { resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        val card = { if (landscape()) lookRoller as View else recipeCard }
        return listOf(
            step("一卷胶片，从这里装上",
                "每一张照片，都循着负片感光、相纸印放的旧工序冲洗而成。\n花一分钟，认识几个手势。",
                button = "开始"),
            step("轻拨卡片",
                "一卡三页：胶卷、相纸、风格。\n此刻停在相纸，向左轻拨，翻到风格。",
                target = card, waitFor = "page", gesture = org.photoall.film.ui.Coach.Gesture.SWIPE),
            step("长按，展开",
                "长按卡片，四百余种风格\n便在取景框里一字排开。",
                target = card, waitFor = "picker_open", gesture = org.photoall.film.ui.Coach.Gesture.HOLD,
                // swiped the other way: the styles all the same, so the strip that opens is theirs
                onEnter = { if (lookPage != 2) { lookPage = 2; updateLabels() } }),
            step("滑动挑选，看画面",
                "左右滑动，取景框随之换色。\n多试几种，挑一种合眼缘的。",
                target = { pickerPanel }, anchor = { picker }, gesture = org.photoall.film.ui.Coach.Gesture.SWIPE,
                button = "选好了", through = true, place = org.photoall.film.ui.Coach.Place.BELOW),
            step("画圈，调浓淡",
                "此刻快门化作旋钮。\n顺时针渐浓，逆时针渐淡，画面边转边变。",
                target = { shutter }, waitFor = "lut_strength", gesture = org.photoall.film.ui.Coach.Gesture.CIRCLE, pad = 10f,
                dwell = 900, onEnter = { coachShowLut() }, onLeave = { coachRestoreLut(); closePicker() }),
            step("拨动刻度",
                "零点居中，一格三分之一档；\n正数更亮，负数更暗，双击归零。",
                target = { dialRow.takeIf { it.isShown } ?: evButton }, waitFor = "ev", gesture = org.photoall.film.ui.Coach.Gesture.DRAG,
                dwell = 900),
            step("择一种光",
                "轻点闪电，四种光贴边浮现，光只在快门落下的一瞬亮起。\n此处只作演示，选哪一种都不会真的闪。",
                target = { flashDock ?: flashIcon }, waitFor = "flash_close", gesture = org.photoall.film.ui.Coach.Gesture.TAP, pad = 2f,
                // a tour in a quiet room must not end in a flash: whatever was tried, it goes back off
                onLeave = { closeFlashModes(); if (store[Settings.FLASH] != 0f) store[Settings.FLASH] = 0f }),
            step("横过来，换一台相机",
                "把手机横过来，取景框左下角会浮起一片发光的扇形。\n轻点它，便是一台带拨轮与刻度的机身。",
                target = { rotateButton.takeIf { it.alpha > 0.5f } }, waitFor = "turn", gesture = org.photoall.film.ui.Coach.Gesture.TAP,
                pad = 0f, dwell = 900,
                // the phone may already be sideways: read it afresh so the offer comes up now
                onEnter = { coachTurn = true; orientationHeld = -1 },
                onLeave = { coachTurn = false; main.removeCallbacks(hideRotate); main.postDelayed(hideRotate, 1500) }),
            step("按下快门",
                "咔嚓一声，底片便在后台显影，\n数秒之后，左下角见分晓。",
                target = { shutter }, waitFor = "shot", gesture = org.photoall.film.ui.Coach.Gesture.TAP),
            step("余下的，在参数里",
                "第一栏是完整的使用指南。\n放大机滤色默认「自动中性」；想留住胶卷本来的底色，改为「保留胶片本色」。",
                target = { settingsIcon }, waitFor = "settings", gesture = org.photoall.film.ui.Coach.Gesture.TAP, button = "下一步", pad = 2f),
            step("写在最后",
                "数码仿胶，终究只是模仿。它学得来颗粒与色彩，却给不了三十六张的克制、等待冲洗的那几天，和取回底片时对着灯光辨认往事的惊喜。\n\n若有机会，去买一台真正的胶片相机吧。你对胶片懂得越多，在这里便越能尝出趣味。",
                button = "下一张"),
            step("点一颗星",
                "Photo All 开源免费，每一行代码都在 GitHub。若它曾让你会心一笑，点一颗 Star，便是最温柔的支持。\n\n新版本与新玩法，会发在小红书与抖音的作品和群聊里，欢迎关注。\n\ngithub.com/YZH2006517/Photo-All-Android",
                button = "前往点 Star", action = { openGithub() }),
        )
    }

    // adb: `am start ... --ei turn 0|1` switches the layout the animated way (no phone to turn).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // adb: `--ez coach true` starts the tour again
        if (intent.getBooleanExtra("coach", false)) main.postDelayed({ startCoach() }, 600)
        // adb: `--ez hint true` shows the rotate offer (no phone to turn)
        if (intent.getBooleanExtra("hint", false)) main.postDelayed({
            offeredOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            rotateButton.label = "横屏"
            rotateButton.toLandscape = true
            rotateButton.contentRotation = 0f
            rotateButton.appear(3000)
            main.removeCallbacks(hideRotate)
            main.postDelayed(hideRotate, 3000)
        }, 400)
        if (intent.hasExtra("turn")) {
            val to = intent.getIntExtra("turn", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            main.postDelayed({ rotateTo(to) }, 400) // once resumed again, as from a tap
        }
    }

    private var shownFlash = -1
    private var shownHdr: Boolean? = null

    /** A control whose state just changed gives a small spring (the flash bolt also tilts). */
    private fun pop(v: View, tilt: Boolean = false) {
        if (!v.isAttachedToWindow) return
        v.animate().cancel()
        v.scaleX = 0.72f; v.scaleY = 0.72f
        if (tilt) v.rotation = -24f
        v.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(0).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
    }

    private var turning = false
    private var turnStarted = 0L
    private val curtain by lazy { org.photoall.film.ui.ShutterCurtain(this) }
    private val turnStuck = Runnable { if (turning) { applyDisplayRotation(); turnIn() } }
    private lateinit var stage: Stage
    private var previewCase: Preview? = null
    private var analysisCase: ImageAnalysis? = null

    /**
     * The new layout: its viewfinder shows as an empty slot; the frosted still, kept the
     * right way up for the world, flies from where the picture was on the glass into it,
     * then clears to the live picture. The controls come in from the edges.
     */
    private fun turnIn() {
        main.removeCallbacks(turnStuck)
        val root = stage.body as? android.view.ViewGroup
        root?.let { turnParts(it, out = false, start = false) }
        val finder = viewfinderFrame
        val cornerTo = overlay.cornerRadius
        val from = turnFrom
        val fromRotation = turnFromRotation
        val fromScreen = turnFromScreen
        val still = turnStill
        turnStill = null
        // the new viewfinder: an empty slot until the still lands
        overlay.frost = null
        overlay.frostAlpha = 1f
        var landed: android.graphics.Bitmap? = null
        stage.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                stage.viewTreeObserver.removeOnPreDrawListener(this)
                val at = IntArray(2)
                finder.getLocationOnScreen(at)
                val toRotation = finder.display?.rotation ?: android.view.Surface.ROTATION_0
                if (from == null || still == null || finder.width == 0 || finder.height == 0) return true
                val m = mapAcrossTurn(from, fromRotation, toRotation, fromScreen)
                // upright for the world in the new screen's coordinates
                val turned = rotateStill(still, -90f * ((toRotation - fromRotation + 4) % 4))
                landed = turned
                val ghost = android.widget.ImageView(this@CameraActivity).apply {
                    setImageBitmap(turned)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                }
                var radius = turnFromCorner
                ghost.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
                stage.ghost = ghost
                ghost.measure(View.MeasureSpec.makeMeasureSpec(finder.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(finder.height, View.MeasureSpec.EXACTLY))
                ghost.layout(finder.left, finder.top, finder.right, finder.bottom)
                ghost.pivotX = 0f; ghost.pivotY = 0f
                ghost.translationX = m.left - at[0]
                ghost.translationY = m.top - at[1]
                ghost.scaleX = m.width() / finder.width
                ghost.scaleY = m.height() / finder.height
                ghost.tag = android.animation.ValueAnimator.ofFloat(turnFromCorner, cornerTo).apply {
                    addUpdateListener { radius = it.animatedValue as Float; ghost.invalidateOutline() }
                }
                return true
            }
        })
        whenSmooth {
            root?.let { turnParts(it, out = false, start = true) }
            val ghost = stage.ghost
            fun clear() {
                // the still has landed: it becomes the frost on the live picture, which clears
                landed?.let { overlay.frost = it }
                overlay.frostAlpha = 1f
                stage.ghost = null
                android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 300
                    startDelay = 40
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { overlay.frostAlpha = it.animatedValue as Float }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            overlay.frost = null
                            overlay.frostAlpha = 0f
                            turning = false
                        }
                    })
                }.start()
            }
            if (ghost == null) { clear(); return@whenSmooth }
            (ghost.tag as? android.animation.ValueAnimator)?.apply { duration = TURN_IN + 160 }?.start()
            ghost.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(0).setDuration(TURN_IN + 160)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction { clear() }.start()
        }
    }

    /**
     * The activity's content: the layout of the moment, and over its empty viewfinder slot
     * the one live viewfinder, which stays put in the window through every relayout.
     */
    private inner class Stage : android.view.ViewGroup(this@CameraActivity) {
        var body: View? = null
            private set
        private var slot: View? = null
        /** The frosted still in flight during a turn, drawn above everything. */
        var ghost: View? = null
            set(value) {
                field?.let { removeView(it) }
                field = value
                value?.let { addView(it) }
            }

        init {
            clipChildren = false
            addView(viewfinderFrame)
        }

        fun show(slot: View, layout: View) {
            body?.let { removeView(it) }
            body = layout
            this.slot = slot
            addView(layout, 0)
            requestApplyInsets()
        }

        private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(maxOf(0, size), View.MeasureSpec.EXACTLY)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = View.MeasureSpec.getSize(widthMeasureSpec)
            val h = View.MeasureSpec.getSize(heightMeasureSpec)
            body?.measure(exactly(w), exactly(h))
            viewfinderFrame.measure(exactly(slot?.measuredWidth ?: 0), exactly(slot?.measuredHeight ?: 0))
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            body?.layout(0, 0, width, height)
            val s = slot ?: return
            var x = 0
            var y = 0
            var v: View? = s
            while (v != null && v !== this) {
                val parent = v.parent as? View
                x += v.left - (parent?.scrollX ?: 0)
                y += v.top - (parent?.scrollY ?: 0)
                v = parent
            }
            if (viewfinderFrame.measuredWidth != s.width || viewfinderFrame.measuredHeight != s.height)
                viewfinderFrame.measure(exactly(s.width), exactly(s.height))
            viewfinderFrame.layout(x, y, x + s.width, y + s.height)
            ghost?.let {
                it.measure(exactly(s.width), exactly(s.height))
                it.layout(x, y, x + s.width, y + s.height)
            }
        }
    }

    /**
     * Runs [action] once two frames in a row arrive on time (the new layout is built on
     * this thread), and not before ~100 ms have passed; at most ~1 s from now.
     */
    private fun whenSmooth(action: () -> Unit) {
        val choreographer = android.view.Choreographer.getInstance()
        val begun = System.nanoTime()
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            var last = 0L
            var steady = 0
            override fun doFrame(now: Long) {
                steady = if (last != 0L && now - last < 34_000_000L) steady + 1 else 0
                last = now
                if ((steady >= 2 && now - begun > 100_000_000L) || now - begun > 1_000_000_000L) action()
                else choreographer.postFrameCallback(this)
            }
        })
    }

    /**
     * The edge parts of the layout (portrait: top bar above, shutter row below; landscape:
     * the two hands of the body) move [out] toward their edges, or come in from them.
     */
    private fun turnParts(root: android.view.ViewGroup, out: Boolean, start: Boolean = true) {
        val landscape = (root as? LinearLayout)?.orientation == LinearLayout.HORIZONTAL
        val d = dp(44).toFloat()
        val last = root.childCount - 1
        for (i in 0..last) {
            val v = root.getChildAt(i)
            val edge = when (i) { 0 -> -1f; last -> 1f; else -> 0f }
            if (edge == 0f) continue
            v.animate().cancel()
            val far = edge * d
            if (out) {
                v.animate().setStartDelay(0).setDuration(TURN_OUT).alpha(0f)
                    .translationX(if (landscape) far else 0f).translationY(if (landscape) 0f else far)
                    .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
            } else if (!start) {
                v.alpha = 0f // waiting at the edge
                if (landscape) v.translationX = far else v.translationY = far
            } else {
                v.animate().setStartDelay(i * 50L).setDuration(TURN_IN + 80).alpha(1f)
                    .translationX(0f).translationY(0f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar() // dialogs and other screens may have shown it again
    }

    private fun stepLut(step: Int) {
        val count = LutLibrary.count
        pickLut((store[Settings.LUT].toInt() + step + count) % count)
    }

    /** Choosing a LUT while it is switched off switches it back on. */
    private fun pickLut(index: Int) {
        if (!store.flag(Settings.LUT_ON)) store[Settings.LUT_ON] = 1f
        store[Settings.LUT] = index.toFloat()
    }

    private var lutSheet: Sheet? = null

    /** A .cube file picked: read and resampled off the main thread, then put in use. */
    private val importLut = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "导入的 LUT"
        val inputs = LutLibrary.Input.entries
        var choice = LutLibrary.Input.guess(name).ordinal
        android.app.AlertDialog.Builder(this)
            .setTitle("这个 LUT 的输入是？")
            .setSingleChoiceItems(inputs.map { it.label }.toTypedArray(), choice) { _, which -> choice = which }
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ -> importLutAs(uri, name, inputs[choice]) }
            .show()
    }

    private fun importLutAs(uri: android.net.Uri, name: String, input: LutLibrary.Input) {
        status.text = "正在导入 $name…"
        engineThread.later {
            val result = runCatching { LutLibrary.importCube(this, uri, name, input) }
            runOnUiThread {
                result.onSuccess { index ->
                    pickLut(index)
                    haptics.play(Haptics.Event.DEVELOPED)
                    status.text = "已导入 ${LutLibrary.name(index)}，在 LUT 的“${LutLibrary.USER_GROUP}”里"
                }.onFailure {
                    haptics.play(Haptics.Event.ERROR)
                    status.text = "导入失败：${it.message ?: "不是 3D LUT（.cube）文件"}"
                }
            }
        }
    }

    /** Full list to pick from, current entry checked. */
    private fun pickFrom(title: String, items: List<String>, current: Int, onPick: (Int) -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), current.coerceIn(0, items.lastIndex)) { dialog, which ->
                onPick(which)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("orientation", chosenOrientation)
    }

    override fun onResume() {
        super.onResume()
        // back from a portrait-only screen: return to the layout the user had
        if (requestedOrientation != chosenOrientation) requestedOrientation = chosenOrientation
        viewfinder.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        loadLatestThumbnail()
        // the first time, the tour (once the camera may run and the layout has settled)
        if (coach == null && !getSharedPreferences("coach", MODE_PRIVATE).getBoolean("camera", false) &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            main.postDelayed({ if (coach == null && !isFinishing && hasWindowFocus() &&
                !getSharedPreferences("coach", MODE_PRIVATE).getBoolean("camera", false)) startCoach() }, 900)
        // The zero-lag session closed with the screen: open it again (the settings
        // screen too may have changed what it should be).
        val reopen = zslPaused
        zslPaused = false
        if (reopen && !settingsOpen) bindCamera()
        if (settingsOpen && store[Settings.THEME] != themeShown) {
            // Every view was built in the old colours: build the screen again.
            settingsOpen = false
            recreate()
            return
        }
        if (settingsOpen) {
            // The settings screen wrote straight to storage; catch up with everything.
            settingsOpen = false
            if (reopen || bindKeys() != bindSnapshot) bindCamera() else applyCameraOptions()
            main.post(rebake)
            updateLabels()
        }
    }

    override fun onPause() {
        closeFlashModes()
        zsl?.let {
            it.close()
            zsl = null
            zslPaused = true
        }
        orientationListener.disable()
        main.removeCallbacks(hideRotate)
        rotateButton.dismiss()
        viewfinder.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        analysisThread.shutdown()
        engineThread.execute { engine?.close() } // before shutdown: always accepted
        engineThread.shutdown()
        super.onDestroy()
    }

    /** Settings that need the camera rebound rather than just new request options. */
    private fun bindKeys() = listOf(store[Settings.RAW], store[Settings.SOFT_3A], if (zslWanted()) 1f else 0f)

    private fun openSettings() {
        coach?.event("settings")
        settingsOpen = true
        bindSnapshot = bindKeys()
        startActivity(Intent(this, org.photoall.film.settings.SettingsActivity::class.java))
    }

    // ----- settings ---------------------------------------------------------

    private fun onSettingChanged(key: String) {
        updateLabels()
        sheetRefresh?.invoke(key)
        when {
            key == Settings.RAW || key == Settings.SOFT_3A -> bindCamera()
            // HDR (and a flash that rules it out) switch between CameraX and the zero-lag session
            (key == Settings.HDR || key == Settings.FLASH) && zslWanted() != (zsl != null || zslPaused) -> bindCamera()
            key in exposureKeys -> applyCameraOptions()
            // the filter mode and the creative LUT change the look too
            key.startsWith("app.") &&
                key !in setOf(Settings.BALANCE, Settings.LUT, Settings.LUT_ON, Settings.LUT_STRENGTH,
                    Settings.FILM_ON, Settings.PRINT_ON) -> {}
            // the strength dial turning: the viewfinder follows as it turns, not after
            key == Settings.LUT_STRENGTH && lutDialDragging -> bakeSoon()
            else -> {
                if (key == "film") applyCameraOptions() // film-matched balance and film ISO
                main.removeCallbacks(rebake)
                main.postDelayed(rebake, 120)
            }
        }
        if (key.isEmpty()) { // reset all
            bindCamera()
            main.post(rebake)
        }
    }

    private fun stepSetting(key: String, step: Int) {
        val setting = Settings.byKey.getValue(key) as org.photoall.film.settings.Setting.Choice
        val count = setting.options.size
        store[key] = ((store[key].toInt() + step + count) % count).toFloat()
    }

    /** The LUT picked in the selector, whether or not it is switched on. */
    private fun chosenLut() = store[Settings.LUT].toInt().coerceIn(0, LutLibrary.count - 1)

    /** The creative LUT in effect (0 = none, also when switched off) and its strength. */
    private fun currentLut() = (if (store.flag(Settings.LUT_ON)) chosenLut() else 0) to store[Settings.LUT_STRENGTH]

    // Engine thread: hand the engine this creative LUT unless it already has it.
    private fun applyLut(lut: Pair<Int, Float>) {
        if (appliedLut == lut) return
        engine().setCreativeLut(LutLibrary.table(this, lut.first), LutLibrary.size, lut.second)
        appliedLut = lut
    }

    // Engine thread.
    private fun bakeLut() {
        val engine = engine ?: return
        val start = System.nanoTime()
        val lut = try {
            applyLut(currentLut())
            engine.previewLut(store.filmParams())
        } catch (e: Throwable) {
            runOnUiThread { status.text = "预览失败：${e.message}" }
            return
        }
        viewfinder.setLut(lut, 33)
        lastLut = lut
        meter?.setLut(lut, 33)
        Log.i(TAG, "preview lut: %.1f ms".format((System.nanoTime() - start) / 1e6))
    }

    // ----- camera -----------------------------------------------------------

    /** Cameras of the current facing that apps can open. */
    private fun lenses(provider: ProcessCameraProvider): List<CameraInfo> {
        val facing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        return provider.availableCameraInfos.filter { it.lensFacing == facing }.sortedBy { equivalentMm(it) }
    }

    /** A lens the user can pick: which camera to bind and the zoom ratio that selects it. */
    private class LensOption(val camera: CameraInfo, val zoom: Float, val ratio: Float)

    /**
     * Every real lens, as a magnification of the main camera. Phones like the Galaxy
     * S23 Ultra hide their 3x and 10x telephotos inside one logical camera that
     * switches lens by zoom ratio; their physical cameras give the real focal lengths.
     * Phones that expose each lens as its own camera are listed camera by camera.
     */
    private fun lensOptions(provider: ProcessCameraProvider): List<LensOption> {
        val cameras = lenses(provider)
        val main = mainCamera(cameras) ?: return emptyList()
        val reference = equivalentMm(main).takeIf { it > 0f } ?: return emptyList()
        val options = mutableListOf<LensOption>()
        for (camera in cameras) {
            val physical = runCatching { camera.physicalCameraInfos }.getOrDefault(emptySet())
            val zoom = camera.zoomState.value
            if (physical.size > 1 && zoom != null) {
                // The logical camera's own focal length is its 1x lens.
                val base = equivalentMm(camera).takeIf { it > 0f } ?: continue
                val scale = base / reference
                for (lens in physical) {
                    val mm = equivalentMm(lens)
                    if (mm <= 0f) continue
                    val z = snapRatio(mm / base).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                    options += LensOption(camera, z, snapRatio(z * scale))
                }
                options += LensOption(camera, 1f, snapRatio(scale))
            } else options += LensOption(camera, 1f, snapRatio(equivalentMm(camera) / reference))
        }
        // One button per magnification; prefer the logical camera (zooms smoothly between lenses).
        val unique = options.sortedBy { it.ratio }.fold(mutableListOf<LensOption>()) { acc, o ->
            val last = acc.lastOrNull()
            if (last != null && Math.abs(last.ratio / o.ratio - 1) < 0.08f) {
                if (o.camera.physicalCameraInfosOrEmpty().size > last.camera.physicalCameraInfosOrEmpty().size)
                    acc[acc.lastIndex] = o
            } else acc += o
            acc
        }
        // A single lens still gets the usual digital stops.
        if (unique.size == 1) {
            val only = unique[0]
            val max = only.camera.zoomState.value?.maxZoomRatio ?: 1f
            for (r in listOf(2f, 4f)) if (r <= max) unique += LensOption(only.camera, r, r)
        }
        return unique
    }

    private fun CameraInfo.physicalCameraInfosOrEmpty(): Set<CameraInfo> =
        runCatching { physicalCameraInfos }.getOrDefault(emptySet())

    /** Snap to the magnifications phones print on their lenses (0.6x, 3x, 10x...). */
    private fun snapRatio(r: Float): Float {
        val nice = floatArrayOf(0.5f, 0.6f, 0.7f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f, 5f, 6f, 10f)
        val near = nice.minByOrNull { Math.abs(it / r - 1) } ?: r
        return if (Math.abs(near / r - 1) < 0.1f) near else (r * 10).roundToInt() / 10f
    }

    /** The main camera: a logical one spanning lenses, else the highest resolution sensor. */
    private fun mainCamera(cameras: List<CameraInfo>): CameraInfo? =
        cameras.firstOrNull { it.physicalCameraInfosOrEmpty().size > 1 } ?: cameras.maxByOrNull { info ->
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { it.width().toLong() * it.height() } ?: 0L
        }

    /** 35 mm equivalent focal length from the physical sensor diagonal. */
    private fun equivalentMm(info: CameraInfo): Float {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val sensor = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return focal
        val diagonal = Math.hypot(sensor.width.toDouble(), sensor.height.toDouble())
        return (focal * 43.27 / diagonal).toFloat()
    }

    private fun zslWanted(): Boolean {
        val flash = store[Settings.FLASH].toInt()
        return store.flag(Settings.HDR) && store.flag(Settings.RAW) && (flash == 0 || flash == Settings.FLASH_STEADY)
    }

    /**
     * HDR: the lens through a zero-lag Camera2 session (RAW frames kept in memory), the
     * sensor held under the meter and the viewfinder shown as metered.
     */
    private fun bindZsl(provider: ProcessCameraProvider, id: String, full: CameraCharacteristics, controller: FilmExposure) {
        provider.unbindAll()
        camera = null
        rawCapture = true
        val previewMeter = PreviewMeter().also { m ->
            meter = m
            m.onHighlights = { clipped -> controller.onPreviewHighlights(clipped); main.post { adaptUnder() } }
            m.setLut(lastLut, 33)
            applyHistogram(m)
        }
        val session = ZslCamera(this, id, full, controller, previewMeter) { message -> main.post { status.text = message } }
        zsl = session
        val sensor = full.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotation = zslRotation(full)
        viewfinder.sensorRotation = sensor
        viewfinder.frontFacing = full.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        controller.sensorRotation = rotation
        setUnder(store[Settings.HDR_UNDER].toDouble().coerceAtMost(1.0))
        viewfinder.provideDirect(session.previewSize, rotation) { surface -> session.open(surface) }
        pendingZoom?.let { session.zoomTo(it) }
        pendingZoom = null
        overlay.hideFocus()
        buildZoomButtons()
        applyCameraOptions()
        updateLabels()
    }

    /** Sensor to upright for the screen as it is turned (CameraX's target rotation). */
    private fun zslRotation(full: CameraCharacteristics): Int {
        val sensor = full.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = full.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val display = (viewfinder.display?.rotation ?: android.view.Surface.ROTATION_0) * 90
        return if (front) (sensor + display) % 360 else (sensor - display + 360) % 360
    }

    /** The zero-lag sensor exposure: [stops] under the meter, shown as metered. */
    private fun setUnder(stops: Double) {
        val gain = Math.pow(2.0, stops).toFloat()
        exposure?.under = stops
        meter?.gain = gain
        viewfinder.gain = gain
        exposure?.push()
    }

    /**
     * Zero-lag HDR: the brightest 0.5% of the sensor at about 80% of white (the merge and
     * the tone mapping bring the rest back), at most HDR_UNDER stops under the meter.
     */
    private fun adaptUnder() {
        val m = meter ?: return
        val c = exposure ?: return
        if (zsl == null) return
        val now = SystemClock.uptimeMillis()
        // Every change restarts the camera's repeating request (a hitch in the viewfinder):
        // only for a third of a stop or more, at most every 1.5 s.
        if (now - lastUnderChange < 1500) return
        val most = store[Settings.HDR_UNDER].toDouble()
        val target = when {
            m.clipped > 0.005f -> c.under + 0.5
            m.highlight > 0f -> c.under + ln(m.highlight / 0.8) / ln(2.0) + 0.3
            else -> c.under
        }.coerceIn(0.3, maxOf(0.3, most))
        val step = Math.round(target * 3) / 3.0
        if (abs(step - c.under) < 0.33) {
            underWanted = null
            return
        }
        // Only a change the scene keeps asking for: the same step for 0.6 s (a still
        // scene then stays put; panning across a lamp does not pump).
        if (underWanted != step) {
            underWanted = step
            underWantedAt = now
            return
        }
        if (now - underWantedAt < 600) return
        underWanted = null
        lastUnderChange = now
        setUnder(step)
    }

    private fun pickCamera(provider: ProcessCameraProvider): CameraInfo? {
        val all = lenses(provider)
        // The remembered lens, else the main camera (not a macro or depth helper).
        all.firstOrNull { Camera2CameraInfo.from(it).cameraId == lensId }?.let { return it }
        return mainCamera(all)
    }

    private fun bindCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get().also { provider = it }
            val info = pickCamera(provider) ?: run { status.text = "没有可用的相机"; return@addListener }
            val fourThree = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val chars = Camera2CameraInfo.from(info)
            val capabilities = chars.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            // Calibrated white balance needs to write the colour matrix ourselves.
            val calibration = Calibration { key -> chars.getCameraCharacteristic(key) }.takeIf {
                it.valid && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
            }
            val view = object : Characteristics {
                override fun <T> get(key: CameraCharacteristics.Key<T>): T? = chars.getCameraCharacteristic(key)
            }
            val controller = FilmExposure(view, calibration) { options ->
                camera?.let { Camera2CameraControl.from(it.cameraControl).setCaptureRequestOptions(options) }
            }.also { c -> c.onLive = { e -> main.post { showLive(e) } } }
            exposure = controller
            boundInfo = info
            zsl?.close()
            zsl = null
            // HDR on a lens that streams RAW: the zero-lag session instead of CameraX.
            if (zslWanted()) {
                val full = (getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager)
                    .getCameraCharacteristics(chars.cameraId)
                if (ZslCamera.supported(full)) {
                    bindZsl(provider, chars.cameraId, full, controller)
                    return@addListener
                }
            }
            viewfinder.gain = 1f

            // The phone's 3A meters; its per-frame results drive the live readout and stills.
            val previewBuilder = Preview.Builder().setResolutionSelector(fourThree)
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(controller.captureCallback)
            val preview = previewBuilder.build()
            preview.surfaceProvider = viewfinder
            previewCase = preview
            // Low-res analysis stream: the preview meter (RAW develops match its brightness,
            // highlight protection reads its clipping).
            val previewMeter = PreviewMeter().also { m ->
                meter = m
                m.onHighlights = { clipped -> controller.onPreviewHighlights(clipped) }
                m.setLut(lastLut, 33)
                applyHistogram(m)
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(analysisThread, previewMeter); analysisCase = it }
            val wantRaw = store.flag(Settings.RAW)
            val rawSupported = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
                .contains(ImageCapture.OUTPUT_FORMAT_RAW)
            rawCapture = wantRaw && rawSupported
            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(fourThree)
            if (rawCapture) builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
            val imageCapture = builder.build()
            provider.unbindAll()
            val bound = provider.bindToLifecycle(this, info.cameraSelector, preview, imageCapture, analysis)
            camera = bound
            capture = imageCapture
            viewfinder.sensorRotation = bound.cameraInfo.sensorRotationDegrees
            viewfinder.frontFacing = bound.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT
            // Metering regions map from the viewfinder as currently rotated (landscape too).
            controller.sensorRotation = bound.cameraInfo.getSensorRotationDegrees(
                viewfinder.display?.rotation ?: android.view.Surface.ROTATION_0)
            bound.cameraInfo.zoomState.observe(this) { updateZoom() }
            pendingZoom?.let { bound.cameraControl.setZoomRatio(it) }
            pendingZoom = null
            overlay.hideFocus()
            buildZoomButtons()
            applyCameraOptions()
            updateLabels()
            if (wantRaw && !rawSupported) status.text = "这颗镜头不支持 RAW，改用 JPEG"
        }, mainExecutor)
    }

    private val exposureKeys = setOf(
        Settings.MANUAL, Settings.ISO, Settings.SHUTTER, Settings.WHITE_BALANCE,
        Settings.METERING, Settings.HIGHLIGHT, Settings.FLICKER, Settings.FILM_ISO,
        Settings.FOCUS, Settings.FOCUS_DISTANCE, Settings.PEAKING, Settings.FLASH, Settings.FLASH_FILL,
    )

    // ----- focus ------------------------------------------------------------

    private fun focusMode() = store[Settings.FOCUS].toInt().coerceIn(0, 2)

    /** Closest focus in diopters (1 / metres); 0 means a fixed-focus lens. */
    private fun minFocusDiopters(): Float = boundInfo?.let {
        Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    } ?: 0f

    /** Manual focus distance in diopters, or null when autofocus runs. */
    private fun manualFocusDiopters(): Float? =
        if (focusMode() == 2 && minFocusDiopters() > 0f) store[Settings.FOCUS_DISTANCE] * minFocusDiopters() else null

    private fun focusLabel(): String {
        val d = manualFocusDiopters() ?: return ""
        val calibrated = boundInfo?.let {
            Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        } != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
        return when {
            d < 0.02f -> "∞"
            !calibrated -> "%.0f%%".format(store[Settings.FOCUS_DISTANCE] * 100) // no real distance scale
            1 / d >= 1f -> "%.1f m".format(1 / d)
            else -> "%.0f cm".format(100 / d)
        }
    }

    private fun updateFocusControls() {
        val mode = focusMode()
        val adjustable = minFocusDiopters() > 0f
        mfRow.visibility = if (mode == 2) View.VISIBLE else View.GONE
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        focusRail.visibility = if (landscape && mode == 2) View.VISIBLE else View.GONE
        focusRail.isEnabled = adjustable
        focusRail.value = store[Settings.FOCUS_DISTANCE]
        focusRail.label = if (adjustable) focusLabel() else "定焦镜头"
        focusRail.peaking = store.flag(Settings.PEAKING)
        focusSeek.isEnabled = adjustable
        focusSeek.progress = (store[Settings.FOCUS_DISTANCE] * 1000).roundToInt()
        distanceLabel.text = if (adjustable) focusLabel() else "定焦镜头"
        Style.active(peakButton, store.flag(Settings.PEAKING))
        viewfinder.peaking = mode == 2 && store.flag(Settings.PEAKING)
    }

    private fun cycleFocusMode() {
        camera?.cameraControl?.cancelFocusAndMetering() // drop any AF lock before switching
        zsl?.unlockFocus()
        overlay.hideFocus()
        store[Settings.FOCUS] = ((focusMode() + 1) % 3).toFloat()
    }

    private fun recipeText(filmOn: Boolean, printOn: Boolean, stock: String, paper: String, lut: Int) =
        org.photoall.film.settings.LookFamilies.recipeText(filmOn, printOn, stock, paper, lut)

    private fun filmName() = FilmEngine.filmStocks[store["film"].toInt().coerceIn(0, FilmEngine.filmStocks.lastIndex)]

    private fun manualExposure(chars: Camera2CameraInfo): Exposure {
        val isoRange = chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val timeRange = chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        var iso = Settings.iso(store[Settings.ISO])
        var ns = (Settings.shutterSeconds(store[Settings.SHUTTER]) * 1e9).toLong()
        if (isoRange != null) iso = iso.coerceIn(isoRange.lower, isoRange.upper)
        if (timeRange != null) ns = ns.coerceIn(timeRange.lower, timeRange.upper)
        return Exposure(ns, iso)
    }

    /** Live readout from the phone's 3A (every few frames). */
    private fun showLive(e: Exposure) {
        if (store.flag(Settings.MANUAL)) return // updateLabels shows the fixed values
        val film = exposure?.let { it.filmRules && it.filmIso > 0 } == true
        overlay.info = "ISO %d · %s".format(e.iso, Settings.secondsLabel(e.timeNs / 1e9)) +
            if (film) " · 胶片 ISO ${exposure?.filmIso}" else ""
    }

    /**
     * Exposure, white balance and focus requests, all through [FilmExposure]: the phone's
     * own 3A meters, film rules (when on) steer it. Always a known sRGB tone curve and
     * the lens shading map the DNG needs.
     */
    private fun applyCameraOptions() {
        val info = boundInfo ?: return
        val controller = exposure ?: return
        val chars = Camera2CameraInfo.from(info)
        updateFocusControls()
        val wb = store[Settings.WHITE_BALANCE].toInt()
        controller.filmRules = store.flag(Settings.SOFT_3A)
        controller.metering = store[Settings.METERING].toInt()
        controller.highlightProtect = store.flag(Settings.HIGHLIGHT)
        controller.antibanding = store[Settings.FLICKER].toInt()
        controller.evStops = evStops
        controller.filmIso = if (store.flag(Settings.FILM_ISO)) Settings.filmIso(filmName()) else 0
        controller.manual = if (store.flag(Settings.MANUAL)) manualExposure(chars) else null
        controller.fixedKelvin = Settings.whiteBalanceKelvin(wb, filmName())
        controller.awbPreset = when (wb) {
            1 -> if (Settings.whiteBalanceKelvin(1, filmName())!! < 4000) CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                else CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            2 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            3 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            4 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            5 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            6 -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        controller.focusDiopters = manualFocusDiopters()
        controller.steadyLight = if (flashMode() == Settings.FLASH_STEADY) store[Settings.FLASH_FILL] else null
        controller.push()
        updateLabels()
    }

    private var options: List<LensOption> = emptyList()
    private val lensButtons = mutableListOf<Pair<LensOption, LensButton>>()
    /** The main lens's 35 mm equivalent: lens buttons show focal lengths from it. */
    private var referenceMm = 0f

    /** One round button per real lens, labelled with its 35 mm equivalent focal length. */
    private fun buildZoomButtons() {
        lensRow.removeAllViews()
        lensButtons.clear()
        val provider = provider ?: return
        options = lensOptions(provider)
        referenceMm = mainCamera(lenses(provider))?.let { equivalentMm(it) } ?: 0f
        for (option in options) {
            val b = LensButton(this).apply { setOnClickListener { selectLens(option) } }
            // small enough to leave the picture alone, still a fair touch target with the gaps
            lensRow.addView(b, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(3); marginEnd = dp(3) })
            lensButtons += option to b
        }
        facingButton.contentDescription = if (front) "切换到后置镜头" else "切换到前置镜头"
        updateZoom()
    }

    /** A magnification of the main lens as a 35 mm focal length (0.6x of 23 mm is 14). */
    private fun focalOf(ratio: Float) = if (referenceMm > 0f) (referenceMm * ratio).roundToInt() else (ratio * 24).roundToInt()

    private fun selectLens(option: LensOption) {
        haptics.play(Haptics.Event.SEGMENT)
        zsl?.let { session ->
            val id = Camera2CameraInfo.from(option.camera).cameraId
            if (id == session.cameraId) {
                session.zoomTo(option.zoom)
                updateZoom()
            } else {
                lensId = id
                pendingZoom = option.zoom
                bindCamera()
            }
            return
        }
        val camera = camera ?: return
        val id = Camera2CameraInfo.from(option.camera).cameraId
        if (id == Camera2CameraInfo.from(camera.cameraInfo).cameraId) camera.cameraControl.setZoomRatio(option.zoom)
        else {
            lensId = id
            pendingZoom = option.zoom
            bindCamera()
        }
    }

    private fun zoomBy(factor: Float) {
        zsl?.let { session ->
            val range = session.characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val lo = range?.lower ?: 1f
            val hi = range?.upper ?: 1f
            session.zoomTo((session.zoom * factor).coerceIn(lo, hi))
            updateZoom()
            return
        }
        val camera = camera ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        camera.cameraControl.setZoomRatio((state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    /** Shows the current magnification and highlights the lens it sits on. */
    private fun updateZoom() {
        val session = zsl
        val camera = camera
        if (session == null && camera == null) return
        val zoom = session?.zoom ?: camera!!.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val id = session?.cameraId ?: Camera2CameraInfo.from(camera!!.cameraInfo).cameraId
        val mine = lensButtons.filter { Camera2CameraInfo.from(it.first.camera).cameraId == id }
        val active = mine.lastOrNull { it.first.zoom <= zoom * 1.01f } ?: mine.firstOrNull()
        val scale = active?.let { it.first.ratio / it.first.zoom } ?: 1f
        zoomLabel.text = zoomText(zoom * scale)
        // the lens in use shows where the zoom really is (pinching between lenses)
        for ((option, b) in lensButtons) {
            val inUse = option === active?.first
            b.show(focalOf(if (inUse) zoom * scale else option.ratio), inUse)
        }
        lensWell.show(lensButtons.map { focalOf(it.first.ratio).toString() },
            lensButtons.indexOfFirst { it.first === active?.first }.coerceAtLeast(0), focalOf(zoom * scale).toString())
    }

    private fun zoomText(ratio: Float) =
        if (ratio < 0.995f || (ratio * 10).roundToInt() % 10 != 0) "%.1f×".format(ratio) else "%.0f×".format(ratio)

    /** Focus (and meter) at view point (x, y); [lock] holds the focus found (a double tap). */
    private fun focusAt(x: Float, y: Float, lock: Boolean = false) {
        zsl?.let { session ->
            val controller = exposure ?: return
            val u = (x / viewfinder.width).toDouble()
            val v = (y / viewfinder.height).toDouble()
            if (controller.filmRules && controller.metering != 0) {
                controller.focusX = u
                controller.focusY = v
                controller.push()
            }
            val mode = if (lock) 1 else focusMode()
            if (mode == 2) return overlay.showFocus(x, y, ViewfinderOverlay.Focus.Metering)
            overlay.showFocus(x, y, ViewfinderOverlay.Focus.Focusing)
            // AF-L holds the focus found until the next tap or a mode change.
            session.focus(controller.regionAt(u, v), lock = mode == 1) { ok ->
                main.post {
                    showFocusResult(x, y, when {
                        ok != true -> ViewfinderOverlay.Focus.Failed
                        mode == 1 -> ViewfinderOverlay.Focus.Locked
                        else -> ViewfinderOverlay.Focus.Focused
                    })
                }
            }
            return
        }
        val camera = camera ?: return
        val factory = DisplayOrientedMeteringPointFactory(viewfinder.display, camera.cameraInfo,
            viewfinder.width.toFloat(), viewfinder.height.toFloat())
        val controller = exposure
        val filmMetering = controller != null && controller.filmRules && controller.metering != 0
        if (filmMetering) {
            // Film rules set the metering pattern; spot metering follows the tap.
            controller!!.focusX = (x / viewfinder.width).toDouble()
            controller.focusY = (y / viewfinder.height).toDouble()
            controller.push()
        }
        val mode = if (lock) 1 else focusMode()
        // With a film metering pattern the HAL only gets the AF part of the tap; manual
        // focus gets no AF at all.
        var flags = if (filmMetering) FocusMeteringAction.FLAG_AF
        else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        if (mode == 2) flags = flags and FocusMeteringAction.FLAG_AF.inv()
        if (flags and FocusMeteringAction.FLAG_AF == 0) {
            overlay.showFocus(x, y, ViewfinderOverlay.Focus.Metering)
            if (flags == 0) return
        } else overlay.showFocus(x, y, ViewfinderOverlay.Focus.Focusing)
        val builder = FocusMeteringAction.Builder(factory.createPoint(x, y), flags)
        if (mode == 1) builder.disableAutoCancel() // AF-L: hold until the next tap or mode change
        val result = camera.cameraControl.startFocusAndMetering(builder.build())
        if (flags and FocusMeteringAction.FLAG_AF == 0) return
        result.addListener({
            // A newer tap cancels this one: its future fails, and its mark has moved on.
            val ok = runCatching { result.get().isFocusSuccessful }.getOrNull() ?: return@addListener
            showFocusResult(x, y, when {
                !ok -> ViewfinderOverlay.Focus.Failed
                mode == 1 -> ViewfinderOverlay.Focus.Locked
                else -> ViewfinderOverlay.Focus.Focused
            })
        }, mainExecutor)
    }

    /** A tap's focus scan settled: the mark, and a short confirmation in the hand. */
    private fun showFocusResult(x: Float, y: Float, state: ViewfinderOverlay.Focus) {
        overlay.showFocus(x, y, state)
        haptics.play(when (state) {
            ViewfinderOverlay.Focus.Failed -> Haptics.Event.FOCUS_FAILED
            ViewfinderOverlay.Focus.Locked -> Haptics.Event.LOCKED
            else -> Haptics.Event.FOCUSED
        })
    }

    /** Live histogram on the viewfinder when enabled (computed by the preview meter). */
    private fun applyHistogram(m: PreviewMeter) {
        if (store.flag(Settings.HISTOGRAM)) m.onHistogram = { h -> main.post { overlay.histogram = h } }
        else {
            m.onHistogram = null
            overlay.histogram = null
        }
    }

    /** Exposure bias in thirds of a stop, sent as the phone AE's compensation. */
    private fun changeEv(step: Int) {
        coach?.event("ev")
        val controller = exposure ?: return
        if (store.flag(Settings.MANUAL)) return
        val wanted = (evStops * 3).roundToInt() + step
        val thirds = wanted.coerceIn(-9, 9)
        haptics.play(when {
            thirds != wanted -> Haptics.Event.LIMIT
            thirds % 3 == 0 -> Haptics.Event.STOP
            else -> Haptics.Event.TICK
        })
        if (thirds != wanted) return
        evStops = thirds / 3.0
        controller.evStops = evStops
        controller.push()
        updateLabels()
    }

    private fun updateLabels() {
        if (::shutter.isInitialized) syncLutDial()
        val filmOn = store.flag(Settings.FILM_ON)
        val printOn = store.flag(Settings.PRINT_ON)
        val paper = FilmEngine.papers[store["paper"].toInt().coerceIn(0, FilmEngine.papers.lastIndex)]
        val lut = currentLut()
        // The card: the page in view (film, paper or LUT).
        val shownStock = if (filmOn) filmName() else null
        if (lookPage == 0 && canister.stock != shownStock && canister.isAttachedToWindow) {
            // a new roll goes in: the cartridge drops in with a small bounce
            canister.scaleX = 0.7f; canister.scaleY = 0.7f; canister.alpha = 0.2f; canister.translationY = -dp(6).toFloat()
            canister.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(340)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }
        canister.stock = shownStock
        fillCard(filmOn, printOn, paper, lut)
        val guides = store[Settings.GUIDES].toInt().coerceIn(0, Settings.guideNames.lastIndex)
        overlay.guides = guides
        meter?.let { applyHistogram(it) }
        val manual = store.flag(Settings.MANUAL)
        evLabel.text = if (manual) "手动" else if (evStops == 0.0) "±0.0" else "%+.1f".format(evStops)
        evLabel.setTextColor(if (manual || evStops != 0.0) Style.ACCENT else Style.TEXT)
        evDial.thirds = (evStops * 3).roundToInt()
        dialRow.visibility = if (!manual && (evOpen || dialPinned)) View.VISIBLE else View.GONE
        manualRow.visibility = if (manual && (evOpen || dialPinned)) View.VISIBLE else View.GONE
        showBody(manual, store.flag(Settings.HDR))
        if (manual) showManual()
        val flashMode = flashMode()
        val hdr = store.flag(Settings.HDR) && rawCapture && (flashMode == 0 || flashMode == Settings.FLASH_STEADY)
        // Top bar: the HDR chip (outlined in the accent when on), the flash icon, histogram.
        val bracket = store[Settings.HDR_LONG] >= 0.1f
        val shorts = store[Settings.HDR_FRAMES].toInt().coerceIn(2, if (bracket) 5 else 6)
        hdrButton.text = if (hdr) (if (bracket) "HDR $shorts+1" else "HDR $shorts") else "HDR"
        hdrButton.setTextColor(if (hdr) Style.ACCENT else Style.TEXT_2)
        hdrButton.background = Style.surface(this, fill = Color.TRANSPARENT, radiusDp = 14f, stroke = if (hdr) Style.ACCENT else Style.HAIRLINE)
        val onBody = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (shownFlash != -1 && shownFlash != flashMode) pop(flashIcon, tilt = true)
        if (shownHdr != null && shownHdr != hdr) pop(hdrButton)
        shownFlash = flashMode
        shownHdr = hdr
        flashIcon.setImageDrawable(Icon(if (flashMode == 0) Icon.Kind.FLASH_OFF else Icon.Kind.FLASH,
            if (flashMode != 0) (if (onBody) Toy.AMBER else Style.ACCENT) else if (onBody) Toy.INK else Style.TEXT))
        flashIcon.contentDescription = "闪光：${Settings.flashModes[flashMode]}"
        val histogramOn = store.flag(Settings.HISTOGRAM)
        histogramIcon.setImageDrawable(Icon(Icon.Kind.HISTOGRAM, if (histogramOn) Style.ACCENT else Style.TEXT))
        histogramIcon.contentDescription = if (histogramOn) "直方图：开" else "直方图：关"
        val roll = rollFrame()
        frameCount.swapText("${roll + 1}") // the counter rolls on, like a film counter
        val landscapeNow = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // the counter window on the body is small: just the roll length there
        frameNote.text = if (landscapeNow) "/$ROLL_LENGTH" else "/ $ROLL_LENGTH · ${if (rawCapture) "RAW" else "JPEG"}"
        modeLabel.text = buildString {
            if (flashMode == Settings.FLASH_STEADY) append("补光常亮 · ")
            if (flashMode != 0 && flashMode != Settings.FLASH_STEADY) append(Settings.flashModes[flashMode])
            else if (hdr) {
                // Same frame split as takePicture: shorts, plus one longer frame when bracketing.
                val bracket = store[Settings.HDR_LONG] >= 0.1f
                val shorts = store[Settings.HDR_FRAMES].toInt().coerceIn(2, if (bracket) 5 else 6)
                append(if (bracket) "HDR 包围 $shorts+1" else "HDR $shorts 帧")
            } else append(if (rawCapture) "RAW" else "JPEG")
            if (rawCapture && !store.flag(Settings.KEEP_RAW)) append("→JPG")
            if (store.flag(Settings.SOFT_3A)) append(" · 胶片测光")
            val wb = store[Settings.WHITE_BALANCE].toInt().coerceIn(0, Settings.whiteBalances.lastIndex)
            if (wb != 0) append(" · ${Settings.whiteBalances[wb]}") // auto is the norm; show only a choice
        }
        // Manual exposure has no live meter; show the fixed values (auto shows them via showLive).
        if (store.flag(Settings.MANUAL))
            overlay.info = "ISO ${Settings.iso(store[Settings.ISO])} · ${Settings.shutterLabel(store[Settings.SHUTTER])} · 手动"
    }

    // ----- capture ----------------------------------------------------------

    /** A capture (single frame or burst) is running; the shutter waits for it. */
    private var capturing = false

    private fun takePicture() {
        if (pickerOpen) closePicker()
        coach?.event("shot")
        zsl?.let { if (store.flag(Settings.HDR)) return takeZsl(it) }
        val capture = capture ?: return
        if (capturing) {
            status.text = "上一张还在拍摄…"
            return
        }
        // A hold left on the sensor would make this shot meter from a frozen exposure.
        exposure?.held?.let { stale ->
            Log.w(TAG, "stale hold ${stale.label()} before a shot: released")
            exposure?.release()
            status.text = "曝光已恢复自动测光，请再按一次快门"
            return
        }
        capturing = true
        shotClacked = false
        shutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120)
        }
        // The curtain waits for the sensor (and for a flash's metering pre-flash): it closes
        // when the exposure really starts. See exposureStarted.
        // Freeze the recipe at the moment of the shot.
        val params = store.filmParams() // engine parameters only
        val recipe = params + (LUT_KEY to currentLut().first.toFloat()) + (LUT_STRENGTH_KEY to currentLut().second)
        val options = DevelopOptions(maxLongEdge = store.longEdge, seed = Random.nextInt(), displayP3 = false)
        val quality = store[Settings.JPEG_QUALITY].toInt()
        val rawEv = store[Settings.RAW_EV]
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        developing++
        status.text = "${work()}中（$developing 张）…"
        // The still's exposure: as metered, unless an HDR burst is held darker (highlights
        // survive; merging cleans the shadows; the develop lifts them back) or the film ISO
        // re-splits the metered exposure. A held exposure needs a few frames to land.
        val controller = exposure
        // Flash: 1 is a real flash in one frame (the HAL meters it with a pre-flash); 2 a
        // steady fill light, already lit in the viewfinder, so the shot is an ordinary one;
        // 3 mixes an ambient frame and one lit by the flash LED, both held 1.5 stops under.
        val flashMode = flashMode()
        val singleFlash = flashMode == 1
        // Manual exposure turns the phone's AE off, and its flash pulse only fires through AE
        // (a pre-flash to meter it): with manual, the flash LED lights at full strength for
        // the frame instead, exposed at the manual ISO and shutter.
        val manualFlash = singleFlash && controller?.manual != null
        val steadyLight = flashMode == Settings.FLASH_STEADY
        val flashPair = flashMode == Settings.FLASH_OVERPOWER
        val flashWhiteChoice = store[Settings.FLASH_WHITE].toInt()
        // A single RAW flash with auto white: an ambient frame first, only to see where the
        // flash lit (it steers a smooth white map; the pixels are the flash frame's).
        val ambientNeutral = controller?.ambientNeutral
        val flashNeutral = controller?.flashNeutral()
        val whiteMap = if (singleFlash && !manualFlash && rawCapture && flashWhiteChoice == 0 && ambientNeutral != null && flashNeutral != null)
            floatArrayOf(*ambientNeutral, *flashNeutral) else null
        val hdr = rawCapture && store.flag(Settings.HDR) && (flashMode == 0 || steadyLight)
        val metered = controller?.live
        // 35 mm equivalent of the lens at the current zoom, for the photo's EXIF.
        val focal35 = camera?.cameraInfo?.let { info ->
            (equivalentMm(info) * (info.zoomState.value?.zoomRatio ?: 1f)).roundToInt().takeIf { it > 0 }
        }
        val under = when {
            flashPair -> 1.5 // headroom for what the flash adds: a flash frame clipping loses its colour
            hdr -> hdrUnder(store[Settings.HDR_UNDER].toDouble())
            else -> 0.0
        }
        // A single flash leaves the exposure to the HAL's flash metering: nothing is held.
        val still = if (singleFlash) null else controller?.stillExposure(under, raw = rawCapture)
        if (still != null) controller?.hold(still)
        // Without an ambient frame first the flash is armed now, else just before its frame.
        val armFlash = {
            controller?.armFlash(true)
            runCatching { capture.flashMode = ImageCapture.FLASH_MODE_ON }.onFailure { Log.w(TAG, "flash mode", it) }
            Unit
        }
        if (singleFlash && whiteMap == null && !manualFlash) armFlash()
        // Exposure bracket: one longer frame after the short ones lifts the shadows; the
        // merge brings it to the short frames' exposure and drops what it clipped.
        val longStops = if (hdr) store[Settings.HDR_LONG].toDouble() else 0.0
        // The flash mix: [ambient gain, flash target, flash->ambient white r g b].
        val flashMix = if (flashPair && still != null) {
            // Per light (auto), or one white for the whole frame: the flash's or the room's.
            val white = (if (flashWhiteChoice == 0) controller?.flashWhite() else null) ?: floatArrayOf(1f, 1f, 1f)
            floatArrayOf(Math.pow(2.0, -store[Settings.FLASH_UNDER].toDouble()).toFloat(), 0.35f, *white)
        } else null
        val bracketBase = still ?: metered
        val longExposure = if (longStops >= 0.1 && bracketBase != null && controller != null)
            controller.longExposure(bracketBase, longStops) else null
        Log.i(TAG, "shot $stamp ${if (hdr) "hdr" else if (rawCapture) "raw" else "jpeg"} still ${still?.label() ?: "auto"}" +
            " long ${longExposure?.label() ?: "-"} (${controller?.describe() ?: "no meter"})")
        // The photo's record: camera, capture plan, live exposure / white balance / focus,
        // recipe and every setting at the shot; the develop and the result are added later.
        val log = ShotLog()
        log.put("拍摄", "时间", stamp)
        log.put("拍摄", "格式", if (rawCapture) "RAW（DNG）冲洗" else "JPEG 冲洗")
        log.put("拍摄", "模式", when {
            manualFlash -> "单张闪光（手动曝光：闪光灯全功率常亮一帧）"
            singleFlash -> "单张闪光"
            flashPair -> "闪光${Settings.flashModes[flashMode]}（两帧合成）"
            hdr -> if (steadyLight) "HDR · 补光常亮" else "HDR"
            steadyLight -> "补光常亮"
            else -> "普通"
        })
        if (steadyLight) log.put("拍摄", "补光亮度", "%.2f".format(Locale.US, store[Settings.FLASH_FILL]))
        log.put("拍摄", "拍摄前压暗", if (under > 0) "%.1f 档".format(Locale.US, under) else "无")
        if (hdr) meter?.let { m ->
            log.put("拍摄", "取景器高光 (99.5%) / 过曝比例",
                "%.3f / %.2f%%".format(Locale.US, m.highlight, 100 * m.clipped))
        }
        log.put("拍摄", "锁定曝光", still?.label() ?: "无（自动曝光）")
        log.put("拍摄", "长曝光帧", longExposure?.label())
        log.put("拍摄", "取景器亮度 key", meter?.key)
        recordCamera(log, focal35)
        controller?.record(log)
        recordRecipe(log, recipe)
        haptics.quiet = true
        val release = {
            main.post {
                capturing = false
                if (!shotClacked) clack() // no start was reported: close the shot here
                if (singleFlash) runCatching { capture.flashMode = ImageCapture.FLASH_MODE_OFF }
                if (still != null || longExposure != null || singleFlash) controller?.release()
                if (manualFlash) controller?.lightTorch(false)
            }
        }
        val settle = if (still != null) 250L else 0L
        if (rawCapture) {
            val shorts = when {
                whiteMap != null -> 1 // the ambient frame; the flash frame follows
                flashMix != null -> 1 // one ambient frame: the less time between the two, the less ghosting
                hdr -> store[Settings.HDR_FRAMES].toInt().coerceIn(2, if (longExposure != null) 5 else 6)
                else -> 1
            }
            val count = shorts + if (longExposure != null || flashMix != null || whiteMap != null) 1 else 0
            val flashFrame = if (whiteMap != null) count - 1 else -1
            val developed = if (whiteMap != null) count - 1 else 0 // the frame whose pixels are kept
            val exposures = List<Exposure?>(shorts) { i -> if (manualFlash && i == 0) controller?.manual else null } +
                listOfNotNull(longExposure ?: if (flashMix != null) still else null)
            val torchFrame = if (flashMix != null) count - 1 else if (manualFlash) 0 else -1
            val keepRaw = store.flag(Settings.KEEP_RAW)
            val files = (0 until count).map { File(cacheDir, "FILM_${stamp}_$it.dng") }
            // The viewfinder's brightness at the shot: the develop matches it, which also
            // undoes any darker hold. Without a reading, push back explicitly.
            val key = meter?.key ?: 0f
            val pushBack = if (still != null && metered != null)
                (Math.log(metered.total / still.total) / Math.log(2.0)).toFloat() else 0f
            // The DNG headers CameraX writes repeat an old capture result (exposure, white
            // balance, noise model); take them from the live camera state at the shot instead.
            // A single flash: its exposure and colour are the flash's (HAL-metered); the
            // white is the calibrated flash light, the noise model the file's own.
            val (neutral, noiseS, noiseO) = when {
                singleFlash -> Triple(if (flashWhiteChoice == 2) ambientNeutral else flashNeutral, 0f, 0f)
                flashPair && flashWhiteChoice == 1 -> Triple(flashNeutral, 0f, 0f).let { (n, _, _) ->
                    val m = controller?.stillMetadata(still); Triple(n ?: m?.first, m?.second ?: 0f, m?.third ?: 0f)
                }
                else -> controller?.stillMetadata(still) ?: Triple(null, 0f, 0f)
            }
            val developOptions = options.copy(
                exposureEv = rawEv + if (key > 0f || singleFlash) 0f else pushBack,
                // a flash frame is brighter than the viewfinder showed: keep the HAL's exposure
                matchKey = if (singleFlash) 0f else key,
                localTone = if (hdr) store[Settings.HDR_TONE] else 0f,
                chromaDenoise = store[Settings.CHROMA_NR],
                rawDenoise = store[Settings.RAW_DENOISE],
                neutral = neutral,
                noiseScale = noiseS,
                noiseOffset = noiseO,
                flash = flashMix,
                flashWhite = whiteMap,
                // An HDR burst: the held exposures, so equal frames skip the CPU measurement.
                frameExposure = if (hdr && flashMix == null && whiteMap == null && still != null)
                    FloatArray(count) { i -> (exposures.getOrNull(i) ?: still).total.toFloat() } else null,
            )
            for (i in 0 until count) {
                val what = when (i) {
                    torchFrame -> "闪光帧（LED 常亮），锁定 ${exposures.getOrNull(i)?.label() ?: still?.label() ?: "自动"}"
                    flashFrame -> "闪光帧（真闪光，曝光由手机预闪测光决定）"
                    else -> exposures.getOrNull(i)?.label()?.let { "锁定 $it" } ?: still?.label()?.let { "锁定 $it" } ?: "自动曝光"
                }
                log.put("每帧（拍摄计划）", "帧 $i", what + if (i == developed) "（成片取自这一帧）" else "")
            }
            val dev = "冲洗参数（传给引擎）"
            log.put(dev, "曝光补偿 (RAW EV)", "%+.2f EV".format(Locale.US, developOptions.exposureEv))
            log.put(dev, "对齐取景器亮度", if (developOptions.matchKey > 0) "%.5f".format(Locale.US, developOptions.matchKey) else "不对齐")
            log.put(dev, "白平衡中性点", developOptions.neutral ?: "用 DNG 自带")
            log.put(dev, "噪声模型 (s o)", if (developOptions.noiseScale > 0) "%.4g %.4g".format(Locale.US, developOptions.noiseScale, developOptions.noiseOffset) else "用 DNG 自带")
            log.put(dev, "RAW 降噪 / 彩色降噪", "%.2f / %.2f".format(Locale.US, developOptions.rawDenoise, developOptions.chromaDenoise))
            log.put(dev, "HDR 影调压缩", developOptions.localTone)
            log.put(dev, "闪光合成 [环境增益 目标 白R G B]", developOptions.flash)
            log.put(dev, "分区白平衡 [环境R G B 闪光R G B]", developOptions.flashWhite)
            log.put(dev, "工作尺寸长边", developOptions.maxLongEdge)
            log.put(dev, "颗粒种子", developOptions.seed)
            val shoot = {
                captureBurst(capture, files, exposures, 0, torchFrame, flashFrame, armFlash) { error ->
                    release()
                    if (error != null) {
                        files.forEach { it.delete() }
                        failed(error)
                        return@captureBurst
                    }
                    engineThread.later {
                        // What each frame really got, against what was asked (the diagnosis
                        // for exposures that do not land).
                        Log.i(TAG, "shot $stamp metadata neutral ${neutral?.joinToString(" ") ?: "dng"} noise $noiseS $noiseO")
                        // Shooting data from the reference DNG (and the long frame's exposure).
                        // Lens data from the DNG; the exposure from what was held or metered.
                        val taken = still ?: metered
                        val shot = ShotInfo.fromDng(files[developed].path)?.let {
                            // a flash frame's exposure is the HAL's own: the one the DNG records
                            // (what the frame really got), not what was metered or held
                            if (singleFlash) it.copy(focal35 = it.focal35 ?: focal35)
                            else it.copy(focal35 = it.focal35 ?: focal35,
                                exposureNs = taken?.timeNs ?: it.exposureNs, iso = taken?.iso ?: it.iso)
                        }
                        val kind = when {
                            singleFlash -> if (whiteMap != null) "闪光 · 分区白平衡" else "闪光"
                            flashMix != null -> "闪光${Settings.flashModes[flashMode]}"
                            longExposure != null -> (if (steadyLight) "补光 · " else "") + "HDR 包围 $shorts+1 帧 · 长曝光 ${longExposure.label().replace("ISO", "· ISO")}" 
                            count > 1 -> (if (steadyLight) "补光 · " else "") + "HDR $count 帧"
                            steadyLight -> "RAW · 补光"
                            else -> "RAW"
                        }
                        finish(stamp, recipe, quality, kind, shot, log) {
                            try {
                                engine().developDng(files.map { it.path }, params, developOptions).also {
                                    if (keepRaw) Photos.saveDng(this@CameraActivity, files[developed], "FILM_$stamp.dng", store.flag(Settings.PRIVATE_PHOTOS))
                                }
                            } finally {
                                files.forEach { it.delete() }
                            }
                        }
                    }
                }
            }
            main.postDelayed(shoot, settle)
        } else {
            // manual flash: the LED lights first and gets a couple of frames to reach full strength
            val torchWait = if (manualFlash) controller?.manual?.let { 200 + 2 * it.timeNs / 1_000_000 } ?: 250L else 0L
            if (manualFlash) controller?.lightTorch(true)
            main.postDelayed({
                capture.takePicture(engineThread, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureStarted() = exposureStarted(frameMs(controller?.manual), last = true)
                    override fun onCaptureSuccess(image: ImageProxy) {
                        release()
                        val shot = jpegShot(image)?.let { it.copy(focal35 = it.focal35 ?: focal35) }
                        finish(stamp, recipe, quality, if (singleFlash) "JPEG · 闪光" else if (steadyLight) "JPEG · 补光" else "JPEG", shot, log) {
                            engine().develop(upright(image), params, options)
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        release()
                        failed(exception)
                    }
                })
            }, settle + torchWait)
        }
    }

    /**
     * Zero-lag HDR: the frames the session already holds (the newest the reference) and,
     * with HDR_LONG, one longer frame right after; developed from memory.
     */
    private fun takeZsl(session: ZslCamera) {
        if (capturing) {
            status.text = "上一张还在拍摄…"
            return
        }
        // Each burst waiting to develop holds ~180 MB: two at most.
        if (developing >= 2) {
            status.text = "还在${work()}前两张，稍等…"
            return
        }
        capturing = true
        shotClacked = false
        shutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120)
        }
        val params = store.filmParams()
        val recipe = params + (LUT_KEY to currentLut().first.toFloat()) + (LUT_STRENGTH_KEY to currentLut().second)
        val options = DevelopOptions(maxLongEdge = store.longEdge, seed = Random.nextInt(), displayP3 = false)
        val quality = store[Settings.JPEG_QUALITY].toInt()
        val rawEv = store[Settings.RAW_EV]
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        developing++
        status.text = "${work()}中（$developing 张）…"
        val controller = exposure
        val ring = controller?.live // the frames' exposure (under the meter)
        val longStops = store[Settings.HDR_LONG].toDouble()
        val long = if (longStops >= 0.1 && ring != null && controller != null) controller.longExposure(ring, longStops) else null
        val key = meter?.key ?: 0f // as the viewfinder showed it
        val focal35 = boundInfo?.let { (equivalentMm(it) * session.zoom).roundToInt().takeIf { mm -> mm > 0 } }
        val log = ShotLog()
        log.put("拍摄", "时间", stamp)
        log.put("拍摄", "格式", "RAW（DNG）冲洗")
        log.put("拍摄", "模式", "HDR（零延迟连拍）")
        log.put("拍摄", "拍摄前压暗", "%.2f 档（取景器提亮同样多）".format(Locale.US, controller?.under ?: 0.0))
        meter?.let { m -> log.put("拍摄", "取景器高光 (99.5%) / 过曝比例", "%.3f / %.2f%%".format(Locale.US, m.highlight, 100 * m.clipped)) }
        log.put("拍摄", "连拍帧曝光", ring?.label())
        log.put("拍摄", "长曝光帧", long?.label())
        log.put("拍摄", "取景器亮度 key", key)
        recordCamera(log, focal35)
        controller?.record(log)
        recordRecipe(log, recipe)
        val orientation = zslRotation(session.characteristics)
        val started = SystemClock.uptimeMillis()
        // The burst length set in the settings; at most six frames reach the merge (five
        // from the ring when a long one follows).
        val frames = store[Settings.HDR_FRAMES].toInt().coerceIn(2, if (long != null) ZslCamera.RING - 1 else ZslCamera.RING)
        haptics.quiet = long != null // the ring frames are already taken; the long one is not
        // Zero lag: the frames were exposed as the button went down. The curtain shuts now
        // and opens after the reference frame's exposure, or after the long frame's.
        exposureStarted(frameMs(ring), last = long == null)
        session.shoot(frames, long, orientation, taken = { main.post { capturing = false; if (!shotClacked) clack() } }) { shot ->
            main.post { capturing = false; if (!shotClacked) clack() }
            if (shot == null) {
                runOnUiThread { developing--; status.text = "拍摄失败：连拍帧还没准备好，请再按一次"; haptics.play(Haptics.Event.ERROR) }
                return@shoot
            }
            log.put("拍摄", "取帧用时", "%d ms（%d 帧%s）".format(Locale.US, SystemClock.uptimeMillis() - started,
                shot.dngs.size, if (shot.long) "，含长曝光" else ""))
            for (i in shot.dngs.indices)
                log.put("每帧（拍摄计划）", "帧 $i", (if (i == shot.dngs.size - 1 && shot.long) "长曝光 " else "连拍 ") +
                    "曝光量 %.0f".format(Locale.US, shot.exposures[i] / 1e6) + if (i == 0) "（参考帧，最接近按快门的一帧）" else "")
            val developOptions = options.copy(
                exposureEv = rawEv,
                matchKey = key,
                localTone = store[Settings.HDR_TONE],
                chromaDenoise = store[Settings.CHROMA_NR],
                rawDenoise = store[Settings.RAW_DENOISE],
                frameExposure = shot.exposures,
            )
            val dev = "冲洗参数（传给引擎）"
            log.put(dev, "曝光补偿 (RAW EV)", "%+.2f EV".format(Locale.US, developOptions.exposureEv))
            log.put(dev, "对齐取景器亮度", "%.5f".format(Locale.US, developOptions.matchKey))
            log.put(dev, "RAW 降噪 / 彩色降噪", "%.2f / %.2f".format(Locale.US, developOptions.rawDenoise, developOptions.chromaDenoise))
            log.put(dev, "HDR 影调压缩", developOptions.localTone)
            engineThread.later {
                val ref = shot.reference
                val info = ShotInfo(
                    exposureNs = ref.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                    iso = ref.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
                    fNumber = ref.get(android.hardware.camera2.CaptureResult.LENS_APERTURE)?.toDouble(),
                    focalMm = ref.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)?.toDouble(),
                    focal35 = focal35,
                    make = android.os.Build.MANUFACTURER,
                    model = android.os.Build.MODEL,
                    takenMs = System.currentTimeMillis(),
                )
                if (store.flag(Settings.KEEP_RAW)) runCatching {
                    val file = File(cacheDir, "FILM_$stamp.dng")
                    shot.dngs[0].save(file)
                    Photos.saveDng(this@CameraActivity, file, "FILM_$stamp.dng", store.flag(Settings.PRIVATE_PHOTOS))
                    file.delete()
                }
                val kind = "HDR 零延迟 ${shot.dngs.size} 帧" + if (shot.long) "（含长曝光）" else ""
                finish(stamp, recipe, quality, kind, info, log) {
                    try {
                        engine().developDng(shot.dngs.map { it.handle }, params, developOptions)
                    } finally {
                        shot.release()
                    }
                }
            }
        }
    }

    /**
     * Captures DNGs one after another into [files], then reports (null = success). A
     * non-null [exposures] entry is held on the sensor first, and the frame is taken once
     * the viewfinder reports it (or after a timeout).
     */
    private fun captureBurst(capture: ImageCapture, files: List<File>, exposures: List<Exposure?>, index: Int,
                             torchFrame: Int, flashFrame: Int = -1, armFlash: () -> Unit = {},
                             done: (ImageCaptureException?) -> Unit) {
        if (index == flashFrame) armFlash()
        if (index >= files.size) return done(null)
        if (files.size > 1) status.text = (if (torchFrame >= 0) "闪光 " else "HDR 连拍 ") + "${index + 1}/${files.size}…"
        val shoot = {
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(files[index]).build(), mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onCaptureStarted() =
                        exposureStarted(frameMs(exposures.getOrNull(index)), last = index == files.size - 1)
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        if (index == torchFrame) exposure?.lightTorch(false)
                        captureBurst(capture, files, exposures, index + 1, torchFrame, flashFrame, armFlash, done)
                    }
                    override fun onError(exception: ImageCaptureException) = done(exception)
                })
        }
        val target = exposures.getOrNull(index)
        val controller = exposure
        if (target == null || controller == null) return shoot()
        if (index == torchFrame) {
            // The LED needs a couple of frames to light fully at the held exposure.
            controller.lightTorch(true)
            main.postDelayed(shoot, 200 + 2 * target.timeNs / 1_000_000)
            return
        }
        controller.hold(target)
        // A few frames at the new exposure: the time it takes to land plus margin.
        val deadline = SystemClock.uptimeMillis() + 400 + 4 * target.timeNs / 1_000_000
        awaitExposure(controller, target, deadline, shoot)
    }

    private fun awaitExposure(controller: FilmExposure, target: Exposure, deadline: Long, then: () -> Unit) {
        val live = controller.live
        val landed = live != null && abs(live.timeNs - target.timeNs) <= target.timeNs / 20 &&
            abs(live.iso - target.iso) <= target.iso / 20 + 1
        if (landed) main.postDelayed(then, 2 * target.timeNs / 1_000_000 + 30) // one more frame for the still
        else if (SystemClock.uptimeMillis() >= deadline) then()
        else main.postDelayed({ awaitExposure(controller, target, deadline, then) }, 20)
    }

    /**
     * How far under the meter an HDR burst is held: just enough that the viewfinder's
     * brightest 0.5% lands at 80% of white (the merge and the tone mapping bring the
     * rest back), up to [most]. Clipped samples hide how far over they are: then all of
     * it. A low-contrast scene is held barely under and keeps its signal.
     */
    private fun hdrUnder(most: Double): Double {
        val m = meter ?: return most
        if (m.clipped > 0.005f || m.highlight <= 0f) return most
        val need = ln(m.highlight / 0.8) / ln(2.0) + 0.3
        return need.coerceIn(0.3, most)
    }

    private var flashDock: org.photoall.film.ui.FlashDock? = null
    private var flashAnchor = android.graphics.PointF() // the flash button's centre, on the picture
    private val closeFlashSoon = Runnable { closeFlashModes() }
    private val flashBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeFlashModes()
    }

    /**
     * The flash modes on a strip of frosted glass that grows out of the flash button onto
     * the viewfinder's nearest edge (the top in portrait, the left side in landscape). Where
     * the readout, the histogram or a control already sits on that edge, the strip moves
     * further in to clear it. A choice slides the accent plate there and the strip folds
     * back into the button; a tap on the picture, back, or a few idle seconds fold it too.
     */
    private fun showFlashModes(anchor: View) {
        if (flashDock != null) { closeFlashModes(); return }
        if (exposure?.hasFlash != true) {
            status.text = "这个镜头没有闪光灯"
            haptics.play(Haptics.Event.ERROR)
            return
        }
        val frame = viewfinderFrame
        if (frame.width == 0) return
        // the button's centre in the picture's coordinates, and which edge it is beyond
        val a = IntArray(2).also { anchor.getLocationInWindow(it) }
        val f = IntArray(2).also { frame.getLocationInWindow(it) }
        val ax = a[0] + anchor.width / 2f - f[0]
        val ay = a[1] + anchor.height / 2f - f[1]
        flashAnchor.set(ax, ay)
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        val beyond = mapOf('T' to -ay, 'B' to ay - h, 'L' to -ax, 'R' to ax - w)
        val edge = beyond.maxByOrNull { it.value }!!.let { if (it.value > 0f) it.key else
            listOf('T' to ay, 'B' to h - ay, 'L' to ax, 'R' to w - ax).minByOrNull { p -> p.second }!!.first }
        val vertical = edge == 'L' || edge == 'R'
        val dock = org.photoall.film.ui.FlashDock(this, vertical).apply {
            setModes(Settings.flashModes, flashMode()) { i -> i != Settings.FLASH_OVERPOWER || rawCapture }
            onBlocked = { status.text = "压光需要先在参数里开启 RAW"; haptics.play(Haptics.Event.ERROR) }
            onPick = { i ->
                store[Settings.FLASH] = i.toFloat()
                haptics.play(Haptics.Event.SEGMENT)
                main.removeCallbacks(closeFlashSoon)
                main.postDelayed(closeFlashSoon, 380) // let the plate land first
            }
        }
        dock.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val dw = dock.measuredWidth.toFloat()
        val dh = dock.measuredHeight.toFloat()
        val margin = dp(10).toFloat()
        // along the edge: level with the button, kept inside the picture
        var x = when (edge) { 'L' -> margin; 'R' -> w - dw - margin; else -> (ax - dw / 2).coerceIn(margin, w - dw - margin) }
        var y = when (edge) { 'T' -> margin; 'B' -> h - dh - margin; else -> (ay - dh / 2).coerceIn(margin, h - dh - margin) }
        // The strip always lies against the edge. Along it, it takes the nearest place to the
        // button that touches nothing on the picture (the readout, the histogram, the
        // controls; not the passing rotate offer). With no such place it stays level with the
        // button, and the readout or histogram under it fades out while it is open.
        val readout = overlay.readoutRect()
        val hist = overlay.histogramRect()
        val taken = mutableListOf<android.graphics.RectF>().apply {
            if (!readout.isEmpty) add(readout)
            if (!hist.isEmpty) add(hist)
        }
        for (i in 0 until frame.childCount) {
            val c = frame.getChildAt(i)
            if (c === viewfinder || c === curtain || c === overlay || c === rotateButton || c.visibility != View.VISIBLE || c.width == 0) continue
            taken += android.graphics.RectF(c.left + c.translationX, c.top + c.translationY,
                c.right + c.translationX, c.bottom + c.translationY)
        }
        fun clear(px: Float, py: Float) = taken.none { android.graphics.RectF.intersects(it, android.graphics.RectF(px, py, px + dw, py + dh)) }
        val along = if (vertical) y else x
        val lo = margin
        val hi = (if (vertical) h - dh else w - dw) - margin
        val step = dp(4).toFloat()
        var found: Float? = null
        var d = 0f
        while (found == null && (along - d >= lo || along + d <= hi)) {
            for (p in floatArrayOf(along - d, along + d)) {
                if (p < lo || p > hi) continue
                if (if (vertical) clear(x, p) else clear(p, y)) { found = p; break }
            }
            d += step
        }
        if (found != null) { if (vertical) y = found!! else x = found!! }
        val box = android.graphics.RectF(x, y, x + dw, y + dh)
        val coverReadout = !readout.isEmpty && android.graphics.RectF.intersects(readout, box)
        val coverHist = !hist.isEmpty && android.graphics.RectF.intersects(hist, box)
        android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 200
            addUpdateListener {
                val a = it.animatedValue as Float
                if (coverReadout) overlay.readoutAlpha = a
                if (coverHist) overlay.histogramAlpha = a
            }
            if (coverReadout || coverHist) start()
        }
        x = x.coerceIn(0f, maxOf(0f, w - dw))
        y = y.coerceIn(0f, maxOf(0f, h - dh))
        frame.addView(dock, FrameLayout.LayoutParams(-2, -2))
        dock.x = x
        dock.y = y
        flashDock = dock
        flashBack.isEnabled = true
        // frosted glass: a blurred still of the picture under it
        dock.frostSize.set(w, h)
        dock.frostOrigin.set(x, y)
        val vw = viewfinder.width
        val vh = viewfinder.height
        if (vw > 0 && vh > 0) runCatching {
            val still = android.graphics.Bitmap.createBitmap(maxOf(1, vw / 4), maxOf(1, vh / 4), android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(viewfinder, still, { result ->
                if (result == android.view.PixelCopy.SUCCESS && flashDock === dock) dock.frost = frosted(still)
            }, main)
        }
        // it grows out of the button: from a small blot at the button's centre to its place
        dock.pivotX = (ax - x).coerceIn(0f, dw)
        dock.pivotY = (ay - y).coerceIn(0f, dh)
        dock.alpha = 0f
        dock.scaleX = if (vertical) 0.5f else 0.2f
        dock.scaleY = if (vertical) 0.2f else 0.5f
        dock.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(340)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1.12f)).start()
        dock.stagger(fromStart = if (vertical) ay < y + dh / 2 else ax < x + dw / 2)
        haptics.play(Haptics.Event.SEGMENT)
        main.removeCallbacks(closeFlashSoon)
        main.postDelayed(closeFlashSoon, 5000)
        coach?.event("flash_open")
    }

    /** The strip folds back into the flash button and goes. */
    private fun closeFlashModes() {
        val dock = flashDock ?: return
        coach?.event("flash_close")
        val fromReadout = overlay.readoutAlpha
        val fromHist = overlay.histogramAlpha
        if (fromReadout < 1f || fromHist < 1f) android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            startDelay = 80
            addUpdateListener {
                val k = it.animatedValue as Float
                overlay.readoutAlpha = fromReadout + (1f - fromReadout) * k
                overlay.histogramAlpha = fromHist + (1f - fromHist) * k
            }
            start()
        }
        flashDock = null
        flashBack.isEnabled = false
        main.removeCallbacks(closeFlashSoon)
        dock.isEnabled = false
        dock.animate().cancel()
        dock.animate().alpha(0f).scaleX(if (dock.width > dock.height) 0.2f else 0.5f)
            .scaleY(if (dock.width > dock.height) 0.5f else 0.2f).setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .withEndAction { (dock.parent as? android.view.ViewGroup)?.removeView(dock) }.start()
    }

    /** The flash mode in use: 0 off; a lens with a flash; the mixed mode (overpower) needs RAW. */
    private fun flashMode(): Int {
        if (exposure?.hasFlash != true) return 0
        val mode = store[Settings.FLASH].toInt().coerceIn(0, Settings.flashModes.lastIndex)
        return if (mode == Settings.FLASH_OVERPOWER && !rawCapture) 0 else mode
    }

    private fun engine() = checkNotNull(engine) { "引擎未就绪" }

    /** The phone, the lens in use and its sensor, into a photo's record. */
    private fun recordCamera(log: ShotLog, focal35: Int?) {
        val sec = "相机与镜头"
        log.put(sec, "手机", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}（${android.os.Build.DEVICE}）")
        log.put(sec, "Android", "${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
        log.put(sec, "应用版本", runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull())
        val info = camera?.cameraInfo ?: return
        val c = Camera2CameraInfo.from(info)
        fun <T> ch(key: CameraCharacteristics.Key<T>): T? = runCatching { c.getCameraCharacteristic(key) }.getOrNull()
        log.put(sec, "摄像头 ID", c.cameraId)
        log.put(sec, "朝向", when (ch(CameraCharacteristics.LENS_FACING)) { 0 -> "前置"; 1 -> "后置"; 2 -> "外接"; else -> "?" })
        log.put(sec, "镜头按钮", zoomLabel.text)
        log.put(sec, "变焦倍数", info.zoomState.value?.zoomRatio)
        log.put(sec, "焦距（可用）", ch(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(" ") { "%.2f mm".format(Locale.US, it) })
        log.put(sec, "等效焦距（当前变焦）", focal35?.let { "$it mm" })
        log.put(sec, "光圈（可用）", ch(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString(" ") { "f/%.2f".format(Locale.US, it) })
        log.put(sec, "传感器物理尺寸", ch(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { "%.2f × %.2f mm".format(Locale.US, it.width, it.height) })
        log.put(sec, "像素阵列 / 有效区域", "${ch(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)} / ${ch(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.flattenToString()}")
        log.put(sec, "传感器白电平", ch(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
        log.put(sec, "CFA 排列", when (ch(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            0 -> "RGGB"; 1 -> "GRBG"; 2 -> "GBRG"; 3 -> "BGGR"; 4 -> "RGB"; 5 -> "单色"; 6 -> "NIR"; else -> "?" })
        log.put(sec, "硬件级别", when (ch(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL_3"; 4 -> "EXTERNAL"; else -> "?" })
        log.put(sec, "最短对焦距离", ch(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { if (it > 0) "%.2f m".format(Locale.US, 1 / it) else "定焦" })
    }

    /** The film recipe and every setting at the shot. */
    private fun recordRecipe(log: ShotLog, recipe: Map<String, Float>) {
        val sec = "胶片配方"
        val filmOn = recipe["stage film"] != 0f
        log.put(sec, "胶片", if (filmOn) FilmEngine.filmStocks.getOrNull(recipe["film"]?.toInt() ?: -1) else "关（直出）")
        log.put(sec, "相纸", if (recipe["stage print"] != 0f) FilmEngine.papers.getOrNull(recipe["paper"]?.toInt() ?: -1) else "关（直接扫描）")
        log.put(sec, "LUT", "编号 ${(recipe[LUT_KEY] ?: 0f).toInt()}（0 = 无），强度 %.2f".format(Locale.US, recipe[LUT_STRENGTH_KEY] ?: 0f))
        for ((k, v) in recipe) if (k != LUT_KEY && k != LUT_STRENGTH_KEY) log.put("胶片参数（引擎）", k, "%.4g".format(Locale.US, v))
        for (setting in Settings.all) {
            val v = store[setting.key]
            val text = when (setting) {
                is Setting.Toggle -> if (v >= 0.5f) "开" else "关"
                is Setting.Slider -> setting.format.format(Locale.US, v)
                is Setting.Choice -> setting.options.getOrElse(v.toInt()) { "%.0f".format(Locale.US, v) }
            }
            log.put("全部设置", "[${setting.group}] ${setting.label}", text)
        }
    }

    /** EXIF of a JPEG still, before it is decoded. */
    private fun jpegShot(image: ImageProxy): ShotInfo? {
        if (image.format != ImageFormat.JPEG) return null
        val buffer = image.planes[0].buffer.duplicate()
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return ShotInfo.fromStream(ByteArrayInputStream(bytes))
    }

    private fun upright(image: ImageProxy): Bitmap {
        val rotation = image.imageInfo.rotationDegrees
        val frame = image.use { it.toBitmap() }
        if (rotation == 0) return frame
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    // Engine thread: develop, save, report.
    private fun finish(stamp: String, recipe: Map<String, Float>, quality: Int, source: String, shot: ShotInfo?,
                       log: ShotLog?, develop: () -> Developed) {
        val start = System.nanoTime()
        val message = try {
            // The creative LUT chosen at the shot (the engine may have moved on since).
            val lut = (recipe[LUT_KEY] ?: 0f).toInt() to (recipe[LUT_STRENGTH_KEY] ?: 0f)
            // A CCD camera: developed without the LUT, then the whole camera on the CPU (its
            // noise at this shot's ISO, blooming, fringing, noise reduction, sharpening).
            val digicam = LutLibrary.digicam(lut.first)?.takeIf { lut.second > 0f }
            applyLut(if (digicam != null) 0 to 0f else lut)
            val developed = develop()
            val ccdStart = System.nanoTime()
            val image = if (digicam == null) developed.bitmap else
                org.photoall.film.engine.Digicam.apply(this, developed.bitmap, digicam, iso = shot?.iso ?: 100,
                    strength = lut.second, seed = Random.nextInt()).also { if (it !== developed.bitmap) developed.bitmap.recycle() }
            val developedAt = System.nanoTime()
            val filmOn = recipe["stage film"] != 0f
            val stock = FilmEngine.filmStocks[recipe.getValue("film").toInt()]
            val name = "FILM_${stamp}_${(if (filmOn) stock else "digital").replace(Regex("[^a-z0-9]+"), "-")}.jpg"
            val paper = FilmEngine.papers[recipe.getValue("paper").toInt().coerceIn(0, FilmEngine.papers.lastIndex)]
            // What the gallery shows as the photo's recipe.
            val text = recipeText(filmOn, recipe["stage print"] != 0f, stock, paper, lut.first)
            Photos.saveJpeg(this, image, name, quality, "$text · $source", shot,
                private = store.flag(Settings.PRIVATE_PHOTOS))
            saveFramed(image, name, quality, "$text · $source", shot, if (filmOn) stock else "直出")
            val savedAt = System.nanoTime()
            runOnUiThread { advanceRoll(); updateLabels() }
            if (log != null) {
                log.put("拍摄", "类型", source)
                log.put("胶片配方", "配方", text)
                log.putEngine(developed.info)
                val res = "冲洗结果"
                log.put(res, "文件", name)
                log.put(res, "成片尺寸", "${image.width}x${image.height}")
                if (digicam != null) log.put(res, "CCD 相机模拟", "%s · ISO %d · %.0f ms".format(Locale.US, digicam,
                    (shot?.iso ?: 100).coerceIn(100, 1600), (developedAt - ccdStart) / 1e6))
                log.put(res, "JPEG 质量", quality)
                log.put(res, "冲洗用时", "%.2f 秒".format(Locale.US, (System.nanoTime() - start) / 1e9))
                log.put(res, "各段用时", "读取 RAW 并冲洗 %.0f ms，保存 JPEG %.0f ms".format(Locale.US,
                    (developedAt - start) / 1e6, (savedAt - developedAt) / 1e6))
                log.put(res, "GPU 时间", "%.1f ms".format(Locale.US, developed.gpuMs))
                log.put(res, "GPU", runCatching { engine().deviceName }.getOrNull())
                shot?.let {
                    val exif = "写入照片的 EXIF"
                    log.put(exif, "快门", it.shutterText ?: "未写入")
                    log.put(exif, "ISO", it.iso ?: "未写入")
                    log.put(exif, "光圈", it.fNumber?.let { f -> "f/%.2f".format(Locale.US, f) })
                    log.put(exif, "焦距 / 等效", "${it.focalMm?.let { f -> "%.2f mm".format(Locale.US, f) }} / ${it.focal35?.let { f -> "$f mm" }}")
                    log.put(exif, "机型", listOfNotNull(it.make, it.model).joinToString(" "))
                }
                log.save(this, name)
            }
            val bitmap = image
            val thumb = Bitmap.createScaledBitmap(bitmap, 300, 300 * bitmap.height / bitmap.width, true)
            runOnUiThread {
                // the new photo lands in the corner
                thumbnail.setImageBitmap(thumb)
                thumbnail.scaleX = 0.5f; thumbnail.scaleY = 0.5f; thumbnail.alpha = 0f
                thumbnail.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
            }
            "%s · %s · %dx%d · GPU %.0f ms · %s %.1f 秒".format(if (filmOn) CanisterView.displayName(stock) else "直出", source,
                bitmap.width, bitmap.height, developed.gpuMs, if (filmOn) "冲洗" else "处理", (System.nanoTime() - start) / 1e9)
        } catch (e: Throwable) {
            Log.e(TAG, "develop failed", e)
            "${if (recipe["stage film"] != 0f) "冲洗" else "处理"}失败：${e.message}"
        }
        Log.i(TAG, message)
        val ok = !message.contains("失败")
        runOnUiThread {
            developing--
            status.text = message
            // Only while the camera is in front: a buzz from the pocket means nothing.
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                haptics.play(if (ok) Haptics.Event.DEVELOPED else Haptics.Event.ERROR)
        }
    }

    // ----- the roll: frames counted like a 36-exposure film ------------------------------

    private val rollPrefs by lazy { getSharedPreferences("roll", MODE_PRIVATE) }

    /** Frames already taken on this roll (0..35). */
    private fun rollFrame() = rollPrefs.getInt("frame", 0)

    /** A photo was saved: one frame on; after the 36th a new roll starts. */
    private fun advanceRoll() {
        val next = rollFrame() + 1
        rollPrefs.edit().apply {
            if (next >= ROLL_LENGTH) putInt("frame", 0).putInt("roll", rollPrefs.getInt("roll", 1) + 1)
            else putInt("frame", next)
        }.apply()
    }

    // ----- the shooting-settings sheet -------------------------------------------------

    /**
     * Flash modes, the main switches as tiles, the viewfinder aids and saving RAW, one tap
     * away (the design's 拍摄设置 sheet). Rebuilt whenever a setting changes while open.
     */
    private fun showQuickSheet() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(this, "拍摄设置", body)
        fun build() {
            body.removeAllViews()
            body.addView(sheetTitle("闪光"))
            val mode = flashMode()
            val hasFlash = exposure?.hasFlash == true
            body.addView(Segmented(this, Settings.flashModes).apply {
                enabled = { i -> hasFlash && (i != Settings.FLASH_OVERPOWER || rawCapture) }
                onBlocked = { haptics.play(Haptics.Event.ERROR) }
                select(mode, animate = false)
                onPick = { i -> store[Settings.FLASH] = i.toFloat(); haptics.play(Haptics.Event.SEGMENT) }
            }, LinearLayout.LayoutParams(-1, -2))
            if (!hasFlash) body.addView(sheetNote("这个镜头没有闪光灯"))
            val hdrOn = store.flag(Settings.HDR)
            val long = store[Settings.HDR_LONG] >= 0.1f
            val frames = store[Settings.HDR_FRAMES].toInt().coerceIn(2, if (long) 5 else 6)
            val guides = store[Settings.GUIDES].toInt().coerceIn(0, Settings.guideNames.lastIndex)
            val focus = focusMode()
            body.addView(tileRow(
                tile("HDR 合成", if (hdrOn) "ON" else "OFF",
                    if (!rawCapture) "需要在参数里开 RAW" else if (long) "$frames 张 + 1 长曝 · 零延迟" else "$frames 张 · 零延迟", hdrOn) {
                    store[Settings.HDR] = if (hdrOn) 0f else 1f
                },
                tile("对焦", Settings.focusModes[focus], when (focus) { 0 -> "连续自动 · 双击锁定"; 1 -> "点按锁定"; else -> "手动 · 拖动滑杆" }, focus != 0) {
                    cycleFocusMode()
                },
            ))
            body.addView(tileRow(
                tile("参考线", if (guides == 0) "关" else "开", Settings.guideNames[guides], guides != 0) {
                    stepSetting(Settings.GUIDES, 1)
                },
                tile("手动曝光", if (store.flag(Settings.MANUAL)) "ON" else "OFF",
                    if (store.flag(Settings.MANUAL)) "ISO ${Settings.iso(store[Settings.ISO])} · ${Settings.shutterLabel(store[Settings.SHUTTER])}" else "自动测光",
                    store.flag(Settings.MANUAL)) {
                    store[Settings.MANUAL] = if (store.flag(Settings.MANUAL)) 0f else 1f
                },
            ))
            body.addView(sheetTitle("取景辅助"))
            body.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(this@CameraActivity).apply {
                    for ((name, key) in listOf("直方图" to Settings.HISTOGRAM, "峰值对焦" to Settings.PEAKING,
                        "高光保护" to Settings.HIGHLIGHT, "胶片测光" to Settings.SOFT_3A, "按胶片 ISO" to Settings.FILM_ISO))
                        addView(chip(name, store.flag(key)) { store[key] = if (store.flag(key)) 0f else 1f },
                            LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
                })
            })
            body.addView(switchRow("拍摄时加边框", "另存一张带边框的；样式在相册里点照片的“边框”设计",
                store.flag(Settings.FRAME_AUTO)) {
                store[Settings.FRAME_AUTO] = if (store.flag(Settings.FRAME_AUTO)) 0f else 1f
            })
            body.addView(switchRow("同时保存 RAW", "DNG 原片，可重新冲洗", store.flag(Settings.KEEP_RAW)) {
                store[Settings.KEEP_RAW] = if (store.flag(Settings.KEEP_RAW)) 0f else 1f
            })
            body.addView(Button(this).apply {
                text = "全部参数"
                Style.textButton(this, 14f)
                setTextColor(Style.TEXT_2)
                setOnClickListener { sheet.dismiss(); openSettings() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            body.addView(Button(this).apply {
                text = "重看新手教程"
                Style.textButton(this, 13f)
                setTextColor(Style.TEXT_3)
                setOnClickListener {
                    sheet.dismiss()
                    getSharedPreferences("coach", MODE_PRIVATE).edit().putBoolean("gallery", false).apply()
                    main.postDelayed({ startCoach() }, 350)
                }
            }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        build()
        sheetRefresh = { key -> if (key != Settings.FLASH) build() } // the flash control animates itself
        sheet.setOnDismissListener { sheetRefresh = null; coach?.resume() }
        haptics.play(Haptics.Event.SEGMENT)
        coach?.event("quick_open")
        coach?.pause()
        sheet.show()
    }

    // ----- the film sheet ------------------------------------------------------------------

    /**
     * Choosing the look (the design's 胶片与相纸 sheet): stocks as cartridges by family,
     * the paper (or a straight scan), the creative LUT and its strength, and the film's
     * grain, halation and couplers. The straight-out switch turns the film off.
     */
    private fun showFilmSheet() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(this, "胶片与相纸", body)
        val stocks = FilmEngine.filmStocks
        val families = org.photoall.film.settings.LookFamilies.FILM.map { f -> f to { n: String -> org.photoall.film.settings.LookFamilies.film(n) == f } }
            .filter { (_, test) -> stocks.any(test) }
        var family = families.indexOfFirst { (_, test) -> test(filmName()) }.coerceAtLeast(0)
        var cardsX = -1 // the card strip's scroll, kept across rebuilds (-1: centre the chosen one)
        fun build() {
            body.removeAllViews()
            val filmOn = store.flag(Settings.FILM_ON)
            val printOn = store.flag(Settings.PRINT_ON)
            body.addView(switchRow("直出", "不套胶片，只保留 LUT", !filmOn) {
                store[Settings.FILM_ON] = if (store.flag(Settings.FILM_ON)) 0f else 1f
            })
            // family tabs
            body.addView(LinearLayout(this).apply {
                setPadding(0, dp(8), 0, dp(10))
                families.forEachIndexed { i, (name, _) ->
                    addView(chip(name, i == family) { family = i; cardsX = 0; build() },
                        LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(6) })
                }
            })
            val current = store["film"].toInt()
            val scroller = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val strip = LinearLayout(this)
            var chosenView: View? = null
            stocks.forEachIndexed { i, name ->
                if (!families[family].second(name)) return@forEachIndexed
                val chosen = filmOn && i == current
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(8), dp(10), dp(8), dp(8))
                    background = Style.surface(this@CameraActivity, fill = Style.SURFACE_2, radiusDp = 14f,
                        stroke = if (chosen) Style.ACCENT else Style.HAIRLINE)
                    contentDescription = CanisterView.displayName(name)
                    addView(CanisterView(this@CameraActivity).apply { stock = name }, LinearLayout.LayoutParams(dp(64), dp(82)))
                    addView(View(this@CameraActivity), LinearLayout.LayoutParams(1, dp(8)))
                    addView(label(12f).apply {
                        text = CanisterView.displayName(name)
                        typeface = Style.medium
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-1, -2))
                    addView(label(10f).apply {
                        val iso = Regex("(\\d+)[a-z]?$").find(name)?.groupValues?.get(1) ?: ""
                        text = if (name.contains("vision3")) "手卷 $iso" else "ISO $iso"
                        maxLines = 1
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextColor(Style.TEXT_2)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(-1, -2))
                    Style.pressable(this)
                    setOnClickListener {
                        cardsX = scroller.scrollX
                        if (!store.flag(Settings.FILM_ON)) store[Settings.FILM_ON] = 1f
                        store["film"] = i.toFloat()
                        haptics.play(Haptics.Event.SEGMENT)
                    }
                }
                strip.addView(card, LinearLayout.LayoutParams(dp(112), -2).apply { marginEnd = dp(10) })
                if (chosen) chosenView = card
            }
            scroller.addView(strip)
            body.addView(scroller, LinearLayout.LayoutParams(-1, -2))
            val keep = cardsX
            scroller.post {
                if (keep >= 0) scroller.scrollTo(keep, 0)
                else chosenView?.let { v -> scroller.scrollTo((v.left - (scroller.width - v.width) / 2).coerceAtLeast(0), 0) }
            }
            // paper: a straight scan, or one of the papers
            body.addView(sheetTitle("相纸"))
            val paper = store["paper"].toInt()
            body.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(this@CameraActivity).apply {
                    addView(chip("直接扫描", filmOn && !printOn) { store[Settings.PRINT_ON] = 0f },
                        LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
                    FilmEngine.papers.forEachIndexed { i, name ->
                        addView(chip(paperName(name), filmOn && printOn && i == paper) {
                            store[Settings.PRINT_ON] = 1f
                            store["paper"] = i.toFloat()
                        }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
                    }
                })
            })
            // creative LUT after the film
            body.addView(sheetTitle("LUT（在胶片之后）"))
            val lut = chosenLut()
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(chip("关", !store.flag(Settings.LUT_ON) || lut == 0) { store[Settings.LUT_ON] = 0f },
                    LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
                addView(chip(if (lut == 0) "选择 LUT…" else LutLibrary.name(lut), store.flag(Settings.LUT_ON) && lut != 0) {
                    showLutSheet()
                }, LinearLayout.LayoutParams(0, dp(36), 1f))
            })
            if (store.flag(Settings.LUT_ON) && lut != 0) body.addView(sliderRow("LUT 强度", Settings.LUT_STRENGTH, 0f, 1f))
            // the film's own character
            body.addView(sheetTitle("颗粒与光学"))
            body.addView(LinearLayout(this).apply {
                for ((name, key) in listOf("颗粒" to "grain", "光晕" to "halation", "耦合剂" to "couplers"))
                    addView(chip(name, store.flag(key)) { store[key] = if (store.flag(key)) 0f else 1f },
                        LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) })
            })
            if (store.flag("grain")) body.addView(sliderRow("颗粒大小", "size", 0.01f, 2.5f))
            body.addView(Button(this).apply {
                text = "全部胶片参数"
                Style.textButton(this, 14f)
                setTextColor(Style.TEXT_2)
                setOnClickListener { sheet.dismiss(); openSettings() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        build()
        // A slider being dragged must not be rebuilt under the finger.
        sheetRefresh = { key -> if (key != Settings.LUT_STRENGTH && key != "size") build() }
        sheet.setOnDismissListener { sheetRefresh = null }
        haptics.play(Haptics.Event.SEGMENT)
        sheet.show()
    }

    /**
     * Choosing a creative LUT: search, a family filter, and the list with the current one
     * marked. Picking one applies it (switching LUTs on) and the sheet sinks away.
     */
    private fun showLutSheet() {
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(6)) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // a fixed height: switching families must not make the sheet jump
        val sheet = Sheet(this, "LUT（在胶片之后）", list, header = head, fixedHeight = 0.78f)
        lutSheet = sheet
        // the user's own first among the families, then the camera looks, then the films
        val groups = (1 until LutLibrary.count).map { LutLibrary.group(it) }.filter { it.isNotEmpty() }.distinct()
            .sortedBy { if (it == LutLibrary.USER_GROUP) 0 else 1 }
        var group: String? = null
        var query = ""
        val chips = LinearLayout(this)
        var chosenRow: View? = null
        fun fill() {
            list.removeAllViews()
            chosenRow = null
            val on = store.flag(Settings.LUT_ON)
            val current = if (on) chosenLut() else 0
            for (i in 0 until LutLibrary.count) {
                val name = LutLibrary.name(i)
                val g = LutLibrary.group(i)
                if (i > 0 && group != null && g != group) continue
                if (query.isNotEmpty() && !name.contains(query, true) && !g.contains(query, true)) continue
                val chosen = i == current
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = Style.surface(this@CameraActivity, fill = if (chosen) Style.SURFACE_2 else Color.TRANSPARENT, radiusDp = 10f)
                    addView(LinearLayout(this@CameraActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(15f).apply { text = name; setTextColor(if (chosen) Style.ACCENT else Style.TEXT) })
                        if (g.isNotEmpty()) addView(label(11f).apply { text = g; setTextColor(Style.TEXT_3) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (chosen) addView(ImageView(this@CameraActivity).apply {
                        setImageDrawable(Icon(Icon.Kind.CHECK, Style.ACCENT, 2f, filled = true))
                    }, LinearLayout.LayoutParams(dp(20), dp(20)))
                    setOnClickListener {
                        if (i == 0) store[Settings.LUT_ON] = 0f else pickLut(i)
                        haptics.play(Haptics.Event.SEGMENT)
                        main.postDelayed({ sheet.dismiss() }, 120)
                    }
                    // the user's own can be removed: long press
                    if (LutLibrary.isUser(i)) setOnLongClickListener {
                        haptics.play(Haptics.Event.LOCKED)
                        android.app.AlertDialog.Builder(this@CameraActivity)
                            .setTitle("删除“$name”？")
                            .setMessage("只删除导入到 App 里的这份 LUT，原文件不受影响。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                val chosenNow = chosenLut()
                                LutLibrary.delete(this@CameraActivity, i)
                                when {
                                    chosenNow == i -> { store[Settings.LUT_ON] = 0f; store[Settings.LUT] = 0f }
                                    chosenNow > i -> store[Settings.LUT] = (chosenNow - 1).toFloat()
                                }
                                sheet.dismiss()
                                status.text = "已删除 $name"
                            }
                            .show()
                        true
                    }
                }
                list.addView(row, LinearLayout.LayoutParams(-1, -2))
                if (chosen) chosenRow = row
            }
            if (list.childCount == 0) list.addView(sheetNote("没有找到“$query”"))
        }
        fun fillChips() {
            chips.removeAllViews()
            for (g in listOf<String?>(null) + groups)
                chips.addView(chip(g ?: "全部", g == group) { group = g; fillChips(); fill() },
                    LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
        }
        head.addView(android.widget.EditText(this).apply {
            hint = "搜索 ${LutLibrary.count - 1} 款 LUT"
            textSize = 15f
            isSingleLine = true
            setTextColor(Style.TEXT)
            setHintTextColor(Style.TEXT_3)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Style.surface(this@CameraActivity, fill = Style.BG, radiusDp = 12f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString()?.trim() ?: ""; fill() }
            })
        }, LinearLayout.LayoutParams(-1, -2))
        if (groups.size > 1) head.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, dp(6))
            addView(chips)
        })
        // a 3D LUT of the user's own: Adobe / Resolve .cube, any size
        head.addView(chip("＋ 导入 3D LUT（.cube）", false) {
            sheet.dismiss()
            runCatching { importLut.launch(arrayOf("*/*")) }
                .onFailure { status.text = "打不开文件选择器" }
        }, LinearLayout.LayoutParams(-2, dp(34)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        fillChips()
        fill()
        sheet.show()
        // open at the LUT in use
        list.post { chosenRow?.let { row -> (list.parent as? android.widget.ScrollView)?.scrollTo(0, (row.top - dp(60)).coerceAtLeast(0)) } }
    }

    /** A titled slider for one setting, the value shown on the right. */
    private fun sliderRow(title: String, key: String, min: Float, max: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
        val value = label(12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        fun show(v: Float) { value.text = if (max <= 1f) "%.0f%%".format(v * 100) else "%.2f".format(v) }
        show(store[key])
        addView(LinearLayout(this@CameraActivity).apply {
            addView(label(12f).apply { text = title; setTextColor(Style.TEXT_2) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value)
        })
        addView(org.photoall.film.ui.Slider(this@CameraActivity).apply {
            this.max = 1000
            progress = ((store[key] - min) / (max - min) * 1000).roundToInt()
            onChange = { p ->
                val v = min + (max - min) * p / 1000f
                store[key] = v
                show(v)
            }
        }, LinearLayout.LayoutParams(-1, dp(32)))
    }

    /** "kodak portra endura" -> "Portra Endura", "kodak 2383" -> "2383". */
    private fun paperName(name: String) = org.photoall.film.settings.LookFamilies.paperName(name)

    private fun sheetTitle(text: String) = label(12f).apply {
        this.text = text
        setTextColor(Style.TEXT_2)
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun sheetNote(text: String) = label(12f).apply {
        this.text = text
        setTextColor(Style.TEXT_3)
        setPadding(0, dp(6), 0, 0)
    }

    /** A switch tile: title and value on top, a line of detail below; outlined when on. */
    private fun tile(title: String, value: String, detail: String, on: Boolean, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Style.surface(this@CameraActivity, fill = if (on) (Style.ACCENT and 0x00ffffff or 0x14000000) else Style.SURFACE_2,
                radiusDp = 14f, stroke = if (on) Style.ACCENT else Style.HAIRLINE)
            addView(LinearLayout(this@CameraActivity).apply {
                addView(label(14f).apply { text = title; typeface = Style.medium }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(12f).apply {
                    text = value
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(if (on) Style.ACCENT else Style.TEXT_2)
                })
            })
            addView(View(this@CameraActivity), LinearLayout.LayoutParams(1, dp(6), 1f))
            addView(label(12f).apply {
                text = detail
                setTextColor(Style.TEXT_2)
                maxLines = 2 // the narrower side panel in landscape wraps rather than cuts
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            Style.pressable(this)
            setOnClickListener { haptics.play(Haptics.Event.SEGMENT); onClick() }
        }

    private fun tileRow(a: View, b: View) = LinearLayout(this).apply {
        // wrap the content (large system fonts), at least the design's 76 dp
        addView(a, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(4) })
        addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4) })
        minimumHeight = dp(76)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    /** An on/off chip: filled in the text colour when on, outlined when off. */
    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        stateListAnimator = null
        Style.compact(this, 14f)
        background = Style.surface(this@CameraActivity, fill = if (on) Style.TEXT else Color.TRANSPARENT, radiusDp = 18f,
            stroke = if (on) 0 else Style.HAIRLINE)
        setTextColor(if (on) Style.BG else Style.TEXT_2)
        Style.pressable(this)
        setOnClickListener { haptics.play(Haptics.Event.SEGMENT); onClick() }
    }

    private fun switchRow(title: String, detail: String, on: Boolean, onToggle: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(6))
        addView(LinearLayout(this@CameraActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(14f).apply { text = title })
            addView(label(12f).apply { text = detail; setTextColor(Style.TEXT_3) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(android.widget.Switch(this@CameraActivity).apply {
            isChecked = on
            Style.switch(this)
            setOnCheckedChangeListener { _, _ -> onToggle() }
        })
    }

    /**
     * Engine thread: with 拍摄时直接套用 on, a second copy of the shot in the chosen border,
     * numbered with this frame of the roll. The original is saved as usual either way.
     */
    private fun saveFramed(bitmap: Bitmap, name: String, quality: Int, description: String, shot: ShotInfo?, film: String) {
        if (!store.flag(Settings.FRAME_AUTO)) return
        // the design made in the album's border editor
        val design = org.photoall.film.gallery.FrameDesign.load(this)
        if (design.empty) return
        runCatching {
            val data = org.photoall.film.gallery.FrameDesigner.data(this, shot, description, film, rollFrame() + 1, System.currentTimeMillis())
            val framed = org.photoall.film.gallery.FrameDesigner.render(bitmap, design, data, maxLong = 8192)
            Photos.saveJpeg(this, framed, name.removeSuffix(".jpg") + "_frame.jpg", quality,
                "$description · 边框 ${design.border.label}", shot, private = store.flag(Settings.PRIVATE_PHOTOS))
            framed.recycle()
        }.onFailure { Log.w(TAG, "framed copy", it) }
    }

    /** What happens to a shot: a film is developed; without one the RAW is only processed. */
    private fun work() = if (store.flag(Settings.FILM_ON)) "冲洗" else "处理"

    private fun failed(exception: ImageCaptureException) {
        Log.e(TAG, "capture failed", exception)
        runOnUiThread {
            developing--
            status.text = "拍摄失败：${exception.message}"
            haptics.quiet = false
            haptics.play(Haptics.Event.ERROR)
        }
    }

    /**
     * The shutter as a camera runs it. When the sensor starts a frame (after any metering
     * pre-flash) the curtain shuts: the viewfinder blacks out, and a flash fires inside
     * that blackout. When the last frame's exposure time is over the curtain opens and the
     * clack is felt, the moment a real shutter's second curtain lands. Nothing vibrates
     * while the sensor is still exposing, so the clack cannot shake the photo.
     */
    private var shotClacked = true
    private val openCurtain = Runnable { clack() }

    private fun exposureStarted(exposureMs: Long, last: Boolean) {
        val run = {
            curtain.close()
            main.removeCallbacks(openCurtain)
            // a burst keeps it black from the first frame to the end of the last; even at a
            // fast speed the view stays black a beat (an SLR's mirror is up longer than its
            // shutter is open): exposure + 120 ms
            if (last) main.postDelayed(openCurtain, exposureMs.coerceIn(0L, 4000L) + CURTAIN_SHUT + BLACKOUT)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }

    /** A frame's exposure time in ms: the held one, else what the meter is giving. */
    private fun frameMs(held: Exposure?): Long =
        ((held ?: exposure?.held ?: exposure?.live)?.timeNs ?: 16_666_667L) / 1_000_000L

    private fun clack() {
        main.removeCallbacks(openCurtain)
        shotClacked = true
        curtain.open()
        shutterTaken()
    }

    /** Every frame of the shot is taken: the motor may move again, and says so. */
    private var lastShutterHaptic = 0L

    private fun shutterTaken() {
        haptics.quiet = false
        val now = SystemClock.uptimeMillis()
        if (now - lastShutterHaptic < 300) return // taken and done both report one shot
        lastShutterHaptic = now
        haptics.play(Haptics.Event.SHUTTER)
    }

    private fun loadLatestThumbnail() {
        engineThread.later {
            // Always replace: the shown photo may have been deleted, or the album emptied.
            val thumb = Photos.list(this).firstOrNull()?.let { latest ->
                Photos.thumbnail(this, latest)
            }
            runOnUiThread { thumbnail.setImageBitmap(thumb) }
        }
    }

    // ----- views ------------------------------------------------------------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun label(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(Style.TEXT)
        gravity = Gravity.CENTER_VERTICAL
        Style.numeric(this)
    }

    /** A step arrow: a bare glyph, no fill. */
    private fun arrow(text: String, onClick: () -> Unit) = button(text, onClick).apply {
        Style.textButton(this, 18f)
        setTextColor(Style.TEXT_2)
    }

    // Compact buttons: the platform default (48dp tall, 88dp wide) overflows the controls.
    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        isSingleLine = true // wide system fonts (Samsung) must not wrap "AF-C" into two lines
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        Style.button(this)
        setOnClickListener { onClick() }
    }

    /** A view in a grid row: [weight] share of the width; fixed [w] x [h] centred, or fill when 0. */
    private class Cell(val view: View, val weight: Float, val w: Int, val h: Int)

    private fun cell(view: View, weight: Float, size: Int = 0, height: Int = size) = Cell(view, weight, size, height)

    /** A row of equal-height cells; each view fills its cell (or is centred at a fixed size). */
    private fun gridRow(vararg cells: Cell, height: Int = dp(42)) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        for (c in cells) addView(FrameLayout(this@CameraActivity).apply {
            setPadding(dp(3), dp(2), dp(3), dp(2))
            (c.view as? TextView)?.takeIf { it !is Button }?.apply { maxLines = 1; isSingleLine = true }
            addView(c.view, if (c.w > 0) FrameLayout.LayoutParams(c.w, c.h, Gravity.CENTER)
            else FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(0, height, c.weight))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /**
     * Portrait: the viewfinder at the top (as large as fits, 3:4) with the control rows
     * right under it; whatever height is left stays empty below them. The controls are
     * measured first, so on a short screen the viewfinder gives way, not the controls.
     */
    private class FinderColumn(context: android.content.Context, private val finder: View, private val rows: View) :
        android.view.ViewGroup(context) {
        /** Told the height left empty under the rows, and the viewfinder's, after each layout. */
        var onSpare: (Int, Int) -> Unit = { _, _ -> }
        init {
            addView(finder)
            addView(rows)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = View.MeasureSpec.getSize(widthMeasureSpec)
            val h = View.MeasureSpec.getSize(heightMeasureSpec)
            rows.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            finder.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxOf(0, h - rows.measuredHeight), View.MeasureSpec.AT_MOST))
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val left = (width - finder.measuredWidth) / 2
            finder.layout(left, 0, left + finder.measuredWidth, finder.measuredHeight)
            rows.layout(0, finder.measuredHeight, width, finder.measuredHeight + rows.measuredHeight)
            onSpare(height - finder.measuredHeight - rows.measuredHeight, finder.measuredHeight)
        }
    }

    /**
     * A landscape control column that always fits the screen's height. The keys are sized
     * in dp, and a phone set to a large display size (or a short screen) has fewer dp
     * across: there the whole column is scaled down evenly instead of losing its last rows.
     */
    private class FitColumn(context: android.content.Context, private val column: View) : android.view.ViewGroup(context) {
        init {
            clipChildren = false
            clipToPadding = false
            addView(column)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = View.MeasureSpec.getSize(widthMeasureSpec)
            val h = View.MeasureSpec.getSize(heightMeasureSpec)
            val exact = View.MeasureSpec.EXACTLY
            column.measure(View.MeasureSpec.makeMeasureSpec(w, exact), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val natural = column.measuredHeight
            val scale = if (h > 0 && natural > h) (h.toFloat() / natural).coerceAtLeast(0.6f) else 1f
            column.measure(View.MeasureSpec.makeMeasureSpec((w / scale).toInt(), exact),
                View.MeasureSpec.makeMeasureSpec((h / scale).toInt(), exact))
            column.pivotX = 0f
            column.pivotY = 0f
            column.scaleX = scale
            column.scaleY = scale
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            column.layout(0, 0, column.measuredWidth, column.measuredHeight)
        }
    }

    companion object {
        private const val TURN_OUT = 150L // the controls draw back
        private const val TURN_IN = 320L  // the new controls come in
        private const val CURTAIN_SHUT = 0L  // the blackout is instant
        private const val BLACKOUT = 120L    // the least time the view stays black

        private const val TAG = "FilmCamera"
        private const val LANDSCAPE_RAIL_DP = 88
        private const val ROLL_LENGTH = 36
        // Recipe-only entries next to the engine parameters (never sent to the engine).
        private const val LUT_KEY = "app.lut"
        private const val LUT_STRENGTH_KEY = "app.lutstrength"
    }
}
