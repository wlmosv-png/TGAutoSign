package io.github.wlmosv_png.tgautosign.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宿主符号**运行时自动定位**（2026-11-12 引入）。
 *
 * <p>判据与 {@code tools/hostmap-gen.py} 同源（离线生成映射表用的就是这一套），
 * 区别只是数据来源从 apk-index 换成 {@link DexReader} 的纯字节解析。
 * <b>两边改动必须同步</b>，否则会出现「离线能生成、线上定不出」的裂缝。
 *
 * <p>为什么不能靠映射表：Telegram 每个版本都重排 {@code org.telegram.messenger.*}
 * 类名，任何逐版本硬编码都必然过期（13.2.1 的表在 13.2.2 上整表失配，锚点 A/B 全不安装）。
 * 本类让模块**自己找**，只有结构（方法签名/常量字符串）真变了才需要人工介入。
 */
public final class HostLocator {

    /** 需要定位的 9 个符号（与 HostSymbols.CLASS_MAPS 的键一致）。 */
    public static final String[] LEGACY = {
            "MessagesController", "NotificationCenter", "BaseController", "UserConfig",
            "SharedConfig", "MessagesStorage", "DialogObject", "SendMessagesHelper",
            "UserNameResolver",
    };

    private static final String PKG = "org.telegram.messenger.";
    private static final String BC_FQN = PKG + "BaseController";
    private static final String OBJECT = "java.lang.Object";

    public static final class Result {
        public final Map<String, String> found = new LinkedHashMap<>();
        public final List<String> notes = new ArrayList<>();
        public final Set<String> candidates = new LinkedHashSet<>();
        public long scanMs;

        public boolean complete() {
            for (String k : LEGACY) if (found.get(k) == null) return false;
            return true;
        }

        public int missing() {
            int n = 0;
            for (String k : LEGACY) if (found.get(k) == null) n++;
            return n;
        }
    }

    private HostLocator() {}

    public static Result locate(DexReader r) {
        Result res = new Result();
        if (r == null) {
            res.notes.add("DexReader 为空（宿主 APK 读不到）");
            return res;
        }
        long t0 = System.currentTimeMillis();
        try {
            run(r, res);
        } catch (Throwable t) {
            res.notes.add("定位异常: " + t);
        }
        res.scanMs = System.currentTimeMillis() - t0;
        return res;
    }

