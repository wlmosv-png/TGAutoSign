package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.wlmosv_png.tgautosign.judge.ReplyNormalizer;
import io.github.wlmosv_png.tgautosign.judge.UnkPool;

/**
 * 群聊 / 作用域 / 来源 —— 交接单第二十三条第 4~9 项的测试。
 *
 * 说明：Peer 解析本身依赖 Telegram 的 TL 对象（反射），纯 Java 环境无法构造，
 * 所以这里测**可纯函数化的部分**：
 *   · Peer → dialogId 的**换算规则**（用 mock 对象验证三分支数学）
 *   · 判定词作用域叠加顺序
 *   · UnkPool 来源保存
 *   · ReplyNormalizer 来源聚类
 */
public final class GroupScopeTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    public static void main(String[] args) {
        peerDialogIdMath();
        scopedWords();
        unkPoolSource();
        normalizerSource();
        groupSignCommand();

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

    // ══════════════════════════════════════════════════════════════
    //  ① Peer → dialogId 换算规则（交接单第一/四条、第二十三条 4~6）
    //     这里用 mock 复刻三分支的**数学**，与生产代码保持同一组常量。
    //     user_id  → 正数本身
    //     chat_id  → -chat_id
    //     channel  → -(1000000000000L + channel_id)
    // ══════════════════════════════════════════════════════════════

    /** mock peer：用一个长字段表示三种 peer 之一。 */
    static final class MockPeer {
        Long user_id, chat_id, channel_id;
        MockPeer(Long u, Long c, Long ch) { user_id = u; chat_id = c; channel_id = ch; }
    }

    /** 与生产代码 resolveDialogIdFromPeer 相同的换算逻辑（纯函数版）。 */
    static long dialogIdOf(MockPeer p) {
        if (p == null) return 0L;
        if (p.user_id != null && p.user_id != 0L) return p.user_id;
        if (p.chat_id != null && p.chat_id != 0L) return -p.chat_id;
        if (p.channel_id != null && p.channel_id != 0L) return -(1000000000000L + p.channel_id);
        return 0L;
    }

    private static void peerDialogIdMath() {
        // TL_peerUser
        eq("peerUser → user_id",
           dialogIdOf(new MockPeer(123456789L, null, null)), 123456789L);
        // TL_peerChat（普通群）：-chat_id
        eq("peerChat → -chat_id",
           dialogIdOf(new MockPeer(null, 123456L, null)), -123456L);
        // TL_peerChannel（超级群/频道）：-(1000000000000 + channel_id)
        eq("peerChannel → -(1e12 + channel_id)",
           dialogIdOf(new MockPeer(null, null, 2123456789L)), -(1000000000000L + 2123456789L));
        // 空 peer
        eq("peer 全空 → 0", dialogIdOf(new MockPeer(null, null, null)), 0L);
        eq("peer null → 0", dialogIdOf(null), 0L);

        // 关键断言：群聊 dialogId 必须是负数（旧代码只读 user_id 会得到 0）
        tru("群 dialogId < 0", dialogIdOf(new MockPeer(null, 999L, null)) < 0);
        tru("频道 dialogId < 0", dialogIdOf(new MockPeer(null, null, 999L)) < 0);
        tru("私聊 dialogId > 0", dialogIdOf(new MockPeer(999L, null, null)) > 0);

        // 频道负数必须大于 2^31（否则与普通群冲突）
        long ch = dialogIdOf(new MockPeer(null, null, 1L));
        tru("频道 id 超出 int 范围（不会与普通群撞）", ch < Integer.MIN_VALUE);
    }

