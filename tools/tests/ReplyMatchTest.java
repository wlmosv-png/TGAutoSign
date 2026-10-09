package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReplyMatchTest —— 群聊回复关联（四级优先级 + 唯一性约束）的行为测试。
 *
 * 背景（本轮完成标准第二条）：
 *   同一个群里挂了多个「未绑 bot」的目标时，任意一条 bot 回复旧代码都会落到
 *   其中一个（取 sent_at 最大者）→ 互相污染。
 *   新规则：候选 > 1 → AMBIGUOUS，不关联，宁可进未识别回复。
 *
 * 说明：Core.resolveReplyTarget 依赖 prefs + Map 目标表，本文件按**同一套判定语义**
 *   复刻为纯函数（与生产代码逐条对应），从而在无 Android 运行时也能验证规则。
 *   生产代码路径：TGAutoSignCore.resolveReplyTarget / ReplyMatch。
 */
public final class ReplyMatchTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    static final long PENDING_TTL_MS = 10L * 60 * 1000;

    public static void main(String[] args) {
        replyToWins();
        botDidWins();
        peerTtlUnique();
        peerTtlAmbiguous();
        peerTtlNewestDisambig();
        peerExactPrefersSameEncoding();
        channelIdCollisionDoc();
        groupNoFallback();
        privateFallback();

        System.out.println("----------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  ✗ " + f);
        if (!failed.isEmpty()) { System.out.println("FAILED"); System.exit(1); }
        System.out.println("ALL OK");
    }

    private static void eq(String what, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) passed++;
        else failed.add(what + " → 得到 " + got + "，期望 " + want);
    }

    private static void tru(String what, boolean cond) {
        if (cond) passed++; else failed.add(what + " → 条件不成立");
    }

    // ── 目标条目 ──
    static final class T {
        String id; long did; Long botDid;
        T(String id, long did, Long botDid) { this.id = id; this.did = did; this.botDid = botDid; }
    }

    static final class Result {
        String targetId; String method; int candidateCount;
    }

    /** 与 Core.resolveReplyTarget 同一套语义的纯函数复刻。 */
    static Result resolve(List<T> list, long peerDid, long fromDid, boolean fromIsBot,
                          int replyToId, Map<String, Object> prefs) {
        Result r = new Result();
        r.method = "NONE";
        if (list == null || list.isEmpty() || peerDid == 0L) return r;
        boolean group = peerDid < 0L;
        if (group && !fromIsBot) return r;                 // 群里普通人发言：不参与
        long now = System.currentTimeMillis();
        // 2026-10-09：精确优先 —— 先按 did 完全相等筛候选；
        // 精确集非空则只用它，避免 samePeerDid 的 -X/-100X 归一造成跨群串号。
        List<T> exact = new ArrayList<T>();
        for (T m : list) if (m.did == peerDid) exact.add(m);
        if (!exact.isEmpty()) list = exact;

        // ① REPLY_TO
        if (replyToId > 0) {
            for (T m : list) {
                if (m.did != peerDid) continue;
                Object s = prefs.get("msg_id_" + m.id);
                if (s instanceof Number && ((Number) s).intValue() == replyToId) {
                    r.targetId = m.id; r.method = "REPLY_TO"; return r;
                }
            }
        }
        // ② BOT_DID
        if (group && fromDid != 0L) {
            for (T m : list) {
                if (m.did != peerDid) continue;
                if (m.botDid != null && m.botDid != 0L) {
                    if (m.botDid == fromDid) { r.targetId = m.id; r.method = "BOT_DID"; return r; }
                }
            }
        }
        // ③ PEER_TTL（唯一性约束）
        T only = null; int cand = 0;
        List<T> cands = new ArrayList<T>();
        List<Long> candSent = new ArrayList<Long>();
        for (T m : list) {
            if (m.did != peerDid) continue;
            if (group && fromDid != 0L && m.botDid != null && m.botDid != 0L && m.botDid != fromDid) continue;
            Object s = prefs.get("sent_at_" + m.id);
            long sent = s instanceof Number ? ((Number) s).longValue() : 0L;
            if (sent <= 0L) continue;
            if (now - sent > PENDING_TTL_MS) continue;
            cand++; only = m; cands.add(m); candSent.add(Long.valueOf(sent));
        }
        if (cand == 1 && only != null) { r.targetId = only.id; r.method = "PEER_TTL"; return r; }
        if (cand > 1) {
            // 2026-10-09：最新比次新至少新 60s → 认最新那条；否则仍 AMBIGUOUS。
            final long GAP = 60000L;
            int newestIdx = -1; long best = 0L, second = 0L;
            for (int i = 0; i < candSent.size(); i++) {
                long v = candSent.get(i).longValue();
                if (v > best) { second = best; best = v; newestIdx = i; }
                else if (v > second) { second = v; }
            }
            if (newestIdx >= 0 && best - second >= GAP) {
                r.targetId = cands.get(newestIdx).id;
                r.method = "PEER_TTL_NEWEST";
                r.candidateCount = cand;
                return r;
            }
            r.targetId = null; r.method = "AMBIGUOUS"; r.candidateCount = cand; return r;
        }

        // ④ FALLBACK（仅私聊）
        if (!group && fromDid != 0L && fromDid == peerDid) {
            for (T m : list) {
                if (m.did != peerDid) continue;
                r.targetId = m.id; r.method = "FALLBACK"; return r;
            }
        }
        return r;
    }

    private static Map<String, Object> prefs(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ════════════════════════════════════════════════════════════
    private static void replyToWins() {
        long group = -100L;
        List<T> l = new ArrayList<T>();
        l.add(new T("g_1", group, null));
        l.add(new T("g_2", group, null));
        // 两个候选都新鲜，但 reply_to 精确指向 g_2 发送的消息
        Map<String, Object> p = prefs(
            "sent_at_g_1", System.currentTimeMillis(),
            "sent_at_g_2", System.currentTimeMillis(),
            "msg_id_g_2", Integer.valueOf(777));
        Result r = resolve(l, group, 555L, true, 777, p);
        eq("REPLY_TO 优先选中 g_2", r.targetId, "g_2");
        eq("方法为 REPLY_TO", r.method, "REPLY_TO");
    }

    private static void botDidWins() {
        long group = -100L;
        List<T> l = new ArrayList<T>();
        l.add(new T("g_1", group, Long.valueOf(111L)));   // 绑定 Bot A
        l.add(new T("g_2", group, Long.valueOf(222L)));   // 绑定 Bot B
        Map<String, Object> p = prefs(
            "sent_at_g_1", System.currentTimeMillis(),
            "sent_at_g_2", System.currentTimeMillis());
        // Bot B 回复 → 应命中 g_2，而不是 sent 更大的那个
        Result r = resolve(l, group, 222L, true, 0, p);
        eq("BOT_DID 选中绑定该 bot 的目标", r.targetId, "g_2");
        eq("方法为 BOT_DID", r.method, "BOT_DID");
    }

    private static void peerTtlUnique() {
        long group = -100L;
        List<T> l = new ArrayList<T>();
        l.add(new T("g_1", group, null));
        Map<String, Object> p = prefs("sent_at_g_1", System.currentTimeMillis());
        Result r = resolve(l, group, 555L, true, 0, p);
        eq("唯一候选 → PEER_TTL", r.method, "PEER_TTL");
        eq("唯一候选选中 g_1", r.targetId, "g_1");
    }

    private static void peerTtlAmbiguous() {
        long group = -100L;
        List<T> l = new ArrayList<T>();
        l.add(new T("g_1", group, null));
        l.add(new T("g_2", group, null));
        l.add(new T("g_3", group, null));
        Map<String, Object> p = prefs(
            "sent_at_g_1", System.currentTimeMillis(),
            "sent_at_g_2", System.currentTimeMillis(),
            "sent_at_g_3", System.currentTimeMillis());
        Result r = resolve(l, group, 999L, true, 0, p);
        eq("多候选 → 不关联（target=null）", r.targetId, null);
        eq("多候选 → method=AMBIGUOUS", r.method, "AMBIGUOUS");
        eq("候选数记录为 3", r.candidateCount, 3);
    }

    /** 2026-10-09：两个候选都新鲜，但一个明显更新（>60s）→ 取最新那条，不再死锁在 AMBIGUOUS。 */
    private static void peerTtlNewestDisambig() {
        long group = -100L;
        long now = System.currentTimeMillis();
        List<T> l = new ArrayList<T>();
        l.add(new T("g_1", group, null));
        l.add(new T("g_2", group, null));
        Map<String, Object> p = prefs(
            "sent_at_g_1", Long.valueOf(now - 5 * 60 * 1000L),   // 5 分钟前
            "sent_at_g_2", Long.valueOf(now - 3 * 1000L));       // 3 秒前
        Result r = resolve(l, group, 999L, true, 0, p);
        eq("明显最新鲜 → 选中 g_2", r.targetId, "g_2");
        eq("方法为 PEER_TTL_NEWEST", r.method, "PEER_TTL_NEWEST");
    }

    /** 2026-10-09：写法定不同的同群条目，精确匹配优先，不被 -X/-100X 归一误伤。 */
    private static void peerExactPrefersSameEncoding() {
        long superGroup = -1001234567890L;
        List<T> l = new ArrayList<T>();
        l.add(new T("other", -1234567890L, null));       // 另一个「裸写」群（归一后会撞号）
        l.add(new T("mine", superGroup, null));          // 本会话，精确相等
        long now = System.currentTimeMillis();
        Map<String, Object> p = prefs(
            "sent_at_other", Long.valueOf(now),
            "sent_at_mine", Long.valueOf(now));
        Result r = resolve(l, superGroup, 999L, true, 0, p);
        eq("精确匹配优先 → 只认 mine", r.targetId, "mine");
    }

    /**
     * 记录一条**已知局限**：channelIdOf 的 -X/-100X 归一在数值上无法区分类型。
     * 普通群 -1234567890 与超级群 -1001234567890 会被归一为同一会话。
     * 缓解手段是「精确优先」（见上一条用例）：只有当目标存的是另一种写法时才会走到归一。
     * 本用例把该行为钉住，避免以后有人误以为它是 bug 而"修"成更宽松的实现。
     */
    private static void channelIdCollisionDoc() {
        eq("普通群 channelIdOf", Long.valueOf(channelIdOf(-1234567890L)), Long.valueOf(1234567890L));
        eq("超级群 channelIdOf", Long.valueOf(channelIdOf(-1001234567890L)), Long.valueOf(1234567890L));
        tru("两种写法归一到同一 channel_id（已知局限）",
            channelIdOf(-1234567890L) == channelIdOf(-1001234567890L));
        tru("-X 与 -100X 归一：同一会话的两种写法应匹配",
            samePeerDid(-1814986730L, -1001814986730L));
    }

    /** 与 Core.channelIdOf 同语义。 */
    static long channelIdOf(long did) {
        if (did >= 0L) return 0L;
        long v = -did;
        if (v > 1000000000000L) return v - 1000000000000L;
        return v;
    }

    /** 与 Core.samePeerDid 同语义。 */
    static boolean samePeerDid(long a, long b) {
        if (a == b) return true;
        if (a == 0L || b == 0L) return false;
        if (a > 0L || b > 0L) return false;
        return channelIdOf(a) == channelIdOf(b) && channelIdOf(a) != 0L;
    }

    private static void groupNoFallback() {
        long group = -100L;
        List<T> l = new ArrayList<T>();
        l.add(new T("g_1", group, null));
        // 没有 sent_at（本轮没发过请求）→ 群聊不得兜底
        Result r = resolve(l, group, 555L, true, 0, prefs());
        eq("群聊无 pending → 不关联", r.targetId, null);
        eq("群聊无 pending → method=NONE", r.method, "NONE");
    }

    private static void privateFallback() {
        long bot = 555L;
        List<T> l = new ArrayList<T>();
        l.add(new T("b_1", bot, null));
        Result r = resolve(l, bot, bot, false, 0, prefs());
        eq("私聊兜底 → FALLBACK", r.method, "FALLBACK");
        eq("私聊兜底选中 b_1", r.targetId, "b_1");
    }
}
