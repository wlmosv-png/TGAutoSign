// ============================================================
// jmb界面版 - TGAutoSign Telegram 自动签到【界面版 v1.0】
// ------------------------------------------------------------
// 重新设计要点：
//  * 无独立 App：管理界面直接在 Telegram 内以对话框呈现
//  * 输入 /jmb 弹出管理菜单（列表/添加/删除/签到/日志/设置）
//  * 完全运行在 LSPilot 插件内，无跨进程通信，热重载即可调试
//  * 数据与旧插件共用 tg_autosign_gen（已学目标无缝继承）
//  * 保留自动学习/每日一签/回复语义判定/错误分类/指数退避
// ============================================================

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import android.app.Activity;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import android.text.InputType;
import java.util.Random;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.text.SimpleDateFormat;

// ---------------- 配置 ----------------
BUILTIN_TARGETS = new HashMap();
LEARN_ENABLED = true;
AUTO_LEARN_NET = true;
THROTTLE_MS = 60L * 1000L;
POLL_INTERVAL_MS = 30L * 60L * 1000L;
RETRY_LIMIT = 5;
LEARN_KEYWORDS = "签到,打卡,checkin,claim,领取,签到领,/qd,/qiandao,/sign";

prefs = hostContext.getSharedPreferences("tg_autosign_gen", 0);
SDF = new SimpleDateFormat("yyyy-MM-dd");
targets = new ArrayList();
lastTryTime = 0L;
mainHandler = new Handler(Looper.getMainLooper());
pendingSigns = new HashSet();
receiverRegistered = false;
lastAccount = -1;

// 界面版：最近的前台 Activity（用于弹管理对话框）
lastActivity = null;
// 日志环形缓冲（最近 200 行）
logBuffer = new ArrayList();

todayStr() { return SDF.format(new Date()); }

String accountPrefix() { return "acc" + UserConfig.selectedAccount + "_"; }

toast(msg) {
    try {
        mainHandler.post(() -> {
            try {
                Toast.makeText(hostContext, String.valueOf(msg), Toast.LENGTH_LONG).show();
            } catch (Throwable t2) {}
        });
    } catch (Throwable t) {}
}

jlog(msg) {
    try {
        System.out.println("[jmb] " + msg);
        synchronized (logBuffer) {
            logBuffer.add("[" + SDF.format(new Date()) + " " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + msg);
            while (logBuffer.size() > 200) logBuffer.remove(0);
        }
    } catch (Throwable t) {}
}

// ---------------- 目标管理（与旧插件同键，数据共享） ----------------
addTarget(dialogId, text) {
    for (Object item : targets) {
        Map m = (Map) item;
        if (((Number) m.get("dialogId")).longValue() == dialogId) return;
    }
    Map m = new HashMap();
    m.put("dialogId", dialogId);
    m.put("text", text);
    targets.add(m);
    jlog("已登记签到目标 " + dialogId + " -> " + text);
}

targetContains(did) {
    for (Object item : targets) {
        Map m = (Map) item;
        if (((Number) m.get("dialogId")).longValue() == did) return true;
    }
    return false;
}

loadTargets() {
    targets.clear();
    try {
        String prefix = accountPrefix();
        Map all = prefs.getAll();
        for (Object keyObj : all.keySet()) {
            String key = String.valueOf(keyObj);
            if (key.startsWith(prefix + "learned_")) {
                String didStr = key.substring((prefix + "learned_").length());
                long did = Long.parseLong(didStr);
                addTarget(did, String.valueOf(all.get(key)));
            }
        }
    } catch (Throwable t) {
        jlog("读取学习目标失败: " + t);
    }
}

learnTarget(dialogId, text) {
    if (!LEARN_ENABLED) return;
    if (text == null || text.length() == 0) return;
    if (dialogId <= 0) {
        jlog("忽略群聊学习: dialogId=" + dialogId);
        return;
    }
    if (targetContains(dialogId)) {
        for (Object item : targets) {
            Map m = (Map) item;
            if (((Number) m.get("dialogId")).longValue() == dialogId) {
                if (String.valueOf(m.get("text")).equals(String.valueOf(text))) {
                    jlog("目标 " + dialogId + " 已学习过相同指令，跳过");
                    return;
                }
                jlog("检测到指令变化，覆盖 " + dialogId + " : " + m.get("text") + " -> " + text);
                break;
            }
        }
    }
    String prefix = accountPrefix();
    try {
        prefs.edit()
            .putString(prefix + "learned_" + dialogId, text)
            .putString(prefix + "last_" + dialogId, todayStr())
            .commit();
        addTarget(dialogId, text);
        jlog("【自动学习】新目标 " + dialogId + " -> " + text);
        toast("✅ 已添加新签到目标: " + text);
    } catch (Throwable t) {
        jlog("学习失败: " + t);
    }
}

