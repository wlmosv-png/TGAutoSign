package io.github.wlmosv_png.tgautosign;

/**
 * 签到音效的**纯逻辑**闸门（2026-10-25）。
 *
 * 为什么单独成类：音效最怕「反复出现」—— 一天响十几次比不响更烦。
 * 而这套判据（开关 / 时段 / 静音 / 当天是否已响）全是纯逻辑，
 * 必须能单测 —— 播放与落盘留给 TGAutoSignCore，本类不碰 Android。
 */
public final class SoundGate {

    /** 当天**首次**签到成功。 */
    public static final int KIND_FIRST_SIGN = 0;
    /** 当天**全部了结**（所有目标都有结论）。 */
    public static final int KIND_ROUND_DONE = 1;

    // ── 音色档位（SOUND_STYLE）────────────────────────────
    // 0/1/2 三个老档位的语义与声音**不变**（用户已存的配置不受影响）；
    // 3 是 2026-10-27 新增的自研音，也是"没设置过"时的默认值。
    /** 系统通知音（跟随用户自己的铃声）。 */
    public static final int STYLE_SYSTEM = 0;
    /** 清脆：ToneGenerator ACK。 */
    public static final int STYLE_CHIME = 1;
    /** 低音：ToneGenerator BEEP2。 */
    public static final int STYLE_LOW = 2;
    /** 自研「玻璃完成音」（assets/sign_done.ogg）。 */
    public static final int STYLE_GLASS = 3;

    private SoundGate() {}

    /**
     * 时段判定，**支持跨天**（from > to，例如 22:00 → 08:00）。
     * from == to 视为「全天」——用户把两个框填一样时按不限制处理，比拒绝更符合直觉。
     */
    public static boolean inWindow(int nowMin, int fromMin, int toMin) {
        if (fromMin < 0 || fromMin > 1439) fromMin = 0;
        if (toMin < 0 || toMin > 1439) toMin = 1439;
        if (fromMin == toMin) return true;
        if (fromMin < toMin) return nowMin >= fromMin && nowMin < toMin;
        return nowMin >= fromMin || nowMin < toMin;
    }

    /**
     * 是否允许播放。纯判定，**不落盘、不占位**（那两件事由调用方做）。
     *
     * @param masterOn    总开关
     * @param kindOn      该时机是否启用（收尾音可以单独关）
     * @param playedToday 今天该账号该时机是否已经响过
     * @param silent      当前静音/勿打扰
     */
    public static boolean allow(boolean masterOn, boolean kindOn, boolean playedToday,
                                int nowMin, int fromMin, int toMin, boolean silent) {
        if (!masterOn || !kindOn) return false;
        if (playedToday) return false;      // «一天一次» 的核心
        if (silent) return false;
        return inWindow(nowMin, fromMin, toMin);
    }

    /** "HH:MM" / "H：MM"（全角冒号）→ 当日分钟；非法返回 def。 */
    public static int parseHM(String s, int def) {
        try {
            if (s == null) return def;
            String t = s.trim();
            if (t.length() == 0) return def;
            int c = t.indexOf(':');
            if (c < 0) c = t.indexOf('\uFF1A');
            if (c < 0) return def;
            int h = Integer.parseInt(t.substring(0, c).trim());
            int m = Integer.parseInt(t.substring(c + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return def;
            return h * 60 + m;
        } catch (Throwable t) { return def; }
    }

    /** 分钟 → "HH:MM"。 */
    public static String fmtHM(int min) {
        if (min < 0) min = 0;
        if (min > 1439) min = 1439;
        return String.format("%02d:%02d", min / 60, min % 60);
    }

    /** 音色档位名（设置页按钮用）。 */
    public static String styleName(int style) {
        if (style == STYLE_CHIME) return "清脆";
        if (style == STYLE_LOW) return "低音";
        if (style == STYLE_GLASS) return "完成音";
        return "系统通知音";
    }

    public static int nextStyle(int style) {
        if (style <= STYLE_SYSTEM) return STYLE_CHIME;
        if (style == STYLE_CHIME) return STYLE_LOW;
        if (style == STYLE_LOW) return STYLE_GLASS;
        return STYLE_SYSTEM;
    }
}
