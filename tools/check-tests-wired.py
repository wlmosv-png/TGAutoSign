#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""测试接线门禁（2026-11-12，事故驱动）。

来历（两条真实事故）：
  ① tools/tests/ 里写了一个测试，函数定义却在 `if __name__=="__main__"` 之后
     → 用例静默不跑，还以为有覆盖；
  ② SignLogicTest 引用了编译集里没有的类 → 门禁链**编译失败**，
     但没人发现（CI 只跑 gradle assembleDebug），"测试通过"变成假象。

规则：
  R1  tools/tests/ 下每个 *Test.java 必须被 build.sh 引用（编译 + 运行），
      或在下面的 EXEMPT 里显式列出并写明理由。
  R2  build.sh 里不许出现「编译了某测试但从不 java 运行它」—— 只编不跑等于没测。

退出码 0 = 通过；1 = FATAL。
"""
import io
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS = os.path.join(ROOT, "tools", "tests")
BUILD = os.path.join(ROOT, "build.sh")

# 显式豁免：写了但故意不挂进门禁链（必须写清理由）
EXEMPT = {
    # 2026-11-12：**已无欠账**。15 个测试文件全部由 build.sh 编译并运行：
    #   SignLogicTest / StateLeakTest / StageTableTest / TriggerPolicyTest /
    #   VerdictEvidenceTest / MatchPositionTest / ReplyMatchTest / AnnouncementLengthTest /
    #   DualChainDiffTest / SuggestWordTest / JudgeTest / GroupScopeTest /
    #   UnkPoolSourcesTest / LearnFlowTest / SavedMsgPolicyTest / ScopedWireTest / JudgeOnGateTest
    #   （最后 5 个由「判定接线测试组」以 stubs+android.jar 两段式 classpath 接入）
    # 备份文件不参与：
    "SignLogicTest.java.bak": "备份文件，非测试",
}

fatal = []

if not os.path.isfile(BUILD):
    print("FATAL: 找不到 build.sh: %s" % BUILD)
    sys.exit(1)

build = io.open(BUILD, encoding="utf-8").read()

files = sorted(f for f in os.listdir(TESTS)
               if f.endswith("Test.java") or f.endswith("Test.java.bak"))
if not files:
    print("FATAL: tools/tests 下没有找到任何 *Test.java")
    sys.exit(1)

for f in files:
    if f in EXEMPT:
        continue
    # 允许两种接线写法：显式文件名（tools/tests/XTest.java）或
    # 循环式清单（_gtests="XTest YTest" 然后统一 javac/java）——类名出现即算已接线。
    bare = f[:-5]
    if f not in build and bare not in build:
        fatal.append("R1 %s 没有被 build.sh 引用（写了不跑 = 没有覆盖）" % f)

# R2：每个 -cp 运行点都要有对应的 javac 源
compiled = set(re.findall(r"tools/tests/([A-Za-z0-9_]+\.java)", build))
run = set(re.findall(r"java -cp [^\n]*io\.github\.wlmosv_png\.tgautosign\.([A-Za-z0-9_]+)", build))
# 循环式运行点：`for _t in ATest BTest; do ... java -cp ... io...$_t ... done`
# 2026-11-12：门禁要认这种写法，否则"批量跑一组测试"会被误判成"只编不跑"。
for grp in re.findall(r"for\s+\w+\s+in\s+([A-Za-z0-9_ ]+);\s*do", build):
    for name in grp.split():
        if name.endswith("Test"):
            run.add(name)
for cls in sorted(run):
    if (cls + ".java") not in compiled:
        fatal.append("R2 %s 被 java 运行，但在 build.sh 的编译集里找不到源码" % cls)
for src in sorted(compiled):
    cls = src[:-5]
    if cls not in run:
        fatal.append("R2 %s 被编译但从不运行（只编不跑等于没测）" % src)

if fatal:
    print("❌ 测试接线门禁未通过：")
    for m in fatal:
        print("   " + m)
    print("")
    print("修复：把该测试挂进 build.sh（javac 编译集 + java 运行点），")
    print("      或加入 check-tests-wired.py 的 EXEMPT 并写明理由。")
    sys.exit(1)

print("✓ 测试接线门禁通过（%d 个测试文件全部被编译并运行）" % (len(files) - len(EXEMPT)))
sys.exit(0)
