package io.github.wlmosv_png.tgautosign;

/**
 * 所有持久化键的唯一真相源（Refactor 1.6.1）。
 *
 * 为什么独立成类：
 *   重构前这些键生成函数散落在 TGAutoSignCore 的 4 处（6553/6569/6655/6892 附近），
 *   改一个键名要 grep 全文、且极易漏（历史事故：日志名 run.log→run-YYYYMMDD.log
 *   的同类漏改在 4 处静默失效）。
 *
 * 约定：
 *   - 所有 per-target 键形如 <prefix><name>_<id>，prefix 由 AccountManager 提供（"accN_"）。
 *   - 所有全局键以 "jmb_" 开头。
 *   - **本类只生成键名，不读写 prefs**。读写统一走 PrefsStore。
 */
public final class Keys {

    private Keys() {}

    // ───────────── 全局配置（jmb_ 前缀）─────────────
    public static String window()            { return "jmb_window"; }
    public static String timer()             { return "jmb_timer"; }
    public static String gap()               { return "jmb_gap"; }
    public static String missBack()          { return "jmb_missback"; }
    public static String missDeadline()      { return "jmb_missdead"; }
    public static String keywords()          { return "jmb_keywords"; }
    public static String retryLimit()        { return "jmb_retry"; }
    public static String wakeCmd()           { return "jmb_wake_cmd"; }
    public static String sort()              { return "jmb_sort"; }
    public static String fx()                { return "jmb_fx"; }
    public static String theme()             { return "jmb_theme"; }
    public static String lang()              { return "jmb_lang"; }
    public static String exclude()           { return "jmb_exclude"; }
    public static String blockedDids()       { return "jmb_blocked_dids"; }
    public static String autoLearn()         { return "jmb_autolearn"; }
    public static String autoLearnNet()      { return "jmb_autolearn_net"; }
    public static String autoLearnNetConfirm() { return "jmb_autolearn_net_confirm"; }
    public static String judge()             { return "jmb_judge"; }
    public static String judgeCustom()       { return "jmb_judge_custom"; }
    public static String loose()             { return "jmb_loose"; }
    public static String okWords()           { return "jmb_ok_words"; }
    public static String failWords()         { return "jmb_fail_words"; }
    public static String notifyOn()          { return "jmb_notify"; }
    public static String notifyFailOnly()    { return "jmb_notify_fail_only"; }
    public static String notifyAllAcc()      { return "jmb_notify_all_acc"; }
    public static String lastRound()         { return "jmb_last_round"; }
    public static String promptDay()         { return "jmb_prompt_day"; }
    public static String tutSeen()           { return "jmb_tut_seen"; }
    public static String configTs()          { return "jmb_config_ts"; }
    public static String pendingConfirm()    { return "jmb_pending_confirm"; }
    public static String pendingConfirm(int account) { return "acc" + account + "_pending_confirm"; }
    public static String dbgOverflow()       { return "jmb_dbg_overflow"; }

