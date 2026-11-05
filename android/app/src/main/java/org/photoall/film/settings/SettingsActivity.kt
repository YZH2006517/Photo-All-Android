package org.photoall.film.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import org.photoall.film.MainActivity

/**
 * The parameter list on its own screen. As an overlay on the camera it scrolled
 * over a live GL surface, recompositing every frame; here the camera pauses and
 * the list scrolls on its own. The camera re-reads the settings when it resumes.
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = SettingsPanel(this, SettingsStore(this),
            onBench = { startActivity(Intent(this, MainActivity::class.java)) },
            onClose = { finish() })
        panel.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(panel)
    }
}
