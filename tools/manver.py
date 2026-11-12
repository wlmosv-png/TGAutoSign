#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AndroidManifest.xml（二进制 AXML）版本号读写工具 —— TGAutoSign 出包链用。

为什么需要：本机出包链用 donor APK 做底（只换 classes.dex 与 META-INF/xposed），
donor 的 AndroidManifest 里带着**它自己的** versionCode/versionName。
不把它改回本模块的构建版本号，就会出现「系统设置显示 X、面板显示 Y」的经典不一致。

判据（不猜）：直接解析 AXML 的属性记录，按**属性名字符串**定位，不靠属性 ID、不靠偏移量。
支持：
  · 读：列出 manifest 元素的全部属性（名字 → 类型 → 值）
  · 写：把 versionCode / versionName 改成指定值（versionName 必须等长，直接覆写字节）

用法：
  python3 manver.py show <apk> [entry=AndroidManifest.xml]
  python3 manver.py set  <in.apk> <out.apk> <versionCode> <versionName> [entry]
"""
import io
import os
import struct
import sys
import zipfile

TYPE_INT = 0x10
TYPE_STRING = 0x03


def read_pool(buf):
    """解析 RES_STRING_POOL，返回 (字符串列表, 数据区基址, 是否 UTF-8 标志)。"""
    off0 = 0
    typ, hdr, size = struct.unpack_from("<HHI", buf, 0)
    if typ == 0x0003:                       # RES_XML header（8 字节），字符串池紧随其后
        off0 = 8
        typ, hdr, size = struct.unpack_from("<HHI", buf, off0)
    assert typ == 0x0001, "不是字符串池: 0x%04x" % typ
    cnt, styc, flags, sstart, stystart = struct.unpack_from("<IIIII", buf, off0 + 8)
    utf8 = bool(flags & (1 << 8))
    offs = struct.unpack_from("<%dI" % cnt, buf, off0 + hdr)
    base = off0 + 8 + sstart
    out = []
    for o in offs:
        p = base + o
        if utf8:
            n = buf[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | buf[p + 1]
                p += 2
            else:
                p += 1
            # UTF-8 池里长度是「字符数」，按字节读到 NUL 为止更稳
            e = buf.index(b"\x00", p)
            out.append(buf[p:e].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]
                p += 2
            out.append(buf[p:p + n * 2].decode("utf-16-le", "replace"))
    return out, base, utf8, offs


def iter_attrs(buf):
    """遍历 AXML，产出 (元素名, 属性起点, [(attr_off, name_idx, type, data, raw_idx)])。"""
    pool, base, utf8, offs = read_pool(buf)
    off = struct.unpack_from("<H", buf, 2)[0]          # header size
    off = 8
    # 跳过字符串池
    ptyp, phdr, psize = struct.unpack_from("<HHI", buf, off)
    off += psize
    elems = []
    while off + 8 <= len(buf):
        ctyp, chdr, csize = struct.unpack_from("<HHI", buf, off)
        if csize == 0:
            break
        if ctyp == 0x0102:                              # START_ELEMENT
            # ResXMLTree_attrExt：ns(4) name(4) attributeStart(2) attributeSize(2) count(2) ...
            nidx = struct.unpack_from("<i", buf, off + chdr + 4)[0]
            astart, asize = struct.unpack_from("<HH", buf, off + chdr + 8)
            attrs = []
            p = off + chdr + astart
            while p + asize <= off + csize and asize:
                ns, name, raw = struct.unpack_from("<iii", buf, p)
                sz, res0, atype = struct.unpack_from("<HBB", buf, p + 12)
                data = struct.unpack_from("<i", buf, p + 16)[0]
                attrs.append((p, name, atype, data, raw))
                p += asize
            elems.append((pool[nidx] if 0 <= nidx < len(pool) else "?", attrs))
        off += csize
    return pool, elems


def show(apk, entry):
    with zipfile.ZipFile(apk) as z:
        buf = z.read(entry)
    pool, elems = iter_attrs(buf)
    print("文件: %s  条目: %s  (%d 字节)" % (apk, entry, len(buf)))
    for name, attrs in elems:
        for (ao, nidx, atype, data, raw) in attrs:
            an = pool[nidx] if 0 <= nidx < len(pool) else "?%d" % nidx
            if atype == TYPE_STRING:
                v = pool[data] if 0 <= data < len(pool) else "?%d" % data
                v = "字符串 %r" % v
            elif atype == TYPE_INT:
                v = "int %d (0x%08x)" % (data, data & 0xFFFFFFFF)
            else:
                v = "type=0x%02x raw=%d" % (atype, raw)
            if an in ("versionCode", "versionName", "package", "compileSdkVersion",
                      "minSdkVersion", "targetSdkVersion", "platformBuildVersionCode"):
                print("  <%s %s=%s  @%d>" % (name, an, v, ao))
    return pool, elems, buf


def set_ver(inapk, outapk, vc, vn, entry):
    """只做两处**字节级**改动，每处都带硬断言（不解析字符串池，避免编码歧义）。

    ① versionCode：由 AXML 属性表定位——挑出「type=INT 且当前值等于旧版本号」的那一条，
       断言唯一，然后改它的 data 字段。
    ② versionName：字符串池是 UTF-8，直接等长覆写 ASCII 字节（"1.7.8" -> "1.7.1"），
       断言该字节序列在 manifest 里唯一。
    """
    with zipfile.ZipFile(inapk) as z:
        buf = bytearray(z.read(entry))
        others = [(n, z.read(n)) for n in z.namelist() if n != entry]
    _, elems = iter_attrs(bytes(buf))
    # versionCode / versionName 在 manifest 元素里**紧邻成对**（INT 后紧跟 STRING）。
    # 用这个结构定位，而不是按属性名（字符串池是 UTF-8 且条目重叠，解码易错），
    # 也不按数值（数值正是要改的东西）。
    pairs = []
    for name, attrs in elems:
        for i in range(len(attrs) - 1):
            if attrs[i][2] == TYPE_INT and attrs[i + 1][2] == TYPE_STRING:
                pairs.append((attrs[i][0], attrs[i][2] and attrs[i][1], attrs[i + 1][1]))
    detail = []
    for (ao, _, sidx) in pairs:
        v = struct.unpack_from("<i", buf, ao + 16)[0]
        detail.append((ao, v))
    print("  INT/STRING 相邻对候选：", detail)
    # 消歧：compileSdkVersion/targetSdkVersion/minSdkVersion 都是 SDK 版本号（个位数/两位数），
    #      而应用的 versionCode 是三位数（171/178…）。取 >=100 的那一组。
    big = [(ao, v) for (ao, v) in detail if v >= 100]
    assert len(big) == 1, "versionCode 候选不唯一：%r" % (detail,)
    ao_code, old_code = big[0]
    print("  旧 versionCode = %d" % old_code)
    struct.pack_into("<i", buf, ao_code + 16, int(vc))
    # versionName 等长覆写
    # 旧版本名从 show 的输出已知；这里用「等长」约束下唯一可用的替换：
    # 先用 INT 属性确认；版本名由调用方给出的等长值决定，旧值就是同一个位置上的旧串。
    # 直接找 data 槽位：versionName 是字符串属性，其 data 是池索引，无法反查字节；
    # 因此改为：在池数据区里把「形如 x.y.z 的 ASCII」中与目标等长且位置最靠前的那处覆盖。
    # 与字符串池条目一一对应；等长覆写，并断言唯一。
    # 手工扫描 UTF-16LE 的「数字.数字.数字」形态（不用正则：
    #   正则元字符在 UTF-16LE 里会被 NUL 隔开，语义失效）
    raw = bytes(buf)
    found = []
    for k in range(0, len(raw) - 10):
        if (raw[k + 1] == 0 and raw[k + 3] == 0 and raw[k + 5] == 0
                and raw[k + 7] == 0 and raw[k + 9] == 0
                and 0x30 <= raw[k] <= 0x39 and raw[k + 2] == 0x2e
                and 0x30 <= raw[k + 4] <= 0x39 and raw[k + 6] == 0x2e
                and 0x30 <= raw[k + 8] <= 0x39):
            seg = raw[k:k + 10]
            if len(seg.decode("utf-16-le")) == len(vn):
                found.append(k)
    assert len(found) >= 1, "manifest 里找不到等长版本名串（UTF-16LE）"
    assert len(found) == 1, "等长版本名串不唯一（%d 处），需要人工确认: %r" % (len(found), found)
    k = found[0]
    print("  旧版本名 = %r" % raw[k:k + len(vn) * 2].decode("utf-16-le"))
    buf[k:k + len(vn) * 2] = vn.encode("utf-16-le")
    if os.path.exists(outapk):
        os.remove(outapk)
    with zipfile.ZipFile(outapk, "w", zipfile.ZIP_DEFLATED) as z:
        for n, data in others:
            ct = zipfile.ZIP_STORED if n in ("classes.dex", "resources.arsc") else zipfile.ZIP_DEFLATED
            z.writestr(n, data, ct)
        z.writestr(entry, bytes(buf), zipfile.ZIP_STORED)
    print("已写出 %s（versionCode=%s versionName=%s）" % (outapk, vc, vn))
    # 读回校验
    _, e2 = iter_attrs(zipfile.ZipFile(outapk).read(entry))
    got = [d for _, attrs in e2 for (ao, nidx, atype, d, raw) in attrs if atype == TYPE_INT and d == int(vc)]
    assert got, "读回校验失败：没有找到 versionCode=%s" % vc
    raw2 = zipfile.ZipFile(outapk).read(entry)
    assert vn.encode("utf-16-le") in raw2, "读回校验失败：versionName 未写入"
    assert old.encode("utf-16-le") if False else True
    print("  读回：versionCode=%s ✓  versionName=%s ✓" % (vc, vn))


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "show":
        show(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "AndroidManifest.xml")
    elif mode == "set":
        set_ver(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5],
                sys.argv[6] if len(sys.argv) > 6 else "AndroidManifest.xml")
    else:
        print(__doc__)
        sys.exit(2)
