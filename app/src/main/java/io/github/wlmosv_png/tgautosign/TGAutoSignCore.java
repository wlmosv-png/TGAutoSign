package io.github.wlmosv_png.tgautosign;

import android.Manifest;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.wlmosv_png.tgautosign.store.Bridge;
import io.github.wlmosv_png.tgautosign.store.Notifier;
import io.github.wlmosv_png.tgautosign.store.Store;
import io.github.wlmosv_png.tgautosign.ui.TGAutoSignWidget;

/**
 * TGAutoSignCore v2 —— 存储迁移到外部 JSON（Store），UI 可读写；
 * 心跳自检；命令队列；通知推送。
 */
public final class TGAutoSignCore {

    private static final String TAG = "TGAutoSignModule";
    private static final long THROTTLE_MS = 60_000L;
    private static final long POLL_INTERVAL_MS = 30L * 60_000L;
    private static final int RETRY_LIMIT_DEFAULT = 5;

    private final Context appContext;
    private final ClassLoader cl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<Map<String, Object>> targets = new CopyOnWriteArrayList<>();
    private final Set<String> seenSignals = new HashSet<>();
    private final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private volatile long lastTry = 0L;
    private volatile boolean receiverRegistered = false;
    private long lastSeenClean = 0L;
    private long lastCallbackToast = 0L;
    private int lastAccount = -1;
    // /jmb 界面版：日志缓冲 + 对话框宿主
    private final java.util.List<String> logBuffer = new java.util.ArrayList<>();
    private final java.text.SimpleDateFormat LOG_SDF = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
    private volatile Activity hostActivity;

    private String LEARN_KEYWORDS = "签到,打卡,checkin,claim,领取,签到领,/qd,/qiandao,/sign";
    private int RETRY_LIMIT = RETRY_LIMIT_DEFAULT;
    private boolean NOTIFY_ENABLED = true;

    public TGAutoSignCore(Context appContext, ClassLoader cl) {
        this.appContext = appContext;
        this.cl = cl;
        Notifier.ensureChannel(appContext);
        Store.migrateLegacy(appContext);
        loadConfig();
    }

    public void start() {
        loadTargets();
        heartbeat();
        registerBridgeReceiver();
        main.postDelayed(() -> enqueueTry("启动"), 10_000L);
        main.postDelayed(this::pollLoop, POLL_INTERVAL_MS);
        registerNetworkReceiver();
        log("TGAutoSignCore v2.2 started, targets=" + targets.size());
    }

