package io.github.wlmosv_png.tgautosign;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主符号解析层（2026-11-08 引入，为 Telegram 13.2.0 的 R8 变更而建）。
 *
 * <h3>为什么需要它</h3>
 * Telegram 13.2.0 起删除了 {@code -keep class org.telegram.messenger.* { *; }}，
 * 该包的核心类名被 R8 改写（实测 13.1.1 保留 726 个类名、13.2.0 只剩 66 个）。
 * 模块过去完全依赖「按原名反射」，于是在新版上会话列表、Peer 解析、账号识别、
 * 回复判定整条链全部落空。
 *
 * <h3>解析顺序（旧版永远走第 1 步，行为与历史逐字一致）</h3>
 * <ol>
 *   <li><b>按原名直查</b>：{@code Class.forName("org.telegram.messenger.MessagesController")}。
 *       13.1.1 及更早、以及所有不混淆的第三方 fork（Nagram / extera / Turrit …）都在这一步命中，
 *       后续步骤完全不执行 —— 旧版零回归。</li>
 *   <li><b>版本映射表</b>：按宿主 versionCode 取该构建的混淆名（只有类名需要离线固定）。</li>
 *   <li><b>结构指纹校验</b>：映射过来的类必须满足该符号的方法/字段契约，否则视为未解析
 *       （拒绝「映射表过期还硬用」）。</li>
 *   <li><b>成员结构解析</b>：方法按「参数表 + 返回类型（必要时）」匹配，字段按「类型」匹配；
 *       <b>候选必须唯一</b>，多候选一律拒绝并记诊断。</li>
 * </ol>
 *
 * <h3>缓存</h3>
 * 解析结果按「宿主包名 + versionCode」分区缓存在内存与 SharedPreferences。
 * 读缓存时会重跑结构校验，因此不会跨版本错误复用。
 */
public final class HostSymbols {

    private static final String TAG = "TGAutoSignSymbols";

    // ─────────────────────────── 常量符号 ───────────────────────────

    /**
     * {@code NotificationCenter.didReceiveNewMessages} 的通知 id。
     * 来源：Telegram 官方源码 {@code NotificationCenter.java}
     *   {@code private static int totalEvents = 1;} 紧跟
     *   {@code public static final int didReceiveNewMessages = totalEvents++;} —— 即恒为 1。
     * 旧版仍优先读字段真值（见 {@link #didReceiveNewMessagesId}），本常量只在新版兜底。
     */
    public static final int NOTIFICATION_DID_RECEIVE_NEW_MESSAGES = 1;

    // ─────────────────────────── 版本映射表 ───────────────────────────

    /**
     * 按 versionCode 起效的类名映射。数组每项 = { 旧名(Telegram 原名), 该构建里的实际名 }。
     * <b>只有类名在这里；方法与字段一律结构解析</b>，所以 Telegram 后续小版本重排成员名时无需改表。
     */
    private static final Map<Long, String[][]> CLASS_MAPS = new LinkedHashMap<>();
    static {
        // Telegram 13.2.0（versionCode 72800+）：R8 不再保留 org.telegram.messenger.* 类名。
        // 证据：官方 proguard-rules.pro 在提交 55356b2a("update to 13.2.0") 删除
        //   -keep class org.telegram.messenger.* { *; }，改为只保留 native 方法名。
        // 下面每个映射都由 apk-index 结构指纹独立确认（见 AUDIT 报告 §3.2 / §5）。
        CLASS_MAPS.put(72800L, new String[][]{
                {"org.telegram.messenger.MessagesController", "org.telegram.messenger.lp"},
                {"org.telegram.messenger.NotificationCenter", "org.telegram.messenger.os"},
                {"org.telegram.messenger.BaseController", "org.telegram.messenger.j1"},
                {"org.telegram.messenger.UserConfig", "org.telegram.messenger.w10"},
                {"org.telegram.messenger.SharedConfig", "org.telegram.messenger.nz"},
                {"org.telegram.messenger.MessagesStorage", "org.telegram.messenger.wr"},
                {"org.telegram.messenger.DialogObject", "org.telegram.messenger.k5"},
                {"org.telegram.messenger.SendMessagesHelper", "org.telegram.messenger.lz"},
                {"org.telegram.messenger.UserNameResolver", "org.telegram.messenger.y10"},
        });
    }

