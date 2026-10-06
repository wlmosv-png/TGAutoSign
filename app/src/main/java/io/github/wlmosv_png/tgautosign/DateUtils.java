package io.github.wlmosv_png.tgautosign;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 日期工具（2026-10-06 P3 新增）。
 *
 * 为什么要有它：
 *   项目里到处 new SimpleDateFormat / Calendar，StatsData 还持有一个**静态共享实例**
 *   —— SimpleDateFormat 不是线程安全的，多账号并发下会串日期。
 *   另外 SignStateStore 的跨天判断依赖自己拼的 Calendar 逻辑，容易写反。
 *
 * 约定：
 *   · **不引用任何 Android 包** —— 可被纯 Java 单测直接编译（交接单要求）。
 *   · 全部使用 java.time（minSdk 26 起可用），**本地时区**。
 *   · 输出格式固定 {@code yyyy-MM-dd}，与项目里存量 prefs 的日期串**完全一致**，
 *     保证历史数据不失效。
 */
public final class DateUtils {

    /** 项目统一的日期串格式（存量 prefs 依赖此格式，不可改）。 */
    public static final DateTimeFormatter YMD =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateUtils() {}

    /** 今天（本地时区），yyyy-MM-dd。 */
    public static String today() {
        return LocalDate.now().format(YMD);
    }

    /** 昨天（本地时区），yyyy-MM-dd。 */
    public static String yesterday() {
        return LocalDate.now().minusDays(1).format(YMD);
    }

    /** 毫秒时间戳 → 本地日期串。 */
    public static String fromMillis(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(YMD);
    }

    /** 解析日期串；非法返回 null（不抛）。 */
    public static LocalDate parse(String ymd) {
        try {
            if (ymd == null) return null;
            String t = ymd.trim();
            if (t.length() == 0) return null;
            return LocalDate.parse(t, YMD);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 该日期串是否是今天。 */
    public static boolean isToday(String ymd) {
        return today().equals(ymd);
    }

    /** 该日期串是否是昨天。 */
    public static boolean isYesterday(String ymd) {
        return yesterday().equals(ymd);
    }

    /**
     * 该日期串是否是「今天或昨天」。
     *
     * 用于跨天判断：stamp 为今天或昨天都算「仍在延续」，
     * 更早则说明中断了 —— 见 SignStateStore.failStreak。
     */
    public static boolean isTodayOrYesterday(String ymd) {
        return isToday(ymd) || isYesterday(ymd);
    }


    /** 紧凑日期 yyyyMMdd（日志文件名等）。 */
    public static final DateTimeFormatter YMD_COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 本地日期时间 yyyy-MM-dd HH:mm:ss（日志落盘）。 */
    public static final DateTimeFormatter YMD_HMS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 毫秒 → yyyy-MM-dd HH:mm:ss（本地时区）。 */
    public static String hms(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(YMD_HMS);
    }

    /** 现在 → yyyy-MM-dd HH:mm:ss。 */
    public static String nowHms() {
        return java.time.LocalDateTime.now().format(YMD_HMS);
    }

    /** 现在 → yyyyMMdd。 */
    public static String todayCompact() {
        return LocalDate.now().format(YMD_COMPACT);
    }

    /** 毫秒 → yyyy-MM-dd。 */
    public static String ymd(long ms) {
        return fromMillis(ms);
    }

    /** 解析 yyyy-MM-dd HH:mm:ss；非法返回 null。 */
    public static java.time.LocalDateTime parseHms(String s) {
        try {
            if (s == null) return null;
            String t = s.trim();
            if (t.length() < 19) return null;
            return java.time.LocalDateTime.parse(t.substring(0, 19), YMD_HMS);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析 yyyyMMdd → LocalDate；非法返回 null。 */
    public static LocalDate parseCompact(String s) {
        try {
            if (s == null) return null;
            String t = s.trim();
            if (t.length() != 8) return null;
            return LocalDate.parse(t, YMD_COMPACT);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 两个日期串相差天数；任一非法返回 -1。 */
    public static long daysBetween(String fromYmd, String toYmd) {
        LocalDate a = parse(fromYmd);
        LocalDate b = parse(toYmd);
        if (a == null || b == null) return -1L;
        return java.time.temporal.ChronoUnit.DAYS.between(a, b);
    }
}
