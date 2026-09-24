# 更新日志

## 1.5.8 (121) — 2026-09-24

### 修复 · Fixed
- **子面板弹出来变成亮色，已修 · Panels turned light — fixed**
  **根因**：面板是 Telegram 的对话框，弹出时在窗口上盖了一层半透明遮罩；主题判定采的是**整屏平均色**，
  把遮罩一起算进去了 —— 深色底 + 白遮罩平均成灰，亮度越过阈值就判成「浅色」，于是面板整体变亮。
  遮罩会**持续存在**（不是瞬时帧），所以单靠缓存或"连续两次一致"都救不了。
  **修法**：采样改取内容区（不含对话框遮罩）；另保留两道保险 —— 同一页面两次采样不一致时沿用上次结论、缓存按页面分开记。
  *Root cause: panels are Telegram dialogs, and opening one lays a translucent scrim over the window. Theme detection sampled the **whole screen's average colour, scrim included** — dark background plus white scrim averages to grey, crosses the brightness threshold, and flips the verdict to "light", so the panel turns light. The scrim **persists** (it is not a transient frame), so neither caching nor a two-sample confirmation could fix it. **Fix**: sampling now reads the content view, which excludes the dialog scrim, keeping two safeguards — reusing the previous verdict when two samples disagree, and per-page caching.*
- **主题日志改为「每次变化都记」 · Theme logging now records every change**
  以前只在首次判定时写一条日志，之后无论判错多少次都不留痕迹 —— 这个 bug 因此一直查不出来。现在一变化就记。
  *It used to log only the very first verdict, leaving no trace of later mistakes — which is why this bug stayed invisible. Every change is logged now.*
- **「未识别 bot 回复」不再刷红 · "Unrecognized bot reply" is no longer an error**
  点充值 / 菜单 / 查询类按钮时，bot 回的本就是业务内容，**永远不可能匹配签到词**，
  那是正常情况而不是错误。现在这类回复只记调试日志；只有回复**看起来像签到结果**
  但词表没覆盖时，才提示可以补词。
  *Tapping a recharge / menu / lookup button naturally gets business content back, which can never match check-in keywords — that is normal, not an error. Such replies are now debug-only; only replies that look like a check-in result but miss the word list raise a hint to add a word.*
- **同一次点击不再被重复处理 · A single tap is no longer processed multiple times**
  Telegram 会把同一条更新通过多个数据源投递，实测同一次点击日志重复 2–4 遍（「标记今日已签」出现 3 次、判定重复 2 次），既刷屏又让每日计数虚高。现在按「目标 + 消息 id + 回复内容」在 20 秒窗口内去重，乐观标记也已幂等。
  *Telegram delivers the same update through several sources; a single tap was logged 2–4 times ("marked signed today" three times, verdict twice), spamming the log and inflating daily counters. Now deduplicated on target + message id + reply text within a 20-second window, and the optimistic mark is idempotent.*

- **i18n 门禁补上白名单漏洞，并修掉 18 处历史漏译 · i18n gate covers the whitelist gap; 18 missing translations fixed**
  以前只要是走封装方法（`withIconText` / `menuItem` / `tcard` / `adInput` 等）的文案，门禁就**只检查方法内部有没有过 Lang，不检查译文在不在字典里** —— 新加的文案忘进字典完全不会被拦。现在白名单方法**也查字典**，并据此补上了 18 处英文用户此前会看到中文的地方（自动识别的选项、群签到说明、复制目标说明、调试空态、收藏夹摘要标题等）。
  *Previously any text routed through a helper (`withIconText`, `menuItem`, `tcard`, `adInput`, …) only had to pass Lang internally — the gate never checked whether a translation existed, so a brand-new string could ship untranslated. Helper methods are now dictionary-checked too, which surfaced and fixed 18 places where English users still saw Chinese.*
- **门禁能抓住「用了封装方法但忘了进字典」 · The gate now catches "helper used, dictionary forgotten"**
  已用反向测试验证：故意注入一条未进字典的文案会直接构建失败并指到具体行号。
  *Verified by a negative test: deliberately injecting an untranslated string fails the build with the exact line number.*

- **后台心跳按需降频，夜里不再每 45 秒醒一次 · Heartbeat slows down when there is nothing to do**
  以前无论白天黑夜、不管今天签没签完，心跳都固定每 45~60 秒唤醒一次（一天约 1,900 次），
  频繁唤醒会妨碍系统进入深度休眠，用户侧表现为耗电与机身发热。现在分三档：
  窗口内且有未签目标 = 45 秒；窗口内但今天已签完 = 10 分钟；窗口外 = 15 分钟。
  窗口**进入**时刻仍由原有的精确闹钟负责，签到准时性不受影响；断网恢复与账号切换照旧立即触发。
  诊断包会显示当前档位。
  *The heartbeat used to wake every 45–60 seconds around the clock (≈1,900 times a day) even at night or after everything was signed, which keeps the device out of deep sleep and shows up as battery drain and heat. It now has three tiers: 45 s inside the window with pending targets, 10 min inside the window once all are signed, 15 min outside the window. Window entry is still handled by the existing precise alarm, so punctuality is unaffected; network-recovery and account-switch triggers still fire immediately. The diagnostics package shows the current tier.*

- **定时任务不再重复排队 / 丢任务 · Scheduled tasks no longer pile up or get lost**
  排新任务时只覆盖了引用、**没取消旧回调**，于是：旧任务仍留在消息队列里，可能和新任务
  **同时触发、同一目标被签多次**；而且旧任务执行时会把引用清成 null，让调度器以为
  "没有待发任务"从而再排一个 —— 任务越滚越多。现在排新任务前先取消同类型的旧任务。
  *Scheduling a new task only overwrote the reference without cancelling the old callback, so the old task stayed queued and could fire alongside the new one (signing the same target twice); worse, when it ran it nulled the reference, making the scheduler think nothing was pending and enqueue another — tasks multiplied. The old task of the same kind is now cancelled first.*
- **面板刷新触发不再与心跳重复发送 · Panel-refresh trigger no longer double-sends with the heartbeat**
  「面板已更新 → 立即补签」只防了自身重入，防不住心跳/定时刚给同一目标发过。现在会先检查该目标是否正在发送中。
  *"Panel updated → sign now" only guarded against its own re-entry, not against the heartbeat or scheduler having just sent the same target. It now checks whether that target is already in flight.*

