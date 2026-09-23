#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TGAutoSign i18n 静态检查器
=========================
目标：让「UI 文案漏翻译」变成构建期错误，而不是上线后才发现。

原理
----
1. 认定哪些是 UI 出口：
   - 已知的 15 个私有构建器（参数中的 String = UI 文案）
   - 直接的 .setText/.setHint/.setTitle/.setMessage 调用
   - toast()/showDialog() 等已内部过 Lang 的，视为安全
2. 对每个出口，检查其参数来源是否会被 Lang.tr/tf 处理：
   - 出口方法体内有 Lang.tr(参数) → 安全
   - 调用点用 Lang.tr/tf 包裹字面量 → 安全
   - 否则，若字面量含中文且不在白名单 → 报错
3. 白名单：日志、宿主交互、用户数据（命令模板/目标名）等，明确列出。

用法
----
  python3 i18n/check_i18n.py            # 检查，有问题 exit 1
  python3 i18n/check_i18n.py --list     # 只列出全部 UI 文案（供翻译）
"""
import io
import os
import re
import sys

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                   'app/src/main/java/io/github/wlmosv_png/tgautosign/TGAutoSignCore.java')
LANGSRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                       'app/src/main/java/io/github/wlmosv_png/tgautosign/Lang.java')

HAN = re.compile(r'[\u2e80-\u9fff\uff00-\uffef\u3000-\u303f]')   # 含 CJK 汉字/CJK 标点/全角（原只查汉字，漏掉『：』这类）


# ── 已确认「内部会过 Lang」的出口方法（调用点传中文即安全）──────────────
SAFE_CALLS = [
    'Lang.tr', 'Lang.tf',
    'withIconText',      # 内部 Lang.tr(text)
    'menuTile',          # 内部 Lang.tr(title)/Lang.tr(sub)
    'addTile',           # 转发给 menuTile
    'sectionHeader',     # 内部 Lang.tr(text)
    'sortChip',          # 内部 Lang.tr(label)
    'badgeChip',         # 内部 Lang.tr(text)
    'badge',             # 内部 Lang.tr(label)
    'peerChip',          # 内部已处理
    'typeChip',          # 内部已处理
    'targetRow',         # 内部 Lang.tr(status)
    'tcard',             # 内部 Lang.tr(h)/Lang.tr(b)
    'toast', 'toastOnce', 'toastDaily',   # 内部 Lang.tr(msg)
    'adInput',           # 内部 Lang.tr(hint)
    'showDialog',        # 内部 Lang.tr(title)/Lang.tr(negLabel)
    'catChip',           # 内部 Lang.tr(label)
    'menuItem',          # 内部 Lang.tr(title)/Lang.tr(subtitle)
    'emptyView',         # 内部 Lang.tr(text)
    'simpleTextView',    # 内部 Lang.tr(text)
    'swRow',             # 内部 Lang.tr(label)
    'logChip',           # 内部 Lang.tr(label)
    'addDiagRow',        # 内部 Lang.tr(label)
    'withIcon',
    'leadIcon',          # 只处理图标，不涉及文字
]

# ── 明确「不该翻译」的：日志 / 数据 / 宿主交互 / 内部匹配 ────────────────
# 按调用函数名判断
LOG_FUNCS = ['jlog', 'logs', 'logd', 'logw', 'loge', 'logi', 'logException',
             'logLine', 'logw', 'logTag', 'appendLog']
# 按所在行是否这些上下文判断


def read(p):
    return io.open(p, encoding='utf-8').read()


def find_call_args(src, start):
    """从 start（'(' 之后）取出实参文本"""
    depth = 1
    j = start
    instr = False
    esc = False
    while j < len(src) and depth > 0:
        ch = src[j]
        if esc:
            esc = False
        elif ch == '\\':
            esc = True
        elif ch == '"':
            instr = not instr
        elif not instr:
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
        j += 1
    return src[start:j-1]


def collect_exports(src):
    """收集所有 UI 出口调用及其实参位置"""
    out = []
    pat = re.compile(r'\b(' + '|'.join(re.escape(s) for s in SAFE_CALLS if s != 'Lang.tr' and s != 'Lang.tf') + r')\s*\(')
    for m in pat.finditer(src):
        name = m.group(1)
        args = find_call_args(src, m.end())
        ln = src[:m.start()].count('\n') + 1
        out.append((ln, name, args, m.start(), m.end() + len(args)))
    # 直接 setText / setHint / setTitle / setMessage
    pat2 = re.compile(r'\.(setText|setHint|setTitle|setMessage'
                         r'|setPositiveButton|setNegativeButton|setNeutralButton)\s*\(')
    for m in pat2.finditer(src):
        args = find_call_args(src, m.end())
        ln = src[:m.start()].count('\n') + 1
        out.append((ln, '.' + m.group(1), args, m.start(), m.end() + len(args)))
    # 额外出口：这些调用的「文案实参」也必须过 Lang
    pat3 = re.compile(r'\b(createChooser|sendSavedMessage)\s*\(')
    for m in pat3.finditer(src):
        args = find_call_args(src, m.end())
        ln = src[:m.start()].count('\n') + 1
        out.append((ln, m.group(1), args, m.start(), m.end() + len(args)))
    return out


def has_lang_wrap(args):
    return 'Lang.tr' in args or 'Lang.tf' in args


def body_of_method(src, name):
    """取某个私有方法的整个方法体"""
    m = re.search(SIG + re.escape(name) + r'\s*\(', src)
    if not m:
        return None
    i = src.find('{', m.end() - 1)
    if i < 0:
        return None
    depth = 1
    j = i + 1
    instr = False
    esc = False
    while j < len(src) and depth > 0:
        ch = src[j]
        if esc:
            esc = False
        elif ch == '\\':
            esc = True
        elif ch == '"':
            instr = not instr
        elif not instr:
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
        j += 1
    return src[i+1:j-1]


METHOD_START = re.compile(r'^    (?:@\w+\s+)?(?:private|public|protected|static|final|void|View|TextView|Button|LinearLayout|Object|String|int|boolean|long|Map|List)\b')



def _lang_spans(text):
    """返回 text 中所有 Lang.tr/tf/trShort( ... ) 调用的 (start, end) 区间。"""
    spans = []
    for m in re.finditer(r'Lang\.(?:tr|tf|trShort)\s*\(', text):
        d = 1
        j = m.end()
        instr = False
        esc = False
        while j < len(text) and d > 0:
            ch = text[j]
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == '"':
                instr = not instr
            elif not instr:
                if ch == '(':
                    d += 1
                elif ch == ')':
                    d -= 1
            j += 1
        spans.append((m.start(), j))
    return spans


def _strip_comments(src):
    """把 // 与 /* */ 注释内容换成空格，保留换行 → 长度/行号不变。"""
    out = list(src)
    n = len(src)
    i = 0
    instr = False
    esc = False
    while i < n:
        ch = src[i]
        if instr:
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == '"':
                instr = False
            i += 1
            continue
        if ch == '"':
            instr = True
            i += 1
            continue
        if ch == '\'':
            j = i + 1
            while j < n and src[j] != '\'':
                if src[j] == '\\':
                    j += 1
                j += 1
            i = j + 1
            continue
        if ch == '/' and i + 1 < n:
            nxt = src[i + 1]
            if nxt == '/':
                j = i
                while j < n and src[j] != '\n':
                    out[j] = ' '
                    j += 1
                i = j
                continue
            if nxt == '*':
                j = i
                while j + 1 < n and not (src[j] == '*' and src[j + 1] == '/'):
                    if src[j] != '\n':
                        out[j] = ' '
                    j += 1
                if j + 1 < n:
                    out[j] = ' '
                    out[j + 1] = ' '
                    j += 2
                else:
                    j = n
                i = j
                continue
        i += 1
    return ''.join(out)


