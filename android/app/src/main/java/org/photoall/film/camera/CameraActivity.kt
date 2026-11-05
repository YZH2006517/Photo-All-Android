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
import org.photoall.film.ui.Style
import org.photoall.film.settings.Settings
import org.photoall.film.settings.SettingsStore
import java.io.File
import org.photoall.film.gallery.ShotInfo
import org.photoall.film.gallery.ShotLog
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
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
    private lateinit var stockLabel: TextView
    private lateinit var paperLabel: TextView
    private lateinit var lutLabel: TextView
    private lateinit var recipeSummary: TextView
    private lateinit var recipeRows: List<LinearLayout>
    private var recipeExpanded = false
    /** HDR / flash / focus mode / guides, folded behind one summary chip. */
    private lateinit var modesSummary: TextView
    private var modesRow: LinearLayout? = null
    private var modesExpanded = false
    private lateinit var controls: LinearLayout
    private lateinit var viewfinderFrame: ViewfinderFrame
    private lateinit var settingsButton: Button
    private lateinit var rotateButton: Button
    // Orientation the rotate button would switch to (ActivityInfo.SCREEN_ORIENTATION_*).
    private var offeredOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private val hideRotate = Runnable { rotateButton.visibility = View.GONE }
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var lensScroll: android.widget.HorizontalScrollView
    private lateinit var evMinus: Button
    private lateinit var filmToggle: Button
    private lateinit var printToggle: Button
    private lateinit var lutToggle: Button
    private lateinit var evPlus: Button
    // The creative LUT last sent to the engine (engine thread), to skip re-uploads.
    private var appliedLut: Pair<Int, Float>? = null
    private lateinit var evLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var facingButton: Button
    private lateinit var hdrButton: Button
    private lateinit var flashButton: Button
    private lateinit var focusButton: Button
    private lateinit var guideButton: Button
    private lateinit var peakButton: Button
    private lateinit var distanceLabel: TextView
    private lateinit var focusSeek: SeekBar
    private lateinit var mfRow: LinearLayout
    private lateinit var thumbnail: ImageView
    private lateinit var shutter: View
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
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
    private val rebake = Runnable { engineThread.execute { bakeLut() } }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCamera() else status.text = "需要相机权限才能取景"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        viewfinder = FilmViewfinder(this).apply {
            onPinch = { factor -> zoomBy(factor) }
            onTap = { x, y -> focusAt(x, y) }
        }
        overlay = ViewfinderOverlay(this)

        // One grid for every control row: same side padding, same row height, and
        // each row split into equal cells, so columns line up from row to row.
        status = label(12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Style.TEXT_2) }
        modeLabel = label(12f).apply { gravity = Gravity.END; setTextColor(Style.TEXT_2); typeface = Style.medium }
        stockLabel = label(15f).apply { gravity = Gravity.CENTER; typeface = Style.medium }
        paperLabel = label(14f).apply { gravity = Gravity.CENTER; setTextColor(Style.TEXT_2) }
        evLabel = label(14f).apply { gravity = Gravity.CENTER; typeface = Style.medium }
        zoomLabel = label(14f).apply { gravity = Gravity.CENTER; minWidth = dp(48) }
        distanceLabel = label(13f).apply { gravity = Gravity.CENTER; minWidth = dp(64) }
        lensRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        facingButton = button("前置") { front = !front; lensId = null; pendingZoom = null; bindCamera() }
        hdrButton = button("HDR") { store[Settings.HDR] = if (store.flag(Settings.HDR)) 0f else 1f }
        flashButton = button("闪关") {
            store[Settings.FLASH] = ((store[Settings.FLASH].toInt() + 1) % Settings.flashModes.size).toFloat()
        }
        focusButton = button("AF-C") { cycleFocusMode() }
        guideButton = button("网格") { stepSetting(Settings.GUIDES, 1) }
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
        thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Style.SURFACE); setStroke(1, Style.HAIRLINE) }
            clipToOutline = true
            setOnClickListener { startActivity(Intent(this@CameraActivity, GalleryActivity::class.java)) }
        }
        shutter = View(this).apply {
            background = Style.shutter(this@CameraActivity)
            setOnClickListener { takePicture() }
        }

        mfRow = gridRow(
            cell(label(13f).apply { text = "∞"; gravity = Gravity.CENTER; setTextColor(Style.TEXT_2) }, 0.6f),
            cell(focusSeek.also { Style.slider(it) }, 3.4f),
            cell(label(13f).apply { text = "近"; gravity = Gravity.CENTER; setTextColor(Style.TEXT_2) }, 0.6f),
            cell(distanceLabel, 1.2f),
            cell(peakButton, 1.2f),
        )
        // Film / paper / LUT: one summary row; tap to show the three switch rows, tap a
        // name for the full list.
        LutLibrary.load(this)
        recipeSummary = label(14f).apply {
            gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Style.medium
            background = Style.surface(this@CameraActivity, radiusDp = 10f)
            setOnClickListener { recipeExpanded = !recipeExpanded; updateLabels() }
        }
        lutLabel = label(14f).apply { gravity = Gravity.CENTER; setTextColor(Style.TEXT_2) }
        modesSummary = label(13f).apply {
            gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Style.medium
            background = Style.surface(this@CameraActivity, radiusDp = 10f)
            setOnClickListener { modesExpanded = !modesExpanded; updateLabels() }
        }
        stockLabel.setOnClickListener { pickFrom("选择胶片", FilmEngine.filmStocks, store["film"].toInt()) { store["film"] = it.toFloat() } }
        paperLabel.setOnClickListener { pickFrom("选择相纸", FilmEngine.papers, store["paper"].toInt()) { store["paper"] = it.toFloat() } }
        lutLabel.setOnClickListener { pickFrom("选择 LUT（${LutLibrary.count - 1} 款）", LutLibrary.entries(), store[Settings.LUT].toInt()) { pickLut(it) } }
        // On/off for the film simulation and for printing the negative onto paper.
        filmToggle = button("胶片") { store[Settings.FILM_ON] = if (store.flag(Settings.FILM_ON)) 0f else 1f }
        printToggle = button("相纸") { store[Settings.PRINT_ON] = if (store.flag(Settings.PRINT_ON)) 0f else 1f }
        lutToggle = button("LUT") { store[Settings.LUT_ON] = if (store.flag(Settings.LUT_ON)) 0f else 1f }
        recipeRows = listOf(
            gridRow(cell(filmToggle, 1.2f), cell(arrow("‹") { stepSetting("film", -1) }, 1f), cell(stockLabel, 4f), cell(arrow("›") { stepSetting("film", 1) }, 1f)),
            gridRow(cell(printToggle, 1.2f), cell(arrow("‹") { stepSetting("paper", -1) }, 1f), cell(paperLabel, 4f), cell(arrow("›") { stepSetting("paper", 1) }, 1f)),
            gridRow(cell(lutToggle, 1.2f),
                cell(arrow("‹") { stepLut(-1) }, 1f), cell(lutLabel, 4f), cell(arrow("›") { stepLut(1) }, 1f)),
        )
        lensScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(lensRow)
        }
        evMinus = button("−") { changeEv(-1) }
        evPlus = button("+") { changeEv(1) }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
        }
        settingsButton = button("参数") { openSettings() }
        // Shown for a few seconds when the phone is turned; tapping it switches layout.
        rotateButton = button("") { rotateTo(offeredOrientation) }.apply {
            textSize = 13f
            isSingleLine = false
            maxLines = 2
            background = Style.surface(this@CameraActivity, fill = Style.SCRIM, radiusDp = 14f, stroke = Style.HAIRLINE)
            setTextColor(Style.ACCENT)
            visibility = View.GONE
        }
        viewfinderFrame = ViewfinderFrame(this).apply {
            addView(viewfinder, FrameLayout.LayoutParams(-1, -1))
            addView(this@CameraActivity.overlay, FrameLayout.LayoutParams(-1, -1)) // not View.overlay
            addView(rotateButton, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END).apply {
                setMargins(dp(10), dp(10), dp(10), dp(10))
            })
        }
        attachLayout()
        startOrientationWatch()
        // adb: `am start ... --ei orientation 0` opens straight in landscape (layout checks).
        if (intent.hasExtra("orientation")) requestedOrientation = intent.getIntExtra("orientation", 1)

        store.onChange { key -> onSettingChanged(key) }
        updateLabels()
        status.text = "正在启动胶片引擎…"
        engineThread.execute {
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
     * Portrait: viewfinder over the controls. Landscape: viewfinder beside a scrolling
     * column of the same controls. The views are built once and re-parented.
     */
    private fun attachLayout() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        fillControls(landscape)
        if (provider != null) buildZoomButtons() // lens button widths follow the orientation
        (viewfinderFrame.parent as? android.view.ViewGroup)?.removeView(viewfinderFrame)
        (controls.parent as? android.view.ViewGroup)?.removeView(controls)
        viewfinderFrame.landscape = landscape
        val finderHost = FrameLayout(this).apply {
            addView(viewfinderFrame, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        for (v in listOf(thumbnail, shutter, settingsButton)) (v.parent as? android.view.ViewGroup)?.removeView(v)
        setContentView(LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            if (landscape) {
                // Camera-style: viewfinder | scrolling settings | shutter rail at the thumb.
                addView(finderHost, LinearLayout.LayoutParams(0, -1, 1f))
                addView(android.widget.ScrollView(this@CameraActivity).apply {
                    isVerticalScrollBarEnabled = false
                    isFillViewport = true
                    setBackgroundColor(Style.BG)
                    addView(controls)
                }, LinearLayout.LayoutParams(landscapeColumnWidth(), -1))
                addView(LinearLayout(this@CameraActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(16), 0, dp(16))
                    addView(thumbnail, LinearLayout.LayoutParams(dp(52), dp(52)))
                    addView(View(this@CameraActivity), LinearLayout.LayoutParams(1, 0, 1f))
                    addView(shutter, LinearLayout.LayoutParams(dp(72), dp(72)))
                    addView(View(this@CameraActivity), LinearLayout.LayoutParams(1, 0, 1f))
                    addView(settingsButton, LinearLayout.LayoutParams(dp(64), dp(36)))
                }, LinearLayout.LayoutParams(dp(LANDSCAPE_RAIL_DP), -1))
            } else {
                addView(finderHost, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(controls, LinearLayout.LayoutParams(-1, -2))
                addView(gridRow(
                    cell(thumbnail, 1f, dp(52)),
                    cell(shutter, 1f, dp(72)),
                    cell(settingsButton, 1f, dp(68), dp(36)),
                    height = dp(92),
                ).apply { setPadding(dp(12), 0, dp(12), dp(4)) })
            }
            setOnApplyWindowInsetsListener { view, insets ->
                // Keep clear of the navigation bar and the camera cutout; the status bar is hidden.
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        hideStatusBar()
    }

    /**
     * The control rows. Portrait packs them wide; the narrower landscape column gives
     * the lenses and the EV / toggle groups a row each so nothing is cut off.
     */
    private fun fillControls(landscape: Boolean) {
        // Rows wrap each view in a fresh cell: release the views from the old ones.
        val shared = listOf(status, modeLabel, lensScroll, zoomLabel, facingButton, recipeSummary,
            evMinus, evLabel, evPlus, modesSummary, hdrButton, flashButton, focusButton, guideButton)
        for (v in shared) (v.parent as? android.view.ViewGroup)?.removeView(v)
        controls.removeAllViews()
        // narrower side margins in the landscape column
        controls.setPadding(dp(if (landscape) 6 else 12), dp(6), dp(if (landscape) 6 else 12), dp(4))
        controls.apply {
            if (landscape) { // the column is narrow: status and mode get a line each
                addView(gridRow(cell(status, 1f), height = dp(24)))
                addView(gridRow(cell(modeLabel, 1f), height = dp(24)))
            } else addView(gridRow(cell(status, 1f), cell(modeLabel, 1f), height = dp(28)))
            if (landscape) {
                addView(gridRow(cell(lensScroll, 1f)))
                addView(gridRow(cell(zoomLabel, 1f), cell(facingButton, 1f)))
            } else addView(gridRow(cell(lensScroll, 4f), cell(zoomLabel, 1f), cell(facingButton, 1f)))
            addView(gridRow(cell(recipeSummary, 1f)))
            recipeRows.forEach { addView(it) }
            if (landscape) {
                addView(gridRow(cell(evMinus, 1f), cell(evLabel, 1.6f), cell(evPlus, 1f)))
                addView(gridRow(cell(modesSummary, 1f)))
            } else addView(gridRow(
                cell(evMinus, 1f), cell(evLabel, 1.4f), cell(evPlus, 1f), cell(modesSummary, 3.6f),
            ))
            // The folded modes: one row of four, shown when the summary is opened.
            modesRow = gridRow(cell(hdrButton, 1f), cell(flashButton, 1f), cell(focusButton, 1f), cell(guideButton, 1f))
                .also { it.visibility = if (modesExpanded) View.VISIBLE else View.GONE; addView(it) }
            addView(mfRow)
        }
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
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        attachLayout()
        // Re-parenting recreated the GL surface, and the capture rotation changed: rebind.
        bindCamera()
    }

    // ----- orientation: the layout only turns when asked ----------------------

    /**
     * The screen stays put when the phone turns; instead a small button appears in the
     * viewfinder corner for three seconds, upright for the way the phone is held, and
     * switches the layout when tapped. Turning back offers the way back.
     */
    private fun startOrientationWatch() {
        orientationListener = object : OrientationEventListener(this) {
            private var held = -1 // the snapped device orientation last acted on
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                // Snap with a margin so a phone held near 45 degrees does not flicker.
                val snapped = when (degrees) {
                    in 300..360, in 0..60 -> 0
                    in 70..110 -> 90
                    in 250..290 -> 270
                    else -> return
                }
                if (snapped == held) return
                held = snapped
                // Device 270 (right side up) is LANDSCAPE, 90 (left side up) REVERSE_LANDSCAPE.
                val wanted = when (snapped) {
                    270 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                if (wanted == currentOrientation()) {
                    main.removeCallbacks(hideRotate)
                    rotateButton.visibility = View.GONE
                    return
                }
                offeredOrientation = wanted
                rotateButton.text = if (wanted == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "↻\n竖屏" else "↻\n横屏"
                // Upright for the user: undo both how the phone is held and how the UI is turned.
                val display = (viewfinder.display?.rotation ?: android.view.Surface.ROTATION_0) * 90
                rotateButton.rotation = ((360 - snapped - display) % 360 + 360) % 360f
                rotateButton.visibility = View.VISIBLE
                main.removeCallbacks(hideRotate)
                main.postDelayed(hideRotate, 3000)
            }
        }
    }

    private fun currentOrientation() = when (viewfinder.display?.rotation) {
        android.view.Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        android.view.Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun rotateTo(orientation: Int) {
        main.removeCallbacks(hideRotate)
        rotateButton.visibility = View.GONE
        requestedOrientation = orientation // fixed until the next tap; onConfigurationChanged relays out
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

    override fun onResume() {
        super.onResume()
        viewfinder.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        loadLatestThumbnail()
        if (settingsOpen) {
            // The settings screen wrote straight to storage; catch up with everything.
            settingsOpen = false
            if (bindKeys() != bindSnapshot) bindCamera() else applyCameraOptions()
            main.post(rebake)
            updateLabels()
        }
    }

    override fun onPause() {
        orientationListener.disable()
        main.removeCallbacks(hideRotate)
        rotateButton.visibility = View.GONE
        viewfinder.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        analysisThread.shutdown()
        engineThread.execute { engine?.close() }
        engineThread.shutdown()
        super.onDestroy()
    }

    /** Settings that need the camera rebound rather than just new request options. */
    private fun bindKeys() = listOf(store[Settings.RAW], store[Settings.SOFT_3A])

    private fun openSettings() {
        settingsOpen = true
        bindSnapshot = bindKeys()
        startActivity(Intent(this, org.photoall.film.settings.SettingsActivity::class.java))
    }

    // ----- settings ---------------------------------------------------------

    private fun onSettingChanged(key: String) {
        updateLabels()
        when {
            key == Settings.RAW || key == Settings.SOFT_3A -> bindCamera()
            key in exposureKeys -> applyCameraOptions()
            // the filter mode and the creative LUT change the look too
            key.startsWith("app.") &&
                key !in setOf(Settings.BALANCE, Settings.LUT, Settings.LUT_ON, Settings.LUT_STRENGTH,
                    Settings.FILM_ON, Settings.PRINT_ON) -> {}
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
            val controller = FilmExposure(chars, calibration) { options ->
                camera?.let { Camera2CameraControl.from(it.cameraControl).setCaptureRequestOptions(options) }
            }.also { c -> c.onLive = { e -> main.post { showLive(e) } } }
            exposure = controller

            // The phone's 3A meters; its per-frame results drive the live readout and stills.
            val previewBuilder = Preview.Builder().setResolutionSelector(fourThree)
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(controller.captureCallback)
            val preview = previewBuilder.build()
            preview.surfaceProvider = viewfinder
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
                .build().also { it.setAnalyzer(analysisThread, previewMeter) }
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
        Settings.FOCUS, Settings.FOCUS_DISTANCE, Settings.PEAKING,
    )

    // ----- focus ------------------------------------------------------------

    private fun focusMode() = store[Settings.FOCUS].toInt().coerceIn(0, 2)

    /** Closest focus in diopters (1 / metres); 0 means a fixed-focus lens. */
    private fun minFocusDiopters(): Float = camera?.let {
        Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    } ?: 0f

    /** Manual focus distance in diopters, or null when autofocus runs. */
    private fun manualFocusDiopters(): Float? =
        if (focusMode() == 2 && minFocusDiopters() > 0f) store[Settings.FOCUS_DISTANCE] * minFocusDiopters() else null

    private fun focusLabel(): String {
        val d = manualFocusDiopters() ?: return ""
        val calibrated = camera?.let {
            Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
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
        focusButton.text = Settings.focusModes[mode]
        mfRow.visibility = if (mode == 2) View.VISIBLE else View.GONE
        focusSeek.isEnabled = adjustable
        focusSeek.progress = (store[Settings.FOCUS_DISTANCE] * 1000).roundToInt()
        distanceLabel.text = if (adjustable) focusLabel() else "定焦镜头"
        Style.active(peakButton, store.flag(Settings.PEAKING))
        viewfinder.peaking = mode == 2 && store.flag(Settings.PEAKING)
    }

    private fun cycleFocusMode() {
        camera?.cameraControl?.cancelFocusAndMetering() // drop any AF lock before switching
        overlay.hideFocus()
        store[Settings.FOCUS] = ((focusMode() + 1) % 3).toFloat()
    }

    /** "film · paper · LUT", or what replaces them when a stage is off. */
    private fun recipeText(filmOn: Boolean, printOn: Boolean, stock: String, paper: String, lut: Int): String {
        val parts = when {
            !filmOn -> listOf("无胶片（直出）")
            !printOn -> listOf(stock, "直接扫描")
            else -> listOf(stock, paper)
        }
        return (parts + if (lut != 0) LutLibrary.name(lut) else "").filter { it.isNotEmpty() }.joinToString(" · ")
    }

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
        val camera = camera ?: return
        val controller = exposure ?: return
        val chars = Camera2CameraInfo.from(camera.cameraInfo)
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
        controller.push()
        updateLabels()
    }

    private var options: List<LensOption> = emptyList()
    private val lensButtons = mutableListOf<Pair<LensOption, Button>>()

    /** One button per real lens, labelled by magnification of the main camera. */
    private fun buildZoomButtons() {
        lensRow.removeAllViews()
        lensButtons.clear()
        val provider = provider ?: return
        options = lensOptions(provider)
        for (option in options) {
            val b = button(zoomText(option.ratio)) { selectLens(option) }
            // four lenses must fit the narrow landscape column
            val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            lensRow.addView(b, LinearLayout.LayoutParams(dp(if (landscape) 43 else 46), dp(36)).apply { marginEnd = dp(if (landscape) 1 else 2) })
            lensButtons += option to b
        }
        facingButton.text = if (front) "后置" else "前置"
        updateZoom()
    }

    private fun selectLens(option: LensOption) {
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
        val camera = camera ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        camera.cameraControl.setZoomRatio((state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    /** Shows the current magnification and highlights the lens it sits on. */
    private fun updateZoom() {
        val camera = camera ?: return
        val zoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val id = Camera2CameraInfo.from(camera.cameraInfo).cameraId
        val mine = lensButtons.filter { Camera2CameraInfo.from(it.first.camera).cameraId == id }
        val active = mine.lastOrNull { it.first.zoom <= zoom * 1.01f } ?: mine.firstOrNull()
        val scale = active?.let { it.first.ratio / it.first.zoom } ?: 1f
        zoomLabel.text = zoomText(zoom * scale)
        for ((option, b) in lensButtons) Style.selected(b, option === active?.first)
    }

    private fun zoomText(ratio: Float) =
        if (ratio < 0.995f || (ratio * 10).roundToInt() % 10 != 0) "%.1f×".format(ratio) else "%.0f×".format(ratio)

    private fun focusAt(x: Float, y: Float) {
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
        val mode = focusMode()
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
            overlay.showFocus(x, y, when {
                !ok -> ViewfinderOverlay.Focus.Failed
                mode == 1 -> ViewfinderOverlay.Focus.Locked
                else -> ViewfinderOverlay.Focus.Focused
            })
        }, mainExecutor)
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
        val controller = exposure ?: return
        if (store.flag(Settings.MANUAL)) return
        evStops = ((evStops * 3).roundToInt() + step).coerceIn(-9, 9) / 3.0
        controller.evStops = evStops
        controller.push()
        updateLabels()
    }

    private fun updateLabels() {
        val filmOn = store.flag(Settings.FILM_ON)
        val printOn = store.flag(Settings.PRINT_ON)
        val paper = FilmEngine.papers[store["paper"].toInt().coerceIn(0, FilmEngine.papers.lastIndex)]
        Style.active(filmToggle, filmOn)
        Style.active(printToggle, filmOn && printOn)
        stockLabel.text = filmName()
        Style.enabled(stockLabel, filmOn)
        // Papers only matter when the negative is printed, not for a straight scan.
        paperLabel.text = paper
        Style.enabled(paperLabel, filmOn && printOn, Style.TEXT_2)
        val lut = currentLut()
        val chosen = chosenLut()
        lutLabel.text = if (chosen == 0) "无 LUT" else "${LutLibrary.name(chosen)} · %.0f%%".format(lut.second * 100)
        Style.active(lutToggle, lut.first != 0)
        Style.enabled(lutLabel, lut.first != 0 || chosen == 0, Style.TEXT_2)
        recipeSummary.text = (if (recipeExpanded) "▴   " else "▾   ") +
            recipeText(filmOn, printOn, filmName(), paper, lut.first)
        for (row in recipeRows) row.visibility = if (recipeExpanded) View.VISIBLE else View.GONE
        val guides = store[Settings.GUIDES].toInt().coerceIn(0, Settings.guideNames.lastIndex)
        overlay.guides = guides
        guideButton.text = if (guides == 0) "网格" else Settings.guideNames[guides].take(2)
        Style.active(guideButton, guides != 0)
        meter?.let { applyHistogram(it) }
        evLabel.text = if (store.flag(Settings.MANUAL)) "手动" else "%+.1f EV".format(evStops)
        evLabel.setTextColor(if (store.flag(Settings.MANUAL) || evStops != 0.0) Style.ACCENT else Style.TEXT)
        val flashMode = flashMode()
        val hdr = store.flag(Settings.HDR) && rawCapture && flashMode == 0
        Style.active(hdrButton, hdr)
        flashButton.text = if (flashMode == 0) "闪关" else Settings.flashModes[flashMode]
        Style.active(flashButton, flashMode != 0)
        // Summary of the folded modes: what is on, plus the focus mode; the accent when
        // HDR or the flash is on, as the buttons themselves show.
        val modeParts = listOfNotNull(
            if (hdr) "HDR" else null,
            if (flashMode != 0) Settings.flashModes[flashMode] else null,
            Settings.focusModes[focusMode()],
            if (guides != 0) Settings.guideNames[guides].take(2) else null,
        )
        modesSummary.text = (if (modesExpanded) "▴   " else "▾   ") + modeParts.joinToString(" · ")
        modesSummary.setTextColor(if (hdr || flashMode != 0) Style.ACCENT else Style.TEXT)
        modesRow?.visibility = if (modesExpanded) View.VISIBLE else View.GONE
        modeLabel.text = buildString {
            if (flashMode != 0) append("闪光${Settings.flashModes[flashMode]}")
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
        shutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120)
        }
        viewfinder.animate().alpha(0.2f).setDuration(60).withEndAction { viewfinder.animate().alpha(1f).setDuration(160) }
        // Freeze the recipe at the moment of the shot.
        val params = store.filmParams() // engine parameters only
        val recipe = params + (LUT_KEY to currentLut().first.toFloat()) + (LUT_STRENGTH_KEY to currentLut().second)
        val options = DevelopOptions(maxLongEdge = store.longEdge, seed = Random.nextInt(), displayP3 = false)
        val quality = store[Settings.JPEG_QUALITY].toInt()
        val rawEv = store[Settings.RAW_EV]
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        developing++
        status.text = "冲洗中（$developing 张）…"
        // The still's exposure: as metered, unless an HDR burst is held darker (highlights
        // survive; merging cleans the shadows; the develop lifts them back) or the film ISO
        // re-splits the metered exposure. A held exposure needs a few frames to land.
        val controller = exposure
        // Flash: 1 is a real flash in one frame (the HAL meters it with a pre-flash); 2-4 mix
        // an ambient frame and one lit by the flash LED, both held 1.5 stops under the meter.
        val flashMode = flashMode()
        val singleFlash = flashMode == 1
        val flashPair = flashMode >= 2
        val flashWhiteChoice = store[Settings.FLASH_WHITE].toInt()
        // A single RAW flash with auto white: an ambient frame first, only to see where the
        // flash lit (it steers a smooth white map; the pixels are the flash frame's).
        val ambientNeutral = controller?.ambientNeutral
        val flashNeutral = controller?.flashNeutral()
        val whiteMap = if (singleFlash && rawCapture && flashWhiteChoice == 0 && ambientNeutral != null && flashNeutral != null)
            floatArrayOf(*ambientNeutral, *flashNeutral) else null
        val hdr = rawCapture && store.flag(Settings.HDR) && flashMode == 0
        val metered = controller?.live
        // 35 mm equivalent of the lens at the current zoom, for the photo's EXIF.
        val focal35 = camera?.cameraInfo?.let { info ->
            (equivalentMm(info) * (info.zoomState.value?.zoomRatio ?: 1f)).roundToInt().takeIf { it > 0 }
        }
        val under = when {
            flashPair -> 1.5 // headroom for what the flash adds: a flash frame clipping loses its colour
            hdr -> store[Settings.HDR_UNDER].toDouble()
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
        if (singleFlash && whiteMap == null) armFlash()
        // Exposure bracket: one longer frame after the short ones lifts the shadows; the
        // merge brings it to the short frames' exposure and drops what it clipped.
        val longStops = if (hdr) store[Settings.HDR_LONG].toDouble() else 0.0
        // The flash mix: [ambient gain, flash target, flash->ambient white r g b].
        val flashMix = if (flashPair && still != null) {
            // Per light (auto), or one white for the whole frame: the flash's or the room's.
            val white = (if (flashWhiteChoice == 0) controller?.flashWhite() else null) ?: floatArrayOf(1f, 1f, 1f)
            when (flashMode) {
                2 -> floatArrayOf(1f, 0.18f * store[Settings.FLASH_FILL], *white)            // fill
                3 -> floatArrayOf(1f, 0.35f, *white)                                          // balanced
                else -> floatArrayOf(Math.pow(2.0, -store[Settings.FLASH_UNDER].toDouble()).toFloat(), 0.35f, *white)
            }
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
            singleFlash -> "单张闪光"
            flashPair -> "闪光${Settings.flashModes[flashMode]}（两帧合成）"
            hdr -> "HDR"
            else -> "普通"
        })
        log.put("拍摄", "拍摄前压暗", if (under > 0) "%.1f 档".format(Locale.US, under) else "无")
        log.put("拍摄", "锁定曝光", still?.label() ?: "无（自动曝光）")
        log.put("拍摄", "长曝光帧", longExposure?.label())
        log.put("拍摄", "取景器亮度 key", meter?.key)
        recordCamera(log, focal35)
        controller?.record(log)
        recordRecipe(log, recipe)
        val release = {
            main.post {
                capturing = false
                if (singleFlash) runCatching { capture.flashMode = ImageCapture.FLASH_MODE_OFF }
                if (still != null || longExposure != null || singleFlash) controller?.release()
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
            val exposures = List<Exposure?>(shorts) { null } + listOfNotNull(longExposure ?: if (flashMix != null) still else null)
            val torchFrame = if (flashMix != null) count - 1 else -1
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
                    engineThread.execute {
                        // What each frame really got, against what was asked (the diagnosis
                        // for exposures that do not land).
                        Log.i(TAG, "shot $stamp metadata neutral ${neutral?.joinToString(" ") ?: "dng"} noise $noiseS $noiseO")
                        // Shooting data from the reference DNG (and the long frame's exposure).
                        // Lens data from the DNG; the exposure from what was held or metered.
                        val taken = still ?: metered
                        val shot = ShotInfo.fromDng(files[developed].path)?.let {
                            // a flash frame's exposure is the HAL's own and unknown here
                            if (singleFlash) it.copy(focal35 = it.focal35 ?: focal35, exposureNs = null, iso = null)
                            else it.copy(focal35 = it.focal35 ?: focal35,
                                exposureNs = taken?.timeNs ?: it.exposureNs, iso = taken?.iso ?: it.iso)
                        }
                        val kind = when {
                            singleFlash -> if (whiteMap != null) "闪光 · 分区白平衡" else "闪光"
                            flashMix != null -> "闪光${Settings.flashModes[flashMode]}"
                            longExposure != null -> "HDR 包围 $shorts+1 帧 · 长曝光 ${longExposure.label().replace("ISO", "· ISO")}" 
                            count > 1 -> "HDR $count 帧"
                            else -> "RAW"
                        }
                        finish(stamp, recipe, quality, kind, shot, log) {
                            try {
                                engine().developDng(files.map { it.path }, params, developOptions).also {
                                    if (keepRaw) Photos.saveDng(this@CameraActivity, files[developed], "FILM_$stamp.dng")
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
            main.postDelayed({
                capture.takePicture(engineThread, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        release()
                        val shot = jpegShot(image)?.let { it.copy(focal35 = it.focal35 ?: focal35) }
                        finish(stamp, recipe, quality, if (singleFlash) "JPEG · 闪光" else "JPEG", shot, log) {
                            engine().develop(upright(image), params, options)
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        release()
                        failed(exception)
                    }
                })
            }, settle)
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

    /** The flash mode in use: 0 off; a lens with a flash; the mixed modes (2-4) need RAW. */
    private fun flashMode(): Int {
        if (exposure?.hasFlash != true) return 0
        val mode = store[Settings.FLASH].toInt().coerceIn(0, Settings.flashModes.lastIndex)
        return if (mode >= 2 && !rawCapture) 0 else mode
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
            applyLut(lut)
            val developed = develop()
            val filmOn = recipe["stage film"] != 0f
            val stock = FilmEngine.filmStocks[recipe.getValue("film").toInt()]
            val name = "FILM_${stamp}_${(if (filmOn) stock else "digital").replace(Regex("[^a-z0-9]+"), "-")}.jpg"
            val paper = FilmEngine.papers[recipe.getValue("paper").toInt().coerceIn(0, FilmEngine.papers.lastIndex)]
            // What the gallery shows as the photo's recipe.
            val text = recipeText(filmOn, recipe["stage print"] != 0f, stock, paper, lut.first)
            Photos.saveJpeg(this, developed.bitmap, name, quality, "$text · $source", shot)
            if (log != null) {
                log.put("拍摄", "类型", source)
                log.put("胶片配方", "配方", text)
                log.putEngine(developed.info)
                val res = "冲洗结果"
                log.put(res, "文件", name)
                log.put(res, "成片尺寸", "${developed.bitmap.width}x${developed.bitmap.height}")
                log.put(res, "JPEG 质量", quality)
                log.put(res, "冲洗用时", "%.2f 秒".format(Locale.US, (System.nanoTime() - start) / 1e9))
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
            val bitmap = developed.bitmap
            val thumb = Bitmap.createScaledBitmap(bitmap, 300, 300 * bitmap.height / bitmap.width, true)
            runOnUiThread { thumbnail.setImageBitmap(thumb) }
            "%s · %s · %dx%d · GPU %.0f ms · 冲洗 %.1f 秒".format(stock, source, bitmap.width, bitmap.height,
                developed.gpuMs, (System.nanoTime() - start) / 1e9)
        } catch (e: Throwable) {
            Log.e(TAG, "develop failed", e)
            "冲洗失败：${e.message}"
        }
        Log.i(TAG, message)
        runOnUiThread { developing--; status.text = message }
    }

    private fun failed(exception: ImageCaptureException) {
        Log.e(TAG, "capture failed", exception)
        runOnUiThread { developing--; status.text = "拍摄失败：${exception.message}" }
    }

    private fun loadLatestThumbnail() {
        engineThread.execute {
            // Always replace: the shown photo may have been deleted, or the album emptied.
            val thumb = Photos.list(this).firstOrNull()?.let { latest ->
                runCatching { contentResolver.loadThumbnail(latest.uri, android.util.Size(300, 300), null) }.getOrNull()
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

    companion object {
        private const val TAG = "FilmCamera"
        private const val LANDSCAPE_RAIL_DP = 88
        // Recipe-only entries next to the engine parameters (never sent to the engine).
        private const val LUT_KEY = "app.lut"
        private const val LUT_STRENGTH_KEY = "app.lutstrength"
    }
}
