package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 「超长推广尾缀导致真结果被判 UNKNOWN」回归测试（2026-10-29）。
 *
 * 故障：SignLogic.isLikelyAnnouncement() 末尾有纯长度兜底
 *       `if (reply.length() > 180) return true;`
 *       它不区分「正文结果」与「推广尾缀（含长 URL）」，
 *       于是真实签到成功回复被整条判成公告 → okChain 里 `continue` →
 *       最终落到 V_UNKNOWN(2)。
 *
 * 真实样本来源（禁止编造）：
 *   宿主 cache4.db 的 messages_v2 表（只读副本 /data/local/tmp/tgdb），
 *   目标 did=8286060286（该 bot 的签到结果共 3 种形态）。
 *   · mid=14613  2026-10-29 12:41:56  正文 182 字（配给领取 · 连续服役 3 天）
 *   · mid=14615  2026-10-29 12:54:57  正文 135 字（今日已配给）
 *   URL 来自同一条消息的 TL 实体（595 字符，URL 编码后），
 *   模块收到的回复文本 = 正文 + 推广尾缀（含该 URL），
 *   日志里对应两行：
 *     12:41:55 【回复判定】8286060286 这条回复像是签到结果，但没匹配上内置词:
 *              ✨ [ 配给领取 ] …（可在 设置 → 回复判定词 里补一条）
 *     12:54:57 【回复判定】8286060286 命中「配给」→ 计入已签（1 条）（用户自定义词）
 *
 * 跑法：
 *   javac -d /tmp/tcls app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java \
 *         tools/tests/AnnouncementLengthTest.java
 *   java -cp /tmp/tcls io.github.wlmosv_png.tgautosign.AnnouncementLengthTest
 */