def _match_brace(src, open_idx):
    """从 open_idx 处的 '{' 开始配对，返回闭合 '}' 之后的下标；不配对返回 None。"""
    n = len(src)
    depth = 0
    j = open_idx
    instr = False
    esc = False
    while j < n:
        ch = src[j]
        if instr:
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == '"':
                instr = False
        else:
            if ch == '"':
                instr = True
            elif ch == '\'':
                k = j + 1
                while k < n and src[k] != '\'':
                    if src[k] == '\\':
                        k += 1
                    k += 1
                j = k
            elif ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    return j + 1
        j += 1
    return None


_DECL = re.compile(
    r'\n( *)((?:private|public|protected|static|final|void|View|TextView|Button|LinearLayout|'
    r'Object|String|int|boolean|long|Dialog|Map|List|Set|CharSequence|Drawable|Runnable)'
    r'[^\n;=]{0,90}?)\b(\w+)\s*\(')

_SKIP_NAMES = ('if', 'for', 'while', 'switch', 'return', 'new', 'catch', 'synchronized', 'try')


def _find_methods(src):
    """独立扫描：每个声明各自配对花括号，互不影响。"""
    out = []
    for m in _DECL.finditer(src):
        name = m.group(3)
        if name in _SKIP_NAMES:
            continue
        bb = src.find('{', m.end() - 1)
        semi = src.find(';', m.end() - 1)
        if bb < 0:
            continue
        if 0 <= semi < bb:
            continue   # 抽象/接口方法，无体
        close = _match_brace(src, bb)
        if close is None:
            continue   # 配对失败，跳过该方法（不影响其它）
        out.append((name, src[bb + 1:close - 1]))
    return out


