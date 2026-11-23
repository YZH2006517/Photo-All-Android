package org.photoall.film.settings

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.photoall.film.ui.Style

/**
 * 更新内容: what this version brought, as an upgrade notice (a banner with the version, then
 * the changes one card each, the new ones tagged). Shown once by itself the first time the
 * app opens after an install or an update; afterwards from the settings.
 */
class WhatsNewActivity : Activity() {
    private fun dp(v: Int) = Style.dp(this, v.toFloat())

    private class Change(val title: String, val text: String, val new: Boolean, val marks: List<String> = emptyList(),
        /** The author and the site under the words (the licence). */
        val link: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        // see-through (the manifest's theme): the page's own ground fades in over the screen under it
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        markSeen(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        list.addView(banner(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        for ((i, c) in CHANGES.withIndex()) list.addView(card(i + 1, c), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        list.addView(TextView(this).apply {
            text = "完整说明见 设置 › 使用指南"
            textSize = 12f; setTextColor(Style.TEXT_3); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })

        val go = TextView(this).apply {
            text = "开始使用"; textSize = 15f; gravity = Gravity.CENTER; typeface = Style.medium
            setTextColor(Style.ON_ACCENT)
            background = Style.surface(this@WhatsNewActivity, fill = Style.ACCENT, radiusDp = 23f)
            setOnClickListener { leave(list, this) }
        }
        goButton = go
        setContentView(LinearLayout(this).apply {
            root = this
            orientation = LinearLayout.VERTICAL
            background = ground
            addView(LinearLayout(this@WhatsNewActivity).apply {
                header = this
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(8), dp(6))
                addView(TextView(this@WhatsNewActivity).apply { text = "更新内容"; textSize = 20f; typeface = Style.medium; setTextColor(Style.TEXT) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@WhatsNewActivity).apply {
                    text = "完成"; textSize = 15f; setTextColor(Style.ACCENT)
                    setPadding(dp(14), dp(10), dp(14), dp(10)); Style.pressable(this)
                    setOnClickListener { leave(list, null) }
                })
            })
            addView(Style.hairline(this@WhatsNewActivity), LinearLayout.LayoutParams(-1, 1))
            addView(ScrollView(this@WhatsNewActivity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(FrameLayout(this@WhatsNewActivity).apply {
                setPadding(dp(16), dp(10), dp(16), dp(12))
                addView(go, FrameLayout.LayoutParams(-1, dp(46)))
            })
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        if (savedInstanceState == null) arrive(list, go) else ground.alpha = 255
    }

    private var root: View? = null
    private var header: View? = null
    private val ground = android.graphics.drawable.ColorDrawable(Style.BG).apply { alpha = 0 }
    private var goButton: View? = null
    private var leaving = false

    override fun onBackPressed() {
        val list = (root as? LinearLayout)?.let { r -> ((r.getChildAt(2) as? ScrollView)?.getChildAt(0)) as? LinearLayout }
        if (list != null) leave(list, null) else super.onBackPressed()
    }

    private val calm = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)

    /**
     * 浮出: the page's ground comes up over the screen it opened on, the header settles, the
     * banner rises and opens out, the cards follow one by one, the button last.
     */
    private fun arrive(list: LinearLayout, go: View) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { ground.alpha = 255; return }
        android.animation.ValueAnimator.ofInt(0, 255).apply {
            duration = 650
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { ground.alpha = it.animatedValue as Int }
            start()
        }
        header?.let { h ->
            h.alpha = 0f; h.translationY = -dp(10).toFloat()
            h.animate().alpha(1f).translationY(0f).setStartDelay(220).setDuration(700).setInterpolator(calm).start()
        }
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(if (i == 0) 64 else 52).toFloat()
            if (i == 0) { v.scaleX = 0.9f; v.scaleY = 0.9f }
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(if (i == 0) 260L else 420L + minOf(i - 1, 8) * 115L)
                .setDuration(if (i == 0) 1100 else 950)
                .setInterpolator(calm).start()
        }
        go.alpha = 0f
        go.translationY = dp(36).toFloat()
        go.animate().alpha(1f).translationY(0f).setStartDelay(1000).setDuration(900).setInterpolator(calm).start()
    }

    /**
     * 开始使用: the button gives under the finger and springs back as it goes, the cards float
     * up and away from the bottom one to the banner, then the ground fades and the screen
     * underneath (the camera, or the settings) is there again.
     */
    private fun leave(list: LinearLayout, button: View?) {
        if (leaving) return
        leaving = true
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { close(); return }
        val away = android.view.animation.PathInterpolator(0.45f, 0f, 0.75f, 0.35f)
        if (button != null) {
            button.animate().scaleX(0.9f).scaleY(0.9f).setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    button.animate().scaleX(1.06f).scaleY(1.06f).alpha(0f).translationY(-dp(12).toFloat())
                        .setDuration(520).setInterpolator(calm).start()
                }.start()
        } else goButton?.animate()?.alpha(0f)?.setDuration(420)?.start()
        val n = list.childCount
        for (i in 0 until n) {
            val v = list.getChildAt(i)
            v.animate().cancel()
            v.animate().alpha(0f).translationY(-dp(80).toFloat()).scaleX(if (i == 0) 0.96f else 1f).scaleY(if (i == 0) 0.96f else 1f)
                .setStartDelay(120L + minOf(n - 1 - i, 9) * 55L).setDuration(640)
                .setInterpolator(away).start()
        }
        header?.animate()?.alpha(0f)?.translationY(-dp(10).toFloat())?.setStartDelay(500)?.setDuration(500)?.start()
        android.animation.ValueAnimator.ofInt(ground.alpha, 0).apply {
            startDelay = 640
            duration = 620
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { ground.alpha = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { close() }
            })
            start()
        }
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    /** The banner: the accent deepening across it, the version large, three headline changes. */
    private fun banner(): View {
        val accent = Style.ACCENT
        val deep = Color.rgb((Color.red(accent) * 0.55f).toInt(), (Color.green(accent) * 0.55f).toInt(), (Color.blue(accent) * 0.55f).toInt())
        val ink = Style.ON_ACCENT
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, deep)).apply { cornerRadius = dp(20).toFloat() }
            elevation = dp(3).toFloat()
            addView(TextView(this@WhatsNewActivity).apply {
                text = "版本升级"
                textSize = 11.5f; typeface = Style.medium; letterSpacing = 0.25f
                setTextColor(ink); alpha = 0.85f
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), ink and 0x00ffffff or 0x99000000.toInt()) }
            }, LinearLayout.LayoutParams(-2, -2))
            addView(TextView(this@WhatsNewActivity).apply {
                text = "V$VERSION"
                textSize = 46f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                letterSpacing = 0.02f
                setTextColor(ink)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(TextView(this@WhatsNewActivity).apply {
                text = "离胶片，又近了一格"
                textSize = 16f; typeface = Style.medium; setTextColor(ink)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(LinearLayout(this@WhatsNewActivity).apply {
                for ((i, t) in listOf("调参台", "观片台", "更多 RAW").withIndex()) addView(TextView(this@WhatsNewActivity).apply {
                    text = t; textSize = 12.5f; typeface = Style.medium; setTextColor(ink)
                    setPadding(dp(11), dp(5), dp(11), dp(5))
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(ink and 0x00ffffff or 0x26000000) }
                }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
    }

    private fun card(n: Int, c: Change): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = Style.surface(this@WhatsNewActivity, fill = Style.SURFACE, radiusDp = 14f)
        addView(TextView(this@WhatsNewActivity).apply {
            text = "%02d".format(n)
            textSize = 20f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(Style.ACCENT)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(38), -2))
        addView(LinearLayout(this@WhatsNewActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@WhatsNewActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@WhatsNewActivity).apply { text = c.title; textSize = 16f; typeface = Style.medium; setTextColor(Style.TEXT) })
                if (c.new) addView(newTag(this@WhatsNewActivity), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            })
            addView(TextView(this@WhatsNewActivity).apply {
                text = marked(c.text, c.marks)
                textSize = 13.5f; setTextColor(Style.TEXT_2); setLineSpacing(0f, 1.35f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            if (c.link) {
                addView(TextView(this@WhatsNewActivity).apply {
                    text = "作者  $AUTHOR"
                    textSize = 13f; typeface = Style.medium; setTextColor(Style.TEXT)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
                addView(TextView(this@WhatsNewActivity).apply {
                    text = SITE
                    textSize = 13f; typeface = Style.medium; setTextColor(Style.ACCENT)
                    setPadding(0, dp(4), dp(12), dp(4))
                    Style.pressable(this)
                    setOnClickListener {
                        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://$SITE"))) }
                    }
                }, LinearLayout.LayoutParams(-2, -2))
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** The key words in the accent, a little heavier. */
    private fun marked(s: String, marks: List<String>): CharSequence {
        val b = SpannableStringBuilder(s)
        for (m in marks) {
            val at = s.indexOf(m)
            if (at < 0) continue
            b.setSpan(ForegroundColorSpan(Style.TEXT), at, at + m.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(StyleSpan(Typeface.BOLD), at, at + m.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    companion object {
        const val VERSION = "0.3.0"

        /** Opened with no window animation: the page makes its own entrance over the screen it opens on. */
        fun open(from: Activity) {
            from.startActivity(android.content.Intent(from, WhatsNewActivity::class.java),
                android.app.ActivityOptions.makeCustomAnimation(from, 0, 0).toBundle())
        }
        private const val PREFS = "whats_new"

        fun seen(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE).getString("seen", null) == VERSION
        fun markSeen(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("seen", VERSION).apply()

        /** The small NEW tag, in the accent. */
        fun newTag(context: Context) = TextView(context).apply {
            text = "NEW"
            textSize = 9.5f; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); letterSpacing = 0.08f
            setTextColor(Style.ON_ACCENT)
            val d = context.resources.displayMetrics.density
            setPadding((6 * d).toInt(), (1.5f * d).toInt(), (6 * d).toInt(), (1.5f * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 8 * d; setColor(Style.ACCENT) }
        }

        private const val AUTHOR = "YZH2006517"
        private const val SITE = "github.com/YZH2006517"

        private val CHANGES = listOf(
            Change("胶片引擎",
                "每道工序重新校准。白是相纸的白，黑得干净，中灰不动；反转片更浓，亮处有晕，二十种胶卷各有其粒。",
                false, listOf("白是相纸的白", "各有其粒")),
            Change("调参台",
                "看不懂参数，也能调出自己的胶片。原片与成片隔着一道线对望，参数每动一格，画面便应声而变。标准讲白话，Pro 见全貌；调好的一套可存为预设，在拍摄设置里随手切换。",
                true, listOf("应声而变")),
            Change("观片台",
                "把照片摊在灯箱上：台面随手摆，联排缓缓放，自选只留心头好，放大镜下看颗粒。看罢一键收回片盒；不要的清出台面，相册里依旧安在。底色、片边、镜片皆可自定，五种外观任选。",
                true, listOf("自选", "一键收回片盒", "五种外观")),
            Change("胶卷架",
                "每一卷都立在 3D 架上，附一页手记：它的色彩脾性，适合的光与景，拍与冲的诀窍。",
                true, listOf("一页手记")),
            Change("预设",
                "把顺手的胶卷、相纸与调子收成一套。长按「工具」，滑过去，松手即换。",
                true, listOf("松手即换")),
            Change("柔光镜与滤镜",
                "柔焦、黑柔、泛光重新调过，光从亮处轻轻晕开。新添 UV 与 IR 截止。",
                true, listOf("UV 与 IR 截止")),
            Change("导入冲洗",
                "导入的照片，按原图尺寸冲出。",
                false, listOf("原图尺寸")),
            Change("更多 RAW",
                "单反与微单的 RAW（NEF、CR3、ARW、RAF……）和三星 Expert RAW，都能送进暗房冲洗。选直出，便是相册里的那一张。",
                true, listOf("三星 Expert RAW")),
            Change("镜头与取景",
                "变焦尺随指拨动，焦段可自定；RAW 只认主摄，不以假乱真。旁轴目镜里，读数就在眼前，画面也更顺滑。",
                false, listOf("不以假乱真")),
            Change("日志",
                "出了问题，设置底部的「日志」复制一份寄给开发者。只有机型与出错记录，不带走一张照片。",
                true, listOf("寄给开发者")),
            Change("许可证",
                "以 CC BY-NC 4.0 发布：可自由使用、修改与分享，须署名，不得商用。",
                false, listOf("CC BY-NC 4.0"), link = true),
        )
    }
}
