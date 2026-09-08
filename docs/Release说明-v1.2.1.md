# TGAutoSign v1.2.1（versionCode 103）

> jmb 界面版：Telegram `/jmb` 管理菜单 + 内置检查更新 + 配置导出/导入（换账号不重学）

| 项 | 值 |
|---|---|
| 模块 ID | `io.github.wlmosv_png.tgautosign` |
| 宿主 | Telegram `org.telegram.messenger`（适配 12.10.1） |
| 框架 | LSPosed / LibXposed API 102（`minApiVersion=102`、`targetApiVersion=102`、`staticScope=false`） |
| 系统 | Android 8.0+（minSdk 26，targetSdk / compileSdk 36） |
| 权限 | 仅 `android.permission.INTERNET`（本版移除 `POST_NOTIFICATIONS`） |
| 运行时依赖 | 无（仅 `compileOnly io.github.libxposed:api:102.0.0`） |
| 签名证书 SHA-256 | `AF:55:24:CD:55:4A:E6:ED:E3:EC:27:47:C7:BE:D1:59:B0:9B:C4:F6:A4:32:44:A6:8B:46:22:E9:46:32:25:C8`（自 v1.0 未变，可覆盖安装） |

## 这一版在做什么

两条主线：**让模块自己告诉你有新版本**，**让配置跟着人走而不是跟着手机走**。另外把 v2「独立管理 App」时代留下的死代码连根拔掉，APK 更干净、权限更少。规模：32 个文件，`+1,233 / −2,257`，净减 1,024 行。

## 新增

### 1. 内置检查更新 —— `/jmb → 🔄 检查更新`

- 开机后静默检查一次，**12 小时冷却**（状态存在偏好 `tg_autosign_update` 的 `last_update_check`）。发现新版只发一条 Toast，不弹窗、不打扰、不自动下载。
- 手动入口**无视冷却**，会展示：当前版本 / 新版本号 / 更新说明 / 安装包大小。
- 下载走 release 资产的 `browser_download_url`，写入系统「下载」目录，Toast 给出完整落盘路径，随后拉起**系统安装器**由你确认——不申请 `REQUEST_INSTALL_PACKAGES`，不做静默安装。
- 更新源是编译期写死的两跳，顺序即优先级，两跳都失败只在日志留一行：
  1. `Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign`
  2. `wlmosv-png/TGAutoSign`
- 断网 / 飞行模式下静默失败，签到功能完全不受影响。
- tag 兼容两种写法：`103-1.2.1`（前缀数字即远端 versionCode，判定最稳）与 `v1.2.1`（按分段数值比版本号）。

### 2. 配置导出 / 导入 —— `/jmb → 📤 导出配置` / `📥 导入配置`

- 一次导出两份偏好：
  - `tg_autosign_gen`：按钮学习缓存、各目标上次签到日期、重试计数、发送时间戳、关键词表
  - `tg_autosign_v2`：目标列表（dialogId + 文案 + 账号 + 是否回调）、生效关键词、重试上限、通知与自动学习开关、签到状态与心跳
- 文件名 `TGAutoSign-config-1.2.1-YYYYMMDD-HHMMSS.json`，落在
  `Android/data/org.telegram.messenger/files/tgautosign/`；头部带 `format`、模块 ID、`moduleVersion`、`moduleVersionCode`、导出时间与设备信息。
- 导入按 `format` 字段校验，**只合并不清空**，缺键不崩。换账号、换手机不必重新进聊天点按钮学习目标。
- 更新冷却与设备状态**不**进导出文件——那是设备的东西，不是配置。

### 3. 版本号单一来源

`UpdateChecker.VERSION_NAME / VERSION_CODE` 成为唯一真值，日志里残留的 `v2.2`、`jmb界面版 v1.0` 全部修正，并与 `META-INF/xposed/module.prop` 的 `version` / `versionCode` 保持一致。

## 变更

- tag 规则明确：**git tag 用 `v1.2.1`**，**GitHub Release tag 用 `103-1.2.1`**（让内置更新器能直接解析出远端 versionCode）。
- 桌面小组件与独立管理页彻底移除。它们在 `AndroidManifest.xml` 里从未注册过 Activity / Receiver，装了也不可能出现，本版直接删干净。

## 清理与安全

