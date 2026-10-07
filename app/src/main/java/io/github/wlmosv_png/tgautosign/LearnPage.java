package io.github.wlmosv_png.tgautosign;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.tgautosign.judge.ReplyNormalizer;
import io.github.wlmosv_png.tgautosign.judge.UnkPool;

/**
 * LearnPage —— 「未识别回复」自学习页。
 *
 * 2026-10-04 第四次重做。前几版按钮文字不显示、列表滚不动、高度离谱，
 * 根因是「自己 new Button + 强压宽度」在宿主主题下被裁。现在**一律用 TextView
 * 自绘按钮**（与 Core 的 menuTile 同一思路）：完全可控，不受宿主 Button 主题影响。
 */
public final class LearnPage {

    public interface Callbacks {
        String prefsPrefix();
        SharedPreferences prefs();
        void addJudgeWords(String[] okWords, String[] failWords);
        void setJudgeWords(String okJoined, String failJoined);
        /**
         * 带作用域的判定词写入（2026-10-06 新增，交接单第八条）。
         * @param word      用户确认/编辑后的词
         * @param isOk      true=成功词 false=失败词
         * @param scope     "global" / "bot" / "target"
         * @param did       目标所属 bot / 会话 did（scope=bot 时用）
         * @param targetId  目标条目 id（scope=target 时用）
         */
        void addJudgeWordScoped(String word, boolean isOk, String scope, long did, String targetId);
        void addIgnoredPattern(String pattern);
        java.util.Set<String> ignoredPatterns();
        /** 撤销一条「已忽略」（问题 6）；池里的原文仍在，撤了就会回到待确认列表。 */
        void removeIgnoredPattern(String pattern);
        /** 被闸门跳过的回复（方案 4.1），返回 [原因, 原话] 对。 */
        java.util.List<String[]> skippedUnknown();
        /** 把被跳过的全部补收进池，返回补收条数。 */
        int promoteSkipped();
        /** 读某词的命中统计 [次数, 最近ts]；无记录返回 null（问题 4）。 */
        long[] wordHitOf(String word);
        /**
         * 读**全部三级**判定词（2026-10-07）。
         * 返回 [词, 作用域标签] 对；顺序：全局 → Bot → 目标。
         * 为什么需要：学习默认写 Bot 级，而旧 UI 只显示全局词 →
         * 用户学完什么都看不到，以为没生效。
         */
        java.util.List<String[]> allJudgeWords(boolean isOk);
        /** 删除一条判定词（按词 + 作用域标签定位）。 */
        void removeJudgeWord(String word, boolean isOk);
        void toast(String msg);
        void dismissAndRefresh();
        /** 记录一次性能日志（卡顿定位用）。 */
        void logPerf(String msg);
        /** 冻结/解冻主题判定（批量构建 View 树时用，避免昂贵的屏幕采样）。 */
        void freezeTheme();
        void unfreezeTheme();
        /** 原地刷新失败时的兜底：关窗重开。 */
        void dismissAndRefreshFallback();
    }

    private final Activity act;
    private final Callbacks cb;

    public LearnPage(Activity act, Callbacks cb) {
        this.act = act;
        this.cb = cb;
    }

    private String okWordsText() { return cb.prefs().getString("jmb_ok_words", ""); }
    private String failWordsText() { return cb.prefs().getString("jmb_fail_words", ""); }

    /**
     * suggestWord 结果缓存（卡顿修复）。
     *
     * 原来每条模式要调两次 suggestWord（命中反馈一次、推荐词一次），
     * 而它内部对 35 个词根做 O(n²) 子串扫描 —— 池子大时明显拖慢主线程。
     * 同一次页面构建内，同一个 norm 的结果不会变，缓存即可。
     */
    private final java.util.HashMap<String, String> suggestCache =
            new java.util.HashMap<String, String>();

    /**
     * 三级判定词缓存（卡顿修复）。
     *
     * wordBlock(true) 与 wordBlock(false) 各调一次 allJudgeWords，
     * 而它内部要 prefs.getAll()（复制整份 map）再遍历 —— 一次构建里白跑两遍。
     * 同一次构建内结果不变，缓存即可。
     */
    private java.util.List<String[]> wordsCacheOk = null;
    private java.util.List<String[]> wordsCacheFail = null;

    private java.util.List<String[]> wordsFor(boolean isOk) {
        try {
            if (isOk) {
                if (wordsCacheOk == null) wordsCacheOk = cb.allJudgeWords(true);
                return wordsCacheOk;
            }
            if (wordsCacheFail == null) wordsCacheFail = cb.allJudgeWords(false);
            return wordsCacheFail;
        } catch (Throwable t) {
            return cb.allJudgeWords(isOk);
        }
    }

