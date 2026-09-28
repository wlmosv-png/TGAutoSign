package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯逻辑单测。**不依赖 JUnit / Android**，纯 javac + java 就能跑：
 *
 *     cd /data/local/tmp/tgas
 *     javac -d /tmp/tcls tools/tests/SignLogicTest.java \
 *           app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java
 *     java -cp /tmp/tcls io.github.wlmosv_png.tgautosign.SignLogicTest
 *
 * 为什么是手写断言而不是 JUnit：
 *   本机链（javac + D8）是模块构建的全部，不想为一个测试引入额外依赖。
 *   手写 runner 更小、更快、零依赖，够用。
 *
 * 退出码 0 = 全过；非 0 = 有失败（可直接挂进 build.sh 当门禁）。
 */
public final class SignLogicTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    public static void main(String[] args) {
        parseHM();
        hhmmRoundtrip();
        windowRange();
        inWindowTests();
        crossMidnight();
        missBackTime();
        backoff();
        normalizeIdTests();
        cbLabel();
        verdict();
        extras();
        signGate();
        accountClamp();
        permanentFail();
        sentPhaseLifecycle();
        resultClassification();
        looseModeFailWords();
        v160Regression();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) {
            System.out.println("FAILED");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }

    // ── 断言助手 ──────────────────────────────────────────────

    private static void eq(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) passed++;
        else failed.add(what + " → 得到 " + got + "，期望 " + want);
    }

    private static void tru(String what, boolean cond) {
        if (cond) passed++;
        else failed.add(what);
    }

    // ── 用例 ──────────────────────────────────────────────────

    private static void parseHM() {
        eq("parseHM 08:30", SignLogic.parseHM("08:30"), 8 * 60 + 30);
        eq("parseHM 00:00", SignLogic.parseHM("00:00"), 0);
        eq("parseHM 23:59", SignLogic.parseHM("23:59"), 23 * 60 + 59);
        eq("parseHM 空格容错", SignLogic.parseHM(" 9:05 "), 9 * 60 + 5);
        eq("parseHM 24:00 非法", SignLogic.parseHM("24:00"), -1);
        eq("parseHM 12:60 非法", SignLogic.parseHM("12:60"), -1);
        eq("parseHM 无冒号", SignLogic.parseHM("1230"), -1);
        eq("parseHM 空", SignLogic.parseHM(""), -1);
        eq("parseHM null", SignLogic.parseHM(null), -1);
        eq("parseHM 非数字", SignLogic.parseHM("aa:bb"), -1);
    }

    private static void hhmmRoundtrip() {
        eq("hhmm(510)", SignLogic.hhmm(510), "08:30");
        eq("hhmm(0)", SignLogic.hhmm(0), "00:00");
        eq("hhmm(1439)", SignLogic.hhmm(1439), "23:59");
    }

    private static void windowRange() {
        int[] r = SignLogic.windowRangeOf("08:30-20:30");
        tru("windowRange 正常解析", r != null && r[0] == 510 && r[1] == 1230);
        tru("windowRange 空 = null", SignLogic.windowRangeOf("") == null);
        tru("windowRange null = null", SignLogic.windowRangeOf(null) == null);
        tru("windowRange 倒序 = null", SignLogic.windowRangeOf("20:30-08:30") == null);
        tru("windowRange 缺一段 = null", SignLogic.windowRangeOf("08:30") == null);
        int[] same = SignLogic.windowRangeOf("08:30-08:30");
        tru("windowRange 起止相同合法", same != null && same[0] == same[1]);
    }

    private static void inWindowTests() {
        int[] r = SignLogic.windowRangeOf("08:30-20:30");
        tru("窗口内(10:00)", SignLogic.inWindow(600, r));
        tru("窗口端点左", SignLogic.inWindow(510, r));
        tru("窗口端点右", SignLogic.inWindow(1230, r));
        tru("窗口前(08:29)", !SignLogic.inWindow(509, r));
        tru("窗口后(20:31)", !SignLogic.inWindow(1231, r));
        tru("无窗口 = 不限 = 恒真", SignLogic.inWindow(600, null));
    }

    private static void crossMidnight() {
        // 跨天窗口 22:00-02:00
        int[] any = SignLogic.windowRangeAny("22:00-02:00");
        tru("跨天窗口可解析", any != null);
        tru("识别为跨天", SignLogic.crossesMidnight(any));
        tru("跨天：23:00 在内", SignLogic.inWindowAny(23 * 60, any));
        tru("跨天：01:00 在内", SignLogic.inWindowAny(1 * 60, any));
        tru("跨天：22:00 端点在内", SignLogic.inWindowAny(22 * 60, any));
        tru("跨天：02:00 端点在内", SignLogic.inWindowAny(2 * 60, any));
        tru("跨天：21:59 不在", !SignLogic.inWindowAny(21 * 60 + 59, any));
        tru("跨天：02:01 不在", !SignLogic.inWindowAny(2 * 60 + 1, any));
        tru("跨天：中午不在", !SignLogic.inWindowAny(12 * 60, any));

        // 分钟轴展开：22:00-02:00 → [1320, 1560]（跨度 240 分钟）
        int[] span = SignLogic.windowSpanAny(any);
        tru("跨天跨度展开", span != null && span[0] == 1320 && span[1] == 1560);
        eq("偏移折回：1560 → 120(02:00)", SignLogic.wrapMinute(1560), 120);
        eq("偏移折回：1440 → 0", SignLogic.wrapMinute(1440), 0);
        eq("偏移折回：1320 → 1320", SignLogic.wrapMinute(1320), 1320);

        // 非跨天窗口行为不变
        int[] day = SignLogic.windowRangeAny("08:30-20:30");
        tru("非跨天不标记", !SignLogic.crossesMidnight(day));
        tru("非跨天：10:00 在内", SignLogic.inWindowAny(600, day));
        tru("非跨天：08:29 不在", !SignLogic.inWindowAny(509, day));
        int[] dspan = SignLogic.windowSpanAny(day);
        tru("非跨天跨度不展开", dspan != null && dspan[0] == 510 && dspan[1] == 1230);

        // 起止相同 = 单点窗口，不算跨天
        int[] same = SignLogic.windowRangeAny("08:30-08:30");
        tru("起止相同不算跨天", !SignLogic.crossesMidnight(same));
        tru("起止相同：08:30 在内", SignLogic.inWindowAny(510, same));
        tru("起止相同：08:31 不在", !SignLogic.inWindowAny(511, same));

        // 非法
        tru("非法窗口 → null", SignLogic.windowRangeAny("25:00-02:00") == null);
        tru("空 → null", SignLogic.windowRangeAny("") == null);
        tru("null → null", SignLogic.windowRangeAny(null) == null);
    }

    private static void missBackTime() {
        int[] r = SignLogic.windowRangeOf("08:30-20:30");
        // 关掉补签
        tru("补签关：恒假", !SignLogic.inMissBackTime(700, r, 23 * 60, false));
        // 截止 23:00 >= 窗口开始 08:30 → 同日区间
        tru("同日：窗口开始时刻在内", SignLogic.inMissBackTime(510, r, 23 * 60, true));
        tru("同日：截止时刻在内", SignLogic.inMissBackTime(23 * 60, r, 23 * 60, true));
        tru("同日：窗口开始之前不在", !SignLogic.inMissBackTime(509, r, 23 * 60, true));
        tru("同日：截止之后不在", !SignLogic.inMissBackTime(23 * 60 + 1, r, 23 * 60, true));
        // 截止 06:00 < 窗口开始 20:00 → 跨天
        int[] late = SignLogic.windowRangeOf("20:00-23:00");
        tru("跨天：晚间在内", SignLogic.inMissBackTime(21 * 60, late, 6 * 60, true));
        tru("跨天：凌晨在内", SignLogic.inMissBackTime(3 * 60, late, 6 * 60, true));
        tru("跨天：下午不在", !SignLogic.inMissBackTime(15 * 60, late, 6 * 60, true));
    }

    private static void backoff() {
        eq("退避 0", SignLogic.backoffDelay(0), 5L * 60 * 1000);
        eq("退避 1", SignLogic.backoffDelay(1), 15L * 60 * 1000);
        eq("退避 2", SignLogic.backoffDelay(2), 45L * 60 * 1000);
        eq("退避 3", SignLogic.backoffDelay(3), 2L * 60 * 60 * 1000);
        eq("退避 4", SignLogic.backoffDelay(4), 4L * 60 * 60 * 1000);
        eq("退避 超表按末档", SignLogic.backoffDelay(99), 4L * 60 * 60 * 1000);
        eq("退避 负数不越界", SignLogic.backoffDelay(-5), 5L * 60 * 1000);
    }

    private static void normalizeIdTests() {
        eq("纯数字", SignLogic.normalizeId("12345"), "12345");
        eq("负数群 ID", SignLogic.normalizeId("-1001234567890"), "-1001234567890");
        eq("含空格", SignLogic.normalizeId(" 123 456 "), "123456");
        eq("unicode 负号 −", SignLogic.normalizeId("\u2212100123"), "-100123");
        eq("全角负号 －", SignLogic.normalizeId("\uFF0D100123"), "-100123");
        eq("en dash –", SignLogic.normalizeId("\u2013100123"), "-100123");
        eq("em dash —", SignLogic.normalizeId("\u2014100123"), "-100123");
        eq("多个负号只留开头", SignLogic.normalizeId("--100--123"), "-100123");
        eq("负号在中间被丢", SignLogic.normalizeId("100-123"), "100123");
        eq("带链接文字", SignLogic.normalizeId("https://t.me/c/123456"), "123456");
        eq("空", SignLogic.normalizeId(""), "");
        eq("null", SignLogic.normalizeId(null), "");
        eq("纯文字", SignLogic.normalizeId("abc"), "");
    }

    private static void cbLabel() {
        eq("ASCII data", SignLogic.cbDataLabel("checkin".getBytes()), "checkin");
        eq("空数组", SignLogic.cbDataLabel(new byte[0]), "回调按钮");
        eq("null", SignLogic.cbDataLabel(null), "回调按钮");
        // 非 ASCII → 退化成 base64 前缀
        String lbl = SignLogic.cbDataLabel(new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFD});
        tru("非 ASCII 退化为 base64 前缀: " + lbl, lbl.startsWith("回调:"));
        // 超长截断到 24 字符 + 省略号
        String longData = "abcdefghijklmnopqrstuvwxyz0123456789";
        String cut = SignLogic.cbDataLabel(longData.getBytes());
        eq("超长截断", cut, "abcdefghijklmnopqrstuvwx\u2026");
    }

    private static void verdict() {
        // 成功
        eq("中文成功", SignLogic.verdictOf("签到成功！明天再来", null, null, null), SignLogic.V_SIGNED);
        eq("英文成功", SignLogic.verdictOf("Success! See you tomorrow", null, null, null), SignLogic.V_SIGNED);
        // 已签过 → 也算已签（关键：不能被"签到"二字误判成刚成功）
        eq("今日已签", SignLogic.verdictOf("您今日已签到", null, null, null), SignLogic.V_SIGNED);
        eq("already signed", SignLogic.verdictOf("Already signed today", null, null, null), SignLogic.V_SIGNED);
        // 失败
        eq("中文失败", SignLogic.verdictOf("签到失败，请稍后再试", null, null, null), SignLogic.V_FAILED);
        eq("活动已结束", SignLogic.verdictOf("活动已结束", null, null, null), SignLogic.V_FAILED);
        eq("英文失败", SignLogic.verdictOf("Request failed", null, null, null), SignLogic.V_FAILED);
        // 认不出来 → UNKNOWN（这是新行为：以前静默什么都不做）
        eq("无关回复 → UNKNOWN", SignLogic.verdictOf("你好，有什么可以帮你？", null, null, null), SignLogic.V_UNKNOWN);
        eq("空 → UNKNOWN", SignLogic.verdictOf("", null, null, null), SignLogic.V_UNKNOWN);
        eq("null → UNKNOWN", SignLogic.verdictOf(null, null, null, null), SignLogic.V_UNKNOWN);
        // 顺序：已签过必须优先于成功（"已签到"含"签到"）
        eq("顺序：已签到不判成刚成功", SignLogic.verdictOf("您今日已签到成功过", null, null, null), SignLogic.V_SIGNED);
        // 自定义词表覆盖
        eq("自定义成功词", SignLogic.verdictOf("领取完毕", new String[0], new String[]{"领取完毕"}, null), SignLogic.V_SIGNED);
        eq("自定义失败词", SignLogic.verdictOf("不可领取", new String[0], null, new String[]{"不可领取"}), SignLogic.V_FAILED);
        // 大小写不敏感
        eq("大小写不敏感", SignLogic.verdictOf("SUCCESS", null, null, null), SignLogic.V_SIGNED);
        // 新增词表覆盖：用户反馈"今天已签到"这类没被识别
        eq("今天已签到", SignLogic.verdictOf("今天已签到", null, null, null), SignLogic.V_SIGNED);
        eq("您已签到", SignLogic.verdictOf("您已签到，明天再来", null, null, null), SignLogic.V_SIGNED);
        eq("已打卡", SignLogic.verdictOf("今日已打卡", null, null, null), SignLogic.V_SIGNED);
        eq("签到已完成", SignLogic.verdictOf("签到已完成", null, null, null), SignLogic.V_SIGNED);
        eq("签到获得积分", SignLogic.verdictOf("签到获得 3 积分", null, null, null), SignLogic.V_SIGNED);
        eq("already checked", SignLogic.verdictOf("You already checked in", null, null, null), SignLogic.V_SIGNED);
        // 反例：这些不该被判成已签
        eq("请勿重复提交 → 不该已签", SignLogic.verdictOf("请勿重复提交", null, null, null), SignLogic.V_UNKNOWN);
        eq("请勿重复提交 → 不是失败", SignLogic.verdictOf("请勿重复提交", null, null, null) != SignLogic.V_FAILED, true);
        // verdictDetail 要能说出「命中了哪条词」—— 排障靠它
        Object[] d1 = SignLogic.verdictDetail("您今日已签到", null, null, null);
        eq("detail 判定码", d1[0], SignLogic.V_SIGNED);
        eq("detail 命中词", d1[1], "今日已签");
        Object[] d2 = SignLogic.verdictDetail("活动已结束", null, null, null);
        eq("detail 失败码", d2[0], SignLogic.V_FAILED);
        eq("detail 失败命中词", d2[1], "活动已结束");
        Object[] d3 = SignLogic.verdictDetail("随便说说", null, null, null);
        eq("detail UNKNOWN 码", d3[0], SignLogic.V_UNKNOWN);
        eq("detail UNKNOWN 无命中词", d3[1], "");
        // 顺序：dup 必须优先于 ok —— 命中词能证明用的哪张表
        Object[] d4 = SignLogic.verdictDetail("您今日已签到成功", null, null, null);
        eq("顺序：dup 表先命中", d4[1], "今日已签");
    }

    /**
     * v1.6.0 修复对应的回归用例。
     *
     * 每一个都对应一个真实修掉的 bug —— 加进来是为了让"改回去"能被门禁拦住。
     */
    /**
     * 「已发出」状态生命周期回归。
     *
     * 复现用户报的 bug（2026-09-27）：
     *   cb 目标发出后显示「已发出」；bot 若把结论放在 callback answer 里、不再另发消息，
     *   回复判定永远等不到 → 过了 sent_at_ 时效后状态**退回「待签」** → 被重新排期重发。
     *   用户感受：签上了却显示已发出，过一阵又变回没签。
     *
     * 修法：超时后转「待确认」（而不是退回待签），并给用户处置入口。
     */
    private static void sentPhaseLifecycle() {
        final long TTL = 10L * 60 * 1000;   // SILENT_TO_PENDING_MS
        final long T0  = 1_700_000_000_000L;

        // ── 没发过 -> 待签 ──
        eq("没发过 -> NONE", SignLogic.sentPhase(false, 0L, T0, TTL), SignLogic.SENT_NONE);

        // ── 刚发出 -> 已发出 ──
        eq("刚发出 -> FRESH", SignLogic.sentPhase(true, T0, T0, TTL), SignLogic.SENT_FRESH);
        eq("5 分钟后 -> FRESH", SignLogic.sentPhase(true, T0, T0 + 5 * 60_000L, TTL), SignLogic.SENT_FRESH);
        eq("恰好 TTL -> FRESH（边界含等于）",
           SignLogic.sentPhase(true, T0, T0 + TTL, TTL), SignLogic.SENT_FRESH);

        // ── 超时 -> 待确认 ──
        eq("TTL+1ms -> STALE",
           SignLogic.sentPhase(true, T0, T0 + TTL + 1, TTL), SignLogic.SENT_STALE);
        eq("30 分钟后 -> STALE",
           SignLogic.sentPhase(true, T0, T0 + 30 * 60_000L, TTL), SignLogic.SENT_STALE);

        // ── 取不到时间戳：保守算"还在时效内"，绝不能当过期（会重复发送） ──
        eq("有 opt_ 无 sent_at_ -> FRESH（保守）",
           SignLogic.sentPhase(true, 0L, T0, TTL), SignLogic.SENT_FRESH);
        eq("有 opt_ 负时间戳 -> FRESH（保守）",
           SignLogic.sentPhase(true, -1L, T0, TTL), SignLogic.SENT_FRESH);

        // ── 时钟回拨（sent_at_ 在未来）：不当过期 ──
        eq("sent_at_ 在未来 -> FRESH",
           SignLogic.sentPhase(true, T0 + 60_000L, T0, TTL), SignLogic.SENT_FRESH);

        // ── 升级判定：只有"发了+超时+今天没结论+没处置过"才升级 ──
        tru("超时且未签 -> 升级",
            SignLogic.shouldPromoteToPending(true, T0, T0 + TTL + 1, TTL, false, false, false));
        tru("超时但今天已签 -> 不升级",
            !SignLogic.shouldPromoteToPending(true, T0, T0 + TTL + 1, TTL, true, false, false));
        tru("超时但已是待确认 -> 不升级",
            !SignLogic.shouldPromoteToPending(true, T0, T0 + TTL + 1, TTL, false, true, false));
        tru("超时但重试已用尽 -> 不升级",
            !SignLogic.shouldPromoteToPending(true, T0, T0 + TTL + 1, TTL, false, false, true));
        tru("还在时效内 -> 不升级",
            !SignLogic.shouldPromoteToPending(true, T0, T0 + 60_000L, TTL, false, false, false));
        tru("没发过 -> 不升级",
            !SignLogic.shouldPromoteToPending(false, 0L, T0 + TTL + 1, TTL, false, false, false));
        tru("有 opt_ 无时间戳 -> 不升级（保守）",
            !SignLogic.shouldPromoteToPending(true, 0L, T0 + TTL + 1, TTL, false, false, false));
    }

    /**
    /**
     * 「按钮可学性」过滤 —— 已于 2026-09-28 整体移除，故本函数一并删除。
     *
     * 原先这里断言的是「支付/菜单按钮必须被挡住」。那套启发式被证明判据错了：
     * 它靠 data 前缀和文案黑名单去猜按钮性质，于是必然同时误伤真签到按钮
     * （实测 EmbyPulse 的签到入口叫 ub_back_menu，撞 ub_menu_ 前缀；
     * 文案「🔙 主菜单」又撞「菜单」）和漏挡新菜单（每个 bot 文案都不同）。
     *
     * 现在改成「用户点按钮 = 意图，点了就学」，不再猜。
     * 剩下的准入判断（排除的 bot / 排除规则）在 TGAutoSignCore.learnDenyReason，
     * 属于 Android 依赖代码，不在本纯逻辑单测的覆盖范围内。
     */

    private static void v160Regression() {
        // P1-4：跨天窗口的补签时段判定。
        // 旧实现走 windowRange()（不支持跨天，返回 null）→ start 退化成 0 →
        // "窗口开始 ~ 截止"恒成立 = 全天补签。这里锁定正确行为。
        int[] cross = SignLogic.windowRangeAny("22:00-02:00");
        tru("跨天窗口可解析", cross != null);
        tru("跨天标记", SignLogic.crossesMidnight(cross));
        // 窗口 22:00-02:00、补签截止 06:00 → 12:00 不该在补签时段
        tru("跨天窗口 12:00 不在补签时段", !SignLogic.inMissBackTime(12 * 60, new int[]{22 * 60, 2 * 60}, 6 * 60, true));
        tru("跨天窗口 23:00 在补签时段", SignLogic.inMissBackTime(23 * 60, new int[]{22 * 60, 2 * 60}, 6 * 60, true));
        tru("跨天窗口 01:00 在补签时段", SignLogic.inMissBackTime(60, new int[]{22 * 60, 2 * 60}, 6 * 60, true));
        tru("跨天窗口 07:00 不在补签时段", !SignLogic.inMissBackTime(7 * 60, new int[]{22 * 60, 2 * 60}, 6 * 60, true));
        // 普通窗口不回归
        tru("普通窗口 10:00 在补签时段", SignLogic.inMissBackTime(10 * 60, new int[]{8 * 60, 20 * 60}, 23 * 60, true));
        tru("补签开关关 → 恒 false", !SignLogic.inMissBackTime(10 * 60, new int[]{8 * 60, 20 * 60}, 23 * 60, false));

        // P3-7：normalizeId 不能返回纯 "-"（下游 Long.parseLong 会抛异常被静默吞）
        eq("normalizeId 纯负号 → 空串", SignLogic.normalizeId("-"), "");
        eq("normalizeId 全角负号 → 空串", SignLogic.normalizeId("－"), "");
        eq("normalizeId 连字符无数字 → 空串", SignLogic.normalizeId("---"), "");
        eq("normalizeId 正常负数保留", SignLogic.normalizeId("-1001234567890"), "-1001234567890");
        eq("normalizeId 负号在中间被清掉", SignLogic.normalizeId("123-456"), "123456");

        // 判定顺序：dup 优先于 ok（历史踩过的坑，永久锁定）
        Object[] d = SignLogic.verdictDetail("签到成功，今日已签到", null, null, null);
        eq("dup 优先于 ok（命中词证明）", d[1], "今日已签");
    }

    private static void extras() {
        tru("附加词为空 → null", SignLogic.parseExtraWords("") == null);
        tru("附加词 null → null", SignLogic.parseExtraWords(null) == null);
        tru("附加词全空白 → null", SignLogic.parseExtraWords("  ,  ,  ") == null);
        String[] a = SignLogic.parseExtraWords("已领取,领取完毕\n签到OK");
        tru("附加词解析 3 条", a != null && a.length == 3);
        String[] b = SignLogic.parseExtraWords("全角，逗号");
        tru("附加词支持全角逗号", b != null && b.length == 2);
    }

    /** 统一签到闸（decideSign）：这是"同一 bot 连发两条"的根治点，必须有回归。 */
    private static void signGate() {
        // 空对象 → 放行
        eq("gate 空 → 放行", SignLogic.decideSign(new SignLogic.SignGate()), SignLogic.SKIP_NONE);

        // 自动路径
        SignLogic.SignGate g = new SignLogic.SignGate();
        g.signedToday = true;
        eq("已签 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_ALREADY_SIGNED);

        g = new SignLogic.SignGate();
        g.sentPendingFresh = true;
        eq("已发出待结论 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_SENT_PENDING);

        g = new SignLogic.SignGate();
        g.retryExhausted = true;
        eq("重试用尽 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_RETRY_EXHAUST);

        g = new SignLogic.SignGate();
        g.inBackoff = true;
        eq("退避中 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_BACKOFF);

        g = new SignLogic.SignGate();
        g.disabled = true;
        eq("停用 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_DISABLED);

        // 在途：manual 也不放行（防并发双发）
        g = new SignLogic.SignGate();
        g.manual = true;
        g.inFlight = true;
        eq("在途 + manual → 仍跳过", SignLogic.decideSign(g), SignLogic.SKIP_IN_FLIGHT);

        // manual 豁免今日进度类限制
        g = new SignLogic.SignGate();
        g.manual = true;
        g.signedToday = true;
        g.sentPendingFresh = true;
        g.retryExhausted = true;
        g.inBackoff = true;
        eq("manual 豁免进度限制 → 放行", SignLogic.decideSign(g), SignLogic.SKIP_NONE);

        // manual 不豁免 disabled
        g = new SignLogic.SignGate();
        g.manual = true;
        g.disabled = true;
        eq("manual 不豁免停用", SignLogic.decideSign(g), SignLogic.SKIP_DISABLED);

        // 优先级：在途 > 已签（在途更"硬"）
        g = new SignLogic.SignGate();
        g.inFlight = true;
        g.signedToday = true;
        eq("在途优先于已签", SignLogic.decideSign(g), SignLogic.SKIP_IN_FLIGHT);

        // 标签不为空（日志要用）
        tru("skipLabel 有文案", SignLogic.skipLabel(SignLogic.SKIP_SENT_PENDING).length() > 0);
        eq("skipLabel 放行 → 空串", SignLogic.skipLabel(SignLogic.SKIP_NONE), "");

        // ── 账号级停用（1.6.1 补）──
        // 此前 isAccountEnabled 只在界面/一键签全部/心跳非当前账号三处检查，
        // 没进这道统一闸 → 切到停用账号后所有触发源照签，开关形同虚设。
        g = new SignLogic.SignGate();
        g.accountDisabled = true;
        eq("账号停用 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_ACCOUNT_DISABLED);

        g = new SignLogic.SignGate();
        g.manual = true;
        g.accountDisabled = true;
        eq("账号停用 + manual → 仍跳过（账号级开关不可被手动绕过）",
           SignLogic.decideSign(g), SignLogic.SKIP_ACCOUNT_DISABLED);

        // 账号停用优先级最高：即使同时「已签/在途」也报账号停用，
        // 用户看到的提示才准确（是账号关了，不是今天签过了）。
        g = new SignLogic.SignGate();
        g.accountDisabled = true;
        g.inFlight = true;
        g.signedToday = true;
        eq("账号停用优先于在途/已签", SignLogic.decideSign(g), SignLogic.SKIP_ACCOUNT_DISABLED);

        // 未停用不受影响
        g = new SignLogic.SignGate();
        eq("账号未停用 → 放行", SignLogic.decideSign(g), SignLogic.SKIP_NONE);

        g = new SignLogic.SignGate();
        g.accountDisabled = false;
        g.manual = true;
        eq("账号未停用 + manual → 放行", SignLogic.decideSign(g), SignLogic.SKIP_NONE);

        // 目标级 disabled 与账号级互不干扰
        g = new SignLogic.SignGate();
        g.disabled = true;
        eq("仅目标级停用 → SKIP_DISABLED（不是账号码）",
           SignLogic.decideSign(g), SignLogic.SKIP_DISABLED);

        tru("skipLabel 账号停用有文案",
            SignLogic.skipLabel(SignLogic.SKIP_ACCOUNT_DISABLED).length() > 0);
        eq("skipLabel 账号停用文案", SignLogic.skipLabel(SignLogic.SKIP_ACCOUNT_DISABLED), "账号已停用");

        // ── 时间展示（今日计划 / 补签列表，2026-09-28）──
        timeDisplay();

        // ── skipSigned：批量入口只签未签的（2026-09-28 用户反馈）──
        batchSkipSigned();
    }

    /**
     * 批量「立即签到」/「签全部账号」不该重发已签的。
     *
     * 用户原话：「立即签到是所有的都签到 我签过的又给我重复了一遍」
     *          「签过的没人会再二次签的吧」
     * 根因：批量入口传 force=true（=manual），而 manual 会绕过 signedToday 检查。
     */
    private static void batchSkipSigned() {
        // ① 已签 + skipSigned → 跳过（这是本次修复的核心）
        SignLogic.SignGate g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = true;
        g.signedToday = true;
        eq("批量 + 已签 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_ALREADY_SIGNED);

        // ② 已发出待结论 + skipSigned → 也跳过（可能已经签上，重发就是重复）
        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = true;
        g.sentPendingFresh = true;
        eq("批量 + 已发出待结论 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_SENT_PENDING);

        // ③ 未签 + skipSigned → 正常放行（批量要签的就是这些）
        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = true;
        eq("批量 + 未签 → 放行", SignLogic.decideSign(g), SignLogic.SKIP_NONE);

        // ④ 单目标入口（skipSigned=false）保留强制重签能力 —— 用户点得具体，
        //    通常怀疑没签上，要允许重发。这是有意保留的行为，不是漏改。
        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = false;
        g.signedToday = true;
        eq("单目标 + 已签 → 仍放行（可强制重签）", SignLogic.decideSign(g), SignLogic.SKIP_NONE);

        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = false;
        g.sentPendingFresh = true;
        eq("单目标 + 已发出 → 仍放行", SignLogic.decideSign(g), SignLogic.SKIP_NONE);

        // ⑤ skipSigned 只影响 manual 分支；自动路径本来就会跳已签，不受影响
        g = new SignLogic.SignGate();
        g.manual = false;
        g.skipSigned = true;
        g.signedToday = true;
        eq("自动 + 已签 → 跳过（原有行为不变）", SignLogic.decideSign(g), SignLogic.SKIP_ALREADY_SIGNED);

        // ⑥ skipSigned 不豁免停用（停用优先）
        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = true;
        g.signedToday = true;
        g.disabled = true;
        eq("批量 + 已签 + 目标停用 → 报已签（已签在前，语义更准）",
           SignLogic.decideSign(g), SignLogic.SKIP_ALREADY_SIGNED);

        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = true;
        g.disabled = true;
        eq("批量 + 目标停用 → 报停用", SignLogic.decideSign(g), SignLogic.SKIP_DISABLED);

        // ⑦ 账号停用优先级最高，skipSigned 不能绕过
        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = true;
        g.accountDisabled = true;
        eq("批量 + 账号停用 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_ACCOUNT_DISABLED);

        // ⑧ 在途保护不受 skipSigned 影响（manual 也不放行）
        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = true;
        g.inFlight = true;
        eq("批量 + 在途 → 跳过", SignLogic.decideSign(g), SignLogic.SKIP_IN_FLIGHT);

        // ⑨ skipSigned 不豁免重试上限/退避（那是自动路径的限制，manual 本来就不受）
        g = new SignLogic.SignGate();
        g.manual = true;
        g.skipSigned = true;
        g.retryExhausted = true;
        g.inBackoff = true;
        eq("批量 + 重试用尽/退避 → 放行（manual 豁免）", SignLogic.decideSign(g), SignLogic.SKIP_NONE);
    }

    /** 时间口径：补签判定、HH:MM 格式化、相对时间文案。 */
    private static void timeDisplay() {
        // hhmmOf：<=0 视为未知，返回空串（调用方据此回退到 ✔）
        eq("hhmmOf(0) → 空串", SignLogic.hhmmOf(0L), "");
        eq("hhmmOf(-1) → 空串", SignLogic.hhmmOf(-1L), "");
        // 用固定时刻验证格式化（避免依赖当前时区/时刻）
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 9);
        c.set(java.util.Calendar.MINUTE, 5);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        eq("hhmmOf 09:05", SignLogic.hhmmOf(c.getTimeInMillis()), "09:05");
        c.set(java.util.Calendar.HOUR_OF_DAY, 23);
        c.set(java.util.Calendar.MINUTE, 59);
        eq("hhmmOf 23:59", SignLogic.hhmmOf(c.getTimeInMillis()), "23:59");

        long day0 = 0L;
        java.util.Calendar d = java.util.Calendar.getInstance();
        d.set(java.util.Calendar.HOUR_OF_DAY, 0);
        d.set(java.util.Calendar.MINUTE, 0);
        d.set(java.util.Calendar.SECOND, 0);
        d.set(java.util.Calendar.MILLISECOND, 0);
        day0 = d.getTimeInMillis();

        // isMissBack：① 有 miss 记录 → 一律算补签（最可信）
        tru("有补签记录 → 补签",
            SignLogic.isMissBack(true, day0 + 8 * 3600000L, 8 * 60, day0, 5));
        // 有记录时即使实签比计划早也算（记录优先）
        tru("有补签记录(实签早于计划) → 仍补签",
            SignLogic.isMissBack(true, day0 + 7 * 3600000L, 8 * 60, day0, 5));

        // ② 无记录 → 按时间差兜底
        // 计划 08:30，实签 08:31（晚 1 分）→ 在 5 分宽限内，不算补签
        tru("准点(晚1分) → 非补签",
            !SignLogic.isMissBack(false, day0 + 8 * 3600000L + 30 * 60000L + 60000L, 8 * 60 + 30, day0, 5));
        // 计划 08:30，实签 08:36（晚 6 分）→ 超宽限，算补签
        tru("晚6分(超5分宽限) → 补签",
            SignLogic.isMissBack(false, day0 + 8 * 3600000L + 30 * 60000L + 6 * 60000L, 8 * 60 + 30, day0, 5));
        // 边界：正好 5 分 → 不算（要求「大于」阈值）
        tru("正好晚5分 → 非补签",
            !SignLogic.isMissBack(false, day0 + 8 * 3600000L + 30 * 60000L + 5 * 60000L, 8 * 60 + 30, day0, 5));
        // 实签早于计划 → 不算补签
        tru("提前签 → 非补签",
            !SignLogic.isMissBack(false, day0 + 8 * 3600000L, 8 * 60 + 30, day0, 5));
        // 缺数据 → 不算（宁可不标，也不误标）
        tru("无实签时刻 → 非补签", !SignLogic.isMissBack(false, 0L, 8 * 60, day0, 5));
        tru("无计划 → 非补签", !SignLogic.isMissBack(false, day0 + 9 * 3600000L, -1, day0, 5));
        tru("无当天零点 → 非补签", !SignLogic.isMissBack(false, day0 + 9 * 3600000L, 8 * 60, 0L, 5));

        // minutesSince：向下取整，未来/未知 → 0
        eq("minutesSince 未来 → 0", SignLogic.minutesSince(1000L, 500L), 0);
        eq("minutesSince 未知 → 0", SignLogic.minutesSince(0L, 99999L), 0);
        eq("minutesSince 42 分", SignLogic.minutesSince(1000L, 1000L + 42L * 60000L), 42);
        eq("minutesSince 不足1分 → 0", SignLogic.minutesSince(1000L, 1000L + 59L * 1000L), 0);

        // humanMinutes：0/分/小时/时分
        eq("humanMinutes 0", SignLogic.humanMinutes(0), "0 分");
        eq("humanMinutes 负数", SignLogic.humanMinutes(-5), "0 分");
        eq("humanMinutes 42", SignLogic.humanMinutes(42), "42 分");
        eq("humanMinutes 60", SignLogic.humanMinutes(60), "1 小时");
        eq("humanMinutes 72", SignLogic.humanMinutes(72), "1 小时 12 分");
        eq("humanMinutes 119", SignLogic.humanMinutes(119), "1 小时 59 分");
    }

    /** 账号索引归一化：多账号串号的根治点，必须有回归。 */
    private static void accountClamp() {
        // 正常范围：原样
        eq("3账号 raw=0 -> 0", SignLogic.clampAccount(0, 3), 0);
        eq("3账号 raw=1 -> 1", SignLogic.clampAccount(1, 3), 1);
        eq("3账号 raw=2 -> 2", SignLogic.clampAccount(2, 3), 2);

        // 越界索引一律按原值用（不再钳到 total-1）
        //
        // 为什么：selectedAccount 读到的值不等于账号的「第几个」。
        // 实测 Nagram XF 登录 4 个账号会读到 7、9，那些是合法索引，数据在 acc9_ 里。
        // 钳到 total-1 会让模块读到另一个账号的分区 —— 表现为「账号1的配置变成账号2的」。
        eq("3账号 raw=3 -> 3（原值）", SignLogic.clampAccount(3, 3), 3);
        eq("2账号 raw=2 -> 2（原值）", SignLogic.clampAccount(2, 2), 2);
        eq("4账号 raw=9 -> 9（原值）", SignLogic.clampAccount(9, 4), 9);
        eq("1账号 raw=2 -> 2（原值）", SignLogic.clampAccount(2, 1), 2);

        // 负值 -> 0（负值不可能合法；返回负数会拼出 acc-1_ 垃圾分区）
        eq("raw=-1 -> 0", SignLogic.clampAccount(-1, 3), 0);
        eq("raw=-99 -> 0", SignLogic.clampAccount(-99, 3), 0);

        // 是否越界（raw >= 0 && total > 0 && raw >= total；负值不算越界）
        tru("raw=3/total=3 越界", SignLogic.accountOutOfRange(3, 3));
        tru("raw=9/total=4 越界", SignLogic.accountOutOfRange(9, 4));
        tru("raw=2/total=3 不算越界", !SignLogic.accountOutOfRange(2, 3));
        tru("正常不算越界", !SignLogic.accountOutOfRange(1, 3));
        tru("负值不算越界（由 clampAccount 退回 0）", !SignLogic.accountOutOfRange(-1, 3));
        tru("total=0 不算越界", !SignLogic.accountOutOfRange(5, 0));
    }

    /** 确定性失败词：命中即应冻结当天，不再重试。 */
    private static void permanentFail() {
        // 属于确定性失败
        tru("请先关注 → 确定性失败", SignLogic.isPermanentFail("请先关注"));
        tru("未关注 → 确定性失败", SignLogic.isPermanentFail("未关注"));
        tru("没有资格 → 确定性失败", SignLogic.isPermanentFail("没有资格"));
        tru("活动已结束 → 确定性失败", SignLogic.isPermanentFail("活动已结束"));
        tru("已过期 → 确定性失败", SignLogic.isPermanentFail("已过期"));
        tru("not allowed → 确定性失败", SignLogic.isPermanentFail("not allowed"));
        tru("大小写不敏感", SignLogic.isPermanentFail("Not Allowed"));

        // 不属于（这些该走普通重试）
        tru("签到失败 → 非确定性", !SignLogic.isPermanentFail("签到失败"));
        tru("try again → 非确定性", !SignLogic.isPermanentFail("try again"));
        tru("invalid → 非确定性", !SignLogic.isPermanentFail("invalid"));
        tru("空串 → 非确定性", !SignLogic.isPermanentFail(""));
        tru("null → 非确定性", !SignLogic.isPermanentFail(null));

        // 与 verdictDetail 联动：确定性失败词必须先被认成 V_FAILED
        Object[] vd = SignLogic.verdictDetail("请先关注本频道再签到", null, null, null);
        eq("「请先关注」判为 V_FAILED", ((Integer) vd[0]).intValue(), SignLogic.V_FAILED);
        tru("命中词可被 isPermanentFail 认出", SignLogic.isPermanentFail(String.valueOf(vd[1])));

        // 现场回归（2026-09-25 目标 7719383660）：
        // bot 先回"✅ 正在签到,请稍后..."（词表认不出 → V_UNKNOWN，
        //   但宽松模式「有回复即算成功」把它当成功 → retry 清零）
        // 再回"请先关注"（命中确定性失败词 → 冻结当天）
        Object[] v1 = SignLogic.verdictDetail("✅ 正在签到,请稍后...", null, null, null);
        eq("「正在签到」词表认不出 → V_UNKNOWN", ((Integer) v1[0]).intValue(), SignLogic.V_UNKNOWN);
        Object[] v2 = SignLogic.verdictDetail("请先关注", null, null, null);
        eq("「请先关注」判为 V_FAILED", ((Integer) v2[0]).intValue(), SignLogic.V_FAILED);
        tru("确定性失败词可被认出（冻结依据）", SignLogic.isPermanentFail(String.valueOf(v2[1])));
        // 说明：V_UNKNOWN 走的是宽松模式分支，不由本函数决定；这里只锁定词表行为。

        eq("单日失败上限 = 3", SignLogic.FAILS_PER_DAY_LIMIT, 3);
    }

    /**
     * 执行结果归类（2026-09-28）。
     *
     * 背景：改前有 5 处散落的 if-else 决定成败，「待确认」一词还被两处
     * 不同语义共用。现在每次执行必须落到恰好一个归类。
     * 这里锁定归类的**语义契约** —— 谁需要处置、谁会自动重试、谁算已签。
     */
    private static void resultClassification() {
        eq("R_SIGNED 码", SignLogic.resultCode(SignLogic.R_SIGNED), "signed");
        eq("R_FAILED 码", SignLogic.resultCode(SignLogic.R_FAILED), "failed");
        eq("R_BTN_STALE 码", SignLogic.resultCode(SignLogic.R_BTN_STALE), "btn_stale");
        eq("R_REPLIED_UNK 码", SignLogic.resultCode(SignLogic.R_REPLIED_UNK), "replied_unknown");
        eq("R_NO_REPLY 码", SignLogic.resultCode(SignLogic.R_NO_REPLY), "no_reply");
        eq("R_JUDGE_OFF 码", SignLogic.resultCode(SignLogic.R_JUDGE_OFF), "judge_off");
        eq("码->btn_stale", SignLogic.resultOfCode("btn_stale"), SignLogic.R_BTN_STALE);
        eq("码->no_reply", SignLogic.resultOfCode("no_reply"), SignLogic.R_NO_REPLY);
        eq("未知码 -> -1", SignLogic.resultOfCode("nonsense"), -1);
        eq("null -> -1", SignLogic.resultOfCode(null), -1);

        tru("SIGNED 不需处置", !SignLogic.needsAttention(SignLogic.R_SIGNED));
        tru("FAILED 不需处置", !SignLogic.needsAttention(SignLogic.R_FAILED));
        tru("BTN_STALE 需处置", SignLogic.needsAttention(SignLogic.R_BTN_STALE));
        tru("REPLIED_UNK 需处置", SignLogic.needsAttention(SignLogic.R_REPLIED_UNK));
        tru("NO_REPLY 需处置", SignLogic.needsAttention(SignLogic.R_NO_REPLY));
        tru("JUDGE_OFF 需处置", SignLogic.needsAttention(SignLogic.R_JUDGE_OFF));

        tru("FAILED 可重试", SignLogic.autoRetryable(SignLogic.R_FAILED));
        tru("BTN_STALE 可重试", SignLogic.autoRetryable(SignLogic.R_BTN_STALE));
        tru("REPLIED_UNK 不重试", !SignLogic.autoRetryable(SignLogic.R_REPLIED_UNK));
        tru("NO_REPLY 不重试", !SignLogic.autoRetryable(SignLogic.R_NO_REPLY));
        tru("JUDGE_OFF 不重试", !SignLogic.autoRetryable(SignLogic.R_JUDGE_OFF));
        tru("SIGNED 不重试", !SignLogic.autoRetryable(SignLogic.R_SIGNED));

        tru("只有 SIGNED 算已签", SignLogic.countsAsSigned(SignLogic.R_SIGNED));
        tru("NO_REPLY 不算已签", !SignLogic.countsAsSigned(SignLogic.R_NO_REPLY));
        tru("BTN_STALE 不算已签", !SignLogic.countsAsSigned(SignLogic.R_BTN_STALE));
        tru("REPLIED_UNK 不算已签", !SignLogic.countsAsSigned(SignLogic.R_REPLIED_UNK));
        tru("JUDGE_OFF 不算已签", !SignLogic.countsAsSigned(SignLogic.R_JUDGE_OFF));

        tru("「正在签到」是进度", SignLogic.looksLikeProgress("正在签到,请稍后..."));
        tru("「请稍后」是进度", SignLogic.looksLikeProgress("请稍后"));
        tru("processing 是进度", SignLogic.looksLikeProgress("Processing..."));
        tru("「签到成功」不是进度", !SignLogic.looksLikeProgress("签到成功"));
        tru("null 不是进度", !SignLogic.looksLikeProgress(null));

        tru("「正在签到」不算结果", !SignLogic.looksLikeSignResult("正在签到,请稍后..."));
        tru("「正在查询」不算结果", !SignLogic.looksLikeSignResult("正在查询,请稍后"));
        tru("「签到成功」算结果", SignLogic.looksLikeSignResult("签到成功"));
        tru("checkin 算结果", SignLogic.looksLikeSignResult("checkin done"));
        tru("无关文本不算结果", !SignLogic.looksLikeSignResult("这是 EmbyPulse 用户自助服务机器人"));
        tru("空串不算结果", !SignLogic.looksLikeSignResult(""));
        tru("null 不算结果", !SignLogic.looksLikeSignResult(null));
    }

    /**
     * 宽松模式的「功能性拒绝」词（2026-09-28）。
     *
     * 实测事故：宽松模式下「⚠️ 请先加入以下1个频道才能使用功能」被判成签到成功 ——
     * 那是明确的拒绝，用户压根没签到。同类还有「🔒 请先绑定或注册账号」。
     *
     * 这类措辞的共同点：**要求用户先做某事**，属于前置条件未满足，
     * 而不是业务结果。它们必须先于"有回复即成功"被判失败。
     */
    private static void looseModeFailWords() {
        // 必须判失败：功能性拒绝
        tru("请先加入频道 → 失败",
            SignLogic.verdictOf("⚠️ 请先加入以下1个频道才能使用功能：", null, new String[0], SignLogic.FAIL_WORDS_DEFAULT)
                == SignLogic.V_FAILED);
        tru("请先绑定账号 → 失败",
            SignLogic.verdictOf("🔒 请先绑定或注册账号后才能使用此功能", null, new String[0], SignLogic.FAIL_WORDS_DEFAULT)
                == SignLogic.V_FAILED);
        tru("未绑定 → 失败",
            SignLogic.verdictOf("你还没有绑定账号，请先绑定", null, new String[0], SignLogic.FAIL_WORDS_DEFAULT)
                == SignLogic.V_FAILED);
        tru("无权限 → 失败",
            SignLogic.verdictOf("无权限操作", null, new String[0], SignLogic.FAIL_WORDS_DEFAULT)
                == SignLogic.V_FAILED);
        tru("not linked → 失败",
            SignLogic.verdictOf("Account not linked", null, new String[0], SignLogic.FAIL_WORDS_DEFAULT)
                == SignLogic.V_FAILED);

        // 不能误伤：正常成功回复仍判成功
        tru("签到成功 → 成功",
            SignLogic.verdictOf("🎉 签到成功", null, SignLogic.OK_WORDS_DEFAULT, SignLogic.FAIL_WORDS_DEFAULT)
                == SignLogic.V_SIGNED);
        tru("已签到 → 成功（重复词）",
            SignLogic.verdictOf("你今天已经签到过了", null, null, SignLogic.FAIL_WORDS_DEFAULT)
                == SignLogic.V_SIGNED);
        // 「请先」类词不能撞到正常回复
        tru("欢迎语不判失败",
            SignLogic.verdictOf("🎉 欢迎使用本机器人", null, new String[0], SignLogic.FAIL_WORDS_DEFAULT)
                == SignLogic.V_UNKNOWN);
    }
}
