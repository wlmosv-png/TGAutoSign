package io.github.wlmosv_png.tgautosign;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * TGAutoSign 模块现代入口（API 102）—— 与 jmb界面版/main.java 的 Hook 点 1:1 对齐
 *  - 宿主判定见 Hosts：已知包名白名单 + 标志类能力探测（支持官网 web 版与第三方 fork）
 *  - Toast 注入证明
 *  - hook Application.attach 后初始化业务（用目标 app 的 ClassLoader）
 *  - 安装业务 hook：
 *    ChatActivityEnterView.didPressedBotButton / ChatMessageCellDelegate.didPressBotButton（按钮学习）
 *    ConnectionsManager.sendRequest（/jmb 拦截 + 网络学习/补签触发）
 *    LaunchActivity.onResume（记录宿主 Activity）
 *    ChatActivity.onResume（打开聊天补签）
 *    MessagesController.processUpdate（回复语义判定）
 */
public final class TGAutoSignEntry extends XposedModule {
    private static final String TAG = "TGAutoSignModule";
    /** 实际注入的宿主包名；Play 版 / 官网 web 版 / 第三方 fork 共用同一套 hook */
    private static volatile String HOST_PKG = null;

    private static final AtomicBoolean ATTACH_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean NOTIFIED = new AtomicBoolean(false);
    private static volatile TGAutoSignCore CORE = null;
    private static final Set<String> HOOKED_METHODS = new HashSet<>();
    /** UI 层 bot 按钮 hook 命中总数：0 表示该宿主 UI hook 失效（如 Nagram 12.10.x），
     *  此时捕获/学习只能靠网络层兜底。诊断包会显示，避免每次排障都靠猜。 */
    public static volatile int BTN_HOOK_COUNT = 0;

    private ClassLoader appLoader = null;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        logInfo("TGAutoSign module loaded in process=" + safeProcessName(param));
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        String packageName = param.getPackageName();

        // Nagram XF 30dcd6c 构建存在兼容问题（注入后启动无响应），版本锁定不注入；其他构建正常
        if ("fork.risin42.nagramx".equals(packageName)) {
            String vn = null;
            try {
                java.lang.reflect.Method gai = param.getClass().getMethod("getApplicationInfo");
                Object ai = gai.invoke(param);
                java.lang.reflect.Field vnF = ai != null ? ai.getClass().getField("versionName") : null;
                if (vnF != null) { Object v = vnF.get(ai); vn = v != null ? String.valueOf(v) : null; }
            } catch (Throwable ignored) {}
            if (vn != null && vn.contains("30dcd6c")) {
                logInfo("Nagram XF 30dcd6c 构建已锁定（注入会导致启动无响应），跳过注入");
                return;
            }
        }
        ClassLoader probe = null;
        try { probe = param.getDefaultClassLoader(); } catch (Throwable ignored) {}
        if (!Hosts.isSupported(packageName, probe)) {
            try { logInfo("缺标志类: " + Hosts.missingMarkers(probe) + " —— 该客户端自研了这些层，模块不注入以免误发"); } catch (Throwable ignored) {}
            logInfo("package loaded，宿主不是 Telegram 家族，跳过: " + packageName);
            return;
        }
        HOST_PKG = packageName;
        logInfo("package loaded package=" + packageName + "（" + Hosts.describe(packageName, probe) + "）");

