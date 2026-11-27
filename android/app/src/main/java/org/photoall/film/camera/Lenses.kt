package org.photoall.film.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.roundToInt

/**
 * The phone's lenses as both cameras see them (the film camera and 拼贴相机): which cameras of a
 * facing apps can open, every real lens as a magnification of the main camera, their 35 mm
 * focal lengths. One reading of the hardware, so the two cameras offer the same lenses.
 */
@OptIn(ExperimentalCamera2Interop::class)
object Lenses {
    /** Cameras of the current facing that apps can open. */
    fun lenses(provider: ProcessCameraProvider, front: Boolean): List<CameraInfo> {
        val facing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        return provider.availableCameraInfos.filter { it.lensFacing == facing }.sortedBy { equivalentMm(it) }
    }

    /*
     * Lenses and RAW: one set of rules.
     *
     *  1. RAW comes only from a lens opened by its own id, so the picture is surely that
     *     lens's sensor: the main camera at 1x, a single listed lens at 1x, or a lens the
     *     system does not list but opens (LensProbe; Camera2, the zero-lag session).
     *  2. Every other focal length (the logical camera's zoom stops, its pinned members, a
     *     zoom between lenses, a pinch or the ruler past 1.8x) is JPEG: the phone picks the
     *     lens itself. A physical lens pinned through the logical camera is never trusted for
     *     RAW (the Galaxy S23 Ultra handed the main sensor's data under a telephoto's name).
     *  3. A lens that will not open (listed or found) is put aside (lens_dead_<id>, said once):
     *     its focal length goes back to rule 2. A listed one is given two tries, a found one one.
     *  4. Driver failures step down per lens, each told: (Xiaomi) the system-camera identity off,
     *     then plain streams, then the plainest requests, then JPEG for that lens.
     */

    /**
     * A lens the user can pick: which camera to bind and the zoom ratio that selects it.
     * [hiddenId]: a lens the system does not list (LensProbe), opened by Camera2 for RAW; the
     * rest of the option is the JPEG way to the same focal length when it is not used.
     */
    class LensOption(val camera: CameraInfo, val zoom: Float, val ratio: Float, val physicalId: String? = null,
                             val hiddenId: String? = null)

    /**
     * Every real lens, as a magnification of the main camera. Phones like the Galaxy
     * S23 Ultra hide their 3x and 10x telephotos inside one logical camera that
     * switches lens by zoom ratio; their physical cameras give the real focal lengths.
     * Phones that expose each lens as its own camera are listed camera by camera.
     */
    fun lensOptions(context: Context, provider: ProcessCameraProvider, front: Boolean, dead: Set<String>,
                    probe: Boolean = true): List<LensOption> {
        val cameras = lenses(provider, front)
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
                    options += LensOption(camera, z, snapRatio(z * scale), runCatching { Camera2CameraInfo.from(lens).cameraId }.getOrNull())
                }
                options += LensOption(camera, 1f, snapRatio(scale))
            } else {
                val ratio = snapRatio(equivalentMm(camera) / reference)
                if (camera !== main && dead.contains(Camera2CameraInfo.from(camera).cameraId)) {
                    // a lens this phone would not open: the same focal length by the main camera's zoom
                    val range = main.zoomState.value
                    val z = range?.let { ratio.coerceIn(it.minZoomRatio, it.maxZoomRatio) } ?: continue
                    if (Math.abs(z / ratio - 1f) < 0.1f) options += LensOption(main, z, ratio)
                } else options += LensOption(camera, 1f, ratio)
            }
        }
        // One button per magnification; prefer the logical camera (zooms smoothly between lenses).
        val unique = options.sortedBy { it.ratio }.fold(mutableListOf<LensOption>()) { acc, o ->
            val last = acc.lastOrNull()
            if (last != null && Math.abs(last.ratio / o.ratio - 1) < 0.08f) {
                val more = o.camera.physicalCameraInfosOrEmpty().size - last.camera.physicalCameraInfosOrEmpty().size
                // the logical camera first; on it, the main camera's own stop over a lens at the same ratio
                if (more > 0 || (more == 0 && o.camera === last.camera && o.physicalId == null && last.physicalId != null))
                    acc[acc.lastIndex] = o
            } else acc += o
            acc
        }
        // The usual stops up to the main camera's reach where no lens is listed: many phones keep
        // their telephoto out of the lists apps see, yet switch to it by zoom ratio.
        // Only when no telephoto is listed at all: a phone that lists its lenses keeps just them.
        val max = main.zoomState.value?.maxZoomRatio ?: 1f
        // lenses the system does not list but describes (rule 1): asked for once per system build
        val listedMm = unique.map { it.ratio * reference }
        val listedIds = cameras.flatMap { c -> listOf(Camera2CameraInfo.from(c).cameraId) +
            c.physicalCameraInfosOrEmpty().mapNotNull { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() } }.toSet()
        val hidden = if (front || !probe) emptyList() else runCatching { LensProbe.find(context, listedIds, listedMm, reference) }.onFailure { android.util.Log.w("Lenses", "lens probe", it) }
            .getOrDefault(emptyList()).filter { it.id !in dead }
        if (unique.none { it.ratio > 1.5f })
            for (r in listOf(2f, 3f, 5f, 10f))
                if (r <= max && unique.none { Math.abs(it.ratio / r - 1) < 0.15f }) unique += LensOption(main, r, r)
        val range = main.zoomState.value
        for (h in hidden) {
            val r = snapRatio(h.mm / reference)
            val at = unique.indexOfFirst { it.physicalId == null && it.hiddenId == null && it.camera === main && Math.abs(it.ratio / r - 1) < 0.1f }
            // its JPEG way: the zoom stop already there, or the main camera's zoom to it
            if (at >= 0) unique[at] = unique[at].let { LensOption(it.camera, it.zoom, r, null, h.id) }
            else unique += LensOption(main, range?.let { r.coerceIn(it.minZoomRatio, it.maxZoomRatio) } ?: 1f, r, null, h.id)
        }
        unique.sortBy { it.ratio }
        return unique
    }

    /** Snap to the magnifications phones print on their lenses (0.6x, 3x, 10x...). */
    fun snapRatio(r: Float): Float {
        val nice = floatArrayOf(0.5f, 0.6f, 0.7f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f, 5f, 6f, 10f)
        val near = nice.minByOrNull { Math.abs(it / r - 1) } ?: r
        return if (Math.abs(near / r - 1) < 0.1f) near else (r * 10).roundToInt() / 10f
    }

    /** The main camera: a logical one spanning lenses, else the highest resolution sensor. */
    fun mainCamera(cameras: List<CameraInfo>): CameraInfo? =
        cameras.firstOrNull { it.physicalCameraInfosOrEmpty().size > 1 } ?: cameras.maxByOrNull { info ->
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { it.width().toLong() * it.height() } ?: 0L
        }

    /** 35 mm equivalent focal length from the physical sensor diagonal. */
    fun equivalentMm(info: CameraInfo): Float {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val sensor = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return focal
        val diagonal = Math.hypot(sensor.width.toDouble(), sensor.height.toDouble())
        return (focal * 43.27 / diagonal).toFloat()
    }
}

/** A camera's physical lenses (a logical camera's members), or none. */
fun CameraInfo.physicalCameraInfosOrEmpty(): Set<CameraInfo> =
    runCatching { physicalCameraInfos }.getOrDefault(emptySet())