public final class AnnouncementLengthTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    /** 设备 prefs `jmb_ok_words` 的真实快照（2026-10-28 04:2x 读取）。 */
    private static final String USER_WORDS =
            "社工库功能,👑,欢迎使用hope,成功,配给,获得积分,恭喜您,签到,欢迎,哭哭,配给领取";

    // ══════════════ 真实原文（cache4.db messages_v2，逐字节还原）══════════════

    /** 目标 8286060286，mid=14613，2026-10-29 12:41:56 —— 真实签到结果正文（182 字）。 */
    static final String REAL_BODY_14613 =
            "✨ [ 配给领取 ]\n"
            + "━━━━━━━━━━━━━━━━━━━━\n"
            + "  连续服役: 3 天 ✨\n"
            + "  获得盒能: +50\n"
            + "  恢复生命: +1\n"
            + "━━━━━━━━━━━━━━━━━━━━\n"
            + "  盒能: 1596  生命: 100/100\n"
            + "━━━━━━━━━━━━━━━━━━━━\n"
            + "  💰 邀请好友 +100盒能+100AI点/人\n"
            + "  🔥 独家算法 · 严格验证 · 远超同行";

    /** 同一条消息的推广尾缀（TL 实体里的 595 字符 URL，原样保留编码）。 */
    static final String REAL_TAIL_14613 =
            "📨 一键分享赚盒能\n"
            + "https://t.me/share/url?url=%F0%9F%94%A5%E7%9B%92%E6%AD%A6%E5%99%A8%E2%80%94%E2%80%94"
            + "%E7%9C%9F%E6%AD%A3%E6%9C%89%E7%A7%91%E7%A0%94%E7%B2%BE%E7%A5%9E%E7%9A%84%E7%A4%BE"
            + "%E5%B7%A5%E5%BA%93%EF%BC%81%E7%8B%AC%E5%AE%B6%E7%AE%97%E6%B3%95%2B%E4%B8%A5%E6%A0%BC"
            + "%E9%AA%8C%E8%AF%81%2B%E6%B7%B1%E5%BA%A6%E5%85%B3%E8%81%94%EF%BC%81%E8%BF%9C%E8%B6%85"
            + "%E5%B8%82%E9%9D%A2%E5%90%8C%E7%B1%BB%E4%BA%A7%E5%93%81%EF%BC%81%E6%8C%81%E7%BB%AD%E8%BF%AD"
            + "%E4%BB%A3%E5%8D%87%E7%BA%A7%E7%89%A9%E8%B6%85%E6%89%80%E5%80%BC%EF%BC%81%E5%85%8D%E8%B4%B9"
            + "%E4%BD%93%E9%AA%8C%20%F0%9F%91%89%20https%3A%2F%2Ft.me%2Fhewuqi%3Fstart%3Dref_8526734916";

    /** 模块实际收到的完整回复：正文 + 推广尾缀（含长 URL）。 */
    static final String REAL_FULL_14613 = REAL_BODY_14613 + "\n" + REAL_TAIL_14613;

    /** 目标 8286060286，mid=14615，2026-10-29 12:54:57 —— 「今日已配给」（135 字）。 */
    static final String REAL_BODY_14615 =
            "✨ [ 今日已配给 ]\n"
            + "━━━━━━━━━━━━━━━━━━━━\n"
            + "  今日已领取（间隔24小时）\n"
            + "  距下次签到: 23小时46分钟\n"
            + "━━━━━━━━━━━━━━━━━━━━\n"
            + "  💰 邀请好友 +100盒能+100AI点/人\n"
            + "  🔥 独家算法 · 严格验证 · 远超同行";

    static final String REAL_FULL_14615 = REAL_BODY_14615 + "\n" + REAL_TAIL_14613;

    // ══════════════════════════ 入口 ══════════════════════════

    public static void main(String[] args) {
        System.out.println("=== 超长推广尾缀 / 长度兜底 回归（真实 cache4.db 原文）===");
        System.out.println("样本长度: body14613=" + REAL_BODY_14613.length()
                + " full14613=" + REAL_FULL_14613.length()
                + " body14615=" + REAL_BODY_14615.length()
                + " full14615=" + REAL_FULL_14615.length());

        System.out.println("\n-- A. 真实故障原文：不得因超长被判公告 --");
        mustNotAnnounce("A-1 真实全文(正文+长URL)", REAL_FULL_14613);
        mustNotAnnounce("A-2 真实正文(182字>180)", REAL_BODY_14613);
        mustNotAnnounce("A-3 真实『今日已配给』全文", REAL_FULL_14615);

        System.out.println("\n-- B. 真实成功判定：最终状态必须是 SIGNED --");
        mustSign("B-1 真实全文 → V_SIGNED", REAL_FULL_14613);
        mustSign("B-2 真实正文 → V_SIGNED", REAL_BODY_14613);
        mustSign("B-3 今日已配给全文 → V_SIGNED", REAL_FULL_14615);

        System.out.println("\n-- C. 纯长广告：仍必须是公告 --");
        mustAnnounce("C-1 长广告(无任何结果证据)",
                pad("本机器人提供数据查询服务，支持姓名、手机号、身份证、QQ、邮箱等多种查询方式。"
                  + "查询结果来自公开渠道聚合，仅供参考。", 400));
        mustAnnounce("C-2 长帮助说明",
                pad("欢迎使用本机器人。使用说明：发送 /start 查看功能列表；"
                  + "发送 /help 查看帮助；发送任意关键词进行查询。", 300));
        mustAnnounce("C-3 长广告+配给领取字样但无结果数字",
                pad("配给领取规则说明：每日签到可获得盒能，邀请好友可额外获得盒能奖励。"
                  + "详细规则请查看公告。", 260));

        System.out.println("\n-- D. 长成功正文：结果 + 大量推广仍判成功 --");
        mustSign("D-1 成功 + 长推广", "✅ 签到成功，积分 +3\n" + pad("推广：邀请好友加入可获得更多积分。", 500));
        mustSign("D-2 成功 + 长 URL", "签到成功\n" + LONG_URL);
        mustSign("D-3 已签到 + 长推广", "您今天已经签到过了\n" + pad("频道公告：请勿相信任何私聊。", 300));

        System.out.println("\n-- E. 长失败正文：失败不得被推广尾缀掩盖 --");
        assertEquals("E-1 签到失败 + 长推广", SignLogic.V_FAILED,
                verdict("❌ 签到失败，原因：积分不足\n" + pad("推广：邀请好友加入可获得更多积分。", 400)));
        assertEquals("E-2 次数已用完 + 长推广", SignLogic.V_EXHAUSTED,
                verdict("今日次数已用完，请明日再试\n" + pad("推广：欢迎加入官方频道。", 400)));

        System.out.println("\n-- F. 边界长度：179 / 180 / 181 --");
        boundarySuccess(179);
        boundarySuccess(180);
        boundarySuccess(181);
        boundarySuccess(182);
        boundaryAd(179);
        boundaryAd(180);
        boundaryAd(181);

        System.out.println("\n-- G. URL 归一化 --");
        // 归一化后长度应只算正文
        int na = SignLogic.effectiveBodyLength(REAL_FULL_14613);
        int nb = REAL_BODY_14613.length();
        // 容差 20：URL 被剥掉后，仍会有「📨 一键分享赚盒能」这类极短引导语残留
        if (na <= nb + 20) {
            passed++;
        } else {
            failed.add("G-1 URL 未从有效长度中剥离（归一化后=" + na + "，正文=" + nb + "）");
        }
        int la = SignLogic.effectiveBodyLength(LONG_URL);
        if (la <= 8) {
            passed++;
        } else {
            failed.add("G-2 纯 URL 文本的有效长度应为 0 附近，实际=" + la);
        }
        int la2 = SignLogic.effectiveBodyLength("签到成功 " + LONG_URL);
        if (la2 >= 4 && la2 <= 20) {
            passed++;
        } else {
            failed.add("G-3 正文+URL 的有效长度应约等于正文，实际=" + la2);
        }

        System.out.println("\n-- H. 公告反例：只有词、没有结果证据 → 不得判成功 --");
        // 这几条是**短文本**，不该靠长度兜底判公告；关键是不得被当成签到成功
        mustNotSign("H-1 含『配给领取』但无结果数字",
                "【配给领取】邀请好友 +100盒能，快来领取吧！加入我们，收益翻倍。");
        mustNotSign("H-2 含邀约奖励数字但无签到结论",
                "邀请一位好友 +100 盒能，邀请三位 +500 盒能，累计更有神秘大奖。");
        mustNotSign("H-3 积分宣传文案",
                "新人注册即送 100 积分，每日签到可获得积分，积分可兑换各种礼品。");
        // 同一批文案加长后（>180）必须仍判公告
        mustAnnounce("H-4 长版 H-1", pad("【配给领取】邀请好友 +100盒能，快来领取吧！加入我们，收益翻倍。", 220));
        mustAnnounce("H-5 长版 H-3", pad("新人注册即送 100 积分，每日签到可获得积分，积分可兑换各种礼品。", 220));

        System.out.println("\n----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) {
            System.out.println("FAILED");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }

    // ══════════════════════════ 断言助手 ══════════════════════════

    private static final String LONG_URL =
            "https://t.me/share/url?url=%F0%9F%94%A5%E7%9B%92%E6%AD%A6%E5%99%A8%E2%80%94%E2%80%94"
            + "%E7%9C%9F%E6%AD%A3%E6%9C%89%E7%A7%91%E7%A0%94%E7%B2%BE%E7%A5%9E%E7%9A%84";

    private static int verdict(String text, String[] okMerged) {
        Object[] vd = SignLogic.verdictDetailScoped(text, okMerged, SignLogic.FAIL_WORDS_DEFAULT,
                null, null, null, null);
        return ((Integer) vd[0]).intValue();
    }

    private static int verdict(String text) {
        return verdict(text, merge(SignLogic.OK_WORDS_DEFAULT, SignLogic.parseExtraWords(USER_WORDS)));
    }

    private static void mustNotAnnounce(String name, String text) {
        if (SignLogic.isLikelyAnnouncement(text)) {
            failed.add(name + " → ★仍被判公告（长度兜底未修好，len=" + text.length() + "）");
        } else {
            passed++;
        }
    }

    private static void mustAnnounce(String name, String text) {
        if (!SignLogic.isLikelyAnnouncement(text)) {
            failed.add(name + " → 长广告未被判公告（漏判，len=" + text.length() + "）");
        } else {
            passed++;
        }
    }

    private static void mustNotSign(String name, String text) {
        int v = verdict(text);
        if (v == SignLogic.V_SIGNED) {
            failed.add(name + " → ★误判为签到成功（无签到结论）");
        } else {
            passed++;
        }
    }

    private static void mustSign(String name, String text) {
        int v = verdict(text);
        if (v == SignLogic.V_SIGNED) {
            passed++;
        } else {
            failed.add(name + " → 真结果未判成功（verdict=" + v + "，期望 0=SIGNED，len=" + text.length() + "）");
        }
    }

    private static void assertEquals(String name, int expect, int actual) {
        if (expect == actual) {
            passed++;
        } else {
            failed.add(name + " → 期望 " + expect + "，实际 " + actual);
        }
    }

    /** 边界：N 字的成功正文（无推广）必须判成功。 */
    private static void boundarySuccess(int n) {
        String head = "签到成功";
        StringBuilder sb = new StringBuilder(head);
        while (sb.length() < n) sb.append('x');
        String t = sb.substring(0, n);
        assertEquals("F-成功 " + n + " 字", SignLogic.V_SIGNED, verdict(t));
        // 且不得被判公告
        if (SignLogic.isLikelyAnnouncement(t)) {
            failed.add("F-公告判断 " + n + " 字成功正文 → 被判公告");
        } else {
            passed++;
        }
    }

    /** 边界：N 字的纯广告（无结果证据）必须仍判公告。 */
    private static void boundaryAd(int n) {
        StringBuilder sb = new StringBuilder("本机器人提供数据查询服务");
        while (sb.length() < n) sb.append('x');
        String t = sb.substring(0, n);
        boolean ann = SignLogic.isLikelyAnnouncement(t);
        if (n > 180) {
            if (ann) passed++;
            else failed.add("F-广告 " + n + " 字 → 未判公告（长度兜底被删过头）");
        } else {
            passed++;   // ≤180 不靠长度兜底，此处不断言
        }
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        int i = 0;
        while (sb.length() < n) sb.append("xyz ".charAt(i++ % 4));
        return sb.toString();
    }

    static String[] merge(String[] base, String[] extra) {
        List<String> out = new ArrayList<String>();
        for (String w : base) if (w != null && w.trim().length() > 0) out.add(w.trim());
        if (extra != null) {
            for (String w : extra) {
                if (w == null) continue;
                String t = w.trim();
                if (t.length() == 0) continue;
                boolean dup = false;
                for (String e : out) if (e.equalsIgnoreCase(t)) { dup = true; break; }
                if (!dup) out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }
}
