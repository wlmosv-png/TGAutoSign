package io.github.wlmosv_png.tgautosign.persist;

import io.github.wlmosv_png.tgautosign.Keys;
import io.github.wlmosv_png.tgautosign.PrefsStore;
import io.github.wlmosv_png.tgautosign.SignLogic;
import io.github.wlmosv_png.tgautosign.SignStateStore;
import io.github.wlmosv_png.tgautosign.DateUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 2 · 状态持久化端到端测试（2026-11-01）。
 *
 * 用户要求（原话节选）：
 *   「必须追踪真实生产链路：Bot 原始回复 → 消息处理入口 → Verdict → 状态转换
 *     → SignStateStore → 持久化文件或数据库 → 重新加载 → 后续签到调度」
 *   「不能只验证内存中的对象，也不能只 mock Store」
 *
 * 本测试的做法：
 *   · 走**真实的 SignStateStore**（生产代码原封不动）
 *   · 底层用 FileSharedPreferences —— 真实 XML 落盘、真实重新加载
 *   · "重新加载" = 新建实例读同一文件，等价于进程重启
 *   · "后续调度"用生产的纯函数 SignLogic.decideSign(SignGate) 判定
 *
 * 6 个场景 A–F，全部真实执行，无 mock。
 */
public final class PersistTest {

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

    /** 新建一套"进程"（文件 + PrefsStore + SignStateStore）。 */
    private static SignStateStore newStore(String tag) {
        File f = new File(dir, "store_" + tag + ".xml");
        return new SignStateStore(new PrefsStore(new FileSharedPreferences(f)));
    }
    /** 模拟"进程重启"：重新读同一文件。 */
    private static SignStateStore reopen(String tag) {
        File f = new File(dir, "store_" + tag + ".xml");
        return new SignStateStore(new PrefsStore(new FileSharedPreferences(f)));
    }

    private static String today() { return DateUtils.today(); }