    // ══════════════════════════════════════════════════════════════
    //  ② 判定词作用域（交接单第八条、第二十三条 7）
    // ══════════════════════════════════════════════════════════════
    private static void scopedWords() {
        String reply = "今日任务完成啦，获得 10 积分";

        // 只给全局词 → 命中
        Object[] v1 = SignLogic.verdictDetailScoped(reply,
                new String[]{"任务完成"}, null, null, null, null, null);
        eq("全局词命中 → SIGNED", v1[0], Integer.valueOf(SignLogic.V_SIGNED));
        eq("全局词命中词", v1[1], "任务完成");

        // 只给 Bot 级词 → 命中
        Object[] v2 = SignLogic.verdictDetailScoped(reply,
                null, null, new String[]{"任务完成"}, null, null, null);
        eq("Bot 级词命中 → SIGNED", v2[0], Integer.valueOf(SignLogic.V_SIGNED));

        // 只给目标级词 → 命中
        Object[] v3 = SignLogic.verdictDetailScoped(reply,
                null, null, null, null, new String[]{"任务完成"}, null);
        eq("目标级词命中 → SIGNED", v3[0], Integer.valueOf(SignLogic.V_SIGNED));

        // 失败词优先于成功词（即使成功词更具体）
        Object[] v4 = SignLogic.verdictDetailScoped(reply,
                new String[]{"任务完成"}, null,
                null, new String[]{"完成啦"},
                null, null);
        eq("失败词优先于成功词", v4[0], Integer.valueOf(SignLogic.V_FAILED));

        // 目标级失败词优先于 Bot 级成功词
        Object[] v5 = SignLogic.verdictDetailScoped(reply,
                null, null, new String[]{"任务完成"}, null, null, new String[]{"完成啦"});
        eq("目标级失败词压过 Bot 级成功词", v5[0], Integer.valueOf(SignLogic.V_FAILED));

        // 都不命中 → 落回内置链
        Object[] v6 = SignLogic.verdictDetailScoped("完全无关的一句话",
                null, null, null, null, null, null);
        eq("都未命中 → UNKNOWN", v6[0], Integer.valueOf(SignLogic.V_UNKNOWN));

        // 作用域空数组不得抛
        Object[] v7 = SignLogic.verdictDetailScoped("签到成功",
                new String[0], new String[0], new String[0], new String[0], new String[0], new String[0]);
        eq("空数组回落到内置链（签到成功）", v7[0], Integer.valueOf(SignLogic.V_SIGNED));
    }

    // ══════════════════════════════════════════════════════════════
    //  ③ UnkPool 来源保存（交接单第九条、第二十三条 6）
    // ══════════════════════════════════════════════════════════════
    private static void unkPoolSource() {
        JudgeTest.FakePrefs p = new JudgeTest.FakePrefs();
        String pool = "acc1_unk_pool", seen = "acc1_unk_seen";
        String today = DateUtils.today();
        String raw = "恭喜您,今日任务完成啦,获得 10 积分";
        String norm = ReplyNormalizer.normalize(raw);

        // 带来源写入
        UnkPool.add(p, pool, seen, -1001234567890L, raw, norm, today,
                    "target-1", 987654321L);

        List<UnkPool.Item> items = UnkPool.list(p, pool);
        eq("来源写入 条数=1", Integer.valueOf(items.size()), Integer.valueOf(1));
        if (!items.isEmpty()) {
            eq("来源 tid 已保存", items.get(0).tid, "target-1");
            eq("来源 from(bot) 已保存", Long.valueOf(items.get(0).from), Long.valueOf(987654321L));
            eq("来源 chat 已保存", Long.valueOf(items.get(0).chat), Long.valueOf(-1001234567890L));
        }

        // 序列化回环：读出来还在（验证向后兼容 + 新字段持久化）
        String blob = (String) p.m.get(pool);
        tru("序列化含 tid 字段", blob != null && blob.contains("tid"));
        tru("序列化含 from 字段", blob != null && blob.contains("from"));

        // 旧格式（无新字段）不得崩：手工塞一条老记录
        JudgeTest.FakePrefs p2 = new JudgeTest.FakePrefs();
        p2.m.put("acc1_unk_pool", "t=1\u0001d=111\u0001n=1\u0001r=老记录\u0001o=老记录");
        List<UnkPool.Item> old = UnkPool.list(p2, "acc1_unk_pool");
        eq("旧格式可读（条数=1）", Integer.valueOf(old.size()), Integer.valueOf(1));
        eq("旧格式 r 正确", old.get(0).r, "老记录");
        eq("旧格式 from 默认为 0", Long.valueOf(old.get(0).from), Long.valueOf(0L));
    }

