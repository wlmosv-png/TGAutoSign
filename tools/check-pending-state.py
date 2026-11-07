#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""待处理状态机门禁（2026-11-08 P0/P1 修复配套）。

为什么有这道门
--------------
「已回复的 bot 却显示未回复」这次事故的根因不是某个函数写错，而是**归因方式错了**：
界面用「距发送超过 90 秒」推导出「未回复」。这种错误不会崩、不会报错、日志里也看不出，
只能靠静态检查把它钉死。所以这里把"必须这样写"和"绝不许那样写"都变成可执行断言。

判定项
------
  P1  roundProblemsOf 必须走纯函数归因 SignLogic.resolvePendingState
  P2  roundProblemsOf 里不得再用"90 秒 + SENT_STALE → 直接判未回复"的时间推导
  P3  "未回复"的写入必须过证据门 SignLogic.mayMarkNoReply
  P4  两个重试入口都必须调用 stateStore.resetRoundStateForRetry
  P5  重试路径不得用 currentAccount() 取账号（必须 accountOfPrefix）
  P6  markSigned 必须把「写 last_」与「清在途」放进同一事务（不得再单独调 clearPostSignState）
  P7  证据等级 API 必须存在且被使用（noteEvidence / evidenceGrade）
  P8  resultOf 必须做轮次隔离（读 result_round_）

跳过：TGAS_SKIP_PENDINGGATE=1
"""
import io
import os
import re
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
CORE = os.path.join(BASE, 'app/src/main/java/io/github/wlmosv_png/tgautosign/TGAutoSignCore.java')
STORE = os.path.join(BASE, 'app/src/main/java/io/github/wlmosv_png/tgautosign/SignStateStore.java')
LOGIC = os.path.join(BASE, 'app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java')


def read(p):
    return io.open(p, encoding='utf-8').read()


def main():
    core, store, logic = read(CORE), read(STORE), read(LOGIC)
    bad = []

    # P1 归因必须走纯函数
    if 'SignLogic.resolvePendingState(' not in core:
        bad.append("P1 roundProblemsOf 未走 SignLogic.resolvePendingState（归因必须集中在一个可测纯函数里）")

    # P2 禁止时间推导直接判「未回复」
    for m in re.finditer(r'SENT_STALE', core):
        seg = core[max(0, m.start() - 260): m.start() + 260]
        if 'RS_NO_REPLY' in seg:
            bad.append("P2 仍有「SENT_STALE → RS_NO_REPLY」的时间推导（行偏移 %d）："
                       "超时不等于未回复" % m.start())

    # P3 「未回复」写入必须过证据门
    if 'SignLogic.mayMarkNoReply(' not in store:
        bad.append("P3 SignStateStore 未接入证据门 SignLogic.mayMarkNoReply")
    if 'SignLogic.mayMarkNoReply(' not in core:
        bad.append("P3 TGAutoSignCore 的超时分支未接入证据门 mayMarkNoReply")

    # P4 两个重试入口统一
    n_reset = core.count('stateStore.resetRoundStateForRetry(')
    if n_reset < 2:
        bad.append("P4 只有 %d 处调用 resetRoundStateForRetry（两个重试入口都要用同一套）" % n_reset)

    # P5 重试入口的 sendSign 必须用 accountOfPrefix（禁止 currentAccount 串号）。
    #    只检查"重试上下文"——项目其它地方（手动签到/全部账号）用 currentAccount 是合法的。
    for m in re.finditer(r'resetRoundStateForRetry\(', core):
        seg = core[m.end(): m.end() + 1500]
        sm = re.search(r'sendSign\([^;]*;', seg)
        if sm and 'accountOfPrefix' not in sm.group(0):
            bad.append("P5 重试入口用 currentAccount/其它方式取账号（会串号），应改 accountOfPrefix（偏移 %d）"
                       % (m.start() + m.end().real if hasattr(m.end(), 'real') else m.start()))

    # P6 markSigned 原子化
    if 'clearPostSignKeys(' not in store:
        bad.append("P6 缺少 clearPostSignKeys（原子清理键集）")
    seg = store[store.index('public boolean markSigned(String prefix, String id, String reason)'):]
    seg = seg[:seg.index('\n    /**')]
    if 'clearPostSignState(prefix, id)' in seg:
        bad.append("P6 markSigned 仍单独调用 clearPostSignState（两次事务 → 可能出现「已签+待确认」撕裂）")
    if 'clearPostSignKeys(ed' not in seg:
        bad.append("P6 markSigned 未在同一事务内清键")

    # P7 证据 API 被使用
    for api in ('noteEvidence(', 'evidenceGrade('):
        if api not in store:
            bad.append("P7 SignStateStore 缺少证据 API %s" % api)
    if 'noteEvidence(' not in core:
        bad.append("P7 核心链未记录任何回复证据（noteEvidence）")

    # P8 resultOf 轮次隔离
    if 'Keys.resultRound(' not in store:
        bad.append("P8 resultOf 未做轮次隔离（读 result_round_），旧轮结论会污染新轮")

    print('  待处理状态机门禁：')
    if bad:
        for b in bad:
            print('    FATAL %s' % b)
        return 1
    print('    OK  P1 归因走纯函数 / P2 无时间推导判未回复 / P3 证据门已接入')
    print('    OK  P4 重试入口统一 / P5 无 currentAccount 串号 / P6 markSigned 原子')
    print('    OK  P7 证据分级已落盘 / P8 轮次隔离已启用')
    return 0


if __name__ == '__main__':
    sys.exit(main())
