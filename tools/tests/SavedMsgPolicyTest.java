package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.wlmosv_png.tgautosign.savedmsg.SavedMsgPolicy;

/**
 * 收藏夹简报的**账号级决策链 + 发送拦截路径**测试（2026-10-27，C 批验收）。
 *
 * 与 SignLogicTest 的区别（刻意分开，便于分级判断）：
 *   · SignLogicTest.savedMsgScope  —— 「纯逻辑」：取值优先级函数本身
 *   · 本文件                       —— 「入口与调用链」：真实的 SavedMsgPolicy
 *                                     （Core 生产路径调用的同一个类）+ 可注入
 *                                     配置读取器 + 记录式发送器替身
 *
 * 跑法：javac -d /tmp/tcls tools/tests/SavedMsgPolicyTest.java <app 源码>
 *       java -cp ... io.github.wlmosv_png.tgautosign.SavedMsgPolicyTest
 *
 * 仍未覆盖（如实标注）：Android SharedPreferences 真实持久化、Telegram 真机发送。
 */
public final class SavedMsgPolicyTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    /** 配置源替身：Map 实现，键不存在 → null（与 prefs 的 contains 语义一致）。 */
    static final class FakeFlags implements SavedMsgPolicy.Flags {
        private final Map<String, Boolean> m = new HashMap<String, Boolean>();
        FakeFlags put(String k, boolean v) { m.put(k, Boolean.valueOf(v)); return this; }
        @Override public Boolean find(String key) {
            return m.containsKey(key) ? m.get(key) : null;
        }
    }

    /** 发送器替身：记录每次真实被调用的 (账号, 文本)。 */
    static final class Rec implements SavedMsgPolicy.Sender {
        final List<String> calls = new ArrayList<String>();
        @Override public boolean send(String text, int account) {
            calls.add(account + ":" + text);
            return true;
        }
    }

    public static void main(String[] args) {
        keyNaming();
        scenarioAOffBOn();
        scenarioAOnBOff();
        switchAccountNoCrosstalk();
        persistenceReload();
        missingConfigCompat();
        threeEntrancesAgree();
        notifyCombination();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }

    // ── 键名契约（生产 Core 与测试必须用同一拼接）────────────────────
    private static void keyNaming() {
        eq("键名·acc0", SavedMsgPolicy.accountKey(0), "acc0_cfg_saved_msg");
        eq("键名·acc3(跳号槽位)", SavedMsgPolicy.accountKey(3), "acc3_cfg_saved_msg");
        eq("全局键", SavedMsgPolicy.GLOBAL_KEY, "jmb_saved_msg");
        tru("默认值=开", SavedMsgPolicy.DEFAULT_ON);
    }

    // ── 场景 1：账号 A 关闭、账号 B 开启 ────────────────────────────
    private static void scenarioAOffBOn() {
        FakeFlags f = new FakeFlags().put(SavedMsgPolicy.accountKey(0), false)
                                    .put(SavedMsgPolicy.accountKey(3), true);
        Rec rec = new Rec();
        tru("场景1·A 不放行", !SavedMsgPolicy.deliver(f, 0, true, "sumA", rec));
        tru("场景1·B 放行", SavedMsgPolicy.deliver(f, 3, true, "sumB", rec));
        eq("场景1·只发出 1 条", Integer.valueOf(rec.calls.size()), Integer.valueOf(1));
        eq("场景1·发出的是 B", rec.calls.get(0), "3:sumB");
    }

    // ── 场景 2：账号 A 开启、账号 B 关闭 ────────────────────────────
    private static void scenarioAOnBOff() {
        FakeFlags f = new FakeFlags().put(SavedMsgPolicy.accountKey(0), true)
                                    .put(SavedMsgPolicy.accountKey(3), false);
        Rec rec = new Rec();
        tru("场景2·A 放行", SavedMsgPolicy.deliver(f, 0, true, "sumA", rec));
        tru("场景2·B 不放行", !SavedMsgPolicy.deliver(f, 3, true, "sumB", rec));
        eq("场景2·只发出 1 条", Integer.valueOf(rec.calls.size()), Integer.valueOf(1));
        eq("场景2·发出的是 A", rec.calls.get(0), "0:sumA");
    }

    // ── 场景 3：切换当前账号后，发送行为仍由目标账号自身配置决定 ──────
    private static void switchAccountNoCrosstalk() {
        // 实测现场：acc0=false（用户关过）、acc3 无键（从没设置过）
        FakeFlags f = new FakeFlags().put(SavedMsgPolicy.accountKey(0), false);
        Rec rec = new Rec();
        for (int i = 0; i < 3; i++) {
            tru("场景3·第" + i + "轮 A 仍不放行", !SavedMsgPolicy.savedMsgOn(f, 0));
            tru("场景3·第" + i + "轮 B 仍默认放行", SavedMsgPolicy.savedMsgOn(f, 3));
        }
        // 交错触发（模拟心跳遍历所有账号的顺序无关性）
        SavedMsgPolicy.deliver(f, 3, true, "b1", rec);
        SavedMsgPolicy.deliver(f, 0, true, "a1", rec);
        SavedMsgPolicy.deliver(f, 3, true, "b2", rec);
        eq("场景3·只发出 B 的两条", rec.calls.toString(), "[3:b1, 3:b2]");
    }

    // ── 场景 4：持久化并重新初始化后状态保持（替身：同一份数据重建 Flags）──
    private static void persistenceReload() {
        Map<String, Boolean> stored = new HashMap<String, Boolean>();
        stored.put(SavedMsgPolicy.accountKey(0), Boolean.FALSE);
        stored.put(SavedMsgPolicy.accountKey(3), Boolean.TRUE);
        // 第一次初始化
        FakeFlags first = new FakeFlags();
        for (Map.Entry<String, Boolean> e : stored.entrySet()) first.put(e.getKey(), e.getValue().booleanValue());
        tru("场景4·初始化后 A=关", !SavedMsgPolicy.savedMsgOn(first, 0));
        tru("场景4·初始化后 B=开", SavedMsgPolicy.savedMsgOn(first, 3));
        // 进程重启：同一份持久化数据 → 新建读取器
        FakeFlags second = new FakeFlags();
        for (Map.Entry<String, Boolean> e : stored.entrySet()) second.put(e.getKey(), e.getValue().booleanValue());
        tru("场景4·重启后 A 仍关", !SavedMsgPolicy.savedMsgOn(second, 0));
        tru("场景4·重启后 B 仍开", SavedMsgPolicy.savedMsgOn(second, 3));
        // 槽位变化（宿主读到跳号 7）不应影响已配置账号的结论
        tru("场景4·槽位7 无键→默认开", SavedMsgPolicy.savedMsgOn(second, 7));
        eq("场景4·acc0 值未被改动", second.find("acc0_cfg_saved_msg"), Boolean.FALSE);
    }

    // ── 场景 5/6（缺省兼容）：账号键 → 全局键 → 默认，显式 false 优先 ──
    private static void missingConfigCompat() {
        FakeFlags empty = new FakeFlags();
        tru("缺省·全缺→默认开", SavedMsgPolicy.savedMsgOn(empty, 0));
        FakeFlags g = new FakeFlags().put(SavedMsgPolicy.GLOBAL_KEY, false);
        tru("缺省·全局 false 生效", !SavedMsgPolicy.savedMsgOn(g, 0));
        tru("缺省·全局 false 对所有账号生效", !SavedMsgPolicy.savedMsgOn(g, 3));
        FakeFlags bg = new FakeFlags().put(SavedMsgPolicy.GLOBAL_KEY, false)
                                     .put(SavedMsgPolicy.accountKey(3), true);
        tru("缺省·账号显式 true 覆盖全局 false", SavedMsgPolicy.savedMsgOn(bg, 3));
        tru("缺省·未设过的账号仍随全局", !SavedMsgPolicy.savedMsgOn(bg, 0));
        FakeFlags explicitFalse = new FakeFlags().put(SavedMsgPolicy.accountKey(0), false);
        tru("显式 false 不得被当成未配置", !SavedMsgPolicy.savedMsgOn(explicitFalse, 0));
        tru("显式 false 不污染别的账号", SavedMsgPolicy.savedMsgOn(explicitFalse, 1));
    }

    // ── 三入口一致性：savedMsgOn / canSendSummary / deliver 同源 ──
    private static void threeEntrancesAgree() {
        FakeFlags f = new FakeFlags().put(SavedMsgPolicy.accountKey(0), false);
        Rec rec = new Rec();
        boolean on = SavedMsgPolicy.savedMsgOn(f, 0);
        boolean can = SavedMsgPolicy.canSendSummary(f, 0, true);
        boolean deliv = SavedMsgPolicy.deliver(f, 0, true, "x", rec);
        tru("三入口一致（关→三处都 false）", !on && !can && !deliv);
        eq("拦截后发送器零调用", Integer.valueOf(rec.calls.size()), Integer.valueOf(0));
        eq("deliver 返回 false", Boolean.valueOf(deliv), Boolean.FALSE);
    }

    // ── NOTIFY_ON 组合（保持既有产品语义）──────────────────────────
    private static void notifyCombination() {
        FakeFlags on = new FakeFlags().put(SavedMsgPolicy.accountKey(0), true);
        FakeFlags off = new FakeFlags().put(SavedMsgPolicy.accountKey(0), false);
        tru("都开→可发", SavedMsgPolicy.canSendSummary(on, 0, true));
        tru("关收藏夹→不可发", !SavedMsgPolicy.canSendSummary(off, 0, true));
        tru("关通知→不可发", !SavedMsgPolicy.canSendSummary(on, 0, false));
        tru("都关→不可发", !SavedMsgPolicy.canSendSummary(off, 0, false));
    }

    // ── 断言助手 ──────────────────────────────────────────────────
    private static void eq(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) passed++;
        else failed.add(what + " → 得到 " + got + "，期望 " + want);
    }

    private static void tru(String what, boolean cond) {
        if (cond) passed++;
        else failed.add(what);
    }
}
