#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：谁有权把目标标成「今日已签」（2026-10-23 新增，事故驱动）。

事故
----
`TL_messages_sendMessage` 的 hook 里调了 markSignedFromRequest。
hook 打在**请求对象**上 —— 早于任何网络往返，更早于 bot 回复。
于是「命令一发出」就写 last_（界面上就是绿色已签）。

实测 2026-10-17 ~ 10-22 逐日统计（无 bot 回复依据却被标已签 / 当天目标数）：
    14/24、17/24、11/22、13/22、14/22、10/22
即每天 1/3~1/2 的目标是"假绿"。这个缺陷存在了一个多月，六道门禁
（版本/双语/界面文案/接线/单测/作用域）一条都没拦住 ——
因为没人管「写 last_ 的依据」这件事。

规则
----
R1 禁止 markSignedFrom* 这类「从动作反推成功」的方法定义。
R2 markSigned( 的调用点不得出现在 hook / 请求分发方法内。
R3 markSigned( 调用点向上 EVID_WINDOW 行内必须出现「bot 有输出」的证据词。

退出码 1 = 有违规。跳过：TGAS_SKIP_LASTWRITE=1
"""
import io
import os
import re
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
JAVA_DIR = os.path.join(BASE, 'app', 'src', 'main', 'java')

CALL = re.compile(r'markSigned\s*\(')
DEF = re.compile(r'\b(?:void|boolean)\s+markSigned\s*\(')
BAD_NAME = re.compile(r'\b\w*\s+markSignedFrom\w*\s*\(')
SIG = re.compile(
    r'^    (?:@\w+\s*)?(?:public|private|protected|static|final|synchronized|abstract|native'
    r'|void|boolean|int|long|double|float|String|List|Object|Map|Class)'
    r'[\w<>\[\],\.\s]*\s+(\w+)\s*\([^;]*\)\s*\{?\s*$')

HOOK_METHOD = re.compile(r'(?i)^(on.*request|.*hook.*|dispatch.*|.*intercept.*|.*sendrequest.*)$')

# 「bot 有输出」的证据词：出现在调用点上方即认为这条 last_ 是被回复驱动的
EVID = (
    "回复判定", "命中", "确认已签", "兜底", "就地", "judgeWithScopes",
    "answer", "reply", "Reply", "宽松", "learnLastUnknown", "onSigned",
    "looksLikeSignResult", "callbackAnswer",
)
EVID_WINDOW = 60


def owner_method(lines, i):
    """向上找最近的方法签名，返回 (方法名, 行号)"""
    for j in range(i, -1, -1):
        m = SIG.match(lines[j])
        if m:
            return m.group(1), j + 1
    return "<unknown>", 0


def main():
    if not os.path.isdir(JAVA_DIR):
        print("  FATAL: 找不到源码目录 %s" % JAVA_DIR)
        return 1
    bad = []
    calls = []
    files = []
    for root, _, fs in os.walk(JAVA_DIR):
        for f in fs:
            if f.endswith('.java'):
                files.append(os.path.join(root, f))
    for path in sorted(files):
        rel = os.path.relpath(path, BASE)
        lines = io.open(path, encoding='utf-8').read().split('\n')
        for i, l in enumerate(lines):
            code = l.split('//')[0]
            if BAD_NAME.search(code) and not DEF.search(code):
                bad.append((rel, i + 1, "R1", "禁止 markSignedFrom* —— 「发出/动作」不等于「签上」", l.strip()))
            if not CALL.search(code) or DEF.search(code):
                continue
            name, mline = owner_method(lines, i)
            win = '\n'.join(lines[max(0, i - EVID_WINDOW):i + 1])
            hits = [e for e in EVID if e in win]
            calls.append((rel, i + 1, name, bool(hits) or name == "markSigned"))
            if name == "markSigned":
                # 转发层自身（Core.markSigned -> stateStore.markSigned），
                # 它的语义由 stateStore 决定，这里只看调用方。
                continue
            if HOOK_METHOD.match(name):
                bad.append((rel, i + 1, "R2",
                            "markSigned( 不得出现在 hook/请求分发方法 %s() 内 —— "
                            "hook 早于 bot 回复，这里写 last_ 就是「发出即已签」" % name,
                            l.strip()))
            elif not hits:
                bad.append((rel, i + 1, "R3",
                            "markSigned( 向上 %d 行无任何「bot 有输出」的证据词（%s() 内）—— "
                            "写 last_ 必须有回复判定依据" % (EVID_WINDOW, name),
                            l.strip()))

    print("  写 last_ 的依据检查：")
    for rel, ln, name, ok in calls:
        print("    %s:%d  %s()  %s" % (rel, ln, name, "证据✓" if ok else "证据✗"))
    if bad:
        for rel, ln, rule, why, src in bad:
            print("    ❌ [%s] %s:%d %s" % (rule, rel, ln, why))
            print("         %s" % src[:100])
        print("  FATAL: 有 %d 处写 last_ 缺依据（临时跳过：TGAS_SKIP_LASTWRITE=1）" % len(bad))
        return 1
    print("    ✅ %d 处调用点：位置合法，且都有 bot 输出的依据" % len(calls))
    return 0


if __name__ == '__main__':
    sys.exit(main())
