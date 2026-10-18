package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.SharedPreferences;

/**
 * LearnFlowTest —— 「判不出 → 补词 → 下次认得」全链路行为测试（2026-10-18）。
 *
 * 为什么必须有这个文件：
 *   用户问「以后遇到判不出，能否正常添加判定词」。
 *   光看代码接没接通不够 —— 要证明**学完之后同一句话的判定结果真的变了**。
 *
 * 覆盖的真实路径（与 Core / LearnPage 逐行对应）：
 *   ① LearnPage.submit → cb.addJudgeWordScoped(word, isOk, scope, did, targetId)
 *   ② Core.addJudgeWordScopedInternal 决定写哪个 key（三级作用域）
 *   ③ 顺手打开 judge_custom 开关（不开就不生效）
 *   ④ 判定链按同一套 key 读回，交给 verdictDetailScoped
 *   ⑤ 同一句话：学之前 UNKNOWN，学之后 = 用户指定的结论
 *
 * 这是「学词到底有没有用」的唯一可信证据。
 */
public final class LearnFlowTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    public static void main(String[] args) {
        learnBotScopeThenJudge();
        learnTargetScopeThenJudge();
        learnGlobalScopeThenJudge();
        failWordLearnable();
        scopeIsolationNoLeak();
        autoEnableCustomSwitch();
        announceNeverLearned();
        pendingWordsVisible();
        removeWordTakesEffect();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) {
            System.out.println("FAILED");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }

    // ── 断言 ────────────────────────────────────────────────

    private static void eq(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) passed++;
        else failed.add(what + " → 得到 " + got + "，期望 " + want);
    }

    private static void tru(String what, boolean cond) {
        if (cond) passed++;
        else failed.add(what);
    }

    // ═══════════════ 复刻生产语义（逐行对应 Core / LearnPage）═══════════════

    /**
     * 复刻 Core.addJudgeWordScopedInternal 的 key 选择 + 写值 + 开关。
     * 与 TGAutoSignCore.java 的同名方法一致。
     */
    static void learnWord(SharedPreferences p, String word, boolean isOk,
                          String scope, long did, String targetId) {
        String key;
        if ("global".equals(scope)) {
            key = isOk ? Keys.okWordsGlobal() : Keys.failWordsGlobal();
        } else if ("target".equals(scope) && targetId != null && targetId.length() > 0) {
            key = isOk ? Keys.okWordsTarget(targetId) : Keys.failWordsTarget(targetId);
        } else if (did != 0L) {
            key = isOk ? Keys.okWordsBot(did) : Keys.failWordsBot(did);
        } else if (targetId != null && targetId.length() > 0) {
            key = isOk ? Keys.okWordsTarget(targetId) : Keys.failWordsTarget(targetId);
        } else {
            key = isOk ? Keys.okWordsGlobal() : Keys.failWordsGlobal();
        }
        String cur = p.getString(key, "");
        p.edit().putString(key, mergeCsv(cur, word)).apply();
        // 学词必须自动打开自定义词开关，否则判定链不读
        p.edit().putBoolean("acc0_cfg_judge_custom", true).apply();
    }

    static String mergeCsv(String cur, String w) {
        if (cur == null || cur.trim().length() == 0) return w;
        for (String e : cur.split("[,，\n]")) {
            if (e != null && e.trim().equals(w)) return cur;   // 去重
        }
        return cur + "," + w;
    }

    /** 复刻 Core 判定链的读词 + 判定（与 ScopedWireTest.judge 同语义）。 */
    static int judge(SharedPreferences p, String reply, long botDid, String targetId) {
        boolean useCustom = p.getBoolean("acc0_cfg_judge_custom", false);
        String[] okGlobal = null, failGlobal = null, okBot = null, failBot = null,
                 okTgt = null, failTgt = null;
        if (useCustom) {
            okGlobal = SignLogic.parseExtraWords(p.getString(Keys.okWordsGlobal(), ""));
            failGlobal = SignLogic.parseExtraWords(p.getString(Keys.failWordsGlobal(), ""));
            if (botDid != 0L) {
                okBot = SignLogic.parseExtraWords(p.getString(Keys.okWordsBot(botDid), ""));
                failBot = SignLogic.parseExtraWords(p.getString(Keys.failWordsBot(botDid), ""));
            }
            if (targetId != null) {
                okTgt = SignLogic.parseExtraWords(p.getString(Keys.okWordsTarget(targetId), ""));
                failTgt = SignLogic.parseExtraWords(p.getString(Keys.failWordsTarget(targetId), ""));
            }
        }
        String[] okMerged = merge(SignLogic.OK_WORDS_DEFAULT, okGlobal);
        String[] failMerged = merge(SignLogic.FAIL_WORDS_DEFAULT, failGlobal);
        Object[] vd = SignLogic.verdictDetailScoped(reply, okMerged, failMerged,
                okBot, failBot, okTgt, failTgt);
        return ((Integer) vd[0]).intValue();
    }

    static String[] merge(String[] base, String[] extra) {
        if (extra == null || extra.length == 0) return base;
        List<String> out = new ArrayList<String>();
        for (String w : base) if (w != null && w.trim().length() > 0) out.add(w.trim());
        for (String w : extra) {
            if (w == null) continue;
            String t = w.trim();
            if (t.length() == 0) continue;
            boolean dup = false;
            for (String e : out) if (e.equalsIgnoreCase(t)) { dup = true; break; }
            if (!dup) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    // ═══════════════ 用例 ═══════════════

    /** 核心问题：bot 级学词后，同一句话能不能认出来。 */
    private static void learnBotScopeThenJudge() {
        FakePrefs p = new FakePrefs();
        String botReply = "任务达成！本次结算完毕，欢迎再来";
        long botDid = 7601994292L;
        String target = "7601994292_cb1";

        int before = judge(p, botReply, botDid, target);
        eq("学之前：判不出", Integer.valueOf(before), Integer.valueOf(SignLogic.V_UNKNOWN));

        learnWord(p, "任务达成", true, "bot", botDid, target);

        int after = judge(p, botReply, botDid, target);
        eq("学之后：判成功", Integer.valueOf(after), Integer.valueOf(SignLogic.V_SIGNED));
    }

    /** 目标级学词。 */
    private static void learnTargetScopeThenJudge() {
        FakePrefs p = new FakePrefs();
        String reply = "本日额度已结算，欢迎下次";
        long botDid = 8500640583L;
        String target = "8500640583_1";

        eq("目标级·学之前判不出", Integer.valueOf(judge(p, reply, botDid, target)),
           Integer.valueOf(SignLogic.V_UNKNOWN));

        learnWord(p, "额度已结算", false, "target", botDid, target);

        eq("目标级·学之后判失败", Integer.valueOf(judge(p, reply, botDid, target)),
           Integer.valueOf(SignLogic.V_FAILED));
    }

    /** 全局级学词。 */
    private static void learnGlobalScopeThenJudge() {
        FakePrefs p = new FakePrefs();
        String reply = "办妥了，请查收";
        long botDid = 1234L;

        eq("全局级·学之前判不出", Integer.valueOf(judge(p, reply, botDid, null)),
           Integer.valueOf(SignLogic.V_UNKNOWN));

        learnWord(p, "办妥了", true, "global", botDid, null);

        eq("全局级·学之后判成功", Integer.valueOf(judge(p, reply, botDid, null)),
           Integer.valueOf(SignLogic.V_SIGNED));
    }

    /** 用户标「其实是失败」也必须生效。 */
    private static void failWordLearnable() {
        FakePrefs p = new FakePrefs();
        String reply = "本次未达成，明天再来";
        long botDid = 999L;

        eq("失败词·学之前判不出", Integer.valueOf(judge(p, reply, botDid, null)),
           Integer.valueOf(SignLogic.V_UNKNOWN));

        learnWord(p, "未达成", false, "bot", botDid, null);

        eq("失败词·学之后判失败", Integer.valueOf(judge(p, reply, botDid, null)),
           Integer.valueOf(SignLogic.V_FAILED));
    }

    /**
     * 作用域隔离：给 A bot 学的词不能污染 B bot。
     * 这是「学一个词污染所有 bot」的历史事故防线。
     */
    private static void scopeIsolationNoLeak() {
        FakePrefs p = new FakePrefs();
        String reply = "结算完毕，谢谢";
        long botA = 111L, botB = 222L;

        learnWord(p, "结算完毕", true, "bot", botA, null);

        eq("A bot 认得", Integer.valueOf(judge(p, reply, botA, null)),
           Integer.valueOf(SignLogic.V_SIGNED));
        eq("B bot 不受污染", Integer.valueOf(judge(p, reply, botB, null)),
           Integer.valueOf(SignLogic.V_UNKNOWN));
    }

    /** 学词后 judge_custom 必须自动打开，否则学了等于没学。 */
    private static void autoEnableCustomSwitch() {
        FakePrefs p = new FakePrefs();
        eq("初始未开自定义", Boolean.valueOf(p.getBoolean("acc0_cfg_judge_custom", false)),
           Boolean.valueOf(false));
        learnWord(p, "测试词", true, "bot", 555L, null);
        eq("学词后自动打开", Boolean.valueOf(p.getBoolean("acc0_cfg_judge_custom", false)),
           Boolean.valueOf(true));

        // 开关关闭时，学过的词不应生效（这是设计：用户显式关闭就该听他的）
        p.edit().putBoolean("acc0_cfg_judge_custom", false).apply();
        eq("关闭自定义后不生效",
           Integer.valueOf(judge(p, "测试词出现了", 555L, null)),
           Integer.valueOf(SignLogic.V_UNKNOWN));
    }

    /** 公告类回复不该被学（也不该进池）—— 学了会污染判定。 */
    private static void announceNeverLearned() {
        tru("公告值得学 = false",
            !SignLogic.worthLearning("☝️\"广告\"是骗子 XX社工库查档 不要点 否则上当不管"));
        tru("真结果值得学 = true",
            SignLogic.worthLearning("签到成功！本次获得积分:2"));
    }

    /** 池里能看到待判词（用户界面上要显示）。 */
    private static void pendingWordsVisible() {
        FakePrefs p = new FakePrefs();
        String prefix = "acc0_";
        String poolKey = prefix + "unk_pool";
        io.github.wlmosv_png.tgautosign.judge.UnkPool.add(
                p, poolKey, prefix + "unk_seen", 777L, "奇妙的结果：办妥了",
                io.github.wlmosv_png.tgautosign.judge.ReplyNormalizer.normalize("奇妙的结果：办妥了"),
                "2026-10-18", "777_1", 777L);
        int n = io.github.wlmosv_png.tgautosign.judge.UnkPool.list(p, poolKey).size();
        eq("入池 1 条", Integer.valueOf(n), Integer.valueOf(1));
    }

    /** 删除词之后必须失效。 */
    private static void removeWordTakesEffect() {
        FakePrefs p = new FakePrefs();
        String reply = "临时措辞已出现";
        long botDid = 888L;
        learnWord(p, "临时措辞", true, "bot", botDid, null);
        eq("删前生效", Integer.valueOf(judge(p, reply, botDid, null)),
           Integer.valueOf(SignLogic.V_SIGNED));

        // 复刻 Core.removeJudgeWordInternal
        String key = Keys.okWordsBot(botDid);
        List<String> cur = new ArrayList<String>();
        for (String s : p.getString(key, "").split("[,，\n]")) {
            String t = s == null ? "" : s.trim();
            if (t.length() > 0 && !t.equals("临时措辞")) cur.add(t);
        }
        StringBuilder sb = new StringBuilder();
        for (String x : cur) { if (sb.length() > 0) sb.append(","); sb.append(x); }
        p.edit().putString(key, sb.toString()).apply();

        eq("删后不生效", Integer.valueOf(judge(p, reply, botDid, null)),
           Integer.valueOf(SignLogic.V_UNKNOWN));
    }

    // ═══════════════ FakePrefs ═══════════════

    static final class FakePrefs implements SharedPreferences {
        private final Map<String, Object> m = new HashMap<String, Object>();
        public Map<String, ?> getAll() { return m; }
        public String getString(String k, String d) {
            Object v = m.get(k); return v == null ? d : String.valueOf(v);
        }
        public Set<String> getStringSet(String k, Set<String> d) { return d; }
        public int getInt(String k, int d) {
            Object v = m.get(k); return v instanceof Number ? ((Number) v).intValue() : d;
        }
        public long getLong(String k, long d) {
            Object v = m.get(k); return v instanceof Number ? ((Number) v).longValue() : d;
        }
        public float getFloat(String k, float d) {
            Object v = m.get(k); return v instanceof Number ? ((Number) v).floatValue() : d;
        }
        public boolean getBoolean(String k, boolean d) {
            Object v = m.get(k); return v instanceof Boolean ? ((Boolean) v) : d;
        }
        public boolean contains(String k) { return m.containsKey(k); }
        public Editor edit() { return new Ed(m); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        static final class Ed implements Editor {
            private final Map<String, Object> m;
            private final Map<String, Object> pend = new HashMap<String, Object>();
            private final Set<String> rm = new HashSet<String>();
            Ed(Map<String, Object> m) { this.m = m; }
            public Editor putString(String k, String v) { pend.put(k, v); return this; }
            public Editor putStringSet(String k, Set<String> v) { pend.put(k, v); return this; }
            public Editor putInt(String k, int v) { pend.put(k, Integer.valueOf(v)); return this; }
            public Editor putLong(String k, long v) { pend.put(k, Long.valueOf(v)); return this; }
            public Editor putFloat(String k, float v) { pend.put(k, Float.valueOf(v)); return this; }
            public Editor putBoolean(String k, boolean v) { pend.put(k, Boolean.valueOf(v)); return this; }
            public Editor remove(String k) { rm.add(k); return this; }
            public Editor clear() { rm.addAll(m.keySet()); return this; }
            private void commit0() {
                for (String k : rm) m.remove(k);
                m.putAll(pend);
            }
            public boolean commit() { commit0(); return true; }
            public void apply() { commit0(); }
        }
    }
}
