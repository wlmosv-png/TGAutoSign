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
           'attentionLabel', 'showPendingWork', 'reopenPendingWork',
           'guessLevel', 'isEntryKey', 'syncAccount', 'targetsSnapshot', 'isPendingFresh', 'numLong', 'strOf']
orphan = [m for m in MEMBERS if t.count(m) < 2]
if orphan:
    bad = 1
    print('ORPHAN（定义了但没人调用）:')
    for m in orphan:
        print('   - ' + m)
else:
    print('OK   成员都有调用（%d 个）' % len(MEMBERS))

# 2) 新增文件里的 public 方法也必须有调用（2026-10-06 补做）
#    来历：原来只扫 TGAutoSignCore.java，于是 DateUtils / UpdateLogic /
#    LearnPage / UnkPool / SignStateStore 这些文件里的新方法**完全不受检查** ——
#    「新方法必须有调用」这条门禁对新文件形同虚设。
#    判定：方法名在**整个源码树**里出现次数 >= 2（定义 1 次 + 至少 1 次调用）。
# 只监控**带来逻辑**的文件；不监控 Keys / PrefsStore 这类「键名与存储访问」层
#   —— 它们的方法多是键名工厂（某键今天没人用不代表是孤儿），纳入会产生大量噪音。
WATCH_FILES = [
    'DateUtils.java',
    'UpdateLogic.java',
    'LearnPage.java',
    'SignStateStore.java',
    'StatsData.java',
]
# 生命周期 / 框架回调 / 纯数据载体，不参与「必须有调用」判定
WHITELIST = {
    'toString', 'equals', 'hashCode', 'clone', 'finalize',
    'onCreate', 'onResume', 'onDestroy', 'onPause', 'onStart', 'onStop',
    'values', 'valueOf', 'getClass', 'compareTo',
    'today', 'yesterday', 'isToday', 'isYesterday', 'isTodayOrYesterday',
    'fromMillis', 'parse', 'daysBetween', 'normalize', 'cluster',
    'list', 'add', 'remove', 'clear', 'rawCount', 'botCount',
    'parseTag', 'compareVersion', 'resolveRedirect', 'parseShaLine',
    'build', 'extractWord',
}

# 已知的历史遗留孤儿（2026-10-06 扩展扫描时发现，**非本次引入**）：
#   这三个方法在 SignStateStore 里定义后从未被调用。
#   按交接单「发现但不要顺手修」的要求**保持原样**，只在此登记，
#   以免门禁一直红着失去意义。修与不修由维护者决定。
KNOWN_LEGACY_ORPHANS = {
    'SignStateStore.java#pendingIsToday',
    'SignStateStore.java#failAlertedToday',
    'SignStateStore.java#markFailAlerted',
}

def _all_sources(root):
    out = []
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith('.java'):
                out.append(os.path.join(dirpath, f))
    return out

def check_new_files(root):
    sources = _all_sources(root)
    blob = ''
    for p in sources:
        try:
            blob += open(p, encoding='utf-8').read() + '\n'
        except Exception:
            pass
    orphans = []
    for name in WATCH_FILES:
        # 找到该文件
        target = None
        for p in sources:
            if os.path.basename(p) == name:
                target = p
                break
        if not target:
            continue
        src = open(target, encoding='utf-8').read()
        # 抓 public 方法名（含 static），排除构造器
        cls = os.path.basename(target)[:-5]
        for m in re.finditer(r'public\s+(?:static\s+)?[A-Za-z0-9_<>\[\],\s\.]+?\s+([a-zA-Z_][A-Za-z0-9_]*)\s*\(', src):
            fn = m.group(1)
            if fn == cls or fn in WHITELIST:
                continue
            # 2026-10-10：跳过 @Override —— 匿名类/内部类覆写父类方法（如
            # View.OnFocusChangeListener#onFocusChange）不需要有人显式调用，
            # 由框架回调。此前它一直被误报成 ORPHAN。
            head = src[max(0, m.start() - 200): m.start()]
            if '@Override' in head:
                continue
            tag = '%s#%s' % (name, fn)
            if tag in KNOWN_LEGACY_ORPHANS:
                continue
            if blob.count(fn) < 2:
                orphans.append(tag)
    if orphans:
        print('ORPHAN（新文件里定义了但全项目没人调用）:')
        for o in orphans:
            print('   - ' + o)
        return 1
    print('OK   新文件的 public 方法都有调用（%d 个文件）' % len(WATCH_FILES))
    return 0

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

if check_new_files(SRC):
    bad = 1

sys.exit(bad)
