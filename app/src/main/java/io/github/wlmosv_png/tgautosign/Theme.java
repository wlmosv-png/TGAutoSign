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
    static boolean dark(Context c) {
        boolean tg = false;
        String how = "no-method";
        boolean sys = false;
        try { int m = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK; sys = m == Configuration.UI_MODE_NIGHT_YES; }
        catch (Throwable t) { sys = false; }
        ClassLoader cl = c.getClassLoader();
        // ① 原版 Theme.isCurrentThemeDark（ExteraLess 等未混淆客户端）
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
                    if (r instanceof Boolean) {
                        tg = ((Boolean) r).booleanValue();
                        how = want;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { how = "exc-1"; }
        // ② 官方版 / Nagram 12.10.3+：混淆后 ActionBar.o6.A0() 返回当前主题对象，n6.q() 是暗色判断
        if (how.startsWith("no-method") || how.startsWith("exc-1")) {
            try {
                Class<?> o6 = Class.forName("org.telegram.ui.ActionBar.o6", false, cl);
                java.lang.reflect.Method a0 = o6.getMethod("A0");
                if (a0 != null && java.lang.reflect.Modifier.isStatic(a0.getModifiers()) && a0.getParameterTypes().length == 0) {
                    Object theme = a0.invoke(null);
                    if (theme != null) {
                        // 遍历主题对象方法，找无参返回 boolean 的（混淆后通常唯一，如 n6.q）
                        for (java.lang.reflect.Method m : theme.getClass().getMethods()) {
                            if (m.getParameterTypes().length != 0) continue;
                            if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                            Object r = m.invoke(theme);
                            if (r instanceof Boolean) {
                                tg = ((Boolean) r).booleanValue();
                                how = "o6.A0()." + m.getName();
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable t) { how = "exc-2"; }
        }
        // ③ 兜底：系统 uiMode
        if (how.startsWith("no-method") || how.startsWith("exc-")) { tg = sys; how += "->sys"; }
        if (!themeLogDone) {
            themeLogDone = true;
            android.util.Log.i("TGAutoSignModule", "theme dark=" + tg + " 来源=" + how + " 系统=" + (sys ? "dark" : "light"));
        }
        return tg;
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