- **机器人名字只显示一次数字 ID，之后再也取不到 · Bot names stuck as numeric IDs after the first attempt**
  名字缓存无论成功失败都写入 —— 冷启动时第一次必然取不到（bot 还没进宿主内存），
  于是把 `null` 缓存住，之后**永远显示数字 ID**，重开列表也不会重试。
  现在只缓存取到的名字，取不到就下次再试。
  *The name cache stored its result regardless of success. On a cold start the first lookup always fails (the bot isn't in the host's memory yet), so `null` got cached and the numeric ID stuck forever — reopening the list never retried. Only successful lookups are cached now.*

- **「今天已签到」这类回复现在认得出了 · Common "already signed today" phrasings are recognised now**
  内置已签词表原先只有「今日已签 / 已经签 / 已签到」等少数写法，遇到「今天已签到」「您已签到」
  「今日已打卡」「签到已完成」「签到获得积分」这些常见表述就判不出来，用户只能自己去加词。
  已把中文 12 种、英文 6 种常见写法补进内置表（成功词同样补了「签到完成」「签到获得」等）。
  *The built-in "already signed" list only had a few phrasings, so common variants such as "今天已签到", "您已签到", "今日已打卡", "签到已完成" or "签到获得积分" were not recognised and users had to add them by hand. 12 Chinese and 6 English variants are now built in, and the success list gained "签到完成" \/ "签到获得" too.*
- **「本条不是签到结果」不再让人误以为失败 · "Not a check-in result" no longer reads like a failure**
  机器人一次交互常发多条消息（先菜单\/广告、后结果），而日志对第一条就写「没识别到签到响应，已忽略」，
  用户看到以为失败了 —— 其实后续回复会正常命中并计入已签。现在措辞改为「本条不是签到结果（继续等后续回复）」，明确它不是最终结论。
  *A bot often sends several messages per interaction (menu\/ad first, result after), yet the log said "no check-in response recognised, ignored" for the first one, which reads like a failure — while the later reply does match and count. It now says "this message isn't a check-in result (still waiting for further replies)".*

- **「自动判定」被关掉时不再静默 · Auto-detection being off is no longer silent**
  判定开关若被关掉，日志只说一句"仅记录"，用户看到的是"bot 明明回签到成功、目标却没变绿"，
  完全无从下手。现在会**弹一次提示**并指向设置项，日志也提级为警告。
  *With detection off the log only said "recording only", leaving users staring at a bot that clearly replied "check-in successful" while the target stayed unsigned. It now shows a one-time prompt pointing at the setting, and logs at warning level.*
- **新装用户点 bot 按钮学不会目标（重要） · New installs couldn't learn from button taps (important)**
  「按钮学习」默认值写成了关闭，而回调学习同样受它管 —— 新装用户按 README 说的「点一次按钮」永远没反应。现在默认开启。
  *"Button learning" defaulted to off, and callback learning was gated by it too — so new users following the README's "tap once" never got anywhere. Now on by default.*
- **捕获模式在 Nagram \/ 官方版上无效 · Capture mode did nothing on Nagram \/ official**
  这两个客户端点击按钮不走已 hook 的方法，捕获只剩网络层入口，此前该入口只记录日志、不接管。现在武装状态下网络层直接弹绑定面板。
  *On these clients the tap never reaches the hooked methods; capture had only the network path left, which logged but didn't take over. Now it opens the binding panel directly.*
- **拒绝原因被谎报 · Deny reason was misreported**
  学习失败时日志固定输出「被排除规则或关键词过滤」，真实原因（最常见是「按钮学习已关闭」）被吞掉。现在打印具体原因。
  *Failures always logged "blocked by rules or keywords", swallowing the real cause. The actual reason is now printed.*
- **界面显示的账号号错乱（如显示「账号10」） · Wrong account number shown (e.g. "account 10")**
  宿主切号时会把 `selectedAccount` 写成越界值（实测只登录 2 个账号却读到 9），
  而该值直接决定 `acc{N}_` 数据分区 —— 越界会让目标与已签记录全部落进空分区，
  界面表现为「配置凭空消失」。现在越界一律钳到 0 并记一条日志。
  *On account switch the host can write an out-of-range `selectedAccount` (measured: 9 while only 2 accounts are signed in). That value picks the `acc{N}_` data partition, so an out-of-range value sends all targets and signed-state into an empty partition and the UI looks like the config vanished. Out-of-range values are now clamped to 0 and logged.*
- **日志账号前缀过期 · Stale account prefix in logs**
  `[账号N]` 取自上次签到轮次的缓存，用户没切号也会标错账号。现在带实时账号，并附「武装时账号」用于比对。
  *The `[account N]` prefix came from the last check-in round's cache and could be wrong even without switching. Logs now carry the live account plus the arming account for comparison.*

- **跨午夜签到窗口现在真的能用 · Windows that cross midnight actually work now**
  以前窗口写成 `22:00-02:00` 会被当成非法：定时模式**整晚不排期**，非定时模式则把"窗口为空"当"不限"，变成全天都能签。现在两者都按跨天正确处理。
  *A window like `22:00-02:00` used to be treated as invalid: scheduled mode never planned anything, and non-scheduled mode treated "no window" as "no limit" and would sign all day. Both now handle midnight crossing properly.*
- **跨客户端同步：账号不再串位 · Cross-client sync no longer mixes up accounts**
  两台手机的账号顺序不一致时（A机 甲/乙、B机 乙/甲），按索引合并会把甲的签到记录写到乙头上。现在按账号自身的 user id 配对，本机没登录的账号直接跳过。
  *When the two devices order accounts differently, index-based merging wrote account A's signed-state onto account B. Pairing is now by the account's own user id, and accounts not signed in locally are skipped.*
- **收到其他客户端的配置后会重排时刻表 · Applying a synced config replans the timer**
  以前只改字段不重排，当日时刻表还按旧窗口跑，定时签到会跑到窗口外。
  *Previously only the fields changed, so the day's schedule still used the old window and timed check-ins could fire outside it.*
