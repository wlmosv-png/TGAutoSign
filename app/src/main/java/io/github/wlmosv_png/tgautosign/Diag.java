package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接收链诊断层（2026-11-08 · F0–F7）。
 *
 * 设计原则（用户 2026-11-08 授权书）：
 *   1. **纯观测**：只统计、只输出，绝不参与任何判定；不读写签到状态。
 *   2. **可测试**：本类不依赖 Android，纯 JVM 可执行 —— 测试必须真跑，不接受"读代码通过"。
 *   3. **失败可观察**：日志写入失败必须计数，不能静默假装成功。
 *   4. **不记正文**：事件行绝不含消息文本、密码、Token。
 *   5. 诊断限频**不得**影响业务过滤 —— 限频只发生在"要不要记这一行"。
 *
 * 输出走 {@link Sink}（由宿主注入 jlog 的 WARN 级接口，保证落盘）。
 */
public final class Diag {

    private Diag() { }

    /** 输出通道。level：2=OK，3=WARN（WARN 在宿主日志里必定落盘）。 */
    public interface Sink { void write(int level, String line); }

    private static volatile Sink sink;
    private static volatile int pid = 0;
    private static volatile boolean enabled = true;

    public static void setSink(Sink s) { sink = s; }
    public static void setPid(int p) { pid = p; }
    public static void setEnabled(boolean e) { enabled = e; }
    public static boolean enabled() { return enabled; }

