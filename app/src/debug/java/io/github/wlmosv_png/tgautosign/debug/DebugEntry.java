package io.github.wlmosv_png.tgautosign.debug;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 【DEBUG 专用】七入口真机验证探针（2026-11-01 阶段 2 补齐）。
 *
 * ┌─ 构建隔离（本类**只存在于 Debug 构建**）────────────────────────────┐
 * │ 本文件位于 app/src/debug/java/，Release 构建的 1-build-dex.sh        │
 * │ **不会**收集这个目录（需显式 TGAS_DEBUG=1）。                        │
 * │ 因此 Release 的 classes.dex 里：                                     │
 * │   · 没有 EntryProbe / DebugEntry 类                                  │
 * │   · 没有 "__selftest" 字面量                                         │
 * │   · 没有本文件的任何 Hook 注册或文件输出逻辑                          │
 * │ 这是**编译期隔离**，不是运行期开关（不使用 static boolean TEST_MODE）。│
 * └──────────────────────────────────────────────────────────────────┘
 *
 * 隔离原则（用户要求）：
 *   · 调用**真实生产判定方法**与**真实包装层**（不 mock Verdict、不复制逻辑）
 *   · 但**不产生真实业务副作用**：用专用测试前缀、测后清理、不改真实目标状态
 *   · 若某入口无法在隔离条件下执行 → 如实记录原因，**不伪造通过**
 */
public final class DebugEntry {

    /** 测试专用键前缀：绝不与真实账号前缀（acc0_/acc3_…）重合。 */
    private static final String PROBE_ACC = "__probe_acc";
    /** 测试专用目标 id：不指向任何真实目标。 */
    private static final String PROBE_TID = "__probe_target_1";
    /** 测试专用对话 id：负数且不在真实会话里。 */
    private static final long PROBE_DID = -999000000001L;

    private static final Object LOCK = new Object();
    private static volatile boolean running = false;

    // ═══════════ 由 main 侧 DebugBridge 反射调用（Release 不会到达）═══════════

    /**
     * @return true = 本命令已被识别并处理；false = 不认识的命令（交回原流程）
     */
    public static boolean run(Object core, String sub) {
        // ── 进程重启持久化验证：两阶段（2026-11-01）──
        // 必须在**两次不同进程**里执行：write 在进程 A，read 在进程 B（冷启动后）。
        if ("__probewrite".equals(sub)) {
            if (core == null) { writeRaw(core, "FAIL: 实例未捕获"); return true; }
            try { probeWrite(core); } catch (Throwable t) { writeRaw(core, "FATAL(write): " + t); }
            return true;
        }
        if ("__proberead".equals(sub)) {
            if (core == null) { writeRaw(core, "FAIL: 实例未捕获"); return true; }
            try { probeRead(core); } catch (Throwable t) { writeRaw(core, "FATAL(read): " + t); }
            return true;
        }
        if ("__probeclean".equals(sub)) {
            try { writeRaw(core, "=== 清理测试数据 ===\n" + cleanup(core)); }
            catch (Throwable t) { writeRaw(core, "FATAL(clean): " + t); }
            return true;
        }
        if ("__probedual".equals(sub)) {
            if (core == null) { writeRaw(core, "FAIL: 实例未捕获"); return true; }
            try { probeDual(core); } catch (Throwable t) { writeRaw(core, "FATAL(dual): " + t + "\n" + stack(t)); }
            return true;
        }
        if (!"__selftest".equals(sub)) return false;
        synchronized (LOCK) {
            if (running) {
                toast(core, "探针正在运行中（防重复锁），请稍候");
                return true;
            }
            running = true;
        }
        try {
            runMatrix(core);
        } catch (Throwable t) {
            write(core, "FATAL: " + t + "\n" + stack(t));
        } finally {
            running = false;
        }
        return true;
    }

    // ═══════════ 测试矩阵 ═══════════

    private static final String[] CORPUS = {
            "签到成功",
            "未签到+1",
            "签到成功了吗？",
            "如果签到成功，将获得奖励",
            "签到冷却中，请8小时后再试",
            "你今天已经签到过了",
            "签到次数已用尽",
            "签到成功，抽奖次数已用尽",
            "上线了赶紧来体验超值福利活动马上开始",
            "今日签到成功！感谢参与！要试试轰炸吗？"
    };

    // ═══════════ 进程重启持久化：两阶段探针 ═══════════
    // 专用隔离标识：绝不与真实账号/目标重合，测后可精确清理。
    private static final String P_ACC_A  = "__probe_acc_A_";
    private static final String P_ACC_B  = "__probe_acc_B_";
    private static final String P_ACC_C1 = "__probe_acc_C1_";
    private static final String P_ACC_C2 = "__probe_acc_C2_";
    private static final String P_ACC_D  = "__probe_acc_D_";
    private static final String P_ACC_E  = "__probe_acc_E_";
    private static final String T_A1 = "__probe_t_A1";
    private static final String T_B1 = "__probe_t_B1";
    private static final String T_C1 = "__probe_t_C1";
    private static final String T_C2 = "__probe_t_C2";
    private static final String T_D1 = "__probe_t_D1";
    private static final String T_E1 = "__probe_t_E1";

