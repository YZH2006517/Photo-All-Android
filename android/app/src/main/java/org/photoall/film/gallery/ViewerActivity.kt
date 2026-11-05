package org.photoall.film.gallery

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Full-screen viewer: swipe or arrows between photos, pinch / double-tap zoom,
 * favourite, details, share, open in another app, move to the recycle bin.
 */
class ViewerActivity : Activity() {
    private val loader = Executors.newSingleThreadExecutor()
    private var photos: List<Photos.Photo> = emptyList()
    private var index = 0
    private var request = 0
    private var favoritesOnly = false
    private lateinit var image: ZoomImageView
    private lateinit var info: TextView
    private lateinit var favoriteButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra(INDEX, 0)
        favoritesOnly = intent.getBooleanExtra(FAVORITES, false)
        image = ZoomImageView(this).apply { onSwipe = { step -> show(index + step) } }
        info = TextView(this).apply {
            setTextColor(0xffdddddd.toInt())
            textSize = 13f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        favoriteButton = button("♡") { toggleFavorite() }.apply { textSize = 18f }
        // Equal-width action cells along the bottom.
        val actions = LinearLayout(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(0xcc000000.toInt())
            for (b in listOf(
                button("‹") { show(index - 1) }, favoriteButton, button("信息") { details() },
                button("分享") { share() }, button("打开") { openWith() }, button("删除") { trash() },
                button("›") { show(index + 1) },
            )) addView(b, LinearLayout.LayoutParams(0, dp(40), if (b.text.length <= 1) 0.8f else 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(actions, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        reload()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("System confirmation for favourites / recycle bin")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        reload()
    }

    private fun reload() {
        loader.execute {
            val list = Photos.list(this, favoritesOnly)
            runOnUiThread {
                photos = list
                show(index, keepImage = false)
            }
        }
    }

    private fun current() = photos.getOrNull(index)

    private fun show(target: Int, keepImage: Boolean = true) {
        if (photos.isEmpty()) {
            finish()
            return
        }
        val previous = current()?.uri
        index = target.coerceIn(0, photos.lastIndex)
        val photo = photos[index]
        info.text = "${index + 1} / ${photos.size}" + if (photo.description.isNotEmpty()) "\n${photo.description}" else ""
        favoriteButton.text = if (photo.favorite) "♥" else "♡"
        favoriteButton.setTextColor(if (photo.favorite) 0xffff4060.toInt() else Color.WHITE)
        if (keepImage && previous == photo.uri) return
        val ticket = ++request
        loader.execute {
            // Decode at most 4096 px on the long edge: sharp when zoomed, bounded memory.
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, photo.uri)) { decoder, inf, _ ->
                    val s = minOf(1f, 4096f / maxOf(inf.size.width, inf.size.height))
                    decoder.setTargetSize((inf.size.width * s).toInt(), (inf.size.height * s).toInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
            runOnUiThread { if (ticket == request) image.setImageBitmap(bitmap as Bitmap?) }
        }
    }

    private fun toggleFavorite() {
        val photo = current() ?: return
        loader.execute {
            val ask = Photos.setFavorite(this, listOf(photo), !photo.favorite)
            runOnUiThread { if (ask != null) ask(ask) else reload() }
        }
    }

    private fun details() {
        val p = current() ?: return
        // The recipe was written at save time; older photos only have it in the file name.
        val recipe = p.description.ifEmpty {
            Regex("^FILM_\\d{8}_\\d{6}(?:_\\d{3})?_(.+)\\.jpg$").find(p.name)?.groupValues?.get(1)?.replace('-', ' ') ?: "—"
        }
        val rows = listOf(
            "胶片 / 相纸" to recipe,
            "拍摄时间" to DateFormat.getDateTimeInstance().format(Date(p.taken * 1000)),
            "尺寸" to "${p.width} × ${p.height}（%.1f MP）".format(p.width * p.height / 1e6),
            "文件大小" to Formatter.formatShortFileSize(this, p.bytes),
            "文件名" to p.name,
            "位置" to Photos.FOLDER,
        )
        AlertDialog.Builder(this)
            .setTitle("详细信息")
            .setMessage(rows.joinToString("\n\n") { (k, v) -> "$k\n$v" })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun share() {
        val photo = current() ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享照片"))
    }

    private fun openWith() {
        val photo = current() ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_VIEW).setDataAndType(photo.uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "用其他应用打开"))
    }

    private fun trash() {
        val photo = current() ?: return
        AlertDialog.Builder(this)
            .setMessage("把这张照片移到回收站？\n同时拍下的 RAW 原片也会移走，30 天内可在系统相册的回收站恢复。\n${photo.name}")
            .setPositiveButton("移到回收站") { _, _ ->
                loader.execute {
                    val ask = Photos.trash(this, listOf(photo))
                    runOnUiThread { if (ask != null) ask(ask) else reload() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // The system asks the user for photos this app may not change on its own.
    @Suppress("DEPRECATION")
    private fun ask(request: PendingIntent) = startIntentSenderForResult(request.intentSender, 1, null, 0, 0, 0)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        isSingleLine = true
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    companion object {
        const val INDEX = "index"
        const val FAVORITES = "favorites"
    }
}