    private String suggestFor(String norm) {
        try {
            if (norm == null) return null;
            String v = suggestCache.get(norm);
            if (v != null) return v.length() == 0 ? null : v;
            String sug = SignLogic.suggestWord(norm);
            if (sug == null || sug.length() == 0) sug = extractWord(norm);
            suggestCache.put(norm, sug == null ? "" : sug);
            return sug;
        } catch (Throwable t) {
            return SignLogic.suggestWord(norm);
        }
    }

    /** 界面上单条文本的显示上限（2026-10-07）。 */
    private static final int UI_TEXT_MAX = 160;

    /** 显示用截断：避免把几百字塞进 TextView / EditText（渲染开销明显）。 */
    private static String uiClip(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= UI_TEXT_MAX ? t : t.substring(0, UI_TEXT_MAX) + "…";
    }

    /** 内容容器（原地刷新时只清它、重填它，不动对话框）。 */
    private LinearLayout contentBox;
    private String contentTypePrefix;
    private String contentTypePoolKey;

    /**
     * 原地刷新（卡顿修复）。
     *
     * 旧路径：每次操作 → dismiss 整个对话框 → 140ms → 重建 View 树 → 入场淡入。
     *   用户点一下会看到"闪一下 + 卡一下"。
     * 新路径：只清空内容容器重填，对话框与滚动位置都不动。
     */
    /**
     * 原地刷新（2026-10-07 第三轮：把重填挪到下一帧）。
     *
     * 为什么还要改：
     *   前两轮已经把重填从"关窗重开"改成"原地重填"，并用 Theme.freeze
     *   干掉了最大的开销（整树重绘采样 1290ms）。
     *   但重填本身仍**同步跑在点击回调里** —— 点击 → 同步建几十个 View → 才渲染。
     *   库里条目一多，这一下仍会顶掉一两帧，主观就是"卡一下"。
     *
     * 现在：点击后立刻返回（toast 等反馈先画出来），
     *   重填放进 contentBox.post()，在下一帧执行。
     *   用户感知从"卡一下"变成"立刻响应，内容随即刷新"。
     */
    public void refreshInPlace() {
        try {
            if (contentBox == null) return;
            // 关键：不在点击回调里同步做重活
            contentBox.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (contentBox == null) return;
                        suggestCache.clear();
                        wordsCacheOk = null;
                        wordsCacheFail = null;
                        long t0 = android.os.SystemClock.uptimeMillis();
                        contentBox.removeAllViews();
                        // 构建期间冻结主题，避免 Theme.dark() 触发整树重绘采样
                        cb.freezeTheme();
                        try {
                            fillContent(contentBox, contentTypePrefix, contentTypePoolKey);
                        } finally {
                            cb.unfreezeTheme();
                        }
                        long dt = android.os.SystemClock.uptimeMillis() - t0;
                        if (dt >= 16) cb.logPerf("学习页原地刷新 " + dt + "ms");
                    } catch (Throwable t) {
                        cb.logPerf("学习页原地刷新异常: " + t);
                        try { cb.dismissAndRefreshFallback(); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            cb.logPerf("学习页原地刷新异常(投递): " + t);
            try { cb.dismissAndRefreshFallback(); } catch (Throwable ignored) {}
        }
    }

    /** contentBox 是否已就绪（供 Core 判断能否原地刷新）。 */
    public boolean canRefreshInPlace() { return contentBox != null; }

    public View build() {
        final String prefix = cb.prefsPrefix();
        final String poolKey = prefix + "unk_pool";
        final SharedPreferences p = cb.prefs();

        ScrollView sv = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(2), dp(10), dp(10));
        contentBox = box;
        contentTypePrefix = prefix;
        contentTypePoolKey = poolKey;
        fillContent(box, prefix, poolKey);
        sv.addView(box, new ScrollView.LayoutParams(-1, -2));
        return sv;
    }

