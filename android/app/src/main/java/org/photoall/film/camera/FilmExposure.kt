package org.photoall.film.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Rational
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Exposure split into sensor time and ISO. */
data class Exposure(val timeNs: Long, val iso: Int) {
    val total: Double get() = timeNs.toDouble() * iso
    fun label(): String = "1/%.0f s ISO %d".format(java.util.Locale.US, 1e9 / timeNs, iso)
}

private const val TAG = "FilmExposure"

/**
 * Exposure and white balance on top of the phone's own 3A. The camera HAL meters at
 * full frame rate on ISP statistics (with its flicker and face handling), so it is
 * the light meter; this class only steers it and applies film rules:
 *  - metering pattern via AE regions, exposure bias via AE compensation,
 *  - highlight protection: a slow loop on the viewfinder histogram nudges the bias,
 *  - film ISO: HAL ISO priority where supported, otherwise the metered exposure is
 *    re-split into film ISO + shutter for the still only (preview stays live),
 *  - fixed colour temperatures from the sensor calibration (deterministic gains and
 *    matrix), auto white balance by the HAL.
 * A known sRGB tone curve is always requested, which the preview LUT and meter assume.
 */
/** Camera characteristics, from CameraX (Camera2CameraInfo) or straight from Camera2. */
interface Characteristics {
    fun <T> get(key: CameraCharacteristics.Key<T>): T?
}

/** Where request settings go: a CameraX options builder or a Camera2 request builder. */
interface RequestSink {
    fun <T : Any> put(key: CaptureRequest.Key<T>, value: T): RequestSink
}

