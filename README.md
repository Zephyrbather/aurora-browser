# Aurora Browser

基于 Android `System WebView` 的安卓浏览器，实际内核为设备上的 Chromium WebView / Chrome WebView 实现。

## 已实现功能

1. 多标签浏览
   - 每个标签页独立 `WebView`
   - 标签缩略图预览
   - 新建、切换、关闭和恢复标签页；启动时优先恢复当前标签，后台标签切换时再加载
2. 首页与导航
   - 自定义首页快捷站点
   - 常访站点展示
   - 搜索历史与搜索建议
   - 首页磁力搜索入口，独立搜索页支持图标化操作、多源选择、有限并发检索、按源切换结果、显示磁力链接与资源大小、手动验证源站、历史记录、复制磁力和一键云播
   - 二维码识别跳转
3. 书签与历史
   - 书签添加、删除、文件夹管理
   - 浏览历史、搜索历史、按时段筛选与搜索
   - 多选批量删除
4. 浏览模式
   - 夜间模式、多主题、随时间变化主题
   - 浏览器菜单、设置页和点击弹出的设置菜单会跟随当前主题配色
   - 无痕浏览
   - 全局电脑模式与站点级电脑模式
   - 页内查找
5. 广告与跳转拦截
   - 内置常见广告/跟踪域名
   - `shouldInterceptRequest` 级别阻断
   - 可疑跨站自动跳转确认
   - 站点级广告拦截开关
6. 网页视频播放优化
   - HTML5 视频原生全屏播放
   - 返回键优先退出视频全屏
   - 播放时保持屏幕常亮，暂停/结束后恢复
   - 自动补充 `playsinline` 与 `preload=metadata`
   - 播放状态回传，用于更准确识别当前视频
7. 资源嗅探与下载
   - 页面标签资源嗅探
   - 网络请求、XHR、fetch、媒体播放资源嗅探
   - 普通文件走系统 `DownloadManager`
   - `m3u8` 流媒体走应用内后台下载与拼接
   - HLS 清晰度选择、分片并发设置、缓存播放列表复用
   - HLS 下载完成后可转码导出 MP4
8. 下载与资源管理
   - 下载分类显示：视频 / 流媒体 / 字幕 / 音频 / 图片 / 文档 / 压缩包 / 种子 / 其他
   - 下载暂停、继续、重试、取消、删除记录
   - 清理已删除文件对应的下载记录
   - 书签、历史、资源、下载列表导出 HTML
   - 内置 P2P 下载器：磁力链接和 `.torrent` 文件通过 libtorrent 后台下载，并在下载列表显示进度、速度、种子和节点数
   - 磁力下载引擎可选：默认使用内置 libtorrent，也可交给系统外部 BT 应用处理
9. 阅读与翻译
   - 当前页面保存 HTML
   - 阅读模式提取正文
   - 页面双语翻译
   - 翻译 API 密钥本机加密保存
10. 隐私与站点工具
    - 站点级设置：电脑模式、广告拦截、自动翻译、账号填充
    - 隐私与站点诊断面板
    - 当前站点本地存储和资源记录清理
    - 浏览器缓存清理，不清理历史、书签、账号和下载记录
11. 账号密码
    - 保存站点账号密码
    - 自动填充 HTTPS 登录表单
    - 使用 Android Keystore 加密保存
    - 进入账号库前进行系统锁屏验证

## 技术说明

- 语言：Kotlin
- UI：Material + ViewBinding
- 数据存储：SQLiteOpenHelper
- 后台下载：WorkManager
- P2P：FrostWire jlibtorrent / libtorrent native
- Web 内核：Android System WebView
- 安全存储：Android Keystore + AES/GCM
- 媒体：MediaPlayer、MediaMetadataRetriever、MediaExtractor/MediaMuxer

## 当前边界

