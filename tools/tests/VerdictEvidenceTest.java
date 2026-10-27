package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 签到判定「证据链」回归测试（2026-10-28）。
 *
 * 设计原则（对应本轮审计要求）：
 *   ① **先于修复**编写：在当前生产逻辑上跑，必须能抓出旧问题（P0/P1）；
 *   ② 用例文本**不是编造的** —— 全部来自真实日志 + Telegram 原始消息
 *      （宿主 cache4.db 的 messages_v2，只读副本），注释里保留：
 *      日志文件与行号、时间戳、目标 did、原始消息内容；
 *   ③ 正反都测：既要"错误消息不得判成功"，也要"真结果不得被漏判"。
 *
 * 跑法：
 *   javac -d /tmp/tcls app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java \
 *         tools/tests/VerdictEvidenceTest.java
 *   java -cp /tmp/tcls io.github.wlmosv_png.tgautosign.VerdictEvidenceTest
 */
public final class VerdictEvidenceTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    /** 设备 prefs `jmb_ok_words` 的真实快照（2026-10-28 04:2x 读取）。 */
    private static final String USER_WORDS =
            "社工库功能,👑,欢迎使用hope,成功,配给,获得积分,恭喜您,签到,欢迎,哭哭,配给领取";

    // ══════════════ 真实原文（来自 cache4.db messages_v2 / 模块日志）══════════════

    /** P0-① 目标 8353929739，mid=14044，10-25 02:37:08（按钮另在 reply_markup）。 */
    private static final String REAL_WELCOME_835 = "欢迎您!";

    /** P0-② 目标 8353929739，mid=14045，10-25 02:37:08 —— 账户信息面板，无签到结论。 */
    private static final String REAL_ACCOUNT_PANEL_835 =
            "欢迎来到六扇门社工库！\n\n · 您的账户信息 \n\n · 当前积分：49 分\n · 人民币余额：0.00 元\n"
            + " · 会员状态：非会员\n · 今日推广：0 人\n · 累计推广：0 人\n\n · 查看邀请返利说明\n\n"
            + " · 支持的查询类型：\n身份证号码(含台湾) | 手机号码 | 姓名 | QQ号 | 邮箱";

    /** P0-③ 目标 8251582055，mid=14048，10-25 02:37:18 —— 帮助说明（含"每日签到可获得 1 积分"）。 */
    private static final String REAL_HELP_825 =
            "欢迎使用，当前积分: 19，详情演示：/help\n\n普通查询: 直接发送·姓名·电话或身份证·任一即可查询\n"
            + "· 积分获取方式\n- /qd: 每日签到可获得 1 积分\n- /info: 邀请每位新用户可获得 3 积分 (前往个人中心一键邀请)";

    /** 真结果：目标 7999853294，mid=14517，10-28 03:34:10（上一条 14516 是进度提示）。 */
    private static final String REAL_RESULT_799 =
            "签到成功\n您已经获得 1 积分\n您现在的积分为：18分\n邀请1位朋友点你的邀请链接可获得3积分 每日不限";

    /** 进度提示：目标 7999853294，mid=14516，10-28 03:34:09。 */
    private static final String REAL_PROGRESS_799 =
            "正在签到,请稍后...\n 🔖 认准永久唯一用户名 @qingbaobu（情报部拼音）其他版本都是骗子";

    /** P1-① 目标 8286060286，mid=14456，10-27 19:04:30 —— 「配给领取」= 该 bot 的签到结果。 */
    private static final String REAL_PEIJI_RESULT_828 =
            "[ 配给领取 ]\n · 连续服役: 2 天 ·\n · 获得盒能: +50\n · 恢复生命: +1\n ·\n · 盒能: 1657  生命: 100/100\n"
            + " · 邀请好友 +100盒能+100AI点/人\n · 独家算法 · 严格验证 · 远超同行";

    /** P1-② 目标 8286060286，mid=14515，10-28 03:34:04 —— 「今日已配给」= 今天已领（已签语义）。 */
    private static final String REAL_PEIJI_DONE_828 =
            "[ 今日已配给 ]\n · 今日已领取（间隔24小时）\n · 距下次签到: 15小时30分钟\n"
            + " · 邀请好友 +100盒能+100AI点/人";

    /** P2 面板：目标 8519588272，mid=14534，10-28 03:34:46（含「每日签到」按钮字）。 */
    private static final String REAL_PANEL_851 =
            "暗影社工库 | 2026最新数据\n\n · 政务接口 | ·26年新数据 | ·短信轰炸\n"
            + " · 官方频道 @anyingsgk\n · 产品客服 @anyinbot\n · 高级人工查询 @bbffo\n\n"
            + "账户信息 / 充值学分 / 推广中心 / 每日签到";

    /** P2 菜单：目标 8519588272，mid=14535，10-28 03:34:46。 */
    private static final String REAL_MENU_851 = "请在下方菜单选择社工库功能\n 数据查询 / 政务查询 / 增值业务";

    /**
     * Little Kunlun 真机案例（2026-10-24 13:46，目标 8812118767 / mfsgk）：
     *   日志：[回复判定]8812118767 命中失败词「拉黑」→ 判定未成功
     *   正文：❌️ 你今天已经签到了. > 你的名字:Little Kunlun > 你的简介:陌生人有事找我请务必通过…
     *   用户原话：「这只鸡好像中了你的内置判定，即使我添加了新的判定也是一直判定失败」
     * 语义：bot 其实是在说"今天已经签过了" = 有效结论，只是简介里带了威胁词「拉黑」。
     */
    private static final String REAL_LKL_ALREADY_SIGNED =
            "❌️ 你今天已经签到了.    >  你的名字:Little Kunlun   >  你的简介:陌生人有事找我请务必通过，不通过就拉黑";

    /** 同一 bot 的**纯条件威胁**（没有签到结论）→ 必须仍然判失败。 */
    private static final String REAL_PURE_THREAT =
            "⚠️ 请先加入频道，否则拉黑并禁止签到";

    /** 其他目标的历史成功回复（用于「不能因为一句话就放行」的负例）。 */
    private static final String REAL_QUERY_MENU = "综合查询 /cx\n输入手机号·姓名·身份证·QQ号均可查询\n全库并行检索，自动聚合所有匹配结果";

    // ══════════════════════════ 入口 ══════════════════════════

    public static void main(String[] args) {
        System.out.println("=== 签到判定证据链回归（真实日志/原始消息）===");

        System.out.println("\n-- P0：用户自定义泛词不得单独构成成功（旧问题，修复前应失败）--");
        mustNotSign("P0-① 欢迎语+用户词「欢迎」", REAL_WELCOME_835);
        mustNotSign("P0-② 账户面板+用户词", REAL_ACCOUNT_PANEL_835);
        mustNotSign("P0-③ 帮助说明(含'每日签到可获得1积分')+用户词", REAL_HELP_825);
        mustNotSign("P0-④ 通用欢迎语+用户词「欢迎」", "欢迎使用本机器人,发送 /start 查看功能");
        mustNotSign("P0-⑤ 泛词「成功」+非签到文本", "操作成功,感谢使用");
        mustNotSign("P0-⑥ 泛词「完成」+非签到文本", "资料更新完成");
        mustNotSign("P0-⑦ 泛词「恭喜」+非签到文本", "恭喜您获得新人礼包");

        System.out.println("\n-- P1：真结果不得被公告守卫误杀 --");
        mustSign("P1-① 配给领取(N 天 + 盒能 +50)", REAL_PEIJI_RESULT_828);
        mustSign("P1-② 今日已配给/今日已领取", REAL_PEIJI_DONE_828);

        System.out.println("\n-- 真结果：内置强词必须继续识别（防收紧过头）--");
        mustSign("真结果-① 签到成功+获得积分", REAL_RESULT_799);
        mustSign("真结果-② 已签到", "您今天已经签到过了,明天再来");
        mustSign("真结果-③ 签到成功(纯)", "签到成功");
        mustSign("真结果-④ 打卡完成", "打卡完成,获得 2 积分");

        // 2026-10-24 用户真机案例（Little Kunlun）：条件威胁词不得压过"已经签到"结论
        mustSign("LKL-① 已签到 + 简介带「拉黑」", REAL_LKL_ALREADY_SIGNED);
        mustNotSign("LKL-② 纯条件威胁（无签到结论）", REAL_PURE_THREAT);

        System.out.println("\n-- 广告/菜单/进度：不得判成功 --");
        mustNotSign("广告-① 社工库面板(含'每日签到')", REAL_PANEL_851);
        mustNotSign("广告-② 选择功能菜单", REAL_MENU_851);
        mustNotSign("广告-③ 查询菜单", REAL_QUERY_MENU);
        mustNotSign("进度-① 正在签到,请稍后", REAL_PROGRESS_799);
        mustNotSign("进度-② 正在签到(带传送门)", "✅ 正在签到,请稍后... 如机器人卡死，可选择其他机器人：玄武机器人传送门");
        mustNotSign("空/无证据-① 空文本", "");
        mustNotSign("空/无证据-② 纯 emoji", "😀🎉");
        mustNotSign("空/无证据-③ 无结论提示", "该功能正在维护，请稍后再试");

        System.out.println("\n-- 一致性：同一文本，命中词放用户表 / 内置表，结论必须一致 --");
        consistentBranch("一致性-① 欢迎语", REAL_WELCOME_835);
        consistentBranch("一致性-② 成功类文本", "操作成功,感谢使用");

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

    private static int verdict(String text, String[] okMerged) {
        Object[] vd = SignLogic.verdictDetailScoped(text, okMerged, SignLogic.FAIL_WORDS_DEFAULT,
                null, null, null, null);
        return ((Integer) vd[0]).intValue();
    }

    /** 用真实用户词表判定。 */
    private static int verdict(String text) {
        return verdict(text, merge(SignLogic.OK_WORDS_DEFAULT, SignLogic.parseExtraWords(USER_WORDS)));
    }

    private static void mustNotSign(String name, String text) {
        int v = verdict(text);
        if (v == SignLogic.V_SIGNED) {
            failed.add(name + " → ★误判为签到成功（该文本没有签到结论）");
        } else {
            passed++;
        }
    }

    private static void mustSign(String name, String text) {
        int v = verdict(text);
        if (v == SignLogic.V_SIGNED || v == SignLogic.V_EXHAUSTED) {
            passed++;
        } else {
            failed.add(name + " → 真结果被漏判（verdict=" + v + "，期望 V_SIGNED）");
        }
    }

    /**
     * 同一段文本，分别「只给内置词表」与「内置+用户词表」判定，结论必须一致 ——
     * 用来钉死「用户词走不同分支」这类不一致（P0 的根因之一）。
     */
    private static void consistentBranch(String name, String text) {
        int withUser = verdict(text);
        int builtinOnly = verdict(text, SignLogic.OK_WORDS_DEFAULT);
        if (withUser == builtinOnly) {
            passed++;
        } else {
            failed.add(name + " → 用户词与内置词分支结论不一致（用户词=" + withUser + " 内置=" + builtinOnly + "）");
        }
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
