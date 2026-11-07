package io.github.wlmosv_png.tgautosign;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HostSymbols 离线单测（零依赖：javac + java 直接跑，不需要 Android / JUnit）。
 *
 * <pre>
 *   javac -d /tmp/scls \
 *     tools/tests/stubs/android/util/Log.java \
 *     tools/tests/fixtures/org/telegram/tgnet/TLRPC.java \
 *     tools/tests/fixtures/org/telegram/messenger/*.java \
 *     app/src/main/java/io/github/wlmosv_png/tgautosign/HostSymbols.java \
 *     tools/tests/HostSymbolsTest.java
 *   java -cp /tmp/scls io.github.wlmosv_png.tgautosign.HostSymbolsTest
 * </pre>
 *
 * 夹具分两套，同一包名下并存：旧名（MessagesController 等）与 13.2.0 混淆名（lp 等）。
 * 通过一个「屏蔽旧名」的 ClassLoader 模拟混淆宿主，从而在纯 Java 环境覆盖两条路径。
 *
 * 退出码 0 = 全过。
 */
public final class HostSymbolsTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    private static final String MC = "org.telegram.messenger.MessagesController";
    private static final String NC = "org.telegram.messenger.NotificationCenter";
    private static final String UC = "org.telegram.messenger.UserConfig";

    private static final Set<String> LEGACY = new HashSet<String>(Arrays.asList(
            MC, NC, UC,
            "org.telegram.messenger.MessagesStorage",
            "org.telegram.messenger.SharedConfig",
            "org.telegram.messenger.DialogObject",
            "org.telegram.messenger.SendMessagesHelper",
            "org.telegram.messenger.UserNameResolver",
            "org.telegram.messenger.BaseController"));

    /** 屏蔽指定类名的 ClassLoader：模拟「原名不存在」的混淆宿主。 */
    private static final class BlockingLoader extends ClassLoader {
        private final Set<String> blocked;
        BlockingLoader(ClassLoader parent, Set<String> blocked) { super(parent); this.blocked = blocked; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (blocked.contains(name)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) { passed++; }
        else { failed.add(what); System.out.println("  FAIL: " + what); }
    }

    private static void eq(String what, Object expect, Object actual) {
        boolean ok = (expect == null) ? actual == null : expect.equals(actual);
        if (!ok) System.out.println("     expect=" + expect + " actual=" + actual);
        check(what, ok);
    }

    public static void main(String[] args) throws Exception {
        ClassLoader plain = HostSymbolsTest.class.getClassLoader();
        ClassLoader blocked = new BlockingLoader(plain, LEGACY);

        // ── 1) 旧版：原名直查（不启用映射，行为与历史一致）──
        HostSymbols.install(plain, "org.telegram.messenger", 171L);
        check("legacy: isMapped=false", !HostSymbols.isMapped());
        Class<?> legacyMc = HostSymbols.mapClass(MC);
        eq("legacy: MessagesController 解析为原名", MC, legacyMc == null ? null : legacyMc.getName());
        check("legacy: hasMessagesController", HostSymbols.hasMessagesController());
        check("legacy: hasNotificationCenter", HostSymbols.hasNotificationCenter());
        check("legacy: hasUserConfig", HostSymbols.hasUserConfig());
        check("legacy: hostAdapted", HostSymbols.hostAdapted());
        eq("legacy: didReceiveNewMessages 读字段真值", 1,
                HostSymbols.didReceiveNewMessagesId(HostSymbols.mapClass(NC)));
        // 旧版方法名直查（不触发结构解析）
        Method legacyGetUser = HostSymbols.method(legacyMc, "getUser", new Class<?>[]{Long.class}, null);
        eq("legacy: MessagesController.getUser(Long) 直查命中", "getUser",
                legacyGetUser == null ? null : legacyGetUser.getName());
        Method legacyPd = HostSymbols.method(legacyMc, "processUpdateArray",
                new Class<?>[]{ArrayList.class, ArrayList.class, ArrayList.class, boolean.class, int.class}, Boolean.FALSE);
        eq("legacy: processUpdateArray 直查命中", "processUpdateArray",
                legacyPd == null ? null : legacyPd.getName());
        check("legacy: processUpdateMethods 走前缀匹配（1 个）",
                HostSymbols.processUpdateMethods(legacyMc).size() == 1);
        // 旧版字段直查
        Object legacyNc = HostSymbols.mapClass(NC).getDeclaredConstructor().newInstance();
        Field lgNcF = HostSymbols.field(HostSymbols.mapClass(NC), "didReceiveNewMessages", null);
        check("legacy: 字段直查命中", lgNcF != null && "didReceiveNewMessages".equals(lgNcF.getName()));

        // ── 2) 旧版路径不受新版映射影响（切回来仍然正确）──
        HostSymbols.install(blocked, "org.telegram.messenger", 72809L);
        check("mapped: isMapped=true", HostSymbols.isMapped());
        HostSymbols.install(plain, "org.telegram.messenger", 172L);
        check("switch-back: 回到旧版后 isMapped=false", !HostSymbols.isMapped());
        Class<?> back = HostSymbols.mapClass(MC);
        eq("switch-back: 仍然解析为原名", MC, back == null ? null : back.getName());

        // ── 3) 新版：版本映射 + 结构校验 ──
        HostSymbols.install(blocked, "org.telegram.messenger", 72809L);
        Class<?> mappedMc = HostSymbols.mapClass(MC);
        eq("mapped: MessagesController → lp", "org.telegram.messenger.lp",
                mappedMc == null ? null : mappedMc.getName());
        eq("mapped: NotificationCenter → os", "org.telegram.messenger.os",
                nameOf(HostSymbols.mapClass(NC)));
        eq("mapped: UserConfig → w10", "org.telegram.messenger.w10", nameOf(HostSymbols.mapClass(UC)));
        eq("mapped: MessagesStorage → wr", "org.telegram.messenger.wr",
                nameOf(HostSymbols.mapClass("org.telegram.messenger.MessagesStorage")));
        eq("mapped: DialogObject → k5", "org.telegram.messenger.k5",
                nameOf(HostSymbols.mapClass("org.telegram.messenger.DialogObject")));
        eq("mapped: SendMessagesHelper → lz", "org.telegram.messenger.lz",
                nameOf(HostSymbols.mapClass("org.telegram.messenger.SendMessagesHelper")));
        eq("mapped: UserNameResolver → y10", "org.telegram.messenger.y10",
                nameOf(HostSymbols.mapClass("org.telegram.messenger.UserNameResolver")));
        check("mapped: hostAdapted", HostSymbols.hostAdapted());

        // 结构方法解析：混淆名不能靠名字，必须靠参数表
        Method mGetUser = HostSymbols.method(mappedMc, "getUser", new Class<?>[]{Long.class}, null);
        eq("mapped: getUser(Long) 结构解析 → Z1", "Z1", mGetUser == null ? null : mGetUser.getName());
        Method mGetDialogs = HostSymbols.method(mappedMc, "getDialogs", new Class<?>[]{int.class}, null);
        eq("mapped: getDialogs(int) 结构解析 → V0", "V0", mGetDialogs == null ? null : mGetDialogs.getName());
        Method mGetInstance = HostSymbols.method(mappedMc, "getInstance", new Class<?>[]{int.class}, Boolean.TRUE);
        eq("mapped: getInstance(int) 结构解析", "getInstance", mGetInstance == null ? null : mGetInstance.getName());
        Method mPd = HostSymbols.method(mappedMc, "processUpdateArray",
                new Class<?>[]{ArrayList.class, ArrayList.class, ArrayList.class, boolean.class, int.class}, Boolean.FALSE);
        eq("mapped: processUpdateArray 结构解析 → K3", "K3", mPd == null ? null : mPd.getName());
        check("mapped: processUpdateMethods 结构定位（非前缀路径）",
                HostSymbols.processUpdateMethods(mappedMc).size() == 1);
        Method mPost = HostSymbols.method(HostSymbols.mapClass(NC), "postNotificationName",
                new Class<?>[]{int.class, Object[].class}, Boolean.FALSE);
        eq("mapped: postNotificationName 结构解析 → k", "k", mPost == null ? null : mPost.getName());

        // 结构字段解析（类型唯一）
        Class<?> lpCls = HostSymbols.mapClass(MC);
        Field fUsers = HostSymbols.field(lpCls, "users", java.util.concurrent.ConcurrentHashMap.class);
        eq("mapped: 字段按类型解析 users → d", "d", fUsers == null ? null : fUsers.getName());
        Class<?> w10Cls = HostSymbols.mapClass(UC);
        Field fUser = HostSymbols.field(w10Cls, "currentUser", Class.forName("org.telegram.tgnet.TLRPC$User", false, plain));
        eq("mapped: UserConfig.currentUser 结构解析 → c", "c", fUser == null ? null : fUser.getName());
        Field fClientId = HostSymbols.field(w10Cls, "clientUserId", long.class);
        eq("mapped: UserConfig.clientUserId 结构解析 → n", "n", fClientId == null ? null : fClientId.getName());
        // 类型提示表驱动的 fieldValue
        Object lpObj = lpCls.getDeclaredConstructor().newInstance();
        check("mapped: fieldValue(users) 非空", HostSymbols.fieldValue(lpObj, "users") != null);
        check("mapped: fieldValue(不存在) 为 null", HostSymbols.fieldValue(lpObj, "noSuchField") == null);

        // 通知 id：混淆宿主读不到字段名 → 官方源码常量
        eq("mapped: didReceiveNewMessages id 退到常量 1", 1,
                HostSymbols.didReceiveNewMessagesId(HostSymbols.mapClass(NC)));

        // ── 3b) 13.2.1 档（Play 实测 72911）：混淆名与 13.2.0 完全不同，必须按版本档切换 ──
        HostSymbols.install(blocked, "org.telegram.messenger", 72911L);
        eq("tier72900: MessagesController → vp", "org.telegram.messenger.vp", nameOf(HostSymbols.mapClass(MC)));
        eq("tier72900: NotificationCenter → ys", "org.telegram.messenger.ys", nameOf(HostSymbols.mapClass(NC)));
        eq("tier72900: UserConfig → c20", "org.telegram.messenger.c20", nameOf(HostSymbols.mapClass(UC)));
        eq("tier72900: BaseController → d1", "org.telegram.messenger.d1",
                nameOf(HostSymbols.mapClass("org.telegram.messenger.BaseController")));
        eq("tier72900: MessagesStorage → hs", "org.telegram.messenger.hs",
                nameOf(HostSymbols.mapClass("org.telegram.messenger.MessagesStorage")));
        eq("tier72900: DialogObject → i5", "org.telegram.messenger.k5".replace("k5", "i5"),
                nameOf(HostSymbols.mapClass("org.telegram.messenger.DialogObject")));
        eq("tier72900: SendMessagesHelper → qz", "org.telegram.messenger.qz",
                nameOf(HostSymbols.mapClass("org.telegram.messenger.SendMessagesHelper")));
        eq("tier72900: UserNameResolver → f20", "org.telegram.messenger.f20",
                nameOf(HostSymbols.mapClass("org.telegram.messenger.UserNameResolver")));
        check("tier72900: hostAdapted", HostSymbols.hostAdapted());
        Class<?> vpCls = HostSymbols.mapClass(MC);
        eq("tier72900: getDialogs 结构解析 → V0", "V0",
                nameOf2(HostSymbols.method(vpCls, "getDialogs", new Class<?>[]{int.class}, null)));
        eq("tier72900: postNotificationName 结构解析 → k", "k",
                nameOf2(HostSymbols.method(HostSymbols.mapClass(NC), "postNotificationName",
                        new Class<?>[]{int.class, Object[].class}, Boolean.FALSE)));
        // 切回 13.2.0 档，两个档位互不串
        HostSymbols.install(blocked, "org.telegram.messenger", 72809L);
        eq("tier72800 仍独立可用: MessagesController → lp", "org.telegram.messenger.lp", nameOf(HostSymbols.mapClass(MC)));
        eq("tier72800 仍独立可用: NotificationCenter → os", "org.telegram.messenger.os", nameOf(HostSymbols.mapClass(NC)));

        // ── 4) 错误映射必须被结构契约拒绝 ──
        HostSymbols.__testInstall(blocked, "org.telegram.messenger", 72809L, new String[][]{
                {MC, "org.telegram.messenger.xd"}});
        check("reject: 非 MessagesController 形状的映射被拒", HostSymbols.mapClass(MC) == null);
        check("reject: rejected → hostAdapted=false", !HostSymbols.hostAdapted());

        // ── 5) 多候选歧义必须拒绝 ──
        HostSymbols.install(plain, "org.telegram.messenger", 171L);
        Class<?> ambCls = Class.forName("org.telegram.messenger.amb", false, plain);
        Method ambiguous = HostSymbols.method(ambCls, "whateverName", new Class<?>[]{int.class}, null);
        check("ambiguity: 两个同参数同返回的方法 → 拒绝", ambiguous == null);

        // ── 6) 未知 / 过旧版本降级 ──
        HostSymbols.install(blocked, "org.telegram.messenger", 70000L);
        check("unknown: 低于映射起效版本 → 不启用映射", !HostSymbols.isMapped());
        check("unknown: hostAdapted=false（明确降级）", !HostSymbols.hostAdapted());
        check("unknown: 状态行含「未适配」", HostSymbols.summaryLine().contains("未适配")
                || HostSymbols.summaryLine().contains("原名直查"));

        // ── 7) 关键符号缺失 ──
        HostSymbols.__testInstall(blocked, "org.telegram.messenger", 72809L, new String[][]{
                {"org.telegram.messenger.NotificationCenter", "org.telegram.messenger.os"}});
        check("partial: 仅有 NC → MC 缺失", !HostSymbols.hasMessagesController());
        check("partial: 仍有事件层能力", HostSymbols.hasNotificationCenter());
        check("partial: hostAdapted=true（事件层可用）", HostSymbols.hostAdapted());

        // ── 8) 消息对象结构判定 + 通知参数形状 ──
        HostSymbols.install(plain, "org.telegram.messenger", 171L);
        Class<?> moCls = Class.forName("org.telegram.messenger.MessageObjectFixture", false, plain);
        Class<?> plainCls = Class.forName("org.telegram.messenger.PlainFixture", false, plain);
        check("shape: 持有 TLRPC$Message 字段 → 是消息对象", HostSymbols.looksLikeMessageObject(moCls));
        check("shape: 普通对象 → 不是消息对象", !HostSymbols.looksLikeMessageObject(plainCls));
        Object moObj = moCls.getDeclaredConstructor().newInstance();
        Class<?> msgCls = Class.forName("org.telegram.tgnet.TLRPC$Message", false, plain);
        moCls.getField("messageOwner").set(moObj, msgCls.getDeclaredConstructor().newInstance());
        check("shape: ownerMessage 取到内部 TLRPC$Message", HostSymbols.ownerMessage(moObj) != null);
        check("shape: ownerMessage 对普通对象为 null", HostSymbols.ownerMessage(plainCls.getDeclaredConstructor().newInstance()) == null);

        List<Object> ml = new ArrayList<Object>();
        ml.add(moObj);
        check("args: (List<MessageObject>, boolean) 命中",
                HostSymbols.looksLikeDidReceiveArgs(new Object[]{ml, Boolean.TRUE}));
        check("args: 空 List + boolean 也命中（保守接受）",
                HostSymbols.looksLikeDidReceiveArgs(new Object[]{new ArrayList<Object>(), Boolean.FALSE}));
        check("args: 参数个数不对 → 不命中",
                !HostSymbols.looksLikeDidReceiveArgs(new Object[]{ml}));
        check("args: 第二参不是 boolean → 不命中",
                !HostSymbols.looksLikeDidReceiveArgs(new Object[]{ml, "x"}));
        check("args: 非 Object[] → 不命中", !HostSymbols.looksLikeDidReceiveArgs("nope"));

        // ── 9) 缓存隔离：同一 loader 下切版本必须重新解析 ──
        HostSymbols.install(plain, "org.telegram.messenger", 171L);
        eq("cache: v171 → 原名", MC, nameOf(HostSymbols.mapClass(MC)));
        HostSymbols.install(blocked, "org.telegram.messenger", 72809L);
        eq("cache: v72809 → lp（缓存未串版本）", "org.telegram.messenger.lp", nameOf(HostSymbols.mapClass(MC)));
        HostSymbols.install(plain, "org.telegram.messenger", 171L);
        eq("cache: 切回 v171 → 原名", MC, nameOf(HostSymbols.mapClass(MC)));

        // ── 10) tgnet 包不受影响（不属于被混淆的包）──
        check("tgnet: mapClass 不接管 tgnet", HostSymbols.mapClass("org.telegram.tgnet.TLRPC$Peer") == null);
        eq("tgnet: 常量与源码一致", 1, HostSymbols.NOTIFICATION_DID_RECEIVE_NEW_MESSAGES);

        System.out.println();
        System.out.println("HostSymbolsTest: passed=" + passed + " failed=" + failed.size());
        if (!failed.isEmpty()) {
            for (String f : failed) System.out.println("  - " + f);
            System.exit(1);
        }
        System.exit(0);
    }

    private static String nameOf(Class<?> c) { return c == null ? null : c.getName(); }
    private static String nameOf2(Method m) { return m == null ? null : m.getName(); }

    private HostSymbolsTest() { }
}
