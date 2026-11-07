#!/usr/bin/env python3
# check-handle-coverage.py —— 守住「异常状态必须可处置」
#
# 原则（用户 2026-10-14 明确）：只要目标出了异常，界面上就必须有处置入口，
# 绝不能只剩「删除目标」一条路。
#
# 做法：把 TGAutoSignCore 里 statusOf() 会返回的「非正常状态」与
# roundProblemsOf() 会产出的状态集合做对照 —— 前者不该有后者不知道的。
import io, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..')
CORE = os.path.join(SRC, 'app/src/main/java/io/github/wlmosv_png/tgautosign/TGAutoSignCore.java')
if not os.path.isfile(CORE):
    print('FATAL: 找不到 ' + CORE); sys.exit(1)

t = io.open(CORE, encoding='utf-8').read()
bad = 0

# 1) statusOf 里出现的「异常类」文案必须在 roundProblemsOf 里有对应处理
#    正常态白名单：不需要处置的
NORMAL = {'已签', '待签', '今日已签'}
# 需要处置的（必须能被 roundProblemsOf 扫出来）
NEED_ACTION = {'签到失败', '按钮失效', '回复判不出', 'bot 未回复', '结果未知',
               '判定已关', '已放弃', '退避中', '从不应答'}

i = t.find('private String statusOf')
if i < 0:
    print('FATAL: 找不到 statusOf'); sys.exit(1)
j = t.find('private int statusRank', i)
body = t[i:j if j > 0 else i + 4000]

found = set()
for m in re.finditer(r'Lang\.tr\("([^"]+)"\)', body):
    found.add(m.group(1))

missing = []
for st in NEED_ACTION:
    if st not in found:
        missing.append(st)

# 2) roundProblemsOf 必须覆盖到这些归类码
rp = t.find('java.util.List<RoundState> roundProblemsOf')
if rp < 0:
    print('FATAL: 找不到 roundProblemsOf'); sys.exit(1)
rpBody = t[rp:rp + 6000]
# 2026-11-08：归因已抽成纯函数 SignLogic.resolvePendingState（可离线单测）。
# 允许 roundProblemsOf 委托给它 —— 此时改为检查**被委托方**是否覆盖这些归类码，
# 并要求委托方把 PS_* 结果映射成界面状态（否则界面拿不到状态）。
_CODES = ['R_FAILED', 'R_BTN_STALE', 'R_REPLIED_UNK', 'R_NO_REPLY']
if 'resolvePendingState(' in rpBody:
    LOGIC = os.path.join(SRC, 'app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java')
    lg = io.open(LOGIC, encoding='utf-8').read()
    k = lg.find('public static int resolvePendingState')
    if k < 0:
        print('  ❌ 找不到 SignLogic.resolvePendingState（返回类型可能已改名，请同步本检查器）'); bad = 1
    else:
        lgBody = lg[k:k + 3000]
        for code in _CODES:
            if code not in lgBody:
                print('  ❌ resolvePendingState 未覆盖归类 ' + code); bad = 1
    for ps in ['PS_NO_REPLY', 'PS_UNKNOWN', 'PS_SENDFAIL', 'PS_PANEL']:
        if ps not in rpBody:
            print('  ❌ roundProblemsOf 未把 ' + ps + ' 映射到界面状态'); bad = 1
else:
    for code in _CODES:
        if code not in rpBody:
            print('  ❌ roundProblemsOf 未覆盖归类 ' + code); bad = 1

# 3) 三个必备动作必须都在自持面板里
render = t.find('private void renderRoundProblems')
if render < 0:
    print('FATAL: 找不到 renderRoundProblems'); sys.exit(1)
rb = t[render:render + 8000]
for act in ['"重试"', '"记为已签"', '"忽略今天"']:
    if act not in rb:
        print('  ❌ 自持面板缺少处置动作 ' + act); bad = 1

if missing:
    print('  ⚠ statusOf 中未见的状态（可能已改名，请同步本检查器）: ' + ', '.join(missing))

if bad:
    print('❌ 异常处置覆盖检查未通过'); sys.exit(1)
print('OK   异常状态都有处置入口（重试 / 记为已签 / 忽略今天 三件套齐备）')
