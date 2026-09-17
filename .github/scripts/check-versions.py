#!/usr/bin/env python3
# 版本一致性守卫：UpdateChecker.VERSION_CODE == build.gradle versionCode == module.prop versionCode
# 三处任一不一致 -> exit 1，发版前必须同步（v1.5.0 曾因漏改导致"永远显示可更新"）
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def grab(path, key):
    try:
        with open(path, encoding='utf-8') as f:
            s = f.read()
    except OSError as e:
        print('FAIL: 读不到', path, e)
        sys.exit(1)
    i = s.find(key)
    if i < 0:
        print('FAIL:', path, '找不到 key =', key)
        sys.exit(1)
    out = ''
    for ch in s[i + len(key):]:
        if ch.isdigit():
            out += ch
        else:
            break
    return out


gradle = grab(os.path.join(ROOT, 'app', 'build.gradle'), 'versionCode ')
updater = grab(os.path.join(ROOT, 'app', 'src', 'main', 'java', 'io', 'github', 'wlmosv_png', 'tgautosign', 'update', 'UpdateChecker.java'), 'VERSION_CODE = ')
prop = grab(os.path.join(ROOT, 'app', 'src', 'main', 'resources', 'META-INF', 'xposed', 'module.prop'), 'versionCode=')

print('gradle=%s updater=%s module.prop=%s' % (gradle, updater, prop))
if gradle and gradle == updater == prop:
    print('OK 版本号三处一致')
    sys.exit(0)
print('FAIL 版本号不一致！发版前必须三处同步：app/build.gradle / update/UpdateChecker.java / module.prop')
sys.exit(1)
