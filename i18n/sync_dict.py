# -*- coding: utf-8 -*-
"""TGAutoSign i18n 字典同步工具

三个数据文件：
  en.tsv         全文翻译真相源（Lang.tr / Lang.tf 用）
  en_short.tsv   短词表（空间紧张处，Lang.trShort 优先取；英文比中文长，放不下时用）
  en.missing.tsv 待译清单（--scan 生成；补上 TAB 后的英文即可）

用法：
  python3 i18n/sync_dict.py --scan    扫描缺失（**合并**写入 en.missing.tsv，不覆写已填译文）
  python3 i18n/sync_dict.py --apply   把 en.tsv + en.missing.tsv + en_short.tsv 回写 Lang.java
                                      （同时把新译回写 en.tsv / en_short.tsv，保证下次 apply 不丢）

历史坑（2026-09-23 修）：
  1) --scan 曾直接覆写 en.missing.tsv → 刚填好的译文在下次 scan 时丢失。
  2) --apply 只从 en.tsv + missing 生成，不回写 en.tsv → 译文不在真相源里，下次 apply 就没了。
  3) --apply 重建整个 static 块，把 EN_SHORT 的 put 全部抹掉 → 短词表静默失效、英文布局又挤坏。
"""
import io, os, re, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
SRC = os.path.join(BASE, 'app/src/main/java/io/github/wlmosv_png/tgautosign/TGAutoSignCore.java')
LANG = os.path.join(BASE, 'app/src/main/java/io/github/wlmosv_png/tgautosign/Lang.java')
TSV = os.path.join(BASE, 'i18n/en.tsv')
SHORT = os.path.join(BASE, 'i18n/en_short.tsv')
MISS = os.path.join(BASE, 'i18n/en.missing.tsv')

# 需要翻译的字符：CJK 汉字/部首 / 全角字符 / CJK 标点
# （只查汉字会漏掉「{0}：{1}」这种整串只有一个全角冒号的文案）
HAN = re.compile(r'[\u2e80-\u9fff\uff00-\uffef\u3000-\u303f]')


def java_unesc(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            out.append({'n': '\n', 'r': '\r', 't': '\t', '"': '"', '\\': '\\'}.get(n, '\\' + n))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def java_esc(s):
    return (s.replace('\\', '\\\\')
             .replace('"', '\\"')
             .replace('\n', '\\n')
             .replace('\r', '\\r')
             .replace('\t', '\\t'))


def tsv_esc(s):
    return (s.replace('\\', '\\\\')
             .replace('\n', '\\n')
             .replace('\t', '\\t'))


def tsv_unesc(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            out.append({'t': '\t', 'n': '\n', 'r': '\r', '\\': '\\'}.get(n, '\\' + n))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def read_tsv(path):
    pairs = []
    if not os.path.exists(path):
        return pairs
    for line in io.open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line or line.startswith('#'):
            continue
        if '\t' not in line:
            continue
        z, e = line.split('\t', 1)
        pairs.append((tsv_unesc(z), tsv_unesc(e)))
    return pairs


def write_tsv(path, pairs, header=None):
    lines = []
    if header:
        lines.append('# ' + header)
    for z, e in pairs:
        lines.append(tsv_esc(z) + '\t' + tsv_esc(e))
    io.open(path, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')


def collect_used(src):
    """收集源码里所有 Lang.tr/tf/trShort 用到的字面量。"""
    used = set()
    for m in re.finditer(r'Lang\.t(?:r|f|rShort)\s*\(', src):
        i = m.end()
        depth = 1
        j = i
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
        args = src[i:j - 1]
        lit = re.match(r'\s*("(?:[^"\\]|\\.)*"(?:\s*\+\s*"(?:[^"\\]|\\.)*")*)', args)
        if not lit:
            continue
        parts = re.findall(r'"((?:[^"\\]|\\.)*)"', lit.group(1))
        val = ''.join(java_unesc(p) for p in parts)
        if HAN.search(val):
            used.add(val)
    return used


def scan():
    src = io.open(SRC, encoding='utf-8').read()
    have = {z for z, _ in read_tsv(TSV)}
    have |= {z for z, _ in read_tsv(SHORT)}
    # 关键：合并旧 missing，保留已填译文（不覆写）
    old = read_tsv(MISS)
    oldmap = {z: e for z, e in old}
    used = collect_used(src)
    missing = sorted(x for x in used if x not in have)

    merged = {}
    for z in missing:
        merged[z] = oldmap.get(z, '')
    for z, e in old:
        if z not in merged:
            merged[z] = e

    if not merged:
        print("✅ 无缺失")
        return 0

    write_tsv(MISS, sorted(merged.items(), key=lambda kv: kv[0]),
              header='待翻译（补上 TAB 后的英文即可）')
    todo = [z for z in missing if not oldmap.get(z, '').strip()]
    print("缺失 %d 条（其中待填 %d 条）-> %s" % (len(missing), len(todo), MISS))
    for x in missing:
        flag = ' ' if oldmap.get(x, '').strip() else '!'
        print("  %s %s" % (flag, x.replace('\n', '\\n')[:90]))
    return 0 if not todo else 1


def apply():
    """把 en.tsv + en.missing.tsv + en_short.tsv 回写 Lang.java，并把新译落盘到 tsv。"""
    full = {}
    for z, e in read_tsv(TSV):
        full[z] = e
    for z, e in read_tsv(MISS):
        if e.strip():
            full[z] = e
    shorts = {}
    for z, e in read_tsv(SHORT):
        shorts[z] = e

    # 落盘真相源（下次 apply 不会再丢）
    write_tsv(TSV, sorted(full.items(), key=lambda kv: (len(kv[0]), kv[0])),
              header='TGAutoSign 全文翻译真相源（中文<TAB>英文）')
    write_tsv(SHORT, sorted(shorts.items(), key=lambda kv: kv[0]),
              header='短词表：空间紧张的等宽 chip 用（Lang.trShort 优先取）')

    src = io.open(LANG, encoding='utf-8').read()
    cut = src.find('    static {')
    if cut < 0:
        print("FATAL: Lang.java 里找不到 '    static {'")
        return 1
    head = src[:cut]
    tail = '\n    }\n}\n'

    body = ['    static {']
    body.append('        // ── 短词表：空间紧张的等宽 chip（英文优先取这里）──')
    for z in sorted(shorts):
        body.append('        EN_SHORT.put("%s", "%s");' % (java_esc(z), java_esc(shorts[z])))
    body.append('')
    body.append('        // ── 全文：中文 -> 英文 ──')
    for z in sorted(full, key=lambda x: (len(x), x)):
        body.append('        EN.put("%s", "%s");' % (java_esc(z), java_esc(full[z])))

    io.open(LANG, 'w', encoding='utf-8').write(head + '\n'.join(body) + tail)
    print("回写 EN %d 条 + EN_SHORT %d 条 -> Lang.java" % (len(full), len(shorts)))
    return 0


if __name__ == '__main__':
    if '--apply' in sys.argv:
        sys.exit(apply())
    sys.exit(scan())
