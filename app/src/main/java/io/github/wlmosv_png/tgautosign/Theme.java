package io.github.wlmosv_png.tgautosign;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

final class Theme {
    private Theme() {}
    static int dp(Context c, float v) {
        return Math.max(1, (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()) + 0.5f));
    }
    private static boolean themeLogDone;
    private static long darkCacheAt = 0L;
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
        if (now - darkCacheAt < 600L && key.equals(darkCacheKey)) return darkCacheVal;

        boolean val;
        String how;
        lastColor = 0;

        // ① 画面采样 —— **主判据**（历史上一贯准确，v1.5.1 起就用它）。
        //    但加两道保险，避免子面板动画/遮罩期采到瞬时帧而判反：
        //      · 同一页面连续两次一致才采信；不一致时先沿用上一次的结论（不翻转）
        //      · 缓存按页面分开，主界面与子面板互不污染
        Boolean byColor = colorProbe(c);
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

    // ---- 终端风色板（v1.5.1 起：日间浅底适配，暗色配色不变） ----
    static int termCard(Context c)      { return dark(c) ? 0xFF101726 : 0xFFF2F5FA; }
    static int termCardDeep(Context c)  { return dark(c) ? 0xFF0A0F1A : 0xFFE8EDF5; }
    static int termCardInput(Context c) { return dark(c) ? 0xFF0D1424 : 0xFFEDF1F8; }
    static int termTxt(Context c)       { return dark(c) ? 0xFFE6F1FF : 0xFF1F2A3D; }
    static int termMuted(Context c)     { return dark(c) ? 0xFF8B98B8 : 0xFF5A6B85; }
    static int termFaint(Context c)     { return dark(c) ? 0xFF5A6A8A : 0xFF7A8AA3; }
    static int termCyan(Context c)      { return dark(c) ? 0xFF00E5FF : 0xFF007C91; }
    static int termGreen(Context c)     { return dark(c) ? 0xFF00FF9C : 0xFF00796B; }
    static int termPink(Context c)      { return dark(c) ? 0xFFFF5A76 : 0xFFC2185B; }
    static int termAmber(Context c)     { return dark(c) ? 0xFFFFB84D : 0xFFB26A00; }
}