learnFromNetwork(did, text) {
    if (!AUTO_LEARN_NET || !LEARN_ENABLED) return;
    if (text == null) return;
    if (did <= 0) return;
    String t = String.valueOf(text).trim();
    if (t.length() == 0 || t.length() > 20) return;
    if (targetContains(did)) return;
    if (LEARN_KEYWORDS != null && LEARN_KEYWORDS.trim().length() > 0) {
        String[] kws = LEARN_KEYWORDS.split(",");
        boolean matched = false;
        for (String kw : kws) {
            if (kw.trim().length() > 0 && t.toLowerCase().contains(kw.trim().toLowerCase())) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            jlog("[候选] uid=" + did + " msg=" + t + "（不含签到关键词，不自动添加）");
            return;
        }
    }
    boolean isBot = false;
    try {
        MessagesController mc = MessagesController.getInstance(UserConfig.selectedAccount);
        if (mc != null) {
            Object u = callMethod(mc, "getUser", Long.valueOf(did));
            if (u != null) {
                Object bot = getObjectField(u, "bot");
                isBot = Boolean.TRUE.equals(bot);
            }
        }
    } catch (Throwable e) {}
    if (!isBot) {
        jlog("[候选] uid=" + did + " msg=" + t + "（非bot，不自动添加）");
        return;
    }
    String prefix = accountPrefix();
    prefs.edit().putString(prefix + "learned_" + did, t).commit();
    addTarget(did, t);
    jlog("【网络层自动学习】新目标 " + did + " -> " + t);
    toast("✅ 已自动添加新签到目标: " + t);
}

markSignedFromRequest(did, text) {
    try {
        if (text == null || !targetContains(did)) return;
        for (Object item : targets) {
            Map m = (Map) item;
            if (((Number) m.get("dialogId")).longValue() == did && text.equals(String.valueOf(m.get("text")))) {
                String prefix = accountPrefix();
                prefs.edit().putString(prefix + "last_" + did, todayStr()).commit();
                jlog("检测到签到消息已发出，标记今日已签 " + did);
                return;
            }
        }
    } catch (Throwable t) {}
}

// ---------------- 发送签到 ----------------
long backoffDelay(int retries) {
    long[] delays = {5L*60*1000, 15L*60*1000, 45L*60*1000, 2L*60*60*1000, 4L*60*60*1000};
    int idx = retries < delays.length ? retries : delays.length - 1;
    return delays[idx];
}

sendSign(dialogId, text) {
    if (pendingSigns.contains(dialogId)) {
        jlog("目标 " + dialogId + " 已有请求在处理中，跳过");
        return true;
    }
    int account = UserConfig.selectedAccount;
    MessagesController mc = MessagesController.getInstance(account);
    if (mc == null) {
        jlog("MessagesController 为空(account=$account)");
        return false;
    }
    Object user = callMethod(mc, "getUser", Long.valueOf(dialogId));
    if (user == null) {
        jlog("内存无缓存 " + dialogId + "，尝试从数据库读取");
        try {
            MessagesStorage ms = MessagesStorage.getInstance(account);
            if (ms != null) user = callMethod(ms, "getUser", dialogId);
        } catch (Throwable e) {
            jlog("数据库读取失败 " + dialogId + " : " + e);
        }
    }
    if (user == null) {
        jlog("未找到用户数据 " + dialogId + "（可能未缓存，稍后自动重试）");
        return false;
    }
    Object peer = MessagesController.getInputPeer(user);
    if (peer == null) {
        jlog("构造 InputPeer 失败 " + dialogId);
        return false;
    }
    TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
    req.peer = (TLRPC.InputPeer) peer;
    req.message = text;
    req.random_id = new Random().nextLong();
    pendingSigns.add(dialogId);
    try { prefs.edit().putLong(accountPrefix() + "sent_at_" + dialogId, System.currentTimeMillis()).commit(); } catch (Throwable e) {}
    ConnectionsManager cm = ConnectionsManager.getInstance(account);
    try {
        cm.sendRequest(req, new RequestDelegate() {
            public void run(TLObject response, TLRPC.TL_error error) {
                mainHandler.post(() -> { pendingSigns.remove(dialogId); });
                try {
                    if (error != null) {
                        String code = String.valueOf(error.code);
                        String text = error.text == null ? "" : String.valueOf(error.text);
                        String upper = text.toUpperCase();
                        String[] PERMANENT = {"PEER_ID_INVALID","USER_BOT_INVALID","CHAT_WRITE_FORBIDDEN","USER_ID_INVALID","AUTH_KEY_UNREGISTERED","MESSAGE_EMPTY","CHAT_ID_INVALID","PEER_ID_NOT_EXIST","USER_PRIVACY_RESTRICTED"};
                        boolean permanent = false;
                        for (String s : PERMANENT) {
                            if (upper.contains(s)) { permanent = true; break; }
                        }
                        if (permanent) {
                            prefs.edit().putString(accountPrefix() + "last_" + dialogId, todayStr())
                                 .putInt(accountPrefix() + "retry_" + dialogId, RETRY_LIMIT).commit();
                            jlog("签到永久失败 " + dialogId + " : " + text + "（今日放弃）");
                            toast("⚠️ 签到失败(" + text + ")，今日不再重试");
                        } else if (code.equals("420") || upper.startsWith("FLOOD_WAIT")) {
                            mainHandler.postDelayed(() -> { try { enqueueTry("限流重试"); } catch (Throwable e) {} }, 60000L);
                            jlog("签到遇限流 " + dialogId + " : " + text + "，60秒后自动重试");
                        } else {
                            int oldRetry = prefs.getInt(accountPrefix() + "retry_" + dialogId, 0);
                            prefs.edit().putInt(accountPrefix() + "retry_" + dialogId, oldRetry + 1)
                                 .putLong(accountPrefix() + "retry_at_" + dialogId, System.currentTimeMillis() + backoffDelay(oldRetry))
                                 .commit();
                            jlog("签到失败 " + dialogId + " : " + text + "（第" + (oldRetry + 1) + "次，退避重试）");
                            toast("⚠️ 签到失败: " + text + "，稍后自动重试");
                        }
                    } else {
                        prefs.edit()
                            .putString(accountPrefix() + "last_" + dialogId, todayStr())
                            .putInt(accountPrefix() + "retry_" + dialogId, 0)
                            .commit();
                        jlog("签到完成 " + dialogId + " text=" + text);
                        toast("✅ 签到成功: " + text);
                    }
                } catch (Throwable t) {}
            }
        });
        jlog("已发起签到请求 " + dialogId + " text=" + text);
        return true;
    } catch (Throwable t) {
        pendingSigns.remove(dialogId);
        jlog("发送异常 " + dialogId + " : " + t);
        return false;
    }
}

