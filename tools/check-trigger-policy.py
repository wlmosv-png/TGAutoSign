#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：自动签到触发链是否被静默吞掉（2026-10-30 新增，事故驱动）。

事故
----
用户反馈「我上线它不签到，只能打开面板手动一键签」。逐条排查出：

  · timerHook 非定时模式 + 「打开 TG 时补签」开 + 启动触发时，
    走的是 `if (CATCHUP_ON_OPEN && isStartupTrigger(reason)) { … kickSchedule(); return; }`
    —— 而 sweepDue/scheduleTimerPlan 首行都是 `if (!TIMER_ENABLED) return;`，
    非定时模式必然空转；随后 return 又把本该走的 enqueueTry 一起绕掉。
    日志实锤：2026-10-28 04:54:09 / 05:00:13 两次打印
    「打开时补签（非定时模式）：有 6 个未签目标，立即执行一次（启动）」，
    之后没有任何发送记录 —— 文案说「立即执行」，实际一个字节都没发。

  · trySignAllFor 在定时模式下 `if (TIMER_ENABLED && !force) { kickSchedule(); return 0; }`
    完全不留日志 → 打开 TG / 心跳 / 网络恢复全部静默。
    日志对比：账号 1 定时关时 10-25 有 17 条 [打开TG] 摘要，转定时后 0 条。

  · trySignAllFor 的 `if (!force && !inWindow())` 用 logd + return 丢弃窗口外触发，
    无排队、无补签、无落盘 → 用户 10-28 03:32、10-30 00:46 落在死区时
    模块既不动也不记录，只能手动。

  · scheduleTickLoop 用**单一** TIMER_ENABLED 判定**所有账号**，
    而 cfg 是每账号独立的（acc0 定时开、acc3 定时关，窗口也各不相同）。

  · enqueueTry 的 90 秒去重是全局 volatile long，不带账号维度，
    账号 A 的触发会吞掉账号 B 的（心跳路径却有按账号的 markEnqueueFor）。

