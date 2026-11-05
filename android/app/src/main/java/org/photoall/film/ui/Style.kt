package org.photoall.film.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The app's one visual language: minimal and professional. Black and greys, hairlines,
 * no decoration; a single accent (a restrained yellow) marks only what is on or
 * selected. Numbers use tabular figures so readouts do not jitter.
 */
object Style {
    const val BG = 0xff000000.toInt()
    const val SURFACE = 0xff121212.toInt()      // buttons, panels
    const val SURFACE_2 = 0xff1e1e1e.toInt()    // selected / raised
    const val HAIRLINE = 0xff2a2a2a.toInt()
    const val TEXT = 0xfff2f2f2.toInt()
    const val TEXT_2 = 0xff8e8e8e.toInt()       // secondary, inactive controls
    const val TEXT_3 = 0xff4e4e4e.toInt()       // disabled
    const val ACCENT = 0xfff2c14e.toInt()       // on / selected only
    const val SCRIM = 0xd9000000.toInt()        // panels over a photo

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).roundToInt()

    /** A rounded fill with a white ripple; [stroke] draws a hairline outline. */
    fun surface(context: Context, fill: Int = SURFACE, radiusDp: Float = 8f, stroke: Int = 0): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, dp(context, 0.75f)), stroke)
        }
        val mask = GradientDrawable().apply { cornerRadius = dp(context, radiusDp).toFloat(); setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape, mask)
    }

    /** Readouts: tabular figures, so changing numbers keep their width. */
    fun numeric(view: TextView) {
        view.fontFeatureSettings = "tnum"
    }

    /** A control button: surface, medium weight, secondary text until it is on. */
    fun button(b: Button, sizeSp: Float = 13f) {
        b.background = surface(b.context)
        b.setTextColor(TEXT_2)
        b.textSize = sizeSp
        b.typeface = medium
        b.isAllCaps = false
        b.stateListAnimator = null
        b.elevation = 0f
        numeric(b)
    }

    /** A plain text action (bars, dialogs): no fill. */
    fun textButton(b: Button, sizeSp: Float = 14f) {
        b.background = surface(b.context, fill = Color.TRANSPARENT)
        b.setTextColor(TEXT)
        b.textSize = sizeSp
        b.typeface = medium
        b.isAllCaps = false
        b.stateListAnimator = null
        b.elevation = 0f
    }

    /** On / off controls: the accent when on, secondary grey when off. */
    fun active(view: TextView, on: Boolean) {
        view.setTextColor(if (on) ACCENT else TEXT_2)
    }

    /** One of a set (a lens): raised surface and accent text when chosen. */
    fun selected(b: Button, chosen: Boolean) {
        b.background = surface(b.context, fill = if (chosen) SURFACE_2 else Color.TRANSPARENT, radiusDp = 18f)
        b.setTextColor(if (chosen) ACCENT else TEXT_2)
    }

    /** Text that only applies when something is on: full when it does, dim when not. */
    fun enabled(view: TextView, on: Boolean, normal: Int = TEXT) {
        view.setTextColor(if (on) normal else TEXT_3)
    }

    /** A section title: small, spaced, grey. */
    fun sectionTitle(view: TextView) {
        view.textSize = 11f
        view.letterSpacing = 0.12f
        view.typeface = medium
        view.setTextColor(TEXT_2)
    }

    fun hairline(context: Context) = View(context).apply { setBackgroundColor(HAIRLINE) }

    /** The shutter: a white ring around a white disc, a thin black gap between. */
    fun shutter(context: Context): Drawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(context, 3f), TEXT)
        }
        val disc = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TEXT) }
        return LayerDrawable(arrayOf(ring, disc)).apply {
            val inset = dp(context, 7f)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    fun slider(seek: SeekBar) {
        seek.progressTintList = ColorStateList.valueOf(TEXT)
        seek.progressBackgroundTintList = ColorStateList.valueOf(HAIRLINE)
        seek.thumbTintList = ColorStateList.valueOf(TEXT)
    }

    fun switch(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(TEXT, TEXT_2))
        sw.trackTintList = ColorStateList(states, intArrayOf(ACCENT, HAIRLINE))
    }
}
