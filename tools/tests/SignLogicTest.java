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

    private static void extras() {
        tru("附加词为空 → null", SignLogic.parseExtraWords("") == null);
        tru("附加词 null → null", SignLogic.parseExtraWords(null) == null);
        tru("附加词全空白 → null", SignLogic.parseExtraWords("  ,  ,  ") == null);
        String[] a = SignLogic.parseExtraWords("已领取,领取完毕\n签到OK");
        tru("附加词解析 3 条", a != null && a.length == 3);
        String[] b = SignLogic.parseExtraWords("全角，逗号");
        tru("附加词支持全角逗号", b != null && b.length == 2);
    }
}