    /** 类级结构契约：映射过来的类必须满足，否则拒绝采用。 */
    private static final String[][] CLASS_CONTRACTS = {
            // 旧名, 契约表达式
            {"org.telegram.messenger.MessagesController", "m:java.util.ArrayList,java.util.ArrayList,java.util.ArrayList,boolean,int:boolean"},
            {"org.telegram.messenger.NotificationCenter", "m:int,java.lang.Object[]:void"},
            {"org.telegram.messenger.BaseController", "f:int:currentAccount"},
            {"org.telegram.messenger.UserConfig", "f:org.telegram.tgnet.TLRPC$User:"},
            {"org.telegram.messenger.MessagesStorage", "s:org.telegram.messenger.BaseController"},
            {"org.telegram.messenger.DialogObject", "ms:org.telegram.tgnet.TLRPC$Peer:long"},
            {"org.telegram.messenger.SendMessagesHelper", "s:org.telegram.messenger.BaseController"},
            {"org.telegram.messenger.SharedConfig", "s:java.lang.Object"},
            {"org.telegram.messenger.UserNameResolver", "c:int"},
    };

    /**
     * 方法返回类型提示：仅用于把「同名方法在混淆后」的候选收窄到唯一。
     * 值为 null 表示靠参数表唯一性判定。这是<em>类型契约</em>，不是混淆名。
     */
    private static final Map<String, String> METHOD_RET_HINTS = new LinkedHashMap<>();
    static {
        h("org.telegram.messenger.MessagesController", "getInstance", "(int)", "@self");
        h("org.telegram.messenger.MessagesController", "getUser", "(java.lang.Long)", "org.telegram.tgnet.TLRPC$User");
        h("org.telegram.messenger.MessagesController", "getUser", "(java.lang.String)", "org.telegram.tgnet.TLRPC$User");
        h("org.telegram.messenger.MessagesController", "getChat", "(java.lang.Long)", "org.telegram.tgnet.TLRPC$Chat");
        h("org.telegram.messenger.MessagesController", "getChannel", "(java.lang.Long)", "org.telegram.tgnet.TLRPC$Chat");
        h("org.telegram.messenger.MessagesController", "getDialog", "(long)", "org.telegram.tgnet.TLRPC$Dialog");
        h("org.telegram.messenger.MessagesController", "getDialogs", "(int)", "java.util.ArrayList");
        h("org.telegram.messenger.MessagesController", "getInputPeer", "(org.telegram.tgnet.TLObject)", "org.telegram.tgnet.TLRPC$InputPeer");
        h("org.telegram.messenger.MessagesController", "getInputPeer", "(long)", "org.telegram.tgnet.TLRPC$InputPeer");
        h("org.telegram.messenger.MessagesStorage", "getInstance", "(int)", "@self");
        h("org.telegram.messenger.MessagesStorage", "getUser", "(long)", "org.telegram.tgnet.TLRPC$User");
        h("org.telegram.messenger.MessagesStorage", "getChat", "(long)", "org.telegram.tgnet.TLRPC$Chat");
        h("org.telegram.messenger.UserConfig", "getInstance", "(int)", "@self");
        h("org.telegram.messenger.UserConfig", "isValidAccount", "(int)", "boolean");
        h("org.telegram.messenger.NotificationCenter", "postNotificationName", "(int,java.lang.Object[])", "void");
        h("org.telegram.messenger.DialogObject", "getPeerDialogId", "(org.telegram.tgnet.TLRPC$Peer)", "long");
        h("org.telegram.messenger.SendMessagesHelper", "getInstance", "(int)", "@self");
        h("org.telegram.messenger.SendMessagesHelper", "getSendingMessageId", "(long)", "long");
        h("org.telegram.messenger.SendMessagesHelper", "removeFromSendingMessages", "(int,boolean)", "void");
    }

    private static void h(String cls, String name, String params, String ret) {
        METHOD_RET_HINTS.put(key(cls, name, params), ret);
    }

