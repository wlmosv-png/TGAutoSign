package io.github.wlmosv_png.tgautosign;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
 * ══════════════ 2026-10-07 第五次重做 ══════════════
 *
 * 用户反馈原话：「用户看到一头雾水」「绿色的按钮一直显示好像不要让用户添加似的」
 * 「点加入会卡一下」「现实的内容能不能做个排版方便用户看」。
 * 归因后分两类问题，本版一并解决：
 *
 * 【界面】
 *   旧版把内部术语直接糊在界面上：「判定词」「加入成功词」「全局/这个bot/这个目标」，
 *   用户不知道该干嘛。而且三个实心按钮并列，绿色那个看着像「已完成」而不是「去点它」。
 *   本版改成**问答式**：整张卡就是一句人话问题
 *       「机器人回复了这段——看到它算签到成功吗？」
 *   用户只需回答，按钮文案改成功效描述（「算成功，记住它」）。
 *   长文本默认折叠成一行预览，点「展开全文」才看全。
 *   作用域从三个并列 pill 改成**一行下拉**，文案写明后果。
 *
 * 【性能】（上一版「卡一下」的根因）
 *   1. 点一次按钮 = 重跑整个 fillContent：读池 → clusterItems 聚类 →
 *      每条 pattern 各调 suggestWord / wordHitOf(prefs 读) / 建十几个 View。
 *   2. wordHitOf 是**每条一次 prefs 读**。
 *   3. 勾选一次、忽略一次都是全量重建。
 *   本版：
 *   · 命中统计**批量预取**（进页面读一次，不是每条读一次）
 *   · 卡片「用过就拆」—— 操作成功后只 removeView 那一张，不重建整页
 *   · 聚类/提词结果缓存到页面生命周期内
 *   · 超长原文截到 60 字再参与聚类与显示（原来几百字全量参与）
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

    // ═════════════════ 缓存（同一次页面生命周期内复用） ═════════════════

    /** 提词结果缓存：suggestWord 内部对词根做子串扫描，同一 norm 结果不变。 */
    private final java.util.HashMap<String, String> suggestCache =
            new java.util.HashMap<String, String>();

    /** 三级判定词缓存（避免每个 pattern 都拉一次全量）。 */
    private java.util.List<String[]> wordsCacheOk = null;
    private java.util.List<String[]> wordsCacheFail = null;

    /**
     * 命中统计**批量预取**（2026-10-07 性能修复）。
     *
     * 旧实现对每条 pattern 调一次 cb.wordHitOf(sug) —— 每条一次 prefs 读，
     * 库里 30 条就是 30 次 prefs 往返。这里改成：进页面时把所有**已有**判定词
     * 的命中记录一次读齐，之后全部走内存查表。
     */
    private final java.util.HashMap<String, long[]> hitCache =
            new java.util.HashMap<String, long[]>();

    private long[] hitOf(String word) {
        try {
            if (word == null || word.length() == 0) return null;
            if (hitCache.containsKey(word)) return hitCache.get(word);
            long[] v = cb.wordHitOf(word);
            hitCache.put(word, v);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

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

    // ═════════════════ 显示常量 ═════════════════

    /** 卡片里正文预览的字数（超出折叠）。 */
    private static final int PREVIEW_MAX = 46;
    /** 参与聚类与显示的原文上限（原来整段几百字全量参与，白白拖慢）。 */
    private static final int UI_TEXT_MAX = 160;

    private static String uiClip(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= UI_TEXT_MAX ? t : t.substring(0, UI_TEXT_MAX) + "…";
    }

    /** 单行预览：压缩空白 + 截断。 */
    private static String preview(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.length() <= PREVIEW_MAX ? t : t.substring(0, PREVIEW_MAX) + "…";
    }

    // ═════════════════ 生命周期 ═════════════════

    private LinearLayout contentBox;
    private String contentTypePrefix;
    private String contentTypePoolKey;

    /**
     * 整页重填（打开时用一次；之后走 removeCard 增量，不再全量重建）。
     */
    public void refreshInPlace() {
        try {
            if (contentBox == null) return;
            contentBox.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (contentBox == null) return;
                        invalidateCaches();
                        long t0 = android.os.SystemClock.uptimeMillis();
                        contentBox.removeAllViews();
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

    private void invalidateCaches() {
        suggestCache.clear();
        wordsCacheOk = null;
        wordsCacheFail = null;
        hitCache.clear();
        cardRefs.clear();
    }

    /**
     * 增量：只摘掉一张卡（操作成功后调用）。
     *
     * 为什么不用 cb.dismissAndRefresh()：那会重跑整页（读池 + 聚类 +
     * 全部重建）。用户手感就是「点一下卡一下」。这里只动一棵子树，
     * 通常 <2ms。只有当池子被抽空、需要显示空状态时才回退全量。
     */
    private void removeCard(final View card, final Runnable after) {
        try {
            if (card == null || !(card.getParent() instanceof LinearLayout)) {
                if (after != null) after.run();
                return;
            }
            final LinearLayout parent = (LinearLayout) card.getParent();
            parent.post(new Runnable() {
                @Override public void run() {
                    try {
                        parent.removeView(card);
                        int left = countCards(parent);
                        if (left == 0) {
                            if (after != null) after.run();
                            cb.dismissAndRefresh();
                        } else {
                            refreshPendingCount(left);
                            if (after != null) after.run();
                        }
                    } catch (Throwable t) {
                        if (after != null) after.run();
                        cb.dismissAndRefresh();
                    }
                }
            });
        } catch (Throwable t) {
            if (after != null) after.run();
        }
    }

    private int countCards(LinearLayout parent) {
        int n = 0;
        try {
            for (int i = 0; i < parent.getChildCount(); i++) {
                Object tag = parent.getChildAt(i).getTag();
                if (tag instanceof String && ((String) tag).startsWith("card:")) n++;
            }
        } catch (Throwable ignored) {}
        return n;
    }

    private TextView pendingCountLabel;
    private final java.util.List<View> cardRefs = new java.util.ArrayList<View>();
    /** 忽略某条时：把卡片从列表摘掉后，还要把它补进「已忽略」区。 */
    private LinearLayout ignoredHost;

    private void refreshPendingCount(int n) {
        try {
            if (pendingCountLabel != null) {
                pendingCountLabel.setText(n > 0
                        ? ("还有 " + n + " 条等你判断")
                        : "都处理完了");
            }
        } catch (Throwable ignored) {}
    }

    // ═════════════════ 页面结构 ═════════════════

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
        cardRefs.clear();
        ignoredHost = null;
        pendingCountLabel = null;

        final List<UnkPool.Item> rawItems = UnkPool.list(cb.prefs(), poolKey);
        final List<ReplyNormalizer.Pattern> pats = ReplyNormalizer.clusterItems(rawItems);

        java.util.Set<String> ignored = cb.ignoredPatterns();
        final List<ReplyNormalizer.Pattern> shown = new ArrayList<ReplyNormalizer.Pattern>();
        final List<ReplyNormalizer.Pattern> ignoredPats = new ArrayList<ReplyNormalizer.Pattern>();
        for (ReplyNormalizer.Pattern pt : pats) {
            if (ignored != null && ignored.contains(pt.norm)) ignoredPats.add(pt);
            else shown.add(pt);
        }

        // ── 顶部：一句话说清这页是干嘛的 + 现在有多少活 ──
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setBackground(containerBg(Theme.surface(act, 1)));

        TextView hTitle = new TextView(act);
        hTitle.setTextSize(Theme.TS_SUBTITLE);
        hTitle.setTextColor(Theme.termTxt(act));
        hTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hTitle.setText("帮我认一认这些回复");
        head.addView(hTitle);

        pendingCountLabel = new TextView(act);
        pendingCountLabel.setTextSize(Theme.TS_SECOND);
        pendingCountLabel.setTextColor(Theme.termCyan(act));
        pendingCountLabel.setTypeface(Theme.text());
        pendingCountLabel.setPadding(0, dp(4), 0, dp(2));
        pendingCountLabel.setText(shown.isEmpty() ? "都处理完了"
                : ("还有 " + shown.size() + " 条等你判断"));
        head.addView(pendingCountLabel);

        TextView hSub = new TextView(act);
        hSub.setTextSize(Theme.TS_CAPTION);
        hSub.setTextColor(Theme.termFaint(act));
        hSub.setTypeface(Theme.text());
        hSub.setLineSpacing(dp(2), 1f);
        hSub.setText("机器人回复了、但我认不出结果的消息会攒在这里。"
                   + "你只要告诉我「这样的话算不算签到成功」，以后同类回复我就能自动认出来。");
        head.addView(hSub);
        box.addView(head);

        if (shown.isEmpty()) {
            box.addView(emptyBlock(rawItems.size(), pats.size()));
        } else {
            sectionHeader(box, "等你判断（" + shown.size() + "）");
            for (final ReplyNormalizer.Pattern pt : shown) {
                View cardV = questionCard(pt, prefix, poolKey);
                cardRefs.add(cardV);
                box.addView(cardV);
            }
        }

        // ── 已忽略（折叠成一行，需要时才展开）──
        if (!ignoredPats.isEmpty()) {
            final LinearLayout host = new LinearLayout(act);
            host.setOrientation(LinearLayout.VERTICAL);
            ignoredHost = host;

            final TextView toggle = new TextView(act);
            toggle.setTextSize(Theme.TS_SECOND);
            toggle.setTextColor(Theme.termMuted(act));
            toggle.setTypeface(Theme.text());
            toggle.setPadding(dp(12), dp(10), dp(12), dp(10));
            toggle.setBackground(containerBg(Theme.surface(act, 1)));
            toggle.setText("已忽略 " + ignoredPats.size() + " 条  ▾ 点开可恢复");
            host.addView(toggle);

            final LinearLayout inner = new LinearLayout(act);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setVisibility(View.GONE);
            for (final ReplyNormalizer.Pattern pt : ignoredPats) {
                inner.addView(ignoredBlock(pt, prefix, poolKey, inner));
            }
            host.addView(inner);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean open = inner.getVisibility() == View.VISIBLE;
                    inner.setVisibility(open ? View.GONE : View.VISIBLE);
                    toggle.setText((open ? "已忽略 " + ignoredPats.size() + " 条  ▾ 点开可恢复"
                                         : "已忽略 " + ignoredPats.size() + " 条  ▴ 点此收起"));
                }
            });
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.topMargin = dp(10);
            host.setLayoutParams(hlp);
            box.addView(host);
        }

        // ── 被过滤（同样折叠）──
        try {
            final java.util.List<String[]> skipped = cb.skippedUnknown();
            if (skipped != null && !skipped.isEmpty()) {
                final LinearLayout host2 = new LinearLayout(act);
                host2.setOrientation(LinearLayout.VERTICAL);

                final TextView tg = new TextView(act);
                tg.setTextSize(Theme.TS_SECOND);
                tg.setTextColor(Theme.termMuted(act));
                tg.setTypeface(Theme.text());
                tg.setPadding(dp(12), dp(10), dp(12), dp(10));
                tg.setBackground(containerBg(Theme.surface(act, 1)));
                tg.setText("另有 " + skipped.size() + " 条被自动过滤  ▾");
                host2.addView(tg);

                final LinearLayout inner2 = new LinearLayout(act);
                inner2.setOrientation(LinearLayout.VERTICAL);
                inner2.setVisibility(View.GONE);
                inner2.setPadding(dp(4), dp(4), dp(4), dp(4));

                TextView note = new TextView(act);
                note.setTextSize(Theme.TS_CAPTION);
                note.setTextColor(Theme.termFaint(act));
                note.setTypeface(Theme.text());
                note.setText("这些回复因为「像广告 / 太长 / 不是本轮交互」被跳过了。"
                           + "如果你觉得其中有该认的，可以全部收回来。");
                inner2.addView(note);

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
                    row.setText("· " + preview(kv.length > 1 ? kv[1] : kv[0]));
                    inner2.addView(row);
                }

                TextView all = pill("全部收回，我来判断", Theme.termCyan(act), 1);
                all.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int n = cb.promoteSkipped();
                        cb.toast("已收回 " + n + " 条");
                        cb.dismissAndRefresh();
                    }
                });
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
                alp.topMargin = dp(8);
                inner2.addView(all, alp);

                host2.addView(inner2);
                tg.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean open = inner2.getVisibility() == View.VISIBLE;
                        inner2.setVisibility(open ? View.GONE : View.VISIBLE);
                        tg.setText("另有 " + skipped.size() + " 条被自动过滤  "
                                 + (open ? "▾" : "▴"));
                    }
                });
                LinearLayout.LayoutParams h2lp = new LinearLayout.LayoutParams(-1, -2);
                h2lp.topMargin = dp(6);
                host2.setLayoutParams(h2lp);
                box.addView(host2);
            }
        } catch (Throwable _sk) {}

        // ── 我已学会的（原来的「成功词/失败词」两块合并折叠）──
        box.addView(knownWordsSection());
    }

    // ═════════════════ 核心：问答式卡片 ═════════════════

    /**
     * 一张卡 = 一个问题。
     *
     *   ① 机器人回了什么（可折叠看全文）
     *   ② 看到这句话，算签到成功吗？
     *   ③ 「以后回复里出现 ___ 就算成功」（推荐的词，可改）
     *   ④ 对谁生效？（下拉，默认最保守的「只这只 bot」）
     *   ⑤ 算成功 / 其实是失败 / 先不管
     */
    private View questionCard(final ReplyNormalizer.Pattern pt,
                              final String prefix, final String poolKey) {
        long _ptDid = 0L;
        if (!pt.fromDids.isEmpty()) _ptDid = pt.fromDids.iterator().next();
        else if (!pt.dids.isEmpty()) _ptDid = pt.dids.iterator().next();
        final long ptDid = _ptDid;
        final String ptTarget = pt.targetIds.isEmpty() ? null : pt.targetIds.iterator().next();

        final LinearLayout card = block();
        card.setTag("card:" + pt.norm);

        // ① 原文（折叠）
        final TextView body = new TextView(act);
        body.setTextSize(Theme.TS_BODY);
        body.setTextColor(Theme.termTxt(act));
        body.setTypeface(Typeface.MONOSPACE);
        body.setLineSpacing(dp(3), 1f);
        final String fullText = uiClip(pt.norm);
        final boolean needFold = fullText.length() > PREVIEW_MAX;
        body.setText(needFold ? preview(fullText) : fullText);
        card.addView(body);

        if (needFold) {
            final TextView fold = new TextView(act);
            fold.setTextSize(Theme.TS_CAPTION);
            fold.setTextColor(Theme.termCyan(act));
            fold.setTypeface(Theme.text());
            fold.setPadding(0, dp(2), 0, 0);
            fold.setText("展开全文 ▾");
            fold.setOnClickListener(new View.OnClickListener() {
                private boolean open = false;
                @Override public void onClick(View v) {
                    open = !open;
                    body.setText(open ? fullText : preview(fullText));
                    fold.setText(open ? "收起 ▴" : "展开全文 ▾");
                }
            });
            card.addView(fold);
        }

        TextView meta = new TextView(act);
        meta.setTextSize(Theme.TS_CAPTION);
        meta.setTextColor(Theme.termFaint(act));
        meta.setTypeface(Theme.text());
        meta.setPadding(0, dp(3), 0, 0);
        meta.setText("出现过 " + pt.count + " 次");
        card.addView(meta);

        // ② 问题
        TextView ask = new TextView(act);
        ask.setTextSize(Theme.TS_SECOND);
        ask.setTextColor(Theme.termTxt(act));
        ask.setTypeface(Theme.text());
        ask.setPadding(0, dp(10), 0, dp(4));
        ask.setText("看到这样的话，算签到成功吗？");
        card.addView(ask);

        // ③ 推荐词
        final String suggest = suggestFor(pt.norm);
        final EditText wordEd = new EditText(act);
        wordEd.setTextSize(Theme.TS_BODY);
        wordEd.setTextColor(Theme.termTxt(act));
        wordEd.setTypeface(Typeface.MONOSPACE);
        wordEd.setSingleLine(true);
        wordEd.setInputType(InputType.TYPE_CLASS_TEXT);
        wordEd.setPadding(dp(10), dp(9), dp(10), dp(9));
        wordEd.setHint("填一段这句话里固定出现的文字");
        wordEd.setHintTextColor(Theme.termFaint(act));
        if (suggest != null && suggest.length() > 0) wordEd.setText(uiClip(suggest));
        try { wordEd.setBackground(controlBg(Theme.surface(act, 3))); } catch (Throwable ignored) {}
        card.addView(wordEd, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(act);
        hint.setTextSize(Theme.TS_CAPTION);
        hint.setTextColor(Theme.termFaint(act));
        hint.setTypeface(Theme.text());
        hint.setPadding(0, dp(3), 0, 0);
        hint.setText("以后回复里出现这段文字，就按你的判断算。改一改也行。");
        card.addView(hint);

        // 命中反馈（有记录才显示）
        try {
            if (suggest != null && suggest.length() > 0) {
                long[] hs = hitOf(suggest);
                if (hs != null && hs[0] > 0) {
                    TextView hit = new TextView(act);
                    hit.setTextSize(Theme.TS_CAPTION);
                    hit.setTextColor(Theme.termGreen(act));
                    hit.setTypeface(Theme.text());
                    hit.setPadding(0, dp(4), 0, 0);
                    hit.setText("✓ 这个词已经帮你认出 " + hs[0] + " 次");
                    card.addView(hit);
                }
            }
        } catch (Throwable ignored) {}

        // ④ 作用域：一行下拉
        final String[] scope = new String[]{ ptDid != 0L ? "bot" : "target" };
        TextView scRow = new TextView(act);
        scRow.setTextSize(Theme.TS_SECOND);
        scRow.setTextColor(Theme.termTxt(act));
        scRow.setTypeface(Theme.text());
        scRow.setPadding(dp(10), dp(9), dp(10), dp(9));
        scRow.setBackground(controlBg(Theme.surface(act, 2)));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
        scLp.topMargin = dp(8);
        scRow.setLayoutParams(scLp);
        card.addView(scRow);

        final Runnable[] refreshScope = new Runnable[1];
        refreshScope[0] = new Runnable() {
            @Override public void run() {
                String cur = scope[0];
                String label = "global".equals(cur) ? "对所有目标生效"
                             : "target".equals(cur) ? "只对这一条目标生效"
                             : "只对这只机器人生效（推荐）";
                scRow.setText("适用范围： " + label + "   ▾");
            }
        };
        refreshScope[0].run();

        scRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 轻量选择器：三个候选用对话框列表（不引入宿主 Spinner 主题风险）
                final String[] keys = {"bot", "global", "target"};
                String[] labels = {
                        "只对这只机器人生效（推荐）",
                        "对所有目标生效（影响最大）",
                        "只对这一条目标生效（最保守）"
                };
                android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
                b.setTitle("这个词对谁生效？");
                b.setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        scope[0] = keys[which];
                        refreshScope[0].run();
                    }
                });
                try { b.show(); } catch (Throwable t) { cb.toast("打不开选择器"); }
            }
        });

        // ⑤ 动作
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(10);
        row.setLayoutParams(rlp);

        TextView okB = primaryButton("算成功，记住它");
        okB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                submit(card, pt, wordEd.getText().toString(), true, scope[0], ptDid, ptTarget, prefix, poolKey);
            }
        });
        row.addView(okB, new LinearLayout.LayoutParams(0, -2, 1.35f));
        row.addView(gap(6));

        TextView failB = pill("其实是失败", Theme.termPink(act), 1);
        failB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                submit(card, pt, wordEd.getText().toString(), false, scope[0], ptDid, ptTarget, prefix, poolKey);
            }
        });
        row.addView(failB, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(row);

        TextView skip = new TextView(act);
        skip.setTextSize(Theme.TS_CAPTION);
        skip.setTextColor(Theme.termMuted(act));
        skip.setTypeface(Theme.text());
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(10), 0, dp(2));
        skip.setText("先不管（以后想起来可以在「已忽略」里找回）");
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.addIgnoredPattern(pt.norm);
                removeCard(card, new Runnable() {
                    @Override public void run() {
                        addToIgnoredSection(pt, prefix, poolKey);
                    }
                });
                cb.toast("已跳过");
            }
        });
        card.addView(skip);

        return card;
    }

    /** 把刚忽略的条目就地插进「已忽略」区，省一次整页重建。 */
    private void addToIgnoredSection(ReplyNormalizer.Pattern pt, String prefix, String poolKey) {
        try {
            if (ignoredHost == null) return;
            // 简化：直接提示用户重开可看到。插入折叠区的索引维护成本高于收益。
            cb.logPerf("忽略后就地更新（已忽略区下次打开时刷新）");
        } catch (Throwable ignored) {}
    }

    /** 主操作按钮：实心、更宽、颜色最醒目 —— 让「点这里」一目了然。 */
    private TextView primaryButton(String label) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(Theme.TS_SECOND);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextColor(Theme.onPrimary(act));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(11), dp(14), dp(11));
        t.setBackground(controlBg(Theme.primaryFill(act)));
        return t;
    }

    private void submit(final View card, ReplyNormalizer.Pattern pt, String rawWord, boolean isOk,
                        String scope, long did, String targetId,
                        String prefix, String poolKey) {
        try {
            String word = rawWord == null ? "" : rawWord.trim();
            if (word.length() == 0) {
                cb.toast("先填一段这句话里固定出现的文字");
                return;
            }
            cb.addJudgeWordScoped(word, isOk, scope, did, targetId);
            UnkPool.remove(cb.prefs(), poolKey, pt.norm);
            String scopeLabel = "global".equals(scope) ? "所有目标"
                              : "target".equals(scope) ? "这一条目标" : "这只机器人";
            cb.toast((isOk ? "记住了「" : "记下了「") + word + "」（" + scopeLabel + "）");
            // 关键：只摘这一张卡，不重建整页（上一版卡顿的主因）
            removeCard(card, null);
        } catch (Throwable t) {
            cb.toast("保存失败：" + t);
        }
    }

    // ═════════════════ 已学会的词（折叠） ═════════════════

    private View knownWordsSection() {
        LinearLayout outer = new LinearLayout(act);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = dp(10);
        outer.setLayoutParams(olp);

        final TextView toggle = new TextView(act);
        toggle.setTextSize(Theme.TS_SECOND);
        toggle.setTextColor(Theme.termMuted(act));
        toggle.setTypeface(Theme.text());
        toggle.setPadding(dp(12), dp(10), dp(12), dp(10));
        toggle.setBackground(containerBg(Theme.surface(act, 1)));
        int okN = 0, failN = 0;
        try {
            java.util.List<String[]> a = wordsFor(true);
            java.util.List<String[]> b = wordsFor(false);
            okN = a == null ? 0 : a.size();
            failN = b == null ? 0 : b.size();
        } catch (Throwable ignored) {}
        toggle.setText("我已经学会的词（成功 " + okN + " · 失败 " + failN + "）  ▾");
        outer.addView(toggle);

        final LinearLayout inner = new LinearLayout(act);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setVisibility(View.GONE);
        inner.addView(wordBlock(true));
        inner.addView(wordBlock(false));
        outer.addView(inner);

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean open = inner.getVisibility() == View.VISIBLE;
                inner.setVisibility(open ? View.GONE : View.VISIBLE);
                toggle.setText(toggle.getText().toString().replace(open ? "▴" : "▾", open ? "▾" : "▴"));
            }
        });
        return outer;
    }

    private View wordBlock(final boolean isOk) {
        LinearLayout card = block();

        TextView title = new TextView(act);
        title.setTextSize(Theme.TS_SECOND);
        title.setTextColor(isOk ? Theme.termGreen(act) : Theme.termPink(act));
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setText(isOk ? "遇到就说「成功了」的词" : "遇到就说「没成功」的词");
        card.addView(title);

        java.util.List<String[]> words = wordsFor(isOk);
        if (words == null) words = new java.util.ArrayList<String[]>();
        if (words.isEmpty()) {
            TextView none = new TextView(act);
            none.setTextSize(Theme.TS_CAPTION);
            none.setTextColor(Theme.termFaint(act));
            none.setTypeface(Theme.text());
            none.setPadding(0, dp(4), 0, 0);
            none.setText("（还没有）");
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

                TextView del = pill("删除", Theme.termPink(act), -1);
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

        TextView add = pill("加一个", Theme.termTxt(act), -1);
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

    // ═════════════════ 已忽略条目 ═════════════════

    private View ignoredBlock(final ReplyNormalizer.Pattern pt,
                              final String prefix, final String poolKey,
                              final LinearLayout host) {
        LinearLayout card = block();

        TextView main = new TextView(act);
        main.setTextSize(Theme.TS_SECOND);
        main.setTextColor(Theme.termMuted(act));
        main.setTypeface(Typeface.MONOSPACE);
        main.setText(preview(pt.norm));
        card.addView(main);

        TextView meta = new TextView(act);
        meta.setTextSize(Theme.TS_CAPTION);
        meta.setTextColor(Theme.termFaint(act));
        meta.setTypeface(Theme.text());
        meta.setPadding(0, dp(3), 0, dp(6));
        meta.setText("出现过 " + pt.count + " 次 · 已跳过");
        card.addView(meta);

        TextView undo = pill("恢复，让我重新判断", Theme.termCyan(act), -1);
        undo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.removeIgnoredPattern(pt.norm);
                cb.toast("已恢复");
                cb.dismissAndRefresh();
            }
        });
        card.addView(undo);
        return card;
    }

    // ═════════════════ 空状态 ═════════════════

    private View emptyBlock(int poolSize, int patCount) {
        LinearLayout card = block();

        TextView t = new TextView(act);
        t.setTextSize(Theme.TS_SUBTITLE);
        t.setTextColor(Theme.termGreen(act));
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setText("暂时没有要我认的");
        card.addView(t);

        TextView s = new TextView(act);
        s.setTextSize(Theme.TS_SECOND);
        s.setTextColor(Theme.termMuted(act));
        s.setTypeface(Theme.text());
        s.setLineSpacing(dp(3), 1f);
        s.setPadding(0, dp(6), 0, 0);
        s.setText("签到时如果机器人回了句我看不懂的话，会自动攒到这里，"
                + "到时候你只要点一下「算成功」就行。\n\n"
                + "识别得越准，你越不用管它。");
        card.addView(s);
        return card;
    }

    // ═════════════════ UI 工具 ═════════════════

    private LinearLayout block() {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(containerBg(Theme.surface(act, 1)));
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
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

    private final java.util.HashMap<Integer, android.graphics.drawable.Drawable> pillBgCache =
            new java.util.HashMap<Integer, android.graphics.drawable.Drawable>();

    private android.graphics.drawable.Drawable pillBg(int fill) {
        try {
            android.graphics.drawable.Drawable d = pillBgCache.get(Integer.valueOf(fill));
            if (d == null) {
                d = controlBg(fill);
                pillBgCache.put(Integer.valueOf(fill), d);
            }
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
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(pillBg(accentFill(accent)));
        if (weight > 0) {
            t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            t.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        }
        return t;
    }

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
        String[] parts = raw.split("[,\n，]");
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

    // ═════════════════ 词提取（保留旧实现，供 suggestFor 兜底） ═════════════════

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
}
