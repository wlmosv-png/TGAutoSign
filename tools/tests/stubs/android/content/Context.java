package android.content;

/**
 * 单测桩：android.content.Context（2026-10-06 补做第 5 项）。
 *
 * judge/UnkPool 里 import 了 Context（实际未使用），
 * 纯 Java 编译需要这个类型存在。只声明最小签名。
 */
public class Context {
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }

    public java.io.File getFilesDir() {
        return null;
    }
}
