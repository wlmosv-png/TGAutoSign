package io.github.wlmosv_png.tgautosign.store;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * TGAutoSign 存储层 —— 模块(Telegram 进程)侧使用，
 * 数据存 Telegram 进程自己的 SharedPreferences（跨进程无顾虑）。
 * /jmb 菜单与模块同进程（Telegram 进程），直接读本类，不再有跨进程广播桥。
 */
public final class Store {

    private static final String PREFS = "tg_autosign_v2";

    private static SharedPreferences p(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static JSONObject loadConfig(Context ctx) {
        return json(p(ctx).getString("cfg", "{}"));
    }

    public static void saveConfig(Context ctx, JSONObject cfg) {
        p(ctx).edit().putString("cfg", cfg.toString()).apply();
    }

    public static JSONObject loadState(Context ctx) {
        return json(p(ctx).getString("state", "{}"));
    }

    public static void saveState(Context ctx, JSONObject state) {
        p(ctx).edit().putString("state", state.toString()).apply();
    }

    public static JSONArray getTargets(Context ctx) {
        JSONObject cfg = loadConfig(ctx);
        return cfg.optJSONArray("targets") != null ? cfg.optJSONArray("targets") : new JSONArray();
    }

    public static void setTargets(Context ctx, JSONArray targets) {
        JSONObject cfg = loadConfig(ctx);
        try { cfg.put("targets", targets); } catch (Throwable ignored) {}
        saveConfig(ctx, cfg);
    }

    public static void heartbeat(Context ctx, long ts) {
        p(ctx).edit().putLong("hb_ts", ts).apply();
    }

    public static long readHeartbeat(Context ctx) {
        return p(ctx).getLong("hb_ts", 0);
    }

    static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 从旧版 tg_autosign_gen 一次性迁移（模块启动时调用） */
    public static boolean migrateLegacy(Context ctx) {
        try {
            if (loadConfig(ctx).has("targets") && loadConfig(ctx).optJSONArray("targets").length() > 0) return false;
            SharedPreferences old = ctx.getSharedPreferences("tg_autosign_gen", Context.MODE_PRIVATE);
            Map<String, ?> all = old.getAll();
            if (all.isEmpty()) return false;
            JSONObject cfg = loadConfig(ctx);
            JSONObject state = loadState(ctx);
            JSONArray targets = new JSONArray();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                String v = String.valueOf(e.getValue());
                if (k.startsWith("learned_")) {
                    try {
                        long did = Long.parseLong(k.substring("learned_".length()));
                        targets.put(new JSONObject().put("dialogId", did).put("text", v).put("account", 0).put("callback", false));
                    } catch (Throwable ignored) {}
                } else if (k.startsWith("last_")) {
                    try { state.put("acc0_" + Long.parseLong(k.substring("last_".length())) + "_last", v); } catch (Throwable ignored) {}
                } else if (k.startsWith("retry_")) {
                    try { state.put("acc0_" + Long.parseLong(k.substring("retry_".length())) + "_retry", Integer.parseInt(v)); } catch (Throwable ignored) {}
                }
            }
            try {
                cfg.put("targets", targets);
                cfg.put("keywords", "签到,打卡,checkin,claim,领取,签到领,/qd,/qiandao,/sign");
                cfg.put("retryLimit", 5);
                cfg.put("notify", true);
                cfg.put("autoLearn", true);
            } catch (Throwable ignored) {}
            saveConfig(ctx, cfg);
            saveState(ctx, state);
            return targets.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // UI 侧缓存（App 内部 prefs，widget 读取）
    public static void saveUiCache(Context ctx, String key, String value) {
        ctx.getSharedPreferences("tg_autosign_ui", Context.MODE_PRIVATE).edit().putString(key, value).apply();
    }

    public static String uiCache(Context ctx, String key, String def) {
        return ctx.getSharedPreferences("tg_autosign_ui", Context.MODE_PRIVATE).getString(key, def);
    }
}
