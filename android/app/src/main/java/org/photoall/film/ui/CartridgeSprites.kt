package org.photoall.film.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every cartridge the app shows is the three.js model (assets/cartridge3d), rendered off
 * screen into pictures: a hidden WebView in the current screen draws one on request
 * (its stock's label, its size, turned by some angle, with so much film out), the picture
 * is kept in memory and on disk, and views draw it. Until a picture is ready the flat
 * [Cartridge3D] drawing stands in, so nothing ever waits on it on screen.
 *
 * The pictures are framed alike: the cartridge's foot 93 % down, its top 13 % down, its
 * axis 36 % across, [ASPECT] wide for each unit high, transparent round it.
 */
object CartridgeSprites {
    const val ASPECT = 0.95f
    private const val AXIS = 0.36f
    private const val FOOT = 0.93f
    private const val SPAN = 0.8f
    private const val VERSION = 3 // a new model: new pictures
    private const val YAW_STEP = 0.0873f // 5 degrees

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var web: WebView? = null
    private var host: Activity? = null
    private var ready = false
    private var dir: File? = null
    private class Req(val key: String, val json: String, val px: Int)
    private val queue = ArrayDeque<Req>()
    private val queued = HashSet<String>()
    private var busy: Req? = null
    private val waiters = HashMap<String, MutableList<() -> Unit>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Puts the renderer in [activity]'s window (call from each screen that shows cartridges, on resume). */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(activity: Activity) {
        if (host === activity && web != null) return
        dir = File(activity.cacheDir, "cart3d").apply { mkdirs() }
        web?.let { old -> (old.parent as? ViewGroup)?.removeView(old); old.destroy() }
        ready = false
        busy?.let { queue.addFirst(it) }
        busy = null
        host = activity
        val w = WebView(activity)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = true
        w.addJavascriptInterface(Bridge(), "AndroidCart")
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d(TAG, "js: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        w.loadUrl("file:///android_asset/cartridge3d/index.html?mode=still")
        w.alpha = 0f
        (activity.window.decorView as ViewGroup).addView(w, FrameLayout.LayoutParams(1, 1))
        web = w
    }

    /** The screen that held the renderer is going: let it go with it. */
    fun detach(activity: Activity) {
        if (host !== activity) return
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null; host = null; ready = false
        busy?.let { queue.addFirst(it) }
        busy = null
    }

    private class Bridge {
        @JavascriptInterface fun ready() = main.post { CartridgeSprites.ready = true; pump() }.let { }
        @JavascriptInterface fun done(id: String, url: String) {
            io.execute {
                val bmp = runCatching {
                    val bytes = Base64.decode(url.substringAfter(','), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                main.post { landed(id, bmp) }
                if (bmp != null) dir?.let { d -> runCatching { File(d, file(id)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
            }
        }
    }

    private fun landed(key: String, bmp: Bitmap?) {
        android.util.Log.d(TAG, "landed $key ${if (bmp == null) "FAILED" else "${bmp.width}x${bmp.height}"} after ${android.os.SystemClock.uptimeMillis() - started} ms")
        if (busy?.key == key) busy = null
        queued.remove(key)
        if (bmp != null) cache.put(key, bmp)
        waiters.remove(key)?.forEach { it() }
        pump()
    }

    private fun pump() {
        val w = web ?: return
        if (!ready || busy != null) return
        val r = queue.removeFirstOrNull() ?: return
        busy = r
        started = android.os.SystemClock.uptimeMillis()
        android.util.Log.d(TAG, "render ${r.key} (${queue.size} waiting)")
        w.evaluateJavascript("renderStill(${JSONObject.quote(r.key)}, ${JSONObject.quote(r.json)})", null)
        // a render that never answers does not hold the queue
        main.postDelayed({ if (busy === r) landed(r.key, null) }, 6000L + r.px * 8L)
    }

    private var started = 0L
    private const val TAG = "CartSprites"

    private fun file(key: String) = "v$VERSION-" + Integer.toHexString(key.hashCode()) + "-" + key.length + ".png"

    private fun bucket(px: Float): Int {
        var b = 96
        while (b < px && b < 2048) b *= 2
        return b
    }

    private fun key(name: String, px: Int, yawStep: Int, leaderStep: Int) = "$name|$px|$yawStep|$leaderStep"

    /**
     * The picture of [name]'s cartridge, [px] high, turned [yaw] radians, [leader] mm of film
     * out; or the nearest turn already drawn while that one is made; or null, with [onReady]
     * called (on the main thread) when there is one.
     */
    fun get(name: String, px: Int, yaw: Float, leader: Float, onReady: (() -> Unit)?, urgent: Boolean = false): Bitmap? {
        val ys = (yaw / YAW_STEP).roundToInt()
        val ls = (leader / 2f).roundToInt()
        val k = key(name, px, ys, ls)
        cache.get(k)?.let { return it }
        request(k, name, px, ys, ls, onReady, urgent)
        // meanwhile the nearest turn of the same picture
        for (d in 1..8) for (s in intArrayOf(-1, 1)) cache.get(key(name, px, ys + s * d, ls))?.let { return it }
        return null
    }

    private fun request(k: String, name: String, px: Int, ys: Int, ls: Int, onReady: (() -> Unit)?, urgent: Boolean = false) {
        if (onReady != null) waiters.getOrPut(k) { mutableListOf() }.add(onReady)
        if (k in queued) {
            // already waiting its turn: an export moves it to the front
            if (urgent) queue.firstOrNull { it.key == k }?.let { r -> queue.remove(r); queue.addFirst(r) }
            return
        }
        queued += k
        val d = dir
        val f = d?.let { File(it, file(k)) }
        if (f != null && f.exists()) {
            io.execute {
                val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
                main.post {
                    if (bmp != null) { queued.remove(k); cache.put(k, bmp); waiters.remove(k)?.forEach { it() } }
                    else enqueue(k, name, px, ys, ls, urgent)
                }
            }
            return
        }
        enqueue(k, name, px, ys, ls, urgent)
    }

    private fun enqueue(k: String, name: String, px: Int, ys: Int, ls: Int, urgent: Boolean = false) {
        val look = FilmLabel.of(name)
        fun hex(c: Int) = String.format("%06x", c and 0xffffff)
        val json = JSONObject().apply {
            put("name", FilmLabel.pretty(name)); put("maker", look.maker); put("title", look.title); put("iso", look.iso)
            put("base", hex(look.base)); put("band", hex(look.band)); put("ink", hex(look.ink))
            put("h", px); put("yaw", ys * YAW_STEP.toDouble()); put("leader", ls * 2.0)
        }.toString()
        if (urgent) queue.addFirst(Req(k, json, px)) else queue.addLast(Req(k, json, px))
        pump()
    }

    /** Asks for the turns either side of rest ahead of time, so turning the phone finds them ready. */
    fun prefetch(name: String, h: Float, leader: Float, maxYaw: Float = 0.7f) {
        val px = bucket(h / SPAN)
        var y = 0f
        while (y <= maxYaw) {
            get(name, px, y, leader, null); get(name, px, -y, leader, null)
            y += YAW_STEP
        }
    }

    /**
     * Draws [name]'s cartridge standing on [base], [h] tall (knob to foot), its axis at [cx]:
     * the 3D picture with a soft shadow under it, or the flat drawing until the picture comes.
     * [leader] is film out in cartridge widths, as [Cartridge3D] takes it. Main thread.
     */
    fun draw(c: Canvas, cx: Float, base: Float, h: Float, name: String, yaw: Float = 0f, leader: Float = 0f,
             shadow: Float = 1f, onReady: () -> Unit): Boolean {
        val bmp = get(name, bucket(h / SPAN), yaw, leader * 25f, onReady)
        if (bmp == null) {
            Cartridge3D.draw(c, cx, base, h, name, yaw = yaw, leader = leader, shadow = shadow)
            return false
        }
        place(c, bmp, cx, base, h, shadow)
        return true
    }

    /**
     * Set on a thread drawing for the screen (a preview, a thumbnail): [drawWaiting] there does
     * not wait for the 3D picture; it draws what there is and this is called (main thread)
     * when the picture is ready, to draw again.
     */
    val onScreen = ThreadLocal<(() -> Unit)?>()

    /** As [draw], off the main thread (an export): waits for the picture, up to a few seconds. */
    fun drawWaiting(c: Canvas, cx: Float, base: Float, h: Float, name: String, yaw: Float = 0f, leader: Float = 0f, shadow: Float = 1f) {
        val px = bucket(h / SPAN)
        val mm = leader * 25f
        val exact = key(name, px, (yaw / YAW_STEP).roundToInt(), (mm / 2f).roundToInt())
        val again = onScreen.get()
        if (again != null && Looper.myLooper() != Looper.getMainLooper()) {
            val b = cache.get(exact)
            val ready = { if (cache.get(exact) != null) again() } // only once the picture is there: a failed render does not loop
            if (b == null) main.post { if (get(name, px, yaw, mm, ready) != null) again() }
            if (b == null) Cartridge3D.draw(c, cx, base, h, name, yaw = yaw, leader = leader, shadow = shadow)
            else place(c, b, cx, base, h, shadow)
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            main.post {
                val onDisk = dir?.let { File(it, file(exact)).exists() } == true
                when {
                    cache.get(exact) != null -> latch.countDown()
                    web == null && !onDisk -> latch.countDown() // nothing can draw it now: the flat one will do
                    else -> get(name, px, yaw, mm, { latch.countDown() }, urgent = true)
                }
            }
            val got = latch.await(8L + px / 128L, TimeUnit.SECONDS)
            android.util.Log.d(TAG, "export $exact: ${if (cache.get(exact) != null) "3D" else if (got) "flat (no picture)" else "flat (timed out)"}")
        }
        val b = cache.get(exact)
        if (b == null) Cartridge3D.draw(c, cx, base, h, name, yaw = yaw, leader = leader, shadow = shadow)
        else place(c, b, cx, base, h, shadow)
    }

    private fun place(c: Canvas, bmp: Bitmap, cx: Float, base: Float, h: Float, shadow: Float) {
        val imgH = h / SPAN
        val imgW = imgH * ASPECT
        val left = cx - AXIS * imgW
        val top = base - FOOT * imgH
        if (shadow > 0f) {
            // its shadow on what it stands on: tight under it, soft away from the lamp
            val r = h * 0.42f
            shade.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb((120 * shadow).toInt(), 0, 0, 0), Color.argb((40 * shadow).toInt(), 0, 0, 0), 0),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            shade.shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(r * 1.4f, h * 0.05f); postTranslate(cx + h * 0.12f, base) })
            c.drawRect(cx - r, base - h * 0.06f, cx + r * 2f, base + h * 0.06f, shade)
            shade.shader = null
        }
        c.drawBitmap(bmp, null, RectF(left, top, left + imgW, top + imgH), paint)
    }

    /** The picture's height in pixels for a cartridge [h] tall (for a caller that wants the picture itself). */
    fun pixels(h: Float) = bucket(h / SPAN)

    @Suppress("unused") private fun clamp(v: Float, a: Float, b: Float) = max(a, min(b, v))
}
