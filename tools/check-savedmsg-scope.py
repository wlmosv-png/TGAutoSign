#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：收藏夹摘要开关必须**按账号**判定（2026-10-27 跨账号串扰回归护栏）。

背景（实测故障）：SAVED_MSG_ON 是单个内存字段，取值读「当前选中账号」的
acc{N}_cfg_saved_msg，而发送端按 accountSlots() 遍历所有账号 →
关一个账号压不住、切号后又回退默认，表现为「关了收藏夹还在发简报」。

契约（R1~R6）：
  R1  sendSavedMessage 内层必须有 savedMsgOnFor(account) 守卫
  R2  不存在用 SAVED_MSG_ON 直接做**发送决策**的写法
  R3  trySendDailySummary 必须用 canSendSavedSummaryFor(acc)
  R4  SAVED_MSG_ON 其余出现只允许：字段声明 / 当前账号缓存赋值 / UI 读写
  R5  决策必须收敛到可注入的 SavedMsgPolicy（禁止在 Core 再写一套键拼接/优先级）
  R6  放行与拦截两条路径都要有可还原日志（真机验收要求）

用法: python3 tools/check-savedmsg-scope.py [项目根]
退出码 0 = 通过；1 = 违反契约（含行号）。
"""
import io
import os
import re
import sys

ROOT_DEFAULT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PKG = "/app/src/main/java/io/github/wlmosv_png/tgautosign"

ALLOW = [
    "private boolean SAVED_MSG_ON",
    "SAVED_MSG_ON = cfgBool(",
    "R.savedMsgSw",
    "putCfg(ed, \"saved_msg\", SAVED_MSG_ON)",
    "savedMsgOnFor(",
    "canSendSavedSummaryFor(",
]


def method_body(src, header):
    i = src.find(header)
    if i < 0:
        return None
    j = src.find("{", i)
    if j < 0:
        return None
    depth = 0
    for k in range(j, len(src)):
        c = src[k]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
    return None


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else ROOT_DEFAULT
    core_path = root + PKG + "/TGAutoSignCore.java"
    src = io.open(core_path, encoding="utf-8").read()
    failures = []

    body = method_body(src, "private void sendSavedMessage(")
    if body is None:
        failures.append("R1 找不到 sendSavedMessage 方法体")
    elif "savedMsgOnFor(" not in body:
        failures.append("R1 sendSavedMessage 内层缺少 savedMsgOnFor(account) 守卫")

    bad = []
    for n, line in enumerate(src.splitlines(), 1):
        t = line.strip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        if re.search(r"if\s*\(\s*!?\s*SAVED_MSG_ON\s*\)", t) and "savedMsgOnFor" not in t:
            bad.append(n)
    if bad:
        failures.append("R2 仍有用 SAVED_MSG_ON 做发送决策的行: " + ",".join(map(str, bad)))

    body = method_body(src, "private void trySendDailySummary(")
    if body is None:
        failures.append("R3 找不到 trySendDailySummary 方法体")
    elif "canSendSavedSummaryFor(" not in body:
        failures.append("R3 trySendDailySummary 未按账号判定")

    offenders = []
    for n, line in enumerate(src.splitlines(), 1):
        if "SAVED_MSG_ON" not in line:
            continue
        t = line.strip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*"):
            continue
        if any(a in line for a in ALLOW):
            continue
        offenders.append((n, t[:90]))
    if offenders:
        failures.append("R4 SAVED_MSG_ON 出现于非白名单位置："
                        + "; ".join("%d:%s" % (n, s) for n, s in offenders))

    b_on = method_body(src, "private boolean savedMsgOnFor(")
    b_can = method_body(src, "private boolean canSendSavedSummaryFor(")
    if b_on is None or "SavedMsgPolicy" not in b_on:
        failures.append("R5 savedMsgOnFor 未收敛到 SavedMsgPolicy（可能在 Core 复写了键拼接/优先级）")
    if b_can is None or "SavedMsgPolicy" not in b_can:
        failures.append("R5 canSendSavedSummaryFor 未收敛到 SavedMsgPolicy")

    policy_path = root + PKG + "/savedmsg/SavedMsgPolicy.java"
    if not os.path.exists(policy_path):
        failures.append("R5 缺少 savedmsg/SavedMsgPolicy.java")
    else:
        pol = io.open(policy_path, encoding="utf-8").read()
        for need in ("accountKey(", "GLOBAL_KEY", "SignLogic.resolveSavedMsgOn",
                     "SignLogic.canSendSavedSummary", "static boolean deliver("):
            if need not in pol:
                failures.append("R5 SavedMsgPolicy 缺关键成员: " + need)

    if "savedmsg_guard:" not in src or "savedmsg_off:" not in src:
        failures.append("R6 缺少放行/拦截的可还原日志（savedmsg_guard / savedmsg_off）")

    calls = len(re.findall(r"sendSavedMessage\(", src))
    print("sendSavedMessage 出现次数（含定义）: %d" % calls)

    if failures:
        print("FATAL 收藏夹开关作用域契约被破坏：")
        for f in failures:
            print("  ✗ " + f)
        return 1
    print("OK   R1 sendSavedMessage 内层有按账号守卫")
    print("OK   R2 无以 SAVED_MSG_ON 直接决策的发送点")
    print("OK   R3 trySendDailySummary 按账号判定")
    print("OK   R4 SAVED_MSG_ON 仅用于当前账号缓存与 UI")
    print("OK   R5 决策收敛到 SavedMsgPolicy（键拼接/优先级唯一）")
    print("OK   R6 放行与拦截均有可还原日志")
    return 0


if __name__ == "__main__":
    sys.exit(main())
