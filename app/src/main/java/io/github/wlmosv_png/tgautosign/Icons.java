package io.github.wlmosv_png.tgautosign;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 纯代码矢量图标集（不引用任何 res 资源）。
 *
 * 设计网格 24x24，描边 1.6，圆头 + 圆角连接，全部无填充（"透明线条"观感）；
 * 颜色一律由调用方传入，取自 {@link Theme} 色板，因此深浅色主题自动跟随。
 *
 * 为什么不用 res/drawable/*.xml：本模块的打包链是「继承上个正式包的资源表、只替换 dex」，
 * 而设备上没有可用的 aapt2（现存副本都是 x86/bionic 二进制，在 arm64 上 Exec format error），
 * 新增任何资源都无法本地编译。Canvas + Path 是等价的矢量引擎，且天然支持任意尺寸与染色。
 */
final class Icons {
    private Icons() {}

    /** 默认线宽（24 网格单位）。调细只减小视觉重量，不改图形几何。 */
    static final float DEF_STROKE = 1.35f;

    /** 已知图标名（用于区分"该画图标"与"就写这个字"） */
    private static final java.util.Set<String> NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "list", "repeat", "rocket", "doc", "sliders", "dots", "plus", "trash", "copy", "globe",
            "layers", "receipt", "upload", "download", "flask", "pulse", "refresh", "book",
            "chevron-d", "chevron-u", "check", "clock", "warn", "target", "down", "bulb", "keyboard",
            "group", "bot", "clean", "megaphone", "chevron-r", "pencil", "pause",
            "hourglass", "gap", "key", "save", "x", "calendar", "bolt", "bell",
            "boltfill", "ring",
            // 今日状态（2026-10-01）：日历摘要行用，与 DayCell 的六种样式解耦
            "today-done", "today-wait", "today-miss", "today-idle"));

    static boolean has(String name) {
        return name != null && NAMES.contains(name);
    }

    /**
     * 环形进度（2026-10-03）：统计页门面。
     *
     * 用 Canvas 画一段圆弧表达完成度 —— 比"11/11 三个大数字并排"强在：
     *   · 一眼看出"满没满"（圆环闭合 = 完成）；
     *   · 中心能放大号数字，视觉重心明确；
     *   · 底环留白表达"还剩多少"，条状进度做不到这种暗示。
     * 颜色由调用方给，深浅色自动跟随；不用任何图片资源。
     */

    // ── 图形容器：stroke 走描边，fill 走实心（圆点） ──────────────────────────
    private static final class G {
        final Path stroke = new Path();
        final Path fill = new Path();
    }

    /** 折线/多边形 */
    private static void poly(Path p, float[] pts, boolean close) {
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i + 1 < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        if (close) p.close();
    }

    /** 圆弧：forceMove 为 true 时另起一段（不连线） */
    private static void arc(Path p, float cx, float cy, float r, float a0, float sweep, boolean forceMove) {
        RectF o = new RectF(cx - r, cy - r, cx + r, cy + r);
        if (forceMove) p.addArc(o, a0, sweep);
        else p.arcTo(o, a0, sweep, false);
    }

    /** 整圆 */
    private static void circle(Path p, float cx, float cy, float r) {
        p.addCircle(cx, cy, r, Path.Direction.CW);
    }

    /** 椭圆（横长 rx、纵长 ry） */
    private static void ellipse(Path p, float cx, float cy, float rx, float ry) {
        p.addOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW);
    }

    /** 实心圆点 */
    private static void dot(Path p, float cx, float cy, float r) {
        p.addCircle(cx, cy, r, Path.Direction.CW);
    }

    /** 在 tip 处、指向 dirDeg 的箭头两条臂 */
    private static void arrow(Path p, float tipX, float tipY, float dirDeg, float size, float spread) {
        for (int s = -1; s <= 1; s += 2) {
            double a = Math.toRadians(dirDeg + 180 + s * spread);
            p.moveTo(tipX, tipY);
            p.lineTo((float) (tipX + size * Math.cos(a)), (float) (tipY + size * Math.sin(a)));
        }
    }

    /** 圆周上 angle 度的点 */
    private static float onCircleX(float cx, float r, float angleDeg) {
        return (float) (cx + r * Math.cos(Math.toRadians(angleDeg)));
    }

    private static float onCircleY(float cy, float r, float angleDeg) {
        return (float) (cy + r * Math.sin(Math.toRadians(angleDeg)));
    }

    /** 图标定义；未知名字返回 null */
    private static G spec(String name) {
        if (name == null) return null;
        G g = new G();
        Path s = g.stroke;
        Path f = g.fill;
        switch (name) {
            case "list":
                poly(s, new float[]{9.8f, 7f, 19f, 7f}, false);
                poly(s, new float[]{9.8f, 12f, 19f, 12f}, false);
                poly(s, new float[]{9.8f, 17f, 15.5f, 17f}, false);
                dot(f, 5.2f, 7f, 1.15f); dot(f, 5.2f, 12f, 1.15f); dot(f, 5.2f, 17f, 1.15f);
                break;
            case "repeat":
                // 上下两条反向箭头 = 往返/重试，语义与 refresh（环形）区分开
                poly(s, new float[]{5.5f, 8.5f, 17f, 8.5f}, false);
                arrow(s, 17f, 8.5f, 0f, 3.2f, 32f);
                poly(s, new float[]{18.5f, 15.5f, 7f, 15.5f}, false);
                arrow(s, 7f, 15.5f, 180f, 3.2f, 32f);
                break;
            case "rocket":
            case "bolt":
                // 闪电：小尺寸下最清晰的"立即执行"符号
                poly(s, new float[]{15.5f, 2.5f, 5.5f, 14f, 10f, 14f, 8.5f, 21.5f, 18.5f, 10f, 14f, 10f}, true);
                break;
            case "boltfill":
                // 实心闪电：用于「已签」状态，比描边版更醒目、动态下更抓眼
                poly(f, new float[]{16f, 2f, 5f, 13.5f, 10.2f, 13.5f, 8f, 22f, 19f, 10.2f, 13.8f, 10.2f}, true);
                break;
            case "ring":
                // 进度环：仅画底环，扫过的弧由 RingDrawable 逐帧叠加
                circle(s, 12f, 12f, 8.2f);
                break;
            case "doc":
                poly(s, new float[]{7f, 3f, 14.5f, 3f, 19f, 7.5f, 19f, 21f, 7f, 21f}, true);
                poly(s, new float[]{14.5f, 3f, 14.5f, 7.5f, 19f, 7.5f}, false);
                break;
            case "sliders":
                poly(s, new float[]{4.5f, 8.5f, 19.5f, 8.5f}, false);
                circle(s, 14.5f, 8.5f, 2.1f);
                poly(s, new float[]{4.5f, 15.5f, 19.5f, 15.5f}, false);
                circle(s, 9.5f, 15.5f, 2.1f);
                break;
            case "dots":
                dot(f, 5.5f, 12f, 1.7f); dot(f, 12f, 12f, 1.7f); dot(f, 18.5f, 12f, 1.7f);
                break;
            case "plus":
                poly(s, new float[]{12f, 5f, 12f, 19f}, false);
                poly(s, new float[]{5f, 12f, 19f, 12f}, false);
                break;
            case "trash":
                poly(s, new float[]{4f, 6.5f, 20f, 6.5f}, false);
                poly(s, new float[]{9.5f, 6.5f, 9.5f, 3.5f, 14.5f, 3.5f, 14.5f, 6.5f}, false);
                poly(s, new float[]{6.5f, 6.5f, 8f, 20.5f, 16f, 20.5f, 17.5f, 6.5f}, false);
                break;
            case "copy":
                poly(s, new float[]{4.5f, 4.5f, 14.5f, 4.5f, 14.5f, 14.5f, 4.5f, 14.5f}, true);
                poly(s, new float[]{9.5f, 9.5f, 19.5f, 9.5f, 19.5f, 19.5f, 9.5f, 19.5f}, true);
                break;
            case "globe":
                // 只保留圆 + 一条经线椭圆；加赤道线会在 20dp 下与椭圆挤成一团
                circle(s, 12f, 12f, 8.5f);
                ellipse(s, 12f, 12f, 3.8f, 8.5f);
                break;
            case "layers":
                poly(s, new float[]{4f, 8.5f, 12f, 4.5f, 20f, 8.5f, 12f, 12.5f}, true);
                poly(s, new float[]{4f, 13.5f, 12f, 17.5f, 20f, 13.5f}, false);
                poly(s, new float[]{4f, 17.5f, 12f, 21.5f, 20f, 17.5f}, false);
                break;
            case "receipt":
                poly(s, new float[]{6f, 3f, 18f, 3f, 18f, 21.5f, 16.5f, 19.8f, 15f, 21.5f, 13.5f, 19.8f,
                        12f, 21.5f, 10.5f, 19.8f, 9f, 21.5f, 7.5f, 19.8f, 6f, 21.5f}, true);
                poly(s, new float[]{9f, 8f, 15f, 8f}, false);
                poly(s, new float[]{9f, 12f, 15f, 12f}, false);
                break;
            case "upload":
                poly(s, new float[]{5f, 20.5f, 19f, 20.5f}, false);
                poly(s, new float[]{12f, 16f, 12f, 4.5f}, false);
                poly(s, new float[]{8f, 8.5f, 12f, 4.5f, 16f, 8.5f}, false);
                break;
            case "download":
            case "down":
                poly(s, new float[]{5f, 20.5f, 19f, 20.5f}, false);
                poly(s, new float[]{12f, 4.5f, 12f, 16f}, false);
                poly(s, new float[]{8f, 12f, 12f, 16f, 16f, 12f}, false);
                break;
            case "flask":
                poly(s, new float[]{10f, 3f, 10f, 8.5f, 4.5f, 19.5f, 19.5f, 19.5f, 14f, 8.5f, 14f, 3f}, false);
                poly(s, new float[]{8.5f, 3f, 15.5f, 3f}, false);
                break;
            case "pulse":
                poly(s, new float[]{3.5f, 12f, 7f, 12f, 9f, 6.5f, 12.5f, 17.5f, 14.5f, 12f, 20.5f, 12f}, false);
                break;
            case "refresh":
                arc(s, 12f, 12f, 6.4f, -150f, 270f, true);
                arrow(s, onCircleX(12f, 6.4f, 120f), onCircleY(12f, 6.4f, 120f), 210f, 3.2f, 32f);
                break;
            case "book":
                poly(s, new float[]{12f, 6.5f, 12f, 20f}, false);
                poly(s, new float[]{12f, 6.5f, 6f, 4.5f, 4f, 7f, 4f, 18.5f, 11.5f, 20.5f}, false);
                poly(s, new float[]{12f, 6.5f, 18f, 4.5f, 20f, 7f, 20f, 18.5f, 12.5f, 20.5f}, false);
                break;
            case "chevron-d":
                poly(s, new float[]{5.5f, 9f, 12f, 15.5f, 18.5f, 9f}, false);
                break;
            case "chevron-u":
                poly(s, new float[]{5.5f, 15f, 12f, 8.5f, 18.5f, 15f}, false);
                break;
            case "chevron-r":
                poly(s, new float[]{9.5f, 5.5f, 15.5f, 12f, 9.5f, 18.5f}, false);
                break;
            case "check":
                poly(s, new float[]{5f, 12.5f, 10f, 17.5f, 19f, 6.5f}, false);
                break;
            case "bell":
                // 铃铛：钟体（上宽下宽、颈窄）+ 摆锤。
                // 纯几何、无文字，24 网格与既有图标同线宽；
                // 加它是为了修【通知】分区标题图标一直空着的 bug ——
                // 该处代码早就写着 "bell"，但这里从没实现过。
                poly(s, new float[]{6.6f, 16.6f, 7.6f, 15.4f, 7.6f, 10.4f}, false);
                arc(s, 12f, 10.4f, 4.4f, 180f, 180f, false);          // 顶部圆穹
                poly(s, new float[]{16.4f, 10.4f, 16.4f, 15.4f, 17.4f, 16.6f}, false);
                poly(s, new float[]{6.6f, 16.6f, 17.4f, 16.6f}, false); // 铃口横线
                poly(s, new float[]{12f, 4.4f, 12f, 6.2f}, false);      // 顶钮
                arc(s, 12f, 18.4f, 1.6f, 0f, 180f, true);               // 摆锤
                break;
            case "clock":
                circle(s, 12f, 12f, 8.6f);
                poly(s, new float[]{12f, 12f, 12f, 6.6f}, false);
                poly(s, new float[]{12f, 12f, 16.2f, 14.2f}, false);
                break;
            case "warn":
                poly(s, new float[]{12f, 3.5f, 21f, 19.5f, 3f, 19.5f}, true);
                poly(s, new float[]{12f, 9.5f, 12f, 14.5f}, false);
                dot(f, 12f, 17f, 1.1f);
                break;
            case "target":
                circle(s, 12f, 12f, 8.6f);
                circle(s, 12f, 12f, 4f);
                dot(f, 12f, 12f, 1.6f);
                break;
            case "bulb":
                circle(s, 12f, 9.5f, 5f);
                poly(s, new float[]{9.7f, 14.5f, 9.7f, 17f, 14.3f, 17f, 14.3f, 14.5f}, false);
                poly(s, new float[]{10.4f, 19.5f, 13.6f, 19.5f}, false);
                break;
            case "keyboard":
                poly(s, new float[]{3.5f, 7f, 20.5f, 7f, 20.5f, 17f, 3.5f, 17f}, true);
                dot(f, 6.5f, 10.5f, 0.95f); dot(f, 10f, 10.5f, 0.95f);
                dot(f, 13.5f, 10.5f, 0.95f); dot(f, 17f, 10.5f, 0.95f);
                poly(s, new float[]{8f, 14f, 16f, 14f}, false);
                break;
            case "group":
                // 双人：左人完整、右人半掩，20dp 下仍能读出"群/多人"
                circle(s, 9f, 8.4f, 3.3f);
                arc(s, 9f, 20f, 5.6f, 180f, 180f, true);
                circle(s, 16.4f, 9.4f, 2.6f);
                arc(s, 16.4f, 20f, 4.6f, 200f, 140f, true);
                break;
            case "bot":
                // 机器人：方头 + 两根天线 + 两眼 + 天线球，20dp 下清晰可辨
                poly(s, new float[]{7.5f, 10.5f, 7.5f, 17.5f, 16.5f, 17.5f, 16.5f, 10.5f}, true);
                poly(s, new float[]{9f, 7f, 9f, 4.5f}, false);
                poly(s, new float[]{15f, 7f, 15f, 4.5f}, false);
                dot(f, 9f, 4f, 1.1f);
                dot(f, 15f, 4f, 1.1f);
                dot(f, 10.4f, 13.5f, 1.1f);
                dot(f, 13.6f, 13.5f, 1.1f);
                break;
            case "clean":
                // 扫帚：斜柄 + 梯形帚头 + 两道帚丝
                poly(s, new float[]{17.5f, 3f, 20.5f, 6f, 11f, 15.5f, 8f, 12.5f}, true);
                poly(s, new float[]{9.5f, 14f, 13.5f, 18f, 7.5f, 21f, 3.5f, 17f}, true);
                poly(s, new float[]{7.5f, 17.5f, 5.5f, 19.5f}, false);
                poly(s, new float[]{10f, 19f, 8f, 21f}, false);
                break;
            case "pencil":
                poly(s, new float[]{4.5f, 19.5f, 8.2f, 18.4f, 19.2f, 7.4f, 16.6f, 4.8f, 5.6f, 15.8f}, true);
                poly(s, new float[]{16.6f, 4.8f, 19.2f, 7.4f}, false);
                break;
            case "pause":
                poly(s, new float[]{9.5f, 5f, 9.5f, 19f}, false);
                poly(s, new float[]{14.5f, 5f, 14.5f, 19f}, false);
                break;
            case "hourglass":
                poly(s, new float[]{7f, 4f, 17f, 4f, 12f, 11.5f, 17f, 19.5f, 7f, 19.5f, 12f, 11.5f}, true);
                poly(s, new float[]{6f, 4f, 18f, 4f}, false);
                poly(s, new float[]{6f, 19.5f, 18f, 19.5f}, false);
                break;
            case "gap":
                // 标尺：一条基线 + 两道刻度，直观表达"间隔"
                poly(s, new float[]{3.5f, 12f, 20.5f, 12f}, false);
                poly(s, new float[]{8f, 7.5f, 8f, 16.5f}, false);
                poly(s, new float[]{16f, 7.5f, 16f, 16.5f}, false);
                break;
            case "key":
                circle(s, 15.8f, 8.2f, 4.2f);
                poly(s, new float[]{12.8f, 11.2f, 4f, 20f}, false);
                poly(s, new float[]{6.2f, 17.8f, 8.2f, 19.8f}, false);
                poly(s, new float[]{8.6f, 15.4f, 10.6f, 17.4f}, false);
                break;
            case "save":
                poly(s, new float[]{4.5f, 3.5f, 16.5f, 3.5f, 20.5f, 7.5f, 20.5f, 20.5f, 3.5f, 20.5f}, true);
                poly(s, new float[]{8f, 3.5f, 8f, 9.5f, 16f, 9.5f, 16f, 3.5f}, false);
                poly(s, new float[]{8f, 15f, 16f, 15f, 16f, 20.5f}, false);
                break;
            case "x":
                poly(s, new float[]{6.5f, 6.5f, 17.5f, 17.5f}, false);
                poly(s, new float[]{17.5f, 6.5f, 6.5f, 17.5f}, false);
                break;
            case "calendar":
                poly(s, new float[]{4f, 6f, 20f, 6f, 20f, 20.5f, 4f, 20.5f}, true);
                poly(s, new float[]{4f, 10.5f, 20f, 10.5f}, false);
                poly(s, new float[]{8.5f, 3f, 8.5f, 7.5f}, false);
                poly(s, new float[]{15.5f, 3f, 15.5f, 7.5f}, false);
                dot(f, 12f, 15.8f, 1.75f);
                break;
            // ── 今日状态四态（2026-10-01）──
            // 设计约定：统一 24 网格、与既有图标同线宽，**纯几何、不含文字/emoji**，
            // 因此在日间/夜间与全部 6 种日历格样式下都不会与配色打架。
            case "today-done":
                // 实心圆 + 对勾：完成态用"满"表达，对勾比"打勾方框"更轻
                circle(s, 12f, 12f, 8.4f);
                poly(s, new float[]{7.8f, 12.2f, 10.9f, 15.3f, 16.4f, 8.6f}, false);
                break;
            case "today-wait":
                // 断续圆环（4 段弧 = "进行中"）+ 中心点：等待态要"未满"的感觉。
                // 第一段 forceMove=true 起笔，其余 false 续画，避免产生额外 moveto。
                arc(s, 12f, 12f, 8.4f, -80f, 70f, true);
                arc(s, 12f, 12f, 8.4f, 20f, 70f, false);
                arc(s, 12f, 12f, 8.4f, 120f, 70f, false);
                arc(s, 12f, 12f, 8.4f, 220f, 70f, false);
                dot(f, 12f, 12f, 1.5f);
                break;
            case "today-miss":
                // 圆环 + 中心短横：错过态要"落空"但不吓人（不用叉，叉太像报错）
                circle(s, 12f, 12f, 8.4f);
                poly(s, new float[]{8.6f, 12f, 15.4f, 12f}, false);
                break;
            case "today-idle":
                // 圆环 + 两短竖（暂停符）：不参与态，与「暂停」语义一致
                circle(s, 12f, 12f, 8.4f);
                poly(s, new float[]{10.2f, 8.6f, 10.2f, 15.4f}, false);
                poly(s, new float[]{13.8f, 8.6f, 13.8f, 15.4f}, false);
                break;
            case "megaphone":
                poly(s, new float[]{4f, 10f, 8f, 10f, 17f, 5f, 17f, 17f, 8f, 12f}, true);
                poly(s, new float[]{4f, 10f, 4f, 14f, 8f, 14f}, false);
                poly(s, new float[]{9f, 14f, 10.5f, 19.5f}, false);
                break;
            default:
                return null;
        }
        return g;
    }

    /** 取图标；未知名或尺寸非法返回 null（调用方据此回退到文字/emoji） */
    static Drawable d(Context c, String name, float dpSize, int color) {
        return d(c, name, dpSize, color, DEF_STROKE);
    }

    /** 取图标并指定线宽（24 网格单位） */
    static Drawable d(Context c, String name, float dpSize, int color, float stroke) {
        if (c == null || dpSize <= 0) return null;
        G g = spec(name);
        if (g == null) return null;
        int px = Theme.dp(c, dpSize);
        return new IconDrawable(g, px, color, stroke);
    }

    /**
     * 进度环：底环 + 按 sweepDeg 逐帧绘制的进度弧。
     *
     * 为什么单独一个类：IconDrawable 的图形是静态 Path，无法表达"扫过角度"；
     * 而「文本指令已发出」这个状态本身就是个进行中的过程，用转动填充的环最贴切。
     * 每帧只重算一条 arc，开销可忽略。
     */
    static final class RingDrawable extends Drawable {
        private final Paint basePaint;
        private final Paint arcPaint;
        private final Paint dotPaint;
        private final int sizePx;
        private final float unit;
        private final RectF oval;
        private float sweepDeg = 0f;
        private int alpha = 255;
        private final int color;

        RingDrawable(int sizePx, int color, float strokeW) {
            this.sizePx = sizePx;
            this.color = color;
            this.unit = sizePx / 24f;
            float r = 8.2f * unit;
            float cx = sizePx / 2f, cy = sizePx / 2f;
            oval = new RectF(cx - r, cy - r, cx + r, cy + r);

            basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            basePaint.setStyle(Paint.Style.STROKE);
            basePaint.setStrokeWidth(Math.max(1f, strokeW * unit));
            basePaint.setStrokeCap(Paint.Cap.ROUND);
            basePaint.setColor(color);
            basePaint.setAlpha(64);          // 底环压暗，形成"未完成"观感

            arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setStrokeWidth(Math.max(1f, (strokeW + 0.5f) * unit));   // 进度弧略粗
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
            arcPaint.setColor(color);

            dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(color);
        }

        /** 0..360；由动画逐帧驱动 */
        void setSweep(float deg) {
            this.sweepDeg = deg;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.drawArc(oval, -90f, 360f, false, basePaint);          // 底环
            if (sweepDeg > 0.5f) {
                canvas.drawArc(oval, -90f, sweepDeg, false, arcPaint);   // 进度弧，从 12 点顺时针
                // 弧头小圆点：让"正在前进"更有方向感
                double rad = Math.toRadians(-90f + sweepDeg);
                float r = oval.width() / 2f;
                float hx = oval.centerX() + (float) (Math.cos(rad) * r);
                float hy = oval.centerY() + (float) (Math.sin(rad) * r);
                canvas.drawCircle(hx, hy, Math.max(1.3f, 1.5f * unit), dotPaint);
            }
            canvas.restoreToCount(save);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }
        @Override public void setAlpha(int a) {
            this.alpha = a;
            arcPaint.setAlpha(a);
            dotPaint.setAlpha(a);
            basePaint.setAlpha((int) (64 * (a / 255f)));
            invalidateSelf();
        }
        @Override public void setColorFilter(ColorFilter cf) {
            basePaint.setColorFilter(cf); arcPaint.setColorFilter(cf); dotPaint.setColorFilter(cf);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 进度环工厂；尺寸与 Icons.d 保持同一套 dp 语义 */
    static RingDrawable ring(Context c, float dpSize, int color, float stroke) {
        if (c == null || dpSize <= 0) return null;
        return new RingDrawable(Theme.dp(c, dpSize), color, stroke);
    }

    /**
     * 环形涟漪：N 个同心环依次从中心向外扩散并淡出。
     *
     * 用途：文本指令「已签」状态的动态图标。
     * 为什么不用转圈填充：匀速扫满 360° 是个机械的"进度条"语义，
     * 而文本指令的动作本质是"我把信号发出去、bot 那边回来了" —— 一发一收，
     * 用水波状的向外扩散更贴切，节奏也更柔和不抢眼。
     *
     * 实现：进度 p (0..1) 驱动 3 个错相的环，每个环的半径随 p 增大、alpha 随 p 衰减。
     */
    static final class RippleDrawable extends Drawable {
        private final Paint ringPaint;
        private final Paint corePaint;
        private final int sizePx;
        private final float strokePx;
        private final int color;
        private float progress = 0f;
        private int alpha = 255;

        private static final int RINGS = 3;

        RippleDrawable(int sizePx, int color, float strokeW) {
            this.sizePx = sizePx;
            this.color = color;
            this.strokePx = Math.max(1f, strokeW * (sizePx / 24f));

            ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(strokePx);
            ringPaint.setColor(color);

            corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            corePaint.setStyle(Paint.Style.FILL);
            corePaint.setColor(color);
        }

        /** 0..1，由动画逐帧驱动 */
        void setProgress(float p) {
            this.progress = p;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            float cx = sizePx / 2f, cy = sizePx / 2f;
            float maxR = sizePx * 0.44f;          // 留出边距，环不贴边

            // 中心核：始终可见，亮度随进度轻微脉动
            float coreR = Math.max(1.2f, sizePx * 0.055f);
            corePaint.setAlpha((int) (alpha * (0.55f + 0.45f * (1f - progress))));
            canvas.drawCircle(cx, cy, coreR, corePaint);

            // 同心环：错相扩散
            for (int i = 0; i < RINGS; i++) {
                float ph = progress - i * (1.0f / RINGS);      // 错相
                if (ph < 0f) ph += 1f;                          // 回绕
                if (ph > 1f) continue;                          // 超出周期的先不画
                float r = maxR * ph;
                if (r < 1f) continue;
                // alpha 随半径增大衰减：内圈亮、外圈淡
                float fade = (1f - ph) * (1f - ph);             // 二次衰减，尾部更柔和
                ringPaint.setAlpha((int) (alpha * 0.85f * fade));
                ringPaint.setStrokeWidth(strokePx * (1f - 0.3f * ph));   // 外圈略细
                canvas.drawCircle(cx, cy, r, ringPaint);
            }
            canvas.restoreToCount(save);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }
        @Override public void setAlpha(int a) { this.alpha = a; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) {
            ringPaint.setColorFilter(cf); corePaint.setColorFilter(cf); invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 环形涟漪工厂 */
    static RippleDrawable ripple(Context c, float dpSize, int color, float stroke) {
        if (c == null || dpSize <= 0) return null;
        return new RippleDrawable(Theme.dp(c, dpSize), color, stroke);
    }

    /**
     * 日历格：科技感小方块，两种状态（已签 / 未签）。
     *
     * 视觉结构（自下而上）：
     *   ① 底板：圆角矩形，已签=实底+发光描边；未签=极暗底 + 虚线感描边
     *   ② 顶部高亮条：一条短横线贴在格子上沿，已签亮、未签隐 —— 像仪表的刻度灯
     *   ③ 脉冲环：仅「今天」绘制，由外部逐帧驱动 radius 表达呼吸
     *
     * 之所以画成一个 Drawable 而不是用 GradientDrawable + 多个 View：
     * 每格要同时表达 圆角/描边/顶条/脉冲 四层，用 View 叠加会产生 4 倍视图数，
     * 日历共 14 格，列表滚动时开销明显。合成到一个 Canvas 里只有 1 个视图。
     */
    /**
     * 该样式的日期文字是否需要靠上排。
     *
     * 圆点与柱条把图形放在格子下半部，若文字仍居中就会压在图形上
     * （暗色下白字压绿点、亮色下黑字压浅点，都很难看）。这两种样式返回 true，
     * 调用方把 TextView 的 gravity 改成 TOP_CENTER 即可。
     */
    static boolean cellTextTop(int style) {
        return style == CELL_DOT || style == CELL_BAR;
    }

    /** 日历格样式。数值即设置里存的编号，勿随意调整顺序。 */
    static final int CELL_GRID  = 0;   // 方角 + 斜纹 + 四角括号
    static final int CELL_GLOW  = 1;   // 圆角 + 发光
    static final int CELL_WAVE  = 2;   // 硬方波 + 顶部条
    static final int CELL_BEVEL = 3;   // 切角八边形
    static final int CELL_DOT   = 4;   // 圆点 + 外环
    static final int CELL_BAR   = 5;   // 竖向柱条

    static final class DayCellDrawable extends Drawable {
        private final Paint p;
        private final Path clip;
        private final int sizePx;
        private final float unit;
        private final boolean signed, isToday, dark;
        private final int accent, muted, style;
        private final RectF box;
        private float pulse = 0f;

        DayCellDrawable(int size, boolean signed, boolean isToday, boolean dark,
                        int accent, int muted, int style) {
            this.sizePx = size;
            this.signed = signed;
            this.isToday = isToday;
            this.dark = dark;
            this.accent = accent;
            this.muted = muted;
            this.style = style;
            this.unit = size / 24f;
            float pad = 1.0f * unit;
            box = new RectF(pad, pad, size - pad, size - pad);
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            clip = new Path();
        }

        void setPulse(float v) { this.pulse = v; invalidateSelf(); }

        @Override public void draw(Canvas c) {
            switch (style) {
                case CELL_GLOW:  drawGlow(c);  break;
                case CELL_WAVE:  drawWave(c);  break;
                case CELL_BEVEL: drawBevel(c); break;
                case CELL_DOT:   drawDot(c);   break;
                case CELL_BAR:   drawBar(c);   break;
                default:         drawGrid(c);  break;
            }
        }

        // ── A 方角 + 斜纹 + 四角括号 ──
        private void drawGrid(Canvas c) {
            float r = 2.0f * unit;
            p.setStyle(Paint.Style.FILL);
            p.setColor(signed ? blend(dark ? 0xFF0A1F18 : 0xFFFFFFFF, accent, 0.30f)
                              : (dark ? 0xFF0B1119 : 0xFFF4F7FB));
            c.drawRoundRect(box, r, r, p);

            if (signed) {
                int s = c.save();
                clip.reset();
                clip.addRoundRect(box, r, r, Path.Direction.CW);
                c.clipPath(clip);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(0.8f, 0.85f * unit));
                p.setColor(withA(accent, dark ? 0x50 : 0x3A));
                float step = 4.2f * unit, diag = box.width() + box.height();
                for (float x = box.left - diag; x < box.right + diag; x += step) {
                    c.drawLine(x, box.bottom, x + diag, box.top, p);
                }
                c.restoreToCount(s);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, (signed ? 1.25f : 1.0f) * unit));
            p.setColor(signed ? withA(accent, dark ? 0xCC : 0xB0)
                              : withA(muted, dark ? 0x30 : 0x48));
            c.drawRoundRect(box, r, r, p);

            if (isToday) {
                p.setStrokeWidth(Math.max(1.1f, 1.3f * unit));
                p.setColor(accent);
                p.setAlpha((int) (255 - pulse * 130));
                float grow = pulse * 0.9f;
                float off = -grow * 2.2f * unit;
                float L = 3.6f * unit + grow * 1.4f * unit;
                float l = box.left + off, rr = box.right - off;
                float tp = box.top + off, bt = box.bottom - off;
                c.drawLine(l, tp, l + L, tp, p);   c.drawLine(l, tp, l, tp + L, p);
                c.drawLine(rr - L, tp, rr, tp, p); c.drawLine(rr, tp, rr, tp + L, p);
                c.drawLine(l, bt - L, l, bt, p);   c.drawLine(l, bt, l + L, bt, p);
                c.drawLine(rr - L, bt, rr, bt, p); c.drawLine(rr, bt - L, rr, bt, p);
            }
        }

        // ── B 圆角 + 发光 ──
        private void drawGlow(Canvas c) {
            float r = 5.5f * unit;
            if (signed) {
                p.setStyle(Paint.Style.STROKE);
                for (int i = 4; i >= 1; i--) {
                    p.setStrokeWidth(i * 1.6f * unit);
                    p.setColor(withA(accent, dark ? (14 - i * 2) : (10 - i)));
                    RectF e = new RectF(box);
                    e.inset(-i * 0.9f * unit, -i * 0.9f * unit);
                    c.drawRoundRect(e, r + i, r + i, p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(signed ? withA(accent, dark ? 0x40 : 0x2E)
                              : (dark ? 0xFF101826 : 0xFFEDF1F8));
            c.drawRoundRect(box, r, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.3f * unit);
            p.setColor(signed ? accent : withA(muted, dark ? 0x44 : 0x55));
            c.drawRoundRect(box, r, r, p);
            if (isToday && pulse > 0f) {
                p.setColor(withA(accent, (int) (150 * (1 - pulse))));
                p.setStrokeWidth(1.3f * unit);
                float g = 1f + pulse * 0.4f;
                float hw = box.width() / 2 * g;
                RectF e = new RectF(box.centerX() - hw, box.centerY() - hw,
                                    box.centerX() + hw, box.centerY() + hw);
                c.drawRoundRect(e, r * g, r * g, p);
            }
        }

        // ── C 硬方波 + 顶部条 ──
        private void drawWave(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(signed ? withA(accent, dark ? 0x30 : 0x22)
                              : (dark ? 0xFF0B1119 : 0xFFF0F4FA));
            c.drawRect(box, p);
            if (signed) {
                p.setColor(accent);
                float bh = box.height() * 0.20f;
                c.drawRect(box.left, box.top, box.right, box.top + bh, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.0f * unit);
            p.setColor(signed ? accent : withA(muted, dark ? 0x40 : 0x50));
            c.drawRect(box, p);
            if (isToday && pulse > 0f) {
                p.setColor(withA(accent, (int) (140 * (1 - pulse))));
                p.setStrokeWidth(1.2f * unit);
                float g = pulse * 1.6f * unit;
                c.drawRect(box.left - g, box.top - g, box.right + g, box.bottom + g, p);
            }
        }

        // ── D 切角八边形 ──
        private void drawBevel(Canvas c) {
            float k = 4.0f * unit;
            clip.reset();
            clip.moveTo(box.left + k, box.top);
            clip.lineTo(box.right - k, box.top);
            clip.lineTo(box.right, box.top + k);
            clip.lineTo(box.right, box.bottom - k);
            clip.lineTo(box.right - k, box.bottom);
            clip.lineTo(box.left + k, box.bottom);
            clip.lineTo(box.left, box.bottom - k);
            clip.lineTo(box.left, box.top + k);
            clip.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(signed ? withA(accent, dark ? 0x36 : 0x26)
                              : (dark ? 0xFF0B1119 : 0xFFF0F4FA));
            c.drawPath(clip, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.3f * unit);
            p.setColor(signed ? accent : withA(muted, dark ? 0x44 : 0x55));
            c.drawPath(clip, p);
            if (signed) {
                p.setStrokeWidth(1.9f * unit);
                c.drawLine(box.left + k, box.top, box.right - k, box.top, p);
            }
            if (isToday && pulse > 0f) {
                p.setColor(withA(accent, (int) (150 * (1 - pulse))));
                p.setStrokeWidth(1.3f * unit);
                float g = pulse * 1.8f * unit;
                clip.reset();
                clip.moveTo(box.left + k - g, box.top - g);
                clip.lineTo(box.right - k + g, box.top - g);
                clip.lineTo(box.right + g, box.top + k - g);
                clip.lineTo(box.right + g, box.bottom - k + g);
                clip.lineTo(box.right - k + g, box.bottom + g);
                clip.lineTo(box.left + k - g, box.bottom + g);
                clip.lineTo(box.left - g, box.bottom - k + g);
                clip.lineTo(box.left - g, box.top + k - g);
                clip.close();
                c.drawPath(clip, p);
            }
        }

        // ── E 圆点 + 外环 ──
        private void drawDot(Canvas c) {
            // 版式：日期文字占上半，圆点压在下半且显著缩小 —— 两者各占一头，互不粘连。
            // 2026-09-30 二次调整：初版圆点半径 0.30 仍偏大，外环几乎顶到格子上沿，
            // 截图里表现为"数字贴着圆点"，观感拥挤。现缩到 0.19 并把圆心下移。
            float cx = box.centerX();
            float cy = box.top + box.height() * 0.735f;
            float R = Math.min(box.width(), box.height()) * 0.19f;

            if (signed) {
                // 实心点
                p.setStyle(Paint.Style.FILL);
                p.setColor(accent);
                c.drawCircle(cx, cy, R * 0.68f, p);
                // 细外环
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.15f * unit);
                p.setColor(withA(accent, dark ? 0x99 : 0x7A));
                c.drawCircle(cx, cy, R, p);
            } else {
                // 未签：空心小圈，一眼区分
                p.setStyle(Paint.Style.FILL);
                p.setColor(dark ? 0xFF1A2433 : 0xFFDCE3EE);
                c.drawCircle(cx, cy, R * 0.42f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.0f * unit);
                p.setColor(withA(muted, dark ? 0x66 : 0x77));
                c.drawCircle(cx, cy, R * 0.88f, p);
            }

            if (isToday && pulse > 0f) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f * unit);
                p.setColor(withA(accent, (int) (150 * (1 - pulse))));
                c.drawCircle(cx, cy, R * (1.0f + pulse * 0.5f), p);
            }
        }

        // ── F 竖向柱条 ──
        private void drawBar(Canvas c) {
            // 改为「贴底横条」：文字在上、进度条在下，互不遮挡
            float bw = box.width() * 0.62f;
            float cx = box.centerX();
            float barH = Math.max(1.8f, 1.8f * unit);
            float bottomY = box.bottom - 2.0f * unit;
            float r = barH / 2f;

            // 轨道
            p.setStyle(Paint.Style.FILL);
            p.setColor(withA(muted, dark ? 0x38 : 0x48));
            c.drawRoundRect(new RectF(cx - bw / 2, bottomY - barH, cx + bw / 2, bottomY), r, r, p);

            // 进度：已签满、未签短
            float fw = bw * (signed ? 1.0f : 0.32f);
            p.setColor(signed ? accent : withA(muted, dark ? 0x66 : 0x77));
            c.drawRoundRect(new RectF(cx - bw / 2, bottomY - barH, cx - bw / 2 + fw, bottomY), r, r, p);

            if (isToday && pulse > 0f) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f * unit);
                p.setColor(withA(accent, (int) (150 * (1 - pulse))));
                float g = pulse * 1.6f * unit;
                c.drawRoundRect(new RectF(cx - bw / 2 - g, bottomY - barH - g,
                                          cx + bw / 2 + g, bottomY + g), r, r, p);
            }
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 两色混合，t=0 取 a，t=1 取 b（含 alpha） */
    private static int blend(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (((int) (aa + (ba - aa) * t)) << 24)
             | (((int) (ar + (br - ar) * t)) << 16)
             | (((int) (ag + (bg - ag) * t)) << 8)
             | ((int) (ab + (bb - ab) * t));
    }

    private static int withA(int c, int a) {
        return ((a & 0xFF) << 24) | (c & 0x00FFFFFF);
    }

    /** 单色描边图标，可即时染色与缩放 */
    static final class IconDrawable extends Drawable {
        private final Path strokePath;
        private final Path fillPath;
        private final Paint strokePaint;
        private final Paint fillPaint;
        private final int sizePx;
        private final float unit;

        IconDrawable(G g, int sizePx, int color, float strokeW) {
            this.strokePath = g.stroke;
            this.fillPath = g.fill;
            this.sizePx = sizePx;
            this.unit = sizePx / 24f;
            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setColor(color);
            strokePaint.setStrokeWidth(Math.max(1f, strokeW * unit));
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(color);
        }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.scale(unit, unit);
            if (!strokePath.isEmpty()) canvas.drawPath(strokePath, strokePaint);
            if (!fillPath.isEmpty()) canvas.drawPath(fillPath, fillPaint);
            canvas.restoreToCount(save);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }

        @Override public int getIntrinsicHeight() { return sizePx; }

        @Override public void setAlpha(int a) {
            strokePaint.setAlpha(a);
            fillPaint.setAlpha(a);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter cf) {
            strokePaint.setColorFilter(cf);
            fillPaint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
