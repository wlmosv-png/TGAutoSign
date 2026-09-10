package io.github.wlmosv_png.tgautosign;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 宿主判定 —— 决定模块注入哪些进程。
 *
 * 两条路径，命中任意一条即注入：
 *  1) 已知包名白名单：逐个用真实 APK 的 DEX 校验过全部反射类与 hook 成员（见 README「支持的客户端」矩阵）。
 *  2) 能力探测兜底：宿主 ClassLoader 能解析 Telegram-Android 的三个标志类。
 *     fork 只换 applicationId、不换内部类名，所以新出现的 fork 不用等模块更新；
 *     而换过内核的客户端（例如 Telegram X 的 xcobalt 分支）标志类不齐全，会被自然挡掉，不会误注入。
 *
 * 注意：作用域由 Xposed 管理器决定，用户必须先把对应客户端勾选进模块作用域，
 * 本类只负责"进了作用域之后要不要真的注入"。
 */
public final class Hosts {

    private Hosts() {}

    /** Telegram 主客户端（Google Play 等渠道） */
    public static final String PKG_OFFICIAL = "org.telegram.messenger";
    /** Telegram 官网直连版（telegram.org/dl/android/apk），与 Play 版可共存 */
    public static final String PKG_OFFICIAL_WEB = "org.telegram.messenger.web";
    /** Nagram XF（NagramX 的 rebrand 分支，Keeperorowner / sukri369 发布） */
    public static final String PKG_NAGRAM_XF = "fork.risin42.nagramx";

    private static final Set<String> KNOWN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            PKG_OFFICIAL,
            PKG_OFFICIAL_WEB,
            PKG_NAGRAM_XF,
            "nu.gpu.nagram",       // Nagram / NagramX（risin42 dev、main 分支 APP_PACKAGE）
            "nu.gpu.nagramx",      // NagramX base 变体（risin42 base 分支 APP_PACKAGE）
            "nu.gpu.nagram.web"    // NagramNX（thesv4k 分支 APP_PACKAGE）
    )));

    /** 三者齐全才认定是 Telegram-Android 血统 */
    private static final String[] MARKERS = {
            "org.telegram.tgnet.ConnectionsManager",
            "org.telegram.ui.Components.ChatActivityEnterView",
            "org.telegram.messenger.UserConfig"
    };

    public static boolean isKnownPackage(String pkg) {
        return pkg != null && KNOWN.contains(pkg);
    }

    /** 是否注入该宿主。loader 允许为 null（此时只有白名单命中才注入）。 */
    public static boolean isSupported(String pkg, ClassLoader loader) {
        if (pkg == null || pkg.length() == 0) return false;
        if (isKnownPackage(pkg)) return true;
        return looksLikeTelegramAndroid(loader);
    }

    public static boolean looksLikeTelegramAndroid(ClassLoader loader) {
        if (loader == null) return false;
        for (String marker : MARKERS) {
            if (!canLoad(loader, marker)) return false;
        }
        return true;
    }

    /** 命中方式说明，用于日志与 /jmb 运行日志 */
    public static String describe(String pkg, ClassLoader loader) {
        if (isKnownPackage(pkg)) return "已知客户端";
        if (looksLikeTelegramAndroid(loader)) return "能力探测命中（Telegram-Android 血统 fork）";
        return "不支持";
    }

    public static Set<String> knownPackages() {
        return KNOWN;
    }

    private static boolean canLoad(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
