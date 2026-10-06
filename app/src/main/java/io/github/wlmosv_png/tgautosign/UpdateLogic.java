package io.github.wlmosv_png.tgautosign;

import java.net.URL;
import java.util.Locale;

/**
 * 更新检查用到的**纯函数**（2026-10-06 P2 抽出）。
 *
 * 为什么要单独一个类：UpdateChecker 依赖 13 个 Android 类，
 *   在纯 Java 的单测环境里编不过；而这几段逻辑（版本比较、tag 解析、
 *   重定向解析、sha 行解析）是**与 Android 无关**的，抽出来就能直接单测。
 *
 * 约定：本类**不引用任何 Android 包**，可被 SignLogicTest 的最小编译集包含。
 */
public final class UpdateLogic {

    private UpdateLogic() {}

    /** 从 tag 里剥出的数字版本码前缀，如 "103-1.6.5" → 103。 */
    public static final java.util.regex.Pattern CODE_PREFIX =
            java.util.regex.Pattern.compile("^(\\d{2,6})-");

    // ───────────────────── tag / 版本 ─────────────────────

    /**
     * 解析 release tag，返回 {版本号, 版本码}。
     * 支持：「1.6.5」「v1.6.5」「103-1.6.5」「v1.6.5-debug」「1.6.5/extra」
     */
    public static String[] parseTag(String tag) {
        String t = tag == null ? "" : tag.trim();
        String code = "";
        java.util.regex.Matcher m = CODE_PREFIX.matcher(t);
        if (m.find()) {
            code = m.group(1);
            t = t.substring(m.end());
        }
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) t = t.substring(1);
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '-' || c == '_' || c == ' ' || c == '/') { cut = i; break; }
        }
        return new String[]{t.substring(0, cut), code};
    }

    /**
     * 比较两个版本号。段数不等时缺位补 0（"1.6" == "1.6.0"）。
     * 每段里的非数字后缀被忽略（"5-beta" → 5）。
     */
    public static int compareVersion(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? numOf(x[i]) : 0;
            int yi = i < y.length ? numOf(y[i]) : 0;
            if (xi != yi) return xi < yi ? -1 : 1;
        }
        return 0;
    }

    private static int numOf(String s) {
        try {
            String d = (s == null ? "" : s).replaceAll("\\D.*$", "");
            if (d.length() == 0) return 0;
            return Integer.parseInt(d);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ───────────────────── 网络 ─────────────────────

    /**
     * 解析重定向目标。
     *
     * 规则（2026-10-06 P2）：
     *   · 相对路径按 baseUrl 解析成绝对地址
     *   · **只允许 https**，降级到 http 一律视为失败（返回 null）
     *   · 空 / 非法 / 解析异常 → null
     */
    public static String resolveRedirect(String baseUrl, String location) {
        try {
            if (location == null || location.trim().length() == 0) return null;
            if (baseUrl == null || baseUrl.trim().length() == 0) return null;
            String loc = location.trim();
            // 一个 URL 里合法出现的 ':' 至多两个（https: 前 + host:port）。
            // "::::" 这类畸形串会被 URL 当成带冒号的路径而"解析成功"，
            // 实际必然下载失败，这里提前拒绝。
            int colons = 0;
            for (int i = 0; i < loc.length(); i++) if (loc.charAt(i) == ':') colons++;
            if (colons > 2) return null;
            URL u = new URL(new URL(baseUrl), loc);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return null;
            return u.toExternalForm();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 sha256sum.txt 的一行里取 sha，且要求该行**提到目标文件名**。
     *
     * 2026-10-06 P2：不再「找不到同名行就回退取第一条」——
     *   多 apk 的 release 会拿错包的 sha，导致「下载正确却校验失败」。
     *
     * @return 64 位小写 sha；不匹配返回空串
     */
    public static String parseShaLine(String line, String apkName) {
        try {
            if (line == null) return "";
            String l = line.trim();
            if (l.length() < 64) return "";
            String sha = l.substring(0, 64).toLowerCase(Locale.US);
            if (!sha.matches("[0-9a-f]{64}")) return "";
            if (apkName == null || apkName.length() == 0) return "";
            if (!l.contains(apkName)) return "";
            return sha;
        } catch (Throwable t) {
            return "";
        }
    }
}