- 删除 v2 时代不可达死代码，7 个类共 744 行：
  `ui/MainActivity`(290)、`ui/LogActivity`(113)、`ui/SettingsActivity`(54)、`ui/TargetAdapter`(74)、`ui/TGAutoSignWidget`(40)、`store/Bridge`(126)、`store/Notifier`(47)
- 连带删除 5 个 layout（`activity_main` 116、`activity_settings` 83、`activity_log` 50、`item_target` 60、`widget_layout` 28，共 337 行）、`drawable/badge_bg`、`drawable/widget_bg`、`xml/appwidget_info`，以及 `colors.xml` 里 5 个只被它们引用的颜色。
- 移除 `POST_NOTIFICATIONS` 权限与 `androidx.recyclerview:1.3.2`、`androidx.core:1.13.1` 依赖 → 本版 APK 唯一权限是 `INTERNET`。
- `build.gradle` 不再硬编码签名口令与 keystore 路径，改为环境变量注入：
  `TGAS_KEYSTORE` / `TGAS_KEYSTORE_PASS` / `TGAS_KEY_ALIAS`（默认 `wlmosv`）/ `TGAS_KEY_PASS`（默认复用库口令）；`keystore/` 已进 `.gitignore`。
- 新增代码集中在 `update/UpdateChecker.java`(345) 与 `update/ConfigStore.java`(259)，注入侧仅 `TGAutoSignCore.java` `+96/−2`、`store/Store.java` `+2/−2`、`module.prop` 版本行。

## 升级方式

1. **覆盖安装，不要先卸载** —— 卸载会丢掉学习缓存与签到记录。
2. LSPosed 里作用域保持 `org.telegram.messenger`；装完重启 Telegram（杀后台或 `force-stop`）。
3. Telegram 内发送 `/jmb` 打开管理菜单，顶部标题应显示 `v1.2.1`。
4. 首次建议：先 `📤 导出配置` 留一份档，再 `🔄 检查更新` 确认链路通（正常应报"已是最新 (1.2.1)"）。

## 产物校验

```
文件名    TGAutoSign-v1.2.1-release.apk
大小      <填>
SHA-256   <填>
证书      AF:55:24:CD:55:4A:E6:ED:E3:EC:27:47:C7:BE:D1:59:B0:9B:C4:F6:A4:32:44:A6:8B:46:22:E9:46:32:25:C8
结构      classes.dex + assets/dexopt/baseline.prof
          + META-INF/xposed/module.prop + META-INF/xposed/java_init.list
badging   package name='io.github.wlmosv_png.tgautosign' versionCode='103' versionName='1.2.1'
权限      仅 android.permission.INTERNET
```

## 真机验证（OnePlus PJX110 / Android 15 / LSPosed 2.2.0 (7854) / Zygisk Next 1.5.0）

- v1.2.0 → v1.2.1 **覆盖安装成功**（未卸载），反证签名证书一致。
- Telegram 进程注入日志：9 处 hook 全部建立（`Application.attach`、`ChatActivityEnterView.didPressedBotButton`、`ChatActivity$ChatMessageCellDelegate.didPressBotButton`、`ConnectionsManager.sendRequest`、`ChatActivity.onResume`、`MessagesController.processUpdate`、`LaunchActivity.onResume`）并输出"注入成功"，无本模块异常。
- `📤 导出配置` 实跑通过，生成 2,146 字节 JSON，含两份偏好的全部键。
- 作用域、启用状态、`apk_path` 在 LSPosed 数据库里均正确，`scope_request_blocked=0`。

## 已知限制

- 更新检查依赖能访问 `api.github.com`；网络不通时静默失败，只影响提示，不影响签到。
- 仅适配 Telegram 官方版 `org.telegram.messenger`；第三方客户端未做适配。
- TG 大版本改动按钮结构或文案后，可能需要重新学习目标：进对应聊天手动点一次签到按钮即可重新学到。
- 冷却状态按设备记录，换设备/换账号导入配置后，第一次检查更新仍需手动触发。

## 反馈

Issue：https://github.com/wlmosv-png/TGAutoSign/issues
提单请附 Telegram 版本号与 `/jmb → 📄 调试转储` 的输出。
**不要附导出的配置 JSON** —— 里面含账号索引与聊天 ID。
