# TGAutoSign

Telegram 自动签到。

**当前可用形态：LSPilot 插件（jmb界面版）** —— 推荐使用，发 `/jmb` 在 Telegram 内弹出管理界面（目标列表 / 添加 / 删除 / 手动签到 / 日志 / 设置）。源码在 `LSPilot-plugins/jmb界面版/`，复制到手机 `Android/media/org.telegram.messenger/LSPilot/Plugin/` 目录并重启 Telegram 即可。

APK 模块版位于 `app/`（libxposed API 102），**实验性，暂不推荐日常使用**——App 与模块的跨进程通信方案仍在迭代。

[更新日志](CHANGELOG.md) · 作者 wlmosv

## 功能

- 自动学习签到按钮（点一次记住，网络层也能识别）
- 每天只签一次，成功即停；已签提示；断网补签
- Bot 回复语义判定、错误分类（永久放弃 / 限流 60s / 指数退避）
- 多账号感知、双触发去重
- `/jmb` 管理菜单：目标列表 / 添加 / 删除 / 立即签到 / 运行日志 / 设置
- 深浅色主题适配

## LSPilot 插件安装（jmb界面版）

1. 手机开启 LSPilot 模块（作用域含 Telegram）
2. 把 `LSPilot-plugins/jmb界面版/` 整个目录放到：
   `Android/media/org.telegram.messenger/LSPilot/Plugin/`
3. 完全重启 Telegram
4. 任意聊天输入 `/jmb` 打开管理菜单

## 构建 APK（实验版）

需要 JDK 21 + Android SDK 36。`gradle.properties` 里的
`android.aapt2FromMavenOverride` 是 ARM64 容器里用 qemu 跑 x86 aapt2 的转译配置，
正常环境请删掉这一行。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
