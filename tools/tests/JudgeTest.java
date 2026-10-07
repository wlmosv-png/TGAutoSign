package io.github.wlmosv_png.tgautosign;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.wlmosv_png.tgautosign.judge.ReplyNormalizer;
import io.github.wlmosv_png.tgautosign.judge.UnkPool;

/**
 * judge 子系统单测（2026-10-06 补做第 5 项）。
 *
 * 为什么单独一个 runner：
 *   judge/UnkPool 依赖 android.content.SharedPreferences，SignLogicTest 的
 *   最小编译集里没有它。这里配 tools/tests/stubs/ 的桩 + 一个内存版假实现，
 *   让 UnkPool 能在纯 Java 环境里被真正测到。
 *
 * 运行（与 SignLogicTest 同法）：
 *   javac -d out tools/tests/stubs/android/content/*.java \
 *         tools/tests/JudgeTest.java \
 *         app/src/main/java/.../judge/*.java
 *   java -cp out io.github.wlmosv_png.tgautosign.JudgeTest
 *
 * 退出码 0 = 全过。
 */
public final class JudgeTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    public static void main(String[] args) {
        replyNormalizer();
        unkPool();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) {
            System.out.println("FAILED");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }

    // ── 断言助手 ──

    private static void eq(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) passed++;
        else failed.add(what + " → 得到 " + got + "，期望 " + want);
    }

    private static void tru(String what, boolean cond) {
        if (cond) passed++;
        else failed.add(what + " → 条件不成立");
    }

    // ══════════════════════════════════════════════════════════════
    //  ReplyNormalizer（纯函数，不需要桩）
    // ══════════════════════════════════════════════════════════════
    private static void replyNormalizer() {
        // 全角转半角 + 数字归一 + 标点空白统一 + 去装饰符
        eq("归一化 数字→{N}", ReplyNormalizer.normalize("签到成功，获得 5 积分"),
           ReplyNormalizer.normalize("签到成功 获得 8 积分"));
        eq("归一化 去 emoji", ReplyNormalizer.normalize("✅ 签到成功"), "签到成功");
        eq("归一化 空串", ReplyNormalizer.normalize(""), "");
        eq("归一化 null", ReplyNormalizer.normalize(null), "");

        // 聚类：语义相同的不同写法应当合并
        List<String> raws = new ArrayList<String>();
        raws.add("✅ 签到成功，获得 5 积分");
        raws.add("签到成功，获得 3 积分");
        raws.add("签到成功 获得 8 积分");
        raws.add("⭕ 您今天已经签到过了！");
        raws.add("您今天已经签到过了");
        List<ReplyNormalizer.Pattern> pats = ReplyNormalizer.cluster(raws);
        eq("聚类 收敛成 2 个模式", Integer.valueOf(pats.size()), Integer.valueOf(2));
        eq("聚类 最大模式计数 3", Integer.valueOf(pats.get(0).count), Integer.valueOf(3));
        tru("聚类 样例已去重", pats.get(0).samples.size() <= 3);

        // 空输入
        eq("聚类 空输入", Integer.valueOf(ReplyNormalizer.cluster(new ArrayList<String>()).size()),
           Integer.valueOf(0));
    }

    // ══════════════════════════════════════════════════════════════
    //  UnkPool（用内存版假 SharedPreferences）
    // ══════════════════════════════════════════════════════════════
    private static void unkPool() {
        FakePrefs p = new FakePrefs();
        String pool = "acc1_unk_pool";
        String seen = "acc1_unk_seen";
        String today = DateUtils.today();

        // 写入
        tru("UnkPool.add 首次成功",
            UnkPool.add(p, pool, seen, 111L, "签到成功，获得 5 积分",
                        ReplyNormalizer.normalize("签到成功，获得 5 积分"), today));
        eq("UnkPool 条数 = 1", Integer.valueOf(UnkPool.rawCount(p, pool)), Integer.valueOf(1));

        // 相同归一化文本 → 计数 +1，不新增条目
        UnkPool.add(p, pool, seen, 111L, "签到成功，获得 9 积分",
                    ReplyNormalizer.normalize("签到成功，获得 9 积分"), today);
        eq("UnkPool 同文本合并（仍 1 条）", Integer.valueOf(UnkPool.rawCount(p, pool)), Integer.valueOf(1));
        eq("UnkPool 合并后计数 = 2", Integer.valueOf(UnkPool.list(p, pool).get(0).n), Integer.valueOf(2));

        // 另一个 bot
        UnkPool.add(p, pool, seen, 222L, "您今天已经签到过了",
                    ReplyNormalizer.normalize("您今天已经签到过了"), today);
        eq("UnkPool 条数 = 2", Integer.valueOf(UnkPool.rawCount(p, pool)), Integer.valueOf(2));
        eq("UnkPool bot 数 = 2", Integer.valueOf(UnkPool.botCount(p, pool)), Integer.valueOf(2));

        // 删除
        tru("UnkPool.remove 命中",
            UnkPool.remove(p, pool, ReplyNormalizer.normalize("您今天已经签到过了")));
        eq("UnkPool 删除后条数 = 1", Integer.valueOf(UnkPool.rawCount(p, pool)), Integer.valueOf(1));

        // 空参数保护（不得抛）
        eq("UnkPool.add null prefs → false", Boolean.valueOf(UnkPool.add(null, pool, seen, 1L, "x", "x", today)), Boolean.FALSE);
        eq("UnkPool.add 空文本 → false", Boolean.valueOf(UnkPool.add(p, pool, seen, 1L, "", "", today)), Boolean.FALSE);

        // 限频常量（P2 后为 8）
        eq("UnkPool DAILY_PER_BOT", Integer.valueOf(UnkPool.DAILY_PER_BOT), Integer.valueOf(8));
        eq("UnkPool MAX_ITEMS", Integer.valueOf(UnkPool.MAX_ITEMS), Integer.valueOf(200));

        // 清空
        UnkPool.clear(p, pool, seen);
        eq("UnkPool 清空后条数 = 0", Integer.valueOf(UnkPool.rawCount(p, pool)), Integer.valueOf(0));
    }

    // ══════════════════════════════════════════════════════════════
    //  内存版假 SharedPreferences（仅测试用）
    // ══════════════════════════════════════════════════════════════
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
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

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
            @Override public void apply() {}
        }
    }
}
