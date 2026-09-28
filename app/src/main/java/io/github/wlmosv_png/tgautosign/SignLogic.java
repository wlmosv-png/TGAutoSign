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
    public static boolean inMissBackTime(int nowMin, int[] range, int missDeadline, boolean missBackOn) {
        if (!missBackOn) return false;
        int start = range != null ? range[0] : 0;
        if (missDeadline >= start) return nowMin >= start && nowMin <= missDeadline;
        return nowMin >= start || nowMin <= missDeadline;
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
            // 英文
            "already", "repeated", "again later", "already signed", "already checked",
            "checked in", "signed today"
    };
    public static final String[] OK_WORDS_DEFAULT = {
            "签到成功", "打卡成功", "成功签到", "领取成功", "发送成功", "签到完成", "打卡完成",
            "签到获得", "获得积分", "success", "claimed", "check-in complete"
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
            "请先验证", "无权限", "没有权限", "暂无权限", "不可用", "暂未开放",
            "please join", "not linked", "not registered", "no permission"
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
    public static int verdictOf(String reply, String[] dup, String[] ok, String[] fail) {
        return (Integer) verdictDetail(reply, dup, ok, fail)[0];
    }

    /**
     * 判定结果 + **命中了哪条词**（用于日志与诊断包）。
     * 以前只返回一个码，"为什么这么判"完全查不到 —— 排障只能猜。
     * 返回 {Integer 判定码, String 命中词}。
     */
    public static Object[] verdictDetail(String reply, String[] dup, String[] ok, String[] fail) {
        if (reply == null || reply.length() == 0) return new Object[]{Integer.valueOf(V_UNKNOWN), ""};
        String lower = reply.toLowerCase();
        String m = matched(lower, dup != null ? dup : DUP_WORDS_DEFAULT);
        if (m != null) return new Object[]{Integer.valueOf(V_SIGNED), m};
        m = matched(lower, ok != null ? ok : OK_WORDS_DEFAULT);
        if (m != null) return new Object[]{Integer.valueOf(V_SIGNED), m};
        m = matched(lower, fail != null ? fail : FAIL_WORDS_DEFAULT);
        if (m != null) return new Object[]{Integer.valueOf(V_FAILED), m};
        return new Object[]{Integer.valueOf(V_UNKNOWN), ""};
    }

    private static boolean hit(String lower, String[] words) {
        return matched(lower, words) != null;
    }

    /** 返回命中的第一条词；没命中返回 null。 */
    private static String matched(String lower, String[] words) {
        if (words == null) return null;
        for (String w : words) {
            if (w == null) continue;
            String t = w.trim().toLowerCase();
            if (t.length() > 0 && lower.contains(t)) return t;
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
            if (g.retryExhausted) return SKIP_RETRY_EXHAUST;
            if (g.inBackoff) return SKIP_BACKOFF;
            if (g.disabled) return SKIP_DISABLED;
        } else {
            // 批量语义：用户要求「只签未签的」。已签 / 已发出待结论都算「今天有结论了」，
            // 跳过它们（后者可能已经签上，重发就是重复）。
            if (g.skipSigned) {
                if (g.signedToday) return SKIP_ALREADY_SIGNED;
                if (g.sentPendingFresh) return SKIP_SENT_PENDING;
            }
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
            default:                  return "";
        }
    }

    // ────────────────────────────────────────────────────────────────
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

    /** 归类的稳定标识串（存进 pendcfm_note_，跨版本可读）。 */
    public static String resultCode(int r) {
        switch (r) {
            case R_SIGNED:      return "signed";
            case R_FAILED:      return "failed";
            case R_BTN_STALE:   return "btn_stale";
            case R_REPLIED_UNK: return "replied_unknown";
            case R_NO_REPLY:    return "no_reply";
            case R_JUDGE_OFF:   return "judge_off";
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
    public static boolean looksLikeProgress(String reply) {
        if (reply == null) return false;
        String lr = reply.toLowerCase(java.util.Locale.US);
        for (String w : PROGRESS_WORDS) {
            if (lr.contains(w)) return true;
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

    /** 把分钟数说成「1 小时 12 分」/「42 分」。 */
    public static String humanMinutes(int min) {
        if (min <= 0) return "0 分";
        if (min < 60) return min + " 分";
        int h = min / 60, m = min % 60;
        return m == 0 ? (h + " 小时") : (h + " 小时 " + m + " 分");
    }
}
