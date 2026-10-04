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
        void addIgnoredPattern(String pattern);
        java.util.Set<String> ignoredPatterns();
        void toast(String msg);
        void dismissAndRefresh();
    }

    private final Activity act;
    private final Callbacks cb;

    public LearnPage(Activity act, Callbacks cb) {
        this.act = act;
        this.cb = cb;
    }

    private String okWordsText() { return cb.prefs().getString("jmb_ok_words", ""); }
    private String failWordsText() { return cb.prefs().getString("jmb_fail_words", ""); }

    public View build() {
        final String prefix = cb.prefsPrefix();
        final String poolKey = prefix + "unk_pool";
        final SharedPreferences p = cb.prefs();

        ScrollView sv = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(2), dp(10), dp(10));

        final List<UnkPool.Item> rawItems = UnkPool.list(p, poolKey);
        List<String> norms = new ArrayList<String>();
        for (UnkPool.Item it : rawItems) norms.add(it.r);
        final List<ReplyNormalizer.Pattern> pats = ReplyNormalizer.cluster(norms);

        java.util.Set<String> ignored = cb.ignoredPatterns();
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
        intro.setText("机器人回复了但认不出结果的消息会攒在这里。判定一次即学会一条词，"
                    + "以后同类回复自动生效。");
        box.addView(intro);

        sectionHeader(box, "待确认（" + shown.size() + "）");
        if (shown.isEmpty()) {
            box.addView(emptyBlock(rawItems.size(), pats.size()));
        } else {
            for (final ReplyNormalizer.Pattern pt : shown) {
                box.addView(patternBlock(pt, prefix, poolKey));
            }
        }

        sectionHeader(box, "判定词");
        box.addView(wordBlock(true));
        box.addView(wordBlock(false));

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

        sv.addView(box, new ScrollView.LayoutParams(-1, -2));
        return sv;
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
        LinearLayout card = block();

        TextView main = new TextView(act);
        main.setTextSize(Theme.TS_BODY);
        main.setTextColor(Theme.termTxt(act));
        main.setTypeface(Typeface.MONOSPACE);
        main.setText(pt.norm);
        card.addView(main);

        TextView meta = new TextView(act);
        meta.setTextSize(Theme.TS_CAPTION);
        meta.setTextColor(Theme.termFaint(act));
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setPadding(0, dp(3), 0, dp(5));
        meta.setText("出现 " + pt.count + " 次");
        card.addView(meta);

        for (String s : pt.samples) {
            TextView sm = new TextView(act);
            sm.setTextSize(Theme.TS_CAPTION);
            sm.setTextColor(Theme.termFaint(act));
            sm.setTypeface(Typeface.MONOSPACE);
            sm.setSingleLine(true);
            sm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sm.setText("· " + s);
            card.addView(sm);
        }

        final String word = extractWord(pt.norm);
        TextView pv = new TextView(act);
        pv.setTextSize(Theme.TS_CAPTION);
        pv.setTextColor(word == null ? Theme.termAmber(act) : Theme.termGreen(act));
        pv.setTypeface(Typeface.MONOSPACE);
        pv.setPadding(0, dp(6), 0, 0);
        pv.setText(word == null ? "提不出有判别力的词，建议「忽略」" : "将学会：「" + word + "」");
        card.addView(pv);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        TextView okB = pill("算成功", Theme.primaryFill(act), 1);   // 主操作：实心
        okB.setTextColor(Theme.onPrimary(act));
        okB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { actAs(pt, true, prefix, poolKey); }
        });
        row.addView(okB);
        row.addView(gap(6));

        TextView failB = pill("算失败", Theme.termPink(act), 1);
        failB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { actAs(pt, false, prefix, poolKey); }
        });
        row.addView(failB);
        row.addView(gap(6));

        TextView igB = pill("忽略", Theme.termTxt(act), 1);
        igB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.addIgnoredPattern(pt.norm);
                UnkPool.remove(cb.prefs(), poolKey, pt.norm);
                cb.toast("已忽略");
                cb.dismissAndRefresh();
            }
        });
        row.addView(igB);
        card.addView(row);
        return card;
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

        final String[] words = splitWords(isOk ? okWordsText() : failWordsText());
        if (words.length == 0) {
            TextView none = new TextView(act);
            none.setTextSize(Theme.TS_CAPTION);
            none.setTextColor(Theme.termFaint(act));
            none.setTypeface(Theme.text());
            none.setText("（暂无）");
            card.addView(none);
        } else {
            for (final String w : words) {
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
                tv.setText(w);
                row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView del = pill("删除", Theme.termPink(act), -1);   // wrap_content
                del.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { removeWord(isOk, w); }
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
    private TextView pill(String label, int accent, int weight) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(Theme.TS_SECOND);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextColor(accent);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(controlBg(accentFill(accent)));
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
