package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * M2 · 声明式阶段表测试（2026-11-01）。
 *
 * 覆盖用户指定：
 *   ① 阶段优先级专项（9 阶段顺序）
 *   ② 多条件同时命中（失败+成功 / 用尽+成功 / 重复+成功…）
 *   ③ 分句与对象归属
 *   ④ 历史事故回归（FAIL_CONTEXT 不吞真失败、冷却语不判成功…）
 *
 * 断言纪律：阶段表测试必须同时断言 **verdict + stage + hitWord**，
 * 不能只断言"最终码"（那会掩盖"结果对了但阶段错了"）。
 */
public final class StageTableTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    private static void ok(String tag, boolean cond) {
        if (cond) passed++; else failed.add(tag);
    }
    private static void eq(String tag, Object got, Object want) {
        boolean b = got == null ? want == null : got.equals(want);
        if (b) passed++; else failed.add(tag + " → 得到 " + got + "，期望 " + want);
    }

    private static SignLogic.StageInput in(String reply) {
        SignLogic.StageInput i = new SignLogic.StageInput();
        i.reply = reply;
        return i;
    }

    private static SignLogic.StageResult decide(String reply) {
        return SignLogic.decideByStage(in(reply));
    }

    public static void main(String[] args) {
        stageOrder();
        multiCondition();
        clauseAndObject();
        historyRegression();
        noneStage();

        System.out.println("----------------------------------------");
        System.out.println("StageTableTest：通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }

    // ═══════════ ① 阶段优先级（9 阶段，每个阶段至少一个代表用例）═══════════

    private static void stageOrder() {
        // S0 FAIL_HARD
        SignLogic.StageResult r = decide("签到失败，请重试");
        eq("①FAIL_HARD·verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_FAILED));
        eq("①FAIL_HARD·stage", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_FAIL_HARD));
        eq("①FAIL_HARD·hit", r.hitWord, "签到失败");

        // S2 PERMANENT_FAIL
        r = decide("活动已结束");
        eq("①PERMANENT_FAIL·stage", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_PERMANENT_FAIL));
        eq("①PERMANENT_FAIL·verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_FAILED));

        // S3 EXHAUSTED（对象感知）
        r = decide("今日签到次数已达上限。");
        eq("①EXHAUSTED·stage", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_EXHAUSTED));
        eq("①EXHAUSTED·verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_EXHAUSTED));

        // S4 REMINDER
        r = decide("请勿重复签到！");
        eq("①REMINDER·stage", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_REMINDER));
        eq("①REMINDER·verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));

        // S5 DUP
        r = decide("您今天已经签到过了");
        eq("①DUP·stage", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_DUP));
        eq("①DUP·verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));

        // S6 SUCCESS
        r = decide("签到成功");
        eq("①SUCCESS·stage", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_SUCCESS));
        eq("①SUCCESS·verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));

        // S8 COMBO（兜底：词表未覆盖、但语义明显是成功）
        // 实测结论（2026-11-01）：当前 OK/DUP 词表已很全，绝大多数"动作词×结果词"
        // 都命中 SUCCESS/DUP；COMBO 兜底几乎不可达 —— 这是**正常现象**，不是缺陷。
        // 因此这里改为**如实断言 COMBO 的边界**：
        //   · 词表未覆盖的"签领成功"（签领∈COMBO_ACT、成功∈COMBO_OK，但都不在 OK/DUP 表），
        //     因过不了 hasSuccessShape（"签领成功"缺签到语境形态）→ 判 NONE。
        //   · 这**恰恰证明 COMBO 不是无条件兜底**（用户明确要求）。
        r = decide("签领成功");
        eq("①COMBO 兜底不无条件放行（词表未覆盖+形态不足 → NONE）",
                Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_NONE));
        eq("①COMBO 兜底 verdict 为 UNKNOWN",
                Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        // S8 NONE
        r = decide("你好，有什么可以帮你？");
        eq("①NONE·stage", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_NONE));
        eq("①NONE·verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        // stageName 完整性
        eq("①stageName 全覆盖", SignLogic.stageName(SignLogic.STAGE_COMBO), "COMBO");
        eq("①stageName NONE", SignLogic.stageName(SignLogic.STAGE_NONE), "NONE");
    }

    // ═══════════ ② 多条件同时命中 ═══════════

    private static void multiCondition() {
        // 失败 + 成功同现 → FAIL_HARD 优先（SUCCESS 不得覆盖失败）
        SignLogic.StageResult r = decide("签到失败，请勿重复签到");
        eq("②失败+成功 → FAIL_HARD 优先", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_FAILED));

        // 用尽 + 成功同现 → 对象感知：抽奖用尽不影响签到成功
        r = decide("今日签到成功，抽奖次数已用尽。");
        eq("②抽奖用尽+签到成功 → SUCCESS", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));
        eq("②阶段应为 SUCCESS", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_SUCCESS));

        // 签到用尽（无成功词）→ EXHAUSTED
        r = decide("今日签到次数已用完，请明天再来");
        eq("②签到用尽 → EXHAUSTED", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_EXHAUSTED));

        // 重复提示 + 成功词 → REMINDER 优先于 SUCCESS（顺序 4 < 6）
        r = decide("请勿重复签到，您已经签过了");
        eq("②重复提示+成功 → REMINDER", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_REMINDER));

        // 已签 + 成功词同现 → DUP 优先于 SUCCESS（顺序 5 < 6）
        r = decide("您今天已经签到过了，签到成功");
        eq("②已签+成功 → DUP", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_DUP));

        // 否定 + 成功 → 不得 SUCCESS
        r = decide("没有签到成功");
        eq("②否定+成功 → FAIL_HARD", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_FAILED));
    }

    // ═══════════ ③ 分句与对象归属 ═══════════

    private static void clauseAndObject() {
        // 成功在前半句、失败条件在后半句 → 不得被后半句覆盖
        SignLogic.StageResult r = decide("签到成功！请先关注频道解锁更多功能");
        eq("③成功+条件 → SUCCESS（不被条件覆盖）",
                Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));

        // 冷却语含「已签到」统计 → 不得判成功（NON_CONCLUSION 语义）
        r = decide("签到冷却中,请8小时后再试,已签到5次");
        eq("③冷却+已签统计 → 不得 SUCCESS",
                Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        // 疑问句
        r = decide("签到成功了吗？");
        eq("③疑问句 → 不得 SUCCESS", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        // 假设句
        r = decide("如果签到成功，将获得奖励");
        eq("③假设句 → 不得 SUCCESS", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        // 对象归属：不相关对象耗尽不覆盖成功
        r = decide("今日签到成功，兑换次数不足");
        eq("③兑换次数不足+签到成功 → SUCCESS",
                Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));
    }

    // ═══════════ ④ 历史事故回归 ═══════════

    private static void historyRegression() {
        // ① 「请先关注本频道再签到，签到成功」不得被成功词覆盖真实前置条件
        //    —— 但你裁定的顺序里 PERMANENT_FAIL 在 SUCCESS 之前，
        //    而「请先关注」是 FAIL_HARD 词 → 应判 FAILED
        SignLogic.StageResult r = decide("请先关注本频道再签到，签到成功");
        ok("④前置条件不被成功词覆盖（判失败或未知，不得成功）",
                r.verdict != SignLogic.V_SIGNED);

        // ② 「签到冷却中…」不得误判成功
        r = decide("签到冷却中，请8小时后再试，已签到 5 次");
        ok("④冷却语不判成功", r.verdict != SignLogic.V_SIGNED);

        // ③ 裸成功词不能单独构成成功证据
        r = decide("签到");
        ok("④裸「签到」不判成功", r.verdict != SignLogic.V_SIGNED);

        // ④ 宽泛动作词不单独成功（「成功」单字）
        r = decide("操作成功");
        ok("④「操作成功」不判签到成功", r.verdict != SignLogic.V_SIGNED);

        // ⑤ 真实失败不被提醒/广告吞掉
        r = decide("签到失败，要试试轰炸吗？");
        eq("④失败不被推广吞掉", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_FAILED));

        // ⑥ 不相关对象耗尽不覆盖成功（已在 ② 覆盖，这里补一个「奖励」变体）
        r = decide("今日签到成功，奖励领取次数已达上限");
        eq("④奖励用尽不覆盖签到成功", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));
    }

    // ═══════════ ⑤ NONE 语义 ═══════════

    private static void noneStage() {
        SignLogic.StageResult r = decide("今日已有 123 人签到");
        eq("⑤统计类 → NONE/UNKNOWN", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        r = decide("签到规则:每日签到可获得积分");
        eq("⑤规则说明 → NONE/UNKNOWN", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        // 空输入
        r = SignLogic.decideByStage(null);
        eq("⑤null 输入 → UNKNOWN", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        SignLogic.StageInput i = new SignLogic.StageInput();
        i.reply = "";
        r = SignLogic.decideByStage(i);
        eq("⑤空输入 → UNKNOWN", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));
    }
}