    // ══════════════════════════════════════════════════════════════
    //  ④ ReplyNormalizer 来源聚类（交接单第十条、第二十三条 6）
    // ══════════════════════════════════════════════════════════════
    private static void normalizerSource() {
        List<UnkPool.Item> items = new ArrayList<UnkPool.Item>();
        items.add(mkItem("签到成功", 111L, 111L, "t1"));
        items.add(mkItem("签到成功", 222L, 222L, "t2"));
        items.add(mkItem("签到失败", 111L, 111L, "t1"));

        List<ReplyNormalizer.Pattern> pats = ReplyNormalizer.clusterItems(items);
        eq("来源聚类 模式数=2", Integer.valueOf(pats.size()), Integer.valueOf(2));

        ReplyNormalizer.Pattern first = pats.get(0);
        eq("最大模式是「签到成功」", first.norm, "签到成功");
        eq("来源 dids 记录了 2 个会话", Integer.valueOf(first.dids.size()), Integer.valueOf(2));
        tru("来源 dids 含 111", first.dids.contains(111L));
        tru("来源 dids 含 222", first.dids.contains(222L));
        eq("来源 targetIds 记录了 2 个目标", Integer.valueOf(first.targetIds.size()), Integer.valueOf(2));
        tru("来源 targetIds 含 t1", first.targetIds.contains("t1"));

        // 旧 cluster(List<String>) 仍可用（来源集合为空，属预期）
        List<String> raws = new ArrayList<String>();
        raws.add("签到成功");
        raws.add("签到成功");
        List<ReplyNormalizer.Pattern> legacy = ReplyNormalizer.cluster(raws);
        eq("旧接口仍可用", Integer.valueOf(legacy.size()), Integer.valueOf(1));
        eq("旧接口 dids 为空（无法知道来源）", Integer.valueOf(legacy.get(0).dids.size()), Integer.valueOf(0));
    }

    /**
     * 群聊口令判定（2026-10-07）。
     *
     * 起因：群聊网络学习的关键词是子串匹配，用户在群里发
     * 「谁找找 我修复群聊签到」被当成签到口令收进了待添加。
     * 群聊额外要求「像一条口令」，这里把边界钉住。
     */
    private static void groupSignCommand() {
        // ① 斜杠命令：算
        eq("斜杠 /checkin", Boolean.TRUE, Boolean.valueOf(SignLogic.looksLikeSignCommand("/checkin")));
        eq("斜杠 /qd", Boolean.TRUE, Boolean.valueOf(SignLogic.looksLikeSignCommand("/qd")));
        eq("斜杠 /sign", Boolean.TRUE, Boolean.valueOf(SignLogic.looksLikeSignCommand("/sign")));
        eq("前后空格也认", Boolean.TRUE, Boolean.valueOf(SignLogic.looksLikeSignCommand("  /checkin  ")));

        // ② 短口令：算
        eq("签到", Boolean.TRUE, Boolean.valueOf(SignLogic.looksLikeSignCommand("签到")));
        eq("每日签到", Boolean.TRUE, Boolean.valueOf(SignLogic.looksLikeSignCommand("每日签到")));
        eq("打卡", Boolean.TRUE, Boolean.valueOf(SignLogic.looksLikeSignCommand("打卡")));
        eq("带 emoji 的短口令", Boolean.TRUE, Boolean.valueOf(SignLogic.looksLikeSignCommand("🌵签到🌵")));

        // ③ 闲聊句子：不算（这是本次要修的场景）
        eq("谁找找 我修复群聊签到", Boolean.FALSE,
           Boolean.valueOf(SignLogic.looksLikeSignCommand("谁找找 我修复群聊签到")));
        eq("今天有人签到成功了吗", Boolean.FALSE,
           Boolean.valueOf(SignLogic.looksLikeSignCommand("今天有人签到成功了吗")));
        eq("我刚签到了你们呢啊哈哈", Boolean.FALSE,
           Boolean.valueOf(SignLogic.looksLikeSignCommand("我刚签到了你们呢啊哈哈")));

        // ④ 边角
        eq("空串", Boolean.FALSE, Boolean.valueOf(SignLogic.looksLikeSignCommand("")));
        eq("null", Boolean.FALSE, Boolean.valueOf(SignLogic.looksLikeSignCommand(null)));
        eq("纯 emoji", Boolean.FALSE, Boolean.valueOf(SignLogic.looksLikeSignCommand("🌵🌵🌵")));
    }

    private static UnkPool.Item mkItem(String text, long chat, long from, String tid) {
        UnkPool.Item it = new UnkPool.Item();
        it.t = System.currentTimeMillis();
        it.d = chat;
        it.from = from;
        it.chat = chat;
        it.tid = tid;
        it.n = 1;
        it.r = ReplyNormalizer.normalize(text);
        it.o = text;
        return it;
    }
}
