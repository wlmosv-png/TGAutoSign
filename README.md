# TGAutoSign

Telegram 自动签到模块（libxposed API 102），带完整界面：目标管理 / 日志 / 设置 / 桌面小组件。

[更新日志](CHANGELOG.md) · 作者 wlmosv

## 功能

- 自动学习签到按钮（点一次记住，网络层也能识别）
- 每天只签一次，成功即停；已签提示；断网补签
- Bot 回复语义判定、错误分类（永久放弃 / 限流 60s / 指数退避）
- 多账号感知、双触发去重
- App 界面：主页状态卡（注入检测 / 今日进度）、目标列表管理、实时日志、设置
- 桌面小组件显示今日签到进度
- 模块与 App 通过广播桥跨进程通信（心跳 / 状态 / 命令）

## 构建

需要 JDK 21 + Android SDK 36。`gradle.properties` 里的
`android.aapt2FromMavenOverride` 是 ARM64 容器里用 qemu 跑 x86 aapt2 的转译配置，
正常环境请删掉这一行。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. LSPosed / Vector 启用模块，作用域勾选 `org.telegram.messenger`
2. 完全重启 Telegram，看到「TGAutoSign 注入成功」Toast
3. 打开 TGAutoSign App：状态卡变绿 = 已注入；目标列表里把要签的机器人手动加一遍，或直接在机器人聊天里点一次签到按钮自动学习
4. 模块同时提供 LSPilot 插件形态（`LSPilot-plugins/jmb界面版/`）：在 Telegram 里发 `/jmb` 弹出管理菜单，适合不喜欢装 App 的场景

## 下载

发布版 APK：<https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases>