    private static void run(DexReader r, Result res) {
        Set<String> cands = shortTopLevelClasses(r);
        res.candidates.addAll(cands);

        // ① MessagesController —— 全包唯一 (ArrayList,ArrayList,ArrayList,boolean,int)->boolean
        List<String> mc = new ArrayList<>();
        for (String c : cands) {
            for (DexReader.MethodInfo m : r.methodsOf(c)) {
                if ("(Ljava/util/ArrayList;Ljava/util/ArrayList;Ljava/util/ArrayList;ZI)".equals(m.params)
                        && "Z".equals(m.ret)) { mc.add(c); break; }
            }
        }
        if (mc.size() != 1) {
            res.notes.add("MessagesController 候选=" + mc);
            return;                                  // 后面全部依赖它，定不出就止损
        }
        String mcCls = mc.get(0);
        res.found.put("MessagesController", mcCls);

        // ② BaseController —— MessagesController 的父类
        String bc = r.superOf(mcCls);
        if (bc == null || !bc.startsWith(PKG)) {
            res.notes.add("BaseController: " + mcCls + " 的父类异常（" + bc + "）");
            return;
        }
        res.found.put("BaseController", bc);

        // ③ NotificationCenter —— (int,Object[])->void 且体内调用 SparseArray.keyAt(I)I
        List<String> nc = new ArrayList<>();
        for (String c : cands) {
            boolean sig = false;
            for (DexReader.MethodInfo m : r.methodsOf(c)) {
                if ("(I[Ljava/lang/Object;)".equals(m.params) && "V".equals(m.ret)) { sig = true; break; }
            }
            if (!sig) continue;
            for (String ref : r.invokeRefsOf(c)) {
                if (ref != null && ref.contains("keyAt")) { nc.add(c); break; }
            }
        }
        pick(res, "NotificationCenter", nc);

        // ④ UserConfig —— 引用字符串 "userconfing"（TG 的历史拼写）且父类是 BaseController
        List<String> uc = new ArrayList<>();
        for (String c : cands) {
            if (!bc.equals(r.superOf(c))) continue;
            if (r.stringRefsOf(c).contains("userconfing")) uc.add(c);
        }
        pick(res, "UserConfig", uc);

        // ⑤ MessagesStorage —— 引用 "cache4.db" 且父类是 BaseController
        List<String> ms = new ArrayList<>();
        for (String c : cands) {
            if (!bc.equals(r.superOf(c))) continue;
            if (r.stringRefsOf(c).contains("cache4.db")) ms.add(c);
        }
        pick(res, "MessagesStorage", ms);

        // ⑥ SharedConfig —— 引用 "mainconfig" 且父类为 Object；多个时取字段最多的（超宽配置类）
        List<String> sc = new ArrayList<>();
        for (String c : cands) {
            if (!OBJECT.equals(r.superOf(c))) continue;
            if (r.stringRefsOf(c).contains("mainconfig")) sc.add(c);
        }
        if (sc.size() > 1) {
            String best = null;
            int bestN = -1;
            for (String c : sc) {
                int n = r.fieldsOf(c).size();
                if (n >= 50 && n > bestN) { bestN = n; best = c; }
            }
            sc = best == null ? new ArrayList<String>() : java.util.Collections.singletonList(best);
        }
        pick(res, "SharedConfig", sc);

        // ⑦ DialogObject —— 同一个类上同时有 static (Peer)->long 与 static (InputPeer)->long
        List<String> dob = new ArrayList<>();
        for (String c : cands) {
            boolean a = false, b = false;
            for (DexReader.MethodInfo m : r.methodsOf(c)) {
                if (!m.isStatic) continue;
                if ("(Lorg/telegram/tgnet/TLRPC$Peer;)".equals(m.params) && "J".equals(m.ret)) a = true;
                if ("(Lorg/telegram/tgnet/TLRPC$InputPeer;)".equals(m.params) && "J".equals(m.ret)) b = true;
            }
            if (a && b) dob.add(c);
        }
        pick(res, "DialogObject", dob);

        String selfDesc = "L" + mcCls.replace('.', '/') + ";";
        String bcCtor = "L" + bc.replace('.', '/') + ";";

        // ⑧ SendMessagesHelper —— BaseController 子类 + static(int)->自身 + ≥10 参方法 + invoke BC <init>(I)
        // 注意：selfStatic / big 只看**单字母方法名**的集合 —— 与 hostmap-gen.py 的
        // `members(..., "^[A-Za-z]$")` 同源。不加这条收窄，13.2.2 上有 8 个候选；
        // 加上后唯一命中 rz。（R8 会把次要方法压成 a/b/c…，这是它的稳定副产物。）
        List<String> smh = new ArrayList<>();
        for (String c : cands) {
            if (!bc.equals(r.superOf(c))) continue;
            boolean selfStatic = false, big = false;
            for (DexReader.MethodInfo m : r.methodsOf(c)) {
                if (m.name == null || m.name.length() != 1) continue;
                if (m.isStatic && "(I)".equals(m.params)
                        && ("L" + c.replace('.', '/') + ";").equals(m.ret)) selfStatic = true;
                if (m.paramCount >= 10) big = true;
            }
            if (!selfStatic || !big) continue;
            // 追加成员判据（同 HostSymbols.METHOD_RET_HINTS 记录的 SMH 成员）：
            // 取"待发消息 id" (long)->long 与 (int,boolean)->void 两个签名，
            // 把 d20/nt/c5/w7 这类同为 BC 子类的宽类排除掉。
            boolean hasSendingId = false, hasRemoveSending = false;
            for (DexReader.MethodInfo m : r.methodsOf(c)) {
                if ("(J)".equals(m.params) && "J".equals(m.ret)) hasSendingId = true;
                if ("(IZ)".equals(m.params) && "V".equals(m.ret)) hasRemoveSending = true;
            }
            if (!hasSendingId || !hasRemoveSending) continue;
            boolean callsBcCtor = false;
            for (String ref : r.invokeRefsOf(c)) {
                if (ref != null && ref.startsWith(bcCtor) && ref.contains("-><init>(I)")) { callsBcCtor = true; break; }
            }
            if (callsBcCtor) smh.add(c);
        }
        pick(res, "SendMessagesHelper", smh);

        // ⑨ UserNameResolver —— 唯一 new TL_contacts_resolveUsername 的类
        List<String> unr = new ArrayList<>();
        for (String c : cands) {
            for (String ref : r.invokeRefsOf(c)) {
                if (ref != null && ref.contains("TL_contacts_resolveUsername;-><init>")) { unr.add(c); break; }
            }
        }
        pick(res, "UserNameResolver", unr);

        // 兜底标注：把没定出来的原因写清楚，便于人工判断
        for (String k : LEGACY) {
            if (!res.found.containsKey(k)) res.notes.add(k + " 未定位");
        }
    }

    /** 唯一才采用；多候选/零候选都记 note 并放弃（宁可留空，也不猜）。 */
    private static void pick(Result res, String legacy, List<String> cands) {
        if (cands.size() == 1) {
            res.found.put(legacy, cands.get(0));
        } else {
            res.notes.add(legacy + " 候选数=" + cands.size() + "（" + cands + "）");
        }
    }

    /**
     * 候选类：{@code org.telegram.messenger} 包下的**顶层短名类**。
     * R8 产出的混淆名一律 ≤4 字符（wp/zs/d1/d20/g20…），据此把两万类收窄到千级。
     */
    private static Set<String> shortTopLevelClasses(DexReader r) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : r.classes()) {
            if (!c.startsWith(PKG)) continue;
            String s = c.substring(PKG.length());
            if (s.indexOf('$') >= 0 || s.indexOf('.') >= 0) continue;
            if (s.length() <= 4) out.add(c);
        }
        return out;
    }
}