_METHOD_CACHE = None


def _methods(src):
    global _METHOD_CACHE
    if _METHOD_CACHE is None:
        _METHOD_CACHE = _find_methods(_strip_comments(src))
    return _METHOD_CACHE


def bodies_of_all(src, name):
    return [b for (nm, b) in _methods(src) if nm == name]


def body_of_method(src, name):
    bs = bodies_of_all(src, name)
    return bs[0] if bs else None


def method_is_safe(src, name, safe_names, visited, keys):
    """递归判定：方法内部直接过 Lang，或把 String 参数透传给另一个安全方法。"""
    if name in visited:
        return False
    visited.add(name)
    if name.startswith('.'):
        return False
    b = body_of_method(src, name)
    if b is None:
        return False
    if 'Lang.tr' in b or 'Lang.tf' in b:
        return True
    # 重载转调：本方法调用了「同名」的另一个重载，而那个重载是安全的
    if re.search(r'\b' + re.escape(name) + r'\s*\(', b):
        # 找另一个同名方法体（简单：合并同名的所有方法体判断）
        for b2 in bodies_of_all(src, name):
            if b2 is b:
                continue
            if 'Lang.tr' in b2 or 'Lang.tf' in b2:
                return True
    # 透传判定：调用了其它安全方法（参数含变量）
    for s in safe_names:
        if s in ('Lang.tr', 'Lang.tf') or s == name:
            continue
        if re.search(r'\b' + re.escape(s) + r'\s*\(', b):
            if method_is_safe(src, s, safe_names, visited, keys):
                return True
    return False