- **发送中状态的有效窗口与失败撤销对齐（90 秒 → 30 分钟） · Pending window now matches the failure-undo window**
  失败撤销特意等慢 bot 到 30 分钟，但发送中状态 90 秒就过期，导致 3 分钟后才回话的 bot 走不到撤销逻辑、失败被记成成功。
  *The undo path waited up to 30 minutes for slow bots, but the pending state expired after 90 seconds, so a bot replying after 3 minutes never reached it and a failure was recorded as success.*
- **跨端同步节流 · Cross-client sync is throttled**
  心跳每 45 秒都会读写同步文件，一小时白写 80 次盘。现在 5 分钟一次，改设置时立即推送。
  *The heartbeat synced files every 45 seconds (80 pointless writes an hour). Now every 5 minutes, with an immediate push when settings change.*
- **发版前自动检查测试标记 · Build checks the debug patch tag**
  `PATCH_TAG` 非空（本机测试标记没清）会直接构建失败，不再靠人记。
  *A non-empty `PATCH_TAG` now fails the build instead of relying on memory.*

### 新增 · New
- **判定词改成开关式 · Verdict words became switches**
  原来要求"自己往里加词"，等于把责任推给用户。现在默认用内置词表判定，另给两个开关：
  「自动判定成功 / 失败」总开关，以及「使用我的自定义词（叠加在内置之上）」—— 不勾选时输入框隐藏。
  *It used to ask you to add your own words. Built-in words judge by default now, with a master "auto-detect success / failure" toggle and "use my own words (on top of the built-ins)" — the custom fields stay hidden until you opt in.*
- **新增「宽松模式（来者不拒）」 · New "loose mode" switch**
  **开** = 点什么学什么（不再按关键词过滤）+ **只要机器人有回复就算成功**；
  **但命中明确失败词（活动已结束 / 请先关注 / 未绑定 等）仍判失败** —— 避免 bot 挂了也显示绿色。
  **关**（默认）= 完全按原有规则判定。开关在「设置 → 判定机器人回复」里。
  *For bots whose wording never matches the built-in keywords. **On**: learn whatever you tap (no keyword filter) and **treat any reply as success**. **Off** (default): judge by the existing rules. The switch lives under Settings → Judging bot replies.*



### 可靠性 · Reliability

- **补上纯逻辑单测，并挂进构建门禁 · Pure-logic unit tests, wired into the build gate**
  签到窗口 \/ 退避 \/ ID 规范化 \/ 回复判定等纯逻辑抽到 `SignLogic`，115 条断言，
  出包前自动跑，不过就构建失败。此前核心逻辑一行测试都没有。
  *Window \/ backoff \/ ID normalization \/ reply-verdict logic moved into `SignLogic` with 115 assertions, run automatically before packaging. The core previously had no tests at all.*
- **bot 回复认不出来时不再静默 · Unrecognized bot replies are no longer silent**
  以前三张关键词表都不命中就什么都不做，用户看到的是「点了、发出去了、没反应」。
  现在会记日志并在诊断包留下该条回复原文，同时提供「回复判定词」设置可自行加词。
  *Previously an unmatched reply did nothing at all, so the UI just looked unresponsive. Now it is logged, the raw reply is kept in the diagnostics package, and custom verdict words can be added in Settings.*
- **关键路径的异常不再被静默吞掉 · Exceptions on critical paths are no longer swallowed**
  36 处「catch 后什么都不做」改为记账（签到发送 \/ 回复判定 \/ 心跳 \/ 学习 \/ 同步等），
  诊断包的「静默异常」从此有实际内容。
  *36 silent catches on critical paths (sign send, reply verdict, heartbeat, learning, sync) now record instead of vanishing, so the diagnostics counter becomes meaningful.*

### 诊断 · Diagnostics

- **诊断包新增学习与 hook 状态 · Diagnostics now reports learning and hook state**
  学习开关（按钮 \/ 网络 \/ 关键词过滤 \/ 关键词表）、排除配置（排除的 bot 数 \/ 排除规则 \/ 捕获武装状态）、UI 按钮 hook 状态（挂载数 + 实际触发数）。此前完全不可见。
  *Learning switches (button \/ network \/ keyword filter \/ keyword list), blocklist config, and UI hook state (methods hooked + actual fire count). Previously invisible.*
- **诊断包头部显示补丁标记 · Patch tag shown in the diagnostics header**
  本机测试包用，正式版为空。
  *Used by local test builds; empty in release builds.*
- **诊断包新增账号字段原始值 · Diagnostics now shows the raw account field**
  `selectedAccount` 原始读数 + 已登录账号数，越界时直接标注。用于确认宿主切号的写入行为。
  *Raw `selectedAccount` reading plus the signed-in account count, flagged when out of range — to confirm what the host writes on account switch.*

### 维护范围 · Supported clients

仅主要维护 `org.telegram.messenger`（官方版）、`xyz.nextalone.nagram`（Nagram）、`com.exteraless.app`（ExteraLess）。
*Maintained: official Telegram, Nagram, ExteraLess. Other forks still inject if their flag classes are intact, but aren't officially maintained.*

## 1.5.7 (120) — 2026-09-23

### 新增

- **界面全面国际化（英文）**：内置英文界面，英文设备上开箱即用，无需任何设置。
  设置页新增「界面语言」三态开关（跟随系统 / 中文 / English）。
  覆盖主菜单、设置、目标列表、补签列表、教程、日志页按钮、分享与对话框，
  以及**发到收藏夹的每日摘要与连续失败告警**。
- **多账号数据隔离修复**：待签记录按账号前缀区分，两个账号用同一个 bot 时不再互相顶掉；
  新增「账号一览」界面，可逐账号查看与启停。

### 界面

- 英文下徽章与短按钮改用短词（`立即签到→Sign`、`目标→Targets`、`唤醒→Wake` 等），
  并允许折行，避免英文变长挤坏等宽布局。**中文布局未做任何改动。**
- 列表中「群 12345」这类占位文案改走模板翻译（英文显示 `Group 12345`）。

### 修复

- **签到倒计时混排**：定时模式下「约 X 分钟后」是拼接字符串，外层模板过了翻译但内部没有；
  现在整句走模板（`08:30 · in ~23 min`）。
