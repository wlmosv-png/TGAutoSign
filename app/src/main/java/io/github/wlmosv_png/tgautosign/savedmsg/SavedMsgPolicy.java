package io.github.wlmosv_png.tgautosign.savedmsg;

import io.github.wlmosv_png.tgautosign.SignLogic;

/**
 * 「收藏夹简报」的**账号级**决策与**发送拦截**（2026-10-27，C 批）。
 *
 * 为什么单独成类：原来的判定散在 Core 里，靠一个内存字段 + 当前账号的 prefs 键，
 * 无法在 JVM 上验证 —— 而线上出的正是「关掉收藏夹还在发简报」这种只有
 * 多账号 + 遍历所有账号才会暴露的问题。
 * 这里把三件事收敛到**一处**，并且依赖全部可注入：
 *   ① key 拼接（acc{N}_cfg_saved_msg / 全局 jmb_saved_msg）
 *   ② 取值优先级（本账号 → 全局 → 默认）—— 复用 SignLogic 的纯函数，不另立一套
 *   ③ 发送拦截（不允许就不调用发送器，任何入口都拦得住）
 *
 * 生产用法（Core）：Flags 由 SharedPreferences 实现；
 * 测试用法：Flags 由 Map 实现，Sender 由记录器实现 —— 于是「决策链 + 拦截路径」
 * 可以在 JVM 上被真实执行，而不是只靠静态检查。
 *
 * 注意：本类**不碰 Android API**，也不做 I/O。
 */
public final class SavedMsgPolicy {

    private SavedMsgPolicy() {}

    /** 配置名后缀（与设置页 putCfg(ed,"saved_msg",v) 一致）。 */
    public static final String CFG_NAME = "saved_msg";
    /** 全局兜底键（历史数据 / 迁移）。 */
    public static final String GLOBAL_KEY = "jmb_saved_msg";
    /** 代码默认值：历史默认「开」。 */
    public static final boolean DEFAULT_ON = true;

    /** 只读配置源：key → 值；**键不存在必须返回 null**（不能返回 false）。 */
    public interface Flags {
        Boolean find(String key);
    }

    /** 发送器替身/真实发送：返回是否真的发出。 */
    public interface Sender {
        boolean send(String text, int account);
    }

    /** 某账号的账号级键名：acc{N}_cfg_saved_msg。 */
    public static String accountKey(int account) {
        return "acc" + account + "_cfg_" + CFG_NAME;
    }

    /** 某账号「收藏夹摘要」是否开启（默认值取 DEFAULT_ON）。 */
    public static boolean savedMsgOn(Flags f, int account) {
        return savedMsgOn(f, account, DEFAULT_ON);
    }

    /**
     * 某账号「收藏夹摘要」是否开启。
     *
     * 优先级：本账号键 → 全局 jmb_ 键 → def。
     * **绝不读取其它账号的键** —— 这是本类存在的根本原因（跨账号串扰）。
     */
    public static boolean savedMsgOn(Flags f, int account, boolean def) {
        Boolean accountVal = (f == null) ? null : f.find(accountKey(account));
        Boolean globalVal = (f == null) ? null : f.find(GLOBAL_KEY);
        return SignLogic.resolveSavedMsgOn(accountVal, globalVal, def);
    }

    /**
     * 该账号的每日摘要是否可发 = 账号级收藏夹开关 ∧ 通知总开关。
     * （notifyOn 的语义与历史一致：开着「签到结果通知」才有摘要。）
     */
    public static boolean canSendSummary(Flags f, int account, boolean notifyOn) {
        return SignLogic.canSendSavedSummary(savedMsgOn(f, account), notifyOn);
    }

    /**
     * 真实的发送拦截路径：不允许 → **不调用 sender**，返回 false。
     * 允许且 sender 为 null 时返回 true（只有判定、不真发，供纯决策验证）。
     */
    public static boolean deliver(Flags f, int account, boolean notifyOn, String text, Sender s) {
        if (!canSendSummary(f, account, notifyOn)) return false;
        if (s == null) return true;
        return s.send(text, account);
    }
}
