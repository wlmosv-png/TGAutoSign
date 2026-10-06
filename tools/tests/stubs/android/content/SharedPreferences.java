package android.content;

import java.util.Map;
import java.util.Set;

/**
 * 单测桩：android.content.SharedPreferences（2026-10-06 补做第 5 项）。
 *
 * 为什么需要：judge/UnkPool 依赖 SharedPreferences，纯 Java 环境编不过，
 *   所以它的测试一直没写。这里提供一个**只含签名的最小桩**，
 *   放在 tools/tests/stubs/ 下，**不进 app/src/main/**，不参与正式构建。
 *
 * 只声明 UnkPool 用到的方法；实现由测试里的内存版本提供。
 */
public interface SharedPreferences {

    Map<String, ?> getAll();

    String getString(String key, String defValue);

    Set<String> getStringSet(String key, Set<String> defValues);

    int getInt(String key, int defValue);

    long getLong(String key, long defValue);

    float getFloat(String key, float defValue);

    boolean getBoolean(String key, boolean defValue);

    boolean contains(String key);

    Editor edit();

    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);

    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);

    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> values);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key);
    }
}
