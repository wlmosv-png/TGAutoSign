package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.SharedPreferences;

import io.github.wlmosv_png.tgautosign.judge.ReplyNormalizer;
import io.github.wlmosv_png.tgautosign.judge.UnkPool;

/**
 * ScopedWireTest —— 「UI 学的词是否真的参与实时判定」的行为测试。
 *
 * 为什么要有这个文件：
 *   上一轮实现了三级词表 + verdictDetailScoped，但**实时判定链仍在读
 *   全局 jmb_ok_words**，于是「学了不生效」。
 *   现有 GroupScopeTest 只测 SignLogic 的纯函数优先级，
 *   测不出「Core 有没有接线」。
 *
 *   本文件把 Core 里的接线逻辑**按其真实语义复刻成纯函数**，
 *   覆盖四种作用域归属、群聊 did≠bot did、以及跨 bot 不污染。
 *
 * 说明：Core 的 onUpdateProcessed 依赖 Xposed/Telegram 运行时，无法在此直接调用；
 *   这里测的是「同一份 key 生成规则 + 同一份判定入口」的组合行为。
 *   key 生成规则来自 Keys（生产类），判定入口来自 SignLogic（生产类），
 *   只有「从 prefs 取哪几个 key、按什么顺序合并」这一步是复刻。
 *   该复刻与 Core 中 patch 后的代码逐行对应。
 */