// ---------------- 补签 ----------------
trySignAll(reason) {
    long now = System.currentTimeMillis();
    if (now - lastTryTime < THROTTLE_MS) return;
    lastTryTime = now;
    if (!hasNetwork()) {
        jlog("[" + reason + "] 无网络，跳过，网络恢复后自动补");
        return;
    }
    String prefix = accountPrefix();
    String today = todayStr();
    boolean promptToday = reason != null && (reason.startsWith("启动") || "打开聊天".equals(reason) || "网络恢复".equals(reason));
    int signed = 0;
    int total = targets.size();
    for (Object item : targets) {
        Map m = (Map) item;
        long dialogId = ((Number) m.get("dialogId")).longValue();
        String text = String.valueOf(m.get("text"));
        try {
            String lastSign = prefs.getString(prefix + "last_" + dialogId, "");
            if (today.equals(lastSign)) { signed++; continue; }
            int retries = prefs.getInt(prefix + "retry_" + dialogId, 0);
            if (retries >= RETRY_LIMIT) {
                jlog("[" + reason + "] " + dialogId + " 今日重试已达上限");
                signed++;
                continue;
            }
            long retryAt = prefs.getLong(prefix + "retry_at_" + dialogId, 0);
            if (System.currentTimeMillis() < retryAt) {
                long mins = (retryAt - System.currentTimeMillis()) / 60000L;
                jlog("[" + reason + "] " + dialogId + " 退避中(剩" + mins + "分钟)，跳过");
                continue;
            }
            if (pendingSigns.contains(dialogId)) {
                jlog("[" + reason + "] " + dialogId + " 已在发送中，跳过");
                signed++;
                continue;
            }
            jlog("[" + reason + "] 尝试签到 " + dialogId + " text=" + text + " (重试" + retries + ")");
            sendSign(dialogId, text);
        } catch (Throwable t) {
            jlog("trySignAll 异常 " + dialogId + " : " + t);
        }
    }
    if (promptToday && total > 0 && signed == total) {
        jlog("[提示] 今天已全部签到完成，无需重复");
        toast("今天已经签到过了 ✅");
    }
}

enqueueTry(reason) {
    mainHandler.post(() -> {
        try { trySignAll(reason); } catch (Throwable t) { jlog("[" + reason + "] 异常: " + t); }
    });
}

hasNetwork() {
    try {
        ConnectivityManager cm = (ConnectivityManager) hostContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    } catch (Throwable t) {
        return true;
    }
}

// ---------------- 状态工具（界面用） ----------------
String statusOf(long did, String today) {
    String prefix = accountPrefix();
    String lastSign = prefs.getString(prefix + "last_" + did, "");
    if (today.equals(lastSign)) return "已签 ✅";
    int retries = prefs.getInt(prefix + "retry_" + did, 0);
    if (retries >= RETRY_LIMIT) return "已放弃 💤";
    long retryAt = prefs.getLong(prefix + "retry_at_" + did, 0);
    if (System.currentTimeMillis() < retryAt) return "退避中 ⏳";
    if (retries > 0) return "重试中 🔄";
    return "待签 ⏱";
}

