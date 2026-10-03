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
    static View build(Activity act, StatsSnapshot s, boolean animate) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        try {
            box.addView(hero(act, s, animate));
            box.addView(sectionTitle(act, Lang.tr("近 30 天趋势")));
            box.addView(trend(act, s, animate));
            box.addView(sectionTitle(act, Lang.tr("近 90 天打卡")));
            box.addView(heat(act, s, animate));
            box.addView(sectionTitle(act, Lang.tr("各账号对比")));
            box.addView(accounts(act, s, animate));
            box.addView(sectionTitle(act, Lang.tr("星期分布")));
            box.addView(weekday(act, s, animate));
            box.addView(sectionTitle(act, Lang.tr("今日时段")));
            box.addView(hours(act, s, animate));
            box.addView(sectionTitle(act, Lang.tr("各目标")));
            box.addView(targetCards(act, s, animate));
            if (animate) box.setTag(TAG_ANIMATED);
        } catch (Throwable t) { swallow(t); }
        return box;
    }

    /** 该 View 是否已经播过入场动画。 */
    static boolean animated(View v) {
        return v != null && TAG_ANIMATED.equals(v.getTag());
    }

    private static void swallow(Throwable t) {
        try { android.util.Log.w("TGAutoSign", "[统计] " + t); } catch (Throwable ignored) {}
    }

    // ══════════════════════ ① 门面 ══════════════════════

    private static View hero(Activity act, StatsSnapshot s, boolean animate) {
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
        float ratio = s.todayTotal <= 0 ? 0f : (float) s.todaySigned / s.todayTotal;
        iv.setImageDrawable(new StatsCharts.RingStatDrawable(ringPx, ratio, col,
                Theme.withAlpha(col, 0x22)));
        ring.addView(iv, new FrameLayout.LayoutParams(ringPx, ringPx));

        LinearLayout center = new LinearLayout(act);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        int inner = (int) (ringPx * 0.62f);
        TextView num = new TextView(act);
        num.setTextSize(18);
        num.setTextColor(col);
        num.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        num.setSingleLine(true);
        num.setText(s.todaySigned + "/" + s.todayTotal);
        center.addView(num, new LinearLayout.LayoutParams(inner, -2));
        TextView sub = new TextView(act);
        sub.setTextSize(Theme.TS_CAPTION);
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

        if (animate) countUp(num, s.todaySigned, s.todayTotal);
        return card;
    }

    private static View metaRow(Activity act, String label, String value, int col) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(act, 4), 0, dp(act, 4));
        TextView l = new TextView(act);
        l.setTextSize(Theme.TS_CAPTION);
        l.setTextColor(Theme.termMuted(act));
        l.setTypeface(Theme.text());
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(act);
        v.setTextSize(Theme.TS_SECOND);
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
            va.setDuration(350);
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

    private static View trend(Activity act, StatsSnapshot s, boolean animate) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        try {
            List<String> range = StatsData.dateRange(s.today, 30, false);
            float[] daily = StatsData.dailyHits(s.signDays, range);
            float[] smooth = StatsData.movingAvg(daily, 7);
            ImageView iv = new ImageView(act);
            int hPx = dp(act, 72);
            iv.setImageDrawable(new StatsCharts.SparkDrawable(smooth, Theme.termCyan(act),
                    dp(act, 280), hPx));
            wrap.addView(iv, new LinearLayout.LayoutParams(-1, hPx));
            int[] ht = StatsData.headTailHits(s.signDays, range, 7);
            String trendWord = ht[1] > ht[0] ? Lang.tr("在变好")
                    : ht[1] < ht[0] ? Lang.tr("在变差") : Lang.tr("持平");
            TextView tip = new TextView(act);
            tip.setTextSize(Theme.TS_CAPTION);
            tip.setTextColor(Theme.termFaint(act));
            tip.setTypeface(Theme.text());
            tip.setText(Lang.tf("7 日滑动平均 · 前 7 天 {0}/7 → 近 7 天 {1}/7（{2}）",
                    ht[0], ht[1], trendWord));
            tip.setPadding(0, dp(act, 4), 0, 0);
            wrap.addView(tip);
            if (animate) fadeUp(iv, 620, 60);
        } catch (Throwable t) { swallow(t); }
        return wrap;
    }

    // ══════════════════════ ③ 热力图 ══════════════════════

    private static View heat(Activity act, StatsSnapshot s, boolean animate) {
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
            for (int r = 0; r < ROWS; r++) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView wl = new TextView(act);
                wl.setTextSize(Theme.TS_CAPTION);
                wl.setTextColor(Theme.termFaint(act));
                wl.setTypeface(Typeface.MONOSPACE);
                wl.setText(WL[r]);
                row.addView(wl, new LinearLayout.LayoutParams(dp(act, 16), cell));
                for (int col = 0; col < cols; col++) {
                    int idx = col * ROWS + r;
                    ImageView iv = new ImageView(act);
                    boolean in = idx < list.size();
                    String d = in ? list.get(idx) : "";
                    boolean on = in && s.signDays.contains(d);
                    boolean isToday = in && s.today.equals(d);
                    iv.setImageDrawable(new StatsCharts.HeatDrawable(cell, CY, on ? 4 : 0, isToday));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
                    lp.setMargins(0, 0, gap, gap);
                    row.addView(iv, lp);
                    if (animate) fadeIn(iv, 180, col * 8);
                }
                outer.addView(row);
            }

            LinearLayout legend = new LinearLayout(act);
            legend.setOrientation(LinearLayout.HORIZONTAL);
            legend.setGravity(Gravity.CENTER_VERTICAL);
            legend.setPadding(dp(act, 16), dp(act, 8), 0, 0);
            TextView a = new TextView(act);
            a.setTextSize(Theme.TS_CAPTION); a.setTextColor(Theme.termFaint(act));
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
            b.setTextSize(Theme.TS_CAPTION); b.setTextColor(Theme.termFaint(act));
            b.setTypeface(Theme.text()); b.setText(Lang.tr("多"));
            b.setPadding(dp(act, 4), 0, 0, 0);
            legend.addView(b);
            legend.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(0, 1, 1f));
            TextView cnt = new TextView(act);
            cnt.setTextSize(Theme.TS_CAPTION);
            cnt.setTextColor(Theme.termMuted(act));
            cnt.setTypeface(Theme.text());
            cnt.setText(Lang.tf("{0} 天有记录", StatsData.countHits(s.signDays, list)));
            legend.addView(cnt);
            outer.addView(legend);
        } catch (Throwable t) { swallow(t); }
        return outer;
    }

    // ══════════════════════ ④ 多账号 ══════════════════════

    private static View accounts(Activity act, StatsSnapshot s, boolean animate) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int i = 0;
        for (StatsSnapshot.Account a : s.accounts) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(act, 6), 0, dp(act, 6));
            int col = a.current ? Theme.termCyan(act) : Theme.termMuted(act);

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = new TextView(act);
            nm.setTextSize(Theme.TS_SECOND);
            nm.setTextColor(col);
            nm.setTypeface(Typeface.MONOSPACE, a.current ? Typeface.BOLD : Typeface.NORMAL);
            nm.setSingleLine(true);
            nm.setText(a.label + (a.current ? Lang.tr("（当前）") : ""));
            head.addView(nm, new LinearLayout.LayoutParams(-2, -2));
            head.addView(new android.widget.Space(act), new LinearLayout.LayoutParams(dp(act, 8), 1));
            TextView meta = new TextView(act);
            meta.setTextSize(Theme.TS_CAPTION);
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
            setBar(track, fill, a.hits30, a.span30, animate, i * 60);

            TextView pct = new TextView(act);
            pct.setTextSize(Theme.TS_CAPTION);
            pct.setTextColor(col);
            pct.setTypeface(Typeface.MONOSPACE);
            int p = a.span30 > 0 ? (int) (a.hits30 * 100f / a.span30) : 0;
            pct.setText(Lang.tf("近 30 天 {0}%", p));
            pct.setPadding(0, dp(act, 3), 0, 0);
            row.addView(pct);
            box.addView(row);
            i++;
        }
        return box;
    }

    // ══════════════════════ ⑤ 星期分布 ══════════════════════

    private static View weekday(Activity act, StatsSnapshot s, boolean animate) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        try {
            int[] cnt = StatsData.weekdayDist(s.signDays);
            FrameLayout wf = new FrameLayout(act);
            ImageView iv = new ImageView(act);
            int hPx = dp(act, 40);
            iv.setImageDrawable(new StatsCharts.BarsDrawable(cnt, Theme.termCyan(act), true));
            wf.addView(iv, new FrameLayout.LayoutParams(-1, hPx));
            View base = new View(act);
            base.setBackgroundColor(Theme.withAlpha(Theme.termCyan(act), 0x33));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, dp(act, 1));
            blp.gravity = Gravity.BOTTOM;
            wf.addView(base, blp);
            wrap.addView(wf, new LinearLayout.LayoutParams(-1, hPx));
            if (animate) fadeUp(wf, 420, 260);
            String[] WL = {Lang.tr("一"), Lang.tr("二"), Lang.tr("三"), Lang.tr("四"),
                    Lang.tr("五"), Lang.tr("六"), Lang.tr("日")};
            LinearLayout ax = new LinearLayout(act);
            ax.setOrientation(LinearLayout.HORIZONTAL);
            ax.setPadding(0, dp(act, 4), 0, 0);
            for (String w : WL) {
                TextView t = new TextView(act);
                t.setTextSize(Theme.TS_CAPTION);
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

    private static View hours(Activity act, StatsSnapshot s, boolean animate) {
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
            iv.setImageDrawable(new StatsCharts.BarsDrawable(buckets, Theme.termCyan(act), true));
            wf.addView(iv, new FrameLayout.LayoutParams(-1, hPx));
            View base = new View(act);
            base.setBackgroundColor(Theme.withAlpha(Theme.termCyan(act), 0x33));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, dp(act, 1));
            blp.gravity = Gravity.BOTTOM;
            wf.addView(base, blp);
            box.addView(wf, new LinearLayout.LayoutParams(-1, hPx));
            if (animate) fadeUp(wf, 420, 200);
            LinearLayout ax = new LinearLayout(act);
            ax.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 6; i++) {
                TextView t = new TextView(act);
                t.setTextSize(Theme.TS_CAPTION);
                t.setTextColor(Theme.termFaint(act));
                t.setTypeface(Typeface.MONOSPACE);
                t.setGravity(Gravity.CENTER);
                t.setText(String.format(Locale.US, "%02d", i * 4));
                ax.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            box.addView(ax);
            TextView span = new TextView(act);
            span.setTextSize(Theme.TS_CAPTION);
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
    private static View targetCards(Activity act, StatsSnapshot s, boolean animate) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        try {
            if (s.targets.isEmpty()) {
                box.addView(hintText(act, Lang.tr("还没有签到目标")));
                return box;
            }
            int i = 0;
            for (StatsSnapshot.Target t : s.targets) {
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

                TextView nm = new TextView(act);
                nm.setTextSize(Theme.TS_SECOND);
                nm.setTextColor(Theme.termTxt(act));
                nm.setTypeface(Typeface.MONOSPACE);
                nm.setSingleLine(true);
                nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
                nm.setText(t.name);
                nm.setPadding(dp(act, 10), 0, dp(act, 8), 0);
                card.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView st = new TextView(act);
                st.setTextSize(Theme.TS_CAPTION);
                st.setTextColor(col);
                st.setTypeface(Theme.text());
                st.setSingleLine(true);
                st.setText(state);
                card.addView(st, new LinearLayout.LayoutParams(-2, -2));
                box.addView(card);
                if (animate) fadeUp(card, 300, i * 40);
                i++;
            }
        } catch (Throwable t) { swallow(t); }
        return box;
    }

    // ══════════════════════ 零件 ══════════════════════

    private static TextView sectionTitle(Activity act, String s) {
        TextView tv = new TextView(act);
        tv.setTextSize(Theme.TS_CAPTION);
        tv.setTextColor(Theme.termCyan(act));
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setLetterSpacing(0.06f);
        tv.setPadding(0, dp(act, 14), 0, dp(act, 6));
        tv.setText(s);
        return tv;
    }

    private static TextView hintText(Activity act, String s) {
        TextView e = new TextView(act);
        e.setTextSize(Theme.TS_CAPTION);
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
