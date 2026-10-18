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

        /**
         * 判定词表发生变化（新增/删除）后调用。
         *
         * 2026-10-18：从「卡住的目标 → 补判定词」进来时，学完必须让那张卡片
         * 重新评估一次 —— 否则用户补完词回到列表，看到的仍是「判不出」。
         */
        void notifyWordsChanged();
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
     * 折叠区展开状态（2026-10-19）。
     *
     * 用户实测：添加/删除一个词后整页重建，所有折叠区又折回去，
     * 得重新点开 —— 操作三个词就要点三次展开。
     * 这里记住状态，重建时恢复；只在本次打开周期内有效。
     */
    private boolean expandIgnored = false;
    private boolean expandSkipped = false;
    private boolean expandKnown = false;

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
    /**
     * 压缩连续重复的装饰符（2026-10-19 性能修复）。
     *
     * 为什么：bot 常用 ━ ═ ─ ▬ ● 之类画分隔线，一行能连出二三十个。
     * 在等宽字体下每个都是 CJK 宽字符且无断行机会，
     * 排版器必须逐字符测量 → 单个 TextView onMeasure 实测 926ms（真机日志）。
     *
     * 只影响**显示**，不动聚类键与判定值。
     */
    static String squeezeDeco(String s) {
        if (s == null || s.length() == 0) return "";
        String t = s;
        // 同一装饰符连续出现 3 次以上 → 压成 3 个（视觉上仍是短线）
        t = t.replaceAll("([\u2501\u2500\u2550\u2500\u2581\u2584\u2588\u25AC\u25AE\u2E3B])\\1{3,}", "$1$1$1");
        // 通用：任意同一非字母数字字符连续 4 次以上 → 3 个
        t = t.replaceAll("([^\\p{L}\\p{N}\\s])\\1{3,}", "$1$1$1");
        return t;
    }

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
    /** 每批渲染多少张卡（2026-10-09）。 */
    private static final int BATCH = 12;

    /** 渲染 [from, to) 区间的问题卡。 */
    private void renderBatch(LinearLayout box, List<ReplyNormalizer.Pattern> shown,
                             int from, int to, String prefix, String poolKey) {
        for (int i = from; i < to && i < shown.size(); i++) {
            try {
                final ReplyNormalizer.Pattern pt = shown.get(i);
                View cardV = questionCard(pt, prefix, poolKey);
                cardRefs.add(cardV);
                box.addView(cardV);
            } catch (Throwable ignored) {}
        }
    }

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
    /** 当前卡片的输入框（空词时自动聚焦用）。 */
    private EditText inputRef;
    private final java.util.List<View> cardRefs = new java.util.ArrayList<View>();
    /** 忽略某条时：把卡片从列表摘掉后，还要把它补进「已忽略」区。 */
    private LinearLayout ignoredHost;

    private void refreshPendingCount(int n) {
        try {
            if (pendingCountLabel != null) {
                pendingCountLabel.setText(n > 0
                        ? Lang.tf("还有 {0} 条等你判断", n)
                        : Lang.tr("都处理完了"));
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

        // 2026-10-18：细粒度计时（用户实测 build=105ms，需定位到具体阶段）
        long _t0 = android.os.SystemClock.uptimeMillis();
        final List<UnkPool.Item> rawItems = UnkPool.list(cb.prefs(), poolKey);
        long _t1 = android.os.SystemClock.uptimeMillis();
        final List<ReplyNormalizer.Pattern> pats = ReplyNormalizer.clusterItems(rawItems);
        long _t2 = android.os.SystemClock.uptimeMillis();

        java.util.Set<String> ignored = cb.ignoredPatterns();
        long _t3 = android.os.SystemClock.uptimeMillis();
        try {
            cb.logPerf("fillContent 分段: poolList=" + (_t1 - _t0) + "ms(" + rawItems.size()
                     + "条) cluster=" + (_t2 - _t1) + "ms(" + pats.size()
                     + "模式) ignored=" + (_t3 - _t2) + "ms");
        } catch (Throwable ignored2) {}
        final List<ReplyNormalizer.Pattern> shown = new ArrayList<ReplyNormalizer.Pattern>();
        final List<ReplyNormalizer.Pattern> ignoredPats = new ArrayList<ReplyNormalizer.Pattern>();
        for (ReplyNormalizer.Pattern pt : pats) {
            if (ignored != null && ignored.contains(pt.norm)) ignoredPats.add(pt);
            else shown.add(pt);
        }

        long _h0 = android.os.SystemClock.uptimeMillis();
        // ── 顶部：一句话说清这页是干嘛的 + 现在有多少活 ──
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setBackground(containerBg(Theme.surface(act, 1)));

        // ── 2026-10-19 去臃肿：标题与计数合成一行，解释文字全删 ──
        // 用户反馈「很臃肿」。原来头部是「标题 + 计数 + 两段解释」共四行，
        // 占掉首屏近 1/4，而解释文字看过一次就再也不需要。
        LinearLayout headRow = new LinearLayout(act);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView hTitle = new TextView(act);
        hTitle.setTextSize(Theme.TS_SUBTITLE);
        hTitle.setTextColor(Theme.termTxt(act));
        hTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hTitle.setText(Lang.tr("帮我认一认"));
        headRow.addView(hTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        pendingCountLabel = new TextView(act);
        pendingCountLabel.setTextSize(Theme.TS_SECOND);
        pendingCountLabel.setTextColor(Theme.termCyan(act));
        pendingCountLabel.setTypeface(Theme.text());
        pendingCountLabel.setGravity(Gravity.END);
        pendingCountLabel.setText(shown.isEmpty()
                ? Lang.tr("都处理完了")
                : Lang.tf("{0} 条待判", shown.size()));
        headRow.addView(pendingCountLabel, new LinearLayout.LayoutParams(-2, -2));
        head.addView(headRow);
        box.addView(head);

        long _h1 = android.os.SystemClock.uptimeMillis();
        if (shown.isEmpty()) {
            box.addView(emptyBlock(rawItems.size(), pats.size()));
        } else {
            sectionHeader(box, Lang.tf("等你判断（{0}）", shown.size()));
            // ── 2026-10-09 分批渲染 ──
            // 池上限 200 条，而每张卡要建十几个控件 —— 一次全建就是 2000+ 控件，
            // 打开必然卡（用户反馈「目标条数多了就卡卡卡」）。
            // 这里先渲染一屏够看的量，剩下的按需追加。
            renderBatch(box, shown, 0, Math.min(BATCH, shown.size()), prefix, poolKey);
            if (shown.size() > BATCH) {
                final TextView more = new TextView(act);
                more.setTextSize(Theme.TS_SECOND);
                more.setTextColor(Theme.termCyan(act));
                more.setTypeface(Theme.text());
                more.setGravity(Gravity.CENTER);
                more.setPadding(dp(12), dp(12), dp(12), dp(12));
                more.setBackground(containerBg(Theme.surface(act, 1)));
                more.setText(Lang.tf("还有 {0} 条 · 点此展开", shown.size() - BATCH));
                more.setOnClickListener(new View.OnClickListener() {
                    private int from = BATCH;
                    @Override public void onClick(View v) {
                        int to = Math.min(shown.size(), from + BATCH);
                        renderBatch(box, shown, from, to, prefix, poolKey);
                        from = to;
                        int left = shown.size() - to;
                        if (left > 0) {
                            more.setText(Lang.tf("还有 {0} 条 · 点此展开", left));
                        } else {
                            try { ((android.view.ViewGroup) box).removeView(more); } catch (Throwable ignored) {}
                        }
                        // 2026-10-18 修算术：原式 size-(to-BATCH)+(to-from)
                        // 里 to-from 恒等于 BATCH，to-BATCH 越展越大 → 剩余数越展越多。
                        refreshPendingCount(shown.size() - to);
                    }
                });
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
                mlp.topMargin = dp(6);
                more.setLayoutParams(mlp);
                box.addView(more);
            }
        }

        long _h2 = android.os.SystemClock.uptimeMillis();
        try {
            cb.logPerf("build 分段: head=" + (_h1 - _h0) + "ms cards=" + (_h2 - _h1)
                     + "ms (" + shown.size() + "张)");
        } catch (Throwable ignoredPerf) {}
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
            toggle.setText(expandIgnored
                    ? Lang.tf("已忽略 {0} 条  ▴ 点此收起", ignoredPats.size())
                    : Lang.tf("已忽略 {0} 条  ▾ 点开可恢复", ignoredPats.size()));
            host.addView(toggle);

            final LinearLayout inner = new LinearLayout(act);
            inner.setOrientation(LinearLayout.VERTICAL);
            // 2026-10-19：恢复上次的展开状态，别把用户刚点开的又折回去
            inner.setVisibility(expandIgnored ? View.VISIBLE : View.GONE);
            // 2026-10-18：分批。改前 for 全建 —— 已忽略条数一多就是
            // 几百个控件一次性构建，展开时整页卡顿（与「等你判断」同因）。
            final int ignBatch = Math.min(BATCH, ignoredPats.size());
            for (int ii = 0; ii < ignBatch; ii++) {
                inner.addView(ignoredBlock(ignoredPats.get(ii), prefix, poolKey, inner));
            }
            final TextView ignMore = new TextView(act);
            ignMore.setTextSize(Theme.TS_CAPTION);
            ignMore.setTextColor(Theme.termCyan(act));
            ignMore.setTypeface(Theme.text());
            ignMore.setGravity(Gravity.CENTER);
            ignMore.setPadding(dp(12), dp(10), dp(12), dp(10));
            if (ignoredPats.size() > ignBatch) {
                ignMore.setText(Lang.tf("还有 {0} 条 · 点此展开", ignoredPats.size() - ignBatch));
                ignMore.setOnClickListener(new View.OnClickListener() {
                    private int fromI = ignBatch;
                    @Override public void onClick(View v) {
                        int toI = Math.min(ignoredPats.size(), fromI + BATCH);
                        for (int ii = fromI; ii < toI; ii++) {
                            inner.addView(ignoredBlock(ignoredPats.get(ii), prefix, poolKey, inner));
                        }
                        fromI = toI;
                        int leftI = ignoredPats.size() - toI;
                        if (leftI > 0) {
                            ignMore.setText(Lang.tf("还有 {0} 条 · 点此展开", leftI));
                        } else {
                            try { ((android.view.ViewGroup) ignMore.getParent()).removeView(ignMore); }
                            catch (Throwable ignored) {}
                        }
                    }
                });
                inner.addView(ignMore);
            }
            host.addView(inner);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean open = inner.getVisibility() == View.VISIBLE;
                    inner.setVisibility(open ? View.GONE : View.VISIBLE);
                    expandIgnored = !open;   // 2026-10-19 记住状态
                    toggle.setText(open
                            ? Lang.tf("已忽略 {0} 条  ▾ 点开可恢复", ignoredPats.size())
                            : Lang.tf("已忽略 {0} 条  ▴ 点此收起", ignoredPats.size()));
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
                tg.setText(Lang.tf("另有 {0} 条被自动过滤  ", skipped.size())
                         + (expandSkipped ? "▴" : "▾"));
                host2.addView(tg);

                final LinearLayout inner2 = new LinearLayout(act);
                inner2.setOrientation(LinearLayout.VERTICAL);
                inner2.setVisibility(expandSkipped ? View.VISIBLE : View.GONE);
                inner2.setPadding(dp(4), dp(4), dp(4), dp(4));

                TextView note = new TextView(act);
                note.setTextSize(Theme.TS_CAPTION);
                note.setTextColor(Theme.termFaint(act));
                note.setTypeface(Theme.text());
                note.setText(Lang.tr("这些回复因为「像广告 / 太长 / 不是本轮交互」被跳过了。"
                                   + "如果你觉得其中有该认的，可以全部收回来。"));
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

                TextView all = pill(Lang.tr("全部收回，我来判断"), Theme.termCyan(act), 1);
                all.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int n = cb.promoteSkipped();
                        cb.toast(Lang.tf("已收回 {0} 条", n));
                        // 2026-10-18：不再 dismissAndRefresh（整页重建 = 所有卡重做，
                        // 收回几十条时用户手感是「点一下卡死」）。走原地刷新，
                        // 只清内容容器重填，对话框不动。
                        try {
                            if (canRefreshInPlace()) { refreshInPlace(); return; }
                        } catch (Throwable ignored) {}
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
                        expandSkipped = !open;   // 2026-10-19 记住状态
                        tg.setText(Lang.tf("另有 {0} 条被自动过滤  ", skipped.size())
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

        // ── ① 原话（唯一的主体，点它展开/收起）──
        String raw0 = null;
        try {
            for (String s : pt.samples) {
                if (s == null) continue;
                if (raw0 == null || s.length() > raw0.length()) raw0 = s;
            }
        } catch (Throwable ignored) {}
        if (raw0 == null || raw0.trim().length() == 0) raw0 = pt.norm;
        final String fullText = uiClip(squeezeDeco(raw0));
        final String shortText = preview(fullText);

        final TextView body = new TextView(act);
        body.setTextSize(Theme.TS_BODY);
        body.setTextColor(Theme.termTxt(act));
        body.setTypeface(Typeface.MONOSPACE);
        body.setLineSpacing(dp(2), 1f);
        try {
            body.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            body.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        } catch (Throwable ignored) {}
        body.setText(shortText);
        card.addView(body);

        // 点正文展开/收起全文（替代原来占一整行的「展开全文 ▾」）
        if (fullText.length() > PREVIEW_MAX) {
            body.setOnClickListener(new View.OnClickListener() {
                private boolean open = false;
                @Override public void onClick(View v) {
                    open = !open;
                    body.setText(open ? fullText : shortText);
                }
            });
        }

        // ── ② 词 + 作用域（同一行：词占满，作用域贴右）──
        final String suggest = suggestFor(pt.norm);
        final String suggestVal = (suggest != null && suggest.length() > 0) ? uiClip(suggest) : "";

        final LinearLayout wordRow = new LinearLayout(act);
        wordRow.setOrientation(LinearLayout.HORIZONTAL);
        wordRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wrlp = new LinearLayout.LayoutParams(-1, -2);
        wrlp.topMargin = dp(8);
        wordRow.setLayoutParams(wrlp);

        final android.widget.FrameLayout wordHost = new android.widget.FrameLayout(act);
        wordHost.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView wordPh = new TextView(act);
        wordPh.setTextSize(Theme.TS_SECOND);
        wordPh.setTextColor(suggestVal.length() > 0 ? Theme.termTxt(act) : Theme.termFaint(act));
        wordPh.setSingleLine(true);
        wordPh.setEllipsize(android.text.TextUtils.TruncateAt.END);
        wordPh.setPadding(dp(8), dp(6), dp(8), dp(6));
        wordPh.setText(suggestVal.length() > 0 ? suggestVal : Lang.tr("点这里自定义判定词"));
        try { wordPh.setBackground(controlBg(Theme.surface(act, 3))); } catch (Throwable ignored) {}
        wordHost.addView(wordPh, new android.widget.FrameLayout.LayoutParams(-1, -2));
        wordRow.addView(wordHost);

        final EditText[] lazyEd = new EditText[1];
        final Runnable ensureEditor = new Runnable() {
            @Override public void run() {
                if (lazyEd[0] != null) {
                    try { lazyEd[0].requestFocus(); } catch (Throwable ignored) {}
                    return;
                }
                EditText e;
                try { e = new EditText(act); } catch (Throwable t) { return; }
                e.setTextSize(Theme.TS_SECOND);
                e.setTextColor(Theme.termTxt(act));
                e.setSingleLine(true);
                e.setInputType(InputType.TYPE_CLASS_TEXT);
                e.setPadding(dp(8), dp(6), dp(8), dp(6));
                e.setHint(Lang.tr("填这句话里固定出现的文字"));
                e.setHintTextColor(Theme.termFaint(act));
                if (suggestVal.length() > 0) {
                    e.setText(suggestVal);
                    e.setSelection(e.getText().length());
                }
                try { e.setBackground(controlBg(Theme.surface(act, 3))); } catch (Throwable ignored) {}
                wordHost.removeAllViews();
                wordHost.addView(e, new android.widget.FrameLayout.LayoutParams(-1, -2));
                lazyEd[0] = e;
                inputRef = e;
                e.requestFocus();
                try {
                    android.view.inputmethod.InputMethodManager im =
                        (android.view.inputmethod.InputMethodManager)
                        act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (im != null) im.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                } catch (Throwable ignored) {}
            }
        };
        wordPh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ensureEditor.run(); }
        });

        // 作用域：紧凑标签，点开选（原来占整行）
        final String[] scope = new String[]{ ptDid != 0L ? "bot" : "target" };
        final TextView scopeTagView = new TextView(act);
        scopeTagView.setTextSize(Theme.TS_CAPTION);
        scopeTagView.setTextColor(Theme.termCyan(act));
        scopeTagView.setTypeface(Theme.text());
        scopeTagView.setPadding(dp(8), dp(6), dp(4), dp(6));
        final Runnable refreshScope = new Runnable() {
            @Override public void run() {
                String cur = scope[0];
                scopeTagView.setText("global".equals(cur) ? Lang.tr("所有目标 ▾")
                        : "target".equals(cur) ? Lang.tr("仅此条 ▾") : Lang.tr("仅此bot ▾"));
            }
        };
        refreshScope.run();
        scopeTagView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] keys = {"bot", "target", "global"};
                String[] labels = {
                        Lang.tr("只对这只机器人生效（推荐）"),
                        Lang.tr("只对这一条目标生效（最保守）"),
                        Lang.tr("对所有目标生效（影响最大）")
                };
                android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
                b.setTitle(Lang.tr("这个词对谁生效？"));
                b.setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        scope[0] = keys[which];
                        refreshScope.run();
                    }
                });
                try { b.show(); } catch (Throwable t) { cb.toast(Lang.tr("打不开选择器")); }
            }
        });
        wordRow.addView(scopeTagView);
        card.addView(wordRow);

        // ── ③ 三个按钮 + 次数（次数缩成行尾小字）──
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(8);
        btns.setLayoutParams(blp);

        TextView okB = pill(Lang.tr("算成功"), Theme.termGreen(act), 1);
        okB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                submit(card, pt, currentWord(lazyEd[0], suggestVal), true, scope[0], ptDid, ptTarget, prefix, poolKey);
            }
        });
        btns.addView(okB, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView failB = pill(Lang.tr("算失败"), Theme.termPink(act), 1);
        failB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                submit(card, pt, currentWord(lazyEd[0], suggestVal), false, scope[0], ptDid, ptTarget, prefix, poolKey);
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, -2, 1f);
        flp.leftMargin = dp(6);
        btns.addView(failB, flp);

        TextView skipB = pill(Lang.tr("先不管"), Theme.termMuted(act), -1);
        skipB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    cb.addIgnoredPattern(pt.norm);
                    cb.toast(Lang.tr("已跳过"));
                    removeCard(card, null);
                } catch (Throwable t) { cb.toast(Lang.tf("操作失败：{0}", String.valueOf(t))); }
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(6);
        btns.addView(skipB, slp);
        card.addView(btns);

        // 次数：行尾小字，不再是单独一行
        if (pt.count > 1) {
            TextView cnt = new TextView(act);
            cnt.setTextSize(Theme.TS_CAPTION);
            cnt.setTextColor(Theme.termFaint(act));
            cnt.setTypeface(Theme.text());
            cnt.setPadding(dp(2), dp(4), 0, 0);
            cnt.setText(Lang.tf("这条回复出现过 {0} 次", pt.count));
            card.addView(cnt);
        }

        return card;
    }

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

    /**
     * 取当前要提交的词（2026-10-19，配合懒创建 EditText）。
     *
     * 输入框还没被点开时，用户看到的是**推荐词占位文本**，
     * 直接点「算成功 / 其实是失败」就该用这个推荐词 —— 而不是提示"请填词"。
     * 点开过就用框里的实际内容（用户可能改过）。
     */
    private String currentWord(EditText ed, String fallback) {
        try {
            if (ed != null) {
                String t = ed.getText().toString().trim();
                if (t.length() > 0) return t;
            }
        } catch (Throwable ignored) {}
        return fallback == null ? "" : fallback;
    }

    private void submit(final View card, ReplyNormalizer.Pattern pt, String rawWord, boolean isOk,
                        String scope, long did, String targetId,
                        String prefix, String poolKey) {
        try {
            String word = rawWord == null ? "" : rawWord.trim();
            if (word.length() == 0) {
                // ── 2026-10-09：空词提示分情况 + 自动聚焦 ──
                // 用户反馈「点『其实是失败』也弹提示要填文字」，一脸懵：
                // 我在否定它，为什么反而要我填词？—— 因为那个词是**以后自动
                // 判断用的特征**，不是给这条回复的批注，两种路径都需要。
                // 原来的提示没把这层说清，也没给任何落点，用户在原地打转。
                if (inputRef != null) {
                    try {
                        inputRef.requestFocus();
                        inputRef.setSelection(0, inputRef.getText().length());
                    } catch (Throwable ignored) {}
                }
                String hint = isOk
                        ? Lang.tr("在上面填一段「这句话里固定会出现」的文字，以后看到它就算成功")
                        : Lang.tr("在上面填一段「这句话里固定会出现」的文字，以后看到它就算失败");
                cb.toast(hint + Lang.tr("（实在找不到就用「先不管」）"));
                return;
            }
            cb.addJudgeWordScoped(word, isOk, scope, did, targetId);
            UnkPool.remove(cb.prefs(), poolKey, pt.norm);
            // ── 2026-10-18：提交后必须失效词表缓存 ──
            // 否则「我已经学会的词（成功 X · 失败 Y）」计数与列表都不会变，
            // 用户看到的现象是「加了但列表里没有」，以为没保存（实测反馈）。
            // 这里不清 suggestCache / hitCache —— 那两个与新增无关，清了反而更慢。
            wordsCacheOk = null;
            wordsCacheFail = null;
            try { cb.notifyWordsChanged(); } catch (Throwable ignored) {}
            String scopeLabel = "global".equals(scope) ? Lang.tr("所有目标")
                              : "target".equals(scope) ? Lang.tr("这一条目标") : Lang.tr("这只机器人");
            // 反馈里带上「学会了几条」，用户能立刻确认落库成功
            String totalHint = "";
            try {
                java.util.List<String[]> now = wordsFor(isOk);
                if (now != null && now.size() > 0) {
                    totalHint = Lang.tf(" · 现已学会 {0} 条", now.size());
                }
            } catch (Throwable ignored) {}
            cb.toast((isOk ? Lang.tr("记住了「") : Lang.tr("记下了「")) + word
                     + Lang.tr("」（") + scopeLabel + Lang.tr("）") + totalHint);
            // 关键：只摘这一张卡，不重建整页（上一版卡顿的主因）
            removeCard(card, null);
        } catch (Throwable t) {
            cb.toast(Lang.tf("保存失败：{0}", String.valueOf(t)));
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
        toggle.setText(Lang.tf("我已经学会的词（成功 {0} · 失败 {1}）  ", okN, failN)
                     + (expandKnown ? "▴" : "▾"));
        outer.addView(toggle);

        final LinearLayout inner = new LinearLayout(act);
        inner.setOrientation(LinearLayout.VERTICAL);
        // 2026-10-19：记住展开状态 —— 加/删一个词不该把这块折回去
        inner.setVisibility(expandKnown ? View.VISIBLE : View.GONE);
        inner.addView(wordBlock(true));
        inner.addView(wordBlock(false));
        outer.addView(inner);

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean open = inner.getVisibility() == View.VISIBLE;
                inner.setVisibility(open ? View.GONE : View.VISIBLE);
                expandKnown = !open;   // 2026-10-19 记住状态
                toggle.setText(toggle.getText().toString().replace(open ? "▴" : "▾", open ? "▾" : "▴"));
            }
        });
        return outer;
    }

    /** 一行「词 + 作用域 + 删除」。抽出来供分批展开复用（2026-10-18）。 */
    private View wordRow(final boolean isOk, final String[] kv) {
        final String w = kv[0];
        final String scopeTag = kv.length > 1 ? kv[1] : "";
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(4);
        row.setLayoutParams(rlp);

        // ── 2026-10-19 去臃肿：去掉每行的「删除」按钮 ──
        // 原来几十个词就是几十个红按钮，视觉噪音大。
        // 现在整行可点，弹确认后删除 —— 行更干净，也少建一个控件。
        TextView tv = new TextView(act);
        tv.setTextSize(Theme.TS_SECOND);
        tv.setTextColor(Theme.termTxt(act));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding(dp(8), dp(7), dp(8), dp(7));
        tv.setText(w + (scopeTag.length() > 0 ? "  · " + scopeTag : ""));
        try { tv.setBackground(controlBg(Theme.surface(act, 2))); } catch (Throwable ignored) {}
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
                    b.setTitle(Lang.tf("删除「{0}」？", w));
                    b.setMessage(Lang.tr("删掉后，以后同类回复就认不出来了。"));
                    b.setPositiveButton(Lang.tr("删除"), new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            removeWordScoped(isOk, w, scopeTag);
                        }
                    });
                    b.setNegativeButton(Lang.tr("取消"), null);
                    b.show();
                } catch (Throwable t) { removeWordScoped(isOk, w, scopeTag); }
            }
        });
        row.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private View wordBlock(final boolean isOk) {
        LinearLayout card = block();

        TextView title = new TextView(act);
        title.setTextSize(Theme.TS_SECOND);
        title.setTextColor(isOk ? Theme.termGreen(act) : Theme.termPink(act));
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setText(isOk ? Lang.tr("遇到就说「成功了」的词") : Lang.tr("遇到就说「没成功」的词"));
        card.addView(title);

        java.util.List<String[]> words = wordsFor(isOk);
        if (words == null) words = new java.util.ArrayList<String[]>();
        if (words.isEmpty()) {
            TextView none = new TextView(act);
            none.setTextSize(Theme.TS_CAPTION);
            none.setTextColor(Theme.termFaint(act));
            none.setTypeface(Theme.text());
            none.setPadding(0, dp(4), 0, 0);
            none.setText(Lang.tr("（还没有）"));
            card.addView(none);
        } else {
            // 2026-10-18：分批。改前一次全建 —— 全局+bot+目标三级词叠起来
            // 轻易上百条，每条 3 个控件，展开时整页卡顿。
            final java.util.List<String[]> wl = words;
            final int wBatch = Math.min(BATCH, wl.size());
            for (int wi = 0; wi < wBatch; wi++) {
                card.addView(wordRow(isOk, wl.get(wi)));
            }
            if (wl.size() > wBatch) {
                final TextView wMore = new TextView(act);
                wMore.setTextSize(Theme.TS_CAPTION);
                wMore.setTextColor(Theme.termCyan(act));
                wMore.setTypeface(Theme.text());
                wMore.setGravity(Gravity.CENTER);
                wMore.setPadding(dp(12), dp(10), dp(12), dp(10));
                wMore.setText(Lang.tf("还有 {0} 条 · 点此展开", wl.size() - wBatch));
                wMore.setOnClickListener(new View.OnClickListener() {
                    private int fromW = wBatch;
                    @Override public void onClick(View v) {
                        int toW = Math.min(wl.size(), fromW + BATCH);
                        int insertAt = card.indexOfChild(wMore);
                        for (int wi = fromW; wi < toW; wi++) {
                            card.addView(wordRow(isOk, wl.get(wi)), insertAt++);
                        }
                        fromW = toW;
                        int leftW = wl.size() - toW;
                        if (leftW > 0) {
                            wMore.setText(Lang.tf("还有 {0} 条 · 点此展开", leftW));
                        } else {
                            card.removeView(wMore);
                        }
                    }
                });
                card.addView(wMore);
            }
        }

        LinearLayout addRow = new LinearLayout(act);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(8);
        addRow.setLayoutParams(alp);

        // 2026-10-19：懒创建（理由同问题卡 —— EditText 是打开页面最贵的开销之一）
        final android.widget.FrameLayout addHost = new android.widget.FrameLayout(act);
        addHost.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView addPh = new TextView(act);
        addPh.setTextSize(Theme.TS_SECOND);
        addPh.setTextColor(Theme.termFaint(act));
        addPh.setSingleLine(true);
        addPh.setPadding(dp(10), dp(9), dp(10), dp(9));
        addPh.setText(isOk ? Lang.tr("如：签到成功") : Lang.tr("如：次数已用完"));
        try { addPh.setBackground(controlBg(Theme.surface(act, 3))); } catch (Throwable ignored) {}
        addHost.addView(addPh, new android.widget.FrameLayout.LayoutParams(-1, -2));
        addRow.addView(addHost);
        addRow.addView(gap(8));

        final EditText[] addEd = new EditText[1];
        final Runnable ensureAddEd = new Runnable() {
            @Override public void run() {
                if (addEd[0] != null) { addEd[0].requestFocus(); return; }
                EditText e;
                try { e = new EditText(act); } catch (Throwable t) { return; }
                e.setHint(isOk ? Lang.tr("如：签到成功") : Lang.tr("如：次数已用完"));
                e.setHintTextColor(Theme.termFaint(act));
                e.setTextSize(Theme.TS_SECOND);
                e.setTextColor(Theme.termTxt(act));
                e.setInputType(InputType.TYPE_CLASS_TEXT);
                e.setSingleLine(true);
                e.setPadding(dp(10), dp(9), dp(10), dp(9));
                try { e.setBackground(controlBg(Theme.surface(act, 3))); } catch (Throwable ignored) {}
                addHost.removeAllViews();
                addHost.addView(e, new android.widget.FrameLayout.LayoutParams(-1, -2));
                addEd[0] = e;
                e.requestFocus();
            }
        };
        addPh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ensureAddEd.run(); }
        });

        TextView add = pill(Lang.tr("加一个"), Theme.termTxt(act), -1);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 没点过输入框 = 还没输入，先把输入框建出来并聚焦
                if (addEd[0] == null) { ensureAddEd.run(); cb.toast(Lang.tr("请输入词")); return; }
                EditText ed = addEd[0];
                String t = ed.getText().toString().trim();
                if (t.length() == 0) { cb.toast(Lang.tr("请输入词")); return; }
                if (isOk) cb.addJudgeWords(new String[]{t}, null);
                else cb.addJudgeWords(null, new String[]{t});
                cb.toast(Lang.tf("已添加「{0}」", t));
                ed.setText("");
                // 2026-10-19：原地刷新，不整页重建（重建会把折叠区全折回去 + 闪一下）
                try {
                    if (canRefreshInPlace()) { refreshInPlace(); return; }
                } catch (Throwable ignored) {}
                cb.dismissAndRefresh();
            }
        });
        addRow.addView(add, new LinearLayout.LayoutParams(-2, -2));
        card.addView(addRow);

        return card;
    }

    private void removeWordScoped(boolean isOk, String word, String scopeTag) {
        // 2026-10-18：删除同样要失效缓存 —— 否则删掉的词还留在「已学会」列表里。
        wordsCacheOk = null;
        wordsCacheFail = null;
        try { cb.notifyWordsChanged(); } catch (Throwable ignored) {}
        try {
            cb.removeJudgeWord(word + "\u0001" + (scopeTag == null ? "" : scopeTag)
                               + "\u0001" + (isOk ? "ok" : "fail"), isOk);
            cb.toast(Lang.tf("已删除「{0}」", word));
            // 2026-10-19：原地刷新，不整页重建
            try {
                if (canRefreshInPlace()) { refreshInPlace(); return; }
            } catch (Throwable ignored) {}
            cb.dismissAndRefresh();
        } catch (Throwable t) {
            cb.toast(Lang.tf("删除失败：{0}", String.valueOf(t)));
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
        main.setText(preview(squeezeDeco(pt.norm)));
        card.addView(main);

        TextView meta = new TextView(act);
        meta.setTextSize(Theme.TS_CAPTION);
        meta.setTextColor(Theme.termFaint(act));
        meta.setTypeface(Theme.text());
        meta.setPadding(0, dp(3), 0, dp(6));
        meta.setText(Lang.tf("出现过 {0} 次 · 已跳过", pt.count));
        card.addView(meta);

        TextView undo = pill(Lang.tr("恢复，让我重新判断"), Theme.termCyan(act), -1);
        undo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.removeIgnoredPattern(pt.norm);
                cb.toast(Lang.tr("已恢复"));
                // 2026-10-19：原地刷新，不整页重建
                try {
                    if (canRefreshInPlace()) { refreshInPlace(); return; }
                } catch (Throwable ignored) {}
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
        t.setText(Lang.tr("暂时没有要我认的"));
        card.addView(t);

        TextView s = new TextView(act);
        s.setTextSize(Theme.TS_SECOND);
        s.setTextColor(Theme.termMuted(act));
        s.setTypeface(Theme.text());
        s.setLineSpacing(dp(3), 1f);
        s.setPadding(0, dp(6), 0, 0);
        s.setText(Lang.tr("签到时如果机器人回了句我看不懂的话，会自动攒到这里，"
                        + "到时候你只要点一下「算成功」就行。\n\n"
                        + "识别得越准，你越不用管它。"));
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
