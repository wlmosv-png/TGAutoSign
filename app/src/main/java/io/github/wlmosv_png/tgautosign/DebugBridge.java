package io.github.wlmosv_png.tgautosign;

import java.lang.reflect.Method;

/**
 * Debug 构建桥（2026-11-01 阶段 2 补齐）。
 *
 * ┌─ 为什么需要一个"桥"──────────────────────────────────────────────┐
 * │ 测试辅助类必须与生产代码**编译期隔离**（Release 的 dex 里不能有它）， │
 * │ 而触发入口（/jmb 子命令）在生产类的命令分发里。                      │
 * │ 因此这里只做一件事：**按固定类名反射转发**。                          │
 * └──────────────────────────────────────────────────────────────┘
 *
 * Release 安全性（逐条对应验收要求）：
 *   · Release 的 1-build-dex.sh 不收集 app/src/debug/java
 *     → 目标类 `...debug.DebugEntry` **物理不存在** → Class.forName 抛 CNFE
 *   · 因此 Release：不包含 EntryProbe 类、不注册测试 Hook、不识别 __selftest、
 *     不含测试文件输出逻辑 —— 都是**编译期不存在**，不是运行期开关。
 *   · 本类里**没有**任何命令字面量（如 "__selftest"），字面量只存在于 Debug 类中。
 *   · 生产判定路径完全不经过本类（只在 /jmb 子命令分发处以 "__" 前缀试探）。
 *
 * 不使用 `static boolean TEST_MODE`：那种方式在 Release 里仍然带着全套测试代码，
 * 只是运行时跳过，不满足"Release 不得包含"的要求。
 */
final class DebugBridge {

    /** Debug 构建才存在的类。Release 里该类不存在。 */
    private static final String DEBUG_ENTRY = "io.github.wlmosv_png.tgautosign.debug.DebugEntry";

    private DebugBridge() {}

    /**
     * 试着把子命令交给 Debug 探针处理。
     *
     * @return true = 已被探针识别并处理；false = 不是探针命令（Release 恒定 false）
     */
    static boolean tryHandle(Object core, String sub) {
        try {
            Class<?> c = Class.forName(DEBUG_ENTRY);
            Method m = c.getMethod("run", Object.class, String.class);
            Object r = m.invoke(null, core, sub);
            return Boolean.TRUE.equals(r);
        } catch (ClassNotFoundException e) {
            return false;                       // Release：类不存在 → 不是探针命令
        } catch (Throwable t) {
            // Debug 构建里探针自身出错：仍算"已识别"，避免落到普通命令分支
            return true;
        }
    }
}
