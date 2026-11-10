package org.photoall.film.gallery

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.film.camera.HistogramPainter
import org.photoall.film.ui.CanisterView
import org.photoall.film.ui.Icon
import org.photoall.film.ui.Style
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * One photo at a time (the design's 查看照片 board): the frame counter and date above,
 * the picture with its film's edge print under it, the recipe as chips, the exposure
 * line, and the actions. Swipe sideways between photos, pinch / double-tap to zoom;
 * swiping up shows the histogram, again the shooting record; down steps back.
 */
class ViewerActivity : Activity() {
    private val loader = Executors.newSingleThreadExecutor()
    /** The neighbours' previews, apart from the full-size decode so they are never queued behind it. */
    private val previewLoader = Executors.newSingleThreadExecutor()
    private var photos: List<Photos.Photo> = emptyList()
    private var index = 0
    private var request = 0
    private var favoritesOnly = false
    private lateinit var image: ZoomImageView
    private lateinit var counter: TextView
    private lateinit var date: TextView
    private lateinit var favoriteButton: android.widget.ImageView
    private lateinit var edgeFilm: TextView
    private lateinit var edgeFrame: TextView
    private lateinit var recipeChips: LinearLayout
    private lateinit var exposureLine: TextView
    private var film: String? = null
    private var privateOnly = false
    private lateinit var histogram: HistogramView
    private lateinit var panel: LinearLayout
    private lateinit var photoFrame: FrameLayout
    private lateinit var histCard: LinearLayout
    private val histSlot by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    /** The glass's veil and the cards on it: see-through, in the theme's ground and ink. */
    private val glassVeil get() = (Style.BG and 0x00ffffff) or (if (Style.palette.light) 0xb8 shl 24 else 0xa6 shl 24)
    private val glassCard get() = (Style.TEXT and 0x00ffffff) or (if (Style.palette.light) 0x10 shl 24 else 0x14 shl 24)
    /**
     * The photo as it is shown, blurred: the frosted glass's ground. It draws the image view
     * itself, so zoom, pan and a page being dragged all follow; the sheet clips it to its
     * own bounds. Android 12+ blurs it; on 10 and 11 the veil alone is there (more opaque).
     */
    private var frostReady = false
    private val frost by lazy {
        frostReady = true
        object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                canvas.save()
                canvas.translate(image.translationX, 0f)
                image.draw(canvas)
                canvas.restore()
                // a page on the move: follow it frame by frame until it rests
                if (image.translationX != 0f) postInvalidateOnAnimation()
            }
        }.apply {
            visibility = View.GONE
            if (android.os.Build.VERSION.SDK_INT >= 31)
                setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(36).toFloat(), dp(36).toFloat(),
                    android.graphics.Shader.TileMode.CLAMP))
            else setBackgroundColor(Style.BG)
        }
    }
    private lateinit var detailRows: LinearLayout
    private lateinit var detailScroll: android.widget.ScrollView
    /** Sections the user opened; they stay open from photo to photo, for comparing. */
    private val expanded = mutableSetOf<String>()
    /** The whole record of the current photo as text, for the copy button. */
    private var detailText = ""
    /** The photo the details panel was built for (null: build it again when shown). */
    private var detailsFor: Uri? = null
    private var shotLog: ShotLog? = null
    private var panelLevel = 0 // 0 hidden, 1 histogram, 2 histogram + details
    private var shot: ShotInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        org.photoall.film.ui.Style.apply(this)
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra(INDEX, 0)
        favoritesOnly = intent.getBooleanExtra(FAVORITES, false)
        film = intent.getStringExtra(FILM)
        privateOnly = intent.getBooleanExtra(PRIVATE, false)
        image = ZoomImageView(this).apply {
            onSwipe = { step -> show(index + step) }
            onSwipeVertical = { step -> setPanel(panelLevel + step) }
            onDrag = { dx -> dragPage(dx) }
            onDragEnd = { dx, vx -> releasePage(dx, vx) }
            onDisplayChanged = { if (frostReady && frost.visibility == View.VISIBLE) frost.invalidate() }
        }
        // the neighbouring photo, sliding in beside the page being dragged away
        peek = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
        }
        counter = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setTextColor(Style.TEXT)
            gravity = Gravity.CENTER
        }
        date = TextView(this).apply { textSize = 11f; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER }
        favoriteButton = iconButton(Icon.Kind.HEART, "收藏") { toggleFavorite() }
        histogram = HistogramView(this)
        detailRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // The record: a card sheet in the theme's colours over the photo's lower part, a
        // handle, the histogram in a well of its own, then (fully open) the key facts as
        // tiles and the shot log's sections as cards.
        // Frosted glass, square edged: the photo behind it blurred (frost, drawn under it and
        // clipped to it), a veil of the theme's ground and a hairline along the top.
        panel = object : LinearLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                frost.clipBounds = android.graphics.Rect(l, t, r, b)
                frost.invalidate()
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(glassVeil),
                android.graphics.drawable.GradientDrawable().apply { setColor(Style.HAIRLINE) },
            )).apply { setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL); setLayerHeight(1, maxOf(1, dp(1) / 2)) }
            setPadding(dp(16), dp(8), dp(16), dp(10))
            visibility = View.GONE
            addView(View(this@ViewerActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Style.TEXT_3) }
            }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
            addView(LinearLayout(this@ViewerActivity).apply {
                histCard = this
                orientation = LinearLayout.VERTICAL
                background = Style.surface(this@ViewerActivity, fill = glassCard, radiusDp = 14f)
                setPadding(dp(12), dp(10), dp(12), dp(8))
                addView(sectionTitle("直方图"))
                addView(histogram, LinearLayout.LayoutParams(-1, dp(118)).apply { topMargin = dp(6) })
            })
            detailScroll = android.widget.ScrollView(this@ViewerActivity).apply {
                isVerticalScrollBarEnabled = false
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(16))
                // fully open, the histogram scrolls with the record (histSlot holds it then)
                addView(LinearLayout(this@ViewerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(histSlot, LinearLayout.LayoutParams(-1, -2))
                    addView(detailRows, LinearLayout.LayoutParams(-1, -2))
                })
            }
            // the rest of the sheet's height; it scrolls
            addView(detailScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        }
        val topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(iconButton(Icon.Kind.BACK, "返回") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(counter)
                addView(date)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(favoriteButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        // The film's edge print under the picture: stock on the left, frame on the right.
        edgeFilm = edgeText()
        edgeFrame = edgeText()
        val edge = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(GalleryActivity.FILM_BASE)
            setPadding(dp(12), 0, dp(12), 0)
            addView(edgeFilm, LinearLayout.LayoutParams(0, -2, 1f))
            addView(edgeFrame)
        }
        recipeChips = LinearLayout(this)
        exposureLine = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Style.TEXT_2)
            setPadding(0, dp(10), 0, 0)
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            addView(android.widget.HorizontalScrollView(this@ViewerActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(recipeChips)
            })
            addView(exposureLine)
        }
        val actions = LinearLayout(this).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            for ((kind, label, action) in listOf<Triple<Icon.Kind, String, () -> Unit>>(
                Triple(Icon.Kind.SHARE, "分享") { share() },
                Triple(Icon.Kind.FRAME, "边框") { openFrames() },
                Triple(Icon.Kind.OPEN, "打开") { openWith() },
                Triple(Icon.Kind.INFO, "信息") { setPanel(if (panelLevel == 2) 0 else 2) },
                Triple(Icon.Kind.TRASH, "删除") { trash() },
            )) addView(action(kind, label, action), LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            addView(topBar, LinearLayout.LayoutParams(-1, dp(56)))
            addView(FrameLayout(this@ViewerActivity).apply {
                setBackgroundColor(GalleryActivity.FILM_BASE)
                photoFrame = this
                addView(peek, FrameLayout.LayoutParams(-1, -1))
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(frost, FrameLayout.LayoutParams(-1, -1))
                addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(edge, LinearLayout.LayoutParams(-1, dp(20)))
            addView(meta)
            addView(Style.hairline(this@ViewerActivity), LinearLayout.LayoutParams(-1, 1))
            addView(actions)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        reload()
    }

    private var paused = false

    override fun onPause() {
        super.onPause()
        paused = true
    }

    override fun onResume() {
        super.onResume()
        if (paused) { paused = false; reload() } // a bordered copy may have joined the album
    }

    override fun onDestroy() {
        loader.shutdownNow()
        previewLoader.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("System confirmation for favourites / recycle bin")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        reload()
    }

    private fun reload() {
        loader.later {
            val list = Photos.filter(Photos.list(this, favoritesOnly), film, privateOnly)
            runOnUiThread {
                photos = list
                show(index, keepImage = false)
            }
        }
    }

    private fun current() = photos.getOrNull(index)

    private fun show(target: Int, keepImage: Boolean = true) {
        if (photos.isEmpty()) {
            finish()
            return
        }
        val previous = current()?.uri
        index = target.coerceIn(0, photos.lastIndex)
        val photo = photos[index]
        // Numbered like a roll (newest highest), as in the contact sheet.
        val frame = photos.size - index
        counter.text = "${index + 1} / ${photos.size}"
        date.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(photo.taken * 1000))
        favoriteButton.setImageDrawable(Icon(Icon.Kind.HEART, if (photo.favorite) Style.ACCENT else Style.TEXT, filled = photo.favorite))
        favoriteButton.contentDescription = if (photo.favorite) "取消收藏" else "收藏"
        val name = Photos.filmOf(photo)
        edgeFilm.text = (if (name == "直出" || name.isEmpty()) "DIGITAL" else CanisterView.displayName(name)).uppercase()
        edgeFrame.text = "$frame  ▸${frame}A"
        // The recipe saved with the photo, one chip per part ("portra 400 · endura · HDR ...").
        recipeChips.removeAllViews()
        for (part in photo.description.split(" · ").map { it.trim() }.filter { it.isNotEmpty() })
            recipeChips.addView(TextView(this).apply {
                text = if (part.startsWith("kodak") || part.startsWith("fuji")) CanisterView.displayName(part) else part
                textSize = 12f
                setTextColor(Style.TEXT)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(10), dp(5), dp(10), dp(5)) // height from the text: large system fonts fit
                background = Style.surface(this@ViewerActivity, fill = Style.SURFACE_2, radiusDp = 13f)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        // the next page's previews start now, not after this page's full decode
        preloadNeighbours()
        if (keepImage && previous == photo.uri) return
        val ticket = ++request
        loader.later {
            // Decode at most 4096 px on the long edge: sharp when zoomed, bounded memory. A
            // hardware bitmap goes to the GPU here, on this thread; a software one would be
            // uploaded on the UI thread at its first draw, a stall right after the page turn.
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, photo.uri)) { decoder, inf, _ ->
                    val s = minOf(1f, 4096f / maxOf(inf.size.width, inf.size.height))
                    decoder.setTargetSize((inf.size.width * s).toInt(), (inf.size.height * s).toInt())
                }
            }.getOrNull()
            // the histogram reads pixels: from a small software copy of its own
            val counts = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, photo.uri)) { decoder, inf, _ ->
                    val s = minOf(1f, 768f / maxOf(inf.size.width, inf.size.height))
                    decoder.setTargetSize(maxOf(1, (inf.size.width * s).toInt()), maxOf(1, (inf.size.height * s).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.let { small -> HistogramPainter.of(small, bins = 256, grid = 768).also { small.recycle() } }
            }.getOrNull()
            val info = ShotInfo.read(this, photo.uri)
            val log = ShotLog.load(this, photo.name)
            runOnUiThread {
                if (ticket == request) {
                    image.setImageBitmap(bitmap as Bitmap?)
                    // arriving by a page turn, the preview is already in place: no fade
                    if (!arrived) {
                        image.alpha = 0f
                        image.animate().alpha(1f).setDuration(160).start()
                    }
                    arrived = false
                    preloadNeighbours()
                    histogram.histogram = counts
                    shot = info
                    shotLog = log
                    exposureLine.text = listOfNotNull(info?.iso?.let { "ISO $it" }, info?.shutterText,
                        info?.fNumber?.let { "ƒ%.1f".format(it) }, info?.focal35?.let { "${it}mm" }
                            ?: info?.focalMm?.let { "%.1fmm".format(it) }).joinToString("   ").ifEmpty { "没有曝光数据" }
                    detailsFor = null // built when the details panel is open
                    if (panelLevel == 2) fillDetails()
                }
            }
        }
    }

    // ---- turning the page with the finger ------------------------------------------------------

    private lateinit var peek: ImageView
    private var arrived = false
    /** Small copies of the photos either side, so a turned page has a picture at once. */
    private val previews = java.util.concurrent.ConcurrentHashMap<Uri, Bitmap>()
    private val gap get() = dp(20).toFloat()

    private fun neighbour(step: Int) = photos.getOrNull(index + step)

    private fun preloadNeighbours() {
        val wanted = listOfNotNull(neighbour(-1), neighbour(1), current()).map { it.uri }.toSet()
        previews.keys.retainAll(wanted)
        for (p in listOfNotNull(neighbour(-1), neighbour(1))) if (!previews.containsKey(p.uri)) previewLoader.later {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, p.uri)) { decoder, inf, _ ->
                    val k = minOf(1f, 1600f / maxOf(inf.size.width, inf.size.height))
                    decoder.setTargetSize((inf.size.width * k).toInt(), (inf.size.height * k).toInt())
                }
            }.getOrNull()?.let { previews[p.uri] = it }
        }
    }

    /** The page follows the finger; the next (or previous) photo comes in beside it. */
    private fun dragPage(dx: Float) {
        image.animate().cancel(); peek.animate().cancel()
        if (frostReady && frost.visibility == View.VISIBLE) frost.invalidate()
        val step = if (dx < 0) 1 else -1
        val next = neighbour(step)
        // past the first or the last photo: it gives, but only a little
        val moved = if (next == null) dx * 0.25f else dx
        image.translationX = moved
        if (next == null) { peek.visibility = View.INVISIBLE; return }
        if (peek.tag != next.uri || peek.drawable == null) { // its preview may have arrived since
            peek.setImageBitmap(previews[next.uri])
            peek.tag = next.uri
        }
        peek.visibility = View.VISIBLE
        peek.translationX = moved + step * (image.width + gap)
    }

    /** Let go: past a third of the width, or flicked that way, the page turns; else it springs back. */
    private fun releasePage(dx: Float, vx: Float) {
        val step = if (dx < 0) 1 else -1
        val next = neighbour(step)
        val w = image.width + gap
        val flung = Math.abs(vx) > 900 && Math.signum(vx) == Math.signum(dx)
        if (next != null && dx != 0f && (Math.abs(dx) > image.width * 0.3f || flung)) {
            val speed = if (flung) 180L else 240L
            image.animate().translationX(-step * w).setDuration(speed)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            peek.animate().translationX(0f).setDuration(speed)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction {
                    // the preview takes the page's place; the full picture follows quietly
                    val shown = previews[next.uri]
                    image.translationX = 0f
                    if (shown != null) { image.setImageBitmap(shown); arrived = true }
                    peek.visibility = View.INVISIBLE
                    peek.translationX = 0f
                    show(index + step, keepImage = false)
                }.start()
        } else {
            image.animate().translationX(0f).setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
            peek.animate().translationX(step * w).setDuration(220)
                .withEndAction { peek.visibility = View.INVISIBLE }.start()
        }
    }

    private var sheetAnim: android.animation.Animator? = null

    /** The frost under the sheet follows it as it moves (clipped to where the sheet is now). */
    private fun syncFrost() {
        val ty = panel.translationY.toInt()
        frost.clipBounds = android.graphics.Rect(panel.left, panel.top + ty, panel.right, panel.bottom + ty)
    }

    /** The sheet's height for [level]: two thirds of the photo when full, its content otherwise. */
    private fun sheetHeight(level: Int): Int = if (level == 2) (photoFrame.height * 0.66f).toInt() else {
        panel.measure(View.MeasureSpec.makeMeasureSpec(photoFrame.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(photoFrame.height, View.MeasureSpec.AT_MOST))
        panel.measuredHeight
    }

    /** The histogram: fixed under the handle when alone, at the head of the scroll when open. */
    private fun placeHistogram(level: Int) {
        val home: android.view.ViewGroup = if (level == 2) histSlot else panel
        if (histCard.parent === home) return
        (histCard.parent as? android.view.ViewGroup)?.removeView(histCard)
        if (home === panel) panel.addView(histCard, 1, LinearLayout.LayoutParams(-1, -2))
        else histSlot.addView(histCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    /**
     * 0 closed, 1 the histogram, 2 the whole record. The sheet rises out of the bottom edge,
     * grows or shrinks between the two heights, and sinks away; the frosted photo under it
     * moves with it.
     */
    private fun setPanel(level: Int) {
        val from = panelLevel
        panelLevel = level.coerceIn(0, 2)
        if (photoFrame.height <= 0) { photoFrame.post { panelLevel = from; setPanel(level) }; return }
        sheetAnim?.cancel()
        val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        (detailScroll.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
        if (panelLevel == 0) {
            if (panel.visibility != View.VISIBLE) return
            sheetAnim = android.animation.ObjectAnimator.ofFloat(panel, View.TRANSLATION_Y, panel.translationY, panel.height.toFloat()).apply {
                duration = 240
                interpolator = android.view.animation.AccelerateInterpolator(1.4f)
                addUpdateListener { syncFrost(); frost.alpha = 1f - it.animatedFraction }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (panelLevel != 0) return
                        panel.visibility = View.GONE
                        frost.visibility = View.GONE
                        panel.translationY = 0f
                        frost.alpha = 1f
                    }
                })
                start()
            }
            return
        }
        if (panelLevel == 2) fillDetails()
        if (from == 0 || panel.visibility != View.VISIBLE) {
            // rising out of the bottom edge at its new height
            placeHistogram(panelLevel)
            detailScroll.visibility = if (panelLevel == 2) View.VISIBLE else View.GONE
            lp.height = if (panelLevel == 2) sheetHeight(2) else -2
            panel.layoutParams = lp
            val h = sheetHeight(panelLevel).toFloat()
            panel.visibility = View.VISIBLE
            frost.visibility = View.VISIBLE
            panel.translationY = h
            frost.alpha = 0f
            frost.invalidate()
            sheetAnim = android.animation.ObjectAnimator.ofFloat(panel, View.TRANSLATION_Y, h, 0f).apply {
                duration = 360
                interpolator = ease
                addUpdateListener { syncFrost(); frost.alpha = it.animatedFraction }
                start()
            }
            return
        }
        if (from == panelLevel) return
        // between the histogram alone and the whole record: the height glides
        val startH = panel.height
        if (panelLevel == 2) { placeHistogram(2); detailScroll.visibility = View.VISIBLE }
        else detailScroll.smoothScrollTo(0, 0)
        val endH = if (panelLevel == 2) sheetHeight(2) else {
            // the closed height as it will be laid out: histogram back under the handle
            detailScroll.visibility = View.GONE
            placeHistogram(1)
            lp.height = -2
            sheetHeight(1).also {
                placeHistogram(2)
                detailScroll.visibility = View.VISIBLE
            }
        }
        lp.height = startH
        panel.layoutParams = lp
        sheetAnim = android.animation.ValueAnimator.ofInt(startH, endH).apply {
            duration = 320
            interpolator = ease
            addUpdateListener {
                lp.height = it.animatedValue as Int
                panel.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (panelLevel == 1) {
                        placeHistogram(1)
                        detailScroll.visibility = View.GONE
                        lp.height = -2
                        panel.layoutParams = lp
                    }
                }
            })
            start()
        }
    }

    private fun toggleFavorite() {
        val photo = current() ?: return
        loader.later {
            val ask = Photos.setFavorite(this, listOf(photo), !photo.favorite)
            runOnUiThread { if (ask != null) ask(ask) else reload() }
        }
    }

    /**
     * The current photo's record in collapsible sections (closed by default): the file,
     * its EXIF, and the shot log saved at capture (camera, exposure, white balance, each
     * frame, the develop's decisions, all settings).
     */
    private fun fillDetails() {
        val p = current() ?: return
        val info = shot
        val sections = LinkedHashMap<String, List<Pair<String, String>>>()
        // The recipe was written at save time; older photos only have it in the file name.
        val recipe = p.description.ifEmpty {
            Regex("""^FILM_\d{8}_\d{6}(?:_\d{3})?_(.+)\.jpg$""").find(p.name)?.groupValues?.get(1)?.replace('-', ' ') ?: "—"
        }
        sections["照片"] = listOf(
            "配方" to recipe,
            "拍摄时间" to DateFormat.getDateTimeInstance().format(Date(info?.takenMs ?: (p.taken * 1000))),
            "尺寸" to "${p.width} × ${p.height}（%.1f MP）".format(p.width * p.height / 1e6),
            "文件大小" to Formatter.formatShortFileSize(this, p.bytes),
            "文件名" to p.name,
            "位置" to Photos.location(p),
        )
        sections["EXIF"] = listOf(
            "曝光" to listOfNotNull(info?.fNumber?.let { "f/%.1f".format(it) }, info?.shutterText, info?.iso?.let { "ISO $it" })
                .joinToString(" · ").ifEmpty { "—" },
            "焦距" to listOfNotNull(info?.focalMm?.let { "%.1f mm".format(it) }, info?.focal35?.let { "等效 $it mm" })
                .joinToString(" · ").ifEmpty { "—" },
            "相机" to listOfNotNull(info?.make, info?.model).joinToString(" ").ifEmpty { "—" },
        )
        if (detailsFor == p.uri) return
        detailsFor = p.uri
        val log = shotLog
        if (log != null) for ((name, rows) in log.sections) sections[name] = rows.toList()
        else sections["拍摄档案"] = listOf("说明" to "这张照片没有拍摄档案（档案功能加入之前拍的）")

        detailText = buildString {
            for ((name, rows) in sections) {
                append("【").append(name).append("】\n")
                for ((k, v) in rows) append(k).append("：").append(v).append('\n')
                append('\n')
            }
        }.trimEnd()

        detailRows.removeAllViews()
        // the key facts, as tiles: what one looks for first
        val facts = listOf(
            "光圈" to (info?.fNumber?.let { "ƒ/%.1f".format(it) } ?: "—"),
            "快门" to (info?.shutterText ?: "—"),
            "ISO" to (info?.iso?.toString() ?: "—"),
            "焦距" to (info?.focal35?.let { "${it} mm" } ?: info?.focalMm?.let { "%.1f mm".format(it) } ?: "—"),
            "尺寸" to "%.1f MP".format(p.width * p.height / 1e6),
            "大小" to Formatter.formatShortFileSize(this, p.bytes),
        )
        for (row in facts.chunked(3)) detailRows.addView(LinearLayout(this).apply {
            row.forEachIndexed { i, (k, v) ->
                addView(LinearLayout(this@ViewerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Style.surface(this@ViewerActivity, fill = glassCard, radiusDp = 12f)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    addView(text(k, Style.TEXT_2).apply { textSize = 11f })
                    addView(text(v, Style.TEXT).apply {
                        textSize = 16f; typeface = Style.medium; maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END; Style.numeric(this)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        detailRows.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), 0, dp(4))
            addView(sectionTitle("拍摄档案 · ${sections.size} 类"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("复制全部") { copyDetails() }.apply {
                setPadding(dp(12), 0, dp(12), 0); setTextColor(Style.ACCENT)
                background = Style.surface(this@ViewerActivity, fill = glassCard, radiusDp = 14f)
            }, LinearLayout.LayoutParams(-2, dp(28)))
        })
        for ((name, rows) in sections) {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(14), dp(8))
                visibility = if (name in expanded) View.VISIBLE else View.GONE
            }
            // a section's rows are made when it is first opened (a shot log has hundreds)
            fun fill() {
                if (body.childCount > 0) return
                rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) body.addView(Style.hairline(this@ViewerActivity), LinearLayout.LayoutParams(-1, 1))
                    body.addView(LinearLayout(this@ViewerActivity).apply {
                        setPadding(0, dp(7), 0, dp(7))
                        addView(text(label, Style.TEXT_2).apply { textSize = 12.5f }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(text(value, Style.TEXT).apply {
                            gravity = Gravity.END; setTextIsSelectable(true); Style.numeric(this)
                        }, LinearLayout.LayoutParams(0, -2, 1.5f).apply { marginStart = dp(10) })
                    })
                }
            }
            if (name in expanded) fill()
            val chevron = android.widget.ImageView(this).apply {
                setImageDrawable(Icon(Icon.Kind.CHEVRON, Style.TEXT_2, 1.8f))
                rotation = if (name in expanded) 90f else 0f
            }
            val header = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                setPadding(dp(14), 0, dp(12), 0)
                addView(text(name, Style.TEXT).apply { typeface = Style.medium; textSize = 14f }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("${rows.size}", Style.TEXT_3).apply { textSize = 12f })
                addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) })
                setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    if (open) fill()
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    if (open) expanded += name else expanded -= name
                    chevron.animate().rotation(if (open) 90f else 0f).setDuration(180).start()
                }
            }
            detailRows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Style.surface(this@ViewerActivity, fill = glassCard, radiusDp = 14f)
                addView(header)
                addView(body)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun sectionTitle(value: String) = text(value, Style.TEXT_2).apply {
        textSize = 12f
        typeface = Style.medium
        letterSpacing = 0.04f
    }

    private fun copyDetails() {
        fillDetails()
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("拍摄档案", detailText))
        android.widget.Toast.makeText(this, "已复制拍摄档案", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun text(value: String, colour: Int) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(colour)
    }

    private fun share() {
        val photo = current() ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享照片"))
    }

    /** The border editor for this photo, numbered as in the contact sheet. */
    private fun openFrames() {
        val photo = current() ?: return
        startActivity(Intent(this, FrameActivity::class.java)
            .putExtra(FrameActivity.URI, photo.uri)
            .putExtra(FrameActivity.NAME, photo.name)
            .putExtra(FrameActivity.FRAME, photos.size - index)
            .putExtra(FrameActivity.FILM, Photos.filmOf(photo))
            .putExtra(FrameActivity.TAKEN, shot?.takenMs ?: (photo.taken * 1000))
            .putExtra(FrameActivity.PRIVATE, photo.file != null)
            .putExtra(FrameActivity.DESCRIPTION, photo.description))
    }

    private fun openWith() {
        val photo = current() ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_VIEW).setDataAndType(photo.uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "用其他应用打开"))
    }

    private fun trash() {
        val photo = current() ?: return
        DeleteConfirm.show(this, listOf(photo)) {
            loader.later {
                val ask = Photos.trash(this, listOf(photo))
                runOnUiThread { if (ask != null) ask(ask) else reload() }
            }
        }
    }

    // The system asks the user for photos this app may not change on its own.
    @Suppress("DEPRECATION")
    private fun ask(request: PendingIntent) = startIntentSenderForResult(request.intentSender, 1, null, 0, 0, 0)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        isSingleLine = true
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(0, 0, 0, 0)
        Style.textButton(this, 13f)
        setOnClickListener { onClick() }
    }

    private fun edgeText() = TextView(this).apply {
        textSize = 9f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        setTextColor(GalleryActivity.FILM_INK)
        letterSpacing = 0.08f
        maxLines = 1
    }

    private fun iconButton(kind: Icon.Kind, description: String, onClick: () -> Unit) = android.widget.ImageView(this).apply {
        contentDescription = description
        setImageDrawable(Icon(kind, Style.TEXT))
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = Style.surface(this@ViewerActivity, fill = Color.TRANSPARENT, radiusDp = 24f)
        setOnClickListener { onClick() }
    }

    /** An action: icon over a small label, in an equal-width cell. */
    private fun action(kind: Icon.Kind, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        contentDescription = label
        background = Style.surface(this@ViewerActivity, fill = Color.TRANSPARENT, radiusDp = 12f)
        Style.pressable(this)
        addView(android.widget.ImageView(this@ViewerActivity).apply { setImageDrawable(Icon(kind, Style.TEXT)) },
            LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(TextView(this@ViewerActivity).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Style.TEXT_2)
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(-2, -2)) // wrap, so the label centres under the icon
        setOnClickListener { onClick() }
    }

    companion object {
        // The record panel sits on a dark scrim over the photo in every theme: light text.
        private const val PANEL_TEXT = 0xfff3efe7.toInt()
        private const val PANEL_TEXT_2 = 0xffb4ada3.toInt()
        const val INDEX = "index"
        const val FAVORITES = "favorites"
        const val FILM = "film"
        const val PRIVATE = "private"
    }
}
