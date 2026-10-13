# WebView电视 0.4.0

面向 Android 9 及以上手机的首个 Release，支持 ARMv8 / arm64-v8a，也保留电视遥控操作。

## 安装包

- 应用名称：WebView电视。
- 包名：`com.vasthread.webviewtv.reconstructed`。
- 版本：`0.4.0`，versionCode 8。
- 最低系统：Android 9 / API 28；targetSdk 35。
- 文件：`WebViewTV-0.4.0-android9plus-universal-release.apk`。
- 大小：628268 字节；SHA-256：`c23a178aa43ca09d0d43726b294fd98b2252aa379d988ca178597ca9ff604b31`。
- 架构：通用 DEX，没有原生 `.so`；可在 ARMv8 / arm64-v8a 手机上安装，不需要按 ABI 分包。依据 [Android 官方 64 位兼容说明](https://developer.android.com/google/play/requirements/64-bit)。
- 运行依赖：系统 Android System WebView；播放源可用性取决于网页、网络和地区。

手机支持两个横屏方向。点击画面打开频道菜单，点击频道后自动收起菜单并全屏播放。首次从竖屏桌面启动时，菜单、设置和节目单均按横屏的长短边计算比例。

保留已确认的 D 款图标、GitHub 频道配置更新、本地编辑、缓存周期、FreeEPG 匹配、快速切台、收藏与退出确认。

## 正式签名与后续更新

Release 使用专用 RSA 3072 私有密钥、APK Signature Scheme v3 和 D8 release 模式，应用未启用 debuggable。最低系统为 Android 9，因此仅使用该系统开始支持的 v3 签名。

发布证书 SHA-256：

```text
725b1d5deeadf5683d65b4520fb010706f6fcd25d32c86d147e3d6711a0d6850
```

签名升级记录连接此前本机开发证书与正式发布证书，允许 Android 9+ 从此前本项目安装覆盖升级并继承数据。它不适用于其他人自行构建、使用不同签名的同包名 APK。原应用使用不同包名，可继续并存。

私钥、Windows 当前用户加密的密码与签名升级记录保存在本机仓库外的 `D:\WebViewTV\signing`。它们不属于 GitHub Release 资产。后续发布必须保留并备份这些签名资料；DPAPI 凭据文件仅能由同一 Windows 用户环境解密，迁移机器前应另行安全备份密钥密码。

已配置本机资料时执行：

```powershell
.\scripts\build-release-local.ps1
```

其他签名环境可以通过 `build.ps1 -Release -KeyStore ... -Lineage ...` 和进程环境变量 `WVT_RELEASE_STORE_PASSWORD` 提供自己的签名资料。签名和升级记录参数依据 [apksigner 官方文档](https://developer.android.com/tools/apksigner)。

## 验证范围

- 当前源码可编译；静态检查 1912 个 class 常量池成员引用，对照 SDK 的 API 版本表。
- 高于 API 28 的引用仅涉及 API 30 系统栏控制，均位于 `Build.VERSION.SDK_INT >= 30` 分支中；Android 9 使用既有 immersive 系统 UI 标志。
- APK 无 `lib/` 和 `.so` 项；原频道配置、网页适配器与 JS 字节校验通过。
- 初次正式构建通过 API 28 签名验证，在 MuMu Android 15 上从旧签名覆盖安装成功；系统保留 firstInstallTime 并记录签名轮换历史，应用正常播放。
- 最终包已重新签名并覆盖安装，系统确认 versionCode 8 / 0.4.0、minSdk 28，未包含 DEBUGGABLE 标志，API 28 的 v3 签名验证通过。
- 在 MuMu Android 15 上暂时模拟 1080 × 2400 / 480dpi 手机，从竖屏桌面冷启动到横屏，菜单、设置比例正常；触摸打开设置、滚动、返回以及选台收起菜单均正常，湖南卫视播放画面按原比例全屏显示。
- 更新后仍显示 795 个频道、1 个收藏；设置中保留当前仓库地址和用户选择的 3 天频道缓存。测试没有保存或重置这些设置。
- 本次 15:34 起 crash buffer 无新增记录；测试后恢复模拟器原始 720 × 1280 / 320dpi，并重新打开应用。
- 未连接 ARMv8 真机或 Android 9 运行设备；静态/API/签名兼容检查不等同于这两类设备的运行实测。

本机截图位于 `build/release-final-phone-menu.png`、`build/release-final-phone-settings.png`、`build/release-final-phone-play.png`，未作为公开 Release 资产上传。Release 只包含安装包与 `SHA256SUMS.txt`。
