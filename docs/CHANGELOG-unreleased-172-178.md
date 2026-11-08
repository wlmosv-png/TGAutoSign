# 未发布构建记录（1.7.2 – 1.7.8）

> 这些构建**从未发过 Release**，按项目规则（CHANGELOG 只记已发布版本）
> 从 CHANGELOG.md 移出，原文保留在此，不作为发布日志对外宣传。
> 2026-11-08 起因：构建版本号按要求锁定 1.7.1 (171)，需与 CHANGELOG 最高条目自洽。

# 更新日志

## 1.7.8 (178) — 2026-11-08

> 接收链纯观测改造 F0–F8：把「哪条链没动、为什么丢、在哪一步丢」变成可读事实。**不涉及任何判定规则与业务状态。**

### 新增 · Added

- **F0 Hook 生命周期取证**
  *Hook lifecycle forensics.*
  逐个 Hook 记录：目标类是否解析、方法是否找到、重载个数、最终选中的签名、安装成功/失败与原因、实际触发次数。
  未安装的链一律标 `HOOK_NOT_INSTALLED`，**不得表现成"这条链只是恰好零消息"**。
  每 60 秒输出一张表：`id | target | class | method | overloads | chosen | installed | fired | 判定`。

- **F4 Update 类型直方图**
  *Update-type histogram.*
  在类型过滤**之前**记录真实收到的 update 类名（仅类名，不记正文）——
  这是区分「没收到 update」与「收到但类型不支持」的唯一手段。

- **F7 重试时间线**
  *Retry timeline.*
  同一 roundId 串联：`clicked → reset_begin → cleared → send_begin → send_end → in_flight_written`，
  另记冷却命中。用于**量化**重试盲区，而不是凭代码结构推测。

- **F8 纯函数 + 真跑测试**
  *Pure functions with real tests.*
  新增 `Diag`（事件 schema、Hook 表、直方图、丢弃计数、限频抑制计数、写盘失败计数）与
  `InboundGate`（update 类型识别、入站闸口逐条归因、链 A/B 差异化保留），
  均为无 Android 依赖纯类 → `ReceiveChainTest` **48 项真实执行**（含 18 项验收场景）。

### 修复 · Fixed

- **F1 探针级别**
  *Probe log level.*
  现全部改走 `logw`（ LV_WARN，宿主日志对 WARN 必定落盘，`TGAutoSignCore:3367` 实测）。
- **F2 锚点 B 探针改位**
  *Anchor B probe moved to the real path.*
  `onPostNotification`（真正被调用的发送端）与 `handleOneMessageObject` 入口。
- **F3 入站探针前移**
  *Inbound probe moved earlier.*
  `[in]` 前移并改为结构化 `recv` 事件；`msg==null` / `peerUid==0` 各有独立原因记录。
- **F5 早退原因全量归因**
  *Every early exit now carries a reason.*
  丢弃原因覆盖到入站前置闸口全部分支（update 空/类型不支持/消息空/peer 为 0/自己发/私聊不匹配/群无在途/无目标/链B各闸）。
- **F6 限频可观察**
  *Throttle is now observable.*
  保留限频，但**统计被抑制数量**；磁盘写失败计数（不再静默假装成功）。

## 1.7.7 (177) — 2026-11-08

> 入站取证版：把「消息到底有没进模块、在哪一步丢的」变成日志里可读的事实；并修掉连点导致的接收盲区。

### 新增 · Added

- **入站诊断日志（按会话限频）**
  *Inbound diagnostics, throttled per chat.*
  `[in] peer= from= bot= rm= await= acc=`：每条进来的新消息都留一行，
  回答「模块有没有看见这条回复」；
  `[inB] acc= len= types=`：锚点B 到底有没有被调用、参数形状如何；
  `[drop] why=peer_not_awaiting / private_self_or_mismatch / no_target`：消息进来了却没判，到底卡在哪一条闸。
  全部 WARN 级，保证进落盘日志（之前 INFO 级会被采样丢掉，这正是四轮排查都看不到真相的原因）。

### 修复 · Fixed

- **重试连点会造成接收盲区**
  *Rapid retries created a receive blind spot.*
  每次重试都会清掉该目标的 `opt_`\/`sent_at_`（在途标记），而接收链的第一道闸就是
  「该会话是否有在途目标」—— **盲区期间到达的 bot 回复会被直接丢弃**。
  真机实测：用户在 4 秒内把同一目标重试到第 6 轮（日志 round=2…6）。
  现在加 60 秒重试冷却：冷却期内点击会提示「刚重试过，请等 N 秒」，不再重置在途状态。

