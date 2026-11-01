#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：judgeOn 总闸是否覆盖全部自动判定入口（2026-11-01 M3 收尾新增，缺陷驱动）。

事故
----
用户关闭「自动判定成功/失败」总闸后，C 长消息链会短路并只记 R_JUDGE_OFF，
但 D（就地对答，sendSign 的 callback answer 判定块）与 E（锚点B，
handleReplyText 的判定块）两条生产路径**没有检查 judgeOn**，仍会调用
judgeWithScopesStage → 命中后写 markSigned / markResultCode / retry /
noteFailStreak，把「用户关掉的自动判定」又悄悄执行了一遍，目标状态被改写。

审计确认（2026-11-01）：
  · judgeOn(prefix) 全仓定义 1 处、调用仅 C 长消息链 1 处（24289）。
  · E handleReplyText 方法体第一个 try 块即 judgeWithScopesStage，无总闸。
  · D sendSign 内就地对答块同样直调 judgeWithScopesStage，无总闸。

规则
----
J7a  handleReplyText（E 锚点B）方法体内，在首次出现 judgeWithScopesStage 之前，
     必须出现 judgeOn( 的短路判断。
J7b  sendSign（D 就地对答）方法体内，在出现「就地对答」判定块
     （judgeWithScopesStage 且紧随 markSigned 的就地判定）之前，
     必须出现 judgeOn( 的短路判断。

退出码 1 = 有违规。跳过：TGAS_SKIP_JUDGEONGATE=1  指定文件：TGAS_CORE=/path
"""
import io
import os
import re
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
CORE = os.path.join(BASE, 'app', 'src', 'main', 'java', 'io', 'github',
                    'wlmosv_png', 'tgautosign', 'TGAutoSignCore.java')

FAILS = []
NOTES = []


def strip_comments(src):
    out = []
    i, n, st = 0, len(src), None
    while i < n:
        c = src[i]
        if st is None:
            if c == '"' or c == "'":
                st = c
                out.append(c)
                i += 1
                continue
            if c == '/' and i + 1 < n and src[i + 1] == '/':
                while i < n and src[i] != '\n':
                    i += 1
                continue
            if c == '/' and i + 1 < n and src[i + 1] == '*':
                i += 2
                nl = 0
                while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                    if src[i] == '\n':
                        nl += 1
                    i += 1
                i += 2
                out.append('\n' * nl)
                continue
            out.append(c)
            i += 1
        else:
            if c == '\\' and i + 1 < n:
                out.append(c)
                out.append(src[i + 1])
                i += 2
                continue
            if c == st:
                st = None
            out.append(c)
            i += 1
    return ''.join(out)


def strip_strings(src):
    out = []
    i, n, st = 0, len(src), None
    while i < n:
        c = src[i]
        if st is None:
            if c == '"' or c == "'":
                st = c
                out.append('""')
                i += 1
                continue
            out.append(c)
            i += 1
        else:
            if c == '\\':
                i += 2
                continue
            if c == st:
                st = None
            i += 1
    return ''.join(out)


def match_paren(src, o):
    d = 0
    k = o
    while k < len(src):
        if src[k] == '(':
            d += 1
        elif src[k] == ')':
            d -= 1
            if d == 0:
                return k
        k += 1
    return -1


def match_brace(src, o):
    d = 0
    for i in range(o, len(src)):
        if src[i] == '{':
            d += 1
        elif src[i] == '}':
            d -= 1
            if d == 0:
                return i
    return -1


def line_of(src, idx):
    return src.count('\n', 0, idx) + 1


def method_bodies(src, name):
    """返回 [(body, line)]，按定义顺序；处理重载（同名多方法体）。"""
    res = []
    for m in re.finditer(r'\b' + re.escape(name) + r'\s*\(', src):
        ls = src.rfind('\n', 0, m.start()) + 1
        prefix = src[ls:m.start()]
        if re.search(r'[=;{(]', prefix):
            continue
        close = match_paren(src, m.end() - 1)
        if close < 0:
            continue
        rest = re.match(r'\s*(?:throws[^{;]*)?\{', src[close + 1:])
        if not rest:
            continue
        brace = close + 1 + rest.end() - 1
        end = match_brace(src, brace)
        if end < 0:
            continue
        res.append((src[brace:end + 1], line_of(src, m.start())))
    return res


def main():
    if os.environ.get('TGAS_SKIP_JUDGEONGATE') == '1':
        print('SKIP')
        return 0
    path = os.environ.get('TGAS_CORE') or CORE
    if not os.path.isfile(path):
        print('FATAL 找不到 %s' % path)
        return 1
    code = strip_strings(strip_comments(io.open(path, encoding='utf-8').read()))

    # ── J7a：handleReplyText（E 锚点B）──
    hrt = method_bodies(code, 'handleReplyText')
    # 重载：带 boolean isMedia 的是实际判定入口，选最长的那个方法体
    if not hrt:
        print('FATAL 未找到方法 handleReplyText（识别逻辑需更新，不能静默通过）')
        return 1
    hrt_body, hrt_ln = max(hrt, key=lambda c: len(c[0]))

    # 在方法体内找 judgeWithScopesStage 首次出现的位置
    idx_stage = hrt_body.find('judgeWithScopesStage')
    if idx_stage < 0:
        NOTES.append('[J7a] handleReplyText 内未找到 judgeWithScopesStage 调用，'
                     '无法判断（可能判定入口已改名，需人工复核）')
    else:
        # 必须出现「if (!judgeOn(」这种短路形式，且位置在 judgeWithScopesStage 之前
        # （只查裸 judgeOn( 不足以证明短路 —— 可能只是读值不用）
        m = re.search(r'if\s*\(\s*!\s*judgeOn\s*\(', hrt_body)
        if m is None or m.start() > idx_stage:
            FAILS.append('[J7a] FAIL:%d handleReplyText（锚点B）在 judgeWithScopesStage '
                         '判定前无 if(!judgeOn( 短路 → 关闭自动判定后仍会 markSigned/markResult'
                         % hrt_ln)
        else:
            NOTES.append('[J7a] handleReplyText 判定前已见 if(!judgeOn( 短路')

    # ── J7b：sendSign（D 就地对答）──
    ss = method_bodies(code, 'sendSign')
    if not ss:
        print('FATAL 未找到方法 sendSign（识别逻辑需更新，不能静默通过）')
        return 1
    # sendSign 有多个重载，选包含「就地对答」判定块的（含 judgeWithScopesStage 的最长者）
    ss_stage = [(b, ln) for (b, ln) in ss if 'judgeWithScopesStage' in b]
    if not ss_stage:
        NOTES.append('[J7b] 所有 sendSign 重载内均未找到 judgeWithScopesStage，'
                     '就地判定可能已移除或改名，需人工复核')
    else:
        ss_body, ss_ln = max(ss_stage, key=lambda c: len(c[0]))
        idx_stage2 = ss_body.find('judgeWithScopesStage')
        m2 = re.search(r'if\s*\(\s*!\s*judgeOn\s*\(', ss_body)
        if m2 is None or m2.start() > idx_stage2:
            FAILS.append('[J7b] FAIL:%d sendSign（就地对答）在 judgeWithScopesStage '
                         '判定前无 if(!judgeOn( 短路 → 关闭自动判定后仍会 markSigned/markResult'
                         % ss_ln)
        else:
            NOTES.append('[J7b] sendSign 判定前已见 if(!judgeOn( 短路')

    for n in NOTES:
        print(n)
    if FAILS:
        print('')
        for f in FAILS:
            print(f)
        print('')
        print('judgeOn 总闸覆盖门禁：%d 条违规' % len(FAILS))
        return 1
    print('judgeOn 总闸覆盖门禁：全部通过（J7a/J7b）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