- 无痕模式基于“关闭本地历史记录并清理会话数据”，不是 Chromium 多 Profile 级别的完全隔离。
- 广告拦截当前是轻量规则集，不是完整 EasyList 引擎。
- 流媒体下载重点支持 `m3u8`，对复杂 DRM、私有鉴权、部分 DASH/加密流不保证成功。
- 网页视频全屏依赖 Android WebView 对 HTML5 `video` 的支持；全屏时会临时接管屏幕方向以便在竖屏锁定下最大化播放，个别自定义播放器可能仍有兼容差异。
- 磁力搜索使用第三方站点搜索模板抓取网页结果，优先通过 HTTP 直连搜索，部分 JS 渲染站点会回退到临时 WebView 读取页面；0Magnet 使用直连和备用域名，不再走隐藏 WebView 验证页路径。搜索会尝试移动/桌面 UA、共享 Cookie、Referer、gzip/deflate 解码和 DOM 稳定等待，但不会破解验证码或明确的人机验证。源站域名和页面结构可能变动，自定义源模板需包含 `{query}`、`%s`、`{query+}`、`{query%20}` 或 `{query-}` 占位符；直链结果会过滤无效 magnet，一键云播依赖 Webtor SDK 和网络可用性。
- 磁力搜索会按源历史成功率、耗时和验证/失败次数排序选中源，并默认最多 3 个源并发检索；每完成一个源会立即刷新结果和源标签。结果行优先显示解析到的 magnet 链接，并从列表行、RSS/描述文本或详情页提取资源大小；解析器会优先复用结果行里已有的 magnet/大小并短期缓存详情页补全结果，减少重复详情页请求。源站未提供大小或详情页无法访问时不显示大小。源站需要验证时会显示单源状态卡，可打开源站手动完成验证后返回重试；验证流程依赖 WebView/CookieManager 共享 Cookie，不能绕过验证码或站点登录限制。搜索中已完成源会立即写入最近搜索缓存，下次打开磁力搜索页会显示上次已拿到的结果；最近一次搜索结果只缓存每源前 30 条，避免偏好文件过大。
- 复制磁力或种子链接时自动弹窗询问下载，弹窗有 1.8 秒防抖避免重复提示。P2P 下载依赖设备网络环境、tracker/DHT 可用性和做种情况；下载器会自动补充公开 tracker、启用 DHT/LSD/UPnP/NAT-PMP 并尝试公开 torrent cache 获取元数据，但私有冷门资源、无做种资源或运营商限制仍可能速度为 0。下载文件保存到应用外部文件目录下的 `Downloads/Aurora/P2P`。
- 设置页的“磁力下载引擎”支持“内置 libtorrent”和“外部 BT 应用”。外部应用模式只通过系统 Intent 交给已安装的 BT 客户端，Aurora 不记录任务，也不能显示外部进度、暂停/继续或完成状态；如果系统没有可处理 magnet/`.torrent` 的应用，会保留复制内容并提示失败。
- 浏览器缓存清理只处理 WebView 缓存和应用临时缓存，不删除历史、书签、账号密码、下载记录和已下载文件。
- 注入网页的资源嗅探、视频状态和凭据辅助脚本现在需要本次页面注入生成的随机桥接 token 才能调用原生接口；普通网页脚本直接调用 `AuroraBridge` 会被忽略。这是减少误触发和恶意调用的防护，不等同于 Chromium Profile 级隔离。

## 近期改动摘要

### 2026-06-05

