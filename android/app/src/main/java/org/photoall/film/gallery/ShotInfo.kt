package org.photoall.film.gallery

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ISO uses TAG_ISO_SPEED_RATINGS (0x8827, the PhotographicSensitivity tag), the framework name.
@Suppress("DEPRECATION")
/** Shooting data of a photo: read from the camera's DNG / JPEG, written into the developed JPEG's EXIF. */
data class ShotInfo(
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val fNumber: Double? = null,
    val focalMm: Double? = null,
    val focal35: Int? = null,
    val make: String? = null,
    val model: String? = null,
    val takenMs: Long? = null,
) {
    /** "1/250 s", "0.5 s". */
    val shutterText: String?
        get() = exposureNs?.let { ns ->
            val s = ns / 1e9
            if (s >= 0.3) "%.1f s".format(Locale.US, s).replace(".0 s", " s") else "1/${(1 / s).roundToInt()} s"
        }

    companion object {
        fun from(exif: ExifInterface) = ShotInfo(
            exposureNs = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { (it * 1e9).roundToLong() },
            iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 },
            fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 },
            focalMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 },
            focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 },
            make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
            model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            takenMs = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                runCatching { exifDate().parse(it)?.time }.getOrNull()
            },
        )

        fun fromFile(path: String): ShotInfo? = runCatching { from(ExifInterface(path)) }.getOrNull()

        /**
         * Shooting data of a camera DNG. Android's DngCreator writes the exposure tags into
         * IFD 0 (TIFF/EP style), where ExifInterface does not look, so the IFDs are read here
         * directly: IFD 0 and, if present, its EXIF sub-IFD.
         */
        fun fromDng(path: String): ShotInfo? = runCatching {
            java.io.RandomAccessFile(path, "r").use { file ->
                val head = ByteArray(minOf(file.length(), 1L shl 20).toInt())
                file.readFully(head)
                val tags = TiffTags(head)
                ShotInfo(
                    exposureNs = tags.rational(0x829A)?.let { (it * 1e9).roundToLong() },
                    iso = tags.int(0x8827),
                    fNumber = tags.rational(0x829D),
                    focalMm = tags.rational(0x920A),
                    focal35 = tags.int(0xA405),
                    make = tags.ascii(0x010F),
                    model = tags.ascii(0x0110),
                    takenMs = tags.ascii(0x9003)?.let { runCatching { exifDate().parse(it)?.time }.getOrNull() },
                )
            }
        }.getOrNull()
        fun fromStream(input: InputStream): ShotInfo? = runCatching { from(ExifInterface(input)) }.getOrNull()

        fun read(context: Context, uri: Uri): ShotInfo? =
            runCatching { context.contentResolver.openInputStream(uri)?.use { fromStream(it) } }.getOrNull()

        private fun exifDate() = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }

    /** Writes the fields that are set into a JPEG opened read-write. */
    fun writeTo(exif: ExifInterface, software: String) {
        fun rational(value: Double, scale: Int) = "${(value * scale).roundToLong()}/$scale"
        // ExifInterface takes exposure time and f-number only as decimals (it drops "a/b"
        // and stores the value in 1/10000 steps), so short exposures are rounded there.
        exposureNs?.let { exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "%.6f".format(Locale.US, it / 1e9)) }
        iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
        fNumber?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, "%.2f".format(Locale.US, it)) }
        focalMm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, rational(it, 1000)) }
        focal35?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString()) }
        make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
        model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
        val date = exifDate().format(Date(takenMs ?: System.currentTimeMillis()))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
        exif.setAttribute(ExifInterface.TAG_DATETIME, date)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, software)
    }
}

/** The tags of a TIFF's IFD 0 and its EXIF sub-IFD, from the file's first bytes. */
private class TiffTags(private val data: ByteArray) {
    private val buffer = java.nio.ByteBuffer.wrap(data).order(
        if (data[0] == 'I'.code.toByte()) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
    private class Entry(val type: Int, val count: Int, val valueAt: Int)
    private val entries = HashMap<Int, Entry>()

    init {
        readIfd(buffer.getInt(4))
        entries[0x8769]?.let { readIfd(buffer.getInt(it.valueAt)) }
    }

    private fun readIfd(offset: Int) {
        if (offset <= 0 || offset + 2 > data.size) return
        val n = buffer.getShort(offset).toInt() and 0xffff
        for (i in 0 until n) {
            val at = offset + 2 + 12 * i
            if (at + 12 > data.size) return
            val tag = buffer.getShort(at).toInt() and 0xffff
            val type = buffer.getShort(at + 2).toInt() and 0xffff
            val count = buffer.getInt(at + 4)
            val size = count * when (type) { 3 -> 2; 4, 9 -> 4; 5, 10 -> 8; else -> 1 }
            // Values of up to 4 bytes sit in the entry itself, longer ones at an offset.
            entries.putIfAbsent(tag, Entry(type, count, if (size <= 4) at + 8 else buffer.getInt(at + 8)))
        }
    }

    fun int(tag: Int): Int? {
        val e = entries[tag] ?: return null
        if (e.valueAt < 0 || e.valueAt + 4 > data.size) return null
        return when (e.type) {
            3 -> buffer.getShort(e.valueAt).toInt() and 0xffff
            4, 9 -> buffer.getInt(e.valueAt)
            else -> null
        }?.takeIf { it > 0 }
    }

    fun rational(tag: Int): Double? {
        val e = entries[tag] ?: return null
        if ((e.type != 5 && e.type != 10) || e.valueAt < 0 || e.valueAt + 8 > data.size) return null
        val num = buffer.getInt(e.valueAt).toLong() and 0xffffffffL
        val den = buffer.getInt(e.valueAt + 4).toLong() and 0xffffffffL
        return if (den == 0L || num == 0L) null else num.toDouble() / den
    }

    fun ascii(tag: Int): String? {
        val e = entries[tag] ?: return null
        if (e.type != 2 || e.valueAt < 0 || e.valueAt + e.count > data.size) return null
        return String(data, e.valueAt, e.count, Charsets.US_ASCII).trimEnd('\u0000', ' ').takeIf { it.isNotEmpty() }
    }
}
