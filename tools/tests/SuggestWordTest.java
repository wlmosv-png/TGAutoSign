package io.github.wlmosv_png.tgautosign;

import java.util.List;

import io.github.wlmosv_png.tgautosign.judge.ReplyNormalizer;

/**
 * SuggestWordTest —— 未识别回复重构（问题 1/2/3）的纯逻辑测试。
 *
 * 覆盖：
 *  · suggestWord 能对**内置词表里没有**的新措辞给出推荐
 *  · 噪声/客套/否定不推荐
 *  · sameDay 跨天判断
 *  · 归一化 → 提词的端到端（数字塌成 {N} 的场景）
 */
public final class SuggestWordTest {

    private static int passed = 0;
    private static final java.util.List<String> failed = new java.util.ArrayList<String>();

    public static void main(String[] args) {
        newPhrasings();
        noiseRejected();
        negationRejected();
        sameDayTests();
        normalizeThenSuggest();
        adFilter();
        adNarrowed();
        negationWindow();
        confidence();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }

    // ── ⑥ 广告/长文过滤（用户反馈：学习页被推广长文占满） ──
    private static void adFilter() {
        // 用户截图里那两条真实广告
        String ad1 = "恭喜您获取到幸运扩展任务扩展任务是需要您在其他群内发送若干消息然后"
                   + "就获得特定的积分收益完成一个任务预计获得10-20积分不定数量积分";
        ntru("推广长文不进池", SignLogic.worthLearning(ad1));
        String ad2 = "配给领取】—————————连续服役7天✨获得盒能+5恢复生命+3"
                   + "邀请好友+3盒能/人每5人兑30天会员🔥独家算法·严格验证·远超同行";
        ntru("第二条推广长文不进池", SignLogic.worthLearning(ad2));
        // 正常的判不出的结果应该收
        tru("『任务达成』该收", SignLogic.worthLearning("任务达成"));
        tru("『还没有绑定囡囡呢』该收", SignLogic.worthLearning("您都还没有绑定囡囡呢"));
        // 超长但含签到语义 → 收（可能是规则说明里带结果）
        String longSign = "签到说明：每日签到可获得积分，签到成功后会显示" + repeat("说明", 60);
        tru("超长但含签到语义 → 收", SignLogic.worthLearning(longSign));
        ntru("null 不收", SignLogic.worthLearning(null));
        ntru("单字不收", SignLogic.worthLearning("好"));
    }