- **DialogObject 重载选择**
  *Overload selection for DialogObject.*
  之前两个重载都塞同一个实参，必然有一个抛 `IllegalArgumentException`
  （日志里那行「试 Peer 抛异常」）。现在按实参类型挑重载。

## 1.7.6 (176) — 2026-11-08

> 1.7.5 真机回访四个缺陷：链路健康标记时机错、重试后目标反复回列表导致连点、脏 no_reply 冒充结论、卡片两行文案自相矛盾。

### 修复 · Fixed

- **观察链路健康标记永远不置位**
  *The watch-chain health flag was never set.*
  1.7.5 把标记放在 `CORE.start()` 之后，那时锚点还没挂上 → 标记永远是 false →
  **所有待处理目标都被显示成「观察链路不健康」**（真机截图实测）。
  现在改在**装完 Hook 之后**判定，任一锚点已挂上即视为链路可用。

- **点完「重试」目标又出现在卡住列表里（导致连点）**
  *After tapping Retry the target stayed in the stuck list, so users tapped repeatedly.*
  1.7.5 把窗口内的「等回复」显式设成 `RS_SENT`，而列表筛除只认 `state<0` →
  目标立刻又回列表 → 用户以为没生效 → **连点 4~5 次**（日志实测：6379863842_1 四秒内跑到 round=6）。
  现已恢复既有语义：等待窗口内不打扰用户。（新增的轮次号让这一点在日志里一目了然）

- **脏 `no_reply` 不再冒充结论**
  *Stale "no reply" records no longer pass as verdicts.*
  `result_=no_reply` 可能是观察链路不健康时期（旧版锚点B 未挂上、宿主刚重启）
  写下的脏结论。现在只有**过了证据门**写入的 no_reply 才带「资格标记」`nr_ok_`；
  无标记的一律降级为「本轮还没有结论」，界面落「未确定」。

- **卡片两行文案自相矛盾**
  *Two lines on the same card contradicted each other.*
  一行写「不代表 bot 没回复」，另一行又写「bot 全程没回复」。现在「未确定」状态下第三行改为
  「还没有结论：可能 bot 已回复但当前无法确认」—— 不再对 bot 行为下断言。

### 测试 · Tests

- **PendingStateTest 扩到 43 项**
  *PendingStateTest grows to 43 assertions.*
  新增「脏 no_reply 无资格标记 → 视为无结论」与「有资格标记才算结论」。

## 1.7.5 (175) — 2026-11-08

> 待处理状态与回复判定链 P0/P1 修复：废除「90 秒推导未回复」、拆分回复证据、统一重试入口与轮次隔离。

### 修复 · Fixed

- **「未回复」不再由发送时间推导**
  *"No reply" is no longer inferred from elapsed time.*
  旧实现：距发出超过 90 秒且没有结论 → 无条件显示「未回复」。
  这是**时间推导**，不是证据 —— 它把三件不同的事压成一个词：bot 回了但判不出、
  什么都没观察到、真没回。现在归因交给纯函数 `SignLogic.resolvePendingState`，
  **先看证据、再看时间**；90 秒阈值只影响措辞，绝不再用于判定。
  新增状态「未确定」（`RS_UNCONFIRMED`）专门承接「证据不足」，与「判不出」「未回复」三者分开。

- **回复证据分级与轮次隔离**
  *Reply evidence is now graded and round-scoped.*
  以前「是否回过」只靠 `answered_` 一个布尔，而它**只在按钮回调路径写** ——
  消息回复路径判不出时什么都不留，界面只能靠时间猜。现在拆成 4 级：
  已观察 → 目标 Bot → 已关联 → 已判定，**判定失败也不丢已观察到的真实回复**。
  每条证据都带轮次号：新轮不继承旧轮结论，但**旧轮证据不被删除**（归档保留）。

- **两个重试入口统一为 `resetRoundStateForRetry`**
  *Both retry entries now share one round-reset routine.*
  待确认面板「重试」与卡住面板「重发」以前各写一套（一个清 `answered_`、一个不清），
  而且前者用 `accountOfPrefix`、后者用 `currentAccount()`（会串号）。现在两者走同一套：
  轮次 +1、清在途与调度状态、**不破坏性删除任何历史证据**、统一用原任务对应的账号。

- **状态转换保护与原子更新**
  *State-transition guards and atomic updates.*
  已确认的回复证据**不再被无证据的超时结论覆盖**；旧轮超时不能修改新轮状态；
  「未回复」必须过三道门（无任何证据 + 无 `answered_` 留痕 + **观察链路健康**）；
  `markSigned` 的「写已签」与「清在途/待确认」合并为**同一事务**，消除「已签 + 仍待确认」的撕裂态。

### 新增 · Added

