package io.github.wlmosv_png.tgautosign;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
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

    /** 忽略导航按钮（主菜单/返回/关闭…）：默认开，用户可关。 */
    private boolean LEARN_SKIP_NAV = true;
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
    /**
     * 模块自身即将发出的回调请求指纹（"did|base64(data)" -> 时间戳）。
     *
     * 为什么需要（2026-09-28 修「目标自己冒出来」）：
     *   sendSign 点击的是**面板里的最新按钮**（resolveLiveButton），它的 data 可能
     *   与目标里存的旧 data 不同。请求发出后网络层 hook 捕获到它，
     *   findCbEntry(u, d) 按 data 精确匹配 → 查不到 → 被 learnCallback 学成**新目标**。
     *   于是「模块自己签一次」就多一条目标，且会自我繁殖。
     *   用户视角就是「我没发指令，它自己突然加了目标」。
     *
     * 修法：发出前登记指纹，hook 精确匹配后跳过学习（TTL 兜底清理）。
     */
    private final Map<String, Long> selfCbPending = new HashMap<String, Long>();
    private static final long SELF_REQ_TTL_MS = 60L * 1000L;
    // 调用环熔断用（见 sendSign 里的递归硬闸）
    private final Map<String, Long> signEnterAt = new HashMap<String, Long>();
    private final Map<String, Integer> signEnterCount = new HashMap<String, Integer>();
    private boolean receiverRegistered = false;
    /** 网络恢复广播 receiver。必须是字段：stop() 要反注册它（以前是方法局部变量，够不着）。 */
    private BroadcastReceiver netReceiver = null;
    private int lastAccount = -1;
    // v1.4.0：回调捕获/绑定/调试台 + 每条目前置命令 + 关键词解耦
    /** 是否自动判定机器人回复的成败（开=按内置/自定义词表判；关=只记录不判）。 */
    private boolean JUDGE_ENABLED = true;
    /**
     * 宽松模式（用户要求）。
     *   学习：**点什么学什么** —— 除「排除的 bot」「排除规则」外不做任何准入判断。
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
    // 注：不要另加 uiBtnFired() 包装 —— 实际调用点直接 uiBtnFire.incrementAndGet()，
    // 包装方法从来没被调用过（check-wiring 抓出来的孤儿）。

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
    private int logLimit = 200;          // 首屏显示条数（2026-10-03：400→200，每行要建 7 个 View）
    private int logPageStep = 300;       // 每次「加载更多」追加条数
    /** 易懂档折叠计数（2026-10-03）：同 foldKey 的条数，渲染时显示成「×N」。 */
    private final java.util.HashMap<String, Integer> logFoldCnt = new java.util.HashMap<String, Integer>();
    /** 易懂档顶部状态条容器（跨刷新保留引用）。 */
    private LinearLayout logHeadBar;
    private TextView logHeadIcon;
    private TextView logHeadTitle;
    private TextView logHeadSub;
    private TextView logHeadStat;
    private int logRendered = 0;         // 当前已渲染条数（游标，用于追加）
    // 日志上下文：账号 / 轮次 / 链路，统一由 jlog 自动带上，便于筛选与归因
    private volatile String ctxAcc = "";
    private volatile int ctxRound = 0;
    private volatile String ctxTrace = "";
    // 静默异常统计：catch(Throwable ignored) 里记一笔，诊断包可见，避免"错误被吞掉却毫无痕迹"
    private final java.util.concurrent.atomic.AtomicInteger swallowedCount = new java.util.concurrent.atomic.AtomicInteger(0);
    /**
     * 当日摘要「已投递」的进程内原子闸（2026-10-01）。
     * 元素形如 "yyyy-MM-dd|账号号"。仅用于同一进程内的心跳/轮次并发去重；
     * 跨重启仍以 prefs 的 jmb_notified_&lt;acc&gt; 为准（两者都要过）。
     */
    private final java.util.Set<String> SUMMARY_SENT_TODAY =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
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
    private int CAL_STYLE = 0;                   // 日历格样式：0=方角斜纹 1=圆角发光 2=硬方波 3=切角 4=圆点 5=柱条
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
    private Object pendingDialog = null;         // 「待处理」对话框（同上；处置后替换而非叠层）

    // ── 界面图导出（2026-09-30）：把页面 View 渲染成 PNG，不截屏 ──
    // 用途：README 截图随 UI 自动更新，不受屏幕尺寸/系统栏影响。
    // 实现：showDialog 在被要求导出时只记录页面 View、不弹窗，随后离屏渲染。
    // 每张图只渲染一次（shot 模式期间该页可能被触发两次，第一次记录后即结束）。
    private volatile boolean SHOT_MODE = false;      // 导出模式：拦截 showDialog
    private volatile boolean SHOT_DARK = true;       // 本次导出的深浅色
    private volatile boolean SHOT_DONE = false;      // 本轮是否已用掉
    private String SHOT_TITLE = null;                // 本次要导出的页面标题
    private View SHOT_VIEW = null;                   // 拦截到的页面 View
    private final java.util.concurrent.atomic.AtomicBoolean SHOT_BUSY =
            new java.util.concurrent.atomic.AtomicBoolean(false);   // 同时只允许一轮导出
    private volatile int SHOT_BG = 0;   // 主线程取的页面底色（渲染在子线程，必须提前取好）
    // ── 导出打码（README 用图不能带真实 bot 名）──
    // 目标名不在 targets map 里：targetTitle() 是实时向宿主查 botName()/chatTitle() 的，
    // 所以打码必须做在显示层（targetTitle/targetSubtitle），改 map 字段无效。
    private volatile boolean SHOT_MASK = false;
    // 导出期间的日志截断点：面板「最近动态」读的是实时日志缓冲，
    // 而导出本身会往里写 [导出]/[主题]/账号实况 等诊断行，
    // 不截断的话这些导出过程自己的日志会出现在宣传图里。
    private volatile int SHOT_LOG_CUTOFF = -1;
    // 文本脱敏：日志行里可能出现真实 bot 名（targetTitle 被多处 log 调用）。
    // 渲染日志时把真名替换成同一个占位名，保证图上不出现真实身份。
    private volatile boolean SHOT_RAW = false;   // true 时 targetTitle 返回真名（供建表用）
    private volatile java.util.Map<String,String> shotTable = null;   // 导出期脱敏表

    /** 建立「真名 -> 占位名」映射。 */
    private java.util.Map<String, String> shotScrubTable() {
        java.util.Map<String, String> t = new java.util.HashMap<String, String>();
        try {
            java.util.List<Map<String, Object>> snap = targetsSnapshot();
            for (Map<String, Object> m : snap) {
                long did = entryDid(m);
                String alias = shotAlias(did, false);
                SHOT_RAW = true;
                String real = null;
                try { real = targetTitle(did); } catch (Throwable ignored) {}
                String sub = null;
                try { sub = targetSubtitle(did); } catch (Throwable ignored) {}
                SHOT_RAW = false;
                if (real != null && real.length() > 1) t.put(real, alias);
                if (sub != null && sub.length() > 1) t.put(sub, shotAlias(did, true));
                String u = null;
                try { u = botUsername(did); } catch (Throwable ignored) {}
                if (u != null && u.length() > 1) t.put(u, "demo_bot");
            }
        } catch (Throwable e) {
            jlog("[导出] 建脱敏表失败: " + e);
        } finally {
            SHOT_RAW = false;
        }
        return t;
    }

    /** 把文本里出现的真名替换成占位名。 */
    private String shotScrub(String text, java.util.Map<String, String> table) {
        if (text == null || table == null || table.isEmpty()) return text;
        String out = text;
        for (java.util.Map.Entry<String, String> e : table.entrySet()) {
            String k = e.getKey();
            if (k != null && k.length() > 1 && out.contains(k)) out = out.replace(k, e.getValue());
        }
        return out;
    }
    private final java.util.Map<Long, Integer> shotNameMap = new java.util.HashMap<Long, Integer>();
    private int shotNameSeq = 0;

    /** 给一个目标分配稳定的占位编号；同一 did 在深浅两次导出里拿到同一个号。 */
    private String shotAlias(long did, boolean username) {
        Integer n = shotNameMap.get(did);
        if (n == null) { n = Integer.valueOf(++shotNameSeq); shotNameMap.put(did, n); }
        String num = n.intValue() < 10 ? "0" + n : String.valueOf(n);
        return username ? ("@demo_bot_" + num) : ("Demo Bot " + num);
    }
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

    /**
     * 导出图里不适合出现的日志行：模块启动行、导出自身、纯机制信息。
     * 只作用于 SHOT_MODE，普通用户的「最近动态」显示规则不变。
     */
    private static boolean isShotHiddenLog(String m) {
        if (m == null) return true;
        String s = m.trim();
        if (s.length() == 0) return true;
        if (s.startsWith("[导出]")) return true;
        if (s.contains("已加载")) return true;
        if (s.contains("注入:")) return true;
        if (s.startsWith("宿主:")) return true;
        if (s.startsWith("账号:")) return true;
        if (s.startsWith("使用:")) return true;
        if (s.contains("启动补签")) return true;
        if (s.contains("收到管理命令")) return true;
        if (s.startsWith("[主题]")) return true;
        if (s.contains("账号实况")) return true;
        if (isInternalLog(s)) return true;
        return false;
    }

    /** 终端日志卡内容：按最近 4 行重建（面板开着时每 4 秒自动刷新一次） */
    private void renderTermBody(final LinearLayout body, final Activity act2) {
        try {
            body.removeAllViews();
            java.util.List<LogLine> recent = new ArrayList<>();
            synchronized (logBuffer) {
                int end = logBuffer.size();
                if (SHOT_MODE && SHOT_LOG_CUTOFF >= 0 && SHOT_LOG_CUTOFF < end) end = SHOT_LOG_CUTOFF;
                if (SHOT_MODE) {
                    // 导出图：从尾部往前挑真正有内容的行（跳过启动/机制噪声），
                    // 这样即使最近几行是启动信息，图上也会显示靠前的签到动态。
                    for (int i = end - 1; i >= 0 && recent.size() < 4; i--) {
                        LogLine cand = logBuffer.get(i);
                        if (isShotHiddenLog(cand.msg)) continue;
                        recent.add(0, cand);
                    }
                } else if (end > 0) {
                    if (logPlain) {
                        // 易懂档：向前找"翻译得出来"的行（与日志页同一判据），
                        // 否则最后 4 行恰好都是内部条时首页会空着，
                        // 而日志页却有一堆内容（用户实测的"外面有里面没有"反向版）。
                        //
                        // 2026-10-03 补去重：只有 4 行位置，却可能被同一条文案占满
                        //（截图实测：两条一模一样的「不在签到时间，稍后自动执行」，
                        //  那是两个目标各报一次，但首页空间宝贵，重复等于浪费）。
                        // 按**翻译后的文案**去重，而不是原始文本 ——
                        // 用户看到的就是文案，文案相同即为重复。
                        java.util.LinkedHashSet<String> seenText = new java.util.LinkedHashSet<String>();
                        for (int i = end - 1; i >= 0 && recent.size() < 4; i--) {
                            LogLine cand = logBuffer.get(i);
                            if (isInternalLog(String.valueOf(cand.msg))) continue;
                            String[] pp = plainLogParts(String.valueOf(cand.msg));
                            if (pp == null) continue;
                            if (!seenText.add(pp[1])) continue;      // 同文案已有更近的一条
                            recent.add(0, cand);
                        }
                    } else {
                        int f = Math.max(0, end - 4);
                        for (int i = f; i < end; i++) recent.add(logBuffer.get(i));
                    }
                }
            }
            if (recent.isEmpty()) {
                TextView e1 = new TextView(act2); e1.setTextSize(Theme.TS_CAPTION); e1.setTypeface(Theme.text()); e1.setTextColor(Theme.termFaint(act2));
                e1.setText(Lang.tr("（暂无动态，签到后会在这里显示）")); body.addView(e1);
                return;
            }
            int shown = 0;
            for (LogLine l : recent) {
                // 易懂档：翻译成一句话；内部机制条跳过，不留空行
                String text;
                String icName = null;
                String sem = null;
                if (logPlain) {
                    String[] pp = plainLogParts(l.msg);
                    if (pp == null) continue;
                    icName = pp[0];
                    text = pp[1];
                    sem = pp.length > 2 ? pp[2] : "info";
                } else {
                    text = l.msg;
                }
                TextView lv2 = new TextView(act2); lv2.setTextSize(Theme.TS_CAPTION); lv2.setTypeface(android.graphics.Typeface.MONOSPACE);
                // 导出时对日志文本脱敏（日志里可能出现真实 bot 名）
                if (SHOT_MODE) text = shotScrub(text, shotTable);
                String lineText = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(l.ts)) + "  " + text;
                if (lineText.length() > 52) lineText = lineText.substring(0, 52) + "...";
                lv2.setText(lineText);
                int lcol = (sem != null)
                        ? plainColor(sem, act2)
                        : (l.lv == LV_ERR ? Theme.termPink(act2)
                           : l.lv == LV_OK ? Theme.termGreen(act2)
                           : l.lv == LV_WARN ? Theme.termAmber(act2)
                           : Theme.termMuted(act2));
                lv2.setTextColor(lcol);
                if (icName != null) {
                    android.graphics.drawable.Drawable ic = Icons.d(act2, icName, 11f, lcol);
                    if (ic != null) {
                        int isz = Theme.dp(act2, 11);
                        ic.setBounds(0, 0, isz, isz);
                        lv2.setCompoundDrawables(ic, null, null, null);
                        lv2.setCompoundDrawablePadding(Theme.dp(act2, 4));
                    }
                }
                body.addView(lv2);
                if (++shown >= 4) break;
            }
            if (shown == 0) {
                TextView e2 = new TextView(act2); e2.setTextSize(Theme.TS_CAPTION); e2.setTypeface(Theme.text());
                e2.setTextColor(Theme.termFaint(act2));
                e2.setText(Lang.tr("（最近没有需要你知道的事情）"));
                body.addView(e2);
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
        return badge(act, label, on, null);
    }

    /** 带图标的徽章：图标让标签含义一眼可辨（徽章文字很短，光看字容易不知所云）。 */
    private TextView badge(final Activity act, String label, boolean on, String icon) {
        TextView b = new TextView(act);
        b.setText((on ? "[ON]  " : "[OFF] ") + Lang.trShort(label));
        if (icon != null) {
            android.graphics.drawable.Drawable ic = Icons.d(act, icon, 11f,
                    on ? Theme.termGreen(act) : Theme.termMuted(act));
            if (ic != null) {
                int sz = dp(11);
                ic.setBounds(0, 0, sz, sz);
                b.setCompoundDrawables(ic, null, null, null);
                b.setCompoundDrawablePadding(dp(3));
            }
        }
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
        // 2026-09-30：取消常驻呼吸动画。
        // 原为 0.6↔1.0 无限闪烁，一个屏幕上同时有几个徽章在闪，
        // 既分散注意力又让人觉得"有什么东西出错了"。开关状态用颜色表达已足够。
        return b;
    }

    private String perAccountLine() {
        StringBuilder sb = new StringBuilder();
        try {
            // 真实槽位遍历（见 accountSlots 说明）：连续区间会漏掉非连续槽位
            int[] slots = accountSlots();
            String today = todayStr();
            // ── 跳过当前账号（2026-10-03）──
            // 主面板顶部已经写了「账号1 · 目标 12」+「已签 11/11」，
            // 这里再列一遍「账号1：目标 11 · 已签 11/11」就是同一屏第三遍。
            // 这一行的职责是"**其它**账号怎么样"，当前账号不需要再说。
            int cur = currentAccount();
            for (int i : slots) {
                if (i == cur) continue;
                List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
                loadTargetsInto(accountPrefix(i), l);
                String pfx2 = accountPrefix(i);
                int done = activeSignedCount(pfx2, l, today);
                int actv = activeTargetCount(pfx2, l);
                if (sb.length() > 0) sb.append('\n');
                // 分子分母同口径（两者都排除冻结/排除），否则会出现"11/12"这种永不满的分数
                sb.append(Lang.tf("{0}：目标 {1} · 已签 {2}/{3}", accountLabel(i), actv, done, actv));
                if (l.isEmpty()) sb.append(Lang.tr("（还没有目标，去该账号学一个）"));
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    /** 把当前账号的目标复制给其它账号（只复制目标，不带已签状态） */
    /** 账号概览数据：目标数 / 今日已签 / 是否有定时计划。 */
    /**
     * 账号概况：活跃目标数 / 今日已签 / 是否定时。
     *
     * 2026-10-03 修口径不一致：本方法原先用 l.size()（**含**冻结与排除的目标），
     * 而主面板用的是 activeTargetCount（**排除**它们）——
     * 于是同一个账号，主面板显示「已签 11/11」，日志页顶部却显示「已签 12/12」，
     * 用户截图直接问"是不是不同步"。
     *
     * 现在统一到同一口径：**分母只算参与签到的目标**。
     * 冻结/被排除的目标本来就不参与，算进分母只会让进度永远到不了满。
     */
    private int[] accountStats(int acc) {
        int total = 0, signed = 0, timer = 0;
        try {
            String prefix = accountPrefix(acc);
            List<Map<String, Object>> l = new ArrayList<>();
            loadTargetsInto(prefix, l);
            String today = todayStr();
            for (Map<String, Object> m : l) {
                try {
                    if (isInactive(prefix, m)) continue;      // 冻结/排除：不进分母
                    total++;
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
            // 真实槽位（用户反馈「账号3 显示没有目标」的根因：
            // 用 0..activatedAccounts()-1 遍历时，非连续槽位（如 7）压根访问不到，
            // 显示的是空分区 acc2_，真数据在 acc7_ 里）
            final int[] slots = accountSlots();
            final int count = Math.max(1, slots.length);
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);

            TextView head = new TextView(act);
            head.setTextSize(Theme.TS_CAPTION); head.setTextColor(Theme.termMuted(act));
            head.setTypeface(android.graphics.Typeface.MONOSPACE);
            head.setText(count <= 1 ? Lang.tr("当前只有 1 个登录账号") : Lang.tf("共 {0} 个登录账号（切换 Telegram 账号即切换目标集）", count));
            head.setPadding(dp(4), 0, dp(4), dp(8));
            box.addView(head);

            for (int i : slots) {
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
            all.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ signAllAccounts(); toast(Lang.tr("已对全部启用账号发起签到，结果见日志")); } });
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
        int[] slots = accountSlots();
        int n = slots.length;
        if (n <= 1) { toast("只有一个登录账号，不用复制"); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.dp(act, 16), Theme.dp(act, 8), Theme.dp(act, 16), Theme.dp(act, 8));
        TextView info = new TextView(act);
        info.setTextSize(Theme.TS_BODY);
        info.setTextColor(Theme.termTxt(act));
        info.setText(Lang.tf("把 {0} 的 {1} 个目标复制到其它账号。\n只复制目标本身，不带「今天已签」和重试记录。", accountLabel(cur), mine.size()));
        box.addView(info);
        for (int i : slots) {
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
        this.store = new PrefsStore(this.prefs);
        this.stateStore = new SignStateStore(this.store);
        this.stateStore.setHooks(new SignStateStore.Hooks() {
            @Override public void onSigned(String prefix, String id) {
                try { updateStreak(prefix); } catch (Throwable ignored) {}
                try { noteSignedDay(prefix); } catch (Throwable ignored) {}
            }
            @Override public void logWarn(String msg) { logw(msg); }
            @Override public void swallow(String where, Throwable t) { noteSwallowed(where, t); }
        });
        // 账号管理器：反射用宿主 loader（模块 loader 看不到宿主类）
        this.accountManager = new AccountManager(cl);
        this.accountManager.setWarner(new AccountManager.Warner() {
            @Override public void warnOutOfRange(int raw, int total, int used) {
                logw("[账号] selectedAccount=" + raw + " 越界（已登录 " + total + " 个）"
                     + (raw < 0 ? "，值非法，已退回 0"
                                : "，已按 " + (used == raw ? "原值使用（账号数不可信）" : "最后一个账号（索引 " + used + "）处理"))
                     + " —— 宿主切号时写入了异常值；若界面账号号对不上，请把这条日志发给作者");
            }
        });
        // 账号数回退：反射失败时扫 prefs 里实际存在的最大账号分区
        this.accountManager.setCountProvider(new AccountManager.CountProvider() {
            @Override public int activatedAccounts() { return scanAccountsFromPrefs(); }
        });
    }

    /** 扫 prefs 推断账号数（形如 acc{N}_ 的最大 N + 1）。反射失败时的回退。 */
    private int scanAccountsFromPrefs() {
        try {
            int maxAcc = -1;
            for (String k : prefs.getAll().keySet()) {
                if (!k.startsWith("acc")) continue;
                int us = k.indexOf('_');
                if (us <= 3) continue;
                String num = k.substring(3, us);
                boolean allDigit = num.length() > 0;
                for (int i = 0; i < num.length(); i++) {
                    char ch = num.charAt(i);
                    if (ch < '0' || ch > '9') { allDigit = false; break; }
                }
                if (!allDigit) continue;
                try { int v = Integer.parseInt(num); if (v > maxAcc) maxAcc = v; } catch (Throwable ignored) {}
            }
            return maxAcc >= 0 ? maxAcc + 1 : 1;
        } catch (Throwable t) { return 1; }
    }


    public void start() {
        migrateLegacyKeys();
        // 待确认池的账号化迁移：必须放在任何读取待确认池的代码之前。
        // 幂等（迁完即删旧键），失败只记日志、不阻断启动。
        try { migratePendingConfirmPool(); } catch (Throwable t) { noteSwallowed("start-pending-migrate", t); }
        if (started) { return; }
        synchronized (TLOCK) { loadTargetsLocked(); started = true; }
        try {
            if (prefs.contains("jmb_keywords")) LEARN_KEYWORDS = prefs.getString("jmb_keywords", DEF_KEYWORDS);
            if (prefs.contains(kExclude())) LEARN_EXCLUDE = prefs.getString(kExclude(), "");
            LEARN_SKIP_NAV = prefs.getBoolean("jmb_skip_nav", true);
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
            if (prefs.contains("jmb_cal_style")) CAL_STYLE = prefs.getInt("jmb_cal_style", 0);
            if (prefs.contains("jmb_lang")) { try { io.github.wlmosv_png.tgautosign.Lang.MODE = prefs.getInt("jmb_lang", 0); } catch (Throwable ignored) {} }
            if (prefs.contains("jmb_notify")) NOTIFY_ON = prefs.getBoolean("jmb_notify", true);
            if (prefs.contains("jmb_notify_fail_only")) NOTIFY_FAIL_ONLY = prefs.getBoolean("jmb_notify_fail_only", false);
            if (prefs.contains("jmb_notify_all_acc")) NOTIFY_ALL_ACCOUNTS = prefs.getBoolean("jmb_notify_all_acc", false);
            Theme.mode = THEME_MODE;
            MISS_DEADLINE = cfgInt("missdead", 23 * 60);
            if (prefs.contains("jmb_sort")) SORT_MODE = prefs.getString("jmb_sort", "unsigned");
            if (prefs.contains("jmb_fx")) TITLE_FX = prefs.getInt("jmb_fx", 0);
            // 就绪窗口：只给客户端一个**短**的初始化时间（8 秒），而不是干等 30 秒。
            // 30 秒太长 —— 冷启动后立刻点 bot 按钮的用户会以为模块坏了（实测反馈）。
            // 真正的"就绪"由 isClientUsable() 动态判定（拿到 MessagesController
            // 且账号可用即放行），bootReadyAt 退化为上限兜底。
            if (bootReadyAt == 0L) bootReadyAt = System.currentTimeMillis() + 8000L;
            DEBUG_OVERFLOW_SIM = prefs.getBoolean("jmb_dbg_overflow", false);
            try { accountManager.setOverflowSim(DEBUG_OVERFLOW_SIM); } catch (Throwable ignored) {}
            AUTO_LEARN = prefs.getBoolean("jmb_autolearn", AUTO_LEARN);
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
        try { sweepOrphanEntryKeys(); } catch (Throwable ignored) {}    // 清掉历史遗留的孤儿状态键
        try { sweepStalePendingConfirm(); } catch (Throwable ignored) {} // 清掉跨天残留的「待确认」
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
        // 目标数必须与账号同源：targetsSnapshot() 是按 lastAccount 装载的内存快照，
        // 启动时 loadTargetsLocked 还没跑，二者会不一致（实测打出"账号: 1 目标: 11"
        // 而账号1 其实不是当前账号）。改为按当前账号现场统计。
        int _bootAcc = currentAccount();
        int _bootCnt = acctTargetCount(_bootAcc);
        jlogForce("账号: " + accountLabel(_bootAcc) + "(acc" + _bootAcc + "_) 目标: " + _bootCnt
            + " 按钮学习: " + (AUTO_LEARN ? "开" : "关") + " 网络学习: " + (AUTO_LEARN_NET ? "开" : "关"));
        jlogForce("使用: 在任意聊天输入 /jmb 打开管理界面");
        logFlush();   // 强制落盘（jlogForce 已绕过采样）




        checkUpdateSilently();
            mainHandler.postDelayed(new Runnable() { @Override public void run() { bootToast(); } }, 15000L);
        mainHandler.postDelayed(new Runnable(){ public void run(){ try{ if(!prefs.getBoolean("jmb_tut_seen",false)){ prefs.edit().putBoolean("jmb_tut_seen",true).apply(); toast("输入 /jmb 打开管理面板 · /help 查看教程"); } }catch(Throwable ignored){} } }, 4000L);
    }


    /**
     * 停止本实例的后台工作（热重载 / 模块卸载时调用）。
     *
     * 为什么必须有：scheduleTickLoop() 与 schedulePoll() 都是**自递归 postDelayed**，
     * 没有任何取消机制；网络广播 receiver 也没反注册。LSPosed 热重载后旧实例仍在跑，
     * 与新实例并行 → 重复签到、重复 Toast、日志交错。
     */
    public void stop() {
        try {
            started = false;
            tickLoopOn = false;
            mainHandler.removeCallbacksAndMessages(null);
            synchronized (waitingPanel) { waitingPanel.clear(); waitingPanelDeadline.clear(); }
            pendingSigns.clear();
            try { if (netReceiver != null) appContext.unregisterReceiver(netReceiver); } catch (Throwable ignored) {}
            netReceiver = null;
            receiverRegistered = false;
            jlog("[生命周期] 实例已停止（热重载 / 卸载）");
        } catch (Throwable t) { noteSwallowed("stop", t); }
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

    /** 昨天日期 yyyy-MM-dd。日历摘要判定"连续是否延续"用（2026-10-01）。 */
    private String yesterdayStr() {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.add(java.util.Calendar.DATE, -1);
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
        } catch (Throwable t) { return ""; }
    }

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

    /**
     * 已激活账号数。
     *
     * 反射失败**不能**盲目回退 1 —— 那会让 currentAccount() 把任何索引都判成越界，
     * 再钳到 total-1 = 0，于是"切到账号3"被当成"账号1"，读错分区、
     * 表现为「账号3 获取不到签到目标」（用户实际反馈）。
     * 回退顺序：① 宿主 API；② prefs 里存在过的最大账号分区 + 1；③ 至少 1。
     */
    /**
     * 已激活账号数。Refactor 1.6.1：实现搬到 AccountManager
     * （反射失败时的 prefs 扫描回退也在那边，避免两处各写一套）。
     */
    private int activatedAccounts() {
        return accountManager.activatedAccounts();
    }

    // ────────────────────────────────────────────────────────────────
    // 账号槽位（slot）体系
    //
    // 背景：selectedAccount 读到的值**不等于**账号的「第几个」。
    // 实测部分客户端（如 Nagram XF 登录 4 个账号）会读到 7、9，那些是**合法索引**，
    // 数据就存在 acc9_ 里。以前把「读到值 + 1」当界面序号用，就会显示错账号。
    //
    // 现在把两个概念分开：
    //   真实索引（slot）  —— 存 acc{N}_ 分区用，来自 SharedConfig.activeAccounts
    //   界面序号（display）—— 给用户看的「账号1/2/3」，来自槽位在数组里的位置
    // ────────────────────────────────────────────────────────────────

    /**
     * 扫描真实账号槽位，升序返回。
     *
     * 优先级：① SharedConfig.activeAccounts（宿主的权威来源）
     *         ② UserConfig.isValidAccount(i) 逐个试（0 .. max(activatedAccounts,12)）
     *         ③ 0 .. max(1, activatedAccounts)-1 兜底
     */
    private int[] scanAccountSlots() {
        try {
            Class<?> sc = classEx("org.telegram.messenger.SharedConfig");
            Object v = getFieldVal(null, sc, "activeAccounts");
            if (v instanceof java.util.Set) {
                java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
                for (Object o : (java.util.Set<?>) v) {
                    if (o instanceof Number) {
                        int i = ((Number) o).intValue();
                        if (i >= 0) set.add(i);
                    }
                }
                if (!set.isEmpty()) {
                    int[] arr = new int[set.size()];
                    int k = 0;
                    for (Integer i : set) arr[k++] = i.intValue();
                    return arr;
                }
            }
        } catch (Throwable t) {
            noteSwallowed("scanAccountSlots-activeAccounts", t);
        }
        try {
            int n = Math.max(activatedAccounts(), 12);
            java.util.List<Integer> list = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                try {
                    Class<?> uc = classEx("org.telegram.messenger.UserConfig");
                    Object ok = staticInvoke(uc, "isValidAccount",
                            new Class<?>[]{int.class}, new Object[]{i});
                    if (Boolean.TRUE.equals(ok)) list.add(i);
                } catch (Throwable ignored) {}
            }
            if (!list.isEmpty()) {
                int[] arr = new int[list.size()];
                for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).intValue();
                return arr;
            }
        } catch (Throwable t) {
            noteSwallowed("scanAccountSlots-isValidAccount", t);
        }
        int n = Math.max(1, activatedAccounts());
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        return arr;
    }

    /** 账号槽位（带 2 秒缓存）。 */
    private int[] accountSlots() {
        long now = System.currentTimeMillis();
        int[] cached = accountSlotsCache;
        if (cached != null && now - accountSlotsAt < 2000L) return cached;
        int[] fresh = scanAccountSlots();
        accountSlotsCache = fresh;
        accountSlotsAt = now;
        return fresh;
    }

    /** 该真实索引是否是一个合法槽位。 */
    private boolean slotIsValid(int slot) {
        if (slot < 0) return false;
        for (int s : accountSlots()) {
            if (s == slot) return true;
        }
        return false;
    }

    /** 真实索引 -> 界面序号（1-based）。 */
    private int displayIndexOf(int slot) {
        int[] slots = accountSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) return i + 1;
        }
        return slot + 1;
    }

    /** 界面序号（1-based）-> 真实索引，越界回 -1。 */
    private int slotOfDisplayIndex(int displayIndex) {
        int[] slots = accountSlots();
        int i = displayIndex - 1;
        if (i >= 0 && i < slots.length) return slots[i];
        return -1;
    }

    /** 槽位列表的字符串形式，用于日志。 */
    private String slotsToString() {
        StringBuilder sb = new StringBuilder();
        for (int s : accountSlots()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /**
     * 账号显示名。注意入参是**真实索引**（槽位），不是界面序号。
     * 显示时换算成界面序号，所以 selectedAccount 读到 7/9 时显示的是「账号2/3」而不是「账号8/10」。
     */
    private String accountLabel(int acc) {
        return Lang.tf("账号{0}", displayIndexOf(acc));
    }

    /** 沿继承链向上查找字段（宿主的字段常声明在父类）。 */
    private static Object getFieldUp(Object obj, String name) {
        if (obj == null || name == null) return null;
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field fd = c.getDeclaredField(name);
                    fd.setAccessible(true);
                    return fd.get(obj);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                } catch (Throwable t) {
                    return null;
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    /** 越界模拟开关（排障用）：打开后 currentAccount() 强制返回越界值。 */
    private boolean DEBUG_OVERFLOW_SIM = false;

    /** 签到状态读写中心（重构 1.6.1 · 步骤 4）：last_/opt_/retry_/fail_streak_ 的唯一入口。 */
    private final SignStateStore stateStore;

    /** 持久化访问层（重构 1.6.1 · 步骤 2）：统一落盘策略，状态机键一律 commit。 */
    /** 持久化访问层（重构 1.6.1 · 步骤 2）：统一落盘策略，状态机键一律 commit。 */
    private final PrefsStore store;

    /** 账号上下文管理（重构 1.6.1）：解析、钳制、Ctx 捕获。 */
    private final AccountManager accountManager;

    /** currentAccount() 读到的原始值（未经钳制），仅用于诊断包展示。 */
    private volatile int lastRawAccount = -1;

    /**
     * 当前账号索引。
     *
     * 语义（别再搞错）：
     *   UserConfig.selectedAccount 是**索引**（从 0 开始）；
     *   UserConfig.getActivatedAccountsCount() 是**数量**。
     *   3 个账号的合法索引只有 0/1/2 —— 读到 3 必然是异常值，
     *   不存在"这是另一种编码所以合法"的情况。
     *
     * 历史错误链（两个版本各错一半，必须记住）：
     *   v1.5.8：`if (c < 0 || c >= total) return 0;` —— 正值越界也钳到 0。
     *     后果：用户登录 3 个账号切到第 3 个（索引 2），宿主偶发写入 3 被钳成 0
     *     → 界面显示「账号1」。这是用户实际反馈过的串号 bug（已复现）。
     *   v1.5.9：改成"正值越界原样返回"，依据是 commit 里写的
     *     「selectedAccount=7/9 是合法索引」—— **该判断是错的**。
     *     原样返回会让 accountPrefix() 拼出不存在的分区（acc3_），
     *     目标表/已签记录全空，用户看到"配置没了"。
     *
     * 现在（正确策略）：越界一律钳到**最后一个合法索引** total-1。
     *   理由：宿主写入越界值时，意图几乎总是"刚登录/刚切换的那个账号"；
     *   而新登录的账号索引最大。钳到 0 反而把用户丢回第 1 个账号。
     *   负值仍归 0（不能返回负数，否则 accountPrefix() 拼出 acc-1_ 垃圾分区）。
     */
    /**
     * 当前账号索引（已钳制）。
     *
     * Refactor 1.6.1：实现搬到 AccountManager（决策核心在 SignLogic.clampAccount，
     * 有单测覆盖多账号边界）。此处只做转接，保证既有 77 个调用点行为不变。
     * 后续会把调用点改为持有 AccountManager.Ctx，让编译器挡住"忘记传账号"。
     */
    private int currentAccount() {
        int a = accountManager.current();
        lastRawAccount = accountManager.lastRaw();
        return a;
    }

    /** 越界告警去重：同一组值只记一次，避免刷屏。 */
    private volatile String lastAccRangeSig = "";

    /** 账号槽位缓存（毫秒时间戳）。真实槽位读取要反射 SharedConfig，故做 2 秒缓存。 */
    private volatile long accountSlotsAt = 0L;
    /** 账号槽位缓存：[真实索引]，升序。见 accountSlots()。 */
    private volatile int[] accountSlotsCache = null;
    private void warnAccountOutOfRange(int raw, int total) {
        try {
            String sig = raw + "/" + total;
            if (sig.equals(lastAccRangeSig)) return;
            lastAccRangeSig = sig;
            logw("[账号] selectedAccount=" + raw + " 越界（已登录 " + total + " 个）"
                 + (raw < 0 ? "，值非法，已退回 0"
                            : "，已按最后一个账号（索引 " + (total - 1) + "）处理")
                 + " —— 宿主切号时写入了异常值；若界面账号号对不上，请把这条日志发给作者");
        } catch (Throwable ignored) {}
    }

    /** Toast 策略：同类提示 5 分钟内只弹一次 / 每天最多一次 / 一轮签到结果合并成一条 */
        private final Map<String, Long> toastAt = new HashMap<String, Long>();
        private int roundOkN = 0, roundErrN = 0;
        private boolean roundScheduled = false;
        // 每账号独立聚合结果；全账号签到时绝不能把账号 A 的结果 Toast 到账号 B。
        private final Map<Integer, int[]> roundResultsByAccount = new HashMap<Integer, int[]>();

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

        void noteResult(boolean good) { noteResult(currentAccount(), good); }

        void noteResult(final int account, boolean good) {
            boolean schedule;
            try { bumpDailyResult(account, good); bumpDaily(account); } catch (Throwable ignored) {}
            synchronized (toastAt) {
                int[] rr = roundResultsByAccount.get(account);
                if (rr == null) { rr = new int[]{0, 0}; roundResultsByAccount.put(account, rr); }
                if (good) rr[0]++; else rr[1]++;
                // 保留旧字段给少量历史 UI 代码，但实际结果以 per-account map 为准。
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
                    // 「今日已放弃」必须按天判（2026-10-03）：缺日期检查时，
                    // 昨天用满重试的目标今天会被当成"已了结"，摘要提前发出。
                    if (prefs.getInt(kRetry(prefix, id), 0) >= RETRY_LIMIT
                            && today.equals(prefs.getString(kRetryDay(prefix, id), ""))) continue;
                    // 发送次数已达上限：同样算"今天了结"（等用户处置，不再自动发）
                    if (isSendAttemptsExhausted(prefix, id)) continue;
                    if (isPendingConfirm(prefix, id)) continue;                              // 待确认：已有归类
                    if (isSnoozed(prefix, id)) continue;                                   // 已顺延
                    pending = true;
                    break;
                }
                if (!pending) return true;
                // 兜底：窗口/补签截止已经过去 1 小时以上还有目标没结论，就不再等，
                // 否则目标卡在"待签"时通知会永远发不出去。
                //
                // 跨天修复（2026-10-01）：此前用 windowRange()，而它对跨天窗口
                // （如 22:00-02:00）**返回 null**，endMin 静默退化成 MISS_DEADLINE，
                // 与真实窗口结束无关 —— 注释却写着"用跨天判定处理"，名实不符。
                //
                // 现在统一用 windowRangeAny() + windowSpanAny() 把窗口展开到"分钟轴"：
                //   非跨天 08:00-20:00          → [480, 1200]
                //   跨天   22:00-02:00          → [1320, 1560]（结束已 +1440）
                // 补签截止同理：跨天窗口下若截止钟点小于窗口开始（如截止 06:00、
                // 窗口 22:00 开始），说明它是"次日钟点"，也要 +1440。
                //
                // 判定：endMin > 1439（结束落在次日）时，今天必然还没过点 ——
                // 因为跨天窗口的"结束"发生在明天凌晨，而那时日期已翻篇、
                // 会按新一天重新起轮。真正的结算等今晚窗口结束，无需在凌晨硬发。
                java.util.Calendar c = java.util.Calendar.getInstance();
                int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
                int[] any = SignLogic.windowRangeAny(WINDOW);
                if (any == null) {
                    // 窗口为空/非法 = 不限。只在开了补签时才依赖截止时间，否则立刻放行。
                    if (!MISS_BACK) return true;
                    if (nowMin <= MISS_DEADLINE) return false;
                    return (nowMin - MISS_DEADLINE) >= 60;
                }
                int[] span = SignLogic.windowSpanAny(any);
                int endMin = span[1];
                if (MISS_BACK) {
                    int dl = MISS_DEADLINE;
                    // 跨天窗口且截止钟点早于窗口开始 → 它是次日钟点
                    if (SignLogic.crossesMidnight(any) && MISS_DEADLINE < span[0]) dl += 24 * 60;
                    endMin = Math.max(endMin, dl);
                }
                if (endMin >= 24 * 60) return false;      // 结束在次日：今天还没到结算点
                if (nowMin <= endMin) return false;       // 还没到点，继续等
                return (nowMin - endMin) >= 60;           // 过点 1 小时才放行
            } catch (Throwable t) { return false; }
        }

        private void flushRoundToast() {
            final Map<Integer, int[]> snapshot = new HashMap<Integer, int[]>();
            synchronized (toastAt) {
                for (Map.Entry<Integer, int[]> e : roundResultsByAccount.entrySet()) {
                    int[] v = e.getValue();
                    if (v != null && (v[0] != 0 || v[1] != 0)) snapshot.put(e.getKey(), new int[]{v[0], v[1]});
                }
                roundResultsByAccount.clear();
                roundOkN = 0; roundErrN = 0; roundScheduled = false;
            }
            if (snapshot.isEmpty()) return;
            for (Map.Entry<Integer, int[]> e : snapshot.entrySet()) {
                int acc = e.getKey();
                int ok = e.getValue()[0], er = e.getValue()[1];
                String tk = "round|" + todayStr() + "|" + acc;
                if (er == 0) toastOnce(tk, Lang.tf("{0}：签到完成 {1} 个", accountLabel(acc), ok));
                else toastOnce(tk, Lang.tf("{0}：签到完成 {1} 个，{2} 个没成功（/jmb → 📄 运行日志 里有原因）", accountLabel(acc), ok, er));
            }
            // 通知摘要也按账号独立处理；全账号签到时不能只检查 currentAccount()。
            try {
                if (NOTIFY_ON) {
                    for (final Integer accObj : snapshot.keySet()) {
                        // 走统一的幂等入口，与心跳里的检查共用同一套判定，
                        // 避免两处逻辑分叉（这正是 2026-10-01 摘要发不出的成因）
                        trySendDailySummary(accObj.intValue());
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
    private void noteFailStreak(int account, String prefix, String id, long did, String errText) {
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
                    accountLabel(account));
            logw("[告警] " + nm + " 连续失败 " + streak + " 天（" + errText + "）");
            if (NOTIFY_ON) sendSavedMessage(tip, account);
            else toastOnce("fail|" + id, Lang.tf("⚠️ {0} 连续 {1} 天签到失败", nm, streak));
        } catch (Throwable ignored) {}
    }

    private void clearFailStreak(String prefix, String id) {
        stateStore.clearFailStreak(prefix, id);
    }

    /** 组装并发送今日签到摘要。 */
    /**
     * 到点就发每日摘要（幂等）。
     *
     * 背景（2026-10-01 实测）：摘要原本只在「签到轮次结束」时检查，
     * 而检查点 if (allSettledToday) 不满足时只记一条日志、不重试 ——
     * 如果窗口结束后没有新的签到轮次，摘要永远不会发出。
     * 实测日志里「暂不发汇总」17 次、摘要 0 次，用户完全收不到日报。
     *
     * 本方法由心跳循环调用（每轮都查），补齐「窗口结束后」的触发点。
     * 幂等靠 jmb_notified_<acc> 键：当天发过就不再发。
     */
    private void trySendDailySummary(final int acc) {
        try {
            if (!NOTIFY_ON) return;
            final String nk = "jmb_notified_" + acc;
            final String today = todayStr();
            // 并发闸（2026-10-01 修）：心跳与 flushRoundToast 可能同时到达，
            // 靠 prefs 去重在“检查→落盘”之间有空窗，两边都会判定“今天没发过”。
            // 用进程内的 Set 做原子占位；prefs 仍是跨重启的最终凭据。
            if (!SUMMARY_SENT_TODAY.add(today + "|" + acc)) return;
            if (!allSettledToday(acc)) { SUMMARY_SENT_TODAY.remove(today + "|" + acc); return; }  // 窗口未结束/仍有在途，不占用
            if (today.equals(prefs.getString(nk, ""))) return;        // 今天已发（跨重启）
            // 必须 commit：该键是**状态机键**，紧随其后的调用（心跳/flushRoundToast）
            // 会读它做闸门。apply 的异步窗口会让闸门失效 → 同一账号收到两条摘要。
            // 这正是 PrefsStore 里写明的规则（状态机键一律 commit），此前漏用了。
            if (!prefs.edit().putString(nk, today).commit()) {        // 落盘失败则不标记，留待下次
                SUMMARY_SENT_TODAY.remove(today + "|" + acc);
                return;
            }
            mainHandler.post(new Runnable() { @Override public void run() {
                try { notifySummary(acc); } catch (Throwable ignored) {}
            } });
        } catch (Throwable t) { noteSwallowed("trySendDailySummary", t); }
    }

    private void notifySummary() { notifySummary(currentAccount()); }

    private void notifySummary(final int acc) {
        try {
            if (!NOTIFY_ON) return;
            String prefix = accountPrefix(acc);
            List<Map<String, Object>> list = new ArrayList<>();
            loadTargetsInto(prefix, list);
            if (list.isEmpty()) return;
            int total = list.size(), done = 0;
            // 按「要不要用户动手」分两组：
            //   needYou —— 待确认：只有人能决定，模块不会自愈
            //   auto    —— 失败/待签：模块会自己重试或补签
            List<String> needYou = new ArrayList<>();
            List<String> auto = new ArrayList<>();
            String today = todayStr();
            for (Map<String, Object> m : list) {
                String id = entryId(m);
                if (today.equals(prefs.getString(kLast(prefix, id), ""))) { done++; continue; }
                String nm = entryDisplayName(m);
                if (isPendingConfirm(prefix, id)) {
                    needYou.add(Lang.tf("{0} — {1}", nm, attentionLabel(prefix, id)));
                    continue;
                }
                int rt = prefs.getInt(kRetry(prefix, id), 0);
                if (rt > 0) auto.add(Lang.tf("{0}（失败 {1} 次）", nm, rt));
                else if (!isSnoozed(prefix, id)) auto.add(Lang.tf("{0}（待签）", nm));
            }
            int bad = needYou.size() + auto.size();
            if (NOTIFY_FAIL_ONLY && bad == 0) return;

            StringBuilder sb = new StringBuilder();
            sb.append(Lang.tf("TGAutoSign 每日摘要 · {0}", accountLabel(acc))).append("\n");
            if (bad == 0) {
                sb.append(Lang.tf("今日 {0} 个目标全部已签", done));
            } else {
                sb.append(Lang.tf("今日 {0}/{1} 已签", done, total));
            }

            // 需要你决定的放前面 —— 这组不做就永远没结果
            if (!needYou.isEmpty()) {
                sb.append("\n\n").append(Lang.tf("⚠ 需要你决定（{0}）", needYou.size()));
                int n = 0;
                for (String f : needYou) { if (n++ >= 6) { sb.append("\n· …"); break; } sb.append("\n· ").append(f); }
                sb.append("\n").append(Lang.tr("发送 /jmb 打开面板处理"));
            }
            // 自动重试中的放后面 —— 告知即可，不用动手
            if (!auto.isEmpty()) {
                sb.append("\n\n").append(Lang.tf("自动重试中（{0}）", auto.size()));
                int n = 0;
                for (String f : auto) { if (n++ >= 6) { sb.append("\n· …"); break; } sb.append("\n· ").append(f); }
            }
            sb.append("\n\n").append(nowHM());
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
                    // 落盘条件（步骤 5 显式化）：
                    //   lv >= LV_WARN           → 状态变更/错误，必定落盘（排障骨架）
                    //   队列达 20 条 / 距上次 5s → 顺带把低级别也刷下去
                    // 也就是说 LV_DEBUG/LV_INFO **可能永久丢失**，别把关键证据放这两级。
                    if (shouldPersist(lv, diskQueue.size(), now - diskFlushAt)) {
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

        /** 是否应该落盘（规则见调用点注释与 guessLevel 文档）。 */
        private static boolean shouldPersist(int lv, int queueSize, long sinceFlushMs) {
            return lv >= LV_WARN || queueSize >= 20 || sinceFlushMs > 5000L;
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
                            LogLine _pl = parseLogLine(t);
                            if (_pl == null) continue;   // 不是日志行（外部文本混入），跳过
                            older.add(_pl);
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
            if (flat == null || flat.length() < 19) return null;   // 太短，不可能是日志行
            try {
                java.util.Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(flat.substring(0, 19));
                if (d == null) return null;                        // 前 19 字符不是时间戳 → 不是日志行
                String rest = flat.substring(19).trim();
                int lv = LV_INFO;
                if (rest.startsWith("[调试]")) { lv = LV_DEBUG; rest = rest.substring(5).trim(); }
                else if (rest.startsWith("[成功]")) { lv = LV_OK; rest = rest.substring(5).trim(); }
                else if (rest.startsWith("[警告]")) { lv = LV_WARN; rest = rest.substring(5).trim(); }
                else if (rest.startsWith("[错误]")) { lv = LV_ERR; rest = rest.substring(5).trim(); }
                return new LogLine(d.getTime(), lv, rest);
            } catch (Throwable t) {
                // 解析失败 = 这行不是模块写的日志（可能是被误当日志读进来的外部文本）。
                // 以前这里返回 ts=0，导出后显示成 1970-01-01，误导排查。
                return null;
            }
        }


        /** 旧的 jlog(String) 调用点按关键词推断级别；重要路径已改成显式 logs/logw/loge */
        /**
         * 按文案猜级别（仅在调用 jlog(String) 时使用）。
         *
         * ⚠️ 分级规则（步骤 5 固化，新增日志照此归类）：
         *   唯一硬约束是"落盘条件"：{@code lv >= LV_WARN} 才**必定**写盘；
         *   LV_INFO/LV_DEBUG 会被采样丢弃（队列满 20 或 5 秒刷盘才顺带写）。
         *   而排障最需要的"状态变更"证据绝不能丢，所以它们必须是 LV_WARN。
         *
         *   宁可高报级别（多写几条盘）也不要漏证据 —— 日志文件不大，
         *   而漏掉"今天签上了"的代价是用户以为功能坏了。
         */
        private static int guessLevel(String m) {
            if (m == null) return LV_INFO;
            // ① 错误
            if (m.contains("异常") || m.contains("失败") || m.contains("错误") || m.contains("崩溃")
                    || m.contains("熔断")) return LV_ERR;
            // ② 状态变更 —— 必须落盘（这些是排障的骨架）
            if (m.contains("标记今日已签") || m.contains("标记已发出") || m.contains("标记为「待确认」")
                    || m.contains("待确认") || m.contains("已计入已签") || m.contains("计入已签")
                    || m.contains("已停止重试") || m.contains("停止重试")
                    || m.contains("重试") || m.contains("退避") || m.contains("限流")
                    || m.contains("跳过发送") || m.contains("跳过排期") || m.contains("跳过本次")
                    || m.contains("未找到") || m.contains("警告") || m.contains("没有可签")
                    || m.contains("账号跟随") || m.contains("越过")) return LV_WARN;
                    // 注：2026-09-30 起「账号实况」不再强制升为警告 ——
                    //   它已改为「正常走 logd、异常才 logw」，这里再强升会把调试条也染成黄色。
            // ③ 用户可感知的成功动作
            if (m.contains("成功") || m.contains("已添加") || m.contains("已保存") || m.contains("已绑定")
                    || m.contains("已删除") || m.contains("已导入") || m.contains("已导出")
                    || m.contains("已复制") || m.contains("已发送") || m.contains("已冻结") || m.contains("已解冻")
                    || m.contains("已恢复") || m.contains("已暂停") || m.contains("已排除")) return LV_OK;
            // ④ 内部细节（可丢）
            if (m.contains("[按钮]") || m.contains("dump:") || m.contains("[候选]") || m.contains("已登记")
                    || m.contains("[面板]") || m.contains("[去重]") || m.contains("节流")
                    || m.contains("[回复判定]") || m.contains("窗口外") || m.contains("[活动]")
                    || m.contains("[定时]") && m.contains("跳过")) return LV_DEBUG;
            return LV_INFO;
        }

    // ---------------- 目标条目模型（v1.3.0：一 bot 多指令 + 回调按钮） ----------------

    private static java.util.Set<String> cs() {
        return java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    }

    private long lastSyncMs = 0L;
    private void syncAccount() {
        long n0 = System.currentTimeMillis();
        try {
            int cur = currentAccount();
            // 账号变了 → 必须立即重载，**不受节流限制**。
            // 反例（用户实际反馈）：在账号1 操作过，2 秒内切到账号3 点签到按钮，
            // 旧的 2 秒节流让 syncAccount 直接 return，targets 仍是 acc0_ 的列表，
            // 于是 findCbEntry 找不到该 bot → 提示「获取不到签到目标」。
            // 账号切换是低频且必须准确的事件，节流对它只会帮倒忙。
            if (cur != lastAccount) {
                lastSyncMs = n0;
                synchronized (TLOCK) {
                    lastAccount = cur;
                    // 顺手刷新日志前缀：ctxAcc 原来只在签到轮次里赋值，
                    // 平时是陈旧缓存 —— 实测会出现"日志打着[账号3]、实际读到 acc1"
                    // 的误导（排障时被带偏）。这里跟真实账号保持一致。
                    try { ctxAcc = accountLabel(cur); } catch (Throwable ignored) {}
                    loadTargetsLocked();
                    jlog("账号跟随: acc" + cur + "（" + accountLabel(cur) + "）"
                         + "，当前目标 " + targets.size() + " 个"
                         + "，selectedAccount=" + lastRawAccount
                         + "，已登录=" + activatedAccounts());
                }
                return;
            }
            if (n0 - lastSyncMs < 2000L) return;   // 同一账号内的高频调用才节流
            lastSyncMs = n0;
        } catch (Throwable ignored) {}
    }

    private List<Map<String, Object>> targetsSnapshot() {
        synchronized (TLOCK) { return new ArrayList<Map<String, Object>>(targets); }
    }

    /** 判定「这条目标正在发送中」的有效窗口。
     *  必须与回复判定侧的撤销窗口对齐：那边特意放宽到 30 分钟等慢 bot 回复，
     *  这里若只有 90 秒，3 分钟后才回话的 bot 会因为 pending 已被清掉而走不到撤销逻辑，
     *  失败会被当成成功记下来。 */
    /** 发完前置命令后等"带按钮的新消息"的最长时间。新发消息型 bot 比原地刷新慢得多。 */
    private static final long PANEL_WAIT_MS = 25L * 1000L;
    /** 等面板总上限。超过就释放等待状态、交回退避重试（避免无限挂着重复发命令）。 */
    private static final long PANEL_TOTAL_MS = 3L * 60L * 1000L;
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

    /**
     * 该目标是否「正在发送中」或「已发出但还没等到结论」。
     *
     * 为什么必须两者一起查（2026-09-25 修「一个账号签到两次」）：
     *   · isPendingFresh      —— 查内存 Set pendingSigns，进程重启后为空
     *   · isSentPendingFresh  —— 只查 prefs 的 sent_at_ 时间戳，跨重启有效
     * 只查前者时，重启后立刻遇「网络恢复」广播 → 内存态已失忆 → 判定可发 → 重复入队。
     * 现场：09:13:01 发出 → 09:13:07 网络恢复 → 09:13:15 又发一条同样指令。
     *
     * 与 skipScheduling 语义一致（那边还多查一个「今天已签」）。
     * 新增判断点请一律走本方法，不要再各处手写单闸。
     */
    private boolean isInFlightOrPending(String prefix, String id) {
        try {
            if (isPendingFresh(prefix, id)) return true;
            return stateStore.isSentPendingFresh(prefix, id, PENDING_TTL_MS);
        } catch (Throwable t) { return false; }
    }

    // ── 模块自身回调请求指纹（2026-09-28 修「目标自己冒出来」）──

    private static String selfCbKey(long did, byte[] data) {
        if (data == null || data.length == 0) return null;
        return did + "|" + Base64.getEncoder().encodeToString(data);
    }

    /** 发请求前登记：告诉网络层 hook「这条是我发的」，别把它学成新目标。 */
    private void markSelfCbRequest(long did, byte[] data) {
        try {
            String k = selfCbKey(did, data);
            if (k == null) return;
            long now = System.currentTimeMillis();
            // 顺手清理过期项，避免无限增长
            java.util.Iterator<Map.Entry<String, Long>> it = selfCbPending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (now - (e.getValue() == null ? 0L : e.getValue().longValue()) > SELF_REQ_TTL_MS) it.remove();
            }
            selfCbPending.put(k, now);
        } catch (Throwable t) { noteSwallowed("markSelfCbRequest", t); }
    }

    /** 该回调请求是否是模块自己发出的（是则**不学习**）。命中即消费掉，避免重复占用。 */
    private boolean consumeSelfCbRequest(long did, byte[] data) {
        try {
            String k = selfCbKey(did, data);
            if (k == null) return false;
            Long ts = selfCbPending.get(k);
            if (ts == null) return false;
            if (System.currentTimeMillis() - ts.longValue() > SELF_REQ_TTL_MS) {
                selfCbPending.remove(k);
                return false;
            }
            selfCbPending.remove(k);
            return true;
        } catch (Throwable t) { return false; }
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
    /** 目标 bot 的数字 ID。缺字段/类型不符时返回 0（调用方普遍用 did==0 判无效）。 */
    private long entryDid(Map<String, Object> m) {
        try {
            Object v = m == null ? null : m.get("did");
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        } catch (Throwable t) { return 0L; }
    }
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
        return findEntryById(id, currentAccount());
    }

    /** 后台任务必须显式指定账号，禁止用当前 UI 账号读取目标。 */
    private Map<String, Object> findEntryById(String id, int account) {
        if (id == null) return null;
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            loadTargetsInto(accountPrefix(account), list);
            for (Map<String, Object> m : list) {
                if (id.equals(entryId(m))) return m;
            }
        } catch (Throwable ignored) {}
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

    /**
     * 条目相关键前缀。
     *
     * 注意：这里漏一个前缀的后果不是"少删一点"，而是**状态复活** ——
     * nextEntryId() 按 <did>_<seq> 生成 id，清空配置后重新添加同一个 bot 会拿到同一个 id，
     * 残留的 frozen_/snooze_ 会被新条目直接继承（表现为"重新添加了但它就是不签"）。
     * v1.6.0 补齐了 v1.3.0 之后新增的全部状态键。
     */
    private static final String[] ENTRY_HEADS = {"learned_", "kind_", "did_", "data_", "hash_", "msg_id_",
            "pre_", "loc_", "last_", "opt_", "retry_", "retry_at_", "retry_day_", "sent_at_", "answered_", "send_n_",
            "signed_at_", "miss_at_", "peerkind_",
            "frozen_", "snooze_", "pendcfm_", "pendcfm_day_", "pendcfm_note_", "pendcfm_result_", "title_",
            "fail_streak_", "fail_laststamp_", "fail_alert_", "fail_date_",
            "timer_plan_", "unknown_reply", "sign_days", "streak", "last_sign_date",
            "panelstale_", "panelstale_day_", "silent_", "silent_day_",
            "fails_today_", "fails_day_", "permfail_"};

    /**
     * 清空配置时**必须保留**的全局设置键（显式白名单）。
     *
     * 为什么不用"删掉所有像条目的键"这种黑名单写法：每次新增状态键都要记得回来改，
     * 而"忘了改"的代价是静默的功能异常（见 ENTRY_HEADS 注释）。白名单只会漏保留
     * （最多是设置被清掉、用户重设一次），不会漏删（漏删会留下看不见的脏状态）。
     */
    private static final Set<String> KEEP_ON_CLEAR = new HashSet<String>(Arrays.asList(
            "jmb_keywords", "jmb_retry", "jmb_wake_cmd", "jmb_alfilter", "jmb_autolearn",
            "jmb_autolearn_net", "jmb_autolearn_net_confirm", "jmb_skip_nav", "jmb_tut_seen", "jmb_prompt_day",
            "jmb_theme", "jmb_lang", "jmb_notify", "jmb_notify_fail_only", "jmb_notify_all_acc",
            "jmb_sort", "jmb_fx", "jmb_judge", "jmb_judge_custom", "jmb_loose",
            "jmb_ok_words", "jmb_fail_words", "jmb_blocked_dids", "jmb_exclude", "jmb_config_ts",
            "jmb_window", "jmb_timer", "jmb_gap", "jmb_missback", "jmb_missdead",
            "jmb_pending_confirm",   // 旧全局池键：仅首次迁移前存在，迁移完即删（见 migratePendingConfirmPool）
            "update_cooldown_at", "update_seen_code", "update_last_notice", "update_last_error"));

    /**
     * 是不是「账号级待确认池」键（acc<N>_pending_confirm）。
     *
     * 单独成函数的原因：KEEP_ON_CLEAR 是精确字符串集合，表达不了 acc<N>_ 这种
     * 不定后缀。用于清空配置时的保留判定。
     */
    private static boolean isPendingConfirmPoolKey(String k) {
        if (k == null) return false;
        if (!k.startsWith("acc") || !k.endsWith("_pending_confirm")) return false;
        int i = k.indexOf('_');
        if (i <= 3) return false;                       // acc 与 _ 之间至少要有一位数字
        for (int j = 3; j < i; j++) {
            if (!Character.isDigit(k.charAt(j))) return false;
        }
        return true;
    }

    /**
     * 一次性迁移：旧全局待确认池 jmb_pending_confirm → acc0_pending_confirm。
     *
     * 背景：1.6.0 起待确认池按账号隔离（键由 Keys.pendingConfirm(int) 提供），
     * 旧版本只有一个全局键。不迁移的话，老用户升级后原来攒着的候选目标会静默消失。
     *
     * 三条纪律：
     *   1) 只执行一次 —— 迁完即删旧键，下次启动 contains 为假，直接返回。
     *   2) 绝不覆盖已有数据 —— acc0 已有非空池时，只删旧键、不写入（存量优先）。
     *   3) 失败不影响启动 —— 整体 try 包裹，异常只记日志。
     *
     * @return 实际搬运的条目数（供启动日志展示）
     */
    private int migratePendingConfirmPool() {
        try {
            if (!prefs.contains(kPendingConfirm())) return 0;        // 旧键不存在 → 已迁过或无数据
            String raw = prefs.getString(kPendingConfirm(), "");
            if (raw == null) raw = "";
            String trimmed = raw.trim();

            // 旧键内容为空：直接清掉，不产生日志噪音
            if (trimmed.length() == 0) {
                prefs.edit().remove(kPendingConfirm()).apply();
                return 0;
            }

            int n = 0;
            for (String line : trimmed.split("\n")) {
                if (line != null && line.trim().length() > 0) n++;
            }

            String target = kPendingConfirm(0);
            String exist = prefs.getString(target, "");
            boolean hasExisting = exist != null && exist.trim().length() > 0;

            if (hasExisting) {
                // 存量优先：不覆盖账号 0 已有的池子，只把旧全局键清理掉
                prefs.edit().remove(kPendingConfirm()).apply();
                jlog("[迁移] pending_confirm：账号0 已有 " + countPoolLines(exist)
                        + " 项，旧键 " + n + " 项未合并（保留现有数据），旧键已删除");
                return 0;
            }

            prefs.edit()
                 .putString(target, trimmed)
                 .remove(kPendingConfirm())
                 .apply();
            jlog("[迁移] pending_confirm：旧键 → 账号0，" + n + " 项");
            return n;
        } catch (Throwable t) {
            logw("迁移待确认池失败: " + t);
            return 0;
        }
    }

    /** 数一个池子字符串里有几行有效条目。 */
    private static int countPoolLines(String raw) {
        if (raw == null || raw.trim().length() == 0) return 0;
        int n = 0;
        for (String line : raw.split("\n")) {
            if (line != null && line.trim().length() > 0) n++;
        }
        return n;
    }

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

    /**
     * 清理"孤儿状态键"：前缀像条目、但对应条目已经不存在的 frozen_/snooze_/pendcfm_ 等。
     *
     * 为什么需要：v1.6.0 之前 clearAllConfig 用的是不完整的 ENTRY_HEADS，删目标时会留下
     * 这些键；而 nextEntryId() 复用同一个 id，用户重新添加同一个 bot 就会继承旧状态
     * （最典型的是 frozen_=true → 加回来了却永远不签，且没有任何日志）。
     * 这里在启动时扫一遍，老用户升级即自动修复。
     *
     * @return 清掉的键数
     */
    private int sweepOrphanEntryKeys() {
        int removed = 0;
        try {
            Set<String> live = new HashSet<String>();
            for (Map<String, Object> m : targetsSnapshot()) live.add(entryId(m));
            // 所有账号的条目都要算"存活"，否则会误删别的账号正在用的状态。
            // 必须遍历**真实槽位**：用 0..activatedAccounts()-1 会漏掉非连续槽位，
            // 那些账号的状态键会被判成"孤儿"删除（用户反馈「停用后目标不显示」的
            // 相关根因之一 —— 删掉的是 sent_at_/opt_/frozen_ 等状态，不是目标本身）。
            for (int i : accountSlots()) {
                List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
                try { loadTargetsInto(accountPrefix(i), l); } catch (Throwable ignored) {}
                for (Map<String, Object> m : l) live.add(entryId(m));
            }
            SharedPreferences.Editor e = prefs.edit();
            for (String k : new ArrayList<String>(prefs.getAll().keySet())) {
                if (!k.startsWith("acc")) continue;
                int us = k.indexOf('_');
                if (us <= 0) continue;
                String body = k.substring(us + 1);
                String hitId = null;
                for (String h : ORPHAN_HEADS) {
                    if (body.startsWith(h)) { hitId = body.substring(h.length()); break; }
                }
                if (hitId == null || hitId.length() == 0) continue;
                if (live.contains(hitId)) continue;
                e.remove(k); removed++;
            }
            if (removed > 0) e.apply();
        } catch (Throwable t) { noteSwallowed("sweepOrphanEntryKeys", t); }
        if (removed > 0) jlog("清理孤儿状态键 " + removed + " 个（条目已删但状态残留，会导致重新添加时状态复活）");
        return removed;
    }

    /**
     * 只有"必须挂在条目上才有意义"的键才参与孤儿清理；cfg_/daycap_ 这类账号级配置不能碰。
     *
     * 2026-10-03：**改为直接引用 Keys.ORPHAN_HEADS**，不再在本文件维护副本。
     * 起因：两处各写一份，实际执行的是这一份，而它漏了 opt_ / send_n_ / signed_at_ /
     * miss_at_ / fail_date_，还重复了两次 answered_ —— 结果删掉目标后这些键成了
     * 永久残渣（实测 -4476076932 已删，opt_ / send_n_ / miss_at_ / signed_at_ 仍在）。
     * 一份表两处维护必然漂移，改为单一真相源。
     */
    private static final String[] ORPHAN_HEADS = Keys.ORPHAN_HEADS;

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
                if (KEEP_ON_CLEAR.contains(k)) continue;                 // 全局设置：保留
                if (k.startsWith("acc") && k.contains("_cfg_")) continue; // 账号级配置：保留
                // 账号级待确认池：属于用户数据而非配置，与旧全局键同待遇，一律保留。
                // KEEP_ON_CLEAR 是精确集合，表达不了 acc<N>_ 这种不定后缀，故在此单独判。
                if (isPendingConfirmPoolKey(k)) continue;
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
            if (!btns.isEmpty()) updatePanelLiveButtons(did, mid, btns, captureAcc >= 0 ? captureAcc : currentAccount());
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
            // 捕获窗口过期检查（与 UI 路径一致）：以前只有 handleTapCapture 里查，
            // 网络层兜底不查 → 用户武装捕获后忘了、几小时后点任意按钮仍会弹绑定面板。
            if (System.currentTimeMillis() - captureArmedAt > 120000L) {
                captureArmed = false;
                jlog("【捕获】武装已超过 2 分钟，自动取消（不弹面板）");
                return;
            }
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
                        toastAfterSign(sendSign(m, currentAccount()), m);
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

    /**
     * 按 sendSign 的真实返回码提示用户。
     * 以前调用方一律弹「已发起签到」—— 已签过的目标内部直接 return，
     * 用户却仍看到"已发起"，以为签了（用户实际反馈的现象）。
     */
    private void toastAfterSign(int code, Map<String, Object> entry) {
        try {
            String nm = "";
            try { nm = targetTitle(entryDid(entry)); } catch (Throwable ignored) {}
            switch (code) {
                case SignLogic.SKIP_NONE:
                    toast(Lang.tr("已发起签到，结果见提示/日志"));
                    break;
                case SignLogic.SKIP_ALREADY_SIGNED:
                    toast(Lang.tf("「{0}」今天已经签过了", nm));
                    break;
                case SignLogic.SKIP_IN_FLIGHT:
                    toast(Lang.tf("「{0}」正在签到中，请稍候", nm));
                    break;
                case SignLogic.SKIP_SENT_PENDING:
                    toast(Lang.tf("「{0}」已发出、正在等结果", nm));
                    break;
                case SignLogic.SKIP_RETRY_EXHAUST:
                    toast(Lang.tf("「{0}」今日重试次数已用完", nm));
                    break;
                case SignLogic.SKIP_BACKOFF:
                    toast(Lang.tf("「{0}」失败退避中，稍后自动重试", nm));
                    break;
                case SignLogic.SKIP_DISABLED:
                    toast(Lang.tf("「{0}」已停用 / 暂停 / 冻结", nm));
                    break;
                case SignLogic.SKIP_SEND_FAIL:
                    toast(Lang.tf("「{0}」发送失败：取不到会话数据，先进该会话发一条消息", nm));
                    break;
                case SignLogic.SKIP_ACCOUNT_DISABLED:
                    toast(Lang.tf("「{0}」所在账号已停用，未发送；如需签到请先在账号一览里启用", nm));
                    break;
                default:
                    toast(Lang.tr("已发起签到，结果见提示/日志"));
                    break;
            }
        } catch (Throwable t) { noteSwallowed("toastAfterSign", t); }
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
        s.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toastAfterSign(sendSign(m, currentAccount()), m); } });
        b.addView(s);
        Button e=mkBtn(act); withIconText(act, e, "pencil", "编辑（标签/前置命令/定位）");
        e.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ showEditEntry(act, m); } });
        b.addView(e);
        Button rb=mkBtn(act); withIconText(act, rb, "repeat", "重绑为回调（去点它的按钮）");
        rb.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ toast("去该 bot 会话点一下要绑的签到按钮，会自动作为回调新增"); startCapture(act); } });
        b.addView(rb);
        // 「待确认」处置（v1.6.0）：长按菜单里也放一份，两条路径都能处理
        try {
            final String _pp = accountPrefix();
            if (isPendingConfirm(_pp, entryId(m))) {
                final long _pd = entryDid(m);
                final String _pi = entryId(m);
                Button pk = mkBtn(act); withIconText(act, pk, "warn", "待确认：确认已签");
                pk.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                    pendConfirmAsSigned(_pp, _pi, _pd); dismissOne(entryActionsDlg[0]); showList(act);
                } });
                b.addView(pk);
                Button pr2 = mkBtn(act); withIconText(act, pr2, "refresh", "待确认：重试一次");
                pr2.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                    pendConfirmRetry(_pp, _pi, _pd); dismissOne(entryActionsDlg[0]); showList(act);
                } });
                b.addView(pr2);
                Button pi2 = mkBtn(act); withIconText(act, pi2, "x", "待确认：忽略今天");
                pi2.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                    pendConfirmIgnoreToday(_pp, _pi, _pd); dismissOne(entryActionsDlg[0]); showList(act);
                } });
                b.addView(pi2);
            }
        } catch (Throwable _eP2) { noteSwallowed("showEntryActions(pendcfm)", _eP2); }
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
            // 发完全部前置命令。等面板状态已在 sendSign 里建立，面板一刷新就立即点按钮。
            //
            // 这里**不再**用"超时后拿旧 msg_id 硬打"的老兜底。实测 hope 社工库这类
            // 「新发消息型」bot：收到 /start 后 1 秒内先回一句纯文本欢迎语（不带按钮），
            // 真正带签到按钮的面板要 34 秒 ~ 2 分钟后才推过来（2026-09-24 实测：
            // 21:30:06 发命令 → 21:32:03 面板才更新）。旧代码 8 秒就放弃，然后拿绑定时
            // 那条早已失效的 msg_id 去点，必然 MESSAGE_ID_INVALID。
            //
            // 新策略：等待状态**不删除**，超时只记一条日志。面板事件什么时候来、
            // 什么时候点 —— waitingPanelDeadline 仅用于让 onPanelRefreshedForWaiting
            // 挑"等得最久"的那条，不再当作"过时不候"的依据。
            final String id = entryId(entry);
            jlog("前置命令已全部发出，等待面板刷新（最长等 " + (PANEL_WAIT_MS / 1000L)
                 + " 秒后改为被动等待，不放弃）…");
            mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                try {
                    boolean stillWaiting;
                    synchronized (waitingPanel) { stillWaiting = waitingPanel.containsKey(id); }
                    if (!stillWaiting) return;   // 面板已到并已触发
                    // 仍在等：保留等待状态（迟到的面板照样能命中）。
                    logd("[" + id + "] 面板还没来（该 bot 推面板较慢，实测 34 秒~2 分钟）…继续等");
                } catch (Throwable ignored) {}
            } }, PANEL_WAIT_MS);

            // 总上限：到点仍未命中就释放等待状态，交回正常重试逻辑。
            // 不设上限的话，等待条目会一直挂在 waitingPanel 里；而 wakeFired 45 秒就解锁，
            // 下一轮 tick 会重复进入"等面板"分支并重发前置命令（刷屏且浪费配额）。
            mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                try {
                    boolean dropped;
                    synchronized (waitingPanel) { dropped = waitingPanel.remove(id) != null; waitingPanelDeadline.remove(id); }
                    if (dropped) {
                        logw("[" + id + "] 等面板总超时（" + (PANEL_TOTAL_MS / 1000L)
                             + " 秒），本次放弃并交回重试；若该 bot 面板来得很慢，建议改用「文本指令」目标");
                        Map<String, Object> en = findEntryById(id);
                        if (en != null) countSoftFail(en, account, "等面板总超时，稍后重试");
                    }
                } catch (Throwable ignored) {}
            } }, PANEL_TOTAL_MS);
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
                // 同 did 下可能同时挂着多个条目（cb1/cb3/...），旧代码只比 did 且 break，
                // 于是"任意一个目标的面板刷新"都会命中最先遍历到的那个 —— 命中谁全看
                // HashMap 迭代顺序。改成：全部收集后优先取"等待时间最长（最早发起）"的那条，
                // 且一次只消费一个（面板刷新事件本来就对应一次前置命令的回应）。
                long bestDeadline = Long.MAX_VALUE;
                ArrayList<String> stale = new ArrayList<String>();
                for (java.util.Map.Entry<String, long[]> e : waitingPanel.entrySet()) {
                    long[] v = e.getValue();
                    if (v == null || v.length < 2 || v[0] != did) continue;
                    Long dl = waitingPanelDeadline.get(e.getKey());
                    long dlv = dl == null ? Long.MAX_VALUE : dl.longValue();
                    if (dlv < bestDeadline) { bestDeadline = dlv; hitId = e.getKey(); hitAcc = (int) v[1]; }
                }
                for (java.util.Map.Entry<String, long[]> e : waitingPanel.entrySet()) {
                    long[] v = e.getValue();
                    if (v != null && v.length > 1 && v[0] == did && !e.getKey().equals(hitId)) stale.add(e.getKey());
                }
                if (hitId != null) {
                    waitingPanel.remove(hitId);
                    waitingPanelDeadline.remove(hitId);
                    for (String sk : stale) { waitingPanel.remove(sk); waitingPanelDeadline.remove(sk); }
                }
            }
            if (hitId != null) {
                jlog("[" + hitId + "] 面板已刷新，立即点按钮签到（msg_id 最新）");
                // 找到对应条目并触发签到
                Map<String, Object> target = hitAcc >= 0 ? findEntryById(hitId, hitAcc) : findEntryById(hitId);
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

    private boolean targetContains(long did, int account) {
        if (account < 0) return targetContains(did);
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try { loadTargetsInto(accountPrefix(account), list); } catch (Throwable ignored) {}
        for (Map<String, Object> m : list) if (entryDid(m) == did) return true;
        return false;
    }

    /** 返回该账号/目标会话最近一次仍在等待结论的目标，只允许一条回复绑定一个目标。 */
    private Map<String, Object> latestPendingTarget(String prefix, long did) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try { loadTargetsInto(prefix, list); } catch (Throwable ignored) {}
        Map<String, Object> best = null; long bestAt = 0L;
        long now = System.currentTimeMillis();
        for (Map<String, Object> m : list) {
            if (entryDid(m) != did) continue;
            String id = entryId(m);
            long sent = prefs.getLong(prefix + "sent_at_" + id, 0L);
            if (sent <= 0L || now - sent < 0L || now - sent > PENDING_TTL_MS) continue;
            if (!isInFlightOrPending(prefix, id)) continue;
            if (sent >= bestAt) { bestAt = sent; best = m; }
        }
        return best;
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

    private void learnFromNetwork(long did, String text, int account) {
        if (!AUTO_LEARN_NET || !LEARN_ENABLED) return;
        if (text == null) return;
        if (did == 0) return;   // 群 ID 是负数，合法
        String t = String.valueOf(text).trim();
        if (t.length() == 0 || t.length() > 20) return;
        if (targetContains(did, account)) return;
        boolean isBotPre = false;
        try {
            Object mc0 = getMessagesController(account);
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
            Object mc = getMessagesController(account);
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
            if (pendingConfirmAdd(account, did, t)) {
                jlog("【网络层学习·待确认】" + did + " -> " + t + "（已入待确认池）");
                toast("已入待添加：确认后才加入目标");
            }
            return;
        }
        learnTarget(did, t);
        jlog("【网络层自动学习】新目标 " + did + " -> " + t);
    }

    /** 读「bot 连续不回复」计数；跨天自动视为 0。 */
    private int silentCount(String prefix, String id) {
        try {
            if (!todayStr().equals(prefs.getString(prefix + "silent_day_" + id, ""))) return 0;
            return prefs.getInt(prefix + "silent_" + id, 0);
        } catch (Throwable t) { return 0; }
    }

    /** 读熔断计数；跨天自动视为 0（昨天的失败不该影响今天）。 */
    private int panelStaleCount(String prefix, String id) {
        try {
            if (!todayStr().equals(prefs.getString(prefix + "panelstale_day_" + id, ""))) return 0;
            return prefs.getInt(prefix + "panelstale_" + id, 0);
        } catch (Throwable t) { return 0; }
    }

    /** 清掉 panelStale 熔断计数（签到成功 / 用户手动处理后调用）。 */
    private void clearPanelStale(String prefix, String id) {
        try {
            prefs.edit().remove(prefix + "panelstale_" + id)
                 .remove(prefix + "panelstale_day_" + id).apply();
        } catch (Throwable t) { noteSwallowed("clearPanelStale", t); }
    }

    private void markSigned(String prefix, String id) {
        // 重构 1.6.1：实现搬到 SignStateStore（那里集中维护"写 last_ 必须连带清理"
        // 等不变式）。此处保留薄封装，既有 6 个调用点不变。
        stateStore.markSigned(prefix, id);
    }

    /**
     * 记录本次执行的**归类**（2026-09-28）。
     *
     * 取代改前散落的"待确认"布尔标记：现在每次执行必须落到恰好一个归类，
     * 界面文案、处置入口、是否重试全部由它派生（见 SignLogic.R_* 与 resultCode）。
     * 这是**唯一入口** —— 别再直接写 pendcfm_，否则界面拿不到原因。
     */
    private void markResultCode(String prefix, String id, int result) {
        try { stateStore.markResult(prefix, id, result); } catch (Throwable ignored) {}
    }

    /** 读取归类码；无归类返回 -1。 */
    private int resultCodeOf(String prefix, String id) {
        try { return stateStore.resultOf(prefix, id); } catch (Throwable t) { return -1; }
    }

    /**
     * 撤掉与"今日已签"互斥的所有状态键。
     * 抽成单一入口：以前这些 remove 散在 markSigned 里，早退分支一 return 就全漏掉。
     * 任何"今天签上了"的路径都必须调它，而不是各写各的。
     */
    private void clearPostSignState(String prefix, String id) {
        stateStore.clearPostSignState(prefix, id);
    }

    private void markSignedFromRequest(int account, long did, String text) {
        try {
            if (text == null) return;
            List<Map<String,Object>> list = new ArrayList<Map<String,Object>>();
            loadTargetsInto(accountPrefix(account), list);
            for (Map<String,Object> m : list) {
                if (entryDid(m) == did && KIND_TEXT.equals(entryKind(m)) && entryText(m).equals(String.valueOf(text))) {
                    markSigned(accountPrefix(account), entryId(m));
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private void markSignedFromCallback(int account, long did, byte[] data) {
        try {
            if (data == null) return;
            List<Map<String,Object>> list = new ArrayList<Map<String,Object>>();
            loadTargetsInto(accountPrefix(account), list);
            for (Map<String,Object> m : list) {
                if (entryDid(m) == did && KIND_CB.equals(entryKind(m)) && Arrays.equals(entryData(m), data)) {
                    markSigned(accountPrefix(account), entryId(m));
                    return;
                }
            }
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
        updatePanelLive(did, mid, replyMarkup, null, currentAccount());
    }

    /** 同上，额外带上消息正文（排除规则要用它匹配验证码类提示语） */
    private void updatePanelLive(long did, int mid, Object replyMarkup, String bodyText){
        updatePanelLive(did, mid, replyMarkup, bodyText, currentAccount());
    }

    private void updatePanelLive(long did, int mid, Object replyMarkup, String bodyText, int account){
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
            updatePanelLiveButtons(did, mid, btns, account);
        } catch (Throwable ignored){}
    }

    /** 采集：按钮清单（捕获采样/自动学习时）→ 写缓存 + 事件驱动 */
    private void updatePanelLiveButtons(long did, int mid, List<Object[]> btns){
        updatePanelLiveButtons(did, mid, btns, currentAccount());
    }

    private void updatePanelLiveButtons(long did, int mid, List<Object[]> btns, int account){
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
            if (inWindow() || inMissBackTime()) fireLiveSign(did, account); else logd("[面板] 窗口外且非补签时段，不触发补签 uid="+did);
        } catch (Throwable _e7) { noteSwallowed("updatePanelLiveButtons", _e7); }
    }

    /** 该 did 的面板缓存是否"新鲜"（默认 90 秒内更新过）。区分"刚推来的有效面板"与"旧残留"。 */
    private boolean isPanelFresh(long did, long maxAgeMs){
        try {
            PanelLive pl;
            synchronized (panelLive){ pl = panelLive.get(did); }
            if (pl == null) return false;
            synchronized (pl){
                if (pl.buttons.isEmpty()) return false;
                return (System.currentTimeMillis() - pl.ts) <= maxAgeMs;
            }
        } catch (Throwable _ePF) { noteSwallowed("isPanelFresh", _ePF); return false; }
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
    private void fireLiveSign(final long did, final int fAcc){
        try {
            synchronized (panelHitBusy){ if (panelHitBusy.contains(did)) return; panelHitBusy.add(did); }
            mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                synchronized (panelHitBusy){ panelHitBusy.remove(did); }
                try {
                    String prefix=accountPrefix(fAcc);
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
                            sendSign(m, fAcc);
                            break;
                        }
                        // 心跳/定时可能刚给这个目标发过，正在等回复 —— 别重复发。
                        // panelHitBusy 只防"面板事件自身"重入，防不住跨路径撞车。
                        // 必须查「在途 or 已发出待结论」：后者跨进程重启仍有效。
                        if (isInFlightOrPending(prefix, id)) {
                            logd("[面板事件] " + did + " 该目标正在发送中/已发出待结论，跳过补签");
                            break;
                        }
                        logd("[面板事件] "+did+" 面板已更新且今日未签，立即补签");
                        sendSign(m, fAcc);
                        break;
                    }
                } catch (Throwable _e8) { noteSwallowed("fireLiveSign", _e8); }
            } }, 700L);
        } catch (Throwable _e9) { noteSwallowed("fireLiveSign", _e9); }
    }

    private void setCtxTrace(String t) {
        try { ctxTrace = t == null ? "" : t; } catch (Throwable ignored) {}
    }

    private int sendSign(Map<String, Object> entry, int account) { return sendSign(entry, account, false); }

    /**
     * 兼容入口：调用方已知账号索引。
     * @deprecated 异步路径请用 {@link #sendSign(Map, AccountManager.Ctx, boolean)} ——
     *             它把账号"钉死"在发起时刻，避免执行时读到别的账号。
     */
    private int sendSign(Map<String, Object> entry, int account, boolean manual) {
        return sendSign(entry, accountManager.ctxOf(account, "sendSign(int)"), manual);
    }

    /**
     * @param manual 用户显式操作：豁免"今天已签/重试用尽/退避"这类进度限制。
     * @return SignLogic.SKIP_* 码；SKIP_NONE 表示请求**确实发出去了**。
     *         调用方应据此提示用户，不要无条件 toast"已发起"。
     */
    private int sendSign(Map<String, Object> entry, AccountManager.Ctx ctx, boolean manual) {
        /* 手动签到：先清掉所有"已发出/在途"状态，确保不被闸拦（2026-09-29） */
        /* manual 清障移到 decideSign 通过后：避免无意义的写操作（2026-09-30）*/
        final int account = ctx.account;
        pace();
        // 每条目标一个链路 id：解析 → 发出 → 响应 → 判定 可串联。
        // 前缀带账号（a0_/a1_）：多账号并行时全局随机数会撞号，日志里
        // 同一个 tXXXX 横跨两个账号，排障时极易误判成"串号"。
        setCtxTrace("a" + account + "_" + Integer.toHexString(random.nextInt(0xFFFF)));
        // 日志上下文跟随「实际发送的账号」，避免全账号循环里延迟任务沿用上一个账号的标签
        try { ctxAcc = accountLabel(account); ctxRound++; } catch (Throwable _e10) { noteSwallowed("sendSign", _e10); }
        final long dialogId = entryDid(entry);
        final String id = entryId(entry);
        final String kind = entryKind(entry);
        final String fText = entryText(entry);
        // ── 统一签到闸（1.6.1 根治）──
        // 判据收敛到 SignLogic.decideSign（纯逻辑 + 单测），所有触发源共用。
        // 历史问题：去重分散在 4 处（节流 / pendingSigns / kLast / 排队时间戳），
        // 彼此不知道对方存在 → 同一 bot 连发两条（用户实测 1.5.8）。
        final String _pfx = ctx.prefix;   // Ctx 已锁定账号前缀
        SignLogic.SignGate _gate = new SignLogic.SignGate();
        _gate.manual = manual;
        _gate.inFlight = isPendingFresh(_pfx, id);
        _gate.signedToday = todayStr().equals(prefs.getString(kLast(_pfx, id), ""));
        _gate.sentPendingFresh = isSentPendingFresh(_pfx, id);
        // 今日熔断（失败达上限 / 命中确定性失败词）——manual 也拦：
        // 「请先关注」这类再点一百次也是同一句话，不该让用户手动触发时白跑。
        _gate.disabled = isDailyBlocked(_pfx, id) || isPermanentFailedToday(_pfx, id)
                || isFrozen(_pfx, id) || isSnoozed(_pfx, id) || isBotBlocked(dialogId);
        // 待确认不得再自动重发（2026-09-30）：转待确认时清了 sent_at_/retry，
        // 闸若不看这个标记，每轮心跳都会再发一次 —— 群聊里没有 bot 就一直刷。
        // manual（用户点重试/测试）仍放行。
        _gate.pendingUnconfirmed = isPendingConfirm(_pfx, id);
        // 账号级停用（1.6.1 补）：此前 isAccountEnabled 只在「账号一览界面」「一键签全部
        // 账号」「心跳的非当前账号分支」三处被检查，**没有进这道统一闸** ——
        // 于是切到被停用的账号后，任何触发源（进入窗口/打开聊天/事件补签/定时/排队）
        // 都会照常签到，停用开关形同虚设。判据收敛到这里，所有触发源共用。
        _gate.accountDisabled = !isAccountEnabled(account);
        // 发送次数硬闸（2026-10-03 修 13 次重复发送）：
        // 上面那条 pendingUnconfirmed 只在 pendcfm_ 还在时有效，而它会被
        // sweepStalePendingConfirm 误清；本闸读 send_n_（按天记），
        // 不受任何清理影响，是"发了没结论"这条路上的最后一道硬闸。
        // manual（用户点重试/测试）仍放行 —— 与 retryExhausted 同语义。
        _gate.sendAttemptsExhausted = isSendAttemptsExhausted(_pfx, id);
        int _skip = SignLogic.decideSign(_gate);
        if (_skip != SignLogic.SKIP_NONE) {
            logd("目标 " + id + " 跳过发送（" + SignLogic.skipLabel(_skip) + "）");
            return _skip;
        }
        // 递归熔断（1.6.1 加的硬闸）：同一 id 在 3 秒内被 sendSign 进入 5 次以上，
        // 说明存在调用环（历史事故：sendSign ↔ onPanelRefreshedForWaiting 互递归，
        // 一秒刷出 6 万条日志）。宁可这次不签，也不能让日志与配额被烧穿。
        try {
            long now = System.currentTimeMillis();
            Long last = signEnterAt.get(id);
            int n = (last != null && now - last < 3000L) ? (signEnterCount.get(id) == null ? 1 : signEnterCount.get(id)) + 1 : 1;
            signEnterAt.put(id, now);
            signEnterCount.put(id, n);
            if (n > 5) {
                loge("[" + id + "] 检测到 sendSign 调用环（3 秒内第 " + n + " 次），已熔断本次调用");
                return SignLogic.SKIP_IN_FLIGHT;
            }
        } catch (Throwable _eRL) { noteSwallowed("sendSign-recursion-guard", _eRL); }
        String prefix = accountPrefix(account);
        // 注：v1.5.8 一度加过"同一 bot 串行闸"（防多目标抢面板），实测副作用太大 ——
        // 签到流程本身要分两步调 sendSign（发前置命令 → 面板刷新后点按钮），
        // 第二步会被自己占的闸拦住，导致"面板已刷新却点不出去、永远显示已有签到在途"。
        // 已按用户要求移除：不拦、直接发，判断权交给用户。
        Object mc = getMessagesController(account);
        if (mc == null) {
            jlog("MessagesController 为空(account=" + account + ")");
            return SignLogic.SKIP_NONE;
        }
        String peerKind = entryPeerKind(entry);
        Object peer = null;
        if ("chat".equals(peerKind)) {
            // 群 / 频道目标：走 chat 支路（getUser 对群聊无效）
            peer = resolveInputPeerAny(dialogId, account, "chat");
            if (peer == null) {
                noteSwallowed("群peer解析(did=" + dialogId + ")", null);
                countSoftFail(entry, account, "取不到该群/频道的会话数据（先进该群发一条消息再试）");
                return SignLogic.SKIP_SEND_FAIL;
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
                return SignLogic.SKIP_SEND_FAIL;
            }
        }
        final List<String> pres = cbPres(entry);
        if (KIND_CB.equals(kind) && !pres.isEmpty() && !wakeFired.contains(id)) {
            // 缓存优先（1.6.1）：面板**事件**可能早就发生过了 —— bot 在发起签到之前就推过
            // 带按钮的消息（实测 2026-09-24：22:05:43 缓存 msg=21833，22:06:04 才发起）。
            // 这时若仍"等面板事件"，等于等一个已过去的事件，只能拖到总超时。
            //
            // 红线：**绝不能**在这里调 onPanelRefreshedForWaiting —— 它命中后会
            // sendSign(...,true) 回到本函数，形成无限递归（实测一次刷出 6 万条日志）。
            // 缓存命中就只跳过"发前置命令"这一步，其余仍走下面的正常按钮流程，
            // 由 resolveLiveButton 用缓存里的最新 msg_id 直接发。
            final boolean cacheHit = isPanelFresh(dialogId, 90L * 1000L);
            if (cacheHit) {
                jlog("[" + id + "] 缓存里已有新鲜面板，跳过前置命令，直接点按钮");
            } else {
                wakeFired.add(id);
                mainHandler.postDelayed(new Runnable() {
                    @Override public void run() { wakeFired.remove(id); }
                }, 45000L);
                // 发前置命令前就建立「等面板」状态，面板事件一到立即命中（不因递归延时错过）
                synchronized (waitingPanel) {
                    waitingPanel.put(id, new long[]{dialogId, account});
                    waitingPanelDeadline.put(id, System.currentTimeMillis() + PANEL_WAIT_MS);
                }
                jlog("[" + id + "] 前置命令将发送，先进入等面板状态（did=" + dialogId + "）");
                sendPreAndResign(entry, account, peer, pres, 0);
                return SignLogic.SKIP_SEND_FAIL;
            }
            // cacheHit 分支：不 return，落到下面的按钮发送流程
        }
        try {
            Object req;
            if (KIND_CB.equals(kind)) {
                Class<?> cbCls = classEx("org.telegram.tgnet.TLRPC$TL_messages_getBotCallbackAnswer");
                // 1.4.5 Live Panel：优先当前面板匹配的按钮（发完前置命令重入时面板已刷新）
                CbButton live = resolveLiveButton(dialogId, entryData(entry), fText);
                // 守卫（1.6.1）：panelLive 里没有该 did 的**新鲜**面板时，entry 里存的 msg_id 是
                // "用户绑定时那一条"。对「新发消息型」bot（每发一条指令就换一条带按钮的新消息，
                // 旧按钮立即失效）必然 MESSAGE_ID_INVALID —— 实测 hope 社工库 8439387373 每次如此。
                // 这种 bot 的按钮只能等它把新消息推过来（onUpdateProcessed → updatePanelLive）；
                // 拿不到就应"稍后重试"，而不是发一个必然失败的请求再标「待确认」。
                boolean panelFresh = isPanelFresh(dialogId, 90L * 1000L);
                if (live == null && !panelFresh) {
                    logw("[" + id + "] 面板未就绪（该 bot 的按钮随消息变化，需等新消息）→ 本次跳过，稍后重试");
                    countSoftFail(entry, account, "等不到带签到按钮的新消息，稍后重试");
                return SignLogic.SKIP_SEND_FAIL;
                }
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
            // 登记「这条请求是模块自己发的」：网络层 hook 命中后跳过学习，
            // 否则面板按钮 data 与目标旧 data 不同时会被学成新目标（实测目标自己冒出来）。
            if (KIND_CB.equals(kind)) {
                byte[] _sentData = null;
                try { Object _d = getFieldValSafe(req, "data"); if (_d instanceof byte[]) _sentData = (byte[]) _d; } catch (Throwable ignored) {}
                if (_sentData == null) { try { _sentData = entryData(entry); } catch (Throwable ignored) {} }
                markSelfCbRequest(dialogId, _sentData);
            }
            try { prefs.edit().putLong(prefix + "sent_at_" + id, System.currentTimeMillis()).commit(); } catch (Throwable _e11) { noteSwallowed("sendSign", _e11); }
            // 当日发送次数 +1（2026-10-01）：V_UNKNOWN 不涨 kRetry，靠这个计数
            // 给"发出后无结论"一个硬上限，见 SignLogic.MAX_SEND_ATTEMPTS。
            final int _attempts = bumpSendAttempts(prefix, id);
            if (_attempts >= SignLogic.MAX_SEND_ATTEMPTS) {
                logw("目标 " + id + " 今日已发送 " + _attempts + " 次仍无结论，"
                     + "本次为最后一次；若仍判不出将转「待确认」等你处置");
            }
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
                                    // 计数必须在**所有** panelStale 路径上累加。
                                    // 旧位置在 else 分支里，但正常路径（fPres 非空）总走"拉新面板重试"
                                    // 那条，永远进不到 else → 计数恒 0 → 熔断形同虚设。
                                    int _staleNow = panelStaleCount(fPrefix, fId) + 1;
                                    prefs.edit().putInt(fPrefix + "panelstale_" + fId, _staleNow)
                                         .putString(fPrefix + "panelstale_day_" + fId, todayStr()).apply();
                                    // 熔断前置判断：已经反复点不动就别再重发了。
                                    // 证据（22:33 日志）：按钮过期 → 800ms 后发 /start → 面板更新
                                    // → 面板事件又点按钮 → 又过期……对该 bot（服务端拒绝代点）
                                    // 重试多少次都是同一个结果，只是刷日志、烧请求额度。
                                    int staleBefore = panelStaleCount(fPrefix, fId);
                                    // ── 归类修正（2026-09-28）──
                                    // 旧逻辑：staleBefore >= 1 就熔断，理由是"该 bot 的失败是确定性的"。
                                    // 该断言被实测证伪：
                                    //   · MESSAGE_ID_INVALID 的真实含义是"你用的 msg_id 过期了"，
                                    //     而 resolveLiveButton 本来就能给出面板最新的 msg_id；
                                    //   · 第一次失败往往只是**面板还没推过来**（时序问题），
                                    //     不是"bot 拒绝代点"；
                                    //   · 实测 8439387373 在 10:42 被熔断，当天 12:09 仍签上了 ——
                                    //     证明它并非"确定性拒绝"。
                                    // 正确做法：看**面板新鲜度**，三分类处置。
                                    boolean triedPres = wakeFired.contains(fId);
                                    boolean _pFresh = isPanelFresh(fDialogId, 90L * 1000L);
                                    if (_pFresh) {
                                        // 面板是新的 → 用最新 msg_id 重发一次（这才是 MESSAGE_ID_INVALID 的解法）
                                        int _rt = panelStaleCount(fPrefix, fId) + 1;
                                        prefs.edit().putInt(fPrefix + "panelstale_" + fId, _rt)
                                             .putString(fPrefix + "panelstale_day_" + fId, todayStr()).apply();
                                        if (_rt <= 2) {
                                            logw("按钮已过期(" + errText + ")：面板是新的，用最新按钮重发一次（" + fId + "）");
                                            mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                                                try { sendSign(fEntry, fAccount, true); } catch (Throwable _eRS) { noteSwallowed("sendSign(resend)", _eRS); }
                                            } }, 1200L);
                                            return null;
                                        }
                                        // 重发 2 次仍失效 → 真有问题的按钮
                                        prefs.edit().putInt(kRetry(fPrefix, fId), RETRY_LIMIT)
                                             .putString(kRetryDay(fPrefix, fId), todayStr()).apply();
                                        loge("按钮反复失效（第 " + _rt + " 次，" + errText + "）：已停止重试 " + fId);
                                        noteFailStreak(fAccount, fPrefix, fId, fDialogId, "按钮失效");
                                        markResultCode(fPrefix, fId, SignLogic.R_BTN_STALE);
                                        noteResult(fAccount, false);
                                        return null;
                                    }
                                    // 面板没就绪 → **不发必然失败的请求**，等新消息推过来
                                    // （3788 行注释已说明这个做法，这里保持一致）
                                    if (!triedPres && fPres != null && !fPres.isEmpty()) {
                                        wakeFired.add(fId);
                                        mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                                            try { sendPreAndResign(fEntry, fAccount, fPeer, fPres, 0); } catch (Throwable _e14) { noteSwallowed("sendSign", _e14); }
                                        } }, 5000L);
                                        logw("按钮已过期(" + errText + ")：面板未就绪，拉新面板后重试（" + fId + "）");
                                        return null;
                                    }
                                    // 面板未就绪且没有前置命令可发 → 归类「按钮失效」，等人处置
                                    markResultCode(fPrefix, fId, SignLogic.R_BTN_STALE);
                                    logw("按钮失效且面板未就绪（" + errText + "）：标记「按钮已失效」，等 bot 推新面板（" + fId + "）");
                                    noteResult(fAccount, false);
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
                                    noteFailStreak(fAccount, fPrefix, fId, dialogId, errText);
                                    noteResult(fAccount, false);
                                } else if (upper.contains("BOT_RESPONSE_TIMEOUT")) {
                                    // 判据必须是"今天到底有没有成功结论"，**与按钮/文本模式无关**。
                                    //
                                    // 实测（2026-09-25 08:39:30 ExteraLess / hope 社工库 / cb1 按钮模式）：
                                    //   08:39:30 [成功] 宽松模式：bot 有回复即算成功 → 计入已签
                                    //   08:39:45 [警告] 没等到签到结论(BOT_RESPONSE_TIMEOUT)：15 分钟后重试
                                    //   —— 同一轮签到被判成"成功"又"失败"，界面来回翻。
                                    // 原因：旧条件写成 `LOOSE_MODE && !KIND_CB`，把按钮模式整个排除，
                                    // 而宽松模式下按钮目标其实同样会先判成功。
                                    if (todayStr().equals(prefs.getString(kLast(fPrefix, fId), ""))) {
                                        keepSignedClearBackoff(fPrefix, fId);
                                        logw("已有签到成功结论，忽略本次超时（" + fId + "）");
                                        noteResult(fAccount, true);
                                        return null;
                                    }
                                    // 宽松模式 + 尚无结论：发出即算成功（用户明确选用该档）。
                                    if (LOOSE_MODE) {
                                        markSigned(fPrefix, fId);
                                        logw("宽松模式：指令已发出且 bot 无结论 -> 按已签处理（" + fId + "）");
                                        noteResult(fAccount, true);
                                        return null;
                                    }
                                    // 溯源：把"这轮请求是哪一步、什么时候发的"打出来。
                                    // 否则 BOT_RESPONSE_TIMEOUT 归因不明（实测 09:43 那轮
                                    // 只发了 /start、从没点按钮，却报"没等到签到结论"）。
                                    long _sentAt = 0L;
                                    try { _sentAt = prefs.getLong(fPrefix + "sent_at_" + fId, 0L); } catch (Throwable ignored) {}
                                    logw("[" + fId + "] 收到 " + errText + " —— 本轮请求 kind=" + fKind
                                         + (fPres != null && !fPres.isEmpty() ? "(有前置命令)" : "(无前置命令)")
                                         + (_sentAt > 0 ? " 发出于 " + ((System.currentTimeMillis() - _sentAt) / 1000L) + " 秒前" : ""));
                                    // 2026-09-28：超时后按"有没有收到过回复"归类 ——
                                    // 收到过 → 回复了但判不出（replied_unknown）
                                    // 完全没收到 → bot 未回复（no_reply）
                                    // 改前一律等 10 分钟转"待确认"，但 bot 要么已经回了、
                                    // 要么就是不会回，这 10 分钟没有信息增量。
                                    if (!optimisticSigned(fPrefix, fId)) {
                                        boolean answered = todayStr().equals(prefs.getString(Keys.answered(fPrefix, fId), ""));
                                        int rc = answered ? SignLogic.R_REPLIED_UNK : SignLogic.R_NO_REPLY;
                                        markResultCode(fPrefix, fId, rc);
                                        logw("超时且无结论（" + errText + "）：标记为「"
                                             + (answered ? "已回复但判不出" : "bot 未回复") + "」，"
                                             + "已停止自动重试（" + fId + "）");
                                        noteResult(fAccount, false);
                                        return null;
                                    }
                                    int tOut = prefs.getInt(kRetry(fPrefix, fId), 0);
                                    if (tOut >= 2) {
                                        // 未回复 ≠ 成功。绝不能替 bot 判定结果 ——
                                        // 之前这里按"已发出"记为已签，结果社工库/查询类 bot 因为本来
                                        // 就不会回复，全被标成绿色，看着签了其实什么都没发生。
                                        // 现在改成「待确认」：不算成功、不算失败、**不再自动重试**，
                                        // 用户自己看一眼决定（手动点一次，或有回复了再判）。
                                        prefs.edit().putBoolean(kPendingConfirm(fPrefix, fId), true)
                                             .putString(Keys.pendingDay(fPrefix, fId), todayStr())
                                             .putInt(kRetry(fPrefix, fId), 0)
                                             .remove(kRetryAt(fPrefix, fId))
                                             .remove(kRetryDay(fPrefix, fId))
                                             .commit();
                                        logw("已发出但没等到签到结论：" + fId
                                             + " 标记为「待确认」（不计成功也不计失败，已停止自动重试；"
                                             + "想再试可手动点一次）");
                                    } else if (optimisticSigned(fPrefix, fId)) {
                                        // ── 归类修正（2026-09-28）──
                                        // 旧逻辑：optimisticSigned 读的是 opt_（只表示"请求发出过"），
                                        // 却直接 keepSignedClearBackoff + noteResult(true) —— 把
                                        // **"发出去了"当成了"签成功了"**。
                                        //
                                        // 实测代价（8428906628 个人中心 / 8439387373 hope社工库）：
                                        //   按钮点不动、bot 只回欢迎语，最终仍被标"今日已签"。
                                        //   用户看到绿色以为签上了，实际什么都没发生。
                                        //
                                        // 现在：发出过但无结论 → 不判成功也不判失败，
                                        // 归类为「bot 未回复」，由用户一键处置。
                                        int sil = silentCount(fPrefix, fId) + 1;
                                        prefs.edit().putInt(fPrefix + "silent_" + fId, sil)
                                             .putString(fPrefix + "silent_day_" + fId, todayStr()).apply();
                                        markResultCode(fPrefix, fId, SignLogic.R_NO_REPLY);
                                        logw("已发出但 bot 未回结果(" + errText + ")：标记「bot 未回复」，"
                                             + "不计成功也不计失败，可在目标列表一键忽略（" + fId + "）");
                                        noteResult(fAccount, false);
                                        return null;
                                    } else {
                                        prefs.edit().putInt(kRetry(fPrefix, fId), tOut + 1)
                                             .remove(kLast(fPrefix, fId))
                                             .putLong(kRetryAt(fPrefix, fId), System.currentTimeMillis() + 15L * 60 * 1000)
                                             .putString(kRetryDay(fPrefix, fId), todayStr()).apply();
                                        // 「不回结果的 bot」识别：有些 bot 本来就不回复（查询类、菜单类），
                                        // 每天为它们等满超时纯属浪费，还会刷一屏日志。
                                        // 累计 3 次无响应就停手，并在目标上留个可见标记。
                                        int silentN = prefs.getInt(fPrefix + "silent_" + fId, 0) + 1;
                                        prefs.edit().putInt(fPrefix + "silent_" + fId, silentN)
                                             .putString(fPrefix + "silent_day_" + fId, todayStr()).apply();
                                        if (silentN >= 3) {
                                            prefs.edit().putBoolean(kPendingConfirm(fPrefix, fId), true)
                                                 .putString(Keys.pendingDay(fPrefix, fId), todayStr())
                                                 .putInt(kRetry(fPrefix, fId), 0)
                                                 .remove(kRetryAt(fPrefix, fId))
                                                 .remove(kRetryDay(fPrefix, fId)).commit();
                                            logw("该 bot 连续 " + silentN + " 次没给出签到结论：已标记「待确认」并停止自动重试（"
                                                 + fId + "）。这类 bot 通常不回结论（可能只回面板按钮），可在目标列表里手动处置");
                                            noteResult(fAccount, false);
                                            return null;
                                        }
                                        // 措辞修正（1.6.1）：这句以前写"bot 未响应"，但实测 hope 社工库
                                        // 这类 bot 收到指令后**秒回**（先一句欢迎语），只是回的不是"带按钮的面板"
                                        // 或"签到结论"。用户看到"未响应"会以为 bot 挂了，其实只是没等到我们要的那条。
                                        logw("没等到签到结论(" + errText + ")：15 分钟后重试（" + fId
                                             + "）；若该 bot 会先回欢迎语、面板稍后才到，属正常等待");
                                        noteResult(fAccount, false);
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
                                    // 限流 = "服务器让你等一会儿"，不是"签到失败"。
                                    // 若本步已乐观标记成功，保留已签（避免"签了却显示退避中"）。
                                    if (optimisticSigned(fPrefix, fId)) {
                                        keepSignedClearBackoff(fPrefix, fId);
                                        logw("签到遇限流 " + dialogId + " : " + errText
                                             + "，但已成功发出，保留已签");
                                    } else {
                                        prefs.edit().putInt(kRetry(fPrefix, fId), prefs.getInt(kRetry(fPrefix, fId), 0) + 1)
                                             .remove(kLast(fPrefix, fId))
                                             .putLong(kRetryAt(fPrefix, fId), System.currentTimeMillis() + waitMs)
                                             .putString(kRetryDay(fPrefix, fId), todayStr()).apply();
                                        logw("签到遇限流 " + dialogId + " : " + errText + "，等待 " + waitSec + " 秒后自动重试");
                                    }
                                } else if (optimisticSigned(fPrefix, fId)) {
                                    // 已经成功发出并标了已签，随后的错误不足以否定它 ——
                                    // 保留已签，只记一条日志（用户可在会话里自查）。
                                    keepSignedClearBackoff(fPrefix, fId);
                                    logw("签到后续报错 " + dialogId + " : " + errText
                                         + "，但已成功发出，保留已签（如需重签可手动点一次）");
                                    noteResult(fAccount, true);
                                } else {
                                    int oldRetry = prefs.getInt(kRetry(fPrefix, fId), 0);
                                    prefs.edit().putInt(kRetry(fPrefix, fId), oldRetry + 1)
                                         .remove(kLast(fPrefix, fId))
                                         .putLong(kRetryAt(fPrefix, fId), System.currentTimeMillis() + backoffDelay(oldRetry))
                                         .putString(kRetryDay(fPrefix, fId), todayStr())
                                         .commit();
                                    loge("签到失败 " + dialogId + " : " + errText + "（第" + (oldRetry + 1) + "次，退避重试）");
                                    noteFailStreak(fAccount, fPrefix, fId, dialogId, errText);
                                    noteResult(fAccount, false);
                                }
                            } else if (!KIND_CB.equals(fKind)) {
                                // ── 文本指令：与按钮目标同构，不预先标成功 ──
                                //
                                // 历史（2026-09-25 起）：这里曾无条件 markSigned，
                                // 理由是"sendText 的 delegate 是空实现，没有回执通道，
                                // 不标已签就会每轮巡检重发（实测 5~15 秒一发）"。
                                //
                                // 该前提已被实测证伪：文本目标**有**回复判定通道 ——
                                //   23:47:41 【回复判定】...: 🦹🏻暗影社工库 >> 🗂 AYData | 数据查询
                                //   00:24:23 【就地对答】... 返回未命中词表，留给回复判定
                                // 真正的后果是：先标了已签，此后 bot 回的
                                // "你还没绑定账号 / 请先加入频道" 这类拒绝就再没人管，
                                // 用户看到绿色、实际没签上 —— 与本次系列修复是同一类问题。
                                //
                                // 现在改为：用 opt_（已发出）防重发，而不是用 last_（已签）假装成功。
                                // opt_ 本就是巡检闸门（见 isSentPendingFresh / skipScheduling），
                                // 标了它同样不会重复发，但不会把"发出去了"说成"签上了"。
                                //
                                // 之后的分支与按钮目标完全一致：
                                //   收到回复 → 回复判定；超时无回复 → 「bot 未回复」。
                                markOptimistic(fPrefix, fId);
                                String _ans0 = "";
                                try {
                                    Object _am = getFieldValSafe(response, "message");
                                    if (_am == null) _am = getFieldValSafe(response, "alert");
                                    if (_am != null) _ans0 = String.valueOf(_am);
                                } catch (Throwable ignored) {}
                                logs("文本指令已发出，等待 bot 回复后判定 " + dialogId
                                     + " text=" + fText + (_ans0.length() > 0 ? " · 返回: " + _ans0 : ""));
                            } else {
                                // 只记"请求已发出"（乐观），**不写 kLast**。
                                // 理由：请求成功只代表 TG 服务器收下了这条指令，bot 完全可能回
                                // "你还没绑定账号/请先关注"之类。最终结论由回复判定给出。
                                // 以前在这里直接写 kLast + clearFailStreak，导致：
                                //   ① 列表秒变"已签"，bot 说失败后又被撤 → 界面来回翻；
                                //   ② fail_streak 被清零 → "连续 3 天失败"告警永远触发不了；
                                //   ③ 与 Toast（读 fail_streak）出现"Toast 说失败、列表说成功"
                                //      的经典分裂。
                                markOptimistic(fPrefix, fId);
                                prefs.edit()
                                    .putInt(kRetry(fPrefix, fId), 0)
                                    .remove(kRetryAt(fPrefix, fId))
                                    .remove(kRetryDay(fPrefix, fId))
                                    .commit();
                                // ── 记账修正（2026-10-01）──
                                // 这里**不再**调 updateStreak / noteSignedDay。
                                // 本分支的语义是"请求发送成功"，紧接着的注释也写着
                                // "不等于签到成功"——而 bot 完全可能回「请先关注」。
                                // 原先在此计入连签/日历，导致：
                                //   · 发送成功但被拒绝的日子，也被算进"连续 N 天"
                                //   · bot 随后撤销 kLast，但 streak / sign_days 不会撤销
                                //     → 日历与连续天数长期虚高
                                // 正确时机是 SignStateStore.markSigned（确认成功）——
                                // 那里的 onSigned hook 会调这两个方法，且已被去重。
                                String ans = "";
                                try { Object am = getFieldValSafe(response, "message"); if (am == null) am = getFieldValSafe(response, "alert"); if (am != null) ans = String.valueOf(am); } catch (Throwable _e16) { noteSwallowed("sendSign", _e16); }
                                // 注意：这只是「请求发送成功」，**不等于签到成功**。
                                // bot 完全可能回「你还没有绑定账号」之类。真正的结论由
                                // onUpdateProcessed 的回复判定给出（会覆盖这里的乐观标记）。
                                // 以前这句话打成 [成功] 级「签到完成 ... 机器人返回: false」，
                                // 与判定层的 [错误]「未识别」自相矛盾，误导用户。
                                String lowerAns = ans == null ? "" : ans.toLowerCase();
                                // ── 就地判定（1.6.1）──
                                // 很多 bot 把签到结论放在 callback answer 里（alert / message），
                                // **不再另发一条消息** —— 实测 8708924142 返回
                                // 「⭕ 您今天已经签到过了！」，而 onUpdateProcessed 永远等不到它。
                                // 于是出现：Toast 说成功、列表不刷新、日志无判定、处置条不出现。
                                // 这里用**同一套词表**当场判一次；判不出来才留给回复判定层。
                                try {
                                    String[] _dup = SignLogic.DUP_WORDS_DEFAULT;
                                    String[] _ok  = SignLogic.OK_WORDS_DEFAULT;
                                    String[] _fail = SignLogic.FAIL_WORDS_DEFAULT;
                                    Object[] _vd = SignLogic.verdictDetail(ans, _dup, _ok, _fail);
                                    int _v = ((Integer) _vd[0]).intValue();
                                    String _hit = String.valueOf(_vd[1]);
                                    if (_v == SignLogic.V_SIGNED) {
                                        markSigned(fPrefix, fId);
                                        logs("【就地对答】" + fId + " 返回命中「" + _hit + "」→ 计入已签: " + clip(ans, 40));
                                        noteResult(fAccount, true);
                                    } else if (_v == SignLogic.V_FAILED) {
                                        prefs.edit().remove(kLast(fPrefix, fId))
                                             .putInt(kRetry(fPrefix, fId), Math.min(
                                                     prefs.getInt(kRetry(fPrefix, fId), 0) + 1, RETRY_LIMIT))
                                             .putString(kRetryDay(fPrefix, fId), todayStr()).apply();
                                        loge("【就地对答】" + fId + " 返回命中失败词「" + _hit + "」→ 未签成功: " + clip(ans, 40));
                                        noteFailStreak(fAccount, fPrefix, fId, fDialogId, "返回: " + clip(ans, 40));
                                        noteResult(fAccount, false);
                                    } else {
                                        logd("【就地对答】" + fId + " 返回未命中词表，留给回复判定: " + clip(ans, 40));
                                        // 记录"bot 确实回了内容"这一事实（不改判定，只留痕）。
                                        // 用途：状态层据此区分两种「已发出」——
                                        //   ① bot 有响应但词表没认出 → 很可能已签上，值得优先提示"确认已签"
                                        //   ② 完全没响应（查询类/菜单类 bot）→ 本就是常态，别误导用户
                                        try {
                                            if (ans != null && ans.trim().length() > 0) {
                                                prefs.edit().putString(Keys.answered(fPrefix, fId), todayStr()).apply();
                                                // 补写归类（2026-09-28 修）：原来只写 answered 不写归类，
                                                // 界面读不到原因、状态停在「已发出」，用户不知道到底怎么了。
                                                // bot 已经明确回了内容、只是词表没认出 —— 这就是「回复判不出」。
                                                markResultCode(fPrefix, fId, SignLogic.R_REPLIED_UNK);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable _eJV) { noteSwallowed("sendSign(judgeAnswer)", _eJV); }
                                // 只记录，不撤销、不猜成败。
                                // （v1.5.8 中途按返回文本里的 false/fail 等字样撤销过乐观标记，
                                //  但关键词匹配太粗，会把正常回复误判成失败 —— 用户反馈"总判失败"。）
                                //  现在把判断权交给回复判定层，这里只留痕。）
                                logs("已发出 " + dialogId + " " + (KIND_CB.equals(fKind) ? "[回调] " : "text=") + fText
                                     + (ans.length() > 0 ? " · 返回: " + ans : "") + "（结论待回复判定）");
                                // 请求成功 != 签到成功；这里绝不再计入成功统计。
                                // 若 callback answer 本身包含明确结果，上面的就地判定已经记账；否则等待 update。
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
            return SignLogic.SKIP_NONE;   // 真的发出去了 —— 调用方据此提示用户
        } catch (Throwable t) {
            pendingSigns.remove(prefix + id);
            logException("发送签到(did=" + dialogId + ")", t);
            return SignLogic.SKIP_SEND_FAIL;
        }
    }

    // ---------------- 补签 ----------------
    void trySignAll(String reason, boolean force) {
        trySignAllFor(reason, force, currentAccount());
    }

    /** 对指定账号执行一轮补签。从 prefs 直接读该账号条目，支持全账号签到。 */

    /** @return 实际排入发送队列的目标条数（0 = 没有可签的）。调用方据此提示用户。 */
    int trySignAllFor(String reason, boolean force, int account) {
        return trySignAllFor(reason, force, account, false);
    }

    /**
     * @param skipSigned 批量语义：今天已签 / 已发出待结论的目标直接跳过。
     *
     * 为什么单独一个参数而不是改 manual（2026-09-28 用户反馈）：
     *   「立即签到」的批量入口和单目标入口都传 force=true（=manual），
     *   而 manual 会绕过 signedToday 检查 → 已签的被重发一遍。
     *   用户原话「我签过的又给我重复了一遍」。
     *   批量入口该跳（用户要的是"补齐未签的"），单目标入口不该跳
     *   （用户点得这么具体，通常是怀疑没签上，要允许强制重签）。
     */
    int trySignAllFor(String reason, boolean force, int account, boolean skipSigned) {
        ctxAcc = accountLabel(account);
        ctxRound++;
        setCtxTrace("");
        // 账号停用：整账号不参与签到。放在最前，避免逐个目标走一遍闸再逐条打日志。
        // 与 sendSign 的 _gate.accountDisabled 同一判据 —— 那里是**唯一**兜底，
        // 这里是提前收敛（事件补签/定时任务不经过本函数，仍靠 sendSign 兜）。
        if (!isAccountEnabled(account)) {
            logd("[" + reason + "] " + accountLabel(account) + " 已停用，整账号跳过");
            lastRound = accountLabel(account) + "：已停用，跳过";
            return 0;
        }
        // 定时模式下，非手动(force=false)的批量触发一律交给时刻表调度，不直接批量发
        if (TIMER_ENABLED && !force) {
            kickSchedule();
            lastRound = accountLabel(account) + "：定时模式，按当日时刻表逐个签到";
            return 0;
        }
        lastRound = null;
        long now = System.currentTimeMillis();
        if (!force && !"进入窗口".equals(reason) && now - lastTryTime < THROTTLE_MS) {
            logd("[" + reason + "] 节流内跳过");
            return 0;
        }
        if (account == currentAccount()) syncAccount();
        if (!force && !inWindow()) {
            logd("[" + reason + "] 签到窗口外（当前 " + nowHM() + "，窗口 " + WINDOW + "），跳过");
            return 0;
        }
        if (!hasNetwork()) {
            logw("[" + reason + "] 无网络，跳过，网络恢复后自动补");
            return 0;
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
                // 跨天重置重试计数（副作用，保留在筛选阶段）
                String retryDay = prefs.getString(kRetryDay(prefix, id), "");
                int retries = prefs.getInt(kRetry(prefix, id), 0);
                if (retries > 0 && !today.equals(retryDay)) {
                    prefs.edit().putInt(kRetry(prefix, id), 0)
                         .remove(kRetryAt(prefix, id))
                         .remove(kRetryDay(prefix, id)).apply();
                    retries = 0;
                    jlog("[" + reason + "] " + dialogId + " 进入新的一天，重试计数已重置");
                }
                // ── 统一闸（与 sendSign 同一套判据，见 SignLogic.decideSign）──
                SignLogic.SignGate gate = new SignLogic.SignGate();
                gate.manual = force;
                gate.skipSigned = skipSigned;
                gate.signedToday = today.equals(prefs.getString(kLast(prefix, id), ""));
                gate.inFlight = isPendingFresh(prefix, id);
                gate.sentPendingFresh = isSentPendingFresh(prefix, id);
                gate.retryExhausted = retries >= RETRY_LIMIT;
                gate.inBackoff = now < prefs.getLong(kRetryAt(prefix, id), 0L);
                gate.disabled = isBotBlocked(entryDid(m)) || isSnoozed(prefix, id) || isFrozen(prefix, id)
                        || isDailyBlocked(prefix, id) || isPermanentFailedToday(prefix, id);
                // 待确认（bot 未回复 / 判不出）不再自动重发，等用户处置（与 sendSign 同一判据）
                gate.pendingUnconfirmed = isPendingConfirm(prefix, id);
                // 账号级停用：与 sendSign 同一判据（见那里的说明）。
                gate.accountDisabled = !isAccountEnabled(account);
                // 发送次数硬闸：与 sendSign 同一判据（见那里的说明）。
                gate.sendAttemptsExhausted = isSendAttemptsExhausted(prefix, id);
                int skip = SignLogic.decideSign(gate);
                if (skip != SignLogic.SKIP_NONE) {
                    if (skip == SignLogic.SKIP_ALREADY_SIGNED) signed++;
                    else busy++;
                    logd("[" + reason + "] " + dialogId + " 跳过（" + SignLogic.skipLabel(skip) + "）");
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
        // 重构 1.6.1：这一批发送是**逐个 postDelayed**（间隔 3~10 秒），
        // 期间用户可能切号 —— 用捕获的 Ctx 而非执行时读账号。
        final AccountManager.Ctx batchCtx = accountManager.ctxOf(account, "trySignAll/" + reason);
        long acc = 0L;
        for (Map<String, Object> m : todo) {
            final Map<String, Object> fm = m;
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        long did = entryDid(fm);
                        // 先把日志前缀切到本批的账号，再打"尝试签到"。
                        // 否则全账号循环里，i=0 排队的任务执行时 ctxAcc 已被 i=1 改写，
                        // 日志会把"账号1的目标"标成 [账号2]（实测 11:25:56 那轮）。
                        try { ctxAcc = accountLabel(batchCtx.account); } catch (Throwable ignored) {}
                        logd("[" + reason + "] 尝试签到 " + did + " " + (KIND_CB.equals(entryKind(fm)) ? "[回调] " : "text=") + entryText(fm));
                        sendSign(fm, batchCtx, force);
                    } catch (Throwable t) {
                        jlog("trySignAll 发送异常 " + entryDid(fm) + " : " + t);
                    }
                }
            }, acc);
            acc += 3000L + (long) (random.nextInt(7000));
        }
        int fired = todo.size();
        // 2026-10-03：total 改用活跃目标数，与主面板/日志页同一口径。
        // 原先 total = list.size()（含冻结），会拼出"目标 12 · 已签 11"这种
        // 分母对不上的句子（用户截图里那行）。
        int actvN = activeTargetCount(prefix, list);
        String sm = actvN == 0 ? accountLabel(account) + "：还没有签到目标（切到该账号，去 bot 会话点一下按钮或发一次指令）"
                : accountLabel(account) + "：目标 " + actvN + " · 已签 " + signed + " · 待重试 " + busy + " · 本轮发出 " + fired;
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
        return fired;
    }


    /** v1.3.0：一键签全部账号（每个账号独立目标集，各自发各自的） */
    public void signAllAccounts() {
            // 遍历**真实槽位**（accountSlots），不是 0..activatedAccounts()-1：
            // 部分客户端（Nagram 实测）槽位不连续，用连续区间会漏签/错位。
            int[] slots = accountSlots();
            int count = slots.length;
            jlog("=== 全账号签到开始，共 " + count + " 个账号 ===");
            StringBuilder rep = new StringBuilder();
            int seq = 0;
            for (int i : slots) {
                seq++;
                try {
                    if (!isAccountEnabled(i)) {
                        jlog(accountLabel(i) + " 已停用，跳过");
                        if (rep.length() > 0) rep.append('\n');
                        rep.append(accountLabel(i) + "：已停用，跳过");
                        continue;
                    }
                    // skipSigned=true：批量只签未签的（用户反馈：已签的被重发了一遍）
                    trySignAllFor("全账号(" + seq + "/" + count + ")", true, i, true);
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
        // 排队去重（1.6.1）：多个触发源会在同一时间各排一个任务，
        // 随机延迟互相错开后又都过了节流 → 同一轮被签多次（实测一个 bot 连发两条）。
        // 这里只允许一个待执行的排队任务；重复请求直接合并。manual 路径不走本方法。
        synchronized (enqueueLock) {
            long nowE = System.currentTimeMillis();
            if (enqueueAt > 0L && nowE - enqueueAt < 90L * 1000L) {
                boolean logIt;
                synchronized (queueLogAt) {
                    Long last = queueLogAt.get(reason);
                    logIt = (last == null || nowE - last.longValue() >= 60_000L);
                    if (logIt) queueLogAt.put(reason, Long.valueOf(nowE));
                }
                if (logIt) {
                    logd("[排队] " + reason + " 合并到已有排队任务（" + ((nowE - enqueueAt) / 1000L) + " 秒前已排）");
                }
                return;
            }
            enqueueAt = nowE;
        }
        long delay = 0L;
        if (inWindow() && windowRange() != null) {
            delay = (long) (Math.random() * 3L * 60L * 1000L);
        }
        final String r = reason;
        mainHandler.postDelayed(() -> { try { trySignAll(r, false); } catch (Throwable t) { jlog("[" + r + "] 异常: " + t); } }, delay);
    }

    /** 按账号的排队去重时间戳（心跳为每个账号各排一次时用）。 */
    private final java.util.Map<Integer, Long> enqueueAtByAcc = new java.util.concurrent.ConcurrentHashMap<Integer, Long>();

    /** 该账号在 90 秒内是否已经排过；返回 true 表示"本次可以排"。 */
    private boolean markEnqueueFor(int acc) {
        long nowE = System.currentTimeMillis();
        Long prev = enqueueAtByAcc.get(acc);
        if (prev != null && nowE - prev.longValue() < 90L * 1000L) {
            logd("[排队] 账号" + (acc + 1) + " 已有待执行任务，合并");
            return false;
        }
        enqueueAtByAcc.put(acc, nowE);
        return true;
    }

    private final Object enqueueLock = new Object();

    // ── 排队合并日志限频（2026-10-01）──
    // 「[排队] xxx 合并到已有排队任务」在 90 秒合并窗口内每次触发都写，
    // 实测单日 229 条、占日志 36%。改为同一 reason 60 秒内只记一条：
    // 仍能看出哪些触发源在反复触发，但不再逐次刷屏。
    private final java.util.Map<String, Long> queueLogAt = new java.util.HashMap<String, Long>();
    private volatile long enqueueAt = 0L;

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
                // ── 日志降噪（2026-09-30）──
                // 起因：实测用户日志被同一条原因刷屏 30+ 行 ——
                //   11 个目标 × 最多 5 次退避 = 55 行「取不到这个 bot 的会话数据」，
                //   把真正有用的信息淹没，用户以为模块坏了。
                // 做法：同一「目标 + 原因」当天只记前 2 次，之后只累计不落日志；
                //   达到上限时才再记一条总结，保留排障所需的全部关键事实。
                final String dkey = "softfail_n_" + prefix + "_" + id;
                int seen = prefs.getInt(dkey, 0);
                if (seen > 0 && !todayStr().equals(prefs.getString(dkey + "_d", ""))) seen = 0;

                if (cur >= RETRY_LIMIT) {   // 已达上限：不再排重试，明确标注今日放弃
                    prefs.edit().putLong(kRetryAt(prefix, id), 0L).apply();
                    logw(why + "：" + targetTitle(entryDid(entry)) + " · " + accountLabel(account)
                            + " · 今日已试 " + cur + " 次达上限，今日不再重试"
                            + (seen > 2 ? "（同类记录已折叠 " + (seen - 2) + " 条）" : ""));
                    return;
                }
                long wait = backoffDelay(cur);
                prefs.edit().putInt(kRetry(prefix, id), cur + 1)
                        .putString(kRetryDay(prefix, id), todayStr())
                        .putLong(kRetryAt(prefix, id), System.currentTimeMillis() + wait)
                        .apply();
                prefs.edit().putInt(dkey, seen + 1).putString(dkey + "_d", todayStr()).apply();
                if (seen < 2) {
                    logw(why + "：" + targetTitle(entryDid(entry)) + " · " + accountLabel(account)
                            + " · 第 " + (cur + 1) + "/" + RETRY_LIMIT + " 次，" + (wait / 60000L) + " 分钟后再试");
                } else {
                    logd("（已折叠）" + why + " · " + targetTitle(entryDid(entry))
                            + " · 第 " + (cur + 1) + "/" + RETRY_LIMIT + " 次");
                }
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
        if (SHOT_MASK && !SHOT_RAW) return shotAlias(did, false);
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
        if (SHOT_MASK && !SHOT_RAW) return shotAlias(did, true);
        if (did < 0) return String.valueOf(did); // 群直接显示 ID
        String u = botUsername(did);
        if (u != null && u.length() > 0) return "@" + u;
        return "id " + did;
    }

    private String statusOf(String prefix, String id, String today) {
        String lastSign = prefs.getString(kLast(prefix, id), "");
        if (today.equals(lastSign)) {
            // ── 文案统一（2026-09-30）──
            // 历史轨迹：
            //   早期文本指令走"发出即已签"，与按钮目标性质不同，故单独标注「指令已发 ✓」；
            //   2026-09-28 修复后发现文本指令**有**回复判定通道（见 4110 行附近的实测记录），
            //     与按钮目标流程完全一致：收到回复 → 判定 → 成功才写 last_，超时归「bot 未回复」。
            //   即：能进入本分支（lastSign == today）就说明**确实判定成功了**。
            // 结论：再区分「已发送」已无意义 —— 那会让用户以为"只是发出去了、没签上"，
            //       而事实是已经签上了。统一显示「已签」，判定依据的差异属于实现细节，
            //       不应外泄到状态文案里让用户困惑。
            return Lang.tr("已签");
        }
        // ── 归类驱动（2026-09-28）──
        // 改前这里只有一个含糊的「待确认」，不区分"按钮失效 / 回复判不出 / bot 没回"，
        // 用户看到「待确认」只知道有事，不知道该干嘛；而且这个词还被网络学习候选池占用。
        // 现在按归类给出**明确文案**，用户一眼知道发生了什么。
        int _rc = resultCodeOf(prefix, id);
        if (_rc == SignLogic.R_BTN_STALE)   return Lang.tr("按钮失效");
        if (_rc == SignLogic.R_REPLIED_UNK) return Lang.tr("回复判不出");
        if (_rc == SignLogic.R_NO_REPLY)    return Lang.tr("bot 未回复");
        if (_rc == SignLogic.R_JUDGE_OFF)   return Lang.tr("判定已关");
        if (_rc == SignLogic.R_EXHAUSTED)   return Lang.tr("今日已用尽");
        if (isPendingConfirm(prefix, id)) return Lang.tr("结果未知");
        /* 跨天重置（2026-09-30）：昨天撞上限的，今天不算"已放弃" */
        String _rd = prefs.getString(kRetryDay(prefix, id), "");
        int retries = today.equals(_rd) ? prefs.getInt(kRetry(prefix, id), 0) : 0;
        if (retries >= RETRY_LIMIT) return Lang.tr("已放弃");
        long retryAt = prefs.getLong(kRetryAt(prefix, id), 0);
        if (System.currentTimeMillis() < retryAt) return Lang.tr("退避中");
        // 「已发出、正在等结果」：以前没有这个状态，请求发出后仍显示"待签" ——
        // 用户以为没签上会反复点（被闸拦住又没有反馈）。如实区分"已发/未发"。
        if (isSentPendingFresh(prefix, id) || isPendingFresh(prefix, id)) return Lang.tr("已发出");
        // 发出已久、sent_at_ 已过期但仍无结论：不能再显示「待签」——
        // 那会让用户以为压根没发过，且会被巡检重新排期重发（用户实测"签了又变回没签"）。
        // 若命中这条，就地转成「待确认」并如实显示，让用户有处置入口。
        if (sentButStale(prefix, id)) {
            promoteSilentToPending(prefix, id, "ui-refresh");
            return Lang.tr("结果未知");
        }
        if (retries > 0) return Lang.tr("重试中");
        return Lang.tr("待签");
    }

    // 状态优先级：待处理(待签/重试/退避)排前，已签/已放弃沉底。数值越小越靠前。
    private int statusRank(String status) {
        if (status == null) return 0;
        if (status.contains("已签") || status.contains("放弃")) return 2;
        if (status.contains("结果未知") || status.contains("待确认")) return 1;
        if (status.contains("已发送") || status.contains("指令已发")) return 2;   // 与"已签"同级（今天已做过）
        if (status.contains("已发出")) return 0;   // 进行中，与"重试中"同属待办区
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

    /**
     * 目标列表的过滤判定（2026-10-03）。**纯渲染层**，不碰签到逻辑。
     *
     * @param st 已算好的状态串（复用 statusOf，避免重复计算）
     */
    private boolean listFilterMatch(Map<String, Object> m, String st, String today) {
        try {
            String id = entryId(m);
            String pfx = accountPrefix();
            String q = logTargetFilter == null ? "" : logTargetFilter.trim().toLowerCase(java.util.Locale.US);
            if (q.length() > 0) {
                String title = String.valueOf(entryTitle(m)).toLowerCase(java.util.Locale.US);
                String cmd = String.valueOf(entryText(m)).toLowerCase(java.util.Locale.US);
                String sub = "";
                try { sub = String.valueOf(targetSubtitle(entryDid(m))).toLowerCase(java.util.Locale.US); } catch (Throwable ignored) {}
                if (!title.contains(q) && !cmd.contains(q) && !sub.contains(q)) return false;
            }
            String f = listFilter == null ? "全部" : listFilter;
            if ("全部".equals(f)) return true;
            boolean signed = today.equals(prefs.getString(kLast(pfx, id), ""));
            boolean frozen = isFrozen(pfx, id) || isBotBlocked(entryDid(m));
            if ("冻结".equals(f)) return frozen;
            if (frozen) return false;                   // 冻结的不混进其他分组
            if ("已签".equals(f)) return signed;
            if ("待签".equals(f)) return !signed && (st == null || st.contains("待签") || st.contains("已发送"));
            if ("需处理".equals(f)) {
                return st != null && (st.contains("按钮失效") || st.contains("回复判不出")
                        || st.contains("结果未知") || st.contains("未回复") || st.contains("失败"));
            }
            return true;
        } catch (Throwable t) { return true; }
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
        } else if ("fails".equals(SORT_MODE)) {
            // 最近失败优先（2026-10-03）：失败次数多的排前面。
            // 用 failStreak_（连续失败天数）而不是当日 retry，因为"连续几天失败"
            // 才是真正的信号 —— 单次失败多半是抖动，连续失败才需要处理。
            java.util.Collections.sort(l, new java.util.Comparator<Map<String, Object>>() {
                @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                    int ia = isInactive(accountPrefix(), a) ? 1 : 0;
                    int ib = isInactive(accountPrefix(), b) ? 1 : 0;
                    if (ia != ib) return ia - ib;
                    int fa = 0, fb = 0;
                    try { fa = prefs.getInt(Keys.failStreak(accountPrefix(), entryId(a)), 0); } catch (Throwable ignored) {}
                    try { fb = prefs.getInt(Keys.failStreak(accountPrefix(), entryId(b)), 0); } catch (Throwable ignored) {}
                    if (fa != fb) return fb - fa;          // 失败多的在前
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
    /** 重染排序 chip（原地刷新用）。顺序与 SORT_MODES 一致。 */
    private void refreshSortChips(Context c) {
        try {
            String[] MODES = {"unsigned", "name", "fails"};
            for (int i = 0; i < listSortChips.size() && i < MODES.length; i++) {
                styleSortChip(listSortChips.get(i), c, MODES[i].equals(SORT_MODE));
            }
        } catch (Throwable t) { noteSwallowed("refreshSortChips", t); }
    }

    private void styleSortChip(TextView chip, Context c, boolean on) {
        try {
            chip.setTextColor(on ? Theme.termTxt(c) : Theme.termMuted(c));
            chip.setBackground(termBorder(c,
                    on ? Theme.withAlpha(Theme.termCyan(c), 0x1E) : Theme.withAlpha(Theme.termMuted(c), 0x0D),
                    on ? Theme.withAlpha(Theme.termCyan(c), 0x66) : Theme.withAlpha(Theme.termMuted(c), 0x33)));
        } catch (Throwable t) { noteSwallowed("styleSortChip", t); }
    }

    private View sortChip(Activity act, String label, String mode) {
        boolean on = mode.equals(SORT_MODE);
        TextView chip = new TextView(act);
        chip.setText(Lang.tr(label));
        chip.setTextSize(Theme.TS_CAPTION);
        chip.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        styleSortChip(chip, act, on);
        if (!listSortChips.contains(chip)) listSortChips.add(chip);
        chip.setClickable(true);
        chip.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
            // 同样原地刷新（见筛选 chip 的说明）：切排序也不该重建窗口。
            if (mode.equals(SORT_MODE)) return;
            SORT_MODE = mode;
            try { prefs.edit().putString("jmb_sort", mode).apply(); } catch (Throwable ignored) {}
            refreshSortChips(act);
            fillTargetRows(act);
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

    /** 条目类型图标：用 Canvas 绘制矢量回调箭头/文本图标，替代之前的圆形文字（2026-09-29） */
    private TextView typeChip(Context c, boolean cb) {
        TextView chip = new TextView(c);
        chip.setTextSize(Theme.TS_CAPTION);
        chip.setText(cb ? Lang.tr("回调") : Lang.tr("指令"));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(Theme.dp(c, 6), Theme.dp(c, 2), Theme.dp(c, 6), Theme.dp(c, 2));
        int tcol = cb ? Theme.termCyan(c) : Theme.termMuted(c);
        chip.setTextColor(tcol);
        chip.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        /* 左侧矢量图标 */
        android.graphics.drawable.Drawable ic = cb
            ? callbackVectorIcon(c, tcol, Theme.dp(c, 11))
            : textCmdVectorIcon(c, tcol, Theme.dp(c, 14));
        if (ic != null) {
            int sz = Theme.dp(c, 11);
            ic.setBounds(0, 0, sz, sz);
            chip.setCompoundDrawables(ic, null, null, null);
            chip.setCompoundDrawablePadding(Theme.dp(c, 3));
        }
        chip.setBackground(termBorder(c,
            Theme.withAlpha(tcol, 0x12),
            Theme.withAlpha(tcol, 0x59)));
        return chip;
    }

    /** 回调按钮矢量图标：圆角矩形内折返箭头 */
    private android.graphics.drawable.Drawable callbackVectorIcon(Context c, int col, int szPx) {
        try {
            float s = szPx;
            float r = s * 0.22f;
            float sw = s * 0.12f;
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            p.setStrokeWidth(sw);
            p.setColor(col);
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap((int) s, (int) s, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bm);
            /* 回旋箭头：左下 → 左上 → 右上 */
            android.graphics.Path path = new android.graphics.Path();
            float cx = s * 0.5f, cy = s * 0.5f;
            float a = s * 0.25f;
            path.moveTo(cx - a, cy + a * 0.6f);
            path.lineTo(cx - a, cy - a * 0.4f);
            path.lineTo(cx + a, cy - a);
            cv.drawPath(path, p);
            /* 箭头尖 */
            float aw = sw * 1.6f;
            p.setStrokeWidth(aw);
            path.reset();
            path.moveTo(cx + a, cy - a);
            path.lineTo(cx + a - aw, cy - a + aw);
            path.moveTo(cx + a, cy - a);
            path.lineTo(cx + a - aw * 0.3f, cy - a + aw * 1.4f);
            cv.drawPath(path, p);
            return new android.graphics.drawable.BitmapDrawable(c.getResources(), bm);
        } catch (Throwable t) { return null; }
    }

    /** 文本指令矢量图标：终端提示符 >_ */
    private android.graphics.drawable.Drawable textCmdVectorIcon(Context c, int col, int szPx) {
        try {
            float s = szPx;
            float sw = s * 0.12f;
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(sw);
            p.setColor(col);
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap((int) s, (int) s, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bm);
            float ox = s * 0.18f, oy = s * 0.5f;
            /* > */
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(ox, oy - s * 0.15f);
            path.lineTo(ox + s * 0.18f, oy);
            path.lineTo(ox, oy + s * 0.15f);
            cv.drawPath(path, p);
            /* _ */
            path.reset();
            path.moveTo(ox + s * 0.25f, oy + s * 0.12f);
            path.lineTo(ox + s * 0.55f, oy + s * 0.12f);
            cv.drawPath(path, p);
            return new android.graphics.drawable.BitmapDrawable(c.getResources(), bm);
        } catch (Throwable t) { return null; }
    }

    private View targetRow(LinearLayout parent, Map<String, Object> entry, String status, String action) {
        Context c = parent.getContext();
        long did = entryDid(entry);
        String id = entryId(entry);
        boolean cb = KIND_CB.equals(entryKind(entry));
        String text = entryText(entry);
        // ── 左侧色条：按**状态**上色（2026-10-03 重做）──
        // 改前：色条只区分"群=品红 / bot=青"，再叠一个"已签=绿"。
        //   实测问题：满屏"待签"时所有行都是同一条青色线，扫不出问题条目；
        //   而"失败/需处理"这些真正要你动手的，反而没有颜色。
        // 现在：颜色 = 状态语义，一眼能扫出哪里不正常。
        //   绿 已签 · 青 待签 · 琥珀 需处理/失败 · 灰 冻结/排除 · 品红 群(/频道)
        //   色调之外还保留"群"的区分：群用偏品红的绿/青（见 chatTint）
        String[] etype = entryTypeOf(entry);
        boolean isChat = !"bot".equals(etype[0]);
        final boolean _isSignedRow = todayStr().equals(prefs.getString(kLast(accountPrefix(), id), ""));
        int accent = stateAccent(c, status, _isSignedRow, isChat);
        final int accentFinal = accent;
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
        row.setBackground(termBorder(c, Theme.termCard(c), Theme.withAlpha(_isSignedRow ? Theme.termGreen(c) : accent, 0x2E)));
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
        // ── 类型 chip：只在**群 \/ 频道**时显示（2026-10-03）──
        // 改前每行都挂 peerChip + typeChip 两个标签，而绝大多数是 bot ——
        // 一行里两个 chip 都在说同一件事（"这是个 bot"），纯占地方。
        // 现在：群 \/ 频道才显类型（那才是需要额外提示的情况），
        //       省出的空间留给右侧的相对时间 \/ 失败次数。
        if (isChat) {
            tl.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,6), 1));
            tl.addView(peerChip(c, entry));
        }
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
        if (status != null && status.contains("已发送")) stCol = Theme.termGreen(c);  // 2026-09-29：文本指令发出即等效签到，绿色与「已签」一致
        else if (status != null && status.contains("已签")) stCol = Theme.termGreen(c);
        else if (status != null && (status.contains("按钮失效") || status.contains("回复判不出"))) stCol = Theme.termAmber(c);
        else if (status != null && (status.contains("结果未知") || status.contains("退避") || status.contains("重试"))) stCol = Theme.termAmber(c);
        else if (status != null && (status.contains("bot 未回复") || status.contains("判定已关") || status.contains("放弃"))) stCol = Theme.termMuted(c);
        st.setTextColor(stCol);
        st.setText(Lang.tr(status));
        // ── 状态前导图标（2026-09-30 重做）──
        // 语义分工：
        //   回调按钮签到 = 实心闪电，做「能量脉冲」——已签是瞬时完成的动作
        //   文本指令签到 = 进度环，从 0 扫到满——指令发出后 bot 结论不可知，
        //                  这个状态本质是「进行中」，转动填充最贴切
        //   其余状态沿用原有静态图标
        boolean _cbKind = false;
        try { _cbKind = entry != null && KIND_CB.equals(entryKind(entry)); } catch (Throwable _eK) {}
        final boolean isCallbackKind = _cbKind;

        final boolean stDone = status != null
                && (status.contains("已签") || status.contains("已发送") || status.contains("指令已发"));
        String stIcon = null;
        if (status != null) {
            if (stDone) stIcon = null;                       // 完成态走下面专门的绘制
            else if (status.contains("按钮失效") || status.contains("回复判不出")
                     || status.contains("结果未知")) stIcon = "warn";
            else if (status.contains("bot 未回复") || status.contains("判定已关")) stIcon = "info";
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

        /* 完成态动态图标：冻结/排除行跳过（已双重虚化，动画纯属干扰） */
        boolean _frozenRow = false;
        try { _frozenRow = isFrozen(accountPrefix(), id) || isBotBlocked(did); } catch (Throwable _eFr) {}

        if (!_frozenRow && stDone) {
            final int ISZ = 14;                              // 略大，动效才看得清
            int ssz = Theme.dp(c, ISZ);
            final int baseCol = stCol;
            final int hotCol  = 0xFFFFFFFF;                  // 白热峰值

            if (isCallbackKind) {
                // ══ 回调：闪电 · 能量爆发 ══
                // 三层同时动作，制造"放电炸开"的观感：
                //   ① 闪电本体白热化：绿 → 白 → 绿
                //   ② 本体缩放：0.82 → 1.22，快起慢落
                //   ③ 冲击波：一个环从中心炸开并淡出（用 RippleDrawable 的一环表达）
                final android.graphics.drawable.Drawable bolt = Icons.d(c, "boltfill", ISZ, baseCol, 1.0f);
                final Icons.RippleDrawable wave = Icons.ripple(c, ISZ + 6, hotCol, 1.1f);
                if (bolt != null) {
                    bolt.setBounds(0, 0, ssz, ssz);
                    st.setCompoundDrawables(bolt, null, null, null);
                    st.setCompoundDrawablePadding(Theme.dp(c, 5));

                    android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    va.setDuration(1000);
                    va.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    va.setRepeatMode(android.animation.ValueAnimator.RESTART);
                    va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
                    va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                            float f = (Float) a.getAnimatedValue();

                            // ── ① 白热化：快速冲到白，再缓慢退回本色 ──
                            float heat = f < 0.22f
                                    ? (f / 0.22f)                       // 0 → 1，猛冲
                                    : (1f - (f - 0.22f) / 0.78f);       // 1 → 0，缓退
                            if (heat < 0f) heat = 0f;
                            int col = blendArgb(baseCol, hotCol, heat);
                            bolt.setColorFilter(new android.graphics.PorterDuffColorFilter(
                                    col, android.graphics.PorterDuff.Mode.SRC_IN));

                            // ── ② 缩放：炸开瞬间最大 ──
                            float k = 0.86f + 0.40f * heat;
                            int hw = (int) (ssz * k / 2f);
                            int cx = ssz / 2, cy = ssz / 2;
                            bolt.setBounds(cx - hw, cy - hw, cx + hw, cy + hw);
                            bolt.setAlpha(255);

                            // ── ③ 冲击波 ──
                            if (wave != null) {
                                wave.setProgress(f);
                                int wsz = Theme.dp(c, ISZ + 6);
                                wave.setBounds(0, 0, wsz, wsz);
                            }
                        }
                    });
                    va.start();
                }
            } else {
                // ══ 指令：环形涟漪（信号发出 → 收回）══
                // 不用转圈：匀速扫满 360° 是机械的进度条语义。
                // 涟漪更贴"指令发出、bot 回应"这个一来一回的过程。
                final Icons.RippleDrawable rp = Icons.ripple(c, ISZ, baseCol, 1.3f);
                if (rp != null) {
                    rp.setBounds(0, 0, ssz, ssz);
                    st.setCompoundDrawables(rp, null, null, null);
                    st.setCompoundDrawablePadding(Theme.dp(c, 5));

                    android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    va.setDuration(1900);                     // 比原先的转圈慢得多
                    va.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    va.setRepeatMode(android.animation.ValueAnimator.RESTART);
                    va.setInterpolator(new android.view.animation.LinearInterpolator());
                    va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                            rp.setProgress((Float) a.getAnimatedValue());
                        }
                    });
                    va.start();
                }
            }
        }
        t2r.addView(st, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        t2r.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(Theme.dp(c,8), 1));
        // ── 目标行第二行：状态 + **本条目是哪一条**（2026-09-28）──
        // 事实：同一个 bot 下可以挂多个目标（如 🔙返回 / 📊媒体库统计 / 付费查询），
        // 而第一行标题取的是 targetTitle(did)，全都显示同一个 bot 名 ——
        // 用户看到几行长得一样，点一个"像"是点了两个，无从分辨。
        // 这里把条目自身的内容（按钮文案 / 指令原文）显式标出来，让每行可辨识。
        TextView tx = new TextView(c); tx.setTextSize(Theme.TS_CAPTION); tx.setTextColor(Theme.termMuted(c)); tx.setTypeface(android.graphics.Typeface.MONOSPACE);
        tx.setSingleLine(true); tx.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tx.setText((cb ? "🔘 " : "⌨ ") + text);
        t2r.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // ── 右侧：相对时间 + 失败次数（2026-10-03 重做）──
        // 改前直接显示 last_ 的原文（"2026-10-03"）—— 用户看不出"这是哪天"，
        // 得自己在脑子里跟今天做减法，而且失败的目标完全看不到失败次数。
        // 现在：有失败优先说失败（那才是要你关注的），否则说"上次签到 N 天前"。
        String lastT = prefs.getString(kLast(accountPrefix(), id), "");
        {
            int failN = 0;
            try { failN = prefs.getInt(Keys.failStreak(accountPrefix(), id), 0); } catch (Throwable ignored) {}
            String tail = null;
            int tailCol = Theme.termFaint(c);
            if (failN >= 2) {
                tail = Lang.tf("失败 {0} 次", failN);
                tailCol = Theme.termAmber(c);
            } else if (lastT.length() > 0) {
                tail = Lang.tf("上次 {0}", relDayLabel(lastT));
            }
            if (tail != null) {
                TextView lt = new TextView(c);
                lt.setTextSize(Theme.TS_CAPTION);
                lt.setTextColor(tailCol);
                lt.setTypeface(Theme.text());
                lt.setText(tail);
                lt.setPadding(Theme.dp(c,6), 0, 0, 0);
                t2r.addView(lt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        col.addView(t2r);
        // ── 内联处置条已移除（2026-09-28）──
        // 原来这里在每行下方横排三个按钮（确认已签/重试/忽略今天）。
        // 两个问题：
        //   ① 破坏一致性 —— 其他所有操作都在「点击行 → 菜单」里，只有它例外；
        //   ② 假设用户此刻要处理它，但用户可能只是路过看一眼列表。
        // 现在改为：列表保持干净的两行结构 → 顶部聚合条 → 点进去集中处理。
        // 处置逻辑见 showPendingWork()。
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

    /**
     * 把 yyyy-MM-dd 说成人话：今天/昨天/N 天前（2026-10-03）。
     *
     * 为什么不用绝对日期：列表右侧就一小块地方，"2026-09-28" 需要用户
     * 自己在脑子里跟今天做减法；而"5 天前"直接就是结论。
     * 超过 30 天不再数天数（"47 天前"没意义），退化成月份。
     */
    private String relDayLabel(String ymd) {
        try {
            if (ymd == null || ymd.length() != 10) return ymd == null ? "" : ymd;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date d = f.parse(ymd);
            if (d == null) return ymd;
            long diff = System.currentTimeMillis() - d.getTime();
            long days = diff / 86400000L;
            if (days <= 0) return Lang.tr("今天");
            if (days == 1) return Lang.tr("昨天");
            if (days < 30) return Lang.tf("{0} 天前", days);
            return new java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).format(d);
        } catch (Throwable t) { return ymd; }
    }

    /**
     * 目标行左侧色条的颜色 = 状态语义（2026-10-03）。
     *
     * 设计取舍：
     *   · 已签 → 绿（唯一"完成"的正面色）
     *   · 需处理（失败\/按钮失效\/判不出\/待确认）→ 琥珀（要你动手）
     *   · 待签 → 青（正常、会自己完成，不需要你看）
     *   · 冻结\/排除 → 灰（不参与）
     *   · 群/频道 → 在青绿基础上偏品红一档，保留"这是群"的辨识
     *     （用 Theme.mix 往品红方向拉 35%，既有状态色又有类型区分）
     *
     * 用 String status 匹配而非引入新状态码：status 已经是这一层的真相源
     * （由 statusOf() 统一产出），再加一层映射会多一处要同步的地方。
     */
    private int stateAccent(Context c, String status, boolean signed, boolean isChat) {
        int col;
        // ── 冻结/排除必须最先判（2026-10-03 修）──
        // 原来 signed 排在最前，而"冻结"的目标昨天可能已签过（last_ 仍是今天），
        // 于是冻结行照样显示**绿条** —— 用户截图问"看不出灰色"就是这个原因。
        // 冻结/排除的目标已经"不参与"，它的颜色就该是"不参与"的灰，
        // 优先于任何"今天签没签"的结果。
        if (status != null && (status.contains("冻结") || status.contains("排除")
                || status.contains("已暂停"))) {
            col = Theme.termMuted(c);
        } else if (signed) {
            col = Theme.termGreen(c);
        } else if (status != null && (status.contains("按钮失效") || status.contains("回复判不出")
                || status.contains("结果未知") || status.contains("未回复")
                || status.contains("失败") || status.contains("放弃"))) {
            col = Theme.termAmber(c);
        } else if (status != null && status.contains("判定已关")) {
            col = Theme.termMuted(c);
        } else {
            col = Theme.termCyan(c);
        }
        // 群（频道）：往品红方向拉一点，保留"这是群"的辨识度
        if (isChat) col = Theme.mix(col, Theme.termPink(c), 0.35f);
        return col;
    }

    /** 目标列表的状态筛选（2026-10-03）：全部 / 待签 / 已签 / 需处理 / 冻结。 */
    private String listFilter = "全部";
    /** 目标列表当前的行容器（供筛选/排序原地刷新，不重建对话框）。 */
    private LinearLayout listRowsHost;
    /** 目标列表当前筛选 chip 组（供原地重染色）。 */
    private final java.util.List<TextView> listFilterChips = new ArrayList<TextView>();
    /** 目标列表当前排序 chip 组（供原地重染色）。 */
    private final java.util.List<TextView> listSortChips = new ArrayList<TextView>();
    /** 目标列表的搜索词（匹配标题、指令、@用户名）。 */
    private String logTargetFilter = "";

    private static long lastMainOpen = 0L;
    private boolean fastMainOpen = false;
    private volatile boolean inSendReq = false;
    private volatile long bootReadyAt = 0L;
    /** 「未就绪被跳过」的日志限频时间戳（避免刷屏，又不至于完全无感）。 */
    private volatile long lastReadySkipLogAt = 0L;

    private boolean notReadyYet() {
        if (System.currentTimeMillis() >= bootReadyAt) return false;
        // 动态提前放行：客户端已经真的能用了就不必干等剩余秒数。
        // 判据 = 当前账号的 MessagesController 拿得到（说明 TG 初始化完成）。
        try {
            if (getMessagesController(currentAccount()) != null) return false;
        } catch (Throwable _eNR) { noteSwallowed("notReadyYet", _eNR); }
        return true;
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
        fastMainOpen = SHOT_MODE || (now0 - lastMainOpen < 15000L);
        lastMainOpen = now0;
        // 标题动效：保持原有"每次打开换一种"的轮换（维持新鲜感）。
        // 真正的节流是上面那行 —— 15 秒内反复打开（切换页面/返回）不重播，
        // 直接静态显示，避免来回进出时反复闪。
        // 2026-10-03：曾试过"一天只播一次"，会让面板显得太死，已回退。
        TITLE_FX = (TITLE_FX + 1) % 4;
        try { prefs.edit().putInt("jmb_fx", TITLE_FX).apply(); } catch (Throwable ignored) {}

        syncAccount();

        if (act == null) { toast("请在 Telegram 界面使用 /jmb"); return; }
        // 账号实况（每次打开 /jmb 都记一条，用 logw 保证不被采样丢）：
        // 多账号串号的排障唯一可靠依据 ——
        // 用户实测反馈"切到账号3却读到 acc1"，必须能一眼看到原始值与解析结果。
        try {
            int _acc = currentAccount();
            StringBuilder _sb = new StringBuilder();
            _sb.append("账号实况: selectedAccount=").append(lastRawAccount)
               .append("  解析=").append(accountLabel(_acc)).append("(acc").append(_acc).append("_)")
               .append("  已登录=").append(activatedAccounts());
            for (int i : accountSlots()) {
                int _c = 0;
                try {
                    java.util.List<Map<String, Object>> _l = new ArrayList<Map<String, Object>>();
                    loadTargetsInto(accountPrefix(i), _l);
                    _c = _l.size();
                } catch (Throwable ignored) {}
                _sb.append("  acc").append(i).append("目标=").append(_c);
            }
            // ── 正常静默，异常才告警（2026-09-30 方案 A）──
            // 改前：每次打开 /jmb 都 logw 一条，于是「黄色警告」满天飞，
            //   用户看到 299 条警告以为模块坏了，真正的问题反而被淹没。
            // 现在只在「内部索引与解析结果对不上」时才用警告级 ——
            //   黄色重新等于「这里有事需要你留意」。
            //   · 索引与解析不一致（串号征兆）→ 警告，必须让用户看到
            //   · 一切正常 → 调试级，只在详细档可见，不打扰
            boolean _mismatch = (lastRawAccount >= 0 && lastRawAccount != _acc);
            if (_mismatch) {
                logw("[账号异常] 内部索引=" + lastRawAccount + " 但解析为 "
                        + accountLabel(_acc) + "（acc" + _acc + "_），若签到落到错误账号请反馈本条");
                logw(_sb.toString());
            } else {
                logd(_sb.toString());
            }
        } catch (Throwable _eAcc) { noteSwallowed("showMainMenu(accInfo)", _eAcc); }
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
        // ── 标题动效（2026-10-03 重做：从"花哨"改成"精致"）──
        // 原版四个特效的共同毛病：
        //   · 幅度过大 —— 波浪上下 3.2dp，23sp 的字看着在抖，不是"流动"是"不稳"；
        //   · 随机驱动 —— 敲击用 Random 挑字符，观感杂乱无节奏；
        //   · 硬色环 —— 流光在四色间硬切，像走马灯，跟终端风的克制感冲突；
        //   · 逐帧全量 setTextColor —— 11 个字 × 25fps，其中大量帧颜色其实没变。
        //
        // 现在四个各自重做，统一原则：**小幅度、确定性、平滑插值、跳过无变化的帧**。
        //   · 颜色量化到 1/24 步，与上一帧相同就不调 setTextColor（省掉无效重绘）；
        //   · 位移全部 ≤2dp，且用平滑曲线，不再用生硬的线性往返。
        // 「今天全签完了吗」——呼吸动效的判据（签完即静止）
        boolean _staTmp = false;
        try { _staTmp = isAllSignedToday(); } catch (Throwable ignored) {}
        final boolean signedTodayAll = _staTmp;

        final int nch = chs.size();
        final int[] lastCol = new int[nch];
        for (int i = 0; i < nch; i++) lastCol[i] = Integer.MIN_VALUE;
        final float invN = nch > 1 ? 1f / (nch - 1) : 0f;

        // 只在该字符颜色确实变了才写（量化到 1/24，肉眼无感但能省一大半重绘）
        final java.util.concurrent.atomic.AtomicInteger _dummy = new java.util.concurrent.atomic.AtomicInteger(0);
        final Runnable[] _setCol = new Runnable[1];

        if (fxKind == 1) {
            // 1 · 波浪：正弦起伏，**幅度减半**（3.2dp → 1.6dp）、颜色随波峰偏绿。
            //     关键改动是位移曲线用 sin 的平滑段，不再让字"跳"。
            flow = new Runnable() {
                float f = 0f;
                @Override public void run() {
                    try {
                        if (!floatRow.isShown()) { th.removeCallbacks(this); return; }
                        for (int i = 0; i < nch; i++) {
                            float ph = (float)(f * 0.085f + i * 0.42f);
                            float sn = (float)Math.sin(ph);
                            float q = Math.round((sn + 1f) / 2f * 24f) / 24f;   // 量化
                            chs.get(i).setTranslationY(sn * dens * 1.6f);
                            int col = Theme.mix(greenC, cyanC, q);
                            if (col != lastCol[i]) { chs.get(i).setTextColor(col); lastCol[i] = col; }
                        }
                        f += 1f;
                        th.postDelayed(this, 40L);
                    } catch (Throwable ignored) {}
                }
            };
        } else if (fxKind == 2) {
            // 2 · 流光：改成**一道柔光扫过**（原来的四色硬切太花）。
            //     高光带从左到右穿过标题，经过的字提亮到近白，过了就回到青。
            //     高斯型衰减（1-d/0.36 的平方）比线性更像真实反光。
            flow = new Runnable() {
                float f = 0f;
                @Override public void run() {
                    try {
                        if (!floatRow.isShown()) { th.removeCallbacks(this); return; }
                        float ph = (f % 105f) / 105f;          // 周期 ~4.2s
                        float hl = ph * 1.7f - 0.35f;          // 高光位置（允许进出画面）
                        for (int i = 0; i < nch; i++) {
                            float pos = i * invN;
                            float d = Math.abs(pos - hl);
                            float g0 = d >= 0.36f ? 0f : (1f - d / 0.36f);
                            float glow = g0 * g0;              // 平方衰减：中心亮、边缘柔
                            float q = Math.round(glow * 24f) / 24f;
                            int col = Theme.mix(cyanC, flashCol, q * 0.9f);
                            if (col != lastCol[i]) { chs.get(i).setTextColor(col); lastCol[i] = col; }
                        }
                        f += 1f;
                        th.postDelayed(this, 40L);
                    } catch (Throwable ignored) {}
                }
            };
        } else if (fxKind == 3) {
            // 3 · 涟漪：改成**从左到右有序掠过**（原来是 Random 乱敲，没节奏）。
            //     一次只点亮一个字，抬起 1.4dp 并变白，扫完一轮从头再来。
            flow = new Runnable() {
                int f = 0;
                @Override public void run() {
                    try {
                        if (!floatRow.isShown()) { th.removeCallbacks(this); return; }
                        int step = 5;                           // 每字停留 5 帧
                        int idx = (f / step) % nch;
                        int sub = f % step;
                        // 抬起曲线：中间高两边低，像指尖按下再抬起
                        float t = sub / (float)(step - 1);
                        float lift = (float)Math.sin(t * Math.PI);
                        for (int i = 0; i < nch; i++) {
                            float q;
                            float ty;
                            if (i == idx) { q = lift; ty = -lift * dens * 1.4f; }
                            else { q = 0f; ty = 0f; }
                            chs.get(i).setTranslationY(ty);
                            int col = Theme.mix(cyanC, flashCol, Math.round(q * 24f) / 24f * 0.85f);
                            if (i == idx || col != lastCol[i]) { chs.get(i).setTextColor(col); lastCol[i] = col; }
                        }
                        f++;
                        th.postDelayed(this, 45L);
                    } catch (Throwable ignored) {}
                }
            };
        } else {
            // 0 · 呼吸：去掉**字符间相位差**（原来每个字错开 0.45 弧度，
            //     整排字此起彼伏像坏了的霓虹灯）。现在整行同相位呼吸，幅度也更小。
            //     未签完时缓慢呼吸；今天签完了就停在最亮处不再动（保留信息语义）。
            flow = new Runnable() {
                float f = 0f;
                @Override public void run() {
                    try {
                        if (!floatRow.isShown()) { th.removeCallbacks(this); return; }
                        if (signedTodayAll) { settleTitle(chs, cyanC); return; }
                        float v = (float)((Math.sin(f * 0.045f) + 1f) / 2f) * 0.55f;  // 幅度压缩到 55%
                        float q = Math.round(v * 24f) / 24f;
                        int col = Theme.mix(greenC, cyanC, q);
                        for (int i = 0; i < nch; i++) {
                            if (col != lastCol[i]) { chs.get(i).setTextColor(col); lastCol[i] = col; }
                        }
                        f += 1f;
                        th.postDelayed(this, 55L);
                    } catch (Throwable ignored) {}
                }
            };
        }
        fx = flow;
        floatRow.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View vv) {
                th.removeCallbacks(fx);
                // 每次打开都完整播放（2026-10-03 用户要求）。
                // 之前 15 秒内重复打开会走 fastMainOpen 分支直接定格 ——
                // 而 settleTitle 定的是**青色**，于是"紧接着再打开"永远看到
                // 同一个深青色静态标题，轮换等于失效（用户实测反馈）。
                // 现在无条件播放：面板每次都是活的，四个特效应有尽有。
                settleTitle(chs, cyanC);      // 先复位，避免上一轮残留的位移/高亮
                th.postDelayed(fx, 0L);
            }
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
                vv.post(new Runnable() { @Override public void run() {
                    int w = head.getWidth();
                    sweep.setVisibility(View.VISIBLE);
                    sweep.setTranslationX(-(float) w);
                    // 与标题动效的拍子对齐（约 1.4s）：扫描线走完，标题也正好一轮过去，
                    // 不会出现"标题还在动、扫描线已经没了"的错位感。
                    sweep.animate().translationX((float) w).setDuration(1400).setStartDelay(0)
                         .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                         .withEndAction(new Runnable() {
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
        // 光标闪烁：打字期间闪，**打完 3 下就收起来**
        //（2026-10-03）：原先只要面板开着就无限闪，是常驻噪声。
        // 光标存在的意义是"正在输入"，输入完了它就该消失。
        final int[] blinkLeft = { 6 };
        final Runnable blink = new Runnable() {
            @Override public void run() {
                if (!cur.isShown()) { th.removeCallbacks(this); return; }
                if (blinkLeft[0] <= 0) { cur.setVisibility(View.GONE); return; }
                blinkLeft[0]--;
                cur.setVisibility(cur.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
                th.postDelayed(this, 420L);
            }
        };
        final Runnable[] typer = new Runnable[1];
        // 打字进度必须是**外部可变状态**：
        // 原先写在匿名类的 int ti 里，第二次打开时它已是"打完"的值，
        // 于是重置了文本却再也不会逐字打出（只有第一次能看到打字效果）。
        final int[] typePos = { 0 };
        typer[0] = new Runnable() {
            @Override public void run() {
                if (!sv.isShown()) { th.removeCallbacks(this); return; }
                if (typePos[0] <= fullCmd.length()) {
                    sv.setText(fullCmd.substring(0, typePos[0]));
                    typePos[0]++;
                    // 45ms/字（原 80ms）：整行约 1.3 秒打完，与标题动效同一拍收尾。
                    // 原来打完要 2.4 秒，节奏明显拖在标题后面。
                    th.postDelayed(this, 45L);
                } else {
                    th.removeCallbacks(this);
                    th.postDelayed(blink, 300L);
                }
            }
        };
        svRow.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View vv) {
                // 每次打开都从头逐字打出（用户要求；原来 15 秒内重开会直接全文显示）
                th.removeCallbacks(typer[0]);
                th.removeCallbacks(blink);
                typePos[0] = 0;               // 打字进度归零，否则第二次不会再打
                blinkLeft[0] = 6;             // 闪烁次数归零，否则第二次光标不再闪
                sv.setText("");
                cur.setVisibility(View.VISIBLE);
                th.postDelayed(typer[0], 200L);
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
            // 2026-10-03 UI 评审：「已签 x/y」原先在这里和进度条各出现一次，
            // 同一屏同一信息重复两遍。现在标题只说账号与规模，
            // 进度数字交给下面那根进度条（改带数字，见 progressBarWithLabel）。
            st1.setText("\u25B8 " + accountLabel(currentAccount()) + "  ·  "
                    + Lang.tf("目标 {0}", aAll)
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
            // 2026-10-03 二次修正：第一版把数字**叠在进度条上**（FrameLayout 居中），
            // 结果文字压在绿条上、又用的是正文白 —— 用户反馈"怎么在那个位置、
            // 白色也不好看"。现在改成条**上方**独立一行：
            //   · 数字用语义色（签完绿 / 未签完青），与状态一致；
            //   · 右侧补「待签 N」，一行说清"完成多少、还剩多少"；
            //   · 条本身回归纯色块（原来是 6dp 细线，现在 7dp，视觉重量够）。
            LinearLayout phead = new LinearLayout(act);
            phead.setOrientation(LinearLayout.HORIZONTAL);
            phead.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView plab = new TextView(act);
            plab.setTextSize(Theme.TS_CAPTION);
            plab.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            plab.setTextColor(pendN == 0 ? Theme.termGreen(act) : Theme.termCyan(act));
            plab.setText(Lang.tf("已签 {0}/{1}", sigN, actN));
            phead.addView(plab, new LinearLayout.LayoutParams(0, -2, 1f));
            if (pendN > 0) {
                TextView pleft = new TextView(act);
                pleft.setTextSize(Theme.TS_CAPTION);
                pleft.setTypeface(Theme.text());
                pleft.setTextColor(Theme.termMuted(act));
                pleft.setText(Lang.tf("待签 {0}", pendN));
                phead.addView(pleft, new LinearLayout.LayoutParams(-2, -2));
            }
            LinearLayout.LayoutParams php = new LinearLayout.LayoutParams(-1, -2);
            php.topMargin = dp(8);
            statCard.addView(phead, php);

            LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(-1, dp(7));
            plp2.topMargin = dp(6);
            statCard.addView(pbar, plp2);
        } catch (Throwable ignored) {}
        // 2026-09-30：原此处单独一行「连续签到 N 天 · 最近 14 天」，
        // 与紧邻的日历摘要（连续 N 天 / N/14）信息完全重复，两个数字还容易看岔。
        // 现统一由日历承担：连续天数与近 14 天完成度都在那里给出，此处不再重复。
        // 定时模式：显示距下次签到倒计时
        //
        // 2026-09-30 修正：今天全部签完时**不再显示"下次签到"**。
        // 起因（用户实测）：已签 14/14，界面却仍写「下次签到 20:07 · 约 97 分钟后」，
        //   看起来像"还没签完、还要再签一轮"，与实际状态矛盾。
        // 根因：倒计时只按时刻表算，不看"今天是否已签完"——
        //   而时刻表是早上生成的（含当时全部未签目标），签完之后表不会自我清理。
        // 2026-10-01：此处原本在「今天全部签完」时显示一行「✓ 今日已全部完成」，
        // 但紧邻的日历摘要行已用「✓ 今天已签」表达同一件事，且区分四态、信息更全
        // （用户截图里同一信息出现了四次）。移除该行，只保留"下次签到"倒计时。
        // 注意：倒计时**仅在没有全部签完时**显示 —— 已签完还提示"下次签到"会
        // 让人以为还要再签一轮（2026-09-30 修过这个矛盾，这里保持该约束）。
        final boolean allSignedToday = isAllSignedToday();
        if (TIMER_ENABLED && !allSignedToday) {
            String nx = nextTimerLabel();
            if (nx != null) {
                // 2026-09-30：改用青色。原为琥珀色，与「按钮失效/退避重试」等
                // 需要留意的状态同色，用户会误以为出问题了 —— 实际这只是个正常倒计时。
                // 琥珀色保留给真正的警示场景。
                TextView stT = new TextView(act); stT.setTextSize(Theme.TS_CAPTION);
                stT.setTextColor(Theme.termCyan(act));
                stT.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                stT.setPadding(0, dp(4), 0, 0);
                stT.setText(Lang.tf("下次签到 {0}", nx));
                android.graphics.drawable.Drawable nd = Icons.d(act, "clock", 13f, Theme.termCyan(act));
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
        // 徽章加图标：仅靠「按钮学习 / 网络学习」几个字，用户看不出在说什么
        chips1.addView(badge(act, "按钮学习", AUTO_LEARN, "target"));
        chips1.addView(badge(act, "网络学习", AUTO_LEARN_NET, "globe"));
        statCard.addView(chips1);
        LinearLayout chips2 = new LinearLayout(act); chips2.setOrientation(LinearLayout.HORIZONTAL); chips2.setPadding(0, dp(6), 0, 0);
        chips2.addView(badge(act, "唤醒", WAKE_CMD != null && !WAKE_CMD.isEmpty(), "bulb"));
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
        tt.setText(Lang.tr("最近动态"));
        term.addView(tt);
        final LinearLayout termBody = new LinearLayout(act);
        termBody.setOrientation(LinearLayout.VERTICAL);
        term.addView(termBody);
        TextView tm = new TextView(act); tm.setTextSize(Theme.TS_CAPTION); tm.setTypeface(android.graphics.Typeface.MONOSPACE); tm.setTextColor(Theme.termCyan(act));
        tm.setText(Lang.tr("查看全部记录 →")); tm.setPadding(0, dp(6), 0, 0); tm.setClickable(true);
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
        // ── 主按钮：立即签到（2026-10-03 UI 评审）──
        // 改前这里是一条 4 格快捷胶囊（立即签到 / 目标 / 日志 / 自检），
        // 与下方 8 个大卡片、底部「全部功能」分类栏**三层指向同一批功能**。
        // 三个里砍掉两个的一半：快捷条整条删（4 项全被卡片覆盖），
        // 但把其中唯一"动作型"的入口提级成主按钮 ——
        // 它是这页唯一"点一下就干活"的东西，值得比导航项更重。
        LinearLayout quick = new LinearLayout(act);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setPadding(0, dp(10), 0, 0);
        {
            Button mainBtn = mkBtnPrimary(act);
            withIconText(act, mainBtn, "bolt", Lang.tr("立即签到"));
            mainBtn.setTextSize(Theme.TS_BODY);
            mainBtn.setPadding(dp(14), dp(12), dp(14), dp(12));
            mainBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { runAction(act, "sign"); }
            });
            quick.addView(mainBtn, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            // 右侧挂一个轻量入口：自检（原来快捷条里的"自检"在卡片里没有）
            TextView diagBtn = new TextView(act);
            diagBtn.setText(Lang.trShort("自检"));
            diagBtn.setTextSize(Theme.TS_CAPTION);
            diagBtn.setTypeface(android.graphics.Typeface.MONOSPACE);
            diagBtn.setTextColor(Theme.termCyan(act));
            diagBtn.setGravity(android.view.Gravity.CENTER);
            diagBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
            diagBtn.setBackground(termBorder(act, Theme.withAlpha(Theme.termCyan(act), 0x12),
                    Theme.withAlpha(Theme.termCyan(act), 0x59)));
            android.graphics.drawable.Drawable qd = Icons.d(act, "pulse", 12f, Theme.termCyan(act));
            if (qd != null) {
                int qsz = Theme.dp(act, 12);
                qd.setBounds(0, 0, qsz, qsz);
                diagBtn.setCompoundDrawables(qd, null, null, null);
                diagBtn.setCompoundDrawablePadding(Theme.dp(act, 4));
            }
            diagBtn.setClickable(true);
            diagBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { runAction(act, "diag"); }
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.setMargins(dp(6), 0, 0, 0);
            quick.addView(diagBtn, dlp);
        }
        head.addView(quick);
        root.addView(head);
        // ── 高频区：每天真的会点的 6 个入口（其余收进「更多功能」） ──
        sectionHeader(root, act, "▍签到");
        android.widget.GridLayout g1 = new android.widget.GridLayout(act); g1.setColumnCount(2); root.addView(g1);
        addTile(g1, act, "list", "目标列表", "查看·测试·编辑", "list");
        addTile(g1, act, "calendar", "补签列表", "今日待补·已补·跳过", "misslist");
        // 「立即签到」已提级为顶部主按钮，不再重复出现在卡片区（2026-10-03）
        addTile(g1, act, "clock", "今日计划", "几点签·补签", "misslist");
        addTile(g1, act, "doc", "运行日志", "搜索·筛选·清空", "log");
        addTile(g1, act, "sliders", "设置", "定时·窗口·间隔", "settings");
        addTile(g1, act, "trash", "排除管理", "规则·排除 bot·待添加", "exclude");
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
            // 易懂 / 详细 切换（放最前，因为它决定整页观感）
            //
            // 2026-09-30 修：原实现在切换时调用 showLog(act) 重建整页 ——
            //   而 showLog 会**再弹一个对话框**，旧的那个不关，
            //   于是点几次就叠几层，返回时要按多次才能退出（用户实测反馈）。
            // 现在改为原地更新：改按钮自身的文案与图标 + 重渲染列表，全程不新建对话框。
            final TextView plainChip = logChip(act, chips, logPlain ? "易懂" : "详细",
                    logPlain ? "check" : "doc",
                    logPlain ? Theme.termGreen(act) : Theme.termMuted(act),
                    null);
            plainChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                logPlain = !logPlain;
                logShowDebug = !logPlain;
                // 原地改按钮外观
                int col = logPlain ? Theme.termGreen(act) : Theme.termMuted(act);
                plainChip.setText(logPlain ? Lang.tr("易懂") : Lang.tr("详细"));
                plainChip.setTextColor(Theme.termTxt(act));
                android.graphics.drawable.Drawable id =
                        Icons.d(act, logPlain ? "check" : "doc", 13f, col);
                if (id != null) {
                    int isz = dp(13);
                    id.setBounds(0, 0, isz, isz);
                    plainChip.setCompoundDrawables(id, null, null, null);
                    plainChip.setCompoundDrawablePadding(dp(5));
                }
                plainChip.setBackground(termBorder(act, Theme.termCard(act),
                        Theme.withAlpha(col, 0x66)));
                // 只重渲染列表，不重建对话框
                logLimit = 200;
                logRendered = 0;
                if (logHeadBar != null) logHeadBar.setVisibility(logPlain ? android.view.View.VISIBLE : android.view.View.GONE);
                refreshLogHead();
                refreshLog();
                jumpLogNewest();
                toast(logPlain ? Lang.tr("易懂：只显示结果与下一步")
                               : Lang.tr("详细：显示原始日志"));
            } });
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
            logChip(act, chips, "导出问题报告", "copy", Theme.termCyan(act), new Runnable() { @Override public void run() { doCopyDiagnose(act); } });
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

            // ── 顶部状态条（2026-10-03）──
            // 解决"易懂模式没劲儿"：战况固定在眼前，不用翻日志找。
            // 同时是「补签已执行」那条绿色噪音的替代 ——
            // 用户要的信息（今天签完没、几个目标、下次什么时候）这里一次给全。
            logHeadBar = new LinearLayout(act);
            logHeadBar.setOrientation(LinearLayout.VERTICAL);
            logHeadBar.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout headRow = new LinearLayout(act);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            logHeadIcon = new TextView(act);
            logHeadTitle = new TextView(act);
            logHeadTitle.setTextSize(Theme.TS_BODY);
            logHeadTitle.setTypeface(android.graphics.Typeface.MONOSPACE);
            logHeadSub = new TextView(act);
            logHeadSub.setTextSize(Theme.TS_CAPTION);
            logHeadSub.setTypeface(android.graphics.Typeface.MONOSPACE);
            logHeadStat = new TextView(act);
            logHeadStat.setTextSize(Theme.TS_CAPTION);
            logHeadStat.setTypeface(android.graphics.Typeface.MONOSPACE);
            headRow.addView(logHeadIcon);
            headRow.addView(logHeadTitle);
            logHeadBar.addView(headRow);
            logHeadBar.addView(logHeadSub);
            logHeadBar.addView(logHeadStat);
            root.addView(logHeadBar);
            refreshLogHead();
            maybeAppendYesterdayReport(root);

            ScrollView sv = new ScrollView(act);
            logSv = sv;
            logList = new LinearLayout(act);
            logList.setOrientation(LinearLayout.VERTICAL);
            sv.addView(logList);
            // 统一高度（见 LIST_H_RATIO）：与目标列表、设置页取同一个值，
            // 页面之间切换时窗口不再"跳"。
            int listH = listContentHeight(act);
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
            logLimit = 200;   // 首屏 200 条（2026-10-03 由 400 下调）
            logRendered = 0;  // 重置分页游标（每次打开都从首屏开始）// 默认多显示一些，配合底部「加载更多」
            refreshLog();
            jumpLogNewest();   // 打开即定位到最新
        }

        private TextView logChip(final Context c, LinearLayout parent, String label, final Runnable action) {
            return logChip(c, parent, Lang.tr(label), null, Theme.termTxt(c), action);
        }

        /** 带前导矢量图标的日志页芯片 */
        private TextView logChip(final Context c, LinearLayout parent, String label, String icon, int iconColor, final Runnable action) {
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
            if (action != null) tv.setOnClickListener(v -> action.run());
            parent.addView(tv);
            return tv;
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
                for (int i = logRendered; i < to; i++) logRowAt(logList, show.get(i));
                logRendered = to;
                updateMoreBtn(show.size(), logRendered);
                if (logStat != null) {
                    String cur = String.valueOf(logStat.getText());
                    logStat.setText(cur.replaceAll("已载入 [0-9]+", Lang.tf("已载入 {0}", logRendered)));
                }
            } catch (Throwable t) { try { loge("追加日志失败: " + t); } catch (Throwable ignored) {} }
        }

        /**
         * 昨日战报（2026-10-03）。
         *
         * 解决"易懂模式没劲儿"的另一半：日志里全是今天的过程，
         * 没有"昨天结果如何"的总结。这里在状态条下面追加一条，
         * **同一天只出现一次**（用 prefs 记已展示的日期），不刷屏。
         *
         * 数据来源：昨天的 last_（哪些目标签上了）+ 昨天的连续天数记录。
         * 取不到就整块不显示 —— 宁可不显示，也不要给假数字。
         */
        private void maybeAppendYesterdayReport(LinearLayout root) {
            try {
                String yest = yesterdayStr();
                if (yest.length() == 0) return;
                String shownDay = prefs.getString("jmb_yreport_day", "");
                if (yest.equals(shownDay)) return;      // 昨天战报今天已展示过

                String prefix = accountPrefix();
                List<Map<String, Object>> tl = new ArrayList<Map<String, Object>>();
                loadTargetsInto(prefix, tl);
                if (tl.isEmpty()) return;
                int total = tl.size(), done = 0;
                for (Map<String, Object> m : tl) {
                    if (yest.equals(prefs.getString(kLast(prefix, entryId(m)), ""))) done++;
                }
                if (done == 0) return;                  // 昨天没签成任何目标：不吹不黑，不显示

                Context c = root.getContext();
                LinearLayout box = new LinearLayout(c);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(android.view.Gravity.CENTER_VERTICAL);
                box.setPadding(dp(10), dp(6), dp(10), dp(6));
                TextView ic = new TextView(c);
                android.graphics.drawable.Drawable d = Icons.d(c, "today-done", 14f, Theme.termMuted(c));
                if (d != null) {
                    int sz = dp(14);
                    d.setBounds(0, 0, sz, sz);
                    ic.setCompoundDrawables(d, null, null, null);
                    ic.setCompoundDrawablePadding(dp(6));
                }
                TextView tv = new TextView(c);
                tv.setTextSize(Theme.TS_CAPTION);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextColor(Theme.termMuted(c));
                tv.setText(Lang.tf("昨天 {0}/{1} 完成", done, total));
                box.addView(ic);
                box.addView(tv);
                box.setBackground(termBorder(c, Theme.termCard(c), Theme.withAlpha(Theme.termMuted(c), 0x44)));
                root.addView(box);
                prefs.edit().putString("jmb_yreport_day", yest).apply();
            } catch (Throwable t) { noteSwallowed("yesterdayReport", t); }
        }

        /**
         * 刷新顶部状态条（2026-10-03）。
         *
         * 一行给结论：今天签完没 / 几个目标 / 连续几天 / 下次什么时候，
         * 颜色直接取四态语义色（绿=已签、青=待签、粉=未签、灰=不参与）。
         * 详情（窗口、补签截止）放第二行，弱色。
         */
        private void refreshLogHead() {
            try {
                if (logHeadBar == null || logHeadTitle == null) return;
                Context c = logHeadBar.getContext();
                int[] st = accountStats(currentAccount());
                int total = st[0], signed = st[1];
                String prefix = accountPrefix();
                String todayS = todayStr(), yestS = yesterdayStr();
                int streak = SignLogic.streakDisplay(streakOf(prefix),
                        prefs.getString(kLastSignDate(prefix), ""), todayS, yestS);
                java.util.Calendar cc = java.util.Calendar.getInstance();
                int nowMin = cc.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cc.get(java.util.Calendar.MINUTE);
                int tState = SignLogic.todayState(signed > 0, nowMin,
                        SignLogic.windowRangeAny(WINDOW), MISS_DEADLINE, MISS_BACK, total > 0);

                String icon; int col; String title;
                if (total == 0) {
                    icon = "today-idle"; col = Theme.termMuted(c);
                    title = Lang.tr("还没有签到目标");
                } else {
                    switch (tState) {
                        case SignLogic.TODAY_DONE:
                            icon = "today-done"; col = Theme.termGreen(c);
                            title = Lang.tf("今日已签 {0}/{1}", signed, total);
                            break;
                        case SignLogic.TODAY_MISSED:
                            icon = "today-miss"; col = Theme.termPink(c);
                            title = Lang.tf("今日未签 {0}/{1}", signed, total);
                            break;
                        case SignLogic.TODAY_IDLE:
                            icon = "today-idle"; col = Theme.termMuted(c);
                            title = Lang.tf("今日 {0}/{1}（无启用目标）", signed, total);
                            break;
                        default:
                            icon = "today-wait"; col = Theme.termCyan(c);
                            title = Lang.tf("今日已签 {0}/{1}", signed, total);
                            break;
                    }
                }
                logHeadTitle.setText(title);
                logHeadTitle.setTextColor(col);
                if (logHeadIcon != null) {
                    android.graphics.drawable.Drawable d = Icons.d(c, icon, 15f, col);
                    if (d != null) {
                        int sz = dp(15);
                        d.setBounds(0, 0, sz, sz);
                        logHeadIcon.setCompoundDrawables(null, null, d, null);
                        logHeadIcon.setCompoundDrawablePadding(dp(6));
                    }
                }
                // 第二行（配置）= 连续天数 + 窗口 + 补签截止；
                // 第三行（战绩）= 今日实际签的时段 + 补签几次。
                // 拆两行是因为一行塞不下：实测"窗口"被裁成"窗□"、
                // "补签至"被挤到下一行（用户截图）。
                StringBuilder sb = new StringBuilder();
                if (streak > 0) sb.append(Lang.tf("连续 {0} 天", streak));
                try {
                    List<Map<String, Object>> tl = new ArrayList<Map<String, Object>>();
                    loadTargetsInto(prefix, tl);
                    long earliest = Long.MAX_VALUE, latest = 0L;
                    int missN = 0;
                    for (Map<String, Object> m : tl) {
                        String id = entryId(m);
                        if (!todayS.equals(prefs.getString(kLast(prefix, id), ""))) continue;
                        long at = stateStore.signedAtMs(prefix, id);
                        if (at > 0L) {
                            if (at < earliest) earliest = at;
                            if (at > latest) latest = at;
                        }
                        if (stateStore.missAtMsToday(prefix, id) > 0L) missN++;
                    }
                    if (latest > 0L) {
                        StringBuilder s2 = new StringBuilder();
                        String e = SignLogic.hhmmOf(earliest);
                        String l = SignLogic.hhmmOf(latest);
                        s2.append(Lang.tf("今日 {0}", e.equals(l) ? e : (e + "-" + l)));
                        if (missN > 0) s2.append(Lang.tf("  /  补签 {0} 次", missN));
                        logHeadStat.setText(s2.toString());
                    } else {
                        logHeadStat.setText("");
                    }
                } catch (Throwable _eH) { noteSwallowed("logHead-stats", _eH); }
                if (total > 0) {
                    int[] wr = SignLogic.windowRangeAny(WINDOW);
                    if (wr != null) {
                        if (sb.length() > 0) sb.append("  /  ");
                        // 去掉"窗口"二字：第二行要放 4 段信息，前缀词太占宽度，
                        // 实测换行时"窗口"会被裁成"窗□"（用户截图）。
                        sb.append(SignLogic.hhmm(wr[0]) + "-" + SignLogic.hhmm(wr[1]));
                    } else {
                    if (sb.length() > 0) sb.append("  /  ");
                        sb.append(Lang.tr("不限窗口"));
                    }
                    if (MISS_BACK) {
                        sb.append(Lang.tf("  /  补签至 {0}", SignLogic.hhmm(MISS_DEADLINE)));
                    }
                }
                logHeadSub.setText(sb.toString());
                logHeadSub.setTextColor(Theme.termFaint(c));
                if (logHeadStat != null) logHeadStat.setTextColor(Theme.termMuted(c));
                logHeadBar.setBackground(termBorder(c, Theme.termCard(c), Theme.withAlpha(col, 0x55)));
            } catch (Throwable t) { noteSwallowed("refreshLogHead", t); }
        }

        /**
         * 把易懂档里"同一件事反复发生"的条目折叠成一条（2026-10-03）。
         *
         * 例：「[定时] X 已有请求在途/待结论，不重排」一天 12 次 ——
         * 讲的是同一件事（"别重复发"），刷 12 行没有新信息。
         * 折叠后只留最新一条，后缀「xN」。
         *
         * 只折叠带 foldKey 的类别（见 foldKeyOf）；签到成功、失败这类
         * 每次都是独立事件，不折叠 —— 否则会掩盖真实的发生次数。
         * 详细档完全不折叠（那里要看原始流水）。
         */
        private List<LogLine> foldShow(List<LogLine> show) {
            logFoldCnt.clear();
            if (!logPlain || show == null || show.isEmpty()) return show;
            try {
                List<LogLine> out = new ArrayList<LogLine>();
                java.util.HashSet<String> seen = new java.util.HashSet<String>();
                java.text.SimpleDateFormat dayFmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
                for (int i = show.size() - 1; i >= 0; i--) {     // 最新 -> 最旧
                    LogLine l = show.get(i);
                    String[] pp = plainLogParts(String.valueOf(l.msg));
                    String fk = (pp != null && pp.length > 3) ? pp[3] : null;
                    if (fk == null) { out.add(l); continue; }
                    // \u0001D: 前缀 = 按天折叠（跨天重新计数，避免"×90"失去时间感）
                    if (fk.length() > 2 && fk.charAt(0) == '\u0001' && fk.charAt(1) == 'D') {
                        fk = fk.substring(2) + "@" + dayFmt.format(new java.util.Date(l.ts));
                    }
                    if (seen.contains(fk)) {
                        Integer cur = logFoldCnt.get(fk);
                        logFoldCnt.put(fk, cur == null ? 2 : cur + 1);
                        continue;
                    }
                    seen.add(fk);
                    out.add(l);
                }
                // 遍历是"新->旧"，收集出来也就成了新->旧；
                // 但调用方（renderLog）拿到的是"旧->新"的列表、后面还要 reverse 一次。
                // 这里必须还原成同序交给它，否则一翻再翻 → 最旧的在最上面
                // （2026-10-03 用户截图实测：09-28 的老记录跑到第一行）。
                Collections.reverse(out);
                return out;
            } catch (Throwable t) { return show; }
        }

        private void refreshLog() {
            // mergedLog 会读最多 8MB×N 个日志文件并解析 2 万行 —— 以前直接在主线程跑，
            // 日志一多打开"运行日志"就明显卡顿。丢到 IO 线程，回主线程渲染。
            if (logList == null) return;
            LOG_IO.execute(new Runnable() { @Override public void run() {
                // 2026-10-03：读入上限 20000 → 8000（修"点开日志卡一下"）。
                // 日志页首屏只显示最近若干条，读满 2 万行纯属浪费 ——
                // 而 renderLog 要对**读进来的每一行**跑一次易懂档翻译过滤
                // （即使最终只显示几百行），2 万行就是 2 万次字符串扫描 + 正则。
                // 8000 行足够覆盖"翻几屏 + 加载更多"的实际使用。
                final List<LogLine> got = mergedLog(8000);
                mainHandler.post(new Runnable() { @Override public void run() { renderLog(got); } });
            } });
        }

        private void renderLog(final List<LogLine> all) {
            try {
                if (logList == null) return;
                logList.removeAllViews();
                List<LogLine> show = new ArrayList<LogLine>();
                int errs = 0, warns = 0;
                String qq = logQuery.toLowerCase(Locale.US);
                String tf = logTarget;
                for (LogLine l : all) {
                    if (l.lv == LV_ERR) errs++;
                    else if (l.lv == LV_WARN) warns++;
                    // 易懂档：**不按级别过滤**（2026-10-03 修"首页有、日志页没有"）。
                    // 级别是给详细档的分档工具；易懂档的过滤靠 plainLogParts 的
                    // 翻译结果（译不出来的返回 null，已在上面跳过）。
                    // 若再按 LV_DEBUG 丢一遍，会误伤大量"用户该知道"的行 ——
                    // 「跳过发送（今天已签）」这类为保证落盘就写成 LV_DEBUG。
                    // 用户显式选了「只看重要/只看错误」时，那个更高阈值仍然生效。
                    if (logPlain) {
                        if (logFilter > LV_DEBUG && l.lv < logFilter) continue;
                    } else {
                        if (!logShowDebug && l.lv == LV_DEBUG) continue;
                        if (l.lv < logFilter) continue;
                    }
                    if (qq.length() > 0 && String.valueOf(l.msg).toLowerCase(Locale.US).indexOf(qq) < 0) continue;
                    // 易懂档：译不出人话的条目直接不参与分页（2026-10-03）。
                    // 以前只在渲染时 return，空条目照样占"已载入 N 条"的名额，
                    // 首屏 400 条里常常一大半是看不见的，用户以为日志很少。
                    if (logPlain && plainLogParts(String.valueOf(l.msg)) == null) continue;
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
                // 折叠：同 foldKey 的重复事件合并成一条（2026-10-03）
                show = foldShow(show);
                Collections.reverse(show);   // 固定「最新在上」
                logViewCache = show;         // 供「加载更多」追加用
                int n = Math.min(show.size(), logLimit);
                logRendered = n;
                // 按钮文案按真实剩余判断（修复「明明还有却说已全部加载」）
                updateMoreBtn(show.size(), n);
                for (int i = 0; i < n; i++) logRowAt(logList, show.get(i));
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

        private void logRow(LinearLayout parent, final LogLine l) { logRow(parent, l, 0); }

        /** 按折叠计数渲染一行（易懂档专用）。 */
        private void logRowAt(LinearLayout parent, LogLine li) {
            String fk = null;
            try {
                String[] pp = logPlain ? plainLogParts(String.valueOf(li.msg)) : null;
                fk = (pp != null && pp.length > 3) ? pp[3] : null;
            } catch (Throwable ignored) {}
            Integer fc = (fk == null) ? null : logFoldCnt.get(fk);
            logRow(parent, li, fc == null ? 0 : fc.intValue());
        }

        private void logRow(LinearLayout parent, final LogLine l, int foldTimes) {
            final Context c = parent.getContext();
            final boolean dark = Theme.dark(c);
            final String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(l.ts));
            // 易懂档：翻译成一句话 + 矢量图标；内部机制条直接不渲染
            String body;
            String plainIcon = null;
            String plainSem = null;
            if (logPlain) {
                String[] pp = plainLogParts(l.msg);
                if (pp == null) return;
                plainIcon = pp[0];
                body = pp[1];
                plainSem = pp.length > 2 ? pp[2] : "info";
                // 折叠计数：同一条内容重复 N 次时显示「×N」，比刷 N 行有用
                if (foldTimes > 1) body = body + "  x" + foldTimes;
            } else {
                body = l.msg;
            }
            // 级别配色：亮色=深色系高对比 / 暗色=亮色系低刺眼
            final int textCol, tsCol, barCol, rowBg;
            switch (plainSem != null ? -1 : l.lv) {
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
                    if (plainSem != null) {
                        // 易懂档：颜色取自「翻译结果的语义」，而非原始日志级别。
                        // 原因见 plainColor 注释：lv 为了保落盘会把成功标成 WARN，
                        // 直接用它上色会把「签到成功」显示成黄色警告。
                        textCol = plainColor(plainSem, c);
                        barCol  = textCol;
                        tsCol   = dark ? 0xFF5A6478 : 0xFFB0B0B0;
                        rowBg   = 0x00000000;
                    } else {
                        textCol = dark ? 0xFFB8C4DC : 0xFF4A5568;
                        tsCol = dark ? 0xFF5A6478 : 0xFFB0B0B0;
                        barCol = dark ? 0xFF7C8DB5 : 0xFF7A8AA3;
                        rowBg = 0x00000000;
                    }
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
            // 易懂档：正文前挂一个矢量图标（颜色跟随级别），比 emoji 更贴主题
            if (plainIcon != null) {
                android.graphics.drawable.Drawable ic = Icons.d(c, plainIcon, 12f, textCol);
                if (ic != null) {
                    int isz = Theme.dp(c, 12);
                    ic.setBounds(0, 0, isz, isz);
                    tv.setCompoundDrawables(ic, null, null, null);
                    tv.setCompoundDrawablePadding(Theme.dp(c, 4));
                }
            }
            // body 现在可能被折叠计数改写（不再是 effectively final），
            // 匿名内部类要用它必须走 final 副本。
            final String bodyForCopy = body;
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { copyToClip(bodyForCopy); return true; }
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

    /**
     * 使用教程（2026-10-03 全面重写）。
     *
     * 改前的问题：
     *   · 14 张卡片一路平铺，要划 8~10 屏才能看完，找一条信息像大海捞针；
     *   · 内容过时 —— 说的是"顶部是今日进度与最近日志，中间 6 个高频入口"，
     *     但面板早就改成了「全部功能」五组分类直达；
     *   · 日志那节还在讲旧的级别配色（灰=普通/暗灰=调试），
     *     而默认档已经改成「易懂」，颜色语义完全不同。
     *
     * 现在：
     *   · 按板块折叠（复用设置页那套 collapseCard，观感统一）；
     *   · 首屏只展开「新手只看这段」，其余收起，一眼看全目录；
     *   · 内容全部按当前实现核对过（面板入口、日志两档、四态、补签语义…）。
     */
    private void showTutorial(Activity act) {
        if (act == null) return;
        // 各板块的强调色（与设置页取同一套主题色，观感统一）
        final int ACC_CORE  = Theme.termCyan(act);
        final int ACC_LOOK  = Theme.termPink(act);
        final int ACC_LEARN = Theme.termGreen(act);
        final int ACC_NOTI  = Theme.termAmber(act);
        final int ACC_LOG   = Theme.termMuted(act);
        ScrollView sv = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.dp(act, 10), Theme.dp(act, 4), Theme.dp(act, 10), Theme.dp(act, 4));

        // ── 新手只看这段（默认展开）──
        tutCard(box, act, "新手只看这段（30 秒）", "pulse", true, ACC_CORE,
                "① 任意聊天输入框发 /jmb 打开面板\n"
              + "② 点「添加目标」，或更省事：去那个 bot 会话里点一次它的签到按钮，模块会自动认出来\n"
              + "③ 剩下的它自己办 —— 每天在你设的时段里自动签、失败自动重试、结果发到你自己的收藏夹\n\n"
              + "不需要一直开着 Telegram。回来看结果，还是发 /jmb。");

        // ── 1 面板怎么用 ──
        tutCard(box, act, "1 · 面板怎么用", "list", false, ACC_CORE,
                "输入 /jmb 随时打开。首页从上到下是：\n"
              + "· 顶部状态条 —— 今天签了几个、连续多少天、下次什么时候\n"
              + "· 今日进度与「最近动态」（最近几条结果，每 4 秒自己刷新）\n"
              + "· 高频入口 —— 目标列表 / 补签列表 / 立即签到 / 运行日志 / 设置 / 排除管理 / 账号一览 / 使用教程\n"
              + "· 最下一行「全部功能」 —— 按 目标 / 数据 / 系统 / 帮助 / 维护 五组分好，横滑点一下直达\n\n"
              + "「立即签到」= 现在就把今天还没签的补一遍；「目标列表」= 看每个目标的具体状态。");

        // ── 2 添加目标（三种方式 + 怎么选）──
        tutCard(box, act, "2 · 添加目标（三种方式）", "plus", false, ACC_CORE,
                "· 回调按钮（推荐，最省事）\n"
              + "  去那个 bot 的会话里**点一次它的签到按钮**，模块自动认出并记住，以后每天替你点。\n"
              + "  绑定不受关键词限制 —— 点什么学什么。\n\n"
              + "· 文本指令\n"
              + "  知道 bot 要求的签到文本（如 /checkin、/qd）时用。填机器人数字 ID + 指令，到点自动发。\n\n"
              + "· 群 / 频道\n"
              + "  签到要在群里发指令时用。填群 ID（形如 -1001234567890，含负号）。\n\n"
              + "不知道 bot ID？三种方式都能用「从会话列表选群」直接挑，不用手抄数字。\n"
              + "同一个 bot 可以加多条指令，互不冲突。");

        // ── 3 拉面板（前置命令）──
        tutCard(box, act, "3 · bot 要先发指令才给面板？", "keyboard", false, ACC_CORE,
                "有的 bot 不主动发签到面板，得先发一条指令它才吐出来。\n\n"
              + "在该条目的「编辑」里填「前置命令序列」—— 逗号或换行分隔，可以写多条（如 /start, 菜单）。\n"
              + "签到或测试时，模块会先依次发送这些命令把面板拉出来，再点按钮。\n\n"
              + "日志里看到「面板未就绪」多半就是缺这一步。");

        // ── 4 节奏（设置）──
        tutCard(box, act, "4 · 用什么节奏签（设置）", "sliders", false, ACC_CORE,
                "设置页分四块，每块都能折叠：签到核心 / 外观 / 学习与判定 / 通知。\n\n"
              + "· 定时签到：开启后只在「签到时间」窗口内动作；窗口外所有自动触发一律不响应\n"
              + "· 签到时间：两端都能点，例如 08:30-20:30；支持跨天（如 22:00-02:00）\n"
              + "· 错开间隔：0 = 按目标数自动均分；设 N 分钟则相邻目标至少隔 N 分钟，防风控\n"
              + "· 错过补签 + 补签截止：窗口内错过的还能补到截止时间（默认 23:00）\n\n"
              + "补签说明：补签没有单独的次数限制，但它和「重试」共用一个每日上限"
              + "（设置里「每日重试上限」，默认 5 次）。区别只在：重试 = 失败后再试，补签 = 错过时间后补上。");

        // ── 5 状态怎么看（含四态 + 补签语义，替换旧⑥⑬）──
        tutCard(box, act, "5 · 状态怎么看懂", "today-done", false, ACC_LOOK,
                "顶部状态条的第一行用四个图标 + 颜色表示今天：\n"
              + "· 实心圆加对勾（绿）—— 今日已签\n"
              + "· 断续圆环（青）—— 今天待签，还没到时间或还没签\n"
              + "· 圆环加短横（粉）—— 今天未签，已过补签截止\n"
              + "· 圆环加双竖（灰）—— 不参与，没有启用中的目标\n\n"
              + "第二、三行是「连续 N 天」、签到时段、以及今天的实际战绩（几点到几点签的、补了几次）。\n\n"
              + "单个目标的状态在「目标列表」里看：\n"
              + "· 已签 = 今天签上了\n"
              + "· 待签 = 还没签，会按计划自动签\n"
              + "· 已发出 = 指令发出去了，在等机器人回复\n"
              + "· 待确认 = 机器人没回话或回的话看不懂，**需要你扫一眼**（列表里点一下就能处置）\n"
              + "· 已放弃 = 今天试到上限了，明天恢复");

        // ── 6 补签列表 ──
        tutCard(box, act, "6 · 补签列表", "calendar", false, ACC_LOOK,
                "看「今天每个目标打算几点签、实际几点签的」。\n\n"
              + "· 准点 / 补签 —— 标出这次是按时签的还是补的\n"
              + "· 计划 09:51 → 实签 10:26，后面还会标「补签 10:24（晚 35 分）」\n"
              + "· 已跳过 = 不在签到时间或没开补签\n"
              + "· 已过期 = 过了补签截止\n\n"
              + "顶部一行会写明当前窗口与补签是否开启。");

        // ── 7 通知 ──
        tutCard(box, act, "7 · 结果通知在哪看", "megaphone", false, ACC_NOTI,
                "设置 → 通知。开启后每天的签到摘要会**静默发到你自己的 Telegram 收藏夹**"
              + "（不弹系统通知，也不需要通知权限）。\n\n"
              + "· 「只通知失败」—— 只想出问题时被打扰就打开它\n"
              + "· 连续失败会额外告警一次，不会天天刷屏\n\n"
              + "摘要内容：今日 X/Y 已签、需要你决定的（按钮失效 / 机器人没回复 / 回复判不出）、"
              + "以及自动重试中的列表。");

        // ── 8 日志怎么读（按现状重写）──
        tutCard(box, act, "8 · 日志怎么读", "doc", false, ACC_LOG,
                "运行日志有两档，顶部第一个芯片切换：\n\n"
              + "· **易懂**（默认）—— 只显示「结果 + 下一步」，把内部机制条藏起来。\n"
              + "  顶部还有一条状态条，一眼看到今天签了几个、连续几天、今天几点完成的。\n"
              + "  同一件事反复发生时（比如反复提示不在签到时间）会折叠成一条并标 ×N。\n"
              + "· **详细** —— 原始流水，排障用。开发者要的信息都在这里。\n\n"
              + "其他芯片：全部 / 只看重要 / 只看错误 / 回到最新 / 目标过滤 / 导出问题报告。\n"
              + "日志固定「最新在最上」，打开即定位最新；「加载更多」每次多取 300 条。\n\n"
              + "颜色含义（两档一致）：绿 = 成功，青 = 进行中，黄 = 需要注意，粉 = 失败，灰 = 普通信息。\n"
              + "长按任意一行可复制该行内容。");

        // ── 9 多账号 ──
        tutCard(box, act, "9 · 多账号", "globe", false, ACC_LOOK,
                "每个账号的目标、设置、进度**各自独立**。\n\n"
              + "Telegram 里切到哪个账号，面板就是那个账号的内容，签到也跟随当前账号，"
              + "不会替后台账号乱发。\n\n"
              + "· 「账号一览」—— 看所有账号各有几个目标、今天签了多少\n"
              + "· 「签全部账号」—— 一次把所有已登录账号都签一遍\n"
              + "· 「复制目标」—— 把当前账号的目标整体复制给其它账号，不用一条条重加\n"
              + "· 每个账号可以单独停用（停用后完全不参与，连手动点也不发）");

        // ── 10 学习行为 ──
        tutCard(box, act, "10 · 自动学习是怎么工作的", "target", false, ACC_LEARN,
                "设置 → 学习与判定。两个开关：\n\n"
              + "· **按钮学习**（默认开）—— 你在 bot 里点过的按钮自动加进目标。"
              + "点什么学什么，只在两处拦：你拉黑过的 bot、你写的排除规则。\n"
              + "· **网络学习**（默认开）—— 你手动发出去的签到文本也能被认出来。"
              + "默认带「需确认」：命中的先放进待添加列表，你点「加入」才真正生效（防误加验证码类 bot）。\n\n"
              + "「排除管理」里可以拉黑整只 bot、写排除规则（支持正则，用 /.../ 包起来）。\n"
              + "被排除的 bot 不再学习、不再签到。");

        // ── 11 临时不想签 / 卡住了 ──
        tutCard(box, act, "11 · 临时不想签，或某个目标卡住了", "pause", false, ACC_LOG,
                "条目操作里：\n"
              + "· **暂停一周** —— 该目标一周内不动作，到期自动恢复。适合出差、bot 在维护。\n"
              + "· **冻结** —— 一直不签，直到你手动解冻。\n"
              + "· **忽略今天** —— 今天不再自动试，明天恢复。\n"
              + "· **重试** —— 立刻再发一次（会顺便清掉今天已用掉的重试次数）。\n\n"
              + "三者区别：暂停有期限、冻结没期限、忽略只管今天。");

        // ── 12 出问题怎么办 ──
        tutCard(box, act, "12 · 出问题怎么办", "flask", false, ACC_LOG,
                "按这个顺序查：\n\n"
              + "① 「自诊断」—— 检查宿主反射锚点是否正常。第三方客户端"
              + "（Nagram XF / Nagram / ExteraLess / Mercurygram 等）适配问题先看这里。\n"
              + "② 「回调调试台」—— 列出面板全部按钮，点任意一个实时发一次看机器人返回，"
              + "用来确认到底哪个按钮才是签到键。\n"
              + "③ 「运行日志」→「导出问题报告」—— 一键复制（错误 + 警告 + 最近记录 + 配置摘要），"
              + "粘贴给作者最省事。\n"
              + "④ 签到没反应？依次确认：通知开关、是否在签到时间内、该目标是否被暂停 / 冻结 / 排除、"
              + "以及详细档里有没有「面板未就绪」（→ 见第 3 节补前置命令）。");

        // ── 13 换手机 / 备份 ──
        tutCard(box, act, "13 · 换手机 / 备份", "save", false, ACC_LOG,
                "· **导出配置** —— 生成 json 到下载目录（含目标、设置、已签记录）\n"
              + "· **导入配置** —— 合并或覆盖，两种模式可选\n"
              + "· **导出日志** —— 把运行日志存到下载目录，方便留档\n"
              + "· **清空配置** —— 跨全部账号彻底清，注意这是不可撤销的\n\n"
              + "配置里**不含任何登录凭据**，可以放心备份。换成新手机后先装宿主（Telegram 或第三方客户端），"
              + "再装本模块并导入配置即可。");

        // ── 14 更新与反馈 ──
        tutCard(box, act, "14 · 更新与反馈", "refresh", false, ACC_LOG,
                "· 「检查更新」—— 走官方 GitHub 发布，检测到新版本可直接下载安装包\n"
              + "· 「加入群组」—— 反馈问题、提需求\n\n"
              + "更新不会动你的配置：版本号变了也照样保留目标与设置。");

        sv.addView(box);
        showDialog(act, Art.bold("TGAutoSign") + Lang.tr(" 使用教程"), sv, "关闭");
    }

    /** 教程卡片：可折叠的标题 + 正文（复用设置页 collapseCard 的观感）。 */
    private void tutCard(LinearLayout box, Activity act, String title, String icon,
                         boolean expanded, int accent, String body) {
        Object[] c = collapseCard(act, title, icon, expanded, accent);
        LinearLayout card = (LinearLayout) c[0];
        LinearLayout cbody = (LinearLayout) c[1];
        TextView bt = new TextView(act);
        bt.setTextSize(Theme.TS_SECOND);
        bt.setTextColor(Theme.termMuted(act));
        bt.setTypeface(android.graphics.Typeface.MONOSPACE);
        bt.setLineSpacing(Theme.dp(act, 2), 1f);
        bt.setText(Lang.tr(body));
        cbody.addView(bt);
        box.addView(card);
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
        // 真实槽位遍历：诊断页要能反映非连续槽位（用户报「账号3无目标」时靠它定位）
        for (int ai : accountSlots()) {
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

        // ── 越界模拟（多账号串号排障）──
        // 用途：登录 N 个账号时，宿主偶尔写入 selectedAccount=N（越界）。
        // 打开后模块把自己的读取值替换成 N，走**完全相同**的钳制逻辑，
        // 于是只有 2 个账号也能验证：解析到哪个账号、读哪个分区。
        // 只影响模块内部读取，不动宿主、不动任何已存数据。
        TextView sep = new TextView(act);
        sep.setTextSize(Theme.TS_CAPTION);
        sep.setTextColor(Theme.termFaint(act));
        sep.setPadding(0, Theme.dp(act, 10), 0, Theme.dp(act, 4));
        sep.setText(Lang.tr("── 多账号串号排查 ──"));
        box.addView(sep);

        addDiagRow(box, act, Lang.tf("真实读取：selectedAccount={0} → {1}",
                lastRawAccount, accountLabel(lastRawAccount)), lastRawAccount >= 0);

        final android.widget.Switch simSw = swRow(act, "越界模拟（强制 selectedAccount = 已登录数）", DEBUG_OVERFLOW_SIM);
        simSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton cb, boolean b) {
                DEBUG_OVERFLOW_SIM = b;
                prefs.edit().putBoolean("jmb_dbg_overflow", b).apply();
                int parsed = currentAccount();
                logw("【越界模拟】" + (b ? "开" : "关") + "：强制值=" + lastRawAccount
                     + " 解析=" + accountLabel(parsed) + "(acc" + parsed + "_)"
                     + " 已登录=" + activatedAccounts()
                     + (b ? "  ← 期望解析到最后一个账号" : ""));
                toast(b ? Lang.tf("模拟已开：解析为 {0}", accountLabel(parsed)) : Lang.tr("模拟已关"));
            }
        });
        box.addView(simSw);

        if (DEBUG_OVERFLOW_SIM) {
            int parsed = currentAccount();
            addDiagRow(box, act, Lang.tf("模拟生效：{0} 个账号 → 解析 {1}（acc{2}_，目标 {3} 个）",
                    activatedAccounts(), accountLabel(parsed), parsed, acctTargetCount(parsed)),
                    parsed == activatedAccounts() - 1);
        }

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

    /**
     * 列表类页面的统一内容高度（屏高比例）。
     *
     * 为什么要有这个常量（2026-10-03 用户反馈"目标列表窗口太小、跟别的页格格不入"）：
     *   对话框内容区上限是屏高的 0.72（见 showDialog 的 maxH），
     *   但**没有下限** —— 内容少就缩、内容多就撑到 0.72。
     *   而目标列表/日志页自己写死了 0.42，于是永远只有别人的六成高：
     *     日志页 0.42 + 顶部芯片 + 搜索 + 统计行 + 底部条 ≈ 0.60
     *     目标列表 0.42 + 过滤条 + 排序行 ≈ 0.55
     *     设置页（内容自然撑满）≈ 0.72
     *   三个页高度不同、切换时窗口"跳"一下，观感就是格格不入。
     *
     * 0.64 是实测过的折中：加上标题栏与底部按钮后总高约 0.78，
     * 在 360×792 的常见机型上不会顶到状态栏/导航栏，同时比 0.42 大一倍。
     */
    private static final float LIST_H_RATIO = 0.64f;

    /** 列表类页面的统一内容高度（像素）。 */
    private int listContentHeight(Activity act) {
        int h = 0;
        try { h = act.getResources().getDisplayMetrics().heightPixels; } catch (Throwable ignored) {}
        if (h <= 0) h = 1920;
        return (int) (h * LIST_H_RATIO);
    }

    private Object showDialog(Activity act, String title, View view, String negLabel) {
        // 导出模式：只记录页面 View 并让调用方尽快收手，不真正弹窗。
        // 取「本次页面构建的第一个 showDialog」，不靠标题匹配 ——
        // 页面标题会随语言变化（目标列表（11） / Targets (11)），匹配标题必然漏页。
        if (SHOT_MODE && !SHOT_DONE) {
            SHOT_DONE = true;
            SHOT_VIEW = view;
            return null;
        }
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
            // ── 入场淡入（2026-10-03）──
            // 切换分类时是"新建一个对话框、160ms 后关掉旧的"，
            // 两个窗口短暂共存；如果尺寸不同，视觉上就是"窗口一跳"。
            // 给新窗口加一段 140ms 淡入 + 轻微上移，让切换看起来是"换内容"
            // 而不是"关一个再开一个"。
            try {
                card.setAlpha(0f);
                card.setTranslationY(Theme.dp(act, 6));
                card.animate().alpha(1f).translationY(0f).setDuration(140)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                        .start();
            } catch (Throwable ignored) {}
            if (dlgFallbackLogged < 3) { dlgFallbackLogged++; logd("[对话框] 自绘卡片(自带滚动=" + nested + "): " + title); }
            pushDlg(dlg);
            return dlg;
        } catch (Throwable t2) {
            jlog("对话框显示失败: " + t2);
        }
        return null;
    }

    // ==================== 界面图导出（不截屏） ====================
    // 把面板页面当作普通 View 离屏渲染成 PNG：没有状态栏/导航栏，
    // 分辨率只由这里决定，不受设备屏幕与缩放影响。
    // 页面内容靠「拦截 showDialog」拿到 —— 各 show* 方法的构建逻辑一行都不用改。

    /** 导出目录：模块私有外部目录，走 root 取，不进相册。 */
    private java.io.File shotDir() {
        try {
            java.io.File d = appContext.getExternalFilesDir("tgautosign");
            if (d == null) return null;
            java.io.File o = new java.io.File(d, "shots");
            if (!o.isDirectory() && !o.mkdirs()) return null;
            return o;
        } catch (Throwable t) { return null; }
    }

    /** 退出导出模式并恢复主题。必须放在 finally，否则界面会卡在强制深浅色。 */
    private void shotEnd(int savedTheme) {
        SHOT_MODE = false;
        SHOT_TITLE = null;
        SHOT_VIEW = null;
        try { Theme.mode = savedTheme; } catch (Throwable ignored) {}
    }

    /** 允许 /jmb shots 3 指定密度倍率，默认 3（1080 宽的内容 → 3240px）。 */
    private float parseScale(String arg) {
        try {
            if (arg != null) {
                float v = Float.parseFloat(arg.trim());
                if (v >= 1f && v <= 4f) return v;
            }
        } catch (Throwable ignored) {}
        return 3f;
    }

    private String shotBaseName(String pageTitle) {
        if ("设置".equals(pageTitle)) return "settings";
        if ("目标列表".equals(pageTitle)) return "targets";
        return "main";
    }

    /**
     * 导出全部界面图：3 页 × {深色, 浅色}。
     * 构建发生在主线程的 show* 方法里，其入场动画都还没机会跑，渲染出来是稳定态；
     * 渲染与存盘在后台线程做，避免卡住界面。
     */
    private void exportShots(final Activity act, String scaleArg) {
        if (act == null) { toast(Lang.tr("请在 Telegram 界面内使用 /jmb")); return; }
        if (!SHOT_BUSY.compareAndSet(false, true)) {
            toast(Lang.tr("界面图正在导出，请稍候…"));
            return;
        }
        final float scale = parseScale(scaleArg);
        final int savedTheme = THEME_MODE;
        SHOT_MASK = true;
        shotNameMap.clear(); shotNameSeq = 0;
        try { synchronized (logBuffer) { SHOT_LOG_CUTOFF = logBuffer.size(); } } catch (Throwable ignored) {}
        shotTable = shotScrubTable();   // 先建脱敏表，再截断日志
        final String[] PAGES = { "TGAutoSign · 管理", "目标列表", "设置" };
        jlog("[导出] 开始：3 页 × 深浅色，密度 " + scale + "x");
        toast(Lang.tr("正在导出界面图…"));

        new Thread(new Runnable() { @Override public void run() {
            int ok = 0, fail = 0;
            try {
                for (int di = 0; di < 2; di++) {
                    final boolean dk = (di == 0);
                    for (int pi = 0; pi < PAGES.length; pi++) {
                        final String pt = PAGES[pi];
                        try {
                            final java.util.concurrent.atomic.AtomicReference<View> holder =
                                    new java.util.concurrent.atomic.AtomicReference<View>();
                            final java.util.concurrent.atomic.AtomicBoolean fired =
                                    new java.util.concurrent.atomic.AtomicBoolean(false);
                            mainHandler.post(new Runnable() { @Override public void run() {
                                shotOnePage(act, pt, dk, holder, fired);
                            } });
                            long deadline = System.currentTimeMillis() + 8000L;
                            while (!fired.get() && System.currentTimeMillis() < deadline) Thread.sleep(30L);
                            if (!fired.get()) { jlog("[导出] 超时未拿到页面: " + pt); fail++; continue; }
                            View v = holder.get();
                            if (v == null) { jlog("[导出] 页面未构建: " + pt); fail++; continue; }
                            java.io.File dir = shotDir();
                            if (dir == null) { jlog("[导出] 目录不可用"); fail++; continue; }
                            String fn = "shot-" + shotBaseName(pt) + (dk ? "-dark" : "-light") + ".png";
                            java.io.File f = new java.io.File(dir, fn);
                            if (renderViewToPng(v, scale, f, SHOT_BG)) {
                                ok++; jlog("[导出] OK " + fn + " " + f.length() + "B");
                            } else { fail++; jlog("[导出] 渲染失败: " + fn); }
                        } catch (Throwable t) { fail++; jlog("[导出] 异常 " + pt + ": " + t); }
                    }
                }
            } catch (Throwable t) {
                jlog("[导出] 整体失败: " + t);
            } finally {
                final int fOk = ok, fFail = fail;
                mainHandler.post(new Runnable() { @Override public void run() {
                    try { shotEnd(savedTheme); } catch (Throwable ignored) {}
                    SHOT_MASK = false;
                    SHOT_LOG_CUTOFF = -1;
                    shotTable = null;
                    SHOT_BUSY.set(false);
                    toast(Lang.tf("界面图导出完成：成功 {0} 张，失败 {1} 张", fOk, fFail));
                } });
            }
        } }).start();
    }

    /** 在主线程构建某一页并交出它的 View（不渲染、不保存）。 */
    private void shotOnePage(Activity act, String pageTitle, boolean dark,
                             java.util.concurrent.atomic.AtomicReference<View> holder,
                             java.util.concurrent.atomic.AtomicBoolean fired) {
        int savedTheme = THEME_MODE;
        try {
            SHOT_MODE = true;
            SHOT_DONE = false;      // 每页都要重置：否则第二页起永远不被截获
            SHOT_TITLE = pageTitle;
            SHOT_VIEW = null;
            Theme.mode = dark ? 2 : 1;   // 1=始终日间 2=始终夜间；dark() 直接采信，绕过缓存
            // 底色必须在主线程、主题生效时取：渲染在子线程做，那时主题已复位
            SHOT_BG = Theme.termCardDeep(act);
            buildPageByTitle(act, pageTitle);
            holder.set(SHOT_VIEW);
        } catch (Throwable t) {
            jlog("[导出] buildPage 失败 " + pageTitle + ": " + t);
            holder.set(null);
        } finally {
            SHOT_MODE = false;
            SHOT_VIEW = null;
            SHOT_TITLE = null;
            try { Theme.mode = savedTheme; } catch (Throwable ignored) {}
            fired.set(true);
        }
    }

    /** 按标题打开对应页面 —— 复用 runAction 那一套，不另写构建代码。 */
    private void buildPageByTitle(Activity act, String title) {
        if ("设置".equals(title)) { showSettings(act); return; }
        if ("目标列表".equals(title)) { showList(act); return; }
        showMainMenu(act);
    }

    /**
     * 离屏渲染：测量 → 布局 → 绘制到 Bitmap。
     * 不经过窗口系统，也不使用任何截屏 API。
     * 宽度按真实对话框的卡片宽度算（屏宽 92%，上限 400dp），
     * 所以导出的图和用户实际看到的面板一样宽。
     */
    /**
     * 裁掉与底色相同的边缘：对话框卡片比画布窄，四周会留一圈底色。
     * 直接从四边往内扫，遇到第一行/列与底色不同的像素即为边界。
     */
    private Bitmap cropToContent(Bitmap src, int bg) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            int tol = 6;
            int br = (bg >> 16) & 0xFF, bgc = (bg >> 8) & 0xFF, bb = bg & 0xFF;
            int[] row = new int[w];
            int top = 0, bottom = h - 1, left = 0, right = w - 1;
            boolean found;
            // top
            found = false;
            for (int y = 0; y < h && !found; y++) {
                src.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) {
                    int c = row[x];
                    if (Math.abs(((c >> 16) & 0xFF) - br) > tol
                            || Math.abs(((c >> 8) & 0xFF) - bgc) > tol
                            || Math.abs((c & 0xFF) - bb) > tol) { top = y; found = true; break; }
                }
            }
            // bottom
            found = false;
            for (int y = h - 1; y >= 0 && !found; y--) {
                src.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) {
                    int c = row[x];
                    if (Math.abs(((c >> 16) & 0xFF) - br) > tol
                            || Math.abs(((c >> 8) & 0xFF) - bgc) > tol
                            || Math.abs((c & 0xFF) - bb) > tol) { bottom = y; found = true; break; }
                }
            }
            // left / right（逐列抽样即可）
            int[] col = new int[h];
            found = false;
            for (int x = 0; x < w && !found; x++) {
                src.getPixels(col, 0, 1, x, 0, 1, h);
                for (int y = 0; y < h; y++) {
                    int c = col[y];
                    if (Math.abs(((c >> 16) & 0xFF) - br) > tol
                            || Math.abs(((c >> 8) & 0xFF) - bgc) > tol
                            || Math.abs((c & 0xFF) - bb) > tol) { left = x; found = true; break; }
                }
            }
            found = false;
            for (int x = w - 1; x >= 0 && !found; x--) {
                src.getPixels(col, 0, 1, x, 0, 1, h);
                for (int y = 0; y < h; y++) {
                    int c = col[y];
                    if (Math.abs(((c >> 16) & 0xFF) - br) > tol
                            || Math.abs(((c >> 8) & 0xFF) - bgc) > tol
                            || Math.abs((c & 0xFF) - bb) > tol) { right = x; found = true; break; }
                }
            }
            if (right <= left || bottom <= top) return src;
            jlog("[导出] 裁边 (" + left + "," + top + ")-(" + right + "," + bottom + ") 画布 " + w + "x" + h);
            return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1);
        } catch (Throwable t) {
            jlog("[导出] 裁边失败: " + t);
            return src;
        }
    }

    private boolean renderViewToPng(View root, float scale, java.io.File out, int bg) {
        Bitmap bmp = null;
        try {
            Context c = root.getContext();
            android.util.DisplayMetrics dm = root.getResources().getDisplayMetrics();
            int cardW = Math.min((int) (dm.widthPixels * 0.92f), Theme.dp(c, 400));
            root.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = root.getMeasuredHeight();
            if (cardW < 32 || h < 32) { jlog("[导出] 尺寸异常 " + cardW + "x" + h); return false; }
            bmp = Bitmap.createBitmap((int) (cardW * scale), (int) (h * scale), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            cv.scale(scale, scale);
            // 底色用主线程取好的值铺满：页面卡片比画布窄（对话框宽 92% 屏宽），
            // 两侧与上下会留透明边，直接存 PNG 会变成黑边。
            cv.drawColor(bg);
            root.layout(0, 0, cardW, h);
            root.draw(cv);
            // 再裁到实际有内容的矩形，去掉透明边距
            Bitmap outBmp = cropToContent(bmp, bg);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            outBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush(); fos.close();
            if (outBmp != bmp) outBmp.recycle();
            return true;
        } catch (Throwable t) {
            jlog("[导出] render 失败: " + t);
            return false;
        } finally {
            try { if (bmp != null) bmp.recycle(); } catch (Throwable ignored) {}
        }
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
            if (findEntryById(id, accountOfPrefix(prefix)) == null) return false;   // 条目已删 → 残留的 snooze_ 不生效
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
        // /jmb shots [倍率] —— 导出 README 用界面图（不透屏、不受屏幕尺寸影响）
        if (sub.equals("shots") || sub.startsWith("shots ") || sub.equals("导出界面图")) {
            String arg = sub.length() > 5 ? sub.substring(5).trim() : null;
            exportShots(lastActivity, arg);
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
            toastAfterSign(sendSign(m, currentAccount(), true), m);  // 2026-09-30: manual=true 绕过闸门
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
                + Lang.tf("\n\n{0} 现在有 {1} 个目标。", accountLabel(currentAccount()), acctTargetCount(currentAccount())));
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
                + (rep.skipped > 0 ? "（跳过 " + rep.skipped + " 个）" : "")
                + " · " + accountLabel(currentAccount()) + " 目标 " + before + " → " + after;
        logs(line);

        // 导入后的引导（2026-09-30）：
        // 实测用户反馈「导入后没数据」—— 实际是**配置属于另一个账号**：
        //   备份里是 acc0_*（账号1），而用户当前在账号2，导入到账号1、账号2 自然仍是 0。
        //   原实现只报「目标 0 → 0」，看起来像失败，用户无从判断问题在哪。
        // 现在主动扫一遍各账号，把「哪些账号有数据」明明白白说出来。
        try {
            int cur = currentAccount();
            StringBuilder other = new StringBuilder();
            int curN = 0;
            for (int i : accountSlots()) {
                int c = 0;
                try {
                    java.util.List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
                    loadTargetsInto(accountPrefix(i), l);
                    c = l.size();
                } catch (Throwable ignored) {}
                if (i == cur) { curN = c; continue; }
                if (c > 0) {
                    if (other.length() > 0) other.append("、");
                    other.append(accountLabel(i)).append(" ").append(c).append(" 个");
                }
            }
            if (curN == 0 && other.length() > 0) {
                toast(Lang.tf("导入完成。当前账号（{0}）没有目标，但 {1} 有 —— 切过去看看",
                        accountLabel(cur), other.toString()));
                logw("【导入】当前账号无目标；其余账号有：" + other);
            } else if (curN > 0) {
                toast(Lang.tf("导入完成：{0} 目标 {1} → {2}", accountLabel(cur), before, curN));
            } else {
                toast(Lang.tf("导入完成：写入 {0} 个键", rep.keys));
            }
        } catch (Throwable _eImp) { noteSwallowed("doImportNow(hint)", _eImp); }
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

            sectionHeader(box, act, "▍待添加");
            final int pendingAcc = currentAccount();
            final java.util.List<Long> pd = pendingConfirmDids(pendingAcc);
            TextView pcLab = new TextView(act); pcLab.setTextSize(Theme.TS_CAPTION); pcLab.setTextColor(Theme.termFaint(act)); pcLab.setTypeface(Theme.text());
            pcLab.setText(pd.isEmpty() ? Lang.tr("(无待添加目标)") : Lang.tf("待添加 {0} 个", pd.size()));
            pcLab.setPadding(dp(4), dp(2), dp(4), dp(4));
            box.addView(pcLab);
            Button pcBtn = mkBtn(act); withIconText(act, pcBtn, "check", "处理待添加");
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
            final int pendingAcc = currentAccount();
            final java.util.List<Long> dids = pendingConfirmDids(pendingAcc);
            final java.util.List<String> texts = pendingConfirmTexts(pendingAcc);
            if (dids.isEmpty()) {
                TextView e = new TextView(act); e.setTextSize(Theme.TS_BODY); e.setTextColor(Theme.termMuted(act)); e.setTypeface(Theme.text());
                e.setText(Lang.tr("(待添加列表为空)\n网络学习命中且「需确认」开启时，这里会出现候选目标。"));
                e.setPadding(dp(8), dp(12), dp(8), dp(12));
                box.addView(e);
            } else {
                /* top hint (2026-09-29 enhanced) */
                TextView hint = new TextView(act); hint.setTextSize(Theme.TS_CAPTION); hint.setTextColor(Theme.termFaint(act)); hint.setTypeface(Theme.text());
                hint.setText(Lang.tr("以下目标来自网络学习，确认后加入自动签到。点「加入」确认，点「忽略」丢弃。"));
                hint.setPadding(dp(4), dp(4), dp(4), dp(8));
                box.addView(hint);
                for (int i = 0; i < dids.size(); i++) {
                    final long did = dids.get(i);
                    final String text = i < texts.size() ? texts.get(i) : "";

                    /* card layout matching the rest of the UI */
                    LinearLayout card = new LinearLayout(act);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x22)));
                    card.setPadding(dp(12), dp(10), dp(12), dp(10));

                    /* row 1: bot name */
                    TextView nm = new TextView(act);
                    nm.setTextSize(Theme.TS_SUBTITLE);
                    nm.setTextColor(Theme.termTxt(act));
                    nm.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                    nm.setText(targetTitle(did));
                    nm.setSingleLine(true);
                    nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    card.addView(nm);

                    /* row 2: button/command text */
                    if (text.length() > 0) {
                        TextView tx = new TextView(act);
                        tx.setTextSize(Theme.TS_CAPTION);
                        tx.setTextColor(Theme.termMuted(act));
                        tx.setTypeface(android.graphics.Typeface.MONOSPACE);
                        tx.setSingleLine(true);
                        tx.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        tx.setText(text.trim().replace("\n", " "));
                        tx.setPadding(0, dp(2), 0, 0);
                        card.addView(tx);
                    }

                    /* row 3: id (small, dim) */
                    TextView didLabel = new TextView(act);
                    didLabel.setTextSize(Theme.TS_CAPTION);
                    didLabel.setTextColor(Theme.termFaint(act));
                    didLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
                    didLabel.setText("ID: " + did);
                    didLabel.setPadding(0, dp(2), 0, dp(6));
                    card.addView(didLabel);

                    /* row 4: two action buttons */
                    LinearLayout acts = new LinearLayout(act);
                    acts.setOrientation(LinearLayout.HORIZONTAL);
                    acts.setGravity(Gravity.CENTER_VERTICAL);

                    Button acc = mkBtn(act); withIconText(act, acc, "check", "加入");
                    acc.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                        if (pendingConfirmAccept(pendingAcc, did, text)) { toast("已加入"); dismissOne(curDlg[0]); showPendingConfirm(act); }
                        else toast("加入失败");
                    } });
                    acts.addView(acc);
                    acts.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(dp(8), 1));

                    Button ign = mkBtn(act); withIconText(act, ign, "x", "忽略");
                    ign.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
                        pendingConfirmRemove(pendingAcc, did); toast("已忽略"); dismissOne(curDlg[0]); showPendingConfirm(act);
                    } });
                    acts.addView(ign);

                    card.addView(acts);
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                    clp.setMargins(0, dp(3), 0, dp(3));
                    card.setLayoutParams(clp);
                    box.addView(card);
                }
            }
            curDlg[0] = showDialog(act, Lang.tf("待添加（{0}）", dids.size()), box, "关闭");
        } catch (Throwable t) { toast(Lang.tf("打开失败: {0}", t)); }
    }

    /** 目标列表里：重填行（原地刷新用，不重建对话框）。 */
    private void fillTargetRows(Activity act) {
        try {
            if (listRowsHost == null) return;
            listRowsHost.removeAllViews();
            String today = todayStr();
            int shownN = 0, filtN = 0;
            for (Map<String, Object> m : sortedTargets()) {
                String st = statusOf(accountPrefix(), entryId(m), today);
                if (!listFilterMatch(m, st, today)) { filtN++; continue; }
                targetRow(listRowsHost, m, st, "more");
                shownN++;
            }
            if (shownN == 0) {
                emptyView(listRowsHost, filtN > 0
                        ? Lang.tf("当前筛选（{0}）下没有目标", Lang.tr(listFilter))
                        : Lang.tr("(暂无目标，点「添加目标」，或直接点 bot 的签到按钮自动学习)"));
            }
        } catch (Throwable t) { noteSwallowed("fillTargetRows", t); }
    }

    /** 重染筛选 chip（原地刷新用）。顺序与 FILTERS 一致。 */
    private void refreshFilterChips(Context act) {
        try {
            String[] FILTERS = {"全部", "待签", "已签", "需处理", "冻结"};
            for (int i = 0; i < listFilterChips.size() && i < FILTERS.length; i++) {
                styleFilterChip(listFilterChips.get(i), act, FILTERS[i].equals(listFilter));
            }
        } catch (Throwable t) { noteSwallowed("refreshFilterChips", t); }
    }

    /** 筛选 chip 的选中/未选中外观（一处定义，原地刷新与首次构建共用）。 */
    private void styleFilterChip(TextView chip, Context c, boolean on) {
        try {
            chip.setTextColor(on ? Theme.termCardDeep(c) : Theme.termMuted(c));
            chip.setBackground(termBorder(c,
                    on ? Theme.termCyan(c) : Theme.termCard(c),
                    Theme.withAlpha(Theme.termCyan(c), on ? 0xAA : 0x33)));
        } catch (Throwable t) { noteSwallowed("styleFilterChip", t); }
    }

    private void showList(Activity act) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        // 待确认池入口（网络学习需确认时产生）
        final java.util.List<Long> pd = pendingConfirmDids(currentAccount());
        if (pd.size() > 0) {
            LinearLayout pc = new LinearLayout(act); pc.setOrientation(LinearLayout.HORIZONTAL); pc.setGravity(Gravity.CENTER_VERTICAL);
            pc.setBackground(termBorder(act, Theme.withAlpha(Theme.termAmber(act), 0x0E), Theme.withAlpha(Theme.termAmber(act), 0x50)));
            pc.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(-1, -2);
            pclp.setMargins(0, dp(2), 0, dp(8));
            pc.setLayoutParams(pclp);
            TextView pct = new TextView(act); pct.setTextSize(Theme.TS_BODY); pct.setTextColor(Theme.termAmber(act)); pct.setTypeface(Theme.monoBold());
            pct.setText(Lang.tf("待添加 {0} 个（点此处理）", pd.size()));
            pc.addView(pct, new LinearLayout.LayoutParams(0, -2, 1f));
            pc.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ showPendingConfirm(act); } });
            box.addView(pc);
        }
        if (targets.size() == 0) {
            emptyView(box, "(暂无目标，点「添加目标」，或直接点 bot 的签到按钮自动学习)");
        }
        // ── 待处理聚合条（2026-09-28）──
        // 取代原来内联在每个目标行里的三个按钮：列表保持干净的两行结构，
        // 需要处置的集中在顶部一个入口（像通知，不打扰，想处理时点进去）。
        try {
            final int needN = countNeedsAttention(accountPrefix());
            if (needN > 0) {
                LinearLayout nb = new LinearLayout(act);
                nb.setOrientation(LinearLayout.HORIZONTAL);
                nb.setGravity(Gravity.CENTER_VERTICAL);
                nb.setPadding(dp(12), dp(10), dp(10), dp(10));
                nb.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termAmber(act), 0x40)));
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
                nlp.setMargins(0, dp(2), 0, dp(8));
                nb.setLayoutParams(nlp);

                TextView nt = new TextView(act);
                nt.setTextSize(Theme.TS_SECOND);
                nt.setTextColor(Theme.termAmber(act));
                nt.setTypeface(Theme.text());
                nt.setText(Lang.tf("{0} 个目标需要处理", needN));
                nb.addView(nt);
                nb.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(0, 1, 1f));

                Button nbBtn = mkBtn(act);
                nbBtn.setText(Lang.tr("处理"));
                nbBtn.setTextSize(Theme.TS_CAPTION);
                nbBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    try {
                        Object od = listDialog;
                        listDialog = null;
                        scheduleDismiss(od);
                        // 直接用本次 showList 拿到的 act，不走 lastActivity ——
                        // 后者会随 Activity 生命周期被清空（onDestroy 里置 null），
                        // 而这里是同步回调，手上的 act 一定有效。
                        showPendingWork(act);
                    } catch (Throwable ignored) {}
                } });
                nb.addView(nbBtn);
                box.addView(nb);
            }
        } catch (Throwable _eNB) { noteSwallowed("showList(pendingBar)", _eNB); }

        // ── 搜索 + 状态筛选（2026-10-03）──
        // 目标一多，满屏"待签"找不着北。搜索按标题/指令/@用户名匹配，
        // 筛选按状态分组，两者都是**纯渲染层过滤**，不碰任何签到逻辑。
        final LinearLayout filterRow = new LinearLayout(act);
        if (targets.size() > 0) {
            filterRow.setOrientation(LinearLayout.VERTICAL);

            final EditText qEd = new EditText(act);
            qEd.setSingleLine(true);
            qEd.setTextSize(Theme.TS_SECOND);
            qEd.setHint(Lang.tr("搜索目标（名称 / 指令 / @用户名）"));
            qEd.setTextColor(Theme.termTxt(act));
            qEd.setHintTextColor(Theme.termFaint(act));
            qEd.setTypeface(android.graphics.Typeface.MONOSPACE);
            qEd.setText(logTargetFilter == null ? "" : logTargetFilter);
            // 输入法回车即应用搜索（不逐字重建列表 —— 那会打断输入、丢焦点）。
            qEd.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            qEd.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ev) {
                    logTargetFilter = String.valueOf(qEd.getText()).trim();
                    dismissOne(listDialog);
                    showList(lastActivity);
                    return true;
                }
            });
            filterRow.addView(qEd);

            LinearLayout fbar = new LinearLayout(act);
            fbar.setOrientation(LinearLayout.HORIZONTAL);
            fbar.setPadding(dp(2), dp(4), dp(2), dp(2));
            final String[] FILTERS = {"全部", "待签", "已签", "需处理", "冻结"};
            listFilterChips.clear();
            for (final String f : FILTERS) {
                final TextView chip = new TextView(act);
                chip.setTextSize(Theme.TS_CAPTION);
                chip.setTypeface(android.graphics.Typeface.MONOSPACE);
                chip.setPadding(dp(10), dp(5), dp(10), dp(5));
                chip.setText(Lang.tr(f));
                styleFilterChip(chip, act, f.equals(listFilter));
                LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(-2, -2);
                clp2.setMargins(0, 0, dp(6), 0);
                chip.setLayoutParams(clp2);
                chip.setClickable(true);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // ── 原地刷新（2026-10-03 修"切筛选会一闪"）──
                        // 改前：dismissOne + showList —— 关掉整个对话框再重建，
                        //   观感是"窗口闪一下又长出来"，用户反馈"不平滑不圆润"。
                        // 现在：只重填列表区 + 重染色 chip，窗口本身不动。
                        if (f.equals(listFilter)) return;
                        listFilter = f;
                        refreshFilterChips(act);
                        fillTargetRows(act);
                    }
                });
                listFilterChips.add(chip);
                fbar.addView(chip);
            }
            filterRow.addView(fbar);
            box.addView(filterRow);
        }

        // 排序切换
        if (targets.size() > 0) {
            LinearLayout bar = new LinearLayout(act); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(2), dp(2), dp(2), dp(6));
            TextView lab = new TextView(act); lab.setText(Lang.tr("排序")); lab.setTextSize(Theme.TS_CAPTION); lab.setTextColor(Theme.termMuted(act)); lab.setTypeface(android.graphics.Typeface.MONOSPACE); lab.setPadding(0, 0, dp(8), 0);
            bar.addView(lab);
            listSortChips.clear();          // 重建前清空，避免旧对话框的引用累积
            bar.addView(sortChip(act, "未签置顶", "unsigned"));
            bar.addView(sortChip(act, "按名称", "name"));
            // 「最近失败」——上面代码注释里写着这个排序"计划后面加"，
            // 现在补上：失败多的排前面，方便先处理真正有问题的目标。
            bar.addView(sortChip(act, "最近失败", "fails"));
            box.addView(bar);
        }
        // ── 列表区：固定高度（2026-10-03 修"筛选一下框就变小"）──
        // 改前目标行是直接加进 box、box 再交给对话框 —— 而对话框高度随内容自适应，
        // 于是从"全部（11 行）"切到"需处理（0 行）"时，整个框会**塌成一个小方块**，
        // 视觉上像界面坏了。日志页早就是固定高度（屏高 42%），这里对齐同一做法：
        // 行放进一个定高 ScrollView，无论筛出几条，框的大小都不变。
        ScrollView listSv = new ScrollView(act);
        listSv.setVerticalScrollBarEnabled(true);
        LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listSv.addView(listBox, new android.widget.ScrollView.LayoutParams(-1, -2));

        listRowsHost = listBox;          // 供筛选/排序原地刷新
        fillTargetRows(act);
        int listH = listContentHeight(act);
        box.addView(listSv, new LinearLayout.LayoutParams(-1, listH));

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
        java.util.Calendar c = java.util.Calendar.getInstance();
        int n = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        // 用支持跨天的解析：以前这里走 windowRange()，而它对跨天窗口（如 22:00-02:00）
        // 返回 null → start 退化成 0 → MISS_DEADLINE >= 0 恒成立 → 判成"全天都是补签时段"。
        // 同文件里的 inWindow() 早就换成了 windowRangeAny，这里当时漏改。
        // 现在直接委托 SignLogic.inMissBackTime（纯函数、有单测覆盖），消除双实现。
        int[] any = SignLogic.windowRangeAny(WINDOW);
        int[] r = any != null ? new int[]{any[0], any[1]} : null;
        return SignLogic.inMissBackTime(n, r, MISS_DEADLINE, true);
    }

    private String nowHM() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /** 今天 00:00 的毫秒时间戳。把「计划分钟数」换算成绝对时刻时用。 */
    private long startOfTodayMs() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
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

    /** 判断日期字符串是否昨天（2026-09-30） */

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
                // ── 双重过滤（2026-09-30 修）──
                // 起因（用户实测）：清空配置 → 全空 → 导入旧配置 → 日历只亮一个旧日期（9-24）。
                //   last_ 只存「最近一次签到」，导入旧配置会把它恢复成历史日期；
                //   而日历把 last_ 当签到史读，于是那个孤零零的旧日期就成了唯一绿格。
                // ① 格式校验：只接受严格 yyyy-MM-dd
                //    （历史版本／异常路径可能残留非日期内容，会渲染成假绿格）
                // ② 时间窗校验：只认最近 14 天
                //    last_ 的语义是「最近一次」，超过两周一定是导入/残留的过期值 ——
                //    正常每天签到的话，last_ 永远落在窗口内。
                if (isYmd(d) && isWithinDays(d, 14)) s.add(d);
            }
        } catch (Throwable ignored) {}

        // ── 回填来源②同样做格式校验 ──
        // 回填来源②：连续签到数据（last_sign_date + streak 天往前推）。
        // last_ 只保留最近一次，历史日期会丢；连续段能反映真实签到史，用它补上昨/前天。
        try {
            String lsd = prefs.getString(kLastSignDate(prefix), "");
            // ── 关键修正（2026-09-30）──
            // 原实现直接读 kStreak 的**原始值**，而 streakOf() 会先判断
            // 「last_sign_date 是不是今天/昨天，不是就返回 0」。
            // 两者读同一个键却口径不同 —— 一旦用户隔了几天没签，
            //   kStreak 里还留着旧的连续天数（如 10），streakOf() 已归 0，
            //   回填②却仍按 10 天往前刷 —— 于是出现截图里的怪象：
            //   「连续 0 天」的标题下，日历却亮着连续 10 个绿格。
            // 现在统一走 streakOf()，标题与格子永远同源。
            int st = streakOf(prefix);
            if (lsd != null && lsd.length() > 0 && st > 0) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                java.util.Date d0 = f.parse(lsd);
                if (d0 != null) {
                    java.util.Calendar cc = java.util.Calendar.getInstance();
                    cc.setTime(d0);
                    for (int i = 0; i < Math.min(st, 180); i++) {
                        String dd = f.format(cc.getTime());
                        if (isYmd(dd)) s.add(dd);
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

    /** 两色线性插值：t=0 取 a，t=1 取 b。用于闪电"放电"时的颜色游走。 */
    private static int blendArgb(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int) (aa + (ba - aa) * t);
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    /**
     * 可折叠分区卡片（2026-09-30）。
     *
     * 为什么需要：设置页原本是 5 个分区、约 20 个控件一路平铺，实测要划 5~6 屏，
     * 且保存按钮在最底部，改完上面某项得一路划下去才能保存。
     * 折起来之后首屏能看全所有分区标题，只展开真正要改的那一块。
     *
     * 返回一个容器：调用方把子视图加进 [1]（内容区），[0] 是标题行。
     */
    private Object[] collapseCard(Activity act, String title, String icon,
                                  boolean expanded, int accent) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(accent, 0x26)));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, dp(2), 0, dp(6));
        card.setLayoutParams(clp);

        // 标题行：图标 + 标题 + 摘要 + 箭头
        final LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(act);
        tv.setTextSize(Theme.TS_BODY);
        tv.setTextColor(Theme.termTxt(act));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setText(Lang.tr(title));
        android.graphics.drawable.Drawable ic = Icons.d(act, icon, 13f, accent);
        if (ic != null) {
            int sz = dp(13);
            ic.setBounds(0, 0, sz, sz);
            tv.setCompoundDrawables(ic, null, null, null);
            tv.setCompoundDrawablePadding(dp(6));
        }
        head.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = new TextView(act);
        arrow.setTextSize(Theme.TS_SECOND);
        arrow.setTextColor(Theme.termMuted(act));
        arrow.setGravity(android.view.Gravity.CENTER);
        arrow.setPadding(dp(8), dp(2), dp(2), dp(2));
        arrow.setText(expanded ? "▾" : "▸");
        head.addView(arrow, new LinearLayout.LayoutParams(-2, -2));

        // 内容区
        final LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        body.setPadding(0, dp(6), 0, 0);

        head.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.setText(show ? "▾" : "▸");
        } });

        card.addView(head);
        card.addView(body);
        return new Object[]{card, body, head, null};
    }

    /**
     * 折叠卡标题行的右侧摘要（收起时也能看到当前值）。
     * 插在「箭头」之前，保证箭头永远最右。
     */
    private TextView cardSummary(Activity act, LinearLayout head, int accent) {
        TextView sm = new TextView(act);
        sm.setTextSize(Theme.TS_CAPTION);
        sm.setTextColor(Theme.withAlpha(accent, 0xC8));
        sm.setTypeface(Theme.text());
        sm.setSingleLine(true);
        sm.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.RIGHT);
        sm.setMaxWidth(dp(140));
        sm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sm.setPadding(0, 0, dp(6), 0);
        int cnt = head.getChildCount();
        head.addView(sm, Math.max(0, cnt - 1), new LinearLayout.LayoutParams(-2, -2));
        return sm;
    }

    // ══════════════════════════════════════════════════════════════════
    //  日志「说人话」层（2026-09-30）
    //
    //  起因：日志是照开发排障需要写的，直接把内部术语摊给用户：
    //    「目标列表自动增殖：模块自身发起的回调被误判为用户学习」
    //    「uid=-1001234567890 text=/checkin」
    //  用户看不懂，也不知道该不该管。
    //
    //  做法：默认「易懂」档，把日志翻译成一句话结论 + 人类可读的目标名；
    //        需要排障时切到「详细」档，看原始日志。
    //  注意：只做展示层翻译，不改动落盘内容 —— 导出与诊断包仍是原始日志。
    // ══════════════════════════════════════════════════════════════════

    /** 日志显示档位：true=易懂（默认） false=详细（原始） */
    private boolean logPlain = true;

    /** 内部机制类日志：只在详细档显示，易懂档直接隐去（用户无需关心） */
    private static boolean isInternalLog(String m) {
        if (m == null) return false;
        // 开发期排障信息
        if (m.contains("自动增殖") || m.contains("归类码") || m.contains("被误判")
            || m.contains("网络层学习") || m.contains("sweep") || m.contains("指纹")
            || m.contains("迁移") || m.contains("槽位") || m.contains("接线")
            || m.contains("落盘") || m.contains("心跳") || m.contains("冷启动")
            || m.contains("实例") || m.contains("hook ") || m.contains("反射")) return true;
        // ── 界面/对话框实现细节（2026-09-30 补）──
        // 截图实测：主页「最近动态」里出现
        //   「[对话框] 自绘卡片(自带滚动=true): 运行日志」
        //   「[对话框] 自绘卡片(自带滚动=false): 目标列表 (6)」
        // 这些是自绘对话框的实现日志，用户看到「自绘卡片」「自带滚动」完全不知所云，
        // 而且它们**总是**排在最近几行，把真正有用的签到动态挤出去。
        if (m.contains("[对话框]") || m.contains("对话框")) return true;
        if (m.contains("自绘") || m.contains("原生对话框")) return true;
        if (m.contains("[界面]") || m.contains("窗口") && m.contains("堆叠")) return true;
        // 装饰性/纯提示类
        if (m.contains("[去重]") || m.contains("跳过重复信号")) return true;
        if (m.startsWith("[通知]") && m.contains("暂不发汇总")) return true;
        // ── 内部调度（2026-09-30 补）──
        // 截图实测：「[排队] 打开聊天 合并到已有排队任务（52 秒前已排）」反复出现，
        //   用户问「排队那玩意是干啥的」—— 它是模块为避免同时开多个会话而做的串行调度，
        //   属于内部实现，用户知道与否都不影响使用。
        if (m.contains("[排队]") || m.contains("打开聊天") || m.contains("合并到已有排队")) return true;
        // 用户自己发的管理命令，无需回显
        if (m.contains("[界面] 收到管理命令") || m.contains("收到管理命令")) return true;
        // 主页统计卡刷新等纯界面动作
        if (m.contains("[面板] 更新 uid=")) return true;
        // ── 启动横幅（2026-10-03 聚合）──
        // 每次冷启写 5 行（版本/宿主/注入/账号/使用），一天重启几十次就是几百行，
        // 把真正的签到动态全挤走（实测 3076 行里约 350 行是它）。
        // 易懂档只留"已启动"一条；详情在「详细」档完整可见。
        if (m.startsWith("宿主:") || m.startsWith("注入:") || m.startsWith("账号:")
                || m.startsWith("使用:")) return true;
        // ── 账号实况 / 主题判定（每次刷新都打，纯诊断）──
        if (m.contains("账号实况:") || m.contains("selectedAccount=")) return true;
        if (m.contains("主题判定:") || m.contains("主题来源") || m.contains("来源=ui-color")) return true;
        // ── 超时/在途的内部细节（2026-10-03）──
        // 「收到 BOT_RESPONSE_TIMEOUT —— 本轮请求 kind=cb(有前置命令) 发出于 15 秒前」
        // 紧跟的下一行才是结论（"机器人没回复，稍后自动重试"），这条纯属重复。
        if (m.contains("BOT_RESPONSE_TIMEOUT")) return true;
        if (m.contains("前置命令已全部发出") || m.contains("等待面板刷新")) return true;
        // ── 防重复发的内部记账（2026-10-03）──
        // 「已有请求在途/待结论，不重排」「跳过排期」讲的是"我没重复发"。
        // 实测一天 60+ 条，对用户不是信息（出问题看详细档即可）。
        if (m.contains("不重排") || m.contains("跳过排期")) return true;
        // ── 启动维护（2026-10-03）──
        // 每次冷启都跑一次的内部卫生动作，结论永远是"清掉 N 个残留"，
        // 用户既无法行动也无需知道（真出问题在详细档可见）。
        if (m.contains("清理孤儿状态键") || m.contains("清理跨天残留")
                || m.contains("清理历史遗留配置") || m.contains("清理更新残留")) return true;
        // ── 未识别候选（"不含签到关键词，不自动添加"）──
        // 每次有人发言就判一次，实测上百条，而结论永远是"不加"。
        // 用户真正关心的是"学到了什么"（那条会作为"发现新目标"显示）。
        if (m.contains("不含签到关键词") || m.contains("非bot，不自动添加")) return true;
        return false;
    }

    /**
     * 把一条原始日志翻译成用户能懂的一句话。
     * 返回 null 表示「这条不该给用户看」（内部机制）。
     */
    /**
     * 易懂档的文案映射。
     *
     * 返回 {图标名, 文案}；返回 null 表示不该给用户看。
     * 图标名走 Icons 的矢量集（与模块主题一致，深浅色自动跟随），
     * 不用 emoji —— emoji 在不同宿主/系统上字形差异大，且与终端风界面不搭。
     * 2026-09-30 由 emoji 改为矢量图标（用户反馈「图标生硬、不符合主题」）。
     */
    private String[] plainLogParts(String m) {
        if (m == null || m.length() == 0) return null;
        // ── 结果缓存（2026-10-03 修"点开日志卡一下"）──
        // 同一条日志在一次渲染里会被翻译 4 遍：
        //   renderLog 过滤 1 遍 → foldShow 折叠 1 遍 → logRowAt 取折叠键 1 遍
        //   → logRow 真正渲染 1 遍
        // 而每次翻译要跑 isInternalLog（约 40 个 contains）+ 若干正则。
        // 20000 行 × 4 遍 = 8 万次字符串扫描，全部发生在主线程 → 可见卡顿。
        // 日志行是**不可变文本**，翻译结果天然可缓存。
        String[] hit = plainCache.get(m);
        if (hit != null) return hit.length == 0 ? null : hit;   // 空数组代表"译不出"
        String[] res = plainLogPartsUncached(m);
        try {
            if (plainCache.size() > 4000) plainCache.clear();   // 有界，防长跑涨内存
            plainCache.put(m, res == null ? new String[0] : res);
        } catch (Throwable ignored) {}
        return res;
    }

    /** 翻译结果缓存：key = 原始日志文本，value = 结果（空数组表示"译不出/该隐藏"）。 */
    private final java.util.LinkedHashMap<String, String[]> plainCache =
            new java.util.LinkedHashMap<String, String[]>(512, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String[]> e) {
                    return size() > 4000;
                }
            };

    private String[] plainLogPartsUncached(String m) {
        String s = m.trim();
        if (isInternalLog(s)) return null;
        // 折叠键（2026-10-03）：同一条重复文案（如"不重排"刷 12 次）
        // 由界面层按 uid 计数合并，这里只负责给出"按什么折叠"。
        String foldKey = foldKeyOf(s);

        // 成功
        if (s.contains("回复判定") && (s.contains("成功") || s.contains("命中成功")))
            return new String[]{"check", Lang.tr("签到成功"), "ok"};
        if (s.contains("签到成功") || s.startsWith("已签") || s.contains("已记录签到日")
                || s.contains("标记今日已签") || s.contains("已记为今日已签"))
            return new String[]{"check", Lang.tr("签到成功")};

        // 发出/等待
        if (s.contains("文本指令已发出") || s.contains("已发出，等待"))
            return new String[]{"hourglass", Lang.tr("指令已发出，等机器人回复"), "info"};
        if (s.contains("请求已发出") || s.contains("发送成功") || s.contains("已发送"))
            return new String[]{"hourglass", Lang.tr("已发出，等机器人回复"), "info"};

        // 失败/异常（带下一步）
        if (s.contains("按钮失效"))
            return new String[]{"warn", Lang.tr("按钮已失效，稍后自动重试"), "warn"};
        if (s.contains("bot 未回复") || s.contains("机器人未回复"))
            return new String[]{"warn", Lang.tr("机器人没回复，稍后自动重试"), "warn"};
        if (s.contains("回复判不出") || s.contains("结果未知"))
            return new String[]{"warn", Lang.tr("看不懂机器人的回复，已记下待处理"), "warn"};
        if (s.contains("超时"))
            return new String[]{"warn", Lang.tr("等待超时，稍后重试"), "warn"};
        if (s.contains("失败") || s.contains("错误") || s.contains("异常"))
            return new String[]{"x", Lang.tr("签到失败，详见详细日志"), "err"};

        // ── 补签（2026-10-03 重做）──
        // 旧实现：只要出现"补签"二字就吐绿色「补签已执行」。
        // 结果 `=== 启动补签 ===`（每轮心跳起点的内部标记，实测 45 次）
        // 和各种"跳过补签 / 不触发补签"全被翻译成绿色成功 ——
        // 用户已签完却一直看到绿色，以为在反复补签（截图反馈）。
        //
        // 现在按后缀分辨语义：
        //   · 跳过类 / 启动标记 / 设置层 → 内部机制，易懂档不显示
        //   · 真排了补签 → 说清"原定几点、稍后执行"（它还没执行，别谎报已执行）
        if (s.contains("补签")) {
            if (s.contains("跳过补签") || s.contains("不触发补签") || s.contains("未开补签")
                    || s.contains("非补签时段") || s.contains("跳过排期")
                    || s.contains("启动补签")) return null;
            // "[定时] 错过补签 X（原计划 07:19）约 4 分钟后触发"
            java.util.regex.Matcher bm = java.util.regex.Pattern
                    .compile("原计划\\s*(\\d{2}:\\d{2})").matcher(s);
            if (bm.find())
                return new String[]{"repeat", Lang.tf("补签 · 原定 {0} · 稍后执行", bm.group(1)), "info"};
            if (s.contains("补签已执行"))
                return new String[]{"repeat", Lang.tr("补了一次"), "ok"};
            return null;   // 补签截止/补签列表等设置层文案，不懂档不显示
        }
        // 窗口外跳过（定时模式下最常见的一条）：说明"现在不在签到时间"，
        // 是对用户有用的状态解释。必须放在通用"跳过"分支之前 ——
        // 否则会被那句 `s.contains("跳过")` 抢先译成笼统的"本次跳过"。
        if (s.contains("窗口外") && s.contains("跳过"))
            return new String[]{"clock", Lang.tr("不在签到时间，稍后自动执行"), "info",
                    foldKey == null ? "\u0001D:offwin" : foldKey};
        if (s.contains("跳过发送（") || s.contains("跳过发送(")) {
            // 「跳过发送（今天已签）」= 模块**主动没重复发**，是正确行为。
            // 用户手动再点一次时常会看到它 —— 那正是"没被重复发送"的证据，
            // 所以不能吞掉；但也不能笼统写"本次跳过"（看着像出错了）。
            // 按真实原因分档措辞（原因即 skipLabel 的后半段）。
            if (s.contains("今天已签"))
                return new String[]{"check", Lang.tr("该目标今天已签过，未重复发送"), "ok"};
            if (s.contains("已发出待结论"))
                return new String[]{"hourglass", Lang.tr("该目标已发出，正在等回复"), "info"};
            if (s.contains("请求在途"))
                return new String[]{"hourglass", Lang.tr("该目标正在发送中，未重复发送"), "info"};
            if (s.contains("退避中") || s.contains("重试已用尽") || s.contains("次数已达上限"))
                return new String[]{"pause", Lang.tr("该目标稍后自动重试"), "info"};
            return new String[]{"pause", Lang.tr("本次跳过"), "info"};
        }
        if (s.contains("跳过") || s.contains("已暂停"))
            // 无目标键时也按天折叠：同一天几十条"跳过"讲的是同一件事
            return new String[]{"pause", Lang.tr("本次跳过"), "info",
                    foldKey == null ? "\u0001D:skip" : foldKey};
        if (s.contains("冻结"))
            return new String[]{"pause", Lang.tr("已冻结，不再签到"), "info"};
        if (s.contains("排除") && !s.contains("规则"))
            return new String[]{"x", Lang.tr("已排除该机器人"), "info"};

        // 用户处置待确认（2026-10-03）：原句带着"【待确认】某目标 用户忽略今天
        // （今日不再自动重试）"，又长又有内部名词。这里给出短句。
        if (s.contains("用户忽略今天")) return new String[]{"pause", Lang.tr("你已忽略该目标（今天不再试）"), "info"};
        if (s.contains("用户确认已签")) return new String[]{"check", Lang.tr("你已确认该目标签上了"), "ok"};
        if (s.contains("用户点了重试")) return new String[]{"refresh", Lang.tr("你点了重试，已重新发送"), "info"};

        // 学习/新增
        if (s.contains("学到") || s.contains("已添加") || s.contains("新目标"))
            return new String[]{"plus", Lang.tr("发现新的签到目标"), "ok"};
        // 候选（网络学习命中但未达关键词）2026-10-03 改：
        // 旧实现在这里吐笼统的"有新的待添加目标"，实测一天 169 条同质噪音。
        // 真正的网络学习命中（learnFromNetwork）不经过这条路 ——
        // 它写的是「网络层自动学习」/「网络层学习·待确认」，前面那条已覆盖。
        // 这里剩下的都是"判过但没加"的候选，属于内部判定过程，不再上易懂档。
        if (s.contains("待添加") || s.contains("候选")) return null;

        // 设置
        if (s.startsWith("设置更新") || s.contains("设置已保存"))
            return new String[]{"sliders", Lang.tr("设置已更新"), "ok"};

        // 启动横幅（其余 4 行已在 isInternalLog 里滤掉）→ 压成一条。
        // 按天折叠：一天重启十几次，逐条列出只是噪音，合并成"×N"才说明问题。
        if (s.startsWith("=== TGAutoSign") && s.contains("已加载"))
            return new String[]{"bot", Lang.tr("已启动"), "info", "\u0001D:boot"};

        // 兜底：去技术前缀，遮住裸 ID
        String r = s;
        // 去掉上下文前缀 [账号1|#3|a0_df28]：[...] 里带 | 或 a0_ 这类链路标记的即内部上下文
        r = r.replaceAll("^\\[[^\\]]*[|][^\\]]*\\]\\s*", "");
        // 去掉残留的 [某目标_cb1] 方括号（目标 id 已由下面的数字替换处理）
        r = r.replaceAll("^\\[[^\\]]*_cb\\d+\\]\\s*", "");
        r = r.replaceAll("\\[[^\\]]*_cb\\d+\\]", Lang.tr("某目标"));
        r = r.replaceAll("uid=-?\\d+", "");
        r = r.replaceAll("-?\\d{9,}", Lang.tr("某目标"));
        r = r.replace("text=", "");
        r = r.replaceAll("\\s+", " ").trim();
        if (r.length() == 0) return null;
        return foldKey == null ? new String[]{"dots", r, "info"}
                               : new String[]{"dots", r, "info", foldKey};
    }

    /**
     * 折叠键：同键的易懂档条目会被界面层合并成"N 次"。
     *
     * 只对"同一条内容会反复出现"的类别给键（定时跳过、在途不重排…），
     * 其余返回 null = 不折叠（每次都是独立事件，比如签到成功）。
     */
    private String foldKeyOf(String s) {
        try {
            // 目标标识：优先 uid=<数字>（网络层日志），否则取 <数字>_<seq> 形态的 id
            //（定时/巡检日志只带 id，没有 uid= 前缀 —— 实测 20 条"不重排"全属于后者，
            //  用 uid= 匹配一条都抓不到，折叠形同虚设）。
            String key = null;
            java.util.regex.Matcher mu = java.util.regex.Pattern
                    .compile("uid=(-?\\d+)").matcher(s);
            if (mu.find()) key = mu.group(1);
            if (key == null) {
                java.util.regex.Matcher mi = java.util.regex.Pattern
                        .compile("(-?\\d{5,})_(?:g\\d+|cb\\d+|\\d+)").matcher(s);
                if (mi.find()) key = mi.group(1);
            }
            if (key == null) return null;
            // 「窗口外跳过」还在易懂档显示（它解释"为什么现在没动作"），
            // 同目标的那句会刷很多遍，折叠成 xN。
            if (s.contains("窗口外")) return "offwin:" + key;
            return null;
        } catch (Throwable t) { return null; }
    }

    /** 兼容旧调用：只要文案 */
    private String plainLog(String pfx, String m) {
        String[] p = plainLogParts(m);
        return p == null ? null : p[1];
    }

    /**
     * 严格判断是不是 yyyy-MM-dd。
     *
     * 用途：日历回填时过滤脏数据。只做形态 + 数值范围校验，不做真实历法校验
     * （值来自本模块自身写入，形态对了就可信）。
     */
    private static boolean isYmd(String s) {
        if (s == null || s.length() != 10) return false;
        if (s.charAt(4) != '-' || s.charAt(7) != '-') return false;
        for (int i = 0; i < 10; i++) {
            if (i == 4 || i == 7) continue;
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            int mo = Integer.parseInt(s.substring(5, 7));
            int d = Integer.parseInt(s.substring(8, 10));
            return y >= 2000 && y <= 2200 && mo >= 1 && mo <= 12 && d >= 1 && d <= 31;
        } catch (Throwable t) { return false; }
    }

    /**
     * 清理历史脏数据（2026-09-30）。
     *
     * 背景：日历曾把 last_<id> 的值无条件当日期，实测出现「今天 9-30，日历却亮 17~26」
     *   —— 连续 0 天却显示十天全签，自相矛盾。
     * 上一版已加写入校验（isYmd），但**存量脏值仍在 prefs 里**，必须主动清一次。
     *
     * 做法：扫描本账号所有目标的 last_<id>，值不是严格 yyyy-MM-dd 的删掉。
     *   只删「明显不合法」的，合法的历史日期保留（用户可能真有历史记录）。
     * 幂等：无脏值时什么都不做，可重复执行。
     */
    private void purgeDirtySignState() {
        try {
            String prefix = accountPrefix();
            java.util.List<Map<String, Object>> l = new java.util.ArrayList<Map<String, Object>>();
            loadTargetsInto(prefix, l);
            SharedPreferences.Editor ed = prefs.edit();
            int fixed = 0;
            for (Map<String, Object> m : l) {
                String key = kLast(prefix, entryId(m));
                String v = prefs.getString(key, "");
                if (v == null || v.length() == 0) continue;
                // ① 格式非法（历史版本残留的非日期内容）
                // ② 格式合法但**超过 14 天**：last_ 语义是「最近一次签到」，
                //    出现两周前的值只可能是导入旧配置带进来的过期数据 ——
                //    实测正是它让日历在空账号上凭空亮起一个旧日期（9-24）。
                //    清掉它只会让日历少亮一个错误格子；若用户真想保留历史，
                //    sign_days（现已纳入导入导出）才是正确的载体。
                if (!isYmd(v) || !isWithinDays(v, 14)) {
                    ed.remove(key);
                    fixed++;
                    logw("【清理】last_ 过期/非法已移除: " + v + " (" + entryId(m) + ")");
                }
            }
            // sign_days 里混入的脏日期也一并过滤
            String sd = prefs.getString(kSignDays(prefix), "");
            if (sd != null && sd.length() > 0) {
                StringBuilder sb = new StringBuilder();
                int keep = 0, drop = 0;
                for (String x : sd.split(",")) {
                    String s = x.trim();
                    if (s.length() == 0) continue;
                    if (isYmd(s)) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(s);
                        keep++;
                    } else {
                        drop++;
                    }
                }
                if (drop > 0) {
                    ed.putString(kSignDays(prefix), sb.toString());
                    fixed += drop;
                    logw("【清理】sign_days 移除 " + drop + " 个非法日期，保留 " + keep + " 个");
                }
            }
            if (fixed > 0) {
                ed.apply();
                logw("【清理】完成，共修正 " + fixed + " 项");
            }
        } catch (Throwable t) { logd("清理脏数据失败: " + t); }
    }

    /**
     * 判断 yyyy-MM-dd 是否落在最近 days 天内（含今天）。
     * 用于过滤 last_ 里的过期值：导入旧配置会把它恢复成很久以前的日期。
     */
    private static boolean isWithinDays(String ymd, int days) {
        if (!isYmd(ymd)) return false;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            f.setLenient(false);
            java.util.Date d0 = f.parse(ymd);
            if (d0 == null) return false;
            java.util.Calendar a = java.util.Calendar.getInstance();
            a.set(java.util.Calendar.HOUR_OF_DAY, 12);
            a.set(java.util.Calendar.MINUTE, 0);
            a.set(java.util.Calendar.SECOND, 0);
            a.set(java.util.Calendar.MILLISECOND, 0);
            a.add(java.util.Calendar.DATE, -(days - 1));
            return !d0.before(a.getTime());
        } catch (Throwable t) { return false; }
    }

    /**
     * 把易懂档的语义级别映射成颜色。
     *
     * 为什么不能直接用日志的 lv：lv 同时承担「是否必定落盘」的职责 ——
     *   为保落盘，guessLevel 会把「签到成功」这类状态变更标成 LV_WARN，
     *   直接拿它上色就会把成功显示成黄色警告（用户截图反馈）。
     */
    /**
     * 标题动画收尾：把所有字符复位到静止态（统一青色、无位移）。
     *
     * 动效收尾/停止时必须调它 —— 否则最后一个中间帧（比如掠过的某个高亮字、
     * 敲击的某个高亮字）会**永久留在屏幕上**，看着像渲染错误。
     */
    private void settleTitle(java.util.List<TextView> chs, int cyan) {
        try {
            if (chs == null) return;
            for (TextView c : chs) {
                if (c == null) continue;
                c.setTranslationY(0f);
                c.setTextColor(cyan);
            }
        } catch (Throwable ignored) {}
    }

    private int plainColor(String sem, Context c) {
        if ("ok".equals(sem))   return Theme.termGreen(c);
        if ("warn".equals(sem)) return Theme.termAmber(c);
        if ("err".equals(sem))  return Theme.termPink(c);
        return Theme.termMuted(c);
    }
    /**
     * 今天是否「全部签完」——活跃目标里没有一个还处于未签状态。
     *
     * 用于主页倒计时的显示决策：全签完就不该再说「下次签到」。
     * 口径与进度分母一致：冻结 / 已被排除 bot 的目标不参与（它们本就"不签"）。
     */
    private boolean isAllSignedToday() {
        try {
            String prefix = accountPrefix();
            java.util.List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            loadTargetsInto(prefix, list);
            String today = todayStr();
            int active = 0;
            for (Map<String, Object> m : list) {
                String id = entryId(m);
                if (isFrozen(prefix, id)) continue;
                if (isBotBlocked(entryDid(m))) continue;
                active++;
                if (!today.equals(prefs.getString(kLast(prefix, id), ""))) return false;
            }
            return active > 0;
        } catch (Throwable t) { return false; }
    }

    private static String kExclude() { return "jmb_exclude"; }
    private static String kBlockedDids() { return "jmb_blocked_dids"; }
    private static String kPendingConfirm() { return "jmb_pending_confirm"; }
    /**
     * 顶部「待处理」聚合条（2026-09-28）。
     *
     * 为什么改成聚合条：内联按钮假设用户此刻要处理它，但用户可能只是路过。
     * 而且它让目标行和别的行长得不一样。聚合条遵循一个更自然的模式 ——
     * 像系统通知：平时不打扰，想处理时点进去，一次看完所有待办。
     *
     * @return 需要处置的目标数；0 表示不显示这条
     */
    private int countNeedsAttention(String prefix) {
        int n = 0;
        try {
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            loadTargetsInto(prefix, list);
            for (Map<String, Object> m : list) {
                if (isPendingConfirm(prefix, entryId(m))) n++;
            }
        } catch (Throwable t) { noteSwallowed("countNeedsAttention", t); }
        return n;
    }

    /** 归类 → 用户可读的处置说明（聚合条里逐条显示）。 */
    private String attentionHint(String prefix, String id) {
        int rc = resultCodeOf(prefix, id);
        if (rc == SignLogic.R_BTN_STALE)    return Lang.tr("按钮已失效，等 bot 推新面板后可重试");
        if (rc == SignLogic.R_REPLIED_UNK)  return Lang.tr("bot 回复了，但判定词认不出结果");
        if (rc == SignLogic.R_NO_REPLY)     return Lang.tr("bot 全程没回复");
        if (rc == SignLogic.R_JUDGE_OFF)    return Lang.tr("自动判定已关闭，未判成败");
        if (rc == SignLogic.R_EXHAUSTED)    return Lang.tr("bot 说今日次数已用尽，今天不必再签");
        return Lang.tr("结果未知，需要你看一眼");
    }

    /** 归类的显示名（聚合条左侧标签）。 */
    private String attentionLabel(String prefix, String id) {
        int rc = resultCodeOf(prefix, id);
        if (rc == SignLogic.R_BTN_STALE)    return Lang.tr("按钮失效");
        if (rc == SignLogic.R_REPLIED_UNK)  return Lang.tr("回复判不出");
        if (rc == SignLogic.R_NO_REPLY)     return Lang.tr("bot 未回复");
        if (rc == SignLogic.R_JUDGE_OFF)    return Lang.tr("判定已关");
        if (rc == SignLogic.R_EXHAUSTED)    return Lang.tr("今日已用尽");
        return Lang.tr("结果未知");
    }


    /**
     * 处置完一条后重新打开「待处理」（2026-09-28）。
     *
     * 为什么要单独一个方法：处置后要么还剩别的待处理（重开自己），
     * 要么已经清空（回目标列表）。两条路都**只操作对话框**，
     * 不碰 Activity —— 见 showPendingWork 里关于 finish() 的说明。
     *
     * 用 postDelayed 而不是立即执行：对话框 dismiss 与新建之间有动画，
     * 立即新建会叠在正在消失的旧框上（既有 scheduleDismiss 也是 160ms）。
     */
    private void reopenPendingWork(final Activity act) {
        final Object old = pendingDialog;
        pendingDialog = null;
        dismissOne(old);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (act == null || act.isFinishing()) return;
                    if (countNeedsAttention(accountPrefix()) > 0) {
                        showPendingWork(act);
                    } else {
                        refreshListFrom(act);
                    }
                } catch (Throwable t) { noteSwallowed("reopenPendingWork", t); }
            }
        }, 180L);
    }

    /**
     * 集中处置界面：一次列出所有需要处理的目标，每条三个动作。
     *
     * 动作与旧的内联按钮完全一致（确认已签 / 重试 / 忽略今天），
     * 只是从"每行都塞"改成"想看时才展开"。
     */
    private void showPendingWork(final Activity act) {
        final String pfx = accountPrefix();
        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(2), dp(2), dp(2), dp(2));

        /* top hint (2026-09-29) */
        TextView topHint = new TextView(act);
        topHint.setTextSize(Theme.TS_CAPTION);
        topHint.setTextColor(Theme.termFaint(act));
        topHint.setTypeface(Theme.text());
        topHint.setText(Lang.tr("以下目标发出后未得到明确结论，需要你判断。"));
        topHint.setPadding(dp(4), dp(2), dp(4), dp(4));
        box.addView(topHint);

        final List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try { loadTargetsInto(pfx, list); } catch (Throwable ignored) {}

        int shown = 0;
        for (final Map<String, Object> m : list) {
            final String eid = entryId(m);
            if (!isPendingConfirm(pfx, eid)) continue;
            shown++;
            final long edid = entryDid(m);
            final String itemText = entryText(m);

            /* card: 4-row layout */
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termAmber(act), 0x33)));

            /* row 1: name + tag + time */
            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = new TextView(act);
            nm.setTextSize(Theme.TS_SUBTITLE);
            nm.setTextColor(Theme.termTxt(act));
            nm.setText(targetTitle(edid));
            nm.setTypeface(Theme.text());
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            head.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView tag = badgeChip(act, attentionLabel(pfx, eid), Theme.termAmber(act), false);
            head.addView(tag);
            head.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(dp(6), 1));

            long issueAt = prefs.getLong(pfx + "sent_at_" + eid, 0L);
            if (issueAt > 0L) {
                TextView ts = new TextView(act);
                ts.setTextSize(Theme.TS_CAPTION);
                ts.setTextColor(Theme.termFaint(act));
                ts.setTypeface(Theme.text());
                ts.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(issueAt)));
                head.addView(ts);
            }
            card.addView(head);

            /* row 2: entry text */
            if (itemText != null && itemText.trim().length() > 0) {
                TextView tx = new TextView(act);
                tx.setTextSize(Theme.TS_CAPTION);
                tx.setTextColor(Theme.termMuted(act));
                tx.setTypeface(android.graphics.Typeface.MONOSPACE);
                tx.setSingleLine(true);
                tx.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tx.setText("\u25b8 " + itemText.trim().replace("\n", " "));
                tx.setPadding(0, dp(2), 0, 0);
                card.addView(tx);
            }

            /* row 3: hint */
            TextView hint = new TextView(act);
            hint.setTextSize(Theme.TS_CAPTION);
            hint.setTextColor(Theme.termFaint(act));
            hint.setText(attentionHint(pfx, eid));
            hint.setTypeface(Theme.text());
            hint.setPadding(0, dp(4), 0, dp(8));
            card.addView(hint);

            /* separator */
            View sep = new View(act);
            sep.setBackgroundColor(Theme.withAlpha(Theme.termAmber(act), 0x18));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 1);
            slp.setMargins(0, 0, 0, dp(8));
            card.addView(sep, slp);

            /* row 4: three equal-width buttons */
            LinearLayout acts = new LinearLayout(act);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.setGravity(Gravity.CENTER_VERTICAL);

            Button bOk = mkBtn(act);
            bOk.setText(Lang.tr("确认已签"));
            bOk.setTextSize(Theme.TS_CAPTION);
            bOk.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                pendConfirmAsSigned(pfx, eid, edid);
                reopenPendingWork(act);
            } });
            acts.addView(bOk, new LinearLayout.LayoutParams(0, -2, 1f));
            acts.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(dp(6), 1));

            Button bRe = mkBtn(act);
            bRe.setText(Lang.tr("重试"));
            bRe.setTextSize(Theme.TS_CAPTION);
            bRe.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                pendConfirmRetry(pfx, eid, edid);
                reopenPendingWork(act);
            } });
            acts.addView(bRe, new LinearLayout.LayoutParams(0, -2, 1f));
            acts.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(dp(6), 1));

            Button bIg = mkBtn(act);
            bIg.setText(Lang.tr("忽略今天"));
            bIg.setTextSize(Theme.TS_CAPTION);
            bIg.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                pendConfirmIgnoreToday(pfx, eid, edid);
                reopenPendingWork(act);
            } });
            acts.addView(bIg, new LinearLayout.LayoutParams(0, -2, 1f));

            card.addView(acts);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.setMargins(0, dp(4), 0, dp(4));
            card.setLayoutParams(clp);
            box.addView(card);
        }

        if (shown == 0) {
            TextView e = new TextView(act);
            e.setTextSize(Theme.TS_SECOND);
            e.setTextColor(Theme.termFaint(act));
            e.setText(Lang.tr("(没有需要处理的目标)\n按钮失效、回复判不出、bot 未回复时，这里会出现条目。"));
            e.setPadding(dp(6), dp(12), dp(6), dp(12));
            box.addView(e);
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.addView(box);
        Object oldP = pendingDialog;
        pendingDialog = showDialog(act, Lang.tf("待处理（{0}）", shown), sv, "关闭");
        scheduleDismiss(oldP);
    }

    /** 账号级待确认池键。唯一真相源在 Keys，这里只做转发（勿再内联字面量）。 */
    private static String kPendingConfirm(int account) { return Keys.pendingConfirm(account); }
    private static String kFrozen(String prefix, String id) { return prefix + "frozen_" + id; }
    /**
     * 当天失败次数（独立计数器，只增不减）。
     * 与 retry_ 的区别：retry_ 会被「成功判定」清零，导致"成功/失败交替"时永远涨不上去
     * （现场 2026-09-25：目标 7719383660 在 0/1 之间震荡，一天被签 8 次）。
     */
    private static String kFailsToday(String prefix, String id) { return prefix + "fails_today_" + id; }
    /** 上面那个计数所属的日期，用于跨天重置。 */
    private static String kFailsDay(String prefix, String id) { return prefix + "fails_day_" + id; }
    /** 当天是否命中过确定性失败词（命中即当天不再重试）。 */
    private static String kPermFail(String prefix, String id) { return prefix + "permfail_" + id; }
    /** 「待确认」：发出去了但 bot 始终没回复，不算成功也不算失败，且不再自动重试。 */
    private static String kPendingConfirm(String prefix, String id) { return prefix + "pendcfm_" + id; }
    private boolean isPendingConfirm(String prefix, String id) {
        try {
            if (!prefs.getBoolean(kPendingConfirm(prefix, id), false)) return false;
            /* 今天已签 → 不再需要确认（2026-09-29 修）：
               只读 pendcfm_ 不能区分「确认后的残留」与「真正待处理」。
               last_ 是「已签」的最终凭据 —— 已签还赖在待处理里就是 bug。 */
            if (todayStr().equals(prefs.getString(kLast(prefix, id), ""))) return false;
            /* 双保险：条目已删（清空配置 / 手动删除）时，残留的 pendcfm_ 不该让
               "重新添加的同一 bot" 一上来就显示「待确认」。 */
            if (findEntryById(id, accountOfPrefix(prefix)) == null) return false;
            return true;
        } catch (Throwable t) { return false; }
    }

    /** 清掉该目标的"在途请求"记录（手动签到前清障，2026-09-29） */
    private void clearPendingRequest(String prefix, String id) {
        try {
            // pendingSigns 是 volatile Map，GUI 线程写入
            if (pendingSigns != null) pendingSigns.remove(id);
        } catch (Throwable t) { noteSwallowed("clearPendingRequest", t); }
    }
    private void clearPendingConfirm(String prefix, String id) {
        try { if (prefs.getBoolean(kPendingConfirm(prefix, id), false)) prefs.edit().remove(kPendingConfirm(prefix, id)).apply(); }
        catch (Throwable _eC) { noteSwallowed("clearPendingConfirm", _eC); }
    }

    // ── 「已发出」超时自动转「待确认」（2026-09-27）──
    //
    // 背景（用户报的 bug）：cb（回调按钮）目标发出后，状态显示「已发出」。
    // 若该 bot 把签到结论放在 callback answer 里、不再另发消息，回复判定永远等不到它，
    // kLast 就永远不写 → 状态一直停在「已发出」；等 PENDING_TTL_MS（30 分钟）过后
    // sent_at_ 失效，状态又**退回「待签」**，于是被重新排期再发一次。
    // 用户感受是「签成功了却显示已发出」，而且过一阵又变回没签。
    //
    // 修法：发出后超过 SILENT_TO_PENDING_MS 仍无结论，就主动转成「待确认」——
    // 复用 v1.6.0 已有的处置条（确认已签 / 重试 / 忽略今天），用户看得到、点得动。
    // 不替 bot 判成功（那是骗人），也不当失败（会无谓重试），只如实告知"没等到回复"。
    private static final long SILENT_TO_PENDING_MS = 10L * 60 * 1000;

    /**
     * 今天发过、但已超出「等结论」时效，且没有结论落库 —— 即"发了但不知道结果"。
     *
     * 与 isSentPendingFresh 互补：那个判"还在时效内"，这个判"已过时效但发过"。
     * 两者一起覆盖 sent_at_ 的整个生命周期，不留"既不显示已发出、也不显示待签"的缝。
     */
    private boolean sentButStale(String prefix, String id) {
        try {
            boolean sentToday = stateStore.isOptimisticToday(prefix, id);
            long sent = prefs.getLong(prefix + "sent_at_" + id, 0L);
            return SignLogic.sentPhase(sentToday, sent, System.currentTimeMillis(),
                                       SILENT_TO_PENDING_MS) == SignLogic.SENT_STALE;
        } catch (Throwable t) { return false; }
    }

    /** 已发出但久无结论 → 转「待确认」。返回是否发生了转换。 */
    /**
     * 读当日发送次数。值形如 "yyyy-MM-dd|3"；跨天自动视为 0。
     * 见 SignLogic.MAX_SEND_ATTEMPTS 与 Keys.sendAttempts。
     */
    /**
     * 当日发送次数是否已达硬上限（MAX_SEND_ATTEMPTS）。
     *
     * 与"重试用尽"的区别：retry_ 只在**明确失败**时涨，而"发出去了、bot 没给结论"
     * 既不算成功也不算失败 → retry_ 不涨 → 所有基于 retry_ 的闸全部失效。
     * 这正是群目标被连发 13 次（10-02 实测）的原因。
     * send_n_ 专门覆盖这一路，且按天记（跨天归零）。
     */
    private boolean isSendAttemptsExhausted(String prefix, String id) {
        try {
            return sendAttemptsToday(prefix, id) >= SignLogic.MAX_SEND_ATTEMPTS;
        } catch (Throwable t) { return false; }
    }

    private int sendAttemptsToday(String prefix, String id) {
        try {
            String v = prefs.getString(Keys.sendAttempts(prefix, id), "");
            if (v == null || v.length() == 0) return 0;
            int bar = v.indexOf('|');
            if (bar <= 0) return 0;
            if (!todayStr().equals(v.substring(0, bar))) return 0;   // 不是今天 → 归零
            return Integer.parseInt(v.substring(bar + 1).trim());
        } catch (Throwable t) { return 0; }
    }

    /** 当日发送次数 +1（同步落盘：紧随其后的闸门要读它）。 */
    private int bumpSendAttempts(String prefix, String id) {
        try {
            int n = sendAttemptsToday(prefix, id) + 1;
            prefs.edit().putString(Keys.sendAttempts(prefix, id), todayStr() + "|" + n).commit();
            return n;
        } catch (Throwable t) { noteSwallowed("bumpSendAttempts", t); return 0; }
    }

    /** 清除当日发送计数（有了明确结论时调用）。 */
    private void clearSendAttempts(String prefix, String id) {
        try { prefs.edit().remove(Keys.sendAttempts(prefix, id)).commit(); }
        catch (Throwable t) { noteSwallowed("clearSendAttempts", t); }
    }

    private boolean promoteSilentToPending(String prefix, String id, String why) {
        try {
            // 判定集中在 SignLogic.shouldPromoteToPending（纯逻辑 + 单测覆盖）
            boolean signedToday = todayStr().equals(prefs.getString(kLast(prefix, id), ""));
            boolean alreadyPending = prefs.getBoolean(kPendingConfirm(prefix, id), false);
            // 重试计数**按天**判定（2026-10-03 修）：原先不查 retry_day_，
            // 昨天的计数在今天仍算"已用尽"，会让本方法错误地拒绝转换。
            // 同文件 skipScheduling 早就带了日期检查，这里当时漏了。
            boolean retryExhausted = prefs.getInt(kRetry(prefix, id), 0) >= RETRY_LIMIT
                    && todayStr().equals(prefs.getString(kRetryDay(prefix, id), ""));
            boolean sentToday = stateStore.isOptimisticToday(prefix, id);
            int attempts = sendAttemptsToday(prefix, id);

            // ── 待确认安全网（2026-10-03）──
            // 已经处于待确认时，这里本就没别的事可做（alreadyPending 会让下面所有
            // 判据返回 false）。但历史数据里存在"send_n_ 已超限、pendcfm_ 却被
            // 旧版 sweepStalePendingConfirm 误清"的组合 —— 那种情况下闸门只能靠
            // send_n_ 拦，界面却看不到归类。这里把它补回待确认，收口一致。
            if (alreadyPending) {
                if (attempts < SignLogic.MAX_SEND_ATTEMPTS) return false;
                if (findEntryById(id, accountOfPrefix(prefix)) == null) return false;
                // 已有 pendcfm_，只补日期标注（缺了会被当"无日期老记录"）。
                if (prefs.getString(Keys.pendingDay(prefix, id), "").length() == 0) {
                    try { prefs.edit().putString(Keys.pendingDay(prefix, id), todayStr()).apply(); }
                    catch (Throwable _ePD) { noteSwallowed("promote-pendingDay", _ePD); }
                }
                return false;
            }
            long sent = prefs.getLong(prefix + "sent_at_" + id, 0L);
            long now = System.currentTimeMillis();
            if (!sentToday) return false;                      // 今天没发过，谈不上"发出后无结论"
            // 是否收到过回复：bot 表态了就早收工，别按"静默"久等（2026-10-01）
            boolean answered = todayStr().equals(prefs.getString(Keys.answered(prefix, id), ""));
            // 两个判据取「或」：
            //   ① 新判据 —— 发送次数硬上限 + 按"有无回复"分档的时间软闸
            //      （解决：V_UNKNOWN 不涨 retry，心跳每 45 秒重发、刷 13 条）
            //   ② 旧判据 —— 非跨天的静默超时（保留，避免回归既有行为）
            boolean giveUp = SignLogic.shouldGiveUpSending(attempts, sent, now, answered,
                                                           signedToday, alreadyPending, retryExhausted)
                          || SignLogic.shouldPromoteToPending(true, sent, now, SILENT_TO_PENDING_MS,
                                                              signedToday, alreadyPending, retryExhausted);
            if (!giveUp) return false;
            long age = sent > 0L ? (now - sent) : 0L;
            // 条目已不存在就不必标了（避免孤儿键）
            if (findEntryById(id, accountOfPrefix(prefix)) == null) return false;

            prefs.edit()
                 .putBoolean(kPendingConfirm(prefix, id), true)
                 // 关键（2026-10-03）：记下"哪天标的"。
                 // 本方法删掉了 sent_at_ / opt_，而那是"今天发过"的唯一凭据；
                 // 不写这个日期，sweepStalePendingConfirm 下次启动就会把
                 // 今天刚标的待确认当成隔天残留清掉 → 闸放行 → 重发（实测 13 次）。
                 .putString(Keys.pendingDay(prefix, id), todayStr())
                 .putInt(kRetry(prefix, id), 0)
                 .remove(kRetryAt(prefix, id))
                 .remove(kRetryDay(prefix, id))
                 .remove(prefix + "sent_at_" + id)
                 .remove(prefix + "opt_" + id)
                 .commit();
            // 补写归类（2026-09-28 修）：上轮只改主路径，漏了这条兜底 ——
            // 结果界面读不到归类、落回兜底文案，日志也还写着旧词「待确认」。
            // 归类是界面文案与处置入口的唯一真相源，这条路径同样必须写。
            markResultCode(prefix, id, answered ? SignLogic.R_REPLIED_UNK : SignLogic.R_NO_REPLY);
            logw("已发出 " + (age / 60000L) + " 分钟仍无签到结论：" + id
                 + " 标记为「" + (answered ? "回复判不出" : "bot 未回复") + "」（" + why + "）"
                 + "。不计成功也不计失败，已停止自动重试；"
                 + "请在目标列表点「确认已签 / 重试 / 忽略今天」处置");
            return true;
        } catch (Throwable t) { noteSwallowed("promoteSilentToPending", t); return false; }
    }

    /** 扫描某账号下所有「已发出且超时」的目标，转待确认。返回转换个数。 */
    private int promoteSilentToPendingAll(String prefix) {
        int n = 0;
        try {
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            loadTargetsInto(prefix, list);
            for (Map<String, Object> m : list) {
                if (promoteSilentToPending(prefix, entryId(m), "sweep")) n++;
            }
        } catch (Throwable t) { noteSwallowed("promoteSilentToPendingAll", t); }
        return n;
    }

    // ── 「待确认」的用户处置（v1.6.0）──
    // 背景：以前这个状态只写不读 —— 置位后除了"手动测试"没有任何清除入口，
    // 用户永远卡在「待确认」，而重试计数已被清零 → 每天照发、照超时、照标待确认（死循环）。
    // 现在给出三个明确动作，并把用户的选择记下来。

    /** 用户已确认该目标今天签上了（写 last_，与正常签到成功等价）。 */
    private void pendConfirmAsSigned(String prefix, String id, long did) {   // prefix 已锁定账号
        try {
            markSigned(prefix, id);              // 会顺带 remove(pendcfm_)
            // 必须显式把"今日已放弃"写死：只清 pendcfm_ 不清重试闸的话，
            // 用户确认完的下一分钟定时任务又会发一遍 → 又 timeout → 又冒「待确认」
            //（实测 21:53:45 确认，21:54:17 照发，21:54:35 又 timeout）。
            prefs.edit()
                 .remove(kPendingConfirm(prefix, id))
                 .putInt(kRetry(prefix, id), RETRY_LIMIT)
                 .putString(kRetryDay(prefix, id), todayStr())
                 .remove(kRetryAt(prefix, id))
                 .putString(prefix + "pendcfm_note_" + id, todayStr() + "|用户确认已签")
                 .apply();
            // 日志要在清理之后打，并复述真实状态 —— 以前无论 markSigned 是否早退都报"计入今日已签"，
            // 用户看到"成功"提示却发现按钮还在，就是这条假日志造成的认知错位。
            logs("用户确认已签 → 已记为今日已签并停止今日重试（" + entryLabel(prefix, id) + "）");
            toast(Lang.tr("已记为今日已签"));
            try { refreshListFrom(lastActivity); } catch (Throwable ignored) {}
        } catch (Throwable t) { noteSwallowed("pendConfirmAsSigned", t); }
    }

    /**
     * 条目的可读标识（日志与界面共用）。
     *
     * 动机（2026-09-28）：同一个 bot 下可以挂多个目标（🔙返回 / 📊统计 / 付费查询），
     * 而日志原来只打 did、界面标题只显示 bot 名 —— 两处都无法区分是哪一条。
     * 实测用户看到「点一个出了两条日志」，其实那是两个不同目标各触发一次。
     *
     * @return 形如 "8756683068_cb1（🔙 返回）"；取不到内容时退化为纯 id
     */
    private String entryLabel(String prefix, String id) {
        try {
            Map<String, Object> m = findEntryById(id, accountOfPrefix(prefix));
            if (m == null) return id;
            String t = entryText(m);
            if (t == null || t.trim().length() == 0) return id;
            t = t.replace("\n", " ").trim();
            if (t.length() > 18) t = t.substring(0, 18) + "…";
            return id + "（" + t + "）";
        } catch (Throwable e) { return id; }
    }

    /** 用户选择重试：清掉待确认与重试计数，立刻再发一次。 */
    private void pendConfirmRetry(String prefix, String id, long did) {
        try {
            prefs.edit().remove(kPendingConfirm(prefix, id))
                 .putInt(kRetry(prefix, id), 0)
                 .remove(kRetryAt(prefix, id))
                 .remove(kRetryDay(prefix, id))
                 // 手动重试 = 用户明确要求"再试一次"，历史失效计数必须清零。
                 // 否则每点一次计数 +1，很快撞上"重发 2 次仍失效"的闸
                 // （实测 4 秒内从第 4 次涨到第 6 次，用户以为重试没生效）。
                 .remove(prefix + "panelstale_" + id)
                 .remove(prefix + "panelstale_day_" + id)
                 .remove(Keys.pendingResult(prefix, id))
                 // 当日发送次数也归零（2026-10-03）：用户明确要求"再试一次"，
                 // 不清的话 send_n_ 仍在上限，非 manual 的后续轮次全被堵死。
                 .remove(Keys.sendAttempts(prefix, id))
                 .putString(prefix + "pendcfm_note_" + id, todayStr() + "|用户点了重试")
                 .apply();
            Map<String, Object> m = findEntryById(id, accountOfPrefix(prefix));
            if (m == null) { toast(Lang.tr("目标已不存在")); return; }
            logs("用户点了重试 → 重新发送（" + entryLabel(prefix, id) + "）");
            toast(Lang.tr("已重新发送"));
            // 账号必须从 prefix 反解，**不能读 currentAccount()**：
            // 界面渲染时的账号与点击时的"当前账号"可能不同（用户切过号），
            // 读当前账号就会把 A 账号的目标用 B 账号发出去 —— 与 armTask/waitingPanel 同一类 bug。
            // 日志实测过：21:13:48 点重试，21:13:57 却以 [账号1] 发账号2 的目标。
            sendSign(m, accountOfPrefix(prefix), true);
        } catch (Throwable t) { noteSwallowed("pendConfirmRetry", t); }
    }

    /** 用户选择忽略今天：清掉待确认，且今天不再自动重试（不计成功也不计失败）。 */
    private void pendConfirmIgnoreToday(String prefix, String id, long did) {
        try {
            prefs.edit().remove(kPendingConfirm(prefix, id))
                 .putInt(kRetry(prefix, id), RETRY_LIMIT)      // = 今日放弃，不再自动试
                 .putString(kRetryDay(prefix, id), todayStr())
                 .putString(prefix + "pendcfm_note_" + id, todayStr() + "|用户忽略今天")
                 .apply();
            logs("用户忽略今天（今日不再自动重试）：" + entryLabel(prefix, id));
            toast(Lang.tr("已忽略今天"));
        } catch (Throwable t) { noteSwallowed("pendConfirmIgnoreToday", t); }
    }

    /**
     * 该条目是否"已经乐观标记为今日已签"。
     *
     * 用途：区分「请求已成功发出并标了已签」与「根本没发出去」。
     * 前者后续的第二步失败（按钮过期 / bot 不回结果 / 限流）**不代表签到失败**，
     * 不该撤销已签 —— 这是 v1.6.0 反复踩到的同一个坑（panelStale / BOT_RESPONSE_TIMEOUT /
     * FLOOD_WAIT 三处都无条件撤销过）。
     */
    private static String kOpt(String prefix, String id) { return prefix + "opt_" + id; }

    /** 标记"请求已成功发出"（乐观），**不等于签到成功**。失败/成功后由各路径清理。 */
    private void markOptimistic(String prefix, String id) {
        // 必须 commit()（同步落盘），不能 apply()。
        // 原因：这个标记是 sweepDue / armTask 的"今天已发出"闸门依据 ——
        // apply() 异步写，紧随其后的巡检会读不到，于是又排一个任务，
        // 同一目标被并发发多次（实测群签到 19~39 秒内发了 3 次）。
        // 同文件的 sent_at_ 本来就用的 commit()，这里保持一致。
        try { prefs.edit().putString(kOpt(prefix, id), todayStr()).commit(); }
        catch (Throwable _eMO) { noteSwallowed("markOptimistic", _eMO); }
    }

    /**
     * 排期/发送前的快速预筛：true = 这一轮不该碰这个目标。
     * 与 SignLogic.decideSign 的"非 manual"分支同义，供 sweepDue / armTask 这类
     * 高频路径使用（它们只看"能不能跳过"，不需要完整的 skip 归因）。
     * 单一实现，避免再出现"某个入口漏写一段判断"。
     */
    private boolean skipScheduling(String prefix, String id) {
        // 与 SignLogic.decideSign 的"非 manual"分支同义，但这里是高频路径，
        // 只看"能不能跳过"、不做归因。单一实现（见调度区注释 S3）。
        try {
            if (stateStore.isSignedToday(prefix, id)) return true;   // 今天已签
            if (isInFlightOrPending(prefix, id)) return true;        // 在途 or 已发出待结论
            // 待确认（bot 未回复 / 回复判不出）：已有归类，等用户处置，**不得再排期**。
            //
            // 为什么必须在这里拦（2026-09-30 用户实测「补签一直在补」）：
            //   promoteSilentToPending 转待确认时会清掉 sent_at_/opt_/retry/retryAt，
            //   而本函数原先只看「已签 / 在途 / 熔断」—— 三者全为 false，
            //   于是每轮心跳都重新排一次补签 → 又超时 → 又转待确认，死循环。
            //   现象：群聊目标（那个群根本没 bot）永远显示「后台 1~5 分钟内补」，
            //   每次打开 TG 就再发一次。
            // 注入/群聊目标没有 bot 回复时，永远不会产生结论 —— 再排一百次也一样。
            if (isPendingConfirm(prefix, id)) return true;
            // 今日重试已用尽：与 SignLogic.decideSign 的非 manual 分支同义。
            // 缺这一条时，「忽略今天 / 确认已签」写的 RETRY_LIMIT 拦不住补签通道 ——
            // sweepDue 会照样排任务（用户实测：点完忽略今天又冒出补签排期）。
            if (prefs.getInt(kRetry(prefix, id), 0) >= RETRY_LIMIT
                    && todayStr().equals(prefs.getString(kRetryDay(prefix, id), ""))) return true;
            // 当日发送次数已达硬上限：不再排期，避免"排一次→发送被拒→再排"空转
            //（2026-10-03）。sendSign 里还有同一道闸，这里只是提前收敛、少刷日志。
            if (isSendAttemptsExhausted(prefix, id)) return true;
            // 今日熔断：失败达上限 or 命中确定性失败词 → 今天不再排期，明天自动恢复
            return isDailyBlocked(prefix, id) || isPermanentFailedToday(prefix, id);
        } catch (Throwable t) { return false; }
    }

    /** "已发出、且还在等结论的时效内"——超过 PENDING_TTL_MS 才允许再发。 */
    private boolean isSentPendingFresh(String prefix, String id) {
        return stateStore.isSentPendingFresh(prefix, id, PENDING_TTL_MS);
    }

    /** 该条目今天是否"已发出请求但尚无最终结论"。 */
    private boolean optimisticToday(String prefix, String id) {
        return stateStore.isOptimisticToday(prefix, id);
    }

    /**
     * 该条目今天是否"请求已成功发出"。
     *
     * 语义变更（1.6.1）：以前读 kLast —— 但 kLast 是**最终已签**，
     * 与"乐观发出"混为一体，导致"请求一发出去就算已签"。
     * 现在读独立的 opt_ 标记：它只表示"发出去过"，不含成败结论。
     * 仍需 kLast 表示真签上时，用 optimisticSigned()（保留原名给旧调用点）。
     */
    private boolean optimisticSigned(String prefix, String id) {
        try {
            if (todayStr().equals(prefs.getString(kLast(prefix, id), ""))) return true;
            return optimisticToday(prefix, id);
        } catch (Throwable t) { return false; }
    }

    /** 只清退避状态、保留已签（第二步失败但第一步已成功时用）。 */
    private void keepSignedClearBackoff(String prefix, String id) {
        stateStore.keepSignedClearBackoff(prefix, id);
    }

    /**
     * 从 prefs 前缀反解账号索引（"acc2_" → 2）。
     * 拿不到返回 currentAccount() —— 但调用方不该依赖这个兜底，
     * 凡是"界面渲染时的账号"与"点击时的账号"可能不同的场景，都必须用本方法。
     */
    private int accountOfPrefix(String prefix) {
        try {
            if (prefix != null && prefix.startsWith("acc")) {
                int us = prefix.indexOf('_');
                if (us > 3) {
                    String num = prefix.substring(3, us);
                    if (num.length() > 0 && num.indexOf('-') < 0) {
                        return Integer.parseInt(num);
                    }
                }
            }
        } catch (Throwable t) { noteSwallowed("accountOfPrefix", t); }
        return currentAccount();
    }

    /** 从任意 View 的 Context 取 Activity 并刷新目标列表（行内按钮用）。 */
    private void refreshListFrom(Context ctx) {
        try {
            Activity a = null;
            if (ctx instanceof Activity) a = (Activity) ctx;
            if (a == null) a = lastActivity;
            if (a == null) return;
            final Activity fa = a;
            mainHandler.post(new Runnable() { @Override public void run() { showList(fa); } });
        } catch (Throwable t) { noteSwallowed("refreshListFrom", t); }
    }

    /** 该条目今天是否已被用户处置过（用于避免每天重复弹提示）。 */
    private boolean pendConfirmedToday(String prefix, String id) {
        try {
            String v = prefs.getString(prefix + "pendcfm_note_" + id, "");
            if (v == null || v.length() == 0) return false;
            int bar = v.indexOf('|');
            return bar > 0 && todayStr().equals(v.substring(0, bar));
        } catch (Throwable t) { return false; }
    }

    /**
     * 跨天清理：昨天遗留的「待确认」不再有意义 —— 它绑的是"那一次发送"，
     * 新的一天会重新发。留着只会让状态永远停在「待确认」。
     * @return 清掉的条数
     */
    private int sweepStalePendingConfirm() {
        int n = 0;
        try {
            String today = todayStr();
            int accN = Math.max(1, activatedAccounts());
            SharedPreferences.Editor e = prefs.edit();
            for (String k : new ArrayList<String>(prefs.getAll().keySet())) {
                if (!k.startsWith("acc") || !k.contains("_pendcfm_")) continue;
                // 只处理「待确认」布尔键本身；pendcfm_day_ / pendcfm_note_ /
                // pendcfm_result_ 是同族伴随键，由下面连带清理，不单独计数。
                if (k.contains("_pendcfm_day_") || k.contains("_pendcfm_note_")
                        || k.contains("_pendcfm_result_")) continue;
                if (!Boolean.TRUE.equals(prefs.getAll().get(k))) continue;
                int us = k.indexOf('_');
                String prefix = k.substring(0, us + 1);
                String id = k.substring(k.indexOf("_pendcfm_") + "_pendcfm_".length());

                // ── 判据一（权威，2026-10-03 新增）：pendcfm_day_ 是不是今天 ──
                // 这是唯一不依赖 sent_at_ 的判据。sent_at_ 在转待确认时就被删了，
                // 旧逻辑据此判"隔天残留"，导致每次重启都把当天刚标的待确认清掉，
                // 状态回到「待签」→ 闸放行 → 重发（实测群目标被刷 13 次）。
                String markedDay = prefs.getString(prefix + "pendcfm_day_" + id, "");
                if (today.equals(markedDay)) continue;            // 今天标的，保留
                if (markedDay.length() > 0) {                     // 明确是旧日期 → 真残留
                    e.remove(k);
                    e.remove(prefix + "pendcfm_day_" + id);
                    e.remove(prefix + "pendcfm_note_" + id);
                    e.remove(prefix + "pendcfm_result_" + id);
                    n++;
                    continue;
                }

                // ── 判据二（老数据兜底）：没有 pendcfm_day_ 的老记录 ──
                // 保留原逻辑，但**先查今天已签**：已签还赖在待确认里就是残留。
                if (today.equals(prefs.getString(kLast(prefix, id), ""))) {
                    e.remove(k);
                    e.remove(prefix + "pendcfm_note_" + id);
                    e.remove(prefix + "pendcfm_result_" + id);
                    n++;
                    continue;
                }
                // 无日期标注的老记录：宁可留着（用户能看到处置入口），
                // 不当"隔天残留"删 —— 删错会把正在等的处置吞掉。
            }
            if (n > 0) e.apply();
        } catch (Throwable t) { noteSwallowed("sweepStalePendingConfirm", t); }
        if (n > 0) jlog("清理跨天残留的「待确认」标记 " + n + " 个");
        return n;
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
            int[] slots = accountSlots();
            int n = slots.length;
            String cur = accountPrefix();
            android.content.SharedPreferences.Editor ed = prefs.edit();
            String[] keys = {"window", "timer", "gap", "missback", "missdead"};
            for (int i : slots) {
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
            int[] slots = accountSlots();
            int n = slots.length;
            String[] keys = {"window", "timer", "gap", "missback", "missdead"};
            android.content.SharedPreferences.Editor ed = null;
            for (int i : slots) {
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
        /* 跨天刷新（2026-09-29）：plan key 已随日期自变，如存在即今天；判断也够 */
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
                if (isFrozen(prefix, id)) continue;                                        // 冻结不排（2026-09-29）
                /* 2026-09-29 fix: frozen targets must not appear in timer plans.
                   ensureTimerPlan only filtered "signed today". Frozen targets were still
                   scheduled → skipScheduling blocked execution but the entry remained →
                   UI showed "待签" indefinitely. */
                if (isFrozen(prefix, id)) continue;
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

    // ════════════════════════════════════════════════════════════════
    //  调度区（Refactor 1.6.1 · 步骤 7）
    //
    //  职责：决定"什么时候、对哪个账号、发哪个目标"，并排期。
    //  不负责：实际发送（→ sendSign）、状态判定（→ SignStateStore/SignLogic）。
    //
    //  入口清单（共 11 个，改动时按此顺序理解）：
    //    ① scheduleWindowWake   窗口进入时刻的精确闹钟
    //    ② scheduleTimerPlan    把"下一个未到点目标"排到它的计划时刻
    //    ③ sweepDue             到点巡检：处理"计划已到/已过但今天未签"
    //    ④ armTask              实际排期（postDelayed），**已用 Ctx 锁定账号**
    //    ⑤ kickSchedule         统一入口：sweepDue + scheduleTimerPlan（SCHED_LOCK 保护）
    //    ⑥ scheduleTickLoop     心跳（三档间隔，自适应）
    //    ⑦ schedulePoll         轮询补签
    //    ⑧ skipScheduling       闸门：已签/在途/已发出待结论 → 跳过
    //    ⑨ isSentPendingFresh   闸门辅助：判断"已发出且未到判定时限"
    //    ⑩ markEnqueueFor       排队去重（按账号）
    //    ⑪ enqueueTry           非定时模式的排队入口
    //
    //  不变式：
    //    S1. 同一目标同一时刻**只有一个**待发任务（armTask 先 removeCallbacks 旧的）
    //    S2. 所有异步任务持有 Ctx，执行时不读 currentAccount()
    //    S3. 闸门判断只有一个实现（skipScheduling / SignLogic.decideSign）
    //    S4. sweepDue 进入即累加 panelstale 类计数（旧的"只在 else 分支累加"是 bug）
    // ════════════════════════════════════════════════════════════════

    private void scheduleTimerPlan() {
        try {
            if (!TIMER_ENABLED) return;
            if (!inWindow()) return;              // 未到点目标只在窗口内，窗口外交给 sweepDue 补
            if (pendingTimerFire != null) return;
            final int _schedAcc = currentAccount();
            // 同 sweepDue：停用账号不排精确任务。
            if (!isAccountEnabled(_schedAcc)) return;
            String prefix = accountPrefix();
            // 先做状态归位：把"已发出但久无结论"的转「待确认」，
            // 否则它们会被 skipScheduling 当作"在途"一直跳过、或过期后被当"没发过"重发。
            // 遍历全部槽位（不读 currentAccount）：其它账号的在途目标同样需要归位。
            for (int _pa2 : accountSlots()) {
                promoteSilentToPendingAll(accountPrefix(_pa2));
            }
            ensureTimerPlan(prefix);
            List<Map<String, Object>> plan = timerPlan(prefix);
            java.util.Calendar c = java.util.Calendar.getInstance();
            int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            for (Map<String, Object> m : plan) {
                String id = String.valueOf(m.get("id"));
                if (todayStr().equals(prefs.getString(kLast(prefix, id), ""))) continue;
                if (isInFlightOrPending(prefix, id)) {
                    logd("[定时] " + id + " 已有请求在途/待结论，不重排（防重启后重复发）");
                    continue;
                }
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
            // 状态归位：把「已发出但久无结论」的转「待确认」。
            //
            // 为什么必须放在这里（2026-09-30 用户实测「补签一直在补」）：
            //   唯一原本的调用点在 scheduleTimerPlan，而它开头就是
            //   `if (!inWindow()) return;` —— 补签时段（窗口外）**根本走不到**，
            //   于是群聊/无 bot 的目标永远停在「已发出」，sent_at_ 过期后被当成
            //   "没发过"重新排期 → 又超时 → 无限补签。
            //   归位成「待确认」后，skipScheduling 会拦住它，等用户处置。
            for (int _pa : accountSlots()) {
                promoteSilentToPendingAll(accountPrefix(_pa));
            }
            final int _schedAcc = currentAccount();
            // 停用账号不排任务。sendSign 仍会兜住（那里是唯一权威），
            // 但提前返回可以少一轮「排任务→被拒」的空转与日志噪音。
            if (!isAccountEnabled(_schedAcc)) return;
            String prefix = accountPrefix();
            ensureTimerPlan(prefix);
            List<Map<String, Object>> plan = timerPlan(prefix);
            java.util.Calendar c = java.util.Calendar.getInstance();
            int nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            for (Map<String, Object> m : plan) {
                String id = String.valueOf(m.get("id"));
                if (todayStr().equals(prefs.getString(kLast(prefix, id), ""))) continue;
                // 条目已被删除（用户删目标 / 切换配置）却还留在当日时刻表里：
                // 直接跳过，避免对已不存在的目标排任务（用户实测删除后仍冒出补签排期）。
                if (findEntryById(id, accountOfPrefix(prefix)) == null) continue;
                if (skipScheduling(prefix, id)) {
                    // 各种情况都会走到这：今天已签 / 在途 / 已发出待结论 / 待确认 / 熔断。
                    // 以前这里是三段内联判断，与 sendSign、trySignAll 各写一份 ——
                    // 群签到卡在"发出但无结论"的空档里被反复重发就是漏了其中一段。
                    continue;
                }
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
                    // 重试计数按天判（2026-10-03 修）：原先不查 retry_day_，
                    // 昨天用满的计数会让今天的补签直接不排 —— 少签一天且无提示。
                    if (prefs.getInt(kRetry(prefix, id), 0) >= RETRY_LIMIT
                            && todayStr().equals(prefs.getString(kRetryDay(prefix, id), ""))) continue;
                    // 发送次数已达上限：今天不再补，等用户处置（与 sendSign 同一道闸）
                    if (isSendAttemptsExhausted(prefix, id)) continue;
                    delayMs = 60000L + (long) (random.nextInt(240000));    // 窗口后：1~5 分钟随机补
                    tag = "[定时] 错过补签 " + m.get("text") + "（原计划 " + hhmm(fireMin) + "）约 " + (delayMs / 60000L) + " 分钟后触发";
                    // 记「这次是补签」：只在这一条路径写（另一条 win 分支是准点到点）。
                    // 落库时刻 = 排任务的时刻，也就是"决定补签"的时刻 —— 比实际发出早
                    // 几秒到几分钟（随机延迟），但对用户"什么时候开始补的"这个问题足够准，
                    // 且不受执行时任务被取消的影响。
                    try { stateStore.markMissTriggered(prefix, id, System.currentTimeMillis()); }
                    catch (Throwable _eMT) { noteSwallowed("sweepDue-missmark", _eMT); }
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
        //
        // 重构 1.6.1：改为捕获 AccountManager.Ctx —— 不可变快照，
        // 执行时只用它，**完全不读 currentAccount()**（原来的"执行时校验"保留，
        // 用于发现"用户切号了"这种需要放弃的场景，但不再依赖它来取前缀）。
        final AccountManager.Ctx ctx = accountManager.ctxOf(acc, "armTask");
        Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    if (sweep) pendingSweep = null; else pendingTimerFire = null;
                    if (!TIMER_ENABLED || (!inWindow() && !inMissBackTime())) { kickSchedule(); return; }
                    // 任务已经持有不可变 Ctx；用户切换 UI 账号不能取消后台任务。
                    // 这里只使用任务创建时锁定的账号。
                    String fPrefix = ctx.prefix;
                    String fid = String.valueOf(fm.get("id"));
                    if (skipScheduling(fPrefix, fid)) {
                        logd("[定时] " + fid + " 跳过排期（已签/在途/已发出待结论）");
                        kickSchedule(); return;
                    }
                    long fdid = ((Number) fm.get("did")).longValue();
                    Map<String, Object> entry = findEntryById(fid, ctx.account);
                    if (entry == null) { kickSchedule(); return; }
                    jlog("[定时] 执行签到 " + fdid + " " + (KIND_CB.equals(fm.get("kind")) ? "[回调] " : "text=") + fm.get("text"));
                    sendSign(entry, ctx, false);   // 用排任务时捕获的 Ctx
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
                    // 每轮心跳顺手把"发出很久仍无结论"的目标转成「待确认」。
                    // 放在这里而不是只放界面：用户不开面板时也能转换，
                    // 状态不会长期停在「已发出」然后悄悄退回「待签」。
                    try {
                        for (int _pa : accountSlots()) {
                            promoteSilentToPendingAll(accountPrefix(_pa));
                        }
                    } catch (Throwable _eP) { noteSwallowed("tick-promote", _eP); }
                    // 到点就发每日摘要（幂等）。必须在心跳里查 ——
                    // 摘要原本只由「签到轮次结束」触发，窗口结束后若没有新轮次
                    // 就永远不会发（2026-10-01 实测：日志里 17 次「暂不发汇总」、
                    // 摘要 0 次）。放在 promote 之后：先归类在途目标，再判定更准。
                    try {
                        if (NOTIFY_ON) {
                            for (int _sa : accountSlots()) trySendDailySummary(_sa);
                        }
                    } catch (Throwable _eS) { noteSwallowed("tick-summary", _eS); }
                    if (TIMER_ENABLED) {
                        kickSchedule();
                    } else if (inWindow()) {
                        // 非定时模式：遍历所有启用账号，各有未签就签（含非当前账号）
                        int cur = currentAccount();
                        if (hasUnsignedTarget()) enqueueTry("心跳检测");
                        // 遍历真实账号槽位，而不是 0..activatedAccounts()-1。
                        // selectedAccount 读到的值不等于账号的「第几个」（实测 Nagram XF
                        // 会读到 7、9），按连续区间遍历会漏掉或错位。
                        for (int i : accountSlots()) {
                            if (i == cur) continue;
                            if (!isAccountEnabled(i)) continue;
                            if (accountHasUnsigned(i)) {
                                final int fAcc = i;
                                // 走带去重的排期（原来直调 trySignAllFor，没有去重；
                                // 而 kLast 要等回复判定才写，空档期内每轮心跳都会重排一次）。
                                if (markEnqueueFor(fAcc)) {
                                    mainHandler.postDelayed(new Runnable(){ @Override public void run(){
                                        try { trySignAllFor("心跳(账号" + (fAcc + 1) + ")", false, fAcc); } catch (Throwable _e24) { noteSwallowed("scheduleTickLoop", _e24); }
                                    } }, 5000L + (long) (random.nextInt(10000)));
                                }
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
                if (isDailyBlocked(prefix, id) || isPermanentFailedToday(prefix, id)) continue;   // 今日熔断
                if (isPendingConfirm(prefix, id)) continue;   // 待确认：已有归类，等用户处置，别再自动重发
                if (!today.equals(prefs.getString(kLast(prefix, id), ""))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 当前账号是否还有今天没签的目标 */
    private boolean hasUnsignedTarget() {
        try {
            // 停用账号一律视为「已签完」：心跳据此降到 10 分钟档，不再 45 秒空转。
            if (!isAccountEnabled(currentAccount())) return false;
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
            // 时间口径说明：这一版新增了「实签时刻」和「补签时刻」，给用户一句话解释
            TextView legend = new TextView(act);
            legend.setTextSize(Theme.TS_CAPTION);
            legend.setTextColor(Theme.termFaint(act));
            legend.setTypeface(Theme.text());
            legend.setText(Lang.tr("时间 = 计划 → 实际签上；补签会额外标出补签时刻"));
            legend.setPadding(dp(4), 0, dp(4), dp(8));
            box.addView(legend);

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
                // 时间口径（用户要求「直接看啥时候签的、啥时候补的」）：
                //   planHM  计划时刻（来自当日时刻表）
                //   signHM  实际签上时刻（signed_at_，回退 sent_at_）
                //   missHM  补签触发时刻（miss_at_，只有走补签路径才有）
                String badge, sub;
                int col;
                String planHM = fireMin >= 0 ? String.format("%02d:%02d", fireMin / 60, fireMin % 60) : "";
                long todayStart = startOfTodayMs();
                if (done) {
                    long signAt = stateStore.signedAtMs(fPrefix, id);
                    String signHM = SignLogic.hhmmOf(signAt);
                    long missAt = stateStore.missAtMsToday(fPrefix, id);
                    boolean wasMiss = SignLogic.isMissBack(missAt > 0L, signAt, fireMin,
                            todayStart, SignLogic.MISS_JUDGE_GRACE_MIN);
                    badge = Lang.tr(wasMiss ? "补签" : "准点"); col = Theme.termGreen(act);
                    if (fireMin < 0) {
                        sub = signHM.length() > 0 ? Lang.tf("实签 {0}", signHM) : Lang.tr("今日已签");
                    } else if (signHM.length() == 0) {
                        sub = Lang.tf("计划 {0} · 已完成", planHM);
                    } else if (wasMiss) {
                        StringBuilder sb = new StringBuilder(Lang.tf("计划 {0} → 实签 {1}", planHM, signHM));
                        if (missAt > 0L) {
                            String missHM = SignLogic.hhmmOf(missAt);
                            int lateMin = SignLogic.minutesSince(todayStart + (long) fireMin * 60000L, signAt);
                            sb.append(" · ").append(Lang.tf("补签 {0}", missHM));
                            if (lateMin > 0) sb.append(Lang.tf("（晚 {0}）", SignLogic.humanMinutes(lateMin)));
                        }
                        sub = sb.toString();
                    } else {
                        sub = Lang.tf("计划 {0} → 实签 {1}", planHM, signHM);
                    }
                    doneN++;
                } else if (fireMin < 0) {
                    badge = Lang.tr("无计划"); col = Theme.termMuted(act);
                    sub = Lang.tr("未排进今日时刻表"); skipN++;
                } else if (fireMin <= nowMin) {
                    int overMin = SignLogic.minutesSince(todayStart + (long) fireMin * 60000L,
                            System.currentTimeMillis());
                    if (!TIMER_ENABLED || !MISS_BACK) {
                        badge = Lang.tr("已跳过"); col = Theme.termMuted(act);
                        sub = Lang.tf("计划 {0} · 已过 {1} · {2}", planHM, SignLogic.humanMinutes(overMin),
                                Lang.tr(!TIMER_ENABLED ? "定时未开" : "补签未开"));
                        skipN++;
                    } else if (nowMin > MISS_DEADLINE) {
                        badge = Lang.tr("已过期"); col = Theme.termMuted(act);
                        sub = Lang.tf("计划 {0} · 已过补签截止 {1}", planHM,
                                String.format("%02d:%02d", MISS_DEADLINE / 60, MISS_DEADLINE % 60));
                        skipN++;
                    } else {
                        badge = Lang.tr("待补"); col = Theme.termAmber(act);
                        long missAt = stateStore.missAtMsToday(fPrefix, id);
                        if (missAt > 0L) {
                            sub = Lang.tf("计划 {0} · 已过 {1} · 补签已于 {2} 排队",
                                    planHM, SignLogic.humanMinutes(overMin), SignLogic.hhmmOf(missAt));
                        } else {
                            sub = Lang.tf("计划 {0} · 已过 {1} · 后台 1~5 分钟内补（截止 {2}）",
                                    planHM, SignLogic.humanMinutes(overMin),
                                    String.format("%02d:%02d", MISS_DEADLINE / 60, MISS_DEADLINE % 60));
                        }
                        waitN++; missN++;
                    }
                } else {
                    badge = Lang.tr("未到点"); col = Theme.termCyan(act);
                    int leftMin = fireMin - nowMin;
                    sub = Lang.tf("计划 {0} · 还有 {1}", planHM, SignLogic.humanMinutes(leftMin));
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
                            toastAfterSign(sendSign(fm2, currentAccount()), fm2);
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
            foot.setText(Lang.tf("已签 {0} · 待补 {1} · 已跳过 {2}", doneN, waitN, skipN) + (missN > 0 ? Lang.tf(" · 错过 {0}", missN) : ""));
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

    /**
     * 签到日历（2026-09-30 重做）。
     *
     * 改前的问题：
     *   ① 格子标 1~14 的顺序号，用户根本不知道哪格是今天，得回头看说明行；
     *   ② 说明行「1 = 13 天前 · 14 = 今天（绿 = 已签到）」要在脑子里做减法；
     *   ③ 就 14 个平涂色块，没有任何信息密度与节奏感。
     *
     * 现在：
     *   · 格子直接标**真实日号**（如 17 / 18 / 19），今天那格标「今」并加脉冲环；
     *   · 去掉说明行，改为数据摘要「连续 N 天 · 本月 N 天 · 共 N 天」，直接给结论；
     *   · 已签格 = 深绿底 + 发光描边 + 顶部刻度条；未签格 = 极暗底 + 弱描边；
     *   · 今天的格子有向外扩散的脉冲，滚动时一眼能找到"现在"。
     */
    private LinearLayout streakCalendar(Activity act) {
        try {
            final String prefix = accountPrefix();
            // 渲染前先自愈一次：清掉历史版本留下的非法 last_ / sign_days 值。
            // 幂等 —— 无脏值时零开销（只做几次 prefs 读），可安全每帧调用。
            purgeDirtySignState();
            java.util.Set<String> days = signDays(prefix);
            if (days.isEmpty() && hasSignedToday(prefix)) days.add(todayStr());

            final java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            final boolean dark = Theme.dark(act);
            final int GREEN = Theme.termGreen(act);
            final int CYAN  = Theme.termCyan(act);
            final int MUTED = Theme.termMuted(act);
            final int FAINT = Theme.termFaint(act);
            final int PINK  = Theme.termPink(act);   // 「今天未签」用危险色，与"已签绿"形成对照

            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);

            final Icons.DayCellDrawable[] draws = new Icons.DayCellDrawable[14];
            LinearLayout row1 = new LinearLayout(act); row1.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout row2 = new LinearLayout(act); row2.setOrientation(LinearLayout.HORIZONTAL);

            int signedIn14 = 0;

            // ── 今日状态（提前算好，供"今天"那格与摘要行共用，2026-10-01）──
            // 放在循环前的原因：格子要在渲染时就知道今天该用哪个语义色，
            // 不能等摘要行算完再回头改（那样两处口径还可能不一致）。
            String todayS = todayStr();
            String yestS = yesterdayStr();
            String lsd = prefs.getString(kLastSignDate(prefix), "");
            boolean signedToday = todayS.equals(lsd) || hasSignedToday(prefix);
            int streak = SignLogic.streakDisplay(streakOf(prefix), lsd, todayS, yestS);

            java.util.Calendar ncal = java.util.Calendar.getInstance();
            int nowMin = ncal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + ncal.get(java.util.Calendar.MINUTE);
            boolean anyOn = false;
            try {
                java.util.List<Map<String, Object>> tl = new java.util.ArrayList<Map<String, Object>>();
                loadTargetsInto(prefix, tl);
                for (Map<String, Object> m : tl) {
                    if (!isFrozen(prefix, entryId(m))) { anyOn = true; break; }
                }
            } catch (Throwable ignored) {}
            final int tState = SignLogic.todayState(signedToday, nowMin,
                    SignLogic.windowRangeAny(WINDOW), MISS_DEADLINE, MISS_BACK, anyOn);

            for (int i = 0; i < 14; i++) {
                // 每次渲染都按「当天」重算：窗口随日期自动滚动，跨天不会残留旧日期
                java.util.Calendar cc = java.util.Calendar.getInstance();
                cc.set(java.util.Calendar.HOUR_OF_DAY, 12);
                cc.set(java.util.Calendar.MINUTE, 0);
                cc.set(java.util.Calendar.SECOND, 0);
                cc.set(java.util.Calendar.MILLISECOND, 0);
                cc.add(java.util.Calendar.DATE, i - 13);

                boolean on    = days.contains(f.format(cc.getTime()));
                boolean today = (i == 13);
                int dow = cc.get(java.util.Calendar.DAY_OF_WEEK);
                boolean weekend = (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY);
                // ② 计数与显示同源（2026-09-30 修）：
                // 原来「N/14」用的 signedIn14 是在循环里累加的，与格子的 on 同源，
                // 但只要 days 集合里混入窗口外／脏日期，两个数字就会对不上
                // （实测出现过「只亮 1 格却显示 2/14」）。
                // 现在直接在渲染后按 draws 里实际点亮数统计，保证「看到的」= 「数字」。
                if (on) signedIn14++;

                TextView cell = new TextView(act);
                cell.setTextSize(Theme.TS_CAPTION);
                cell.setTypeface(Theme.text());
                // 圆点/柱条样式把图形放在下半格，文字改靠上排，避免互相遮挡
                if (Icons.cellTextTop(CAL_STYLE)) {
                    cell.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(0, dp(3), 0, 0);
                } else {
                    cell.setGravity(android.view.Gravity.CENTER);
                }
                cell.setText(today ? Lang.tr("今")
                                   : String.valueOf(cc.get(java.util.Calendar.DAY_OF_MONTH)));

                // 「今天」那格按今日状态取色（2026-10-01）：
                // 已签→绿、待签→青、未签→粉、未参与→弱。
                // 这样格子颜色与摘要行文字永远同源，不会出现"格子绿着、
                // 摘要却说未签"这类自相矛盾的画面。
                int todayAccent;
                switch (tState) {
                    case SignLogic.TODAY_DONE:    todayAccent = GREEN; break;
                    case SignLogic.TODAY_PENDING: todayAccent = CYAN;  break;
                    case SignLogic.TODAY_MISSED:  todayAccent = PINK;  break;
                    default:                      todayAccent = MUTED; break;
                }
                int txtCol;
                if (today)         txtCol = todayAccent;
                else if (on)       txtCol = dark ? 0xFFE8FFF6 : blendText(dark, GREEN);
                else if (weekend)  txtCol = FAINT;
                else               txtCol = MUTED;
                cell.setTextColor(txtCol);

                int accent = today ? todayAccent : GREEN;
                Icons.DayCellDrawable d = new Icons.DayCellDrawable(
                        dp(30), on, today, dark, accent, MUTED, CAL_STYLE);
                draws[i] = d;
                cell.setBackground(d);

                android.widget.LinearLayout.LayoutParams lp =
                        new android.widget.LinearLayout.LayoutParams(0, dp(30), 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                (i < 7 ? row1 : row2).addView(cell, lp);
            }

            box.addView(row1);
            box.addView(row2);

            // 今天格的脉冲：只用一个 ValueAnimator
            final Icons.DayCellDrawable todayDraw = draws[13];
            if (todayDraw != null) {
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(1800);
                va.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                va.setRepeatMode(android.animation.ValueAnimator.RESTART);
                va.setInterpolator(new android.view.animation.LinearInterpolator());
                va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        todayDraw.setPulse((Float) a.getAnimatedValue());
                    }
                });
                va.start();
            }

            // ── 摘要行（2026-09-30 方案 B）──
            // ── 摘要行（2026-10-01 重做）──
            //
            // 设计目标：回答"我今天要不要动手"——这是唯一可行动的信息。
            //
            // 旧版问题（用户实测）：只显示「连续 14 天」+「14/14」两个**历史**数字，
            // 而新的一天还没签时连续天数仍沿用旧值（streakOf 把"昨天签过"也算连续），
            // 与日历里今天那格的空框互相矛盾 —— 用户看不出今天到底要不要签。
            //
            // 新版三栏（左→右，视觉权重递减）：
            //   ① 今日状态：图标 + 短文案，**唯一带语义色的元素**，一眼看到
            //   ② 连续天数：中性色，是"已完成的历史"，今天签了才 +1
            //   ③ 近 14 天完成度：最弱色，趋势参考
            //
            // 为什么今天没签**不归零**：中午打开看到"0 天"会让人以为白签了。
            // 今天的状态由 ① 独立表达，不靠连续天数归零来暗示。
            //
            // 图标与配色：4 个矢量图标（today-done/wait/miss/idle）在 24 网格内
            // 纯几何绘制，与既有图标同线宽，因此在日间/夜间 + 全部 6 种日历格
            // 样式下都不与配色打架；不使用 emoji。
            // 今日状态与连续天数已在日历循环前算好（见上），此处直接复用 ——
            // 同一份数据、同一套口径，格子与文字不会打架。
            String tIcon, tText; int tColor;
            switch (tState) {
                case SignLogic.TODAY_DONE:
                    tIcon = "today-done"; tText = Lang.tr("今天已签"); tColor = GREEN; break;
                case SignLogic.TODAY_PENDING:
                    tIcon = "today-wait"; tText = Lang.tr("今天待签"); tColor = CYAN; break;
                case SignLogic.TODAY_MISSED:
                    tIcon = "today-miss"; tText = Lang.tr("今天未签"); tColor = PINK; break;
                default:
                    tIcon = "today-idle"; tText = Lang.tr("今天未参与"); tColor = MUTED; break;
            }

            LinearLayout sum = new LinearLayout(act);
            sum.setOrientation(LinearLayout.HORIZONTAL);
            sum.setGravity(android.view.Gravity.CENTER_VERTICAL);
            sum.setPadding(0, dp(8), 0, 0);

            // ① 今日状态：图标 + 文案，唯一承载状态语义
            TextView left = new TextView(act);
            left.setTextSize(Theme.TS_CAPTION);
            left.setTypeface(Theme.text());
            left.setTextColor(tColor);
            left.setGravity(android.view.Gravity.CENTER_VERTICAL);
            left.setSingleLine(true);
            left.setText(tText);
            android.graphics.drawable.Drawable bi = Icons.d(act, tIcon, 11f, tColor);
            if (bi != null) {
                int sz = dp(11);
                bi.setBounds(0, 0, sz, sz);
                left.setCompoundDrawables(bi, null, null, null);
                left.setCompoundDrawablePadding(dp(4));
            }
            sum.addView(left, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

            // ② 连续天数：中性色（不抢 ① 的注意力），今天未签时也照常显示
            if (streak > 0) {
                TextView mid = new TextView(act);
                mid.setTextSize(Theme.TS_CAPTION);
                mid.setTypeface(Theme.text());
                mid.setTextColor(MUTED);
                mid.setGravity(android.view.Gravity.CENTER);
                mid.setSingleLine(true);
                mid.setText(Lang.tf("连续 {0} 天", streak));
                sum.addView(mid, new android.widget.LinearLayout.LayoutParams(-2, -2));
            }

            // ③ 近 14 天完成度：**仅在日历有缺口时才显示**（2026-10-01 按用户反馈收紧）
            //
            // 用户实测反馈「14/14 太多了」——确实。满勤时这个数字就是在数上面那 14 个
            // 绿格，日历本身已经说完了，数字纯属重复（截图里同一信息出现了四次：
            // 头部「已签 10/10」、状态行「今日已全部完成」、14 个绿格、以及这一行）。
            //
            // 保留条件：只有**中间断过**（signedIn14 < 14）时，
            // 「连续 N 天」与「N/14」的**差值**才有信息量 —— 它解释了
            // "为什么连续天数不是 14"。满勤时两者恒等，没有解释价值。
            //
            // 且必须**有启用中的目标**才显示：清空数据后（目标 0、日历全空）
            // 「近 14 天 0/14」是纯噪音 —— 此时说明职责由下方的
            // "还没有目标，去该账号学一个"引导行承担（2026-10-01 用户实测）。
            if (signedIn14 < 14 && anyOn) {
                TextView right = new TextView(act);
                right.setTextSize(Theme.TS_CAPTION);
                right.setTypeface(Theme.text());
                right.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
                right.setSingleLine(true);
                int rCol = (signedIn14 * 2 >= 14) ? CYAN : FAINT;
                right.setTextColor(rCol);
                right.setText(Lang.tf("近 14 天 {0}", signedIn14 + "/14"));
                android.widget.LinearLayout.LayoutParams rlp =
                        new android.widget.LinearLayout.LayoutParams(-2, -2);
                rlp.leftMargin = dp(streak > 0 ? 10 : 0);
                sum.addView(right, rlp);
            }

            box.addView(sum);

            return box;
        } catch (Throwable t) { return null; }
    }

    /** 亮色模式下把强调色调深，保证在浅底上可读 */
    private int blendText(boolean dark, int accent) {
        if (dark) return 0xFFE8FFF6;
        int r = (accent >> 16) & 0xFF, g = (accent >> 8) & 0xFF, b = accent & 0xFF;
        // 与黑色按 45% 混合：压暗但保留色相
        return 0xFF000000
             | (((int) (r * 0.55f)) << 16)
             | (((int) (g * 0.55f)) << 8)
             | ((int) (b * 0.55f));
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
                    toastAfterSign(sendSign(m, currentAccount()), m);
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
                // skipSigned=true：批量入口只签未签的，已签/已发出的跳过（用户反馈）
                int _fired = trySignAllFor("手动全部", true, currentAccount(), true);
                toast(_fired > 0 ? Lang.tf("已对 {0} 个目标发起签到，结果见运行日志", _fired)
                                 : Lang.tr("没有可签的目标（都签过了 / 暂停 / 冻结）"));
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
            // 控件句柄容器：分区方法往里写，保存块从里读（见 SettingsRefs）
            final SettingsRefs R = new SettingsRefs(act, box);

            // ── 折叠分区（2026-09-30）──
            // 起因：原设置页 5 个分区约 20 个控件一路平铺，要划 5~6 屏，
            //       且保存按钮在最底部，改完上面某项得一路划下去。
            // 现在：分区折成卡片，默认只展开最常改的「签到核心」，
            //       首屏即可看全所有分区标题；标题右侧显示当前值摘要。
            final int ACC_CORE  = Theme.termCyan(act);
            final int ACC_LOOK  = Theme.termPink(act);
            final int ACC_LEARN = Theme.termGreen(act);
            final int ACC_NOTI  = Theme.termAmber(act);

            Object[] c1 = collapseCard(act, "签到核心", "bolt", false, ACC_CORE);
            box.addView((View) c1[0]);
            R.section = (LinearLayout) c1[1];
            c1[3] = cardSummary(act, (LinearLayout) c1[2], ACC_CORE);
            buildSectionSignCore(R);

            Object[] c2 = collapseCard(act, "外观", "bulb", false, ACC_LOOK);
            box.addView((View) c2[0]);
            R.section = (LinearLayout) c2[1];
            c2[3] = cardSummary(act, (LinearLayout) c2[2], ACC_LOOK);
            buildSectionAppearance(R);

            Object[] c3 = collapseCard(act, "学习与判定", "target", false, ACC_LEARN);
            box.addView((View) c3[0]);
            R.section = (LinearLayout) c3[1];
            c3[3] = cardSummary(act, (LinearLayout) c3[2], ACC_LEARN);
            buildSectionLearn(R);
            buildSectionKeywords(R);

            Object[] c4 = collapseCard(act, "通知", "bell", false, ACC_NOTI);
            box.addView((View) c4[0]);
            R.section = (LinearLayout) c4[1];
            c4[3] = cardSummary(act, (LinearLayout) c4[2], ACC_NOTI);
            buildSectionNotify(R);

            // ── 填充摘要：收起时也能看到当前值 ──
            try { ((TextView) c1[3]).setText(WINDOW + (TIMER_ENABLED ? "" : " · " + Lang.tr("未开定时"))); } catch (Throwable ignored) {}
            try {
                String th = THEME_MODE == 0 ? Lang.tr("自动") : (THEME_MODE == 1 ? Lang.tr("日间") : Lang.tr("夜间"));
                String lg = Lang.MODE == 0 ? Lang.tr("跟随系统") : (Lang.MODE == 1 ? "中文" : "English");
                ((TextView) c2[3]).setText(th + " · " + lg); } catch (Throwable ignored) {}
            try {
                int on = 0;
                if (AUTO_LEARN) on++; if (AUTO_LEARN_NET) on++;
                if (JUDGE_ENABLED) on++; if (LOOSE_MODE) on++;
                ((TextView) c3[3]).setText(Lang.tf("已开 {0}/4", on)); } catch (Throwable ignored) {}
            try { ((TextView) c4[3]).setText(NOTIFY_ON ? (NOTIFY_FAIL_ONLY ? Lang.tr("仅失败") : Lang.tr("全部")) : Lang.tr("关闭")); } catch (Throwable ignored) {}

            // ── 操作 ──
            sectionHeader(box, act, "▍操作");
            Button ok = mkBtnPrimary(act);
            withIconText(act, ok, "save", "保存设置");
            ok.setOnClickListener(v -> {
                String k = R.keywordsEd.getText().toString().trim();
                LEARN_KEYWORDS = k.isEmpty() ? DEF_KEYWORDS : k;
                try {
                    int r = Integer.parseInt(R.retryLimitEd.getText().toString().trim());
                    if (r > 0 && r <= 99) RETRY_LIMIT = r;
                } catch (Throwable ignored) {}
                WAKE_CMD = R.wakeCmdEd.getText().toString().trim();
                String wv = String.format("%02d:%02d-%02d:%02d", R.wStart[0] / 60, R.wStart[0] % 60, R.wEnd[0] / 60, R.wEnd[0] % 60);
                if (windowRangeOf(wv) == null) { toast("时间范围错误：结束需晚于开始"); return; }
                boolean winChanged = !String.valueOf(WINDOW).equals(wv);
                String oldWindow = WINDOW;
                WINDOW = wv;
                TIMER_ENABLED = R.timerSw.isChecked();
                NOTIFY_ON = R.notifySw.isChecked();
                NOTIFY_FAIL_ONLY = R.notifyFailSw.isChecked();
                THEME_MODE = R.themeMode;
                CAL_STYLE = R.calStyle;
                Theme.mode = THEME_MODE;
                try { android.content.SharedPreferences.Editor le = prefs.edit(); le.putInt("jmb_lang", Lang.MODE); le.apply(); } catch (Throwable ignored) {}
                MISS_BACK = R.missBackSw.isChecked();
                try { MISS_DEADLINE = R.missDeadlineMin; if (MISS_DEADLINE < 0) MISS_DEADLINE = 0; if (MISS_DEADLINE > 24 * 60 - 1) MISS_DEADLINE = 24 * 60 - 1; } catch (Throwable ignored) {}
                scheduleTickLoop();   // 确保心跳在跑（开启补签后立即可用，不等触发源）
                try { GAP_MIN = Integer.parseInt(R.gapEd.getText().toString().trim()); if (GAP_MIN < 0) GAP_MIN = 0; if (GAP_MIN > 240) GAP_MIN = 240; } catch (Throwable ignored) {}
                // 清掉旧时刻表，重新按新设置生成（窗口/间隔/补签改动都走这里）
                try { prefs.edit().remove(kTimerPlan(accountPrefix(), todayStr())).apply(); } catch (Throwable ignored) {}
                if (winChanged) jlog("[定时] 窗口已改 " + oldWindow + " → " + wv + "，当日计划作废待重排");
                scheduleWindowWake();
                if (inWindow()) { if (TIMER_ENABLED) kickSchedule(); else enqueueTry("进入窗口"); }
                AUTO_LEARN = R.autoLearnSw.isChecked();
                AUTO_LEARN_NET = R.autoLearnNetSw.isChecked();
                AUTO_LEARN_NET_CONFIRM = R.autoLearnNetCfmSw.isChecked();
                JUDGE_ENABLED = R.judgeSw.isChecked();
                LOOSE_MODE = R.looseSw.isChecked();
                JUDGE_USE_CUSTOM = R.judgeCustomSw.isChecked();
                boolean _cfgSaveOk = true;
                try {
                    android.content.SharedPreferences.Editor ed = prefs.edit();
                    ed.putString("jmb_keywords", LEARN_KEYWORDS)
                      .putInt("jmb_retry", RETRY_LIMIT)
                      .putString("jmb_wake_cmd", WAKE_CMD)
                      .putInt("jmb_theme", THEME_MODE)
                      .putInt("jmb_cal_style", CAL_STYLE)
                      .putBoolean("jmb_skip_nav", R.skipNavSw != null && R.skipNavSw.isChecked())
                      .putBoolean("jmb_autolearn", AUTO_LEARN)
                      .putBoolean("jmb_autolearn_net", AUTO_LEARN_NET)
                      .putBoolean("jmb_autolearn_net_confirm", AUTO_LEARN_NET_CONFIRM)
                      .putBoolean("jmb_judge", JUDGE_ENABLED)
                      .putBoolean("jmb_loose", LOOSE_MODE)
                      .putBoolean("jmb_judge_custom", JUDGE_USE_CUSTOM)
                      .putString("jmb_ok_words", R.okWordsEd.getText().toString().trim())
                      .putString("jmb_fail_words", R.failWordsEd.getText().toString().trim())
                      .putBoolean("jmb_notify", NOTIFY_ON)
                      .putBoolean("jmb_notify_fail_only", NOTIFY_FAIL_ONLY);
                    putCfg(ed, "window", WINDOW);
                    putCfg(ed, "timer", TIMER_ENABLED);
                    putCfg(ed, "gap", GAP_MIN);
                    LEARN_EXCLUDE = String.valueOf(R.excludeEd.getText()).trim();
                    putCfg(ed, "exclude", LEARN_EXCLUDE);
                    putCfg(ed, "blocked_dids", blockedDidsToStr());
                    putCfg(ed, "missback", MISS_BACK);
                    putCfg(ed, "missdead", MISS_DEADLINE);
                    // commit() 而非 apply()：设置保存是用户显式动作，
                    // 必须立刻落盘并可校验；apply() 异步且吞异常，
                    // 写失败时用户仍看到"设置已保存"（静默丢配置）。
                    _cfgSaveOk = ed.commit();
                    } catch (Throwable _eCfg) { _cfgSaveOk = false; noteSwallowed("saveConfig", _eCfg); }
                try { prefs.edit().putLong("jmb_config_ts", System.currentTimeMillis()).apply(); } catch (Throwable ignored) {}
                try { syncNow(true); } catch (Throwable ignored) {}
                toast(_cfgSaveOk ? Lang.tr("设置已保存")
                                : Lang.tr("设置保存失败：写盘出错，请检查存储空间"));
                jlog("设置更新: 关键词=" + LEARN_KEYWORDS + " 重试上限=" + RETRY_LIMIT + " 唤醒命令=" + WAKE_CMD + " 窗口=" + WINDOW + " 定时=" + (TIMER_ENABLED ? "开" : "关")
                        + " 自动判定=" + (JUDGE_ENABLED ? "开" : "关") + " 宽松模式=" + (LOOSE_MODE ? "开" : "关") + " 间隔=" + GAP_MIN + " 错过补签=" + (MISS_BACK ? "开" : "关") + " 补签截止=" + String.format("%02d:%02d", MISS_DEADLINE / 60, MISS_DEADLINE % 60)
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
            sb.append("当前账号: ").append(accountLabel(currentAccount())).append("  目标数: ").append(acctTargetCount(currentAccount())).append("\n");
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
              .append("  关键词=").append(LEARN_KEYWORDS == null || LEARN_KEYWORDS.trim().isEmpty() ? "(空=全收)" : LEARN_KEYWORDS)
              .append("\n");
            // 判定模式全貌 —— 排障必看。缺了这几项就只能靠猜
            // （2026-09-25 就吃过亏：想知道"宽松模式到底开没开"却无从查证）。
            sb.append("判定模式: 自动判定=").append(JUDGE_ENABLED ? "开" : "关")
              .append("  宽松模式=").append(LOOSE_MODE ? "开（有回复即算成功）" : "关")
              .append("  自定义词=").append(JUDGE_USE_CUSTOM ? "开" : "关")
              .append("  附加成功词=").append(strOf("jmb_ok_words") == null || strOf("jmb_ok_words").trim().isEmpty() ? "(无)" : strOf("jmb_ok_words"))
              .append("  附加失败词=").append(strOf("jmb_fail_words") == null || strOf("jmb_fail_words").trim().isEmpty() ? "(无)" : strOf("jmb_fail_words"))
              .append("\n");
            sb.append("排除配置: bot=").append(LEARN_BLOCKED_DIDS.size()).append(" 个  规则=")
              .append(LEARN_EXCLUDE == null || LEARN_EXCLUDE.trim().isEmpty() ? "(无)" : LEARN_EXCLUDE)
              .append("  捕获武装=").append(captureArmed ? "是" : "否")
              .append("  武装时账号=").append(captureAcc < 0 ? "无" : accountLabel(captureAcc)).append("\n");
            sb.append("账号字段: selectedAccount=").append(lastRawAccount)
              .append("  已登录=").append(activatedAccounts());
            if (lastRawAccount < 0) sb.append("  ← 值为负，非法，本轮跳过账号操作");
            else if (lastRawAccount >= activatedAccounts())
                sb.append("  ← 越界，已按最后一个账号处理（索引 " + (activatedAccounts() - 1) + "）");
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
                  .append("  目标=").append(acctTargetCount(currentAccount()))
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
            netReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    timerHook("网络恢复");
                }
            };
            appContext.registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
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
    /**
     * 当天失败计数 +1，并在达到上限时冻结该目标到明天。
     *
     * 为什么要独立于 retry_：retry_ 会被「成功判定」清零。当 bot 先回
     * "正在签到"（宽松模式算成功 → retry=0）再回"请先关注"（算失败 → retry=1）时，
     * 计数在 0/1 震荡，永远到不了 RETRY_LIMIT，一天被反复重签。
     *
     * @param permanent 是否确定性失败（请先关注 / 活动已结束 等）—— 命中即立刻冻结
     * @return 是否已冻结
     */
    private boolean bumpFailsToday(String prefix, String id, boolean permanent) {
        try {
            String today = todayStr();
            String dayKey = kFailsDay(prefix, id);
            int n = prefs.getInt(kFailsToday(prefix, id), 0);
            // 跨天重置：计数只对当天有效
            if (!today.equals(prefs.getString(dayKey, ""))) n = 0;
            n++;
            boolean blocked = permanent || n >= SignLogic.FAILS_PER_DAY_LIMIT;
            prefs.edit().putInt(kFailsToday(prefix, id), n)
                 .putString(dayKey, today).commit();
            if (blocked) {
                logw("[熔断] " + id + " 今日失败 " + n + " 次"
                        + (permanent ? "（确定性失败：重试不会成功）" : "")
                        + "，今日不再自动重试");
            }
            return blocked;
        } catch (Throwable t) { noteSwallowed("bumpFailsToday", t); return false; }
    }

    /** 该目标今天是否已被熔断（失败次数达上限）。跨天自动恢复。 */
    private boolean isDailyBlocked(String prefix, String id) {
        try {
            if (!todayStr().equals(prefs.getString(kFailsDay(prefix, id), ""))) return false;
            return prefs.getInt(kFailsToday(prefix, id), 0) >= SignLogic.FAILS_PER_DAY_LIMIT;
        } catch (Throwable t) { return false; }
    }

    /** 该目标今天是否命中过确定性失败词（请先关注 等），命中一次即当天不再重试。 */
    private boolean isPermanentFailedToday(String prefix, String id) {
        try {
            if (!todayStr().equals(prefs.getString(kFailsDay(prefix, id), ""))) return false;
            return prefs.getBoolean(kPermFail(prefix, id), false);
        } catch (Throwable t) { return false; }
    }

    private boolean isFrozen(String prefix, String id) {
        try {
            if (!prefs.getBoolean(kFrozen(prefix, id), false)) return false;
            // 双保险：条目已被删除时，残留的 frozen_ 不该让"重新添加的同一 bot"继承冻结。
            // （清空配置走的是白名单删除，正常不会残留；但历史版本留下的脏键还在。）
            // 必须按 prefix 反解账号，不能读 currentAccount()：后台多账号遍历时
            // 当前 UI 账号与 prefix 对应账号不同，会查错账号 → 冻结误判为未冻结。
            if (findEntryById(id, accountOfPrefix(prefix)) == null) return false;
            return true;
        } catch (Throwable t) { return false; }
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
     * 优先级：bot 黑名单 > 排除规则 > 放行。
     * 返回 null 表示允许学习；返回非 null 是拒绝原因（用于日志）。
     */
    private String learnDenyReason(long did, String text, String context) {
        return learnDenyReason(did, text, null, context);
    }

    /**
     * 学习拒绝原因（带 data）。
     *
     * @param data 回调按钮的 data 原文；文本类目标传 null。
     *
     * 2026-09-28 改：**只保留用户自己定的准入判断**。
     *   · 排除的 bot —— 用户明确拉黑的整只 bot
     *   · 排除规则   —— 用户自己写的关键词 / 正则
     *
     * 曾经那套"猜这个按钮像不像签到"的启发式（data 前缀黑名单、文案黑名单、
     * 随机 hex、关键词过滤）已整体移除。理由见 SignLogic 顶部说明：
     * 用户点按钮这个动作本身就是意图，猜必然误伤，而且误伤时界面上毫无提示。
     */
    private String learnDenyReason(long did, String text, String data, String context) {
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

            // ── 导航按钮过滤（2026-09-30 恢复）──
            // 只挡「确定的导航语义」（主菜单/返回/关闭/翻页…），不恢复那套猜测式启发式。
            // 起因：点了签到按钮后，bot 回复里常同时挂着「← 主菜单」这类导航按钮，
            //   一并被学走 → 目标列表被噪音淹没（用户实测反馈）。
            // 设计：判据是字面语义；命中日志写明原因；用户可关；手动添加不受限。
            if (LEARN_SKIP_NAV) {
                // 同时比对「文案」与「data 串」—— 实测有 bot 不给中文文案、
                // 按钮直接显示 ub_back_menu 这类 data，只比文案会漏掉。
                String nav = SignLogic.navButtonHit(text, data);
                if (nav != null) return Lang.tf("像是导航按钮（{0}）", nav);
            }

            String hay = (context == null ? "" : context) + " \n " + (text == null ? "" : text);
            String hit = excludeHit(hay);
            if (hit != null) return Lang.tf("命中排除规则「{0}」", hit);
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

    /**
     * 按 data 反查该按钮的**真实文案**（从最近一次面板快照里找）。
     *
     * 用途：网络层学习时手上只有 data 解码串，直接拿它当目标名会显示成
     * 「ub_back_menu」这种机器串；UI 层 hook 能拿到文案，网络层拿不到，
     * 这里从 panelLive 补齐，让加进来的目标显示成用户看得懂的按钮文案。
     *
     * 注：这**只影响显示名**，不参与任何准入判断 —— 过滤那套已整体移除，
     * 用户点什么就学什么（见 learnDenyReason 与 SignLogic 顶部说明）。
     *
     * @return 找到的按钮文案；找不到返回 null（调用方应回退到 data 解码串）
     */
    private String buttonTextForData(long did, byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            PanelLive pl;
            synchronized (panelLive) { pl = panelLive.get(did); }
            if (pl == null) return null;
            synchronized (pl) {
                for (CbButton b : pl.buttons) {
                    if (b.data != null && java.util.Arrays.equals(b.data, data)) {
                        return b.text;
                    }
                }
            }
        } catch (Throwable t) { noteSwallowed("buttonTextForData", t); }
        return null;
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
    private java.util.List<Long> pendingConfirmDids() { return pendingConfirmDids(currentAccount()); }
    private java.util.List<Long> pendingConfirmDids(int account) {
        java.util.List<Long> out = new java.util.ArrayList<Long>();
        try {
            String raw = prefs.getString(kPendingConfirm(account), "");
            if (raw == null || raw.trim().length() == 0) return out;
            for (String line : raw.split("\\n")) {
                String t = line.trim(); if (t.length() == 0) continue;
                int sp = t.indexOf('|'); if (sp < 0) continue;
                try { out.add(Long.parseLong(t.substring(0, sp).trim())); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private java.util.List<String> pendingConfirmTexts() { return pendingConfirmTexts(currentAccount()); }
    private java.util.List<String> pendingConfirmTexts(int account) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            String raw = prefs.getString(kPendingConfirm(account), "");
            if (raw == null || raw.trim().length() == 0) return out;
            for (String line : raw.split("\\n")) {
                String t = line.trim(); if (t.length() == 0) continue;
                int sp = t.indexOf('|'); if (sp < 0) continue;
                out.add(t.substring(sp + 1));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private boolean pendingConfirmAdd(long did, String text) { return pendingConfirmAdd(currentAccount(), did, text); }
    private boolean pendingConfirmAdd(int account, long did, String text) {
        try {
            java.util.List<Long> dids = pendingConfirmDids(account);
            for (Long d : dids) if (d != null && d.longValue() == did) return false;
            StringBuilder sb = new StringBuilder();
            String raw = prefs.getString(kPendingConfirm(account), "");
            if (raw != null && raw.trim().length() > 0) sb.append(raw).append('\n');
            sb.append(did).append('|').append(text == null ? "" : text.replace("\n", " ").replace('|', ' '));
            prefs.edit().putString(kPendingConfirm(account), sb.toString()).apply();
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private boolean pendingConfirmRemove(long did) { return pendingConfirmRemove(currentAccount(), did); }
    private boolean pendingConfirmRemove(int account, long did) {
        try {
            java.util.List<Long> dids = pendingConfirmDids(account);
            java.util.List<String> texts = pendingConfirmTexts(account);
            StringBuilder sb = new StringBuilder(); boolean removed = false;
            for (int i = 0; i < dids.size(); i++) {
                if (dids.get(i) != null && dids.get(i).longValue() == did) { removed = true; continue; }
                if (sb.length() > 0) sb.append('\n');
                sb.append(dids.get(i)).append('|').append(i < texts.size() ? texts.get(i) : "");
            }
            prefs.edit().putString(kPendingConfirm(account), sb.toString()).apply();
            return removed;
        } catch (Throwable ignored) { return false; }
    }

    private boolean pendingConfirmAccept(long did, String text) { return pendingConfirmAccept(currentAccount(), did, text); }
    private boolean pendingConfirmAccept(int account, long did, String text) {
        try {
            pendingConfirmRemove(account, did);
            // learnTarget() 使用当前账号；这里 UI 操作必须先确认当前账号仍等于列表所属账号。
            if (account != currentAccount()) return false;
            learnTarget(did, text);
            return true;
        } catch (Throwable t) { return false; }
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
            byte[] _bd1 = isCallbackButton(button) ? buttonData(button) : null;
            String deny = !AUTO_LEARN ? "按钮学习已关闭" : learnDenyReason(did, t, _bd1 == null ? null : new String(_bd1, "UTF-8"), panelContext(did));
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
                byte[] _bd2 = isCallbackButton(proto) ? buttonData(proto) : null;
                String deny = !AUTO_LEARN ? "按钮学习已关闭" : learnDenyReason(u, t, _bd2 == null ? null : new String(_bd2, "UTF-8"), panelContext(u));
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
                byte[] _bd2 = isCallbackButton(proto) ? buttonData(proto) : null;
                String deny = !AUTO_LEARN ? "按钮学习已关闭" : learnDenyReason(u, t, _bd2 == null ? null : new String(_bd2, "UTF-8"), panelContext(u));
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

    /**
     * 触发源 2：网络活动（学习/已签标记/补签/命令拦截）。返回 true 表示已拦截（不发送）。
     * @deprecated 多账号下无法定位真实发起账号，请改用 {@link #onSendRequest(Object[], Object)}，
     *             hook 处传入 sendRequest 的 thisObject（ConnectionsManager 实例）。
     */
    public boolean onSendRequest(Object[] args) { return onSendRequest(args, null); }

    /**
     * @param connObj sendRequest 方法的 thisObject（ConnectionsManager 实例）。
     *                hook 安装处需要把 param.thisObject 传进来，本方法据此用
     *                {@link #accountOfConnection(Object)} 定位"这次请求到底是哪个账号的连接发出的"——
     *                全局 currentAccount() 只反映 UI 当前选中账号，多账号并发发送时会张冠李戴
     *                （典型场景：本模块自己的「签全部账号」短时间内给多个账号各发一批请求）。
     */
    public boolean onSendRequest(Object[] args, Object connObj) {
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
        // 启动后 30 秒的"未就绪"窗口：以前这里直接 return，且**一行日志都不写**。
        // 但 Nagram / 官方版的 UI 按钮 hook 挂得上、不触发（实测），网络层是唯一的学习入口 ——
        // 于是"冷启动 → 立刻点 bot 按钮"表现为彻底没反应，用户以为模块坏了。
        // 现在：① 用户主动武装的捕获立即放行；② 被跳过时记一条日志，不再静默。
        // v1.6.0 去掉了原来的 `inSendReq` 前置门：它是**实例级**布尔，任何一次 sendRequest
        // （包括与签到无关的普通消息、图片上传）在处理期间，会把其他所有请求的学习/捕获
        // 全部静默跳过 —— 用户连点多个 bot 按钮时只有第一个可能被学到。
        // 学习本身是幂等的（findCbEntry 去重），不需要全局串行。
        if (notReadyYet() && !captureArmed) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastReadySkipLogAt > 5000L) {
                lastReadySkipLogAt = nowMs;
                logd("[启动] 尚未就绪（还差 " + ((bootReadyAt - nowMs) / 1000L)
                     + " 秒），本次回调不处理；点按钮学习请等启动完成后再试");
            }
            return false;
        }
        inSendReq = true;   // 仅用于"模块自己发起的请求"期间的自我重入保护
        try {
            // 优先用发起这次请求的连接实例定位账号；拿不到（旧调用点没传 connObj）才退回全局值。
            int _ha = accountOfConnection(connObj);
            final int hookAccount = _ha >= 0 ? _ha : currentAccount();
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
                            if (cbEntry != null && isPendingFresh(accountPrefix(hookAccount), entryId(cbEntry))) {
                                // 只标"请求已发出"（opt_），**不写 kLast**。
                                // 这里只是"请求即将发出去"，服务器尚未响应；
                                // 以前调 markSignedFromCallback 直接写 kLast，导致日志里
                                // "标记今日已签" 出现在 "已发出请求" 之前 —— 界面先绿，
                                // 之后 MESSAGE_ID_INVALID 再撤销，来回翻。
                                String oid = entryId(cbEntry);
                                markOptimistic(accountPrefix(hookAccount), oid);
                                logd("[活动] 模块发起的回调：标记已发出（待结论） " + oid);
                            } else {
                                logd("[活动] 非模块发起的回调，不乐观标记 " + u);
                            }
                            timerHook("TG活动");
                            // 用户手动点过但按钮 hook 未捕获时，网络层兜底学习。
                            // 改革：官方版 TG 用 R8 混淆，UI 层 didPressedBotButton 匹配 0 个方法（hook 失效），
                            // 因此网络层这条兜底就是官方版唯一的回调学习入口。
                            // 判断与 UI 层保持一致：只尊重「排除的 bot」与「排除规则」，
                            // 这两者都是用户自己定的；不再对按钮性质做任何猜测（2026-09-28）。
                            if (findCbEntry(u, d) == null && d.length > 0) {
                                // 优先用面板快照里的**真实按钮文案**（用户可见）；
                                // 取不到才回退到 data 解码串。判据以文案为准，
                                // 避免「文案是签到、data 像随机串」的真按钮被误挡。
                                String realText = buttonTextForData(u, d);
                                String disp = (realText != null && realText.trim().length() > 0)
                                        ? realText : cbDataLabel(d);
                                int mid0 = 0;
                                try { Object m2 = getFieldValSafe(req, "msg_id"); if (m2 instanceof Number) mid0 = ((Number) m2).intValue(); } catch (Throwable ignored) {}
                                // 先算清拒绝原因，别再把它吞掉：旧版这句谎报「被排除规则或关键词过滤」，
                                // 实际最常见的原因是「按钮学习已关闭」（AUTO_LEARN 默认 false）。
                                // 注：按钮性质过滤已移除，现在只剩用户自定的两条 + 自身请求防护。
                                // ⓪ 模块自身发出的请求：**绝不学习**。
                                //     面板按钮 data 常与目标旧 data 不同（bot 每次推送都换），
                                //     findCbEntry 查不到就会被学成新目标 → 用户看到「目标自己冒出来」。
                                boolean _selfReq = consumeSelfCbRequest(u, d);
                                String deny = _selfReq ? "模块自身发出的请求（不学习）"
                                            : (!AUTO_LEARN ? "按钮学习已关闭（设置→学习行为→按钮学习）"
                                              : learnDenyReason(u, disp, new String(d, "UTF-8"), panelContext(u)));
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
                                        // 账号必须用 hookAccount（本次网络请求所属账号），
                                        // 不能用 currentAccount() —— 那是"此刻界面上选中的账号"，
                                        // 异步回调里读它会串号（实测日志出现前缀=账号2、内容=账号1）。
                                        jlog("【网络层学习·回调】acc=" + accountLabel(hookAccount) + " " + u + " -> [" + disp + "] data=" + Base64.getEncoder().encodeToString(d) + " msg_id=" + mid0);
                                    } catch (Throwable lt) {
                                        loge("[网络层学习·回调] 失败: " + lt);
                                    }
                                } else {
                                    logd("[回调] acc=" + accountLabel(hookAccount) + " uid=" + u + " data=" + Base64.getEncoder().encodeToString(d) + "（不学习：" + deny + "）");
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
                            if (targetContains(u, hookAccount)) {
                                // 同样：只有模块自己发起的文本才乐观标记，避免用户手动发送被误判为已签
                                Map<String, Object> tx = null;
                                List<Map<String,Object>> _txs = new ArrayList<Map<String,Object>>();
                                loadTargetsInto(accountPrefix(hookAccount), _txs);
                                for (Map<String,Object> _tm : _txs) {
                                    if (entryDid(_tm) == u && KIND_TEXT.equals(entryKind(_tm)) && entryText(_tm).equals(t)) { tx = _tm; break; }
                                }
                                if (tx != null && isPendingFresh(accountPrefix(hookAccount), entryId(tx))) {
                                    markSignedFromRequest(hookAccount, u, t);
                                } else {
                                    logd("[活动] 非模块发起的文本，不乐观标记 " + u);
                                }
                            } else {
                                learnFromNetwork(u, t, hookAccount);
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
    /** 兼容旧调用点（不带 controller）。 */
    public void onUpdateProcessed(Object update) { onUpdateProcessed(update, null); }

    /**
     * 从 MessagesController 实例反解账号索引。
     *
     * 为什么必须这么做：processUpdateArray 是**实例方法**，实例上就有 BaseController.currentAccount
     * （protected final int，apk-index 实测 Nagram 12.10.3 存在）。而 currentAccount() 读的是
     * UserConfig.selectedAccount —— **全局静态**，多账号下随时可能已经被切走。
     * 以前这里用全局值定位 prefs 前缀，会把 A 账号 bot 的回复判到 B 账号头上：
     * 那个账号的目标被写上 last_=今天（漏签且显示已签），或误判失败被撤销已签。
     *
     * @return 账号索引；拿不到返回 -1（调用方回退全局值）。
     */
    private int accountOfController(Object mc) {
        if (mc == null) return -1;
        // Try 1: old TG (<12.9) — MessagesController has currentAccount field (BaseController)
        try {
            Object v = getFieldVal(mc, "currentAccount");
            if (v instanceof Number) {
                int a = ((Number) v).intValue();
                if (a >= 0) return a;
            }
        } catch (Throwable t) { /* fall through to Try 2 */ }
        // Try 2: TG 12.9+ / NagramX — iterate getInstance(i) to match by identity
        try {
            int total = activatedAccounts();
            for (int i = 0; i < Math.min(total, 8); i++) {
                Object inst = getMessagesController(i);
                if (inst == mc) return i;
            }
        } catch (Throwable t) { noteSwallowed("accountOfController", t); }
        return -1;
    }

    /**
     * 与 accountOfController 同理，但用于 sendRequest hook 的 thisObject（ConnectionsManager 实例）。
     * ConnectionsManager 每账号一个单例（见 getInstance(int account)），字段名以实测 Nagram
     * 12.10.3 为准是 currentAccount；若目标客户端字段名不同，需要按实际反编译结果调整这里的字段名，
     * 或补一个 getCurrentAccount() 方法调用兜底。拿不到就返回 -1，调用方退回全局 currentAccount()。
     */
    private int accountOfConnection(Object cm) {
        if (cm == null) return -1;
        // Try 1: old TG — ConnectionsManager has currentAccount field
        try {
            Object v = getFieldVal(cm, "currentAccount");
            if (v instanceof Number) {
                int a = ((Number) v).intValue();
                if (a >= 0) return a;
            }
        } catch (Throwable t1) {
            try {
                Object v2 = invoke(cm, "getCurrentAccount", new Class<?>[0], new Object[0]);
                if (v2 instanceof Number) {
                    int a = ((Number) v2).intValue();
                    if (a >= 0) return a;
                }
            } catch (Throwable t2) { /* fall through */ }
        }
        // Try 2: TG 12.9+ / NagramX — iterate getInstance by identity
        try {
            int total = activatedAccounts();
            for (int i = 0; i < Math.min(total, 8); i++) {
                Object inst = getConnectionsManager(i);
                if (inst == cm) return i;
            }
        } catch (Throwable t) { noteSwallowed("accountOfConnection", t); }
        return -1;
    }

    public void onUpdateProcessed(Object update, Object controller) {
        // 启动 30 秒的"未就绪"窗口**不能丢事件**。
        //
        // 这里以前是 `if (notReadyYet()) return;` —— 但本方法同时承担两件事：
        //   ① 缓存 bot 面板（updatePanelLive）→ 它是"等面板"机制的**唯一触发源**
        //   ② 判定回复
        // 事件一丢，sendSign 的"等面板"就永远等不到 → 8 秒后只能走兜底 msg_id，
        // 而兜底的按钮往往已经过期 → 报 MESSAGE_ID_INVALID。
        // 用户看到的就是"机器人明明秒回，模块却一直走兜底"（实测社工 bot 复现）。
        //
        // 未就绪只该限制"模块主动发起的动作"，不该限制"被动接收的事件"：
        // 面板缓存与回复判定都是幂等的，早处理无害。
        // 账号在进入判定链之前就钉死，后续所有 prefs 前缀都用它
        final int ctrlAcc = accountOfController(controller);
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
            if (msg == null) continue;   // 曾经是 return：单条异常会吞掉整批 update（含同批的面板消息）
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
            //
            // 关键（1.6.1 修「自噬」）：**收藏夹（Saved Messages）是自己发给自己** ——
            // peerUid == fromUid == selfId，属于私聊分支，而旧条件 `fromUid != peerUid`
            // 在这种情形下不成立 → 模块自己发的「每日汇总」被当成 bot 回复重新处理，
            // 日志出现「跳过重复信号 8526734916|⚠️ TGAutoSign 今日 0/9 已签…」。
            // 所以"排除自己发的"必须在两种分支上都做。
            boolean isGroupPeer = peerUid < 0;
            long selfId = accountSelfId(ctrlAcc >= 0 ? ctrlAcc : currentAccount());
            if (selfId > 0 && fromUid == selfId) continue;   // 自己发的（含收藏夹汇总、群指令）一律不处理
            if (isGroupPeer) {
                // continue 而非 return：TG 一次投递可带多条 update（Updates 容器），
                // return 会把同批里排在后面的**目标 bot 的带按钮面板**一起丢掉 ——
                // 面板时有时无、要等下一批推送才出现，根子常在这里。
                // （自己发的已在上面统一过滤）
            } else {
                if (peerUid <= 0 || fromUid != peerUid) continue;
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
                    if (mId > 0) updatePanelLive(peerUid, mId, rm, body, ctrlAcc >= 0 ? ctrlAcc : currentAccount());
                }
            } catch (Throwable _e32) { noteSwallowed("onUpdateProcessed", _e32); }
            // ctrlAcc may be -1 on NagramX/TG12.9+; use currentAccount() fallback
            int _chkAcc = ctrlAcc >= 0 ? ctrlAcc : currentAccount();
            if (!targetContains(peerUid, _chkAcc)) return;
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
                    // 用 hook 实例锁定的账号；拿不到（旧调用点）才回退全局值
                    String prefix = ctrlAcc >= 0 ? accountPrefix(ctrlAcc) : accountPrefix();
                    // 目标列表必须**按这个 prefix 现场重载**，不能用 targetsSnapshot()。
                    // 反例（用户实测）：内存 targets 是按 lastAccount 装载的，而一条 update
                    // 可能来自另一个账号（全账号签到/多窗口），两者不同步时 prefix 指向账号2
                    // 而 targets 里是账号1 的目标 → entryDid 比对全不中 → 判定被静默跳过，
                    // 表现为「账号2 拉起账号1」「账号3 无功能」。
                    final java.util.List<Map<String, Object>> judgeTargets =
                            new ArrayList<Map<String, Object>>();
                    try { loadTargetsInto(prefix, judgeTargets); } catch (Throwable ignored) {}
                    // 「已签过/重复」必须先判且同样标记已签：
                    // 否则 last_ 不写 → 日历不绿 → 心跳每 90 秒再发一次（死循环）
                    // 判定逻辑已抽到 SignLogic（纯逻辑、有单测）：返回 {判定码, 命中词}
                    // 判定总开关：关掉就只记录回复、不判成败（用户自己确认目标）
                    if (!JUDGE_ENABLED) {
                        // 关掉判定是用户的显式选择，但**不能让它悄悄生效**：
                        // 否则用户会看到"bot 明明回签到成功、目标却没变绿"，无从下手。
                        //
                        // 2026-09-28：原来只写日志，界面上毫无体现 —— 用户只看到"等待"，
                        // 看不出是这个原因。现在落到归类 judge_off，界面显式标注。
                        logw("【回复判定】自动判定已关闭，本次回复只记录不判定: " + clip(replyText, 50)
                             + "（如需自动判成败，去 设置 → 判定机器人回复 → 打开「自动判定成功 / 失败」）");
                        for (Map<String, Object> m : judgeTargets) {
                            if (entryDid(m) != did) continue;
                            markResultCode(prefix, entryId(m), SignLogic.R_JUDGE_OFF);
                        }
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
                            for (Map<String, Object> m : judgeTargets) {
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
                                noteResult(ctrlAcc >= 0 ? ctrlAcc : currentAccount(), false);
                                return;
                            }
                            return;
                        }
                        // 没有明确失败词 → 有回复即算成功
                        int lz = 0;
                        for (Map<String, Object> m : judgeTargets) {
                            if (entryDid(m) != did) continue;
                            String lid = entryId(m);
                            prefs.edit().putInt(kRetry(prefix, lid), 0)
                                 .remove(kRetryAt(prefix, lid)).remove(kRetryDay(prefix, lid)).apply();
                            markSigned(prefix, lid);
                            lz++;
                        }
                        // 用 logw 而非 jlog：jlog 是 INFO 级，受落盘采样影响可能被丢弃，
                        // 导致「判成功了但日志里看不到」，排障时极易误判成没反应（实测踩过）。
                        logw("【回复判定】宽松模式：bot 有回复即算成功 → 计入已签（" + lz + " 条）: " + clip(replyText, 50));
                        noteResult(ctrlAcc >= 0 ? ctrlAcc : currentAccount(), true);
                        return;
                    }
                    // 自定义词：只有用户开了「使用我的自定义词」才叠加，否则纯用内置
                    String[] extraOk = JUDGE_USE_CUSTOM ? SignLogic.parseExtraWords(prefs.getString("jmb_ok_words", "")) : null;
                    String[] extraFail = JUDGE_USE_CUSTOM ? SignLogic.parseExtraWords(prefs.getString("jmb_fail_words", "")) : null;
                    String[] okMerged = mergeWords(SignLogic.OK_WORDS_DEFAULT, extraOk);
                    String[] failMerged = mergeWords(SignLogic.FAIL_WORDS_DEFAULT, extraFail);

                    // ── 进度提示优先（2026-09-30 修）──
                    // 实测日志：「【回复判定】… 命中「正在签到」→ 计入已签（用户自定义词）」
                    //   bot 先回「✅ 正在签到，请稍后…」，再回真正的结果。前者是**过程**不是结论。
                    // 原实现只在 V_UNKNOWN 分支里检查 looksLikeProgress —— 但只要用户自定义词
                    //   或内置词表命中了这句进度提示（「正在签到」含「签到」，用户很容易把它加进成功词），
                    //   就会在检查之前直接判成功，把中间态当成终态，真正的失败被永久吞掉。
                    // 现在把进度提示提到一切判定之前：进度语一律不产生结论，继续等后续回复。
                    if (SignLogic.looksLikeProgress(replyText)) {
                        logd("【回复判定】" + did + " 进度提示，不作为结论（继续等）: " + clip(replyText, 40));
                        return;
                    }

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
                        for (Map<String, Object> m : judgeTargets) {
                            if (entryDid(m) != did) continue;
                            String id = entryId(m);
                            prefs.edit().putInt(kRetry(prefix, id), 0)
                                 .remove(kRetryAt(prefix, id)).remove(kRetryDay(prefix, id)).apply();
                            markSigned(prefix, id);
                            clearSendAttempts(prefix, id);   // 有结论了，当日发送计数归零
                            marked++;
                        }
                        jlog("【回复判定】" + did + " 命中「" + hitWord + "」→ 计入已签（" + marked + " 条）"
                             + (extraHit(hitWord, extraOk) ? "（用户自定义词）" : ""));
                        noteResult(ctrlAcc >= 0 ? ctrlAcc : currentAccount(), true);
                        return;
                    }

                    if (verdict == SignLogic.V_EXHAUSTED) {
                        /* 今日已用尽（2026-10-01 新增）。
                           现场：某群 bot 对重复签到回「❌ 您本日的规则触发数量上限，请明日再试」。
                           这句不含签到词、三张词表都不命中 → 原本走 V_UNKNOWN →
                           不写 kLast、不涨 kRetry、不熔断 → 心跳每 45 秒重发，群里被刷 13 条。

                           处置原则：
                             · **不标已签** —— 用户可能真没签上（额度在别处用掉了），标绿是撒谎；
                             · **不计失败** —— 这不是失败，计进 fail_streak 会污染"连续 3 天失败"告警；
                             · **不再重发** —— 归入「今日了结」，清计数与发送计数，当天收工。
                           界面归类码用 R_SIGNED（今天不必再管），但日志与 Toast 说清是"已用尽"。 */
                        for (Map<String, Object> m : judgeTargets) {
                            if (entryDid(m) != did) continue;
                            String id = entryId(m);
                            // 清掉"待结论"痕迹：不再重发，也不再计入发送次数
                            prefs.edit()
                                 .remove(kLast(prefix, id))          // 不冒充已签
                                 .putInt(kRetry(prefix, id), RETRY_LIMIT)   // 今日放弃重试
                                 .putString(kRetryDay(prefix, id), todayStr())
                                 .remove(prefix + "sent_at_" + id)
                                 .remove(prefix + "opt_" + id)
                                 .commit();
                            clearSendAttempts(prefix, id);
                            markResultCode(prefix, id, SignLogic.R_EXHAUSTED);
                        }
                        jlog("【回复判定】" + did + " 命中「" + hitWord + "」→ 今日已用尽，不再重发"
                             + "（不计失败、不标已签；如需重签请手动「立即签到」）");
                        noteResult(ctrlAcc >= 0 ? ctrlAcc : currentAccount(), true);
                        return;
                    }

                    if (verdict == SignLogic.V_FAILED) {
                        // 撤销该 bot 最近 30 分钟内发过签到请求的条目（多指令时只动刚发的那条）
                        for (Map<String, Object> m : judgeTargets) {
                            if (entryDid(m) != did) continue;
                            String id = entryId(m);
                            long sentAt = prefs.getLong(prefix + "sent_at_" + id, 0);
                            if (System.currentTimeMillis() - sentAt > PENDING_TTL_MS) continue;   // 与 isPendingFresh 同一常量
                            int cur = prefs.getInt(kRetry(prefix, id), 0);
                            // 确定性失败（请先关注/活动已结束…）重试无意义；其它失败用当天独立计数兜底。
                            boolean permanent = SignLogic.isPermanentFail(hitWord);
                            if (permanent) {
                                prefs.edit().putBoolean(kPermFail(prefix, id), true).commit();
                            }
                            boolean blocked = bumpFailsToday(prefix, id, permanent);
                            clearSendAttempts(prefix, id);   // 已有结论（失败），交给重试机制
                            prefs.edit()
                                .remove(kLast(prefix, id))
                                .putInt(kRetry(prefix, id), blocked ? RETRY_LIMIT : cur + 1)
                                .putLong(kRetryAt(prefix, id), System.currentTimeMillis() + backoffDelay(Math.max(cur, 1)))
                                .putString(kRetryDay(prefix, id), todayStr())
                                .commit();
                            logw("【回复判定】" + did + " 命中「" + hitWord + "」→ 判定未成功"
                                 + (permanent ? "（确定性失败，今日不再重试）" : "")
                                 + "，撤销已签" + (blocked ? "并当日熔断" : "并安排重试") + " (id=" + id + ")");
                            noteResult(ctrlAcc >= 0 ? ctrlAcc : currentAccount(), false);
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
                    // 2026-09-28：改用 SignLogic.looksLikeSignResult —— 它会先排除进度提示词。
                    // 改前这里直接判 contains("签到")，于是「✅ 正在签到,请稍后...」被误报为
                    // "像是签到结果但没匹配上内置词"，刷警告 + 进诊断包 + 提示补词。
                    // 但它是进度提示，下一句才是结果，补词毫无意义（全日志刷了 8 次纯噪音）。
                    boolean progress = SignLogic.looksLikeProgress(replyText);
                    boolean looksLikeResult = SignLogic.looksLikeSignResult(replyText);
                    /* 记下「bot 确实回了内容」这一事实（2026-10-01 补）。
                       为什么必须在这里写：promoteSilentToPending 靠 answered_ 区分
                       「bot 表态了但判不出」与「bot 完全没理」——
                       前者 3 分钟就收工，后者才等 10 分钟。
                       此前只有"就地对答"路径写这个键，回复判定路径漏了，
                       于是所有判不出的回复都被当成"静默"，白等 10 分钟、多发 10 条。 */
                    for (Map<String, Object> m : judgeTargets) {
                        if (entryDid(m) != did) continue;
                        try {
                            prefs.edit().putString(Keys.answered(prefix, entryId(m)), todayStr()).commit();
                        } catch (Throwable ignored) {}
                    }
                    if (progress) {
                        logd("【回复判定】" + did + " 这是进度提示（继续等真正的结果）: " + clip(replyText, 40));
                    } else if (looksLikeResult) {
                        logw("【回复判定】" + did + " 这条回复像是签到结果，但没匹配上内置词: "
                             + clip(replyText, 60) + "（可在 设置 → 回复判定词 里补一条）");
                        noteUnknownReply(prefix, did, replyText);
                        for (Map<String, Object> m : judgeTargets) {
                            if (entryDid(m) != did) continue;
                            markResultCode(prefix, entryId(m), SignLogic.R_REPLIED_UNK);
                        }
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
            for (int i : accountSlots()) {
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
            cfg.put("cal_style", CAL_STYLE);
            cfg.put("keywords", LEARN_KEYWORDS == null ? "" : LEARN_KEYWORDS);
            cfg.put("exclude", LEARN_EXCLUDE == null ? "" : LEARN_EXCLUDE);
            cfg.put("skipNav", LEARN_SKIP_NAV);
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
            CAL_STYLE = cfg.optInt("cal_style", CAL_STYLE);
            TIMER_ENABLED = cfg.optBoolean("timer", TIMER_ENABLED);
            GAP_MIN = cfg.optInt("gap", GAP_MIN);
            MISS_BACK = cfg.optBoolean("missback", MISS_BACK);
            MISS_DEADLINE = cfg.optInt("missdead", MISS_DEADLINE);
            LEARN_KEYWORDS = cfg.optString("keywords", LEARN_KEYWORDS);
            LEARN_EXCLUDE = cfg.optString("exclude", LEARN_EXCLUDE);
            LEARN_SKIP_NAV = cfg.optBoolean("skipNav", LEARN_SKIP_NAV);
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
            // 必须**同时**写账号级键：cfgStr()/cfgBool()/cfgInt() 是"账号级优先"，
            // 一旦本机保存过设置（写了 accN_cfg_*），全局 jmb_* 就永远读不到了 ——
            // 以前这里只写全局键，于是"已应用其他客户端的配置"是句谎话（实际没生效）。
            prefs.edit()
                .putString(kWindow(), WINDOW)
                .putString(accountPrefix() + "cfg_window", WINDOW)
                .putInt("jmb_theme", THEME_MODE)
                .putBoolean(kTimerEnabled(), TIMER_ENABLED)
                .putBoolean(accountPrefix() + "cfg_timer", TIMER_ENABLED)
                .putInt(kGap(), GAP_MIN)
                .putInt(accountPrefix() + "cfg_gap", GAP_MIN)
                .putBoolean(kMissBack(), MISS_BACK)
                .putBoolean(accountPrefix() + "cfg_missback", MISS_BACK)
                .putInt("jmb_missdead", MISS_DEADLINE)
                .putInt(accountPrefix() + "cfg_missdead", MISS_DEADLINE)
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

    private Object getConnectionsManager(int account) {
        try {
            return staticInvoke(classEx("org.telegram.tgnet.ConnectionsManager"), "getInstance", new Class<?>[]{int.class}, new Object[]{account});
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

    /** 设置 · 外观分区（从 showSettings 抽出，见 SettingsRefs 说明）。 */
    private void buildSectionAppearance(final SettingsRefs R) {
        final Activity act = R.act;
        final LinearLayout box = R.section;
        // ── 外观 ──
        LinearLayout card0 = new LinearLayout(act); card0.setOrientation(LinearLayout.VERTICAL);
        card0.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termPink(act), 0x26)));
        card0.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams c0lp = new LinearLayout.LayoutParams(-1, -2);
        c0lp.setMargins(0, dp(2), 0, dp(6));
        card0.setLayoutParams(c0lp);
        R.themeMode = THEME_MODE;
        final Button tbSw = mkBtn(act); tbSw.setTextSize(Theme.TS_BODY);
        final Runnable refreshT = new Runnable() { @Override public void run() {
            tbSw.setText(Lang.tr(R.themeMode == 0 ? "自动（跟宿主主题）" : (R.themeMode == 1 ? "始终日间（浅色）" : "始终夜间（终端风）")));
        } };
        tbSw.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            R.themeMode = (R.themeMode + 1) % 3;
            refreshT.run();
            Theme.mode = R.themeMode;
            toast(Lang.tf("主题：{0}（保存后生效）", Lang.tr(R.themeMode == 0 ? "自动" : (R.themeMode == 1 ? "日间" : "夜间"))));
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

        // ── 日历样式：带实时预览的循环切换 ──
        // 六种格子的画法差异较大，光看名字选不出来，所以每切一次就重画一行样例格。
        R.calStyle = CAL_STYLE;
        LinearLayout calRow = new LinearLayout(act);
        calRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(-1, -2);
        crlp.topMargin = dp(12);
        calRow.setLayoutParams(crlp);

        final TextView calLab = new TextView(act);
        calLab.setTextSize(Theme.TS_BODY);
        calLab.setTypeface(Theme.text());
        calLab.setTextColor(Theme.termTxt(act));
        calRow.addView(calLab);

        // 预览：固定 8 格（6 已签 + 1 未签 + 今天），够看出风格
        final LinearLayout prev = new LinearLayout(act);
        prev.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(6);
        prev.setLayoutParams(plp);
        calRow.addView(prev);

        final TextView calTip = new TextView(act);
        calTip.setTextSize(Theme.TS_CAPTION);
        calTip.setTextColor(Theme.termFaint(act));
        calTip.setTypeface(Theme.text());
        calTip.setPadding(dp(4), dp(5), dp(4), 0);
        calRow.addView(calTip);

        final Button calBtn = mkBtn(act);
        calBtn.setTextSize(Theme.TS_BODY);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(6);
        calBtn.setLayoutParams(blp);
        calRow.addView(calBtn);

        final Runnable refreshCal = new Runnable() { @Override public void run() {
            int st = R.calStyle;
            final String[] NAMES = {
                    "方角 · 斜纹底（默认）", "圆角 · 发光边框", "硬方波 · 顶部条",
                    "切角块 · 八边形", "圆点 · 外环", "竖柱条 · 高度表达"
            };
            final String[] DESC = {
                    "方格子加深色斜纹，边界清楚、最不抢眼。适合每天看。",
                    "圆角块 + 外发光，视觉最亮。格子多时会稍显花。",
                    "直角实心块加一条顶线，像示波器读数，最硬朗。",
                    "四角切掉的八边形，科技感强，占用空间略大。",
                    "圆点套一圈外环，最简洁；但不太像传统的日历。",
                    "每个格子是一根柱子，柱高表达状态，数据感最强。"
            };
            calBtn.setText(Lang.tr("日历样式：") + Lang.tr(NAMES[st % NAMES.length]));
            calTip.setText(Lang.tr(DESC[st % DESC.length]));
            prev.removeAllViews();
            boolean dark = Theme.dark(act);
            int green = Theme.termGreen(act), cyan = Theme.termCyan(act),
                muted = Theme.termMuted(act), pink = Theme.termPink(act);
            // 预览 8 格，覆盖全部会出现的组合，让用户选样式时就看到今天格的语义：
            //   [0..4] 已签 · [5] 未签 · [6] 未签(周末) · [7] 今天（待签 = 青）
            // 今天格用"待签"色（青）演示 —— 这是最常见、也最需要看清的状态。
            for (int i = 0; i < 8; i++) {
                boolean signed = (i <= 4);
                boolean today = (i == 7);
                TextView cell = new TextView(act);
                cell.setTextSize(Theme.TS_CAPTION);
                cell.setTypeface(Theme.text());
                if (Icons.cellTextTop(R.calStyle)) {
                    cell.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(0, dp(3), 0, 0);
                } else {
                    cell.setGravity(android.view.Gravity.CENTER);
                }
                cell.setText(today ? Lang.tr("今") : String.valueOf(17 + i));
                int ac = today ? cyan : green;
                int tc;
                if (today)       tc = cyan;                 // 演示"今天待签"
                else if (signed) tc = dark ? 0xFFE8FFF6 : blendText(dark, green);
                else             tc = muted;
                cell.setTextColor(tc);
                cell.setBackground(new Icons.DayCellDrawable(
                        dp(28), signed, today, dark, ac, muted, R.calStyle));
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(0, dp(28), 1f);
                lp.setMargins(dp(2), 0, dp(2), 0);
                prev.addView(cell, lp);
            }
        } };
        calBtn.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            R.calStyle = (R.calStyle + 1) % 6;
            refreshCal.run();
        } });
        refreshCal.run();
        card0.addView(calRow);

        box.addView(card0);

    }


    /** 设置 · 通知分区（从 showSettings 抽出）。 */
    private void buildSectionNotify(final SettingsRefs R) {
        final Activity act = R.act;
        final LinearLayout box = R.section;
        LinearLayout cardN = new LinearLayout(act); cardN.setOrientation(LinearLayout.VERTICAL);
        cardN.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termGreen(act), 0x26)));
        cardN.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cnlp = new LinearLayout.LayoutParams(-1, -2);
        cnlp.setMargins(0, dp(2), 0, dp(6));
        cardN.setLayoutParams(cnlp);
        R.notifySw = swRow(act, "签到结果通知", NOTIFY_ON);
        R.notifySw.setTextSize(Theme.TS_BODY);
        cardN.addView(R.notifySw);
        R.notifyFailSw = swRow(act, "只通知失败", NOTIFY_FAIL_ONLY);
        R.notifyFailSw.setTextSize(Theme.TS_BODY);
        cardN.addView(R.notifyFailSw);
        TextView nTip = new TextView(act); nTip.setTextSize(Theme.TS_CAPTION); nTip.setTextColor(Theme.termFaint(act));
        nTip.setTypeface(Theme.text());
        nTip.setText(Lang.tr("每账号每天一条摘要，发到自己的「收藏夹」（不弹系统通知）。目标连续 3 天失败会额外提醒。"));
        nTip.setPadding(dp(4), dp(4), dp(4), 0);
        cardN.addView(nTip);
        box.addView(cardN);

    }


    /** 设置 · 签到核心分区（从 showSettings 抽出：定时/窗口/间隔/补签）。 */
    private void buildSectionSignCore(final SettingsRefs R) {
        final Activity act = R.act;
        final LinearLayout box = R.section;
        LinearLayout card1 = new LinearLayout(act); card1.setOrientation(LinearLayout.VERTICAL);
        card1.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x26)));
        card1.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams c1lp = new LinearLayout.LayoutParams(-1, -2);
        c1lp.setMargins(0, dp(2), 0, dp(6));
        card1.setLayoutParams(c1lp);
        R.timerSw = swRow(act, "定时签到", TIMER_ENABLED);
        R.timerSw.setTextSize(Theme.TS_BODY);
        card1.addView(R.timerSw);
        TextView tmSub = new TextView(act); tmSub.setTextSize(Theme.TS_CAPTION); tmSub.setTextColor(Theme.termFaint(act)); tmSub.setTypeface(Theme.text());
        tmSub.setText(Lang.tr("开 = 窗口内按随机时刻逐个签（防风控，推荐）；关 = 检测到未签就马上签（限窗口内）"));
        tmSub.setPadding(dp(4), 0, dp(4), dp(6));
        card1.addView(tmSub);
        box.addView(card1);
        // 时间选择：两个按钮弹系统时间选择器，免手输
        final int[] wRange = windowRange();
        final int[] wStart = { wRange != null ? wRange[0] : 8 * 60 + 30 };
        R.wStart = wStart;   // 保存块也要读（跨作用域）
        final int[] wEnd   = { wRange != null ? wRange[1] : 20 * 60 + 30 };
        R.wEnd = wEnd;
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
        R.gapEd = new EditText(act);
        R.gapEd.setText(String.valueOf(GAP_MIN));
        R.gapEd.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        R.gapEd.setGravity(android.view.Gravity.CENTER);
        R.gapEd.setTextSize(Theme.TS_BODY);
        R.gapEd.setSingleLine(true);
        R.gapEd.setTextColor(Theme.termTxt(act));
        R.gapEd.setBackground(termBorder(act, Theme.termCardInput(act), Theme.withAlpha(Theme.termCyan(act), 0x33)));
        Button gapPlus = mkBtn(act); gapPlus.setText("+");
        gapMinus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(R.gapEd.getText().toString().trim()); vv=Math.max(0,vv-5); R.gapEd.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
        gapPlus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(R.gapEd.getText().toString().trim()); vv=Math.min(240,vv+5); R.gapEd.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
        gapRow.addView(gapMinus, new LinearLayout.LayoutParams(0, -2, 1f));
        gapRow.addView(R.gapEd, new LinearLayout.LayoutParams(0, -2, 1.6f));
        gapRow.addView(gapPlus, new LinearLayout.LayoutParams(0, -2, 1f));
        card1.addView(gapRow);
        TextView gapTip = new TextView(act); gapTip.setTextSize(Theme.TS_CAPTION); gapTip.setTextColor(Theme.termFaint(act)); gapTip.setTypeface(Theme.text());
        gapTip.setText(Lang.tr("0=按目标数自动均分；如设 30，则相邻目标至少隔 30 分钟"));
        gapTip.setPadding(dp(4), 0, dp(4), dp(2));
        card1.addView(gapTip);
        R.missBackSw = swRow(act, "错过补签", MISS_BACK);
        R.missBackSw.setTextSize(Theme.TS_BODY);
        card1.addView(R.missBackSw);
        TextView mbTip = new TextView(act); mbTip.setTextSize(Theme.TS_CAPTION); mbTip.setTextColor(Theme.termFaint(act)); mbTip.setTypeface(Theme.text());
        mbTip.setText(Lang.tr("关掉 TG 期间错过的签到点：开=错过后随机延迟 1~5 分钟自动补；关=错过即跳过（默认）"));
        mbTip.setPadding(dp(4), 0, dp(4), dp(2));
        card1.addView(mbTip);
        // 补签截止：窗口结束后仍可补到该时间（与签到窗口解耦，避免当天错过作废）
        R.missDeadlineMin = MISS_DEADLINE;
        LinearLayout mdRow = new LinearLayout(act); mdRow.setOrientation(LinearLayout.HORIZONTAL); mdRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mdRow.setPadding(dp(4), dp(4), dp(4), dp(6));
        TextView mdLab = new TextView(act); mdLab.setText(Lang.tr("补签截止")); leadIcon(act, mdLab, "hourglass", Theme.termMuted(act)); mdLab.setTextSize(Theme.TS_SECOND); mdLab.setTextColor(Theme.termMuted(act));
        mdLab.setTypeface(android.graphics.Typeface.MONOSPACE);
        mdRow.addView(mdLab, new LinearLayout.LayoutParams(0, -2, 1f));
        final Button mdb = mkBtn(act); mdb.setTextSize(Theme.TS_BODY);
        final Runnable refreshMd = new Runnable() { @Override public void run() {
            mdb.setText(String.format("%02d:%02d", R.missDeadlineMin / 60, R.missDeadlineMin % 60));
        } };
        mdb.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            new android.app.TimePickerDialog(act, new android.app.TimePickerDialog.OnTimeSetListener(){
                @Override public void onTimeSet(android.widget.TimePicker tp, int h, int m) { R.missDeadlineMin = h * 60 + m; refreshMd.run(); }
            }, R.missDeadlineMin / 60, R.missDeadlineMin % 60, true).show();
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
                        // 优先「实际签上时刻」(signed_at_)，回退到「发起时刻」(sent_at_)、
                        // 再回退到 ✔。以前只读 sent_at_ —— 那是请求发出的时刻，
                        // bot 隔几分钟才回复时会显示成"签得比实际早"。
                        long at = stateStore.signedAtMs(fPrefix, id);
                        String hhmm = SignLogic.hhmmOf(at);
                        if (hhmm.length() > 0) {
                            time.setText(hhmm);
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
                foot.setText(Lang.tf("已签 {0} / {1} · 已签=实际签上时刻 / 待签=计划时刻", doneN, all.size()));
                foot.setPadding(dp(4), dp(6), dp(4), 0);
                pl.addView(foot);
                showDialog(act, "今日计划", pl, "关闭");
            } catch (Throwable t) { toast(Lang.tf("预览失败: {0}", t)); }
        } });
        card1.addView(planBtn);

    }


    /** 设置 · 学习行为分区（从 showSettings 抽出）。 */
    private void buildSectionLearn(final SettingsRefs R) {
        final Activity act = R.act;
        final LinearLayout box = R.section;
        LinearLayout card2 = new LinearLayout(act); card2.setOrientation(LinearLayout.VERTICAL);
        card2.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x26)));
        card2.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams c2lp = new LinearLayout.LayoutParams(-1, -2);
        c2lp.setMargins(0, dp(2), 0, dp(6));
        card2.setLayoutParams(c2lp);
        R.autoLearnSw = swRow(act, "按钮学习", AUTO_LEARN);
        card2.addView(R.autoLearnSw);
        TextView alSub = new TextView(act); alSub.setTextSize(Theme.TS_CAPTION); alSub.setTextColor(Theme.termFaint(act)); alSub.setTypeface(Theme.text());
        alSub.setText(Lang.tr("点一下 bot 按钮就自动加到列表"));
        alSub.setPadding(dp(4), 0, dp(4), dp(4));
        card2.addView(alSub);
        R.autoLearnNetSw = swRow(act, "网络学习", AUTO_LEARN_NET);
        card2.addView(R.autoLearnNetSw);
        TextView anSub = new TextView(act); anSub.setTextSize(Theme.TS_CAPTION); anSub.setTextColor(Theme.termFaint(act)); anSub.setTypeface(Theme.text());
        anSub.setText(Lang.tr("自动识别你在 bot 里发的签到文本"));
        anSub.setPadding(dp(4), 0, dp(4), dp(4));
        card2.addView(anSub);
        R.autoLearnNetCfmSw = swRow(act, "网络学习需确认", AUTO_LEARN_NET_CONFIRM);
        card2.addView(R.autoLearnNetCfmSw);
        TextView anCfgSub = new TextView(act); anCfgSub.setTextSize(Theme.TS_CAPTION); anCfgSub.setTextColor(Theme.termFaint(act)); anCfgSub.setTypeface(Theme.text());
        anCfgSub.setText(Lang.tr("命中后先进「待确认」列表，你手动确认才加入（防验证码类 bot 误加）"));
        anCfgSub.setPadding(dp(4), 0, dp(4), dp(4));
        card2.addView(anCfgSub);

        // ── 忽略导航按钮（2026-09-30 新增）──
        // 点签到按钮时，bot 回复里常同时挂着「← 主菜单」「关闭」这类导航按钮，
        // 一并被学走会让目标列表迅速被噪音淹没（用户实测反馈）。
        // 只挡「确定是导航」的文案，不恢复那套会误伤签到按钮的猜测式过滤；
        // 关掉本开关即回到「点什么学什么」。
        R.skipNavSw = swRow(act, "忽略导航按钮", LEARN_SKIP_NAV);
        card2.addView(R.skipNavSw);
        TextView navSub = new TextView(act); navSub.setTextSize(Theme.TS_CAPTION); navSub.setTextColor(Theme.termFaint(act)); navSub.setTypeface(Theme.text());
        navSub.setText(Lang.tr("「主菜单 / 返回 / 关闭」这类导航按钮不学。只按文案判断，不会误挡签到按钮；关掉即「点什么学什么」"));
        navSub.setPadding(dp(4), 0, dp(4), dp(4));
        card2.addView(navSub);

        box.addView(card2);

    }


    /** 设置 · 关键词与策略分区（从 showSettings 抽出）。 */
    private void buildSectionKeywords(final SettingsRefs R) {
        final Activity act = R.act;
        final LinearLayout box = R.section;
        LinearLayout card3 = new LinearLayout(act); card3.setOrientation(LinearLayout.VERTICAL);
        card3.setBackground(termBorder(act, Theme.termCard(act), Theme.withAlpha(Theme.termCyan(act), 0x26)));
        card3.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams c3lp = new LinearLayout.LayoutParams(-1, -2);
        c3lp.setMargins(0, dp(2), 0, dp(6));
        card3.setLayoutParams(c3lp);
        TextView kwLab = new TextView(act); kwLab.setText(Lang.tr("学习关键词（逗号分隔，用于识别签到文本）")); leadIcon(act, kwLab, "key", Theme.termMuted(act)); kwLab.setTextSize(Theme.TS_SECOND); kwLab.setTextColor(Theme.termMuted(act));
        kwLab.setTypeface(android.graphics.Typeface.MONOSPACE); kwLab.setPadding(dp(2), dp(2), dp(2), dp(4));
        card3.addView(kwLab);
        R.keywordsEd = adInput(act, "如: 签到,打卡,checkin", 0);
        R.keywordsEd.setText(LEARN_KEYWORDS == null ? "" : String.valueOf(LEARN_KEYWORDS));
        card3.addView(R.keywordsEd);

        // ── 排除规则（黑名单）：一行一条，支持正则 ──
        TextView exLab = new TextView(act);
        exLab.setText(Lang.tr("排除规则（一行一条，命中不学习）"));
        leadIcon(act, exLab, "trash", Theme.termPink(act));
        exLab.setTextSize(Theme.TS_SECOND); exLab.setTextColor(Theme.termMuted(act));
        exLab.setTypeface(android.graphics.Typeface.MONOSPACE);
        exLab.setPadding(dp(2), dp(10), dp(2), dp(4));
        card3.addView(exLab);
        R.excludeEd = adInput(act, "如: 点击图中事物（一行一条）", 3);
        R.excludeEd.setText(LEARN_EXCLUDE == null ? "" : String.valueOf(LEARN_EXCLUDE));
        R.excludeEd.setMinLines(2);
        card3.addView(R.excludeEd);
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
        R.looseSw = swRow(act, "宽松模式（来者不拒）", LOOSE_MODE);
        card3.addView(R.looseSw);
        TextView loTip = new TextView(act); loTip.setTextSize(Theme.TS_CAPTION);
        loTip.setTextColor(Theme.termFaint(act)); loTip.setTypeface(Theme.text());
        loTip.setText(Lang.tr("开：点什么学什么；判定时**只要机器人有回复就算成功**，\n但命中明确失败词（活动已结束 / 请先关注 / 未绑定 等）仍判失败。\n适合判定词千奇百怪的机器人。关：完全按下面的规则判定。"));
        loTip.setPadding(dp(4), dp(2), dp(4), dp(6));
        card3.addView(loTip);

        // 开关式：默认用内置词表判定成功/失败，不再要求用户"自己加词"。
        R.judgeSw = swRow(act, "自动判定成功 / 失败", JUDGE_ENABLED);
        card3.addView(R.judgeSw);
        TextView rvTip = new TextView(act); rvTip.setTextSize(Theme.TS_CAPTION);
        rvTip.setTextColor(Theme.termFaint(act)); rvTip.setTypeface(Theme.text());
        rvTip.setText(Lang.tr("开着：按内置词表判断机器人回复是成功还是失败。\n关掉：只记录回复、不判成败（目标需要你自己确认）。"));
        rvTip.setPadding(dp(4), dp(4), dp(4), dp(2));
        card3.addView(rvTip);

        // 自定义补充词（可选）：只在需要时填，且要求开关开着
        R.judgeCustomSw = swRow(act, "使用我的自定义词（叠加在内置之上）", JUDGE_USE_CUSTOM);
        card3.addView(R.judgeCustomSw);
        R.okWordsEd = adInput(act, "如: 领取完毕,今日完成", 0);
        R.okWordsEd.setText(strOf("jmb_ok_words"));
        R.okWordsEd.setVisibility(JUDGE_USE_CUSTOM ? android.view.View.VISIBLE : android.view.View.GONE);
        card3.addView(R.okWordsEd);
        R.failWordsEd = adInput(act, "如: 次数已用完,不可领取", 0);
        R.failWordsEd.setText(strOf("jmb_fail_words"));
        R.failWordsEd.setVisibility(JUDGE_USE_CUSTOM ? android.view.View.VISIBLE : android.view.View.GONE);
        card3.addView(R.failWordsEd);
        R.judgeCustomSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                R.okWordsEd.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
                R.failWordsEd.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
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
        R.retryLimitEd = new EditText(act); R.retryLimitEd.setText(String.valueOf(RETRY_LIMIT)); R.retryLimitEd.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); R.retryLimitEd.setGravity(android.view.Gravity.CENTER); R.retryLimitEd.setTextSize(Theme.TS_BODY);
        Button rlPlus = mkBtn(act); rlPlus.setText("+");
        rlMinus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(R.retryLimitEd.getText().toString().trim()); vv=Math.max(1,vv-1); R.retryLimitEd.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
        rlPlus.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try { int vv=Integer.parseInt(R.retryLimitEd.getText().toString().trim()); vv=Math.min(99,vv+1); R.retryLimitEd.setText(String.valueOf(vv)); } catch (Throwable ignored) {} } });
        rlRow.addView(rlMinus, new LinearLayout.LayoutParams(0, -2, 1f));
        rlRow.addView(R.retryLimitEd, new LinearLayout.LayoutParams(0, -2, 2.2f));
        rlRow.addView(rlPlus, new LinearLayout.LayoutParams(0, -2, 1f));
        card3.addView(rlRow);
        TextView wcLab = new TextView(act); wcLab.setText(Lang.tr("全局默认唤醒命令")); leadIcon(act, wcLab, "keyboard", Theme.termMuted(act)); wcLab.setTextSize(Theme.TS_SECOND); wcLab.setTextColor(Theme.termMuted(act));
        wcLab.setTypeface(android.graphics.Typeface.MONOSPACE); wcLab.setPadding(dp(2), dp(6), dp(2), dp(4));
        card3.addView(wcLab);
        R.wakeCmdEd = adInput(act, "如 /start；条目自带前置命令优先", 0);
        R.wakeCmdEd.setText(WAKE_CMD == null ? "" : WAKE_CMD);
        card3.addView(R.wakeCmdEd);
        box.addView(card3);

    }

}