int dp(float value) {
    return Math.max(1, (int) (android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, value, android.content.res.Resources.getSystem().getDisplayMetrics()) + 0.5f));
}

// 主题适配：深色模式白字，浅色模式黑字（TG AlertDialog 的自定义 View 不会自动继承文字色）
boolean isDarkMode(Context ctx) {
    try {
        int mode = ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    } catch (Throwable t) { return false; }
}
String txtMain(Context ctx) { return isDarkMode(ctx) ? "#F2F2F2" : "#1F1F1F"; }
String txtSub(Context ctx) { return isDarkMode(ctx) ? "#ABABAB" : "#757575"; }

// ---------------- 界面版：管理对话框（Telegram 风格） ----------------
// 注意：BSH 的 lambda 捕获有坑（同一作用域多个 lambda 会绑成最后一个），
// 回调全部用 【action 标签 + 匿名类 onClick + 分发函数】实现，杜绝捕获问题。

int dp(float value) {
    return Math.max(1, (int) (android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, value, android.content.res.Resources.getSystem().getDisplayMetrics()) + 0.5f));
}

// 主题适配：深色模式白字，浅色模式黑字（TG AlertDialog 的自定义 View 不会自动继承文字色）
boolean isDarkMode(Context ctx) {
    try {
        int mode = ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    } catch (Throwable t) { return false; }
}
String txtMain(Context ctx) { return isDarkMode(ctx) ? "#F2F2F2" : "#1F1F1F"; }
String txtSub(Context ctx) { return isDarkMode(ctx) ? "#ABABAB" : "#757575"; }

// 菜单行：emoji + 标题 + 副标题 + 箭头（action 为字符串指令，由 runAction 分发）
menuItem(LinearLayout parent, String emoji, String title, String subtitle, String action) {
    LinearLayout row = new LinearLayout(parent.getContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(11), dp(16), dp(11));
    row.setTag(action);
    row.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            runAction(v.getContext(), String.valueOf(v.getTag()));
        }
    });

    TextView em = new TextView(parent.getContext());
    em.setTextSize(20);
    em.setText(emoji);
    row.addView(em, new LinearLayout.LayoutParams(dp(40), -2));

    LinearLayout col = new LinearLayout(parent.getContext());
    col.setOrientation(LinearLayout.VERTICAL);
    TextView t1 = new TextView(parent.getContext());
    t1.setTextSize(15);
    t1.setTextColor(android.graphics.Color.parseColor(txtMain(parent.getContext())));
    t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    t1.setText(title);
    TextView t2 = new TextView(parent.getContext());
    t2.setTextSize(12);
    t2.setTextColor(android.graphics.Color.parseColor(txtSub(parent.getContext())));
    if (subtitle != null && subtitle.length() > 0) t2.setText(subtitle); else t2.setVisibility(8);
    col.addView(t1);
    col.addView(t2);
    row.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

    TextView arrow = new TextView(parent.getContext());
    arrow.setTextSize(18);
    arrow.setText("›");
    arrow.setTextColor(android.graphics.Color.parseColor(txtSub(parent.getContext())));
    row.addView(arrow);
    parent.addView(row);

    View div = new View(parent.getContext());
    div.setBackgroundColor(0x1A000000);
    parent.addView(div, new LinearLayout.LayoutParams(-1, 1));
    return row;
}

// 目标行：状态(payload 前半) + uid + 指令；action 可为 "delete" / "sign" / null
targetRow(LinearLayout parent, String status, long did, String text, String action) {
    LinearLayout row = new LinearLayout(parent.getContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(10), dp(16), dp(10));
    row.setTag(action + "|" + did + "|" + text);
    if (action != null) {
        row.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                String tag = String.valueOf(v.getTag());
                String[] parts = tag.split("\\|");
                runTargetAction(v.getContext(), parts[0], Long.parseLong(parts[1]), parts.length > 2 ? parts[2] : "");
            }
        });
    }
    TextView st = new TextView(parent.getContext());
    st.setTextSize(15);
    st.setText(status);
    row.addView(st, new LinearLayout.LayoutParams(dp(46), -2));

    LinearLayout col = new LinearLayout(parent.getContext());
    col.setOrientation(LinearLayout.VERTICAL);
    TextView t1 = new TextView(parent.getContext());
    t1.setTextSize(15);
    t1.setTextColor(android.graphics.Color.parseColor(txtMain(parent.getContext())));
    t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    t1.setText("uid: " + did);
    TextView t2 = new TextView(parent.getContext());
    t2.setTextSize(13);
    t2.setTextColor(android.graphics.Color.parseColor(txtSub(parent.getContext())));
    t2.setText("指令: " + text);
    col.addView(t1);
    col.addView(t2);
    row.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

    TextView arrow = new TextView(parent.getContext());
    arrow.setTextSize(18);
    arrow.setText("›");
    arrow.setTextColor(android.graphics.Color.parseColor(txtSub(parent.getContext())));
    row.addView(arrow);
    parent.addView(row);

    View div = new View(parent.getContext());
    div.setBackgroundColor(0x1A000000);
    parent.addView(div, new LinearLayout.LayoutParams(-1, 1));
    return row;
}

