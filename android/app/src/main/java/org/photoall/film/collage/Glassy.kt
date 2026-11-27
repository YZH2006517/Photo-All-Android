package org.photoall.film.collage

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.film.ui.Style

/**
 * 拼贴相机's look, Apple's: the screen's ground is the latest photo blurred to colour (the
 * same ground as the default theme, whatever theme the film camera wears), panels of frosted
 * glass with a rim of light, large round corners, white type in a heavy and a light weight,
 * and every tap answered with a small spring.
 */
object Glassy {
    const val TEXT = 0xfff5f5f7.toInt()
    const val TEXT_2 = 0x99ebebf5.toInt()
    const val TEXT_3 = 0x4debebf5
    const val YELLOW = 0xffffd60a.toInt()
    const val RED = 0xffff453a.toInt()

    val bold: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val regular: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)

    fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).toInt()

    /** Before super.onCreate: the app's theme for dialogs, then the photo ground and clear bars. */
    fun apply(a: Activity) {
        Style.apply(a, darkGlass = true)
        Style.loadAmbient(a)
        a.window.setBackgroundDrawable(Style.Ambient(dark = true))
        a.window.statusBarColor = Color.TRANSPARENT
        a.window.navigationBarColor = Color.TRANSPARENT
        a.window.decorView.post { a.window.insetsController?.setSystemBarsAppearance(0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) }
    }

    /** The content root: pads itself clear of the system bars. */
    fun root(a: Activity, content: View): View = FrameLayout(a).apply {
        addView(content, FrameLayout.LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    /** Frosted glass: [tint] over the ground, a rim of light, [radius] dp corners (≥ 999 a pill). */
    fun glass(c: Context, radius: Float = 22f, tint: Int = 0x1fffffff, rim: Boolean = true) =
        Style.Glass(c.resources.displayMetrics.density, tint, radius * c.resources.displayMetrics.density, rim)

    fun text(c: Context, s: String, size: Float, color: Int = TEXT, face: Typeface = regular) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); typeface = face; includeFontPadding = false
    }

    /** A capsule button of glass with a word (and a mark before it), springing under the finger. */
    fun pill(c: Context, label: String, mark: String? = null, strong: Boolean = false, onClick: () -> Unit) = TextView(c).apply {
        text = if (mark != null) "$mark  $label" else label
        textSize = 15f
        typeface = semibold
        gravity = Gravity.CENTER
        setTextColor(if (strong) 0xff1c1c1e.toInt() else TEXT)
        background = if (strong) GradientDrawable().apply { setColor(TEXT); cornerRadius = 999f } else glass(c, 999f)
        setPadding(dp(c, 18f), 0, dp(c, 18f), 0)
        Style.pressable(this)
        setOnClickListener { onClick() }
    }

    /** A round glass key holding a mark (a short word or symbol). */
    fun roundKey(c: Context, mark: String, size: Float = 17f, onClick: () -> Unit) = TextView(c).apply {
        text = mark; textSize = size; gravity = Gravity.CENTER; setTextColor(TEXT); typeface = semibold
        background = glass(c, 999f)
        Style.pressable(this)
        setOnClickListener { onClick() }
    }

    /** The top of a screen: a back key, a centred title, an action on the right. */
    fun header(a: Activity, title: String, action: View? = null): View = FrameLayout(a).apply {
        setPadding(dp(a, 16f), dp(a, 6f), dp(a, 16f), dp(a, 6f))
        addView(roundKey(a, "‹", 24f) { a.finish() }, FrameLayout.LayoutParams(dp(a, 40f), dp(a, 40f), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(text(a, title, 17f, TEXT, semibold), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        if (action != null) addView(action, FrameLayout.LayoutParams(-2, dp(a, 36f), Gravity.END or Gravity.CENTER_VERTICAL))
    }

    /** Arrives: rises a little and fades in, after [delay] ms (a list of these in turn). */
    fun rise(v: View, delay: Long) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        v.alpha = 0f; v.translationY = 28f * v.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(620)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)).start()
    }

    fun vertical(c: Context, build: LinearLayout.() -> Unit) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; build() }
    fun row(c: Context, build: LinearLayout.() -> Unit) = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; build() }

    fun lp(w: Int, h: Int, weight: Float = 0f, build: LinearLayout.LayoutParams.() -> Unit = {}) =
        LinearLayout.LayoutParams(w, h, weight).apply(build)

    fun ViewGroup.space(h: Int) = addView(View(context), LinearLayout.LayoutParams(1, h))
}