- **署名残缺**：英文下 `出品` 词条曾译成空串，导致只剩 `(c) wlmosv`；改为整句模板。
- **诊断页整块未译**：诊断行的宿主包、当前账号、目标数、标志类四项直接拼接输出，全部改走模板。
- **弹窗按钮与分享标题未译**：删除确认框的「删除 / 取消」、系统分享面板标题。
- **日志页筛选与空态未译**：「目标：全部 / 目标：xxx / 没有匹配「xxx」的日志」。
- **进度条永远走不满**：冻结 / 排除的目标被算进了分母，导致活跃目标全部签完仍显示未满。
  现改为**只统计活跃目标**，冻结与排除的不参与计算（列表里仍可见，虚化并下沉到底部）。
- **「排除的 bot」没有真正生效**：原来只阻止被学习，已收录的目标照签不误。
  现在被排除的 bot 一并停止签到。
- **冻结条目视觉不一致**：原来只有「排除的 bot」会虚化，冻结的没有；现统一虚化并下沉。
- 版本号三处不同步的隐患：源码树 `module.prop` 曾落后于实际发布版本，现补上并加入构建期四查守卫。

### 维护范围

本版仍**仅对以下三个客户端做主要维护**：

| 客户端 | 包名 |
| --- | --- |
| Telegram（Play / 默认渠道） | `org.telegram.messenger` |
| Nagram | `xyz.nextalone.nagram` |
| ExteraLess | `com.exteraless.app` |

其它 Telegram fork 若标志类齐全仍会注入，但不再作为主要维护对象，适配不保证及时。

## 1.5.6 (119) — 2026-09-22

### 新增

- **三层黑名单**：给不想签的目标上锁 ——
  - **排除规则**（关键词 / 正则）：命中 bot 回复正文或按钮文案就不学习；一行一条、`#` 注释、`/…/` 当正则
  - **排除的 bot**：整只 bot 不学习、不自动签到，可多选
  - **目标冻结**：永久停签某一条目（与「暂停一周」区分）
- **排除管理**：主菜单独立入口，规则 · 排除列表 · 待确认集中一处；已排除的 bot 独立分组显示，目标删了也看得到
- **网络学习需确认**：网络学习命中的目标先进「待确认」，手动点「加入」才真正添加，防验证码类 bot 误加
- **目标可读性**：支持自定义备注名；列表显示 `名字 + @username` 副标题，不再裸露数字 ID
- **bot 矢量图标**：新增机器人图标，替换 emoji 与人头图标

### 界面

- 冻结 / 已排除 在目标列表有徽章标识，已排除条目整行虚化
- 对话框不再叠层，操作后即时刷新

### 修复

- **官方版 / Nagram 按钮学习失效**：改为按「参数类型」结构匹配 hook，不再依赖被混淆的方法名
- **回调签到误判**：前置命令发出后等面板真的刷新再点按钮（原来盲等 1.2 秒，bot 慢就面板过期）
- `TL_messages_getBotCallbackAnswer` 无 `hash` 字段导致网络层学习崩溃
- 手动添加被排除的 bot 时，自动解除排除
- 收藏夹汇总不再「签第一个就发」，等所有目标出结论

### 维护范围

自本版起，**仅对以下三个客户端做主要维护**：

| 客户端 | 包名 |
| --- | --- |
| Telegram（Play / 默认渠道） | `org.telegram.messenger` |
| Nagram | `xyz.nextalone.nagram` |
| ExteraLess | `com.exteraless.app` |

其它 Telegram fork 若标志类齐全仍会注入，但不再作为主要维护对象，适配不保证及时。

### 兼容

- Telegram 12.10.3 起按钮点击方法与 `AlertDialog$Builder` 的 setter 一样被方法名混淆（官方版 `didPressedBotButton` → `g`，Nagram → `f` / `h`）；模块改为**按参数类型结构匹配**定位并 hook，官方版与各 fork 通用
- 三客户端回调签到全流程实测通过

## 1.5.5 (118) — 2026-09-21

### 新增

- **群 / 频道签到**：群 ID（`-100…`）支持签到与回复判定；时刻表、补签、失败告警与私聊 bot 一致，列表显示群名或备注名
- **签到结果通知**：每账号每天一条摘要发到自己的收藏夹，不依赖系统通知权限；设置 → 通知（可「只通知失败」）
- **连续失败告警**：同一目标连续失败 3 天告警一次，签到成功即清零
- **跨客户端同步**：官方版 / Nagram / ExteraLess 之间同步目标与签到状态，任一客户端签的都算数；在哪改配置以哪为准
- **多账号独立配置**：签到窗口、定时、错开间隔、补签设置改按账号独立存储，互不覆盖
- **补签截止**：补签与签到窗口解耦 —— 窗口结束后仍补到该时刻（默认 23:00），当天不再提前作废

### 界面

- **图标矢量化**：新增 `Icons.java`，24×24 网格代码绘制，替换 26 种 emoji；不新增任何资源文件，各机型渲染一致
- **分类直达**：主菜单加「全部功能」横滑一行（目标 / 数据 / 系统 / 帮助 / 维护），取消「更多功能」二级页
- **教程重写**：按使用意图重写 16 节，补齐通知、主题、暂停、重试上限等此前漏写的说明
- **日志页**：固定「最新在上」，打开即定位最新；按天切分落盘（512KB × 7 天）；异常带堆栈、每条带账号与轮次上下文
- **诊断包**：新增运行环境与目标状态表
- **自绘对话框**：三宿主外观统一，不再依赖宿主 `AlertDialog` API
- **主题判定**：改为采样界面真实颜色定深浅，宿主无关、切主题即时跟随；新增手动切换（自动 / 日间 / 夜间）
- 目标类型可视化：列表左侧色条 + 类型徽章（群 / bot）；补签列表改两行式避免名称截断

### 修复

- 手动发指令被误判为已签，导致自动签到跳过该目标
- bot 回「已签过」不落盘，日历不绿且心跳每 90 秒重发
- 群签到发出后收不到结果判定（发送者与 peer 不一致被过滤）
- bot 回复「签到失败」时误记为成功，统计失真
- 重试上限失效；失败撤销已签的时间窗 10 → 30 分钟
- 重复定时器：多次触发叠加到点任务，多目标时出现重复签到
- 定时模式下「一开 TG 就立即签第一个」，未按时刻表执行
- 日志只读当天文件，历史记录整批读不到
- 日志「回到最新」方向相反（滚到最旧）
- 主界面停留几秒后消失（对话框栈未出栈）
- 切换账号时挂起定时器仍按旧账号时刻表触发
- 跨客户端同步改按条目配对，与账号索引解耦

