package org.photoall.film.gallery

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.film.ui.CanisterView
import org.photoall.film.ui.Icon
import org.photoall.film.ui.Segmented
import org.photoall.film.ui.Sheet
import org.photoall.film.ui.Style
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 观片台: the album's photos as rolls of 36, oldest roll first, laid on a light table
 * ([LightTable]). The same filter as the album it was opened from. Keepers are the album's
 * favourites; rejects are this app's own marks, and 清理排除 takes them off the table (only
 * off the table: the photos stay in the album, and the settings can put them back).
 * Where each frame lies on the table, and the table's view, are kept per photo and per roll.
 */
class LightboxActivity : Activity() {
    private val loader = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(
        minOf(256L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 2).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private class Probe(val aspect: Float, val orientation: Int, val width: Int, val height: Int)
    private val probes = java.util.concurrent.ConcurrentHashMap<String, Probe>()

    private var favoritesOnly = false
    private var film: String? = null
    private var privateOnly = false
    private var photos: List<Photos.Photo> = emptyList() // newest first, as the album
    private var rolls: List<List<Photos.Photo>> = emptyList() // oldest roll first, frames in order
    private var rollIndex = -1
    private var generation = 0
    private var pendingTrash: List<Photos.Photo> = emptyList()

    private lateinit var table: LightTable
    private lateinit var hiRes: LightTable.HiRes
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var modes: Segmented
    private lateinit var clean: Button
    private lateinit var shelf: LinearLayout
    private lateinit var shelfScroll: HorizontalScrollView
    private lateinit var lampButton: ImageView
    private lateinit var heart: Tool
    private lateinit var reject: Tool
    private lateinit var loupe: Tool
    private lateinit var play: Tool
    private lateinit var settingsTool: Tool
    private lateinit var empty: TextView
    private lateinit var haptics: org.photoall.film.ui.Haptics

    private val prefs by lazy { getSharedPreferences("lightbox", Context.MODE_PRIVATE) }
    // Places laid out by the old spread (sized apart from the drawn pieces, so they could
    // overlap) are dropped once; every roll is spread again the new way.
    private val layoutPrefs by lazy {
        getSharedPreferences("lightbox_layout", Context.MODE_PRIVATE).also {
            if (it.getInt("layout_version", 1) < LAYOUT_VERSION) it.edit().clear().putInt("layout_version", LAYOUT_VERSION).apply()
        }
    }
    private val marks by lazy { getSharedPreferences("lightbox_marks", Context.MODE_PRIVATE) }
    private var rejectedNames: MutableSet<String> = mutableSetOf()
    /** Taken off the table by 清理排除: not shown here, never deleted. */
    private var hiddenNames: MutableSet<String> = mutableSetOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        // the light table's own style (its settings), not the app's theme
        Style.applyTheme(this, LightboxLook.theme(this))
        super.onCreate(savedInstanceState)
        favoritesOnly = intent.getBooleanExtra(ViewerActivity.FAVORITES, false)
        film = intent.getStringExtra(ViewerActivity.FILM)
        privateOnly = intent.getBooleanExtra(ViewerActivity.PRIVATE, false)
        rollIndex = savedInstanceState?.getInt("roll", -1) ?: -1
        rejectedNames = marks.getStringSet("rejected", emptySet())!!.toMutableSet()
        hiddenNames = marks.getStringSet("hidden", emptySet())!!.toMutableSet()
        val settings = org.photoall.film.settings.SettingsStore(this)
        haptics = org.photoall.film.ui.Haptics(window.decorView) { settings[org.photoall.film.settings.Settings.HAPTICS].toInt() }

        hiRes = LightTable.HiRes(this)
        table = LightTable(this).apply {
            hiRes = this@LightboxActivity.hiRes
            magnification = prefs.getFloat("magnification", 3f)
            lensSize = prefs.getFloat("lens_size", 0.35f)
            offColor = prefs.getInt("off_color", LightboxLook.offDefault(OFF_COLORS[0].second))
            pencilColor = LightboxLook.pencil(pencilColor)
            brightness = prefs.getInt("brightness", 100)
            borders = prefs.getBoolean("borders", true)
            secondsPerFrame = prefs.getFloat("seconds", 6f)
            stripHorizontal = prefs.getBoolean("strip_horizontal", false)
            onOpen = { f -> select(f, scroll = false); openViewer() }
            setLamp(false, animate = false) // every visit starts with the lamp off
            filmName = { f -> filmLabel(f.photo) }
            rejected = { f -> f.photo.name in rejectedNames }
            fullSize = { f -> probes[key(f.photo)]?.let { it.width to it.height } }
            onCurrent = { updateLabels() }
            onDoubleTap = { f -> select(f, scroll = false); toggleFavorite() }
            onDoubleTapBlank = { goImmersive(!immersive) }
            onArranged = { saveLayout(); updateToolsForMode(mode) }
            onPlaying = { on -> play.set(if (on) Icon.Kind.PAUSE else Icon.Kind.PLAY, if (on) "暂停" else "播放", on) }
            onLoupe = { on -> loupe.set(Icon.Kind.LOUPE, "放大镜", on) }
            onTick = { haptics.play(org.photoall.film.ui.Haptics.Event.TICK) }
        }

        title = TextView(this).apply {
            textSize = 16f; typeface = Style.medium; setTextColor(Style.TEXT); gravity = Gravity.CENTER
            Style.numeric(this)
            typeface = LightboxLook.titleFace(typeface)
            if (LightboxLook.skinned) textSize = 18f
        }
        subtitle = TextView(this).apply {
            textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER
            letterSpacing = 0.06f; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        lampButton = iconButton(Icon.Kind.LAMP, "背光") { toggleLamp() }
        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            val classic = LightboxLook.capsule
            val key = if (classic) dp(40) else dp(48)
            if (classic) setPadding(dp(12), 0, dp(12), 0)
            addView(iconButton(Icon.Kind.BACK, "返回") { finish() }, LinearLayout.LayoutParams(key, key))
            addView(LinearLayout(this@LightboxActivity).apply {
                topMiddle = this
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // 经典: no words up here; the modes sit in the middle (see fillBars)
                if (!classic) { addView(title); addView(subtitle) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(lampButton, LinearLayout.LayoutParams(key, key))
        }
        modes = Segmented(this, listOf("台面", "联排")).apply {
            onPick = { i -> setMode(if (i == 0) LightTable.Mode.TABLE else LightTable.Mode.STRIP) }
            if (LightboxLook.capsule) {
                // the same pale capsule as the tools below
                pill = true
                background = LightboxLook.glass(resources.displayMetrics.density, 0x1affffff)
                trackColor = Color.TRANSPARENT; indicatorColor = LightboxLook.indicator
                onText = LightboxLook.chosenInk; offText = LightboxLook.inkSoft
            }
        }
        pickButton = TextView(this).apply {
            text = "选片"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Style.ACCENT); typeface = Style.medium
            setPadding(dp(12), 0, dp(12), 0)
            Style.pressable(this)
            setOnClickListener { showPicker() }
            visibility = View.GONE
        }
        clean = Button(this).apply {
            isAllCaps = false; isSingleLine = true
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            Style.textButton(this, 13f)
            setOnClickListener { cleanRejects() }
            visibility = View.INVISIBLE
        }
        modeBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (LightboxLook.capsule) {
            // 经典: 选片 and 清理排除 as small pills beside the rolls
            for (b in listOf<TextView>(pickButton, clean)) {
                b.background = LightboxLook.glass(resources.displayMetrics.density, 0x1affffff, shadow = false)
                b.setTextColor(LightboxLook.accent); b.typeface = LightboxLook.labelFace(Style.medium); b.textSize = 13f
                b.setPadding(dp(14), 0, dp(14), 0); b.gravity = Gravity.CENTER
            }
        }
        empty = TextView(this).apply {
            textSize = 14f; setTextColor(if (LightboxLook.skinned) Style.TEXT_2 else 0xff5d5a52.toInt()); gravity = Gravity.CENTER
            visibility = View.GONE
        }
        // The lightbox itself: a dark housing round the lit diffuser, in every theme.
        immersiveHint = TextView(this).apply {
            text = "双击空白处退出"
            textSize = 12f; setTextColor(0xffffffff.toInt()); gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(16).toFloat() }
            alpha = 0f
        }
        housing = FrameLayout(this).apply {
            background = LightboxLook.housing(resources.displayMetrics.density)
                ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xff3a3b3c.toInt(), 0xff232425.toInt())).apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), 0xff4b4c4d.toInt())
                }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            addView(FrameLayout(this@LightboxActivity).apply {
                tableFrame = this
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, tableCorner)
                }
                addView(table, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-1, -1))
                addView(immersiveHint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
                rotateHint = org.photoall.film.ui.RotateHint(this@LightboxActivity).apply {
                    visibility = View.GONE
                    setOnClickListener { haptics.play(org.photoall.film.ui.Haptics.Event.SEGMENT); turnTo(offered) }
                }
                addView(rotateHint, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.BOTTOM or Gravity.START))
            }, FrameLayout.LayoutParams(-1, -1))
            elevation = dp(3).toFloat()
        }
        if (LightboxLook.capsule) {
            // 经典: a plain dark card round the table, its corners concentric with the table's
            housing.background = LightboxLook.card(resources.displayMetrics.density)
            housing.elevation = 0f
        }
        housingLook = housing.background
        shelf = LinearLayout(this).apply { setPadding(dp(14), dp(10), dp(14), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        shelfScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(28))
            addView(shelf)
        }
        heart = Tool(Icon.Kind.HEART, "收藏") { toggleFavorite() }
        reject = Tool(Icon.Kind.CROSS, "排除") { toggleReject() }
        loupe = Tool(Icon.Kind.LOUPE, "放大镜") { toggleLoupe() }
        play = Tool(Icon.Kind.PLAY, "播放") {
            when {
                table.mode == LightTable.Mode.STRIP -> togglePlay()
                table.stowed -> unstow()
                else -> stow()  // laid out already: 收回 into the box
            }
        }
        settingsTool = Tool(Icon.Kind.TUNE, "设置") { showSettings() }
        tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            if (LightboxLook.capsule) {
                // 经典: one glass capsule, the keys joined in it, words only; as wide as the table
                setPadding(dp(18), dp(10), dp(18), dp(12))
                addView(LinearLayout(this@LightboxActivity).apply {
                    background = LightboxLook.glass(resources.displayMetrics.density, 0x1fffffff)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    for (t in listOf(heart, reject, loupe, play, settingsTool)) addView(t.view, LinearLayout.LayoutParams(0, -1, 1f))
                }, LinearLayout.LayoutParams(-1, dp(50)))
            } else {
                setPadding(dp(8), dp(2), dp(8), dp(6))
                for (t in listOf(heart, reject, loupe, play, settingsTool)) addView(t.view, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    if (LightboxLook.skinned) { marginStart = dp(3); marginEnd = dp(3) }
                })
            }
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            setOnApplyWindowInsetsListener { view, insets ->
                // immersive: edge to edge, into the camera's cutout too
                val bars = if (immersive) android.graphics.Insets.NONE
                    else insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        immersive = savedInstanceState?.getBoolean("immersive", false) ?: false
        chosen = savedInstanceState?.getInt("orientation", chosen) ?: chosen
        requestedOrientation = chosen
        watchOrientation()
        arrange()
        if (LightboxLook.capsule) {
            backdrop = LightboxLook.Backdrop(this, table)
            table.onFrame = { backdrop?.poke() }
            root.setBackgroundColor(Color.TRANSPARENT)
            setContentView(FrameLayout(this).apply {
                addView(backdrop, FrameLayout.LayoutParams(-1, -1))
                addView(root, FrameLayout.LayoutParams(-1, -1))
            })
        } else setContentView(root)
        val saved = prefs.getString("mode", "table")
        // the shelf's 自选 is a place, not a mode (an old "picked" mode meant 自选 as a strip)
        pickedMode = prefs.getBoolean("on_picked", false) || saved == "picked"
        val mode = if (saved == "strip" || saved == "picked") LightTable.Mode.STRIP else LightTable.Mode.TABLE
        modes.select(if (mode == LightTable.Mode.TABLE) 0 else 1, animate = false)
        pickButton.visibility = if (pickedMode) View.VISIBLE else View.GONE
        pendingMode = mode
        updateLamp()
        updateToolsForMode(mode)
    }

    private var pendingMode = LightTable.Mode.TABLE

    /**
     * 自选: only the photos the user picked (from every roll, in the order they were taken), as a
     * strip. Kept by name in prefs "picked".
     */
    private var pickedMode = false
    private val pickedNames: MutableSet<String> by lazy { prefs.getStringSet("picked", emptySet())!!.toMutableSet() }
    private lateinit var pickButton: TextView

    /** The picked photos, oldest first, as a roll is. */
    private fun pickedList() = photos.reversed().filter { it.name in pickedNames && it.name !in hiddenNames }

    /** The frames on the table: the picked ones in 自选, else the roll. */
    private fun shownRoll(): List<Photos.Photo>? = if (pickedMode) pickedList() else rolls.getOrNull(rollIndex)

    // ===== layout: portrait, landscape, immersive ==============================================

    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var modeBar: LinearLayout
    private lateinit var housing: FrameLayout
    private lateinit var tools: LinearLayout
    private lateinit var immersiveHint: TextView
    private var immersive = false
    private lateinit var tableFrame: FrameLayout
    private var housingLook: android.graphics.drawable.Drawable? = null
    private var tableCorner = 0f

    /**
     * The light box's dress: its housing (the dark frame, the rounded glass) normally; none at
     * all when immersive, the table alone from edge to edge, the camera's cutout included.
     */
    private fun dress() {
        housing.background = if (immersive) null else housingLook
        val classic = LightboxLook.capsule
        // 经典: the card 22 dp round, 6 dp out from a table 16 dp round (one centre for both)
        val pad = if (immersive) 0 else if (classic) dp(LightboxLook.cardPadDp) else dp(7)
        housing.setPadding(pad, pad, pad, pad)
        housing.elevation = if (immersive || classic) 0f else dp(3).toFloat()
        tableCorner = if (immersive) 0f else if (classic) LightboxLook.tableCornerDp * resources.displayMetrics.density else dp(10).toFloat()
        tableFrame.invalidateOutline()
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (immersive) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        root.requestApplyInsets()
    }

    /**
     * The pieces put in place for how the phone is held. Portrait: the bars, the light box,
     * the rolls and the tools stacked. Landscape: one top line (back, title, modes, lamp), the
     * light box filling the height, the rolls and the tools side by side under it. Immersive:
     * the light box alone, edge to edge.
     */
    private fun arrange() {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // landscape and immersive are full screen: no navigation bar (a swipe from the edge shows it a moment)
        root.post { bars(!(immersive || land)) }
        for (v in listOf(topBar, modeBar, housing, shelfScroll, tools)) (v.parent as? android.view.ViewGroup)?.removeView(v)
        root.removeAllViews()
        val classic = LightboxLook.capsule
        fillBars(classic)
        dress()
        if (immersive) {
            root.addView(housing, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        if (classic && !land) {
            // 经典: the modes and the rolls on top, the table taking the rest, the tools under it;
            // every edge 12 dp in from the screen's
            root.addView(topBar, LinearLayout.LayoutParams(-1, dp(56)))
            root.addView(housing, LinearLayout.LayoutParams(-1, 0, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            root.addView(modeBar, LinearLayout.LayoutParams(-1, -2))
            root.addView(tools, LinearLayout.LayoutParams(-1, -2))
        } else if (classic) {
            topLine = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(topBar, LinearLayout.LayoutParams(dp(300), dp(48)))
                addView(modeBar, LinearLayout.LayoutParams(0, -2, 1f))
            }
            bottomLine = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(tools, LinearLayout.LayoutParams(dp(400), -2))
            }
            root.addView(topLine, LinearLayout.LayoutParams(-1, -2))
            root.addView(housing, LinearLayout.LayoutParams(-1, 0, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            root.addView(bottomLine, LinearLayout.LayoutParams(-1, -2))
            barsOut = true
            barsLater(2000)
        } else if (!land) {
            root.addView(topBar, LinearLayout.LayoutParams(-1, dp(56)))
            root.addView(modeBar, LinearLayout.LayoutParams(-1, -2))
            root.addView(housing, LinearLayout.LayoutParams(-1, 0, 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
            root.addView(shelfScroll, LinearLayout.LayoutParams(-1, -2))
            root.addView(tools, LinearLayout.LayoutParams(-1, -2))
        } else {
            topLine = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(topBar, LinearLayout.LayoutParams(0, dp(48), 1f))
                // a fixed width: the bar's own spacer would take the whole line from the title
                addView(modeBar, LinearLayout.LayoutParams(dp(250), -2).apply { topMargin = dp(6) })
            }
            bottomLine = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(shelfScroll, LinearLayout.LayoutParams(0, -2, 1f))
                addView(tools, LinearLayout.LayoutParams(dp(300), -2))
            }
            root.addView(topLine, LinearLayout.LayoutParams(-1, -2))
            root.addView(housing, LinearLayout.LayoutParams(-1, 0, 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
            root.addView(bottomLine, LinearLayout.LayoutParams(-1, -2))
            // out for two seconds, then tucked away; a tap brings them back
            barsOut = true
            barsLater(2000)
        }
    }

    // ===== landscape: the bars tuck away ========================================================

    private var topLine: View? = null
    private lateinit var topMiddle: LinearLayout
    private var backdrop: LightboxLook.Backdrop? = null

    /**
     * What goes in the mode bar. 经典: the modes up in the top bar's middle, the rolls (自选
     * first) along this bar with 选片 and 清理排除 at its end. Others: the modes, 选片, 清理排除.
     */
    private fun fillBars(classic: Boolean) {
        for (v in listOf(modes, pickButton, clean, shelfScroll)) (v.parent as? android.view.ViewGroup)?.removeView(v)
        modeBar.removeAllViews()
        if (classic) {
            topMiddle.addView(modes, LinearLayout.LayoutParams(dp(168), dp(34)))
            // the rolls and the tools exactly as wide as the table (12 dp out, 6 dp of card)
            modeBar.setPadding(dp(18), dp(10), dp(18), 0)
            shelf.setPadding(0, 0, dp(6), 0)
            modeBar.addView(shelfScroll, LinearLayout.LayoutParams(0, -2, 1f))
            modeBar.addView(pickButton, LinearLayout.LayoutParams(-2, dp(32)).apply { marginStart = dp(6) })
            modeBar.addView(clean, LinearLayout.LayoutParams(-2, dp(32)).apply { marginStart = dp(6) })
        } else {
            modeBar.setPadding(dp(14), 0, dp(6), dp(8))
            modeBar.addView(modes, LinearLayout.LayoutParams(dp(150), dp(34)))
            modeBar.addView(pickButton, LinearLayout.LayoutParams(-2, dp(34)))
            modeBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            modeBar.addView(clean, LinearLayout.LayoutParams(-2, dp(36)))
        }
    }
    private var bottomLine: View? = null
    private var barsOut = true
    private val tuckBars = Runnable { showBars(false) }
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L

    private fun landscape() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun barsLater(ms: Long) { root.removeCallbacks(tuckBars); root.postDelayed(tuckBars, ms) }

    /** Landscape's two lines shown or tucked away (the light box takes their room). */
    private fun showBars(show: Boolean) {
        if (immersive || !landscape()) return
        if (show) barsLater(3000)
        if (barsOut == show) return
        barsOut = show
        android.transition.TransitionManager.beginDelayedTransition(root, android.transition.AutoTransition().setDuration(200))
        val v = if (show) View.VISIBLE else View.GONE
        topLine?.visibility = v
        bottomLine?.visibility = v
    }

    /** In landscape: a tap brings the bars out; any touch while they are out keeps them three seconds more. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (landscape() && !immersive) when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchDownX = e.rawX; touchDownY = e.rawY; touchDownAt = android.os.SystemClock.uptimeMillis()
                if (barsOut) barsLater(3000)
            }
            android.view.MotionEvent.ACTION_UP -> {
                val tap = android.os.SystemClock.uptimeMillis() - touchDownAt < 400 &&
                    kotlin.math.hypot((e.rawX - touchDownX).toDouble(), (e.rawY - touchDownY).toDouble()) < 12 * resources.displayMetrics.density
                if (barsOut) barsLater(3000) else if (tap) showBars(true)
            }
        }
        return super.dispatchTouchEvent(e)
    }

    // ===== which way it is held: the fan in the corner ==========================================

    private var chosen = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private var offered = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    private var held = -1
    private var orientationListener: android.view.OrientationEventListener? = null
    private lateinit var rotateHint: org.photoall.film.ui.RotateHint
    private val hideHint = Runnable { rotateHint.dismiss() }

    private fun shownOrientation() = when (window.decorView.display?.rotation) {
        android.view.Surface.ROTATION_90 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        android.view.Surface.ROTATION_270 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /**
     * The screen stays put when the phone turns (as on the camera); a glowing fan comes up in the
     * light box's corner for three seconds, upright for the way the phone is held, and turns the
     * screen when tapped. Turning back offers the way back.
     */
    private fun watchOrientation() {
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val snapped = when (degrees) {
                    in 300..360, in 0..60 -> 0
                    in 70..110 -> 90
                    in 250..290 -> 270
                    else -> return
                }
                if (snapped == held) return
                held = snapped
                val wanted = when (snapped) {
                    270 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    90 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                if (wanted == shownOrientation()) { root.removeCallbacks(hideHint); rotateHint.dismiss(); return }
                offered = wanted
                rotateHint.label = if (wanted == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "竖屏" else "横屏"
                rotateHint.toLandscape = wanted != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val display = (window.decorView.display?.rotation ?: android.view.Surface.ROTATION_0) * 90
                rotateHint.contentRotation = ((360 - snapped - display) % 360 + 360) % 360f
                rotateHint.appear(3000)
                root.removeCallbacks(hideHint)
                root.postDelayed(hideHint, 3000)
            }
        }
    }

    private fun turnTo(orientation: Int) {
        root.removeCallbacks(hideHint)
        rotateHint.dismiss()
        chosen = orientation
        requestedOrientation = orientation
    }

    /** The system bars shown or hidden (a swipe from the edge brings them back for a moment). */
    private fun bars(show: Boolean) {
        window.insetsController?.let {
            // back as the app keeps them: the navigation bar, no status bar
            if (show) { it.show(WindowInsets.Type.navigationBars()); it.hide(WindowInsets.Type.statusBars()) }
            else {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** Immersive: only the light box, edge to edge, no bars. A double tap on the empty table goes in and out. */
    private fun goImmersive(on: Boolean) {
        coach?.event("immersive")
        if (on == immersive) return
        immersive = on
        haptics.play(org.photoall.film.ui.Haptics.Event.SEGMENT)
        android.transition.TransitionManager.beginDelayedTransition(root, android.transition.AutoTransition().setDuration(220))
        arrange()
        immersiveHint.animate().cancel()
        if (on) {
            immersiveHint.alpha = 0f
            immersiveHint.animate().alpha(1f).setDuration(200).withEndAction {
                immersiveHint.animate().alpha(0f).setStartDelay(1600).setDuration(400).start()
            }.start()
        } else immersiveHint.alpha = 0f
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (immersive) { goImmersive(false); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onResume() {
        Style.use(LightboxLook.theme(this)) // back from a screen in the app's theme
        super.onResume()
        // back from the photo (a portrait screen): the way this screen was chosen to be held
        if (requestedOrientation != chosen) requestedOrientation = chosen
        if (orientationListener?.canDetectOrientation() == true) orientationListener?.enable()
        reload()
    }

    override fun onPause() {
        orientationListener?.disable()
        rotateHint.dismiss()
        table.stop()
        saveLayout()
        prefs.edit().putFloat("magnification", table.magnification).apply()
        Style.use(Style.storedTheme(this)) // the next screen draws in the app's own theme
        super.onPause()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        hiRes.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("roll", rollIndex)
        outState.putBoolean("immersive", immersive)
        outState.putInt("orientation", chosen)
    }

    @Deprecated("System confirmation for favourites / recycle bin")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TRASH_REQUEST && resultCode == RESULT_OK) forgetRejects(pendingTrash)
        pendingTrash = emptyList()
        reload()
    }

    // ===== rolls =============================================================================

    private fun key(p: Photos.Photo) = "${p.uri}#${p.bytes}"

    private fun reload() {
        loader.later {
            val list = Photos.filter(Photos.list(this, favoritesOnly), film, privateOnly)
            runOnUiThread { if (!isDestroyed) applyList(list) }
        }
    }

    private fun applyList(list: List<Photos.Photo>) {
        photos = list
        val onTable = list.filter { it.name !in hiddenNames }
        rolls = onTable.reversed().chunked(ROLL_LENGTH)
        empty.visibility = if (onTable.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (list.isEmpty()) "这个筛选下没有照片"
            else if (onTable.isEmpty()) "照片都清理出观片台了：设置 ›「已清理」可以放回" else ""
        if (rolls.isEmpty()) { rollIndex = -1; table.show(emptyList(), pendingMode, null, null, false); updateShelf(); updateLabels(); return }
        if (rollIndex !in rolls.indices) rollIndex = rolls.lastIndex // the newest roll first
        val shown = table.frames
        val roll = shownRoll() ?: return
        if (shown.size == roll.size && shown.map { it.photo.name } == roll.map { it.name }) {
            // the same frames: only their records changed (a favourite, say)
            for ((f, p) in shown.zip(roll)) f.photo = p
            table.refresh()
            updateShelf(); updateLabels()
        } else showRoll(rollIndex, intro = true)
    }

    // ===== the tour ===========================================================================

    private var coach: org.photoall.film.ui.Coach? = null
    private var coachAsked = false

    /** The first visit with a roll on the table: the short tour, once (and again from the settings). */
    private fun maybeTour() {
        if (coachAsked || coach != null) return
        if (getSharedPreferences("coach", MODE_PRIVATE).getBoolean("lightbox", false)) return
        coachAsked = true
        root.postDelayed({ startTour() }, 900)
    }

    private fun startTour() {
        if (coach != null || isFinishing || isDestroyed || !hasWindowFocus()) { coachAsked = false; return }
        if (immersive) goImmersive(false)
        if (landscape()) showBars(true)
        fun step(title: String, body: String, target: (() -> View?)? = null, waitFor: String? = null,
                 gesture: org.photoall.film.ui.Coach.Gesture = org.photoall.film.ui.Coach.Gesture.NONE, button: String? = null, pad: Float = 6f,
                 place: org.photoall.film.ui.Coach.Place = org.photoall.film.ui.Coach.Place.AUTO,
                 onEnter: (() -> Unit)? = null, onLeave: (() -> Unit)? = null) =
            org.photoall.film.ui.Coach.Step(title, body, target, waitFor, gesture, button, null, waitFor != null, pad,
                onEnter, onLeave, null, if (waitFor != null) 700L else 320L, place)
        val steps = listOf(
            step("一卷底片，摆上灯箱",
                "每一卷照片都排在这张台面上，像冲好的底片铺在灯箱上。",
                button = "开始"),
            step("开灯",
                "点右上角的灯，灯箱亮起来，底片透出颜色；再点一次关灯。",
                target = { lampButton.takeIf { it.isShown } }, waitFor = "lamp", gesture = org.photoall.film.ui.Coach.Gesture.TAP, pad = 2f),
            step("手势",
                "单击照片　选中，再点一次看大图\n" +
                "双击照片　收藏\n" +
                "双击空白　沉浸，只剩灯箱；再双击退出\n" +
                "长按　　　拿起放大镜\n" +
                "拖动　　　摆片；空白处拖动移动台面\n" +
                "双指　　　缩放台面；按住一张捏合，只改这张\n" +
                "横过来　　角落浮起扇形，点它换横屏",
                button = "记住了"),
            step("放大镜",
                "打开放大镜，拖到哪里，就看哪里的细节；双指捏合改倍数。",
                target = { loupe.view.takeIf { it.isShown } }, waitFor = "loupe", gesture = org.photoall.film.ui.Coach.Gesture.TAP, pad = 2f,
                onLeave = { if (table.loupeLocked) toggleLoupe() }),
            step("联排",
                "换到联排，底片排成一条，上下滑动翻看，也能自动播放。",
                target = { modes.takeIf { it.isShown } }, waitFor = "mode", gesture = org.photoall.film.ui.Coach.Gesture.TAP, pad = 4f),
        )
        val c = org.photoall.film.ui.Coach(this, steps, onAdvance = { haptics.play(org.photoall.film.ui.Haptics.Event.SEGMENT) }) { _ ->
            coach = null
            getSharedPreferences("coach", MODE_PRIVATE).edit().putBoolean("lightbox", true).apply()
        }
        coach = c
        addContentView(c, android.view.ViewGroup.LayoutParams(-1, -1))
        c.start()
    }

    private fun showRoll(index: Int, intro: Boolean) {
        if (index !in rolls.indices) return
        if (index != rollIndex && !pickedMode) saveLayout()
        rollIndex = index
        val gen = ++generation
        val roll = if (pickedMode) pickedList() else rolls[index]
        empty.visibility = if (pickedMode && roll.isEmpty()) View.VISIBLE else if (photos.isEmpty()) View.VISIBLE else View.GONE
        if (pickedMode && roll.isEmpty()) empty.text = "还没有选片：点「选片」或再点一次「自选」，挑想展示的照片"
        if (roll.isEmpty()) { table.show(emptyList(), pendingMode, null, null, false); updateShelf(); updateLabels(); return }
        updateShelf()
        loader.later {
            // each frame's shape, from the file's header and its orientation
            for (p in roll) if (probes[key(p)] == null) probe(p)?.let { probes[key(p)] = it }
            runOnUiThread {
                if (gen != generation || isDestroyed) return@runOnUiThread
                val frames = roll.mapIndexed { i, p ->
                    val pr = probes[key(p)]
                    LightTable.Frame(p, i + 1, pr?.aspect ?: 0.75f, pr?.orientation ?: 1).also { it.bitmap = cache.get(key(p)) }
                }
                place(frames)
                val view = layoutPrefs.getString(viewKey(roll.first().name), null)
                    ?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.toFloatArray()
                val current = frames.lastOrNull()
                table.show(frames, pendingMode, current, view, intro)
                table.setStowed(layoutPrefs.getBoolean(stowKey(), false))
                showStowHint()
                updateToolsForMode(pendingMode)
                updateLabels()
                if (frames.isNotEmpty()) maybeTour()
                loadBitmaps(frames, gen)
            }
        }
    }

    private fun probe(p: Photos.Photo): Probe? = runCatching {
        val orientation = contentResolver.openInputStream(p.uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        } ?: 1
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(p.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var w = bounds.outWidth; var h = bounds.outHeight
        if (w <= 0 || h <= 0) { w = p.width; h = p.height }
        if (w <= 0 || h <= 0) return@runCatching null
        if (orientation == 6 || orientation == 8) { val t = w; w = h; h = t }
        Probe(w.toFloat() / h, orientation, w, h)
    }.getOrNull()

    // Nearest the current frame first; the long side about the screen's width and a quarter.
    private fun loadBitmaps(frames: List<LightTable.Frame>, gen: Int) {
        val long = (resources.displayMetrics.widthPixels * 1.25f).toInt().coerceIn(720, 1600)
        val centre = frames.lastIndex
        for (f in frames.sortedBy { abs(frames.indexOf(it) - centre) }) {
            if (f.bitmap != null) continue
            loader.later {
                if (gen != generation) return@later
                val k = key(f.photo)
                val bmp = cache.get(k) ?: runCatching {
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, f.photo.uri)) { d, info, _ ->
                        val s = minOf(1f, long.toFloat() / maxOf(info.size.width, info.size.height))
                        d.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt()))
                    }
                }.getOrNull()?.also { cache.put(k, it) } ?: return@later
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    f.bitmap = bmp
                    table.refresh()
                }
            }
        }
    }

    // ===== where the frames lie ===============================================================

    /** Saved places first; frames new to the table go in a spread below the ones already laid out. */
    private fun place(frames: List<LightTable.Frame>) {
        val fresh = mutableListOf<LightTable.Frame>()
        for (f in frames) {
            val v = layoutPrefs.getString(placeKey(f), null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            if (v != null && v.size == 4 && v[2] > 0) { f.x = v[0]; f.y = v[1]; f.w = v[2]; f.z = v[3].toInt() }
            else fresh += f
        }
        if (fresh.isEmpty()) return
        val placed = frames - fresh.toSet()
        // below the pieces already lying there, rails and all
        val top = if (placed.isEmpty()) 0f else placed.maxOf { it.y + table.pieceBelow(it) }
        val z = placed.maxOfOrNull { it.z } ?: 0
        for ((f, t) in spreadOf(fresh)) { f.x = t.first; f.y = t.second + top; f.w = t.third }
        fresh.forEachIndexed { i, f -> f.z = z + i + 1 }
    }

    /**
     * The default spread: rows of [columns] as a cut roll lies in a sleeve. Every picture gets
     * the same long side (a roll's frames are one size), the largest that lets the widest piece,
     * rails and all, fill its cell; each row as tall as its tallest piece, each piece centred
     * in its cell. Measured with the table's own piece geometry, so nothing overlaps whatever
     * the film borders or the pictures' shapes. Centre x, y and picture width, world units.
     */
    private fun spreadOf(frames: List<LightTable.Frame>): Map<LightTable.Frame, Triple<Float, Float, Float>> {
        val out = LinkedHashMap<LightTable.Frame, Triple<Float, Float, Float>>()
        if (frames.isEmpty()) return out
        val cols = prefs.getInt("columns", DEFAULT_COLUMNS).coerceIn(2, 6)
        val gap = 46f * 3 / cols + 10f
        val cell = (1000f - gap * (cols + 1)) / cols
        fun widthFor(f: LightTable.Frame, long: Float) = if (f.aspect >= 1f) long else long * f.aspect
        // pieces are linear in the picture's size: measure each at a long side of 1
        val long = cell / frames.maxOf { table.pieceSize(widthFor(it, 1f), it.aspect).first }
        var y = gap
        for (row in frames.chunked(cols)) {
            val sizes = row.map { table.pieceSize(widthFor(it, long), it.aspect) }
            val rowH = sizes.maxOf { it.second }
            for ((i, f) in row.withIndex()) out[f] = Triple(gap + cell / 2 + i * (cell + gap), y + rowH / 2, widthFor(f, long))
            y += rowH + gap
        }
        return out
    }

    private fun stowKey() = if (pickedMode) "stowed:picked" else "stowed:${rolls.getOrNull(rollIndex)?.firstOrNull()?.name}"

    /** 收回: the photos go into the box below the table, the table left clear. */
    private fun stow() {
        layoutPrefs.edit().putBoolean(stowKey(), true).apply()
        table.stow()
        showStowHint()
    }

    private fun unstow() {
        layoutPrefs.edit().putBoolean(stowKey(), false).apply()
        table.frames.forEachIndexed { i, f -> f.z = i }
        table.unstow(spreadOf(table.frames))
        showStowHint()
    }

    private fun showStowHint() {
        if (table.frames.isEmpty()) return
        if (table.stowed && table.mode == LightTable.Mode.TABLE) {
            empty.text = "照片已收进片盒 · 点「平铺」摆出来"
            empty.alpha = 0f
            empty.visibility = View.VISIBLE
            empty.animate().alpha(1f).setStartDelay(500).setDuration(300).start()
        } else if (empty.visibility == View.VISIBLE) {
            empty.animate().cancel(); empty.alpha = 1f
            empty.visibility = View.GONE
        }
    }

    private fun spread() {
        val frames = table.frames
        if (frames.isEmpty()) return
        frames.forEachIndexed { i, f -> f.z = i }
        table.spread(spreadOf(frames))
    }

    /** Where a frame lies: in its roll, or in 自选 (its own places there). */
    private fun placeKey(f: LightTable.Frame) = (if (pickedMode) "picked:" else "") + f.photo.name
    private fun viewKey(first: String) = if (pickedMode) "view:picked" else "view:$first"

    private fun saveLayout() {
        val frames = table.frames
        if (frames.isEmpty() || table.width == 0) return
        val e = layoutPrefs.edit()
        // z kept small: the order among this roll only
        frames.sortedBy { it.z }.forEachIndexed { i, f -> f.z = i }
        for (f in frames) e.putString(placeKey(f), "%.1f,%.1f,%.1f,%d".format(java.util.Locale.US, f.x, f.y, f.w, f.z))
        val v = table.savedView()
        e.putString(viewKey(frames.first().photo.name), "%.4f,%.4f,%.4f".format(java.util.Locale.US, v[0], v[1], v[2]))
        e.apply()
    }

    // ===== actions ===========================================================================

    private fun setMode(m: LightTable.Mode) {
        coach?.event("mode")
        pendingMode = m
        prefs.edit().putString("mode", if (m == LightTable.Mode.STRIP) "strip" else "table").apply()
        table.setMode(m)
        showStowHint()
        updateToolsForMode(m)
    }

    /** The shelf's 自选, or a roll: which frames are on the table (in whichever mode it is in). */
    private fun showPicked(on: Boolean, index: Int = rollIndex) {
        if (on == pickedMode && index == rollIndex) return
        saveLayout()
        pickedMode = on
        prefs.edit().putBoolean("on_picked", on).apply()
        pickButton.visibility = if (on) View.VISIBLE else View.GONE
        table.stop()
        if (index >= 0) showRoll(index, intro = true)
        if (on && pickedNames.isEmpty()) root.post { showPicker() }
    }

    private fun updateToolsForMode(m: LightTable.Mode) {
        if (m == LightTable.Mode.STRIP) play.set(if (table.playing) Icon.Kind.PAUSE else Icon.Kind.PLAY, if (table.playing) "暂停" else "播放", table.playing)
        // on the table: 平铺 while the photos are in the box, 收回 once they are laid out
        else if (table.stowed) play.set(Icon.Kind.SPREAD, "平铺", false)
        else play.set(Icon.Kind.GATHER, "收回", false)
    }

    /** Playing the strip is for watching: the bars go with it (a double tap on the table brings them back). */
    private fun togglePlay() {
        if (table.playing) { table.stop(); return }
        if (immersive) { table.play(); return }
        // the bars go first; the strip starts once the light box has its full size (the
        // transition takes 220 ms), from the first frame when it stood at the last
        goImmersive(true)
        root.postDelayed({ if (immersive && !table.playing) table.play() }, 300)
    }

    private fun toggleLamp() {
        coach?.event("lamp")
        table.setLamp(!table.lampOn)
        prefs.edit().putBoolean("lamp", table.lampOn).apply()
        updateLamp()
    }

    private fun updateLamp() {
        val on = table.lampOn
        lampButton.setImageDrawable(Icon(Icon.Kind.LAMP, if (on) LightboxLook.accent else LightboxLook.ink, 1.7f, filled = on))
        lampButton.contentDescription = if (on) "关闭背光" else "打开背光"
    }

    private fun toggleLoupe() {
        coach?.event("loupe")
        if (!table.refracts && !prefs.getBoolean("flat_lens_told", false)) {
            prefs.edit().putBoolean("flat_lens_told", true).apply()
            android.widget.Toast.makeText(this, "这台手机的系统版本低于 Android 13，放大镜没有折射效果", android.widget.Toast.LENGTH_LONG).show()
        }
        table.toggleLoupe()
    }

    private fun toggleFavorite() {
        coach?.event("favorite")
        val f = table.current ?: return
        val photo = f.photo
        loader.later {
            val ask = Photos.setFavorite(this, listOf(photo), !photo.favorite)
            runOnUiThread {
                if (ask != null) ask(ask, FAVORITE_REQUEST)
                else { f.photo = photo.copy(favorite = !photo.favorite); table.refresh(); updateLabels(); reload() }
            }
        }
    }

    private fun toggleReject() {
        val f = table.current ?: return
        if (!rejectedNames.remove(f.photo.name)) rejectedNames += f.photo.name
        marks.edit().putStringSet("rejected", rejectedNames.toSet()).apply()
        haptics.play(org.photoall.film.ui.Haptics.Event.TICK)
        table.refresh()
        updateLabels()
    }

    private fun forgetRejects(list: List<Photos.Photo>) {
        rejectedNames.removeAll(list.map { it.name }.toSet())
        marks.edit().putStringSet("rejected", rejectedNames.toSet()).apply()
    }

    /** 清理排除: the rejects off the table, not deleted (they stay in the album). */
    private fun cleanRejects() {
        val list = table.frames.map { it.photo }.filter { it.name in rejectedNames }
        if (list.isEmpty()) return
        hiddenNames += list.map { it.name }
        rejectedNames.removeAll(list.map { it.name }.toSet())
        marks.edit().putStringSet("hidden", hiddenNames.toSet()).putStringSet("rejected", rejectedNames.toSet()).apply()
        haptics.play(org.photoall.film.ui.Haptics.Event.TICK)
        android.widget.Toast.makeText(this, "${list.size} 张已移出观片台，照片还在相册里；设置 ›「已清理」可以放回",
            android.widget.Toast.LENGTH_LONG).show()
        applyList(photos)
    }

    private fun restoreHidden() {
        if (hiddenNames.isEmpty()) return
        hiddenNames.clear()
        marks.edit().putStringSet("hidden", emptySet()).apply()
        applyList(photos)
    }

    @Suppress("DEPRECATION")
    private fun ask(request: PendingIntent, code: Int) = startIntentSenderForResult(request.intentSender, code, null, 0, 0, 0)

    private fun openViewer() {
        val f = table.current ?: return
        val index = photos.indexOfFirst { it.uri == f.photo.uri }
        if (index < 0) return
        table.stop()
        startActivity(Intent(this, ViewerActivity::class.java)
            .putExtra(ViewerActivity.INDEX, index)
            .putExtra(ViewerActivity.FAVORITES, favoritesOnly)
            .putExtra(ViewerActivity.FILM, film)
            .putExtra(ViewerActivity.PRIVATE, privateOnly))
    }

    // ===== settings ==========================================================================

    /** The table's own settings, as on the desktop: lamp-off colour, backlight, film borders, spread, loupe, playback. */
    private fun showSettings() {
        table.stop()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(24)) }
        // a section's title opens and folds it; all folded to begin with
        var target: LinearLayout = body
        fun section(text: String) {
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val arrow = TextView(this).apply { this.text = "›"; textSize = 20f; setTextColor(Style.TEXT_3); rotation = 0f }
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(10))
                addView(TextView(this@LightboxActivity).apply { this.text = text; Style.sectionTitle(this) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(arrow)
                Style.pressable(this)
                setOnClickListener {
                    val open = content.visibility != View.VISIBLE
                    content.visibility = if (open) View.VISIBLE else View.GONE
                    arrow.animate().rotation(if (open) 90f else 0f).setDuration(160).start()
                }
            }, LinearLayout.LayoutParams(-1, -2))
            body.addView(content, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            target = content
        }
        fun slider(label: String, min: Float, max: Float, value: Float, format: (Float) -> String, onChange: (Float) -> Unit) {
            val readout = TextView(this).apply { textSize = 13f; setTextColor(Style.TEXT_2); Style.numeric(this); text = format(value) }
            target.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@LightboxActivity).apply { text = label; textSize = 14f; setTextColor(Style.TEXT) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(readout)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            target.addView(org.photoall.film.ui.Slider(this).apply {
                this.max = 1000
                progress = (((value - min) / (max - min)) * 1000).roundToInt()
                this.onChange = { p -> val v = min + (max - min) * p / 1000f; readout.text = format(v); onChange(v) }
            }, LinearLayout.LayoutParams(-1, -2))
        }

        // 皮肤: the light table's own style, apart from the app's theme
        section("皮肤")
        target.addView(TextView(this).apply {
            text = "观片台的样式，不随软件主题变化"; textSize = 13f; setTextColor(Style.TEXT_2)
        })
        val skins = LightboxLook.SKINS
        target.addView(Segmented(this, skins.map { it.second }).apply {
            select(skins.indexOfFirst { it.first == LightboxLook.skin(this@LightboxActivity) }.coerceAtLeast(0), animate = false)
            onPick = { i ->
                if (skins[i].first != LightboxLook.skin(this@LightboxActivity)) {
                    prefs.edit().putInt("skin", skins[i].first).apply()
                    root.postDelayed({ if (!isFinishing) recreate() }, 180)
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(8); bottomMargin = dp(4) })

        // 关灯底色: the desktop's three, and one of the user's own
        section("关灯底色")
        val custom = prefs.getInt("custom_color", 0xff6b5a44.toInt())
        val dots = mutableListOf<ColorDot>()
        val customRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun pick(color: Int, isCustom: Boolean) {
            table.offColor = color
            prefs.edit().putInt("off_color", color).putBoolean("off_custom", isCustom).apply()
            dots.forEachIndexed { i, dot -> dot.on = if (i < OFF_COLORS.size) !isCustom && OFF_COLORS[i].second == color else isCustom }
            customRows.visibility = if (isCustom) View.VISIBLE else View.GONE
        }
        val isCustom = prefs.getBoolean("off_custom", false)
        target.addView(LinearLayout(this).apply {
            for ((name, color) in OFF_COLORS + ("自定义" to custom)) {
                val dot = ColorDot(this@LightboxActivity, color)
                dots += dot
                addView(LinearLayout(this@LightboxActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(dot, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(TextView(this@LightboxActivity).apply { text = name; textSize = 12f; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                    setOnClickListener { pick(if (name == "自定义") dots.last().color else color, name == "自定义") }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        })
        // the user's own colour: hue, saturation, lightness
        val hsv = FloatArray(3).also { Color.colorToHSV(custom, it) }
        fun setCustom() {
            val c = Color.HSVToColor(hsv)
            dots.last().color = c
            prefs.edit().putInt("custom_color", c).apply()
            pick(c, true)
        }
        target.addView(customRows)
        run {
            fun customSlider(label: String, min: Float, max: Float, i: Int) {
                val readout = TextView(this).apply { textSize = 13f; setTextColor(Style.TEXT_2); Style.numeric(this) }
                fun show() { readout.text = if (i == 0) "${hsv[0].roundToInt()}°" else "${(hsv[i] * 100).roundToInt()}%" }
                show()
                customRows.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@LightboxActivity).apply { text = label; textSize = 13f; setTextColor(Style.TEXT) }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(readout)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                customRows.addView(org.photoall.film.ui.Slider(this).apply {
                    this.max = 1000
                    progress = (((hsv[i] - min) / (max - min)) * 1000).roundToInt()
                    onChange = { p -> hsv[i] = min + (max - min) * p / 1000f; show(); setCustom() }
                }, LinearLayout.LayoutParams(-1, -2))
            }
            customSlider("色相", 0f, 360f, 0)
            customSlider("饱和度", 0f, 1f, 1)
            customSlider("明度", 0f, 1f, 2)
        }
        pick(table.offColor, isCustom)

        section("背光")
        slider("背光亮度", 40f, 160f, table.brightness.toFloat(), { "${it.roundToInt()}%" }) { v ->
            table.brightness = v.roundToInt(); prefs.edit().putInt("brightness", table.brightness).apply()
        }

        section("片边")
        target.addView(android.widget.Switch(this).apply {
            text = "自动加片边（片孔、片边字）"
            textSize = 14f
            setTextColor(Style.TEXT)
            isChecked = table.borders
            Style.switch(this)
            setOnCheckedChangeListener { _, on ->
                table.borders = on
                prefs.edit().putBoolean("borders", on).apply()
                spread() // the pieces change size: lay them out again
            }
        }, LinearLayout.LayoutParams(-1, dp(44)))

        section("平铺")
        target.addView(TextView(this).apply { text = "每行几张（点「平铺」时按此排列）"; textSize = 14f; setTextColor(Style.TEXT) })
        val columns = listOf(2, 3, 4, 5, 6)
        target.addView(Segmented(this, columns.map { "$it" }).apply {
            select(columns.indexOf(prefs.getInt("columns", DEFAULT_COLUMNS)).coerceAtLeast(0), animate = false)
            onPick = { i -> prefs.edit().putInt("columns", columns[i]).apply() }
        }, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(8) })

        section("已清理")
        val hiddenHere = photos.count { it.name in hiddenNames }
        target.addView(TextView(this).apply {
            text = if (hiddenHere == 0) "没有清理出观片台的照片" else "$hiddenHere 张照片被「清理排除」移出了观片台（没有删除，还在相册里）"
            textSize = 14f; setTextColor(Style.TEXT); setLineSpacing(0f, 1.25f)
        })
        if (hiddenHere > 0) target.addView(Button(this).apply {
            text = "全部放回观片台"
            isAllCaps = false
            Style.textButton(this, 14f)
            setOnClickListener {
                restoreHidden()
                text = "已放回"; isEnabled = false
            }
        }, LinearLayout.LayoutParams(-2, dp(40)).apply { topMargin = dp(6) })

        section("放大镜")
        slider("镜片大小", 0.12f, 0.45f, table.lensSize, { "${(it * 200).roundToInt()}%" }) { v ->
            table.lensSize = v; prefs.edit().putFloat("lens_size", v).apply()
        }
        slider("倍率", 1.5f, 8f, table.magnification, { "%.1f×".format(it) }) { v ->
            table.magnification = v; table.refresh(); prefs.edit().putFloat("magnification", v).apply()
        }

        section("联排播放")
        target.addView(TextView(this).apply { text = "方向（联排与自选）"; textSize = 14f; setTextColor(Style.TEXT) })
        target.addView(Segmented(this, listOf("竖向", "横向")).apply {
            select(if (table.stripHorizontal) 1 else 0, animate = false)
            onPick = { i -> table.stripHorizontal = i == 1; prefs.edit().putBoolean("strip_horizontal", i == 1).apply() }
        }, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(8); bottomMargin = dp(4) })
        slider("每张停留", 2f, 15f, table.secondsPerFrame, { "%.1f 秒".format(it) }) { v ->
            table.secondsPerFrame = v; prefs.edit().putFloat("seconds", v).apply()
        }
        target.addView(TextView(this).apply {
            text = "点「播放」自动进入沉浸模式，双击画面退出"; textSize = 12f; setTextColor(Style.TEXT_3)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        body.addView(TextView(this).apply {
            text = "单击选中 · 再点一次看大图 · 双击收藏 · 长按出放大镜\n台面：拖动摆片 · 按住照片双指缩放改大小 · 空白处拖动或缩放台面"
            textSize = 12f
            setTextColor(Style.TEXT_3)
            setLineSpacing(0f, 1.3f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        lateinit var sheet: Sheet
        body.addView(TextView(this).apply {
            text = "重看教程"
            textSize = 14f; setTextColor(Style.ACCENT); typeface = Style.medium; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = Style.surface(this@LightboxActivity, fill = Style.SURFACE_2, radiusDp = 12f)
            Style.pressable(this)
            setOnClickListener { sheet.dismiss(); root.postDelayed({ startTour() }, 350) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        sheet = Sheet(this, "观片台设置", scroll, fixedHeight = 0.72f)
        sheet.show()
    }

    /**
     * 选片 for 自选: every photo of the album (as filtered here), newest first, four to a row;
     * a tap picks it or puts it back. Closing keeps the choice and shows the picked strip.
     */
    private fun showPicker() {
        table.stop()
        val all = photos
        val before = pickedNames.toSet()
        val count = TextView(this).apply { textSize = 13f; setTextColor(Style.TEXT_2) }
        fun showCount() { count.text = "已选 ${pickedNames.size} 张 · 点照片选上或取消" }
        val grid = android.widget.GridLayout(this).apply { columnCount = 4 }
        // four equal columns across the sheet (its padding, the cells' margins and the sheet's own edge off)
        val cell = ((resources.displayMetrics.widthPixels - dp(32) - dp(16) - dp(16)) / 4).coerceAtLeast(dp(56))
        val views = ArrayList<android.widget.ImageView>(all.size)
        val ticks = ArrayList<TextView>(all.size)
        for (p in all) {
            val image = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Style.SURFACE_2)
            }
            val tick = TextView(this).apply {
                text = "✓"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Style.ON_ACCENT)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Style.ACCENT); setStroke(dp(2), Color.WHITE)
                }
            }
            val box = FrameLayout(this).apply {
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(tick, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); marginEnd = dp(5) })
                fun paint() {
                    val on = p.name in pickedNames
                    tick.visibility = if (on) View.VISIBLE else View.GONE
                    image.alpha = if (on) 1f else 0.8f
                    foreground = if (on) android.graphics.drawable.GradientDrawable().apply { setStroke(dp(3), Style.ACCENT) } else null
                }
                paint()
                setOnClickListener {
                    if (!pickedNames.remove(p.name)) pickedNames += p.name
                    haptics.play(org.photoall.film.ui.Haptics.Event.TICK)
                    paint(); showCount()
                }
            }
            grid.addView(box, android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = cell; setMargins(dp(2), dp(2), dp(2), dp(2))
            })
            views += image; ticks += tick
        }
        showCount()
        val clear = TextView(this).apply {
            text = "清空"; textSize = 13f; setTextColor(Style.ACCENT); setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener {
                pickedNames.clear(); showCount()
                for (i in 0 until grid.childCount) { ticks[i].visibility = View.GONE; views[i].alpha = 0.8f; (grid.getChildAt(i) as FrameLayout).foreground = null }
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
            addView(LinearLayout(this@LightboxActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(count, LinearLayout.LayoutParams(0, -2, 1f))
                addView(clear)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(android.widget.ScrollView(this@LightboxActivity).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        // the thumbnails, a few at a time, while the sheet is open
        var open = true
        loader.later {
            for ((i, p) in all.withIndex()) {
                if (!open) break
                val b = runCatching { contentResolver.loadThumbnail(p.uri, android.util.Size(cell, cell), null) }.getOrNull() ?: continue
                runOnUiThread { if (open) views[i].setImageBitmap(b) }
            }
        }
        val sheet = Sheet(this, "选片", content, fixedHeight = 0.82f)
        sheet.setOnDismissListener {
            open = false
            prefs.edit().putStringSet("picked", pickedNames.toSet()).apply()
            if (pickedMode && pickedNames != before && rollIndex >= 0) showRoll(rollIndex, intro = true)
            updateShelf()
        }
        sheet.show()
    }

    /** A colour to choose: a disc, ringed when chosen. */
    private class ColorDot(context: Context, color: Int) : View(context) {
        var color = color
            set(v) { field = v; invalidate() }
        var on = false
            set(v) { field = v; invalidate() }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) / 2f
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(cx, cy, r - 6 * d, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1 * d
            paint.color = Style.HAIRLINE
            canvas.drawCircle(cx, cy, r - 6 * d, paint)
            if (on) {
                paint.strokeWidth = 2 * d
                paint.color = Style.TEXT
                canvas.drawCircle(cx, cy, r - 2 * d, paint)
            }
        }
    }

    // ===== labels ============================================================================

    private fun filmLabel(p: Photos.Photo): String {
        val name = Photos.filmOf(p)
        return when {
            name.isEmpty() -> "PHOTO ALL"
            name == "直出" -> "DIGITAL"
            else -> CanisterView.displayName(name)
        }
    }

    private fun updateLabels() {
        val roll = shownRoll()
        if (roll == null) { title.text = "观片台"; subtitle.text = ""; clean.visibility = View.INVISIBLE; return }
        title.text = if (pickedMode) "自选 · %d 张".format(roll.size) else "ROLL %02d · %d EXP".format(rollIndex + 1, roll.size)
        val f = table.current
        val kept = table.frames.count { it.photo.favorite }
        val out = table.frames.count { it.photo.name in rejectedNames }
        subtitle.text = buildString {
            if (f != null) append("%02d / %d · %s".format(f.number, roll.size, filmLabel(f.photo).uppercase()))
            append("  ♥ $kept  ✕ $out")
        }
        clean.text = "清理排除 $out"
        clean.visibility = if (out > 0) View.VISIBLE else if (LightboxLook.capsule) View.GONE else View.INVISIBLE
        heart.set(Icon.Kind.HEART, if (f?.photo?.favorite == true) "已收藏" else "收藏", f?.photo?.favorite == true)
        val isOut = f != null && f.photo.name in rejectedNames
        reject.set(Icon.Kind.CROSS, if (isOut) "已排除" else "排除", isOut)
    }

    /** The chip chosen last time the shelf was laid out (a new one springs in). */
    private var shelfChosen = -2

    private fun updateShelf() {
        shelf.removeAllViews()
        // 自选: the photos picked from every roll, on the table or as a strip
        if (rolls.isNotEmpty()) shelf.addView(Button(this).apply {
            text = if (pickedNames.isEmpty()) "自选" else "自选 ${pickedList().size}"
            isAllCaps = false; isSingleLine = true
            textSize = 12f
            stateListAnimator = null
            Style.compact(this, 12f)
            Style.pressable(this)
            val on = pickedMode
            background = (if (!on) LightboxLook.key(resources.displayMetrics.density, 15f) else null)
                ?: Style.surface(this@LightboxActivity, fill = if (on) Style.ACCENT else Color.TRANSPARENT, radiusDp = 15f,
                    stroke = if (on) 0 else Style.ACCENT)
            setTextColor(if (on) Style.ON_ACCENT else Style.ACCENT)
            typeface = LightboxLook.labelFace(Style.medium)
            if (LightboxLook.capsule) classicChip(this, on, gold = true)
            contentDescription = "自选：只放挑出的照片"
            setOnClickListener { if (!pickedMode) showPicked(true) else showPicker() }
        }, LinearLayout.LayoutParams(-2, dp(if (LightboxLook.capsule) 32 else 30)).apply { marginEnd = dp(if (LightboxLook.capsule) 8 else 10) })
        for ((i, roll) in rolls.withIndex()) {
            val on = i == rollIndex && !pickedMode // in 自选 no roll is the one shown
            val chip = Button(this).apply {
                text = "ROLL %02d".format(i + 1)
                isAllCaps = false; isSingleLine = true
                textSize = 12f
                stateListAnimator = null
                Style.compact(this, 12f)
                Style.pressable(this)
                Style.numeric(this)
                background = (if (!on) LightboxLook.key(resources.displayMetrics.density, 15f) else null)
                    ?: Style.surface(this@LightboxActivity, fill = if (on) Style.TEXT else Color.TRANSPARENT, radiusDp = 15f,
                        stroke = if (on) 0 else Style.HAIRLINE)
                setTextColor(if (on) Style.BG else Style.TEXT_2)
                typeface = LightboxLook.labelFace(if (on) Style.medium else Style.regular)
                if (LightboxLook.capsule) classicChip(this, on, gold = false)
                contentDescription = "第 ${i + 1} 卷，${roll.size} 张"
                setOnClickListener {
                    if (pickedMode) showPicked(false, i)
                    else if (i != rollIndex) showRoll(i, intro = true)
                }
            }
            shelf.addView(chip, LinearLayout.LayoutParams(-2, dp(if (LightboxLook.capsule) 32 else 30)).apply { marginEnd = dp(6) })
        }
        val chosen = if (pickedMode) 0 else rollIndex + 1
        if (LightboxLook.capsule && shelfChosen != -2 && chosen != shelfChosen && android.animation.ValueAnimator.areAnimatorsEnabled())
            shelf.getChildAt(chosen)?.let { c ->
                c.scaleX = 0.86f; c.scaleY = 0.86f
                c.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
            }
        shelfChosen = chosen
        shelf.post {
            val chip = shelf.getChildAt(if (pickedMode) 0 else rollIndex + 1) ?: return@post
            shelfScroll.smoothScrollTo((chip.left - shelfScroll.width / 2 + chip.width / 2).coerceAtLeast(0), 0)
        }
    }

    // ===== small views =======================================================================

    /** A tool in the bottom bar: its mark over a short name, in the accent while on. */
    private inner class Tool(kind: Icon.Kind, label: String, onClick: () -> Unit) {
        private val icon = ImageView(this@LightboxActivity)
        private val pill = LightboxLook.capsule
        private val text = TextView(this@LightboxActivity).apply {
            textSize = if (pill) 13.5f else 10f; gravity = Gravity.CENTER; maxLines = 1
            typeface = LightboxLook.labelFace(if (pill) Style.medium else typeface)
        }
        val view = LinearLayout(this@LightboxActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (pill) {
                // 经典: a lighter glass pill in the capsule, the word alone
                background = LightboxLook.glass(resources.displayMetrics.density, 0x14ffffff, shadow = false)
                Style.pressable(this)
                addView(text, LinearLayout.LayoutParams(-2, -2))
            } else {
                background = LightboxLook.key(resources.displayMetrics.density, 14f)
                    ?: Style.surface(this@LightboxActivity, fill = Color.TRANSPARENT, radiusDp = 14f)
                if (LightboxLook.skinned) setPadding(0, dp(4), 0, dp(4))
                Style.pressable(this)
                addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(text, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            }
            setOnClickListener { onClick() }
        }
        init { set(kind, label, false) }
        fun set(kind: Icon.Kind, label: String, on: Boolean) {
            val color = if (on) Style.ACCENT else Style.TEXT
            icon.setImageDrawable(Icon(kind, color, 1.7f, filled = on && kind == Icon.Kind.HEART))
            val changed = pill && text.text.isNotEmpty() && text.text.toString() != label
            text.text = label
            text.setTextColor(if (pill) (if (on) LightboxLook.accent else LightboxLook.ink)
                else if (on) Style.ACCENT else Style.TEXT_2)
            if (changed && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                // the new word rises in
                text.alpha = 0f; text.translationY = dp(6).toFloat()
                text.animate().alpha(1f).translationY(0f).setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
            }
            view.contentDescription = label
        }
    }

    private fun iconButton(kind: Icon.Kind, description: String, onClick: () -> Unit) = ImageView(this).apply {
        contentDescription = description
        if (LightboxLook.capsule) {
            // 经典: a round dark key, as Apple's
            setImageDrawable(Icon(kind, LightboxLook.ink, 1.7f))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = LightboxLook.glass(resources.displayMetrics.density, 0x1fffffff)
            Style.pressable(this)
            setOnClickListener { onClick() }
            return@apply
        }
        setImageDrawable(Icon(kind, Style.TEXT))
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = LightboxLook.key(resources.displayMetrics.density)
            ?: Style.surface(this@LightboxActivity, fill = Color.TRANSPARENT, radiusDp = 24f)
        setOnClickListener { onClick() }
    }

    /** 经典's chips: a round-ended pill, raised dark, white when chosen; plain sans words. */
    private fun classicChip(b: TextView, on: Boolean, gold: Boolean) {
        b.background = LightboxLook.glass(resources.displayMetrics.density, if (on) 0x4dffffff else 0x14ffffff, shadow = on)
        b.setTextColor(if (on) LightboxLook.chosenInk else if (gold) LightboxLook.accent else LightboxLook.inkSoft)
        b.typeface = LightboxLook.labelFace(Style.medium)
        b.textSize = 13f
        b.letterSpacing = 0.01f
        b.setPadding(dp(14), 0, dp(14), 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val ROLL_LENGTH = 36
        private const val LAYOUT_VERSION = 2
        /** 平铺 by default: two across, each frame large enough to read on a phone. */
        private const val DEFAULT_COLUMNS = 2
        /** The desktop table's lamp-off colours. */
        private val DESK_COLORS = listOf("米黄" to 0xffdfd5b5.toInt(), "暖灰" to 0xffa5a49d.toInt(), "灰黑" to 0xff272c2e.toInt())
        /** The desk's colours, the style's own first when it has one of its own. */
        private val OFF_COLORS: List<Pair<String, Int>> get() {
            val own = LightboxLook.offDefault(DESK_COLORS[0].second)
            return if (DESK_COLORS.any { it.second == own }) DESK_COLORS else listOf("主题" to own) + DESK_COLORS
        }
        private const val FAVORITE_REQUEST = 1
        private const val TRASH_REQUEST = 2
    }
}
