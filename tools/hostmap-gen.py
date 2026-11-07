#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HostSymbols 映射表生成器 —— Telegram 每次更新后跑一遍即可。

为什么需要它
------------
Telegram 每个版本都会重排 org.telegram.messenger.* 的混淆名（实测 13.2.0 官网版是
lp/os/w10…，13.2.1 Play 版却是 vp/ys/c20…），所以 HostSymbols 里的 CLASS_MAPS
必须逐版本维护。手抄容易错、也慢；这里用 apk-index 的**结构指纹**自动定位。

用法
----
    # 1) 先用 apk-index 载入目标 APK（dex 内容 sha1 去重；重复调用直接命中缓存）
    python3 tools/hostmap-gen.py /path/to/base.apk --tier 73000
    # 2) 确认输出无误后直接写进 HostSymbols.java
    python3 tools/hostmap-gen.py /path/to/base.apk --tier 73000 --apply

结构指纹（与 HostSymbols 的运行期校验同源，改一处要改两处）
----------------------------------------------------------
  MessagesController  : 全包唯一 (ArrayList,ArrayList,ArrayList,boolean,int)->boolean
                        （原 processUpdateArray；该方法在 Telegram-Android 里唯一）
  NotificationCenter  : 全包唯一「(int,Object[])->void 且体内调用 SparseArray.keyAt(I)I」
                        （原 postNotificationName）
  BaseController      : MessagesController 的父类
  UserConfig          : BaseController 子类 + 持有 TLRPC$User 字段 + static (int)->自身
  SharedConfig        : 引用字符串 "mainconfig" 且父类为 java.lang.Object
  MessagesStorage     : BaseController 子类 + 引用字符串 "cache4.db"
  DialogObject        : static (Peer)->long 且**同时**有 static (InputPeer)->long（消歧关键）
  SendMessagesHelper  : BaseController 子类 + static (int)->自身 + 存在 ≥10 个参数的方法
  UserNameResolver    : 唯一 new TL_contacts_resolveUsername 的 messenger 包类

