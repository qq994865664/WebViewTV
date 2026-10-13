# WebView电视

支持 Android 手机触摸操作和电视遥控操作的 WebView 直播播放器。当前版本为 **0.4.3**（versionCode 11），包名 `com.vasthread.webviewtv.reconstructed`，可与原应用并存。

## 下载安装

在 [GitHub Releases](https://github.com/qq994865664/WebViewTV/releases/latest) 下载 `WebViewTV-0.4.3-android9plus-universal-release.apk`，最低 **Android 9（API 28）**，支持 **ARMv8 / arm64-v8a** 手机。项目只有 Java/WebView 和网页资源，没有打包原生 `.so` 库，因此使用同一个通用 APK，也支持其他 Android CPU 架构。它依赖手机上的 Android System WebView。

手机打开后进入横屏，可向左右两个方向横持。点击播放画面打开频道菜单，点击频道切换并自动收起菜单，右上角进入设置；系统返回键先关闭面板，再显示退出确认。

0.4.2 新增播放画面滑动手势：**上滑下一个频道，下滑上一个频道；左滑下一个直播源，右滑上一个直播源**。松手后每次切换一次，首尾循环；轻点仍打开菜单。菜单、设置和节目单内的滑动用于操作当前面板。单直播源频道会显示提示，不会重复加载。详见 [0.4.2 发布说明](docs/release-042.md)。

0.4.3 为上下换台加入跟手动画和目标频道提示。滑动不足时平滑回弹，确认换台后提示渐隐、画面归位；松手立即请求频道，不等待动画结束。系统关闭动画时直接切换。详见 [0.4.3 发布说明](docs/release-043.md)。

Release 使用正式私有密钥签名、关闭应用调试，带有旧本机开发签名的升级记录，可从本项目此前安装覆盖升级并保留配置。下载页同时提供 SHA-256 校验文件。构建与验证细节见 [发布说明](docs/release-040.md)。

0.4.1 修复遥控器从右侧频道返回左侧分组时的列表跳位：切换区域保留两栏选中项与滚动位置，更新分组高亮时只刷新可见行。复现和回归记录见 [0.4.1 修复说明](docs/release-041.md)。

- 深色双栏频道菜单，支持分组、收藏、遥控焦点、数字选台与备用来源。
- 采用已确认的 D 款圆润 TV 图标，统一桌面图标、电视启动横幅与菜单标识。
- 切换频道后自动全屏；播放画面按返回可确认退出并停止播放。
- 设置集中管理频道配置、更新周期、节目单和切台选项。
- 初始配置含 795 个频道条目、984 个网页来源、37 个分组。
- FreeEPG 节目单仅匹配已有频道；没有数据时显示暂无节目，不增加频道。

## 从仓库更新频道

应用默认读取本仓库的 [`config/tv-config.json`](config/tv-config.json)：

```text
https://raw.githubusercontent.com/qq994865664/WebViewTV/main/config/tv-config.json
```

维护频道时，在 GitHub 打开该文件，编辑 `channels` 内的名称、分组、播放地址或 `epgId`，然后提交到 **main 分支**。应用会在下一次缓存到期后更新；需要立即应用时，进入「设置 → 立即更新频道列表」。修改配置无需重新构建或安装 APK。

默认更新周期为 **24 小时**。设置中可选择 2 小时、6 小时、24 小时、3 天、5 天、10 天或 30 天。应用运行期间每分钟检查是否到期；应用关闭时，下次启动再检查。网络失败或配置无效时继续使用现有频道。GitHub 原始文件/CDN 可能有短暂缓存，提交后立即请求不一定马上取得最新内容。

默认原始文件地址无法读取时，应用自动尝试同一公开仓库的官方 GitHub Contents API，无需填写 GitHub token 或修改地址，并记住上次成功的读取入口。两个入口都失败时仍保留现有频道。

**远程更新只替换 `channels`**，保留本机的 EPG 地址、播放选项和更新周期。持续维护的频道应修改仓库；本机临时编辑的频道在下一次远程更新时会被仓库内容覆盖。

升级到 0.3.1 时，旧安装中的空频道源地址会进行一次迁移，指向此仓库；已有非空自定义地址会保留。之后仍可在设置中清空地址并保存，改为纯本机手动维护。设置支持编辑当前频道、编辑完整 JSON，以及使用系统文件选择器导入和导出。字段说明和文件位置见 [`config/CONFIG.md`](config/CONFIG.md)，小型示例见 [`config/tv-config.example.json`](config/tv-config.example.json)。

提交前可在工程根目录检查配置：

```powershell
python .\scripts\validate-config.py
```

## 遥控与触摸操作

| 场景 | 操作 |
| --- | --- |
| 播放画面 | 点击画面、确定键或菜单键打开频道列表 |
| 播放画面手势 | 上滑下一个频道、下滑上一个频道；左滑下一个直播源、右滑上一个直播源 |
| 播放画面 | 上下换台，左右切换来源；数字输入频道序号，1.2 秒后播放 |
| 频道菜单 | 上下选择，左右切换分组、频道和工具区域，确定播放 |
| 频道菜单 | 选择频道、切源或刷新后自动收起并全屏播放 |
| 返回键 | 面板中先关闭面板；播放画面显示退出确认 |
| 菜单底部 | 收藏、切源、刷新、画中画、节目单 |
| 菜单右上角 | 设置 |

## 节目单与播放

默认节目源为 [FreeEPG 中国](https://free-epg.de/countries/cn)，XMLTV 地址为 `https://free-epg.de/api/epg/cn.xml.gz`，默认每 6 小时更新。初始映射覆盖 212 个原频道名称、265 个频道条目，同台跨分组的重复项仍保留。实际节目覆盖随源更新变化；没有匹配或缺少当日节目时不会填造内容。映射依据见 [`docs/free-epg-mapping.md`](docs/free-epg-mapping.md)。

快速切台通过提前安全注入、初期播放器扫描、成功来源记忆与连接提示减少等待；稳定播放后降低扫描频率。频道使用公开播放网页，起播速度和可用性仍取决于网站、网络、地区和播放器限制。某来源无法播放时，可左右切源；成功来源会被记住。历史 MuMu 测试中的 12 次首帧测量平均为关闭快速模式 2.54 秒、开启 2.30 秒，约降低 9.6%，该小样本不保证每个频道都有相同效果。详细记录见 [`docs/features-03-validation.md`](docs/features-03-validation.md)。

## 构建

工程是 Android Java 项目，最低 Android 9（API 28），目标 API 35，编译 SDK 36。需要 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。

### Windows 离线构建

在工程根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Sdk 'C:\Android\Sdk' -Jdk 'C:\Java\jdk-17'
```

也可通过 `ANDROID_HOME`、`JAVA_HOME` 指定安装目录后直接运行 `build.ps1`。脚本保留本机研究环境的路径作为最后回退值；其他机器应显式传入参数或设置环境变量。所需工具已安装后，构建不依赖研究目录或网络下载。

输出为 `dist/webviewtv-reconstructed-debug.apk`。脚本验证 APK 签名、打包资源路径和哈希。调试密钥在首次构建时生成到 `build/debug.keystore`，未纳入仓库；后续覆盖安装应保留本机同一签名密钥。

正式发布使用 `build.ps1 -Release -KeyStore <私有密钥> -Lineage <签名升级记录>`，密码从 `WVT_RELEASE_STORE_PASSWORD` 进程环境读取。已配置本机签名资料时，运行 `scripts/build-release-local.ps1`，它解密 Windows 当前用户保存的凭据并在构建后清除进程中的密码。私钥和密码不在仓库中；正式升级需持续使用同一发布密钥。

### Android Studio

直接打开工程，使用 JDK 17 和 SDK 36，同步后构建 `app`。Gradle 插件版本为 8.9.1，首次同步需要取得插件与依赖。仓库未包含 Gradle Wrapper；命令行离线构建使用上面的 PowerShell 脚本。

## 来源与验证记录

0.3.2 的 D 款图标原稿与导出说明见 [design/README.md](design/README.md)，打包、覆盖安装和 MuMu 显示验证见 [docs/icon-032-validation.md](docs/icon-032-validation.md)。

0.3.1 的实际上传、MuMu 仓库更新及配置保留验证见 [docs/github-sync-validation.md](docs/github-sync-validation.md)。

这是基于用户提供的 APK 证据编写的独立研究实现，不是原作者发布版本，也不声称完整恢复原源码。原 APK 的频道数据、网站适配器及四份 JavaScript 已保留；原 JavaScript 的作者/应用署名为 **vasthread / WebView电视**。所提供 APK 未包含明确覆盖这些打包资源的授权文件；未为提取资源附加新的开源授权，原权利归属不因重建而改变。

实现范围、注入修复和版本变化见 [`docs/RECONSTRUCTION.md`](docs/RECONSTRUCTION.md)。仓库包含构建所需源码和资产，没有上传原 APK、完整反编译目录、运行截图、缓存、构建产物或签名私钥。历史报告中引用的原始证据保留在本机 `D:\WebViewTV\webviewtv-research\evidence`；其验证结果针对报告注明的版本，不等同于每个版本、设备或频道的保证。
