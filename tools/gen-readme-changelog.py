#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 CHANGELOG.md 生成 README 的「更新日志」浓缩段（最新 N 版）。

为什么要有它（2026-09-23 定）：
  README.md / README.en.md 里的更新日志段是**手写副本**，和 CHANGELOG.md 是两份
  独立内容 —— 必然跑偏（1.5.8 发布时 README.en.md 就还停在 1.5.7）。
  改成从 CHANGELOG.md 生成，单一真相源，还能顺带拿到双语。

用法：
  python3 gen-readme-changelog.py            # 打印 zh 段（模块仓 README.md 用）
  python3 gen-readme-changelog.py --lang en  # 打印 en 段（README.en.md 用）
  python3 gen-readme-changelog.py --versions 2

输出格式（与现有 README 一致）：
  ## 📜 更新日志 · Changelog

  ### v1.5.8 (121) — 2026-09-23

  **修复**：条目一 · 条目二 · 条目三

  **诊断**：...

  [完整更新日志 →](https://github.com/wlmosv-png/TGAutoSign/blob/master/CHANGELOG.md)
"""
import io
import os
import re
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
CHLOG = os.path.join(BASE, 'CHANGELOG.md')
FULL_LINK = 'https://github.com/wlmosv-png/TGAutoSign/blob/master/CHANGELOG.md'

CJK = re.compile(r'[\u4e00-\u9fff]')
SEC_EN = {'新增': 'New', '界面': 'UI', '修复': 'Fixed', '诊断': 'Diagnostics',
          '可靠性': 'Reliability', '兼容': 'Compatibility',
          '架构更新': 'Architecture', '工具': 'Tooling', '文档': 'Docs',
          '维护范围': 'Supported clients'}
VER = re.compile(r'^##\s+(\d+\.\d+\.\d+)\s*\((\d+)\)\s*[—-]\s*(.+)$')


def _en_from_block(body, start_idx, stop_idx):
    """从「成块对照」的中文条目里抽出英文摘要。

    结构（body 是版本段的行列表，start_idx 指向 "- **中文标题**" 那行）：
        - **中文标题**
          中文正文若干行...

          English body line 1.
          English body line 2.

    判定：条目块内，第一条「以 ASCII 大写字母开头且不含 CJK」的行，即为英文正文起点。
    返回英文正文的**首句**（README 浓缩段要的是短摘要，不是整段）。
    """
    block = []
    for l in body[start_idx + 1:stop_idx]:
        st = l.strip()
        if st.startswith('- **') or st.startswith('### ') or st.startswith('## '):
            break
        block.append(l)
    # 找英文正文起点
    en_start = None
    for i, l in enumerate(block):
        st = l.strip()
        if not st:
            continue
        if st[0].isupper() and st[0].isascii() and not CJK.search(st):
            en_start = i
            break
    if en_start is None:
        return ''
    # 只要首句：成块格式的英文正文是完整段落，塞进 README 浓缩段会爆长。
    # 拼接时按行保留，遇到空行或新句起点即停。
    parts = []
    for x in block[en_start:]:
        st = x.strip()
        if not st:
            if parts:
                break
            continue
        parts.append(st)
    text = ' '.join(parts)
    m = re.search(r'^(.+?[.!?])(?:\s|$)', text)
    out = (m.group(1) if m else text).strip()
    # 首句仍然过长时按逗号再切一刀：README 是浓缩段，不是完整段落。
    if len(out) > 120:
        cut = out[:120]
        pos = max(cut.rfind(', '), cut.rfind('; '))
        if pos > 40:
            out = cut[:pos] + '…'
        else:
            out = cut.rstrip() + '…'
    return out


def parse_versions(text, want):
    """返回 [(ver, code, date, [(section, [ (zh,en) ]) ])]，取顶部 want 个版本。"""
    lines = text.split('\n')
    heads = [i for i, l in enumerate(lines) if VER.match(l)]
    out = []
    for n, i in enumerate(heads[:want]):
        m = VER.match(lines[i])
        stop = heads[n + 1] if n + 1 < len(heads) else len(lines)
        body = lines[i + 1:stop]
        sections = []
        cur_sec, items = None, []
        for body_idx, l in enumerate(body):
            st = l.strip()
            if st.startswith('### '):
                if cur_sec is not None and items:
                    sections.append((cur_sec, items))
                cur_sec, items = st[4:].split(' · ')[0].strip(), []
                continue
            if st.startswith('- **'):
                # 两种写法都要认：
                #   ① 内联： "- **中文 · English**"
                #   ② 成块： "- **中文标题**" + 缩进中文正文 + 空行 + 缩进英文正文
                #      2026-09-26 的 1.6.0 改用了 ②，脚本只认 ① 时英文段会整段生成不出来。
                mm = re.match(r'^-\s+\*\*(.+?)\*\*', st)
                if mm:
                    t = mm.group(1)
                    if ' · ' in t:
                        zh, en = t.split(' · ', 1)
                    else:
                        zh = t
                        en = _en_from_block(body, body_idx, stop)
                    items.append((zh.strip(), en.strip()))
        if cur_sec is not None and items:
            sections.append((cur_sec, items))
        out.append((m.group(1), m.group(2), m.group(3).strip(), sections))
    return out


def main():
    lang = 'zh'
    want = 2
    a = sys.argv[1:]
    for k, v in zip(a, a[1:]):
        if k == '--lang':
            lang = v
        elif k == '--versions':
            want = int(v)

    s = io.open(CHLOG, encoding='utf-8').read()
    vers = parse_versions(s, want)
    if not vers:
        print('FATAL: CHANGELOG.md 里没找到版本段', file=sys.stderr)
        return 1

    buf = ['## 📜 更新日志 · Changelog', '']
    for ver, code, date, sections in vers:
        block = []
        for name, items in sections:
            if name in ('维护范围', 'Supported clients'):
                continue
            if lang == 'zh':
                picks = [i[0] for i in items if i[0]]
                sec = name
            else:
                # 英文段：取英文条译；历史版本可能没有英文 —— 整节跳过，不硬塞中文
                picks = [i[1] for i in items
                         if i[1] and not CJK.search(i[1]) and i[1] != i[0]]
                sec = SEC_EN.get(name, name)
            if not picks:
                continue
            if lang == 'zh':
                block.append('**%s**：%s' % (sec, ' · '.join(picks)))
            else:
                block.append('**%s**: %s' % (sec, ' · '.join(picks)))
            block.append('')
        # 整版没有任何可用条目就不输出标题（英文页的历史版本会走到这里）
        if not block:
            continue
        buf.append('### v%s (%s) — %s' % (ver, code, date))
        buf.append('')
        buf.extend(block)
    buf.append('[完整更新日志 →](%s)' % FULL_LINK if lang == 'zh'
               else '[Full changelog →](%s)' % FULL_LINK)
    print('\n'.join(buf))
    return 0


if __name__ == '__main__':
    sys.exit(main())
