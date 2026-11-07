package io.github.wlmosv_png.tgautosign;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 待处理状态机 + 回复证据 + 轮次隔离 单测（2026-11-08 P0/P1 修复配套）。
 *
 * 覆盖任务书列出的 14 条验收场景（纯函数层 + 状态存储层）。
 * 零依赖：javac + java 直接跑，不需要 Android / JUnit。
 *
 * 退出码 0 = 全过。
 */
public final class PendingStateTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else { failed.add(what); System.out.println("  FAIL: " + what); }
    }

    private static void eq(String what, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (!ok) System.out.println("     expect=" + expect + " actual=" + actual);
        check(what, ok);
    }

    private static final String P = "acc0_";
    private static final String ID = "1234567890_1";

    /** 造一个"今天已发出、链健康"的场景。 */
    private static int ps(boolean signed, boolean pend, boolean sentToday, long ageMs,
                          int grade, int rc, boolean chain) {
        return SignLogic.resolvePendingState(signed, pend, sentToday, ageMs, grade, rc, chain);
    }

    public static void main(String[] args) {
        // ══════════ A. 纯函数归因（场景 1/2/3/4/5/14） ══════════
        final long T = 91L * 1000L;   // 91 秒：正好落在旧的 90 秒误判区间

        // 1) 91 秒、无回复证据 → 不得判「未回复」
        eq("1. 91s 无证据 → 未确定（不得未回复）",
                SignLogic.PS_UNCONFIRMED, ps(false, false, true, T, SignLogic.GRADE_NONE, -1, true));

        // 2) 91 秒、已观察到回复 → 不得判「未回复」
        eq("2. 91s 已观察 → 判不出（不得未回复）",
                SignLogic.PS_UNKNOWN, ps(false, false, true, T, SignLogic.GRADE_SEEN, -1, true));
        eq("2b. 91s answered 留痕（grade 提升）→ 判不出",
                SignLogic.PS_UNKNOWN, ps(false, false, true, T, SignLogic.GRADE_CORR, -1, true));

        // 3) 已观察到回复但无法判定 → 保留未确定/判不出态（不是成功、不是未回复）
        eq("3. 有证据 + result=replied_unknown → 判不出",
                SignLogic.PS_UNKNOWN, ps(false, false, true, T, SignLogic.GRADE_CORR, SignLogic.R_REPLIED_UNK, true));
        check("3b. 判不出 ≠ 已签", ps(false, false, true, T, SignLogic.GRADE_CORR,
                SignLogic.R_REPLIED_UNK, true) != SignLogic.PS_SIGNED);

        // 4) 明确超时 + 无任何证据 + 链路健康 → 允许「未回复」
        eq("4. 明确归类 R_NO_REPLY + 链路健康 → 未回复",
                SignLogic.PS_NO_REPLY, ps(false, false, true, T, SignLogic.GRADE_NONE, SignLogic.R_NO_REPLY, true));
        check("4b. mayMarkNoReply 三条件：无证据+无answered+链路健康",
                SignLogic.mayMarkNoReply(false, SignLogic.GRADE_NONE, true));
        check("4c. 有 answered 时不得判未回复",
                !SignLogic.mayMarkNoReply(true, SignLogic.GRADE_NONE, true));
        check("4d. 有证据等级时不得判未回复",
                !SignLogic.mayMarkNoReply(false, SignLogic.GRADE_SEEN, true));

        // 5) 观察链路中断（宿主刚重启/Hook 未就绪）→ 不能假装"没有回复"
        eq("5. 链路不健康 → 未确定（不得未回复）",
                SignLogic.PS_UNCONFIRMED, ps(false, false, true, T, SignLogic.GRADE_NONE, -1, false));
        eq("5b. 链路不健康 + 已有 no_reply 归类 → 降级为未确定",
                SignLogic.PS_UNCONFIRMED, ps(false, false, true, T, SignLogic.GRADE_NONE, SignLogic.R_NO_REPLY, false));
        check("5c. 链路不健康时 mayMarkNoReply=false",
                !SignLogic.mayMarkNoReply(false, SignLogic.GRADE_NONE, false));

        // 14) UI 归因必须来自证据，而不是发送时间
        check("14. 窗口内 = 等回复（不是未回复）",
                ps(false, false, true, 30_000L, SignLogic.GRADE_NONE, -1, true) == SignLogic.PS_SENT);
        check("14b. 已签优先于一切",
                ps(true, true, true, 999_999L, SignLogic.GRADE_NONE, -1, false) == SignLogic.PS_SIGNED);
        check("14c. 待确认优先于时间",
                ps(false, true, true, 999_999L, SignLogic.GRADE_NONE, -1, true) == SignLogic.PS_UNKNOWN);
        check("14d. 未发出 → 不进需处理",
                ps(false, false, false, -1L, SignLogic.GRADE_NONE, -1, true) == SignLogic.PS_NONE);
        check("14e. 失败归类 → 发送失败",
                ps(false, false, true, 5_000L, SignLogic.GRADE_NONE, SignLogic.R_FAILED, true) == SignLogic.PS_SENDFAIL);

        // ══════════ B. 状态存储层（场景 6/7/8/9/10/11/12/13） ══════════
        PrefsStore store = new PrefsStore(new FakePrefs());
        SignStateStore st = new SignStateStore(store);
        final String today = DateUtils.today();

        // 场景 10/11：多目标 / 多账号互不串
        String idA = "111_1", idB = "222_1", pB = "acc3_";
        st.noteEvidence(P, idA, SignLogic.GRADE_CORR);
        eq("10. 目标隔离：A 有证据、B 无", SignLogic.GRADE_CORR, st.evidenceGrade(P, idA));
        eq("10b. 目标隔离：B 仍为无证据", SignLogic.GRADE_NONE, st.evidenceGrade(P, idB));
        eq("11. 账号隔离：同 id 跨账号不共享",
                SignLogic.GRADE_NONE, st.evidenceGrade(pB, idA));

        // 场景 12：重复消息不能重复签到（markSigned 幂等）
        check("12. 首次 markSigned 返回 true", st.markSigned(P, idA, "t1"));
        check("12b. 重复 markSigned 返回 false（不重复记账）", !st.markSigned(P, idA, "t2"));
        eq("12c. last_ 仍是今天", today, store.s(Keys.last(P, idA), ""));

        // 场景 13：已签 + 待确认 不得撕裂（原子更新把 pendcfm_ 一起清掉）
        store.set(Keys.pendingCfm(P, idA), true);
        store.set(Keys.pendingDay(P, idA), today);
        st.markSigned(P, idA, "again");
        check("13. 已签后不存在残留 pendcfm_", !store.b(Keys.pendingCfm(P, idA), false));
        check("13b. 已签后 opt_/sent_at_ 已清",
                !st.isOptimisticToday(P, idA) && store.l(Keys.sentAt(P, idA), 0L) == 0L);

        // 场景 6/9：重试 → 轮次隔离，且**不破坏性删除**
        String id2 = "333_1";
        int r1 = st.ensureRound(P, id2);
        eq("6. 首发轮次 = 1", 1, r1);
        store.set(Keys.opt(P, id2), today);
        store.set(Keys.sentAt(P, id2), System.currentTimeMillis());
        store.set("result_" + id2, SignLogic.resultCode(SignLogic.R_REPLIED_UNK));
        store.set("result_day_" + id2, today);
        store.set(Keys.resultRound(P, id2), 1);
        st.noteEvidence(P, id2, SignLogic.GRADE_CORR);
        eq("6b. 第 1 轮证据 = 已关联", SignLogic.GRADE_CORR, st.evidenceGrade(P, id2));
        st.resetRoundStateForRetry(P, id2);
        eq("6c. 重试后轮次 = 2", 2, st.roundOf(P, id2));
        eq("6d. 新轮不继承旧轮结论（resultOf 按轮隔离）", -1, st.resultOf(P, id2));
        eq("6e. 新轮不继承旧轮证据（证据按轮读取）", SignLogic.GRADE_NONE, st.evidenceGrade(P, id2));
        check("6f. 旧轮证据**未被删除**（归档仍在）", store.i(Keys.evRoundArchived(P, id2), 0) == 1);
        check("6g. 重试清掉了在途状态（opt_/sent_at_ 不再指向旧轮）",
                !store.has(Keys.opt(P, id2)) && store.l(Keys.sentAt(P, id2), 0L) == 0L);

        // 场景 9：已签状态不能被重试/超时打回未回复
        String id3 = "444_1";
        st.markSigned(P, id3, "signed");
        st.resetRoundStateForRetry(P, id3);
        check("9. 重试后仍保留已签事实（last_ 不清）", st.isSignedToday(P, id3));
        eq("9b. 已签 → 归因恒为已签",
                SignLogic.PS_SIGNED, ps(st.isSignedToday(P, id3), false, true, 999_999L,
                        SignLogic.GRADE_NONE, -1, false));

        // 场景 7：新轮回复先到 → 旧轮超时不得覆盖
        String id4 = "555_1";
        st.ensureRound(P, id4);
        store.set(Keys.opt(P, id4), today);
        store.set(Keys.sentAt(P, id4), System.currentTimeMillis());
        st.noteEvidence(P, id4, SignLogic.GRADE_CORR);
        st.markResult(P, id4, SignLogic.R_REPLIED_UNK);              // 新轮：有证据的归类
        st.markResult(P, id4, SignLogic.R_NO_REPLY);                // 旧式超时：无证据归类
        eq("7. 有证据的归类不被无证据的超时覆盖",
                SignLogic.R_REPLIED_UNK, st.resultOf(P, id4));

        // 场景 8：两个重试入口的状态初始化一致（同一 reset API → 结果必然一致）
        String id5a = "666_1", id5b = "777_1";
        for (String x : new String[]{id5a, id5b}) {
            store.set(Keys.opt(P, x), today);
            store.set(Keys.sentAt(P, x), 1L);
            store.set(Keys.pendingCfm(P, x), true);
            store.set(Keys.retry(P, x), 3);
            store.set("result_" + x, SignLogic.resultCode(SignLogic.R_NO_REPLY));
            store.set("result_day_" + x, today);
            st.noteEvidence(P, x, SignLogic.GRADE_CORR);
        }
        st.resetRoundStateForRetry(P, id5a);
        st.resetRoundStateForRetry(P, id5b);
        StringBuilder sigA = new StringBuilder(), sigB = new StringBuilder();
        for (String k : new String[]{"opt_", "sent_at_", "pendcfm_", "retry_", Keys.pendingDay(P, "").replace(P, ""),
                                     "result_", "result_day_", "round_", "round_day_", "ev_grade_", "ev_round_"}) {
            sigA.append(k).append('=').append(store.s(P + k + id5a, store.l(P + k + id5a, -1) + "")).append(';');
            sigB.append(k).append('=').append(store.s(P + k + id5b, store.l(P + k + id5b, -1) + "")).append(';');
        }
        eq("8. 两个重试入口初始化结果一致", sigA.toString(), sigB.toString());
        check("8b. 重试后旧轮证据归档保留（非破坏性）",
                store.i(Keys.evRoundArchived(P, id5a), 0) == 1 && store.i(Keys.evRoundArchived(P, id5b), 0) == 1);

        // 场景：脏 no_reply（无资格标记）不得冒充结论；有资格标记才算数
        String id7 = "999_1";
        store.set("result_" + id7, SignLogic.resultCode(SignLogic.R_NO_REPLY));
        store.set("result_day_" + id7, today);
        eq("Q1. 旧脏 no_reply（无资格标记）→ 视为无结论", -1, st.resultOf(P, id7));
        store.set(Keys.noReplyOk(P, id7), today);
        eq("Q2. 带资格标记的 no_reply 才算结论",
                SignLogic.R_NO_REPLY, st.resultOf(P, id7));
        check("Q3. 资格门要求无证据+链路健康",
                SignLogic.mayMarkNoReply(false, SignLogic.GRADE_NONE, true)
                && !SignLogic.mayMarkNoReply(true, SignLogic.GRADE_NONE, true));

        // 补充：证据只升不降（判定失败也不能把"已关联"降级）
        String id6 = "888_1";
        st.noteEvidence(P, id6, SignLogic.GRADE_CORR);
        st.noteEvidence(P, id6, SignLogic.GRADE_BOT);
        eq("E1. 证据只升不降", SignLogic.GRADE_CORR, st.evidenceGrade(P, id6));

        // 补充：链路健康标记
        check("E2. 初始链路不健康", !st.chainHealthyToday());
        st.markChainHealthy();
        check("E3. 标记后链路健康", st.chainHealthyToday());

        System.out.println();
        System.out.println("PendingStateTest: passed=" + passed + " failed=" + failed.size());
        if (!failed.isEmpty()) {
            for (String f : failed) System.out.println("  - " + f);
            System.exit(1);
        }
        System.exit(0);
    }

    private PendingStateTest() { }

    // ── 内存版假 SharedPreferences（与 JudgeTest 同构，仅测试用） ──
    public static final class FakePrefs implements SharedPreferences {
        public final Map<String, Object> m = new HashMap<String, Object>();
        @Override public Map<String, ?> getAll() { return m; }
        @Override public String getString(String k, String d) { Object v = m.get(k); return v == null ? d : (String) v; }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String k, Set<String> d) { Object v = m.get(k); return v == null ? d : (Set<String>) v; }
        @Override public int getInt(String k, int d) { Object v = m.get(k); return v == null ? d : ((Number) v).intValue(); }
        @Override public long getLong(String k, long d) { Object v = m.get(k); return v == null ? d : ((Number) v).longValue(); }
        @Override public float getFloat(String k, float d) { Object v = m.get(k); return v == null ? d : ((Number) v).floatValue(); }
        @Override public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v == null ? d : (Boolean) v; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public Editor edit() { return new Ed(m); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        static final class Ed implements Editor {
            final Map<String, Object> m;
            Ed(Map<String, Object> m) { this.m = m; }
            @Override public Editor putString(String k, String v) { m.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { m.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { m.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { m.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { m.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { m.put(k, v); return this; }
            @Override public Editor remove(String k) { m.remove(k); return this; }
            @Override public Editor clear() { m.clear(); return this; }
            @Override public boolean commit() { return true; }
            @Override public void apply() { }
        }
    }
}
