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
    private static final String[] SIGN_WORDS = {
            "签到", "打卡", "签领", "领取", "每日", "报到",
            // 2026-10-05 扩：常见措辞（这些词较宽，靠下面 REJECT_WORDS 反向否决防误伤）
            "奖励", "积分", "续期", "保号", "签到领",
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
            if (m != null) return new Object[]{Integer.valueOf(V_SIGNED), m};
        }
        return verdictDetail(reply, dup, ok, fail);
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
        if (m != null) return new Object[]{Integer.valueOf(V_FAILED), m};
        m = matched(lower, PERMANENT_FAIL_WORDS);
        if (m != null) return new Object[]{Integer.valueOf(V_FAILED), m};
        // 今日已用尽（2026-10-01 新增）：排在重复/成功词之前。
        // 这类回复常同时含"已"字（如"今日次数已用完"），若不抢先判定，
        // 会被组合判定当成"签到+已"误判成功；但它**不等于签到成功**。
        m = matched(lower, EXHAUSTED_WORDS);
        if (m != null) return new Object[]{Integer.valueOf(V_EXHAUSTED), m};
        m = matched(lower, dup != null ? dup : DUP_WORDS_DEFAULT);
        if (m != null) return new Object[]{Integer.valueOf(V_SIGNED), m};
        m = matched(lower, ok != null ? ok : OK_WORDS_DEFAULT);
        if (m != null) return new Object[]{Integer.valueOf(V_SIGNED), m};
        // ── 组合判定（兜底）：词表没覆盖，但语义上明显是成功 ──
        m = comboSuccess(lower);
        if (m != null) return new Object[]{Integer.valueOf(V_SIGNED), m};
        return new Object[]{Integer.valueOf(V_UNKNOWN), ""};
    }

    /** 签到行为词（与「结果词」组合时才生效） */
    private static final String[] COMBO_ACT = {
            "签到", "打卡", "签领", "check in", "check-in", "checked in", "sign in", "sign-in"
    };

    /** 成功结果词 */
    private static final String[] COMBO_OK = {
            "成功", "完成", "已", "获得", "领取", "恭喜", "奖励", "积分", "+1",
            "success", "complete", "done", "earned", "claimed", "received"
    };

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