    // ───────────── per-target（<prefix><name>_<id>）─────────────
    /** 今日已签日期（yyyy-MM-dd）。唯一"最终已签"凭据。 */
    public static String last(String p, String id)      { return p + "last_" + id; }
    /** 今日已发出的乐观标记（与 last 分离，见 SignLogic）。 */
    public static String opt(String p, String id)       { return p + "opt_" + id; }
    public static String retry(String p, String id)     { return p + "retry_" + id; }
    public static String retryAt(String p, String id)   { return p + "retry_at_" + id; }
    public static String retryDay(String p, String id)  { return p + "retry_day_" + id; }
    public static String snooze(String p, String id)    { return p + "snooze_" + id; }
    public static String frozen(String p, String id)    { return p + "frozen_" + id; }
    public static String learned(String p, String id)   { return p + "learned_" + id; }
    public static String pendingCfm(String p, String id){ return p + "pendcfm_" + id; }
    /**
     * 「待确认」是**哪一天**标的（yyyy-MM-dd）。
     *
     * 为什么必须有（2026-10-03 修 13 次重复发送）：
     *   promoteSilentToPending 转待确认时删掉了 sent_at_ / opt_，
     *   而这两个键是「今天发过」的**唯一凭据**。
     *   于是 sweepStalePendingConfirm 在下次启动时读到 sent_at_ 为空，
     *   误判成"隔天残留"把当天刚标的 pendcfm_ 清掉 → 闸全放行 → 重发。
     *   实测 ExteraLess 上群目标被刷 13 次（10-02 重启 13 次，每次清一遍）。
     *
     * 本键与 pendcfm_ 同生共死：标待确认时一起写，用户处置时一起清。
     * 判据只看"是不是今天标的"，与 sent_at_ 存不存在无关。
     */
    public static String pendingDay(String p, String id){ return p + "pendcfm_day_" + id; }
    public static String pendingNote(String p, String id){ return p + "pendcfm_note_" + id; }
    /**
     * **执行结果归类码**（resultCode 串）。
     *
     * 为什么不复用 pendingNote：那个键的既有语义是「日期|处置动作」，
     * pendConfirmedToday 靠它判断"今天是否已被用户处置过"。
     * 两者混用会让归类被覆盖成"2026-09-28|用户点了重试"，解析失败 → 归类丢失。
     */
    public static String pendingResult(String p, String id){ return p + "pendcfm_result_" + id; }
    public static String sentAt(String p, String id)    { return p + "sent_at_" + id; }
    /** 实际签到成功的时刻（毫秒）。只在 markSigned 首次写入时落库，天然按天覆盖。 */
    public static String signedAt(String p, String id)  { return p + "signed_at_" + id; }
    /**
     * 补签触发时刻（毫秒）。只有走 sweepDue 的「错过补签」路径才写。
     * 注意：**不按天清理**（签到成功后仍要显示「补签于 HH:MM」），
     * 所以读取方必须自行判断该时间戳是否落在今天，否则昨天的补签会污染今天。
     */
    public static String missAt(String p, String id)    { return p + "miss_at_" + id; }
    /** bot 在 callback answer 里回过内容（但词表没识别出结果）。用于区分"有响应/无响应"。 */
    public static String answered(String p, String id)  { return p + "answered_" + id; }
    // ── 2026-10-06 判定词作用域（交接单第八条）────────────────
    // 三级：全局 / Bot / 目标。旧键 jmb_ok_words、jmb_fail_words 是「全局」级，
    // 继续原样读写，不迁移、不丢失。
    ///
    public static String okWordsGlobal()  { return "jmb_ok_words"; }
    public static String failWordsGlobal(){ return "jmb_fail_words"; }
    public static String okWordsBot(long did)   { return "jmb_ok_bot_" + did; }
    public static String failWordsBot(long did) { return "jmb_fail_bot_" + did; }
    public static String okWordsTarget(String entryId)   { return "jmb_ok_tgt_" + entryId; }
    public static String failWordsTarget(String entryId) { return "jmb_fail_tgt_" + entryId; }

