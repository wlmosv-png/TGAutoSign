#!/usr/bin/env python3
"""打包前的接线自检：新增的方法/菜单入口真的被用上了吗。

  ./check-wiring.py [源码目录]        默认 /data/local/tmp/tgas
退出码 1 = 有孤儿方法或有菜单项没有派发分支（别急着打包）。

来历：v1.4.1 增补时「复制目标到其它账号」的界面方法写好了、菜单项也插了，
但 runAction 里漏了派发分支；dex 字符串自检全部命中，装上却看不到入口。
只看"字符串在不在"是不够的，必须查"有没有人调用"。
"""
import os
import re
import sys

SRC = sys.argv[1] if len(sys.argv) > 1 else '/data/local/tmp/tgas'
CORE = os.path.join(SRC, 'app/src/main/java/io/github/wlmosv_png/tgautosign/TGAutoSignCore.java')
if not os.path.isfile(CORE):
    sys.exit('FATAL: 找不到 ' + CORE)

t = open(CORE, encoding='utf-8').read()
bad = 0

# 1) 本版本新增/改动的成员：定义之外还必须有人调用
MEMBERS = ['showCopyTargets', 'copyTargetsTo', 'perAccountLine', 'acctTargetCount', 'accountsWithoutTargets',
           'showImportPicker', 'showImportMode', 'doImportNow', 'migrateLegacyKeys', 'bootToast',
           'toastDaily', 'toastOnce', 'noteResult', 'flushRoundToast', 'peerFromDialogs', 'countSoftFail',
           'isChatOrChannel', 'accountLabel', 'mergedLog', 'logChip', 'logRow',
           'refreshLog', 'copyToClip', 'confirmClearLog', 'clearLogNow', 'appendDisk', 'parseLogLine',
           'guessLevel', 'isEntryKey', 'syncAccount', 'targetsSnapshot', 'isPendingFresh', 'numLong', 'strOf']
orphan = [m for m in MEMBERS if t.count(m) < 2]
if orphan:
    bad = 1
    print('ORPHAN（定义了但没人调用）:')
    for m in orphan:
        print('   - ' + m)
else:
    print('OK   成员都有调用（%d 个）' % len(MEMBERS))

# 2) 菜单项 action 必须有对应派发分支
acts = set(re.findall(r'menuItem\((?:root|box|menu)[^;]*?"([a-z_]+)"\);', t))
disp = set(re.findall(r'if \("([a-z_]+)"\.equals\(action\)\)', t))
missing = sorted(a for a in acts if a not in disp)
if missing:
    bad = 1
    print('菜单项没有派发分支（点了没反应）:', missing)
else:
    print('OK   %d 个菜单 action 全部有派发' % len(acts))

# 3) 每条菜单都必须有非空说明文字
if re.search(r'menuItem\([^;]*?, ""\);', t):
    bad = 1
    print('WARN 有菜单项说明文字为空')

# 4) 反射字符串别散落字面量（便于宿主适配统一改）
lit = len(re.findall(r'"org\.telegram\.[A-Za-z.$]+"', t))
print('INFO 反射类名字面量 %d 处（新增宿主适配时记得一起查）' % lit)

sys.exit(bad)
