package org.photoall.film.collage

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import org.photoall.film.collage.Glassy.dp
import org.photoall.film.collage.Glassy.lp
import java.util.concurrent.Executors

/**
 * Choosing a photo, in the app's own look: the film camera's album (the photos Photo All made,
 * the same list as its gallery) or every photo on the phone, newest first, as a grid of
 * thumbnails over the photo ground. Hands back the photo's uri ([uriOf]); opened by [multiple],
 * several, numbered in the order they were tapped, handed back with 完成 ([urisOf]).
 */
class PhotoPickerActivity : Activity() {
    private enum class Source(val label: String) { FILM("胶片相册"), ALL("全部照片") }

    private val loader = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<Uri, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }
    private var source = Source.FILM
    private var uris: List<Uri> = emptyList()
    /** 全部照片 by album (the phone's own folders); null: every album. */
    private var album: Long? = null
    private var albums: List<Album> = emptyList()
    private lateinit var albumRow: android.widget.LinearLayout
    private lateinit var albumScroll: android.widget.HorizontalScrollView

    class Album(val id: Long, val name: String, val count: Int)
    /** 全部照片 without full access: why only some show, and the way to allow all. */
    private lateinit var permit: android.widget.LinearLayout
    private lateinit var permitText: TextView
    private lateinit var grid: GridView
    private lateinit var tabs: android.widget.LinearLayout
    private lateinit var empty: TextView
    /** How many may be chosen (0: one, handed back at the tap). */
    private var most = 0
    private val chosen = mutableListOf<Uri>()
    private var done: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Glassy.apply(this)
        super.onCreate(savedInstanceState)
        val a = this
        most = intent.getIntExtra(EXTRA_MOST, 0)
        source = if (getSharedPreferences("picker", MODE_PRIVATE).getString("source", null) == Source.ALL.name) Source.ALL else Source.FILM
        tabs = Glassy.row(a) {
            background = Glassy.glass(a, 999f, 0x14ffffff)
            setPadding(dp(a, 4f), dp(a, 4f), dp(a, 4f), dp(a, 4f))
        }
        grid = GridView(a).apply {
            numColumns = 3
            horizontalSpacing = dp(a, 3f); verticalSpacing = dp(a, 3f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            setPadding(dp(a, 3f), dp(a, 6f), dp(a, 3f), dp(a, 24f))
            adapter = Thumbs()
            setOnItemClickListener { _, _, position, _ ->
                val uri = uris[position]
                if (most <= 0) {
                    setResult(RESULT_OK, Intent().setData(uri))
                    finish()
                    return@setOnItemClickListener
                }
                if (!chosen.remove(uri)) {
                    if (chosen.size >= most) { android.widget.Toast.makeText(a, "最多 $most 张", android.widget.Toast.LENGTH_SHORT).show(); return@setOnItemClickListener }
                    chosen += uri
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                paintDone()
                invalidateViews()
            }
        }
        empty = Glassy.text(a, "", 14f, Glassy.TEXT_2).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val body = Glassy.vertical(a) {
            if (most > 0) done = Glassy.pill(a, "完成", strong = true) { finishChosen() }
            addView(Glassy.header(a, intent.getStringExtra(EXTRA_TITLE) ?: "选择照片", done))
            addView(tabs, lp(dp(a, 220f), dp(a, 38f)) { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(a, 8f) })
            albumRow = Glassy.row(a) { setPadding(dp(a, 12f), 0, dp(a, 12f), 0) }
            albumScroll = android.widget.HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                addView(albumRow)
                visibility = View.GONE
            }
            addView(albumScroll, lp(-1, dp(a, 40f)) { bottomMargin = dp(a, 6f) })
            permitText = Glassy.text(a, "", 13f, Glassy.TEXT_2)
            permit = Glassy.row(a) {
                background = Glassy.glass(a, 18f, 0x1fffffff)
                setPadding(dp(a, 14f), dp(a, 10f), dp(a, 8f), dp(a, 10f))
                gravity = Gravity.CENTER_VERTICAL
                addView(permitText, lp(0, -2, 1f))
                addView(Glassy.pill(a, "允许", strong = true) { askToRead() }, lp(dp(a, 72f), dp(a, 36f)) { marginStart = dp(a, 8f) })
                visibility = View.GONE
            }
            addView(permit, lp(-1, -2) { marginStart = dp(a, 12f); marginEnd = dp(a, 12f); bottomMargin = dp(a, 8f) })
            addView(FrameLayout(a).apply {
                addView(grid, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-1, -1))
            }, lp(-1, 0, 1f))
        }
        setContentView(Glassy.root(a, body))
        paintDone()
        paintTabs()
        load()
    }

    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }

    /** 完成 with the count; dim (and doing nothing) while none is chosen. */
    private fun paintDone() {
        val d = done ?: return
        d.text = if (chosen.isEmpty()) "完成" else "完成 ${chosen.size}"
        d.alpha = if (chosen.isEmpty()) 0.4f else 1f
    }

    private fun finishChosen() {
        if (chosen.isEmpty()) return
        setResult(RESULT_OK, Intent().putParcelableArrayListExtra(EXTRA_URIS, ArrayList(chosen)))
        finish()
    }

    override fun onResume() {
        super.onResume()
        // back from the system's settings or its photo choice: what can be read may have changed
        if (::grid.isInitialized && source == Source.ALL && access() != lastAccess) { albums = emptyList(); load() }
    }

    private enum class Access { ALL, SOME, NONE }
    private var lastAccess: Access? = null

    /** How much of the phone's photos this app may read (Android 14 can allow a chosen few). */
    private fun access(): Access {
        fun has(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return when {
            android.os.Build.VERSION.SDK_INT >= 33 && has(android.Manifest.permission.READ_MEDIA_IMAGES) -> Access.ALL
            android.os.Build.VERSION.SDK_INT < 33 && has(android.Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.ALL
            android.os.Build.VERSION.SDK_INT >= 34 && has(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.SOME
            else -> Access.NONE
        }
    }

    private fun askToRead() {
        val perms = when {
            android.os.Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val prefs = getSharedPreferences("picker", MODE_PRIVATE)
        // refused for good (asked before, the system no longer shows its question): the app's settings page
        if (prefs.getBoolean("read_asked", false) && access() == Access.NONE && !shouldShowRequestPermissionRationale(perms[0]))
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        else {
            prefs.edit().putBoolean("read_asked", true).apply()
            requestPermissions(perms, READ_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_REQUEST) { albums = emptyList(); load() }
    }

    private fun paintPermit() {
        val acc = access()
        lastAccess = acc
        permit.visibility = if (source == Source.ALL && acc != Access.ALL) View.VISIBLE else View.GONE
        permitText.text = if (acc == Access.SOME) "只允许了读取部分照片，所以这里只有那几张" else "还没有允许读取手机照片，这里只能看到 Photo All 自己的"
    }

    private fun paintTabs() {
        tabs.removeAllViews()
        for (s in Source.entries) tabs.addView(TextView(this).apply {
            text = s.label; textSize = 13f; gravity = Gravity.CENTER; typeface = Glassy.semibold
            val on = s == source
            setTextColor(if (on) 0xff1c1c1e.toInt() else Glassy.TEXT)
            background = if (on) android.graphics.drawable.GradientDrawable().apply { setColor(Glassy.TEXT); cornerRadius = 999f } else null
            setOnClickListener {
                if (source != s) {
                    source = s
                    getSharedPreferences("picker", MODE_PRIVATE).edit().putString("source", s.name).apply()
                    paintTabs(); load()
                }
            }
        }, lp(0, -1, 1f))
    }

    private fun load() {
        val s = source
        loader.execute {
            val al = album
            val list = runCatching { if (s == Source.FILM) filmAlbum(this) else allPhotos(this, al) }.getOrDefault(emptyList())
            val found = if (s == Source.ALL && albums.isEmpty()) runCatching { albums(this) }.getOrDefault(emptyList()) else albums
            runOnUiThread {
                if (s != source || isFinishing) return@runOnUiThread
                albums = found
                paintAlbums()
                paintPermit()
                uris = list
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                grid.setSelection(0)
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                empty.text = if (s == Source.FILM) "胶片相册里还没有照片" else if (access() == Access.ALL) "手机里没有照片" else "允许读取照片后，这里会显示手机里的全部照片"
            }
        }
    }

    /** The phone's albums as chips (全部照片 only): 全部, then each by size, with its count. */
    private fun paintAlbums() {
        val a = this
        albumScroll.visibility = if (source == Source.ALL && albums.isNotEmpty()) View.VISIBLE else View.GONE
        albumRow.removeAllViews()
        fun chip(label: String, on: Boolean, go: () -> Unit) = albumRow.addView(TextView(a).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = Glassy.semibold
            setTextColor(if (on) 0xff1c1c1e.toInt() else Glassy.TEXT)
            background = if (on) android.graphics.drawable.GradientDrawable().apply { setColor(Glassy.TEXT); cornerRadius = 999f }
                else Glassy.glass(a, 999f, 0x14ffffff, rim = false)
            setPadding(dp(a, 14f), 0, dp(a, 14f), 0)
            setOnClickListener { go() }
        }, lp(-2, dp(a, 34f)) { marginEnd = dp(a, 6f) })
        chip("全部", album == null) { if (album != null) { album = null; load() } }
        for (al in albums) chip("${al.name}  ${al.count}", album == al.id) { if (album != al.id) { album = al.id; load() } }
    }

    /** One cell: a square thumbnail, loaded off the main thread and kept while it fits the cache. */
    private inner class Thumbs : BaseAdapter() {
        override fun getCount() = uris.size
        override fun getItem(position: Int) = uris[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? Cell) ?: Cell().apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x14ffffff)
            }
            val uri = uris[position]
            v.tag = uri
            v.order = chosen.indexOf(uri) + 1
            val cached = thumbs.get(uri)
            v.setImageBitmap(cached)
            if (cached == null) loader.execute {
                if (v.tag != uri) return@execute
                val b = runCatching { contentResolver.loadThumbnail(uri, Size(320, 320), null) }.getOrNull() ?: return@execute
                thumbs.put(uri, b)
                v.post { if (v.tag == uri) v.setImageBitmap(b) }
            }
            return v
        }
    }

    /** A square thumbnail; chosen ([order] > 0), dimmed a little, its number in a yellow dot. */
    private inner class Cell : ImageView(this@PhotoPickerActivity) {
        var order = 0
        private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
        override fun onDraw(c: android.graphics.Canvas) {
            super.onDraw(c)
            val d = resources.displayMetrics.density
            val r = 11 * d; val x = width - r - 6 * d; val y = r + 6 * d
            dot.style = android.graphics.Paint.Style.FILL
            if (order > 0) {
                c.drawColor(0x40000000)
                dot.color = 0xffffd60a.toInt(); c.drawCircle(x, y, r, dot)
                dot.color = 0xff1c1c1e.toInt(); dot.textSize = 12 * d; dot.textAlign = android.graphics.Paint.Align.CENTER; dot.typeface = Glassy.bold
                c.drawText(order.toString(), x, y + 4.2f * d, dot)
            } else if (most > 0) {
                dot.style = android.graphics.Paint.Style.STROKE; dot.strokeWidth = 1.5f * d; dot.color = 0xccffffff.toInt()
                c.drawCircle(x, y, r, dot)
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        /** How many may be chosen ([multiple]). */
        const val EXTRA_MOST = "most"
        const val EXTRA_URIS = "uris"
        private const val READ_REQUEST = 7

        fun intent(context: Context, title: String = "选择照片") = Intent(context, PhotoPickerActivity::class.java).putExtra(EXTRA_TITLE, title)

        fun uriOf(data: Intent?): Uri? = data?.data

        /** Up to [most] photos at once, handed back with 完成 ([urisOf]). */
        fun multiple(context: Context, title: String, most: Int) = intent(context, title).putExtra(EXTRA_MOST, most)

        @Suppress("DEPRECATION")
        fun urisOf(data: Intent?): List<Uri> = data?.getParcelableArrayListExtra<Uri>(EXTRA_URIS) ?: emptyList()

        /** The photos Photo All made, as its own album lists them, newest first. */
        fun filmAlbum(context: Context): List<Uri> =
            org.photoall.film.gallery.Photos.list(context).filter { !it.name.endsWith(".dng", true) }.map { it.uri }

        /** The phone's photo albums (MediaStore buckets), the largest first. */
        fun albums(context: Context): List<Album> {
            val count = LinkedHashMap<Long, Pair<String, Int>>()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val name = c.getString(1) ?: "其他"
                    count[id] = name to ((count[id]?.second ?: 0) + 1)
                }
            }
            return count.map { (id, v) -> Album(id, v.first, v.second) }.sortedByDescending { it.count }
        }

        /** Every photo on the phone (or in album [bucket]), newest first (most recent 3000). */
        fun allPhotos(context: Context, bucket: Long? = null): List<Uri> {
            val out = ArrayList<Uri>()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                bucket?.let { "${MediaStore.Images.Media.BUCKET_ID} = ?" }, bucket?.let { arrayOf(it.toString()) },
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 3000)
                    out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            }
            return out
        }
    }
}
