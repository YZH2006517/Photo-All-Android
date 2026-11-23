package org.photoall.film.gallery

import android.content.Intent
import android.animation.LayoutTransition
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.photoall.film.gallery.FrameDesign.Arrange
import org.photoall.film.gallery.FrameDesign.Border
import org.photoall.film.gallery.FrameDesign.Font
import org.photoall.film.gallery.FrameDesign.Item
import org.photoall.film.gallery.FrameDesign.Logo
import org.photoall.film.gallery.FrameDesign.Stamp
import org.photoall.film.settings.Settings
import org.photoall.film.settings.SettingsStore
import org.photoall.film.ui.Icon
import org.photoall.film.ui.Style
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The border editor: the photo on a quiet grid, its border drawn live, and a floating
 * tool bar below. Each tool opens a card above the bar: 版式 (whole designs to start
 * from), 边框, 颜色 (including colours taken from the photo), 文字 (which lines, which
 * face, which mark), 排列 (on the border or in the picture, across or upright, how the
 * lines combine, where), 时间戳 (an old camera's date). 存储 saves a bordered copy at full
 * size next to the original; the design is kept, and new shots can get it too.
 */
class FrameActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: SettingsStore
    private lateinit var design: FrameDesign
    private lateinit var preview: ImageView
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: FrameLayout
    private lateinit var toolbar: LinearLayout
    private var small: Bitmap? = null      // the photo at preview size
    private var upright: Bitmap? = null    // the same, turned as asked
    private var turns = 0                  // quarter turns clockwise
    private var tiny: Bitmap? = null       // for the design thumbnails
    private var palette: List<Int> = emptyList()
    private var data: FrameDesigner.Data? = null
    @Volatile private var ticket = 0
    /** The design thumbnails: drawn on their own thread so the preview never waits behind them, and kept. */
    private val thumbs = Executors.newSingleThreadExecutor()
    private val thumbCache = HashMap<String, Bitmap>()
    private var tab: Tab? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val uri by lazy { intent.getParcelableExtra<Uri>(URI)!! }
    private val frame by lazy { intent.getIntExtra(FRAME, 0) }
    private val film by lazy { intent.getStringExtra(FILM) ?: "" }
    private val taken by lazy { intent.getLongExtra(TAKEN, System.currentTimeMillis()) }
    /** Straight after an import: blank to begin with and on 还原, and the album's design left alone. */
    private val blank by lazy { intent.getBooleanExtra(BLANK, false) }

    /** The design kept for next time (the album's border page only). */
    private fun keep() { if (!blank) FrameDesign.save(this, design) }

    /** The next picture of an import, to its own border page. */
    override fun finish() {
        val first = !isFinishing
        super.finish()
        if (!first) return
        @Suppress("DEPRECATION")
        val queue = intent.getParcelableArrayListExtra<Intent>(QUEUE) ?: return
        if (queue.isEmpty()) return
        startActivity(queue.first().putParcelableArrayListExtra(QUEUE, ArrayList(queue.drop(1))))
    }

    // Apple-like neutrals for this page, light or dark with the theme
    private val light by lazy { Style.palette.light }
    private val canvasColor by lazy { if (light) 0xfff2f2f6.toInt() else 0xff0b0b0c.toInt() }
    private val gridColor by lazy { if (light) 0xffe3e3e9.toInt() else 0xff19191c.toInt() }
    private val cardColor by lazy { if (light) 0xffffffff.toInt() else 0xff1c1c1e.toInt() }
    private val fillColor by lazy { if (light) 0xfff2f2f7.toInt() else 0xff2c2c2e.toInt() }
    private val textColor by lazy { if (light) 0xff1c1c1e.toInt() else 0xfff5f5f7.toInt() }
    private val text2Color by lazy { if (light) 0xff8e8e93.toInt() else 0xff98989f.toInt() }

    private enum class Tab(val label: String) { LAYOUT("版式"), BORDER("边框"), COLOR("颜色"), TEXT("文字"), ARRANGE("排列"), STAMP("时间戳") }

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        design = if (blank) FrameDesign.blank() else FrameDesign.load(this)
        window.statusBarColor = canvasColor
        window.navigationBarColor = canvasColor

        preview = CartridgePreview(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            elevation = dp(14).toFloat()
            outlineProvider = ViewOutlineProvider.BOUNDS
        }
        val stage = PreviewBox(this, preview).apply { setPadding(dp(28), dp(12), dp(28), dp(16)) }

        // top: a round back key, a capsule with 还原 and 存储
        val top = FrameLayout(this).apply {
            addView(floating(ImageView(this@FrameActivity).apply {
                contentDescription = "返回"
                setImageDrawable(Icon(Icon.Kind.BACK, textColor))
                setPadding(dp(11), dp(11), dp(11), dp(11))
                setOnClickListener { finish() }
            }, radius = 22f), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(16) })
            addView(floating(ImageView(this@FrameActivity).apply {
                contentDescription = "旋转照片（顺时针 90°）"
                setImageDrawable(Icon(Icon.Kind.ROTATE, textColor))
                setPadding(dp(11), dp(11), dp(11), dp(11))
                setOnClickListener { rotate() }
            }, radius = 22f), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(70) })
            addView(floating(LinearLayout(this@FrameActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
                addView(capsuleText("还原", textColor) { reset() })
                addView(View(this@FrameActivity).apply { setBackgroundColor(gridColor) }, LinearLayout.LayoutParams(dp(1), dp(18)))
                addView(capsuleText("存储", Style.ACCENT, bold = true) { save() })
            }, radius = 22f), FrameLayout.LayoutParams(-2, dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
        }

        panelTitle = TextView(this).apply {
            textSize = 13f; typeface = Style.medium; setTextColor(text2Color); letterSpacing = 0.04f
        }
        panelBody = FrameLayout(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(24).toFloat() }
            elevation = dp(10).toFloat()
            visibility = View.GONE
            addView(panelTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            addView(MaxHeightScroll(this@FrameActivity, (resources.displayMetrics.heightPixels * 0.34f).toInt()).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(panelBody, FrameLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(32).toFloat() }
            elevation = dp(10).toFloat()
            for (t in Tab.entries) addView(FrameLayout(this@FrameActivity).apply {
                contentDescription = t.label
                tag = t
                addView(ImageView(this@FrameActivity).apply {
                    setImageDrawable(ToolIcon(t, text2Color))
                }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                setOnClickListener { open(t) }
                Style.pressable(this)
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(260)
            }
            addView(top, LinearLayout.LayoutParams(-1, dp(64)))
            addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(panel, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(10) })
            addView(toolbar, LinearLayout.LayoutParams(-1, dp(54)).apply { marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(10) })
        }
        setContentView(FrameLayout(this).apply {
            background = GridBackdrop(canvasColor, gridColor, dp(22).toFloat())
            addView(column, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })

        worker.later {
            val shot = ShotInfo.read(this, uri)
            val photo = decode(1600)
            val d = FrameDesigner.data(this, shot, intent.getStringExtra(DESCRIPTION) ?: "", film, frame, taken)
            val p = photo?.let { FrameDesigner.palette(it) } ?: emptyList()
            val t = photo?.let { Bitmap.createScaledBitmap(it, maxOf(1, it.width * 260 / maxOf(it.width, it.height)),
                maxOf(1, it.height * 260 / maxOf(it.width, it.height)), true) }
            runOnUiThread {
                small = photo; tiny = t; palette = p; data = d
                refresh()
                tab?.let { fillPanel(it) }
            }
        }
        // the first card open, as an invitation
        main.postDelayed({ if (tab == null) open(Tab.LAYOUT) }, 350)
    }

    override fun onResume() {
        super.onResume()
        org.photoall.film.ui.CartridgeSprites.attach(this)
    }

    override fun onDestroy() {
        org.photoall.film.ui.CartridgeSprites.detach(this)
        worker.shutdownNow()
        thumbs.shutdownNow()
        super.onDestroy()
    }

    // ---- preview -----------------------------------------------------------------------------

    private val redraw = Runnable { refresh() }

    /** Something changed: keep it, and draw it again (soon, so a dragged slider stays smooth). */
    private fun changed(rebuild: Boolean = false) {
        main.removeCallbacks(quick)
        keep()
        main.removeCallbacks(redraw)
        main.postDelayed(redraw, 30)
        if (rebuild) tab?.let { fillPanel(it, animate = false) }
    }

    /** [b] turned [turns] quarter turns clockwise (itself when none). */
    private fun turned(b: Bitmap): Bitmap {
        if (turns % 4 == 0) return b
        val m = android.graphics.Matrix().apply { setRotate(90f * turns) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun quarter(b: Bitmap): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { setRotate(90f) }, true)

    /** A quarter turn clockwise: the preview turns as it goes, then the border is drawn round the new shape. */
    private fun rotate() {
        val photo = small ?: return
        turns = (turns + 1) % 4
        preview.animate().rotationBy(90f).scaleX(0.86f).scaleY(0.86f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction {
                upright = turned(photo)
                tiny = tiny?.let { quarter(it) } // the design thumbnails turn with it
                refreshTurned = true
                refresh()
            }.start()
    }

    private var refreshTurned = false

    /** The photo as the preview lays it out (turned as it is turned): its size. */
    private var photoW = 0
    private var photoH = 0
    private var shownPhoto: Bitmap? = null
    /** A film border's picture on the preview, as shares of it (null for other borders). */
    private var filmPicture: RectF? = null
    private var lastQuick = 0L

    /** While a finger moves the photo: the sheet laid out at a small size, at most every 60 ms. */
    private fun quickRefresh() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastQuick < 60) { main.removeCallbacks(quick); main.postDelayed(quick, 60); return }
        lastQuick = now
        refresh(quick = true)
    }
    private val quick = Runnable { lastQuick = android.os.SystemClock.uptimeMillis(); refresh(quick = true) }

    /**
     * The sheet with an empty place where the photo goes: the background while a finger moves
     * or sizes the photo (drawn live on top). One render when the photo is picked up, none
     * while it moves, so a slow sheet never trails a stale photo behind the finger.
     */
    private fun refreshBare() = refresh(quick = true, bare = true)

    private fun refresh(quick: Boolean = false, bare: Boolean = false) {
        val shot = (if (turns % 4 == 0) small else upright ?: small?.let { turned(it).also { u -> upright = u } }) ?: return
        photoW = shot.width; photoH = shot.height; shownPhoto = shot
        val photo = if (!bare) shot else
            Bitmap.createBitmap(maxOf(1, shot.width / 8), maxOf(1, shot.height / 8), Bitmap.Config.ARGB_8888)
        val d = data ?: return
        // the cartridge is drawn live over the preview (CartridgePreview), not into it
        val snapshot = design.copy().apply { cartridge = false }
        val t = ++ticket
        val picture = RectF()
        worker.later {
            if (t != ticket) return@later // a newer change is already on its way
            // the 3D cartridge of a poster is drawn when ready, without holding this one up
            org.photoall.film.ui.CartridgeSprites.onScreen.set { if (t == ticket) refresh() }
            val shown = try {
                runCatching { FrameDesigner.render(photo, snapshot, d, maxLong = if (quick) 640 else 1400, pictureOut = picture) }.getOrNull()
            } finally {
                org.photoall.film.ui.CartridgeSprites.onScreen.set(null)
            } ?: return@later
            runOnUiThread {
                if (t != ticket) return@runOnUiThread
                filmPicture = if (snapshot.border.kind != null && !picture.isEmpty) picture else null
                val first = preview.drawable == null
                preview.setImageBitmap(shown)
                if (refreshTurned) {
                    // the new shape lands without the turn: settle from small to full size
                    refreshTurned = false
                    preview.rotation = 0f
                    preview.scaleX = 0.86f; preview.scaleY = 0.86f
                    preview.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
                }
                if (first) {
                    preview.alpha = 0f; preview.scaleX = 0.94f; preview.scaleY = 0.94f
                    preview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
                }
            }
        }
    }

    // ---- the tool bar and its cards ----------------------------------------------------------

    private fun open(t: Tab) {
        if (tab == t && panel.visibility == View.VISIBLE) {
            tab = null
            panel.animate().alpha(0f).translationY(dp(16).toFloat()).setDuration(160).withEndAction { panel.visibility = View.GONE }.start()
            markTabs()
            return
        }
        val wasOpen = panel.visibility == View.VISIBLE
        tab = t
        markTabs()
        fillPanel(t, animate = wasOpen)
        if (!wasOpen) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.translationY = dp(24).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
    }

    private fun markTabs() {
        for (i in 0 until toolbar.childCount) {
            val cell = toolbar.getChildAt(i) as FrameLayout
            val on = cell.tag == tab
            (cell.getChildAt(0) as ImageView).setImageDrawable(ToolIcon(cell.tag as Tab, if (on) textColor else text2Color))
            cell.background = if (on) GradientDrawable().apply { setColor(fillColor); cornerRadius = dp(20).toFloat() } else null
        }
    }

    private fun fillPanel(t: Tab, animate: Boolean = false) {
        panelTitle.text = t.label
        val body = when (t) {
            Tab.LAYOUT -> layoutCard()
            Tab.BORDER -> borderCard()
            Tab.COLOR -> colorCard()
            Tab.TEXT -> textCard()
            Tab.ARRANGE -> arrangeCard()
            Tab.STAMP -> stampCard()
        }
        panelBody.removeAllViews()
        panelBody.addView(body, FrameLayout.LayoutParams(-1, -2))
        if (animate) {
            body.alpha = 0f; body.translationX = dp(12).toFloat()
            body.animate().alpha(1f).translationX(0f).setDuration(200).start()
        }
    }

    // 版式: whole designs, each shown on this photo
    private fun layoutCard(): View = column {
        val strip = LinearLayout(this@FrameActivity)
        addView(HorizontalScrollView(this@FrameActivity).apply { isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength((28 * resources.displayMetrics.density).toInt());
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(strip)
        }, LinearLayout.LayoutParams(-1, -2))
        for (preset in FrameDesign.presets) {
            val thumb = ImageView(this@FrameActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply { setColor(fillColor); cornerRadius = dp(12).toFloat() }
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            strip.addView(LinearLayout(this@FrameActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(thumb, LinearLayout.LayoutParams(dp(62), dp(76)))
                addView(TextView(this@FrameActivity).apply {
                    text = preset.name; textSize = 11f; setTextColor(textColor); gravity = Gravity.CENTER; maxLines = 1
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                Style.pressable(this)
                setOnClickListener {
                    // a new layout keeps the cartridge where it was put
                    val keep = design
                    design = preset.build(palette, design.caption).apply {
                        cartridge = keep.cartridge; cartX = keep.cartX; cartY = keep.cartY; cartSize = keep.cartSize
                    }
                    changed()
                }
            }, LinearLayout.LayoutParams(dp(68), -2).apply { marginEnd = dp(6) })
            val photo = tiny
            val d = data
            val key = "${preset.name}|$turns|${design.caption}"
            val kept = thumbCache[key]
            if (kept != null) thumb.setImageBitmap(kept)
            else if (photo != null && d != null) {
                fun draw() {
                    thumbs.later {
                        org.photoall.film.ui.CartridgeSprites.onScreen.set { draw() }
                        val bmp = try {
                            runCatching { FrameDesigner.render(photo, preset.build(palette, design.caption), d, maxLong = 220) }.getOrNull()
                        } finally {
                            org.photoall.film.ui.CartridgeSprites.onScreen.set(null)
                        }
                        runOnUiThread { if (bmp != null) { thumbCache[key] = bmp; thumb.setImageBitmap(bmp) } }
                    }
                }
                draw()
            }
        }
        fold("更多", listOfNotNull("暗盒".takeIf { design.cartridge }, "自动套用".takeIf { store.flag(Settings.FRAME_AUTO) }).joinToString(" · ")) {
            addView(switchRow("画面上放胶卷暗盒", "在预览上拖动挪位置，双指捏合调大小", design.cartridge) { on ->
                design.cartridge = on
                changed()
                preview.invalidate()
            }, LinearLayout.LayoutParams(-1, -2))
            addView(switchRow("拍照时自动套用", "每张另存一份带这个边框的，原图照常保存", store.flag(Settings.FRAME_AUTO)) { on ->
                store[Settings.FRAME_AUTO] = if (on) 1f else 0f
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    // 边框: the kind of border, its width, the photo's corners and shadow
    private fun borderCard(): View = column {
        fun group(b: Border) = when { b.film -> 0; b == Border.NONE -> 1; b == Border.CUSTOM -> 2; b.theme != null -> 4; else -> 3 }
        addView(choices(Border.entries.map { it.label }, setOf(design.border.ordinal)) { picked ->
            val before = design.border
            design.border = Border.entries[picked.first()]
            if (design.border != before) design.placed = false
            changed(rebuild = group(before) != group(design.border))
        })
        if (design.border.film) {
            val stock = data?.filmKey?.takeIf { it.isNotBlank() && it != "直出" }
                ?.let { org.photoall.film.ui.CanisterView.displayName(it) }
            fold("片边细节", stock ?: "PHOTO ALL") {
            addView(hint((if (stock != null) "片边按这张照片的胶卷印：$stock" else "这张照片没用胶卷，片边印 PHOTO ALL") +
                "。画幅和照片比例不同时，按住画面拖动，选裁哪一部分"),
                LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(this@FrameActivity).apply {
                isBaselineAligned = false
                addView(choices(listOf("边缘灰雾"), if (design.fog) setOf(0) else emptySet(), multi = true, scroll = false) {
                    design.fog = 0 in it; changed()
                }, LinearLayout.LayoutParams(-2, -2))
                addView(chip("换一种漏光", false) { design.leakSeed++; changed() },
                    LinearLayout.LayoutParams(-2, dp(34)).apply { marginStart = dp(8) })
                addView(chip("↻ 转 90°", false) { design.turn = (design.turn + 90) % 360; changed() },
                    LinearLayout.LayoutParams(-2, dp(34)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(slider("漏光", design.leak) { design.leak = it; changed() })
            }
        } else if (design.border.theme != null) {
            addView(LinearLayout(this@FrameActivity).apply {
                addView(chip("换一张纸", false) { design.leakSeed++; changed() }, LinearLayout.LayoutParams(-2, dp(34)))
                if (design.placed) addView(chip("照片复位", false) { design.placed = false; changed(rebuild = true) },
                    LinearLayout.LayoutParams(-2, dp(34)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(hint("长按照片拖动位置；双击照片调大小"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        } else if (design.border == Border.CUSTOM) {
            // the card made to measure: its proportions, the photo's size and place, its colour
            addView(section("画幅比例"))
            addView(choices(FrameDesign.Ratio.entries.map { it.label }, setOf(design.ratio.ordinal)) {
                design.ratio = FrameDesign.Ratio.entries[it.first()]; changed()
            })
            fold("照片大小与位置") {
            addView(LinearLayout(this@FrameActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@FrameActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(slider("照片", (design.photoScale - 0.4f) / 0.6f) { design.photoScale = 0.4f + 0.6f * it; changed() })
                    addView(slider("圆角", design.corner) { design.corner = it; changed() })
                    addView(choices(listOf("照片投影"), if (design.shadow) setOf(0) else emptySet(), multi = true) { picked ->
                        design.shadow = 0 in picked; changed()
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(LinearLayout(this@FrameActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(section("照片位置"))
                    addView(AnchorPad(this@FrameActivity, 3, design.photoAnchor) { design.photoAnchor = it; changed() },
                        LinearLayout.LayoutParams(dp(84), dp(84)))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            })
            }
            fold("颜色") {
            addView(swatches(listOf(FrameDesign.WHITE, FrameDesign.PAPER, FrameDesign.BLACK, FrameDesign.BLUR) + palette,
                design.background) { design.background = it; changed() })
            }
        } else if (design.border != Border.NONE) {
            addView(slider("宽度", design.thickness) { design.thickness = it; changed() })
            fold("圆角与投影") {
            addView(slider("圆角", design.corner) { design.corner = it; changed() })
            addView(choices(listOf("照片投影"), if (design.shadow) setOf(0) else emptySet(), multi = true) { picked ->
                design.shadow = 0 in picked; changed()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
    }

    // 颜色: the card behind the photo and the ink, plain or taken from the photo
    private fun colorCard(): View = column {
        addView(section("背景"))
        val backs = listOf(FrameDesign.WHITE, FrameDesign.PAPER, FrameDesign.BLACK, FrameDesign.BLUR) + palette
        addView(swatches(backs, design.background) { design.background = it; changed() })
        addView(section("文字与标志"))
        val inks = listOf(FrameDesign.AUTO, 0xff1c1c1e.toInt(), 0xfff5f5f7.toInt()) + palette
        addView(swatches(inks, design.ink) { design.ink = it; changed() })
        if (palette.isNotEmpty()) addView(hint("后面几种颜色取自这张照片"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    // 文字: which lines, the user's own words, the face, the mark, the size
    private fun textCard(): View = column {
        addView(section("内容", first = true))
        addView(choices(Item.entries.map { it.label }, design.items.map { it.ordinal }.toSet(), multi = true) { picked ->
            val hadCaption = Item.CAPTION in design.items
            design.items.clear()
            picked.sorted().forEach { design.items += Item.entries[it] }
            changed(rebuild = hadCaption != (Item.CAPTION in design.items))
        })
        if (Item.CAPTION in design.items) addView(EditText(this@FrameActivity).apply {
            setText(design.caption)
            hint = "写一句话、签名或水印"
            textSize = 15f
            isSingleLine = true
            setTextColor(textColor)
            setHintTextColor(text2Color)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(fillColor); cornerRadius = dp(12).toFloat() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { design.caption = e?.toString() ?: ""; changed() }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        fold("品牌、字体与标志", "${design.font.label} · ${design.logo.label}") {
        addView(section("品牌", first = true))
        val maker = data?.maker?.let { FrameDesigner.BRANDS[it]?.text } ?: data?.brand ?: ""
        addView(choices(listOf("$maker 品牌字标", "跟随字体"), setOf(if (design.brandMark) 0 else 1), scroll = false) {
            design.brandMark = it.first() == 0; changed()
        })
        addView(section("字体"))
        addView(choices(Font.entries.map { it.label }, setOf(design.font.ordinal)) { design.font = Font.entries[it.first()]; changed() })
        addView(section("标志"))
        addView(choices(Logo.entries.map { it.label }, setOf(design.logo.ordinal)) { design.logo = Logo.entries[it.first()]; changed() })
        }
        addView(slider("字号", design.size) { design.size = it; changed() })
    }

    // 排列: on the border or in the picture, across or upright, how the lines combine, where
    private fun arrangeCard(): View = column {
        addView(LinearLayout(this@FrameActivity).apply {
            isBaselineAligned = false
            addView(choices(listOf("边框上", "照片里"), setOf(if (design.inPhoto) 1 else 0), scroll = false) {
                design.inPhoto = it.first() == 1; changed()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(choices(listOf("横排", "竖排"), setOf(if (design.vertical) 1 else 0), scroll = false) {
                design.vertical = it.first() == 1; changed()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        })
        addView(LinearLayout(this@FrameActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@FrameActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(section("组合"))
                addView(choices(Arrange.entries.map { it.label }, setOf(design.arrange.ordinal), scroll = false, wrap = 2) {
                    design.arrange = Arrange.entries[it.first()]; changed()
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(this@FrameActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(section("位置"))
                // a place picked by hand is kept: the automatic layout steps aside
                addView(AnchorPad(this@FrameActivity, 3, design.anchor) {
                    design.anchor = it
                    val was = design.autoPlace
                    design.autoPlace = false
                    changed(rebuild = was)
                }, LinearLayout.LayoutParams(dp(92), dp(92)))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        })
        addView(choices(listOf("自动避让"), if (design.autoPlace) setOf(0) else emptySet(), multi = true, scroll = false) {
            design.autoPlace = 0 in it; changed()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        fold("说明") {
            addView(hint(if (design.autoPlace) "文字和日期会自己找没被遮挡、画面最干净的位置；在上面点一个位置就改为手动"
                else "手动：文字放在你点的位置。打开自动避让，让它自己找位置"))
        }
    }

    // 时间戳: an old camera's date back
    private fun stampCard(): View = column {
        addView(choices(Stamp.entries.map { it.label }, setOf(design.stamp.ordinal)) { design.stamp = Stamp.entries[it.first()]; changed() })
        addView(LinearLayout(this@FrameActivity).apply {
            gravity = Gravity.TOP
            addView(LinearLayout(this@FrameActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(section("颜色"))
                addView(swatches(FrameDesign.STAMP_COLOURS.toList() + palette.take(3), design.stampColor) { design.stampColor = it; changed() })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(this@FrameActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(section("角落"))
                addView(AnchorPad(this@FrameActivity, 2, design.stampCorner) { design.stampCorner = it; changed() },
                    LinearLayout.LayoutParams(dp(72), dp(72)))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        })
    }

    // ---- controls ------------------------------------------------------------------------------

    private fun column(fill: LinearLayout.() -> Unit) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fill() }

    /** Folds opened by hand, kept while the page is open. */
    private val unfolded = HashSet<String>()

    /** A titled group shut by default: one line with what is set, opened with a tap. */
    private fun LinearLayout.fold(title: String, summary: String = "", fill: LinearLayout.() -> Unit) {
        val body = column(fill).apply { visibility = if (title in unfolded) View.VISIBLE else View.GONE }
        val arrow = TextView(this@FrameActivity).apply {
            text = "\u203a"; textSize = 18f; setTextColor(text2Color); gravity = Gravity.CENTER
            rotation = if (title in unfolded) 90f else 0f
        }
        addView(LinearLayout(this@FrameActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(TextView(this@FrameActivity).apply {
                text = title; textSize = 13f; setTextColor(textColor); typeface = Style.medium
            }, LinearLayout.LayoutParams(-2, -2))
            addView(TextView(this@FrameActivity).apply {
                text = summary; textSize = 12f; setTextColor(text2Color); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(6) })
            addView(arrow, LinearLayout.LayoutParams(dp(20), dp(24)))
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                if (open) unfolded += title else unfolded -= title
                body.visibility = if (open) View.VISIBLE else View.GONE
                arrow.animate().rotation(if (open) 90f else 0f).setDuration(180).start()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        addView(body, LinearLayout.LayoutParams(-1, -2))
    }

    /** Scrolls what does not fit under [max] pixels. */
    private class MaxHeightScroll(context: Context, private val max: Int) : android.widget.ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
        }
    }

    private fun section(text: String, first: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(text2Color); letterSpacing = 0.04f
        setPadding(0, if (first) 0 else dp(12), 0, dp(6))
    }

    private fun hint(text: String) = TextView(this).apply { this.text = text; textSize = 12f; setTextColor(text2Color) }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        isSingleLine = true
        includeFontPadding = false
        setPadding(dp(12), 0, dp(12), 0)
        paintChip(this, on)
        Style.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun paintChip(v: TextView, on: Boolean) {
        v.background = GradientDrawable().apply { setColor(if (on) textColor else fillColor); cornerRadius = dp(17).toFloat() }
        v.setTextColor(if (on) cardColor else textColor)
        v.typeface = if (on) Style.medium else Typeface.DEFAULT
    }

    /**
     * A row of chips, one picked (or several with [multi]); the row repaints itself and
     * hands the picked indexes back. [wrap] > 0 lays them out in rows of that many.
     */
    private fun choices(labels: List<String>, selected: Set<Int>, multi: Boolean = false, scroll: Boolean = true,
                        wrap: Int = 0, onChange: (Set<Int>) -> Unit): View {
        val picked = selected.toMutableSet()
        val chips = mutableListOf<TextView>()
        labels.forEachIndexed { i, label ->
            chips += chip(label, i in picked) {
                if (multi) { if (!picked.remove(i)) picked += i } else { picked.clear(); picked += i }
                chips.forEachIndexed { j, c -> paintChip(c, j in picked) }
                onChange(picked.toSet())
            }
        }
        if (wrap > 0) return column {
            chips.chunked(wrap).forEachIndexed { r, rowChips ->
                addView(LinearLayout(this@FrameActivity).apply {
                    isBaselineAligned = false
                    rowChips.forEachIndexed { k, c -> addView(c, LinearLayout.LayoutParams(0, dp(34), 1f).apply { if (k > 0) marginStart = dp(6) }) }
                }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = dp(6) })
            }
        }
        val row = LinearLayout(this).apply { isBaselineAligned = false }
        chips.forEachIndexed { k, c ->
            row.addView(c, if (scroll) LinearLayout.LayoutParams(-2, dp(34)).apply { if (k > 0) marginStart = dp(6) }
                else LinearLayout.LayoutParams(0, dp(34), 1f).apply { if (k > 0) marginStart = dp(6) })
        }
        if (!scroll) return row
        return HorizontalScrollView(this).apply { isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength((28 * resources.displayMetrics.density).toInt()); isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this)
        val views = mutableListOf<Swatch>()
        for (c in colors.distinct()) {
            val v = Swatch(this, c, c == selected, small)
            views += v
            v.setOnClickListener {
                views.forEach { it.on = it.color == c }
                onPick(c)
            }
            row.addView(v, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) })
        }
        return HorizontalScrollView(this).apply { isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength((28 * resources.displayMetrics.density).toInt()); isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun slider(label: String, value: Float, onChange: (Float) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(TextView(this@FrameActivity).apply { text = label; textSize = 13f; setTextColor(textColor) },
            LinearLayout.LayoutParams(dp(44), -2))
        addView(PillSlider(this@FrameActivity, value, fillColor, textColor, onChange), LinearLayout.LayoutParams(0, dp(32), 1f))
    }

    private fun switchRow(title: String, detail: String, on: Boolean, onToggle: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@FrameActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@FrameActivity).apply { text = title; textSize = 14f; setTextColor(textColor) })
            addView(TextView(this@FrameActivity).apply { text = detail; textSize = 11f; setTextColor(text2Color) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(android.widget.Switch(this@FrameActivity).apply {
            isChecked = on
            Style.switch(this)
            setOnCheckedChangeListener { _, value -> onToggle(value) }
        })
    }

    private fun floating(v: View, radius: Float): View = v.apply {
        background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(radius.toInt()).toFloat() }
        elevation = dp(6).toFloat()
        Style.pressable(this)
    }

    private fun capsuleText(text: String, color: Int, bold: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(color)
        typeface = if (bold) Style.medium else Typeface.DEFAULT
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -1)
    }

    // ---- actions -------------------------------------------------------------------------------

    private fun reset() {
        repeat((4 - turns) % 4) { tiny = tiny?.let { t -> quarter(t) } }
        turns = 0
        upright = null
        design = if (blank) FrameDesign.blank() else FrameDesign.presets[0].build(palette, design.caption)
        changed()
        tab?.let { fillPanel(it, animate = true) }
    }

    /** The bordered copy at full size, saved next to the original (same folder, same EXIF). */
    private fun save() {
        val d = data ?: return
        if (design.empty) {
            Toast.makeText(this, "这个设计什么都没加", Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = design.copy()
        val name = intent.getStringExtra(NAME) ?: "FILM_frame.jpg"
        val private = intent.getBooleanExtra(PRIVATE, false)
        val description = intent.getStringExtra(DESCRIPTION) ?: ""
        if (saving != null) return
        // a 3D cartridge at the photo's full size takes a few seconds to draw: said, and the page held
        val cartridge = snapshot.cartridge || snapshot.border == Border.FILM
        saving = savingVeil(if (cartridge) "正在渲染 3D 暗盒，请稍候…" else "正在存储…",
            if (cartridge) "按原图尺寸渲染，可能需要十几秒" else null)
        worker.later {
            val result = runCatching {
                val raw = decode(8192) ?: error("读不出原图")
                val full = turned(raw).also { if (it !== raw) raw.recycle() }
                val framed = FrameDesigner.render(full, snapshot, d, maxLong = 8192)
                full.recycle()
                Photos.saveJpeg(this, framed, name.removeSuffix(".jpg") + "_frame.jpg", store[Settings.JPEG_QUALITY].roundToInt(),
                    listOf(description, "边框 ${snapshot.border.label}").filter { it.isNotEmpty() }.joinToString(" · "),
                    ShotInfo.read(this, uri), private = private)
                framed.recycle()
            }
            runOnUiThread {
                saving?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
                saving = null
                result.onSuccess { Toast.makeText(this, "已存储带边框的照片", Toast.LENGTH_SHORT).show(); finish() }
                    .onFailure { Toast.makeText(this, "存储失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** The veil over the page while a save runs: what it is doing, a spinner, no taps through. */
    private var saving: View? = null

    private fun savingVeil(title: String, detail: String?): View {
        val veil = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true // the page under it waits
            alpha = 0f
            addView(LinearLayout(this@FrameActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(22), dp(28), dp(22))
                background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(22).toFloat() }
                elevation = dp(12).toFloat()
                addView(android.widget.ProgressBar(this@FrameActivity).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(org.photoall.film.ui.Style.ACCENT)
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(this@FrameActivity).apply {
                    text = title; textSize = 15f; setTextColor(textColor); gravity = Gravity.CENTER
                    typeface = org.photoall.film.ui.Style.medium
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
                if (detail != null) addView(TextView(this@FrameActivity).apply {
                    text = detail; textSize = 12f; setTextColor(text2Color); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        (window.decorView as ViewGroup).addView(veil, ViewGroup.LayoutParams(-1, -1))
        veil.animate().alpha(1f).setDuration(180).start()
        return veil
    }

    private fun decode(maxLong: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val s = minOf(1f, maxLong.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ---- views of this page --------------------------------------------------------------------

    /**
     * The preview, with the cartridge drawn live over it: one finger on the cartridge moves
     * it, two fingers anywhere make it bigger or smaller. On a theme's sheet the photo can be
     * moved and sized: a long press picks it up to drag it; a double tap shows its corners, to
     * pull one or pinch (a tap outside, or another double tap, puts the corners away). While a
     * finger is on it the photo is drawn where the finger is, at once; the sheet round it is
     * laid out again behind it, quickly, and properly when the finger lifts. The preview has
     * the sheet's own proportions, so places and sizes, kept as shares of the sheet, are the saved ones.
     */
    /** The photo on a theme's sheet: untouched, held (a long press coming?), being moved, its corners out. */
    private enum class PlaceMode { IDLE, HOLD, MOVING, SIZING }

    private inner class CartridgePreview(context: Context) : ImageView(context) {
        private var dragging = false
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private val density = resources.displayMetrics.density
        private val pinch = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: android.view.ScaleGestureDetector): Boolean {
                if (mode != PlaceMode.IDLE) {
                    design.placeSize *= det.scaleFactor
                    keepOnSheet()
                    live()
                    return true
                }
                design.cartSize = (design.cartSize * det.scaleFactor).coerceIn(0.08f, 0.8f)
                moved = true
                invalidate()
                return true
            }
        })

        // ---- the photo on a theme's sheet ----
        private var mode = PlaceMode.IDLE
        private var pulling = false          // a corner is being pulled (SIZING)
        private var touching = false         // a finger is on the photo (draw it live)
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var lastTapAt = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        private var startSize = 0f
        private var startDist = 1f
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private val pickUp = Runnable {
            if (mode == PlaceMode.HOLD) {
                begin(); mode = PlaceMode.MOVING; touching = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                live()
            }
        }
        /** The bare sheet is behind the photo now (until the finger lifts). */
        private var bare = false
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.2f * density; color = 0xeeffffff.toInt()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        private val dashShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * density; color = 0x55000000 }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
        private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000; maskFilter = android.graphics.BlurMaskFilter(6f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        /** The photo's rectangle in this view (null where it cannot be moved). */
        private fun photoRect(): RectF? {
            if (design.border.theme == null || photoW <= 0 || photoH <= 0 || width == 0) return null
            if (design.placed && mode != PlaceMode.IDLE && mode != PlaceMode.HOLD) {
                val w = design.placeSize * width
                val h = w * photoH / photoW
                val cx = design.placeX * width; val cy = design.placeY * height
                return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            }
            val f = FrameDesigner.themePhoto(photoW, photoH, design) ?: return null
            return RectF(f.left * width, f.top * height, f.right * width, f.bottom * height)
        }

        /** The photo taken from where the layout has it, to be moved or sized by hand. */
        private fun begin() {
            val r = photoRect() ?: return
            design.placeX = r.centerX() / width
            design.placeY = r.centerY() / height
            design.placeSize = r.width() / width
            design.placed = true
            lastX = downX; lastY = downY
            parent.requestDisallowInterceptTouchEvent(true)
        }

        /** The photo kept whole on the sheet (its size first, then its centre). */
        private fun keepOnSheet() {
            if (width == 0 || height == 0 || photoW <= 0) return
            fun tall(size: Float) = size * width * photoH / photoW / height
            design.placeSize = design.placeSize.coerceIn(0.15f, 1f)
            if (tall(design.placeSize) > 1f) design.placeSize = design.placeSize / tall(design.placeSize)
            val hw = design.placeSize / 2; val hh = tall(design.placeSize) / 2
            design.placeX = if (hw >= 0.5f) 0.5f else design.placeX.coerceIn(hw, 1f - hw)
            design.placeY = if (hh >= 0.5f) 0.5f else design.placeY.coerceIn(hh, 1f - hh)
        }

        /** Drawn where the finger is now; the sheet behind laid out again, quickly. */
        private fun live() {
            invalidate()
            if (!bare) { bare = true; refreshBare() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || drawable == null) return
            if (design.cartridge) FrameDesigner.drawCartridge(canvas, width, height, design, data?.filmKey ?: "") { invalidate() }
            if (dragging) canvas.drawRoundRect(cartBounds(), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x99ffffff.toInt()
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
            })
            if (mode != PlaceMode.MOVING && mode != PlaceMode.SIZING) return
            val r = photoRect() ?: return
            // the photo itself under the finger, at once: a print's edge, its shadow
            val shot = shownPhoto
            if (touching && shot != null) {
                val e = r.width() * 0.018f
                canvas.drawRect(r.left - e, r.top - e + 3 * density, r.right + e, r.bottom + e + 3 * density, shadowPaint)
                knob.color = 0xffffffff.toInt(); canvas.drawRect(r.left - e, r.top - e, r.right + e, r.bottom + e, knob)
                canvas.drawBitmap(shot, null, r, photoPaint)
            }
            canvas.drawRect(r, dashShade); canvas.drawRect(r, dash)
            if (mode == PlaceMode.SIZING) for ((x, y) in corners(r)) {
                knob.color = 0x66000000; canvas.drawCircle(x, y + 1 * density, 9 * density, knob)
                knob.color = 0xffffffff.toInt(); canvas.drawCircle(x, y, 8 * density, knob)
                knob.color = Style.ACCENT; canvas.drawCircle(x, y, 4.5f * density, knob)
            }
        }

        private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

        private fun cartBounds(): RectF {
            val h = design.cartSize * min(width, height)
            val cx = design.cartX * width
            val base = design.cartY * height
            return RectF(cx - h * 0.4f, base - h, cx + h * 0.6f, base).apply { inset(-h * 0.25f, -h * 0.2f) }
        }

        // ---- the crop inside a film border's picture ----
        private var cropping = false

        /** How far the photo can slide in the picture, in view pixels, across and down (one is 0). */
        private fun cropRoom(): Pair<Float, Float>? {
            val f = filmPicture ?: return null
            if (photoW <= 0 || photoH <= 0 || width == 0) return null
            val pw = f.width() * width; val ph = f.height() * height
            val a = photoW.toFloat() / photoH
            val rx = ph * a - pw; val ry = pw / a - ph
            return if (rx > 1f) rx to 0f else if (ry > 1f) 0f to ry else null
        }

        private fun cropTouch(e: MotionEvent): Boolean {
            val (rx, ry) = cropRoom() ?: return false.also { cropping = false }
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (rx > 0f) design.cropX = (design.cropX - (e.x - lastX) / rx).coerceIn(0f, 1f)
                    if (ry > 0f) design.cropY = (design.cropY - (e.y - lastY) / ry).coerceIn(0f, 1f)
                    lastX = e.x; lastY = e.y
                    refresh(quick = true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cropping = false
                    changed()
                }
            }
            return true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (cropping) return cropTouch(e)
            if (mode != PlaceMode.IDLE) return photoTouch(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN && !(design.cartridge && cartBounds().contains(e.x, e.y))) {
                // a film border cutting the photo: one finger on the picture slides the photo in it
                val f = filmPicture
                if (f != null && width > 0 && f.contains(e.x / width, e.y / height) && cropRoom() != null) {
                    cropping = true
                    lastX = e.x; lastY = e.y
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            if (e.actionMasked == MotionEvent.ACTION_DOWN && !(design.cartridge && cartBounds().contains(e.x, e.y))) {
                if (photoRect()?.contains(e.x, e.y) == true) {
                    mode = PlaceMode.HOLD
                    downX = e.x; downY = e.y; downAt = android.os.SystemClock.uptimeMillis()
                    postDelayed(pickUp, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
            }
            if (!design.cartridge) return super.onTouchEvent(e)
            pinch.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = cartBounds().contains(e.x, e.y)
                    lastX = e.x; lastY = e.y
                    moved = false
                    if (!dragging) return false
                    parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> { dragging = true; parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> if (dragging && !pinch.isInProgress && e.pointerCount == 1) {
                    design.cartX = (design.cartX + (e.x - lastX) / width).coerceIn(0f, 1f)
                    design.cartY = (design.cartY + (e.y - lastY) / height).coerceIn(0.05f, 1.05f)
                    lastX = e.x; lastY = e.y
                    moved = true
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // the finger left on the glass carries on moving it from where it is
                    val stay = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(stay); lastY = e.getY(stay)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (moved) keep()
                    invalidate()
                }
            }
            return true
        }

        /** The photo: held for a long press or a double tap, moved, sized, set down. */
        private fun photoTouch(e: MotionEvent): Boolean {
            if (mode == PlaceMode.MOVING || mode == PlaceMode.SIZING) pinch.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // with the corners showing: a corner to pull, the photo to drag or pinch, elsewhere to finish
                    if (mode == PlaceMode.SIZING) {
                        val r = photoRect()
                        val near = r?.let { corners(it).any { (x, y) -> kotlin.math.hypot((e.x - x).toDouble(), (e.y - y).toDouble()) < 30 * density } } == true
                        if (r == null || (!near && !r.contains(e.x, e.y))) { finish(); return true }
                        live()
                        pulling = near
                        startSize = design.placeSize
                        startDist = kotlin.math.hypot((e.x - r.centerX()).toDouble(), (e.y - r.centerY()).toDouble()).toFloat().coerceAtLeast(1f)
                        downX = e.x; downY = e.y; downAt = android.os.SystemClock.uptimeMillis()
                        lastX = e.x; lastY = e.y
                        touching = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == PlaceMode.HOLD) {
                        // moved before the long press: not one
                        if (kotlin.math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) { removeCallbacks(pickUp); mode = PlaceMode.IDLE }
                        return true
                    }
                    if (pinch.isInProgress || e.pointerCount > 1) return true
                    if (!touching) { touching = true }
                    if (mode == PlaceMode.SIZING && pulling) {
                        val cx = design.placeX * width; val cy = design.placeY * height
                        val dist = kotlin.math.hypot((e.x - cx).toDouble(), (e.y - cy).toDouble()).toFloat()
                        design.placeSize = startSize * dist / startDist
                    } else {
                        design.placeX += (e.x - lastX) / width
                        design.placeY += (e.y - lastY) / height
                    }
                    lastX = e.x; lastY = e.y
                    keepOnSheet()
                    live()
                }
                MotionEvent.ACTION_POINTER_DOWN -> { touching = true }
                MotionEvent.ACTION_POINTER_UP -> {
                    val stay = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(stay); lastY = e.getY(stay)
                    pulling = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(pickUp)
                    val now = android.os.SystemClock.uptimeMillis()
                    val tap = e.actionMasked == MotionEvent.ACTION_UP && now - downAt < android.view.ViewConfiguration.getTapTimeout() * 2 &&
                        kotlin.math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < slop
                    val double = tap && now - lastTapAt < android.view.ViewConfiguration.getDoubleTapTimeout() &&
                        kotlin.math.hypot((e.x - lastTapX).toDouble(), (e.y - lastTapY).toDouble()) < 48 * density
                    when (mode) {
                        PlaceMode.HOLD -> {
                            mode = PlaceMode.IDLE
                            if (double) {
                                // a double tap: the corners, to size it
                                lastTapAt = 0L
                                begin(); mode = PlaceMode.SIZING
                                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                                invalidate()
                            } else if (tap) { lastTapAt = now; lastTapX = e.x; lastTapY = e.y }
                        }
                        PlaceMode.MOVING -> finish()
                        PlaceMode.SIZING -> {
                            touching = false; pulling = false; bare = false
                            if (double) finish()
                            else {
                                if (tap) { lastTapAt = now; lastTapX = e.x; lastTapY = e.y }
                                keep()
                                changed()
                                invalidate()
                            }
                        }
                        else -> {}
                    }
                }
            }
            return true
        }

        /** Set down: the corners away, the sheet laid out properly round it. */
        private fun finish() {
            removeCallbacks(pickUp)
            mode = PlaceMode.IDLE; touching = false; pulling = false; lastTapAt = 0L; bare = false
            changed(rebuild = true)
            invalidate()
        }
    }

    /** Lays the preview out as large as fits, at its picture's own proportions. */
    private class PreviewBox(context: Context, private val image: ImageView) : FrameLayout(context) {
        init { addView(image) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val aw = (w - paddingLeft - paddingRight).coerceAtLeast(1)
            val ah = (h - paddingTop - paddingBottom).coerceAtLeast(1)
            val d = image.drawable
            val (iw, ih) = if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                val k = min(aw.toFloat() / d.intrinsicWidth, ah.toFloat() / d.intrinsicHeight)
                (d.intrinsicWidth * k).toInt() to (d.intrinsicHeight * k).toInt()
            } else aw to ah
            image.measure(MeasureSpec.makeMeasureSpec(iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ih, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val x = paddingLeft + (width - paddingLeft - paddingRight - image.measuredWidth) / 2
            val y = paddingTop + (height - paddingTop - paddingBottom - image.measuredHeight) / 2
            image.layout(x, y, x + image.measuredWidth, y + image.measuredHeight)
        }
    }

    /** The quiet grid the photo lies on. */
    private class GridBackdrop(private val bg: Int, private val line: Int, private val step: Float) : Drawable() {
        private val paint = Paint().apply { color = line; strokeWidth = 1f }
        override fun draw(canvas: Canvas) {
            canvas.drawColor(bg)
            val b = bounds
            var x = b.left.toFloat()
            while (x < b.right) { canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), paint); x += step }
            var y = b.top.toFloat()
            while (y < b.bottom) { canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, paint); y += step }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** A colour to pick; AUTO and BLUR drawn as what they mean. */
    private inner class Swatch(context: Context, val color: Int, selected: Boolean, private val photo: Bitmap?) : View(context) {
        var on = selected
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        init { Style.pressable(this) }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f - dp(5)
            when (color) {
                FrameDesign.AUTO -> {
                    paint.color = 0xff1c1c1e.toInt(); canvas.drawCircle(cx, cy, r, paint)
                    canvas.save(); canvas.clipRect(cx, 0f, width.toFloat(), height.toFloat())
                    paint.color = 0xfff5f5f7.toInt(); canvas.drawCircle(cx, cy, r, paint); canvas.restore()
                    paint.color = 0xff8e8e93.toInt(); paint.textSize = r * 0.9f; paint.textAlign = Paint.Align.CENTER
                    paint.typeface = Style.medium
                    canvas.drawText("A", cx, cy + r * 0.32f, paint)
                }
                FrameDesign.BLUR -> {
                    canvas.save()
                    canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
                    if (photo != null) canvas.drawBitmap(photo, null, RectF(cx - r * 1.6f, cy - r * 1.6f, cx + r * 1.6f, cy + r * 1.6f), paint)
                    paint.color = 0x55ffffff; canvas.drawCircle(cx, cy, r, paint)
                    canvas.restore()
                    paint.color = 0x99000000.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat()
                    canvas.drawCircle(cx, cy, r * 0.42f, paint); paint.style = Paint.Style.FILL
                }
                else -> {
                    paint.color = color; canvas.drawCircle(cx, cy, r, paint)
                }
            }
            // a hairline round every swatch, so black shows on a dark card and white on a light one
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = if (light) 0x26000000 else 0x4dffffff
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.FILL
            if (on) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = textColor
                canvas.drawCircle(cx, cy, r + dp(3), paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    /** A slim track and a round white knob that casts a soft shadow. */
    private inner class PillSlider(context: Context, start: Float, private val track: Int, private val fill: Int,
                                   private val onChange: (Float) -> Unit) : View(context) {
        private var value = start.coerceIn(0f, 1f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = dp(12).toFloat()
        override fun onDraw(canvas: Canvas) {
            val cy = height / 2f
            val left = knob
            val right = width - knob
            val x = left + (right - left) * value
            paint.color = track
            canvas.drawRoundRect(left, cy - dp(2), right, cy + dp(2), dp(2).toFloat(), dp(2).toFloat(), paint)
            paint.color = fill
            canvas.drawRoundRect(left, cy - dp(2), x, cy + dp(2), dp(2).toFloat(), dp(2).toFloat(), paint)
            paint.color = 0x40000000
            paint.maskFilter = BlurMaskFilter(dp(3).toFloat(), BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(x, cy + dp(1), knob, paint)
            paint.maskFilter = null
            paint.color = 0xffffffff.toInt()
            canvas.drawCircle(x, cy, knob, paint)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    value = ((e.x - knob) / (width - 2 * knob)).coerceIn(0f, 1f)
                    invalidate()
                    onChange(value)
                }
            }
            return true
        }
    }

    /** Where on the photo or border: a 3 x 3 (or, for corners, 2 x 2) grid of spots. */
    private inner class AnchorPad(context: Context, private val n: Int, start: Int, private val onPick: (Int) -> Unit) : View(context) {
        private var at = start
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            paint.color = fillColor
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(14).toFloat(), dp(14).toFloat(), paint)
            val cell = width / n.toFloat()
            for (i in 0 until n * n) {
                val cx = cell * (i % n + 0.5f)
                val cy = cell * (i / n + 0.5f)
                if (i == at) {
                    paint.color = textColor
                    canvas.drawRoundRect(cx - cell * 0.28f, cy - cell * 0.2f, cx + cell * 0.28f, cy + cell * 0.2f, dp(4).toFloat(), dp(4).toFloat(), paint)
                } else {
                    paint.color = text2Color
                    canvas.drawCircle(cx, cy, dp(2.5f), paint)
                }
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val col = (e.x / (width / n.toFloat())).toInt().coerceIn(0, n - 1)
                val row = (e.y / (height / n.toFloat())).toInt().coerceIn(0, n - 1)
                at = row * n + col
                invalidate()
                onPick(at)
            }
            return true
        }
        private fun dp(v: Float) = v * resources.displayMetrics.density
    }

    /** The tool bar's icons, drawn in one line weight. */
    private class ToolIcon(private val tab: Tab, color: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = b.width().toFloat()
            p.strokeWidth = s * 0.075f
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            when (tab) {
                Tab.LAYOUT -> {   // two cards, one fanned behind the other
                    canvas.save(); canvas.rotate(-12f, s * 0.4f, s * 0.6f)
                    canvas.drawRoundRect(s * 0.14f, s * 0.2f, s * 0.6f, s * 0.82f, s * 0.08f, s * 0.08f, p); canvas.restore()
                    p.style = Paint.Style.FILL; p.alpha = 255
                    canvas.drawRoundRect(s * 0.38f, s * 0.16f, s * 0.86f, s * 0.82f, s * 0.08f, s * 0.08f, p)
                    p.style = Paint.Style.STROKE
                }
                Tab.BORDER -> {   // a print with its margin
                    canvas.drawRoundRect(s * 0.18f, s * 0.1f, s * 0.82f, s * 0.9f, s * 0.08f, s * 0.08f, p)
                    p.style = Paint.Style.FILL
                    canvas.drawRect(s * 0.3f, s * 0.22f, s * 0.7f, s * 0.64f, p)
                    p.style = Paint.Style.STROKE
                }
                Tab.COLOR -> {    // three overlapping discs
                    canvas.drawCircle(s * 0.5f, s * 0.34f, s * 0.22f, p)
                    canvas.drawCircle(s * 0.34f, s * 0.62f, s * 0.22f, p)
                    canvas.drawCircle(s * 0.66f, s * 0.62f, s * 0.22f, p)
                }
                Tab.TEXT -> {     // Aa, underlined
                    p.style = Paint.Style.FILL
                    p.textSize = s * 0.62f
                    p.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                    canvas.drawText("Aa", s * 0.1f, s * 0.68f, p)
                    p.style = Paint.Style.STROKE
                    canvas.drawLine(s * 0.12f, s * 0.84f, s * 0.88f, s * 0.84f, p)
                }
                Tab.ARRANGE -> {  // a grid of spots, one larger
                    p.style = Paint.Style.FILL
                    for (i in 0 until 9) {
                        val cx = s * (0.22f + 0.28f * (i % 3))
                        val cy = s * (0.22f + 0.28f * (i / 3))
                        canvas.drawCircle(cx, cy, if (i == 5) s * 0.1f else s * 0.055f, p)
                    }
                    p.style = Paint.Style.STROKE
                }
                Tab.STAMP -> {    // an old date back: '26 in a lit window
                    canvas.drawRoundRect(s * 0.08f, s * 0.24f, s * 0.92f, s * 0.76f, s * 0.12f, s * 0.12f, p)
                    p.style = Paint.Style.FILL
                    p.textSize = s * 0.34f
                    p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    canvas.drawText("'26", s * 0.2f, s * 0.62f, p)
                    p.style = Paint.Style.STROKE
                }
            }
            canvas.restore()
        }
        override fun getIntrinsicWidth() = 72
        override fun getIntrinsicHeight() = 72
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    @Suppress("unused")
    private fun outline(v: View, r: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        v.clipToOutline = true
    }

    @Suppress("unused")
    private fun ViewGroup.noop() = Unit

    companion object {
        const val URI = "uri"
        const val NAME = "name"
        const val FRAME = "frame"
        const val FILM = "film"
        const val TAKEN = "taken"
        const val PRIVATE = "private"
        const val DESCRIPTION = "description"
        /** Opened after an import: no border, no layout to begin with (and on 还原); nothing kept. */
        const val BLANK = "blank"
        /** The import's other pictures, each opened in turn when this page closes. */
        const val QUEUE = "queue"
    }
}