        if (!ATTACH_HOOKED.compareAndSet(false, true)) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof Context) {
                            try {
                                initBusiness((Context) arg, packageName);
                            } catch (Throwable t) {
                                logError("initBusiness failed", t);
                            }
                        }
                        return result;
                    });
            logInfo("hooked Application.attach for " + packageName);
        } catch (Throwable t) {
            logError("hook Application.attach failed", t);
        }
    }

    private void initBusiness(Context context, String packageName) {
        Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (appLoader == null) appLoader = context.getClassLoader();

        // ── 宿主符号解析（2026-11-08）──────────────────────────────
        // Telegram 13.2.0 起 org.telegram.messenger.* 类名被 R8 改写；
        // 这里把「旧名 → 实际类/方法/字段」的翻译装好，之后所有业务反射都经它。
        long vc = 0L;
        try {
            android.content.pm.PackageInfo pi = appContext.getPackageManager().getPackageInfo(packageName, 0);
            vc = pi.getLongVersionCode();
        } catch (Throwable ignored) {}
        HostSymbols.install(appLoader, packageName, vc);
        logInfo("宿主符号解析：" + HostSymbols.summaryLine());
        for (String _sym : HostSymbols.symbolLog()) logInfo(_sym);

        if (CORE == null) {
            synchronized (TGAutoSignEntry.class) {
                if (CORE == null) {
                    CORE = new TGAutoSignCore(appContext, context.getClassLoader());
                }
            }
        }
        CORE.start();
        installBusinessHooks();
        // 2026-11-08（1.7.6 修）：观察链路健康标记必须在**装完 Hook 之后**判定。
        // 1.7.5 把它放在 CORE.start() 之后，那时 anchorA/BHooked 还是 false →
        //   标记永远不置位 → 所有待处理目标都被显示成"链路不健康"（真机截图实测）。
        // 判据：任一锚点已挂上即视为链路可用（Hook 挂上 = 事件会到达）。
        try {
            if (CORE.anchorAHooked || CORE.anchorBHooked || CORE.anchorCHooked) {
                CORE.markChainHealthyNow();
            }
        } catch (Throwable ignored) {}
        notifyInjected(appContext, packageName);
    }

    // ---------------- 业务 hook（与 jmb界面版 插件一致） ----------------
    private void installBusinessHooks() {
        hookBotButtonEnterView();
        hookBotButtonCell();
        hookSendRequest();
        hookChatActivityOnResume();
        hookProcessUpdate();
        hookDidReceiveNewMessages();
        hookPostNotification();
        hookLaunchActivity();
        hookNagramProxyRotationFix();
    }

    /**
     * Nagram 兼容补丁：ProxyRotationController.initInternal() 会遍历
     * SharedConfig.activeAccounts，但 Nagram 的启动顺序里该字段可能还是 null，
     * 导致 ApplicationLoader.onCreate 直接 NPE 崩溃（不是本模块引入，是 Nagram 自身缺陷）。
     * 这里在 initInternal 进入前，若 activeAccounts 为 null 就先填一个空集合，避免崩溃。
     * 只在 Nagram 上生效；其它宿主类不存在时静默跳过。
     */
    private void hookNagramProxyRotationFix() {
        try {
            Class<?> prc = loadClass("org.telegram.messenger.ProxyRotationController");
            Class<?> sc = loadClass("org.telegram.messenger.SharedConfig");
            final java.lang.reflect.Field fActive;
            try {
                fActive = sc.getDeclaredField("activeAccounts");
                fActive.setAccessible(true);
            } catch (Throwable t) { return; }   // 字段不存在（官方版等）→ 跳过
            Method target = null;
            for (Method m : prc.getDeclaredMethods()) {
                if ("initInternal".equals(m.getName()) && m.getParameterTypes().length == 0) { target = m; break; }
            }
            if (target == null) return;
            target.setAccessible(true);
            hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object cur = fActive.get(null);
                            if (cur == null) {
                                fActive.set(null, new java.util.concurrent.CopyOnWriteArraySet<Object>());
                                logInfo("[兼容] Nagram activeAccounts 为空，已补空集合防崩溃");
                            }
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
            logInfo("[兼容] 已挂 Nagram ProxyRotationController.initInternal 防崩溃补丁");
        } catch (Throwable ignored) {}
    }

    // 触发源 1：ChatActivityEnterView.didPressedBotButton（UI 按钮学习）
    // 官方版 TG 用 R8 混淆，方法名变成单字母（如 g）；这里改为「结构匹配」：
    //   在 ChatActivityEnterView 里找「第1参是 ChatActivityEnterView、第2参类型含 KeyboardButton、返回 void」的方法。
    private void hookBotButtonEnterView() {
        // 扫描多个候选类：按钮点击可能落在 EnterView、ChatActivity 或它们的内部类里。
        // 判定只看「参数类型名含 KeyboardButton」（TL 类型名不被混淆），跨客户端通用。
        String[] classNames = {
                "org.telegram.ui.Components.ChatActivityEnterView",
                "org.telegram.ui.ChatActivity$ChatMessageCellDelegate",
                "org.telegram.ui.ChatActivity",
        };
        for (String cn : classNames) {
            try { hookBotButtonInClass(loadClass(cn)); } catch (Throwable ignored) {}
        }
    }

    private void hookBotButtonInClass(Class<?> cls) {
        try {
            Method[] ms = cls.getDeclaredMethods();
            int hooked = 0;
            for (Method m : ms) {
                boolean isNamed = "didPressedBotButton".equals(m.getName());
                boolean isStructural = !isNamed && looksLikeBotButtonMethod(m);
                if (!isNamed && !isStructural) continue;
                String key = "enterView#botbtn#" + m.toGenericString();
                synchronized (HOOKED_METHODS) {
                    if (HOOKED_METHODS.contains(key)) continue;
                    HOOKED_METHODS.add(key);
                }
                try {
                    m.setAccessible(true);
                    final boolean structural = isStructural;
                    final int argc = m.getParameterTypes().length;
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    Object[] args = chain.getArgs().toArray();
                                    Object button = pickButtonArg(args);
                                    Object mo = pickMessageObjectArg(args);
                                    if (button == null && isNamed) {
                                        // 按名匹配的老形态：args[0]=按钮, args[2]=MessageObject
                                        button = args != null && args.length > 0 ? args[0] : null;
                                        mo = args != null && args.length > 2 ? args[2] : null;
                                    }
                                    if (button != null) {
                                        CORE.onBotButtonEnterView(button, mo);
                                        logInfo("[按钮探测] 命中 " + chain.getExecutable().toGenericString());
                                    }
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                    hooked++;
                    logInfo("hooked botButton(" + (structural ? "结构匹配:" + m.getName() : "按名") + " argc=" + argc + ") " + m.toGenericString());
                } catch (Throwable ignored) {}
            }
            if (hooked > 0) {
                BTN_HOOK_COUNT += hooked;
                logInfo("hooked botButton in " + cls.getName() + "（匹配 " + hooked + " 个方法）");
            } else {
                logInfo("botButton 在 " + cls.getName() + " 未命中任何方法（该宿主可能走网络层兜底）");
            }
        } catch (Throwable t) {
            logError("hook botButton in " + cls.getName() + " failed", t);
        }
    }

    /**
     * 通用判定：只要方法参数里「任意一个」类型名含 KeyboardButton（TL 类型名不被混淆，
     * 可信赖），就认它是按钮点击候选。不限制静态/实例、不限制参数个数、不看方法名。
     * 这样能覆盖各客户端 R8 混淆后的任意形态（g / h / f / didPressedBotButton ...）。
     */
    /**
     * 类型名是否是按钮类型。
     *
     * 2026-10-04 修（关键）：旧判据 `contains("KeyboardButton")` **大小写敏感**。
     *   声明类型是抽象父类 `TL_keyboard$KeyboardButton`（含大写 KeyboardButton），
     *   但运行时实例是 `TL_keyboard$TL_keyboardButtonCallback_layer228`
     *   （`keyboardButton`，小写 k）—— 于是 hook 装得上（看声明类型），
     *   用户真点按钮时却取不到按钮对象（看实例类型）→ 直接不学习。
     *   这就是「按钮学习有时能、有时不能」的机制来源。
     *   现在：不分大小写，并沿父类/接口链再找一层。
     */
    private static boolean isKeyboardButtonType(Class<?> c) {
        if (c == null) return false;
        try {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                if (containsIgnoreCase(k.getName(), "keyboardbutton")) return true;
                Class<?>[] ifs = k.getInterfaces();
                if (ifs != null) {
                    for (Class<?> i : ifs) {
                        if (i != null && containsIgnoreCase(i.getName(), "keyboardbutton")) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean containsIgnoreCase(String s, String sub) {
        try { return s != null && s.toLowerCase(java.util.Locale.US).contains(sub); }
        catch (Throwable t) { return false; }
    }

    /** hook 安装期：参数里任意一个是按钮类型即认定为按钮点击候选（可跨客户端兼容混淆名）。 */
    private static boolean looksLikeBotButtonMethod(Method m) {
        try {
            Class<?>[] ps = m.getParameterTypes();
            if (ps == null) return false;
            for (Class<?> p : ps) {
                if (isKeyboardButtonType(p)) return true;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    /** 从一个参数数组里挑出「按钮对象」（类型名含 KeyboardButton 的那个）。 */
    private static Object pickButtonArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            if (isKeyboardButtonType(a.getClass())) return a;
        }
        return null;
    }

    /** 从参数数组里挑出 MessageObject（类型名含 MessageObject 的那个，用于取 dialogId）。 */
    private static Object pickMessageObjectArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            // 2026-11-08：混淆宿主里类名不再含 "MessageObject"，改按结构判定
            // （类里持有 TLRPC$Message 字段 = 消息对象）。
            if (HostSymbols.looksLikeMessageObject(a.getClass())) return a;
        }
        return null;
    }

    // 触发源 1b：气泡内按钮点击（UI 按钮学习）
    //
    // 2026-10-04 修：旧实现只按名字 "didPressBotButton" 精确匹配，且只认一个类
    //   org.telegram.ui.ChatActivity$ChatMessageCellDelegate。
    //   实测官方 12.10.6（21570 类）里：
    //     · org.telegram.ui.ChatActivity            —— 不存在（被 R8 改名，实际是 org.telegram.ui.vn）
    //     · ChatActivity$ChatMessageCellDelegate    —— 不存在
    //     · 字符串 "didPressBotButton"              —— 0 命中
    //   于是这整条触发源在官方版/新 Nagram 上完全失效（LSPosed 日志反复打 hook failed）。
    //   现在改为：多候选类 + **结构匹配**（参数含按钮类型即认），与 EnterView 同一套判据。
    private void hookBotButtonCell() {
        String[] classNames = {
                "org.telegram.ui.ChatActivity$ChatMessageCellDelegate",
                "org.telegram.ui.Cells.n1",           // 官方 12.10.6 气泡按钮回调接口（vn 实现它）
        };
        int total = 0;
        for (String cn : classNames) {
            total += hookCellCandidate(cn);
        }
        if (total > 0) logInfo("hooked ChatMessageCellDelegate.didPressBotButton（匹配 " + total + " 个方法）");
        else logInfo("气泡按钮回调类未命中（该宿主走 EnterView / 网络层兜底，不视为错误）");
    }

    /** 尝试挂一个候选类；返回挂上的方法数（类不存在或无可挂方法返回 0，不报错）。 */
    private int hookCellCandidate(String cn) {
        try {
            Class<?> cls = loadClass(cn);
            if (cls == null) return 0;
            Method[] ms = cls.getDeclaredMethods();
            int hooked = 0;
            for (Method m : ms) {
                boolean isNamed = "didPressBotButton".equals(m.getName());
                if (!isNamed && !looksLikeBotButtonMethod(m)) continue;
                String key = "cellDelegate#" + m.toGenericString();
                synchronized (HOOKED_METHODS) {
                    if (HOOKED_METHODS.contains(key)) continue;
                    HOOKED_METHODS.add(key);
                }
                try {
                    m.setAccessible(true);
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    Object[] args = chain.getArgs().toArray();
                                    Object cell = args != null && args.length > 0 ? args[0] : null;
                                    Object proto = args != null && args.length > 1 ? args[1] : null;
                                    CORE.onBotButtonCell(cell, proto);
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            if (hooked > 0) logInfo("hooked 气泡按钮回调 " + cls.getName() + "（匹配 " + hooked + " 个方法）");
            try {
                // 2026-11-12：hooked==0 是「该类存在但没有可挂方法」——各客户端混淆名不同，
                //   这是**常态**，不是挂钩失败。旧写法无条件调 hookInstall(ok=false)，
                //   于是每天固定刷 8 条 `UI_cell:n1 FAIL(该类无可挂方法)` 的假失败，
                //   把真正的挂钩失败淹掉了（用户会以为模块挂了）。
                //   现在：只有真挂上才记 hookInstall；没方法可挂只记一条目标解析记录。
                if (hooked > 0) {
                    Diag.hookTarget("UI_cell:" + cls.getSimpleName(), cn, true, true, hooked, "-");
                    Diag.hookInstall("UI_cell:" + cls.getSimpleName(), cn, true, null);
                }
            } catch (Throwable ignored) {}
            return hooked;
        } catch (Throwable t) {
            // 类不存在是常态（各客户端混淆名不同），降为 info 不再刷 error
            logInfo("气泡按钮回调类 " + cn + " 不可用（" + t.getClass().getSimpleName() + "），跳过");
            return 0;
        }
    }

    // 触发源 2：ConnectionsManager.sendRequest（网络学习/补签/命令拦截）
    private void hookSendRequest() {
        try {
            Class<?> cm = loadClass("org.telegram.tgnet.ConnectionsManager");
            Method[] ms = cm.getDeclaredMethods();
            int hooked = 0;
            for (Method m : ms) {
                if (!"sendRequest".equals(m.getName())) continue;
                String key = "sendRequest#" + m.toGenericString();
                synchronized (HOOKED_METHODS) {
                    if (HOOKED_METHODS.contains(key)) continue;
                    HOOKED_METHODS.add(key);
                }
                try {
                    m.setAccessible(true);
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    try { Diag.hookFired("C"); } catch (Throwable ignored) {}
                            if (CORE.onSendRequest(chain.getArgs().toArray())) {
                                        return null; // /jmb 管理命令：拦截不发送
                                    }
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            CORE.anchorCHooked = true;
            logInfo("hooked ConnectionsManager.sendRequest（匹配 " + hooked + " 个方法）");
            if (hooked == 0) logError("未找到 " + "ConnectionsManager.sendRequest" + "，该触发源在此宿主上无效", null);
            try {
                Diag.hookTarget("C", "ConnectionsManager.sendRequest", true, hooked > 0, hooked,
                        hooked > 0 ? "sendRequest" : "-");
                Diag.hookInstall("C", "ConnectionsManager.sendRequest", hooked > 0,
                        hooked > 0 ? null : "未找到 sendRequest");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logError("hook sendRequest failed", t);
        }
    }

    // 触发源 3.1：打开聊天补签
    private void hookChatActivityOnResume() {
        try {
            Class<?> ca = loadClass("org.telegram.ui.ChatActivity");
            Method onResume = findMethod(ca, "onResume");
            if (onResume == null) {
                logError("ChatActivity.onResume not found", null);
                return;
            }
            onResume.setAccessible(true);
            String key = "onResume#" + onResume.toGenericString();
            synchronized (HOOKED_METHODS) {
                if (HOOKED_METHODS.contains(key)) return;
                HOOKED_METHODS.add(key);
            }
            hook(onResume)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            CORE.enqueueTry("打开聊天");
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
            logInfo("hooked ChatActivity.onResume");
            try { Diag.hookInstall("UI_chat", "org.telegram.ui.ChatActivity.onResume", true, null); }
            catch (Throwable ignored) {}
        } catch (Throwable t) {
            logError("hook ChatActivity.onResume failed", t);
        }
    }

    // 触发源 3.5：bot 回复语义判定
    //
    // 2026-11-08 适配 Telegram 13.2.0：
    //   旧实现靠「方法名前缀 processUpdate」匹配。13.2.0 起 org.telegram.messenger.* 的
    //   方法名也被 R8 改写（实测 processUpdateArray → lp.K3），前缀匹配会 0 命中。
    //   现在改为：先按原名前缀（旧版路径，逐字不变），0 命中时按**结构指纹**取目标。
    //   解析不到就明确报「宿主未适配」并停装锚点 A —— 绝不假装已挂上。
    private void hookProcessUpdate() {
        Class<?> mc;
        try {
            mc = loadClass("org.telegram.messenger.MessagesController");
        } catch (Throwable t) {
            logError("宿主未适配：MessagesController 无法解析 → 锚点A(协议层)不安装", t);
            return;
        }
        try {
            java.util.List<Method> targets = HostSymbols.processUpdateMethods(mc);
            int hooked = 0;
            for (Method m : targets) {
                // 2026-11-08 F9：每个挂载点独立 id / 独立安装结果 / 独立触发计数。
                // 为什么要拆：一个总状态无法区分「三个都装上」与「只装上其中一个」，
                //   而「频道有、私聊没有」恰恰要靠这个区分。
                final String hid = probeHookId(m);
                final String hlabel = probeHookLabel(m);
                final int hparams = m.getParameterTypes().length;
                String key = "processUpdate#" + m.toGenericString();
                synchronized (HOOKED_METHODS) {
                    if (HOOKED_METHODS.contains(key)) continue;
                    HOOKED_METHODS.add(key);
                }
                boolean ok = false;
                String fail = null;
                try {
                    m.setAccessible(true);
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try { Diag.hookFired(hid); } catch (Throwable ignored) {}
                                try {
                                    // F9 探针：先记参数面（只统计，不参与判定）
                                    Object[] _as = new Object[hparams];
                                    for (int _i = 0; _i < hparams; _i++) {
                                        try { _as[_i] = chain.getArg(_i); } catch (Throwable ignored) {}
                                    }
                                    Diag.probeCall(hid, _as);
                                } catch (Throwable ignored) {}
                                try {
                                    Object update = chain.getArg(0);
                                    // 把 MessagesController 实例一起传下去：它自己带 currentAccount
                                    // （BaseController 字段），判定回复时才能定位到正确的账号。
                                    Object ctrl = null;
                                    try { ctrl = chain.getThisObject(); } catch (Throwable ignored) {}
                                    if (update != null) CORE.onUpdateProcessed(update, ctrl);
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                    ok = true;
                    hooked++;
                } catch (Throwable t) {
                    fail = t.getClass().getSimpleName();
                }
                try { Diag.hookTarget(hid, hlabel, true, true, hparams, m.getName()); } catch (Throwable ignored) {}
                try { Diag.hookInstall(hid, hlabel, ok, fail); } catch (Throwable ignored) {}
            }
            logInfo("hooked MessagesController.processUpdate*（匹配 " + hooked + " 个方法）");
            if (hooked > 0) CORE.anchorAHooked = true;
            if (hooked == 0) logError("未找到 processUpdate* 的对应方法 → 锚点A在此宿主上无效", null);
            // 2026-11-08 F0：锚点 A 的解析/安装结果（未安装不得被描述为"已覆盖入口"）
            try {
                StringBuilder _ms = new StringBuilder();
                for (Method _m : targets) { if (_ms.length() > 0) _ms.append(','); _ms.append(_m.getName()); }
                Diag.hookTarget("A", "MessagesController.processUpdate*", true, !targets.isEmpty(),
                        targets.size(), _ms.toString());
                Diag.hookInstall("A", "MessagesController.processUpdate*", hooked > 0,
                        hooked > 0 ? null : "未找到 processUpdate* 的对应方法");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logError("hook processUpdate failed", t);
        }
    }

    /**
     * F9：按参数表给锚点 A 的每个挂载点一个稳定 id —— 不再用单一总状态代替三个结果。
     * 纯结构判定（不依赖混淆名）：
     *   A1 = processUpdateArray(ArrayList, ArrayList, ArrayList, boolean, int)  ← 唯一 5 参
     *   A2 = processUpdates(TLRPC$Updates, boolean)                             ← 2 参且第 2 参 boolean
     *   A3 = processUpdatesQueue(int, int)                                      ← 2 参全 int
     */
    private static String probeHookId(Method m) {
        try {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 5) return "A1";
            if (ps.length == 2 && ps[1] == boolean.class) return "A2";
            if (ps.length == 2 && ps[0] == int.class && ps[1] == int.class) return "A3";
            return "A9";
        } catch (Throwable t) {
            return "A9";
        }
    }

    private static String probeHookLabel(Method m) {
        String id = probeHookId(m);
        if ("A1".equals(id)) return "processUpdateArray";
        if ("A2".equals(id)) return "processUpdates";
        if ("A3".equals(id)) return "processUpdatesQueue";
        return "processUpdate?";
    }

    // 触发源 3.6（锚点 B）：NotificationCenter.didReceiveNewMessages（实现端）
    //
    // 为什么要有它：锚点 A（processUpdate*）是协议层入口，锚点 B 是「消息已入库」的事件层。
    // 两者互不依赖 —— TG 换 fork 或大版本重构时，不可能同时消失（消失了自己就崩）。
    //
    // 2026-11-08：官方版向来没有 MessagesController.didReceivedNotification 这个名字
    //   （13.1.1 实测也是 0 命中），所以实现端在官方版一直是「记一行 info 就返回」。
    //   这里保持该语义不变，只把类/方法解析换成符号层；混淆宿主读不到 id 字段时退到
    //   官方源码常量（1）并在发送端用「参数形状」二次过滤，见 hookPostNotification。
    private void hookDidReceiveNewMessages() {
        Class<?> ncCls;
        try {
            ncCls = loadClass("org.telegram.messenger.NotificationCenter");
        } catch (Throwable t) {
            logError("宿主未适配：NotificationCenter 无法解析 → 锚点B(事件层)不安装", t);
            try {
                Diag.hookTarget("B_impl", "MessagesController.didReceivedNotification", false, false, 0, "-");
                Diag.hookInstall("B_impl", "MessagesController.didReceivedNotification", false, String.valueOf(t));
            } catch (Throwable ignored) {}
            return;
        }
        Class<?> mc;
        try {
            mc = loadClass("org.telegram.messenger.MessagesController");
        } catch (Throwable t) {
            logInfo("MessagesController 不可用，锚点B实现端跳过（发送端仍会尝试）");
            return;
        }
        try {
            final int ID_DID_RECEIVE = HostSymbols.didReceiveNewMessagesId(ncCls);
            Method m = HostSymbols.method(mc, "didReceivedNotification",
                    new Class<?>[]{int.class, int.class, Object[].class}, Boolean.FALSE);
            if (m == null) {
                logInfo("该宿主没有 MessagesController.didReceivedNotification（官方版向来如此），锚点B走发送端");
                // 2026-11-08 F0：**必须显式记为 HOOK_NOT_INSTALLED**，不能表现成"这条链恰好零消息"
                try {
                    Diag.hookTarget("B_impl", "MessagesController.didReceivedNotification", true, false, 0, "-");
                    Diag.hookInstall("B_impl", "MessagesController.didReceivedNotification", false,
                            "该宿主无此方法（官方版向来如此）");
                } catch (Throwable ignored) {}
                return;
            }
            m.setAccessible(true);
            String key = "didReceiveNewMessages#" + m.toGenericString();
            synchronized (HOOKED_METHODS) {
                if (HOOKED_METHODS.contains(key)) return;
                HOOKED_METHODS.add(key);
            }
            hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            int id = ((Number) chain.getArg(0)).intValue();
                            Object argsObj = chain.getArg(2);
                            // 只关心「新消息到达」这一条通知；其余立刻放行，开销可忽略。
                            // 混淆宿主上 id 字段名不可读时，用参数形状兜底（(ArrayList, boolean)）。
                            boolean hit = (id == ID_DID_RECEIVE)
                                    || (HostSymbols.isMapped() && HostSymbols.looksLikeDidReceiveArgs(argsObj));
                            if (hit) {
                                Object account = chain.getArg(1);
                                Object ctrl = null;
                                try { ctrl = chain.getThisObject(); } catch (Throwable ignored) {}
                                int acc = CORE.accountOfController(ctrl);
                                int accFinal = acc >= 0 ? acc
                                        : (account instanceof Number ? ((Number) account).intValue() : -1);
                                if (argsObj instanceof Object[]) {
                                    try { CORE.onDidReceiveNewMessages(accFinal, (Object[]) argsObj); }
                                    catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
            CORE.anchorBHooked = true;
            logInfo("hooked MessagesController.didReceivedNotification（锚点 B，id=" + ID_DID_RECEIVE + "）");
        } catch (Throwable t) {
            logError("hook didReceiveNewMessages failed", t);
        }
    }

    // 触发源 3.7（锚点 B 发送端）：NotificationCenter.postNotificationName
    //
    // 与原「实现端」钩子并存：实现端依赖「谁注册了 id=1」，发送端不依赖任何注册关系。
    // 两者都成功时按 (peer, msgId) 天然去重（同一条消息判定两次结果一致、幂等）。
    //
    // 2026-11-08：混淆宿主上 NotificationCenter 的字段名也不可读，didReceiveNewMessages 的
    //   id 取值退到官方源码常量（totalEvents 从 1 起、didReceiveNewMessages 是第一个使用它的
    //   字段 → 恒为 1）。为了「常量万一变了也不漏事件」，额外允许**参数形状**命中：
    //   仅当宿主是混淆形态时启用，且要求 (ArrayList, boolean) 且首元素是消息对象。
    private void hookPostNotification() {
        Class<?> ncCls;
        try {
            ncCls = loadClass("org.telegram.messenger.NotificationCenter");
        } catch (Throwable t) {
            logError("宿主未适配：NotificationCenter 无法解析 → 锚点B发送端不安装", t);
            try {
                Diag.hookTarget("B_send", "NotificationCenter", false, false, 0, "-");
                Diag.hookInstall("B_send", "NotificationCenter", false, String.valueOf(t));
            } catch (Throwable ignored) {}
            return;
        }
        try {
            final int ID_DID_RECEIVE = HostSymbols.didReceiveNewMessagesId(ncCls);
            // 2026-11-08：13.2.1 的 NotificationCenter(ys) 里有 **两个** (int,Object[])->void
            //   （ys.k 真正分发到观察者、ys.m 是它的包装），纯签名分不开。
            //   策略：成员提示给出首选 k，同时把全部同签名候选都挂上 ——
            //   两者都是「发通知」入口，重复投递由既有 (peer,msgId) 去重吸收。
            java.util.List<Method> postMethods = HostSymbols.methods(ncCls, "postNotificationName",
                    new Class<?>[]{int.class, Object[].class}, void.class, Boolean.FALSE);
            if (postMethods.isEmpty()) {
                // 最后兜底：按参数表取全部候选（不看返回类型）
                postMethods.addAll(HostSymbols.methods(ncCls, "postNotificationName",
                        new Class<?>[]{int.class, Object[].class}, null, Boolean.FALSE));
            }
            if (postMethods.isEmpty()) {
                logError("NotificationCenter.postNotificationName(int,Object[]) 解析失败 → 锚点B发送端无效", null);
                try {
                    Diag.hookTarget("B_send", "NotificationCenter.postNotificationName", true, false, 0, "-");
                    Diag.hookInstall("B_send", "NotificationCenter.postNotificationName", false,
                            "int,Object[] 签名未找到");
                } catch (Throwable ignored) {}
                return;
            }
            int hookedPost = 0;
            for (Method m : postMethods) {
                try {
                    m.setAccessible(true);
                    String key = "postNotificationName#" + m.toGenericString();
                    synchronized (HOOKED_METHODS) {
                        if (HOOKED_METHODS.contains(key)) continue;
                        HOOKED_METHODS.add(key);
                    }
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                try {
                                    Object a0 = chain.getArg(0);
                                    Object a1 = chain.getArg(1);
                                    int id = (a0 instanceof Number) ? ((Number) a0).intValue() : Integer.MIN_VALUE;
                                    boolean hit = (id == ID_DID_RECEIVE)
                                            || (HostSymbols.isMapped() && HostSymbols.looksLikeDidReceiveArgs(a1));
                                    if (hit && a1 instanceof Object[]) {
                                        try { CORE.onPostNotification(ID_DID_RECEIVE, (Object[]) a1); }
                                        catch (Throwable ignored) {}
                                    }
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                    hookedPost++;
                } catch (Throwable ignored) {}
            }
            logInfo("hooked NotificationCenter.postNotificationName×" + hookedPost
                    + "（锚点 B 发送端，id=" + ID_DID_RECEIVE + "）");
            // 2026-11-08 F0
            try {
                StringBuilder _ms = new StringBuilder();
                for (Method _m : postMethods) { if (_ms.length() > 0) _ms.append(','); _ms.append(_m.getName()); }
                Diag.hookTarget("B_send", "NotificationCenter.postNotificationName", true,
                        !postMethods.isEmpty(), postMethods.size(), _ms.toString());
                Diag.hookInstall("B_send", "NotificationCenter.postNotificationName", hookedPost > 0,
                        hookedPost > 0 ? null : "所有候选方法安装均失败");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logError("hook postNotificationName failed", t);
        }
    }

    // 触发源 3：LaunchActivity 记录对话框宿主
    private void hookLaunchActivity() {
        try {
            Class<?> la = loadClass("org.telegram.ui.LaunchActivity");
            Method onResume = findMethod(la, "onResume");
            if (onResume == null) {
                logError("LaunchActivity.onResume not found", null);
                return;
            }
            onResume.setAccessible(true);
            synchronized (HOOKED_METHODS) {
                if (HOOKED_METHODS.contains("LaunchActivityOnResume")) return;
                HOOKED_METHODS.add("LaunchActivityOnResume");
            }
            hook(onResume)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object thiz = chain.getThisObject();
                            if (thiz instanceof Activity) {
                                CORE.setHostActivity((Activity) thiz);
                            }
                        } catch (Throwable ignored) {}
                        // ── 打开 TG = 一次即时巡检（2026-10-04）──
                        // 为什么放这里：进程重启或被冻结恢复后，
                        //   模块唯一入口是启动 10 秒后的 timerHook("启动")；
                        //   若那一刻在窗口外，就得等下一次心跳
                        //   （窗口外 15 分钟、窗口内 45 秒）。
                        //   而“用户打开 TG”本身就是最可靠的信号 ——
                        //   他在用手机、网络一定通。
                        // 安全性：enqueueTry 自带 90 秒去重 + 窗口内随机延迟，
                        //   且定时模式下会自动转交时刻表调度 —— 不会窗口外签到。
                        try { CORE.enqueueTry("打开TG"); } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
            logInfo("hooked LaunchActivity.onResume (jmb host)");
            try { Diag.hookInstall("UI_launch", "org.telegram.ui.LaunchActivity.onResume", true, null); }
            catch (Throwable ignored) {}
        } catch (Throwable t) {
            logError("hook LaunchActivity failed", t);
        }
    }


    // 沿类/父类链查找方法（处理 protected 方法，如 onResume）
    private static Method findMethod(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name);
                return m;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    // 带参数类型的重载：按签名精确定位（同名重载多时用）。
    // 找不到返回 null，不抛 —— 调用方自己决定「缺失是否致命」。
    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                return m;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private Class<?> loadClass(String name) throws ClassNotFoundException {
        // 2026-11-08：混淆宿主（Telegram 13.2.0+）先经符号层翻译；
        // 旧版与不混淆 fork 在 mapClass 里直接命中原名，行为与历史逐字一致。
        Class<?> mapped = HostSymbols.mapClass(name);
        if (mapped != null) return mapped;
        return Class.forName(name, false, appLoader);
    }

    // ---------------- API 102 热加载 ----------------
    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        logInfo("hot reloading requested");
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        logInfo("hot reload complete, restoring hooks");
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
        HOOKED_METHODS.clear();
        ATTACH_HOOKED.set(false);
        NOTIFIED.set(false);
        // 旧实例必须显式停掉：它的心跳/轮询是自递归 postDelayed，光丢引用不会停，
        // 否则热重载后两个 Core 并行跑（重复签到 + 日志交错）。
        try { if (CORE != null) CORE.stop(); } catch (Throwable t) { logError("stop old core failed", t); }
        CORE = null;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof Context) {
                            try { if (HOST_PKG != null) initBusiness((Context) arg, HOST_PKG); } catch (Throwable t) {}
                        }
                        return result;
                    });
            ATTACH_HOOKED.set(true);
        } catch (Throwable t) {
            logError("re-install attach hook failed", t);
        }
    }

    // ---------------- Toast 注入证明 ----------------
    private void notifyInjected(Context context, String loadedPackageName) {
        if (!NOTIFIED.compareAndSet(false, true)) return;
        Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        String message = "TGAutoSign 注入成功: " + loadedPackageName;
        logInfo(message);
        // v1.4.1: 冷启注入不再弹 Toast；证明注入请看 logcat 或 /jmb -> 自诊断
    }

    @SuppressWarnings("unused")
    private void showToast(Context context, String message) {
        String display = message.length() > 200 ? message.substring(0, 200) + "..." : message;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(context, display, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                logError("failed to show toast", t);
            }
        });
    }

    private void logInfo(String message) {
        Log.i(TAG, message);
        try { log(Log.INFO, TAG, message); } catch (Throwable ignored) {}
    }

    private void logError(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        try { log(Log.ERROR, TAG, message, throwable); } catch (Throwable ignored) {}
    }

    private static String safeProcessName(XposedModuleInterface.ModuleLoadedParam param) {
        try { return param.getProcessName(); } catch (Throwable ignored) { return "unknown"; }
    }
}
