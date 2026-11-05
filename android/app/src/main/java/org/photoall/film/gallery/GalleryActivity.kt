package org.photoall.film.gallery

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Grid of the developed photos. Tap opens the viewer; long-press starts selecting
 * (tap toggles), with select-all, share and move-to-recycle-bin for the selection.
 * The title bar switches between all photos and favourites.
 */
class GalleryActivity : Activity() {
    private val loader = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var photos: List<Photos.Photo> = emptyList()
    private val selected = linkedSetOf<android.net.Uri>()
    private var selecting = false
    private var favoritesOnly = false
    private lateinit var grid: GridView
    private lateinit var title: TextView
    private lateinit var filterButton: Button
    private lateinit var browseBar: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selectTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
        filterButton = button("只看收藏") { favoritesOnly = !favoritesOnly; reload() }
        browseBar = bar(title, filterButton)
        selectTitle = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
        selectBar = bar(
            selectTitle,
            button("全选") { if (selected.size == photos.size) selected.clear() else selected += photos.map { it.uri }; refreshSelection() },
            button("分享") { share() },
            button("删除") { trash() },
            button("取消") { endSelection() },
        ).apply { visibility = View.GONE }
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setOnItemClickListener { _, _, position, _ ->
                val photo = photos[position]
                if (selecting) toggle(photo)
                else startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.INDEX, position)
                    .putExtra(ViewerActivity.FAVORITES, favoritesOnly))
            }
            setOnItemLongClickListener { _, _, position, _ ->
                selecting = true
                toggle(photos[position])
                true
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(browseBar)
            addView(selectBar)
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Back leaves selection mode first")
    override fun onBackPressed() {
        if (selecting) endSelection() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    @Deprecated("System confirmation for favourites / recycle bin")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) endSelection()
        reload()
    }

    private fun reload() {
        loader.execute {
            val list = Photos.list(this, favoritesOnly)
            runOnUiThread {
                photos = list
                selected.retainAll(list.map { it.uri }.toSet())
                title.text = when {
                    list.isEmpty() && favoritesOnly -> "还没有收藏"
                    list.isEmpty() -> "还没有照片"
                    favoritesOnly -> "收藏 · ${list.size} 张"
                    else -> "已冲洗 ${list.size} 张"
                }
                filterButton.text = if (favoritesOnly) "全部照片" else "只看收藏"
                grid.adapter = Adapter()
                refreshSelection()
            }
        }
    }

    private fun toggle(photo: Photos.Photo) {
        if (!selected.remove(photo.uri)) selected += photo.uri
        refreshSelection()
    }

    private fun endSelection() {
        selecting = false
        selected.clear()
        refreshSelection()
    }

    private fun refreshSelection() {
        browseBar.visibility = if (selecting) View.GONE else View.VISIBLE
        selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        selectTitle.text = "已选 ${selected.size} 张"
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun chosen() = photos.filter { it.uri in selected }

    private fun share() {
        val uris = ArrayList(chosen().map { it.uri })
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        startActivity(Intent.createChooser(intent.setType("image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享照片"))
    }

    private fun trash() {
        val chosen = chosen()
        if (chosen.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage("把 ${chosen.size} 张照片移到回收站？\n同时拍下的 RAW 原片也会移走，30 天内可在系统相册的回收站恢复。")
            .setPositiveButton("移到回收站") { _, _ ->
                loader.execute {
                    val request = Photos.trash(this, chosen)
                    runOnUiThread { if (request != null) ask(request) else { endSelection(); reload() } }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // The system asks the user for photos this app may not change on its own.
    @Suppress("DEPRECATION")
    private fun ask(request: PendingIntent) = startIntentSenderForResult(request.intentSender, 1, null, 0, 0, 0)

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val side = (parent.width - dp(4)) / 3
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@GalleryActivity).apply {
                addView(ImageView(this@GalleryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xff1a1a1a.toInt())
                }, FrameLayout.LayoutParams(-1, -1))
                addView(TextView(this@GalleryActivity).apply { // favourite / selection marks
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setShadowLayer(4f, 0f, 0f, Color.BLACK)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            }
            cell.layoutParams = AbsListView.LayoutParams(side, side)
            val image = cell.getChildAt(0) as ImageView
            val mark = cell.getChildAt(1) as TextView
            val photo = photos[position]
            val isSelected = photo.uri in selected
            image.alpha = if (selecting && !isSelected) 0.55f else 1f
            mark.text = when {
                selecting -> if (isSelected) "✓" else "○"
                photo.favorite -> "♥"
                else -> ""
            }
            mark.setTextColor(if (!selecting && photo.favorite) 0xffff4060.toInt() else Color.WHITE)
            val key = photo.uri.toString()
            image.tag = key
            val cached = cache.get(key)
            image.setImageBitmap(cached)
            if (cached == null) loader.execute {
                val thumb = runCatching { contentResolver.loadThumbnail(photo.uri, Size(360, 360), null) }.getOrNull()
                    ?: return@execute
                cache.put(key, thumb)
                runOnUiThread { if (image.tag == key) image.setImageBitmap(thumb) }
            }
            return cell
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        isSingleLine = true
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun bar(lead: TextView, vararg buttons: Button) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(8), dp(6))
        addView(lead, LinearLayout.LayoutParams(0, dp(40), 1f))
        for (b in buttons) addView(b, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(4) })
    }
}
