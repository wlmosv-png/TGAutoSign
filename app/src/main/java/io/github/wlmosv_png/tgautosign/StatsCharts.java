package io.github.wlmosv_png.tgautosign;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 统计页专用图表（2026-10-04 分层重构）。
 *
 * 原本这四个 Drawable 挤在 Icons.java 里 —— 但 Icons 的定位是"矢量图标集"
 * （24 网格线性图标），而这里是**数据可视化**：环形进度、趋势折线、
 * 分布柱、热力格。两者放在一起会同时污染两边的阅读。
 *
 * 拆出后：
 *   · Icons.java   只放图标（约 -190 行）
 *   · 本文件        只放图表
 * 都用 Canvas 程序绘制，不依赖任何 res 资源（本模块打包链的既有约束）。
 */
final class StatsCharts {
    private StatsCharts() {}

    /**
     * 对话框内容区可用宽度（px）。
     *
     * 与 TGAutoSignCore.showDialog 里的限制保持同一口径：
     *   min(屏宽*0.92, 400dp) - 卡片左右内边距。
     * 凡是"按宽度算格子尺寸"的地方都用它 ——
     * 直接拿屏幕宽算必然溢出（热力图踩过这个坑，右列被裁）。
     */
    static int contentWidth(android.content.Context c) {
        int w = 0;
        try { w = c.getResources().getDisplayMetrics().widthPixels; } catch (Throwable ignored) {}
        if (w <= 0) w = 1080;
        int target = Math.min((int) (w * 0.92f), Theme.dp(c, 400));
        return Math.max(0, target - Theme.dp(c, 28));
    }

    /** 给颜色换 alpha（本地副本，避免依赖 Icons 的包内可见性）。 */
    static int withA(int c, int a) {
        return ((c & 0x00FFFFFF) | ((a & 0xFF) << 24));
    }

