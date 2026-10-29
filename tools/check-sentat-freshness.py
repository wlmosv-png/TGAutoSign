#!/usr/bin/env python3
# check-sentat-freshness.py
#
# 守住「sent_at_ 必须带今天语义」这条不变式（2026-10-16 事故）。
#
# 事故回顾：roundProblemsOf 里写
#     long sent = prefs.getLong(prefix + "sent_at_" + id, 0L);
#     if (sent > 0 && now - sent > 90_000L) { 判未回复 }
#   —— 只判「够不够久」，没判「是不是今天」。
#   sent_at_ 是**裸毫秒、没有日期语义**，跨天不清；
#   于是几天前的残留会在第二天被无条件判成「未回复」，
#   用户看到「全部目标待处理 / 已发出 4999 分钟」，而当天压根没发过。
#
# 本检查器：扫描所有读 sent_at_ 的位置，要求同一作用域内出现「今天」判据。
#   认可的判据（任一）：
#     · SignLogic.sentPhase(sentToday, ...)      —— 首选，语义最清楚
#     · stateStore.isOptimisticToday(...)        —— opt_ 带日期，天然按天
#     · today.equals(...opt...)                  —— 手写但正确
#     · 与 PENDING_TTL_MS（≤30 分钟）比较        —— 短窗口，跨天不影响
#
# 用法: python3 tools/check-sentat-freshness.py <源码目录>
import io, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..")
CORE = os.path.join(SRC, "app/src/main/java/io/github/wlmosv_png/tgautosign/TGAutoSignCore.java")
if not os.path.isfile(CORE):
    print("FATAL: 找不到 " + CORE)
    sys.exit(1)

lines = io.open(CORE, encoding="utf-8").read().split("\n")

# 短窗口（≤30 分钟）不算跨天风险：这类常量名一并认作安全
SAFE_WINDOW = ("PENDING_TTL_MS", "PANEL_WAIT_MS", "PANEL_TOTAL_MS")
OK_MARKERS = ("sentPhase(", "isOptimisticToday(", "sentToday", "opt_", "SENT_STALE",
              "today.equals(", "todayStr().equals(", "msToYmd(", "isTodayMs(")
# 纯日志/纯展示的位置：读它只为打印"发出于 N 秒前"，不影响任何判定
LOG_ONLY_MARKERS = ("发出于", "age \/ 60000L")

bad = []
for i, l in enumerate(lines):
    if "sent_at_" not in l or "getLong" not in l:
        continue
    # 取本行 + 上下 12 行为窗口（判定通常紧邻）
    lo = max(0, i - 12)
    hi = min(len(lines), i + 13)
    ctx = "\n".join(lines[lo:hi])
    if any(m in ctx for m in OK_MARKERS):
        continue
    # 纯日志行（只为打印"发出于 N 秒前"）不参与判定，不要求日期语义
    if any(m in ctx for m in LOG_ONLY_MARKERS):
        continue
    if any(w in ctx for w in SAFE_WINDOW):
        continue
    bad.append(i + 1)

if bad:
    print("  ❌ 以下位置读 sent_at_ 但没有「今天」判据（跨天残留会被误判）:")
    for ln in bad:
        print("     L%d: %s" % (ln, lines[ln - 1].strip()[:100]))
    print("")
    print("  修法：用 SignLogic.sentPhase(sentToday, sent, now, ttl) 或")
    print("        stateStore.isOptimisticToday(prefix, id) 判定「今天是否发过」；")
    print("        仅当今天发过且已超时，才可判成「未回复 / 未结案」。")
    sys.exit(1)

print("OK   sent_at_ 的每处读取都带了「今天」判据（或落在 ≤30 分钟短窗口内）")