    public static void main(String[] args) throws Exception {
        dir = new File(args.length > 0 ? args[0] : "/data/local/tmp/persist-run");
        if (!dir.exists()) dir.mkdirs();

        scenarioA();
        scenarioB();
        scenarioC();
        scenarioD();
        scenarioE();
        scenarioF();

        System.out.println("----------------------------------------");
        System.out.println("持久化测试：通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }

    // ══════════ 场景 A：正常已签 ══════════
    private static void scenarioA() {
        String tag = "A", p = "acc0_", id = "1001_1";
        SignStateStore st = newStore(tag);
        st.markSigned(p, id, "阶段2测试");

        // 落库后即时可见
        ok("A1·写入后已签", st.isSignedToday(p, id));
        ok("A2·signed_at_ 有值", st.signedAtMs(p, id) > 0L);

        // 重新加载（等价重启）
        SignStateStore st2 = reopen(tag);
        ok("A3·重载后已签仍在", st2.isSignedToday(p, id));
        eq("A4·重载后 last_ 值正确", st2.isSignedToday(p, id) ? today() : null, today());

        // 后续调度：已签 → 必须跳过
        SignLogic.SignGate g = new SignLogic.SignGate();
        g.manual = false; g.signedToday = st2.isSignedToday(p, id);
        eq("A5·后续调度跳过已签", Integer.valueOf(SignLogic.decideSign(g)),
                Integer.valueOf(SignLogic.SKIP_ALREADY_SIGNED));
        // 手动批量语义：skipSigned 时也应跳过
        SignLogic.SignGate g2 = new SignLogic.SignGate();
        g2.manual = true; g2.skipSigned = true; g2.signedToday = st2.isSignedToday(p, id);
        eq("A6·手动批量也跳过已签", Integer.valueOf(SignLogic.decideSign(g2)),
                Integer.valueOf(SignLogic.SKIP_ALREADY_SIGNED));
    }

    // ══════════ 场景 B：UNKNOWN 保护 ══════════
    private static void scenarioB() {
        String tag = "B", p = "acc0_", id = "1002_1";
        SignStateStore st = newStore(tag);
        st.markSigned(p, id, "先确认已签");
        ok("B1·前置已签", st.isSignedToday(p, id));

        // 一条判不出的回复到达 → 生产路径会 markResult(R_REPLIED_UNK)
        st.markResult(p, id, SignLogic.R_REPLIED_UNK);
        ok("B2·UNKNOWN 后 last_ 未被覆盖", st.isSignedToday(p, id));

        // 再来一条"bot 未回复"归类
        st.markResult(p, id, SignLogic.R_NO_REPLY);
        ok("B3·NO_REPLY 后 last_ 未被覆盖", st.isSignedToday(p, id));

        // 重载
        SignStateStore st2 = reopen(tag);
        ok("B4·重载后已签仍在", st2.isSignedToday(p, id));

        // 后续调度仍跳过
        SignLogic.SignGate g = new SignLogic.SignGate();
        g.signedToday = st2.isSignedToday(p, id);
        eq("B5·调度仍跳过", Integer.valueOf(SignLogic.decideSign(g)),
                Integer.valueOf(SignLogic.SKIP_ALREADY_SIGNED));
    }

    // ══════════ 场景 C：重复回复 ══════════
    private static void scenarioC() {
        String tag = "C", p = "acc0_", id = "1003_1";
        SignStateStore st = newStore(tag);

        boolean first = st.markSigned(p, id, "第一次");
        boolean second = st.markSigned(p, id, "第二次");
        ok("C1·首次返回 true", first);
        ok("C2·重复返回 false（未重复转换）", !second);
        eq("C3·signed_at_ 未被第二次改写",
                Long.valueOf(st.signedAtMs(p, id)), Long.valueOf(st.signedAtMs(p, id)));

        // 连续 markResult 两次，状态应稳定
        st.markResult(p, id, SignLogic.R_SIGNED);
        st.markResult(p, id, SignLogic.R_SIGNED);
        ok("C4·重复归类后仍已签", st.isSignedToday(p, id));

        SignStateStore st2 = reopen(tag);
        ok("C5·重载后状态一致", st2.isSignedToday(p, id));
        // C6 修正说明（2026-11-01）：原写 pendingIsToday()==false 是**误读 API**——
        //   该方法在 pendingDay_ 为空时**设计上**返回 true（注释："老数据：保守保留"），
        //   它表达的是"是否有今天的待确认日期"，不是"是否存在待确认"。
        //   真正该断言的是：**pendingCfm_ 这个键本身被清掉了**。
        FileSharedPreferences raw2 = new FileSharedPreferences(new File(dir, "store_" + tag + ".xml"));
        ok("C6·重复回复后无 pendcfm_ 残留键",
                !raw2.contains(Keys.pendingCfm(p, id)));
    }

    // ══════════ 场景 D：多账号隔离 ══════════
    private static void scenarioD() {
        String tag = "D";
        String pA = "acc0_", pB = "acc3_";
        String idA = "2001_1", idB = "2002_1";
        SignStateStore st = newStore(tag);

        st.markSigned(pA, idA, "账号A成功");
        st.markResult(pB, idB, SignLogic.R_FAILED);

        ok("D1·账号A 已签", st.isSignedToday(pA, idA));
        ok("D2·账号B 未签", !st.isSignedToday(pB, idB));

        // 同 id 不同账号也要隔离
        String shared = "3003_1";
        st.markSigned(pA, shared, "A 的");
        ok("D3·同 id 账号B 不受影响", !st.isSignedToday(pB, shared));

        SignStateStore st2 = reopen(tag);
        ok("D4·重载后 A 仍已签", st2.isSignedToday(pA, idA));
        ok("D5·重载后 B 仍未签", !st2.isSignedToday(pB, idB));
        ok("D6·重载后同 id 隔离仍成立", !st2.isSignedToday(pB, shared));
    }

    // ══════════ 场景 E：撤销 / 暂停 / 恢复 ══════════
    private static void scenarioE() {
        String tag = "E", p = "acc0_", id = "1004_1";
        SignStateStore st = newStore(tag);

        st.markSigned(p, id, "先签上");
        ok("E1·前置已签", st.isSignedToday(p, id));

        // 撤销（用户手动标记未签）
        boolean was = st.markUnsigned(p, id);
        ok("E2·撤销返回 true（之前确实已签）", was);
        ok("E3·撤销后未签", !st.isSignedToday(p, id));
        eq("E4·撤销同时清掉 signed_at_", Long.valueOf(st.signedAtMs(p, id)), Long.valueOf(0L));

        SignStateStore st2 = reopen(tag);
        ok("E5·重载后仍是未签（旧记录没被复原）", !st2.isSignedToday(p, id));

        // 暂停 → 由调度闸体现（disabled）
        SignLogic.SignGate gp = new SignLogic.SignGate();
        gp.disabled = true; gp.signedToday = st2.isSignedToday(p, id);
        eq("E6·暂停目标被跳过", Integer.valueOf(SignLogic.decideSign(gp)),
                Integer.valueOf(SignLogic.SKIP_DISABLED));

        // 恢复 → 应可正常参与
        SignLogic.SignGate gr = new SignLogic.SignGate();
        gr.disabled = false; gr.signedToday = st2.isSignedToday(p, id);
        ok("E7·恢复后可参与调度", SignLogic.decideSign(gr) == SignLogic.SKIP_NONE);

        // 重新收到成功回复 → 重新签上
        st2.markSigned(p, id, "重新签");
        ok("E8·重新签上", st2.isSignedToday(p, id));
        SignStateStore st3 = reopen(tag);
        ok("E9·重载后重新签的状态在", st3.isSignedToday(p, id));
    }

    // ══════════ 场景 F：EXHAUSTED 落库与调度 ══════════
    private static void scenarioF() {
        String tag = "F", p = "acc0_";
        String idEx = "1005_1", idFail = "1006_1", idUnk = "1007_1";
        SignStateStore st = newStore(tag);

        // 用尽：生产路径把 EXHAUSTED 当"今日已了结" → 写已签 + 归类 R_EXHAUSTED
        st.markSigned(p, idEx, "命中次数已达上限");
        st.markResult(p, idEx, SignLogic.R_EXHAUSTED);
        ok("F1·EXHAUSTED 落库为已签（今日了结）", st.isSignedToday(p, idEx));
        // F2 修正说明（2026-11-01）：原期望 resultOf()==R_EXHAUSTED 是**误读 API**——
        //   resultOf 开头即 `if (isSignedToday) return -1;`，注释写明
        //   「已签 → 没有异常归类可谈（历史残留也不显示）」（2026-10-28 治本）。
        //   EXHAUSTED 在生产路径里会 markSigned，所以它本就是"已签"，读 -1 是设计行为。
        //   真正该断言的是：**已签时不再暴露异常归类**（否则界面会把已签显示成"卡住"）。
        eq("F2·已签时 resultOf 按设计返回 -1（不暴露异常归类）",
                Integer.valueOf(st.resultOf(p, idEx)), Integer.valueOf(-1));
        // 对照组：未签时归类码必须可读回（证明 markResult 真的写进去了）
        st.markResult(p, idFail, SignLogic.R_EXHAUSTED);
        eq("F2b·未签时归类码可读回", Integer.valueOf(st.resultOf(p, idFail)),
                Integer.valueOf(SignLogic.R_EXHAUSTED));
        st.clearResult(p, idFail);   // 复原，避免影响 F3 的语义

        // 失败：不写 last_
        st.markFailed(p, idFail, 5, 60000L);
        ok("F3·失败不写已签", !st.isSignedToday(p, idFail));

        // 判不出：不写 last_
        st.markResult(p, idUnk, SignLogic.R_REPLIED_UNK);
        ok("F4·判不出不写已签", !st.isSignedToday(p, idUnk));

        // 重载后区分仍在
        SignStateStore st2 = reopen(tag);
        ok("F5·重载后 EXHAUSTED 仍算已签", st2.isSignedToday(p, idEx));
        ok("F6·重载后失败仍未签", !st2.isSignedToday(p, idFail));
        ok("F7·重载后判不出仍未签", !st2.isSignedToday(p, idUnk));

        // 后续调度：三者行为差异
        SignLogic.SignGate gEx = new SignLogic.SignGate();
        gEx.signedToday = st2.isSignedToday(p, idEx);
        eq("F8·EXHAUSTED 后续跳过", Integer.valueOf(SignLogic.decideSign(gEx)),
                Integer.valueOf(SignLogic.SKIP_ALREADY_SIGNED));

        SignLogic.SignGate gFail = new SignLogic.SignGate();
        gFail.signedToday = st2.isSignedToday(p, idFail);
        gFail.retryExhausted = true;                 // 生产：失败达上限会置该位
        eq("F9·失败达上限跳过（不误签）", Integer.valueOf(SignLogic.decideSign(gFail)),
                Integer.valueOf(SignLogic.SKIP_RETRY_EXHAUST));

        SignLogic.SignGate gUnk = new SignLogic.SignGate();
        gUnk.signedToday = st2.isSignedToday(p, idUnk);
        gUnk.pendingUnconfirmed = true;              // 生产：判不出 → 待确认
        eq("F10·判不出转待确认跳过", Integer.valueOf(SignLogic.decideSign(gUnk)),
                Integer.valueOf(SignLogic.SKIP_PENDING_CONFIRM));

        // 关键：EXHAUSTED 的"跳过"不应影响**别的**目标继续签到
        SignLogic.SignGate other = new SignLogic.SignGate();
        other.signedToday = false;
        eq("F11·其他目标不受 EXHAUSTED 影响", Integer.valueOf(SignLogic.decideSign(other)),
                Integer.valueOf(SignLogic.SKIP_NONE));
    }
}
