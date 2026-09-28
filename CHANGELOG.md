# 更新日志

## 1.6.4 (127) — 2026-09-28

> 本版修复 1.6.3 归类改造遗留的三处问题，并把文本指令目标的「已签」
> 改为诚实标注。另补齐宽松模式漏掉的「功能性拒绝」词。

### 修复 · Fixed

- **归类码与既有字段冲突，导致归类丢失**
  现象：用户处置过（确认/重试/忽略）的目标，状态不再显示具体归类，
  退回含糊的通用文案。
  根因：归类码被写入 `pendcfm_note_`，而该键的既有语义是「日期|处置动作」，
  用于判断"今天是否已被用户处置过"。用户一点处置，归类就被覆盖成
  `2026-09-28|用户点了重试`，解析失败返回 -1。
  修复：归类码改用独立键 `pendcfm_result_`，两个字段各司其职。

  Classification code collided with an existing field, losing the classification.
  Symptom: after a user handled a target (confirm / retry / ignore), the status no longer
  showed its specific classification and fell back to generic wording. Root cause: the code
  was written to `pendcfm_note_`, whose existing meaning is "date|action" and which is used to
  tell whether the user already handled it today. As soon as the user acted, the
  classification was overwritten with `2026-09-28|user tapped retry`, which failed to parse and
  returned -1. Fix: the classification now uses its own key `pendcfm_result_`, so the two
  fields no longer overlap.

- **手动重试不清按钮失效计数，重试很快被历史计数吃掉**
  现象：在待处理里连续点「重试」，日志里的失效次数持续累加（实测 4 秒内从第 4 次到第 6 次），
  重试形同无效。
  根因：重试只重置了重试计数，未清 `panelstale_`；而每次重试又会触发一次按钮失效，
  计数继续上涨，很快撞上「重发 2 次仍失效」的判定。
  修复：手动重试同时清零按钮失效计数 —— 用户点重试即明确要求"再试一次"，
  历史计数不应继续累计。

  Manual retry did not clear the button-expiry counter, so retries were quickly consumed.
  Symptom: tapping "retry" repeatedly raised the expiry count (from 4 to 6 within four
  seconds in one measurement), making retries ineffective. Root cause: retry reset the retry
  counter but not `panelstale_`; each retry triggered another expiry, so the count kept
  climbing until it hit the "still failing after two resends" rule. Fix: manual retry now
  also clears the expiry count — tapping retry is an explicit request to try again, and
  earlier failures should not keep accumulating.