@OptIn(ExperimentalCamera2Interop::class)
class FilmExposure(
    private val chars: Characteristics,
    private val calibration: Calibration?,
    private val apply: (CaptureRequestOptions) -> Unit,
) {
    /**
     * A zero-lag burst session runs the sensor this many stops under the meter (the
     * viewfinder shows it brighter): the RAW frames keep their highlights.
     */
    var under = 0.0
    /** Set while a Camera2 session (not CameraX) owns the camera: it takes the settings. */
    var onSettings: (() -> Unit)? = null

    // ----- settings, written by the activity, then push() -----------------------
    /** Film rules on: metering pattern, highlight protection, film ISO, calibrated WB. */
    var filmRules = false
    /** 0 the phone's matrix metering, 1 centre-weighted, 2 spot at the focus point. */
    var metering = 0
    var focusX = 0.5
    var focusY = 0.5
    /** Upright (display) -> sensor rotation, for mapping the focus point onto the sensor. */
    var sensorRotation = 90
    var highlightProtect = false
    /** 0 50 Hz, 1 60 Hz, 2 off. */
    var antibanding = 0
    var evStops = 0.0
    /** Film box speed to expose at, 0 for the HAL's own ISO choice. */
    var filmIso = 0
    /** Manual exposure overrides all metering; null = auto. */
    var manual: Exposure? = null
    /** Fixed colour temperature; null = auto white balance. */
    var fixedKelvin: Double? = null
    /** HAL white balance preset when film rules are off (CONTROL_AWB_MODE_*). */
    var awbPreset = CaptureRequest.CONTROL_AWB_MODE_AUTO
    /** Manual focus distance in diopters; null = autofocus. */
    var focusDiopters: Float? = null

    // ----- live state from capture results ---------------------------------------
    @Volatile var live: Exposure? = null
        private set
    @Volatile var onLive: (Exposure) -> Unit = {}
    /** The HAL's digital gain after the raw (100 = none) and AE mode / state, from the last result. */
    @Volatile var liveBoost = 100
        private set
    @Volatile var liveAeMode = -1
        private set
    @Volatile var liveAeState = -1
        private set
    /** Sensor ISO (without digital boost), as-shot neutral and noise profile of the last result. */
    @Volatile var liveSensorIso = 0
        private set
    @Volatile private var liveNeutral: FloatArray? = null
    @Volatile private var liveNoise: Pair<Double, Double>? = null
    // More of the last result, for the photo's record.
    @Volatile private var liveGains: FloatArray? = null
    @Volatile private var liveCcm: FloatArray? = null
    @Volatile private var liveFocus: Float? = null
    @Volatile private var liveAfState = -1
    @Volatile private var liveAwbMode = -1
    @Volatile private var liveAwbState = -1
    @Volatile private var liveFrameNs = 0L
    @Volatile private var liveAperture: Float? = null
    @Volatile private var liveFocal: Float? = null

    /**
     * What a DNG of a still at [still] (null: as metered) should carry: the live as-shot
     * neutral and the noise profile, scaled to that ISO (shot noise with the gain, read
     * noise with its square). CameraX's DNG headers repeat one old capture result.
     */
    fun stillMetadata(still: Exposure?): Triple<FloatArray?, Float, Float> {
        val noise = liveNoise
        val base = liveSensorIso
        if (noise == null || base <= 0) return Triple(liveNeutral, 0f, 0f)
        val k = (still?.iso ?: base).toDouble() / base
        return Triple(liveNeutral, (noise.first * k).toFloat(), (noise.second * k * k).toFloat())
    }

    /** The exposure currently frozen on the sensor (a still or burst in progress), or null. */
    val held: Exposure? get() = hold

    private val activeArray: Rect? = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    private val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private val timeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    private val compensationRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    private val compensationStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        ?.toDouble()?.takeIf { it > 0 } ?: (1.0 / 3)
    private val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    private val boostRange = chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
    /** The lens has a flash unit (its LED serves the flash frame as a torch at full strength). */
    val hasFlash: Boolean = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    /**
     * The flash fired by the LED lit steadily for the frame (as with manual exposure) instead
     * of the HAL's metered flash: for phones that do not offer that mode, or offer it and do
     * not fire (seen on some MediaTek phones). Set from the start when the mode is missing,
     * and by the camera once a flash shot comes back unfired ([onFlashMissed]).
     */
    var flashByTorch: Boolean = hasFlash && chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        ?.contains(CameraCharacteristics.CONTROL_AE_MODE_ON_ALWAYS_FLASH) != true
    /** A still asked for the metered flash and the HAL reports it did not fire (camera thread). */
    @Volatile var onFlashMissed: ((flashState: Int) -> Unit)? = null
    private val torchMaxLevel: Int = if (Build.VERSION.SDK_INT >= 35)
        chars.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1 else 1
    /** The next still fires the flash (AE meters with a pre-flash); single-frame flash. */
    var flashShot = false
        private set

    fun armFlash(on: Boolean) {
        android.util.Log.i(TAG, "flash armed $on (${describe()})")
        flashShot = on; push()
    }

    /** The live as-shot neutral (camera rgb, g = 1): the room light the camera balanced for. */
    val ambientNeutral: FloatArray? get() = liveNeutral

    /** Camera rgb of a neutral under daylight at [kelvin] (a white LED flash), from the calibration. */
    fun flashNeutral(kelvin: Double = 5500.0): FloatArray? {
        val cal = calibration?.takeIf { it.valid } ?: return null
        val (r, b) = cal.pointAt(kelvin)
        return if (r > 0 && b > 0) floatArrayOf(r.toFloat(), 1f, b.toFloat()) else null
    }

    /** The flash LED is lit (for a flash frame); only together with a hold. */
    var torch = false
        private set
    /**
     * Steady fill light: the flash LED lit as a torch all the time, at this share of its
     * strength (the strength only where the phone lets it be set). Null: off.
     */
    var steadyLight: Float? = null
    /** Android 16 AE priority: the HAL keeps our ISO and meters with the shutter. */
    val isoPriority: Boolean = Build.VERSION.SDK_INT >= 36 &&
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
            ?.contains(CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY) == true

    private var highlightBias = 0.0  // film rules: stops taken off for clipped highlights
    private var lastHighlightStep = 0L
    private var hold: Exposure? = null
    private var frames = 0

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
            val e = Exposure(time, iso * boost / 100)
            live = e
            liveBoost = boost
            liveSensorIso = iso
            result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.takeIf { it.size == 3 }?.let { n ->
                liveNeutral = FloatArray(3) { n[it].toFloat() }
            }
            result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.takeIf { it.isNotEmpty() }?.let { p ->
                liveNoise = p.sumOf { it.first } / p.size to p.sumOf { it.second } / p.size
            }
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { liveGains = floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) }
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
                liveCcm = FloatArray(9) { t.getElement(it % 3, it / 3).toFloat() }
            }
            liveFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            liveAfState = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
            liveAwbMode = result.get(CaptureResult.CONTROL_AWB_MODE) ?: -1
            liveAwbState = result.get(CaptureResult.CONTROL_AWB_STATE) ?: -1
            liveFrameNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
            liveAperture = result.get(CaptureResult.LENS_APERTURE)
            liveFocal = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            val aeMode = result.get(CaptureResult.CONTROL_AE_MODE) ?: -1
            if (aeMode != liveAeMode) android.util.Log.i(TAG, "AE mode $liveAeMode -> $aeMode (${describe()})")
            liveAeMode = aeMode
            liveAeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: -1
            // Still captures pass through this session callback too: log what each really got.
            if (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                // the metered flash asked for and not given: the phone's flash needs the LED way
                if (request.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH) {
                    val state = result.get(CaptureResult.FLASH_STATE)
                    if (state != null && state != CaptureResult.FLASH_STATE_FIRED && state != CaptureResult.FLASH_STATE_PARTIAL)
                        onFlashMissed?.invoke(state)
                }
                val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                android.util.Log.i(TAG, "still result frame ${result.frameNumber}: ${e.label()} (sensor ISO $iso boost $boost)" +
                    " ae mode $aeMode requested ${request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)?.let { "1/%.0f".format(java.util.Locale.US, 1e9 / it) } ?: "-"}" +
                    " gains ${gains?.let { "%.2f %.2f %.2f".format(java.util.Locale.US, it.red, it.greenEven, it.blue) } ?: "-"}" +
                    " neutral ${neutral?.joinToString(" ") { "%.3f".format(java.util.Locale.US, it.toDouble()) } ?: "-"}")
            }
            if (frames++ % 6 == 0) onLive(e)
        }
    }

    fun push() {
        val direct = onSettings
        if (direct != null) direct() else apply(options())
    }

    /**
     * Viewfinder highlight statistics (fraction of near-white samples). With highlight
     * protection on, take a third of a stop off while >1.5% clips, give it back
     * slowly once <0.3% does; at most one step per 300 ms so the HAL can settle.
     */
    fun onPreviewHighlights(clipped: Float) {
        if (!filmRules || !highlightProtect || manual != null || hold != null) {
            if (highlightBias != 0.0 && (!filmRules || !highlightProtect)) { highlightBias = 0.0; push() }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHighlightStep < 300) return
        val next = when {
            clipped > 0.015f -> max(-2.0, highlightBias - 1.0 / 3)
            clipped < 0.003f && highlightBias < 0 -> min(0.0, highlightBias + 1.0 / 6)
            else -> highlightBias
        }
        if (next != highlightBias) {
            highlightBias = next
            lastHighlightStep = now
            push()
        }
    }

    /**
     * Exposure for the next still, or null to shoot as metered. [underStops] darkens
     * an HDR burst; without HAL ISO priority the film ISO is applied here, keeping the
     * metered total so the frame matches the viewfinder.
     */
    fun stillExposure(underStops: Double, raw: Boolean = false): Exposure? {
        val metered = manual ?: live ?: return null
        val needsIso = filmRules && filmIso > 0 && !isoPriority && manual == null
        // The HAL may meter with low sensor ISO plus digital gain after the raw: the preview
        // looks right but a RAW would come out that much darker. Hold the metered total with
        // the gain moved into real ISO instead.
        val boosted = raw && manual == null && liveBoost > 100
        if (underStops <= 0 && !needsIso && !boosted) return null
        val total = metered.total * 2.0.pow(-underStops)
        var iso = if (needsIso) filmIso else metered.iso
        var time = total / iso
        val longest = max(metered.timeNs.toDouble(), 66_666_666.0) // never slower than the meter chose, or 1/15 s
        if (time > longest) { time = longest; iso = (total / time).roundToInt() }
        time = max(time, (timeRange?.lower ?: 10_000L).toDouble())
        iso = iso.coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 6400)
        return Exposure(time.toLong(), iso)
    }

    /**
     * The longer frame of an HDR bracket: [stops] more light than [base] (the burst's
     * short exposure). Time grows up to 1/15 s (or the base's own time if longer) for
     * hand-held sharpness; the rest goes to ISO. The merge measures the actual ratio.
     */
    fun longExposure(base: Exposure, stops: Double): Exposure {
        val total = base.total * 2.0.pow(stops)
        val longest = max(base.timeNs.toDouble(), 66_666_666.0)
        val time = min(total / base.iso, longest).coerceAtMost((timeRange?.upper ?: Long.MAX_VALUE).toDouble())
        val iso = (total / time).roundToInt().coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 6400)
        return Exposure(time.toLong(), iso)
    }

    /** Freezes [e] on the sensor (for a still or a burst), then [release]s back to metering. */
    fun hold(e: Exposure) {
        android.util.Log.i(TAG, "hold ${e.label()} (${describe()})")
        hold = e; push()
    }
    fun release() {
        android.util.Log.i(TAG, "release (${describe()})")
        hold = null; torch = false; flashShot = false; push()
    }

    /** Lights or puts out the flash LED for the next frames of a held exposure. */
    fun lightTorch(on: Boolean) {
        android.util.Log.i(TAG, "torch $on (${describe()})")
        torch = on; push()
    }

    /**
     * Per camera channel, what turns the flash's light into the ambient white: the live
     * as-shot neutral over the calibrated neutral of daylight at [flashKelvin] (a white
     * LED flash). Null without a calibration or a live neutral.
     */
    fun flashWhite(flashKelvin: Double = 5500.0): FloatArray? {
        val cal = calibration?.takeIf { it.valid } ?: return null
        val ambient = liveNeutral ?: return null
        val (r, b) = cal.pointAt(flashKelvin)
        if (r <= 0 || b <= 0) return null
        return floatArrayOf((ambient[0] / ambient[1] / r).toFloat(), 1f, (ambient[2] / ambient[1] / b).toFloat())
    }

    /** The live camera state and the exposure rules, into a photo's record. */
    fun record(log: org.photoall.film.gallery.ShotLog) {
        val e = live
        val ex = "曝光（拍摄瞬间的实时测光）"
        log.put(ex, "实时曝光", e?.label())
        log.put(ex, "传感器 ISO / 数字增益", "$liveSensorIso / ${liveBoost / 100.0}×")
        log.put(ex, "总曝光量（秒·ISO）", e?.let { "%.4f".format(java.util.Locale.US, it.total / 1e9) })
        log.put(ex, "帧时长", if (liveFrameNs > 0) "%.1f ms".format(java.util.Locale.US, liveFrameNs / 1e6) else null)
        log.put(ex, "自动曝光模式 / 状态", "${aeModeName(liveAeMode)} / ${aeStateName(liveAeState)}")
        log.put(ex, "曝光补偿", "%+.1f EV".format(java.util.Locale.US, evStops))
        log.put(ex, "胶片测光规则", filmRules)
        log.put(ex, "测光模式", listOf("手机智能测光", "中央重点", "点测光").getOrElse(metering) { "$metering" })
        log.put(ex, "高光保护 / 当前压暗", "${if (highlightProtect) "开" else "关"} / %+.2f EV".format(java.util.Locale.US, highlightBias))
        log.put(ex, "按胶片 ISO", if (filmIso > 0) "ISO $filmIso${if (isoPriority) "（HAL ISO 优先）" else "（重新分配快门/ISO）"}" else "关")
        log.put(ex, "防频闪", listOf("50 Hz", "60 Hz", "关").getOrElse(antibanding) { "$antibanding" })
        log.put(ex, "手动曝光", manual?.label() ?: "关")
        log.put(ex, "锁定中的曝光", hold?.label() ?: "无")
        log.put(ex, "传感器 ISO 范围 / 快门范围", "${isoRange?.lower}–${isoRange?.upper} / " +
            "${timeRange?.let { "1/%.0f – %.2f s".format(java.util.Locale.US, 1e9 / it.lower, it.upper / 1e9) }}")
        val wb = "白平衡（拍摄瞬间）"
        log.put(wb, "自动白平衡模式 / 状态", "${awbModeName(liveAwbMode)} / ${awbStateName(liveAwbState)}")
        log.put(wb, "实时中性点 (R G B)", liveNeutral)
        log.put(wb, "实时增益 (R Gr Gb B)", liveGains)
        log.put(wb, "实时增益折算色温参考", liveNeutral?.let { n -> "R/G %.3f  B/G %.3f".format(java.util.Locale.US, n[0] / n[1], n[2] / n[1]) })
        log.put(wb, "色彩校正矩阵", liveCcm)
        log.put(wb, "固定色温", fixedKelvin?.let { "%.0f K".format(java.util.Locale.US, it) } ?: "无（自动）")
        log.put(wb, "HAL 白平衡预设", awbModeName(awbPreset))
        log.put(wb, "传感器标定", if (calibration?.valid == true) "可用" else "不可用")
        log.put(wb, "闪光（5500K）中性点", flashNeutral())
        log.put(wb, "噪声模型（实时 s o）", liveNoise?.let { "%.4g %.4g".format(java.util.Locale.US, it.first, it.second) })
        val af = "对焦"
        log.put(af, "对焦距离", liveFocus?.let { if (it > 0) "%.2f 屈光度（约 %.2f m）".format(java.util.Locale.US, it, 1 / it) else "无穷远" })
        log.put(af, "对焦状态", afStateName(liveAfState))
        log.put(af, "手动对焦", focusDiopters?.let { "%.2f 屈光度".format(java.util.Locale.US, it) } ?: "关（自动对焦）")
        log.put(af, "对焦点（取景框比例）", "%.2f, %.2f".format(java.util.Locale.US, focusX, focusY))
        val lens = "相机与镜头"
        log.put(lens, "实时光圈 / 焦距", "${liveAperture?.let { "f/%.2f".format(java.util.Locale.US, it) }} / ${liveFocal?.let { "%.2f mm".format(java.util.Locale.US, it) }}")
        log.put(lens, "闪光灯 / 手电最高档", "${if (hasFlash) "有" else "无"} / $torchMaxLevel")
        log.put(lens, "传感器方向（相对取景）", "$sensorRotation°")
    }

    private fun aeModeName(m: Int) = when (m) {
        0 -> "关（手动）"; 1 -> "开"; 2 -> "开·自动闪光"; 3 -> "开·强制闪光"; 4 -> "开·防红眼"; 5 -> "开·外接闪光"; else -> "$m"
    }
    private fun aeStateName(s: Int) = when (s) {
        0 -> "未启动"; 1 -> "搜索"; 2 -> "已收敛"; 3 -> "锁定"; 4 -> "需要闪光"; 5 -> "预闪中"; else -> "$s"
    }
    private fun awbModeName(m: Int) = when (m) {
        0 -> "关（手动）"; 1 -> "自动"; 2 -> "白炽灯"; 3 -> "荧光灯"; 4 -> "暖荧光灯"; 5 -> "日光"; 6 -> "阴天"; 7 -> "黄昏"; 8 -> "阴影"; else -> "$m"
    }
    private fun awbStateName(s: Int) = when (s) { 0 -> "未启动"; 1 -> "搜索"; 2 -> "已收敛"; 3 -> "锁定"; else -> "$s" }
    private fun afStateName(s: Int) = when (s) {
        0 -> "未启动"; 1 -> "被动扫描"; 2 -> "被动对焦完成"; 3 -> "主动扫描"; 4 -> "对焦锁定"; 5 -> "未能对焦锁定"; 6 -> "被动未对焦"; else -> "$s"
    }

    /** Live state for the log: exposure, digital boost, AE mode and state, any hold. */
    fun describe(): String = "live ${live?.label() ?: "-"} boost $liveBoost ae mode $liveAeMode state $liveAeState" +
        " neutral ${liveNeutral?.joinToString(" ") { "%.3f".format(java.util.Locale.US, it) } ?: "-"}" +
        " noise ${liveNoise?.let { "%.3g %.3g".format(java.util.Locale.US, it.first, it.second) } ?: "-"}" +
        (hold?.let { " held ${it.label()}" } ?: "")

    fun options(): CaptureRequestOptions {
        val b = CaptureRequestOptions.Builder()
        fill(object : RequestSink {
            override fun <T : Any> put(key: CaptureRequest.Key<T>, value: T): RequestSink {
                b.setCaptureRequestOption(key, value)
                return this
            }
        })
        return b.build()
    }

    /** The settings for a Camera2 request builder (a zero-lag session). */
    fun fill(builder: CaptureRequest.Builder) = fill(object : RequestSink {
        override fun <T : Any> put(key: CaptureRequest.Key<T>, value: T): RequestSink {
            builder.set(key, value)
            return this
        }
    })

    /**
     * The camera driver restarted on a RAW shot with the lens shading map and the preset
     * tone curve asked for (an OPPO with a MediaTek chip): left to the driver's defaults.
     * The DNG then has no shading map (the vignetting stays) and the viewfinder the camera's
     * own curve.
     */
    var plain = false

    private fun fill(o: RequestSink) {
        if (!plain) o.put(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            .put(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
            .put(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)

        val fixed = hold ?: manual
        if (fixed != null) {
            o.put(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .put(CaptureRequest.SENSOR_SENSITIVITY, fixed.iso)
                .put(CaptureRequest.SENSOR_EXPOSURE_TIME, fixed.timeNs)
                .put(CaptureRequest.SENSOR_FRAME_DURATION, max(fixed.timeNs, 33_333_333L))
            // No digital gain after the raw: the sensor gets the whole requested exposure.
            boostRange?.let { o.put(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it.lower) }
        } else {
            // A flash shot lets the HAL fire the flash and meter it (pre-flash); CameraX runs
            // the precapture sequence for it.
            o.put(CaptureRequest.CONTROL_AE_MODE,
                if (flashShot && hasFlash) CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH else CaptureRequest.CONTROL_AE_MODE_ON)
            val bias = evStops - under + if (filmRules) highlightBias else 0.0
            val range = compensationRange
            val index = (bias / compensationStep).roundToInt().let { if (range != null) it.coerceIn(range.lower, range.upper) else it }
            o.put(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
            o.put(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, when (antibanding) {
                0 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                1 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            })
            if (filmRules) {
                meteringRegion()?.let { o.put(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
                if (filmIso > 0 && isoPriority && Build.VERSION.SDK_INT >= 36) {
                    o.put(CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                        CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
                        .put(CaptureRequest.SENSOR_SENSITIVITY,
                            filmIso.coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 6400))
                }
            }
        }

        // The LED as a torch: full strength for a flash frame, else the steady fill light.
        val level = when {
            !hasFlash -> null
            torch -> 1f
            flashShot -> null
            else -> steadyLight
        }
        if (level != null) {
            o.put(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            if (Build.VERSION.SDK_INT >= 35 && torchMaxLevel > 1)
                o.put(CaptureRequest.FLASH_STRENGTH_LEVEL,
                    (level * torchMaxLevel).roundToInt().coerceIn(1, torchMaxLevel))
        }

        val kelvin = fixedKelvin
        val cal = calibration
        if (filmRules && kelvin != null && cal != null && cal.valid) {
            // Calibrated, repeatable white balance: gains from the sensor's CT curve and
            // the matching camera -> sRGB matrix, instead of the HAL's preset guess.
            val (r, b) = cal.pointAt(kelvin)
            val ccm = cal.cameraToSrgb(kelvin)
            o.put(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                .put(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                .put(CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(Calibration.clampGain(1 / r).toFloat(), 1f, 1f, Calibration.clampGain(1 / b).toFloat()))
                .put(CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    ColorSpaceTransform(Array(9) { Rational((ccm[it] * 10000).roundToInt(), 10000) }))
        } else o.put(CaptureRequest.CONTROL_AWB_MODE, if (filmRules) CaptureRequest.CONTROL_AWB_MODE_AUTO else awbPreset)

        focusDiopters?.let {
            o.put(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .put(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
    }

    /** A square on the sensor around viewfinder point (u, v), 0..1 each: tap-to-focus areas. */
    fun regionAt(u: Double, v: Double, half: Double = 0.06): MeteringRectangle? {
        val area = activeArray ?: return null
        val (cx, cy) = toSensor(u, v)
        val x0 = ((cx - half).coerceIn(0.0, 1.0) * area.width()).toInt()
        val y0 = ((cy - half).coerceIn(0.0, 1.0) * area.height()).toInt()
        val x1 = ((cx + half).coerceIn(0.0, 1.0) * area.width()).toInt()
        val y1 = ((cy + half).coerceIn(0.0, 1.0) * area.height()).toInt()
        return MeteringRectangle(x0, y0, max(1, x1 - x0), max(1, y1 - y0), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    /** Centre-weighted / spot rectangle on the sensor; null leaves the phone's matrix metering. */
    private fun meteringRegion(): MeteringRectangle? {
        val area = activeArray ?: return null
        if (maxAeRegions < 1 || metering == 0) return null
        val (cx, cy) = if (metering == 2) toSensor(focusX, focusY) else 0.5 to 0.5
        val half = if (metering == 2) 0.06 else 0.22
        val x0 = ((cx - half).coerceIn(0.0, 1.0) * area.width()).toInt()
        val y0 = ((cy - half).coerceIn(0.0, 1.0) * area.height()).toInt()
        val x1 = ((cx + half).coerceIn(0.0, 1.0) * area.width()).toInt()
        val y1 = ((cy + half).coerceIn(0.0, 1.0) * area.height()).toInt()
        return MeteringRectangle(x0, y0, max(1, x1 - x0), max(1, y1 - y0), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    // Upright viewfinder (u right, v down) -> sensor coordinates; regions follow the zoomed field of view.
    private fun toSensor(u: Double, v: Double): Pair<Double, Double> = when (sensorRotation) {
        90 -> v to 1 - u
        180 -> 1 - u to 1 - v
        270 -> 1 - v to u
        else -> u to v
    }
}
