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
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.film.camera.HistogramPainter
import org.photoall.film.ui.Style
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Full-screen viewer: swipe or arrows between photos, pinch / double-tap zoom,
 * favourite, share, open in another app, move to the recycle bin. Swiping up shows
 * the histogram, again the shooting details; swiping down steps back.
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
    private lateinit var histogram: HistogramView
    private lateinit var panel: LinearLayout
    private lateinit var detailRows: LinearLayout
    private lateinit var detailScroll: android.widget.ScrollView
    /** Sections the user opened; they stay open from photo to photo, for comparing. */
    private val expanded = mutableSetOf<String>()
    /** The whole record of the current photo as text, for the copy button. */
    private var detailText = ""
    private var panelLevel = 0 // 0 hidden, 1 histogram, 2 histogram + details
    private var shot: ShotInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra(INDEX, 0)
        favoritesOnly = intent.getBooleanExtra(FAVORITES, false)
        image = ZoomImageView(this).apply {
            onSwipe = { step -> show(index + step) }
            onSwipeVertical = { step -> setPanel(panelLevel + step) }
        }
        info = TextView(this).apply {
            setTextColor(Style.TEXT)
            textSize = 13f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            Style.numeric(this)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        favoriteButton = button("♡") { toggleFavorite() }.apply { textSize = 18f }
        histogram = HistogramView(this)
        detailRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.SCRIM)
            setPadding(dp(16), dp(12), dp(16), dp(8))
            visibility = View.GONE
            addView(histogram, LinearLayout.LayoutParams(-1, dp(96)))
            detailScroll = android.widget.ScrollView(this@ViewerActivity).apply { addView(detailRows) }
            // Half the screen for the record; it scrolls.
            addView(detailScroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5).toInt())
                .apply { topMargin = dp(10) })
        }
        // Equal-width action cells along the bottom.
        val actions = LinearLayout(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(Style.SCRIM)
            for (b in listOf(
                button("‹") { show(index - 1) }, favoriteButton, button("信息") { setPanel(if (panelLevel == 2) 0 else 2) },
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
            // Above the action row (40 dp buttons + 12 dp padding).
            addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(52) })
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
        favoriteButton.setTextColor(if (photo.favorite) Style.ACCENT else Style.TEXT)
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
            val counts = (bitmap as Bitmap?)?.let { HistogramPainter.of(it) }
            val info = ShotInfo.read(this, photo.uri)
            runOnUiThread {
                if (ticket == request) {
                    image.setImageBitmap(bitmap as Bitmap?)
                    histogram.histogram = counts
                    shot = info
                    fillDetails()
                }
            }
        }
    }

    private fun setPanel(level: Int) {
        panelLevel = level.coerceIn(0, 2)
        panel.visibility = if (panelLevel > 0) View.VISIBLE else View.GONE
        detailScroll.visibility = if (panelLevel == 2) View.VISIBLE else View.GONE
    }

    private fun toggleFavorite() {
        val photo = current() ?: return
        loader.execute {
            val ask = Photos.setFavorite(this, listOf(photo), !photo.favorite)
            runOnUiThread { if (ask != null) ask(ask) else reload() }
        }
    }

    /**
     * The current photo's record in collapsible sections (closed by default): the file,
     * its EXIF, and the shot log saved at capture (camera, exposure, white balance, each
     * frame, the develop's decisions, all settings).
     */
    private fun fillDetails() {
        val p = current() ?: return
        val info = shot
        val sections = LinkedHashMap<String, List<Pair<String, String>>>()
        // The recipe was written at save time; older photos only have it in the file name.
        val recipe = p.description.ifEmpty {
            Regex("""^FILM_\d{8}_\d{6}(?:_\d{3})?_(.+)\.jpg$""").find(p.name)?.groupValues?.get(1)?.replace('-', ' ') ?: "—"
        }
        sections["照片"] = listOf(
            "配方" to recipe,
            "拍摄时间" to DateFormat.getDateTimeInstance().format(Date(info?.takenMs ?: (p.taken * 1000))),
            "尺寸" to "${p.width} × ${p.height}（%.1f MP）".format(p.width * p.height / 1e6),
            "文件大小" to Formatter.formatShortFileSize(this, p.bytes),
            "文件名" to p.name,
            "位置" to Photos.FOLDER,
        )
        sections["EXIF"] = listOf(
            "曝光" to listOfNotNull(info?.fNumber?.let { "f/%.1f".format(it) }, info?.shutterText, info?.iso?.let { "ISO $it" })
                .joinToString(" · ").ifEmpty { "—" },
            "焦距" to listOfNotNull(info?.focalMm?.let { "%.1f mm".format(it) }, info?.focal35?.let { "等效 $it mm" })
                .joinToString(" · ").ifEmpty { "—" },
            "相机" to listOfNotNull(info?.make, info?.model).joinToString(" ").ifEmpty { "—" },
        )
        val log = ShotLog.load(this, p.name)
        if (log != null) for ((name, rows) in log.sections) sections[name] = rows.toList()
        else sections["拍摄档案"] = listOf("说明" to "这张照片没有拍摄档案（档案功能加入之前拍的）")

        detailText = buildString {
            for ((name, rows) in sections) {
                append("【").append(name).append("】\n")
                for ((k, v) in rows) append(k).append("：").append(v).append('\n')
                append('\n')
            }
        }.trimEnd()

        detailRows.removeAllViews()
        detailRows.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("拍摄档案 · ${sections.size} 类，点类别展开", Style.TEXT_2), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("复制全部") { copyDetails() }.apply { setPadding(dp(10), 0, dp(10), 0) },
                LinearLayout.LayoutParams(-2, dp(32)))
        })
        for ((name, rows) in sections) {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, dp(6))
                visibility = if (name in expanded) View.VISIBLE else View.GONE
                for ((label, value) in rows) addView(LinearLayout(this@ViewerActivity).apply {
                    setPadding(0, dp(2), 0, dp(2))
                    addView(text(label, Style.TEXT_2), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(value, Style.TEXT).apply { setTextIsSelectable(true); Style.numeric(this) },
                        LinearLayout.LayoutParams(0, -2, 1.4f).apply { marginStart = dp(8) })
                })
            }
            val header = text("", Style.TEXT).apply {
                typeface = Style.medium
                textSize = 14f
                setPadding(0, dp(8), 0, dp(6))
                fun label() { text = "${if (body.visibility == View.VISIBLE) "▾" else "▸"}  $name（${rows.size}）" }
                label()
                setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    if (open) expanded += name else expanded -= name
                    label()
                }
            }
            detailRows.addView(header)
            detailRows.addView(body)
        }
    }

    private fun copyDetails() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("拍摄档案", detailText))
        android.widget.Toast.makeText(this, "已复制拍摄档案", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun text(value: String, colour: Int) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(colour)
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
        Style.textButton(this, 13f)
        setOnClickListener { onClick() }
    }

    companion object {
        const val INDEX = "index"
        const val FAVORITES = "favorites"
    }
}