emptyView(LinearLayout parent, String text) {
    TextView tv = new TextView(parent.getContext());
    tv.setText(text);
    tv.setTextColor(android.graphics.Color.parseColor(txtSub(parent.getContext())));
    tv.setGravity(android.view.Gravity.CENTER);
    tv.setPadding(0, dp(24), 0, dp(24));
    parent.addView(tv);
    return tv;
}

EditText adInput(Activity act, String hint, int type) {
    EditText e = new EditText(act);
    e.setHint(hint);
    e.setTextSize(15);
    if (type == 1) e.setInputType(InputType.TYPE_CLASS_NUMBER);
    return e;
}

// 菜单动作分发（写死，避免 BSH lambda 捕获坑）
runAction(Context ctx, String action) {
    if (ctx == null || !(ctx instanceof Activity)) return;
    Activity act = (Activity) ctx;
    if ("list".equals(action)) { showList(act); return; }
    if ("add".equals(action)) { showAdd(act); return; }
    if ("del".equals(action)) { showDelete(act); return; }
    if ("sign".equals(action)) { showSign(act); return; }
    if ("log".equals(action)) { showLog(act); return; }
    if ("settings".equals(action)) { showSettings(act); return; }
}

runTargetAction(Context ctx, String action, long did, String text) {
    if (ctx == null || !(ctx instanceof Activity)) return;
    if ("delete".equals(action)) {
        String prefix = accountPrefix();
        prefs.edit()
            .remove(prefix + "learned_" + did)
            .remove(prefix + "last_" + did)
            .remove(prefix + "retry_" + did)
            .remove(prefix + "retry_at_" + did)
            .commit();
        for (int j = targets.size() - 1; j >= 0; j--) {
            Map mm = (Map) targets.get(j);
            if (((Number) mm.get("dialogId")).longValue() == did) targets.remove(j);
        }
        jlog("已删除目标 " + did);
        toast("已删除 " + did);
        showDelete((Activity) ctx);
        return;
    }
    if ("sign".equals(action)) {
        jlog("[界面] 手动签到 " + did);
        sendSign(did, text);
        toast("已命令签到 " + did);
        return;
    }
}

showMainMenu(Activity act) {
    if (act == null) { toast("请在 Telegram 界面使用 /jmb"); return; }
    String today = todayStr();
    int signed = 0;
    for (Object item : targets) {
        String prefix = accountPrefix();
        long did = ((Number) ((Map) item).get("dialogId")).longValue();
        if (today.equals(prefs.getString(prefix + "last_" + did, ""))) signed++;
    }
    LinearLayout menu = new LinearLayout(act);
    menu.setOrientation(LinearLayout.VERTICAL);
    // 右上角署名
    TextView cred = new TextView(act);
    cred.setText("by wlmosv");
    cred.setTextSize(12);
    cred.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
    cred.setGravity(android.view.Gravity.END);
    cred.setPadding(dp(16), 0, dp(16), dp(6));
    menu.addView(cred);
    menuItem(menu, "📋", "目标列表", "共 " + targets.size() + " 个 · 已签 " + signed, "list");
    menuItem(menu, "➕", "添加目标", "bot ID + 签到指令，立即执行", "add");
    menuItem(menu, "🗑", "删除目标", "从自动签到移除", "del");
    menuItem(menu, "🚀", "立即签到", "手动触发一次签到", "sign");
    menuItem(menu, "📄", "运行日志", "最近 200 行", "log");
    menuItem(menu, "⚙️", "设置", "关键词 / 重试上限", "settings");

    org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(act);
    b.setTitle("TGAutoSign · 管理");
    b.setView(menu);
    b.setNegativeButton("关闭", null);
    try { b.create().show(); } catch (Throwable t) { jlog("菜单显示失败: " + t); }
}

showList(Activity act) {
    LinearLayout box = new LinearLayout(act);
    box.setOrientation(LinearLayout.VERTICAL);
    String today = todayStr();
    if (targets.size() == 0) {
        emptyView(box, "暂无目标\n在机器人的聊天里点一次签到按钮即可自动学习，或返回点“添加目标”");
    } else {
        for (Object item : targets) {
            Map m = (Map) item;
            long did = ((Number) m.get("dialogId")).longValue();
            String text = String.valueOf(m.get("text"));
            String status = statusOf(did, today);
            targetRow(box, status, did, text, null);
        }
    }
    Button back = new Button(act);
    back.setText("← 返回主菜单");
    back.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) { showMainMenu(act); }
    });
    box.addView(back);
    org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(act);
    b.setTitle("签到目标");
    b.setView(box);
    b.setNegativeButton("关闭", null);
    try { b.create().show(); } catch (Throwable t) { jlog("列表显示失败: " + t); toast("列表打开失败: " + t); }
}

