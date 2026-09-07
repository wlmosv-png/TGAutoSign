# TGAutoSign

Telegram 每日自动签到 Xposed/LSPosed 模块（libxposed API 102）

- 自动学习机器人签到按钮（UI 点击 + 网络层双通道）
- 每天只签一次，成功即停，已签提示
- 断网补签：网络恢复 / 打开聊天 / 30 分钟兜底
- Bot 回复语义判定、错误分类、指数退避、多账号感知

## 构建

需要 JDK 21 + Android SDK 36（aapt2 转译配置见 gradle.properties，非 ARM64 环境请删除 `android.aapt2FromMavenOverride` 行）。

```bash
./gradlew :app:assembleDebug
```

## 下载 / 发布

- 官方模块仓库（Release APK）：https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign
- 作者：wlmosv

## 使用

1. LSPosed/Vector 启用模块，作用域勾选 `org.telegram.messenger`
2. 重启 Telegram，确认 Toast「TGAutoSign 注入成功」
3. 各签到机器人手动点一次即可自动学习
