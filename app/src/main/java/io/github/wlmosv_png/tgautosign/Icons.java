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
            "group", "clean", "megaphone", "chevron-r", "pencil", "pause",
            "hourglass", "gap", "key", "save", "x", "calendar", "bolt"));

    static boolean has(String name) {
        return name != null && NAMES.contains(name);
    }

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