public final class ScopedWireTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    public static void main(String[] args) {
        learnThenJudge();
        botIsolation();
        targetIsolation();
        groupDidVsBotDid();
        failurePrecedence();
        negationStillWorks();
        legacyCompat();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }

    private static void eq(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) passed++;
        else failed.add(what + " → 得到 " + got + "，期望 " + want);
    }

    private static void tru(String what, boolean cond) {
        if (cond) passed++; else failed.add(what + " → 条件不成立");
    }

    // ════════════════════════════════════════════════════════════
    //  复刻 Core 的接线：从 prefs 取三级词表 → verdictDetailScoped
    //  与 TGAutoSignCore 中 patch 后的代码一一对应。
    // ════════════════════════════════════════════════════════════

    /** 复刻 addJudgeWordScopedInternal 的 key 选择（与生产代码同一 Keys）。 */
    static String scopeKey(boolean isOk, String scope, long did, String targetId) {
        if ("global".equals(scope)) {
            return isOk ? Keys.okWordsGlobal() : Keys.failWordsGlobal();
        } else if ("target".equals(scope) && targetId != null && targetId.length() > 0) {
            return isOk ? Keys.okWordsTarget(targetId) : Keys.failWordsTarget(targetId);
        } else if (did != 0L) {
            return isOk ? Keys.okWordsBot(did) : Keys.failWordsBot(did);
        } else if (targetId != null && targetId.length() > 0) {
            return isOk ? Keys.okWordsTarget(targetId) : Keys.failWordsTarget(targetId);
        }
        return isOk ? Keys.okWordsGlobal() : Keys.failWordsGlobal();
    }

    /** 复刻 Core 判定链：读三级词表并调用 scoped 入口。 */
    static int judge(SharedPreferences p, String reply,
                     long scopeBotDid, String scopeTargetId, boolean useCustom) {
        String[] okGlobal = null, failGlobal = null, okBot = null, failBot = null,
                 okTgt = null, failTgt = null;
        if (useCustom) {
            okGlobal = SignLogic.parseExtraWords(p.getString(Keys.okWordsGlobal(), ""));
            failGlobal = SignLogic.parseExtraWords(p.getString(Keys.failWordsGlobal(), ""));
            okBot = SignLogic.parseExtraWords(p.getString(Keys.okWordsBot(scopeBotDid), ""));
            failBot = SignLogic.parseExtraWords(p.getString(Keys.failWordsBot(scopeBotDid), ""));
            if (scopeTargetId != null) {
                okTgt = SignLogic.parseExtraWords(p.getString(Keys.okWordsTarget(scopeTargetId), ""));
                failTgt = SignLogic.parseExtraWords(p.getString(Keys.failWordsTarget(scopeTargetId), ""));
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

    // ════════════════════════════════════════════════════════════
    //  ① UI 学一个 Bot 的成功词 → 该 bot 下一次实时判定成功
    //     （本轮完成标准的第一条）
    // ════════════════════════════════════════════════════════════
    private static void learnThenJudge() {
        FakePrefs p = new FakePrefs();
        String reply = "今日任务完成啦";
        long botA = 111111L;
        String tgtA = "111111_1";

        // 学之前：判不出
        eq("未学词 → UNKNOWN", judge(p, reply, botA, tgtA, true), SignLogic.V_UNKNOWN);

        // 用户在「未识别回复」页：填词「任务完成」+ 选「这个 bot」→ 保存
        String key = scopeKey(true, "bot", botA, tgtA);
        p.edit().putString(key, "任务完成").apply();

        // 学之后：同一个 bot 的下一次回复 → SUCCESS
        eq("学词后（Bot 级）→ SIGNED", judge(p, reply, botA, tgtA, true), SignLogic.V_SIGNED);
        eq("词确实进的是 Bot 级键", key, Keys.okWordsBot(botA));
    }

    // ════════════════════════════════════════════════════════════
    //  ② Bot 级隔离：A 的词不影响 B（不污染）
    // ════════════════════════════════════════════════════════════
    private static void botIsolation() {
        FakePrefs p = new FakePrefs();
        String reply = "完成啦";
        long botA = 111111L, botB = 222222L;

        p.edit().putString(Keys.okWordsBot(botA), "完成啦").apply();

        eq("Bot A 命中自己的词 → SIGNED", judge(p, reply, botA, "111111_1", true), SignLogic.V_SIGNED);
        // 这是「默认不污染所有 bot」的核心断言
        eq("Bot B 不该继承 A 的词", judge(p, reply, botB, "222222_1", true), SignLogic.V_UNKNOWN);
    }

    // ════════════════════════════════════════════════════════════
    //  ③ 目标级隔离
    // ════════════════════════════════════════════════════════════
    private static void targetIsolation() {
        FakePrefs p = new FakePrefs();
        String reply = "完成啦";
        long botA = 111111L;

        p.edit().putString(Keys.okWordsTarget("111111_1"), "完成啦").apply();

        eq("目标 1 命中自己的词 → SIGNED",
           judge(p, reply, botA, "111111_1", true), SignLogic.V_SIGNED);
        eq("目标 2 不该继承目标 1 的词",
           judge(p, reply, botA, "111111_2", true), SignLogic.V_UNKNOWN);
    }

    // ════════════════════════════════════════════════════════════
    //  ④ 群聊：did 是群 id，词表归属必须用 bot did
    //     否则同群所有 bot 共用一张表 → 互相污染
    // ════════════════════════════════════════════════════════════
    private static void groupDidVsBotDid() {
        FakePrefs p = new FakePrefs();
        // 用不在内置词表里的措辞 —— 否则内置词会先命中，测不出作用域隔离
        String reply = "任务达成";
        long group = -1001234567890L;
        long botX = 555555L, botY = 666666L;

        // 用户在群里给 Bot X 学了词 → 键必须是 bot id，不是群 id
        String key = scopeKey(true, "bot", botX, "x_1");
        p.edit().putString(key, "任务达成").apply();
        eq("群聊下 Bot 级键用 bot did 而非群 did", key, Keys.okWordsBot(botX));
        tru("键里不含群 id", key.indexOf(String.valueOf(group)) < 0);

        eq("群里的 Bot X 命中 → SIGNED", judge(p, reply, botX, "x_1", true), SignLogic.V_SIGNED);
        eq("同群的 Bot Y 不该命中", judge(p, reply, botY, "y_1", true), SignLogic.V_UNKNOWN);
    }

    // ════════════════════════════════════════════════════════════
    //  ⑤ 失败词优先：不能被「…成功」这种子串翻成成功
    // ════════════════════════════════════════════════════════════
    private static void failurePrecedence() {
        FakePrefs p = new FakePrefs();
        long bot = 1L;
        p.edit().putString(Keys.okWordsBot(bot), "成功").apply();
        p.edit().putString(Keys.failWordsBot(bot), "失败").apply();

        eq("『失败，但显示成功』→ FAILED",
           judge(p, "失败，但显示成功", bot, "1_1", true), SignLogic.V_FAILED);

        // 目标级失败词 > Bot 级成功词
        FakePrefs p2 = new FakePrefs();
        p2.edit().putString(Keys.okWordsBot(bot), "完成").apply();
        p2.edit().putString(Keys.failWordsTarget("1_1"), "已过期").apply();
        eq("目标级失败词优先于 Bot 级成功词",
           judge(p2, "任务完成，已过期", bot, "1_1", true), SignLogic.V_FAILED);
    }

    // ════════════════════════════════════════════════════════════
    //  ⑥ 否定守卫在 scoped 路径上依然生效
    // ════════════════════════════════════════════════════════════
    private static void negationStillWorks() {
        FakePrefs p = new FakePrefs();
        long bot = 1L;
        p.edit().putString(Keys.okWordsBot(bot), "签到成功").apply();

        eq("『还没有签到成功』不该判成功",
           judge(p, "还没有签到成功", bot, "1_1", true), SignLogic.V_FAILED);
        eq("『没问题,签到成功』仍算成功",
           judge(p, "没问题,签到成功", bot, "1_1", true), SignLogic.V_SIGNED);
    }

    // ════════════════════════════════════════════════════════════
    //  ⑦ 旧配置兼容：老用户的全局词继续有效
    // ════════════════════════════════════════════════════════════
    private static void legacyCompat() {
        FakePrefs p = new FakePrefs();
        // 旧版本只写过 jmb_ok_words
        p.edit().putString("jmb_ok_words", "打卡完成").apply();
        eq("旧全局词继续生效",
           judge(p, "今日打卡完成", 999L, "999_1", true), SignLogic.V_SIGNED);

        FakePrefs p2 = new FakePrefs();
        // 三级词表全空 → 行为必须等价旧版（走内置默认词）
        eq("无自定义词时内置词仍生效",
           judge(p2, "签到成功", 999L, "999_1", true), SignLogic.V_SIGNED);
    }

    // ── 内存版 SharedPreferences（与 JudgeTest 同风格，独立避免互相依赖） ──
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
