#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""资源同步门禁 —— 防止「改了 res / manifest，却用旧打包链出包导致资源没进包」。

## 为什么需要这道门（2026-10-04 实际事故）

用户此前提交了 7f00945「新图标 + 补模块描述」，但之后连续十几轮出包，
走的都是 `2-stage-apk.sh`（以 donor 为底，只替换 classes.dex / module.prop /
scope.list）。**那条链不碰 res/ 与 resources.arsc** —— 于是：
  · 应用图标一直是 donor 的旧图案；
  · AndroidManifest 的 android:description 从来没有被写进包，
    表现为 MMRL 模块列表里「描述为空」。
源码里改动一直都在，commit 也没丢，纯粹是打包链选错，且**没有任何环节会报错**。

## 检查项

  1. manifest 必须含 android:description（精确属性名，不是子串）
  2. 包内必须覆盖源码 res/ 的每个**实体资源**（drawable / mipmap / layout …；
     values/ 下的 strings/colors 编译后只进 arsc，不产生同名文件，故排除）
  3. 【内容级】用 aapt2 把源码 res 编译一遍，与 APK 内同名资源**逐字节比对**。
     这是唯一能识穿「文件名一样但内容还是旧图标」的判据。
     需要 aapt2；拿不到时跳过并明确提示（不算失败，但会打印警告）。

## 用法

    python3 tools/check-res-sync.py <apk路径>
    python3 tools/check-res-sync.py <apk路径> --aapt2 <aapt2> --aapt2-lib <libdir>

