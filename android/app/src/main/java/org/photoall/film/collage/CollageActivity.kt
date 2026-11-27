package org.photoall.film.collage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.photoall.film.collage.CollageArt.Ground
import org.photoall.film.collage.CollageArt.Hand
import org.photoall.film.collage.CollageArt.Paper
import org.photoall.film.collage.CollageArt.Surface
import org.photoall.film.collage.CollageUi.Icon.Kind
import org.photoall.film.collage.Glassy.dp
import org.photoall.film.collage.Glassy.lp
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 拼贴: a board of layers over a lit ground — photos, each on a print of its own make, colour and
 * surface; stickers; words in a hand of their own. Everything moves with a finger and sizes and
 * turns with two; in 取景 the chosen photo moves and zooms inside its window instead.
 *
 * Laid out as Apple lays out an editor: a floating glass toolbar at the foot (what can be added,
 * the one in use lit), and over it an inspector card that rises with a spring when something is
 * chosen, cross-fades as the choice changes and sinks away when nothing is. Its choices are shown
 * as they will look (each print with this photo on it, each surface in this paper's colour).
 * Saved as one picture into the album at full size, drawn exactly as on screen.
 */
class CollageActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var board: Board
    private lateinit var area: FrameLayout
    private lateinit var inspector: LinearLayout
    private lateinit var tools: List<Pair<Kind, Pair<ImageView, TextView>>>
    /** The prints' card (样式) is open: for the print chosen, else the first. */
    private var styleOpen = false

    /** 样式: the chosen print's card (or the first print's), or closed again. */
    private fun openStyle() {
        if (styleOpen) { styleOpen = false; updateInspector(); return }
        val shot = board.selected as? Board.Shot ?: board.layers.filterIsInstance<Board.Shot>().lastOrNull()
        if (shot == null) { Toast.makeText(this, "先放一张照片", Toast.LENGTH_SHORT).show(); return }
        styleOpen = true; groundOpen = false
        if (board.selected === shot) updateInspector() else board.select(shot)
    }

    /** How far the keyboard pushes the card up. */
    private var keyboardLift = 0f
    /** The words' field in the card (kept while the words are chosen, so typing is never cut). */
    private var wordsField: EditText? = null
    private var wordsFor: Board.Label? = null
    /** The chosen print's page: 样式, 颜色, 质感, 比例. */
    private var shotTab = 0
    /** The ground's inspector is open (nothing chosen, 背景 tapped). */
    private var groundOpen = false
    /** What the inspector shows now (a layer, or the ground): a new one cross-fades in. */
    private var shownKey: Any? = null
    /** Where each row of choices was scrolled to, so a choice made does not throw it back. */
    private val scrolled = HashMap<String, Int>()
    /** What the photo picker's answer is for: a new print, or the chosen one's photo. */
    private var replacing = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> r.data?.data?.let { loadPhoto(it, replacing) } }
    /** The editor saves a new photo and hands it back: the chosen print takes it. */
    private val editPhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> r.data?.data?.let { loadPhoto(it, true) } }
    private val pickSticker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getStringExtra(StickerLibraryActivity.EXTRA_ID) ?: return@registerForActivityResult
        worker.execute { Stickers.load(this, id)?.let { b -> runOnUiThread { board.add(Board.Sticker(b)) } } }
    }

    // ---- 一键拼贴: a main photo, then a handful to cut out, laid round it ------------------------

    private val auto by lazy { intent.getBooleanExtra(EXTRA_AUTO, false) }
    private var autoSeed = System.currentTimeMillis()
    private var working: View? = null

    private val pickMain = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (uri == null) { finish(); return@registerForActivityResult }
        loadPhoto(uri, false)
        pickMany.launch(PhotoPickerActivity.multiple(this, "再选几张，抠出主体", AutoCollage.MOST))
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uris = PhotoPickerActivity.urisOf(r.data)
        if (uris.isEmpty()) worker.execute { runOnUiThread { AutoCollage.arrange(board, autoSeed); board.select(null) } }
        else cutAndLay(uris)
    }

    /** Each photo's subject cut out as a sticker (a card on the board counts them), then all laid out. */
    private fun cutAndLay(uris: List<Uri>) {
        showWorking("正在抠图 0 / ${uris.size}")
        worker.execute {
            val cut = AutoCollage.cut(this, uris) { done -> runOnUiThread { showWorking("正在抠图 $done / ${uris.size}") } }
            runOnUiThread {
                showWorking(null)
                for (b in cut.stickers) board.layers += Board.Sticker(b)
                AutoCollage.arrange(board, autoSeed)
                board.select(null)
                if (cut.missed > 0) Toast.makeText(this, "${cut.missed} 张没找到主体，没有放上", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The working card over the board ([text]), or gone (null). */
    private fun showWorking(text: String?) {
        if (text == null) { working?.let { v -> v.animate().alpha(0f).setDuration(220).withEndAction { area.removeView(v) }.start() }; working = null; return }
        val v = working as? TextView ?: Glassy.text(this, "", 15f, Glassy.TEXT, Glassy.semibold).apply {
            gravity = Gravity.CENTER
            background = CollageUi.glass(this@CollageActivity, 999f, 0x29ffffff)
            setPadding(dp(this@CollageActivity, 22f), 0, dp(this@CollageActivity, 22f), 0)
            area.addView(this, FrameLayout.LayoutParams(-2, dp(this@CollageActivity, 46f), Gravity.CENTER))
            Glassy.rise(this, 0L)
            working = this
        }
        v.text = text
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Glassy.apply(this)
        super.onCreate(savedInstanceState)
        val a = this
        board = Board(a).apply { onSelect = {
            if (selected != null) groundOpen = false
            // 样式 stays open while the choice moves from print to print; anything else closes it
            if (selected !is Board.Shot) styleOpen = false
            updateInspector()
        } }
        inspector = Glassy.vertical(a) {
            background = CollageUi.glass(a, 28f, 0x14ffffff)
            setPadding(dp(a, 14f), dp(a, 12f), dp(a, 14f), dp(a, 14f))
            visibility = View.GONE
            isClickable = true // touches on it never reach the board under it
        }
        area = FrameLayout(a).apply {
            clipChildren = false
            setPadding(dp(a, 14f), dp(a, 4f), dp(a, 14f), 0)
            addView(board, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(inspector, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(a, 10f) })
        }
        // the toolbar: a floating glass capsule of what can be added
        val bar = Glassy.row(a) {
            background = CollageUi.glass(a, 999f, 0x1affffff)
            setPadding(dp(a, 8f), dp(a, 6f), dp(a, 8f), dp(a, 6f))
            gravity = Gravity.CENTER_VERTICAL
        }
        tools = listOf<Triple<Kind, String, () -> Unit>>(
            Triple(Kind.PHOTO, "照片") { replacing = false; pickPhoto.launch(PhotoPickerActivity.intent(a, "放进拼贴")) },
            Triple(Kind.STYLE, "样式") { openStyle() },
            Triple(Kind.TEXT, "文字") { editWords(null) },
            Triple(Kind.STICKER, "贴纸") { pickSticker.launch(Intent(a, StickerLibraryActivity::class.java).putExtra(StickerLibraryActivity.EXTRA_PICK, true).putExtra(Flows.EXTRA_RETURN, "放进拼贴")) },
            Triple(Kind.GROUND, "背景") { groundOpen = !groundOpen || board.selected != null; board.select(null) },
        ).map { (kind, label, run) ->
            val icon = ImageView(a).apply { setImageDrawable(CollageUi.Icon(kind, Glassy.TEXT)) }
            val text = Glassy.text(a, label, 10f, 0xff8e8e93.toInt(), Glassy.semibold).apply { gravity = Gravity.CENTER }
            bar.addView(Glassy.vertical(a) {
                gravity = Gravity.CENTER
                addView(icon, lp(dp(a, 24f), dp(a, 24f)))
                addView(text, lp(-2, -2) { topMargin = dp(a, 3f) })
                org.photoall.film.ui.Style.pressable(this)
                setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); run() }
            }, lp(0, dp(a, 50f), 1f))
            kind to (icon to text)
        }
        val body = Glassy.vertical(a) {
            val keep = Glassy.pill(a, "存储", strong = true) { save() }
            // 一键拼贴: another arrangement of the same pieces, before keeping one
            addView(Glassy.header(a, if (auto) "一键拼贴" else "拼贴", if (!auto) keep else Glassy.row(a) {
                addView(Glassy.pill(a, "换排版") {
                    if (working != null) return@pill
                    autoSeed = System.nanoTime(); board.select(null)
                    AutoCollage.arrange(board, autoSeed)
                }, lp(-2, -1) { marginEnd = dp(a, 8f) })
                addView(keep, lp(-2, -1))
            }))
            addView(area, lp(-1, 0, 1f))
            addView(bar, lp(-1, dp(a, 62f)) { marginStart = dp(a, 28f); marginEnd = dp(a, 28f); bottomMargin = dp(a, 12f); topMargin = dp(a, 2f) })
        }
        setContentView(Glassy.root(a, body))
        paintGround()
        // the keyboard over the card: the card rises over it, the board shrinks to the room left
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        area.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            val loc = IntArray(2); v.getLocationInWindow(loc)
            val below = (v.rootView.height - (loc[1] + v.height)) // the toolbar and the bars under the area
            val lift = (ime - below).coerceAtLeast(0).toFloat()
            if (lift != keyboardLift) {
                keyboardLift = lift
                compactForTyping(lift > 0f)
                inspector.animate().translationY(-lift).setDuration(260).setInterpolator(CollageUi.EASE).start()
                fitBoard(true)
            }
            insets
        }
        area.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) fitBoard(false) }
        updateInspector()
        if (savedInstanceState == null) {
            // arriving with a photo (the camera) or a sticker (制作贴纸): straight onto the board
            val given = intent.data
            val sticker = intent.getStringExtra(EXTRA_STICKER)
            if (given != null) loadPhoto(given, false)
            if (sticker != null) worker.execute { Stickers.load(this, sticker)?.let { b -> runOnUiThread { board.add(Board.Sticker(b)) } } }
            if (auto) pickMain.launch(PhotoPickerActivity.intent(a, "选一张主照片"))
            else if (given == null && sticker == null) pickPhoto.launch(PhotoPickerActivity.intent(a, "放进拼贴"))
        }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    @Deprecated("Back: out of 取景, then the choice, first")
    override fun onBackPressed() {
        when {
            board.photoMode -> { board.photoMode = false; board.invalidate(); updateInspector() }
            styleOpen -> { styleOpen = false; updateInspector() }
            board.selected != null || groundOpen -> { groundOpen = false; board.select(null) }
            else -> super.onBackPressed()
        }
    }

    // ---- the inspector ---------------------------------------------------------------------------

    /** The inspector for what is chosen: risen, cross-faded to it, rebuilt in place, or sunk away. */
    private fun updateInspector() {
        val s = board.selected
        // a print chosen on the board only shows it chosen: its card opens from 样式
        val key: Any? = if (s is Board.Shot) s.takeIf { styleOpen } else s ?: if (groundOpen) "ground" else null
        // the toolbar lights what the inspector is about
        val lit = when (s) { is Board.Shot -> if (styleOpen) Kind.STYLE else null; is Board.Label -> Kind.TEXT; is Board.Sticker -> Kind.STICKER; else -> if (groundOpen) Kind.GROUND else null }
        for ((kind, views) in tools) {
            val on = kind == lit
            (views.first.drawable as? CollageUi.Icon)?.color = if (on) Glassy.YELLOW else Glassy.TEXT
            views.first.invalidate()
            views.second.setTextColor(if (on) Glassy.YELLOW else 0xff8e8e93.toInt())
        }
        if (s !is Board.Label) wordsField?.let { f ->
            if (f.hasFocus()) (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0)
            f.clearFocus()
        }
        if (key == null) { sink(); shownKey = null; fitBoard(true); return }
        val was = shownKey
        shownKey = key
        val fresh = inspector.visibility != View.VISIBLE
        if (!fresh) android.transition.TransitionManager.beginDelayedTransition(area,
            android.transition.ChangeBounds().setDuration(300).setInterpolator(CollageUi.EASE))
        inspector.removeAllViews()
        when (s) {
            null -> groundPage()
            is Board.Shot -> shotPage(s)
            is Board.Label -> labelPage(s)
            is Board.Sticker -> stickerPage(s)
        }
        inspector.post { fitBoard(true) }
        when {
            fresh -> rise()
            was !== key -> for (i in 0 until inspector.childCount) inspector.getChildAt(i).apply {
                // another thing chosen: its controls come in softly
                alpha = 0f; translationY = dp(this@CollageActivity, 8f).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay(20L * i).setDuration(240).setInterpolator(CollageUi.EASE).start()
            }
        }
    }

    /**
     * The board kept whole above the inspector: smaller while the card is up, back to full size and
     * centred when it is down; moved with the card's own spring.
     */
    private fun fitBoard(animate: Boolean) {
        if (board.height == 0 || area.height == 0) { area.post { fitBoard(false) }; return }
        val room = area.height - area.paddingTop - dp(this, 8f) -
            (if (inspector.visibility == View.VISIBLE && shownKey != null) inspector.height + dp(this, 22f) + keyboardLift.toInt() else 0)
        val k = (room.toFloat() / board.height).coerceIn(0.2f, 1f)
        val ty = ((room - board.height * k) / 2f).coerceAtLeast(0f)
        board.pivotX = board.width / 2f; board.pivotY = 0f
        if (!animate) { board.animate().cancel(); board.scaleX = k; board.scaleY = k; board.translationY = ty; return }
        board.animate().scaleX(k).scaleY(k).translationY(ty).setDuration(460).setInterpolator(CollageUi.SPRING).start()
    }

    /** While typing: only the card's head and the words' field; after: everything back. */
    private fun compactForTyping(typing: Boolean) {
        if (board.selected !is Board.Label) return
        val field = wordsField ?: return
        for (i in 0 until inspector.childCount) {
            val v = inspector.getChildAt(i)
            if (v === field || i == 0) continue
            v.visibility = if (typing) View.GONE else View.VISIBLE
        }
        inspector.post { fitBoard(true) }
    }

    /** Up from below the toolbar with a little spring. */
    private fun rise() {
        inspector.animate().cancel()
        inspector.visibility = View.VISIBLE
        inspector.alpha = 0f
        inspector.translationY = dp(this, 120f).toFloat()
        inspector.scaleX = 0.96f; inspector.scaleY = 0.96f
        inspector.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(CollageUi.SPRING).start()
    }

    /** Down and away. */
    private fun sink() {
        if (inspector.visibility != View.VISIBLE) return
        inspector.animate().cancel()
        inspector.animate().alpha(0f).translationY(dp(this, 90f).toFloat()).scaleX(0.97f).scaleY(0.97f).setDuration(240)
            .setInterpolator(CollageUi.EASE).withEndAction { inspector.visibility = View.GONE }.start()
    }

    /** The card's head: what it is about, and a round × that lets it go. */
    private fun head(title: String) = inspector.addView(Glassy.row(this) {
        gravity = Gravity.CENTER_VERTICAL
        addView(Glassy.text(this@CollageActivity, title, 15f, Glassy.TEXT, Glassy.bold), lp(0, -2, 1f))
        addView(ImageView(this@CollageActivity).apply {
            setImageDrawable(CollageUi.Icon(Kind.CLOSE, 0xffc8c8ce.toInt()))
            setPadding(dp(this@CollageActivity, 7f), dp(this@CollageActivity, 7f), dp(this@CollageActivity, 7f), dp(this@CollageActivity, 7f))
            background = CollageUi.glass(this@CollageActivity, 999f, 0x1affffff, shadow = false)
            org.photoall.film.ui.Style.pressable(this)
            setOnClickListener { groundOpen = false; styleOpen = false; board.select(null) }
        }, lp(dp(this@CollageActivity, 30f), dp(this@CollageActivity, 30f)))
    }, lp(-1, -2) { bottomMargin = dp(this@CollageActivity, 12f) })

    /** A row of choices that scrolls sideways and keeps where it was. */
    private fun choices(key: String, fill: (LinearLayout) -> Unit) {
        val r = Glassy.row(this) { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        fill(r)
        val sv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(r)
            setOnScrollChangeListener { _, x, _, _, _ -> scrolled[key] = x }
        }
        inspector.addView(sv, lp(-1, -2))
        scrolled[key]?.let { x -> sv.post { sv.scrollTo(x, 0) } }
    }

    /** The actions at the card's foot: round glass wells with a line icon, the name under each. */
    private fun actions(list: List<Triple<Kind, String, () -> Unit>>) = inspector.addView(Glassy.row(this) {
        for ((kind, label, run) in list) addView(Glassy.vertical(this@CollageActivity) {
            gravity = Gravity.CENTER_HORIZONTAL
            val danger = kind == Kind.TRASH
            val on = kind == Kind.CROP && board.photoMode
            addView(ImageView(this@CollageActivity).apply {
                setImageDrawable(CollageUi.Icon(kind, if (danger) Glassy.RED else if (on) 0xff1c1c1e.toInt() else Glassy.TEXT))
                setPadding(dp(this@CollageActivity, 10f), dp(this@CollageActivity, 10f), dp(this@CollageActivity, 10f), dp(this@CollageActivity, 10f))
                background = if (on) android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Glassy.YELLOW) }
                    else CollageUi.glass(this@CollageActivity, 999f, 0x1affffff, shadow = false)
            }, lp(dp(this@CollageActivity, 42f), dp(this@CollageActivity, 42f)))
            addView(Glassy.text(this@CollageActivity, label, 11f, if (danger) Glassy.RED else 0xff8e8e93.toInt()).apply { gravity = Gravity.CENTER }, lp(-2, -2) { topMargin = dp(this@CollageActivity, 4f) })
            org.photoall.film.ui.Style.pressable(this)
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); run() }
        }, lp(0, -2, 1f))
    }, lp(-1, -2) { topMargin = dp(this@CollageActivity, 14f) })

    private fun tile(label: String, chosen: Boolean, w: Float, h: Float, onClick: () -> Unit, art: (Canvas, RectF) -> Unit) =
        CollageUi.Tile(this, label, chosen, w, h, art).apply { setOnClickListener { onClick() } }

    private fun well(color: Int, chosen: Boolean, onClick: () -> Unit) =
        CollageUi.Well(this, color, chosen).apply { setOnClickListener { onClick() } }

    private fun shotPage(s: Board.Shot) {
        head(if (board.photoMode) "取景：拖动、双指缩放照片" else "样式")
        inspector.addView(CollageUi.Segments(this, listOf("样式", "颜色", "质感", "比例"), shotTab) { i -> shotTab = i; updateInspector() },
            lp(-1, dp(this, 32f)) { bottomMargin = dp(this@CollageActivity, 12f) })
        when (shotTab) {
            0 -> choices("paper") { r ->
                for (p in Paper.entries) r.addView(tile(p.label, s.paper == p, 62f, 74f, { s.paper = p; s.color = p.color; s.surface = p.surface; board.invalidate(); updateInspector() }) { c, well ->
                    // this paper, with this photo on it
                    val aspect = p.window ?: s.ratio ?: s.photo?.let { it.height.toFloat() / it.width } ?: 0.75f
                    var cw = well.width() * 0.74f
                    var g = CollageArt.geometry(p, cw, aspect)
                    if (g.card.height() > well.height() * 0.8f) { cw *= well.height() * 0.8f / g.card.height(); g = CollageArt.geometry(p, cw, aspect) }
                    c.save(); c.translate(well.centerX(), well.centerY())
                    CollageArt.print(c, cw / 0.64f, g, p, p.color, p.surface, s.thumb ?: s.photo, 0f, 0f, 1f, 1, null)
                    c.restore()
                })
            }
            1 -> choices("color") { r ->
                val own = s.tones?.let { intArrayOf(CollageArt.mix(it[0], 0xffffffff.toInt(), 0.6f), it[0], it[1]) } ?: IntArray(0)
                for (col in own + CollageArt.PAPER_COLORS) r.addView(well(col, s.color == col) { s.color = col; board.invalidate(); updateInspector() },
                    lp(dp(this, 40f), dp(this, 40f)) { marginEnd = dp(this@CollageActivity, 6f) })
            }
            2 -> choices("surface") { r ->
                for (f in Surface.entries) r.addView(tile(f.label, s.surface == f, 58f, 58f, { s.surface = f; board.invalidate(); updateInspector() }) { c, well ->
                    // this paper's colour in this surface, lit from above
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (s.color ushr 24 == 0) 0xfff4f1ea.toInt() else s.color }
                    c.drawRect(well, p)
                    CollageArt.texture(c, well, f, 2600f, 1f)
                    p.shader = android.graphics.LinearGradient(0f, well.top, 0f, well.bottom, intArrayOf(0x22ffffff, 0x00ffffff, 0x1a000000), null, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(well, p)
                })
            }
            else -> if (s.paper.window != null) inspector.addView(Glassy.text(this, "「${s.paper.label}」的画幅是固定的；换成白卡、细边等相纸就能改比例", 13f, 0xffa8a8b0.toInt()).apply {
                setPadding(dp(this@CollageActivity, 4f), dp(this@CollageActivity, 8f), 0, dp(this@CollageActivity, 8f))
            }, lp(-1, -2))
            else choices("ratio") { r ->
                for ((label, v) in RATIOS) r.addView(tile(label, s.ratio == v, 52f, 52f, { s.ratio = v; s.cropX = 0f; s.cropY = 0f; board.invalidate(); updateInspector() }) { c, well ->
                    val asp = v ?: s.photo?.let { it.height.toFloat() / it.width } ?: 0.75f
                    val m = well.width() * 0.62f
                    val w = if (asp > 1f) m / asp else m; val h = w * asp
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * resources.displayMetrics.density; color = if (s.ratio == v) Glassy.YELLOW else Glassy.TEXT }
                    c.drawRoundRect(RectF(well.centerX() - w / 2, well.centerY() - h / 2, well.centerX() + w / 2, well.centerY() + h / 2), 3f, 3f, p)
                })
            }
        }
        actions(listOf(
            Triple(Kind.CROP, "取景") { board.photoMode = !board.photoMode; board.invalidate(); updateInspector() },
            Triple(Kind.SWAP, "换图") { replacing = true; pickPhoto.launch(PhotoPickerActivity.intent(this, "换一张")) },
            Triple(Kind.MAGIC, "修图") { s.uri?.let { editPhoto.launch(Flows.edit(this, it)) } },
            Triple(Kind.FRONT, "置顶") { board.toTop(s) },
            Triple(Kind.TRASH, "删除") { board.remove(s) },
        ))
    }

    private fun labelPage(s: Board.Label) {
        head("文字")
        val field = wordsField?.takeIf { wordsFor === s } ?: EditText(this).apply {
            setText(s.text); setSelection(text.length)
            hint = "输入文字"; setHintTextColor(0xff8e8e93.toInt()); setTextColor(Glassy.TEXT); textSize = 16f; typeface = Glassy.semibold
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x3d767680); cornerRadius = dp(this@CollageActivity, 12f).toFloat() }
            setPadding(dp(this@CollageActivity, 14f), 0, dp(this@CollageActivity, 14f), 0)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, _, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); v.clearFocus(); true
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { s.text = e?.toString()?.ifBlank { " " } ?: " "; board.invalidate() }
            })
        }
        wordsField = field; wordsFor = s
        (field.parent as? android.view.ViewGroup)?.removeView(field)
        inspector.addView(field, lp(-1, dp(this, 42f)) { bottomMargin = dp(this@CollageActivity, 12f) })
        choices("hand") { r ->
            for (h in Hand.entries) r.addView(tile(h.label, s.hand == h, 62f, 42f, { s.hand = h; board.invalidate(); updateInspector() }) { c, well ->
                c.drawRect(well, Paint().apply { color = 0xff2c2c2e.toInt() })
                c.save(); c.translate(well.centerX(), well.centerY())
                CollageArt.words(c, "Aa", h, s.color, s.glow, well.height() * 0.42f)
                c.restore()
            })
        }
        inspector.addView(View(this), lp(-1, dp(this, 4f)))
        choices("ink") { r ->
            for (col in CollageArt.INK_COLORS) r.addView(well(col, s.color == col) { s.color = col; board.invalidate(); updateInspector() },
                lp(dp(this, 40f), dp(this, 40f)) { marginEnd = dp(this@CollageActivity, 6f) })
        }
        actions(listOf(
            Triple(Kind.MAGIC, if (s.glow) "光晕 开" else "光晕 关") { s.glow = !s.glow; board.invalidate(); updateInspector() },
            Triple(Kind.FRONT, "置顶") { board.toTop(s) },
            Triple(Kind.TRASH, "删除") { board.remove(s) },
        ))
    }

    private fun stickerPage(s: Board.Sticker) {
        head("贴纸")
        inspector.addView(Glassy.text(this, "单指移动，双指缩放、旋转", 12f, 0xffa8a8b0.toInt()), lp(-1, -2))
        actions(listOf(
            Triple(Kind.FRONT, "置顶") { board.toTop(s) },
            Triple(Kind.BACK, "置底") { board.toBottom(s) },
            Triple(Kind.TRASH, "删除") { board.remove(s) },
        ))
    }

    private fun groundPage() {
        head("背景")
        choices("ground") { r ->
            for (g in Ground.entries) r.addView(tile(g.label, board.ground == g, 50f, 66f, { board.ground = g; board.invalidate(); updateInspector() }) { c, well ->
                c.save(); c.translate(well.left, well.top)
                CollageArt.ground(c, well.width(), well.height(), g, board.mainTones())
                c.restore()
            })
        }
    }

    /**
     * Words: a new label on the board (「文字」) or [label] chosen, its card open with the field
     * focused and the keyboard up — typed straight onto the board, as the background is chosen.
     */
    fun editWords(label: Board.Label?) {
        val l = label ?: Board.Label("文字").also { w ->
            // above the highest print, in the free top of the board; over its corner when there is no room
            val W = board.width.toFloat().coerceAtLeast(1f)
            val top = board.layers.filterIsInstance<Board.Shot>().minOfOrNull { it.cy - it.half(W).second }
            val hh = w.half(W).second
            if (top != null) {
                if (top - 2 * hh - 0.02f > 0.02f) w.cy = top - hh - 0.02f
                else { w.cy = top + hh * 0.2f; w.cx = 0.36f; w.rot = -6f }
            }
            board.add(w)
        }
        if (board.selected !== l) board.select(l)
        wordsField?.let { f ->
            f.postDelayed({
                f.requestFocus()
                if (label == null) f.selectAll()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(f, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 260)
        }
    }

    // ---- photos and saving ------------------------------------------------------------------------

    /** The screen's ground from the first print's photo's colours (a neutral dark before any). */
    private fun paintGround() {
        // the photo's colours, frosted, as the app's glass ground: every pane over it bends them
        val m = resources.displayMetrics
        val g = org.photoall.film.ui.Style.Ambient(dark = true)
        g.own = CollageUi.toneImage(if (::board.isInitialized) board.mainPalette() else null, m.heightPixels.toFloat() / m.widthPixels)
        window.setBackgroundDrawable(g)
        if (::inspector.isInitialized) inspector.invalidate()
    }

    private fun loadPhoto(uri: Uri, replace: Boolean) = worker.execute {
        val b = Stickers.decode(this, uri, 2400) ?: return@execute
        val tones = Tones.of(b)
        val palette = Tones.palette(b)
        val thumb = Bitmap.createScaledBitmap(b, 240, maxOf(1, 240 * b.height / b.width), true)
        runOnUiThread {
            val chosen = board.selected as? Board.Shot
            if (replace && chosen != null) {
                chosen.photo = b; chosen.thumb = thumb; chosen.palette = palette; chosen.uri = uri; chosen.tones = tones; chosen.cropX = 0f; chosen.cropY = 0f; chosen.zoom = 1f
                board.invalidate(); updateInspector()
            } else board.add(Board.Shot(b, uri, tones, main = board.layers.none { it is Board.Shot }).also { it.thumb = thumb; it.palette = palette })
            paintGround()
        }
    }

    private fun save() {
        if (board.layers.isEmpty()) { Toast.makeText(this, "先放一张照片或贴纸", Toast.LENGTH_SHORT).show(); return }
        val prefs = getSharedPreferences("collage", MODE_PRIVATE)
        val n = prefs.getInt("count", 0) + 1
        worker.execute {
            val W = 2400; val H = (W * Board.ASPECT).toInt()
            val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            board.paint(Canvas(out), W.toFloat(), H.toFloat(), n, forExport = true)
            val name = "COLLAGE_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
            val ok = runCatching { org.photoall.film.gallery.Photos.saveJpeg(this, out, name, 95, "拼贴 · 第 $n 格") }.isSuccess
            out.recycle()
            if (ok) prefs.edit().putInt("count", n).apply()
            runOnUiThread {
                Toast.makeText(this, if (ok) "已存进作品 · 第 $n 格（拼贴相机首页 ›「作品」）" else "存储失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** A sticker to put on the board at once (its id). */
        const val EXTRA_STICKER = "sticker"
        /** 一键拼贴: a main photo, then a handful whose subjects are cut out and laid round it. */
        const val EXTRA_AUTO = "auto"

        /** The window's shapes (height over width); null: the photo's own. */
        val RATIOS = listOf<Pair<String, Float?>>("原图" to null, "1:1" to 1f, "4:5" to 1.25f, "3:4" to 4f / 3f, "3:2" to 2f / 3f, "16:9" to 9f / 16f)
    }

    /**
     * The board: the ground, then the layers bottom to top. One finger moves the layer under it,
     * two size and turn it; in [photoMode] they move and zoom the chosen print's photo in its
     * window. A tap on nothing chooses nothing (the ground's panel).
     */
    class Board(context: Context) : View(context) {
        /** A thing on the board: centre and width (shares of the board's width), turn in degrees. */
        abstract class Layer(var cx: Float, var cy: Float, var w: Float, var rot: Float) {
            /** Drawn about (0, 0) in its own turn. */
            abstract fun draw(c: Canvas, W: Float, frame: Int)
            /** Half its width and height, in shares of the board's width. */
            abstract fun half(W: Float): Pair<Float, Float>
        }

        /** A photo on a print. */
        class Shot(var photo: Bitmap?, var uri: Uri?, var tones: IntArray?, main: Boolean) :
            Layer(if (main) 0.5f else 0.5f + rnd(0.5f), if (main) 0.5f * ASPECT else 0.5f * ASPECT + rnd(0.7f), if (main) 0.64f else 0.32f, if (main) 0f else rnd(22f)) {
            var paper = if (main) Paper.CARD else Paper.INSTANT
            var color = paper.color
            var surface = paper.surface
            var ratio: Float? = null
            var cropX = 0f; var cropY = 0f; var zoom = 1f
            /** A small copy for the inspector's previews. */
            var thumb: Bitmap? = null
            /** The photo's colours for the screen's wallpaper ([Tones.palette]). */
            var palette: IntArray? = null
            val overflow = FloatArray(2)
            fun aspect() = paper.window ?: ratio ?: photo?.let { it.height.toFloat() / it.width } ?: 0.75f
            fun geometry(W: Float) = CollageArt.geometry(paper, w * W, aspect())
            override fun draw(c: Canvas, W: Float, frame: Int) =
                CollageArt.print(c, W, geometry(W), paper, color, surface, photo, cropX, cropY, zoom, frame, overflow)
            override fun half(W: Float) = geometry(W).card.let { it.width() / 2 / W to it.height() / 2 / W }
        }

        /** A sticker, as cut. */
        class Sticker(val bmp: Bitmap) : Layer(0.5f + rnd(0.6f), 0.5f * ASPECT + rnd(0.8f), 0.3f, rnd(24f)) {
            override fun draw(c: Canvas, W: Float, frame: Int) {
                val sw = w * W; val sh = sw * bmp.height / bmp.width
                // a sticker lies on the board: a close shadow under its edge
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                p.setShadowLayer(W * 0.008f, 0f, W * 0.004f, 0x55000000)
                c.drawBitmap(bmp, null, RectF(-sw / 2, -sh / 2, sw / 2, sh / 2), p)
            }
            override fun half(W: Float) = w / 2 to w / 2 * bmp.height / bmp.width
        }

        /** Words; [w] is their height. */
        class Label(var text: String) : Layer(0.5f, 0.15f, 0.075f, rnd(8f)) {
            var hand = Hand.CHALK
            var color = 0xfff5f2ea.toInt()
            var glow = true
            override fun draw(c: Canvas, W: Float, frame: Int) { CollageArt.words(c, text, hand, color, glow, w * W) }
            override fun half(W: Float) = CollageArt.words(null, text, hand, color, glow, w * W).let { it.first / W to it.second / W }
        }

        var ground = Ground.AUTO
        val layers = mutableListOf<Layer>()
        var selected: Layer? = null
            private set
        var photoMode = false
        var onSelect: () -> Unit = {}
        /** After every drawing on screen (the activity makes the screen's ground of it). */
        var onDrawn: () -> Unit = {}
        private val d = resources.displayMetrics.density

        /** The first print's photo's tones (the 照片色 ground). */
        fun mainTones() = layers.firstNotNullOfOrNull { (it as? Shot)?.tones }
        /** The first print's photo's wallpaper colours (the screen's ground). */
        fun mainPalette() = layers.firstNotNullOfOrNull { (it as? Shot)?.palette }

        fun add(l: Layer) { layers += l; select(l) }
        fun select(l: Layer?) { selected = l; if (l !is Shot) photoMode = false; invalidate(); onSelect() }
        fun remove(l: Layer) { layers.remove(l); select(null) }
        fun toTop(l: Layer) { layers.remove(l); layers.add(l); invalidate() }
        fun toBottom(l: Layer) { layers.remove(l); layers.add(0, l); invalidate() }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
            val bw = minOf(w.toFloat(), h / ASPECT)
            setMeasuredDimension(bw.toInt(), (bw * ASPECT).toInt())
        }

        override fun onDraw(c: Canvas) {
            val r = 22 * d
            c.save()
            c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, Path.Direction.CW) })
            paint(c, width.toFloat(), height.toFloat(), context.getSharedPreferences("collage", Context.MODE_PRIVATE).getInt("count", 0) + 1, false)
            c.restore()
            if (layers.isEmpty()) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99ffffff.toInt(); textSize = 15 * d; textAlign = Paint.Align.CENTER; typeface = Glassy.semibold }
                c.drawText("点「照片」放进一张", width / 2f, height / 2f, p)
            }
        }

        fun paint(c: Canvas, W: Float, H: Float, frame: Int, forExport: Boolean) {
            CollageArt.ground(c, W, H, ground, mainTones())
            for (l in layers) {
                c.save(); c.translate(l.cx * W, l.cy * W); c.rotate(l.rot)
                l.draw(c, W, frame)
                if (!forExport && l === selected) outline(c, l, W)
                c.restore()
            }
        }

        private fun outline(c: Canvas, l: Layer, W: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.5f * d; pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
                color = if (photoMode) Glassy.YELLOW else 0xd9ffffff.toInt()
            }
            val r = if (photoMode && l is Shot) l.geometry(W).window else {
                val (hw, hh) = l.half(W); val m = 6 * d
                RectF(-hw * W - m, -hh * W - m, hw * W + m, hh * W + m)
            }
            c.drawRoundRect(r, 8 * d, 8 * d, p)
        }

        // ---- touch ----
        private var lastX = 0f; private var lastY = 0f
        private var startDist = 0f; private var startAngle = 0f
        private var startW = 0f; private var startRot = 0f; private var startZoom = 1f
        private var downAt = 0L; private var lastTap = 0L; private var moved = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val W = width.toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downAt = e.eventTime; moved = false
                    if (!photoMode) {
                        val hit = layers.asReversed().firstOrNull { inside(it, e.x / W, e.y / W) }
                        if (hit !== selected) { selected = hit; if (hit !is Shot) photoMode = false; onSelect() }
                    }
                    parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    startDist = dist(e); startAngle = angle(e)
                    selected?.let { startW = it.w; startRot = it.rot }
                    (selected as? Shot)?.let { startZoom = it.zoom }
                }
                MotionEvent.ACTION_MOVE -> {
                    val two = e.pointerCount >= 2 && startDist > 0f
                    val dx = (e.x - lastX) / W; val dy = (e.y - lastY) / W
                    if (hypot(e.x - lastX, e.y - lastY) > 2 * d) moved = true
                    val s = selected
                    val shot = s as? Shot
                    when {
                        s == null -> {}
                        photoMode && shot != null -> if (two) shot.zoom = (startZoom * dist(e) / startDist).coerceIn(1f, 5f)
                            else if (e.pointerCount == 1) {
                                // the finger drags the photo: its overflow moves the other way, in the print's turn
                                val a = Math.toRadians(-s.rot.toDouble())
                                val lx = (dx * Math.cos(a) - dy * Math.sin(a)).toFloat(); val ly = (dx * Math.sin(a) + dy * Math.cos(a)).toFloat()
                                if (shot.overflow[0] > 1e-4f) shot.cropX = (shot.cropX - lx / shot.overflow[0] * (W / width)).coerceIn(-0.5f, 0.5f)
                                if (shot.overflow[1] > 1e-4f) shot.cropY = (shot.cropY - ly / shot.overflow[1] * (W / width)).coerceIn(-0.5f, 0.5f)
                            }
                        two -> {
                            val (lo, hi) = when (s) { is Label -> 0.025f to 0.3f; is Shot -> 0.15f to 1.2f; else -> 0.06f to 1.4f }
                            s.w = (startW * dist(e) / startDist).coerceIn(lo, hi)
                            s.rot = startRot + Math.toDegrees((angle(e) - startAngle).toDouble()).toFloat()
                        }
                        e.pointerCount == 1 -> { s.cx += dx; s.cy += dy }
                    }
                    if (e.pointerCount == 1) { lastX = e.x; lastY = e.y }
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    startDist = 0f
                    val stay = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(stay); lastY = e.getY(stay)
                }
                MotionEvent.ACTION_UP -> {
                    // a double tap on words: change them
                    if (!moved && e.eventTime - downAt < 250) {
                        if (e.eventTime - lastTap < 320) (selected as? Label)?.let { l -> (context as? CollageActivity)?.editWords(l) }
                        lastTap = e.eventTime
                    }
                }
            }
            return true
        }

        /** Whether (x, y) (shares of the board's width) lies on [l], turned with it. */
        private fun inside(l: Layer, x: Float, y: Float): Boolean {
            val (hw, hh) = l.half(width.toFloat())
            val a = Math.toRadians(-l.rot.toDouble())
            val dx = x - l.cx; val dy = y - l.cy
            val lx = dx * Math.cos(a) - dy * Math.sin(a); val ly = dx * Math.sin(a) + dy * Math.cos(a)
            return kotlin.math.abs(lx) <= hw && kotlin.math.abs(ly) <= hh
        }

        private fun dist(e: MotionEvent) = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
        private fun angle(e: MotionEvent) = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))

        companion object {
            const val ASPECT = 4f / 3f
            private fun rnd(span: Float) = (Math.random().toFloat() - 0.5f) * span
        }
    }
}
