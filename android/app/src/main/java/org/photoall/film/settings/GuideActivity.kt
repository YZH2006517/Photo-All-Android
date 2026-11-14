package org.photoall.film.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.photoall.film.ui.Style

/**
 * The full guide, the first entry of the settings: every control in turn, in plain words,
 * then a closing note, the project's page and where updates are announced. The short
 * tour on the camera points here.
 */
class GuideActivity : Activity() {

    private fun dp(v: Int) = Style.dp(this, v.toFloat())

    private class Part(val title: String, val items: List<Pair<String, String>>)

    private val parts = listOf(
        Part("取景与拍摄", listOf(
            "取景框" to "轻点对焦，双击锁定对焦，双指开合变焦。下沿的圆钮是各颗镜头的等效焦距，右下角切换前后镜头。",
            "快门" to "按下即拍。照片在后台冲洗，数秒后出现在左下角；冲洗期间可以接着拍。",
            "片数" to "顶栏的「34 / 36」是这一卷还剩几张。一卷拍完，自动换上新的一卷，相册里按卷编号。",
        )),
        Part("胶卷、相纸与风格", listOf(
            "一张卡片，三页" to "快门上方的卡片分三页：胶卷、相纸、LUT 风格。左右轻拨换页，轻点打开这一页的详细面板。",
            "长按挑选" to "长按卡片，片盒在取景框里一字排开，左右滑动挑选，画面实时换色；上方一排分组，点一下即可跳转。轻点画面收起。",
            "胶卷" to "负片温润宽容，反转片浓烈锐利，黑白克制，电影卷长于叙事。选「直出」则不经胶片。",
            "相纸" to "同一张底片，印在不同相纸上，冷暖与反差各异。选「直接扫描」则跳过印放，看底片本来的样子。",
            "LUT 风格" to "四百余种色彩查找表，分为卡片机、复古相机、电影、黑白等类别。也可以导入自己的 .cube 文件，并标明它对应的相机 Log 曲线。",
            "画圈调强度" to "在风格页挑好一种后，快门会化作旋钮：手指绕着它画圈，顺时针渐浓，逆时针渐淡，画面边转边变。轻点仍是快门。",
        )),
        Part("曝光", listOf(
            "曝光刻度" to "卡片下方的刻度，零点居中，每格三分之一档；拨向正数更亮，拨向负数更暗，双击归零。",
            "手动曝光" to "长按「曝光」键，切换到手动：点 ISO 或快门，再拖动刻度调整。再长按一次回到自动。",
            "按胶片测光" to "在「拍摄设置 · 取景辅助」里打开「胶片测光」「按胶片 ISO」，相机便按所选胶卷的感光度与宽容度来测光。",
        )),
        Part("闪光", listOf(
            "四种光" to "轻点左上角的闪电，四种模式贴着取景框边缘浮现：关、闪光、补光、压光。",
            "补光" to "灯常亮，适合近处的静物与人像，也能与 HDR 合成同用。",
            "压光" to "闪光压过环境光，背景沉下去，主体跳出来；需要先在参数里开启 RAW。",
            "一点礼貌" to "在安静的场合，记得把闪光关上。",
        )),
        Part("拍摄设置", listOf(
            "打开方式" to "快门右边的方格键。常用开关都在这一层，往下滑即可关上。",
            "HDR 合成" to "按下即拍，连拍数帧对齐合并，暗部干净，高光不溢；可再加一张长曝光补足暗部。需要开启 RAW。",
            "对焦" to "AF-C 连续自动（双击锁定）、AF-L 点按锁定、MF 手动。横屏手动对焦时，取景框右侧会出现对焦条。",
            "参考线与取景辅助" to "三分线、黄金比例、对角线、中心；直方图、峰值对焦、高光保护，都可以随手开关。",
            "拍摄时加边框" to "另存一张带边框的照片，原片不受影响。边框样式在相册里设计。",
            "同时保存 RAW" to "另存一份 DNG 原片，日后可以换一卷胶片重新冲洗。",
        )),
        Part("横屏机身", listOf(
            "怎样进入" to "把手机横过来，取景框左下角会浮起一片发光的扇形，轻点它，界面便换成带拨轮、按键与刻度波轮的机身。",
            "各个部件" to "滚轮换胶卷，轻点滚轮换类别，长按展开挑选；波轮调 ISO 与快门；镜头盘选焦距。",
        )),
        Part("相册与暗房", listOf(
            "导入冲洗" to "相册第一格。选一张手机里的照片，配上胶卷、相纸与风格重新冲洗；冲洗键分成两半，另一半可以调整引擎的全部参数。",
            "浏览与筛选" to "顶部按胶卷筛选，也可以只看收藏或只看本应用拍的。长按进入多选，按住拖动可以连选一片。",
            "查看一张" to "左右滑动翻看。底栏「信息」里有直方图与完整的拍摄档案，「边框」可添片边、拍立得白边、日期印与参数。",
        )),
        Part("偏色与放大机滤色", listOf(
            "为什么会偏色" to "每一种胶卷都带着自己的底色，这正是胶片的性格；但有时它会显得过重。",
            "自动中性（默认）" to "每一卷都被校正到中性，换哪一卷都干净耐看。",
            "保留胶片本色" to "想要胶卷原汁原味的底色，进入参数的「放大机滤色」，把「滤色方式」改为「保留胶片本色」。",
            "手动" to "选「手动」后，可以像暗房里那样，自己调青、品、黄三色滤镜。",
        )),
        Part("参数与小技巧", listOf(
            "恢复默认" to "参数里长按某一项，单独恢复默认；右上角「恢复默认」则一次全部还原。",
            "主题" to "暗盒、暗房红灯（夜里只用红光，不伤暗适应）、相纸、黄铜、冷银。",
            "重看教程" to "拍摄设置的最下面，可以把那段简短的引导再走一遍。",
        )),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(48))
        }
        list.addView(text("Photo All 的每一处，都在这里。", 13f, Style.TEXT_2), lp(top = 4, bottom = 12))
        parts.forEachIndexed { i, part ->
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.BOTTOM
                addView(text("%02d".format(i + 1), 13f, Style.ACCENT).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.08f },
                    LinearLayout.LayoutParams(dp(34), -2))
                addView(text(part.title, 19f, Style.TEXT).apply { typeface = Style.medium })
            }, lp(top = 24, bottom = 6))
            part.items.forEach { (k, v) ->
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(34), dp(8), 0, dp(4))
                    addView(text(k, 15f, Style.TEXT).apply { typeface = Style.medium })
                    addView(text(v, 14f, Style.TEXT_2).apply { setLineSpacing(0f, 1.4f) }, lp(top = 3))
                })
            }
        }
        list.addView(closing(), lp(top = 36))

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(10))
            addView(text("使用指南", 20f, Style.TEXT).apply { typeface = Style.medium }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("完成", 15f, Style.ACCENT).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                Style.pressable(this)
                setOnClickListener { finish() }
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            addView(header)
            addView(Style.hairline(this@GuideActivity), LinearLayout.LayoutParams(-1, 1))
            addView(ScrollView(this@GuideActivity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
    }

    /** The closing note: what a phone cannot give, a real camera, then the project and its news. */
    private fun closing(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(22))
        background = Style.surface(this@GuideActivity, fill = Style.SURFACE, radiusDp = 16f)
        addView(text("写在最后", 18f, Style.TEXT).apply { typeface = Style.medium })
        addView(text(
            "坦白说，数码仿胶终究只是模仿。它学得来颗粒、色彩与片边，却学不来一卷胶片真正的分量：" +
                "三十六张的克制，按下快门却不知成败的忐忑，等待冲洗的那几天，" +
                "以及取回底片时，对着灯光一格一格辨认往事的惊喜。这些，任何算法都给不了。",
            14.5f, Style.TEXT_2).apply { setLineSpacing(0f, 1.5f) }, lp(top = 12))
        addView(text(
            "所以，若有机会，真心希望你去买一台真正的胶片相机，装上一卷，慢慢拍完，送去冲洗。" +
                "你对胶片懂得越多，它的宽容、它的偏色、它在逆光里的脾气，" +
                "在这款软件里，便越能尝出其中的趣味。",
            14.5f, Style.TEXT_2).apply { setLineSpacing(0f, 1.5f) }, lp(top = 10))
        addView(Style.hairline(this@GuideActivity), LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(18); bottomMargin = dp(16) })
        addView(text("Photo All 是开源的，每一行代码都在 GitHub 上。若它曾让你会心一笑，不妨去点一颗 Star，这是对作者最温柔的鼓励。",
            14f, Style.TEXT_2).apply { setLineSpacing(0f, 1.45f) })
        addView(text(GITHUB.removePrefix("https://"), 14f, Style.ACCENT).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = Style.surface(this@GuideActivity, fill = Style.SURFACE_2, radiusDp = 12f)
            Style.pressable(this)
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) }
            }
        }, lp(top = 12))
        addView(text("☆ 点一颗 Star，支持一下", 12.5f, Style.TEXT_3).apply { gravity = Gravity.CENTER }, lp(top = 6))
        addView(text("新版本与新玩法，会第一时间发在小红书与抖音的作品和群聊里。欢迎关注，我们一起，把这卷胶片拍下去。",
            14f, Style.TEXT_2).apply { setLineSpacing(0f, 1.45f) }, lp(top = 16))
    }

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    companion object {
        const val GITHUB = "https://github.com/YZH2006517/Photo-All-Android"
    }
}
