# Photo All · 胶片相机（Android）

一款模拟胶片拍摄的 Android 相机。取景时实时预览胶片效果；按下快门后，照片（默认 RAW）交给手机 GPU 上的**光谱胶片模拟引擎**冲洗：先曝光到胶片、显影，再印放到相纸，最后可以叠加一层 LUT。

胶片模拟基于 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 在 [vkdt](https://github.com/hanatos/vkdt) 中的 GPU 实现（filmsim 模块）。我们把它移植成一个独立的 C++ / Vulkan 计算引擎，直接跑在手机上。

> 当前版本 0.2.1（debug 签名）。第一次打开会有一段手把手的新手教程，完整说明在应用内「参数 → 使用指南」。

### 机型适配

| 机型 | 状态 |
| --- | --- |
| Samsung Galaxy S23 Ultra（骁龙 8 Gen 2） | 开发主力机，全部功能 |
| 红魔（骁龙） | 全部功能；修复了震动反馈 |
| 小米 14（骁龙 8 Gen 3） | 0.2.1 修复冲洗后闪退（CCD 数码相机效果的内存越界） |
| 小米 11 Ultra（骁龙 888，第三方 ROM） | 0.2.1 起可用 RAW：支持压缩（无损 JPEG）与非 16 位的 DNG |
| vivo X300 Pro、OPPO Find X9 Pro（天玑 9500） | 0.2.1 针对大底传感器做了内存与相机兼容处理，待更多实测反馈 |
| OPPO（闪光不亮） | 0.2.1 起闪光未触发时自动改用常亮方式，待实测反馈 |

遇到问题：应用意外退出后，下次打开会弹出问题报告；没有崩溃但不正常时，在「参数 → 复制诊断信息」。复制后发到 [Issues](../../issues) 即可，报告不含照片。

## 下载

在 [Releases](../../releases) 下载 `app-debug.apk` 安装即可（需要允许安装未知来源应用）。

- Android 10（API 29）及以上，64 位 ARM（arm64-v8a）
- GPU 需支持 Vulkan 1.1
- 这是 debug 签名的安装包，以后换了签名的版本需要先卸载再安装

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
- HDR：按下即拍的零延迟连拍（多帧短曝光 RAW，可加一帧长曝光），HDR+ 风格的对齐与鲁棒合并，之后可选局部影调压缩
- 闪光：点闪电，四种模式贴着取景框边缘浮现
  - **闪光**：单张真闪光（手机预闪测光）；拍 RAW 时先补一帧环境光，按闪光照到的区域分别校正白平衡
  - **补光**：闪光灯常亮，可与 HDR 同用
  - **压光**：一帧环境光加一帧闪光，在 RAW 里算出闪光单独的光，再重新配比，压暗背景、突出主体（需开启 RAW）
- 取景器实时预览胶片效果（33³ 3D LUT），预览和成片亮度、色彩对齐
- 多镜头识别和切换（例如 S23 Ultra 的 0.6× / 1× / 3× / 10×），双指变焦
- 对焦：AF-C（双击锁定）、AF-L（点按锁定）、MF（滑杆 + 峰值对焦；横屏时取景框右侧有对焦条）
- 曝光：EV 刻度常驻，零点居中，1/3 档一格，双击归零；长按「曝光」切换手动 ISO / 快门；可选的「胶片测光规则」（测光模式、高光保护、按胶片 ISO 曝光、标定白平衡）
- 拍摄设置一层可达：HDR、对焦、参考线、手动曝光、取景辅助（直方图、峰值对焦、高光保护）、拍摄时加边框、同时保存 RAW
- 横屏机身：把手机横过来，取景框左下角浮起一片发光的扇形，点它切到带滚轮、刻度波轮和镜头盘的横屏布局
- 震动反馈适配没有振幅控制的马达（例如部分红魔机型），全程都有手感
- 五套主题：暗盒、暗房红灯（夜拍只用红光）、相纸、黄铜、冷银

**相册与暗房**
- 第一格「导入冲洗」：把手机里的照片配上胶卷、相纸与风格重新冲洗；冲洗键分成两半，另一半可调整引擎的全部参数
- 按胶卷筛选、只看收藏、只看本应用拍的；长按多选，按住拖动连选
- 查看照片：滑动、缩放；底栏「信息」打开毛玻璃面板，看精细直方图与「拍摄档案」
- 拍摄档案：每张照片记录相机、拍摄瞬间的曝光 / 白平衡 / 对焦、每一帧、冲洗引擎的每个决定和全部设置，按类别折叠，可一键复制
- 边框设计：片边印字、拍立得白边、日期印、参数与机型，文字可旋转、组合、自由摆放
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
| filmsim 着色器、白平衡表 | [vkdt](https://github.com/hanatos/vkdt)，hanatos | BSD 2-Clause（个别标注的文件为 GPLv3，见 `engine/shaders/vkdt/LICENCE.*`） |
| RAW 降噪着色器（`engine/shaders/denoise/`） | 改写自 vkdt 的 denoise 模块，hanatos | BSD 2-Clause，改动见该目录的 `README.md` |
| HDR+ 连拍对齐与频域合并（`engine/shaders/hdrplus/`） | 移植自 [hdr-plus-swift](https://github.com/martin-marek/hdr-plus-swift)（Burst Photo），Martin Marek、Christian Bauer 等 | GPL-3.0，改动见该目录的 `README.md` |
| 胶片 / 相纸光谱数据（`assets/filmsim/filmsim.lut`） | [spektrafilm](https://github.com/andreavolpato/spektrafilm)，Andrea Volpato | CC BY-SA 4.0（经 vkdt 打包，未修改） |
| 光谱上采样表（`assets/filmsim/spectra-em.lut`） | 用 vkdt 的 mkspectra 生成 | 同 vkdt |
| 创意 LUT（`assets/luts/`） | [NatronGitHub/clut](https://github.com/NatronGitHub/clut)，Pat David、Juan Melara、Marc Roovers 等 | CC BY-SA 4.0，已从 HaldCLUT 8 级转换为 33³，见 `assets/luts/CREDITS.md` |
| CCD 数码相机（`assets/luts/digicam/`、`cpp/digicam.cpp`、`engine/Digicam.kt`） | [Marcs Digicam Emulator](https://github.com/marc-gronenberg/marcs-digicam-emulator)，Marc Gronenberg | MIT。原版 37³ 相机 LUT 随附；取景器用的 33³ sRGB 版由 `tools/convert_digicam.py` 转换；成片的噪点、溢出、紫边、降噪、锐化移植自其滤镜源码，见该目录的 `LICENSE` |
| 电影 / 调色 LUT（`assets/luts/editorhub/`） | [Editor Hub](https://editor-hub.com/assets/luts) 原创、电影风格与 FreshLUTs 社区精选 | CC0，由 `tools/convert_editorhub.py` 转换为 33³ |
| 复古相机 LUT（`assets/luts/retro/`） | [ganjmeng/dazz-retro-camera](https://github.com/ganjmeng/dazz-retro-camera) 的 CCD、胶片机、拍立得、黑白相机风格 | MIT，由 `tools/convert_dazzretro.py` 转换为 33³ |

胶片、相纸和 LUT 名称里出现的商标属于各自的所有者，只用来说明模拟的是哪种胶片，本项目与这些公司没有关联。

本项目以 [GPL-3.0](LICENSE) 发布（其中移植的 hdr-plus-swift 代码要求如此）。上表中各第三方部分保留各自的许可。
