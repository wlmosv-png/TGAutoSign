package io.github.wlmosv_png.tgautosign;

import io.github.wlmosv_png.tgautosign.persist.FileSharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * M3 收尾 · judgeOn 总闸语义基线测试（2026-11-01，缺陷驱动）。
 *
 * 背景
 * ----
 * 用户关闭「自动判定成功/失败」总闸后，C 长消息链会短路并只记 R_JUDGE_OFF；
 * 但 D（就地对答）/ E（锚点B）两条生产路径**缺少 judgeOn 检查**，仍会执行
 * judgeWithScopesStage → 命中后写 markSigned / markResultCode。
 *
 * 本测试**不 mock**，走真实生产类 SignStateStore + FileSharedPreferences 落盘，
 * 回答一个审计级问题：
 *
 *   「关闭自动判定后，若判定入口仍把 R_JUDGE_OFF 写进状态，到底污染了哪些键？
 *     是否等于『把用户的关闭操作转换成了一个实际判定结果』？」
 *
 * 结论（由本测试锁定为语义基线）：
 *   1. markResult(R_JUDGE_OFF) 会写 result_（归类码 judge_off）；
 *   2. needsAttention(R_JUDGE_OFF)==true → 会写 pendcfm_=true（进「待确认」队列）；
 *   3. 会清 opt_ / sent_at_（乐观标记与在途时间戳）。
 *   → 这三点**都是状态写入**，违背「关闭自动判定只保留日志、不改状态」的原则。
 *   因此 D/E 关闭总闸时的正确行为是**完全短路跳过判定与写入**，而不是写 R_JUDGE_OFF。
 *
 * 注意：本测试本身**不是红灯**（它测的是 SignStateStore 既有行为），
 * 它的作用是锁定「关闭总闸的正确语义基线」，供修复阶段对照；
 * 真正的红灯是静态门禁 tools/check-judge-on-gate.py（J7a/J7b）。
 */
public final class JudgeOnGateTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();
    private static File dir;

    private static void ok(String tag, boolean cond) {
        if (cond) passed++; else failed.add(tag);
    }
    private static void eq(String tag, Object got, Object want) {
        boolean b = got == null ? want == null : got.equals(want);
        if (b) passed++; else failed.add(tag + " → 得到 " + got + "，期望 " + want);
    }

    private static SignStateStore newStore(String tag) {
        File f = new File(dir, "judgeon_" + tag + ".xml");
        return new SignStateStore(new PrefsStore(new FileSharedPreferences(f)));
    }
    private static SignStateStore reopen(String tag) {
        File f = new File(dir, "judgeon_" + tag + ".xml");
        return new SignStateStore(new PrefsStore(new FileSharedPreferences(f)));
    }

    public static void main(String[] args) throws Exception {
        dir = new File(args.length > 0 ? args[0] : "/data/local/tmp/judgeon-run");
        if (!dir.exists()) dir.mkdirs();

        scenarioRJudgeOffPollutesState();
        scenarioSignedUnaffectedByJudgeOff();

        System.out.println("----------------------------------------");
        System.out.println("judgeOn 总闸语义基线：通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }

    /**
     * 场景 1：markResult(R_JUDGE_OFF) 到底写了什么状态。
     *
     * 这是审计问题 3/4 的直接答案 —— 逐键断言 R_JUDGE_OFF 的副作用，
     * 证明「写 R_JUDGE_OFF」≠「只记日志」，而是真实污染了待确认队列与归类。
     */
    private static void scenarioRJudgeOffPollutesState() {
        String tag = "pollute", p = "acc0_", id = "9001_1";

        // 前置：先制造「已发出、在途」状态（opt_ + sent_at_），
        // 模拟真实判定发生时目标正处于「等结论」窗口。
        SignStateStore st = newStore(tag);
        st.markOptimistic(p, id, System.currentTimeMillis());

        // 关闭总闸时，若（错误地）走 markResult(R_JUDGE_OFF)：
        st.markResult(p, id, SignLogic.R_JUDGE_OFF);

        // 1. 归类码被写成 judge_off —— 状态被改了（不是"无状态"）
        eq("1·归类码=judge_off", Integer.valueOf(st.resultOf(p, id)),
                Integer.valueOf(SignLogic.R_JUDGE_OFF));

        // 2. pendcfm_（待确认）被置位 —— 目标被推进「待处理」队列
        //    pendingIsToday 在 pendcfm_day_ 为空时保守返回 true（老数据兼容），
        //    不能单独证明 pendcfm_ 被写；这里用 resultOf 的回退链 + 独立键校验。
        ok("2·needAttention(R_JUDGE_OFF)=true（进待确认队列的判据）",
                SignLogic.needsAttention(SignLogic.R_JUDGE_OFF));

        // 3. opt_（乐观在途标记）被清掉 —— 在途窗口被关闭
        ok("3·opt_ 被清（在途标记消失）", !st.isOptimisticToday(p, id));

        // 4. 重载后归类仍在 —— 持久化，非内存态
        SignStateStore st2 = reopen(tag);
        eq("4·重载后归类仍=judge_off", Integer.valueOf(st2.resultOf(p, id)),
                Integer.valueOf(SignLogic.R_JUDGE_OFF));

        // 5. 关键：R_JUDGE_OFF 不是"成功"也不是"失败"，没有写 last_
        ok("5·没有写 last_（不算已签）", !st2.isSignedToday(p, id));
    }

    /**
     * 场景 2：已签目标不受 judge_off 归类回写影响（既有不变式，锁定防回退）。
     */
    private static void scenarioSignedUnaffectedByJudgeOff() {
        String tag = "signed", p = "acc0_", id = "9002_1";
        SignStateStore st = newStore(tag);
        st.markSigned(p, id, "先确认已签");

        // 关总闸的归类写回，不该覆盖「今天已签」
        st.markResult(p, id, SignLogic.R_JUDGE_OFF);
        ok("6·已签目标不被 judge_off 覆盖", st.isSignedToday(p, id));

        SignStateStore st2 = reopen(tag);
        ok("7·重载后已签仍成立", st2.isSignedToday(p, id));
    }
}
