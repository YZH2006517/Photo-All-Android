package org.photoall.film.engine

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.ColorSpace

/** Camera-side options for one development. */
/**
 * A DNG written into native memory (outside the Java heap, which a burst of 25 MB frames
 * would exhaust). Pass [handle] (a Long) to developDng; [release] frees it.
 */
class NativeDng(capacity: Long) : java.io.OutputStream() {
    val handle: Long = FilmNative.bufferCreate(capacity)
    private val chunk = ByteArray(256 * 1024)
    private var filled = 0
    private var released = false

    override fun write(b: Int) {
        if (filled == chunk.size) flush()
        chunk[filled++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len >= chunk.size) {
            flush()
            FilmNative.bufferWrite(handle, b, off, len)
            return
        }
        if (filled + len > chunk.size) flush()
        System.arraycopy(b, off, chunk, filled, len)
        filled += len
    }

    override fun flush() {
        if (filled > 0) FilmNative.bufferWrite(handle, chunk, 0, filled)
        filled = 0
    }

    override fun close() = flush()

    fun save(file: java.io.File): Boolean = FilmNative.bufferSave(handle, file.path)

    fun release() {
        if (released) return
        released = true
        FilmNative.bufferRelease(handle)
    }
}

data class DevelopOptions(
    /** Working resolution; larger sources are area-resampled first. */
    val maxLongEdge: Int = 4096,
    /** Exposure compensation in stops, applied in scene-linear light before the film. */
    val exposureEv: Float = 0f,
    /** Grain and glare seed. Keep it with the frame to redevelop identically. */
    val seed: Int = 1,
    val displayP3: Boolean = true,
    /**
     * RAW only: the viewfinder's centre-weighted log-average linear luminance at the
     * shot. The develop scales the raw to it so the result matches the preview's
     * brightness; [exposureEv] is then an offset. 0 disables.
     */
    val matchKey: Float = 0f,
    /** RAW only: local tone compression before the film, 0..1 (shadows up, highlights down). */
    val localTone: Float = 0f,
    /** RAW only: chroma noise reduction before the film, 0..1. */
    val chromaDenoise: Float = 0f,
    /** RAW only: vkdt's wavelet denoise on the mosaic, from the sensor noise model, 0..1. */
    val rawDenoise: Float = 0f,
    /**
     * RAW only: the camera's as-shot neutral (camera rgb, like DNG AsShotNeutral) and noise
     * model (variance = noiseScale x + noiseOffset) at the shot, replacing the DNG's own
     * (CameraX can write stale ones). Null / 0 keeps the file's.
     */
    val neutral: FloatArray? = null,
    val noiseScale: Float = 0f,
    val noiseOffset: Float = 0f,
    /**
     * RAW bursts only: flash / no-flash mix, the last path being the flash frame taken at
     * the same exposure. [ambient gain, flash target level, flash->ambient white r g b];
     * null = no flash.
     */
    val flash: FloatArray? = null,
    /**
     * RAW single flash with per-area white: paths are {ambient frame, flash frame}; the
     * flash frame is developed, the white blended per area between [ambient r g b] and
     * [flash r g b] (camera rgb neutrals). Null = off.
     */
    val flashWhite: FloatArray? = null,
    /**
     * RAW bursts: each frame's exposure (time x gain) as asked of the camera, in any unit.
     * Equal frames then need no exposure measurement and the GPU aligns them all.
     * Null = measured.
     */
    val frameExposure: FloatArray? = null,
)

class Developed(
    val bitmap: Bitmap,
    val passes: Int,
    val peakImageBytes: Long,
    /** GPU time from timestamps; 0 when the device has none. */
    val gpuMs: Double,
    /** The develop's record: "section\tkey\tvalue" lines. */
    val info: String = "",
)

/**
 * Spectral film development (vkdt filmsim, an implementation of spektrafilm) on
 * the GPU. Parameters use filmsim's names, e.g. "film", "paper", "ev film",
 * "cp amt", "radius", "size"; unset ones keep filmsim's defaults.
 */
class FilmEngine private constructor(private var handle: Long) : AutoCloseable {

    val deviceName: String get() = FilmNative.deviceName(checkOpen())

