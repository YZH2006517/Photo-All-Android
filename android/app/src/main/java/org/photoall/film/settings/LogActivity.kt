package org.photoall.film.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.photoall.film.Diagnostics
import org.photoall.film.ui.Style

/**
 * 日志 (the last entry of the settings): this phone's diagnostics and the errors the app
 * handled lately, shown in full so the user sees exactly what they pass on, with one button
 * to copy it and, when the build has the report address, one to send it to the developer.
 * No photos and no account data are in it.
 */
class LogActivity : Activity() {
    private fun dp(v: Int) = Style.dp(this, v.toFloat())
    private var log = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        val body = TextView(this).apply {
            text = "正在收集…"
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Style.TEXT_2)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Style.surface(this@LogActivity, fill = Style.SURFACE, radiusDp = 14f)
        }
        val status = TextView(this).apply {
            textSize = 12.5f; setTextColor(Style.TEXT_2); gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), 0); visibility = View.GONE
        }
        fun button(label: String, primary: Boolean, onClick: (TextView) -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER; typeface = Style.medium
            setTextColor(if (primary) Style.ON_ACCENT else Style.TEXT)
            background = if (primary) Style.surface(this@LogActivity, fill = Style.ACCENT, radiusDp = 22f)
                else Style.surface(this@LogActivity, fill = Style.SURFACE, radiusDp = 22f, stroke = Style.HAIRLINE)
            Style.pressable(this)
            setOnClickListener { onClick(this) }
        }
        val copy = button("复制日志", true) {
            if (log.isEmpty()) return@button
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Photo All 日志", log))
            status.text = "已复制。可以发到 GitHub Issues，或小红书 / 抖音私信作者"
            status.visibility = View.VISIBLE
        }

        val send = if (!org.photoall.film.ReportSender.enabled) null else button("发送给开发者", false) { b ->
            if (log.isEmpty() || b.tag == "sent") return@button
            b.isEnabled = false
            b.text = "发送中…"
            Thread {
                val result = org.photoall.film.ReportSender.send("日志", log, false)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    b.isEnabled = true
                    status.visibility = View.VISIBLE
                    result.onSuccess { id ->
                        b.tag = "sent"
                        b.text = "已发送 ✓"
                        status.text = "报告编号 $id。和开发者联系时报上这个编号就能找到它，谢谢。"
                    }.onFailure {
                        b.text = "再试一次"
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Photo All 日志", log))
                        status.text = "没发出去（${it.message ?: "网络不通"}），日志已复制，可以粘贴给开发者。"
                    }
                }
            }.start()
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(6))
            addView(TextView(this@LogActivity).apply { text = "日志"; textSize = 20f; typeface = Style.medium; setTextColor(Style.TEXT) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@LogActivity).apply {
                text = "完成"; textSize = 15f; setTextColor(Style.ACCENT)
                setPadding(dp(14), dp(10), dp(14), dp(10)); Style.pressable(this)
                setOnClickListener { finish() }
            })
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
            addView(TextView(this@LogActivity).apply {
                text = "遇到问题时，把这份日志发送或复制给开发者：里面只有机型、系统、相机能力、拍摄设置与最近的出错记录，不含任何照片。下面就是会发出去的全部内容。"
                textSize = 13f; setTextColor(Style.TEXT_2); setLineSpacing(0f, 1.3f)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            addView(status)
            addView(LinearLayout(this@LogActivity).apply {
                send?.let { addView(it, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) }) }
                addView(copy, LinearLayout.LayoutParams(0, dp(46), 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = org.photoall.film.ui.Style.ground()
            addView(header)
            addView(Style.hairline(this@LogActivity), LinearLayout.LayoutParams(-1, 1))
            addView(ScrollView(this@LogActivity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(Style.hairline(this@LogActivity), LinearLayout.LayoutParams(-1, 1))
            addView(actions)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        // collected off the main thread (it asks every camera for its capabilities)
        Thread {
            val text = runCatching { Diagnostics.collect(this) }.getOrElse { "收集失败：${it.message}" }
            runOnUiThread { if (!isFinishing) { log = text; body.text = text } }
        }.start()
    }
}