    /**
     * 当日「发出后仍无结论」的发送次数（2026-10-01）。
     *
     * 为什么需要：V_UNKNOWN 既不写 kLast 也不涨 kRetry，所有熔断闸都失效，
     * 心跳每 45 秒重发一次（实测群聊被刷 13 条）。本计数给发送次数一个硬上限，
     * 与词表、与 bot 类型无关 —— 任何情况下发满 MAX_SEND_ATTEMPTS 即转「待确认」。
     * 按天记（值形如 "yyyy-MM-dd|3"），跨天自动归零。
     */
    public static String sendAttempts(String p, String id){ return p + "send_n_" + id; }
    public static String title(String p, String id)     { return p + "title_" + id; }
    public static String kind(String p, String id)      { return p + "kind_" + id; }
    public static String did(String p, String id)       { return p + "did_" + id; }
    public static String data(String p, String id)      { return p + "data_" + id; }
    public static String hash(String p, String id)      { return p + "hash_" + id; }
    public static String msgId(String p, String id)     { return p + "msg_id_" + id; }
    public static String pre(String p, String id)       { return p + "pre_" + id; }
    public static String loc(String p, String id)       { return p + "loc_" + id; }
    public static String peerKind(String p, String id)  { return p + "peerkind_" + id; }
    public static String failStreak(String p, String id){ return p + "fail_streak_" + id; }
    public static String failLastStamp(String p, String id) { return p + "fail_laststamp_" + id; }
    public static String failAlert(String p, String id) { return p + "fail_alert_" + id; }
    public static String failDate(String p, String id)  { return p + "fail_date_" + id; }
    public static String panelStale(String p, String id){ return p + "panelstale_" + id; }
    public static String panelStaleDay(String p, String id) { return p + "panelstale_day_" + id; }
    public static String silent(String p, String id)    { return p + "silent_" + id; }
    public static String silentDay(String p, String id) { return p + "silent_day_" + id; }
    public static String unknownReply(String p)         { return p + "unknown_reply"; }

    // ───────────── per-account ─────────────
    public static String signDays(String p)     { return p + "sign_days"; }
    public static String streak(String p)       { return p + "streak"; }
    public static String lastSignDate(String p) { return p + "last_sign_date"; }
    public static String timerPlan(String p, String day) { return p + "timer_plan_" + day; }
    /** 账号是否参与自动签到。 */
    public static String enabled(int acc)       { return "acc" + acc + "_enabled"; }
    /** 该账号的某项配置（window/timer/gap/missback/missdead）。 */
    public static String cfg(int acc, String name) { return "acc" + acc + "_cfg_" + name; }

    /**
     * 条目相关的键前缀（清空配置 / 孤儿清理用）。
     * 注意：漏一个前缀的后果是**状态复活** —— nextEntryId 复用 id，
     * 清空后重新添加同一个 bot 会继承旧状态。新增 per-target 键必须加进来。
     *
     * 2026-10-01：去掉重复的 "pendcfm_result_"（原本写了两次）。
     * 另注：timer_plan_ 虽由本类的 timerPlan() 生成，但它是**账号级 · 按天**
     * 的键（acc{N}_timer_plan_&lt;日期&gt;），归 TGAutoSignCore 的账号级清理表管，
     * 故意不列在这里 —— 列进来反而会在清空条目时误删当天时刻表。
     */
    public static final String[] ENTRY_HEADS = {
            "learned_", "kind_", "did_", "data_", "hash_", "msg_id_",
            "pre_", "loc_", "last_", "opt_", "retry_", "retry_at_", "retry_day_", "sent_at_", "answered_", "send_n_",
            "signed_at_", "miss_at_",
            "frozen_", "snooze_", "pendcfm_", "pendcfm_day_", "pendcfm_note_", "pendcfm_result_", "title_",
            "fail_streak_", "fail_laststamp_", "fail_alert_", "fail_date_",
            "fails_today_", "fails_day_", "permfail_",
            "panelstale_", "panelstale_day_", "silent_", "silent_day_", "peerkind_"
    };

    /**
     * 孤儿清理只看"挂在条目上才有意义"的键；cfg_/enabled 这类账号级配置不能碰。
     */
    public static final String[] ORPHAN_HEADS = {
            "frozen_", "snooze_", "pendcfm_", "pendcfm_day_", "pendcfm_note_", "pendcfm_result_",
            "title_", "fail_streak_", "fail_laststamp_", "fail_alert_", "fail_date_",
            "fails_today_", "fails_day_", "permfail_",
            "sent_at_", "signed_at_", "miss_at_", "opt_", "answered_", "send_n_",
            "panelstale_", "panelstale_day_", "silent_", "silent_day_"
    };
}
