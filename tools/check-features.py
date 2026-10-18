#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""功能可用性核查（2026-10-18）—— 逐项对照「界面上有的」vs「代码里真做的」。

只做静态核查：每个用户可见入口，追到它的实现，确认不是空壳。
输出成表格，人读。
"""
import io, os, re, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "app/src/main/java/io/github/wlmosv_png/tgautosign")
def read(n):
    return io.open(os.path.join(BASE, n), encoding="utf-8").read()

core = read("TGAutoSignCore.java")
learn = read("LearnPage.java")
sig = read("SignLogic.java")
lang = read("Lang.java")

rows = []
def chk(name, ok, detail=""):
    rows.append((name, ok, detail))

# ── 1. 未识别回复：三个按钮 ──
chk("未识别回复 · 算成功/其实是失败",
    'cb.addJudgeWordScoped(word, isOk, scope' in learn)
chk("未识别回复 · 先不管",
    'addIgnoredPattern' in learn or 'ignorePattern' in learn)
chk("未识别回复 · 已忽略可恢复",
    'removeIgnoredPattern' in learn and 'removeIgnoredPattern' in core)
chk("未识别回复 · 全部收回",
    'promoteSkippedUnknown' in core)
chk("未识别回复 · 收回后不整页重建",
    'if (canRefreshInPlace()) { refreshInPlace(); return; }' in learn)
chk("未识别回复 · 显示原话（非聚类键）",
    'raw0' in learn and 'pt.norm' in learn)

# ── 2. 判定词读写一致性 ──
chk("设置页写入全局键",
    'ed.putString(Keys.okWordsGlobal()' in core)
chk("设置页回显读全局键",
    'strOf(Keys.okWordsGlobal())' in core)
chk("宽松模式读全局键",
    'parseExtraWords(prefs.getString(Keys.failWordsGlobal()' in core)
chk("规则测试器读全局键",
    'parseExtraWords(prefs.getString(Keys.okWordsGlobal()' in core)
chk("学词写全局键（addJudgeWordsInternal）",
    'prefs.edit().putString(Keys.okWordsGlobal(), merged).commit();' in core)
chk("确认已签提词写全局键",
    'String cur = prefs.getString(Keys.okWordsGlobal(), "");' in core)
chk("无残留 cfg 词表读取",
    'cfgStr("ok_words"' not in core and 'cfgStr("fail_words"' not in core)

# ── 3. 宽松模式 ──
i_prog = core.find('进度提示必须排在宽松模式')
i_loose = core.find('if (LOOSE_MODE) {', i_prog) if i_prog > 0 else -1
chk("宽松模式：进度检查在其之前", 0 < i_prog < i_loose if i_prog > 0 else False,
    "prog@%d loose@%d" % (i_prog, i_loose))
chk("宽松模式：不绕过用户排除规则",
    'String hitL = excludeHit(hayL);' in core)

# ── 4. 判不出三档兜底 ──
chk("兜底放行：要求像签到结果",
    'boolean looksRes = SignLogic.looksLikeSignResult(replyText);' in core)
chk("兜底成功：检查今天在途",
    'long okSent = prefs.getLong(prefix + "sent_at_" + id, 0L);' in core)

# ── 5. 垃圾过滤 ──
chk("公告过滤函数存在", 'public static boolean isLikelyAnnouncement' in sig)
chk("池子入口调用公告过滤", 'if (SignLogic.isLikelyAnnouncement(text))' in core)
chk("worthLearning 调用公告过滤", 'if (isLikelyAnnouncement(r)) return false;' in sig)
chk("收回时过滤公告", 'if (SignLogic.isLikelyAnnouncement(text)) continue;' in core)
chk("收回有上限", 'MAX_PROMOTE' in core)

# ── 6. 性能 ──
chk("CapBox 不再 UNSPECIFIED 双重测量",
    'MeasureSpec.UNSPECIFIED));\n            if (getMeasuredHeight() > maxH)' not in core)
chk("CapBox 用 AT_MOST", 'MeasureSpec.AT_MOST' in core)
chk("已忽略区已分批", 'ignBatch' in learn)
chk("学会词区已分批", 'wBatch' in learn)
chk("展开计数已修正", 'refreshPendingCount(shown.size() - to)' in learn)

# ── 7. i18n ──
missing = 0
for m in re.finditer(r'Lang\.tr\("([^"]*[\u4e00-\u9fff][^"]*)"\)', core + learn):
    s = m.group(1)
    if ('EN.put("%s"' % s) not in lang:
        missing += 1
chk("界面新增文案有英文映射", missing == 0, "缺 %d" % missing)

print("=" * 62)
print("功能可用性核查")
print("=" * 62)
bad = 0
for name, ok, detail in rows:
    print(("  ✅ " if ok else "  ❌ ") + name + (("   [" + detail + "]") if detail else ""))
    if not ok:
        bad += 1
print("-" * 62)
print("通过 %d / 失败 %d" % (len(rows) - bad, bad))
sys.exit(0 if bad == 0 else 1)