    private static Object storeOf(Object core) throws Exception {
        Field f = core.getClass().getDeclaredField("stateStore");
        f.setAccessible(true);
        return f.get(core);
    }

    private static void call(Object st, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = findMethod(st.getClass(), name, sig);
        m.setAccessible(true);
        m.invoke(st, args);
    }

    // ═══════════ P1 · 规则测试器双模式验证 ═══════════

    /** 区分语料：公共词表 / 默认成功失败重复否定词表里都不存在，且不是宽泛动作词。 */
    private static final String DUAL_TID_A = "__probe_dual_tgt_A";
    private static final String DUAL_TID_B = "__probe_dual_tgt_B";
    private static final String DUAL_ENABLED = "__probe_dual_enabled";
    private static final String DUAL_PHRASE_A = "灵石已入库";     // 仅目标 A 词表
    private static final String DUAL_PHRASE_B = "星砂已归位";     // 仅目标 B 词表
    private static final String DUAL_MSG_A = "您的灵石已入库";    // 同一条消息
    private static final String DUAL_MSG_B = "您的星砂已归位";

    private static void probeDual(Object core) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.lang.reflect.Field pf = core.getClass().getDeclaredField("prefs");
        pf.setAccessible(true);
        android.content.SharedPreferences sp = (android.content.SharedPreferences) pf.get(core);
        String prefix = "acc0_";
        int pass = 0, fail = 0;

        sb.append("=== P1 规则测试器双模式验证 ===\n");
        sb.append("PID=").append(android.os.Process.myPid()).append("  时间=").append(nowStr()).append("\n\n");

        // ── 建测试目标 + 写目标级词表（数据隔离：专用 id，测后清理）──
        // 注意：cfg_judge_custom 是 **boolean** 类型 —— 首版用 getString 读，
        // 抛 ClassCastException（探针自身缺陷，生产未受影响）。改为按类型读取。
        boolean hadCustom = sp.contains(prefix + "cfg_judge_custom");
        boolean origCustom = sp.getBoolean(prefix + "cfg_judge_custom", false);
        android.content.SharedPreferences.Editor ed = sp.edit();
        ed.putBoolean(prefix + "cfg_judge_custom", true);          // 打开"使用自定义词"
        // 目标 A
        ed.putString(prefix + "learned_" + DUAL_TID_A, "签到");
        ed.putLong(prefix + "did_" + DUAL_TID_A, -999000000002L);
        ed.putString(prefix + "kind_" + DUAL_TID_A, "text");
        ed.putString("jmb_ok_tgt_" + DUAL_TID_A, DUAL_PHRASE_A);
        // 目标 B
        ed.putString(prefix + "learned_" + DUAL_TID_B, "签到");
        ed.putLong(prefix + "did_" + DUAL_TID_B, -999000000003L);
        ed.putString(prefix + "kind_" + DUAL_TID_B, "text");
        ed.putString("jmb_ok_tgt_" + DUAL_TID_B, DUAL_PHRASE_B);
        ed.commit();
        sb.append("[准备] 已建测试目标 A/B 并写入各自独有的目标级成功短语\n");
        sb.append("  目标A(").append(DUAL_TID_A).append(") 词表=[").append(DUAL_PHRASE_A).append("]\n");
        sb.append("  目标B(").append(DUAL_TID_B).append(") 词表=[").append(DUAL_PHRASE_B).append("]\n");
        sb.append("  区分消息: 「").append(DUAL_MSG_A).append("」\n\n");

        // 词表数量证据
        int pubOk = 0;
        try {
            Class<?> SL = Class.forName("io.github.wlmosv_png.tgautosign.SignLogic", true,
                    core.getClass().getClassLoader());
            pubOk = ((String[]) SL.getField("OK_WORDS_DEFAULT").get(null)).length;
        } catch (Throwable ignored) {}
        int tgtACnt = SignLogicParseCount(sp.getString("jmb_ok_tgt_" + DUAL_TID_A, ""));
        int tgtBCnt = SignLogicParseCount(sp.getString("jmb_ok_tgt_" + DUAL_TID_B, ""));
        sb.append("[词表数量] 公共OK词=").append(pubOk)
          .append("  目标A词=").append(tgtACnt).append("  目标B词=").append(tgtBCnt).append("\n\n");

        // ── 主对比：同一条消息，两种模式 ──
        sb.append("── 主对比（同一消息「").append(DUAL_MSG_A).append("」）──\n");
        String[] pub = callJudgeTest(core, DUAL_MSG_A, null);
        sb.append("  [公共预览]  targetId=null       -> ").append(join(pub)).append("\n");
        String[] tgt = callJudgeTest(core, DUAL_MSG_A, DUAL_TID_A);
        sb.append("  [指定目标]  targetId=").append(DUAL_TID_A).append(" -> ").append(join(tgt)).append("\n");

