# 更新日志

## v1.2.2 (versionCode 104) —— 多客户端支持（issue #1 / #2）

- **新增：宿主判定不再写死包名。** 新增 `Hosts`：已知包名白名单 + 标志类能力探测，命中任意一条才注入。
  - 白名单：`org.telegram.messenger`、`org.telegram.messenger.web`（官网直连版，issue #1）、`fork.risin42.nagramx`（Nagram XF，issue #2）、`nu.gpu.nagram`、`nu.gpu.nagramx`、`nu.gpu.nagram.web`
  - 能力探测：宿主 ClassLoader 能解析 `org.telegram.tgnet.ConnectionsManager` + `org.telegram.ui.Components.ChatActivityEnterView` + `org.telegram.messenger.UserConfig` 即视为 Telegram-Android 血统，新 fork 不必等模块更新；换内核的客户端（Telegram X）标志类不齐全，不会被误注入
  - 静态核对：官方 12.10.1(70382) / 官网 web 12.10.1(70389) / NagramXF 12.10.1-dec46b0(1250) 三个真实包里，14 个反射类与 `didPressedBotButton`(2 重载)、`sendRequest`(7 重载)、`MessagesController.getInstance`、`MessagesStorage.getInstance/getUser`、`UserConfig.selectedAccount`、`getInputPeer`、`ChatActivity.onResume`、`LaunchActivity.onResume` 逐项一致；第三方客户端尚未做运行时实测
- **变更：多客户端可观测。** 注入日志标注命中方式（已知客户端 / 能力探测），`/jmb → 📄 运行日志` 首行记录宿主包名；热重载沿用首次命中的宿主包名。
- **修复（仅诊断，不改行为）：** 按名字扫描方法的 4 个 hook 现在如实打印"匹配 N 个方法"，N=0 时告警。此前 `MessagesController.processUpdate` 在 TG 12.10.1 已改名 `processUpdateArray`，扫描 0 命中却照样打 `hooked`，把"Bot 回复语义判定"这条失效触发源掩盖了。
- **兼容：** 签到数据、导出 json 格式、更新通道与签名 key 均不变，可直接覆盖升级；老用户不需要重新学习目标。

## v1.2.1 (versionCode 103)
- 新增：模块内置检查更新。启动后 12 小时冷却静默查 GitHub Releases，发现新版 Toast 提示；`/jmb → 🔄 检查更新` 可强制查看版本、更新说明与大小，并把安装包下载到系统「下载」目录后交由系统安装器确认（不申请安装权限，不自动安装）。
- 新增：`/jmb → 📤 导出配置 / 📥 导入配置`。导出 `tg_autosign_gen` 与 `tg_autosign_v2` 两份偏好（目标、关键词、重试上限、签到状态），换账号或换手机不用重新学习目标；导入为只合并不清空。
- 新增：版本号单一来源 `UpdateChecker.VERSION_NAME/VERSION_CODE`，修正日志里残留的 `v2.2` / `jmb界面版 v1.0` 与实际版本不符。
- 变更：tag 规则明确为 git tag `v1.2.1` + GitHub Release tag `103-1.2.1`（前缀数字即远端 versionCode，内置更新器判定最稳；纯版本号 `v1.2.1` 写法同样兼容）。
- 清理：删除 v2「独立管理 App」时代残留的死代码——`ui/MainActivity`、`ui/LogActivity`、`ui/SettingsActivity`、`ui/TargetAdapter`、`ui/TGAutoSignWidget`、`store/Bridge`、`store/Notifier` 共 7 个类 744 行，以及 5 个 layout、`badge_bg`、`widget_bg`、`appwidget_info` 与 5 个颜色；`AndroidManifest.xml` 从未注册过任何 Activity/Receiver，桌面小组件也因此一直不出现。同时去掉不再需要的 `POST_NOTIFICATIONS` 权限与 `androidx.recyclerview` / `androidx.core` 依赖。
- 安全：`build.gradle` 移除硬编码签名口令与 keystore 路径，改为环境变量注入（`TGAS_KEYSTORE` / `TGAS_KEYSTORE_PASS` / `TGAS_KEY_ALIAS` / `TGAS_KEY_PASS`）；`keystore/` 加入 .gitignore。本版 APK 唯一权限为 `INTERNET`。

## v1.2.0（2026-09-08）—— 修复签到不发送 + 按钮学习关键词过滤

- **修复签到不发送**：`MessagesController.getInputPeer` 反射参数类型错误（`Object` → 实际应为 `TLObject`），此前手动/自动签到都在「构造 InputPeer 失败」处静默退出（表现为「已命令签到」但没有任何消息发出），现已恢复正常发送
- **按钮学习增加关键词过滤**：点按不含已配置关键词的按钮一律不添加目标；手动「添加目标」不受影响（显式操作不做关键词门槛）
- **适配 TG 12.10.1**：按钮文案读取优先走 `getText()`（兼容 `KeyboardButtonProto` 新接口结构），`text` 字段兜底
- 设置页补全「保存」按钮，关键词 / 每日重试上限即时生效并持久化
