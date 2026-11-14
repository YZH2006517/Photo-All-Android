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
 * One theme: the same named colours, different values. Photos, the viewfinder and the
 * histogram are never tinted by a theme; readouts over the viewfinder keep a dark [scrim].
 */
class Palette(
    val name: String,
    val bg: Int, val surface: Int, val surface2: Int, val hairline: Int,
    val text: Int, val text2: Int, val text3: Int,
    val accent: Int, val onAccent: Int, val scrim: Int,
    val light: Boolean = false,
)

/**
 * The app's one visual language: minimal and professional. A warm ground, hairlines,
 * no decoration; a single accent marks only what is on or selected. Numbers use
 * tabular figures so readouts do not jitter. The colours come from the chosen theme
 * ([apply] at each screen's start); a theme change recreates the screens.
 */
object Style {
    /** In the order of the settings' theme choice. Text pairs keep a contrast of 4.5 or more. */
    val palettes = listOf(
        Palette("暗盒", 0xff0b0b0a.toInt(), 0xff171615.toInt(), 0xff22201e.toInt(), 0xff2c2a27.toInt(),
            0xfff3efe7.toInt(), 0xffa8a29a.toInt(), 0xff5e5953.toInt(), 0xfff2b544.toInt(), 0xff0b0b0a.toInt(), 0xd90b0b0a.toInt()),
        // Night: only red light, so the eye stays dark-adapted.
        Palette("暗房红灯", 0xff000000.toInt(), 0xff140504.toInt(), 0xff230907.toInt(), 0xff3a0e0a.toInt(),
            0xffff6a55.toInt(), 0xffd9503c.toInt(), 0xff6a1e15.toInt(), 0xffff3b22.toInt(), 0xff000000.toInt(), 0xd9000000.toInt()),
        // Daylight: paper white and ink.
        Palette("相纸", 0xffefeae0.toInt(), 0xffe2dbcd.toInt(), 0xffd4cbb9.toInt(), 0xffc9c0ae.toInt(),
            0xff1b1a18.toInt(), 0xff5e574d.toInt(), 0xff9a9286.toInt(), 0xff9a360b.toInt(), 0xffffffff.toInt(), 0xd90b0b0a.toInt(),
            light = true),
        Palette("黄铜", 0xff161813.toInt(), 0xff20231c.toInt(), 0xff2b2f25.toInt(), 0xff363a2f.toInt(),
            0xffece6d6.toInt(), 0xffa9a390.toInt(), 0xff5f5c50.toInt(), 0xffc9a45c.toInt(), 0xff161813.toInt(), 0xd9101210.toInt()),
        Palette("冷银", 0xff0e1012.toInt(), 0xff171a1d.toInt(), 0xff21252a.toInt(), 0xff2b3036.toInt(),
            0xffeef1f4.toInt(), 0xff9aa3ad.toInt(), 0xff525a63.toInt(), 0xff8cc8e6.toInt(), 0xff0e1012.toInt(), 0xd90e1012.toInt()),
    )

    var palette: Palette = palettes[0]
        private set

    val BG get() = palette.bg
    val SURFACE get() = palette.surface        // buttons, panels
    val SURFACE_2 get() = palette.surface2     // selected / raised
    val HAIRLINE get() = palette.hairline
    val TEXT get() = palette.text
    val TEXT_2 get() = palette.text2           // secondary, inactive controls
    val TEXT_3 get() = palette.text3           // disabled
    val ACCENT get() = palette.accent          // on / selected only
    val ON_ACCENT get() = palette.onAccent     // text on an accent fill
    val SCRIM get() = palette.scrim            // panels over a photo: always dark

    /**
     * Before super.onCreate: the chosen theme's colours, the matching platform theme
     * (dialogs, switches) and window / system-bar colours.
     */
    fun apply(activity: android.app.Activity) {
        val index = org.photoall.film.settings.SettingsStore(activity)[org.photoall.film.settings.Settings.THEME].toInt()
        // The theme list runs on into the camera styles: past the themes, a style with its own colours.
        val store = org.photoall.film.settings.SettingsStore(activity)
        var theme = index
        val raw = activity.getSharedPreferences("film-settings", android.content.Context.MODE_PRIVATE)
        val oldSkin = raw.getFloat(org.photoall.film.settings.Settings.SKIN, 0f).toInt()
        if (oldSkin > 0) { // from the build that kept them apart
            theme = palettes.size + oldSkin - 1
            raw.edit().remove(org.photoall.film.settings.Settings.SKIN).apply()
            store[org.photoall.film.settings.Settings.THEME] = theme.toFloat()
        }
        palette = palettes[theme.coerceIn(0, palettes.lastIndex)]
        Skin.load(if (theme >= palettes.size) theme - palettes.size + 1 else 0)?.let { palette = it }
        activity.setTheme(if (palette.light) org.photoall.film.R.style.FilmThemeLight else org.photoall.film.R.style.FilmTheme)
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        activity.window.navigationBarColor = BG
        activity.window.statusBarColor = BG
        val appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.decorView.post {
            activity.window.insetsController?.setSystemBarsAppearance(if (palette.light) appearance else 0, appearance)
        }
    }

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(context: Context, v: Float) = (v * context.resources.displayMetrics.density).roundToInt()

    /** A rounded fill with a ripple in the text colour; [stroke] draws a hairline outline. */
    fun surface(context: Context, fill: Int = SURFACE, radiusDp: Float = 8f, stroke: Int = 0): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, dp(context, 0.75f)), stroke)
        }
        val mask = GradientDrawable().apply { cornerRadius = dp(context, radiusDp).toFloat(); setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(TEXT and 0x00ffffff or 0x33000000), shape, mask)
    }

    /**
     * Press feedback for anything tappable: it sinks a little under the finger and comes
     * back with a slight spring. Replaces the platform's elevation animator.
     */
    fun pressable(view: View) {
        val down = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.95f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.95f)).setDuration(90)
        val up = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)).setDuration(180).apply {
            interpolator = android.view.animation.OvershootInterpolator(2f)
        }
        view.stateListAnimator = android.animation.StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), up)
        }
    }

    /**
     * A text button that never cuts its text: no font padding, no vertical padding, centred,
     * one line; the caller's height then fits any system font size.
     */
    fun compact(b: TextView, horizontalDp: Float = 12f) {
        b.includeFontPadding = false
        b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
        val h = dp(b.context, horizontalDp)
        b.setPadding(h, 0, h, 0)
        b.gravity = android.view.Gravity.CENTER
        b.isSingleLine = true
        b.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** Readouts: tabular figures, so changing numbers keep their width. */
    fun numeric(view: TextView) {
        view.fontFeatureSettings = "tnum"
    }

    /** A control button: surface, medium weight, secondary text until it is on. */
    fun button(b: Button, sizeSp: Float = 13f) {
        b.includeFontPadding = false
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
        b.includeFontPadding = false
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

    /** The shutter, in the theme's metal and finish (see [ShutterFace]). */
    fun shutter(context: Context): Drawable = ShutterFace(context.resources.displayMetrics.density,
        ShutterFace.materials[palettes.indexOf(palette).coerceIn(0, ShutterFace.materials.lastIndex)])

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
