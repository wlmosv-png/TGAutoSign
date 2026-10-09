package io.github.wlmosv_png.tgautosign;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

final class Theme {
    private Theme() {}
    /**
     * 字体缩放系数（2026-10-04）。
     *
     * 为什么需要：用户在系统里调大字体后，sp 会变大，
     * 而模块里大量的**定高**容器（日历格 30dp、热力图标签列 16dp）
     * 不会跟着变 → 文字被裁或被挤成两行。
     * 这是最典型的“换个手机就不对”的 UI 错误。
     */
    static float fontScale(Context c) {
        try {
            float f = c.getResources().getConfiguration().fontScale;
            if (f >= 0.5f && f <= 3f) return f;
        } catch (Throwable ignored) {}
        return 1f;
    }

    /**
     * “能装下 v 行文字”的容器高度（px）。
     * 基础值 baseDp 不变，但会按字体缩放系数拉高 ——
     * 保证“字变大了，格子也跟着变大”，而不是把字裁掉。
     */
    static int textBoxPx(Context c, float baseDp) {
        return (int) (dp(c, baseDp) * Math.max(1f, fontScale(c)) + 0.5f);
    }

    static int dp(Context c, float v) {
        return Math.max(1, (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()) + 0.5f));
    }
    private static boolean themeLogDone;
    private static long darkCacheAt = 0L;
    /** 上次「屏幕采样」的时间；采样极贵，做最小间隔节流（2026-10-07）。 */
    private static long lastSampleAt = 0L;
    private static final long SAMPLE_MIN_GAP_MS = 3_000L;

    /** 暗色缓存时长（2026-10-07：600ms → 10s，见 dark() 内注释）。 */
    private static final long CACHE_MS = 60_000L;   // 2026-10-07：10s → 60s（采样太贵）

    /** 冻结状态：页面批量构建期间钉住主题值，避免触发昂贵的屏幕采样。 */
    private static volatile boolean frozen = false;
    private static volatile boolean frozenVal = false;

    /**
     * 冻结主题判定（2026-10-07 卡顿修复）。
     *
     * 用途：在**批量构建 View 树**之前调用一次，把当前深浅色值钉死；
     *   构建期间所有 Theme.xxx(c) 都直接返回该值，不会走到 colorProbe
     *   （colorProbe 会把整棵视图树重绘到 1×1 位图，实测一次约 1.3 秒）。
     * 用法：freeze() → 构建 → unfreeze()。必须在 finally 里 unfreeze。
     */
    static void freeze(Context c) {
        try {
            // ── 2026-10-07 关键修复 ──
            // 旧实现是 `frozenVal = dark(c);` —— 而 dark() 在缓存过期时会走
            //   colorProbe → sampleScreenColor → target.draw(canvas)
            // 也就是**把整棵视图树重绘到 1×1 位图**。
            // 结果：本意是"冻结以免采样"，实际上 freeze 自己就触发了那次采样，
            // 打开学习页仍然是 1 秒级卡顿（用户反复反馈"一瞬就卡"）。
            //
            // 现在：冻结时**只用已有缓存/已知值，绝不主动采样**。
            //   有缓存 → 用缓存；缓存过期也照用（主题不会在打开一个面板的瞬间变）；
            //   完全没有过值 → 才退一次 dark()（这是进程内第一次，无从避免）。
            long now = System.currentTimeMillis();
            boolean hasKey = false;
            try { hasKey = cacheKey(c).equals(darkCacheKey); } catch (Throwable ignored) {}
            if (darkCacheAt > 0L && (hasKey || now - darkCacheAt < 60_000L)) {
                frozenVal = darkCacheVal;          // 用已知值，零成本
            } else {
                frozenVal = dark(c);               // 进程内首次：无从避免
            }
            frozen = true;
        } catch (Throwable ignored) {}
    }

    static void unfreeze() {
        frozen = false;
    }
    private static boolean darkCacheVal = false;

    /** 主题模式：0=自动（跟宿主主题，取不到再看系统）1=强制日间 2=强制夜间 */
    static volatile int mode = 0;

    /** 诊断：最近一次判定的来源与取到的颜色（Core 会写进运行日志） */
    static String lastHow = "-";
    static int lastColor = 0;

    /**
     * 主题深浅判定。
     *   mode=1/2 时直接返回用户指定值（手动兜底，永远可控）。
     *   自动：① 采样当前画面真实颜色（最可靠，宿主/混淆无关）
     *        ② 内容区/窗口背景 drawable
     *        ③ windowBackground / colorBackground 主题属性
     *        ④ Theme.isCurrentThemeDark（未混淆宿主）
     *        ⑤ 混淆主题对象（只认已知名或唯一候选）
     *        ⑥ 系统 uiMode
     * 600ms 缓存，避免绘制期反复探测。
     */
    /**
     * 主题深浅判定。
     *
     * 顺序很重要（v1.5.8 调整）：
     *   ① 宿主 API `Theme.isCurrentThemeDark` —— 宿主自己维护的真值，最可靠
     *   ② 主题属性 windowBackground / colorBackground —— 稳定，不受动画影响
     *   ③ 画面采样 —— **最后手段**，且要求连续两次一致才采信
     *   ④ 系统 uiMode —— 兜底
     *
     * 为什么把采样从第一位降到第三位：
     *   采样的是"整屏平均色"。子面板刚弹出（半透明遮罩 / 动画中）、列表滚动、
     *   软键盘弹出时，采到的都是**瞬时帧**而不是主题色，会判定成相反的明暗 ——
     *   用户看到的就是"主界面深色，子面板有时变亮"。采样本身不适合判断主题。
     *
     * 缓存按 Activity 分别记，避免主界面与子面板互相污染。
     */
    static boolean dark(Context c) {
        if (mode == 1) { lastHow = "force-light"; lastColor = 0; return false; }
        if (mode == 2) { lastHow = "force-dark"; lastColor = 0; return true; }

        long now = System.currentTimeMillis();
        String key = cacheKey(c);
        // 2026-10-07 卡顿修复：600ms → 10s。
        //   600ms 太短 —— 构建一个页面的 View 树时（调 Theme.xxx 几十次）
        //   每 600ms 就会触发一次 colorProbe，而它内部要做
        //     target.draw(canvas)   // 整棵视图树重绘到 1×1 位图
        //   对话框打开时树很庞大，实测单次约 1.3 秒（用户报的卡顿）。
        //   主题在 10 秒内不会变，缓存拉长完全安全。
        if (now - darkCacheAt < CACHE_MS && key.equals(darkCacheKey)) return darkCacheVal;
        // 冻结期：直接返回钉住的值，绝不触发采样
        if (frozen) return frozenVal;

        boolean val;
        String how;
        lastColor = 0;

        // ① 画面采样 —— **主判据**（历史上一贯准确，v1.5.1 起就用它）。
        //    但加两道保险，避免子面板动画/遮罩期采到瞬时帧而判反：
        //      · 同一页面连续两次一致才采信；不一致时先沿用上一次的结论（不翻转）
        //      · 缓存按页面分开，主界面与子面板互不污染
        // 2026-10-07：采样是**最贵的操作**（整棵视图树重绘到 1×1 位图）。
        // 即便缓存过期，也强制两次采样之间至少间隔 SAMPLE_MIN_GAP_MS，
        // 期间直接用上次的值 —— 主题不会在几秒内改变。
        Boolean byColor;
        long sinceSample = now - lastSampleAt;
        if (lastSampleAt > 0L && sinceSample < SAMPLE_MIN_GAP_MS) {
            byColor = null;      // 节流：本次不采样，走下面的属性/API 回退（它们很便宜）
        } else {
            byColor = colorProbe(c);
            if (byColor != null) lastSampleAt = now;
        }
        if (byColor != null) {
            boolean b = byColor.booleanValue();
            if (!sampleSeen) {                 // 本进程第一次：直接采信
                sampleSeen = true; sampleLast = b;
                val = b; how = "ui-color";
            } else if (b == sampleLast) {      // 与上次一致：采信
                val = b; how = "ui-color";
            } else {                            // 翻转了：极可能是瞬时帧，沿用上次
                val = sampleLast; how = "ui-color(抖动,沿用上次)";
            }
        } else {
            // ② 采样取不到（绘制期 getWidth=0 等）才退回主题属性。
            //    注意只看 colorBackground：TG 的 windowBackground 在深色下常是白色
            //    （窗口底色，内容盖在上面），拿它判主题会把深色判成浅色。
            Boolean byAttr = attrProbe(c);
            if (byAttr != null) { val = byAttr.booleanValue(); how = "theme-attr"; }
            else {
                Boolean byApi = darkByThemeClass(c);
                if (byApi != null) { val = byApi.booleanValue(); how = "theme-api"; }
                else { val = sysDark(c); how = "sys-uiMode"; }
            }
        }

        darkCacheAt = now;
        darkCacheVal = val;
        darkCacheKey = key;
        lastHow = how;
        if (!themeLogDone || val != lastLoggedVal || !how.equals(lastLoggedHow)) {
            themeLogDone = true;
            lastLoggedVal = val;
            lastLoggedHow = how;
            try {
                android.util.Log.i("TGAutoSignModule", "theme dark=" + val + " 来源=" + how
                        + " color=#" + Integer.toHexString(lastColor)
                        + " 系统=" + (sysDark(c) ? "dark" : "light"));
            } catch (Throwable ignored) {}
        }
        return val;
    }

    /** 采样一致性状态（跨调用保持）。 */
    private static boolean sampleSeen = false;
    private static boolean sampleLast = false;
    private static String darkCacheKey = "";
    private static boolean lastLoggedVal = false;
    private static String lastLoggedHow = "";

    private static String cacheKey(Context c) {
        try {
            android.app.Activity a = findActivity(c);
            if (a != null) return a.getClass().getName();
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * 兜底：主题属性背景色。
     * 只认 colorBackground —— **不要用 windowBackground**：
     * TG 的 windowBackground 在深色主题下常是白色（窗口底色，内容盖在上面），
     * 拿它判主题会把深色误判成浅色（v1.5.8 试过，主界面直接变亮，已回退）。
     */
    private static Boolean attrProbe(Context c) {
        try {
            android.app.Activity a = findActivity(c);
            if (a == null) return null;
            Integer attr = themeAttrColor(a, android.R.attr.colorBackground);
            if (attr != null) return judge(attr.intValue());
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean sysDark(Context c) {
        try {
            int m = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return m == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 颜色探测：任一路取到真实颜色即可判定，主题一改立即跟随。 */
    private static Boolean colorProbe(Context c) {
        try {
            android.app.Activity a = findActivity(c);
            if (a == null) return null;
            // ① 采样当前画面（缩绘成 1 像素的平均色）——最真实，优先级最高
            // 只做画面采样；属性探测已由 attrProbe 负责（顺序更靠前）
            Integer samp = sampleScreenColor(a);
            if (samp != null) return judge(samp.intValue());
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean judge(int color) {
        if (Color.alpha(color) < 8) return null;
        lastColor = color;
        double lum = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0;
        return Boolean.valueOf(lum < 0.5);
    }

    /** 把整个界面缩绘到 1x1 像素取平均色（主线程才可绘制）。 */
    /**
     * 采样页面平均色。
     *
     * **必须采内容区（android.R.id.content），不能采 decorView。**
     * decorView 会带上对话框/面板的半透明遮罩：深色主题下弹出面板时，
     * 整屏平均色被遮罩提亮成灰，亮度越过阈值就被判成"浅色" ——
     * 这正是"主界面深色、子面板变亮"的根因（遮罩会持续存在，不是瞬时帧，
     * 所以单靠"连续两次一致"也救不了）。
     * 内容区不含遮罩层，采到的才是真实底色。
     */
    private static Integer sampleScreenColor(android.app.Activity a) {
        try {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return null;
            android.view.Window w = a.getWindow();
            if (w == null) return null;
            // 优先内容区（不含对话框遮罩）；拿不到再退回 decorView
            android.view.View target = null;
            try {
                target = a.findViewById(android.R.id.content);
            } catch (Throwable ignored) {}
            if (target == null) target = w.getDecorView();
            if (target == null) return null;
            int wd = target.getWidth(), ht = target.getHeight();
            if (wd <= 0 || ht <= 0) return null;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            cv.scale(1f / wd, 1f / ht);
            target.draw(cv);
            int px = bmp.getPixel(0, 0);
            bmp.recycle();
            if (Color.alpha(px) < 8) return null;
            return Integer.valueOf(px);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer themeAttrColor(android.app.Activity a, int attr) {
        try {
            TypedValue tv = new TypedValue();
            if (!a.getTheme().resolveAttribute(attr, tv, true)) return null;
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data == 0 ? null : Integer.valueOf(tv.data);
            }
            if (tv.resourceId != 0) {
                try {
                    android.graphics.drawable.Drawable dd = a.getResources().getDrawable(tv.resourceId, a.getTheme());
                    return colorOf(dd);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static android.app.Activity findActivity(Context c) {
        try {
            Context cur = c;
            for (int i = 0; i < 6 && cur != null; i++) {
                if (cur instanceof android.app.Activity) return (android.app.Activity) cur;
                if (cur instanceof android.content.ContextWrapper) cur = ((android.content.ContextWrapper) cur).getBaseContext();
                else break;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer colorOf(android.graphics.drawable.Drawable d) {
        try {
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) d).getColor();
                if (Color.alpha(c) > 8) return Integer.valueOf(c);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 备用：Theme 静态 API（未混淆）或混淆主题对象。 */
    private static Boolean darkByThemeClass(Context c) {
        ClassLoader cl;
        try { cl = c.getClassLoader(); } catch (Throwable t) { return null; }
        try {
            Class<?> th = Class.forName("org.telegram.ui.ActionBar.Theme", false, cl);
            for (String want : new String[]{"isCurrentThemeDark", "isCurrentThemeNight"}) {
                try {
                    java.lang.reflect.Method m = th.getMethod(want);
                    if (m == null) continue;
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                    Object r = m.invoke(null);
                    if (r instanceof Boolean) return (Boolean) r;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> o6 = Class.forName("org.telegram.ui.ActionBar.o6", false, cl);
            java.lang.reflect.Method a0 = o6.getMethod("A0");
            if (a0 != null && java.lang.reflect.Modifier.isStatic(a0.getModifiers()) && a0.getParameterTypes().length == 0) {
                Object theme = a0.invoke(null);
                if (theme != null) {
                    java.util.List<java.lang.reflect.Method> cand = new java.util.ArrayList<java.lang.reflect.Method>();
                    for (java.lang.reflect.Method m : theme.getClass().getMethods()) {
                        if (m.getParameterTypes().length != 0) continue;
                        if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                        if (m.getDeclaringClass() == Object.class) continue;
                        cand.add(m);
                    }
                    String[] prefer = {"q", "isDark", "isCurrentThemeDark"};
                    for (String pn : prefer) {
                        for (java.lang.reflect.Method m : cand) {
                            if (!m.getName().equals(pn)) continue;
                            Object r = m.invoke(theme);
                            if (r instanceof Boolean) return (Boolean) r;
                        }
                    }
                    if (cand.size() == 1) {
                        Object r = cand.get(0).invoke(theme);
                        if (r instanceof Boolean) return (Boolean) r;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static int txtPrimary(Context c) { return dark(c) ? 0xFFF2F2F2 : 0xFF1F1F1F; }
    static int txtMuted(Context c) { return dark(c) ? 0xFFABAFB4 : 0xFF757575; }
    static int cardBg(Context c) { return dark(c) ? 0xFF2B2F36 : Color.WHITE; }
    static int line(Context c) { return dark(c) ? 0x26FFFFFF : 0x14000000; }
    static int accent(Context c) {
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme() != null && c.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                if (tv.resourceId != 0) {
                    int r = c.getResources().getColor(tv.resourceId);
                    if (r != 0) return r;
                } else if (tv.data != 0) return tv.data;
            }
        } catch (Throwable ignored) {}
        return dark(c) ? 0xFF8AB4F8 : 0xFF2A7AFF;
    }
    static int mix(int a, int b, float r) {
        int f = (int) (r * 255);
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb((ar * (255 - f) + br * f) / 255, (ag * (255 - f) + bg * f) / 255, (ab * (255 - f) + bb * f) / 255);
    }
    static int withAlpha(int c, int a) { return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)); }
    static GradientDrawable card(Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(cardBg(c)); d.setCornerRadius(dp(c, 14)); d.setStroke(dp(c, 1), line(c)); return d;
    }
    static GradientDrawable chipBg(Context c, int col) {
        GradientDrawable d = new GradientDrawable(); d.setColor(col); d.setCornerRadius(dp(c, 9)); return d;
    }
    static GradientDrawable tile(Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(withAlpha(accent(c), 0x26)); d.setCornerRadius(dp(c, 11)); return d;
    }
    static GradientDrawable header(Context c) {
        int a = accent(c);
        int bg = dark(c) ? 0xFF0E1116 : 0xFFFFFFFF;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { a, mix(a, bg, 0.62f) });
        g.setCornerRadius(dp(c, 16)); return g;
    }

    // ==================== 设计令牌 ====================
    // 【字号标尺】5 档，所有界面文字一律用这些常量，禁止再写裸数字。
    static final int TS_CAPTION  = 11;   // 脚注/说明/提示
    static final int TS_SECOND   = 12;   // 次要信息、列表副标题
    static final int TS_BODY     = 14;   // 正文、列表主标题
    static final int TS_SUBTITLE = 16;   // 小标题、对话框标题
    static final int TS_TITLE    = 20;   // 页面级标题

    // 【间距标尺】4 的倍数，一律用 Token.dp(c, N) 取值
    static final int SP_XS = 4;
    static final int SP_SM = 8;
    static final int SP_MD = 12;
    static final int SP_LG = 16;

    // 【圆角标尺】三级（2026-10-04 定）。
    //   背景：改造前**所有东西都是 14dp 圆角** —— 输入框、按钮、chip、卡片全一样。
    //   圆角一致不等于语言一致，反而让"容器 / 控件 / 徽章"的层级差异消失。
    //   现在按角色分三级，任何新控件都必须挂到其中一级。
    static final int R_CONTAINER = 14;   // 对话框、折叠卡片（大容器）
    static final int R_CONTROL   = 8;    // 按钮、输入框、chip（可直接点的东西）
    static final int R_BADGE     = 999;  // 药丸：类型 chip、状态点（用高度一半也行）

    // 【字体分工】数字/ID/时间/指令用等宽保留终端味；中文说明用系统默认字体（等宽遇中文会 fallback，行高不匀）
    static android.graphics.Typeface mono() {
        return android.graphics.Typeface.MONOSPACE;
    }
    static android.graphics.Typeface monoBold() {
        return android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
    }
    static android.graphics.Typeface text() {
        return android.graphics.Typeface.DEFAULT;
    }
    static android.graphics.Typeface textBold() {
        return android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
    }

    // 【颜色语义】取色时按角色取，别按“好看”取：
    //   termCyan  = 主色：标题、可点入口、普通信息
    //   termGreen = 成功：已签、连续天数、开关“开”
    //   termAmber = 进行中：待补、下次签到倒计时
    //   termPink  = 危险：删除、清空、失败
    //   termMuted / termFaint = 纯辅助文字，不承载状态语义

    // ══════════════════════════════════════════════════════════════
    // 色板表（2026-10-09 多主题支持）
    //
    // 结构：PALSETS[styleId][0=日间 / 1=夜间][13 个语义索引]
    // 索引：0 page 1 card 2 inset 3 txt 4 muted 5 faint
    //       6 cyan 7 green 8 amber 9 pink 10 line(argb) 11 fill 12 onfill
    //
    // 新增色板：在 STYLE_KEYS 与 PALSETS 各加一行，styleId 即数组下标。
    // 老用户无 jmb_theme_style 时用 DEFAULT_STYLE（Nord）。
    // ══════════════════════════════════════════════════════════════

    /** Nord（极地冷灰） —— 北境冷灰蓝 · 安静不抢戏 */
    static final int STYLE_NORD = 0;
    /** 终端风（原配色） —— 墨青底 + 霓虹主色 */
    static final int STYLE_TERMINAL = 1;
    /** Solarized（暖砂护眼） —— 低眩光暖砂底 · 久看不累 */
    static final int STYLE_SOLARIZED = 2;
    /** Tokyo Night（深紫夜） —— 深紫底 · 夜间最有氛围 */
    static final int STYLE_TOKYO = 3;
    /** 墨玉（极简无彩） —— 宣纸白配墨字 · 最不吵 */
    static final int STYLE_INKJADE = 4;
    /** 暖琥珀（明亮暖色） —— 米白配琥珀 · 暖亮友善 */
    static final int STYLE_AMBER = 5;
    /** Gruvbox（复古暖棕） —— 暖褐底 · 复古终端味 */
    static final int STYLE_GRUVBOX = 6;
    /** 深海 Ocean（蓝青） —— 深海底蓝 · 冷静通透 */
    static final int STYLE_OCEAN = 7;

    /**
     * 默认色板（2026-10-09 用户改定）。
     * 最初设为 Nord，但用户实际一直用「终端风」——那才是他熟悉的样子。
     * 只影响**没有** jmb_theme_style 键的机器；已经选过别的色板不受影响。
     */
    static final int DEFAULT_STYLE = STYLE_TERMINAL;
    static final int STYLE_COUNT = 8;

    /** 色板显示名（下标 = styleId）。 */
    private static final String[] STYLE_NAMES = {
        "Nord（极地冷灰）",
        "终端风（原配色）",
        "Solarized（暖砂护眼）",
        "Tokyo Night（深紫夜）",
        "墨玉（极简无彩）",
        "暖琥珀（明亮暖色）",
        "Gruvbox（复古暖棕）",
        "深海 Ocean（蓝青）",
    };

    /** 色板键名（持久化用，改名会丢配置 —— 只增不改）。 */
    static final String[] STYLE_KEYS = {
        "nord",
        "terminal",
        "solarized",
        "tokyo",
        "inkjade",
        "amber",
        "gruvbox",
        "ocean",
    };

    /** 色板描述（设置页提示语）。 */
    private static final String[] STYLE_DESCS = {
        "北境冷灰蓝 · 安静不抢戏",
        "墨青底 + 霓虹主色",
        "低眩光暖砂底 · 久看不累",
        "深紫底 · 夜间最有氛围",
        "宣纸白配墨字 · 最不吵",
        "米白配琥珀 · 暖亮友善",
        "暖褐底 · 复古终端味",
        "深海底蓝 · 冷静通透",
    };

    /** 明暗 × 色板 二维表。 */
    private static final int[][][] PALSETS = {
        { // nord
            { 0xFFECEFF4, 0xFFFFFFFF, 0xFFE5E9F0, 0xFF2E3440, 0xFF4C566A, 0xFF7B88A1, 0xFF5E81AC, 0xFF4E8A5F, 0xFFB8891F, 0xFFBF616A, 0x142E3440, 0xFF5E81AC, 0xFFFFFFFF },
            { 0xFF2E3440, 0xFF3B4252, 0xFF353C4A, 0xFFECEFF4, 0xFFA6B1C2, 0xFF7B88A1, 0xFF88C0D0, 0xFFA3BE8C, 0xFFEBCB8B, 0xFFBF616A, 0x26FFFFFF, 0xFF88C0D0, 0xFF2E3440 },
        },
        { // terminal
            { 0xFFF4F5F7, 0xFFFFFFFF, 0xFFF7F8FA, 0xFF1F2A3D, 0xFF5A6B85, 0xFF7A8AA3, 0xFF006E84, 0xFF00796B, 0xFFB26A00, 0xFFC2185B, 0x14000000, 0xFF0E8F63, 0xFFFFFFFF },
            { 0xFF070B14, 0xFF131A2A, 0xFF0E1424, 0xFFE6F1FF, 0xFF8B98B8, 0xFF5A6A8A, 0xFF00E5FF, 0xFF00FF9C, 0xFFFFB84D, 0xFFFF5A76, 0x26FFFFFF, 0xFF0E9F6E, 0xFF06231A },
        },
        { // solarized
            { 0xFFFDF6E3, 0xFFFFFFFF, 0xFFEEE8D5, 0xFF073642, 0xFF586E75, 0xFF93A1A1, 0xFF268BD2, 0xFF7A8B00, 0xFFB58900, 0xFFDC322F, 0x14073642, 0xFF268BD2, 0xFFFFFFFF },
            { 0xFF002B36, 0xFF073642, 0xFF02323C, 0xFFEEE8D5, 0xFF93A1A1, 0xFF586E75, 0xFF2AA198, 0xFF859900, 0xFFB58900, 0xFFDC322F, 0x26FFFFFF, 0xFF2AA198, 0xFF002B36 },
        },
        { // tokyo
            { 0xFFF0F2F8, 0xFFFFFFFF, 0xFFE9ECF5, 0xFF343B58, 0xFF6172B0, 0xFF8990B3, 0xFF2E7DE9, 0xFF4E7A35, 0xFF8C6C3E, 0xFFD33B57, 0x14343B58, 0xFF2E7DE9, 0xFFFFFFFF },
            { 0xFF1A1B26, 0xFF24283B, 0xFF1F2335, 0xFFC0CAF5, 0xFF7982A9, 0xFF565F89, 0xFF7AA2F7, 0xFF9ECE6A, 0xFFE0AF68, 0xFFF7768E, 0x267AA2F7, 0xFF7AA2F7, 0xFF1A1B26 },
        },
        { // inkjade
            { 0xFFF7F6F3, 0xFFFFFFFF, 0xFFF0EFEB, 0xFF1C1C1C, 0xFF6B6B6B, 0xFF9B9B9B, 0xFF17726B, 0xFF3E7A4E, 0xFF96702C, 0xFFA33A3A, 0x141C1C1C, 0xFF17726B, 0xFFFFFFFF },
            { 0xFF101010, 0xFF1C1C1C, 0xFF161616, 0xFFEDEDED, 0xFFA0A0A0, 0xFF6E6E6E, 0xFF5FB3A8, 0xFF7FBF8F, 0xFFD9B26A, 0xFFE08080, 0x26FFFFFF, 0xFF5FB3A8, 0xFF101010 },
        },
        { // amber
            { 0xFFFBF7F0, 0xFFFFFFFF, 0xFFF5EFE4, 0xFF2B2118, 0xFF7A6A55, 0xFFA89A87, 0xFFC2761A, 0xFF5E8C3E, 0xFFB8860B, 0xFFC0473C, 0x142B2118, 0xFFC2761A, 0xFFFFFFFF },
            { 0xFF1A1512, 0xFF26201A, 0xFF201A15, 0xFFF0E6D8, 0xFFB3A491, 0xFF7D7264, 0xFFF0A94B, 0xFFA3C46A, 0xFFE0B860, 0xFFF08272, 0x26FFFFFF, 0xFFF0A94B, 0xFF1A1512 },
        },
        { // gruvbox
            { 0xFFFBF1C7, 0xFFFFF9E8, 0xFFF2E5BC, 0xFF3C3836, 0xFF7C6F64, 0xFFA89984, 0xFFAF3A03, 0xFF79740E, 0xFFB57614, 0xFF9D0006, 0x143C3836, 0xFFAF3A03, 0xFFFBF1C7 },
            { 0xFF1D2021, 0xFF282828, 0xFF232323, 0xFFEBDBB2, 0xFFA89984, 0xFF7C6F64, 0xFFFE8019, 0xFFB8BB26, 0xFFFABD2F, 0xFFFB4934, 0x26EBDBB2, 0xFFFE8019, 0xFF1D2021 },
        },
        { // ocean
            { 0xFFEEF4F8, 0xFFFFFFFF, 0xFFE4EDF3, 0xFF12303F, 0xFF4A6B7C, 0xFF7E99A8, 0xFF0F7A9C, 0xFF2E8B6E, 0xFFB8801F, 0xFFC4444E, 0x1412303F, 0xFF0F7A9C, 0xFFFFFFFF },
            { 0xFF0A1620, 0xFF12222E, 0xFF0E1C26, 0xFFD6E8F2, 0xFF84A3B5, 0xFF5B7A8C, 0xFF4FC3E8, 0xFF5FD3A8, 0xFFF0BE6A, 0xFFF27C86, 0x264FC3E8, 0xFF4FC3E8, 0xFF0A1620 },
        },
    };

    // 语义索引常量
    private static final int I_PAGE = 0;
    private static final int I_CARD = 1;
    private static final int I_INSET = 2;
    private static final int I_TXT = 3;
    private static final int I_MUTED = 4;
    private static final int I_FAINT = 5;
    private static final int I_CYAN = 6;
    private static final int I_GREEN = 7;
    private static final int I_AMBER = 8;
    private static final int I_PINK = 9;
    private static final int I_LINE = 10;
    private static final int I_FILL = 11;
    private static final int I_ONFILL = 12;

    /** 当前色板 id。volatile：保存与渲染在不同线程都可能读。 */
    static volatile int styleId = DEFAULT_STYLE;

    /** 色板显示名。 */
    static String styleName() {
        try { return STYLE_NAMES[clampStyle(styleId)]; } catch (Throwable t) { return STYLE_NAMES[DEFAULT_STYLE]; }
    }

    /** 色板描述。 */
    static String styleDesc() {
        try { return STYLE_DESCS[clampStyle(styleId)]; } catch (Throwable t) { return STYLE_DESCS[DEFAULT_STYLE]; }
    }

    /** 持久化键名。 */
    static String styleKey() {
        try { return STYLE_KEYS[clampStyle(styleId)]; } catch (Throwable t) { return STYLE_KEYS[DEFAULT_STYLE]; }
    }

    /** 按持久化键名还原下标；未知键退默认。 */
    static int styleFromKey(String k) {
        if (k != null) {
            for (int i = 0; i < STYLE_KEYS.length; i++) if (k.equals(STYLE_KEYS[i])) return i;
        }
        return DEFAULT_STYLE;
    }

    static int clampStyle(int s) {
        if (s < 0 || s >= STYLE_COUNT) return DEFAULT_STYLE;
        return s;
    }

    /**
     * 取语义色。所有主题色都从这里出 —— termXxx(c) 只是它的薄封装。
     * 调用点一律用 termXxx(c)，不要直接调本方法（保持语义可读）。
     */
    static int color(Context c, int idx) {
        return paletteColor(clampStyle(styleId), dark(c) ? 1 : 0, idx);
    }

    /**
     * 描边色（合成到给定底色上）。
     * 色板里的 line 是**带 alpha 的 ARGB**，需要跟底合成才是可见色 ——
     * 直接当实色用会在浅底上出现"脏灰线"。
     */
    static int lineOn(Context c, int base) {
        return over(base, color(c, I_LINE));
    }

    /** 把带 alpha 的 fg 合成到 opaque base 上。 */
    static int over(int base, int fg) {
        try {
            int a = (fg >>> 24) & 0xFF;
            if (a == 255) return fg;
            if (a == 0) return base;
            int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
            int fr = (fg >> 16) & 0xFF, fgc = (fg >> 8) & 0xFF, fb = fg & 0xFF;
            int r = (fr * a + br * (255 - a)) / 255;
            int g2 = (fgc * a + bg * (255 - a)) / 255;
            int b2 = (fb * a + bb * (255 - a)) / 255;
            return 0xFF000000 | (r << 16) | (g2 << 8) | b2;
        } catch (Throwable t) { return base; }
    }

    // ---- 终端风色板（v1.5.1 起：日间浅底适配，暗色配色不变） ----
    // 2026-10-04 日间色调优化：旧值偏冷（#F2F5FA 蓝味重），在白屏上显脏；
    //   改暖中性，且与 surface(1) 的纯白卡形成明确层次。
    // ── 语义色（2026-10-09：全部转发到色板，440+ 调用点零改动）──
    // 注意：这些名字是**语义**不是颜色 —— termCyan 叫cyan但Nord下是灰蓝。
    // 改名会牵动 440 处调用，故保留原名，只把取值换成 color(c, I_XXX)。
    static int termCard(Context c)      { return color(c, I_CARD); }
    static int termCardDeep(Context c)  { return color(c, I_PAGE); }
    static int termCardInput(Context c) { return color(c, I_INSET); }
    static int termTxt(Context c)       { return color(c, I_TXT); }
    static int termMuted(Context c)     { return color(c, I_MUTED); }
    static int termFaint(Context c)     { return color(c, I_FAINT); }
    static int termCyan(Context c)      { return color(c, I_CYAN); }
    static int termGreen(Context c)     { return color(c, I_GREEN); }
    static int termPink(Context c)      { return color(c, I_PINK); }
    static int termAmber(Context c)     { return color(c, I_AMBER); }
    /** 描边色（已按底色合成）。 */
    static int termLine(Context c, int base) { return lineOn(c, base); }

    // ── 面板底色三档（2026-10-04 新增）────────────────────────────────
    // 为什么要有这个：日间模式的"发灰"根因是**在浅底上再叠半透明**。
    //   半透明叠浅色 = 只会更灰更脏（色彩学上无法避免）。
    //   正解是改用**实色分层**：靠三层明度差表达层次。
    // 明暗两套策略不同：
    //   夜间 —— 深底上半透明叠加效果好，沿用叠色；
    //   日间 —— 直接给三档实色，其中 level1 是纯白卡（白卡 + 微灰底 = 天然层次）。
    // level: 0 = 页面底 / 1 = 卡片（默认） / 2 = 卡片内嵌（输入框、次级块）
    static int surface(Context c, int level) {
        // 2026-10-09：改走色板。level 语义不变 ——
        //   0 = 页面底 / 1 = 卡片 / 2 = 卡内嵌（输入框、次级块）
        if (level <= 0) return color(c, I_PAGE);
        if (level == 1) return color(c, I_CARD);
        return color(c, I_INSET);
    }

    /**
     * 主操作实心底色：**全屏最多出现一个**，只留给「保存 / 确认」这类提交动作。
     *
     * 2026-10-04 二次调整（用户反馈"绿色有点亮"）：
     *   初版 #00B87C 在深色底上饱和度过高，而且当时的 mkBtnPrimary 把所有主按钮
     *   都改成了实心 —— 一屏出现「测试」「保存」两个亮绿块，主次反而糊了。
     *   现在明度各降一档，并明确：可重复执行的动作（测试/自检/查看）一律不用实心。
     */
    /**
     * 主操作实心底色：全屏最多出现一个，只留给「保存 / 确认」这类提交动作。
     *
     * 2026-10-09 多主题修复：
     *   原来写死 `dark ? 0xFF0E9F6E : 0xFF0E8F63`（一种饱和绿）——
     *   于是**所有色板下主按钮都是同一块绿**，在暖色/冷色主题里都像贴上去的补丁。
     *   现在读色板的 fill token（每套色板都定义了它，就是为这个用途准备的）。
     */
    static int primaryFill(Context c) { return color(c, I_FILL); }

    /** 主操作上的文字色。亮底配深字、暗底配浅字，由色板的 onfill token 给出。 */
    static int onPrimary(Context c)   { return color(c, I_ONFILL); }

    /**
     * 安静动作（Quiet）：无底 + 主色字。用于测试、自检、查看这类可重复操作。
     *
     * 2026-10-09 多主题：改为跟色板走（原来是写死的薄荷青 / 深青）。
     * 取主色并按明暗做一次可读性校正 —— 亮底压深、暗底提亮，任何色板都成立。
     */
    static int quietText(Context c) {
        int cy = color(c, I_CYAN);
        return readable(c, cy, cy);
    }

    /** 淡底控件块（无描边）：普通按钮、开关轨道。alpha 由调用方给。 */
    static int softFill(Context c, int base, int alpha) {
        return withAlpha(base, alpha);
    }

    /**
     * 主色之上的文字色（2026-10-09 多主题）。
     * 语义：给定一个"强调色"（绿/青/粉…），返回压在它上面的**文字**色。
     * 老实现写死 dark?0xFFE8FFF6:blendText(...)，换色板后不跟。
     * 现按"强调色的明度"决定：亮色用深字，暗色用浅字。
     */
    static int onAccentText(Context c, int accent) {
        try {
            double lum = (0.2126 * Color.red(accent) + 0.7152 * Color.green(accent)
                        + 0.0722 * Color.blue(accent)) / 255.0;
            // 亮底 → 用页面底色的深色版当字；暗底 → 用浅色
            int base = color(c, I_TXT);
            if (lum > 0.55) {
                return dark(c) ? base : 0xFF0A1F18;
            }
            return dark(c) ? 0xFFE8FFF6 : mix(base, 0xFF000000, 0.15f);
        } catch (Throwable t) { return color(c, I_TXT); }
    }

    /** 标题闪烁色（2026-10-09：改为色板派生，不再写死）。 */
    static int flashColor(Context c) {
        return dark(c) ? 0xFFFFFFFF : mix(color(c, I_PAGE), 0xFF000000, 0.88f);
    }

    // ══════════════════════════════════════════════════════════════
    // 设置页支持 API（2026-10-09）
    // 预览要在"不切换全局 styleId"的前提下取指定色板的颜色，
    // 所以下面这些都收**显式下标**，不读 styleId。
    // ══════════════════════════════════════════════════════════════

    /** 预览条索引（与 I_* 对应，供设置页画色块）。 */
    static final int SW_PAGE = I_PAGE, SW_CARD = I_CARD, SW_CYAN = I_CYAN,
                     SW_GREEN = I_GREEN, SW_AMBER = I_AMBER, SW_PINK = I_PINK;

    /** 指定下标的色板名。 */
    static String styleName(int sid) {
        try { return STYLE_NAMES[clampStyle(sid)]; } catch (Throwable t) { return STYLE_NAMES[DEFAULT_STYLE]; }
    }

    /** 指定下标的色板描述。 */
    static String styleDesc(int sid) {
        try { return STYLE_DESCS[clampStyle(sid)]; } catch (Throwable t) { return STYLE_DESCS[DEFAULT_STYLE]; }
    }

    /** 指定色板 + 当前明暗的某个语义色（预览用）。 */
    static int previewColor(Context c, int idx) {
        return paletteColor(clampStyle(styleId), dark(c) ? 1 : 0, idx);
    }

    /** 指定色板 + 指定明暗行的语义色。 */
    static int previewColorAt(int sid, boolean isDark, int idx) {
        return paletteColor(clampStyle(sid), isDark ? 1 : 0, idx);
    }

    /** 底层取色（带两级兜底）。 */
    private static int paletteColor(int sid, int rowIdx, int idx) {
        try {
            return PALSETS[sid][rowIdx][idx];
        } catch (Throwable t) {
            try { return PALSETS[DEFAULT_STYLE][rowIdx][idx]; } catch (Throwable t2) { return 0xFF808080; }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 日志等级配色（2026-10-09 多主题）
    // 原实现写死 5 档 × 4 值，换色板后日志行不跟。现改为派生。
    //   LV_ERR=0? 不 —— 这里用与 Core 相同的常量值语义：
    //     0=普通 1=成功 2=警告 3=错误 4=调试（见 Core 的 LV_* 定义）
    // 返回 int[4] = { textCol, tsCol, barCol, rowBg }
    // ══════════════════════════════════════════════════════════════
    static int[] levelColors(Context c, int lv) {
        int text, bar;
        switch (lv) {
            case 4:  // 错误
                text = color(c, I_PINK);  bar = color(c, I_PINK);  break;
            case 3:  // 警告
                text = color(c, I_AMBER); bar = color(c, I_AMBER); break;
            case 2:  // 成功
                text = color(c, I_GREEN); bar = color(c, I_GREEN); break;
            case 1:  // 调试
                text = color(c, I_FAINT); bar = color(c, I_FAINT); break;
            default: // 普通
                text = color(c, I_MUTED); bar = color(c, I_MUTED); break;
        }
        // 亮底可读性：与 onAccentText 同一判据
        int shown = readable(c, text, bar);
        int ts = color(c, I_FAINT);
        int rowBg = withAlpha(text, dark(c) ? 0x1C : 0x12);
        return new int[]{ shown, ts, shown, rowBg };
    }

    /** 语义色在**当前明暗**下的可读版本（亮底压深、暗底提亮）。 */
    static int readable(Context c, int accent, int fallback) {
        try {
            double lum = (0.2126 * Color.red(accent) + 0.7152 * Color.green(accent)
                        + 0.0722 * Color.blue(accent)) / 255.0;
            if (dark(c)) {
                // 暗底：颜色太深就提亮
                if (lum < 0.35) return mix(accent, 0xFFFFFFFF, 0.45f);
                return accent;
            }
            // 亮底：颜色太浅就压深（保留色相）
            if (lum > 0.62) return mix(accent, 0xFF000000, 0.45f);
            return accent;
        } catch (Throwable t) { return fallback; }
    }

    /**
     * 暗色下强调色之上的文字色（无 Context 版本，2026-10-09）。
     * 供 Core.blendText(boolean,int) 这类拿不到 Context 的旧签名使用。
     * 按当前色板的 txt 色轻微提亮 —— 保证"已签"字样在深底上够亮，
     * 但又不像纯白那样刺眼（原实现是写死的 0xFFE8FFF6）。
     */
    static int darkAccentText(int accent) {
        try {
            // 当前色板夜间行的主文字色（I_TXT），提亮 12%
            int base = paletteColor(clampStyle(styleId), 1, I_TXT);
            return mix(base, 0xFFFFFFFF, 0.12f);
        } catch (Throwable t) { return 0xFFE8FFF6; }
    }

    // ══════════════════════════════════════════════════════════════
    // 主题过渡插值（2026-10-09）
    // 用途：切换色板时，让**正在显示的**元素颜色平滑过渡而非硬切。
    // 代价控制：快速路径只多一个 volatile boolean 判断；
    //           插值仅在过渡窗口内发生（约 260ms）。
    // ══════════════════════════════════════════════════════════════

    /** 过渡起点色（13 个 token）。null = 无过渡。 */
    private static volatile int[] transFrom = null;
    /** 过渡进度 0..1。 */
    private static volatile float transT = 1f;

    /** 开始一次颜色过渡：把「当前色板 + 当前明暗」的 13 色记为起点。 */
    static void beginTransition(Context c) {
        try {
            int row = dark(c) ? 1 : 0;
            int sid = clampStyle(styleId);
            int[] from = new int[14];
            for (int i = 0; i < 14; i++) from[i] = paletteColor(sid, row, i);
            transFrom = from;
            transT = 0f;
        } catch (Throwable ignored) {}
    }

    /** 推进过渡进度。 */
    static void setTransition(float t) {
        transT = t < 0f ? 0f : (t > 1f ? 1f : t);
        if (t >= 1f) transFrom = null;      // 结束：清掉，回到快速路径
    }

    static boolean inTransition() { return transFrom != null; }

    /** 用终点色覆盖起点（过渡结束时的收尾，防止残留）。 */
    static void endTransition() { transFrom = null; transT = 1f; }

    /**
     * 带过渡的取色。比 color() 多一层判断 —— 只在过渡期走插值。
     * 过渡期返回「起点色 → 当前色板色」按 transT 混合的结果。
     */
    static int colorT(Context c, int idx) {
        int base = color(c, idx);
        int[] from = transFrom;
        if (from == null) return base;
        try {
            int f = from[idx];
            return mix(f, base, transT);
        } catch (Throwable t) { return base; }
    }

    /** 过渡期的插值色（供需要显式走过渡的调用方使用）。 */
    static int transColor(int idx) {
        int[] from = transFrom;
        int base = color(safeCtx, idx);
        if (from == null) return base;
        try { return mix(from[idx], base, transT); } catch (Throwable t) { return base; }
    }

    /** 过渡用的 Context（由 beginTransition 的调用方设置）。 */
    private static volatile Context safeCtx = null;
    static void setTransitionContext(Context c) { safeCtx = c; }
}
