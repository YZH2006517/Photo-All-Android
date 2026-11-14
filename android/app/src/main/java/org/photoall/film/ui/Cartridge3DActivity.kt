package org.photoall.film.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * A film's cartridge as a solid to hold: the three.js model in assets/cartridge3d, turned
 * all the way round with a finger, brought nearer with two, its film drawn out with a
 * double tap. The stock's colours and words come in the address, so every film has its
 * own printed shell.
 */
class Cartridge3DActivity : Activity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_FILM) ?: "kodak portra 400"
        val look = FilmLabel.of(name)
        fun hex(c: Int) = String.format("%06x", c and 0xffffff)
        val url = Uri.parse("file:///android_asset/cartridge3d/index.html").buildUpon()
            .appendQueryParameter("name", FilmLabel.pretty(name))
            .appendQueryParameter("maker", look.maker)
            .appendQueryParameter("title", look.title)
            .appendQueryParameter("iso", look.iso)
            .appendQueryParameter("base", hex(look.base))
            .appendQueryParameter("band", hex(look.band))
            .appendQueryParameter("ink", hex(look.ink))
            .build().toString()
        val view = WebView(this).apply {
            setBackgroundColor(Color.rgb(20, 19, 17))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = true
            overScrollMode = View.OVER_SCROLL_NEVER
            loadUrl(url)
        }
        web = view
        val close = ImageView(this).apply {
            setImageDrawable(Icon(Icon.Kind.BACK, 0xffece6da.toInt()))
            contentDescription = "返回"
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setOnClickListener { finish() }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(20, 19, 17))
            addView(view, FrameLayout.LayoutParams(-1, -1))
            val d = resources.displayMetrics.density
            addView(close, FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), android.view.Gravity.END or android.view.Gravity.TOP))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                (close.layoutParams as FrameLayout.LayoutParams).setMargins(0, bars.top + (8 * d).toInt(), bars.right + (8 * d).toInt(), 0)
                close.requestLayout()
                insets
            }
        }
        setContentView(root)
        window.statusBarColor = Color.rgb(20, 19, 17)
        window.navigationBarColor = Color.rgb(20, 19, 17)
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }

    companion object {
        const val EXTRA_FILM = "film"
    }
}
