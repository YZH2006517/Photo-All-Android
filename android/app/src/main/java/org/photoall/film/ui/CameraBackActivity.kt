package org.photoall.film.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Loading a film into the camera, in 3D (assets/cartridge3d/back.html): the camera's back
 * open, seen from above in landscape; the cartridge dragged into its chamber, the leader
 * pulled to the take-up spool, the film wound on twice, the back closed. Finishes with
 * RESULT_OK when the film is in, RESULT_CANCELED when skipped.
 */
class CameraBackActivity : Activity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_FILM) ?: "kodak portra 400"
        val look = FilmLabel.of(name)
        fun hex(c: Int) = String.format("%06x", c and 0xffffff)
        val url = Uri.parse("file:///android_asset/cartridge3d/back.html").buildUpon()
            .appendQueryParameter("name", FilmLabel.pretty(name))
            .appendQueryParameter("maker", look.maker).appendQueryParameter("title", look.title)
            .appendQueryParameter("iso", look.iso)
            .appendQueryParameter("base", hex(look.base)).appendQueryParameter("band", hex(look.band))
            .appendQueryParameter("ink", hex(look.ink))
            .build().toString()
        val view = WebView(this).apply {
            setBackgroundColor(Color.rgb(14, 13, 12))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(object {
                @JavascriptInterface fun done() = runOnUiThread { finishWith(RESULT_OK) }
                @JavascriptInterface fun skip() = runOnUiThread { finishWith(RESULT_CANCELED) }
            }, "AndroidBack")
            loadUrl(url)
        }
        web = view
        setContentView(view)
        window.statusBarColor = Color.rgb(14, 13, 12)
        window.navigationBarColor = Color.rgb(14, 13, 12)
        // the whole screen for the camera: the bars only on a swipe
        window.decorView.post {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun finishWith(code: Int) {
        if (isFinishing) return
        setResult(code, Intent().putExtra(EXTRA_INDEX, intent.getIntExtra(EXTRA_INDEX, -1)))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }

    companion object {
        const val EXTRA_FILM = "film"
        const val EXTRA_INDEX = "index"
    }
}
