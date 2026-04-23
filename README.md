# Aurora Browser

基于 Android `System WebView` 的安卓浏览器，实际内核为设备上的 Chromium WebView / Chrome WebView 实现。

## 已实现功能

1. 书签添加与删除
2. 浏览历史记录与一键清空
3. 夜间模式
4. 无痕浏览
5. 电脑模式
   - 切换桌面 UA
   - 浏览器标识追加 `AuroraBrowser/0.1 Desktop`
6. 页内查找
7. 广告拦截
   - 内置一组常见广告/跟踪域名
   - `shouldInterceptRequest` 级别阻断
8. 资源嗅探与下载
   - 页面标签资源嗅探
   - 网络请求资源嗅探
   - 普通文件走系统 `DownloadManager`
   - `m3u8` 流媒体走应用内后台下载与拼接
9. 下载分类显示
   - 视频 / 流媒体 / 音频 / 图片 / 文档 / 压缩包 / 其他

## 技术说明

- 语言：Kotlin
- UI：Material + ViewBinding
- 数据存储：SQLiteOpenHelper
- 后台下载：WorkManager

## 当前边界

- 无痕模式基于“关闭本地历史记录并清理会话数据”，不是 Chromium 多 Profile 级别的完全隔离。
- 广告拦截当前是轻量规则集，不是完整 EasyList 引擎。
- 流媒体下载重点支持 `m3u8`，对复杂 DRM、私有鉴权、部分 DASH/加密流不保证成功。

## 构建

```bash
./gradlew assembleDebug
```

调试 APK 输出目录：

```text
app/build/outputs/apk/debug/app-debug.apk
```
