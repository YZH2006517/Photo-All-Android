package org.photoall.film.settings

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.photoall.film.ui.Style

/**
 * The guide, the first entry of the settings, by section: a row of section tabs under the
 * title (a tap jumps to that section and opens it), then each section as a card that folds
 * (「快速上手」 open to begin with), the questions people ask second, each answer folded under
 * its question, and the closing note. The short tour on the camera points here.
 */
class GuideActivity : Activity() {

    private fun dp(v: Int) = Style.dp(this, v.toFloat())

    private class Section(val title: String, val about: String, val items: List<Pair<String, String>>,
                          val steps: Boolean = false, val faq: Boolean = false)

    private val sections = listOf(
        Section("快速上手", "第一次用，先看这五步", listOf(
            "选一卷胶卷" to "快门上方的卡片显示正在用的胶卷。长按它，片盒在取景框里一字排开，左右滑动挑选，画面实时换色；轻点画面收起。",
            "构图与对焦" to "轻点画面对焦，双指开合变焦；也可以在取景框下沿的焦距按钮上左右滑动，拉出变焦尺。",
            "按下快门" to "照片在后台冲洗，几秒后出现在左下角；冲洗期间可以接着拍。顶栏的「1 / 36」是这卷拍到第几张，拍满 36 张自动换上新的一卷。",
            "回看照片" to "点左下角的缩略图进入相册，按卷、按胶卷整理；也可以在「工具 → 观片台」里一卷一卷地看。",
            "存成预设" to "调到喜欢的胶卷、相纸与风格后，在「工具 → 预设」里起个名字保存。下次长按「工具」，滑到它上面松开就换上。",
        ), steps = true),
        Section("常见问题", "装不上、打不开、照片去哪了", listOf(
            "安装时提示「没有签名文件」或「解析包出错」" to "多半是安装包没下载完整。请用手机浏览器重新下载（不要用聊天软件里的内置浏览器），下载完对一下文件大小与发布页写的是否一致。较新的版本同时带有三种签名，个别手机的安全扫描不会再误报。",
            "提示相机被占用、打不开" to "先关掉其他正在用相机的应用（相机、视频通话、扫码、录屏等）。应用会自己隔一会儿重连几次；仍然不行，重启手机后再打开。",
            "拍完照片要等几秒才出现" to "每张照片都在后台从 RAW 开始冲洗：胶片曝光、相纸印放、光晕与颗粒都在显卡上算，所以要几秒。冲洗期间可以接着拍，照片会按顺序出来。",
            "取景画面和拍出来的不太一样" to "取景画面用的是简化的实时预览，成片是完整冲洗，多了光晕、颗粒与相纸印放的细节。色调大体一致；取景画面的亮度、柔化、饱和度可以在设置「取景」一组里单独调，不影响照片。",
            "切换焦段为什么要等一下" to "开着 RAW 时，主摄和其他焦段之间切换要重新打开相机，大约一秒，画面会先变成磨砂。这是因为 RAW 只从主摄拍、其他焦段拍 JPEG。关掉 RAW 就能瞬时切换。",
            "长焦拍的为什么不是 RAW" to "见「镜头与变焦 → RAW 从哪来」：RAW 只从直接打开的镜头拍；手机自己切换镜头的变焦档位拍 JPEG。",
            "照片存在哪里" to "手机相册里的「PhotoAll Film」文件夹（Pictures/PhotoAll Film），保存的 RAW 在它的 RAW 子文件夹，拍摄录屏在「Movies/Photo All」。若打开了「照片只存在本应用相册」，照片不会出现在手机相册里，卸载或清除应用数据时会一起删除，请先导出备份。",
            "HDR+ 多帧合成去哪了" to "因为许可证的原因，HDR+ 多帧合成已经停用，现在每次拍单张。暗处可以开 RAW、手动放慢快门并稳住手机，或用补光。",
            "闪光灯没有亮" to "有的手机在测光闪光时不会真的点亮闪光灯。应用发现后，会自动改用常亮方式拍闪光并提示一次，之后这颗镜头都这样拍。",
            "「压光」点不了" to "压光要从 RAW 里分离闪光与环境光，需要先在设置里打开 RAW。",
            "手机发烫、耗电快" to "取景时相机与显卡一直在工作，每张照片还要在显卡上完整冲洗，拍得久了发热是正常的。拍摄录屏会额外录制屏幕，不用时请在设置里关掉；不拍的时候锁屏或退到后台，相机会随之关闭。",
            "打开时提示「上次运行时出了点问题」" to "应用在出错后已经自动恢复，必要时会把这台手机切到更稳的设置，并告诉你改了什么。点「发送给开发者」一键发到开发者那里，或点「复制」自己发；只含机型、系统与出错位置，不含照片。",
            "导入的 RAW 提示「改按普通照片冲洗」" to "这张 RAW 引擎读不了时，会改用它的成片冲洗并说明原因。手机自己拍的 DNG、单反和微单的 RAW（尼康 NEF、佳能 CR2/CR3、索尼 ARW、富士 RAF 等）、三星 Expert RAW 都能按 RAW 冲洗；富士 X-Trans 与尼康 HE / HE* 高效压缩格式暂不支持。",
            "导入「直出」的照片和手机相册里不一样" to "选「直出」或只套 LUT 时，RAW 直接用手机系统自己的解码，和相册里看到的一致；一旦选了胶片才按 RAW 冲洗。导入时「微调」里的色温和色调只对这一次有效，每次导入都从中性开始。",
            "放大看全是噪点" to "白天光线充足的照片噪点很少，降噪会自动减弱，以保留细节；画面上细密的颗粒是胶片颗粒，可以在「工具 → 调参台」里把「颗粒」调低。",
            "提示画面或功能已自动降级" to "有的手机显卡驱动或相机不支持某些功能，应用会自动换成兼容的做法（例如去掉测光分析流、改拍 JPEG、改用兼容模式的着色器），每次都会明确告诉你；系统更新后会重新检测。",
            "卸载重装后设置和预设还在吗" to "设置与预设保存在应用自己的数据里，卸载或清除数据后会被删除；存在手机相册里的照片不受影响。",
            "可以把它用在商业用途吗" to "Photo All 以 CC BY-NC 4.0 发布：可以免费使用、修改与分享，但须署名作者并注明出处，未经作者书面许可不得用于任何商业用途。",
            "怎样反馈问题" to "设置最下面的「日志」：里面是这台手机的诊断信息与最近的出错记录（不含照片），点「复制日志」后发到 GitHub Issues，或小红书 / 抖音私信作者。",
        ), faq = true),
        Section("取景与对焦", "取景框里的手势和辅助", listOf(
            "取景框" to "轻点对焦并在那里测光；双击锁定对焦（AF-L），再点一次解锁；双指开合变焦。",
            "对焦模式" to "在拍摄设置（快门右边的方格键）里切换：AF-C 连续自动、AF-L 点按锁定、MF 手动。手动对焦时可以打开峰值对焦，横屏时取景框右侧会出现对焦条。",
            "参考线与辅助" to "三分线、黄金比例、对角线、中心十字；实时直方图与高光保护，都在拍摄设置里随手开关。",
            "取景画面的样子" to "设置里「取景」一组可以调取景画面的亮度、柔化、饱和度与颗粒，只影响你看到的画面，不影响拍出的照片。关闭「取景画面套用胶片」则看手机相机的原始画面。",
            "前后镜头" to "取景框右下角的旋转键。切换时画面像卡片一样翻过去，新镜头出画后才翻回来。",
            "状态栏" to "拍摄时不显示手机状态栏，从屏幕顶端往下滑可以临时呼出。",
            "快门换到上面" to "按住曝光刻度三秒，快门、相册与拍摄设置会换到刻度原来的位置，单手更好按；再按住三秒换回来。",
            "旁轴的目镜" to "「旁轴」主题里长按取景框，画面变成眼睛贴近旁轴相机目镜看到的样子：两侧参数收起，转动手机时目镜里的画面跟着视线移动，下方显示 ISO 与快门。轻点拍摄，长按或返回键退出。",
        )),
        Section("镜头与变焦", "焦距按钮、变焦尺与 RAW 的取舍", listOf(
            "焦距按钮" to "取景框下沿的圆钮是每颗镜头的 35 mm 等效焦距（例如 13 / 22 / 67 mm），点一下切换，在用的那颗会亮起。",
            "变焦尺" to "手指按在焦距按钮上左右滑动，按钮会变成一条变焦尺：中间的指针不动，刻度跟着手指走，往左更长焦、往右更广角。经过每颗镜头的焦段时轻震一下，尺上还标出 28、35、50、85、135 这些经典焦距。松手 1.5 秒后变回按钮。",
            "切换时的磨砂" to "开着 RAW 时，进出主摄要重新打开相机，大约一秒；这段时间画面会先化成磨砂，新镜头出画后再清晰。关掉 RAW，同一组镜头之间切换是瞬时的。",
            "RAW 从哪来" to "RAW 只从「按镜头自己的编号直接打开」的镜头拍，保证画面真是那颗镜头的：主摄 1 倍、系统单独列出的镜头 1 倍，以及系统没列出但允许打开的镜头（应用会自己找，例如部分三星、小米的长焦）。经由「多镜头合成相机」变焦或指定的镜头不拍 RAW，因为有的手机会拿主摄的数据冒充长焦。",
            "变焦档位拍 JPEG" to "其余焦段（变焦档位、两颗镜头之间的焦距、双指或变焦尺放大到 1.8 倍以上）拍 JPEG，由手机自己切换镜头。某颗镜头打不开时只试一次，之后这个焦段改拍 JPEG，并会提示。小米手机上应用会向相机声明系统相机身份，让它像自带相机一样切到长焦；出错时第一步先撤掉这个声明。",
            "暗处与近处" to "光线太暗或离得太近时，手机可能不切到长焦，而是用主摄裁切；这时应用会提示一次。",
            "怎样确认用的是哪颗" to "用手指挡住某颗镜头再拍：画面变黑，这一张就出自那颗镜头。",
            "自定义焦段" to "「工具 → 焦段」里勾选要显示的焦段，也可以加上自己常用的焦距（例如 40、75 mm），用离它最近、比它广的镜头变焦得到；每个焦段旁标着拍出来是 RAW 还是 JPEG。",
        )),
        Section("胶卷、相纸与风格", "一张卡片，三页，管住画面的颜色", listOf(
            "一张卡片，三页" to "快门上方的卡片分三页：胶卷、相纸、LUT 风格。左右轻拨换页，轻点打开这一页的详细面板。",
            "长按挑选" to "长按卡片，片盒在取景框里一字排开，左右滑动挑选；上方一排是分组，点一下就跳过去。",
            "胶卷" to "负片温润宽容，反转片浓烈锐利，黑白克制，电影卷长于叙事。选「直出」则不经胶片模拟。",
            "相纸" to "同一张底片印在不同相纸上，冷暖与反差各不相同。选「直接扫描」则跳过印放，看底片扫描出来的样子。",
            "LUT 风格" to "四百余种色彩查找表，分为卡片机、复古相机、电影、黑白等类别。也可以导入自己的 .cube 文件，并标明它对应的相机 Log 曲线；长按自己导入的那份可以删除。",
            "画圈调强度" to "在风格页挑好一种后，快门会化作旋钮：手指绕着它画圈，顺时针更浓、逆时针更淡，画面边转边变。轻点仍然是快门。",
            "细调冲洗" to "设置里「胶片」「冲印」「光晕」「颗粒」「耦合剂」等分组，可以细调胶片与相纸的曝光、反差、光晕与颗粒，和暗房里一样。",
            "直出" to "胶卷选「直出」时不经胶片模拟：拍摄直接用手机自己的成像（和系统相机一样的影调与处理），只套 LUT 时也是在它上面套。",
            "胶卷详情" to "在「工具 → 胶卷架」点开一卷，除了厂商资料，还写着它的气质、色彩风格、适合拍什么、拍摄与冲洗的建议，以及在 Photo All 里推荐配哪种相纸。",
        )),
        Section("曝光与闪光", "亮暗、手动与四种光", listOf(
            "曝光刻度" to "卡片下方的刻度，零点居中，每格三分之一档；往正数拨更亮，往负数拨更暗，双击归零。",
            "手动曝光" to "长按「曝光」键切到手动：点 ISO 或快门，再拖动刻度调整。再长按一次回到自动。",
            "按胶片测光" to "在拍摄设置的「取景辅助」里打开「胶片测光」和「按胶片 ISO」，相机就按所选胶卷的感光度与宽容度来测光。",
            "四种光" to "轻点左上角的闪电，四种模式贴着取景框边缘浮现：关、闪光、补光、压光。",
            "闪光" to "单张真闪光，手机先预闪测光。拍 RAW 时会先补拍一帧环境光，按闪光照到的区域分别校正白平衡，人和背景都不偏色。",
            "补光" to "闪光灯常亮，适合近处的静物与人像；亮度在设置「闪光」一组里调。",
            "压光" to "一帧环境光加一帧闪光，在 RAW 里算出闪光单独的光再重新配比：背景沉下去，主体跳出来。需要开启 RAW。",
            "一点礼貌" to "在安静的场合，记得把闪光关上。",
        )),
        Section("工具与预设", "闪光键旁边的胶囊", listOf(
            "工具入口" to "闪光键旁边写着「工具」的胶囊，图案随主题变化。轻点打开工具页：调参台、预设、快门声、焦段、观片台、胶卷架，以及 Photo All 的其他版本。",
            "调参台" to "看不懂参数也能调：选一张相册照片、一份 RAW 文件，或用主摄现拍一张 RAW，上面是原图与冲洗结果，拖动中间的线对比，双指或双击放大看细节（放大后按高分辨率重新冲洗）。下面「标准」用白话写明每项是什么、两端什么效果；「Pro」是全部参数。调好点「应用到拍摄」，之后的照片按这套参数冲洗；「还原」回到相机现在的参数。点右上角「存为预设」起个名字，就能在拍摄设置的「引擎参数」里一键切换。",
            "保存预设" to "在「工具 → 预设」顶部，起个名字点「保存」。默认只存胶片、相纸与 LUT；点「保存内容 · 更改」可以再加上冲洗参数、曝光与测光、降噪、边框。",
            "换上预设" to "点一下预设就换上。套用时只改这个预设存了的部分，例如只存了 LUT 的预设不会动你的胶卷。和当前设置一致的预设会标「当前」。",
            "长按滑选" to "在拍摄界面长按「工具」，预设会浮出来：手指不离开屏幕，滑到一个上松开就换上；在卡片外面松开则什么都不改。最后一项「管理预设」打开预设页。",
            "整理预设" to "点预设右边的「⋯」：重命名、用当前设置覆盖、删除（删除要再点一次确认）。",
            "观片台" to "像在灯箱上看底片：一卷一卷摆在台面上，可以联排播放、用放大镜看细节、用红笔做标记，挑出想要的那几张；也可以关灯看片。",
            "观片台 · 自选" to "底部卷号最前面的「自选」只放你挑的照片：点「选片」（或再点一次「自选」），在网格里点选（可以跨卷）。自选在「台面」和「联排」里都能看，台面上的摆放单独记住；点后面的卷号回到那一卷。",
            "观片台 · 播放" to "点「播放」，界面自动收起进入沉浸模式并开始放映；双击画面退出。设置的「联排播放」里可以选竖向或横向、每张停留几秒。观片台设置的各组默认收起，点标题展开。",
            "胶卷架" to "全部胶卷与相纸的 3D 陈列，上下拖动浏览，横竖屏都可以。点一件拿起来细看，附有厂商公开的技术资料：感光度、颗粒度、解像力与冲洗工艺。",
        )),
        Section("相册与暗房", "看照片、重新冲洗、加边框", listOf(
            "浏览与筛选" to "顶部按胶卷筛选，也可以只看收藏或只看本应用拍的。长按进入多选，按住拖动可以连选一片。",
            "查看一张" to "左右滑动翻看。底栏「信息」里有直方图与完整的拍摄档案，「边框」可以加片边、拍立得白边、日期印与参数。",
            "导入冲洗" to "相册第一格。选一张手机里的照片，配上胶卷、相纸与风格重新冲洗；冲洗键分成两半，另一半可以调整引擎的全部参数（按分组分页），只对这一次导入生效。标题栏的小图是直方图，点按切换波形与关闭；「测光」决定按原版测光还是保留照片原本的亮度。",
            "导入 RAW" to "可以导入手机的 DNG、三星 Expert RAW，以及单反和微单的 RAW（尼康 NEF、佳能 CR2/CR3、索尼 ARW、富士 RAF、奥林巴斯 ORF、松下 RW2 等），按 RAW 冲洗，宽容度更大。",
            "编辑" to "查看一张照片时点「编辑」：调整亮度、对比、色温等十余项，裁切、旋转、镜像、拉直，存成一张新照片，原片不动。分享键可以发给别人，也可以用其他应用打开。",
            "拍摄时加边框" to "打开后每张照片另存一份带边框的，原片不受影响。边框样式、片边文字、日期印，以及白边上要不要印拍摄信息、手机型号、胶片配方，都在设置「边框」一组。",
            "保存 RAW" to "打开「保存 RAW 格式」会另存一份 DNG 原片，日后可以换一卷胶片重新冲洗。",
            "拍摄录屏" to "在设置「拍摄录屏」里打开后，每按一次快门，就保存按下前后若干秒（默认各 10 秒）的屏幕录像，存在手机的「Movies/Photo All」。",
        )),
        Section("主题与界面", "配色、皮肤与横屏机身", listOf(
            "主题" to "设置「界面 → 主题」：暗盒、暗房红灯（夜里只用红光，不伤暗适应）、相纸（白天户外的浅色）、黄铜、冷银，以及四套界面皮肤：光、经典（拟真相机）、漫、风。",
            "横屏机身" to "把手机横过来，取景框左下角会浮起一片发光的扇形，轻点它，界面换成带拨轮、按键与刻度波轮的机身：滚轮换胶卷，波轮调 ISO 与快门，镜头盘选焦距。",
            "震动反馈" to "快门、拨盘与对焦确认的震动，可以选关、轻、标准。",
            "恢复默认" to "设置里长按某一项单独恢复默认；右上角「恢复默认」一次全部还原。",
            "重看教程" to "拍摄设置的最下面，可以把那段简短的引导再走一遍。",
        )),
        Section("偏色与放大机滤色", "胶片的底色，留还是不留", listOf(
            "为什么会偏色" to "每一种胶卷都带着自己的底色，这正是胶片的性格；但有时它会显得过重。",
            "自动中性（默认）" to "每一卷都被校正到中性，换哪一卷都干净耐看。",
            "保留胶片本色" to "想要胶卷原汁原味的底色，进入设置「放大机滤色」，把「滤色方式」改为「保留胶片本色」。",
            "手动" to "选「手动」后，可以像暗房里那样，自己调青、品、黄三色滤镜，再用品红与黄微调。",
        )),
    )

