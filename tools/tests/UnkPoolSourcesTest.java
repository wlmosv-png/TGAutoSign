package io.github.wlmosv_png.tgautosign;

import java.util.List;

import io.github.wlmosv_png.tgautosign.judge.ReplyNormalizer;
import io.github.wlmosv_png.tgautosign.judge.UnkPool;

/**
 * UnkPoolSourcesTest —— 遗留问题 #1 / #2 的行为测试。
 *
 * #1 同一句「签到成功」来自 Bot A 与 Bot B 时，
 *    来源必须**累积**而不是只留最后一个。
 * #2 限频语义：每 bot 每天 8 条候选；
 *    同一 normalized 模式每天最多 2 次。
 */
public final class UnkPoolSourcesTest {

    private static int passed = 0;
    private static final java.util.List<String> failed = new java.util.ArrayList<String>();

    public static void main(String[] args) {
        crossBotSources();
        perBotDailyCap();
        perPatternDailyCap();
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

    private static final String RAW = "签到成功";
    private static final String NORM = ReplyNormalizer.normalize(RAW);

    // ── #1：跨 bot 来源累积 ──
    private static void crossBotSources() {
        JudgeTest.FakePrefs p = new JudgeTest.FakePrefs();
        String pool = "a_unk_pool", seen = "a_unk_seen";
        String today = "2026-10-06";
        long botA = 111111L, botB = 222222L;

        UnkPool.add(p, pool, seen, -100L, RAW, NORM, today, "t1", botA);
        UnkPool.add(p, pool, seen, -100L, RAW, NORM, today, "t1", botB);

        List<UnkPool.Item> items = UnkPool.list(p, pool);
        eq("#1 同句同模式仍是一条", Integer.valueOf(items.size()), Integer.valueOf(1));
        UnkPool.Item it = items.get(0);
        List<Long> fs = it.fromList();
        tru("#1 来源含 Bot A", fs.contains(Long.valueOf(botA)));
        tru("#1 来源含 Bot B", fs.contains(Long.valueOf(botB)));
        eq("#1 来源个数=2", Integer.valueOf(fs.size()), Integer.valueOf(2));
    }

    // ── #2a：每 bot 每天上限 8 条候选 ──
    private static void perBotDailyCap() {
        JudgeTest.FakePrefs p = new JudgeTest.FakePrefs();
        String pool = "b_unk_pool", seen = "b_unk_seen";
        String today = "2026-10-06";
        long bot = 555L;
        int accepted = 0;
        // 12 条**不同**原文 → 旧实现会全收；新实现第 9 条起拒绝
        // 注意：原文不能含数字 —— normalize 会把数字替换成 {N}，
        // 那样 12 条会塌成同一个模式，被「同模式每天 2 次」先拦下（测的就不是本项了）。
        String[] RAWS = {"签到成功", "打卡完成", "领取成功", "任务达成", "已参与活动",
                         "恭喜获得奖励", "今日已完成", "操作成功", "奖励已到账",
                         "参加成功", "提交完成", "处理完毕"};
        for (String raw : RAWS) {
            boolean ok = UnkPool.add(p, pool, seen, bot, raw,
                                     ReplyNormalizer.normalize(raw), today, null, 0L);
            if (ok) accepted++;
        }
        eq("#2 每 bot 每天最多 8 条", Integer.valueOf(accepted), Integer.valueOf(UnkPool.DAILY_PER_BOT));
    }

    // ── #2b：同一模式每天最多 2 次 ──
    private static void perPatternDailyCap() {
        JudgeTest.FakePrefs p = new JudgeTest.FakePrefs();
        String pool = "c_unk_pool", seen = "c_unk_seen";
        String today = "2026-10-06";
        long bot = 777L;
        int accepted = 0;
        for (int i = 0; i < 5; i++) {
            boolean ok = UnkPool.add(p, pool, seen, bot, RAW, NORM, today, null, 0L);
            if (ok) accepted++;
        }
        eq("#2 同模式每天最多 2 次", Integer.valueOf(accepted),
           Integer.valueOf(UnkPool.PER_PATTERN_PER_DAY));
    }

    // ── 旧记录（无 froms/tids）仍可读 ──
    private static void legacyCompat() {
        JudgeTest.FakePrefs p = new JudgeTest.FakePrefs();
        String pool = "d_unk_pool";
        // 模拟旧格式：只有 t/d/n/r/o + from
        p.edit().putString(pool,
            "t=1791173379985\u0001d=8439387373\u0001n=1\u0001r=旧记录模式\u0001o=旧记录原文"
            + "\u0001tid=\u0001from=999\u0001chat=8439387373").apply();

        List<UnkPool.Item> items = UnkPool.list(p, pool);
        eq("旧记录可读", Integer.valueOf(items.size()), Integer.valueOf(1));
        UnkPool.Item it = items.get(0);
        eq("旧记录文本保留", it.r, "旧记录模式");
        tru("旧记录 fromList 退回单值", it.fromList().contains(Long.valueOf(999L)));
    }
}
