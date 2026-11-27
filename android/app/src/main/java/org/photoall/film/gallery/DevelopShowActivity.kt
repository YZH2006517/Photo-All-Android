package org.photoall.film.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import org.photoall.film.ui.Style

/**
 * 冲洗过程动画 full screen, for an import (a shot plays it over the viewfinder instead): a
 * [DevelopShowView] filling the screen; its extras say what to show.
 */
class DevelopShowActivity : Activity() {

    private var show: DevelopShowView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        val view = DevelopShowView(this, intent) { if (!isFinishing) finish() }
        show = view
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(view)
    }

    override fun onDestroy() {
        show?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "uri"            // a picture or camera RAW in the album
        const val EXTRA_AS_RAW = "as_raw"      // with EXTRA_URI: try it as a RAW first
        const val EXTRA_DNG = "dng"            // a DNG file (the camera's shot)
        const val EXTRA_DELETE = "delete"      // the DNG file is the show's own copy
        const val EXTRA_KEYS = "keys"
        const val EXTRA_VALUES = "values"
        const val EXTRA_LUT = "lut"
        const val EXTRA_LUT_STRENGTH = "lut_strength"
        const val EXTRA_EV = "ev"
        const val EXTRA_METER = "meter"

        /** What one develop shows: [params] the engine's, the LUT and exposure as the develop has them. */
        fun intent(context: Context, params: Map<String, Float>, lut: Int, lutStrength: Float, ev: Float, meter: Boolean) =
            Intent(context, DevelopShowActivity::class.java)
                .putExtra(EXTRA_KEYS, params.keys.toTypedArray())
                .putExtra(EXTRA_VALUES, params.values.toFloatArray())
                .putExtra(EXTRA_LUT, lut)
                .putExtra(EXTRA_LUT_STRENGTH, lutStrength)
                .putExtra(EXTRA_EV, ev)
                .putExtra(EXTRA_METER, meter)
    }
}
