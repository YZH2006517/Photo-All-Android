package org.photoall.film.gallery

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.film.ui.CanisterView
import org.photoall.film.ui.Icon
import org.photoall.film.ui.Style
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Drag-to-select: a long press selects a frame and, without lifting, sliding across
 * selects (or clears, if the first frame was already selected) every frame between it
 * and the finger; near the top or bottom edge the sheet scrolls on its own. While
 * selecting, a sideways drag starting on a frame does the same.
 *
 * The album as a contact sheet: frames on a dark film base, each with its frame number
 * and film printed underneath in edge-print amber, the way a contact print of a roll
 * looks. Filters: all, favourites, one film, the app's own folder. Tap opens the viewer;
 * long-press (or 选择) starts selecting, with select-all, share and delete.
 *
 * The sheet keeps its film-base colours in every theme, as photos are never tinted.
 */
class GalleryActivity : Activity() {
    private val loader = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var all: List<Photos.Photo> = emptyList()      // the folder (or favourites)
    private var photos: List<Photos.Photo> = emptyList()   // after the film / folder filter
    private val selected = linkedSetOf<android.net.Uri>()
    private var selecting = false
    private var favoritesOnly = false
    private var film: String? = null
    private var privateOnly = false
    private lateinit var grid: GridView
    private lateinit var restoreBanner: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var chips: LinearLayout
    private lateinit var empty: TextView
    private lateinit var browseBar: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selectTitle: TextView
    private lateinit var haptics: org.photoall.film.ui.Haptics
    // drag-to-select: the frame it started on, the selection before it, adding or clearing
    private var dragAnchor = -1
    private var dragLast = -1
    private var dragBase: Set<android.net.Uri> = emptySet()
    private var dragAdding = true
    private var downX = 0f
    private var downY = 0f
    private var downPos = -1
    private var fingerY = 0f
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (dragAnchor < 0) return
            val edge = dp(72)
            val speed = when {
                fingerY < edge -> -(edge - fingerY) / 4
                fingerY > grid.height - edge -> (fingerY - (grid.height - edge)) / 4
                else -> 0f
            }
            if (speed != 0f) {
                grid.scrollListBy(speed.toInt().coerceIn(-dp(24), dp(24)))
                extendDrag(grid.width / 2f, fingerY.coerceIn(1f, grid.height - 1f), keepX = true)
            }
            grid.postOnAnimation(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        val settings = org.photoall.film.settings.SettingsStore(this)
        haptics = org.photoall.film.ui.Haptics(window.decorView) { settings[org.photoall.film.settings.Settings.HAPTICS].toInt() }
        title = TextView(this).apply {
            text = "相册"
            textSize = 16f; typeface = Style.medium; setTextColor(Style.TEXT); gravity = Gravity.CENTER
        }
        subtitle = TextView(this).apply {
            textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }
        browseBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            addView(iconButton(Icon.Kind.BACK, "返回") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(title)
                addView(subtitle)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // the light table: this album's photos, roll by roll, under the same filter
            addView(LightboxEntry(this@GalleryActivity).apply {
                setOnClickListener {
                    haptics.play(org.photoall.film.ui.Haptics.Event.TICK)
                    startActivity(Intent(this@GalleryActivity, LightboxActivity::class.java)
                        .putExtra(ViewerActivity.FAVORITES, favoritesOnly)
                        .putExtra(ViewerActivity.FILM, film)
                        .putExtra(ViewerActivity.PRIVATE, privateOnly))
                }
            }, LinearLayout.LayoutParams(dp(76), dp(34)).apply { marginEnd = dp(4) })
            addView(textButton("选择") { selecting = true; refreshSelection() }, LinearLayout.LayoutParams(dp(56), dp(48)))
        }
        selectTitle = TextView(this).apply {
            textSize = 16f; typeface = Style.medium; setTextColor(Style.ACCENT); gravity = Gravity.CENTER_VERTICAL
            Style.numeric(this)
        }
        selectBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
            addView(selectTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
            for ((text, action) in listOf<Pair<String, () -> Unit>>(
                "全选" to { if (selected.size == photos.size) selected.clear() else selected += photos.map { it.uri }; refreshSelection() },
                "分享" to { share() }, "删除" to { trash() }, "取消" to { endSelection() },
            )) addView(textButton(text, action), LinearLayout.LayoutParams(-2, dp(48)))
            visibility = View.GONE
        }
        chips = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(10)); gravity = Gravity.CENTER_VERTICAL }
        restoreBanner = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = Style.surface(this@GalleryActivity, fill = Style.SURFACE, radiusDp = 12f)
            addView(TextView(this@GalleryActivity).apply {
                text = "清除数据或重装后，以前的照片还在手机相册里，但本应用需要授权读取照片才能重新显示它们"
                textSize = 12f
                setTextColor(Style.TEXT_2)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(textButton("授权") { askToRead() }.apply { setTextColor(Style.ACCENT) }, LinearLayout.LayoutParams(-2, dp(40)))
            addView(textButton("不再提示") {
                getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().putBoolean("restore_dismissed", true).apply()
                visibility = View.GONE
            }, LinearLayout.LayoutParams(-2, dp(40)))
            visibility = View.GONE
        }
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(6)
            verticalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(12), dp(12), dp(12), dp(24))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            // the contact sheet comes up cell by cell, row by row
            layoutAnimation = android.view.animation.GridLayoutAnimationController(
                android.view.animation.AnimationUtils.loadAnimation(this@GalleryActivity, org.photoall.film.R.anim.grid_cell_in),
                0.12f, 0.16f)
            setOnItemClickListener { _, _, cell, _ ->
                // the first cell brings the user's own pictures in to develop
                if (cell == 0) {
                    if (!selecting) startActivity(Intent(this@GalleryActivity, ImportActivity::class.java))
                    return@setOnItemClickListener
                }
                val position = cell - 1
                val photo = photos[position]
                if (selecting) toggle(photo)
                else {
                    coach?.event("open")
                    startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.INDEX, position)
                    .putExtra(ViewerActivity.FAVORITES, favoritesOnly)
                    .putExtra(ViewerActivity.FILM, film)
                    .putExtra(ViewerActivity.PRIVATE, privateOnly))
                }
            }
            setOnItemLongClickListener { _, _, cell, _ ->
                if (cell == 0) return@setOnItemLongClickListener false
                selecting = true
                startDrag(cell - 1)
                true
            }
            setOnTouchListener { _, e -> dragTouch(e) }
        }
        empty = TextView(this).apply {
            textSize = 14f; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER
            visibility = View.GONE
        }
        // The film base: a strip of sprocket holes along the top, frames below.
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG) // the theme's ground, as the rest of the app
            addView(SprocketStrip(this@GalleryActivity), LinearLayout.LayoutParams(-1, dp(12)))
            addView(FrameLayout(this@GalleryActivity).apply {
                addView(grid, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            addView(browseBar, LinearLayout.LayoutParams(-1, dp(56)))
            addView(selectBar, LinearLayout.LayoutParams(-1, dp(56)))
            addView(HorizontalScrollView(this@GalleryActivity).apply { isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength((28 * resources.displayMetrics.density).toInt());
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            addView(restoreBanner, LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(10)
            })
            addView(sheet, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
    }

    // A photo developing while the album is open appears when it is saved: the system's
    // media index for shared photos, the folder itself for the app's own.
    private val reloadSoon = Runnable { reload() }
    private val mediaWatch = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // the folder watch calls this on its own thread: settle it on the main one, and
            // not at all once the screen is closing
            grid.post {
                if (isFinishing || isDestroyed) return@post
                grid.removeCallbacks(reloadSoon)
                grid.postDelayed(reloadSoon, 400)
            }
        }
    }
    private var folderWatch: android.os.FileObserver? = null

    override fun onResume() {
        super.onResume()
        restoreBanner.visibility = if (!canReadAll() &&
            !getSharedPreferences("gallery", Context.MODE_PRIVATE).getBoolean("restore_dismissed", false)) View.VISIBLE else View.GONE
        reload()
        contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaWatch)
        folderWatch = object : android.os.FileObserver(Photos.privateDir(this),
            android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.MOVED_TO or android.os.FileObserver.DELETE) {
            override fun onEvent(event: Int, path: String?) { mediaWatch.onChange(false) }
        }.also { it.startWatching() }
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(mediaWatch)
        folderWatch?.stopWatching()
        folderWatch = null
        grid.removeCallbacks(reloadSoon)
        super.onPause()
    }

    override fun onDestroy() {
        grid.removeCallbacks(reloadSoon)
        loader.shutdownNow()
        super.onDestroy()
    }

    /**
     * Without a read permission the system lists only the photos this installation saved.
     * Clearing the app's data (or reinstalling it) makes the earlier ones nobody's: they
     * stay in the phone's gallery, but this album can list them again only with the right
     * to read photos.
     */
    private fun readPermission() =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun canReadAll() = checkSelfPermission(readPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun askToRead() {
        if (shouldShowRequestPermissionRationale(readPermission()) || !getSharedPreferences("gallery", Context.MODE_PRIVATE)
                .getBoolean("read_asked", false)) {
            getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().putBoolean("read_asked", true).apply()
            requestPermissions(arrayOf(readPermission()), READ_REQUEST)
        } else // refused for good: only the app's settings page can grant it now
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != READ_REQUEST) return
        if (canReadAll()) restoreBanner.visibility = View.GONE
        reload()
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
        loader.later {
            val list = Photos.list(this, favoritesOnly)
            runOnUiThread {
                all = list
                applyFilter()
            }
        }
    }

    /** The chips from what is in the album, then the grid for the chosen filter. */
    private fun applyFilter() {
        val films = all.map { Photos.filmOf(it) }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        if (film != null && film !in films) film = null
        photos = Photos.filter(all, film, privateOnly)
        selected.retainAll(photos.map { it.uri }.toSet())

        chips.removeAllViews()
        fun add(text: String, on: Boolean, onClick: () -> Unit) =
            chips.addView(chip(text, on, onClick), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        add("全部", !favoritesOnly && film == null && !privateOnly) { favoritesOnly = false; film = null; privateOnly = false; reload() }
        add("收藏", favoritesOnly) { favoritesOnly = !favoritesOnly; reload() }
        for (f in films) add(if (f == "直出") f else CanisterView.displayName(f), film == f) { film = if (film == f) null else f; applyFilter() }
        if (all.any { it.file != null }) add("仅本应用", privateOnly) { privateOnly = !privateOnly; applyFilter() }

        val roll = getSharedPreferences("roll", Context.MODE_PRIVATE)
        subtitle.text = "ROLL %02d · %d EXP".format(roll.getInt("roll", 1), photos.size)
        empty.text = when {
            all.isEmpty() && favoritesOnly -> "还没有收藏\n在查看照片时点 ♥ 收藏"
            all.isEmpty() -> "还没有照片\n拍下的照片冲洗好后会出现在这里\n也可以点「导入」冲洗手机里的照片"
            photos.isEmpty() -> "这个筛选下没有照片"
            else -> ""
        }
        empty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        // a new filter (or the first fill) deals the sheet again from the top; coming back from a
        // photo, or a fresh photo arriving, refreshes in place so the scroll position stays put
        val key = Triple(favoritesOnly, film, privateOnly)
        val adapter = grid.adapter as? Adapter
        if (adapter == null || key != shownFilter) grid.adapter = Adapter() else adapter.notifyDataSetChanged()
        if (key != shownFilter) grid.scheduleLayoutAnimation()
        shownFilter = key
        refreshSelection()
        if (!coachShown && !getSharedPreferences("coach", Context.MODE_PRIVATE).getBoolean("gallery", false)) {
            coachShown = true
            grid.postDelayed({ startCoach() }, 700) // after the sheet is dealt
        }
    }

    private var shownFilter: Triple<Boolean, String?, Boolean>? = null
    private var coachShown = false
    private var coach: org.photoall.film.ui.Coach? = null

    /** The album's short tour: the import frame, then what a photo opens to. */
    private fun startCoach() {
        if (isFinishing || isDestroyed || !hasWindowFocus()) { coachShown = false; return }
        fun cell(position: Int): View? =
            if (grid.firstVisiblePosition <= position && position <= grid.lastVisiblePosition)
                grid.getChildAt(position - grid.firstVisiblePosition) else null
        val steps = listOf(
            org.photoall.film.ui.Coach.Step("旧照片，也能重冲",
                "第一格是暗房的入口：选一张手机里的照片，\n配上胶卷、相纸与风格，再冲一遍。",
                target = { cell(0) }, button = "下一步"),
            org.photoall.film.ui.Coach.Step("点开一张",
                "轻点照片细看；底栏「信息」里有直方图与拍摄档案，「边框」可添片边与日期。\n长按照片，进入多选。",
                target = { cell(1) }, waitFor = "open", gesture = org.photoall.film.ui.Coach.Gesture.TAP, button = "知道了"),
        )
        val coach = org.photoall.film.ui.Coach(this, steps) { _ ->
            this.coach = null
            getSharedPreferences("coach", Context.MODE_PRIVATE).edit().putBoolean("gallery", true).apply()
        }
        this.coach = coach
        addContentView(coach, android.view.ViewGroup.LayoutParams(-1, -1))
        coach.start()
    }

    /** The finger went down on [position] to select: it flips that frame and anchors the range. */
    private fun startDrag(position: Int) {
        dragAnchor = position
        dragLast = position
        dragBase = selected.toSet()
        dragAdding = photos[position].uri !in selected
        applyDrag(position)
        grid.postOnAnimation(edgeScroll)
    }

    // The anchor .. the frame under the finger, added to (or cleared from) the selection as it was.
    private fun applyDrag(position: Int) {
        val range = minOf(dragAnchor, position)..maxOf(dragAnchor, position)
        val uris = range.map { photos[it].uri }
        selected.clear()
        selected += if (dragAdding) dragBase + uris else dragBase - uris.toSet()
        refreshSelection()
    }

    private fun extendDrag(x: Float, y: Float, keepX: Boolean = false) {
        var cell = grid.pointToPosition(x.toInt(), y.toInt())
        if (cell == AbsListView.INVALID_POSITION && keepX) cell = grid.pointToPosition(grid.width / 2, y.toInt())
        if (cell == AbsListView.INVALID_POSITION) return
        val pos = (cell - 1).coerceAtLeast(0) // the import cell counts as the first frame
        if (pos == dragLast) return
        dragLast = pos
        applyDrag(pos)
        haptics.play(org.photoall.film.ui.Haptics.Event.TICK)
    }

    /** The grid's touches: while a drag-select runs it takes them (no scrolling, no taps). */
    private fun dragTouch(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                downPos = grid.pointToPosition(e.x.toInt(), e.y.toInt()).let { if (it > 0) it - 1 else AbsListView.INVALID_POSITION }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                fingerY = e.y
                if (dragAnchor >= 0) {
                    extendDrag(e.x, e.y)
                    return true
                }
                // already selecting: a sideways drag from a frame starts a range
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                val dx = kotlin.math.abs(e.x - downX)
                if (selecting && downPos != AbsListView.INVALID_POSITION && dx > slop && dx > kotlin.math.abs(e.y - downY) * 1.5f) {
                    grid.cancelPendingInputEvents()
                    startDrag(downPos)
                    // the grid must not take the rest of the gesture as a scroll or a tap
                    val cancel = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    grid.onTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragAnchor >= 0) {
                    val wasLongPress = dragLast == dragAnchor
                    dragAnchor = -1
                    grid.removeCallbacks(edgeScroll)
                    // a long press lifted on the same frame is just that frame: let the grid end it;
                    // after a drag the grid gets a cancel, so it neither taps nor flings
                    if (wasLongPress) return false
                    val cancel = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    grid.onTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
            }
        }
        return false
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
        if (dragAnchor >= 0) grid.invalidateViews() else (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
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
        DeleteConfirm.show(this, chosen) {
            loader.later {
                val request = Photos.trash(this, chosen)
                runOnUiThread { if (request != null) ask(request) else { endSelection(); reload() } }
            }
        }
    }

    // The system asks the user for photos this app may not change on its own.
    @Suppress("DEPRECATION")
    private fun ask(request: PendingIntent) = startIntentSenderForResult(request.intentSender, 1, null, 0, 0, 0)

    /** One frame: the picture (3:4), and under it the frame number and the film. */
    private inner class Adapter : BaseAdapter() {
        // cell 0 is the import frame; the photos follow it
        override fun getCount() = photos.size + 1
        override fun getItem(position: Int): Any = if (position == 0) IMPORT else photos[position - 1]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (position == 0) 1 else 0

        override fun getView(cellIndex: Int, convertView: View?, parent: ViewGroup): View {
            // the grid measures its first cell before it has a width: use the screen's then
            val gridWidth = if (grid.width > 0) grid.width else resources.displayMetrics.widthPixels
            val width = (gridWidth - grid.paddingLeft - grid.paddingRight - 2 * grid.horizontalSpacing) / 3
            if (cellIndex == 0) return importCell(convertView, width)
            val position = cellIndex - 1
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(FrameLayout(this@GalleryActivity).apply {
                    addView(ImageView(this@GalleryActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Style.SURFACE)
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) =
                                outline.setRoundRect(0, 0, view.width, view.height, dp(3).toFloat())
                        }
                    }, FrameLayout.LayoutParams(-1, -1))
                    addView(ImageView(this@GalleryActivity), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END)
                        .apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }) // favourite / selection mark
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(LinearLayout(this@GalleryActivity).apply {
                    setPadding(dp(1), dp(4), dp(1), 0)
                    addView(edgeText(), LinearLayout.LayoutParams(-2, -2))
                    addView(View(this@GalleryActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(edgeText().apply {
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        isSingleLine = true
                    }, LinearLayout.LayoutParams(-2, -2))
                })
            }
            cell.layoutParams = AbsListView.LayoutParams(width, width * 4 / 3 + dp(18))
            val frame = cell.getChildAt(0) as FrameLayout
            val image = frame.getChildAt(0) as ImageView
            val mark = frame.getChildAt(1) as ImageView
            val edge = cell.getChildAt(1) as LinearLayout
            val photo = photos[position]
            val isSelected = photo.uri in selected
            image.animate().cancel()
            image.alpha = if (selecting && !isSelected) 0.45f else 1f
            mark.setImageDrawable(when {
                selecting -> Icon(Icon.Kind.CHECK, if (isSelected) Style.ACCENT else Color.WHITE, 2f, filled = isSelected)
                photo.favorite -> Dot(Style.ACCENT)
                else -> null
            })
            // Newest first, numbered like a roll: the newest frame has the highest number.
            (edge.getChildAt(0) as TextView).text = "${photos.size - position}"
            val name = Photos.filmOf(photo)
            (edge.getChildAt(2) as TextView).apply {
                // the film gives way to the frame number: cut short, never overlapping it
                maxWidth = width - dp(30)
                text = (if (name == "直出" || name.isEmpty()) name else CanisterView.displayName(name)).uppercase()
            }
            // keyed by size too: a photo still being written gets a new key once it is complete
            val key = "${photo.uri}#${photo.bytes}"
            image.tag = key
            val cached = cache.get(key)
            image.setImageBitmap(cached)
            if (cached == null) loader.later {
                val thumb = Photos.thumbnail(this@GalleryActivity, photo) ?: return@later
                cache.put(key, thumb)
                runOnUiThread {
                    if (image.tag != key) return@runOnUiThread
                    // frames develop in: a short fade as each thumbnail arrives, to the
                    // frame's own brightness (never to wherever an earlier fade had got to)
                    val target = if (selecting && photo.uri !in selected) 0.45f else 1f
                    image.animate().cancel()
                    image.setImageBitmap(thumb)
                    image.alpha = 0f
                    image.animate().alpha(target).setDuration(180).start()
                }
            }
            return cell
        }
    }

    /** An empty frame with a plus: the user's own pictures, developed like a shot. */
    private fun importCell(convertView: View?, width: Int): View {
        val cell = convertView ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(Style.SURFACE)
                    setStroke(dp(1), Style.TEXT_3, dp(5).toFloat(), dp(4).toFloat())
                }
                addView(TextView(this@GalleryActivity).apply {
                    text = "+"
                    textSize = 30f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(Style.ACCENT)
                }, LinearLayout.LayoutParams(-2, -2))
                addView(TextView(this@GalleryActivity).apply {
                    text = "导入冲洗"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(Style.ACCENT)
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(-2, -2))
                addView(TextView(this@GalleryActivity).apply {
                    text = "选胶卷 · 相纸 · LUT"
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(Style.TEXT_2)
                    setPadding(0, dp(3), 0, 0)
                }, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(this@GalleryActivity).apply {
                setPadding(dp(1), dp(4), dp(1), 0)
                addView(edgeText().apply { text = "IMPORT" }, LinearLayout.LayoutParams(-2, -2))
            })
        }
        cell.layoutParams = AbsListView.LayoutParams(width, width * 4 / 3 + dp(18))
        cell.alpha = if (selecting) 0.35f else 1f
        return cell
    }

    private fun edgeText() = TextView(this).apply {
        textSize = 9f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Style.ACCENT)
        letterSpacing = 0.04f
        maxLines = 1
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun iconButton(kind: Icon.Kind, description: String, onClick: () -> Unit) = ImageView(this).apply {
        contentDescription = description
        setImageDrawable(Icon(kind, Style.TEXT))
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = Style.surface(this@GalleryActivity, fill = Color.TRANSPARENT, radiusDp = 24f)
        setOnClickListener { onClick() }
    }

    /**
     * The way to the light table: a small rounded tablet with its name, dressed quietly after the
     * theme in use — a cartridge's yellow with its black band, a safelight's red, a print's white
     * with crop marks, engraved brass, brushed silver; for the camera styles a gilt double rule
     * (光), chrome on leatherette (经典), an inked sticker (漫), ink with an ultramarine stroke (风).
     */
    private class LightboxEntry(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
        private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * resources.displayMetrics.scaledDensity
            textAlign = android.graphics.Paint.Align.CENTER; letterSpacing = 0.12f
        }
        private val r = android.graphics.RectF()
        /** Which face: 0-4 the themes, 5.. the camera styles. */
        private val face = if (org.photoall.film.ui.Skin.active) 4 + org.photoall.film.ui.Skin.kind else Style.themeIndex.coerceIn(0, 4)

        init {
            isClickable = true
            contentDescription = "观片台"
            Style.pressable(this)
        }

        private fun gradient(top: Int, mid: Int, bottom: Int) = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom,
            intArrayOf(top, mid, bottom), null, android.graphics.Shader.TileMode.CLAMP)

        private fun text(c: android.graphics.Canvas, color: Int, face: Typeface, shift: Float = 0f, size: Float = 1f): Float {
            label.typeface = face; label.color = color
            label.textSize = 14f * resources.displayMetrics.scaledDensity * size
            val fm = label.fontMetrics
            val y = r.centerY() - (fm.ascent + fm.descent) / 2
            c.drawText("观片台", r.centerX() + shift, y, label)
            return y
        }

        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            r.set(0f, 0f, w, h)
            val rad = 9 * d
            p.shader = null; line.shader = null
            val bold = Typeface.create("sans-serif", Typeface.BOLD)
            when (face) {
                0 -> { // 暗盒: the cartridge's yellow, its black band at one end
                    p.shader = gradient(0xfff0c45a.toInt(), 0xffe3b341.toInt(), 0xffc99a2e.toInt()); c.drawRoundRect(r, rad, rad, p); p.shader = null
                    c.save(); c.clipPath(android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) })
                    p.color = 0xff15120e.toInt(); c.drawRect(0f, 0f, 7 * d, h, p)
                    p.color = 0xffc8321f.toInt(); c.drawRect(7 * d, 0f, 8.6f * d, h, p)
                    c.restore()
                    text(c, 0xff15120e.toInt(), bold, shift = 4 * d)
                }
                1 -> { // 暗房红灯: near black, a red rim, a red word, a lamp lit at the left
                    p.color = 0xff1a0605.toInt(); c.drawRoundRect(r, rad, rad, p)
                    line.color = 0xff8a1a12.toInt(); line.strokeWidth = 1 * d
                    c.drawRoundRect(r.left + 0.5f * d, r.top + 0.5f * d, r.right - 0.5f * d, r.bottom - 0.5f * d, rad, rad, line)
                    val lx = 10 * d
                    p.shader = android.graphics.RadialGradient(lx, h / 2, 8 * d, intArrayOf(0x88ff3a22.toInt(), 0x00ff3a22), null, android.graphics.Shader.TileMode.CLAMP)
                    c.drawCircle(lx, h / 2, 8 * d, p); p.shader = null
                    p.color = 0xffff5a3c.toInt(); c.drawCircle(lx, h / 2, 2.6f * d, p)
                    text(c, 0xffff6a4d.toInt(), bold, shift = 5 * d)
                }
                2 -> { // 相纸: a print's white, a fine ink edge, crop marks at the corners
                    p.color = 0xfffbf8f1.toInt(); c.drawRoundRect(r, rad * 0.5f, rad * 0.5f, p)
                    line.color = 0xff2a2724.toInt(); line.strokeWidth = 1 * d
                    c.drawRoundRect(r.left + 0.5f * d, r.top + 0.5f * d, r.right - 0.5f * d, r.bottom - 0.5f * d, rad * 0.5f, rad * 0.5f, line)
                    line.color = 0xff8a8170.toInt(); line.strokeWidth = 0.7f * d
                    val m = 4 * d; val l = 4 * d
                    for ((x, y, sx, sy) in listOf(floatArrayOf(m, m, 1f, 1f), floatArrayOf(w - m, m, -1f, 1f), floatArrayOf(m, h - m, 1f, -1f), floatArrayOf(w - m, h - m, -1f, -1f)).map { it.toList() }) {
                        c.drawLine(x, y, x + sx * l, y, line); c.drawLine(x, y, x, y + sy * l, line)
                    }
                    text(c, 0xff2a2724.toInt(), Typeface.create(Typeface.SERIF, Typeface.BOLD))
                }
                3 -> { // 黄铜: brass, the word engraved (a light edge under its strokes)
                    p.shader = gradient(0xffe2c27a.toInt(), 0xffb08a42.toInt(), 0xff7c5f28.toInt()); c.drawRoundRect(r, rad, rad, p); p.shader = null
                    line.color = 0xff4a3818.toInt(); line.strokeWidth = 1 * d
                    c.drawRoundRect(r.left + 0.5f * d, r.top + 0.5f * d, r.right - 0.5f * d, r.bottom - 0.5f * d, rad, rad, line)
                    label.typeface = bold
                    c.save(); c.translate(0f, 0.8f * d); text(c, 0x66fff2cc, bold); c.restore()
                    text(c, 0xff2a1e0a.toInt(), bold)
                }
                4 -> { // 冷银: brushed silver, cold blue words
                    p.shader = gradient(0xffeef2f5.toInt(), 0xffb4bbc3.toInt(), 0xff8a929b.toInt()); c.drawRoundRect(r, rad, rad, p); p.shader = null
                    c.save(); c.clipPath(android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) })
                    line.strokeWidth = 0.5f * d
                    val rnd = java.util.Random(4)
                    var y = 0f
                    while (y < h) { line.color = if (rnd.nextBoolean()) 0x33ffffff else 0x14000000; c.drawLine(0f, y, w, y, line); y += 1.3f * d }
                    c.restore()
                    line.color = 0xff5b636c.toInt(); line.strokeWidth = 1 * d
                    c.drawRoundRect(r.left + 0.5f * d, r.top + 0.5f * d, r.right - 0.5f * d, r.bottom - 0.5f * d, rad, rad, line)
                    text(c, 0xff1d3c66.toInt(), bold)
                }
                5 -> { // 光: bone, a gilt double rule, a serif italic
                    p.color = 0xffefebe1.toInt(); c.drawRoundRect(r, rad * 0.4f, rad * 0.4f, p)
                    line.color = 0xffa88435.toInt(); line.strokeWidth = 1.2f * d
                    c.drawRoundRect(r.left + 0.6f * d, r.top + 0.6f * d, r.right - 0.6f * d, r.bottom - 0.6f * d, rad * 0.4f, rad * 0.4f, line)
                    line.strokeWidth = 0.6f * d
                    c.drawRoundRect(r.left + 3 * d, r.top + 3 * d, r.right - 3 * d, r.bottom - 3 * d, rad * 0.2f, rad * 0.2f, line)
                    text(c, 0xff161514.toInt(), Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC))
                }
                6 -> { // 经典: leatherette in a satin chrome edge, the word light and narrow
                    p.shader = gradient(0xffcfcec9.toInt(), 0xff6a6965.toInt(), 0xff3a3a38.toInt()); c.drawRoundRect(r, rad, rad, p); p.shader = null
                    val i = 2 * d
                    p.color = 0xff1b1b1c.toInt(); c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, rad - i, rad - i, p)
                    p.color = 0x22ffffff
                    val rnd = java.util.Random(5)
                    repeat(120) { c.drawCircle(i + rnd.nextFloat() * (w - 2 * i), i + rnd.nextFloat() * (h - 2 * i), 0.5f * d, p) }
                    text(c, 0xffe4e1d8.toInt(), Typeface.create("sans-serif-condensed", Typeface.BOLD))
                }
                7 -> { // 漫: white, a heavy navy line with its shadow offset
                    val o = 2.4f * d
                    p.color = 0xff1b1464.toInt(); c.drawRoundRect(r.left + o, r.top + o, r.right, r.bottom, rad, rad, p)
                    val f = android.graphics.RectF(r.left, r.top, r.right - o, r.bottom - o)
                    p.color = 0xffffffff.toInt(); c.drawRoundRect(f, rad, rad, p)
                    line.color = 0xff1b1464.toInt(); line.strokeWidth = 2 * d
                    c.drawRoundRect(f.left + d, f.top + d, f.right - d, f.bottom - d, rad - d, rad - d, line)
                    label.typeface = Typeface.create("sans-serif-black", Typeface.ITALIC); label.color = 0xff1b1464.toInt()
                    label.textSize = 14f * resources.displayMetrics.scaledDensity
                    val fm = label.fontMetrics
                    c.drawText("观片台", f.centerX(), f.centerY() - (fm.ascent + fm.descent) / 2, label)
                }
                9 -> { // 旁轴: vulcanite in a satin chrome edge, a fine engraved word, the display's red dot
                    p.shader = gradient(0xffe6e5e0.toInt(), 0xff8a8984.toInt(), 0xff4a4a47.toInt()); c.drawRoundRect(r, rad, rad, p); p.shader = null
                    val i = 1.6f * d
                    p.color = 0xff121212.toInt(); c.drawRoundRect(r.left + i, r.top + i, r.right - i, r.bottom - i, rad - i, rad - i, p)
                    val rnd = java.util.Random(9)
                    repeat(160) {
                        val x = i + rnd.nextFloat() * (w - 2 * i); val y = i + rnd.nextFloat() * (h - 2 * i)
                        p.color = 0x18ffffff; c.drawCircle(x, y, 0.6f * d, p)
                    }
                    text(c, 0xffeceae4.toInt(), Typeface.create("sans-serif-light", Typeface.NORMAL), shift = 3 * d)
                    p.color = 0xffff2b1c.toInt()
                    c.drawCircle(r.left + 10 * d, r.centerY(), 2.2f * d, p)
                }
                else -> { // 风: bone paper, a fine ink line, an ultramarine stroke under the word
                    p.color = 0xffefede6.toInt(); c.drawRoundRect(r, rad, rad, p)
                    line.color = 0xff121212.toInt(); line.strokeWidth = 1 * d
                    c.drawRoundRect(r.left + 0.5f * d, r.top + 0.5f * d, r.right - 0.5f * d, r.bottom - 0.5f * d, rad, rad, line)
                    val y = text(c, 0xff121212.toInt(), Typeface.create(Typeface.SERIF, Typeface.BOLD), size = 0.95f)
                    val tw = label.measureText("观片台")
                    p.color = 0xff1f2bd0.toInt()
                    c.drawRoundRect(r.centerX() - tw * 0.32f, y + 4f * d, r.centerX() + tw * 0.32f, y + 5.5f * d, 0.8f * d, 0.8f * d, p)
                }
            }
        }
    }

    private fun textButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        isSingleLine = true
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        Style.textButton(this, 14f)
        setOnClickListener { onClick() }
    }

    /** A filter chip: filled in the text colour when on, outlined when off. */
    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        isSingleLine = true
        textSize = 13f
        stateListAnimator = null
        Style.compact(this, 14f)
        // as tall as its words need at any font size (a fixed 32 dp cut the bottom off on large type)
        includeFontPadding = true
        setPadding(paddingLeft, dp(5), paddingRight, dp(5))
        minHeight = dp(32); minimumHeight = dp(32)
        Style.pressable(this)
        background = Style.surface(this@GalleryActivity, fill = if (on) Style.TEXT else Color.TRANSPARENT, radiusDp = 16f,
            stroke = if (on) 0 else Style.HAIRLINE)
        setTextColor(if (on) Style.BG else Style.TEXT_2)
        typeface = if (on) Style.medium else Style.regular
        setOnClickListener { onClick() }
    }

    /** The film base's top edge: sprocket holes. */
    private class SprocketStrip(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.SURFACE_2 }
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val pitch = 9 * d
            val w = 5.5f * d
            var x = 3 * d
            while (x < width) {
                rect.set(x, height * 0.2f, x + w, height * 0.8f)
                canvas.drawRoundRect(rect, d, d, paint)
                x += pitch
            }
        }
    }

    /** The favourite mark: a small accent dot. */
    private class Dot(private val color: Int) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            paint.color = color
            canvas.drawCircle(bounds.exactCenterX() + bounds.width() * 0.25f, bounds.exactCenterY() - bounds.height() * 0.25f,
                bounds.width() * 0.17f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    companion object {
        // Film base and edge print: the same in every theme.
        const val FILM_BASE = 0xff121110.toInt()
        private const val READ_REQUEST = 3
        private val IMPORT = Any()
        const val FILM_INK = 0xffe2a33c.toInt()
        const val FILM_INK_DIM = 0xff8a7550.toInt()
    }
}