### 兼容

- 适配 Telegram 12.10.3：`AlertDialog$Builder` 方法名被混淆（`setTitle` → `g` 等），对话框改为自绘规避
- 主题判定修复 Nagram 不跟深浅、ExteraLess 一直降级系统主题
- 修复 Nagram 12.8.1 回调签到 `InstantiationException`

## 1.5.4 (117) — 2026-09-20


### ⏰ 定时签到体系（全新）
- **定时签到开关**：开启后只在签到窗口内签到，窗口外所有自动触发（启动/网络/轮询/面板）一律不动作；关闭=全天自动补签
- **签到时间选择器**：点按钮弹系统时间选择器，免手输（默认 08:30-20:30）
- **当日时刻表**：窗口按目标数均分时段，每目标在自己时段随机取时刻，互不重叠、当天固定、重启不重摇
- **错开间隔可配置**：0=自动均分；设 N 分钟则相邻目标至少隔 N 分钟，防风控节奏自定
- **今日计划卡片**：每目标一行——已签显示实际签到时刻（绿）、待签显示计划时刻（琥珀）、窗口外标「明日排」
- 新增目标自动清当日时刻表重排；时刻表生成改用 org.json（修转义隐患）

### 🐛 修复
- **启动补签遵守窗口**：启动 10 秒补签不再 force 绕过签到窗口
- **面板事件补签遵守窗口**：bot 面板更新不再窗口外乱签
- **多目标同时齐发**：改为 3~10 秒随机间隔逐个发送

### 📄 日志系统升级
- 🎯 按目标过滤（弹窗选择，不再是循环切换）
- 📋 一键诊断包：错误+警告+最近 50 条+版本/窗口/目标摘要，复制即发
- ⬇ 加载更多（列表顶部卡片，上限 8000 条）
- 🎨 配色体系重做：时间灰列 + 级别色文字 + 左侧色条 + 错误/警告行淡色底，亮暗双套
- 日志页状态条显示当前筛选

### 🖌 界面与主题
- **自绘终端卡片对话框**：告别系统框（标题+✕+内容+底部按钮，深浅双套）
- **主题判定统一+多宿主适配**：ExteraLess 走 isCurrentThemeDark；官方/Nagram 12.10.3 走 o6.A0().q()
- **设置页美化**：四分组卡片 + 图标标签 + 开关两行 + 主色保存按钮
- 主界面状态卡加「⏳ 下次签到」倒计时（定时模式）
- 使用教程全面更新（定时/窗口/间隔/计划/日志排障）

### 🧹 维护
- 删除死代码 targetRowOld / menuItemOld；新增 ui-smoke.sh UI 冒烟测试（15 项断言）

## 1.5.3 (116) — 2026-09-19

### 🐛 修复
- **日历漏绿**：自动签到成功后连续天数与日历不同步（成功回调只写 last_ 漏记日历），列表已签✅但日历不绿；已同步记录并**回填历史**——旧版签过但没进日历的日子自动补绿
- **子界面叠层/闪烁**：进子界面层层叠新窗口、退出要按很多次、光标杵着不动；恢复单窗口导航（主菜单↔子界面正常返回、列表原地替换），快速重开时光标也正常闪烁

### 🎨 界面重排
- **主菜单分组**：18 项按「核心 / 工具 / 数据 / 系统 / 维护」分 5 组，高频置顶、危险操作沉底
- **目标列表行重排**：标题过长省略 + 徽章固定；第二行「状态(带色) · 指令 · 上次时间」三列对齐；状态色区分（已签=绿、重试/退避=琥珀、放弃=灰）
- **目标列表排序**：未签置顶 / 按名称 两种，选择持久化
- **标题动效 4 连**：每次打开轮换「霓虹呼吸 / 逐字波浪 / RGB 流光 / 键盘敲击」，深浅色双主题契合
- 类型徽章统一 [回调]/[指令] 等宽文本

### ✨ 新功能
- **📣 加入群组**：主菜单「系统」组新增入口，一键跳 TG 交流群

### 🔌 兼容
- 宿主白名单新增 **NextAlone Nagram**（xyz.nextalone.nagram）
- Nagram XF 30dcd6c 构建注入导致启动无响应，已版本锁定自动跳过注入

## 1.5.2 (115) — 2026-09-18

### ✨ 新功能
- **每日签到窗口**：设置里可配「每日签到窗口」（如 08:00-10:00），窗口外自动触发全部跳过、窗口内准点补签；秒级精度排程 + 0~2 分钟随机偏移防风控；「进入窗口」触发豁免节流，不被其他操作挡住
- **连续签到日历**：首页显示连续签到天数 + 最近 14 天打卡格子（绿=已签，1=13 天前 · 14=今天）；跨天自动累加、断签归零，按账号隔离；旧版签到记录自动兼容
- **预设模板**：/jmb 主菜单新增「📚 预设模板」，内置示例模板点选即改即加（bot ID + 指令可编辑）

### 🎨 界面与体验
- 主菜单 18 项改双排网格卡片 + 按压缩放反馈，滚动长度减半；修复新版 Telegram 主面板内容无法滚动
- 面板开启动效：顶部扫光、命令行逐字打字与光标闪烁、状态呼吸灯、状态卡 / 日志卡渐进显现、菜单格错落亮起；标题字符动态效果；15 秒内重复打开面板直接秒开
- 连续签到文案与关键词表优化（关键词清空自动回落新默认）

### 🔌 兼容与适配
- **Telegram 12.10.3**：新版重构了对话框 Builder（setTitle / setView 移除），模块自动降级为主题化系统框，功能不受影响
- **ExteraLess**（ExteraGram fork，com.exteraless.app）加入白名单与声明式作用域，静态核对 + 真机实测
- 启动期保护：注入后 30 秒内自动处理静默放行，避免高频宿主启动时卡顿（针对 Nagram XF 30dcd6c 构建的启动无响应问题，该构建暂不注入）
- sendRequest 快速路径（非相关请求零成本放行）、防重入、账号同步节流