showAdd(Activity act) {
    LinearLayout box = new LinearLayout(act);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(8), dp(16), dp(8));
    EditText uid = adInput(act, "机器人 ID（数字，无需 @）", 1);
    EditText cmd = adInput(act, "签到指令，如：/qd 或 📅 签到", 0);
    box.addView(uid);
    box.addView(cmd);
    Button ok = new Button(act);
    ok.setText("添加并立即签到");
    ok.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            try {
                long did = Long.parseLong(uid.getText().toString().trim());
                String t = cmd.getText().toString().trim();
                if (t.length() == 0) { toast("指令不能为空"); return; }
                learnTarget(did, t);
                toast("✅ 已添加 " + did + " → " + t + "，立即签到…");
                sendSign(did, t);
            } catch (Throwable e) {
                toast("UID 格式错误");
            }
        }
    });
    box.addView(ok);
    org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(act);
    b.setTitle("添加签到目标");
    b.setView(box);
    b.setNegativeButton("取消", null);
    try { b.create().show(); } catch (Throwable t) { jlog("添加框失败: " + t); toast("添加框打开失败: " + t); }
}

showDelete(Activity act) {
    if (targets.size() == 0) { toast("暂无目标可删除"); return; }
    LinearLayout box = new LinearLayout(act);
    box.setOrientation(LinearLayout.VERTICAL);
    for (Object item : targets) {
        Map m = (Map) item;
        long did = ((Number) m.get("dialogId")).longValue();
        String text = String.valueOf(m.get("text"));
        targetRow(box, "🗑", did, text, "delete");
    }
    org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(act);
    b.setTitle("点选要删除的目标");
    b.setView(box);
    b.setNegativeButton("取消", null);
    try { b.create().show(); } catch (Throwable t) { jlog("删除框失败: " + t); }
}

showSign(Activity act) {
    if (targets.size() == 0) { toast("暂无目标"); return; }
    LinearLayout box = new LinearLayout(act);
    box.setOrientation(LinearLayout.VERTICAL);
    for (Object item : targets) {
        Map m = (Map) item;
        long did = ((Number) m.get("dialogId")).longValue();
        String text = String.valueOf(m.get("text"));
        String status = statusOf(did, todayStr());
        targetRow(box, "🚀", did, text, "sign");
    }
    org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(act);
    b.setTitle("点选立即签到");
    b.setView(box);
    b.setNegativeButton("取消", null);
    try { b.create().show(); } catch (Throwable t) { jlog("签到框失败: " + t); }
}

showLog(Activity act) {
    try {
        StringBuilder sb = new StringBuilder();
        List copy = null;
        synchronized (logBuffer) { copy = new ArrayList(logBuffer); }
        for (Object line : copy) sb.append(String.valueOf(line)).append("\n");
        String content = sb.toString();
        if (content.length() > 4000) content = content.substring(content.length() - 4000);
        if (content.length() == 0) content = "(暂无日志)";
        ScrollView sv = new ScrollView(act);
        TextView tv = new TextView(act);
        tv.setText(content);
        tv.setTextSize(12);
        tv.setTextColor(android.graphics.Color.parseColor(txtMain(act)));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        sv.addView(tv);
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(act);
        b.setTitle("运行日志（最近 200 行）");
        b.setView(sv);
        b.setNegativeButton("关闭", null);
        b.create().show();
    } catch (Throwable t) {
        jlog("日志框失败: " + t);
        toast("日志打开失败: " + t);
    }
}

showSettings(Activity act) {
    try {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText kw = adInput(act, "学习关键词（逗号分隔）", 0);
        kw.setText(LEARN_KEYWORDS == null ? "" : String.valueOf(LEARN_KEYWORDS));
        EditText rl = adInput(act, "每日重试上限", 1);
        rl.setText(String.valueOf(RETRY_LIMIT));
        box.addView(kw);
        box.addView(rl);
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(act);
        b.setTitle("设置");
        b.setView(box);
        b.setNegativeButton("取消", null);
        b.create().show();
    } catch (Throwable t) {
        jlog("设置框失败: " + t);
        toast("设置打开失败: " + t);
    }
}

// 命令入口：拦截用户发送的 /jmb 开头消息
boolean handleCommand(String text) {
    String t = String.valueOf(text).trim();
    if (!t.startsWith("/jmb")) return false;
    jlog("[界面] 收到管理命令: " + t);
    mainHandler.post(() -> {
        try {
            showMainMenu(lastActivity);
        } catch (Throwable e) {
            jlog("打开管理菜单失败: " + e);
        }
    });
    return true;
}

