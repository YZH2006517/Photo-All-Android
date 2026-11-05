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
}

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
@OptIn(ExperimentalCamera2Interop::class)
class FilmExposure(
    private val chars: Camera2CameraInfo,
    private val calibration: Calibration?,
    private val apply: (CaptureRequestOptions) -> Unit,
) {
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

    private val activeArray: Rect? = chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    private val isoRange = chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private val timeRange = chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    private val compensationRange = chars.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    private val compensationStep = chars.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        ?.toDouble()?.takeIf { it > 0 } ?: (1.0 / 3)
    private val maxAeRegions = chars.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    /** Android 16 AE priority: the HAL keeps our ISO and meters with the shutter. */
    val isoPriority: Boolean = Build.VERSION.SDK_INT >= 36 &&
        chars.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
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
            if (frames++ % 6 == 0) onLive(e)
        }
    }

    fun push() = apply(options())

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
    fun stillExposure(underStops: Double): Exposure? {
        val metered = manual ?: live ?: return null
        val needsIso = filmRules && filmIso > 0 && !isoPriority && manual == null
        if (underStops <= 0 && !needsIso) return null
        val total = metered.total * 2.0.pow(-underStops)
        var iso = if (needsIso) filmIso else metered.iso
        var time = total / iso
        val longest = max(metered.timeNs.toDouble(), 66_666_666.0) // never slower than the meter chose, or 1/15 s
        if (time > longest) { time = longest; iso = (total / time).roundToInt() }
        time = max(time, (timeRange?.lower ?: 10_000L).toDouble())
        iso = iso.coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 6400)
        return Exposure(time.toLong(), iso)
    }

    /** Freezes [e] on the sensor (for a still or a burst), then [release]s back to metering. */
    fun hold(e: Exposure) { hold = e; push() }
    fun release() { hold = null; push() }

    fun options(): CaptureRequestOptions {
        val o = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            .setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
            .setCaptureRequestOption(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)

        val fixed = hold ?: manual
        if (fixed != null) {
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, fixed.iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, fixed.timeNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(fixed.timeNs, 33_333_333L))
        } else {
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val bias = evStops + if (filmRules) highlightBias else 0.0
            val range = compensationRange
            val index = (bias / compensationStep).roundToInt().let { if (range != null) it.coerceIn(range.lower, range.upper) else it }
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, when (antibanding) {
                0 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                1 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            })
            if (filmRules) {
                meteringRegion()?.let { o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
                if (filmIso > 0 && isoPriority && Build.VERSION.SDK_INT >= 36) {
                    o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                        CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,
                            filmIso.coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 6400))
                }
            }
        }

        val kelvin = fixedKelvin
        val cal = calibration
        if (filmRules && kelvin != null && cal != null && cal.valid) {
            // Calibrated, repeatable white balance: gains from the sensor's CT curve and
            // the matching camera -> sRGB matrix, instead of the HAL's preset guess.
            val (r, b) = cal.pointAt(kelvin)
            val ccm = cal.cameraToSrgb(kelvin)
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(Calibration.clampGain(1 / r).toFloat(), 1f, 1f, Calibration.clampGain(1 / b).toFloat()))
                .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    ColorSpaceTransform(Array(9) { Rational((ccm[it] * 10000).roundToInt(), 10000) }))
        } else o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, if (filmRules) CaptureRequest.CONTROL_AWB_MODE_AUTO else awbPreset)

        focusDiopters?.let {
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        return o.build()
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
