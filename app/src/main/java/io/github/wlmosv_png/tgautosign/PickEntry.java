package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话选择器的**候选条目**与**过滤逻辑**（2026-10-17 新增）。
 *
 * 为什么要单独抽一个文件（而不是继续往 TGAutoSignCore 里堆）：
 *   Core 已经 23000 行，再加"三层数据源 + 过滤 + 分类"必然又是一团。
 *   这里只做**纯数据**：条目结构 + 过滤/排序，不碰任何 Android View、不碰反射。
 *   取数（反射宿主）留在 Core，UI 也留在 Core，中间这一层可以单独跑单测。
 *
 * 数据来源是 Core 分三层收集的（见 collectPickCandidates）：
 *   ① getDialogs 全文件夹   —— 最近聊过的
 *   ② MessagesStorage users —— 很久没聊但交互过的
 *   ③ MessagesController.users 内存表
 * 合并去重后交给这里过滤。
 */
final class PickEntry {

    /** 类型：私聊用户 / 机器人 / 群 / 频道。 */
    static final int T_USER = 0;
    static final int T_BOT = 1;
    static final int T_GROUP = 2;
    static final int T_CHANNEL = 3;

    long did;
    String title = "";
    String username = "";      // 不含 @
    int type = T_USER;
    boolean inDialog = false;  // 是否出现在会话列表里（用于排序：聊过的排前面）

    PickEntry(long did, String title, String username, int type, boolean inDialog) {
        this.did = did;
        this.title = title == null ? "" : title;
        this.username = username == null ? "" : username;
        this.type = type;
        this.inDialog = inDialog;
    }

    /** 显示用：@username（没有就空串）。 */
    String at() { return username.length() == 0 ? "" : "@" + username; }

    /**
     * 搜索是否命中。
     *
     * 支持三种输入，这是用户最常混用的三种：
     *   · 名称片段（"签到" / "check"）
     *   · @username（"@xxx_bot" 或 "xxx_bot"）
     *   · 数字 ID（"-1869891509" / "1869891509"，正负都试）
     */
    boolean matches(String q) {
        if (q == null) return true;
        String k = q.trim();
        if (k.length() == 0) return true;
        String low = k.toLowerCase(java.util.Locale.US);

        // 数字 ID：正负两种写法都认（用户常把负号漏掉）
        String digits = low.startsWith("-") ? low.substring(1) : low;
        if (digits.length() > 0 && digits.matches("\\d+")) {
            String mine = String.valueOf(Math.abs(did));
            return mine.equals(digits) || mine.contains(digits);
        }
        // @username
        String u = low.startsWith("@") ? low.substring(1) : low;
        if (username.length() > 0 && username.toLowerCase(java.util.Locale.US).contains(u)) return true;
        // 名称
        return title.toLowerCase(java.util.Locale.US).contains(low);
    }

    /** 分类 chip 过滤：cat = "all" / "bot" / "group" / "channel"。 */
    boolean inCat(String cat) {
        if (cat == null || "all".equals(cat)) return true;
        if ("bot".equals(cat)) return type == T_BOT;
        if ("group".equals(cat)) return type == T_GROUP;
        if ("channel".equals(cat)) return type == T_CHANNEL;
        return true;
    }

    /**
     * 过滤 + 排序。
     *
     * 排序规则（用户视角：最近的、聊过的先出来）：
     *   1. 出现在会话列表里的排前面（那是"我最近真的用过"）
     *   2. 同组内机器人优先（选 bot 的主场景）
     *   3. 再按标题字典序，保证结果稳定不跳动
     */
    static List<PickEntry> filter(List<PickEntry> src, String query, String cat, int limit) {
        List<PickEntry> out = new ArrayList<PickEntry>();
        if (src == null) return out;
        for (PickEntry e : src) {
            if (e == null) continue;
            if (!e.inCat(cat)) continue;
            if (!e.matches(query)) continue;
            out.add(e);
        }
        java.util.Collections.sort(out, new java.util.Comparator<PickEntry>() {
            @Override public int compare(PickEntry a, PickEntry b) {
                if (a.inDialog != b.inDialog) return a.inDialog ? -1 : 1;
                boolean ab = (a.type == T_BOT), bb = (b.type == T_BOT);
                if (ab != bb) return ab ? -1 : 1;
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<PickEntry>(out.subList(0, limit));
        }
        return out;
    }

    /** 按 did 去重合并（后加入的只补充缺失字段，不覆盖已有标题）。 */
    static void merge(Map<Long, PickEntry> into, PickEntry e) {
        if (into == null || e == null || e.did == 0L) return;
        PickEntry old = into.get(Long.valueOf(e.did));
        if (old == null) { into.put(Long.valueOf(e.did), e); return; }
        if (old.title.length() == 0 && e.title.length() > 0) old.title = e.title;
        if (old.username.length() == 0 && e.username.length() > 0) old.username = e.username;
        if (!old.inDialog && e.inDialog) old.inDialog = true;
        // 类型以更具体的为准：群/频道 > 机器人 > 普通用户
        if (e.type > old.type) old.type = e.type;
    }

    /** 由 did + 是否有用户名 + 是否 bot 推断类型。 */
    static int typeOf(long did, boolean isBot) {
        if (did < 0L) {
            // 频道 id 形如 -100xxxxxxxxxx；群是 -xxxxxxxxx
            long raw = -did;
            return raw > 1000000000000L ? T_CHANNEL : T_GROUP;
        }
        return isBot ? T_BOT : T_USER;
    }

    static PickEntry fromDid(long did, String title, String username, boolean isBot, boolean inDialog) {
        return new PickEntry(did, title, username, typeOf(did, isBot), inDialog);
    }

    /** 新建一个空容器（用 LinkedHashMap 保序，便于"先来先排"）。 */
    static Map<Long, PickEntry> newBag() { return new LinkedHashMap<Long, PickEntry>(); }
}
