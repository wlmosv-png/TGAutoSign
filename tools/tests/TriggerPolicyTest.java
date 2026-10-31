package io.github.wlmosv_png.tgautosign;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动签到触发策略单测（2026-10-30 新增，事故驱动）。
 *
 * 背景（用户反馈）：「怎么能让它我上线它就签到？定时也不行，我只能打开面板手动一键签。」
 * 排查结论：非定时模式下「打开 TG 时补签」走 kickSchedule() 空转并绕过排队入口；
 * 定时模式下 trySignAllFor 静默早退；窗口外一律丢弃且不落盘。
 *
 * 本测试分两组：
 *   A 组 —— 现有纯函数（inWindowAny / inMissBackTime / decideSign / 归类）。
 *          现在就能跑，作为回归保护网，当前应全绿。
 *   B 组 —— 目标 API `SignLogic.decideTrigger(SignLogic.TriggerCtx)` 的四态策略。
 *          用**反射**调用：API 尚未实现时逐条记为失败并继续跑，
 *          所以本文件在修复前 **编译通过、运行失败** —— 这正是「先写失败测试」。
 *          修复落地后无需改动本文件即自动转绿（常量值由实现决定，测试只比对常量本身）。
 *
 * 计数纪律（2026-10-30 审核要求）：**每条断言都必须无条件执行**。
 *   早期版本把交叉回判断言包在 `if (a != null && b != null)` 里，ctx 创建失败时
 *   整块被静默跳过、从未计入失败 —— 那等于"跳过失败项"。现已改为一律如实计数。
 *
 * 纯 javac + java，无 JUnit / 无 Android 依赖。退出码 0 = 全过。
 */
