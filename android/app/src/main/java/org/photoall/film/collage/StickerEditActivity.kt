package org.photoall.film.collage

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.photoall.film.collage.Glassy.dp
import org.photoall.film.collage.Glassy.lp
import org.photoall.film.collage.Glassy.space
import java.util.concurrent.Executors

/**
 * 编辑贴纸, full screen over the sticker's own colours: the sticker large, its edge as a row
 * of glass keys, the edge's width with its percentage, 修整 (the brush on the cut-out),
 * favour, copy, delete, and on to a collage or the camera.
 */
class StickerEditActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var sticker: Stickers.Sticker
    private lateinit var big: ImageView
    private lateinit var edges: LinearLayout
    private lateinit var percent: TextView
    private lateinit var favKey: TextView
    private var editor: MaskEditor? = null
    private lateinit var stage: FrameLayout
    private lateinit var tools: View

    override fun onCreate(savedInstanceState: Bundle?) {
        Glassy.apply(this)
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        sticker = Stickers.list(this).firstOrNull { it.id == id } ?: run { finish(); return }
        val a = this
        big = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        stage = FrameLayout(a).apply { addView(big, FrameLayout.LayoutParams(-1, -1)) }
        edges = Glassy.row(a) { gravity = Gravity.CENTER }
        percent = Glassy.text(a, "", 11f, Glassy.TEXT_2)
        val width = SeekBar(a).apply {
            max = 100; progress = ((sticker.width - 0.4f) / 1.6f * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Glassy.TEXT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40ffffff)
            thumbTintList = android.content.res.ColorStateList.valueOf(Glassy.TEXT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) { percent.text = "$p%" }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) { sticker.width = 0.4f + b.progress / 100f * 1.6f; restyle() }
            })
        }
        percent.text = "${width.progress}%"
        favKey = action("", Glassy.YELLOW) { sticker.fav = !sticker.fav; paintFav(); worker.execute { Stickers.update(a, sticker) } }
        paintFav()
        tools = Glassy.vertical(a) {
            setPadding(dp(a, 20f), 0, dp(a, 20f), dp(a, 14f))
            addView(edges, lp(-1, dp(a, 64f)))
            space(dp(a, 12f))
            addView(Glassy.row(a) {
                addView(Glassy.text(a, "宽度", 12f, Glassy.TEXT_2), lp(dp(a, 36f), -2))
                addView(width, lp(0, -2, 1f))
                addView(percent, lp(dp(a, 40f), -2))
            })
            space(dp(a, 10f))
            addView(Glassy.row(a) {
                addView(action("✎ 修整", Glassy.TEXT) { openEditor() }, lp(0, dp(a, 40f), 1f))
                addView(favKey, lp(0, dp(a, 40f), 1f))
                addView(action("⧉ 复制", Glassy.TEXT) { worker.execute { Stickers.duplicate(a, sticker); runOnUiThread { finish() } } }, lp(0, dp(a, 40f), 1f))
                addView(action("删除", Glassy.RED) { worker.execute { Stickers.delete(a, sticker.id); runOnUiThread { finish() } } }, lp(0, dp(a, 40f), 1f))
            })
            space(dp(a, 10f))
            addView(Glassy.row(a) {
                addView(Glassy.pill(a, "去拼贴") {
                    Flows.anchored(a, Intent(a, CollageActivity::class.java).putExtra(CollageActivity.EXTRA_STICKER, sticker.id))
                }, lp(0, dp(a, 46f), 1f))
                addView(View(a), lp(dp(a, 10f), 1))
                addView(Glassy.pill(a, "贴到相机", strong = true) {
                    Flows.anchored(a, Intent(a, CollageCameraActivity::class.java).putExtra(CollageCameraActivity.EXTRA_STICKER, sticker.id))
                }, lp(0, dp(a, 46f), 1f))
            })
        }
        val body = Glassy.vertical(a) {
            addView(FrameLayout(a).apply {
                setPadding(dp(a, 16f), dp(a, 6f), dp(a, 16f), dp(a, 6f))
                addView(Glassy.text(a, "编辑贴纸", 15f, Glassy.TEXT, Glassy.semibold), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                addView(Glassy.pill(a, "完成") { if (editor != null) closeEditor(true) else finish() }.apply { textSize = 13f },
                    FrameLayout.LayoutParams(-2, dp(a, 34f), Gravity.END or Gravity.CENTER_VERTICAL))
            }, lp(-1, dp(a, 52f)))
            addView(Glassy.text(a, java.text.SimpleDateFormat("主体 MMdd HH:mm", java.util.Locale.CHINA).format(sticker.created), 12f, Glassy.TEXT_2).apply {
                gravity = Gravity.CENTER
            }, lp(-1, -2))
            addView(stage, lp(-1, 0, 1f) { setMargins(dp(a, 40f), dp(a, 12f), dp(a, 40f), dp(a, 12f)) })
            addView(tools, lp(-1, -2))
        }
        setContentView(Glassy.root(a, body))
        paintEdges()
        worker.execute {
            val b = Stickers.load(a, sticker.id)
            val ground = b?.let { StickerViews.ground(a, listOf(it)) }
            runOnUiThread {
                big.setImageBitmap(b)
                ground?.let { window.setBackgroundDrawable(it) }
                big.scaleX = 0.85f; big.scaleY = 0.85f; big.alpha = 0f
                big.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
            }
        }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    @Deprecated("Back: out of 修整 first")
    override fun onBackPressed() { if (editor != null) closeEditor(false) else super.onBackPressed() }

    private fun action(label: String, color: Int, run: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = Glassy.semibold; setTextColor(color)
        org.photoall.film.ui.Style.pressable(this); setOnClickListener { run() }
    }

    private fun paintFav() { favKey.text = if (sticker.fav) "★ 已收藏" else "☆ 收藏" }

    /** The edge styles as square glass keys, each with its name under it. */
    private fun paintEdges() {
        val a = this
        edges.removeAllViews()
        val marks = mapOf(Stickers.Edge.WHITE to "◯", Stickers.Edge.THICK to "◉", Stickers.Edge.TORN to "✺", Stickers.Edge.LINE to "◌", Stickers.Edge.NONE to "∅")
        for (e in Stickers.Edge.entries) edges.addView(Glassy.vertical(a) {
            gravity = Gravity.CENTER
            val on = e == sticker.edge
            background = Glassy.glass(a, 14f, if (on) 0x47ffffff else 0x17ffffff)
            addView(Glassy.text(a, marks[e] ?: "", 16f, Glassy.TEXT).apply { gravity = Gravity.CENTER }, lp(-1, -2))
            addView(Glassy.text(a, e.label, 10f, if (on) Glassy.TEXT else Glassy.TEXT_2).apply { gravity = Gravity.CENTER }, lp(-1, -2) { topMargin = dp(a, 3f) })
            org.photoall.film.ui.Style.pressable(this)
            setOnClickListener { if (sticker.edge != e) { sticker.edge = e; paintEdges(); restyle() } }
        }, lp(0, -1, 1f) { marginStart = dp(a, 4f); marginEnd = dp(a, 4f) })
    }

    private fun restyle() {
        val a = this
        val s = sticker
        worker.execute {
            Stickers.restyle(a, s)
            val b = Stickers.load(a, s.id)
            runOnUiThread { big.setImageBitmap(b) }
        }
    }

    /** 修整 on the sticker's cut-out: erase or bring back within it. */
    private fun openEditor() {
        val cut = BitmapFactory.decodeFile(Stickers.cutFile(this, sticker.id).path)?.copy(Bitmap.Config.ARGB_8888, true) ?: return
        cut.setHasAlpha(true)
        // the mask: the cut-out's own alpha; the picture: the cut-out made opaque again
        val mask = Bitmap.createBitmap(cut.width, cut.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(cut.width * cut.height); cut.getPixels(px, 0, cut.width, 0, 0, cut.width, cut.height)
        val m = IntArray(px.size) { (px[it] and 0xff000000.toInt()) or 0xffffff }
        mask.setPixels(m, 0, cut.width, 0, 0, cut.width, cut.height)
        for (i in px.indices) px[i] = px[i] or 0xff000000.toInt()
        val opaque = Bitmap.createBitmap(px, cut.width, cut.height, Bitmap.Config.ARGB_8888)
        val ed = MaskEditor(this, opaque, mask)
        editor = ed
        big.visibility = View.INVISIBLE
        stage.addView(ed, FrameLayout.LayoutParams(-1, -1))
        tools.visibility = View.GONE
    }

    private fun closeEditor(apply: Boolean) {
        val ed = editor ?: return
        editor = null
        stage.removeView(ed)
        big.visibility = View.VISIBLE
        tools.visibility = View.VISIBLE
        if (!apply) return
        val a = this
        worker.execute {
            val cut = BitmapFactory.decodeFile(Stickers.cutFile(a, sticker.id).path) ?: return@execute
            val opaque = cut.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(true) }
            val px = IntArray(opaque.width * opaque.height); opaque.getPixels(px, 0, opaque.width, 0, 0, opaque.width, opaque.height)
            for (i in px.indices) px[i] = px[i] or 0xff000000.toInt()
            opaque.setPixels(px, 0, opaque.width, 0, 0, opaque.width, opaque.height)
            val fresh = Segmenter.compose(opaque, ed.mask) ?: return@execute
            Stickers.cutFile(a, sticker.id).outputStream().use { fresh.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Stickers.restyle(a, sticker)
            val b = Stickers.load(a, sticker.id)
            runOnUiThread { big.setImageBitmap(b) }
        }
    }

    companion object { const val EXTRA_ID = "id" }
}