    // ── ⑦ P0/P1：AD_WORDS 收窄后不再误杀真结果 ──
    private static void adNarrowed() {
        tru("『签到成功，限时福利已到账』该收（原会被限时误杀）",
            SignLogic.worthLearning("签到成功，限时福利已到账"));
        tru("『签到成功，优惠已发放』该收",
            SignLogic.worthLearning("签到成功，优惠已发放"));
        tru("『恭喜获得今日福利』该收",
            SignLogic.worthLearning("恭喜获得今日福利"));
        ntru("铁广告仍被拦（扩展任务）",
             SignLogic.worthLearning("恭喜您获取到幸运扩展任务，独家算法"));
        ntru("含链接仍被拦", SignLogic.worthLearning("点这里 http://x.com/a"));
        // 2026-10-07：用户截图里真实漏网的那条推广长文
        String realAd = "📣变态美女图#dogeindex_{N}b{N}fe{N}e{N} 📣中文曝光吧安危头条吃瓜(接广告合作) "
                      + "📣中文pg体育吧(禁广告)-{N}.{N}k{N} 📣博彩供需付费广告{N}u/次-{N}.{N}k{N} "
                      + "📣tk打粉交流群.tiktok广告户老户";
        ntru("截图里那条推广长文必须被拦", SignLogic.worthLearning(realAd));
        ntru("含『接广告合作』被拦", SignLogic.worthLearning("本号接广告合作，私聊"));
        ntru("含『博彩』被拦", SignLogic.worthLearning("博彩平台招代理"));
        ntru("含『打粉』被拦", SignLogic.worthLearning("tk打粉交流群"));
        // 超过 400 字一律不收
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 60; i++) big.append("这是很长的一段说明文字");
        ntru("超 400 字一律不收", SignLogic.worthLearning(big.toString()));
        // 正常结果仍要收
        tru("正常『任务达成』仍收", SignLogic.worthLearning("任务达成"));
    }

    // ── ⑧ P1：否定窗口自适应 ──
    private static void negationWindow() {
        int v;
        v = SignLogic.verdictOf("今天没有签到成功", null, null, null);
        eq("『今天没有签到成功』→ 不该判成功", Integer.valueOf(v), Integer.valueOf(SignLogic.V_FAILED));
        v = SignLogic.verdictOf("没有问题,签到成功", null, null, null);
        eq("『没有问题,签到成功』→ 仍算成功", Integer.valueOf(v), Integer.valueOf(SignLogic.V_SIGNED));
        v = SignLogic.verdictOf("不错,今日已签到", null, null, null);
        eq("『不错,今日已签到』→ 仍算成功", Integer.valueOf(v), Integer.valueOf(SignLogic.V_SIGNED));
        v = SignLogic.verdictOf("还没有签到", null, null, null);
        tru("『还没有签到』不是成功", v != SignLogic.V_SIGNED);
    }

    // ── ⑨ P1：置信度 ──
    private static void confidence() {
        eq("明确词 → HIGH", Integer.valueOf(SignLogic.confidenceOf("签到成功", false)),
           Integer.valueOf(SignLogic.CONF_HIGH));
        eq("组合判定 → MEDIUM", Integer.valueOf(SignLogic.confidenceOf("签到+已", true)),
           Integer.valueOf(SignLogic.CONF_MEDIUM));
        eq("单字词 → MEDIUM", Integer.valueOf(SignLogic.confidenceOf("签", false)),
           Integer.valueOf(SignLogic.CONF_MEDIUM));
        eq("无命中 → LOW", Integer.valueOf(SignLogic.confidenceOf("", false)),
           Integer.valueOf(SignLogic.CONF_LOW));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static void eq(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) passed++;
        else failed.add(what + " → 得到 " + got + "，期望 " + want);
    }

    private static void tru(String what, boolean cond) {
        if (cond) passed++; else failed.add(what + " → 条件不成立");
    }

    private static void ntru(String what, boolean cond) {
        if (!cond) passed++; else failed.add(what + " → 本不该成立");
    }

    // ── ① 新措辞（内置 46 词表里没有的）也能提出推荐 ──
    private static void newPhrasings() {
        // 这些都不是 LearnPage.extractWord 里的固定词
        String a = SignLogic.suggestWord("任务达成");
        tru("『任务达成』能提出推荐", a != null && a.length() > 0);

        String b = SignLogic.suggestWord("本日事项已办妥");
        tru("『本日事项已办妥』能提出推荐", b != null && b.length() > 0);

        String c = SignLogic.suggestWord("处理完毕");
        tru("『处理完毕』能提出推荐", c != null && c.length() > 0);

        String d = SignLogic.suggestWord("结算已完成");
        tru("『结算已完成』能提出推荐", d != null && d.length() > 0);
    }

    // ── ② 噪声/客套不推荐 ──
    private static void noiseRejected() {
        // 纯 {N}（归一化后的数字）不该被推荐
        ntru("纯 {N} 不推荐", "{n}".equals(SignLogic.suggestWord("{n}")));
        // 单字不推荐
        ntru("单字不推荐", "好".equals(SignLogic.suggestWord("好")));
        // 纯欢迎语：可以提，但不应把「欢迎」本身当词
        String w = SignLogic.suggestWord("欢迎使用本服务");
        ntru("不推荐『欢迎使用』整段", "欢迎使用本服务".equals(w));
    }

    // ── ③ 否定句不推荐否定片段 ──
    private static void negationRejected() {
        String x = SignLogic.suggestWord("还没有签到");
        // 允许 null，或推荐不含否定词的片段；绝不能把「还没有签到」整段当词
        ntru("不推荐整段否定句", "还没有签到".equals(x));
    }

    // ── ④ sameDay ──
    private static void sameDayTests() {
        long now = System.currentTimeMillis();
        tru("同一时刻同天", SignLogic.sameDay(now, now));
        tru("相隔 1 小时同天（多数情况下）",
            SignLogic.sameDay(now, now + 3600_000L) || SignLogic.sameDay(now, now - 3600_000L));
        ntru("相隔 30 天不同天", SignLogic.sameDay(now, now - 30L * 24 * 3600 * 1000));
    }

    // ── ⑤ 归一化 → 提词（数字塌成 {N} 后仍能提出） ──
    private static void normalizeThenSuggest() {
        String raw = "恭喜您,今日任务完成啦,获得 10 积分";
        String norm = ReplyNormalizer.normalize(raw);
        String sug = SignLogic.suggestWord(norm);
        tru("归一化后能提出推荐（原句：" + raw + "）", sug != null && sug.length() > 0);
        // 推荐词必须是「有判别力」的片段，不能是纯占位
        if (sug != null) ntru("推荐词不是纯占位", "{n}".equals(sug));
    }
}
