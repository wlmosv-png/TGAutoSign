package io.github.wlmosv_png.tgautosign;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 统计页的**渲染层**（2026-10-04 分层重构，从 TGAutoSignCore 抽出约 600 行）。
 *
 * 职责边界：
 *   输入 StatsSnapshot（纯数据，Core 填好）
 *   输出 View（纯界面，不读任何 prefs / 不改任何状态）
 *
 * 因此本文件可以独立阅读：想知道"统计页长什么样"，只看这里。
 * 数据口径与算法在 StatsSnapshot 的生产方（Core.statsSnapshot）与 StatsData 里。
 */
final class StatsView {
    private StatsView() {}

    /** 入场动画只播一次的闸（同一个 View 树内）。 */
    private static final String TAG_ANIMATED = "stats_animated";

    private static int dp(Context c, float v) { return Theme.dp(c, v); }

    /**
     * 统计页**局部字号梯度**（2026-10-04）。
     *
     * 用户反馈「字变得很小」。原因是统计页密集用了
     * 全局最小两档（TS_CAPTION=11 / TS_SECOND=12），
     * 而它与普通列表页不同：列表的主标题是 TS_BODY=14，
     * 统计页却**没有**这一档 —— 全是脚注大小。
     *
     * 这里给统计页自己一组（不动全局主题，其它页面不受影响）：
     *   说明文字 11 → 12.5，次要信息 12 → 13.5。
     */
    private static final float FS_SMALL = 12.5f;
    private static final float FS_MID   = 13.5f;

