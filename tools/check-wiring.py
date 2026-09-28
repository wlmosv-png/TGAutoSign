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
           # 1.6.3：执行结果归类 + 待处理聚合条（新增即须有人调用）
           'markResultCode', 'resultCodeOf', 'countNeedsAttention', 'attentionHint',
           'attentionLabel', 'showPendingWork',
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

# 3.5) 账号遍历必须用真实槽位（accountSlots），不能用连续区间
#
# 来历（2026-09-28 用户反馈）：部分客户端（Nagram 实测）账号槽位**不连续**，
# selectedAccount / SharedConfig.activeAccounts 会给出 7、9 这类真实索引。
# 用 `for (i = 0; i < activatedAccounts(); i++)` 遍历时：
#   - 槽位 {0,1,7} → 只访问 0,1,2，真账号3（槽位7）压根读不到 → 界面显示「没有目标」
#   - 更糟的是 sweepOrphanEntryKeys 会把它当「孤儿」删状态键
# 正确写法：for (int i : accountSlots()) { ... }
ACC_LOOP = re.compile(
    r'for\s*\(\s*int\s+\w+\s*=\s*0\s*;\s*\w+\s*<\s*(?:Math\.max\(1,\s*)?activatedAccounts\(\)'
)
acc_bad = []
for m in ACC_LOOP.finditer(t):
    # 取该循环后 600 字符，看是否真的在按账号取前缀/读目标
    seg = t[m.start():m.start() + 600]
    if 'accountPrefix(' in seg or 'loadTargetsInto(' in seg or 'acctTargetCount(' in seg:
        line = t[:m.start()].count('\n') + 1
        acc_bad.append(line)
if acc_bad:
    bad = 1
    print('账号遍历用了连续区间（槽位不连续时会漏账号 / 误删状态）:')
    for ln in acc_bad:
        print('   - 第 %d 行：应改为 for (int i : accountSlots())' % ln)
else:
    print('OK   账号遍历都走 accountSlots（真实槽位）')

# 4) 反射字符串别散落字面量（便于宿主适配统一改）
lit = len(re.findall(r'"org\.telegram\.[A-Za-z.$]+"', t))
print('INFO 反射类名字面量 %d 处（新增宿主适配时记得一起查）' % lit)

sys.exit(bad)
