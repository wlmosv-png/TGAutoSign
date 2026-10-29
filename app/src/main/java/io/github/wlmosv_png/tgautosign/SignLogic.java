package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 签到相关的**纯逻辑**，从 TGAutoSignCore 里抽出来。
 *
 * 为什么要抽（2026-09-23）：
 *   Core 有 8400+ 行，纯逻辑和 Android/Hook 代码混在一起，**根本没法写测试**。
 *   而今天三个线上事故（按钮学习默认值、账号越界、捕获无效）全是
 *   「代码里有、但从没验证过行为」—— 静态检查一条都拦不住。
 *   这些函数没有副作用、不碰 Context，可以在 JVM 上直接跑断言。
 *
 * 约定：本类**不加 Android / Xposed 依赖**，只能放纯函数。
 * 修改行为前先看 tests/ 里对应的用例。
 */
public final class SignLogic {

    private SignLogic() {}

    // ─────────────────────── 时间与窗口 ───────────────────────

    /** "HH:MM" → 当日分钟数；非法返回 -1。 */
    public static int parseHM(String s) {
        try {
            String[] p = s.split(":");
            if (p.length != 2) return -1;
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Throwable t) { return -1; }
    }

    /** 分钟数 → "HH:MM"。 */
    public static String hhmm(int min) {
        return String.format("%02d:%02d", min / 60, min % 60);
    }

    /**
     * "08:30-20:30" → [开始, 结束]（当日分钟）。
     * 非法或 结束 < 开始 返回 null（跨天窗口由调用方另行处理）。
     */
    public static int[] windowRangeOf(String w) {
        try {
            if (w == null) return null;
            String t = w.trim();
            if (t.isEmpty()) return null;
            String[] p = t.split("-");
            if (p.length != 2) return null;
            int a = parseHM(p[0].trim());
            int b = parseHM(p[1].trim());
            if (a < 0 || b < 0 || b < a) return null;
            return new int[]{a, b};
        } catch (Throwable t) { return null; }
    }

    /**
     * 解析窗口，**支持跨天**（如 "22:00-02:00" 表示当晚 22:00 到次日 02:00）。
     *
     * 返回 {start, end, crossesMidnight}；跨天时 end < start（end 表示次日钟点）。
     * 非法返回 null。
     *
     * 为什么需要这个：windowRangeOf() 对跨天窗口返回 null，而下游两处对 null 的
     * 理解**不一致** —— inWindow 把 null 当"不限"、ensureTimerPlan 把 null 当
     * "不排期"，导致跨天窗口下定时签到完全失效，非定时也可能全天签到。
     */
    public static int[] windowRangeAny(String w) {
        try {
            if (w == null) return null;
            String t = w.trim();
            if (t.isEmpty()) return null;
            String[] p = t.split("-");
            if (p.length != 2) return null;
            int a = parseHM(p[0].trim());
            int b = parseHM(p[1].trim());
            if (a < 0 || b < 0) return null;
            return new int[]{a, b, b < a ? 1 : 0};
        } catch (Throwable t) { return null; }
    }

    /** 窗口是否跨天。 */
    public static boolean crossesMidnight(int[] any) {
        return any != null && any.length >= 3 && any[2] == 1;
    }

    /** 当前分钟是否落在窗口内 —— 支持跨天。any 来自 windowRangeAny。 */
    public static boolean inWindowAny(int nowMin, int[] any) {
        if (any == null) return true;                 // 窗口为空/非法 = 不限
        int a = any[0], b = any[1];
        if (crossesMidnight(any)) return nowMin >= a || nowMin <= b;
        return nowMin >= a && nowMin <= b;
    }

    /** 当天第一分钟起算的「分钟轴偏移」。
     *  跨天窗口当作 [start, 1440+end] 来算，便于均分排期；非跨天就是 [start, end]。 */
    public static int[] windowSpanAny(int[] any) {
        if (any == null) return null;
        int a = any[0];
        int b = crossesMidnight(any) ? any[1] + 24 * 60 : any[1];
        return new int[]{a, b};
    }

    /**
     * 解析「某个目标的固定签到时刻」应该落在哪一分钟（2026-10-21）。
     *
     * 规则：
     *   ① 用户在窗口内指定了时间 → 就用它
     *   ② 指定时间早于窗口开始   → 夹到窗口开始
     *   ③ 指定时间晚于窗口结束   → 夹到窗口结束前一分钟
     *   ④ 窗口非法/为空（不限）  → 原样使用
     *   ⑤ 跨天窗口（如 22:00-02:00）→ 落在 [start,1440) ∪ [0,end] 内即算窗口内
     *
     * @param wantMin  用户指定的分钟（0..1439）
     * @param winAny   窗口解析结果（windowRangeAny 的返回，可为 null）
     * @return {实际分钟, 是否被夹取}；wantMin 非法（<0）时返回 {0,false} 由调用方忽略
     *
     * 抽成纯函数的理由：夹取逻辑最容易写错（跨天窗口尤其），必须能单测。
     */
    public static int[] resolveSignMinute(int wantMin, int[] winAny) {
        if (wantMin < 0) return new int[]{0, 0};
        int want = wrapMinute(wantMin);
        if (winAny == null) return new int[]{want, 0};          // 窗口不限
        int a = winAny[0], b = winAny[1];
        boolean cross = crossesMidnight(winAny);

        // 已在窗口内 → 原样使用
        if (inWindowAny(want, winAny)) return new int[]{want, 0};

        // 不在窗口内 → 夹到最近的边界
        if (!cross) {
            if (want < a) return new int[]{a, 1};
            // want > b
            return new int[]{wrapMinute(b - 1 < 0 ? 1439 : b - 1), 1};
        }
        // 跨天窗口：窗口是 [a,1440) ∪ [0,b]
        // 落在中间空档 (b, a) —— 取离哪边近
        int distToStart = Math.abs(want - a);
        int distToEnd = Math.abs(want - b);
        return distToStart <= distToEnd ? new int[]{a, 1}
                                        : new int[]{wrapMinute(b - 1 < 0 ? 1439 : b - 1), 1};
    }

    /** 把窗口内的分钟轴偏移折回 0..1439（跨天窗口会跨过午夜）。 */
    public static int wrapMinute(int min) {
        int m = min % (24 * 60);
        return m < 0 ? m + 24 * 60 : m;
    }

    /** 当前分钟是否落在窗口内（含端点）。 */
    public static boolean inWindow(int nowMin, int[] range) {
        if (range == null) return true;
        return nowMin >= range[0] && nowMin <= range[1];
    }

