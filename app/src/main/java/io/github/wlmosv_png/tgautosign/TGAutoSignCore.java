package io.github.wlmosv_png.tgautosign;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TGAutoSignCore - Telegram 自动签到核心逻辑（纯反射，不依赖 Telegram 编译期类）
 * 机制同 LSPilot BSH 插件版：
 *  - 自动学习：网络层观测向未登记 bot 发送的短指令 -> 自动加入目标（Toast 通知）
 *  - 每日一次：请求发出且无错误 -> 标记今日已签（bot 回"已签到"也算），当天绝不再发
 *  - 已签提示：启动/打开聊天/网络恢复且全部已签 -> Toast「今天已经签到过了 ✅」
 *  - 断网补签：失败每日每目标最多 RETRY_LIMIT 次；网络恢复/定时自动补
 *  - 触发：启动5秒 / 打开聊天 / 网络恢复 / TG网络活动(1分钟节流) / 30分钟定时
 *  - 学习记录存独立 SharedPreferences("tg_autosign_gen")
 */
public final class TGAutoSignCore {

    private static final String TAG = "TGAutoSignModule";
    private static final long THROTTLE_MS = 60_000L;
    private static final long POLL_INTERVAL_MS = 30L * 60_000L;
    private static final int RETRY_LIMIT = 5;
    // v1.1: 网络层学习关键词
    private static final String LEARN_KEYWORDS = "签到,打卡,checkin,claim,领取,签到领,/qd,/qiandao,/sign";
    private static final String[] PERMANENT_ERRORS = {"PEER_ID_INVALID", "USER_BOT_INVALID", "CHAT_WRITE_FORBIDDEN", "USER_ID_INVALID", "AUTH_KEY_UNREGISTERED", "MESSAGE_EMPTY", "CHAT_ID_INVALID", "PEER_ID_NOT_EXIST", "USER_PRIVACY_RESTRICTED"};
    private final Set<String> seenSignals = new HashSet<>();
    private long lastSeenClean = 0L;
    private long lastCallbackToast = 0L;
    private int lastAccount = -1;

    private final Context appContext;
    private final ClassLoader cl;
    private final SharedPreferences prefs;
    private final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
    private final List<Map<String, Object>> targets = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private volatile long lastTry = 0L;
    private volatile boolean receiverRegistered = false;

    public TGAutoSignCore(Context appContext, ClassLoader cl) {
        this.appContext = appContext;
        this.cl = cl;
        this.prefs = appContext.getSharedPreferences("tg_autosign_gen", Context.MODE_PRIVATE);
    }

    public void start() {
        loadTargets();
        // 启动 5 秒后（等 TG 连接完成）补签一次
        main.postDelayed(() -> enqueueTry("启动"), 10000L);
        // 每 30 分钟兜底
        main.postDelayed(this::pollLoop, POLL_INTERVAL_MS);
        // 网络恢复监听
        registerNetworkReceiver();
        log("TGAutoSignCore started, targets=" + targets.size());
    }

    private void pollLoop() {
        try {
            int acc = UserConfigSelectedAccount();
            if (lastAccount != acc) {
                lastAccount = acc;
                loadTargets();
                log("检测到账号切换 -> acc" + acc + "，已重载目标");
            }
        } catch (Throwable ignored) {}
        enqueueTry("定时");
        main.postDelayed(this::pollLoop, POLL_INTERVAL_MS);
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

    // ---------------- 由 Entry 的 sendRequest hook 回调 ----------------
    public void onSendRequest(List<Object> args) {
        try {
            if (args != null && !args.isEmpty() && args.get(0) != null) {
                Object req = args.get(0);
                String name = req.getClass().getName();
                if (name.contains("TL_messages_getBotCallbackAnswer")) {
                    // v1.1: 回调型按钮识别
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
                            // v1.1: 双触发去重（sendRequest 多重载嵌套）
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
    }

    // ---------------- 目标管理 ----------------
    private String todayStr() { return SDF.format(new Date()); }

    private boolean targetContains(long did) {
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == did) return true;
        }
        return false;
    }

    private void addTarget(long dialogId, String text) {
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == dialogId) return;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("dialogId", dialogId);
        m.put("text", text);
        targets.add(m);
        log("已登记签到目标 " + dialogId + " -> " + text);
    }

    private void loadTargets() {
        targets.clear();
        try {
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (key.startsWith("learned_")) {
                    long did = Long.parseLong(key.substring("learned_".length()));
                    addTarget(did, String.valueOf(e.getValue()));
                }
            }
        } catch (Throwable t) {
            log("loadTargets err: " + t);
        }
    }