- **兜底路径未写归类，界面与日志仍停留在旧状态**
  现象：目标状态显示含糊文案，日志也仍写着旧词「待确认」。
  根因：转待确认的兜底路径只写了旧的布尔标记，未记录归类；
  而界面文案与处置入口都以归类为唯一真相源。
  修复：该路径补写归类（有回复则记「回复判不出」，无回复则记「bot 未回复」），
  日志文案同步改为归类名。

  The fallback path did not record a classification, leaving the UI and log on the old state.
  Symptom: targets showed vague status text and the log still used the old word. Root cause:
  the fallback that promotes a target recorded only the legacy boolean marker and no
  classification, while the UI wording and action entries derive from the classification.
  Fix: that path now records one ("reply unreadable" when the bot replied, "bot did not
  reply" otherwise), and the log message now names the classification.

- **文本指令目标的「已签」改为诚实标注**
  现象：文本类目标（如发送 `/checkin`）显示为「已签」，与真正判定成功的目标无法区分。
  根因：文本指令走 `sendText`，没有回执通道，永远等不到结论；若不计入已签，
  每轮巡检都会重发（实测曾 5~15 秒一发）。因此「发出即已签」是有意设计，
  但它与按钮目标的「判定成功」性质不同，混在一起显示绿色会误导。
  修复：文本目标单独显示「已发指令」，颜色走中性色，与「已签」区分。

  Text-command targets now label their state honestly instead of showing "signed".
  Symptom: text targets (such as sending `/checkin`) appeared as "signed",
  indistinguishable from targets that genuinely passed judging. Root cause: text commands go
  through `sendText`, which has no reply channel and never yields a verdict; if they were not
  counted as signed, every sweep would resend them (observed at 5-15 second intervals).
  "Sent counts as signed" is therefore deliberate, but it differs in nature from a judged
  success, and showing both in green is misleading. Fix: text targets now show
  "command sent" in a neutral colour, distinct from "signed".

- **宽松模式把功能性拒绝判成成功**
  现象：宽松模式下「⚠️ 请先加入以下1个频道才能使用功能」被记为已签，
  同类还有「🔒 请先绑定或注册账号」。
  根因：失败词表只覆盖了业务失败（签到失败、活动已结束等），
  未覆盖「前置条件未满足」这类拒绝 —— 而它们都要求用户先做某事。
  修复：补齐该类词（请先加入 / 加入频道 / 请先绑定 / 未绑定 / 无权限 等）。

  Lenient mode counted functional refusals as success.
  Symptom: with lenient mode on, "please join the following channel before using this
  feature" was recorded as signed, as was "please bind or register an account first".
  Root cause: the failure word list covered only business failures (sign-in failed, event
  ended) and not refusals caused by unmet preconditions, all of which ask the user to do
  something first. Fix: such wording was added (please join / join the channel / please bind /
  not bound / no permission, and similar).

### 变更 · Changed

- **归类语义补入回归用例**
  新增宽松模式失败词的单测，锁定「功能性拒绝必须判失败」及「不误伤正常成功回复」。

  Regression cases for the classification semantics.
  Tests were added for the lenient-mode failure words, pinning down both "a functional
  refusal must be judged as failure" and "a normal success reply must not be harmed".

## 1.6.3 (126) — 2026-09-28

> 本版把散落在 5 处的成败判定收敛为**单一执行结果归类**，
> 并移除三处把「请求发出」误当「签到成功」的逻辑。
> 界面侧去掉目标行的内联处置按钮，改为顶部聚合条。
> 改动源自用户实测：按钮点不动却显示「已签」、bot 回复了却显示「等待」。

### 修复 · Fixed

- **「请求发出」被当成「签到成功」**
  现象：按钮点不动、bot 只回一句欢迎语的目标，最终仍显示「已签」。
  根因：超时分支以 `optimisticSigned()` 为判据，而该函数读的是 `opt_` 标记 ——
  其语义仅为「今天发出过请求」，不含任何成败结论。代码据此执行
  `keepSignedClearBackoff` 与 `noteResult(true)`，等于把「发出去了」当作「签上了」。
  实测 `8439387373`（hope 社工库）按钮全程点不动，当天仍被标记已签。
  修复：发出但无结论时不再判成功，归类为「bot 未回复」，等待用户处置。
  归类的语义契约由 `SignLogic.countsAsSigned` 承载，只有 `R_SIGNED` 会写已签状态。

  "Request sent" was treated as "signed in".
  Symptom: targets whose button could not be tapped, and where the bot only replied with a
  greeting, were still shown as signed. Root cause: the timeout branch used
  `optimisticSigned()`, which reads the `opt_` marker — that marker only means "a request was
  sent today" and carries no verdict. The code then ran `keepSignedClearBackoff` and
  `noteResult(true)`, treating "sent" as "signed". In one measurement the button for
  8439387373 (hope) could never be tapped, yet the target was marked signed that day.
  Fix: a sent request without a verdict is no longer counted as success; it is classified as
  "bot did not reply" and left for the user. The semantic contract lives in
  `SignLogic.countsAsSigned` — only `R_SIGNED` writes signed state.

- **按钮失效的错误归因导致永久放弃重试**
  现象：按钮报 `MESSAGE_ID_INVALID` 后，模块提示「该 bot 拒绝程序代点按钮，
  请改成文本指令目标」，并从此不再重试。
  根因：熔断条件为 `staleBefore >= 1`，注释称「该 bot 的失败是确定性的」。
  该断言不成立 —— `MESSAGE_ID_INVALID` 的含义是「所使用的 msg_id 已过期」，
  而面板的最新 msg_id 本就可用；首次失败通常只是面板尚未推送（时序问题）。
  实测被熔断的目标当天稍后仍可签上，证明并非确定性拒绝。
  修复：改为按面板新鲜度三分类 —— 面板新鲜则以最新按钮重发一次；
  面板未就绪则不发必然失败的请求，等待新消息；两者皆否才归类「按钮失效」。
  同时删除「该 bot 拒绝程序代点」这一错误提示。

  Wrong attribution of expired buttons caused permanent retry abandonment.
  Symptom: after a button returned `MESSAGE_ID_INVALID` the module advised "this bot refuses
  programmatic taps, switch to a text-command target" and never retried. Root cause: the
  circuit breaker used `staleBefore >= 1`, with a comment claiming the failure was
  deterministic. That claim does not hold — `MESSAGE_ID_INVALID` means the msg_id in use has
  expired, while the panel's newest msg_id is available; a first failure is usually just the
  panel not having arrived yet (a timing issue). A target that tripped the breaker was still
  signed later the same day. Fix: classify by panel freshness — if the panel is fresh, resend
  once using the newest button; if it is not ready, do not send a request bound to fail and
  wait for the new message; only when neither applies is it classified as "button expired".
  The misleading "this bot refuses programmatic taps" message was removed.

- **进度提示被误判为「像是签到结果」**
  现象：日志反复出现「这条回复像是签到结果，但没匹配上内置词（可在设置里补一条）」，
  而对应内容只是「正在签到,请稍后...」。
  根因：判据为 `contains("签到")`，进度提示必然含该词，于是被当作待补词的结果。
  这类提示的下一句才是结果，补词并无意义；全量日志中出现 8 次，均属噪音。
  修复：新增 `SignLogic.looksLikeProgress` 并让 `looksLikeSignResult` 先行排除；
  进度提示降为调试级日志，不再告警、不再进入诊断包。

  Progress messages were mistaken for sign-in results.
  Symptom: the log repeatedly showed "this reply looks like a sign-in result but matched no
  built-in keyword (you can add one in settings)", while the content was merely
  "signing in, please wait...". Root cause: the test was `contains("sign-in")`, which a
  progress message necessarily contains, so it was treated as a result awaiting a keyword.
  The actual result is the next message, making the advice meaningless; the pattern appeared
  8 times and was pure noise. Fix: `SignLogic.looksLikeProgress` was added and
  `looksLikeSignResult` now excludes it first; progress messages log at debug level and no
  longer raise warnings or enter the diagnostics package.

- **自动判定关闭时界面无从体现**
  现象：关闭「自动判定成功 / 失败」后，bot 回复了内容，界面却只显示「等待」，
  用户无法得知失败原因是判定被关闭。
  根因：该分支仅写日志后返回，未在状态中留下任何痕迹。
  修复：新增归类 `judge_off`，界面显式显示「判定已关」。

  Closing auto-judging left no trace in the UI.
  Symptom: with "auto judge success / failure" off, a reply from the bot left the UI showing
  only "waiting", giving no hint that judging was disabled. Root cause: the branch logged and
  returned without recording any state. Fix: a `judge_off` classification was added and the UI
  now shows "judging off" explicitly.

### 变更 · Changed

- **执行结果归类取代散落的成败判定**
  改前由 5 处独立分支决定成败，没有一处能回答「这个目标今天到底怎么了」；
  「待确认」一词还被签到状态与网络学习候选池共用，含义冲突。
  现在每次执行落到恰好一个归类：已签 / 失败 / 按钮失效 / 回复判不出 / bot 未回复 / 判定已关。
  归类为唯一真相源，界面文案、处置入口与重试策略均由它派生。
  「不知道」被确立为一等状态，模块不再用模糊措辞掩盖不确定。

  Execution-result classification replaces scattered verdict branches.
  Previously five independent branches decided success or failure, and none could answer
  "what actually happened to this target today"; the phrase "needs confirmation" was shared by
  the signed state and the network-learning candidate pool, with conflicting meanings. Now
  every execution lands on exactly one classification: signed / failed / button expired /
  reply unreadable / bot did not reply / judging off. The classification is the single source
  of truth, and the UI wording, action entries and retry policy all derive from it.
  "Unknown" is now a first-class state; the module no longer hides uncertainty behind vague wording.

- **待处理处置改为顶部聚合条**
  改前每个待处理目标在行内横排三个按钮（确认已签 / 重试 / 忽略今天），
  与其他行的结构不一致，且默认用户此刻就要处理它。
  现在目标行保持统一的两行结构，需要处置的目标由顶部聚合条汇总，
  点「处理」进入集中界面逐条处置，动作与原来一致。

  Pending actions moved to a top summary bar.
  Previously each pending target showed three inline buttons (confirm signed / retry / ignore
  today), inconsistent with other rows and assuming the user wanted to act right away. Target
  rows now keep the uniform two-line structure, a top bar summarises what needs attention, and
  tapping "Handle" opens a screen for dealing with them one by one, with the same actions as before.

- **「待确认」重命名以消除歧义**
  签到状态「待确认」改为按归类显示明确文案（按钮失效 / 回复判不出 / bot 未回复 / 判定已关 /
  结果未知）；网络学习候选池「待确认」改为「待添加」。

  "Needs confirmation" renamed to remove ambiguity.
  The signed state now shows explicit wording per classification (button expired / reply
  unreadable / bot did not reply / judging off / result unknown), and the network-learning
  candidate pool was renamed to "to add".

## 1.6.2 (125) — 2026-09-28

> 本版移除按钮学习路径上的全部启发式准入判断。改动源自用户实测反馈：
> 某些 bot 的签到入口被过滤规则误拦，用户点按钮后无任何反应，界面上也没有提示。
> 过滤规则改为只保留用户自定义项，模块不再推测按钮性质。

### 修复 · Fixed

- **按钮学习误拦：签到入口被启发式过滤规则挡住**
  现象：在部分 bot 中点击签到按钮后，目标列表没有任何变化，界面无提示，用户误以为无法添加；
  同一按钮在「捕获」路径下却能添加成功。
  根因：`SignLogic.obviousNonSignButton` 以三条启发式判据推测按钮性质 —— data 前缀黑名单
  （`pay:` / `ub_menu_` / `menu:` 等）、文案黑名单（支付 / 充值 / 绑定 / 菜单 等）、
  随机 hex token。该设计无法区分「菜单项」与「签到入口」：实测某 bot 的签到入口
  data 为 `ub_back_menu`，命中 `ub_menu_` 前缀；按钮文案为「🔙 主菜单」，又命中「菜单」二字。
  同时该过滤只在日志中留痕，界面上没有任何反馈，用户无法得知按钮被拦。
  修复：移除 `obviousNonSignButton` 及其全部词表（`JUNK_DATA_PREFIXES`、`BAD_LABELS`、
  `SIGN_LABELS`、`SIGN_NEGATIONS`、`looksLikeRandomHex`、`labelLooksLikeSign`）。
  学习准入只保留两项由用户显式配置的判断：排除的 bot 与排除规则。
  模块自身发起的请求仍由请求指纹机制排除，该机制是精确匹配而非推测，不受本次改动影响。

  Button learning blocked valid sign-in entries.
  Symptom: after tapping a sign-in button in some bots, the target list did not change and the UI
  showed nothing, so users concluded the target could not be added — while the same button added
  fine through the capture path. Root cause: `SignLogic.obviousNonSignButton` inferred button
  nature from three heuristics — a data prefix deny list (`pay:` / `ub_menu_` / `menu:` and
  others), a label deny list (pay / recharge / bind / menu and others), and random hex tokens.
  The design could not distinguish a menu item from a sign-in entry: in one measured bot the
  sign-in entry had data `ub_back_menu`, matching the `ub_menu_` prefix, and its label was
  "🔙 主菜单", matching the word "菜单". The filter also only recorded a log line, giving no UI
  feedback that a button had been blocked. Fix: `obviousNonSignButton` and all of its word lists
  were removed (`JUNK_DATA_PREFIXES`, `BAD_LABELS`, `SIGN_LABELS`, `SIGN_NEGATIONS`,
  `looksLikeRandomHex`, `labelLooksLikeSign`). Learning admission now keeps only two judgements
  the user configures explicitly: blocked bots and exclusion rules. Requests the module itself
  sends are still excluded by the request-fingerprint mechanism, which matches exactly rather
  than guessing, and is unaffected by this change.

- **移除「关键词过滤」开关**
  现象：设置 → 学习行为中的「关键词过滤」开启后，只有文案命中学习关键词的按钮才会被学习，
  未命中的按钮点击后无反应。
  根因：该开关的判据是按钮文案，与上一条属同类推测；且同一开关同时承担「识别签到文本」
  与「过滤按钮」两种语义，用户难以判断关闭后会影响哪一部分。
  修复：移除该开关及其字段、界面控件与字典项。学习关键词保留，仅用于网络层识别签到文本，
  设置页标签已注明用途。

  Removed the keyword-filter switch.
  Symptom: with "Keyword filter" enabled under Settings → Learning, only buttons whose label
  matched a learning keyword were learned; taps on other buttons did nothing. Root cause: the
  switch judged by button label, the same kind of inference as the entry above, and it carried
  two meanings at once — recognising sign-in texts and filtering buttons — making it unclear
  which part a user would affect by turning it off. Fix: the switch, its field, its UI control
  and its dictionary entries were removed. Learning keywords remain, now used only by the network
  layer to recognise sign-in texts; the settings label states this purpose.

- **移除启动时的目标自动清理**
  现象：升级或重启后，目标列表中的条目在用户未操作的情况下消失。
  根因：`sweepLearnedJunkEntries` 在每次启动时遍历全部账号，删除 data 命中前缀黑名单的回调目标。
  该清理复用同一份前缀名单，因此继承同一误判：前缀匹配到的真签到目标会在启动时被静默删除。
  修复：移除该清理逻辑。由于前缀黑名单本身已删除，已无误删来源；
  历史上被误删的目标需重新点击一次按钮添加。

  Removed the automatic target cleanup at startup.
  Symptom: after an upgrade or restart, entries disappeared from the target list without user
  action. Root cause: `sweepLearnedJunkEntries` walked every account on each start and deleted
  callback targets whose data matched the prefix deny list. It reused the same list, inheriting
  the same misjudgement: a genuine sign-in target matched by prefix was deleted silently at
  startup. Fix: the cleanup was removed. Since the prefix list itself is gone there is no longer
  a source of false deletion; targets removed in the past need one more button tap to re-add.

### 变更 · Changed

- **学习行为设置项调整**
  设置 → 学习行为中原有的「关键词过滤」已移除，该分区现在包含「按钮学习」「网络学习」
  「网络学习需确认」三项。帮助页面对应说明已同步更新。

  Learning settings adjusted.
  "Keyword filter" has been removed from Settings → Learning, which now holds "Button learning",
  "Network learning" and "Network learning needs confirmation". The matching help text was
  updated as well.

## 1.6.1 (124) — 2026-09-28

> 本版承接 1.6.0 的账号隔离重构，改动全部源自用户实际反馈与线上日志复盘，
> 覆盖签到状态机、按钮学习过滤、账号槽位遍历三条主线。
> 共修复 9 项问题、新增 2 项能力，每项均落到可验证的判定逻辑上，
> 并固化为回归用例（单测 174 → 297 条）。

### 修复 · Fixed

- **目标列表自动增殖：模块自身发起的回调被误判为用户学习**
  现象：用户在未执行任何操作的情况下，目标列表自行新增条目，且每次签到后可能继续增加。
  根因：回调按钮的 data 由 bot 在每次推送时重新生成。模块执行签到时点击面板中最新的按钮，
  其 data 与目标中保存的旧值不一致；请求发出后，网络层 hook 以 data 精确匹配
  （`findCbEntry`）无法定位既有目标，遂将其作为用户新点击的按钮写入学习流程，形成自我繁殖。
  修复：发送前登记请求指纹（`did` + `data`），网络层命中该指纹时跳过学习。
  同时补齐实测遗漏的黑名单项（如 `mp_help`）。

  Targets multiplied automatically: the module's own callbacks were misread as user learning.
  Symptom: targets appeared in the list without user action, and could keep increasing after each
  sign-in. Root cause: callback button data is regenerated by the bot on every push. When signing
  in, the module taps the freshest button on the panel, whose data differs from the value stored
  for the target. After the request was sent, the network hook matched targets by exact data
  equality (`findCbEntry`) and failed to locate the existing target, so it routed the tap into the
  learning flow as a new user action, producing self-replication. Fix: a request fingerprint
  (`did` + `data`) is registered before sending; when the network layer matches that fingerprint
  it skips learning entirely. Observed omissions in the deny list (such as `mp_help`) were also
  filled in.

- **「已发出」状态回退导致重复签到**
  现象：目标显示「已发出」后，状态在一段时间后回退为「待签」，请求被再次发出。
  根因：1.6.0 引入的「已发出」状态存在生命周期缺口。回调类目标发出后仅写入乐观标记
  `sent_at_`，「已签」需等待回复判定写入。若 bot 将结论置于 callback answer 而不另行
  发送消息，回复判定永不触发；`sent_at_` 超过 30 分钟时效后，状态回退为「待签」，
  被巡检重新排期。
  修复：发出超过 10 分钟仍无结论时，状态转为「待确认」而非回退「待签」，
  并复用既有处置入口（确认已签／重试／忽略今天）。判定逻辑集中于
  `SignLogic.sentPhase` 与 `shouldPromoteToPending`，由心跳、定时巡检、界面渲染三处共同触发。

  Sent-state rollback caused duplicate sign-ins.
  Symptom: after a target showed "sent", the state later reverted to "pending" and the request was
  sent again. Root cause: the "sent" state introduced in 1.6.0 had a lifecycle gap. A callback
  target only writes the optimistic marker `sent_at_` when sent, while "signed" requires a reply
  verdict. If the bot places its verdict in the callback answer without sending a separate message,
  the verdict never arrives; once `sent_at_` exceeded its 30-minute window the state reverted to
  "pending" and the scheduler re-armed the target. Fix: after 10 minutes without a verdict the
  state moves to "needs confirmation" instead of reverting, reusing the existing action entries
  (confirm / retry / ignore today). The decision is centralised in `SignLogic.sentPhase` and
  `shouldPromoteToPending` and is triggered from the heartbeat, the scheduled sweep and UI
  rendering.

- **非签到按钮被纳入学习范围**
  现象：模块将某 bot 的支付按钮学习为签到目标并实际触发，条目数量持续增长
  （同类回调由 2 次增至 15 次）。
  根因：学习入口在「宽松模式」下直接放行。该模式的设计意图是「判定词不匹配时仍允许学习」，
  不应扩展至「明显非签到按钮」。
  修复：新增独立于宽松模式的硬性闸门，按 data 前缀、按钮文案、随机 token 特征三类判据拦截。
  判据遵循「不确定即放行」原则——误挡真实签到按钮的代价远高于多学一个条目。

  Non-check-in buttons entered the learning scope.
  Symptom: the module learned a bot's payment buttons as check-in targets and actually triggered
  them, with entries growing continuously (one callback class went from 2 to 15 occurrences). Root
  cause: the learning entry passed everything through in "loose mode", whose intent is to allow
  learning when verdict words do not match — not to admit buttons that are plainly not check-ins.
  Fix: a hard gate independent of loose mode now blocks entries by data prefix, button label and
  random-token characteristics. The criteria follow a "pass when uncertain" principle: wrongly
  blocking a real check-in button is far more costly than learning one extra entry.

- **网络层学习的账号归属错误**
  现象：学习日志出现同一行内账号前缀与内容不符（前缀为账号2、内容属于账号1）。
  根因：该路径在异步回调中读取「当前账号」。异步回调期间账号可能已切换，
  此即 1.6.0 已针对其他路径修复的串号根因，本路径遗漏。
  修复：改用本次网络请求所属账号（`hookAccount`），不再读取 `currentAccount()`。

  Incorrect account attribution in network-layer learning.
  Symptom: learning logs showed an account prefix and content belonging to different accounts on
  the same line. Root cause: that path read the "current account" inside an asynchronous callback.
  The account may change during the callback — the same crossover root cause 1.6.0 fixed elsewhere,
  missed on this path. Fix: it now uses the account that owns the network request (`hookAccount`)
  instead of `currentAccount()`.

- **账号索引钳制范围过宽**
  现象：多账号环境下读取到其他账号的配置与目标。
  根因：账号索引此前被钳制至「已登录账号数 − 1」。宿主 `selectedAccount` 返回的值并非账号
  序号——部分客户端（Nagram 实测）返回 7、9 等值，均为合法索引，其数据存于对应分区。
  钳制导致模块读取错误分区。
  修复：索引归一化仅处理负值，不再设上限。真实槽位改由 `SharedConfig.activeAccounts`
  读取，界面序号与真实索引分离。

  Account index clamping was too broad.
  Symptom: in multi-account setups the module read another account's configuration and targets.
  Root cause: the index was clamped to "signed-in count − 1". The value returned by the host's
  `selectedAccount` is not an account ordinal — some clients (Nagram observed) return 7 or 9, both
  legitimate indexes whose data lives in the corresponding partition. Clamping made the module read
  the wrong partition. Fix: index normalisation now only handles negative values and no longer
  applies an upper bound. Real slots are read from `SharedConfig.activeAccounts`, and display
  ordinals are decoupled from real indexes.

- **停用账号仍执行签到**
  现象：账号标记为「停用」后，仍会在签到窗口、打开会话、面板事件补签、定时巡检、
  排队等路径下执行签到。
  根因：账号停用状态此前仅在三处生效（账号一览徽章、「签全部账号」循环、
  心跳遍历非当前账号时），而统一的签到判定入口（`sendSign` 的 `_gate.disabled`）
  未包含该项，导致开关未实际约束签到行为。
  修复：将账号停用状态并入统一判定闸，并置于判定序列最前，手动操作亦不放行——
  否则停用后单次手动签到即可绕过，与开关语义相悖。
  同时修正两处副作用：停用账号不再维持心跳高频轮询（由 45 秒降至 10 分钟档），
  定时巡检不再为其排入任务。

  Disabled accounts still performed sign-ins.
  Symptom: after an account was marked "disabled", sign-ins still ran from the sign-in window,
  opening a chat, panel-event make-up, the scheduled sweep and the queue. Root cause: the disabled
  flag was previously honoured in only three places (the overview badge, the sign-all loop, and the
  heartbeat when iterating non-current accounts), while the unified sign-in gate (`sendSign`'s
  `_gate.disabled`) did not include it, so the switch did not actually constrain sign-in behaviour.
  Fix: the disabled flag now feeds the unified gate and is evaluated first; manual actions do not
  bypass it either — otherwise a single manual sign-in after disabling would defeat the switch.
  Two side effects were also corrected: a disabled account no longer keeps the heartbeat polling at
  high frequency (45 seconds down to the 10-minute tier), and the scheduled sweep no longer arms
  tasks for it.

- **批量签到重复发送已签目标**
  现象：执行「全部签到」或「签全部账号」时，当日已签目标被重新发送；
  在定时模式开启时，全天计划被一次性发出。
  根因：两个批量入口均传递 `force=true`，而 `manual` 语义为「用户显式操作，豁免当日进度限制」，
  该豁免同时绕过了「今日已签」判定。
  修复：将 `manual` 拆分为两个维度——`manual` 保持原有语义（豁免节流、重试上限、退避），
  新增 `skipSigned` 用于批量语义（当日已有结论者跳过，含已签与已发出待确认）。
  批量入口启用 `skipSigned`，单目标「立即签到」保留强制重发能力。

  Batch sign-in re-sent targets already signed.
  Symptom: "sign all" and "sign all accounts" re-sent targets already signed that day; with the
  timer enabled, the entire day's plan was issued at once. Root cause: both batch entry points
  passed `force=true`, and `manual` means "the user acted explicitly, exempt from today's progress
  limits" — an exemption that also bypassed the already-signed check. Fix: `manual` is split into
  two dimensions — `manual` keeps its original meaning (exempt from throttling, retry cap and
  backoff), and a new `skipSigned` carries the batch semantics (skip anything with a verdict today,
  including signed and sent-and-awaiting). Batch entry points enable `skipSigned`; the
  single-target "sign now" retains forced re-send.

- **账号一览遗漏非连续槽位账号**
  现象：账号一览对部分账号显示「还没有目标」，而该账号实际存在目标；
  另有用户反馈某账号在停用后即不再显示。
  根因：账号遍历采用连续区间 `0..已登录数−1`，而非真实槽位。真实槽位来自宿主
  `SharedConfig.activeAccounts`，部分客户端（Nagram 实测）返回 7、9 等非连续索引。
  以槽位 `{0,1,7}` 为例：界面「账号3」实际读取空分区 `acc2_`，真实数据位于 `acc7_`，
  遍历未能覆盖。
  附带影响：启动时的孤儿状态键清理采用相同遍历方式，非连续槽位账号的状态键被判定为孤儿
  并删除（涉及 `sent_at_`、`opt_`、`frozen_` 等中间状态，不涉及目标本身）。
  修复：11 处账号遍历统一改为真实槽位。槽位连续时返回值与原有实现一致，行为不变，
  仅在非连续槽位场景体现修复。同时新增构建门禁，源码中再次出现连续区间账号遍历
  将直接导致构建失败。

  The account overview omitted accounts on non-contiguous slots.
  Symptom: the overview showed "no targets yet" for some accounts that did have targets; another
  user reported that an account stopped appearing after being disabled. Root cause: account
  iteration used the contiguous range `0..signed-in-count−1` instead of real slots. Real slots come
  from the host's `SharedConfig.activeAccounts`, and some clients (Nagram observed) return
  non-contiguous indexes such as 7 or 9. With slots `{0,1,7}`, the UI's "account 3" actually reads
  the empty partition `acc2_` while the real data lives under `acc7_`, which iteration never
  reached. Secondary impact: the startup orphan sweep used the same iteration, so state keys for
  non-contiguous accounts were classified as orphans and deleted (intermediate state such as
  `sent_at_`, `opt_` and `frozen_`, not the targets themselves). Fix: all 11 account iterations now
  use real slots. When slots are contiguous the values are identical to the previous implementation,
  so behaviour is unchanged; the fix shows only on non-contiguous slots. A build gate now fails the
  build if contiguous-range account iteration reappears in the source.

- **明确标识签到的按钮被过滤拦截（上一版引入的回归）**
  现象：同一面板内两个真实签到按钮，「🎯 签到」可正常学习，「✅ 每日签到」无法学习，
  须经「添加目标」手动捕获；手动捕获路径不经过该过滤，故不受影响。
  根因：上一版新增的非签到按钮过滤完全以 data 为判据。data 由 bot 定义，
  而用户可见信息为按钮文案；当文案明确为签到、data 恰落入随机 token 特征区间时被误判拦截。
  修复：判据调整为文案优先。按钮文案命中签到白名单（签到、打卡、checkin、signin、claim、
  领取等）时无条件放行，不再检查 data；配套否定词（记录、历史、说明、规则、教程、统计、
  排行榜）确保「签到记录」等非按钮条目不被纳入。网络层学习同步改为从面板快照按 data
  反查真实按钮文案，使两条学习路径判据一致。启动清理同样采用文案优先，避免误删真实签到条目。

  Buttons clearly labelled as check-ins were blocked (regression introduced in the previous build).
  Symptom: within the same panel, "🎯 签到" could be learned normally while "✅ 每日签到" could not
  and required manual capture via "add target"; manual capture does not pass through the filter, so
  it was unaffected. Root cause: the non-check-in button filter added in the previous build relied
  entirely on data. data is defined by the bot, while the information visible to users is the button
  label; when a label clearly indicated a check-in but its data happened to fall within the
  random-token characteristics, it was wrongly blocked. Fix: the criteria are now label-first. When
  a button label matches the check-in allowlist (签到, 打卡, checkin, signin, claim, 领取, etc.) it
  passes unconditionally without inspecting data; companion negation words (records, history, help,
  rules, tutorial, stats, ranking) keep non-button entries such as "签到记录" from qualifying.
  Network-layer learning now looks up the real button label from the panel snapshot by data so both
  learning paths apply identical criteria. Startup cleanup is label-first as well, to avoid deleting
  real check-in entries.

### 新增 · New

- **新增两个适配客户端，并修正默认作用域**
  `tw.nekomimi.nekogram`（Nekogram）与 `it.belloworld.mercurygram`（Mercurygram）纳入白名单与
  作用域清单，已完成真机注入验证。
  同时修正一处自 1.6.0 起存在的配置不一致：`module.prop` 的 `scope=` 仅列出 3 个包，
  而 `scope.list` 为 8 个。`staticScope=true` 时 LSPosed 以 `module.prop` 的 `scope=`
  作为默认作用域，导致新装用户仅勾选这 3 个包，使用 Nagram XF、NagramX 等客户端的用户
  在作用域列表中找不到自己的客户端。
  现 `module.prop`、`scope.list` 与 `Hosts.KNOWN` 三处保持一致，均为 10 个包，
  新装用户的默认作用域与验证环境完全一致。

  Two more supported clients, and a corrected default scope.
  `tw.nekomimi.nekogram` (Nekogram) and `it.belloworld.mercurygram` (Mercurygram) joined the
  whitelist and the scope list, with real-device injection verified. A configuration inconsistency
  present since 1.6.0 was also corrected: `module.prop`'s `scope=` listed only 3 packages while
  `scope.list` had 8. With `staticScope=true`, LSPosed treats `module.prop`'s `scope=` as the
  default scope, so new installs only had those 3 packages ticked and users of Nagram XF, NagramX
  and similar clients could not find their client in the scope list. `module.prop`, `scope.list` and
  `Hosts.KNOWN` are now consistent at 10 packages, so a fresh install receives the same default
  scope as the verified environment.

- **误学条目清理日志**
  启动时清理已误学目标将记录日志，列出被移除条目及其判定原因，便于用户核对。

  Cleanup logging for mislearned entries.
  Startup cleanup of mislearned targets now writes a log listing each removed entry and the reason
  for its removal, so users can verify the outcome.

### 工具 · Tooling

- **README 更新日志生成器支持成块对照格式**
  生成器此前仅识别内联格式（`- **中文 · English**`），而 1.6.0 的 CHANGELOG 已改为
  中英成块对照，导致英文段落无法生成。现同时支持两种格式，并补充节名映射与超长首句截断。

  README changelog generator supports block-style bilingual entries.
  The generator previously recognised only the inline form (`- **Chinese · English**`), while
  1.6.0's CHANGELOG had switched to block-style bilingual entries, so the English section could not
  be generated at all. Both forms are now supported, with section-name mapping and long-first-
  sentence truncation.

- **新增「README 更新日志同步」构建门禁**
  以生成器重算段落并与 README 中现有段落逐字节比对，不一致即构建失败，
  将发布流程中的手工步骤转为构建期校验。

  New build gate: README changelog must stay in sync.
  The generator recomputes the section and compares it byte-for-byte with the existing section in
  the README; a mismatch fails the build, turning a manual release step into a build-time check.

- **纯逻辑单测 174 → 297 条**
  新增五组用例：`sentPhaseLifecycle`（状态三态、时钟回拨、缺失时间戳的保守处理、
  升级条件互斥）、`nonSignButtonFilter`（实测非签到按钮必须拦截，真实签到按钮必须放行，
  含边界）、`accountDisabled`（账号级停用不可被手动绕过，优先级高于在途与已签）、
  `batchSkipSigned`（批量仅签未签目标，单目标保留强制重发，停用优先于跳过）、
  时间展示（补签判定阈值、时间格式化、相对时间文案、历史数据回退）。
  用户反馈的每个现象均已固化为回归用例。

  Pure-logic unit tests grew from 174 to 297.
  Five new groups: `sentPhaseLifecycle` (three-state lifecycle, clock rollback, conservative
  handling of missing timestamps, mutually exclusive promotion conditions),
  `nonSignButtonFilter` (observed non-check-in buttons must be blocked, real check-in buttons must
  pass, boundaries included), `accountDisabled` (the account-level switch cannot be bypassed
  manually and outranks in-flight and already-signed), `batchSkipSigned` (batch signs only unsigned
  targets, single-target retains forced re-send, disabled outranks skipping) and time display
  (make-up threshold, time formatting, relative time wording, fallback for legacy data). Every
  reported symptom is now a regression case.

## 1.6.0 (123) — 2026-09-26

> 累积更新：自 1.5.8 以来的全部改动合并发布，含一次账号隔离体系重构。

### 架构更新 · Architecture

> 自 v1.0 以来最大的一次内部重构。**无用户可见行为变化**，但它是本次大量 bug 得以根治的前提。
> The largest internal refactor since v1.0. **No user-visible behaviour change**, but it is the
> precondition that made this release's bug fixes possible.

- **账号隔离从「约定」变成「结构」**
  重构前，代码里有 75 处读「当前账号」、67 处读无参账号前缀 —— 全部读宿主的静态字段。
  任何一处出现在异步回调或延迟任务里，就会串号。本次引入不可变账号快照（`Ctx`）：
  **发起任务时锁定账号，执行时只认快照**，从结构上杜绝串号，而不是靠每一处记得判断。

  Account isolation moved from convention to structure.
  Before the refactor the codebase read the "current account" in 75 places and the no-arg account
  prefix in 67 — all reading a host static field. Any one of them sitting inside an asynchronous
  callback or delayed task caused crossover. An immutable account snapshot (Ctx) is now used
  instead: **the account is pinned when work is started and only the snapshot is honoured when it
  runs**, preventing crossover structurally rather than relying on every call site remembering to
  check.

- **核心文件拆出 6 个职责单一的类**
  `Keys`（存储键唯一真相源）、`AccountManager`（账号解析与快照）、
  `PrefsStore`（持久化与落盘策略）、`SignStateStore`（签到状态读写与不变式）、
  `SettingsRefs`（设置面板控件容器）、`SignLogic`（无 Android 依赖的纯逻辑层）。
  主文件仍承载业务编排，但每类问题都有了明确归属。

  Six single-responsibility classes extracted from the core file.
  Keys (single source of truth for storage keys), AccountManager (account resolution and snapshot),
  PrefsStore (persistence and flush policy), SignStateStore (sign-in state I/O and invariants),
  SettingsRefs (settings panel control container) and SignLogic (pure logic with no Android
  dependency). The main file still orchestrates the business, but every class of problem now has a
  clear home.

- **纯逻辑可单测**
  时间窗、退避、回复判定、签到闸门等逻辑已与 Android API 解耦，可在桌面直接跑断言 ——
  本版单测从 115 条扩到 174 条，新增用例逐条对应本次修掉的 bug。

  Pure logic is now unit-testable.
  Time windows, backoff, reply verdicts and the sign gate are decoupled from Android APIs and can
  be asserted directly on a desktop — assertions grew from 115 to 174 in this release, each new
  case mapping to a bug fixed here.

- **存储键名收敛到唯一真相源**
  键生成函数原来散落在核心文件的 4 处，改一个键名要 grep 全文且极易漏。
  （历史事故：日志名从 `run.log` 改为 `run-YYYYMMDD.log` 时漏改 4 处，静默失效。）

  Storage keys consolidated into a single source of truth.
  Key-building helpers were scattered across four places in the core file; renaming one key meant
  grepping the whole file and was easy to miss. A past incident: the log file name change from
  run.log to run-YYYYMMDD.log missed four call sites and failed silently.

- **落盘策略显式化**
  原来 244 处直接访问存储，落盘方式（apply / commit）没有任何规则。
  现在状态变更一律 `commit`，其余 `apply`，并明确哪些变更必须落盘。

  Flush policy made explicit.
  There were 244 direct storage accesses with no rule for apply versus commit. State changes now
  always commit, everything else applies, and which changes must persist is explicit.


### 修复 · Fixed

- **账号串号：六条路径全部改为锁定账号（重要）**
  病根只有一个 —— **在异步回调或延迟任务里读「当前账号」**。回调执行时用户可能已经切到别的账号，
  于是拿新账号去发旧账号的目标。本次把这条根因的落点全部扫清，覆盖六条路径：
  定时任务（延迟 1~5 分钟）、回调签到等面板（异步最长 8 秒）、面板事件补签（延迟 700 毫秒）、
  网络层学习、收藏夹告警、结果归因与汇总通知。全部改为**在发起时锁定账号**，执行时只用锁定值。
  此前用户报告「账号1自动给只有账号2配置的 bot 发了消息」，即由此而来。

  Account crossover — all six paths now pin the account.
  There was a single root cause: reading the "current account" inside an asynchronous callback or a
  delayed task. By the time the callback runs the user may have switched accounts, so a new account
  sent an older account's target. This release clears every instance across six paths: scheduled
  tasks (1–5 min delay), callback sign-in waiting for a panel (up to 8 s async), panel-triggered
  make-up sign-in (700 ms delay), network-layer learning, saved-message alerts, and result
  attribution plus summary notifications. All of them now capture the account when the work is
  initiated and use only that captured value. This is what produced the report "account 1
  automatically messaged a bot that only exists on account 2".

- **越界保护反而读到别人的分区（重要）**
  v1.5.8 加了一道保护：读到超出已登录数的索引就按 0 处理。出发点是好的，但**前提是错的** ——
  实测部分客户端（如 Nagram XF 登录 4 个账号）`selectedAccount` 会读到 7、9，
  **那些是合法索引**，数据就存在 `acc9_` 里。被改成 0 之后，模块读的是**另一个账号**的分区：
  目标、已签记录、重试退避全部错位，表现为「账号1的配置变成了账号2的」。
  现已回退：索引原值使用，只有负值（不可能合法）才跳过本轮。

  Out-of-range guard read another account's partition (important).
  v1.5.8 added a guard that coerced an index beyond the signed-in count to 0. The intent was
  reasonable, but the assumption was wrong: on some clients (e.g. Nagram XF with four accounts)
  selectedAccount reads 7 or 9, and those are legitimate indexes whose data lives under acc9_.
  Coercing to 0 made the module read another account's partition, misplacing targets, signed state
  and backoff — reported as "account 1's config turned into account 2's". Reverted: the index is
  used as-is, and only negative values (which cannot be valid) skip the round.

- **失败目标被反复重签（重要）**
  机器人连回两条消息是常态，两条消息的判定结果会互相抵消：第一条「正在签到,请稍后…」
  被宽松模式当作成功并把重试计数清零，第二条「请先关注」判失败只把计数加到 1 ——
  计数永远在 0 和 1 之间震荡，涨不到上限，于是每一轮都被重新选中。**实测有目标一天被签了 8 次。**
  现在加两道熔断：**确定性失败词**（请先关注 / 活动已结束 / 已过期 等）命中即当日停止；
  其它失败用**当天独立计数**（不受"成功清零"影响）累计 3 次后同样当日停止。跨天自动恢复。

  Failed targets were signed over and over (important).
  Robots commonly send two replies whose verdicts cancel each other out: the first ("signing in,
  please wait") is treated as success by loose mode and resets the retry counter, while the second
  ("please follow first") counts as a failure and bumps it to 1 — so the counter oscillates between
  0 and 1, never reaching the cap, and the target is re-selected every round. One target was signed
  8 times in a single day. Two circuit breakers are now in place: permanent-failure phrases
  (follow-first / event ended / expired) stop the target for the day immediately, and other failures
  accumulate in a day-scoped counter that is immune to the success reset. Both reset automatically
  the next day.

- **同一账号重复排期发送（重要）**
  排下一次定时任务时只检查了「内存中是否正在发送」，漏了「已发出但还没等到结论」。
  内存状态在进程重启后是空的，于是重启后一遇网络恢复之类的触发，就会把同一个目标再排一次 ——
  现象是同一账号同一目标被连发两条。现在两半一起查，并统一到一个判断入口，避免以后再分叉。

  Duplicate scheduling within one account (important).
  Scheduling the next timed task only checked "is a send in flight in memory", missing the other
  half: "already sent, still awaiting a verdict". In-memory state is empty after a process restart,
  so the first trigger — such as network recovery — queued the same target again, sending twice to
  the same account and target. Both halves are now checked together behind a single entry point so
  they cannot diverge again.

- **明明签到成功，却被判失败并退回「退避中」（重要）**
  回调按钮签到分两步。当前置命令已经完成签到、而第二步的按钮因为**消息已更新**被 Telegram 拒绝
  （`MESSAGE_ID_INVALID`）时，模块把「第二步失败」当成了「签到整体失败」：先撤销已签标记，
  再排一次退避重试。用户侧看到的是**已经签过了，状态却是「退避中」**，10 分钟后又白跑一次。
  `MESSAGE_ID_INVALID` 只是"按钮过期了"，**不代表签到失败**。现已改为：前置命令确认发送成功时，
  按钮过期不再撤销已签，只清掉退避状态。

  A successful check-in was revoked and shown as "backoff" (important).
  Callback sign-in runs in two steps. When the pre-command had already completed the check-in but
  the second step's button was rejected by Telegram because the message had been updated
  (MESSAGE_ID_INVALID), the module treated "step two failed" as "the whole check-in failed": it
  revoked the signed marker and scheduled a backoff retry. Users saw a completed check-in displayed
  as "backoff", followed by another pointless attempt ten minutes later. MESSAGE_ID_INVALID only
  means the button is stale, not that the check-in failed. Now, when a pre-command was accepted, a
  stale button no longer revokes the sign-in — only the backoff state is cleared.

- **「等面板」永远超时走兜底（重要）**
  回调签到靠"面板刷新事件"驱动。但该事件的唯一入口开头有一句"启动后 30 秒未就绪就直接返回"，
  于是**启动窗口内面板事件被整条丢弃** → 等面板必然超时 → 只能走 8 秒兜底 msg_id，
  而兜底用的旧按钮往往已过期 → 报 `MESSAGE_ID_INVALID`。用户看到的是
  **机器人明明秒回，模块却一直走兜底**。现在未就绪只限制"模块主动发起的动作"，不再丢被动事件。

  Panel waiting always timed out into the fallback (important).
  Callback sign-in is driven by panel-refresh events, but the only entry point for those events
  began with "return if not ready for the first 30 seconds" — so during that window the events were
  dropped entirely, waiting always timed out, and the flow fell back to an 8-second stale msg_id
  that reported MESSAGE_ID_INVALID. Users saw the bot reply instantly while the module kept falling
  back. Not-ready now only throttles actions the module initiates, never passive events.

- **同类问题全量清查：还有三处会把成功当失败**
  同一个病根还有三处，全部无条件撤销了已签：① bot 不回结果（`BOT_RESPONSE_TIMEOUT`）——
  有些机器人本来就不回复签到结论；② 服务器限流（`FLOOD_WAIT`）—— 限流只是"稍后再试"，
  不代表没签上；③ 其他请求层错误 —— 请求已经成功发出并标了已签，后续报错不足以否定它。
  现在统一用「是否已乐观标记为已签」来区分：已发出且标记成功时，后续第二步失败只清退避状态。

  Same class of bug, full sweep: three more places treated success as failure.
  Three more instances of the same root cause unconditionally revoked the sign-in: (1) the bot never
  replying (BOT_RESPONSE_TIMEOUT) — some robots simply never send a verdict; (2) server rate
  limiting (FLOOD_WAIT) — that only means "try again later", not that the check-in failed; (3) other
  request-layer errors — the request had already been sent and marked. All now share one rule: if
  the send succeeded and was marked, a later second-step failure clears only the backoff state.

- **「待确认」池按账号隔离，并自动迁移旧数据**
  待确认池原来是一个全局键，多个账号的候选目标混在一起，点「加入」还可能加到错的账号。
  现按账号分键存储，并提供一次性迁移：旧数据搬到账号 1，**若账号 1 已有内容则保留现有、不覆盖**，
  迁移完成后删除旧键。整个过程幂等，只执行一次。

  The "pending" pool is now per-account, with automatic migration.
  The pending pool used to be one global key, mixing candidates from every account, so tapping
  "Add" could file a target under the wrong one. It is now stored per account, with a one-time
  migration: legacy data moves to account 1, existing account-1 content wins (never overwritten),
  and the old key is removed afterwards. The whole step is idempotent and runs once.

- **「待确认」是个死状态：看得到、点不动（重要）**
  这个状态以前只写不读 —— 置位后除了"手动测试"没有任何清除入口，用户永远卡在「待确认」，
  而重试计数已被清零 → 每天照发、照超时、照标待确认（死循环）。现在给出三个明确动作
  （重试 / 忽略 / 删除），并把用户的选择记下来。

  "Pending" was a dead state: visible but un-actionable (important).
  The state used to be write-only: once set there was no way to clear it apart from a manual test,
  so users were stuck on "pending" forever while the retry counter had already been reset —
  sending, timing out and re-marking every day. Three explicit actions are now offered (retry /
  ignore / delete) and the user's choice is recorded.

- **回复判定用错账号（重要）**
  回复判定在异步回调里执行，读的是「当前账号」的目标列表。切过账号之后，
  机器人发来的结论会被记到别的账号上。现在按消息所属账号取目标列表。

  Reply verdict could land on the wrong account (important).
  Reply verdicts ran in an async callback and read the current account's target list. After an
  account switch, a bot's verdict was recorded against the wrong account. The list is now taken
  from the account the message belongs to.

- **全账号签到时结果串到别的账号**
  结果统计内部读「当前账号」，于是账号 A 的成绩会显示成账号 B 的。现在改为显式传入账号，
  并按账号独立聚合、逐账号提示与汇总通知。

  A full-account round reported one account's tally as another's.
  Result tallying read the "current account" internally, so account A's score was shown as account
  B's. It now takes an explicit account, aggregates per account, and reports and notifies per
  account.

- **清空配置会残留状态，导致重新添加的 bot 状态复活（重要）**
  条目 id 按 `<会话>_<序号>` 生成，清空配置后重新添加同一个 bot 会拿到同一个 id，
  残留的冻结 / 暂停 / 已放弃状态被新条目直接继承，表现为"重新添加了但它就是不签"。
  现在补齐了 v1.3.0 之后新增的全部状态键前缀，并增加孤儿状态键清理。

  Clearing config left state behind, resurrecting it on re-add (important).
  Entry ids are generated as `<dialog>_<seq>`, so re-adding the same bot after clearing config
  yields the same id and inherits leftover frozen / snoozed / given-up state — it "just won't sign
  in" after re-adding. All state-key prefixes added since v1.3.0 are now covered, plus orphan-state
  cleanup.

- **清空配置会误删账号级待确认池**
  清空配置的保留名单是精确字符串匹配，表达不了 `acc<N>_` 这种不定后缀，于是旧的全局池被保留、
  新的账号级池反而被删掉 —— 同一份数据换个键名后行为不一致。现在单独判定并保留。

  Clearing config wrongly deleted the per-account pending pool.
  The keep-list used exact string matching, which cannot express the variable `acc<N>_` prefix, so
  the legacy global pool was kept while the new per-account pool was deleted — the same data
  behaved differently under a new key name. It is now matched and kept explicitly.

- **按钮反复过期导致无限重试**
  有些机器人的按钮随消息变化，每次重新拉取面板都会换一套 msg_id，模拟点击永远追不上。
  模块会一直"拉新面板 → 点击 → 过期 → 再拉"，一天白跑十几次。现在同一目标连续 3 次过期即熔断，
  日志直接给出出路（把这条改成「文本指令」目标）。

  Stale buttons caused an endless retry loop.
  Some robots regenerate their buttons with each message, so every panel refresh yields a new msg_id
  and a simulated tap can never keep up. The module kept pulling a fresh panel, tapping, failing and
  pulling again, wasting a dozen attempts a day. Three consecutive stale results now trip a circuit
  breaker, and the log states the way out: convert that target to a text-command target.

- **不回结果的机器人每天白等超时**
  查询类、菜单类机器人本来就不回复签到结论，模块仍会为它们等满超时。现在连续 3 次无响应
  即停止自动重试并标记「待确认」，交由用户处置。

  Silent robots made the module wait out a timeout every day.
  Query and menu robots never reply with a check-in verdict, yet the module still waited out the
  timeout for them. After three consecutive silent results it now stops retrying and marks the
  target "pending" for the user to decide.

- **设置界面保存时闪退**
  重构设置界面时抽出了控件容器，其中两个控件的引用只改了读取处、没改定义处，点保存时空指针。
  已修正，并对全部控件做了一次读写配对自查。

  Crash when saving in the settings screen.
  The settings refactor extracted a control container, but two controls had their reads updated
  while their definitions were not, causing a null-pointer crash on save. Fixed, plus a full
  read/write pairing audit of every control.

- **打开「运行日志」会卡顿**
  日志渲染在主线程逐行构建，长日志会明显卡顿。现在限制渲染量并改为异步读取。

  Opening "Logs" stuttered.
  Log rendering built every line on the main thread, so long logs stuttered. The amount rendered is
  now bounded and reading is asynchronous.

- **启动日志只落盘第一行**
  日志为省电做了落盘采样（INFO 连续输出只在首次落盘），而启动那几行几乎同一毫秒写出，
  结果只有首行进了文件。现已新增绕过采样的强制落盘，启动信息完整入档。

  Only the first startup line reached the log file.
  Log writing is sampled to save power (consecutive INFO lines only flush on the first), and the
  startup lines are emitted within the same millisecond — so only the first reached the file. A
  sampling-bypassing forced flush now writes them all.

- **冷启动 30 秒内点 bot 按钮毫无反应**
  未就绪窗口同样挡掉了主动学习。现在按钮学习不受该窗口限制。

  Tapping a bot button within 30 s of a cold start did nothing.
  The not-ready window also blocked active learning. Button learning is no longer gated by it.

- **跨天窗口下补签时段变成全天**
  补签时段跨天时区间判断失效，导致全天都算补签时段。

  The make-up window became all day when it crossed midnight.
  The make-up window's range check failed when it crossed midnight, making the whole day count as
  make-up time.

- **跨端同步的配置在本机不生效**
  从其他客户端同步过来的配置没有被应用。

  Configs synced from another client did not apply locally.
  Configs synced from another client were not applied on this device.

- **连点多个 bot 按钮时只有第一个能被学到**
  连续点击多个按钮时，只有第一个会被记住。

  Only the first of several bot buttons was learned.
  Tapping several bot buttons in a row only learned the first one.

- **日志里的链路编号会跨账号重复**
  链路编号原本是全局随机数，多账号并行时会撞号，同一个编号横跨两个账号，
  排查时极易误判成"串号"。现在编号带账号前缀，一眼可辨。

  Chain ids in the log could repeat across accounts.
  Chain ids were global random numbers and collided when accounts ran in parallel: one id spanning
  two accounts, easily mistaken for account crossover while troubleshooting. The id now carries an
  account prefix.

- **热重载后两个实例并行跑**
  热重载可能留下两个模块实例同时运行。

  Two instances ran in parallel after a hot reload.
  A hot reload could leave two module instances running side by side.

- **启动日志里的数字标题渲染成错误字形**
  启动日志中的数字标题会显示成错误的字形。

  Numeric headings rendered as wrong glyphs in the startup log.
  Numeric headings in the startup log rendered with the wrong glyphs.

- **花体字有豆腐块**
  部分设备上装饰性文字会显示成方框。

  Decorative text showed tofu boxes.
  Decorative text rendered as tofu boxes on some devices.

- **「待确认」的「重试」按钮用错账号**
  待确认列表里的重试按钮读的是当前账号，而不是该目标所属的账号。

  The "pending" retry button used the wrong account.
  The retry button in the pending list used the current account instead of the target's own.

### 新增 · New

- **日志与诊断包显示详细版本信息**
  启动日志现在输出：模块版本名 + 版本码、宿主友好名 + 包名 + 版本名/码、CPU 架构、
  Android 版本、注入方式（已知客户端 / 能力探测命中）、模块包名。诊断包同步补全。
  排查「装了没生效」「版本对不上」「到底哪个包在跑」不再靠猜。

  Detailed version info in logs and the diagnostics bundle.
  The startup log now reports the module version name and code, the host's friendly name, package
  and version name/code, the CPU ABI, the Android version, the injection method (known client or
  capability probe) and the module package. The diagnostics bundle matches. No more guessing
  whether the module loaded, which version is installed, or which package is running.

- **宿主友好名支持英文**
  英文界面下输出 Official / Nagram / ExteraLess，不再在英文环境里出现中文宿主名。

  Host friendly names follow the UI language.
  English UI now prints Official / Nagram / ExteraLess instead of Chinese host names.

### 工具 · Tooling

- **仓库里的 `build.sh` 缺三道门禁**
  仓库版只有国际化检查一道门禁，纯逻辑单测、更新日志双语、版本五查都在构建工具目录里 ——
  任何直接 clone 源码仓的人跑 `./build.sh` 都会绕过它们。现已补齐并新增接线自检门禁。

  The in-repo build.sh was missing three gates.
  The in-repo script only ran the i18n gate; the unit tests, bilingual changelog and version checks
  lived only in the build kit, so anyone cloning the source and running ./build.sh skipped them.
  All are now in place, plus a new wiring self-check gate.

- **纯逻辑单测从 115 条扩到 174 条**
  新增用例逐条对应本次修掉的 bug，改回去会被门禁拦住。

  Unit assertions grew from 115 to 174.
  Each new case maps to a bug fixed here, so a regression is caught by the gate.

- **接线自检的成员清单里有早已删除的方法**
  导致该检查长期空转；已修正并挂进构建。

  The wiring checker listed a method that no longer existed.
  The check had been a no-op for a long time; fixed and wired into the build.

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
