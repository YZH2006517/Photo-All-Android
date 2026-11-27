package org.photoall.film.collage

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.photoall.film.collage.Glassy.dp
import org.photoall.film.collage.Glassy.lp
import org.photoall.film.collage.Glassy.space
import java.util.concurrent.Executors

/**
 * 制作贴纸. A photo in (the app's own picker, or [EXTRA_URI]), its subject found on the phone
 * ([Segmenter]) while a band of light reads across it; the mask can then be touched up by
 * hand (修整: erase or bring back with a brush), or drawn by hand from nothing (手动抠图, also
 * offered when nothing was found). The sticker in an edge style, into the library, and on to
 * a collage or the camera.
 */
class StickerMakerActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var stage: FrameLayout
    private lateinit var photoView: ImageView
    private lateinit var stickerView: ImageView
    private lateinit var scan: ScanView
    private lateinit var status: TextView
    private lateinit var sub: TextView
    private lateinit var styleBox: View
    private lateinit var actions: LinearLayout
    private lateinit var chips: LinearLayout
    private var photo: Bitmap? = null
    private var mask: Bitmap? = null
    private var cut: Bitmap? = null
    private var edge = Stickers.Edge.WHITE
    private var edgeWidth = 1f
    private var ticket = 0
    private var styling = 0
    private var editor: MaskEditor? = null
    private var editorBar: View? = null
    private var lastSaved: String? = null

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (uri != null) start(uri) else if (photo == null) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Glassy.apply(this)
        super.onCreate(savedInstanceState)
        val a = this
        photoView = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        stickerView = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }
        scan = ScanView(a)
        stage = FrameLayout(a).apply {
            addView(photoView, FrameLayout.LayoutParams(-1, -1))
            addView(scan, FrameLayout.LayoutParams(-1, -1))
            addView(stickerView, FrameLayout.LayoutParams(-1, -1))
        }
        status = Glassy.text(a, "选一张照片", 20f, Glassy.TEXT, Glassy.semibold).apply { gravity = Gravity.CENTER }
        sub = Glassy.text(a, "识别在这台手机上完成，照片不会上传", 12f, Glassy.TEXT_2).apply { gravity = Gravity.CENTER }
        chips = Glassy.row(a) { gravity = Gravity.CENTER }
        styleBox = Glassy.vertical(a) {
            addView(Glassy.text(a, "边缘", 13f, Glassy.TEXT_2, Glassy.semibold))
            space(dp(a, 10f))
            addView(chips, lp(-1, dp(a, 36f)))
            space(dp(a, 12f))
            addView(Glassy.row(a) {
                addView(Glassy.text(a, "宽度", 13f, Glassy.TEXT_2, Glassy.semibold), lp(dp(a, 40f), -2))
                addView(slider(50) { p -> edgeWidth = 0.4f + p / 100f * 1.6f; restyle() }, lp(0, -2, 1f))
            })
            space(dp(a, 14f))
        }
        actions = Glassy.row(a) {}
        val panel = Glassy.vertical(a) {
            background = Glassy.glass(a, 28f)
            setPadding(dp(a, 16f), dp(a, 16f), dp(a, 16f), dp(a, 16f))
            addView(styleBox, lp(-1, -2))
            addView(actions, lp(-1, dp(a, 48f)))
        }
        val body = Glassy.vertical(a) {
            addView(Glassy.header(a, "制作贴纸"))
            addView(Glassy.vertical(a) {
                setPadding(dp(a, 20f), dp(a, 8f), dp(a, 20f), dp(a, 16f))
                addView(status, lp(-1, -2))
                space(dp(a, 6f))
                addView(sub, lp(-1, -2))
                space(dp(a, 16f))
                addView(stage, lp(-1, 0, 1f))
                space(dp(a, 16f))
                addView(panel, lp(-1, -2))
            }, lp(-1, 0, 1f))
        }
        setContentView(Glassy.root(a, body))
        fillChips()
        showActions(Step.PICK)
        @Suppress("DEPRECATION") val given = intent.getParcelableExtra<Uri>(EXTRA_URI)
        if (given != null) start(given) else if (savedInstanceState == null) openPicker()
    }

    override fun onDestroy() { worker.shutdownNow(); Sam.forget(); super.onDestroy() }

    @Deprecated("Back: out of 修整 first")
    override fun onBackPressed() { if (editor != null) closeEditor(apply = false) else super.onBackPressed() }

    private fun openPicker() = pick.launch(PhotoPickerActivity.intent(this, "做成贴纸"))

    private fun slider(start: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        max = 100; progress = start
        progressTintList = android.content.res.ColorStateList.valueOf(Glassy.TEXT)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40ffffff)
        thumbTintList = android.content.res.ColorStateList.valueOf(Glassy.TEXT)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = Glassy.semibold
        setTextColor(if (on) 0xff1c1c1e.toInt() else Glassy.TEXT)
        background = if (on) android.graphics.drawable.GradientDrawable().apply { setColor(Glassy.TEXT); cornerRadius = 999f }
            else Glassy.glass(this@StickerMakerActivity, 999f, 0x14ffffff, rim = false)
        org.photoall.film.ui.Style.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun fillChips() {
        chips.removeAllViews()
        for (e in Stickers.Edge.entries) chips.addView(chip(e.label, e == edge) { if (edge != e) { edge = e; fillChips(); restyle() } },
            lp(0, -1, 1f) { marginStart = dp(this@StickerMakerActivity, 3f); marginEnd = dp(this@StickerMakerActivity, 3f) })
    }

    /** What the bottom buttons offer at each step. */
    private enum class Step { PICK, FAILED, RESULT, SAVED }

    private fun showActions(step: Step) {
        val a = this
        actions.removeAllViews()
        styleBox.visibility = if (step == Step.RESULT) View.VISIBLE else View.GONE
        fun add(v: View, weight: Float) {
            if (actions.childCount > 0) actions.addView(View(a), lp(dp(a, 8f), 1))
            actions.addView(v, lp(0, -1, weight))
        }
        when (step) {
            Step.PICK -> add(Glassy.pill(a, "选照片") { openPicker() }, 1f)
            Step.FAILED -> {
                add(Glassy.pill(a, "换一张") { openPicker() }, 1f)
                add(Glassy.pill(a, "框选抠图", strong = true) { openEditor(fromNothing = true) }, 1.3f)
            }
            Step.RESULT -> {
                add(Glassy.pill(a, "修整") { openEditor(fromNothing = false) }, 1f)
                add(Glassy.pill(a, "换一张") { openPicker() }, 1f)
                add(Glassy.pill(a, "存入贴纸库", strong = true) { save() }, 1.5f)
            }
            Step.SAVED -> {
                val back = intent.getStringExtra(Flows.EXTRA_RETURN)
                add(Glassy.pill(a, "再做一张") { openPicker() }, 1f)
                if (back != null) add(Glassy.pill(a, back, strong = true) {
                    // made for a screen that asked: back to it with the sticker (the same collage)
                    lastSaved?.let { setResult(RESULT_OK, Intent().putExtra(StickerLibraryActivity.EXTRA_ID, it)); finish() }
                }, 1.6f)
                else {
                    add(Glassy.pill(a, "去拼贴") {
                        lastSaved?.let { Flows.anchored(a, Intent(a, CollageActivity::class.java).putExtra(CollageActivity.EXTRA_STICKER, it)) }
                    }, 1f)
                    add(Glassy.pill(a, "贴到相机", strong = true) {
                        lastSaved?.let { Flows.anchored(a, Intent(a, CollageCameraActivity::class.java).putExtra(CollageCameraActivity.EXTRA_STICKER, it)) }
                    }, 1.2f)
                }
            }
        }
        actions.alpha = 0f; actions.animate().alpha(1f).setDuration(260).start()
    }

    private fun start(uri: Uri) {
        val t = ++ticket
        photo = null; mask = null; cut = null
        stickerView.animate().alpha(0f).setDuration(200).start()
        photoView.alpha = 1f; photoView.scaleX = 1f; photoView.scaleY = 1f
        status.text = "正在分开主体"
        sub.text = "识别在这台手机上完成，照片不会上传"
        showActions(Step.PICK)
        worker.execute {
            val ph = Stickers.decode(this, uri, 1600)
            runOnUiThread { if (t == ticket) { photoView.setImageBitmap(ph); if (ph != null) scan.start() } }
            if (ph == null) { runOnUiThread { failed("读不出这张照片") }; return@execute }
            val m = runCatching { Segmenter.mask(this, ph) }
            val c = m.getOrNull()?.let { Segmenter.compose(ph, it) }
            runOnUiThread {
                if (t != ticket || isFinishing) return@runOnUiThread
                scan.stop()
                photo = ph
                mask = m.getOrNull() ?: Bitmap.createBitmap(ph.width, ph.height, Bitmap.Config.ARGB_8888)
                when {
                    m.isFailure -> failed("抠图失败：${m.exceptionOrNull()?.message}，可以框住主体再识别一次")
                    c == null -> failed("没有找到明显的主体；框住或涂抹主体，在那一块里再识别")
                    else -> { cut = c; reveal() }
                }
            }
        }
    }

    private fun failed(why: String) {
        status.text = "没能自动抠出来"
        sub.text = why
        scan.stop()
        showActions(if (photo != null) Step.FAILED else Step.PICK)
    }

    /** The photo falls away behind, the sticker lifts out of it. */
    private fun reveal() {
        status.text = "主体已经分开"
        sub.text = "选一种边缘；边缘不准时点「修整」用手擦除或恢复"
        restyle {
            photoView.animate().alpha(0.18f).scaleX(0.94f).scaleY(0.94f).setDuration(520).start()
            stickerView.scaleX = 0.82f; stickerView.scaleY = 0.82f
            stickerView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(620)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
            showActions(Step.RESULT)
        }
    }

    private fun restyle(then: (() -> Unit)? = null) {
        val c = cut ?: return
        val n = ++styling
        val e = edge; val w = edgeWidth
        worker.execute {
            val big = maxOf(c.width, c.height)
            val small = if (big > 900) Bitmap.createScaledBitmap(c, c.width * 900 / big, c.height * 900 / big, true) else c
            val out = Stickers.render(small, e, w)
            runOnUiThread { if (n == styling && !isFinishing) { stickerView.setImageBitmap(out); then?.invoke() } }
        }
    }

    // ---- 修整 / 手动抠图 ----------------------------------------------------------------------------

    private fun openEditor(fromNothing: Boolean) {
        val ph = photo ?: return
        val a = this
        val m = mask?.takeIf { !fromNothing }?.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(ph.width, ph.height, Bitmap.Config.ARGB_8888)
        val ed = MaskEditor(a, ph, m).apply { tool = if (fromNothing) MaskEditor.Tool.BOX else MaskEditor.Tool.ERASE }
        editor = ed
        stage.addView(ed, FrameLayout.LayoutParams(-1, -1))
        photoView.visibility = View.INVISIBLE; stickerView.visibility = View.INVISIBLE
        status.text = if (fromNothing) "框选抠图" else "修整"
        fun hint() {
            sub.text = when (ed.tool) {
                MaskEditor.Tool.BOX -> "框住一个物体，松手后把框里的它抠出来"
                MaskEditor.Tool.SMEAR -> "在要抠的物体上涂几笔，松手后把涂到的物体抠出来"
                MaskEditor.Tool.ERASE -> "涂掉多余的部分；两指缩放移动"
                MaskEditor.Tool.RESTORE -> "涂出要留下的部分；两指缩放移动"
            }
        }
        hint()
        // the model run again on the part the user showed, its answer added in (undoable)
        // MobileSAM finds the object shown (a box, or strokes on it); should it fail on this phone,
        // the saliency model in that part instead, and the user told
        ed.onRegion = { box, points ->
            ed.busy = true
            sub.text = if (points.isEmpty()) "正在识别框里的物体…" else "正在识别涂过的物体…"
            worker.execute {
                val sam = runCatching { Sam.mask(this, ph, box, points) }
                sam.exceptionOrNull()?.let { android.util.Log.w("Sam", "MobileSAM failed", it) }
                val part = sam.recoverCatching { Segmenter.maskIn(this, ph, box) }
                runOnUiThread {
                    if (editor !== ed || isFinishing) return@runOnUiThread
                    ed.busy = false; ed.clearRegion()
                    val fellBack = sam.isFailure
                    part.onSuccess { pm ->
                        val note = if (fellBack) "（精细识别在这台手机上不可用：${sam.exceptionOrNull()?.message}，已改用普通识别）" else ""
                        if (Segmenter.bounds(pm) == null) sub.text = "这里没认出物体，换个范围，或用「恢复」涂出来$note"
                        else { ed.merge(pm); sub.text = "已加进来；可以再框一个，或「擦除」多余的$note" }
                        pm.recycle()
                    }.onFailure { sub.text = "识别失败：${it.message}" }
                }
            }
        }
        val tools = Glassy.row(a) {}
        fun paintTools() {
            tools.removeAllViews()
            for ((t, label) in listOf(MaskEditor.Tool.BOX to "框选", MaskEditor.Tool.SMEAR to "涂抹", MaskEditor.Tool.ERASE to "擦除", MaskEditor.Tool.RESTORE to "恢复"))
                tools.addView(chip(label, ed.tool == t) { if (!ed.busy) { ed.tool = t; paintTools(); hint() } }, lp(0, -1, 1f) { marginEnd = dp(a, 5f) })
            tools.addView(chip("撤销", false) { if (!ed.busy) ed.undoLast() }, lp(0, -1, 1f))
        }
        paintTools()
        val bar = Glassy.vertical(a) {
            addView(tools, lp(-1, dp(a, 36f)))
            space(dp(a, 12f))
            addView(Glassy.row(a) {
                addView(Glassy.text(a, "笔刷", 13f, Glassy.TEXT_2, Glassy.semibold), lp(dp(a, 40f), -2))
                addView(slider(30) { p -> ed.brushDp = 8f + p * 0.9f }, lp(0, -2, 1f))
            })
            space(dp(a, 12f))
            addView(Glassy.row(a) {
                addView(Glassy.pill(a, "取消") { closeEditor(apply = false) }, lp(0, dp(a, 48f), 1f))
                addView(View(a), lp(dp(a, 8f), 1))
                addView(Glassy.pill(a, "完成", strong = true) { closeEditor(apply = true) }, lp(0, dp(a, 48f), 1.4f))
            })
        }
        editorBar = bar
        styleBox.visibility = View.GONE
        actions.visibility = View.GONE
        (actions.parent as LinearLayout).addView(bar, lp(-1, -2))
    }

    private fun closeEditor(apply: Boolean) {
        val ed = editor ?: return
        val ph = photo
        editor = null
        stage.removeView(ed)
        editorBar?.let { (it.parent as? LinearLayout)?.removeView(it) }
        editorBar = null
        actions.visibility = View.VISIBLE
        photoView.visibility = View.VISIBLE; stickerView.visibility = View.VISIBLE
        if (!apply || ph == null) { showActions(if (cut != null) Step.RESULT else Step.FAILED); return }
        mask = ed.mask
        val c = Segmenter.compose(ph, ed.mask)
        if (c == null) { cut = null; failed("没有留下任何部分"); return }
        cut = c
        photoView.alpha = 0.18f
        stickerView.alpha = 1f
        status.text = "主体已经分开"
        sub.text = "选一种边缘，存入贴纸库"
        restyle { showActions(Step.RESULT) }
    }

    // ---- saving -----------------------------------------------------------------------------------

    private fun save() {
        val c = cut ?: return
        val e = edge; val w = edgeWidth
        worker.execute {
            val s = Stickers.add(this, c, e, w)
            runOnUiThread {
                lastSaved = s.id
                Toast.makeText(this, "已存入贴纸库", Toast.LENGTH_SHORT).show()
                status.text = "存好了"
                sub.text = "接着拼一张，或者贴到相机四周"
                showActions(Step.SAVED)
            }
        }
    }

    /** A band of light reading across the photo, over and over, while the model works. */
    private class ScanView(context: Context) : View(context) {
        private val p = Paint()
        private var t = 0f
        private var anim: ValueAnimator? = null
        fun start() {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        fun stop() { anim?.cancel(); anim = null; invalidate() }
        override fun onDraw(c: Canvas) {
            if (anim == null) return
            val y = -height * 0.3f + t * height * 1.6f
            p.shader = LinearGradient(0f, y - height * 0.25f, 0f, y, intArrayOf(0x00ffffff, 0x55ffffff, 0x00ffffff),
                floatArrayOf(0f, 0.85f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, y - height * 0.25f, width.toFloat(), y, p)
        }
    }

    companion object { const val EXTRA_URI = "uri" }
}
