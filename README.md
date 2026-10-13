# WebView电视 · 研究版

面向 Android 电视与遥控操作的 WebView 直播播放器。当前版本为 **0.3.1-research**（versionCode 5），包名 `com.vasthread.webviewtv.reconstructed`，可与原应用并存。

- 深色双栏频道菜单，支持分组、收藏、遥控焦点、数字选台与备用来源。
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

工程是 Android Java 项目，最低 Android 8.0（API 26），目标 API 35，编译 SDK 36。需要 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。

### Windows 离线构建

在工程根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Sdk 'C:\Android\Sdk' -Jdk 'C:\Java\jdk-17'
```

也可通过 `ANDROID_HOME`、`JAVA_HOME` 指定安装目录后直接运行 `build.ps1`。脚本保留本机研究环境的路径作为最后回退值；其他机器应显式传入参数或设置环境变量。所需工具已安装后，构建不依赖研究目录或网络下载。

输出为 `dist/webviewtv-reconstructed-debug.apk`。脚本验证 APK 签名、打包资源路径和哈希。调试密钥在首次构建时生成到 `build/debug.keystore`，未纳入仓库；后续覆盖安装应保留本机同一签名密钥。

### Android Studio

直接打开工程，使用 JDK 17 和 SDK 36，同步后构建 `app`。Gradle 插件版本为 8.9.1，首次同步需要取得插件与依赖。仓库未包含 Gradle Wrapper；命令行离线构建使用上面的 PowerShell 脚本。

## 来源与验证记录

这是基于用户提供的 APK 证据编写的独立研究实现，不是原作者发布版本，也不声称完整恢复原源码。原 APK 的频道数据、网站适配器及四份 JavaScript 已保留；原 JavaScript 的作者/应用署名为 **vasthread / WebView电视**。所提供 APK 未包含明确覆盖这些打包资源的授权文件；未为提取资源附加新的开源授权，原权利归属不因重建而改变。

实现范围、注入修复和版本变化见 [`docs/RECONSTRUCTION.md`](docs/RECONSTRUCTION.md)。仓库包含构建所需源码和资产，没有上传原 APK、完整反编译目录、运行截图、缓存、构建产物或签名私钥。历史报告中引用的原始证据保留在本机 `D:\WebViewTV\webviewtv-research\evidence`；其验证结果针对报告注明的版本，不等同于每个版本、设备或频道的保证。
