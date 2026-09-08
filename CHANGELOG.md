# 更新日志

## v1.2.1 (versionCode 103)
- 新增：模块内置检查更新。启动后 12 小时冷却静默查 GitHub Releases，发现新版 Toast 提示；`/jmb → 🔄 检查更新` 可强制查看版本、更新说明与大小，并把安装包下载到系统「下载」目录后交由系统安装器确认（不申请安装权限，不自动安装）。
- 新增：`/jmb → 📤 导出配置 / 📥 导入配置`。导出 `tg_autosign_gen` 与 `tg_autosign_v2` 两份偏好（目标、关键词、重试上限、签到状态），换账号或换手机不用重新学习目标；导入为只合并不清空。
- 新增：版本号单一来源 `UpdateChecker.VERSION_NAME/VERSION_CODE`，修正日志里残留的 `v2.2` / `jmb界面版 v1.0` 与实际版本不符。
- 变更：tag 规则统一为 `v1.2.1`（检查更新同时兼容旧的 `103-1.2.0` 写法）。
- 清理：删除 v2「独立管理 App」时代残留的死代码——`ui/MainActivity`、`ui/LogActivity`、`ui/SettingsActivity`、`ui/TargetAdapter`、`ui/TGAutoSignWidget`、`store/Bridge`、`store/Notifier` 共 7 个类 790 行，以及 5 个 layout、`badge_bg`、`widget_bg`、`appwidget_info` 与 5 个颜色；`AndroidManifest.xml` 从未注册过任何 Activity/Receiver，桌面小组件也因此一直不出现。同时去掉不再需要的 `POST_NOTIFICATIONS` 权限与 `androidx.recyclerview` / `androidx.core` 依赖。
- 安全：`build.gradle` 移除硬编码签名口令，改为环境变量注入；`keystore/` 加入 .gitignore。

## v1.2.0（2026-09-08）—— 修复签到不发送 + 按钮学习关键词过滤

- **修复签到不发送**：`MessagesController.getInputPeer` 反射参数类型错误（`Object` → 实际应为 `TLObject`），此前手动/自动签到都在「构造 InputPeer 失败」处静默退出（表现为「已命令签到」但没有任何消息发出），现已恢复正常发送
- **按钮学习增加关键词过滤**：点按不含已配置关键词的按钮一律不添加目标；手动「添加目标」不受影响（显式操作不做关键词门槛）
- **适配 TG 12.10.1**：按钮文案读取优先走 `getText()`（兼容 `KeyboardButtonProto` 新接口结构），`text` 字段兜底
- 设置页补全「保存」按钮，关键词 / 每日重试上限即时生效并持久化