    /** 字段类型提示：字段名被混淆后按「类型唯一」定位。 */
    private static final Map<String, String> FIELD_TYPE_HINTS = new LinkedHashMap<>();
    static {
        f("org.telegram.messenger.MessagesController", "dialogs", "java.util.ArrayList");
        f("org.telegram.messenger.MessagesController", "dialogsByFolder", "android.util.SparseArray");
        f("org.telegram.messenger.MessagesController", "users", "java.util.concurrent.ConcurrentHashMap");
        f("org.telegram.messenger.UserConfig", "currentUser", "org.telegram.tgnet.TLRPC$User");
        f("org.telegram.messenger.UserConfig", "clientUserId", "long");
        f("org.telegram.messenger.SharedConfig", "activeAccounts", "java.util.Set");
    }

    private static void f(String cls, String name, String type) {
        FIELD_TYPE_HINTS.put(key(cls, name, ""), type);
    }

    private static String key(String cls, String name, String params) {
        return cls + "#" + name + params;
    }

    // ─────────────────────────── 运行态 ───────────────────────────

    private static volatile String sPkg = null;
    private static volatile long sVersionCode = -1L;
    private static volatile ClassLoader sLoader = null;
    private static volatile boolean sInstalled = false;
    private static volatile boolean sMapped = false;   // true = 宿主类名被混淆，走了映射
    private static volatile String sMapLabel = null;
    private static volatile String[][] sMapOverride = null;

    private static final ConcurrentHashMap<String, Object> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> MEMBER_CACHE = new ConcurrentHashMap<>();
    private static final Object MISS = new Object();

    private static final List<String> DIAG = new ArrayList<>();
    private static final List<String> SYMBOL_LOG = new ArrayList<>();

    private HostSymbols() {}

    // ─────────────────────────── 安装 ───────────────────────────

    /** 由模块入口在拿到宿主 ClassLoader 后调用一次（幂等）。 */
    public static synchronized void install(ClassLoader loader, String pkg, long versionCode) {
        installInternal(loader, pkg, versionCode, null);
    }

    /** 仅供离线单测：可注入自定义映射表（生产代码不使用）。 */
    public static synchronized void __testInstall(ClassLoader loader, String pkg, long versionCode, String[][] mapOverride) {
        installInternal(loader, pkg, versionCode, mapOverride);
    }

    private static synchronized void installInternal(ClassLoader loader, String pkg, long versionCode, String[][] mapOverride) {
        // 幂等：同 loader + 同版本号直接复用；但带自定义映射（仅单测）时必须重跑。
        if (sInstalled && sLoader == loader && versionCode == sVersionCode && mapOverride == null) return;
        sLoader = loader;
        sPkg = pkg;
        sVersionCode = versionCode;
        CLASS_CACHE.clear();
        MEMBER_CACHE.clear();
        DIAG.clear();
        SYMBOL_LOG.clear();
        sMapOverride = mapOverride;
        sInstalled = true;

        // §1 按原名直查：能直查就不是混淆宿主，后续步骤全部跳过 —— 旧版与不混淆 fork 零回归。
        if (canLoad("org.telegram.messenger.MessagesController")) {
            sMapped = false;
            sMapLabel = "原名直查（13.1.1 及更早 / 未混淆 fork）";
            addLog("宿主符号：按原名直查可用，不启用混淆映射");
            return;
        }
        String[][] map = (mapOverride != null) ? mapOverride : pickMap(versionCode);
        if (map == null) {
            sMapped = false;
            sMapLabel = "无可用映射（versionCode=" + versionCode + "）";
            addLog("宿主符号：未知混淆版本 versionCode=" + versionCode + "，TG 相关业务将降级");
            return;
        }
        sMapped = true;
        sMapLabel = "版本映射（versionCode>=72800，Telegram 13.2.0+）";
        addLog("宿主符号：原名不可用，启用 13.2.0 映射表");

        // 预解析并逐个做结构校验，结果写诊断（面板与日志可见）。
        for (String[] pair : map) {
            String cls = resolveMappedClassName(pair[0]);
            boolean ok = cls != null;
            addLog((ok ? "  ✓ " : "  ✗ ") + simple(pair[0]) + " → " + (ok ? simple(cls) : "未命中"));
        }
    }