    /** 填充内容（build 与 refreshInPlace 共用）。 */
    private void fillContent(final LinearLayout box, final String prefix, final String poolKey) {
        final SharedPreferences p = cb.prefs();
        final long _t0 = android.os.SystemClock.uptimeMillis();
        long _tPrev = _t0;
        final StringBuilder _prof = new StringBuilder();

        final List<UnkPool.Item> rawItems = UnkPool.list(p, poolKey);
        { long _n = android.os.SystemClock.uptimeMillis();
          _prof.append("list=").append(_n - _tPrev).append(" ");
          _tPrev = _n; }
        // 2026-10-06 第二轮：改用 clusterItems —— 旧 cluster(List<String>) 只知道文本，
        //   Pattern.dids / fromDids / targetIds 恒为空，于是 patternBlock 里
        //   ptDid 永远是 0，作用域只能默认「这个目标」，**Bot 级词表永远学不进去**。
        //   这里直接按 Item 聚类，把来源（哪个 bot / 哪个目标 / 哪个会话）带出来。
        final List<ReplyNormalizer.Pattern> pats = ReplyNormalizer.clusterItems(rawItems);
        { long _n = android.os.SystemClock.uptimeMillis();
          _prof.append("cluster=").append(_n - _tPrev).append("(").append(pats.size()).append("项) ");
          _tPrev = _n; }

        java.util.Set<String> ignored = cb.ignoredPatterns();
        { long _n = android.os.SystemClock.uptimeMillis();
          _prof.append("ignored=").append(_n - _tPrev).append(" ");
          _tPrev = _n; }
        final List<ReplyNormalizer.Pattern> shown = new ArrayList<ReplyNormalizer.Pattern>();
        for (ReplyNormalizer.Pattern pt : pats) {
            if (ignored != null && ignored.contains(pt.norm)) continue;
            shown.add(pt);
        }

        TextView intro = new TextView(act);
        intro.setTextSize(Theme.TS_CAPTION);
        intro.setTextColor(Theme.termFaint(act));
        intro.setTypeface(Theme.text());
        intro.setLineSpacing(dp(2), 1f);
        intro.setPadding(dp(2), dp(6), dp(2), dp(2));
        intro.setText("机器人回复了、但认不出结果的消息会攒在这里。"
                    + "填一个判定词保存后即可生效；填过的词下次会显示命中情况。"
                    + "点「忽略」的条目可在下方「已忽略」里恢复。");
        box.addView(intro);

        sectionHeader(box, "待确认（" + shown.size() + "）");
        if (shown.isEmpty()) {
            box.addView(emptyBlock(rawItems.size(), pats.size()));
        } else {
            for (final ReplyNormalizer.Pattern pt : shown) {
                box.addView(patternBlock(pt, prefix, poolKey));
            }
            { long _n = android.os.SystemClock.uptimeMillis();
              _prof.append("blocks=").append(_n - _tPrev).append("(").append(shown.size()).append("条) ");
              _tPrev = _n; }
        }

        // ── 问题 6：已忽略区块（可撤销）──
        final List<ReplyNormalizer.Pattern> ignoredPats = new ArrayList<ReplyNormalizer.Pattern>();
        for (ReplyNormalizer.Pattern pt : pats) {
            if (ignored != null && ignored.contains(pt.norm)) ignoredPats.add(pt);
        }
        if (!ignoredPats.isEmpty()) {
            sectionHeader(box, "已忽略（" + ignoredPats.size() + "）· 点「恢复」可撤回");
            for (final ReplyNormalizer.Pattern pt : ignoredPats) {
                box.addView(ignoredBlock(pt, prefix, poolKey));
            }
        }

        // ── 方案 4.1：被过滤的回复（否则用户"判不出却看不到"）──
        try {
            final java.util.List<String[]> skipped = cb.skippedUnknown();
            if (skipped != null && !skipped.isEmpty()) {
                sectionHeader(box, "另有 " + skipped.size() + " 条被过滤");
                LinearLayout sk = block();
                TextView st = new TextView(act);
                st.setTextSize(Theme.TS_CAPTION);
                st.setTextColor(Theme.termFaint(act));
                st.setTypeface(Theme.text());
                st.setText("这些回复因为「广告/过长/不属于本轮交互」没进待确认列表。"
                         + "如果你觉得其中有该学的，可以全部收进来。");
                sk.addView(st);
                int shownSk = 0;
                for (String[] kv : skipped) {
                    if (shownSk++ >= 8) break;
                    TextView row = new TextView(act);
                    row.setTextSize(Theme.TS_CAPTION);
                    row.setTextColor(Theme.termMuted(act));
                    row.setTypeface(Typeface.MONOSPACE);
                    row.setSingleLine(true);
                    row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.setPadding(0, dp(3), 0, 0);
                    row.setText("· [" + kv[0] + "] " + kv[1]);
                    sk.addView(row);
                }
                TextView all = pill("全部收进池", Theme.termCyan(act), 1);
                all.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int n = cb.promoteSkipped();
                        cb.toast("已补收 " + n + " 条");
                        cb.dismissAndRefresh();
                    }
                });
                LinearLayout skRow = new LinearLayout(act);
                skRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(-1, -2);
                skLp.topMargin = dp(6);
                skRow.setLayoutParams(skLp);
                skRow.addView(all);
                sk.addView(skRow);
                box.addView(sk);
            }
        } catch (Throwable _eSk) {}

        sectionHeader(box, "判定词");
        box.addView(wordBlock(true));
        box.addView(wordBlock(false));
        { long _n = android.os.SystemClock.uptimeMillis();
          _prof.append("words=").append(_n - _tPrev).append(" ");
          _tPrev = _n; }

        LinearLayout ops = new LinearLayout(act);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = dp(12);
        ops.setLayoutParams(olp);
        TextView clear = pill("清空未识别池", Theme.termPink(act), -1);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UnkPool.clear(p, poolKey, prefix + "unk_seen");
                cb.toast("已清空未识别池");
                cb.dismissAndRefresh();
            }
        });
        ops.addView(clear);
        box.addView(ops);
        { long _n = android.os.SystemClock.uptimeMillis();
          _prof.append("tail=").append(_n - _tPrev).append(" ");
          _prof.append("TOTAL=").append(_n - _t0); }
        cb.logPerf("学习页分段 " + _prof.toString());
    }

    // ───────────────────── 待确认 ─────────────────────

    private View emptyBlock(int poolSize, int patCount) {
        LinearLayout card = block();
        TextView t = new TextView(act);
        t.setTextSize(Theme.TS_SECOND);
        t.setTextColor(Theme.termTxt(act));
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setText(poolSize == 0 ? "还没有采集到未识别的回复" : "全部模式都已处理完");
        card.addView(t);

        TextView b = new TextView(act);
        b.setTextSize(Theme.TS_CAPTION);
        b.setTextColor(Theme.termMuted(act));
        b.setTypeface(Theme.text());
        b.setLineSpacing(dp(2), 1f);
        b.setPadding(0, dp(5), 0, 0);
        b.setText(poolSize == 0
                ? "等某个 bot 回了消息、但内置词认不出结果时会自动出现。\n也可以直接在下面「判定词」里手动添加。"
                : "池中还有 " + poolSize + " 条原始回复，归成 " + patCount + " 个模式，但都已被忽略。");
        card.addView(b);
        return card;
    }

    private View patternBlock(final ReplyNormalizer.Pattern pt,
                              final String prefix, final String poolKey) {
        // 2026-10-06：取该模式关联的来源，作为作用域的默认值（交接单第九/十条）。
        // 第二轮修正：优先用 **fromDids（真正的发送者 bot）**——
        //   群聊里 did 是「群 id」，拿它当 bot 级 key 会让同群所有 bot 共用一张词表。
        //   私聊时 fromDids 与 dids 相同，取哪个都一样。
        long _ptDid = 0L;
        if (!pt.fromDids.isEmpty()) _ptDid = pt.fromDids.iterator().next();
        else if (!pt.dids.isEmpty()) _ptDid = pt.dids.iterator().next();
        final long ptDid = _ptDid;
        final String ptTarget = pt.targetIds.isEmpty() ? null : pt.targetIds.iterator().next();
        LinearLayout card = block();

        TextView main = new TextView(act);
        main.setTextSize(Theme.TS_BODY);
        main.setTextColor(Theme.termTxt(act));
        main.setTypeface(Typeface.MONOSPACE);
        main.setText(uiClip(pt.norm));
        card.addView(main);

        TextView meta = new TextView(act);
        meta.setTextSize(Theme.TS_CAPTION);
        meta.setTextColor(Theme.termFaint(act));
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setPadding(0, dp(3), 0, dp(5));
        meta.setText("出现 " + pt.count + " 次");
        card.addView(meta);

        // 问题 4：如果这个模式对应的推荐词**已经学过并有命中记录**，
        // 直接在条目上显示效果，用户就知道"学了到底有没有用"。
        try {
            String sug = suggestFor(pt.norm);
            if (sug != null && sug.length() > 0) {
                long[] hs = cb.wordHitOf(sug);
                if (hs != null && hs[0] > 0) {
                    TextView hit = new TextView(act);
                    hit.setTextSize(Theme.TS_CAPTION);
                    hit.setTextColor(Theme.termGreen(act));
                    hit.setTypeface(Theme.text());
                    hit.setPadding(0, dp(2), 0, dp(2));
                    java.text.SimpleDateFormat fmt =
                            new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
                    hit.setText("「" + sug + "」已命中 " + hs[0] + " 次 · 最近 "
                                + fmt.format(new java.util.Date(hs[1])));
                    card.addView(hit);
                }
            }
        } catch (Throwable ignored) {}

        for (String s : pt.samples) {
            TextView sm = new TextView(act);
            sm.setTextSize(Theme.TS_CAPTION);
            sm.setTextColor(Theme.termFaint(act));
            sm.setTypeface(Typeface.MONOSPACE);
            sm.setSingleLine(true);
            sm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sm.setText("· " + uiClip(s));
            card.addView(sm);
        }

        // ── 2026-10-06 改造（交接单第六/七条）──
        // 改前：「将学会：xxx」是只读 Label，用户只能接受系统猜的词。
        // 改后：推荐词放进**可编辑输入框**，用户能改成更准的片段。
        // 2026-10-06 重构（问题 3）：推荐词改为**算法提词**。
        // 旧 extractWord 是 46 个硬编码词，只能识别词表里已有的措辞 ——
        // 而用户真正要学的恰恰是词表里没有的新说法。
        // 现在先走 suggestWord（按分隔切片 + 打分选片段），
        // 提不出再退回旧词表（保底），仍为 null 就让用户手填。
        String suggest = suggestFor(pt.norm);

        TextView lab = new TextView(act);
        lab.setTextSize(Theme.TS_CAPTION);
        lab.setTextColor(Theme.termGreen(act));
        lab.setTypeface(Typeface.MONOSPACE);
        lab.setPadding(0, dp(6), 0, dp(2));
        lab.setText(suggest == null || suggest.length() == 0
                ? "系统提不出有判别力的词，可自己填一个，或选「忽略」"
                : "判定词（可直接修改）：");
        card.addView(lab);

        final EditText wordEd = new EditText(act);
        wordEd.setTextSize(Theme.TS_SECOND);
        wordEd.setTextColor(Theme.termTxt(act));
        wordEd.setTypeface(Typeface.MONOSPACE);
        wordEd.setSingleLine(true);
        wordEd.setInputType(InputType.TYPE_CLASS_TEXT);
        wordEd.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (suggest != null) wordEd.setText(uiClip(suggest));
        wordEd.setHint("在此填写判定词");
        wordEd.setHintTextColor(Theme.termFaint(act));
        try { wordEd.setBackground(controlBg(Theme.surface(act, 3))); } catch (Throwable ignored) {}
        card.addView(wordEd, new LinearLayout.LayoutParams(-1, -2));

        // ── 作用域选择（交接单第八条）──
        // 默认「这个 bot」：避免学一个词污染所有目标；
        // 拿不到 bot 信息时降级到「这个目标」。
        final String[] scope = new String[]{ ptDid != 0L ? "bot" : "target" };
        LinearLayout scRow = new LinearLayout(act);
        scRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
        scLp.topMargin = dp(6);
        scRow.setLayoutParams(scLp);

        final TextView scGlobal = pill("全局", Theme.termTxt(act), 1);
        final TextView scBot    = pill("这个 bot", Theme.termTxt(act), 1);
        final TextView scTarget = pill("这个目标", Theme.termTxt(act), 1);
        final android.widget.TextView[] scAll = { scGlobal, scBot, scTarget };

        final Runnable refreshScope = new Runnable() {
            @Override public void run() {
                String cur = scope[0];
                int onFill = Theme.primaryFill(act);
                int offFill = Theme.surface(act, 2);
                int onTxt = Theme.onPrimary(act);
                int offTxt = Theme.termTxt(act);
                scGlobal.setBackground(controlBg("global".equals(cur) ? onFill : offFill));
                scGlobal.setTextColor("global".equals(cur) ? onTxt : offTxt);
                scBot.setBackground(controlBg("bot".equals(cur) ? onFill : offFill));
                scBot.setTextColor("bot".equals(cur) ? onTxt : offTxt);
                scTarget.setBackground(controlBg("target".equals(cur) ? onFill : offFill));
                scTarget.setTextColor("target".equals(cur) ? onTxt : offTxt);
            }
        };
        scGlobal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scope[0] = "global"; refreshScope.run(); }
        });
        scBot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scope[0] = "bot"; refreshScope.run(); }
        });
        scTarget.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scope[0] = "target"; refreshScope.run(); }
        });
        scRow.addView(scGlobal);
        scRow.addView(gap(6));
        scRow.addView(scBot);
        scRow.addView(gap(6));
        scRow.addView(scTarget);
        card.addView(scRow);
        refreshScope.run();

        TextView scTip = new TextView(act);
        scTip.setTextSize(Theme.TS_CAPTION);
        scTip.setTextColor(Theme.termFaint(act));
        scTip.setTypeface(Theme.text());
        scTip.setPadding(0, dp(3), 0, 0);
        scTip.setText("默认只作用于这个 bot，不会影响其它 bot；确需全局生效再选「全局」。");
        card.addView(scTip);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        TextView okB = pill("加入成功词", Theme.primaryFill(act), 1);   // 主操作：实心
        okB.setTextColor(Theme.onPrimary(act));
        okB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actAsEdited(pt, wordEd.getText().toString(), true, scope[0], ptDid, ptTarget, prefix, poolKey);
            }
        });
        row.addView(okB);
        row.addView(gap(6));

        TextView failB = pill("加入失败词", Theme.termPink(act), 1);
        failB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actAsEdited(pt, wordEd.getText().toString(), false, scope[0], ptDid, ptTarget, prefix, poolKey);
            }
        });
        row.addView(failB);
        row.addView(gap(6));

        TextView igB = pill("忽略", Theme.termTxt(act), 1);
        igB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 2026-10-06（问题 6）：只写忽略表，**不再删池** ——
                // 删了原文就永久找不回，用户没法反悔。现在可从「已忽略」区恢复。
                cb.addIgnoredPattern(pt.norm);
                cb.toast("已忽略（可在下方「已忽略」里恢复）");
                cb.dismissAndRefresh();
            }
        });
        row.addView(igB);
        card.addView(row);
        return card;
    }

    /**
     * 「已忽略」条目（问题 6）。
     *
     * 旧行为：点忽略 = 写忽略表 + **从池里删掉** → 反悔也找不回原文。
     * 新行为：忽略只写忽略表，池里原文保留；这里给一个「恢复」按钮，
     * 点一下把它从忽略表移除，条目自然回到「待确认」列表。
     */
    /**
     * 按「词 + 作用域标签」删除（2026-10-07）。
     *
     * 为什么不能沿用旧的 removeWord(isOk, w)：三级词表里同一个词可能同时存在于
     * 全局与 Bot 级，旧的按「词」删会删错级（或删两处）。
     * 这里把标签一并交给 Core，由它定位到具体 key。
     */
    private void removeWordScoped(boolean isOk, String word, String scopeTag) {
        try {
            cb.removeJudgeWord(word + "\u0001" + (scopeTag == null ? "" : scopeTag)
                               + "\u0001" + (isOk ? "ok" : "fail"), isOk);
            cb.toast("已删除「" + word + "」");
            cb.dismissAndRefresh();
        } catch (Throwable t) {
            cb.toast("删除失败：" + t);
        }
    }

    private View ignoredBlock(final ReplyNormalizer.Pattern pt,
                              final String prefix, final String poolKey) {
        LinearLayout card = block();

        TextView main = new TextView(act);
        main.setTextSize(Theme.TS_SECOND);
        main.setTextColor(Theme.termMuted(act));
        main.setTypeface(Typeface.MONOSPACE);
        main.setText(uiClip(pt.norm));
        card.addView(main);

        TextView meta = new TextView(act);
        meta.setTextSize(Theme.TS_CAPTION);
        meta.setTextColor(Theme.termFaint(act));
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setPadding(0, dp(3), 0, dp(6));
        meta.setText("出现 " + pt.count + " 次 · 已忽略");
        card.addView(meta);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView undo = pill("恢复", Theme.termCyan(act), 1);
        undo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.removeIgnoredPattern(pt.norm);
                cb.toast("已恢复：「" + pt.norm + "」回到待确认");
                cb.dismissAndRefresh();
            }
        });
        row.addView(undo);
        card.addView(row);
        return card;
    }

    /**
     * 按**用户编辑后的词 + 选定作用域**写入判定词（2026-10-06 新增）。
     *
     * 与旧 actAs 的区别：
     *   · 词来自输入框，不是系统猜的
     *   · 带作用域（global / bot / target），默认 bot，避免污染全局
     */
    private void actAsEdited(ReplyNormalizer.Pattern pt, String rawWord, boolean isOk,
                             String scope, long did, String targetId,
                             String prefix, String poolKey) {
        try {
            String word = rawWord == null ? "" : rawWord.trim();
            if (word.length() == 0) {
                cb.toast("请先填写判定词");
                return;
            }
            cb.addJudgeWordScoped(word, isOk, scope, did, targetId);
            UnkPool.remove(cb.prefs(), poolKey, pt.norm);
            String scopeLabel = "global".equals(scope) ? "全局"
                              : "bot".equals(scope) ? "这个 bot" : "这个目标";
            cb.toast("已加入" + (isOk ? "成功词" : "失败词") + "：「" + word + "」（" + scopeLabel + "）");
            cb.dismissAndRefresh();
        } catch (Throwable t) {
            cb.toast("保存失败：" + t);
        }
    }

    // ───────────────────── 判定词 ─────────────────────

    private View wordBlock(final boolean isOk) {
        LinearLayout card = block();

        TextView title = new TextView(act);
        title.setTextSize(Theme.TS_SECOND);
        title.setTextColor(isOk ? Theme.termGreen(act) : Theme.termPink(act));
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setText(isOk ? "成功词" : "失败词");
        card.addView(title);

        TextView sub = new TextView(act);
        sub.setTextSize(Theme.TS_CAPTION);
        sub.setTextColor(Theme.termFaint(act));
        sub.setTypeface(Theme.text());
        sub.setPadding(0, dp(2), 0, dp(6));
        sub.setText(isOk ? "命中即判签到成功" : "命中即判签到失败");
        card.addView(sub);

        // 2026-10-07：改为显示**三级全部**（全局 / Bot / 目标）。
        // 旧实现只读全局词，用户选「这个 bot」学的词写进了 jmb_ok_bot_<did>，
        // 这里一个字都不显示 → 看起来像"点了没进去"。
        java.util.List<String[]> words = wordsFor(isOk);
        if (words == null) words = new java.util.ArrayList<String[]>();
        if (words.isEmpty()) {
            TextView none = new TextView(act);
            none.setTextSize(Theme.TS_CAPTION);
            none.setTextColor(Theme.termFaint(act));
            none.setTypeface(Theme.text());
            none.setText("（暂无）");
            card.addView(none);
        } else {
            for (final String[] kv : words) {
                final String w = kv[0];
                final String scopeTag = kv.length > 1 ? kv[1] : "";
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = dp(4);
                row.setLayoutParams(rlp);

                TextView tv = new TextView(act);
                tv.setTextSize(Theme.TS_SECOND);
                tv.setTextColor(Theme.termTxt(act));
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setText(w + (scopeTag.length() > 0 ? "  · " + scopeTag : ""));
                row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView del = pill("删除", Theme.termPink(act), -1);   // wrap_content
                del.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { removeWordScoped(isOk, w, scopeTag); }
                });
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                dlp.leftMargin = dp(8);
                row.addView(del, dlp);
                card.addView(row);
            }
        }

        LinearLayout addRow = new LinearLayout(act);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(8);
        addRow.setLayoutParams(alp);

        final EditText ed = new EditText(act);
        ed.setHint(isOk ? "如：签到成功" : "如：次数已用完");
        ed.setHintTextColor(Theme.termFaint(act));
        ed.setTextSize(Theme.TS_SECOND);
        ed.setTextColor(Theme.termTxt(act));
        ed.setTypeface(Typeface.MONOSPACE);
        ed.setInputType(InputType.TYPE_CLASS_TEXT);
        ed.setSingleLine(true);
        ed.setPadding(dp(10), dp(9), dp(10), dp(9));
        try { ed.setBackground(controlBg(Theme.surface(act, 3))); } catch (Throwable ignored) {}
        addRow.addView(ed, new LinearLayout.LayoutParams(0, -2, 1f));
        addRow.addView(gap(8));

        TextView add = pill("添加", Theme.termTxt(act), -1);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String t = ed.getText().toString().trim();
                if (t.length() == 0) { cb.toast("请输入词"); return; }
                if (isOk) cb.addJudgeWords(new String[]{t}, null);
                else cb.addJudgeWords(null, new String[]{t});
                cb.toast("已添加「" + t + "」");
                ed.setText("");
                cb.dismissAndRefresh();
            }
        });
        addRow.addView(add, new LinearLayout.LayoutParams(-2, -2));
        card.addView(addRow);

        return card;
    }

    private void removeWord(boolean isOk, String word) {
        try {
            String cur = isOk ? okWordsText() : failWordsText();
            StringBuilder sb = new StringBuilder();
            for (String w : splitWords(cur)) {
                if (w.equals(word)) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(w);
            }
            cb.setJudgeWords(isOk ? sb.toString() : null, isOk ? null : sb.toString());
            cb.toast("已删除「" + word + "」");
            cb.dismissAndRefresh();
        } catch (Throwable t) {
            cb.toast("删除失败：" + t);
        }
    }

    private void actAs(ReplyNormalizer.Pattern pt, boolean success,
                       String prefix, String poolKey) {
        try {
            String word = extractWord(pt.norm);
            if (word == null || word.length() == 0) {
                cb.toast("这条提不出可用关键词，建议选「忽略」");
                return;
            }
            if (success) cb.addJudgeWords(new String[]{word}, null);
            else cb.addJudgeWords(null, new String[]{word});
            UnkPool.remove(cb.prefs(), poolKey, pt.norm);
            cb.toast("已学会「" + word + "」");
            cb.dismissAndRefresh();
        } catch (Throwable t) {
            cb.toast("保存失败：" + t);
        }
    }

    static String extractWord(String norm) {
        if (norm == null) return null;
        String s = norm.replace("{n}", "").trim();
        if (s.length() == 0) return null;

        String[] noise = {
            "广告", "骗子", "请勿", "不要上", "谨防", "上当",
            "欢迎使用", "欢迎来到", "我是", "接下来", "为您服务",
            "点击下方", "点击上方", "菜单按钮", "会话超时", "工单反馈",
            "未收录", "不消", "免责", "声明", "官方频道", "自动推送",
            "命令列表", "使用说明", "帮助", "请先", "重新打开",
        };
        for (String n : noise) {
            if (s.contains(n)) return null;
        }

        String[][] cores = {
            {"签到成功"}, {"打卡成功"}, {"签到完成"}, {"打卡完成"},
            {"签到已完成"}, {"打卡已完成"}, {"完成签到"}, {"完成打卡"},
            {"已签到成功"}, {"签到奖励"}, {"获得奖励"}, {"领取完成"},
            {"已经签到"}, {"已经打卡"}, {"今日已签"}, {"今日已签过"},
            {"已签到"}, {"已领取"}, {"已获得"}, {"已打卡"},
            {"次数已用完"}, {"今日次数"}, {"已达上限"}, {"请明日再试"},
            {"明日再来"}, {"签到"}, {"打卡"},
        };
        for (String[] c : cores) {
            if (s.contains(c[0])) return c[0];
        }
        return null;
    }

    // ───────────────────── UI 工具 ─────────────────────

    private LinearLayout block() {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(containerBg(Theme.surface(act, 1)));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(3));
        card.setLayoutParams(lp);
        return card;
    }

    private void sectionHeader(LinearLayout parent, String text) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(14);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        View bar = new View(act);
        bar.setBackgroundColor(Theme.termCyan(act));
        row.addView(bar, new LinearLayout.LayoutParams(dp(2), dp(13)));
        row.addView(gap(6));

        TextView t = new TextView(act);
        t.setTextSize(Theme.TS_SECOND);
        t.setTextColor(Theme.termMuted(act));
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setText(text);
        row.addView(t);
        parent.addView(row);
    }

    /**
     * 自绘按钮（TextView 实现）。
     *
     * 为什么不用 Button（2026-10-04 实测）：
     *   前几版 new Button 后无论怎么设样式，在 ColorOS/宿主主题下文字都被裁掉，
     *   出现「一排空框」。TextView 完全受控，且与 Core 的 menuTile 同一思路。
     *
     * @param weight >0 表示参与等分（0dp + weight），<=0 表示按内容自适应（wrap_content）
     */
    // 卡顿修复：同色 pill 背景只创建一次（原来每个 pill 都现场 new drawable）。
    private final java.util.HashMap<Integer, android.graphics.drawable.Drawable> pillBgCache =
            new java.util.HashMap<Integer, android.graphics.drawable.Drawable>();

    private android.graphics.drawable.Drawable pillBg(int fill) {
        try {
            android.graphics.drawable.Drawable d = pillBgCache.get(Integer.valueOf(fill));
            if (d == null) {
                d = controlBg(fill);
                pillBgCache.put(Integer.valueOf(fill), d);
            }
            // Drawable 会被多个 View 共享 → 必须开 mutate，否则状态互相影响
            return d.getConstantState() != null ? d.getConstantState().newDrawable() : d;
        } catch (Throwable t) {
            return controlBg(fill);
        }
    }

    private TextView pill(String label, int accent, int weight) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(Theme.TS_SECOND);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextColor(accent);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(pillBg(accentFill(accent)));
        if (weight > 0) {
            t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            t.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        }
        return t;
    }

    /** 按钮底色：用强调色混合出实色底，保证与文字有对比。 */
    private int accentFill(int accent) {
        if (Theme.dark(act)) return Theme.withAlpha(accent, 0x1F);
        return blendOn(Theme.surface(act, 2), accent, 0x1A);
    }

    private int blendOn(int base, int fg, int alpha) {
        return android.graphics.Color.argb(
                255,
                (android.graphics.Color.red(base)   * (255 - alpha) + android.graphics.Color.red(fg)   * alpha) / 255,
                (android.graphics.Color.green(base) * (255 - alpha) + android.graphics.Color.green(fg) * alpha) / 255,
                (android.graphics.Color.blue(base)  * (255 - alpha) + android.graphics.Color.blue(fg)  * alpha) / 255);
    }

    private View gap(int w) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1));
        return v;
    }

    private static String[] splitWords(String raw) {
        if (raw == null || raw.trim().length() == 0) return new String[0];
        String[] parts = raw.split("[,\\n，]");
        List<String> out = new ArrayList<String>();
        for (String s : parts) {
            String t = s.trim();
            if (t.length() > 0) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    private int dp(float v) { return Theme.dp(act, v); }

    private android.graphics.drawable.Drawable containerBg(int fill) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(Theme.R_CONTAINER));
        g.setColor(fill);
        return g;
    }

    private android.graphics.drawable.Drawable controlBg(int fill) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(Theme.R_CONTROL));
        g.setColor(fill);
        return g;
    }
}
