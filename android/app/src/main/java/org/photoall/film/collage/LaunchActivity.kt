package org.photoall.film.collage

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.photoall.film.collage.Glassy.dp
import org.photoall.film.collage.Glassy.lp
import org.photoall.film.collage.Glassy.space

/**
 * The first screen: which camera. 胶片相机, the film camera as it was; 拼贴相机, stickers
 * cut from photos and laid on a print. Two large glass cards over the photo ground.
 */
class LaunchActivity : Activity() {
    private lateinit var galleryRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        Glassy.apply(this)
        super.onCreate(savedInstanceState)
        val a = this
        val head = Glassy.vertical(a) {
            addView(Glassy.text(a, "Photo All", 15f, Glassy.TEXT_2, Glassy.semibold))
            space(dp(a, 6f))
            addView(Glassy.text(a, "今天想怎么拍？", 34f, Glassy.TEXT, Glassy.bold))
        }
        val film = card("胶片相机", "光谱胶片模拟，从 RAW 冲洗出一卷胶片的颜色与颗粒。", "FILM", Art.FILM) {
            startActivity(Intent(a, org.photoall.film.camera.CameraActivity::class.java)) // kept: back from either camera comes here
        }
        val collage = card("拼贴相机", "抠出照片里的主体做成贴纸，拼进一张属于你的相纸。", "COLLAGE", Art.COLLAGE) {
            startActivity(Intent(a, CollageHomeActivity::class.java)) // kept: back from 拼贴相机 comes here
        }
        val retouch = card("AI 修图", "选一张照片，一键调色、提亮、消除。", "RETOUCH", Art.AI, compact = true) {
            @Suppress("DEPRECATION") startActivityForResult(PhotoPickerActivity.intent(a, "选一张来修"), PICK_FOR_AI)
        }
        val aiCam = card("AI 相机", "拍完自动 AI 调色，直接存好。", "AI CAMERA", Art.AI_CAMERA, compact = true) {
            startActivity(Intent(a, CollageCameraActivity::class.java).putExtra(CollageCameraActivity.EXTRA_AI, true))
        }
        // the two AI ways side by side
        val ai = Glassy.row(a) {
            addView(aiCam, lp(0, -1, 1f))
            addView(View(a), lp(dp(a, 12f), 1))
            addView(retouch, lp(0, -1, 1f))
        }
        val body = Glassy.vertical(a) {
            setPadding(dp(a, 20f), dp(a, 28f), dp(a, 20f), dp(a, 20f))
            addView(head)
            space(dp(a, 22f))
            addView(film, lp(-1, 0, 1f))
            space(dp(a, 16f))
            addView(collage, lp(-1, 0, 1f))
            space(dp(a, 16f))
            addView(ai, lp(-1, 0, 1f))
            space(dp(a, 14f))
            // the one gallery the three modes share
            addView(Glassy.row(a) {
                background = Glassy.glass(a, 22f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(a, 20f), 0, dp(a, 16f), 0)
                addView(Glassy.text(a, "▣", 18f, Glassy.TEXT), lp(-2, -2) { marginEnd = dp(a, 12f) })
                addView(Glassy.vertical(a) {
                    addView(Glassy.text(a, "图库", 17f, Glassy.TEXT, Glassy.bold))
                    addView(Glassy.text(a, "三种模式拍的、拼的、修的，都在这里", 12f, Glassy.TEXT_2))
                }, lp(0, -2, 1f))
                addView(Glassy.text(a, "›", 22f, Glassy.TEXT_2))
                org.photoall.film.ui.Style.pressable(this)
                setOnClickListener { startActivity(Flows.gallery(a)) }
            }.also { galleryRow = it }, lp(-1, dp(a, 68f)))
        }
        val root = Glassy.root(a, body) as FrameLayout
        setContentView(root)
        val prefs = getSharedPreferences("launch", MODE_PRIVATE)
        if (!prefs.getBoolean(TEST_NOTE, false)) root.postDelayed({ if (!isFinishing) testNote(root) { prefs.edit().putBoolean(TEST_NOTE, true).apply() } }, 650L)
        if (savedInstanceState == null) listOf(head, film, collage, ai, galleryRow).forEachIndexed { i, v -> Glassy.rise(v, 80L + i * 110L) }
    }

    // the 3D cartridges are drawn by a renderer living in the screen that shows them
    override fun onResume() { super.onResume(); org.photoall.film.ui.CartridgeSprites.attach(this) }
    override fun onPause() { org.photoall.film.ui.CartridgeSprites.detach(this); super.onPause() }

    @Deprecated("the picker's answer for AI 修图")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK_FOR_AI && resultCode == RESULT_OK && uri != null) startActivity(Flows.edit(this, uri, tool = "智能"))
    }

    /**
     * Once: this is a build for trying things, not the finished app. A glass card over the
     * dimmed screen, risen with a spring; 知道了 sinks it and it is not shown again.
     */
    private fun testNote(root: FrameLayout, seen: () -> Unit) {
        val a = this
        val dim = FrameLayout(a).apply {
            setBackgroundColor(0x80000000.toInt())
            isClickable = true // nothing under it is touched while it is up
            alpha = 0f
        }
        val card = Glassy.vertical(a) {
            background = Glassy.glass(a, 28f, 0x33ffffff)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(a, 24f), dp(a, 24f), dp(a, 24f), dp(a, 20f))
            addView(Glassy.text(a, "开发测试版本", 20f, Glassy.TEXT, Glassy.bold))
            space(dp(a, 10f))
            addView(Glassy.text(a, "拼贴相机、AI 相机和 AI 修图还在开发中，现在的样子不代表最终效果。遇到问题，可以在设置 › 日志里复制日志反馈。", 14f, Glassy.TEXT_2).apply {
                gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
            })
            space(dp(a, 20f))
            addView(Glassy.pill(a, "知道了", strong = true) {
                seen()
                dim.animate().alpha(0f).setDuration(240).withEndAction { root.removeView(dim) }.start()
            }, lp(-1, dp(a, 44f)))
        }
        dim.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { marginStart = dp(a, 36f); marginEnd = dp(a, 36f) })
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))
        dim.animate().alpha(1f).setDuration(260).start()
        card.scaleX = 0.88f; card.scaleY = 0.88f
        card.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
    }

    private enum class Art { FILM, COLLAGE, AI, AI_CAMERA }

    private fun card(title: String, line: String, tag: String, art: Art, compact: Boolean = false, onClick: () -> Unit): View {
        val a = this
        return FrameLayout(a).apply {
            background = Glassy.glass(a, 30f, 0x1fffffff)
            org.photoall.film.ui.Style.pressable(this)
            setOnClickListener { onClick() }
            addView(ArtView(a, art), FrameLayout.LayoutParams(-1, -1))
            addView(Glassy.vertical(a) {
                if (compact) setPadding(dp(a, 18f), dp(a, 16f), dp(a, 16f), dp(a, 18f)) else setPadding(dp(a, 22f), dp(a, 20f), dp(a, 22f), dp(a, 22f))
                addView(Glassy.text(a, tag, 11f, Glassy.TEXT_2, Glassy.semibold).apply { letterSpacing = 0.2f })
                addView(View(a), lp(1, 0, 1f))
                addView(Glassy.text(a, title, if (compact) 22f else 28f, Glassy.TEXT, Glassy.bold))
                space(dp(a, if (compact) 6f else 8f))
                addView(Glassy.text(a, line, if (compact) 12f else 14f, Glassy.TEXT_2).apply { setLineSpacing(0f, 1.2f) })
            }, FrameLayout.LayoutParams(-1, -1))
            addView(Glassy.text(a, "›", 28f, Glassy.TEXT_2), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(a, 14f); marginEnd = dp(a, 20f)
            })
        }
    }

    /**
     * The card's picture, top right: the film camera's cartridge standing; the collage
     * camera's newest stickers fanned out (or the cartridge's place left empty when none).
     */
    private class ArtView(context: android.content.Context, private val art: Art) : View(context) {
        private val d = resources.displayMetrics.density
        private var stickers: List<Bitmap> = emptyList()

        init {
            if (art == Art.COLLAGE) Thread {
                val list = Stickers.list(context).take(3).mapNotNull { Stickers.load(context, it.id) }
                post { stickers = list; invalidate() }
            }.start()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (art == Art.FILM) {
                val store = org.photoall.film.settings.SettingsStore(context)
                val name = org.photoall.film.engine.FilmEngine.filmStocks.getOrNull(store["film"].toInt()) ?: "kodak portra 400"
                // the 3D cartridge (rendered in this screen, see onResume), in the card's upper right,
                // clear of the words below it; a short leader of film, inside the card
                val ch = minOf(h * 0.5f, w * 0.34f)
                org.photoall.film.ui.CartridgeSprites.draw(c, w - ch * 0.95f, h * 0.1f + ch, ch, name, yaw = -0.45f, leader = 0.25f) { invalidate() }
                return
            }
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (art == Art.AI || art == Art.AI_CAMERA) {
                // a glowing orb of colour with sparkles: what the models do to a picture
                // a narrow card (two side by side): a smaller light up in its corner, clear of the words
                val narrow = w < h * 1.4f
                val r = if (narrow) h * 0.105f else h * 0.3f
                val cx = w - r * (if (narrow) 1.45f else 1.5f); val cy = if (narrow) h * 0.5f else h * 0.42f
                if (art == Art.AI_CAMERA) {
                    // a lens: rings round the light
                    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.12f; color = 0x59ffffff }
                    c.drawCircle(cx, cy, r * 1.05f, ring); ring.strokeWidth = r * 0.05f; ring.color = 0x33ffffff; c.drawCircle(cx, cy, r * 1.3f, ring)
                }
                p.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(0xffff9f0a.toInt(), 0xffff375f.toInt(), 0xffbf5af2.toInt(), 0xff0a84ff.toInt(), 0xffff9f0a.toInt()), null)
                p.alpha = 150
                p.maskFilter = android.graphics.BlurMaskFilter(r * 0.35f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                c.drawCircle(cx, cy, r, p)
                p.maskFilter = null; p.shader = null; p.alpha = 255
                p.color = 0xffffffff.toInt(); p.textAlign = Paint.Align.CENTER; p.typeface = Glassy.bold
                for ((dx, dy, size) in listOf(Triple(0f, 0f, r * 0.9f), Triple(r * 0.75f, -r * 0.7f, r * 0.42f), Triple(-r * 0.8f, r * 0.55f, r * 0.32f))) {
                    p.textSize = size; c.drawText("✦", cx + dx, cy + dy + size * 0.35f, p)
                }
                return
            }
            if (stickers.isEmpty()) {
                // three blank sticker shapes, to say what this is
                p.color = 0x26ffffff
                for (i in 0 until 3) {
                    c.save(); c.rotate(-12f + i * 12f, w * 0.74f, h * 0.36f)
                    c.drawRoundRect(RectF(w * 0.6f + i * 18 * d, h * 0.12f + i * 8 * d, w * 0.6f + i * 18 * d + h * 0.28f, h * 0.12f + i * 8 * d + h * 0.34f), 14 * d, 14 * d, p)
                    c.restore()
                }
                return
            }
            for ((i, b) in stickers.withIndex()) {
                val sh = h * 0.42f
                val sw = sh * b.width / b.height
                val cx = w * 0.62f + i * w * 0.11f; val cy = h * 0.34f + (i % 2) * 10 * d
                c.save(); c.rotate(-14f + i * 13f, cx, cy)
                c.drawBitmap(b, null, RectF(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2), p)
                c.restore()
            }
        }
    }

    private companion object {
        const val PICK_FOR_AI = 51
        /** The test-build note was seen (shown once). */
        const val TEST_NOTE = "test_note_seen"
    }
}
