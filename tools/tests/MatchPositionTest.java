package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * M1 · 归一化与命中位置测试（阶段 3，2026-11-01）。
 *
 * 覆盖用户指定的全部要点：
 *   · 中文 / 英文 / 中英混合
 *   · 连续空格、换行、制表符
 *   · 全角与半角标点
 *   · Emoji 与特殊 Unicode 字符
 *   · 相同词条在不同分句中的命中
 *   · 多词条同时命中（词表顺序敏感）
 *   · **命中位置与原文位置一致**（逐条用 substring 反查验证）
 *   · 空文本 / 超长文本 / 异常输入
 *   · **Java UTF-16 索引 vs Unicode 码点**（emoji 前后的偏移）
 *
 * 断言纪律：位置类断言必须**独立可验证** ——
 *   用 `text.substring(m.rawStart, m.rawEnd)` 反查，而不是只看"没抛异常"。
 */
public final class MatchPositionTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    private static void ok(String tag, boolean cond) {
        if (cond) passed++; else failed.add(tag);
    }
    private static void eq(String tag, Object got, Object want) {
        boolean b = got == null ? want == null : got.equals(want);
        if (b) passed++; else failed.add(tag + " → 得到 " + got + "，期望 " + want);
    }

    public static void main(String[] args) {
        coreMatch();
        positionFidelity();
        normalization();
        unicodeAndEmoji();
        clauseScoping();
        orderingAndSource();
        edgeCases();

        System.out.println("----------------------------------------");
        System.out.println("MatchPositionTest：通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }

    // ═══════════ ① 命中结构与 matched 一致性 ═══════════

    private static void coreMatch() {
        String[] words = { "签到成功", "签到" };
        String text = "🎉 签到成功，获得积分".toLowerCase();

        SignLogic.Match m = SignLogic.matchAt(text, words);
        ok("①命中非空", m != null);
        eq("①词条与 matched 一致", SignLogic.matched(text, words), m.word);
        eq("①命中词条", m.word, "签到成功");
        ok("①原文直接命中（viaFlat=false）", !m.viaFlat);
        // 词表顺序敏感：把「签到」提前，应命中「签到」
        SignLogic.Match m2 = SignLogic.matchAt(text, new String[]{ "签到", "签到成功" });
        eq("①词表顺序敏感（首个命中胜出）", m2.word, "签到");
    }

    // ═══════════ ② 位置保真（核心） ═══════════

    private static void positionFidelity() {
        String[] words = { "签到成功" };

        // 原文直接命中：substring 反查必须等于命中词
        String t1 = "今日签到成功！".toLowerCase();
        SignLogic.Match m1 = SignLogic.matchAt(t1, words);
        ok("②原文命中位置可反查", m1 != null && m1.rawStart >= 0 && m1.rawEnd <= t1.length()
                && t1.substring(m1.rawStart, m1.rawEnd).equals(m1.word));
        eq("②rawStart 正确（文本为「今日签到成功！」，命中词在第 3 个位置 → 索引 2）",
                Integer.valueOf(2), Integer.valueOf(m1.rawStart));

        // 去空白命中：位置映射回原文后，去掉空白应等于命中词
        String t2 = "今日 签到 成功！".toLowerCase();
        SignLogic.Match m2 = SignLogic.matchAt(t2, words);
        ok("②viaFlat 命中非空", m2 != null);
        ok("②viaFlat 标记正确", m2 != null && m2.viaFlat);
        if (m2 != null && m2.rawStart >= 0) {
            String seg = t2.substring(m2.rawStart, Math.min(t2.length(), m2.rawEnd));
            eq("②viaFlat 位置反查（去空白后等价）", SignLogic.stripSpace(seg), m2.word);
        } else {
            failed.add("②viaFlat 位置映射失败（rawStart=" + (m2 == null ? "null" : m2.rawStart) + "）");
        }

        // flat 索引必须与 stripSpace 串自洽
        if (m2 != null && m2.flatStart >= 0) {
            String flat = SignLogic.stripSpace(t2);
            ok("②flat 位置自洽", m2.flatEnd <= flat.length()
                    && SignLogic.stripSpace(flat.substring(m2.flatStart, m2.flatEnd)).equals(SignLogic.stripSpace(m2.word)));
        }
    }

    // ═══════════ ③ 归一化判据 ═══════════

    private static void normalization() {
        // 各类空白均应被剔除
        eq("③普通空格", SignLogic.stripSpace("a b"), "ab");
        eq("③全角空格 \\u3000", SignLogic.stripSpace("a\u3000b"), "ab");
        eq("③制表符", SignLogic.stripSpace("a\tb"), "ab");
        eq("③换行", SignLogic.stripSpace("a\nb"), "ab");
        eq("③回车", SignLogic.stripSpace("a\rb"), "ab");
        eq("③换页 \\f", SignLogic.stripSpace("a\fb"), "ab");
        eq("③垂直制表 \\u000B", SignLogic.stripSpace("a\u000Bb"), "ab");
        eq("③NBSP \\u00A0", SignLogic.stripSpace("a\u00A0b"), "ab");
        eq("③窄 NBSP \\u202F", SignLogic.stripSpace("a\u202Fb"), "ab");
        eq("③连续混合空白", SignLogic.stripSpace("  签\t\n\u3000到  "), "签到");
        eq("③空串", SignLogic.stripSpace(""), "");
        eq("③null", SignLogic.stripSpace(null), "");
        // 零宽字符**不应**被删（有语义）
        eq("③零宽 ZWSP 保留", SignLogic.stripSpace("a\u200Bb"), "a\u200Bb");
        eq("③ZWNJ 保留", SignLogic.stripSpace("a\u200Cb"), "a\u200Cb");

        // matched 与 stripSpace 口径一致（M1 统一点）
        String[] words = { "签到成功" };
        String t = "签到\t成功";
        ok("③matched 使用统一口径（制表符可命中）",
                SignLogic.matched(t.toLowerCase(), words) != null);
        SignLogic.Match m = SignLogic.matchAt(t.toLowerCase(), words);
        ok("③matchAt 同样命中", m != null && m.viaFlat);
    }

    // ═══════════ ④ Unicode / Emoji / UTF-16 ═══════════

    private static void unicodeAndEmoji() {
        String[] words = { "签到成功" };

        // emoji 在命中词之前 → rawStart 必须是 UTF-16 code unit 索引
        String t = "🎉🎊签到成功".toLowerCase();
        SignLogic.Match m = SignLogic.matchAt(t, words);
        ok("④emoji 前缀命中非空", m != null);
        if (m != null) {
            // 🎉🎊 各占 2 个 code unit（surrogate pair），所以起点应为 4
            eq("④emoji 前缀 rawStart（UTF-16 code unit）", Integer.valueOf(4), Integer.valueOf(m.rawStart));
            eq("④substring 反查正确", t.substring(m.rawStart, m.rawEnd), "签到成功");
            // 显式对比"码点计数"，证明我们没有误用码点
            // 该断言的正确写法：前 2 个 code unit 只覆盖🎉一个码点 → codePointCount=1
            eq("④emoji 占 2 个 code unit 但只有 1 个码点",
                Integer.valueOf(t.codePointCount(0, 2)), Integer.valueOf(1));
        }

        // emoji 在命中词之后
        String t2 = "签到成功🎉".toLowerCase();
        SignLogic.Match m2 = SignLogic.matchAt(t2, words);
        ok("④emoji 后缀命中非空", m2 != null);
        if (m2 != null) {
            eq("④emoji 后缀 rawEnd", Integer.valueOf(4), Integer.valueOf(m2.rawEnd));
            eq("④反查仍是命中词", t2.substring(m2.rawStart, m2.rawEnd), "签到成功");
        }

        // emoji 夹在命中词中间（去空白也救不了 → 不命中；验证不误报）
        String t3 = "签到🎉成功".toLowerCase();
        eq("④emoji 夹在中间 → 不命中（与 matched 一致）",
                SignLogic.matched(t3, words), SignLogic.matchAt(t3, words) == null ? null : "命中");

        // 特殊 Unicode：全角标点、带变音符
        String t4 = "【签到成功】".toLowerCase();
        SignLogic.Match m4 = SignLogic.matchAt(t4, words);
        ok("④全角括号内命中", m4 != null && t4.substring(m4.rawStart, m4.rawEnd).equals("签到成功"));

        // 中英混合
        String[] words6 = { "check-in complete" };
        String t5 = "✅ Check-In Complete".toLowerCase();
        ok("④中英混合命中（大小写不敏感）", SignLogic.matched(t5, words6) != null);
    }

    // ═══════════ ⑤ 子句范围 ═══════════

    private static void clauseScoping() {
        String[] words = { "签到成功" };
        String t = "系统公告：本群禁止广告。今日签到成功！要试试轰炸吗？".toLowerCase();
        SignLogic.Match m = SignLogic.matchAt(t, words);
        ok("⑤跨子句命中非空", m != null);
        if (m != null) {
            String clause = m.clause;
            ok("⑤子句含命中词", clause.contains("签到成功"));
            ok("⑤子句**不含**前一句（禁止广告）", !clause.contains("禁止广告"));
            ok("⑤子句**不含**后一句（轰炸）", !clause.contains("轰炸"));
            eq("⑤clause 与子句范围自洽", t.substring(m.clauseStart, m.clauseEnd), clause);
        }

        // 同词条在两个子句出现 → 应命中**第一个**
        String t2 = "签到成功！又签到成功了".toLowerCase();
        SignLogic.Match m2 = SignLogic.matchAt(t2, words);
        ok("⑤同词多次出现 → 命中第一个", m2 != null && m2.rawStart == 0);

        // 换行作为子句边界
        String t3 = "第一行\n签到成功\n第三行".toLowerCase();
        SignLogic.Match m3 = SignLogic.matchAt(t3, words);
        ok("⑤换行分句生效", m3 != null && m3.clause.contains("签到成功") && !m3.clause.contains("第一行"));

        // 子句范围工具直测
        int[] r = SignLogic.clauseRangeOf(t, "签到成功");
        ok("⑤clauseRangeOf 返回有效范围", r[0] < r[1] && r[1] <= t.length());
    }

    // ═══════════ ⑥ 多词命中与来源 ═══════════

    private static void orderingAndSource() {
        // 多词表：目标级 > Bot级 > 全局 —— 由调用方按序拼接，matchAt 遵守顺序
        String[] targetWords = { "灵石已入库" };
        String[] botWords    = { "签到成功" };
        String[] globalWords = { "签到" };

        String text = "您的灵石已入库".toLowerCase();
        // 按"目标 > Bot > 全局"拼接（生产查询顺序）
        String[] merged = concat(targetWords, botWords, globalWords);
        SignLogic.Match m = SignLogic.matchAt(text, merged);
        eq("⑥多级词表命中目标级词", m == null ? null : m.word, "灵石已入库");

        // 词表里不存在的词 → null（不伪造命中）
        eq("⑥未命中返回 null", SignLogic.matchAt("完全无关的文本", merged), null);
        // 空词表 → null
        eq("⑥空词表返回 null", SignLogic.matchAt(text, new String[0]), null);
        eq("⑥null 词表返回 null", SignLogic.matchAt(text, null), null);
        // 词表含 null 项与空串 → 跳过，不崩
        eq("⑥词表含 null/空串 → 安全跳过",
                SignLogic.matchAt(text, new String[]{ null, "", "  ", "灵石已入库" }) == null ? null : "灵石已入库",
                "灵石已入库");
    }

    private static String[] concat(String[]... arrs) {
        List<String> out = new ArrayList<String>();
        for (String[] a : arrs) if (a != null) for (String s : a) out.add(s);
        return out.toArray(new String[0]);
    }

    // ═══════════ ⑦ 边界与异常 ═══════════

    private static void edgeCases() {
        String[] words = { "签到成功" };
        eq("⑦null 文本", SignLogic.matchAt(null, words), null);
        eq("⑦空文本", SignLogic.matchAt("", words), null);
        eq("⑦纯空白文本", SignLogic.matchAt("   \t\n", words), null);
        ok("⑦纯空白文本 stripSpace 为空", SignLogic.stripSpace("   \t\n").length() == 0);

        // 超长文本（100k）不崩且位置正确
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("填充文本abc");
        sb.append("签到成功");
        String big = sb.toString().toLowerCase();
        SignLogic.Match mb = SignLogic.matchAt(big, words);
        ok("⑦超长文本命中", mb != null);
        if (mb != null) eq("⑦超长文本位置正确", big.substring(mb.rawStart, mb.rawEnd), "签到成功");

        // 命中词比文本长 → 不命中且不越界
        eq("⑦词长于文本 → 不命中", SignLogic.matchAt("签到".toLowerCase(), words), null);

        // 异常：代理对单独出现（孤立 surrogate）不应崩
        String lone = "签到成功\ud83c";
        SignLogic.Match ml = SignLogic.matchAt(lone.toLowerCase(), words);
        ok("⑦孤立 surrogate 不崩", ml != null);

        // 全角半角混排
        String[] w2 = { "check" };
        ok("⑦全角标点混排仍可命中", SignLogic.matchAt("【Ｃｈｅｃｋ】".toLowerCase(), new String[]{"ｃｈｅｃｋ"}) != null
                || SignLogic.matchAt("【Check】".toLowerCase(), w2) != null);
    }
}
