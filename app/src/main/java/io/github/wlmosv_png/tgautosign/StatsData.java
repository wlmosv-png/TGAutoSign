package io.github.wlmosv_png.tgautosign;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 统计页的**数据计算**（2026-10-04 分层重构）。
 *
 * 为什么单独一个类：
 *   统计页原先把"取数据 / 算趋势 / 画界面"混在 TGAutoSignCore 的 14 个方法里
 *   （约 634 行）。算错了只能靠肉眼看截图 ——
 *   事实上已经踩过两次：
 *     · 热力图日期范围算错，最后一格不是今天；
 *     · 7 日滑动平均的窗口边界写错。
 *   把纯计算抽出来之后，这些都能像 SignLogic 一样写单测钉住。
 *
 * 本类**不依赖 Android**（只用 java.util / java.text），
 * 因此可以直接 javac + java 跑测试，与既有 SignLogicTest 同一套方式。
 *
 * 约定：所有日期字符串都是 yyyy-MM-dd（与模块其它部分一致）。
 */
final class StatsData {
    private StatsData() {}

    private static final SimpleDateFormat YMD =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    /** yyyy-MM-dd 的当天（把时分秒清零，避免跨日边界抖动）。 */
    static String ymd(Calendar c) {
        Calendar t = (Calendar) c.clone();
        t.set(Calendar.HOUR_OF_DAY, 0);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        return YMD.format(t.getTime());
    }

    /**
     * 生成热力图/趋势图的日期序列。
     *
     * @param today   今天（yyyy-MM-dd）
     * @param days    往前取多少天
     * @param weekCols true = 按"周列"布局（起点对齐周一，长度补满整周）
     * @return 从旧到新的日期列表
     */
    static List<String> dateRange(String today, int days, boolean weekCols) {
        List<String> out = new ArrayList<String>();
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(YMD.parse(today));
            if (weekCols) {
                // 对齐到本周一，再往前推若干整周，终点正好落在"今天所在的那一周"
                int dow = c.get(Calendar.DAY_OF_WEEK);          // 1=周日 … 7=周六
                int toMon = (dow + 5) % 7;                      // 距本周一的天数（周一=0）
                int cols = (days + 6) / 7;
                c.add(Calendar.DATE, -toMon - (cols - 1) * 7);
                int total = cols * 7;
                for (int i = 0; i < total; i++) {
                    out.add(YMD.format(c.getTime()));
                    c.add(Calendar.DATE, 1);
                }
            } else {
                c.add(Calendar.DATE, -(days - 1));
                for (int i = 0; i < days; i++) {
                    out.add(YMD.format(c.getTime()));
                    c.add(Calendar.DATE, 1);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * 7 日滑动平均（用于趋势折线）。
     *
     * 输入是每天"签没签"（0/1），直接连线是锯齿，看不出趋势；
     * 滑动平均把它变成一条平滑曲线。窗口是**当前及之前**共 7 天
     * （不足 7 天时按实际天数平均，避免开头被拉低）。
     */
    static float[] movingAvg(float[] daily, int window) {
        if (daily == null || daily.length == 0) return new float[0];
        int w = Math.max(1, window);
        float[] out = new float[daily.length];
        float sum = 0f;
        for (int i = 0; i < daily.length; i++) {
            sum += daily[i];
            if (i >= w) sum -= daily[i - w];
            int n = Math.min(i + 1, w);
            out[i] = n > 0 ? sum / n : 0f;
        }
        return out;
    }

    /** 把日期集合转成"每天 0/1"的数组（顺序与 list 一致）。 */
    static float[] dailyHits(Set<String> days, List<String> list) {
        if (list == null) return new float[0];
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = (days != null && days.contains(list.get(i))) ? 1f : 0f;
        }
        return out;
    }

    /** 统计 list 中有多少天命中。 */
    static int countHits(Set<String> days, List<String> list) {
        if (list == null) return 0;
        int n = 0;
        for (String d : list) if (days != null && days.contains(d)) n++;
        return n;
    }

    /**
     * 星期分布：周一..周日各命中多少天。返回长度 7（索引 0 = 周一）。
     *
     * 注意 Calendar.DAY_OF_WEEK 是 1=周日…7=周六，需要换算。
     */
    static int[] weekdayDist(Set<String> days) {
        int[] out = new int[7];
        if (days == null) return out;
        for (String d : days) {
            if (d == null || d.length() != 10) continue;
            try {
                Calendar c = Calendar.getInstance();
                c.setTime(YMD.parse(d));
                int dow = c.get(Calendar.DAY_OF_WEEK);      // 1=周日
                out[(dow + 5) % 7]++;                        // 转成 0=周一
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * 前 N 天与后 N 天的命中数对比（用于"在变好/在变差"）。
     * @return int[]{前段, 后段}
     */
    static int[] headTailHits(Set<String> days, List<String> list, int n) {
        int head = 0, tail = 0;
        if (list == null) return new int[]{0, 0};
        int k = Math.min(n, list.size() / 2);
        for (int i = 0; i < k; i++) {
            if (days != null && days.contains(list.get(i))) head++;
            if (days != null && days.contains(list.get(list.size() - 1 - i))) tail++;
        }
        return new int[]{head, tail};
    }

    /** 连续天数：今天没签不归零，显示"截至昨天"的连续值。 */
    static int streak(int stored, String lastDate, String today, String yesterday) {
        return SignLogic.streakDisplay(stored, lastDate, today, yesterday);
    }
}
