#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""宿主作用域一致性门禁（2026-10-06 新增，P1 交接单要求）。

校验三处集合**完全相同**：
  ① Hosts.KNOWN            （代码里认哪些包名）
  ② scope.list             （Xposed 声明的作用域，每行一个包名）
  ③ module.prop 的 scope=   （同上，逗号分隔）

为什么需要：Turrit（org.telegram.group）曾在 ① ② 里都有、③ 里缺失，
  而当时没有任何门禁能发现 —— 用户装完发现 Turrit 不在作用域里。
  三处任一不同步都会造成「声明支持但实际不注入」或反之。

用法：
  python3 tools/check-scope-sync.py [源码目录]     默认 .
退出码 1 = 不一致（并指出差异）。
"""
import os
import re
import sys


def read_hosts(src):
    p = os.path.join(src, "app/src/main/java/io/github/wlmosv_png/tgautosign/Hosts.java")
    if not os.path.isfile(p):
        sys.exit("FATAL: 找不到 " + p)
    s = open(p, encoding="utf-8").read()

    # 抓 KNOWN 集合块：从 "private static final Set<String> KNOWN" 到该语句结束
    m = re.search(r"KNOWN\s*=\s*Collections\.unmodifiableSet\(new HashSet<>\(Arrays\.asList\((.*?)\)\)\)\)",
                  s, re.S)
    if not m:
        # 退回：抓常量定义 + 字面量
        m = re.search(r"KNOWN\s*=\s*Collections\.unmodifiableSet\((.*?)\);\n", s, re.S)
    block = m.group(1) if m else ""

    pkgs = set()
    # 字符串字面量
    for lit in re.findall(r'"([a-zA-Z0-9_.]+)"', block):
        pkgs.add(lit)
    # 常量引用，如 PKG_OFFICIAL / PKG_OFFICIAL_WEB / PKG_NAGRAM_XF
    for const in re.findall(r'\b(PKG_[A-Z_]+)\b', block):
        cm = re.search(r'String\s+%s\s*=\s*"([^"]+)"' % const, s)
        if cm:
            pkgs.add(cm.group(1))
    return pkgs


def read_scope_list(src):
    p = os.path.join(src, "app/src/main/resources/META-INF/xposed/scope.list")
    if not os.path.isfile(p):
        sys.exit("FATAL: 找不到 " + p)
    out = set()
    for line in open(p, encoding="utf-8"):
        t = line.strip()
        if t and not t.startswith("#") and not t.startswith("$"):
            out.add(t)
    return out


def read_module_prop(src):
    p = os.path.join(src, "app/src/main/resources/META-INF/xposed/module.prop")
    if not os.path.isfile(p):
        sys.exit("FATAL: 找不到 " + p)
    for line in open(p, encoding="utf-8"):
        if line.startswith("scope="):
            val = line.split("=", 1)[1].strip()
            return set(x.strip() for x in val.split(",") if x.strip())
    sys.exit("FATAL: module.prop 里没有 scope=")


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "."
    hosts = read_hosts(src)
    slist = read_scope_list(src)
    mprop = read_module_prop(src)

    print("  宿主作用域一致性检查：")
    print("    Hosts.KNOWN      %d 个" % len(hosts))
    print("    scope.list       %d 个" % len(slist))
    print("    module.prop      %d 个" % len(mprop))

    bad = 0
    for name, a, b in (("Hosts.KNOWN ↔ scope.list", hosts, slist),
                       ("Hosts.KNOWN ↔ module.prop", hosts, mprop),
                       ("scope.list ↔ module.prop", slist, mprop)):
        only_a = sorted(a - b)
        only_b = sorted(b - a)
        if only_a or only_b:
            bad = 1
            print("    ✗ %s 不一致" % name)
            if only_a:
                print("        仅前者有: %s" % ", ".join(only_a))
            if only_b:
                print("        仅后者有: %s" % ", ".join(only_b))
        else:
            print("    ✓ %s 一致" % name)

    if bad:
        print("  ❌ 三处集合必须完全相同")
        return 1
    print("  ✅ 三处一致（共 %d 个宿主）" % len(hosts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