- 优化磁力搜索耗时：结果解析先判断链接类型再截取上下文，并从同一结果行补齐已有 magnet/大小，减少无效 HTML 扫描和详情页请求。
- 优化磁力搜索网络回退：详情页补全结果增加短期内存缓存；HTTP/WebView 等待时间收紧；直连已返回空结果时，WebView 回退只补查主 URL 和首个 UA，保持最多 3 个源并发和现有结果展示逻辑不变。
- 本次磁力搜索速度优化验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 优化磁力搜索恢复：新搜索开始会保存关键词并清空旧结果缓存，每个源完成后立即保存当前结果快照；即使上一轮搜索未完全结束，下次打开磁力搜索页也会显示已完成源的结果。
- 修复渐变主题底部栏配色：底部功能栏外层、内部背景、按钮图标/禁用态、点击波纹和标签计数统一从当前主题 palette 派生，普通主题和渐变主题切换时会恢复 stroke 与背景状态。
- 本次磁力搜索恢复和渐变主题底部栏修复验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 修复渐变主题底部栏外侧矩形底色：渐变主题下根布局背景现在使用同一渐变，首页内容面板改为透出根渐变，底部圆角栏外侧不再露出固定浅色块。
- 本次渐变主题底部外侧背景修复验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 统一浏览器菜单与设置页主题联动：浏览器主菜单、浏览器菜单继续唤出的确认/选择弹窗、设置页根背景、工具栏、卡片，以及下载路径、缓存清理、HLS 并发、P2P 引擎、初始页、搜索引擎、翻译引擎、翻译凭据和权限说明弹窗都从当前主题 palette 派生颜色；主题切换后已打开的浏览器主菜单会按新 palette 重绘。
- 本次浏览器菜单与设置页主题统一验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

### 2026-05-20

- 优化磁力搜索结果展示：结果列表优先显示 magnet 链接，复制主操作只复制已解析到的 magnet 或 `.torrent` 直链；未解析到磁力的详情页结果保留打开入口。
- 新增资源大小提取：搜索解析会从结果行、RSS/描述文本和详情页中的 `size`/`大小`/`文件大小` 等字段提取 KB/MB/GB/TB 等大小文本，并在结果源信息行显示。
- 扩展详情页补全：不再只对 0Magnet 补磁力链接，所有源的详情页结果都会在限量范围内尝试补 magnet 和大小；0Magnet 仍保持直连解析，不走隐藏 WebView 验证页路径。
- 本次磁力链接和资源大小展示验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

### 2026-05-16

