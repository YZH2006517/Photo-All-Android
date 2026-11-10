package org.photoall.film.gallery

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * The developed photos this app saved, newest first, plus the RAW copies: in the shared
 * Pictures folder (the phone's gallery shows them) or, with the private setting, in the
 * app's own folder, which only this app's gallery lists.
 */
object Photos {
    const val FOLDER = "Pictures/PhotoAll Film/"
    const val RAW_FOLDER = "Pictures/PhotoAll Film/RAW/"
    private const val AUTHORITY = "org.photoall.film.files"

    /** The app's own photo folder: not indexed by the system, removed with the app. */
    fun privateDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PhotoAll").apply { mkdirs() }

    private fun privateRawDir(context: Context) = File(privateDir(context), "RAW").apply { mkdirs() }

    private fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, AUTHORITY, file)

    /** Where a photo is, for its details. */
    fun location(photo: Photo) = if (photo.file != null) "仅本应用相册（不在手机相册）" else FOLDER

    /**
     * The film a photo was developed with, as the recipe saved with it says ("kodak
     * portra 400 · ..."), or the file name for older photos; "直出" without a film.
     */
    fun filmOf(photo: Photo): String {
        val first = photo.description.substringBefore(" · ").trim()
        if (first.isNotEmpty()) return if (first.startsWith("无胶片")) "直出" else first
        val fromName = Regex("""^FILM_\d{8}_\d{6}(?:_\d{3})?_(.+)\.jpg$""").find(photo.name)?.groupValues?.get(1)
        return when (fromName) {
            null -> ""
            "digital" -> "直出"
            else -> fromName.replace('-', ' ')
        }
    }

    /**
     * A grid thumbnail (long side 480), decoded from the photo itself. The system's ready-made
     * thumbnail is not used: for photos saved by this app it can come out dark or black
     * (made from an embedded preview, or before the file was complete), and stays that way.
     * JPEG decoding at a target size subsamples, so this is quick.
     */
    fun thumbnail(context: Context, photo: Photo): android.graphics.Bitmap? {
        return runCatching {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, photo.uri)) { d, info, _ ->
                val s = minOf(1f, 480f / maxOf(info.size.width, info.size.height))
                d.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt()))
            }
        }.getOrNull()
    }

    /** The album's filters, shared by the grid and the viewer so both page through the same set. */
    fun filter(photos: List<Photo>, film: String?, privateOnly: Boolean) = photos.filter {
        (film == null || filmOf(it) == film) && (!privateOnly || it.file != null)
    }

    data class Photo(
        val uri: Uri,
        val name: String,
        val width: Int,
        val height: Int,
        val taken: Long,        // seconds
        val bytes: Long,
        val favorite: Boolean,
        val description: String, // film / paper / source written at save time
        val file: File? = null,  // set for a photo in the app's own folder
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
        val recipes = recipes(context)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
            arrayOf(FOLDER, "image/jpeg"), "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                photos += Photo(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5),
                    // MediaStore drops descriptions apps write (it reads them from the file),
                    // so the recipe saved with the photo lives in the app's own store.
                    c.getInt(6) == 1, c.getString(7)?.takeIf { it.isNotEmpty() } ?: recipes.getString(c.getString(1), "") ?: "",
                )
            }
        }
        photos += listPrivate(context, favoritesOnly, recipes)
        return photos.sortedByDescending { it.taken }
    }

    private fun listPrivate(context: Context, favoritesOnly: Boolean,
                            recipes: android.content.SharedPreferences): List<Photo> {
        val favorites = privateFavorites(context).getStringSet("names", emptySet()) ?: emptySet()
        val files = privateDir(context).listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return emptyList()
        return files.filter { !favoritesOnly || it.name in favorites }.map { f ->
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, bounds)
            Photo(uriOf(context, f), f.name, bounds.outWidth, bounds.outHeight, f.lastModified() / 1000, f.length(),
                f.name in favorites, recipes.getString(f.name, "") ?: "", f)
        }
    }

    private fun privateFavorites(context: Context) =
        context.getSharedPreferences("private_favorites", Context.MODE_PRIVATE)

    fun saveJpeg(context: Context, bitmap: Bitmap, name: String, quality: Int, description: String = "",
                 shot: ShotInfo? = null, private: Boolean = false): Uri {
        // Encode, add the shooting data (EXIF) in a cache file, then copy in one write: a
        // file changed after insertion is rescanned, which clears the description.
        val temp = File.createTempFile("save", ".jpg", context.cacheDir)
        try {
            temp.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "保存失败" }
            }
            if (shot != null) runCatching {
                android.media.ExifInterface(temp.path).apply { shot.writeTo(this, SOFTWARE) }.saveAttributes()
            }
            if (description.isNotEmpty()) recipes(context).edit().putString(name, description).apply()
            if (private) {
                val target = File(privateDir(context), name)
                temp.copyTo(target, overwrite = true)
                return uriOf(context, target)
            }
            val uri = insert(context, name, "image/jpeg", FOLDER, description)
            context.contentResolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "保存失败" }
                temp.inputStream().use { it.copyTo(out) }
            }
            publish(context, uri)
            return uri
        } finally {
            temp.delete()
        }
    }

    /** Copies a camera DNG into the shared RAW folder so other editors can open it. */
    /** Recipe text per saved file name (film · paper · LUT · source). */
    private fun recipes(context: Context) = context.getSharedPreferences("photo_recipes", Context.MODE_PRIVATE)

    const val SOFTWARE = "Photo All" // EXIF ASCII

    fun saveDng(context: Context, file: File, name: String, private: Boolean = false): Uri {
        if (private) {
            val target = File(privateRawDir(context), name)
            file.copyTo(target, overwrite = true)
            return uriOf(context, target)
        }
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
    fun setFavorite(context: Context, photos: List<Photo>, on: Boolean): PendingIntent? {
        val (mine, shared) = photos.partition { it.file != null }
        if (mine.isNotEmpty()) {
            val store = privateFavorites(context)
            val names = (store.getStringSet("names", emptySet()) ?: emptySet()).toMutableSet()
            if (on) names += mine.map { it.name } else names -= mine.map { it.name }.toSet()
            store.edit().putStringSet("names", names).apply()
        }
        if (shared.isEmpty()) return null
        return update(context, shared.map { it.uri }, MediaStore.Images.Media.IS_FAVORITE, on) {
            MediaStore.createFavoriteRequest(context.contentResolver, it, on)
        }
    }

    /**
     * Moves photos, and the RAW originals shot with them (same FILM_<time> stamp), to the
     * system recycle bin, where the system gallery can restore them for 30 days.
     */
    fun trash(context: Context, photos: List<Photo>): PendingIntent? {
        // The app's own photos (and their RAW) are deleted outright: no system bin knows them.
        val (mine, shared) = photos.partition { it.file != null }
        for (photo in mine) {
            photo.file?.delete()
            Regex("^FILM_\\d{8}_\\d{6}_\\d{3}").find(photo.name)?.value?.let { File(privateRawDir(context), "$it.dng").delete() }
        }
        if (shared.isEmpty()) return null
        return update(context, shared.map { it.uri } + rawOriginals(context, shared), MediaStore.Images.Media.IS_TRASHED, true) {
            MediaStore.createTrashRequest(context.contentResolver, it, true)
        }
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
