package org.photoall.film.collage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.photoall.film.collage.Glassy.dp
import org.photoall.film.collage.Glassy.lp
import org.photoall.film.collage.Glassy.space

/**
 * 拼贴相机's home: shoot (the camera dressed in your stickers), make a sticker, lay out a
 * collage, the sticker library. A bento of glass cards.
 */
class CollageHomeActivity : Activity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        then(intent)
    }

    /** [Flows.anchored]: the screen to open over this one. */
    private fun then(i: Intent) {
        @Suppress("DEPRECATION") val next = i.getParcelableExtra<Intent>(EXTRA_THEN) ?: return
        i.removeExtra(EXTRA_THEN)
        startActivity(next)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Glassy.apply(this)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) window.decorView.post { then(intent) }
        val a = this
        val top = FrameLayout(a).apply {
            addView(Glassy.vertical(a) {
                addView(Glassy.text(a, "Photo All", 13f, Glassy.TEXT_2, Glassy.semibold))
                space(dp(a, 4f))
                addView(Glassy.text(a, "拼贴相机", 32f, Glassy.TEXT, Glassy.bold))
            })
            addView(Glassy.pill(a, "切换相机") { finish() }, FrameLayout.LayoutParams(-2, dp(a, 36f), Gravity.END or Gravity.BOTTOM))
        }
        val shoot = tile("拍摄", "相机四周贴上你的贴纸", "◉", big = true) { startActivity(Intent(a, CollageCameraActivity::class.java)) }
        val auto = tile("一键拼贴", "一张主照片，加一组照片：自动抠图、排版", "✦") {
            startActivity(Intent(a, CollageActivity::class.java).putExtra(CollageActivity.EXTRA_AUTO, true))
        }
        val make = tile("制作贴纸", "从照片里抠出主体", "✂︎") { startActivity(Intent(a, StickerMakerActivity::class.java)) }
        val collage = tile("拼贴", "相纸、照片与贴纸", "▦") { startActivity(Intent(a, CollageActivity::class.java)) }
        val library = tile("贴纸库", "整理、收藏、换边框", "❏") { startActivity(Intent(a, StickerLibraryActivity::class.java)) }
        val works = tile("作品", "存好的拼贴与拍摄", "▣") { startActivity(Flows.gallery(a, "拼贴")) }
        val g = dp(a, 12f)
        val body = Glassy.vertical(a) {
            setPadding(dp(a, 18f), dp(a, 20f), dp(a, 18f), dp(a, 28f))
            addView(top, lp(-1, -2))
            space(dp(a, 22f))
            addView(shoot, lp(-1, dp(a, 170f)))
            space(g)
            addView(auto, lp(-1, dp(a, 128f)))
            space(g)
            addView(Glassy.row(a) {
                addView(make, lp(0, dp(a, 150f), 1f))
                addView(View(a), lp(g, 1))
                addView(collage, lp(0, dp(a, 150f), 1f))
            }, lp(-1, -2))
            space(g)
            addView(Glassy.row(a) {
                addView(library, lp(0, dp(a, 128f), 1f))
                addView(View(a), lp(g, 1))
                addView(works, lp(0, dp(a, 128f), 1f))
            }, lp(-1, -2))
        }
        setContentView(Glassy.root(a, ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(body) }))
        if (savedInstanceState == null) listOf(top, shoot, auto, make.parent as View, library.parent as View).forEachIndexed { i, v -> Glassy.rise(v, 60L + i * 90L) }
    }

    /** A card of the bento: a mark in a small glass circle, a title, a line under it. */
    private fun tile(title: String, line: String, mark: String, big: Boolean = false, onClick: () -> Unit): View {
        val a = this
        return FrameLayout(a).apply {
            background = Glassy.glass(a, 26f, if (big) 0x29ffffff else 0x1fffffff)
            org.photoall.film.ui.Style.pressable(this)
            setOnClickListener { onClick() }
            addView(Glassy.vertical(a) {
                setPadding(dp(a, 18f), dp(a, 16f), dp(a, 18f), dp(a, 18f))
                addView(Glassy.text(a, mark, if (big) 22f else 18f, Glassy.TEXT, Glassy.semibold).apply {
                    gravity = Gravity.CENTER; background = Glassy.glass(a, 999f, 0x24ffffff)
                }, lp(dp(a, if (big) 48f else 40f), dp(a, if (big) 48f else 40f)))
                addView(View(a), lp(1, 0, 1f))
                addView(Glassy.text(a, title, if (big) 26f else 19f, Glassy.TEXT, Glassy.bold))
                space(dp(a, 5f))
                addView(Glassy.text(a, line, 13f, Glassy.TEXT_2))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(Glassy.text(a, "›", 24f, Glassy.TEXT_3), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(a, 12f); marginEnd = dp(a, 18f)
            })
        }
    }

    companion object { const val EXTRA_THEN = "then" }
}
