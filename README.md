# Photo All · 胶片相机（Android）

一款模拟胶片拍摄的 Android 相机。取景时实时预览胶片效果；按下快门后，照片（默认 RAW）交给手机 GPU 上的**光谱胶片模拟引擎**冲洗：先曝光到胶片、显影，再印放到相纸，最后可以叠加一层 LUT。

胶片模拟基于 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 在 [vkdt](https://github.com/hanatos/vkdt) 中的 GPU 实现（filmsim 模块）。我们把它移植成一个独立的 C++ / Vulkan 计算引擎，直接跑在手机上。

> 目前是开发版：界面还没做样式，功能已基本完整，仅在 Samsung Galaxy S23 Ultra 和红魔手机上测试过。

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
- 滤色方式：默认「保留胶片本色」（所有胶片用同一套参考滤色，各自的色彩差异都在），也可以选逐卷自动中性或手动
- 347 个开源创意 LUT（HaldCLUT，可调强度），在胶片之后叠加
- RAW 降噪：去马赛克之前按 DNG 噪声模型做小波降噪（移植自 vkdt 的 denoise 模块），之后再做一遍只动颜色的彩色降噪

**拍摄**
- RAW（DNG）拍摄后从 RAW 冲洗，默认冲洗成 JPG 后删掉 RAW（可选保留）
- HDR：多帧短曝光 RAW 加一帧长曝光（包围），按实测曝光比在 RAW 线性空间合并（HDR+ 风格的鲁棒合并），之后可选局部影调压缩
- 闪光：
  - **闪光**：单张真闪光（手机预闪测光）；拍 RAW 时先补一帧环境光，按闪光照到的区域分别校正白平衡
  - **补光 / 平衡 / 压光**：一帧环境光加一帧闪光（同一曝光），在 RAW 里算出闪光单独的光，再按模式重新配比；闪光和室内光各自校正色温
- 取景器实时预览胶片效果（33³ 3D LUT），预览和成片亮度、色彩对齐
- 多镜头识别和切换（例如 S23 Ultra 的 0.6× / 1× / 3× / 10×），双指变焦
- 对焦：AF-C、AF-L（点按锁定）、MF（距离滑块 + 峰值对焦）
- 曝光：±EV（1/3 档），手动 ISO / 快门；可选的「胶片测光规则」（测光模式、高光保护、按胶片 ISO 曝光、标定白平衡）
- 实时直方图（按胶片效果计算）、构图辅助线（三分、黄金比例、对角线、中心）
- 竖屏为主；把手机横过来时，角落会出现一个小按钮，点了才切到横屏布局，几秒后自动消失
- HDR、闪光、对焦模式、引导线收在一个可折叠的汇总条里

**相册**
- 应用内查看照片：滑动、缩放；上滑看直方图，再上滑看「拍摄档案」
- 拍摄档案：每张照片记录相机、拍摄瞬间的曝光 / 白平衡 / 对焦、每一帧、冲洗引擎的每个决定和全部设置，按类别折叠，可一键复制
- 收藏（红心）、只看收藏、多选分享、移到系统回收站（同时移走对应的 RAW）

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
