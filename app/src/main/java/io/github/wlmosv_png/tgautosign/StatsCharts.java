package io.github.wlmosv_png.tgautosign;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

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

    // ══════════════════════════════════════════════════════════════
    //  主题化动效构件（2026-10-04）
    //  模块是终端/赛博风（青绿品红琥珀 + 深底 + 等宽字），
    //  因此动效也用同一套语汇：**扫描线、十字准星、数据流、辉光**，
    //  而不是通用的"淡入淡出"。
    //  全部 Canvas 程序绘制，零资源依赖。
    // ══════════════════════════════════════════════════════════════

    /**
     * 环形进度（可动画版）：外圈带**扫描角标**与**端点辉光**。
     *
     * 与旧版区别：旧版只是一段静止圆弧；现在
     *   · progress 可逐帧推进（扫过动画）；
     *   · 弧的末端画一个亮点 + 外发光 —— 像扫描头停在当前位置；
     *   · 起始处画一个小十字准星，呼应终端风。
     */
    static final class RingScanDrawable extends Drawable {
        private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval;
        private final float size, cx, cy, r, stroke;
        private final int col;
        /** 分格数（= 今日总目标数）。底环按它画成虚线，1..36 以外不分格。 */
        private final int ticks;
        private final Path circle = new Path();
        private float progress;     // 0..1

        RingScanDrawable(int sizePx, int col, int baseCol) {
            this(sizePx, col, baseCol, 0);
        }

        RingScanDrawable(int sizePx, int col, int baseCol, int tickCount) {
            this.col = col;
            this.ticks = (tickCount >= 1 && tickCount <= 36) ? tickCount : 0;
            this.size = sizePx;
            this.cx = sizePx / 2f;
            this.cy = sizePx / 2f;
            this.stroke = sizePx * 0.093f;             // 细一点，上一版 0.105 偏傻
            this.r = sizePx / 2f - stroke * 2.1f;      // 留出扫描头的外扰空间
            oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            circle.addArc(oval, -90f, 360f);           // 起点 12 点，与进度弧对齐

            base.setStyle(Paint.Style.STROKE);
            base.setStrokeWidth(stroke * 0.72f);
            base.setStrokeCap(Paint.Cap.BUTT);
            base.setColor(baseCol);
            // 底环分格：占空比固定，数目少时缺口略窄更像「格」
            if (this.ticks > 0) {
                float c = (float) (2 * Math.PI * r);
                float seg = c / this.ticks;
                float gapR = this.ticks <= 6 ? 0.22f : 0.30f;
                base.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{seg * (1f - gapR), seg * gapR}, 0f));
            }

            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(col);

            tail.setStyle(Paint.Style.STROKE);
            tail.setStrokeCap(Paint.Cap.ROUND);
            tail.setColor(col);

            glow.setStyle(Paint.Style.FILL);

            mark.setStyle(Paint.Style.STROKE);
            mark.setStrokeWidth(Math.max(1.2f, sizePx * 0.016f));
            mark.setStrokeCap(Paint.Cap.ROUND);
            mark.setColor(withA(col, 0x99));
        }

        void setProgress(float p) { this.progress = Math.max(0f, Math.min(1f, p)); invalidateSelf(); }

        @Override public void draw(Canvas cv) {
            try {
                // ① 底环（分格虚线）—— 让「3/12」看得出是十二格里亮了三格
                cv.drawPath(circle, base);

                float sweep = 360f * progress;

                // ② 彗尾：沿进度弧往回扫 26°，逐段降 alpha + 收窄
                //    —— 上一版只有一个硬边圆斑，像糊在一起的三个圈（用户截图）。
                if (sweep > 8f) {
                    final int N = 9;
                    float span = 26f;
                    for (int i = 0; i < N; i++) {
                        float f = i / (float) N;                 // 0 = 最靠近扫描头
                        float segLen = span / N;
                        float st = -90f + sweep - (f + 1f) * segLen + segLen;
                        tail.setAlpha((int) (0xCE * (1f - f) * (1f - f)));   // 平方衰减，更像彗尾
                        tail.setStrokeWidth(stroke * (1f - f * 0.30f));
                        cv.drawArc(oval, st - segLen, segLen * 1.25f, false, tail);
                    }
                }

                // ③ 主弧
                if (sweep > 0.5f) cv.drawArc(oval, -90f, sweep, false, arc);

                // ④ 起点准星（比上一版长一点，否则被弧盖住看不见）
                float tx = cx, ty = cy - r;
                float k = size * 0.052f;
                cv.drawLine(tx - k, ty, tx - k * 0.34f, ty, mark);
                cv.drawLine(tx + k * 0.34f, ty, tx + k, ty, mark);
                cv.drawLine(tx, ty - k, tx, ty - k * 0.34f, mark);
                cv.drawLine(tx, ty + k * 0.34f, tx, ty + k, mark);

                // ⑤ 扫描头：**半径渐变幻光** + 实心点
                //    之前用三个同心硬边圆叠加，边缘硬、比弧还宽，
                //    看起来就是一团方块状的糊斑。现在用 RadialGradient 真正的光。
                if (progress > 0.001f) {
                    double a0 = Math.toRadians(-90f + sweep);
                    float ex = (float) (cx + r * Math.cos(a0));
                    float ey = (float) (cy + r * Math.sin(a0));
                    float gr = stroke * 3.0f;
                    try {
                        android.graphics.RadialGradient rg = new android.graphics.RadialGradient(
                                ex, ey, gr,
                                new int[]{withA(col, 0xB0), withA(col, 0x48), withA(col, 0x00)},
                                new float[]{0f, 0.34f, 1f},
                                android.graphics.Shader.TileMode.CLAMP);
                        glow.setShader(rg);
                        cv.drawCircle(ex, ey, gr, glow);
                        glow.setShader(null);
                    } catch (Throwable ignored) {}
                    glow.setColor(col);
                    cv.drawCircle(ex, ey, stroke * 0.40f, glow);
                }
            } catch (Throwable ignored) {}
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 趋势折线（可动画版）：**逐段生长** + 末端数据点脉冲。
     *
     * 用 PathMeasure 按进度取部分路径 —— 线条像被"画"出来，
     * 比整条淡入更像扫描仪在绘图。末端点带一圈呼吸光环。
     */
    static final class SparkGrowDrawable extends Drawable {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] vals;
        private final int col;
        private final Path full = new Path();
        private Path seg;
        private float progress = 0f;
        SparkGrowDrawable(float[] values, int col, int wPx, int hPx) {
            this.vals = values;
            this.col = col;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1.8f, hPx * 0.022f));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(col);
            fill.setStyle(Paint.Style.FILL);
            dot.setStyle(Paint.Style.FILL);
            dot.setColor(col);
            halo.setStyle(Paint.Style.FILL);
            halo.setColor(withA(col, 0x55));
            build(wPx, hPx);
        }
        private void build(int w, int h) {
            full.reset();
            if (vals == null || vals.length == 0 || w <= 0 || h <= 0) return;
            float padY = h * 0.14f, usable = h - padY * 2f;
            int n = vals.length;
            float dx = n > 1 ? (float) w / (n - 1) : w;
            for (int i = 0; i < n; i++) {
                float v = Math.max(0f, Math.min(1f, vals[i]));
                float x = i * dx, y = padY + (1f - v) * usable;
                if (i == 0) full.moveTo(x, y); else full.lineTo(x, y);
            }
        }
        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            try {
                android.graphics.PathMeasure pm = new android.graphics.PathMeasure(full, false);
                seg = new Path();
                pm.getSegment(0f, pm.getLength() * progress, seg, true);
            } catch (Throwable t) { seg = full; }
            invalidateSelf();
        }
        /** 已构建路径的尺寸（用于懒重建）。 */
        private int builtW = -1, builtH = -1;

        @Override public void draw(Canvas cv) {
            try {
                int w = getBounds().width(), h = getBounds().height();
                if (w <= 0 || h <= 0 || vals == null || vals.length == 0) return;
                // 构造时传的是 dp(280)，而 ImageView 是 match_parent（实际约 330dp）——
                // 路径只铺满左边一截，右侧空白。
                // 现在第一次绘制时按**真实 bounds** 重建，并重算当前进度切片。
                if (w != builtW || h != builtH) {
                    builtW = w; builtH = h;
                    build(w, h);
                    setProgress(progress);   // seg 基于新路径重算，避免用旧尺寸的残片
                }
                Path use = (seg != null) ? seg : full;
                // 面积（渐变淡），随进度一致生长
                Path area = new Path(use);
                area.lineTo(measureEndX(w), h);
                area.lineTo(0, h);
                area.close();
                android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                        0, 0, 0, h,
                        new int[]{withA(col, 0x4D), withA(col, 0x14), withA(col, 0x00)},
                        new float[]{0f, 0.45f, 1f},
                        android.graphics.Shader.TileMode.CLAMP);
                fill.setShader(lg);
                cv.drawPath(area, fill);
                cv.drawPath(use, line);
                // 末端脉冲点
                float[] pos = new float[2];
                try {
                    android.graphics.PathMeasure pm = new android.graphics.PathMeasure(full, false);
                    pm.getPosTan(pm.getLength() * progress, pos, null);
                } catch (Throwable ignored) {}
                if (pos[0] > 0f || pos[1] > 0f) {
                    cv.drawCircle(pos[0], pos[1], h * 0.075f, halo);
                    cv.drawCircle(pos[0], pos[1], h * 0.032f, dot);
                }
            } catch (Throwable ignored) {}
        }
        private float measureEndX(int w) {
            try {
                android.graphics.PathMeasure pm = new android.graphics.PathMeasure(full, false);
                float[] p = new float[2];
                pm.getPosTan(pm.getLength() * progress, p, null);
                return p[0];
            } catch (Throwable t) { return w; }
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 柱状（可动画版）：**逐根升起**（底部对齐生长）+ 顶部亮点。
     */
    static final class BarsGrowDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] vals;
        private final int col;
        private final int max;
        private float grow = 1f;      // 0..1 整体生长
        BarsGrowDrawable(int[] values, int col) {
            this.vals = values;
            this.col = col;
            int m = 1;
            if (values != null) for (int v : values) if (v > m) m = v;
            this.max = m;
            tip.setStyle(Paint.Style.FILL);
        }
        void setGrow(float g) { grow = Math.max(0f, Math.min(1f, g)); invalidateSelf(); }
        @Override public void draw(Canvas cv) {
            try {
                int w = getBounds().width(), h = getBounds().height();
                if (w <= 0 || h <= 0 || vals == null || vals.length == 0) return;
                int n = vals.length;
                float slot = (float) w / n;
                float bw = slot * 0.26f;
                float gap = slot - bw;
                float r = Math.min(bw * 0.22f, h * 0.16f);
                for (int i = 0; i < n; i++) {
                    float x = i * (bw + gap) + gap / 2f;
                    if (vals[i] <= 0) {
                        p.setColor(withA(col, 0x33));
                        cv.drawRoundRect(new RectF(x, h - Math.max(2f, h * 0.04f), x + bw, h), r, r, p);
                        continue;
                    }
                    float ratio = vals[i] / (float) max;
                    float bh = Math.max(h * 0.10f, h * ratio * grow);
                    int alpha = 0x77 + (int) (ratio * 0x88);
                    p.setColor(withA(col, Math.min(0xFF, alpha)));
                    float top = h - bh;
                    cv.drawRoundRect(new RectF(x, top, x + bw, h), r, r, p);
                    // 顶部亮点：只在超过 1 格时画，避免空柱也发光
                    if (ratio > 0f && grow > 0.35f) {
                        tip.setColor(withA(col, (int) (0xCC * grow)));
                        cv.drawCircle(x + bw / 2f, top + bw * 0.30f, bw * 0.22f, tip);
                    }
                }
            } catch (Throwable ignored) {}
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 热力格（可点亮版）：单格可带"刚点亮"的高亮光环。
     */
    static final class HeatLitDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx, col, level;
        private final boolean today;
        private float lit = 0f;    // 0..1 点亮程度
        HeatLitDrawable(int sizePx, int col, int level, boolean today) {
            this.sizePx = sizePx;
            this.col = col;
            this.level = Math.max(0, Math.min(4, level));
            this.today = today;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Math.max(1f, sizePx * 0.10f));
        }
        void setLit(float v) { lit = Math.max(0f, Math.min(1f, v)); invalidateSelf(); }
        @Override public void draw(Canvas cv) {
            try {
                float pad = sizePx * 0.09f, r = sizePx * 0.20f;
                RectF box = new RectF(pad, pad, sizePx - pad, sizePx - pad);
                p.setStyle(Paint.Style.FILL);
                if (level == 0) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1f, sizePx * 0.06f));
                    p.setColor(withA(col, lit > 0f ? (int) (0x33 + 0x55 * lit) : 0x33));
                } else {
                    int alpha = 0x55 + (int) ((level - 1) / 3f * 0xAA);
                    if (lit > 0f) alpha = Math.min(0xFF, alpha + (int) (0x66 * lit));
                    p.setColor(withA(col, Math.min(0xFF, alpha)));
                }
                cv.drawRoundRect(box, r, r, p);
                // 点亮瞬间的光环
                if (lit > 0.02f && lit < 1f) {
                    ring.setColor(withA(col, (int) (0xFF * (1f - lit) * 0.8f)));
                    cv.drawRoundRect(box, r, r, ring);
                }
                if (today) {
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

    /**
     * 扫描条：一条水平细线 + 头尾渐隐，从 0 扫到满宽。
     * 用于"卡片被扫描一遍"的入场感（终端风的核心语汇）。
     */
    static final class ScanLineDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int col;
        private float pos = -1f;     // -1 = 未开始
        ScanLineDrawable(int col) {
            this.col = col;
            p.setStrokeWidth(1.6f);
        }
        void setPos(float v) { pos = v; invalidateSelf(); }
        @Override public void draw(Canvas cv) {
            if (pos < 0f) return;
            try {
                int w = getBounds().width(), h = getBounds().height();
                if (w <= 0) return;
                float x = pos * w;
                android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                        x - w * 0.18f, 0, x + w * 0.02f, 0,
                        new int[]{withA(col, 0x00), withA(col, 0xDD)},
                        null, android.graphics.Shader.TileMode.CLAMP);
                p.setShader(lg);
                p.setColor(col);
                cv.drawLine(Math.max(0, x - w * 0.18f), 0, Math.min(w, x), 0, p);
            } catch (Throwable ignored) {}
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 「数据流拼装」文字视图（2026-10-04）。
     *
     * 用户要的效果：名字像被字符流"拼"出来 —— 左右错位、闪动、然后归位。
     * 这是终端/赛博风最标志性的观感（矩阵雨 / 解码）。
     *
     * 实现：自定义 View，用 Canvas 逐字绘制。
     *   · 每个字有独立的"就位时间" t_i = i / n（从左到右）;
     *   · 未就位时：显示随机字符 + 随机水平偏移（幅度随时间收敛）+ 高亮色;
     *   · 就位瞬间：一次白色闪（flash），随后回到正常色;
     *   · 全部就位后：整体一次轻微"合拢"（scale 1.03 → 1）。
     *
     * 不用 TextView.setText 逐帧改（会产生大量布局/重绘），
     * 而是在 onDraw 里 drawText —— 一次绘制、零布局。
     */
    static final class DecodeTextView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String text;
        private final char[] glyphs;          // 逐字拆开（代理对安全）
        private final float[] jitter;         // 每个字当前的横向偏移
        private final float[] arrive;         // 每个字就位后的残留闪动
        private final java.util.Random rnd = new java.util.Random();
        private final int normalCol, flashCol;
        private final float textSize;
        private float textPx = 12f;          // sp 换算后的像素值（绘制/测量都用它）
        private float progress = 1f;          // 0..1 整体进度
        private float scale = 1f;
        private boolean done;

        DecodeTextView(Context c, String text, float textSize, int normalCol, int flashCol) {
            super(c);
            this.text = text == null ? "" : text;
            this.textSize = textSize;
            this.normalCol = normalCol;
            this.flashCol = flashCol;
            java.util.List<Character> cs = new java.util.ArrayList<Character>();
            for (int i = 0; i < this.text.length(); ) {
                int cp = this.text.codePointAt(i);
                cs.add(Character.valueOf((char) cp));
                i += Character.charCount(cp);
            }
            glyphs = new char[cs.size()];
            for (int i = 0; i < glyphs.length; i++) glyphs[i] = cs.get(i).charValue();
            jitter = new float[glyphs.length];
            arrive = new float[glyphs.length];
            p.setTypeface(Typeface.MONOSPACE);
            // ⚠️ Paint.setTextSize 要的是**像素**，而调用方传进来的是 sp
            //    （Theme.TS_SECOND = 12 这类）。直接传会得到 12px ≈ 4dp，
            //    字小到看不清（用户截图实测）。
            //    这里用 scaledDensity 做 sp→px，与 TextView 的默认行为一致。
            float px = textSize;
            try {
                px = textSize * c.getResources().getDisplayMetrics().scaledDensity;
            } catch (Throwable ignored) {}
            this.textPx = px;
            p.setTextSize(px);
            p.setFakeBoldText(true);
        }

        /** 0..1 整体进度（外部逐帧驱动）。 */
        void setProgress(float v) {
            progress = Math.max(0f, Math.min(1f, v));
            int n = glyphs.length;
            // 每个字按位置稍晚就位：最右的字最后拼好
            for (int i = 0; i < n; i++) {
                float ti = n <= 1 ? 0f : (i / (float) n) * 0.55f;
                float local = (progress - ti) / Math.max(0.001f, 1f - ti);
                local = Math.max(0f, Math.min(1f, local));
                // 未就位 → 偏移随 local 收敛；就位后保留一点闪
                float amp = (1f - local);
                jitter[i] = amp * (rnd.nextFloat() * 2f - 1f) * textPx * 0.42f;
                arrive[i] = local >= 1f ? Math.max(0f, arrive[i] - 0.12f) : 1f;
            }
            // 全部就位后来一次轻微合拢
            if (progress >= 0.999f && !done) { done = true; scale = 1.03f; }
            if (done && scale > 1f) scale = Math.max(1f, scale - 0.006f);
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = (int) p.measureText(text) + 4;
            int h = (int) (textPx * 1.45f);
            setMeasuredDimension(Math.max(1, w), Math.max(1, h));
        }

        @Override protected void onDraw(Canvas cv) {
            try {
                int n = glyphs.length;
                if (n == 0) return;
                cv.save();
                cv.scale(scale, scale, 0, getHeight() / 2f);
                float x = 0f;
                for (int i = 0; i < n; i++) {
                    String g = String.valueOf(glyphs[i]);
                    float dx = jitter[i];
                    // 未就位：偶尔替换成随机字符（看不出原字，像解码中）
                    if (Math.abs(dx) > 0.6f && rnd.nextInt(3) == 0) {
                        g = String.valueOf((char) ('a' + rnd.nextInt(26)));
                    }
                    if (arrive[i] > 0.05f) {
                        // 就位闪：向白色插值
                        int col = blend(flashCol, normalCol, 1f - arrive[i]);
                        p.setColor(col);
                    } else if (Math.abs(dx) > 0.6f) {
                        p.setColor(normalCol);
                        p.setAlpha(140 + rnd.nextInt(80));
                    } else {
                        p.setColor(normalCol);
                        p.setAlpha(255);
                    }
                    cv.drawText(g, x + dx, getHeight() * 0.72f, p);
                    x += p.measureText(g);
                }
                cv.restore();
            } catch (Throwable ignored) {}
        }

        private static int blend(int a, int b, float r) {
            r = Math.max(0f, Math.min(1f, r));
            int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            return 0xFF000000
                    | ((int) (ar + (br - ar) * r) << 16)
                    | ((int) (ag + (bg - ag) * r) << 8)
                    | (int) (ab + (bb - ab) * r);
        }
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
