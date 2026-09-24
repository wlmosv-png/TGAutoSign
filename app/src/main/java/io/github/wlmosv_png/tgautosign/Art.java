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
            // 数学粗体数字零是 U+1D7CE（不是 0x1D7F9 —— 那是 Monospace Digit Three，
            // 会把 0 渲染成 𝟹、9 溢出到别的码位）。当前调用方标题无数字，属于潜伏 bug。
            else if (ch >= '0' && ch <= '9') out.appendCodePoint(0x1D7CE + (ch - '0'));
            else out.append(ch);
        }
        return out.toString();
    }
    // 数学粗体：干净无例外，适合标题 wordmark
    static String bold(String s) { return map(s, 0x1D400, 0x1D41A); }
    /**
     * 花体(Bold Script)：适合署名。
     *
     * 不用 0x1D49C（Script）：那一组有 11 个**未分配码点**（B E F H I L M R / e g o），
     * Unicode 为让各字母数字字体共享编码而挖空，渲染成豆腐块。
     * 实测：script("wlmosv") 里的 'o' 就是未分配码点。
     * Bold Script（0x1D4D0）连续无空洞。
     */
    static String script(String s) { return map(s, 0x1D4D0, 0x1D4EA); }
}
