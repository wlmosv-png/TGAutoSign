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
        for (T m : list) {
            if (m.did != peerDid) continue;
            if (group && fromDid != 0L && m.botDid != null && m.botDid != 0L && m.botDid != fromDid) continue;
            Object s = prefs.get("sent_at_" + m.id);
            long sent = s instanceof Number ? ((Number) s).longValue() : 0L;
            if (sent <= 0L) continue;
            if (now - sent > PENDING_TTL_MS) continue;
            cand++; only = m;
        }
        if (cand == 1 && only != null) { r.targetId = only.id; r.method = "PEER_TTL"; return r; }
        if (cand > 1) { r.targetId = null; r.method = "AMBIGUOUS"; r.candidateCount = cand; return r; }

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
