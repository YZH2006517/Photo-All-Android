package org.photoall.film.gallery

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** The developed photos this app saved, newest first, plus the RAW copies. */
object Photos {
    const val FOLDER = "Pictures/PhotoAll Film/"
    const val RAW_FOLDER = "Pictures/PhotoAll Film/RAW/"

    data class Photo(
        val uri: Uri,
        val name: String,
        val width: Int,
        val height: Int,
        val taken: Long,        // seconds
        val bytes: Long,
        val favorite: Boolean,
        val description: String, // film / paper / source written at save time
    )

    fun list(context: Context, favoritesOnly: Boolean = false): List<Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.IS_FAVORITE, MediaStore.Images.Media.DESCRIPTION,
        )
        var selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?"
        if (favoritesOnly) selection += " AND ${MediaStore.Images.Media.IS_FAVORITE} = 1"
        val photos = mutableListOf<Photo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
            arrayOf(FOLDER, "image/jpeg"), "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                photos += Photo(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5),
                    c.getInt(6) == 1, c.getString(7) ?: "",
                )
            }
        }
        return photos
    }

    fun saveJpeg(context: Context, bitmap: Bitmap, name: String, quality: Int, description: String = ""): Uri {
        val uri = insert(context, name, "image/jpeg", FOLDER, description)
        context.contentResolver.openOutputStream(uri).use { out ->
            check(out != null && bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "保存失败" }
        }
        publish(context, uri)
        return uri
    }

    /** Copies a camera DNG into the shared RAW folder so other editors can open it. */
    fun saveDng(context: Context, file: File, name: String): Uri {
        val uri = insert(context, name, "image/x-adobe-dng", RAW_FOLDER)
        context.contentResolver.openOutputStream(uri).use { out ->
            checkNotNull(out) { "无法写入 RAW" }
            file.inputStream().use { it.copyTo(out) }
        }
        publish(context, uri)
        return uri
    }

    /**
     * Sets the system favourite flag. The app may write its own photos directly; if the
     * system refuses (e.g. after a reinstall changed ownership) the returned request
     * asks the user instead.
     */
    fun setFavorite(context: Context, photos: List<Photo>, on: Boolean): PendingIntent? =
        update(context, photos.map { it.uri }, MediaStore.Images.Media.IS_FAVORITE, on) {
            MediaStore.createFavoriteRequest(context.contentResolver, it, on)
        }

    /**
     * Moves photos, and the RAW originals shot with them (same FILM_<time> stamp), to the
     * system recycle bin, where the system gallery can restore them for 30 days.
     */
    fun trash(context: Context, photos: List<Photo>): PendingIntent? =
        update(context, photos.map { it.uri } + rawOriginals(context, photos), MediaStore.Images.Media.IS_TRASHED, true) {
            MediaStore.createTrashRequest(context.contentResolver, it, true)
        }

    private fun rawOriginals(context: Context, photos: List<Photo>): List<Uri> {
        val names = photos.mapNotNull { Regex("^FILM_\\d{8}_\\d{6}_\\d{3}").find(it.name)?.value?.plus(".dng") }
        if (names.isEmpty()) return emptyList()
        val uris = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} IN (${names.joinToString { "?" }})",
            arrayOf(RAW_FOLDER) + names, null,
        )?.use { c -> while (c.moveToNext()) uris += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) }
        return uris
    }

    private fun update(context: Context, uris: List<Uri>, column: String, on: Boolean,
                       request: (List<Uri>) -> PendingIntent): PendingIntent? {
        val refused = uris.filter { uri ->
            runCatching {
                context.contentResolver.update(uri, ContentValues().apply { put(column, if (on) 1 else 0) }, null, null)
            }.isFailure
        }
        return if (refused.isEmpty()) null else request(refused)
    }

    private fun insert(context: Context, name: String, mime: String, folder: String, description: String = ""): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            if (description.isNotEmpty()) put(MediaStore.Images.Media.DESCRIPTION, description)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "无法写入相册"
        }
    }

    private fun publish(context: Context, uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
}