    private lateinit var scroll: ScrollView
    private val cards = mutableListOf<View>()
    private val openers = mutableListOf<(Boolean) -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(48))
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING); setDuration(180) }
        }
        list.addView(text("Photo All 的每一处，都在这里。点一个板块展开；第一次用，先看「快速上手」，遇到问题看「常见问题」。", 13f, Style.TEXT_2).apply {
            setLineSpacing(0f, 1.3f); setPadding(dp(4), 0, dp(4), 0)
        }, lp(bottom = 12))
        // every section folded to begin with: the list reads as a table of contents
        sections.forEachIndexed { i, s -> list.addView(section(i, s, open = false), lp(bottom = 10)) }
        list.addView(closing(), lp(top = 22))
        scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(6))
            addView(text("使用指南", 20f, Style.TEXT).apply { typeface = Style.medium }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("完成", 15f, Style.ACCENT).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                Style.pressable(this)
                setOnClickListener { finish() }
            })
        }
        // the sections as tabs: a tap opens that section and brings it to the top
        val tabs = LinearLayout(this).apply { setPadding(dp(16), 0, dp(16), dp(10)) }
        sections.forEachIndexed { i, s ->
            tabs.addView(text(s.title, 13f, if (s.faq) Style.ACCENT else Style.TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = Style.surface(this@GuideActivity, fill = Style.SURFACE, radiusDp = 15f,
                    stroke = if (s.faq) Style.ACCENT else Style.HAIRLINE)
                Style.pressable(this)
                setOnClickListener {
                    openers[i](true)
                    scroll.postDelayed({ scroll.smoothScrollTo(0, (cards[i].top - dp(8)).coerceAtLeast(0)) }, 200)
                }
            }, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(6) })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = org.photoall.film.ui.Style.ground()
            addView(header)
            addView(HorizontalScrollView(this@GuideActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            })
            addView(Style.hairline(this@GuideActivity), LinearLayout.LayoutParams(-1, 1))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
    }

    /** One section: its number, title and what it covers; tapped, it opens on its items. */
    private fun section(index: Int, s: Section, open: Boolean): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
            visibility = if (open) View.VISIBLE else View.GONE
        }
        s.items.forEachIndexed { k, (title, words) ->
            if (k > 0) body.addView(Style.hairline(this), LinearLayout.LayoutParams(-1, 1))
            body.addView(when {
                s.steps -> step(k + 1, title, words)
                s.faq -> question(title, words)
                else -> item(title, words)
            })
        }
        val chevron = ImageView(this).apply {
            setImageDrawable(org.photoall.film.ui.Icon(org.photoall.film.ui.Icon.Kind.CHEVRON, Style.TEXT_2, 1.8f))
            rotation = if (open) 90f else 0f
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            addView(text("%02d".format(index + 1), 13f, Style.ACCENT).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.08f },
                LinearLayout.LayoutParams(dp(32), -2))
            addView(LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(s.title, 17f, Style.TEXT).apply { typeface = Style.medium })
                addView(text("${s.about} · ${s.items.size} 条", 12f, Style.TEXT_3).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
            Style.pressable(this)
        }
        fun setOpen(on: Boolean) {
            if ((body.visibility == View.VISIBLE) == on) return
            body.visibility = if (on) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (on) 90f else 0f).setDuration(180).start()
        }
        head.setOnClickListener { setOpen(body.visibility != View.VISIBLE) }
        openers += { on -> setOpen(on) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.surface(this@GuideActivity, fill = Style.SURFACE, radiusDp = 16f,
                stroke = if (s.faq) Style.ACCENT else 0)
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING); setDuration(180) }
            addView(head)
            addView(body)
        }.also { cards += it }
    }

    private fun item(title: String, words: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(text(title, 15f, Style.TEXT).apply { typeface = Style.medium })
        addView(text(words, 14f, Style.TEXT_2).apply { setLineSpacing(0f, 1.45f) }, lp(top = 4))
    }

    /** A numbered step of the quick start. */
    private fun step(n: Int, title: String, words: String) = LinearLayout(this).apply {
        setPadding(0, dp(12), 0, dp(12))
        addView(text(n.toString(), 13f, Style.BG).apply {
            gravity = Gravity.CENTER; typeface = Style.medium
            background = Style.surface(this@GuideActivity, fill = Style.ACCENT, radiusDp = 12f)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12); topMargin = dp(1) })
        addView(LinearLayout(this@GuideActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, Style.TEXT).apply { typeface = Style.medium })
            addView(text(words, 14f, Style.TEXT_2).apply { setLineSpacing(0f, 1.45f) }, lp(top = 4))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** A question; its answer folds open under it. */
    private fun question(q: String, a: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        val mark = text("+", 18f, Style.ACCENT).apply { gravity = Gravity.CENTER }
        val answer = text(a, 14f, Style.TEXT_2).apply { setLineSpacing(0f, 1.45f); visibility = View.GONE; setPadding(0, dp(6), dp(28), 0) }
        addView(LinearLayout(this@GuideActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(q, 15f, Style.TEXT).apply { typeface = Style.medium }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)))
        })
        addView(answer)
        Style.pressable(this)
        setOnClickListener {
            val on = answer.visibility != View.VISIBLE
            answer.visibility = if (on) View.VISIBLE else View.GONE
            mark.text = if (on) "−" else "+"
        }
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
        addView(text("Photo All 的源码公开在 GitHub 上。若它曾让你会心一笑，不妨去点一颗 Star，这是对作者最温柔的鼓励。",
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