    // UI 入口学习（按钮点击）
    public void learnTarget(long dialogId, String text) {
        if (text == null || text.isEmpty()) return;
        // v1.1: 去重（相同指令跳过；指令变化才覆盖）
        for (Map<String, Object> m : targets) {
            if (((Number) m.get("dialogId")).longValue() == dialogId) {
                if (String.valueOf(m.get("text")).equals(String.valueOf(text))) {
                    log("目标 " + dialogId + " 已学习过相同指令，跳过");
                    return;
                }
                log("检测到指令变化，覆盖 " + dialogId + " : " + m.get("text") + " -> " + text);
                break;
            }
        }
        try {
            prefs.edit().putString("learned_" + dialogId, text).putString("last_" + dialogId, todayStr()).apply();
            addTarget(dialogId, text);
            log("【自动学习】新目标 " + dialogId + " -> " + text);
            toast("✅ 已添加新签到目标: " + text);
        } catch (Throwable t) {
            log("learnTarget err: " + t);
        }
    }

    // 网络层学习：仅 bot + <=20 字符短指令
    private void learnFromNetwork(long did, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 20) return;
        if (targetContains(did)) return;
        // v1.1: 关键词过滤（网络层只学签到类指令）
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
        prefs.edit().putString("learned_" + did, t).apply();
        addTarget(did, t);
        log("【网络层自动学习】新目标 " + did + " -> " + t);
        toast("✅ 已自动添加新签到目标: " + t);
    }

    private void markSignedFromRequest(long did, String text) {
        try {
            if (text == null || !targetContains(did)) return;
            for (Map<String, Object> m : targets) {
                if (((Number) m.get("dialogId")).longValue() == did
                        && text.equals(String.valueOf(m.get("text")))) {
                    prefs.edit().putString("last_" + did, todayStr()).apply();
                    log("检测到签到消息已发出，标记今日已签 " + did);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- 发送签到 ----------------
    private void sendSign(long dialogId, String text) {
        try {
            int account = UserConfigSelectedAccount();
            Object mc = getMessagesController();
            if (mc == null) {
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
                log("未找到用户数据 " + dialogId);
                return;
            }
            Object peer = staticInvoke(getClassEx("org.telegram.messenger.MessagesController"), "getInputPeer", new Class<?>[]{Object.class}, new Object[]{user});
            if (peer == null) {
                log("构造 InputPeer 失败 " + dialogId);
                return;
            }

            Class<?> sendCls = getClassEx("org.telegram.tgnet.TLRPC$TL_messages_sendMessage");
            Object req = sendCls.newInstance();
            setFieldVal(req, "peer", peer);
            setFieldVal(req, "message", text);
            setFieldVal(req, "random_id", random.nextLong());

            Object cm = staticInvoke(getClassEx("org.telegram.tgnet.ConnectionsManager"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
            prefs.edit().putLong("sent_at_" + dialogId, System.currentTimeMillis()).apply();
            final long fDid = dialogId;
            final String fText = text;
            final Object delegate = newRequestDelegate(new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] margs) throws Throwable {
                    if ("run".equals(method.getName()) && margs != null && margs.length >= 2) {
                        final Object error = margs[1];
                        main.post(() -> {
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
                                    if (permanent) {
                                        prefs.edit().putString("last_" + fDid, todayStr()).putInt("retry_" + fDid, RETRY_LIMIT).apply();
                                        log("签到永久失败 " + fDid + " : " + et + "（今日放弃）");
                                        toast("⚠️ 签到失败(" + et + ")，今日不再重试");
                                    } else if (code.contains("420") || upper.startsWith("FLOOD_WAIT")) {
                                        main.postDelayed(() -> { try { enqueueTry("限流重试"); } catch (Throwable ignored) {} }, 60_000L);
                                        log("签到遇限流 " + fDid + " : " + et + "，60秒后自动重试");
                                    } else {
                                        int oldRetry = prefs.getInt("retry_" + fDid, 0);
                                        prefs.edit()
                                            .putInt("retry_" + fDid, oldRetry + 1)
                                            .putLong("retry_at_" + fDid, System.currentTimeMillis() + backoffDelay(oldRetry))
                                            .apply();
                                        log("签到失败 " + fDid + " : " + et + "（第" + (oldRetry + 1) + "次，退避重试）");
                                        toast("⚠️ 签到失败: " + et + "，稍后自动重试");
                                    }
                                } else {
                                    prefs.edit().putString("last_" + fDid, todayStr()).putInt("retry_" + fDid, 0).apply();
                                    log("签到完成 " + fDid + " text=" + fText);
                                    toast("✅ 签到成功: " + fText);
                                }
                            } catch (Throwable ignored) {}
                        });
                    }
                    return null;
                }
            });

            invoke(cm, "sendRequest", new Class<?>[]{getClassEx("org.telegram.tgnet.TLObject"), getClassEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
            log("已发起签到请求 " + dialogId + " text=" + text);
        } catch (Throwable t) {
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
                if (today.equals(prefs.getString("last_" + dialogId, ""))) {
                    signed++;
                    continue;
                }
                int retries = prefs.getInt("retry_" + dialogId, 0);
                if (retries >= RETRY_LIMIT) {
                    log("[" + reason + "] " + dialogId + " 今日重试已达上限");
                    signed++;
                    continue;
                }
                long retryAt = prefs.getLong("retry_at_" + dialogId, 0);
                if (System.currentTimeMillis() < retryAt) {
                    log("[" + reason + "] " + dialogId + " 退避中(剩" + ((retryAt - System.currentTimeMillis()) / 60000L) + "分钟)，跳过");
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

    // ---------------- 反射 / 工具 ----------------
    private long backoffDelay(int retries) {
        long[] delays = {5L * 60 * 1000, 15L * 60 * 1000, 45L * 60 * 1000, 2L * 60 * 60 * 1000, 4L * 60 * 60 * 1000};
        return delays[Math.min(retries, delays.length - 1)];
    }

    // v1.1: bot 回复语义判定（由 Entry 的 processUpdate hook 调用）
    void onUpdateProcessed(Object update) {
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
            if (System.currentTimeMillis() - prefs.getLong("sent_at_" + did, 0) > 10L * 60 * 1000) return;
            main.post(() -> {
                try {
                    String lower = replyText.toLowerCase();
                    String[] failWords = {"失败", "未成功", "请先", "不能", "无法", "不可", "错误", "已过期", "未关注", "没有资格", "failed", "invalid", "rejected", "not allowed", "try again"};
                    for (String w : failWords) {
                        if (lower.contains(w)) {
                            int cur = prefs.getInt("retry_" + did, 0);
                            prefs.edit()
                                .remove("last_" + did)
                                .putInt("retry_" + did, cur + 1)
                                .putLong("retry_at_" + did, System.currentTimeMillis() + backoffDelay(1))
                                .apply();
                            log("【回复判定】" + did + " bot 回复: " + replyText + " → 判定未成功，撤销已签并安排重试");
                            toast("⚠️ " + did + " 可能未签到成功: " + replyText);
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            log("[回复判定] 异常: " + t);
        }
    }

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

    private int UserConfigSelectedAccount() throws Exception {
        Object v = getFieldVal(null, getClassEx("org.telegram.messenger.UserConfig"), "selectedAccount");
        return ((Number) v).intValue();
    }

    private Object getMessagesController() {
        try {
            return staticInvoke(getClassEx("org.telegram.messenger.MessagesController"), "getInstance", new Class<?>[]{int.class}, new Object[]{UserConfigSelectedAccount()});
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getMessagesStorage() {
        try {
            return staticInvoke(getClassEx("org.telegram.messenger.MessagesStorage"), "getInstance", new Class<?>[]{int.class}, new Object[]{UserConfigSelectedAccount()});
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

    private static Object getFieldVal(Object obj, Class<?> cls, String name) throws Exception {
        try {
            Field f = cls.getField(name);
            return f.get(obj);
        } catch (Throwable t) {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
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

    void toast(String msg) {
        main.post(() -> {
            try {
                Toast.makeText(appContext, String.valueOf(msg), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
    }

    void log(String msg) {
        Log.i(TAG, msg);
    }
}
