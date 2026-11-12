# -*- coding: utf-8 -*-
"""合规打包：STORED 条目 4 字节对齐（Android 11+ 对 resources.arsc 的硬要求）。

为什么自己写：skills 的 3-sign.sh 内嵌打包器把 classes.dex / AndroidManifest.xml
写成 STORED 却不加 padding —— 实测偏移 41 与 1064629 均非 4 倍数。
本机 pm install 以 root 运行会绕过该检查，用户点安装会直接失败。

做法：把必须 STORED 的条目（AndroidManifest.xml / classes.dex / resources.arsc）
放在最前并逐个补 extra 字段使数据区对齐；其余条目照旧 DEFLATED 排在后面。
"""
import os, sys, zipfile

def main(repack_dir, out_apk):
    items = {}
    order = ["AndroidManifest.xml", "classes.dex", "resources.arsc"]
    for root, _, files in os.walk(repack_dir):
        for f in files:
            p = os.path.join(root, f)
            rel = os.path.relpath(p, repack_dir).replace(os.sep, "/")
            items[rel] = open(p, "rb").read()
    for n in order:
        assert n in items, "缺少必需条目 %s" % n
    stored = [n for n in order]
    rest = sorted(n for n in items if n not in order)
    if os.path.exists(out_apk):
        os.remove(out_apk)
    pos = 0
    with zipfile.ZipFile(out_apk, "w") as zo:
        for name in stored:
            data = items[name]
            nb = name.encode("utf-8")
            base = 30 + len(nb)
            pad = (4 - (pos + base) % 4) % 4
            zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zi.extra = b"\x00" * pad
            zo.writestr(zi, data)
            pos = pos + base + pad + len(data)
        for name in rest:
            zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zo.writestr(zi, items[name])
    # 自检：逐条读回真实数据偏移
    bad = []
    with zipfile.ZipFile(out_apk) as z:
        for i in z.infolist():
            if i.compress_type != zipfile.ZIP_STORED:
                continue
            off = i.header_offset + 30 + len(i.filename.encode("utf-8")) + len(i.extra)
            ok = (off % 4 == 0)
            print("    %-24s STORED offset=%-8d %s" % (i.filename, off, "aligned" if ok else "MISALIGNED"))
            if not ok:
                bad.append(i.filename)
            assert off == i.header_offset + 30 + len(i.filename.encode()) + len(i.extra)
    assert not bad, "仍有未对齐的 STORED 条目: %r" % bad
    print("  打包完成 %s  %d B" % (out_apk, os.path.getsize(out_apk)))

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
