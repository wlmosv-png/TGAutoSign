package io.github.wlmosv_png.tgautosign;

/**
 * 签到状态的读写中心（Refactor 1.6.1 · 步骤 4）。
 *
 * 为什么独立：
 *   重构前这些方法散在 TGAutoSignCore 的 1201~6720（跨度 5500 行），
 *   而它们正是**今天所有 bug 的震中**：
 *     - markSigned 早退吞清理        → 「确认已签」按钮赖着不走
 *     - 请求发出即写 last_           → 界面来回翻
 *     - 6 个 markSigned 调用点无一清 fail_streak → Toast 与列表两套账
 *     - 乐观标记与最终已签共用字段    → 无法区分"发了"和"签上了"
 *   集中一处后，这些不变式可以**一次写清、一处维护**。
 *
 * 不变式（必须保持）：
 *   I1. last_ 只表示"今天确实签上了"（回复判定 / 就地判定 / 用户确认）。
 *       绝不因"请求发出成功"而写。
 *   I2. opt_ 表示"今天已发出、尚无结论"。与 last_ 分离；有最终结论时清掉。
 *   I3. 写 last_ 必须同时清 opt_ / pendcfm_ / panelstale_ / silent_ / retry_，
 *       并清 fail_streak_（否则"连续失败天数"会跨过成功的日子继续累加）。
 *   I4. 所有状态键用 commit()：闸门依赖紧随其后的读。
 *   I5. pendcfm_ = "需要人看一眼"，pendcfm_note_ = 为什么（resultCode）。
 *       一次执行必须落到恰好一个归类；有结论时两者都清（见 markResult）。
 */
public final class SignStateStore {

    private final PrefsStore store;

    /** 回调：主类的"额外动作"（连签天数、日历、日志）。避免把 UI/统计拖进来。 */
    public interface Hooks {
        void onSigned(String prefix, String id);          // 计入连签/日历
        void logWarn(String msg);
        void swallow(String where, Throwable t);
    }

    private Hooks hooks;
    public void setHooks(Hooks h) { this.hooks = h; }

    public SignStateStore(PrefsStore store) { this.store = store; }

    // 2026-10-06 P3：改走 DateUtils（java.time，线程安全，与存量格式一致）。
    private String today() {
        return DateUtils.today();
    }

    private void warn(String m) { if (hooks != null) { try { hooks.logWarn(m); } catch (Throwable ignored) {} } }
    private void swallow(String w, Throwable t) { if (hooks != null) { try { hooks.swallow(w, t); } catch (Throwable ignored) {} } }

    // ───────────── 读 ─────────────

    /** 今天是否已签（最终）。 */
    public boolean isSignedToday(String prefix, String id) {
        return today().equals(store.s(Keys.last(prefix, id), ""));
    }

    /** 今天是否"已发出、尚无结论"。 */
    public boolean isOptimisticToday(String prefix, String id) {
        return today().equals(store.s(Keys.opt(prefix, id), ""));
    }

    /**
     * 今天是否"已发出过"（含已有结论的情况）——闸门用。
     * 语义：只要今天发过（无论最终成功与否的中间态），就算。
     */
    public boolean sentToday(String prefix, String id) {
        return isSignedToday(prefix, id) || isOptimisticToday(prefix, id);
    }

    /**
     * 实际签到成功时刻（毫秒）；今天没签或老数据无该字段时返回 0。
     *
     * 为什么需要回退：signed_at_ 是 1.6.1 新增键，此前签过的记录只有 last_（日期）。
     * 界面读不到时刻时应回退到 sent_at_（发起时刻），再没有就显示 ✔ —— 不能因为
     * 新增字段就让老记录看起来"没时间"。
     */
    public long signedAtMs(String prefix, String id) {
        try {
            long t = store.l(Keys.signedAt(prefix, id), 0L);
            if (t > 0L && isTodayMs(t)) return t;
            long sent = store.l(Keys.sentAt(prefix, id), 0L);
            if (sent > 0L && isTodayMs(sent)) return sent;
            return 0L;
        } catch (Throwable t) { return 0L; }
    }

