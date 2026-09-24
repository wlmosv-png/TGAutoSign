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
    private boolean AUTO_LEARN_NET_CONFIRM = true;   // 网络学习命中后需手动确认，不直接添加（默认开，防误加验证码类 bot）
    private long THROTTLE_MS = 60L * 1000L;
    /** 轮询只负责「账号切换感知」+ 兜底，不需要高频；窗口外进一步拉长。 */
    private long POLL_INTERVAL_MS = 30L * 60L * 1000L;
    private int RETRY_LIMIT = 5;
    private static final String DEF_KEYWORDS = "签到,打卡,checkin,/checkin,claim,领取,签到领,/qd,/qiandao,/sign,/daily,daily,/clock,/kaoqin";
    private String LEARN_KEYWORDS = DEF_KEYWORDS;
    /** 排除规则：一行一条，命中即不学习。支持正则（用 /.../ 包裹），否则按子串匹配。 */
    private String LEARN_EXCLUDE = "";
    /** 排除的 bot ID 集合（整只 bot 不学习、不签到） */
    private final java.util.Set<Long> LEARN_BLOCKED_DIDS = new java.util.HashSet<Long>();

    private final Context appContext;
    private final ClassLoader cl;

    /** 模块自身包名：宿主 context 的 getPackageName() 返回的是宿主，拿不到模块，只能写死 */
    static final String MODULE_PKG = "io.github.wlmosv_png.tgautosign";
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
    private boolean AUTO_LEARN_FILTER = true;
    /** 是否自动判定机器人回复的成败（开=按内置/自定义词表判；关=只记录不判）。 */
    private boolean JUDGE_ENABLED = true;
    /**
     * 宽松模式（用户要求）。
     *   学习：**点什么学什么** —— 跳过排除规则 / 关键词过滤 / 排除的 bot 以外的所有准入判断。
     *   判定：**只要 bot 回了内容就算成功** —— 不再依赖判定词，专治措辞千奇百怪的机器人。
     * 关掉则按原逻辑走（默认关）。
     */
    private boolean LOOSE_MODE = false;
    /** 是否叠加用户自定义判定词（关=只用内置词表）。 */
    private boolean JUDGE_USE_CUSTOM = false;
    private volatile boolean captureArmed = false;       // 捕获模式已武装，等待下一次按钮点击
    private volatile long lastCapDid = 0L;               // 最近一次采样到的会话 uid
    private volatile int  lastCapMid = 0;                // 最近一次采样到的消息 id
    private volatile List<Object[]> lastCapBtns = null;  // 最近一次采样到的整张键盘
    private volatile int captureAcc = -1;                // 武装捕获时的账号（-1=未知）；避免用过期上下文误判账号
    /** UI 层按钮 hook 的实际触发次数（挂载数 ≠ 触发数）。0 表示该宿主点击不走这些方法，
     *  学习和捕获只能依赖网络层兜底——诊断包显示，避免靠猜。 */
    private final java.util.concurrent.atomic.AtomicInteger uiBtnFire = new java.util.concurrent.atomic.AtomicInteger(0);
    void uiBtnFired() { uiBtnFire.incrementAndGet(); }

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
        final String ctx;   // 账号/轮次/链路上下文，空则不带前缀
        LogLine(long t, int l, String m) { this(t, l, m, ""); }
        LogLine(long t, int l, String m, String cx) { ts = t; lv = l; msg = m; ctx = cx == null ? "" : cx; }
        String flat() {
            String tag = lv == LV_DEBUG ? "[调试]" : lv == LV_OK ? "[成功]"
                    : lv == LV_WARN ? "[警告]" : lv == LV_ERR ? "[错误]" : "";
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ts))
                    + (tag.length() == 0 ? "" : " " + tag)
                    + (ctx.isEmpty() ? "" : " [" + ctx + "]")
                    + "  " + msg;
        }
    }
    private final List<LogLine> logBuffer = new ArrayList<LogLine>();
    private final List<String> diskQueue = new ArrayList<String>();
    private long diskFlushAt = 0L;
    private int logFilter = LV_DEBUG;
    private boolean logShowDebug = true;   // 默认含调试（与「全部」芯片一致）
    private String logQuery = "";
    private String logTarget = "";       // 按目标(文本/uid)过滤，空=全部
    private int logLimit = 400;          // 首屏显示条数
    private int logPageStep = 300;       // 每次「加载更多」追加条数
    private int logRendered = 0;         // 当前已渲染条数（游标，用于追加）
    // 日志上下文：账号 / 轮次 / 链路，统一由 jlog 自动带上，便于筛选与归因
    private volatile String ctxAcc = "";
    private volatile int ctxRound = 0;
    private volatile String ctxTrace = "";
    // 静默异常统计：catch(Throwable ignored) 里记一笔，诊断包可见，避免"错误被吞掉却毫无痕迹"
    private final java.util.concurrent.atomic.AtomicInteger swallowedCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.ConcurrentLinkedQueue<String> swallowedRecent = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    private boolean dlgModeLogged = false;   // 对话框实现方式只报告一次
    private int dlgFallbackLogged = 0;       // 自绘卡片日志限次（避免刷屏）
    private LinearLayout logList;
    private android.widget.ScrollView logSv;
    private Button logMoreBtn;
    private TextView logStat;

    private final Random random = new Random();
    private int DAILY_CAP = 60;                  // 每账号每日动作上限（防风控），/jmb 设置里可改
    private volatile long lastPaceAt = 0L;        // 连发节奏锁（jitter 用）
    private String WAKE_CMD = "";
    private String WINDOW = "";
    private boolean TIMER_ENABLED = false;       // 定时签到模式：开=只在窗口内按当日时刻表逐个签 / 关=全天自动补签
    private int THEME_MODE = 0;                  // 主题模式：0=自动（跟宿主/系统）1=始终日间 2=始终夜间
    private boolean NOTIFY_ON = true;            // 签到结果通知
    private boolean NOTIFY_FAIL_ONLY = false;    // 只通知失败
    private boolean NOTIFY_ALL_ACCOUNTS = false; // 汇总时包含其它账号
    private int FAIL_ALERT_DAYS = 3;             // 连续失败多少天开始告警
    private String lastThemeSig = "";
    private boolean MISS_BACK = false;           // 错过补签：窗口内错过的目标，下次触发时随机延迟 1~5 分钟补签（默认关）
    private int MISS_DEADLINE = 23 * 60;         // 补签截止（分钟，默认 23:00）：窗口结束后仍可补到这个点，避免当天错过作废
    private int GAP_MIN = 0;                     // 时刻表最小间隔分钟；0=按目标数自动均分
    private String SORT_MODE = "unsigned";       // 目标列表排序：unsigned=未签置顶 / name=按名称
    private int TITLE_FX = 0;                    // 标题动画：0呼吸 1波浪 2流光 3敲击，每次打开轮换
    private Object listDialog = null;            // 目标列表对话框（自刷新时替换，避免叠层）
    private boolean lastPollInWindow = true;
    private String SIGN = "wlmosv";
    private boolean AUTO_LEARN = true;                        // 按钮/回调学习总开关。默认开：新装用户点一次 bot 按钮就能学会，
                                                              // 这是 README 口号「点一次，签一年」的前提（v1.5.8 前默认 false，
                                                              // 导致新用户点按钮永远学不到；老用户当年手动开过才没暴露）
    private final Set<String> wakeFired = cs(); // 唤醒只触发一次，防循环
    // ── 回调签到状态机（改革）：等面板事件驱动，不再盲等固定延时 ──
    // waitingPanel: entryId -> {did, acc}，前置命令已发出、正在等 bot 刷新面板。
    // 账号必须在这里锁定：面板刷新是异步事件（最长 8 秒兜底），期间用户可能切号，
    // 回调时再读 currentAccount() 就会用新账号去发旧账号的目标（串号 bug）。
    private final java.util.Map<String, long[]> waitingPanel = new java.util.HashMap<String, long[]>();
    // waitingPanelDeadline: entryId -> 截止时间戳(ms)，超时兜底
    private final java.util.Map<String, Long> waitingPanelDeadline = new java.util.HashMap<String, Long>();
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
        /** 最近一条带键盘消息的正文，供排除规则匹配（验证码 bot 的提示语在这里） */
        volatile String lastText = "";
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
                TextView e1 = new TextView(act2); e1.setTextSize(Theme.TS_CAPTION); e1.setTypeface(Theme.text()); e1.setTextColor(Theme.termFaint(act2));
                e1.setText(Lang.tr("$ (暂无日志消息，稍后自动出现)")); body.addView(e1);
                return;
            }
            for (LogLine l : recent) {
                TextView lv2 = new TextView(act2); lv2.setTextSize(Theme.TS_CAPTION); lv2.setTypeface(android.graphics.Typeface.MONOSPACE);
                String lineText = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(l.ts)) + "  " + l.msg;
                if (lineText.length() > 46) lineText = lineText.substring(0, 46) + "...";
                lv2.setText("$ " + lineText);
                lv2.setTextColor(l.lv == LV_ERR ? Theme.termPink(act2) : l.lv == LV_OK ? Theme.termGreen(act2) : l.lv == LV_WARN ? Theme.termAmber(act2) : Theme.termMuted(act2));
                body.addView(lv2);
            }
        } catch (Throwable ignored) {}
    }

    /** 终端风按钮工厂：所有对话框按钮统一走它（等宽 + 暗底 + 霓虹描边） */
    private final java.util.ArrayDeque<Object> dlgStack = new java.util.ArrayDeque<Object>();

    /** 记录已打开的对话框；超过 3 层时关掉最早的那层，避免无限堆叠。 */
    private void pushDlg(Object d) {
        try {
            if (d == null) return;
            dlgStack.addLast(d);
            // 关闭/取消时从栈移除——否则已关闭的占位会累积，把活着的底层界面（主菜单）挤掉
            if (d instanceof android.app.Dialog) {
                final Object dd = d;
                try {
                    ((android.app.Dialog) d).setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                        @Override public void onDismiss(android.content.DialogInterface di) {
                            try { dlgStack.remove(dd); } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            }
            // 兜底：活着的对话框超过 5 层才关最早的（正常逐层关闭不会触发）
            while (dlgStack.size() > 5) {
                Object old = dlgStack.pollFirst();
                try { call(old, "dismiss", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ── 矢量图标（定义见 Icons.java）─────────────────────────────────────
    /** 图标视图；名字未定义时返回 null，调用方回退到文字/emoji */
    private android.widget.ImageView iconView(Context c, String name, float sizeDp, int color) {
        android.graphics.drawable.Drawable d = Icons.d(c, name, sizeDp, color);
        if (d == null) return null;
        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setImageDrawable(d);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** 按动作名推断图标语义色（签到=绿、删除清空=品红、其余=青） */
    private int iconColorOf(Context c, String kw) {
        if (kw == null) return Theme.termCyan(c);
        String k = kw.toLowerCase(java.util.Locale.US);
        if (k.contains("sign") || k.contains("primary") || k.contains("ok")) return Theme.termGreen(c);
        if (k.contains("del") || k.contains("clear") || k.contains("remove") || k.contains("danger")) return Theme.termPink(c);
        if (k.contains("warn")) return Theme.termAmber(c);
        return Theme.termCyan(c);
    }

    /** 给标签 TextView 贴一个前置小图标（默认 13dp，跟随文字基线） */
    private void leadIcon(Context c, TextView t, String name) {
        leadIcon(c, t, name, Theme.termCyan(c));
    }

    private void leadIcon(Context c, TextView t, String name, int color) {
        if (t == null) return;
        android.graphics.drawable.Drawable d = Icons.d(c, name, 13f, color);
        if (d == null) return;
        int sz = Theme.dp(c, 13);
        d.setBounds(0, 0, sz, sz);
        t.setCompoundDrawables(d, null, null, null);
        t.setCompoundDrawablePadding(Theme.dp(c, 5));
    }

    /** 给按钮左侧贴一个矢量图标 */
    private Button withIcon(Context c, Button b, String name) {
        return withIcon(c, b, name, Theme.termCyan(c));
    }

    private Button withIcon(Context c, Button b, String name, int color) {
        if (b == null) return b;
        android.graphics.drawable.Drawable d = Icons.d(c, name, 14f, color);
        if (d == null) return b;
        int sz = Theme.dp(c, 14);
        d.setBounds(0, 0, sz, sz);
        b.setCompoundDrawables(d, null, null, null);
        b.setCompoundDrawablePadding(Theme.dp(c, 6));
        return b;
    }

    /** 图标 + 纯文字（替代原来 "▼ 加载更多" 这类字符画按钮） */
    private Button withIconText(Context c, Button b, String name, String text) {
        if (b == null) return b;
        b.setText(Lang.tr(text));
        return withIcon(c, b, name);
    }

    private Button mkBtn(Context c) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setTextSize(Theme.TS_BODY);
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
        } catch (Throwable t) { toast(Lang.tf("打开作者主页失败: {0}", t)); }
    }

    /** 加入 TG 交流群（私有邀请链接，ACTION_VIEW 让系统路由到 Telegram） */
    private static final String GROUP_LINK = "https://t.me/+V2Oyu8pSubs4ZjE0";
    // 分享文案：写清楚卖点 + 官方下载入口，便于他人直接安装
    private static final String SHARE_TEXT =
            "推荐一个 Telegram 自动签到模块：TGAutoSign\n"
            + "\n"
            + "· 在 bot 会话里点一次签到按钮就学会，之后每天自动签\n"
            + "\n"
            + "· 回调按钮 / 文本指令 / 群签到都支持\n"
            + "\n"
            + "· 多账号隔离、签到窗口、断网补签、限流退避\n"
            + "\n"
            + "· 管理面板就在 Telegram 内，发 /jmb 打开\n"
            + "\n"
            + "需 LSPosed 环境，下载：\n"
            + "https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases/latest";

    // 分享：复制文案 / 发到收藏夹 / 调系统分享
    private void showShare(final Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(4), dp(6), dp(4));
        TextView tip = new TextView(act);
        tip.setTextSize(Theme.TS_SECOND);
        tip.setTextColor(Theme.termMuted(act));
        tip.setTypeface(Theme.mono());
        tip.setText(Lang.tr("分享给需要的人，文案已写好（含下载链接）"));
        box.addView(tip);

        TextView preview = new TextView(act);
        preview.setTextSize(Theme.TS_CAPTION);
        preview.setTextColor(Theme.termTxt(act));
        preview.setTypeface(Theme.mono());
        preview.setText(SHARE_TEXT);
        preview.setPadding(dp(10), dp(10), dp(10), dp(10));
        preview.setBackground(termBorder(act, Theme.termCardInput(act),
                Theme.withAlpha(Theme.termCyan(act), 0x33)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(8);
        box.addView(preview, plp);

        Button copyB = mkBtn(act);
        withIconText(act, copyB, "copy", "复制文案");
        copyB.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("TGAutoSign", SHARE_TEXT));
                toast("已复制，粘到任意聊天即可");
            } catch (Throwable t) { toast(Lang.tf("复制失败: {0}", t)); }
        } });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(8);
        box.addView(copyB, blp);

        Button sysB = mkBtn(act);
        withIconText(act, sysB, "upload", "系统分享（选应用）");
        sysB.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(android.content.Intent.EXTRA_TEXT, SHARE_TEXT);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(android.content.Intent.createChooser(i, Lang.tr("分享 TGAutoSign")));
            } catch (Throwable t) { toast(Lang.tf("无法打开分享: {0}", t)); }
        } });
        box.addView(sysB, blp);

        showDialog(act, "分享本模块", box, "关闭");
    }

    private void openGroup() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(GROUP_LINK));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(i);
            jlog("[界面] 打开交流群链接");
        } catch (Throwable t) { toast(Lang.tf("打开群链接失败，请确认已装 Telegram: {0}", t.getMessage())); }
    }

    /** 状态徽章：开=绿实心点，关=灰空心点（主界面状态卡片用） */
    private TextView badge(final Activity act, String label, boolean on) {
        TextView b = new TextView(act);
        b.setText((on ? "[ON]  " : "[OFF] ") + Lang.trShort(label));
        if (Lang.isEnglish()) { b.setSingleLine(false); b.setMaxLines(2); b.setEllipsize(null); }
        b.setTextSize(Theme.TS_CAPTION);
        b.setTypeface(android.graphics.Typeface.MONOSPACE);
        b.setTextColor(on ? Theme.termGreen(act) : Theme.termMuted(act));
        if (Lang.isEnglish()) b.setPadding(dp(6), dp(5), dp(6), dp(5));
        else b.setPadding(dp(10), dp(5), dp(10), dp(5));
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
                // v1.5.7：分母用活跃目标（排除冻结/排除的 bot）
                String pfx2 = accountPrefix(i);
                int done = activeSignedCount(pfx2, l, today);
                int actv = activeTargetCount(pfx2, l);
                if (i > 0) sb.append('\n');
                sb.append(Lang.tf("{0}：目标 {1} · 已签 {2}/{3}", accountLabel(i), l.size(), done, actv));
                if (l.isEmpty()) sb.append(Lang.tr("（还没有目标，去该账号学一个）"));
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    /** 把当前账号的目标复制给其它账号（只复制目标，不带已签状态） */
    /** 账号概览数据：目标数 / 今日已签 / 是否有定时计划。 */
    private int[] accountStats(int acc) {
        int total = 0, signed = 0, timer = 0;
        try {
            String prefix = accountPrefix(acc);
            List<Map<String, Object>> l = new ArrayList<>();
            loadTargetsInto(prefix, l);
            total = l.size();
            String today = todayStr();
            for (Map<String, Object> m : l) {
                try {
                    if (today.equals(prefs.getString(kLast(prefix, entryId(m)), ""))) signed++;
                } catch (Throwable ignored) {}
            }
            if (prefs.getBoolean(prefix + "cfg_timer", prefs.getBoolean("jmb_timer", false))) timer = 1;
        } catch (Throwable ignored) {}
        return new int[]{total, signed, timer};
    }

    /** 账号是否参与自动签到（默认启用）。 */
    private boolean isAccountEnabled(int acc) {
        try { return prefs.getBoolean("acc" + acc + "_enabled", true); } catch (Throwable t) { return true; }
    }

    private void setAccountEnabled(int acc, boolean on) {
        try { prefs.edit().putBoolean("acc" + acc + "_enabled", on).apply(); } catch (Throwable ignored) {}
    }

    /** 账号一览：所有已激活账号的概况，当前账号高亮。 */
    private void showAccountOverview(final Activity act) {
        try {
            final int cur = currentAccount();
            final int count = Math.max(1, activatedAccounts());
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);

            TextView head = new TextView(act);
            head.setTextSize(Theme.TS_CAPTION); head.setTextColor(Theme.termMuted(act));
            head.setTypeface(android.graphics.Typeface.MONOSPACE);
            head.setText(count <= 1 ? Lang.tr("当前只有 1 个登录账号") : Lang.tf("共 {0} 个登录账号（切换 Telegram 账号即切换目标集）", count));
            head.setPadding(dp(4), 0, dp(4), dp(8));
            box.addView(head);

            for (int i = 0; i < count; i++) {
                final int acc = i;
                int[] st = accountStats(i);
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.VERTICAL);
                boolean isCur = (i == cur);
                int accent = isCur ? Theme.termGreen(act) : Theme.termCyan(act);
                row.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(accent, isCur ? 0x4D : 0x26)));
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.setMargins(0, dp(3), 0, dp(3));
                row.setLayoutParams(rlp);

                LinearLayout line1 = new LinearLayout(act); line1.setOrientation(LinearLayout.HORIZONTAL); line1.setGravity(Gravity.CENTER_VERTICAL);
                TextView nm = new TextView(act); nm.setTextSize(Theme.TS_SUBTITLE); nm.setTextColor(Theme.termTxt(act));
                nm.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                nm.setText((isCur ? "● " : "○ ") + accountLabel(i) + (isCur ? Lang.tr("（当前）") : ""));
                line1.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
                if (st[2] == 1) {
                    TextView tchip = badgeChip(act, " 定时", Theme.termCyan(act), false);
                    line1.addView(tchip);
                }
                if (!isAccountEnabled(i)) {
                    TextView dchip = badgeChip(act, " 已停用", Theme.termMuted(act), false);
                    line1.addView(dchip);
                }
                row.addView(line1);

                TextView line2 = new TextView(act); line2.setTextSize(Theme.TS_CAPTION); line2.setTextColor(Theme.termMuted(act));
                line2.setTypeface(android.graphics.Typeface.MONOSPACE);
                if (st[0] == 0) line2.setText(Lang.tr("（还没有目标）"));
                else line2.setText(Lang.tf("目标 {0} · 今日已签 {1} · 待签 {2}", st[0], st[1], st[0] - st[1]));
                line2.setPadding(dp(1), dp(2), 0, 0);
                row.addView(line2);

                if (count > 1) {
                    final boolean en = isAccountEnabled(i);
                    Button tg = mkBtn(act);
                    withIconText(act, tg, en ? "pause" : "check", en ? "停用该账号（不参与自动签到）" : "启用该账号");
                    tg.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                        setAccountEnabled(acc, !en);
                        toast(en ? Lang.tf("已停用 {0}", accountLabel(acc)) : Lang.tf("已启用 {0}", accountLabel(acc)));
                        showAccountOverview(act);
                    } });
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                    clp.topMargin = dp(6);
                    row.addView(tg, clp);
                }
                if (!isCur && st[0] == 0) {
                    Button cp = mkBtn(act); withIconText(act, cp, "copy", "复制本账号目标到该账号");
                    cp.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                        List<Map<String, Object>> mine = targetsSnapshot();
                        if (mine.isEmpty()) { toast("当前账号还没有目标"); return; }
                        copyTargetsTo(acc, currentAccount(), mine);
                        toast(Lang.tf("已复制到 {0}", accountLabel(acc)));
                        showAccountOverview(act);
                    } });
                    LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(-1, -2);
                    clp2.topMargin = dp(6);
                    row.addView(cp, clp2);
                }
                box.addView(row);
            }

            if (count > 1) {
                TextView tip = new TextView(act);
                tip.setTextSize(Theme.TS_CAPTION); tip.setTextColor(Theme.termFaint(act)); tip.setTypeface(Theme.text());
                tip.setText(Lang.tr("「签全部账号」会依次签每个账号；定时签到跟随当前账号。切换 Telegram 账号后，本面板显示的目标集会随之切换。"));
                tip.setPadding(dp(4), dp(8), dp(4), dp(2));
                box.addView(tip);
            }

            Button all = mkBtnPrimary(act); withIconText(act, all, "globe", "签全部账号");
            all.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ signAllAccounts(); toast("已命令全账号签到，结果见日志"); } });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
            alp.topMargin = dp(8);
            box.addView(all, alp);

            showDialog(act, Lang.tf("账号一览（{0}）", count), box, "关闭");
        } catch (Throwable t) { toast(Lang.tf("打开失败: {0}", t)); }
    }

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
        info.setTextSize(Theme.TS_BODY);
        info.setTextColor(Theme.termTxt(act));
        info.setText(Lang.tf("把 {0} 的 {1} 个目标复制到其它账号。\n只复制目标本身，不带「今天已签」和重试记录。", accountLabel(cur), mine.size()));
        box.addView(info);
        for (int i = 0; i < n; i++) {
            if (i == cur) continue;
            final int target = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Theme.dp(act, 4), Theme.dp(act, 12), Theme.dp(act, 4), Theme.dp(act, 12));
            TextView t = new TextView(act);
            t.setTextSize(Theme.TS_SUBTITLE);
            t.setTextColor(Theme.termTxt(act));
            t.setText(Lang.tf("→ {0}（现有 {1} 个）", accountLabel(i), acctTargetCount(i)));
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
        toast(added == 0 ? Lang.tr("没有需要复制的新目标（可能早就复制过了）")
                : Lang.tf("已复制到 {0}：新增 {1} 个", accountLabel(account), added));
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
            if (prefs.contains(kExclude())) LEARN_EXCLUDE = prefs.getString(kExclude(), "");
            LEARN_BLOCKED_DIDS.clear();
            try {
                String bd = prefs.getString(kBlockedDids(), "");
                if (bd != null && bd.trim().length() > 0) {
                    for (String one : bd.split(",")) {
                        String t = one.trim();
                        if (t.length() == 0) continue;
                        try { LEARN_BLOCKED_DIDS.add(Long.parseLong(t)); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            if (prefs.contains("jmb_retry")) RETRY_LIMIT = prefs.getInt("jmb_retry", RETRY_LIMIT);
            WAKE_CMD = prefs.getString("jmb_wake_cmd", "");
            WINDOW = cfgStr("window", "");
            TIMER_ENABLED = cfgBool("timer", false);
            GAP_MIN = cfgInt("gap", 0);
            MISS_BACK = cfgBool("missback", false);
            if (prefs.contains("jmb_theme")) THEME_MODE = prefs.getInt("jmb_theme", 0);
            if (prefs.contains("jmb_lang")) { try { io.github.wlmosv_png.tgautosign.Lang.MODE = prefs.getInt("jmb_lang", 0); } catch (Throwable ignored) {} }
            if (prefs.contains("jmb_notify")) NOTIFY_ON = prefs.getBoolean("jmb_notify", true);
            if (prefs.contains("jmb_notify_fail_only")) NOTIFY_FAIL_ONLY = prefs.getBoolean("jmb_notify_fail_only", false);
            if (prefs.contains("jmb_notify_all_acc")) NOTIFY_ALL_ACCOUNTS = prefs.getBoolean("jmb_notify_all_acc", false);
            Theme.mode = THEME_MODE;
            MISS_DEADLINE = cfgInt("missdead", 23 * 60);
            if (prefs.contains("jmb_sort")) SORT_MODE = prefs.getString("jmb_sort", "unsigned");
            if (prefs.contains("jmb_fx")) TITLE_FX = prefs.getInt("jmb_fx", 0);
            if (bootReadyAt == 0L) bootReadyAt = System.currentTimeMillis() + 30000L;
            AUTO_LEARN = prefs.getBoolean("jmb_autolearn", AUTO_LEARN);
            AUTO_LEARN_FILTER = prefs.getBoolean("jmb_alfilter", AUTO_LEARN_FILTER);
            // jmb_judge 是 v1.5.8 新键，默认开。**尊重用户显式关闭**：
            // 只有键存在时才用存储值，读不到（老用户/未设置）用默认 true。
            JUDGE_ENABLED = prefs.getBoolean("jmb_judge", true);
            JUDGE_USE_CUSTOM = prefs.getBoolean("jmb_judge_custom", JUDGE_USE_CUSTOM);
            LOOSE_MODE = prefs.getBoolean("jmb_loose", LOOSE_MODE);
            AUTO_LEARN_NET = prefs.getBoolean("jmb_autolearn_net", AUTO_LEARN_NET);
            AUTO_LEARN_NET_CONFIRM = prefs.getBoolean("jmb_autolearn_net_confirm", AUTO_LEARN_NET_CONFIRM);
        } catch (Throwable ignored) {}
        try { lastAccount = currentAccount(); } catch (Throwable ignored) {}
        try { migrateAccountConfigs(); } catch (Throwable ignored) {}   // 全局默认 → 各账号（老用户不丢设置）
        registerNetworkReceiver();
        registerActivityListener();
        mainHandler.postDelayed(() -> { try { jlog("=== 启动补签 ==="); timerHook("启动"); } catch (Throwable ignored) {} }, 10000L);
        schedulePoll();
        scheduleTickLoop();   // 心跳常驻：正常签到与补签都靠它兜底
        mainHandler.postDelayed(new Runnable(){ @Override public void run(){ try { syncNow(); } catch (Throwable ignored) {} } }, 12000L);
        jlogForce("=== TGAutoSign v" + UpdateChecker.VERSION_NAME + " (" + UpdateChecker.VERSION_CODE + ") 已加载 ===");
        String _hostPkg158 = safePkg();
        jlogForce("宿主: " + hostLabel(_hostPkg158) + " [" + _hostPkg158 + "] " + hostVersion(_hostPkg158)
            + " · " + hostAbi() + " · Android " + android.os.Build.VERSION.RELEASE
            + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");
        jlogForce("注入: " + injectHow(_hostPkg158) + " · 模块 " + MODULE_PKG);
        jlogForce("账号: " + currentAccount() + " 目标: " + targetsSnapshot().size()
            + " 按钮学习: " + (AUTO_LEARN ? "开" : "关") + " 网络学习: " + (AUTO_LEARN_NET ? "开" : "关"));
        jlogForce("使用: 在任意聊天输入 /jmb 打开管理界面");
        logFlush();   // 强制落盘（jlogForce 已绕过采样）




        checkUpdateSilently();
            mainHandler.postDelayed(new Runnable() { @Override public void run() { bootToast(); } }, 15000L);
        mainHandler.postDelayed(new Runnable(){ public void run(){ try{ if(!prefs.getBoolean("jmb_tut_seen",false)){ prefs.edit().putBoolean("jmb_tut_seen",true).apply(); toast("输入 /jmb 打开管理面板 · /help 查看教程"); } }catch(Throwable ignored){} } }, 4000L);
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
                    toast(Lang.tf("TGAutoSign 有新版本 v{0}：发 /jmb → 🔄 检查更新", r.version));
                } else {
                    logd("检查更新：已是最新 v" + UpdateChecker.VERSION_NAME);
                }
            });
        } catch (Throwable t) { jlog("检查更新异常(忽略): " + t); }
    }

    // ---------------- 工具 ----------------
    private String todayStr() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    /** 宿主友好名：已知客户端给短名，未知 fork 显示包名末段 */
    private String hostLabel(String pkg) {
        if (pkg == null || pkg.length() == 0) return "unknown";
        boolean en = Lang.isEnglish();
        if ("org.telegram.messenger".equals(pkg)) return en ? "Official" : "官方版";
        if ("org.telegram.messenger.web".equals(pkg)) return en ? "Official (web)" : "官方版(官网直连)";
        if ("fork.risin42.nagramx".equals(pkg)) return "Nagram XF";
        if ("com.exteraless.app".equals(pkg)) return "ExteraLess";
        if (pkg.startsWith("nu.gpu.nagram") || "xyz.nextalone.nagram".equals(pkg)) return "Nagram";
        int i = pkg.lastIndexOf('.');
        return i > 0 ? pkg.substring(i + 1) : pkg;
    }

    /** 宿主版本名 + 版本码（失败返回 ?，不影响主流程） */
    private String hostVersion(String pkg) {
        try {
            android.content.pm.PackageInfo pi = appContext.getPackageManager().getPackageInfo(pkg, 0);
            long vc;
            try { vc = pi.getLongVersionCode(); }
            catch (Throwable t) { vc = pi.versionCode; }
            return pi.versionName + " (" + vc + ")";
        } catch (Throwable t) { return "?"; }
    }

    /** 主 ABI（32/64 位适配排查用） */
    private String hostAbi() {
        try {
            String[] a = android.os.Build.SUPPORTED_ABIS;
            return (a != null && a.length > 0) ? a[0] : "?";
        } catch (Throwable t) { return "?"; }
    }

    /** 注入方式（英文模式走英文，避免英文用户看到中文） */
    private String injectHow(String pkg) {
        String d;
        try { d = Hosts.describe(pkg, cl); } catch (Throwable t) { d = "?"; }
        if (!Lang.isEnglish()) return d;
        if ("已知客户端".equals(d)) return "known client";
        if ("不支持".equals(d)) return "unsupported";
        return "capability probe (Telegram-Android fork)";
    }

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

    /** currentAccount() 读到的原始值（未经钳制），仅用于诊断包展示。 */
    private volatile int lastRawAccount = -1;

    /**
     * 当前账号索引。
     *
     * 历史教训（v1.5.8 → v1.5.9 回退）：
     * v1.5.8 曾在此处把越界值「钳到 0」，出发点是不让界面因越界而读到空分区。
     * 但实测证明这个假设是错的 —— 宿主 UserConfig.selectedAccount 是**静态字段**，
     * 在部分客户端（如 fork.risin42.nagramx，登录 4 个账号）会读到 7、9 这类
     * 大于「已登录数」的值，而它们**是合法的账号索引**（账号数据就存在 acc9_ 里）。
     * 钳到 0 的后果是把用户重定向到**另一个账号**的分区：目标表、已签记录、
     * 重试退避全部读错，用户侧表现为「账号1的配置变成了账号2的」，
     * 比「读到空分区」严重得多。
     *
     * 现在的策略：
     *  - 正常范围内：原样返回（与 v1.5.7 一致）。
     *  - 负值：不可能合法，退回 0 并记日志（不能返回负数，否则 accountPrefix()
     *    会拼出 "acc-1_" 这种垃圾分区）。
     *  - 越界但为正：仍原样返回（宁可按用户当前账号操作，也不改到别的账号头上），
     *    只记一条日志便于追查宿主行为。
     */
    private int currentAccount() {
        try {
            Object v = getFieldVal(null, classEx("org.telegram.messenger.UserConfig"), "selectedAccount");
            int c = ((Number) v).intValue();
            lastRawAccount = c;
            if (c < 0) {
                // 负值不可能是合法索引。不能返回 -1：accountPrefix() 会拼出 "acc-1_"
                // 这种垃圾分区（有 57 处调用点）。退回 0 并记日志。
                warnAccountOutOfRange(c, activatedAccounts());
                return 0;
            }
            return c;
        } catch (Throwable t) { return 0; }
    }

    /** 越界告警去重：同一组值只记一次，避免刷屏。 */
    private volatile String lastAccRangeSig = "";
    private void warnAccountOutOfRange(int raw, int total) {
        try {
            String sig = raw + "/" + total;
            if (sig.equals(lastAccRangeSig)) return;
            lastAccRangeSig = sig;
            logw("[账号] selectedAccount=" + raw + "（已登录 " + total + " 个）"
                 + (raw < 0 ? "，值非法，已退回 0（不再改写为其它账号）"
                            : "，大于已登录数 —— 但仍是合法索引，按原值使用（不再改到别的账号）"));
        } catch (Throwable ignored) {}
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

        /**
         * 当天所有目标是否都已有结论，用于决定要不要发"今日汇总"通知。
         * 有结论 = 今日已签 / 今日已放弃(重试拉满或退避) / 被 snooze 到明天。
         * 只要还有一个"待签"就返回 false，等后续轮次或补签把它推进到结论。
         */
        private boolean allSettledToday(int acc) {
            try {
                String prefix = accountPrefix(acc);
                List<Map<String, Object>> list = new ArrayList<>();
                loadTargetsInto(prefix, list);
                if (list.isEmpty()) return false;
                String today = todayStr();
                boolean pending = false;
                for (Map<String, Object> m : list) {
                    String id = entryId(m);
                    if (today.equals(prefs.getString(kLast(prefix, id), ""))) continue;   // 已签
                    if (prefs.getInt(kRetry(prefix, id), 0) >= RETRY_LIMIT) continue;      // 今日已放弃
                    if (isSnoozed(prefix, id)) continue;                                   // 已顺延
                    pending = true;
                    break;
                }
                if (!pending) return true;
                // 兜底：窗口/补签截止已经过去 1 小时以上还有目标没结论，就不再等，
                // 否则目标卡在"待签"时通知会永远发不出去。
                // 窗口跨天时（如 20:00-23:00）endMin 仍是同日的钟点，用「跨天判定」处理。
                java.util.Calendar c = java.util.Calendar.getInstance();
                int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
                int[] r = windowRange();
                int endMin = r != null ? Math.max(r[1], MISS_BACK ? MISS_DEADLINE : r[1]) : MISS_DEADLINE;
                if (endMin >= nowMin) return false;                       // 还没到点，继续等
                return (nowMin - endMin) >= 60;                           // 过点 1 小时才放行
            } catch (Throwable t) { return false; }
        }

        private void flushRoundToast() {
            int ok, er;
            synchronized (toastAt) {
                ok = roundOkN; er = roundErrN;
                roundOkN = 0; roundErrN = 0; roundScheduled = false;
            }
            if (ok == 0 && er == 0) return;
            if (er == 0) toastOnce("round|" + todayStr(), Lang.tf("✅ 签到完成 {0} 个", ok));
            else toastOnce("round|" + todayStr(), Lang.tf("签到完成 {0} 个，{1} 个没成功（/jmb → 📄 运行日志 里有原因）", ok, er));
            // 通知摘要：每账号每天最多一条，且必须等"当天所有目标都有结论"才发。
            // 旧写法只看单轮结果 —— 时刻表间隔 4 分钟，第 1 个刚签完就发"今日 1/9"，
            // 用户以为流程结束了。现在有待签/待重试就继续等，由下一轮回来再判断。
            try {
                if (NOTIFY_ON) {
                    int accN = currentAccount();
                    if (!allSettledToday(accN)) {
                        jlog("[通知] 还有目标未出结果，暂不发汇总");
                    } else {
                        String nk = "jmb_notified_" + accN;
                        String today = todayStr();
                        if (!today.equals(prefs.getString(nk, ""))) {
                            prefs.edit().putString(nk, today).apply();
                            mainHandler.postDelayed(new Runnable() { @Override public void run() {
                                try { notifySummary(); } catch (Throwable ignored) {}
                            } }, 3000L);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    void toast(String msg) {
        final String out = Lang.tr(msg);
        try {
            mainHandler.post(() -> {
                try { Toast.makeText(appContext, String.valueOf(out), Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    // ==================== 签到结果通知 ====================
    // 用宿主自己的能力给「收藏夹（Saved Messages）」静默发一条摘要，无系统通知权限依赖。
    // 找不到收藏夹时不报错，仅记日志（通知失败不能影响签到）。

    /** 当前账号的收藏夹 peer（Saved Messages）。 */
    private Object savedMessagesPeer(int account) {
        try {
            Object mc = getMessagesController(account);
            if (mc == null) return null;
            Object me = null;
            try {
                Object uc = staticInvoke(classEx("org.telegram.messenger.UserConfig"),
                        "getInstance", new Class<?>[]{int.class}, new Object[]{account});
                if (uc != null) me = getFieldValSafe(uc, "currentUser");
            } catch (Throwable ignored) {}
            if (me == null) {
                try { me = invoke(mc, "getUser", new Class<?>[]{Long.class},
                        new Object[]{Long.valueOf(accountSelfId(account))}); } catch (Throwable ignored) {}
            }
            if (me == null) return null;
            return staticInvoke(classEx("org.telegram.messenger.MessagesController"),
                    "getInputPeer", new Class<?>[]{classEx("org.telegram.tgnet.TLObject")}, new Object[]{me});
        } catch (Throwable t) { return null; }
    }

    private long accountSelfId(int account) {
        try {
            Object uc = staticInvoke(classEx("org.telegram.messenger.UserConfig"),
                    "getInstance", new Class<?>[]{int.class}, new Object[]{account});
            if (uc != null) {
                Object v = getFieldValSafe(uc, "clientUserId");
                if (v instanceof Number) return ((Number) v).longValue();
                Object u = getFieldValSafe(uc, "currentUser");
                if (u != null) {
                    Object id = getFieldValSafe(u, "id");
                    if (id instanceof Number) return ((Number) id).longValue();
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    /** 往收藏夹发一条纯文本消息。 */
    private void sendSavedMessage(String text, int account) {
        try {
            Object peer = savedMessagesPeer(account);
            if (peer == null) { logd("[通知] 收藏夹 peer 获取失败，跳过通知"); return; }
            sendText(peer, account, text);
            logd("[通知] 已发送到收藏夹");
        } catch (Throwable t) { logException("[通知] 发送", t); }
    }

    /**
     * 记录连续失败：同一天同一目标只计一次，累加"连续失败天数"。
     * 达到 3 天时告警一次（写成运行日志 + 收藏夹通知），并标记已告警避免每天刷屏。
     */
    private void noteFailStreak(String prefix, String id, long did, String errText) {
        try {
            String today = todayStr();
            String lastFail = prefs.getString(prefix + "fail_date_" + id, "");
            if (today.equals(lastFail)) return;   // 今天已计过
            int streak;
            String y = prefs.getString(prefix + "fail_laststamp_" + id, "");
            int n = prefs.getInt(prefix + "fail_streak_" + id, 0);
            streak = isYesterday(y) ? n + 1 : 1;
            prefs.edit()
                .putString(prefix + "fail_date_" + id, today)
                .putString(prefix + "fail_laststamp_" + id, today)
                .putInt(prefix + "fail_streak_" + id, streak)
                .apply();
            if (streak < FAIL_ALERT_DAYS) return;
            String ak = prefix + "fail_alert_" + id;
            if (today.equals(prefs.getString(ak, ""))) return;
            prefs.edit().putString(ak, today).apply();

            String nm = targetTitle(did);   // 群/频道会显示群名，bot 显示 bot 名
            String tip = Lang.tf("⚠️ {0} 已连续 {1} 天签到失败\n原因: {2}\n账号: {3}",
                    nm, streak,
                    (errText == null || errText.isEmpty() ? Lang.tr("未知") : errText),
                    accountLabel(currentAccount()));
            logw("[告警] " + nm + " 连续失败 " + streak + " 天（" + errText + "）");
            if (NOTIFY_ON) sendSavedMessage(tip, currentAccount());
            else toastOnce("fail|" + id, Lang.tf("⚠️ {0} 连续 {1} 天签到失败", nm, streak));
        } catch (Throwable ignored) {}
    }

    private void clearFailStreak(String prefix, String id) {
        try {
            if (prefs.getInt(prefix + "fail_streak_" + id, 0) == 0
                    && prefs.getString(prefix + "fail_date_" + id, "").isEmpty()) return;
            prefs.edit()
                .remove(prefix + "fail_date_" + id)
                .remove(prefix + "fail_laststamp_" + id)
                .remove(prefix + "fail_streak_" + id)
                .remove(prefix + "fail_alert_" + id)
                .apply();
        } catch (Throwable ignored) {}
    }

    /** 组装并发送今日签到摘要。 */
    private void notifySummary() {
        try {
            if (!NOTIFY_ON) return;
            int acc = currentAccount();
            String prefix = accountPrefix(acc);
            List<Map<String, Object>> list = new ArrayList<>();
            loadTargetsInto(prefix, list);
            if (list.isEmpty()) return;
            int total = list.size(), done = 0;
            List<String> fails = new ArrayList<>();
            String today = todayStr();
            for (Map<String, Object> m : list) {
                String id = entryId(m);
                if (today.equals(prefs.getString(kLast(prefix, id), ""))) { done++; continue; }
                int rt = prefs.getInt(kRetry(prefix, id), 0);
                String nm = entryDisplayName(m);
                if (rt > 0) fails.add(Lang.tf("{0}（失败 {1} 次）", nm, rt));
                else if (!isSnoozed(prefix, id)) fails.add(Lang.tf("{0}（待签）", nm));
            }
            if (NOTIFY_FAIL_ONLY && fails.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            if (fails.isEmpty()) {
                sb.append(Lang.tf("✅ TGAutoSign 今日签到完成 {0}", done));
            } else {
                sb.append(Lang.tf("⚠️ TGAutoSign 今日 {0}/{1} 已签，{2} 个未完成", done, total, fails.size()));
                sb.append("\n");
                int n = 0;
                for (String f : fails) { if (n++ >= 8) { sb.append("\n…"); break; } sb.append("\n· ").append(f); }
            }
            sb.append("\n").append(nowHM()).append(" · ").append(accountLabel(acc));
            sendSavedMessage(sb.toString(), acc);
        } catch (Throwable t) { logException("[通知] 汇总", t); }
    }

    void jlog(String msg) { jlog(guessLevel(msg), msg); }
        void logd(String msg) { jlog(LV_DEBUG, msg); }
        /** 强制落盘：绕过 INFO 采样（采样 5 秒窗口会把同批启动行丢掉），启动/诊断信息必须用它 */
        void jlogForce(String msg) {
            try {
                long now = System.currentTimeMillis();
                String cx = ctxTag();
                LogLine l = new LogLine(now, LV_INFO, String.valueOf(msg), cx);
                Log.i(TAG, msg);
                synchronized (logBuffer) {
                    logBuffer.add(l);
                    while (logBuffer.size() > 1200) logBuffer.remove(0);
                    diskQueue.add(l.flat());
                }
            } catch (Throwable ignored) {}
        }

        /** 立即落盘：jlog 有采样（INFO 连续输出会被丢），启动信息必须完整进文件 */
        void logFlush() {
            try {
                final List<String> batch;
                synchronized (logBuffer) {
                    if (diskQueue.isEmpty()) return;
                    batch = new ArrayList<String>(diskQueue);
                    diskQueue.clear();
                    diskFlushAt = System.currentTimeMillis();
                }
                final java.io.File dir = logDir();
                final String day = dayStr();
                LOG_IO.execute(new Runnable() { @Override public void run() { appendDisk(dir, day, batch); } });
            } catch (Throwable ignored) {}
        }

        void logs(String msg) { jlog(LV_OK, msg); }
        void logw(String msg) { jlog(LV_WARN, msg); }
        void loge(String msg) { jlog(LV_ERR, msg); }

        void jlog(int lv, String msg) {
            try {
                long now = System.currentTimeMillis();
                String cx = ctxTag();
                LogLine l = new LogLine(now, lv, String.valueOf(msg), cx);
                Log.i(TAG, (cx.isEmpty() ? "" : "[" + cx + "] ") + msg);
                synchronized (logBuffer) {
                    logBuffer.add(l);
                    while (logBuffer.size() > 1200) logBuffer.remove(0);
                    if (lv >= LV_WARN || diskQueue.size() >= 20 || now - diskFlushAt > 5000L) {
                        diskQueue.add(l.flat());
                    }
                    if (diskQueue.size() >= 20 || now - diskFlushAt > 5000L) {
                        final List<String> batch = new ArrayList<String>(diskQueue);
                        diskQueue.clear();
                        diskFlushAt = now;
                        final java.io.File dir = logDir();
                        final String day = dayStr();
                        LOG_IO.execute(new Runnable() { @Override public void run() { appendDisk(dir, day, batch); } });
                    }
                }
            } catch (Throwable ignored) {}
        }

        /** 当前上下文串：账号|轮次|链路（没有的不带）。 */
        private String ctxTag() {
            try {
                StringBuilder sb = new StringBuilder();
                if (ctxAcc != null && !ctxAcc.isEmpty()) sb.append(ctxAcc);
                if (ctxRound > 0) { if (sb.length() > 0) sb.append('|'); sb.append('#').append(ctxRound); }
                if (ctxTrace != null && !ctxTrace.isEmpty()) { if (sb.length() > 0) sb.append('|'); sb.append(ctxTrace); }
                return sb.toString();
            } catch (Throwable t) { return ""; }
        }

        private static String dayStr() {
            return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        }

        /** 异常带堆栈落盘（关键路径用；栈只取前 8 层，避免刷屏）。 */
        void logException(String where, Throwable t) {
            try {
                if (t == null) return;
                jlog(LV_ERR, where + " 异常: " + t);
                StackTraceElement[] st = t.getStackTrace();
                int n = Math.min(st == null ? 0 : st.length, 8);
                for (int i = 0; i < n; i++) jlog(LV_ERR, "    at " + st[i]);
            } catch (Throwable ignored) {}
        }

        /** 记录一次被静默吞掉的异常（catch(Throwable ignored) 里调用）。 */
        void noteSwallowed(String where, Throwable t) {
            try {
                swallowedCount.incrementAndGet();
                String m = (where == null ? "?" : where) + " -> " + (t == null ? "?" : String.valueOf(t));
                swallowedRecent.add(m);
                while (swallowedRecent.size() > 20) swallowedRecent.poll();
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

        /**
         * 按天落盘：run-YYYYMMDD.log；单文件超 512KB 后加 -1/-2 后缀；最多保留 7 天。
         * 跨天排查直接开对应日期文件，不再是一锅粥。
         */
        private static void appendDisk(java.io.File dir, String day, List<String> batch) {
            try {
                if (dir == null || batch == null || batch.isEmpty()) return;
                java.io.File cur = new java.io.File(dir, "run-" + day + ".log");
                if (cur.length() > 512L * 1024L) {
                    for (int i = 3; i >= 1; i--) {
                        java.io.File old = new java.io.File(dir, "run-" + day + "-" + i + ".log");
                        if (!old.exists()) continue;
                        if (i == 3) old.delete();
                        else old.renameTo(new java.io.File(dir, "run-" + day + "-" + (i + 1) + ".log"));
                    }
                    cur.renameTo(new java.io.File(dir, "run-" + day + "-1.log"));
                }
                StringBuilder sb = new StringBuilder();
                for (String s : batch) sb.append(s).append('\n');
                java.io.FileOutputStream os = new java.io.FileOutputStream(cur, true);
                os.write(sb.toString().getBytes("UTF-8"));
                os.close();
                pruneLogs(dir);
            } catch (Throwable ignored) {}
        }

        /** 只保留最近 7 天的日志文件。 */
        private static void pruneLogs(java.io.File dir) {
            try {
                java.io.File[] fs = dir.listFiles();
                if (fs == null || fs.length <= 7) return;
                java.util.List<java.io.File> logs = new java.util.ArrayList<java.io.File>();
                for (java.io.File f : fs) {
                    String n = f.getName();
                    if (f.isFile() && isLogFileName(n)) logs.add(f);
                }
                if (logs.size() <= 7) return;
                java.util.Collections.sort(logs, new java.util.Comparator<java.io.File>() {
                    @Override public int compare(java.io.File a, java.io.File b) {
                        return a.getName().compareTo(b.getName());
                    }
                });
                for (int i = 0; i + 7 < logs.size(); i++) {
                    try { logs.get(i).delete(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        /** 内存与落盘历史合并（按整行文本去重），供日志页与导出使用 */
        private List<LogLine> mergedLog(int max) {
            List<LogLine> out = new ArrayList<LogLine>();
            try {
                synchronized (logBuffer) { out.addAll(logBuffer); }
                java.io.File dir = logDir();
                if (dir != null && dir.isDirectory()) {
                    // 读全部日志文件：run.log（旧命名）+ run-YYYYMMDD.log + run-YYYYMMDD-N.log
                    java.util.List<java.io.File> files = new java.util.ArrayList<java.io.File>();
                    java.io.File[] fs = dir.listFiles();
                    if (fs != null) for (java.io.File f : fs) {
                        if (f == null || !f.isFile()) continue;
                        String n = f.getName();
                        if (!n.endsWith(".log")) continue;
                        if (n.equals("run.log") || n.startsWith("run-")) files.add(f);
                    }
                    java.util.Collections.sort(files, new java.util.Comparator<java.io.File>() {
                        @Override public int compare(java.io.File a, java.io.File b) {
                            // run.log 是旧命名遗留，内容最老，必须排最前。
                            // 注意不能直接比名字：'-'(45) < '.'(46)，run.log 会排到
                            // run-YYYYMMDD 之后，从而被当成"最新文件"读进来。
                            boolean la = a.getName().equals("run.log"), lb = b.getName().equals("run.log");
                            if (la != lb) return la ? -1 : 1;
                            return a.getName().compareTo(b.getName());
                        }
                    });
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
                    for (LogLine l : out) seen.add(l.flat());
                    List<LogLine> older = new ArrayList<LogLine>();
                    long totalRead = 0L;
                    // 从「最旧文件」往后读、文件内也从旧往新，直接得到「旧→新」。
                    // 旧写法是「从新文件往回读 + 整体 reverse 一次」，那样跨文件时会倒成
                    // [最旧][次旧]…[最新]，把最新日志甩到列表尾部，首屏反而全是老日志。
                    for (int fi = 0; fi < files.size(); fi++) {
                        java.io.File f = files.get(fi);
                        if (!f.exists() || f.length() <= 0L) continue;
                        if (f.length() > 8L * 1024 * 1024) continue;
                        java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                        try {
                            String ln;
                            while ((ln = br.readLine()) != null) lines.add(ln);
                        } finally { try { br.close(); } catch (Throwable ignored) {} }
                        for (int i = 0; i < lines.size(); i++) {   // 文件内从旧往新
                            String t = lines.get(i);
                            if (t == null || t.trim().length() == 0) continue;
                            if (seen.contains(t)) continue;
                            seen.add(t);
                            older.add(parseLogLine(t));
                            totalRead++;
                            if (totalRead > max) break;
                        }
                        if (totalRead > max) break;
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

    /** 判定「这条目标正在发送中」的有效窗口。
     *  必须与回复判定侧的撤销窗口对齐：那边特意放宽到 30 分钟等慢 bot 回复，
     *  这里若只有 90 秒，3 分钟后才回话的 bot 会因为 pending 已被清掉而走不到撤销逻辑，
     *  失败会被当成成功记下来。 */
    private static final long PENDING_TTL_MS = 30L * 60 * 1000;

    private boolean isPendingFresh(String prefix, String id) {
        String key = (prefix == null ? "" : prefix) + id;
        if (!pendingSigns.contains(key)) return false;
        long sentAt = prefs.getLong((prefix == null ? "" : prefix) + "sent_at_" + id, 0L);
        // sent_at 取不到（例如刚重启、或该条目是别的账号发的）时，不能当成"已过期"，
        // 否则会把正在发送中的请求误判作废、甚至导致重复发送。
        if (sentAt <= 0L) return true;
        if (System.currentTimeMillis() - sentAt > PENDING_TTL_MS) {
            pendingSigns.remove(key);
            jlog("目标 " + id + " 发送状态超过 " + (PENDING_TTL_MS / 60000L) + " 分钟未回调，已清理并允许重试");
            return false;
        }
        return true;
    }

    /** 查某条目在某账号下的发送时间（不再跨账号 fallback，避免多账号互相误判）。 */
    private long currentSentAt(String prefix, String id) {
        try {
            return prefs.getLong((prefix == null ? "" : prefix) + "sent_at_" + id, 0L);
        } catch (Throwable ignored) {}
        return 0L;
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
    /** peer 类型：user（默认，含 bot）| chat（群/超级群/频道） */
    private String entryPeerKind(Map<String, Object> m) {
        Object o = m.get("peerKind");
        String v = o == null ? "user" : String.valueOf(o);
        return "chat".equals(v) ? "chat" : "user";
    }
    /** 条目自定义标题（群名等）；没有则用 botName 兜底 */
    private String entryTitle(Map<String, Object> m) {
        Object o = m.get("title");
        if (o == null) return null;
        String v = String.valueOf(o);
        return ("null".equals(v) || v.isEmpty()) ? null : v;
    }

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
        e.putString(kLearned(prefix, id), entryText(m));
        e.putString(prefix + "kind_" + id, entryKind(m));
        e.putLong(prefix + "did_" + id, entryDid(m));
        // 群/频道目标：记下 peer 类型与显示名（群聊 getChat 而非 getUser）
        String pk = entryPeerKind(m);
        if (!"user".equals(pk)) e.putString(prefix + "peerkind_" + id, pk);
        else e.remove(prefix + "peerkind_" + id);
        String ttl = m.get("title") == null ? null : String.valueOf(m.get("title"));
        if (ttl != null && ttl.length() > 0 && !"null".equals(ttl)) e.putString(prefix + "title_" + id, ttl);
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
            .remove(kLearned(prefix, id))
            .remove(prefix + "kind_" + id)
            .remove(prefix + "did_" + id)
            .remove(prefix + "data_" + id)
            .remove(prefix + "hash_" + id)
            .remove(prefix + "msg_id_" + id)
            .remove(kLast(prefix, id))
            .remove(kRetry(prefix, id))
            .remove(kRetryAt(prefix, id))
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
        warn.setTextSize(Theme.TS_BODY);
        warn.setText(Lang.tr("将删除【所有账号】的全部签到目标与已签/重试状态，仅保留关键词/重试上限/唤醒命令设置。此操作不可撤销，建议先导出配置备份。"));
        box.addView(warn);
        Button ok = mkBtn(act);
        ok.setText(Lang.tr("确认清空全部配置"));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = clearAllConfig();
                toast(Lang.tf("已清空 {0} 项配置", n));
                logs("【清空配置】删除 " + n + " 个键，当前账号目标数=" + targets.size());
            }
        });
        box.addView(ok);
        showDialog(act, "清空所有配置", box, "取消");
    }

    private void sendWake(Object peer, int account) throws Exception {
        Class<?> sendCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_sendMessage");
        Object req = newTlObject(sendCls);
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
        Object req = newTlObject(sendCls);
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
            if (did==0 && lastCapDid>0) did=lastCapDid;   // 群 ID 是负数，只有 0 才算无效
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
            jlog("【捕获】采样 acc="+accountLabel(currentAccount())+"(武装时 "+accountLabel(captureAcc)+") uid="+did+" msg="+mid+" 按钮数="+btns.size());
        } catch (Throwable t){ jlog("捕获异常: "+t); }
        return true;
    }

    /**
     * 网络层捕获兜底：部分客户端（如 Nagram 12.10.x）UI 层按钮 hook 不命中，
     * 此时网络层是唯一入口。武装状态下直接用回调自身信息弹绑定面板。
     * 注意：不调 updatePanelLiveButtons，避免捕获期间误触发签到。
     */
    private void handleNetworkCapture(final long did, final int msgId, final byte[] data){
        try {
            if (did == 0 || data == null || data.length == 0) return;
            final String label = cbDataLabel(data);
            lastCapDid = did; lastCapMid = msgId;
            final List<Object[]> btns = new ArrayList<Object[]>();
            btns.add(new Object[]{ label, data, 0L });
            lastCapBtns = btns;
            mainHandler.post(new Runnable(){ @Override public void run(){ showCapturePicker(lastActivity, did, msgId, btns, null); } });
            jlog("【捕获·网络兜底】弹绑定面板 uid=" + did + " msg=" + msgId + " 按钮数=1");
        } catch (Throwable t) { jlog("捕获兜底异常: " + t); }
    }

    private void showCapturePicker(Activity act, long did, int mid, List<Object[]> btns, Object proto){
        if (act==null){ toast("请在 TG 界面内完成捕获"); return; }
        LinearLayout box=new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(8),dp(16),dp(8));
        int cb=0; for (Object[] b:btns) if (b[1]!=null) cb++;
        TextView head=new TextView(act); head.setTextSize(Theme.TS_BODY); head.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        head.setText(Lang.tf("会话 uid={0}  msg={1}  回调按钮 {2}/{3}；点一个即绑定（可连点多个）", did, mid, cb, btns.size()));
        box.addView(head);
        for (final Object[] b:btns){
            final byte[] data=(byte[])b[1];
            final long hash=((Number)b[2]).longValue();
            final String text=strOr(b[0], Lang.tr("回调按钮"));
            if (data==null){
                LinearLayout r2=new LinearLayout(act); r2.setOrientation(LinearLayout.HORIZONTAL);
                TextView tt=new TextView(act); tt.setTextSize(Theme.TS_SUBTITLE); tt.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
                tt.setText("\u26a0\ufe0f "+text+Lang.tr("（文本/链接按钮，不能绑定回调）"));
                r2.addView(tt,new LinearLayout.LayoutParams(0,-2,1f));
                r2.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ toast(Lang.tr("这类按钮无法用回调模拟；文本键盘类请用「文本指令」目标（文本=按钮文字）")); } });
                box.addView(r2);
                View dv=new View(act); dv.setBackgroundColor(Theme.line(act)); box.addView(dv,new LinearLayout.LayoutParams(-1,1));
                continue;
            }
            final long fdid=did; final int fmid=mid;
            LinearLayout row=new LinearLayout(act); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(dp(4),dp(11),dp(4),dp(11));
            TextView t=new TextView(act); t.setTextSize(Theme.TS_SUBTITLE); t.setTextColor(android.graphics.Color.parseColor(txtMain(act)));
            t.setText("🔘 "+text+"   ["+hexOf(data,10)+"]");
            row.addView(t,new LinearLayout.LayoutParams(0,-2,1f));
            TextView ar=new TextView(act); ar.setTextSize(18); ar.setText("\u203a"); row.addView(ar);
            row.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ bindCallback(fdid,text,data,hash,fmid); } });
            box.addView(row);
            View div=new View(act); div.setBackgroundColor(Theme.line(act)); box.addView(div,new LinearLayout.LayoutParams(-1,1));
        }
        if (cb==0 && proto!=null && isCallbackButton(proto)){
            final byte[] fdd=buttonData(proto); final long fh=buttonHash(proto); final String text=strOr(buttonText(proto), Lang.tr("回调按钮")); final long fdid=did; final int fmid=mid;
            if (fdd!=null){ Button one=mkBtnPrimary(act); one.setText(Lang.tr(" 绑定刚点按钮: ")+text); one.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ bindCallback(fdid,text,fdd,fh,fmid);} }); box.addView(one); }
        }
        if (cb==0 && proto==null) emptyView(box,"(没读到按钮，请在 bot 里点一下签到按钮再试)");
        showDialog(act,"捕获回调按钮", box, "完成");
    }

    private void bindCallback(long did, String text, byte[] data, long hash, int msgId){
        if (did==0){ toast("绑定失败：会话ID无效"); return; }
        if (data==null||data.length==0){ toast("该按钮无回调数据"); return; }
        Map<String,Object> exist=findCbEntry(did,data);
        if (exist!=null){
            String lb=(text==null||text.trim().isEmpty())?"回调按钮":text.trim();
            exist.put("msgId", msgId); exist.put("hash", hash); exist.put("text", lb); exist.put("loc", lb);
            persistEntry(accountPrefix(), exist);
            toast(Lang.tf("已存在该回调，已刷新消息/指纹（msg_id={0}）", msgId));
            logs("【绑定】已存在回调，刷新 msg_id="+msgId+" text="+lb);
            return;
        }
        String label=(text==null||text.trim().isEmpty())?Lang.tr("回调按钮"):text.trim();
        Map<String,Object> m=new HashMap<>();
        m.put("id", nextEntryId(did, KIND_CB));
        m.put("did", did); m.put("text", label); m.put("kind", KIND_CB);
        m.put("data", data); m.put("hash", hash); m.put("msgId", msgId); m.put("loc", label);
        persistEntry(accountPrefix(), m); addTargetEntry(m);
        toast(Lang.tf("✅ 已绑定回调: {0}（当前共 {1} 个目标）", label, targetsSnapshot().size()));
        logs("【绑定】uid="+did+" text="+label+" data="+hexOf(data,16)+" msg_id="+msgId);
    }

    private void startCapture(Activity act){
        if (act==null){ toast("请在 TG 界面使用 /jmb"); return; }
        captureArmed=true; captureArmedAt=System.currentTimeMillis();
        captureAcc=currentAccount();
        toast("捕获模式已开启：去 bot 会话里点一次它的按钮，我会列出该消息所有按钮供你绑定");
        jlog("【捕获】已武装 acc=" + accountLabel(captureAcc) + "，等待下一次按钮点击");
    }

    /**
     * 群 ID 查询：列出当前账号最近会话里的「群 / 频道」，附 ID，点一条直接填入。
     * 解决"群 ID 怎么获取"——用户不用去别处查。
     */
    /**
     * 读取会话列表。宿主差异：
     *   12.10.x —— getDialogs(int folderId)  （apk-index 实测签名）
     *   老版本  —— getDialogs(ArrayList) 或字段 dialogs
     * 逐个尝试，都失败返回 null。
     */
    @SuppressWarnings("unchecked")
    private java.util.List<Object> fetchDialogs(Object mc) {
        if (mc == null) return null;
        // ① getDialogs(int)  —— 0 = 主文件夹
        try {
            Object r = call(mc, "getDialogs", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(0)});
            if (r instanceof java.util.List) return (java.util.List<Object>) r;
        } catch (Throwable ignored) {}
        // ② getDialogs(ArrayList) —— 老版本传入空列表由它填充
        try {
            java.util.ArrayList<Object> out = new java.util.ArrayList<Object>();
            Object r = call(mc, "getDialogs", new Class<?>[]{java.util.ArrayList.class}, new Object[]{out});
            if (r instanceof java.util.List && !((java.util.List<?>) r).isEmpty()) return (java.util.List<Object>) r;
            if (!out.isEmpty()) return out;
        } catch (Throwable ignored) {}
        // ③ 无参 getDialogs()
        try {
            Object r = call(mc, "getDialogs", new Class<?>[0], new Object[0]);
            if (r instanceof java.util.List) return (java.util.List<Object>) r;
        } catch (Throwable ignored) {}
        // ④ 字段 dialogsByFolder（SparseArray）/ dialogs
        try {
            Object arr = getFieldValSafe(mc, "dialogsByFolder");
            if (arr != null) {
                try {
                    Object r = call(arr, "get", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(0)});
                    if (r instanceof java.util.List) return (java.util.List<Object>) r;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            Object r = getFieldValSafe(mc, "dialogs");
            if (r instanceof java.util.List) return (java.util.List<Object>) r;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 会话条目 → dialogId（字段名各版本不一）。 */
    private long dialogIdOf(Object d) {
        if (d == null) return 0L;
        for (String m : new String[]{"getDialogId", "getId"}) {
            try {
                Object o = call(d, m, new Class<?>[0], new Object[0]);
                if (o instanceof Number) return ((Number) o).longValue();
            } catch (Throwable ignored) {}
        }
        for (String f : new String[]{"dialogId", "id"}) {
            try {
                Object o = getFieldValSafe(d, f);
                if (o instanceof Number) return ((Number) o).longValue();
            } catch (Throwable ignored) {}
        }
        return 0L;
    }

    /** 批量取群名：从 chat / channel 缓存里找。 */
    private String chatTitleFromCache(Object mc, long did, int account) {
        long raw = -did;
        boolean isChannel = raw > 1000000000000L;
        long bare = isChannel ? (raw - 1000000000000L) : raw;
        for (String m : new String[]{"getChat", "getChannel"}) {
            try {
                Object c = invoke(mc, m, new Class<?>[]{Long.class}, new Object[]{Long.valueOf(bare)});
                if (c != null) {
                    Object t = getFieldValSafe(c, "title");
                    if (t != null) {
                        String v = String.valueOf(t);
                        if (!v.isEmpty() && !"null".equals(v)) return v;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // 兜底：数据库
        try {
            Object ms = getMessagesStorage(account);
            if (ms != null) {
                Object c = invoke(ms, "getChat", new Class<?>[]{long.class}, new Object[]{Long.valueOf(bare)});
                if (c != null) {
                    Object t = getFieldValSafe(c, "title");
                    if (t != null) {
                        String v = String.valueOf(t);
                        if (!v.isEmpty() && !"null".equals(v)) return v;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void showPickChat(final Activity act, final EditText targetEd, final EditText titleEd) {
        try {
            Object mc = getMessagesController(currentAccount());
            if (mc == null) { toast("拿不到 MessagesController，请回到 TG 主界面再试"); return; }
            java.util.List<Object> dialogs = fetchDialogs(mc);
            if (dialogs == null || dialogs.isEmpty()) {
                toast("会话列表为空：先打开一次 TG 主界面（会话列表）再试");
                return;
            }

            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(8), dp(6), dp(8), dp(6));
            TextView tip = new TextView(act);
            tip.setTextSize(Theme.TS_CAPTION);
            tip.setTextColor(Theme.termMuted(act));
            tip.setTypeface(Theme.text());
            tip.setText(Lang.tr("下面是最近会话里的群 / 频道。点一条即可填入群 ID。"));
            tip.setPadding(dp(4), 0, dp(4), dp(8));
            box.addView(tip);

            int shown = 0;
            for (Object d : dialogs) {
                if (shown >= 40) break;
                long did = dialogIdOf(d);
                if (did >= 0) continue;   // 只收群/频道（负数）
                String title = chatTitleFromCache(mc, did, currentAccount());
                if (title == null || title.isEmpty()) continue;
                shown++;

                final long fDid = did;
                final String fTitle = title;
                TextView row = new TextView(act);
                row.setTextSize(Theme.TS_BODY);
                row.setTextColor(Theme.termTxt(act));
                row.setTypeface(Theme.text());
                row.setText(title + "\n" + did);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x26)));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.setMargins(0, dp(2), 0, dp(2));
                row.setLayoutParams(rlp);
                row.setClickable(true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            if (targetEd != null) targetEd.setText(String.valueOf(fDid));
                            if (titleEd != null && titleEd.getText().toString().trim().isEmpty()) titleEd.setText(fTitle);
                            toast(Lang.tf("已填入: {0}", fTitle));
                        } catch (Throwable ignored) {}
                    }
                });
                box.addView(row);
            }
            if (shown == 0) {
                TextView none = new TextView(act);
                none.setTextSize(Theme.TS_BODY);
                none.setTextColor(Theme.termMuted(act));
                none.setTypeface(Theme.text());
                none.setText(Lang.tr("没找到群/频道。请先打开该群发一条消息，再回来试。"));
                none.setPadding(dp(8), dp(10), dp(8), dp(10));
                box.addView(none);
            }
            showDialog(act, "选择群 / 频道", box, "关闭");
        } catch (Throwable t) { toast(Lang.tf("读取会话失败: {0}", t)); }
    }

    /** 添加「群聊签到」目标：输入群（点选会话或手填）+ 签到指令。 */
    // 规范化 ID 输入：去空格、全角减号/数字转半角、去掉 -100 前的多余符号
    private static String normalizeId(String raw) {
        return SignLogic.normalizeId(raw);
    }

    private void showAddGroup(final Activity act) {
        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(8), dp(16), dp(8));
            TextView tip = new TextView(act);
            tip.setTextSize(Theme.TS_BODY);
            tip.setTextColor(Theme.termMuted(act));
            tip.setTypeface(Theme.text());
            tip.setText(Lang.tr("用于在「群 / 频道」里发签到指令（不是私聊 bot）。\n\n"
                    + "群 ID 怎么拿？三种办法：\n"
                    + "① 点下面「从会话列表选群」→ 自动填 ID（推荐）\n"
                    + "② 直接去那个群点一次签到按钮 → 自动识别添加\n"
                    + "③ 群里长按任意消息转发给 @userinfobot 也可查（备用）"));
            tip.setPadding(dp(4), 0, dp(4), dp(8));
            box.addView(tip);

            final EditText idEd = adInput(act, Lang.tr("群 ID（形如 -1001234567890，含负号）"), 2);
            box.addView(idEd);
            final EditText titleEd = adInput(act, "备注名（可留空，如：某签到群）", 0);
            box.addView(titleEd);
            Button pick = mkBtn(act);
            withIconText(act, pick, "list", "从会话列表选群（自动填 ID）");
            pick.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPickChat(act, idEd, titleEd); }
            });
            box.addView(pick);
            final EditText cmdEd = adInput(act, "签到指令（如 /checkin）", 0);
            box.addView(cmdEd);

            Button ok = mkBtnPrimary(act);
            ok.setText(Lang.tr("添加群签到目标"));
            ok.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        String raw = idEd.getText().toString().trim();
                        String cmd = cmdEd.getText().toString().trim();
                        String ttl = titleEd.getText().toString().trim();
                        if (raw.isEmpty()) { toast("请填群 ID"); return; }
                        if (cmd.isEmpty()) { toast("请填签到指令"); return; }
                        long did;
                        try { did = Long.parseLong(normalizeId(raw)); }
                        catch (Throwable t) { toast("群 ID 必须是数字（负数），例：-1001234567890"); return; }
                        if (did >= 0) { toast("群 ID 应为负数，如 -1001234567890"); return; }
                        String prefix = accountPrefix();
                        String id = String.valueOf(did) + "_g1";
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", id);
                        m.put("did", did);
                        m.put("text", cmd);
                        m.put("kind", KIND_TEXT);
                        m.put("peerKind", "chat");
                        if (!ttl.isEmpty()) m.put("title", ttl);
                        persistEntry(prefix, m);
                        loadTargets();
                        logs("[群签到] 已添加 " + did + " 指令=" + cmd + (ttl.isEmpty() ? "" : " 备注=" + ttl));
                        toast("已添加群签到目标");
                        try { sendSign(m, currentAccount()); } catch (Throwable ignored) {}
                    } catch (Throwable t) { toast(Lang.tf("添加失败: {0}", t)); }
                }
            });
            box.addView(ok);
            showDialog(act, "添加群聊签到", box, "取消");
        } catch (Throwable t) { logd("群签到页异常: " + t); }
    }

    private void showAddChooser(final Activity act){
        LinearLayout menu=new LinearLayout(act); menu.setOrientation(LinearLayout.VERTICAL);
        TextView tip=new TextView(act); tip.setTextSize(Theme.TS_BODY); tip.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        tip.setText(Lang.tr("不用管类型：去 bot 会话点一下它的签到按钮，选「自动识别」即可。\n如果它要的是发指令，用「我有签到指令」。"));
        tip.setPadding(dp(12),dp(8),dp(12),dp(8)); menu.addView(tip);
        menuItem(menu,"bulb","自动识别（推荐）","去 bot 会话点一下它的签到按钮，会自动记忆并每天跟进","cap_cb");
        menuItem(menu,"keyboard","我有签到指令","知道它要求的文本指令（bot ID + 指令）","add_text");
        menuItem(menu,"group","群 / 频道签到","在群聊里发签到指令（不是私聊）","add_group");
        showDialog(act,"添加签到目标", menu, "关闭");
    }

    private final Object[] entryActionsDlg = new Object[1];
    private void showEntryActions(final Activity act, final Map<String,Object> m){
        dismissOne(entryActionsDlg[0]);
        final String id=entryId(m); final boolean cb=KIND_CB.equals(entryKind(m));
        LinearLayout b=new LinearLayout(act); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView hd=new TextView(act); hd.setTextSize(Theme.TS_SUBTITLE); hd.setTextColor(android.graphics.Color.parseColor(txtMain(act)));
        hd.setText(targetTitle(entryDid(m))+"   "+(cb?Lang.tr("[回调]"):Lang.tr("[指令]"))+"   "+entryText(m)); b.addView(hd);
        if (cb){
            Button t=mkBtn(act); withIconText(act, t, "flask", "测试签到（先跑前置命令→点按钮→看返回）");
            t.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ testEntry(id); } });
            b.addView(t);
        }
        Button s=mkBtnPrimary(act); withIconText(act, s, "bolt", "立即签到");
        s.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ sendSign(m, currentAccount()); toast("已发起签到，结果见提示/日志"); } });
        b.addView(s);
        Button e=mkBtn(act); withIconText(act, e, "pencil", "编辑（标签/前置命令/定位）");
        e.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ showEditEntry(act, m); } });
        b.addView(e);
        Button rb=mkBtn(act); withIconText(act, rb, "repeat", "重绑为回调（去点它的按钮）");
        rb.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toast("去该 bot 会话点一下要绑的签到按钮，会自动作为回调新增"); startCapture(act); } });
        b.addView(rb);
        Button sn=mkBtn(act); withIconText(act, sn, "pause", "暂停一周 / 恢复");
        sn.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toggleSnooze(m, currentAccount()); showEntryActions(act, m); } });
        b.addView(sn);
        // 冻结：永久不再签这条（与"暂停一周"区分）
        final boolean frozenNow = isFrozen(accountPrefix(), entryId(m));
        Button fz=mkBtn(act); withIconText(act, fz, frozenNow ? "refresh" : "pause", frozenNow ? "解冻（恢复签到）" : "冻结（不再签到）");
        fz.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            setFrozen(m, currentAccount(), !isFrozen(accountPrefix(), entryId(m)));
            showEntryActions(act, m);
        } });
        b.addView(fz);
        // 排除整只 bot：一次挡住这个 bot 的所有签到
        final long fDid = entryDid(m);
        final boolean botBlockedNow = isBotBlocked(fDid);
        Button bb=mkBtn(act); withIconText(act, bb, "bot", botBlockedNow ? "取消排除该 bot" : "排除整只 bot");
        bb.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            try {
                if (LEARN_BLOCKED_DIDS.contains(fDid)) {
                    LEARN_BLOCKED_DIDS.remove(fDid);
                    toast("已取消排除该 bot");
                } else {
                    LEARN_BLOCKED_DIDS.add(fDid);
                    toast("已排除该 bot：不再自动学习、不再签到");
                }
                prefs.edit().putString(kBlockedDids(), blockedDidsToStr()).apply();
                jlog("设置更新: 排除的 bot = " + (LEARN_BLOCKED_DIDS.isEmpty() ? "(无)" : blockedDidsToStr()));
                showEntryActions(act, m);
            } catch (Throwable t) { toast(Lang.tf("操作失败: {0}", t)); }
        } });
        b.addView(bb);
        Button d=mkBtnDanger(act); d.setText(Lang.tr("删除"));
        withIcon(act, d, "trash", Theme.termPink(act));
        d.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ confirmDelete(act, m); } });
        b.addView(d);
        entryActionsDlg[0] = showDialog(act,"条目操作", b, "关闭");
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
        final EditText alias=adInput(act,"备注名（显示用，可空；如「每日签到」「查档」）",0);
        String curTitle = entryTitle(m);
        alias.setText(curTitle == null ? "" : curTitle);
        box.addView(alias);
        final EditText name=adInput(act,"标签 / 指令",0); name.setText(entryText(m)); box.addView(name);
        final EditText pre=adInput(act,"前置命令序列（逗号或换行分隔，可空；发送后拉面板再点按钮）",0);
        if (cb) pre.setText(preToJsonToText(entryPre(m))); box.addView(pre);
        if (cb){
            TextView pt = new TextView(act); pt.setTextSize(Theme.TS_SECOND); pt.setTextColor(Theme.termMuted(act)); pt.setPadding(dp(2), dp(6), 0, 0);
            pt.setText(Lang.tr("模板（点一下追加；可自行输入）："));
            box.addView(pt);
            android.widget.HorizontalScrollView hsc = new android.widget.HorizontalScrollView(act);
            LinearLayout chips = new LinearLayout(act); chips.setOrientation(LinearLayout.HORIZONTAL); chips.setPadding(0, dp(4), 0, 0);
            final String[] TPL = {"/start", "/menu", "/qd", "/checkin", "签到", "开始", "菜单"};
            for (final String tpl : TPL) {
                Button cbB = mkBtn(act); cbB.setText(tpl); cbB.setTextSize(Theme.TS_SECOND);
                cbB.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                    String cur = pre.getText()==null?"":pre.getText().toString().trim();
                    if (cur.length() > 0) cur += ",";
                    pre.setText(cur + tpl);
                } });
                chips.addView(cbB, new LinearLayout.LayoutParams(-2, -2));
            }
            Button clb = mkBtnDanger(act); clb.setText(Lang.tr("清空")); clb.setTextSize(Theme.TS_SECOND);
            clb.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ pre.setText(""); } });
            chips.addView(clb, new LinearLayout.LayoutParams(-2, -2));
            hsc.addView(chips);
            box.addView(hsc);
        }
        final EditText loc=adInput(act,"按钮定位文案（重开面板按此找回按钮，默认=标签）",0);
        if (cb){ loc.setText(entryLoc(m)); box.addView(loc); }
        Button ok=mkBtnPrimary(act); ok.setText(Lang.tr("保存"));
        ok.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            try {
                m.put("text", name.getText().toString().trim());
                String al = alias.getText().toString().trim();
                if (al.length() > 0) m.put("title", al); else m.remove("title");
                if (cb){
                    String pj=textToPreJson(pre.getText().toString());
                    if (pj!=null) m.put("pre", pj); else m.remove("pre");
                    String lc=loc.getText().toString().trim();
                    m.put("loc", lc.length()==0?m.get("text"):lc);
                }
                persistEntry(accountPrefix(), m);
                toast("已保存"); showList(act);
            } catch (Throwable t){ toast(Lang.tf("保存失败: {0}", t)); }
        }});
        box.addView(ok);
        showDialog(act,"编辑目标", box, "取消");
    }

    private void sendPreAndResign(final Map<String,Object> entry, final int account, final Object peer, final List<String> pres, final int idx){
        if (idx >= pres.size()){
            // 改革：发完全部前置命令后，只设超时兜底。等面板状态已在 sendSign 里建立，
            // 面板刷新事件一到就会命中并立即点按钮；这里只在超时（面板一直没来）时兜底。
            final String id = entryId(entry);
            jlog("前置命令已全部发出，等待面板刷新（8 秒超时兜底）…");
            mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                try {
                    synchronized (waitingPanel) {
                        if (!waitingPanel.containsKey(id)) return; // 面板已到并已触发，取消兜底
                        waitingPanel.remove(id);
                        waitingPanelDeadline.remove(id);
                    }
                    jlog("[" + id + "] 等待面板超时，改用旧 msg_id 兜底签到…");
                    // 与"面板刷新后点按钮"同一流程的兜底分支，同样必须跳过串行闸
                    sendSign(entry, account, true);
                } catch (Throwable ignored) {}
            } }, 8000L);
            return;
        }
        try { sendText(peer, account, pres.get(idx)); jlog("前置命令 [" + pres.get(idx) + "] 已发送，等待面板…"); }
        catch (Throwable t){ jlog("前置命令发送失败(忽略): " + t); }
        mainHandler.postDelayed(new Runnable(){ @Override public void run(){ sendPreAndResign(entry, account, peer, pres, idx + 1); } }, 1200L);
    }

    /** 面板刷新事件回调：若有条目正等这个 did 的面板，立即触发点按钮（msg_id 最新）。 */
    private void onPanelRefreshedForWaiting(long did) {
        try {
            String hitId = null;
            int hitAcc = -1;
            synchronized (waitingPanel) {
                for (java.util.Map.Entry<String, long[]> e : waitingPanel.entrySet()) {
                    long[] v = e.getValue();
                    if (v != null && v.length > 1 && v[0] == did) { hitId = e.getKey(); hitAcc = (int) v[1]; break; }
                }
                if (hitId != null) {
                    waitingPanel.remove(hitId);
                    waitingPanelDeadline.remove(hitId);
                }
            }
            if (hitId != null) {
                jlog("[" + hitId + "] 面板已刷新，立即点按钮签到（msg_id 最新）");
                // 找到对应条目并触发签到
                Map<String, Object> target = null;
                for (Map<String, Object> m : targetsSnapshot()) {
                    if (hitId.equals(entryId(m))) { target = m; break; }
                }
                // 这里是**同一条签到的后续步骤**（前置命令已发、面板刚刷新，现在点按钮）。
                // 必须传 manual=true 跳过串行闸：闸门是这次签到自己在第一步占下的，
                // 不跳过就会"自己拦自己"——面板刷新了却点不出去，永远卡在"已有签到在途"
                //（实测 ExteraLess 上 8439387373_cb1 反复被拦、始终签不上）。
                // 用发起时锁定的账号，不用 currentAccount()：面板刷新可能隔几秒，
                // 期间切号会把这个目标发到错误账号（与定时任务 armTask 同类 bug）。
                if (target != null) sendSign(target, hitAcc >= 0 ? hitAcc : currentAccount(), true);
            }
        } catch (Throwable _e1) { noteSwallowed("onPanelRefreshedForWaiting", _e1); }
    }

    /**
     * 解析任意 peer（user / bot / 群 / 超级群 / 频道）→ InputPeer。
     * 现有代码只走 getUser，群聊会拿不到；这里补 chat / channel 支路。
     * kind: "user"(默认) | "chat"，由条目字段 peer_kind 决定。
     */
    private Object resolveInputPeerAny(long did, int account, String kind) {
        try {
            Object mc = getMessagesController(account);
            if (mc == null) return null;
            boolean isChat = "chat".equals(kind);
            if (isChat) {
                // 群 / 频道：-100xxxxxxxxxx 为超级群/频道，-xxxxxxxxxx 为普通群
                if (did < 0) {
                    long raw = -did;
                    boolean isChannel = raw > 1000000000000L;
                    long bare = isChannel ? (raw - 1000000000000L) : raw;
                    Object chat = null;
                    try { chat = invoke(mc, "getChat", new Class<?>[]{Long.class}, new Object[]{Long.valueOf(bare)}); } catch (Throwable ignored) {}
                    if (chat == null) {
                        try { chat = invoke(mc, "getChat", new Class<?>[]{Long.class}, new Object[]{Long.valueOf(did)}); } catch (Throwable ignored) {}
                    }
                    if (chat == null) {
                        try {
                            Object ms = getMessagesStorage(account);
                            if (ms != null) chat = invoke(ms, "getChat", new Class<?>[]{long.class}, new Object[]{Long.valueOf(bare)});
                        } catch (Throwable ignored) {}
                    }
                    if (chat != null) {
                        try {
                            Object p = staticInvoke(classEx("org.telegram.messenger.MessagesController"), "getInputPeer",
                                    new Class<?>[]{classEx("org.telegram.tgnet.TLObject")}, new Object[]{chat});
                            if (p != null) return p;
                        } catch (Throwable ignored) {}
                    }
                }
                Object p2 = peerFromDialogs(did, account);
                if (p2 != null) return p2;
                return null;
            }
            return resolveInputPeer(did, account);
        } catch (Throwable t) { return null; }
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
        // 手动测试是用户明确要发：清掉同一 bot 的串行闸与待确认标记，否则会被
        //「该对话已有签到在途」挡掉（实测：连点两次都被拦，看着像功能没反应）。
        String _p = accountPrefix();
        clearPendingConfirm(_p, id);
        sendSign(m, currentAccount(), true);
    }

    private void testFire(final long did, final int mid, final String label, final byte[] data, final long hash){
        try {
            final int account=currentAccount();
            Object peer=resolveInputPeer(did, account);
            if (peer==null){ toast("取 InputPeer 失败，先在会话里点一下该 bot"); return; }
            Object req=newTlObject(classEx("org.telegram.tgnet.TLRPC$TL_messages_getBotCallbackAnswer"));
            setFieldVal(req,"peer",peer); setFieldVal(req,"data",data); setFieldVal(req,"msg_id",mid);
            final String fl=label; final long fdid=did;
            Object cm=staticInvoke(classEx("org.telegram.tgnet.ConnectionsManager"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
            Object delegate=newRequestDelegate(new InvocationHandler(){
                @Override public Object invoke(Object p, Method mm, Object[] a){
                    if ("run".equals(mm.getName()) && a!=null && a.length>=2){
                        final Object resp=a[0]; final Object err=a[1];
                        mainHandler.post(new Runnable(){ public void run(){
                            if (err!=null){ String et=""; try{ et=strOr(getFieldValSafe(err,"text"),""); }catch(Throwable ignored){} toast(Lang.tf("🧪 {0} 失败: {1}", fl, et)); jlog("【测试】uid="+fdid+" ["+fl+"] 失败 err="+et); }
                            else { String ans=""; try{ Object am=getFieldValSafe(resp,"message"); if(am==null) am=getFieldValSafe(resp,"alert"); ans=strOr(am,""); }catch(Throwable ignored){} toast(Lang.tf("🧪 {0} 成功", fl)+(ans.length()>0?": "+ans:"")); jlog("【测试】uid="+fdid+" ["+fl+"] 成功 answer="+ans); }
                        }});
                    }
                    return null;
                }
            });
            invoke(cm,"sendRequest", new Class<?>[]{classEx("org.telegram.tgnet.TLObject"), classEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
            toast(Lang.tf("🧪 已发送测试: {0}", label));
        } catch (Throwable t){ toast(Lang.tf("测试异常: {0}", t)); }
    }

    private void showDebugConsole(Activity act){
        if (act==null){ toast("请在 TG 界面使用 /jmb"); return; }
        List<Object[]> btns = readVisibleKeyboard();
        if (btns.isEmpty() && lastCapBtns!=null) btns = lastCapBtns;
        LinearLayout box=new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(8),dp(16),dp(8));
        TextView head=new TextView(act); head.setTextSize(Theme.TS_BODY); head.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        head.setText(Lang.tf("回调调试台 · uid={0} msg={1} 按钮 {2} 个\n点任意按钮=实时发一次该回调并看返回；不放心先「重新采样」", lastCapDid, lastCapMid, btns.size()));
        box.addView(head);
        Button samp=mkBtnPrimary(act); withIconText(act, samp, "target", "重新采样（去点一次按钮）");
        samp.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ startCapture(act); } });
        box.addView(samp);
        int cb=0;
        for (final Object[] b:btns){
            final byte[] data=(byte[])b[1];
            if (data==null) continue; cb++;
            final long hash=((Number)b[2]).longValue();
            final String text=strOr(b[0], Lang.tr("回调按钮"));
            final long fdid=lastCapDid; final int fmid=lastCapMid;
            LinearLayout row=new LinearLayout(act); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(dp(4),dp(11),dp(4),dp(11));
            TextView t=new TextView(act); t.setTextSize(Theme.TS_SUBTITLE); t.setTextColor(android.graphics.Color.parseColor(txtMain(act)));
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
                if (key.startsWith(kLearned(prefix, ""))) ids.add(key.substring(kLearned(prefix, "").length()));
            }
            Collections.sort(ids);
            for (String id : ids) {
                try {
                    long did = numLong(prefix + "did_" + id, 0L);
                    String kind = strOf(prefix + "kind_" + id);
                    // 注意：群/频道 did 是负数，不能当无效值丢掉！
                    if (did == 0L) {
                        try { did = Long.parseLong(id); } catch (Throwable t) { continue; } // 旧键 learned_<did>
                    }
                    if (kind == null) kind = KIND_TEXT;   // kind_ 在就如实回填（修复回调被强制变指令）
                    if (did == 0L) continue;
                    String text = String.valueOf(all.get(kLearned(prefix, id)));
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", id);
                    m.put("did", did);
                    m.put("text", text);
                    m.put("kind", kind);
                    String pkv = strOf(prefix + "peerkind_" + id);
                    if (pkv != null && pkv.length() > 0) m.put("peerKind", pkv);
                    String ttlv = strOf(prefix + "title_" + id);
                    if (ttlv != null && ttlv.length() > 0) m.put("title", ttlv);
                    if (KIND_CB.equals(kind)) {
                        String b64 = strOf(prefix + "data_" + id);
                        if (b64 != null) {
                            try { m.put("data", Base64.getDecoder().decode(b64)); } catch (Throwable _e2) { noteSwallowed("loadTargetsInto", _e2); }
                        }
                        m.put("hash", numLong(prefix + "hash_" + id, 0L));
                        m.put("msgId", (int) numLong(prefix + "msg_id_" + id, 0L));
                        String pj = strOf(prefix + "pre_" + id);
                        if (pj != null) m.put("pre", pj);
                        String lj = strOf(prefix + "loc_" + id);
                        if (lj != null) m.put("loc", lj);
                    }
                    out.add(m);
                } catch (Throwable _e3) { noteSwallowed("loadTargetsInto", _e3); }
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
        if (dialogId == 0) {
            logd("忽略无效 dialogId=0");
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
        if (dialogId < 0) {   // 群 / 频道：记下类型与群名，发送时走 chat 支路
            m.put("peerKind", "chat");
            String ct = chatTitle(dialogId, currentAccount());
            if (ct != null) m.put("title", ct);
        }
        String prefix = accountPrefix();
        persistEntry(prefix, m);
        addTargetEntry(m);
        logs("【自动学习】新目标 " + dialogId + " -> " + text);
        String tShort = text != null && text.length() > 18 ? text.substring(0, 18) + "…" : text;
        toast(Lang.tf("✅ 已添加新签到目标: {0}", tShort));
    }

    /** 把 callback data 转成尽量可读的标签（网络层学习用，拿不到按钮文案时的兜底）。 */
    private static String cbDataLabel(byte[] d) {
        return SignLogic.cbDataLabel(d);
    }

    /** 学习回调按钮目标（inline button，v1.3.0 新增）。同 bot 相同 data 去重。 */
    private void learnCallback(long dialogId, String display, byte[] data, long hash, int msgId) {

        syncAccount();

        if (!LEARN_ENABLED) return;
        if (data == null || data.length == 0) return;
        if (dialogId == 0) return;
        if (findCbEntry(dialogId, data) != null) {
            logd("目标 " + dialogId + " 已加过相同回调按钮，跳过");
            return;
        }
        String label = display != null && display.trim().length() > 0 ? String.valueOf(display).trim() : Lang.tr("回调按钮");
        Map<String, Object> m = new HashMap<>();
        m.put("id", nextEntryId(dialogId, KIND_CB));
        m.put("did", dialogId);
        m.put("text", label);
        m.put("kind", KIND_CB);
        if (dialogId < 0) {
            m.put("peerKind", "chat");
            String ct = chatTitle(dialogId, currentAccount());
            if (ct != null) m.put("title", ct);
        }
        m.put("data", data);
        m.put("hash", hash);
        m.put("msgId", msgId);
        String prefix = accountPrefix();
        persistEntry(prefix, m);
        addTargetEntry(m);
        try { prefs.edit().remove(kTimerPlan(prefix, todayStr())).apply(); } catch (Throwable _e4) { noteSwallowed("learnCallback", _e4); }
        logs("【自动学习】新回调目标 " + dialogId + " -> [" + label + "] data=" + Base64.getEncoder().encodeToString(data) + " msg_id=" + msgId);
        // 该对话已有别的回调目标 → 提醒这多半是"顺手收着玩"，不是真签到按钮。
        int sameBot = 0;
        try {
            for (Map<String, Object> x : targetsSnapshot()) {
                if (entryDid(x) == dialogId && KIND_CB.equals(entryKind(x))) sameBot++;
            }
        } catch (Throwable _eP) { noteSwallowed("learnCallback(计数)", _eP); }
        toast(Lang.tf("✅ 已添加回调签到目标: {0}", label));
    }

    private void learnFromNetwork(long did, String text) {
        if (!AUTO_LEARN_NET || !LEARN_ENABLED) return;
        if (text == null) return;
        if (did == 0) return;   // 群 ID 是负数，合法
        String t = String.valueOf(text).trim();
        if (t.length() == 0 || t.length() > 20) return;
        if (targetContains(did)) return;
        boolean isBotPre = false;
        try {
            Object mc0 = getMessagesController();
            if (mc0 != null) {
                Object u0 = invoke(mc0, "getUser", new Class<?>[]{Long.class}, new Object[]{did});
                if (u0 != null) isBotPre = Boolean.TRUE.equals(getFieldVal(u0, "bot"));
            }
        } catch (Throwable _e5) { noteSwallowed("learnFromNetwork", _e5); }
        if (isBotPre && isBotBlocked(did)) { logd("[候选] uid=" + did + " msg=" + t + "（命中「排除的 bot」，不自动添加）"); return; }
        String exHit = excludeHit(t);
        if (exHit != null) { logd("[候选] uid=" + did + " msg=" + t + "（命中排除规则「" + exHit + "」，不自动添加）"); return; }
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
        } catch (Throwable _e6) { noteSwallowed("learnFromNetwork", _e6); }
        if (!isBot) {
            logd("[候选] uid=" + did + " msg=" + t + "（非bot，不自动添加）");
            return;
        }
        if (AUTO_LEARN_NET_CONFIRM) {
            // 需确认：进入待确认池，不直接添加（防验证码类 bot 误加）
            if (pendingConfirmAdd(did, t)) {
                jlog("【网络层学习·待确认】" + did + " -> " + t + "（已入待确认池）");
                toast("已入待确认：确认后才加入目标");
            }
            return;
        }
        learnTarget(did, t);
        jlog("【网络层自动学习】新目标 " + did + " -> " + t);
    }

    private void markSigned(String prefix, String id) {
        // 已经标过就静默返回：这条路径会被重复投递触发多次，
        // 重复执行会让 updateStreak / noteSignedDay 累加、日志刷屏。
        String key = prefix + id;
        String cur = prefs.getString(kLast(prefix, id), "");
        if (todayStr().equals(cur)) return;
        prefs.edit().putString(kLast(prefix, id), todayStr())
             .remove(kPendingConfirm(prefix, id))   // 真签成功 → 撤掉"待确认"
             .apply();   // apply 异步落盘，避免回调路径阻塞
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
        return SignLogic.backoffDelay(retries);
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
        updatePanelLive(did, mid, replyMarkup, null);
    }

    /** 同上，额外带上消息正文（排除规则要用它匹配验证码类提示语） */
    private void updatePanelLive(long did, int mid, Object replyMarkup, String bodyText){
        try {
            if (did==0 || replyMarkup==null) return;
            List<Object[]> btns=parseKeyboardRows(replyMarkup);
            if (btns.isEmpty()) return;
            if (bodyText != null && bodyText.length() > 0) {
                PanelLive pl;
                synchronized (panelLive) {
                    pl = panelLive.get(did);
                    if (pl == null) { pl = new PanelLive(did); panelLive.put(did, pl); }
                }
                synchronized (pl) { pl.lastText = bodyText; }
            }
            updatePanelLiveButtons(did, mid, btns);
        } catch (Throwable ignored){}
    }

    /** 采集：按钮清单（捕获采样/自动学习时）→ 写缓存 + 事件驱动 */
    private void updatePanelLiveButtons(long did, int mid, List<Object[]> btns){
        try {
            if (did==0 || btns==null || btns.isEmpty()) return;
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
            // 改革：先看有没有条目正等这个 did 的面板（前置命令刚发出），有就立即点按钮
            onPanelRefreshedForWaiting(did);
            if (inWindow() || inMissBackTime()) fireLiveSign(did); else logd("[面板] 窗口外且非补签时段，不触发补签 uid="+did);
        } catch (Throwable _e7) { noteSwallowed("updatePanelLiveButtons", _e7); }
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
                    if (!inWindow() && !inMissBackTime()) { logd("[面板事件] "+did+" 窗口外且非补签时段，跳过补签"); return; }
                    List<Map<String, Object>> list=new ArrayList<>();
                    loadTargetsInto(prefix, list);
                    for (Map<String, Object> m:list){
                        if (entryDid(m)!=did || !KIND_CB.equals(entryKind(m))) continue;
                        String id=entryId(m);
                        if (todayStr().equals(prefs.getString(prefix+"last_"+id, ""))) continue;
                        // 定时模式下收口到时刻表：计划时刻未到不签；已过点且未开补签也不签
                        if (TIMER_ENABLED) {
                            int planMin = timerPlanMin(prefix, id);
                            java.util.Calendar cc = java.util.Calendar.getInstance();
                            int nowMin = cc.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cc.get(java.util.Calendar.MINUTE);
                            if (planMin < 0) { logd("[面板事件] "+did+" 定时模式无计划时刻，跳过"); break; }
                            if (planMin > nowMin) { logd("[面板事件] "+did+" 定时模式计划 "+String.format("%02d:%02d", planMin/60, planMin%60)+" 未到，交给时刻表"); break; }
                            if (!MISS_BACK) { logd("[面板事件] "+did+" 定时模式已过点且未开补签，跳过"); break; }
                            logd("[面板事件] "+did+" 定时模式已过点，开启补签：立即补");
                            sendSign(m, currentAccount());
                            break;
                        }
                        // 心跳/定时可能刚给这个目标发过，正在等回复 —— 别重复发。
                        // panelHitBusy 只防"面板事件自身"重入，防不住跨路径撞车。
                        if (isPendingFresh(prefix, id)) {
                            logd("[面板事件] " + did + " 该目标正在发送中，跳过补签");
                            break;
                        }
                        logd("[面板事件] "+did+" 面板已更新且今日未签，立即补签");
                        sendSign(m, currentAccount());
                        break;
                    }
                } catch (Throwable _e8) { noteSwallowed("fireLiveSign", _e8); }
            } }, 700L);
        } catch (Throwable _e9) { noteSwallowed("fireLiveSign", _e9); }
    }

    private void setCtxTrace(String t) {
        try { ctxTrace = t == null ? "" : t; } catch (Throwable ignored) {}
    }

    private void sendSign(Map<String, Object> entry, int account) { sendSign(entry, account, false); }

    /** @param manual 保留参数（串行闸已移除，不再区分手动/自动）。 */
    private void sendSign(Map<String, Object> entry, int account, boolean manual) {
        pace();
        // 每条目标一个链路 id：解析 → 发出 → 响应 → 判定 可串联
        setCtxTrace("t" + Integer.toHexString(random.nextInt(0xFFFF)));
        // 日志上下文跟随「实际发送的账号」，避免全账号循环里延迟任务沿用上一个账号的标签
        try { ctxAcc = accountLabel(account); ctxRound++; } catch (Throwable _e10) { noteSwallowed("sendSign", _e10); }
        final long dialogId = entryDid(entry);
        final String id = entryId(entry);
        final String kind = entryKind(entry);
        final String fText = entryText(entry);
        if (isPendingFresh(accountPrefix(account), id)) {
            jlog("目标 " + id + " 已有请求在处理中，跳过");
            return;
        }
        String prefix = accountPrefix(account);
        // 注：v1.5.8 一度加过"同一 bot 串行闸"（防多目标抢面板），实测副作用太大 ——
        // 签到流程本身要分两步调 sendSign（发前置命令 → 面板刷新后点按钮），
        // 第二步会被自己占的闸拦住，导致"面板已刷新却点不出去、永远显示已有签到在途"。
        // 已按用户要求移除：不拦、直接发，判断权交给用户。
        Object mc = getMessagesController(account);
        if (mc == null) {
            jlog("MessagesController 为空(account=" + account + ")");
            return;
        }
        String peerKind = entryPeerKind(entry);
        Object peer = null;
        if ("chat".equals(peerKind)) {
            // 群 / 频道目标：走 chat 支路（getUser 对群聊无效）
            peer = resolveInputPeerAny(dialogId, account, "chat");
            if (peer == null) {
                noteSwallowed("群peer解析(did=" + dialogId + ")", null);
                countSoftFail(entry, account, "取不到该群/频道的会话数据（先进该群发一条消息再试）");
                return;
            }
            logd("[群签到] peer 已解析 " + dialogId + " 指令=" + entryText(entry));
        } else {
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
            if (user != null) {
                try { peer = staticInvoke(classEx("org.telegram.messenger.MessagesController"), "getInputPeer", new Class<?>[]{classEx("org.telegram.tgnet.TLObject")}, new Object[]{user}); }
                catch (Throwable t) { peer = null; }
            }
            if (peer == null) peer = peerFromDialogs(dialogId, account);
            if (peer == null) {
                // 诊断：目标其实是群/频道却被当成私聊 bot（多因旧数据缺 peerKind）
                if (isChatOrChannel(dialogId, account)) {
                    logw("[诊断] " + dialogId + " 实际是群/频道但条目缺 peerKind，请在「目标列表」里删掉后重新添加");
                    countSoftFail(entry, account, "这是群/频道，需按「👥 群签到」重新添加");
                } else {
                    countSoftFail(entry, account, "取不到这个 bot 的会话数据");
                }
                return;
            }
        }
        final List<String> pres = cbPres(entry);
        if (KIND_CB.equals(kind) && !pres.isEmpty() && !wakeFired.contains(id)) {
            wakeFired.add(id);
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { wakeFired.remove(id); }
            }, 45000L);
            // 改革：发前置命令前就建立「等面板」状态，面板刷新事件一到立即命中（不因递归延时错过）
            synchronized (waitingPanel) {
                waitingPanel.put(id, new long[]{dialogId, account});
                waitingPanelDeadline.put(id, System.currentTimeMillis() + 8000L);
            }
            jlog("[" + id + "] 前置命令将发送，先进入等面板状态（did=" + dialogId + "）");
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
                req = newTlObject(cbCls);
                // 老内核（Nagram 12.8.1 等）字段形状可能不同：逐个 best-effort 并记录缺失
                if (!trySetFieldVal(req, "peer", peer)) logw("[兼容] getBotCallbackAnswer 无 peer 字段");
                if (useData != null) {
                    if (!trySetFieldVal(req, "data", useData)) logw("[兼容] getBotCallbackAnswer 无 data 字段");
                    trySetFieldVal(req, "flags", 1);   // 12.x：data 是否序列化由 flags 位决定，不设则 DATA_INVALID
                }
                if (!trySetFieldVal(req, "msg_id", useMid)) logw("[兼容] getBotCallbackAnswer 无 msg_id 字段");
            } else {
                Class<?> sendCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_sendMessage");
                req = newTlObject(sendCls);
                setFieldVal(req, "peer", peer);
                setFieldVal(req, "message", fText);
                setFieldVal(req, "random_id", random.nextLong());
            }
            pendingSigns.add(prefix + id);
            try { prefs.edit().putLong(prefix + "sent_at_" + id, System.currentTimeMillis()).commit(); } catch (Throwable _e11) { noteSwallowed("sendSign", _e11); }
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
                        mainHandler.post(() -> {
                            pendingSigns.remove(fPrefix + fId);
                        });
                        try {
                            if (error != null) {
                                String code = "";
                                try { code = String.valueOf(getFieldVal(error, "code")); } catch (Throwable _e12) { noteSwallowed("sendSign", _e12); }
                                String errText = "";
                                try { errText = String.valueOf(getFieldVal(error, "text")); } catch (Throwable _e13) { noteSwallowed("sendSign", _e13); }
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
                                            try { sendPreAndResign(fEntry, fAccount, fPeer, fPres, 0); } catch (Throwable _e14) { noteSwallowed("sendSign", _e14); }
                                        } }, 800L);
                                        logw("回调按钮过期(" + errText + ")：拉新面板后重试（" + fId + "）");
                                    } else {
                                        // 面板过期 = 签到没发出去，绝不能标"今日已签"。
                                        // 清掉已签标记 + 排一次稍后重试，当天仍会被补签捞回。
                                        int rtry = prefs.getInt(kRetry(fPrefix, fId), 0) + 1;
                                        prefs.edit().remove(kLast(fPrefix, fId))
                                             .putInt(kRetry(fPrefix, fId), Math.min(rtry, RETRY_LIMIT))
                                             .putLong(kRetryAt(fPrefix, fId), System.currentTimeMillis() + 10L * 60 * 1000)
                                             .putString(kRetryDay(fPrefix, fId), todayStr()).apply();
                                        loge("回调面板已变化(" + errText + ")：本次未签成功，将在补签时段自动重试"
                                                + (rtry >= RETRY_LIMIT ? "（已重试 " + rtry + " 次）" : "")
                                                + "；也可去 bot 会话手动点一次签到按钮立即修复（" + fId + "）");
                                        noteFailStreak(fPrefix, fId, fDialogId, "回调面板已变化 " + errText);
                                    }
                                    noteResult(false);
                                    return null;
                                }
                                if (permanent) {
                                    // 永久失败 = 今日放弃，绝不能写成"已签"。
                                    // 保留 kRetry=RETRY_LIMIT 表示不再自动试，但 kLast 必须为空，
                                    // 否则界面把失败当成功，用户只能去翻日志。
                                    prefs.edit().remove(kLast(fPrefix, fId))
                                         .putInt(kRetry(fPrefix, fId), RETRY_LIMIT)
                                         .putString(kRetryDay(fPrefix, fId), todayStr()).apply();
                                    loge("签到永久失败 " + dialogId + " : " + errText + "（今日放弃）");
                                    noteFailStreak(fPrefix, fId, dialogId, errText);
                                    noteResult(false);
                                } else if (upper.contains("BOT_RESPONSE_TIMEOUT")) {
                                    int tOut = prefs.getInt(kRetry(fPrefix, fId), 0);
                                    if (tOut >= 2) {
                                        // 未回复 ≠ 成功。绝不能替 bot 判定结果 ——
                                        // 之前这里按"已发出"记为已签，结果社工库/查询类 bot 因为本来
                                        // 就不会回复，全被标成绿色，看着签了其实什么都没发生。
                                        // 现在改成「待确认」：不算成功、不算失败、**不再自动重试**，
                                        // 用户自己看一眼决定（手动点一次，或有回复了再判）。
                                        prefs.edit().putBoolean(kPendingConfirm(fPrefix, fId), true)
                                             .putInt(kRetry(fPrefix, fId), 0)
                                             .remove(kRetryAt(fPrefix, fId))
                                             .remove(kRetryDay(fPrefix, fId))
                                             .commit();
                                        logw("已发出但 bot 始终没回复：" + fId
                                             + " 标记为「待确认」（不计成功也不计失败，已停止自动重试；"
                                             + "想再试可手动点一次）");
                                    } else {
                                        prefs.edit().putInt(kRetry(fPrefix, fId), tOut + 1)
                                             .remove(kLast(fPrefix, fId))
                                             .putLong(kRetryAt(fPrefix, fId), System.currentTimeMillis() + 15L * 60 * 1000)
                                             .putString(kRetryDay(fPrefix, fId), todayStr()).apply();
                                        logw("bot 未响应(" + errText + ")：15 分钟后重试（" + fId + "）");
                                        noteResult(false);
                                    }
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
                                    } catch (Throwable _e15) { noteSwallowed("sendSign", _e15); }
                                    if (waitSec < 1L) waitSec = 60L;
                                    long waitMs = waitSec * 1000L + 1500L;
                                    prefs.edit().putInt(kRetry(fPrefix, fId), prefs.getInt(kRetry(fPrefix, fId), 0) + 1)
                                         .remove(kLast(fPrefix, fId))
                                         .putLong(kRetryAt(fPrefix, fId), System.currentTimeMillis() + waitMs)
                                         .putString(kRetryDay(fPrefix, fId), todayStr()).apply();
                                    logw("签到遇限流 " + dialogId + " : " + errText + "，等待 " + waitSec + " 秒后自动重试");
                                } else {
                                    int oldRetry = prefs.getInt(kRetry(fPrefix, fId), 0);
                                    prefs.edit().putInt(kRetry(fPrefix, fId), oldRetry + 1)
                                         .remove(kLast(fPrefix, fId))
                                         .putLong(kRetryAt(fPrefix, fId), System.currentTimeMillis() + backoffDelay(oldRetry))
                                         .putString(kRetryDay(fPrefix, fId), todayStr())
                                         .commit();
                                    loge("签到失败 " + dialogId + " : " + errText + "（第" + (oldRetry + 1) + "次，退避重试）");
                                    noteFailStreak(fPrefix, fId, dialogId, errText);
                                    noteResult(false);
                                }
                            } else {
                                prefs.edit()
                                    .putString(kLast(fPrefix, fId), todayStr())
                                    .putInt(kRetry(fPrefix, fId), 0)
                                    .remove(kRetryAt(fPrefix, fId))
                                    .remove(kRetryDay(fPrefix, fId))
                                    .commit();
                                updateStreak(fPrefix);
                                noteSignedDay(fPrefix);
                                clearFailStreak(fPrefix, fId);
                                String ans = "";
                                try { Object am = getFieldValSafe(response, "message"); if (am == null) am = getFieldValSafe(response, "alert"); if (am != null) ans = String.valueOf(am); } catch (Throwable _e16) { noteSwallowed("sendSign", _e16); }
                                // 注意：这只是「请求发送成功」，**不等于签到成功**。
                                // bot 完全可能回「你还没有绑定账号」之类。真正的结论由
                                // onUpdateProcessed 的回复判定给出（会覆盖这里的乐观标记）。
                                // 以前这句话打成 [成功] 级「签到完成 ... 机器人返回: false」，
                                // 与判定层的 [错误]「未识别」自相矛盾，误导用户。
                                String lowerAns = ans == null ? "" : ans.toLowerCase();
                                // 只记录，不撤销、不猜成败。
                                // （v1.5.8 中途按返回文本里的 false/fail 等字样撤销过乐观标记，
                                //  但关键词匹配太粗，会把正常回复误判成失败 —— 用户反馈"总判失败"。
                                //  现在把判断权交给回复判定层，这里只留痕。）
                                logs("已发出 " + dialogId + " " + (KIND_CB.equals(fKind) ? "[回调] " : "text=") + fText
                                     + (ans.length() > 0 ? " · 返回: " + ans : "") + "（结论待回复判定）");
                                noteResult(true);
                                // 定时模式：签到成功立即推进下一个目标（原来等 30 分钟轮询，有滞后）
                                if (TIMER_ENABLED) { mainHandler.post(new Runnable(){ @Override public void run(){ try { kickSchedule(); } catch (Throwable _e17) { noteSwallowed("sendSign", _e17); } } }); }
                                mainHandler.post(new Runnable(){ @Override public void run(){ try { syncNow(); } catch (Throwable _e18) { noteSwallowed("sendSign", _e18); } } });
                                setCtxTrace("");
                            }
                        } catch (Throwable _e19) { noteSwallowed("sendSign", _e19); }
                    }
                    return null;
                }
            });
            invoke(cm, "sendRequest", new Class<?>[]{classEx("org.telegram.tgnet.TLObject"), classEx("org.telegram.tgnet.RequestDelegate")}, new Object[]{req, delegate});
            logd("已发出请求 " + dialogId + " " + (KIND_CB.equals(kind) ? "[回调] " : "text=") + fText + " (id=" + id + ")");
        } catch (Throwable t) {
            pendingSigns.remove(prefix + id);
            logException("发送签到(did=" + dialogId + ")", t);
        }
    }

    // ---------------- 补签 ----------------
    void trySignAll(String reason, boolean force) {
        trySignAllFor(reason, force, currentAccount());
    }

    /** 对指定账号执行一轮补签。从 prefs 直接读该账号条目，支持全账号签到。 */

    void trySignAllFor(String reason, boolean force, int account) {
        ctxAcc = accountLabel(account);
        ctxRound++;
        setCtxTrace("");
        // 定时模式下，非手动(force=false)的批量触发一律交给时刻表调度，不直接批量发
        if (TIMER_ENABLED && !force) {
            kickSchedule();
            lastRound = accountLabel(account) + "：定时模式，按当日时刻表逐个签到";
            return;
        }
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
        // 先筛出本轮要发的目标（沿用全部跳过判断），再主线程按随机间隔逐个发送，避免同时发出
        final List<Map<String, Object>> todo = new ArrayList<>();
        for (Map<String, Object> m : list) {
            long dialogId = entryDid(m);
            String id = entryId(m);
            try {
                if (today.equals(prefs.getString(kLast(prefix, id), ""))) { signed++; continue; }
                String retryDay = prefs.getString(kRetryDay(prefix, id), "");
                int retries = prefs.getInt(kRetry(prefix, id), 0);
                if (retries > 0 && !today.equals(retryDay)) {
                    prefs.edit().putInt(kRetry(prefix, id), 0)
                         .remove(kRetryAt(prefix, id))
                         .remove(kRetryDay(prefix, id)).apply();
                    retries = 0;
                    jlog("[" + reason + "] " + dialogId + " 进入新的一天，重试计数已重置");
                }
                if (retries >= RETRY_LIMIT) {
                    logw("[" + reason + "] " + dialogId + " 今日已重试 " + retries + " 次，明天再试");
                    busy++;
                    continue;
                }
                long retryAt = prefs.getLong(kRetryAt(prefix, id), 0);
                if (now < retryAt) {
                    logw("[" + reason + "] " + dialogId + " 退避中(剩 " + (retryAt - now) / 60000L + " 分钟)，跳过");
                    busy++;
                    continue;
                }
                if (isPendingFresh(prefix, id)) {
                    logd("[" + reason + "] " + dialogId + " 正在发送中，跳过");
                    busy++;
                    continue;
                }
                // v1.5.7：被排除的 bot 不再签到（原来只拦学习阶段，已收录的目标照签）
                if (!force && isBotBlocked(entryDid(m))) { continue; }
                if (!force && isSnoozed(prefix, id)) {
                    logd("[" + reason + "] " + dialogId + " 暂停中(至 " + prefs.getString(kSnooze(prefix, id), "") + ")，跳过");
                    continue;
                }
                if (isFrozen(prefix, id)) {
                    logd("[" + reason + "] " + dialogId + " 已冻结，跳过");
                    continue;
                }
                if (!force && dailyUsed(account) >= DAILY_CAP) {
                    logw("[" + reason + "] " + accountLabel(account) + " 今日动作已达上限 " + DAILY_CAP + "，本轮停止");
                    break;
                }
                todo.add(m);
            } catch (Throwable t) {
                jlog("trySignAll 异常 " + dialogId + " : " + t);
            }
        }
        final int fAccount = account;
        long acc = 0L;
        for (Map<String, Object> m : todo) {
            final Map<String, Object> fm = m;
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        long did = entryDid(fm);
                        logd("[" + reason + "] 尝试签到 " + did + " " + (KIND_CB.equals(entryKind(fm)) ? "[回调] " : "text=") + entryText(fm));
                        sendSign(fm, fAccount, force);
                    } catch (Throwable t) {
                        jlog("trySignAll 发送异常 " + entryDid(fm) + " : " + t);
                    }
                }
            }, acc);
            acc += 3000L + (long) (random.nextInt(7000));
        }
        int fired = todo.size();
        String sm = total == 0 ? accountLabel(account) + "：还没有签到目标（切到该账号，去 bot 会话点一下按钮或发一次指令）"
                : accountLabel(account) + "：目标 " + total + " · 已签 " + signed + " · 待重试 " + busy + " · 本轮发出 " + fired;
        lastRound = sm;
        if (total > 0) jlog("[" + reason + "] " + sm);
        if (promptToday && total > 0 && signed == total) {
            String pd = "";
            try { pd = prefs.getString("jmb_prompt_day", ""); } catch (Throwable _e20) { noteSwallowed("trySignAllFor", _e20); }
            if (!today.equals(pd)) {
                try { prefs.edit().putString("jmb_prompt_day", today).apply(); } catch (Throwable _e21) { noteSwallowed("trySignAllFor", _e21); }
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
                    if (!isAccountEnabled(i)) {
                        jlog("账号" + (i + 1) + " 已停用，跳过");
                        if (rep.length() > 0) rep.append('\n');
                        rep.append(accountLabel(i) + "：已停用，跳过");
                        continue;
                    }
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
            if (rep.length() > 0) toastOnce("allacc|" + todayStr() + "|" + rep.length(), Lang.tf("全账号签到\n{0}", rep));
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
                int cur = prefs.getInt(kRetry(prefix, id), 0);
                if (cur > 0 && !todayStr().equals(prefs.getString(kRetryDay(prefix, id), ""))) cur = 0;
                if (cur >= RETRY_LIMIT) {   // 已达上限：不再排重试，明确标注今日放弃
                    prefs.edit().putLong(kRetryAt(prefix, id), 0L).apply();
                    logw(why + "：" + targetTitle(entryDid(entry)) + " · " + accountLabel(account)
                            + " · 今日已试 " + cur + " 次达上限，今日不再重试");
                    return;
                }
                long wait = backoffDelay(cur);
                prefs.edit().putInt(kRetry(prefix, id), cur + 1)
                        .putString(kRetryDay(prefix, id), todayStr())
                        .putLong(kRetryAt(prefix, id), System.currentTimeMillis() + wait)
                        .apply();
                logw(why + "：" + targetTitle(entryDid(entry)) + " · " + accountLabel(account)
                        + " · 第 " + (cur + 1) + "/" + RETRY_LIMIT + " 次，" + (wait / 60000L) + " 分钟后再试");
            } catch (Throwable t) { logd("记录失败状态异常: " + t); }
        }

        /** 明显签不成的目标（群/频道、被删的 bot）当天放弃，避免无意义重跑 */
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
    /** 从宿主读取群/频道的显示名（拿不到返回 null）。 */
    private String chatTitle(long did, int account) {
        try {
            Object mc = getMessagesController(account);
            if (mc == null) return null;
            long raw = -did;
            boolean isChannel = raw > 1000000000000L;
            long bare = isChannel ? (raw - 1000000000000L) : raw;
            Object chat = null;
            try { chat = invoke(mc, "getChat", new Class<?>[]{Long.class}, new Object[]{Long.valueOf(bare)}); } catch (Throwable ignored) {}
            if (chat == null) { try { chat = invoke(mc, "getChat", new Class<?>[]{Long.class}, new Object[]{Long.valueOf(did)}); } catch (Throwable ignored) {} }
            if (chat == null) return null;
            Object t = getFieldValSafe(chat, "title");
            if (t == null) t = getFieldValSafe(chat, "username");
            String v = t == null ? null : String.valueOf(t);
            return (v == null || v.isEmpty() || "null".equals(v)) ? null : v;
        } catch (Throwable t) { return null; }
    }

    /** 目标显示名：群用条目备注名，其它用 bot 名。 */
    private String entryDisplayName(Map<String, Object> m) {
        long did = entryDid(m);
        // ① 条目自带备注名
        try {
            String t = entryTitle(m);
            if (t != null && !t.isEmpty()) return t;
        } catch (Throwable ignored) {}
        // ② 群 / 频道：实时查群名（查到就回写条目，下次直接用）
        if ("chat".equals(entryPeerKind(m))) {
            String ct = chatTitle(did, currentAccount());
            if (ct != null && !ct.isEmpty()) {
                try { cacheEntryTitle(m, ct); } catch (Throwable ignored) {}
                return ct;
            }
            // ③ 群名也拿不到时，从 nameCache 里找（面板/学习阶段可能记过）
            if (nameCache.containsKey(did)) {
                String cn = nameCache.get(did);
                if (cn != null && cn.length() > 0) return cn;
            }
            return Lang.tf("群 {0}", did);   // 至少标明这是群，不裸露数字
        }
        // ④ bot / 用户
        String bn = botName(did);
        return (bn != null && bn.length() > 0) ? bn : String.valueOf(did);
    }

    /** 把查到的名字写回条目（title_<id>），避免每次重查。 */
    private void cacheEntryTitle(Map<String, Object> m, String title) {
        try {
            String id = entryId(m);
            if (id == null || id.isEmpty() || title == null || title.isEmpty()) return;
            String cur = prefs.getString(accountPrefix() + "title_" + id, "");
            if (title.equals(cur)) return;
            prefs.edit().putString(accountPrefix() + "title_" + id, title).apply();
            m.put("title", title);
        } catch (Throwable ignored) {}
    }

    private String botName(long did) {
        // 只缓存**取到的**名字：以前无论成功失败都 put，导致第一次取不到时
        // 把 null 缓存住，之后永远显示数字 ID（用户反馈"排除列表只能获取一次名字"）。
        String cached = nameCache.get(did);
        if (cached != null && cached.length() > 0) return cached;
        String name = null;
        try {
            Object u = getUserObject(did);
            if (u != null) {
                // 显示名优先 first_name，其次 username，其次 last_name
                name = firstNonEmpty(getFieldVal(u, "first_name"), getFieldVal(u, "username"), getFieldVal(u, "last_name"));
            }
        } catch (Throwable ignored) {}
        if (name != null && name.length() > 0) nameCache.put(did, name);   // 只缓存成功结果
        return name;
    }

    /** 取 User 对象（先内存缓存，再磁盘）。 */
    private Object getUserObject(long did) {
        Object u = null;
        try {
            Object mc = getMessagesController();
            if (mc != null) {
                try { u = invoke(mc, "getUser", new Class<?>[]{Long.class}, new Object[]{did}); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        if (u == null) {
            try {
                Object ms = getMessagesStorage();
                if (ms != null) {
                    try { u = invoke(ms, "getUser", new Class<?>[]{long.class}, new Object[]{did}); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return u;
    }

    /** bot 的 @username（可读、能猜用途）。没有则返回 null。 */
    private String botUsername(long did) {
        try {
            Object u = getUserObject(did);
            if (u == null) return null;
            String un = firstNonEmpty(getFieldVal(u, "username"));
            return (un != null && un.length() > 0) ? un : null;
        } catch (Throwable ignored) { return null; }
    }

    private static String firstNonEmpty(Object... vals) {
        for (Object v : vals) {
            if (v == null) continue;
            String s = String.valueOf(v);
            if (s.length() > 0 && !"null".equals(s)) return s;
        }
        return null;
    }

    /** 目标显示标题：备注名 > bot 显示名 > @username > 群名，不再裸露数字 ID。 */
    private String targetTitle(long did) {
        // 群 / 频道：先查群名
        if (did < 0) {
            String ct = chatTitle(did, currentAccount());
            if (ct != null && !ct.isEmpty()) return ct;
            if (nameCache.containsKey(did)) {
                String cn = nameCache.get(did);
                if (cn != null && cn.length() > 0) return cn;
            }
            return Lang.tf("群 {0}", did);
        }
        // bot / 用户：优先显示名，其次 @username
        String n = botName(did);
        if (n == null || n.length() == 0) {
            String u = botUsername(did);
            if (u != null && u.length() > 0) return "@" + u;
            return Lang.tf("bot {0}", did);
        }
        return n;
    }

    /** 目标副标题：@username（可读、能猜用途）；没有 username 才退回数字 ID。 */
    private String targetSubtitle(long did) {
        if (did < 0) return String.valueOf(did); // 群直接显示 ID
        String u = botUsername(did);
        if (u != null && u.length() > 0) return "@" + u;
        return "id " + did;
    }

    private String statusOf(String prefix, String id, String today) {
        String lastSign = prefs.getString(kLast(prefix, id), "");
        if (today.equals(lastSign)) return Lang.tr("已签");
        // 待确认：发过、没回复、已停止重试 —— 不能显示成"未签"（像没做），
        // 也不能显示成"已签"（骗人）。单独一个状态让用户自己判断。
        if (isPendingConfirm(prefix, id)) return Lang.tr("待确认");
        int retries = prefs.getInt(kRetry(prefix, id), 0);
        if (retries >= RETRY_LIMIT) return Lang.tr("已放弃");
        long retryAt = prefs.getLong(kRetryAt(prefix, id), 0);
        if (System.currentTimeMillis() < retryAt) return Lang.tr("退避中");
        if (retries > 0) return Lang.tr("重试中");
        return Lang.tr("待签");
    }

    // 状态优先级：待处理(待签/重试/退避)排前，已签/已放弃沉底。数值越小越靠前。
    private int statusRank(String status) {
        if (status == null) return 0;
        if (status.contains("已签") || status.contains("放弃")) return 2;
        if (status.contains("待确认")) return 1;   // 需要人看一眼，但不算"待办"
        return 0;
    }

    // 按当前排序模式返回有序快照
    // ── v1.5.7 计数口径：冻结/排除的目标不参与签到，也不该拖累进度分母 ──
    /** 该条目是否「不参与签到」（冻结 或 所在 bot 被排除） */
    private boolean isInactive(String prefix, Map<String, Object> m) {
        try {
            if (isFrozen(prefix, entryId(m))) return true;
            if (isBotBlocked(entryDid(m))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 活跃目标数（进度分母）：总数 − 冻结 − 排除的 bot */
    private int activeTargetCount(String prefix, List<Map<String, Object>> list) {
        int c = 0;
        for (Map<String, Object> m : list) {
            try { if (!isInactive(prefix, m)) c++; } catch (Throwable ignored) {}
        }
        return c;
    }

    /** 活跃目标里今天已签数（进度分子） */
    private int activeSignedCount(String prefix, List<Map<String, Object>> list, String today) {
        int c = 0;
        for (Map<String, Object> m : list) {
            try {
                if (isInactive(prefix, m)) continue;
                if (today.equals(prefs.getString(kLast(prefix, entryId(m)), ""))) c++;
            } catch (Throwable ignored) {}
        }
        return c;
    }

    private List<Map<String, Object>> sortedTargets() {
        List<Map<String, Object>> l = targetsSnapshot();
        String today = todayStr();
        if ("name".equals(SORT_MODE)) {
            java.util.Collections.sort(l, new java.util.Comparator<Map<String, Object>>() {
                @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                    int ia = isInactive(accountPrefix(), a) ? 1 : 0;
                    int ib = isInactive(accountPrefix(), b) ? 1 : 0;
                    if (ia != ib) return ia - ib;
                    String na = botName(entryDid(a));
                    String nb = botName(entryDid(b));
                    if (na == null) na = "";
                    if (nb == null) nb = "";
                    return na.compareToIgnoreCase(nb);
                }
            });
        } else {
            java.util.Collections.sort(l, new java.util.Comparator<Map<String, Object>>() {
                @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                    // v1.5.7：冻结/排除的沉底，活跃目标永远排在前面
                    int ia = isInactive(accountPrefix(), a) ? 1 : 0;
                    int ib = isInactive(accountPrefix(), b) ? 1 : 0;
                    if (ia != ib) return ia - ib;
                    int ra = statusRank(statusOf(accountPrefix(), entryId(a), today));
                    int rb = statusRank(statusOf(accountPrefix(), entryId(b), today));
                    if (ra != rb) return ra - rb;
                    String na = botName(entryDid(a));
                    String nb = botName(entryDid(b));
                    if (na == null) na = "";
                    if (nb == null) nb = "";
                    return na.compareToIgnoreCase(nb);
                }
            });
        }
        return l;
    }

    private int dp(float value) {
        return Math.max(1, (int) (android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, value, appContext.getResources().getDisplayMetrics()) + 0.5f));
    }

    private View menuTile(Activity act, String icon, String title, String sub, String action) {
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
        // 图标：优先代码矢量图标；名字未定义时回退成字符（保证不漏改也不空白）
        android.widget.ImageView icv = iconView(act, icon, 20f, iconColorOf(act, action));
        if (icv != null) {
            v.addView(icv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        } else {
            TextView em = new TextView(act); em.setText(icon); em.setTextSize(20); em.setGravity(android.view.Gravity.CENTER);
            v.addView(em);
        }
        TextView t = new TextView(act); t.setText(Lang.tr(title)); t.setTextSize(Theme.TS_SECOND);
        t.setTextColor(Theme.termTxt(act)); t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        t.setGravity(android.view.Gravity.CENTER); t.setPadding(0, dp(3), 0, 0);
        v.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(act); s.setText(Lang.tr(sub)); s.setTextSize(Theme.TS_CAPTION);
            s.setTextColor(Theme.termMuted(act)); s.setTypeface(android.graphics.Typeface.MONOSPACE);
            s.setGravity(android.view.Gravity.CENTER); s.setPadding(0, dp(2), 0, 0);
            v.addView(s);
        }
        return v;
    }

    private void addTile(android.widget.GridLayout grid, Activity act, String icon, String title, String sub, String action) {
        View v = menuTile(act, icon, title, sub, action);
        android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        v.setLayoutParams(lp);
        if (!fastMainOpen) {
            // 动画节流：只对前 6 个 tile 做入场，delay 15ms 递增（原来 19 个 × 40ms 要等 760ms）
            int n = grid.getChildCount();
            if (n >= 6) { grid.addView(v); return; }
            final int delay = n * 15;
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

    /** 分组小标题（终端风，青色等宽） */
    // 分类入口 chip（主菜单横滑一行） */
    private View catChip(Context c, final String catAction, String label, String icon) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        box.setBackground(termBorder(c, Theme.withAlpha(Theme.termCyan(c), 0x10),
                Theme.withAlpha(Theme.termCyan(c), 0x40)));
        box.setPadding(Theme.dp(c, 10), Theme.dp(c, 8), Theme.dp(c, 12), Theme.dp(c, 8));
        android.widget.ImageView iv = iconView(c, icon, 14f, Theme.termCyan(c));
        if (iv != null) {
            box.addView(iv, new LinearLayout.LayoutParams(Theme.dp(c, 14), Theme.dp(c, 14)));
            box.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c, 6), 1));
        }
        TextView t = new TextView(c);
        t.setText(Lang.tr(label));
        t.setTextSize(Theme.TS_SECOND);
        t.setTextColor(Theme.termTxt(c));
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        box.addView(t);
        box.setTag(catAction);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runAction(v.getContext(), String.valueOf(v.getTag())); }
        });
        return box;
    }

    private android.widget.LinearLayout.LayoutParams catChipLp(Context c) {
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Theme.dp(c, 6);
        return lp;
    }

    private void sectionHeader(LinearLayout parent, Activity act, String text) {
        TextView h = new TextView(act);
        h.setText(Lang.tr(text));
        h.setTextSize(Theme.TS_CAPTION);
        h.setTextColor(Theme.termCyan(act));
        h.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        h.setPadding(dp(8), dp(14), dp(8), dp(4));
        parent.addView(h);
    }

    /** 列表排序切换 chip，当前项高亮 */
    private View sortChip(Activity act, String label, String mode) {
        boolean on = mode.equals(SORT_MODE);
        TextView chip = new TextView(act);
        chip.setText(Lang.tr(label));
        chip.setTextSize(Theme.TS_CAPTION);
        chip.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setTextColor(on ? Theme.termTxt(act) : Theme.termMuted(act));
        chip.setBackground(termBorder(act, on ? Theme.withAlpha(Theme.termCyan(act), 0x1E) : Theme.withAlpha(Theme.termMuted(act), 0x0D), on ? Theme.withAlpha(Theme.termCyan(act), 0x66) : Theme.withAlpha(Theme.termMuted(act), 0x33)));
        chip.setClickable(true);
        chip.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
            SORT_MODE = mode;
            try { prefs.edit().putString("jmb_sort", mode).apply(); } catch (Throwable ignored) {}
            showList(act);
        } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), 0, dp(3), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private View menuItem(LinearLayout parent, String icon, String title, String subtitle, String action) {
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
        // 左侧图标块：矢量图标 + 淡青方框；未定义名字回退成字符
        int icColor = iconColorOf(c, action);
        android.widget.ImageView icv = iconView(c, icon, 20f, icColor);
        int boxSz = Theme.dp(c, 40);
        if (icv != null) {
            icv.setPadding(Theme.dp(c, 10), Theme.dp(c, 10), Theme.dp(c, 10), Theme.dp(c, 10));
            icv.setBackground(termBorder(c, Theme.withAlpha(icColor, 0x16), Theme.withAlpha(icColor, 0x33)));
            row.addView(icv, new LinearLayout.LayoutParams(boxSz, boxSz));
        } else {
            TextView em = new TextView(c);
            em.setText(icon); em.setTextSize(18); em.setGravity(Gravity.CENTER);
            em.setBackground(termBorder(c, Theme.withAlpha(Theme.termCyan(c), 0x16), Theme.withAlpha(Theme.termCyan(c), 0x33)));
            row.addView(em, new LinearLayout.LayoutParams(boxSz, boxSz));
        }
        row.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,12), 1));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(c); t1.setTextSize(Theme.TS_SUBTITLE); t1.setTextColor(Theme.termTxt(c)); t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); t1.setText(Lang.tr(title)); col.addView(t1);
        TextView t2 = new TextView(c); t2.setTextSize(Theme.TS_CAPTION); t2.setTextColor(Theme.termMuted(c)); t2.setTypeface(android.graphics.Typeface.MONOSPACE);
        if (subtitle != null && subtitle.length() > 0) t2.setText(Lang.tr(subtitle)); else t2.setVisibility(View.GONE);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageView arv = iconView(c, "chevron-r", 14f, Theme.termCyan(c));
        if (arv != null) {
            row.addView(arv, new LinearLayout.LayoutParams(Theme.dp(c,14), Theme.dp(c,14)));
        } else {
            TextView ar = new TextView(c); ar.setTextSize(18); ar.setText("\u203a"); ar.setTextColor(Theme.termCyan(c)); row.addView(ar);
        }
        parent.addView(row);
        return row;
    }

    /** 通用小徽章：文字 + 主题色（背景/描边由颜色派生）。 */
    private TextView badgeChip(Context c, String text, int col, boolean solid) {
        TextView chip = new TextView(c);
        chip.setTextSize(Theme.TS_CAPTION);
        chip.setText(Lang.tr(text));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(Theme.dp(c,6), Theme.dp(c,2), Theme.dp(c,6), Theme.dp(c,2));
        chip.setTextColor(solid ? Theme.termCardDeep(c) : col);
        chip.setTypeface(Theme.monoBold());
        chip.setBackground(termBorder(c,
                solid ? col : Theme.withAlpha(col, 0x12),
                Theme.withAlpha(col, 0x66)));
        return chip;
    }

    /**
     * 条目类型：{主类型, 投递方式}
     *   主类型：bot=私聊机器人 / chat=群或频道
     *   投递：cb=回调按钮 / text=文本指令
     */
    private String[] entryTypeOf(Map<String, Object> m) {
        String main = "chat".equals(entryPeerKind(m)) ? "chat" : "bot";
        String how = KIND_CB.equals(entryKind(m)) ? "cb" : "text";
        return new String[]{main, how};
    }

    /** 目标类型徽章：群/频道（品红实心）与 bot（青色描边）一眼可分，图标用矢量。 */
    private TextView peerChip(Context c, Map<String, Object> m) {
        boolean isChat = "chat".equals(entryPeerKind(m));
        int col = isChat ? Theme.termPink(c) : Theme.termCyan(c);
        String icon = isChat ? "group" : "bot";
        String label = isChat ? Lang.tr(" 群") : " bot";
        TextView chip = badgeChip(c, label, col, isChat);
        android.graphics.drawable.Drawable ic = Icons.d(c, icon, 11f, isChat ? Theme.termCardDeep(c) : col);
        if (ic != null) {
            int sz = Theme.dp(c, 11);
            ic.setBounds(0, 0, sz, sz);
            chip.setCompoundDrawables(ic, null, null, null);
            chip.setCompoundDrawablePadding(Theme.dp(c, 3));
        }
        return chip;
    }

    private TextView typeChip(Context c, boolean cb) {
        TextView chip = new TextView(c);
        chip.setTextSize(Theme.TS_CAPTION);
        chip.setText(cb ? Lang.tr("[回调]") : Lang.tr("[指令]"));
        chip.setGravity(android.view.Gravity.CENTER);
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
        // 左侧色条：群=品红，bot=青，扫一眼即可分组
        String[] etype = entryTypeOf(entry);
        boolean isChat = !"bot".equals(etype[0]);
        int accent = isChat ? Theme.termPink(c) : Theme.termCyan(c);
        final boolean blocked = isBotBlocked(did);
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.setMargins(Theme.dp(c,3), Theme.dp(c,4), Theme.dp(c,3), Theme.dp(c,4));
        wrap.setLayoutParams(wlp);
        View barView = new View(c);
        android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
        bgd.setColor(accent);
        bgd.setCornerRadius(Theme.dp(c,2));
        barView.setBackground(bgd);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Theme.dp(c,3), LinearLayout.LayoutParams.MATCH_PARENT);
        blp.setMargins(0, Theme.dp(c,3), 0, Theme.dp(c,3));
        wrap.addView(barView, blp);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(termBorder(c, Theme.termCard(c), Theme.withAlpha(accent, 0x2E)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        row.setLayoutParams(lp);
        row.setPadding(Theme.dp(c,11), Theme.dp(c,11), Theme.dp(c,12), Theme.dp(c,11));
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
        String mainTitle = entryTitle(entry);
        if (mainTitle == null || mainTitle.length() == 0) mainTitle = targetTitle(did);
        TextView t1 = new TextView(c); t1.setTextSize(Theme.TS_SUBTITLE); t1.setTextColor(Theme.termTxt(c)); t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); t1.setText(mainTitle);
        t1.setSingleLine(true); t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tl.addView(t1, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tl.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,6), 1));
        tl.addView(peerChip(c, entry));
        tl.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,4), 1));
        tl.addView(typeChip(c, cb));
        if (isFrozen(accountPrefix(), id)) {
            tl.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,4), 1));
            TextView fzChip = badgeChip(c, " 冻结", Theme.termAmber(c), false);
            android.graphics.drawable.Drawable fzIc = Icons.d(c, "pause", 10f, Theme.termAmber(c));
            if (fzIc != null) {
                int sz = Theme.dp(c, 10);
                fzIc.setBounds(0, 0, sz, sz);
                fzChip.setCompoundDrawables(fzIc, null, null, null);
                fzChip.setCompoundDrawablePadding(Theme.dp(c, 3));
            }
            tl.addView(fzChip);
        }
        if (blocked) {
            tl.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,4), 1));
            TextView blChip = badgeChip(c, " 已排除", Theme.termMuted(c), false);
            android.graphics.drawable.Drawable blIc = Icons.d(c, "x", 10f, Theme.termMuted(c));
            if (blIc != null) {
                int sz = Theme.dp(c, 10);
                blIc.setBounds(0, 0, sz, sz);
                blChip.setCompoundDrawables(blIc, null, null, null);
                blChip.setCompoundDrawablePadding(Theme.dp(c, 3));
            }
            tl.addView(blChip);
        }
        col.addView(tl);
        // 副标题行：@username（可读、能猜用途），仅 bot 且非群时显示
        if (did > 0) {
            String sub = targetSubtitle(did);
            if (sub != null && sub.length() > 0 && !sub.startsWith("id ")) {
                TextView ts = new TextView(c); ts.setTextSize(Theme.TS_CAPTION); ts.setTextColor(Theme.termFaint(c)); ts.setTypeface(android.graphics.Typeface.MONOSPACE);
                ts.setSingleLine(true); ts.setEllipsize(android.text.TextUtils.TruncateAt.END);
                ts.setText(sub);
                ts.setPadding(Theme.dp(c,1), 0, 0, 0);
                col.addView(ts);
            }
        }
        // 第二行：状态(带色) + 指令(省略) + 上次(右对齐小字)
        LinearLayout t2r = new LinearLayout(c); t2r.setOrientation(LinearLayout.HORIZONTAL); t2r.setGravity(Gravity.CENTER_VERTICAL);
        TextView st = new TextView(c); st.setTextSize(Theme.TS_CAPTION); st.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        int stCol = Theme.termTxt(c);
        if (status != null && status.contains("已签")) stCol = Theme.termGreen(c);
        else if (status != null && (status.contains("退避") || status.contains("重试"))) stCol = Theme.termAmber(c);
        else if (status != null && status.contains("放弃")) stCol = Theme.termMuted(c);
        st.setTextColor(stCol);
        st.setText(Lang.tr(status));
        // 状态前导图标：已签=勾、待签=钟、退避/重试=警告
        String stIcon = null;
        if (status != null) {
            if (status.contains("已签")) stIcon = "check";
            else if (status.contains("待签")) stIcon = "clock";
            else if (status.contains("退避") || status.contains("重试")) stIcon = "warn";
        }
        if (stIcon != null) {
            android.graphics.drawable.Drawable sd = Icons.d(c, stIcon, 12f, stCol);
            if (sd != null) {
                int ssz = Theme.dp(c, 12);
                sd.setBounds(0, 0, ssz, ssz);
                st.setCompoundDrawables(sd, null, null, null);
                st.setCompoundDrawablePadding(Theme.dp(c, 4));
            }
        }
        t2r.addView(st, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        t2r.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,8), 1));
        TextView tx = new TextView(c); tx.setTextSize(Theme.TS_CAPTION); tx.setTextColor(Theme.termMuted(c)); tx.setTypeface(android.graphics.Typeface.MONOSPACE);
        tx.setSingleLine(true); tx.setEllipsize(android.text.TextUtils.TruncateAt.END); tx.setText(text);
        t2r.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String lastT = prefs.getString(kLast(accountPrefix(), id), "");
        if (lastT.length() > 0) {
            TextView lt = new TextView(c); lt.setTextSize(Theme.TS_CAPTION); lt.setTextColor(Theme.termFaint(c)); lt.setTypeface(Theme.text());
            lt.setText(lastT); lt.setPadding(Theme.dp(c,6), 0, 0, 0);
            t2r.addView(lt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        col.addView(t2r);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ar = new TextView(c); ar.setTextSize(18); ar.setText("\u203a"); ar.setTextColor(accent); row.addView(ar);
        wrap.addView(row);
        // v1.5.7：冻结 或 所在 bot 被排除 → 整行虚化（两者视觉一致）
        boolean frozenRow = false;
        try { frozenRow = isFrozen(accountPrefix(), id); } catch (Throwable ignored2) {}
        if (blocked || frozenRow) {
            wrap.setAlpha(0.45f);
        }
        // 点击挂在最外层，保证色条区域也可点
        if (action != null) {
            wrap.setTag(action + "|" + id);
            wrap.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String tag = String.valueOf(v.getTag());
                    String[] parts = tag.split("\\|");
                    runTargetAction(v.getContext(), parts[0], parts.length > 1 ? parts[1] : "");
                }
            });
        }
        parent.addView(wrap);
        return wrap;
    }

    private static long lastMainOpen = 0L;
    private boolean fastMainOpen = false;
    private volatile boolean inSendReq = false;
    private volatile long bootReadyAt = 0L;

    private boolean notReadyYet() {
        return System.currentTimeMillis() < bootReadyAt;
    }

    /** 更多功能：低频入口集中页（把主菜单从功能大全变成今日签到面板） */
    // 分类面板：由主菜单分类 chip 直达 */
    private void showCategory(Activity act, String cat) {
        try {
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Theme.dp(act,10), Theme.dp(act,6), Theme.dp(act,10), Theme.dp(act,6));

            boolean all = cat == null;
            if (all || "target".equals(cat)) {
            sectionHeader(root, act, "▍目标");
            android.widget.GridLayout g1 = new android.widget.GridLayout(act); g1.setColumnCount(2); root.addView(g1);
            addTile(g1, act, "plus", "添加目标", "指令 / 捕获按钮", "add");
            addTile(g1, act, "trash", "删除目标", "移除条目", "del");
            addTile(g1, act, "copy", "复制目标", "给其它账号", "copy_targets");
            addTile(g1, act, "globe", "签全部账号", Lang.tf("{0} 个账号", activatedAccounts()), "sign_all_accounts");
            addTile(g1, act, "layers", "预设模板", "一键添加", "presets");
            }

            if (all || "data".equals(cat)) {
            sectionHeader(root, act, "▍数据");
            android.widget.GridLayout g2 = new android.widget.GridLayout(act); g2.setColumnCount(2); root.addView(g2);
            addTile(g2, act, "receipt", "导出日志", "到下载目录", "export_log");
            addTile(g2, act, "upload", "导出配置", "json 备份", "export");
            addTile(g2, act, "download", "导入配置", "合并或覆盖", "import");
            }

            if (all || "system".equals(cat)) {
            sectionHeader(root, act, "▍诊断");
            android.widget.GridLayout g3 = new android.widget.GridLayout(act); g3.setColumnCount(2); root.addView(g3);
            addTile(g3, act, "flask", "回调调试台", "按钮·发射·绑定", "debug");
            addTile(g3, act, "pulse", "自诊断", "反射锚点检查", "diag");
            String upSub = (lastUpdate != null && lastUpdate.newer) ? "新版本 v" + lastUpdate.version : "当前 v" + UpdateChecker.VERSION_NAME;
            addTile(g3, act, "refresh", "检查更新", upSub, "update");
            }

            if (all || "help".equals(cat)) {
            sectionHeader(root, act, "▍帮助");
            android.widget.GridLayout g4 = new android.widget.GridLayout(act); g4.setColumnCount(2); root.addView(g4);
            addTile(g4, act, "book", "使用教程", "快速上手", "tutorial");
            addTile(g4, act, "megaphone", "加入群组", "反馈·交流·帮助", "join_group");
            addTile(g4, act, "upload", "分享本模块", "推荐给朋友", "share");
            }

            if (all || "maintain".equals(cat)) {
            sectionHeader(root, act, "▍维护");
            android.widget.GridLayout g5 = new android.widget.GridLayout(act); g5.setColumnCount(2); root.addView(g5);
            addTile(g5, act, "clean", "清空配置", "跨账号彻底清", "clear_all");
            }

            ScrollView scv = new ScrollView(act);
            scv.addView(root, new android.widget.ScrollView.LayoutParams(-1, -2));
            String catName = "target".equals(cat) ? "目标管理"
                    : "data".equals(cat) ? "数据备份"
                    : "system".equals(cat) ? "诊断与更新"
                    : "help".equals(cat) ? "帮助"
                    : "maintain".equals(cat) ? "维护" : "全部功能";
            showDialog(act, catName, scv, "关闭");
        } catch (Throwable t) { logd("更多页异常: " + t); }
    }

    private void showMainMenu(Activity act) {

        long now0 = System.currentTimeMillis();
        fastMainOpen = now0 - lastMainOpen < 15000L;
        lastMainOpen = now0;
        TITLE_FX = (TITLE_FX + 1) % 4;
        try { prefs.edit().putInt("jmb_fx", TITLE_FX).apply(); } catch (Throwable ignored) {}

        syncAccount();

        if (act == null) { toast("请在 Telegram 界面使用 /jmb"); return; }
        // 主题诊断：来源变化时记一条，方便排查"为什么是这个配色"
        try {
            boolean dk = Theme.dark(act);
            String sig = Theme.lastHow + "/" + dk;
            if (!sig.equals(lastThemeSig)) {
                lastThemeSig = sig;
                jlog("[主题] 来源=" + Theme.lastHow + " dark=" + dk + " color=#" + Integer.toHexString(Theme.lastColor) + " 模式=" + THEME_MODE);
            }
        } catch (Throwable ignored) {}
        String today = todayStr();
        int signed = 0;
        for (Map<String, Object> m : targetsSnapshot()) {
            if (today.equals(prefs.getString(kLast(accountPrefix(), entryId(m)), ""))) signed++;
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
        final android.os.Handler th = new android.os.Handler(act.getMainLooper());
        final float dens = act.getResources().getDisplayMetrics().density;
        final java.util.Random rnd = new java.util.Random();
        final int[] PAL = { Theme.termCyan(act), Theme.termGreen(act), Theme.termPink(act), Theme.termAmber(act) };
        final int cyanC = Theme.termCyan(act), greenC = Theme.termGreen(act), pinkC = Theme.termPink(act), amberC = Theme.termAmber(act);
        final int flashCol = Theme.dark(act) ? 0xFFFFFFFF : 0xFF001820;
        final java.util.List<TextView> chs = new java.util.ArrayList<>();
        for (int wi = 0; wi < wtitle.length(); ) {
            int cp = wtitle.codePointAt(wi);
            wi += Character.charCount(cp);
            final TextView chv = new TextView(act);
            chv.setTextSize(23); chv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            chv.setTextColor(cyanC); chv.setText(new String(Character.toChars(cp)));
            floatRow.addView(chv);
            chs.add(chv);
        }
        final Runnable fx;
        final int fxKind = TITLE_FX;
        final Runnable flow;
        if (fxKind == 1) {
            // 1 逐字波浪：正弦起伏，波峰亮、波谷暗
            flow = new Runnable() {
                long frame = 0L;
                @Override public void run() {
                    try {
                        if (!floatRow.isShown()) { th.removeCallbacks(this); return; }
                        int n = chs.size();
                        for (int i = 0; i < n; i++) {
                            float ph = (float)((frame * 0.11f + i * 0.55f) % (2f * Math.PI));
                            float sin = (float)Math.sin(ph);
                            chs.get(i).setTranslationY(sin * dens * 3.2f);
                            chs.get(i).setTextColor(Theme.mix(greenC, cyanC, (sin + 1f) / 2f));
                        }
                        frame++;
                        th.postDelayed(this, 40L);
                    } catch (Throwable ignored) {}
                }
            };
        } else if (fxKind == 2) {
            // 2 RGB 流光：四色环推进，字符相位错开
            flow = new Runnable() {
                long frame = 0L;
                @Override public void run() {
                    try {
                        if (!floatRow.isShown()) { th.removeCallbacks(this); return; }
                        float phase0 = (frame % 250) / 250f;
                        for (int i = 0; i < chs.size(); i++) {
                            float p = (phase0 + i * 0.28f) % 1f;
                            float seg = p * 4f;
                            int a = (int) seg;
                            chs.get(i).setTextColor(Theme.mix(PAL[a % 4], PAL[(a + 1) % 4], seg - a));
                        }
                        frame++;
                        th.postDelayed(this, 40L);
                    } catch (Throwable ignored) {}
                }
            };
        } else if (fxKind == 3) {
            // 3 键盘敲击：随机字符下沉回弹 + 变色
            flow = new Runnable() {
                long frame = 0L;
                @Override public void run() {
                    try {
                        if (!floatRow.isShown()) { th.removeCallbacks(this); return; }
                        int i = rnd.nextInt(chs.size());
                        final TextView c = chs.get(i);
                        c.setTranslationY(dens * 3f);
                        c.setTextColor(flashCol);
                        c.postDelayed(new Runnable() { @Override public void run() {
                            c.setTranslationY(0f); c.setTextColor(Theme.termCyan(c.getContext()));
                        } }, 120L);
                        frame++;
                        th.postDelayed(this, 220L + rnd.nextInt(320));
                    } catch (Throwable ignored) {}
                }
            };
        } else {
            // 0 霓虹呼吸：青↔绿缓慢呼吸，相位错开
            flow = new Runnable() {
                long frame = 0L;
                @Override public void run() {
                    try {
                        if (!floatRow.isShown()) { th.removeCallbacks(this); return; }
                        int n = chs.size();
                        for (int i = 0; i < n; i++) {
                            float ph = (float)((frame * 0.035f + i * 0.45f) % (2f * Math.PI));
                            float v = (float)((Math.sin(ph) + 1f) / 2f);
                            chs.get(i).setTextColor(Theme.mix(greenC, cyanC, v));
                        }
                        frame++;
                        th.postDelayed(this, 50L);
                    } catch (Throwable ignored) {}
                }
            };
        }
        fx = flow;
        floatRow.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View vv) { th.removeCallbacks(fx); th.postDelayed(fx, 0L); }
            @Override public void onViewDetachedFromWindow(android.view.View vv) { th.removeCallbacks(fx); }
        });
        titleRow.addView(floatRow, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView start = new TextView(act); start.setText("[START]"); start.setTextSize(Theme.TS_SECOND); start.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
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
        final TextView sv = new TextView(act); sv.setTextSize(Theme.TS_SECOND); sv.setTextColor(Theme.termMuted(act));
        sv.setTypeface(android.graphics.Typeface.MONOSPACE);
        String sign = (SIGN == null || SIGN.isEmpty()) ? "wlmosv" : SIGN;
        final String fullCmd = "$ tgas --v " + UpdateChecker.VERSION_NAME + "  ·  " + Lang.tf("{0} 出品", sign);
        final TextView cur = new TextView(act); cur.setTextSize(Theme.TS_SECOND); cur.setTextColor(Theme.termCyan(act));
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
                if (fastMainOpen) { sv.setText(fullCmd); cur.setVisibility(View.VISIBLE); th.postDelayed(blink, 300L); }
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
        TextView st1 = new TextView(act); st1.setTextSize(Theme.TS_BODY); st1.setTextColor(Theme.termCyan(act));
        st1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        {
            int aAll = targets.size();
            int aAct = activeTargetCount(accountPrefix(), targets);
            int aSig = activeSignedCount(accountPrefix(), targets, today);
            int aOut = Math.max(0, aAll - aAct);
            st1.setText("\u25B8 " + accountLabel(currentAccount()) + "  ·  "
                    + Lang.tf("目标 {0} · 已签 {1}/{2}", aAll, aSig, aAct)
                    + (aOut > 0 ? Lang.tf("（{0} 个不参与）", aOut) : ""));
        }
        statCard.addView(st1);
        // 今日进度条：已签=绿，未签=底色，一眼看出进度
        try {
            int total = targets.size();
            LinearLayout pbar = new LinearLayout(act); pbar.setOrientation(LinearLayout.HORIZONTAL);
            android.graphics.drawable.GradientDrawable pbg = new android.graphics.drawable.GradientDrawable();
            pbg.setColor(Theme.withAlpha(Theme.termCyan(act), 0x22));
            pbg.setCornerRadius(dp(3));
            pbar.setBackground(pbg);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(6));
            plp.topMargin = dp(8);
            pbar.setLayoutParams(plp);
            // v1.5.7：分母只取活跃目标 —— 冻结/排除的完全不进条，
            // 因此有目标被冻结时，剩下的签完就是满条（绿=已签 / 青=待签）
            int actN = activeTargetCount(accountPrefix(), targets);
            int sigN = Math.min(activeSignedCount(accountPrefix(), targets, today), actN);
            int pendN = Math.max(0, actN - sigN);
            if (sigN > 0) {
                View f = new View(act);
                android.graphics.drawable.GradientDrawable fd = new android.graphics.drawable.GradientDrawable();
                fd.setColor(Theme.termGreen(act));
                fd.setCornerRadius(dp(3));
                f.setBackground(fd);
                pbar.addView(f, new LinearLayout.LayoutParams(0, dp(6), sigN));
            }
            if (pendN > 0) {
                View r = new View(act);
                android.graphics.drawable.GradientDrawable rd = new android.graphics.drawable.GradientDrawable();
                rd.setColor(Theme.withAlpha(Theme.termCyan(act), 0x66));
                rd.setCornerRadius(dp(3));
                r.setBackground(rd);
                pbar.addView(r, new LinearLayout.LayoutParams(0, dp(6), pendN));
            }
            statCard.addView(pbar);
        } catch (Throwable ignored) {}
        int streakN = streakOf(accountPrefix());
        TextView stS = new TextView(act); stS.setTextSize(Theme.TS_SECOND);
        stS.setTextColor(streakN > 0 ? Theme.termGreen(act) : Theme.termMuted(act));
        stS.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        stS.setPadding(0, dp(6), 0, 0);
        stS.setText(streakN > 0 ? Lang.tf("连续签到 {0} 天 · 最近 14 天", streakN) : Lang.tr("还没连续签到，今天去签一个"));
        statCard.addView(stS);
        // 定时模式：显示距下次签到倒计时
        if (TIMER_ENABLED) {
            String nx = nextTimerLabel();
            if (nx != null) {
                TextView stT = new TextView(act); stT.setTextSize(Theme.TS_CAPTION);
                stT.setTextColor(Theme.termAmber(act));
                stT.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                stT.setPadding(0, dp(4), 0, 0);
                stT.setText(Lang.tf("下次签到 {0}", nx));
                android.graphics.drawable.Drawable nd = Icons.d(act, "clock", 13f, Theme.termAmber(act));
                if (nd != null) {
                    int nsz = Theme.dp(act, 13);
                    nd.setBounds(0, 0, nsz, nsz);
                    stT.setCompoundDrawables(nd, null, null, null);
                    stT.setCompoundDrawablePadding(Theme.dp(act, 5));
                }
                statCard.addView(stT);
            }
        }
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
            TextView st3 = new TextView(act); st3.setTextSize(Theme.TS_CAPTION); st3.setTextColor(Theme.termMuted(act)); st3.setTypeface(android.graphics.Typeface.MONOSPACE); st3.setPadding(0, dp(8), 0, 0); st3.setText(plc); statCard.addView(st3);
        }
        if (!fastMainOpen) {
            statCard.setAlpha(0f); statCard.setTranslationY(Theme.dp(act, 8));
            statCard.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(android.view.View vv) { vv.postDelayed(new Runnable() { @Override public void run() { vv.animate().alpha(1f).translationY(0f).setDuration(220).start(); } }, 120L); }
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
        TextView tt = new TextView(act); tt.setTextSize(Theme.TS_CAPTION); tt.setTypeface(android.graphics.Typeface.MONOSPACE);
        tt.setTextColor(Theme.termGreen(act));
        tt.setText("$ tail -f ~/.logs/tgautosign");
        term.addView(tt);
        final LinearLayout termBody = new LinearLayout(act);
        termBody.setOrientation(LinearLayout.VERTICAL);
        term.addView(termBody);
        TextView tm = new TextView(act); tm.setTextSize(Theme.TS_CAPTION); tm.setTypeface(android.graphics.Typeface.MONOSPACE); tm.setTextColor(Theme.termCyan(act));
        tm.setText(Lang.tr("[更多日志] → 运行日志")); tm.setPadding(0, dp(6), 0, 0); tm.setClickable(true);
        tm.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ runAction(act, "log"); } });
        term.addView(tm);
        if (!fastMainOpen) {
            term.setAlpha(0f); term.setTranslationY(Theme.dp(act, 8));
            term.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(android.view.View vv) { vv.postDelayed(new Runnable() { @Override public void run() { vv.animate().alpha(1f).translationY(0f).setDuration(220).start(); } }, 180L); }
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
        // 图标放文字右侧：4 个 chip 等宽，把图标塞在左侧会把最长那项挤到折行
        String[][] QK = {{"sign", "\u7acb\u5373\u7b7e\u5230", "bolt"}, {"list", "\u76ee\u6807", "list"},
                         {"log", "\u65e5\u5fd7", "doc"}, {"diag", "\u81ea\u68c0", "pulse"}};
        for (final String[] q : QK) {
            TextView qb = new TextView(act); qb.setText(Lang.trShort(q[1])); qb.setTextSize(Theme.TS_CAPTION); qb.setTypeface(android.graphics.Typeface.MONOSPACE);
            qb.setTextColor(Theme.termCyan(act)); qb.setClickable(true);
            qb.setGravity(android.view.Gravity.CENTER);
            qb.setSingleLine(false); qb.setMaxLines(2);   // 英文比中文长：允许两行，不挤压布局
            android.graphics.drawable.Drawable qd = Icons.d(act, q[2], 12f, Theme.termCyan(act));
            if (qd != null) {
                int qsz = Theme.dp(act, 12);
                qd.setBounds(0, 0, qsz, qsz);
                qb.setCompoundDrawables(null, null, qd, null);
                qb.setCompoundDrawablePadding(Theme.dp(act, 4));
            }
            qb.setPadding(dp(4), dp(7), dp(4), dp(7));
            qb.setBackground(termBorder(act, Theme.withAlpha(Theme.termCyan(act), 0x12), Theme.withAlpha(Theme.termCyan(act), 0x59)));
            // 英文比中文长：给足两行的最小高度，避免折行后第二行被裁
            if (Lang.isEnglish()) { qb.setMinHeight(dp(46)); qb.setGravity(android.view.Gravity.CENTER); }
            qb.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ runAction(act, q[0]); } });
            quick.addView(qb, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        }
        head.addView(quick);
        root.addView(head);
        // ── 高频区：每天真的会点的 6 个入口（其余收进「更多功能」） ──
        sectionHeader(root, act, "▍签到");
        android.widget.GridLayout g1 = new android.widget.GridLayout(act); g1.setColumnCount(2); root.addView(g1);
        addTile(g1, act, "list", "目标列表", "查看·测试·编辑", "list");
        addTile(g1, act, "calendar", "补签列表", "今日待补·已补·跳过", "misslist");
        addTile(g1, act, "bolt", "立即签到", "当前账号", "sign");
        addTile(g1, act, "doc", "运行日志", "搜索·筛选·清空", "log");
        addTile(g1, act, "sliders", "设置", "定时·窗口·间隔", "settings");
        addTile(g1, act, "trash", "排除管理", "规则·排除 bot·待确认", "exclude");
        addTile(g1, act, "globe", "账号一览", Lang.tf("{0} 个账号", activatedAccounts()), "accounts");
        addTile(g1, act, "book", "使用教程", "上手·排障", "tutorial");

        // 分类直达：不用进二级页，横滑一行找到全部功能
        sectionHeader(root, act, "▍全部功能");
        android.widget.HorizontalScrollView catBar = new android.widget.HorizontalScrollView(act);
        catBar.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow = new LinearLayout(act);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        catBar.addView(catRow);
        String[][] CATS = {
                {"list", "目标", "cat:target"},
                {"doc", "数据", "cat:data"},
                {"pulse", "系统", "cat:system"},
                {"book", "帮助", "cat:help"},
                {"clean", "维护", "cat:maintain"}};
        for (int ci = 0; ci < CATS.length; ci++) {
            catRow.addView(catChip(act, CATS[ci][2], CATS[ci][1], CATS[ci][0]), catChipLp(act));
        }
        root.addView(catBar);

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
            logChip(act, chips, "回到最新", "chevron-u", Theme.termCyan(act), new Runnable() { @Override public void run() { jumpLogNewest(); } });
            final String[] targetNames = collectTargetNames();
            logChip(act, chips, logTarget.length() == 0 ? Lang.tr("目标：全部") : Lang.tf("目标：{0}", logTarget), "target", Theme.termAmber(act), new Runnable() { @Override public void run() {
                try {
                    if (targetNames.length == 0) { toast("还没有签到目标"); return; }
                    LinearLayout pick = new LinearLayout(act);
                    pick.setOrientation(LinearLayout.VERTICAL);
                    pick.setPadding(dp(8), dp(6), dp(8), dp(6));
                    TextView tip = new TextView(act); tip.setTextSize(Theme.TS_SECOND); tip.setTextColor(Theme.termMuted(act));
                    tip.setTypeface(android.graphics.Typeface.MONOSPACE);
                    tip.setText(Lang.tr("选择要查看日志的目标（点「全部」恢复）"));
                    tip.setPadding(dp(4), 0, dp(4), dp(8));
                    pick.addView(tip);
                    final Object[] dlg = new Object[1];
                    // 全部
                    TextView all = new TextView(act); all.setTextSize(Theme.TS_BODY); all.setTypeface(android.graphics.Typeface.MONOSPACE);
                    all.setPadding(dp(10), dp(8), dp(10), dp(8));
                    boolean allOn = logTarget.length() == 0;
                    all.setText((allOn ? "● " : "○ ") + Lang.tr("全部目标"));
                    all.setTextColor(allOn ? Theme.termCyan(act) : Theme.termTxt(act));
                    all.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                        logTarget = ""; refreshLog(); dismissOne(dlg[0]);
                    } });
                    pick.addView(all);
                    for (final String tn : targetNames) {
                        boolean on = tn.equals(logTarget);
                        TextView row = new TextView(act); row.setTextSize(Theme.TS_BODY); row.setTypeface(android.graphics.Typeface.MONOSPACE);
                        row.setPadding(dp(10), dp(8), dp(10), dp(8));
                        row.setText((on ? "● " : "○ ") + tn);
                        row.setTextColor(on ? Theme.termCyan(act) : Theme.termTxt(act));
                        row.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                            logTarget = tn; refreshLog(); dismissOne(dlg[0]);
                        } });
                        pick.addView(row);
                    }
                    dlg[0] = showDialog(act, "目标过滤", pick, "关闭");
                } catch (Throwable t) { toast(Lang.tf("打开目标选择失败: {0}", t)); }
            } });
            logChip(act, chips, "诊断包", "copy", Theme.termCyan(act), new Runnable() { @Override public void run() { doCopyDiagnose(act); } });
            logChip(act, chips, "清空", "trash", Theme.termPink(act), new Runnable() { @Override public void run() { confirmClearLog(act); } });
            logChip(act, chips, "导出", "upload", Theme.termCyan(act), new Runnable() { @Override public void run() { doExportLog(act); } });
            root.addView(bar);

            EditText q = new EditText(act);
            q.setSingleLine();
            q.setTextSize(Theme.TS_BODY);
            q.setHint(Lang.tr("搜索日志，边输边过滤"));
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
            logStat.setTextSize(Theme.TS_CAPTION);
            logStat.setTextColor(Theme.termMuted(act));
            root.addView(logStat);

            ScrollView sv = new ScrollView(act);
            logSv = sv;
            logList = new LinearLayout(act);
            logList.setOrientation(LinearLayout.VERTICAL);
            sv.addView(logList);
            // 高度自适应：屏高的 56%，比原来固定 340dp 能多看好几行
            int listH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.42f);
            root.addView(sv, new LinearLayout.LayoutParams(-1, listH));

            // 底部工具条：只留「加载更多」（看最新由芯片「回到最新」负责）
            LinearLayout tools = new LinearLayout(act);
            tools.setOrientation(LinearLayout.HORIZONTAL);
            tools.setPadding(0, dp(6), 0, dp(2));
            Button moreBtn = mkBtn(act);
            withIconText(act, moreBtn, "chevron-d", "加载更多");
            moreBtn.setTextSize(Theme.TS_SECOND);
            moreBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { appendMoreLog(); }
            });
            tools.addView(moreBtn, new LinearLayout.LayoutParams(-1, -2, 1f));
            root.addView(tools);
            logMoreBtn = moreBtn;

            TextView legend = new TextView(act);
            legend.setTextSize(Theme.TS_CAPTION);
            legend.setTextColor(Theme.termMuted(act));
            legend.setTypeface(android.graphics.Typeface.MONOSPACE);
            String lvTag = logFilter == LV_ERR ? "只看错误" : (logFilter == LV_WARN ? "只看重要" : "全部级别");
            String tgTag = logTarget.length() == 0 ? "全部目标" : "目标:" + logTarget;
            legend.setText(Lang.tf("最新在最上 ｜ 现在看：{0} · {1} ｜ 红=错误 黄=警告 绿=成功 灰=普通 暗灰=调试 ｜ 长按行复制", lvTag, tgTag));
            root.addView(legend);

            showDialog(act, "运行日志", root, "关闭");
            logLimit = 400;   // 首屏 400 条
            logRendered = 0;  // 重置分页游标（每次打开都从首屏开始）// 默认多显示一些，配合底部「加载更多」
            refreshLog();
            jumpLogNewest();   // 打开即定位到最新
        }

        private void logChip(final Context c, LinearLayout parent, String label, final Runnable action) {
            logChip(c, parent, Lang.tr(label), null, Theme.termTxt(c), action);
        }

        /** 带前导矢量图标的日志页芯片 */
        private void logChip(final Context c, LinearLayout parent, String label, String icon, int iconColor, final Runnable action) {
            TextView tv = new TextView(c);
            tv.setTextSize(Theme.TS_BODY);
            tv.setText(Lang.tr(label));
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setTextColor(Theme.termTxt(c));
            tv.setBackground(termBorder(c, Theme.termCard(c), Theme.withAlpha(Theme.termCyan(c), 0x22)));
            tv.setPadding(Theme.dp(c, 10), Theme.dp(c, 6), Theme.dp(c, 10), Theme.dp(c, 6));
            if (icon != null) {
                android.graphics.drawable.Drawable id = Icons.d(c, icon, 13f, iconColor);
                if (id != null) {
                    int isz = Theme.dp(c, 13);
                    id.setBounds(0, 0, isz, isz);
                    tv.setCompoundDrawables(id, null, null, null);
                    tv.setCompoundDrawablePadding(Theme.dp(c, 5));
                }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Theme.dp(c, 2), 0, Theme.dp(c, 2), 0);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> action.run());
            parent.addView(tv);
        }

        /** 一键回到最新（列表顶端）。post 保证布局完成后再滚。 */
        private void jumpLogNewest() {
            try {
                final android.widget.ScrollView svv = logSv;
                if (svv == null) return;
                svv.post(new Runnable() { @Override public void run() {
                    try { svv.fullScroll(android.view.View.FOCUS_UP); } catch (Throwable ignored) {}
                } });
            } catch (Throwable ignored) {}
        }

        private List<LogLine> logViewCache = new ArrayList<LogLine>();

        // 按真实总数更新按钮文案与可用性
        private void updateMoreBtn(int total, int shown) {
            if (logMoreBtn == null) return;
            int left = Math.max(0, total - shown);
            logMoreBtn.setText(left > 0 ? Lang.tf("加载更多（还有 {0} 条）", left) : Lang.tr("已全部加载"));
            logMoreBtn.setEnabled(left > 0);
            logMoreBtn.setAlpha(left > 0 ? 1f : 0.45f);
        }

        // 追加下一页（不重建已有视图，保留滚动位置）
        private void appendMoreLog() {
            try {
                if (logList == null) return;
                List<LogLine> show = logViewCache;
                if (show == null || logRendered >= show.size()) { updateMoreBtn(show == null ? 0 : show.size(), logRendered); return; }
                int to = Math.min(show.size(), logRendered + logPageStep);
                for (int i = logRendered; i < to; i++) logRow(logList, show.get(i));
                logRendered = to;
                updateMoreBtn(show.size(), logRendered);
                if (logStat != null) {
                    String cur = String.valueOf(logStat.getText());
                    logStat.setText(cur.replaceAll("已载入 [0-9]+", Lang.tf("已载入 {0}", logRendered)));
                }
            } catch (Throwable t) { try { loge("追加日志失败: " + t); } catch (Throwable ignored) {} }
        }

        private void refreshLog() {
            try {
                if (logList == null) return;
                logList.removeAllViews();
                List<LogLine> all = mergedLog(20000);
                List<LogLine> show = new ArrayList<LogLine>();
                int errs = 0, warns = 0;
                String qq = logQuery.toLowerCase(Locale.US);
                String tf = logTarget;
                for (LogLine l : all) {
                    if (l.lv == LV_ERR) errs++;
                    else if (l.lv == LV_WARN) warns++;
                    if (!logShowDebug && l.lv == LV_DEBUG) continue;
                    if (l.lv < logFilter) continue;
                    if (qq.length() > 0 && String.valueOf(l.msg).toLowerCase(Locale.US).indexOf(qq) < 0) continue;
                    if (tf.length() > 0) {
                        String lm = String.valueOf(l.msg);
                        if (lm.indexOf(tf) < 0) {
                            boolean hit = false;
                            try { hit = String.valueOf(l.msg).indexOf("uid=" + tf) >= 0 || String.valueOf(l.msg).indexOf(tf) >= 0; } catch (Throwable ignored) {}
                            if (!hit) continue;
                        }
                    }
                    show.add(l);
                }
                Collections.reverse(show);   // 固定「最新在上」
                logViewCache = show;         // 供「加载更多」追加用
                int n = Math.min(show.size(), logLimit);
                logRendered = n;
                // 按钮文案按真实剩余判断（修复「明明还有却说已全部加载」）
                updateMoreBtn(show.size(), n);
                for (int i = 0; i < n; i++) logRow(logList, show.get(i));
                if (n == 0) emptyView(logList, show.size() == 0 ? "没有符合条件的日志" : Lang.tf("没有匹配「{0}」的日志", logQuery));
                String span = "";
                if (!all.isEmpty()) {
                    span = " · 时间 " + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(all.get(0).ts))
                            + " 起 " + all.size() + " 条";
                }
                if (logStat != null) logStat.setText(Lang.tf("错误 {0} · 警告 {1}{2}", errs, warns, span)
                        + Lang.tf(" · 已载入 {0} / 共 {1} 条", n, show.size())
                        + (show.size() > n ? Lang.tr("（点下方按钮继续）") : ""));
            } catch (Throwable t) {
                try { loge("日志页刷新失败: " + t); } catch (Throwable ignored) {}
            }
        }

        private void logRow(LinearLayout parent, final LogLine l) {
            final Context c = parent.getContext();
            final boolean dark = Theme.dark(c);
            final String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(l.ts));
            final String body = l.msg;
            // 级别配色：亮色=深色系高对比 / 暗色=亮色系低刺眼
            final int textCol, tsCol, barCol, rowBg;
            switch (l.lv) {
                case LV_ERR:
                    textCol = dark ? 0xFFFF9E94 : 0xFFB3261E;
                    tsCol = dark ? 0xFF6E7A8C : 0xFF8C8C8C;
                    barCol = dark ? 0xFFFF5C54 : 0xFFD32F2F;
                    rowBg = dark ? 0x2E211F : 0x1AFDE7E9;
                    break;
                case LV_WARN:
                    textCol = dark ? 0xFFFFC98A : 0xFF9A6200;
                    tsCol = dark ? 0xFF6E7A8C : 0xFF8C8C8C;
                    barCol = dark ? 0xFFFFB24D : 0xFFE67F00;
                    rowBg = dark ? 0x2E2820 : 0x1AFFF4DE;
                    break;
                case LV_OK:
                    textCol = dark ? 0xFF7FE3A0 : 0xFF1B7E4A;
                    tsCol = dark ? 0xFF6E7A8C : 0xFF8C8C8C;
                    barCol = dark ? 0xFF35C46F : 0xFF1E9E55;
                    rowBg = dark ? 0x1C243028 : 0x14E8F5E9;
                    break;
                case LV_DEBUG:
                    textCol = dark ? 0xFF8A94A6 : 0xFF9A9A9A;
                    tsCol = dark ? 0xFF5A6478 : 0xFFB0B0B0;
                    barCol = dark ? 0xFF4A5468 : 0xFFC8C8C8;
                    rowBg = 0x00000000;
                    break;
                default:
                    textCol = dark ? 0xFFB8C4DC : 0xFF4A5568;
                    tsCol = dark ? 0xFF5A6478 : 0xFFB0B0B0;
                    barCol = dark ? 0xFF7C8DB5 : 0xFF7A8AA3;
                    rowBg = 0x00000000;
            }
            // 行容器：背景色 + 左侧级别色条
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (rowBg != 0x00000000) row.setBackgroundColor(rowBg);
            View gutter = new View(c);
            gutter.setBackgroundColor(barCol);
            row.addView(gutter, new LinearLayout.LayoutParams(Theme.dp(c, 3), -1));
            row.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c, 6), 1));
            TextView tv = new TextView(c);
            tv.setTextSize(Theme.TS_SECOND);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(Theme.dp(c, 2), Theme.dp(c, 3), Theme.dp(c, 2), Theme.dp(c, 3));
            // 时间戳灰 + 内容级别色：用 Spannable 拼两段
            android.text.SpannableStringBuilder sp = new android.text.SpannableStringBuilder();
            sp.append(ts);
            sp.setSpan(new android.text.style.ForegroundColorSpan(tsCol), 0, sp.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.append("  ");
            int bodyStart = sp.length();
            sp.append(body);
            sp.setSpan(new android.text.style.ForegroundColorSpan(textCol), bodyStart, sp.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(sp);
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { copyToClip(body); return true; }
            });
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
            parent.addView(row);
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
            t.setTextSize(Theme.TS_BODY);
            t.setTextColor(Theme.termTxt(act));
            t.setText(Lang.tr("清空会同时删掉当前列表和落盘的历史日志文件。\n建议先「导出再清空」留一份，方便之后对账。"));
            box.addView(t);
            Button b1 = mkBtn(act);
            b1.setText(Lang.tr("先导出一份，再清空"));
            b1.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doExportLog(act); clearLogNow(); } });
            box.addView(b1);
            Button b2 = mkBtn(act);
            b2.setText(Lang.tr("直接清空"));
            b2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { clearLogNow(); } });
            box.addView(b2);
            showDialog(act, "清空运行日志", box, "取消");
        }

        private void clearLogNow() {
            int deleted = 0;
            try {
                synchronized (logBuffer) { logBuffer.clear(); diskQueue.clear(); }
                java.io.File dir = logDir();
                if (dir != null) {
                    java.io.File[] fs = dir.listFiles();
                    if (fs != null) for (java.io.File f : fs) {
                        if (f.isFile() && isLogFileName(f.getName())) { if (f.delete()) deleted++; }
                    }
                }
                logRendered = 0;
                logViewCache = new ArrayList<LogLine>();
                jlog(LV_INFO, "运行日志已清空（删除 " + deleted + " 个文件），这条是新起的第一条");
                toast("日志已清空");
                refreshLog();
            } catch (Throwable t) { logw("清空日志失败: " + t); }
        }

    private void tcard(LinearLayout box, Activity act, String h, String b) {
        LinearLayout card = new LinearLayout(act); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, Theme.dp(act,4), 0, Theme.dp(act,4)); card.setLayoutParams(lp);
        card.setPadding(Theme.dp(act,14), Theme.dp(act,12), Theme.dp(act,14), Theme.dp(act,12));
        TextView ht = new TextView(act); ht.setTextSize(Theme.TS_SUBTITLE); ht.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); ht.setTextColor(Theme.termCyan(act)); ht.setText(Lang.tr(h)); card.addView(ht);
        TextView bt = new TextView(act); bt.setTextSize(Theme.TS_SECOND); bt.setTextColor(Theme.termMuted(act)); bt.setTypeface(android.graphics.Typeface.MONOSPACE); bt.setPadding(0, Theme.dp(act,4), 0, 0); bt.setText(Lang.tr(b)); card.addView(bt);
        box.addView(card);
    }

    private void showTutorial(Activity act) {
        if (act == null) return;
        ScrollView sv = new ScrollView(act);
        LinearLayout box = new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(Theme.dp(act,10), Theme.dp(act,4), Theme.dp(act,10), Theme.dp(act,4));
        tcard(box, act, "新手只看这段", "① 任意聊天输入框发 /jmb 打开面板 → ② 点「添加目标」加一个你要签的 bot → ③ 剩下的它自己办：每天在设定时段内自动签，失败自动重试，结果发到你自己的收藏夹。不需要开着 Telegram。");
        tcard(box, act, "① 打开面板", "任意聊天（含收藏夹）输入框发 /jmb 即可。面板顶部是今日进度与最近日志，中间 6 个高频入口，最下一行「全部功能」是分类直达：目标 / 数据 / 系统 / 帮助 / 维护，横滑点一下直接进对应分组。");
        tcard(box, act, "② 添加目标（三种方式）", "· 文本指令：知道 bot 要求的签到文本（如 /checkin）时用，填机器人数字 ID + 指令，到点自动发。\n· 回调按钮（推荐）：这类要“点按钮”。去那个 bot 会话点一次它的签到按钮，模块会自动认出并记住，以后每天替你点。绑定不受关键词限制。\n· 群 / 频道：签到在群里发指令时用，填群 ID（形如 -1001234567890）。\n不确定 bot ID 时，用「从会话列表选群」直接挑。");
        tcard(box, act, "③ 前置命令（拉面板）", "有的 bot 不主动发签到面板。在该条目的「编辑」里填“前置命令序列”（逗号或换行分隔，可多条，如 /start, 菜单）。签到或测试时会先依次发送把面板拉出来，再点按钮。");
        tcard(box, act, "④ 用什么节奏签（设置）", "· 定时签到：开启后只在「签到时间」窗口内动作，窗口外所有自动触发一律不响应。\n· 签到时间：两端可点，例如 08:30-20:30。\n· 错开间隔：0 = 按目标数自动均分；设 N 分钟则相邻目标至少隔 N 分钟再随机，防风控节奏自己定。\n· 错过补签 + 补签截止：当天错过仍可补到该时刻（默认 23:00），避免白白作废。");
        tcard(box, act, "⑤ 临时不想签", "条目操作里有「暂停一周 / 恢复」：暂停后该目标一周内不动作，到期自动恢复。适合出差、bot 维护、或暂时不想被签的场合，不用删掉再加回来。");
        tcard(box, act, "⑥ 怎么看结果", "· 首页进度：当前账号今天签了几个。\n· 今日计划：设置 →「查看今日计划」，每个目标一行，已签显示实际时刻（绿），待签显示计划时刻（琥珀）。\n· 补签列表：今日待补 / 已补 / 已跳过 / 错过，以及窗口与补签状态。\n· 运行日志：每次动作的完整记录，可按级别和目标筛选。");
        tcard(box, act, "⑦ 签到通知在哪看", "设置 →「签到结果通知」开启后，每天的签到摘要会静默发到你自己的 Telegram 收藏夹（不弹系统通知、不需要通知权限）。只想出问题时被打扰，就打开「只通知失败」。\n连续 3 天签到失败会额外告警一次，不会天天刷屏。");
        tcard(box, act, "⑧ 多账号", "每个账号的目标和“今天是否已签”各自独立。Telegram 里切到哪个账号，面板就是那个账号的目标，发送签到也跟随当前账号，不会替后台账号乱发。首页会显示其它账号各有几个目标。");
        tcard(box, act, "复制目标到其它账号", "多账号用户的省事入口：把当前账号的目标整体复制给其它账号，不用一条条重新添加。入口在「全部功能 → 目标」里。");
        tcard(box, act, "⑨ 换手机 / 备份", "「导出配置」生成 json 到下载目录，新设备用「导入配置」还原（导入是合并，不是覆盖）。配置里不含任何登录凭据。\n「导出日志」把运行日志导出到下载目录，方便留档或发给作者。\n删不干净时用「清空配置」，会跨全部账号彻底清。");
        tcard(box, act, "⑩ 外观与主题", "设置里可选主题：自动（跟随 Telegram 主题，推荐）/ 始终日间 / 始终夜间。界面配色由 TG 当前主题决定，切换 TG 主题面板会跟着变。");
        tcard(box, act, "⑪ 关键词与自动学习", "设置 → 学习行为：「按钮学习」开启后，你在 bot 里点过的按钮会自动加进目标；「网络学习」自动识别你发的签到文本。\n「关键词过滤」默认关闭（点过的都能绑）；开启后只有文案命中「学习关键词」的按钮才自动加，防误加。");
        tcard(box, act, "⑫ 出问题怎么办", "①「自诊断」：列出宿主反射锚点是否正常，第三方客户端（Nagram XF / Nagram / ExteraLess）适配先看这里。\n②「回调调试台」：列出面板全部按钮，点任意一个实时发一次看机器人返回，用来确认哪个按钮才对。\n③「运行日志」：点「诊断包」一键复制（错误 + 警告 + 最近 50 条 + 配置摘要），粘贴给作者最省事。\n④ 签到没反应：先看通知开关、是否在签到窗口内、该目标是否被暂停。");
        tcard(box, act, "⑬ 日志怎么读", "运行日志固定「最新在最上」，打开即定位到最新那条；往下滑看更早的，「加载更多」每次多取 300 条。\n顶部芯片可按级别（全部 / 只看重要 / 只看错误）和目标过滤；「回到最新」一键回顶。\n颜色：红 = 错误，黄 = 警告，绿 = 成功，灰 = 普通，暗灰 = 调试。长按某行可复制。");
        tcard(box, act, "⑭ 更新与反馈", "「检查更新」走官方 GitHub 发布，检测到新版本可直接下载安装包。遇到问题或想提需求，用「加入群组」反馈。");
        sv.addView(box);
        showDialog(act, Art.bold("TGAutoSign") + Lang.tr(" 使用教程"), sv, "关闭");
    }

    private void addDiagRow(LinearLayout box, Activity act, String label, boolean ok) {
        TextView t = new TextView(act);
        t.setTextSize(Theme.TS_SECOND); t.setTextColor(Theme.termTxt(act)); t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(Theme.dp(act,12), Theme.dp(act,10), Theme.dp(act,12), Theme.dp(act,10));
        t.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, Theme.dp(act,3), 0, Theme.dp(act,3)); t.setLayoutParams(lp);
        t.setText(Lang.tr(label));
        android.graphics.drawable.Drawable dd = Icons.d(act, ok ? "check" : "warn", 14f,
                ok ? Theme.termGreen(act) : Theme.termAmber(act));
        if (dd != null) {
            int dsz = Theme.dp(act, 14);
            dd.setBounds(0, 0, dsz, dsz);
            t.setCompoundDrawables(dd, null, null, null);
            t.setCompoundDrawablePadding(Theme.dp(act, 8));
        } else {
            t.setText((ok ? "\u2705 " : "\u26a0\ufe0f ") + Lang.tr(label));
        }
        box.addView(t);
    }

    private void showDiag(Activity act) {
        if (act == null) return;
        ScrollView sv = new ScrollView(act);
        LinearLayout box = new LinearLayout(act); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(Theme.dp(act,10), Theme.dp(act,4), Theme.dp(act,10), Theme.dp(act,4));
        addDiagRow(box, act, Lang.tf("宿主包 {0}", safePkg()), true);
        addDiagRow(box, act, Lang.tf("当前账号：{0}（共登录 {1} 个）", accountLabel(currentAccount()), activatedAccounts()), currentAccount() >= 0);
        for (int ai = 0; ai < activatedAccounts(); ai++) {
            int cnt = acctTargetCount(ai);
            addDiagRow(box, act, Lang.tf("{0}：{1}", accountLabel(ai), cnt == 0 ? Lang.tr("还没有签到目标，切过去学一个") : Lang.tf("{0} 个目标", cnt)), cnt > 0);
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
        addDiagRow(box, act, Lang.tf("Telegram 标志类：{0}", missM.isEmpty() ? Lang.tr("三项齐全") : Lang.tf("缺 {0}（该客户端自研了这层，模块不注入也不误伤）", missM)), missM.isEmpty());
        addDiagRow(box, act, "回调按钮读取正常（按实例字段取，不依赖类名）", true);
        addDiagRow(box, act, "已采样过按钮（捕获/调试台可用）", lastCapBtns != null);
        sv.addView(box);
        showDialog(act, "自诊断", sv, "关闭");
    }

    private void confirmDelete(final Activity act, final Map<String, Object> m) {
        try {
            new android.app.AlertDialog.Builder(act)
                .setTitle(Lang.tr("删除目标"))
                .setMessage(targetTitle(entryDid(m)) + "  " + entryText(m) + Lang.tr("\n确定删除？（会跨所有账号清干净）"))
                .setPositiveButton(Lang.tr("删除"), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String id = entryId(m);
                        removeEntryEverywhere(id);
                        for (int j = targets.size() - 1; j >= 0; j--) if (entryId(targets.get(j)).equals(id)) targets.remove(j);
                        toast("已删除");
                        showList(act);
                    }
                })
                .setNegativeButton(Lang.tr("取消"), null)
                .show();
        } catch (Throwable t) { toast(Lang.tf("确认框失败: {0}", t)); }
    }


    private static boolean themeLogDone;
    private boolean isDarkMode(Context ctx) {
        // 统一走 Theme.dark（TG 反射优先，失败才回退系统），与界面其它部分保持一致
        boolean d = Theme.dark(ctx);
        if (!themeLogDone) {
            themeLogDone = true;
            jlog("主题判定: dark=" + d + "（统一走 Theme.dark）");
        }
        return d;
    }

    private String txtMain(Context ctx) { return isDarkMode(ctx) ? "#F2F2F2" : "#1F1F1F"; }
    private String txtSub(Context ctx) { return isDarkMode(ctx) ? "#ABABAB" : "#757575"; }

    private void emptyView(LinearLayout parent, String text) {
        TextView tv = new TextView(parent.getContext());
        tv.setText(Lang.tr(text));
        tv.setTextColor(android.graphics.Color.parseColor(txtSub(parent.getContext())));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(24), 0, dp(24));
        parent.addView(tv);
    }

    private EditText adInput(Activity act, String hint, int type) {
        EditText e = new EditText(act);
        e.setHint(Lang.tr(hint));
        e.setTextSize(Theme.TS_BODY);
        e.setTypeface(android.graphics.Typeface.MONOSPACE);
        e.setTextColor(Theme.termTxt(act));
        e.setHintTextColor(Theme.termFaint(act));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        try { e.setBackground(termBorder(act, Theme.termCardInput(act), Theme.withAlpha(Theme.termCyan(act), 0x33))); } catch (Throwable ignored) {}
        if (type == 1) e.setInputType(InputType.TYPE_CLASS_NUMBER);   // 纯数字（如 bot ID）
        // type=2：可能是负数的 ID（群/频道），必须带符号与数字
        if (type == 2) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        // type=3：多行文本（排除规则等一行一条的输入）
        if (type == 3) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setSingleLine(false);
            e.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            e.setHorizontallyScrolling(false);
        }
        return e;
    }

    private void dismissOne(Object d) {
        if (d == null) return;
        try { call(d, "dismiss", new Class<?>[0], new Object[0]); }
        catch (Throwable t) {
            try { ((android.app.Dialog) d).dismiss(); } catch (Throwable t2) {}
        }
    }

    /** 关闭所有已打开的对话框（避免重开界面时叠层、不实时）。 */
    private void dismissAllDialog() {
        try {
            while (!dlgStack.isEmpty()) {
                Object d = dlgStack.pollLast();
                try { dismissOne(d); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void scheduleDismiss(final Object old) {
        if (old == null) return;
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { dismissOne(old); }
        }, 160L);
    }

    // 日志文件名判断：run.log（旧命名）与 run-YYYYMMDD[-N].log（现命名）都算
    // 注意必须同时匹配两种前缀 —— 只认 "run." 会漏掉现在的文件，
    // 表现就是「清空后重启日志又回来了」。
    private static boolean isLogFileName(String n) {
        if (n == null) return false;
        if (!n.endsWith(".log")) return false;
        return n.equals("run.log") || n.startsWith("run-") || n.startsWith("run.");
    }

    // 递归判断视图里是否已经有能滚动的控件（ScrollView/ListView/RecyclerView…）
    private static boolean containsScrollable(View v) {
        if (v == null) return false;
        if (v instanceof android.widget.ScrollView
                || v instanceof android.widget.HorizontalScrollView
                || v instanceof android.widget.ListView
                || v instanceof android.widget.GridView) return true;
        try {
            if (v.getClass().getName().contains("RecyclerView")) return true;
        } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsScrollable(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    // 限高容器：内容不超高就用自然高度，超高才钳到 maxH（避免短内容出现大片空白）
    private static final class CapBox extends android.widget.FrameLayout {
        private final int maxH;
        CapBox(Context c, int maxH) { super(c); this.maxH = maxH; }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            super.onMeasure(wSpec, android.view.View.MeasureSpec.makeMeasureSpec(0,
                    android.view.View.MeasureSpec.UNSPECIFIED));
            if (getMeasuredHeight() > maxH) {
                super.onMeasure(wSpec, android.view.View.MeasureSpec.makeMeasureSpec(maxH,
                        android.view.View.MeasureSpec.EXACTLY));
            }
        }
    }

    // 统一外观：true = 三个宿主都走自绘终端卡片（原生框空隙大、TG 12.10.3 又改了 API）
    private static final boolean FORCE_CUSTOM_DIALOG = true;

    private Object showDialog(Activity act, String title, View view, String negLabel) {
        title = Lang.tr(title);
        negLabel = Lang.tr(negLabel);

        if (act == null || act.isFinishing()) { toast(act == null ? Lang.tr("请在 Telegram 界面内使用 /jmb") : Lang.tr("页面已关闭，请重新打开")); return null; }

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
            // 对话框实现方式只报告一次，避免每次开关窗口都刷屏
            if (!dlgModeLogged) { dlgModeLogged = true; logd("[对话框] 使用 TG 原生对话框"); }
            pushDlg(d);
            return d;
        } catch (Throwable t) {
            if (!dlgModeLogged) {
                dlgModeLogged = true;
                logd(FORCE_CUSTOM_DIALOG
                    ? "[对话框] 使用自绘终端卡片（三宿主统一外观）"
                    : "[对话框] 宿主重构了 Builder，改用自绘终端卡片（仅提示一次，不影响功能）");
            }
        }
        // 兜底：自绘终端风卡片对话框（无系统标题栏/白边，深浅色走 Theme 色板）
        try {
            android.app.Dialog dlg = new android.app.Dialog(act);
            try { dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); } catch (Throwable ignored) {}
            dlg.setCanceledOnTouchOutside(true);
            // 根卡片
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(termBorder(act, Theme.termCardDeep(act), Theme.withAlpha(Theme.termCyan(act), 0x4D)));
            // 标题行：标题 + 关闭 ×
            LinearLayout hd = new LinearLayout(act); hd.setOrientation(LinearLayout.HORIZONTAL); hd.setGravity(android.view.Gravity.CENTER_VERTICAL);
            hd.setPadding(dp(16), dp(12), dp(8), dp(10));
            TextView tt = new TextView(act); tt.setText(title); tt.setTextSize(Theme.TS_SUBTITLE); tt.setTextColor(Theme.termTxt(act));
            tt.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            tt.setPadding(0, 0, dp(8), 0);
            hd.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView x = new TextView(act); x.setText("✕"); x.setTextSize(Theme.TS_SUBTITLE); x.setTextColor(Theme.termMuted(act));
            x.setGravity(android.view.Gravity.CENTER);
            x.setPadding(dp(10), dp(6), dp(12), dp(6));
            x.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ try { dlg.dismiss(); } catch (Throwable ignored) {} } });
            hd.addView(x, new LinearLayout.LayoutParams(-2, -2));
            card.addView(hd);
            View div = new View(act); div.setBackgroundColor(Theme.withAlpha(Theme.termCyan(act), 0x22));
            card.addView(div, new LinearLayout.LayoutParams(-1, dp(1)));
            // 内容区：包一层 ScrollView 限高 72% 屏高，超长可滚
            // 0.86 会把「标题栏 + 底部按钮」一起挤出屏幕导致「关闭」被裁；留足 chrome 空间
            final int maxH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.72f);
            // 关键：内容自身已经能滚动时，绝不再套一层 ScrollView。
            // 双层 ScrollView 会让外层抢走手势、内层滑不动（官方 TG 12.10.3 / Nagram 实测有这个毛病）。
            boolean nested = containsScrollable(view);
            View contentView;
            if (nested) {
                contentView = view;
            } else {
                android.widget.ScrollView sc = new android.widget.ScrollView(act);
                sc.setFillViewport(false);
                sc.addView(view, new android.widget.ScrollView.LayoutParams(-1, -2));
                contentView = sc;
            }
            CapBox cap = new CapBox(act, maxH);
            cap.addView(contentView, new android.widget.FrameLayout.LayoutParams(-1, -2));
            card.addView(cap, new LinearLayout.LayoutParams(-1, -2));
            // 底部按钮
            if (negLabel != null && !negLabel.isEmpty()) {
                LinearLayout bt = new LinearLayout(act); bt.setOrientation(LinearLayout.HORIZONTAL); bt.setGravity(android.view.Gravity.END);
                bt.setPadding(dp(12), dp(8), dp(12), dp(12));
                Button nb = mkBtn(act); nb.setText(negLabel);
                nb.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ try { dlg.dismiss(); } catch (Throwable ignored) {} } });
                bt.addView(nb, new LinearLayout.LayoutParams(-2, -2));
                card.addView(bt);
            }
            dlg.setContentView(card);
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                int wpx = act.getResources().getDisplayMetrics().widthPixels;
                int wTarget = Math.min((int) (wpx * 0.92f), Theme.dp(act, 400));
                w.setLayout(wTarget, -2);
            }
            dlg.show();
            if (dlgFallbackLogged < 3) { dlgFallbackLogged++; logd("[对话框] 自绘卡片(自带滚动=" + nested + "): " + title); }
            pushDlg(dlg);
            return dlg;
        } catch (Throwable t2) {
            jlog("对话框显示失败: " + t2);
        }
        return null;
    }

    private void runAction(Context ctx, String action) {
        if (ctx == null || !(ctx instanceof Activity)) return;
        Activity act = (Activity) ctx;
        if ("list".equals(action)) { showList(act); return; }
        if ("misslist".equals(action)) { showMissList(act); return; }
        if (action != null && action.startsWith("cat:")) { showCategory(act, action.substring(4)); return; }
        if ("more".equals(action)) { showCategory(act, null); return; }
        if ("add".equals(action)) { showAddChooser(act); return; }
        if ("del".equals(action)) { showDelete(act); return; }
        if ("sign".equals(action)) { showSign(act); return; }
        if ("sign_all_accounts".equals(action)) { signAllAccounts(); return; }
        if ("accounts".equals(action)) { showAccountOverview(act); return; }
        if ("copy_targets".equals(action)) { showCopyTargets(act); return; }
        if ("log".equals(action)) { showLog(act); return; }
        if ("settings".equals(action)) { showSettings(act); return; }
        if ("exclude".equals(action)) { showExcludeManager(act); return; }
        if ("presets".equals(action)) { showPresets(act); return; }
        if ("update".equals(action)) { showUpdate(act); return; }
        if ("update_download".equals(action)) { downloadUpdate(act); return; }
        if ("export".equals(action)) { doExport(); return; }
        if ("export_log".equals(action)) { doExportLog(act); return; }
        if ("import".equals(action)) { showImportPicker(act); return; }
        if ("clear_all".equals(action)) { confirmClearAll(act); return; }
        if ("add_text".equals(action)) { showAdd(act); return; }
        if ("add_group".equals(action)) { showAddGroup(act); return; }
        if ("cap_cb".equals(action)) { startCapture(act); return; }
        if ("debug".equals(action)) { showDebugConsole(act); return; }
        if ("tutorial".equals(action)) { showTutorial(act); return; }
        if ("diag".equals(action)) { showDiag(act); return; }
        if ("join_group".equals(action)) { openGroup(); return; }
        if ("share".equals(action)) { showShare(act); return; }
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
            String until = prefs.getString(kSnooze(prefix, id), "");
            if (until == null || until.length() == 0) return false;
            return until.compareTo(todayStr()) > 0;
        } catch (Throwable t) { return false; }
    }

    private void toggleSnooze(Map<String, Object> m, int account) {
        try {
            String prefix = accountPrefix(account);
            String id = entryId(m);
            String title = entryDisplayName(m);
            if (isSnoozed(prefix, id)) {
                prefs.edit().remove(kSnooze(prefix, id)).apply();
                toast(Lang.tf("已恢复：{0}", title));
                logs("【暂停】已恢复 " + title + " (acc" + account + ")");
            } else {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.add(java.util.Calendar.DAY_OF_YEAR, 7);
                String until = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
                prefs.edit().putString(kSnooze(prefix, id), until).apply();
                toast(Lang.tf("已暂停一周（至 {0}）：{1}", until, title));
                logs("【暂停】" + title + " 暂停至 " + until + " (acc" + account + ")");
            }
        } catch (Throwable t) { toast(Lang.tf("暂停操作失败: {0}", t)); }
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
            java.io.File f = null;
            java.io.File[] fsl = dir.listFiles();
            if (fsl != null) {
                java.util.List<java.io.File> cand = new java.util.ArrayList<java.io.File>();
                for (java.io.File x : fsl) if (x.isFile() && isLogFileName(x.getName())) cand.add(x);
                // 取"最新"：现命名 run-日期 排在 run.log 之后，故按名字倒序取第一个；
                // 名字相同规则下 run-YYYYMMDD 天然大于 run.log
                java.util.Collections.sort(cand, new java.util.Comparator<java.io.File>() {
                    @Override public int compare(java.io.File a, java.io.File b) {
                        return a.getName().compareTo(b.getName());
                    }
                });
                if (!cand.isEmpty()) f = cand.get(cand.size() - 1);
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
            toast(Lang.tf("已复制最近 {0} 行日志到剪贴板", n));
            jlog("【/jmb log】已复制最近 " + n + " 行日志");
        } catch (Throwable t) { toast(Lang.tf("复制日志失败: {0}", t)); }
    }

    private void forceCheckUpdate() {
        try {
            UpdateChecker.checkAsync(appContext, true, mainHandler, r -> {
                if (r == null) return;
                if (r.networkError) { toast(Lang.tf("检查更新未成功: {0}", r.message)); return; }
                lastUpdate = r;
                if (r.newer) {
                    toast(Lang.tf("发现新版本 v{0}（当前 v{1}）：发 /jmb → 🔄 检查更新", r.version, UpdateChecker.VERSION_NAME));
                } else {
                    toast(Lang.tf("已是最新 v{0}", UpdateChecker.VERSION_NAME));
                }
                logs("【/jmb update】" + (r.newer ? "发现新版本 v" + r.version : "已是最新 v" + UpdateChecker.VERSION_NAME));
            });
        } catch (Throwable t) { toast(Lang.tf("检查更新失败: {0}", t)); }
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
            toast(Lang.tf("已删除 {0}", targetTitle(did)));
            showDelete((Activity) ctx);
            return;
        }
        if ("sign".equals(action)) {
            Map<String, Object> m = findEntryById(id);
            if (m == null) { toast("目标不存在"); return; }
            jlog("[界面] 手动签到 " + entryDid(m) + " (id=" + id + ")");
            sendSign(m, currentAccount());
            toast(Lang.tf("已命令签到 {0}", entryText(m)));
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
        info.setTextSize(Theme.TS_BODY);
        info.setTextColor(Theme.termTxt(act));
        if (fs.isEmpty()) {
            info.setText(Lang.tf("没有找到备份文件。\n\n备份放在这里：\nAndroid/data/{0}/files/tgautosign/\n（在 /jmb → 📤 导出配置 里生成，也可以手动把 json 拷进去）", safePkg()));
            box.addView(info);
            showDialog(act, "导入配置", box, "关闭");
            return;
        }
        info.setText(Lang.tr("选一份备份导入。导入前会显示它的内容，导入后会告诉你目标数有没有变化。\n"
                + "合并 = 只覆盖文件里有的键；覆盖 = 先清掉现有目标和状态再导入。"));
        box.addView(info);
        int n = 0;
        for (final java.io.File f : fs) {
            if (n++ >= 10) break;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Theme.dp(act, 4), Theme.dp(act, 11), Theme.dp(act, 4), Theme.dp(act, 11));
            TextView t1 = new TextView(act);
            t1.setTextSize(Theme.TS_BODY);
            t1.setTextColor(Theme.termTxt(act)); t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            t1.setText(f.getName());
            row.addView(t1);
            TextView t2 = new TextView(act);
            t2.setTextSize(Theme.TS_CAPTION);
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
        t.setTextSize(Theme.TS_SECOND);
        t.setTextColor(Theme.termTxt(act)); t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setText(f.getName() + "\n" + ConfigStore.describe(f)
                + Lang.tf("\n\n{0} 现在有 {1} 个目标。", accountLabel(currentAccount()), targetsSnapshot().size()));
        box.addView(t);
        Button b1 = mkBtn(act);
        b1.setText(Lang.tr("合并导入（保留现有的）"));
        b1.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doImportNow(act, f, false); } });
        box.addView(b1);
        Button b2 = mkBtn(act);
        b2.setText(Lang.tr("覆盖导入（先清空目标和状态）"));
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
            toast(Lang.tf("导入失败：{0}", rep.message));
            return;
        }
        try { lastAccount = -1; syncAccount(); } catch (Throwable ignored) {}
        int after = targetsSnapshot().size();
        String line = "【导入】" + f.getName() + " · " + (replace ? "覆盖" : "合并") + " · 写入 " + rep.keys + " 个键"
                + (rep.skipped > 0 ? "（忽略不认识的老键 " + rep.skipped + " 个）" : "")
                + " · " + accountLabel(currentAccount()) + " 目标 " + before + " → " + after;
        if (after == before && before > 0) logw(line + " —— 当前账号的目标数没变");
        else logs(line);
        toast(Lang.tf("导入完成：{0} 目标 {1} → {2}", accountLabel(currentAccount()), before, after));
        if (act != null) showList(act);
    }

    /** 排除管理：整合排除规则、排除 bot、待确认池三个入口。 */
    private void showExcludeManager(Activity act) {
        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            sectionHeader(box, act, "▍排除规则");
            TextView exLab = new TextView(act);
            exLab.setText(Lang.tr("排除规则（一行一条，命中不学习）"));
            leadIcon(act, exLab, "trash", Theme.termPink(act));
            exLab.setTextSize(Theme.TS_SECOND); exLab.setTextColor(Theme.termMuted(act));
            exLab.setTypeface(android.graphics.Typeface.MONOSPACE);
            exLab.setPadding(dp(2), dp(2), dp(2), dp(4));
            box.addView(exLab);
            final EditText ex = adInput(act, "如: 点击图中事物（一行一条）", 3);
            ex.setText(LEARN_EXCLUDE == null ? "" : String.valueOf(LEARN_EXCLUDE));
            ex.setMinLines(2);
            box.addView(ex);
            TextView exTip = new TextView(act);
            exTip.setTextSize(Theme.TS_CAPTION); exTip.setTextColor(Theme.termFaint(act)); exTip.setTypeface(Theme.text());
            exTip.setText(Lang.tr("匹配 bot 回复正文 + 按钮文案。用 / 包裹当正则，# 开头为注释。"));
            exTip.setPadding(dp(4), dp(4), dp(4), dp(6));
            box.addView(exTip);

            sectionHeader(box, act, "▍排除的 bot");
            final TextView blVal = new TextView(act);
            blVal.setTextSize(Theme.TS_CAPTION); blVal.setTextColor(Theme.termFaint(act)); blVal.setTypeface(Theme.text());
            final Runnable refreshBl = new Runnable() { @Override public void run() {
                if (LEARN_BLOCKED_DIDS.isEmpty()) blVal.setText(Lang.tr("(未排除任何 bot)"));
                else blVal.setText(Lang.tf("已排除 {0} 个 bot", LEARN_BLOCKED_DIDS.size()));
            } };
            refreshBl.run();
            blVal.setPadding(dp(4), dp(2), dp(4), dp(4));
            box.addView(blVal);
            Button blBtn = mkBtn(act); withIconText(act, blBtn, "bot", "选择要排除的 bot");
            blBtn.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                try { showBlockedBotPicker(act, refreshBl); } catch (Throwable t) { toast(Lang.tf("打开失败: {0}", t)); }
            } });
            box.addView(blBtn);

            sectionHeader(box, act, "▍待确认");
            final java.util.List<Long> pd = pendingConfirmDids();
            TextView pcLab = new TextView(act); pcLab.setTextSize(Theme.TS_CAPTION); pcLab.setTextColor(Theme.termFaint(act)); pcLab.setTypeface(Theme.text());
            pcLab.setText(pd.isEmpty() ? Lang.tr("(无待确认目标)") : Lang.tf("待确认 {0} 个", pd.size()));
            pcLab.setPadding(dp(4), dp(2), dp(4), dp(4));
            box.addView(pcLab);
            Button pcBtn = mkBtn(act); withIconText(act, pcBtn, "check", "处理待确认");
            pcBtn.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ showPendingConfirm(act); } });
            box.addView(pcBtn);

            Button save = mkBtnPrimary(act); withIconText(act, save, "save", "保存排除规则");
            save.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                LEARN_EXCLUDE = String.valueOf(ex.getText()).trim();
                prefs.edit().putString(kExclude(), LEARN_EXCLUDE).apply();
                toast("排除规则已保存");
            } });
            box.addView(save);
            showDialog(act, "排除管理", box, "关闭");
        } catch (Throwable t) { toast(Lang.tf("打开失败: {0}", t)); }
    }

    /** 待确认池界面：网络学习命中的目标，用户手动确认加入或忽略。 */
    private void showPendingConfirm(Activity act) {
        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            final Object[] curDlg = new Object[1];
            final java.util.List<Long> dids = pendingConfirmDids();
            final java.util.List<String> texts = pendingConfirmTexts();
            if (dids.isEmpty()) {
                TextView e = new TextView(act); e.setTextSize(Theme.TS_BODY); e.setTextColor(Theme.termMuted(act)); e.setTypeface(Theme.text());
                e.setText(Lang.tr("(待确认列表为空)\n网络学习命中且「需确认」开启时，这里会出现候选目标。"));
                e.setPadding(dp(8), dp(12), dp(8), dp(12));
                box.addView(e);
            } else {
                TextView hint = new TextView(act); hint.setTextSize(Theme.TS_CAPTION); hint.setTextColor(Theme.termFaint(act)); hint.setTypeface(Theme.text());
                hint.setText(Lang.tr("以下目标来自「网络学习」，确认后才会加入自动签到。点「加入」确认，点「忽略」丢弃。"));
                hint.setPadding(dp(4), dp(4), dp(4), dp(8));
                box.addView(hint);
                for (int i = 0; i < dids.size(); i++) {
                    final long did = dids.get(i);
                    final String text = i < texts.size() ? texts.get(i) : "";
                    LinearLayout row = new LinearLayout(act); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x26)));
                    row.setPadding(dp(12), dp(10), dp(12), dp(10));
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    rlp.setMargins(0, dp(3), 0, dp(3));
                    row.setLayoutParams(rlp);
                    String title = targetTitle(did);
                    TextView tt = new TextView(act); tt.setTextSize(Theme.TS_SUBTITLE); tt.setTextColor(Theme.termTxt(act)); tt.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                    tt.setText(title + "\n" + text);
                    row.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
                    Button acc = mkBtn(act); withIconText(act, acc, "check", "加入");
                    acc.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                        if (pendingConfirmAccept(did, text)) { toast("已加入"); dismissOne(curDlg[0]); showPendingConfirm(act); }
                        else toast("加入失败");
                    } });
                    row.addView(acc);
                    Button ign = mkBtn(act); withIconText(act, ign, "x", "忽略");
                    ign.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                        pendingConfirmRemove(did); toast("已忽略"); dismissOne(curDlg[0]); showPendingConfirm(act);
                    } });
                    row.addView(ign);
                    box.addView(row);
                }
            }
            curDlg[0] = showDialog(act, Lang.tf("待确认（{0}）", dids.size()), box, "关闭");
        } catch (Throwable t) { toast(Lang.tf("打开失败: {0}", t)); }
    }

    private void showList(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        // 待确认池入口（网络学习需确认时产生）
        final java.util.List<Long> pd = pendingConfirmDids();
        if (pd.size() > 0) {
            LinearLayout pc = new LinearLayout(act); pc.setOrientation(LinearLayout.HORIZONTAL); pc.setGravity(Gravity.CENTER_VERTICAL);
            pc.setBackground(termBorder(act, Theme.withAlpha(Theme.termAmber(act), 0x0E), Theme.withAlpha(Theme.termAmber(act), 0x50)));
            pc.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(-1, -2);
            pclp.setMargins(0, dp(2), 0, dp(8));
            pc.setLayoutParams(pclp);
            TextView pct = new TextView(act); pct.setTextSize(Theme.TS_BODY); pct.setTextColor(Theme.termAmber(act)); pct.setTypeface(Theme.monoBold());
            pct.setText(Lang.tf("待确认 {0} 个（点此处理）", pd.size()));
            pc.addView(pct, new LinearLayout.LayoutParams(0, -2, 1f));
            pc.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ showPendingConfirm(act); } });
            box.addView(pc);
        }
        if (targets.size() == 0) {
            emptyView(box, "(暂无目标，点「添加目标」，或直接点 bot 的签到按钮自动学习)");
        }
        // 排序切换
        if (targets.size() > 0) {
            LinearLayout bar = new LinearLayout(act); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(2), dp(2), dp(2), dp(6));
            TextView lab = new TextView(act); lab.setText(Lang.tr("排序")); lab.setTextSize(Theme.TS_CAPTION); lab.setTextColor(Theme.termMuted(act)); lab.setTypeface(android.graphics.Typeface.MONOSPACE); lab.setPadding(0, 0, dp(8), 0);
            bar.addView(lab);
            bar.addView(sortChip(act, "未签置顶", "unsigned"));
            bar.addView(sortChip(act, "按名称", "name"));
            box.addView(bar);
        }
        String today = todayStr();
        for (Map<String, Object> m : sortedTargets()) {
            targetRow(box, m, statusOf(accountPrefix(), entryId(m), today), "more");
        }
        Object oldList = listDialog;
        listDialog = showDialog(act, Lang.tf("目标列表（{0}）", targets.size()), box, "关闭");
        scheduleDismiss(oldList);
    }


    // ---------------- v1.5.2：签到窗口 / 连续签到 / 预设模板 ----------------

    private int parseHM(String s) {
        // 实现已抽到 SignLogic（纯逻辑、有单测）；此处保留薄封装，调用点不用改。
        return SignLogic.parseHM(s);
    }

    private int[] windowRange() {
        return windowRangeOf(WINDOW);
    }

    private boolean inWindow() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int n = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        // 用支持跨天的版本：以前 windowRange() 对跨天窗口返回 null，
        // 而这里把 null 当「不限」→ 跨天窗口下会全天签到。
        return SignLogic.inWindowAny(n, SignLogic.windowRangeAny(WINDOW));
    }

    /**
     * 错过补签的可用时段：[窗口开始, 补签截止]。
     * 与「签到窗口」解耦——窗口结束后仍可补到截止时间，避免 TG 后台没开/错过就把当天目标作废。
     * 窗口开始之前不算错过（还没到该签的时间）。
     */
    private boolean inMissBackTime() {
        if (!MISS_BACK) return false;
        int[] r = windowRange();
        java.util.Calendar c = java.util.Calendar.getInstance();
        int n = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int start = r != null ? r[0] : 0;
        if (MISS_DEADLINE >= start) {
            // 普通情况：窗口开始 ~ 截止（同日）
            return n >= start && n <= MISS_DEADLINE;
        }
        // 截止早于窗口开始 = 跨到次日（如窗口 20:00-23:00、截止 06:00）
        return n >= start || n <= MISS_DEADLINE;
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
            String lastDate = prefs.getString(kLastSignDate(prefix), "");
            if (today.equals(lastDate)) return;
            int n = prefs.getInt(kStreak(prefix), 0);
            if (lastDate.length() > 0 && isYesterday(lastDate)) n = n + 1; else n = 1;
            prefs.edit().putInt(kStreak(prefix), n).putString(kLastSignDate(prefix), today).apply();
        } catch (Throwable ignored) {}
    }

    private int streakOf(String prefix) {
        try {
            String lastDate = prefs.getString(kLastSignDate(prefix), "");
            String today = todayStr();
            if (lastDate.length() > 0 && (today.equals(lastDate) || isYesterday(lastDate))) {
                return prefs.getInt(kStreak(prefix), 0);
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
                if (today.equals(prefs.getString(kLast(prefix, entryId(m)), ""))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private java.util.Set<String> signDays(String prefix) {
        java.util.Set<String> s = new java.util.HashSet<String>();
        String v = prefs.getString(kSignDays(prefix), "");
        if (v != null) {
            for (String x : v.split(",")) {
                String t = x.trim();
                if (t.length() > 0) s.add(t);
            }
        }
        // 回填来源①：每个目标的 last_<id>（旧版自动签到只写 last_ 不写 sign_days，日历会漏绿）
        try {
            java.util.List<Map<String, Object>> l = new java.util.ArrayList<Map<String, Object>>();
            loadTargetsInto(prefix, l);
            for (Map<String, Object> m : l) {
                String d = prefs.getString(kLast(prefix, entryId(m)), "");
                if (d != null && d.length() > 0) s.add(d);
            }
        } catch (Throwable ignored) {}
        // 回填来源②：连续签到数据（last_sign_date + streak 天往前推）。
        // last_ 只保留最近一次，历史日期会丢；连续段能反映真实签到史，用它补上昨/前天。
        try {
            String lsd = prefs.getString(kLastSignDate(prefix), "");
            int st = prefs.getInt(kStreak(prefix), 0);
            if (lsd != null && lsd.length() > 0 && st > 0) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                java.util.Date d0 = f.parse(lsd);
                if (d0 != null) {
                    java.util.Calendar cc = java.util.Calendar.getInstance();
                    cc.setTime(d0);
                    for (int i = 0; i < Math.min(st, 180); i++) {
                        s.add(f.format(cc.getTime()));
                        cc.add(java.util.Calendar.DATE, -1);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return s;
    }

    /**
     * 记录"今天已签到"到 sign_days。
     * 注意：这里必须读【原始值】，不能用 signDays()（那个会从 last_ 回填今天，
     * 导致 s.add(today) 恒为 false，sign_days 永远写不进去——日历因此漏绿）。
     * 用 commit() 同步落盘，保证进程随时被杀也不丢。
     */
    private void noteSignedDay(String prefix) {
        try {
            String today = todayStr();
            String raw = prefs.getString(kSignDays(prefix), "");
            java.util.Set<String> s = new java.util.LinkedHashSet<String>();
            if (raw != null) {
                for (String x : raw.split(",")) {
                    String t = x.trim();
                    if (t.length() > 0) s.add(t);
                }
            }
            if (!s.add(today)) return;   // 原始记录里已有今天，不用再写
            StringBuilder sb = new StringBuilder();
            int cap = 0;
            for (String x : s) {
                if (cap++ > 180) break;   // 保留约半年
                if (sb.length() > 0) sb.append(',');
                sb.append(x);
            }
            prefs.edit().putString(kSignDays(prefix), sb.toString()).commit();
            logd("[日历] 已记录签到日 " + today + "（累计 " + s.size() + " 天）");
        } catch (Throwable t) {
            logd("[日历] 记录失败: " + t);
        }
    }

    private int[] windowRangeOf(String w) {
        return SignLogic.windowRangeOf(w);
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
            if (nowSec >= startSec && nowSec <= endSec) {
                mainHandler.removeCallbacks(windowWakeRunnable);
                if (TIMER_ENABLED) { kickSchedule(); } else { enqueueTry("进入窗口"); }
                return;
            }
            long delay = nowSec < startSec ? (long) (startSec - nowSec) * 1000L : (long) (24 * 3600 - nowSec + startSec) * 1000L;
            delay += (long) (Math.random() * 2L * 60L * 1000L);
            mainHandler.removeCallbacks(windowWakeRunnable);
            mainHandler.postDelayed(windowWakeRunnable, delay);
        } catch (Throwable ignored) {}
    }

    // ---------------- 定时签到：当日时刻表（v1.5.4） ----------------

    // ---------------- prefs 键集中管理（所有动态键生成只走这里，改键名只改一处） ----------------
    private static String kLast(String prefix, String id) { return prefix + "last_" + id; }
    private static String kRetry(String prefix, String id) { return prefix + "retry_" + id; }
    private static String kRetryAt(String prefix, String id) { return prefix + "retry_at_" + id; }
    private static String kRetryDay(String prefix, String id) { return prefix + "retry_day_" + id; }
    private static String kSnooze(String prefix, String id) { return prefix + "snooze_" + id; }
    private static String kLearned(String prefix, String id) { return prefix + "learned_" + id; }
    private static String kSignDays(String prefix) { return prefix + "sign_days"; }
    private static String kStreak(String prefix) { return prefix + "streak"; }
    private static String kLastSignDate(String prefix) { return prefix + "last_sign_date"; }
    private static String kTimerPlan(String prefix, String day) { return prefix + "timer_plan_" + day; }
    private static String kPromptDay() { return "jmb_prompt_day"; }
    private static String kTimerEnabled() { return "jmb_timer"; }
    private static String kWindow() { return "jmb_window"; }
    private static String kExclude() { return "jmb_exclude"; }
    private static String kBlockedDids() { return "jmb_blocked_dids"; }
    private static String kPendingConfirm() { return "jmb_pending_confirm"; }
    private static String kFrozen(String prefix, String id) { return prefix + "frozen_" + id; }
    /** 「待确认」：发出去了但 bot 始终没回复，不算成功也不算失败，且不再自动重试。 */
    private static String kPendingConfirm(String prefix, String id) { return prefix + "pendcfm_" + id; }
    private boolean isPendingConfirm(String prefix, String id) {
        try { return prefs.getBoolean(kPendingConfirm(prefix, id), false); } catch (Throwable t) { return false; }
    }
    private void clearPendingConfirm(String prefix, String id) {
        try { if (prefs.getBoolean(kPendingConfirm(prefix, id), false)) prefs.edit().remove(kPendingConfirm(prefix, id)).apply(); }
        catch (Throwable _eC) { noteSwallowed("clearPendingConfirm", _eC); }
    }

    private static String kGap() { return "jmb_gap"; }
    private static String kMissBack() { return "jmb_missback"; }

    // ---- 配置按账号（多账号用户各账号独立设置；老键保留兼容与回滚）----
    private String cfgStr(String name, String def) {
        try {
            String p = accountPrefix() + "cfg_" + name;
            if (prefs.contains(p)) return prefs.getString(p, def);
            if (prefs.contains("jmb_" + name)) return prefs.getString("jmb_" + name, def);
        } catch (Throwable ignored) {}
        return def;
    }

    private boolean cfgBool(String name, boolean def) {
        try {
            String p = accountPrefix() + "cfg_" + name;
            if (prefs.contains(p)) return prefs.getBoolean(p, def);
            if (prefs.contains("jmb_" + name)) return prefs.getBoolean("jmb_" + name, def);
        } catch (Throwable ignored) {}
        return def;
    }

    private int cfgInt(String name, int def) {
        try {
            String p = accountPrefix() + "cfg_" + name;
            if (prefs.contains(p)) return prefs.getInt(p, def);
            if (prefs.contains("jmb_" + name)) return prefs.getInt("jmb_" + name, def);
        } catch (Throwable ignored) {}
        return def;
    }

    private void putCfg(android.content.SharedPreferences.Editor ed, String name, Object val) {
        try {
            String p = accountPrefix() + "cfg_" + name;
            if (val instanceof Boolean) ed.putBoolean(p, (Boolean) val);
            else if (val instanceof Integer) ed.putInt(p, (Integer) val);
            else ed.putString(p, String.valueOf(val));
        } catch (Throwable ignored) {}
    }

    /** 把当前账号的签到配置（窗口/定时/间隔/补签等）应用到所有账号。 */
    private void applyConfigToAllAccounts() {
        try {
            int n = Math.max(1, activatedAccounts());
            String cur = accountPrefix();
            android.content.SharedPreferences.Editor ed = prefs.edit();
            String[] keys = {"window", "timer", "gap", "missback", "missdead"};
            for (int i = 0; i < n; i++) {
                String p = accountPrefix(i);
                for (String k : keys) {
                    String src = cur + "cfg_" + k;
                    if (!prefs.contains(src)) continue;
                    Object v = prefs.getAll().get(src);
                    String dst = p + "cfg_" + k;
                    if (v instanceof Boolean) ed.putBoolean(dst, (Boolean) v);
                    else if (v instanceof Integer) ed.putInt(dst, (Integer) v);
                    else if (v != null) ed.putString(dst, String.valueOf(v));
                }
            }
            ed.putLong("jmb_config_ts", System.currentTimeMillis());
            ed.apply();
            jlog("【配置】已把当前账号的签到配置应用到全部 " + n + " 个账号");
            toast(Lang.tf("已应用到全部 {0} 个账号", n));
        } catch (Throwable t) { toast(Lang.tf("应用失败: {0}", t)); }
    }

    /** 启动迁移：没有 acc{N}_cfg_* 的账号，从全局 jmb_* 初始化，避免老用户设置丢失。 */
    private void migrateAccountConfigs() {
        try {
            int n = Math.max(1, activatedAccounts());
            String[] keys = {"window", "timer", "gap", "missback", "missdead"};
            android.content.SharedPreferences.Editor ed = null;
            for (int i = 0; i < n; i++) {
                String p = accountPrefix(i);
                for (String k : keys) {
                    String accKey = p + "cfg_" + k;
                    String gKey = "jmb_" + k;
                    if (prefs.contains(accKey)) continue;      // 已配置过，不动
                    if (!prefs.contains(gKey)) continue;       // 全局也没有，用代码默认
                    if (ed == null) ed = prefs.edit();
                    Object v = prefs.getAll().get(gKey);
                    if (v instanceof Boolean) ed.putBoolean(accKey, (Boolean) v);
                    else if (v instanceof Integer) ed.putInt(accKey, (Integer) v);
                    else if (v != null) ed.putString(accKey, String.valueOf(v));
                }
            }
            if (ed != null) { ed.apply(); jlog("【配置】已把全局默认配置迁移到各账号"); }
        } catch (Throwable ignored) {}
    }
    private static String kKeywords() { return "jmb_keywords"; }
    private static String kRetryLimit() { return "jmb_retry"; }
    private static String kWakeCmd() { return "jmb_wake_cmd"; }
    private static String kSort() { return "jmb_sort"; }
    private static String kFx() { return "jmb_fx"; }
    private static String kAutoLearn() { return "jmb_autolearn"; }
    private static String kAutoLearnNet() { return "jmb_autolearn_net"; }
    private static String kAutoLearnFilter() { return "jmb_alfilter"; }
    private static String kLastRound() { return "jmb_last_round"; }
    private static String kTimerPlanOf(String prefix) { return prefix + "timer_plan_" + todayStrStatic(); }
    private static String todayStrStatic() { try { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()); } catch (Throwable t) { return ""; } }

    /** 当日时刻表 key：acc{N}_timer_plan_<yyyy-MM-dd>，值 = JSON 数组 [{id,did,kind,text,min}]（min=窗口内偏移分钟） */
    private String timerPlanKey(String prefix) {
        return kTimerPlan(prefix, todayStr());
    }

    /** 生成当日时刻表：窗口 [start,end] 按目标数均分时段，每目标在自己时段内随机取整数分钟。已签目标不排。 */
    private void ensureTimerPlan(String prefix) {
        try {
            String key = timerPlanKey(prefix);
            if (prefs.contains(key)) return;
            // 支持跨天窗口（如 22:00-02:00）：展开成 [1320, 1560] 的分钟轴，
            // 排完期再折回 0..1439。旧写法遇到跨天直接 return，定时模式整晚不工作。
            int[] any = SignLogic.windowRangeAny(WINDOW);
            if (any == null) return;
            boolean cross = SignLogic.crossesMidnight(any);
            int[] spanAxis = SignLogic.windowSpanAny(any);
            int r0 = spanAxis[0], r1 = spanAxis[1];
            int span = r1 - r0;
            List<Map<String, Object>> list = new ArrayList<>();
            loadTargetsInto(prefix, list);
            if (list.isEmpty()) return;
            org.json.JSONArray arr = new org.json.JSONArray();
            // 间隔策略：用户设了 jmb_gap>0 用固定间隔；否则按目标数自动均分
            // 先筛出今天还没签的目标，只为这些排时刻
            List<Map<String, Object>> todo = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> m : list) {
                String id = entryId(m);
                if (todayStr().equals(prefs.getString(kLast(prefix, id), ""))) continue;   // 今天已签不排
                todo.add(m);
            }
            int nTodo = todo.size();
            if (nTodo <= 0) return;
            // 序号均分：把窗口切成 nTodo 份，第 i 个目标只在第 i 份里随机。
            // 这样 lo 由序号决定，数学上不可能跑到窗口外（旧写法 cursor 单调累加会溢出）。
            int idx = 0;
            for (Map<String, Object> m : todo) {
                String id = entryId(m);
                int lo = r0 + (int) ((long) span * idx / nTodo);
                int hi = r0 + (int) ((long) span * (idx + 1) / nTodo) - 1;
                if (hi > r1 - 1) hi = r1 - 1;
                if (hi < lo) hi = lo;
                // 错开间隔 >0 时额外收窄本份上界，但不越过份的边界
                if (GAP_MIN > 0 && hi > lo + GAP_MIN - 1) hi = lo + GAP_MIN - 1;
                int min = lo + random.nextInt(hi - lo + 1);
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("id", id);
                    o.put("did", entryDid(m));
                    o.put("kind", entryKind(m));
                    o.put("text", entryText(m));
                    o.put("min", SignLogic.wrapMinute(min));
                    arr.put(o);
                } catch (Throwable _e22) { noteSwallowed("ensureTimerPlan", _e22); }
                idx++;
            }
            if (arr.length() == 0) return;
            prefs.edit().putString(key, arr.toString()).apply();
            jlog("[定时] 已生成当日时刻表: " + arr.length() + " 条（窗口 "
                    + hhmm(any[0]) + "-" + hhmm(any[1])
                    + " 均分 " + Math.max(1, span / Math.max(1, nTodo)) + " 分钟/个"
                    + (GAP_MIN > 0 ? " 下限" + GAP_MIN + " 分钟" : "")
                    + " 首 " + hhmm(arr.optJSONObject(0).optInt("min"))
                    + " 末 " + hhmm(arr.optJSONObject(arr.length() - 1).optInt("min"))
                    + (cross ? " 跨天" : "")
                    + "）");
        } catch (Throwable t) {
            logException("[定时] 生成时刻表", t);
        }
    }

    /** 读取当日时刻表 → [{id,did,kind,text,min}] */
    private List<Map<String, Object>> timerPlan(String prefix) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            String key = timerPlanKey(prefix);
            String v = prefs.getString(key, "");
            if (v == null || v.isEmpty()) return out;
            org.json.JSONArray arr = new org.json.JSONArray(v);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", o.optString("id"));
                m.put("did", o.optLong("did"));
                m.put("kind", o.optString("kind"));
                m.put("text", o.optString("text"));
                m.put("min", o.optInt("min"));
                out.add(m);
            }
            java.util.Collections.sort(out, new java.util.Comparator<Map<String, Object>>() {
                @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                    return ((Number) a.get("min")).intValue() - ((Number) b.get("min")).intValue();
                }
            });
        } catch (Throwable ignored) {}
        return out;
    }

    /** 精确排期：把下一个「未到点」的目标排到它的计划时刻。只负责准时；后台被压制由 sweepDue 兜底。 */
    private Runnable pendingTimerFire = null;   // 待触发的精确到点任务
    private Runnable pendingSweep = null;       // 待触发的巡检查道任务

    private void scheduleTimerPlan() {
        try {
            if (!TIMER_ENABLED) return;
            if (!inWindow()) return;              // 未到点目标只在窗口内，窗口外交给 sweepDue 补
            if (pendingTimerFire != null) return;
            final int _schedAcc = currentAccount();
            String prefix = accountPrefix();
            ensureTimerPlan(prefix);
            List<Map<String, Object>> plan = timerPlan(prefix);
            java.util.Calendar c = java.util.Calendar.getInstance();
            int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            for (Map<String, Object> m : plan) {
                String id = String.valueOf(m.get("id"));
                if (todayStr().equals(prefs.getString(kLast(prefix, id), ""))) continue;
                if (isPendingFresh(prefix, id)) continue;
                int fireMin = ((Number) m.get("min")).intValue();
                if (fireMin <= nowMin) continue;   // 已到点 → 交给 sweepDue 立即处理
                long delayMs = (fireMin - nowMin) * 60000L - c.get(java.util.Calendar.SECOND) * 1000L;
                if (delayMs < 0) delayMs = 0;
                armTask(m, delayMs, false, "[定时] 下一目标 " + m.get("text") + " 于 " + hhmm(fireMin) + " 触发", _schedAcc);
                return;
            }
        } catch (Throwable t) {
            logException("[定时] 排期", t);
        }
    }

    /**
     * 到点巡检：处理「计划时刻已到/已过但今天未签」的目标。
     * 窗口内 = 正常签到（3~15 秒内执行）；窗口结束后 = 错过补签（1~5 分钟随机）。
     * 这是正常签到的可靠通道：不依赖 postDelayed 的准时性，
     * 后台被 Doze/冻结压制过，进程一恢复也能马上把该签的签上。
     */
    private void sweepDue() {
        try {
            if (!TIMER_ENABLED) return;
            boolean win = inWindow();
            boolean mb = inMissBackTime();
            if (!win && !mb) return;
            if (pendingSweep != null) return;
            final int _schedAcc = currentAccount();
            String prefix = accountPrefix();
            ensureTimerPlan(prefix);
            List<Map<String, Object>> plan = timerPlan(prefix);
            java.util.Calendar c = java.util.Calendar.getInstance();
            int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            for (Map<String, Object> m : plan) {
                String id = String.valueOf(m.get("id"));
                if (todayStr().equals(prefs.getString(kLast(prefix, id), ""))) continue;
                if (isPendingFresh(prefix, id)) continue;
                int fireMin = ((Number) m.get("min")).intValue();
                if (fireMin > nowMin) continue;        // 还没到点
                long delayMs;
                String tag;
                if (win) {
                    delayMs = 3000L + (long) (random.nextInt(12000));      // 窗口内：3~15 秒内签
                    tag = "[定时] 到点签到 " + m.get("text") + "（计划 " + hhmm(fireMin) + "）";
                } else {
                    long retryAt = prefs.getLong(kRetryAt(prefix, id), 0L);
                    if (System.currentTimeMillis() < retryAt) continue;
                    if (prefs.getInt(kRetry(prefix, id), 0) >= RETRY_LIMIT) continue;
                    delayMs = 60000L + (long) (random.nextInt(240000));    // 窗口后：1~5 分钟随机补
                    tag = "[定时] 错过补签 " + m.get("text") + "（原计划 " + hhmm(fireMin) + "）约 " + (delayMs / 60000L) + " 分钟后触发";
                }
                armTask(m, delayMs, true, tag, _schedAcc);
                return;
            }
        } catch (Throwable t) {
            logException("[定时] 巡检", t);
        }
    }

    /** 排一个待执行的签到任务（sweep=true 走巡检查道，false 走精确排期）。 */
    private void armTask(final Map<String, Object> m, long delayMs, final boolean sweep, String tag, final int acc) {
        final Map<String, Object> fm = m;
        // 账号在「排任务时」就钉死：延迟可达 1~5 分钟，期间用户可能切号。
        // 以前执行时才取 accountPrefix()/currentAccount()，会把旧账号的目标
        // 用新账号发出去（用户反馈「账号1给账号2配置的 bot 发消息」）。
        Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    if (sweep) pendingSweep = null; else pendingTimerFire = null;
                    if (!TIMER_ENABLED || (!inWindow() && !inMissBackTime())) { kickSchedule(); return; }
                    // 账号变了就作废这次任务：它属于旧账号，不能拿新账号发
                    if (acc != currentAccount()) {
                        jlog("[定时] 账号已切换（任务属 acc" + acc + "，当前 acc" + currentAccount() + "），取消本次任务");
                        kickSchedule(); return;
                    }
                    String fPrefix = accountPrefix(acc);
                    String fid = String.valueOf(fm.get("id"));
                    if (todayStr().equals(prefs.getString(kLast(fPrefix, fid), ""))) { kickSchedule(); return; }
                    long fdid = ((Number) fm.get("did")).longValue();
                    Map<String, Object> entry = findEntryById(fid);
                    if (entry == null) { kickSchedule(); return; }
                    jlog("[定时] 执行签到 " + fdid + " " + (KIND_CB.equals(fm.get("kind")) ? "[回调] " : "text=") + fm.get("text"));
                    sendSign(entry, acc);
                } catch (Throwable ignored) {}
            }
        };
        // 先取消同类型的旧任务，再排新的。
        // 只覆盖引用是不够的：旧回调仍留在消息队列里，会与新任务同时触发
        //（同一目标重复签到），而且旧任务执行时会把引用清成 null，
        // 让调度器以为"没有待发任务"从而再排一个 —— 任务会越滚越多。
        if (sweep) {
            if (pendingSweep != null) { mainHandler.removeCallbacks(pendingSweep); pendingSweep = null; }
            pendingSweep = r;
        } else {
            if (pendingTimerFire != null) { mainHandler.removeCallbacks(pendingTimerFire); pendingTimerFire = null; }
            pendingTimerFire = r;
        }
        mainHandler.postDelayed(r, delayMs);
        jlog(tag);
    }

    private final Object SCHED_LOCK = new Object();

    /** 统一调度入口：巡检 + 排期。设置变更 / 触发源 / 签到完成后都走这里。串行化防重排。 */
    private void kickSchedule() {
        synchronized (SCHED_LOCK) {
            try { sweepDue(); } catch (Throwable ignored) {}
            try { scheduleTimerPlan(); } catch (Throwable ignored) {}
        }
    }

    /** 三档心跳间隔：活跃 / 已签完 / 窗口外。 */
    private static final long TICK_ACTIVE_MS   = 45000L;              // 窗口内、有未签目标
    private static final long TICK_IDLE_MS     = 10L * 60 * 1000L;    // 窗口内、今天已签完
    private static final long TICK_OFFHOURS_MS = 15L * 60 * 1000L;    // 窗口外

    /** 根据当前状态决定下一次心跳间隔（毫秒）。
     *  窗口**进入**时刻不靠它，靠 scheduleWindowWake 的精确闹钟；
     *  这里只负责"平时多久看一眼"，避免整夜每 45 秒唤醒一次（耗电/发热的常见来源）。 */
    private long nextTickInterval() {
        try {
            if (!inWindow() && !inMissBackTime()) return TICK_OFFHOURS_MS;
            if (!hasUnsignedTarget()) return TICK_IDLE_MS;
        } catch (Throwable _eTk) { noteSwallowed("nextTickInterval", _eTk); }
        return TICK_ACTIVE_MS;
    }


    // 后台心跳：每 45~60 秒巡检一次到点未签目标（正常签到靠它兜住后台被压制的情况），
    // 同时给下一个未到点目标排精确闹钟。进程活着就一直跑，前台后台无差别。
    private boolean tickLoopOn = false;

    private void scheduleTickLoop() {
        if (tickLoopOn) return;
        tickLoopOn = true;
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    syncNow();   // 跨客户端同步：先并别家的状态，再把本机写出去
                } catch (Throwable _e23) { noteSwallowed("scheduleTickLoop", _e23); }
                try {
                    if (TIMER_ENABLED) {
                        kickSchedule();
                    } else if (inWindow()) {
                        // 非定时模式：遍历所有启用账号，各有未签就签（含非当前账号）
                        int cur = currentAccount();
                        if (hasUnsignedTarget()) enqueueTry("心跳检测");
                        int nAcc = Math.max(1, activatedAccounts());
                        for (int i = 0; i < nAcc; i++) {
                            if (i == cur) continue;
                            if (!isAccountEnabled(i)) continue;
                            if (accountHasUnsigned(i)) {
                                final int fAcc = i;
                                mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                                    try { trySignAllFor("心跳(账号" + (fAcc + 1) + ")", false, fAcc); } catch (Throwable _e24) { noteSwallowed("scheduleTickLoop", _e24); }
                                } }, 5000L + (long) (random.nextInt(10000)));
                            }
                        }
                    }
                } catch (Throwable _e25) { noteSwallowed("scheduleTickLoop", _e25); }
                // 自适应间隔：窗口内且有未签目标才保持高频，其余场景大幅降频，
                // 避免整夜每 45 秒唤醒一次（耗电/发热的常见来源）。
                long base = nextTickInterval();
                long jitter = base >= TICK_IDLE_MS ? 0L : (long) (random.nextInt(15000));
                mainHandler.postDelayed(this, base + jitter);
            }
        }, 4000L);
    }

    /** 指定账号是否还有今天没签的目标（读 prefs，不依赖内存缓存）。 */
    private boolean accountHasUnsigned(int account) {
        try {
            String prefix = accountPrefix(account);
            String today = todayStr();
            List<Map<String, Object>> l = new ArrayList<>();
            loadTargetsInto(prefix, l);
            if (l.isEmpty()) return false;
            for (Map<String, Object> m : l) {
                String id = entryId(m);
                if (isFrozen(prefix, id)) continue;
                if (!today.equals(prefs.getString(kLast(prefix, id), ""))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 当前账号是否还有今天没签的目标 */
    private boolean hasUnsignedTarget() {
        try {
            String prefix = accountPrefix();
            String today = todayStr();
            List<Map<String, Object>> l = new ArrayList<>();
            loadTargetsInto(prefix, l);
            for (Map<String, Object> m : l) {
                if (!today.equals(prefs.getString(kLast(prefix, entryId(m)), ""))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String hhmm(int min) {
        return SignLogic.hhmm(min);
    }

    /** 目标在当日时刻表中的计划分钟；不在表中返回 -1 */

    /** 补签列表：今日时刻表里每个目标的补签状态——待补（已过点未签）/ 已补 / 未到点 / 已跳过。 */
    private void showMissList(Activity act) {
        try {
            String fPrefix = accountPrefix();
            ensureTimerPlan(fPrefix);
            List<Map<String, Object>> plan = timerPlan(fPrefix);
            java.util.Map<String, Integer> minById = new java.util.HashMap<>();
            for (Map<String, Object> m : plan) {
                minById.put(String.valueOf(m.get("id")), ((Number) m.get("min")).intValue());
            }
            List<Map<String, Object>> all = new ArrayList<>();
            loadTargetsInto(fPrefix, all);
            if (all.isEmpty()) { toast("还没有签到目标"); return; }

            java.util.Calendar c = java.util.Calendar.getInstance();
            int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            int[] r = windowRange();
            boolean inWin = inWindow();

            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(6), dp(6), dp(6), dp(6));
            TextView head = new TextView(act);
            head.setTextSize(Theme.TS_CAPTION); head.setTextColor(Theme.termMuted(act)); head.setTypeface(android.graphics.Typeface.MONOSPACE);
            StringBuilder hb = new StringBuilder(Lang.tf("今日补签 · {0} 目标", all.size()));
            if (r != null) hb.append(Lang.tf(" · 窗口 {0}-{1}", String.format("%02d:%02d", r[0] / 60, r[0] % 60), String.format("%02d:%02d", r[1] / 60, r[1] % 60)));
            hb.append(TIMER_ENABLED ? (MISS_BACK ? Lang.tf(" · 补签开(至 {0})", String.format("%02d:%02d", MISS_DEADLINE / 60, MISS_DEADLINE % 60)) : Lang.tr(" · 补签关")) : Lang.tr(" · 定时关"));
            head.setText(hb.toString());
            head.setPadding(dp(4), 0, dp(4), dp(8));
            box.addView(head);

            int missN = 0, doneN = 0, waitN = 0, skipN = 0;
            for (Map<String, Object> m : all) {
                final long did = entryDid(m);
                String id = entryId(m);
                String txt = entryText(m);
                if (txt.length() > 14) txt = txt.substring(0, 14) + "…";
                boolean done = todayStr().equals(prefs.getString(kLast(fPrefix, id), ""));
                Integer mn = minById.get(id);
                int fireMin = mn != null ? mn.intValue() : -1;

                // 状态归类
                String badge, sub;
                int col;
                if (done) {
                    badge = Lang.tr("已补"); col = Theme.termGreen(act);
                    sub = fireMin >= 0 ? Lang.tf("原计划 {0} · 已完成", String.format("%02d:%02d", fireMin / 60, fireMin % 60)) : Lang.tr("今日已签");
                    doneN++;
                } else if (fireMin < 0) {
                    badge = Lang.tr("无计划"); col = Theme.termMuted(act);
                    sub = Lang.tr("未排进今日时刻表"); skipN++;
                } else if (fireMin <= nowMin) {
                    if (!TIMER_ENABLED || !MISS_BACK) {
                        badge = Lang.tr("已跳过"); col = Theme.termMuted(act);
                        sub = Lang.tf("原计划 {0} · {1}", String.format("%02d:%02d", fireMin / 60, fireMin % 60), Lang.tr(!TIMER_ENABLED ? "定时未开" : "补签未开"));
                        skipN++;
                    } else if (nowMin > MISS_DEADLINE) {
                        badge = Lang.tr("已过期"); col = Theme.termMuted(act);
                        sub = Lang.tf("原计划 {0} · 已过补签截止 {1}", String.format("%02d:%02d", fireMin / 60, fireMin % 60), String.format("%02d:%02d", MISS_DEADLINE / 60, MISS_DEADLINE % 60));
                        skipN++;
                    } else {
                        badge = Lang.tr("待补"); col = Theme.termAmber(act);
                        sub = Lang.tf("原计划 {0} · 后台 1~5 分钟随机补", String.format("%02d:%02d", fireMin / 60, fireMin % 60));
                        waitN++; missN++;
                    }
                } else {
                    badge = Lang.tr("未到点"); col = Theme.termCyan(act);
                    sub = Lang.tf("计划 {0} · 到时自动签", String.format("%02d:%02d", fireMin / 60, fireMin % 60));
                    waitN++;
                }

                // 两行式：第一行「徽章 + 名称/指令 + 操作」，第二行「状态说明」全宽。
                // 原来挤在一行，长状态文字会把名称列压到重叠/截断（官方版/Nagram 字体更宽时尤甚）。
                boolean isChatRow = "chat".equals(entryPeerKind(m));
                int rowAccent = isChatRow ? Theme.termPink(act) : Theme.termCyan(act);
                LinearLayout wrapRow = new LinearLayout(act);
                wrapRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams wrlp = new LinearLayout.LayoutParams(-1, -2);
                wrlp.setMargins(0, dp(2), 0, dp(2));
                wrapRow.setLayoutParams(wrlp);
                View rbar = new View(act);
                android.graphics.drawable.GradientDrawable rbd = new android.graphics.drawable.GradientDrawable();
                rbd.setColor(rowAccent);
                rbd.setCornerRadius(dp(2));
                rbar.setBackground(rbd);
                LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT);
                rblp.setMargins(0, dp(2), 0, dp(2));
                wrapRow.addView(rbar, rblp);

                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackground(termBorder(act, Theme.termCard(act), done
                        ? Theme.withAlpha(Theme.termGreen(act), 0x40)
                        : (fireMin >= 0 && fireMin <= nowMin && TIMER_ENABLED && MISS_BACK && inWin)
                        ? Theme.withAlpha(Theme.termAmber(act), 0x30)
                        : Theme.withAlpha(rowAccent, 0x2E)));
                row.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

                LinearLayout line1 = new LinearLayout(act);
                line1.setOrientation(LinearLayout.HORIZONTAL);
                line1.setGravity(android.view.Gravity.CENTER_VERTICAL);

                TextView bg = new TextView(act);
                bg.setTextSize(Theme.TS_CAPTION); bg.setTypeface(Theme.monoBold());
                bg.setText(badge);
                bg.setTextColor(col);
                bg.setBackground(termBorder(act, Theme.withAlpha(col, 0x12), Theme.withAlpha(col, 0x59)));
                bg.setPadding(dp(6), dp(3), dp(6), dp(3));
                line1.addView(bg, new LinearLayout.LayoutParams(-2, -2));
                TextView pc = peerChip(act, m);
                LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(-2, -2);
                pclp.leftMargin = dp(4);
                line1.addView(pc, pclp);

                LinearLayout col2 = new LinearLayout(act);
                col2.setOrientation(LinearLayout.VERTICAL);
                col2.setPadding(dp(8), 0, dp(4), 0);
                String title = entryDisplayName(m);
                TextView t1 = new TextView(act);
                t1.setTextSize(Theme.TS_BODY); t1.setTextColor(Theme.termTxt(act)); t1.setTypeface(Theme.monoBold());
                t1.setText(title);
                t1.setSingleLine(true);
                t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col2.addView(t1);
                TextView t2 = new TextView(act);
                t2.setTextSize(Theme.TS_CAPTION); t2.setTextColor(Theme.termMuted(act)); t2.setTypeface(Theme.mono());
                t2.setText(txt);
                t2.setSingleLine(true);
                t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col2.addView(t2);
                line1.addView(col2, new LinearLayout.LayoutParams(0, -2, 1f));

                // 待补状态：给个手动触发入口，不必等后台随机延迟
                boolean canFire = !done && fireMin >= 0 && fireMin <= nowMin && TIMER_ENABLED
                        && MISS_BACK && nowMin <= MISS_DEADLINE;
                if (canFire) {
                    TextView go = new TextView(act);
                    go.setText(Lang.tr("补"));
                    go.setTextSize(Theme.TS_SECOND);
                    go.setTextColor(Theme.termAmber(act));
                    go.setTypeface(Theme.monoBold());
                    go.setGravity(android.view.Gravity.CENTER);
                    go.setPadding(dp(10), dp(5), dp(10), dp(5));
                    go.setBackground(termBorder(act, Theme.withAlpha(Theme.termAmber(act), 0x14),
                            Theme.withAlpha(Theme.termAmber(act), 0x66)));
                    go.setClickable(true);
                    final Map<String, Object> fm2 = m;
                    go.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            toast(Lang.tf("已发起补签：{0}", entryText(fm2)));
                            sendSign(fm2, currentAccount());
                        }
                    });
                    LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
                    glp.leftMargin = dp(6);
                    line1.addView(go, glp);
                }
                row.addView(line1, new LinearLayout.LayoutParams(-1, -2));

                TextView t3 = new TextView(act);
                t3.setTextSize(Theme.TS_CAPTION); t3.setTextColor(col); t3.setTypeface(Theme.text());
                t3.setText(sub);
                t3.setPadding(dp(6), dp(4), 0, 0);
                row.addView(t3, new LinearLayout.LayoutParams(-1, -2));

                wrapRow.addView(row);
                box.addView(wrapRow);
            }

            TextView foot = new TextView(act);
            foot.setTextSize(Theme.TS_CAPTION);
            foot.setTextColor(Theme.termFaint(act));
            foot.setTypeface(android.graphics.Typeface.MONOSPACE);
            foot.setPadding(dp(4), dp(6), dp(4), 0);
            foot.setText(Lang.tf("已补 {0} · 待补 {1} · 已跳过 {2}", doneN, waitN, skipN) + (missN > 0 ? Lang.tf(" · 错过 {0}", missN) : ""));
            box.addView(foot);
            showDialog(act, "补签列表", box, "关闭");
        } catch (Throwable t) {
            logd("补签列表异常: " + t);
        }
    }

    private int timerPlanMin(String prefix, String id) {
        try {
            for (Map<String, Object> m : timerPlan(prefix)) {
                if (String.valueOf(m.get("id")).equals(id)) return ((Number) m.get("min")).intValue();
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** 定时模式下距下一次到点目标的文案（如 "08:30 之后 · 约 23 分钟"）；非定时/无计划返回 null */
    private String nextTimerLabel() {
        try {
            if (!TIMER_ENABLED) return null;
            String prefix = accountPrefix();
            ensureTimerPlan(prefix);
            List<Map<String, Object>> plan = timerPlan(prefix);
            java.util.Calendar c = java.util.Calendar.getInstance();
            int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            for (Map<String, Object> m : plan) {
                String id = String.valueOf(m.get("id"));
                if (todayStr().equals(prefs.getString(kLast(prefix, id), ""))) continue;
                int fireMin = ((Number) m.get("min")).intValue();
                if (fireMin < nowMin) continue;
                String tm = String.format("%02d:%02d", fireMin / 60, fireMin % 60);
                int left = fireMin - nowMin;
                if (left <= 1) return tm;
                return Lang.tf("{0}  ·  约 {1} 分钟后", tm, left);
            }
            int[] r = windowRange();
            if (r != null) {
                String tm = String.format("%02d:%02d", r[0] / 60, r[0] % 60);
                return Lang.tf("{0}（明日）", tm);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 定时模式下所有自动触发入口统一走这里（窗口外不动作） */
    private void timerHook(String reason) {
        if (!TIMER_ENABLED) { enqueueTry(reason); return; }
        if (!inWindow() && !inMissBackTime()) { logd("[定时] 窗口外(" + reason + ")，跳过"); return; }
        kickSchedule();
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
                cell.setTextSize(Theme.TS_CAPTION);
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
            legend.setTextSize(Theme.TS_CAPTION);
            legend.setTextColor(Theme.termFaint(act));
            legend.setPadding(0, dp(2), 0, 0);
            legend.setText(Lang.tr("1 = 13 天前 · 14 = 今天（绿 = 已签到）"));
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
        tip.setTextSize(Theme.TS_SECOND);
        tip.setTextColor(Theme.termMuted(act));
        tip.setTypeface(android.graphics.Typeface.MONOSPACE);
        tip.setText(Lang.tr("内置为通用示例模板，点选后把 bot ID 和指令改成你的签到 bot。"));
        box.addView(tip);
        for (final String[] p : PRESETS) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            TextView t1 = new TextView(act);
            t1.setTextSize(Theme.TS_BODY);
            t1.setTextColor(Theme.termTxt(act));
            t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            t1.setText(Lang.tr(p[0]) + "  ·  " + p[1] + "  →  " + p[2]);
            row.addView(t1);
            TextView t2 = new TextView(act);
            t2.setTextSize(Theme.TS_CAPTION);
            t2.setTextColor(Theme.termMuted(act));
            t2.setTypeface(android.graphics.Typeface.MONOSPACE);
            t2.setText(Lang.tr(p[3]));
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
        ok.setText(Lang.tr("添加"));
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try {
                long did = Long.parseLong(uid.getText().toString().trim());
                String t = cmd.getText().toString().trim();
                if (t.length() == 0) { toast("指令不能为空"); return; }
                if (findTextEntry(did, t) != null) { toast("该 bot 已存在相同指令"); return; }
                unblockBot(did);
                learnTarget(did, t);
                Map<String, Object> m = findTextEntry(did, t);
                if (m != null) toast(Lang.tf("✅ 已添加 {0} → {1}", did, t));
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
        ok.setText(Lang.tr("添加并立即签到"));
        ok.setOnClickListener(v -> {
            try {
                long did = Long.parseLong(uid.getText().toString().trim());
                String t = cmd.getText().toString().trim();
                if (t.length() == 0) { toast("指令不能为空"); return; }
                if (findTextEntry(did, t) != null) { toast("该 bot 已存在相同指令"); return; }
                unblockBot(did);
                learnTarget(did, t);
                Map<String, Object> m = findTextEntry(did, t);
                if (m != null) {
                    toast(Lang.tf("✅ 已添加 {0} → {1}，立即签到…", did, t));
                    sendSign(m, currentAccount());
                }
            } catch (Throwable e) {
                toast("UID 格式错误");
            }
        });
        box.addView(ok);
        TextView tip = new TextView(act);
        tip.setTextSize(Theme.TS_SECOND);
        tip.setTextColor(android.graphics.Color.parseColor(txtSub(act)));
        tip.setText(Lang.tr("提示：同一 bot 可添加多条指令（多指令自动签到）；\n回调按钮型签到无需手动添加——直接点一次 bot 的签到按钮即可自动学习。"));
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
            withIconText(act, all, "bolt", Lang.tf("全部签到（{0} 个条目）", targets.size()));
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


    private TextView simpleTextView(Context c, String text) {
        TextView t = new TextView(c);
        t.setTextSize(Theme.TS_BODY);
        t.setTextColor(Theme.termTxt(c));
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setText(Lang.tr(text));
        return t;
    }

    private android.widget.Switch swRow(Context c, String label, boolean on) {
        android.widget.Switch s = new android.widget.Switch(c);
        s.setText(Lang.tr(label)); s.setTextSize(Theme.TS_BODY); s.setTextColor(Theme.termTxt(c)); s.setTypeface(android.graphics.Typeface.MONOSPACE); s.setChecked(on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, Theme.dp(c,6), 0, Theme.dp(c,6)); s.setLayoutParams(lp);
        s.setPadding(Theme.dp(c,4), Theme.dp(c,10), Theme.dp(c,4), Theme.dp(c,10));
        return s;
    }


    private void showSettings(Activity act) {
        try {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(8), dp(16), dp(8));

            // ── 外观 ──
            sectionHeader(box, act, "▍外观");
            LinearLayout card0 = new LinearLayout(act); card0.setOrientation(LinearLayout.VERTICAL);
            card0.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termPink(act), 0x26)));
            card0.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams c0lp = new LinearLayout.LayoutParams(-1, -2);
            c0lp.setMargins(0, dp(2), 0, dp(6));
            card0.setLayoutParams(c0lp);
            final int[] tMode = { THEME_MODE };
            final Button tbSw = mkBtn(act); tbSw.setTextSize(Theme.TS_BODY);
            final Runnable refreshT = new Runnable() { @Override public void run() {
                tbSw.setText(Lang.tr(tMode[0] == 0 ? "自动（跟宿主主题）" : (tMode[0] == 1 ? "始终日间（浅色）" : "始终夜间（终端风）")));
            } };
            tbSw.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                tMode[0] = (tMode[0] + 1) % 3;
                refreshT.run();
                Theme.mode = tMode[0];
                toast(Lang.tf("主题：{0}（保存后生效）", Lang.tr(tMode[0] == 0 ? "自动" : (tMode[0] == 1 ? "日间" : "夜间"))));
            } });
            refreshT.run();
            card0.addView(tbSw, new LinearLayout.LayoutParams(-1, -2));
            TextView tTip = new TextView(act); tTip.setTextSize(Theme.TS_CAPTION); tTip.setTextColor(Theme.termFaint(act));
            tTip.setTypeface(Theme.text());
            tTip.setText(Lang.tr("自动 = 读宿主当前配色（取不到再看系统深色）；识别不准时可手动锁定，保存后重开界面生效"));
            tTip.setPadding(dp(4), dp(4), dp(4), 0);
            card0.addView(tTip);

            // 界面语言：跟随系统 / 中文 / English
            final int[] lMode = { Lang.MODE };
            final Button lbSw = mkBtn(act); lbSw.setTextSize(Theme.TS_BODY);
            final TextView lTip = new TextView(act); lTip.setTextSize(Theme.TS_CAPTION); lTip.setTextColor(Theme.termFaint(act)); lTip.setTypeface(Theme.text());
            final Runnable refreshL = new Runnable() { @Override public void run() {
                lbSw.setText(Lang.tr(lMode[0] == 0 ? "界面语言：跟随系统" : (lMode[0] == 1 ? "界面语言：中文" : "界面语言：English")));
            } };
            lbSw.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                lMode[0] = (lMode[0] + 1) % 3;
                Lang.MODE = lMode[0];
                refreshL.run();
                lTip.setText(Lang.tr(lMode[0] == 0 ? "界面语言：跟随系统" : (lMode[0] == 1 ? "界面语言：中文" : "界面语言：English")));
                toast(Lang.tf("语言：{0}（保存后生效）", Lang.tr(lMode[0] == 0 ? "跟随系统" : (lMode[0] == 1 ? "中文" : "English"))));
            } });
            refreshL.run();
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
            llp.topMargin = dp(8);
            card0.addView(lbSw, llp);
            lTip.setText(Lang.tr(lMode[0] == 0 ? "跟随系统：系统语言非中文时自动切英文。" : "保存后生效，日志不翻译。"));
            lTip.setPadding(dp(4), dp(4), dp(4), 0);
            card0.addView(lTip);
            box.addView(card0);

            // ── 通知 ──
            sectionHeader(box, act, "▍通知");
            LinearLayout cardN = new LinearLayout(act); cardN.setOrientation(LinearLayout.VERTICAL);
            cardN.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termGreen(act), 0x26)));
            cardN.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams cnlp = new LinearLayout.LayoutParams(-1, -2);
            cnlp.setMargins(0, dp(2), 0, dp(6));
            cardN.setLayoutParams(cnlp);
            final android.widget.Switch nfSw = swRow(act, "签到结果通知", NOTIFY_ON);
            nfSw.setTextSize(Theme.TS_BODY);
            cardN.addView(nfSw);
            final android.widget.Switch nfoSw = swRow(act, "只通知失败", NOTIFY_FAIL_ONLY);
            nfoSw.setTextSize(Theme.TS_BODY);
            cardN.addView(nfoSw);
            TextView nTip = new TextView(act); nTip.setTextSize(Theme.TS_CAPTION); nTip.setTextColor(Theme.termFaint(act));
            nTip.setTypeface(Theme.text());
            nTip.setText(Lang.tr("每账号每天一条摘要，发到自己的「收藏夹」（不弹系统通知）。目标连续 3 天失败会额外提醒。"));
            nTip.setPadding(dp(4), dp(4), dp(4), 0);
            cardN.addView(nTip);
            box.addView(cardN);

            // ── 签到核心 ──
            sectionHeader(box, act, "▍签到核心");
            LinearLayout card1 = new LinearLayout(act); card1.setOrientation(LinearLayout.VERTICAL);
            card1.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x26)));
            card1.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams c1lp = new LinearLayout.LayoutParams(-1, -2);
            c1lp.setMargins(0, dp(2), 0, dp(6));
            card1.setLayoutParams(c1lp);
            final android.widget.Switch tmSw = swRow(act, "定时签到", TIMER_ENABLED);
            tmSw.setTextSize(Theme.TS_BODY);
            card1.addView(tmSw);
            TextView tmSub = new TextView(act); tmSub.setTextSize(Theme.TS_CAPTION); tmSub.setTextColor(Theme.termFaint(act)); tmSub.setTypeface(Theme.text());
            tmSub.setText(Lang.tr("开 = 窗口内按随机时刻逐个签（防风控，推荐）；关 = 检测到未签就马上签（限窗口内）"));
            tmSub.setPadding(dp(4), 0, dp(4), dp(6));
            card1.addView(tmSub);
            box.addView(card1);
            // 时间选择：两个按钮弹系统时间选择器，免手输
            final int[] wRange = windowRange();
            final int[] wStart = { wRange != null ? wRange[0] : 8 * 60 + 30 };
            final int[] wEnd   = { wRange != null ? wRange[1] : 20 * 60 + 30 };
            LinearLayout wRow = new LinearLayout(act); wRow.setOrientation(LinearLayout.HORIZONTAL); wRow.setGravity(android.view.Gravity.CENTER_VERTICAL); wRow.setPadding(dp(4), dp(4), dp(4), dp(4));
            TextView wLab = new TextView(act); wLab.setText(Lang.tr("签到时间")); leadIcon(act, wLab, "clock", Theme.termCyan(act)); wLab.setTextSize(Theme.TS_BODY); wLab.setTextColor(Theme.termTxt(act));
            wLab.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            wRow.addView(wLab, new LinearLayout.LayoutParams(0, -2, 1f));
            final Button wb1 = mkBtn(act); wb1.setTextSize(Theme.TS_BODY);
            final Button wb2 = mkBtn(act); wb2.setTextSize(Theme.TS_BODY);
            final Runnable refreshW = new Runnable() { @Override public void run() {
                wb1.setText(String.format("%02d:%02d", wStart[0] / 60, wStart[0] % 60));
                wb2.setText(String.format("%02d:%02d", wEnd[0] / 60, wEnd[0] % 60));
            } };
            wb1.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                new android.app.TimePickerDialog(act, new android.app.TimePickerDialog.OnTimeSetListener(){
                    @Override public void onTimeSet(android.widget.TimePicker tp, int h, int m) { wStart[0] = h * 60 + m; refreshW.run(); }
                }, wStart[0] / 60, wStart[0] % 60, true).show();
            } });
            wb2.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                new android.app.TimePickerDialog(act, new android.app.TimePickerDialog.OnTimeSetListener(){
                    @Override public void onTimeSet(android.widget.TimePicker tp, int h, int m) { wEnd[0] = h * 60 + m; refreshW.run(); }
                }, wEnd[0] / 60, wEnd[0] % 60, true).show();
            } });
            refreshW.run();
            wRow.addView(wb1, new LinearLayout.LayoutParams(0, -2, 1.2f));
            TextView wSep = new TextView(act); wSep.setText(" — "); wSep.setTextColor(Theme.termMuted(act)); wSep.setGravity(android.view.Gravity.CENTER);
            wRow.addView(wSep, new LinearLayout.LayoutParams(-2, -2));
            wRow.addView(wb2, new LinearLayout.LayoutParams(0, -2, 1.2f));
            card1.addView(wRow);
            TextView wTip = new TextView(act); wTip.setTextSize(Theme.TS_CAPTION); wTip.setTextColor(Theme.termFaint(act)); wTip.setTypeface(Theme.text());
            wTip.setText(Lang.tr("每目标在窗口内各占一段随机时刻，互不重叠"));
            wTip.setPadding(dp(4), 0, dp(4), dp(6));
            card1.addView(wTip);
            // 错开间隔：0=自动均分，>0=固定最小间隔分钟
            LinearLayout gapRow = new LinearLayout(act); gapRow.setOrientation(LinearLayout.HORIZONTAL); gapRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            gapRow.setPadding(dp(4), dp(4), dp(4), dp(6));
            TextView gapLab = new TextView(act); gapLab.setText(Lang.tr("错开间隔")); leadIcon(act, gapLab, "gap", Theme.termMuted(act)); gapLab.setTextSize(Theme.TS_SECOND); gapLab.setTextColor(Theme.termMuted(act));
            gapLab.setTypeface(android.graphics.Typeface.MONOSPACE);
            gapRow.addView(gapLab, new LinearLayout.LayoutParams(0, -2, 1f));
            Button gapMinus = mkBtn(act); gapMinus.setText("−");
            final EditText gapEd = new EditText(act);
            gapEd.setText(String.valueOf(GAP_MIN));
            gapEd.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            gapEd.setGravity(android.view.Gravity.CENTER);
            gapEd.setTextSize(Theme.TS_BODY);
            gapEd.setSingleLine(true);
            gapEd.setTextColor(Theme.termTxt(act));
            gapEd.setBackground(termBorder(act, Theme.termCardInput(act), Theme.withAlpha(Theme.termCyan(act), 0x33)));
            Button gapPlus = mkBtn(act); gapPlus.setText("+");
            gapMinus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(gapEd.getText().toString().trim()); vv=Math.max(0,vv-5); gapEd.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
            gapPlus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(gapEd.getText().toString().trim()); vv=Math.min(240,vv+5); gapEd.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
            gapRow.addView(gapMinus, new LinearLayout.LayoutParams(0, -2, 1f));
            gapRow.addView(gapEd, new LinearLayout.LayoutParams(0, -2, 1.6f));
            gapRow.addView(gapPlus, new LinearLayout.LayoutParams(0, -2, 1f));
            card1.addView(gapRow);
            TextView gapTip = new TextView(act); gapTip.setTextSize(Theme.TS_CAPTION); gapTip.setTextColor(Theme.termFaint(act)); gapTip.setTypeface(Theme.text());
            gapTip.setText(Lang.tr("0=按目标数自动均分；如设 30，则相邻目标至少隔 30 分钟"));
            gapTip.setPadding(dp(4), 0, dp(4), dp(2));
            card1.addView(gapTip);
            final android.widget.Switch mbSw = swRow(act, "错过补签", MISS_BACK);
            mbSw.setTextSize(Theme.TS_BODY);
            card1.addView(mbSw);
            TextView mbTip = new TextView(act); mbTip.setTextSize(Theme.TS_CAPTION); mbTip.setTextColor(Theme.termFaint(act)); mbTip.setTypeface(Theme.text());
            mbTip.setText(Lang.tr("关掉 TG 期间错过的签到点：开=错过后随机延迟 1~5 分钟自动补；关=错过即跳过（默认）"));
            mbTip.setPadding(dp(4), 0, dp(4), dp(2));
            card1.addView(mbTip);
            // 补签截止：窗口结束后仍可补到该时间（与签到窗口解耦，避免当天错过作废）
            final int[] mdMin = { MISS_DEADLINE };
            LinearLayout mdRow = new LinearLayout(act); mdRow.setOrientation(LinearLayout.HORIZONTAL); mdRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            mdRow.setPadding(dp(4), dp(4), dp(4), dp(6));
            TextView mdLab = new TextView(act); mdLab.setText(Lang.tr("补签截止")); leadIcon(act, mdLab, "hourglass", Theme.termMuted(act)); mdLab.setTextSize(Theme.TS_SECOND); mdLab.setTextColor(Theme.termMuted(act));
            mdLab.setTypeface(android.graphics.Typeface.MONOSPACE);
            mdRow.addView(mdLab, new LinearLayout.LayoutParams(0, -2, 1f));
            final Button mdb = mkBtn(act); mdb.setTextSize(Theme.TS_BODY);
            final Runnable refreshMd = new Runnable() { @Override public void run() {
                mdb.setText(String.format("%02d:%02d", mdMin[0] / 60, mdMin[0] % 60));
            } };
            mdb.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                new android.app.TimePickerDialog(act, new android.app.TimePickerDialog.OnTimeSetListener(){
                    @Override public void onTimeSet(android.widget.TimePicker tp, int h, int m) { mdMin[0] = h * 60 + m; refreshMd.run(); }
                }, mdMin[0] / 60, mdMin[0] % 60, true).show();
            } });
            refreshMd.run();
            mdRow.addView(mdb, new LinearLayout.LayoutParams(0, -2, 1.2f));
            card1.addView(mdRow);
            TextView mdTip = new TextView(act); mdTip.setTextSize(Theme.TS_CAPTION); mdTip.setTextColor(Theme.termFaint(act)); mdTip.setTypeface(Theme.text());
            mdTip.setText(Lang.tr("窗口结束后仍会补签到到这个时间（如 23:00），过了才真正放弃；仅「错过补签」开启时生效"));
            mdTip.setPadding(dp(4), 0, dp(4), dp(2));
            card1.addView(mdTip);
            Button planBtn = mkBtn(act); withIconText(act, planBtn, "list", "查看今日计划");
            planBtn.setTextColor(Theme.termCyan(act));
            planBtn.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                try {
                    String fPrefix = accountPrefix();
                    ensureTimerPlan(fPrefix);
                    List<Map<String, Object>> plan = timerPlan(fPrefix);
                    // 计划时刻 id -> HH:MM
                    java.util.Map<String, String> minById = new java.util.HashMap<>();
                    for (Map<String, Object> m : plan) {
                        int mn = ((Number) m.get("min")).intValue();
                        minById.put(String.valueOf(m.get("id")), String.format("%02d:%02d", mn / 60, mn % 60));
                    }
                    List<Map<String, Object>> all = new ArrayList<>();
                    loadTargetsInto(fPrefix, all);
                    if (all.isEmpty()) { toast("还没有签到目标"); return; }

                    LinearLayout pl = new LinearLayout(act);
                    pl.setOrientation(LinearLayout.VERTICAL);
                    pl.setPadding(dp(6), dp(6), dp(6), dp(6));
                    TextView head = new TextView(act);
                    head.setTextSize(Theme.TS_CAPTION); head.setTextColor(Theme.termMuted(act)); head.setTypeface(android.graphics.Typeface.MONOSPACE);
                    head.setText(Lang.tf("今日计划 · {0} 个目标 · 随机错开", all.size()));
                    head.setPadding(dp(4), 0, dp(4), dp(8));
                    pl.addView(head);

                    int doneN = 0;
                    for (Map<String, Object> m : all) {
                        final long did = entryDid(m);
                        String id = entryId(m);
                        String txt = entryText(m);
                        if (txt.length() > 14) txt = txt.substring(0, 14) + "…";
                        boolean done = todayStr().equals(prefs.getString(kLast(fPrefix, id), ""));
                        if (done) doneN++;
                        String tm = minById.get(id);

                        LinearLayout row = new LinearLayout(act);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(10), dp(8), dp(10), dp(8));
                        row.setBackground(termBorder(act, Theme.termCard(act), done
                                ? Theme.withAlpha(Theme.termGreen(act), 0x40)
                                : Theme.withAlpha(Theme.termCyan(act), 0x26)));
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                        rlp.setMargins(0, dp(2), 0, dp(2));
                        row.setLayoutParams(rlp);

                        // 状态徽章
                        TextView badge = new TextView(act);
                        badge.setTextSize(Theme.TS_CAPTION); badge.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                        badge.setPadding(dp(6), dp(3), dp(6), dp(3));
                        if (done) {
                            badge.setText(Lang.tr("已签"));
                            badge.setTextColor(Theme.termGreen(act));
                            badge.setBackground(termBorder(act, Theme.withAlpha(Theme.termGreen(act), 0x12), Theme.withAlpha(Theme.termGreen(act), 0x59)));
                        } else {
                            badge.setText(Lang.tr("待签"));
                            badge.setTextColor(Theme.termAmber(act));
                            badge.setBackground(termBorder(act, Theme.withAlpha(Theme.termAmber(act), 0x12), Theme.withAlpha(Theme.termAmber(act), 0x59)));
                        }
                        row.addView(badge, new LinearLayout.LayoutParams(-2, -2));

                        // bot 简称 + 指令
                        LinearLayout col = new LinearLayout(act);
                        col.setOrientation(LinearLayout.VERTICAL);
                        col.setPadding(dp(8), 0, dp(4), 0);
                        String bn = botName(did);
                        String title = (bn != null && bn.length() > 0) ? bn : String.valueOf(did);
                        if (title.length() > 12) title = title.substring(0, 12) + "…";
                        TextView t1 = new TextView(act);
                        t1.setTextSize(Theme.TS_BODY); t1.setTextColor(Theme.termTxt(act)); t1.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                        t1.setText(title);
                        col.addView(t1);
                        TextView t2 = new TextView(act);
                        t2.setTextSize(Theme.TS_CAPTION); t2.setTextColor(Theme.termMuted(act)); t2.setTypeface(android.graphics.Typeface.MONOSPACE);
                        t2.setText(txt);
                        t2.setPadding(0, dp(1), 0, 0);
                        col.addView(t2);
                        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

                        // 右侧时间
                        TextView time = new TextView(act);
                        time.setTextSize(Theme.TS_BODY); time.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                        if (done) {
                            long sentAt = 0L;
                            try { sentAt = prefs.getLong(fPrefix + "sent_at_" + id, 0L); } catch (Throwable ignored) {}
                            if (sentAt > 0) {
                                java.util.Calendar cc = java.util.Calendar.getInstance();
                                cc.setTimeInMillis(sentAt);
                                time.setText(String.format("%02d:%02d", cc.get(java.util.Calendar.HOUR_OF_DAY), cc.get(java.util.Calendar.MINUTE)));
                                time.setTextColor(Theme.termGreen(act));
                            } else {
                                time.setText("✔"); time.setTextColor(Theme.termGreen(act));
                            }
                        } else if (tm != null) {
                            time.setText(tm);
                            time.setTextColor(Theme.termCyan(act));
                        } else {
                            time.setText(Lang.tr("明日排"));
                            time.setTextColor(Theme.termFaint(act));
                        }
                        row.addView(time, new LinearLayout.LayoutParams(-2, -2));
                        pl.addView(row);
                    }
                    TextView foot = new TextView(act);
                    foot.setTextSize(Theme.TS_CAPTION); foot.setTextColor(Theme.termFaint(act)); foot.setTypeface(Theme.text());
                    foot.setText(Lang.tf("已签 {0} / {1} · 时间=已签时刻 / 待签计划时刻", doneN, all.size()));
                    foot.setPadding(dp(4), dp(6), dp(4), 0);
                    pl.addView(foot);
                    showDialog(act, "今日计划", pl, "关闭");
                } catch (Throwable t) { toast(Lang.tf("预览失败: {0}", t)); }
            } });
            card1.addView(planBtn);

            // ── 学习行为 ──
            sectionHeader(box, act, "▍学习行为");
            LinearLayout card2 = new LinearLayout(act); card2.setOrientation(LinearLayout.VERTICAL);
            card2.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x26)));
            card2.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams c2lp = new LinearLayout.LayoutParams(-1, -2);
            c2lp.setMargins(0, dp(2), 0, dp(6));
            card2.setLayoutParams(c2lp);
            final android.widget.Switch alSw = swRow(act, "按钮学习", AUTO_LEARN);
            card2.addView(alSw);
            TextView alSub = new TextView(act); alSub.setTextSize(Theme.TS_CAPTION); alSub.setTextColor(Theme.termFaint(act)); alSub.setTypeface(Theme.text());
            alSub.setText(Lang.tr("点一下 bot 按钮就自动加到列表"));
            alSub.setPadding(dp(4), 0, dp(4), dp(4));
            card2.addView(alSub);
            final android.widget.Switch anSw = swRow(act, "网络学习", AUTO_LEARN_NET);
            card2.addView(anSw);
            TextView anSub = new TextView(act); anSub.setTextSize(Theme.TS_CAPTION); anSub.setTextColor(Theme.termFaint(act)); anSub.setTypeface(Theme.text());
            anSub.setText(Lang.tr("自动识别你在 bot 里发的签到文本"));
            anSub.setPadding(dp(4), 0, dp(4), dp(4));
            card2.addView(anSub);
            final android.widget.Switch anCfgSw = swRow(act, "网络学习需确认", AUTO_LEARN_NET_CONFIRM);
            card2.addView(anCfgSw);
            TextView anCfgSub = new TextView(act); anCfgSub.setTextSize(Theme.TS_CAPTION); anCfgSub.setTextColor(Theme.termFaint(act)); anCfgSub.setTypeface(Theme.text());
            anCfgSub.setText(Lang.tr("命中后先进「待确认」列表，你手动确认才加入（防验证码类 bot 误加）"));
            anCfgSub.setPadding(dp(4), 0, dp(4), dp(4));
            card2.addView(anCfgSub);
            final android.widget.Switch afSw = swRow(act, "关键词过滤", AUTO_LEARN_FILTER);
            card2.addView(afSw);
            TextView afSub = new TextView(act); afSub.setTextSize(Theme.TS_CAPTION); afSub.setTextColor(Theme.termFaint(act)); afSub.setTypeface(Theme.text());
            afSub.setText(Lang.tr("只收文案命中关键词的按钮，防误加"));
            afSub.setPadding(dp(4), 0, dp(4), dp(2));
            card2.addView(afSub);
            box.addView(card2);

            // ── 关键词与策略 ──
            sectionHeader(box, act, "▍关键词与策略");
            LinearLayout card3 = new LinearLayout(act); card3.setOrientation(LinearLayout.VERTICAL);
            card3.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x26)));
            card3.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams c3lp = new LinearLayout.LayoutParams(-1, -2);
            c3lp.setMargins(0, dp(2), 0, dp(6));
            card3.setLayoutParams(c3lp);
            TextView kwLab = new TextView(act); kwLab.setText(Lang.tr("学习关键词（逗号分隔）")); leadIcon(act, kwLab, "key", Theme.termMuted(act)); kwLab.setTextSize(Theme.TS_SECOND); kwLab.setTextColor(Theme.termMuted(act));
            kwLab.setTypeface(android.graphics.Typeface.MONOSPACE); kwLab.setPadding(dp(2), dp(2), dp(2), dp(4));
            card3.addView(kwLab);
            EditText kw = adInput(act, "如: 签到,打卡,checkin", 0);
            kw.setText(LEARN_KEYWORDS == null ? "" : String.valueOf(LEARN_KEYWORDS));
            card3.addView(kw);

            // ── 排除规则（黑名单）：一行一条，支持正则 ──
            TextView exLab = new TextView(act);
            exLab.setText(Lang.tr("排除规则（一行一条，命中不学习）"));
            leadIcon(act, exLab, "trash", Theme.termPink(act));
            exLab.setTextSize(Theme.TS_SECOND); exLab.setTextColor(Theme.termMuted(act));
            exLab.setTypeface(android.graphics.Typeface.MONOSPACE);
            exLab.setPadding(dp(2), dp(10), dp(2), dp(4));
            card3.addView(exLab);
            final EditText ex = adInput(act, "如: 点击图中事物（一行一条）", 3);
            ex.setText(LEARN_EXCLUDE == null ? "" : String.valueOf(LEARN_EXCLUDE));
            ex.setMinLines(2);
            card3.addView(ex);
            TextView exTip = new TextView(act);
            exTip.setTextSize(Theme.TS_CAPTION); exTip.setTextColor(Theme.termFaint(act));
            exTip.setTypeface(Theme.text());
            exTip.setText(Lang.tr("匹配 bot 回复正文 + 按钮文案。普通关键词按子串（不分大小写）；\n用 / 包裹当正则，如 /^每日.*点击/ 。# 开头为注释。"));
            exTip.setPadding(dp(4), dp(4), dp(4), dp(2));
            card3.addView(exTip);

            // ── 回复判定词（自定义）：bot 回复换措辞导致判不出成功/失败时用 ──
            TextView okLab = new TextView(act);
            okLab.setText(Lang.tr("判定机器人回复"));
            leadIcon(act, okLab, "key", Theme.termGreen(act));
            okLab.setTextSize(Theme.TS_SECOND); okLab.setTextColor(Theme.termMuted(act));
            okLab.setTypeface(android.graphics.Typeface.MONOSPACE);
            okLab.setPadding(dp(2), dp(10), dp(2), dp(4));
            card3.addView(okLab);

            // 宽松模式：最省心的那一档 —— 学什么都收、回了就算成功
            final android.widget.Switch loSw = swRow(act, "宽松模式（来者不拒）", LOOSE_MODE);
            card3.addView(loSw);
            TextView loTip = new TextView(act); loTip.setTextSize(Theme.TS_CAPTION);
            loTip.setTextColor(Theme.termFaint(act)); loTip.setTypeface(Theme.text());
            loTip.setText(Lang.tr("开：点什么学什么（不再按关键词过滤）；判定时**只要机器人有回复就算成功**，\n但命中明确失败词（活动已结束 / 请先关注 / 未绑定 等）仍判失败。\n适合判定词千奇百怪的机器人。关：完全按下面的规则判定。"));
            loTip.setPadding(dp(4), dp(2), dp(4), dp(6));
            card3.addView(loTip);

            // 开关式：默认用内置词表判定成功/失败，不再要求用户"自己加词"。
            final android.widget.Switch rvSw = swRow(act, "自动判定成功 / 失败", JUDGE_ENABLED);
            card3.addView(rvSw);
            TextView rvTip = new TextView(act); rvTip.setTextSize(Theme.TS_CAPTION);
            rvTip.setTextColor(Theme.termFaint(act)); rvTip.setTypeface(Theme.text());
            rvTip.setText(Lang.tr("开着：按内置词表判断机器人回复是成功还是失败。\n关掉：只记录回复、不判成败（目标需要你自己确认）。"));
            rvTip.setPadding(dp(4), dp(4), dp(4), dp(2));
            card3.addView(rvTip);

            // 自定义补充词（可选）：只在需要时填，且要求开关开着
            final android.widget.Switch mySw = swRow(act, "使用我的自定义词（叠加在内置之上）", JUDGE_USE_CUSTOM);
            card3.addView(mySw);
            final EditText okW = adInput(act, "如: 领取完毕,今日完成", 0);
            okW.setText(strOf("jmb_ok_words"));
            okW.setVisibility(JUDGE_USE_CUSTOM ? android.view.View.VISIBLE : android.view.View.GONE);
            card3.addView(okW);
            final EditText failW = adInput(act, "如: 次数已用完,不可领取", 0);
            failW.setText(strOf("jmb_fail_words"));
            failW.setVisibility(JUDGE_USE_CUSTOM ? android.view.View.VISIBLE : android.view.View.GONE);
            card3.addView(failW);
            mySw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    okW.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
                    failW.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
                }
            });

            // ── 排除的 bot ──
            TextView blLab = new TextView(act);
            blLab.setText(Lang.tr("排除的 bot"));
            leadIcon(act, blLab, "bot", Theme.termPink(act));
            blLab.setTextSize(Theme.TS_SECOND); blLab.setTextColor(Theme.termMuted(act));
            blLab.setTypeface(android.graphics.Typeface.MONOSPACE);
            blLab.setPadding(dp(2), dp(10), dp(2), dp(4));
            card3.addView(blLab);
            final TextView blVal = new TextView(act);
            blVal.setTextSize(Theme.TS_CAPTION); blVal.setTextColor(Theme.termFaint(act));
            blVal.setTypeface(Theme.text());
            final Runnable refreshBl = new Runnable() { @Override public void run() {
                if (LEARN_BLOCKED_DIDS.isEmpty()) blVal.setText(Lang.tr("(未排除任何 bot)"));
                else blVal.setText(Lang.tf("已排除 {0} 个 bot", LEARN_BLOCKED_DIDS.size()));
            } };
            refreshBl.run();
            blVal.setPadding(dp(4), dp(2), dp(4), dp(4));
            card3.addView(blVal);
            Button blBtn = mkBtn(act); withIconText(act, blBtn, "bot", "选择要排除的 bot");
            blBtn.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                try { showBlockedBotPicker(act, refreshBl); } catch (Throwable t) { toast(Lang.tf("打开失败: {0}", t)); }
            } });
            card3.addView(blBtn);
            LinearLayout rlRow = new LinearLayout(act); rlRow.setOrientation(LinearLayout.HORIZONTAL); rlRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            rlRow.setPadding(dp(2), dp(8), dp(2), dp(4));
            TextView rlLabel = new TextView(act); rlLabel.setText(Lang.tr("每日重试上限")); leadIcon(act, rlLabel, "repeat", Theme.termMuted(act)); rlLabel.setTextSize(Theme.TS_SECOND); rlLabel.setTextColor(Theme.termMuted(act));
            rlLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
            rlRow.addView(rlLabel, new LinearLayout.LayoutParams(0, -2, 1f));
            Button rlMinus = mkBtn(act); rlMinus.setText("−");
            final EditText rl = new EditText(act); rl.setText(String.valueOf(RETRY_LIMIT)); rl.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); rl.setGravity(android.view.Gravity.CENTER); rl.setTextSize(Theme.TS_BODY);
            Button rlPlus = mkBtn(act); rlPlus.setText("+");
            rlMinus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(rl.getText().toString().trim()); vv=Math.max(1,vv-1); rl.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
            rlPlus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(rl.getText().toString().trim()); vv=Math.min(99,vv+1); rl.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
            rlRow.addView(rlMinus, new LinearLayout.LayoutParams(0, -2, 1f));
            rlRow.addView(rl, new LinearLayout.LayoutParams(0, -2, 2.2f));
            rlRow.addView(rlPlus, new LinearLayout.LayoutParams(0, -2, 1f));
            card3.addView(rlRow);
            TextView wcLab = new TextView(act); wcLab.setText(Lang.tr("全局默认唤醒命令")); leadIcon(act, wcLab, "keyboard", Theme.termMuted(act)); wcLab.setTextSize(Theme.TS_SECOND); wcLab.setTextColor(Theme.termMuted(act));
            wcLab.setTypeface(android.graphics.Typeface.MONOSPACE); wcLab.setPadding(dp(2), dp(6), dp(2), dp(4));
            card3.addView(wcLab);
            EditText wc = adInput(act, "如 /start；条目自带前置命令优先", 0);
            wc.setText(WAKE_CMD == null ? "" : WAKE_CMD);
            card3.addView(wc);
            box.addView(card3);

            // ── 操作 ──
            sectionHeader(box, act, "▍操作");
            Button ok = mkBtnPrimary(act);
            withIconText(act, ok, "save", "保存设置");
            ok.setOnClickListener(v -> {
                String k = kw.getText().toString().trim();
                LEARN_KEYWORDS = k.isEmpty() ? DEF_KEYWORDS : k;
                try {
                    int r = Integer.parseInt(rl.getText().toString().trim());
                    if (r > 0 && r <= 99) RETRY_LIMIT = r;
                } catch (Throwable ignored) {}
                WAKE_CMD = wc.getText().toString().trim();
                String wv = String.format("%02d:%02d-%02d:%02d", wStart[0] / 60, wStart[0] % 60, wEnd[0] / 60, wEnd[0] % 60);
                if (windowRangeOf(wv) == null) { toast("时间范围错误：结束需晚于开始"); return; }
                boolean winChanged = !String.valueOf(WINDOW).equals(wv);
                String oldWindow = WINDOW;
                WINDOW = wv;
                TIMER_ENABLED = tmSw.isChecked();
                NOTIFY_ON = nfSw.isChecked();
                NOTIFY_FAIL_ONLY = nfoSw.isChecked();
                THEME_MODE = tMode[0];
                Theme.mode = THEME_MODE;
                try { android.content.SharedPreferences.Editor le = prefs.edit(); le.putInt("jmb_lang", Lang.MODE); le.apply(); } catch (Throwable ignored) {}
                MISS_BACK = mbSw.isChecked();
                try { MISS_DEADLINE = mdMin[0]; if (MISS_DEADLINE < 0) MISS_DEADLINE = 0; if (MISS_DEADLINE > 24 * 60 - 1) MISS_DEADLINE = 24 * 60 - 1; } catch (Throwable ignored) {}
                scheduleTickLoop();   // 确保心跳在跑（开启补签后立即可用，不等触发源）
                try { GAP_MIN = Integer.parseInt(gapEd.getText().toString().trim()); if (GAP_MIN < 0) GAP_MIN = 0; if (GAP_MIN > 240) GAP_MIN = 240; } catch (Throwable ignored) {}
                // 清掉旧时刻表，重新按新设置生成（窗口/间隔/补签改动都走这里）
                try { prefs.edit().remove(kTimerPlan(accountPrefix(), todayStr())).apply(); } catch (Throwable ignored) {}
                if (winChanged) jlog("[定时] 窗口已改 " + oldWindow + " → " + wv + "，当日计划作废待重排");
                scheduleWindowWake();
                if (inWindow()) { if (TIMER_ENABLED) kickSchedule(); else enqueueTry("进入窗口"); }
                AUTO_LEARN = alSw.isChecked();
                AUTO_LEARN_NET = anSw.isChecked();
                AUTO_LEARN_NET_CONFIRM = anCfgSw.isChecked();
                AUTO_LEARN_FILTER = afSw.isChecked();
                JUDGE_ENABLED = rvSw.isChecked();
                LOOSE_MODE = loSw.isChecked();
                JUDGE_USE_CUSTOM = mySw.isChecked();
                try {
                    android.content.SharedPreferences.Editor ed = prefs.edit();
                    ed.putString("jmb_keywords", LEARN_KEYWORDS)
                      .putInt("jmb_retry", RETRY_LIMIT)
                      .putString("jmb_wake_cmd", WAKE_CMD)
                      .putInt("jmb_theme", THEME_MODE)
                      .putBoolean("jmb_alfilter", AUTO_LEARN_FILTER)
                      .putBoolean("jmb_autolearn", AUTO_LEARN)
                      .putBoolean("jmb_autolearn_net", AUTO_LEARN_NET)
                      .putBoolean("jmb_autolearn_net_confirm", AUTO_LEARN_NET_CONFIRM)
                      .putBoolean("jmb_judge", JUDGE_ENABLED)
                      .putBoolean("jmb_loose", LOOSE_MODE)
                      .putBoolean("jmb_judge_custom", JUDGE_USE_CUSTOM)
                      .putString("jmb_ok_words", okW.getText().toString().trim())
                      .putString("jmb_fail_words", failW.getText().toString().trim())
                      .putBoolean("jmb_notify", NOTIFY_ON)
                      .putBoolean("jmb_notify_fail_only", NOTIFY_FAIL_ONLY);
                    putCfg(ed, "window", WINDOW);
                    putCfg(ed, "timer", TIMER_ENABLED);
                    putCfg(ed, "gap", GAP_MIN);
                    LEARN_EXCLUDE = String.valueOf(ex.getText()).trim();
                    putCfg(ed, "exclude", LEARN_EXCLUDE);
                    putCfg(ed, "blocked_dids", blockedDidsToStr());
                    putCfg(ed, "missback", MISS_BACK);
                    putCfg(ed, "missdead", MISS_DEADLINE);
                    ed.apply();
                } catch (Throwable ignored) {}
                try { prefs.edit().putLong("jmb_config_ts", System.currentTimeMillis()).apply(); } catch (Throwable ignored) {}
                try { syncNow(true); } catch (Throwable ignored) {}
                toast("设置已保存");
                jlog("设置更新: 关键词=" + LEARN_KEYWORDS + " 重试上限=" + RETRY_LIMIT + " 唤醒命令=" + WAKE_CMD + " 窗口=" + WINDOW + " 定时=" + (TIMER_ENABLED ? "开" : "关") + " 间隔=" + GAP_MIN + " 错过补签=" + (MISS_BACK ? "开" : "关") + " 补签截止=" + String.format("%02d:%02d", MISS_DEADLINE / 60, MISS_DEADLINE % 60)
                    + " 按钮学习=" + (AUTO_LEARN ? "开" : "关") + " 网络学习=" + (AUTO_LEARN_NET ? "开" : "关"));
            });
            box.addView(ok);
            if (activatedAccounts() > 1) {
                Button applyAll = mkBtn(act);
                withIconText(act, applyAll, "globe", "把本账号配置应用到全部账号");
                applyAll.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                    applyConfigToAllAccounts();
                } });
                LinearLayout.LayoutParams aplp = new LinearLayout.LayoutParams(-1, -2);
                aplp.topMargin = dp(6);
                box.addView(applyAll, aplp);
                TextView apTip = new TextView(act);
                apTip.setTextSize(Theme.TS_CAPTION); apTip.setTextColor(Theme.termFaint(act)); apTip.setTypeface(Theme.text());
                apTip.setText(Lang.tf("当前共 {0} 个账号。默认只改本账号（{1}…），点上面按钮才会同步到其它账号。", activatedAccounts(), accountLabel(0)));
                apTip.setPadding(dp(4), dp(4), dp(4), dp(2));
                box.addView(apTip);
            }
            showDialog(act, "设置", box, "取消");
        } catch (Throwable t) {
            jlog("设置框失败: " + t);
            toast(Lang.tf("设置打开失败: {0}", t));
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
        info.setText(Lang.tf("当前 v{0}\n正在检查更新…", UpdateChecker.VERSION_NAME));
        box.addView(info);
        showDialog(act, "TGAutoSign · 检查更新", box, "关闭");
        UpdateChecker.checkAsync(appContext, true, mainHandler, r -> {
            try {
                if (r == null) { info.setText(Lang.tr("刚刚已经检查过，请稍后再试")); return; }
                lastUpdate = r;
                info.setText(r.summary(UpdateChecker.VERSION_NAME));
                if (r.newer && r.apkUrl != null) {
                    menuItem(box, "download", Lang.tf("下载 v{0} 安装包", r.version), "下载到系统「下载」目录，校验 sha256 后确认安装", "update_download");
                }
            } catch (Throwable ignored) {}
        });
    }

    private void downloadUpdate(Activity act) {
        final UpdateChecker.Result src = lastUpdate;
        if (src == null || src.apkUrl == null) { toast("该版本没有可直接下载的安装包"); return; }
        toast(Lang.tf("开始下载 v{0}…", src.version));
        jlog("下载安装包: " + src.apkUrl);
        UpdateChecker.downloadAsync(appContext, src.apkUrl, src.apkName, src.apkSha256, mainHandler, d -> {
            if (d.networkError) { jlog("下载失败: " + d.message); toast(Lang.tf("下载失败：{0}", d.message)); return; }
            jlog("安装包已保存: " + d.savedPath);
            boolean opened = UpdateChecker.openSaved(appContext, d.savedUri, d.savedPath);
            toast(Lang.tf("已保存到 {0}", d.savedPath) + Lang.tr(opened ? "，请在安装界面确认" : "，请用文件管理器点开安装"));
        });
    }

    private void doExport() {
        ConfigStore.Report rep = ConfigStore.exportAll(appContext);
        if (rep.ok) {
            logs("配置已导出: " + rep.path + "（" + rep.keys + " 项 / " + rep.prefFiles + " 个存储）");
            toast(Lang.tf("已导出 {0} 项配置\n{1}", rep.keys, rep.path));
        } else {
            loge("导出配置失败: " + rep.message);
            toast(Lang.tf("导出失败：{0}", rep.message));
        }
    }





    /** 导出运行日志到系统「下载」目录（MediaStore，无需存储权限），便于 issue 反馈 */
    /** 收集目标显示名(文本前12字 或 uid)，供「按目标过滤」循环 */
    private String[] collectTargetNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        try {
            List<Map<String, Object>> l = new ArrayList<>();
            loadTargetsInto(accountPrefix(), l);
            for (Map<String, Object> m : l) {
                String t = entryText(m);
                if (t == null || t.length() == 0) { names.add(String.valueOf(entryDid(m))); continue; }
                if (t.length() > 12) t = t.substring(0, 12) + "…";
                names.add(t);
            }
        } catch (Throwable ignored) {}
        String[] arr = names.toArray(new String[0]);
        return arr;
    }

    /** 一键诊断包：错误/警告 + 最近 50 条 + 版本/窗口/目标摘要 → 复制到剪贴板 */
    private void doCopyDiagnose(Activity act) {
        try {
            List<LogLine> all = mergedLog(4000);
            StringBuilder sb = new StringBuilder();
            sb.append("===== TGAutoSign 诊断包 v").append(UpdateChecker.VERSION_NAME);
            if (UpdateChecker.PATCH_TAG != null && UpdateChecker.PATCH_TAG.length() > 0)
                sb.append(" (").append(UpdateChecker.PATCH_TAG).append(")");
            sb.append(" =====\n");
            sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
            String _dp158 = safePkg();
            sb.append("宿主: ").append(hostLabel(_dp158)).append(" [").append(_dp158).append("] ")
              .append(hostVersion(_dp158)).append("\n");
            sb.append("宿主架构: ").append(hostAbi())
              .append("   Android: ").append(android.os.Build.VERSION.RELEASE)
              .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            sb.append("注入方式: ").append(injectHow(_dp158))
              .append("   模块: ").append(MODULE_PKG).append(" (").append(UpdateChecker.VERSION_CODE).append(")\n");
            sb.append("当前账号: ").append(accountLabel(currentAccount())).append("  目标数: ").append(targetsSnapshot().size()).append("\n");
            sb.append("签到窗口: ").append(WINDOW == null || WINDOW.isEmpty() ? "不限" : WINDOW)
              .append("  定时模式: ").append(TIMER_ENABLED ? "开" : "关").append("\n");
            sb.append("补签: ").append(MISS_BACK ? "开" : "关")
              .append("  截止: ").append(String.format("%02d:%02d", MISS_DEADLINE / 60, MISS_DEADLINE % 60))
              .append("  错开间隔: ").append(GAP_MIN).append(" 分钟\n");
            sb.append("主题来源: ").append(Theme.lastHow).append(" dark=").append(Theme.dark(appContext))
              .append(" 模式=").append(THEME_MODE).append("\n");
            long _tick = nextTickInterval();
            sb.append("心跳: ").append(tickLoopOn ? "运行中" : "未启动")
              .append("  间隔=").append(_tick / 60000L).append(" 分钟")
              .append(_tick == TICK_OFFHOURS_MS ? "（窗口外·省电）"
                      : _tick == TICK_IDLE_MS ? "（窗口内·已签完）" : "（窗口内·有未签）")
              .append("  心跳待发: ").append(pendingSweep != null ? "有" : "无")
              .append("  排期待发: ").append(pendingTimerFire != null ? "有" : "无").append("\n");
            sb.append("静默异常: ").append(swallowedCount.get()).append(" 次\n");
            sb.append("学习开关: 按钮=").append(AUTO_LEARN ? "开" : "关")
              .append("  网络=").append(AUTO_LEARN_NET ? "开" : "关")
              .append("  关键词过滤=").append(AUTO_LEARN_FILTER ? "开" : "关")
              .append("  关键词=").append(LEARN_KEYWORDS == null || LEARN_KEYWORDS.trim().isEmpty() ? "(空=全收)" : LEARN_KEYWORDS)
              .append("\n");
            sb.append("排除配置: bot=").append(LEARN_BLOCKED_DIDS.size()).append(" 个  规则=")
              .append(LEARN_EXCLUDE == null || LEARN_EXCLUDE.trim().isEmpty() ? "(无)" : LEARN_EXCLUDE)
              .append("  捕获武装=").append(captureArmed ? "是" : "否")
              .append("  武装时账号=").append(captureAcc < 0 ? "无" : accountLabel(captureAcc)).append("\n");
            sb.append("账号字段: selectedAccount=").append(lastRawAccount)
              .append("  已登录=").append(activatedAccounts());
            if (lastRawAccount < 0) sb.append("  ← 值为负，非法，本轮跳过账号操作");
            else if (lastRawAccount >= activatedAccounts())
                sb.append("  ← 大于已登录数，但按原值使用（索引即分区，不再改写）");
            sb.append("\n");
            try {
                String unk = todayUnknownReply();
                if (unk != null && unk.length() > 0)
                    sb.append("未识别回复(今日): ").append(unk).append("\n");
            } catch (Throwable ignored) {}
            sb.append("UI按钮hook: ").append(TGAutoSignEntry.BTN_HOOK_COUNT > 0
                    ? ("挂载 " + TGAutoSignEntry.BTN_HOOK_COUNT + " 个方法")
                    : "未挂载")
              .append("  实际触发 ").append(uiBtnFire.get()).append(" 次")
              .append(uiBtnFire.get() == 0 ? "（点击未走这些方法，学习/捕获靠网络层兜底）" : "").append("\n");
            int sc = 0;
            for (String m : swallowedRecent) { if (sc++ >= 5) break; sb.append("   · ").append(m).append("\n"); }
            sb.append("------------------------------\n");

            // 目标状态表
            sb.append("===== 目标状态 =====\n");
            try {
                String pfx = accountPrefix();
                List<Map<String, Object>> tl = new ArrayList<>();
                loadTargetsInto(pfx, tl);
                java.util.Map<String, Integer> planMin = new java.util.HashMap<>();
                for (Map<String, Object> pm : timerPlan(pfx)) {
                    planMin.put(String.valueOf(pm.get("id")), ((Number) pm.get("min")).intValue());
                }
                for (Map<String, Object> tm : tl) {
                    String tid = entryId(tm);
                    String tname = entryDisplayName(tm);
                    boolean tdone = todayStr().equals(prefs.getString(kLast(pfx, tid), ""));
                    int tretry = prefs.getInt(kRetry(pfx, tid), 0);
                    int tfail = prefs.getInt(pfx + "fail_streak_" + tid, 0);
                    Integer tpm = planMin.get(tid);
                    sb.append(tdone ? "● " : "○ ").append(tname)
                      .append("  [").append("chat".equals(entryPeerKind(tm)) ? "群" : "bot")
                      .append('/').append(KIND_CB.equals(entryKind(tm)) ? "回调" : "指令").append("]")
                      .append("  ").append(tdone ? "已签" : "未签")
                      .append(tpm != null ? "  计划 " + String.format("%02d:%02d", tpm / 60, tpm % 60) : "  无计划")
                      .append(tretry > 0 ? "  重试 " + tretry : "")
                      .append(tfail > 0 ? "  连败 " + tfail + " 天" : "")
                      .append("\n");
                }
                if (tl.isEmpty()) sb.append("(无目标)\n");
            } catch (Throwable t) { sb.append("(读取失败: ").append(t).append(")\n"); }
            sb.append("------------------------------\n");
            int errs = 0, warns = 0;
            for (LogLine l : all) { if (l.lv == LV_ERR) errs++; else if (l.lv == LV_WARN) warns++; }
            sb.append("错误 ").append(errs).append(" 条 · 警告 ").append(warns).append(" 条\n");
            sb.append("===== 错误/警告 =====").append("\n");
            int cnt = 0;
            for (LogLine l : all) {
                if (l.lv != LV_ERR && l.lv != LV_WARN) continue;
                sb.append(l.flat()).append("\n");
                if (++cnt >= 60) break;
            }
            sb.append("===== 最近 50 条 =====").append("\n");
            int from = Math.max(0, all.size() - 50);
            for (int i = from; i < all.size(); i++) sb.append(all.get(i).flat()).append("\n");
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("TGAutoSign 诊断包", sb.toString()));
            } catch (Throwable ignored) {}
            jlog("诊断包已复制（错误 " + errs + " · 警告 " + warns + " · 共 " + all.size() + " 条日志）");
            toast("✅ 诊断包已复制，直接粘贴发给作者即可");
        } catch (Throwable t) {
            toast(Lang.tf("生成诊断包失败: {0}", t));
        }
    }

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
                toast(Lang.tf("已导出到「下载」：{0}", fname));
            } catch (Throwable t) {
                logw("日志导出失败: " + t);
                toast(Lang.tf("日志导出失败：{0}", t));
            }
        }

        private static String accountLabel(int acc) {
            return Lang.tf("账号{0}", acc + 1);
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
                    timerHook("网络恢复");
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
                    int prev = lastAccount;
                    lastAccount = currentAccount();
                    loadTargets();
                    // 账号切换：挂着的定时任务属于上一个账号，必须取消，否则会拿错账号的时刻表触发
                    try {
                        if (pendingTimerFire != null) { mainHandler.removeCallbacks(pendingTimerFire); pendingTimerFire = null; }
                        if (pendingSweep != null) { mainHandler.removeCallbacks(pendingSweep); pendingSweep = null; }
                    } catch (Throwable _e26) { noteSwallowed("schedulePoll", _e26); }
                    jlog("检测到账号切换 acc" + prev + " -> acc" + lastAccount + "，已重载目标并重置定时任务");
                    try { kickSchedule(); } catch (Throwable _e27) { noteSwallowed("schedulePoll", _e27); }
                }
                scheduleWindowWake();
                timerHook("定时");
                schedulePoll();
            } catch (Throwable _e28) { noteSwallowed("schedulePoll", _e28); }
        }, (inWindow() || inMissBackTime()) ? POLL_INTERVAL_MS : (60L * 60L * 1000L));
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

    /**
     * 条目是否被冻结（用户就地关闭）：冻结的条目不签到、也不参与重新学习。
     * 与 snooze 的区别：冻结是"永久不要这个"，snooze 是"暂停一段"。
     */
    private boolean isFrozen(String prefix, String id) {
        try { return prefs.getBoolean(kFrozen(prefix, id), false); } catch (Throwable t) { return false; }
    }

    private void setFrozen(Map<String, Object> m, int account, boolean frozen) {
        try {
            String prefix = accountPrefix(account);
            String id = entryId(m);
            String title = entryDisplayName(m);
            prefs.edit().putBoolean(kFrozen(prefix, id), frozen).apply();
            if (frozen) { logs("【冻结】" + title + " 已冻结，不再自动签到"); toast(Lang.tf("已冻结：{0}", title)); }
            else { logs("【冻结】" + title + " 已解冻"); toast(Lang.tf("已解冻：{0}", title)); }
        } catch (Throwable t) { toast(Lang.tf("冻结操作失败: {0}", t)); }
    }

    /**
     * 排除规则匹配：一行一条。以 / 包裹的按正则（如 /^每日.*$/），其余按子串（不区分大小写）。
     * 命中任一条即返回该条规则原文，用于日志说明原因；没命中返回 null。
     */
    private String excludeHit(String haystack) {
        try {
            if (LEARN_EXCLUDE == null || LEARN_EXCLUDE.trim().length() == 0) return null;
            if (haystack == null || haystack.length() == 0) return null;
            String lower = haystack.toLowerCase(Locale.US);
            String[] lines = LEARN_EXCLUDE.split("\r?\n");
            for (String raw : lines) {
                String rule = raw == null ? "" : raw.trim();
                if (rule.length() == 0 || rule.startsWith("#")) continue;   // 空行与 # 注释跳过
                if (rule.length() > 2 && rule.startsWith("/") && rule.endsWith("/")) {
                    String pat = rule.substring(1, rule.length() - 1);
                    try {
                        if (java.util.regex.Pattern.compile(pat, java.util.regex.Pattern.CASE_INSENSITIVE)
                                .matcher(haystack).find()) return rule;
                    } catch (Throwable ignored) { /* 正则写坏就当没命中，不影响其他规则 */ }
                } else {
                    if (lower.contains(rule.toLowerCase(Locale.US))) return rule;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 学习准入统一判定。三个学习入口都必须先过这里。
     * 优先级：bot 黑名单 > 排除规则 > 白名单（关键词过滤开启时）> 放行。
     * 返回 null 表示允许学习；返回非 null 是拒绝原因（用于日志）。
     */
    private String learnDenyReason(long did, String text, String context) {
        try {
            // 宽松模式：来者不拒 —— 点什么学什么（仍然尊重「排除的 bot」，
            // 因为那是用户明确拉黑的整只 bot，不属于"判定词对不上"的范畴）。
            if (LOOSE_MODE) {
                if (did != 0 && LEARN_BLOCKED_DIDS.contains(did)) {
                    return Lang.tf("命中「排除的 bot」({0})", did);
                }
                return null;
            }
            if (did != 0 && LEARN_BLOCKED_DIDS.contains(did)) {
                return Lang.tf("命中「排除的 bot」({0})", did);
            }
            String hay = (context == null ? "" : context) + " \n " + (text == null ? "" : text);
            String hit = excludeHit(hay);
            if (hit != null) return Lang.tf("命中排除规则「{0}」", hit);
            if (AUTO_LEARN_FILTER && !keywordMatched(text)) return Lang.tr("不含学习关键词");
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 取该 bot 最近一条"带键盘的消息"的正文，作为排除规则的匹配上下文。
     * 验证码类签到 bot 的提示语（如"请在 30 秒内点击图中事物的按钮"）就挂在这条消息上，
     * 用户写一条规则即可挡住整类 bot，不必去穷举它会出什么图/什么按钮。
     */
    private String panelContext(long did) {
        try {
            PanelLive pl;
            synchronized (panelLive) { pl = panelLive.get(did); }
            if (pl == null) return "";
            synchronized (pl) {
                String t = pl.lastText;
                return t == null ? "" : t;
            }
        } catch (Throwable t) { return ""; }
    }

    /** 排除的 bot 集合 → 存储串（逗号分隔） */
    private String blockedDidsToStr() {
        try {
            if (LEARN_BLOCKED_DIDS.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (Long d : LEARN_BLOCKED_DIDS) {
                if (d == null) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(d);
            }
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    /**
     * 「排除的 bot」选择器：列出当前账号所有目标的来源 bot，
     * 勾选即加入黑名单（整只 bot 不学习、不签到）。
     */
    private void showBlockedBotPicker(final Activity act, final Runnable onDone) {
        try {
            String prefix = accountPrefix();
            List<Map<String, Object>> all = new ArrayList<>();
            loadTargetsInto(prefix, all);

            // 去重出 (did, 显示名) —— 目标列表里的 bot（已排除的跳过，避免在灰色分组重复显示）
            final List<Long> dids = new ArrayList<>();
            final List<String> names = new ArrayList<>();
            final java.util.Set<Long> seen = new java.util.HashSet<Long>();
            for (Map<String, Object> m : all) {
                long d = entryDid(m);
                if (d == 0 || seen.contains(d)) continue;
                if (LEARN_BLOCKED_DIDS.contains(d)) continue; // 已排除的进粉色分组，不在这显示
                seen.add(d);
                dids.add(d);
                names.add(entryDisplayName(m));
            }
            // 已排除的 bot 即使目标已删，也要显示（排在前面，用 botName 取名字）
            final List<Long> blockedDids = new ArrayList<>();
            final List<String> blockedNames = new ArrayList<>();
            for (Long bd : LEARN_BLOCKED_DIDS) {
                if (bd == null || seen.contains(bd)) continue; // 已在目标列表里显示过，跳过
                blockedDids.add(bd);
                String nm = botName(bd);
                blockedNames.add((nm != null && nm.length() > 0) ? nm : String.valueOf(bd));
                seen.add(bd);
            }

            // 无可选项（既无目标也无已排除记录）
            if (dids.isEmpty() && blockedDids.isEmpty()) {
                LinearLayout pl = new LinearLayout(act);
                pl.setOrientation(LinearLayout.VERTICAL);
                pl.setPadding(dp(8), dp(10), dp(8), dp(10));
                TextView e = new TextView(act); e.setTextSize(Theme.TS_BODY); e.setTextColor(Theme.termMuted(act)); e.setTypeface(Theme.text());
                e.setText(Lang.tr("当前没有可排除的对象。\n先在目标列表长按某个 bot 选「排除整只 bot」，\n或先添加目标，再来这里勾选。"));
                e.setPadding(dp(4), dp(4), dp(4), dp(4));
                pl.addView(e);
                showDialog(act, "排除的 bot", pl, "关闭");
                return;
            }

            LinearLayout pl = new LinearLayout(act);
            pl.setOrientation(LinearLayout.VERTICAL);
            pl.setPadding(dp(6), dp(6), dp(6), dp(6));
            TextView head = new TextView(act);
            head.setTextSize(Theme.TS_CAPTION); head.setTextColor(Theme.termMuted(act));
            head.setTypeface(android.graphics.Typeface.MONOSPACE);
            head.setText(Lang.tr("勾选 = 排除该 bot（不自动学习、不自动签到）"));
            head.setPadding(dp(4), 0, dp(4), dp(8));
            pl.addView(head);

            // 已排除（目标可能已删）的 bot 排最前，独立分组标注
            final List<android.widget.CheckBox> boxes = new ArrayList<>();
            final List<Long> allDids = new ArrayList<>();
            if (!blockedDids.isEmpty()) {
                TextView bh = new TextView(act); bh.setTextSize(Theme.TS_CAPTION); bh.setTextColor(Theme.termPink(act)); bh.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                bh.setText(Lang.tr("▍已排除的 bot（取消勾选可解除）"));
                bh.setPadding(dp(4), dp(4), dp(4), dp(4));
                pl.addView(bh);
                for (int i = 0; i < blockedDids.size(); i++) {
                    final long d = blockedDids.get(i);
                    android.widget.CheckBox cb = new android.widget.CheckBox(act);
                    String nm = blockedNames.get(i);
                    if (nm == null || nm.length() == 0) nm = String.valueOf(d);
                    if (nm.length() > 20) nm = nm.substring(0, 20) + "…";
                    cb.setText(nm + "  (" + d + ")");
                    cb.setTextSize(Theme.TS_BODY);
                    cb.setTextColor(Theme.termPink(act));
                    cb.setTypeface(android.graphics.Typeface.MONOSPACE);
                    cb.setChecked(true);
                    cb.setPadding(dp(6), dp(6), dp(6), dp(6));
                    boxes.add(cb); allDids.add(d);
                    pl.addView(cb);
                }
            }
            if (!dids.isEmpty()) {
                TextView th = new TextView(act); th.setTextSize(Theme.TS_CAPTION); th.setTextColor(Theme.termMuted(act)); th.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                th.setText(Lang.tr("▍目标列表里的 bot（勾选 = 排除）"));
                th.setPadding(dp(4), dp(8), dp(4), dp(4));
                pl.addView(th);
                for (int i = 0; i < dids.size(); i++) {
                    final long d = dids.get(i);
                    android.widget.CheckBox cb = new android.widget.CheckBox(act);
                    String nm = names.get(i);
                    if (nm == null || nm.length() == 0) nm = String.valueOf(d);
                    if (nm.length() > 20) nm = nm.substring(0, 20) + "…";
                    cb.setText(nm + "  (" + d + ")");
                    cb.setTextSize(Theme.TS_BODY);
                    cb.setTextColor(Theme.termTxt(act));
                    cb.setTypeface(android.graphics.Typeface.MONOSPACE);
                    cb.setChecked(LEARN_BLOCKED_DIDS.contains(d));
                    cb.setPadding(dp(6), dp(6), dp(6), dp(6));
                    boxes.add(cb); allDids.add(d);
                    pl.addView(cb);
                }
            }
            Button save = mkBtn(act); withIconText(act, save, "check", "保存");
            save.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v) {
                try {
                    LEARN_BLOCKED_DIDS.clear();
                    for (int i = 0; i < boxes.size(); i++) {
                        if (boxes.get(i).isChecked()) LEARN_BLOCKED_DIDS.add(allDids.get(i));
                    }
                    prefs.edit().putString(kBlockedDids(), blockedDidsToStr()).apply();
                    jlog("设置更新: 排除的 bot = " + (LEARN_BLOCKED_DIDS.isEmpty() ? "(无)" : blockedDidsToStr()));
                    if (onDone != null) onDone.run();
                    toast(LEARN_BLOCKED_DIDS.isEmpty() ? Lang.tr("已清空排除列表") : Lang.tf("已排除 {0} 个 bot", LEARN_BLOCKED_DIDS.size()));
                } catch (Throwable t) { toast(Lang.tf("保存失败: {0}", t)); }
            } });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = dp(8);
            pl.addView(save, blp);
            showDialog(act, "排除的 bot", pl, "关闭");
        } catch (Throwable t) { toast(Lang.tf("打开失败: {0}", t)); }
    }

    /** 该 bot 是否整体被排除 */
    private boolean isBotBlocked(long did) {
        try { return did != 0 && LEARN_BLOCKED_DIDS.contains(did); } catch (Throwable t) { return false; }
    }

    /** 解除排除：用户手动添加 = 明确想要这个 bot，自动把它从排除列表移除。 */
    private boolean unblockBot(long did) {
        try {
            if (did == 0 || !LEARN_BLOCKED_DIDS.contains(did)) return false;
            LEARN_BLOCKED_DIDS.remove(did);
            prefs.edit().putString(kBlockedDids(), blockedDidsToStr()).apply();
            jlog("手动添加 " + did + "：已自动解除排除");
            return true;
        } catch (Throwable t) { return false; }
    }
    // ── 待确认池：网络学习命中但需用户确认才加入的目标 ──
    /** 待确认池 did 列表 */
    private java.util.List<Long> pendingConfirmDids() {
        java.util.List<Long> out = new java.util.ArrayList<Long>();
        try {
            String raw = prefs.getString(kPendingConfirm(), "");
            if (raw == null || raw.trim().length() == 0) return out;
            for (String line : raw.split("\\n")) {
                String t = line.trim();
                if (t.length() == 0) continue;
                int sp = t.indexOf('|');
                if (sp < 0) continue;
                try { out.add(Long.parseLong(t.substring(0, sp).trim())); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 待确认池文本列表（与 dids 同序） */
    private java.util.List<String> pendingConfirmTexts() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            String raw = prefs.getString(kPendingConfirm(), "");
            if (raw == null || raw.trim().length() == 0) return out;
            for (String line : raw.split("\\n")) {
                String t = line.trim();
                if (t.length() == 0) continue;
                int sp = t.indexOf('|');
                if (sp < 0) continue;
                out.add(t.substring(sp + 1));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 加入待确认池（去重）。返回是否真的新增。 */
    private boolean pendingConfirmAdd(long did, String text) {
        try {
            java.util.List<Long> dids = pendingConfirmDids();
            for (Long d : dids) if (d == did) return false;
            StringBuilder sb = new StringBuilder();
            String raw = prefs.getString(kPendingConfirm(), "");
            if (raw != null && raw.trim().length() > 0) sb.append(raw).append('\n');
            sb.append(did).append('|').append(text == null ? "" : text.replace("\n", " ").replace('|', ' '));
            prefs.edit().putString(kPendingConfirm(), sb.toString()).apply();
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** 从待确认池移除指定 did。 */
    private boolean pendingConfirmRemove(long did) {
        try {
            java.util.List<Long> dids = pendingConfirmDids();
            java.util.List<String> texts = pendingConfirmTexts();
            StringBuilder sb = new StringBuilder();
            boolean removed = false;
            for (int i = 0; i < dids.size(); i++) {
                if (dids.get(i) == did) { removed = true; continue; }
                if (sb.length() > 0) sb.append('\n');
                sb.append(dids.get(i)).append('|').append(i < texts.size() ? texts.get(i) : "");
            }
            prefs.edit().putString(kPendingConfirm(), sb.toString()).apply();
            return removed;
        } catch (Throwable ignored) { return false; }
    }

    /** 确认待确认池中的指定 did（真正加入目标）。 */
    private boolean pendingConfirmAccept(long did, String text) {
        try {
            pendingConfirmRemove(did);
            learnTarget(did, text);
            return true;
        } catch (Throwable t) { return false; }
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

    /**
     * 触发源 1（结构匹配版）：官方版 TG 用 R8 混淆，didPressedBotButton 变成单字母方法名，
     * 但结构固定为 (ChatActivityEnterView, KeyboardButton)。这里从 EnterView 反解 dialogId。
     */
    public void onBotButtonEnterViewStructural(Object enterView, Object button) {
        try {
            if (button == null) return;
            long did = resolveDialogIdFromEnterView(enterView);
            if (did == 0) { logd("[按钮·结构] 取不到 dialogId，跳过学习"); return; }
            if (captureArmed) { handleTapCaptureStructural(button, did); return; }
            Object text = buttonText(button);
            if (text == null) return;
            String t = String.valueOf(text);
            String deny = !AUTO_LEARN ? "按钮学习已关闭" : learnDenyReason(did, t, panelContext(did));
            if (deny != null) { logd("[按钮·结构] uid=" + did + " text=" + t + "（" + deny + "，不自动学习）"); return; }
            if (isCallbackButton(button)) {
                byte[] data = buttonData(button);
                if (data != null && data.length > 0) {
                    int msgId = resolveMsgIdFromEnterView(enterView);
                    learnCallback(did, t, data, buttonHash(button), msgId);
                    jlog("【按钮·结构】回调学习 uid=" + did + " text=" + t + " msg_id=" + msgId);
                } else {
                    logd("[按钮·结构] uid=" + did + " text=" + t + "（无回调 data，跳过）");
                }
            } else {
                learnTarget(did, t);
                jlog("【按钮·结构】文本学习 uid=" + did + " text=" + t);
            }
        } catch (Throwable ignored) {}
    }

    /** 从 ChatActivityEnterView 反解当前对话 dialogId（官方版混淆，逐个候选试探）。 */
    private long resolveDialogIdFromEnterView(Object enterView) {
        if (enterView == null) return 0;
        // 1) 常见字段名（官方版为 O2:J / A2:J 这类）
        String[] fieldNames = {"O2", "A2", "dialogId", "dialog_id", "currentDialogId"};
        for (String fn : fieldNames) {
            try {
                Object v = getFieldVal(enterView, fn);
                if (v instanceof Number) {
                    long d = ((Number) v).longValue();
                    if (d != 0) return d;
                }
            } catch (Throwable ignored) {}
        }
        // 2) 方法 getDialogId()
        try {
            Object v = call(enterView, "getDialogId", new Class<?>[0], new Object[0]);
            if (v instanceof Number) { long d = ((Number) v).longValue(); if (d != 0) return d; }
        } catch (Throwable ignored) {}
        // 3) 持有 ChatActivity 引用：字段 X2 -> k() 返回 Peer
        try {
            Object x2 = getFieldVal(enterView, "X2");
            if (x2 != null) {
                Object peer = call(x2, "k", new Class<?>[0], new Object[0]);
                if (peer != null) {
                    Object uid = getFieldVal(peer, "user_id");
                    if (uid instanceof Number) { long d = ((Number) uid).longValue(); if (d != 0) return d; }
                    Object cid = getFieldVal(peer, "chat_id");
                    if (cid instanceof Number) { long d = ((Number) cid).longValue(); if (d != 0) return -d; }
                    Object chid = getFieldVal(peer, "channel_id");
                    if (chid instanceof Number) { long d = ((Number) chid).longValue(); if (d != 0) return -(1000000000000L + d); }
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 从 EnterView 反解最近消息 id（用于回调 msg_id 兜底；取不到返回 0）。 */
    private int resolveMsgIdFromEnterView(Object enterView) {
        if (enterView == null) return 0;
        try {
            for (String fn : new String[]{"E2", "B3"}) {
                Object v = getFieldVal(enterView, fn);
                if (v instanceof Number) { int m = ((Number) v).intValue(); if (m > 0) return m; }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 结构版捕获：把当前会话采集为回调候选。 */
    private void handleTapCaptureStructural(Object button, long did) {
        try {
            logd("[捕获·结构] uid=" + did + " 采到按钮点击");
        } catch (Throwable ignored) {}
    }

    /** 触发源 1：UI 按钮点击学习（ChatActivityEnterView.didPressedBotButton） */
    public void onBotButtonEnterView(Object proto, Object moOrNull) {
        try {
            if (proto == null) return;
            uiBtnFire.incrementAndGet();
            if (captureArmed) { handleTapCapture(proto, moOrNull); return; }
            Object did = null;
            if (moOrNull != null) { try { did = call(moOrNull, "getDialogId", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            Object text = buttonText(proto);
            if (did != null && text != null) {
                String t = String.valueOf(text);
                long u = ((Number) did).longValue();
                String deny = !AUTO_LEARN ? "按钮学习已关闭" : learnDenyReason(u, t, panelContext(u));
                if (deny != null) { logd("[按钮] uid=" + did + " text=" + t + "（" + deny + "，不自动学习；可用 捕获/调试台 手动绑定）"); return; }
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
            uiBtnFire.incrementAndGet();
            Object mo = null;
            if (cell != null) { try { mo = call(cell, "getMessageObject", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            if (captureArmed) { handleTapCapture(proto, mo); return; }
            Object did = null;
            if (mo != null) { try { did = call(mo, "getDialogId", new Class<?>[0], new Object[0]); } catch (Throwable ignored) {} }
            Object text = buttonText(proto);
            if (did != null && text != null) {
                String t = String.valueOf(text);
                long u = ((Number) did).longValue();
                String deny = !AUTO_LEARN ? "按钮学习已关闭" : learnDenyReason(u, t, panelContext(u));
                if (deny != null) { logd("[按钮] uid=" + did + " text=" + t + "（" + deny + "，不自动学习；可用 捕获/调试台 手动绑定）"); return; }
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

        try {
            if (rn0.contains("TL_messages_sendMessage")) {
                Object m0 = getFieldVal(req0, "message");
                if (m0 != null && isJmbCommand(String.valueOf(m0))) {
                    handleCommand(String.valueOf(m0));
                    return true;
                }
            }
        } catch (Throwable ignored) {}
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
                            // 只有"模块自己发起"的回调（该目标正在发送中）才乐观标记；
                            // 用户手动点按钮不该被当成签到成功（发送 ≠ 成功）
                            Map<String, Object> cbEntry = findCbEntry(u, d);
                            if (cbEntry != null && isPendingFresh(accountPrefix(), entryId(cbEntry))) {
                                markSignedFromCallback(u, d);
                            } else {
                                logd("[活动] 非模块发起的回调，不乐观标记 " + u);
                            }
                            timerHook("TG活动");
                            // 用户手动点过但按钮 hook 未捕获时，网络层兜底学习。
                            // 改革：官方版 TG 用 R8 混淆，UI 层 didPressedBotButton 匹配 0 个方法（hook 失效），
                            // 因此网络层这条兜底就是官方版唯一的回调学习入口。
                            // 判断与 UI 层保持一致：尊重「排除的 bot」「排除规则」「关键词过滤」开关，
                            // 且不拿 callback data 解码串当关键词（它是 data 不是用户可见文案）。
                            if (findCbEntry(u, d) == null && d.length > 0) {
                                String disp = cbDataLabel(d);
                                int mid0 = 0;
                                try { Object m2 = getFieldValSafe(req, "msg_id"); if (m2 instanceof Number) mid0 = ((Number) m2).intValue(); } catch (Throwable ignored) {}
                                // 先算清拒绝原因，别再把它吞掉：旧版这句谎报「被排除规则或关键词过滤」，
                                // 实际最常见的原因是「按钮学习已关闭」（AUTO_LEARN 默认 false）。
                                String deny = !AUTO_LEARN ? "按钮学习已关闭（设置→学习行为→按钮学习）" : learnDenyReason(u, disp, panelContext(u));
                                if (captureArmed) {
                                    // 捕获兜底：UI 层按钮 hook 不命中时（Nagram 实测），网络层是唯一入口。
                                    captureArmed = false;
                                    jlog("【捕获·网络兜底】acc=" + accountLabel(currentAccount()) + "(武装时 " + accountLabel(captureAcc) + ") uid=" + u + " data=" + Base64.getEncoder().encodeToString(d) + " msg_id=" + mid0);
                                    handleNetworkCapture(u, mid0, d);
                                } else if (deny == null) {
                                    try {
                                        // 注意：TL_messages_getBotCallbackAnswer 本身没有 hash 字段（官方版会抛 NoSuchFieldException），
                                        // 用容错读取，取不到就传 0。
                                        Object h = getFieldValSafe(req, "hash");
                                        learnCallback(u, disp, d, h instanceof Number ? ((Number) h).longValue() : 0L, mid0);
                                        jlog("【网络层学习·回调】acc=" + accountLabel(currentAccount()) + " " + u + " -> [" + disp + "] data=" + Base64.getEncoder().encodeToString(d) + " msg_id=" + mid0);
                                    } catch (Throwable lt) {
                                        loge("[网络层学习·回调] 失败: " + lt);
                                    }
                                } else {
                                    logd("[回调] acc=" + accountLabel(currentAccount()) + " uid=" + u + " data=" + Base64.getEncoder().encodeToString(d) + "（不学习：" + deny + "）");
                                }
                            } else {
                                logd("[回调] uid=" + u + " 跳过学习: alreadyBound=" + (findCbEntry(u, d) != null) + " dataLen=" + d.length);
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
                    timerHook("TG活动");
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
                                // 同样：只有模块自己发起的文本才乐观标记，避免用户手动发送被误判为已签
                                Map<String, Object> tx = findTextEntry(u, t);
                                if (tx != null && isPendingFresh(accountPrefix(), entryId(tx))) {
                                    markSignedFromRequest(u, t);
                                } else {
                                    logd("[活动] 非模块发起的文本，不乐观标记 " + u);
                                }
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
            } catch (Throwable _e29) { noteSwallowed("onUpdateProcessed", _e29); }
            try {
                Object fromId = getFieldVal(msg, "from_id");
                Object fu = fromId != null ? getFieldVal(fromId, "user_id") : null;
                if (fu != null) fromUid = ((Number) fu).longValue();
            } catch (Throwable _e30) { noteSwallowed("onUpdateProcessed", _e30); }
            // 私聊 bot：要求消息来自该 bot 本人
            // 群 / 频道：peer 是负数，回复来自群内任意机器人；只接收「非自己发的」消息
            boolean isGroupPeer = peerUid < 0;
            if (isGroupPeer) {
                long selfId = accountSelfId(currentAccount());
                if (selfId > 0 && fromUid == selfId) return;   // 忽略自己发的（群签到指令本身）
            } else {
                if (peerUid <= 0 || fromUid != peerUid) return;
            }
            // 1.4.5 Live Panel：bot 自己发的带键盘消息 → 缓存当前面板（无需该 bot 已是目标）
            try {
                Object rm = getFieldVal(msg, "reply_markup");
                Object mi = getFieldVal(msg, "id");
                if (rm != null && mi != null) {
                    int mId = mi instanceof Number ? ((Number) mi).intValue() : 0;
                    String body = "";
                    try {
                        Object bt = getFieldVal(msg, "message");
                        if (bt != null) body = String.valueOf(bt);
                    } catch (Throwable _e31) { noteSwallowed("onUpdateProcessed", _e31); }
                    if (mId > 0) updatePanelLive(peerUid, mId, rm, body);
                }
            } catch (Throwable _e32) { noteSwallowed("onUpdateProcessed", _e32); }
            if (!targetContains(peerUid)) return;
            Object mtext = getFieldVal(msg, "message");
            if (mtext == null) return;
            final String replyText = String.valueOf(mtext);
            final long did = peerUid;
            if (replyText.length() == 0) return;
            // 同一条回复只判一次（TG 多源重复投递，实测重复 2~4 次）
            Object midObj = getFieldValSafe(msg, "id");
            int midForDedup = midObj instanceof Number ? ((Number) midObj).intValue() : 0;
            if (verdictDup("reply:" + did + ":" + midForDedup + ":" + replyText.hashCode())) {
                logd("[回复判定] 重复投递，跳过（mid=" + midForDedup + "）");
                return;
            }
            mainHandler.post(() -> {
                try {
                    String prefix = accountPrefix();
                    String lower = replyText.toLowerCase();
                    // 「已签过/重复」必须先判且同样标记已签：
                    // 否则 last_ 不写 → 日历不绿 → 心跳每 90 秒再发一次（死循环）
                    // 判定逻辑已抽到 SignLogic（纯逻辑、有单测）：返回 {判定码, 命中词}
                    // 判定总开关：关掉就只记录回复、不判成败（用户自己确认目标）
                    if (!JUDGE_ENABLED) {
                        // 关掉判定是用户的显式选择，但**不能让它悄悄生效**：
                        // 否则用户会看到"bot 明明回签到成功、目标却没变绿"，无从下手。
                        logw("【回复判定】自动判定已关闭，本次回复只记录不判定: " + clip(replyText, 50)
                             + "（如需自动判成败，去 设置 → 判定机器人回复 → 打开「自动判定成功 / 失败」）");
                        noteJudgeOffOnce();
                        return;
                    }
                    // 宽松模式：**只要 bot 回了内容就算成功**，完全不看判定词。
                    // 专门对付措辞千奇百怪、内置词表永远对不上的机器人。
                    // 放在词表判定之前，命中即返回。
                    if (LOOSE_MODE) {
                        // 宽松 ≠ 盲目：**明确失败**仍然判失败（活动已结束 / 请先关注 /
                        // 未绑定账号 / failed 之类）。否则 bot 挂了也会显示绿色，用户被骗。
                        String[] looseExtraFail = JUDGE_USE_CUSTOM
                                ? SignLogic.parseExtraWords(prefs.getString("jmb_fail_words", "")) : null;
                        String[] looseFail = mergeWords(SignLogic.FAIL_WORDS_DEFAULT, looseExtraFail);
                        Object[] lvd = SignLogic.verdictDetail(replyText, new String[0], new String[0], looseFail);
                        if (((Integer) lvd[0]).intValue() == SignLogic.V_FAILED) {
                            String lhit = String.valueOf(lvd[1]);
                            for (Map<String, Object> m : targetsSnapshot()) {
                                if (entryDid(m) != did) continue;
                                String lid = entryId(m);
                                long lSent = prefs.getLong(prefix + "sent_at_" + lid, 0L);
                                if (lSent <= 0L || System.currentTimeMillis() - lSent > PENDING_TTL_MS) continue;
                                int lcur = prefs.getInt(kRetry(prefix, lid), 0);
                                prefs.edit().remove(kLast(prefix, lid))
                                     .putInt(kRetry(prefix, lid), lcur + 1)
                                     .putLong(kRetryAt(prefix, lid), System.currentTimeMillis() + backoffDelay(Math.max(lcur, 1)))
                                     .putString(kRetryDay(prefix, lid), todayStr()).commit();
                                logw("【回复判定】宽松模式：命中明确失败词「" + lhit + "」→ 判失败并退避重试 (id=" + lid + ")");
                                noteResult(false);
                                return;
                            }
                            return;
                        }
                        // 没有明确失败词 → 有回复即算成功
                        int lz = 0;
                        for (Map<String, Object> m : targetsSnapshot()) {
                            if (entryDid(m) != did) continue;
                            String lid = entryId(m);
                            prefs.edit().putInt(kRetry(prefix, lid), 0)
                                 .remove(kRetryAt(prefix, lid)).remove(kRetryDay(prefix, lid)).apply();
                            markSigned(prefix, lid);
                            lz++;
                        }
                        jlog("【回复判定】宽松模式：bot 有回复即算成功 → 计入已签（" + lz + " 条）: " + clip(replyText, 50));
                        noteResult(true);
                        return;
                    }
                    // 自定义词：只有用户开了「使用我的自定义词」才叠加，否则纯用内置
                    String[] extraOk = JUDGE_USE_CUSTOM ? SignLogic.parseExtraWords(prefs.getString("jmb_ok_words", "")) : null;
                    String[] extraFail = JUDGE_USE_CUSTOM ? SignLogic.parseExtraWords(prefs.getString("jmb_fail_words", "")) : null;
                    String[] okMerged = mergeWords(SignLogic.OK_WORDS_DEFAULT, extraOk);
                    String[] failMerged = mergeWords(SignLogic.FAIL_WORDS_DEFAULT, extraFail);
                    Object[] vd = SignLogic.verdictDetail(replyText, null, okMerged, failMerged);
                    int verdict = ((Integer) vd[0]).intValue();
                    String hitWord = String.valueOf(vd[1]);

                    if (verdict == SignLogic.V_SIGNED) {
                        // 成功 or bot 说"已签过" —— 两者都表示今天确实签过了。
                        // 「已签过」必须能落盘，否则 last_ 不写 → 日历不绿 → 心跳每 90 秒再发（死循环）。
                        //
                        // 恢复 1.5.7 行为：命中成功词就把该 bot 的目标都标上。
                        // （v1.5.8 中途试过"只标刚发过请求的那条"，但一旦 sent_at 缺失/超时
                        //  就会"bot 说成功却不计已签"，看着像失败 —— 比偶发误标更让人迷惑。
                        //  用户自己知道哪个是签到 bot，成功就算成功。）
                        int marked = 0;
                        for (Map<String, Object> m : targetsSnapshot()) {
                            if (entryDid(m) != did) continue;
                            String id = entryId(m);
                            prefs.edit().putInt(kRetry(prefix, id), 0)
                                 .remove(kRetryAt(prefix, id)).remove(kRetryDay(prefix, id)).apply();
                            markSigned(prefix, id);
                            marked++;
                        }
                        jlog("【回复判定】" + did + " 命中「" + hitWord + "」→ 计入已签（" + marked + " 条）"
                             + (extraHit(hitWord, extraOk) ? "（用户自定义词）" : ""));
                        noteResult(true);
                        return;
                    }

                    if (verdict == SignLogic.V_FAILED) {
                        // 撤销该 bot 最近 30 分钟内发过签到请求的条目（多指令时只动刚发的那条）
                        for (Map<String, Object> m : targetsSnapshot()) {
                            if (entryDid(m) != did) continue;
                            String id = entryId(m);
                            long sentAt = prefs.getLong(prefix + "sent_at_" + id, 0);
                            if (System.currentTimeMillis() - sentAt > PENDING_TTL_MS) continue;   // 与 isPendingFresh 同一常量
                            int cur = prefs.getInt(kRetry(prefix, id), 0);
                            prefs.edit()
                                .remove(kLast(prefix, id))
                                .putInt(kRetry(prefix, id), cur + 1)
                                .putLong(kRetryAt(prefix, id), System.currentTimeMillis() + backoffDelay(Math.max(cur, 1)))
                                .putString(kRetryDay(prefix, id), todayStr())
                                .commit();
                            logw("【回复判定】" + did + " 命中「" + hitWord + "」→ 判定未成功，撤销已签并安排重试 (id=" + id + ")");
                            noteResult(false);
                            return;
                        }
                        return;
                    }

                    // V_UNKNOWN：回复里没有签到结果。
                    //
                    // 分两种情况，绝不能一律当成错误：
                    //   ① 点的是菜单/充值/查询类按钮 → bot 回业务内容，**本来就不该有签到结论**，
                    //      这是正常情况，只记调试日志（默认不显示），不打扰用户。
                    //   ② 回复**看起来像签到结果**（含签到/打卡/领取等字样）但词表没覆盖 →
                    //      这才值得提示用户「可以去加词」，并留进诊断包。
                    String lr = replyText.toLowerCase();
                    boolean looksLikeResult = lr.contains("签到") || lr.contains("打卡") || lr.contains("领取")
                            || lr.contains("签") || lr.contains("check") || lr.contains("sign")
                            || lr.contains("claim") || lr.contains("daily");
                    if (looksLikeResult) {
                        logw("【回复判定】" + did + " 这条回复像是签到结果，但没匹配上内置词: "
                             + clip(replyText, 60) + "（可在 设置 → 回复判定词 里补一条）");
                        noteUnknownReply(prefix, did, replyText);
                    } else {
                        // 措辞要准确：bot 一次交互常发多条消息（先菜单/广告、后结果），
                        // 这条只是"不是签到结果"，**不是最终结论** —— 后续回复仍可能命中。
                        // 以前写"没识别到签到响应，已忽略"，用户看到以为失败了，其实后面会翻盘。
                        logd("【回复判定】" + did + " 本条不是签到结果（继续等后续回复）: " + clip(replyText, 50));
                    }
                } catch (Throwable _e33) { noteSwallowed("onUpdateProcessed", _e33); }
            });
            } // end for (update)
        } catch (Throwable t) {
            jlog("[回复判定] 异常: " + t);
        }
    }

    // ==================== 回复判定辅助 ====================

    /** 合并默认词表 + 用户附加词（去重，保持顺序）。 */
    private static String[] mergeWords(String[] base, String[] extra) {
        if (extra == null || extra.length == 0) return base;
        java.util.List<String> out = new ArrayList<String>();
        for (String w : base) if (w != null && w.trim().length() > 0) out.add(w.trim());
        for (String w : extra) {
            if (w == null) continue;
            String t = w.trim();
            if (t.length() == 0) continue;
            boolean dup = false;
            for (String e : out) { if (e.equalsIgnoreCase(t)) { dup = true; break; } }
            if (!dup) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    /** 命中词是否来自用户附加表（用于日志区分「默认词」和「用户词」）。 */
    private static boolean extraHit(String hitWord, String[] extra) {
        if (hitWord == null || extra == null) return false;
        for (String w : extra) {
            if (w != null && w.trim().equalsIgnoreCase(hitWord.trim())) return true;
        }
        return false;
    }

    /** 日志截断，避免把整段长回复写进日志。 */
    private static String clip(String t, int max) {
        if (t == null) return "";
        String v = t.replace("\n", " ").trim();
        return v.length() <= max ? v : v.substring(0, max) + "\u2026";
    }

    /**
     * 记一次「认不出的 bot 回复」。同一账号同一天只记一次，避免刷屏。
     * 用**单个固定键**存「日期|原文」—— 若把日期放进键名，每天会新增一个键永不清除，
     * 老用户跑几个月就积一堆垃圾键。这里保证每个账号永远只有 1 个键。
     */
    private void noteUnknownReply(String prefix, long did, String reply) {
        try {
            String key = prefix + "unknown_reply";
            String prev = prefs.getString(key, "");
            String today = todayStr();
            if (prev != null && prev.startsWith(today + "|")) return;   // 今天已记过
            prefs.edit().putString(key, today + "|" + clip(reply, 120)).apply();
            logw("[回复判定] 该 bot 的回复认不出来，已记录一条（诊断包可见）；"
                 + "若确认它代表签到成功/失败，可在「设置 → 回复判定词」里加词");
        } catch (Throwable ignored) {}
    }

    /** 判定被关闭时只提示一次（避免每条回复都刷）。 */
    private boolean judgeOffNotified = false;
    private void noteJudgeOffOnce() {
        try {
            if (judgeOffNotified) return;
            judgeOffNotified = true;
            mainHandler.post(new Runnable(){ @Override public void run(){
                try { toast(Lang.tr("自动判定已关闭：机器人回复不会被判成败（设置 → 判定机器人回复）")); }
                catch (Throwable _eJ) { noteSwallowed("noteJudgeOffOnce", _eJ); }
            } });
        } catch (Throwable _eJ2) { noteSwallowed("noteJudgeOffOnce", _eJ2); }
    }

    /** 今日是否有认不出的回复（诊断包用）；非今日返回空。 */
    private String todayUnknownReply() {
        try {
            String v = prefs.getString(accountPrefix() + "unknown_reply", "");
            if (v == null || v.length() == 0) return "";
            int bar = v.indexOf('|');
            if (bar <= 0) return "";
            return todayStr().equals(v.substring(0, bar)) ? v.substring(bar + 1) : "";
        } catch (Throwable t) { return ""; }
    }

    // ==================== 跨客户端同步 ====================
    // 每个宿主（官方版/Nagram/ExteraLess）各有独立 prefs，签到状态与配置互不可见。
    // 方案：各客户端把状态/配置写到自己外部目录的 sync/shared.json，
    //       由 root 侧守护脚本合并成 merged.json 写回，客户端再读回来合并。文本。
    private long lastMergeTs = 0L;
    /** 回复判定去重：key = "kind:did:标识"，value = 处理时间。
     *  TG 会把同一条 update 通过多个数据源投递，实测同一次点击重复 2~4 次。 */
    private final java.util.Map<String, Long> verdictSeen = new java.util.HashMap<String, Long>();
    private static final long VERDICT_DEDUP_MS = 20L * 1000;   // 20 秒内同一条只判一次

    /** true = 这条判定在去重窗口内已处理过，应当跳过。 */
    private boolean verdictDup(String key) {
        try {
            long now = System.currentTimeMillis();
            synchronized (verdictSeen) {
                if (verdictSeen.size() > 400) {
                    java.util.Iterator<java.util.Map.Entry<String, Long>> it = verdictSeen.entrySet().iterator();
                    while (it.hasNext()) {
                        if (now - it.next().getValue() > VERDICT_DEDUP_MS) it.remove();
                    }
                }
                Long prev = verdictSeen.get(key);
                if (prev != null && now - prev < VERDICT_DEDUP_MS) return true;
                verdictSeen.put(key, now);
                return false;
            }
        } catch (Throwable _eD) { noteSwallowed("verdictDup", _eD); return false; }
    }
    /** 跨端同步节流：心跳每 45 秒跑一次，但文件同步不需要这么频繁。
     *  跨设备（靠 root 脚本合并）本身就是慢通道，5 分钟一次足够，
     *  否则一小时白写 80 次盘，白耗电。 */
    private volatile long lastSyncAt = 0L;
    private static final long SYNC_MIN_INTERVAL = 5L * 60 * 1000;

    private java.io.File syncDir() {
        try {
            java.io.File d = appContext.getExternalFilesDir("tgautosign");
            if (d == null) return null;
            java.io.File s = new java.io.File(d, "sync");
            if (!s.exists()) s.mkdirs();
            return s;
        } catch (Throwable t) { return null; }
    }

    private static void writeTextFile(java.io.File f, String content) throws Exception {
        java.io.FileOutputStream os = new java.io.FileOutputStream(f);
        try { os.write(content.getBytes("UTF-8")); } finally { try { os.close(); } catch (Throwable ignored) {} }
    }

    private static String readTextFile(java.io.File f) throws Exception {
        java.io.FileInputStream is = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int n = is.read(buf);
            return new String(buf, 0, Math.max(0, n), "UTF-8");
        } finally { try { is.close(); } catch (Throwable ignored) {} }
    }

    /** 把本客户端的签到状态 + 配置写出去（供 root 侧合并）。 */
    private void syncPush() {
        try {
            java.io.File dir = syncDir();
            if (dir == null) return;
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("ts", System.currentTimeMillis());
            try { root.put("pkg", safePkg()); } catch (Throwable _e34) { noteSwallowed("syncPush", _e34); }
            org.json.JSONObject accs = new org.json.JSONObject();
            int accN = Math.max(1, activatedAccounts());
            for (int i = 0; i < accN; i++) {
                String prefix = accountPrefix(i);
                org.json.JSONObject a = new org.json.JSONObject();
                org.json.JSONObject last = new org.json.JSONObject();
                List<Map<String, Object>> l = new ArrayList<>();
                try { loadTargetsInto(prefix, l); } catch (Throwable _e35) { noteSwallowed("syncPush", _e35); }
                for (Map<String, Object> m : l) {
                    String d = prefs.getString(kLast(prefix, entryId(m)), "");
                    if (d != null && d.length() > 0) {
                        // 双键：既有原始 id，也有 did（账号索引不一致时仍能配对）
                        last.put(entryId(m), d);
                        last.put(String.valueOf(entryDid(m)), d);
                    }
                }
                a.put("last", last);
                a.put("sign_days", prefs.getString(kSignDays(prefix), ""));
                a.put("streak", prefs.getInt(kStreak(prefix), 0));
                a.put("last_sign_date", prefs.getString(kLastSignDate(prefix), ""));
                // 身份锚点：账号索引在不同手机上可能不同（A机 [甲,乙]、B机 [乙,甲]），
                // 只按索引同步会把甲的签到记录写到乙头上。附上该账号自身的 user id，
                // 合并侧优先按 selfId 配对，索引只作兜底。
                try {
                    long sid = accountSelfId(i);
                    if (sid > 0) a.put("self_id", sid);
                } catch (Throwable _eS) { noteSwallowed("syncPush(selfId)", _eS); }
                accs.put(String.valueOf(i), a);
            }
            root.put("accounts", accs);
            org.json.JSONObject cfg = new org.json.JSONObject();
            cfg.put("window", WINDOW == null ? "" : WINDOW);
            cfg.put("timer", TIMER_ENABLED);
            cfg.put("gap", GAP_MIN);
            cfg.put("missback", MISS_BACK);
            cfg.put("missdead", MISS_DEADLINE);
            cfg.put("theme", THEME_MODE);
            cfg.put("keywords", LEARN_KEYWORDS == null ? "" : LEARN_KEYWORDS);
            cfg.put("exclude", LEARN_EXCLUDE == null ? "" : LEARN_EXCLUDE);
            cfg.put("blocked_dids", blockedDidsToStr());
            cfg.put("retry", RETRY_LIMIT);
            cfg.put("wake", WAKE_CMD == null ? "" : WAKE_CMD);
            cfg.put("config_ts", prefs.getLong("jmb_config_ts", 0L));
            root.put("config", cfg);
            writeTextFile(new java.io.File(dir, "shared.json"), root.toString());
        } catch (Throwable t) { logException("[同步] push", t); }
    }

    /** sign_days 并集合并 */
    private boolean mergeSignDays(String prefix, String incoming) {
        try {
            if (incoming == null || incoming.isEmpty()) return false;
            java.util.Set<String> s = signDays(prefix);
            boolean changed = false;
            for (String x : incoming.split(",")) {
                String t = x.trim();
                if (t.length() > 0 && s.add(t)) changed = true;
            }
            if (!changed) return false;
            StringBuilder sb = new StringBuilder();
            int cap = 0;
            for (String x : s) {
                if (cap++ > 180) break;
                if (sb.length() > 0) sb.append(',');
                sb.append(x);
            }
            prefs.edit().putString(kSignDays(prefix), sb.toString()).commit();
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /**
     * 同步一次：先拉别家的，再把自己的写出去。
     * 涉及文件 IO，统一丢到 IO 线程执行（心跳跑在主线程，直接写会卡 UI）。
     * 注意：syncPull 会改字段与 prefs，必须回主线程应用。
     */
    private void syncNow() { syncNow(false); }

    /**
     * @param force true = 无视节流（用户手动改设置后调用，要立刻推出去）
     */
    private void syncNow(final boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSyncAt < SYNC_MIN_INTERVAL) return;
        lastSyncAt = now;
        LOG_IO.execute(new Runnable() { @Override public void run() {
            try {
                final String js = readMergedJson();
                if (js != null && !js.isEmpty()) {
                    mainHandler.post(new Runnable() { @Override public void run() { applyMerged(js); } });
                }
                mainHandler.post(new Runnable() { @Override public void run() { syncPush(); } });
            } catch (Throwable _e36) { noteSwallowed("syncNow", _e36); }
        } });
    }

    /** 读取合并结果（IO 线程用）。 */
    private String readMergedJson() {
        try {
            java.io.File dir = syncDir();
            if (dir == null) return null;
            java.io.File f = new java.io.File(dir, "merged.json");
            if (!f.exists()) return null;
            return readTextFile(f);
        } catch (Throwable t) { return null; }
    }

    /** 应用合并结果（主线程用）。 */
    private void applyMerged(String js) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(js);
            long ver = root.optLong("merge_ts", 0L);
            if (ver <= lastMergeTs) return;
            lastMergeTs = ver;
            applyConfigFrom(root.optJSONObject("config"));
            applyStatesFrom(root.optJSONObject("accounts"));
        } catch (Throwable t) { logException("[同步] apply", t); }
    }

    private void applyConfigFrom(org.json.JSONObject cfg) {
        if (cfg == null) return;
        try {
            long cts = cfg.optLong("config_ts", 0L);
            long localCts = prefs.getLong("jmb_config_ts", 0L);
            if (cts <= localCts) return;
            WINDOW = cfg.optString("window", WINDOW);
            THEME_MODE = cfg.optInt("theme", THEME_MODE);
            TIMER_ENABLED = cfg.optBoolean("timer", TIMER_ENABLED);
            GAP_MIN = cfg.optInt("gap", GAP_MIN);
            MISS_BACK = cfg.optBoolean("missback", MISS_BACK);
            MISS_DEADLINE = cfg.optInt("missdead", MISS_DEADLINE);
            LEARN_KEYWORDS = cfg.optString("keywords", LEARN_KEYWORDS);
            LEARN_EXCLUDE = cfg.optString("exclude", LEARN_EXCLUDE);
            LEARN_BLOCKED_DIDS.clear();
            try {
                String bd = cfg.optString("blocked_dids", "");
                if (bd != null && bd.trim().length() > 0) {
                    for (String one : bd.split(",")) {
                        String t = one.trim();
                        if (t.length() == 0) continue;
                        try { LEARN_BLOCKED_DIDS.add(Long.parseLong(t)); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            RETRY_LIMIT = cfg.optInt("retry", RETRY_LIMIT);
            WAKE_CMD = cfg.optString("wake", WAKE_CMD);
            Theme.mode = THEME_MODE;
            prefs.edit()
                .putString(kWindow(), WINDOW)
                .putInt("jmb_theme", THEME_MODE)
                .putBoolean(kTimerEnabled(), TIMER_ENABLED)
                .putInt(kGap(), GAP_MIN)
                .putBoolean(kMissBack(), MISS_BACK)
                .putInt("jmb_missdead", MISS_DEADLINE)
                .putString(kKeywords(), LEARN_KEYWORDS)
                .putString(kExclude(), LEARN_EXCLUDE == null ? "" : LEARN_EXCLUDE)
                .putString(kBlockedDids(), blockedDidsToStr())
                .putInt(kRetryLimit(), RETRY_LIMIT)
                .putString(kWakeCmd(), WAKE_CMD)
                .putLong("jmb_config_ts", cts)
                .apply();
            jlog("[同步] 已应用其他客户端的配置（ts=" + cts + "）");
            // 配置来自别的客户端（窗口/间隔/补签可能都变了），本机必须重排：
            // 旧写法只改字段不重排 → 当日时刻表仍按旧窗口，定时签到跑到窗口外。
            try {
                prefs.edit().remove(kTimerPlan(accountPrefix(), todayStr())).apply();
                scheduleWindowWake();
                if (TIMER_ENABLED && (inWindow() || inMissBackTime())) kickSchedule();
            } catch (Throwable _eA) { noteSwallowed("applyConfigFrom(重排)", _eA); }
        } catch (Throwable t) { logException("[同步] 配置应用", t); }
    }

    private void applyStatesFrom(org.json.JSONObject accs) {
        if (accs == null) return;
        try {
            int merged = 0;
            java.util.Iterator<String> it = accs.keys();
            while (it.hasNext()) {
                String ai = it.next();
                org.json.JSONObject a = accs.optJSONObject(ai);
                if (a == null) continue;
                int acc = 0;
                try { acc = Integer.parseInt(ai); } catch (Throwable ignored) {}
                // 优先用 self_id 反查本地账号：索引在多机之间不一定一致，
                // 只按索引配对会把别的账号的签到记录写到自己头上。
                long remoteSelf = 0L;
                try { remoteSelf = a.optLong("self_id", 0L); } catch (Throwable ignored) {}
                if (remoteSelf > 0L) {
                    int mapped = -1;
                    int nAcc = Math.max(1, activatedAccounts());
                    for (int k = 0; k < nAcc; k++) {
                        long sid = accountSelfId(k);
                        if (sid > 0L && sid == remoteSelf) { mapped = k; break; }
                    }
                    if (mapped >= 0) {
                        if (mapped != acc) {
                            jlog("[同步] 账号索引不一致：远端索引 " + acc + " 按 self_id 校对为本地 " + mapped);
                            acc = mapped;
                        }
                    } else {
                        // 远端这个账号本机没登录 —— 跳过，绝不能写到别的账号头上
                        logd("[同步] 跳过远端账号 self_id=" + remoteSelf + "（本机未登录该账号）");
                        continue;
                    }
                }
                String prefix = accountPrefix(acc);
                org.json.JSONObject last = a.optJSONObject("last");
                if (last != null) {
                    List<Map<String, Object>> mine = new ArrayList<>();
                    try { loadTargetsInto(prefix, mine); } catch (Throwable ignored) {}
                    android.content.SharedPreferences.Editor ed = prefs.edit();
                    boolean dirty = false;
                    for (Map<String, Object> mm : mine) {
                        String myId = entryId(mm);
                        String myDid = String.valueOf(entryDid(mm));
                        String d = last.optString(myId, "");
                        if (d.isEmpty()) d = last.optString(myDid, "");
                        if (d.isEmpty()) continue;
                        String cur = prefs.getString(kLast(prefix, myId), "");
                        if (cur == null || d.compareTo(cur) > 0) {
                            ed.putString(kLast(prefix, myId), d);
                            dirty = true; merged++;
                        }
                    }
                    if (dirty) ed.apply();
                }
                if (mergeSignDays(prefix, a.optString("sign_days", ""))) merged++;
                int st = a.optInt("streak", 0);
                if (st > prefs.getInt(kStreak(prefix), 0)) {
                    prefs.edit().putInt(kStreak(prefix), st)
                         .putString(kLastSignDate(prefix), a.optString("last_sign_date", prefs.getString(kLastSignDate(prefix), "")))
                         .apply();
                    merged++;
                }
            }
            if (merged > 0) jlog("[同步] 已合并其他客户端的签到状态（" + merged + " 项）");
        } catch (Throwable t) { logException("[同步] 状态应用", t); }
    }

    // ==================== 反射工具 ====================
    private Class<?> classEx(String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    /**
     * 构造一个 TLRPC 请求对象，兼容各种宿主内核：
     * 1) public 无参构造；2) 私有/包私有无参构造；3) 参数最少的构造 + 默认值填充。
     * Nagram 12.8.1（较老内核，R8 优化更激进）里 TL_messages_getBotCallbackAnswer 零参构造
     * 被移除，cls.newInstance() 会抛 InstantiationException: no zero argument constructor。
     */
    private static Object newTlObject(Class<?> cls) throws Exception {
        try {
            java.lang.reflect.Constructor<?> c = cls.getConstructor();
            return c.newInstance();
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignored) {}
        java.lang.reflect.Constructor<?> best = null;
        for (java.lang.reflect.Constructor<?> c : cls.getDeclaredConstructors()) {
            if (best == null || c.getParameterCount() < best.getParameterCount()) best = c;
        }
        if (best != null) {
            best.setAccessible(true);
            Class<?>[] pts = best.getParameterTypes();
            Object[] args = new Object[pts.length];
            for (int i = 0; i < pts.length; i++) {
                Class<?> p = pts[i];
                if (p == int.class) args[i] = 0;
                else if (p == long.class) args[i] = 0L;
                else if (p == boolean.class) args[i] = false;
                else if (p == byte.class) args[i] = (byte) 0;
                else if (p == short.class) args[i] = (short) 0;
                else if (p == char.class) args[i] = (char) 0;
                else if (p == float.class) args[i] = 0f;
                else if (p == double.class) args[i] = 0d;
                else args[i] = null;
            }
            Object o = best.newInstance(args);
            try { android.util.Log.i(TAG, "[反射] " + cls.getSimpleName() + " 无零参构造，已用 " + best.getParameterCount() + " 参构造兜底"); } catch (Throwable ignored) {}
            return o;
        }
        throw new NoSuchMethodException(cls.getName() + " 无可用构造函数");
    }

    private Object tgBuilder(Activity act) throws Exception {
        if (FORCE_CUSTOM_DIALOG) throw new IllegalStateException("FORCE_CUSTOM_DIALOG");
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

    /** best-effort 字段设置：字段不存在或类型不符时返回 false，不抛。用于跨内核版本的兼容写入。 */
    private static boolean trySetFieldVal(Object obj, String name, Object val) {
        try { setFieldVal(obj, name, val); return true; } catch (Throwable t) { return false; }
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