退出码：0 = 全部定位成功；1 = 有符号没找到（会列出是哪几个，不要用残缺的表）。
"""
import argparse
import json
import os
import re
import subprocess
import sys

REPO = os.environ.get("APK_INDEX_REPO", "/storage/emulated/0/Download/projects/apk-index-pub/apk-index")
ENV = dict(os.environ, PYTHONPATH=REPO + "/src")

LEGACY = [
    "MessagesController", "NotificationCenter", "BaseController", "UserConfig",
    "SharedConfig", "MessagesStorage", "DialogObject", "SendMessagesHelper", "UserNameResolver",
]


def call(tool, args):
    p = subprocess.run([sys.executable, "-m", "apkindex.cli", "call", tool, json.dumps(args)],
                       capture_output=True, text=True, env=ENV, cwd=REPO, timeout=900)
    try:
        return json.loads(p.stdout.strip())
    except Exception:
        return {"ok": False, "raw": p.stdout[:600], "err": p.stderr[:600]}


def items(r):
    return (r.get("items") or (r.get("structured_content") or {}).get("items") or [])


def load(apk):
    r = call("loadApk", {"path": apk})
    if not r.get("ok"):
        sys.exit("FATAL: loadApk 失败: %s" % r)
    return r["sessionId"]


def hits(ses, **kw):
    """返回命中的类名列表（去重、保序）。"""
    r = call("matchSignature", dict(sessionId=ses, limit=200, **kw))
    out = []
    for i in items(r):
        c = i.get("class")
        if c and c not in out:
            out.append(c)
    return out


def only(ses, **kw):
    h = hits(ses, **kw)
    return (h[0], h) if len(h) == 1 else (None, h)


def _field_count(ses, cls):
    r = call("listMembers", {"sessionId": ses, "class": cls, "include": "both", "limit": 1})
    hint = ((r.get("structured_content") or r).get("hint") or "")
    m = re.search(r"(\d+)\s*字段", hint)
    return int(m.group(1)) if m else 0


def by_string_all(ses, text, want_super=None, exclude=()):
    """返回所有满足条件的候选类（by_string 的单结果版见下）。"""
    r = call("searchByString", {"sessionId": ses, "text": text, "match": "contains", "limit": 30})
    cands = []
    for i in items(r):
        for mm in (i.get("methods") or []):
            c = mm.get("class") or ""
            if c and c not in cands:
                cands.append(c)
    out = []
    for c in cands:
        if c in exclude:
            continue
        if want_super is None or _super(ses, c) == want_super:
            out.append(c)
    return out


def by_string(ses, text, want_super=None, exclude=()):
    r = call("searchByString", {"sessionId": ses, "text": text, "match": "contains", "limit": 30})
    out = by_string_all(ses, text, want_super, exclude)
    return out[0] if len(out) == 1 else None


_SUPER_CACHE = {}


def _super(ses, cls):
    k = (ses, cls)
    if k in _SUPER_CACHE:
        return _SUPER_CACHE[k]
    r = call("searchClasses", {"sessionId": ses, "query": "^%s$" % re.escape(cls), "kind": "regex", "limit": 3})
    s = None
    for i in items(r):
        s = i.get("superClass")
        break
    _SUPER_CACHE[k] = s
    return s


def members(ses, cls, kind="methods", pat=None):
    a = {"sessionId": ses, "class": cls, "include": kind, "limit": 2000}
    if pat:
        a["namePattern"] = pat
    r = call("listMembers", a)
    return (r.get("structured_content") or r).get("items") or []


def resolve(ses, verbose=False):
    """结构指纹定位。返回 {旧名: 实际类名}，未定位到的值为 None。"""
    res = {k: None for k in LEGACY}
    note = []

    mc, h = only(ses, params=["java.util.ArrayList", "java.util.ArrayList", "java.util.ArrayList",
                              "boolean", "int"], returnType="boolean")
    res["MessagesController"] = mc
    if not mc:
        note.append("MessagesController 候选=%s" % h)
        return res, note
    base = _super(ses, mc)
    res["BaseController"] = base
    if not base:
        note.append("BaseController: 取不到 %s 的父类" % mc)
        return res, note

    nc, h = only(ses, params=["int", "java.lang.Object[]"], returnType="void",
                 invokedMethods=["Landroid/util/SparseArray;->keyAt(I)I"])
    res["NotificationCenter"] = nc

    # 字符串锚点 + 父类判定（比枚举子类稳，不受返回条数上限影响）
    res["UserConfig"] = by_string(ses, "userconfing", want_super=base)
    # SharedConfig：引用 "mainconfig" 且父类 Object 的候选可能不止一个（还有工具类），
    # 再按「字段数最多且 >=50」收窄 —— SharedConfig 是那个超宽配置类（实测 134 字段量级）。
    sc_cands = by_string_all(ses, "mainconfig", want_super="java.lang.Object")
    if len(sc_cands) > 1:
        scored = []
        for c in sc_cands:
            n = _field_count(ses, c)
            if n >= 50:
                scored.append((n, c))
        scored.sort(reverse=True)
        sc_cands = [scored[0][1]] if scored else []
    res["SharedConfig"] = sc_cands[0] if len(sc_cands) == 1 else None
    res["MessagesStorage"] = by_string(ses, "cache4.db", want_super=base)

    # DialogObject：两个重载必须落在同一个类上（只有 (Peer) 版本的是 MessageObject 一类的工具）
    pk = hits(ses, params=["org.telegram.tgnet.TLRPC$Peer"], returnType="long", modifiers=["static", "public"])
    ik, _ = only(ses, params=["org.telegram.tgnet.TLRPC$InputPeer"], returnType="long", modifiers=["static", "public"])
    both = [c for c in pk if c == ik]
    res["DialogObject"] = both[0] if len(both) == 1 else None
    if not res["DialogObject"]:
        note.append("DialogObject: (Peer)->long=%s  (InputPeer)->long=%s" % (pk, ik))

    # SendMessagesHelper：BaseController 子类里「有 static(int)->自身 且 存在 ≥10 参方法」
    subs = []
    r = call("findImplementations", {"sessionId": ses, "superClass": base, "limit": 200, "transitive": True})
    for i in items(r):
        c = i.get("binaryName") or i.get("descriptor") or i.get("class")
        if c and c.startswith("org.telegram.messenger."):
            subs.append(c)
    r2 = call("matchSignature", {"sessionId": ses,
                                 "invokedMethods": ["L%s;-><init>(I)V" % base.replace(".", "/")], "limit": 200})
    for i in items(r2):
        c = i.get("class")
        if c and c.startswith("org.telegram.messenger.") and c not in subs:
            subs.append(c)
    cand = []
    for c in subs:
        ms = members(ses, c, "methods", "^[A-Za-z]$")
        selfstatic = any(m.get("static") and m.get("paramsDescriptor") == "(I)"
                         and (m.get("returnDescriptor") or "") == "L%s;" % c.replace(".", "/") for m in ms)
        big = any((m.get("paramCount") or 0) >= 10 for m in ms)
        if selfstatic and big:
            cand.append(c)
    res["SendMessagesHelper"] = cand[0] if len(cand) == 1 else None
    if not res["SendMessagesHelper"]:
        note.append("SendMessagesHelper: 候选=%s（子类样本=%d）" % (cand, len(subs)))

    r = call("matchSignature", {"sessionId": ses,
                                "invokedMethods": ["Lorg/telegram/tgnet/TLRPC$TL_contacts_resolveUsername;-><init>()V"],
                                "limit": 60})
    ur = []
    for i in (r.get("items") or []):
        c = i.get("class") or ""
        if c.startswith("org.telegram.messenger.") and c not in ur:
            ur.append(c)
    res["UserNameResolver"] = ur[0] if len(ur) == 1 else None
    if len(ur) > 1:
        note.append("UserNameResolver: 候选=%s" % ur)
    return res, note


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--tier", type=int, required=True, help="版本档（versionCode 取百位，如 72900）")
    ap.add_argument("--apply", action="store_true", help="直接写进 HostSymbols.java")
    ap.add_argument("--src", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()

    ses = load(a.apk)
    print("sessionId:", ses)
    res, note = resolve(ses)
    bad = [k for k in LEGACY if not res.get(k)]
    for k in LEGACY:
        print("  %s %-20s %s" % ("OK " if res.get(k) else "?? ", k, res.get(k) or "-"))
    for n in note:
        print("  note:", n)
    if bad:
        print("\nFATAL: 以下符号未能唯一定位，请人工确认后再写表：%s" % ", ".join(bad))
        return 1

    body = "\n".join('                {"org.telegram.messenger.%s", "%s"},' % (k, res[k]) for k in LEGACY)
    block = ('        CLASS_MAPS.put(%dL, new String[][]{\n%s\n        });\n' % (a.tier, body))
    print("\n---- 生成条目 ----")
    print(block)
    if a.apply:
        path = os.path.join(os.path.abspath(a.src), "app/src/main/java/io/github/wlmosv_png/tgautosign/HostSymbols.java")
        s = open(path, encoding="utf-8").read()
        anchor = "        CLASS_MAPS.put("
        i = s.index(anchor)
        s = s[:i] + block + s[i:]
        open(path, "w", encoding="utf-8").write(s)
        print("已写入 %s" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