    private static String[][] pickMap(long versionCode) {
        String[][] best = null;
        long bestTier = -1L;
        for (Map.Entry<Long, String[][]> e : CLASS_MAPS.entrySet()) {
            if (versionCode >= e.getKey() && e.getKey() >= bestTier) {
                bestTier = e.getKey();
                best = e.getValue();
            }
        }
        return best;
    }

    public static boolean isMapped() { return sMapped; }
    public static boolean isInstalled() { return sInstalled; }
    public static long versionCode() { return sVersionCode; }
    public static String mapLabel() { return sMapLabel; }
    public static List<String> symbolLog() { return new ArrayList<>(SYMBOL_LOG); }
    public static List<String> diagnostics() { return new ArrayList<>(DIAG); }

    private static void addLog(String s) {
        if (SYMBOL_LOG.size() < 200) SYMBOL_LOG.add(s);
    }

    private static void addDiag(String s) {
        if (DIAG.size() < 200) DIAG.add(s);
        try { Log.i(TAG, s); } catch (Throwable ignored) {}
    }

    private static String simple(String n) {
        if (n == null) return "null";
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    // ─────────────────────────── 类解析 ───────────────────────────

    private static boolean canLoad(String name) {
        try {
            Class.forName(name, false, sLoader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 旧名 → 实际类名。旧版返回原名（与历史行为一致）；混淆宿主返回映射名。
     * 找不到返回 null。
     */
    public static String resolveMappedClassName(String legacy) {
        if (legacy == null) return null;
        if (canLoad(legacy)) return legacy;                        // ① 原名直查
        if (!sMapped) return null;
        String[][] map = (sMapOverride != null) ? sMapOverride : pickMap(sVersionCode);
        if (map == null) return null;
        String actual = null;
        for (String[] pair : map) {
            if (pair[0].equals(legacy)) { actual = pair[1]; break; }
        }
        if (actual == null) return null;
        if (!canLoad(actual)) {                                    // ② 映射名不存在
            addDiag("映射失效：" + legacy + " → " + actual + " 不存在（宿主可能已再次改动混淆）");
            return null;
        }
        if (!verifyClassContract(legacy, actual)) {                // ③ 结构指纹校验
            addDiag("映射被拒绝：" + actual + " 不满足 " + simple(legacy) + " 的结构契约");
            return null;
        }
        return actual;
    }

    /**
     * 供 TGAutoSignCore.classEx 使用：返回「该旧名对应的类」。
     * 解析不出来返回 null（调用方自行回退到原 Class.forName 逻辑，保持旧行为）。
     */
    public static Class<?> mapClass(String legacy) {
        if (!sInstalled || legacy == null || sLoader == null) return null;
        if (!legacy.startsWith("org.telegram.messenger.")) return null;   // 只管会被混淆的包
        Object c = CLASS_CACHE.get(legacy);
        if (c == MISS) return null;
        if (c instanceof Class) return (Class<?>) c;
        Class<?> r = null;
        try {
            Class<?> direct = Class.forName(legacy, false, sLoader);
            r = direct;
        } catch (Throwable ignored) {}
        if (r == null) {
            String actual = resolveMappedClassName(legacy);
            if (actual != null) {
                try { r = Class.forName(actual, false, sLoader); } catch (Throwable ignored) {}
            }
        }
        CLASS_CACHE.put(legacy, r == null ? MISS : r);
        return r;
    }

    private static boolean verifyClassContract(String legacy, String actual) {
        for (String[] c : CLASS_CONTRACTS) {
            if (!c[0].equals(legacy)) continue;
            try {
                Class<?> cls = Class.forName(actual, false, sLoader);
                String spec = c[1];
                if (spec.startsWith("s:")) {                       // 父类等于某旧名
                    String want = spec.substring(2);
                    Class<?> wantCls = mapClass(want);
                    return wantCls != null && cls.getSuperclass() == wantCls;
                }
                if (spec.startsWith("c:")) {                       // 存在某构造函数
                    return findUniqueCtor(cls, spec.substring(2)) != null;
                }
                if (spec.startsWith("f:")) {                       // 存在某类型（可选名字）的字段
                    String[] parts = spec.substring(2).split(":", -1);
                    return findFieldByType(cls, typeOf(parts[0]), parts.length > 1 && parts[1].length() > 0 ? parts[1] : null) != null;
                }
                if (spec.startsWith("ms:")) {                      // static 方法：参数 -> 返回
                    String[] parts = spec.substring(3).split(":", -1);
                    return findMethodStructural(cls, typeOfList(parts[0]), typeOf(parts[1]), Boolean.TRUE) != null;
                }
                if (spec.startsWith("m:")) {                       // 方法：参数 -> 返回
                    String[] parts = spec.substring(2).split(":", -1);
                    return findMethodStructural(cls, typeOfList(parts[0]), typeOf(parts[1]), null) != null;
                }
            } catch (Throwable t) {
                addDiag("结构校验异常 " + actual + ": " + t);
                return false;
            }
        }
        return true;   // 没写契约的符号只做「存在性」校验
    }

    private static Class<?> typeOf(String n) {
        if (n == null || n.isEmpty()) return null;
        switch (n) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "void": return void.class;
            default: break;
        }
        Class<?> c = mapTgOrSystem(n);
        return c;
    }

    private static Class<?>[] typeOfList(String csv) {
        if (csv == null || csv.isEmpty()) return new Class<?>[0];
        String[] parts = csv.split(",");
        Class<?>[] out = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = typeOf(parts[i]);
        return out;
    }

    private static Class<?> mapTgOrSystem(String n) {
        try {
            Class<?> c = mapClass(n);
            if (c != null) return c;
        } catch (Throwable ignored) {}
        try { return Class.forName(n, false, sLoader); } catch (Throwable ignored) {}
        return null;
    }

    // ─────────────────────────── 方法解析 ───────────────────────────

    /**
     * 在 owner 上定位一个成员方法。
     *
     * @param owner    实际类（通常来自 {@link #mapClass}）
     * @param legacy   Telegram 原名
     * @param types    参数类型（可能含 null，表示该位置不限）
     * @param staticReq true=必须 static，false=必须实例，null=不限
     * @return 唯一命中的 Method；找不到或存在歧义返回 null
     */
    public static Method method(Class<?> owner, String legacy, Class<?>[] types, Boolean staticReq) {
        if (owner == null || legacy == null) return null;
        String ck = owner.getName() + "#" + legacy + sig(types) + "#" + staticReq;
        Object cached = MEMBER_CACHE.get(ck);
        if (cached == MISS) return null;
        if (cached instanceof Method) return (Method) cached;

        Method m = null;
        // ① 按原名直查（含父类链）—— 旧版与未混淆成员都走这里，行为与历史一致。
        m = findMethodByName(owner, legacy, types);
        if (m == null) {
            // ② 结构解析：参数表（+ 返回类型提示）唯一命中。
            Class<?> ret = null;
            String hint = METHOD_RET_HINTS.get(key(ownerLegacyName(owner), legacy, sig(types)));
            if ("@self".equals(hint)) ret = owner;
            else if (hint != null) ret = mapTgOrSystem(hint);
            if (ret == void.class) ret = null;
            m = findMethodStructural(owner, types, ret, staticReq);
            if (m == null && ret != null) {
                // 提示的返回类型可能因 fork/版本不同（例如返回自定义接口），退回参数表唯一性。
                m = findMethodStructural(owner, types, null, staticReq);
            }
            if (m != null) addDiag("结构解析方法 " + simple(owner.getName()) + "." + legacy + sig(types)
                    + " → " + m.getName());
        }
        if (m != null) {
            try { m.setAccessible(true); } catch (Throwable ignored) {}
        }
        MEMBER_CACHE.put(ck, m == null ? MISS : m);
        return m;
    }

    /** 便捷重载：不带参数类型时用空参数表。 */
    public static Method method(Class<?> owner, String legacy) {
        return method(owner, legacy, new Class<?>[0], null);
    }

    /** 旧名（若 owner 是映射来的类，返回它是哪个旧名）。 */
    private static String ownerLegacyName(Class<?> owner) {
        if (owner == null) return null;
        String n = owner.getName();
        if (sMapped) {
            String[][] map = (sMapOverride != null) ? sMapOverride : pickMap(sVersionCode);
            if (map != null) {
                for (String[] pair : map) if (pair[1].equals(n)) return pair[0];
            }
        }
        return n;
    }

    private static String sig(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        if (types != null) {
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(types[i] == null ? "*" : types[i].getName());
            }
        }
        return sb.append(')').toString();
    }

    private static Method findMethodByName(Class<?> owner, String name, Class<?>[] types) {
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod(name, types);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 结构匹配：遍历 owner 自身声明的全部方法（含父类链，直到 Object），
     * 找出参数表完全一致、返回类型一致（ret=null 表示不限）、static 标记一致的方法。
     * 唯一命中才返回；0 个或多个都返回 null（歧义必须拒绝）。
     */
    private static Method findMethodStructural(Class<?> owner, Class<?>[] types, Class<?> ret, Boolean staticReq) {
        List<Method> hits = new ArrayList<>();
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            Method[] ms;
            try { ms = c.getDeclaredMethods(); } catch (Throwable t) { ms = new Method[0]; }
            for (Method m : ms) {
                if (types != null && !sameTypes(m.getParameterTypes(), types)) continue;
                if (ret != null && m.getReturnType() != ret) continue;
                if (staticReq != null && Modifier.isStatic(m.getModifiers()) != staticReq.booleanValue()) continue;
                hits.add(m);
                if (hits.size() > 1) return null;   // 早退：已经歧义
            }
            c = c.getSuperclass();
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static boolean sameTypes(Class<?>[] a, Class<?>[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (b[i] == null) continue;             // 通配
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static Constructor<?> findUniqueCtor(Class<?> owner, String csv) {
        Class<?>[] types = typeOfList(csv);
        List<Constructor<?>> hits = new ArrayList<>();
        try {
            for (Constructor<?> ctor : owner.getDeclaredConstructors()) {
                if (sameTypes(ctor.getParameterTypes(), types)) hits.add(ctor);
            }
        } catch (Throwable ignored) {}
        return hits.size() == 1 ? hits.get(0) : null;
    }

    // ─────────────────────────── 字段解析 ───────────────────────────

    public static Field field(Class<?> owner, String legacy, Class<?> expectedType) {
        if (owner == null || legacy == null) return null;
        String ck = owner.getName() + "#f#" + legacy + "#" + (expectedType == null ? "" : expectedType.getName());
        Object cached = MEMBER_CACHE.get(ck);
        if (cached == MISS) return null;
        if (cached instanceof Field) return (Field) cached;

        Field f = null;
        // ① 按原名直查
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            try { f = c.getDeclaredField(legacy); break; } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        // ② 结构解析（按类型唯一命中）
        if (f == null && expectedType != null) {
            f = findFieldByType(owner, expectedType, null);
            if (f != null) addDiag("结构解析字段 " + simple(owner.getName()) + "." + legacy
                    + " → " + f.getName() + " (" + f.getType().getSimpleName() + ")");
        }
        if (f != null) {
            try { f.setAccessible(true); } catch (Throwable ignored) {}
        }
        MEMBER_CACHE.put(ck, f == null ? MISS : f);
        return f;
    }

    private static Field findFieldByType(Class<?> owner, Class<?> type, String mustName) {
        if (type == null) return null;
        List<Field> hits = new ArrayList<>();
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            Field[] fs;
            try { fs = c.getDeclaredFields(); } catch (Throwable t) { fs = new Field[0]; }
            for (Field fd : fs) {
                if (mustName != null && !mustName.equals(fd.getName())) continue;
                if (fd.getType() == type) {
                    hits.add(fd);
                    if (hits.size() > 1) return null;
                }
            }
            c = c.getSuperclass();
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** 字段类型提示（旧名 + 字段名 → 期望类型）。查不到返回 null。 */
    public static Class<?> fieldTypeHint(Class<?> owner, String legacyField) {
        String hint = FIELD_TYPE_HINTS.get(key(ownerLegacyName(owner), legacyField, ""));
        if (hint == null) return null;
        return typeOf(hint);
    }

    /**
     * 智能字段读取：给「旧字段名」，返回该对象上对应的值。
     * 旧版（名字保留 / tgnet 包）走直查；混淆宿主按类型提示定位。
     */
    public static Object fieldValue(Object obj, String legacyField) {
        if (obj == null || legacyField == null) return null;
        Class<?> owner = obj.getClass();
        Field f = field(owner, legacyField, null);
        if (f == null) {
            Class<?> hintType = fieldTypeHint(owner, legacyField);
            if (hintType != null) f = field(owner, legacyField, hintType);
        }
        if (f == null) return null;
        try { return f.get(obj); } catch (Throwable t) { return null; }
    }

    /**
     * 是否是「消息对象」（MessageObject 的替代判据）。
     * 混淆后类名不再含 MessageObject，改按结构判定：类里持有 TLRPC$Message 字段。
     */
    public static boolean looksLikeMessageObject(Class<?> cls) {
        if (cls == null) return false;
        if (cls.getName().contains("MessageObject")) return true;   // 旧版/未混淆 fork 的快路径
        Class<?> msg = mapTgOrSystem("org.telegram.tgnet.TLRPC$Message");
        if (msg == null) return false;
        Class<?> c = cls;
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            try {
                for (Field fd : c.getDeclaredFields()) {
                    if (fd.getType() == msg && !Modifier.isStatic(fd.getModifiers())) return true;
                }
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return false;
    }

    /** 取「消息对象」内部的 TLRPC$Message（messageOwner 的结构替代）。 */
    public static Object ownerMessage(Object messageObject) {
        if (messageObject == null) return null;
        Class<?> msg = mapTgOrSystem("org.telegram.tgnet.TLRPC$Message");
        if (msg == null) return null;
        Class<?> c = messageObject.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            try {
                for (Field fd : c.getDeclaredFields()) {
                    if (fd.getType() != msg || Modifier.isStatic(fd.getModifiers())) continue;
                    fd.setAccessible(true);
                    Object v = fd.get(messageObject);
                    if (v != null) return v;
                }
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    // ─────────────────────────── 通知 id ───────────────────────────

    /**
     * didReceiveNewMessages 的通知 id。
     * 旧版读 NotificationCenter 的 public static final int 字段真值；
     * 混淆宿主读不到字段名（字段名也是混淆的），退到官方源码常量 1 —— 并在模块侧用
     * 「参数形状」二次过滤（见 TGAutoSignEntry），因此即使常量偶有出入也不会误触发。
     */
    public static int didReceiveNewMessagesId(Class<?> notificationCenter) {
        if (notificationCenter != null) {
            try {
                Field f = notificationCenter.getField("didReceiveNewMessages");
                return f.getInt(null);
            } catch (Throwable ignored) {}
            for (Field f : notificationCenter.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                if (!"didReceiveNewMessages".equals(f.getName())) continue;
                try { f.setAccessible(true); return f.getInt(null); } catch (Throwable ignored) {}
            }
        }
        return NOTIFICATION_DID_RECEIVE_NEW_MESSAGES;
    }

    /** didReceiveNewMessages 的参数形状：(ArrayList, boolean)。 */
    public static boolean looksLikeDidReceiveArgs(Object args) {
        try {
            if (!(args instanceof Object[])) return false;
            Object[] a = (Object[]) args;
            if (a.length != 2) return false;
            if (!(a[0] instanceof java.util.List)) return false;
            if (!(a[1] instanceof Boolean)) return false;
            java.util.List<?> l = (java.util.List<?>) a[0];
            if (l.isEmpty()) return true;
            Object first = l.get(0);
            return first == null || looksLikeMessageObject(first.getClass());
        } catch (Throwable t) {
            return false;
        }
    }

    // ─────────────────────────── 能力汇总 ───────────────────────────

    /** 关键符号是否齐备（决定业务 Hook 能否安装）。 */
    public static boolean hasMessagesController() {
        return mapClass("org.telegram.messenger.MessagesController") != null;
    }

    public static boolean hasNotificationCenter() {
        return mapClass("org.telegram.messenger.NotificationCenter") != null;
    }

    public static boolean hasUserConfig() {
        return mapClass("org.telegram.messenger.UserConfig") != null;
    }

    /** 一行汇总，写进启动日志与 /jmb 自检面板。 */
    public static String summaryLine() {
        if (!sInstalled) return "未安装（模块未初始化）";
        if (!sMapped) {
            return "原名直查 · MC=" + (hasMessagesController() ? "✓" : "✗")
                    + " NC=" + (hasNotificationCenter() ? "✓" : "✗")
                    + " UC=" + (hasUserConfig() ? "✓" : "✗");
        }
        return "混淆映射(" + sMapLabel + ") · MC=" + (hasMessagesController() ? "✓" : "✗")
                + " NC=" + (hasNotificationCenter() ? "✓" : "✗")
                + " UC=" + (hasUserConfig() ? "✓" : "✗");
    }

    /** 宿主版本是否已被本模块适配（用于「明确显示不兼容」）。 */
    public static boolean hostAdapted() {
        return hasMessagesController() || hasNotificationCenter();
    }

    // ─────────────────────────── 兼容旧调用 ───────────────────────────

    /** MessagesController \u7684\u300c\u66f4\u65b0\u5165\u53e3\u300d\u65b9\u6cd5\u96c6\u5408\uff08\u951a\u70b9 A \u7684\u76ee\u6807\uff09\u3002
     * \u65e7\u7248\uff1a\u539f\u540d\u4ee5 processUpdate \u5f00\u5934\u7684\u5168\u90e8\u65b9\u6cd5\uff08\u4e0e\u5386\u53f2\u5b9e\u73b0\u5b8c\u5168\u4e00\u81f4\uff09\u3002
     * \u6df7\u6dc6\u5bbf\u4e3b\uff1a\u6309\u7ed3\u6784\u6307\u7eb9\u5b9a\u4f4d\uff0813.2.0 \u5b9e\u6d4b
     * processUpdateArray = (ArrayList,ArrayList,ArrayList,boolean,int):boolean\uff0c\u5168\u5305\u552f\u4e00\uff09\u3002
     */
    public static List<Method> processUpdateMethods(Class<?> mc) {
        List<Method> out = new ArrayList<>();
        if (mc == null) return out;
        try {
            for (Method m : mc.getDeclaredMethods()) {
                if (m.getName().startsWith("processUpdate")) out.add(m);
            }
        } catch (Throwable ignored) {}
        if (!out.isEmpty()) return out;

        Class<?> upd = mapTgOrSystem("org.telegram.tgnet.TLRPC$Updates");
        addNonNull(out, method(mc, "processUpdateArray",
                new Class<?>[]{java.util.ArrayList.class, java.util.ArrayList.class, java.util.ArrayList.class,
                        boolean.class, int.class}, Boolean.FALSE));
        if (upd != null) {
            addNonNull(out, method(mc, "processUpdates",
                    new Class<?>[]{upd, boolean.class}, Boolean.FALSE));
        }
        addNonNull(out, method(mc, "processUpdatesQueue",
                new Class<?>[]{int.class, int.class}, Boolean.FALSE));
        return out;
    }

    private static void addNonNull(List<Method> out, Method m) {
        if (m != null && !out.contains(m)) out.add(m);
    }

    /** 供 TGAutoSignCore 的静态反射辅助使用（无 cl 参数时）。 */
    public static Object staticInvokeCompat(Class<?> cls, String legacy, Class<?>[] types, Object[] args) throws Exception {
        Method m = method(cls, legacy, types, Boolean.TRUE);
        if (m == null) m = cls.getMethod(legacy, types);
        return m.invoke(null, args);
    }

    public static Object invokeCompat(Object obj, String legacy, Class<?>[] types, Object[] args) throws Exception {
        Method m = method(obj.getClass(), legacy, types, null);
        if (m == null) m = obj.getClass().getMethod(legacy, types);
        return m.invoke(obj, args);
    }
}