- 优化磁力搜索调度：选中源会按本地健康度记录排序，默认最多 3 个源并发检索，单源失败会降级为该源失败状态，不再中断整轮搜索。
- 优化磁力搜索状态缓存：记录每个源最近成功/失败/验证、耗时和结果数，最近一次搜索结果只保留每源前 30 条；结果列表复用 Activity 主题 palette，减少逐项绑定时重复读设置。
- 本次磁力搜索并发和缓存优化验证状态：`testDebugUnitTest`、`lintDebug`、`clean assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 优化列表和数据访问性能：历史搜索/时间筛选、资源分类、下载分类查询下推到 SQLite；系统下载刷新只读取有系统下载 ID 的记录；库列表 Diff 改为后台线程计算，减少大列表刷新卡顿。
- 优化下载状态更新：下载记录更新默认保留未传字段，只有明确标记时才清空 `localUri`、进度、大小或错误信息，降低 HLS/P2P/系统下载状态互相覆盖的风险。
- 优化启动标签恢复：应用启动只立即加载当前标签页，其他恢复标签保留标题和 URL，用户切换到对应标签时再加载，降低启动网络和内存压力。
- 收紧 WebView JS Bridge：资源嗅探、视频状态、剪贴板磁力提示和凭据保存等接口必须携带注入脚本中的随机 token，普通网页直接调用原生桥接口会被拒绝。
- 本次全盘优化验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；安装状态见验证记录。
- 优化磁力搜索流程：从并发收齐结果改为逐源检索，状态栏显示当前源和进度，每完成一个源立即渲染结果；“全部”和单源结果页分开呈现，单源失败/验证有独立状态卡。
- 新增磁力源手动验证入口：遇到站点验证时可打开源站页面完成验证，返回后重试该源；不会自动破解验证码或验证页。
- 优化磁力搜索界面视觉：顶部工具按钮、源标签、结果按钮和状态卡改为更紧凑的自然圆角和主题色样式，结果操作改为图标按钮以减少窄屏拥挤。
- 本次磁力搜索流程和界面优化验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 新增磁力下载引擎选择：设置页可在“内置 libtorrent”和“外部 BT 应用”之间切换；复制/打开磁力或种子链接时，确认弹窗会显示当前默认方式，并提供另一个方式作为临时操作。
- 内置模式继续使用 `P2pDownloadWorker` 和 jlibtorrent，任务进入 Aurora 下载列表；外部应用模式通过系统 `ACTION_VIEW` 打开 magnet 或 `.torrent`，不写入下载记录。
- 本次磁力下载引擎选择验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

### 2026-05-11

- 修复浏览网页后点击底部主页键返回内置首页时，常访站点、我的站点和添加站点入口可能被清空不显示的问题；内置首页导航会先同步当前标签页状态，再刷新首页数据。
- 本次主页回退修复验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

### 2026-05-09

- 增强磁力搜索站点兼容性：HTTP 抓取增加移动/桌面两套 UA 重试，WebView 回退开启 Cookie/三方 Cookie、页面滚动和 DOM 稳定等待，验证码/人机验证只识别并降级提示，不做破解。
- 增强内置 P2P 下载：磁力下载自动追加公开 tracker；libtorrent 会话开启 DHT、LSD、UPnP、NAT-PMP、uTP/TCP 和 DHT bootstrap；下载中定期强制 tracker/DHT announce，并在状态中显示 DHT 节点、连接数、tracker 和综合速度。
- 优化 P2P 元数据获取：磁力 BTIH 会先尝试公开 torrent cache 获取 `.torrent` 元数据；网页 `.torrent` 下载会携带 Referer、Cookie 和 User-Agent；Android 14 前台 dataSync 服务权限已补齐。
- 本次反爬兼容和 P2P 下载增强验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 调整 0Magnet 搜索反爬处理：恢复 `0magnet.co` 为优先域名，补充旧版路径和备用域名；0Magnet 搜索与详情解析不再触发隐藏 WebView 回退，避免把验证页当成搜索结果错误。
- 收窄磁力搜索验证页识别：只有明确 Cloudflare/JS challenge 特征才标记为验证拦截，普通页面里的 Cloudflare/attribution 文案不再误判；空页面按暂无结果或站点暂不可访问处理。
- 优化磁力源 HTTP 读取：按源站域名生成 Referer，并正确解码 gzip/deflate 响应，减少压缩页面导致的空结果。
- 本次 0Magnet 反爬误判修复验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

### 2026-05-08

- 新增内置 P2P 下载器：引入 jlibtorrent native，磁力链接和 `.torrent` 文件进入 `P2pDownloadWorker` 后台下载，下载列表显示 P2P 进度、速度、种子/节点数，并支持暂停、继续、取消、重试和删除记录/文件。
- 新增复制/点击磁力或种子链接后的下载确认：网页复制、长按链接复制、磁力搜索复制/打开都会识别有效 magnet 或 `.torrent`，弹窗确认后加入内置 P2P 队列。
- 修复 0Magnet 搜索始终失败的问题：定位原因为 0Magnet 使用短码链接（`/!kkhz`）而非标准路径，解析器无法识别为结果页。修复方式：增加 0Magnet 短码链接识别，并在搜索后并发抓取详情页提取真实磁力链接（最多 15 页）。同时增强 Cloudflare 检测以覆盖 200 响应中的 JS 挑战页面。
- 新增剪贴板磁力/种子链接自动检测：复制磁力或种子链接时自动弹窗询问是否下载，支持 1.8s 防抖避免重复弹窗。
- 大幅扩展磁力搜索源：新增 ETTV、GloTorrents、Toros、Monova、Zooqle、RARBG、TorrentSeeker、Snowfl 等 8 个源，总数增至 33 个。更新 0Magnet、TPB、1337x、EZTV、BTDB、BTSOW、MagnetDL、LimeTorrents、TorrentDownloads、TorrentProject、SkyTorrents 等源的主域名和备用域名。
- 修复磁力搜索源失效问题：HttpURLConnection 增加 CookieManager 集成、Referer 头、多种 User-Agent 和完整浏览器请求头；针对 Cloudflare 等 JS 挑战站点增加 WebView 渲染回退，先尝试直连，失败时用 WebView 完成反爬验证后解析结果。
- 修复新标签/回首页后首页常用站点和添加入口不重绘的问题。
- 优化资源下载反馈：从资源列表下载时显示明确提示，并可一键跳转下载列表。
- 优化磁力搜索细节：搜索、历史、全选、添加源改为图标按钮；结果源标签显示所有已搜索源及成功/失败状态；过滤缺失有效 BTIH/BTMH 的无效 magnet；新增从页面文本/剪贴属性提取 magnet；一键云播改为内置 Webtor SDK 播放页。
- 扩展默认磁力源：在原有 0Magnet、EZTV、BTDirectory、BTSOW、1337x、TPB、BitSearch 等基础上新增 BT4G、LimeTorrents、GloDLS、TorrentDownloads、YourBittorrent、EXT Torrents 等默认模板。
- 优化磁力搜索结果页：显示搜索成功源，按源横向切换结果，保留所有搜索到的结果；记录上次选中源和搜索历史，支持删除历史、复制磁力链接和一键云播。
- 优化磁力搜索：入口改为独立页面，支持勾选多个源后同时搜索，结果可一键复制磁力/种子链接或在线打开详情/下载页，避免直接打开源站导致连续 `keyword error`。
- 新增首页磁力搜索：替换首页搜索框下方的资源嗅探图标，内置 0Magnet、EZTV、BTDirectory、BTSOW、1337x、TPB、BitSearch 等源，并支持按示例模板添加/删除自定义源。
- 优化网页视频全屏：进入网页视频全屏时临时切换到匹配视频比例的全屏方向，竖屏锁定下也可横屏最大化播放，退出后恢复原方向和浏览器界面。
- 优化网页视频播放：支持 HTML5 视频全屏、播放常亮、返回键退出全屏、内联播放兼容和播放状态回传。
- 新增浏览器缓存清理入口：位于设置页，只清理网页缓存、HLS 预取播放列表、缩略图和应用临时缓存。
- 已将 APK 安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

### 2026-05-07

- 新增站点设置、隐私诊断、阅读模式、HLS 清晰度选择、列表 HTML 导出、字幕资源分类。
- 优化下载链路：HLS 分片并发、m3u8 缓存复用、缩略图任务取消、DB 批量事务、后台 IO 加载。
- 加强隐私安全：账号密码和翻译密钥加密保存、账号库系统锁屏验证、禁用备份敏感数据。
- 优化 UI：设置页工具化卡片、资源/下载/历史列表密度、图标按钮和多选批量管理。

## 验证记录

最近一次完整验证：

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

本次全屏修复后安装状态：已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次磁力搜索功能安装状态：已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次独立磁力搜索页安装状态：已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次磁力搜索结果增强安装状态：已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次磁力搜索源、无效 magnet 过滤、图标按钮和云播页修复验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；最终覆盖安装时 ADB 设备列表为空，未完成最终安装。

本次内置 P2P 下载器安装状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次 0Magnet 反爬误判修复安装状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次反爬兼容和 P2P 下载增强安装状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次磁力下载引擎选择验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次磁力搜索流程和界面优化验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次全盘优化验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

本次磁力搜索并发和缓存优化验证状态：`testDebugUnitTest`、`lintDebug`、`clean assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。

最近一次安装：

```bash
adb -s 10AFA313MD002MQ install -r app/build/outputs/apk/debug/app-debug.apk
```

## 构建

```bash
./gradlew assembleDebug
```

调试 APK 输出目录：

```text
app/build/outputs/apk/debug/app-debug.apk
```