### 🔧 其他
- 终端风新图标（深色渐变底 + 青色纸飞机）；模块元数据规范化（xposeddescription 改资源引用 + 中文简介）
- 版本三处同步 115 / 1.5.2（CI 版本守卫护航）
## 1.5.1 (114) — 2026-09-18

### 界面：日间模式适配（终端风色板主题化）
- 终端风界面全部配色改为跟随 Telegram 主题：日间=浅灰蓝卡片 + 深色文字 + 高对比描边；暗色=原配色不变
- 修复日间模式下深色卡片嵌浅色背景的割裂感、青色低透明度描边几乎不可见的问题
- 分隔线 / 灰色提示 / 编辑框等弱对比元素统一走主题色板
- 深浅色判定改为跟随 Telegram 主题开关（原跟随系统：TG 内切日间、系统仍是暗色时界面不变色）

### 修复
- 更新检查"永远显示可更新"：UpdateChecker.VERSION_CODE 停在 112，远端 113 > 112 导致永远判定有新版本；本次与 build.gradle / module.prop 统一到 114 / 1.5.1
## 1.5.0 (113) — 2026-09-17

### 🔴 修复：回调签到全链路（本版核心）
- **TL 协议 flags 修复**：`TL_messages_getBotCallbackAnswer` 未写 `flags` 位导致 `data` 可选字段未序列化，服务器一律回 `DATA_INVALID`（此前换任何 bot、刷任何新面板都失败）。现在请求字段布局与官方客户端一致。
- **Live Panel 面板实时引擎**：模块持续跟踪每个 bot 的最新面板（bot 发带键盘消息、你手动点按钮时自动采集）→ 重放前先在当前面板按「data 精确 > 文本一致 > 最近点击」自适应匹配，用**最新** msg_id + data；data 每次都变的 bot 同样跟随。
- **一次性按钮自愈**：同消息按钮被点过后再点会 `DATA_INVALID`，此时自动拉新面板（发前置命令）重试一次，成功落地新 msg_id，不再闷头退避。
- **面板采集链路解包**：`processUpdate*` 首参是 Updates 容器而非单条 update，解包后逐条处理——修复了「bot 回复判定 + 面板采集」这条链从装机起从未运行的问题。
- **按钮类型识别改为功能探测**：能取到回调 data 即判定为回调族，不再依赖类名猜测——新架构按钮不会再被误学成文字目标。
- **语义判定三态**：bot 回复分为「成功 / 已签过 / 失败」三态；失败自动撤销已签并按指数退避重试。
- **BOT_RESPONSE_TIMEOUT 长退避**：bot 不即时回执时 15 分钟后再试、当日最多 2 次，不再连打空转。

### ✨ 新功能
- 回调绑定查重改为「刷新 msg_id/指纹」而非静默跳过；data 为空的按钮在捕获框分级显示（文本/链接按钮提示转文本目标）
- 目标可**暂停一周**（📋 条目操作 ⏸）
- **单账号每日动作上限**（默认 60，`jmb_cap` 可调，防风控）
- 签到动作加入 300–1200ms 随机间隔，平滑连发特征
- `/jmb log`（最近 400 行日志一键复制到剪贴板）、`/jmb update`（无视冷却强制检查更新）
- 成功/失败按账号统计（`acc{N}_ok/_err`）显示在首页
- 前置命令**模板一键添加**（/start、/menu、/qd、/checkin、签到、开始、菜单，点选追加可自定义）
- 设置面板「每日重试上限」改为 −/+ 步进器

### 🎨 界面（终端 / 黑客风大改版）
- 主界面全新暗色终端风格：等宽字体、霓虹青/绿描边、圆角面板
- 头部状态徽章 `[ON]/[OFF]`（按钮学习/网络学习/过滤/唤醒）
- **实时日志卡**（tail -f：面板开着每 4 秒自动刷新最近 4 条，无需重开面板）
- 快捷命令按钮行：▶ 立即签到 / → 目标 / ⏁ 日志 / ⚔ 自检
- 标题行右上 `[START]` 一键打开作者 GitHub
- 菜单项全局统一暗色终端卡片风
- 作者标识保留（由 wlmosv 出品），仅移除设置项说明里的「署名」字样

### 🧹 清理
- 移除 docs/ 下 v1.2.1 时代的三份一次性发布文档
- README / CHANGELOG 同步更新至 1.5.0

## v1.4.3 (versionCode 112) - 自诊断能告诉你"为什么没注入" + 作用域可自动申请

- **自诊断逐项判定**：三个 Telegram 标志类（ConnectionsManager / ChatActivityEnterView / UserConfig）各自在不在，
  缺哪个直接写出来；不注入时日志也会说明原因，不再只有"跳过"两个字。
- **新增：作用域自动申请（libxposed 102 模块服务）**。首次注入后模块会问框架："这些已知 Telegram 客户端还没在我的
  作用域里，能加上吗？"框架侧以**系统通知**让你一键确认（模块本身没有、也不会去写 LSPosed 的数据库）。
  之后换手机、出新 fork、或你重装客户端导致作用域丢失，都不用再去作用域列表里翻应用。
  - 每天最多发起一轮，不会反复弹通知；`scope_request_blocked`（你在管理器里勾了"阻止作用域请求"）时会如实说明
  - 非 LSPosed / 旧框架拿不到该服务时全部静默跳过，不影响签到主流程
  - 结果与失败原因在 `/jmb` -> 🩺 自诊断 里可查
- 依赖：新增 `io.github.libxposed:service` + `interface`（AIDL 客户端，共 43KB，零第三方库、零 kotlin），打进包内

## v1.4.2 (versionCode 111) - 签到调度与多账号修正 + 运行日志重做 + 跨账号复制目标

本版把整轮审计（P0/P1/P2）的修复与新增功能一次做完。1.4.1 与 110 是开发自测用的内部构建号，
从未发布到任何仓库，正式版本号为 v1.4.2 / 111。

- **[P0] 同一天的退避与重试上限失效**：失败的目标不再被反复重发刷 bot；跨天判定改用 `retry_day_`，
  设置页的「重试上限」真正生效。