- **最小化事件日志（send / recv / corr / verdict / timeout / ui / retry）**
  *Minimal event log for the whole reply chain.*
  每条带 `taskId / botId / acc / round / msgId / t / grade / reason`，用于回答四个问题：
  为什么收到消息却没识别？为什么点了重试还是未回复？
  为什么 UI 显示未回复？为什么旧任务能覆盖新任务？**不记录任何凭据**，文本一律截断。

### 测试 · Tests

- **新增 PendingStateTest（40 项）与第 9 道静态门禁 `check-pending-state.py`**
  *New PendingStateTest (40 assertions) plus a static gate.*
  覆盖：91 秒无证据不得判未回复、91 秒已观察不得判未回复、证据不足保留未确定、
  明确超时且链路健康才可判未回复、链路中断不得假装没回复、重试后旧轮消息不污染新轮、
  新轮回复先到时旧轮超时不得覆盖、两个重试入口初始化一致、已签不能变回未回复、
  多 Bot 不串号、多账号不串号、重复消息不重复签到、已签与待确认不撕裂、UI 始终反映真实状态。

## 1.7.4 (174) — 2026-11-08

> 修复符号层里四个真实 bug：NotificationCenter 被误杀导致回复收不全、DialogObject 与 getSendingMessageId 定位失败。

### 修复 · Fixed

- **NotificationCenter 被错误拒绝（锚点 B 挂不上）**
  *NotificationCenter was wrongly rejected, so anchor B never got hooked.*
  13.2.1 的 NotificationCenter(ys) 里有**两个** 签名完全相同的
  方法：`k`（真正分发给观察者，方法体调用 `SparseArray.keyAt`）和
  `m`（它的包装）。“唯一才采用”的契约把整个类判死 → MC=✓ 但 NC=✗ →
  只剩锤点 A（长消息链）在工作，部分 bot 的回复收不到，用户看到一堆「回复判不出」。
  现在：祖相契约改为「至少存在一个」（新加 `mx:` 类型），新增**成员名提示表**
  记住「这个版本里真正分发的是 k」，并把同签名的候选**全部挂上**
  （两者都是「发通知」入口，重复投递由既有 (peer, msgId) 去重吸收）。

- **方法返回类型提示不认基本类型**
  *Method return-type hints did not understand primitives.*
  `mapTgOrSystem("long")` 返回 null（它只会 `Class.forName`）→ 返回类型提示静默失效 →
  只剩参数表 → 多候选被拒。受影响：`getSendingMessageId`、`isValidAccount`、
  `getPeerDialogId`、`postNotificationName` —— 日志里的「getSendingMessageId 方法没找到」
  和「DialogObject.getPeerDialogId 候选数=0」就是它。现在改走 `typeOf()`。

- **数组类型的规范名不认**
  *Canonical array type names were not understood.*
  `Class.getName()` 对数组返回 `[Ljava.lang.Object;`，而契约里写的是
  `java.lang.Object[]` → 解析成 null → 参数表对比将该位置**当成通配符**，
  契约被静默放宽。现在 `typeOf()` 与 `sig()` 都按人类可读规范名处理。

- **父类契约恒为假**
  *The superclass contract was always false.*
  `s:java.lang.Object` 走的是只管 `org.telegram.messenger.*` 的 mapClass → 返回 null →
  SharedConfig 永远未命中（日志里的「✗ SharedConfig → 未命中」）。已修。

- **DialogObject 双重载改走符号层**
  *DialogObject overload lookup now goes through the symbol layer.*
  `getPeerDialogId` 在 13.2.1 里叫 `i5.o` / `i5.n`，按名扫描必然 0 命中。

### 测试 · Tests

- **HostSymbolsTest 77 项**
  *HostSymbolsTest: 77 assertions.*
  四个 bug 各有回归用例（基本类型提示、`mx:` 契约不误杀、
  成员提示把真正分发的排首、父类契约能解析非 messenger 包名）。
  夹具补上「两个同签名重载」的 NotificationCenter，与真实 APK 一致。

## 1.7.3 (173) — 2026-11-08

> 修复 Telegram 13.2.1 的宿主映射：混淆名逐版本变化，新增 13.2.1 档与自动生成器。

### 修复 · Fixed

- **13.2.1 的宿主符号映射缺失**
  *Host symbol mapping for Telegram 13.2.1 was missing.*
  1.7.2 只收录了 13.2.0（官网直连版实测 lp / os / w10 …）。
  Telegram 13.2.1（Play 渠道，versionCode 72911）把混淆名**整套换了一轮**：
  MessagesController→vp、NotificationCenter→ys、UserConfig→c20、
  MessagesStorage→hs、DialogObject→i5、SendMessagesHelper→qz、
  UserNameResolver→f20、BaseController→d1、SharedConfig→tz。
  旧表下这些对不上，模块会明说「宿主版本未适配」并停用数据与事件功能——
  这正是设计预期（宁可明说，不做假成功）。现在补上 13.2.1 档，
  13.2.0 档保留，两档互不影响。

