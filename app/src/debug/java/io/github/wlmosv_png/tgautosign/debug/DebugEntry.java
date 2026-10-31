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
                if (k.startsWith(PROBE_ACC) || k.contains(PROBE_TID)) {
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
