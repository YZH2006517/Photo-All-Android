package org.photoall.film.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import org.photoall.film.engine.NativeDng
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Zero-shutter-lag RAW bursts, the way HDR+ captures: a Camera2 session streams the
 * viewfinder, a small YUV stream for the meter and full-size RAW frames, keeping the
 * last [RING] RAW frames in memory. The shutter takes the frames already captured (no
 * lag, no files in between) and, for a bracket, one longer frame right after. The
 * sensor runs [FilmExposure.under] stops under the meter so the frames keep their
 * highlights; the viewfinder and the meter show it brighter by the same amount.
 */
class ZslCamera(
    context: Context,
    val cameraId: String,
    val characteristics: CameraCharacteristics,
    private val exposure: FilmExposure,
    private val meter: PreviewMeter,
    /** A failure, and whether it is the lens refusing these streams (true) or a passing one. */
    private val onError: (String, Boolean) -> Unit,
    /**
     * RAW on demand: the repeating request feeds only the viewfinder and the meter, and a
     * shot is one STILL_CAPTURE request for a RAW frame. For a single RAW on a camera that
     * cannot stream full-size RAW at the preview rate (a MediaTek HAL fails the device,
     * error 4, when RAW is in the repeating request).
     */
    val onDemand: Boolean = false,
) {
    /**
     * A burst: one DNG per frame in native memory (newest = reference first; the caller
     * releases them) and their exposures.
     */
    class Shot(val dngs: List<NativeDng>, val exposures: FloatArray, val reference: TotalCaptureResult,
               val long: Boolean) {
        fun release() = dngs.forEach { it.release() }
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("zsl").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    // DNGs of a burst are written on several threads at once.
    private val writers = java.util.concurrent.Executors.newFixedThreadPool(4)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var preview: Surface? = null
    @Volatile private var closed = false

    /**
     * Where the session is on its way up (open, configure, first frame): the report's
     * answer when a shot finds no session, and the last step written down at each change.
     */
    @Volatile var state = "等取景画面"
        private set
    private fun step(what: String) {
        state = what
        org.photoall.film.CrashReport.stage("Camera2：$what")
        Log.i(TAG, "state: $what")
    }
    private var firstFrame = false

    /**
     * The RAW size streamed: the largest up to ~26 MP. Some phones (quad-Bayer 50 MP
     * sensors, often MediaTek) list the full-resolution RAW here too; a ring of those is
     * gigabytes and gets the app killed, so the binned size is taken when there is one.
     */
    val rawSize: Size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        .getOutputSizes(ImageFormat.RAW_SENSOR).let { sizes ->
            sizes.filter { it.width.toLong() * it.height <= MAX_RAW_PIXELS }.maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.minBy { it.width.toLong() * it.height }
        }

    /** Frames the ring keeps: [RING], fewer when the frames are large (about 600 MB of buffers at most). */
    val keep: Int = run {
        val bytes = rawSize.width.toLong() * rawSize.height * 2
        ((600_000_000L / bytes - 4) / 2).toInt().coerceIn(2, RING)
    }

    // Handler thread only: the last frames, kept in the reader's own (native) buffers,
    // newest last; the results by sensor timestamp.
    private val ring = ArrayDeque<Image>()
    private val results = LinkedHashMap<Long, TotalCaptureResult>()
    private var longWanted: Long = -1   // sensor timestamp of the long frame being waited for
    private var longFrame: Image? = null

    /** Zoom ratio (CONTROL_ZOOM_RATIO) and the tap-to-focus area. */
    @Volatile var zoom = 1f
        private set
    @Volatile var focusArea: MeteringRectangle? = null
    // AF-L: hold the focus found (AF_MODE_AUTO, no new trigger) instead of continuous AF.
    @Volatile private var focusLocked = false
    // Handler thread: a tap's AF scan being watched, and what to tell when it settles.
    private var focusDone: ((Boolean?) -> Unit)? = null
    private var focusDeadline = 0L
    private var focusStart = 0L

    /** Viewfinder buffer size (4:3, at most 1920 wide). */
    val previewSize: Size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        .getOutputSizes(SurfaceTexture::class.java)
        .filter { abs(it.width * 3 - it.height * 4) < 8 && it.width <= 1920 }
        .maxByOrNull { it.width } ?: Size(1440, 1080)

    @SuppressLint("MissingPermission")
    fun open(viewfinder: Surface) {
        preview = viewfinder
        // The ring, the frames of a burst being written out and a few for the camera.
        rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, if (onDemand) 3 else 2 * keep + 4).apply {
            setOnImageAvailableListener({ onRaw(it) }, handler)
        }
        yuvReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { meter.analyzeYuv(it) } }, handler)
        }
        exposure.onSettings = { handler.post { repeat() } }
        step("正在打开相机 $cameraId")
        // a session that never comes up says where it stopped, instead of waiting forever
        handler.postDelayed({
            if (!closed && session == null) onError("Camera2 会话 6 秒没建好，停在：$state", false)
        }, 6000)
        openDevice(0)
    }

    /**
     * Opens the camera; one still held by the previous user (CameraX closes it a moment after
     * it is unbound: a Xiaomi with a Snapdragon 765G answered ERROR_CAMERA_IN_USE or
     * ERROR_MAX_CAMERAS_IN_USE) is asked again a little later, a few times, before it fails.
     */
    @SuppressLint("MissingPermission")
    private fun openDevice(attempt: Int) {
        if (closed) return
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) return camera.close()
                    step("相机已打开，配置会话")
                    device = camera
                    // a camera HAL may refuse the streams by throwing rather than by
                    // onConfigureFailed; thrown here (a camera thread) it would end the app
                    try {
                        configure(camera)
                    } catch (e: Exception) {
                        Log.w(TAG, "configure", e)
                        if (!closed) onError("这颗镜头不能同时连续输出 RAW 和预览：${e.message}", true)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { step("相机被断开（别的应用或系统占用）"); camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    step("相机报错 $error")
                    camera.close()
                    device = null
                    if (closed) return
                    val busy = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                    if (busy && attempt < RETRIES.size) {
                        step("相机还被占用（$error），${RETRIES[attempt]} ms 后重试第 ${attempt + 1} 次")
                        handler.postDelayed({ openDevice(attempt + 1) }, RETRIES[attempt])
                    } else onError("相机打开失败（$error${if (busy) "：被占用" else ""}）", false)
                }
            }, handler)
        } catch (e: Exception) {
            onError("相机打开失败：${e.message}", false)
        }
    }

    /** False when the meter's stream was left out (the camera would not take all three). */
    var metering = true
        private set

    private fun configure(camera: CameraDevice) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) return s.close()
                step("会话已配置${if (metering) "" else "（无测光流）"}，开始预览")
                session = s
                repeat()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                step("会话配置失败")
                if (!closed) onError("这颗镜头不能同时输出 RAW 和预览", true)
            }
        }
        fun config(withMeter: Boolean) = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
            listOfNotNull(preview!!, yuvReader!!.surface.takeIf { withMeter }, rawReader!!.surface).map { OutputConfiguration(it) },
            executor, callback)
        // ask first (Android 12+): a set of streams the camera cannot run is changed before it is
        // tried, rather than left to a HAL that may never answer
        fun supported(c: SessionConfiguration): Boolean? =
            if (Build.VERSION.SDK_INT >= 31) runCatching { camera.isSessionConfigurationSupported(c) }.getOrNull() else null
        var chosen = config(true)
        val all = supported(chosen)
        if (all == false) {
            val lean = config(false)
            val two = supported(lean)
            step("预览+测光+RAW 不支持，预览+RAW ${when (two) { true -> "支持"; false -> "也不支持"; null -> "未知" }}")
            if (two != false) { chosen = lean; metering = false }
        } else step("会话组合：预览 ${previewSize} + 测光 + RAW $rawSize（${if (all == true) "支持" else "系统未答"}）")
        camera.createCaptureSession(chosen)
    }

    // Diagnosis: frame cadence and how often the repeating request was restarted.
    private var lastFrame = 0L
    private var frameCount = 0
    private var gaps = 0
    private var restarts = 0
    private var statsSince = 0L

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            exposure.captureCallback.onCaptureCompleted(s, request, result)
            if (!firstFrame) { firstFrame = true; step("预览已出帧") }
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (lastFrame > 0 && ts - lastFrame > 50_000_000L) gaps++
            lastFrame = ts
            frameCount++
            val now = android.os.SystemClock.uptimeMillis()
            if (statsSince == 0L) statsSince = now
            if (now - statsSince >= 5000) {
                Log.i(TAG, "%.1f fps, %d gaps over 50 ms, %d restarts in %.1f s".format(java.util.Locale.US,
                    frameCount * 1000.0 / (now - statsSince), gaps, restarts, (now - statsSince) / 1000.0))
                statsSince = now; frameCount = 0; gaps = 0; restarts = 0
            }
            results[ts] = result
            while (results.size > 3 * keep) results.remove(results.keys.first())
            if (request.tag == STILL_TAG) results[ts] = result.also { stillResult = ts }
            if (request.tag == LONG_TAG) longWanted = ts
            takeLong()
            takeStill()
            watchFocus(result)
        }
        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (request.tag == LONG_TAG) longWanted = 0 // give up on it
            if (request.tag == STILL_TAG) {
                Log.w(TAG, "still RAW failed: reason ${failure.reason}")
                stillFailed("拍摄请求失败（原因 ${failure.reason}）")
            }
        }
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            if (request.tag == STILL_TAG) stillStarted?.invoke()
        }
    }

    // Handler thread: (re)start the repeating request with the current settings.
    private fun repeat() {
        val camera = device ?: return
        val s = session ?: return
        try {
            val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview!!)
            if (metering) b.addTarget(yuvReader!!.surface)
            if (!onDemand) b.addTarget(rawReader!!.surface)
            settings(b)
            s.setRepeatingRequest(b.build(), resultCallback, handler)
            restarts++
        } catch (e: Exception) {
            Log.w(TAG, "repeat", e)
        }
    }

    private fun settings(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE,
            if (focusLocked) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        focusArea?.let { b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
        if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        // RAW frames for the merge: no noise reduction or sharpening they would not show anyway.
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        exposure.fill(b) // exposure, white balance, tone curve, manual focus
    }

    /**
     * Focus at [area] (sensor coordinates): one AF scan there, then continuous AF around
     * it, or with [lock] the focus held. [done] gets whether it focused (null: no answer).
     */
    fun focus(area: MeteringRectangle?, lock: Boolean, done: (Boolean?) -> Unit) {
        focusArea = area
        handler.post {
            focusLocked = false
            focusDone?.invoke(null)
            focusDone = { ok ->
                focusLocked = lock
                repeat() // continuous AF again, or AUTO holding the lens
                done(ok)
            }
            focusStart = android.os.SystemClock.uptimeMillis()
            focusDeadline = focusStart + 2500
            val camera = device ?: return@post
            val s = session ?: return@post
            try {
                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(preview!!)
                settings(b)
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
                // The repeating request keeps AUTO (no trigger) until the scan settles.
                val r = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                r.addTarget(preview!!)
                r.addTarget(yuvReader!!.surface)
                r.addTarget(rawReader!!.surface)
                settings(r)
                r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                s.setRepeatingRequest(r.build(), resultCallback, handler)
            } catch (e: Exception) {
                Log.w(TAG, "focus", e)
                focusDone?.let { focusDone = null; it(null) }
            }
        }
    }

    /** Back to continuous AF (a focus mode change). */
    fun unlockFocus() {
        handler.post {
            focusLocked = false
            focusDone = null
            repeat()
        }
    }

    // Handler thread: a tap's AF scan settled (locked: in focus or not), or timed out.
    private fun watchFocus(result: TotalCaptureResult) {
        val done = focusDone ?: return
        // A lock left from the last tap may still be reported for a frame or two.
        if (android.os.SystemClock.uptimeMillis() - focusStart < 150) return
        val state = result.get(CaptureResult.CONTROL_AF_STATE)
        val ok = when (state) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> true
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> false
            else -> if (android.os.SystemClock.uptimeMillis() > focusDeadline) null else return
        }
        focusDone = null
        done(ok)
    }

    fun zoomTo(ratio: Float) {
        zoom = ratio
        handler.post { repeat() }
    }

    // Handler thread: every new RAW frame joins the ring; the oldest goes back to the camera.
    private fun onRaw(reader: ImageReader) {
        while (true) {
            val image = try {
                reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                null // all buffers held (a burst being written): the rest waits a moment
            } ?: return
            if (onDemand) {
                if (pending != null && stillImage == null) { stillImage = image; takeStill() } else image.close()
                continue
            }
            if (pending != null && image.timestamp == longWanted) {
                longFrame = image
                takeLong()
                continue
            }
            ring.addLast(image)
            while (ring.size > keep) ring.removeFirst().close()
        }
    }

    private var pending: ((Shot?) -> Unit)? = null
    private var pendingTaken: (() -> Unit)? = null
    private var pendingFrames: List<Image> = emptyList()
    private var pendingOrientation = 0

    /**
     * The burst: the [frames] newest ring frames (the newest is the reference) plus, when
     * [long] is given, one frame at that exposure taken now. [done] runs on the handler
     * thread with DNGs written in memory, or null if the frames were not there.
     */
    /** On demand: when the still's exposure starts, and what failed when it does not come. */
    @Volatile var stillStarted: (() -> Unit)? = null
    @Volatile var stillError: String? = null
    private var stillImage: Image? = null
    private var stillResult = -1L

    // Handler thread: the still's frame and its result both in: the shot.
    private fun takeStill() {
        if (!onDemand || pending == null) return
        val image = stillImage ?: return
        if (results[image.timestamp] == null) return
        stillImage = null
        pendingFrames = listOf(image)
        finish(null)
    }

    private fun stillFailed(why: String) {
        val done = pending ?: return
        pending = null
        stillError = why
        stillImage?.close(); stillImage = null
        done(null)
    }

    /** On demand: one STILL_CAPTURE request for a RAW frame, at the exposure the meter holds. */
    private fun shootStill(orientationDegrees: Int, taken: () -> Unit, done: (Shot?) -> Unit) {
        handler.post {
            val camera = device
            val s = session
            if (camera == null || s == null) { stillError = "相机还没准备好（停在：$state）"; return@post done(null) }
            pendingTaken = taken
            pendingOrientation = orientationDegrees
            pending = done
            stillError = null
            stillImage?.close(); stillImage = null
            try {
                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(preview!!)
                b.addTarget(rawReader!!.surface)
                settings(b)
                b.setTag(STILL_TAG)
                s.capture(b.build(), resultCallback, handler)
                val ms = (exposure.live?.timeNs ?: 50_000_000L) / 1_000_000
                handler.postDelayed({ if (pending === done) stillFailed("相机 ${5000 + 3 * ms} ms 内没有交回 RAW") }, 5000 + 3 * ms)
            } catch (e: Exception) {
                Log.w(TAG, "still RAW", e)
                stillFailed("拍摄请求被拒绝：${e.message}")
            }
        }
    }

    fun shoot(frames: Int, long: Exposure?, orientationDegrees: Int, taken: () -> Unit, done: (Shot?) -> Unit) {
        if (onDemand) return shootStill(orientationDegrees, taken, done)
        handler.post {
            pendingTaken = taken
            val taken = ring.toList().takeLast(frames).reversed()
            ring.removeAll(taken.toSet())
            if (taken.isEmpty()) return@post done(null)
            pendingFrames = taken
            pendingOrientation = orientationDegrees
            pending = done
            if (long == null) return@post finish(null)
            longWanted = -1
            longFrame = null
            val camera = device
            val s = session
            if (camera == null || s == null) return@post finish(null)
            try {
                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(preview!!)
                b.addTarget(rawReader!!.surface)
                settings(b)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, long.timeNs)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, long.iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(long.timeNs, 33_333_333L))
                b.setTag(LONG_TAG)
                s.capture(b.build(), resultCallback, handler)
                // Give up on the long frame after a while: the burst still merges.
                handler.postDelayed({ if (pending != null) { longWanted = 0; finish(null) } },
                    600 + 3 * long.timeNs / 1_000_000)
            } catch (e: Exception) {
                Log.w(TAG, "long frame", e)
                finish(null)
            }
        }
    }

    private fun takeLong() {
        if (pending == null || longWanted <= 0) return
        // The frame may have reached the ring before its result named it.
        if (longFrame == null) ring.firstOrNull { it.timestamp == longWanted }?.let { ring.remove(it); longFrame = it }
        val frame = longFrame ?: return
        if (frame.timestamp != longWanted) return
        longFrame = null
        finish(frame)
    }

    // Handler thread: DNGs for the frames (in memory), then report.
    private fun finish(long: Image?) {
        val done = pending ?: return
        pending = null
        val frames = pendingFrames + listOfNotNull(long)
        pendingFrames = emptyList()
        val reference = results[frames.first().timestamp]
        if (reference == null) {
            Log.w(TAG, "no capture result for the reference frame")
            frames.forEach { it.close() }
            return done(null)
        }
        // The frames are secured: the shutter may go again while they are written.
        pendingTaken?.invoke()
        pendingTaken = null
        val orientation = pendingOrientation
        val exposures = FloatArray(frames.size)
        val jobs = frames.mapIndexed { i, f ->
            val result = results[f.timestamp] ?: reference
            val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            exposures[i] = (time.toDouble() * iso).toFloat() // the RAW sees no post-RAW boost
            writers.submit<NativeDng?> {
                val out = NativeDng(rawSize.width.toLong() * rawSize.height * 2 + 512 * 1024)
                try {
                    DngCreator(characteristics, result).use { creator ->
                        creator.setOrientation(exifOrientation(orientation))
                        creator.writeImage(out, f)
                    }
                    out.flush()
                    out
                } catch (e: Exception) {
                    Log.e(TAG, "DNG $i", e)
                    out.release()
                    null
                } finally {
                    f.close() // back to the camera at once
                }
            }
        }
        writers.execute {
            val dngs = jobs.map { runCatching { it.get() }.getOrNull() }
            if (dngs.any { it == null }) {
                dngs.forEach { it?.release() }
                done(null)
            } else done(Shot(dngs.filterNotNull(), exposures, reference, long != null))
        }
    }

    fun close() {
        closed = true
        exposure.onSettings = null
        handler.post {
            try { session?.close() } catch (_: Exception) {}
            device?.close()
            session = null
            device = null
            ring.forEach { runCatching { it.close() } }
            ring.clear()
            pendingFrames.forEach { runCatching { it.close() } }
            longFrame?.close()
            rawReader?.close()
            yuvReader?.close()
            results.clear()
            writers.shutdown()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "ZslCamera"
        /** Frames kept for a burst. */
        const val RING = 6
        const val MAX_RAW_PIXELS = 26_000_000L
        /** Waits before asking again for a camera still held by its last user. */
        private val RETRIES = longArrayOf(300, 700, 1500)
        private const val LONG_TAG = "long"
        private const val STILL_TAG = "still"

        fun exifOrientation(degrees: Int) = when ((degrees % 360 + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

        /** The lens streams full-size RAW next to a preview (the RAW capability). */
        fun supported(characteristics: CameraCharacteristics): Boolean =
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
    }
}
