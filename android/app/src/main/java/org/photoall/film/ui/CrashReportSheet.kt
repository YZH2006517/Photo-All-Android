package org.photoall.film.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.photoall.film.CrashReport
import kotlin.math.roundToInt

/**
 * The report of what went wrong last time: a short word of what to do with it, the
 * report itself condensed onto one card (one or two screenshots), and one button that
 * copies all of it.
 */
object CrashReportSheet {
    private const val TEXT_SP = 10.5f

    fun show(activity: Activity, report: CrashReport.Report, onCopied: () -> Unit, onClosed: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(activity, report.title, body)

        body.addView(TextView(activity).apply {
            text = (if (report.fatal) "抱歉。" else "应用已自动恢复，但这些错误值得修好。") +
                "复制这份报告发给开发者（GitHub Issues，或小红书 / 抖音私信），能帮我们在这台手机上修好它。只含机型、系统与出错位置，不含照片。"
            textSize = 14f
            setLineSpacing(0f, 1.25f)
            setTextColor(Style.TEXT_2)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        if (report.safeMode) body.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Style.surface(activity, fill = Style.SURFACE, radiusDp = 12f)
            addView(View(activity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Style.ACCENT) }
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(10) })
            addView(TextView(activity).apply {
                text = "连续意外退出两次，已换成安全设置：关闭 HDR 与闪光，冲洗尺寸 3072。可在参数里改回。"
                textSize = 13f
                setLineSpacing(0f, 1.2f)
                setTextColor(Style.TEXT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // the report on one card, condensed so one or two screenshots hold all of it (the
        // copy is the full report)
        val padding = dp(12)
        body.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(10), padding, padding)
            background = Style.surface(activity, fill = Style.SURFACE, radiusDp = 14f)
            addView(TextView(activity).apply {
                text = "报告（精简显示，复制的是完整版）"
                textSize = 11f
                typeface = Style.medium
                setTextColor(Style.TEXT_3)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            addView(TextView(activity).apply {
                text = condense(report.text)
                textSize = TEXT_SP
                typeface = Typeface.MONOSPACE
                setTextColor(Style.TEXT)
                setLineSpacing(0f, 1.08f)
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2))

        fun action(text: String, fill: Int, ink: Int, stroke: Int, onClick: (Button) -> Unit) = Button(activity).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            typeface = Style.medium
            stateListAnimator = null
            Style.compact(this, 12f)
            background = Style.surface(activity, fill = fill, radiusDp = 14f, stroke = stroke)
            setTextColor(ink)
            Style.pressable(this)
            setOnClickListener { onClick(this) }
        }
        body.addView(LinearLayout(activity).apply {
            setPadding(0, dp(14), 0, dp(4))
            addView(action("忽略", Color.TRANSPARENT, Style.TEXT, Style.HAIRLINE) { sheet.dismiss() },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
            addView(action("一键复制报告", Style.ACCENT, Style.ON_ACCENT, 0) { button ->
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Photo All 问题报告", report.text))
                button.text = "已复制 ✓"
                button.postDelayed({ button.text = "一键复制报告" }, 1600)
                onCopied()
            }, LinearLayout.LayoutParams(0, dp(50), 1.4f).apply { marginStart = dp(6) })
        })

        sheet.setOnDismissListener { onClosed() }
        sheet.show()
    }

    /**
     * The report for the eye: every line but the stack as it is; of each stack, the app's
     * own frames and the first two of each exception, written short (Class.method:line),
     * the runs of system frames between them counted instead of listed.
     */
    private fun condense(text: String): String {
        val frame = Regex("""^\s*at ([\w.$]+)\.([\w$<>-]+)\(([^)]*)\)\s*$""")
        val out = StringBuilder()
        var sinceHeader = 99
        var skipped = 0
        fun flush() {
            if (skipped > 0) out.append("   … 系统调用 ").append(skipped).append(" 行\n")
            skipped = 0
        }
        for (line in text.trimEnd().lines()) {
            val m = frame.find(line)
            if (m != null) {
                sinceHeader++
                val (cls, method, where) = m.destructured
                val own = cls.startsWith("org.photoall")
                if (own || sinceHeader <= 2) {
                    flush()
                    val name = cls.substringAfterLast('.')
                    val at = where.substringAfterLast(':', "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    out.append(if (own) " ▸ " else "   ").append(name).append('.').append(method)
                        .append(if (at != null) ":$at" else "").append('\n')
                } else skipped++
                continue
            }
            if (line.trim().matches(Regex("""\.\.\. \d+ more"""))) { skipped++; continue }
            flush()
            sinceHeader = 0
            out.append(line).append('\n')
        }
        flush()
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trimEnd()
    }

}
