package org.photoall.film.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The tools page (opened from StudioEntry), in words only: the tools by name (presets, the light table, the
 * film shelf), the Photo All series with a line each, and who made it. Colours from the theme in use.
 */
object StudioPanel {
    fun build(context: Context, version: String, onPresets: () -> Unit, onEngine: () -> Unit, onShutterSound: () -> Unit, onFocal: () -> Unit, onLightbox: () -> Unit, onShelf: () -> Unit, open: (String) -> Unit): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        fun text(s: String, size: Float, color: Int, face: Typeface? = null) = TextView(context).apply {
            text = s; textSize = size; setTextColor(color); face?.let { typeface = it }
        }
        fun card(fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = 18 * d; setStroke(dp(1f), Style.HAIRLINE) }
        fun section(title: String) = text(title, 15f, Style.TEXT, bold).apply { setPadding(0, dp(24f), 0, dp(10f)) }
        fun row(title: String, detail: String? = null, strong: Boolean = false, onClick: () -> Unit) = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = card(if (strong) Style.SURFACE_2 else Style.SURFACE)
            Style.pressable(this)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 17f, Style.TEXT, bold))
                detail?.let { addView(text(it, 13f, Style.TEXT_2).apply { setPadding(0, dp(3f), 0, 0) }) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("›", 20f, Style.TEXT_3))
            setOnClickListener { onClick() }
        }
        val gap = { LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) } }

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(2f), dp(18f), dp(22f)) }
        root.addView(text("工具", 30f, Style.TEXT, serif).apply { setPadding(0, dp(6f), 0, dp(4f)) })

        root.addView(section("功能"))
        root.addView(row("调参台", "用自己的照片调胶片：颗粒、反差、色彩、光晕…边调边看", strong = true, onClick = onEngine), gap())
        root.addView(row("预设", onClick = onPresets), gap())
        root.addView(row("快门声", onClick = onShutterSound), gap())
        root.addView(row("焦段", onClick = onFocal), gap())
        root.addView(row("观片台", onClick = onLightbox), gap())
        root.addView(row("胶卷架", onClick = onShelf), gap())

        root.addView(section("Photo All 系列"))
        for ((name, detail, repo) in listOf(
            Triple("电脑版", "Windows 上的观片台与暗房", "Photo-All"),
            Triple("安卓版 · 当前", "手机拍摄，取景即胶片", "Photo-All-Android"),
            Triple("相机版", "单反、微单拍完传到手机", "Photo-All-Tether"))) {
            root.addView(row(name, detail, strong = repo == "Photo-All-Android") { open("https://github.com/YZH2006517/$repo") }, gap())
        }

        root.addView(section("关于"))
        root.addView(row("YZH2006517") { open("https://github.com/YZH2006517") })
        return root
    }
}
