package org.photoall.film.gallery

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
    private var tiny: Bitmap? = null       // for the design thumbnails
    private var palette: List<Int> = emptyList()
    private var data: FrameDesigner.Data? = null
    private var ticket = 0
    private var tab: Tab? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val uri by lazy { intent.getParcelableExtra<Uri>(URI)!! }
    private val frame by lazy { intent.getIntExtra(FRAME, 0) }
    private val film by lazy { intent.getStringExtra(FILM) ?: "" }
    private val taken by lazy { intent.getLongExtra(TAKEN, System.currentTimeMillis()) }

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
        design = FrameDesign.load(this)
        window.statusBarColor = canvasColor
        window.navigationBarColor = canvasColor

        preview = ImageView(this).apply {
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
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(26).toFloat() }
            elevation = dp(10).toFloat()
            visibility = View.GONE
            addView(panelTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(panelBody, LinearLayout.LayoutParams(-1, -2))
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
            addView(toolbar, LinearLayout.LayoutParams(-1, dp(62)).apply { marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(14) })
        }
        setContentView(FrameLayout(this).apply {
            background = GridBackdrop(canvasColor, gridColor, dp(22).toFloat())
            addView(column, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
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

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- preview -----------------------------------------------------------------------------

    private val redraw = Runnable { refresh() }

    /** Something changed: keep it, and draw it again (soon, so a dragged slider stays smooth). */
    private fun changed(rebuild: Boolean = false) {
        FrameDesign.save(this, design)
        main.removeCallbacks(redraw)
        main.postDelayed(redraw, 30)
        if (rebuild) tab?.let { fillPanel(it, animate = false) }
    }

    private fun refresh() {
        val photo = small ?: return
        val d = data ?: return
        val snapshot = design.copy()
        val t = ++ticket
        worker.later {
            val shown = runCatching { FrameDesigner.render(photo, snapshot, d, maxLong = 1400) }.getOrNull() ?: return@later
            runOnUiThread {
                if (t != ticket) return@runOnUiThread
                val first = preview.drawable == null
                preview.setImageBitmap(shown)
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
        addView(HorizontalScrollView(this@FrameActivity).apply {
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
                addView(thumb, LinearLayout.LayoutParams(dp(78), dp(96)))
                addView(TextView(this@FrameActivity).apply {
                    text = preset.name; textSize = 11f; setTextColor(textColor); gravity = Gravity.CENTER; maxLines = 1
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                Style.pressable(this)
                setOnClickListener {
                    design = preset.build(palette, design.caption)
                    changed()
                }
            }, LinearLayout.LayoutParams(dp(84), -2).apply { marginEnd = dp(6) })
            val photo = tiny
            val d = data
            if (photo != null && d != null) worker.later {
                val bmp = runCatching { FrameDesigner.render(photo, preset.build(palette, design.caption), d, maxLong = 220) }.getOrNull()
                runOnUiThread { thumb.setImageBitmap(bmp) }
            }
        }
        addView(switchRow("拍照时自动套用", "每张另存一份带这个边框的，原图照常保存", store.flag(Settings.FRAME_AUTO)) { on ->
            store[Settings.FRAME_AUTO] = if (on) 1f else 0f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    // 边框: the kind of border, its width, the photo's corners and shadow
    private fun borderCard(): View = column {
        fun group(b: Border) = when { b.film -> 0; b == Border.NONE -> 1; b == Border.CUSTOM -> 2; else -> 3 }
        addView(choices(Border.entries.map { it.label }, setOf(design.border.ordinal)) { picked ->
            val before = design.border
            design.border = Border.entries[picked.first()]
            changed(rebuild = group(before) != group(design.border))
        })
        if (design.border.film) {
            val stock = data?.filmKey?.takeIf { it.isNotBlank() && it != "直出" }
                ?.let { org.photoall.film.ui.CanisterView.displayName(it) }
            addView(hint(if (stock != null) "片边按这张照片的胶卷印：$stock" else "这张照片没用胶卷，片边印 PHOTO ALL"),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
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
        } else if (design.border == Border.CUSTOM) {
            // the card made to measure: its proportions, the photo's size and place, its colour
            addView(section("画幅比例"))
            addView(choices(FrameDesign.Ratio.entries.map { it.label }, setOf(design.ratio.ordinal)) {
                design.ratio = FrameDesign.Ratio.entries[it.first()]; changed()
            })
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
            addView(section("颜色"))
            addView(swatches(listOf(FrameDesign.WHITE, FrameDesign.PAPER, FrameDesign.BLACK, FrameDesign.BLUR) + palette,
                design.background) { design.background = it; changed() })
        } else if (design.border != Border.NONE) {
            addView(slider("宽度", design.thickness) { design.thickness = it; changed() })
            addView(slider("圆角", design.corner) { design.corner = it; changed() })
            addView(choices(listOf("照片投影"), if (design.shadow) setOf(0) else emptySet(), multi = true) { picked ->
                design.shadow = 0 in picked; changed()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
        addView(section("品牌"))
        val maker = data?.maker?.let { FrameDesigner.BRANDS[it]?.text } ?: data?.brand ?: ""
        addView(choices(listOf("$maker 品牌字标", "跟随字体"), setOf(if (design.brandMark) 0 else 1), scroll = false) {
            design.brandMark = it.first() == 0; changed()
        })
        addView(section("字体"))
        addView(choices(Font.entries.map { it.label }, setOf(design.font.ordinal)) { design.font = Font.entries[it.first()]; changed() })
        addView(section("标志"))
        addView(choices(Logo.entries.map { it.label }, setOf(design.logo.ordinal)) { design.logo = Logo.entries[it.first()]; changed() })
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
                addView(AnchorPad(this@FrameActivity, 3, design.anchor) { design.anchor = it; changed() },
                    LinearLayout.LayoutParams(dp(92), dp(92)))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        })
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
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
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
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
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
        design = FrameDesign.presets[0].build(palette, design.caption)
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
        Toast.makeText(this, "正在存储…", Toast.LENGTH_SHORT).show()
        worker.later {
            val result = runCatching {
                val full = decode(8192) ?: error("读不出原图")
                val framed = FrameDesigner.render(full, snapshot, d, maxLong = 8192)
                full.recycle()
                Photos.saveJpeg(this, framed, name.removeSuffix(".jpg") + "_frame.jpg", store[Settings.JPEG_QUALITY].roundToInt(),
                    listOf(description, "边框 ${snapshot.border.label}").filter { it.isNotEmpty() }.joinToString(" · "),
                    ShotInfo.read(this, uri), private = private)
                framed.recycle()
            }
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "已存储带边框的照片", Toast.LENGTH_SHORT).show(); finish() }
                    .onFailure { Toast.makeText(this, "存储失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
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
    }
}
