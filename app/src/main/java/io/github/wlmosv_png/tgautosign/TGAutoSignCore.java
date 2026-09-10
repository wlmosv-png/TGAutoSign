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
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.github.wlmosv_png.tgautosign.update.ConfigStore;
import io.github.wlmosv_png.tgautosign.update.UpdateChecker;

/**
 * TGAutoSignCore —— v1.3.0 多目标模型版。
 *
 * 目标模型（v1.3.0 起）：
 *  - 一个 bot 可登记多条签到目标（多指令 / 文本 + 回调按钮并存），条目以 id 区分
 *  - 条目字段：id（prefs 键后缀，主键）、did（bot 数字 ID）、text（指令或按钮文案）、
 *    kind（text=文本指令 / cb=回调按钮）、data（回调 payload，cb 专用）、hash（回调 hash）
 *  - 旧数据兼容：v1.2.x 的 acc{N}_learned_<did> 直接以 id=did 迁移，键名不变，无需改写
 *  - 新条目 id = <did>_<seq> 或 <did>_cb<seq>，与旧键永不冲突
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
    private final Set<String> pendingSigns = new HashSet<>();
    private boolean receiverRegistered = false;
    private int lastAccount = -1;

    // 界面版：最近的前台 Activity（用于弹管理对话框）
    private volatile Activity lastActivity = null;
    // 日志环形缓冲（最近 200 行）
    private final List<String> logBuffer = new ArrayList<>();

    private final Random random = new Random();
    /** 最近一次更新检查结果（/jmb 菜单与下载动作读取） */
    private volatile UpdateChecker.Result lastUpdate = null;

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
        jlog("=== TGAutoSign 模块 v" + UpdateChecker.VERSION_NAME + " (jmb界面版) 已加载 ===");
        jlog("宿主客户端: " + safePkg() + "，账号: " + currentAccount() + "，目标数: " + targets.size());
        jlog("使用: 在任意聊天输入 /jmb 打开管理界面");
        toast("TGAutoSign 界面版已运行：发 /jmb 管理");
        jlog("TGAutoSignCore v" + UpdateChecker.VERSION_NAME + " started, targets=" + targets.size());
        checkUpdateSilently();
    }

    /** 静默检查更新：12 小时冷却，任何失败都不影响签到主流程 */
    private void checkUpdateSilently() {
        try {
            UpdateChecker.checkAsync(appContext, false, mainHandler, r -> {
                if (r == null) return;
                if (r.networkError) { jlog("检查更新未成功(忽略): " + r.message); return; }
                lastUpdate = r;
                if (r.newer) {
                    jlog("发现新版本 v" + r.version + "（当前 v" + UpdateChecker.VERSION_NAME + "）");
                    toast("TGAutoSign 有新版本 v" + r.version + "：发 /jmb → 🔄 检查更新");
                } else {
                    jlog("检查更新：已是最新 v" + UpdateChecker.VERSION_NAME);
                }
            });
        } catch (Throwable t) { jlog("检查更新异常(忽略): " + t); }
    }

    // ---------------- 工具 ----------------
    private String todayStr() { return SDF.format(new Date()); }

    /** 宿主包名（多客户端排查用；失败返回 unknown，不影响主流程） */
    private String safePkg() {
        try { return appContext.getPackageName(); } catch (Throwable t) { return "unknown"; }
    }

    private String accountPrefix() { return "acc" + currentAccount() + "_"; }

    private String accountPrefix(int account) { return "acc" + account + "_"; }

    /** 已激活账号数（全账号签到用）；反射失败回退 1 */
    private int activatedAccounts() {
        try {
            Object n = staticInvoke(classEx("org.telegram.messenger.UserConfig"), "getActivatedAccountsCount", new Class<?>[0], new Object[0]);
            if (n instanceof Number) {
                int c = ((Number) n).intValue();
                return c > 0 ? c : 1;
            }
        } catch (Throwable ignored) {}
        return 1;
    }

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

    // ---------------- 目标条目模型（v1.3.0：一 bot 多指令 + 回调按钮） ----------------
    private static final String KIND_TEXT = "text";
    private static final String KIND_CB = "cb";

    private String entryId(Map<String, Object> m) { return String.valueOf(m.get("id")); }
    private long entryDid(Map<String, Object> m) { return ((Number) m.get("did")).longValue(); }
    private String entryText(Map<String, Object> m) { return String.valueOf(m.get("text")); }
    private String entryKind(Map<String, Object> m) { return m.get("kind") == null ? KIND_TEXT : String.valueOf(m.get("kind")); }
    private byte[] entryData(Map<String, Object> m) { return m.get("data") instanceof byte[] ? (byte[]) m.get("data") : null; }
    private long entryHash(Map<String, Object> m) { return m.get("hash") instanceof Number ? ((Number) m.get("hash")).longValue() : 0L; }

    private Map<String, Object> findEntryById(String id) {
        for (Map<String, Object> m : targets) {
            if (entryId(m).equals(id)) return m;
        }
        return null;
    }

    private Map<String, Object> findTextEntry(long did, String text) {
        for (Map<String, Object> m : targets) {
            if (entryDid(m) == did && KIND_TEXT.equals(entryKind(m)) && entryText(m).equals(String.valueOf(text))) return m;
        }
        return null;
    }

    private Map<String, Object> findCbEntry(long did, byte[] data) {
        if (data == null) return null;
        for (Map<String, Object> m : targets) {
            if (entryDid(m) == did && KIND_CB.equals(entryKind(m)) && Arrays.equals(entryData(m), data)) return m;
        }
        return null;
    }

    /** 新条目 id：<did>_<seq>（文本）或 <did>_cb<seq>（回调），与旧键 acc{N}_learned_<did> 永不冲突 */
    private String nextEntryId(long did, String kind) {
        int maxSeq = 0;
        String prefix = did + (KIND_CB.equals(kind) ? "_cb" : "_");
        for (Map<String, Object> m : targets) {
            String id = entryId(m);
            if (id.startsWith(prefix)) {
                try { maxSeq = Math.max(maxSeq, Integer.parseInt(id.substring(prefix.length()))); } catch (Throwable ignored) {}
            }
        }
        return prefix + (maxSeq + 1);
    }

    private void persistEntry(String prefix, Map<String, Object> m) {
        String id = entryId(m);
        SharedPreferences.Editor e = prefs.edit();
        e.putString(prefix + "learned_" + id, entryText(m));
        e.putString(prefix + "kind_" + id, entryKind(m));
        e.putLong(prefix + "did_" + id, entryDid(m));
        if (KIND_CB.equals(entryKind(m)) && entryData(m) != null) {
            e.putString(prefix + "data_" + id, Base64.getEncoder().encodeToString(entryData(m)));
            e.putLong(prefix + "hash_" + id, entryHash(m));
        }
        e.commit();
    }

    private void removeEntryKeys(String prefix, String id) {
        prefs.edit()
            .remove(prefix + "learned_" + id)
            .remove(prefix + "kind_" + id)
            .remove(prefix + "did_" + id)
            .remove(prefix + "data_" + id)
            .remove(prefix + "hash_" + id)
            .remove(prefix + "last_" + id)
            .remove(prefix + "retry_" + id)
            .remove(prefix + "retry_at_" + id)
            .remove(prefix + "sent_at_" + id)
            .commit();
    }

    private void addTargetEntry(Map<String, Object> m) {
        targets.add(m);
        jlog("已登记签到目标 " + entryDid(m) + " -> " + (KIND_CB.equals(entryKind(m)) ? "[回调] " : "") + entryText(m) + " (id=" + entryId(m) + ")");
    }

    private boolean targetContains(long did) {
        for (Map<String, Object> m : targets) {
            if (entryDid(m) == did) return true;
        }
        return false;
    }

    private void loadTargets() {
        targets.clear();
        loadTargetsInto(accountPrefix(), targets);
        Collections.sort(targets, new Comparator<Map<String, Object>>() {
            @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                int c = Long.compare(entryDid(a), entryDid(b));
                return c != 0 ? c : entryId(a).compareTo(entryId(b));
            }
        });
    }

    /** 从 prefs 读出某账号的全部条目（旧键 learned_<did> 自动迁移为 id=did） */
    private void loadTargetsInto(String prefix, List<Map<String, Object>> out) {
        try {
            Map<String, ?> all = prefs.getAll();
            List<String> ids = new ArrayList<>();
            for (String key : all.keySet()) {
                if (key.startsWith(prefix + "learned_")) ids.add(key.substring((prefix + "learned_").length()));
            }
            Collections.sort(ids);
            for (String id : ids) {
                try {
                    long did;
                    String kind;
                    if (id.contains("_")) {
                        did = prefs.getLong(prefix + "did_" + id, 0L);
                        kind = prefs.getString(prefix + "kind_" + id, KIND_TEXT);
                    } else {
                        did = Long.parseLong(id);   // 旧格式：learned_<did>
                        kind = KIND_TEXT;
                    }
                    if (did <= 0) continue;
                    String text = String.valueOf(all.get(prefix + "learned_" + id));
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", id);
                    m.put("did", did);
                    m.put("text", text);
                    m.put("kind", kind);
                    if (KIND_CB.equals(kind)) {
                        String b64 = prefs.getString(prefix + "data_" + id, null);
                        if (b64 != null) {
                            try { m.put("data", Base64.getDecoder().decode(b64)); } catch (Throwable ignored) {}
                        }
                        m.put("hash", prefs.getLong(prefix + "hash_" + id, 0L));
                    }
                    out.add(m);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            jlog("读取学习目标失败: " + t);
        }
    }

    /** 学习文本指令目标（按钮 / 手动 / 网络层）。同 bot 不同指令 = 新增条目；相同指令 = 跳过。 */
    private void learnTarget(long dialogId, String text) {
        if (!LEARN_ENABLED) return;
        if (text == null || text.length() == 0) return;
        if (dialogId <= 0) {
            jlog("忽略群聊学习: dialogId=" + dialogId);
            return;
        }
        if (findTextEntry(dialogId, String.valueOf(text)) != null) {
            jlog("目标 " + dialogId + " 已学习过相同指令，跳过");
            return;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("id", nextEntryId(dialogId, KIND_TEXT));
        m.put("did", dialogId);
        m.put("text", String.valueOf(text));
        m.put("kind", KIND_TEXT);
        String prefix = accountPrefix();
        persistEntry(prefix, m);
        addTargetEntry(m);
        jlog("【自动学习】新目标 " + dialogId + " -> " + text);
        toast("✅ 已添加新签到目标: " + text);
    }

    /** 学习回调按钮目标（inline button，v1.3.0 新增）。同 bot 相同 data 去重。 */
    private void learnCallback(long dialogId, String display, byte[] data, long hash) {
        if (!LEARN_ENABLED) return;
        if (data == null || data.length == 0) return;
        if (dialogId <= 0) return;
        if (findCbEntry(dialogId, data) != null) {
            jlog("目标 " + dialogId + " 已学习过相同回调按钮，跳过");
            return;
        }
        String label = display != null && display.trim().length() > 0 ? String.valueOf(display).trim() : "回调按钮";
        Map<String, Object> m = new HashMap<>();
        m.put("id", nextEntryId(dialogId, KIND_CB));
        m.put("did", dialogId);
        m.put("text", label);
        m.put("kind", KIND_CB);
        m.put("data", data);
        m.put("hash", hash);
        String prefix = accountPrefix();
        persistEntry(prefix, m);
        addTargetEntry(m);
        jlog("【自动学习】新回调签到目标 " + dialogId + " -> [" + label + "] data=" + Base64.getEncoder().encodeToString(data));
        toast("✅ 已添加回调签到目标: " + label);
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
        learnTarget(did, t);
        jlog("【网络层自动学习】新目标 " + did + " -> " + t);
    }

    private void markSigned(String prefix, String id) {
        prefs.edit().putString(prefix + "last_" + id, todayStr()).commit();
        jlog("检测到签到消息已发出，标记今日已签 " + id);
    }

    private void markSignedFromRequest(long did, String text) {
        try {
            if (text == null) return;
            Map<String, Object> m = findTextEntry(did, String.valueOf(text));
            if (m != null) markSigned(accountPrefix(), entryId(m));
        } catch (Throwable ignored) {}
    }

    private void markSignedFromCallback(long did, byte[] data) {
        try {
            Map<String, Object> m = findCbEntry(did, data);
            if (m != null) markSigned(accountPrefix(), entryId(m));
        } catch (Throwable ignored) {}
    }

    // ---------------- 发送签到 ----------------
    private long backoffDelay(int retries) {
        long[] delays = {5L*60*1000, 15L*60*1000, 45L*60*1000, 2L*60*60*1000, 4L*60*60*1000};
        int idx = retries < delays.length ? retries : delays.length - 1;
        return delays[idx];
    }

    /** 发送单条目标。account 指定账号；kind=text 发消息，kind=cb 重放回调按钮。 */
    private void sendSign(Map<String, Object> entry, int account) {
        final long dialogId = entryDid(entry);
        final String id = entryId(entry);
        final String kind = entryKind(entry);
        final String fText = entryText(entry);
        if (pendingSigns.contains(id)) {
            jlog("目标 " + id + " 已有请求在处理中，跳过");
            return;
        }
        String prefix = accountPrefix(account);
        Object mc = getMessagesController(account);
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
                Object ms = getMessagesStorage(account);
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
            Object req;
            if (KIND_CB.equals(kind)) {
                Class<?> cbCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_getBotCallbackAnswer");
                req = cbCls.newInstance();
                setFieldVal(req, "peer", peer);
                setFieldVal(req, "data", entryData(entry));
                setFieldVal(req, "hash", entryHash(entry));
            } else {
                Class<?> sendCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_sendMessage");
                req = sendCls.newInstance();
                setFieldVal(req, "peer", peer);
                setFieldVal(req, "message", fText);
                setFieldVal(req, "random_id", random.nextLong());
            }
            pendingSigns.add(id);
            try { prefs.edit().putLong(prefix + "sent_at_" + id, System.currentTimeMillis()).commit(); } catch (Throwable ignored) {}
            Object cm = staticInvoke(classEx("org.telegram.tgnet.ConnectionsManager"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
            final String fId = id;
            final String fPrefix = prefix;
            final String fKind = kind;
            final Object delegate = newRequestDelegate(new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] margs) {
                    if ("run".equals(method.getName()) && margs != null && margs.length >= 2) {
                        final Object response = margs[0];
                        final Object error = margs[1];
                        mainHandler.post(() -> { pendingSigns.remove(fId); });
                        try {
                            if (error != null) {
                                String code = "";
                                try { code = String.valueOf(getFieldVal(error, "code")); } catch (Throwable ignored) {}
                                String errText = "";
                                try { errText = String.valueOf(getFieldVal(error, "text")); } catch (Throwable ignored) {}
                                String upper = errText.toUpperCase();
                                final String[] PERMANENT = {"PEER_ID_INVALID","USER_BOT_INVALID","CHAT_WRITE_FORBIDDEN","USER_ID_INVALID","AUTH_KEY_UNREGISTERED","MESSAGE_EMPTY","CHAT_ID_INVALID","PEER_ID_NOT_EXIST","USER_PRIVACY_RESTRICTED"};
                                boolean permanent = false;
                                for (String s : PERMANENT) {
                                    if (upper.contains(s)) { permanent = true; break; }
                                }
                                if (permanent) {
                                    prefs.edit().putString(fPrefix + "last_" + fId, todayStr())
                                         .putInt(fPrefix + "retry_" + fId, RETRY_LIMIT).commit();
                                    jlog("签到永久失败 " + dialogId + " : " + errText + "（今日放弃）");
                                    toast("⚠️ 签到失败(" + errText + ")，今日不再重试");
                                } else if (code.equals("420") || upper.startsWith("FLOOD_WAIT")) {
                                    // FLOOD_WAIT_<sec>：尊重服务器要求的等待秒数，写 retry_at 由轮询补签
                                    long waitSec = 60L;
                                    try {
                                        int i = upper.indexOf("FLOOD_WAIT");
                                        if (i >= 0) {
                                            StringBuilder digits = new StringBuilder();
                                            for (int j = i + "FLOOD_WAIT".length(); j < upper.length(); j++) {
                                                char c = upper.charAt(j);
                                                if (c >= '0' && c <= '9') digits.append(c); else if (digits.length() > 0) break;
                                            }
                                            if (digits.length() > 0) waitSec = Long.parseLong(digits.toString());
                                        }
                                    } catch (Throwable ignored) {}
                                    if (waitSec < 1L) waitSec = 60L;
                                    long waitMs = waitSec * 1000L + 1500L;
                                    prefs.edit().putInt(fPrefix + "retry_" + fId, prefs.getInt(fPrefix + "retry_" + fId, 0) + 1)
                                         .remove(fPrefix + "last_" + fId)
                                         .putLong(fPrefix + "retry_at_" + fId, System.currentTimeMillis() + waitMs).commit();
                                    jlog("签到遇限流 " + dialogId + " : " + errText + "，等待 " + waitSec + " 秒后自动重试");
                                } else {
                                    int oldRetry = prefs.getInt(fPrefix + "retry_" + fId, 0);
                                    prefs.edit().putInt(fPrefix + "retry_" + fId, oldRetry + 1)
                                         .remove(fPrefix + "last_" + fId)
                                         .putLong(fPrefix + "retry_at_" + fId, System.currentTimeMillis() + backoffDelay(oldRetry))
                                         .commit();
                                    jlog("签到失败 " + dialogId + " : " + errText + "（第" + (oldRetry + 1) + "次，退避重试）");
                                    toast("⚠️ 签到失败: " + errText + "，稍后自动重试");
                                }
                            } else {
                                prefs.edit()
                                    .putString(fPrefix + "last_" + fId, todayStr())
                                    .putInt(fPrefix + "retry_" + fId, 0)
                                    .commit();
                                jlog("签到完成 " + dialogId + " " + (KIND_CB.equals(fKind) ? "[回调] " : "text=") + fText);
                                toast("✅ 签到成功: " + fText);
                            }
                        } catch (Throwable ignored) {}
                    }
                    return null;
                }
            });
            invoke(cm, "sendRequest", new Class<?>[]{classEx("org.telegram.tgnet.TLObject"), classEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
            jlog("已发起签到请求 " + dialogId + " " + (KIND_CB.equals(kind) ? "[回调] " : "text=") + fText + " (id=" + id + ")");
        } catch (Throwable t) {
            pendingSigns.remove(id);
            jlog("发送异常 " + dialogId + " : " + t);
        }
    }

    // ---------------- 补签 ----------------
    void trySignAll(String reason, boolean force) {
        trySignAllFor(reason, force, currentAccount());
    }

    /** 对指定账号执行一轮补签。从 prefs 直接读该账号条目，支持全账号签到。 */
    void trySignAllFor(String reason, boolean force, int account) {
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
        String prefix = accountPrefix(account);
        String today = todayStr();
        boolean promptToday = reason != null && (reason.startsWith("启动") || "打开聊天".equals(reason) || "网络恢复".equals(reason));
        int signed = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        loadTargetsInto(prefix, list);
        int total = list.size();
        for (Map<String, Object> m : list) {
            long dialogId = entryDid(m);
            String id = entryId(m);
            try {
                String lastSign = prefs.getString(prefix + "last_" + id, "");
                if (today.equals(lastSign)) { signed++; continue; }
                // 跨天重置：last_ 不是今天说明是新的一天，清掉昨天的重试计数与退避
                int retries = prefs.getInt(prefix + "retry_" + id, 0);
                if (retries > 0 || prefs.contains(prefix + "retry_at_" + id)) {
                    prefs.edit()
                        .putInt(prefix + "retry_" + id, 0)
                        .remove(prefix + "retry_at_" + id)
                        .commit();
                    retries = 0;
                    jlog("[" + reason + "] " + dialogId + " 新的一天，重试计数已重置");
                }
                if (retries >= RETRY_LIMIT) {
                    jlog("[" + reason + "] " + dialogId + " 今日重试已达上限");
                    signed++;
                    continue;
                }
                long retryAt = prefs.getLong(prefix + "retry_at_" + id, 0);
                if (System.currentTimeMillis() < retryAt) {
                    long mins = (retryAt - System.currentTimeMillis()) / 60000L;
                    jlog("[" + reason + "] " + dialogId + " 退避中(剩" + mins + "分钟)，跳过");
                    continue;
                }
                if (pendingSigns.contains(id)) {
                    jlog("[" + reason + "] " + dialogId + " 已在发送中，跳过");
                    signed++;
                    continue;
                }
                jlog("[" + reason + "] 尝试签到 " + dialogId + " " + (KIND_CB.equals(entryKind(m)) ? "[回调] " : "text=") + entryText(m) + " (重试" + retries + ")");
                sendSign(m, account);
            } catch (Throwable t) {
                jlog("trySignAll 异常 " + dialogId + " : " + t);
            }
        }
        jlog("[" + reason + "] 检查完成(账号" + account + ") 目标=" + total + " 已签=" + signed + " 处理=" + (total - signed));
        if (promptToday && total > 0 && signed == total) {
            jlog("[提示] 今天已全部签到完成，无需重复");
            toast("今天已经签到过了 ✅");
        }
    }

    /** v1.3.0：一键签全部账号（每个账号独立目标集，各自发各自的） */
    public void signAllAccounts() {
        int count = activatedAccounts();
        jlog("=== 全账号签到开始，共 " + count + " 个账号 ===");
        for (int i = 0; i < count; i++) {
            try {
                trySignAllFor("全账号(" + (i + 1) + "/" + count + ")", false, i);
            } catch (Throwable t) {
                jlog("账号 " + i + " 签到异常: " + t);
            }
        }
        jlog("=== 全账号签到结束 ===");
        toast("全账号签到已执行，结果见运行日志");
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
    private final Map<Long, String> nameCache = new HashMap<>();

    /** 解析 bot 显示名（username / first_name），失败返回 null */
    private String botName(long did) {
        if (nameCache.containsKey(did)) return nameCache.get(did);
        String name = null;
        try {
            Object u = null;
            Object mc = getMessagesController();
            if (mc != null) {
                try { u = invoke(mc, "getUser", new Class<?>[]{Long.class}, new Object[]{did}); } catch (Throwable ignored) {}
            }
            if (u == null) {
                Object ms = getMessagesStorage();
                if (ms != null) {
                    try { u = invoke(ms, "getUser", new Class<?>[]{long.class}, new Object[]{did}); } catch (Throwable ignored) {}
                }
            }
            if (u != null) {
                Object un = null;
                try { un = getFieldVal(u, "username"); } catch (Throwable ignored) {}
                if (un == null || String.valueOf(un).length() == 0) {
                    try { un = getFieldVal(u, "first_name"); } catch (Throwable ignored) {}
                }
                if (un != null && String.valueOf(un).length() > 0) name = String.valueOf(un);
            }
        } catch (Throwable ignored) {}
        nameCache.put(did, name);
        return name;
    }

    /** 目标显示标题：bot 名(uid) 或裸 uid */
    private String targetTitle(long did) {
        String n = botName(did);
        return n != null ? n + " (" + did + ")" : "uid: " + did;
    }

    private String statusOf(String prefix, String id, String today) {
        String lastSign = prefs.getString(prefix + "last_" + id, "");
        if (today.equals(lastSign)) return "已签 ✅";
        int retries = prefs.getInt(prefix + "retry_" + id, 0);
        if (retries >= RETRY_LIMIT) return "已放弃 💤";
        long retryAt = prefs.getLong(prefix + "retry_at_" + id, 0);
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

    private View targetRow(LinearLayout parent, Map<String, Object> entry, String status, String action) {
        Context c = parent.getContext();
        long did = entryDid(entry);
        String id = entryId(entry);
        String kind = entryKind(entry);
        String text = entryText(entry);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setTag(action + "|" + id);
        if (action != null) {
            row.setOnClickListener(v -> {
                String tag = String.valueOf(v.getTag());
                String[] parts = tag.split("\\|");
                runTargetAction(v.getContext(), parts[0], parts.length > 1 ? parts[1] : "");
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
        t1.setText(targetTitle(did) + (KIND_CB.equals(kind) ? "  🔘" : ""));
        TextView t2 = new TextView(c);
        t2.setTextSize(13);
        t2.setTextColor(android.graphics.Color.parseColor(txtSub(c)));
        String lastT = prefs.getString(accountPrefix() + "last_" + id, "");
        String prefix = KIND_CB.equals(kind) ? "回调: " : "指令: ";
        t2.setText(prefix + text + (lastT.length() > 0 ? "  ·  上次: " + lastT : ""));
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
        if ("sign_all_accounts".equals(action)) { signAllAccounts(); return; }
        if ("log".equals(action)) { showLog(act); return; }
        if ("settings".equals(action)) { showSettings(act); return; }
        if ("update".equals(action)) { showUpdate(act); return; }
        if ("update_download".equals(action)) { downloadUpdate(act); return; }
        if ("export".equals(action)) { doExport(); return; }
        if ("export_log".equals(action)) { doExportLog(act); return; }
        if ("import".equals(action)) { doImport(); return; }
    }

    private void runTargetAction(Context ctx, String action, String id) {
        if (ctx == null || !(ctx instanceof Activity)) return;
        if ("delete".equals(action)) {
            Map<String, Object> m = findEntryById(id);
            if (m == null) { toast("目标不存在"); return; }
            long did = entryDid(m);
            String prefix = accountPrefix();
            removeEntryKeys(prefix, id);
            for (int j = targets.size() - 1; j >= 0; j--) {
                if (entryId(targets.get(j)).equals(id)) targets.remove(j);
            }
            jlog("已删除目标 " + did + " (id=" + id + ")");
            toast("已删除 " + targetTitle(did));
            showDelete((Activity) ctx);
            return;
        }
        if ("sign".equals(action)) {
            Map<String, Object> m = findEntryById(id);
            if (m == null) { toast("目标不存在"); return; }
            jlog("[界面] 手动签到 " + entryDid(m) + " (id=" + id + ")");
            sendSign(m, currentAccount());
            toast("已命令签到 " + entryText(m));
        }
    }

    private void showMainMenu(Activity act) {
        if (act == null) { toast("请在 Telegram 界面使用 /jmb"); return; }
        String today = todayStr();
        int signed = 0;
        for (Map<String, Object> m : targets) {
            if (today.equals(prefs.getString(accountPrefix() + "last_" + entryId(m), ""))) signed++;
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
        menuItem(menu, "📋", "目标列表", "共 " + targets.size() + " 个条目 · 已签 " + signed, "list");
        menuItem(menu, "➕", "添加目标", "bot ID + 签到指令，立即执行", "add");
        menuItem(menu, "🗑", "删除目标", "从自动签到移除", "del");
        menuItem(menu, "🚀", "立即签到", "手动触发一次签到", "sign");
        menuItem(menu, "🌐", "签全部账号", "当前共 " + activatedAccounts() + " 个账号，各自独立签到", "sign_all_accounts");
        menuItem(menu, "📄", "运行日志", "最近 200 行", "log");
        menuItem(menu, "🧾", "导出运行日志", "写出到系统「下载」目录，便于反馈问题", "export_log");
        menuItem(menu, "⚙️", "设置", "关键词 / 重试上限", "settings");
        String upSub = (lastUpdate != null && lastUpdate.newer)
                ? "发现新版本 v" + lastUpdate.version + "，可下载"
                : "当前 v" + UpdateChecker.VERSION_NAME;
        menuItem(menu, "🔄", "检查更新", upSub, "update");
        menuItem(menu, "📤", "导出配置", "目标与设置存成 json，换账号不用重学", "export");
        menuItem(menu, "📥", "导入配置", "读最新导出文件，只合并不清空", "import");
        showDialog(act, "TGAutoSign · 管理", menu, "关闭");
    }

    private void showList(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        if (targets.size() == 0) {
            emptyView(box, "(暂无目标，点 ➕ 添加，或直接点 bot 的签到按钮自动学习)");
        }
        String today = todayStr();
        for (Map<String, Object> m : targets) {
            targetRow(box, m, statusOf(accountPrefix(), entryId(m), today), null);
        }
        showDialog(act, "目标列表（" + targets.size() + "）", box, "关闭");
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
                if (findTextEntry(did, t) != null) { toast("该 bot 已存在相同指令"); return; }
                learnTarget(did, t);
                Map<String, Object> m = findTextEntry(did, t);
                if (m != null) {
                    toast("✅ 已添加 " + did + " → " + t + "，立即签到…");
                    sendSign(m, currentAccount());
                }
            } catch (Throwable e) {
                toast("UID 格式错误");
            }
        });
        box.addView(ok);
        TextView tip = new TextView(act);
        tip.setTextSize(12);
        tip.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        tip.setText("提示：同一 bot 可添加多条指令（多指令自动签到）；\n回调按钮型签到无需手动添加——直接点一次 bot 的签到按钮即可自动学习。");
        tip.setPadding(dp(4), dp(10), dp(4), dp(4));
        box.addView(tip);
        showDialog(act, "添加签到目标", box, "取消");
    }

    private void showDelete(Activity act) {
        if (targets.size() == 0) { toast("暂无目标"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        String today = todayStr();
        for (Map<String, Object> m : targets) {
            targetRow(box, m, statusOf(accountPrefix(), entryId(m), today), "delete");
        }
        showDialog(act, "点选要删除的目标", box, "取消");
    }

    private void showSign(Activity act) {
        if (targets.size() == 0) { toast("暂无目标"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        if (targets.size() > 1) {
            Button all = new Button(act);
            all.setText("🚀 全部签到（" + targets.size() + " 个条目）");
            all.setOnClickListener(v -> {
                trySignAll("手动全部", true);
                toast("已命令全部签到，结果见运行日志");
            });
            box.addView(all);
        }
        String today = todayStr();
        for (Map<String, Object> m : targets) {
            targetRow(box, m, statusOf(accountPrefix(), entryId(m), today), "sign");
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

    // ==================== 更新检查 / 配置迁移（v1.2.1 新增） ====================

    private void showUpdate(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView info = new TextView(act);
        info.setTextSize(14f);
        info.setTextColor(android.graphics.Color.parseColor(txtMain(act)));
        info.setPadding(dp(4), dp(4), dp(4), dp(10));
        info.setText("当前 v" + UpdateChecker.VERSION_NAME + "\n正在检查更新…");
        box.addView(info);
        showDialog(act, "TGAutoSign · 检查更新", box, "关闭");
        UpdateChecker.checkAsync(appContext, true, mainHandler, r -> {
            try {
                if (r == null) { info.setText("刚刚已经检查过，请稍后再试"); return; }
                lastUpdate = r;
                info.setText(r.summary(UpdateChecker.VERSION_NAME));
                if (r.newer && r.apkUrl != null) {
                    menuItem(box, "⬇️", "下载 v" + r.version + " 安装包", "保存到系统「下载」目录后确认安装", "update_download");
                }
            } catch (Throwable ignored) {}
        });
    }

    private void downloadUpdate(Activity act) {
        final UpdateChecker.Result src = lastUpdate;
        if (src == null || src.apkUrl == null) { toast("该版本没有可直接下载的安装包"); return; }
        toast("开始下载 v" + src.version + "…");
        jlog("下载安装包: " + src.apkUrl);
        UpdateChecker.downloadAsync(appContext, src.apkUrl, src.apkName, mainHandler, d -> {
            if (d.networkError) { jlog("下载失败: " + d.message); toast("下载失败：" + d.message); return; }
            jlog("安装包已保存: " + d.savedPath);
            boolean opened = UpdateChecker.openSaved(appContext, d.savedUri, d.savedPath);
            toast("已保存到 " + d.savedPath + (opened ? "，请在安装界面确认" : "，请用文件管理器点开安装"));
        });
    }

    private void doExport() {
        ConfigStore.Report rep = ConfigStore.exportAll(appContext);
        if (rep.ok) {
            jlog("配置已导出: " + rep.path + "（" + rep.keys + " 项 / " + rep.prefFiles + " 个存储）");
            toast("已导出 " + rep.keys + " 项配置\n" + rep.path);
        } else {
            jlog("导出配置失败: " + rep.message);
            toast("导出失败：" + rep.message);
        }
    }

    private void doImport() {
        ConfigStore.Report rep = ConfigStore.importAll(appContext, null);
        if (rep.ok) {
            jlog("配置已导入: " + rep.path + "（" + rep.keys + " 项）");
            loadTargets();
            jlog("导入后目标数 " + targets.size() + "，当前账号 acc" + currentAccount());
            toast("已导入 " + rep.keys + " 项，当前目标 " + targets.size() + " 个\n发 /jmb → 📋 目标列表 核对");
        } else {
            jlog("导入配置失败: " + rep.message);
            toast("导入失败：" + rep.message);
        }
    }

    /** 导出运行日志到系统「下载」目录（MediaStore，无需存储权限），便于 issue 反馈 */
    private void doExportLog(Activity act) {
        try {
            List<String> copy;
            synchronized (logBuffer) { copy = new ArrayList<>(logBuffer); }
            if (copy.isEmpty()) { toast("暂无日志可导出"); return; }
            StringBuilder sb = new StringBuilder();
            sb.append("TGAutoSign v").append(UpdateChecker.VERSION_NAME)
              .append(" 宿主=").append(safePkg())
              .append(" 导出时间=").append(SDF.format(new Date())).append('\n');
            for (String line : copy) sb.append(line).append('\n');
            String content = sb.toString();
            android.content.ContentResolver cr = appContext.getContentResolver();
            android.content.ContentValues v = new android.content.ContentValues();
            String fname = "TGAutoSign-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname);
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            if (android.os.Build.VERSION.SDK_INT >= 29) v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) { toast("日志导出失败：无法写入下载目录"); return; }
            java.io.OutputStream os = cr.openOutputStream(uri);
            os.write(content.getBytes("UTF-8"));
            os.close();
            jlog("日志已导出: " + fname);
            toast("日志已导出到下载目录，文件名见运行日志\n" + fname);
        } catch (Throwable t) {
            jlog("日志导出失败: " + t);
            toast("日志导出失败：" + t);
        }
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

    /** 回调按钮 payload：TLRPC.KeyboardButtonCallback 有 data(byte[]) 与 hash(long) 字段 */
    private byte[] buttonData(Object proto) {
        if (proto == null) return null;
        try {
            Object d = getFieldVal(proto, "data");
            return d instanceof byte[] ? (byte[]) d : null;
        } catch (Throwable t) { return null; }
    }

    private long buttonHash(Object proto) {
        try {
            Object h = getFieldVal(proto, "hash");
            return h instanceof Number ? ((Number) h).longValue() : 0L;
        } catch (Throwable t) { return 0L; }
    }

    private boolean isCallbackButton(Object proto) {
        if (proto == null) return false;
        String n = proto.getClass().getName();
        return n.contains("Callback");
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
                long u = ((Number) did).longValue();
                if (isCallbackButton(proto)) {
                    byte[] data = buttonData(proto);
                    if (data != null && data.length > 0) {
                        learnCallback(u, t, data, buttonHash(proto));
                    } else {
                        jlog("[按钮] uid=" + u + " text=" + t + "（无回调 data，可能是链接/游戏按钮，跳过）");
                    }
                } else {
                    learnTarget(u, t);
                }
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
                long u = ((Number) did).longValue();
                if (isCallbackButton(proto)) {
                    byte[] data = buttonData(proto);
                    if (data != null && data.length > 0) {
                        learnCallback(u, t, data, buttonHash(proto));
                    } else {
                        jlog("[按钮] uid=" + u + " text=" + t + "（无回调 data，可能是链接/游戏按钮，跳过）");
                    }
                } else {
                    learnTarget(u, t);
                }
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
                        if (data instanceof byte[]) {
                            long u = ((Number) uid).longValue();
                            byte[] d = (byte[]) data;
                            // 自己重放的回调请求 → 标记该条目已签
                            markSignedFromCallback(u, d);
                            // 用户手动点过但按钮 hook 未捕获时，网络层兜底学习
                            if (findCbEntry(u, d) == null && d.length > 0) {
                                String disp = "回调按钮";
                                try {
                                    String s2 = new String(d, "ISO-8859-1");
                                    if (s2 != null && s2.length() > 0) {
                                        String clean = s2.trim();
                                        if (clean.length() > 20) clean = clean.substring(0, 20);
                                        disp = clean;
                                    }
                                } catch (Throwable ignored) {}
                                if (keywordMatched(disp)) {
                                    Object h = getFieldVal(req, "hash");
                                    learnCallback(u, disp, d, h instanceof Number ? ((Number) h).longValue() : 0L);
                                }
                            }
                        } else {
                            jlog("[回调按钮] uid=" + uid + "（无 data，可能是链接/游戏按钮，不学习）");
                        }
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
                    String prefix = accountPrefix();
                    String lower = replyText.toLowerCase();
                    String[] failWords = {"失败","未成功","请先","不能","无法","不可","错误","已过期","未关注","没有资格","failed","invalid","rejected","not allowed","try again"};
                    for (String w : failWords) {
                        if (lower.contains(w)) {
                            // 撤销该 bot 最近 10 分钟内发过签到请求的条目（多指令时只动刚发的那条）
                            for (Map<String, Object> m : targets) {
                                if (entryDid(m) != did) continue;
                                String id = entryId(m);
                                long sentAt = prefs.getLong(prefix + "sent_at_" + id, 0);
                                if (System.currentTimeMillis() - sentAt > 10L * 60 * 1000) continue;
                                int cur = prefs.getInt(prefix + "retry_" + id, 0);
                                prefs.edit()
                                    .remove(prefix + "last_" + id)
                                    .putInt(prefix + "retry_" + id, cur + 1)
                                    .putLong(prefix + "retry_at_" + id, System.currentTimeMillis() + backoffDelay(Math.max(cur, 1)))
                                    .commit();
                                jlog("【回复判定】" + did + " bot 回复: " + replyText + " → 判定未成功，撤销已签并安排重试 (id=" + id + ")");
                                toast("⚠️ " + did + " 可能未签到成功: " + replyText);
                                return;
                            }
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
        return getMessagesController(currentAccount());
    }

    private Object getMessagesController(int account) {
        try {
            return staticInvoke(classEx("org.telegram.messenger.MessagesController"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getMessagesStorage() {
        return getMessagesStorage(currentAccount());
    }

    private Object getMessagesStorage(int account) {
        try {
            return staticInvoke(classEx("org.telegram.messenger.MessagesStorage"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
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
