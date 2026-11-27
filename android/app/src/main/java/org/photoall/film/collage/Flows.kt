package org.photoall.film.collage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** The ways between 拼贴相机's screens and the film camera's editor, in one place. */
object Flows {
    /**
     * The film camera's editor (调整, 曲线, 混色, 分级, 智能, 裁切) for [uri]; it saves a new
     * photo and hands its uri back as the result's data.
     */
    fun edit(context: Context, uri: Uri, tool: String? = null): Intent = Intent(context, org.photoall.film.gallery.EditActivity::class.java)
        .apply { if (tool != null) putExtra(org.photoall.film.gallery.EditActivity.TOOL, tool) }
        .putExtra(org.photoall.film.gallery.EditActivity.URI, uri)
        .putExtra(org.photoall.film.gallery.EditActivity.NAME, displayName(context, uri) ?: "COLLAGE.jpg")
        .putExtra(org.photoall.film.gallery.EditActivity.PRIVATE, false)
        .putExtra(org.photoall.film.gallery.EditActivity.DESCRIPTION, "")

    /**
     * Going on to a main screen of 拼贴相机 (拼贴, the camera) from deep in a side errand (a
     * sticker just made, a sticker opened from the library): the screens of the errand are
     * closed and [target] opens over 拼贴相机's home, so back from it is one step, home —
     * not the whole way that led there again.
     */
    fun anchored(context: Context, target: Intent) {
        context.startActivity(Intent(context, CollageHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CollageHomeActivity.EXTRA_THEN, target))
    }

    /**
     * A sticker made for a screen that asked for one (拼贴's 贴纸, the camera's 加贴纸): 制作贴纸
     * hands the new sticker back up the way it came, the asking screen takes it — the same
     * collage, not a new one. The value is the words on the button that does it.
     */
    const val EXTRA_RETURN = "return_to"

    /** The one gallery all three modes share (the film camera's), opened on [filter] when given. */
    fun gallery(context: Context, filter: String? = null): Intent =
        Intent(context, org.photoall.film.gallery.GalleryActivity::class.java).apply { if (filter != null) putExtra(org.photoall.film.gallery.GalleryActivity.EXTRA_FILM, filter) }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