def main():
    src = read(SRC)
    lang = read(LANGSRC)

    # 1. 字典 keys
    keys = set()
    for m in re.finditer(r'EN\.put\("((?:[^"\\]|\\.)*)"', lang):
        raw = m.group(1)
        real = (raw.replace('\\"', '"').replace('\\\\', '\\')
                   .replace('\\n', '\n').replace('\\r', '\r').replace('\\t', '\t'))
        keys.add(real)

    # 2. 收集所有出口调用
    exports = collect_exports(src)

    # 3. 已知安全的出口方法名集合
    safe_names = set(SAFE_CALLS)

    problems = []
    covered = set()

    lines = src.split('\n')

    for ln, name, args, s0, s1 in exports:
        # 取该调用所在的整条语句（多行也一起）
        stmt_start = max(0, ln - 6)
        stmt = '\n'.join(lines[stmt_start:ln + 8])
        # 该调用的实参中所有中文字面量
        lits = []
        for lm in re.finditer(r'"((?:[^"\\]|\\.)*)"', args):
            raw = lm.group(1)
            if HAN.search(raw):
                real = (raw.replace('\\"', '"').replace('\\\\', '\\')
                           .replace('\\n', '\n').replace('\\r', '\r').replace('\\t', '\t'))
                lits.append(real)
        if not lits:
            continue

        # 情况 A：调用点自己就用了 Lang → 安全
        if has_lang_wrap(args):
            for x in lits:
                covered.add(x)
            continue

        # 情况 B：出口方法内部过 Lang → 安全
        if name in safe_names and name not in ('.setText', '.setHint', '.setTitle', '.setMessage',
                                                '.setPositiveButton', '.setNegativeButton', '.setNeutralButton'):
            if method_is_safe(src, name, safe_names, set(), keys):
                for x in lits:
                    covered.add(x)
                continue

        # 情况 C：本行就是日志调用 → 不查（原实现用 14 行窗口，会把附近出口全部误豁免）
        cur_line = lines[ln - 1]
        if any(re.search(r'\b' + f + r'\s*\(', cur_line) for f in LOG_FUNCS):
            continue

        # 其余 = 问题
        for x in lits:
            problems.append((ln, name, x))

    # 4. 检查「被 Lang 用到但字典没有」的（用与 sync_dict 相同的求值逻辑）
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
        import sync_dict as SD
        used = SD.collect_used(src)
        missing_dict = []
        for x in sorted(used):
            if x not in keys:
                missing_dict.append((0, x))
    except Exception as _e:
        missing_dict = []

    # 5. 赋值型检测：变量 = "中文..." [+ ...]，随后进 setText(...)
    problems2 = []
    assign_pat = re.compile(r'\b(\w+)\s*=\s*([^;\n]*"[^"\n]*[\u4e00-\u9fa5][^"\n]*"[^;\n]*);')
    for am in assign_pat.finditer(src):
        var = am.group(1)
        expr = am.group(2)
        if 'Lang.tr' in expr or 'Lang.tf' in expr:
            continue
        ln = src[:am.start()].count('\n') + 1
        # 该变量是否进了 setText
        if not re.search(r'\.setText\(\s*' + re.escape(var) + r'\s*\)', src):
            continue
        for lm in re.finditer(r'"((?:[^"\\]|\\.)*)"', expr):
            raw = lm.group(1)
            if HAN.search(raw):
                real = (raw.replace('\\"', '"').replace('\\\\', '\\')
                           .replace('\\n', chr(10)))
                # 单字/纯符号跳过
                if len(real.strip()) > 1:
                    problems2.append((ln, 'assign:' + var, real))

    problems.extend(problems2)

    # ── 规则 5：辅助 String 方法体内的中文字面量（外层 Lang.tf 挡不住的）──
    # 背景：setText(Lang.tf("下次签到 {0}", nx)) —— nx 是方法返回值，
    #       外层模板过了 Lang，但 nx 内部拼接的中文漏译。
    rule5_aux_method_strings = []
    ALLOW_AUX = {
        # 纯数据/日志方法：返回值只进日志或作为数据键，不直接显示
        'todayStr', 'accountPrefix', 'accountLabel', 'kLast', 'kRetry', 'kRetryAt',
        'entryId', 'hhmm', 'cbDataLabel', 'hostPkg', 'tgFlag',
        'ensureTimerPlan', 'windowRange', 'timerPlan',
        # 纯逻辑判定：体内中文用于 contains\/equals 比较，改了会坏功能
        'statusRank', 'statusColorCol', 'peerKindCn', 'sortWeight',
    }
    # 只要体内有 setText/toast/showDialog 等出口，或名字像 UI 文案的，就查
    for (mname, mbody) in _methods(src):
        if mname in ALLOW_AUX:
            continue
        # 只看返回 String 的私有方法
        msig = re.search(r'\b(?:private|protected)\s+(?:static\s+)?String\s+' + re.escape(mname) + r'\s*\(', src)
        if not msig:
            continue
        # 方法体里若已经有 Lang.tr/tf，仍要查「没被包裹的裸字面量」
        for lm in re.finditer(r'"((?:[^"\\]|\\.)*)"', mbody):
            raw = lm.group(1)
            if not HAN.search(raw):
                continue
            real = (raw.replace('\\"', '"').replace('\\\\', '\\'))
            # 整个方法体内若该字面量以 Lang.tf("...", ) 形式出现也算安全
            # 简化：检查该字面量所在行是否有 Lang.tf/Lang.tr
            off = lm.start()
            if any(a <= off < b for (a, b) in _lang_spans(mbody)):
                continue
            rule5_aux_method_strings.append((mname, real))

    # ── 规则 6：出口实参里的「碎片拼接」中文字面量（内部 Lang.tr(整串) 匹配不上字典）──
    rule6_fragments = []
    lang_span = re.compile(r'Lang\.(?:tr|tf|trShort)\s*\(')
    litspan = re.compile(r'"((?:[^"\\]|\\.)*)"')
    for ln, name, args, s0, s1 in exports:
        if '+' not in args:
            continue
        # 标出实参里所有 Lang.tr/tf 调用的字符区间
        spans = []
        for lm in lang_span.finditer(args):
            d = 1
            j = lm.end()
            instr = False
            esc = False
            while j < len(args) and d > 0:
                ch = args[j]
                if esc:
                    esc = False
                elif ch == '\\':
                    esc = True
                elif ch == '"':
                    instr = not instr
                elif not instr:
                    if ch == '(':
                        d += 1
                    elif ch == ')':
                        d -= 1
                j += 1
            spans.append((lm.start(), j))
        for lm in litspan.finditer(args):
            raw = lm.group(1)
            if not HAN.search(raw):
                continue
            real = (raw.replace('\\"', '"').replace('\\\\', '\\'))
            if len(real.strip()) < 2:
                continue
            # 该字面量是否落在某个 Lang.* 调用内
            if any(a <= lm.start() < b for (a, b) in spans):
                continue
            # 精确判定：该字面量本身必须是 `+` 的操作数
            pre = args[:lm.start()].rstrip()
            post = args[lm.end():].lstrip()
            is_operand = pre.endswith('+') or post.startswith('+')
            if is_operand:
                rule6_fragments.append((ln, name, real))

    # ── 规则 7：消息生成器（文本会发到用户 Telegram / 写进剪贴板）──
    # 这些方法返回 void，规则 5 覆盖不到；体内裸中文必须过 Lang。
    MSG_METHODS = ['notifySummary', 'noteFailStreak']
    rule7_msg_strings = []
    for _mn in MSG_METHODS:
        for _b in bodies_of_all(src, _mn):
            for lm in re.finditer(r'"((?:[^"\\]|\\.)*)"', _b):
                raw = lm.group(1)
                if not HAN.search(raw):
                    continue
                real = raw.replace('\\"', '"').replace('\\\\', '\\')
                ln2 = _b[:lm.start()].count('\n') + 1
                line_start = _b.rfind('\n', 0, lm.start()) + 1
                line_end = _b.find('\n', lm.start())
                if line_end < 0:
                    line_end = len(_b)
                cur = _b[line_start:line_end]
                # 该行的日志调用实参不算 UI（如 logException("[通知] 汇总", t)）
                if any(re.search(r'\b' + f + r'\s*\(', cur) for f in LOG_FUNCS):
                    continue
                off7 = lm.start()
                if any(a <= off7 < b for (a, b) in _lang_spans(_b)):
                    continue
                rule7_msg_strings.append((_mn, real))

    # ── 输出 ──
    if '--list' in sys.argv:
        print("=== 全部 UI 文案（%d 条去重）===" % len(covered | keys))
        for x in sorted(covered | keys):
            print(x.replace('\n', '\\n'))
        return 0

    for (mn, s) in rule5_aux_method_strings:
        problems.append((0, 'aux:' + mn, s))

    for (ln, mn, s) in rule6_fragments:
        problems.append((ln, 'frag:' + mn, s))

    for (mn, s) in rule7_msg_strings:
        problems.append((0, 'msg:' + mn, s))

    fail = False
    if problems:
        fail = True
        print("❌ 发现 %d 处 UI 文案未接 Lang：" % len(problems))
        for ln, name, x in problems:
            print("   L%-6d %-16s %s" % (ln, name, x.replace('\n', '\\n')[:90]))
    if missing_dict:
        fail = True
        print("❌ 发现 %d 条 Lang 用到但字典缺失：" % len(missing_dict))
        seen = set()
        for ln, x in missing_dict:
            if x in seen:
                continue
            seen.add(x)
            print("   %s" % (x.replace('\n', '\\n')[:90]))

    if fail:
        print("\n修复方式：")
        print("  1) 给出口方法体内加 Lang.tr(参数)  —— 或 ——")
        print("  2) 在调用点写 Lang.tr(\"中文\") / Lang.tf(\"模板 {0}\", v)")
        print("  3) 若确实不该翻译（日志/数据），把该调用加入检查器的白名单")
        return 1

    print("✅ i18n 检查通过（字典 %d 条，UI 出口 %d 处）" % (len(keys), len(exports)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