// ---------------- Hook ----------------
// 触发源 1：UI 按钮点击学习
try {
    hookAllMethodsBefore(
        Class.forName("org.telegram.ui.Components.ChatActivityEnterView", true, hostLoader),
        "didPressedBotButton",
        param -> {
            try {
                Object proto = param.args[0];
                Object mo = param.args.length > 2 ? param.args[2] : null;
                if (proto == null) return;
                Object did = null;
                if (mo != null) { try { did = callMethod(mo, "getDialogId"); } catch (Throwable e) {} }
                Object text = null;
                try { text = getObjectField(proto, "text"); } catch (Throwable e) {}
                if (did != null && text != null) {
                    learnTarget(((Number) did).longValue(), String.valueOf(text));
                }
            } catch (Throwable t) {}
        }
    );
    hookAllMethodsBefore(
        Class.forName("org.telegram.ui.ChatActivity$ChatMessageCellDelegate", true, hostLoader),
        "didPressBotButton",
        param -> {
            try {
                Object cell = param.args.length > 0 ? param.args[0] : null;
                Object proto = param.args.length > 1 ? param.args[1] : null;
                Object mo = null;
                if (cell != null) { try { mo = callMethod(cell, "getMessageObject"); } catch (Throwable e) {} }
                Object did = null;
                if (mo != null) { try { did = callMethod(mo, "getDialogId"); } catch (Throwable e) {} }
                Object text = null;
                try { text = getObjectField(proto, "text"); } catch (Throwable e) {}
                if (did != null && text != null) {
                    learnTarget(((Number) did).longValue(), String.valueOf(text));
                }
            } catch (Throwable t) {}
        }
    );
} catch (Throwable t) {
    jlog("按钮学习 Hook 失败: " + t);
}

// 触发源 2：网络活动（学习/已签标记/补签/命令拦截）
try {
    hookAllMethodsBefore(
        Class.forName("org.telegram.tgnet.ConnectionsManager", true, hostLoader),
        "sendRequest",
        param -> {
            try {
                Object req = param.args[0];
                if (req != null) {
                    String rn = req.getClass().getName();
                    if (rn.contains("TL_messages_getBotCallbackAnswer")) {
                        try {
                            Object peer = getObjectField(req, "peer");
                            Object uid = null;
                            if (peer != null) { try { uid = getObjectField(peer, "user_id"); } catch (Throwable e) {} }
                            if (uid != null) {
                                Object data = getObjectField(req, "data");
                                jlog("[回调按钮] uid=" + uid + " data=" + data + " → 回调型按钮，自动签到暂不支持");
                                toast("ℹ️ 该机器人使用回调按钮，自动签到暂不支持");
                            }
                        } catch (Throwable e) {}
                    } else if (rn.contains("TL_messages_sendMessage")) {
                        Object peer = getObjectField(req, "peer");
                        Object msg = getObjectField(req, "message");
                        // [界面版] 管理命令拦截
                        if (msg != null && String.valueOf(msg).trim().startsWith("/jmb")) {
                            param.skipWith(null);   // 吞掉管理命令，不发送
                            handleCommand(String.valueOf(msg));
                            return;
                        }
                        Object uid = null;
                        if (peer != null) { try { uid = getObjectField(peer, "user_id"); } catch (Throwable e) {} }
                        if (uid != null) {
                            final Object fUid = uid;
                            final Object fMsg = msg;
                            mainHandler.post(() -> {
                                try {
                                    long u = ((Number) fUid).longValue();
                                    String t = String.valueOf(fMsg);
                                    if (targetContains(u)) {
                                        markSignedFromRequest(u, t);
                                    } else {
                                        learnFromNetwork(u, t);
                                    }
                                } catch (Throwable e2) {}
                            });
                        }
                    }
                }
                enqueueTry("TG活动");
            } catch (Throwable t) {
                jlog("[活动] 异常: " + t);
            }
        }
    );
} catch (Throwable t) {
    jlog("网络 Hook 失败: " + t);
}

// 触发源 3：LaunchActivity 记录对话框宿主（TG 的 Activity 是 LaunchActivity，ChatActivity 不是 Activity！）
try {
    hookAllMethodsBefore(
        Class.forName("org.telegram.ui.LaunchActivity", true, hostLoader),
        "onResume",
        param -> {
            try {
                if (param.thisObject instanceof Activity) {
                    lastActivity = (Activity) param.thisObject;
                    jlog("[界面] LaunchActivity 已记录，可弹管理菜单");
                }
            } catch (Throwable t) {}
        }
    );
} catch (Throwable t) {
    jlog("LaunchActivity Hook 失败: " + t);
}