- **[P0] 「签全部账号」只签了第一个账号**：整轮走 force，不再被自己刚设的 60 秒全局节流挡掉。
- **[P1] 签到状态与目标缓存跟随账号**：切换账号后不再拿上一个账号的列表判重、分配 id、写「已签」。
- **[P1] 线程安全**：目标列表走锁与快照，发送中/去重/唤醒集合改并发容器，日期格式化不再共享实例
  （旧版会 CME 或日期错乱，且异常被吞后表现为「点了没学到」）。
- **[P1] 僵尸目标处理**：取不到 bot 会话数据时先尝试从会话列表解析 peer 自救；仍取不到才计入退避与
  重试上限，当天放弃并说明下一步怎么做；目标是群/频道则直接判不可签。
- **[P1] 编辑目标里清空「前置命令」现在真的落盘**，不再重启后复活；导入的配置若数值类型不一致，
  条目不会再静默消失。
- **[P1] `/jmb` 只认恰好 `/jmb` 或 `/jmb 参数`**，不再吞掉 `/jmbx` 这类正常消息；发送中状态 90 秒
  自动过期，卡死可自愈。
- **[P1] 捕获模式 2 分钟自动解除**；Activity 销毁即释放、弹窗前统一判活（治「发 /jmb 没反应」与泄漏）。
- **[P1] 网络层自动学习加了开关并持久化**（默认仍为开）；回复判失败的词表去掉「请先/不能/无法/错误」，
  不再把正常回复误判成失败并撤销已签。
- **[新增] 复制目标到其它账号**（主菜单里）：把当前账号的目标一次性复制给其它账号，只复制目标本身，
  不带「今天已签」与重试状态；同 bot 同指令自动跳过并报告跳过数。
- **[重做] 运行日志**：错误/警告/成功/普通/调试五级；内存 800 条 + 落盘历史（256KB x 5 分片轮转），
  重启后仍可翻查；界面支持搜索、级别筛选、正序倒序切换、长按单行复制、清空（可先导出一份再清）、
  导出带级别与统计；删掉三套无人调用的旧日志/旧菜单界面。
- **[重做] 导入配置**：先列出候选备份（文件名、版本、导出时间、项数），再选「合并」或「覆盖」，
  结束时报真实结论（当前账号目标数从几个变几个；没变就直说没变）；不认识的键一律不写入。
- **[策略] Toast 降噪但不失联**：启动汇总每天最多一条（并提示还有几个账号没学习目标）；签到结果
  8 秒窗口合并成一条；同类提示 5 分钟去重；「今天已经签到过了」保持每天最多一次。
- **[清理] 历史遗留的无前缀老键一次性搬正**（v1.2.2 迁移时漏下的孤儿键）；删除与清空统一按「是否
  目标键」判定，设置项一律保留。
- **[改进] 更新链**：检查失败不再占用 12 小时冷却；GitHub 403/限流不再被报成「所有候选仓库都不可达」；
  下载安装包按同 Release 的 `sha256sum.txt` 校验，不匹配就删掉坏包并说明期望与实际。
- **[体验] 自诊断与主界面逐账号列出目标数**，不再露出 0 基账号编号；日志时间戳多余括号等文案修正。
## v1.4.0 (versionCode 108) —— 大改版：回调签到可用又可控 + 全新界面 + 配置彻底可清理

本版把 1.3.1 之后未发布的改进并成一次发布：修掉配置删不净与回调类型丢失，重做回调的添加与调试，并把管理界面整体换成卡片式设计。

- **新增：回调按钮可手动绑定多个。** 「➕ 添加目标」拆成「⌨️ 文本指令」与「🔘 回调按钮(捕获)」：进捕获模式后去 bot 会话点一下按钮，模块列出那条消息里的所有内联按钮，点哪个绑哪个，可连点多个，同 bot 同按钮自动去重；绑定不受关键词限制。
- **新增：🧪 测试 / 🔬 回调调试台。** 调试台列出当前面板全部按钮，点任意一个即时发一次该回调并把机器人返回显示出来；列表内每条目标也可「测试」。正常签到成功同样回报机器人返回文本。
- **新增：每条目标自带「前置命令序列」。** 针对不主动推面板的 bot，条目里填 `/start`、`菜单` 等（逗号或换行分隔，可多条），签到/测试前先依次发送拉起面板再重放按钮；设置里的全局唤醒命令降为默认值。
- **新增：🔁 重绑为回调。** 1.3.1 期间被误存成“指令”的旧回调，进条目操作点一下、再去点它的按钮即可按真实回调重建。
- **修复：删除配置有残留、重开 TG 又复活（第三方 fork 尤其明显）。** 逐条删除改为跨全部账号前缀（含无前缀旧键）彻底清除，删除前增加二次确认；新增 `🧹 清空所有配置` 一键跨全部账号重置（仅保留设置）。
- **修复：回调按钮重启后变“发送消息/指令”。** 加载时优先读回 `kind_`/`did_`，只有确实缺失才回落文本，回调类型可靠保留。
- **变更：自动学习与关键词解耦，且默认不再乱加。** 新增开关「自动学习：点一下按钮就加入列表」默认关闭（关了就用捕获/调试台手动加，不会误加），打开后默认再叠加「仅加命中关键词的按钮」过滤。捕获 / 调试台 / 重绑始终不受限制。
- **变更：界面大改版。** 顶部概览卡显示当前账号、目标数、今日已签、开关状态与其它账号目标数；菜单项与目标行统一圆角卡片、图标色块、类型徽标（🔵回调 / ⌨指令）；自动适配深浅色并取用 Telegram 主题色；设置改用开关控件；署名 wlmosv。
- **新增：📖 使用教程（分节，首次自动弹出一次）与 🩺 自诊断（列出关键反射锚点是否解析与失败原因，便于排查第三方客户端适配）。**
- **变更：运行日志分级着色、倒序显示**（成功绿 / 失败红 / 警告橙），导出仍写到系统「下载」目录。
- **版本一致性：** `app/build.gradle` 与 `UpdateChecker` 统一为 108 / v1.4.0（此前 gradle 停在 106/1.3.0）。
- **兼容：** prefs 文件名、导出 json 格式、更新通道与签名 key 均不变，versionCode 107 → 108。老用户覆盖安装即可，学过的目标与已签状态原样保留。


