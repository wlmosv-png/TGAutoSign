package io.github.wlmosv_png.tgautosign.judge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

/**
 * ReplyNormalizer —— bot 回复的归一化与聚类（判定词自学习 · P2 前半）。
 *
 * 目的：把「措辞千奇百怪」的未识别回复压成少数几个模式，
 * 让用户在页面上确认的量从几百条降到十几条。
 *
 * 纯函数，无 Android / Xposed 依赖，可直接在 JVM 上跑单测。
 * 约定（与 SignLogic 一致）：本类不放任何有副作用的代码。
 *
 * 归一化规则：
 *   1. 去首尾空白；
 *   2. 全角 → 半角；统一小写；
 *   3. 连续数字 → {N}（保留数字个数信息会更好，但易造成模式碎片，选统一）；
 *   4. 去首尾装饰字符（emoji / 符号：✅⭕⚠️❗️★☆等）；
 *   5. 压缩连续空白为单个空格；
 *   6. 截断到 MAX_LEN。
 *
 * 注意：**不删中间标点**。中文「签到成功，获得5积分」与「签到成功获得5积分」
 * 若把逗号也去掉会合并成一个模式，但那两条实际判别力相同，
 * 合并反而更利于提词。所以标点统一：中文逗号/顿号 → 英文逗号，其余保留。
 */
public final class ReplyNormalizer {

    /** 归一化文本最长长度（与 UnkPool.MAX_TEXT 对齐）。 */
    public static final int MAX_LEN = 120;   // 2026-10-07：200 → 120（超长键拖慢聚类与渲染）

    private ReplyNormalizer() {}

    /**
     * 归一化单条回复。
     *
     * @param raw 原文
     * @return 归一化文本；输入为空时返回空串
     */
    public static String normalize(String raw) {
        try {
            if (raw == null) return "";
            String s = raw.trim();
            if (s.length() == 0) return "";

            s = toHalfWidth(s);              // 全角 → 半角
            s = stripEdgeDeco(s);            // 去首尾装饰符
            s = unifyPunct(s);               // 标点统一
            s = replaceNumbers(s);           // 数字 → {N}
            s = compressSpace(s);            // 压缩空白
            s = s.toLowerCase();             // 小写（英文）
            s = trimEdges(s);
            s = s.replace(" ", "");          // 聚类键去空格（见下）
            s = truncate(s, MAX_LEN);
            return s;
        } catch (Throwable t) {
            return raw == null ? "" : raw.trim();
        }
    }

    /**
     * 聚类：把一组原始回复按归一化结果分组。
     *
     * @param raws 原始回复列表（可含 null / 空）
     * @return 模式列表，已按出现次数降序排序；每项的 samples 是代表样例（最多 3 条）
     */
    public static List<Pattern> cluster(List<String> raws) {
        Map<String, Pattern> map = new LinkedHashMap<String, Pattern>();
        if (raws == null) return new ArrayList<Pattern>();
        for (String raw : raws) {
            if (raw == null) continue;
            String norm = normalize(raw);
            if (norm.length() == 0) continue;
            Pattern p = map.get(norm);
            if (p == null) {
                p = new Pattern(norm);
                p.count = 0;
                map.put(norm, p);
            }
            p.count++;
            if (p.samples.size() < 3) {
                String s = raw.trim();
                if (s.length() > UnkPool.MAX_SAMPLE) s = s.substring(0, UnkPool.MAX_SAMPLE);
                if (!p.samples.contains(s)) p.samples.add(s);
            }
        }
        List<Pattern> out = new ArrayList<Pattern>(map.values());
        Collections.sort(out, new Comparator<Pattern>() {
            @Override public int compare(Pattern a, Pattern b) {
                if (a.count != b.count) return b.count - a.count;
                return a.norm.compareTo(b.norm);
            }
        });
        return out;
    }

    /**
     * 按 Item 聚类（2026-10-06 新增，交接单第十条）。
     *
     * 与 {@link #cluster(java.util.List)} 的区别：这里**保留来源** ——
     * dids / fromDids / targetIds 会被真实填充。
     * 旧方法保留，供不关心来源的调用方使用（其 dids 恒为空）。
     *
     * @param items UnkPool 里的条目（或任意具备 r/o/d 的对象语义）
     */
    public static List<Pattern> clusterItems(List<io.github.wlmosv_png.tgautosign.judge.UnkPool.Item> items) {
        Map<String, Pattern> map = new LinkedHashMap<String, Pattern>();
        if (items == null) return new ArrayList<Pattern>();
        for (io.github.wlmosv_png.tgautosign.judge.UnkPool.Item it : items) {
            if (it == null) continue;
            String norm = it.r != null && it.r.length() > 0 ? it.r : normalize(it.o);
            if (norm == null || norm.length() == 0) continue;
            Pattern p = map.get(norm);
            if (p == null) {
                p = new Pattern(norm);
                map.put(norm, p);
            }
            p.count += Math.max(1, it.n);
            // 来源（#1：用集合，跨 bot 不再只留最后一个）
            if (it.d != 0L) p.dids.add(it.d);
            for (Long f : it.fromList()) { if (f != null && f.longValue() != 0L) p.fromDids.add(f); }
            if (it.tid != null && it.tid.length() > 0) p.targetIds.add(it.tid);
            if (it.tids != null && it.tids.length() > 0) {
                for (String t : it.tids.split(",")) {
                    String tt = t.trim();
                    if (tt.length() > 0) p.targetIds.add(tt);
                }
            }
            // 样例
            if (p.samples.size() < 3 && it.o != null) {
                String s = it.o.trim();
                if (s.length() > UnkPool.MAX_SAMPLE) s = s.substring(0, UnkPool.MAX_SAMPLE);
                if (!p.samples.contains(s)) p.samples.add(s);
            }
        }
        List<Pattern> out = new ArrayList<Pattern>(map.values());
        Collections.sort(out, new Comparator<Pattern>() {
            @Override public int compare(Pattern a, Pattern b) {
                if (a.count != b.count) return b.count - a.count;
                return a.norm.compareTo(b.norm);
            }
        });
        return out;
    }

