package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 统计页的数据快照（2026-10-04 分层重构）。
 *
 * 设计取舍：**数据与渲染彻底分开**。
 *   上一版想法是"给 StatsView 一个接口，让它自己去 Core 取数" ——
 *   那等于把 Core 的依赖换个形式带过去，模块一大照样纠缠。
 *
 * 现在的分工：
 *   Core.statsSnapshot()   读 prefs / 遍历目标 → 填出本对象（唯一的取数处）
 *   StatsView.build(act, snap)  只负责把本对象画出来（**不读任何状态**）
 *
 * 好处：
 *   · StatsView 可以脱离 Core 单独读懂（输入就是这些字段）；
 *   · 想要"假数据预览界面"时，直接 new 一个填假值即可；
 *   · 数据口径只有一处，不会出现"两个页面算法不同"。
 */
final class StatsSnapshot {

    /** 一个目标的统计条目。 */
    static final class Target {
        String name = "";
        boolean signedToday;
        int failStreak;
        long signedAtMs;
        boolean pending;      // 待确认（需人工处理）
        boolean frozen;
    }

    /** 一个账号的统计条目。 */
    static final class Account {
        int slot;
        String label = "";
        boolean current;
        int todaySigned;
        int todayTotal;
        int streak;
        int hits30;           // 近 30 天命中
        int span30 = 30;
    }

    // ── 当前账号 ──
    String today = "";
    String yesterday = "";
    int todaySigned;
    int todayTotal;
    int streak;
    int totalDays;            // 累计签到天数
    int hits30;
    int hits90;

    /** 已签日期集合（当前账号），供热力图/趋势/星期分布使用。 */
    java.util.Set<String> signDays = new java.util.HashSet<String>();

    /** 各账号（多账号对比）。 */
    final List<Account> accounts = new ArrayList<Account>();

    /** 各目标。 */
    final List<Target> targets = new ArrayList<Target>();

    /** 今日已签目标的时刻（毫秒），用于时段分布。 */
    final List<Long> todayTimes = new ArrayList<Long>();
}
