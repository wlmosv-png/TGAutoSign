package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨天状态泄漏 + 「文档尾缀吞掉成功」的回归测试（2026-11-12 P0）。
 *
 * 只依赖 SignLogic（纯逻辑），零 Android 依赖 —— 与 StageTableTest 同法编译：
 *   javac -nowarn -encoding UTF-8 -d out \
 *       app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java \
 *       tools/tests/StateLeakTest.java
 *   java -cp out io.github.wlmosv_png.tgautosign.StateLeakTest
 *
 * 退出码 0 = 全过。
 *
 * 为什么单列一个 runner：这三类缺陷（词表命中顺序、文档尾缀、跨天证据/待确认）
 * 各自的判据完全不同，混进 SignLogicTest 会让"哪条不变量被破坏"看不出来。
 */
public final class StateLeakTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    public static void main(String[] args) {
        docTailDoesNotEatSuccess();   // 真机事故：成功词 + 「使用说明」尾缀
        keywordOrderStrength();       // 命中取「最强」，不是表序首个
        docWordsNotInNonConclusion(); // 文档词不参与「本次无结论」的裁决
        stateWordsStillBlocked();     // 防回归：冷却/未完成/统计 仍不得判成功
        evidenceStampTruth();         // 跨天证据：日期不符 = 没有证据
        pendingStillValidTruth();     // 跨天待确认：无日戳 / 隔天 = 不再阻断
        startupTriggerMoved();        // 真逻辑迁址后的行为不变
        unknownPromoteGate();         // 判不出不得被无关推送升级为待确认
        pendingOrderVerdict();        // 明确归类优先于「待确认」布尔（同卡归因矛盾）
        targetLevelFixedMinute();     // 固定时刻是目标级：不影响别的 bot

