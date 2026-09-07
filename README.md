# TGAutoSign

Telegram 自动签到 Xposed 模块（libxposed API 102）。

**当前发布版本 v1.2.0**：管理界面直接做进 Telegram，发 `/jmb` 弹出菜单（目标列表 / 添加 / 删除 / 立即签到 / 日志 / 设置），无需独立 App。

[更新日志](CHANGELOG.md) · 模块仓库发布页：[Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases) · 作者 wlmosv

## 功能

- 自动学习签到目标：点 bot 签到按钮自动记录（仅当按钮文案包含已配置关键词时添加）；网络层自动识别签到指令（仅 bot、含关键词）
- 每天只签一次；已签提示；断网补签；打开聊天 / 网络恢复自动触发
- Bot 回复语义判定、错误分类（永久放弃 / 限流 60s / 指数退避 5m→15m→45m→2h→4h）
- 多账号感知、双触发去重
- `/jmb` 管理菜单：目标列表 / 添加 / 删除 / 立即签到 / 运行日志 / 设置
- 深浅色主题适配

## 安装模块

1. 安装 Release 页 APK
2. Xposed 模块管理器 → 启用 TGAutoSign → 作用域勾选 Telegram
3. 强制停止 Telegram 后重开，任意聊天发 `/jmb` 即可管理

## 构建

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
