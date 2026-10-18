#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描「绕过封装函数直接裸读带 _day 语义的键」。

本项目最高频 bug 模式：
  写了一个带跨天校验的读取函数（silentCount / panelStaleCount / failStreak），
  但在某个分支里图省事直接 prefs.getInt(同名键) —— 校验被绕过。

本次已在 silent_ 上抓到一例。这里把同类全找出来。
"""
import io, re, os

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "app/src/main/java/io/github/wlmosv_png/tgautosign")

# 1) 找出所有「带 _day 校验的读取函数」及其保护的键前缀
guard_fns = {}
for root, _, files in os.walk(BASE):
    for f in files:
        if not f.endswith(".java"): continue
        p = os.path.join(root, f)
        s = io.open(p, encoding="utf-8").read()
        s = re.sub(r'//[^\n]*', '', s)
        s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
        # private int XXX(String prefix, String id) { ... todayStr().equals(prefs.getString(prefix + "KEY_day_" ... )
        for m in re.finditer(
            r'private\s+(?:int|long|boolean)\s+(\w+)\s*\(\s*String\s+prefix\s*,\s*String\s+id\s*\)\s*\{'
            r'(?:(?!\n    \}).)*?todayStr\(\)\.equals\(prefs\.getString\(prefix\s*\+\s*"(\w+)"',
            s, re.S):
            guard_fns[m.group(1)] = (f, m.group(2))

print("=" * 72)
print("绕过跨天校验的裸读扫描")
print("=" * 72)
print("\n── 已识别的「带跨天校验」读取函数 ──")
for fn, (f, key) in guard_fns.items():
    print("  ✓ %-22s 保护键前缀 %-16s [%s]" % (fn, key, f))

# 2) 找出所有裸读 prefs.getInt/ getString 用到「被保护键」的地方
print("\n── 裸读这些键的地方（应改为走上面的函数）──")
guarded_keys = set(k for _, k in guard_fns.values())
found = 0
for root, _, files in os.walk(BASE):
    for f in files:
        if not f.endswith(".java"): continue
        p = os.path.join(root, f)
        lines = io.open(p, encoding="utf-8").read().split("\n")
        for i, ln in enumerate(lines, 1):
            if ln.strip().startswith("//") or ln.strip().startswith("*"): continue
            if "todayStr().equals" in ln: continue          # 这就是校验本身
            if "prefs.edit()" in ln: continue               # 写入侧正常
            for k in guarded_keys:
                if re.search(r'prefs\.(getInt|getLong|getString)\(\s*\w+\s*\+\s*"' + re.escape(k), ln) \
                   or re.search(r'prefs\.(getInt|getLong|getString)\(\s*"' + re.escape(k), ln):
                    print("  ⚠ %s:%d  %s" % (f, i, ln.strip()[:100]))
                    found += 1
if not found:
    print("  无（全部走封装函数）")
