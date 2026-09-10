# TGAutoSign

**Telegram 自动签到** Xposed 模块，基于 LibXposed API 102。管理界面直接做进 Telegram：任意聊天里发 `/jmb` 弹出菜单，没有独立 App，没有桌面组件。

学习一次 bot 的签到按钮，之后模块在每天、每个账号上自动替你完成签到——断网补签、限流退避、错误分类全部内置。

[更新日志](CHANGELOG.md) · [模块发布页（Xposed-Modules-Repo）](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases) · 当前版本 **v1.3.0 (106)** · 作者 wlmosv

---

## 功能总览

**签到核心**
- 自动学习签到目标：点按 bot 签到按钮即记录（仅当按钮文案包含已配置关键词时添加，防误学）
- 每天每号只签一次；已签提示；断网后自动补签；打开聊天 / 网络恢复自动触发
- 发送层自动识别签到指令（仅 bot 会话、含关键词），不碰普通聊天
- Bot 回复语义判定 + 错误分类：永久放弃 / 限流冷却 60s / 指数退避 5m→15m→45m→2h→4h

**多客户端（v1.2.2 新增）**
- 官方 Play 版、官网直连版、Nagram XF 等 Telegram-Android 系 fork 均可注入
- 宿主判定两通道：已知包名白名单 + 标志类能力探测，未知新 fork 不必等模块更新
- Telegram X 等换内核客户端标志类不齐全，明确不注入、不误伤

**多账号**
- 学习目标、当天已签、重试与退避全部按当前账号隔离存放，互不覆盖
- 定时轮询检测账号切换，切号后重载目标并补签；只签当前选中账号，不替后台账号发消息
- **🌐 一键签全部账号**：`/jmb` 菜单直接对所有已激活账号分别签到（v1.3.0 新增）
- 官方版与第三方客户端偏好文件独立，可分别配置

**管理与维护（全部在 `/jmb` 菜单内完成）**
- 📋 目标列表 / ➕ 添加 / 🗑 删除 / 🚀 立即签到
- 📄 运行日志（含宿主包名与命中方式，便于排障与反馈）
- ⚙️ 设置：签到关键词、每日重试上限，即时生效
- 🔄 检查更新：启动静默查（12 小时冷却）+ 手动强制查，下载到系统「下载」目录后交系统安装器确认（不申请安装权限、不自动安装）
- 📤 导出配置 / 📥 导入配置：目标、关键词、重试上限、当天状态存成 json；导入只合并不清空，换账号、换手机不用重新学习

---

## 支持的客户端

先按宿主包名判定，再按标志类能力兜底，命中才注入：

| 客户端 | 包名 | 状态 |
| --- | --- | --- |
| Telegram 官方（Play / 默认渠道） | `org.telegram.messenger` | ✅ 12.10.1 (70382) 静态逐项核对 + 长期实测 |
| Telegram 官网直连版 | `org.telegram.messenger.web` | ✅ 12.10.1 (70389) 静态逐项核对（issue #1） |
| Nagram XF | `fork.risin42.nagramx` | ✅ 12.10.1-dec46b0 (1250) 静态核对 + **真机运行时实测通过**（issue #2） |
| Nagram / NagramX | `nu.gpu.nagram`、`nu.gpu.nagramx` | 白名单覆盖，未实测 |
| NagramNX web | `nu.gpu.nagram.web` | 白名单覆盖，未实测 |
| 其它 Telegram-Android 系 fork | 任意 | 能力探测：宿主 ClassLoader 能解析 `org.telegram.tgnet.ConnectionsManager`、`org.telegram.ui.Components.ChatActivityEnterView`、`org.telegram.messenger.UserConfig` 三个标志类即注入 |
| Telegram X | — | ❌ 不适配（换内核，标志类不齐全） |

- fork 只换 `applicationId`、不换内部类名，所以新出现的 fork 不用等模块更新。
- Xposed 管理器的作用域默认只勾了官方版；使用官网版 / 第三方客户端时，请在 LSPosed 中为模块勾选对应客户端的作用域。`Hosts` 只决定"勾了之后注不注入"。
- 未实测的客户端欢迎带 `/jmb → 📄 运行日志` 开 issue 反馈。

## 安装

1. 到 [Release 页](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases) 下载最新 APK 安装
2. LSPosed → 模块 → 启用 TGAutoSign，作用域勾选你实际在用的 Telegram 客户端
3. 强制停止 Telegram 后重开，任意聊天发 `/jmb` 即可管理

**老用户升级**：所有正式包用同一把 release key 签名，直接覆盖安装，签到数据不丢，不需要重新学习目标。遇到「与已安装应用签名不同」说明装到了调试包，先卸载再装正式版。

## 换账号 / 换手机迁移

1. 旧环境：`/jmb → 📤 导出配置`，得到 `TGAutoSign-config-<版本>-<时间>.json`（写入 `Android/data/org.telegram.messenger/files/tgautosign/` 与系统「下载」目录）
2. 新环境：把 json 放进 `Android/data/org.telegram.messenger/files/tgautosign/`（或先放下载目录再手动移过去）
3. 新环境：`/jmb → 📥 导入配置`，`📋 目标列表` 核对。导入是**只合并不清空**，不会抹掉新学的目标

## 已知限制

- 静默更新检查有 12 小时冷却，手动 `/jmb → 🔄 检查更新` 无视冷却
- 用户侧网络到 GitHub 不通时，应用内检查更新/下载会失败；可手动到 Release 页下载

## 构建

```bash
# JDK 21 + Android SDK 36（platforms;android-36, build-tools;36.0.0）
export TGAS_KEYSTORE=/path/to/wlmosv-release.keystore
export TGAS_KEYSTORE_PASS='库口令'
export TGAS_KEY_ALIAS='wlmosv'
./gradlew --no-daemon :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- 依赖极简：仅 `io.github.libxposed:api:102.0.0`（compileOnly），零 androidx、零运行期第三方库
- 签名信息只从环境变量读取，仓库不保存 keystore 与口令
- ARM64 容器内如遇 aapt2 无法执行，加 `-Pandroid.aapt2FromMavenOverride=<可执行的 aapt2 路径>`
- 工具链细节与发布流程见 [`docs/发布-v1.2.1.md`](docs/发布-v1.2.1.md)、[`docs/执行说明-v1.2.1发布.md`](docs/执行说明-v1.2.1发布.md)

## 版本历史

| 版本 | versionCode | 主题 |
| --- | --- | --- |
| v1.3.0 | 106 | 回调按钮签到 + 一 bot 多指令 + 一键签全部账号 |
| v1.2.3 | 105 | 签到可靠性修复（跨天重置 / 失败撤销 / FLOOD_WAIT / processUpdateArray 兼容）+ 日志导出 |
| v1.2.2 | 104 | 多客户端支持（官网直连版 / Nagram XF / 任意 TG-Android fork） |
| v1.2.1 | 103 | 内置检查更新 + 配置导出/导入 |
| v1.2.0 | 102 | 修复签到不发送 + 按钮学习关键词过滤 + 适配 TG 12.10.1 |
| v1.1.0 | 101 | 多账号支持 |
| v1.0.0 | 100 | 首个 /jmb 菜单版本 |

完整变更见 [CHANGELOG.md](CHANGELOG.md)。
