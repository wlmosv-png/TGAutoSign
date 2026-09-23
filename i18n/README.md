# TGAutoSign 国际化（i18n）维护指南

> 目标：**UI 文案漏翻译 = 构建失败**，而不是上线后靠用户逐条反馈。

## 文件

| 文件 | 作用 |
|---|---|
| `en.tsv` | **翻译真相源**（中文⇥英文）。`--apply` 后自动回写，手改也行 |
| `en_short.tsv` | **短词表**：空间紧张的等宽 chip 用（`Lang.trShort` 优先取这里） |
| `en.missing.tsv` | 待译清单（`--scan` 生成；填上 TAB 后的英文即可） |
| `check_i18n.py` | 静态检查器，**已接进 `build.sh` 门禁** |
| `sync_dict.py` | 字典同步（`--scan` / `--apply`） |
| `selftest.sh` | 门禁回归自测：注入 6 类违规，确认都被拦住 |

## 加文案的标准流程

1. 源码里正常写中文，**中文布局一个字都不动**
2. `python3 i18n/check_i18n.py` → 它告诉你缺什么、在哪一行
3. 出口包 `Lang.tr("整句")`，带变量用 `Lang.tf("模板 {0}", v)`
4. 空紧处用 `Lang.trShort("短词")`（英文会取 `en_short.tsv` 里的短词）
5. `python3 i18n/sync_dict.py --scan` → 生成/更新待译清单
6. 填 `en.missing.tsv` 的英文（TAB 后面）
7. `python3 i18n/sync_dict.py --apply` → 回写 `Lang.java` **并落盘两个 tsv**
8. `./build.sh` → 不通过就构建失败（临时跳过：`TGAS_SKIP_I18N=1`）

## Lang.java 三个 API

```java
Lang.tr("整句")              // 整句翻译，未命中回退中文（渐进安全）
Lang.tf("已补 {0} · 待补 {1}", a, b)   // 带变量
Lang.trShort("唤醒")          // 空间紧张处，英文优先取 EN_SHORT 短词
Lang.isEnglish()             // 是否英文模式
```

`Lang.MODE`：0 跟随系统 / 1 中文 / 2 英文。

**日志正文保持中文**（排障对照用），只翻译界面文案。

## 检查器的 7 类判定

| # | 判定 | 说明 |
|---|---|---|
| 1 | 出口实参裸中文字面量 | `setText` / `setHint` / `setTitle` / `setMessage` / `setPositiveButton` / `setNegativeButton` / `setNeutralButton` / `createChooser` / `sendSavedMessage` 这些出口的文案实参没包 Lang |
| 2 | 出口方法内部过 Lang | 支持重载 + 递归转调 |
| 3 | **赋值型** | `String badge="未到点"; ... setText(badge)` |
| 4 | 字典缺条目 | `Lang.tr` 用了 `en.tsv` 里没有的键 |
| 5 | **辅助方法体内裸中文** | 凡 `private/protected String` 方法，体内裸中文必须包 Lang |
| 6 | **碎片拼接** | 实参里 `"前缀" + v + "后缀"` 拼出来的中文，若不在 `Lang.tf` 里 → 必漏 |
| 7 | **消息生成器体内裸中文** | 返回 void 的文案生成器（`notifySummary` / `noteFailStreak`）；这些文本会发到用户 Telegram，规则 5 覆盖不到。**新增消息生成器要加进 `MSG_METHODS`** |

判定用的「需要翻译」字符范围是 `[\u2e80-\u9fff\uff00-\uffef\u3000-\u303f]`：
CJK 汉字 + **CJK 标点 + 全角字符**。
（只查汉字会漏掉 `{0}：{1}` 这种整串只有一个全角冒号的文案。）

## 出口方法白名单（`SAFE_CALLS`）

