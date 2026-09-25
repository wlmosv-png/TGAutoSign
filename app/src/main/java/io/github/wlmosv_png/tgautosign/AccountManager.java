package io.github.wlmosv_png.tgautosign;

import java.lang.reflect.Field;

/**
 * 账号上下文管理（Refactor 1.6.1）。
 *
 * 解决的问题（今天的核心病灶）：
 *   TGAutoSignCore 里有 75 处 currentAccount()、67 处无参 accountPrefix()。
 *   无参版读的是**宿主静态字段 UserConfig.selectedAccount**，它在多账号下
 *   随时可能变（用户切号、别的代码临时改写）。
 *   任何异步路径（postDelayed / 网络回调 / 面板事件）里读它都可能拿到**别的账号**：
 *     - armTask 延迟 1~5 分钟
 *     - 面板刷新事件最长 25 秒
 *     - 回调 delegate 的 post
 *   今天的 5 个串号补丁都是"在某处锁定账号"，属于打补丁 —— 新加功能还会犯。
 *
 * 本类的设计：
 *   1. {@link #current()} 读宿主值，做**边界钳制**（越界→最后一个合法索引，
 *      负值→0），并缓存 last raw 值供诊断。
 *   2. {@link #ctx(int)} 生成一个**不可变的账号上下文**，异步任务必须持有它，
 *      而不是在执行时再读 current()。
 *
 * 未来：调用方从 sendSign(entry, currentAccount()) 改为 sendSign(entry, ctx)，
 * 编译器即可挡住"忘记传账号"。
 *
 * 注意：本类不做任何 prefs 读写（保持可测）。
 */
public final class AccountManager {

    /** 宿主 UserConfig 的反射句柄（延迟解析，失败时走回退路径）。 */
    private final ClassLoader hostLoader;
    private Class<?> userConfigClass;

    /** 最近一次读到的原始值（未钳制），供诊断包展示。 */
    private volatile int lastRaw = -1;
    /** 越界告警去重。 */
    private volatile String lastWarnSig = "";

    /** 回调：把越界告警交给外部（写日志）。可为 null。 */
    public interface Warner { void warnOutOfRange(int raw, int total, int used); }
    private Warner warner;

    public AccountManager(ClassLoader hostLoader) {
        this.hostLoader = hostLoader;
    }

    public void setWarner(Warner w) { this.warner = w; }

    public int lastRaw() { return lastRaw; }

    /** 越界模拟（排障用）。由外部开关控制；true 时把读取值替换为"必然越界"的 total。 */
    private volatile boolean overflowSim = false;
    public void setOverflowSim(boolean on) { this.overflowSim = on; }
    public boolean overflowSim() { return overflowSim; }

    private Class<?> uc() {
        if (userConfigClass == null) {
            try {
                userConfigClass = Class.forName("org.telegram.messenger.UserConfig", false, hostLoader);
            } catch (Throwable t) { userConfigClass = null; }
        }
        return userConfigClass;
    }

    /** 已激活账号数。反射失败时**扫 prefs 的形状**由外部注入（见 setCountFallback）。 */
    public interface CountProvider { int activatedAccounts(); }
    private CountProvider countProvider;
    public void setCountProvider(CountProvider p) { this.countProvider = p; }

    public int activatedAccounts() {
        try {
            Class<?> c = uc();
            if (c != null) {
                Object n = c.getMethod("getActivatedAccountsCount").invoke(null);
                if (n instanceof Number) {
                    int v = ((Number) n).intValue();
                    if (v > 0) return v;
                }
            }
        } catch (Throwable ignored) {}
        if (countProvider != null) {
            try { int v = countProvider.activatedAccounts(); if (v > 0) return v; } catch (Throwable ignored) {}
        }
        return 1;
    }

    /**
     * 当前账号索引（已钳制）。规则见类注释。
     * 越界 → total-1（宿主写越界值时意图通常是"刚登录/刚切换的那个"，其索引最大）；
     * 负值 → 0；total 不可信（<=1 却读到正数）→ 按原值（宁可按用户当前账号，也不丢回账号1）。
     */
    public int current() {
        try {
            int c;
            if (overflowSim) {
                c = activatedAccounts();   // total 本身即越界
            } else {
                Class<?> cls = uc();
                if (cls == null) return 0;
                Field f;
                try {
                    f = cls.getField("selectedAccount");
                } catch (Throwable t) {
                    f = cls.getDeclaredField("selectedAccount");
                    f.setAccessible(true);
                }
                Object v = f.get(null);
                c = ((Number) v).intValue();
            }
            lastRaw = c;
            int total = activatedAccounts();
            // 决策核心在 SignLogic（纯逻辑 + 单测覆盖多账号边界）
            int used = SignLogic.clampAccount(c, total);
            if (SignLogic.accountClamped(c, total)) warn(c, total, used);
            return used;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void warn(int raw, int total, int used) {
        String sig = raw + "/" + total;
        if (sig.equals(lastWarnSig)) return;
        lastWarnSig = sig;
        if (warner != null) {
            try { warner.warnOutOfRange(raw, total, used); } catch (Throwable ignored) {}
        }
    }

    /** 账号前缀，如 "acc2_"。 */
    public String prefix(int account) { return "acc" + account + "_"; }

    /** 带账户上下文的不可变快照。异步任务**必须**持有它。 */
    public static final class Ctx {
        public final int account;
        public final String prefix;
        public final long capturedAt;
        public final String reason;   // 谁发起的（排障用）

        Ctx(int account, String prefix, String reason) {
            this.account = account;
            this.prefix = prefix;
            this.capturedAt = System.currentTimeMillis();
            this.reason = reason == null ? "" : reason;
        }
        @Override public String toString() { return "Ctx(acc" + account + ", from=" + reason + ")"; }
    }

    /** 捕获当前账号上下文。*/
    public Ctx capture(String reason) {
        int a = current();
        return new Ctx(a, prefix(a), reason);
    }

    /** 用指定账号构造上下文（调用方已知账号时优先用这个）。 */
    public Ctx ctxOf(int account, String reason) {
        return new Ctx(account, prefix(account), reason);
    }
}
