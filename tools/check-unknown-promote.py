#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「判不出」升级门槛门禁（2026-11-12 P0，事故驱动）。

事故：bot 主动推一条与签到无关的广告 → 被归属到目标 → 判定链判不出 →
  **无条件** markResultCode(R_REPLIED_UNK) → 目标钉成「待确认」：
  进「需处理」列表 + 当天不再自动重签。用户看到「还没签到就判断了，一大片要处理」。

规则：源码里每一处 `markResultCode(..., R_REPLIED_UNK)` 写入点，必须满足其一：
  R1  附近（前 40 行内）出现 `mayPromoteUnknown(` 门槛；
  R2  该行所在语句前后 3 行内有显式标记 `// PROMOTE-OK:` 并写明理由。

为什么要 R2 而不是全部套门槛：有些调用点语义上**已有前提**
（如"只把已有 no_reply 修正为判不出"、"就地对答"、"已校验 sentToday"），
再套一层门槛是错的。R2 要求把这种判断**写成文字**，便于审计与复核。

退出码 0 = 通过；1 = FATAL。
"""
import io
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = os.path.join(ROOT, "app", "src", "main", "java", "io", "github", "wlmosv_png", "tgautosign", "TGAutoSignCore.java")

if not os.path.isfile(CORE):
    print("FATAL: 找不到 TGAutoSignCore.java: %s" % CORE)
    sys.exit(1)

lines = io.open(CORE, encoding="utf-8").read().split("\n")
bad = []
count = 0
for i, ln in enumerate(lines):
    if "markResultCode(" not in ln or "R_REPLIED_UNK" not in ln:
        continue
    count += 1
    window = "\n".join(lines[max(0, i - 40):i + 1])
    near = "\n".join(lines[max(0, i - 3):min(len(lines), i + 4)])
    if "mayPromoteUnknown(" in window:
        continue
    if "PROMOTE-OK:" in near:
        continue
    bad.append(i + 1)

if bad:
    print("❌ 判不出升级门槛未通过：以下写入点既无 mayPromoteUnknown 门槛，也无 // PROMOTE-OK: 理由")
    for n in bad:
        print("   TGAutoSignCore.java:%d  %s" % (n, lines[n - 1].strip()))
    print("")
    print("修复：① 加 SignLogic.mayPromoteUnknown(...) 门槛（推荐，见该函数注释）；或")
    print("      ② 若该处语义上已有前提，紧随其上加一行 // PROMOTE-OK: <理由>。")
    sys.exit(1)

print("✓ 判不出升级门槛门禁通过（%d 个写入点均有门槛或显式理由）" % count)
sys.exit(0)