## v1.3.1 (versionCode 107) —— 修复回调按钮签到不可用

- **修复：回调按钮签到在 TG 12.10.1 上不可用。** v1.3.0 新增回调按钮签到，但适配 TG 12.10.1 时读按钮 payload 的字段写错，导致点签到按钮学不进去、重放请求缺 msg_id 发不出去，实际无法签到（只会发一条文本消息，bot 回复不支持）。

- **按钮 data 读取兼容新架构：** 适配 `KeyboardButtonProto.getData()` 方法与 `mType.data` 字段（TG 12.10.1 重构后按钮不再有 data 字段）。

- **回调重放补齐 msg_id：** `TL_messages_getBotCallbackAnswer` 的 msg_id 是必填序列化字段，学习时记录按钮所在消息 id，重放时写入。

- **移除对已不存在 hash 字段的赋值：** 此前 `setFieldVal(req, "hash", ...)` 会抛异常被吞，导致请求未发出，现已移除。

- **兼容：** 签到数据、导出 json 格式、更新通道与签名 key 均不变，老用户覆盖安装即可。

## v1.3.0 (versionCode 106) —— 大功能版：回调按钮签到 + 一 bot 多指令 + 全账号签到

- **新增：回调按钮（inline button）签到支持。** 签到 bot 用回调按钮（点按钮触发而非发文本指令）现在可以直接支持：在聊天里点一次 bot 的签到按钮即可自动学习，之后每天自动重放该回调。学习时记录按钮文案 + callback data（payload），重放走 `TL_messages_getBotCallbackAnswer`；链接/游戏类按钮（无 data）不会被误学。已学文本指令目标不受影响，两者可并存。
- **新增：一个 bot 可登记多条签到目标。** 此前同一 bot 只能存一条指令，后添加的会覆盖旧的；现在同一 bot 的不同指令、以及文本 + 回调按钮可以同时存在，各自独立签到、独立状态、独立重试。手动添加重复指令会被拦截提示。
- **新增：一键签全部账号（🌐 签全部账号）。** 遍历所有已激活账号，每个账号用各自独立的目标集、各自的 MessagesController/ConnectionsManager 发签到，互不干扰；结果按账号打印在运行日志。账号间数据隔离逻辑不变。
- **变更：目标条目模型升级为「条目制」。** 存储键新增 `kind_/did_/data_/hash_`（回调条目专用），旧数据 `acc{N}_learned_<did>` 原键迁移、零改写，覆盖升级后目标与已签状态原样保留；导出/导入（整文件备份）天然兼容新键。
- **修复（随重构）：** 手动添加同一 bot 第二指令不再覆盖第一条；回复语义判定失败撤销按「最近 10 分钟内发过请求的条目」精确撤销，不再整 bot 连坐。
- **兼容：** 签到数据、导出 json 格式、更新通道与签名 key 均不变；仅新增键，不删除任何旧键。老用户覆盖安装即可。
## v1.2.3 (versionCode 105) —— 签到可靠性修复

- **修复：重试计数跨天不重置。** 此前某天用完每日重试上限后，该目标从此每天都被"今日重试已达上限"跳过，自动签到永久失效（只能手动签一次或删除重学恢复）。现在检测到新的一天会自动清零重试计数与退避。
- **修复：失败后"已签"标记不撤销。** 此前模块发出签到请求时先乐观标记已签，若随后请求报错（非永久失败 / FLOOD_WAIT），标记不会被撤销，导致"退避重试"永远不会真正执行、界面却显示已签 ✅。现在两类失败都会撤销当日已签标记，由轮询按退避计划真实重试。
- **修复：FLOOD_WAIT 不再固定 60 秒重发。** 现在解析服务器返回的等待秒数（`FLOOD_WAIT_<sec>`），写入退避计划后等待，不再空转重发加重限流；限流现在也计入每日重试上限。
- **修复：Bot 回复语义判定在 TG 12.10.1+ 失效。** `processUpdate` 已改名 `processUpdateArray`，改为按 `processUpdate` 前缀匹配 hook，新旧两代宿主都兼容（v1.2.2 仅诊断，本版真正修复）。
- **修复：Bot 回复判定失败时的退避档位** 按当前重试次数取值，不再写死 15 分钟档。
- **新增：🧾 导出运行日志**（`/jmb` 菜单）。写入系统「下载」目录（MediaStore，无需存储权限），含宿主包名与版本信息，便于反馈第三方客户端问题。
- **新增：目标列表显示 bot 用户名与上次签到时间**，不再只有裸 uid；🚀 立即签到页新增"全部签到"一键按钮。
- **清理：删除零引用死代码 `store/Store.java`。**
- **兼容：** 签到数据、导出 json 格式、更新通道与签名 key 均不变，覆盖安装即可，目标与已签状态不受影响。

## v1.2.2 (versionCode 104) —— 多客户端支持（issue #1 / #2）

- **新增：宿主判定不再写死包名。** 新增 `Hosts`：已知包名白名单 + 标志类能力探测，命中任意一条才注入。
  - 白名单：`org.telegram.messenger`、`org.telegram.messenger.web`（官网直连版，issue #1）、`fork.risin42.nagramx`（Nagram XF，issue #2）、`nu.gpu.nagram`、`nu.gpu.nagramx`、`nu.gpu.nagram.web`
  - 能力探测：宿主 ClassLoader 能解析 `org.telegram.tgnet.ConnectionsManager` + `org.telegram.ui.Components.ChatActivityEnterView` + `org.telegram.messenger.UserConfig` 即视为 Telegram-Android 血统，新 fork 不必等模块更新；换内核的客户端（Telegram X）标志类不齐全，不会被误注入
  - 静态核对：官方 12.10.1(70382) / 官网 web 12.10.1(70389) / NagramXF 12.10.1-dec46b0(1250) 三个真实包里，14 个反射类与 `didPressedBotButton`(2 重载)、`sendRequest`(7 重载)、`MessagesController.getInstance`、`MessagesStorage.getInstance/getUser`、`UserConfig.selectedAccount`、`getInputPeer`、`ChatActivity.onResume`、`LaunchActivity.onResume` 逐项一致；其中 Nagram XF 已真机运行时实测通过（注入与签到），其它第三方包欢迎带运行日志反馈
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