    /** 归一化模式（聚类结果）。 */
    public static final class Pattern {
        public final String norm;              // 归一化文本（聚类键）
        public int count;                      // 出现次数
        public final List<String> samples;     // 原始样例（≤3）
        public final HashSet<Long> dids = new HashSet<Long>();   // 涉及的会话（群/私聊）
        // 2026-10-06：来源集合（交接单第十条）—— 由 clusterItems 填充。
        // 旧 cluster(List<String>) 无法知道来源，此集合会保持为空。
        public final HashSet<Long> fromDids = new HashSet<Long>();   // 回复发送者（群聊时是 bot）
        public final HashSet<String> targetIds = new HashSet<String>(); // 目标条目 id

        public Pattern(String norm) {
            this.norm = norm;
            this.samples = new ArrayList<String>();
        }

        @Override public String toString() { return norm + " ×" + count; }
    }

    // ───────────────────────── 分步实现 ─────────────────────────

    /** 全角字符（FF01-FF5E）→ 半角；全角空格 → 普通空格。 */
    static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u3000') sb.append(' ');
            else if (c >= '\uFF01' && c <= '\uFF5E') sb.append((char) (c - 0xFEE0));
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 去掉首尾的装饰字符：emoji、符号、空白。
     * 只处理首尾，中间不动 —— 中间 emoji 往往无判别力但也不影响聚类。
     */
    static String stripEdgeDeco(String s) {
        int a = 0, b = s.length();
        while (a < b && isDeco(s.charAt(a))) a++;
        while (b > a && isDeco(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** 判断是否为「装饰性」字符（不计入模式核心）。 */
    static boolean isDeco(char c) {
        if (Character.isWhitespace(c)) return true;
        // 常见 emoji / 符号区间与具体字符
        if (c >= 0x2700 && c <= 0x27BF) return true;   // 装饰性符号
        if (c >= 0x2600 && c <= 0x26FF) return true;   // 杂项符号
        if (c >= 0x2190 && c <= 0x21FF) return true;   // 箭头
        if (c >= 0x2B00 && c <= 0x2BFF) return true;   // 杂项符号箭头
        if (c >= 0x1F300 && c <= 0x1FAFF) return true; // 主要 emoji 区块
        if (c >= 0xFE00 && c <= 0xFE0F) return true;   // 变体选择符
        if (c >= 0x2000 && c <= 0x206F) return true;   // 通用标点（含零宽）
        switch (c) {
            case '✅': case '❌': case '⭕': case '⚠': case '❗': case '❓':
            case '★': case '☆': case '●': case '○': case '◆': case '◇':
            case '·': case '•': case '「': case '」': case '【': case '】':
            case '（': case '）': case '[': case ']': case '(': case ')':
            case '-': case '—': case '_': case '|': case '/': case '\\':
            case '~': case '^': case '*': case '>': case '<': case '#':
                return true;
            default:
                return false;
        }
    }

    /**
     * 标点统一：**所有分隔性标点与空白统一成单个空格**。
     *
     * 为什么统一成空格而不是逗号（2026-10-04 实测发现）：
     *   「签到成功，获得5积分」与「签到成功 获得5积分」语义完全一致，
     *   但一个用逗号一个用空格 —— 若标点保留为逗号，两者聚成两个模式，
     *   用户的确认量凭空翻倍。统一成空格后二者合并，聚类更干净。
     *   句末标点（。！？）一律丢弃（无判别力）。
     */
    static String unifyPunct(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '，' || c == '、' || c == '；' || c == '：'
                    || c == ',' || c == ';' || c == ':') {
                sb.append(' ');
            } else if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
                sb.append(' ');   // 句末标点 → 空格，后续压缩
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 连续数字串 → {N}。
     * 例：「获得5积分」「+12 分」「明天10:30」→「获得{N}积分」「+{N} 分」「明天{N}:{N}」
     */
    static String replaceNumbers(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inNum = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (!inNum) { sb.append("{N}"); inNum = true; }
            } else {
                sb.append(c);
                inNum = false;
            }
        }
        return sb.toString();
    }

    /** 压缩连续空白为单个空格。 */
    static String compressSpace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!prevSpace) sb.append(' ');
                prevSpace = true;
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString();
    }

    /** 去掉首尾的空白与逗号/点号等残余标点。 */
    static String trimEdges(String s) {
        int a = 0, b = s.length();
        while (a < b && isEdgePunct(s.charAt(a))) a++;
        while (b > a && isEdgePunct(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    static boolean isEdgePunct(char c) {
        return Character.isWhitespace(c)
                || c == ',' || c == '.' || c == '!' || c == '?' || c == ':';
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
