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
        nonSignButtonFilter();
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
     * 非签到按钮过滤回归。
     *
     * 复现实测事故（2026-09-27）：模块把 8439387373 的支付按钮学成签到目标并真的去点，
     * 且自我繁殖（09-23 出现 2 次 → 09-27 涨到 15 次）。
     *
     * 设计原则：**只挡明显的**。真签到按钮被误挡会让用户完全签不了，
     * 比多学一个按钮严重得多 —— 所以"拿不准"必须放行。
     */
    private static void nonSignButtonFilter() {
        // ── 必须挡住：实测见过的支付/菜单按钮 ──
        tru("pay:alipay 要挡", SignLogic.obviousNonSignButton("支付宝", "pay:alipay") != null);
        tru("pay:wxpay 要挡", SignLogic.obviousNonSignButton("微信支付", "pay:wxpay") != null);
        tru("pay:menu 要挡", SignLogic.obviousNonSignButton("支付菜单", "pay:menu") != null);
        tru("ub_menu_bind 要挡", SignLogic.obviousNonSignButton("绑定", "ub_menu_bind") != null);
        tru("ub_menu_register 要挡", SignLogic.obviousNonSignButton("注册", "ub_menu_register") != null);
        tru("ub_menu_library 要挡", SignLogic.obviousNonSignButton("资源库", "ub_menu_library") != null);
        // 实测漏网（2026-09-27 日志）：UI 层按文案「🆘 帮助」拦住了，
        // 网络层拿到的是 data 解码后的 mp_help，黑名单没有它 → 被学成目标。
        tru("mp_help 要挡", SignLogic.obviousNonSignButton("帮助", "mp_help") != null);
        tru("mp_ 前缀要挡", SignLogic.obviousNonSignButton("", "mp_settings") != null);
        // 反向：真签到按钮仍必须放行
        tru("sign 仍放行", SignLogic.obviousNonSignButton("签到", "sign") == null);
        tru("checkin 仍放行", SignLogic.obviousNonSignButton("", "checkin_daily") == null);

        // ── 文案黑名单（data 干净、但文案明显不是签到）──
        tru("文案「立即支付」要挡", SignLogic.obviousNonSignButton("立即支付", "abc123def") != null);
        tru("文案「充值」要挡", SignLogic.obviousNonSignButton("充值", "xyz789") != null);
        tru("文案「邀请好友」要挡", SignLogic.obviousNonSignButton("邀请好友", "inv1") != null);
        tru("文案「取消」要挡", SignLogic.obviousNonSignButton("取消", "zzz") != null);
        tru("英文 pay 要挡", SignLogic.obviousNonSignButton("Pay now", "a1b2") != null);
        tru("英文 logout 要挡", SignLogic.obviousNonSignButton("Logout", "q1w2") != null);

        // ── 随机 hex token（长度 4-12 的纯 hex）──
        tru("随机 hex 4b780f 要挡", SignLogic.obviousNonSignButton("", "4b780f") != null);
        tru("随机 hex f9f106 要挡", SignLogic.obviousNonSignButton("", "f9f106") != null);
        tru("随机 hex 8d9e2a 要挡", SignLogic.obviousNonSignButton("", "8d9e2a") != null);

        // ── 必须放行：真签到按钮（绝不能误伤）──
        tru("sign 要放行", SignLogic.obviousNonSignButton("签到", "sign") == null);
        tru("checkin 要放行", SignLogic.obviousNonSignButton("签到", "checkin") == null);
        tru("daily_check 要放行", SignLogic.obviousNonSignButton("每日签到", "daily_check") == null);
        tru("qd 要放行", SignLogic.obviousNonSignButton("签到", "qd") == null);
        tru("长 data 要放行", SignLogic.obviousNonSignButton("签到", "checkin_daily_20260927") == null);
        tru("空输入要放行", SignLogic.obviousNonSignButton(null, null) == null);
        tru("空串要放行", SignLogic.obviousNonSignButton("", "") == null);

        // ── 边界：sign 不是纯 hex（含 s/i/g/n），不能被随机规则误伤 ──
        tru("sign 不是 hex", SignLogic.obviousNonSignButton("", "sign") == null);
        // 短英文词（menu/help/back）不进文案黑名单：有些 bot 的签到入口就叫 "Menu"，
        // 子串匹配会误伤真签到按钮。它们仍由 data 前缀名单拦截（menu: 带分隔符才判）。
        tru("文案 menu 要放行（避免误伤）", SignLogic.obviousNonSignButton("menu", "abcxyz") == null);
        tru("文案 Menu 要放行", SignLogic.obviousNonSignButton("Menu", "click_start") == null);
        tru("但 data menu:xxx 要挡", SignLogic.obviousNonSignButton("", "menu:settings") != null);
        tru("claim 不是 hex", SignLogic.obviousNonSignButton("", "claim") == null);
        // 长度边界：3 字符太短不判随机
        tru("3 字符不判随机", SignLogic.obviousNonSignButton("", "abc") == null);
        // 13 字符超出随机区间
        tru("13 字符不判随机", SignLogic.obviousNonSignButton("", "abcdef0123456") == null);

        // ── 回归（2026-09-28，Sarah 反馈）：文案明确是签到的按钮，data 再可疑也要放行 ──
        // 现象：同两个真签到按钮，「🎯 签到」能学到，「✅ 每日签到」学不到，
        //      必须去「添加目标」手动捕获才行（手动路径不经过本过滤）。
        // 根因：上一版把 data 当权威判据，data 撞上黑名单/随机 hex 就拦，
        //      没看用户可见文案。同面板两个按钮文案都是明确签到，差异只在 data。
        tru("文案「✅ 每日签到」要放行（即便 data 是随机 hex）",
            SignLogic.obviousNonSignButton("✅ 每日签到", "a3f9c1") == null);
        tru("文案「每日签到」要放行",
            SignLogic.obviousNonSignButton("每日签到", "b7e2d4") == null);
        tru("文案「签到」要放行（即便 data 像 pay:）",
            SignLogic.obviousNonSignButton("签到", "pay:alipay") == null);
        tru("文案「🎯 签到」要放行",
            SignLogic.obviousNonSignButton("🎯 签到", "9f8e7d") == null);
        tru("文案「📅 签到」要放行",
            SignLogic.obviousNonSignButton("📅 签到", "1a2b3c") == null);
        tru("文案「每日打卡」要放行",
            SignLogic.obviousNonSignButton("每日打卡", "dead") == null);
        tru("文案「领取奖励」要放行",
            SignLogic.obviousNonSignButton("领取奖励", "cafe") == null);
        tru("文案「Check in」要放行",
            SignLogic.obviousNonSignButton("Check in", "8d9e2a") == null);
        tru("文案「Daily Check-in」要放行",
            SignLogic.obviousNonSignButton("Daily Check-in", "4b780f") == null);
        // 大写下划线变体
        tru("文案「CHECK_IN」要放行",
            SignLogic.obviousNonSignButton("CHECK_IN", "f9f106") == null);
        // 带空格/装饰的签到文案
        tru("文案「签 到」要放行",
            SignLogic.obviousNonSignButton("签 到", "x9y8z7") == null);

        // labelLooksLikeSign 本身
        tru("labelLooksLikeSign 签到", SignLogic.labelLooksLikeSign("签到"));
        tru("labelLooksLikeSign 每日签到", SignLogic.labelLooksLikeSign("✅ 每日签到"));
        tru("labelLooksLikeSign checkin", SignLogic.labelLooksLikeSign("checkin"));
        tru("labelLooksLikeSign 空 → false", !SignLogic.labelLooksLikeSign(""));
        tru("labelLooksLikeSign null → false", !SignLogic.labelLooksLikeSign(null));
        // 否定词优先：「签到记录」不是签到按钮
        tru("「签到记录」不算签到", !SignLogic.labelLooksLikeSign("签到记录"));
        tru("「签到历史」不算签到", !SignLogic.labelLooksLikeSign("签到历史"));
        tru("「签到说明」不算签到", !SignLogic.labelLooksLikeSign("签到说明"));
        tru("「签到统计」不算签到", !SignLogic.labelLooksLikeSign("签到统计"));
        tru("「签到规则」不算签到", !SignLogic.labelLooksLikeSign("签到规则"));
        tru("「签到教程」不算签到", !SignLogic.labelLooksLikeSign("签到教程"));
        tru("「签到排行榜」不算签到", !SignLogic.labelLooksLikeSign("签到排行榜"));
        // 「qd」刻意不进白名单：网络层会拿 data 串当 label，"aqdb1" 这类会误命中
        tru("labelLooksLikeSign 不含裸 qd", !SignLogic.labelLooksLikeSign("qd"));
        tru("qd 文案仍由关键词过滤兜（不靠白名单）",
            SignLogic.obviousNonSignButton("qd", "a3f9c1") != null);
        // 但否定词只在「看起来像签到」时才该生效；普通菜单文案不受影响
        tru("「用户中心」不受否定词影响",
            SignLogic.obviousNonSignButton("用户中心", "abcxyz") == null);

        // 真非签到按钮在文案不明确时，仍要被 data 判据拦住（不能因为放宽而漏挡）
        tru("data pay: 仍要挡（文案无签到词）",
            SignLogic.obviousNonSignButton("支付", "pay:wxpay") != null);
        tru("data mp_help 仍要挡",
            SignLogic.obviousNonSignButton("帮助", "mp_help") != null);
        tru("随机 hex 仍要挡（文案无签到词）",
            SignLogic.obviousNonSignButton("", "4b780f") != null);

        // ── 前缀名单本身（清理逻辑复用同一份，必须非空且含实测项）──
        tru("JUNK_DATA_PREFIXES 非空", SignLogic.JUNK_DATA_PREFIXES.length > 0);
        boolean hasPay = false, hasMenu = false;
        for (String p : SignLogic.JUNK_DATA_PREFIXES) {
            if ("pay:".equals(p)) hasPay = true;
            if ("ub_menu_".equals(p)) hasMenu = true;
        }
        tru("名单含 pay:", hasPay);
        tru("名单含 ub_menu_:", hasMenu);
    }

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
}
