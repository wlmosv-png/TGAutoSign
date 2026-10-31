package io.github.wlmosv_png.tgautosign.persist;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 真实文件版 SharedPreferences（测试用，2026-11-01 阶段 2）。
 *
 * 为什么自己实现而不是 mock：
 *   用户明确要求「必须使用真实生产状态转换和持久化方法…不能只 mock Store」。
 *   SignStateStore 依赖的是 android.content.SharedPreferences —— 这是个**接口**，
 *   因此这里实现一份行为等价的文件实现：
 *     · 真实的 XML 序列化与落盘（格式与 Android 原生一致，可互认）
 *     · 真实的"重新加载"：新建实例读同一文件 = 模拟进程重启
 *     · commit() 同步落盘（与生产 PrefsStore 依赖的语义一致）
 *   走的是**生产 SignStateStore 的原封代码路径**，只替换了底层存储介质。
 */
public final class FileSharedPreferences implements SharedPreferences {

    private final File file;
    private final Map<String, Object> map = new LinkedHashMap<String, Object>();

    public FileSharedPreferences(File file) {
        this.file = file;
        load();
    }

    /** 从磁盘读取（也用于模拟"进程重启后重新加载"）。 */
    public void load() {
        map.clear();
        if (!file.exists()) return;
        try {
            String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            parse(xml);
        } catch (Throwable ignored) {}
    }

    public File file() { return file; }

    // ───────── 极简 XML 解析：仅覆盖 SharedPreferences 的写法 ─────────
    private void parse(String xml) {
        int p = 0;
        while (true) {
            int lt = xml.indexOf("<", p);
            if (lt < 0) break;
            int gt = xml.indexOf(">", lt);
            if (gt < 0) break;
            String tag = xml.substring(lt + 1, gt).trim();
            if (tag.startsWith("?") || tag.startsWith("map") || tag.startsWith("/")) { p = gt + 1; continue; }

            String name = attr(tag, "name");
            String type = tag.split("\\s+")[0];
            String value = attr(tag, "value");
            if (name != null) {
                try {
                    switch (type) {
                        case "string": {
                            // 字符串元素的形式是 <string name="k">value</string>
                            int end = xml.indexOf("</string>", gt);
                            String v = end > 0 ? xml.substring(gt + 1, end) : "";
                            map.put(name, unescape(v));
                            p = end > 0 ? end + 9 : gt + 1;
                            continue;
                        }
                        case "int":    map.put(name, Integer.parseInt(value)); break;
                        case "long":   map.put(name, Long.parseLong(value)); break;
                        case "float":  map.put(name, Float.parseFloat(value)); break;
                        case "boolean":map.put(name, Boolean.parseBoolean(value)); break;
                        case "set":    map.put(name, new java.util.LinkedHashSet<String>()); break;
                        default: break;
                    }
                } catch (Throwable ignored) {}
            }
            p = gt + 1;
        }
    }

    private static String attr(String tag, String key) {
        int i = tag.indexOf(key + "=\"");
        if (i < 0) return null;
        int s = i + key.length() + 2;
        int e = tag.indexOf('"', s);
        return e < 0 ? null : unescape(tag.substring(s, e));
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 真实落盘（与 Android 原生格式一致）。 */
    private boolean persist() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n");
            for (Map.Entry<String, Object> e : new TreeMap<String, Object>(map).entrySet()) {
                Object v = e.getValue();
                String k = esc(e.getKey());
                if (v instanceof String) {
                    sb.append("    <string name=\"").append(k).append("\">")
                      .append(esc((String) v)).append("</string>\n");
                } else if (v instanceof Integer) {
                    sb.append("    <int name=\"").append(k).append("\" value=\"").append(v).append("\" />\n");
                } else if (v instanceof Long) {
                    sb.append("    <long name=\"").append(k).append("\" value=\"").append(v).append("\" />\n");
                } else if (v instanceof Boolean) {
                    sb.append("    <boolean name=\"").append(k).append("\" value=\"").append(v).append("\" />\n");
                } else if (v instanceof Float) {
                    sb.append("    <float name=\"").append(k).append("\" value=\"").append(v).append("\" />\n");
                }
            }
            sb.append("</map>\n");
            Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            try { w.write(sb.toString()); } finally { w.close(); }
            return true;
        } catch (Throwable t) { return false; }
    }

    // ───────── SharedPreferences ─────────

    @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(map); }
    @Override public String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
    @Override public Set<String> getStringSet(String k, Set<String> d) { Object v = map.get(k); return v instanceof Set ? (Set<String>) v : d; }
    @Override public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
    @Override public long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
    @Override public float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
    @Override public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    @Override public boolean contains(String k) { return map.containsKey(k); }
    @Override public Editor edit() { return new Ed(); }

    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

    private final class Ed implements Editor {
        private final Map<String, Object> pending = new HashMap<String, Object>();
        private final java.util.Set<String> removed = new java.util.HashSet<String>();

        @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
        @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
        @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
        @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
        @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
        @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
        @Override public Editor remove(String k) { removed.add(k); return this; }
        @Override public Editor clear() { removed.addAll(map.keySet()); return this; }

        @Override public boolean commit() {
            for (String k : removed) map.remove(k);
            for (Map.Entry<String, Object> e : pending.entrySet()) {
                if (e.getValue() == null) map.remove(e.getKey()); else map.put(e.getKey(), e.getValue());
            }
            return persist();          // 同步真实落盘
        }

        @Override public void apply() { commit(); }
    }
}