        System.out.println("----------------------------------------");
        if (!failed.isEmpty()) {
            System.out.println("StateLeakTest：失败 " + failed.size() + " 项：");
            for (String f : failed) System.out.println("  ✗ " + f);
            System.out.println("通过 " + passed + " / 失败 " + failed.size());
            System.exit(1);
        }
        System.out.println("StateLeakTest：通过 " + passed + " / 失败 0");
        System.out.println("ALL OK");
    }

    // ══════════════════════════════════════════════════════════════
    // ① 真机事故回归：回复尾部带「📝 使用说明」，不得吞掉「签到成功」
    //    原始文本取自设备 prefs 的 acc0_last_unk_7999853294_1 /
    //    acc0_last_unk_8353929739_1（判「回复判不出」当时的实际回复）。
    // ══════════════════════════════════════════════════════════════
    private static final String REAL_7999853294 =
            "- 这里上传视频即可获得积分,30积分哦!\n"
          + "🔖 永久唯一用户名 @qingbaobu = 情报部拼音，认准防骗！\n"
          + "🎉签到成功🎉\n"
          + "您已经获得 1 积分。\n"
          + "您现在的积分为：33分。\n"
          + "邀请1位朋友点你的邀请链接可获得3积分 每日不限\n"
          + "🔗 这是您的专属邀请链接(长按复制)：\n"
          + "https://t.me/qingbaobu?start=ODUyNjczNDkxNg==\n"
          + "📝 使用说明：\n"
          + "- 只要点菜单 获取邀请连接 把链接地址发给朋友 即可给您加上3积分。\n"
          + "- 这里上传视频即可获得积分,30积分哦!";

    private static final String REAL_8353929739 =
            "（侵犯版权/盗版）\n"
          + "然后随意选择即可完成举报 ✅\n"
          + "❌❌❌ 请勿点击任何非官方广告，谨防受骗！❌❌❌\n"
          + "🎉签到成功🎉\n"
          + "您已经获得 1 积分。\n"
          + "您现在的积分为：67分。\n"
          + "📝 使用说明：\n"
          + "- 只要点菜单 获取邀请连接。";

    /** 设备上真实启用的用户词表（注意「成功」排在「签到成功」之前 —— 正是旧缺陷的触发条件）。 */
    private static final String[] USER_OK = {
            "社工库功能", "👑", "欢迎使用hope", "成功", "配给", "获得积分", "恭喜您",
            "签到", "欢迎", "哭哭", "配给领取", "签到成功", "积分", "今天已领过",
    };

    /**
     * 生产链实际使用的词表 = 目标/Bot/全局 > **内置**（见 Core.judgeWithScopesStage）。
     * 测试必须用同一口径，否则会出现"内置能判、用户表判不出"的假失败 ——
     * 那正是最初把问题看成"词表没覆盖"的原因。
     */
    private static final String[] MERGED_OK = merge(USER_OK, SignLogic.OK_WORDS_DEFAULT);

    private static String[] merge(String[] a, String[] b) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String x : a) out.add(x);
        for (String x : b) out.add(x);
        return out.toArray(new String[out.size()]);
    }

    private static void docTailDoesNotEatSuccess() {
        // 内置词表
        eq("真机文本A（内置表）", v(REAL_7999853294, null), SignLogic.V_SIGNED);
        eq("真机文本B（内置表）", v(REAL_8353929739, null), SignLogic.V_SIGNED);
        // 生产口径词表（旧实现下这里命中泛词「成功」→ 判不出）
        eq("真机文本A（生产词表）", v(REAL_7999853294, MERGED_OK), SignLogic.V_SIGNED);
        eq("真机文本B（生产词表）", v(REAL_8353929739, MERGED_OK), SignLogic.V_SIGNED);

        String[] heads = {"🎉签到成功🎉", "签到成功", "签到完成", "已领取每日补给", "配给领取"};

        // ① 文档 / 教程 / 导航类尾缀：**不得**否证同条里的明确成功结论
        String[] docTails = {"使用说明", "说明", "教程", "规则", "指引", "帮助", "介绍",
                             "如何签到", "使用帮助", "命令列表", "怎么用"};
        for (String h : heads) {
            for (String t : docTails) {
                String reply = h + "\n您已经获得 1 积分。\n📝 " + t + "：详情请点菜单";
                eq("强成功 + 文档尾缀「" + t + "」(" + h + ")", v(reply, MERGED_OK), SignLogic.V_SIGNED);
            }
        }

        // ② 状态 / 统计类尾缀：**允许**把结论压回非结论（保守，防误判成功）。
        //    这不是回归 —— 它描述的是"本次签到当前状态/他人统计"，
        //    在无法确定对象归属时宁可不判成功。此处只锁"不得变成成功"。
        String[] stateTails = {"排行榜", "已有 123 人签到", "请稍后", "签到冷却中"};
        for (String h : heads) {
            for (String t : stateTails) {
                String reply = h + "\n您已经获得 1 积分。\n📝 " + t + "：详情请点菜单";
                ok("强成功 + 状态尾缀「" + t + "」→ 不判成功（保守）",
                        v(reply, MERGED_OK) != SignLogic.V_SIGNED);
            }
        }

        // ③ 纯文档 / 纯教程：绝不能判成功（防误判）
        ok("纯使用说明 → 不判成功", v("📝 使用说明：发送 /qd 即可签到", MERGED_OK) != SignLogic.V_SIGNED);
        ok("纯教程句 → 不判成功", v("进行普通签到可以随机获得积分", MERGED_OK) != SignLogic.V_SIGNED);
        ok("纯导航面板 → 不判成功",
                v("请选择功能：点击下方按钮查看全部命令列表", MERGED_OK) != SignLogic.V_SIGNED);
    }


    // ══════════════════════════════════════════════════════════════
    // ② 命中顺序：取最强，而不是表序首个
    // ══════════════════════════════════════════════════════════════
    private static void keywordOrderStrength() {
        String lower = "🎉签到成功🎉 您已经获得 1 积分。";
        eq("最强命中：强词胜过靠前泛词",
                SignLogic.matchedStrongest(lower, USER_OK), "签到成功");
        // 泛词排在前面也不影响
        eq("最强命中：泛词在前仍选强词",
                SignLogic.matchedStrongest("签到成功", new String[]{"成功", "签到", "签到成功"}),
                "签到成功");
        // 文档句不得被当成结论证据
        String docOnly = "📖使用教程─────────────🔎查询方式💡小贴士";
        ok("最强命中：文档句不当结论证据",
                SignLogic.matchedStrongest(docOnly, new String[]{docOnly, "完成"}) == null
                        || !"完成".equals(SignLogic.matchedStrongest(docOnly, new String[]{docOnly, "完成"})));
        // 无命中 → null；不被抛出
        ok("最强命中：无命中返回 null", SignLogic.matchedStrongest("今天天气不错", USER_OK) == null);
        ok("最强命中：空表/空串不抛",
                SignLogic.matchedStrongest("", USER_OK) == null
                        && SignLogic.matchedStrongest("x", new String[0]) == null
                        && SignLogic.matchedStrongest(null, USER_OK) == null);
        // 确定性：同输入两次结果一致
        eq("最强命中：确定性",
                SignLogic.matchedStrongest(lower, USER_OK), SignLogic.matchedStrongest(lower, USER_OK));
    }

    // ══════════════════════════════════════════════════════════════
    // ③ 文档词不参与「本次无结论」裁决（结构性排除）
    // ══════════════════════════════════════════════════════════════
    private static void docWordsNotInNonConclusion() {
        for (String w : new String[]{"规则", "说明", "教程", "指引", "如何", "怎么", "帮助", "介绍"}) {
            ok("文档词被标记为文档类: " + w, SignLogic.isDocHelpWord(w));
        }
        // minusWords 的行为
        String[] r = SignLogic.minusWords(new String[]{"冷却", "说明", "稍后"}, new String[]{"说明"});
        eq("minusWords 长度", Integer.valueOf(r.length), Integer.valueOf(2));
        ok("minusWords 保留冷却", contains(r, "冷却") && contains(r, "稍后") && !contains(r, "说明"));
        String[] none = SignLogic.minusWords(null, new String[]{"x"});
        eq("minusWords null 安全", Integer.valueOf(none.length), Integer.valueOf(0));
    }

    // ══════════════════════════════════════════════════════════════
    // ④ 防回归：状态类证据仍然不许判成功
    //    （这些是历史 P0 事故：误判成功 = 第二天断签）
    // ══════════════════════════════════════════════════════════════
    private static void stateWordsStillBlocked() {
        int r;
        r = v("签到冷却中，请8小时后再试", USER_OK);
        ok("冷却语不得判成功", r != SignLogic.V_SIGNED);
        r = v("签到冷却中，请于 8 小时后再试，已签到 5 次", USER_OK);
        ok("冷却+历史统计不得判成功", r != SignLogic.V_SIGNED);
        r = v("签到成功，但本次签到尚未完成", USER_OK);
        ok("「尚未完成」不得判成功", r != SignLogic.V_SIGNED);
        r = v("昨天签到成功，今天冷却中", USER_OK);
        ok("历史成功+今天冷却不得判成功", r != SignLogic.V_SIGNED);
        r = v("今日已有 123 人签到", USER_OK);
        ok("纯统计不得判成功", r != SignLogic.V_SIGNED);
        r = v("📝 使用说明：发送 /qd 即可签到", USER_OK);
        ok("纯教程不得判成功", r != SignLogic.V_SIGNED);
        r = v("进行普通签到可以随机获得积分", USER_OK);
        ok("教程句不得判成功", r != SignLogic.V_SIGNED);
        r = v("请先关注本频道再签到，签到成功", USER_OK);
        eq("前置条件失败优先", Integer.valueOf(r), Integer.valueOf(SignLogic.V_FAILED));
    }

    // ══════════════════════════════════════════════════════════════
    // ⑤ 证据戳：日期不符 = 没有证据（真机泄漏的根治点）
    // ══════════════════════════════════════════════════════════════
    private static void evidenceStampTruth() {
        String today = "2026-11-12", yday = "2026-11-11";

        String stamp = SignLogic.evidenceStamp(today, 1, SignLogic.GRADE_VERDICT);
        eq("戳格式", stamp, today + "|1|4");
        eq("同天同轮 → 原值", Integer.valueOf(SignLogic.evidenceGradeOf(stamp, today, 1)),
                Integer.valueOf(SignLogic.GRADE_VERDICT));
        // ★ 真机事故：昨天的 "1|4" 在今天 round 也是 1，旧实现会当成今天的证据
        eq("旧格式（无日期）→ 无证据",
                Integer.valueOf(SignLogic.evidenceGradeOf("1|4", today, 1)),
                Integer.valueOf(SignLogic.GRADE_NONE));
        eq("日期不符 → 无证据",
                Integer.valueOf(SignLogic.evidenceGradeOf(SignLogic.evidenceStamp(yday, 1, 4), today, 1)),
                Integer.valueOf(SignLogic.GRADE_NONE));
        eq("轮次不符 → 无证据",
                Integer.valueOf(SignLogic.evidenceGradeOf(SignLogic.evidenceStamp(today, 2, 4), today, 1)),
                Integer.valueOf(SignLogic.GRADE_NONE));
        eq("空戳 → 无证据",
                Integer.valueOf(SignLogic.evidenceGradeOf("", today, 1)),
                Integer.valueOf(SignLogic.GRADE_NONE));
        eq("null 戳 → 无证据",
                Integer.valueOf(SignLogic.evidenceGradeOf(null, today, 1)),
                Integer.valueOf(SignLogic.GRADE_NONE));
        eq("越界等级 → 无证据",
                Integer.valueOf(SignLogic.evidenceGradeOf(SignLogic.evidenceStamp(today, 1, 99), today, 1)),
                Integer.valueOf(SignLogic.GRADE_NONE));
        eq("非数字 → 无证据",
                Integer.valueOf(SignLogic.evidenceGradeOf(today + "|x|y", today, 1)),
                Integer.valueOf(SignLogic.GRADE_NONE));
        eq("多余字段 → 无证据",
                Integer.valueOf(SignLogic.evidenceGradeOf(today + "|1|4|9", today, 1)),
                Integer.valueOf(SignLogic.GRADE_NONE));
    }

    // ══════════════════════════════════════════════════════════════
    // ⑥ 「待确认」是否还有效：无日戳/隔天一律不再阻断自动重签
    // ══════════════════════════════════════════════════════════════
    private static void pendingStillValidTruth() {
        String today = "2026-11-12", yday = "2026-11-11";
        ok("今天标的 → 有效",
                SignLogic.pendingStillValid(true, today, "", today));
        ok("未标记 → 无效", !SignLogic.pendingStillValid(false, today, "", today));
        ok("今天已签 → 无效（不需要确认了）",
                !SignLogic.pendingStillValid(true, yday, today, today));
        // ★ 真机事故：pendcfm_=true 而无 pendcfm_day_ → 旧实现永久有效
        ok("无日戳（真机损坏态）→ 无效",
                !SignLogic.pendingStillValid(true, "", "", today));
        ok("无日戳（null）→ 无效",
                !SignLogic.pendingStillValid(true, null, "", today));
        ok("隔天残留 → 无效",
                !SignLogic.pendingStillValid(true, yday, "", today));
    }

    // ══════════════════════════════════════════════════════════════
    // ⑦ 触发源判定迁址后行为不变
    // ══════════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════
    // ⑧ 「判不出」升级门槛（真机事故：bot 主动推广告 → 目标被钉成待确认）
    // ══════════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════
    // ⑨ 归因顺序（真机截图：同一条目同时显示「判不出」与「bot 全程没回复」）
    // ══════════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════
    // ⑩ 固定时刻是**目标级**（真机：一个目标设 12:00 → 整批被压到 12:00）
    // ══════════════════════════════════════════════════════════════
    private static void targetLevelFixedMinute() {
        int[] noWin = null;
        int now = 10 * 60;   // 10:00

        // 没设固定时刻的目标 → 永不因别人被跳过（这条正是用户要的）
        ok("无固定时刻 → 不跳过",
                !SignLogic.targetFixedMinuteNotReached(false, false, -1, noWin, now));
        // 设了 12:00（720）且现在 10:00 → 只跳过它自己
        ok("设 12:00 且未到 → 跳过该目标",
                SignLogic.targetFixedMinuteNotReached(false, true, 720, noWin, now));
        // 已到 → 不跳过
        ok("设 12:00 且已到 → 不跳过",
                !SignLogic.targetFixedMinuteNotReached(false, true, 720, noWin, 12 * 60 + 5));
        // 设了 07:40（460）且现在 10:00 → 不跳过
        ok("设 07:40 且已过 → 不跳过",
                !SignLogic.targetFixedMinuteNotReached(false, true, 460, noWin, now));
        // 定时模式：由时刻表调度，本判据不参与
        ok("定时模式 → 本判据不参与",
                !SignLogic.targetFixedMinuteNotReached(true, true, 720, noWin, now));
        // signAt 非法 → 不跳过（宁可按"没有固定时刻"处理）
        ok("signAt=-1 → 不跳过",
                !SignLogic.targetFixedMinuteNotReached(false, true, -1, noWin, now));
    }

    private static void pendingOrderVerdict() {
        // rc = no_reply（明确归类）+ 链路健康 → 必须报「未回复」，不能被 pendcfm_ 截成「判不出」
        eq("no_reply + pendcfm → PS_NO_REPLY",
                Integer.valueOf(SignLogic.resolvePendingState(false, true, true, 0L,
                        SignLogic.GRADE_NONE, SignLogic.R_NO_REPLY, true)),
                Integer.valueOf(SignLogic.PS_NO_REPLY));
        // rc = no_reply 但链路不健康 → 未确定（保守，不冤枉 bot）
        eq("no_reply + 链路不健康 → PS_UNCONFIRMED",
                Integer.valueOf(SignLogic.resolvePendingState(false, false, true, 0L,
                        SignLogic.GRADE_NONE, SignLogic.R_NO_REPLY, false)),
                Integer.valueOf(SignLogic.PS_UNCONFIRMED));
        // 有回复证据但判不出 → 判不出（不受 pendcfm_ 影响）
        eq("replied_unknown → PS_UNKNOWN",
                Integer.valueOf(SignLogic.resolvePendingState(false, true, true, 0L,
                        SignLogic.GRADE_NONE, SignLogic.R_REPLIED_UNK, true)),
                Integer.valueOf(SignLogic.PS_UNKNOWN));
        // 没有归类、只有 pendcfm_ → 兜底判不出
        eq("仅 pendcfm（无归类）→ PS_UNKNOWN",
                Integer.valueOf(SignLogic.resolvePendingState(false, true, true, 0L,
                        SignLogic.GRADE_NONE, -1, true)),
                Integer.valueOf(SignLogic.PS_UNKNOWN));
        // 今天已签优先于一切
        eq("已签 → PS_SIGNED",
                Integer.valueOf(SignLogic.resolvePendingState(true, true, true, 0L,
                        SignLogic.GRADE_NONE, SignLogic.R_REPLIED_UNK, true)),
                Integer.valueOf(SignLogic.PS_SIGNED));
    }

    private static void unknownPromoteGate() {
        // 真值表：任一成立才允许升级
        ok("在途 → 可升级", SignLogic.mayPromoteUnknown(true, false));
        ok("像签到结果 → 可升级", SignLogic.mayPromoteUnknown(false, true));
        ok("都在途且像结果 → 可升级", SignLogic.mayPromoteUnknown(true, true));
        ok("既不在途也不像结果 → 不升级", !SignLogic.mayPromoteUnknown(false, false));

        // 真机广告文本：不像签到结果、通常也不在途 → 必须不升级
        String ad = "夜生活开始！！🔥🔥约茶全国精品外围妹子 真人真图 面付🔥🔥🔥\n约茶（@yuecha）找波波（@bo_bo）\n网红 明星 陪玩 处女 猎奇 海外 孕妇 洋妞 母女 双胞胎 等等 请私聊客服";
        ok("色情广告不像签到结果", !SignLogic.looksLikeSignResult(ad));
        ok("色情广告 → 不得升级为待确认", !SignLogic.mayPromoteUnknown(false, SignLogic.looksLikeSignResult(ad)));

        // 另一条真机文本：推广型
        String promo = "https://t.me/xueppbbot\n直接点击，即可使用，积分跟会员都是同步的\n有任何疑问请私聊客服 @xuepp\n本消息由 @sxing 试用版发送";
        ok("推广文本 → 不得升级为待确认",
                !SignLogic.mayPromoteUnknown(false, SignLogic.looksLikeSignResult(promo)));

        // 正面：真结果文本即使在途已过期，仍应允许升级（照顾慢回复）
        ok("真结果慢回复 → 仍可升级",
                SignLogic.mayPromoteUnknown(false, SignLogic.looksLikeSignResult("🎉签到成功🎉 您已经获得 1 积分。")));
    }

    private static void startupTriggerMoved() {
        ok("「启动」", SignLogic.isStartupTrigger("启动"));
        ok("「启动补签」", SignLogic.isStartupTrigger("启动补签"));
        ok("「打开TG」", SignLogic.isStartupTrigger("打开TG"));
        ok("「定时」不算", !SignLogic.isStartupTrigger("定时"));
        ok("「进入窗口」不算", !SignLogic.isStartupTrigger("进入窗口"));
        ok("空串不算", !SignLogic.isStartupTrigger(""));
        ok("null 不算", !SignLogic.isStartupTrigger(null));
    }

    // ─────────────────────────── helpers ───────────────────────────
    private static int v(String reply, String[] okWords) {
        SignLogic.StageInput in = new SignLogic.StageInput();
        in.reply = reply;
        in.okWords = okWords;
        return SignLogic.decideByStage(in).verdict;
    }

    private static boolean contains(String[] a, String s) {
        for (String x : a) if (s.equals(x)) return true;
        return false;
    }

    private static void ok(String what, boolean cond) {
        if (cond) { passed++; return; }
        failed.add(what);
        System.out.println("  ✗ " + what);
    }

    private static void eq(String what, Object got, Object want) {
        boolean same = (got == null) ? (want == null) : got.equals(want);
        if (same) { passed++; return; }
        failed.add(what);
        System.out.println("  ✗ " + what + "  got=" + got + " want=" + want);
    }

    private static void eq(String what, int got, int want) {
        eq(what, Integer.valueOf(got), Integer.valueOf(want));
    }

    private static void eq(String what, String got, String want) {
        eq(what, (Object) got, (Object) want);
    }
}
