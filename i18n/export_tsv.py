# -*- coding: utf-8 -*-
"""把 Lang.java 里的 EN 字典导出成独立 TSV（翻译真相源）。
   TSV 格式：中文<TAB>英文
   好处：可 diff、可交给翻译、检查器直接读、不用编译。
"""
import io, re

P = '/data/local/tmp/tgas/app/src/main/java/io/github/wlmosv_png/tgautosign/Lang.java'
OUT = '/data/local/tmp/tgas/i18n/en.tsv'

src = io.open(P, encoding='utf-8').read()


def java_unesc(s):
    """Java 源码字符串 -> 真实字符串"""
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i+1]
            if n == 'n': out.append('\n')
            elif n == 'r': out.append('\r')
            elif n == 't': out.append('\t')
            elif n == '"': out.append('"')
            elif n == '\\': out.append('\\')
            else: out.append('\\'); out.append(n)
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


pairs = []
seen = set()
# 短词表也导出（标注 [short]）
for m in re.finditer(r'EN_SHORT\.put\("((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\)', src):
    zh = java_unesc(m.group(1))
    en = java_unesc(m.group(2))
    if zh in seen:
        continue
    seen.add(zh)
    pairs.append((zh, en))

for m in re.finditer(r'EN\.put\("((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\)', src):
    zh = java_unesc(m.group(1))
    en = java_unesc(m.group(2))
    if zh in seen:
        continue
    seen.add(zh)
    pairs.append((zh, en))

pairs.sort(key=lambda x: x[0])

with io.open(OUT, 'w', encoding='utf-8') as f:
    f.write('# TGAutoSign 英文翻译表  (格式: 中文<TAB>英文)\n')
    f.write('# \\n 表示换行；{0} {1} 是变量占位符；# 开头为注释\n')
    for zh, en in pairs:
        z = zh.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n').replace('\r', '\\r')
        e = en.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n').replace('\r', '\\r')
        f.write(z + '\t' + e + '\n')

print("导出", len(pairs), "条 ->", OUT)
