package io.github.wlmosv_png.tgautosign;

import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** v1.4.3 新增：借 libxposed 102 的模块服务，请框架把已知 Telegram 系客户端加进本模块作用域。
 *  框架侧是「发通知让用户确认」（LSPosed LSPModuleService.requestScope），不是模块静默写库；
 *  管理器里勾了「阻止作用域请求」时会直接回调失败。非 LSPosed / 旧框架 → 全程静默无副作用。 */
public final class ScopeSync {

    public static volatile boolean bound = false;
    public static volatile String framework = "";
    public static volatile String lastAsk = "还没发起过";
    public static volatile String lastError = "";
    public static volatile List<String> missing = new ArrayList<String>();

    private static volatile XposedService svc = null;
    private static volatile SharedPreferences prefs = null;
    private static volatile boolean registered = false;

    private ScopeSync() {}

    public static synchronized void init(SharedPreferences p) {
        prefs = p;
        if (registered) return;
        registered = true;
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(XposedService service) {
                    svc = service;
                    bound = true;
                    framework = safeName(service);
                    Log.i("TGAutoSignModule", "已连上框架服务: " + framework);
                }
                @Override public void onServiceDied(XposedService service) {
                    if (svc == service) { svc = null; bound = false; framework = ""; }
                }
            });
        } catch (Throwable t) {
            lastError = "框架服务不可用: " + t;
        }
    }

    private static String safeName(XposedService s) {
        try { return s.getFrameworkName() + " " + s.getFrameworkVersion() + " (api " + s.getApiVersion() + ")"; }
        catch (Throwable t) { return "未知框架"; }
    }

    /** 每天最多一轮，避免反复弹通知。 */
    public static void askForKnownHosts() {
        try {
            XposedService s = svc;
            SharedPreferences p = prefs;
            if (s == null || p == null) { lastError = "还没连上框架服务"; return; }
            if (s.getApiVersion() < XposedService.API_102) { lastError = "框架服务版本过低，不支持作用域请求"; return; }
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (today.equals(p.getString("jmb_scope_ask_day", ""))) { lastAsk = "今天已发起过一轮"; return; }
            Set<String> want = new LinkedHashSet<String>(Hosts.knownPackages());
            List<String> have = new ArrayList<String>();
            try { List<String> h = s.getScope(); if (h != null) have.addAll(h); }
            catch (Throwable t) { lastError = "读作用域失败: " + t; return; }
            List<String> ask = new ArrayList<String>();
            for (String w : want) if (!have.contains(w)) ask.add(w);
            missing = ask;
            p.edit().putString("jmb_scope_ask_day", today).apply();
            if (ask.isEmpty()) { lastAsk = "已知客户端都已在作用域里"; return; }
            s.requestScope(ask, new XposedService.OnScopeEventListener() {
                @Override public void onScopeRequestApproved(List<String> ok) {
                    lastAsk = "你已批准 " + (ok == null ? 0 : ok.size()) + " 个客户端加入作用域";
                    if (ok != null) missing.removeAll(ok);
                    Log.i("TGAutoSignModule", "作用域请求已批准: " + ok + "；重启对应客户端后生效");
                }
                @Override public void onScopeRequestFailed(String msg) {
                    lastError = "作用域请求被拒或失败: " + msg;
                    Log.i("TGAutoSignModule", lastError);
                }
            });
            lastAsk = "已发起 " + ask.size() + " 个请求，请在系统通知里确认";
        } catch (Throwable t) {
            lastError = "作用域请求异常: " + t;
        }
    }

    public static String describe() {
        if (bound) return "已连接 " + framework;
        return "未连接（" + (lastError.isEmpty() ? "框架未提供该服务" : lastError) + "）";
    }
}
