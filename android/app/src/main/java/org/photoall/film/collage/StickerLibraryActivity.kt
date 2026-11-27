package org.photoall.film.collage

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import org.photoall.film.collage.Glassy.dp
import org.photoall.film.collage.Glassy.lp
import org.photoall.film.collage.Glassy.space
import java.util.concurrent.Executors

/**
 * 取景贴纸 (the sticker library), as a sheet over the stickers' own colours: folders across
 * the top (我的, 自动 — those 一键拼贴 cut —, 收藏, 最近) with their stickers peeking out, the folder's stickers in a free
 * grid under them (each on its own shadow, no tiles), 从相册 and 拍照 at the foot. A tap opens
 * the sticker full screen ([StickerEditActivity]); with [EXTRA_PICK] it hands the sticker back.
 */
class StickerLibraryActivity : androidx.activity.ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private enum class Folder(val label: String) { MINE("我的"), AUTO("自动"), FAV("收藏"), RECENT("最近") }
    private var folder = Folder.MINE
    private lateinit var folders: LinearLayout
    private lateinit var grid: LinearLayout
    private val thumbs = HashMap<String, Bitmap>()
    /** Long press: choosing several at once, to favour or delete them together. */
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var all: List<Stickers.Sticker> = emptyList()
    private lateinit var title: TextView
    private lateinit var bottomBar: View
    private lateinit var selectBar: LinearLayout
    private lateinit var scroll: ScrollView
    /** The cells shown, by sticker: the selection is painted on them in place (no rebuild). */
    private val cells = LinkedHashMap<String, FrameLayout>()
    /** A long press goes on as a drag: every cell the finger crosses is chosen (or let go, if it started on a chosen one). */
    private var dragging = false
    private var dragAdds = true
    private var lastDragId: String? = null
    private val picking get() = intent.getBooleanExtra(EXTRA_PICK, false)
    /** 从相册 / 拍照 while picking: the sticker made comes straight back, and on to the asker. */
    private val made = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getStringExtra(EXTRA_ID) ?: return@registerForActivityResult
        worker.execute { Stickers.touch(this, id) }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, id))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Glassy.apply(this)
        super.onCreate(savedInstanceState)
        val a = this
        folders = Glassy.row(a) {}
        grid = Glassy.vertical(a) {}
        val top = FrameLayout(a).apply {
            setPadding(dp(a, 16f), dp(a, 4f), dp(a, 16f), dp(a, 8f))
            // the sheet's grabber
            addView(View(a).apply { background = android.graphics.drawable.GradientDrawable().apply { setColor(0x59ffffff); cornerRadius = 99f } },
                FrameLayout.LayoutParams(dp(a, 36f), dp(a, 5f), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(Glassy.pill(a, "关闭") { finish() }.apply { textSize = 13f },
                FrameLayout.LayoutParams(-2, dp(a, 32f), Gravity.START or Gravity.CENTER_VERTICAL).apply { topMargin = dp(a, 10f) })
            title = Glassy.text(a, if (picking) "选一张贴纸" else "取景贴纸", 15f, Glassy.TEXT, Glassy.semibold)
            addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dp(a, 10f) })
        }
        bottomBar = Glassy.row(a) {
            setPadding(dp(a, 16f), dp(a, 8f), dp(a, 16f), dp(a, 12f))
            val back = intent.getStringExtra(Flows.EXTRA_RETURN)
            addView(Glassy.pill(a, "从相册", "▣") {
                val i = Intent(a, StickerMakerActivity::class.java)
                if (picking) made.launch(i.putExtra(Flows.EXTRA_RETURN, back ?: "用这张")) else startActivity(i)
            }.apply { textSize = 13f }, lp(0, dp(a, 40f), 1f))
            addView(View(a), lp(dp(a, 10f), 1))
            addView(Glassy.pill(a, "拍照", "◉") {
                val i = Intent(a, CollageCameraActivity::class.java).putExtra(CollageCameraActivity.EXTRA_FOR_STICKER, true)
                if (picking) made.launch(i.putExtra(Flows.EXTRA_RETURN, back ?: "用这张")) else startActivity(i)
            }.apply { textSize = 13f }, lp(0, dp(a, 40f), 1f))
        }
        val body = Glassy.vertical(a) {
            addView(top, lp(-1, dp(a, 58f)))
            addView(ScrollView(a).also { scroll = it }.apply {
                isVerticalScrollBarEnabled = false
                addView(Glassy.vertical(a) {
                    setPadding(dp(a, 14f), dp(a, 6f), dp(a, 14f), dp(a, 20f))
                    addView(folders, lp(-1, dp(a, 96f)))
                    space(dp(a, 18f))
                    addView(grid, lp(-1, -2))
                })
            }, lp(-1, 0, 1f))
            addView(bottomBar, lp(-1, -2))
            selectBar = Glassy.row(a) {
                setPadding(dp(a, 16f), dp(a, 8f), dp(a, 16f), dp(a, 12f))
                addView(Glassy.pill(a, "全选") {
                    val ids = shown(all, folder).map { it.id }
                    if (selected.containsAll(ids)) selected.clear() else selected += ids
                    paintSelection()
                }.apply { textSize = 13f }, lp(0, dp(a, 40f), 1f))
                addView(View(a), lp(dp(a, 8f), 1))
                addView(Glassy.pill(a, "收藏") { favSelected() }.apply { textSize = 13f }, lp(0, dp(a, 40f), 1f))
                addView(View(a), lp(dp(a, 8f), 1))
                addView(Glassy.pill(a, "删除") { deleteSelected() }.apply { textSize = 13f; setTextColor(Glassy.RED) }, lp(0, dp(a, 40f), 1f))
                addView(View(a), lp(dp(a, 8f), 1))
                addView(Glassy.pill(a, "完成", strong = true) { endSelect() }.apply { textSize = 13f }, lp(0, dp(a, 40f), 1f))
                visibility = View.GONE
            }
            addView(selectBar, lp(-1, -2))
        }
        setContentView(Glassy.root(a, body))
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun refresh() {
        val a = this
        worker.execute {
            val all = Stickers.list(a)
            thumbs.keys.retainAll(all.map { it.id }.toSet())
            for (s in all) if (thumbs[s.id] == null) BitmapFactory.decodeFile(Stickers.file(a, s.id).path,
                BitmapFactory.Options().apply { inSampleSize = 2 })?.let { thumbs[s.id] = it }
            val ground = StickerViews.ground(a, all.take(12).mapNotNull { thumbs[it.id] })
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                this.all = all
                selected.retainAll(all.map { it.id }.toSet())
                window.setBackgroundDrawable(ground)
                fillFolders(all); fillGrid(all)
            }
        }
    }

    private fun shown(all: List<Stickers.Sticker>, f: Folder) = when (f) {
        Folder.MINE -> all.filter { !it.auto }.sortedByDescending { it.created }
        Folder.AUTO -> all.filter { it.auto }.sortedByDescending { it.created }
        Folder.FAV -> all.filter { it.fav }.sortedByDescending { it.used }
        Folder.RECENT -> all.sortedByDescending { it.used }.take(24)
    }

    private fun fillFolders(all: List<Stickers.Sticker>) {
        val a = this
        folders.removeAllViews()
        for ((i, f) in Folder.entries.withIndex()) {
            val items = shown(all, f)
            folders.addView(StickerViews.Folder(a, f.label, items.size, items.take(3).mapNotNull { thumbs[it.id] }, f == folder).apply {
                org.photoall.film.ui.Style.pressable(this)
                setOnClickListener { if (folder != f) { folder = f; fillFolders(all); fillGrid(all) } }
            }, lp(0, -1, 1f) { if (i > 0) marginStart = dp(a, 12f) })
        }
    }

    private fun fillGrid(all: List<Stickers.Sticker>) {
        val a = this
        grid.removeAllViews()
        cells.clear()
        val list = shown(all, folder)
        if (list.isEmpty()) {
            grid.addView(Glassy.text(a, when (folder) {
                Folder.FAV -> "还没有收藏的贴纸"
                Folder.AUTO -> "还没有：「一键拼贴」抠出的贴纸会收在这里"
                else -> "还没有贴纸：从相册选一张照片，抠出主体"
            }, 14f, Glassy.TEXT_2).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(a, 60f), 0, 0)
            }, lp(-1, -2))
            return
        }
        for ((r, row) in list.chunked(5).withIndex()) grid.addView(Glassy.row(a) {
            for (s in row) addView(FrameLayout(a).apply {
                addView(ImageView(a).apply {
                    setImageBitmap(thumbs[s.id]); scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(a, 3f), dp(a, 3f), dp(a, 3f), dp(a, 3f))
                }, FrameLayout.LayoutParams(-1, -1))
                if (s.fav) addView(Glassy.text(a, "★", 10f, Glassy.YELLOW), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
                // the tick of a chosen one, shown while selecting
                addView(Glassy.text(a, "✓", 11f, 0xff1c1c1e.toInt(), Glassy.bold).apply {
                    tag = "tick"; gravity = Gravity.CENTER; visibility = View.GONE
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Glassy.YELLOW) }
                }, FrameLayout.LayoutParams(dp(a, 18f), dp(a, 18f), Gravity.BOTTOM or Gravity.END))
                cells[s.id] = this
                decorate(this, s.id)
                org.photoall.film.ui.Style.pressable(this)
                setOnClickListener {
                    when {
                        selecting -> toggle(s.id)
                        picking -> handBack(s)
                        else -> startActivity(Intent(a, StickerEditActivity::class.java).putExtra(StickerEditActivity.EXTRA_ID, s.id))
                    }
                }
                setOnLongClickListener {
                    // selecting begins (or goes on) here; the finger can now sweep over more
                    if (!selecting) { selecting = true; selected.clear(); paintBars() }
                    dragAdds = s.id !in selected
                    set(s.id, dragAdds)
                    dragging = true; lastDragId = s.id
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    true
                }
                if (!selecting) Glassy.rise(this, 30L * r)
            }, lp(0, dp(a, 68f), 1f))
            repeat(5 - row.size) { addView(View(a), lp(0, 1, 1f)) }
        }, lp(-1, -2) { bottomMargin = dp(a, 10f) })
    }

    /** The title and the bars as the selection now stands. */
    private fun paintBars() {
        title.text = if (selecting) (if (selected.isEmpty()) "选择贴纸" else "已选 ${selected.size} 张") else if (picking) "选一张贴纸" else "取景贴纸"
        bottomBar.visibility = if (selecting) View.GONE else View.VISIBLE
        selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
    }

    /** Every cell and the bars painted as the selection stands, in place. */
    private fun paintSelection() {
        paintBars()
        for ((id, cell) in cells) decorate(cell, id)
    }

    /** One cell as chosen or not: full strength, a ring and a tick; the others a little faded while selecting. */
    private fun decorate(cell: FrameLayout, id: String) {
        val on = selecting && id in selected
        cell.animate().alpha(if (!selecting || on) 1f else 0.5f).setDuration(140).start()
        cell.foreground = if (on) android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(this@StickerLibraryActivity, 14f).toFloat(); setStroke(dp(this@StickerLibraryActivity, 2f), Glassy.YELLOW)
        } else null
        cell.findViewWithTag<View>("tick")?.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun toggle(id: String) = set(id, id !in selected)

    /** [id] chosen or let go; its cell gives a small pop and a tick of the hand, the rest stay still. */
    private fun set(id: String, on: Boolean) {
        val changed = if (on) selected.add(id) else selected.remove(id)
        paintBars()
        val cell = cells[id] ?: return
        decorate(cell, id)
        if (!changed) return
        cell.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        cell.animate().cancel()
        cell.scaleX = if (on) 0.82f else 1.08f; cell.scaleY = cell.scaleX
        cell.animate().scaleX(1f).scaleY(1f).alpha(if (on) 1f else 0.5f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
    }

    /** The drag after a long press: the cells under the finger, the list scrolled at its edges. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!dragging) return super.dispatchTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                val hit = cells.entries.firstOrNull { (_, v) ->
                    val r = android.graphics.Rect(); v.getGlobalVisibleRect(r) && r.contains(e.rawX.toInt(), e.rawY.toInt())
                }?.key
                if (hit != null && hit != lastDragId) { lastDragId = hit; set(hit, dragAdds) }
                // near the list's top or bottom edge: it scrolls on
                val r = android.graphics.Rect(); scroll.getGlobalVisibleRect(r)
                val edge = dp(this, 56f)
                if (e.rawY < r.top + edge) scroll.scrollBy(0, -dp(this, 10f))
                else if (e.rawY > r.bottom - edge) scroll.scrollBy(0, dp(this, 10f))
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                dragging = false; lastDragId = null
                // the long-pressed cell never saw its finger lift: it is told the touch ended
                super.dispatchTouchEvent(android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL })
            }
        }
        return true
    }

    private fun endSelect() { selecting = false; selected.clear(); paintSelection() }

    @Deprecated("Back: out of selecting first")
    override fun onBackPressed() { if (selecting) endSelect() else super.onBackPressed() }

    /** Favour the chosen (or, all of them favourites already, unfavour them). */
    private fun favSelected() {
        val chosen = all.filter { it.id in selected }
        if (chosen.isEmpty()) return
        val fav = !chosen.all { it.fav }
        worker.execute {
            for (st in Stickers.list(this)) if (st.id in selected && st.fav != fav) Stickers.update(this, st.apply { this.fav = fav })
            runOnUiThread { endSelect(); refresh() }
        }
    }

    /** Delete the chosen, once asked. */
    private fun deleteSelected() {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("删除 ${ids.size} 张贴纸？")
            .setMessage("贴纸库里的这些贴纸会被删掉，不能恢复。已经存好的拼贴不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                worker.execute { for (id in ids) Stickers.delete(this, id); runOnUiThread { endSelect(); refresh() } }
            }.show()
    }

    private fun handBack(s: Stickers.Sticker) {
        worker.execute { Stickers.touch(this, s.id) }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, s.id))
        finish()
    }

    companion object {
        const val EXTRA_PICK = "pick"
        const val EXTRA_ID = "id"
    }
}
