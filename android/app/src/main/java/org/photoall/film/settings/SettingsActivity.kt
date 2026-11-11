package org.photoall.film.settings

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets

/**
 * The parameter list on its own screen. As an overlay on the camera it scrolled
 * over a live GL surface, recompositing every frame; here the camera pauses and
 * the list scrolls on its own. The camera re-reads the settings when it resumes.
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        org.photoall.film.ui.Style.apply(this)
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        // A new theme repaints this screen at once (the camera follows when it resumes).
        store.onChange { key -> if (key == Settings.THEME || key.isEmpty()) recreate() }
        val panel = SettingsPanel(this, store, onBench = null, onClose = { finish() },
            onGuide = { startActivity(android.content.Intent(this, GuideActivity::class.java)) },
            onDiagnostics = { showDiagnostics() })
        panel.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(panel)
    }

    /** Collected off the main thread, copied, and shown so the user sees what is sent. */
    private fun showDiagnostics() {
        Thread {
            val text = org.photoall.film.Diagnostics.collect(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Photo All 诊断信息", text))
                android.app.AlertDialog.Builder(this)
                    .setTitle("诊断信息已复制")
                    .setMessage("发给开发者即可（GitHub Issues，或小红书 / 抖音私信）。只含机型、相机能力与最近的提示，不含照片。")
                    .setView(android.widget.ScrollView(this).apply {
                        addView(android.widget.TextView(this@SettingsActivity).apply {
                            this.text = text; textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
                            setTextIsSelectable(true); setPadding(48, 16, 48, 16)
                        })
                    })
                    .setPositiveButton("好", null)
                    .show()
            }
        }.start()
    }
}
