#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""版本一致性四查：build.gradle / UpdateChecker / module.prop / CHANGELOG 顶部

背景（2026-09-23 发现）：线上 v1.5.6 的包内 module.prop 是 1.5.6/119，
但**源码树**的 module.prop 停在 1.5.5/118 —— 上次发版用 repack --overlay
塞了正确 prop 进包，却没回写源码树。下次谁直接 build.sh 就会打出 118 的包。
四查挂进 build.sh，不一致直接构建失败。
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

    c = read(CHLOG)
    m = re.search(r'^##\s+([\d.]+)\s*\((\d+)\)', c, re.M)
    if not m:
        print('FATAL: CHANGELOG.md 顶部读不到 "## X.Y.Z (NNN)"')
        return 1
    got['CHANGELOG'] = (int(m.group(2)), m.group(1))

    print('  版本四查：')
    bad = []
    for k in ('build.gradle', 'UpdateChecker', 'module.prop', 'CHANGELOG'):
        code, name = got[k]
        print('    %-16s %s (%d)' % (k, name, code))

    codes = {v[0] for v in got.values()}
    names = {v[1] for v in got.values()}
    if len(codes) != 1 or len(names) != 1:
        bad = [k for k in got if got[k][0] != max(codes) or got[k][1] not in names]

    if bad:
        print('FATAL: 版本号四处不一致：%s' % ', '.join(bad))
        print('  三处必须同 versionCode 同 versionName（module.prop 也参与 --overlay）')
        return 1

    print('  ✅ 版本一致：%s (%d)' % (list(names)[0], list(codes)[0]))
    return 0


if __name__ == '__main__':
    sys.exit(main())
