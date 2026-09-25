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

    private String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
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

    /** 今天是否"已发出、且还在等结论的时效内"。 */
    public boolean isSentPendingFresh(String prefix, String id, long ttlMs) {
        if (!isOptimisticToday(prefix, id)) return false;
        long sent = store.l(Keys.sentAt(prefix, id), 0L);
        if (sent <= 0L) return true;   // 有 opt_ 无时间戳：保守视为在飞
        return System.currentTimeMillis() - sent < ttlMs;
    }

    /** 连续失败天数（跨天自动视为 0）。 */
    public int failStreak(String prefix, String id) {
        String y = store.s(Keys.failLastStamp(prefix, id), "");
        if (!isYesterday(y)) return 0;
        return store.i(Keys.failStreak(prefix, id), 0);
    }

    private boolean isYesterday(String ymd) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.add(java.util.Calendar.DAY_OF_YEAR, -1);
            return f.format(c.getTime()).equals(ymd);
        } catch (Throwable t) { return false; }
    }

    // ───────────── 写 ─────────────

    /**
     * 标记"今日已签"。【唯一合法入口】
     * 幂等：今天已标过则只做清理、不重复记账（历史 bug：早退把清理也跳过了）。
     * 返回 true 表示本次真正写入（首次）。
     */
    public boolean markSigned(String prefix, String id) {
        try {
            boolean first = !isSignedToday(prefix, id);
            if (first) {
                store.set(Keys.last(prefix, id), today());
            }
            // 无论首次还是重复，清理都要做（清 opt_/pendcfm_/退避/熔断/失败计数）
            clearPostSignState(prefix, id);
            if (first) {
                if (hooks != null) { try { hooks.onSigned(prefix, id); } catch (Throwable t) { swallow("markSigned.onSigned", t); } }
                warn("标记今日已签 " + id);
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

    /** 清掉与"今日已签"互斥的全部状态键。【唯一入口】 */
    public void clearPostSignState(String prefix, String id) {
        try {
            store.tx(ed -> ed
                    .remove(Keys.opt(prefix, id))
                    .remove(Keys.pendingCfm(prefix, id))
                    .remove(Keys.panelStale(prefix, id))
                    .remove(Keys.panelStaleDay(prefix, id))
                    .remove(Keys.silent(prefix, id))
                    .remove(Keys.silentDay(prefix, id))
                    .remove(Keys.retry(prefix, id))
                    .remove(Keys.retryAt(prefix, id))
                    .remove(Keys.retryDay(prefix, id)));
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
}
