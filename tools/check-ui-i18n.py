#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
界面文案 i18n 门禁：Lang.tr() 用到的中文串必须在 EN 表里有映射。

为什么有这道门（2026-09-30 定）：
  本次日志改造一口气新增了 40 多条用户可见文案，全部漏了英文映射 ——
  英文设备上会直接显示中文。已有的 check-chlog-i18n.py 只管 CHANGELOG，
  管不到界面文案；这类缺口不会报错，只会静默存在。

  相比让作者"记得加"，挂进门禁更可靠。

判定：
  1) 扫描 TGAutoSignCore / TGAutoSignEntry 里 Lang.tr("...") / Lang.tf("...") 的字面量
  2) 与 Lang.java 的 EN.put 表比对，缺映射的列出来
  3) 纯符号/纯数字/含 CJK 以外字符的白名单跳过

退出码 1 = 有缺失（可挂进 build.sh）。
跳过：TGAS_SKIP_UI18N=1
"""
import io
import os
import re
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
JAVA_DIR = os.path.join(BASE, 'app/src/main/java/io/github/wlmosv_png/tgautosign')

# 不需要翻译的：纯符号、格式串、已是英文
WHITELIST = {
    "今",
}


def read(p):
    with io.open(p, encoding='utf-8') as f:
        return f.read()


def main():
    lang_p = os.path.join(JAVA_DIR, 'Lang.java')
    if not os.path.isfile(lang_p):
        print("FATAL: 找不到 Lang.java")
        return 1
    lang = read(lang_p)

    # 已登记的英文映射键
    have = set(re.findall(r'EN\.put\(\s*"((?:[^"\\]|\\.)*)"', lang))
    have_short = set(re.findall(r'EN_SHORT\.put\(\s*"((?:[^"\\]|\\.)*)"', lang))
    have |= have_short

    # 扫描源码里所有 Lang.tr / Lang.tf 的中文字面量
    used = {}
    for name in ('TGAutoSignCore.java', 'TGAutoSignEntry.java', 'SettingsRefs.java'):
        p = os.path.join(JAVA_DIR, name)
        if not os.path.isfile(p):
            continue
        src = read(p)
        for m in re.finditer(r'Lang\.(?:tr|tf)\(\s*"((?:[^"\\]|\\.)*)"', src):
            s = m.group(1)
            if not re.search(r'[\u4e00-\u9fff]', s):
                continue          # 不含中文，无需翻译
            if s in WHITELIST:
                continue
            used.setdefault(s, name)

    missing = sorted(k for k in used if k not in have)
    print("  界面文案双语检查：")
    print("    Lang.tr/tf 中文串 %d 条 / 已映射 %d 条"
          % (len(used), len(used) - len(missing)))
    if not missing:
        print("    ✅ 全部有英文映射")
        return 0
    print("    ❌ 以下 %d 条缺少英文映射（英文设备会显示中文）：" % len(missing))
    for k in missing[:40]:
        print("      · %s   [%s]" % (k, used[k]))
    if len(missing) > 40:
        print("      … 还有 %d 条" % (len(missing) - 40))
    print("    在 Lang.java 的 EN.put 区补上即可。")
    return 1


if __name__ == '__main__':
    if os.environ.get('TGAS_SKIP_UI18N') == '1':
        print("  （已按 TGAS_SKIP_UI18N=1 跳过界面文案检查）")
        sys.exit(0)
    sys.exit(main())
