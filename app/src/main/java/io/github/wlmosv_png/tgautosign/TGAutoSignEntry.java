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

        if (CORE == null) {
            synchronized (TGAutoSignEntry.class) {
                if (CORE == null) {
                    CORE = new TGAutoSignCore(appContext, context.getClassLoader());
                }
            }
        }
        CORE.start();
        installBusinessHooks();
        notifyInjected(appContext, packageName);
    }

    // ---------------- 业务 hook（与 jmb界面版 插件一致） ----------------
    private void installBusinessHooks() {
        hookBotButtonEnterView();
        hookBotButtonCell();
        hookSendRequest();
        hookChatActivityOnResume();
        hookProcessUpdate();
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
    private static boolean looksLikeBotButtonMethod(Method m) {
        try {
            Class<?>[] ps = m.getParameterTypes();
            if (ps == null) return false;
            for (Class<?> p : ps) {
                String n = p.getName();
                if (n != null && n.contains("KeyboardButton")) return true;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    /** 从一个参数数组里挑出「按钮对象」（类型名含 KeyboardButton 的那个）。 */
    private static Object pickButtonArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            String n = a.getClass().getName();
            if (n != null && n.contains("KeyboardButton")) return a;
        }
        return null;
    }

    /** 从参数数组里挑出 MessageObject（类型名含 MessageObject 的那个，用于取 dialogId）。 */
    private static Object pickMessageObjectArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            String n = a.getClass().getName();
            if (n != null && n.contains("MessageObject")) return a;
        }
        return null;
    }

    // 触发源 1b：ChatActivity$ChatMessageCellDelegate.didPressBotButton（UI 按钮学习）
    private void hookBotButtonCell() {
        try {
            Class<?> cls = loadClass("org.telegram.ui.ChatActivity$ChatMessageCellDelegate");
            Method[] ms = cls.getDeclaredMethods();
            int hooked = 0;
            for (Method m : ms) {
                if (!"didPressBotButton".equals(m.getName())) continue;
                String key = "cellDelegate#didPressBotButton#" + m.toGenericString();
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
            logInfo("hooked ChatActivity$ChatMessageCellDelegate.didPressBotButton（匹配 " + hooked + " 个方法）");
            if (hooked == 0) logError("未找到 " + "ChatMessageCellDelegate.didPressBotButton" + "，该触发源在此宿主上无效", null);
        } catch (Throwable t) {
            logError("hook didPressBotButton failed", t);
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
                                    if (CORE.onSendRequest(chain.getArgs().toArray())) {
                                        return null; // /jmb 管理命令：拦截不发送
                                    }
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            logInfo("hooked ConnectionsManager.sendRequest（匹配 " + hooked + " 个方法）");
            if (hooked == 0) logError("未找到 " + "ConnectionsManager.sendRequest" + "，该触发源在此宿主上无效", null);
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
        } catch (Throwable t) {
            logError("hook ChatActivity.onResume failed", t);
        }
    }

    // 触发源 3.5：bot 回复语义判定
    private void hookProcessUpdate() {
        try {
            Class<?> mc = loadClass("org.telegram.messenger.MessagesController");
            Method[] ms = mc.getDeclaredMethods();
            int hooked = 0;
            for (Method m : ms) {
                // TG 12.10.1 起改名为 processUpdateArray，按前缀匹配兼容新旧两代
                if (!m.getName().startsWith("processUpdate")) continue;
                String key = "processUpdate#" + m.toGenericString();
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
                                    Object update = chain.getArg(0);
                                    // 把 MessagesController 实例一起传下去：它自己带 currentAccount
                                    // （BaseController 字段），判定回复时才能定位到正确的账号。
                                    Object ctrl = null;
                                    try { ctrl = chain.getThisObject(); } catch (Throwable ignored) {}
                                    if (update != null) CORE.onUpdateProcessed(update, ctrl);
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            logInfo("hooked MessagesController.processUpdate*（匹配 " + hooked + " 个方法）");
            if (hooked == 0) logError("未找到 " + "MessagesController.processUpdate*" + "，该触发源在此宿主上无效", null);
        } catch (Throwable t) {
            logError("hook processUpdate failed", t);
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
                        return chain.proceed();
                    });
            logInfo("hooked LaunchActivity.onResume (jmb host)");
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

    private Class<?> loadClass(String name) throws ClassNotFoundException {
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