    /**
     * 补签触发时刻（毫秒）；今天没走过补签路径时返回 0。
     * 必须判"是否今天"：miss_at_ 不按天清理（签到成功后仍要显示），
     * 昨天的值不能污染今天的展示。
     */
    public long missAtMsToday(String prefix, String id) {
        try {
            long t = store.l(Keys.missAt(prefix, id), 0L);
            return (t > 0L && isTodayMs(t)) ? t : 0L;
        } catch (Throwable t) { return 0L; }
    }

    /** 时间戳是否落在今天（本地时区）。 */
    private boolean isTodayMs(long ms) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            return f.format(new java.util.Date(ms)).equals(today());
        } catch (Throwable t) { return false; }
    }

    /** 标记"本次是补签触发"。只有 sweepDue 的错过补签路径会调。 */
    public void markMissTriggered(String prefix, String id, long atMs) {
        try { store.set(Keys.missAt(prefix, id), atMs); } catch (Throwable t) { swallow("markMissTriggered", t); }
    }

    /** 今天是否"已发出、且还在等结论的时效内"。 */
    public boolean isSentPendingFresh(String prefix, String id, long ttlMs) {
        if (!isOptimisticToday(prefix, id)) return false;
        long sent = store.l(Keys.sentAt(prefix, id), 0L);
        if (sent <= 0L) return true;   // 有 opt_ 无时间戳：保守视为在飞
        return System.currentTimeMillis() - sent < ttlMs;
    }

    /** 连续失败天数（跨天自动视为 0）。 */
    public int failStreak(String prefix, String id) {
        // 2026-10-06 P3 修正：原判据是 `!isYesterday(stamp) → 0`，
        // 但 noteFailStreak 写入的 stamp 是**今天** —— 于是今天刚失败反而返回 0，
        // 完全读不到连续失败天数（且该方法此前无人调用，所以一直没暴露）。
        // 现语义：stamp 为**今天或昨天** → 视为仍在延续，返回存储天数；更早 → 0。
        String y = store.s(Keys.failLastStamp(prefix, id), "");
        if (!DateUtils.isTodayOrYesterday(y)) return 0;
        return store.i(Keys.failStreak(prefix, id), 0);
    }

    /**
     * 连续失败天数是否应「继续计数」（纯函数，便于单测）。
     *
     * 2026-10-06 P3 抽出。判据：stamp 为**今天或昨天** → 延续；更早 / 空 / 非法 → 中断。
     * 为什么是「今天或昨天」而不是「昨天」：
     *   noteFailStreak 写入的 stamp 就是**今天**（当天失败当天记），
     *   若只认昨天，今天刚失败反而返回 0 —— 这正是修复前的 bug。
     */
    static boolean failStreakContinues(String stampYmd) {
        return DateUtils.isTodayOrYesterday(stampYmd);
    }

    private boolean isYesterday(String ymd) {
        return DateUtils.isYesterday(ymd);
    }

    // ───────────── 执行结果归类（2026-09-28）─────────────

    /**
     * 记录本次执行的归类。
     *
     * 与 pendcfm_ 的分工：
     *   · pendcfm_        = "这个目标现在需要人看一眼"（布尔闸门，界面据此显示处置入口）
     *   · pendcfm_note_   = **为什么**（resultCode 串，界面据此选文案与动作）
     *
     * 只对需要处置的归类写 pendcfm_；成功/失败这类自解释的不写
     * （失败有 retry_ 与 fail_streak_ 表达，成功有 last_）。
     */
    public void markResult(String prefix, String id, int result) {
        try {
            // 2026-10-28 治本：**今天已经签上了**就不再接受异常归类。
            // 否则 sweep / 超时兜底等路径会在成功之后又把「判不出」写回去，
            // 界面与真实状态打架（真机实测 6379863842 就是这个形态）。
            // ═══ 2026-11-08 P0 · 状态转换保护（写入前先过三关）═══
            final int _curRound = roundOf(prefix, id);
            int _cur = resultOf(prefix, id);
            int _curResRound = store.i(Keys.resultRound(prefix, id), 0);
            if (_curResRound != 0 && _curResRound != _curRound) _cur = -1;   // 旧轮结论不作用于新轮（数据保留）
            // ① 已有"有证据"的归类，不得被"无证据"的超时结论覆盖
            if (hasEvidenceResult(_cur) && !hasEvidenceResult(result)) {
                warn("拒绝覆盖：已有证据归类(" + SignLogic.resultCode(_cur) + ")，本次为无证据归类("
                     + SignLogic.resultCode(result) + ") —— " + id);
                return;
            }
            // ② "未回复"必须有资格：没有任何回复证据 + 观察链路健康
            if (result == SignLogic.R_NO_REPLY
                    && !SignLogic.mayMarkNoReply(isAnsweredToday(prefix, id), evidenceGrade(prefix, id),
                                                 chainHealthyToday())) {
                warn("拒绝判「未回复」：证据不足或观察链路不健康（answered=" + isAnsweredToday(prefix, id)
                     + " grade=" + SignLogic.gradeLabel(evidenceGrade(prefix, id))
                     + " chain=" + chainHealthyToday() + "）—— " + id);
                return;      // 不写归类 → 状态层落「未确定」，而不是"未回复"
            }
            if (isSignedToday(prefix, id)) {
                store.tx(ed -> ed.remove(Keys.opt(prefix, id))
                                 .remove(Keys.sentAt(prefix, id))
                                 .remove("result_" + id)
                                 .remove("result_day_" + id));
                return;
            }
            /* 无论什么归类 —— bot 已经结束了这一轮，opt_ 和 sent_at_ 必须清掉。
               2026-09-29 线上事故：markResult 只写 pendcfm_ 不清 opt_ →
               模块重启后 promoteSilentToPending 读到残留 opt_，
               把「判定失败」的目标又标成「bot 未回复」（日志实锤）。 */
            store.tx(ed -> {
                ed.remove(Keys.opt(prefix, id));
                ed.remove(Keys.sentAt(prefix, id));
                // ── 2026-10-14：归类码无条件落一份（独立键）──
                // 以前只有 needsAttention(result) 为真才写 pendcfm_result_，
                // 而 R_FAILED 不在其中 → 「签到失败」这个归类**根本没地方存** →
                // 界面 statusOf() 读不到 → 落回「待签」（用户实测：
                // 日志明明写着「命中失败词 → 判定未成功」，列表却显示待签）。
                // 现在 result_ 独立存，任何归类都能如实显示。
                ed.putString("result_" + id, SignLogic.resultCode(result));
                ed.putString("result_day_" + id, today());
                ed.putInt(Keys.resultRound(prefix, id), _curRound);   // 本轮专属，供跨轮隔离
                if (result == SignLogic.R_NO_REPLY) {
                    // 只有过了证据门才走到这里 → 打上资格标记；读取方认标记才敢说"未回复"
                    ed.putString(Keys.noReplyOk(prefix, id), today());
                } else {
                    ed.remove(Keys.noReplyOk(prefix, id));
                }
                if (SignLogic.needsAttention(result)) {
                    ed.putBoolean(Keys.pendingCfm(prefix, id), true);
                    // 同时记"哪天标的"：sweepStalePendingConfirm 靠它区分
                    // 「今天刚标」与「隔天残留」。没有它就只能靠 sent_at_，
                    // 而 sent_at_ 恰恰被这一步删掉了（13 次重复发送的根因）。
                    ed.putString(Keys.pendingDay(prefix, id), today());
                    ed.putString(Keys.pendingResult(prefix, id), SignLogic.resultCode(result));
                } else {
                    ed.remove(Keys.pendingCfm(prefix, id));
                    ed.remove(Keys.pendingDay(prefix, id));
                    ed.remove(Keys.pendingResult(prefix, id));
                }
            });
        } catch (Throwable t) { swallow("markResult", t); }
    }

    /**
     * 只写归类码，**不置「待确认」**（2026-10-30）。
     *
     * 用途：还没发出任何请求就失败的场景（典型：回调目标等不到带按钮的新消息）。
     * 这类失败必须让界面如实显示原因（列表状态、「卡住的目标」、归类统计都读 result_），
     * 但**绝不能**走 markResult —— 那里对 needsAttention 的归类会写 pendcfm_，
     * 而 SignGate.pendingUnconfirmed 会据此拦住当天剩下的自动重试；
     * 面板随时可能被 bot 推过来，「继续等下一次重试」才是对的策略。
     *
     * 与 markResult 一样按天有效（result_day_），跨天自动失效。
     */
    public void markReason(String prefix, String id, int result) {
        try {
            if (isSignedToday(prefix, id)) return;
            store.tx(ed -> ed.putString("result_" + id, SignLogic.resultCode(result))
                             .putString("result_day_" + id, today()));
        } catch (Throwable t) { swallow("markReason", t); }
    }

    /**
     * 「待确认」是否是**今天**标的。
     *
     * 老数据（pendcfm_ 有、pendcfm_day_ 无）返回 true —— 宁可留着，
     * 也不能把用户真在等的处置吞掉；陈旧的那些由用户处置或跨天自然收敛。
     */
    public boolean pendingIsToday(String prefix, String id) {
        try {
            String d = store.s(Keys.pendingDay(prefix, id), "");
            if (d.length() == 0) return true;      // 老数据：保守保留
            return today().equals(d);
        } catch (Throwable t) { return true; }
    }

    /** 读取归类码；无归类返回 -1。 */
    public int resultOf(String prefix, String id) {
        try {
            // 2026-10-28：已签 → 没有异常归类可谈（历史残留也不显示）
            if (isSignedToday(prefix, id)) return -1;
            // 独立键优先（按天有效；跨天视为无归类）
            String d = store.s("result_day_" + id, "");
            if (d != null && d.equals(today())) {
                int r = SignLogic.resultOfCode(store.s("result_" + id, ""));
                if (r >= 0) {
                    // 2026-11-08 P0：**轮次隔离** —— 旧轮的结论不得作用于新轮。
                    // 重试不删除 result_（证据不破坏性删除），但用 result_round_ 标记它属于哪一轮；
                    // 轮次对不上就当作"本轮还没有结论"，新轮从零开始。
                    int rr = store.i(Keys.resultRound(prefix, id), 0);
                    int cur = roundOf(prefix, id);
                    if (rr != 0 && rr != cur) return -1;
                    // 2026-11-08 P0：**"未回复"必须带资格标记**。
                    // 脏数据（旧版本/链路不健康时期写下的 no_reply）不得冒充结论，
                    // 一律降级为"本轮还没有结论"（界面落「未确定」）。
                    if (r == SignLogic.R_NO_REPLY
                            && !today().equals(store.s(Keys.noReplyOk(prefix, id), ""))) return -1;
                    return r;
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (!store.b(Keys.pendingCfm(prefix, id), false)) return -1;
            return SignLogic.resultOfCode(store.s(Keys.pendingResult(prefix, id), ""));
        } catch (Throwable t) { return -1; }
    }

    /** 清除归类（用户处置完 / 签成功后）。 */

    /**
     * 只清 opt_ + sent_at_（手动签到前用，不碰 pendcfm_ 等归类状态）。
     *
     * 场景：用户点了「立即签到」，之前 opt_ 残留会拦住发送——
     * 先清掉乐观标记，让本次发送能正常走。
     */
    public void clearOptimistic(String prefix, String id) {
        try {
            store.tx(ed -> ed.remove(Keys.opt(prefix, id))
                             .remove(Keys.sentAt(prefix, id)));
        } catch (Throwable t) { swallow("clearOptimistic", t); }
    }

    public void clearResult(String prefix, String id) {
        try {
            store.tx(ed -> ed.remove(Keys.pendingCfm(prefix, id))
                             .remove(Keys.pendingDay(prefix, id))
                             .remove(Keys.pendingResult(prefix, id)));
        } catch (Throwable t) { swallow("clearResult", t); }
    }

    // ───────────── 写 ─────────────

    /**
     * 标记"今日已签"。【唯一合法入口】
     * 幂等：今天已标过则只做清理、不重复记账（历史 bug：早退把清理也跳过了）。
     * 返回 true 表示本次真正写入（首次）。
     */
    public boolean markSigned(String prefix, String id) { return markSigned(prefix, id, null); }

    /**
     * 带「原因」的重载（2026-10-24）。
     *
     * 为什么需要：日志里「标记今日已签 X」以前是孤零零一行 ——
     * 判定命中那行属于 INFO/DEBUG，会被落盘采样丢掉（lv>=LV_WARN 才必写），
     * 于是用户排障时完全看不到「凭什么判成功」。现在把命中词写进同一条 WARN。
     */
    public boolean markSigned(String prefix, String id, String reason) {
        try {
            boolean first = !isSignedToday(prefix, id);
            /* 2026-11-08 P0：**原子状态更新**。
               原先「写 last_/signed_at_（tx1）」与「clearPostSignState（tx2）」是两次提交，
               中间被打断就留下「已签 + 仍有 pendcfm_」的撕裂态（用户看到"明明签了还要确认"）。
               现在两者合并进**同一个 commit**：要么全成、要么全不成。
               signed_at_ 仍与 last_ 同一次写（分开会出现「有日期没时刻」的中间态）。 */
            final long nowMs = System.currentTimeMillis();
            final boolean _first = first;
            store.tx(ed -> {
                if (_first) {
                    ed.putString(Keys.last(prefix, id), today())
                      .putLong(Keys.signedAt(prefix, id), nowMs);
                }
                clearPostSignKeys(ed, prefix, id);
            });
            try { clearFailStreak(prefix, id); } catch (Throwable ignored) {}
            if (first) {
                if (hooks != null) { try { hooks.onSigned(prefix, id); } catch (Throwable t) { swallow("markSigned.onSigned", t); } }
                warn("标记今日已签 " + id
                     + (reason == null || reason.length() == 0 ? "" : "（" + reason + "）"));
            }
            return first;
        } catch (Throwable t) { swallow("markSigned", t); return false; }
    }

    /**
     * 撤销"今日已签"并计入一次重试（失败路径）。
     * @param retryLimit 重试上限
     * @param backoffMs  退避时长
     */
    public void markFailed(String prefix, String id, int retryLimit, long backoffMs) {
        try {
            int cur = store.i(Keys.retry(prefix, id), 0);
            store.tx(ed -> {
                ed.remove(Keys.last(prefix, id))
                  .remove(Keys.opt(prefix, id))
                  .remove(Keys.sentAt(prefix, id))  /* 2026-09-30: 失败也清 sent_at_，防 sweep 误判 */
                  .putInt(Keys.retry(prefix, id), Math.min(cur + 1, retryLimit))
                  .putLong(Keys.retryAt(prefix, id), System.currentTimeMillis() + backoffMs)
                  .putString(Keys.retryDay(prefix, id), today());
            });
        } catch (Throwable t) { swallow("markFailed", t); }
    }

    /** 标记"已发出、待结论"（乐观）。不写 last_。 */
    public void markOptimistic(String prefix, String id, long sentAtMs) {
        try {
            store.tx(ed -> ed.putString(Keys.opt(prefix, id), today())
                              .putLong(Keys.sentAt(prefix, id), sentAtMs));
        } catch (Throwable t) { swallow("markOptimistic", t); }
    }

    /**
     * 清键集合（Editor 级）。与 clearPostSignState **同源** ——
     * 抽出来是为了让 markSigned 能把「写 last_」与「清在途/归类/待确认」放进同一个 commit；
     * 两处各抄一份键列表迟早漂移（历史上就漂过）。
     */
    private void clearPostSignKeys(android.content.SharedPreferences.Editor ed, String prefix, String id) {
        ed.remove(Keys.opt(prefix, id))
          .remove(Keys.sentAt(prefix, id))
          .remove(Keys.pendingCfm(prefix, id))
          .remove(Keys.pendingDay(prefix, id))
          .remove(Keys.pendingNote(prefix, id))
          .remove(Keys.pendingResult(prefix, id))
          .remove(Keys.panelStale(prefix, id))
          .remove(Keys.panelStaleDay(prefix, id))
          .remove(Keys.silent(prefix, id))
          .remove(Keys.silentDay(prefix, id))
          .remove(Keys.retry(prefix, id))
          .remove(Keys.retryAt(prefix, id))
          .remove(Keys.retryDay(prefix, id))
          .remove(Keys.answered(prefix, id))
          .remove(Keys.resultRound(prefix, id))
          .remove(Keys.noReplyOk(prefix, id))
          .remove(prefix + "retry_click_" + id)
          .remove("nr_" + prefix + id)
          .remove("nrday_" + prefix + id)
          .remove("result_" + id)
          .remove("result_day_" + id);
    }

    /** 清掉与"今日已签"互斥的全部状态键。【唯一入口】 */
    public void clearPostSignState(String prefix, String id) {
        try {
            store.tx(ed -> ed
                    .remove(Keys.opt(prefix, id))
                    // sent_at_ 必须一起清：isSentPendingFresh 先看 opt_ 再看 sent_at_，
                    // 只清 opt_ 会让「有结论了却还被当成在飞」的窗口残留在别处读到
                    //（实测：已签目标 30 分钟内仍被判成「已发出」）。
                    .remove(Keys.sentAt(prefix, id))
                    .remove(Keys.pendingCfm(prefix, id))
                    .remove(Keys.pendingDay(prefix, id))
                    .remove(Keys.pendingNote(prefix, id))
                    .remove(Keys.pendingResult(prefix, id))
                    .remove(Keys.panelStale(prefix, id))
                    .remove(Keys.panelStaleDay(prefix, id))
                    .remove(Keys.silent(prefix, id))
                    .remove(Keys.silentDay(prefix, id))
                    .remove(Keys.retry(prefix, id))
                    .remove(Keys.retryAt(prefix, id))
                    .remove(Keys.retryDay(prefix, id))
                    .remove(Keys.answered(prefix, id))
                    // 2026-10-28 修：独立归类键也必须一起清 ——
                    // resultOf() **优先**读它，残留会让"已签"的目标仍显示「判不出」
                    // （真机实测：last_=今天 而 result_=replied_unknown）。
                    .remove("result_" + id)
                    .remove("result_day_" + id));
            clearFailStreak(prefix, id);
        } catch (Throwable t) { swallow("clearPostSignState", t); }
    }

    /** 只清退避、保留已签（"第二步失败但第一步已成功"用）。 */
    public void keepSignedClearBackoff(String prefix, String id) {
        try {
            store.tx(ed -> ed.putInt(Keys.retry(prefix, id), 0)
                             .remove(Keys.retryAt(prefix, id))
                             .remove(Keys.retryDay(prefix, id)));
            clearFailStreak(prefix, id);
        } catch (Throwable t) { swallow("keepSignedClearBackoff", t); }
    }

    /**
     * 手动撤销「今日已签」（2026-11-01，用户诉求）。
     *
     * 背景（用户 Gheoix 原话）：
     *   「S5 命中回复重复签到，直接就算签到了，我还没法给他标没签到成功，
     *     只能把这个目标删了再添加；添加的时候又顺带帮你签到，又算成功。」
     *   —— 误判成已签之后没有任何撤销手段，只能删目标，删了再加又被自动签一次。
     *
     * 与 markFailed 的区别：
     *   · markFailed 是**判定链**的失败路径 —— 会涨重试计数、排退避；
     *   · 本方法是**用户主动纠正** —— 不涨计数、不排重试，纯粹回到"未签"。
     *
     * 与 clearSigned 的区别：clearSigned 只删 last_，会留下 signed_at_ / opt_ /
     *   sent_at_ 等残留（界面仍可能显示"已发出"或日历读到脏时刻）。本方法
     *   一次清干净，复用 clearPostSignState 这个唯一入口。
     *
     * @return true = 之前确实处于"今日已签"状态（调用方据此给不同提示）
     */
    public boolean markUnsigned(String prefix, String id) {
        try {
            boolean was = isSignedToday(prefix, id);
            store.tx(ed -> ed.remove(Keys.last(prefix, id))
                             .remove(Keys.signedAt(prefix, id)));
            clearPostSignState(prefix, id);
            if (was) warn("手动撤销今日已签 " + id);
            return was;
        } catch (Throwable t) { swallow("markUnsigned", t); return false; }
    }

    /** 撤销"今日已签"（不含重试计数）。 */
    public void clearSigned(String prefix, String id) {
        try { store.remove(Keys.last(prefix, id)); } catch (Throwable t) { swallow("clearSigned", t); }
    }

    // ───────────── 连续失败计数 ─────────────

    /** 记录一次失败（同一天同一目标只计一次）。返回累计天数。 */
    public int noteFailStreak(String prefix, String id) {
        try {
            String t = today();
            if (t.equals(store.s(Keys.failDate(prefix, id), ""))) return store.i(Keys.failStreak(prefix, id), 0);
            String y = store.s(Keys.failLastStamp(prefix, id), "");
            int n = store.i(Keys.failStreak(prefix, id), 0);
            int streak = isYesterday(y) ? n + 1 : 1;
            final int fStreak = streak;
            store.tx(ed -> ed.putString(Keys.failDate(prefix, id), t)
                             .putString(Keys.failLastStamp(prefix, id), t)
                             .putInt(Keys.failStreak(prefix, id), fStreak));
            return streak;
        } catch (Throwable t2) { swallow("noteFailStreak", t2); return 0; }
    }

    /** 清零连续失败计数（签成功后）。 */
    public void clearFailStreak(String prefix, String id) {
        try {
            if (store.i(Keys.failStreak(prefix, id), 0) == 0
                    && store.s(Keys.failDate(prefix, id), "").isEmpty()) return;
            store.tx(ed -> ed.remove(Keys.failDate(prefix, id))
                             .remove(Keys.failLastStamp(prefix, id))
                             .remove(Keys.failStreak(prefix, id))
                             .remove(Keys.failAlert(prefix, id)));
        } catch (Throwable t) { swallow("clearFailStreak", t); }
    }

    /** 是否已就"连续失败"告过警（当天）。 */
    public boolean failAlertedToday(String prefix, String id) {
        return today().equals(store.s(Keys.failAlert(prefix, id), ""));
    }

    public void markFailAlerted(String prefix, String id) {
        store.set(Keys.failAlert(prefix, id), today());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  2026-11-08 · 轮次 / 回复证据 / 观察链路 / 重试轮次隔离（P0+P1）
    // ══════════════════════════════════════════════════════════════════════

    /** 该归类是否属于「有证据」类（不得被无证据的超时结论覆盖）。 */
    private static boolean hasEvidenceResult(int r) {
        return r == SignLogic.R_SIGNED || r == SignLogic.R_FAILED
                || r == SignLogic.R_REPLIED_UNK || r == SignLogic.R_BTN_STALE;
    }

    /** 今天 bot 是否回过内容（含就地对答与消息回复两条路径）。 */
    public boolean isAnsweredToday(String prefix, String id) {
        try { return today().equals(store.s(Keys.answered(prefix, id), "")); }
        catch (Throwable t) { return false; }
    }

    /** 今天观察链路是否健康（锚点 A/B 至少收到过一批事件）。 */
    public boolean chainHealthyToday() {
        try { return today().equals(store.s(Keys.chainOk(), "")); }
        catch (Throwable t) { return false; }
    }

    /** 标记今天的观察链路健康（由模块入口在任一锚点收到事件时调用）。 */
    public void markChainHealthy() {
        try {
            if (!chainHealthyToday()) store.set(Keys.chainOk(), today());
        } catch (Throwable t) { swallow("markChainHealthy", t); }
    }

    /** 当前轮次（跨天/无记录 → 1）。 */
    public int roundOf(String prefix, String id) {
        try {
            if (!today().equals(store.s(Keys.roundDay(prefix, id), ""))) return 1;
            int r = store.i(Keys.round(prefix, id), 0);
            return r <= 0 ? 1 : r;
        } catch (Throwable t) { return 1; }
    }

    /** 发送前确保轮次存在（同一天同一目标保持同一轮次）。 */
    public int ensureRound(String prefix, String id) {
        try {
            if (!today().equals(store.s(Keys.roundDay(prefix, id), ""))) {
                store.tx(ed -> ed.putInt(Keys.round(prefix, id), 1).putString(Keys.roundDay(prefix, id), today()));
                return 1;
            }
            return roundOf(prefix, id);
        } catch (Throwable t) { return 1; }
    }

    /** 本轮证据等级（只认当前轮次；旧轮证据仍归档在键里，不删除）。 */
    public int evidenceGrade(String prefix, String id) {
        try {
            String v = store.s(Keys.evGrade(prefix, id), "");
            if (v == null || v.length() == 0) return SignLogic.GRADE_NONE;
            int bar = v.indexOf('|');
            if (bar <= 0) return SignLogic.GRADE_NONE;
            int round = Integer.parseInt(v.substring(0, bar));
            int grade = Integer.parseInt(v.substring(bar + 1));
            return round == roundOf(prefix, id) ? grade : SignLogic.GRADE_NONE;
        } catch (Throwable t) { return SignLogic.GRADE_NONE; }
    }

    /**
     * 记录回复证据（取最高级，不降级）。
     * 语义分级见 Keys.evGrade 的注释 —— 这是把"观察到 / 来自目标 bot / 已关联 / 已判定"
     * 拆开的关键：判定失败也**不得丢弃已观察到的真实回复**。
     */
    public void noteEvidence(String prefix, String id, int grade) {
        try {
            if (grade <= SignLogic.GRADE_NONE) return;
            final int r = roundOf(prefix, id);
            if (grade > evidenceGrade(prefix, id)) {
                store.tx(ed -> {
                    ed.putString(Keys.evGrade(prefix, id), r + "|" + grade);
                    // 归档"最后一个有证据的轮次"——重试时**不清**，供回溯
                    if (grade >= SignLogic.GRADE_SEEN) ed.putInt(Keys.evRoundArchived(prefix, id), r);
                });
            }
        } catch (Throwable t) { swallow("noteEvidence", t); }
    }

    /**
     * 重试前的**轮次隔离**（P0-3）。
     *
     * 关键取舍（任务书明令）：**不做破坏性删除**。
     *   · 轮次 +1 → 新轮只认新轮的证据与结论；旧轮的 result_ 由 result_round_ 隔离，
     *     旧轮的证据归档在 ev_grade_/ev_round_ 里**原样保留**，可回溯、不丢失。
     *   · 只清"在途与调度"这类**属于上一轮**的运行态（opt_/sent_at_/退避/发送计数/待确认），
     *     它们若留着，就会让新轮一开跑就被旧轮的超时逻辑命中。
     *   · last_（已签事实）与 ev_*（证据归档）**一律不动**。
     */
    public void resetRoundStateForRetry(String prefix, String id) {
        try {
            final int nr = roundOf(prefix, id) + 1;
            store.tx(ed -> {
                ed.putInt(Keys.round(prefix, id), nr).putString(Keys.roundDay(prefix, id), today());
                ed.remove(Keys.opt(prefix, id))
                  .remove(Keys.sentAt(prefix, id))
                  .remove(Keys.pendingCfm(prefix, id))
                  .remove(Keys.pendingDay(prefix, id))
                  .remove(Keys.pendingNote(prefix, id))
                  .remove(Keys.pendingResult(prefix, id))
                  .remove(Keys.panelStale(prefix, id))
                  .remove(Keys.panelStaleDay(prefix, id))
                  .remove(Keys.silent(prefix, id))
                  .remove(Keys.silentDay(prefix, id))
                  .remove(Keys.retry(prefix, id))
                  .remove(Keys.retryAt(prefix, id))
                  .remove(Keys.retryDay(prefix, id))
                  .remove(Keys.sendAttempts(prefix, id))
                  .remove(Keys.noReplyOk(prefix, id))
                  .remove("nr_" + prefix + id)
                  .remove("nrday_" + prefix + id);
                // 注意：**不删** last_ / result_ / ev_grade_ / ev_round_ / answered_
                //   —— 旧轮证据与旧轮结论都保留，靠轮次号隔离，而不是靠删除。
            });
            // 2026-11-08 F7：轮次时间线（reset 开始/结束、在途已清、新 roundId）
            try {
                Diag.event("round", "CORE", -1, 0L, 0L, 0, nr - 1,
                        "in_flight", "resetting", "-", Diag.hookStatus("A"));
                Diag.event("round", "CORE", -1, 0L, 0L, 0, nr,
                        "resetting", "cleared(in_flight/opt_/sent_at_/retry/send_n_)", "-", Diag.hookStatus("A"));
            } catch (Throwable ignored) {}
            warn("轮次切换 → 第 " + nr + " 轮（旧轮证据归档保留，不清除）：" + id);
        } catch (Throwable t) { swallow("resetRoundStateForRetry", t); }
    }
}
