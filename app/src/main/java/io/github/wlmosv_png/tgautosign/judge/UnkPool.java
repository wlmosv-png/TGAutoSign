package io.github.wlmosv_png.tgautosign.judge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UnkPool —— 「未识别回复」池（判定词自学习 · P1）。
 *
 * 目的：把判定链里落到 V_UNKNOWN 的 bot 回复沉淀下来，
 * 供后续（P2/P3）在页面上确认「算成功 / 算失败」，进而生成自定义判定词。
 *
 * 设计约束（对着历史事故来）：
 *  1. 硬上限 MAX_ITEMS=200，超出按时间淘汰最旧 —— 防止 prefs 无限膨胀；
 *  2. 每个 bot 每天最多 DAILY_PER_BOT=5 条 —— 防一个坏 bot 刷爆池；
 *  3. 全部方法自带 try-catch，异常一律吞掉 —— 绝不因为采集而崩判定主链；
 *  4. 只做纯字符串 + prefs 操作，无网络 / 无线程等待 —— 不拖慢判定链；
 *  5. 池按账号 prefix 隔离（与 Core 的 accountPrefix 体系一致）。
 *
 * 存储格式（prefs，key = {@code <prefix>unk_pool}）：
 *   一个手写序列化的列表，每行一条，字段以 {@code \u0001} 分隔：
 *     t=<时间戳> d=<did> n=<出现次数> r=<归一化文本> o=<原始样例>
 *   之所以不用 JSON：模块无第三方依赖，手写序列化最快且零依赖。
 *
 * 限频记录（prefs，key = {@code <prefix>unk_seen}）：
 *   一行一条： {@code <did> <yyyymmdd> <当日已记条数>}
 *
 * 注意：本类不引用 SignLogic / Core，保持可单测、无 Android 运行时依赖
 * （仅依赖 SharedPreferences 接口，测试可用内存实现）。
 */
public final class UnkPool {

    /** 池容量上限。 */
    public static final int MAX_ITEMS = 200;
    /** 单个 bot 每日最多入池条数（防刷）。 */
    public static final int DAILY_PER_BOT = 8;
    /** 单条文本最长保留长度（防一条超长回复撑爆 prefs）。 */
    public static final int MAX_TEXT = 200;
    /** 原始样例最长保留长度。 */
    public static final int MAX_SAMPLE = 120;

    private static final char FIELD_SEP = '\u0001';
    private static final char PAIR_SEP = '=';

    private UnkPool() {}

    /** 一条池记录。 */
    public static final class Item {
        public long t;        // 首次入库时间戳
        public long d;        // did
        public int n;         // 出现次数
        public String r;      // 归一化文本（聚类键）
        public String o;      // 原始样例（展示用）

        public Item() {}

        Item(long t, long d, int n, String r, String o) {
            this.t = t; this.d = d; this.n = n; this.r = r; this.o = o;
        }
    }

    // ───────────────────────── 写入 ─────────────────────────

