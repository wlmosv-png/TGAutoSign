package android.util;
/** 离线单测桩：只让 HostSymbols 能在纯 Java 环境编译运行，不进正式包。 */
public final class Log {
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable t) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
}