    // UI(模块 App) → 模块(Telegram) 广播通道
    private void registerBridgeReceiver() {
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    try {
                        String a = intent.getAction();
                        if (Bridge.ACTION_STATE_REQ.equals(a)) {
                            // UI 请求全量状态
                            Bridge.sendStateResp(appContext,
                                    Store.loadConfig(appContext).toString(),
                                    Store.loadState(appContext).toString());
                        } else if (Bridge.ACTION_CMD.equals(a)) {
                            String cmd = intent.getStringExtra(Bridge.EXTRA_CMD);
                            String json = intent.getStringExtra(Bridge.EXTRA_JSON);
                            handleCmd(cmd, json == null ? "{}" : json);
                        }
                    } catch (Throwable t) {
                        log("bridge receive err: " + t);
                    }
                }
            };
            Bridge.registerModuleReceiver(appContext, r);
        } catch (Throwable t) {
            log("bridge receiver failed: " + t);
        }
    }

    private void handleCmd(String cmd, String json) {
        try {
            JSONObject o = new JSONObject(json);
            long did = o.optLong("dialogId");
            String text = o.optString("text", "");
            if ("manual_sign".equals(cmd)) {
                log("[命令] 手动签到 " + did + " -> " + text);
                // 若目标不在列表则先加入
                if (!targetContains(did) && !text.isEmpty()) {
                    learnTarget(did, text);
                }
                sendSign(did, text);
            } else if ("delete_target".equals(cmd)) {
                log("[命令] 删除目标 " + did);
                removeTarget(did);
            } else if ("reset_target".equals(cmd)) {
                log("[命令] 重置目标 " + did);
                String p = keyPrefix(did);
                JSONObject st = Store.loadState(appContext);
                try {
                    st.remove(p + "last");
                    st.remove(p + "retry");
                    st.remove(p + "retry_at");
                    st.remove(p + "sent_at");
                } catch (Throwable ignored) {}
                Store.saveState(appContext, st);
                log("已重置 " + did + " 今日状态");
            } else if ("edit_target".equals(cmd)) {
                log("[命令] 修改目标指令 " + did + " -> " + text);
                editTarget(did, text);
            }
            // 命令后立即回发权威状态，UI 快速同步
            Bridge.sendStateResp(appContext, Store.loadConfig(appContext).toString(), Store.loadState(appContext).toString());
        } catch (Throwable t) {
            log("handleCmd err: " + t);
        }
    }

    private void editTarget(long did, String text) {
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == did) {
                m.put("text", text);
                saveTargets();
                return;
            }
        }
    }

    private void registerNetworkReceiver() {
        try {
            if (receiverRegistered) return;
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    enqueueTry("网络恢复");
                }
            };
            appContext.registerReceiver(receiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            receiverRegistered = true;
        } catch (Throwable t) {
            log("register network receiver failed: " + t);
        }
    }

    private void loadConfig() {
        JSONObject cfg = Store.loadConfig(appContext);
        LEARN_KEYWORDS = cfg.optString("keywords", LEARN_KEYWORDS);
        RETRY_LIMIT = cfg.optInt("retryLimit", RETRY_LIMIT_DEFAULT);
        NOTIFY_ENABLED = cfg.optBoolean("notify", true);
    }

    private void heartbeat() {
        Store.heartbeat(appContext, System.currentTimeMillis());
        Bridge.sendHb(appContext);
        updateWidget();
    }

    private void updateWidget() {
        try {
            Intent it = new Intent(appContext, TGAutoSignWidget.class);
            it.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            appContext.sendBroadcast(it);
        } catch (Throwable ignored) {}
    }

    private void pollLoop() {
        try {
            heartbeat();
            int acc = currentAccount();
            if (lastAccount != acc) {
                lastAccount = acc;
                log("检测到账号切换 -> acc" + acc);
            }
            // UI 可能改过配置，重读
            loadConfig();
            loadTargets();
        } catch (Throwable ignored) {}
        enqueueTry("定时");
        main.postDelayed(this::pollLoop, POLL_INTERVAL_MS);
    }

    // ---------------- 触发源：TG 网络活动 ----------------
    public boolean onSendRequest(List<Object> args) {
        try {
            if (args != null && !args.isEmpty() && args.get(0) != null) {
                Object req = args.get(0);
                String name = req.getClass().getName();
                if (name.contains("TL_messages_sendMessage")) {
                    Object m0 = getFieldVal(req, "message");
                    if (m0 != null && String.valueOf(m0).trim().startsWith("/jmb")) {
                        handleCommand(String.valueOf(m0));
                        return true;
                    }
                }
                if (name.contains("TL_messages_getBotCallbackAnswer")) {
                    try {
                        Object peer = getFieldVal(req, "peer");
                        Object uid = null;
                        if (peer != null) { try { uid = getFieldVal(peer, "user_id"); } catch (Throwable ignored) {} }
                        if (uid != null) {
                            final long u = ((Number) uid).longValue();
                            Object data = getFieldVal(req, "data");
                            log("[回调按钮] uid=" + u + " data=" + data + " → 回调型按钮，自动签到暂不支持");
                            long nowMs = System.currentTimeMillis();
                            if (nowMs - lastCallbackToast > 60_000L) {
                                lastCallbackToast = nowMs;
                                toast("ℹ️ 该机器人使用回调按钮，自动签到暂不支持");
                            }
                            // 标记目标为回调型（UI 展示）
                            markCallbackType(u);
                        }
                    } catch (Throwable ignored) {}
                } else if (name.contains("TL_messages_sendMessage")) {
                    Object peer = getFieldVal(req, "peer");
                    Object msg = getFieldVal(req, "message");
                    Object uid = null;
                    if (peer != null) {
                        try { uid = getFieldVal(peer, "user_id"); } catch (Throwable ignored) {}
                    }
                    if (uid != null) {
                        final Object fUid = uid;
                        final Object fMsg = msg;
                        main.post(() -> {
                            long u = ((Number) fUid).longValue();
                            String t = String.valueOf(fMsg);
                            String key = u + "|" + t;
                            long nowMs = System.currentTimeMillis();
                            if (nowMs - lastSeenClean > 500L) {
                                seenSignals.clear();
                                lastSeenClean = nowMs;
                            }
                            if (seenSignals.contains(key)) {
                                log("[去重] 跳过重复信号 " + key);
                                return;
                            }
                            seenSignals.add(key);
                            if (targetContains(u)) {
                                markSignedFromRequest(u, t);
                            } else {
                                learnFromNetwork(u, t);
                            }
                        });
                    }
                }
            }
            enqueueTry("TG活动");
        } catch (Throwable t) {
            log("onSendRequest err: " + t);
        }
        return false;
    }


    // ==================== /jmb 界面版（Telegram 内管理菜单） ====================

    public void setHostActivity(Activity a) {
        this.hostActivity = a;
    }

    private int dp(float value) {
        return Math.max(1, (int) (android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, value, appContext.getResources().getDisplayMetrics()) + 0.5f));
    }

    private boolean isDarkMode() {
        try {
            int mode = appContext.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private int txtMain() {
        return android.graphics.Color.parseColor(isDarkMode() ? "#F2F2F2" : "#1F1F1F");
    }

    private int txtSub() {
        return android.graphics.Color.parseColor(isDarkMode() ? "#ABABAB" : "#757575");
    }

    private void handleCommand(String text) {
        log("[界面] 管理命令: " + text.trim());
        main.post(this::showMainMenu);
    }

    private String statusOf(long did) {
        String p = "acc" + currentAccount() + "_" + did + "_";
        JSONObject st = Store.loadState(appContext);
        String today = todayStr();
        if (today.equals(st.optString(p + "last"))) return "已签 ✅";
        int retries = st.optInt(p + "retry", 0);
        if (retries >= RETRY_LIMIT) return "已放弃 💤";
        if (System.currentTimeMillis() < st.optLong(p + "retry_at", 0)) return "退避中 ⏳";
        if (retries > 0) return "重试中 🔄";
        return "待签 ⏱";
    }

    private View menuItem(LinearLayout parent, String emoji, String title, String subtitle, String action) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        row.setTag(action);
        row.setOnClickListener(v -> runAction(v.getContext(), String.valueOf(v.getTag())));

        TextView em = new TextView(c);
        em.setTextSize(20);
        em.setText(emoji);
        row.addView(em, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(c);
        t1.setTextSize(15);
        t1.setTextColor(txtMain());
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t1.setText(title);
        TextView t2 = new TextView(c);
        t2.setTextSize(12);
        t2.setTextColor(txtSub());
        if (subtitle != null && subtitle.length() > 0) t2.setText(subtitle); else t2.setVisibility(View.GONE);
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView arrow = new TextView(c);
        arrow.setTextSize(18);
        arrow.setText("›");
        arrow.setTextColor(txtSub());
        row.addView(arrow);
        parent.addView(row);

        View div = new View(c);
        div.setBackgroundColor(0x1A000000);
        parent.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return row;
    }

    private View targetRow(LinearLayout parent, String status, long did, String text, String action) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setTag(action + "|" + did + "|" + text);
        if (action != null) {
            row.setOnClickListener(v -> {
                String[] parts = String.valueOf(v.getTag()).split("\\|");
                runTargetAction(v.getContext(), parts[0], Long.parseLong(parts[1]), parts.length > 2 ? parts[2] : "");
            });
        }
        TextView st = new TextView(c);
        st.setTextSize(15);
        st.setText(status);
        row.addView(st, new LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(c);
        t1.setTextSize(15);
        t1.setTextColor(txtMain());
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t1.setText("uid: " + did);
        TextView t2 = new TextView(c);
        t2.setTextSize(13);
        t2.setTextColor(txtSub());
        t2.setText("指令: " + text);
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView arrow = new TextView(c);
        arrow.setTextSize(18);
        arrow.setText("›");
        arrow.setTextColor(txtSub());
        row.addView(arrow);
        parent.addView(row);

        View div = new View(c);
        div.setBackgroundColor(0x1A000000);
        parent.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return row;
    }

    private void emptyView(LinearLayout parent, String text) {
        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextColor(txtSub());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(24), 0, dp(24));
        parent.addView(tv);
    }

    private EditText adInput(Activity act, String hint, boolean numeric) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setTextSize(15);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private void showMainMenu() {
        Activity act = hostActivity;
        if (act == null) {
            toast("请在 Telegram 界面发送 /jmb");
            return;
        }
        String today = todayStr();
        int signed = 0;
        for (Map<String, Object> m : targets) {
            String p = "acc" + currentAccount() + "_" + ((Number) m.get("dialogId")).longValue() + "_";
            JSONObject st = Store.loadState(appContext);
            if (today.equals(st.optString(p + "last"))) signed++;
        }
        LinearLayout menu = new LinearLayout(act);
        menu.setOrientation(LinearLayout.VERTICAL);

        TextView cred = new TextView(act);
        cred.setText("by wlmosv");
        cred.setTextSize(12);
        cred.setTextColor(txtSub());
        cred.setGravity(Gravity.END);
        cred.setPadding(dp(16), 0, dp(16), dp(6));
        menu.addView(cred);

        menuItem(menu, "📋", "目标列表", "共 " + targets.size() + " 个 · 已签 " + signed, "list");
        menuItem(menu, "➕", "添加目标", "bot ID + 签到指令，立即执行", "add");
        menuItem(menu, "🗑", "删除目标", "从自动签到移除", "del");
        menuItem(menu, "🚀", "立即签到", "手动触发一次签到", "sign");
        menuItem(menu, "📄", "运行日志", "最近 200 行", "log");
        menuItem(menu, "⚙️", "设置", "关键词 / 重试上限", "settings");

        new AlertDialog.Builder(act)
                .setTitle("TGAutoSign · 管理")
                .setView(menu)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void runAction(Context ctx, String action) {
        if (!(ctx instanceof Activity)) return;
        Activity act = (Activity) ctx;
        switch (action) {
            case "list": showList(act); break;
            case "add": showAdd(act); break;
            case "del": showDelete(act); break;
            case "sign": showSign(act); break;
            case "log": showLog(act); break;
            case "settings": showSettings(act); break;
        }
    }

    private void runTargetAction(Context ctx, String action, long did, String text) {
        if ("delete".equals(action)) {
            removeTarget(did);
            toast("已删除 " + did);
            if (ctx instanceof Activity) showDelete((Activity) ctx);
        } else if ("sign".equals(action)) {
            log("[界面] 手动签到 " + did);
            sendSign(did, text);
            toast("已命令签到 " + did);
        }
    }

    private void showList(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        if (targets.size() == 0) {
            emptyView(box, "暂无目标\n在机器人的聊天里点一次签到按钮即可自动学习，或返回点「添加目标」");
        } else {
            for (Map<String, Object> m : targets) {
                long did = ((Number) m.get("dialogId")).longValue();
                String text = String.valueOf(m.get("text"));
                targetRow(box, statusOf(did), did, text, null);
            }
        }
        Button back = new Button(act);
        back.setText("← 返回主菜单");
        back.setOnClickListener(v -> showMainMenu());
        box.addView(back);
        new AlertDialog.Builder(act)
                .setTitle("签到目标")
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAdd(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText uid = adInput(act, "机器人 ID（数字，无需 @）", true);
        EditText cmd = adInput(act, "签到指令，如：/qd 或 📅 签到", false);
        box.addView(uid);
        box.addView(cmd);
        Button ok = new Button(act);
        ok.setText("添加并立即签到");
        ok.setOnClickListener(v -> {
            try {
                long did = Long.parseLong(uid.getText().toString().trim());
                String t = cmd.getText().toString().trim();
                if (t.isEmpty()) {
                    toast("指令不能为空");
                    return;
                }
                learnTarget(did, t);
                toast("✅ 已添加 " + did + " → " + t + "，立即签到…");
                sendSign(did, t);
            } catch (Throwable e) {
                toast("UID 格式错误");
            }
        });
        box.addView(ok);
        new AlertDialog.Builder(act)
                .setTitle("添加签到目标")
                .setView(box)
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDelete(Activity act) {
        if (targets.size() == 0) {
            toast("暂无目标可删除");
            return;
        }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        for (Map<String, Object> m : targets) {
            long did = ((Number) m.get("dialogId")).longValue();
            String text = String.valueOf(m.get("text"));
            targetRow(box, "🗑", did, text, "delete");
        }
        new AlertDialog.Builder(act)
                .setTitle("点选要删除的目标")
                .setView(box)
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSign(Activity act) {
        if (targets.size() == 0) {
            toast("暂无目标");
            return;
        }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        for (Map<String, Object> m : targets) {
            long did = ((Number) m.get("dialogId")).longValue();
            String text = String.valueOf(m.get("text"));
            targetRow(box, "🚀", did, text, "sign");
        }
        new AlertDialog.Builder(act)
                .setTitle("点选立即签到")
                .setView(box)
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLog(Activity act) {
        StringBuilder sb = new StringBuilder();
        List<String> copy;
        synchronized (logBuffer) {
            copy = new ArrayList<>(logBuffer);
        }
        for (String line : copy) sb.append(line).append("\n");
        String content = sb.toString();
        if (content.length() > 4000) content = content.substring(content.length() - 4000);
        if (content.isEmpty()) content = "(暂无日志)";
        ScrollView sv = new ScrollView(act);
        TextView tv = new TextView(act);
        tv.setText(content);
        tv.setTextSize(12);
        tv.setTextColor(txtMain());
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        sv.addView(tv);
        new AlertDialog.Builder(act)
                .setTitle("运行日志（最近 200 行）")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showSettings(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText kw = adInput(act, "学习关键词（逗号分隔）", false);
        kw.setText(LEARN_KEYWORDS == null ? "" : LEARN_KEYWORDS);
        EditText rl = adInput(act, "每日重试上限", true);
        rl.setText(String.valueOf(RETRY_LIMIT));
        box.addView(kw);
        box.addView(rl);
        Button ok = new Button(act);
        ok.setText("保存");
        ok.setOnClickListener(v -> {
            String k = kw.getText().toString().trim();
            if (!k.isEmpty()) LEARN_KEYWORDS = k;
            try {
                int r = Integer.parseInt(rl.getText().toString().trim());
                if (r > 0 && r <= 99) RETRY_LIMIT = r;
            } catch (Throwable ignored) {}
            JSONObject cfg = Store.loadConfig(appContext);
            try {
                cfg.put("keywords", LEARN_KEYWORDS);
                cfg.put("retryLimit", RETRY_LIMIT);
            } catch (Throwable ignored) {}
            Store.saveConfig(appContext, cfg);
            toast("设置已保存: 关键词[" + LEARN_KEYWORDS + "] 重试上限[" + RETRY_LIMIT + "]");
            log("设置更新: 关键词=" + LEARN_KEYWORDS + " 重试上限=" + RETRY_LIMIT);
        });
        box.addView(ok);
        new AlertDialog.Builder(act)
                .setTitle("设置")
                .setView(box)
                .setNegativeButton("取消", null)
                .show();
    }

    // v2: bot 回复语义判定
    public void onUpdateProcessed(Object update) {
        try {
            String un = update.getClass().getName();
            if (!un.contains("TL_updateNewMessage") && !un.contains("TL_updateNewChannelMessage")) return;
            Object msg = getFieldVal(update, "message");
            if (msg == null) return;
            long peerUid = -1, fromUid = -1;
            try {
                Object peerId = getFieldVal(msg, "peer_id");
                Object pu = peerId != null ? getFieldVal(peerId, "user_id") : null;
                if (pu != null) peerUid = ((Number) pu).longValue();
            } catch (Throwable ignored) {}
            try {
                Object fromId = getFieldVal(msg, "from_id");
                Object fu = fromId != null ? getFieldVal(fromId, "user_id") : null;
                if (fu != null) fromUid = ((Number) fu).longValue();
            } catch (Throwable ignored) {}
            if (peerUid <= 0 || fromUid != peerUid) return;
            if (!targetContains(peerUid)) return;
            Object mtext = getFieldVal(msg, "message");
            if (mtext == null) return;
            final String replyText = String.valueOf(mtext);
            if (replyText.isEmpty()) return;
            final long did = peerUid;
            if (System.currentTimeMillis() - lastSentAt(did) > 10L * 60 * 1000) return;
            main.post(() -> {
                try {
                    String lower = replyText.toLowerCase();
                    String[] failWords = {"失败", "未成功", "请先", "不能", "无法", "不可", "错误", "已过期", "未关注", "没有资格", "failed", "invalid", "rejected", "not allowed", "try again"};
                    for (String w : failWords) {
                        if (lower.contains(w)) {
                            String p = keyPrefix(did);
                            JSONObject st = Store.loadState(appContext);
                            try {
                                st.put(p + "retry", st.optInt(p + "retry", 0) + 1);
                                st.put(p + "retry_at", System.currentTimeMillis() + backoffDelay(1));
                                st.remove(p + "last");
                            } catch (Throwable ignored) {}
                            Store.saveState(appContext, st);
                            log("【回复判定】" + did + " bot 回复: " + replyText + " → 撤销已签，安排重试");
                            notifyUser("⚠️ " + did + " 可能未签到成功", replyText);
                            updateWidget();
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            log("[回复判定] 异常: " + t);
        }
    }

    // ---------------- 目标管理 ----------------
    private void loadTargets() {
        targets.clear();
        JSONArray arr = Store.getTargets(appContext);
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                Map<String, Object> m = new HashMap<>();
                m.put("dialogId", o.optLong("dialogId"));
                m.put("text", o.optString("text"));
                m.put("account", o.optInt("account", 0));
                m.put("callback", o.optBoolean("callback", false));
                targets.add(m);
            } catch (Throwable ignored) {}
        }
        log("目标已加载: " + targets.size());
    }

    private void saveTargets() {
        try {
            JSONArray arr = new JSONArray();
            for (Map<String, Object> m : targets) {
                JSONObject o = new JSONObject();
                o.put("dialogId", ((Number) m.get("dialogId")).longValue());
                o.put("text", String.valueOf(m.get("text")));
                o.put("account", ((Number) m.getOrDefault("account", 0)).intValue());
                o.put("callback", Boolean.TRUE.equals(m.getOrDefault("callback", false)));
                arr.put(o);
            }
            Store.setTargets(appContext, arr);
        } catch (Throwable t) {
            log("保存目标失败: " + t);
        }
    }

    private boolean targetContains(long did) {
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == did) return true;
        }
        return false;
    }

    private void removeTarget(long did) {
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == did) {
                targets.remove(m);
                break;
            }
        }
        saveTargets();
        updateWidget();
    }

    private void markCallbackType(long did) {
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == did) {
                if (!Boolean.TRUE.equals(m.get("callback"))) {
                    m.put("callback", true);
                    saveTargets();
                }
                return;
            }
        }
    }

    public void learnTarget(long dialogId, String text) {
        if (!Store.loadConfig(appContext).optBoolean("autoLearn", true)) return;
        if (text == null || text.isEmpty()) return;
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == dialogId) {
                if (String.valueOf(m.get("text")).equals(String.valueOf(text))) {
                    log("目标 " + dialogId + " 已学习过相同指令，跳过");
                    return;
                }
                log("检测到指令变化，覆盖 " + dialogId + " : " + m.get("text") + " -> " + text);
                m.put("text", text);
                saveTargets();
                return;
            }
        }
        Map<String, Object> m = new HashMap<>();
        m.put("dialogId", dialogId);
        m.put("text", text);
        m.put("account", currentAccount());
        m.put("callback", false);
        targets.add(m);
        saveTargets();
        log("【自动学习】新目标 " + dialogId + " -> " + text);
        toast("✅ 已添加新签到目标: " + text);
        updateWidget();
    }

    private void learnFromNetwork(long did, String text) {
        if (!Store.loadConfig(appContext).optBoolean("autoLearn", true)) return;
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 20) return;
        if (targetContains(did)) return;
        boolean matched = false;
        for (String kw : LEARN_KEYWORDS.split(",")) {
            if (!kw.trim().isEmpty() && t.toLowerCase().contains(kw.trim().toLowerCase())) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            log("[候选] uid=" + did + " msg=" + t + "（不含签到关键词，不自动添加）");
            return;
        }
        boolean isBot = false;
        try {
            Object mc = getMessagesController();
            if (mc != null) {
                Object u = invoke(mc, "getUser", new Class<?>[]{Long.class}, new Object[]{did});
                if (u != null) {
                    Object bot = getFieldVal(u, "bot");
                    isBot = Boolean.TRUE.equals(bot);
                }
            }
        } catch (Throwable ignored) {}
        if (!isBot) {
            log("[候选] uid=" + did + " msg=" + t + "（非bot，不自动添加）");
            return;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("dialogId", did);
        m.put("text", t);
        m.put("account", currentAccount());
        m.put("callback", false);
        targets.add(m);
        saveTargets();
        log("【网络层自动学习】新目标 " + did + " -> " + t);
        toast("✅ 已自动添加新签到目标: " + t);
        updateWidget();
    }

    private void markSignedFromRequest(long did, String text) {
        try {
            for (Map<String, Object> m : targets) {
                if (((Number) m.get("dialogId")).longValue() == did
                        && text.equals(String.valueOf(m.get("text")))) {
                    JSONObject st = Store.loadState(appContext);
                    try {
                        st.put(keyPrefix(did) + "last", todayStr());
                    } catch (Throwable ignored) {}
                    Store.saveState(appContext, st);
                    log("检测到签到消息已发出，标记今日已签 " + did);
                    updateWidget();
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- 发送签到 ----------------
    private void sendSign(long dialogId, String text) {
        if (pending.contains(dialogId)) {
            log("目标 " + dialogId + " 已有请求在处理中，跳过");
            return;
        }
        pending.add(dialogId);
        try {
            int account = currentAccount();
            Object mc = getMessagesController();
            if (mc == null) {
                pending.remove(dialogId);
                log("MessagesController 为空");
                return;
            }
            Object user = invoke(mc, "getUser", new Class<?>[]{Long.class}, new Object[]{dialogId});
            if (user == null) {
                log("内存无缓存 " + dialogId + "，尝试从数据库读取");
                try {
                    Object ms = getMessagesStorage();
                    if (ms != null) {
                        user = invoke(ms, "getUser", new Class<?>[]{long.class}, new Object[]{dialogId});
                    }
                } catch (Throwable e) {
                    log("数据库读取失败 " + dialogId + " : " + e);
                }
            }
            if (user == null) {
                pending.remove(dialogId);
                log("未找到用户数据 " + dialogId);
                return;
            }
            Object peer = staticInvoke(getClassEx("org.telegram.messenger.MessagesController"), "getInputPeer", new Class<?>[]{Object.class}, new Object[]{user});
            if (peer == null) {
                pending.remove(dialogId);
                log("构造 InputPeer 失败 " + dialogId);
                return;
            }

            Class<?> sendCls = getClassEx("org.telegram.tgnet.TLRPC$TL_messages_sendMessage");
            Object req = sendCls.newInstance();
            setFieldVal(req, "peer", peer);
            setFieldVal(req, "message", text);
            setFieldVal(req, "random_id", random.nextLong());

            Object cm = staticInvoke(getClassEx("org.telegram.tgnet.ConnectionsManager"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
            markSent(dialogId);
            final long fDid = dialogId;
            final String fText = text;
            final Object delegate = newRequestDelegate(new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] margs) throws Throwable {
                    if ("run".equals(method.getName()) && margs != null && margs.length >= 2) {
                        final Object error = margs[1];
                        main.post(() -> {
                            pending.remove(fDid);
                            try {
                                if (error != null) {
                                    String et = "";
                                    try { et = String.valueOf(getFieldVal(error, "text")); } catch (Throwable ignored) {}
                                    String code = "";
                                    try { code = String.valueOf(getFieldVal(error, "code")); } catch (Throwable ignored) {}
                                    String upper = (et + " " + code).toUpperCase();
                                    boolean permanent = false;
                                    for (String e : PERMANENT_ERRORS) {
                                        if (upper.contains(e)) { permanent = true; break; }
                                    }
                                    String p = keyPrefix(fDid);
                                    if (permanent) {
                                        JSONObject st = Store.loadState(appContext);
                                        try {
                                            st.put(p + "last", todayStr());
                                            st.put(p + "retry", RETRY_LIMIT);
                                        } catch (Throwable ignored) {}
                                        Store.saveState(appContext, st);
                                        log("签到永久失败 " + fDid + " : " + et + "（今日放弃）");
                                        notifyUser("⚠️ " + fDid + " 签到失败", et + "（永久错误，今日不再重试）");
                                    } else if (code.contains("420") || upper.startsWith("FLOOD_WAIT")) {
                                        main.postDelayed(() -> { try { enqueueTry("限流重试"); } catch (Throwable ignored) {} }, 60_000L);
                                        log("签到遇限流 " + fDid + " : " + et + "，60秒后自动重试");
                                    } else {
                                        JSONObject st = Store.loadState(appContext);
                                        try {
                                            st.put(p + "retry", st.optInt(p + "retry", 0) + 1);
                                            st.put(p + "retry_at", System.currentTimeMillis() + backoffDelay(st.optInt(p + "retry", 0)));
                                        } catch (Throwable ignored) {}
                                        Store.saveState(appContext, st);
                                        log("签到失败 " + fDid + " : " + et + "（第" + st.optInt(p + "retry", 0) + "次，退避重试）");
                                        notifyUser("⚠️ " + fDid + " 签到失败", et + "，稍后自动重试");
                                    }
                                } else {
                                    JSONObject st = Store.loadState(appContext);
                                    try {
                                        st.put(keyPrefix(fDid) + "last", todayStr());
                                        st.put(keyPrefix(fDid) + "retry", 0);
                                    } catch (Throwable ignored) {}
                                    Store.saveState(appContext, st);
                                    log("签到完成 " + fDid + " text=" + fText);
                                    toast("✅ 签到成功: " + fText);
                                    notifyUser("✅ 签到成功", fText);
                                }
                                updateWidget();
                            } catch (Throwable ignored) {}
                        });
                    }
                    return null;
                }
            });

            invoke(cm, "sendRequest", new Class<?>[]{getClassEx("org.telegram.tgnet.TLObject"), getClassEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
            log("已发起签到请求 " + dialogId + " text=" + text);
        } catch (Throwable t) {
            pending.remove(dialogId);
            log("sendSign err " + dialogId + " : " + t);
            toast("⚠️ 签到发送失败: " + t);
        }
    }

    // ---------------- 补签 ----------------
    public void enqueueTry(String reason) {
        main.post(() -> {
            try {
                trySignAll(reason);
            } catch (Throwable t) {
                log("[" + reason + "] 异常: " + t);
            }
        });
    }

    private void trySignAll(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastTry < THROTTLE_MS) return;
        lastTry = now;

        if (!hasNetwork()) {
            log("[" + reason + "] 无网络，跳过，网络恢复后自动补");
            return;
        }
        boolean promptToday = reason != null && (reason.startsWith("启动") || "打开聊天".equals(reason) || "网络恢复".equals(reason));
        String today = todayStr();
        int signed = 0;
        int total = targets.size();
        for (Map<String, Object> m : targets) {
            long dialogId = ((Number) m.get("dialogId")).longValue();
            String text = String.valueOf(m.get("text"));
            try {
                String p = keyPrefix(dialogId);
                JSONObject st = Store.loadState(appContext);
                if (today.equals(st.optString(p + "last"))) {
                    signed++;
                    continue;
                }
                int retries = st.optInt(p + "retry", 0);
                if (retries >= RETRY_LIMIT) {
                    log("[" + reason + "] " + dialogId + " 今日重试已达上限");
                    signed++;
                    continue;
                }
                long retryAt = st.optLong(p + "retry_at", 0);
                if (System.currentTimeMillis() < retryAt) {
                    log("[" + reason + "] " + dialogId + " 退避中(剩" + ((retryAt - now) / 60000L) + "分钟)，跳过");
                    continue;
                }
                log("[" + reason + "] 尝试签到 " + dialogId + " text=" + text + " (重试" + retries + ")");
                sendSign(dialogId, text);
            } catch (Throwable t) {
                log("trySignAll 异常 " + dialogId + " : " + t);
            }
        }
        if (promptToday && total > 0 && signed == total) {
            log("[提示] 今天已全部签到完成，无需重复");
            toast("今天已经签到过了 ✅");
        }
    }

    // ---------------- 状态工具 ----------------
    private String keyPrefix(long did) {
        return "acc" + currentAccount() + "_" + did + "_";
    }

    private long lastSentAt(long did) {
        JSONObject st = Store.loadState(appContext);
        return st.optLong(keyPrefix(did) + "sent_at", 0);
    }

    private void markSent(long did) {
        JSONObject st = Store.loadState(appContext);
        try { st.put(keyPrefix(did) + "sent_at", System.currentTimeMillis()); } catch (Throwable ignored) {}
        Store.saveState(appContext, st);
    }

    private String todayStr() {
        return SDF.format(new Date());
    }

    private long backoffDelay(int retries) {
        long[] delays = {5L * 60 * 1000, 15L * 60 * 1000, 45L * 60 * 1000, 2L * 60 * 60 * 1000, 4L * 60 * 60 * 1000};
        return delays[Math.min(retries, delays.length - 1)];
    }

    // ---------------- 通知 / Toast / 日志 ----------------
    private void notifyUser(String title, String text) {
        try {
            Notifier.notify(appContext, title, text, NOTIFY_ENABLED);
        } catch (Throwable ignored) {}
    }

    void toast(String msg) {
        main.post(() -> {
            try {
                Toast.makeText(appContext, String.valueOf(msg), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
    }

    // 日志：logcat + 内存缓冲 + 广播（UI 进程实时收）
    void log(String msg) {
        Log.i(TAG, msg);
        Bridge.bufferLog(msg);
        Bridge.sendLogLine(appContext, msg);
        synchronized (logBuffer) {
            logBuffer.add(LOG_SDF.format(new Date()) + " " + msg);
            while (logBuffer.size() > 200) logBuffer.remove(0);
        }
    }

    // ---------------- 反射 / 工具 ----------------
    private static final String[] PERMANENT_ERRORS = {"PEER_ID_INVALID", "USER_BOT_INVALID", "CHAT_WRITE_FORBIDDEN", "USER_ID_INVALID", "AUTH_KEY_UNREGISTERED", "MESSAGE_EMPTY", "CHAT_ID_INVALID", "PEER_ID_NOT_EXIST", "USER_PRIVACY_RESTRICTED"};
    private final Set<Long> pending = new HashSet<>();

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    private int currentAccount() {
        try {
            Object v = getFieldVal(null, getClassEx("org.telegram.messenger.UserConfig"), "selectedAccount");
            return ((Number) v).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private Object getMessagesController() {
        try {
            return staticInvoke(getClassEx("org.telegram.messenger.MessagesController"), "getInstance", new Class<?>[]{int.class}, new Object[]{currentAccount()});
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getMessagesStorage() {
        try {
            return staticInvoke(getClassEx("org.telegram.messenger.MessagesStorage"), "getInstance", new Class<?>[]{int.class}, new Object[]{currentAccount()});
        } catch (Throwable t) {
            return null;
        }
    }

    private Object newRequestDelegate(InvocationHandler handler) throws Exception {
        Class<?> iface = getClassEx("org.telegram.tgnet.RequestDelegate");
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, handler);
    }

    private Class<?> getClassEx(String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    private static Object getFieldVal(Object obj, Class<?> cls, String name) {
        try {
            Field f = cls.getField(name);
            return f.get(obj);
        } catch (Throwable t) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable t2) {
                throw new RuntimeException(t2);
            }
        }
    }

    private static Object getFieldVal(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            return f.get(obj);
        } catch (Throwable t) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable t2) {
                throw new RuntimeException(t2);
            }
        }
    }

    private static void setFieldVal(Object obj, String name, Object val) {
        try {
            Field f = obj.getClass().getField(name);
            f.set(obj, val);
        } catch (Throwable t) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, val);
            } catch (Throwable t2) {
                throw new RuntimeException(t2);
            }
        }
    }

    private static Object invoke(Object obj, String name, Class<?>[] paramTypes, Object[] args) throws Exception {
        Method m = obj.getClass().getMethod(name, paramTypes);
        return m.invoke(obj, args);
    }

    private static Object staticInvoke(Class<?> cls, String name, Class<?>[] paramTypes, Object[] args) throws Exception {
        Method m = cls.getMethod(name, paramTypes);
        return m.invoke(null, args);
    }
}