    fun develop(
        source: Bitmap,
        params: Map<String, Float> = emptyMap(),
        options: DevelopOptions = DevelopOptions(),
    ): Developed {
        org.photoall.film.CrashReport.stage("冲洗 ${source.width}×${source.height}")
        val rgba = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        val sourceP3 = rgba.colorSpace?.let { it == ColorSpace.get(ColorSpace.Named.DISPLAY_P3) } ?: false
        val info = FilmNative.develop(
            checkOpen(), rgba, sourceP3,
            params.keys.toTypedArray(), params.values.toFloatArray(),
            options.maxLongEdge, options.exposureEv, options.seed, options.displayP3,
        )
        val space = ColorSpace.get(if (options.displayP3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
        val out = Bitmap.createBitmap(info[0].toInt(), info[1].toInt(), Bitmap.Config.ARGB_8888, false, space)
        FilmNative.copyResult(handle, out)
        org.photoall.film.CrashReport.stage("冲洗完成")
        return Developed(out, info[2].toInt(), info[3], info[4] / 1000.0)
    }

    /**
     * Develops camera DNGs from scene-linear sensor data (the path spektrafilm is
     * built for). Several equally exposed frames are aligned and merged first
     * (HDR+-style; the first is the reference, at most 6). The result is rotated
     * upright from the DNG orientation tag.
     */
    /**
     * Develops DNGs given as file paths (String) or as direct ByteBuffers holding a DNG
     * up to their limit; several are merged as an HDR+ burst (the first the reference).
     */
    fun developDng(
        sources: List<Any>,
        params: Map<String, Float> = emptyMap(),
        options: DevelopOptions = DevelopOptions(),
    ): Developed {
        org.photoall.film.CrashReport.stage("从 RAW 冲洗 ${sources.size} 帧")
        val info = FilmNative.developDng(
            checkOpen(), sources.toTypedArray(), params.keys.toTypedArray(), params.values.toFloatArray(),
            options.maxLongEdge, options.exposureEv, options.seed, options.displayP3, options.matchKey,
            options.localTone, options.chromaDenoise, options.rawDenoise,
            options.neutral, options.noiseScale, options.noiseOffset, options.flash, options.flashWhite,
            options.frameExposure,
        )
        val developedAt = System.nanoTime()
        val space = ColorSpace.get(if (options.displayP3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
        var out = Bitmap.createBitmap(info[0].toInt(), info[1].toInt(), Bitmap.Config.ARGB_8888, false, space)
        FilmNative.copyResult(handle, out)
        val copiedAt = System.nanoTime()
        val degrees = when (info[5].toInt()) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (degrees != 0f) {
            val upright = android.graphics.Matrix().apply { postRotate(degrees) }
            out = Bitmap.createBitmap(out, 0, 0, out.width, out.height, upright, true)
        }
        val rotatedAt = System.nanoTime()
        org.photoall.film.CrashReport.stage("冲洗完成")
        val timing = "冲洗结果\t取图 / 旋转\t%.0f / %.0f ms\n".format(java.util.Locale.US,
            (copiedAt - developedAt) / 1e6, (rotatedAt - copiedAt) / 1e6)
        return Developed(out, info[2].toInt(), info[3], info[4] / 1000.0, FilmNative.lastNote(handle) + timing)
    }

    /**
     * The per-pixel film look as a [size]^3 RGBA8 3D LUT (r fastest, then g, then b)
     * for the live viewfinder. Input is the display-encoded camera frame.
     */
    fun previewLut(params: Map<String, Float> = emptyMap(), size: Int = 33, displayP3: Boolean = false): ByteArray =
        FilmNative.previewLut(checkOpen(), params.keys.toTypedArray(), params.values.toFloatArray(), size, displayP3)

    /**
     * Creative look applied after the film (and so in previews too): RGB8 [size]^3,
     * red fastest, sRGB in and out, blended at [strength]. Null clears it.
     */
    fun setCreativeLut(rgb: ByteArray?, size: Int, strength: Float) =
        FilmNative.setCreativeLut(checkOpen(), rgb, size, strength)

    override fun close() {
        if (handle != 0L) FilmNative.destroy(handle)
        handle = 0L
    }

    private fun checkOpen(): Long {
        check(handle != 0L) { "FilmEngine is closed" }
        return handle
    }

    companion object {
        /** Loads filmsim/filmsim.lut and filmsim/spectra-em.lut from the app assets. */
        fun create(assets: AssetManager): FilmEngine {
            org.photoall.film.CrashReport.stage("启动 GPU 引擎")
            return FilmEngine(FilmNative.create(assets)).also {
                runCatching { it.deviceName }.getOrNull()?.let { name -> org.photoall.film.CrashReport.gpuName(name) }
                org.photoall.film.CrashReport.stage("GPU 引擎就绪")
            }
        }

        val filmStocks: List<String> by lazy { FilmNative.names(false).toList() }
        val papers: List<String> by lazy { FilmNative.names(true).toList() }
    }
}

internal object FilmNative {
    init {
        System.loadLibrary("filmjni")
    }

    @JvmStatic external fun create(assets: AssetManager): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun deviceName(handle: Long): String
    @JvmStatic external fun names(papers: Boolean): Array<String>
    @JvmStatic external fun develop(
        handle: Long, source: Bitmap, sourceP3: Boolean,
        names: Array<String>, values: FloatArray,
        maxLongEdge: Int, exposureEv: Float, seed: Int, outputP3: Boolean,
    ): LongArray
    @JvmStatic external fun copyResult(handle: Long, bitmap: Bitmap)
    @JvmStatic external fun lastNote(handle: Long): String
    @JvmStatic external fun setCreativeLut(handle: Long, rgb: ByteArray?, size: Int, strength: Float)
    /** CCD camera emulation (cpp/digicam.cpp) from [src] into [dst], both ARGB_8888 of one size. */
    @JvmStatic external fun digicam(src: Bitmap, dst: Bitmap, lutU16: ByteArray, params: FloatArray, iso: Int, strength: Float, seed: Int): Boolean
    @JvmStatic external fun bufferCreate(capacity: Long): Long
    @JvmStatic external fun bufferWrite(handle: Long, data: ByteArray, offset: Int, length: Int)
    @JvmStatic external fun bufferSave(handle: Long, path: String): Boolean
    @JvmStatic external fun bufferRelease(handle: Long)
    @JvmStatic external fun developDng(
        handle: Long, paths: Array<Any>, names: Array<String>, values: FloatArray,
        maxLongEdge: Int, exposureEv: Float, seed: Int, outputP3: Boolean, matchKey: Float, localTone: Float,
        chromaDenoise: Float, rawDenoise: Float,
        neutral: FloatArray?, noiseScale: Float, noiseOffset: Float, flash: FloatArray?, flashWhite: FloatArray?,
        frameExposure: FloatArray?,
    ): LongArray
    @JvmStatic external fun previewLut(
        handle: Long, names: Array<String>, values: FloatArray, size: Int, outputP3: Boolean,
    ): ByteArray
}
