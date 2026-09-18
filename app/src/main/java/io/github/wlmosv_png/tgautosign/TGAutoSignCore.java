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
    private static final String DEF_KEYWORDS = "签到,打卡,checkin,/checkin,claim,领取,签到领,/qd,/qiandao,/sign,/daily,daily,/clock,/kaoqin";
    private String LEARN_KEYWORDS = DEF_KEYWORDS;

    private final Context appContext;
    private final ClassLoader cl;
    private final SharedPreferences prefs;
    private final Set<String> seenSignals = cs();
    private long lastSeenClean = 0L;
    private final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private volatile boolean started = false;
    private volatile long captureArmedAt = 0L;
    private final Object TLOCK = new Object();
    private final List<Map<String, Object>> targets = new ArrayList<>();
    private long lastTryTime = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> pendingSigns = cs();
    private boolean receiverRegistered = false;
    private int lastAccount = -1;
    // v1.4.0：回调捕获/绑定/调试台 + 每条目前置命令 + 关键词解耦
    private boolean AUTO_LEARN_FILTER = true;          // true=点击学习仍按关键词过滤；绑定/测试/调试台永不受限
    private volatile boolean captureArmed = false;       // 捕获模式已武装，等待下一次按钮点击
    private volatile long lastCapDid = 0L;               // 最近一次采样到的会话 uid
    private volatile int  lastCapMid = 0;                // 最近一次采样到的消息 id
    private volatile List<Object[]> lastCapBtns = null;  // 最近一次采样到的整张键盘

    // 界面版：最近的前台 Activity（用于弹管理对话框）
    private volatile Activity lastActivity = null;
    // 日志环形缓冲（最近 200 行）
    private static final int LV_DEBUG = 0, LV_INFO = 1, LV_OK = 2, LV_WARN = 3, LV_ERR = 4;
    private static final java.util.concurrent.ExecutorService LOG_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "TGAutoSignLog");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });
    private static final class LogLine {
        final long ts; final int lv; final String msg;
        LogLine(long t, int l, String m) { ts = t; lv = l; msg = m; }
        String flat() {
            String tag = lv == LV_DEBUG ? "[调试]" : lv == LV_OK ? "[成功]"
                    : lv == LV_WARN ? "[警告]" : lv == LV_ERR ? "[错误]" : "";
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ts))
                    + (tag.length() == 0 ? "" : " " + tag) + "  " + msg;
        }
    }
    private final List<LogLine> logBuffer = new ArrayList<LogLine>();
    private final List<String> diskQueue = new ArrayList<String>();
    private long diskFlushAt = 0L;
    private int logFilter = LV_DEBUG;
    private boolean logShowDebug = false;
    private boolean logDesc = true;
    private String logQuery = "";
    private LinearLayout logList;
    private TextView logStat;

    private final Random random = new Random();
    private int DAILY_CAP = 60;                  // 每账号每日动作上限（防风控），/jmb 设置里可改
    private volatile long lastPaceAt = 0L;        // 连发节奏锁（jitter 用）
    private String WAKE_CMD = "";
    private String WINDOW = "";
    private boolean lastPollInWindow = true;
    private String SIGN = "wlmosv";
    private boolean AUTO_LEARN = false;                       // 非空=回调签到前先发的唤醒命令（拉面板）
    private final Set<String> wakeFired = cs(); // 唤醒只触发一次，防循环
    /** 最近一次更新检查结果（/jmb 菜单与下载动作读取） */
    private volatile UpdateChecker.Result lastUpdate = null;
    private String lastRound = "";

    // ---------------- Live Panel 引擎（1.4.5）：面板实时缓存 + 事件驱动 ----------------
    private static final class CbButton {
        final String text; final byte[] data; final long hash; final int msgId;
        CbButton(String t, byte[] d, long h, int m){ text=t; data=d; hash=h; msgId=m; }
    }
    private static final class PanelLive {
        final long did; volatile long ts; volatile int msgId;
        final List<CbButton> buttons = new ArrayList<CbButton>();
        CbButton lastClicked = null;
        PanelLive(long d){ did=d; }
    }
    private final Map<Long, PanelLive> panelLive = new HashMap<Long, PanelLive>();
    private final Set<Long> panelHitBusy = new HashSet<Long>();

    /** 没有任何目标的已登录账号数（用于启动提示里提醒） */
        private int accountsWithoutTargets() {
            int n = 0;
            try {
                int total = activatedAccounts();
                for (int i = 0; i < total; i++) if (acctTargetCount(i) == 0) n++;
            } catch (Throwable ignored) {}
            return n;
        }
    private int acctTargetCount(int account) {
        try {
            List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
            loadTargetsInto(accountPrefix(account), l);
            return l.size();
        } catch (Throwable t) { return 0; }
    }

    /** 每个账号一行：目标数 + 今天已签数 */
    /** 终端风圆角面板：bg 填充色 + border 描边色 */
    private android.graphics.drawable.GradientDrawable termBorder(Context c, int bg, int border) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(Theme.dp(c, 14));
        try { g.setColor(bg); g.setStroke(Theme.dp(c, 1), border); } catch (Throwable ignored) {}
        return g;
    }

    /** 终端日志卡内容：按最近 4 行重建（面板开着时每 4 秒自动刷新一次） */
    private void renderTermBody(final LinearLayout body, final Activity act2) {
        try {
            body.removeAllViews();
            java.util.List<LogLine> recent = new ArrayList<>();
            synchronized (logBuffer) { if (logBuffer.size() > 0) { int f = Math.max(0, logBuffer.size() - 4); for (int i = f; i < logBuffer.size(); i++) recent.add(logBuffer.get(i)); } }
            if (recent.isEmpty()) {
                TextView e1 = new TextView(act2); e1.setTextSize(11); e1.setTypeface(android.graphics.Typeface.MONOSPACE); e1.setTextColor(Theme.termFaint(act2));
                e1.setText("$ (暂无日志消息，稍后自动出现)"); body.addView(e1);
                return;
            }
            for (LogLine l : recent) {
                TextView lv2 = new TextView(act2); lv2.setTextSize(10); lv2.setTypeface(android.graphics.Typeface.MONOSPACE);
                String lineText = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(l.ts)) + "  " + l.msg;
                if (lineText.length() > 46) lineText = lineText.substring(0, 46) + "...";
                lv2.setText("$ " + lineText);
                lv2.setTextColor(l.lv == LV_ERR ? Theme.termPink(act2) : l.lv == LV_OK ? Theme.termGreen(act2) : l.lv == LV_WARN ? Theme.termAmber(act2) : Theme.termMuted(act2));
                body.addView(lv2);
            }
        } catch (Throwable ignored) {}
    }

    /** 终端风按钮工厂：所有对话框按钮统一走它（等宽 + 暗底 + 霓虹描边） */
    private Button mkBtn(Context c) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        b.setTextColor(Theme.termCyan(c));
        b.setPadding(dp(10), dp(9), dp(10), dp(9));
        try { b.setBackground(termBorder(c, Theme.withAlpha(Theme.termCyan(c), 0x14), Theme.withAlpha(Theme.termCyan(c), 0x59))); } catch (Throwable ignored) {}
        return b;
    }

    /** 终端风按钮（强调：荧光绿，用于主操作/确认） */
    private Button mkBtnPrimary(Context c) {
        Button b = mkBtn(c);
        b.setTextColor(Theme.termGreen(c));
        try { b.setBackground(termBorder(c, Theme.withAlpha(Theme.termGreen(c), 0x14), Theme.withAlpha(Theme.termGreen(c), 0x66))); } catch (Throwable ignored) {}
        return b;
    }

    /** 终端风按钮（危险：品红，用于删除/清空） */
    private Button mkBtnDanger(Context c) {
        Button b = mkBtn(c);
        b.setTextColor(Theme.termPink(c));
        try { b.setBackground(termBorder(c, Theme.withAlpha(Theme.termPink(c), 0x14), Theme.withAlpha(Theme.termPink(c), 0x66))); } catch (Throwable ignored) {}
        return b;
    }

    /** 点击主界面标题 → 作者 GitHub */
    private void openGithub() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/wlmosv-png"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(i);
            jlog("[界面] 打开作者主页 github.com/wlmosv-png");
        } catch (Throwable t) { toast("打开作者主页失败: " + t); }
    }

    /** 状态徽章：开=绿实心点，关=灰空心点（主界面状态卡片用） */
    private TextView badge(final Activity act, String label, boolean on) {
        TextView b = new TextView(act);
        b.setText((on ? "[ON]  " : "[OFF] ") + label);
        b.setTextSize(11);
        b.setTypeface(android.graphics.Typeface.MONOSPACE);
        b.setTextColor(on ? Theme.termGreen(act) : Theme.termMuted(act));
        b.setPadding(dp(10), dp(5), dp(10), dp(5));
        try {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(12));
            g.setColor(on ? Theme.withAlpha(Theme.termGreen(act), 0x14) : Theme.withAlpha(Theme.termMuted(act), 0x0A));
            g.setStroke(dp(1), on ? Theme.withAlpha(Theme.termGreen(act), 0x66) : Theme.withAlpha(Theme.termMuted(act), 0x33));
            b.setBackground(g);
        } catch (Throwable ignored) {}
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        if (on) {
            final android.animation.ValueAnimator ba = android.animation.ValueAnimator.ofFloat(0.6f, 1f);
            ba.setDuration(2400); ba.setRepeatCount(android.animation.ValueAnimator.INFINITE); ba.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            ba.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) { b.setAlpha(((Float) a.getAnimatedValue()).floatValue()); }
            });
            b.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(android.view.View vv) { ba.start(); }
                @Override public void onViewDetachedFromWindow(android.view.View vv) { ba.cancel(); b.setAlpha(1f); }
            });
        }
        return b;
    }

    private String perAccountLine() {
        StringBuilder sb = new StringBuilder();
        try {
            int n = activatedAccounts();
            String today = todayStr();
            for (int i = 0; i < n; i++) {
                List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
                loadTargetsInto(accountPrefix(i), l);
                int done = 0;
                for (Map<String, Object> m : l) {
                    try { if (today.equals(prefs.getString(accountPrefix(i) + "last_" + entryId(m), ""))) done++; } catch (Throwable ignored) {}
                }
                if (i > 0) sb.append('\n');
                sb.append(accountLabel(i)).append("：目标 ").append(l.size()).append(" · 已签 ").append(done);
                if (l.isEmpty()) sb.append("（还没有目标，去该账号学一个）");
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    /** 把当前账号的目标复制给其它账号（只复制目标，不带已签状态） */
    private void showCopyTargets(final Activity act) {
        if (act == null) { toast("请在 TG 界面使用 /jmb"); return; }
        final int cur = currentAccount();
        final List<Map<String, Object>> mine = targetsSnapshot();
        if (mine.isEmpty()) { toast("当前账号还没有目标，先学一个再复制"); return; }
        int n = activatedAccounts();
        if (n <= 1) { toast("只有一个登录账号，不用复制"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.dp(act, 16), Theme.dp(act, 8), Theme.dp(act, 16), Theme.dp(act, 8));
        TextView info = new TextView(act);
        info.setTextSize(13);
        info.setTextColor(Theme.termTxt(act));
        info.setText("把 " + accountLabel(cur) + " 的 " + mine.size() + " 个目标复制到其它账号。\n只复制目标本身，不带「今天已签」和重试记录。");
        box.addView(info);
        for (int i = 0; i < n; i++) {
            if (i == cur) continue;
            final int target = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Theme.dp(act, 4), Theme.dp(act, 12), Theme.dp(act, 4), Theme.dp(act, 12));
            TextView t = new TextView(act);
            t.setTextSize(15);
            t.setTextColor(Theme.termTxt(act));
            t.setText("→ " + accountLabel(i) + "（现有 " + acctTargetCount(i) + " 个）");
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView ar = new TextView(act);
            ar.setTextSize(18);
            ar.setText("\u203a");
            row.addView(ar);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { copyTargetsTo(target, cur, mine); }
            });
            box.addView(row);
            View div = new View(act);
            div.setBackgroundColor(Theme.line(act));
            box.addView(div, new LinearLayout.LayoutParams(-1, 1));
        }
        showDialog(act, "复制目标到其它账号", box, "关闭");
    }

    private void copyTargetsTo(int account, int fromAccount, List<Map<String, Object>> src) {
        String prefix = accountPrefix(account);
        List<Map<String, Object>> exist = new ArrayList<Map<String, Object>>();
        try { loadTargetsInto(prefix, exist); } catch (Throwable ignored) {}
        int added = 0, skipped = 0;
        for (Map<String, Object> m : src) {
            try {
                String kind = entryKind(m);
                boolean dup = false;
                for (Map<String, Object> e : exist) {
                    if (entryDid(e) != entryDid(m)) continue;
                    if (!kind.equals(entryKind(e))) continue;
                    if (KIND_CB.equals(kind)) { if (Arrays.equals(entryData(e), entryData(m))) dup = true; }
                    else if (entryText(e).equals(entryText(m))) dup = true;
                    if (dup) break;
                }
                if (dup) { skipped++; continue; }
                Map<String, Object> cp = new HashMap<String, Object>(m);
                persistEntry(prefix, cp);
                added++;
            } catch (Throwable ignored) {}
        }
        String msg = accountLabel(account) + "：新增 " + added + " 个目标" + (skipped > 0 ? "（跳过重复 " + skipped + " 个）" : "");
        if (added == 0) logw("【复制目标】" + msg + "——一个都没加，可能已经复制过了");
        else logs("【复制目标】" + accountLabel(fromAccount) + " → " + msg);
        toast(added == 0 ? "没有需要复制的新目标（可能早就复制过了）"
                : "已复制到 " + accountLabel(account) + "：新增 " + added + " 个");
    }

    public TGAutoSignCore(Context appContext, ClassLoader cl) {
        this.appContext = appContext.getApplicationContext() != null ? appContext.getApplicationContext() : appContext;
        this.cl = cl;
        this.prefs = this.appContext.getSharedPreferences("tg_autosign_gen", 0);
    }


    public void start() {
        migrateLegacyKeys();
        if (started) { return; }
        synchronized (TLOCK) { loadTargetsLocked(); started = true; }
        try {
            if (prefs.contains("jmb_keywords")) LEARN_KEYWORDS = prefs.getString("jmb_keywords", DEF_KEYWORDS);
            if (prefs.contains("jmb_retry")) RETRY_LIMIT = prefs.getInt("jmb_retry", RETRY_LIMIT);
            WAKE_CMD = prefs.getString("jmb_wake_cmd", "");
            if (prefs.contains("jmb_window")) WINDOW = prefs.getString("jmb_window", "");
            if (bootReadyAt == 0L) bootReadyAt = System.currentTimeMillis() + 30000L;
            AUTO_LEARN = prefs.getBoolean("jmb_autolearn", AUTO_LEARN);
            AUTO_LEARN_FILTER = prefs.getBoolean("jmb_alfilter", AUTO_LEARN_FILTER);
            AUTO_LEARN_NET = prefs.getBoolean("jmb_autolearn_net", AUTO_LEARN_NET);
        } catch (Throwable ignored) {}
        try { lastAccount = currentAccount(); } catch (Throwable ignored) {}
        registerNetworkReceiver();
        registerActivityListener();
        mainHandler.postDelayed(() -> { try { jlog("=== 启动立即补签 ==="); trySignAll("启动立即", true); } catch (Throwable ignored) {} }, 10000L);
        schedulePoll();
        jlog("=== TGAutoSign 模块 v" + UpdateChecker.VERSION_NAME + " 已加载 ===");
        jlog("宿主: " + safePkg() + " 账号: " + currentAccount() + " 目标: " + targetsSnapshot().size()
            + " 按钮学习: " + (AUTO_LEARN ? "开" : "关") + " 网络学习: " + (AUTO_LEARN_NET ? "开" : "关"));
        jlog("使用: 在任意聊天输入 /jmb 打开管理界面");
        checkUpdateSilently();
            mainHandler.postDelayed(new Runnable() { @Override public void run() { bootToast(); } }, 15000L);
        mainHandler.postDelayed(new Runnable(){ public void run(){ try{ if(!prefs.getBoolean("jmb_tut_seen",false)){ Activity a=lastActivity; if(a!=null){ prefs.edit().putBoolean("jmb_tut_seen",true).apply(); showTutorial(a);} } }catch(Throwable ignored){} } }, 4000L);
    }


    /** 静默检查更新：12 小时冷却，任何失败都不影响签到主流程 */
    private void checkUpdateSilently() {
        try {
            UpdateChecker.checkAsync(appContext, false, mainHandler, r -> {
                if (r == null) return;
                if (r.networkError) { logw("检查更新未成功: " + r.message); return; }
                lastUpdate = r;
                if (r.newer) {
                    logs("发现新版本 v" + r.version + "（当前 v" + UpdateChecker.VERSION_NAME + "）");
                    toast("TGAutoSign 有新版本 v" + r.version + "：发 /jmb → 🔄 检查更新");
                } else {
                    logd("检查更新：已是最新 v" + UpdateChecker.VERSION_NAME);
                }
            });
        } catch (Throwable t) { jlog("检查更新异常(忽略): " + t); }
    }

    // ---------------- 工具 ----------------
    private String todayStr() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

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

    /** Toast 策略：同类提示 5 分钟内只弹一次 / 每天最多一次 / 一轮签到结果合并成一条 */
        private final Map<String, Long> toastAt = new HashMap<String, Long>();
        private int roundOkN = 0, roundErrN = 0;
        private boolean roundScheduled = false;

        void toastOnce(String key, String msg) {
            try {
                long now = System.currentTimeMillis();
                synchronized (toastAt) {
                    Long prev = toastAt.get(key);
                    if (prev != null && now - prev < 5L * 60L * 1000L) return;
                    toastAt.put(key, now);
                }
                toast(msg);
            } catch (Throwable t) { toast(msg); }
        }

        void toastDaily(String key, String msg) {
            try {
                String today = todayStr();
                String k = "jmb_toast_" + key;
                if (today.equals(prefs.getString(k, ""))) return;
                prefs.edit().putString(k, today).apply();
            } catch (Throwable ignored) {}
            toast(msg);
        }

        void noteResult(boolean good) {
            boolean schedule;
            try { bumpDailyResult(currentAccount(), good); bumpDaily(currentAccount()); } catch (Throwable ignored) {}
            synchronized (toastAt) {
                if (good) roundOkN++; else roundErrN++;
                schedule = !roundScheduled;
                if (schedule) roundScheduled = true;
            }
            if (schedule) {
                mainHandler.postDelayed(new Runnable() {
                    @Override public void run() { flushRoundToast(); }
                }, 8000L);
            }
        }

        private void flushRoundToast() {
            int ok, er;
            synchronized (toastAt) {
                ok = roundOkN; er = roundErrN;
                roundOkN = 0; roundErrN = 0; roundScheduled = false;
            }
            if (ok == 0 && er == 0) return;
            if (er == 0) toastOnce("round|" + todayStr(), "✅ 签到完成 " + ok + " 个");
            else toastOnce("round|" + todayStr(), "签到完成 " + ok + " 个，" + er + " 个没成功（/jmb → 📄 运行日志 里有原因）");
        }
    void toast(String msg) {
        try {
            mainHandler.post(() -> {
                try { Toast.makeText(appContext, String.valueOf(msg), Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    void jlog(String msg) { jlog(guessLevel(msg), msg); }
        void logd(String msg) { jlog(LV_DEBUG, msg); }
        void logs(String msg) { jlog(LV_OK, msg); }
        void logw(String msg) { jlog(LV_WARN, msg); }
        void loge(String msg) { jlog(LV_ERR, msg); }

        void jlog(int lv, String msg) {
            try {
                long now = System.currentTimeMillis();
                LogLine l = new LogLine(now, lv, String.valueOf(msg));
                Log.i(TAG, msg);
                synchronized (logBuffer) {
                    logBuffer.add(l);
                    while (logBuffer.size() > 800) logBuffer.remove(0);
                    diskQueue.add(l.flat());
                    if (diskQueue.size() >= 20 || now - diskFlushAt > 5000L) {
                        final List<String> batch = new ArrayList<String>(diskQueue);
                        diskQueue.clear();
                        diskFlushAt = now;
                        final java.io.File dir = logDir();
                        LOG_IO.execute(new Runnable() { @Override public void run() { appendDisk(dir, batch); } });
                    }
                }
            } catch (Throwable ignored) {}
        }

        private java.io.File logDir() {
            try {
                java.io.File d = appContext.getExternalFilesDir("tgautosign");
                if (d == null) d = appContext.getFilesDir();
                if (d != null && !d.exists()) d.mkdirs();
                return d;
            } catch (Throwable t) { return null; }
        }

        private static void appendDisk(java.io.File dir, List<String> batch) {
            try {
                if (dir == null || batch == null || batch.isEmpty()) return;
                java.io.File cur = new java.io.File(dir, "run.log");
                if (cur.length() > 256L * 1024L) {
                    for (int i = 4; i >= 1; i--) {
                        java.io.File old = new java.io.File(dir, "run." + i + ".log");
                        if (!old.exists()) continue;
                        if (i == 4) old.delete();
                        else old.renameTo(new java.io.File(dir, "run." + (i + 1) + ".log"));
                    }
                    cur.renameTo(new java.io.File(dir, "run.1.log"));
                }
                StringBuilder sb = new StringBuilder();
                for (String s : batch) sb.append(s).append('\n');
                java.io.FileOutputStream os = new java.io.FileOutputStream(cur, true);
                os.write(sb.toString().getBytes("UTF-8"));
                os.close();
            } catch (Throwable ignored) {}
        }

        /** 内存与落盘历史合并（按整行文本去重），供日志页与导出使用 */
        private List<LogLine> mergedLog(int max) {
            List<LogLine> out = new ArrayList<LogLine>();
            try {
                synchronized (logBuffer) { out.addAll(logBuffer); }
                java.io.File dir = logDir();
                java.io.File f = dir == null ? null : new java.io.File(dir, "run.log");
                if (f != null && f.exists() && f.length() < 2L * 1024 * 1024) {
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
                    for (LogLine l : out) seen.add(l.flat());
                    java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                    String ln;
                    while ((ln = br.readLine()) != null) lines.add(ln);
                    br.close();
                    int from = Math.max(0, lines.size() - max);
                    List<LogLine> older = new ArrayList<LogLine>();
                    for (int i = from; i < lines.size(); i++) {
                        String s = lines.get(i);
                        if (s == null || s.trim().length() == 0 || seen.contains(s)) continue;
                        older.add(parseLogLine(s));
                    }
                    out.addAll(0, older);
                }
            } catch (Throwable ignored) {}
            while (out.size() > max) out.remove(0);
            return out;
        }

        private static LogLine parseLogLine(String flat) {
            try {
                java.util.Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(flat.substring(0, 19));
                String rest = flat.substring(19).trim();
                int lv = LV_INFO;
                if (rest.startsWith("[调试]")) { lv = LV_DEBUG; rest = rest.substring(5).trim(); }
                else if (rest.startsWith("[成功]")) { lv = LV_OK; rest = rest.substring(5).trim(); }
                else if (rest.startsWith("[警告]")) { lv = LV_WARN; rest = rest.substring(5).trim(); }
                else if (rest.startsWith("[错误]")) { lv = LV_ERR; rest = rest.substring(5).trim(); }
                return new LogLine(d == null ? 0L : d.getTime(), lv, rest);
            } catch (Throwable t) { return new LogLine(0L, LV_INFO, flat); }
        }


        /** 旧的 jlog(String) 调用点按关键词推断级别；重要路径已改成显式 logs/logw/loge */
        private static int guessLevel(String m) {
            if (m == null) return LV_INFO;
            if (m.contains("异常") || m.contains("失败") || m.contains("错误") || m.contains("崩溃")) return LV_ERR;
            if (m.contains("重试") || m.contains("退避") || m.contains("限流") || m.contains("未找到")
                    || m.contains("跳过") || m.contains("警告") || m.contains("没有")) return LV_WARN;
            if (m.contains("成功") || m.contains("已添加") || m.contains("已保存") || m.contains("已绑定")
                    || m.contains("已删除") || m.contains("已导入") || m.contains("已复制") || m.contains("已发送")) return LV_OK;
            if (m.contains("[按钮]") || m.contains("dump:") || m.contains("[候选]") || m.contains("已登记")) return LV_DEBUG;
            return LV_INFO;
        }

    // ---------------- 目标条目模型（v1.3.0：一 bot 多指令 + 回调按钮） ----------------

    private static java.util.Set<String> cs() {
        return java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    }

    private long lastSyncMs = 0L;
    private void syncAccount() {
        long n0 = System.currentTimeMillis();
        if (n0 - lastSyncMs < 2000L) return;
        lastSyncMs = n0;
        try {
            int cur = currentAccount();
            synchronized (TLOCK) {
                if (cur != lastAccount) {
                    lastAccount = cur;
                    loadTargetsLocked();
                    jlog("账号跟随: acc" + cur + "，当前目标 " + targets.size() + " 个");
                }
            }
        } catch (Throwable ignored) {}
    }

    private List<Map<String, Object>> targetsSnapshot() {
        synchronized (TLOCK) { return new ArrayList<Map<String, Object>>(targets); }
    }

    private boolean isPendingFresh(String id) {
        if (!pendingSigns.contains(id)) return false;
        long sentAt = 0L;
        try { sentAt = prefs.getLong(accountPrefix() + "sent_at_" + id, 0L); } catch (Throwable ignored) {}
        if (System.currentTimeMillis() - sentAt > 90L * 1000L) {
            pendingSigns.remove(id);
            jlog("目标 " + id + " 发送状态超过 90 秒未回调，已清理并允许重试");
            return false;
        }
        return true;
    }

    private static boolean isJmbCommand(String raw) {
        String t = String.valueOf(raw).trim();
        if (t.length() < 4 || !t.startsWith("/jmb")) return false;
        if (t.length() == 4) return true;
        char c = t.charAt(4);
        return c == ' ' || c == '\n' || c == '\t';
    }

    private static final String KIND_TEXT = "text";
    private static final String KIND_CB = "cb";

    private String entryId(Map<String, Object> m) { return String.valueOf(m.get("id")); }
    private long entryDid(Map<String, Object> m) { return ((Number) m.get("did")).longValue(); }
    private String entryText(Map<String, Object> m) { return String.valueOf(m.get("text")); }
    private String entryKind(Map<String, Object> m) { return m.get("kind") == null ? KIND_TEXT : String.valueOf(m.get("kind")); }
    private byte[] entryData(Map<String, Object> m) { return m.get("data") instanceof byte[] ? (byte[]) m.get("data") : null; }
    private long entryHash(Map<String, Object> m) { return m.get("hash") instanceof Number ? ((Number) m.get("hash")).longValue() : 0L; }
    private int entryMsgId(Map<String, Object> m) { return m.get("msgId") instanceof Number ? ((Number) m.get("msgId")).intValue() : 0; }

    private Map<String, Object> findEntryById(String id) {
        for (Map<String, Object> m : targetsSnapshot()) {
            if (entryId(m).equals(id)) return m;
        }
        return null;
    }

    private Map<String, Object> findTextEntry(long did, String text) {
        for (Map<String, Object> m : targetsSnapshot()) {
            if (entryDid(m) == did && KIND_TEXT.equals(entryKind(m)) && entryText(m).equals(String.valueOf(text))) return m;
        }
        return null;
    }

    private Map<String, Object> findCbEntry(long did, byte[] data) {
        if (data == null) return null;
        for (Map<String, Object> m : targetsSnapshot()) {
            if (entryDid(m) == did && KIND_CB.equals(entryKind(m)) && Arrays.equals(entryData(m), data)) return m;
        }
        return null;
    }

    /** 新条目 id：<did>_<seq>（文本）或 <did>_cb<seq>（回调），与旧键 acc{N}_learned_<did> 永不冲突 */
    private String nextEntryId(long did, String kind) {
        int maxSeq = 0;
        String prefix = did + (KIND_CB.equals(kind) ? "_cb" : "_");
        for (Map<String, Object> m : targetsSnapshot()) {
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
            e.putInt(prefix + "msg_id_" + id, entryMsgId(m));
            String pj = m.get("pre") == null ? null : String.valueOf(m.get("pre"));
            if (pj != null && pj.length() > 0 && !"null".equals(pj)) e.putString(prefix + "pre_" + id, pj);
            else e.remove(prefix + "pre_" + id);
            if (m.get("loc") != null) e.putString(prefix + "loc_" + id, String.valueOf(m.get("loc")));
            else e.remove(prefix + "loc_" + id);
        }
        e.apply();
    }


    private void removeEntryKeys(String prefix, String id) {
        prefs.edit()
            .remove(prefix + "learned_" + id)
            .remove(prefix + "kind_" + id)
            .remove(prefix + "did_" + id)
            .remove(prefix + "data_" + id)
            .remove(prefix + "hash_" + id)
            .remove(prefix + "msg_id_" + id)
            .remove(prefix + "last_" + id)
            .remove(prefix + "retry_" + id)
            .remove(prefix + "retry_at_" + id)
            .remove(prefix + "sent_at_" + id)
            .commit();
    }

    private static final String[] ENTRY_MARKERS =
        {"learned_","kind_","did_","data_","hash_","msg_id_","pre_","loc_","last_","retry_","retry_at_","retry_day_","sent_at_"};
    private static final Set<String> GLOBAL_KEYS = new HashSet<String>(Arrays.asList(
        "jmb_keywords","jmb_retry","jmb_wake_cmd","jmb_alfilter","jmb_autolearn","jmb_autolearn_net","jmb_tut_seen","jmb_prompt_day","update_cooldown_at","update_seen_code","update_last_notice","update_last_error"));

    private static final String[] ENTRY_HEADS = {"learned_", "kind_", "did_", "data_", "hash_", "msg_id_",
            "pre_", "loc_", "last_", "retry_", "retry_at_", "retry_day_", "sent_at_"};

    /** 是不是"目标/状态"键（带 acc 前缀或不带的老格式）；不是的就是设置项 */
    private static boolean isEntryKey(String k) {
        if (k == null) return false;
        String body = k;
        if (k.startsWith("acc")) {
            int i = k.indexOf('_');
            if (i > 0) body = k.substring(i + 1);
        }
        for (String h : ENTRY_HEADS) if (body.startsWith(h)) return true;
        return false;
    }

    /** 一次性搬正 v1.2.2 之前的无前缀老键：能并到 acc0_ 就并，撞车就丢弃 */
    private int migrateLegacyKeys() {
        int moved = 0, dropped = 0;
        try {
            java.util.Map<String, ?> all = prefs.getAll();
            SharedPreferences.Editor e = prefs.edit();
            for (String k : new ArrayList<String>(all.keySet())) {
                if (k.startsWith("acc") || !isEntryKey(k)) continue;
                String nk = "acc0_" + k;
                Object v = all.get(k);
                e.remove(k);
                if (prefs.contains(nk)) { dropped++; continue; }
                if (v instanceof String) e.putString(nk, (String) v);
                else if (v instanceof Integer) e.putInt(nk, (Integer) v);
                else if (v instanceof Long) e.putLong(nk, (Long) v);
                else if (v instanceof Boolean) e.putBoolean(nk, (Boolean) v);
                else if (v instanceof Float) e.putFloat(nk, (Float) v);
                else { dropped++; continue; }
                moved++;
            }
            if (moved + dropped > 0) e.apply();
        } catch (Throwable t) { logw("迁移历史键失败: " + t); }
        if (moved + dropped > 0) jlog("清理历史遗留配置：搬正 " + moved + " 个，丢弃重复 " + dropped + " 个");
        return moved + dropped;
    }

    /** 每天第一次加载时提示一条汇总（之前是每次重启都弹两条，很吵） */
    private void bootToast() {
        try {
            int t = targetsSnapshot().size();
            int noTarget = accountsWithoutTargets();
            String msg = "TGAutoSign 今天已就位：" + accountLabel(currentAccount()) + " " + t + " 个目标";
            if (noTarget > 0) msg += "；还有 " + noTarget + " 个账号没学习目标";
            toastDaily("boot", msg);
        } catch (Throwable ignored) {}
    }

    private void removeEntryEverywhere(String id) {
            SharedPreferences.Editor e = prefs.edit();
            int removed = 0;
            for (String k : new ArrayList<String>(prefs.getAll().keySet())) {
                if (!isEntryKey(k)) continue;
                String body = k;
                if (k.startsWith("acc")) { int i = k.indexOf('_'); if (i > 0) body = k.substring(i + 1); }
                for (String mk : ENTRY_HEADS) {
                    if (body.equals(mk + id) || body.endsWith("_" + mk + id)) { e.remove(k); removed++; break; }
                }
            }
            e.apply();
            logs("【删除】跨账号清除 id=" + id + " 共 " + removed + " 个键");
        }

    private int clearAllConfig() {
            SharedPreferences.Editor e = prefs.edit();
            int removed = 0;
            for (String k : new ArrayList<String>(prefs.getAll().keySet())) {
                if (!isEntryKey(k)) continue;   // 设置项（jmb_* 等）一律保留
                e.remove(k);
                removed++;
            }
            e.apply();
            synchronized (TLOCK) { targets.clear(); }
            lastAccount = currentAccount();
            loadTargets();
            return removed;
        }

    private void confirmClearAll(final Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView warn = new TextView(act);
        warn.setTextSize(14);
        warn.setText("将删除【所有账号】的全部签到目标与已签/重试状态，仅保留关键词/重试上限/唤醒命令设置。此操作不可撤销，建议先导出配置备份。");
        box.addView(warn);
        Button ok = mkBtn(act);
        ok.setText("确认清空全部配置");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = clearAllConfig();
                toast("已清空 " + n + " 项配置");
                logs("【清空配置】删除 " + n + " 个键，当前账号目标数=" + targets.size());
            }
        });
        box.addView(ok);
        showDialog(act, "清空所有配置", box, "取消");
    }

    private void sendWake(Object peer, int account) throws Exception {
        Class<?> sendCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_sendMessage");
        Object req = sendCls.newInstance();
        setFieldVal(req, "peer", peer);
        setFieldVal(req, "message", WAKE_CMD);
        setFieldVal(req, "random_id", random.nextLong());
        Object cm = staticInvoke(classEx("org.telegram.tgnet.ConnectionsManager"), "getInstance",
            new Class<?>[]{int.class}, new Object[]{account});
        Object delegate = Proxy.newProxyInstance(classEx("org.telegram.tgnet.RequestDelegate").getClassLoader(),
            new Class<?>[]{classEx("org.telegram.tgnet.RequestDelegate")},
            new InvocationHandler() {
                @Override public Object invoke(Object p, Method m, Object[] a) { return null; }
            });
        invoke(cm, "sendRequest",
            new Class<?>[]{classEx("org.telegram.tgnet.TLObject"), classEx("org.telegram.tgnet.RequestDelegate")},
            new Object[]{req, delegate});
    }

    private static Object callSafe(Object o, String n){ try { return o.getClass().getMethod(n).invoke(o); } catch (Throwable t){ return null; } }
    private static Object getFieldValSafe(Object o, String n){ if (o==null) return null; try { return getFieldVal(o,n); } catch (Throwable t){ return null; } }
    private static String strOr(Object o, String d){ return o==null ? d : String.valueOf(o); }
    private static String hexOf(byte[] b, int max){ if (b==null) return ""; StringBuilder s=new StringBuilder(); int n=Math.min(b.length, max); for (int i=0;i<n;i++) s.append(String.format("%02x", b[i])); if (b.length>max) s.append("\u2026"); return s.toString(); }

    private List<String> entryPre(Map<String,Object> m){
        List<String> l=new ArrayList<>();
        Object o=m.get("pre");
        if (o!=null){ String s=String.valueOf(o); if (s.length()>0 && !"null".equals(s)){ try { org.json.JSONArray a=new org.json.JSONArray(s); for (int i=0;i<a.length();i++){ String v=a.optString(i); if (v!=null && v.length()>0) l.add(v);} } catch (Throwable t){ l.add(s);} } }
        return l;
    }
    private String entryLoc(Map<String,Object> m){ Object o=m.get("loc"); return o==null ? entryText(m) : String.valueOf(o); }
    private List<String> cbPres(Map<String,Object> m){ List<String> l=entryPre(m); if (l.isEmpty() && WAKE_CMD!=null && WAKE_CMD.length()>0) l.add(WAKE_CMD); return l; }

    private void sendText(Object peer, int account, String msg) throws Exception {
        Class<?> sendCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_sendMessage");
        Object req = sendCls.newInstance();
        setFieldVal(req, "peer", peer);
        setFieldVal(req, "message", msg);
        setFieldVal(req, "random_id", random.nextLong());
        Object cm = staticInvoke(classEx("org.telegram.tgnet.ConnectionsManager"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
        Object delegate = Proxy.newProxyInstance(classEx("org.telegram.tgnet.RequestDelegate").getClassLoader(), new Class<?>[]{classEx("org.telegram.tgnet.RequestDelegate")},
            new InvocationHandler(){ @Override public Object invoke(Object p, Method mm, Object[] a){ return null; } });
        invoke(cm, "sendRequest", new Class<?>[]{classEx("org.telegram.tgnet.TLObject"), classEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
    }

    private List<Object[]> readKeyboard(Object mo){
        List<Object[]> out=new ArrayList<>();
        if (mo==null) return out;
        Object msg=mo;
        try { if (getFieldValSafe(mo,"reply_markup")==null){ Object m2=callSafe(mo,"getMessageObject"); if (m2==null) m2=getFieldValSafe(mo,"messageObject"); if (m2!=null) msg=m2; } } catch (Throwable ignored){}
        try {
            Object rows=getFieldValSafe(msg,"reply_markup");
            if (rows==null && mo!=msg){ rows=getFieldValSafe(mo,"reply_markup"); }
            List<?> rowList=normalizeRows(rows);
            if (rowList==null) return out;
            for (Object rowObj:rowList){
                Object bl=getFieldValSafe(rowObj,"buttons");
                if (!(bl instanceof List)) continue;
                for (Object b:(List<?>)bl){
                    String text=strOr(getFieldValSafe(b,"text"),"");
                    Object type=getFieldValSafe(b,"type");
                    byte[] data=null; long hash=0L;
                    if (type!=null){ Object dn=getFieldValSafe(type,"data"); if (dn instanceof byte[]) data=(byte[])dn; Object hn=getFieldValSafe(type,"hash"); if (hn instanceof Number) hash=((Number)hn).longValue(); }
                    out.add(new Object[]{ text, data, hash });
                }
            }
        } catch (Throwable ignored){}
        return out;
    }

    private boolean handleTapCapture(Object proto, Object mo){

        if (captureArmed && System.currentTimeMillis() - captureArmedAt > 120000L) {
            captureArmed = false;
            jlog("捕获模式超过 2 分钟未点按钮，已自动解除");
        }

        if (!captureArmed) return false;
        captureArmed=false;
        try {
            long did=0L; int mid=0;
            if (mo!=null){ Object d=callSafe(mo,"getDialogId"); if (d instanceof Number) did=((Number)d).longValue(); Object m2=callSafe(mo,"getId"); if (m2 instanceof Number) mid=((Number)m2).intValue(); }
            if (did<=0 && lastCapDid>0) did=lastCapDid;
            List<Object[]> btns=readKeyboard(mo);
            byte[] pdata = proto==null?null:buttonData(proto);
            if (btns.isEmpty() && pdata!=null) btns.add(new Object[]{ strOr(buttonText(proto),"回调按钮"), pdata, buttonHash(proto) });
            if (btns.isEmpty()) btns=readVisibleKeyboard();
            if (btns.isEmpty()) {
                String pn=proto==null?"null":proto.getClass().getName();
                int vis=readVisibleKeyboard().size();
                jlog("[捕获] 未读到按钮：proto="+pn+" protoData="+(pdata==null?"null":"len="+pdata.length)
                        +" mo="+(mo==null?"null":mo.getClass().getName())+" 可见键盘="+vis);
            }
            lastCapDid=did; lastCapMid=mid; lastCapBtns=btns;
            if (!btns.isEmpty()) updatePanelLiveButtons(did, mid, btns);
            final long fd=did; final int fm=mid; final List<Object[]> fb=btns; final Object fp=proto;
            mainHandler.post(new Runnable(){ @Override public void run(){ showCapturePicker(lastActivity, fd, fm, fb, fp); } });
            jlog("【捕获】采样 uid="+did+" msg="+mid+" 按钮数="+btns.size());
        } catch (Throwable t){ jlog("捕获异常: "+t); }
        return true;
    }

    private void showCapturePicker(Activity act, long did, int mid, List<Object[]> btns, Object proto){
        if (act==null){ toast("请在 TG 界面内完成捕获"); return; }
        LinearLayout box=new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(8),dp(16),dp(8));
        int cb=0; for (Object[] b:btns) if (b[1]!=null) cb++;
        TextView head=new TextView(act); head.setTextSize(13); head.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        head.setText("会话 uid="+did+"  msg="+mid+"  回调按钮 "+cb+"/"+btns.size()+"；点一个即绑定（可连点多个）");
        box.addView(head);
        for (final Object[] b:btns){
            final byte[] data=(byte[])b[1];
            final long hash=((Number)b[2]).longValue();
            final String text=strOr(b[0],"回调按钮");
            if (data==null){
                LinearLayout r2=new LinearLayout(act); r2.setOrientation(LinearLayout.HORIZONTAL);
                TextView tt=new TextView(act); tt.setTextSize(15); tt.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
                tt.setText("\u26a0\ufe0f "+text+"（文本/链接按钮，不能绑定回调）");
                r2.addView(tt,new LinearLayout.LayoutParams(0,-2,1f));
                r2.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ toast("这类按钮无法用回调模拟；文本键盘类请用「文本指令」目标（文本=按钮文字）"); } });
                box.addView(r2);
                View dv=new View(act); dv.setBackgroundColor(Theme.line(act)); box.addView(dv,new LinearLayout.LayoutParams(-1,1));
                continue;
            }
            final long fdid=did; final int fmid=mid;
            LinearLayout row=new LinearLayout(act); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(dp(4),dp(11),dp(4),dp(11));
            TextView t=new TextView(act); t.setTextSize(15); t.setTextColor(android.graphics.Color.parseColor(txtMain(act)));
            t.setText("🔘 "+text+"   ["+hexOf(data,10)+"]");
            row.addView(t,new LinearLayout.LayoutParams(0,-2,1f));
            TextView ar=new TextView(act); ar.setTextSize(18); ar.setText("\u203a"); row.addView(ar);
            row.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ bindCallback(fdid,text,data,hash,fmid); } });
            box.addView(row);
            View div=new View(act); div.setBackgroundColor(Theme.line(act)); box.addView(div,new LinearLayout.LayoutParams(-1,1));
        }
        if (cb==0 && proto!=null && isCallbackButton(proto)){
            final byte[] fdd=buttonData(proto); final long fh=buttonHash(proto); final String text=strOr(buttonText(proto),"回调按钮"); final long fdid=did; final int fmid=mid;
            if (fdd!=null){ Button one=mkBtnPrimary(act); one.setText(" 绑定刚点按钮: "+text); one.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ bindCallback(fdid,text,fdd,fh,fmid);} }); box.addView(one); }
        }
        if (cb==0 && proto==null) emptyView(box,"(没读到按钮，请在 bot 里点一下签到按钮再试)");
        showDialog(act,"捕获回调按钮", box, "完成");
    }

    private void bindCallback(long did, String text, byte[] data, long hash, int msgId){
        if (did<=0){ toast("绑定失败：会话ID无效"); return; }
        if (data==null||data.length==0){ toast("该按钮无回调数据"); return; }
        Map<String,Object> exist=findCbEntry(did,data);
        if (exist!=null){
            String lb=(text==null||text.trim().isEmpty())?"回调按钮":text.trim();
            exist.put("msgId", msgId); exist.put("hash", hash); exist.put("text", lb); exist.put("loc", lb);
            persistEntry(accountPrefix(), exist);
            toast("已存在该回调，已刷新消息/指纹（msg_id="+msgId+"）");
            logs("【绑定】已存在回调，刷新 msg_id="+msgId+" text="+lb);
            return;
        }
        String label=(text==null||text.trim().isEmpty())?"回调按钮":text.trim();
        Map<String,Object> m=new HashMap<>();
        m.put("id", nextEntryId(did, KIND_CB));
        m.put("did", did); m.put("text", label); m.put("kind", KIND_CB);
        m.put("data", data); m.put("hash", hash); m.put("msgId", msgId); m.put("loc", label);
        persistEntry(accountPrefix(), m); addTargetEntry(m);
        toast("✅ 已绑定回调: "+label+"（当前共 "+targetsSnapshot().size()+" 个目标）");
        logs("【绑定】uid="+did+" text="+label+" data="+hexOf(data,16)+" msg_id="+msgId);
    }

    private void startCapture(Activity act){
        if (act==null){ toast("请在 TG 界面使用 /jmb"); return; }
        captureArmed=true; captureArmedAt=System.currentTimeMillis();
        toast("捕获模式已开启：去 bot 会话里点一次它的按钮，我会列出该消息所有按钮供你绑定");
        jlog("【捕获】已武装，等待下一次按钮点击");
    }

    private void showAddChooser(final Activity act){
        LinearLayout menu=new LinearLayout(act); menu.setOrientation(LinearLayout.VERTICAL);
        TextView tip=new TextView(act); tip.setTextSize(13); tip.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        tip.setText("不用管类型：去 bot 会话点一下它的签到按钮，选「自动识别」即可。\n如果它要的是发指令，用「我有签到指令」。");
        tip.setPadding(dp(12),dp(8),dp(12),dp(8)); menu.addView(tip);
        menuItem(menu,"🤖","自动识别（推荐）","去 bot 会话点一下它的签到按钮，会自动记忆并每天跟进","cap_cb");
        menuItem(menu,"⌨️","我有签到指令","知道它要求的文本指令（bot ID + 指令）","add_text");
        showDialog(act,"添加签到目标", menu, "关闭");
    }

    private void showEntryActions(final Activity act, final Map<String,Object> m){
        final String id=entryId(m); final boolean cb=KIND_CB.equals(entryKind(m));
        LinearLayout b=new LinearLayout(act); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView hd=new TextView(act); hd.setTextSize(15); hd.setTextColor(android.graphics.Color.parseColor(txtMain(act)));
        hd.setText(targetTitle(entryDid(m))+"   "+(cb?"🔘回调":"⌨️指令")+"   "+entryText(m)); b.addView(hd);
        if (cb){
            Button t=mkBtn(act); t.setText("🧪 测试签到（先跑前置命令→点按钮→看返回）");
            t.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ testEntry(id); } });
            b.addView(t);
        }
        Button s=mkBtnPrimary(act); s.setText("🚀 立即签到");
        s.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ sendSign(m, currentAccount()); toast("已发起签到，结果见提示/日志"); } });
        b.addView(s);
        Button e=mkBtn(act); e.setText("✏️ 编辑（标签/前置命令/定位）");
        e.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ showEditEntry(act, m); } });
        b.addView(e);
        Button rb=mkBtn(act); rb.setText("🔁 重绑为回调（去点它的按钮）");
        rb.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toast("去该 bot 会话点一下要绑的签到按钮，会自动作为回调新增"); startCapture(act); } });
        b.addView(rb);
        Button sn=mkBtn(act); sn.setText("⏸ 暂停一周 / 恢复");
        sn.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toggleSnooze(m, currentAccount()); showEntryActions(act, m); } });
        b.addView(sn);
        Button d=mkBtnDanger(act); d.setText("🗑 删除");
        d.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ confirmDelete(act, m); } });
        b.addView(d);
        showDialog(act,"条目操作", b, "关闭");
    }

    private static String textToPreJson(String txt){
        try { org.json.JSONArray a=new org.json.JSONArray();
            String[] parts=txt.split("[\n,]");
            for (String s: parts){ String x=s.trim(); if (x.length()>0) a.put(x); }
            return a.length()==0 ? null : a.toString();
        } catch (Throwable t){ return null; }
    }
    private static String preToJsonToText(List<String> l){ StringBuilder sb=new StringBuilder(); for (int i=0;i<l.size();i++){ if (i>0) sb.append(", "); sb.append(l.get(i)); } return sb.toString(); }

    private void showEditEntry(final Activity act, final Map<String,Object> m){
        final boolean cb=KIND_CB.equals(entryKind(m));
        LinearLayout box=new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(8),dp(16),dp(8));
        final EditText name=adInput(act,"标签 / 指令",0); name.setText(entryText(m)); box.addView(name);
        final EditText pre=adInput(act,"前置命令序列（逗号或换行分隔，可空；发送后拉面板再点按钮）",0);
        if (cb) pre.setText(preToJsonToText(entryPre(m))); box.addView(pre);
        if (cb){
            TextView pt = new TextView(act); pt.setTextSize(12); pt.setTextColor(Theme.termMuted(act)); pt.setPadding(dp(2), dp(6), 0, 0);
            pt.setText("模板（点一下追加；可自行输入）：");
            box.addView(pt);
            android.widget.HorizontalScrollView hsc = new android.widget.HorizontalScrollView(act);
            LinearLayout chips = new LinearLayout(act); chips.setOrientation(LinearLayout.HORIZONTAL); chips.setPadding(0, dp(4), 0, 0);
            final String[] TPL = {"/start", "/menu", "/qd", "/checkin", "签到", "开始", "菜单"};
            for (final String tpl : TPL) {
                Button cbB = mkBtn(act); cbB.setText(tpl); cbB.setTextSize(12);
                cbB.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                    String cur = pre.getText()==null?"":pre.getText().toString().trim();
                    if (cur.length() > 0) cur += ",";
                    pre.setText(cur + tpl);
                } });
                chips.addView(cbB, new LinearLayout.LayoutParams(-2, -2));
            }
            Button clb = mkBtnDanger(act); clb.setText("清空"); clb.setTextSize(12);
            clb.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ pre.setText(""); } });
            chips.addView(clb, new LinearLayout.LayoutParams(-2, -2));
            hsc.addView(chips);
            box.addView(hsc);
        }
        final EditText loc=adInput(act,"按钮定位文案（重开面板按此找回按钮，默认=标签）",0);
        if (cb){ loc.setText(entryLoc(m)); box.addView(loc); }
        Button ok=mkBtnPrimary(act); ok.setText("保存");
        ok.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            try {
                m.put("text", name.getText().toString().trim());
                if (cb){
                    String pj=textToPreJson(pre.getText().toString());
                    if (pj!=null) m.put("pre", pj); else m.remove("pre");
                    String lc=loc.getText().toString().trim();
                    m.put("loc", lc.length()==0?m.get("text"):lc);
                }
                persistEntry(accountPrefix(), m);
                toast("已保存"); showList(act);
            } catch (Throwable t){ toast("保存失败: "+t); }
        }});
        box.addView(ok);
        showDialog(act,"编辑目标", box, "取消");
    }

    private void sendPreAndResign(final Map<String,Object> entry, final int account, final Object peer, final List<String> pres, final int idx){
        if (idx >= pres.size()){
            mainHandler.postDelayed(new Runnable(){ @Override public void run(){ sendSign(entry, account); } }, 1200L);
            return;
        }
        try { sendText(peer, account, pres.get(idx)); jlog("前置命令 [" + pres.get(idx) + "] 已发送，等待面板…"); }
        catch (Throwable t){ jlog("前置命令发送失败(忽略): " + t); }
        mainHandler.postDelayed(new Runnable(){ @Override public void run(){ sendPreAndResign(entry, account, peer, pres, idx + 1); } }, 1200L);
    }

    private Object resolveInputPeer(long did, int account){
        try {
            Object mc=getMessagesController(account); Object user=null;
            if (mc!=null){ try{ user=invoke(mc,"getUser",new Class<?>[]{Long.class},new Object[]{did}); }catch(Throwable ignored){} }
            if (user==null){ Object ms=getMessagesStorage(account); if (ms!=null){ try{ user=invoke(ms,"getUser",new Class<?>[]{long.class},new Object[]{did}); }catch(Throwable ignored){} } }
            if (user==null) return null;
            return staticInvoke(classEx("org.telegram.messenger.MessagesController"), "getInputPeer", new Class<?>[]{classEx("org.telegram.tgnet.TLObject")}, new Object[]{user});
        } catch (Throwable t){ return null; }
    }

    private void testEntry(String id){
        Map<String,Object> m=findEntryById(id);
        if (m==null){ toast("目标不存在"); return; }
        wakeFired.remove(id);
        jlog("【测试】手动验证 id="+id+" did="+entryDid(m)+" kind="+entryKind(m)+" 前置="+cbPres(m));
        sendSign(m, currentAccount());
    }

    private void testFire(final long did, final int mid, final String label, final byte[] data, final long hash){
        try {
            final int account=currentAccount();
            Object peer=resolveInputPeer(did, account);
            if (peer==null){ toast("取 InputPeer 失败，先在会话里点一下该 bot"); return; }
            Object req=classEx("org.telegram.tgnet.TLRPC$TL_messages_getBotCallbackAnswer").newInstance();
            setFieldVal(req,"peer",peer); setFieldVal(req,"data",data); setFieldVal(req,"msg_id",mid);
            final String fl=label; final long fdid=did;
            Object cm=staticInvoke(classEx("org.telegram.tgnet.ConnectionsManager"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
            Object delegate=newRequestDelegate(new InvocationHandler(){
                @Override public Object invoke(Object p, Method mm, Object[] a){
                    if ("run".equals(mm.getName()) && a!=null && a.length>=2){
                        final Object resp=a[0]; final Object err=a[1];
                        mainHandler.post(new Runnable(){ public void run(){
                            if (err!=null){ String et=""; try{ et=strOr(getFieldValSafe(err,"text"),""); }catch(Throwable ignored){} toast("🧪 "+fl+" 失败: "+et); jlog("【测试】uid="+fdid+" ["+fl+"] 失败 err="+et); }
                            else { String ans=""; try{ Object am=getFieldValSafe(resp,"message"); if(am==null) am=getFieldValSafe(resp,"alert"); ans=strOr(am,""); }catch(Throwable ignored){} toast("🧪 "+fl+" 成功"+(ans.length()>0?": "+ans:"")); jlog("【测试】uid="+fdid+" ["+fl+"] 成功 answer="+ans); }
                        }});
                    }
                    return null;
                }
            });
            invoke(cm,"sendRequest", new Class<?>[]{classEx("org.telegram.tgnet.TLObject"), classEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
            toast("🧪 已发送测试: "+label);
        } catch (Throwable t){ toast("测试异常: "+t); }
    }

    private void showDebugConsole(Activity act){
        if (act==null){ toast("请在 TG 界面使用 /jmb"); return; }
        List<Object[]> btns = readVisibleKeyboard();
        if (btns.isEmpty() && lastCapBtns!=null) btns = lastCapBtns;
        LinearLayout box=new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView head=new TextView(act); head.setTextSize(13); head.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        head.setText("回调调试台 · uid="+lastCapDid+" msg="+lastCapMid+" 按钮 "+btns.size()+" 个\n点任意按钮=实时发一次该回调并看返回；不放心先「重新采样」");
        box.addView(head);
        Button samp=mkBtnPrimary(act); samp.setText("🔘 重新采样（去点一次按钮）");
        samp.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ startCapture(act); } });
        box.addView(samp);
        int cb=0;
        for (final Object[] b:btns){
            final byte[] data=(byte[])b[1];
            if (data==null) continue; cb++;
            final long hash=((Number)b[2]).longValue();
            final String text=strOr(b[0],"回调按钮");
            final long fdid=lastCapDid; final int fmid=lastCapMid;
            LinearLayout row=new LinearLayout(act); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(dp(4),dp(11),dp(4),dp(11));
            TextView t=new TextView(act); t.setTextSize(15); t.setTextColor(android.graphics.Color.parseColor(txtMain(act)));
            t.setText("🧪 "+text+"  ["+hexOf(data,10)+"]");
            row.addView(t,new LinearLayout.LayoutParams(0,-2,1f));
            TextView ar=new TextView(act); ar.setTextSize(18); ar.setText("\u203a"); row.addView(ar);
            row.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ testFire(fdid,fmid,text,data,hash); } });
            box.addView(row);
            View div=new View(act); div.setBackgroundColor(Theme.line(act)); box.addView(div,new LinearLayout.LayoutParams(-1,1));
        }
        if (cb==0) emptyView(box,"(暂无可调试按钮：先在目标 bot 会话里点一次它的签到按钮)");
        showDialog(act,"回调调试台", box, "关闭");
    }

    private List<Object[]> readVisibleKeyboard(){
        List<Object[]> out=new ArrayList<>();
        try {
            Activity a=lastActivity; if (a==null) return out;
            if (!a.getClass().getName().contains("ChatActivity")) return out;
            int mid=0; Object mm=callSafe(a,"getLastDisplayedMessage"); if (mm instanceof Number) mid=((Number)mm).intValue();
            if (mid<=0) return out;
            Object mc=getMessagesController(currentAccount()); if (mc==null) return out;
            Object msg=null;
            try { msg=invoke(mc,"getKnownMessage",new Class<?>[]{int.class},new Object[]{mid}); } catch(Throwable t1){}
            if (msg!=null) out=readKeyboard(msg);
        } catch (Throwable ignored){}
        return out;
    }


    private void addTargetEntry(Map<String, Object> m) {
        targets.add(m);
        logs("已添加目标 " + entryDid(m) + " -> " + (KIND_CB.equals(entryKind(m)) ? "[回调] " : "") + entryText(m) + " (id=" + entryId(m) + ")");
    }

    private boolean targetContains(long did) {
        for (Map<String, Object> m : targetsSnapshot()) {
            if (entryDid(m) == did) return true;
        }
        return false;
    }


    private void loadTargets() {
        synchronized (TLOCK) { loadTargetsLocked(); }
    }

    private void loadTargetsLocked() {
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
                    long did = numLong(prefix + "did_" + id, 0L);
                    String kind = strOf(prefix + "kind_" + id);
                    if (did <= 0) {
                        try { did = Long.parseLong(id); } catch (Throwable t) { continue; } // 旧键 learned_<did>
                    }
                    if (kind == null) kind = KIND_TEXT;   // kind_ 在就如实回填（修复回调被强制变指令）
                    if (did <= 0) continue;
                    String text = String.valueOf(all.get(prefix + "learned_" + id));
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", id);
                    m.put("did", did);
                    m.put("text", text);
                    m.put("kind", kind);
                    if (KIND_CB.equals(kind)) {
                        String b64 = strOf(prefix + "data_" + id);
                        if (b64 != null) {
                            try { m.put("data", Base64.getDecoder().decode(b64)); } catch (Throwable ignored) {}
                        }
                        m.put("hash", numLong(prefix + "hash_" + id, 0L));
                        m.put("msgId", (int) numLong(prefix + "msg_id_" + id, 0L));
                        String pj = strOf(prefix + "pre_" + id);
                        if (pj != null) m.put("pre", pj);
                        String lj = strOf(prefix + "loc_" + id);
                        if (lj != null) m.put("loc", lj);
                    }
                    out.add(m);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            loge("读取目标列表失败: " + t);
        }
    }

    /** 学习文本指令目标（按钮 / 手动 / 网络层）。同 bot 不同指令 = 新增条目；相同指令 = 跳过。 */
    private void learnTarget(long dialogId, String text) {

        syncAccount();

        if (!LEARN_ENABLED) return;
        if (text == null || text.length() == 0) return;
        if (dialogId <= 0) {
            logd("忽略群聊学习: dialogId=" + dialogId);
            return;
        }
        if (findTextEntry(dialogId, String.valueOf(text)) != null) {
            logd("目标 " + dialogId + " 已加过相同指令，跳过");
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
        logs("【自动学习】新目标 " + dialogId + " -> " + text);
        toast("✅ 已添加新签到目标: " + text);
    }

    /** 学习回调按钮目标（inline button，v1.3.0 新增）。同 bot 相同 data 去重。 */
    private void learnCallback(long dialogId, String display, byte[] data, long hash, int msgId) {

        syncAccount();

        if (!LEARN_ENABLED) return;
        if (data == null || data.length == 0) return;
        if (dialogId <= 0) return;
        if (findCbEntry(dialogId, data) != null) {
            logd("目标 " + dialogId + " 已加过相同回调按钮，跳过");
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
        m.put("msgId", msgId);
        String prefix = accountPrefix();
        persistEntry(prefix, m);
        addTargetEntry(m);
        logs("【自动学习】新回调目标 " + dialogId + " -> [" + label + "] data=" + Base64.getEncoder().encodeToString(data) + " msg_id=" + msgId);
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
                logd("[候选] uid=" + did + " msg=" + t + "（不含签到关键词，不自动添加）");
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
            logd("[候选] uid=" + did + " msg=" + t + "（非bot，不自动添加）");
            return;
        }
        learnTarget(did, t);
        jlog("【网络层自动学习】新目标 " + did + " -> " + t);
    }

    private void markSigned(String prefix, String id) {
        prefs.edit().putString(prefix + "last_" + id, todayStr()).commit();
        updateStreak(prefix);
        noteSignedDay(prefix);
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
    /** TG 各版本 reply_markup 形态不一：List 直取 / 对象取 rows / 再取 markup */
    private List<?> normalizeRows(Object rm){
        try {
            if (rm instanceof List) return (List<?>) rm;
            Object r = getFieldValSafe(rm, "rows");
            if (r instanceof List) return (List<?>) r;
            Object m = getFieldValSafe(rm, "markup");
            if (m instanceof List) return (List<?>) m;
        } catch (Throwable ignored){}
        return null;
    }

    /** 从 reply_markup rows 解析按钮清单（与 readKeyboard 同字段逻辑，供面板缓存用） */
    private List<Object[]> parseKeyboardRows(Object rowsParam){
        List<Object[]> out=new ArrayList<>();
        try {
            List<?> rows = normalizeRows(rowsParam);
            if (rows==null) return out;
            for (Object rowObj:rows){
                Object bl=getFieldValSafe(rowObj,"buttons");
                if (!(bl instanceof List)) continue;
                for (Object b:(List<?>)bl){
                    String text=strOr(getFieldValSafe(b,"text"),"");
                    byte[] data=null; long hash=0L;
                    try {
                        Object type=getFieldValSafe(b,"type");
                        if (type==null) type=b;
                        Object dn=getFieldValSafe(type,"data");
                        if (dn instanceof byte[]) data=(byte[])dn;
                        Object hn=getFieldValSafe(type,"hash");
                        if (hn instanceof Number) hash=((Number)hn).longValue();
                        if (data==null||data.length==0){ byte[] zd=buttonData(type); if (zd!=null&&zd.length>0) data=zd; }
                    } catch (Throwable ignored){}
                    out.add(new Object[]{ text, data, hash });
                }
            }
        } catch (Throwable ignored){}
        return out;
    }

    /** 采集：bot 面板消息（reply_markup）到达 → 写缓存 + 事件驱动 */
    private void updatePanelLive(long did, int mid, Object replyMarkup){
        try {
            if (did<=0 || replyMarkup==null) return;
            List<Object[]> btns=parseKeyboardRows(replyMarkup);
            if (btns.isEmpty()) return;
            updatePanelLiveButtons(did, mid, btns);
        } catch (Throwable ignored){}
    }

    /** 采集：按钮清单（捕获采样/自动学习时）→ 写缓存 + 事件驱动 */
    private void updatePanelLiveButtons(long did, int mid, List<Object[]> btns){
        try {
            if (did<=0 || btns==null || btns.isEmpty()) return;
            PanelLive pl;
            synchronized (panelLive) {
                pl=panelLive.get(did);
                if (pl==null){ pl=new PanelLive(did); panelLive.put(did, pl); }
            }
            synchronized (pl) {
                pl.msgId=mid; pl.ts=System.currentTimeMillis();
                pl.buttons.clear();
                for (Object[] b:btns){
                    byte[] d=b!=null&&b.length>1?(byte[])b[1]:null;
                    if (d==null) continue;
                    pl.buttons.add(new CbButton(b.length>0?String.valueOf(b[0]):"", d,
                            b.length>2&&b[2] instanceof Number?((Number)b[2]).longValue():0L, mid));
                }
                if (pl.buttons.isEmpty()) return;
            }
            jlog("[面板] 更新 uid="+did+" msg="+mid+" 回调按钮="+pl.buttons.size());
            fireLiveSign(did);
        } catch (Throwable ignored){}
    }

    /** 匹配：面板当前按钮（data 精确 > 文本一致 > 最近点击）；6 小时内没见过面板视为过期 */
    private CbButton resolveLiveButton(long did, byte[] fixedData, String text){
        PanelLive pl;
        synchronized (panelLive){ pl=panelLive.get(did); }
        if (pl==null) return null;
        synchronized (pl) {
            if (System.currentTimeMillis()-pl.ts > 6L*3600*1000L) return null;
            if (fixedData!=null && fixedData.length>0){
                for (CbButton b:pl.buttons)
                    if (b.data!=null && Arrays.equals(b.data, fixedData))
                        return new CbButton(b.text, b.data, b.hash, pl.msgId);
            }
            if (text!=null && text.length()>0){
                for (CbButton b:pl.buttons)
                    if (text.equals(b.text))
                        return new CbButton(b.text, b.data, b.hash, pl.msgId);
            }
            if (pl.lastClicked!=null && fixedData!=null && pl.lastClicked.data!=null
                    && Arrays.equals(pl.lastClicked.data, fixedData))
                return new CbButton(pl.lastClicked.text, pl.lastClicked.data, pl.lastClicked.hash, pl.msgId);
        }
        return null;
    }

    /** 事件驱动：面板刚更新且该 bot 今日未签 → 立即补签（did 级 8 秒防抖） */
    private void fireLiveSign(final long did){
        try {
            synchronized (panelHitBusy){ if (panelHitBusy.contains(did)) return; panelHitBusy.add(did); }
            mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                synchronized (panelHitBusy){ panelHitBusy.remove(did); }
                try {
                    String prefix=accountPrefix();
                    List<Map<String, Object>> list=new ArrayList<>();
                    loadTargetsInto(prefix, list);
                    for (Map<String, Object> m:list){
                        if (entryDid(m)!=did || !KIND_CB.equals(entryKind(m))) continue;
                        String id=entryId(m);
                        if (todayStr().equals(prefs.getString(prefix+"last_"+id, ""))) continue;
                        logd("[面板事件] "+did+" 面板已更新且今日未签，立即补签");
                        sendSign(m, currentAccount());
                        break;
                    }
                } catch (Throwable ignored){}
            } }, 700L);
        } catch (Throwable ignored){}
    }

    private void sendSign(Map<String, Object> entry, int account) {
        pace();
        final long dialogId = entryDid(entry);
        final String id = entryId(entry);
        final String kind = entryKind(entry);
        final String fText = entryText(entry);
        if (isPendingFresh(id)) {
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
            logd("内存里没有该 bot，改查数据库 " + dialogId + "，尝试从数据库读取");
            try {
                Object ms = getMessagesStorage(account);
                if (ms != null) user = invoke(ms, "getUser", new Class<?>[]{long.class}, new Object[]{dialogId});
            } catch (Throwable e) {
                logd("数据库也没读到 " + dialogId + " : " + e);
            }
        }
        Object peer = null;
                if (user != null) {
                    try { peer = staticInvoke(classEx("org.telegram.messenger.MessagesController"), "getInputPeer", new Class<?>[]{classEx("org.telegram.tgnet.TLObject")}, new Object[]{user}); }
                    catch (Throwable t) { peer = null; }
                }
                if (peer == null) peer = peerFromDialogs(dialogId, account);
                if (peer == null) {
                    if (isChatOrChannel(dialogId, account)) { giveUpTarget(entry, account, "这个目标是群或频道，不是 bot"); return; }
                    countSoftFail(entry, account, "取不到这个 bot 的会话数据");
                    return;
                }
        final List<String> pres = cbPres(entry);
        if (KIND_CB.equals(kind) && !pres.isEmpty() && !wakeFired.contains(id)) {
            wakeFired.add(id);
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { wakeFired.remove(id); }
            }, 45000L);
            sendPreAndResign(entry, account, peer, pres, 0);
            return;
        }
        try {
            Object req;
            if (KIND_CB.equals(kind)) {
                Class<?> cbCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_getBotCallbackAnswer");
                // 1.4.5 Live Panel：优先当前面板匹配的按钮（发完前置命令重入时面板已刷新）
                CbButton live = resolveLiveButton(dialogId, entryData(entry), fText);
                byte[] useData = live != null ? live.data : entryData(entry);
                int useMid = live != null ? live.msgId : entryMsgId(entry);
                if (live != null) logd("[" + id + "] 面板命中按钮 \"" + live.text + "\" msg=" + live.msgId);
                req = cbCls.newInstance();
                setFieldVal(req, "peer", peer);
                if (useData != null) {
                    setFieldVal(req, "data", useData);
                    try { setFieldVal(req, "flags", 1); } catch (Throwable ignored) {}   // 12.x：data 是否序列化由 flags 位决定，不设则 DATA_INVALID
                }
                try { setFieldVal(req, "msg_id", useMid); } catch (Throwable ignored) {}
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
            final Map<String,Object> fEntry = entry;
            final int fAccount = account;
            final Object fPeer = peer;
            final List<String> fPres = pres;
            final long fDialogId = dialogId;
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
                                boolean panelStale = upper.contains("DATA_INVALID") || upper.contains("MESSAGE_ID_INVALID")
                                        || upper.contains("BUTTON") || upper.contains("MESSAGE_NOT_FOUND");
                                if (panelStale && !permanent) {
                                    boolean triedPres = wakeFired.contains(fId);
                                    if (!triedPres && fPres != null && !fPres.isEmpty()) {
                                        wakeFired.add(fId);
                                        mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                                            try { sendPreAndResign(fEntry, fAccount, fPeer, fPres, 0); } catch (Throwable ignored) {}
                                        } }, 800L);
                                        logw("回调按钮过期(" + errText + ")：拉新面板后重试（" + fId + "）");
                                    } else {
                                        prefs.edit().putString(fPrefix + "last_" + fId, todayStr())
                                             .putInt(fPrefix + "retry_" + fId, RETRY_LIMIT)
                                             .putString(fPrefix + "retry_day_" + fId, todayStr()).apply();
                                        loge("回调面板已变化(" + errText + ")：去 bot 会话再点一次签到按钮即自动修复（" + fId + "）");
                                    }
                                    noteResult(false);
                                    return null;
                                }
                                if (permanent) {
                                    prefs.edit().putString(fPrefix + "last_" + fId, todayStr())
                                         .putInt(fPrefix + "retry_" + fId, RETRY_LIMIT)
                                         .putString(fPrefix + "retry_day_" + fId, todayStr()).apply();
                                    loge("签到永久失败 " + dialogId + " : " + errText + "（今日放弃）");
                                    noteResult(false);
                                } else if (upper.contains("BOT_RESPONSE_TIMEOUT")) {
                                    int tOut = prefs.getInt(fPrefix + "retry_" + fId, 0);
                                    if (tOut >= 2) {
                                        prefs.edit().putString(fPrefix + "last_" + fId, todayStr())
                                             .putInt(fPrefix + "retry_" + fId, 99)
                                             .putString(fPrefix + "retry_day_" + fId, todayStr()).apply();
                                        loge("bot 长时间未响应(" + errText + ")：今日不再自动试（" + fId + "）");
                                    } else {
                                        prefs.edit().putInt(fPrefix + "retry_" + fId, tOut + 1)
                                             .remove(fPrefix + "last_" + fId)
                                             .putLong(fPrefix + "retry_at_" + fId, System.currentTimeMillis() + 15L * 60 * 1000)
                                             .putString(fPrefix + "retry_day_" + fId, todayStr()).apply();
                                        logw("bot 未响应(" + errText + ")：15 分钟后重试（" + fId + "）");
                                    }
                                    noteResult(false);
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
                                         .putLong(fPrefix + "retry_at_" + fId, System.currentTimeMillis() + waitMs)
                                         .putString(fPrefix + "retry_day_" + fId, todayStr()).apply();
                                    logw("签到遇限流 " + dialogId + " : " + errText + "，等待 " + waitSec + " 秒后自动重试");
                                } else {
                                    int oldRetry = prefs.getInt(fPrefix + "retry_" + fId, 0);
                                    prefs.edit().putInt(fPrefix + "retry_" + fId, oldRetry + 1)
                                         .remove(fPrefix + "last_" + fId)
                                         .putLong(fPrefix + "retry_at_" + fId, System.currentTimeMillis() + backoffDelay(oldRetry))
                                         .putString(fPrefix + "retry_day_" + fId, todayStr())
                                         .commit();
                                    loge("签到失败 " + dialogId + " : " + errText + "（第" + (oldRetry + 1) + "次，退避重试）");
                                    noteResult(false);
                                }
                            } else {
                                prefs.edit()
                                    .putString(fPrefix + "last_" + fId, todayStr())
                                    .putInt(fPrefix + "retry_" + fId, 0)
                                    .remove(fPrefix + "retry_at_" + fId)
                                    .remove(fPrefix + "retry_day_" + fId)
                                    .commit();
                                String ans = "";
                                try { Object am = getFieldValSafe(response, "message"); if (am == null) am = getFieldValSafe(response, "alert"); if (am != null) ans = String.valueOf(am); } catch (Throwable ignored) {}
                                logs("签到完成 " + dialogId + " " + (KIND_CB.equals(fKind) ? "[回调] " : "text=") + fText + (ans.length() > 0 ? " 机器人返回: " + ans : ""));
                                noteResult(true);
                            }
                        } catch (Throwable ignored) {}
                    }
                    return null;
                }
            });
            invoke(cm, "sendRequest", new Class<?>[]{classEx("org.telegram.tgnet.TLObject"), classEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
            logd("已发出请求 " + dialogId + " " + (KIND_CB.equals(kind) ? "[回调] " : "text=") + fText + " (id=" + id + ")");
        } catch (Throwable t) {
            pendingSigns.remove(id);
            loge("发送异常 " + dialogId + " : " + t);
        }
    }

    // ---------------- 补签 ----------------
    void trySignAll(String reason, boolean force) {
        trySignAllFor(reason, force, currentAccount());
    }

    /** 对指定账号执行一轮补签。从 prefs 直接读该账号条目，支持全账号签到。 */

    void trySignAllFor(String reason, boolean force, int account) {
        lastRound = null;
        long now = System.currentTimeMillis();
        if (!force && !"进入窗口".equals(reason) && now - lastTryTime < THROTTLE_MS) {
            logd("[" + reason + "] 节流内跳过");
            return;
        }
        if (account == currentAccount()) syncAccount();
        if (!force && !inWindow()) {
            logd("[" + reason + "] 签到窗口外（当前 " + nowHM() + "，窗口 " + WINDOW + "），跳过");
            return;
        }
        if (!hasNetwork()) {
            logw("[" + reason + "] 无网络，跳过，网络恢复后自动补");
            return;
        }
        lastTryTime = now;
        String prefix = accountPrefix(account);
        String today = todayStr();
        boolean promptToday = reason != null && (reason.startsWith("启动") || "网络恢复".equals(reason));
        int signed = 0, busy = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        loadTargetsInto(prefix, list);
        int total = list.size();
        for (Map<String, Object> m : list) {
            long dialogId = entryDid(m);
            String id = entryId(m);
            try {
                if (today.equals(prefs.getString(prefix + "last_" + id, ""))) { signed++; continue; }
                String retryDay = prefs.getString(prefix + "retry_day_" + id, "");
                int retries = prefs.getInt(prefix + "retry_" + id, 0);
                if (retries > 0 && !today.equals(retryDay)) {
                    prefs.edit().putInt(prefix + "retry_" + id, 0)
                         .remove(prefix + "retry_at_" + id)
                         .remove(prefix + "retry_day_" + id).apply();
                    retries = 0;
                    jlog("[" + reason + "] " + dialogId + " 进入新的一天，重试计数已重置");
                }
                if (retries >= RETRY_LIMIT) {
                    logw("[" + reason + "] " + dialogId + " 今日已重试 " + retries + " 次，明天再试");
                    busy++;
                    continue;
                }
                long retryAt = prefs.getLong(prefix + "retry_at_" + id, 0);
                if (now < retryAt) {
                    logw("[" + reason + "] " + dialogId + " 退避中(剩 " + (retryAt - now) / 60000L + " 分钟)，跳过");
                    busy++;
                    continue;
                }
                if (isPendingFresh(id)) {
                    logd("[" + reason + "] " + dialogId + " 正在发送中，跳过");
                    busy++;
                    continue;
                }
                if (!force && isSnoozed(prefix, id)) {
                    logd("[" + reason + "] " + dialogId + " 暂停中(至 " + prefs.getString(prefix + "snooze_" + id, "") + ")，跳过");
                    continue;
                }
                if (!force && dailyUsed(account) >= DAILY_CAP) {
                    logw("[" + reason + "] " + accountLabel(account) + " 今日动作已达上限 " + DAILY_CAP + "，本轮停止");
                    break;
                }
                logd("[" + reason + "] 尝试签到 " + dialogId + " " + (KIND_CB.equals(entryKind(m)) ? "[回调] " : "text=") + entryText(m) + " (重试 " + retries + "/" + RETRY_LIMIT + ")");
                sendSign(m, account);
            } catch (Throwable t) {
                jlog("trySignAll 异常 " + dialogId + " : " + t);
            }
        }
        String sm = total == 0 ? accountLabel(account) + "：还没有签到目标（切到该账号，去 bot 会话点一下按钮或发一次指令）"
                : accountLabel(account) + "：目标 " + total + " · 已签 " + signed + " · 待重试 " + busy + " · 本轮发出 " + (total - signed - busy);
        lastRound = sm;
        if (total > 0) jlog("[" + reason + "] " + sm);
        if (promptToday && total > 0 && signed == total) {
            String pd = "";
            try { pd = prefs.getString("jmb_prompt_day", ""); } catch (Throwable ignored) {}
            if (!today.equals(pd)) {
                try { prefs.edit().putString("jmb_prompt_day", today).apply(); } catch (Throwable ignored) {}
                jlog("[提示] 今天 " + total + " 个目标都已签完");
                toast("今天已经签到过了");
            }
        }
    }


    /** v1.3.0：一键签全部账号（每个账号独立目标集，各自发各自的） */
    public void signAllAccounts() {
            int count = activatedAccounts();
            jlog("=== 全账号签到开始，共 " + count + " 个账号 ===");
            StringBuilder rep = new StringBuilder();
            for (int i = 0; i < count; i++) {
                try {
                    trySignAllFor("全账号(" + (i + 1) + "/" + count + ")", true, i);
                    String one = lastRound;
                    if (one == null) one = accountLabel(i) + "：本轮跳过（60 秒内刚跑过，或没网）";
                    if (rep.length() > 0) rep.append('\n');
                    rep.append(one);
                } catch (Throwable t) {
                    loge("账号 " + (i + 1) + " 签到异常: " + t);
                }
            }
            jlog("=== 全账号签到结束 ===");
            if (rep.length() > 0) toastOnce("allacc|" + todayStr() + "|" + rep.length(), "全账号签到\n" + rep);
        }

    public void enqueueTry(String reason) {
        long delay = 0L;
        if (inWindow() && windowRange() != null) {
            delay = (long) (Math.random() * 3L * 60L * 1000L);
        }
        final String r = reason;
        mainHandler.postDelayed(() -> { try { trySignAll(r, false); } catch (Throwable t) { jlog("[" + r + "] 异常: " + t); } }, delay);
    }

    /** 兜底解析 peer：缓存与数据库都拿不到 user 时，从会话列表里找这个 did 直接用 */
        private Object peerFromDialogs(long did, int account) {
            try {
                Object mc = getMessagesController(account);
                if (mc == null) return null;
                Object dialogs = null;
                try { dialogs = call(mc, "getDialogs", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {}
                if (dialogs == null) { try { dialogs = getFieldVal(mc, "dialogs"); } catch (Throwable ignored) {} }
                if (!(dialogs instanceof List)) return null;
                for (Object d : (List<?>) dialogs) {
                    if (d == null) continue;
                    long id = -1L;
                    try { Object o = call(d, "getDialogId", new Class<?>[0], new Object[0]); if (o instanceof Number) id = ((Number) o).longValue(); } catch (Throwable ignored) {}
                    if (id != did) {
                        try { Object o = getFieldVal(d, "dialogId"); if (o instanceof Number) id = ((Number) o).longValue(); } catch (Throwable ignored) {}
                        if (id != did) continue;
                    }
                    Object cand = null;
                    try { cand = call(d, "getInputPeer", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {}
                    if (cand == null) { try { cand = call(d, "getPeer", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
                    if (cand == null) { try { cand = getFieldVal(d, "inputPeer"); } catch (Throwable ignored) {} }
                    if (cand == null) continue;
                    String cn = cand.getClass().getName();
                    if (cn.contains("InputChannel") || cn.contains("InputChat")) return null;
                    if (cn.contains("InputPeer")) return cand;
                    try {
                        Object ip = staticInvoke(classEx("org.telegram.messenger.MessagesController"), "getInputPeer",
                                new Class<?>[]{classEx("org.telegram.tgnet.TLObject")}, new Object[]{cand});
                        if (ip != null && ip.getClass().getName().contains("InputPeer")) return ip;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private boolean isChatOrChannel(long did, int account) {
            try {
                Object mc = getMessagesController(account);
                if (mc == null) return false;
                Object chat = null;
                try { chat = invoke(mc, "getChat", new Class<?>[]{Long.class}, new Object[]{did}); } catch (Throwable ignored) {}
                if (chat == null) { try { chat = invoke(mc, "getChannel", new Class<?>[]{Long.class}, new Object[]{did}); } catch (Throwable ignored) {} }
                return chat != null;
            } catch (Throwable t) { return false; }
        }

        /** 没真正发出去的失败也要计入退避，否则会每 60 秒空跑一次 */
        private void countSoftFail(Map<String, Object> entry, int account, String why) {
            String prefix = accountPrefix(account);
            String id = entryId(entry);
            try {
                int cur = prefs.getInt(prefix + "retry_" + id, 0);
                if (cur > 0 && !todayStr().equals(prefs.getString(prefix + "retry_day_" + id, ""))) cur = 0;
                long wait = backoffDelay(cur);
                prefs.edit().putInt(prefix + "retry_" + id, cur + 1)
                        .putString(prefix + "retry_day_" + id, todayStr())
                        .putLong(prefix + "retry_at_" + id, System.currentTimeMillis() + wait)
                        .apply();
                logw(why + "：" + targetTitle(entryDid(entry)) + " · " + accountLabel(account)
                        + " · 第 " + (cur + 1) + "/" + RETRY_LIMIT + " 次，" + (wait / 60000L) + " 分钟后再试");
            } catch (Throwable t) { logd("记录失败状态异常: " + t); }
        }

        /** 明显签不成的目标（群/频道、被删的 bot）当天放弃，避免无意义重跑 */
        private void giveUpTarget(Map<String, Object> entry, int account, String why) {
            String prefix = accountPrefix(account);
            String id = entryId(entry);
            try {
                prefs.edit().putString(prefix + "last_" + id, todayStr())
                        .putInt(prefix + "retry_" + id, RETRY_LIMIT)
                        .putString(prefix + "retry_day_" + id, todayStr())
                        .apply();
            } catch (Throwable ignored) {}
            String who = targetTitle(entryDid(entry));
            logw(why + "：" + who + " · " + accountLabel(account) + "，今天不再尝试；在 /jmb → 📋 目标列表 里可以删掉它");
            toastOnce("skip|" + account + "|" + id, "⚠ " + who + " 签不了：" + why);
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

    private View menuTile(Activity act, String emoji, String title, String sub, String action) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp(8), dp(10), dp(8), dp(10));
        v.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x22)));
        v.setTag(action);
        v.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View vv){ runAction(vv.getContext(), String.valueOf(vv.getTag())); } });
        v.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override public boolean onTouch(View vv, android.view.MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) { vv.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start(); }
                else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) { vv.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); }
                return false;
            }
        });
        TextView em = new TextView(act); em.setText(emoji); em.setTextSize(20); em.setGravity(android.view.Gravity.CENTER);
        v.addView(em);
        TextView t = new TextView(act); t.setText(title); t.setTextSize(12);
        t.setTextColor(Theme.termTxt(act)); t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        t.setGravity(android.view.Gravity.CENTER); t.setPadding(0, dp(3), 0, 0);
        v.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(act); s.setText(sub); s.setTextSize(9);
            s.setTextColor(Theme.termMuted(act)); s.setTypeface(android.graphics.Typeface.MONOSPACE);
            s.setGravity(android.view.Gravity.CENTER); s.setPadding(0, dp(2), 0, 0);
            v.addView(s);
        }
        return v;
    }

    private void addTile(android.widget.GridLayout grid, Activity act, String emoji, String title, String sub, String action) {
        View v = menuTile(act, emoji, title, sub, action);
        android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        v.setLayoutParams(lp);
        if (!fastMainOpen) {
            final int delay = grid.getChildCount() * 40;
            v.setAlpha(0f);
            v.setTranslationY(Theme.dp(act, 8));
            v.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(android.view.View vv) {
                    vv.postDelayed(new Runnable() { @Override public void run() { vv.animate().alpha(1f).translationY(0f).setDuration(300).start(); } }, delay);
                }
                @Override public void onViewDetachedFromWindow(android.view.View vv) { vv.animate().cancel(); vv.setAlpha(0f); vv.setTranslationY(Theme.dp(act, 8)); }
            });
        }
        grid.addView(v);
    }

    private View menuItem(LinearLayout parent, String emoji, String title, String subtitle, String action) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(termBorder(c, Theme.termCard(c), Theme.withAlpha(Theme.termCyan(c), 0x22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(Theme.dp(c,3), Theme.dp(c,4), Theme.dp(c,3), Theme.dp(c,4));
        row.setLayoutParams(lp);
        row.setPadding(Theme.dp(c,12), Theme.dp(c,12), Theme.dp(c,12), Theme.dp(c,12));
        row.setTag(action);
        row.setOnClickListener(v -> runAction(v.getContext(), String.valueOf(v.getTag())));
        TextView em = new TextView(c);
        em.setText(emoji); em.setTextSize(18); em.setGravity(Gravity.CENTER);
        em.setBackground(termBorder(c, Theme.withAlpha(Theme.termCyan(c), 0x16), Theme.withAlpha(Theme.termCyan(c), 0x33)));
        row.addView(em, new LinearLayout.LayoutParams(Theme.dp(c,40), Theme.dp(c,40)));
        row.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,12), 1));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(c); t1.setTextSize(15); t1.setTextColor(Theme.termTxt(c)); t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); t1.setText(title); col.addView(t1);
        TextView t2 = new TextView(c); t2.setTextSize(11); t2.setTextColor(Theme.termMuted(c)); t2.setTypeface(android.graphics.Typeface.MONOSPACE);
        if (subtitle != null && subtitle.length() > 0) t2.setText(subtitle); else t2.setVisibility(View.GONE);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ar = new TextView(c); ar.setTextSize(18); ar.setText("\u203a"); ar.setTextColor(Theme.termCyan(c)); row.addView(ar);
        parent.addView(row);
        return row;
    }

    private TextView typeChip(Context c, boolean cb) {
        TextView chip = new TextView(c);
        chip.setTextSize(11);
        chip.setText(cb ? "🔸 回调" : "\u2328 指令");
        chip.setPadding(Theme.dp(c,8), Theme.dp(c,2), Theme.dp(c,8), Theme.dp(c,2));
        chip.setTextColor(cb ? Theme.termCyan(c) : Theme.termMuted(c));
        chip.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        chip.setBackground(termBorder(c, cb ? Theme.withAlpha(Theme.termCyan(c), 0x12) : Theme.withAlpha(Theme.termMuted(c), 0x0D), cb ? Theme.withAlpha(Theme.termCyan(c), 0x59) : Theme.withAlpha(Theme.termMuted(c), 0x33)));
        return chip;
    }

    private View targetRow(LinearLayout parent, Map<String, Object> entry, String status, String action) {
        Context c = parent.getContext();
        long did = entryDid(entry);
        String id = entryId(entry);
        boolean cb = KIND_CB.equals(entryKind(entry));
        String text = entryText(entry);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(termBorder(c, Theme.termCard(c), Theme.withAlpha(Theme.termCyan(c), 0x22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(Theme.dp(c,3), Theme.dp(c,4), Theme.dp(c,3), Theme.dp(c,4));
        row.setLayoutParams(lp);
        row.setPadding(Theme.dp(c,14), Theme.dp(c,11), Theme.dp(c,12), Theme.dp(c,11));
        row.setTag(action + "|" + id);
        if (action != null) {
            row.setOnClickListener(v -> {
                String tag = String.valueOf(v.getTag());
                String[] parts = tag.split("\\|");
                runTargetAction(v.getContext(), parts[0], parts.length > 1 ? parts[1] : "");
            });
        }
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tl = new LinearLayout(c); tl.setOrientation(LinearLayout.HORIZONTAL); tl.setGravity(Gravity.CENTER_VERTICAL);
        TextView t1 = new TextView(c); t1.setTextSize(15); t1.setTextColor(Theme.termTxt(c)); t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); t1.setText(targetTitle(did));
        tl.addView(t1, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tl.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,8), 1));
        tl.addView(typeChip(c, cb));
        col.addView(tl);
        TextView t2 = new TextView(c); t2.setTextSize(11); t2.setTextColor(Theme.termMuted(c)); t2.setTypeface(android.graphics.Typeface.MONOSPACE);
        String lastT = prefs.getString(accountPrefix() + "last_" + id, "");
        StringBuilder sb = new StringBuilder(status);
        sb.append("   ").append(cb ? "🔸" : "\u2328").append(" ").append(text);
        if (lastT.length() > 0) sb.append("   ·  上次 ").append(lastT);
        t2.setText(sb.toString());
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ar = new TextView(c); ar.setTextSize(18); ar.setText("\u203a"); ar.setTextColor(Theme.termCyan(c)); row.addView(ar);
        parent.addView(row);
        return row;
    }

    private static long lastMainOpen = 0L;
    private boolean fastMainOpen = false;
    private volatile boolean inSendReq = false;
    private volatile long bootReadyAt = 0L;

    private boolean notReadyYet() {
        return System.currentTimeMillis() < bootReadyAt;
    }

    private void showMainMenu(Activity act) {

        long now0 = System.currentTimeMillis();
        fastMainOpen = now0 - lastMainOpen < 15000L;
        lastMainOpen = now0;

        syncAccount();

        if (act == null) { toast("请在 Telegram 界面使用 /jmb"); return; }
        String today = todayStr();
        int signed = 0;
        for (Map<String, Object> m : targetsSnapshot()) {
            if (today.equals(prefs.getString(accountPrefix() + "last_" + entryId(m), ""))) signed++;
        }
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Theme.dp(act,10), Theme.dp(act,6), Theme.dp(act,10), Theme.dp(act,6));
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackground(Theme.header(act));
        head.setPadding(Theme.dp(act,18), Theme.dp(act,16), Theme.dp(act,18), Theme.dp(act,16));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.setMargins(0, 0, 0, Theme.dp(act,8)); head.setLayoutParams(hlp);
        head.setBackground(termBorder(act, Theme.termCardDeep(act), Theme.withAlpha(Theme.termCyan(act), 0x4D)));
        // 标题行：左侧标题 + 右侧 [START]（仅此按钮跳 GitHub，其余区域不触发）
        LinearLayout titleRow = new LinearLayout(act); titleRow.setOrientation(LinearLayout.HORIZONTAL); titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout floatRow = new LinearLayout(act); floatRow.setOrientation(LinearLayout.HORIZONTAL);
        String wtitle = Art.bold("TGAutoSign");
        final java.util.Random rnd = new java.util.Random();
        final float dens = act.getResources().getDisplayMetrics().density;
        final android.os.Handler th = new android.os.Handler(act.getMainLooper());
        for (int wi = 0; wi < wtitle.length(); ) {
            int cp = wtitle.codePointAt(wi);
            final String chs = new String(Character.toChars(cp));
            final int idx = wi;
            wi += Character.charCount(cp);
            final TextView chv = new TextView(act);
            chv.setTextSize(23); chv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            final int baseCol = Theme.termCyan(act);
            final int flashCol = Theme.dark(act) ? 0xFFFFFFFF : 0xFF001820;
            chv.setTextColor(baseCol); chv.setText(chs);
            floatRow.addView(chv);
            final Runnable tw = new Runnable() {
                @Override public void run() {
                    try {
                        if (!chv.isShown()) { th.removeCallbacks(this); return; }
                        float dx = (float) (rnd.nextInt(7) - 3) * dens;
                        float dy = (float) (rnd.nextInt(7) - 3) * dens;
                        chv.setTranslationX(dx);
                        chv.setTranslationY(dy);
                        chv.setTextColor(flashCol);
                        chv.postDelayed(new Runnable() {
                            @Override public void run() {
                                chv.setTranslationX(0f);
                                chv.setTranslationY(0f);
                                chv.setTextColor(baseCol);
                            }
                        }, 90L);
                    } catch (Throwable ignored) {}
                    th.postDelayed(this, 500L + rnd.nextInt(2000));
                }
            };
            chv.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(android.view.View vv) { th.postDelayed(tw, (long) (idx * 80L)); }
                @Override public void onViewDetachedFromWindow(android.view.View vv) { th.removeCallbacks(tw); }
            });
        }
        titleRow.addView(floatRow, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView start = new TextView(act); start.setText("[START]"); start.setTextSize(12); start.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        start.setTextColor(Theme.termGreen(act)); start.setClickable(true);
        start.setPadding(dp(14), dp(6), dp(14), dp(6));
        start.setBackground(termBorder(act, Theme.withAlpha(Theme.termGreen(act), 0x14), Theme.withAlpha(Theme.termGreen(act), 0x66)));
        start.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ openGithub(); } });
        titleRow.addView(start, new LinearLayout.LayoutParams(-2, -2));
        final View sweep = new View(act);
        sweep.setBackground(termBorder(act, Theme.withAlpha(Theme.termCyan(act), 0x40), 0));
        android.widget.LinearLayout.LayoutParams swp = new android.widget.LinearLayout.LayoutParams(Theme.dp(act, 160), Theme.dp(act, 2));
        swp.topMargin = Theme.dp(act, 6);
        sweep.setLayoutParams(swp);
        head.addView(sweep, 0);
        sweep.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View vv) {
                if (fastMainOpen) { sweep.setVisibility(View.GONE); return; }
                vv.post(new Runnable() { @Override public void run() {
                    int w = head.getWidth();
                    sweep.setVisibility(View.VISIBLE);
                    sweep.setTranslationX(-(float) w);
                    sweep.animate().translationX((float) w).setDuration(600).setStartDelay(80).withEndAction(new Runnable() {
                        @Override public void run() { sweep.setVisibility(View.GONE); }
                    }).start();
                } });
            }
            @Override public void onViewDetachedFromWindow(android.view.View vv) { sweep.animate().cancel(); sweep.setVisibility(View.VISIBLE); }
        });
        head.addView(titleRow);
        final TextView sv = new TextView(act); sv.setTextSize(12); sv.setTextColor(Theme.termMuted(act));
        sv.setTypeface(android.graphics.Typeface.MONOSPACE);
        String sign = (SIGN == null || SIGN.isEmpty()) ? "wlmosv" : SIGN;
        final String fullCmd = "$ tgas --v " + UpdateChecker.VERSION_NAME + "  ·  (c) " + sign + " 出品";
        final TextView cur = new TextView(act); cur.setTextSize(12); cur.setTextColor(Theme.termCyan(act));
        cur.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); cur.setText("▊");
        LinearLayout svRow = new LinearLayout(act); svRow.setOrientation(LinearLayout.HORIZONTAL); svRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        svRow.addView(sv);
        svRow.addView(cur);
        head.addView(svRow);
        final Runnable blink = new Runnable() {
            @Override public void run() {
                if (!cur.isShown()) { th.removeCallbacks(this); return; }
                cur.setVisibility(cur.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
                th.postDelayed(this, 500L);
            }
        };
        final Runnable[] typer = new Runnable[1];
        typer[0] = new Runnable() {
            int ti = 0;
            @Override public void run() {
                if (!sv.isShown()) { th.removeCallbacks(this); return; }
                if (ti <= fullCmd.length()) {
                    sv.setText(fullCmd.substring(0, ti));
                    ti++;
                    th.postDelayed(this, 80L);
                } else {
                    th.removeCallbacks(this);
                    th.postDelayed(blink, 300L);
                }
            }
        };
        svRow.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View vv) {
                if (fastMainOpen) { sv.setText(fullCmd); cur.setVisibility(View.VISIBLE); }
                else { th.postDelayed(typer[0], 200L); }
            }
            @Override public void onViewDetachedFromWindow(android.view.View vv) { th.removeCallbacks(typer[0]); th.removeCallbacks(blink); }
        });
        int others = 0; try { others = countOtherAccounts(); } catch (Throwable ignored) {}
        LinearLayout statCard = new LinearLayout(act);
        statCard.setOrientation(LinearLayout.VERTICAL);
        statCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        statCard.setBackground(termBorder(act, Theme.termCardInput(act), Theme.withAlpha(Theme.termCyan(act), 0x33)));
        android.widget.LinearLayout.LayoutParams lpCard = new android.widget.LinearLayout.LayoutParams(-1, -2);
        lpCard.topMargin = dp(12);
        statCard.setLayoutParams(lpCard);
        TextView st1 = new TextView(act); st1.setTextSize(14); st1.setTextColor(Theme.termCyan(act));
        st1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        st1.setText("\u25B8 " + accountLabel(currentAccount()) + "  ·  targets " + targets.size() + "  ·  signed " + signed + "/" + targets.size());
        statCard.addView(st1);
        int streakN = streakOf(accountPrefix());
        TextView stS = new TextView(act); stS.setTextSize(12);
        stS.setTextColor(streakN > 0 ? Theme.termGreen(act) : Theme.termMuted(act));
        stS.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        stS.setPadding(0, dp(6), 0, 0);
        stS.setText(streakN > 0 ? "连续签到 " + streakN + " 天 · 最近 14 天" : "还没连续签到，今天去签一个");
        statCard.addView(stS);
        LinearLayout cal = streakCalendar(act);
        if (cal != null) statCard.addView(cal);
        LinearLayout chips1 = new LinearLayout(act); chips1.setOrientation(LinearLayout.HORIZONTAL); chips1.setPadding(0, dp(8), 0, 0);
        chips1.addView(badge(act, "按钮学习", AUTO_LEARN));
        chips1.addView(badge(act, "网络学习", AUTO_LEARN_NET));
        statCard.addView(chips1);
        LinearLayout chips2 = new LinearLayout(act); chips2.setOrientation(LinearLayout.HORIZONTAL); chips2.setPadding(0, dp(6), 0, 0);
        chips2.addView(badge(act, "过滤", AUTO_LEARN_FILTER));
        chips2.addView(badge(act, "唤醒", WAKE_CMD != null && !WAKE_CMD.isEmpty()));
        statCard.addView(chips2);
        String plc = perAccountLine();
        if (plc != null && plc.length() > 0) {
            TextView st3 = new TextView(act); st3.setTextSize(11); st3.setTextColor(Theme.termMuted(act)); st3.setTypeface(android.graphics.Typeface.MONOSPACE); st3.setPadding(0, dp(8), 0, 0); st3.setText(plc); statCard.addView(st3);
        }
        if (!fastMainOpen) {
            statCard.setAlpha(0f); statCard.setTranslationY(Theme.dp(act, 8));
            statCard.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(android.view.View vv) { vv.postDelayed(new Runnable() { @Override public void run() { vv.animate().alpha(1f).translationY(0f).setDuration(350).start(); } }, 420L); }
                @Override public void onViewDetachedFromWindow(android.view.View vv) { vv.animate().cancel(); vv.setAlpha(0f); vv.setTranslationY(Theme.dp(act, 8)); }
            });
        }
        head.addView(statCard);
        // 终端输出卡：最近 4 行运行日志（实时刷新：面板开着每 4 秒自动更新）
        LinearLayout term = new LinearLayout(act);
        term.setOrientation(LinearLayout.VERTICAL);
        term.setPadding(dp(12), dp(10), dp(12), dp(10));
        term.setBackground(termBorder(act, Theme.termCardDeep(act), Theme.withAlpha(Theme.termGreen(act), 0x59)));
        android.widget.LinearLayout.LayoutParams tl2 = new android.widget.LinearLayout.LayoutParams(-1, -2);
        tl2.topMargin = dp(8); term.setLayoutParams(tl2);
        TextView tt = new TextView(act); tt.setTextSize(11); tt.setTypeface(android.graphics.Typeface.MONOSPACE);
        tt.setTextColor(Theme.termGreen(act));
        tt.setText("$ tail -f ~/.logs/tgautosign");
        term.addView(tt);
        final LinearLayout termBody = new LinearLayout(act);
        termBody.setOrientation(LinearLayout.VERTICAL);
        term.addView(termBody);
        TextView tm = new TextView(act); tm.setTextSize(11); tm.setTypeface(android.graphics.Typeface.MONOSPACE); tm.setTextColor(Theme.termCyan(act));
        tm.setText("[更多日志] → 运行日志"); tm.setPadding(0, dp(6), 0, 0); tm.setClickable(true);
        tm.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ runAction(act, "log"); } });
        term.addView(tm);
        if (!fastMainOpen) {
            term.setAlpha(0f); term.setTranslationY(Theme.dp(act, 8));
            term.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(android.view.View vv) { vv.postDelayed(new Runnable() { @Override public void run() { vv.animate().alpha(1f).translationY(0f).setDuration(350).start(); } }, 620L); }
                @Override public void onViewDetachedFromWindow(android.view.View vv) { vv.animate().cancel(); vv.setAlpha(0f); vv.setTranslationY(Theme.dp(act, 8)); }
            });
        }
        head.addView(term);
        renderTermBody(termBody, act);
        // 实时刷新：面板还开着就每 4 秒重建一次日志内容（isShown 自停，避免多开叠加）
        mainHandler.postDelayed(new Runnable(){ @Override public void run(){
            try {
                if (termBody == null || !termBody.isShown()) return;
                renderTermBody(termBody, act);
                termBody.postDelayed(this, 4000L);
            } catch (Throwable ignored) {}
        } }, 4000L);
        // 快捷命令行
        LinearLayout quick = new LinearLayout(act); quick.setOrientation(LinearLayout.HORIZONTAL); quick.setPadding(0, dp(8), 0, 0);
        String[][] QK = {{"sign", "\u25B6 \u7acb\u5373\u7b7e\u5230"}, {"list", "\u2192 \u76ee\u6807"}, {"log", "\u2441 \u65e5\u5fd7"}, {"diag", "\u2694 \u81ea\u68c0"}};
        for (final String[] q : QK) {
            TextView qb = new TextView(act); qb.setText(q[1]); qb.setTextSize(11); qb.setTypeface(android.graphics.Typeface.MONOSPACE);
            qb.setTextColor(Theme.termCyan(act)); qb.setClickable(true);
            qb.setPadding(dp(6), dp(7), dp(6), dp(7));
            qb.setBackground(termBorder(act, Theme.withAlpha(Theme.termCyan(act), 0x12), Theme.withAlpha(Theme.termCyan(act), 0x59)));
            qb.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ runAction(act, q[0]); } });
            quick.addView(qb, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        }
        head.addView(quick);
        root.addView(head);
        android.widget.GridLayout grid = new android.widget.GridLayout(act);
        grid.setColumnCount(2);
        root.addView(grid);
        addTile(grid, act, "📋", "目标列表", "查看·测试·编辑·删除", "list");
        addTile(grid, act, "➕", "添加目标", "指令 或 捕获按钮", "add");
        addTile(grid, act, "🚀", "立即签到", "当前账号全部", "sign");
        addTile(grid, act, "🌐", "签全部账号", activatedAccounts() + " 个账号", "sign_all_accounts");
        addTile(grid, act, "🔬", "回调调试台", "按钮·发射·绑定", "debug");
        addTile(grid, act, "⧉", "复制目标", "给其它账号", "copy_targets");
        addTile(grid, act, "📖", "使用教程", "快速上手", "tutorial");
        addTile(grid, act, "🩺", "自诊断", "反射锚点检查", "diag");
        addTile(grid, act, "📄", "运行日志", "搜索·筛选·清空", "log");
        addTile(grid, act, "🗑", "删除目标", "移除条目", "del");
        addTile(grid, act, "🧹", "清空配置", "跨账号彻底清", "clear_all");
        addTile(grid, act, "🧾", "导出日志", "到下载目录", "export_log");
        addTile(grid, act, "⚙", "设置", "关键词·窗口·上限", "settings");
        addTile(grid, act, "📚", "预设模板", "一键添加", "presets");
        String upSub = (lastUpdate != null && lastUpdate.newer) ? "发现新版本 v" + lastUpdate.version + "，可下载" : "当前 v" + UpdateChecker.VERSION_NAME;
        addTile(grid, act, "🔄", "检查更新", upSub, "update");
        addTile(grid, act, "📤", "导出配置", "json 备份", "export");
        addTile(grid, act, "📥", "导入配置", "合并或覆盖", "import");
        ScrollView scv = new ScrollView(act);
        scv.addView(root, new android.widget.ScrollView.LayoutParams(-1, -2));
        showDialog(act, "TGAutoSign · 管理", scv, "关闭");
    }

    private int countOtherAccounts() {
        int n = 0; int cur = currentAccount();
        try {
            int total = Math.max(1, activatedAccounts());
            for (int acc = 0; acc < total; acc++) {
                if (acc == cur) continue;
                for (String k : prefs.getAll().keySet()) if (k.startsWith("acc" + acc + "_learned_")) n++;
            }
        } catch (Throwable ignored) {}
        return n;
    }

    private void showLog(final Activity act) {
            if (act == null) { toast("请在 TG 界面使用 /jmb"); return; }
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Theme.dp(act, 10), Theme.dp(act, 4), Theme.dp(act, 10), Theme.dp(act, 4));

            android.widget.HorizontalScrollView bar = new android.widget.HorizontalScrollView(act);
            bar.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(act);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            bar.addView(chips);
            logChip(act, chips, "全部", new Runnable() { @Override public void run() { logFilter = LV_DEBUG; logShowDebug = true; refreshLog(); } });
            logChip(act, chips, "只看重要", new Runnable() { @Override public void run() { logFilter = LV_WARN; logShowDebug = false; refreshLog(); } });
            logChip(act, chips, "只看错误", new Runnable() { @Override public void run() { logFilter = LV_ERR; logShowDebug = false; refreshLog(); } });
            logChip(act, chips, logDesc ? "↓ 最新在上" : "↑ 最新在下", new Runnable() { @Override public void run() { logDesc = !logDesc; refreshLog(); } });
            logChip(act, chips, "🗑 清空", new Runnable() { @Override public void run() { confirmClearLog(act); } });
            logChip(act, chips, "📤 导出", new Runnable() { @Override public void run() { doExportLog(act); } });
            root.addView(bar);

            EditText q = new EditText(act);
            q.setSingleLine();
            q.setTextSize(13);
            q.setHint("搜索日志，边输边过滤");
            q.setTextColor(Theme.termTxt(act));
            q.setHintTextColor(Theme.termFaint(act));
            q.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    logQuery = String.valueOf(s).trim();
                    refreshLog();
                }
            });
            root.addView(q);

            logStat = new TextView(act);
            logStat.setTextSize(11);
            logStat.setTextColor(Theme.termMuted(act));
            root.addView(logStat);

            ScrollView sv = new ScrollView(act);
            logList = new LinearLayout(act);
            logList.setOrientation(LinearLayout.VERTICAL);
            sv.addView(logList);
            root.addView(sv, new LinearLayout.LayoutParams(-1, Theme.dp(act, 340)));

            TextView legend = new TextView(act);
            legend.setTextSize(11);
            legend.setTextColor(Theme.termMuted(act));
            legend.setText("颜色：红=出错要处理 · 黄=会自动重试 · 绿=成功 · 灰白=普通 · 淡灰=调试细节。长按任意行可复制。");
            root.addView(legend);

            showDialog(act, "运行日志", root, "关闭");
            refreshLog();
        }

        private void logChip(final Context c, LinearLayout parent, String label, final Runnable action) {
            TextView tv = new TextView(c);
            tv.setTextSize(13);
            tv.setText(label);
            tv.setSingleLine(true);
            tv.setTextColor(Theme.termTxt(c));
            tv.setBackground(termBorder(c, Theme.termCard(c), Theme.withAlpha(Theme.termCyan(c), 0x22)));
            tv.setPadding(Theme.dp(c, 10), Theme.dp(c, 6), Theme.dp(c, 10), Theme.dp(c, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Theme.dp(c, 2), 0, Theme.dp(c, 2), 0);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> action.run());
            parent.addView(tv);
        }

        private void refreshLog() {
            try {
                if (logList == null) return;
                logList.removeAllViews();
                List<LogLine> all = mergedLog(800);
                List<LogLine> show = new ArrayList<LogLine>();
                int errs = 0, warns = 0;
                String qq = logQuery.toLowerCase(Locale.US);
                for (LogLine l : all) {
                    if (l.lv == LV_ERR) errs++;
                    else if (l.lv == LV_WARN) warns++;
                    if (!logShowDebug && l.lv == LV_DEBUG) continue;
                    if (l.lv < logFilter) continue;
                    if (qq.length() > 0 && String.valueOf(l.msg).toLowerCase(Locale.US).indexOf(qq) < 0) continue;
                    show.add(l);
                }
                if (logDesc) Collections.reverse(show);
                int n = Math.min(show.size(), 200);
                for (int i = 0; i < n; i++) logRow(logList, show.get(i));
                if (n == 0) emptyView(logList, show.size() == 0 ? "没有符合条件的日志" : "没有匹配「" + logQuery + "」的日志");
                String span = "";
                if (!all.isEmpty()) {
                    span = " · 时间 " + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(all.get(0).ts))
                            + " 起 " + all.size() + " 条";
                }
                if (logStat != null) logStat.setText("错误 " + errs + " · 警告 " + warns + span
                        + " · 当前显示 " + n + (show.size() > n ? "（更多请导出查看）" : ""));
            } catch (Throwable t) {
                try { loge("日志页刷新失败: " + t); } catch (Throwable ignored) {}
            }
        }

        private void logRow(LinearLayout parent, final LogLine l) {
            final Context c = parent.getContext();
            final String body = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(l.ts)) + "  " + l.msg;
            TextView tv = new TextView(c);
            tv.setTextSize(12);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(Theme.dp(c, 2), Theme.dp(c, 3), Theme.dp(c, 2), Theme.dp(c, 3));
            int col;
            switch (l.lv) {
                case LV_ERR: col = Theme.dark(c) ? 0xFFFF8A80 : 0xFFB00020; break;
                case LV_WARN: col = Theme.dark(c) ? 0xFFFFC466 : 0xFFB26A00; break;
                case LV_OK: col = Theme.dark(c) ? 0xFF7BD88F : 0xFF1B7E33; break;
                case LV_DEBUG: col = Theme.dark(c) ? 0xFF74797F : 0xFF9AA0A6; break;
                default: col = 0xFFB8C4DC;
            }
            tv.setTextColor(col);
            tv.setText(body);
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { copyToClip(body); return true; }
            });
            parent.addView(tv);
        }

        private void copyToClip(String body) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("TGAutoSign", body));
                toast("已复制这一行");
            } catch (Throwable t) { logw("复制失败: " + t); }
        }

        private void confirmClearLog(final Activity act) {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Theme.dp(act, 16), Theme.dp(act, 8), Theme.dp(act, 16), Theme.dp(act, 8));
            TextView t = new TextView(act);
            t.setTextSize(13);
            t.setTextColor(Theme.termTxt(act));
            t.setText("清空会同时删掉当前列表和落盘的历史日志文件。\n建议先「导出再清空」留一份，方便之后对账。");
            box.addView(t);
            Button b1 = mkBtn(act);
            b1.setText("先导出一份，再清空");
            b1.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doExportLog(act); clearLogNow(); } });
            box.addView(b1);
            Button b2 = mkBtn(act);
            b2.setText("直接清空");
            b2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { clearLogNow(); } });
            box.addView(b2);
            showDialog(act, "清空运行日志", box, "取消");
        }

        private void clearLogNow() {
            try {
                synchronized (logBuffer) { logBuffer.clear(); diskQueue.clear(); }
                java.io.File dir = logDir();
                if (dir != null) {
                    java.io.File[] fs = dir.listFiles();
                    if (fs != null) for (java.io.File f : fs) {
                        String n = f.getName();
                        if (n.equals("run.log") || (n.startsWith("run.") && n.endsWith(".log"))) f.delete();
                    }
                }
                jlog(LV_INFO, "运行日志已清空，这条是新起的第一条");
                toast("日志已清空");
                refreshLog();
            } catch (Throwable t) { logw("清空日志失败: " + t); }
        }

    private void tcard(LinearLayout box, Activity act, String h, String b) {
        LinearLayout card = new LinearLayout(act); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, Theme.dp(act,4), 0, Theme.dp(act,4)); card.setLayoutParams(lp);
        card.setPadding(Theme.dp(act,14), Theme.dp(act,12), Theme.dp(act,14), Theme.dp(act,12));
        TextView ht = new TextView(act); ht.setTextSize(15); ht.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); ht.setTextColor(Theme.termCyan(act)); ht.setText(h); card.addView(ht);
        TextView bt = new TextView(act); bt.setTextSize(12); bt.setTextColor(Theme.termMuted(act)); bt.setTypeface(android.graphics.Typeface.MONOSPACE); bt.setPadding(0, Theme.dp(act,4), 0, 0); bt.setText(b); card.addView(bt);
        box.addView(card);
    }

    private void showTutorial(Activity act) {
        if (act == null) return;
        ScrollView sv = new ScrollView(act);
        LinearLayout box = new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(Theme.dp(act,10), Theme.dp(act,4), Theme.dp(act,10), Theme.dp(act,4));
        tcard(box, act, "开始：打开面板", "在任意聊天输入框发送 /jmb 即可打开管理面板。");
        tcard(box, act, "文本指令签到", "添加目标→文本指令：填机器人数字ID + 它要求的签到文本（如 /checkin）。到点自动发送，不需要面板。");
        tcard(box, act, "回调按钮签到（重点）", "这类要“点”。添加目标→回调按钮(捕获)→去该bot会话点一下它的签到按钮→面板会列出该消息所有按钮→点你要绑的（可连点多个）。绑定不受关键词限制。");
        tcard(box, act, "前置命令（拉面板）", "有的bot不主动发面板。在条目“编辑”里填“前置命令序列”（逗号或换行分隔，可多条，如 /start, 菜单）。签到/测试时会先依次发送把面板拉出来，再点按钮。");
        tcard(box, act, "测试 / 调试台", "列表点某条→测试：跑前置命令+点按钮+把机器人返回结果显示给你。调试台：列出面板全部按钮，点任意一个实时发一次看返回，最适合排查哪个按钮或data才对。");
        tcard(box, act, "重新识别类型", "老数据若显示成指令，进条目操作点“重绑为回调”，去点一次它的按钮即可按真实回调重建。");
        tcard(box, act, "多账号", "每个账号的目标与今天是否已签各自独立；在TG切到对应账号，面板就是那个账号的目标。首页会显示当前账号与其它账号目标数。");
        tcard(box, act, "关键词 / 自动学习", "设置里的关键词只影响“点一下自动学习”。过滤默认关闭：你点过的都能绑；开启后只有命中关键词的按钮才自动加。");
        tcard(box, act, "清空 / 导出 / 导入", "删不干净时先“清空所有配置”（跨全部账号彻底清）。换设备/账号用 导出→导入（导入是合并）。");
        tcard(box, act, "自诊断 / 更新", "自诊断列出宿主反射锚点是否正常，第三方客户端(XF/Nagram)适配看这里。检查更新走官方发布。");
        sv.addView(box);
        showDialog(act, Art.bold("TGAutoSign") + " 使用教程", sv, "关闭");
    }

    private void addDiagRow(LinearLayout box, Activity act, String label, boolean ok) {
        TextView t = new TextView(act);
        t.setTextSize(12); t.setTextColor(Theme.termTxt(act)); t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(Theme.dp(act,12), Theme.dp(act,10), Theme.dp(act,12), Theme.dp(act,10));
        t.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, Theme.dp(act,3), 0, Theme.dp(act,3)); t.setLayoutParams(lp);
        t.setText((ok ? "\u2705 " : "\u26a0\ufe0f ") + label);
        box.addView(t);
    }

    private void showDiag(Activity act) {
        if (act == null) return;
        ScrollView sv = new ScrollView(act);
        LinearLayout box = new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(Theme.dp(act,10), Theme.dp(act,4), Theme.dp(act,10), Theme.dp(act,4));
        addDiagRow(box, act, "宿主包 " + safePkg(), true);
        addDiagRow(box, act, "当前账号：" + accountLabel(currentAccount()) + "（共登录 " + activatedAccounts() + " 个）", currentAccount() >= 0);
        for (int ai = 0; ai < activatedAccounts(); ai++) {
            int cnt = acctTargetCount(ai);
            addDiagRow(box, act, accountLabel(ai) + "：" + (cnt == 0 ? "还没有签到目标，切过去学一个" : cnt + " 个目标"), cnt > 0);
        }
        String[] anchors = {
            "org.telegram.messenger.UserConfig",
            "org.telegram.messenger.MessagesController",
            "org.telegram.messenger.MessagesStorage",
            "org.telegram.tgnet.ConnectionsManager",
            "org.telegram.tgnet.TLObject",
            "org.telegram.tgnet.RequestDelegate",
            "org.telegram.tgnet.TLRPC$TL_messages_getBotCallbackAnswer",
            "org.telegram.tgnet.TLRPC$TL_messages_sendMessage",
            "org.telegram.ui.ActionBar.AlertDialog$Builder",
            "org.telegram.ui.Components.ChatActivityEnterView"
        };
        for (String a : anchors) {
            boolean okA; String why = "";
            try { okA = classEx(a) != null; if (!okA) why = "  (未解析)"; }
            catch (Throwable t) { okA = false; why = "  (" + t.getClass().getSimpleName() + ")"; }
            addDiagRow(box, act, a.substring(a.lastIndexOf('.') + 1) + why, okA);
        }
        String missM = Hosts.missingMarkers(cl);
        addDiagRow(box, act, "Telegram 标志类：" + (missM.isEmpty() ? "三项齐全" : "缺 " + missM + "（该客户端自研了这层，模块不注入也不误伤）"), missM.isEmpty());
        addDiagRow(box, act, "回调按钮读取正常（按实例字段取，不依赖类名）", true);
        addDiagRow(box, act, "已采样过按钮（捕获/调试台可用）", lastCapBtns != null);
        sv.addView(box);
        showDialog(act, "自诊断", sv, "关闭");
    }

    private void confirmDelete(final Activity act, final Map<String, Object> m) {
        try {
            new android.app.AlertDialog.Builder(act)
                .setTitle("删除目标")
                .setMessage(targetTitle(entryDid(m)) + "  " + entryText(m) + "\n确定删除？（会跨所有账号清干净）")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String id = entryId(m);
                        removeEntryEverywhere(id);
                        for (int j = targets.size() - 1; j >= 0; j--) if (entryId(targets.get(j)).equals(id)) targets.remove(j);
                        toast("已删除");
                        showList(act);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Throwable t) { toast("确认框失败: " + t); }
    }


    private static boolean themeLogDone;
    private boolean isDarkMode(Context ctx) {
        boolean tg = false;
        String how = "no-method";
        try {
            Class<?> th = classEx("org.telegram.ui.ActionBar.Theme");
            for (java.lang.reflect.Method m : th.getMethods()) {
                String n = m.getName();
                if (m.getParameterTypes().length == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && (n.equals("isCurrentThemeDark") || n.equals("isCurrentThemeNight"))) {
                    Object r = m.invoke(null);
                    if (r instanceof Boolean) { tg = ((Boolean) r).booleanValue(); how = n; break; }
                }
            }
        } catch (Throwable t) { how = "exc-" + t.getClass().getSimpleName(); }
        boolean sys = false;
        try {
            sys = (ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {}
        if (how.startsWith("no-method") || how.startsWith("exc-")) { tg = sys; how = how + "->sys"; }
        if (!themeLogDone) {
            themeLogDone = true;
            jlog("主题判定: dark=" + tg + " 来源=" + how + " 系统=" + (sys ? "dark" : "light"));
        }
        return tg;
    }

    private String txtMain(Context ctx) { return isDarkMode(ctx) ? "#F2F2F2" : "#1F1F1F"; }
    private String txtSub(Context ctx) { return isDarkMode(ctx) ? "#ABABAB" : "#757575"; }

    // ---------------- 界面版：管理对话框（Telegram 风格） ----------------
    private View menuItemOld(LinearLayout parent, String emoji, String title, String subtitle, String action) {
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
        div.setBackgroundColor(Theme.line(c));
        parent.addView(div, new LinearLayout.LayoutParams(-1, 1));
        return row;
    }

    private View targetRowOld(LinearLayout parent, Map<String, Object> entry, String status, String action) {
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
        div.setBackgroundColor(Theme.line(c));
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
        e.setTextSize(14);
        e.setTypeface(android.graphics.Typeface.MONOSPACE);
        e.setTextColor(Theme.termTxt(act));
        e.setHintTextColor(Theme.termFaint(act));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        try { e.setBackground(termBorder(act, Theme.termCardInput(act), Theme.withAlpha(Theme.termCyan(act), 0x33))); } catch (Throwable ignored) {}
        if (type == 1) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private void showDialog(Activity act, String title, View view, String negLabel) {

        if (act == null || act.isFinishing()) { toast(act == null ? "请在 Telegram 界面内使用 /jmb" : "页面已关闭，请重新打开"); return; }

        try {
            Object b = tgBuilder(act);
            call(b, "setTitle", new Class<?>[]{CharSequence.class}, new Object[]{title});
            call(b, "setView", new Class<?>[]{View.class}, new Object[]{view});
            // TG 的 Builder.setNegativeButton（找不到/签名不符时忽略，TG 对话框仍可显示）
            Object d;
            try {
                call(b, "setNegativeButton", new Class<?>[]{CharSequence.class, android.content.DialogInterface.OnClickListener.class}, new Object[]{negLabel, null});
            } catch (Throwable e1) {
                logd("[对话框] setNegativeButton 未找到(忽略): " + e1);
                try { call(b, "setCancelable", new Class<?>[]{boolean.class}, new Object[]{true}); } catch (Throwable ignored) {}
            }
            d = call(b, "create", new Class<?>[0], new Object[0]);
            if (d != null) call(d, "show", new Class<?>[0], new Object[0]);
            logd("[对话框] TG 风格对话框成功: " + title);
            return;
        } catch (Throwable t) {
            logd("[对话框] TG 对话框不可用(TG 12.10.3+ 重构 Builder)，降级系统框: " + t);
        }
        // 兜底：尽量接近 TG 深色卡片风格
        try {
            android.app.AlertDialog.Builder ab = new android.app.AlertDialog.Builder(act);
            ab.setTitle(title);
            ab.setView(view);
            ab.setNegativeButton(negLabel, null);
            android.app.AlertDialog ad = ab.create();
            if (ad.getWindow() != null) {
                android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
                bgd.setColor(Theme.dark(act) ? 0xFF202124 : 0xFFF7F8FA);
                bgd.setCornerRadius(dp(12));
                ad.getWindow().setBackgroundDrawable(bgd);
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
        if ("add".equals(action)) { showAddChooser(act); return; }
        if ("del".equals(action)) { showDelete(act); return; }
        if ("sign".equals(action)) { showSign(act); return; }
        if ("sign_all_accounts".equals(action)) { signAllAccounts(); return; }
        if ("copy_targets".equals(action)) { showCopyTargets(act); return; }
        if ("log".equals(action)) { showLog(act); return; }
        if ("settings".equals(action)) { showSettings(act); return; }
        if ("presets".equals(action)) { showPresets(act); return; }
        if ("update".equals(action)) { showUpdate(act); return; }
        if ("update_download".equals(action)) { downloadUpdate(act); return; }
        if ("export".equals(action)) { doExport(); return; }
        if ("export_log".equals(action)) { doExportLog(act); return; }
        if ("import".equals(action)) { showImportPicker(act); return; }
        if ("clear_all".equals(action)) { confirmClearAll(act); return; }
        if ("add_text".equals(action)) { showAdd(act); return; }
        if ("cap_cb".equals(action)) { startCapture(act); return; }
        if ("debug".equals(action)) { showDebugConsole(act); return; }
        if ("tutorial".equals(action)) { showTutorial(act); return; }
        if ("diag".equals(action)) { showDiag(act); return; }
    }

    // ---------------- 1.4.4：节奏 / 每日上限 / 暂停 / 子命令 ----------------
    private void pace() {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return;   // 主线程不睡，避免卡界面
            long now = System.currentTimeMillis();
            long wait;
            synchronized (TLOCK) {
                long next = Math.max(lastPaceAt + 300L + random.nextInt(900), now + 150L);
                wait = next - now;
                lastPaceAt = next;
            }
            if (wait > 0) Thread.sleep(wait);
        } catch (Throwable ignored) {}
    }

    private int dailyUsed(int account) {
        try {
            String p = "acc" + account;
            if (!todayStr().equals(prefs.getString(p + "_daycap_date", ""))) return 0;
            return prefs.getInt(p + "_daycap_n", 0);
        } catch (Throwable t) { return 0; }
    }

    private void bumpDaily(int account) {
        try {
            String p = "acc" + account;
            String today = todayStr();
            if (!today.equals(prefs.getString(p + "_daycap_date", ""))) {
                prefs.edit().putString(p + "_daycap_date", today).putInt(p + "_daycap_n", 1).apply();
            } else {
                prefs.edit().putInt(p + "_daycap_n", prefs.getInt(p + "_daycap_n", 0) + 1).apply();
            }
        } catch (Throwable ignored) {}
    }

    private void bumpDailyResult(int account, boolean good) {
        try {
            String p = "acc" + account;
            String today = todayStr();
            if (!today.equals(prefs.getString(p + "_stat_date", ""))) {
                prefs.edit().putString(p + "_stat_date", today).putInt(p + "_ok", 0).putInt(p + "_err", 0).apply();
            }
            if (good) prefs.edit().putInt(p + "_ok", prefs.getInt(p + "_ok", 0) + 1).apply();
            else      prefs.edit().putInt(p + "_err", prefs.getInt(p + "_err", 0) + 1).apply();
        } catch (Throwable ignored) {}
    }

    private boolean isSnoozed(String prefix, String id) {
        try {
            String until = prefs.getString(prefix + "snooze_" + id, "");
            if (until == null || until.length() == 0) return false;
            return until.compareTo(todayStr()) > 0;
        } catch (Throwable t) { return false; }
    }

    private void toggleSnooze(Map<String, Object> m, int account) {
        try {
            String prefix = accountPrefix(account);
            String id = entryId(m);
            String title = targetTitle(entryDid(m));
            if (isSnoozed(prefix, id)) {
                prefs.edit().remove(prefix + "snooze_" + id).apply();
                toast("已恢复：" + title);
                logs("【暂停】已恢复 " + title + " (acc" + account + ")");
            } else {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.add(java.util.Calendar.DAY_OF_YEAR, 7);
                String until = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
                prefs.edit().putString(prefix + "snooze_" + id, until).apply();
                toast("已暂停一周（至 " + until + "）：" + title);
                logs("【暂停】" + title + " 暂停至 " + until + " (acc" + account + ")");
            }
        } catch (Throwable t) { toast("暂停操作失败: " + t); }
    }

    /** /jmb 子命令：log=复制最近日志；update=强制检查更新；其他返回 false 走原面板 */
    private boolean handleJmbSub(String raw) {
        String t = String.valueOf(raw).trim();
        if (!isJmbCommand(t)) return false;
        String sub = t.length() > 4 ? t.substring(4).trim() : "";
        if (sub.length() == 0) return false;
        if ("log".equals(sub) || "日志".equals(sub)) {
            copyRecentLogs();
            return true;
        }
        if ("update".equals(sub) || "检查更新".equals(sub)) {
            forceCheckUpdate();
            return true;
        }
        return false;
    }

    private void copyRecentLogs() {
        try {
            java.io.File dir = logDir();
            if (dir == null) { toast("日志目录不可用"); return; }
            java.io.File f = new java.io.File(dir, "run.log");
            if (!f.isFile()) {
                java.io.File[] fs = dir.listFiles();
                if (fs != null) for (java.io.File x : fs) {
                    if (x.getName().equals("run.log") || (x.getName().startsWith("run.") && x.getName().endsWith(".log"))) { f = x; break; }
                }
            }
            if (f == null || !f.isFile()) { toast("还没有日志文件"); return; }
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8));
            String l; int n = 0;
            while ((l = br.readLine()) != null && n < 400) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(l); n++;
            }
            br.close();
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    appContext.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("TGAutoSign", sb.toString()));
            toast("已复制最近 " + n + " 行日志到剪贴板");
            jlog("【/jmb log】已复制最近 " + n + " 行日志");
        } catch (Throwable t) { toast("复制日志失败: " + t); }
    }

    private void forceCheckUpdate() {
        try {
            UpdateChecker.checkAsync(appContext, true, mainHandler, r -> {
                if (r == null) return;
                if (r.networkError) { toast("检查更新未成功: " + r.message); return; }
                lastUpdate = r;
                if (r.newer) {
                    toast("发现新版本 v" + r.version + "（当前 v" + UpdateChecker.VERSION_NAME + "）：发 /jmb → 🔄 检查更新");
                } else {
                    toast("已是最新 v" + UpdateChecker.VERSION_NAME);
                }
                logs("【/jmb update】" + (r.newer ? "发现新版本 v" + r.version : "已是最新 v" + UpdateChecker.VERSION_NAME));
            });
        } catch (Throwable t) { toast("检查更新失败: " + t); }
    }

    private void runTargetAction(Context ctx, String action, String id) {
        if (ctx == null || !(ctx instanceof Activity)) return;
        if ("delete".equals(action)) {
            Map<String, Object> m = findEntryById(id);
            if (m == null) { toast("目标不存在"); return; }
            long did = entryDid(m);
            removeEntryEverywhere(id);   // 跨全部账号前缀删净，杜绝删了重开又出现
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
            return;
        }
        if ("test".equals(action)) { testEntry(id); return; }
        if ("edit".equals(action)) { Map<String,Object> em=findEntryById(id); if (em!=null) showEditEntry((Activity)ctx, em); return; }
        if ("more".equals(action)) { Map<String,Object> mm=findEntryById(id); if (mm!=null) showEntryActions((Activity)ctx, mm); return; }
        if ("snooze".equals(action)) { Map<String,Object> sn=findEntryById(id); if (sn!=null) toggleSnooze(sn, currentAccount()); return; }
    }


    private void showImportPicker(final Activity act) {
        if (act == null) { toast("请在 TG 界面使用 /jmb"); return; }
        java.util.List<java.io.File> fs = ConfigStore.listExports(appContext);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.dp(act, 16), Theme.dp(act, 8), Theme.dp(act, 16), Theme.dp(act, 8));
        TextView info = new TextView(act);
        info.setTextSize(13);
        info.setTextColor(Theme.termTxt(act));
        if (fs.isEmpty()) {
            info.setText("没有找到备份文件。\n\n备份放在这里：\nAndroid/data/" + safePkg() + "/files/tgautosign/\n（在 /jmb → 📤 导出配置 里生成，也可以手动把 json 拷进去）");
            box.addView(info);
            showDialog(act, "导入配置", box, "关闭");
            return;
        }
        info.setText("选一份备份导入。导入前会显示它的内容，导入后会告诉你目标数有没有变化。\n"
                + "合并 = 只覆盖文件里有的键；覆盖 = 先清掉现有目标和状态再导入。");
        box.addView(info);
        int n = 0;
        for (final java.io.File f : fs) {
            if (n++ >= 10) break;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Theme.dp(act, 4), Theme.dp(act, 11), Theme.dp(act, 4), Theme.dp(act, 11));
            TextView t1 = new TextView(act);
            t1.setTextSize(14);
            t1.setTextColor(Theme.termTxt(act)); t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            t1.setText(f.getName());
            row.addView(t1);
            TextView t2 = new TextView(act);
            t2.setTextSize(11);
            t2.setTextColor(Theme.termMuted(act)); t2.setTypeface(android.graphics.Typeface.MONOSPACE);
            t2.setText(ConfigStore.describe(f));
            row.addView(t2);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showImportMode(act, f); }
            });
            box.addView(row);
            View div = new View(act);
            div.setBackgroundColor(Theme.line(act));
            box.addView(div, new LinearLayout.LayoutParams(-1, 1));
        }
        showDialog(act, "导入配置 · 选备份", box, "关闭");
    }

    private void showImportMode(final Activity act, final java.io.File f) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.dp(act, 16), Theme.dp(act, 8), Theme.dp(act, 16), Theme.dp(act, 8));
        TextView t = new TextView(act);
        t.setTextSize(12);
        t.setTextColor(Theme.termTxt(act)); t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setText(f.getName() + "\n" + ConfigStore.describe(f)
                + "\n\n" + accountLabel(currentAccount()) + " 现在有 " + targetsSnapshot().size() + " 个目标。");
        box.addView(t);
        Button b1 = mkBtn(act);
        b1.setText("合并导入（保留现有的）");
        b1.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doImportNow(act, f, false); } });
        box.addView(b1);
        Button b2 = mkBtn(act);
        b2.setText("覆盖导入（先清空目标和状态）");
        b2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doImportNow(act, f, true); } });
        box.addView(b2);
        showDialog(act, "怎么导入", box, "取消");
    }

    private void doImportNow(Activity act, java.io.File f, boolean replace) {
        int before = targetsSnapshot().size();
        try { if (replace) clearAllConfig(); } catch (Throwable ignored) {}
        ConfigStore.Report rep = ConfigStore.importMerge(appContext, f, replace);
        if (!rep.ok) {
            loge("导入失败：" + rep.message);
            toast("导入失败：" + rep.message);
            return;
        }
        try { lastAccount = -1; syncAccount(); } catch (Throwable ignored) {}
        int after = targetsSnapshot().size();
        String line = "【导入】" + f.getName() + " · " + (replace ? "覆盖" : "合并") + " · 写入 " + rep.keys + " 个键"
                + (rep.skipped > 0 ? "（忽略不认识的老键 " + rep.skipped + " 个）" : "")
                + " · " + accountLabel(currentAccount()) + " 目标 " + before + " → " + after;
        if (after == before && before > 0) logw(line + " —— 当前账号的目标数没变");
        else logs(line);
        toast(after == before
                ? "导入完成，但当前账号目标数没变（" + after + " 个）。\n备份可能是同账号的旧内容，或切到别的账号再看"
                : "导入完成：" + accountLabel(currentAccount()) + " 目标 " + before + " → " + after);
        if (act != null) showList(act);
    }

    private void showList(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        if (targets.size() == 0) {
            emptyView(box, "(暂无目标，点 ➕ 添加，或直接点 bot 的签到按钮自动学习)");
        }
        String today = todayStr();
        for (Map<String, Object> m : targetsSnapshot()) {
            targetRow(box, m, statusOf(accountPrefix(), entryId(m), today), "more");
        }
        showDialog(act, "目标列表（" + targets.size() + "）", box, "关闭");
    }


    // ---------------- v1.5.2：签到窗口 / 连续签到 / 预设模板 ----------------

    private int parseHM(String s) {
        try {
            String[] p = s.split(":");
            if (p.length != 2) return -1;
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Throwable t) { return -1; }
    }

    private int[] windowRange() {
        return windowRangeOf(WINDOW);
    }

    private boolean inWindow() {
        int[] r = windowRange();
        if (r == null) return true;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int n = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        return n >= r[0] && n <= r[1];
    }

    private String nowHM() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    private boolean isYesterday(String d) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date dt = f.parse(d);
            java.util.Calendar y = java.util.Calendar.getInstance();
            y.add(java.util.Calendar.DATE, -1);
            return f.format(dt).equals(f.format(y.getTime()));
        } catch (Throwable t) { return false; }
    }

    private void updateStreak(String prefix) {
        try {
            String today = todayStr();
            String lastDate = prefs.getString(prefix + "last_sign_date", "");
            if (today.equals(lastDate)) return;
            int n = prefs.getInt(prefix + "streak", 0);
            if (lastDate.length() > 0 && isYesterday(lastDate)) n = n + 1; else n = 1;
            prefs.edit().putInt(prefix + "streak", n).putString(prefix + "last_sign_date", today).apply();
        } catch (Throwable ignored) {}
    }

    private int streakOf(String prefix) {
        try {
            String lastDate = prefs.getString(prefix + "last_sign_date", "");
            String today = todayStr();
            if (lastDate.length() > 0 && (today.equals(lastDate) || isYesterday(lastDate))) {
                return prefs.getInt(prefix + "streak", 0);
            }
            if (lastDate.length() == 0 && hasSignedToday(prefix)) return 1;
            return 0;
        } catch (Throwable t) { return 0; }
    }

    private boolean hasSignedToday(String prefix) {
        try {
            String today = todayStr();
            List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
            loadTargetsInto(prefix, l);
            for (Map<String, Object> m : l) {
                if (today.equals(prefs.getString(prefix + "last_" + entryId(m), ""))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private java.util.Set<String> signDays(String prefix) {
        java.util.Set<String> s = new java.util.HashSet<String>();
        String v = prefs.getString(prefix + "sign_days", "");
        if (v != null) {
            for (String x : v.split(",")) {
                String t = x.trim();
                if (t.length() > 0) s.add(t);
            }
        }
        return s;
    }

    private void noteSignedDay(String prefix) {
        try {
            String today = todayStr();
            java.util.Set<String> s = signDays(prefix);
            if (s.add(today)) {
                StringBuilder sb = new StringBuilder();
                int cap = 0;
                for (String x : s) {
                    if (cap++ > 90) break;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(x);
                }
                prefs.edit().putString(prefix + "sign_days", sb.toString()).apply();
            }
        } catch (Throwable ignored) {}
    }

    private int[] windowRangeOf(String w) {
        try {
            if (w == null) return null;
            String t = w.trim();
            if (t.isEmpty()) return null;
            String[] p = t.split("-");
            if (p.length != 2) return null;
            int a = parseHM(p[0].trim());
            int b = parseHM(p[1].trim());
            if (a < 0 || b < 0 || b < a) return null;
            return new int[]{a, b};
        } catch (Throwable t) { return null; }
    }

    private final Runnable windowWakeRunnable = new Runnable() {
        @Override public void run() {
            try { trySignAll("进入窗口", false); } catch (Throwable ignored) {}
            scheduleWindowWake();
        }
    };

    private void scheduleWindowWake() {
        try {
            int[] r = windowRange();
            if (r == null) { mainHandler.removeCallbacks(windowWakeRunnable); return; }
            java.util.Calendar c = java.util.Calendar.getInstance();
            int nowSec = c.get(java.util.Calendar.HOUR_OF_DAY) * 3600 + c.get(java.util.Calendar.MINUTE) * 60 + c.get(java.util.Calendar.SECOND);
            int startSec = r[0] * 60;
            int endSec = r[1] * 60;
            if (nowSec >= startSec && nowSec <= endSec) { mainHandler.removeCallbacks(windowWakeRunnable); enqueueTry("进入窗口"); return; }
            long delay = nowSec < startSec ? (long) (startSec - nowSec) * 1000L : (long) (24 * 3600 - nowSec + startSec) * 1000L;
            delay += (long) (Math.random() * 2L * 60L * 1000L);
            mainHandler.removeCallbacks(windowWakeRunnable);
            mainHandler.postDelayed(windowWakeRunnable, delay);
        } catch (Throwable ignored) {}
    }

    private LinearLayout streakCalendar(Activity act) {
        try {
            java.util.Set<String> days = signDays(accountPrefix());
            if (days.isEmpty() && hasSignedToday(accountPrefix())) days.add(todayStr());
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            LinearLayout row1 = new LinearLayout(act); row1.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout row2 = new LinearLayout(act); row2.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 14; i++) {
                java.util.Calendar cc = java.util.Calendar.getInstance();
                cc.set(java.util.Calendar.HOUR_OF_DAY, 12);
                cc.set(java.util.Calendar.MINUTE, 0);
                cc.set(java.util.Calendar.SECOND, 0);
                cc.set(java.util.Calendar.MILLISECOND, 0);
                cc.add(java.util.Calendar.DATE, i - 13);
                boolean on = days.contains(f.format(cc.getTime()));
                TextView cell = new TextView(act);
                cell.setTextSize(9);
                cell.setGravity(android.view.Gravity.CENTER);
                cell.setText(String.valueOf(i + 1));
                cell.setTextColor(on ? 0xFFE8FFF6 : Theme.termMuted(act));
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(dp(4));
                g.setColor(on ? 0xFF1F7A5A : Theme.termCardInput(act));
                g.setStroke(dp(1), on ? Theme.termGreen(act) : Theme.withAlpha(Theme.termCyan(act), 0x22));
                cell.setBackground(g);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, dp(26), 1f);
                lp.setMargins(dp(1), dp(2), dp(1), dp(2));
                (i < 7 ? row1 : row2).addView(cell, lp);
            }
            LinearLayout box = new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL);
            box.addView(row1);
            box.addView(row2);
            TextView legend = new TextView(act);
            legend.setTextSize(9);
            legend.setTextColor(Theme.termFaint(act));
            legend.setPadding(0, dp(2), 0, 0);
            legend.setText("1 = 13 天前 · 14 = 今天（绿 = 已签到）");
            box.addView(legend);
            return box;
        } catch (Throwable t) { return null; }
    }

    private static final String[][] PRESETS = {
        {"示例 · 每日签到", "777000", "/checkin", "示例模板：改成你的 bot（数字 ID 在 bot 里发 /start 可得）"},
        {"示例 · 群打卡", "777000", "/qd", "示例模板：群打卡指令，可改"},
        {"示例 · 领取", "777000", "/sign", "示例模板：通用领取指令，可改或删除"},
    };

    private void showPresets(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView tip = new TextView(act);
        tip.setTextSize(12);
        tip.setTextColor(Theme.termMuted(act));
        tip.setTypeface(android.graphics.Typeface.MONOSPACE);
        tip.setText("内置为通用示例模板，点选后把 bot ID 和指令改成你的签到 bot。");
        box.addView(tip);
        for (final String[] p : PRESETS) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            TextView t1 = new TextView(act);
            t1.setTextSize(14);
            t1.setTextColor(Theme.termTxt(act));
            t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            t1.setText(p[0] + "  ·  " + p[1] + "  →  " + p[2]);
            row.addView(t1);
            TextView t2 = new TextView(act);
            t2.setTextSize(11);
            t2.setTextColor(Theme.termMuted(act));
            t2.setTypeface(android.graphics.Typeface.MONOSPACE);
            t2.setText(p[3]);
            row.addView(t2);
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showPresetEdit(act, p); } });
            box.addView(row);
            View div = new View(act);
            div.setBackgroundColor(Theme.line(act));
            box.addView(div, new LinearLayout.LayoutParams(-1, 1));
        }
        showDialog(act, "预设模板", box, "关闭");
    }

    private void showPresetEdit(final Activity act, final String[] p) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        final EditText uid = adInput(act, "机器人 ID（数字，无需 @）", 1);
        uid.setText(p[1]);
        box.addView(uid);
        final EditText cmd = adInput(act, "签到指令", 0);
        cmd.setText(p[2]);
        box.addView(cmd);
        Button ok = mkBtn(act);
        ok.setText("添加");
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try {
                long did = Long.parseLong(uid.getText().toString().trim());
                String t = cmd.getText().toString().trim();
                if (t.length() == 0) { toast("指令不能为空"); return; }
                if (findTextEntry(did, t) != null) { toast("该 bot 已存在相同指令"); return; }
                learnTarget(did, t);
                Map<String, Object> m = findTextEntry(did, t);
                if (m != null) toast("✅ 已添加 " + did + " → " + t);
            } catch (Throwable e) { toast("UID 格式错误"); }
        }});
        box.addView(ok);
        showDialog(act, "确认模板", box, "取消");
    }

    private void showAdd(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText uid = adInput(act, "机器人 ID（数字，无需 @）", 1);
        EditText cmd = adInput(act, "签到指令，如：/qd 或 📅 签到", 0);
        box.addView(uid);
        box.addView(cmd);
        Button ok = mkBtn(act);
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
        for (Map<String, Object> m : targetsSnapshot()) {
            targetRow(box, m, statusOf(accountPrefix(), entryId(m), today), "delete");
        }
        showDialog(act, "点选要删除的目标", box, "取消");
    }

    private void showSign(Activity act) {
        if (targets.size() == 0) { toast("暂无目标"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        if (targets.size() > 1) {
            Button all = mkBtn(act);
            all.setText("🚀 全部签到（" + targets.size() + " 个条目）");
            all.setOnClickListener(v -> {
                trySignAll("手动全部", true);
                toast("已命令全部签到，结果见运行日志");
            });
            box.addView(all);
        }
        String today = todayStr();
        for (Map<String, Object> m : targetsSnapshot()) {
            targetRow(box, m, statusOf(accountPrefix(), entryId(m), today), "sign");
        }
        showDialog(act, "点选立即签到", box, "取消");
    }


    private android.widget.Switch swRow(Context c, String label, boolean on) {
        android.widget.Switch s = new android.widget.Switch(c);
        s.setText(label); s.setTextSize(13); s.setTextColor(Theme.termTxt(c)); s.setTypeface(android.graphics.Typeface.MONOSPACE); s.setChecked(on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, Theme.dp(c,6), 0, Theme.dp(c,6)); s.setLayoutParams(lp);
        s.setPadding(Theme.dp(c,4), Theme.dp(c,10), Theme.dp(c,4), Theme.dp(c,10));
        return s;
    }


    private void showSettings(Activity act) {
        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(8), dp(16), dp(8));
            EditText kw = adInput(act, "学习关键词（逗号分隔）", 0);
            kw.setText(LEARN_KEYWORDS == null ? "" : String.valueOf(LEARN_KEYWORDS));
            box.addView(kw);
            LinearLayout rlRow = new LinearLayout(act); rlRow.setOrientation(LinearLayout.HORIZONTAL); rlRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView rlLabel = new TextView(act); rlLabel.setText("每日重试上限"); rlLabel.setTextSize(13); rlLabel.setTextColor(Theme.termMuted(act));
            rlRow.addView(rlLabel, new LinearLayout.LayoutParams(0, -2, 1f));
            Button rlMinus = mkBtn(act); rlMinus.setText("−");
            final EditText rl = new EditText(act); rl.setText(String.valueOf(RETRY_LIMIT)); rl.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); rl.setGravity(android.view.Gravity.CENTER); rl.setTextSize(14);
            Button rlPlus = mkBtn(act); rlPlus.setText("+");
            rlMinus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(rl.getText().toString().trim()); vv=Math.max(1,vv-1); rl.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
            rlPlus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(rl.getText().toString().trim()); vv=Math.min(99,vv+1); rl.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
            rlRow.addView(rlMinus, new LinearLayout.LayoutParams(0, -2, 1f));
            rlRow.addView(rl, new LinearLayout.LayoutParams(0, -2, 2.2f));
            rlRow.addView(rlPlus, new LinearLayout.LayoutParams(0, -2, 1f));
            box.addView(rlRow);
            EditText wc = adInput(act, "全局默认唤醒命令(如 /start；条目自带前置命令优先)", 0);
            wc.setText(WAKE_CMD == null ? "" : WAKE_CMD);
            box.addView(wc);
            EditText wd = adInput(act, "每日签到窗口（如 08:00-10:00；留空=不限）", 0);
            wd.setText(WINDOW == null ? "" : WINDOW);
            box.addView(wd);
            final android.widget.Switch alSw = swRow(act, "按钮学习：点一下按钮就加到列表（关=用 捕获/调试台 手动加）", AUTO_LEARN);
            box.addView(alSw);
            final android.widget.Switch anSw = swRow(act, "网络学习：自动识别你在 bot 里发的签到文本（关=只认按钮/手动）", AUTO_LEARN_NET);
            box.addView(anSw);
            final android.widget.Switch afSw = swRow(act, "自动学习仅加命中关键词的按钮（防误加）", AUTO_LEARN_FILTER);
            box.addView(afSw);
            Button ok = mkBtn(act);
            ok.setText("保存");
            ok.setOnClickListener(v -> {
                String k = kw.getText().toString().trim();
                LEARN_KEYWORDS = k.isEmpty() ? DEF_KEYWORDS : k;
                try {
                    int r = Integer.parseInt(rl.getText().toString().trim());
                    if (r > 0 && r <= 99) RETRY_LIMIT = r;
                } catch (Throwable ignored) {}
                WAKE_CMD = wc.getText().toString().trim();
                String wv = wd.getText().toString().trim();
                if (wv.length() > 0 && windowRangeOf(wv) == null) { toast("窗口格式不对：应为 08:00-10:00（开始-结束）"); return; }
                WINDOW = wv;
                scheduleWindowWake();
                if (inWindow()) enqueueTry("进入窗口");
                AUTO_LEARN = alSw.isChecked();
                AUTO_LEARN_NET = anSw.isChecked();
                AUTO_LEARN_FILTER = afSw.isChecked();
                try {
                    prefs.edit()
                        .putString("jmb_keywords", LEARN_KEYWORDS)
                        .putInt("jmb_retry", RETRY_LIMIT)
                        .putString("jmb_wake_cmd", WAKE_CMD)
                        .putString("jmb_window", WINDOW)
                        .putBoolean("jmb_alfilter", AUTO_LEARN_FILTER)
                        .putBoolean("jmb_autolearn", AUTO_LEARN)
                        .putBoolean("jmb_autolearn_net", AUTO_LEARN_NET)
                        .apply();
                } catch (Throwable ignored) {}
                toast("设置已保存");
                jlog("设置更新: 关键词=" + LEARN_KEYWORDS + " 重试上限=" + RETRY_LIMIT + " 唤醒命令=" + WAKE_CMD + " 窗口=" + WINDOW
                    + " 按钮学习=" + (AUTO_LEARN ? "开" : "关") + " 网络学习=" + (AUTO_LEARN_NET ? "开" : "关"));
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
        if (!isJmbCommand(t)) return false;
        if (handleJmbSub(t)) return true;
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
                    menuItem(box, "⬇️", "下载 v" + r.version + " 安装包", "下载到系统「下载」目录，校验 sha256 后确认安装", "update_download");
                }
            } catch (Throwable ignored) {}
        });
    }

    private void downloadUpdate(Activity act) {
        final UpdateChecker.Result src = lastUpdate;
        if (src == null || src.apkUrl == null) { toast("该版本没有可直接下载的安装包"); return; }
        toast("开始下载 v" + src.version + "…");
        jlog("下载安装包: " + src.apkUrl);
        UpdateChecker.downloadAsync(appContext, src.apkUrl, src.apkName, src.apkSha256, mainHandler, d -> {
            if (d.networkError) { jlog("下载失败: " + d.message); toast("下载失败：" + d.message); return; }
            jlog("安装包已保存: " + d.savedPath);
            boolean opened = UpdateChecker.openSaved(appContext, d.savedUri, d.savedPath);
            toast("已保存到 " + d.savedPath + (opened ? "，请在安装界面确认" : "，请用文件管理器点开安装"));
        });
    }

    private void doExport() {
        ConfigStore.Report rep = ConfigStore.exportAll(appContext);
        if (rep.ok) {
            logs("配置已导出: " + rep.path + "（" + rep.keys + " 项 / " + rep.prefFiles + " 个存储）");
            toast("已导出 " + rep.keys + " 项配置\n" + rep.path);
        } else {
            loge("导出配置失败: " + rep.message);
            toast("导出失败：" + rep.message);
        }
    }





    /** 导出运行日志到系统「下载」目录（MediaStore，无需存储权限），便于 issue 反馈 */
    private void doExportLog(Activity act) {
            try {
                List<LogLine> all = mergedLog(4000);
                if (all.isEmpty()) { toast("暂无日志可导出"); return; }
                StringBuilder sb = new StringBuilder();
                sb.append("TGAutoSign v").append(UpdateChecker.VERSION_NAME)
                  .append("  宿主=").append(safePkg())
                  .append("  当前账号=").append(accountLabel(currentAccount()))
                  .append("  目标=").append(targetsSnapshot().size())
                  .append("  导出=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                  .append("  行数=").append(all.size()).append('\n');
                for (LogLine l : all) sb.append(l.flat()).append('\n');
                String content = sb.toString();
                android.content.ContentResolver cr = appContext.getContentResolver();
                android.content.ContentValues v = new android.content.ContentValues();
                String fname = "TGAutoSign-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) { toast("导出失败：写不进「下载」目录"); return; }
                java.io.OutputStream os = cr.openOutputStream(uri);
                if (os == null) { toast("导出失败：打不开写入流"); return; }
                try { os.write(content.getBytes("UTF-8")); } finally { try { os.close(); } catch (Throwable ignored) {} }
                jlog("日志已导出：" + fname + "（" + all.size() + " 行，含落盘历史）");
                toast("已导出到「下载」：" + fname);
            } catch (Throwable t) {
                logw("日志导出失败: " + t);
                toast("日志导出失败：" + t);
            }
        }

        private static String accountLabel(int acc) {
            return "账号" + (acc + 1);
        }

    // ---------------- 宿主 Activity 记录（兜底：任何 Activity resume 都记） ----------------

    private void registerActivityListener() {
        try {
            if (!(appContext instanceof Application)) return;
            ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) { lastActivity = activity; }
                @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { lastActivity = activity; }
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {
                    if (lastActivity == activity) lastActivity = null;
                }
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
                scheduleWindowWake();
                enqueueTry("定时");
                schedulePoll();
            } catch (Throwable ignored) {}
        }, POLL_INTERVAL_MS);
        scheduleWindowWake();
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
            if (d instanceof byte[]) return (byte[]) d;
        } catch (Throwable ignored) {}
        try {
            Object mType = getFieldVal(proto, "mType");
            if (mType != null) {
                Object d2 = getFieldVal(mType, "data");
                if (d2 instanceof byte[]) return (byte[]) d2;
            }
        } catch (Throwable ignored) {}
        try {
            Object d3 = call(proto, "getData", new Class<?>[0], new Object[0]);
            if (d3 instanceof byte[]) return (byte[]) d3;
        } catch (Throwable ignored) {}
        return null;
    }


    private long numLong(String key, long def) {
        try {
            Object o = prefs.getAll().get(key);
            if (o instanceof Number) return ((Number) o).longValue();
        } catch (Throwable ignored) {}
        return def;
    }

    private String strOf(String key) {
        try {
            Object o = prefs.getAll().get(key);
            if (o instanceof String) return (String) o;
            if (o != null) return String.valueOf(o);
        } catch (Throwable ignored) {}
        return null;
    }

    private long buttonHash(Object proto) {
        try {
            Object h = getFieldVal(proto, "hash");
            if (h instanceof Number) return ((Number) h).longValue();
        } catch (Throwable ignored) {}
        try {
            Object mType = getFieldVal(proto, "mType");
            if (mType != null) {
                Object h2 = getFieldVal(mType, "hash");
                if (h2 instanceof Number) return ((Number) h2).longValue();
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    private boolean isCallbackButton(Object proto) {
        if (proto == null) return false;
        try { byte[] d = buttonData(proto); if (d != null && d.length > 0) return true; } catch (Throwable ignored) {}
        return proto.getClass().getName().contains("Callback");
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
            if (captureArmed) { handleTapCapture(proto, moOrNull); return; }
            Object did = null;
            if (moOrNull != null) { try { did = call(moOrNull, "getDialogId", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            Object text = buttonText(proto);
            if (did != null && text != null) {
                String t = String.valueOf(text);
                if (!AUTO_LEARN || (AUTO_LEARN_FILTER && !keywordMatched(t))) { logd("[按钮] uid=" + did + " text=" + t + "（不含关键词，自动学习已过滤；可用 捕获/调试台 手动绑定）"); return; }
                long u = ((Number) did).longValue();
                if (isCallbackButton(proto)) {
                    byte[] data = buttonData(proto);
                    if (data != null && data.length > 0) {
                        int msgId = 0;
                        if (moOrNull != null) { try { Object mid = call(moOrNull, "getId", new Class<?>[0], new Object[0]); msgId = mid instanceof Number ? ((Number) mid).intValue() : 0; } catch (Throwable ignored) {} }
                        learnCallback(u, t, data, buttonHash(proto), msgId);
                    } else {
                        logd("[按钮] uid=" + u + " text=" + t + "（无回调 data，可能是链接/游戏按钮，跳过）");
                    }
                } else {
                    String cn = proto.getClass().getName();
                    if (cn.contains("Url") || cn.contains("Switch") || cn.contains("Game") || cn.contains("Buy")) {
                        logd("[按钮] uid=" + u + " text=" + t + "（" + cn.substring(cn.lastIndexOf('$') + 1) + " 类型按钮，不支持回调；请手动用文本目标）");
                    } else {
                        learnTarget(u, t);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 触发源 1b：UI 按钮点击学习（ChatMessageCellDelegate.didPressBotButton） */
    public void onBotButtonCell(Object cell, Object proto) {
        try {
            Object mo = null;
            if (cell != null) { try { mo = call(cell, "getMessageObject", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            if (captureArmed) { handleTapCapture(proto, mo); return; }
            Object did = null;
            if (mo != null) { try { did = call(mo, "getDialogId", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            Object text = buttonText(proto);
            if (did != null && text != null) {
                String t = String.valueOf(text);
                if (!AUTO_LEARN || (AUTO_LEARN_FILTER && !keywordMatched(t))) { logd("[按钮] uid=" + did + " text=" + t + "（不含关键词，自动学习已过滤；可用 捕获/调试台 手动绑定）"); return; }
                long u = ((Number) did).longValue();
                if (isCallbackButton(proto)) {
                    byte[] data = buttonData(proto);
                    if (data != null && data.length > 0) {
                        int msgId = 0;
                        if (mo != null) { try { Object mid = call(mo, "getId", new Class<?>[0], new Object[0]); msgId = mid instanceof Number ? ((Number) mid).intValue() : 0; } catch (Throwable ignored) {} }
                        learnCallback(u, t, data, buttonHash(proto), msgId);
                    } else {
                        logd("[按钮] uid=" + u + " text=" + t + "（无回调 data，可能是链接/游戏按钮，跳过）");
                    }
                } else {
                    String cn = proto.getClass().getName();
                    if (cn.contains("Url") || cn.contains("Switch") || cn.contains("Game") || cn.contains("Buy")) {
                        logd("[按钮] uid=" + u + " text=" + t + "（" + cn.substring(cn.lastIndexOf('$') + 1) + " 类型按钮，不支持回调；请手动用文本目标）");
                    } else {
                        learnTarget(u, t);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 触发源 2：网络活动（学习/已签标记/补签/命令拦截）。返回 true 表示已拦截（不发送）。 */
    public boolean onSendRequest(Object[] args) {
        Object req0 = args != null && args.length > 0 ? args[0] : null;
        if (req0 == null) return false;
        String rn0 = req0.getClass().getName();
        if (!rn0.contains("TL_messages_sendMessage") && !rn0.contains("TL_messages_getBotCallbackAnswer")
                && !rn0.contains("TL_messages_sendWebViewData") && !rn0.contains("TL_messages_sendMedia")
                && !rn0.contains("TL_messages_sendInlineBotResult") && !rn0.contains("TL_messages_sendPhoto")) {
            return false;
        }
        if (inSendReq || notReadyYet()) return false;
        inSendReq = true;
        try {
            syncAccount();
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
                            enqueueTry("TG活动");
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
                                if (AUTO_LEARN && keywordMatched(disp)) {
                                    Object h = getFieldVal(req, "hash");
                                    int mid = 0;
                                    try { Object m2 = getFieldVal(req, "msg_id"); mid = m2 instanceof Number ? ((Number) m2).intValue() : 0; } catch (Throwable ignored) {}
                                    learnCallback(u, disp, d, h instanceof Number ? ((Number) h).longValue() : 0L, mid);
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
                if (msg != null && isJmbCommand(String.valueOf(msg))) {
                    handleCommand(String.valueOf(msg));
                    return true;   // 吞掉管理命令，不发送
                }
                Object uid = null;
                if (peer != null) { try { uid = getFieldVal(peer, "user_id"); } catch (Throwable ignored) {} }
                if (uid != null) {
                    enqueueTry("TG活动");
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
            return false;
        } catch (Throwable t) {
            loge("[活动] 异常: " + t);
            return false;
        } finally {
            inSendReq = false;
        }
    }

    /** 触发源 3.5：bot 回复语义判定（失败撤销 + 退避重试） */
    public void onUpdateProcessed(Object update) {
        if (notReadyYet()) return;
        try {
            if (update == null) return;
            // 1.4.7：processUpdate* 的第一参数是 Updates 容器 / List，先解包再逐条判定（此前整条链路从未触发）
            java.util.List<Object> ups = new ArrayList<>();
            if (update instanceof List) { ups.addAll((List<?>) update); }
            else {
                Object u1 = getFieldValSafe(update, "updates");
                if (u1 instanceof List) ups.addAll((List<?>) u1);
                else {
                    Object u2 = getFieldValSafe(update, "updatesList");
                    if (u2 instanceof List) ups.addAll((List<?>) u2);
                    else ups.add(update);
                }
            }
            for (Object u : ups) {
            String un = u.getClass().getName();
            if (!un.contains("TL_updateNewMessage") && !un.contains("TL_updateNewChannelMessage")) continue;
            Object msg = getFieldVal(u, "message");
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
            // 1.4.5 Live Panel：bot 自己发的带键盘消息 → 缓存当前面板（无需该 bot 已是目标）
            try {
                Object rm = getFieldVal(msg, "reply_markup");
                Object mi = getFieldVal(msg, "id");
                if (rm != null && mi != null) {
                    int mId = mi instanceof Number ? ((Number) mi).intValue() : 0;
                    if (mId > 0) updatePanelLive(peerUid, mId, rm);
                }
            } catch (Throwable ignored) {}
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
                    String[] okWords = {"签到成功","打卡成功","成功签到","已签到","领取成功","发送成功","success","claimed","done"};
                    for (String w : okWords) {
                        if (lower.contains(w)) {
                            for (Map<String, Object> m : targetsSnapshot()) {
                                if (entryDid(m) != did) continue;
                                String id = entryId(m);
                                prefs.edit().putInt(prefix + "retry_" + id, 0)
                                     .remove(prefix + "retry_at_" + id).remove(prefix + "retry_day_" + id).apply();
                            }
                            jlog("【回复判定】" + did + " bot 回复: " + replyText + " → 判定成功");
                            noteResult(true);
                            return;
                        }
                    }
                    String[] dupWords = {"今日已签","已签到","重复","已领取","已参与","already","repeated","again later"};
                    for (String w : dupWords) {
                        if (lower.contains(w)) {
                            for (Map<String, Object> m : targetsSnapshot()) {
                                if (entryDid(m) != did) continue;
                                String id = entryId(m);
                                prefs.edit().putInt(prefix + "retry_" + id, 0)
                                     .remove(prefix + "retry_at_" + id).remove(prefix + "retry_day_" + id).apply();
                            }
                            jlog("【回复判定】" + did + " bot 回复: " + replyText + " → 判定已签过（不必再签）");
                            return;
                        }
                    }
                    String[] failWords = {"签到失败","打卡失败","未签到成功","未成功","活动已结束","已过期","未关注","没有资格","请先关注","请先开始","请重新签到","failed","invalid","rejected","not allowed","try again","not signed"};
                    for (String w : failWords) {
                        if (lower.contains(w)) {
                            // 撤销该 bot 最近 10 分钟内发过签到请求的条目（多指令时只动刚发的那条）
                            for (Map<String, Object> m : targetsSnapshot()) {
                                if (entryDid(m) != did) continue;
                                String id = entryId(m);
                                long sentAt = prefs.getLong(prefix + "sent_at_" + id, 0);
                                if (System.currentTimeMillis() - sentAt > 10L * 60 * 1000) continue;
                                int cur = prefs.getInt(prefix + "retry_" + id, 0);
                                prefs.edit()
                                    .remove(prefix + "last_" + id)
                                    .putInt(prefix + "retry_" + id, cur + 1)
                                    .putLong(prefix + "retry_at_" + id, System.currentTimeMillis() + backoffDelay(Math.max(cur, 1)))
                                    .putString(prefix + "retry_day_" + id, todayStr())
                                    .commit();
                                jlog("【回复判定】" + did + " bot 回复: " + replyText + " → 判定未成功，撤销已签并安排重试 (id=" + id + ")");
                                noteResult(true);
                                return;
                            }
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
            });
            } // end for (update)
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