    static final class RingStatDrawable extends Drawable {
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval;
        private final float stroke;
        private final float ratio;
        RingStatDrawable(int sizePx, float ratio, int col, int baseCol) {
            float pad = sizePx * 0.10f;
            this.stroke = sizePx * 0.115f;
            this.ratio = Math.max(0f, Math.min(1f, ratio));
            oval = new RectF(pad, pad, sizePx - pad, sizePx - pad);
            base.setStyle(Paint.Style.STROKE);
            base.setStrokeWidth(stroke);
            base.setStrokeCap(Paint.Cap.ROUND);
            base.setColor(baseCol);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(col);
        }
        @Override public void draw(Canvas cv) {
            try {
                // 底环：从 -90° 起一整圈
                cv.drawArc(oval, -90f, 360f, false, base);
                if (ratio > 0f) {
                    // 进度弧：从 12 点方向顺时针，最小 3° 保证"只签了一个"也看得见
                    float sweep = Math.max(3f, 360f * ratio);
                    cv.drawArc(oval, -90f, sweep, false, arc);
                }
            } catch (Throwable ignored) {}
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 趋势折线（2026-10-03）：把"每天的完成率"连成一条曲线。
     *
     * 这是三个数字给不了的东西 —— 数字只说"现在"，折线说"在变好还是变差"。
     * 面积用线性渐变从色到透明，视觉上是"填补过的曲线"而不是干巴巴的线。
     */
    static final class SparkDrawable extends Drawable {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] vals;     // 0..1
        private final int col;
        SparkDrawable(float[] values, int col, int wPx, int hPx) {
            this.vals = values;
            this.col = col;
            line.setStyle(Paint.Style.STROKE);
            // 线细一点（2026-10-03）：上一版 3.5% 高度，在大图上像一条粗带
            line.setStrokeWidth(Math.max(1.8f, hPx * 0.022f));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(col);
            fill.setStyle(Paint.Style.FILL);
        }
        @Override public void draw(Canvas cv) {
            try {
                int w = getBounds().width(), h = getBounds().height();
                if (w <= 0 || h <= 0 || vals == null || vals.length == 0) return;
                float padY = h * 0.14f;
                float usable = h - padY * 2f;
                int n = vals.length;
                float dx = n > 1 ? (float) w / (n - 1) : w;
                Path p = new Path();
                float firstX = 0f, firstY = 0f;
                for (int i = 0; i < n; i++) {
                    float v = Math.max(0f, Math.min(1f, vals[i]));
                    float x = i * dx;
                    float y = padY + (1f - v) * usable;
                    if (i == 0) { p.moveTo(x, y); firstX = x; firstY = y; }
                    else p.lineTo(x, y);
                }
                // 面积：把曲线首尾接到基线
                Path area = new Path(p);
                area.lineTo((n - 1) * dx, h);
                area.lineTo(firstX, h);
                area.close();
                // ── 填充改成"只要底部一点点"（2026-10-03）──
                // 上一版从线到图底整片填充（0x66 起），在深色底上像一整块实心色，
                // 完全盖过了折线本身，看着"不像趋势图像色块"（用户截图）。
                // 现在渐变起点也贴近线（0x4D），且到 45% 高度就完全透明 ——
                // 只在曲线下方留一层薄光晕，视线仍落在线上。
                android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                        0, 0, 0, h,
                        new int[]{withA(col, 0x4D), withA(col, 0x14), withA(col, 0x00)},
                        new float[]{0f, 0.45f, 1f},
                        android.graphics.Shader.TileMode.CLAMP);
                fill.setShader(lg);
                cv.drawPath(area, fill);
                cv.drawPath(p, line);
            } catch (Throwable ignored) {}
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 迷你柱状（2026-10-03）：时段分布 / 星期分布用。
     * 柱子按值高低给不同透明度，高点更亮 —— 比同色柱子更能看出"峰"。
     */
    static final class BarsDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] vals;
        private final int col;
        private final int max;
        /** true = 细胶囊样式（统计页用）；false = 旧粗柱。 */
        private final boolean slim;
        BarsDrawable(int[] values, int col) { this(values, col, false); }
        BarsDrawable(int[] values, int col, boolean slim) {
            this.vals = values;
            this.col = col;
            this.slim = slim;
            int m = 1;
            if (values != null) for (int v : values) if (v > m) m = v;
            this.max = m;
        }
        @Override public void draw(Canvas cv) {
            try {
                int w = getBounds().width(), h = getBounds().height();
                if (w <= 0 || h <= 0 || vals == null || vals.length == 0) return;
                int n = vals.length;
                // 柱太宽太满（2026-10-03）：上一版 3.5% 间隙、柱子几乎占满一格，
                // 七根粗柱像七块砖。现在加宽间隙、收窄柱体，露出呼吸感。
                // slim 模式（统计页）柱宽只占约 42%，圆角拉满 = 胶囊观感。
                float slot = (float) w / n;
                // slim：柱宽只占 26%（上一版 42% 太胖），
                // 圆角另算 —— 见下面 r 的计算
                float bw = slim ? slot * 0.26f : slot * 0.62f;
                float gap = slot - bw;
                // 圆角只占柱宽 22%：是"圆角矩形柱"，不是胶囊。
                // 上一版用 bw*0.5 = 圆角等于半宽 → 直接变药丸（用户截图）。
                float r = slim ? Math.min(bw * 0.22f, h * 0.16f)
                               : Math.min(bw * 0.32f, h * 0.10f);
                for (int i = 0; i < n; i++) {
                    float x = i * (bw + gap) + gap / 2f;
                    if (vals[i] <= 0) {
                        // 空桶：一条底线，表示"这个时段没有"
                        p.setColor(withA(col, 0x33));
                        RectF t = new RectF(x, h - Math.max(2f, h * 0.04f), x + bw, h);
                        cv.drawRoundRect(t, r, r, p);
                        continue;
                    }
                    float ratio = vals[i] / (float) max;
                    float bh = Math.max(h * 0.10f, h * ratio);
                    // 越高的柱子越不透明，峰一眼可见
                    int alpha = 0x77 + (int) (ratio * 0x88);
                    p.setColor(withA(col, Math.min(0xFF, alpha)));
                    RectF t = new RectF(x, h - bh, x + bw, h);
                    cv.drawRoundRect(t, r, r, p);
                }
            } catch (Throwable ignored) {}
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 热力图小格（2026-10-03）：统计页用。
     *
     * 与日历的 DayCellDrawable 区别：那个表达"某天签没签 + 是不是今天"，
     * 带脉冲、带日期文字，格大；这个只表达"某天的完成度"，格小、无文字，
     * 一个屏能铺 90 天。颜色按 level 分 5 档（0=空，4=满）。
     */
    static final class HeatDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;
        private final int col;
        private final int level;     // 0..4
        private final boolean today;
        HeatDrawable(int sizePx, int col, int level, boolean today) {
            this.sizePx = sizePx;
            this.col = col;
            this.level = Math.max(0, Math.min(4, level));
            this.today = today;
        }
        @Override public void draw(Canvas cv) {
            try {
                float pad = sizePx * 0.09f;
                float r = sizePx * 0.20f;
                RectF box = new RectF(pad, pad, sizePx - pad, sizePx - pad);
                p.setStyle(Paint.Style.FILL);
                if (level == 0) {
                    // 空格：只描边，表示"这天没签"
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1f, sizePx * 0.06f));
                    p.setColor(withA(col, 0x33));
                } else {
                    // 有签：alpha 随 level 递增（0x55 -> 0xFF），一眼看出深浅
                    int alpha = 0x55 + (int) ((level - 1) / 3f * 0xAA);
                    p.setColor(withA(col, Math.min(0xFF, alpha)));
                }
                cv.drawRoundRect(box, r, r, p);
                if (today) {
                    // 今天：加一圈对比色描边，便于定位
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1f, sizePx * 0.09f));
                    p.setColor(withA(col, 0xFF));
                    cv.drawRoundRect(new RectF(0, 0, sizePx, sizePx), r, r, p);
                }
            } catch (Throwable ignored) {}
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