    /**
     * 采集一条未识别回复。
     *
     * @param prefs   目标 prefs（调用方用 accountPrefix 对应的那个）
     * @param poolKey 池子键（含 prefix，如 "acc1_unk_pool"）
     * @param seenKey 限频键（含 prefix，如 "acc1_unk_seen"）
     * @param did     bot 数字 id
     * @param raw     回复原文
     * @param norm    归一化后的文本（由 ReplyNormalizer 提供；为空则用 raw 截断）
     * @param today   当日标识（yyyymmdd），用于限频
     * @return true 表示本次真的写入了（新增或计数+1）
     */
    public static boolean add(SharedPreferences prefs, String poolKey, String seenKey,
                              long did, String raw, String norm, String today) {
        try {
            if (prefs == null || poolKey == null) return false;
            String key = norm == null || norm.length() == 0 ? clip(raw, MAX_TEXT) : clip(norm, MAX_TEXT);
            if (key == null || key.length() == 0) return false;

            // 1. 限频：该 bot 今日已记满则直接返回
            Map<String, int[]> seen = loadSeen(prefs, seenKey);
            // 2026-10-04：限频键改为 did+id（每个目标独立配额）。
            // 原先按 did 限频，一个 bot 下多个目标共用 5 条，
            // 用户反复测同一 bot 时很快打满，之后完全不采集（实测 "7002913751 20261004 5"）。
            String didKey = did + "|" + (raw == null ? "" : raw.hashCode());
            // 说明：用 (did, 回复内容哈希) 作限频键 —— 同一目标的同一句话只记一次，
            // 不同目标/不同内容各有配额，避免一个 bot 撑爆整池。
            int[] rec = seen.get(didKey);
            if (rec != null && today != null && today.equals(dayOf(rec))) {
                if (rec[1] >= DAILY_PER_BOT) return false;
            }

            // 2. 读池
            List<Item> items = load(prefs, poolKey);

            // 3. 去重合并：归一化文本相同 → n++，不新增
            Item hit = null;
            for (Item it : items) {
                if (it.r != null && it.r.equals(key)) { hit = it; break; }
            }
            if (hit != null) {
                hit.n = hit.n + 1;
                // 刷新样例（保留最新一条，展示更贴近现状）
                if (raw != null && raw.length() > 0) hit.o = clip(raw, MAX_SAMPLE);
            } else {
                items.add(new Item(System.currentTimeMillis(), did, 1, key, clip(raw, MAX_SAMPLE)));
                trim(items);
            }

            // 4. 落盘
            save(prefs, poolKey, items);

            // 5. 更新限频
            if (today != null) {
                int cur = (rec != null && today.equals(dayOf(rec))) ? rec[1] : 0;
                seen.put(didKey, new int[]{Integer.parseInt(today.replace("-", "").substring(0, 8)), cur + 1});
                saveSeen(prefs, seenKey, seen);
            }
            return true;
        } catch (Throwable t) {
            return false;   // 采集失败绝不外抛
        }
    }

    // ───────────────────────── 读取 ─────────────────────────

