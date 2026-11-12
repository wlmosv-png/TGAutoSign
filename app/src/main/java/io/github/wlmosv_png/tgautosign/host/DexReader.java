package io.github.wlmosv_png.tgautosign.host;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 宿主 dex 的**静态结构**读取器（2026-11-12 引入，为「宿主符号自愈」而建）。
 *
 * <p>为什么需要：Telegram 13.2.0 起 R8 每版重排 {@code org.telegram.messenger.*} 的类名，
 * 旧办法（{@code HostSymbols.CLASS_MAPS} 逐版本硬编码）**必然过期** ——
 * 2026-11-12 真机实证：13.2.1 的表在 13.2.2 上整表失配，锚点 A/B 全不安装。
 * 本类让模块能在**运行时自己把类找回来**，不再依赖人工补表。
 *
 * <p><b>设计红线（必须守住）</b>
 * <ul>
 *   <li><b>零 Android 依赖</b>：只用 {@code java.util} / {@code java.util.zip}，
 *       这样 Linux 侧 javac+java 就能拿真实 APK 做端到端回归（见 tools/tests/HostLocateTest.java）。</li>
 *   <li><b>不加载类</b>：纯字节解析。绝不用 {@code Class.forName} 探路 ——
 *       那会触发静态初始化并付出 classloading 代价，且在解析阶段可能炸宿主。</li>
 *   <li><b>只读、可失败</b>：任何异常都退化成「字段为空」，绝不抛出影响宿主。</li>
 *   <li><b>按 dex 分段</b>：APK 里的 classes.dex / classes2.dex … 各有独立的
 *       string/type/proto/field/method 表。跨 dex 混用索引会读到垃圾 —— 实踩过。</li>
 * </ul>
 */
public final class DexReader {

    // ───────────────────────── 单个 dex 的全部索引 ─────────────────────────

    private static final class Inner {
        byte[] buf;
        String[] strings;
        String[] types;
        int[] protoParamsOff;
        String[] protoRet;
        int[] fieldClass, fieldType, fieldNameOff;
        int[] methodClass, methodProto, methodNameOff;
        final Map<String, Set<String>> stringRefs = new HashMap<>();
        final Map<String, Set<String>> invokeRefs = new HashMap<>();
    }

    public static final class MethodInfo {
        final Inner in;
        public final String cls;
        public final String name;
        public final String params;      // "(ILjava/lang/String;)"
        public final String ret;         // "V" / "I" / "Lfoo/Bar;"
        public final boolean isStatic;
        public final boolean isCtor;
        public final int paramCount;
        final int codeOff;

        MethodInfo(Inner in, String cls, String name, String params, String ret,
                   boolean isStatic, boolean isCtor, int paramCount, int codeOff) {
            this.in = in; this.cls = cls; this.name = name; this.params = params; this.ret = ret;
            this.isStatic = isStatic; this.isCtor = isCtor;
            this.paramCount = paramCount; this.codeOff = codeOff;
        }

        public String signature() {
            return (isStatic ? "static " : "") + params + ret;
        }
    }

    public static final class FieldInfo {
        public final String cls;
        public final String name;
        public final String type;
        public final boolean isStatic;
        public final boolean isFinal;

        FieldInfo(String cls, String name, String type, boolean isStatic, boolean isFinal) {
            this.cls = cls; this.name = name; this.type = type;
            this.isStatic = isStatic; this.isFinal = isFinal;
        }
    }

    private static final class ClsDef {
        Inner in;
        int superIdx;
        int classDataOff;

        ClsDef(Inner in, int superIdx, int classDataOff) {
            this.in = in; this.superIdx = superIdx; this.classDataOff = classDataOff;
        }
    }

    private final List<Inner> inners = new ArrayList<>();
    private final Map<String, ClsDef> classDefs = new LinkedHashMap<>();
    private final Map<String, List<MethodInfo>> methods = new LinkedHashMap<>();
    private final Map<String, List<FieldInfo>> fields = new LinkedHashMap<>();

    // 指令长度表（单位：16-bit code units），Dalvik 字节码规范 §instruction formats。
    private static final int[] INSNS_LEN = buildInsnsLen();

