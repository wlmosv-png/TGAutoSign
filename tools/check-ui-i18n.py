#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
界面文案 i18n 门禁：中文文案必须有英文映射。

为什么有这道门（2026-09-30 定，2026-10-07 加强）：
  日志改造一口气新增 40 多条用户可见文案，全部漏了英文映射 ——
  英文设备上会直接显示中文。

  ⚠️ 2026-10-07 加强：旧版只扫 TGAutoSignCore / TGAutoSignEntry /
  SettingsRefs 三个文件里的 Lang.tr()/Lang.tf() 调用。结果 LearnPage.java
  整页写的是**裸字符串**（setText("中文")、pill("中文")），门禁完全扫不到，
  一直是绿的 —— 实际上那一页英文设备 100% 显示中文。
  现在：
    1) 扫**全部 .java**
    2) 除了 Lang.tr/tf 的字面量，再扫「UI 调用里的裸中文」
       （setText / setHint / setTitle / pill(...) / toast(...) / sectionHeader 最后一参）
    3) 排除算法词表与注释

退出码 1 = 有缺失。跳过：TGAS_SKIP_UI18N=1
"""
import io
import os
import re
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
JAVA_DIR = os.path.join(BASE, 'app/src/main/java/io/github/wlmosv_png/tgautosign')

# 不需要翻译的
WHITELIST = {
    "今",
}

CJK = re.compile(r'[\u4e00-\u9fff]')

# UI 调用：这些括号里的中文字面量必须走 Lang
UI_CALL = re.compile(
    r'(?:setText|setHint|setTitle|pill|primaryButton|toast|sectionHeader|setHintTextColor)'
    r'\s*\(([^;]{0,400}?)\)\s*;',
    re.S)

LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')


def read(p):
    with io.open(p, encoding='utf-8') as f:
        return f.read()


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def main():
    lang_p = os.path.join(JAVA_DIR, 'Lang.java')
    if not os.path.isfile(lang_p):
        print("FATAL: 找不到 Lang.java")
        return 1
    lang = read(lang_p)
    have = set(re.findall(r'EN\.put\(\s*"((?:[^"\\]|\\.)*)"', lang))
    have |= set(re.findall(r'EN_SHORT\.put\(\s*"((?:[^"\\]|\\.)*)"', lang))

    used = {}          # 中文串 -> 出现位置
    files = sorted(f for f in os.listdir(JAVA_DIR)
                   if f.endswith('.java') and f != 'Lang.java')

    for name in files:
        p = os.path.join(JAVA_DIR, name)
        raw = read(p)
        src = strip_comments(raw)

        # ① Lang.tr / Lang.tf 的字面量（可能跨行用 + 拼接）
        for m in re.finditer(r'Lang\.(?:tr|tf)\(\s*"((?:[^"\\]|\\.)*)"'
                             r'((?:\s*\+\s*"(?:[^"\\]|\\.)*")*)', src, re.S):
            parts = [m.group(1)] + re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2) or "")
            s = "".join(parts)
            if CJK.search(s) and s not in WHITELIST:
                used.setdefault(s, name + ":Lang")

        # ② UI 调用里的裸中文（没被 Lang 包住的）
        for cm in UI_CALL.finditer(src):
            args = cm.group(1)
            # 整条调用里已经有 Lang.tr/tf 的，说明作者已处理（可能是跨行拼接，
            # 单看某个字面量的前文看不到 Lang），整条跳过，避免误报。
            if 'Lang.tr(' in args or 'Lang.tf(' in args:
                continue
            for lm in LIT.finditer(args):
                lit = lm.group(1)
                if not CJK.search(lit):
                    continue
                if lit in WHITELIST:
                    continue
                pre = args[max(0, lm.start() - 10):lm.start()]
                if 'Lang.tr(' in pre or 'Lang.tf(' in pre:
                    continue
                # 算法词表：extractWord 里的中文常量列表，靠缩进+短词特征排除
                if lit in ('广告', '骗子', '请勿', '不要上', '谨防', '上当',
                           '欢迎使用', '欢迎来到', '我是', '接下来', '为您服务',
                           '点击下方', '点击上方', '菜单按钮', '会话超时', '工单反馈',
                           '未收录', '不消', '免责', '声明', '官方频道', '自动推送',
                           '命令列表', '使用说明', '帮助', '请先', '重新打开',
                           '签到成功', '打卡成功', '签到完成', '打卡完成',
                           '签到已完成', '打卡已完成', '完成签到', '完成打卡',
                           '已签到成功', '签到奖励', '获得奖励', '领取完成',
                           '已经签到', '已经打卡', '今日已签', '今日已签过',
                           '已签到', '已领取', '已获得', '已打卡',
                           '次数已用完', '今日次数', '已达上限', '请明日再试',
                           '明日再来', '签到', '打卡'):
                    continue
                used.setdefault(lit, name + ":raw")

    missing = sorted(k for k in used if k not in have)
    print("  界面文案双语检查：")
    print("    中文文案 %d 条 / 已映射 %d 条（扫描 %d 个 java 文件）"
          % (len(used), len(used) - len(missing), len(files)))
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
