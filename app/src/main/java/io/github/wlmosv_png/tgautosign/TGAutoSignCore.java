package io.github.wlmosv_png.tgautosign;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
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

/**
 * TGAutoSignCore —— 由 jmb界面版/main.java（LSPilot 插件）1:1 翻译而来。
 *  - 存储与旧插件共用 SharedPreferences("tg_autosign_gen")，键 acc{account}_learned_<did> 等
 *  - 交互全部在 Telegram 进程内完成（模块 hook Telegram）
 *  - 对话框使用 TG 风格（org.telegram.ui.ActionBar.AlertDialog），反射构造
 */
public final class TGAutoSignCore {
    private static final String TAG = "TGAutoSignModule";

    // ---------------- 配置 ----------------
    private final Map<String, Object> BUILTIN_TARGETS = new HashMap<>();
    private boolean LEARN_ENABLED = true;
    private boolean AUTO_LEARN_NET = true;
    private long THROTTLE_MS = 60L * 1000L;
    private long POLL_INTERVAL_MS = 30L * 60L * 1000L;
    private int RETRY_LIMIT = 5;
    private String LEARN_KEYWORDS = "签到,打卡,checkin,claim,领取,签到领,/qd,/qiandao,/sign";

    private final Context appContext;
    private final ClassLoader cl;
    private final SharedPreferences prefs;
    private final Set<String> seenSignals = new HashSet<>();
    private long lastSeenClean = 0L;
    private final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final List<Map<String, Object>> targets = new ArrayList<>();
    private long lastTryTime = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<Long> pendingSigns = new HashSet<>();
    private boolean receiverRegistered = false;
    private int lastAccount = -1;

    // 界面版：最近的前台 Activity（用于弹管理对话框）
    private volatile Activity lastActivity = null;
    // 日志环形缓冲（最近 200 行）
    private final List<String> logBuffer = new ArrayList<>();

    private final Random random = new Random();

    public TGAutoSignCore(Context appContext, ClassLoader cl) {
        this.appContext = appContext.getApplicationContext() != null ? appContext.getApplicationContext() : appContext;
        this.cl = cl;
        this.prefs = this.appContext.getSharedPreferences("tg_autosign_gen", 0);
    }

    public void start() {
        loadTargets();
        // 从持久化读回设置
        try {
            if (prefs.contains("jmb_keywords")) LEARN_KEYWORDS = prefs.getString("jmb_keywords", LEARN_KEYWORDS);
            if (prefs.contains("jmb_retry")) RETRY_LIMIT = prefs.getInt("jmb_retry", RETRY_LIMIT);
        } catch (Throwable ignored) {}
        registerNetworkReceiver();
        registerActivityListener();
        mainHandler.postDelayed(() -> { try { jlog("=== 启动立即补签 ==="); trySignAll("启动立即", true); } catch (Throwable ignored) {} }, 10000L);
        schedulePoll();
        jlog("=== jmb界面版 v1.0 (模块) 已加载 ===");
        jlog("当前账号: " + currentAccount() + "，目标数: " + targets.size());
        jlog("使用: 在任意聊天输入 /jmb 打开管理界面");
        toast("TGAutoSign 界面版已运行：发 /jmb 管理");
        jlog("TGAutoSignCore v2.2 started, targets=" + targets.size());
    }

    // ---------------- 工具 ----------------
    private String todayStr() { return SDF.format(new Date()); }

    private String accountPrefix() { return "acc" + currentAccount() + "_"; }

    private int currentAccount() {
        try {
            Object v = getFieldVal(null, classEx("org.telegram.messenger.UserConfig"), "selectedAccount");
            return ((Number) v).intValue();
        } catch (Throwable t) { return 0; }
    }