    private static int[] buildInsnsLen() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) t[i] = 1;
        t[0x02] = 2; t[0x03] = 3; t[0x08] = 2; t[0x09] = 3;
        t[0x13] = 2; t[0x14] = 3; t[0x15] = 2; t[0x16] = 2; t[0x17] = 3; t[0x18] = 5;
        t[0x19] = 2; t[0x1a] = 2; t[0x1b] = 3; t[0x1c] = 2;
        t[0x1f] = 2; t[0x20] = 2; t[0x22] = 2; t[0x23] = 2; t[0x24] = 3; t[0x25] = 3; t[0x26] = 3;
        t[0x29] = 2; t[0x2a] = 3; t[0x2b] = 3; t[0x2c] = 3;
        for (int i = 0x2d; i <= 0x3d; i++) t[i] = 2;
        for (int i = 0x44; i <= 0x6d; i++) t[i] = 2;
        t[0x6e] = 3; t[0x6f] = 3; t[0x70] = 3; t[0x71] = 3; t[0x72] = 3;
        t[0x74] = 3; t[0x75] = 3; t[0x76] = 3; t[0x77] = 3; t[0x78] = 3;
        for (int i = 0x90; i <= 0xaf; i++) t[i] = 2;
        for (int i = 0xd0; i <= 0xe2; i++) t[i] = 2;
        t[0xfa] = 4; t[0xfb] = 4; t[0xfc] = 4; t[0xfd] = 4; t[0xfe] = 2; t[0xff] = 2;
        return t;
    }

    private DexReader() {}

    // ───────────────────────── 入口 ─────────────────────────

    /** 从 APK 路径读取（可传多个：base + split）。失败返回 null。 */
    public static DexReader fromApk(String... apkPaths) {
        DexReader r = new DexReader();
        for (String p : apkPaths) {
            if (p == null || p.isEmpty()) continue;
            r.readApk(p);
        }
        return r.classDefs.isEmpty() ? null : r;
    }

    private void readApk(String path) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(path);
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.equals("classes.dex") || (n.startsWith("classes") && n.endsWith(".dex"))) names.add(n);
            }
            Collections.sort(names);
            for (String n : names) {
                byte[] data = readEntry(zf, n);
                if (data == null) continue;
                readDex(data);
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] readEntry(ZipFile zf, String name) {
        InputStream in = null;
        try {
            in = zf.getInputStream(zf.getEntry(name));
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
            byte[] tmp = new byte[1 << 16];
            int k;
            while ((k = in.read(tmp)) > 0) bo.write(tmp, 0, k);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private void readDex(byte[] b) {
        try {
            if (b == null || b.length < 112) return;
            if (!(b[0] == 'd' && b[1] == 'e' && b[2] == 'x' && b[3] == 0x0a)) return;
            Inner in = new Inner();
            in.buf = b;
            int stringIdsSize = u4(b, 0x38), stringIdsOff = u4(b, 0x3c);
            int typeIdsSize = u4(b, 0x40), typeIdsOff = u4(b, 0x44);
            int protoIdsSize = u4(b, 0x48), protoIdsOff = u4(b, 0x4c);
            int fieldIdsSize = u4(b, 0x50), fieldIdsOff = u4(b, 0x54);
            int methodIdsSize = u4(b, 0x58), methodIdsOff = u4(b, 0x5c);
            int classDefsSize = u4(b, 0x60), classDefsOff = u4(b, 0x64);

            in.strings = new String[stringIdsSize];
            for (int i = 0; i < stringIdsSize; i++) {
                in.strings[i] = readString(b, u4(b, stringIdsOff + i * 4));
            }
            in.types = new String[typeIdsSize];
            for (int i = 0; i < typeIdsSize; i++) {
                int idx = u4(b, typeIdsOff + i * 4);
                in.types[i] = (idx >= 0 && idx < in.strings.length) ? in.strings[idx] : null;
            }
            in.protoParamsOff = new int[protoIdsSize];
            in.protoRet = new String[protoIdsSize];
            for (int i = 0; i < protoIdsSize; i++) {
                int base = protoIdsOff + i * 12;
                in.protoParamsOff[i] = u4(b, base + 8);
                int retIdx = u4(b, base + 4);
                in.protoRet[i] = (retIdx >= 0 && retIdx < in.types.length) ? in.types[retIdx] : null;
            }
            in.fieldClass = new int[fieldIdsSize];
            in.fieldType = new int[fieldIdsSize];
            in.fieldNameOff = new int[fieldIdsSize];
            for (int i = 0; i < fieldIdsSize; i++) {
                int base = fieldIdsOff + i * 8;
                in.fieldClass[i] = u2(b, base);
                in.fieldType[i] = u2(b, base + 2);
                in.fieldNameOff[i] = u4(b, base + 4);
            }
            in.methodClass = new int[methodIdsSize];
            in.methodProto = new int[methodIdsSize];
            in.methodNameOff = new int[methodIdsSize];
            for (int i = 0; i < methodIdsSize; i++) {
                int base = methodIdsOff + i * 8;
                in.methodClass[i] = u2(b, base);
                in.methodProto[i] = u2(b, base + 2);
                in.methodNameOff[i] = u4(b, base + 4);
            }
            inners.add(in);

            for (int i = 0; i < classDefsSize; i++) {
                int base = classDefsOff + i * 32;
                String cls = descToName(typeAt(in, u4(b, base)));
                if (cls == null) continue;
                int superIdx = u4(b, base + 8);
                int classDataOff = u4(b, base + 24);
                if (!classDefs.containsKey(cls)) classDefs.put(cls, new ClsDef(in, superIdx, classDataOff));
            }
            // 类数据单独走一遍，保证 merge 语义（后一个 dex 不覆盖已有类）
            for (Map.Entry<String, ClsDef> e : classDefs.entrySet()) {
                ClsDef cd = e.getValue();
                if (cd.in == in && cd.classDataOff != 0 && !methods.containsKey(e.getKey())) {
                    readClassData(in, e.getKey(), cd.classDataOff);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void readClassData(Inner in, String cls, int off) {
        try {
            int[] pos = new int[]{off};
            int staticFields = uleb(in.buf, pos);
            int instFields = uleb(in.buf, pos);
            int directMethods = uleb(in.buf, pos);
            int virtMethods = uleb(in.buf, pos);
            List<FieldInfo> fl = new ArrayList<>();
            int idx = 0;
            for (int i = 0; i < staticFields; i++) {
                idx += uleb(in.buf, pos);
                fl.add(fieldInfo(in, cls, idx, uleb(in.buf, pos)));
            }
            idx = 0;
            for (int i = 0; i < instFields; i++) {
                idx += uleb(in.buf, pos);
                fl.add(fieldInfo(in, cls, idx, uleb(in.buf, pos)));
            }
            fields.put(cls, fl);

            List<MethodInfo> ml = new ArrayList<>();
            idx = 0;
            for (int i = 0; i < directMethods; i++) {
                idx += uleb(in.buf, pos);
                int flags = uleb(in.buf, pos);
                ml.add(methodInfo(in, cls, idx, flags, uleb(in.buf, pos)));
            }
            idx = 0;
            for (int i = 0; i < virtMethods; i++) {
                idx += uleb(in.buf, pos);
                int flags = uleb(in.buf, pos);
                ml.add(methodInfo(in, cls, idx, flags, uleb(in.buf, pos)));
            }
            methods.put(cls, ml);
        } catch (Throwable ignored) {
        }
    }

    private FieldInfo fieldInfo(Inner in, String cls, int fi, int flags) {
        try {
            if (fi < 0 || fi >= in.fieldClass.length) return new FieldInfo(cls, "?", "?", false, false);
            String t = descToName(typeAt(in, in.fieldType[fi]));
            return new FieldInfo(cls, stringAt(in, in.fieldNameOff[fi]), t == null ? "?" : t,
                    (flags & 0x8) != 0, (flags & 0x10) != 0);
        } catch (Throwable t) {
            return new FieldInfo(cls, "?", "?", false, false);
        }
    }

    private MethodInfo methodInfo(Inner in, String cls, int mi, int flags, int codeOff) {
        try {
            if (mi < 0 || mi >= in.methodClass.length) {
                return new MethodInfo(in, cls, "?", "()", "V", false, false, 0, 0);
            }
            int pIdx = in.methodProto[mi];
            String params = paramsDesc(in, pIdx);
            String ret = (pIdx >= 0 && pIdx < in.protoRet.length && in.protoRet[pIdx] != null) ? in.protoRet[pIdx] : "V";
            String name = stringAt(in, in.methodNameOff[mi]);
            boolean isStatic = (flags & 0x8) != 0;
            boolean isCtor = "<init>".equals(name);
            int pc = countParams(params);
            if (!isStatic && !isCtor) pc += 1;
            return new MethodInfo(in, cls, name == null ? "?" : name, params, ret, isStatic, isCtor, pc, codeOff);
        } catch (Throwable t) {
            return new MethodInfo(in, cls, "?", "()", "V", false, false, 0, 0);
        }
    }

    // ───────────────────────── 查询 API ─────────────────────────

    public Set<String> classes() { return new LinkedHashSet<>(classDefs.keySet()); }

    public int classCount() { return classDefs.size(); }

    public String superOf(String cls) {
        ClsDef cd = classDefs.get(cls);
        if (cd == null) return null;
        return descToName(typeAt(cd.in, cd.superIdx));
    }

    public boolean isSubclassOf(String cls, String ancestor) {
        String c = cls;
        for (int i = 0; i < 12 && c != null; i++) {
            if (c.equals(ancestor)) return true;
            c = superOf(c);
        }
        return false;
    }

    public List<MethodInfo> methodsOf(String cls) {
        List<MethodInfo> l = methods.get(cls);
        return l == null ? Collections.<MethodInfo>emptyList() : l;
    }

    public List<FieldInfo> fieldsOf(String cls) {
        List<FieldInfo> l = fields.get(cls);
        return l == null ? Collections.<FieldInfo>emptyList() : l;
    }

    /** 类体内引用的字符串常量（const-string），用**该类所属 dex** 的字符串表解释。 */
    public Set<String> stringRefsOf(String cls) {
        List<MethodInfo> ms = methodsOf(cls);
        if (ms.isEmpty()) return Collections.emptySet();
        Inner in = ms.get(0).in;
        Set<String> cached = in.stringRefs.get(cls);
        if (cached != null) return cached;
        Set<String> out = new LinkedHashSet<>();
        for (MethodInfo m : ms) {
            if (m.codeOff == 0) continue;
            for (int s : insnRefs(in, m.codeOff, true)) {
                if (s >= 0 && s < in.strings.length && in.strings[s] != null) out.add(in.strings[s]);
            }
        }
        in.stringRefs.put(cls, out);
        return out;
    }

    /** 类体内 invoke 的目标方法引用，形如 {@code Lfoo/Bar;->baz(I)V}。 */
    public Set<String> invokeRefsOf(String cls) {
        List<MethodInfo> ms = methodsOf(cls);
        if (ms.isEmpty()) return Collections.emptySet();
        Inner in = ms.get(0).in;
        Set<String> cached = in.invokeRefs.get(cls);
        if (cached != null) return cached;
        Set<String> out = new LinkedHashSet<>();
        for (MethodInfo m : ms) {
            if (m.codeOff == 0) continue;
            for (int s : insnRefs(in, m.codeOff, false)) {
                String ref = methodRefString(in, s);
                if (ref != null) out.add(ref);
            }
        }
        in.invokeRefs.put(cls, out);
        return out;
    }

    private List<Integer> insnRefs(Inner in, int codeOff, boolean wantString) {
        List<Integer> out = new ArrayList<>();
        try {
            byte[] b = in.buf;
            int insnsSize = u4(b, codeOff + 12);
            int insnsOff = codeOff + 16;
            int i = 0;
            while (i < insnsSize) {
                int unit = u2(b, insnsOff + i * 2);
                int op = unit & 0xff;
                // 0x00 是特例：高字节非 0 时是 payload 伪指令，不是 nop。
                // 2026-11-12 踩过：按「nop 长度 1」跳 → 后续指令全部错位。
                if (op == 0x00) {
                    if (unit == 0x0000) { i += 1; continue; }
                    if (unit == 0x0100) {
                        i += 2 + u2(b, insnsOff + (i + 1) * 2) * 2; continue;
                    }
                    if (unit == 0x0200) {
                        i += 2 + u2(b, insnsOff + (i + 1) * 2) * 4; continue;
                    }
                    if (unit == 0x0300) {
                        int width = u2(b, insnsOff + (i + 1) * 2);
                        int size = u4(b, insnsOff + (i + 2) * 2);
                        i += 4 + (size * width + 1) / 2; continue;
                    }
                    i += 1; continue;
                }
                int len = INSNS_LEN[op];
                if (len <= 0) len = 1;
                if (i + len > insnsSize) break;
                if (wantString) {
                    if (op == 0x1a) {
                        out.add(u2(b, insnsOff + (i + 1) * 2));
                    } else if (op == 0x1b) {
                        out.add(u2(b, insnsOff + (i + 1) * 2) | (u2(b, insnsOff + (i + 2) * 2) << 16));
                    }
                } else if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                    out.add(u2(b, insnsOff + (i + 1) * 2));
                }
                i += len;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 方法索引 → "Lcls;->name(params)ret"（用该 dex 自己的表解释）。 */
    public String methodRefString(int methodIdx) {
        for (Inner in : inners) {
            String s = methodRefString(in, methodIdx);
            if (s != null) return s;
        }
        return null;
    }

    private String methodRefString(Inner in, int methodIdx) {
        try {
            if (methodIdx < 0 || methodIdx >= in.methodClass.length) return null;
            String cls = typeAt(in, in.methodClass[methodIdx]);
            String name = stringAt(in, in.methodNameOff[methodIdx]);
            int pIdx = in.methodProto[methodIdx];
            return cls + "->" + name + paramsDesc(in, pIdx) + in.protoRet[pIdx];
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private String typeAt(Inner in, int idx) {
        if (idx < 0 || idx >= in.types.length) return null;
        return in.types[idx];
    }

    private static String stringAt(Inner in, int idx) {
        if (idx < 0 || idx >= in.strings.length) return null;
        return in.strings[idx];
    }

    private String paramsDesc(Inner in, int protoIdx) {
        try {
            if (protoIdx < 0 || protoIdx >= in.protoParamsOff.length) return "()";
            int off = in.protoParamsOff[protoIdx];
            StringBuilder sb = new StringBuilder("(");
            if (off != 0) {
                int size = u4(in.buf, off);
                for (int i = 0; i < size; i++) sb.append(typeAt(in, u2(in.buf, off + 4 + i * 2)));
            }
            sb.append(')');
            return sb.toString();
        } catch (Throwable t) {
            return "()";
        }
    }

    private static int countParams(String p) {
        if (p == null) return 0;
        int n = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '[') continue;
            if (c == 'L') { n++; i = p.indexOf(';', i); if (i < 0) break; }
            else n++;
        }
        return n;
    }

    /** "Lfoo/Bar;" → "foo.Bar"；基本类型原样返回。 */
    public static String descToName(String d) {
        if (d == null) return null;
        if (d.startsWith("L") && d.endsWith(";")) return d.substring(1, d.length() - 1).replace('/', '.');
        return d;
    }

    private static int u2(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int u4(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int uleb(byte[] b, int[] pos) {
        int r = 0, sh = 0;
        while (true) {
            int x = b[pos[0]++] & 0xff;
            r |= (x & 0x7f) << sh;
            if ((x & 0x80) == 0) break;
            sh += 7;
            if (sh > 28) break;
        }
        return r;
    }

    /** string_data_item：uleb128 utf16_size + MUTF-8 字节。 */
    private static String readString(byte[] b, int off) {
        try {
            int[] pos = new int[]{off};
            int n = 0, sh = 0;
            while (true) {
                int x = b[pos[0]++] & 0xff;
                n |= (x & 0x7f) << sh;
                if ((x & 0x80) == 0) break;
                sh += 7;
                if (sh > 28) break;
            }
            char[] out = new char[Math.max(n, 1)];
            int o = 0, i = pos[0];
            while (o < n && i < b.length) {
                int a = b[i++] & 0xff;
                if (a == 0) break;
                if (a < 0x80) {
                    out[o++] = (char) a;
                } else if ((a & 0xe0) == 0xc0) {
                    if (i >= b.length) break;
                    out[o++] = (char) (((a & 0x1f) << 6) | (b[i++] & 0x3f));
                } else if ((a & 0xf0) == 0xe0) {
                    if (i + 1 >= b.length) break;
                    out[o++] = (char) (((a & 0x0f) << 12) | ((b[i++] & 0x3f) << 6) | (b[i++] & 0x3f));
                } else {
                    out[o++] = '?';
                }
            }
            return new String(out, 0, o);
        } catch (Throwable t) {
            return null;
        }
    }
}
