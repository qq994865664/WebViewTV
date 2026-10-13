# WebView电视 0.4.1

修复遥控器从右侧频道返回左侧分组时，列表重新定位、产生突然跳动的问题。

## 修复

- 跨栏切换保留两栏各自的选中项和滚动位置，不再把左侧分组强制移动到固定高度。
- 分组高亮、收藏数量只更新可见行，避免整张分组列表重新绑定。
- 正常方向键使用键盘焦点；触摸后恢复方向键时，按原行位置恢复。
- 延迟恢复会被新的按键、触摸、筛选或关闭操作取消，避免快速操作后旧定位覆盖新选择。
- 实际切换分组仍重新筛选频道；打开菜单仍定位当前播放频道。

## 验证

2026-10-13，MuMu Android15：

- 修改前，用真实 Android ListView 和方向键复现：选中第 23 项返回左栏后，首个可见项由索引 14 变成 19。
- 修改后，独立测试 APK 使用生产 `TvMenuView` 和 `UiScale`，通过 **56 项断言**；覆盖深层分组、滚动频道、首末分组、空收藏、触摸转方向键、快速连续左右切换、返回后立即上下移动。
- 检查选中项、可见行像素位置、焦点归属，并确认仅跨栏时频道适配器没有重新筛选通知。比较视口时忽略完全落在顶部 padding 内的附加行。
- 正式 APK 覆盖安装后，实际频道中从「黑龙江」进入右栏向下移动，再返回左栏，分组及其滚动位置保持一致，后台播放正常。
- 正式包通过最低 API 28 的 v3 签名校验，沿用 0.4.0 发布证书。

运行回归测试前，准备 JDK17、Android SDK36 / Build Tools36.0.0、ADB 和已连接设备；先运行一次 `build.ps1` 生成本地调试密钥，然后执行：

```powershell
.\scripts\test-navigation.ps1 -Sdk C:\Android\Sdk -Jdk C:\Java\jdk-17 -Adb C:\Android\Sdk\platform-tools\adb.exe -Serial emulator-5554
```

测试包名为 `com.vasthread.webviewtv.navigationtest`，使用虚构频道，不读写正式应用的数据；结果保存在 `build/navigation-test-*/result.txt`。测试结束后可用 ADB 卸载此测试包。本次测试已卸载测试包，保留正式应用。

## 安装包

- 版本：0.4.1 / versionCode 9。
- Android 9 及以上，支持 ARMv8 的通用 APK。
- 文件：`WebViewTV-0.4.1-android9plus-universal-release.apk`。
- 大小：628269 字节。
- SHA-256：`b29ca4c67fccea8ab06fc07a6a59ab18bd06e734c636dbb15d89cdef7e43886b`。
- 可覆盖升级正式版；本次运行实测为 MuMu Android15，未新增 ARMv8 真机或 Android9 实机测试。

本机对比截图：`build/wvt041-left-before.png`、`build/wvt041-right.png`、`build/wvt041-left-after.png`。构建产物、测试 APK 和截图未纳入源码仓库。
