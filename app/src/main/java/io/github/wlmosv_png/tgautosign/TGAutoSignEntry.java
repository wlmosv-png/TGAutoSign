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

    private ClassLoader appLoader = null;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        logInfo("TGAutoSign module loaded in process=" + safeProcessName(param));
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        String packageName = param.getPackageName();
        ClassLoader probe = null;
        try { probe = param.getDefaultClassLoader(); } catch (Throwable ignored) {}
        if (!Hosts.isSupported(packageName, probe)) {
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
    }

    // 触发源 1：ChatActivityEnterView.didPressedBotButton（UI 按钮学习）
    private void hookBotButtonEnterView() {
        try {
            Class<?> cls = loadClass("org.telegram.ui.Components.ChatActivityEnterView");
            Method[] ms = cls.getDeclaredMethods();
            int hooked = 0;
            for (Method m : ms) {
                if (!"didPressedBotButton".equals(m.getName())) continue;
                String key = "enterView#didPressedBotButton#" + m.toGenericString();
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
                                    Object proto = args != null && args.length > 0 ? args[0] : null;
                                    Object mo = args != null && args.length > 2 ? args[2] : null;
                                    CORE.onBotButtonEnterView(proto, mo);
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            logInfo("hooked ChatActivityEnterView.didPressedBotButton（匹配 " + hooked + " 个方法）");
            if (hooked == 0) logError("未找到 " + "ChatActivityEnterView.didPressedBotButton" + "，该触发源在此宿主上无效", null);
        } catch (Throwable t) {
            logError("hook didPressedBotButton failed", t);
        }
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
                                    if (update != null) CORE.onUpdateProcessed(update);
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
        showToast(appContext, message);
    }

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
