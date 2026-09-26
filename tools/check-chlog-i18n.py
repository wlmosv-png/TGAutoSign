#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""更新日志双语门禁：CHANGELOG.md 顶部最新版本段，中英条目必须配对。

为什么有这道门（2026-09-23 定）：
  模块界面已国际化，但更新日志长期只有中文 —— 老外点进 Release / 看 CHANGELOG
  只能看到中文。这类"忘记写"的缺口不会报错，只会静默存在，靠人记不住，
  所以挂进门禁：写不全就出不了包。

判定规则（Operator 授权自定）：
  取 CHANGELOG.md 顶部第一个 "## X.Y.Z (NNN)" 段，数两种条目：
    中文条目 = 行首 "- **...**"，标题里含 CJK 字符
    英文条译 = 紧跟在中文条目之后的斜体行 "  *...*"（中英各一行的格式）
  通过条件：英文条译 >= 中文条目 - ALLOWANCE（默认 1）
    · 允许 1 条豁免：有些条目可能无法翻译（纯版本号、纯符号），
      留一点余量避免为一条卡死整个构建、导致每次都 SKIP 跳过、门禁形同虚设。
    · 但不能差太多 —— 差 2 条以上说明是"忘了写"而不是"个别豁免"。

只管"有没有"，不管"好不好"：机翻、错译一律放行，质量仍需人工把关。

跳过：TGAS_SKIP_CHLOG=1（会在输出里留痕，事后可查）。
"""
import io
import os
import re
import sys

ALLOWANCE = 0   # 英文一个都不能少（Operator 2026-09-26 定）

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
CHLOG = os.path.join(BASE, 'CHANGELOG.md')


def main():
    if not os.path.isfile(CHLOG):
        print('FATAL: 找不到 CHANGELOG.md: %s' % CHLOG)
        return 1
    s = io.open(CHLOG, encoding='utf-8').read()
    lines = s.split('\n')

    # 定位顶部第一个版本段
    head = None
    for i, l in enumerate(lines):
        if re.match(r'^##\s+\d+\.\d+\.\d+\s*\(\d+\)', l):
            head = i
            break
    if head is None:
        print('FATAL: CHANGELOG.md 顶部读不到 "## X.Y.Z (NNN)"')
        return 1
    end = len(lines)
    for j in range(head + 1, len(lines)):
        if lines[j].startswith('## '):
            end = j
            break
    seg = lines[head:end]
    title = seg[0].strip()

    # 逐行数：中文条目 / 英文条译
    cjk = re.compile(r'[\u4e00-\u9fff]')
    # 条目格式（2026-09-26 起）：中文在上、英文在下，各成一段。
    #   - **中文标题**
    #     中文正文（可多行）
    #     <空行>
    #     English heading.
    #     English body (可多行)
    #     <空行>
    # 一个「中文条目」= 以 "- **" 开头且含 CJK 的行。
    # 一个「英文条译」= 该条目之后、下一个 "- **" 之前，出现的首个
    #                   非空、不以 | # > 开头、且不含 CJK 的正文行。
    cjk = re.compile(r'[\u4e00-\u9fff]')
    zh_items = []      # 中文条目行号
    en_gloss = []      # 英文条译行号（每个中文条目至多算一次）
    for k, l in enumerate(seg):
        st = l.strip()
        if st.startswith('- **') and cjk.search(st):
            zh_items.append(k)

    # 为每个中文条目找它自己的英文段（上界是下一条中文条目）
    for n, idx in enumerate(zh_items):
        upper = zh_items[n + 1] if n + 1 < len(zh_items) else len(seg)
        for j in range(idx + 1, upper):
            st = seg[j].strip()
            if st == '' or st.startswith(('|', '#', '>')):
                continue
            if cjk.search(st) is None:
                en_gloss.append(j)
                break

    nzh, nen = len(zh_items), len(en_gloss)
    limit = nzh - ALLOWANCE

    print('  更新日志双语检查：')
    print('    %s' % title)
    print('    中文条目 %d 条 / 英文条译 %d 条（要求 >= %d）' % (nzh, nen, limit))

    if nzh == 0:
        print('    （本段没有中文条目，跳过配对检查）')
        return 0

    if nen < limit:
        # 打印缺译文的中文条目，便于直接补
        missing = []
        for n, idx in enumerate(zh_items):
            upper = zh_items[n + 1] if n + 1 < len(zh_items) else len(seg)
            found = False
            for j in range(idx + 1, upper):
                st = seg[j].strip()
                if st == '' or st.startswith(('|', '#', '>')):
                    continue
                if cjk.search(st) is None:
                    found = True
                    break
            if not found:
                missing.append(seg[idx].strip()[:72])
        print('    FATAL: 英文条译不足，缺 %d 条：' % (limit - nen))
        for m in missing:
            print('      · %s' % m)
        print('    补齐英文条译，或临时跳过：TGAS_SKIP_CHLOG=1')
        return 1

    print('    ✅ 中英配对通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
