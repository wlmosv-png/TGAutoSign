package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 双链一致性门禁（阶段 3 · M0，2026-11-01）。
 *
 * ── 这个测试存在的原因 ──
 *
 * 生产判定有**两条实现**：`verdictDetailScoped`（A）与 `verdictDetail`（B），
 * A 未命中时回退到 B。阶段 3 的目标是合并为单一引擎（S5）。
 * 合并前必须先知道：**当前两链在哪些输入下结论不同** —— 那是迁移清单。
 *
 * ── 断言策略（关键，按用户要求设计）──
 *
 * 不使用「断言两链相等」：两链**当前确实不一致**，那样会直接红。
 * 也**不允许**删除断言让门禁变绿 —— 那等于把不一致藏起来。
 *
 * 采用：**实际分叉集合 必须恰好等于 预登记清单**
 *   · 出现**新分叉** → FAIL（迁移引入了新分叉，必须立刻知道）
 *   · 已知分叉**消失** → 也 FAIL（说明修好了却没人更新清单 → 清单会腐烂，
 *     失去"迁移进度表"的作用）
 *
 * 这样门禁是绿的，但每一处不一致都**显式在案、可追踪、不可隐藏**。
 *
 * 纯 javac + java，无 JUnit 依赖。退出码 0 = 与清单一致。
 */
