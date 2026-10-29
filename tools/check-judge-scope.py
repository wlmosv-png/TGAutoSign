#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：判定链的**账号维度**是否被串扰（2026-10-30 新增，事故驱动）。

事故
----
用户「加了判定词换账号就不认」。原因不是词表，是那个**开关**的账号维度：
  · 词表 jmb_ok_words 是全局的 ✓
  · 但 judge_custom（「使用我的自定义词」）是**账号级**的
于是两处出问题：

  J1  加词入口 addJudgeWordsInternal 只给**当前账号**打开开关，
      而学习页那条入口 addJudgeWordScoped 有跨账号同步（2026-10-22 修过一半）。
      → 在账号1 加的词，账号2 判定时开关是 false，词不生效。

  J2  判定入口 judgeWithScopes 拿到了 prefix（目标所属账号），
      却仍读**当前选中账号**的单实例字段 JUDGE_USE_CUSTOM。
      → 面板停在账号 A（开关关着）时判定账号 B 的目标，B 的词不生效。

规则
----
J1 addJudgeWordsInternal 内必须出现对 accountSlots() 的遍历
   （即：加词时必须给所有已登录账号打开开关）。
J2 judgeWithScopes 内不得出现裸 JUDGE_USE_CUSTOM —— 必须走 judgeCustomOn(prefix)。
J3 judgeCustomOn 必须以 prefix 为参数读取（不得退化成读单实例字段）。
J4 判定链不得直读单实例 JUDGE_ENABLED（总闸）。
J5 判定链不得直读单实例 FALLBACK（兜底档）。
J6 judgeOn / looseOn / fallbackOf 三个按账号读取的 helper 必须存在。

退出码 1 = 有违规。跳过：TGAS_SKIP_JUDGESCOPE=1  指定文件：TGAS_CORE=/path
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


def method_body(src, name):
    res = []
    for m in re.finditer(r'\b' + re.escape(name) + r'\s*\(', src):
        ls = src.rfind('\n', 0, m.start()) + 1
        prefix = src[ls:m.start()]
        if re.search(r'[=;{(]', prefix):
            continue
        if not re.match(r'^[ \t]*(?:@\w+[ \t]*)*[\w<>\[\],\.\s]*$', prefix):
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


def pick(src, name):
    b = method_body(src, name)
    if not b:
        return None, -1
    return max(b, key=lambda c: len(c[0]))


def main():
    if os.environ.get('TGAS_SKIP_JUDGESCOPE') == '1':
        print('SKIP')
        return 0
    path = os.environ.get('TGAS_CORE') or CORE
    if not os.path.isfile(path):
        print('FATAL 找不到 %s' % path)
        return 1
    nc = strip_comments(io.open(path, encoding='utf-8').read())
    code = strip_strings(nc)

    add, add_ln = pick(code, 'addJudgeWordsInternal')
    jws, jws_ln = pick(code, 'judgeWithScopes')
    for nm, b in (('addJudgeWordsInternal', add), ('judgeWithScopes', jws)):
        if b is None:
            print('FATAL 未找到方法 %s（识别逻辑需更新，不能静默通过）' % nm)
            return 1
    # J3 的判据要看**字符串字面量**，所以用未剥字符串的文本；
    # 且该方法在修复前的版本里本就不存在 —— 记 FAIL 而不是 FATAL，
    # 否则 J1/J2 会被短路，拿不到"修复前报了什么"的完整证据。
    jco, jco_ln = pick(nc, 'judgeCustomOn')

    # J1：加词入口必须遍历所有账号槽位
    if not re.search(r'\baccountSlots\s*\(\s*\)', add):
        FAILS.append('[J1] FAIL:%d addJudgeWordsInternal 未遍历 accountSlots() → '
                     '只给当前账号打开 judge_custom，换账号后自定义词不生效' % add_ln)

    # J2：**所有判定路径**都不得读裸 JUDGE_USE_CUSTOM。
    # 2026-10-30：原来只查 judgeWithScopes —— 长消息链（onUpdateProcessed）
    # 是第二条判定路径，当时被漏掉，导致两条路径的开关判据不一致。
    jpaths = [('judgeWithScopes', jws, jws_ln)]
    oup, oup_ln = pick(code, 'onUpdateProcessed')
    if oup is not None:
        jpaths.append(('onUpdateProcessed', oup, oup_ln))
    for pname, pbody, pln in jpaths:
        if re.search(r'(?<![\w.])JUDGE_USE_CUSTOM(?![\w])', pbody):
            FAILS.append('[J2] FAIL:%d %s 直读单实例 JUDGE_USE_CUSTOM → '
                         '应走 judgeCustomOn(prefix)，否则用当前账号的开关判别的账号的目标'
                         % (pln, pname))
    if oup is None:
        NOTES.append('[J2] 未找到 onUpdateProcessed（长消息链判定路径），该项覆盖不完整')

    # J3：judgeCustomOn 必须存在，且以 prefix 拼键读取
    if jco is None:
        FAILS.append('[J3] FAIL judgeCustomOn 未实现 → 判定无法按目标所属账号读开关')
    elif not re.search(r'prefix\s*\+\s*"cfg_judge_custom"', jco):
        FAILS.append('[J3] FAIL:%d judgeCustomOn 未以 prefix 为键读取' % jco_ln)

    # J4/J5：判定链不得再直读这两个账号级单实例字段
    #     （设置页/装载/日志里的使用是合法的，所以只盯"判定链特征写法"）
    raw = io.open(path, encoding='utf-8').read()
    n4 = len(re.findall(r'if\s*\(\s*!\s*JUDGE_ENABLED\s*\)', raw))
    if n4:
        FAILS.append('[J4] FAIL 判定总闸仍直读单实例 JUDGE_ENABLED（%d 处）→ '
                     '应改判 _judgeOn / judgeOn(prefix)' % n4)
    n5 = len(re.findall(r'"loose"\s*\.\s*equals\s*\(\s*FALLBACK\s*\)', raw))
    if n5:
        FAILS.append('[J5] FAIL 兜底档仍直读单实例 FALLBACK（%d 处）→ '
                     '应改判 _fb / fallbackOf(prefix)' % n5)

    # J6：三个按账号读取的 helper 必须都在
    for fn in ('judgeOn', 'looseOn', 'fallbackOf'):
        b, _ln = pick(code, fn)
        if b is None:
            FAILS.append('[J6] FAIL 缺少 %s(prefix) → 账号级开关无法按账号读取' % fn)

    for n in NOTES:
        print(n)
    if FAILS:
        print('')
        for f in FAILS:
            print(f)
        print('')
        print('判定链账号维度门禁：%d 条违规' % len(FAILS))
        return 1
    print('判定链账号维度门禁：全部通过（J1~J6）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
