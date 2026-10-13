# 0.3.0 设置 / EPG / 返回 / 切台验证

验证日期：2026-10-13；设备 MuMu Android15 / emulator-5554，1280×720，系统 WebView110。

最终版本 `0.3.0-research` / versionCode4，包名 `com.vasthread.webviewtv.reconstructed`，与原软件并存。
APK：`reconstructed/dist/webviewtv-reconstructed-debug.apk`，181330字节。
SHA256：`d58bab1a066c59e5a4f50b0860e5dd8b489a3e7c249bffc65598c2202d103326`。

## 构建及安装

- 离线 aapt2 / javac / d8 / zipalign 构建通过，apksigner v2/v3签名验证通过。
- verify-assets 核对打包路径与所有资产哈希通过；原APK提取的四份JS未修改。
- 最终 APK 安装后 dumpsys package 确认为 versionCode4 / 0.3.0-research。

## MuMu 交互与数据

| 项目 | 实际结果 / 证据 |
| --- | --- |
| 返回退出 | 设置/EPG先返回菜单；菜单返回关闭；播放画面返回显示退出确认；点击退出后 topResumedActivity 为 LawnchairLauncher，播放停止。最终包重复验证。`features-03-exit.png` / `features-03-exited.png` |
| 设置和缓存周期 | 2h、6h、24h、3d、5d、10d、30d选项显示；触摸选择10天并保存后 JSON为240；最终恢复24小时。`features-03-cache-options.png` / `features-03-config.saved.json` |
| 编辑当前频道 | 795频道配置编辑保存后仍为795；CCTV1三个URL及参数保留。修正高密度屏文字裁切后再次查看名称、分组、EPG ID、三个URL均可读。`features-03-editor-final.png` |
| Android15导出 | 使用 ACTION_CREATE_DOCUMENT 保存 `/sdcard/Download/WebView电视配置.json`，实际生成 UTF-8 JSON。`features-03-export-picker-2.png` |
| Android15导入 | ACTION_OPEN_DOCUMENT 从Download选择完整JSON，795频道及FreeEPG地址成功落盘。`features-03-config.imported.json` |
| 远程JSON缓存 | 本机 HTTP夹具经 ADB reverse 获取；首取成功，立即更新发送ETag并收到304；返回损坏JSON时仍保留原2频道，设置提示失败。`fixtures/requests.log` / `features-03-live.log` |
| EPG过滤 | 夹具3个XMLTV频道，配置只含2个，Android实际缓存结果2频道/10节目；额外频道未加入菜单。`features-03-fixture-epg.png` |
| 用户指定EPG | 正式源 https://free-epg.de/api/epg/cn.xml.gz；Android缓存212个匹配XMLTV频道、17869节目；江苏卫视12:00—12:34「正午江苏」与实际视频一致。`features-03-epg-final.png` |
| 切台/全屏 | 最终包数字54切江苏卫视，真实decoded-frame1956ms，自动完整全屏；退出再启动恢复54，首帧1743ms。`features-03-jiangsu-final.png` |
| 成功源记忆 | CCTV1当前第一网页源无法起播，源2正常；后续重启和两个模式测量均恢复源2。CCTV2保持源1。`switch-ready`日志可复核 |

最终手机配置：795频道 / 984地址；远程频道地址空、周期24h；EPG启用，FreeEPG中国源、周期6h；fastSwitch与rememberSource均启用。收藏和历史频道保留。正式配置快照见 `evidence/features-03-config.final.json`。原快照海峡卫视两条URL含中文误前缀，内置初始化和完整可编辑配置已修正，原解码证据未改。

## 切台测量

有效记录：`evidence/switch-benchmark-20261013-115116/results.json` / CSV / logcat。
同一新版本分别关闭/开启快速模式，CCTV1源2、CCTV2源1各切3次；只统计原生收到的视频真实解码帧，视频帧计时不包含数字选台1.2秒输入等待。两模式各有预热，保持网页缓存；测量期间临时关闭EPG，结束恢复全部原配置。此方法测试播放适配的差异，并非旧APK到新APK的直接对比。

| 模式 | 有效样本 | 平均首帧 | 中位数 | 最快 / 最慢 |
| --- | --- | --- | --- | --- |
| 快速关闭 | 6 | 2543.0ms | 2622.5ms | 1641 / 3735ms |
| 快速开启 | 6 | 2298.3ms | 2489.5ms | 1305 / 3104ms |

本轮平均降低9.6%，中位数降低5.1%，12次均出现真实解码帧。样本小、测量顺序固定，网站/CDN、网络、缓存和可用源会影响结果，不能保证每台每次都加速。

首次 `switch-benchmark-20261013-114155` 记录因Windows日志文件共享锁，采集误报超时；该批结果不用于性能统计。脚本读取改为允许共享读取后重测。第一源CCTV1实际不可用记录也保留，不伪报所有原频道/来源都健康。

## XMLTV生产解析验证

EpgRepository以真实SDK KXml2运行40项离线断言通过：时区与DST、CCTV/5/5+、显式ID、歧义不误配、节目空档、不提供结束时间、拒绝DOCTYPE/截断、gzip识别、TTL、失败使用旧缓存、同源原子保存和配置子集。测试Android API桥用于JVM执行，Android实际缓存/网络行为由上述MuMu测试验证。

真实FreeEPG文件原约6.4MB，匹配与时间窗口精简后约2.4MB；只保留过去48小时至未来8天节目。源暂未提供当前节目的频道显示暂无，不生成节目。映射报告见 `reports/free-epg-mapping.md`；初始审计265个原频道项有明确映射，当时88项具有当前节目，含同台跨分组重复。数据覆盖随源更新变化。

## 使用

使用 `reconstructed/config/tv-config.json` 在电脑手动修改，随后应用设置 → 导入配置。不要直接改变原解码证据。未指定远程频道地址时不自动覆盖手工列表；填写JSON网址后，App运行或下次启动检查到期，关闭App期间不唤醒设备。详细字段见 `reconstructed/config/CONFIG.md`。
