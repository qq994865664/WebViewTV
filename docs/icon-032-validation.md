# 0.3.2 图标验证

验证日期：2026-10-13。用户确认「D 款 · 弧线韵律」后，将该原图应用到工程，版本为 `0.3.2-research` / versionCode 6。原稿、完整生成提示词和资源导出说明见 [design/README.md](../design/README.md)。

## 构建与包检查

- `build.ps1` 增加 Android 资源编译和链接，原 assets 仍使用 jar 打包。
- 离线构建成功；`verify-assets.ps1` 的 ZIP 路径和原始内容校验通过。
- `aapt2 dump badging` 确认应用图标指向自适应资源，电视横幅指向 320 × 180 PNG，包名与版本符合预期。
- APK 的 v2/v3 签名验证通过；新旧包的签名证书 SHA-256 均为 `8deea8c07c82b502cf70e17bb496dbae6884f52aefa6c6c9f1055ab1cde0c635`。
- 配置文件和原有 assets 没有改动；`git diff --check` 通过。

本地输出：`dist/webviewtv-reconstructed-debug.apk`，628469 字节，SHA-256：

```text
027B5AD476CA5103008A653419D829260103906EC705E371547ECB7BCE5E55D4
```

## MuMu Android 15

在 `emulator-5554` 上从 0.3.1 覆盖安装到 0.3.2，系统回报安装成功，`firstInstallTime` 保持不变。ADB 的增量安装模式被设备拒绝后自动转为流式安装成功。

| 检查 | 结果 |
| --- | --- |
| 桌面图标 | 显示 D 款 TV 标识，圆角遮罩内字母与播放三角完整 |
| 冷启动 | `am start -W` 返回 `Status: ok`；一次启动耗时 400ms，仅作本次记录 |
| 菜单页眉 | 显示同一图标，标题、设置和关闭按钮没有重叠 |
| 频道与收藏 | 菜单仍显示 795 个频道、5 个收藏 |
| 播放 | 启动恢复广东珠江频道画面 |
| 切换全屏 | 在菜单点击广东卫视，菜单自动收起并显示全屏节目画面 |
| 崩溃日志 | 本次安装启动时段 14:29 起的 crash buffer 无新增记录 |

本机证据目录：`D:\WebViewTV\icon-designs\validation`，包含 `home.png`、`menu.png`、`playback.png` 和 `switch-fullscreen.png`。截图和签名私钥未上传。电视横幅已检查 PNG 与 APK 资源引用，未在独立 Android TV 启动器上实测。
