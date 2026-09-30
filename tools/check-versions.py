#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""版本一致性检查。

## 规则（2026-10-01 修订）

分两类，语义不同，不能混为一谈：

  A. **构建版本号**（必须三处完全相同）
     build.gradle / UpdateChecker / module.prop
     这三处会被编进 APK：module.prop 进 LSPosed 列表，UpdateChecker 进 /jmb 面板，
     build.gradle 进 AndroidManifest。任何一处不一致，用户看到的就是版本混乱。
     背景（2026-09-23）：线上 v1.5.6 的包内 module.prop 是 1.5.6/119，
     但源码树停在 1.5.5/118 —— 上次发版用 repack --overlay 塞了正确 prop，
     却没回写源码树，下次谁直接 build.sh 就会打出 118 的包。

  B. **CHANGELOG 顶部**（只准落后，不准超前）
     CHANGELOG 是**已发布版本**的记录，不是构建产物。
     日常改代码时，构建版本号定格到「线上 + 1」，但 CHANGELOG 不该跟着走 ——
     否则 README 的日志段生成器会把未发布的版本同步进去，等于对外宣布一个
     不存在的版本（2026-10-01 实际发生过：1.6.3 没发版，README 已在宣传它）。

     所以这里只拦「CHANGELOG 比构建版本号新」：那说明写日志时忘了还没发版，
     或版本号回退了。CHANGELOG 落后是正常状态（改动还没发）。

## 用法
    python3 tools/check-versions.py       # 退出码 0 = 通过
"""
import io
import os
import re
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
GRADLE = os.path.join(BASE, 'app/build.gradle')
UPD = os.path.join(BASE, 'app/src/main/java/io/github/wlmosv_png/tgautosign/update/UpdateChecker.java')
PROP = os.path.join(BASE, 'app/src/main/resources/META-INF/xposed/module.prop')
CHLOG = os.path.join(BASE, 'CHANGELOG.md')


def read(p):
    return io.open(p, encoding='utf-8').read()


def main():
    got = {}

    g = read(GRADLE)
    m = re.search(r'versionCode\s+(\d+)', g)
    n = re.search(r'versionName\s+"([^"]+)"', g)
    if not m or not n:
        print('FATAL: build.gradle 里读不到 versionCode/versionName')
        return 1
    got['build.gradle'] = (int(m.group(1)), n.group(1))

    u = read(UPD)
    m = re.search(r'VERSION_CODE\s*=\s*(\d+)', u)
    n = re.search(r'VERSION_NAME\s*=\s*"([^"]+)"', u)
    if not m or not n:
        print('FATAL: UpdateChecker.java 里读不到 VERSION_CODE/VERSION_NAME')
        return 1
    got['UpdateChecker'] = (int(m.group(1)), n.group(1))

    p = read(PROP)
    m = re.search(r'^versionCode=(\d+)', p, re.M)
    n = re.search(r'^version=([^\s]+)', p, re.M)
    if not m or not n:
        print('FATAL: module.prop 里读不到 versionCode/version')
        return 1
    got['module.prop'] = (int(m.group(1)), n.group(1))

    # 第五查：正式包的 PATCH_TAG 必须为空
    pt = re.search(r'PATCH_TAG\s*=\s*"([^"]*)"', u)
    if pt is None:
        print('FATAL: UpdateChecker 里读不到 PATCH_TAG')
        return 1
    if pt.group(1).strip() != '':
        print('FATAL: PATCH_TAG 非空（"%s"）—— 这是本机测试标记，正式发版必须清空' % pt.group(1))
        return 1

    # CHANGELOG（可缺，但不准超前）
    c = read(CHLOG)
    m = re.search(r'^##\s+([\d.]+)\s*\((\d+)\)', c, re.M)
    chlog = (int(m.group(2)), m.group(1)) if m else None

    print('  版本检查：')
    print('    %-16s %s' % ('PATCH_TAG', '(空) OK'))
    for k in ('build.gradle', 'UpdateChecker', 'module.prop'):
        code, name = got[k]
        print('    %-16s %s (%d)' % (k, name, code))
    if chlog:
        print('    %-16s %s (%d)  ← 已发布的最新一条' % ('CHANGELOG', chlog[1], chlog[0]))
    else:
        print('    %-16s (无)' % 'CHANGELOG')

    # A. 三处构建版本号必须一致
    codes = {v[0] for v in got.values()}
    names = {v[1] for v in got.values()}
    if len(codes) != 1 or len(names) != 1:
        bad = [k for k in got if got[k][0] != max(codes) or got[k][1] not in names]
        print('FATAL: 构建版本号三处不一致：%s' % ', '.join(bad))
        print('  这三处会被编进 APK，必须同 versionCode 同 versionName')
        return 1

    build_code = list(codes)[0]
    build_name = list(names)[0]

    # B. CHANGELOG 不得超前
    if chlog:
        if chlog[0] > build_code:
            print('FATAL: CHANGELOG 顶部 %s (%d) 比构建版本 %s (%d) 新' % (chlog[1], chlog[0], build_name, build_code))
            print('  CHANGELOG 记的是已发布版本，不该超前。')
            print('  改代码阶段请勿写 CHANGELOG —— 它会让 README 日志段宣传未发布的版本。')
            return 1
        if chlog[0] == build_code:
            print('  ✅ 构建版本 %s (%d)，CHANGELOG 已跟上（发版状态）' % (build_name, build_code))
        else:
            print('  ✅ 构建版本 %s (%d)，CHANGELOG 停在 %s (%d)（开发中，正常）'
                  % (build_name, build_code, chlog[1], chlog[0]))
    else:
        print('  ✅ 构建版本 %s (%d)，CHANGELOG 无条目' % (build_name, build_code))
    return 0


if __name__ == '__main__':
    sys.exit(main())
