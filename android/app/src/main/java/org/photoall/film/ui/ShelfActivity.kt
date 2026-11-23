package org.photoall.film.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import org.photoall.film.engine.FilmEngine

/**
 * The film shelf (assets/cartridge3d/shelf.html): every film stock as its 3D cartridge on the
 * upper board, every paper as its box (the motion-picture print films as cans) on the lower one.
 * Drag along to look; a cartridge opens in the 3D viewer, a box lifts its lid.
 */
class ShelfActivity : Activity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        val items = runCatching { describe() }.getOrElse { JSONObject().toString() }
        val view = WebView(this).apply {
            setBackgroundColor(Color.rgb(20, 18, 16))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(object {
                @JavascriptInterface fun items(): String = items
                @JavascriptInterface fun inspect(name: String) = runOnUiThread {
                    startActivity(Intent(this@ShelfActivity, Cartridge3DActivity::class.java).putExtra(Cartridge3DActivity.EXTRA_FILM, name))
                }
                @JavascriptInterface fun inspectPaper(name: String) = runOnUiThread {
                    startActivity(Intent(this@ShelfActivity, Cartridge3DActivity::class.java).putExtra(Cartridge3DActivity.EXTRA_PAPER, name))
                }
            }, "AndroidShelf")
            loadUrl("file:///android_asset/cartridge3d/shelf.html")
        }
        web = view
        val d = resources.displayMetrics.density
        val close = ImageView(this).apply {
            setImageDrawable(Icon(Icon.Kind.BACK, 0xffece6da.toInt()))
            contentDescription = "返回"
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setOnClickListener { finish() }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(20, 18, 16))
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(close, FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), android.view.Gravity.START or android.view.Gravity.TOP).apply {
                leftMargin = (8 * d).toInt(); topMargin = (8 * d).toInt()
            })
        })
        window.decorView.post {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** The stocks and papers, with what their labels look like. */
    private fun describe(): String {
        fun hex(c: Int) = String.format("%06x", c and 0xffffff)
        val films = JSONArray()
        for (name in FilmEngine.filmStocks.distinctBy { FilmLabel.pretty(it).lowercase() }) {
            val look = FilmLabel.of(name)
            films.put(JSONObject().apply {
                put("id", name); put("name", FilmLabel.pretty(name)); put("maker", look.maker); put("title", look.title)
                put("iso", look.iso); put("base", hex(look.base)); put("band", hex(look.band)); put("ink", hex(look.ink))
            })
        }
        val papers = JSONArray()
        for (name in FilmEngine.papers) papers.put(JSONObject().apply { put("id", name) })
        return JSONObject().put("films", films).put("papers", papers).toString()
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