退出码 0 = 通过；1 = 资源没进包（并打印补救步骤）。
不传 APK 时退化为源码侧自检。
"""
import hashlib
import io
import os
import subprocess
import sys
import tempfile
import zipfile

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES_DIR = os.path.join(BASE, "app", "src", "main", "res")
MANIFEST = os.path.join(BASE, "app", "src", "main", "AndroidManifest.xml")

REQUIRED = ["ic_launcher_bg", "ic_launcher_fg", "ic_launcher", "ic_launcher_round"]
REQUIRED_ATTRS = [("android:description", "模块描述（LSPosed / MMRL 列表下方那行字）")]
NO_FILE_KINDS = {"values"}   # 见文档：这些只进 arsc，不产生独立文件


def read(p):
    with io.open(p, encoding="utf-8", errors="replace") as f:
        return f.read()


def source_res_files():
    """源码 res/ 下会产生实体文件的资源：{名字主干: 绝对路径}。"""
    out = {}
    for root, _dirs, files in os.walk(RES_DIR):
        kind = os.path.basename(root).split("-", 1)[0]
        if kind in NO_FILE_KINDS:
            continue
        for f in files:
            out[f.rsplit(".", 1)[0]] = os.path.join(root, f)
    return out


def apk_res_files(apk):
    """APK 内 res/ 实体资源：{名字主干: 条目名}。"""
    out = {}
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if not n.startswith("res/") or n.endswith("/"):
                continue
            f = n.rsplit("/", 1)[-1]
            if not f:
                continue
            out[f.rsplit(".", 1)[0]] = n
    return out


def find_aapt2():
    cand = os.environ.get("TGAS_AAPT2", "/data/local/tmp/eta/aapt2run/bin/aapt2")
    lib = os.environ.get("TGAS_AAPT2_LIB", "/data/local/tmp/eta/aapt2run/lib")
    return (cand if os.path.isfile(cand) else None), lib


def compile_source_res(aapt2, lib):
    """aapt2 compile 源码 res → {资源名主干: 编译产物字节}（失败返回 None）。"""
    d = tempfile.mkdtemp(prefix="reschk")
    flat = os.path.join(d, "flat.zip")
    env = dict(os.environ)
    env["LD_LIBRARY_PATH"] = lib + ":" + env.get("LD_LIBRARY_PATH", "")
    try:
        r = subprocess.run([aapt2, "compile", "--dir", RES_DIR, "-o", flat],
                           env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        if r.returncode != 0 or not os.path.isfile(flat):
            return None
        out = {}
        with zipfile.ZipFile(flat) as z:
            for n in z.namelist():
                if n.endswith("/"):
                    continue
                stem = n.rsplit("/", 1)[-1].rsplit(".", 1)[0]
                out[stem] = z.read(n)
        return out
    except Exception:
        return None
    finally:
        try:
            import shutil
            shutil.rmtree(d, ignore_errors=True)
        except Exception:
            pass


def manifest_has_attr(apk, attr, aapt2, lib):
    """产物 manifest 是否含某属性（精确属性名）。

    踩过的坑：早先用「二进制里搜 description 子串」，donor 包因为带
    meta-data xposeddescription 也被判成"有" —— 门禁形同虚设。
    """
    short = attr.replace("android:", "")
    if aapt2:
        env = dict(os.environ)
        env["LD_LIBRARY_PATH"] = lib + ":" + env.get("LD_LIBRARY_PATH", "")
        try:
            r = subprocess.run([aapt2, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"],
                               env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=90)
            if r.returncode == 0:
                for line in r.stdout.decode("utf-8", "replace").splitlines():
                    ls = line.strip()
                    if ls.startswith("A:") and ("android:" + short + "(") in ls:
                        return True
                return False
        except Exception:
            pass
    # 退化：二进制里出现该属性名，且不只以 xposeddescription 形态存在
    with zipfile.ZipFile(apk) as z:
        try:
            raw = z.read("AndroidManifest.xml")
        except KeyError:
            return False
    pat = short.encode("utf-16-le")
    if pat not in raw:
        return False
    return raw.count(pat) > raw.count(("xposed" + short).encode("utf-16-le"))


def main():
    args = [a for a in sys.argv[1:]]
    apk = None
    aapt2 = None
    lib = ""
    i = 0
    while i < len(args):
        if args[i] == "--aapt2" and i + 1 < len(args):
            aapt2 = args[i + 1]; i += 2; continue
        if args[i] == "--aapt2-lib" and i + 1 < len(args):
            lib = args[i + 1]; i += 2; continue
        if apk is None:
            apk = args[i]
        i += 1
    if aapt2 is None:
        aapt2, lib = find_aapt2()

    src = source_res_files()
    src_manifest = read(MANIFEST)
    ok = True
    for attr, desc in REQUIRED_ATTRS:
        if attr not in src_manifest:
            print("  FATAL: 源码 AndroidManifest.xml 缺 %s（%s）" % (attr, desc)); ok = False
    for name in REQUIRED:
        if name not in src:
            print("  FATAL: 源码 res/ 缺资源 %s" % name); ok = False
    sx = os.path.join(RES_DIR, "values", "strings.xml")
    if not os.path.isfile(sx) or "module_description" not in read(sx):
        print("  FATAL: strings.xml 缺 module_description"); ok = False
    if not ok:
        return 1

    if not apk:
        print("  资源同步门禁（源码侧自检）：")
        print("    源码 res 实体资源 %d 项，manifest / strings 齐全" % len(src))
        return 0

    if not os.path.isfile(apk):
        print("  FATAL: APK 不存在: %s" % apk)
        return 1

    print("  资源同步门禁（产物校验）：")
    problems = []

    for attr, desc in REQUIRED_ATTRS:
        if manifest_has_attr(apk, attr, aapt2, lib):
            print("    OK   manifest 含 %s" % attr)
        else:
            print("    MISS manifest 缺 %s —— %s" % (attr, desc))
            problems.append("manifest 缺 %s" % attr)

    apkf = apk_res_files(apk)
    missing = sorted(n for n in src if n not in apkf)
    if missing:
        print("    MISS 包内缺少 %d 个源码资源: %s" % (len(missing), ", ".join(missing[:10])))
        problems.append("包内缺 %d 个资源" % len(missing))
    else:
        print("    OK   源码 res 的 %d 个实体资源全部在包内" % len(src))

    # ── 内容级比对（唯一能识穿「同名但内容还是旧图标」的判据）──
    if aapt2:
        ref = compile_source_res(aapt2, lib)
        if ref is None:
            print("    WARN aapt2 compile 失败，跳过内容比对")
        else:
            diff = []
            for name, data in ref.items():
                if name not in apkf:
                    continue
                with zipfile.ZipFile(apk) as z:
                    got = z.read(apkf[name])
                if hashlib.sha256(got).hexdigest() != hashlib.sha256(data).hexdigest():
                    diff.append(name)
            if diff:
                print("    MISS 以下资源**内容与源码不一致**（包里还是旧的）: %s"
                      % ", ".join(sorted(diff)[:10]))
                problems.append("%d 个资源内容陈旧" % len(diff))
            else:
                print("    OK   包内资源内容与源码一致（逐字节比对）")
    else:
        print("    WARN 未找到 aapt2，已跳过内容比对"
              "（注意：仅凭文件名无法发现「换了图标但包内还是旧图」）")

    if problems:
        print("")
        print("  ❌ 资源没进包：%s" % "；".join(problems))
        print("")
        print("  这通常意味着**用了错误的打包链**。只替换 dex 的链（2-stage-apk.sh）")
        print("  不会带入 res/ 与 resources.arsc，改了图标/描述也不生效，且不报错。")
        print("")
        print("  正确出包链（改过 res 或 manifest 资源引用时必须走这条）：")
        print("    android: aapt2 compile --dir app/src/main/res -o flat.zip")
        print("    android: aapt2 link --manifest app/src/main/AndroidManifest.xml \\")
        print("               -I <android.jar> --min-sdk-version 26 --target-sdk-version 36 \\")
        print("               --version-code N --version-name X.Y.Z -o res.apk flat.zip")
        print("    合流  res.apk 内的 res/ 、AndroidManifest.xml 、resources.arsc 进 repack/")
        print("    linux:   python3 pack_repack.py          # 整目录打包 + 4 字节对齐")
        print("    linux:   apksigner sign ...")
        return 1

    print("    ✅ 资源与源码一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
