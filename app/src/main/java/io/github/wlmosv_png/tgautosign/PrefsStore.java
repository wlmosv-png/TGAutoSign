package io.github.wlmosv_png.tgautosign;

import android.content.SharedPreferences;

import java.util.Map;

/**
 * 持久化访问层（Refactor 1.6.1 · 步骤 2）。
 *
 * 为什么需要它：
 *   重构前 TGAutoSignCore 有 244 处直接访问 prefs，其中 81 处 edit、
 *   69 处 apply / 12 处 commit。落盘方式（apply/commit）的语义**没有任何规则**，
 *   导致今天连踩两次同类坑：
 *     - markOptimistic 用 apply → 紧跟其后的巡检读不到 → 同一目标并发发多次
 *     - 设置保存 catch 吞异常 + apply → 写失败仍提示"已保存"
 *
 * 本类固化的规则：
 *   ① **状态机用键**（last_/opt_/retry_/sent_at_/pendcfm_/frozen_/snooze_ 等）
 *      → 一律 {@code commit()}。这些键会被**紧随其后的读**依赖（闸门判断），
 *        apply 的异步窗口会让闸门失效。
 *   ② **设置类键**（jmb_* 全局配置）→ commit 也可接受（低频），统一 commit 更安全。
 *   ③ 纯展示/缓存类（可丢）→ 允许 apply（但本类默认 commit，除非显式 fast()）。
 *
 * 另外：
 *   - 所有方法都不抛出（内部吞异常并返回默认值），与既有代码风格一致。
 *   - 提供 {@link #setChecked} 让调用方能判断"写成功了吗"（设置保存的反馈需要）。
 */
public final class PrefsStore {

    private final SharedPreferences prefs;

    public PrefsStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public SharedPreferences raw() { return prefs; }

    // ───────────── 读 ─────────────

    public String s(String key, String def) {
        try { return prefs.getString(key, def); } catch (Throwable t) { return def; }
    }

    public String s(String key) { return s(key, ""); }

    public int i(String key, int def) {
        try { return prefs.getInt(key, def); } catch (Throwable t) { return def; }
    }

    public long l(String key, long def) {
        try { return prefs.getLong(key, def); } catch (Throwable t) { return def; }
    }

    public boolean b(String key, boolean def) {
        try { return prefs.getBoolean(key, def); } catch (Throwable t) { return def; }
    }

    public boolean has(String key) {
        try { return prefs.contains(key); } catch (Throwable t) { return false; }
    }

    public Map<String, ?> all() {
        try { return prefs.getAll(); } catch (Throwable t) { return java.util.Collections.emptyMap(); }
    }

    // ───────────── 写（默认 commit：可被紧随其后的读依赖）─────────────

    /** 写单个键（commit）。返回是否成功。 */
    public boolean set(String key, String v) {
        try { return prefs.edit().putString(key, v).commit(); } catch (Throwable t) { return false; }
    }

    public boolean set(String key, int v) {
        try { return prefs.edit().putInt(key, v).commit(); } catch (Throwable t) { return false; }
    }

    public boolean set(String key, long v) {
        try { return prefs.edit().putLong(key, v).commit(); } catch (Throwable t) { return false; }
    }

    public boolean set(String key, boolean v) {
        try { return prefs.edit().putBoolean(key, v).commit(); } catch (Throwable t) { return false; }
    }

    /** 删单个键（commit）。 */
    public boolean remove(String key) {
        try { return prefs.edit().remove(key).commit(); } catch (Throwable t) { return false; }
    }

    // ───────────── 批量（编辑器封装）─────────────

    /**
     * 批量编辑：**同步落盘**，返回是否成功。
     * 用法：{@code store.tx(ed -> ed.putString(a,b).remove(c));}
     */
    public boolean tx(Tx body) {
        try {
            SharedPreferences.Editor ed = prefs.edit();
            body.apply(ed);
            return ed.commit();
        } catch (Throwable t) { return false; }
    }

    public interface Tx { void apply(SharedPreferences.Editor ed); }

    /**
     * 批量编辑但**异步落盘**（apply）：仅用于"丢了也不影响正确性"的缓存类写入。
     * 涉及状态机的键请用 {@link #tx}。
     */
    public void txFast(Tx body) {
        try {
            SharedPreferences.Editor ed = prefs.edit();
            body.apply(ed);
            ed.apply();
        } catch (Throwable ignored) {}
    }

    // ───────────── 便捷：日期型状态（今天已签等）─────────────

    /** 某键是否等于"今天"（yyyy-MM-dd）。跨天自动视为 false，无需显式清理。 */
    public boolean isToday(String key, String today) {
        return today != null && today.equals(s(key, ""));
    }

    /** 把某键置为今天。 */
    public boolean markToday(String key, String today) { return set(key, today); }
}