        boolean pubUnknown = pub[0] != null && pub[0].contains("认不出");
        boolean tgtSigned = tgt[0] != null && tgt[0].contains("已签");
        sb.append("  期望：公共=认不出(UNKNOWN)、指定目标=已签(SIGNED)\n");
        if (pubUnknown) { pass++; sb.append("  ✓ 公共模式 UNKNOWN\n"); }
        else { fail++; sb.append("  ✗ 公共模式非 UNKNOWN：").append(join(pub)).append("\n"); }
        if (tgtSigned) { pass++; sb.append("  ✓ 指定目标 SIGNED\n"); }
        else { fail++; sb.append("  ✗ 指定目标非 SIGNED：").append(join(tgt)).append("\n"); }
        sb.append("  命中词来源：公共模式=").append(pub[1])
          .append("  指定目标=").append(tgt[1]).append("\n\n");

        // ── 负向测试 ──
        sb.append("── 负向测试 ──\n");

        // N1 未选目标只用公共词表（用目标 B 的专属短语，公共模式必须认不出）
        String[] n1 = callJudgeTest(core, DUAL_MSG_B, null);
        boolean n1ok = n1[0] != null && n1[0].contains("认不出");
        sb.append("  N1 未选目标→公共词表：消息「").append(DUAL_MSG_B).append("」-> ")
          .append(n1ok ? "认不出 ✓" : ("**" + join(n1) + "** ✗")).append("\n");
        if (n1ok) pass++; else fail++;

        // N2 选有效目标 → 加载目标级（用 B 的消息 + B 的目标）
        String[] n2 = callJudgeTest(core, DUAL_MSG_B, DUAL_TID_B);
        boolean n2ok = n2[0] != null && n2[0].contains("已签") && n2[0].contains("jmb_ok_tgt")
                || (n2[0] != null && n2[0].contains("已签"));
        sb.append("  N2 选有效目标(B)→加载目标级：-> ").append(join(n2))
          .append(n2ok ? " ✓" : " ✗").append("\n");
        if (n2ok) pass++; else fail++;

        // N3/N4 选不存在的目标 → 明确报错，不静默回退
        String[] n3 = callJudgeTest(core, DUAL_MSG_A, "__probe_dual_tgt_NOTEXIST");
        boolean n3ok = n3[0] != null && n3[0].contains("目标不存在");
        sb.append("  N3/N4 不存在的目标 -> ").append(join(n3))
          .append(n3ok ? " ✓（明确报错、未回退）" : " ✗").append("\n");
        if (n3ok) pass++; else fail++;

        // N5 切换目标后不残留（A 的消息 + B 的目标 → 不应命中 A 的词）
        String[] n5 = callJudgeTest(core, DUAL_MSG_A, DUAL_TID_B);
        boolean n5ok = !(n5[0] != null && n5[0].contains("已签"));
        sb.append("  N5 切换目标后不残留：A的消息+B的目标 -> ").append(join(n5))
          .append(n5ok ? " ✓（未串用）" : " **串用** ✗").append("\n");
        if (n5ok) pass++; else fail++;

        // N6 退出指定模式回公共 → 不残留
        String[] n6 = callJudgeTest(core, DUAL_MSG_A, null);
        boolean n6ok = n6[0] != null && n6[0].contains("认不出");
        sb.append("  N6 退出指定目标回公共 -> ").append(join(n6))
          .append(n6ok ? " ✓（无残留）" : " ✗").append("\n");
        if (n6ok) pass++; else fail++;

        // N7 两个目标之间不串用
        String[] n7a = callJudgeTest(core, DUAL_MSG_B, DUAL_TID_A);
        String[] n7b = callJudgeTest(core, DUAL_MSG_A, DUAL_TID_B);
        boolean n7ok = !(n7a[0] != null && n7a[0].contains("已签"))
                    && !(n7b[0] != null && n7b[0].contains("已签"));
        sb.append("  N7 两目标不串词：B消息+A目标=").append(join(n7a))
          .append(" / A消息+B目标=").append(join(n7b))
          .append(n7ok ? " ✓" : " ✗").append("\n");
        if (n7ok) pass++; else fail++;

        // ── 清理 ──
        sb.append("\n── 清理 ──\n");
        android.content.SharedPreferences.Editor ce = sp.edit();
        ce.remove(prefix + "learned_" + DUAL_TID_A).remove(prefix + "did_" + DUAL_TID_A)
          .remove(prefix + "kind_" + DUAL_TID_A).remove("jmb_ok_tgt_" + DUAL_TID_A)
          .remove(prefix + "learned_" + DUAL_TID_B).remove(prefix + "did_" + DUAL_TID_B)
          .remove(prefix + "kind_" + DUAL_TID_B).remove("jmb_ok_tgt_" + DUAL_TID_B);
        if (hadCustom) ce.putBoolean(prefix + "cfg_judge_custom", origCustom);
        else ce.remove(prefix + "cfg_judge_custom");
        ce.commit();
        int left = 0;
        for (String k : sp.getAll().keySet()) if (k.contains("__probe_dual")) left++;
        sb.append("  清理后残留 __probe_dual 键 = ").append(left).append(left == 0 ? " ✓" : " ✗").append("\n");
        if (left == 0) pass++; else fail++;

