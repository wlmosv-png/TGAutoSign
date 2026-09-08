# TGAutoSign

Telegram 自动签到 Xposed 模块（libxposed API 102）。

**当前发布版本 v1.2.1**：管理界面直接做进 Telegram，发 `/jmb` 弹出菜单（目标列表 / 添加 / 删除 / 立即签到 / 日志 / 设置 / 检查更新 / 导出配置 / 导入配置），无需独立 App。模块会自己检查更新并把新版 APK 下载到「下载」目录，换账号或换手机导出一份 json 就能带走全部签到目标。

[更新日志](CHANGELOG.md) · 模块仓库发布页：[Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases) · 作者 wlmosv

## 功能

- 自动学习签到目标：点 bot 签到按钮自动记录（仅当按钮文案包含已配置关键词时添加）；网络层自动识别签到指令（仅 bot、含关键词）
- 每天只签一次；已签提示；断网补签；打开聊天 / 网络恢复自动触发
- Bot 回复语义判定、错误分类（永久放弃 / 限流 60s / 指数退避 5m→15m→45m→2h→4h）
- 多账号感知、双触发去重
- `/jmb` 管理菜单：目标列表 / 添加 / 删除 / 立即签到 / 运行日志 / 设置
- 深浅色主题适配
- 内置检查更新：启动静默查（12 小时冷却）+ `/jmb → 🔄 检查更新` 强制查并下载，安装确认交给系统安装器
- 配置导出 / 导入：签到目标、关键词、重试上限、当天签到状态存成 json，换账号不用重新学习目标

## 安装模块

1. 安装 Release 页 APK
2. Xposed 模块管理器 → 启用 TGAutoSign → 作用域勾选 Telegram
3. 强制停止 Telegram 后重开，任意聊天发 `/jmb` 即可管理

## 换账号 / 换手机迁移

1. 旧环境：`/jmb → 📤 导出配置`，得到 `TGAutoSign-config-<版本>-<时间>.json`
   （同时写入 `Android/data/org.telegram.messenger/files/tgautosign/` 与系统「下载」目录）
2. 新环境：把 json 放进 `Android/data/org.telegram.messenger/files/tgautosign/`（或下载目录后手动放过去）
3. 新环境：`/jmb → 📥 导入配置`，再 `📋 目标列表` 核对；导入是**只合并不清空**，不会抹掉新学目标

> 当天已签状态按 `acc{N}_` 前缀分账号存储，换账号后各号互不干扰；导入不会重复签已签的号。

## 版本与签名（老用户升级须知）

- `versionName` 与 tag 一律 `v主.次.修`（不再使用 `103-1.2.0` 这种「码-版本」前缀，检查更新仍兼容旧写法）
- `versionCode` 每次发布 +1：v1.0.0=100、v1.1.0=101、v1.2.0=102、v1.2.1=103
- 全部正式包用同一把 release key 签名。**只要签名不变，LSPilot / LSPosed 里可直接覆盖升级，签到数据不丢**；
  调试包（Android Debug 证书）不能覆盖正式包，遇到「与已安装应用签名不同」时先卸载模块 App 再装正式版
- 日志里的版本串统一读 `UpdateChecker.VERSION_NAME`，不再手写 v2.2 / v1.0

## 构建

```bash
# JDK 21（工程 source/target = 21）+ Android SDK 36
export TGAS_KEYSTORE=/path/to/wlmosv-release.keystore
export TGAS_KEYSTORE_PASS='从密码管理器取，不要写进任何文件或提交'
./gradlew :app:assembleRelease      # 产物 app/build/outputs/apk/release/app-release.apk
```


需要 JDK 21 + Android SDK 36。`gradle.properties` 里的
`android.aapt2FromMavenOverride` 是 ARM64 容器里用 qemu 跑 x86 aapt2 的转译配置，
正常环境请删掉这一行。

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

签名：release 走 `app/build.gradle` 的 `signingConfigs.release`，
默认从环境变量读取 keystore 路径与密码（`TGAS_KEYSTORE` / `TGAS_KEYSTORE_PASS` / `TGAS_KEY_ALIAS` / `TGAS_KEY_PASS`），
或把 keystore 放到 `app/keystore/wlmosv-release.keystore`。