    void toast(String msg) {
        try {
            mainHandler.post(() -> {
                try { Toast.makeText(appContext, String.valueOf(msg), Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    void jlog(String msg) {
        try {
            Log.i(TAG, msg);
            synchronized (logBuffer) {
                logBuffer.add(SDF.format(new Date()) + " " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "] " + msg);
                while (logBuffer.size() > 200) logBuffer.remove(0);
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- 目标管理（与旧插件同键，数据共享） ----------------
    private void addTarget(long dialogId, String text) {
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == dialogId) return;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("dialogId", dialogId);
        m.put("text", text);
        targets.add(m);
        jlog("已登记签到目标 " + dialogId + " -> " + text);
    }

    private boolean targetContains(long did) {
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == did) return true;
        }
        return false;
    }

    private void loadTargets() {
        targets.clear();
        try {
            String prefix = accountPrefix();
            Map<String, ?> all = prefs.getAll();
            for (String key : all.keySet()) {
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

    private void learnTarget(long dialogId, String text) {
        if (!LEARN_ENABLED) return;
        if (text == null || text.length() == 0) return;
        if (dialogId <= 0) {
            jlog("忽略群聊学习: dialogId=" + dialogId);
            return;
        }
        if (targetContains(dialogId)) {
            for (Map<String, Object> m : targets) {
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

    private void learnFromNetwork(long did, String text) {
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
            jlog("[候选] uid=" + did + " msg=" + t + "（非bot，不自动添加）");
            return;
        }
        String prefix = accountPrefix();
        prefs.edit().putString(prefix + "learned_" + did, t).commit();
        addTarget(did, t);
        jlog("【网络层自动学习】新目标 " + did + " -> " + t);
        toast("✅ 已自动添加新签到目标: " + t);
    }

    private void markSignedFromRequest(long did, String text) {
        try {
            if (text == null || !targetContains(did)) return;
            for (Map<String, Object> m : targets) {
                if (((Number) m.get("dialogId")).longValue() == did && text.equals(String.valueOf(m.get("text")))) {
                    String prefix = accountPrefix();
                    prefs.edit().putString(prefix + "last_" + did, todayStr()).commit();
                    jlog("检测到签到消息已发出，标记今日已签 " + did);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- 发送签到 ----------------
    private long backoffDelay(int retries) {
        long[] delays = {5L*60*1000, 15L*60*1000, 45L*60*1000, 2L*60*60*1000, 4L*60*60*1000};
        int idx = retries < delays.length ? retries : delays.length - 1;
        return delays[idx];
    }

    private void sendSign(long dialogId, String text) {
        if (pendingSigns.contains(dialogId)) {
            jlog("目标 " + dialogId + " 已有请求在处理中，跳过");
            return;
        }
        int account = currentAccount();
        Object mc = getMessagesController();
        if (mc == null) {
            jlog("MessagesController 为空(account=" + account + ")");
            return;
        }
        Object user;
        try { user = invoke(mc, "getUser", new Class<?>[]{Long.class}, new Object[]{dialogId}); }
        catch (Throwable t) { user = null; }
        if (user == null) {
            jlog("内存无缓存 " + dialogId + "，尝试从数据库读取");
            try {
                Object ms = getMessagesStorage();
                if (ms != null) user = invoke(ms, "getUser", new Class<?>[]{long.class}, new Object[]{dialogId});
            } catch (Throwable e) {
                jlog("数据库读取失败 " + dialogId + " : " + e);
            }
        }
        if (user == null) {
            jlog("未找到用户数据 " + dialogId + "（可能未缓存，稍后自动重试）");
            return;
        }
        Object peer;
        try { peer = staticInvoke(classEx("org.telegram.messenger.MessagesController"), "getInputPeer", new Class<?>[]{classEx("org.telegram.tgnet.TLObject")}, new Object[]{user}); }
        catch (Throwable t) { peer = null; }
        if (peer == null) {
            jlog("构造 InputPeer 失败 " + dialogId);
            return;
        }
        try {
            Class<?> sendCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_sendMessage");
            Object req = sendCls.newInstance();
            setFieldVal(req, "peer", peer);
            setFieldVal(req, "message", text);
            setFieldVal(req, "random_id", random.nextLong());
            pendingSigns.add(dialogId);
            try { prefs.edit().putLong(accountPrefix() + "sent_at_" + dialogId, System.currentTimeMillis()).commit(); } catch (Throwable ignored) {}
            Object cm = staticInvoke(classEx("org.telegram.tgnet.ConnectionsManager"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
            final long fDid = dialogId;
            final String fText = text;
            final Object delegate = newRequestDelegate(new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] margs) {
                    if ("run".equals(method.getName()) && margs != null && margs.length >= 2) {
                        final Object response = margs[0];
                        final Object error = margs[1];
                        mainHandler.post(() -> { pendingSigns.remove(fDid); });
                        try {
                            if (error != null) {
                                String code = "";
                                try { code = String.valueOf(getFieldVal(error, "code")); } catch (Throwable ignored) {}
                                String text = "";
                                try { text = String.valueOf(getFieldVal(error, "text")); } catch (Throwable ignored) {}
                                String upper = text.toUpperCase();
                                final String[] PERMANENT = {"PEER_ID_INVALID","USER_BOT_INVALID","CHAT_WRITE_FORBIDDEN","USER_ID_INVALID","AUTH_KEY_UNREGISTERED","MESSAGE_EMPTY","CHAT_ID_INVALID","PEER_ID_NOT_EXIST","USER_PRIVACY_RESTRICTED"};
                                boolean permanent = false;
                                for (String s : PERMANENT) {
                                    if (upper.contains(s)) { permanent = true; break; }
                                }
                                if (permanent) {
                                    prefs.edit().putString(accountPrefix() + "last_" + fDid, todayStr())
                                         .putInt(accountPrefix() + "retry_" + fDid, RETRY_LIMIT).commit();
                                    jlog("签到永久失败 " + fDid + " : " + text + "（今日放弃）");
                                    toast("⚠️ 签到失败(" + text + ")，今日不再重试");
                                } else if (code.equals("420") || upper.startsWith("FLOOD_WAIT")) {
                                    mainHandler.postDelayed(() -> { try { enqueueTry("限流重试"); } catch (Throwable ignored) {} }, 60000L);
                                    jlog("签到遇限流 " + fDid + " : " + text + "，60秒后自动重试");
                                } else {
                                    int oldRetry = prefs.getInt(accountPrefix() + "retry_" + fDid, 0);
                                    prefs.edit().putInt(accountPrefix() + "retry_" + fDid, oldRetry + 1)
                                         .putLong(accountPrefix() + "retry_at_" + fDid, System.currentTimeMillis() + backoffDelay(oldRetry))
                                         .commit();
                                    jlog("签到失败 " + fDid + " : " + text + "（第" + (oldRetry + 1) + "次，退避重试）");
                                    toast("⚠️ 签到失败: " + text + "，稍后自动重试");
                                }
                            } else {
                                prefs.edit()
                                    .putString(accountPrefix() + "last_" + fDid, todayStr())
                                    .putInt(accountPrefix() + "retry_" + fDid, 0)
                                    .commit();
                                jlog("签到完成 " + fDid + " text=" + fText);
                                toast("✅ 签到成功: " + fText);
                            }
                        } catch (Throwable ignored) {}
                    }
                    return null;
                }
            });
            invoke(cm, "sendRequest", new Class<?>[]{classEx("org.telegram.tgnet.TLObject"), classEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
            jlog("已发起签到请求 " + dialogId + " text=" + text);
        } catch (Throwable t) {
            pendingSigns.remove(dialogId);
            jlog("发送异常 " + dialogId + " : " + t);
        }
    }

    // ---------------- 补签 ----------------
    void trySignAll(String reason, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastTryTime < THROTTLE_MS) {
            jlog("[" + reason + "] 节流内跳过");
            return;
        }
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
        for (Map<String, Object> m : targets) {
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
        jlog("[" + reason + "] 检查完成 目标=" + total + " 已签=" + signed + " 处理=" + (total - signed));
        if (promptToday && total > 0 && signed == total) {
            jlog("[提示] 今天已全部签到完成，无需重复");
            toast("今天已经签到过了 ✅");
        }
    }

    public void enqueueTry(String reason) {
        mainHandler.post(() -> { try { trySignAll(reason, false); } catch (Throwable t) { jlog("[" + reason + "] 异常: " + t); } });
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------- 状态工具（界面用） ----------------
    private String statusOf(long did, String today) {
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

    private int dp(float value) {
        return Math.max(1, (int) (android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, value, appContext.getResources().getDisplayMetrics()) + 0.5f));
    }

    private boolean isDarkMode(Context ctx) {
        try {
            int mode = ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) { return false; }
    }

    private String txtMain(Context ctx) { return isDarkMode(ctx) ? "#F2F2F2" : "#1F1F1F"; }
    private String txtSub(Context ctx) { return isDarkMode(ctx) ? "#ABABAB" : "#757575"; }

    // ---------------- 界面版：管理对话框（Telegram 风格） ----------------
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
        t1.setTextColor(android.graphics.Color.parseColor(txtMain(c)));
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t1.setText(title);
        TextView t2 = new TextView(c);
        t2.setTextSize(12);
        t2.setTextColor(android.graphics.Color.parseColor(txtSub(c)));
        if (subtitle != null && subtitle.length() > 0) t2.setText(subtitle); else t2.setVisibility(View.GONE);
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView arrow = new TextView(c);
        arrow.setTextSize(18);
        arrow.setText("›");
        arrow.setTextColor(android.graphics.Color.parseColor(txtSub(c)));
        row.addView(arrow);
        parent.addView(row);
        View div = new View(c);
        div.setBackgroundColor(0x1A000000);
        parent.addView(div, new LinearLayout.LayoutParams(-1, 1));
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
                String tag = String.valueOf(v.getTag());
                String[] parts = tag.split("\\|");
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
        t1.setTextColor(android.graphics.Color.parseColor(txtMain(c)));
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t1.setText("uid: " + did);
        TextView t2 = new TextView(c);
        t2.setTextSize(13);
        t2.setTextColor(android.graphics.Color.parseColor(txtSub(c)));
        t2.setText("指令: " + text);
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView arrow = new TextView(c);
        arrow.setTextSize(18);
        arrow.setText("›");
        arrow.setTextColor(android.graphics.Color.parseColor(txtSub(c)));
        row.addView(arrow);
        parent.addView(row);
        View div = new View(c);
        div.setBackgroundColor(0x1A000000);
        parent.addView(div, new LinearLayout.LayoutParams(-1, 1));
        return row;
    }

    private void emptyView(LinearLayout parent, String text) {
        TextView tv = new TextView(parent.getContext());
        tv.setText(text);
        tv.setTextColor(android.graphics.Color.parseColor(txtSub(parent.getContext())));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(24), 0, dp(24));
        parent.addView(tv);
    }

    private EditText adInput(Activity act, String hint, int type) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setTextSize(15);
        if (type == 1) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private void showDialog(Activity act, String title, View view, String negLabel) {
        try {
            Object b = tgBuilder(act);
            call(b, "setTitle", new Class<?>[]{CharSequence.class}, new Object[]{title});
            call(b, "setView", new Class<?>[]{View.class}, new Object[]{view});
            // TG 的 Builder.setNegativeButton（找不到/签名不符时忽略，TG 对话框仍可显示）
            Object d;
            try {
                call(b, "setNegativeButton", new Class<?>[]{CharSequence.class, android.content.DialogInterface.OnClickListener.class}, new Object[]{negLabel, null});
            } catch (Throwable e1) {
                jlog("[对话框] setNegativeButton 未找到(忽略): " + e1);
                try { call(b, "setCancelable", new Class<?>[]{boolean.class}, new Object[]{true}); } catch (Throwable ignored) {}
            }
            d = call(b, "create", new Class<?>[0], new Object[0]);
            if (d != null) call(d, "show", new Class<?>[0], new Object[0]);
            jlog("[对话框] TG 风格对话框成功: " + title);
            return;
        } catch (Throwable t) {
            jlog("[对话框] TG 对话框失败，回退系统框: " + t);
        }
        // 兜底：尽量接近 TG 深色卡片风格
        try {
            android.app.AlertDialog.Builder ab = new android.app.AlertDialog.Builder(act);
            ab.setTitle(title);
            ab.setView(view);
            ab.setNegativeButton(negLabel, null);
            android.app.AlertDialog ad = ab.create();
            if (ad.getWindow() != null) {
                ad.getWindow().setBackgroundDrawable(new android.graphics.drawable.GradientDrawable() {{
                    setColor(0xFF202124);
                    setCornerRadius(dp(12));
                }});
            }
            ad.show();
            jlog("[对话框] 已用兜底系统框: " + title);
        } catch (Throwable t2) {
            jlog("对话框显示失败: " + t2);
        }
    }

    private void runAction(Context ctx, String action) {
        if (ctx == null || !(ctx instanceof Activity)) return;
        Activity act = (Activity) ctx;
        if ("list".equals(action)) { showList(act); return; }
        if ("add".equals(action)) { showAdd(act); return; }
        if ("del".equals(action)) { showDelete(act); return; }
        if ("sign".equals(action)) { showSign(act); return; }
        if ("log".equals(action)) { showLog(act); return; }
        if ("settings".equals(action)) { showSettings(act); return; }
    }

    private void runTargetAction(Context ctx, String action, long did, String text) {
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
                Map<String, Object> mm = targets.get(j);
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
        }
    }

    private void showMainMenu(Activity act) {
        if (act == null) { toast("请在 Telegram 界面使用 /jmb"); return; }
        String today = todayStr();
        int signed = 0;
        for (Map<String, Object> m : targets) {
            long did = ((Number) m.get("dialogId")).longValue();
            if (today.equals(prefs.getString(accountPrefix() + "last_" + did, ""))) signed++;
        }
        LinearLayout menu = new LinearLayout(act);
        menu.setOrientation(LinearLayout.VERTICAL);
        TextView cred = new TextView(act);
        cred.setText("by wlmosv");
        cred.setTextSize(12);
        cred.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        cred.setGravity(Gravity.END);
        cred.setPadding(dp(16), 0, dp(16), dp(6));
        menu.addView(cred);
        menuItem(menu, "📋", "目标列表", "共 " + targets.size() + " 个 · 已签 " + signed, "list");
        menuItem(menu, "➕", "添加目标", "bot ID + 签到指令，立即执行", "add");
        menuItem(menu, "🗑", "删除目标", "从自动签到移除", "del");
        menuItem(menu, "🚀", "立即签到", "手动触发一次签到", "sign");
        menuItem(menu, "📄", "运行日志", "最近 200 行", "log");
        menuItem(menu, "⚙️", "设置", "关键词 / 重试上限", "settings");
        showDialog(act, "TGAutoSign · 管理", menu, "关闭");
    }

    private void showList(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        String today = todayStr();
        if (targets.size() == 0) {
            emptyView(box, "暂无目标\n在机器人的聊天里点一次签到按钮即可自动学习，或返回点“添加目标”");
        } else {
            for (Map<String, Object> m : targets) {
                long did = ((Number) m.get("dialogId")).longValue();
                String text = String.valueOf(m.get("text"));
                String status = statusOf(did, today);
                targetRow(box, status, did, text, null);
            }
        }
        Button back = new Button(act);
        back.setText("← 返回主菜单");
        back.setOnClickListener(v -> showMainMenu(act));
        box.addView(back);
        showDialog(act, "签到目标", box, "关闭");
    }

    private void showAdd(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText uid = adInput(act, "机器人 ID（数字，无需 @）", 1);
        EditText cmd = adInput(act, "签到指令，如：/qd 或 📅 签到", 0);
        box.addView(uid);
        box.addView(cmd);
        Button ok = new Button(act);
        ok.setText("添加并立即签到");
        ok.setOnClickListener(v -> {
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
        });
        box.addView(ok);
        showDialog(act, "添加签到目标", box, "取消");
    }

    private void showDelete(Activity act) {
        if (targets.size() == 0) { toast("暂无目标可删除"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        for (Map<String, Object> m : targets) {
            long did = ((Number) m.get("dialogId")).longValue();
            String text = String.valueOf(m.get("text"));
            targetRow(box, "🗑", did, text, "delete");
        }
        showDialog(act, "点选要删除的目标", box, "取消");
    }

    private void showSign(Activity act) {
        if (targets.size() == 0) { toast("暂无目标"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        for (Map<String, Object> m : targets) {
            long did = ((Number) m.get("dialogId")).longValue();
            String text = String.valueOf(m.get("text"));
            String status = statusOf(did, todayStr());
            targetRow(box, "🚀", did, text, "sign");
        }
        showDialog(act, "点选立即签到", box, "取消");
    }

    private void showLog(Activity act) {
        try {
            StringBuilder sb = new StringBuilder();
            List<String> copy;
            synchronized (logBuffer) { copy = new ArrayList<>(logBuffer); }
            for (String line : copy) sb.append(line).append("\n");
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
            showDialog(act, "运行日志（最近 200 行）", sv, "关闭");
        } catch (Throwable t) {
            jlog("日志框失败: " + t);
            toast("日志打开失败: " + t);
        }
    }

    private void showSettings(Activity act) {
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
            Button ok = new Button(act);
            ok.setText("保存");
            ok.setOnClickListener(v -> {
                String k = kw.getText().toString().trim();
                if (!k.isEmpty()) LEARN_KEYWORDS = k;
                try {
                    int r = Integer.parseInt(rl.getText().toString().trim());
                    if (r > 0 && r <= 99) RETRY_LIMIT = r;
                } catch (Throwable ignored) {}
                try {
                    prefs.edit()
                        .putString("jmb_keywords", LEARN_KEYWORDS)
                        .putInt("jmb_retry", RETRY_LIMIT)
                        .commit();
                } catch (Throwable ignored) {}
                toast("设置已保存: 关键词[" + LEARN_KEYWORDS + "] 重试上限[" + RETRY_LIMIT + "]");
                jlog("设置更新: 关键词=" + LEARN_KEYWORDS + " 重试上限=" + RETRY_LIMIT);
            });
            box.addView(ok);
            showDialog(act, "设置", box, "取消");
        } catch (Throwable t) {
            jlog("设置框失败: " + t);
            toast("设置打开失败: " + t);
        }
    }

    // 命令入口：拦截用户发送的 /jmb 开头消息
    public boolean handleCommand(String text) {
        String t = String.valueOf(text).trim();
        if (!t.startsWith("/jmb")) return false;
        jlog("[界面] 收到管理命令: " + t);
        mainHandler.post(() -> { try { showMainMenu(lastActivity); } catch (Throwable e) { jlog("打开管理菜单失败: " + e); } });
        return true;
    }

    public void setHostActivity(Activity act) {
        lastActivity = act;
    }

    // ---------------- 宿主 Activity 记录（兜底：任何 Activity resume 都记） ----------------
    private void registerActivityListener() {
        try {
            if (!(appContext instanceof Application)) return;
            ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) { lastActivity = activity; }
                @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        } catch (Throwable ignored) {}
    }

    // ---------------- 网络恢复 ----------------
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
        } catch (Throwable ignored) {}
    }

    // ---------------- 定时轮询（账号切换感知） ----------------
    private void schedulePoll() {
        mainHandler.postDelayed(() -> {
            try {
                if (lastAccount != currentAccount()) {
                    lastAccount = currentAccount();
                    loadTargets();
                    jlog("检测到账号切换 -> acc" + lastAccount + "，已重载目标");
                }
                enqueueTry("定时");
                schedulePoll();
            } catch (Throwable ignored) {}
        }, POLL_INTERVAL_MS);
    }

    // ==================== 由 Entry 调用的 Hook 回调 ====================

    /** 兼容 TG 12.x：按钮文案优先走 getText()，字段 text 作为兜底 */
    private Object buttonText(Object proto) {
        if (proto == null) return null;
        try { return call(proto, "getText", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {}
        try { return getFieldVal(proto, "text"); } catch (Throwable ignored) {}
        return null;
    }

    /** 关键词过滤：与网络层学习保持同一规则 */
    private boolean keywordMatched(String text) {
        String t = String.valueOf(text).trim();
        if (LEARN_KEYWORDS == null || LEARN_KEYWORDS.trim().length() == 0) return true;
        String[] kws = LEARN_KEYWORDS.split(",");
        for (String kw : kws) {
            if (kw.trim().length() > 0 && t.toLowerCase().contains(kw.trim().toLowerCase())) return true;
        }
        return false;
    }

    /** 触发源 1：UI 按钮点击学习（ChatActivityEnterView.didPressedBotButton） */
    public void onBotButtonEnterView(Object proto, Object moOrNull) {
        try {
            if (proto == null) return;
            Object did = null;
            if (moOrNull != null) { try { did = call(moOrNull, "getDialogId", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            Object text = buttonText(proto);
            if (did != null && text != null) {
                String t = String.valueOf(text);
                if (!keywordMatched(t)) { jlog("[按钮] uid=" + did + " text=" + t + "（不含签到关键词，不自动添加）"); return; }
                learnTarget(((Number) did).longValue(), t);
            }
        } catch (Throwable ignored) {}
    }

    /** 触发源 1b：UI 按钮点击学习（ChatMessageCellDelegate.didPressBotButton） */
    public void onBotButtonCell(Object cell, Object proto) {
        try {
            Object mo = null;
            if (cell != null) { try { mo = call(cell, "getMessageObject", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            Object did = null;
            if (mo != null) { try { did = call(mo, "getDialogId", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            Object text = buttonText(proto);
            if (did != null && text != null) {
                String t = String.valueOf(text);
                if (!keywordMatched(t)) { jlog("[按钮] uid=" + did + " text=" + t + "（不含签到关键词，不自动添加）"); return; }
                learnTarget(((Number) did).longValue(), t);
            }
        } catch (Throwable ignored) {}
    }

    /** 触发源 2：网络活动（学习/已签标记/补签/命令拦截）。返回 true 表示已拦截（不发送）。 */
    public boolean onSendRequest(Object[] args) {
        try {
            Object req = args != null && args.length > 0 ? args[0] : null;
            if (req == null) return false;
            String rn = req.getClass().getName();
            if (rn.contains("TL_messages_getBotCallbackAnswer")) {
                try {
                    Object peer = getFieldVal(req, "peer");
                    Object uid = null;
                    if (peer != null) { try { uid = getFieldVal(peer, "user_id"); } catch (Throwable ignored) {} }
                    if (uid != null) {
                        Object data = getFieldVal(req, "data");
                        jlog("[回调按钮] uid=" + uid + " data=" + data + " → 回调型按钮，自动签到暂不支持");
                        toast("ℹ️ 该机器人使用回调按钮，自动签到暂不支持");
                    }
                } catch (Throwable ignored) {}
            } else if (rn.contains("TL_messages_sendMessage")) {
                Object peer = getFieldVal(req, "peer");
                Object msg = getFieldVal(req, "message");
                // [界面版] 管理命令拦截
                if (msg != null && String.valueOf(msg).trim().startsWith("/jmb")) {
                    handleCommand(String.valueOf(msg));
                    return true;   // 吞掉管理命令，不发送
                }
                Object uid = null;
                if (peer != null) { try { uid = getFieldVal(peer, "user_id"); } catch (Throwable ignored) {} }
                if (uid != null) {
                    final Object fUid = uid;
                    final Object fMsg = msg;
                    mainHandler.post(() -> {
                        try {
                            long u = ((Number) fUid).longValue();
                            String t = String.valueOf(fMsg);
                            String key = u + "|" + t;
                            long nowMs = System.currentTimeMillis();
                            if (nowMs - lastSeenClean > 500L) { seenSignals.clear(); lastSeenClean = nowMs; }
                            if (seenSignals.contains(key)) {
                                jlog("[去重] 跳过重复信号 " + key);
                                return;
                            }
                            seenSignals.add(key);
                            if (targetContains(u)) {
                                markSignedFromRequest(u, t);
                            } else {
                                learnFromNetwork(u, t);
                            }
                        } catch (Throwable ignored) {}
                    });
                }
            }
            enqueueTry("TG活动");
            return false;
        } catch (Throwable t) {
            jlog("[活动] 异常: " + t);
            return false;
        }
    }

    /** 触发源 3.5：bot 回复语义判定（失败撤销 + 退避重试） */
    public void onUpdateProcessed(Object update) {
        try {
            if (update == null) return;
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
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            jlog("[回复判定] 异常: " + t);
        }
    }

    // ==================== 反射工具 ====================
    private Class<?> classEx(String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    private Object tgBuilder(Activity act) throws Exception {
        Class<?> bc = classEx("org.telegram.ui.ActionBar.AlertDialog$Builder");
        // tg dialog 构造：Builder(Context) 或 Builder(Context, int theme)
        try {
            Constructor<?> ctor = bc.getConstructor(Context.class);
            return ctor.newInstance(act);
        } catch (NoSuchMethodException e) {
            Constructor<?> ctor = bc.getConstructor(Context.class, int.class);
            return ctor.newInstance(act, 0);
        }
    }

    private static Object call(Object obj, String name, Class<?>[] types, Object[] args) throws Exception {
        Method m = obj.getClass().getMethod(name, types);
        return m.invoke(obj, args);
    }

    private static Object invoke(Object obj, String name, Class<?>[] types, Object[] args) throws Exception {
        Method m = obj.getClass().getMethod(name, types);
        return m.invoke(obj, args);
    }

    private static Object staticInvoke(Class<?> cls, String name, Class<?>[] types, Object[] args) throws Exception {
        Method m = cls.getMethod(name, types);
        return m.invoke(null, args);
    }

    private Object getMessagesController() {
        try {
            return staticInvoke(classEx("org.telegram.messenger.MessagesController"), "getInstance", new Class<?>[]{int.class}, new Object[]{currentAccount()});
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getMessagesStorage() {
        try {
            return staticInvoke(classEx("org.telegram.messenger.MessagesStorage"), "getInstance", new Class<?>[]{int.class}, new Object[]{currentAccount()});
        } catch (Throwable t) {
            return null;
        }
    }

    private Object newRequestDelegate(InvocationHandler handler) throws Exception {
        Class<?> iface = classEx("org.telegram.tgnet.RequestDelegate");
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, handler);
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
        } catch (Throwable t1) {
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
}