- **映射表可自动生成**
  *The mapping table can now be generated automatically.*
  新增 `tools/hostmap-gen.py`：输入任意 Telegram APK，用结构指纹
  （全包唯一 5 参 processUpdateArray、`(int,Object[])` + `SparseArray.keyAt`、
  字符串锚点 `cache4.db` / `userconfing` / `mainconfig`、DialogObject 双重载消歧）
  自动定位 9 个符号并输出可直接写进表的条目，`--apply` 一键插入。
  以后 Telegram 更新只需一条命令。

### 测试 · Tests

- **HostSymbolsTest 扩到 70 项**
  *HostSymbolsTest grows to 70 assertions.*
  新增「13.2.1 档选中」与「两档互不串」用例：同一 loader 下以 72911 安装应得到 vp / ys / c20，
  以 72809 安装应得到 lp / os / w10，切换后不互相污染。

## 1.7.2 (172) — 2026-11-08

> 适配 Telegram 13.2.0 的类名混淆变更：新增宿主符号解析层，恢复会话列表、Peer 解析、账号识别与回复判定；13.1.1 及更早行为不变。

### 新增 · Added

- **宿主符号解析层（HostSymbols）**
  *New host symbol resolution layer (HostSymbols).*
  Telegram 13.2.0 起不再保留 `org.telegram.messenger.*` 的类名（官方混淆规则删除
  `-keep class org.telegram.messenger.* { *; }`，实测保留类名从 726 个降到 66 个），
  模块过去完全靠「按原名反射」，新版上会话列表、Peer 解析、账号识别、回复判定整条链静默失效。
  现在统一由符号层解析：① 按原名直查（旧版与不混淆 fork 走这条，行为逐字不变）；
  ② 按宿主 versionCode 查映射表；③ 结构指纹校验（映射来的类必须满足方法／字段契约，否则拒绝）；
  ④ 成员按「参数表 + 返回类型」结构匹配，多候选一律拒绝。

- **映射表逐个用结构证据确认**
  *Every mapping entry is confirmed by structural evidence.*
  13.2.0 实测：MessagesController→lp、NotificationCenter→os、UserConfig→w10、
  SharedConfig→nz、MessagesStorage→wr、DialogObject→k5、SendMessagesHelper→lz、
  UserNameResolver→y10、BaseController→j1。其中 DialogObject 有两个候选（ej / k5），
  按「同时具备 (Peer)→long 与 (InputPeer)→long 两个重载」消歧后才定为 k5。

- **适配状态显式可见**
  *Adaptation status is now explicit.*
  `/jmb` 自检面板新增「宿主符号解析」一行，启动日志写「宿主适配」与逐项符号结果；
  解析不出关键符号时明说「宿主版本未适配」，并停装对应的业务 Hook。

### 修复 · Fixed

- **假注入**
  *Fixed false-positive injection.*
  过去白名单命中就直接放行：即使关键类一个都解析不到，模块仍报「已加载」、面板照常打开。
  现在白名单只代表「允许尝试适配」，每个业务 Hook 各自要求符号解析成功才安装；
  不兼容时保留仍能独立工作的 `/jmb` 命令与按钮学习，并明确标注降级。

- **锚点 A 的前缀匹配在新版失效**
  *Anchor A's prefix match no longer worked on the new build.*
  旧实现按方法名前缀 `processUpdate` 匹配；13.2.0 方法名同样被改写（processUpdateArray→K3），
  前缀匹配 0 命中。现在先走前缀（旧版路径），0 命中时按结构指纹
  `(ArrayList,ArrayList,ArrayList,boolean,int):boolean` 定位。

- **通知 id 读不到时的兜底**
  *Fallback when the notification id cannot be read.*
  didReceiveNewMessages 的 id 过去读 NotificationCenter 的字段；新版字段名也被改写。
  现在旧版读字段真值，新版退到官方源码常量（totalEvents 自 1 起、该通知是第一个使用者 → 恒为 1），
  并额外按参数形状 (ArrayList, boolean) 二次过滤，保证常量出入时也不会漏事件或误触发。

### 测试 · Tests

- **新增 HostSymbolsTest（57 项）**
  *New HostSymbolsTest (57 assertions).*
  覆盖：旧版原名解析、新版映射解析、错误映射被结构契约拒绝、
  同参数同返回的多候选拒绝、缓存隔离（切版本不串）、未知版本降级、
  关键符号缺失、旧版路径不受新版映射影响、消息对象结构判定与通知参数形状。