    private static void emit(int level, String line) {
        if (!enabled) return;
        Sink s = sink;
        if (s == null) return;
        try { s.write(level, line); } catch (Throwable t) { noteWriteFailure(); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  统一事件 schema（字段固定顺序；不含消息正文）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 一条结构化事件。
     *
     * @param eventType  send / recv / drop / verdict / timeout / ui / retry / hook / round / upd
     * @param entryPoint A / A2 / B / B2 / C / UI / OTHER  —— 每条链路独立可辨
     */
    public static void event(String eventType, String entryPoint,
                             long accountId, long peerId, long fromId, int messageId, int roundId,
                             String previousState, String nextState,
                             String dropReason, String hookStatus) {
        StringBuilder sb = new StringBuilder(220);
        sb.append("[evt2] type=").append(nz(eventType))
          .append(" entry=").append(nz(entryPoint))
          .append(" pid=").append(pid)
          .append(" t=").append(System.currentTimeMillis())
          .append(" acc=").append(accountId)
          .append(" peer=").append(peerId)
          .append(" from=").append(fromId)
          .append(" mid=").append(messageId)
          .append(" round=").append(roundId)
          .append(" prev=").append(nz(previousState))
          .append(" next=").append(nz(nextState))
          .append(" drop=").append(nz(dropReason))
          .append(" hook=").append(nz(hookStatus));
        emit(3, sb.toString());
    }

    private static String nz(String s) { return (s == null || s.length() == 0) ? "-" : s; }

    // ══════════════════════════════════════════════════════════════════
    //  F0 · Hook 生命周期
    // ══════════════════════════════════════════════════════════════════

    public static final class HookRec {
        public final String id, target;
        public volatile boolean classResolved, methodFound, installed;
        public volatile int overloads;
        public volatile String chosen = "-";
        public volatile String err = "-";
        public volatile long installedAt = 0L;
        public final AtomicLong fired = new AtomicLong(0L);
        HookRec(String id, String target) { this.id = id; this.target = target; }
        public boolean triggered() { return fired.get() > 0L; }
    }

    private static final Map<String, HookRec> HOOKS = new ConcurrentHashMap<String, HookRec>();
    private static final AtomicLong INSTALL_CALLS = new AtomicLong(0L);

    /** 记录一次 Hook 目标解析结果。 */
    public static void hookTarget(String id, String target, boolean classResolved,
                                  boolean methodFound, int overloads, String chosen) {
        HookRec r = hook(id, target);
        r.classResolved = classResolved;
        r.methodFound = methodFound;
        r.overloads = overloads;
        r.chosen = chosen == null ? "-" : chosen;
        emit(3, "[hook] id=" + id + " phase=target class=" + (classResolved ? "ok" : "MISSING")
                + " method=" + (methodFound ? "ok" : "MISSING") + " overloads=" + overloads
                + " chosen=" + r.chosen + " target=" + nz(target));
    }

    /** 记录一次 Hook 安装结果。失败必须带原因，且不可被描述为"已覆盖"。 */
    public static void hookInstall(String id, String target, boolean ok, String err) {
        HookRec r = hook(id, target);
        r.installed = ok;
        r.err = ok ? "-" : nz(err);
        r.installedAt = System.currentTimeMillis();
        INSTALL_CALLS.incrementAndGet();
        emit(3, "[hook] id=" + id + " phase=install result=" + (ok ? "OK" : "FAIL")
                + " err=" + r.err + " target=" + nz(target));
    }

    /** 记录一次 Hook 实际触发（调用点内第一行调用）。 */
    public static void hookFired(String id) {
        HookRec r = HOOKS.get(id);
        if (r == null) { r = hook(id, "?"); }
        r.fired.incrementAndGet();
    }

    public static String hookStatus(String id) {
        HookRec r = HOOKS.get(id);
        if (r == null) return "UNKNOWN";
        if (!r.installed) return "HOOK_NOT_INSTALLED";
        return r.triggered() ? "HOOK_FIRED" : "HOOK_SILENT";
    }

    public static boolean isInstalled(String id) {
        HookRec r = HOOKS.get(id);
        return r != null && r.installed;
    }

    public static long installCalls() { return INSTALL_CALLS.get(); }

    public static HookRec hook(String id, String target) {
        HookRec r = HOOKS.get(id);
        if (r == null) {
            synchronized (HOOKS) {
                r = HOOKS.get(id);
                if (r == null) { r = new HookRec(id, target); HOOKS.put(id, r); }
            }
        }
        return r;
    }

    public static Map<String, HookRec> hooks() { return new LinkedHashMap<String, HookRec>(HOOKS); }

    /** F0 要求的表格：Hook | 目标方法 | 解析 | 安装 | 实际触发 | 证据。 */
    public static String hookTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("hook表: id | target | class | method | overloads | chosen | installed | fired | 判定\n");
        for (HookRec r : hooks().values()) {
            sb.append("  ").append(r.id)
              .append(" | ").append(r.target)
              .append(" | ").append(r.classResolved ? "ok" : "MISS")
              .append(" | ").append(r.methodFound ? "ok" : "MISS")
              .append(" | ").append(r.overloads)
              .append(" | ").append(r.chosen)
              .append(" | ").append(r.installed ? "OK" : "FAIL(" + r.err + ")")
              .append(" | ").append(r.fired.get())
              .append(" | ").append(hookStatus(r.id))
              .append("\n");
        }
        sb.append("  hook安装调用次数=").append(INSTALL_CALLS.get());
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════
    //  F4 · update 类型直方图
    // ══════════════════════════════════════════════════════════════════

    private static final Map<String, AtomicLong> UPD = new ConcurrentHashMap<String, AtomicLong>();

    /** 在类型过滤**之前**调用，记录真实收到的 update 类名（不含正文）。 */
    public static void noteUpdateType(String className) {
        String k = className == null ? "null" : shortName(className);
        AtomicLong c = UPD.get(k);
        if (c == null) { c = new AtomicLong(0L); UPD.put(k, c); }
        c.incrementAndGet();
    }

    public static String shortName(String cn) {
        if (cn == null) return "null";
        int i = Math.max(cn.lastIndexOf('.'), cn.lastIndexOf('$'));
        return i < 0 ? cn : cn.substring(i + 1);
    }

    public static Map<String, Long> updateHistogram() {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, AtomicLong> e : UPD.entrySet()) out.put(e.getKey(), e.getValue().get());
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  F5 · 早退原因计数
    // ══════════════════════════════════════════════════════════════════

    private static final Map<String, AtomicLong> DROPS = new ConcurrentHashMap<String, AtomicLong>();

    public static void noteDrop(String reason) {
        String k = reason == null ? "unknown" : reason;
        AtomicLong c = DROPS.get(k);
        if (c == null) { c = new AtomicLong(0L); DROPS.put(k, c); }
        c.incrementAndGet();
    }

    public static Map<String, Long> dropCounters() {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, AtomicLong> e : DROPS.entrySet()) out.put(e.getKey(), e.getValue().get());
        return out;
    }

    public static long dropCount(String reason) {
        AtomicLong c = DROPS.get(reason);
        return c == null ? 0L : c.get();
    }

    // ══════════════════════════════════════════════════════════════════
    //  F6 · 限频（保留限频，但必须统计被抑制数量）
    // ══════════════════════════════════════════════════════════════════

    private static final Map<String, long[]> THROTTLE = new ConcurrentHashMap<String, long[]>();
    private static final AtomicLong THROTTLED = new AtomicLong(0L);

    /** @return true = 允许记录；false = 被抑制（已计数）。 */
    public static boolean allow(String key, long windowMs) {
        long now = System.currentTimeMillis();
        long[] v = THROTTLE.get(key);
        if (v == null) { v = new long[]{0L, 0L}; THROTTLE.put(key, v); }
        synchronized (v) {
            if (now - v[0] < windowMs) { v[1]++; THROTTLED.incrementAndGet(); return false; }
            v[0] = now;
            return true;
        }
    }

    public static long suppressedCount() { return THROTTLED.get(); }

    // ══════════════════════════════════════════════════════════════════
    //  日志写入失败可观察
    // ══════════════════════════════════════════════════════════════════

    private static final AtomicLong WRITE_FAIL = new AtomicLong(0L);
    private static final AtomicLong WRITE_OK = new AtomicLong(0L);

    public static void noteWriteFailure() { WRITE_FAIL.incrementAndGet(); }
    public static void noteWriteOk() { WRITE_OK.incrementAndGet(); }
    public static long writeFailures() { return WRITE_FAIL.get(); }
    public static long writeOk() { return WRITE_OK.get(); }

    // ══════════════════════════════════════════════════════════════════
    //  汇总（周期性输出，便于一眼看出"哪条链没动"）
    // ══════════════════════════════════════════════════════════════════

    public static String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[evt2] type=SUMMARY pid=").append(pid).append(" t=").append(System.currentTimeMillis());
        if (!enabled) sb.append(" enabled=0");
        sb.append("\n  update类型: ").append(fmt(updateHistogram()));
        sb.append("\n  丢弃原因: ").append(fmt(dropCounters()));
        sb.append("\n  限频抑制=").append(THROTTLED.get())
          .append("  日志写入成功=").append(WRITE_OK.get())
          .append("  失败=").append(WRITE_FAIL.get());
        sb.append("\n").append(hookTable());
        return sb.toString();
    }

    private static String fmt(Map<String, Long> m) {
        if (m.isEmpty()) return "(空)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 周期汇总输出（走 WARN，必落盘）。 */
    public static void flushSummary() {
        emit(3, summary());
    }

    /** 仅供测试：清空所有状态。 */
    public static void resetForTest() {
        HOOKS.clear(); UPD.clear(); DROPS.clear(); THROTTLE.clear();
        INSTALL_CALLS.set(0L); THROTTLED.set(0L);
        WRITE_FAIL.set(0L); WRITE_OK.set(0L);
        pid = 0;
    }

    /** 供测试取全部 Hook id（有序）。 */
    public static List<String> hookIds() { return new ArrayList<String>(hooks().keySet()); }
}