        sb.append("\n=== 断言：通过 ").append(pass).append(" / 失败 ").append(fail).append(" ===\n");
        writeRaw(core, sb.toString());
    }

    private static String[] callJudgeTest(Object core, String msg, String targetId) {
        try {
            Field f = core.getClass().getDeclaredField("stateStore");  // 仅为触发类加载
            f.setAccessible(true);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = findMethod(core.getClass(), "judgeReplyForTest",
                    new Class[]{String.class, String.class});
            m.setAccessible(true);
            Object r = m.invoke(core, msg, targetId);
            return (String[]) r;
        } catch (Throwable t) {
            return new String[]{"ERR:" + t, "", "err"};
        }
    }

    private static int SignLogicParseCount(String raw) {
        try {
            Class<?> SL = Class.forName("io.github.wlmosv_png.tgautosign.SignLogic");
            java.lang.reflect.Method m = SL.getMethod("parseExtraWords", String.class);
            Object r = m.invoke(null, raw);
            return r == null ? 0 : ((String[]) r).length;
        } catch (Throwable t) { return -1; }
    }

    private static String join(String[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(" | "); sb.append(a[i]); }
        return sb.toString();
    }

    /** 阶段一：写入 5 个场景（在**进程 A**）。 */
    private static void probeWrite(Object core) throws Exception {
        Object st = storeOf(core);
        long pid = android.os.Process.myPid();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 阶段一 · 写入（进程 A）===\n");
        sb.append("PID=").append(pid).append("\n");
        sb.append("时间=").append(nowStr()).append("\n\n");

        // A 正常已签
        call(st, "markSigned", new Class[]{String.class, String.class, String.class}, P_ACC_A, T_A1, "probe-A");
        sb.append("[A] markSigned(").append(P_ACC_A).append(T_A1).append(")\n");

        // B 已签 + 后续 UNKNOWN（不得覆盖）
        call(st, "markSigned", new Class[]{String.class, String.class, String.class}, P_ACC_B, T_B1, "probe-B");
        call(st, "markResult", new Class[]{String.class, String.class, int.class}, P_ACC_B, T_B1,
                Integer.valueOf(SignLogicConst.R_REPLIED_UNK(core)));
        sb.append("[B] markSigned + markResult(REPLIED_UNK)\n");

        // C 多账号隔离（同 id 跨账号也可以）
        call(st, "markSigned", new Class[]{String.class, String.class, String.class}, P_ACC_C1, T_C1, "probe-C1");
        call(st, "markResult", new Class[]{String.class, String.class, int.class}, P_ACC_C2, T_C2,
                Integer.valueOf(SignLogicConst.R_FAILED(core)));
        sb.append("[C] accC1 已签 / accC2 失败\n");

        // D EXHAUSTED
        call(st, "markSigned", new Class[]{String.class, String.class, String.class}, P_ACC_D, T_D1, "probe-D");
        call(st, "markResult", new Class[]{String.class, String.class, int.class}, P_ACC_D, T_D1,
                Integer.valueOf(SignLogicConst.R_EXHAUSTED(core)));
        sb.append("[D] markSigned + markResult(EXHAUSTED)\n");

        // E 撤销
        call(st, "markSigned", new Class[]{String.class, String.class, String.class}, P_ACC_E, T_E1, "probe-E");
        call(st, "markUnsigned", new Class[]{String.class, String.class}, P_ACC_E, T_E1);
        sb.append("[E] markSigned → markUnsigned（撤销）\n");

        sb.append("\n写入后即时读回（同进程）：\n");
        sb.append(describeState(st, "同进程"));
        sb.append("\n→ 阶段一结束。请杀进程后执行 __probe_read。\n");
        writeRaw(core, sb.toString());
    }

    /** 阶段二：读取（在**进程 B**，冷启动后）。 */
    private static void probeRead(Object core) throws Exception {
        Object st = storeOf(core);
        long pid = android.os.Process.myPid();
        StringBuilder sb = new StringBuilder();
        int pass = 0, fail = 0;

        sb.append("=== 阶段二 · 读取（进程 B，冷启动后）===\n");
        sb.append("PID=").append(pid).append("\n");
        sb.append("时间=").append(nowStr()).append("\n\n");
        sb.append(describeState(st, "新进程"));

        // ── 断言 ──
        boolean aSigned = (Boolean) call2(st, "isSignedToday", new Class[]{String.class, String.class}, P_ACC_A, T_A1);
        sb.append("\n[A] 正常已签：").append(aSigned ? "仍为 SIGNED ✓" : "**丢失** ✗").append("\n");
        if (aSigned) pass++; else fail++;

        boolean bSigned = (Boolean) call2(st, "isSignedToday", new Class[]{String.class, String.class}, P_ACC_B, T_B1);
        sb.append("[B] UNKNOWN 未覆盖已签：").append(bSigned ? "已签仍在 ✓" : "**被覆盖** ✗").append("\n");
        if (bSigned) pass++; else fail++;

        boolean c1 = (Boolean) call2(st, "isSignedToday", new Class[]{String.class, String.class}, P_ACC_C1, T_C1);
        boolean c2 = (Boolean) call2(st, "isSignedToday", new Class[]{String.class, String.class}, P_ACC_C2, T_C2);
        sb.append("[C] 多账号隔离：C1已签=").append(c1).append(" C2未签=").append(!c2)
          .append((c1 && !c2) ? " ✓" : " **串数据** ✗").append("\n");
        if (c1 && !c2) pass++; else fail++;

        boolean dSigned = (Boolean) call2(st, "isSignedToday", new Class[]{String.class, String.class}, P_ACC_D, T_D1);
        sb.append("[D] EXHAUSTED：重载后仍为已签态=").append(dSigned)
          .append(dSigned ? " ✓（未被映射成 UNKNOWN/FAILED）" : " ✗").append("\n");
        if (dSigned) pass++; else fail++;

        boolean eSigned = (Boolean) call2(st, "isSignedToday", new Class[]{String.class, String.class}, P_ACC_E, T_E1);
        sb.append("[E] 撤销：重载后仍为未签=").append(!eSigned)
          .append(!eSigned ? " ✓（旧记录未被复原）" : " **旧记录回来了** ✗").append("\n");
        if (!eSigned) pass++; else fail++;

        // ── 调度行为（生产纯函数）──
        sb.append("\n调度行为（SignLogic.decideSign）：\n");
        try {
            Class<?> SL = Class.forName("io.github.wlmosv_png.tgautosign.SignLogic", true,
                    core.getClass().getClassLoader());
            Class<?> G = Class.forName("io.github.wlmosv_png.tgautosign.SignLogic$SignGate", true,
                    core.getClass().getClassLoader());
            Object g = G.getDeclaredConstructor().newInstance();
            G.getField("signedToday").set(g, Boolean.valueOf(aSigned));
            Method ds = SL.getMethod("decideSign", G);
            Object r = ds.invoke(null, g);
            Field f = SL.getField("SKIP_ALREADY_SIGNED");
            boolean skip = f.getInt(null) == ((Integer) r).intValue();
            sb.append("  已签目标 -> ").append(skip ? "SKIP_ALREADY_SIGNED ✓（不会重复安排）" : ("code=" + r)).append("\n");
            if (skip) pass++; else fail++;
        } catch (Throwable t) { sb.append("  调度断言失败: ").append(t).append("\n"); fail++; }

        sb.append("\n=== 断言：通过 ").append(pass).append(" / 失败 ").append(fail).append(" ===\n");
        writeRaw(core, sb.toString());
    }

    private static Object call2(Object st, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = findMethod(st.getClass(), name, sig);
        m.setAccessible(true);
        return m.invoke(st, args);
    }

    /** 直接读 store 的当前状态快照（同时打印真实 prefs 键值，作为落盘证据）。 */
    private static String describeState(Object st, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 状态快照（").append(tag).append("）---\n");
        String[][] items = {
                {"accA", P_ACC_A, T_A1}, {"accB", P_ACC_B, T_B1},
                {"accC1", P_ACC_C1, T_C1}, {"accC2", P_ACC_C2, T_C2},
                {"accD", P_ACC_D, T_D1}, {"accE", P_ACC_E, T_E1}
        };
        for (String[] it : items) {
            try {
                boolean s = (Boolean) call2(st, "isSignedToday", new Class[]{String.class, String.class},
                        it[1], it[2]);
                sb.append("  ").append(it[0]).append(" isSignedToday=").append(s).append("\n");
            } catch (Throwable t) { sb.append("  ").append(it[0]).append(" ERR ").append(t).append("\n"); }
        }
        return sb.toString();
    }

    /** 写原始文本（不经过 outDir 之外的加工）。 */
    private static void writeRaw(Object core, String text) {
        write(core, text);
    }

    /**
     * 结果码：**反射读真实常量**（不硬编码）。
     * 阶段 2 修过一个"往返不闭合"缺陷（resultOfCode 缺 exhausted 分支），
     * 若这里再写死数字，测试本身就会变成第二个隐患。
     */
    private static final class SignLogicConst {
        static int of(Object core, String name) {
            try {
                Class<?> SL = Class.forName("io.github.wlmosv_png.tgautosign.SignLogic", true,
                        core.getClass().getClassLoader());
                return SL.getField(name).getInt(null);
            } catch (Throwable t) { return -1; }
        }
        static int R_FAILED(Object c)      { return of(c, "R_FAILED"); }
        static int R_REPLIED_UNK(Object c) { return of(c, "R_REPLIED_UNK"); }
        static int R_EXHAUSTED(Object c)   { return of(c, "R_EXHAUSTED"); }
    }

    private static void runMatrix(Object core) {
        StringBuilder sb = new StringBuilder();
        List<String> json = new ArrayList<String>();

        sb.append("=== TGAutoSign 七入口真机探针 ===\n");
        sb.append("时间=").append(nowStr()).append("\n");
        sb.append("实例=").append(core).append("\n\n");

        // ── 0. 前置：真实实例与生产方法可用性 ──
        sb.append("[0] 前置检查\n");
        sb.append("  TGAutoSignCore 实例: ").append(core != null ? "已捕获" : "**未捕获**").append("\n");
        if (core == null) {
            sb.append("  → 结论：实例未就绪，测试不执行。\n");
            write(core, sb.toString());
            return;
        }
        Class<?> cc = core.getClass();
        sb.append("  类: ").append(cc.getName()).append("\n\n");

        // ── A/B：底层判定内核 ──
        sb.append("[A] verdictDetailScoped（生产判定内核·scoped）\n");
        for (String msg : CORPUS) {
            String r = invokeStatic2(core, "io.github.wlmosv_png.tgautosign.SignLogic",
                    "verdictDetailScoped", msg);
            sb.append("  ").append(pad(msg)).append(" => ").append(r).append("\n");
        }
        sb.append("\n[B] verdictDetail（生产判定内核·内置链）\n");
        for (String msg : CORPUS) {
            String r = invokeDetail(core, msg);
            sb.append("  ").append(pad(msg)).append(" => ").append(r).append("\n");
        }

        // ── C/D/E：包装层（judgeWithScopes 是 D/E 的公共包装核心）──
        sb.append("\n[C/D/E] judgeWithScopes（包装层：按账号读开关 + 组装三级词表）\n");
        for (String msg : CORPUS) {
            String r = invokeJudgeWithScopes(core, PROBE_ACC + "_", PROBE_DID, PROBE_DID, PROBE_TID, msg);
            sb.append("  ").append(pad(msg)).append(" => ").append(r).append("\n");
            json.add("{\"entry\":\"C/D/E\",\"msg\":\"" + esc(msg) + "\",\"targetId\":\"" + PROBE_TID + "\",\"verdict\":\"" + esc(r) + "\"}");
        }

        // ── F：规则测试器完整包装层 ──
        sb.append("\n[F] judgeReplyForTest（规则测试器完整包装层）\n");
        sb.append("  注意：该入口内部传 targetId=null → **目标级词表：未加载**\n");
        for (String msg : CORPUS) {
            String r = invokeJudgeForTest(core, msg);
            sb.append("  ").append(pad(msg)).append(" => ").append(r).append("\n");
            json.add("{\"entry\":\"F\",\"msg\":\"" + esc(msg) + "\",\"targetId\":null,\"targetWordsLoaded\":false,\"verdict\":\"" + esc(r) + "\"}");
        }

        // ── F' 对照：同一消息带 targetId 走 judgeWithScopes ──
        sb.append("\n[F'] 对照：同一消息经 judgeWithScopes 并**传入** targetId（目标级词表：已加载）\n");
        for (String msg : CORPUS) {
            String r = invokeJudgeWithScopes(core, PROBE_ACC + "_", PROBE_DID, PROBE_DID, PROBE_TID, msg);
            sb.append("  ").append(pad(msg)).append(" => ").append(r).append("\n");
        }

        // ── E：handleReplyText 完整包装层（状态写入隔离到专用前缀）──
        sb.append("\n[E] handleReplyText（锚点回复完整包装层，状态写入隔离到专用前缀）\n");
        for (String msg : CORPUS) {
            String r = invokeHandleReplyText(core, msg);
            sb.append("  ").append(pad(msg)).append(" => ").append(r).append("\n");
        }

        // ── G：宽松模式 ──
        sb.append("\n[G] 宽松模式判定（verdictDetail with WORDS_NONE）\n");
        for (String msg : CORPUS) {
            String r = invokeLoose(core, msg);
            sb.append("  ").append(pad(msg)).append(" => ").append(r).append("\n");
        }

        // ── 状态副作用核查 ──
        sb.append("\n[S] 副作用核查（真实目标是否被污染）\n");
        sb.append("  ").append(checkNoPollution(core)).append("\n");

        // ── 清理 ──
        sb.append("\n[X] 清理测试数据\n");
        sb.append("  ").append(cleanup(core)).append("\n");

        sb.append("\n=== 结束 ===\n");
        write(core, sb.toString());
        writeJson(core, json);
    }

    // ═══════════ 各入口的真实调用 ═══════════

    /** A：SignLogic.verdictDetailScoped(reply, OK默认, FAIL默认, null,null,null,null) */
    private static String invokeStatic2(Object core, String cls, String method, String msg) {
        try {
            Class<?> SL = Class.forName(cls, true, core.getClass().getClassLoader());
            Field okF = SL.getField("OK_WORDS_DEFAULT");
            Field failF = SL.getField("FAIL_WORDS_DEFAULT");
            String[] ok = (String[]) okF.get(null);
            String[] fail = (String[]) failF.get(null);
            Method m = SL.getMethod(method, String.class, String[].class, String[].class,
                    String[].class, String[].class, String[].class, String[].class);
            Object[] vd = (Object[]) m.invoke(null, msg, ok, fail, null, null, null, null);
            return describe(vd);
        } catch (Throwable t) { return "ERR:" + t; }
    }

    /** B：SignLogic.verdictDetail(reply, null, null, null) */
    private static String invokeDetail(Object core, String msg) {
        try {
            Class<?> SL = Class.forName("io.github.wlmosv_png.tgautosign.SignLogic", true,
                    core.getClass().getClassLoader());
            Method m = SL.getMethod("verdictDetail", String.class, String[].class,
                    String[].class, String[].class);
            Object[] vd = (Object[]) m.invoke(null, msg, null, null, null);
            return describe(vd);
        } catch (Throwable t) { return "ERR:" + t; }
    }

    /** C/D/E 包装核心：TGAutoSignCore.judgeWithScopes(prefix,did,botDid,targetId,reply) */
    private static String invokeJudgeWithScopes(Object core, String prefix, long did,
                                                long botDid, String targetId, String msg) {
        try {
            Method m = findMethod(core.getClass(), "judgeWithScopes",
                    new Class[]{String.class, long.class, long.class, String.class, String.class});
            m.setAccessible(true);
            Object[] vd = (Object[]) m.invoke(core, prefix, did, botDid, targetId, msg);
            return describe(vd) + "   [targetId=" + targetId + " → 目标级词表已加载]";
        } catch (Throwable t) { return "ERR:" + t; }
    }

    /** F：TGAutoSignCore.judgeReplyForTest(reply) —— 规则测试器完整包装层 */
    private static String invokeJudgeForTest(Object core, String msg) {
        try {
            Method m = findMethod(core.getClass(), "judgeReplyForTest", new Class[]{String.class});
            m.setAccessible(true);
            Object r = m.invoke(core, msg);
            String s = "返回 " + stringify(r);
            return s + "   [targetId=null → 目标级词表：未加载]";
        } catch (Throwable t) { return "ERR:" + t; }
    }

    /**
     * E：TGAutoSignCore.handleReplyText(prefix, account, Map, text) —— 锚点回复完整包装层。
     * 用一个**合成目标条目**（PROBE_TID），因此任何状态写入都落在 __probe_ 前缀上，
     * 不会触碰真实目标。执行后由 cleanup() 统一清除。
     */
    private static String invokeHandleReplyText(Object core, String msg) {
        try {
            java.util.Map<String, Object> entry = new java.util.HashMap<String, Object>();
            entry.put("id", PROBE_TID);
            entry.put("dialogId", Long.valueOf(PROBE_DID));
            entry.put("text", "签到");
            entry.put("kind", "text");
            Method m = findMethod(core.getClass(), "handleReplyText",
                    new Class[]{String.class, int.class, java.util.Map.class, String.class});
            m.setAccessible(true);
            m.invoke(core, PROBE_ACC + "_", Integer.valueOf(0), entry, msg);
            return "已进入包装层（无异常）";
        } catch (Throwable t) { return "ERR:" + t; }
    }

    /** G：宽松模式 —— SignLogic.verdictDetail(reply, null, WORDS_NONE, FAIL默认) */
    private static String invokeLoose(Object core, String msg) {
        try {
            Class<?> SL = Class.forName("io.github.wlmosv_png.tgautosign.SignLogic", true,
                    core.getClass().getClassLoader());
            String[] none = (String[]) SL.getField("WORDS_NONE").get(null);
            String[] fail = (String[]) SL.getField("FAIL_WORDS_DEFAULT").get(null);
            Method m = SL.getMethod("verdictDetail", String.class, String[].class,
                    String[].class, String[].class);
            Object[] vd = (Object[]) m.invoke(null, msg, null, none, fail);
            return describe(vd);
        } catch (Throwable t) { return "ERR:" + t; }
    }

    // ═══════════ 副作用核查与清理 ═══════════

    /** 核查：真实账号前缀下是否出现了测试目标 id（应为 0）。 */
    private static String checkNoPollution(Object core) {
        try {
            SharedPreferences sp = prefsOf(core);
            int polluted = 0;
            StringBuilder detail = new StringBuilder();
            for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String k = e.getKey();
                if (k.contains(PROBE_TID) && !k.startsWith(PROBE_ACC)) {
                    polluted++;
                    if (polluted <= 5) detail.append("\n    ! ").append(k);
                }
            }
            return polluted == 0
                    ? "真实前缀下未发现测试目标键 ✓（0 处）"
                    : "**污染 " + polluted + " 处**" + detail;
        } catch (Throwable t) { return "核查失败: " + t; }
    }

    /** 清理：删除所有 __probe 前缀的键。 */
    private static String cleanup(Object core) {
        try {
            SharedPreferences sp = prefsOf(core);
            SharedPreferences.Editor ed = sp.edit();
            int n = 0;
            for (String k : new ArrayList<String>(sp.getAll().keySet())) {
                if (k.startsWith(PROBE_ACC) || k.contains(PROBE_TID)
                        || k.contains("__probe_")) {
                    ed.remove(k); n++;
                }
            }
            ed.commit();
            return "已清除 " + n + " 个测试键";
        } catch (Throwable t) { return "清理失败: " + t; }
    }

    // ═══════════ 工具 ═══════════

    private static SharedPreferences prefsOf(Object core) throws Exception {
        Field f = core.getClass().getDeclaredField("prefs");
        f.setAccessible(true);
        return (SharedPreferences) f.get(core);
    }

    private static Method findMethod(Class<?> c, String name, Class<?>[] sig) throws Exception {
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != sig.length) continue;
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                if (!ps[i].getName().equals(sig[i].getName())) { ok = false; break; }
            }
            if (ok) return m;
        }
        throw new NoSuchMethodException(name);
    }

    private static String describe(Object[] vd) {
        if (vd == null || vd.length == 0) return "null";
        int code = vd[0] instanceof Integer ? (Integer) vd[0] : -1;
        String hit = vd.length > 1 ? stringify(vd[1]) : "";
        return name(code) + "  hit=\"" + hit + "\"";
    }

    private static String name(int v) {
        switch (v) {
            case 0: return "SIGNED";
            case 1: return "FAILED";
            case 2: return "UNKNOWN";
            case 3: return "EXHAUSTED";
            default: return "code=" + v;
        }
    }

    private static String stringify(Object o) {
        if (o == null) return "null";
        if (o instanceof Object[]) {
            Object[] a = (Object[]) o;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(","); sb.append(a[i]); }
            return sb.append("]").toString();
        }
        return String.valueOf(o);
    }

    private static String pad(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += (c >= 0x2E80) ? 2 : 1;
        }
        StringBuilder sb = new StringBuilder(s);
        while (w < 30) { sb.append(' '); w++; }
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String nowStr() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /**
     * 输出目录（2026-11-01）。
     *
     * 为什么不用 /sdcard 根目录：Android 11+ 分区存储下，宿主进程写 `/sdcard/`
     * 需要 MANAGE_EXTERNAL_STORAGE，普通应用会**静默失败** ——
     * 实测首次触发时命令被正确拦截（界面显示"发送错误"即拦截成功）、
     * 但结果文件一个字节都没写出来，就是栽在这里。
     * 改用宿主自己的 app 专属外部目录（无需任何权限），root 侧可直接读取。
     */
    private static java.io.File outDir(Object core) {
        try {
            java.lang.reflect.Field f = core.getClass().getDeclaredField("appContext");
            f.setAccessible(true);
            Object ctx = f.get(core);
            java.lang.reflect.Method m = ctx.getClass().getMethod("getExternalFilesDir", String.class);
            Object dir = m.invoke(ctx, (String) null);
            if (dir instanceof java.io.File) return (java.io.File) dir;
        } catch (Throwable ignored) {}
        return new java.io.File("/sdcard");   // 退路
    }

    private static void write(Object core, String text) {
        try {
            java.io.File d = outDir(core);
            if (!d.exists()) d.mkdirs();
            java.io.File out = new java.io.File(d, "tgas-entry-probe.txt");
            java.io.FileWriter fw = new java.io.FileWriter(out, false);
            try { fw.write(text); } finally { fw.close(); }
            android.util.Log.i("TGAutoSignModule", "[EntryProbe] 已写入 " + out.getAbsolutePath());
        } catch (Throwable t) {
            try { android.util.Log.e("TGAutoSignModule", "[EntryProbe] 写文件失败: " + t); }
            catch (Throwable ignored) {}
        }
    }

    private static void writeJson(Object core, List<String> items) {
        try {
            StringBuilder sb = new StringBuilder("{\n  \"probe\": [\n");
            for (int i = 0; i < items.size(); i++) {
                sb.append("    ").append(items.get(i));
                if (i < items.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ]\n}\n");
            java.io.File d = outDir(core);
            if (!d.exists()) d.mkdirs();
            java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(d, "tgas-entry-probe.json"), false);
            try { fw.write(sb.toString()); } finally { fw.close(); }
        } catch (Throwable t) {
            try { android.util.Log.e("TGAutoSignModule", "[EntryProbe] 写 JSON 失败: " + t); }
            catch (Throwable ignored) {}
        }
    }

    private static void toast(Object core, String s) {
        try {
            android.util.Log.i("TGAutoSignModule", "[EntryProbe] " + s);
        } catch (Throwable ignored) {}
    }
}
