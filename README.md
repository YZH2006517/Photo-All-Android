# Photo All · 胶片相机（Android）

一款模拟胶片拍摄的 Android 相机。取景时实时预览胶片效果；按下快门后，照片（默认 RAW）交给手机 GPU 上的**光谱胶片模拟引擎**冲洗：先曝光到胶片、显影，再印放到相纸，最后可以叠加一层 LUT。

胶片模拟基于 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 在 [vkdt](https://github.com/hanatos/vkdt) 中的 GPU 实现（filmsim 模块）。我们把它移植成一个独立的 C++ / Vulkan 计算引擎，直接跑在手机上。

> 当前版本 0.3.0（debug 签名）。第一次打开会有一段手把手的新手教程，完整说明在应用内「参数 → 使用指南」。

## 下载

点下面的链接直接下载安装包，装上即可（需要允许安装未知来源应用）。

| 版本 | 适合 | 下载 |
|---|---|---|
| **正式版 0.3.0** | 日常使用，推荐 | [下载 APK](https://github.com/YZH2006517/Photo-All-Android/releases/download/v0.3.0/app-debug.apk) |
| **测试版 0.3.1-beta9** | 想先试新功能：拼贴相机、AI 相机、AI 修图（开发中，不代表最终效果） | [下载 APK](https://github.com/YZH2006517/Photo-All-Android/releases/download/v0.3.1-beta9/PhotoAll-0.3.1-beta9.apk) |

两个版本签名相同：测试版可以直接覆盖安装在正式版上；从测试版回到正式版需要先卸载。历史版本见 [Releases](../../releases)。

- Android 10（API 29）及以上，64 位 ARM（arm64-v8a）
- GPU 需支持 Vulkan 1.1
- 这是 debug 签名的安装包，以后换了签名的版本需要先卸载再安装

遇到问题：应用意外退出后，下次打开会弹出问题报告；没有崩溃但不正常时，在「参数 → 复制诊断信息」。复制后发到 [Issues](../../issues) 即可，报告不含照片。

## 0.3.0 更新

**观片台**
- 把照片摊在灯箱上：台面自由摆片并记住位置，联排自动播放，放大镜下看颗粒
- 关灯时台面仍是米色，照片暗下去，像没开灯的真底片
- 自选只留心头好；看罢一键收回片盒，清出台面的照片在相册里依旧保留
- 设置里可改底色、亮度、片边、平铺列数、放大镜大小；五种外观随主题

**调参台**
- 边调边看：原片与成片隔线对照，参数每动一格画面随之变化
- 标准模式讲白话，Pro 模式看全部参数；调好的一套可存为预设

**胶片引擎**
- 相纸重新校准：白是相纸的白，黑位干净，中灰不动，高光不再被削平
- 晕光加在曝光上，亮处的光晕更真实；反转片色彩更浓
- 颗粒三层同时显影，二十种胶卷各按自己的颗粒
- 柔焦、黑柔、泛光重新调过；新增 UV 与 IR 截止滤镜（参数 → 光学）

**胶卷架与预设**
- 3D 胶卷架，每一卷附一页手记：色彩脾性、适合的光与景、拍与冲的诀窍
- 预设：长按「工具」滑到想要的一套，松手即换

**RAW 与导入**
- 相机 RAW：NEF、CR2/CR3、ARW、RAF、ORF、RW2 等（LibRaw 解包）
- 三星 Expert RAW（线性 DNG）
- 导入的照片按原图尺寸冲洗；选「直出」时使用系统渲染的那一张

**拍摄**
- 变焦尺随指拨动，焦段可自定
- RAW 只用主摄，其他焦段拍 JPEG
- 旁轴目镜里显示读数，取景更顺滑

**稳定性与问题报告**
- 修复 RAW 冲洗时的闪退；更新后导入恢复可用
- 设置底部「日志」一键复制或发送给开发者，只含机型与出错记录，不含照片

**许可证**
- 改为 CC BY-NC 4.0：可自由使用、修改与分享，须署名，不得商用
- HDR+ 多帧合成随之移除（其代码来自 GPL 项目）

## 功能

**胶片冲洗**
- 22 种胶片：Portra、Ektar、Gold、Ultramax、Vision3 电影卷、Fujifilm C200 / Pro 400H、Kodachrome、Velvia、Provia、黑白 Double-X / Tri-X 等
- 9 种相纸：Endura、Ektacolor Edge、Crystal Archive、2383 / 2393 电影正片、2302 黑白
- 完整的光谱流程：DIR 耦合剂、光晕（halation）、散射、颗粒、预闪、放大机滤色
- 三个阶段都能单独关闭：
  - **胶片关**：不做胶片模拟，直出（RAW 加一条中性影调曲线）
  - **相纸关**：直接扫描底片，不印放。反转片本来就是正片；负片按实验室扫描仪的方式反转，每种胶片用灰阶标定
  - **LUT 关**：保留所选 LUT，暂时不用
- 放大机滤色：默认「自动中性」（逐卷校正到中性）；也可以选「保留胶片本色」（所有胶片用同一套参考滤色，各自的底色都在）或手动调青、品、黄
- 416 个创意 LUT，在胶片之后叠加，强度可调：即时显影、彩色负片、反转片、黑白、电影风格、电影拷贝、调色、复古相机、CCD 数码相机等类别；也可以导入自己的 `.cube`，并标明对应的相机 Log 曲线
- CCD 数码相机风格除了 LUT，成片还会加上那个年代数码相机的噪点、高光溢出、紫边、降噪与锐化
- RAW 降噪：去马赛克之前按 DNG 噪声模型做小波降噪（移植自 vkdt 的 denoise 模块），之后再做一遍只动颜色的彩色降噪
- 原生引擎以 -O3 编译，一张照片从 RAW 到成片约 3 秒

**拍摄**
- 取景框下方的卡片分三页（胶卷、相纸、风格），左右拨动换页；长按在取景框里展开挑选条，按类别分组，画面实时换色
- 挑风格时快门变成旋钮：在快门上画圈调 LUT 强度，顺时针更浓、逆时针更淡，边转边预览
- RAW（DNG）拍摄后从 RAW 冲洗，默认冲洗成 JPG 后删掉 RAW（可选保留）
- HDR+ 多帧合成已停用：它的合并代码移植自 GPL-3.0 项目，随许可变更为 CC BY-NC 4.0 移除；RAW 单张拍摄不受影响
- 闪光：点闪电，四种模式贴着取景框边缘浮现
  - **闪光**：单张真闪光（手机预闪测光）；拍 RAW 时先补一帧环境光，按闪光照到的区域分别校正白平衡
  - **补光**：闪光灯常亮
  - **压光**：一帧环境光加一帧闪光，在 RAW 里算出闪光单独的光，再重新配比，压暗背景、突出主体（需开启 RAW）
- 取景器实时预览胶片效果（33³ 3D LUT），预览和成片亮度、色彩对齐
- 多镜头识别和切换（例如 S23 Ultra 的 0.6× / 1× / 3× / 10×），双指变焦
- 对焦：AF-C（双击锁定）、AF-L（点按锁定）、MF（滑杆 + 峰值对焦；横屏时取景框右侧有对焦条）
- 曝光：EV 刻度常驻，零点居中，1/3 档一格，双击归零；长按「曝光」切换手动 ISO / 快门；可选的「胶片测光规则」（测光模式、高光保护、按胶片 ISO 曝光、标定白平衡）
- 拍摄设置一层可达：对焦、参考线、手动曝光、取景辅助（直方图、峰值对焦、高光保护）、拍摄时加边框、同时保存 RAW
- 横屏机身：把手机横过来，取景框左下角浮起一片发光的扇形，点它切到带滚轮、刻度波轮和镜头盘的横屏布局
- 震动反馈适配没有振幅控制的马达（例如部分红魔机型），全程都有手感
- 五套主题：暗盒、暗房红灯（夜拍只用红光）、相纸、黄铜、冷银；另有四套界面皮肤：光、经典（拟真相机）、漫、风

**相册与暗房**
- 第一格「导入冲洗」：把手机里的照片配上胶卷、相纸与风格重新冲洗；冲洗键分成两半，另一半可调整引擎的全部参数
- 按胶卷筛选、只看收藏、只看本应用拍的；长按多选，按住拖动连选
- 查看照片：滑动、缩放；底栏「信息」打开毛玻璃面板，看精细直方图与「拍摄档案」
- 拍摄档案：每张照片记录相机、拍摄瞬间的曝光 / 白平衡 / 对焦、每一帧、冲洗引擎的每个决定和全部设置，按类别折叠，可一键复制
- 边框设计：按真实规格的 135 片边、拍立得白边、五张主题相纸、日期印、参数与机型、画面上的 3D 暗盒；文字可旋转、组合、自由摆放或自动避让
- 收藏（红心）、多选分享、移到系统回收站（同时移走对应的 RAW）

**上手**
- 第一次打开有一段手把手的新手教程：在真实的按钮上标出位置，等你亲手做一遍再继续；教程里不会真的闪光
- 「参数」第一栏是完整的使用指南；拍摄设置最下面可以重看教程

## 项目结构

```
engine/                 胶片引擎（C++17 + Vulkan 计算着色器，Android 与桌面通用）
  src/                  Vulkan 宿主、渲染图、DNG 解析、连拍对齐、参数
  shaders/vkdt/         从 vkdt 导入的 filmsim 着色器（tools/sync_vkdt.py 生成）
  shaders/own/          自己的着色器：RAW 去马赛克、HDR 合并、缩放、模糊、输出编码与 LUT
  desktop/              桌面命令行 filmsim_cli，用来在电脑上测试冲洗
android/                Android 应用（Kotlin + CameraX + JNI）
  app/src/main/cpp/     JNI 桥
  app/src/main/java/org/photoall/film/
    camera/             拍摄界面、取景器（OpenGL ES + 3D LUT）、测光与曝光
    gallery/            相册与查看器
    settings/           参数定义与参数面板
    engine/             引擎的 Kotlin 接口
  app/src/main/assets/  spektrafilm 光谱数据、LUT 数据
tools/                  导入与生成脚本（见下文）
```

## 从源码构建

需要：

- Android SDK（compileSdk 36）、NDK `28.2.13676358`、CMake `3.31.6`（都可以在 SDK Manager 里安装）
- JDK 17

```bash
cd android
./gradlew assembleDebug
```

APK 输出在 `android/app/build/outputs/apk/debug/app-debug.apk`。着色器在构建时用 NDK 自带的 `glslc` 编译，不需要另装 Vulkan SDK。

胶片数据和 LUT 已经放在仓库里，直接构建即可。只有更新上游数据时才需要下面的脚本：

| 脚本 | 作用 |
| --- | --- |
| `tools/sync_vkdt.py [vkdt 路径]` | 从 vkdt 导入 filmsim 着色器（加上移动端补丁：只写图像显式声明 rgba16f）、白平衡表和 spektrafilm 数据。默认读 `third_party/vkdt`（测试用的是 938adb0） |
| `tools/convert_clut.py [clut 路径]` | 把 [NatronGitHub/clut](https://github.com/NatronGitHub/clut) 的 HaldCLUT 转成 33³ RGB8 资源和 manifest。默认读 `third_party/clut`（af7b50d） |
| `tools/make_icon.py [预览.html]` | 生成应用图标（自适应图标的三个矢量图层），可以顺便输出一个预览页 |
| `tools/convert_digicam.py` | 把 Marcs Digicam Emulator 的相机 LUT 转成取景器用的 33³ sRGB 版 |
| `tools/convert_editorhub.py` | 把 Editor Hub 的电影 / 调色 LUT 转成 33³ |
| `tools/convert_dazzretro.py` | 把 dazz-retro-camera 的复古相机风格转成 33³ |

### 桌面测试工具

`engine/desktop/filmsim_cli` 在电脑上用同一个引擎冲洗图片（PPM / DNG），方便对比参数。Windows 上需要在 MSVC 环境（`vcvars64.bat`）里构建：

```bash
cmake -S engine -B build/desktop -G Ninja -DFILMENGINE_NDK=<NDK 路径>
cmake --build build/desktop
build/desktop/filmsim_cli --filmsim android/app/src/main/assets/filmsim/filmsim.lut \
    --spectra android/app/src/main/assets/filmsim/spectra-em.lut \
    --in in.ppm --out out.ppm --set film=4 --set "stage print=0"
```

`--list` 列出胶片和相纸编号，`--set` 接受 vkdt filmsim 的参数名，以及 `stage film` / `stage print`。

## 第三方代码与数据

| 内容 | 来源 | 许可 |
| --- | --- | --- |
| filmsim 着色器、白平衡表 | [vkdt](https://github.com/hanatos/vkdt)，hanatos | BSD 2-Clause，见 `engine/shaders/vkdt/LICENCE.bsd2`（本项目取用的文件均为 BSD；同目录的 `LICENCE.gpl3` 是 vkdt 上游随附的，不适用于这些文件） |
| RAW 降噪着色器（`engine/shaders/denoise/`） | 改写自 vkdt 的 denoise 模块，hanatos | BSD 2-Clause，改动见该目录的 `README.md` |
| 胶片 / 相纸光谱数据（`assets/filmsim/filmsim.lut`） | [spektrafilm](https://github.com/andreavolpato/spektrafilm)，Andrea Volpato | CC BY-SA 4.0（经 vkdt 打包，未修改） |
| 光谱上采样表（`assets/filmsim/spectra-em.lut`） | 用 vkdt 的 mkspectra 生成 | 同 vkdt |
| JPEG XL 解码（DNG 1.7 的 JPEG XL 压缩，如三星 Expert RAW；构建时取 `third_party/libjxl`） | [libjxl](https://github.com/libjxl/libjxl) 0.11.1 及其依赖 Highway、Brotli、skcms | libjxl BSD-3-Clause；Highway Apache-2.0 / BSD-3；Brotli MIT；skcms BSD-3。只编入解码器 |
| 相机 RAW 解包（`engine/libraw/`：NEF、CR2/CR3、ARW、RAF、ORF、RW2 等非 DNG 格式） | [LibRaw](https://www.libraw.org) 0.21.4，LibRaw LLC（含 Dave Coffin 的 dcraw 代码） | 按 CDDL 1.0 使用（LibRaw 为 LGPL 2.1 / CDDL 1.0 双许可），文件原样未改，由 `tools/sync_libraw.py` 导入，见 `engine/libraw/README.md`、`LICENSE.CDDL`。只用到解包，去马赛克和之后的步骤都是本引擎自己的；DNG 不经过它 |
| 创意 LUT（`assets/luts/`） | [NatronGitHub/clut](https://github.com/NatronGitHub/clut)，Pat David、Juan Melara、Marc Roovers 等 | CC BY-SA 4.0，已从 HaldCLUT 8 级转换为 33³，见 `assets/luts/CREDITS.md` |
| CCD 数码相机（`assets/luts/digicam/`、`cpp/digicam.cpp`、`engine/Digicam.kt`） | [Marcs Digicam Emulator](https://github.com/marc-gronenberg/marcs-digicam-emulator)，Marc Gronenberg | MIT。原版 37³ 相机 LUT 随附；取景器用的 33³ sRGB 版由 `tools/convert_digicam.py` 转换；成片的噪点、溢出、紫边、降噪、锐化移植自其滤镜源码，见该目录的 `LICENSE` |
| 电影 / 调色 LUT（`assets/luts/editorhub/`） | [Editor Hub](https://editor-hub.com/assets/luts) 原创、电影风格与 FreshLUTs 社区精选 | CC0，由 `tools/convert_editorhub.py` 转换为 33³ |
| 复古相机 LUT（`assets/luts/retro/`） | [ganjmeng/dazz-retro-camera](https://github.com/ganjmeng/dazz-retro-camera) 的 CCD、胶片机、拍立得、黑白相机风格 | MIT，由 `tools/convert_dazzretro.py` 转换为 33³ |
| 3D 渲染（`assets/cartridge3d/three.min.js`） | [three.js](https://github.com/mrdoob/three.js) r155 | MIT，见 `assets/cartridge3d/THREE-LICENSE.txt`；暗盒与相纸盒的模型是本项目自己按实物尺寸建的 |
| 相机后盖场景的相机模型（`assets/cartridge3d/camera01.js`、`camera01/`） | [Poly Haven](https://polyhaven.com/a/Camera_01) 的 Camera_01，Rajil Jose Macatangay | CC0，见 `camera01/LICENSE.txt`；为后盖开合做了切分与转换 |
| 片边字体（`assets/fonts/edge/`） | TeX Gyre Heros（GUST）、DejaVu Sans Mono | GUST Font License、DejaVu 字体许可，见该目录的许可文件 |

胶片、相纸和 LUT 名称里出现的商标属于各自的所有者，只用来说明模拟的是哪种胶片，本项目与这些公司没有关联。

本项目自己的代码与内容以 [CC BY-NC 4.0](LICENSE)（署名—非商业性使用 4.0 国际）发布：可以使用、修改和分享，但须署名 YZH2006517 并注明出处，**未经作者书面许可不得用于任何商业用途**。上表中各第三方部分保留各自的许可（其中 CC BY-SA 4.0 的数据与 LUT 仍按 CC BY-SA 4.0）。

0.2.2 及更早的版本（至提交 800c692）以 GPL-3.0 发布，那些版本仍按 GPL-3.0 有效。
