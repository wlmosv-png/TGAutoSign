#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对照扫描：配置键 ←→ 界面控件 ←→ 逻辑读取。

三个集合：
  ① 界面有控件（swRow / adInput）
  ② 配置有存取（putCfg / cfgStr|cfgBool|cfgInt）
  ③ 逻辑有读取（除读取配置那几行外还有用处）

产出四象限，其中「界面有、逻辑无」= 装饰品开关（本次已抓到一例）。
"""
import io, re, os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                 "app/src/main/java/io/github/wlmosv_png/tgautosign/TGAutoSignCore.java")
src = io.open(P, encoding="utf-8").read()
code = re.sub(r'//[^\n]*', '', src)
code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)

# ① 界面控件：swRow(act, "文案", FIELD)  /  adInput
ui_fields = set(re.findall(r'swRow\s*\(\s*\w+\s*,\s*"[^"]*"\s*,\s*([A-Za-z_][\w.]*)\s*\)', code))

# ② 配置读写键
put_keys = set(re.findall(r'putCfg\(\s*\w+\s*,\s*"([a-z_]+)"', code))
get_keys = set(re.findall(r'cfg(?:Bool|Str|Int)\(\s*"([a-z_]+)"', code))

# 每个字段的「非赋值用法」次数
def logic_uses(field):
    # 排除：声明、cfg 读取赋值、从界面读赋值、putCfg 参数
    n = 0
    for m in re.finditer(r'\b' + re.escape(field) + r'\b', code):
        line_start = code.rfind('\n', 0, m.start()) + 1
        line_end = code.find('\n', m.start())
        line = code[line_start:line_end if line_end > 0 else len(code)]
        # 跳过纯赋值行 / 声明行 / 日志字符串行
        if re.search(re.escape(field) + r'\s*=(?!=)', line) and 'if' not in line and '?' not in line:
            continue
        if 'private' in line and ';' in line and '=' in line:
            continue
        if 'append(' in line or '"\u0000' in line:
            continue
        n += 1
    return n

print("=" * 72)
print("配置开关三分对照")
print("=" * 72)
print("界面控件 %d 个 / 配置写 %d 个 / 配置读 %d 个\n" % (len(ui_fields), len(put_keys), len(get_keys)))

print("── ① 界面有开关，但逻辑里从不判断（装饰品）──")
dead = []
for f in sorted(ui_fields):
    if logic_uses(f) <= 1:
        dead.append(f)
for f in dead:
    print("  ⚠ %s" % f)
if not dead:
    print("  无")

print("\n── ② 配置写了但没人读（存了白存）──")
only_put = sorted(put_keys - get_keys)
for k in only_put:
    print("  · %s" % k)
if not only_put:
    print("  无")

print("\n── ③ 配置读了但从没写过（永远默认值）──")
only_get = sorted(get_keys - put_keys)
for k in only_get:
    print("  ⚠ %s" % k)
if not only_get:
    print("  无")