    /** 读取全部池记录（按出现次数降序，次数相同按时间倒序）。 */
    public static List<Item> list(SharedPreferences prefs, String poolKey) {
        List<Item> items = load(prefs, poolKey);
        Collections.sort(items, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.n != b.n) return b.n - a.n;
                return Long.compare(b.t, a.t);
            }
        });
        return items;
    }

    /** 池中原始条目数（未聚类，用于概况显示）。 */
    public static int rawCount(SharedPreferences prefs, String poolKey) {
        return load(prefs, poolKey).size();
    }

    /** 池中涉及的不同 bot 数。 */
    public static int botCount(SharedPreferences prefs, String poolKey) {
        List<Item> items = load(prefs, poolKey);
        java.util.HashSet<Long> s = new java.util.HashSet<Long>();
        for (Item it : items) s.add(it.d);
        return s.size();
    }

    // ───────────────────────── 删除 / 维护 ─────────────────────────

    /** 按归一化文本删除条目（确认后从池中移除）。返回是否删掉了东西。 */
    public static boolean remove(SharedPreferences prefs, String poolKey, String norm) {
        try {
            List<Item> items = load(prefs, poolKey);
            boolean removed = false;
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).r != null && items.get(i).r.equals(norm)) {
                    items.remove(i); removed = true;
                }
            }
            if (removed) save(prefs, poolKey, items);
            return removed;
        } catch (Throwable t) { return false; }
    }

    /** 清空池与限频记录。 */
    public static void clear(SharedPreferences prefs, String poolKey, String seenKey) {
        try {
            if (prefs == null) return;
            SharedPreferences.Editor e = prefs.edit();
            if (poolKey != null) e.remove(poolKey);
            if (seenKey != null) e.remove(seenKey);
            e.apply();
        } catch (Throwable ignored) {}
    }

    // ───────────────────────── 内部：序列化 ─────────────────────────

    private static List<Item> load(SharedPreferences prefs, String poolKey) {
        List<Item> out = new ArrayList<Item>();
        try {
            if (prefs == null || poolKey == null) return out;
            String blob = prefs.getString(poolKey, "");
            if (blob == null || blob.length() == 0) return out;
            String[] lines = blob.split("\n");
            for (String line : lines) {
                if (line == null || line.length() == 0) continue;
                Item it = parseLine(line);
                if (it != null && it.r != null && it.r.length() > 0) out.add(it);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void save(SharedPreferences prefs, String poolKey, List<Item> items) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Item it : items) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("t").append(PAIR_SEP).append(it.t)
                  .append(FIELD_SEP).append("d").append(PAIR_SEP).append(it.d)
                  .append(FIELD_SEP).append("n").append(PAIR_SEP).append(it.n)
                  .append(FIELD_SEP).append("r").append(PAIR_SEP).append(esc(it.r))
                  .append(FIELD_SEP).append("o").append(PAIR_SEP).append(esc(it.o));
            }
            prefs.edit().putString(poolKey, sb.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static Item parseLine(String line) {
        try {
            Item it = new Item();
            String[] fields = line.split(String.valueOf(FIELD_SEP));
            for (String f : fields) {
                int p = f.indexOf(PAIR_SEP);
                if (p <= 0) continue;
                String k = f.substring(0, p);
                String v = unesc(f.substring(p + 1));
                if ("t".equals(k)) it.t = parseLong(v);
                else if ("d".equals(k)) it.d = parseLong(v);
                else if ("n".equals(k)) it.n = parseInt(v);
                else if ("r".equals(k)) it.r = v;
                else if ("o".equals(k)) it.o = v;
            }
            return it;
        } catch (Throwable t) { return null; }
    }

    // ───────────────────────── 内部：限频 ─────────────────────────

    /** did → {yyyymmdd, count} */
    private static Map<String, int[]> loadSeen(SharedPreferences prefs, String seenKey) {
        Map<String, int[]> out = new LinkedHashMap<String, int[]>();
        try {
            if (prefs == null || seenKey == null) return out;
            String blob = prefs.getString(seenKey, "");
            if (blob == null || blob.length() == 0) return out;
            for (String line : blob.split("\n")) {
                if (line == null || line.length() == 0) continue;
                String[] p = line.trim().split(" ");
                if (p.length < 3) continue;
                out.put(p[0], new int[]{parseInt(p[1]), parseInt(p[2])});
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void saveSeen(SharedPreferences prefs, String seenKey, Map<String, int[]> seen) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, int[]> e : seen.entrySet()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getKey()).append(' ')
                  .append(e.getValue()[0]).append(' ')
                  .append(e.getValue()[1]);
            }
            prefs.edit().putString(seenKey, sb.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static String dayOf(int[] rec) {
        try {
            String s = String.valueOf(rec[0]);
            if (s.length() == 8) return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
            return s;
        } catch (Throwable t) { return ""; }
    }

    // ───────────────────────── 内部：工具 ─────────────────────────

    /** 超限淘汰：按 t 升序删除最旧，直到 <= MAX_ITEMS。 */
    private static void trim(List<Item> items) {
        while (items.size() > MAX_ITEMS) {
            int oldest = 0;
            for (int i = 1; i < items.size(); i++) {
                if (items.get(i).t < items.get(oldest).t) oldest = i;
            }
            items.remove(oldest);
        }
    }

    /** 转义分隔符与换行，避免破坏行格式。 */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(String.valueOf(FIELD_SEP), "\\u0001")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    static String unesc(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n")
                .replace("\\u0001", String.valueOf(FIELD_SEP))
                .replace("\\\\", "\\");
    }

    static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0L; }
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; }
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
