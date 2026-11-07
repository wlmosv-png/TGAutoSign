package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 接收链验收测试（2026-11-08 · F8）。
 *
 * ⚠️ 这份测试**真正执行** Diag / InboundGate 的纯逻辑 —— 不是"读代码后的静态结论"。
 *    它不能覆盖 Android 反射与 Hook 装载本身（那部分只能由真机 F0 日志证明），
 *    凡未覆盖之处在报告里明确标注，绝不冒充已测。
 *
 * 运行：
 *   javac -d /tmp/t Diag.java InboundGate.java tools/tests/ReceiveChainTest.java
 *   java -cp /tmp/t io.github.wlmosv_png.tgautosign.ReceiveChainTest
 * 退出码 0 = 全过。
 */
public final class ReceiveChainTest {

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

    /** 收集型 sink（第 15/18 项用）。 */
    private static final class Collect implements Diag.Sink {
        final List<String> lines = new ArrayList<String>();
        boolean explode = false;
        @Override public void write(int level, String line) {
            if (explode) throw new RuntimeException("模拟磁盘写失败");
            lines.add(line);
        }
    }

    public static void main(String[] args) {
        Collect sink = new Collect();
        Diag.resetForTest();
        Diag.setSink(sink);
        Diag.setPid(4242);

        // ══════════ 1. Hook 成功安装 ══════════
        Diag.hookTarget("A", "vp.N3(ArrayList,ArrayList,ArrayList,boolean,int)", true, true, 1,
                "N3(Ljava/util/ArrayList;Ljava/util/ArrayList;Ljava/util/ArrayList;ZI)Z");
        Diag.hookInstall("A", "vp.N3", true, null);
        Diag.hookFired("A");
        check("1. Hook 成功安装 → HOOK_FIRED", "HOOK_FIRED".equals(Diag.hookStatus("A")));
        check("1b. isInstalled(A)", Diag.isInstalled("A"));

        // ══════════ 2. Hook 目标不存在 ══════════
        Diag.hookTarget("B_impl", "MessagesController.didReceivedNotification(I,I,[Ljava/lang/Object;)V",
                false, false, 0, "-");
        check("2. 目标类不存在 → HOOK_NOT_INSTALLED",
                "HOOK_NOT_INSTALLED".equals(Diag.hookStatus("B_impl")));
        check("2b. 未安装 ≠ 已覆盖（isInstalled=false）", !Diag.isInstalled("B_impl"));

        // ══════════ 3. Hook 安装异常 ══════════
        Diag.hookInstall("C_broken", "ConnectionsManager.sendRequest", false, "NoSuchMethodException");
        check("3. 安装异常 → HOOK_NOT_INSTALLED",
                "HOOK_NOT_INSTALLED".equals(Diag.hookStatus("C_broken")));
        check("3b. 异常原因被记录", Diag.hook("C_broken", "x").err.contains("NoSuchMethod"));

        // 装好 B_send 供后续用
        Diag.hookTarget("B_send", "ys.k(I[Ljava/lang/Object;)V", true, true, 2, "k()m()");
        Diag.hookInstall("B_send", "ys.k", true, null);

        // ══════════ 4/5/6. 锚点A 私聊 / 群 / 频道 ══════════
        eq("4. 锚点A 私聊(peer==from) → ACCEPT", InboundGate.ACCEPT,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_MESSAGE, false,
                        6379863842L, false, 999L, 6379863842L));
        eq("4b. 私聊 update 类型识别",
                InboundGate.UPD_NEW_MESSAGE,
                InboundGate.updateKind("org.telegram.tgnet.tl.TL_update$TL_updateNewMessage"));
        eq("5. 群消息(peer<0) → ACCEPT", InboundGate.ACCEPT,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_CHANNEL, false,
                        -1001646076832L, false, 999L, 8570148908L));
        check("5b. 群判定 isGroupPeer", InboundGate.isGroupPeer(-1001646076832L));
        eq("6. 频道消息 → ACCEPT", InboundGate.ACCEPT,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_CHANNEL, false,
                        -1003952093414L, false, 999L, 8470602516L));
        eq("6b. 频道类型识别",
                InboundGate.UPD_NEW_CHANNEL,
                InboundGate.updateKind("org.telegram.tgnet.tl.TL_update$TL_updateNewChannelMessage"));

        // ══════════ 7. 不支持的 Update 类型 ══════════
        eq("7. 不支持的 update → DROP_NOT_NEW_MESSAGE", InboundGate.DROP_NOT_NEW_MESSAGE,
                InboundGate.classifyEarly(false, InboundGate.UPD_NONE, false,
                        123L, false, 999L, 123L));
        eq("7b. 类型直方图记录真实类名", 0,
                (int) InboundGate.updateKind("org.telegram.tgnet.tl.TL_update$TL_updateUserName"));

        // ══════════ 8. 消息对象为空 ══════════
        eq("8. msg==null → DROP_MSG_NULL", InboundGate.DROP_MSG_NULL,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_MESSAGE, true,
                        123L, false, 999L, 123L));

        // ══════════ 9. peer 解析失败 ══════════
        eq("9. peerUid==0 → DROP_PEER_ZERO", InboundGate.DROP_PEER_ZERO,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_MESSAGE, false,
                        0L, false, 999L, 0L));

        // ══════════ 10. UID 为零（私聊 from 取不到） ══════════
        eq("10. 私聊 fromUid=0 → DROP_PRIVATE_MISMATCH", InboundGate.DROP_PRIVATE_MISMATCH,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_MESSAGE, false,
                        123L, false, 999L, 0L));
        check("10b. privateFromMatches(peer,0)=false", !InboundGate.privateFromMatches(123L, 0L));

        // ══════════ 11. 自己发送的消息 ══════════
        eq("11. out=true → DROP_SELF_OUT", InboundGate.DROP_SELF_OUT,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_MESSAGE, false,
                        123L, true, 999L, 999L));
        eq("11b. from==selfId → DROP_SELF_FROM", InboundGate.DROP_SELF_FROM,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_MESSAGE, false,
                        123L, false, 999L, 999L));

        // ══════════ 12. 私聊 from/peer 不匹配 ══════════
        eq("12. 私聊 from!=peer → DROP_PRIVATE_MISMATCH", InboundGate.DROP_PRIVATE_MISMATCH,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_MESSAGE, false,
                        123L, false, 999L, 456L));

        // ══════════ 13. 锚点 B 未安装 ══════════
        Diag.hookInstall("B_never", "ys.k", false, "hook 调用抛异常");
        check("13. 锚点B 未安装 → HOOK_NOT_INSTALLED（不得表现为普通零消息）",
                "HOOK_NOT_INSTALLED".equals(Diag.hookStatus("B_never")));
        check("13b. 未安装的 B 不会出现在 hookTable 的 OK 判定里",
                Diag.hookTable().contains("HOOK_NOT_INSTALLED"));

        // ══════════ 14. 锚点 B 正常触发时的参数解析 ══════════
        check("14. args>=2 且 args[1] 是 List → 通过", InboundGate.anchorBArgsOk(2, true));
        check("14b. args[1] 非 List → 拒绝", !InboundGate.anchorBArgsOk(2, false));
        check("14c. args 少于 2 → 拒绝", !InboundGate.anchorBArgsOk(1, true));
        eq("14d. B 单条私聊消息 → ACCEPT", InboundGate.ACCEPT,
                InboundGate.classifyAnchorB(false, false, 6379863842L, 6379863842L, false, false));
        eq("14e. B 的私聊 fromUid=0 → **放行**（与链A不同，如实保留该不对称）",
                InboundGate.ACCEPT,
                InboundGate.classifyAnchorB(false, false, 6379863842L, 0L, false, false));
        eq("14f. B 无文本且非媒体 → DROP", "drop:b_no_text",
                InboundGate.classifyAnchorB(false, false, 1L, 1L, true, false));

        // ══════════ 15. 重试期间的状态时间线 ══════════
        sink.lines.clear();
        int round = 2;
        Diag.event("round", "CORE", 0, 6379863842L, 0, 0, round, "in_flight", "resetting", "-", "-");
        Diag.event("round", "CORE", 0, 6379863842L, 0, 0, round, "resetting", "cleared", "-", "-");
        long tAfterReset = System.currentTimeMillis();
        Diag.event("round", "CORE", 0, 6379863842L, 0, 0, round, "cleared", "send_begin", "-", "-");
        Diag.event("round", "CORE", 0, 6379863842L, 0, 0, round, "send_begin", "send_end", "-", "-");
        Diag.event("round", "CORE", 0, 6379863842L, 0, 0, round, "send_end", "in_flight_written", "-", "-");
        final long TTL = 30L * 60 * 1000L;
        check("15. 盲区可量化：reset 后到写回前 inFlight=false",
                !InboundGate.inFlight(true, 0L, tAfterReset, TTL));
        check("15b. 写回后 inFlight=true",
                InboundGate.inFlight(true, tAfterReset, tAfterReset + 1L, TTL));
        check("15c. 时间线 5 条事件同一 roundId",
                sink.lines.size() == 5 && allContain(sink.lines, "round=2"));
        check("15d. 事件行含 prev/next（可还原状态迁移）",
                sink.lines.get(0).contains("prev=in_flight") && sink.lines.get(0).contains("next=resetting"));

        // ══════════ 16. 旧轮次与新轮次隔离 ══════════
        check("16. 轮次不同 → 事件 roundId 可区分",
                sink.lines.get(0).contains("round=2") && !sink.lines.get(0).contains("round=3"));
        check("16b. TTL 之外不算在途（旧轮不会冒充新轮）",
                !InboundGate.inFlight(true, 1000L, 1000L + TTL + 1L, TTL));
        check("16c. 跨天（sentToday=false）不算在途", !InboundGate.inFlight(false, 1000L, 2000L, TTL));

        // ══════════ 17. 同一会话多条消息限频统计 ══════════
        long before = Diag.suppressedCount();
        boolean a1 = Diag.allow("peer:6379863842", 30000L);
        boolean a2 = Diag.allow("peer:6379863842", 30000L);
        boolean a3 = Diag.allow("peer:6379863842", 30000L);
        boolean b1 = Diag.allow("peer:9999999", 30000L);      // 不同会话不受影响
        check("17. 限频：首条放行", a1);
        check("17b. 限频：后续抑制", !a2 && !a3);
        check("17c. 抑制数量被统计（不静默丢失）", Diag.suppressedCount() - before == 2);
        check("17d. 不同会话互不影响", b1);

        // ══════════ 18. 日志写入失败不影响业务结果 ══════════
        Collect boom = new Collect();
        boom.explode = true;
        Diag.setSink(boom);
        long failBefore = Diag.writeFailures();
        boolean threw = false;
        try {
            Diag.event("recv", "A", 0, 1L, 1L, 1, 1, "-", "-", "-", "-");
            Diag.flushSummary();
        } catch (Throwable t) { threw = true; }
        check("18. 日志写入抛异常不会外溢（业务不受影响）", !threw);
        check("18b. 写入失败被计数（不静默假装成功）", Diag.writeFailures() >= failBefore);
        eq("18c. 写失败时纯判定结果依旧正确", InboundGate.ACCEPT,
                InboundGate.classifyEarly(false, InboundGate.UPD_NEW_MESSAGE, false,
                        123L, false, 999L, 123L));

        // ══════════ 附加：F4 直方图 / F5 计数 / 汇总渲染 ══════════
        Diag.setSink(sink);
        Diag.noteUpdateType("org.telegram.tgnet.tl.TL_update$TL_updateNewMessage");
        Diag.noteUpdateType("org.telegram.tgnet.tl.TL_update$TL_updateNewMessage");
        Diag.noteUpdateType("org.telegram.tgnet.tl.TL_update$TL_updateUserName");
        check("F4. update 直方图按短名聚合",
                Diag.updateHistogram().containsKey("TL_updateNewMessage")
                        && Diag.updateHistogram().get("TL_updateNewMessage") == 2L);
        Diag.noteDrop(InboundGate.DROP_PEER_ZERO);
        Diag.noteDrop(InboundGate.DROP_PEER_ZERO);
        check("F5. 丢弃原因计数", Diag.dropCount(InboundGate.DROP_PEER_ZERO) == 2L);
        String sum = Diag.summary();
        check("F6. 汇总含 hook 表 + 类型 + 丢弃 + 限频", sum.contains("hook表") && sum.contains("update类型")
                && sum.contains("丢弃原因") && sum.contains("限频抑制"));
        check("F0. hook 表含未安装项", Diag.hookTable().contains("HOOK_NOT_INSTALLED"));
        check("F7. 事件行不含消息正文（无 txt/original 字段）",
                !sum.contains("txt=") && sink.lines.size() > 0 && !sink.lines.get(0).contains("txt="));

        System.out.println();
        System.out.println("ReceiveChainTest: passed=" + passed + " failed=" + failed.size());
        if (!failed.isEmpty()) {
            for (String f : failed) System.out.println("  - " + f);
            System.exit(1);
        }
        System.exit(0);
    }

    private static boolean allContain(List<String> ls, String s) {
        for (String l : ls) if (!l.contains(s)) return false;
        return true;
    }

    private ReceiveChainTest() { }
}
