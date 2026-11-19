# ADB 黑名单

一个面向 Android 15–17 的 LSPosed 模块：按电脑的 **ADB 公钥指纹**维护黑名单。黑名单中的电脑请求 USB 调试时，系统不再弹出授权窗口，而是直接拒绝，并（可选）发一条低优先级通知。

包名：`com.qutick.adbblacklist`

## 功能

- **连接历史**：系统 ADB 授权弹窗出现的那一刻就记录请求（无论用户随后点允许还是拒绝），因此不需要先允许一次才能拉黑。
- **黑名单**：按公钥 MD5 指纹（与系统 `AdbDebuggingManager` 使用的格式一致）匹配。
- **命中行为**：不启动系统 `UsbDebuggingActivity`，直接向 adbd 返回 `NO`；PC 侧保持 `unauthorized`。默认发一条低优先级通知，可在设置中关闭。
- **已授权设备**：把一台已经授权（曾点“始终允许”）的电脑加入黑名单时，会撤销它的系统授权、请求 adbd 断开当前调试连接，并清除连接计数，避免 key 被断开回调重新写回。
- **历史管理**：默认保留 30 天，可选 7/30/90 天或永久；支持手动清空。清空历史不会影响黑名单。
- **解除拉黑**：恢复系统默认流程，下次连接会重新弹出授权窗口。
- **中英双语** UI。

## 安装

1. 从 GitHub Actions 的 Artifact 下载 `app-debug.apk` 并安装。
2. 在 LSPosed 中启用“ADB 黑名单”模块。
3. 作用域勾选 **系统**（`system` / system_server），不要勾选其他应用。
4. 重启手机。
5. 打开 app，顶部显示“系统服务已连接 · Android xx”即表示模块工作正常。

## 使用流程

1. 某台电脑首次请求 USB 调试时，系统会弹出授权窗口；此时该请求已经进入 app 的“连接历史”。
2. 在 app 的历史或“已授权电脑”列表中点击 **拉黑**。
3. 如果该电脑已授权，模块会撤销授权并断开当前调试；如果只是请求过但未授权，则直接进入黑名单。
4. 以后该电脑再连接：不弹窗、直接拒绝；如果开启通知，会收到一条“已拦截 ADB 调试请求”。
5. 需要恢复时，在黑名单中点击 **解除拉黑**；下一次连接会回到系统默认弹窗。

> v1 没有在系统授权弹窗里增加“拉黑”按钮。弹窗出现即记录，点允许或拒绝都可以随后在 app 中拉黑。

## 实现概要

- **入口**：`AdbBlacklistModule`（libxposed API 101+，编译依赖 102；未使用 102 专属接口），作用域仅 `system`。
- **拦截点**：`com.android.server.adb.AdbDebuggingManager$AdbDebuggingHandler.startConfirmationForKey(String key, String fingerprint)`。
  - 命中黑名单：记录 `blocked`、撤销已有授权（如存在）、调用框架的 `denyDebugging()`（等价于用户点“拒绝”，向 adbd 发送 `NO`），并跳过原方法，因此系统 UI 不会启动。
  - 未命中：记录 `pending` 后继续原流程；`allowDebugging()` / `denyDebugging()` 被 hook 以更新历史结果。
  - 如果 ROM 改了 `startConfirmationForKey`，会回退到 hook `handleMessage` 中的 `MESSAGE_ADB_CONFIRM`。
- **撤销与断开**：在 `AdbDebuggingManager` 自己的 Handler 线程上执行：
  1. 从 `mConnectedKeys` 移除该 key，避免 adbd 的断开回调调用 `setLastConnectionTime()` 把 key 重新写回；
  2. 通过 `AdbThread.sendResponse("DD" + key)` 请求 adbd 断开该 key；
  3. `AdbKeyStore.removeKey(key)` 并持久化。
- **存储**：`/data/system/adb_blacklist.json`（0600，system_server 私有）。不保存公钥内容，只保存指纹和主机名；撤销时按指纹在框架 keystore 中查 key。
- **App 与 system_server 通信**：带自定义 `signature` 权限的有序广播；app 定义并持有 `com.qutick.adbblacklist.permission.MANAGE`，system_server 的 receiver 在注册时要求该权限。其他应用无法调用。
- **模块激活检测**：app 通过 `snapshot` 广播是否成功来判断；失败时显示 LSPosed 启用/作用域/重启引导。

## 兼容性

- Android 15 / 16 / 17（API 35–37）；AOSP 15、16、main 分支的 `AdbDebuggingManager` 流程一致。
- LSPosed（libxposed API 101+）。
- 仅处理 **USB ADB 授权**；无线调试不在 v1 范围内。

## 排错

在电脑上执行：

```bash
adb logcat -s AdbBlacklist
```

模块会打印 hook 安装、请求拦截、撤销结果等信息。如果看到 `AdbDebuggingManager classes not found` 或 `startConfirmationForKey unavailable`，说明该 ROM 改了类/方法，需要根据日志适配。

常见问题：

- **app 显示“系统服务未连接”**：确认 LSPosed 中模块已启用、作用域勾选了“系统”，并重启过手机。
- **拉黑后仍弹窗**：检查 logcat 中 `startConfirmationForKey` hook 是否安装成功；确认指纹是弹窗对应的那把 key（同一台电脑换 key 需要重新拉黑）。
- **历史为空**：只有出现过 ADB 授权请求的电脑才会被记录；已授权设备来自系统 `getPairedDevices()`，会出现在“已授权电脑”中。

## 构建

仓库没有提交签名配置，GitHub Actions 构建的是 **debug APK**：

- workflow：`.github/workflows/build-debug.yml`
- 触发：push 到 `main`、PR、手动 `workflow_dispatch`
- 产物：Actions → 对应 run → Artifacts → `adb-blacklist-debug`

本地构建需要 JDK 21、Android SDK 37（`platforms;android-37.0`、`build-tools;37.0.0`）和 Gradle 9.8：

```bash
bash ./gradlew :app:assembleDebug
```

## 隐私

黑名单、历史、设置仅保存在本机 `/data/system/adb_blacklist.json`，不上传。通知内容只包含主机名/指纹。

## 已知限制

- 首次连接必须先出现一次系统弹窗才能进入历史；v1 不支持在弹窗内直接拉黑。
- 同一台电脑使用不同 ADB 私钥时视为不同设备。
- 模块依赖 AOSP 的私有实现细节；如果厂商 ROM 大幅改动 `AdbDebuggingManager`，需要按日志适配。
- 撤销授权后，adbd 断开是异步的；app 会在约 0.7 秒后自动刷新一次。
