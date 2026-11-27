package org.photoall.film.collage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import org.json.JSONArray
import org.json.JSONObject
import org.photoall.film.collage.Glassy.dp
import org.photoall.film.collage.Glassy.lp
import org.photoall.film.collage.Glassy.space
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 拼贴相机's camera, made your own: the viewfinder a rounded window in a dark body, and your
 * stickers stuck round it wherever you put them (定制: drag, two fingers to size and turn,
 * kept between visits). Plain, quick shots (the phone's own JPEG) for collages and stickers.
 * [EXTRA_FOR_STICKER]: the shot goes straight on to 制作贴纸.
 */
class CollageCameraActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var preview: PreviewView
    private lateinit var decor: Decor
    private lateinit var thumb: ImageView
    private lateinit var flashKey: TextView
    private lateinit var zooms: LinearLayout
    private lateinit var editBar: View
    /**
     * AI 相机: the same camera with nothing stuck round it; each shot is retouched by itself as AI
     * 修图's 一键 does it (the adaptive colour model, light for a dark picture) on a thread of its own
     * and saved retouched, while the next can already be taken. Nothing to open, nothing to wait for.
     */
    private val ai by lazy { intent.getBooleanExtra(EXTRA_AI, false) }
    /** How this camera's shots are described in the album (its filter there). */
    private val kind by lazy { if (ai) "AI 相机" else "拼贴相机" }
    private val aiWorker = Executors.newSingleThreadExecutor()
    /** AI 相机's framing guide over the picture, fed by [guideWorker] a few times a second. */
    private var guide: ComposeGuide? = null
    private val guideWorker = Executors.newSingleThreadExecutor()
    private var lastGuide = 0L
    private var guideFailed = false
    private val tilt = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            // the phone upright: its roll from gravity across the screen
            val roll = Math.toDegrees(kotlin.math.atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
            guide?.let { g -> if (kotlin.math.abs(roll - g.roll) > 0.3f) g.roll = roll }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }
    private var retouch: org.photoall.film.retouch.Retouch? = null
    private var toldAiFailed = false
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    /** The phone's lenses as the film camera reads them ([org.photoall.film.camera.Lenses]), less the hidden focal lengths. */
    private var options: List<org.photoall.film.camera.Lenses.LensOption> = emptyList()
    private var referenceMm = 0f
    /** The lens to bind (null: the facing's default camera) and the zoom that picks it. */
    private var lens: org.photoall.film.camera.Lenses.LensOption? = null
    private var back = true
    private var flash = ImageCapture.FLASH_MODE_OFF

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) bind() else finish() }
    private val pickSticker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getStringExtra(StickerLibraryActivity.EXTRA_ID) ?: return@registerForActivityResult
        worker.execute { Stickers.load(this, id)?.let { b -> runOnUiThread { decor.add(id, b) } } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Glassy.apply(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xff141416.toInt()))
        super.onCreate(savedInstanceState)
        val a = this
        preview = PreviewView(a).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        val window = FrameLayout(a).apply {
            background = Glassy.glass(a, 28f, 0x14ffffff)
            setPadding(dp(a, 3f), dp(a, 3f), dp(a, 3f), dp(a, 3f))
            addView(FrameLayout(a).apply {
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 25 * resources.displayMetrics.density)
                }
                addView(preview, FrameLayout.LayoutParams(-1, -1))
                if (ai) addView(ComposeGuide(a).also { guide = it }, FrameLayout.LayoutParams(-1, -1))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val pinch = android.view.ScaleGestureDetector(a, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                val c = camera ?: return false
                val z = c.cameraInfo.zoomState.value ?: return false
                c.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        preview.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !pinch.isInProgress && e.eventTime - e.downTime < 250) {
                // a tap: focus and meter there
                camera?.cameraControl?.startFocusAndMetering(
                    androidx.camera.core.FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(e.x, e.y)).build())
            }
            true
        }
        flashKey = Glassy.text(a, "⚡ 关", 13f, Glassy.TEXT, Glassy.semibold).apply {
            setPadding(dp(a, 12f), dp(a, 6f), dp(a, 12f), dp(a, 6f))
            setOnClickListener { cycleFlash() }
        }
        val topBar = Glassy.row(a) {
            background = Glassy.glass(a, 999f, 0x14ffffff)
            setPadding(dp(a, 6f), 0, dp(a, 6f), 0)
            addView(Glassy.text(a, "‹", 22f, Glassy.TEXT).apply { setPadding(dp(a, 10f), 0, dp(a, 10f), dp(a, 2f)); setOnClickListener { finish() } })
            addView(flashKey)
            if (!ai) addView(Glassy.text(a, "定制", 13f, Glassy.TEXT, Glassy.semibold).apply {
                setPadding(dp(a, 12f), dp(a, 6f), dp(a, 12f), dp(a, 6f)); setOnClickListener { setEditing(!decor.editing) }
            })
        }
        zooms = Glassy.row(a) { gravity = Gravity.CENTER }
        thumb = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true; background = Glassy.glass(a, 14f)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 14 * resources.displayMetrics.density)
            }
            // AI 相机: straight into the album's viewer at the shot; 拼贴相机: what to do with it
            setOnClickListener { if (ai) openInAlbum() else lastShot?.let { shotActions(it) } ?: startActivity(Intent(a, PhotoPickerActivity::class.java)) }
            // long press: this photo in the album's own viewer (the newest 拼贴相机 shot when none this time)
            setOnLongClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); openInAlbum(); true }
        }
        val shutter = View(a).apply {
            background = ShutterFace(resources.displayMetrics.density)
            org.photoall.film.ui.Style.pressable(this)
            setOnClickListener { shoot() }
        }
        val bottom = Glassy.row(a) {
            gravity = Gravity.CENTER
            addView(thumb, lp(dp(a, 48f), dp(a, 48f)))
            addView(View(a), lp(0, 1, 1f))
            addView(shutter, lp(dp(a, 78f), dp(a, 78f)))
            addView(View(a), lp(0, 1, 1f))
            addView(Glassy.roundKey(a, "⟲", 18f) { back = !back; lens = null; options = emptyList(); bind() }, lp(dp(a, 48f), dp(a, 48f)))
        }
        editBar = Glassy.row(a) {
            gravity = Gravity.CENTER
            addView(Glassy.pill(a, "加贴纸", "✦") { pickSticker.launch(Intent(a, StickerLibraryActivity::class.java).putExtra(StickerLibraryActivity.EXTRA_PICK, true).putExtra(Flows.EXTRA_RETURN, "贴到相机")) }, lp(0, dp(a, 44f), 1f))
            addView(View(a), lp(dp(a, 8f), 1))
            addView(Glassy.pill(a, "移除") { decor.removeSelected() }, lp(0, dp(a, 44f), 1f))
            addView(View(a), lp(dp(a, 8f), 1))
            addView(Glassy.pill(a, "完成", strong = true) { setEditing(false) }, lp(0, dp(a, 44f), 1f))
            visibility = View.GONE
        }
        val body = Glassy.vertical(a) {
            setPadding(dp(a, 16f), dp(a, 8f), dp(a, 16f), dp(a, 14f))
            addView(topBar, lp(-2, dp(a, 38f)) { gravity = Gravity.CENTER_HORIZONTAL })
            space(dp(a, 12f))
            // the window, centred; its tools are one key beside the focal lengths
            addView(Ratio(a, 3f / 4f).apply { addView(window, FrameLayout.LayoutParams(-1, -1)) }, lp(-1, 0, 1f))
            space(dp(a, 12f))
            addView(zooms, lp(-1, dp(a, 42f)))
            space(dp(a, 14f))
            addView(editBar, lp(-1, -2))
            addView(bottom, lp(-1, -2) { marginStart = dp(a, 12f); marginEnd = dp(a, 12f) })
        }
        decor = Decor(a)
        setContentView(Glassy.root(a, FrameLayout(a).apply {
            addView(body, FrameLayout.LayoutParams(-1, -1))
            addView(decor, FrameLayout.LayoutParams(-1, -1))
        }))
        worker.execute {
            // the corner shows the newest shot this camera made, so a tap or a long press has one to open
            runCatching {
                val P = org.photoall.film.gallery.Photos
                P.list(a).firstOrNull { P.filmOf(it) == kind }?.let { ph ->
                    val t = P.thumbnail(a, ph)
                    runOnUiThread { if (lastShot == null) { lastShot = ph.uri; t?.let { thumb.setImageBitmap(it) } } }
                }
            }
            if (!ai) decor.load()
            // arriving with a sticker (制作贴纸 → 贴到相机): it goes on, ready to place
            intent.getStringExtra(EXTRA_STICKER)?.let { id -> Stickers.load(a, id)?.let { b -> runOnUiThread { setEditing(true); decor.add(id, b) } } }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bind()
        else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        // shots still being retouched are finished and saved; then the models let go
        aiWorker.execute { runCatching { retouch?.close() } }
        aiWorker.shutdown()
        guideWorker.shutdownNow()
        super.onDestroy()
    }

    @Volatile private var lastShot: android.net.Uri? = null

    /** The last shot: on to a sticker, a collage, the editor, or the album. */
    private fun shotActions(uri: android.net.Uri) {
        val a = this
        val layer = FrameLayout(a)
        fun close() { (layer.parent as? android.view.ViewGroup)?.removeView(layer) }
        val sheet = Glassy.vertical(a) {
            background = Glassy.glass(a, 30f, 0xe6202024.toInt())
            setPadding(dp(a, 16f), dp(a, 16f), dp(a, 16f), dp(a, 16f))
            addView(Glassy.text(a, "这张照片", 15f, Glassy.TEXT, Glassy.semibold).apply { gravity = Gravity.CENTER }, lp(-1, -2) { bottomMargin = dp(a, 12f) })
            for ((label, go) in listOf<Pair<String, () -> Unit>>(
                "做成贴纸" to { startActivity(Intent(a, StickerMakerActivity::class.java).putExtra(StickerMakerActivity.EXTRA_URI, uri)) },
                "拼贴" to { startActivity(Intent(a, CollageActivity::class.java).setData(uri)) },
                "编辑 · 智能修图" to { startActivity(Flows.edit(a, uri)) },
                "作品" to { startActivity(Flows.gallery(a, "拼贴")) },
            )) addView(Glassy.pill(a, label) { close(); go() }, lp(-1, dp(a, 48f)) { bottomMargin = dp(a, 8f) })
        }
        layer.setBackgroundColor(0x66000000)
        layer.setOnClickListener { close() }
        sheet.isClickable = true
        layer.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(a, 12f), 0, dp(a, 12f), dp(a, 28f)) })
        addContentView(layer, android.view.ViewGroup.LayoutParams(-1, -1))
        sheet.translationY = dp(a, 300f).toFloat()
        sheet.animate().translationY(0f).setDuration(380).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)).start()
    }

    /** A frame that keeps its child at [ratio] (width / height), as large as fits, centred. */
    private class Ratio(context: Context, private val ratio: Float) : FrameLayout(context) {
        override fun onMeasure(w: Int, h: Int) {
            val mw = MeasureSpec.getSize(w); val mh = MeasureSpec.getSize(h)
            var cw = mw; var ch = (mw / ratio).toInt()
            if (ch > mh) { ch = mh; cw = (mh * ratio).toInt() }
            val cs = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY); val hs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(cs, hs)
            setMeasuredDimension(mw, mh)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = (width - c.measuredWidth) / 2; val y = (height - c.measuredHeight) / 2
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }
    }

    private fun setEditing(on: Boolean) {
        decor.editing = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) worker.execute { decor.save() }
        decor.invalidate()
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun bind() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // 4:3, the sensor's own shape, as the window shows it
            val ratio = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val pv = Preview.Builder().setResolutionSelector(ratio).build().also { it.surfaceProvider = preview.surfaceProvider }
            // quality first, and the phone's own sharpening off: its single-frame sharpening drew white
            // halos round every edge (the Galaxy S23 Ultra's); noise reduction at its best
            val capBuilder = ImageCapture.Builder().setResolutionSelector(ratio)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setFlashMode(flash)
            androidx.camera.camera2.interop.Camera2Interop.Extender(capBuilder)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE, android.hardware.camera2.CaptureRequest.EDGE_MODE_OFF)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE, android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            val cap = capBuilder.build()
            this.provider = provider
            if (options.isEmpty()) readLenses(provider)
            val chosen = lens
            provider.unbindAll()
            // AI 相机: the picture read a few times a second for its framing
            val look = if (ai) androidx.camera.core.ImageAnalysis.Builder().setResolutionSelector(ratio)
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                .also { it.setAnalyzer(guideWorker) { img -> frame(img) } } else null
            val selector = chosen?.camera?.cameraSelector ?: if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            camera = runCatching {
                if (look != null) provider.bindToLifecycle(this, selector, pv, cap, look) else provider.bindToLifecycle(this, selector, pv, cap)
            }.recoverCatching {
                // a phone that cannot stream all three: the camera without the guide (said once)
                if (look == null) throw it
                runOnUiThread { Toast.makeText(this, "这台手机同时预览、拍照和分析画面会冲突，构图引导先关闭", Toast.LENGTH_LONG).show(); guide?.visibility = View.GONE }
                provider.unbindAll(); provider.bindToLifecycle(this, selector, pv, cap)
            }.getOrNull()
            capture = cap
            chosen?.let { camera?.cameraControl?.setZoomRatio(it.zoom) }
            camera?.cameraInfo?.zoomState?.observe(this) { paintLenses() }
            fillZooms()
        }, mainExecutor)
    }

    /** The facing's lenses, as the film camera reads them; the focal lengths hidden there hidden here too. */
    private fun readLenses(provider: ProcessCameraProvider) {
        val front = !back
        val quirks = getSharedPreferences("camera_quirks", MODE_PRIVATE)
        val dead = quirks.all.keys.filter { it.startsWith("lens_dead_") }.map { it.removePrefix("lens_dead_") }.toSet()
        val L = org.photoall.film.camera.Lenses
        referenceMm = L.mainCamera(L.lenses(provider, front))?.let { L.equivalentMm(it) } ?: 0f
        val all = runCatching { L.lensOptions(this, provider, front, dead, probe = false) }.getOrDefault(emptyList())
        val hidden = org.photoall.film.camera.FocalLengths.hidden(this)
        options = all.filter { focal(it) !in hidden }.ifEmpty { all }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun idOf(info: androidx.camera.core.CameraInfo) =
        runCatching { androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId }.getOrNull()

    private fun focal(o: org.photoall.film.camera.Lenses.LensOption) =
        if (referenceMm > 0f) Math.round(referenceMm * o.ratio) else Math.round(o.ratio * 24)

    /** A lens: the same camera zooms to it; another camera is bound for it. */
    private fun select(o: org.photoall.film.camera.Lenses.LensOption) {
        val c = camera
        if (c != null && idOf(o.camera) == idOf(c.cameraInfo)) {
            lens = o; c.cameraControl.setZoomRatio(o.zoom)
        } else { lens = o; bind() }
    }

    /** The lens in use lit: the bound camera's option whose magnification is nearest the zoom now. */
    private fun paintLenses() {
        val c = camera ?: return
        val z = c.cameraInfo.zoomState.value?.zoomRatio ?: return
        val id = idOf(c.cameraInfo)
        val mine = options.filter { idOf(it.camera) == id }
        // on one camera every option's magnification is its zoom times the same scale
        val base = mine.firstOrNull()?.let { it.ratio / it.zoom } ?: 1f
        val now = base * z
        val on = mine.minByOrNull { kotlin.math.abs(it.ratio / now - 1f) }?.takeIf { kotlin.math.abs(it.ratio / now - 1f) < 0.12f }
        for (i in 0 until zooms.childCount) {
            val b = zooms.getChildAt(i) as? org.photoall.film.ui.LensButton ?: continue
            val o = b.tag as? org.photoall.film.camera.Lenses.LensOption ?: continue
            b.show(focal(o), o === on)
        }
    }

    private fun fillZooms() {
        val a = this
        zooms.removeAllViews()
        // the phone's lenses, by focal length, as on the film camera
        for (o in options) zooms.addView(org.photoall.film.ui.LensButton(a).apply {
            tag = o
            show(focal(o), false)
            setOnClickListener { select(o) }
        }, lp(dp(a, 38f), dp(a, 38f)) { marginStart = dp(a, 3f); marginEnd = dp(a, 3f) })
        paintLenses()
        // the tools that used to stand in a rail beside the window, behind one key
        if (!ai) zooms.addView(Glassy.roundKey(a, "⋯", 16f) { toolsSheet() }, lp(dp(a, 40f), dp(a, 34f)) { marginStart = dp(a, 12f) })
    }

    /**
     * The last shot (or, none taken this time, the newest one this camera made) open in the album's
     * viewer, at its place among all the photos, as the gallery opens it.
     */
    private fun openInAlbum() = worker.execute {
        val list = org.photoall.film.gallery.Photos.filter(org.photoall.film.gallery.Photos.list(this, false), null, false)
        val target = lastShot ?: list.firstOrNull { org.photoall.film.gallery.Photos.filmOf(it) == kind }?.uri
        val at = list.indexOfFirst { it.uri == target }
        runOnUiThread {
            if (at < 0) { Toast.makeText(this, "还没有用${if (ai) "AI 相机" else "拼贴相机"}拍过照片", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
            startActivity(Intent(this, org.photoall.film.gallery.ViewerActivity::class.java)
                .putExtra(org.photoall.film.gallery.ViewerActivity.INDEX, at))
        }
    }

    /** 加贴纸, 拼贴, 做贴纸, 作品: a glass sheet from the bottom. */
    private fun toolsSheet() {
        val a = this
        val layer = FrameLayout(a)
        fun close() { (layer.parent as? android.view.ViewGroup)?.removeView(layer) }
        val sheet = Glassy.vertical(a) {
            background = Glassy.glass(a, 30f, 0xe6202024.toInt())
            setPadding(dp(a, 16f), dp(a, 16f), dp(a, 16f), dp(a, 16f))
            for ((label, go) in listOf<Pair<String, () -> Unit>>(
                "✦  加贴纸到相机" to { setEditing(true); pickSticker.launch(Intent(a, StickerLibraryActivity::class.java).putExtra(StickerLibraryActivity.EXTRA_PICK, true).putExtra(Flows.EXTRA_RETURN, "贴到相机")) },
                "▦  拼贴" to { startActivity(Intent(a, CollageActivity::class.java)) },
                "✂︎  制作贴纸" to { startActivity(Intent(a, StickerMakerActivity::class.java)) },
                "▣  作品" to { startActivity(Flows.gallery(a, "拼贴")) },
            )) addView(Glassy.pill(a, label) { close(); go() }, lp(-1, dp(a, 48f)) { bottomMargin = dp(a, 8f) })
        }
        layer.setBackgroundColor(0x66000000)
        layer.setOnClickListener { close() }
        sheet.isClickable = true
        layer.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(a, 12f), 0, dp(a, 12f), dp(a, 28f)) })
        addContentView(layer, android.view.ViewGroup.LayoutParams(-1, -1))
        sheet.translationY = dp(a, 300f).toFloat()
        sheet.animate().translationY(0f).setDuration(380).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)).start()
    }

    private fun cycleFlash() {
        flash = when (flash) { ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO; ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
        flashKey.text = when (flash) { ImageCapture.FLASH_MODE_AUTO -> "⚡ 自动"; ImageCapture.FLASH_MODE_ON -> "⚡ 开"; else -> "⚡ 关" }
        capture?.flashMode = flash
    }

    /**
     * AI 相机's shot: shown at once in the corner (faint while it is worked on), retouched as 一键
     * does it, given the filter its scene calls for ([SceneLook]), saved, shown full. Should the models fail on this phone, the shot is saved as taken
     * and the user told (once).
     */
    private fun retouchAndSave(bmp: Bitmap) {
        val small = Bitmap.createScaledBitmap(bmp, 160, 160 * bmp.height / bmp.width, true)
        runOnUiThread { thumb.setImageBitmap(small); thumb.alpha = 0.55f }
        aiWorker.execute {
            val done = runCatching {
                val r = retouch ?: org.photoall.film.retouch.Retouch(this).also { retouch = it }
                val plan = org.photoall.film.retouch.Retouch.Plan().apply {
                    // half strength: a lift, not a new picture
                    colorModel = org.photoall.film.retouch.Retouch.Color.ADAPTIVE; color = 0.5f
                    light = if (meanLuma(bmp) < 0.3f) 0.5f else 0f
                }
                r.full(bmp, plan)
            }
            val retouched = done.getOrNull() ?: bmp
            val crop = runCatching { Composition.cropFor(Composition.analyze(this, bmp), bmp.width.toFloat() / bmp.height) }.getOrNull()
            val toned = crop?.let { c ->
                val x = (c.left * retouched.width).toInt().coerceIn(0, retouched.width - 2); val y = (c.top * retouched.height).toInt().coerceIn(0, retouched.height - 2)
                val cw = (c.width() * retouched.width).toInt().coerceAtMost(retouched.width - x); val ch = (c.height() * retouched.height).toInt().coerceAtMost(retouched.height - y)
                Bitmap.createBitmap(retouched, x, y, cw, ch).also { if (it !== retouched && retouched !== bmp) retouched.recycle() }
            } ?: retouched
            // the filter the scene calls for, over the retouched picture
            val look = runCatching { SceneLook.choose(bmp) }.getOrNull()
            val graded = look?.let { l -> runCatching { SceneLook.apply(this, toned, l) }.getOrNull()?.also { if (toned !== bmp) toned.recycle() } } ?: toned
            // the colour model and the negative stock both flatten a picture: its punch given back
            val punched = runCatching { keepContrast(bmp, crop, graded) }.getOrNull() ?: graded
            // the phone's sharpening taken back: its halos and crunch, not the detail
            val out = runCatching { desharpen(punched, 0.55f) }.getOrNull() ?: punched
            val name = "AI_SHOT_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date()) + ".jpg"
            val about = listOfNotNull("AI 相机", if (done.isFailure) "原图" else null, look?.let { "${it.scene} · ${it.label}" }, crop?.let { "构图裁切" }).joinToString(" · ")
            val uri = runCatching { org.photoall.film.gallery.Photos.saveJpeg(this, out, name, 95, about) }.getOrNull()
            val shown = Bitmap.createScaledBitmap(out, 160, 160 * out.height / out.width, true)
            if (out !== bmp) out.recycle()
            bmp.recycle()
            runOnUiThread {
                if (uri != null) lastShot = uri
                thumb.setImageBitmap(shown); thumb.animate().alpha(1f).setDuration(200).start()
                if (uri == null) Toast.makeText(this, "存储失败", Toast.LENGTH_SHORT).show()
                else if (done.isFailure && !toldAiFailed) {
                    toldAiFailed = true
                    Toast.makeText(this, "AI 处理在这台手机上失败（${done.exceptionOrNull()?.message}），这张存的是原图", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** A preview frame for the guide (at most ~3 a second): its best framing, shown over the picture. */
    private fun frame(img: ImageProxy) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (guideFailed || now - lastGuide < 330) { img.close(); return }
        lastGuide = now
        val turn = img.imageInfo.rotationDegrees
        val bmp = runCatching { img.toBitmap() }.getOrNull()
        img.close()
        bmp ?: return
        val up = if (turn != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true) else bmp
        val r = runCatching { Composition.analyze(this, up) }
        r.exceptionOrNull()?.let { e ->
            guideFailed = true
            runOnUiThread { guide?.visibility = View.GONE; Toast.makeText(this, "构图引导在这台手机上用不了：${e.message}", Toast.LENGTH_LONG).show() }
        }
        val res = r.getOrNull() ?: return
        // the front camera's picture is shown mirrored
        val box = if (!back) RectF(1 - res.box.right, res.box.top, 1 - res.box.left, res.box.bottom) else res.box
        runOnUiThread { guide?.show(Composition.Result(box, res.gain)) }
    }

    override fun onResume() {
        super.onResume()
        if (ai) (getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager).let { sm ->
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)?.let { sm.registerListener(tilt, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        }
    }

    override fun onPause() {
        if (ai) (getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager).unregisterListener(tilt)
        super.onPause()
    }

    /**
     * The phone's sharpening undone, as far as it can be: the picture mixed with a soft copy of
     * itself ([amount] of the way), the soft copy a 1-2-1 filter twice each way (a Gaussian of about
     * a pixel, the radius phones sharpen at). Unsharp masking run backwards: the white rims round
     * edges and the crisp grit in walls and leaves fall back, the picture's own detail stays.
     * In place when it can be. Worker thread.
     */
    private fun desharpen(src: Bitmap, amount: Float): Bitmap {
        val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        val w = out.width; val h = out.height
        val a = (amount * 256).toInt(); val keep = 256 - a
        // three rows at a time: blurred across, then down
        val rows = Array(5) { IntArray(w) }
        val across = Array(5) { IntArray(w * 3) }
        fun blurRow(src: IntArray, dst: IntArray) {
            // 1-2-1 twice = 1-4-6-4-1 / 16, per channel, the ends held
            for (x in 0 until w) {
                val x0 = maxOf(x - 2, 0); val x1 = maxOf(x - 1, 0); val x3 = minOf(x + 1, w - 1); val x4 = minOf(x + 2, w - 1)
                for (ch in 0..2) {
                    val sh = 16 - 8 * ch
                    dst[x * 3 + ch] = ((src[x0] shr sh and 255) + 4 * (src[x1] shr sh and 255) + 6 * (src[x] shr sh and 255) +
                        4 * (src[x3] shr sh and 255) + (src[x4] shr sh and 255))
                }
            }
        }
        val original = IntArray(w * h)
        out.getPixels(original, 0, w, 0, 0, w, h)
        fun rowAt(y: Int) = minOf(maxOf(y, 0), h - 1)
        val line = IntArray(w)
        // each row blurred across once, kept while the five rows round it are in use
        val slotRow = IntArray(5) { -1 }
        fun across(yy: Int): IntArray {
            val slot = yy % 5
            if (slotRow[slot] != yy) {
                System.arraycopy(original, yy * w, rows[slot], 0, w)
                blurRow(rows[slot], across[slot])
                slotRow[slot] = yy
            }
            return across[slot]
        }
        for (y in 0 until h) {
            val r0 = across(rowAt(y - 2)); val r1 = across(rowAt(y - 1)); val r2 = across(y); val r3 = across(rowAt(y + 1)); val r4 = across(rowAt(y + 2))
            for (x in 0 until w) {
                val c = original[y * w + x]
                var o = c and 0xff000000.toInt()
                for (ch in 0..2) {
                    val i = x * 3 + ch
                    val soft = (r0[i] + 4 * r1[i] + 6 * r2[i] + 4 * r3[i] + r4[i]) shr 8
                    val v = ((c shr (16 - 8 * ch) and 255) * keep + soft * a) shr 8
                    o = o or (v.coerceIn(0, 255) shl (16 - 8 * ch))
                }
                line[x] = o
            }
            out.setPixels(line, 0, w, 0, y, w, 1)
        }
        return out
    }

    /**
     * [graded]'s contrast brought back toward the shot's own ([shot], the part [crop] kept): the
     * brightness spread measured on both, [graded] stretched about its own mean by up to 1.3 (80 %
     * of the way), its colours and brightness kept. A flat, grey result was the AI camera's fault.
     */
    private fun keepContrast(shot: Bitmap, crop: RectF?, graded: Bitmap): Bitmap {
        fun stats(b: Bitmap, r: RectF?): Pair<Float, Float> {
            val src = r?.let { c -> Bitmap.createBitmap(b, (c.left * b.width).toInt(), (c.top * b.height).toInt(),
                (c.width() * b.width).toInt().coerceAtMost(b.width - (c.left * b.width).toInt()),
                (c.height() * b.height).toInt().coerceAtMost(b.height - (c.top * b.height).toInt())) } ?: b
            val s = Bitmap.createScaledBitmap(src, 96, 128, true)
            val px = IntArray(96 * 128); s.getPixels(px, 0, 96, 0, 0, 96, 128)
            if (s !== src) s.recycle(); if (src !== b) src.recycle()
            var sum = 0.0; var sq = 0.0
            for (c in px) { val l = 0.3 * (c shr 16 and 255) + 0.59 * (c shr 8 and 255) + 0.11 * (c and 255); sum += l; sq += l * l }
            val m = sum / px.size
            return m.toFloat() to kotlin.math.sqrt((sq / px.size - m * m).coerceAtLeast(0.0)).toFloat()
        }
        val (_, before) = stats(shot, crop)
        val (mean, after) = stats(graded, null)
        if (after < 1f) return graded
        val k = 1f + ((before / after).coerceIn(1f, 1.3f) - 1f) * 0.8f
        if (k < 1.01f) return graded
        val out = if (graded.isMutable) graded else graded.copy(Bitmap.Config.ARGB_8888, true).also { graded.recycle() }
        val w = out.width; val row = IntArray(w)
        val lut = IntArray(256) { v -> (mean + (v - mean) * k).toInt().coerceIn(0, 255) }
        for (y in 0 until out.height) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                row[x] = (c and 0xff000000.toInt()) or (lut[c shr 16 and 255] shl 16) or (lut[c shr 8 and 255] shl 8) or lut[c and 255]
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun meanLuma(b: Bitmap): Float {
        val s = Bitmap.createScaledBitmap(b, 32, 32, true)
        val px = IntArray(32 * 32); s.getPixels(px, 0, 32, 0, 0, 32, 32); if (s !== b) s.recycle()
        return px.sumOf { ((it shr 16 and 255) * 0.3 + (it shr 8 and 255) * 0.59 + (it and 255) * 0.11) }.toFloat() / px.size / 255f
    }

    private fun shoot() {
        val cap = capture ?: return
        preview.animate().alpha(0.2f).setDuration(60).withEndAction { preview.animate().alpha(1f).setDuration(220).start() }.start()
        cap.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val raw = image.toBitmap()
                val turn = image.imageInfo.rotationDegrees
                image.close()
                val bmp = if (turn != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn.toFloat()) }, true) else raw
                if (ai) { retouchAndSave(bmp); return }
                val name = (if (ai) "AI_SHOT_" else "COLLAGE_SHOT_") + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date()) + ".jpg"
                val uri = runCatching { org.photoall.film.gallery.Photos.saveJpeg(this@CollageCameraActivity, bmp, name, 95, kind) }.getOrNull()
                if (uri != null) lastShot = uri
                val small = Bitmap.createScaledBitmap(bmp, 160, 160 * bmp.height / bmp.width, true)
                runOnUiThread {
                    thumb.setImageBitmap(small)
                    if (uri != null && intent.getBooleanExtra(EXTRA_FOR_STICKER, false)) {
                        startActivity(Intent(this@CollageCameraActivity, StickerMakerActivity::class.java).putExtra(StickerMakerActivity.EXTRA_URI, uri)
                            .apply {
                                intent.getStringExtra(Flows.EXTRA_RETURN)?.let { putExtra(Flows.EXTRA_RETURN, it); addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT) }
                            })
                        finish()
                    }
                }
            }
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { Toast.makeText(this@CollageCameraActivity, "拍摄失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    /** The shutter: a white disc in a ring, softly lit. */
    private class ShutterFace(private val d: Float) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pressed = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed); if (now == pressed) return false; pressed = now; invalidateSelf(); return true
        }
        override fun draw(c: Canvas) {
            val r = bounds.width() / 2f; val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.color = 0xe6ffffff.toInt()
            c.drawCircle(cx, cy, r - 2 * d, p)
            p.style = Paint.Style.FILL
            p.shader = android.graphics.RadialGradient(cx - r * 0.2f, cy - r * 0.3f, r, intArrayOf(0xffffffff.toInt(), 0xffe9e9ee.toInt(), 0xffc9c9d0.toInt()),
                floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, (r - 8 * d) * if (pressed) 0.92f else 1f, p)
            p.shader = null
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * The stickers stuck on the camera, over everything; in 定制 they move, size and turn
     * under the fingers, otherwise every touch passes through to the camera's own keys.
     * Kept as shares of the screen in preferences.
     */
    private class Decor(context: Context) : View(context) {
        class Piece(val id: String, val bmp: Bitmap, var x: Float, var y: Float, var w: Float, var rot: Float)
        private val pieces = mutableListOf<Piece>()
        var editing = false
        private var selected: Piece? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val d = resources.displayMetrics.density
        private val prefs = context.getSharedPreferences("collage_camera", Context.MODE_PRIVATE)

        fun load() {
            val a = runCatching { JSONArray(prefs.getString("decor", "[]")) }.getOrDefault(JSONArray())
            val list = (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val b = Stickers.load(context, o.getString("id")) ?: return@mapNotNull null
                Piece(o.getString("id"), b, o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("w").toFloat(), o.getDouble("r").toFloat())
            }
            post { pieces.clear(); pieces += list; invalidate() }
        }

        fun save() {
            val a = JSONArray()
            for (it in pieces.toList()) a.put(JSONObject().put("id", it.id).put("x", it.x.toDouble()).put("y", it.y.toDouble())
                .put("w", it.w.toDouble()).put("r", it.rot.toDouble()))
            prefs.edit().putString("decor", a.toString()).apply()
        }

        fun add(id: String, b: Bitmap) {
            val it = Piece(id, b, 0.2f + Math.random().toFloat() * 0.6f, 0.25f + Math.random().toFloat() * 0.4f, 0.22f, (Math.random().toFloat() - 0.5f) * 30f)
            pieces += it; selected = it; invalidate()
        }

        fun removeSelected() { selected?.let { pieces.remove(it) }; selected = null; invalidate() }

        override fun onDraw(c: Canvas) {
            val W = width.toFloat(); val H = height.toFloat()
            for (it in pieces) {
                val sw = it.w * W; val sh = sw * it.bmp.height / it.bmp.width
                c.save(); c.translate(it.x * W, it.y * H); c.rotate(it.rot)
                c.drawBitmap(it.bmp, null, RectF(-sw / 2, -sh / 2, sw / 2, sh / 2), p)
                if (editing && it === selected) c.drawRoundRect(RectF(-sw / 2, -sh / 2, sw / 2, sh / 2), 10 * d, 10 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xccffffff.toInt()
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
                })
                c.restore()
                if (editing && it === selected) {
                    // the x to take it off, at its upper right corner, upright
                    val bx = badgeAt(it); val r = 13 * d
                    c.drawCircle(bx[0], bx[1], r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffff453a.toInt() })
                    val x = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffffff.toInt(); strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND }
                    c.drawLine(bx[0] - r * 0.4f, bx[1] - r * 0.4f, bx[0] + r * 0.4f, bx[1] + r * 0.4f, x)
                    c.drawLine(bx[0] + r * 0.4f, bx[1] - r * 0.4f, bx[0] - r * 0.4f, bx[1] + r * 0.4f, x)
                }
            }
        }

        private var lastX = 0f; private var lastY = 0f
        private var startDist = 0f; private var startAngle = 0f; private var startW = 0f; private var startRot = 0f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!editing) return false
            val W = width.toFloat(); val H = height.toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // the x on the chosen sticker takes it off
                    if (badge(e.x, e.y)) { removeSelected(); return true }
                    val found = hit(e.x, e.y) ?: return false // the keys under it stay usable; the choice stays
                    selected = found
                    selected?.let { pieces.remove(it); pieces.add(it) }
                    lastX = e.x; lastY = e.y; invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) selected?.let {
                    startDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)); startAngle = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                    startW = it.w; startRot = it.rot
                }
                MotionEvent.ACTION_MOVE -> selected?.let {
                    if (e.pointerCount >= 2 && startDist > 0f) {
                        it.w = (startW * hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)) / startDist).coerceIn(0.06f, 0.9f)
                        it.rot = startRot + Math.toDegrees((atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)) - startAngle).toDouble()).toFloat()
                    } else if (e.pointerCount == 1) { it.x += (e.x - lastX) / W; it.y += (e.y - lastY) / H; lastX = e.x; lastY = e.y }
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> { startDist = 0f; val k = if (e.actionIndex == 0) 1 else 0; lastX = e.getX(k); lastY = e.getY(k) }
            }
            return true
        }

        /** Where the chosen sticker's x sits: its upper right corner, turned with it. */
        private fun badgeAt(it: Piece): FloatArray {
            val W = width.toFloat(); val H = height.toFloat()
            val hw = it.w * W / 2; val hh = hw * it.bmp.height / it.bmp.width
            val a = Math.toRadians(it.rot.toDouble())
            return floatArrayOf((it.x * W + hw * Math.cos(a) + hh * Math.sin(a)).toFloat(), (it.y * H + hw * Math.sin(a) - hh * Math.cos(a)).toFloat())
        }

        private fun badge(x: Float, y: Float): Boolean {
            val s = selected ?: return false
            if (!editing) return false
            val b = badgeAt(s)
            return hypot(x - b[0], y - b[1]) < 22 * d
        }

        private fun hit(x: Float, y: Float): Piece? {
            val W = width.toFloat(); val H = height.toFloat()
            for (it in pieces.asReversed()) {
                val hw = it.w * W / 2; val hh = hw * it.bmp.height / it.bmp.width
                val a = Math.toRadians(-it.rot.toDouble())
                val dx = x - it.x * W; val dy = y - it.y * H
                val lx = dx * Math.cos(a) - dy * Math.sin(a); val ly = dx * Math.sin(a) + dy * Math.cos(a)
                if (kotlin.math.abs(lx) <= hw && kotlin.math.abs(ly) <= hh) return it
            }
            return null
        }
    }

    companion object {
        const val EXTRA_FOR_STICKER = "for_sticker"
        /** Open as AI 相机. */
        const val EXTRA_AI = "ai"
        /** A sticker to put on the camera at once (its id). */
        const val EXTRA_STICKER = "sticker"
    }
}