规则
----
G1 timerHook 内「直接层级」调用 kickSchedule() 的块，必须同时有
   enqueueTry( 或 requestAutoSign(（否则是非定时模式下的空转分支）。
G2 trySignAllFor(skipSigned) 的 `TIMER_ENABLED && !force` 块内必须有日志。
G3 trySignAllFor(skipSigned) 的 `!force && !inWindow()` 块必须走 decideTrigger(
   或落盘日志，不得只有 logd + return。
G4 timerHook 内日志文案含「立即执行」时，其所在分支块内必须有真实执行调用。
G5 scheduleTickLoop 内不得出现裸 `if (TIMER_ENABLED)` 分支（须逐账号判定）。
G6 enqueueTry 的去重键必须带账号维度（不得只用全局 enqueueAt）。
G7 requestAutoSign 的同一分支不得双重去重（markEnqueueFor 紧跟 enqueueTryFor）。
G8 armTask 执行体必须按任务持有的账号 Ctx 判定（不得读全局 TIMER_ENABLED/inWindow()）。

反假阳性
--------
· 剥注释时保留换行数量，行号与原文一致。
· 「调用」类断言在再剥字符串字面量后的文本上做 —— 日志文案里写
  "kickSchedule()" 不会误判成真调用。
· 「文案」类断言只在剥注释后的原文上做，括号配平取整条语句（兼容拼接）。
· 方法体用大括号配平抽取，不依赖缩进 / 换行 / 空格 / 注释。
· 重载方法（trySignAllFor 有 3 参 / 4 参两版）按参数表挑，不误取转发版。
· G1 只看「直接层级」（剔除嵌套块），避免父子块重复报同一条。
· 文件或方法找不到 → FATAL 直接失败，不静默通过。
· 自带 `--selftest`：用合成片段验证上述反假阳性，全部通过才建议接入门禁。

退出码 1 = 有违规。跳过：TGAS_SKIP_TRIGGER=1  指定文件：TGAS_CORE=/path/to.java
"""
import os
import re
import sys
import tempfile

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
DEFAULT_CORE = os.path.join(BASE, 'app', 'src', 'main', 'java', 'io', 'github',
                            'wlmosv_png', 'tgautosign', 'TGAutoSignCore.java')


# ───────────────────────── 文本预处理 ─────────────────────────

def strip_comments(src):
    """去掉 // 与 /* */ 注释；块注释按原换行数补回，保证行号不变。"""
    out = []
    i, n, st = 0, len(src), None
    while i < n:
        c = src[i]
        if st is None:
            if c == '"' or c == "'":
                st = c
                out.append(c)
                i += 1
                continue
            if c == '/' and i + 1 < n and src[i + 1] == '/':
                while i < n and src[i] != '\n':
                    i += 1
                continue
            if c == '/' and i + 1 < n and src[i + 1] == '*':
                i += 2
                nl = 0
                while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                    if src[i] == '\n':
                        nl += 1
                    i += 1
                i += 2
                out.append('\n' * nl)
                continue
            out.append(c)
            i += 1
        else:
            if c == '\\' and i + 1 < n:
                out.append(c)
                out.append(src[i + 1])
                i += 2
                continue
            if c == st:
                st = None
            out.append(c)
            i += 1
    return ''.join(out)


def strip_strings(src):
    """把字符串/字符字面量换成空串（不增删换行），用于判断"真调用"。"""
    out = []
    i, n, st = 0, len(src), None
    while i < n:
        c = src[i]
        if st is None:
            if c == '"' or c == "'":
                st = c
                out.append('""')
                i += 1
                continue
            out.append(c)
            i += 1
        else:
            if c == '\\':
                i += 2
                continue
            if c == st:
                st = None
            i += 1
    return ''.join(out)


# ───────────────────────── 结构抽取 ─────────────────────────

def match_paren(src, open_idx):
    depth = 0
    k = open_idx
    while k < len(src):
        if src[k] == '(':
            depth += 1
        elif src[k] == ')':
            depth -= 1
            if depth == 0:
                return k
        k += 1
    return -1


def match_brace(src, open_idx):
    depth = 0
    for i in range(open_idx, len(src)):
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                return i
    return -1


def line_of(src, idx):
    return src.count('\n', 0, idx) + 1


def all_method_bodies(src, name, param_hint=None):
    """返回 [(body, decl_line)]；param_hint 为正则时要求参数表匹配（区分重载）。"""
    res = []
    for m in re.finditer(r'\b' + re.escape(name) + r'\s*\(', src):
        ls = src.rfind('\n', 0, m.start()) + 1
        prefix = src[ls:m.start()]
        if re.search(r'[=;{(]', prefix):
            continue
        if not re.match(r'^[ \t]*(?:@\w+[ \t]*)*[\w<>\[\],\.\s]*$', prefix):
            continue
        close = match_paren(src, m.end() - 1)
        if close < 0:
            continue
        if param_hint and not re.search(param_hint, src[m.end():close]):
            continue
        rest = re.match(r'\s*(?:throws[^{;]*)?\{', src[close + 1:])
        if not rest:
            continue
        brace = close + 1 + rest.end() - 1
        end = match_brace(src, brace)
        if end < 0:
            continue
        res.append((src[brace:end + 1], line_of(src, m.start())))
    return res


def iter_if_blocks(body, base=0):
    """产出 body 内所有 if 块的 (cond, block, **绝对偏移**)。

    同一层里跳过落在本层已产出块内部的 if（否则嵌套 if 会被上层 finditer
    重复扫到）；offset 一律相对最外层 body，供行号计算使用。
    """
    local = []
    for m in re.finditer(r'\bif\s*\(', body):
        if any(a <= m.start() < b for a, b in local):
            continue
        close = match_paren(body, m.end() - 1)
        if close < 0:
            continue
        cond = body[m.end():close]
        r = re.match(r'\s*\{', body[close + 1:])
        if not r:
            continue
        brace = close + 1 + r.end() - 1
        end = match_brace(body, brace)
        if end < 0:
            continue
        blk = body[brace:end + 1]
        local.append((m.start(), end + 1))
        yield cond, blk, base + m.start()
        for sub in iter_if_blocks(blk, base + brace):
            yield sub


def strip_nested_blocks(blk):
    """把块内的 if(...) {...} 整体替换成 ';'，只留直接层级语句。"""
    out = blk
    guard = 0
    while guard < 500:
        guard += 1
        m = re.search(r'\bif\s*\(', out)
        if not m:
            break
        close = match_paren(out, m.end() - 1)
        if close < 0:
            break
        r = re.match(r'\s*\{', out[close + 1:])
        if not r:
            out = out[:m.start()] + ';' + out[m.end():]
            continue
        brace = close + 1 + r.end() - 1
        end = match_brace(out, brace)
        if end < 0:
            break
        out = out[:m.start()] + ';' + out[end + 1:]
    return out


def enclosing_block(body, off):
    """返回包含 off 的最内层 if 块；没有则返回整个 body。"""
    best = None
    for cond, blk, boff in iter_if_blocks(body):
        if boff <= off <= boff + len(blk):
            if best is None or len(blk) < len(best):
                best = blk
    return best if best is not None else body


def find_if_block(body, cond_pat):
    for cond, blk, off in iter_if_blocks(body):
        if re.search(cond_pat, cond):
            return cond, blk, off
    return None, None, -1


def has_call(text, name):
    return bool(re.search(r'(?<![\w.])' + re.escape(name) + r'\s*\(', text))


def log_statements(text):
    """产出 (整条 log 语句, 起点偏移)，括号配平，兼容字符串拼接。"""
    for m in re.finditer(r'\blog[wdsei]?\s*\(', text):
        close = match_paren(text, m.end() - 1)
        if close < 0:
            continue
        yield text[m.start():close + 1], m.start()


# ───────────────────────── 规则检查 ─────────────────────────

def check(core_path):
    """返回 (fails, notes)。"""
    fails, notes = [], []
    raw = open(core_path, encoding='utf-8').read()
    nc = strip_comments(raw)
    code = strip_strings(nc)
    if code.count('\n') != nc.count('\n'):
        return (['[FATAL] strip_strings 改变了行数'], [])

    def pick(src, name, hint=None):
        cands = all_method_bodies(src, name, hint)
        if not cands:
            return None, -1
        return max(cands, key=lambda c: len(c[0]))

    timer, timer_ln = pick(code, 'timerHook')
    signall, signall_ln = pick(code, 'trySignAllFor', r'skipSigned')
    tick, tick_ln = pick(code, 'scheduleTickLoop')
    enq, enq_ln = pick(code, 'enqueueTry')
    for nm, b in (('timerHook', timer), ('trySignAllFor(skipSigned)', signall),
                  ('scheduleTickLoop', tick), ('enqueueTry', enq)):
        if b is None:
            return (['[FATAL] 未找到方法 %s（识别逻辑需更新，不能静默通过）' % nm], [])
    timer_raw, timer_raw_ln = pick(nc, 'timerHook')

    def add(tag, msg, line):
        fails.append('[%s] FAIL:%d %s' % (tag, line, msg))

    # G1：直接层级调用 kickSchedule() 的块必须有排队入口
    for cond, blk, off in iter_if_blocks(timer):
        direct = strip_nested_blocks(blk)
        if not has_call(direct, 'kickSchedule'):
            continue
        if not (has_call(direct, 'enqueueTry') or has_call(direct, 'requestAutoSign')):
            add('G1', 'if(%s) 直接调用 kickSchedule() 却无 enqueueTry/requestAutoSign '
                      '→ 非定时模式必然空转且绕过排队入口' % cond.strip()[:60],
                timer_ln + timer[:off].count('\n'))

    # G4：文案含「立即执行」的分支块内必须有真实执行调用
    for stmt, off in log_statements(timer_raw):
        if '立即执行' not in stmt:
            continue
        blk = strip_strings(enclosing_block(timer_raw, off))
        if not (has_call(blk, 'enqueueTry') or has_call(blk, 'requestAutoSign')
                or has_call(blk, 'trySignAll')):
            add('G4', '日志文案含「立即执行」但所在分支块内无真实执行调用（文案与实现不符）',
                timer_raw_ln + timer_raw[:off].count('\n'))

    # G2：定时模式早退必须有日志。
    # 允许重构后的写法 timerOf(account)（按账号现读配置）；若分支整体不存在，
    # 必须是"已接入 decideTrigger"的合法重构 —— 否则算违规，不得静默 NOT。
    cond2, blk2, off2 = find_if_block(
        signall, r'(?:TIMER_ENABLED|timerOf\s*\(\s*account\s*\))\s*&&\s*!\s*force')
    if blk2 is None:
        if has_call(signall, 'decideTrigger'):
            notes.append('[G2] 无显式「定时模式早退」分支；trySignAllFor 已接入 decideTrigger，本项不适用')
        else:
            add('G2', 'trySignAllFor 既无「定时模式早退」分支、也未接入 decideTrigger '
                      '→ 结构被改动却无替代，不能视为通过', signall_ln)
    elif not re.search(r'\blog[wdsei]?\s*\(', blk2):
        add('G2', '定时模式早退没有任何日志 → 打开TG/心跳/网络恢复被静默吞掉',
            signall_ln + signall[:off2].count('\n'))

    # G3：窗口外不得只有 logd+return（兼容 inWindow / inWindowOf(account) 两种写法）
    cond3, blk3, off3 = find_if_block(signall, r'!\s*force\s*&&\s*!\s*inWindow')
    if blk3 is None:
        if has_call(signall, 'decideTrigger'):
            notes.append('[G3] 无显式「窗口外」分支；trySignAllFor 已接入 decideTrigger，本项不适用')
        else:
            add('G3', 'trySignAllFor 既无「窗口外」分支、也未接入 decideTrigger '
                      '→ 不能视为通过', signall_ln)
    elif not (re.search(r'\blog[ws]\s*\(', blk3) or has_call(blk3, 'decideTrigger')):
        add('G3', '窗口外分支只有 logd+return → 窗口外触发被丢弃，无排队/无补签/无落盘',
            signall_ln + signall[:off3].count('\n'))

    # G5：不得用裸 TIMER_ENABLED 做全局分支
    for cond, blk, off in iter_if_blocks(tick):
        if re.match(r'^\s*TIMER_ENABLED\s*$', cond):
            add('G5', 'scheduleTickLoop 用单一 TIMER_ENABLED 分支判定所有账号；'
                      'cfg 是每账号独立的，须遍历 accountSlots() 逐账号判定',
                tick_ln + tick[:off].count('\n'))

    # G6：去重键必须带账号维度。
    # P3（2026-10-30）：必须检查**真正的实现体** enqueueTryFor —— 只查一行包装
    # enqueueTry 会在"实现体被塞进全局去重"时漏报（已用定向注入证实）。
    _g6_hit = False
    for _nm in ('enqueueTryFor', 'enqueueTry'):
        _b, _ln = pick(code, _nm)
        if _b is None:
            continue
        # 判据：方法体内**不得出现**全局 enqueueAt（正确实现只应使用账号维度的
        # markEnqueueFor / enqueueAtByAcc）。注意不能把"含 markEnqueueFor"当作豁免 ——
        # 实现体天然含它，那会让本项永不命中（P3 的 G6b 注入已证实）。
        if re.search(r'(?<![\w.])enqueueAt(?![\w])', _b):
            add('G6', _nm + ' 的 90 秒去重用全局 enqueueAt（不带账号）→ '
                      '账号 A 的触发会吞掉账号 B 的', _ln)
            _g6_hit = True
            break
    if not _g6_hit and pick(code, 'enqueueTryFor')[0] is None and pick(code, 'enqueueTry')[0] is None:
        notes.append('[G6] 未找到 enqueueTryFor / enqueueTry，本项不适用')

    # G7：统一出口不得「双重去重」——
    # requestAutoSign 若先 markEnqueueFor 再调 enqueueTryFor（后者内部也 markEnqueueFor），
    # 第二次必然返回 false → 任务永不排入，RUN_NOW 通道静默失效。
    # 判据按「分支块」而非「整方法」：T_RUN_LATE 分支用 markEnqueueFor 是合法的。
    req, req_ln = pick(code, 'requestAutoSign')
    if req is None:
        notes.append('[G7] 未找到 requestAutoSign（统一出口），本项不适用')
    else:
        # 按 switch 的 case/default 切段：分支内同时出现两者才算违规
        #（T_RUN_LATE 用 markEnqueueFor 是合法的，不能与 RUN_NOW 的 enqueueTryFor 混算）。
        segs = re.split(r'\n\s*(?:case\s+[^:\n]+:|default\s*:)', req)
        if len(segs) > 1:
            for seg in segs:
                if has_call(seg, 'markEnqueueFor') and has_call(seg, 'enqueueTryFor'):
                    add('G7', 'requestAutoSign 的同一分支内同时 markEnqueueFor 与 enqueueTryFor '
                              '→ 双重去重会让任务永不排入（RUN_NOW 静默失效）', req_ln)
                    break
        else:
            if has_call(req, 'markEnqueueFor') and has_call(req, 'enqueueTryFor'):
                add('G7', 'requestAutoSign 内同时 markEnqueueFor 与 enqueueTryFor、且无分支隔离 '
                          '→ 双重去重风险（RUN_NOW 可能静默失效）', req_ln)

    # G8：定时任务执行体必须用**任务持有的账号**判定。
    # P1：armTask 在排期时已锁定账号 Ctx，若执行时改读全局 TIMER_ENABLED / inWindow()，
    # 则「排任务 → 执行」之间一旦切换账号，A 的任务会被 B 的配置误杀（直接丢弃）。
    arm, arm_ln = pick(code, 'armTask')
    if arm is None:
        notes.append('[G8] 未找到 armTask，本项不适用')
    else:
        for cond, _blk, off in iter_if_blocks(arm):
            if re.search(r'(?<![\w.])TIMER_ENABLED(?![\w])', cond) \
                    or re.search(r'(?<![\w.])inWindow\s*\(\s*\)', cond):
                add('G8', 'armTask 执行体用全局 TIMER_ENABLED/inWindow() 判定（条件：%s）→ '
                          '切号后会把已锁定账号的任务误杀，须改用 ctx.account 读配置'
                    % cond.strip()[:60], arm_ln + arm[:off].count('\n'))
                break

    return fails, notes


# ───────────────────────── 自测（反假阳性） ─────────────────────────

BAD = '''public final class TGAutoSignCore {
    private boolean TIMER_ENABLED = false;
    private long enqueueAt = 0L;
    private void timerHook(String reason) {
        if (!TIMER_ENABLED) {
            if (CATCHUP_ON_OPEN && isStartupTrigger(reason)) {
                int pend = countUnsignedToday();
                if (pend > 0) {
                    logw("[定时] 打开时补签（非定时模式）：有 " + pend + " 个未签目标，立即执行一次（" + reason + "）");
                    kickSchedule();
                    return;
                }
                logd("打开时补签：今天都签完了，跳过");
                return;
            }
            enqueueTry(reason);
            return;
        }
        if (!inWindow() && !inMissBackTime()) { logd("窗口外，跳过"); return; }
        kickSchedule();
    }
    int trySignAllFor(String reason, boolean force, int account, boolean skipSigned) {
        if (TIMER_ENABLED && !force) {
            kickSchedule();
            return 0;
        }
        if (!force && !inWindow()) {
            logd("签到窗口外，跳过");
            return 0;
        }
        return 1;
    }
    private void scheduleTickLoop() {
        if (tickLoopOn) return;
        mainHandler.postDelayed(new Runnable(){
            public void run() {
                if (TIMER_ENABLED) {
                    kickSchedule();
                } else if (inWindow()) {
                    enqueueTry("心跳检测");
                }
            }
        }, 4000L);
    }
    public void enqueueTry(String reason) {
        synchronized (enqueueLock) {
            long nowE = System.currentTimeMillis();
            if (enqueueAt > 0L && nowE - enqueueAt < 90L * 1000L) { return; }
            enqueueAt = nowE;
        }
        mainHandler.postDelayed(() -> trySignAll(reason, false), 0L);
    }
}
'''

GOOD = '''public final class TGAutoSignCore {
    private void timerHook(String reason) {
        if (!TIMER_ENABLED) {
            if (CATCHUP_ON_OPEN && isStartupTrigger(reason)) {
                if (pendingToday()) {
                    requestAutoSign(reason, SignLogic.T_RUN_NOW);
                }
                return;
            }
            enqueueTry(reason);
            return;
        }
        requestAutoSign(reason, decideTriggerForCurrent());
    }
    int trySignAllFor(String reason, boolean force, int account, boolean skipSigned) {
        if (TIMER_ENABLED && !force) {
            logw("[定时] " + reason + " 转时刻表调度");
            kickSchedule();
            return 0;
        }
        if (!force && !inWindow() && decideTriggerForCurrent() == SignLogic.T_SKIP) {
            logw("[窗口外] " + reason + " 排队到下一个合法时段");
            return 0;
        }
        return 1;
    }
    private void scheduleTickLoop() {
        for (int acc : accountSlots()) {
            if (timerEnabledOf(acc)) kickScheduleOf(acc); else requestAutoSign("心跳", SignLogic.T_RUN_NOW);
        }
    }
    public void enqueueTry(String reason) { enqueueTryFor(currentAccount(), reason); }
    public void enqueueTryFor(final int acc, String reason) {
        if (!markEnqueueFor(acc)) return;
        mainHandler.postDelayed(() -> trySignAllFor(reason, false, acc, false), 0L);
    }
    private void armTask(Object m, long delayMs, boolean sweep, String tag, final int acc) {
        if (!timerOf(acc) || !inWindowOf(acc)) { kickSchedule(); return; }
        sendSign(m, acc);
    }
    private void requestAutoSign(int acc, String reason, int decision, Object ctx) {
        switch (decision) {
            case 3:
                logd("[\u89e6\u53d1] SKIP");
                return;
            case 2:
                logw("[\u89e6\u53d1] QUEUE");
                return;
            case 1:
                if (!markEnqueueFor(acc)) { return; }
                mainHandler.postDelayed(new Runnable() { public void run() { trySignAllFor(reason, false, acc, false); } }, 60000L);
                return;
            default:
                logw("[\u89e6\u53d1] RUN_NOW");
                enqueueTryFor(acc, reason);
                return;
        }
    }
}
'''


def _variants():
    """返回 [(名称, 源码, 期望的失败 tag 集合)]。"""
    v = []
    v.append(('bad-baseline', BAD, {'G1', 'G2', 'G3', 'G4', 'G5', 'G6'}))
    # 修复版：带注释示例代码 + 文案里写函数名 + 压缩格式，都不应误报
    noisy = GOOD.replace('public final class TGAutoSignCore {',
                         '/* 反例：不要写成 kickSchedule(); return;\n'
                         '   也不要 `if (!TIMER_ENABLED) { kickSchedule(); }` */\n'
                         'public final class TGAutoSignCore {')
    noisy = noisy.replace('logw("[定时] " + reason + " 转时刻表调度");',
                          'logw("[定时] " + reason + " 转时刻表调度（不是 kickSchedule() 空转）");')
    v.append(('good-with-noise', noisy, set()))
    # 格式变化：一条语句换行 + tab 缩进，判定不应改变
    tabs = GOOD.replace('\n    ', '\n\t').replace('if (!TIMER_ENABLED) {\n\t\t\tif',
                                                   'if (!TIMER_ENABLED)\n\t\t\t{\n\t\t\t\tif')
    v.append(('good-tabs-bracefmt', tabs, set()))
    # G4 单独反例：文案说「立即执行」但只有 kickSchedule（同时命中 G1，属预期）
    g4 = GOOD.replace('requestAutoSign(reason, SignLogic.T_RUN_NOW);',
                      'logw("有未签目标，立即执行一次"); kickSchedule();')
    v.append(('bad-G4-copy-mismatch', g4, {'G1', 'G4'}))
    # G1 单独反例：直接层级 kickSchedule 无排队入口（非定时模式）
    g1 = GOOD.replace('requestAutoSign(reason, SignLogic.T_RUN_NOW);', 'kickSchedule();')
    v.append(('bad-G1-stall', g1, {'G1'}))
    # G2/G3 保护：相关分支被整体删掉、且未接入 decideTrigger → 必须报违规，
    # 不得因为"匹配不到就静默 NOT"而假通过。
    nobranch = ('public final class TGAutoSignCore {\n'
                '    int trySignAllFor(String reason, boolean force, int account, boolean skipSigned) {\n'
                '        return 1;\n'
                '    }\n'
                '    private void timerHook(String reason) { }\n'
                '    private void scheduleTickLoop() { }\n'
                '    public void enqueueTry(String reason) { }\n'
                '}\n')
    v.append(('no-branch-no-decide', nobranch, {'G2', 'G3'}))
    # G7 反例：统一出口里双重去重（先 markEnqueueFor 再 enqueueTryFor）
    g7bad = GOOD.replace('''            default:
                logw("[\u89e6\u53d1] RUN_NOW");
                enqueueTryFor(acc, reason);''', '''            default:
                if (!markEnqueueFor(acc)) { return; }
                logw("[\u89e6\u53d1] RUN_NOW");
                enqueueTryFor(acc, reason);''')
    v.append(('bad-G7-double-dedupe', g7bad, {'G7'}))
    # G6 反例（P3）：往**实现体** enqueueTryFor 注入全局去重 —— 旧门禁只查包装，会漏报
    g6bad = GOOD.replace('''    public void enqueueTryFor(final int acc, String reason) {
        if (!markEnqueueFor(acc)) return;''', '''    public void enqueueTryFor(final int acc, String reason) {
        if (enqueueAt > 0L) return;
        if (!markEnqueueFor(acc)) return;''')
    v.append(('bad-G6b-实现体全局去重', g6bad, {'G6'}))
    # G8 反例（P1）：armTask 执行体退回全局判定
    g8bad = GOOD.replace('if (!timerOf(acc) || !inWindowOf(acc)) { kickSchedule(); return; }',
                         'if (!TIMER_ENABLED || (!inWindow() && !inMissBackTime())) { kickSchedule(); return; }')
    v.append(('bad-G8-armTask 全局判定', g8bad, {'G8'}))
    # 嵌套不应重复报：坏基线里 G1 只应报 1 条
    return v


def selftest():
    ok = True
    with tempfile.TemporaryDirectory() as td:
        for name, src, want in _variants():
            p = os.path.join(td, 'TGAutoSignCore.java')
            open(p, 'w', encoding='utf-8').write(src)
            fails, _notes = check(p)
            got = set(re.match(r'\[(\w+)\]', f).group(1) for f in fails
                      if re.match(r'\[(\w+)\]', f))
            g1 = [f for f in fails if f.startswith('[G1]')]
            status = 'PASS' if got == want else 'FAIL'
            if status == 'FAIL':
                ok = False
            print('  %-24s 期望 %-22s 实际 %-22s %s' % (name, sorted(want), sorted(got), status))
            if name == 'bad-baseline' and len(g1) != 1:
                ok = False
                print('  %-24s G1 应只报 1 条（嵌套去重），实际 %d 条 FAIL' % (name, len(g1)))
    print('反假阳性自测：%s' % ('全部通过' if ok else '有失败'))
    return 0 if ok else 1


def main():
    if '--selftest' in sys.argv:
        return selftest()
    if os.environ.get('TGAS_SKIP_TRIGGER') == '1':
        print('SKIP (TGAS_SKIP_TRIGGER=1)')
        return 0
    core = os.environ.get('TGAS_CORE') or DEFAULT_CORE
    if not os.path.isfile(core):
        print('FATAL 找不到目标文件: %s' % core)
        return 1
    fails, notes = check(core)
    for n in notes:
        print(n)
    if fails:
        print('')
        for f in fails:
            print(f)
        print('')
        print('触发链门禁：%d 条违规' % len(fails))
        return 1
    print('触发链门禁：全部通过（G1~G8）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