    /**
     * 补签时段判定：[窗口开始, 补签截止]，与签到窗口解耦。
     * 截止早于窗口开始 = 跨到次日（如窗口 20:00-23:00、截止 06:00）。
     */
    /**
     * 两个时间戳是否属于同一天（本地时区）。
     *
     * 用途：未识别回复的入库门槛从「TTL 内」放宽到「当天发过请求」时要判断跨天。
     * 纯函数、无 Android 依赖。
     */
    public static boolean sameDay(long a, long b) {
        try {
            java.util.Calendar ca = java.util.Calendar.getInstance();
            ca.setTimeInMillis(a);
            java.util.Calendar cb = java.util.Calendar.getInstance();
            cb.setTimeInMillis(b);
            return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
                && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR);
        } catch (Throwable t) { return false; }
    }

    public static boolean inMissBackTime(int nowMin, int[] range, int missDeadline, boolean missBackOn) {
        if (!missBackOn) return false;
        int start = range != null ? range[0] : 0;
        if (missDeadline >= start) return nowMin >= start && nowMin <= missDeadline;
        return nowMin >= start || nowMin <= missDeadline;
    }

    // ─────────────────── 自动签到触发策略（2026-10-30，事故驱动） ───────────────────
    //
    // 事故：用户「我上线它不签到，只能打开面板手动一键签」。
    // 旧实现：自动路径被「窗口/补签时段」硬门禁，窗口外（尤其凌晨）完全静默；
    // 非定时模式下的「打开 TG 时补签」又调 kickSchedule()，在非定时模式下必然空转。
    //
    // 本函数只回答一个问题：**时段上该不该动**（立即 / 稍后 / 排队 / 不动）。
    // 账号启用、绑定、网络、退避、发送上限、在途、待确认等保护**不在这里**，
    // 一律由 skipScheduling / decideSign 负责，且自动与手动共用同一实现。

    /** 立即执行（窗口内）。 */
    public static final int T_RUN_NOW  = 0;
    /** 立即执行，补签语义（窗口结束后到补签截止之间）。 */
    public static final int T_RUN_LATE = 1;
    /** 持久化排队到下一个合法时刻，本次不发（窗口开始前 / 截止后）。 */
    public static final int T_QUEUE    = 2;
    /** 本周期无事可做 / 冷却未到 / 明确不该动。 */
    public static final int T_SKIP     = 3;

    /** decideTrigger 的输入快照（全部按**账号**维度填，不得使用全局单值）。 */
    public static final class TriggerCtx {
        public boolean timerEnabled;          // 本账号「定时签到」
        public boolean missBackOn;            // 本账号「错过补签」
        public int[]   windowAny;             // 本账号窗口（windowRangeAny，可跨天；null = 不限）
        public int     missDeadlineMin;       // 本账号补签截止（分钟）
        public int     nowMin;                // 设备本地时间（分钟）
        public boolean startupTrigger;        // 本次是否「刚打开宿主」
        public boolean catchupOnOpen;         // 本账号「打开 TG 时补签」
        public boolean pendingToday = true;   // 本周期仍有关注目标（未签且未在途且未待确认）
        public long    sinceLastAutoCheckMs = -1L;   // 距上次自动检查；<0 = 从未查过
        public long    autoCooldownMs;        // 自动检查冷却（0 = 不限）
    }

    /**
     * 四态决策。判定优先级（先命中者胜）：
     *   1. pendingToday == false    → T_SKIP（已签完 / 在途 / 待确认，无事可做）
     *   2. 窗口内（含窗口不限）      → T_RUN_NOW（窗口内到点不受冷却限制）
     *   3. 窗口外且冷却未到          → T_SKIP
     *   4. 定时关                    → T_RUN_NOW（用户语义：任何时间上线都该试）
     *   5. 定时开 + 补签时段         → T_RUN_LATE
     *   6. 其余（窗口前 / 截止后）   → T_QUEUE（排队，不在本次发送）
     *
     * 窗口解析失败（非法格式）按「不限」处理，与 inWindowAny(null)==true 的既有语义一致，
     * 避免用户把窗口写错就永远不动。
     */
    public static int decideTrigger(TriggerCtx c) {
        if (c == null) return T_SKIP;
        if (!c.pendingToday) return T_SKIP;

        int[] any = c.windowAny;
        // 窗口内：立即执行，不受冷却限制（冷却只用于抑制窗口外的重复探测）
        if (any == null || inWindowAny(c.nowMin, any)) return T_RUN_NOW;

        // 窗口外：先看自动检查冷却（触发合并，防止切后台再回来反复触发）
        if (c.autoCooldownMs > 0L && c.sinceLastAutoCheckMs >= 0L
                && c.sinceLastAutoCheckMs < c.autoCooldownMs) return T_SKIP;

        // 定时关闭 = 用户选择「任何时间上线都应尝试」
        if (!c.timerEnabled) return T_RUN_NOW;

        // 定时开启 = 用户选择「只在窗口（含补签）内动作」
        if (inMissBackTime(c.nowMin, any, c.missDeadlineMin, c.missBackOn)) return T_RUN_LATE;
        return T_QUEUE;
    }

    /** 队列语义：下一个合法执行时刻（分钟）。用于日志与 next_allowed_at_ 计算。 */
    public static int nextAllowedMin(TriggerCtx c) {
        if (c == null) return -1;
        int[] any = c.windowAny;
        if (any == null) return -1;
        return any[0];     // 定时开 + 窗口前 / 截止后 → 等下一个窗口开始
    }

    // ─────────────────── 合并日志限频（2026-10-30 P2） ───────────────────
    //
    // 事故：原 enqueueTry 有 queueLogAt 60 秒限频，改用 markEnqueueFor 后丢了限频，
    // 而 markEnqueueFor 现在被**所有** RUN_NOW 触发源调用（含每次打开 TG 的 onResume）
    // —— 同类噪音在历史上曾达单日 229 条、占日志 36%（用户明确要求降噪）。
    //
    // 只抑制「[排队] … 合并」这一条日志：不涉及任何发送、失败、成功证据。
    // 作用域 = 每账号；窗口 = 60 秒（ENQUEUE_LOG_WINDOW_MS）；并发由调用方串行化保证。

    /** 合并日志的限频窗口（毫秒）；<=0 表示不限频。 */
    public static final long ENQUEUE_LOG_WINDOW_MS = 60_000L;

    /**
     * 合并日志限频判定（纯函数，便于单测）。
     *
     * @param lastMs   上次记录时刻（毫秒）；<=0 表示从未记录过
     * @param nowMs    当前时刻（毫秒）
     * @param windowMs 限频窗口（毫秒）；<=0 表示不限频
     * @return true = 本次允许记录
     */
    public static boolean enqueueLogAllowed(long lastMs, long nowMs, long windowMs) {
        if (windowMs <= 0L) return true;
        if (lastMs <= 0L) return true;
        return nowMs - lastMs >= windowMs;
    }

    // ─────────────────────── 重试退避 ───────────────────────

    /** 单目标单日失败次数上限：达到即冻结到明天（防"成功清零/失败+1"震荡导致的反复重签）。 */
    public static final int FAILS_PER_DAY_LIMIT = 3;

    private static final long[] BACKOFF = {
            5L * 60 * 1000, 15L * 60 * 1000, 45L * 60 * 1000,
            2L * 60 * 60 * 1000, 4L * 60 * 60 * 1000
    };

    /** 第 retries 次失败后的等待毫秒数；超出表长按最后一档。 */
    public static long backoffDelay(int retries) {
        int idx = retries < 0 ? 0 : (retries < BACKOFF.length ? retries : BACKOFF.length - 1);
        return BACKOFF[idx];
    }

    // ─────────────────────── ID 规范化 ───────────────────────

    /**
     * 把用户粘贴的各种写法规范化成纯数字 ID：
     * 保留数字，各种连字符（- − － – —）统一成 '-'，其余字符全部丢弃。
     * 负号只保留开头一个（群/频道 ID 是负数）。
     */
    public static String normalizeId(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (ch == '-' || ch == '\u2212' || ch == '\uFF0D' || ch == '\u2013' || ch == '\u2014') sb.append('-');
        }
        String v = sb.toString();
        if (v.startsWith("-")) v = "-" + v.substring(1).replace("-", "");
        else v = v.replace("-", "");
        // 只有一个负号（用户粘了 "-" 或 "－"）时返回空串：调用方普遍拿结果去
        // Long.parseLong，返回 "-" 会抛 NumberFormatException 被静默吞掉，
        // 表现为"填了 ID 但什么都没发生"。返回空串让调用方走"无效输入"分支。
        if ("-".equals(v)) return "";
        return v;
    }

    // ─────────────────────── 回调 data 标签 ───────────────────────

    /** 回调 data 解码成可读标签：可打印 ASCII 直接用，否则退化成 base64 前缀。 */
    public static String cbDataLabel(byte[] d) {
        if (d == null || d.length == 0) return "回调按钮";
        try {
            String utf8 = new String(d, "UTF-8");
            boolean printable = true;
            for (int i = 0; i < utf8.length(); i++) {
                char ch = utf8.charAt(i);
                if (ch < 0x20 || ch > 0x7E) { printable = false; break; }
            }
            if (printable && utf8.trim().length() > 0) {
                String t = utf8.trim();
                return t.length() > 24 ? t.substring(0, 24) + "\u2026" : t;
            }
        } catch (Throwable ignored) {}
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(d);
            return b64.length() > 12 ? "回调:" + b64.substring(0, 12) : "回调:" + b64;
        } catch (Throwable ignored) {}
        return "回调按钮";
    }

    // ─────────────────────── 回复语义判定 ───────────────────────

    /** 判定结果。 */
    /**
     * 「重试也不会成功」的确定性失败词。
     *
     * 这些情形与"签到时机不对"不同 —— 用户没关注 bot、活动结束了、账号没资格，
     * 再签一百次也是同一句话。命中即当日冻结，不再进重试队列。
     *
     * 必须与 FAIL_WORDS_DEFAULT 有交集：判定顺序是先查本表、再查失败表。
     * 现场（2026-09-25）：目标 7719383660 因 bot 先回"正在签到"（算成功、retry 清零）
     * 再回"请先关注"（算失败、retry 只加到 1），在 0/1 之间震荡，一天被签了 8 次。
     */
    public static final String[] PERMANENT_FAIL_WORDS = {
            "请先关注", "未关注", "没有资格", "活动已结束", "已过期",
            "请先开始", "not allowed"
    };

    /**
     * 该命中词是否属于「确定性失败」。
     * 传入的是 verdictDetail 返回的命中词，不是整段回复。
     */
    /**
     * 「今日已用尽」类回复 —— bot 明确表示今天不能再操作了。
     *
     * 背景（2026-10-01 用户实测）：
     *   某群 bot 对重复签到回「❌ 您本日的规则触发数量上限，请明日再试」。
     *   这句既不含签到词（looksLikeSignResult=false），三张词表也不命中 →
     *   verdictDetail 返回 V_UNKNOWN → 不写 kLast、不涨 retry、不熔断 →
     *   心跳每 45 秒重发一次，10 分钟后才转「待确认」，用户看到群里被刷 13 条。
     *
     * 语义区分（关键）：
     *   · PERMANENT_FAIL_WORDS（请先关注/活动已结束）—— **条件不满足**，
     *     今天再怎么试也没用，且**不是**成功。要撤销已签、计失败。
     *   · 本表（次数上限/请明日再试）—— **今日额度用尽**，
     *     说明今天已经操作过了（很可能就是你手动签的）。既不该计失败，
     *     也不该继续发。归入「今日已了结」，安静收工。
     *
     * 判定顺序：必须排在 PERMANENT_FAIL_WORDS 之后、成功/重复词之前 ——
     * 它同时含"已/用尽"语义，若排在成功词后会与组合判定打架。
     *
     * ⚠️ 收录词必须**只表达"今天没了"**，不能是"明天还能来"这种中性的：
     *   单测实测「您已签到，明天再来」是**成功**语（bot 签到成功后附带的话），
     *   最初收了"明天再来"→ 把成功的判成"用尽"，332/334 单测直接挂 2 条。
     *   同理不要收「明日再来」「明天再试」这类 —— 它们与成功语高度重叠。
     *   保留的「请明日再试」带了"请…再试"，是明确的拒绝语气，与成功语不冲突。
     */
    public static final String[] EXHAUSTED_WORDS = {
            // 中文：额度/次数用尽
            "次数已达上限", "次数已用完", "次数用完", "已达上限", "已达今日上限",
            "达到上限", "达到每日上限", "达到今日上限", "已达到上限", "已达到每日上限",
            "今日上限", "本日上限", "今日次数", "本日次数",
            "触发数量上限", "数量上限", "操作上限", "超过限制", "超出限制",
            "请明日再试", "请明天再试", "明日再试", "明天再试",
            "今日已结束", "本日已结束", "今天已结束",
            // 英文
            "daily limit", "limit reached", "reached the limit", "try tomorrow",
            "come back tomorrow", "tomorrow again", "no more attempts", "quota",
    };

    /**
     * 该回复是否表示「今日已用尽」。
     *
     * 与 isPermanentFail 一样，入参可以是整段回复（内部自行小写匹配），
     * 便于调用方在 verdictDetail 之外独立判断。
     */
    public static boolean isExhausted(String reply) {
        if (reply == null || reply.length() == 0) return false;
        String h = reply.toLowerCase(java.util.Locale.US);
        for (String w : EXHAUSTED_WORDS) {
            if (w != null && h.contains(w.toLowerCase(java.util.Locale.US))) return true;
        }
        return false;
    }

    public static boolean isPermanentFail(String hitWord) {
        if (hitWord == null || hitWord.length() == 0) return false;
        String h = hitWord.toLowerCase();
        for (String w : PERMANENT_FAIL_WORDS) {
            if (w != null && h.contains(w.toLowerCase())) return true;
        }
        return false;
    }

    public static final int V_SIGNED = 0;   // 成功（或 bot 说已签过，等价于今天签过了）
    public static final int V_FAILED = 1;   // 明确失败 → 撤销已签 + 退避重试
    public static final int V_UNKNOWN = 2;  // 认不出来 → 不猜，留痕
    /**
     * 今日已用尽（次数上限/请明日再试）→ **今日了结**，不再重发，不计失败。
     *
     * 为什么不并入 V_SIGNED：用户可能真的没签上（额度被别人/别处用掉了），
     * 直接标"已签"是撒谎。为什么不并入 V_FAILED：这不是失败，
     * 计进 fail_streak 会污染"连续失败 3 天"告警。
     * 单独一类，界面显示「今日已了结」，语义准确。
     */
    public static final int V_EXHAUSTED = 3;

    /**
     * 关键词表。用户可在「设置 → 回复判定词」里追加，默认是下面这些。
     * 注意顺序：**先判「已签过」**，因为「已签到」里也含「签到」，
     * 若先判成功会把"已签过"误判成"刚签成功"（历史上踩过）。
     */
    public static final String[] DUP_WORDS_DEFAULT = {
            // 中文：把「今天已签到」的各种常见写法都覆盖掉，避免用户还要自己加词。
            // 注意顺序无关（都是子串匹配），但**别放太短的宽词**：
            // 曾经有 "重复" 一条，会把「请勿重复提交」也判成已签，已移除。
            "今日已签", "今天已签", "本日已签", "您已签", "你已签", "已签到", "已经签",
            "已领取", "已参与", "已打卡", "已签过", "重复签到",
            "已完成了签到", "签到已完成", "今天已经签到",
            // 2026-09-30 扩充：用户反馈「明明签上了却判不出」，多为下列措辞未覆盖
            "今日已签过", "已签到过", "已经签到", "已经打卡", "已打卡过",
            "今日签到已完成", "今日打卡完成", "今天已完成", "本日已完成",
            "您今天已经签到", "你今天已经签到", "今日已领取", "已成功签到",
            "签到已成功", "打卡已成功",
            // 英文
            "already", "repeated", "again later", "already signed", "already checked",
            "checked in", "signed today"
    };
    public static final String[] OK_WORDS_DEFAULT = {
            "签到成功", "打卡成功", "成功签到", "领取成功", "发送成功", "签到完成", "打卡完成",
            "签到获得", "获得积分", "success", "claimed", "check-in complete",
            // 2026-09-30 扩充：常见变体
            "签到已完成", "打卡已完成", "完成签到", "完成打卡", "签到完毕", "打卡完毕",
            "已签到成功", "签到奖励", "获得奖励", "领取完成", "已获得", "成功打卡",
            "恭喜签到", "恭喜打卡", "签到 +1", "打卡 +1", "积分 +"
    };
    public static final String[] FAIL_WORDS_DEFAULT = {
            "签到失败", "打卡失败", "未签到成功", "未成功", "活动已结束", "已过期",
            "未关注", "没有资格", "请先关注", "请先开始", "请重新签到",
            "failed", "invalid", "rejected", "not allowed", "try again", "not signed",
            // ── 前置条件未满足（2026-09-28 补）──
            // 实测：宽松模式下「⚠️ 请先加入以下1个频道才能使用功能」被判成功 ——
            // 那是明确的拒绝，用户压根没签到。同类还有「请先绑定或注册账号」。
            // 这类措辞的共同点是**要求用户先做某事**，属于功能性拒绝而非业务结果。
            "请先加入", "加入频道", "请先绑定", "请先注册", "未绑定", "未注册",
            // 2026-10-14：否定词与动词之间常插字（「还没有绑定」不含连续子串「未绑定」），
            // 单测实测漏判 → 归 UNKNOWN。这里补齐常见写法。
            "还没有绑定", "没有绑定", "还没绑定", "还没有注册", "没有注册", "还没注册",
            "还没有关注", "没有关注", "还没关注", "还没有加入", "没有加入", "还没加入",
            // 2026-10-06 P0：封禁/禁止类 —— 条件不满足且今日无解，归永久失败。
            "禁止签到", "禁止", "被封", "封禁", "已拉黑", "拉黑",
            "请先验证", "无权限", "没有权限", "暂无权限", "不可用", "暂未开放",
            "please join", "not linked", "not registered", "no permission",
            // 2026-09-30 补：否定词 + 签到动词的常见组合。
            // 单测发现「没有签到成功」会被成功词表的「签到成功」命中 —— 否定必须显式列出。
            "没有签到成功", "没有成功", "未签到成功", "签到未成功", "打卡未成功",
            "没有完成签到", "没有打卡成功", "未能签到", "签到未能", "未完成签到"
    };

    /**
     * 按关键词判定 bot 回复。
     *
     * @param reply    bot 回复原文
     * @param dup/ok/fail 关键词表（可为 null 表示用默认）
     * @return V_SIGNED / V_FAILED / V_UNKNOWN
     *
     * 抽成纯函数的意义：以前三张表都不命中时**静默返回**，用户看到的现象是
     * 「点了、发出去了、没反应」，且没有任何日志。现在返回 V_UNKNOWN，
     * 调用方必须显式处理（记日志 + 诊断包留痕）。
     */
    // ════════════════════════════════════════════════════════════════
    //  导航按钮识别（2026-09-30）
    //
    //  背景：2026-09-28 把「按钮性质过滤」整体移除，改为「点什么学什么」——
    //    起因是那套启发式（data 前缀黑名单、随机 hex、文案黑名单）**猜**得太凶，
    //    把真正的签到按钮也挡掉了，用户界面上还毫无提示。
    //
    //  但完全放开后暴露了新问题：用户点签到按钮后，bot 回复里往往同时挂着
    //    「← 主菜单」「去商城逛逛」这类**导航按钮**，点签到的那一下会把它们一并学走，
    //    目标列表迅速被噪音淹没（用户实测反馈）。
    //
    //  所以现在恢复的**不是**那套猜测式过滤，而是只针对「确定的导航语义」：
    //    · 判据是字面文案的语义，不依赖 data 形态、不依赖 bot 类型
    //    · 命中即不学，且日志会写明原因（不静默）
    //    · 用户可在「设置 → 学习行为」关闭该过滤（默认开）
    //    · **手动添加不受限制** —— 手动本身就是明确意图
    //
    //  设计原则：宁可漏挡（多学一个导航按钮）也不误挡（漏掉真签到按钮）。
    //    因此只收录「几乎不可能出现在签到按钮上」的词。
    // ════════════════════════════════════════════════════════════════


    /**
     * 文本里是否含 emoji / 符号类码点（2026-10-17）。
     *
     * 为什么需要：emoji 在 Java char 上是**代理对**（U+1F3B2 = \uD83C\uDFB2），
     *   Character.isLetterOrDigit 对代理项一律 false，stripDecor 会整对丢弃。
     *   这里用 codePoint 遍历判断，才认得出生 🎲 这类。
     */
    public static boolean hasEmojiOrSymbol(String s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp <= 0x20) continue;                      // 空白跳过
            int t = Character.getType(cp);
            if (t == Character.OTHER_SYMBOL || t == Character.MATH_SYMBOL
                    || t == Character.MODIFIER_SYMBOL || t == Character.OTHER_PUNCTUATION
                    || t == Character.CURRENCY_SYMBOL || t == Character.SURROGATE
                    || cp >= 0x1F000) {
                return true;
            }
        }
        return false;
    }

    /** 去掉常见装饰符与表情，便于比对（← → « » ⬅ ➡ 🔙 等） */
    private static String stripDecor(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // 保留字母数字与 CJK；丢弃符号/表情
            if (Character.isLetterOrDigit(ch) || (ch >= '\u4e00' && ch <= '\u9fff')) {
                sb.append(Character.toLowerCase(ch));
            } else if (ch == ' ') {
                sb.append(' ');
            }
        }
        String r = sb.toString().trim();
        // 折叠连续空格
        return r.replaceAll("\\s+", " ");
    }

    /**
     * 这个按钮文案是否属于「确定的导航按钮」。
     *
     * @return 命中的词（用于日志），不是导航按钮则返回 null
     */
    /**
     * 这个按钮是「明确的后退 / 端点类按钮」吗？
     *
     * 设计（2026-09-30 二次重做）：
     *   第一版只列了十来个具体词，实测立刻漏掉三种：
     *     · `<<<返回主界面`（只有「返回菜单」，没有「返回主界面」）
     *     · `👤 账户信息`（根本没想到要收录）
     *     · `ub_back_menu`（这是 data 串，不是文案，压根没比对）
     *   穷举词表的通病：bot 的说法无穷，列不完。
     *
     *   现在改为**按语义类别匹配**，并用「必须命中类别词」+「不得含签到词」双重约束，
     *   既扩大覆盖，又把误伤压到零。
     *
     * @param text 按钮文案（可含 emoji / 箭头装饰）
     * @param data 回调按钮的 data 原文（可为 null）；网络层只能拿到这个
     * @return 命中的类别说明（用于日志），不是这类按钮返回 null
     */

    /**
     * 这个按钮看起来是不是「签到类」按钮？
     *
     * 用于按钮学习准入：命中即**自动学习、不必确认**——
     * 签到按钮是明确目标，没必要每次都问用户；
     * 说不清的按钮才进「待添加」交给用户判断。
     *
     * 复用 navButtonHit 里那份 SIGN_WORDS（同一份词表，避免两处维护跑偏），
     * 额外也匹配回调 data —— 有些 bot 的按钮文案只有图标，语义在 data 里。
     *
     * 纯函数、无副作用，可直接单测。
     */
    /**
     * 群聊里这段话「像不像一条签到口令」。
     *
     * 用途：群聊的自动学习比私聊危险得多 —— 群里天天有人聊「签到」，
     * 而关键词是子串匹配，一句随口的话就能被收进待添加（用户实测：
     * 「谁找找 我修复群聊签到」被抓）。所以群聊额外要求文本像一条**口令**，
     * 而不是像一句话。
     *
     * 判据（按顺序）：
     *   ① 以 / 开头 → 命令式，算（/checkin、/qd、/sign）
     *   ② 去掉标点/emoji/空白后 ≤8 字 → 算（「签到」「每日签到」「打卡」）
     *   ③ 其余 → 不算（长句子、闲聊）
     *
     * 纯函数、无副作用，可直接单测。
     */
    public static boolean looksLikeSignCommand(String text) {
        try {
            if (text == null) return false;
            String s = text.trim();
            if (s.length() == 0) return false;
            if (s.startsWith("/")) return true;
            String bare = stripDecor(s);
            // 2026-10-17：纯 emoji 口令也算 —— 有些 bot 的签到就是"发一个 🎲 / 🎳 / 🎯"。
            // stripDecor 会把这些符号全丢掉，bare 为空 → 旧逻辑判"不像口令"，
            // 于是群里发的 emoji 签到永远学不进来。现在按"原文全是符号且很短"放行。
            if (bare.length() == 0) {
                return (s.length() <= COMMAND_MAX_RAW) && hasEmojiOrSymbol(s);
            }
            if (bare.length() > COMMAND_MAX_BARE) return false;
            // 太长的原文同样可疑（夹在一句话里）
            if (s.length() > COMMAND_MAX_RAW) return false;
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 口令判定：去修饰后的最大字数。 */
    private static final int COMMAND_MAX_BARE = 8;
    /** 口令判定：原文最大字数（含 / 与 emoji 之类）。 */
    private static final int COMMAND_MAX_RAW = 16;

    public static boolean looksLikeSignButton(String text, String data) {
        try {
            String t = text == null ? "" : stripDecor(text);
            String d = data == null ? "" : stripDecor(data.replace('_', ' ').replace(':', ' '));
            String tRaw = text == null ? "" : text.toLowerCase(java.util.Locale.US);
            String dRaw = data == null ? "" : data.toLowerCase(java.util.Locale.US);
            boolean signHit = false;
            for (String w : SIGN_WORDS) {
                if (t.contains(w) || tRaw.contains(w) || d.contains(w) || dRaw.contains(w)) {
                    signHit = true;
                    break;
                }
            }
            if (!signHit) return false;
            // 2026-10-05 防误伤：命中签到词后再查反向否决词。
            // 「积分商城」「奖励规则」这类属查询/说明，不该自动学 → 落到待添加。
            for (String rj : SIGN_REJECT_WORDS) {
                if (t.contains(rj) || tRaw.contains(rj) || d.contains(rj) || dRaw.contains(rj)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static String navButtonHit(String text, String data) {
        String raw = text == null ? "" : text.trim();
        String dat = data == null ? "" : data.trim();
        if (raw.length() == 0 && dat.length() == 0) return null;

        // 归一化：去 emoji / 箭头 / 标点，转小写；data 另做下划线转空格便于词匹配
        String bare = stripDecor(raw);
        String dbare = stripDecor(dat.replace('_', ' ').replace(':', ' '));
        String hayText = raw.toLowerCase();
        String hayBare = bare;                       // 已是小写（stripDecor 内已转）
        String hayData = dbare;

        // ══ 硬性否决：含任何「签到类」词的，一律不挡 ══
        // 这是防误伤的最后一道闸。哪怕它同时含「返回」也不挡
        //（例如「返回签到页」这种真·签到入口）。
        for (String w : SIGN_WORDS) {
            if (hayBare.contains(w) || hayText.contains(w) || hayData.contains(w)) return null;
        }

        // ══ 类别一：后退 / 返回（任何"往回走"的表达）══
        for (String w : BACK_WORDS) {
            if (hayBare.contains(w) || hayText.contains(w) || hayData.contains(w)) {
                return "返回类「" + w + "」";
            }
        }

        // ══ 类别二：主菜单 / 主界面 / 首页 ══
        for (String w : MENU_WORDS) {
            if (hayBare.contains(w) || hayText.contains(w) || hayData.contains(w)) {
                return "菜单类「" + w + "」";
            }
        }

        // ══ 类别三：关闭 / 取消 / 退出 ══
        for (String w : CLOSE_WORDS) {
            if (hayBare.contains(w) || hayText.contains(w) || hayData.contains(w)) {
                return "关闭类「" + w + "」";
            }
        }

        // ══ 类别四：账户 / 个人中心 / 设置（非签到功能入口）══
        for (String w : ACCOUNT_WORDS) {
            if (hayBare.contains(w) || hayText.contains(w) || hayData.contains(w)) {
                return "账户类「" + w + "」";
            }
        }

        // ══ 类别五：纯符号按钮（只有箭头/省略号，无文字）══
        if (raw.length() > 0 && bare.length() == 0) return "纯符号";

        return null;
    }

    /** 兼容旧签名（只需文案时） */
    public static String navButtonHit(String text) {
        return navButtonHit(text, null);
    }

    /**
     * 签到类词 —— 出现这些的一律**不**判为导航按钮（防误伤）。
     * 宁可漏挡一个导航按钮，也不能挡掉真签到入口。
     *
     * 2026-10-01 修两处：
     *   ① 去掉单字 **「签」**。它是硬性否决词，只要按钮文案/data 里出现"签"
     *      就绝不判为导航 —— 于是「签名设置」「标签」「签到处」这类明显非签到的
     *      按钮全部漏挡，重新堆进目标列表。表里已有「签到」「签领」等长词，
     *      真签到入口必含其中之一（实测「签到」「每日签到」「签到领积分」均命中），
     *      单字「签」纯属冗余宽词。
     *   ② 去掉重复的「签到」（原本出现两次，无功能影响，但说明该表未被复核）。
     */
    /**
     * 「签到按钮」强判据 —— 只放**不会被误用的词**。
     *
     * 2026-10-18 收窄（用户实测事故：「点了一些按钮被偷偷加进签到列表」）：
     *   旧表含「领取」「奖励」「积分」「每日」「续期」「保号」「claim」「reward」，
     *   这些词在**非签到**按钮上同样常见（「领取奖励」「我的奖励」「每日任务」）。
     *   而 learnTarget 调 looksLikeSignButton 时 data 恒为 null，
     *   反向否决词也无从发挥 → 用户随手点一下就进了签到列表。
     *
     *   现在只保留「几乎只可能出现在签到按钮上」的词。
     *   宽的词移到 SIGN_SOFT_WORDS（下方），命中它们**一律走待确认**。
     */
    private static final String[] SIGN_WORDS = {
            "签到", "打卡", "签领", "报到", "签到领",
            "check in", "check-in", "sign in", "sign-in",
    };

    /**
     * 「疑似签到」弱判据 —— 命中不算错，但不该自动放行。
     *
     * 用途：仅用于「待添加」列表排序/展示，以及判定链的宽松兜底。
     * **绝不用于跳过待确认闸**（那正是 2026-10-18 事故的根因）。
     */
    public static final String[] SIGN_SOFT_WORDS = {
            "领取", "每日", "奖励", "积分", "续期", "保号",
            "check", "sign", "clock", "daily", "reward", "claim", "bonus",
    };

    /**
     * 反向否决词：命中 SIGN_WORDS 后再含这些的，**不算签到按钮**。
     *
     * 为什么需要（2026-10-05）：
     *   「积分」「奖励」「续期」在签到场景很常见，但在**查询/商城**场景同样常见：
     *     「积分商城」「奖励规则」「续期说明」「积分排行」「签到记录」
     *   若不放否决，这些会被当成签到按钮自动学进目标列表 —— 就是用户抱怨的噪音。
     *   加这道闸后它们落到「待添加」，由用户判断。宁可多问一次，不要误学。
     */
    private static final String[] SIGN_REJECT_WORDS = {
            "商城", "排行", "榜单", "规则", "说明", "明细", "记录", "历史",
            "兑换", "教程", "帮助", "客服", "统计", "查询", "详情", "介绍",
            "shop", "rank", "rule", "help", "history", "detail", "exchange",
    };

    /** 后退类：任何「往回走」的表达 */
    private static final String[] BACK_WORDS = {
            // 中文
            "返回", "后退", "回退", "上一页", "上一步", "回上", "回到上",
            // 英文（data 串里最常见）
            "back", "goback", "go back", "previous", "prev", "return",
    };

    /** 菜单/首页类 */
    private static final String[] MENU_WORDS = {
            "主菜单", "主界面", "主页", "首页", "菜单", "初始", "起始",
            "mainmenu", "main menu", "home", "start", "main",
    };

    /** 关闭/取消类 */
    private static final String[] CLOSE_WORDS = {
            "关闭", "取消", "退出", "我知道了", "知道了",
            "close", "cancel", "exit", "quit", "dismiss",
    };

    /**
     * 账户/个人中心/设置类。
     *
     * 这类不是「导航」，而是**非签到的功能入口**——用户不需要每天去点它。
     * 纳入的理由：截图实测「👤 账户信息」紧挨签到按钮，很容易被顺手点掉；
     *   而它的语义与签到完全无关，挡掉不会影响任何签到流程。
     */
    private static final String[] ACCOUNT_WORDS = {
            "账户信息", "账户管理", "账号信息", "个人信息", "个人中心", "我的账户", "我的账号",
            "钱包", "余额", "充值", "设置", "关于我们", "关于", "帮助", "客服",
            "account", "profile", "wallet", "balance", "settings", "setting",
            "help", "about", "support", "contact",
    };


    /**
     * 按关键词判定 bot 回复。
     *
     * @param reply    bot 回复原文
     * @param dup/ok/fail 关键词表（可为 null 表示用默认）
     * @return V_SIGNED / V_FAILED / V_UNKNOWN
     *
     * 抽成纯函数的意义：以前三张表都不命中时**静默返回**，用户看到的现象是
     * 「点了、发出去了、没反应」，且没有任何日志。现在返回 V_UNKNOWN，
     * 调用方必须显式处理（记日志 + 诊断包留痕）。
     */
    // ════════════════════════════════════════════════════════════════
    //  导航按钮识别（2026-09-30）
    //
    //  背景：2026-09-28 把「按钮性质过滤」整体移除，改为「点什么学什么」——
    //    起因是那套启发式（data 前缀黑名单、随机 hex、文案黑名单）**猜**得太凶，
    //    把真正的签到按钮也挡掉了，用户界面上还毫无提示。
    //
    //  但完全放开后暴露了新问题：用户点签到按钮后，bot 回复里往往同时挂着
    //    「← 主菜单」「去商城逛逛」这类**导航按钮**，点签到的那一下会把它们一并学走，
    //    目标列表迅速被噪音淹没（用户实测反馈）。
    //
    //  所以现在恢复的**不是**那套猜测式过滤，而是只针对「确定的导航语义」：
    //    · 判据是字面文案的语义，不依赖 data 形态、不依赖 bot 类型
    //    · 命中即不学，且日志会写明原因（不静默）
    //    · 用户可在「设置 → 学习行为」关闭该过滤（默认开）
    //    · **手动添加不受限制** —— 手动本身就是明确意图
    //
    //  设计原则：宁可漏挡（多学一个导航按钮）也不误挡（漏掉真签到按钮）。
    //    因此只收录「几乎不可能出现在签到按钮上」的词。
    // ════════════════════════════════════════════════════════════════


    public static int verdictOf(String reply, String[] dup, String[] ok, String[] fail) {
        return (Integer) verdictDetail(reply, dup, ok, fail)[0];
    }

    /**
     * 判定结果 + **命中了哪条词**（用于日志与诊断包）。
     * 以前只返回一个码，"为什么这么判"完全查不到 —— 排障只能猜。
     * 返回 {Integer 判定码, String 命中词}。
     */
    /**
     * 带**作用域**的判定入口（2026-10-06 交接单第八条）。
     *
     * 四级词表按「从通用到具体」叠加，失败词始终优先于成功词：
     *   失败侧：系统默认 → 全局自定义 → Bot 专属 → 目标专属
     *   成功侧：系统默认 → 全局自定义 → Bot 专属 → 目标专属
     *
     * 为什么必须分层：全局词表下，为 A bot 学的「任务完成」会污染 B bot。
     * 学习默认写入 **Bot 级**（拿不到 bot id 时降级到目标级），
     * 只有用户显式选「全局」才进全局表。
     *
     * @param okGlobal / failGlobal   全局自定义词（旧 jmb_ok_words / jmb_fail_words）
     * @param okBot    / failBot      Bot 级词
     * @param okTarget / failTarget   目标级词
     * @return {Integer 判定码, String 命中词}
     */
    public static Object[] verdictDetailScoped(String reply,
                                               String[] okGlobal, String[] failGlobal,
                                               String[] okBot, String[] failBot,
                                               String[] okTarget, String[] failTarget) {
        if (reply == null || reply.length() == 0) return new Object[]{Integer.valueOf(V_UNKNOWN), ""};
        String lower = reply.toLowerCase();

        // 失败侧：具体级优先（目标 > Bot > 全局）
        //
        // 2026-10-17：这里**故意不做裸词语境守卫**。
        // 三张表全部来自用户学习/手输（不是 FAIL_WORDS_DEFAULT），
        // 属于「用户显式表态」——用户亲自把「拉黑」标成失败，
        // 就该按原语义生效，不能被模板语境判断吞掉。
        // 守卫只服务内置默认表（verdictDetail 的 FAIL 分支）。
        String[][] failChains = { failTarget, failBot, failGlobal };
        for (String[] words : failChains) {
            if (words == null || words.length == 0) continue;
            String m = matched(lower, words);
            if (m == null) continue;
            // ── 2026-10-18：scoped 也必须过裸词语境守卫 ──
            // 改前这里直接 return V_FAILED，而判定链走的正是 scoped ——
            // 于是 verdictDetail 里加的守卫在真实路径上完全没生效
            // （行为验证 Flow 场景4 实测：公告句仍被判失败）。
            // 与 verdictDetail 同一套判据：内置裸词要过语境，用户加的词豁免。
            if (isBareFailWord(m) && !isUserAddedWord(m, words)
                    && !bareFailSupported(lower.replace(" ", "").replace("\u3000", ""), m)) {
                continue;   // 模板复述 → 不作数，继续看下一条链
            }
            // 2026-10-24：用户自己加的词仍按用户语义生效；
            // 只有内置/条件型词才允许被「同句成功结论」翻过来。
            if (!isUserAddedWord(m, words)) {
                String okOv = conditionalSuccessOverride(lower, m, okGlobal);
                if (okOv != null) return new Object[]{Integer.valueOf(V_SIGNED), okOv};
            }
            return new Object[]{Integer.valueOf(V_FAILED), m};
        }

        // 成功侧：具体级优先（目标 > Bot > 全局）
        //
        // 2026-10-28 P0 修复（真实事故：10-25 02:37 两条“未签到却显示成功”）：
        //   旧写法只对**内置词**加语境守卫（`isBuiltinOkWord(m) && okWordNeedsContext(m)`），
        //   于是用户自定义的泛词（"欢迎""成功""恭喜您"…）**完全绕过守卫** ——
        //   欢迎语 / 帮助说明里出现这些字就直接判成功。
        //   现在：**内置词与用户词走同一套证据标准** —— 泛词必须有「结果形态」。
        String[][] okChains = { okTarget, okBot, okGlobal };
        for (String[] words : okChains) {
            if (words == null || words.length == 0) continue;
            String m = matched(lower, words);
            if (m == null) continue;
            if (negated(lower, m)) continue;                       // 否定守卫
            if (isLikelyAnnouncement(reply)) continue;             // 公告守卫
            if (m.contains("重复") && hasTrailingNegation(lower)) continue;
            // ★ 统一证据标准：泛词（无论内置还是用户词）必须有结果形态
            if (isGenericOkWord(m) && !hasSuccessShape(lower)) continue;
            if (isBuiltinOkWord(m) && okWordNeedsContext(m) && !hasSignContext(lower)) continue;
            return new Object[]{Integer.valueOf(V_SIGNED), m};
        }

        // 都未命中 → 落回内置完整判定链（含非结论类、组合判定）
        return verdictDetail(reply, null, null, null);
    }

    /**
     * 判定，但**用户自定义词优先**。
     *
     * 为什么需要（2026-10-04 实测事故）：
     *   内置表里 FAIL_WORDS_DEFAULT 含「未绑定」，而判定顺序是
     *   FAIL → PERM_FAIL → EXHAUSTED → DUP → OK。
     *   用户在自学习页把「您都还没有绑定囡囡呢」显式判成**成功**后，
     *   下次同样的回复仍先命中内置的 FAIL「未绑定」→ 判失败 → 用户看到
     *   「我明明学过了，还是判不出」。
     *
     * 原则：**用户显式表态 > 内置假设**。用户亲自点过「算成功 / 算失败」，
     *   说明他确认过这句话的真实语义，应当直接生效。
     *
     * 顺序：userFail → userOk → （落回原 verdictDetail 的完整判定链）
     *   注意 userFail 仍排在 userOk 前：否定词常内嵌在肯定词里
     *   （「没有签到成功」既含「签到成功」又是否定），失败优先与本文件既有约定一致。
     */
    public static Object[] verdictDetailCustom(String reply, String[] dup, String[] ok,
                                               String[] fail, String[] userOk, String[] userFail) {
        if (reply == null || reply.length() == 0) return new Object[]{Integer.valueOf(V_UNKNOWN), ""};
        String lower = reply.toLowerCase();
        if (userFail != null && userFail.length > 0) {
            String m = matched(lower, userFail);
            if (m != null) return new Object[]{Integer.valueOf(V_FAILED), m};
        }
        if (userOk != null && userOk.length > 0) {
            String m = matched(lower, userOk);
            // 2026-10-06：用户词也过否定守卫 —— 学到的是肯定句，
            //   却被「还没有绑定」这类否定句命中，会把失败判成功。
            if (m != null && !negated(lower, m)) return new Object[]{Integer.valueOf(V_SIGNED), m};
        }
        return verdictDetail(reply, dup, ok, fail);
    }

    /**
     * 判定词是不是「条件型失败词」（2026-10-24 修假失败）。
     *
     * 背景（真实事故）：bot 把结论和条件写在一句里 ——
     *   「签到成功！请先关注频道解锁更多功能」
     * 旧实现失败词优先，直接命中「请先关注」判失败 → 撤销已签 + 退避重发，
     * 用户看到「日志判失败、实际已经签上」。littlekunlun 反馈的正是这条。
     *
     * 真结论失败词（「签到失败」「未成功」「没有签到成功」）含业务动词，不属于条件型，
     * 仍然直接判失败。
     */
    static boolean isConditionalFailWord(String w) {
        if (w == null || w.length() == 0) return false;
        String lw = w.toLowerCase(java.util.Locale.US);
        String[] VERDICT = {"签到", "打卡", "成功", "完成", "领取", "参与", "奖励", "积分",
                "check", "sign", "claim"};
        for (String k : VERDICT) {
            if (lw.contains(k)) return false;
        }
        return true;
    }

    /** 同一句里是否另有**未被否定**的成功结论；有则返回它，否则 null。 */
    static String conditionalSuccessOverride(String lower, String failWord, String[] ok) {
        try {
            if (lower == null || !isConditionalFailWord(failWord)) return null;
            // 2026-10-28 修（真实案例）：**不要**在这里再要求 hasSuccessShape ——
            //   「❌️ 你今天已经签到了…不通过就拉黑」这类是"完成态 + 条件威胁"混合句，
            //   它的成功证据本来就是"同句存在未被否定的成功结论"（下面 firstUnnegated）。
            //   之前多加了形态要求，DONE_STATE 表又没收"已经签到"，于是永远翻不了盘
            //   → 用户抱怨「加了判定词也一直判失败」（Little Kunlun 的 mfsgk）。
            String[] tab = (ok != null && ok.length > 0) ? ok : OK_WORDS_DEFAULT;
            String hit = firstUnnegated(lower, tab);
            if (hit != null) return hit;
            return firstUnnegated(lower, DUP_WORDS_DEFAULT);
        } catch (Throwable t) { return null; }
    }

    private static String firstUnnegated(String lower, String[] tab) {
        if (tab == null) return null;
        for (String w : tab) {
            if (w == null || w.length() == 0) continue;
            String lw = w.toLowerCase(java.util.Locale.US);
            if (lower.contains(lw) && !negated(lower, lw)) return w;
        }
        return null;
    }

    /** 命中词是否表达「今天已经签过了」（不是"刚刚签成功"）。
     *  成功落库时用它决定「只标在途那条」还是「该 bot 名下都标」。 */
    public static boolean isAlreadySignedWord(String w) {
        if (w == null || w.length() == 0) return false;
        String lw = w.toLowerCase(java.util.Locale.US);
        String[] AL = {"已签", "已经签", "已打卡", "已经打卡", "重复", "已领取", "已参与",
                "already", "repeated", "again later"};
        for (String k : AL) {
            if (lw.contains(k)) return true;
        }
        return false;
    }

    /**
     * 用户自定义成功词是否「过宽」——会被 bot 的广告/菜单命中。
     *
     * 2026-10-24：实测用户的全局成功词里有 `👑`、`社工库功能`，
     * 而社工库 bot 的推广文案恰好满屏 👑 与「社工库」——
     * 实测「😇暗影社工库 | 2026最新数据 👑政务接口…」被判 V_SIGNED。
     * 本函数只用于**设置页提示**，不改用户配置。
     */
    public static boolean isTooBroadOkWord(String w) {
        if (w == null) return false;
        String s = w.trim();
        if (s.length() == 0) return false;
        boolean hasAlnum = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) { hasAlnum = true; break; }
        }
        if (!hasAlnum) return true;              // 纯 emoji / 符号
        if (s.length() < 2) return true;         // 单字
        if (isLikelyAnnouncement(s)) return true; // 命中公告特征词（社工库/广告/邀请链接…）
        return false;
    }

    /** 从逗号分隔的自定义词里挑出过宽的（设置页提示用）。 */
    public static java.util.List<String> tooBroadWords(String csv) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (csv == null) return out;
        for (String w : parseExtraWords(csv)) {
            if (isTooBroadOkWord(w)) out.add(w);
        }
        return out;
    }

    public static Object[] verdictDetail(String reply, String[] dup, String[] ok, String[] fail) {
        if (reply == null || reply.length() == 0) return new Object[]{Integer.valueOf(V_UNKNOWN), ""};
        String lower = reply.toLowerCase();

        // ── 判定顺序（2026-09-30 调整）──
        // 必须是「失败 → 永久失败 → 重复 → 成功」，不能是「重复 → 成功 → 失败」。
        //
        // 原因：这三张表都是**子串匹配**，而中文里否定词常加在肯定词前面：
        //   「未签到成功」「没有签到成功」「签到未成功」
        // 若先查成功表，`签到成功` 会命中，把明确的失败判成成功 —— 与用户利益相反
        // （用户以为签上了，实际没有，第二天直接断签）。
        //
        // 失败优先不会反过来误伤：成功语里几乎不会内嵌失败词。
        String m = matched(lower, fail != null ? fail : FAIL_WORDS_DEFAULT);
        // 2026-10-17：裸短词（拉黑/未注册/禁止…）要过语境校验 ——
        // bot 的公告模板里也含这些字样，撞上就判失败属于误判（用户实测 7 次）。
        // 用户自定义词（fail != null）不受此限：那是用户亲自确认过的语义。
        // 2026-10-18 修：判据改为「命中的这个词是不是内置的」。
        // 改前用 isCustomFailTable(fail) 判整表 —— 而判定链传的是
        // mergeWords(FAIL_WORDS_DEFAULT, extraFail) 合并体，永远不等于默认表，
        // 于是只要用户加过一个词，「拉黑」守卫就整个失效（行为验证实测到）。
        if (m != null && (!isBareFailWord(m) || isUserAddedWord(m, fail) || bareFailSupported(
                lower.replace(" ", "").replace("\u3000", ""), m))) {
            // 2026-10-24：条件型失败词 + 同句成功结论 → 以成功为准（修假失败）
            String okOv = conditionalSuccessOverride(lower, m, ok);
            if (okOv != null) return new Object[]{Integer.valueOf(V_SIGNED), okOv};
            return new Object[]{Integer.valueOf(V_FAILED), m};
        }
        m = matched(lower, PERMANENT_FAIL_WORDS);
        // 2026-10-17：同 FAIL 分支 —— 「未关注」「没有资格」在公告里也常见
        // （「未关注公众号的用户没有资格参加」），需要语境校验才认可。
        if (m != null && (!isBareFailWord(m) || bareFailSupported(
                lower.replace(" ", "").replace("\u3000", ""), m))) {
            String okOv2 = conditionalSuccessOverride(lower, m, ok);
            if (okOv2 != null) return new Object[]{Integer.valueOf(V_SIGNED), okOv2};
            return new Object[]{Integer.valueOf(V_FAILED), m};
        }
        // 今日已用尽（2026-10-01 新增）：排在重复/成功词之前。
        // 这类回复常同时含"已"字（如"今日次数已用完"），若不抢先判定，
        // 会被组合判定当成"签到+已"误判成功；但它**不等于签到成功**。
        m = matched(lower, EXHAUSTED_WORDS);
        if (m != null) return new Object[]{Integer.valueOf(V_EXHAUSTED), m};
        // ── 非结论类（说明 / 统计 / 冷却）：在 FAIL 之后、DUP/OK/组合之前 ──
        // 2026-10-06 新增。位置很关键：放最前会吞掉真失败（「签到失败,详见规则说明」），
        // 放最后又拦不住（DUP/OK 已先命中）。只在这一段直接返回 V_UNKNOWN。
        if (matched(lower, NON_CONCLUSION_WORDS) != null) {
            return new Object[]{Integer.valueOf(V_UNKNOWN), ""};
        }
        // 2026-10-24：成功侧也要过「公告/广告」守卫 ——
        // 实测「😇暗影社工库 | 2026最新数据 👑政务接口…」因自定义词 👑 被判成功。
        // isLikelyAnnouncement 先看结果词（有「签到成功」等一律放行），再查公告特征词，
        // 真结论不会受影响。
        m = matched(lower, dup != null ? dup : DUP_WORDS_DEFAULT);
        if (m != null && !negated(lower, m) && !isLikelyAnnouncement(reply)
                && !(m.contains("重复") && hasTrailingNegation(lower))) {
            return new Object[]{Integer.valueOf(V_SIGNED), m};
        }
        m = matched(lower, ok != null ? ok : OK_WORDS_DEFAULT);
        if (m != null && !negated(lower, m) && !isLikelyAnnouncement(reply)
                && !(okWordNeedsContext(m) && (!hasSignContext(lower) || hasTrailingNegation(lower)))
                // 2026-10-28：与 scoped 链同一标准 —— 泛词必须有结果形态
                && !(isGenericOkWord(m) && !hasSuccessShape(lower))) {
            return new Object[]{Integer.valueOf(V_SIGNED), m};
        }
        // ── 组合判定（兜底）：词表没覆盖，但语义上明显是成功 ──
        String cm = comboSuccess(lower);
        // 2026-10-28：组合判定同样要结果形态（否则"欢迎+签到"这类面板会被判成功）
        if (cm != null && !negated(lower, cm.split("\\+")[0]) && hasSuccessShape(lower)) {
            return new Object[]{Integer.valueOf(V_SIGNED), cm};
        }
        return new Object[]{Integer.valueOf(V_UNKNOWN), ""};
    }

    /**
     * 否定守卫：命中词**紧邻之前**是否出现否定词。
     *
     * 2026-10-06 新增。起因：FAIL 表只列了固定组合（「没有签到成功」等），
     *   「还没有签到」「尚未签到」「无法签到」「haven't checked in」全部漏过，
     *   而这些句子里含「签到成功」类子串，会被 DUP/OK/组合判成成功。
     *
     * 窗口设计（两套规则分开，不能混用）：
     *   · 中文：看命中词之前 **3 个字符**
     *   · 英文：看命中词之前 **2 个词**
     *
     * 边界用例（必须有测试）：
     *   ✗ 「还没有签到」   → 前 3 字含「没有」→ 作废
     *   ✗ 「尚未签到成功」 → 前 3 字含「尚未」→ 作废
     *   ✓ 「没问题,签到成功」→ 前 3 字是「题,签」→ 不作废（否定词离得远）
     *   ✓ 「不错,今日已签到」→ 前 3 字是「日已签」→ 不作废
     *
     * @param lower 已小写的整句
     * @param hitWord 命中的词（matched 返回的是小写词）
     * @return true = 该命中应作废
     */
    static boolean negated(String lower, String hitWord) {
        try {
            if (lower == null || hitWord == null || hitWord.length() == 0) return false;
            int idx = lower.indexOf(hitWord);
            if (idx < 0) return false;
            // 取命中词之前的一小段
            int from = Math.max(0, idx - 12);
            String before = lower.substring(from, idx);
            if (before.length() == 0) return false;

            // ── 中文规则：只看命中词**紧邻的 2 个字符** ──
            // 为什么是 2 而不是 3（2026-10-06 实测迭代）：
            //   3 字窗口会把「不错,今日已签到」误杀 —— 「不」正好落在窗口里。
            //   2 字窗口仍能覆盖「还没有/尚未/无法/不能/没有/禁止」等全部实际写法，
            //   而逗号/空格会把「不错,」与命中词隔开，自然不误伤。
            int cnSep = -1;
            for (int ci = before.length() - 1; ci >= 0; ci--) {
                char cc = before.charAt(ci);
                if (cc == ',' || cc == '，' || cc == '。' || cc == '.' || cc == ';' || cc == '；'
                        || cc == '!' || cc == '！' || cc == '?' || cc == '？' || cc == '、'
                        || cc == '\n' || cc == ' ' || cc == '\t') { cnSep = ci; break; }
            }
            String cnWin;
            if (cnSep >= 0) {
                // 有分隔符 → **只取分隔符之后**，绝不回溯。
                // 为什么不能回溯：「没有问题,签到成功」的前一小句含「没有」，
                //   但那是"没问题"，后一小句才是结论 —— 回溯会把它误判成否定。
                //   「没有问题,签到成功」与「没有签到成功」的区别就在这个逗号。
                cnWin = before.substring(cnSep + 1);
            } else {
                // 无分隔符：同一小句内向回看最多 6 字（覆盖「今天没有签到成功」）
                cnWin = before.length() > 6 ? before.substring(before.length() - 6) : before;
            }
            if (cnWin.length() > 6) cnWin = cnWin.substring(cnWin.length() - 6);
            // 例外：这些前缀本身含否定字，但语义是肯定
            if (cnWin.startsWith("不") || cnWin.startsWith("没") || cnWin.startsWith("无")) {
                // 「不错」「没问题」类：否定字后面跟着褒义词，不算否定
                if (cnWin.startsWith("不错") || cnWin.startsWith("没问") || cnWin.startsWith("无妨")) {
                    // 视为肯定，跳过中文否定判定
                } else {
                    for (String n : NEG_WORDS_CN) {
                        if (cnWin.contains(n)) return true;
                    }
                }
            } else {
                for (String n : NEG_WORDS_CN) {
                    if (cnWin.contains(n)) return true;
                }
            }

            // ── 英文规则：只看命中词**紧邻的 1 个词** ──
            // 同理由 2 词收窄到 1 词：「no problem, checked in」里
            //   「no」与 checked 中间隔着 problem → 只有一个词窗口才不会误杀。
            String[] parts = before.trim().split("[\\s,.;:!?、，。！？]+");
            if (parts.length > 0) {
                // 2026-10-07：窗口 1 词 → 2 词，但跳过不构成否定的名词。
                //   1 词漏掉 "not yet signed in"；2 词又会把 "no problem, checked in"
                //   的 no 误当否定 —— 跳过 problem/idea/doubt 这类即可两全。
                String[] SKIPW = {"problem", "idea", "doubt", "question", "worry", "issue"};
                int checkedW = 0;
                for (int pi = parts.length - 1; pi >= 0 && checkedW < 2; pi--) {
                    String w = parts[pi] == null ? "" : parts[pi].trim();
                    if (w.length() == 0) continue;
                    String wFlat = w.replace("'", "").replace("\u2019", "").toLowerCase();
                    boolean skipW = false;
                    for (String sk : SKIPW) { if (wFlat.equals(sk)) { skipW = true; break; } }
                    // 2026-10-07：遇到这类名词要**截断窗口**，不只是跳过它。
                    //   「no problem, checked in」里 no 修饰的是 problem，
                    //   与后面的 checked 无关；若只是跳过 problem 继续往前看，
                    //   仍会读到 no 并误判为否定。break 才是正确语义。
                    if (skipW) break;
                    checkedW++;
                    for (String neg : NEG_WORDS_EN) {
                        String nFlat = neg.replace("'", "").replace("\u2019", "").toLowerCase();
                        if (wFlat.equals(nFlat)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 签到行为词（与「结果词」组合时才生效） */
    private static final String[] COMBO_ACT = {
            "签到", "打卡", "签领", "check in", "check-in", "checked in", "sign in", "sign-in"
    };

    /**
     * 成功结果词（2026-10-06 收紧）。
     *
     * 旧表含「已」「积分」「奖励」「领取」—— 这些词与「签到」同句的概率极高
     * （「签到规则:每日签到可获得积分」「已有 123 人签到」），
     * 于是大量**非成功**回复被组合判定成 V_SIGNED，进而 markSigned，
     * 当天不再补发，用户第二天才发现断签。
     *
     * 现在只保留**强结果词**：出现即基本等价于「这次签到有明确结果」。
     * 「获得 + 数字」「+1」这类带数值的，由 comboSuccess 的数值分支单独处理。
     */
    private static final String[] COMBO_OK = {
            "成功", "完成签到", "签到完成", "打卡完成", "完成打卡",
            "success", "completed", "checked in successfully",
    };

    /**
     * 「非结论类」词表（2026-10-06 新增）—— 说明 / 统计 / 冷却。
     *
     * 这类文本常在句子内容纳了签到相关字样，却**不是本次签到的结论**：
     *   · 说明类：「签到规则:每日签到可获得积分」
     *   · 统计类：「今日已有 123 人签到」
     *   · 冷却类：「签到冷却中,请于 8 小时后再试,已签到 5 次」
     * 若放它们进入 DUP / OK / 组合判定，极易命中「已」「积分」等宽词被误判成功。
     *
     * 判定位置：**必须排在 FAIL / PERMANENT_FAIL / EXHAUSTED 之后**。
     *   否则「签到失败,详见规则说明」会被它先吞成 V_UNKNOWN，丢掉真失败结论。
     */
    private static final String[] NON_CONCLUSION_WORDS = {
            // 说明 / 规则
            "规则", "说明", "教程", "指引", "如何", "怎么", "帮助", "介绍",
            // 统计 / 排行
            "已有", "已有 ", "人数", "排名", "排行", "榜单", "统计", "总数", "累计",
            // 冷却 / 等待
            "冷却", "请等待", "稍后", "后再试", "小时后", "分钟后再",
            "rules", "how to", "guide", "ranking", "cooldown", "try again in",
    };

    /**
     * 否定词 —— 出现在命中词**紧邻之前**时，该命中作废。
     *
     * 中文按**字符数**看前 3 个字符；英文按**词数**看前 2 个词（两套规则分开写，
     * 因为中文 3 字≈英文 1~2 词，用同一套会一边过松、一边过紧）。
     *
     * 为什么窗口这么窄：要把「没问题,签到成功」「不错,今日已签到」保留为成功
     *   —— 它们的否定词离得远（「没问题,」后面还有 5 个字），不会被误伤。
     */
    private static final String[] NEG_WORDS_CN = {
            "未", "没", "无", "不", "别", "禁止", "尚未", "无法", "不能", "勿",
    };
    private static final String[] NEG_WORDS_EN = {
            "not", "no", "never", "haven't", "hasn't", "didn't", "don't", "won't",
            "cannot", "can't", "unable", "without", "fail", "failed",
    };

    /**
     * 「裸短词」失败词守卫（2026-10-17 新增）。
     *
     * 背景（用户反馈事故）：两个 bot 长期显示失败 ——
     *   · 7601994292 每次「命中明确失败词"未注册"」
     *   · 8500640583 每次「命中明确失败词"拉黑"」，连续 4 天 7 次
     * 而 7601994292 在同一秒又收到「您今天已经签到过了」，
     * 8500640583 几天前还明确回过「签到成功，积分+3」。
     *
     * 根因：FAIL_WORDS_DEFAULT 里「拉黑」「未注册」「禁止」「被封」都是**裸词**，
     *   全文本 contains 匹配。而 bot 的模板/公告/防骗提示常含这类字样：
     *     「广告是骗子，点了会被拉黑」「请勿重复签到，否则封禁」
     *     「发送 /start 完成注册；未注册用户无法使用」
     *   这类句子说的是**别人的条件**，不是这次签到的结论。
     *   撞上就判失败并退避重试，比"判不出"更糟 —— 用户以为签不上。
     *
     * 判据（保守，宁可漏挡也不误挡真失败）：
     *   裸词命中后检查整句语境，只有**不是条件句**且**指向用户**才算真失败。
     *   任一不满足 → 该命中作废，落回后续判定继续等结论。
     */
    private static final String[] BARE_FAIL_WORDS = {
            "拉黑", "未注册", "禁止", "被封", "封禁", "不可用", "无权限",
            "未关注", "没有资格",
    };

    /** 指向用户的代词/限定语：出现才算这句话在说"你"。 */
    private static final String[] SELF_REF_WORDS = {
            "你", "您", "该账号", "此账号", "贵号",
    };

    /** 条件连词：命中词紧跟在它们后面时，是在描述"如果"，不是当前结论。 */
    private static final String[] COND_WORDS = {
            "否则", "如果", "如若", "才", "将会", "将被", "会被", "可能被",
    };

    /**
     * 传入的失败词表是否为「用户自定义」。
     *
     * 2026-10-17：调用方（宽松模式、单测）总是显式传 FAIL_WORDS_DEFAULT，
     *   若按 fail != null 判豁免，守卫永远不生效 —— 实测 5 条用例全挂。
     * 正解：只有**确实不同于默认表**的才算用户自定义，才豁免守卫。
     */
    /**
     * 命中的这个词，是不是**用户额外加的**（不在内置表里）？
     *
     * 2026-10-18：替代 isCustomFailTable 的整表比较。
     * 判定链传进来的是「内置 + 用户」合并体，整表比较必然判成"自定义"，
     * 导致裸词守卫被整体绕过（行为验证实测）。这里只看**词本身**归属。
     */
    static boolean isUserAddedWord(String word, String[] table) {
        if (word == null || word.length() == 0) return false;
        // ① 传入表完全不含内置词 → 这是「纯用户表」（用户亲自制定的规则），
        //    整表豁免守卫。例：测试里传 new String[]{"拉黑"}，
        //    语义是「用户要求出现拉黑就算失败」，不该被公告语境吃掉。
        // 判定链传的是 mergeWords(FAIL_WORDS_DEFAULT, extra) —— 含**全部**默认词；
        // 用户单独传的纯表（如 {"拉黑"}）只含个别默认词。
        // 所以用「是否包含默认表全部词」来区分，而不是「含不含某个默认词」。
        if (table != null && table.length > 0) {
            boolean coversAll = true;
            for (String b : FAIL_WORDS_DEFAULT) {
                boolean found = false;
                for (String t : table) {
                    if (b.equals(t)) { found = true; break; }
                }
                if (!found) { coversAll = false; break; }
            }
            if (!coversAll) return true;   // 不是完整默认表 → 纯用户表 → 豁免
        }

        // ② 合并表 / 默认表：按**词本身**归属判断
        for (String b : FAIL_WORDS_DEFAULT) {
            if (b.equals(word)) return false;      // 内置词 → 不是用户加的
        }
        for (String b : PERMANENT_FAIL_WORDS) {
            if (b.equals(word)) return false;
        }
        return true;                               // 不在内置表 → 用户加的
    }

    static boolean isCustomFailTable(String[] fail) {
        if (fail == null) return false;
        if (fail == FAIL_WORDS_DEFAULT) return false;
        if (fail.length != FAIL_WORDS_DEFAULT.length) return true;
        for (int i = 0; i < fail.length; i++) {
            if (!fail[i].equals(FAIL_WORDS_DEFAULT[i])) return true;
        }
        return false;
    }

    /** 该词是否属于需要语境校验的裸短词。 */
    static boolean isBareFailWord(String w) {
        if (w == null) return false;
        for (String b : BARE_FAIL_WORDS) {
            if (b.equals(w)) return true;
        }
        return false;
    }

    /**
     * 裸短词命中的语境校验。
     *
     * @param flat 已小写、已去空白的整句
     * @param word 命中的裸词
     * @return true = 语境支持"这是真失败"；false = 模板复述，作废
     */
    static boolean bareFailSupported(String flat, String word) {
        if (flat == null || word == null || word.length() == 0) return true;
        int idx = flat.indexOf(word);
        if (idx < 0) return true;   // 不该发生；放行给原逻辑

        // ① 条件连词前缀 → 说的是"如果"，不是当前结论（最典型的误判源）
        int from = Math.max(0, idx - 4);
        String before = flat.substring(from, idx);
        for (String c : COND_WORDS) {
            if (before.contains(c)) return false;
        }

        // ② 短句直接认可：整句（去空白）≤ 12 字几乎不可能是在复述公告
        //    「无权限操作」「未注册」这类就是直接结论，不能因为没代词就被吞。
        if (flat.length() <= 12) return true;

        // ③ 句中有自称代词 → 在说用户
        for (String s : SELF_REF_WORDS) {
            if (flat.contains(s)) return true;
        }
        // ④ 无代词但明显指向读者
        if (before.contains("已被") || before.contains("把你")
                || before.contains("将你") || before.contains("你被")) {
            return true;
        }
        // ⑤ 长句 + 无主语 → 大概率是公告/模板 → 作废
        return false;
    }

    /**
     * 组合判定：句子里同时出现「签到类行为词」与「成功类结果词」即判成功。
     *
     * 为什么需要：bot 的措辞千奇百怪（「✅ 今日打卡 +1」「签到完成，获得 5 积分」
     * 「恭喜，今日签到成功」），穷举词表永远追不上。
     * 实测用户报「明明签上了却显示回复判不出」，多数属于此类。
     *
     * 为什么安全：① fail 已在前面先判过，含否定词的根本到不了这里；
     *            ② 必须两类词同时出现，单个「签到」不构成结果；
     *            ③ 只是把结果从「判不出」提升为「成功」，
     *               而「判不出」本身也不会写「今日已签」，不会造成错误记账。
     *
     * @return 命中的组合描述（如「签到+成功」），未命中返回 null
     */
    private static String comboSuccess(String lower) {
        String act = matched(lower, COMBO_ACT);
        if (act == null) return null;
        String okw = matched(lower, COMBO_OK);
        if (okw == null) return null;
        return act + "+" + okw;
    }

    private static boolean hit(String lower, String[] words) {
        return matched(lower, words) != null;
    }

    /** 返回命中的第一条词；没命中返回 null。 */
    private static String matched(String lower, String[] words) {
        if (words == null) return null;
        // 2026-10-04 修「学了词还是判不出」：词与回复的空格不一致会失配。
        // 实测：词表「💢您都还没有绑」（提词走了归一化，空格被删），
        // 而 bot 回「💢 您都还没有绑定囡囡呢」（💢 后有空格）→ contains 不中。
        // 这里把两边空白都去掉再比：对原本无空格的词（签到成功等）行为不变。
        String flat = lower.replace(" ", "").replace("\u3000", "");
        for (String w : words) {
            if (w == null) continue;
            String t = w.trim().toLowerCase();
            if (t.length() == 0) continue;
            if (lower.contains(t)) return t;
            String ft = t.replace(" ", "").replace("\u3000", "");
            if (ft.length() > 0 && flat.contains(ft)) return t;
        }
        return null;
    }

    /** 把用户输入的附加词（逗号/换行分隔）解析成数组；空返回 null。 */
    public static String[] parseExtraWords(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        List<String> out = new ArrayList<String>();
        for (String p : raw.split("[,\\n\\r\\uFF0C]+")) {
            String t = p.trim();
            if (t.length() > 0) out.add(t);
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    // ────────────────────────────────────────────────────────────────
    // 统一签到调度：决策部分（纯逻辑，可单测）
    //
    // 背景：模块有 6+ 个触发源都在"想签到"（进入窗口 / 心跳检测 / 打开聊天 /
    // 网络恢复 / 定时巡检 / 交互），去重曾分散在 4 个地方（节流 lastTryTime、
    // pendingSigns、kLast、排队时间戳），彼此不知道对方存在 —— 导致
    // "同一个 bot 连发两条签到"（用户实测 1.5.8：09:13:01 与 09:13:15 各发一次）。
    //
    // 这里把"该不该发"的判定收敛成纯函数，由调用方提供状态快照。
    // 好处：① 所有路径共用同一套判据；② 可单测覆盖；③ 加新触发源自动获得去重。
    // ────────────────────────────────────────────────────────────────

    /** 跳过原因（用于日志归因）。 */
    public static final int SKIP_NONE          = 0;   // 可以发
    public static final int SKIP_ALREADY_SIGNED = 1;  // 今天已签
    public static final int SKIP_IN_FLIGHT      = 2;  // 请求在途
    public static final int SKIP_SENT_PENDING   = 3;  // 已发出、等结论（且在时效内）
    public static final int SKIP_RETRY_EXHAUST  = 4;  // 今日重试已用尽
    public static final int SKIP_BACKOFF        = 5;  // 退避中
    public static final int SKIP_DISABLED       = 6;  // 暂停/冻结/被排除
    public static final int SKIP_SEND_FAIL      = 7;  // 过了闸但发送过程失败（会话取不到等）
    public static final int SKIP_ACCOUNT_DISABLED = 8; // 所在账号被用户停用（账号级，最高优先）
    /** 结果未知、等用户处置（「待确认」）—— 不得再自动重发。 */
    public static final int SKIP_PENDING_CONFIRM = 9;
    /**
     * 当日「发出后仍无结论」次数已达硬上限（MAX_SEND_ATTEMPTS）。
     *
     * 为什么需要独立一条（2026-10-03 修 13 次重复发送）：
     *   MAX_SEND_ATTEMPTS 原本只被 promoteSilentToPending 读，而它开头有
     *   `if (!sentToday) return false;` 的早退 —— opt_ 一旦被清就永远读不到，
     *   计数涨到 13 也没人拦。现在把它接进**发送闸**，成为真正的硬闸。
     */
    public static final int SKIP_SEND_ATTEMPTS_EXHAUST = 10;

    /** 参数打包，避免调用方传一长串布尔。 */
    public static final class SignGate {
        public boolean manual;              // 用户显式操作（绕过所有"今天已签"类限制）
        public boolean signedToday;         // kLast == today
        public boolean inFlight;            // pendingSigns 命中
        public boolean sentPendingFresh;    // opt_ == today 且 sent_at 在时效内
        public boolean retryExhausted;      // 今日重试达上限
        public boolean inBackoff;           // now < retryAt
        public boolean disabled;            // 暂停/冻结/被排除（目标级）
        public boolean accountDisabled;     // 所在账号被停用（账号级，见 SKIP_ACCOUNT_DISABLED）
        /**
         * 当日「发出后仍无结论」次数已达上限（MAX_SEND_ATTEMPTS）。
         *
         * 语义与 retryExhausted 不同：retry_ 记的是**失败**次数，
         * 而"发出去了、bot 没给结论"既不算失败也不算成功，retry_ 不涨 ——
         * 这正是 13 次重复发送能穿过所有闸的原因。
         * 本字段读 send_n_（Keys.sendAttempts），按天记，跨天自动归零。
         */
        public boolean sendAttemptsExhausted;
        /**
         * manual 时是否仍跳过「今天已签 / 已发出待结论」。
         *
         * 为什么需要它（2026-09-28 用户反馈）：
         *   manual 的语义是「用户显式操作，绕过今日进度限制」，本意是别拦用户。
         *   但**批量入口**（「立即签到」的全部、一键签全部账号）也走 manual，
         *   于是把今天已经签过的目标又重发一遍 —— 用户原话：
         *   「我签过的又给我重复了一遍」「签过的没人会再二次签的吧」。
         *   重发既浪费当日动作配额，也可能被 bot 判为异常请求。
         *
         * 所以拆成两个维度：
         *   manual      = 用户点的（豁免节流/重试上限/退避）
         *   skipSigned  = 批量语义：只要今天有结论（已签 / 已发出）就别再发
         * 单目标「立即签到」保持 skipSigned=false —— 用户点得这么具体，
         * 通常是有原因的（怀疑没签上），保留强制重签的能力。
         */
        public boolean skipSigned;
        /**
         * 结果未知、等用户处置（「待确认」）。
         *
         * （2026-09-30 用户实测）：转「待确认」时会清空 sent_at_/retry，
         * 而闸从不看该标记 → 每轮心跳都判定"可以发" → 又超时 → 又转待确认，无限刷。
         * 现象：每次打开 TG 就在群里重发一次签到文本（那个群没有 bot）。
         * manual（用户点重试/测试）仍放行。
         */
        public boolean pendingUnconfirmed;
    }

    /**
     * 统一决策：返回 SKIP_* 之一。
     *
     * 顺序有意如此（从"最确定的拒绝"到"最不确定的"）：
     *   已签 > 在途 > 已发出待结论 > 用尽 > 退避 > 停用
     * manual 只豁免"已签/用尽/退避"这类**今日进度**限制，
     * 不豁免"在途"（避免并发双发）。
     */
    public static int decideSign(SignGate g) {
        if (g == null) return SKIP_NONE;
        // 账号停用是**账号级**开关：整个账号不参与任何自动签到。
        // 放在最前，且 manual 也不放行 —— 否则「停用」形同虚设：
        // 用户停用后点一下「立即签到」就能绕过，与开关语义矛盾。
        if (g.accountDisabled) return SKIP_ACCOUNT_DISABLED;
        if (g.inFlight) return SKIP_IN_FLIGHT;                 // 并发保护，manual 也不放行
        if (!g.manual) {
            if (g.signedToday) return SKIP_ALREADY_SIGNED;
            if (g.sentPendingFresh) return SKIP_SENT_PENDING;
            if (g.pendingUnconfirmed) return SKIP_PENDING_CONFIRM;
            if (g.retryExhausted) return SKIP_RETRY_EXHAUST;
            // 发送次数硬闸：与 retryExhausted 并列，但覆盖"发了没结论"这一路。
            // 放在退避之前 —— 它是当日总量上限，与"还要等多久"无关。
            if (g.sendAttemptsExhausted) return SKIP_SEND_ATTEMPTS_EXHAUST;
            if (g.inBackoff) return SKIP_BACKOFF;
            if (g.disabled) return SKIP_DISABLED;
        } else {
            // 批量语义：用户要求「只签未签的」。已签 / 已发出待结论都算「今天有结论了」，
            // 跳过它们（后者可能已经签上，重发就是重复）。
            if (g.skipSigned) {
                if (g.signedToday) return SKIP_ALREADY_SIGNED;
                if (g.sentPendingFresh) return SKIP_SENT_PENDING;
            }
            // ── 2026-10-14：manual 也必须遵守「当日发送上限」与「退避」──
            // 事故：用户点「签全部账号」是 manual(force=true)，而 manual 分支
            // 完全没查 sendAttemptsExhausted / retryExhausted / inBackoff，
            // 于是连点两次就绕过全部上限 —— 日志实测同一目标发到第 10 次，
            // 每次 sendSign 都有同步落盘的 commit()，界面因此明显卡顿。
            // manual 的语义是「用户明确要求，跳过『已签/在途』这类状态判断」，
            // 不是「可以无限重复发送」。
            if (g.sendAttemptsExhausted) return SKIP_SEND_ATTEMPTS_EXHAUST;
            if (g.retryExhausted) return SKIP_RETRY_EXHAUST;
            if (g.inBackoff) return SKIP_BACKOFF;
            if (g.disabled) return SKIP_DISABLED;
        }
        return SKIP_NONE;
    }

    /** 跳过原因的短标签（进日志，便于统计"到底被谁拦住了"）。 */
    public static String skipLabel(int code) {
        switch (code) {
            case SKIP_ALREADY_SIGNED: return "今天已签";
            case SKIP_IN_FLIGHT:      return "请求在途";
            case SKIP_SENT_PENDING:   return "已发出待结论";
            case SKIP_RETRY_EXHAUST:  return "今日重试已用尽";
            case SKIP_BACKOFF:        return "退避中";
            case SKIP_DISABLED:       return "已停用/冻结/排除";
            case SKIP_SEND_FAIL:      return "发送失败（会话数据取不到）";
            case SKIP_ACCOUNT_DISABLED: return "账号已停用";
            case SKIP_PENDING_CONFIRM: return "待确认（等用户处置）";
            case SKIP_SEND_ATTEMPTS_EXHAUST: return "今日发送次数已达上限";
            default:                  return "";
        }
    }

    // ────────────────────────────────────────────────────────────────
    // ─────────────────── 收藏夹摘要开关（按账号解析，2026-10-27）───────────────────

    /**
     * 解析「收藏夹摘要」开关的**实际生效值**。
     *
     * 故障背景（实测现场）：
     *   Core 里原来是**单个内存字段** SAVED_MSG_ON，取值走 cfgBool("saved_msg")，
     *   而 cfgBool 读的是 `acc{currentAccount()}_cfg_saved_msg` —— 只有「当前选中账号」
     *   的那一个键。可发送端是按 accountSlots() **遍历所有账号**，于是：
     *     · 只关了一个账号 → 其它账号读不到键 → 回退默认 true → 照发；
     *     · 停在关掉的那个账号 → 遍历到的所有账号被一起抑制（反向串扰）。
     *   官方宿主 prefs 实测：acc0_cfg_saved_msg=false，而 acc1 / acc3 无该键。
     *
     * 取值顺序（与 cfgBool 完全同序，只是把「哪个账号」显式化）：
     *   ① 本账号键存在       → 用它（**绝不去读别的账号的键**）
     *   ② 否则全局 jmb_ 键存在 → 用它（历史数据 / 迁移兜底）
     *   ③ 都没有             → def
     *
     * @param accountVal 本账号键的值；键不存在传 null
     *                   （null = 没设置过，false = 用户显式关了 —— 语义相反，不可混用）
     * @param globalVal  全局 jmb_ 键的值；不存在传 null
     * @param def        代码默认值
     */
    public static boolean resolveSavedMsgOn(Boolean accountVal, Boolean globalVal, boolean def) {
        if (accountVal != null) return accountVal.booleanValue();
        if (globalVal != null) return globalVal.booleanValue();
        return def;
    }

    /**
     * 收藏夹简报是否允许发送 = **账号级**收藏夹开关 ∧ 通知总开关。
     *
     * 两个开关职责不同，缺一不可 —— 不要因为「有两处判断」就删掉其中一个：
     *   · savedMsgOn —— 「往收藏夹发不发」，账号级，设置页可单独关；
     *   · notifyOn   —— 「签到结果通知」总开关，历史上就是摘要的上游闸门，
     *                    产品语义是「开了通知才有摘要」，本次修复**不改**这个语义。
     * 返回 false 时，心跳 / 轮次结束 / 失败告警三条入口都不应发送。
     */
    public static boolean canSendSavedSummary(boolean savedMsgOn, boolean notifyOn) {
        return savedMsgOn && notifyOn;
    }

    // 账号索引归一化（纯逻辑，可单测）
    //
    // AccountManager.current() 的决策核心抽到这里，便于测试覆盖多账号边界。
    // 规则：
    //   raw < 0   -> 0    （负值不可能合法；不能返回负数，
    //                      否则 prefix() 会拼出 acc-1_ 垃圾分区）
    //   其它      -> raw  （原值使用，不做上限钳制）
    //
    // 为什么不再钳到 total-1：selectedAccount 读到的值**不等于**账号的「第几个」。
    // 实测部分客户端（如 Nagram XF 登录 4 个账号）会读到 7、9，那些是**合法索引**，
    // 数据就存在 acc9_ 里。钳到 total-1 会让模块读到**另一个账号**的分区 ——
    // 表现为「账号1的配置变成了账号2的」。真实槽位由 TGAutoSignCore.accountSlots()
    // 从 SharedConfig.activeAccounts 读取，界面序号由 displayIndexOf() 换算。
    // ────────────────────────────────────────────────────────────────

    /** 归一化后的账号索引：只把负值退回 0，其余原值返回。 */
    public static int clampAccount(int raw, int total) {
        if (raw < 0) return 0;
        return raw;
    }

    /**
     * 索引是否越界。
     *
     * 语义（与设备实测行为一致）：raw >= 0 && total > 0 && raw >= total。
     * 注意**负值不算越界** —— 负值由 clampAccount 单独退回 0，
     * 调用方（AccountManager）按 c < 0 自行告警。
     */
    public static boolean accountOutOfRange(int raw, int total) {
        return raw >= 0 && total > 0 && raw >= total;
    }

    // ────────────────────────────────────────────────────────────────
    // 「已发出」状态的生命周期（纯逻辑，可单测）
    //
    // 背景（用户报的 bug，2026-09-27）：
    //   cb（回调按钮）目标发出后状态显示「已发出」。若该 bot 把结论放在
    //   callback answer 里、不再另发消息，回复判定永远等不到 → kLast 永不写。
    //   等 sent_at_ 过了时效（30 分钟），状态会**退回「待签」**，于是被重新
    //   排期重发。用户感受：签上了却显示已发出，过一阵又变回没签。
    //
    // 修法：把「发出多久」这件事抽成纯函数，Core 只负责读写 prefs。
    //   时效内            -> SENT_FRESH   显示「已发出」
    //   超时但今天没结论  -> SENT_STALE   转「待确认」，给用户处置入口
    //   没发过            -> SENT_NONE    显示「待签」
    // ────────────────────────────────────────────────────────────────

    public static final int SENT_NONE  = 0;   // 今天没发过
    public static final int SENT_FRESH = 1;   // 已发出，还在等结论的时效内
    public static final int SENT_STALE = 2;   // 发出过但已超时效、仍无结论

    /**
     * 判定某目标「今天发出过、且处于什么阶段」。
     *
     * @param sentToday  今天是否发过（有 opt_ 标记）
     * @param sentAtMs   发出时刻（0 表示取不到）
     * @param nowMs      当前时刻
     * @param ttlMs      等结论的时效
     *
     * 注意：`sentAtMs <= 0` 且有 opt_ 标记时**保守视为还在时效内** ——
     * 取不到时间戳多半是刚重启或跨账号写入，当成"过期"会导致重复发送。
     * 这与 isSentPendingFresh 的既有语义保持一致。
     */
    public static int sentPhase(boolean sentToday, long sentAtMs, long nowMs, long ttlMs) {
        if (!sentToday) return SENT_NONE;
        if (sentAtMs <= 0L) return SENT_FRESH;
        long age = nowMs - sentAtMs;
        if (age < 0L) return SENT_FRESH;        // 时钟回拨，保守处理
        return age > ttlMs ? SENT_STALE : SENT_FRESH;
    }

    /**
     * 是否应当把「已发出」升级为「待确认」。
     *
     * 只在"发了、超时、今天还没结论、也没被处置过"时升级 ——
     * 已签 / 已有待确认 / 已放弃 都不该被覆盖。
     */
    public static boolean shouldPromoteToPending(boolean sentToday, long sentAtMs,
                                                 long nowMs, long ttlMs,
                                                 boolean signedToday, boolean alreadyPending,
                                                 boolean retryExhausted) {
        if (signedToday || alreadyPending || retryExhausted) return false;
        return sentPhase(sentToday, sentAtMs, nowMs, ttlMs) == SENT_STALE;
    }

    // ────────────────────────────────────────────────────────────────
    // 按钮「可学性」判定 —— 已于 2026-09-28 整体移除
    //
    // 这里曾有一套启发式过滤器（data 前缀黑名单 pay: / ub_menu_ 等、文案黑名单
    // 支付/充值/绑定/菜单 等、随机 hex token），用来"猜"某个按钮是不是签到按钮。
    //
    // 它被证明是错的，而且两类错误同时发生：
    //   · 误伤真签到按钮 —— 用户点了没反应，界面上还毫无提示（只写进日志）。
    //     实测 EmbyPulse 面板：该 bot 的签到入口叫 ub_back_menu，撞上 ub_menu_
    //     前缀；按钮文案「🔙 主菜单」又撞上「菜单」二字。用户以为"加不了"。
    //   · 漏挡新菜单 —— 每个 bot 的菜单文案都不一样，黑名单永远列不全。
    //
    // 更根本的是判据错了：**用户点按钮这个动作本身就是意图**，
    // 不需要模块替他判断"这个像不像签到"。点了就学，是用户的自由。
    // 实测代价也印证了这一点：真签到按钮被拦 = 用户完全签不了；
    // 误学一个菜单按钮 = 多点一次、日志多一条。两者严重性根本不对等。
    //
    // 现在只剩两类**用户自己定的**准入判断（见 TGAutoSignCore.learnDenyReason）：
    //   · 排除的 bot —— 用户明确拉黑的整只 bot
    //   · 排除规则   —— 用户自己写的关键词 / 正则
    // 另加一个精确机制（不是猜测）：模块自身发出的请求不学习
    // （consumeSelfCbRequest，这才是"目标自己冒出来"的正解）。
    // ════════════════════════════════════════════════════════════════
    // 执行结果归类（2026-09-28）
    //
    // 问题：改前有 5 处散落的 if-else 决定成败，没有一处能回答
    //   「这个目标今天到底怎么了」——「待确认」「已签」「已发出」
    //   混在一起，而且「待确认」这个名字还被网络学习候选池占用。
    //
    // 现在：每次执行必须落到**恰好一个**归类。归类是唯一真相源，
    //   界面文案、处置入口、是否重试全部由它派生。
    //
    // 关键设计：**「不知道」是一等公民**。
    //   改前模块总想给个答案（判不出→待确认、没回复→保留已签），
    //   用模糊掩盖不确定。现在明确说「我不知道，原因是这个」。
    // ════════════════════════════════════════════════════════════════

    /** 结果码。全流程只有这 6 种，互斥。 */
    public static final int R_SIGNED        = 0;  // 确认签到成功（命中成功词 / bot 说已签过）
    public static final int R_FAILED        = 1;  // 明确失败（命中失败词 / 永久错误）
    public static final int R_BTN_STALE     = 2;  // 按钮失效：面板消息过期或未就绪
    public static final int R_REPLIED_UNK   = 3;  // bot 回复了，但判不出结果（词表没覆盖）
    public static final int R_NO_REPLY      = 4;  // bot 全程没回复
    public static final int R_JUDGE_OFF     = 5;  // 用户关闭了自动判定，模块不替 bot 下结论
    /**
     * 今日已用尽（2026-10-01 新增）：bot 回「次数上限 / 请明日再试」。
     * 今天不必再管，但**不代表签到成功** —— 界面要说清，别让用户误以为签上了。
     */
    public static final int R_EXHAUSTED     = 6;

    /** 归类的稳定标识串（存进 pendcfm_note_，跨版本可读）。 */
    public static String resultCode(int r) {
        switch (r) {
            case R_SIGNED:      return "signed";
            case R_FAILED:      return "failed";
            case R_BTN_STALE:   return "btn_stale";
            case R_REPLIED_UNK: return "replied_unknown";
            case R_NO_REPLY:    return "no_reply";
            case R_JUDGE_OFF:   return "judge_off";
            case R_EXHAUSTED:   return "exhausted";
            default:            return "unknown";
        }
    }

    /** 标识串 → 结果码；无法识别返回 -1（调用方按"无归类"处理）。 */
    public static int resultOfCode(String code) {
        if (code == null) return -1;
        switch (code.trim()) {
            case "signed":          return R_SIGNED;
            case "failed":          return R_FAILED;
            case "btn_stale":       return R_BTN_STALE;
            case "replied_unknown": return R_REPLIED_UNK;
            case "no_reply":        return R_NO_REPLY;
            case "judge_off":       return R_JUDGE_OFF;
            default:                return -1;
        }
    }

    /** 该归类是否需要用户处置（界面据此决定是否进「待处理」聚合条）。 */
    public static boolean needsAttention(int r) {
        return r == R_BTN_STALE || r == R_REPLIED_UNK || r == R_NO_REPLY || r == R_JUDGE_OFF;
    }

    /** 该归类是否允许自动重试（false = 停止重试，等人）。 */
    public static boolean autoRetryable(int r) {
        switch (r) {
            case R_FAILED:    return true;    // 失败按退避重试
            case R_BTN_STALE: return true;    // 等新面板后可重试
            default:          return false;   // 其余一律停手
        }
    }

    /** 是否会写「今日已签」。只有 R_SIGNED 会。 */
    public static boolean countsAsSigned(int r) {
        return r == R_SIGNED;
    }

    /**
     * 进度提示词 —— 这些是「正在做」，**不是结果**。
     *
     * 2026-09-28：实测 `✅ 正在签到,请稍后...` 含「签到」二字，被
     * looksLikeResult 误判为「像是签到结果但没匹配上内置词」，
     * 于是刷警告日志 + 进诊断包 + 提示用户去补词。但它是进度提示，
     * 补词毫无意义（下一句才是结果）。全日志刷了 8 次，纯噪音。
     */
    public static final String[] PROGRESS_WORDS = {
            "正在签到", "正在查询", "正在处理", "正在加载", "正在执行", "正在获取",
            "请稍后", "请稍候", "稍等", "处理中", "加载中", "查询中",
            "processing", "please wait", "loading", "just a moment"
    };

    /** 这条回复是不是「进度提示」而非结果。 */
    /**
     * 广告 / 推广类特征词（命中即不值得学）。
     *
     * 2026-10-07 收窄：原表含「限时/优惠/福利/推广/广告」——
     * 这些词**会出现在真实的签到结果里**，例如
     *   「签到成功，限时福利已到账」「恭喜获得今日福利」
     * 一旦误杀，用户看到的就是"判不出、学习页还空的"（实测反馈）。
     * 现在只保留**不太可能出现在签到结论中**的铁广告特征。
     */
    public static final int CONF_LOW = 0;
    public static final int CONF_MEDIUM = 1;
    public static final int CONF_HIGH = 2;

    /**
     * 判定置信度（2026-10-07 · 方案 2.3）。
     * 让用户区分「真签上了」与「按兜底策略算签上了」。纯函数，不改判定结果。
     */
    public static int confidenceOf(String hitWord, boolean isCombo) {
        try {
            if (hitWord == null || hitWord.length() == 0) return CONF_LOW;
            if (isCombo || hitWord.indexOf('+') >= 0) return CONF_MEDIUM;
            if (hitWord.trim().length() <= 2) return CONF_MEDIUM;
            return CONF_HIGH;
        } catch (Throwable t) { return CONF_LOW; }
    }

    private static final String[] AD_WORDS = {
            "恭喜您获取到", "扩展任务", "独家算法", "严格验证", "远超同行",
            "注册链接", "推荐码", "邀请码", "返利", "佣金",
            "www.", "http://", "https://", "t.me/", "@所有人",
            // ── 2026-10-07 扩充（用户截图实证的漏网广告）──
            // 截图中这条 600+ 字的推广长文以上词一个都不含，所以照进了池子。
            "接广告合作", "广告合作", "免广告", "广告位",
            "博彩", "赌", "菠菜", "下注", "彩票",
            "打粉", "引流", "拉新", "曝光吧", "吃瓜",
            "付费广告", "发布广告", "广告投放", "私人定制", "定制服务",
            "成人", "美女图", "福利群", "资源群", "交流群",
            "私聊", "加微", "加v", "看片", "免费观看",
            "供需", "招代理", "接单", "上车",
    };

    /**
     * 这条回复值不值得进「未识别回复」池（2026-10-06 补）。
     *
     * 起因：上一轮把入库门槛从「TTL 内」放宽到「当天发过请求就收」，
     * 结果 bot 的推广长文也进了池，学习页被广告占满（用户截图 3 条全是广告）。
     *
     * 判别（**不**依赖 TTL）：
     *   ✗ 含广告/推广特征词            → 不收
     *   ✗ 超长（>120 字）且无签到语义   → 不收（说明/规则类长文）
     *   ✓ 其余照收（保留放宽后的行为）
     *
     * @param reply 原文
     * @return true = 值得沉淀
     */
    public static boolean worthLearning(String reply) {
        try {
            if (reply == null) return false;
            String r = reply.trim();
            if (r.length() < 2) return false;
            String low = r.toLowerCase();
            for (String w : AD_WORDS) {
                if (low.contains(w)) return false;
            }
            // 2026-10-18：再接一层公告判定。
            // AD_WORDS 只覆盖推广类，而 bot 的**公告尾巴**（防骗声明、价格表、
            // 免责声明、命令列表）一个 AD_WORDS 都不含，长度也够不到 400 阈值，
            // 于是全进池 —— 用户实测 10 条里 6 条。isLikelyAnnouncement 用
            // 「结果词优先放行 + 公告强特征词」两段式，不会误伤真结果。
            if (isLikelyAnnouncement(r)) return false;
            // 超长且不含签到语义 → 判为说明/规则长文，不收。
            // 2026-10-07：阈值 120 → 300。带排版的 bot 回复（说明+结果）常超 120，
            // 误杀会让用户遇到"判不出但学习页是空的"。
            // 2026-10-07：>400 字一律不收。
            // 真实的签到结果不会这么长；600+ 字的必然是推广/说明长文，
            // 而且它们会拖慢学习页（聚类、提词、EditText 渲染都要处理这几百字）。
            if (r.length() > 400) return false;
            if (r.length() > 300) {
                boolean hasSign = false;
                for (String w : SIGN_WORDS) {
                    if (low.contains(w)) { hasSign = true; break; }
                }
                if (!hasSign) return false;
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    /**
     * 从归一化文本里**算法化**地提一个推荐判定词（未识别回复重构 · 问题 3）。
     *
     * 为什么不再用固定词表：用户要学的恰恰是词表里没有的新措辞，
     * 硬编码的 46 个词永远提不出「任务达成」「办妥了」这类。
     *
     * 算法：
     *   ① 按分隔符切片；
     *   ② 丢噪声片段（纯 {N} / 单字 / 纯符号）；
     *   ③ 片段打分（与已知词有公共子串 +、句尾 +、长度 2~6 +、
     *      否定词 −、通用客套 −）；
     *   ④ 取最高分；全 0 返回 null（交给用户手填）。
     *
     * 纯函数、无 Android 依赖，可单测。
     *
     * @param norm 已归一化的文本（ReplyNormalizer.normalize 的输出）
     * @return 推荐词；提不出返回 null
     */
    public static String suggestWord(String norm) {
        try {
            if (norm == null) return null;
            String s0 = norm.trim();
            if (s0.length() == 0) return null;

            String[] parts = s0.split("[\\s,，、;；:：|/\\-—–\\[\\]（）(){}<>\"']+");
            String best = null;
            int bestScore = 0;
            for (int i = 0; i < parts.length; i++) {
                String seg = parts[i] == null ? "" : parts[i].trim();
                if (seg.length() == 0) continue;
                // ② 噪声：纯数字占位 / 单字符 / 纯符号
                if (seg.equals("{n}") || seg.equals("{N}")) continue;
                if (seg.length() < 2) continue;
                boolean allSym = true;
                for (int k = 0; k < seg.length(); k++) {
                    char c = seg.charAt(k);
                    if (Character.isLetterOrDigit(c) || c > 0x2E80) { allSym = false; break; }
                }
                if (allSym) continue;

                int score = 0;
                // 长度：2~6 最佳
                int L = seg.length();
                if (L >= 2 && L <= 6) score += 3;
                else if (L <= 10) score += 1;
                else score -= 1;

                // 与已知判定词有公共子串 → 同类语义
                if (sharesWithKnown(seg)) score += 4;

                // 句尾片段（中文结论常在尾部）
                if (i == parts.length - 1) score += 2;

                // 否定词 → **直接跳过**（不推荐）。
                // 早先是 -5 分，但「还没有签到」这类句子：
                //   长度 +3、与已知词共现 +4、句尾 +2 = +4，扣完仍是最高分被选中，
                //   用户照抄就会把一个否定句学成成功词。整段跳过才是安全做法。
                boolean negated = false;
                for (String nw : NEG_WORDS_CN) { if (seg.contains(nw)) { negated = true; break; } }
                if (!negated) {
                    for (String nw : NEG_WORDS_EN) { if (seg.contains(nw)) { negated = true; break; } }
                }
                if (negated) continue;

                // 通用客套 → 判别力低
                if (seg.contains("欢迎") || seg.contains("感谢") || seg.contains("谢谢")
                        || seg.contains("请") || seg.contains("如有") || seg.contains("客服")
                        || seg.contains("详情") || seg.contains("说明") || seg.contains("帮助")
                        || seg.contains("菜单") || seg.contains("命令")) score -= 3;

                // 进度提示词 → 不该推荐（那是过程不是结论）
                for (String pw : PROGRESS_WORDS) { if (seg.contains(pw)) { score -= 4; break; } }

                if (score > bestScore) { bestScore = score; best = seg; }
            }
            return best;   // 可能为 null
        } catch (Throwable t) {
            return null;
        }
    }

    /** 片段是否与已知成功/失败词有 ≥2 字公共子串（同类语义的弱证据）。 */
    private static boolean sharesWithKnown(String seg) {
        try {
            for (String w : KNOWN_LEXICON) {
                if (w == null || w.length() < 2) continue;
                for (int i = 0; i + 2 <= w.length(); i++) {
                    String bi = w.substring(i, i + 2);
                    if (seg.contains(bi)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 「大概率是签到语义」的二字词根库（只用于给推荐词打分，不用于判定）。 */
    private static final String[] KNOWN_LEXICON = {
            "签到", "打卡", "签领", "领取", "已签", "完成", "成功", "失败", "奖励",
            "积分", "获得", "已获", "达成", "办妥", "已办", "结算", "到账", "发放",
            "领取", "过期", "结束", "上限", "用尽", "再来", "重试", "绑定", "关注",
            "check", "sign", "claim", "reward", "success", "fail", "done", "complete"
    };

    public static boolean looksLikeProgress(String reply) {
        if (reply == null) return false;
        String lr = reply.toLowerCase(java.util.Locale.US);
        for (String w : PROGRESS_WORDS) {
            if (lr.contains(w)) return true;
        }
        return false;
    }

    /** 「命令菜单 / 使用说明」类回复的特征词。
     *
     *  为什么单独一类（2026-10-14 用户反馈「hope 那种问题」）：
     *  很多 bot 首次交互会推一大段「可用命令 /start /my /sign ... 使用说明」。
     *  这段文字**含有 sign 字样**，于是 looksLikeSignResult 返回 true，
     *  被归类成「回复判不出」→ 用户看到「未识别」以为模块坏了。
     *  实际上它压根不是签到结果，只是功能菜单。单独识别后直接归「不适用」。
     */
    private static final String[] MENU_HELP_WORDS = {
            "可用命令", "使用说明", "命令列表", "使用示例", "使用帮助",
            "注册账户", "个人信息", "邀请链接", "自助充值", "联系我们",
            "使用教程", "功能说明", "指令列表", "菜单如下", "回复数字选择",
            "/help", "/start", "help", "menu"
    };

    /** MessageObject.messageText 对「无文字的媒体消息」的渲染值。
     *  实测（TG 13.0.1）：纯图片消息渲染成「图片」，贴纸/视频同理。 */
    private static final String[] MEDIA_PLACEHOLDER = {
            "图片", "照片", "贴纸", "视频", "动画", "文件", "音频", "语音", "视频消息",
            "photo", "sticker", "video", "animation", "document", "audio", "voice"
    };

    /** 这段文本是否只是「媒体占位符」（不是真实回复内容）。 */
    public static boolean isMediaPlaceholderText(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() == 0 || t.length() > 8) return false;
        String lt = t.toLowerCase(java.util.Locale.US);
        for (String w : MEDIA_PLACEHOLDER) {
            if (t.equals(w) || lt.equals(w)) return true;
        }
        return false;
    }

    /** 这条回复是否像「命令菜单 / 使用说明」而不是签到结果。 */
    public static boolean looksLikeMenuHelp(String reply) {
        if (reply == null || reply.length() == 0) return false;
        int hits = 0;
        String lr = reply.toLowerCase(java.util.Locale.US);
        for (String w : MENU_HELP_WORDS) {
            String lw = w.toLowerCase(java.util.Locale.US);
            if (lr.contains(lw)) hits++;
            if (hits >= 2) return true;      // 至少两个特征词才算菜单，避免误伤
        }
        return false;
    }

    /**
     * 这条回复是否**可能**是签到结果（含签到类字眼）。
     * 用于区分「菜单/查询类回复（正常，不该烦用户）」与
     * 「看起来是结果但词表没覆盖（值得提示补词）」。
     *
     * 2026-09-28：进度提示词先行排除 —— 它们必然含「签到」，否则会全被误判。
     */
    /**
     * 这条「认不出」的回复，是不是**公告 / 广告 / 免责声明**？
     *
     * 2026-10-18 新增。为什么必须有：
     *   未识别回复池原来只过滤「不像签到结果」，但 bot 的公告尾巴同样含
     *   「签到」「积分」等字样，于是整段防骗声明全被收进池 ——
     *   用户实测 10 条里 6 条是广告，真正要判的一条也没有，
     *   既看不清该判哪条，也让渲染量翻几倍（卡顿的主因之一）。
     *
     * 设计原则：**宁可漏收，不可错收**。
     *   只收录公告语料里高频、且几乎不会出现在「本次签到结论」里的词。
     *   真结果会含「成功/失败/已签/获得/积分+N」——本函数里凡是带这些
     *   结果词的句子，一律放行（返回 false），交给判定链去处理。
     *
     * @param reply 回复原文
     * @return true = 公告类，不要进未识别池
     */

    /** 公告判定的长度阈值（对**归一化后**的有效正文计算，见 effectiveBodyLength）。 */
    static final int ANNOUNCE_LENGTH_LIMIT = 180;

    /**
     * 有效正文长度：剥掉 URL / 邀请链接后再计长度。
     *
     * 2026-10-29 新增。为什么需要：
     *   bot 的真结果正文通常很短，但会把「一键分享」的长 URL 拼在尾部，
     *   一条 184 字的真结果能被 URL 撑到 797 字 —— 旧实现用整串长度判定，
     *   真签到被当成超长公告否决（8286060286 / mid=14613）。
     *
     * 处理原则（保守，绝不切断正文）：
     *   · 只整段移除 **完整的 URL token**（http:// https:// t.me/ 与裸域名 www.x.y），
     *     从协议头一直吃到下一个空白/换行/中文字符为止；
     *   · URL 内部的 %XX 编码、查询参数原样跳过，不做解码、不做截断推断；
     *   · 未被空白分隔的 URL 若紧贴中文（少见），以首个非 ASCII 字符为界停下，正文不受损。
     *
     * @return 剥离 URL 后的字符数（用于公告判定的长度证据）
     */
    public static int effectiveBodyLength(String reply) {
        if (reply == null || reply.length() == 0) return 0;
        String s = stripUrls(reply);
        return s.trim().length();
    }

    /** 移除文本中的完整 URL，返回剩余正文（不改写、不截断正文）。 */
    static String stripUrls(String text) {
        if (text == null || text.length() == 0) return "";
        StringBuilder out = new StringBuilder(text.length());
        int i = 0, n = text.length();
        while (i < n) {
            int st = urlStart(text, i);
            if (st < 0) { out.append(text.charAt(i)); i++; continue; }
            i = st;
            // 吃掉 URL 本体：直到空白/换行/中文字符/全角标点为止
            while (i < n) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r' || c == '\t' || c == ' ') break;
                // 中文与全角标点属于正文，遇到即停（URL 里不会出现未编码的这些字符）
                if (c >= 0x2E80 && c <= 0x9FFF) break;
                if (c >= 0x3000 && c <= 0x303F) break;
                if (c >= 0xFF00 && c <= 0xFFEF) break;
                // emoji 也是正文边界
                if (Character.isHighSurrogate(c)) break;
                i++;
            }
            // 用等长空白占位，保留行结构（避免前后两段粘连成新词）
            for (int k = st; k < i; k++) out.append(' ');
        }
        return out.toString();
    }

    /** 若 i 处是一个 URL 的起点，返回其起始下标；否则 -1。 */
    private static int urlStart(String t, int i) {
        int n = t.length();
        String[] PFX = {"https://", "http://", "t.me/", "www."};
        for (String p : PFX) {
            if (i + p.length() <= n && t.regionMatches(true, i, p, 0, p.length())) {
                // 允许出现在句首/空白/标点之后
                return i;
            }
        }
        return -1;
    }

    public static boolean isLikelyAnnouncement(String reply) {
        if (reply == null || reply.length() == 0) return false;
        String lr = reply.toLowerCase(java.util.Locale.US);

        // ① 先看有没有明确结果词 —— 有就当结果，绝不当公告
        String[] RESULT_HINT = {
                "签到成功", "打卡成功", "签到失败", "打卡失败", "已签到", "已打卡",
                "已经签到", "已经打卡", "签到完成", "打卡完成", "积分+",
                "领取成功", "次数已用完", "请明日再试", "明日再来",
                "success", "failed", "already",
        };
        for (String h : RESULT_HINT) {
            if (lr.contains(h)) return false;
        }
        // 2026-10-25 加固（**防漏判**）：内置表命中也算「结果词」——
        // 否则「恭喜完成签到，欢迎加入官方频道 @x」这类**真结果**因为含公告特征词
        // 会被判成公告 → 不判成功 → 用户以为没签上。
        // 但放行范围必须窄：只认「重复/已签过」表，以及带**结论形态**（成功/完成/完毕…）
        // 的成功词 —— 「获得积分」这类弱词是教程句的高频词，放行会让教程被误判成功
        //（既有单测「教程：可随机获得积分 → 公告」就是卡这条）。
        if (matched(lr, DUP_WORDS_DEFAULT) != null) return false;
        String _okw = matched(lr, OK_WORDS_DEFAULT);
        if (_okw != null) {
            for (String strong : new String[]{"成功", "完成", "完毕", "失败",
                    "success", "failed", "claimed", "already"}) {
                if (_okw.contains(strong)) return false;
            }
        }
        // 「获得积分」不能当裸词 —— 教程句也含它：
        //   「进行普通签到可以随机获得积分」（教怎么签到，不是结果）
        // 真结果一定带数字：「获得积分:2」「获得积分 +3」「获得积分 5」。
        int gi = lr.indexOf("获得积分");
        while (gi >= 0) {
            int p = gi + "获得积分".length();
            // 跳过空格与分隔符后必须紧跟数字或 +/- 才算结果
            while (p < lr.length() && (lr.charAt(p) == ' ' || lr.charAt(p) == ':'
                    || lr.charAt(p) == '：' || lr.charAt(p) == '=')) p++;
            if (p < lr.length()) {
                char cc = lr.charAt(p);
                if (Character.isDigit(cc) || cc == '+' || cc == '-') return false;
            }
            gi = lr.indexOf("获得积分", gi + 1);
        }

        // ② 公告强特征词：命中任意一个即判公告
        String[] ANNOUNCE = {
                // 防骗 / 广告
                "广告", "骗子", "谨防", "防骗", "上当", "社工库", "非官方",
                "不要点", "别点", "假冒", "冒充", "钓鱼",
                // 免责 / 声明
                "免责", "声明", "最终解释权", "官方频道", "请认准", "永久唯一",
                "认准唯一", "谨记",
                // 招募 / 推广 —— 2026-10-28：**降为弱特征**（见 ANNOUNCE_WEAK）
                // 为什么：真结果尾部常挂"邀请好友 +100盒能"这类推广，
                // 强特征会把它整条判成公告 → 真签到被漏判。
                // 教程 / 菜单 / 导航
                "使用说明", "使用帮助", "命令列表", "查看全部", "怎么用",
                "点击下方", "点击上方", "请选择功能", "请选择操作",
                // 2026-10-18 单测抓出的漏网：bot 常把运维说明附在回复里，
                // 用户截图那条「抬头向上看…价格表…提示被注销」就是。
                "价格表", "查询价格", "路线查询", "提示被注销", "无法使用可以",
                "其他机器人", "下方按钮", "抬头向上看",
                // 2026-10-18 真机样本验证补漏：这类是**教程**（教怎么签到），不是结果
                "可以随机获得", "可随机获得", "进行普通签到", "普通签到可以",
                "任意一个给机器人", "给机器人进行", "表情给机器人",
                // 商城 / 充值引导 —— 2026-10-28：同样降为弱特征
                // 英文公告
                "advertisement", "scam", "disclaimer", "official channel",
        };
        for (String w : ANNOUNCE) {
            if (lr.contains(w)) return true;
        }

        // ②b 弱公告特征（这些词**本身也可能是签到结果**，例如“配给领取”）：
        //     只有**没有结果形态**时才当公告 —— 否则该 bot 的真结果会被误杀
        //     （2026-10-28 P1：8286060286 的「[ 配给领取 ] 连续服役: 2 天 · 获得盒能: +50」被判成公告 → 漏判）
        String[] ANNOUNCE_WEAK = {
                "配给领取", "可以随机获得", "可随机获得", "进行普通签到",
                // 推广/招募/商城：既是推广话术，也常是**真结果的尾巴**
                "邀请链接", "邀请1位", "邀请好友", "拉人", "推广", "招募",
                "去商城", "去充值", "开通会员", "购买",
        };
        for (String w : ANNOUNCE_WEAK) {
            if (lr.contains(w) && !hasSuccessShape(lr)) return true;
        }

        // ③ 超长且无结果词 → 大概率是公告/说明
        //    真结论通常很短（「✅ 签到成功，积分+3」）；
        //    180 字以上还一个结果词都没有的，基本是模板。
        //
        // 2026-10-29 修复（真实事故：8286060286 / mid=14613 / mid=14615）：
        //   纯长度兜底**不能否决已有的可靠结果证据**。
        //   真实回复正文仅 184 字（「✨ [ 配给领取 ] 连续服役: 3 天 · 获得盒能: +50 …」），
        //   后面再挂 595 字符的推广 URL —— 整条 797 字。
        //   旧实现先判「>180 → 公告」，真签到整条被判公告，
        //   okChain 里 continue → 最终落到 V_UNKNOWN(2)，日志现形：
        //   「这条回复像是签到结果，但没匹配上内置词」。
        //
        //   现在：① 长度改用 effectiveBodyLength（先剥 URL / 邀请链接，推广尾巴不再撑大长度）
        //        ② hasSuccessShape 成立（签到语境 + 强结论词/完成态/业务数字）时，
        //           长度一律不再否决，交给判定链
        //        ③ 无结果证据的长文本仍照旧判公告（保留原有识别能力）
        if (effectiveBodyLength(reply) > ANNOUNCE_LENGTH_LIMIT && !hasSuccessShape(lr)) return true;

        return false;
    }

    /** 该成功词是否**必须**有「签到语境」才算数。
     *
     *  2026-10-25 审查新增（防误判）：内置表里「已获得」「获得积分」「签到获得」「积分 +」
     *  这类是**宽词** —— 查询类 bot 回一句「您的查询已获得结果」就会被判成签到成功。
     *  纯英文/数字词（success / claimed / already）不算宽词：英文 bot 回 "Success!"
     *  本来就该算结果，加语境要求会造成漏判。
     */
    // ─────────────── 成功证据（2026-10-28 P0/P1 修复）───────────────

    /** 强结论词：本身带签到语义且是结论形态 —— 命中即可判成功，不需要额外证据。 */
    private static final String[] STRONG_RESULT = {
            "签到成功", "打卡成功", "签到完成", "打卡完成", "签到已完成", "打卡已完成",
            "已成功签到", "签到已成功", "成功签到", "成功打卡", "完成签到", "完成打卡",
            "签到完毕", "打卡完毕", "领取成功", "领取完毕", "任务完成", "任务达成",
            "check-in complete", "claimed", "success",
    };

    /**
     * **通用高频词**：这些词在任何 bot 回复里都可能出现，单独命中**不足以**证明签到成功。
     * 采用「完全相等」匹配 —— 所以「任务完成」「配给领取」「办妥了」这类具体说法不受影响。
     */
    private static final String[] GENERIC_WORDS = {
            // 用户词表里出现过的宽词（2026-10-28 prefs 快照）
            "成功", "完成", "欢迎", "恭喜", "恭喜您", "领取", "获得", "签到", "打卡",
            "积分", "获得积分", "签到获得", "签到奖励", "已获得", "签到 +1", "打卡 +1",
            "配给", "哭哭", "社工库功能", "欢迎使用hope",
            // 内置表里的宽词
            "已参与", "参与成功", "领取完成", "获得奖励", "积分 +",
            // 常见礼貌/状态词
            "感谢", "谢谢", "好的", "收到", "已处理", "处理成功", "提交成功", "已收到",
    };

    /** 面板/账户类文本特征：命中即视为"信息展示"，不构成签到结论。 */
    private static final String[] PANEL_HINT = {
            "账户信息", "会员状态", "账户余额", "累计推广", "今日推广", "支持的查询",
            "查询类型", "可用积分", "积分获取方式", "个人信息", "我的资料",
    };

    /** 完成态：表示“这件事已经发生过了”。 */
    private static final String[] DONE_STATE = {
            "已领取", "已签到", "已打卡", "已参与", "今日已", "已经领", "已配给",
            "距下次", "剩余", "连续", "已获得",
            // 2026-10-28 补：这几类在真实回复里极常见（如「你今天已经签到了」），
            // 漏了会让泛词\/完成态判定集体失灵
            "已经签", "已经打卡", "今天已经", "今日已签", "已签过", "已完成",
    };

    /** 教程 / 说明 / 帮助形态：这类文本里的“签到/领取”是在教怎么做，不是结论。 */
    private static final String[] TUTORIAL_HINT = {
            "可获得", "可获取", "可以获得", "获取方式", "获取方法", "使用方法", "使用说明",
            "演示", "示例", "例如", "格式", "怎么用", "如何", "教程", "说明：", "详情",
            "积分获取", "积分获得方式",
    };

    /** 是否属于“教程/帮助/说明”文本（含签到动词也不算结论）。 */
    static boolean looksLikeTutorialOrHelp(String lower) {
        if (lower == null) return false;
        for (String w : TUTORIAL_HINT) if (lower.contains(w)) return true;
        return false;
    }

    /**
     * 「结果形态」：文本是否具备**签到结果的形态证据**。
     *
     * 为什么需要（2026-10-28 真实事故）：
     *   用户自定义词表里有「欢迎」「成功」「恭喜您」这类**泛词**，
     *   而欢迎语 / 帮助说明 / 操作提示里到处是这些字 ——
     *   结果是「未签到却显示成功」（日志 10-25 02:37:08 两条，TG 原始消息为欢迎语与帮助文本）。
     *
     * 形态 = ① 有签到语境（签到/打卡/领取/配给/check/sign/claim/daily/qd）
     *        + ② 且不是教程句
     *        + ③ 且满足任一结论证据：
     *             · 强结论词（签到成功 / 领取成功 …）
     *             · 完成态（已领取 / 今日已 / 距下次 …）
     *             · 业务数字（获得盒能: +50 / 积分 3 / 服役: 2 天 / +1）
     *
     * 反例（必须判 false）：账户面板、帮助菜单、欢迎语、广告面板、查询说明。
     */
    static boolean hasSuccessShape(String lower) {
        if (lower == null || lower.length() == 0) return false;
        if (!hasSignContextEx(lower)) return false;
        if (looksLikeTutorialOrHelp(lower)) return false;
        // 面板/账户类：展示信息，不是签到结论（否则"当前积分：49 分"会因 积分+数字 被放行）
        for (String p : PANEL_HINT) if (lower.contains(p)) return false;
        for (String w : STRONG_RESULT) if (lower.contains(w)) return true;
        for (String w : DONE_STATE) if (lower.contains(w)) return true;
        return hasBusinessNumber(lower);
    }

    /** 签到语境（比 hasSignContext 多认“配给 / qd”这类账号私有说法）。 */
    static boolean hasSignContextEx(String lower) {
        if (hasSignContext(lower)) return true;
        String[] EXTRA = {"配给", "qd", "盒能", "积分"};
        for (String c : EXTRA) if (lower.contains(c)) return true;
        return false;
    }

    /** 文本里是否有“业务数字”证据（积分/盒能/奖励/服役/天数/次数/加号）。 */
    static boolean hasBusinessNumber(String lower) {
        if (lower == null) return false;
        // ① 必须有数字或加减号
        boolean digit = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isDigit(c) || c == '+' || c == '＋') { digit = true; break; }
        }
        if (!digit) return false;
        // ② 数字必须**紧邻**业务名词（同一子句内 ±6 字符）才算业务数字。
        //
        // 2026-10-29 收紧（真实反例，同一 bot 的推广句）：
        //   「【配给领取】邀请好友 +100盒能，快来领取吧！加入我们，收益翻倍。」
        //   旧实现只要文本里出现任意业务锚点（积分/盒能/领取…）+ 任意数字就成立，
        //   于是「邀请好友 +100盒能」这种**奖励宣传**也能过形态检查，
        //   配上用户词「配给」直接判签到成功。
        //   现在要求数字与业务名词相邻 —— 真结果形态都能过：
        //     "获得盒能: +50" / "盒能: 1596" / "连续服役: 3 天" / "打卡 +1" / "积分 +3"
        //   纯宣传句则不过（数字 100 与「邀请好友」相邻，不与本次结果相邻亦可，
        //   但「盒能」紧邻 100 时仍被算作业务数字 → 交给下面的排除表处理）。
        String[] ANCHOR = {"积分", "盒能", "奖励", "服役", "获得", "领取",
                "生命", "打卡", "签到", "配给", "qd"};
        // 排除：明显属于「邀请/拉人/注册赠送」的宣传数字，不是本次签到结果
        String[] PROMO = {"邀请", "拉人", "好友", "注册", "新人", "宣传", "推广",
                "返利", "收益", "翻倍", "抽奖", "赠送"};
        for (int i = 0; i < lower.length(); i++) {
            if (!Character.isDigit(lower.charAt(i))) continue;
            // 取数字左侧最近的业务名词
            String near = "";
            for (String a : ANCHOR) {
                int p = lower.lastIndexOf(a, i);
                if (p < 0) continue;
                if (i - (p + a.length()) > 6) continue;   // 太远不算
                if (isPromoContext(lower, p, i, PROMO)) continue;  // 宣传语境，跳过
                near = a;
                break;
            }
            if (!near.isEmpty()) return true;
        }
        return false;
    }

    /** 业务名词与数字之间/紧邻处是否夹着推广语（邀请、好友、注册…）。 */
    private static boolean isPromoContext(String lower, int anchorPos, int digitPos, String[] promo) {
        int from = Math.max(0, anchorPos - 8);
        int to = Math.min(lower.length(), digitPos + 8);
        String win = lower.substring(from, to);
        for (String p : promo) if (win.contains(p)) return true;
        return false;
    }

    /**
     * 命中词是否为**泛词** —— 单独出现不足以证明签到成功（内置词与用户词**一视同仁**）。
     *
     * 强词（含签到/打卡动词的结论形态）不算泛词；其余一律按泛词处理：
     *   成功 / 完成 / 欢迎 / 恭喜 / 领取 / 获得 / 积分 / 配给 / 哭哭 / emoji / 社工库功能 …
     */
    static boolean isGenericOkWord(String w) {
        if (w == null || w.length() == 0) return false;
        String lw = w.toLowerCase(java.util.Locale.US);
        // ① 强结论形态（签到成功 / 领取完毕…）→ 不是泛词
        for (String strong : STRONG_RESULT) if (lw.contains(strong)) return false;
        // ② **通用高频词**（完全相等）→ 泛词。它们在任何回复里都可能出现，
        //    例如「获得积分」在教程句里就有（实测被误放行过）。
        for (String g : GENERIC_WORDS) if (lw.equals(g)) return true;
        // ③ 极短词（≤2 个汉字）或纯符号/emoji/短英文 → 判别力不足，也算泛词。
        int cjk = 0;
        for (int i = 0; i < lw.length(); i++) {
            char c = lw.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
        }
        if (cjk <= 2) return true;
        if (cjk == 0 && lw.length() <= 4) return true;
        // ④ 其余（任务完成 / 任务达成 / 配给领取 / 办妥了 …）→ 用户逐条学过的具体说法，
        //    有判别力，直接生效（公告守卫仍然生效）。
        return false;
    }

    /** 命中泛词、却因缺少结果形态而被拦下的那个词（供 Core 留痕诊断）。 */
    public static String genericOkBlocked(String reply) {
        if (reply == null || reply.length() == 0) return null;
        String lower = reply.toLowerCase();
        String[] all = OK_WORDS_DEFAULT;
        String m = matched(lower, all);
        if (m == null || !isGenericOkWord(m)) return null;
        if (hasSuccessShape(lower)) return null;
        return m;
    }

    static boolean okWordNeedsContext(String w) {
        if (w == null || w.length() == 0) return false;
        String lw = w.toLowerCase(java.util.Locale.US);
        if (lw.matches("[a-z0-9 +\\-]+")) return false;      // 纯英文/数字 → 不设限
        String[] CORE = {"签到", "打卡", "领取", "签", "check", "sign", "claim"};
        for (String c : CORE) {
            if (lw.contains(c)) return false;
        }
        return true;
    }

    /** 整句是否含签到语境。 */
    static boolean hasSignContext(String lower) {
        if (lower == null) return false;
        String[] CORE = {"签到", "打卡", "领取", "check", "sign", "claim", "daily"};
        for (String c : CORE) {
            if (lower.contains(c)) return true;
        }
        return false;
    }

    /** 句中出现「后置否定」（守卫只看命中词之前，这类得单独挡）。
     *  典型：「每天签到可获得积分，重复签到无效」——「重复签到」在 DUP 表里，
     *  但后面跟了「无效」，显然不是本次结论。 */
    static boolean hasTrailingNegation(String lower) {
        if (lower == null) return false;
        String[] N = {"无效", "不算", "不成功", "不可用", "不生效", "取消"};
        for (String n : N) {
            if (lower.contains(n)) return true;
        }
        return false;
    }

    /** 命中词是不是**内置**表里的（用于区分用户自定义词 —— 用户词不受上下文限制）。 */
    static boolean isBuiltinOkWord(String w) {
        if (w == null || w.length() == 0) return false;
        for (String x : OK_WORDS_DEFAULT) {
            if (x.equalsIgnoreCase(w)) return true;
        }
        for (String x : DUP_WORDS_DEFAULT) {
            if (x.equalsIgnoreCase(w)) return true;
        }
        return false;
    }

    /** 命中成功词、却被「公告守卫」拦下的那个词（没有则返回 null）。
     *
     *  2026-10-25：必须留痕。否则用户会看到「我明明加了词/学了词，还是不判成功」，
     *  却查不到任何原因 —— 实际是被公告守卫挡了。
     */
    public static String blockedOkWord(String reply, String[] okGlobal, String[] okBot, String[] okTarget) {
        try {
            if (reply == null || reply.length() == 0) return null;
            if (!isLikelyAnnouncement(reply)) return null;
            String lower = reply.toLowerCase(java.util.Locale.US);
            String[][] chains = { okTarget, okBot, okGlobal };
            for (String[] words : chains) {
                if (words == null || words.length == 0) continue;
                String m = matched(lower, words);
                if (m != null && !negated(lower, m)) return m;
            }
            return null;
        } catch (Throwable t) { return null; }
    }

    public static boolean looksLikeSignResult(String reply) {
        if (reply == null || reply.length() == 0) return false;
        if (looksLikeProgress(reply)) return false;      // 进度 ≠ 结果
        String lr = reply.toLowerCase(java.util.Locale.US);
        return lr.contains("签到") || lr.contains("打卡") || lr.contains("领取")
                || lr.contains("签") || lr.contains("check") || lr.contains("sign")
                || lr.contains("claim") || lr.contains("daily");
    }

    // ────────────────────────────────────────────────────────────────
    // 时间展示（今日计划 / 补签列表）
    // ────────────────────────────────────────────────────────────────

    /** 判定"这次算不算补签"的默认阈值：实际比计划晚这么多就按补签展示。 */
    public static final int MISS_JUDGE_GRACE_MIN = 5;

    /** 毫秒时间戳 → HH:MM（本地时区）。<=0 返回空串（调用方据此决定显示什么）。 */
    public static String hhmmOf(long ms) {
        if (ms <= 0L) return "";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format("%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /**
     * 这次签到是否属于"补签"。
     *
     * 两个判据，任一成立即算（宁可能标就标，标错只是多一个提示、不影响状态）：
     *   ① missAtMs > 0          —— 走过 sweepDue 的错过补签路径，**最准确**
     *   ② 实签时刻 − 计划时刻 > 阈值 —— 兜底：非定时路径（事件补签/手动）也会晚点
     *
     * @param missAtMs   补签触发时刻（0 = 无记录）
     * @param signedAtMs 实际签到时刻（0 = 未知）
     * @param planMin    计划分钟数（0..1439，<0 = 无计划）
     * @param signedDayStartMs 实签当天 00:00 的毫秒（把 planMin 换算成绝对时刻用）
     * @param graceMin   阈值分钟
     */
    public static boolean isMissBack(boolean hasMissRecord,
                                     long signedAtMs,
                                     int planMin,
                                     long signedDayStartMs,
                                     int graceMin) {
        if (hasMissRecord) return true;                       // ① 显式记录，最可信
        if (signedAtMs <= 0L || planMin < 0 || signedDayStartMs <= 0L) return false;
        long planMs = signedDayStartMs + (long) planMin * 60000L;
        long lateMs = signedAtMs - planMs;
        if (lateMs <= 0L) return false;                        // 提前签不算补签
        return lateMs > (long) graceMin * 60000L;
    }

    /**
     * 距今多少分钟（向下取整，最小 0）。nowMs <= atMs 时返回 0。
     * 用于「已过点 42 分」「晚了 42 分」这类相对时间。
     */
    public static int minutesSince(long atMs, long nowMs) {
        if (atMs <= 0L || nowMs <= atMs) return 0;
        return (int) ((nowMs - atMs) / 60000L);
    }

    // ────────────────────────────────────────────────────────────────
    //  重复发送控制（2026-10-01）
    //
    //  背景：两个真实痛点，根因相同 —— 发送后长时间拿不到「结论」时，
    //  模块只会傻等固定的 10 分钟，其间每 45 秒重发一次（约 13 条）。
    //    · 群聊里 bot 完全不回复
    //    · bot 回了但内容认不出（如「次数上限，请明日再试」）
    //  用户视角就是"刷屏"，群聊里还会被当成骚扰。
    //
    //  设计：区分两种"没结论"，给不同阈值。
    //    · 完全无回复 → 给足时间（bot 可能慢、可能被限流）
    //    · 已有回复但判不出 → bot 已经表态了，别再等，早收工
    //  并且给"发送次数"设硬上限，任何情况下都不会无限刷。
    // ────────────────────────────────────────────────────────────────

    /** 完全无回复时的宽限（毫秒）：bot 慢/被限流都可能，给足时间。 */
    public static final long QUIET_GRACE_MS = 10L * 60 * 1000;

    /** 已有回复但判不出时的宽限（毫秒）：bot 已表态，不必久等。 */
    public static final long ANSWERED_GRACE_MS = 3L * 60 * 1000;

    /**
     * 同一目标当日「发出后仍无结论」的最大发送次数。
     *
     * 硬闸：无论走哪条路径，发到这个次数就转「待确认」，等用户处置。
     * 取 3 是因为：正常 bot 1~2 次内必有明确结论；到第 3 次还没有，
     * 要么词表不覆盖、要么 bot 行为异常，继续发只会刷屏。
     */
    public static final int MAX_SEND_ATTEMPTS = 3;

    /**
     * 是否应把「已发出但无结论」的目标转为「待确认」。
     *
     * @param attempts    当日已发送次数（含本次）
     * @param sentAtMs    最近一次发送时刻（毫秒），0 表示无记录
     * @param nowMs       当前时刻（毫秒）
     * @param answered    发出后是否收到过任何回复（bot 已表态）
     * @param signedToday 今天是否已有结论（已签）
     * @param alreadyPending 是否已在「待确认」
     * @param retryExhausted 重试是否已用尽
     * @return true = 应转「待确认」
     */
    public static boolean shouldGiveUpSending(int attempts, long sentAtMs, long nowMs,
                                              boolean answered, boolean signedToday,
                                              boolean alreadyPending, boolean retryExhausted) {
        if (signedToday || alreadyPending || retryExhausted) return false;
        // 次数硬闸：发满即收工（不依赖时间，避免"秒回也被刷 13 条"）
        if (attempts >= MAX_SEND_ATTEMPTS) return true;
        // 时间软闸：按"有无回复"分档
        if (sentAtMs <= 0L) return false;
        long age = nowMs - sentAtMs;
        if (age < 0L) return false;                       // 时钟回拨，保守
        long grace = answered ? ANSWERED_GRACE_MS : QUIET_GRACE_MS;
        return age >= grace;
    }

    /** 该状态是否「今日已了结」（不再自动重发，也不计失败）。 */
    public static boolean isSettledForToday(int verdict) {
        return verdict == V_SIGNED || verdict == V_EXHAUSTED;
    }

    // ────────────────────────────────────────────────────────────────
    //  连续签到 / 日历状态（2026-10-01 重做）
    //
    //  背景（用户反馈）：日历上"连续 14 天"在**新的一天还没签**时仍然显示 14，
    //  与日历里今天那格的"空框"互相矛盾；用户不知道"今天到底要不要动手"。
    //
    //  根因：streakOf() 把"昨天签过"也算作连续有效，于是整个白天都沿用旧值。
    //  设计取舍：**今天没签不归零**（中午看到"0 天"会以为白签了），
    //  但必须把"今天的状态"独立表达出来 —— 那才是唯一可行动的信息。
    // ────────────────────────────────────────────────────────────────

    /** 今天的状态：已签。 */
    public static final int TODAY_DONE = 0;
    /** 今天的状态：还没签，且仍在签到窗口/补签时段内 —— 还来得及。 */
    public static final int TODAY_PENDING = 1;
    /** 今天的状态：还没签，且已过窗口与补签截止 —— 今天大概率赶不上了。 */
    public static final int TODAY_MISSED = 2;
    /** 今天的状态：账号停用或全部目标冻结 —— 今天不参与。 */
    public static final int TODAY_IDLE = 3;

    /**
     * 判定"今天"的状态。
     *
     * @param signedToday   今天是否已签（至少一个目标）
     * @param nowMin        当前分钟（0..1439）
     * @param windowAny     签到窗口（windowRangeAny 的结果，可为 null=不限）
     * @param missDeadline  补签截止分钟
     * @param missBackOn    是否开启补签
     * @param anyTargetOn   是否还有启用中的目标
     */
    public static int todayState(boolean signedToday, int nowMin, int[] windowAny,
                                 int missDeadline, boolean missBackOn, boolean anyTargetOn) {
        if (signedToday) return TODAY_DONE;
        if (!anyTargetOn) return TODAY_IDLE;
        // 有补签时，窗口结束后仍可补到截止时间 —— 那段时间不算"错过"
        if (missBackOn && windowAny != null) {
            if (inMissBackTime(nowMin, new int[]{windowAny[0], windowAny[0]}, missDeadline, true)) {
                return TODAY_PENDING;
            }
        }
        // ── 不限窗口（WINDOW 为空）──
        // 2026-10-01 修：初版这里直接 return TODAY_PENDING，导致"今天未签"**永远不可达**
        // （用户清空数据后实测反馈：怎么都看不到粉色）。不限窗口虽然意味着全天可签，
        // 但一天总要有收尾 —— 超过某个点还没签，用户需要知道"今天大概不会自动签上了"。
        // 这里用「补签截止」当收尾参考点（默认 23:00，用户可改）。
        //
        // 边界保护：若该值落在凌晨（< 06:00），说明用户指的是"次日凌晨"，
        // 拿它当"今天收尾"会导致白天误报未签 → 退回"待签"。
        if (windowAny == null) {
            if (missDeadline < 6 * 60) return TODAY_PENDING;
            return nowMin <= missDeadline ? TODAY_PENDING : TODAY_MISSED;
        }
        // 窗口内（含跨天）→ 还来得及
        if (inWindowAny(nowMin, windowAny)) return TODAY_PENDING;
        // 跨天窗口（如 22:00-02:00）：今天的机会在今晚 22:00 之后，
        // 当前无论处在"昨夜的尾巴"还是"白天空档"，都还没到今天的点 → 等
        if (crossesMidnight(windowAny)) return TODAY_PENDING;
        // 还没到窗口开始（如窗口 08:00 开始、现在 07:00）→ **等**，不是"未签"。
        // 2026-10-01 修：初版在这里直接落进 TODAY_MISSED，于是早上打开面板
        // 会看到粉色的"今天未签"——那时压根还没到该签的时候，纯属误报惊吓。
        if (nowMin < windowAny[0]) return TODAY_PENDING;
        // 窗口已过、补签也没开或已过 → 今天确实赶不上了
        return TODAY_MISSED;
    }

    /**
     * 连续天数的展示值。
     *
     * 规则：今天已签 → 用存储值（含今天）；今天未签但昨天签过 → 存储值（不含今天）；
     * 否则 0。注意**不因"今天还没签"而归零**，避免用户中午打开看到 0 天。
     *
     * @param stored      已存储的连续天数
     * @param lastDate    最近一次签到日期 yyyy-MM-dd
     * @param today       今天 yyyy-MM-dd
     * @param yesterday   昨天 yyyy-MM-dd
     */
    public static int streakDisplay(int stored, String lastDate, String today, String yesterday) {
        if (lastDate == null || lastDate.length() == 0) return 0;
        if (today != null && today.equals(lastDate)) return Math.max(stored, 1);
        if (yesterday != null && yesterday.equals(lastDate)) return Math.max(stored, 1);
        return 0;
    }

    /** 把分钟数说成「1 小时 12 分」/「42 分」。 */
    public static String humanMinutes(int min) {
        if (min <= 0) return "0 分";
        if (min < 60) return min + " 分";
        int h = min / 60, m = min % 60;
        return m == 0 ? (h + " 小时") : (h + " 小时 " + m + " 分");
    }
}
