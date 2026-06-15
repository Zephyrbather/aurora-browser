# 验证记录

## 常用验证命令

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

最近一次成功安装命令：

```bash
adb -s 10AFA313MD002MQ install -r app/build/outputs/apk/debug/app-debug.apk
```

## 历史记录

- 2026-06-15：新标签页搜索框清理、导航状态 helper、首页刷新入口收敛、验证记录拆分和低风险 Kotlin 警告清理验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次网页翻译优化验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过。
- 本次全屏修复后安装状态：已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次磁力搜索功能安装状态：已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次独立磁力搜索页安装状态：已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次磁力搜索结果增强安装状态：已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次磁力搜索源、无效 magnet 过滤、图标按钮和云播页修复验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；最终覆盖安装时 ADB 设备列表为空，未完成最终安装。
- 本次内置 P2P 下载器安装状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次 0Magnet 反爬误判修复安装状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次反爬兼容和 P2P 下载增强安装状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次磁力下载引擎选择验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次磁力搜索流程和界面优化验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次全盘优化验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次磁力搜索并发和缓存优化验证状态：`testDebugUnitTest`、`lintDebug`、`clean assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
- 本次多标签和首页恢复修复验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。热修复 APK 通过 `adb shell pm install -r -g /data/local/tmp/aurora-hotfix.apk` 安装成功。
- 本次磁力源下拉界面验证状态：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均通过；已安装到设备 `10AFA313MD002MQ`，安装结果为 `Success`。