public final class TriggerPolicyTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    // 用户设备上的真实配置（acc0 / acc1 / acc3）
    private static final int[] W_0635_1937 = SignLogic.windowRangeAny("06:35-19:37");
    private static final int[] W_0535_0537 = SignLogic.windowRangeAny("05:35-05:37");
    private static final int[] W_0535_1937 = SignLogic.windowRangeAny("05:35-19:37");
    private static final int[] W_CROSS = SignLogic.windowRangeAny("22:00-02:00");

    public static void main(String[] args) {
        // ── A 组：现有纯函数（当前应全绿）──
        enqueueLogThrottle();      // P2：合并日志限频（2026-10-30 新增 5 条）
        windowBoundaries();
        deadZoneRealConfigs();
        unknownResultProtection();
        manualNotRelaxedHardGates();

        // ── B 组：目标 API decideTrigger 四态（修复前应全红）──
        // ── C 组：时刻表门槛 + 目标维度补签起点（2026-11-01 事故驱动）──
        planGateC20261101();
        missBackTargetStart20261101();
        realLogFixtures20261101();
        signedFlipProtection20261101();
        duplicateReminder20261101();
        p0ClauseAndNegation20261101();
        p2ExhaustedAndLoose20261101();
        resultCodeRoundTrip20261101();

        fourStates();
        multiAccountIsolation();
        startupCatchup();
        cycleDedupe();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) {
            System.out.println("FAILED");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }

    // ═══════════════ A 组：P2 合并日志限频（2026-10-30） ═══════════════
    //
    // 事故：原 enqueueTry 有 queueLogAt 60 秒限频，改走 markEnqueueFor 后丢了限频，
    // 而它现在被所有 RUN_NOW 触发源调用（含每次打开 TG 的 onResume）。
    // 限频只作用于「[排队] … 合并」这一条日志，不涉及发送/失败/成功证据。

    private static void enqueueLogThrottle() {
        final long W = SignLogic.ENQUEUE_LOG_WINDOW_MS;
        eq("P2·窗口常量=60秒", Long.valueOf(W), Long.valueOf(60000L));
        tru("P2·从未记录过 → 允许", SignLogic.enqueueLogAllowed(0L, 1_000_000L, W));
        tru("P2·1 秒前记过 → 抑制", !SignLogic.enqueueLogAllowed(1_000_000L, 1_001_000L, W));
        tru("P2·59 秒前记过 → 抑制", !SignLogic.enqueueLogAllowed(1_000_000L, 1_059_000L, W));
        tru("P2·恰好 60 秒 → 允许", SignLogic.enqueueLogAllowed(1_000_000L, 1_060_000L, W));
        tru("P2·超过 60 秒 → 允许", SignLogic.enqueueLogAllowed(1_000_000L, 1_200_000L, W));
        tru("P2·窗口 0 = 不限频（不得把 0 当抑制）", SignLogic.enqueueLogAllowed(500L, 501L, 0L));
        tru("P2·负数时间戳按未记录处理", SignLogic.enqueueLogAllowed(-1L, 501L, W));
    }

    // ═══════════════ A 组：窗口 / 补签时段边界（现有 API） ═══════════════

    private static void windowBoundaries() {
        // 端点闭合：06:35 与 19:37 都算窗口内
        tru("窗口·起点 06:35 在内", SignLogic.inWindowAny(395, W_0635_1937));
        tru("窗口·终点 19:37 在内", SignLogic.inWindowAny(1177, W_0635_1937));
        tru("窗口·06:34 在外", !SignLogic.inWindowAny(394, W_0635_1937));
        tru("窗口·19:38 在外", !SignLogic.inWindowAny(1178, W_0635_1937));
        tru("窗口·空窗口=不限", SignLogic.inWindowAny(3 * 60, null));
        tru("窗口·跨天 01:00 在内", SignLogic.inWindowAny(60, W_CROSS));
        tru("窗口·跨天 12:00 在外", !SignLogic.inWindowAny(720, W_CROSS));

        // 补签时段 = [窗口开始, 截止]；窗口开始之前**不算错过**
        tru("补签·截止 21:00 在内", SignLogic.inMissBackTime(1260, W_0635_1937, 1260, true));
        tru("补签·21:01 已过", !SignLogic.inMissBackTime(1261, W_0635_1937, 1260, true));
        tru("补签·窗口开始前 03:32 不算错过", !SignLogic.inMissBackTime(212, W_0635_1937, 1260, true));
        tru("补签·开关关=不补", !SignLogic.inMissBackTime(1200, W_0635_1937, 1260, false));
        tru("补签·窗口 null 全天可补", SignLogic.inMissBackTime(720, null, 1260, true));
    }

    /** 用户三档真实配置下的「死区」断言 —— 与日志里两次手动签到时间吻合。 */
    private static void deadZoneRealConfigs() {
        // acc0：06:35-19:37 / 截止 21:00 → 死区 21:00~06:34
        tru("死区·acc0 03:32 既非窗口也非补签",
                !SignLogic.inWindowAny(212, W_0635_1937)
                        && !SignLogic.inMissBackTime(212, W_0635_1937, 1260, true));
        tru("死区·acc0 20:02 属补签",
                !SignLogic.inWindowAny(1202, W_0635_1937)
                        && SignLogic.inMissBackTime(1202, W_0635_1937, 1260, true));
        tru("死区·acc0 21:30 死区",
                !SignLogic.inWindowAny(1290, W_0635_1937)
                        && !SignLogic.inMissBackTime(1290, W_0635_1937, 1260, true));

        // acc1：05:35-05:37 / 截止 08:00 → 除 2 分钟窗口外几乎全天死区
        tru("死区·acc1 10:00 死区",
                !SignLogic.inWindowAny(600, W_0535_0537)
                        && !SignLogic.inMissBackTime(600, W_0535_0537, 480, true));
        tru("死区·acc1 05:36 窗口内", SignLogic.inWindowAny(336, W_0535_0537));

        // acc3：05:35-19:37 / 截止 21:00 → 死区 21:00~05:34（10-30 00:46 手动那次的时刻）
        tru("死区·acc3 00:46 死区",
                !SignLogic.inWindowAny(46, W_0535_1937)
                        && !SignLogic.inMissBackTime(46, W_0535_1937, 1260, true));
        tru("死区·acc3 20:30 属补签",
                SignLogic.inMissBackTime(1230, W_0535_1937, 1260, true));
    }

    // ═══════════════ A 组：结果未知保护 / 手动不豁免硬闸 ═══════════════

    private static void unknownResultProtection() {
        // 「已发出、等待回复」→ 跳过（不得重发、不得当成功）
        SignLogic.SignGate g = new SignLogic.SignGate();
        g.sentPendingFresh = true;
        eq("未知·已发出待结论 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_SENT_PENDING);

        // 「待确认」（bot 未回复 / 判不出）→ 跳过，等用户处置
        g = new SignLogic.SignGate();
        g.pendingUnconfirmed = true;
        eq("未知·待确认 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_PENDING_CONFIRM);

        // 在途：manual 也不放行（防并发双发）
        g = new SignLogic.SignGate();
        g.manual = true;
        g.inFlight = true;
        eq("未知·在途+manual → 仍跳过", SignLogic.decideSign(g), SignLogic.SKIP_IN_FLIGHT);

        // 「结果未知」不得计入已签 —— 归类语义
        eq("未知·R_NO_REPLY 不计成功",
                Boolean.valueOf(SignLogic.countsAsSigned(SignLogic.R_NO_REPLY)), Boolean.FALSE);
        eq("未知·R_REPLIED_UNK 不计成功",
                Boolean.valueOf(SignLogic.countsAsSigned(SignLogic.R_REPLIED_UNK)), Boolean.FALSE);
        eq("未知·R_SIGNED 计成功",
                Boolean.valueOf(SignLogic.countsAsSigned(SignLogic.R_SIGNED)), Boolean.TRUE);
    }

    private static void manualNotRelaxedHardGates() {
        // manual 豁免「已签 / 已发出待结论」这类进度状态
        SignLogic.SignGate g = new SignLogic.SignGate();
        g.manual = true;
        g.signedToday = true;
        g.sentPendingFresh = true;
        eq("手动·豁免已签/已发出", SignLogic.decideSign(g), SignLogic.SKIP_NONE);

        // 但**不豁免**「别再打了」类硬闸
        g = new SignLogic.SignGate();
        g.manual = true;
        g.sendAttemptsExhausted = true;
        eq("手动·不豁免发送上限", SignLogic.decideSign(g), SignLogic.SKIP_SEND_ATTEMPTS_EXHAUST);

        g = new SignLogic.SignGate();
        g.manual = true;
        g.retryExhausted = true;
        eq("手动·不豁免重试上限", SignLogic.decideSign(g), SignLogic.SKIP_RETRY_EXHAUST);

        g = new SignLogic.SignGate();
        g.manual = true;
        g.inBackoff = true;
        eq("手动·不豁免退避", SignLogic.decideSign(g), SignLogic.SKIP_BACKOFF);

        g = new SignLogic.SignGate();
        g.manual = true;
        g.accountDisabled = true;
        eq("手动·账号停用也拦", SignLogic.decideSign(g), SignLogic.SKIP_ACCOUNT_DISABLED);
    }

    // ═══════════════ B 组：目标 API decideTrigger（修复前全红） ═══════════════
    // 说明：以下每条断言都无条件执行；ctx 为 null 时 tg() 会如实记失败。

    private static void fourStates() {
        Object c;

        c = ctx("四态·窗口内");
        setF(c, "timerEnabled", Boolean.TRUE);
        setF(c, "nowMin", Integer.valueOf(600));            // 10:00
        setF(c, "windowAny", W_0635_1937);
        tg("四态·窗口内 → RUN_NOW", c, "T_RUN_NOW");

        c = ctx("四态·窗口后补签");
        setF(c, "timerEnabled", Boolean.TRUE);
        setF(c, "missBackOn", Boolean.TRUE);
        setF(c, "nowMin", Integer.valueOf(1202));           // 20:02
        setF(c, "windowAny", W_0635_1937);
        setF(c, "missDeadlineMin", Integer.valueOf(1260));
        tg("四态·窗口后补签 → RUN_LATE", c, "T_RUN_LATE");

        c = ctx("四态·超截止");
        setF(c, "timerEnabled", Boolean.TRUE);
        setF(c, "missBackOn", Boolean.TRUE);
        setF(c, "nowMin", Integer.valueOf(1290));           // 21:30
        setF(c, "windowAny", W_0635_1937);
        setF(c, "missDeadlineMin", Integer.valueOf(1260));
        tg("四态·超截止 → QUEUE", c, "T_QUEUE");

        c = ctx("四态·窗口前-定时开");
        setF(c, "timerEnabled", Boolean.TRUE);
        setF(c, "nowMin", Integer.valueOf(212));            // 03:32
        setF(c, "windowAny", W_0535_1937);
        tg("四态·窗口前(定时开) → QUEUE", c, "T_QUEUE");

        // ★ RC-2 验收：定时关闭时任何时间上线都应尝试
        c = ctx("四态·窗口前-定时关");
        setF(c, "timerEnabled", Boolean.FALSE);
        setF(c, "nowMin", Integer.valueOf(212));
        setF(c, "windowAny", W_0535_1937);
        tg("四态·窗口前(定时关) → RUN_NOW", c, "T_RUN_NOW");

        c = ctx("四态·空窗口");
        setF(c, "timerEnabled", Boolean.TRUE);
        setF(c, "nowMin", Integer.valueOf(212));
        setF(c, "windowAny", null);
        tg("四态·空窗口 → RUN_NOW", c, "T_RUN_NOW");

        c = ctx("四态·跨天");
        setF(c, "timerEnabled", Boolean.TRUE);
        setF(c, "nowMin", Integer.valueOf(60));             // 01:00
        setF(c, "windowAny", W_CROSS);
        tg("四态·跨天窗口内 → RUN_NOW", c, "T_RUN_NOW");

        // 冷却只在**窗口外**生效；窗口内到点不受冷却限制（设计 §3.2 前置优先级 2）
        // 故此处必须用窗口外时刻，否则本用例会在窗口内被 RUN_NOW，失去判别力。
        c = ctx("四态·冷却未到");
        setF(c, "timerEnabled", Boolean.FALSE);
        setF(c, "nowMin", Integer.valueOf(212));            // 03:32 窗口外，本应 RUN_NOW
        setF(c, "sinceLastAutoCheckMs", Long.valueOf(3L * 60 * 1000));
        setF(c, "autoCooldownMs", Long.valueOf(10L * 60 * 1000));
        tg("四态·冷却未到(窗口外) → SKIP", c, "T_SKIP");

        c = ctx("四态·无待办");
        setF(c, "pendingToday", Boolean.FALSE);
        tg("四态·本周期无事可做 → SKIP", c, "T_SKIP");
    }

    private static void multiAccountIsolation() {
        // 同一时刻，两个账号配置不同：判定必须各自独立（RC-6 / RC-7）
        Object a = ctx("隔离·账号A");
        Object b = ctx("隔离·账号B");

        setF(a, "timerEnabled", Boolean.TRUE);
        setF(a, "nowMin", Integer.valueOf(212));
        setF(a, "windowAny", W_0635_1937);      // 定时开 + 窗口开始前 → QUEUE
        tg("隔离·账号A(定时开,窗口前) → QUEUE", a, "T_QUEUE");

        setF(b, "timerEnabled", Boolean.FALSE);
        setF(b, "nowMin", Integer.valueOf(212));
        setF(b, "windowAny", W_0535_1937);      // 定时关 → 应执行
        tg("隔离·账号B(定时关) → RUN_NOW", b, "T_RUN_NOW");

        // 交叉再判两次，确认判定只依赖入参、不受前一次调用影响
        tg("隔离·回判账号A 仍 QUEUE", a, "T_QUEUE");
        tg("隔离·回判账号B 仍 RUN_NOW", b, "T_RUN_NOW");
    }

    private static void startupCatchup() {
        // ★ RC-1 验收：非定时 + 「打开 TG 时补签」开 + 启动触发 → 必须真执行
        Object c = ctx("启动补签·非定时");
        setF(c, "timerEnabled", Boolean.FALSE);
        setF(c, "catchupOnOpen", Boolean.TRUE);
        setF(c, "startupTrigger", Boolean.TRUE);
        setF(c, "nowMin", Integer.valueOf(300));            // 05:00
        setF(c, "windowAny", W_0535_1937);
        tg("启动补签·非定时窗口前 → RUN_NOW", c, "T_RUN_NOW");

        // 定时 + 窗口外：应排队，不得静默 SKIP
        c = ctx("启动补签·定时");
        setF(c, "timerEnabled", Boolean.TRUE);
        setF(c, "catchupOnOpen", Boolean.TRUE);
        setF(c, "startupTrigger", Boolean.TRUE);
        setF(c, "nowMin", Integer.valueOf(300));
        setF(c, "windowAny", W_0635_1937);
        tg("启动补签·定时窗口外 → QUEUE", c, "T_QUEUE");

        // 已有在途 / 待确认（pendingToday=false）时，打开 TG 不得重复发
        c = ctx("启动补签·已有在途");
        setF(c, "timerEnabled", Boolean.FALSE);
        setF(c, "startupTrigger", Boolean.TRUE);
        setF(c, "pendingToday", Boolean.FALSE);
        tg("启动补签·已无待办 → SKIP", c, "T_SKIP");
    }

    private static void cycleDedupe() {
        // 同一签到周期内多触发源（启动 / 打开TG / 心跳 / 网络恢复）→ 除首个外应 SKIP
        Object c = ctx("周期去重·首次");
        setF(c, "timerEnabled", Boolean.FALSE);
        setF(c, "nowMin", Integer.valueOf(600));
        setF(c, "sinceLastAutoCheckMs", Long.valueOf(60L * 60 * 1000));
        tg("周期去重·首次 → RUN_NOW", c, "T_RUN_NOW");

        // 同周期内多个触发源（启动 / 打开TG / 心跳 / 网络恢复）：
        // 首轮已在窗口内执行过 → 窗口外的后续触发受冷却合并（设计 §3.2 前置优先级 2）
        c = ctx("周期去重·二次");
        setF(c, "timerEnabled", Boolean.FALSE);
        setF(c, "nowMin", Integer.valueOf(212));            // 窗口外，避免窗口内 RUN_NOW
        setF(c, "sinceLastAutoCheckMs", Long.valueOf(30L * 1000));   // 刚查过
        tg("周期去重·二次(同周期刚查过) → SKIP", c, "T_SKIP");

        c = ctx("周期去重·结果未知");
        setF(c, "timerEnabled", Boolean.FALSE);
        setF(c, "nowMin", Integer.valueOf(600));
        setF(c, "pendingToday", Boolean.FALSE);      // 已发出待结论 → 无"待办"
        tg("周期去重·结果未知保护 → SKIP", c, "T_SKIP");
    }

    // ═══════════════ 反射辅助：目标 API 未实现时记失败并继续 ═══════════════

    private static Object ctx(String tag) {
        try {
            Class<?> cls = Class.forName("io.github.wlmosv_png.tgautosign.SignLogic$TriggerCtx");
            Object o = cls.getDeclaredConstructor().newInstance();
            // 默认：有待办、冷却已过、补签开、窗口 06:35-19:37 / 截止 21:00
            setF(o, "pendingToday", Boolean.TRUE);
            setF(o, "missBackOn", Boolean.TRUE);
            setF(o, "missDeadlineMin", Integer.valueOf(1260));
            setF(o, "windowAny", W_0635_1937);
            setF(o, "autoCooldownMs", Long.valueOf(10L * 60 * 1000));
            setF(o, "sinceLastAutoCheckMs", Long.valueOf(60L * 60 * 1000));
            return o;
        } catch (Throwable t) {
            // 不在此处记失败：由随后的 tg() 如实记一条「前置 ctx 创建失败」，
            // 避免同一根因被双计、把断言数与失败数搅乱。
            lastCtxError = t.getClass().getSimpleName();
            return null;
        }
    }

    private static String lastCtxError = "unknown";

    private static void setF(Object ctx, String field, Object val) {
        if (ctx == null) return;      // ctx 缺失已在 ctx() 里记过失败，避免二次误报
        try {
            Field f = ctx.getClass().getField(field);
            f.set(ctx, val);
        } catch (Throwable t) {
            failed.add("TriggerCtx 字段 " + field + " 缺失（契约不完整）");
        }
    }

    /**
     * 调用 decideTrigger 并与实现里的常量比对（常量值由实现决定，测试不硬编码数字）。
     * ctx 为 null 时也**如实记失败** —— 不得静默跳过断言。
     */
    private static void tg(String tag, Object ctx, String expectConst) {
        if (ctx == null) {
            failed.add(tag + " → 前置 ctx 创建失败（TriggerCtx API 未实现：" + lastCtxError + "）");
            return;
        }
        try {
            Method m = SignLogic.class.getMethod("decideTrigger", ctx.getClass());
            int got = ((Number) m.invoke(null, ctx)).intValue();
            int want = SignLogic.class.getField(expectConst).getInt(null);
            eq(tag, Integer.valueOf(got), Integer.valueOf(want));
        } catch (Throwable t) {
            failed.add(tag + " → decideTrigger API 未实现（" + t.getClass().getSimpleName() + "）");
        }
    }

    // ═══════════ C 组：时刻表门槛（2026-11-01） ═══════════
    //
    // 事故（用户 Gheoix 实测）：全局窗口 00:00-23:59 + 单目标固定 08:00，
    // 0 点打开 TG 就把该目标签掉了 —— 定时形同虚设。
    // 根因：decideTrigger 里「窗口内 → RUN_NOW」排在时刻表之前，窗口全天为真时恒命中。
    // 修复：定时开启且已知当日最早计划时刻时，未到点一律 T_QUEUE。
    //
    // 这两组是**真阳性用例**：对修复前的源码必须失败，否则等于没测。

    private static final int[] W_ALLDAY = SignLogic.windowRangeAny("00:00-23:59");

    private static void planGateC20261101() {
        // ① 定时开 + 窗口全天 + 最早计划 08:00 + 现在 00:05 → 必须排队，不得签
        Object c1 = ctx("planGate1");
        setF(c1, "timerEnabled", Boolean.TRUE);
        setF(c1, "windowAny", W_ALLDAY);
        setF(c1, "earliestPlanMin", Integer.valueOf(480));
        setF(c1, "nowMin", Integer.valueOf(5));
        tg("C1·窗口全天+计划08:00+现在00:05 → 排队不签", c1, "T_QUEUE");

        // ② 到点（08:00）→ 放行
        Object c2 = ctx("planGate2");
        setF(c2, "timerEnabled", Boolean.TRUE);
        setF(c2, "windowAny", W_ALLDAY);
        setF(c2, "earliestPlanMin", Integer.valueOf(480));
        setF(c2, "nowMin", Integer.valueOf(480));
        tg("C2·到点 08:00 → 立即执行", c2, "T_RUN_NOW");

        // ③ 已过点（15:00）→ 立即执行
        Object c3 = ctx("planGate3");
        setF(c3, "timerEnabled", Boolean.TRUE);
        setF(c3, "windowAny", W_ALLDAY);
        setF(c3, "earliestPlanMin", Integer.valueOf(480));
        setF(c3, "nowMin", Integer.valueOf(900));
        tg("C3·过点 15:00 → 立即执行", c3, "T_RUN_NOW");

        // ④ 定时关 + **有目标设了固定时刻** → 也要排队（2026-11-01 v2 修正）
        //
        // 为什么改：用户 Gheoix 的日志写着「定时=关」，但某个目标设了 08:00。
        // 目标级固定时刻是用户对单个目标的显式约定，不该被账号级开关否掉。
        // v1 修复要求 timerEnabled=true —— 覆盖不到他，这是实测发现的漏网场景。
        Object c4 = ctx("planGate4");
        setF(c4, "timerEnabled", Boolean.FALSE);
        setF(c4, "windowAny", W_ALLDAY);
        setF(c4, "earliestPlanMin", Integer.valueOf(480));
        setF(c4, "nowMin", Integer.valueOf(5));
        tg("C4·定时关但有固定时刻目标 → 未到点仍排队", c4, "T_QUEUE");

        // ④b 定时关 + **无任何固定时刻目标**(-1) → 上线即签（旧语义，必须保住）
        Object c4b = ctx("planGate4b");
        setF(c4b, "timerEnabled", Boolean.FALSE);
        setF(c4b, "windowAny", W_ALLDAY);
        setF(c4b, "earliestPlanMin", Integer.valueOf(-1));
        setF(c4b, "nowMin", Integer.valueOf(5));
        tg("C4b·定时关且无固定时刻 → 立即执行（旧语义不变）", c4b, "T_RUN_NOW");

        // ④c 定时关 + 已过固定时刻 → 立即执行
        Object c4c = ctx("planGate4c");
        setF(c4c, "timerEnabled", Boolean.FALSE);
        setF(c4c, "windowAny", W_ALLDAY);
        setF(c4c, "earliestPlanMin", Integer.valueOf(480));
        setF(c4c, "nowMin", Integer.valueOf(600));
        tg("C4c·定时关且已过点 → 立即执行", c4c, "T_RUN_NOW");

        // ⑤ 取不到计划（earliestPlanMin=-1）→ 退化为旧行为，不得误锁
        Object c5 = ctx("planGate5");
        setF(c5, "timerEnabled", Boolean.TRUE);
        setF(c5, "windowAny", W_ALLDAY);
        setF(c5, "earliestPlanMin", Integer.valueOf(-1));
        setF(c5, "nowMin", Integer.valueOf(5));
        tg("C5·无计划(-1) → 退化旧行为，立刻执行", c5, "T_RUN_NOW");

        // ⑥ 冷却未到：窗口外又不该动的场景下仍要 T_SKIP（不能被排队逻辑吃掉冷却）
        Object c6 = ctx("planGate6");
        setF(c6, "timerEnabled", Boolean.TRUE);
        setF(c6, "windowAny", W_ALLDAY);
        setF(c6, "earliestPlanMin", Integer.valueOf(480));
        setF(c6, "nowMin", Integer.valueOf(5));
        setF(c6, "sinceLastAutoCheckMs", Long.valueOf(1000L));   // 1 秒前刚查过
        tg("C6·未到点且冷却内 → T_SKIP（不刷排队记录）", c6, "T_SKIP");
    }

    // ═══════════ C 组：目标维度补签起点（2026-11-01） ═══════════
    //
    // 旧签名只接受账号窗口起点。窗口 00:00-23:59 时，05:00 就被判成"补签时段内"，
    // 于是 08:00 的目标会被提前签掉。用户诉求：8:00 的目标，可补区间 = 08:00-23:59。

    private static void missBackTargetStart20261101() {
        // ⑦ 窗口全天 + 目标 480 + 现在 30 → 不在补签时段
        tru("C7·全天文窗口·目标08:00·现在00:30 → 不可补",
                !SignLogic.inMissBackTime(30, W_ALLDAY, 1439, true, 480));

        // ⑧ 同上，到点后 600 → 可补
        tru("C8·全天文窗口·目标08:00·现在10:00 → 可补",
                SignLogic.inMissBackTime(600, W_ALLDAY, 1439, true, 480));

        // ⑨ 无目标计划（-1）→ 与旧签名完全一致（窗口起点 0:00，全天可补）
        tru("C9·无目标计划(-1) → 旧行为：00:30 可补",
                SignLogic.inMissBackTime(30, W_ALLDAY, 1439, true, -1));

        // ⑩ 旧四参签名必须与新签名(-1)等价 —— 保证既有调用点不被破坏
        boolean old4 = SignLogic.inMissBackTime(600, W_0635_1937, 1260, true);
        boolean new5 = SignLogic.inMissBackTime(600, W_0635_1937, 1260, true, -1);
        eq("C10·四参签名 ≡ 五参(-1)", Boolean.valueOf(old4), Boolean.valueOf(new5));

        // ⑪ 目标时刻早于窗口起点 → 起点仍取窗口起点（只抬不降）
        //    窗口 06:35 起，目标 05:00，现在 05:30 → 不可补（还没到窗口起点）
        tru("C11·目标早于窗口起点 → 起点不下降",
                !SignLogic.inMissBackTime(330, W_0635_1937, 1260, true, 300));

        // ⑫ 跨天窗口 + 目标在次日的时段 → 不得把起点抬到第二天，导致当天失效
        //    窗口 22:00-02:00，目标 00:30(30) 属次日段，现在 23:00(1380) → 可补
        tru("C12·跨天窗口·目标落在次日段 → 起点不被误抬",
                SignLogic.inMissBackTime(1380, W_CROSS, 1439, true, 30));

        // ⑬ 补签开关关 → 无论目标时刻如何都不可补
        tru("C13·补签关 → 恒 false",
                !SignLogic.inMissBackTime(600, W_ALLDAY, 1439, false, 480));
    }

    // ═══════════ D 组：真实日志原文 fixture（2026-11-01） ═══════════
    //
    // 素材来源：用户 Gheoix 导出的日志
    //   /storage/emulated/0/Android/data/org.telegram.messenger/files/Telegram/
    //   Telegram Documents/5_6283034526910259847.txt
    // 这条链上真实发生的事故（原文照抄，不改一字）：
    //   00:05:14 面板公告含「已过期」→ 判 V_FAILED → 撤销已签 + 排重试
    //   00:05:16 真回复「📅 签到成功」→ 记「计入已签（0 条）」（目标已被清）
    //   00:10:05 「📅 今天已经签到过了」→ 记「计入已签（0 条）」
    //   00:10:00 「今天已经签过了，明天再来。」→ 记「计入已签（1 条）」
    // 也就是说：**真成功判失败，重复签到反而算成功**。
    //
    // 这些用例是修复前必红的真阳性。

    private static final String F_PANEL =
            "🏠 乐呵乐呵 私聊控制台  👤 当前绑定：quanwuhui 🎬 Emby 账号：quanwuhui 👑 会员状态：已过期";
    private static final String F_REAL_OK =
            "📅 签到成功  🎁 本次获得：+10 爆米花 🍿 当前爆米花：130 🔥 连续签到：11 天";
    private static final String F_DUP =
            "📅 今天已经签到过了  🎁 本次获得：无额外奖励 🍿 当前爆米花：130 🔥 连续签到：11";
    private static final String F_DUP2 = "今天已经签过了，明天再来。 😑";
    private static final String F_EMBY_PANEL =
            "👋 欢迎回来，quanwuhui！  🎬 EmbyPulse 用户自助服务 请选择你需要的服务：";

    private static int verdictOf(String reply) {
        try {
            String[] ok = SignLogic.OK_WORDS_DEFAULT;
            String[] fail = SignLogic.FAIL_WORDS_DEFAULT;
            Object[] vd = SignLogic.verdictDetailScoped(reply, ok, fail, null, null, null, null);
            return ((Integer) vd[0]).intValue();
        } catch (Throwable t) { return -999; }
    }

    private static void realLogFixtures20261101() {
        // ⓐ 面板公告含「已过期」→ 绝不能判失败（这是本次事故的入口）
        int vPanel = verdictOf(F_PANEL);
        tru("D1·面板公告含『已过期』不得判 V_FAILED（实得 " + vPanel + "）",
                vPanel != SignLogic.V_FAILED);

        // ⓑ 真成功回复必须判成功
        eq("D2·真回复『📅 签到成功』→ V_SIGNED",
                Integer.valueOf(verdictOf(F_REAL_OK)), Integer.valueOf(SignLogic.V_SIGNED));

        // ⓒ 「今天已经签到过了」是成功（今天确实签过了）
        int vDup = verdictOf(F_DUP);
        tru("D3·『今天已经签到过了』不是失败（实得 " + vDup + "）",
                vDup != SignLogic.V_FAILED);

        // ⓓ 同上，另一版措辞
        int vDup2 = verdictOf(F_DUP2);
        tru("D4·『今天已经签过了，明天再来』不是失败（实得 " + vDup2 + "）",
                vDup2 != SignLogic.V_FAILED);

        // ⓔ Emby 面板（欢迎回来 + 请选择）→ 不得判失败
        int vEmby = verdictOf(F_EMBY_PANEL);
        tru("D5·Emby 欢迎面板不得判 V_FAILED（实得 " + vEmby + "）",
                vEmby != SignLogic.V_FAILED);

        // ⓕ 反向保护：真的失败句必须仍判失败，不能被守卫吞掉
        int vRealFail = verdictOf("❌ 签到失败，请明天再来");
        eq("D6·真失败句仍判 V_FAILED（守卫不得过度放行）",
                Integer.valueOf(vRealFail), Integer.valueOf(SignLogic.V_FAILED));

        // ⓖ 反向保护：「您不是群组管理员，无法进行此操作」这类拒绝仍是失败
        int vReject = verdictOf("您不是群组管理员，无法进行此操作。");
        tru("D7·管理员拒绝仍判失败或未知（实得 " + vReject + "），不得判成功",
                vReject != SignLogic.V_SIGNED);

        // ⓗ isPanelLike 直测
        tru("D8·isPanelLike 认面板公告", SignLogic.isPanelLike(F_PANEL.replace(" ", "")));
        tru("D9·isPanelLike 不认真结果",
                !SignLogic.isPanelLike(F_REAL_OK.replace(" ", "")));
    }

    // ═══════════ E 组：已签保护闸（2026-11-01） ═══════════
    //
    // 通用逻辑缺口：多消息 bot 一次签到连发多条，后面那条会把成功结论翻转。
    // 真实案例 bot 6379863842（「短信轰炸机」）：
    //   01:28:45 「签到成功！」→ 判成功
    //   01:28:45 「恭喜您获取到扩展任务…」→ 走另一路径 → 结论被撤 → 界面要用户手动标记
    // 规则：当天已签 → 自动判定只读不写。

    private static void signedFlipProtection20261101() {
        String today = "2026-11-01";

        tru("E1·今天已签 → 拦截自动翻转",
                SignLogic.shouldProtectSigned(today, today));
        tru("E2·昨天签的 → 不拦（新的一天该重新签）",
                !SignLogic.shouldProtectSigned("2026-10-31", today));
        tru("E3·从未签过（空值）→ 不拦",
                !SignLogic.shouldProtectSigned("", today));
        tru("E4·null → 不拦（防 NPE）",
                !SignLogic.shouldProtectSigned(null, today));
        tru("E5·today 为 null → 不拦（防 NPE）",
                !SignLogic.shouldProtectSigned(today, null));
        tru("E6·带空格的值 → 仍识别为已签",
                SignLogic.shouldProtectSigned(" 2026-11-01 ", today));
        tru("E7·脏值（非日期）→ 不拦，交给自愈清理",
                !SignLogic.shouldProtectSigned("garbage", today));
    }

    // ═══════════ F 组：命令式重复提示（2026-11-01） ═══════════
    //
    // 真实素材（用户设备 01:53，bot 6379863842）：
    //   「Clsckdu Toole /qd 请勿重复签到！ 要试试轰炸吗？您当前可以轰炸12次
    //     您可以尝试发送 /hz …」
    // 这句话意思是「你今天已经签过了」，本该直接算已签，
    // 却被两重守卫拦下判成"判不出"，用户被迫手动处理。

    private static final String F_DUP_REMIND =
            "Clsckdu Toole /qd 请勿重复签到！ 要试试轰炸吗？您当前可以轰炸12次 "
            + "您可以尝试发送 /hz 您也可以尝试 /yshz 可以体验一下延时轰炸，这你爱的人送上“祝福”。";

    private static void duplicateReminder20261101() {
        tru("F1·带推广语的『请勿重复签到』应判已签（真实素材）",
                SignLogic.isDuplicateReminder(F_DUP_REMIND));
        eq("F1b·且 verdict 为 V_SIGNED",
                Integer.valueOf(verdictOf(F_DUP_REMIND)), Integer.valueOf(SignLogic.V_SIGNED));

        tru("F2·『不要重复签到』→ 已签", SignLogic.isDuplicateReminder("不要重复签到"));
        tru("F3·『再次签到』式劝阻 → 已签",
                SignLogic.isDuplicateReminder("请勿再次签到，明天再来"));
        tru("F4·英文 already signed → 不靠本判据（词表另有覆盖）",
                !SignLogic.isDuplicateReminder("already signed today"));

        // 反向保护：不能把无关的"重复"吞进来
        tru("F5·『请勿重复提交』不判已签（无签到动作）",
                !SignLogic.isDuplicateReminder("请勿重复提交订单"));
        tru("F6·失败优先：带『失败』不判已签",
                !SignLogic.isDuplicateReminder("签到失败，请勿重复签到"));
        tru("F7·带『已过期』不判已签",
                !SignLogic.isDuplicateReminder("验证已过期，请勿重复签到"));
        tru("F8·『请先加入频道』不受影响（不是重复语）",
                !SignLogic.isDuplicateReminder("请先加入用户群，再签到"));

        // 真失败句仍判失败
        eq("F9·真失败句仍 V_FAILED",
                Integer.valueOf(verdictOf("❌ 签到失败，请明天再来")),
                Integer.valueOf(SignLogic.V_FAILED));
    }

    // ═══════════ G 组：阶段 1 · P0 安全修复（2026-11-01） ═══════════
    //
    // 基线实测的两个 P0（会把"没签到"记成"签到了"，直接导致断签）：
    //   P0-1 negated 与 matched 口径不一致 → 「未签到+1」被判已签
    //   P0-2 疑问/假设子句无守卫         → 「签到成功了吗？」被判已签
    // 反向保护同样重要：修 P0-2 不能把「今日签到成功！要试试轰炸吗？」这类
    // 真结果（结果在前半子句、疑问/推广在后半）一起丢掉。

    private static void p0ClauseAndNegation20261101() {
        // ── P0-1：带空格内置词不得绕过否定守卫 ──
        tru("G1·『未签到+1』不得判已签",
                verdictOf("未签到+1") != SignLogic.V_SIGNED);
        tru("G2·『今天未签到+1』不得判已签",
                verdictOf("今天未签到+1") != SignLogic.V_SIGNED);
        tru("G3·『未 签到 +1』不得判已签",
                verdictOf("未 签到 +1") != SignLogic.V_SIGNED);
        tru("G4·『还没有签到 +1』不得判已签",
                verdictOf("还没有签到 +1") != SignLogic.V_SIGNED);
        // 对照组：没被误伤的肯定句必须仍判已签
        eq("G5·『签到 +1』（对照）仍判已签",
                Integer.valueOf(verdictOf("签到 +1")), Integer.valueOf(SignLogic.V_SIGNED));
        // 英文侧不能被"去空白"改坏（实测曾被改坏过 2 条）
        tru("G6·英文否定句不得判已签",
                verdictOf("You haven't checked in today. Received 0 points") != SignLogic.V_SIGNED);

        // ── P0-2：疑问 / 假设子句不作证据 ──
        tru("G7·『签到成功了吗？』不得判已签",
                verdictOf("签到成功了吗？") != SignLogic.V_SIGNED);
        tru("G8·『如果签到成功，将获得奖励』不得判已签",
                verdictOf("如果签到成功，将获得奖励") != SignLogic.V_SIGNED);
        tru("G9·『是否签到成功』不得判已签",
                verdictOf("是否签到成功") != SignLogic.V_SIGNED);
        tru("G10·『若签到成功，即可获得积分』不得判已签",
                verdictOf("若签到成功，即可获得积分") != SignLogic.V_SIGNED);
        // 反向保护：真结果 + 后续疑问/推广，不能因问号被丢
        eq("G11·『今日签到成功！感谢参与！要试试轰炸吗？』仍判已签",
                Integer.valueOf(verdictOf("今日签到成功！感谢参与！要试试轰炸吗？")),
                Integer.valueOf(SignLogic.V_SIGNED));
        eq("G12·『今日已签到，感谢参与！要试试轰炸吗？』仍判已签",
                Integer.valueOf(verdictOf("今日已签到，感谢参与！要试试轰炸吗？")),
                Integer.valueOf(SignLogic.V_SIGNED));

        // ── 子句工具直测 ──
        tru("G13·疑问子句识别", SignLogic.clauseIsNonResult("签到成功了吗？"));
        tru("G14·假设子句识别", SignLogic.clauseIsNonResult("如果签到成功，将获得奖励"));
        tru("G15·结果子句不误判", !SignLogic.clauseIsNonResult("今日签到成功"));
        tru("G16·进度问句不误判为结果子句", SignLogic.clauseIsNonResult("签到成功了吗"));
    }

    // ═══════════ H 组：阶段 2 · 额度对象识别 + 入口一致性（2026-11-01） ═══════════
    //
    // 基线问题（corpus-baseline C1–C6 五例互斥 / T2/T12/T13/T18 宽松丢结论）：
    //   ① scoped 链完全没有 EXHAUSTED 检查 → 同一句在两条生产路径结论相反；
    //   ② 宽松模式传空数组致整张 DUP 表失效；
    //   ③ 抽奖/兑换等其他业务额度会干扰签到判定。
    // 用户要求：不能让其他业务次数影响签到；也不能让明确成功的签到变成未签到。

    private static int scopedRaw(String r) {
        Object[] vd = SignLogic.verdictDetailScoped(r, SignLogic.OK_WORDS_DEFAULT,
                SignLogic.FAIL_WORDS_DEFAULT, null, null, null, null);
        return ((Integer) vd[0]).intValue();
    }
    private static int detailRaw(String r) {
        Object[] vd = SignLogic.verdictDetail(r, null, null, null);
        return ((Integer) vd[0]).intValue();
    }
    /** 与生产宽松模式调用同形（dup=null 用内置表；ok=WORDS_NONE 显式禁用）。 */
    private static int looseRaw(String r) {
        Object[] vd = SignLogic.verdictDetail(r, null, SignLogic.WORDS_NONE, SignLogic.FAIL_WORDS_DEFAULT);
        return ((Integer) vd[0]).intValue();
    }
    private static void same3(String tag, String r) {
        int a = scopedRaw(r), b = detailRaw(r);
        tru(tag + "·入口一致 [" + r + "] scoped=" + a + " detail=" + b, a == b);
    }

    private static void p2ExhaustedAndLoose20261101() {
        // ── 额度对象识别：其他业务额度不得干扰签到 ──
        eq("H1·签到成功+抽奖次数用尽 → 已签（不被抽奖带偏）",
                Integer.valueOf(scopedRaw("今日签到成功，抽奖次数已用尽。")),
                Integer.valueOf(SignLogic.V_SIGNED));
        eq("H2·签到成功+兑换次数不足 → 已签",
                Integer.valueOf(scopedRaw("今日签到成功，兑换次数不足。")),
                Integer.valueOf(SignLogic.V_SIGNED));
        eq("H3·签到完成+抽奖为0 → 已签",
                Integer.valueOf(scopedRaw("签到已完成，但抽奖次数为0。")),
                Integer.valueOf(SignLogic.V_SIGNED));
        eq("H4·签到成功+抽奖次数已用完（同义） → 已签",
                Integer.valueOf(scopedRaw("今日签到成功，抽奖次数已用完")),
                Integer.valueOf(SignLogic.V_SIGNED));
        tru("H5·纯抽奖额度用尽 → 不产生签到结论",
                scopedRaw("今日抽奖次数已达上限。") == SignLogic.V_UNKNOWN);
        eq("H6·签到额度用尽 → 用尽", Integer.valueOf(scopedRaw("今日签到次数已达上限。")),
                Integer.valueOf(SignLogic.V_EXHAUSTED));

        // ── 非结论（冷却/统计）不得被成功词带偏 ──
        tru("H7·冷却句不得判成功（DUP 词『已签到』在统计语境里不算数）",
                scopedRaw("签到冷却中,请于 8 小时后再试,已签到 5 次") != SignLogic.V_SIGNED);
        eq("H8·额度用尽+请明日再试 → 用尽（不被『后再试』吞成判不出）",
                Integer.valueOf(scopedRaw("❌ 您本日的规则触发数量上限，请明日再试")),
                Integer.valueOf(SignLogic.V_EXHAUSTED));

        // ── S3：宽松模式 DUP 表恢复 ──
        String[] dupCases = {"您今天已经签到过了", "今日已签到", "今日已领取", "请勿重复签到"};
        for (String c : dupCases) {
            tru("H9·宽松模式识别已签 [" + c + "]",
                    looseRaw(c) == SignLogic.V_SIGNED);
            same3("H10", c);
        }

        // ── 入口一致性（阶段 2 核心验收）──
        String[] all = {
                "今日签到次数已达上限。", "今日签到成功，抽奖次数已用尽。",
                "今日签到成功，兑换次数不足。", "签到失败，今日尝试次数已用尽。",
                "今日奖励领取次数已达上限。", "签到已完成，但抽奖次数为0。",
                "今日抽奖次数已达上限。", "签到冷却中,请于 8 小时后再试,已签到 5 次",
                "签到成功", "签到失败", "请勿重复签到", "签到成功了吗？"
        };
        for (String c : all) same3("H11", c);
    }

    // ═══════════ I 组：归类码往返闭合（2026-11-01 阶段 2 新发现缺陷） ═══════════
    //
    // 根因：resultCode(int) 会把 R_EXHAUSTED 写成 "exhausted"，
    //   但 resultOfCode(String) 的 switch **没有 "exhausted" 分支** → 落到 default 得 -1
    //   → 读回被判成"无归类"。后果：次数用尽的目标在界面侧丢失归类。
    // 由阶段 2 持久化测试 F2b 实测抓到（写入后读回 -1，期望 R_EXHAUSTED）。
    // 不变式：两个映射必须**互为逆**，新增码必须同时加两处。

    private static void resultCodeRoundTrip20261101() {
        int[] codes = {
                SignLogic.R_SIGNED, SignLogic.R_FAILED, SignLogic.R_BTN_STALE,
                SignLogic.R_REPLIED_UNK, SignLogic.R_NO_REPLY, SignLogic.R_JUDGE_OFF,
                SignLogic.R_EXHAUSTED
        };
        for (int c : codes) {
            String str = SignLogic.resultCode(c);
            int back = SignLogic.resultOfCode(str);
            eq("I·归类码往返 " + c + " → \"" + str + "\" → " + back, Integer.valueOf(back), Integer.valueOf(c));
        }
        eq("I2·未知串返回 -1（不误判）", Integer.valueOf(SignLogic.resultOfCode("nonsense")), Integer.valueOf(-1));
        eq("I3·null 返回 -1", Integer.valueOf(SignLogic.resultOfCode(null)), Integer.valueOf(-1));
    }

    // ═══════════════ 断言助手 ═══════════════

    private static void eq(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) {
            passed++;
        } else {
            failed.add(what + " → 得到 " + got + "，期望 " + want);
        }
    }

    private static void tru(String what, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed.add(what);
        }
    }
}