// 触发源 3.1：打开聊天补签
try {
    hookAllMethodsBefore(
        Class.forName("org.telegram.ui.ChatActivity", true, hostLoader),
        "onResume",
        param -> {
            try {
                enqueueTry("打开聊天");
            } catch (Throwable t) {}
        }
    );
} catch (Throwable t) {
    jlog("ChatActivity Hook 失败: " + t);
}

// 触发源 3.5：bot 回复语义判定（失败撤销 + 退避重试）
try {
    hookAllMethodsBefore(
        Class.forName("org.telegram.messenger.MessagesController", true, hostLoader),
        "processUpdate",
        param -> {
            try {
                Object update = param.args.length > 0 ? param.args[0] : null;
                if (update == null) return;
                String un = update.getClass().getName();
                if (!un.contains("TL_updateNewMessage") && !un.contains("TL_updateNewChannelMessage")) return;
                Object msg = getObjectField(update, "message");
                if (msg == null) return;
                long peerUid = -1, fromUid = -1;
                Object peerId = null, fromId = null;
                try { peerId = getObjectField(msg, "peer_id"); } catch (Throwable e) {}
                try { fromId = getObjectField(msg, "from_id"); } catch (Throwable e) {}
                try {
                    Object pu = peerId != null ? getObjectField(peerId, "user_id") : null;
                    if (pu != null) peerUid = ((Number) pu).longValue();
                } catch (Throwable e) {}
                try {
                    Object fu = fromId != null ? getObjectField(fromId, "user_id") : null;
                    if (fu != null) fromUid = ((Number) fu).longValue();
                } catch (Throwable e) {}
                if (peerUid <= 0 || fromUid != peerUid) return;
                if (!targetContains(peerUid)) return;
                Object mtext = null;
                try { mtext = getObjectField(msg, "message"); } catch (Throwable e) {}
                if (mtext == null) return;
                final String replyText = String.valueOf(mtext);
                final long did = peerUid;
                if (replyText.length() == 0) return;
                mainHandler.post(() -> {
                    try {
                        long sentAt = prefs.getLong(accountPrefix() + "sent_at_" + did, 0);
                        if (System.currentTimeMillis() - sentAt > 10L * 60 * 1000) return;
                        String lower = replyText.toLowerCase();
                        String[] failWords = {"失败","未成功","请先","不能","无法","不可","错误","已过期","未关注","没有资格","failed","invalid","rejected","not allowed","try again"};
                        for (String w : failWords) {
                            if (lower.contains(w)) {
                                int cur = prefs.getInt(accountPrefix() + "retry_" + did, 0);
                                prefs.edit()
                                    .remove(accountPrefix() + "last_" + did)
                                    .putInt(accountPrefix() + "retry_" + did, cur + 1)
                                    .putLong(accountPrefix() + "retry_at_" + did, System.currentTimeMillis() + backoffDelay(1))
                                    .commit();
                                jlog("【回复判定】" + did + " bot 回复: " + replyText + " → 判定未成功，撤销已签并安排重试");
                                toast("⚠️ " + did + " 可能未签到成功: " + replyText);
                                return;
                            }
                        }
                    } catch (Throwable e2) {}
                });
            } catch (Throwable t) {}
        }
    );
} catch (Throwable t) {
    jlog("回复判定 Hook 失败: " + t);
}

// 触发源 4：网络恢复
try {
    if (!receiverRegistered) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                enqueueTry("网络恢复");
            }
        };
        hostContext.registerReceiver(receiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        receiverRegistered = true;
    }
} catch (Throwable t) {}

// ---------------- 启动 ----------------
try {
    // 从持久化读回设置
    if (prefs.contains("jmb_keywords")) LEARN_KEYWORDS = prefs.getString("jmb_keywords", LEARN_KEYWORDS);
    if (prefs.contains("jmb_retry")) RETRY_LIMIT = prefs.getInt("jmb_retry", RETRY_LIMIT);
} catch (Throwable t) {}

loadTargets();
jlog("=== jmb界面版 v1.0 已加载 ===");
jlog("当前账号: " + UserConfig.selectedAccount + "，目标数: " + targets.size());
jlog("使用: 在任意聊天输入 /jmb 打开管理界面");
toast("TGAutoSign 界面版已运行：发 /jmb 管理");

// 10 秒后启动补签 + 每 30 分钟轮询（账号切换感知）
mainHandler.postDelayed(() -> {
    try {
        jlog("=== 启动立即补签 ===");
        trySignAll("启动立即");
    } catch (Throwable t) {}
}, 10000L);

schedulePoll() {
    mainHandler.postDelayed(() -> {
        try {
            if (lastAccount != UserConfig.selectedAccount) {
                lastAccount = UserConfig.selectedAccount;
                loadTargets();
                jlog("检测到账号切换 -> acc" + lastAccount + "，已重载目标");
            }
            enqueueTry("定时");
            schedulePoll();
        } catch (Throwable t) {}
    }, POLL_INTERVAL_MS);
}
schedulePoll();

jlog("jmb界面版 初始化完成");