    /** 统一的卡片底（与 Core 里的 termBorder 观感一致；此处自带一份避免反向依赖）。 */
    private static android.graphics.drawable.GradientDrawable border(Context c, int bg, int line) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(Theme.dp(c, 14));
        try { g.setColor(bg); g.setStroke(Theme.dp(c, 1), line); } catch (Throwable ignored) {}
        return g;
    }

    /**
     * 构建整个统计页。
     *
     * @param animate 是否播放入场动画（首次进入 true；每 4 秒的刷新传 false，
     *                否则数字与柱子会不停重播，很吵）
     */
    /**
     * 本次构建收集到的"待播动画"（2026-10-04）。
     *
     * 为什么要延迟到可见才播：
     *   统计页比一屏长，原先所有动画在建树那一刻齐发 ——
     *   等用户滚到下面（趋势 / 热力图 / 各目标），动画早跑完了，等于没看到。
     *   现在把每个区块的"播放入口"连同它自己收集起来，
     *   交给调用方（Core）在滚动时按可见性触发。
     *
     * 结构：每个元素是 Object[]{ View 锚点, Runnable 播放入口, int 延迟 }。
     */
    static final class Pending {
        final List<Object[]> items = new java.util.ArrayList<Object[]>();
        void add(View anchor, Runnable play, int delay) {
            items.add(new Object[]{anchor, play, Integer.valueOf(delay)});
        }
        int size() { return items.size(); }
    }

    /**
     * 构建统计页。
     *
     * @param animate true = 收集动画任务（由调用方按可见性触发）；
     *                false = 直接落到终态（4 秒刷新用，避免重播）
     * @param out     收集容器（animate=true 时使用）
     */
    static View build(Activity act, StatsSnapshot s, boolean animate, Pending out) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        // ⚠️ **每块独立 try**（2026-10-04 修"下面整段没有"）：
        // 原先一个大 try 包住全部，任何一块抛异常 → 后面的区块全不渲染，
        // 表现为"内容到某处就断了"（用户截图：到「各账号对比」就没了）。
        // 分块后一块失败只丢它自己，其余照常。
        try { box.addView(hero(act, s, animate, out)); } catch (Throwable t) { swallow(t); }
        addSafe(box, act, Lang.tr("近 30 天趋势"), new Block() {
            @Override public View make() { return trend(act, s, animate, out); }
        });
        addSafe(box, act, Lang.tr("近 90 天打卡"), new Block() {
            @Override public View make() { return heat(act, s, animate, out); }
        });
        addSafe(box, act, Lang.tr("各账号对比"), new Block() {
            @Override public View make() { return accounts(act, s, animate, out); }
        });
        addSafe(box, act, Lang.tr("星期分布"), new Block() {
            @Override public View make() { return weekday(act, s, animate, out); }
        });
        addSafe(box, act, Lang.tr("今日时段"), new Block() {
            @Override public View make() { return hours(act, s, animate, out); }
        });
        addSafe(box, act, Lang.tr("各目标"), new Block() {
            @Override public View make() { return targetCards(act, s, animate, out); }
        });
        if (animate) box.setTag(TAG_ANIMATED);
        return box;
    }

    /** 区块工厂（供 addSafe 调用）。 */
    private interface Block { View make(); }

    /**
     * 先加一个区块，失败就跳过它自己（不影响后续），
     * 并在失败时留一行可读提示 —— 比"整段消失"更容易排查。
     */
    private static void addSafe(LinearLayout box, Activity act, String title, Block b) {
        View content;
        try {
            content = b.make();
        } catch (Throwable t) {
            swallow(t);
            content = null;
        }
        // 标题也只在有内容时才加（避免出现"只有标题没内容"的空段）
        if (content == null) return;
        try { box.addView(sectionTitle(act, title)); } catch (Throwable ignored) {}
        try { box.addView(content); } catch (Throwable ignored) {}
    }

    /** 兼容旧签名（不收集动画，直接终态）。 */
    static View build(Activity act, StatsSnapshot s, boolean animate) {
        return build(act, s, animate, null);
    }

    /** 该 View 是否已经播过入场动画。 */
    static boolean animated(View v) {
        return v != null && TAG_ANIMATED.equals(v.getTag());
    }

    private static void swallow(Throwable t) {
        try {
            android.util.Log.w("TGAutoSign", "[统计构件失败] " + t);
            // 打类名栈头，便于定位是哪一块（分块 try 后每块的异常都会到这里）
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) {
                android.util.Log.w("TGAutoSign", "[统计构件失败]   at " + st[i]);
            }
        } catch (Throwable ignored) {}
    }

    // ══════════════════════ ① 门面 ══════════════════════

    private static View hero(Activity act, StatsSnapshot s, boolean animate, Pending out) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(act, 10), dp(act, 12), dp(act, 10), dp(act, 14));
        boolean allDone = s.todayTotal > 0 && s.todaySigned >= s.todayTotal;
        int col = allDone ? Theme.termGreen(act) : Theme.termCyan(act);

        // 环形 + 圆心数字（数字带滚动动画）
        FrameLayout ring = new FrameLayout(act);
        int ringPx = dp(act, 108);
        ImageView iv = new ImageView(act);
        final float ratio = s.todayTotal <= 0 ? 0f : (float) s.todaySigned / s.todayTotal;
        // 换用 RingScanDrawable：弧末端有**扫描头 + 辉光**、起点有十字准星 ——
        // 与终端主题同语汇（旧版只是一段静止圆弧，太素）。
        final StatsCharts.RingScanDrawable rd =
                new StatsCharts.RingScanDrawable(ringPx, col, Theme.withAlpha(col, 0x33),
                        s.todayTotal);   // 整圈按目标数分格（亮格 = 已签）
        rd.setProgress(animate ? 0f : ratio);
        iv.setImageDrawable(rd);
        ring.addView(iv, new FrameLayout.LayoutParams(ringPx, ringPx));
        if (animate && out != null) {
            out.add(iv, new Runnable() { @Override public void run() {
                try {
                    android.animation.ValueAnimator va =
                            android.animation.ValueAnimator.ofFloat(0f, ratio);
                    va.setDuration(1250);
                    va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
                    va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                            try { rd.setProgress((Float) a.getAnimatedValue()); } catch (Throwable ignored) {}
                        }
                    });
                    va.start();
                } catch (Throwable ignored) {}
            } }, 0);
        }

        LinearLayout center = new LinearLayout(act);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        int inner = (int) (ringPx * 0.62f);
        TextView num = new TextView(act);
        num.setTextSize(23);
        num.setTextColor(col);
        num.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        num.setSingleLine(true);
        num.setText(s.todaySigned + "/" + s.todayTotal);
        center.addView(num, new LinearLayout.LayoutParams(inner, -2));
        if (animate && out != null) {
            out.add(num, new Runnable() { @Override public void run() {
                countUp(num, s.todaySigned, s.todayTotal);
            } }, 0);
        }
        TextView sub = new TextView(act);
        sub.setTextSize(FS_SMALL);
        sub.setTextColor(Theme.termMuted(act));
        sub.setTypeface(Theme.text());
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setText(Lang.tr("今日完成"));
        center.addView(sub, new LinearLayout.LayoutParams(inner, -2));
        ring.addView(center, new FrameLayout.LayoutParams(inner, -2, Gravity.CENTER));
        card.addView(ring);

        LinearLayout meta = new LinearLayout(act);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(act, 14), 0, 0, 0);
        meta.addView(metaRow(act, Lang.tr("连续"), s.streak + " " + Lang.tr("天"), Theme.termGreen(act)));
        meta.addView(metaRow(act, Lang.tr("累计"), s.totalDays + " " + Lang.tr("天"), Theme.termTxt(act)));
        meta.addView(metaRow(act, Lang.tr("近 30 天"), s.hits30 + "/30",
                s.hits30 >= 25 ? Theme.termGreen(act) : Theme.termAmber(act)));
        card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1f));

        // 门面卡顶部扫过一条细线：整块像刚被"扫描建立"。
        // ScanLineDrawable 已带渐变头尾，短促（420ms）不拖沓。
        if (animate && out != null) {
            FrameLayout holder = new FrameLayout(act);
            holder.addView(card, new FrameLayout.LayoutParams(-1, -2));
            ImageView scan = new ImageView(act);
            final StatsCharts.ScanLineDrawable sd = new StatsCharts.ScanLineDrawable(col);
            scan.setImageDrawable(sd);
            holder.addView(scan, new FrameLayout.LayoutParams(-1, dp(act, 2)));
            out.add(holder, new Runnable() { @Override public void run() {
                try {
                    android.animation.ValueAnimator va =
                            android.animation.ValueAnimator.ofFloat(0f, 1f);
                    va.setDuration(620);
                    va.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                    va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                            try { sd.setPos((Float) a.getAnimatedValue()); } catch (Throwable ignored) {}
                        }
                    });
                    va.start();
                } catch (Throwable ignored) {}
            } }, 0);
            return holder;
        }
        return card;
    }

    private static View metaRow(Activity act, String label, String value, int col) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(act, 4), 0, dp(act, 4));
        TextView l = new TextView(act);
        l.setTextSize(FS_SMALL);
        l.setTextColor(Theme.termMuted(act));
        l.setTypeface(Theme.text());
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(act);
        v.setTextSize(FS_MID);
        v.setTextColor(col);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setText(value);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        return r;
    }

    /** 数字滚动：0 → 目标值（约 350ms）。 */
    private static void countUp(final TextView tv, final int signed, final int total) {
        try {
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofInt(0, signed);
            va.setDuration(560);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    try { tv.setText(((Integer) a.getAnimatedValue()) + "/" + total); }
                    catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    // ══════════════════════ ② 趋势 ══════════════════════

    private static View trend(Activity act, StatsSnapshot s, boolean animate, Pending out) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        try {
            List<String> range = StatsData.dateRange(s.today, 30, false);
            float[] daily = StatsData.dailyHits(s.signDays, range);
            float[] smooth = StatsData.movingAvg(daily, 7);
                ImageView iv = new ImageView(act);
            int hPx = dp(act, 72);
            // 逐段生长：线像被"画"出来，末端带脉冲点（旧版整条淡入，没有过程感）
            final StatsCharts.SparkGrowDrawable sd = new StatsCharts.SparkGrowDrawable(
                    smooth, Theme.termCyan(act), dp(act, 280), hPx);
            sd.setProgress(animate ? 0f : 1f);
            iv.setImageDrawable(sd);
            wrap.addView(iv, new LinearLayout.LayoutParams(-1, hPx));
            if (animate && out != null) {
                out.add(iv, new Runnable() { @Override public void run() {
                    try {
                        android.animation.ValueAnimator va =
                                android.animation.ValueAnimator.ofFloat(0f, 1f);
                        va.setDuration(1500);
                        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f));
                        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                                try { sd.setProgress((Float) a.getAnimatedValue()); }
                                catch (Throwable ignored) {}
                            }
                        });
                        va.start();
                    } catch (Throwable ignored) {}
                } }, 0);
            }
            int[] ht = StatsData.headTailHits(s.signDays, range, 7);
            String trendWord = ht[1] > ht[0] ? Lang.tr("在变好")
                    : ht[1] < ht[0] ? Lang.tr("在变差") : Lang.tr("持平");
            TextView tip = new TextView(act);
            tip.setTextSize(FS_SMALL);
            tip.setTextColor(Theme.termFaint(act));
            tip.setTypeface(Theme.text());
            tip.setText(Lang.tf("7 日滑动平均 · 前 7 天 {0}/7 → 近 7 天 {1}/7（{2}）",
                    ht[0], ht[1], trendWord));
            tip.setPadding(0, dp(act, 4), 0, 0);
            wrap.addView(tip);
        } catch (Throwable t) { swallow(t); }
        return wrap;
    }

    // ══════════════════════ ③ 热力图 ══════════════════════

    private static View heat(Activity act, StatsSnapshot s, boolean animate, Pending out) {
        LinearLayout outer = new LinearLayout(act);
        outer.setOrientation(LinearLayout.VERTICAL);
        try {
            final int ROWS = 7;
            List<String> list = StatsData.dateRange(s.today, 90, true);
            int cols = Math.max(1, list.size() / ROWS);
            // 精确铺满：用对话框可用宽减去星期标签列，再平分给列
            int avail = StatsCharts.contentWidth(act) - dp(act, 20);
            int gap = Math.max(dp(act, 2), avail / 130);
            int cell = Math.max(dp(act, 5), (avail - gap * (cols - 1)) / cols);
            int CY = Theme.termCyan(act);

            String[] WL = {Lang.tr("一"), Lang.tr("二"), Lang.tr("三"), Lang.tr("四"),
                    Lang.tr("五"), Lang.tr("六"), Lang.tr("日")};
            // 逐列点亮：从最旧一列扫到最新（像在被"回放"）
            final java.util.List<StatsCharts.HeatLitDrawable> heatCells =
                    new java.util.ArrayList<StatsCharts.HeatLitDrawable>();
            for (int r = 0; r < ROWS; r++) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView wl = new TextView(act);
                wl.setTextSize(FS_SMALL);
                wl.setTextColor(Theme.termFaint(act));
                wl.setTypeface(Typeface.MONOSPACE);
                wl.setText(WL[r]);
                // 列宽自适应（2026-10-04）：原写死 16dp，而内容是 12.5sp 中文。
                // 用户把系统字体调大 → 文字比格子宽 → 被裁或挤成两行。
                // 现按实际文字测量取宽，上限 28dp 避免占掉热力图。
                int wlW = dp(act, 16);
                try {
                    android.graphics.Paint mp = wl.getPaint();
                    wlW = (int) Math.min(dp(act, 28), Math.ceil(mp.measureText(WL[r])) + dp(act, 4));
                    wlW = Math.max(wlW, dp(act, 12));
                } catch (Throwable ignored) {}
                wl.setGravity(Gravity.CENTER);
                wl.setSingleLine(true);
                row.addView(wl, new LinearLayout.LayoutParams(wlW, cell));
                for (int col = 0; col < cols; col++) {
                    int idx = col * ROWS + r;
                    ImageView iv = new ImageView(act);
                    boolean in = idx < list.size();
                    String d = in ? list.get(idx) : "";
                    boolean on = in && s.signDays.contains(d);
                    boolean isToday = in && s.today.equals(d);
                    StatsCharts.HeatLitDrawable hd =
                            new StatsCharts.HeatLitDrawable(cell, CY, on ? 4 : 0, isToday);
                    hd.setLit(animate ? 0f : 1f);
                    iv.setImageDrawable(hd);
                    heatCells.add(hd);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
                    lp.setMargins(0, 0, gap, gap);
                    row.addView(iv, lp);
                }
                outer.addView(row);
            }

            if (animate && out != null && !heatCells.isEmpty()) {
                final int total = heatCells.size();
                final int nCols = cols;
                out.add(outer, new Runnable() { @Override public void run() {
                    try {
                        android.animation.ValueAnimator va =
                                android.animation.ValueAnimator.ofFloat(0f, nCols);
                        va.setDuration(Math.min(1400, nCols * 40));
                        va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.1f));
                        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                                try {
                                    float done = (Float) a.getAnimatedValue();
                                    for (int i = 0; i < total; i++) {
                                        int colIdx = i / ROWS;
                                        float lit = done - colIdx;
                                        heatCells.get(i).setLit(
                                                lit < 0f ? 0f : (lit > 1f ? 1f : lit));
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        va.start();
                    } catch (Throwable ignored) {}
                } }, 0);
            }

            LinearLayout legend = new LinearLayout(act);
            legend.setOrientation(LinearLayout.HORIZONTAL);
            legend.setGravity(Gravity.CENTER_VERTICAL);
            legend.setPadding(dp(act, 16), dp(act, 8), 0, 0);
            TextView a = new TextView(act);
            a.setTextSize(FS_SMALL); a.setTextColor(Theme.termFaint(act));
            a.setTypeface(Theme.text()); a.setText(Lang.tr("少"));
            legend.addView(a);
            for (int lv = 0; lv <= 4; lv++) {
                ImageView iv = new ImageView(act);
                iv.setImageDrawable(new StatsCharts.HeatDrawable(dp(act, 9), CY, lv, false));
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(act, 9), dp(act, 9));
                lp2.setMargins(dp(act, 3), 0, 0, 0);
                legend.addView(iv, lp2);
            }
            TextView b = new TextView(act);
            b.setTextSize(FS_SMALL); b.setTextColor(Theme.termFaint(act));
            b.setTypeface(Theme.text()); b.setText(Lang.tr("多"));
            b.setPadding(dp(act, 4), 0, 0, 0);
            legend.addView(b);
            legend.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(0, 1, 1f));
            TextView cnt = new TextView(act);
            cnt.setTextSize(FS_SMALL);
            cnt.setTextColor(Theme.termMuted(act));
            cnt.setTypeface(Theme.text());
            cnt.setText(Lang.tf("{0} 天有记录", StatsData.countHits(s.signDays, list)));
            legend.addView(cnt);
            outer.addView(legend);
        } catch (Throwable t) { swallow(t); }
        return outer;
    }

    // ══════════════════════ ④ 多账号 ══════════════════════

    private static View accounts(Activity act, StatsSnapshot s, boolean animate, Pending out) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int i = 0;
        for (StatsSnapshot.Account a : s.accounts) {
          try {   // 逐账号独立（2026-10-04）：一个账号异常不应把其余都带走
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(act, 6), 0, dp(act, 6));
            int col = a.current ? Theme.termCyan(act) : Theme.termMuted(act);

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = new TextView(act);
            nm.setTextSize(Theme.TS_BODY);
            nm.setTextColor(col);
            nm.setTypeface(Typeface.MONOSPACE, a.current ? Typeface.BOLD : Typeface.NORMAL);
            nm.setSingleLine(true);
            nm.setText(a.label + (a.current ? Lang.tr("（当前）") : ""));
            head.addView(nm, new LinearLayout.LayoutParams(-2, -2));
            head.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(dp(act, 8), 1));
            TextView meta = new TextView(act);
            meta.setTextSize(FS_SMALL);
            meta.setTextColor(Theme.termMuted(act));
            meta.setTypeface(Theme.text());
            meta.setSingleLine(true);
            meta.setGravity(Gravity.END);
            meta.setText(Lang.tf("今日 {0}/{1} · 连续 {2} 天", a.todaySigned, a.todayTotal, a.streak));
            head.addView(meta, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(head);

            // 进度条 + 百分比（百分比带扫过动画）
            FrameLayout track = new FrameLayout(act);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Theme.withAlpha(Theme.termCyan(act), 0x1A));
            bg.setCornerRadius(dp(act, 2));
            track.setBackground(bg);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(act, 5));
            tlp.topMargin = dp(act, 6);
            View fill = new View(act);
            android.graphics.drawable.GradientDrawable fg = new android.graphics.drawable.GradientDrawable();
            fg.setColor(col);
            fg.setCornerRadius(dp(act, 2));
            fill.setBackground(fg);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(0, -1);
            track.addView(fill, flp);
            row.addView(track, tlp);
            if (animate && out != null) {
                final int fi = i, fhit = a.hits30, fspan = a.span30;
                out.add(track, new Runnable() { @Override public void run() {
                    setBar(track, fill, fhit, fspan, true, fi * 60);
                } }, 0);
            } else {
                setBar(track, fill, a.hits30, a.span30, false, 0);
            }

            TextView pct = new TextView(act);
            pct.setTextSize(FS_SMALL);
            pct.setTextColor(col);
            pct.setTypeface(Typeface.MONOSPACE);
            int p = a.span30 > 0 ? (int) (a.hits30 * 100f / a.span30) : 0;
            pct.setText(Lang.tf("近 30 天 {0}%", p));
            pct.setPadding(0, dp(act, 3), 0, 0);
            row.addView(pct);
            box.addView(row);
            i++;
          } catch (Throwable _one) { swallow(_one); }
        }
        return box;
    }

    // ══════════════════════ ⑤ 星期分布 ══════════════════════

    private static View weekday(Activity act, StatsSnapshot s, boolean animate, Pending out) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        try {
            int[] cnt = StatsData.weekdayDist(s.signDays);
            FrameLayout wf = new FrameLayout(act);
            ImageView iv = new ImageView(act);
            int hPx = dp(act, 40);
            final StatsCharts.BarsGrowDrawable bg2 =
                    new StatsCharts.BarsGrowDrawable(cnt, Theme.termCyan(act));
            bg2.setGrow(animate ? 0f : 1f);
            iv.setImageDrawable(bg2);
            wf.addView(iv, new FrameLayout.LayoutParams(-1, hPx));
            if (animate && out != null) out.add(wf, new Runnable() { @Override public void run() {
                growUp(bg2, 640, 0);
            } }, 0);
            View base = new View(act);
            base.setBackgroundColor(Theme.withAlpha(Theme.termCyan(act), 0x33));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, dp(act, 1));
            blp.gravity = Gravity.BOTTOM;
            wf.addView(base, blp);
            wrap.addView(wf, new LinearLayout.LayoutParams(-1, hPx));
            String[] WL = {Lang.tr("一"), Lang.tr("二"), Lang.tr("三"), Lang.tr("四"),
                    Lang.tr("五"), Lang.tr("六"), Lang.tr("日")};
            LinearLayout ax = new LinearLayout(act);
            ax.setOrientation(LinearLayout.HORIZONTAL);
            ax.setPadding(0, dp(act, 4), 0, 0);
            for (String w : WL) {
                TextView t = new TextView(act);
                t.setTextSize(FS_SMALL);
                t.setTextColor(Theme.termFaint(act));
                t.setTypeface(Theme.text());
                t.setGravity(Gravity.CENTER);
                t.setText(w);
                ax.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            wrap.addView(ax);
        } catch (Throwable t) { swallow(t); }
        return wrap;
    }

    // ══════════════════════ ⑥ 时段 ══════════════════════

    private static View hours(Activity act, StatsSnapshot s, boolean animate, Pending out) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        try {
            int[] buckets = new int[6];
            long earliest = Long.MAX_VALUE, latest = 0L;
            for (Long t : s.todayTimes) {
                if (t == null || t <= 0L) continue;
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(t);
                int b = c.get(Calendar.HOUR_OF_DAY) / 4;
                if (b >= 0 && b < 6) buckets[b]++;
                if (t < earliest) earliest = t;
                if (t > latest) latest = t;
            }
            if (s.todayTimes.isEmpty()) {
                box.addView(hintText(act, Lang.tr("今天还没有签到记录")));
                return box;
            }
            FrameLayout wf = new FrameLayout(act);
            ImageView iv = new ImageView(act);
            int hPx = dp(act, 44);
            final StatsCharts.BarsGrowDrawable hb =
                    new StatsCharts.BarsGrowDrawable(buckets, Theme.termCyan(act));
            hb.setGrow(animate ? 0f : 1f);
            iv.setImageDrawable(hb);
            wf.addView(iv, new FrameLayout.LayoutParams(-1, hPx));
            if (animate && out != null) out.add(wf, new Runnable() { @Override public void run() {
                growUp(hb, 640, 0);
            } }, 0);
            View base = new View(act);
            base.setBackgroundColor(Theme.withAlpha(Theme.termCyan(act), 0x33));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, dp(act, 1));
            blp.gravity = Gravity.BOTTOM;
            wf.addView(base, blp);
            box.addView(wf, new LinearLayout.LayoutParams(-1, hPx));
            LinearLayout ax = new LinearLayout(act);
            ax.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 6; i++) {
                TextView t = new TextView(act);
                t.setTextSize(FS_SMALL);
                t.setTextColor(Theme.termFaint(act));
                t.setTypeface(Typeface.MONOSPACE);
                t.setGravity(Gravity.CENTER);
                t.setText(String.format(Locale.US, "%02d", i * 4));
                ax.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            box.addView(ax);
            TextView span = new TextView(act);
            span.setTextSize(FS_SMALL);
            span.setTextColor(Theme.termMuted(act));
            span.setTypeface(Theme.text());
            span.setPadding(0, dp(act, 6), 0, 0);
            span.setText(Lang.tf("最早 {0} · 最晚 {1} · 共 {2} 个",
                    SignLogic.hhmmOf(earliest), SignLogic.hhmmOf(latest), s.todayTimes.size()));
            box.addView(span);
        } catch (Throwable t) { swallow(t); }
        return box;
    }

    // ══════════════════════ ⑦ 各目标（卡片化）══════════════════════

    /**
     * 各目标从"一行文字"升级成"状态卡"（2026-10-04）：
     *   左侧状态色点 · 中间名称 · 右侧状态文字 · 底部微进度条
     * 每张卡带错开的淡入上浮，扫过去有"逐条落位"的节奏。
     */
    private static View targetCards(Activity act, StatsSnapshot s, boolean animate, Pending out) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        try {
            if (s.targets.isEmpty()) {
                box.addView(hintText(act, Lang.tr("还没有签到目标")));
                return box;
            }
            int i = 0;
            for (StatsSnapshot.Target t : s.targets) {
              // ── 逐项独立 try（2026-10-04）──
              // 旧写法循环体无保护，只有最外层一个 try：
              // 任何一个目标抛异常 → 循环中断 → **整个「各目标」区空白**（用户实测）。
              try {
                int col;
                String state;
                if (t.frozen) { col = Theme.termMuted(act); state = Lang.tr("已冻结"); }
                else if (t.pending) { col = Theme.termAmber(act); state = Lang.tr("需处理"); }
                else if (t.failStreak >= 2) { col = Theme.termAmber(act); state = Lang.tf("连续失败 {0} 天", t.failStreak); }
                else if (t.signedToday) { col = Theme.termGreen(act); state = Lang.tr("今天已签"); }
                else { col = Theme.termCyan(act); state = Lang.tr("今天待签"); }

                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(act, 10), dp(act, 9), dp(act, 10), dp(act, 9));
                card.setBackground(border(act, Theme.termCard(act), Theme.withAlpha(col, 0x44)));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                clp.setMargins(0, dp(act, 2), 0, dp(act, 5));
                card.setLayoutParams(clp);

                // 左侧状态圆点
                View dot = new View(act);
                android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
                dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dg.setColor(col);
                dg.setSize(dp(act, 8), dp(act, 8));
                dot.setBackground(dg);
                card.addView(dot, new LinearLayout.LayoutParams(dp(act, 8), dp(act, 8)));

                // 名字用「数据流拼装」效果（DecodeTextView）：
                // 未就位时显示随机字符 + 左右抖动，就位瞬间白闪后归位。
                final StatsCharts.DecodeTextView nm =
                        new StatsCharts.DecodeTextView(act, t.name,
                                FS_MID + 1.5f, Theme.termTxt(act), 0xFFFFFFFF);
                nm.setPadding(dp(act, 10), 0, dp(act, 8), 0);
                card.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView st = new TextView(act);
                st.setTextSize(FS_SMALL);
                st.setTextColor(col);
                st.setTypeface(Theme.text());
                st.setSingleLine(true);
                st.setText(state);
                card.addView(st, new LinearLayout.LayoutParams(-2, -2));
                box.addView(card);
                if (animate && out != null) {
                    final View fc = card;
                    final StatsCharts.DecodeTextView fnm = nm;
                    final boolean fromLeft = (i % 2 == 0);
                    out.add(fc, new Runnable() { @Override public void run() {
                        slideInDecode(fc, fnm, fromLeft);
                    } }, 0);
                }
                i++;
              } catch (Throwable _one) {
                // 单个目标失败只丢它自己，不影响其余
                swallow(_one);
              }
            }
        } catch (Throwable t) { swallow(t); }
        return box;
    }

    // ══════════════════════ 零件 ══════════════════════

    private static TextView sectionTitle(Activity act, String s) {
        TextView tv = new TextView(act);
        tv.setTextSize(13);                      // 区块标题比脚注大一档（用户反馈统计页字偏小）
        tv.setTextColor(Theme.termCyan(act));
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setLetterSpacing(0.06f);
        tv.setPadding(0, dp(act, 14), 0, dp(act, 6));
        tv.setText(s);
        return tv;
    }

    private static TextView hintText(Activity act, String s) {
        TextView e = new TextView(act);
        e.setTextSize(FS_SMALL);
        e.setTextColor(Theme.termFaint(act));
        e.setTypeface(Theme.text());
        e.setText(s);
        e.setPadding(0, dp(act, 2), 0, dp(act, 6));
        return e;
    }

    /** 进度条填充：按比例设初始宽度；animate 时从左扫过。 */
    private static void setBar(final FrameLayout track, final View fill,
                               int hit, int span, boolean animate, int delay) {
        final float ratio = span <= 0 ? 0f : Math.max(0.012f, Math.min(1f, (float) hit / span));
        track.post(new Runnable() { @Override public void run() {
            try {
                final int w = track.getWidth();
                if (w <= 0) return;
                if (!animate) {
                    ViewGroup.LayoutParams lp = fill.getLayoutParams();
                    lp.width = (int) (w * ratio);
                    fill.setLayoutParams(lp);
                    return;
                }
                android.animation.ValueAnimator va =
                        android.animation.ValueAnimator.ofFloat(0f, ratio);
                va.setDuration(520);
                va.setStartDelay(delay);
                va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
                va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        try {
                            ViewGroup.LayoutParams lp = fill.getLayoutParams();
                            lp.width = (int) (w * ((Float) a.getAnimatedValue()));
                            fill.setLayoutParams(lp);
                        } catch (Throwable ignored) {}
                    }
                });
                va.start();
            } catch (Throwable ignored) {}
        } });
    }

    /**
     * 目标卡入场：**左右交替滑入 + 名字解码拼装**（2026-10-04 用户要求）。
     *
     * 观感：卡片从左侧或右侧（按索引奇偶交替）带透明滑到位置，
     * 同时名字里的字符还在"乱码 → 就位"地拼装、闪动。
     * 两者节奏对齐（约 620ms），读起来像"目标被逐条解析出来"。
     */
    private static void slideInDecode(final View card, final StatsCharts.DecodeTextView name,
                                      boolean fromLeft) {
        try {
            Context c = card.getContext();
            float dist = Theme.dp(c, 42) * (fromLeft ? -1f : 1f);
            card.setAlpha(0f);
            card.setTranslationX(dist);
            card.animate().alpha(1f).translationX(0f)
                    .setDuration(480)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .start();
            if (name == null) return;
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(900);                    // 比滑动长一点：卡到位后文字还在拼
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    try { name.setProgress((Float) a.getAnimatedValue()); } catch (Throwable ignored) {}
                }
            });
            // 结束后再写一次终态（2026-10-04）：
            // 不靠展开期间的最后一帧，确保 progress 真的到 1.0。
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    try { name.setProgress(1f); } catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    /** 柱子升起（底部对齐生长）。 */
    private static void growUp(final StatsCharts.BarsGrowDrawable d, int duration, int delay) {
        try {
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(duration);
            va.setStartDelay(delay);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    try { d.setGrow((Float) a.getAnimatedValue()); } catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    /** 淡入 + 轻微上浮。 */
    private static void fadeUp(final View v, int duration, int delay) {
        try {
            v.setAlpha(0f);
            v.setTranslationY(Theme.dp(v.getContext(), 6));
            v.animate().alpha(1f).translationY(0f).setDuration(duration)
                    .setStartDelay(delay)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        } catch (Throwable ignored) {}
    }

    /** 纯淡入（热力图逐列用，延迟很小）。 */
    private static void fadeIn(final View v, int duration, int delay) {
        try {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(duration).setStartDelay(delay).start();
        } catch (Throwable ignored) {}
    }
}
