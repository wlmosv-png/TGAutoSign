package io.github.wlmosv_png.tgautosign;

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
 * TGAutoSign 模块现代入口（API 102）
 *  - Toast 注入证明
 *  - hook Application.attach 后初始化业务（用目标 app 的 ClassLoader）
 *  - 安装业务 hook：ConnectionsManager.sendRequest（网络学习/补签触发）、
 *    ChatActivity.onResume（打开聊天补签）
 */
public final class TGAutoSignEntry extends XposedModule {
    private static final String TAG = "TGAutoSignModule";
    private static final String TARGET_PKG = "org.telegram.messenger";

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
        logInfo("package loaded package=" + packageName);
        if (!TARGET_PKG.equals(packageName)) return;

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

    // ---------------- 业务 hook ----------------
    private void installBusinessHooks() {
        hookSendRequest();
        hookChatActivityOnResume();
        hookProcessUpdate();
    }

    private void hookSendRequest() {
        try {
            Class<?> cm = loadClass("org.telegram.tgnet.ConnectionsManager");
            Method[] ms = cm.getDeclaredMethods();
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
                                    CORE.onSendRequest(chain.getArgs());
                                } catch (Throwable ignored) {}
                                return chain.proceed();
                            });
                } catch (Throwable ignored) {}
            }
            logInfo("hooked ConnectionsManager.sendRequest");
        } catch (Throwable t) {
            logError("hook sendRequest failed", t);
        }
    }

    private void hookChatActivityOnResume() {
        try {
            Class<?> ca = loadClass("org.telegram.ui.ChatActivity");
            Method onResume = ca.getDeclaredMethod("onResume");
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

    // v1.1: bot 回复语义判定（hook 消息接收，转发给 Core）
    private void hookProcessUpdate() {
        try {
            Class<?> mc = loadClass("org.telegram.messenger.MessagesController");
            Method[] ms = mc.getDeclaredMethods();
            for (Method m : ms) {
                if (!"processUpdate".equals(m.getName())) continue;
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
                } catch (Throwable ignored) {}
            }
            logInfo("hooked MessagesController.processUpdate");
        } catch (Throwable t) {
            logError("hook processUpdate failed", t);
        }
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
        // attach hook 会随旧 handle unhook 失效，这里重新安装
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof Context) {
                            try { initBusiness((Context) arg, TARGET_PKG); } catch (Throwable t) {}
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
