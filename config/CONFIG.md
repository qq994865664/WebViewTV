# 频道与播放配置

[`tv-config.json`](tv-config.json) 是完整的 UTF-8 JSON 配置，也是本仓库的远程频道来源。[`tv-config.example.json`](tv-config.example.json) 是两个频道的格式示例。设置页的编辑、导入和导出使用相同格式；导入与远程更新也接受单独的频道数组。

## 在 GitHub 维护

编辑 `config/tv-config.json` 的 `channels` 并提交到 **main**。应用的默认地址是：

```text
https://raw.githubusercontent.com/qq994865664/WebViewTV/main/config/tv-config.json
```

默认 24 小时更新一次。应用运行时每分钟检查是否到期，关闭时在下次启动检查；「设置 → 立即更新频道列表」可跳过有效期。远程读取只更新 `channels`，仓库中的 `epg`、`playback`、`channelUpdateHours` 不会覆盖现有安装的设置。

本机频道编辑在下一次远程更新时会被仓库内容替换。需要持续维护时修改仓库；需要仅使用本机列表时，清空设置中的频道源地址并保存。0.3.1 首次升级会把旧安装的空地址迁移到默认仓库地址一次，保留已有非空地址；后续主动清空仍有效。

提交前在工程根目录运行：

```powershell
python .\scripts\validate-config.py
```

## 字段

| 字段 | 用途 |
| --- | --- |
| `version` | 当前为整数 `1` |
| `channelSourceUrl` | HTTP(S) 频道 JSON 地址；默认本仓库地址，空字符串表示纯本机维护 |
| `channelUpdateHours` | `2`、`6`、`24`、`72`、`120`、`240`、`720`：2 小时、6 小时、24 小时、3 天、5 天、10 天、30 天 |
| `epg.enabled` | 是否显示节目单 |
| `epg.url` | XMLTV 地址；默认 `https://free-epg.de/api/epg/cn.xml.gz`，明确空值表示未配置源 |
| `epg.refreshHours` | 节目单刷新间隔，默认 6 小时，配置支持 1 至 720 小时；设置页提供 2、6、24 小时 |
| `playback.fastSwitch` | 快速切台优化开关 |
| `playback.rememberSource` | 是否记住每个频道上次成功播放的来源 |
| `playback.sourceTimeoutSeconds` | 超时自动切源：`0` 关闭，或 `5`、`10`、`15`、`30` 秒；默认 `15`，旧配置省略此字段也使用 `15` |
| `channels` | 非空频道数组 |

每个频道必须有非空 `name`、`group` 和 `urls`。单个来源写成 `{"url":"https://...","extra":"可选的播放器参数"}`，也支持直接使用 HTTP(S) 字符串。`extra` 是网站适配器需要的参数，应保留已知有效值。

示例：

```json
{
  "name": "频道名称",
  "group": "卫视",
  "urls": [
    {"url": "https://example.com/live", "extra": ""}
  ],
  "id": "stable-channel-id",
  "epgId": "XMLTV-channel-id"
}
```

`id` 和 `epgId` 均为可选字符串。`id` 应稳定，方便更新后识别同一频道；`epgId` 对应 XMLTV 的 `<channel id>`，没有映射的频道仍可播放。改用其他节目源时，应核对该源的 ID，不能假定不同来源使用相同 ID。

新增分组只需使用新的 `group` 值。频道显示顺序与数组顺序一致，同名频道允许保留。来源是频道播放网页，短期内嵌视频地址可能失效；多个来源的顺序决定首次尝试顺序，已启用成功源记忆时会优先恢复成功来源。

## 本机文件与导入导出

Android 实际配置通常位于：

```text
/storage/emulated/0/Android/data/com.vasthread.webviewtv.reconstructed/files/config/tv-config.json
```

Android 15 可能限制普通文件管理器访问 `Android/data`，优先使用应用设置中的编辑、导入与导出。通过 ADB 可以 pull/push 此文件，手动修改后重启应用。电脑上的仓库文件不会自动同步到手机本机文件；提交到 GitHub 后由远程更新读取，或通过设置导入。

文件应使用 UTF-8 和标准 JSON，不支持 JavaScript 表达式或注释。完整对象导入可修改本机设置；远程下载只合并频道。应用保留配置对象中的自定义字段。

## 缓存与错误处理

缓存周期控制的是远程频道 JSON。只有成功获取并保存后才记录刷新时间；HTTP ETag、Last-Modified 条件请求可避免重复下载。更换地址会使旧地址的缓存时间失效。GitHub/CDN 可能短暂返回提交前的内容，稍后再次更新即可。

默认仓库地址读取失败时，自动备用读取同一公开仓库的官方 GitHub Contents API，无需 GitHub token。缓存会记住成功入口，下次优先使用；两个入口都失败时继续使用原列表。此备用读取仅适用于默认仓库地址，自定义源仍按其配置读取。

HTTP 错误、超时、超过 8 MiB、UTF-8 解码失败、JSON 无效或全部频道无效时，继续使用当前有效配置。部分无效频道或来源会被忽略，并显示数量与原因警告。下载时若频道或源地址被编辑，旧下载结果会被丢弃，避免覆盖新编辑。

配置目录中的保护文件：

| 文件 | 用途 |
| --- | --- |
| `tv-config.json` | 实际可编辑配置 |
| `tv-config.last-good.json` | 最近一次修改前的有效配置 |
| `channel-cache.json` | 最近一次有效远程频道数组 |
| `channel-cache.meta.json` | 来源、成功时间、HTTP 验证标记与内容 SHA-256 |

写入使用同目录临时文件、同步和原子替换。配置损坏时保留损坏文件并报告错误，尝试上次有效配置；没有有效备份时使用内置频道。应用不会在关闭期间唤醒设备更新。

## 开发接口

`ConfigRepository(Context)` 不读取文件；先调用 `load()`，再读取 `snapshot()` / `channels()`。两者返回深拷贝，修改返回值不会直接修改仓库。

`save(JSONObject)`、`importText(String)`、`exportText()` 用于保存及导入导出。`load()` / `save()` / `importText()` 的错误为 `IOException`；`lastError()` / `lastWarning()` 提供恢复与部分数据无效信息；`file()` 返回实际配置位置。

`refreshDue()` 检查有效期；`refresh(boolean force)` 必须在工作线程执行，等待网络时不持有配置锁。结果包含 `changed`、`skipped`、`error`（成功时为 `null`）、`warning`、`timestamp`、`channelCount`。`lastRefreshAt()` 返回当前来源最近成功时间，未更新或已更换来源时为 `0`。
