package io.github.wlmosv_png.tgautosign;
final class Art {
    private Art() {}
    private static String map(String s, int upperBase, int lowerBase) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 'A' && ch <= 'Z') out.appendCodePoint(upperBase + (ch - 'A'));
            else if (ch >= 'a' && ch <= 'z') out.appendCodePoint(lowerBase + (ch - 'a'));
            else if (ch >= '0' && ch <= '9') out.appendCodePoint(0x1D7F9 + (ch - '0'));
            else out.append(ch);
        }
        return out.toString();
    }
    // 数学粗体：干净无例外，适合标题 wordmark
    static String bold(String s) { return map(s, 0x1D400, 0x1D41A); }
    // 花体(script)：小写连贯，适合署名（大写有若干跳位，署名多为小写故无碍）
    static String script(String s) { return map(s, 0x1D49C, 0x1D4B6); }
}
