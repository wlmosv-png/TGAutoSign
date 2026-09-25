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
    public static String autoLearnFilter()   { return "jmb_alfilter"; }
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
    public static String pendingNote(String p, String id){ return p + "pendcfm_note_" + id; }
    public static String sentAt(String p, String id)    { return p + "sent_at_" + id; }
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
     */
    public static final String[] ENTRY_HEADS = {
            "learned_", "kind_", "did_", "data_", "hash_", "msg_id_",
            "pre_", "loc_", "last_", "opt_", "retry_", "retry_at_", "retry_day_", "sent_at_",
            "frozen_", "snooze_", "pendcfm_", "pendcfm_note_", "title_",
            "fail_streak_", "fail_laststamp_", "fail_alert_", "fail_date_",
            "panelstale_", "panelstale_day_", "silent_", "silent_day_", "peerkind_"
    };

    /**
     * 孤儿清理只看"挂在条目上才有意义"的键；cfg_/enabled 这类账号级配置不能碰。
     */
    public static final String[] ORPHAN_HEADS = {
            "frozen_", "snooze_", "pendcfm_", "pendcfm_note_",
            "title_", "fail_streak_", "fail_laststamp_", "fail_alert_", "fail_date_",
            "sent_at_", "opt_", "panelstale_", "panelstale_day_", "silent_", "silent_day_"
    };
}