`withIconText / menuTile / addTile / sectionHeader / sortChip / badgeChip / badge /
peerChip / typeChip / targetRow / tcard / toast / toastOnce / toastDaily / adInput /
showDialog / catChip / menuItem / emptyView / simpleTextView / swRow / logChip /
addDiagRow / leadIcon / withIcon`

纯逻辑方法（体内中文只做 `contains/equals` 比较，译了会坏功能）放 `ALLOW_AUX`：
`statusRank / statusColorCol / peerKindCn / sortWeight` 等。

## 布局红线

**中文布局一个字不能动。** 英文更长，按这套处理：

- 优先 `trShort` 取短词（`立即签到→Sign`、`目标→Targets`、`唤醒→Wake`…）
- 允许两行：`if (Lang.isEnglish()) { setSingleLine(false); setMaxLines(2); }`
- 再不够就**只在英文下**收紧内边距（例：`badge()` 的 `[OON]` chip 10dp→6dp）

`Theme.TS_CAPTION = 11sp` 等宽下每字符约 6.6dp，一行大约只放得下 2 个整词 chip。

## 真机验证法

临时插 `[I18N-SELFCHECK]` 块，把一批文案的英文渲染写进模块日志：

- **必须用 `logw()`，不能用 `jlog()`** —— `jlog` 落盘有采样：
  `lv >= LV_WARN || 队列满 20 || 距上次刷盘 > 5s` 才入队，INFO 级连续输出会被丢
- 日志在 `/storage/emulated/0/Android/data/<宿主包>/files/tgautosign/run-<YYYYMMDD>.log`
- **验完必须删干净**再出包，可用下面这条确认 dex 无残留：
  ```sh
  python3 -c "import zipfile;print(b'I18N-SELFCHECK' in zipfile.ZipFile('x.apk').read('classes.dex'))"
  ```

## 改完检查器/同步器一定跑这个

```sh
sh i18n/selftest.sh     # 6/6 才算门禁有效
```

## 走过的坑（别再犯）

1. 第一版只扫字面量，漏掉 15 个自定义构建器 → 现在靠 `SAFE_CALLS` + 递归兜住
2. 字典 key 的 `\n` 必须是 Java 转义（单反斜杠+n），写成 `\\n` 会变字面量
3. 带前导空格的 chip（`" 定时"`、`" 出品"`）要单独建词条
4. `accountLabel()` 是 `"账号"+(n+1)` 运行时拼接，必须 `Lang.tf`
5. `" 出品"` 曾译成空串 → 英文显示 `(c) wlmosv`；改成 `Lang.tf("{0} 出品", sign)`
6. **检查器的手写花括号配对不跳注释**，注释里的 `{}` 会带偏 → 顺序扫描一旦跑飞，
   之后整段方法全丢（曾导致 `showDialog`/`adInput`/`emptyView` 找不到 → 假报 120 处）。
   现在改成**独立扫描**（每个声明各自配对），且先剥离注释
7. `collect_used` 的 raw 正则被写成 `r'Lang\\.t'`（原始字符串下是「反斜杠+.t」，永不匹配）
   → 恒返回 0，「字典缺条目」检查从未生效。**写工具脚本时注意 data 是字面量，别习惯性写双反斜杠**
8. 规则 5/7 曾用「当前行往前 200 字符内出现过 `Lang.tf` 就整行豁免」——附近一有 `Lang.tf`，把已修好的行改回裸中文也能溜过去（selftest 变异 6 就是这么漏的）。现在改成**精确区间判定**：字面量偏移必须落在某个 `Lang.tr/tf/trShort(...)` 调用区间内
9. `perAccountLine` 一度被错放进 `ALLOW_AUX`（当成"纯数据方法"），但它其实显示在首页统计卡片上
10. `--scan` 曾覆写 `en.missing.tsv`、`--apply` 不回写 `en.tsv` 且会重建整个 static 块
   把 `EN_SHORT` 抹掉 → 现在 `--scan` 合并、`--apply` 落盘两个 tsv 并同时输出两个 map