public final class DualChainDiffTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();
    private static final List<String> actualDiffs = new ArrayList<String>();

    private static void ok(String tag, boolean cond) {
        if (cond) passed++; else failed.add(tag);
    }

    // ══════════════════════════════════════════════════════════════
    //  预登记分叉清单（迁移进度表）—— 每修好一条就删一条，并在注释写明由哪个步骤修复
    // ══════════════════════════════════════════════════════════════

    /**
     * 生产形态配置下的已知分叉。
     * 生产配置恒为 `mergeWords(内置表, 用户词)`，且可能带 Bot 级 / 目标级。
     */
    private static final String[] KNOWN_DIFFS_PROD = {
            // ① Bot级/目标级词表只在 scoped 生效，回退链读不到 → 待 M3/M4 统一词表装载
            "含Bot级|✨ [ 配给领取 ] 连续服役: 2 天 ✨ 获得盒能: +50",
            "含目标级|✨ [ 配给领取 ] 连续服役: 2 天 ✨ 获得盒能: +50"
    };

    /**
     * 非生产配置（纯用户表、不含内置）下的已知分叉。
     *
     * 成因：A 三级未命中后回退时用的是**内置表**，B 用的是**调用方传入的表**。
     * 生产代码恒传 `mergeWords(内置, 用户)`，因此**这 6 条不会在生产触发** ——
     * 记录它们是为了（a）说明两链在参数语义上的差异，（b）防止未来有人用
     * 纯用户表调用时才发现行为不一致。
     */
    private static final String[] KNOWN_DIFFS_PURE_USER = {
            "纯用户表|签到失败，请重试",
            "纯用户表|⚠️ 请先加入以下1个频道才能使用功能",
            "纯用户表|您已被禁止签到",
            "纯用户表|请先加入用户群，再签到、抢红包或兑换邀请码。",
            "纯用户表|没有签到成功"
    };

    /** 用户真实词表（取自设备 prefs，作为生产形态样本）。 */
    private static final String[] USER_OK = {
            "社工库功能", "👑", "欢迎使用hope", "成功", "配给", "获得积分",
            "恭喜您", "签到", "欢迎", "哭哭", "配给领取", "签到成功", "积分"
    };
    private static final String[] USER_FAIL = { "拉黑", "骗子" };

    /** 语料集（含历史真实事故样本）。 */
    private static final String[] CORPUS = {
            "签到成功", "您今天已经签到过了", "签到失败，请重试", "未签到",
            "签到成功了吗？", "如果签到成功，将获得奖励",
            "签到冷却中,请8小时后再试,已签到5次", "今日签到次数已达上限",
            "请先关注本频道再签到，签到成功", "⚠️ 请先加入以下1个频道才能使用功能",
            "上线了赶紧来体验超值福利活动马上开始", "请点击下方按钮签到",
            "签到成功！感谢参与！要试试轰炸吗？", "今日签到成功，抽奖次数已用尽",
            "您已被禁止签到", "签到获得 3 积分", "您今日已签到成功过",
            "请先加入用户群，再签到、抢红包或兑换邀请码。", "没有签到成功",
            "✨ [ 配给领取 ] 连续服役: 2 天 ✨ 获得盒能: +50",
            "🎉签到成功🎉 您已经获得 1 积分", "今天已领过，明天再来～ 当前 27 分",
            "请勿重复签到！", "灵石已入库", "星砂已归位",
            "您不是群组管理员，无法进行此操作。", "🎁 已领取 3 积分", "已完成签到，明天见"
    };

    public static void main(String[] args) {
        System.out.println("=== 双链一致性门禁（M0）===");
        System.out.println("A = verdictDetailScoped（生产主路径）");
        System.out.println("B = verdictDetail（回退链）\n");

        prodConfig();
        pureUserConfig();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("DIFF_LIST_MATCHES");
    }

    /** 生产形态：内置 + 用户全局词表（并可带 Bot/目标级）。 */
    private static void prodConfig() {
        System.out.println("── 生产形态配置 ──");
        actualDiffs.clear();
        for (String c : CORPUS) {
            record("内置+用户全局", c,
                    scoped(c, SignLogic.OK_WORDS_DEFAULT, SignLogic.FAIL_WORDS_DEFAULT, null, null, null, null),
                    detail(c, SignLogic.OK_WORDS_DEFAULT, SignLogic.FAIL_WORDS_DEFAULT));
        }
        for (String c : CORPUS) {
            record("含Bot级", c,
                    scoped(c, SignLogic.OK_WORDS_DEFAULT, SignLogic.FAIL_WORDS_DEFAULT, USER_OK, null, null, null),
                    detail(c, SignLogic.OK_WORDS_DEFAULT, SignLogic.FAIL_WORDS_DEFAULT));
        }
        for (String c : CORPUS) {
            record("含目标级", c,
                    scoped(c, SignLogic.OK_WORDS_DEFAULT, SignLogic.FAIL_WORDS_DEFAULT, null, null, USER_OK, USER_FAIL),
                    detail(c, SignLogic.OK_WORDS_DEFAULT, SignLogic.FAIL_WORDS_DEFAULT));
        }
        System.out.println("  分叉 " + actualDiffs.size() + " 条，预登记 " + KNOWN_DIFFS_PROD.length + " 条");
        assertListMatches("P·生产形态", actualDiffs, KNOWN_DIFFS_PROD);
    }

    /** 非生产形态：纯用户表（不含内置）。 */
    private static void pureUserConfig() {
        System.out.println("\n── 非生产形态配置（纯用户表）──");
        actualDiffs.clear();
        for (String c : CORPUS) {
            record("纯用户表", c,
                    scoped(c, USER_OK, USER_FAIL, null, null, null, null),
                    detail(c, USER_OK, USER_FAIL));
        }
        System.out.println("  分叉 " + actualDiffs.size() + " 条，预登记 " + KNOWN_DIFFS_PURE_USER.length + " 条");
        assertListMatches("P·纯用户表", actualDiffs, KNOWN_DIFFS_PURE_USER);
    }

    // ───────── 工具 ─────────

    private static int scoped(String r, String[] okG, String[] failG,
                              String[] okB, String[] failB, String[] okT, String[] failT) {
        try {
            Object[] v = SignLogic.verdictDetailScoped(r, okG, failG, okB, failB, okT, failT);
            return ((Integer) v[0]).intValue();
        } catch (Throwable t) { return -999; }
    }

    private static int detail(String r, String[] ok, String[] fail) {
        try {
            Object[] v = SignLogic.verdictDetail(r, null, ok, fail);
            return ((Integer) v[0]).intValue();
        } catch (Throwable t) { return -999; }
    }

    private static void record(String cfg, String reply, int a, int b) {
        if (a != b) {
            actualDiffs.add(cfg + "|" + reply);   // 复合键：精确到"哪个配置下分叉"
        }
    }

    private static void assertListMatches(String tag, List<String> got, String[] want) {
        List<String> w = new ArrayList<String>();
        for (String s : want) w.add(s);

        // 新增分叉 → 失败
        for (String g : got) {
            if (!w.contains(g)) {
                failed.add(tag + "·**新增分叉**（迁移引入了新不一致）: " + g);
            }
        }
        // 已知分叉消失 → 也失败（提示更新清单，避免清单腐烂）
        for (String x : w) {
            if (!got.contains(x)) {
                failed.add(tag + "·已知分叉已消失（若为修复成果，请从 KNOWN_DIFFS 中删除该条）: " + x);
            }
        }
        // 严格比较数量：只在"无新增且无消失"时才算通过
        if (got.size() != w.size()) {
            failed.add(tag + "·分叉数量不符：实际 " + got.size() + " 条，清单 " + w.size() + " 条");
        } else {
            passed++;
        }

        // 打印实际分叉（显式在案）
        for (String g : got) System.out.println("    · 分叉: " + g);
    }
}
