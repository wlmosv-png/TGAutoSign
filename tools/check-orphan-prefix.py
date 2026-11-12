#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""孤儿键前缀门禁（2026-11-12 P0，事故驱动）。

规则（两条，都必须通过）：

  R1  Keys.ORPHAN_HEADS 必须**按长度降序**排列。
      为什么：孤儿键清理是「首个前缀命中即 break」——
        for (String h : HEADS) if (body.startsWith(h)) { hitId = body.substring(h.length()); break; }
      只要短前缀排在长前缀之前，复合键就会被短前缀吃掉、算出假 id 后被当孤儿删除。
      真机事故：ORPHAN_HEADS 里 "pendcfm_" 排在 "pendcfm_day_" 之前，于是
      acc0_pendcfm_day_<id> 每次启动都被删除 → 跨天判据（"哪天标的"）全部失效 →
      6 个目标永久停在「判不出」且永久不再自动重试。
      同族受害者：pendcfm_note_ / pendcfm_result_ / panelstale_day_ /
                  silent_day_ / fails_day_。

  R2  清理循环必须使用 ORPHAN_HEADS_LONGEST_FIRST（长度降序副本），
      不得直接遍历 ORPHAN_HEADS —— 否则 R1 就白要求了。

退出码 0 = 通过；1 = FATAL。
"""
import io
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "app", "src", "main", "java", "io", "github", "wlmosv_png", "tgautosign")
KEYS = os.path.join(PKG, "Keys.java")
CORE = os.path.join(PKG, "TGAutoSignCore.java")

fatal = []


def read(p):
    return io.open(p, encoding="utf-8").read()


# ── 解析 ORPHAN_HEADS ──
try:
    keys_src = read(KEYS)
except Exception as e:
    print("FATAL: 读不到 Keys.java: %s" % e)
    sys.exit(1)

m = re.search(r"ORPHAN_HEADS\s*=\s*\{(.*?)\};", keys_src, re.S)
if not m:
    print("FATAL: Keys.java 里找不到 ORPHAN_HEADS 数组字面量（结构变了？门禁需要同步）")
    sys.exit(1)

# 先剥掉注释：数组体里带着大段中文说明（含引号），不剥会把说明文字当成前缀
body = re.sub(r"//[^\n]*", "", m.group(1))
heads = re.findall(r'"([^"]+)"', body)
if not heads:
    print("FATAL: ORPHAN_HEADS 解析结果为空")
    sys.exit(1)

# ── R1 长度降序 ──
prev = None
for h in heads:
    if prev is not None and len(h) > len(prev):
        fatal.append("R1 长度降序被破坏：%r(%d) 排在 %r(%d) 之后"
                     % (h, len(h), prev, len(prev)))
    prev = h

# ── R1b 前缀关系必须长在前 ──
for i, a in enumerate(heads):
    for j, b in enumerate(heads):
        if i >= j:
            continue
        # a 在前、b 在后；若 **a 是 b 的前缀** → 短前缀排在长前缀之前 → 它会把 b 吃掉
        if b.startswith(a) and len(a) < len(b):
            fatal.append("R1b 前缀序被破坏：短前缀 %r 排在 %r 之前，会吃掉后者" % (a, b))

# ── R2 清理循环用降序副本 ──
try:
    core_src = read(CORE)
except Exception as e:
    print("FATAL: 读不到 TGAutoSignCore.java: %s" % e)
    sys.exit(1)

if "ORPHAN_HEADS_LONGEST_FIRST" not in core_src:
    fatal.append("R2 找不到 ORPHAN_HEADS_LONGEST_FIRST 定义 —— 长度降序副本缺失")
if re.search(r"for\s*\(\s*String\s+\w+\s*:\s*ORPHAN_HEADS\s*\)", core_src):
    fatal.append("R2 清理循环仍在直接遍历 ORPHAN_HEADS（须改为 ORPHAN_HEADS_LONGEST_FIRST）")

# ── 输出 ──
if fatal:
    print("❌ 孤儿键前缀门禁未通过：")
    for f in fatal:
        print("   " + f)
    print("")
    print("修复：把 Keys.ORPHAN_HEADS 里更长的前缀排到更短的前面（保证长度降序），")
    print("      并确认清理循环遍历 ORPHAN_HEADS_LONGEST_FIRST。")
    sys.exit(1)

print("✓ 孤儿键前缀门禁通过（%d 个前缀，长度降序，清理走降序副本）" % len(heads))
sys.exit(0)
