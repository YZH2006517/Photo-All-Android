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
            onGuide = { startActivity(android.content.Intent(this, GuideActivity::class.java)) })
        panel.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(panel)
    }
}
