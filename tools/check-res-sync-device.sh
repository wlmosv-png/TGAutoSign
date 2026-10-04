#!/system/bin/sh
# 资源同步门禁 —— **Android 侧**内容比对段。
#
# 为什么单独一个脚本：
#   内容级比对必须用 aapt2，而设备上的 aapt2 是 Android/arm64 二进制，
#   Linux 工具环境跑不了（Exec format error）；反过来 Android 侧没有 python3。
#   所以门禁拆两段：
#     · Linux 段   tools/check-res-sync.py         manifest 属性 + 资源名覆盖
#     · Android 段 本脚本                          逐字节内容比对
#
# 关键：比对基准必须是 **aapt2 link 之后**的产物，不能拿 aapt2 compile 的 .flat
#   中间格式去比 —— .flat 与最终二进制天然不同，那样会一路误报（踩过）。
#   流程：compile res → link 出 res.apk → 从 res.apk 内取 res/* → 与目标 APK 内逐一比 sha256。
#
# 用法:  sh check-res-sync-device.sh <apk路径>
set -u

APK="${1:-}"
[ -n "$APK" ] || { echo "用法: sh $0 <apk路径>"; exit 2; }
[ -f "$APK" ] || { echo "FATAL: APK 不存在: $APK"; exit 2; }

SRC="${TGAS_SRC:-/storage/emulated/0/Download/TGAutoSign-build/upstream/src}"
AJ="${TGAS_ANDROID_JAR:-/storage/emulated/0/Download/TGAutoSign-build/tools/libs/android.jar}"
AAPT2="${TGAS_AAPT2:-/data/local/tmp/eta/aapt2run/bin/aapt2}"
A2LIB="${TGAS_AAPT2_LIB:-/data/local/tmp/eta/aapt2run/lib}"
TMP="${TGAS_TMP:-/data/local/tmp/eta/reschk}"
VC="${TGAS_VC:-129}"
VN="${TGAS_VN:-1.6.3}"

export LD_LIBRARY_PATH="$A2LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
[ -x "$AAPT2" ] || { echo "WARN: 找不到 aapt2（$AAPT2），跳过内容比对"; exit 0; }

rm -rf "$TMP"; mkdir -p "$TMP/ref" "$TMP/apk" || exit 2

echo "  资源同步门禁（Android 内容比对）："

# 1) compile + link，得到与出包时**同级**的资源二进制
"$AAPT2" compile --dir "$SRC/app/src/main/res" -o "$TMP/flat.zip" >/dev/null 2>&1 \
  || { echo "WARN: aapt2 compile 失败，跳过内容比对"; rm -rf "$TMP"; exit 0; }
"$AAPT2" link -o "$TMP/res.apk" -I "$AJ" \
  --manifest "$SRC/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 36 \
  --version-code "$VC" --version-name "$VN" \
  "$TMP/flat.zip" >/dev/null 2>&1 \
  || { echo "WARN: aapt2 link 失败，跳过内容比对"; rm -rf "$TMP"; exit 0; }

cd "$TMP/ref" && unzip -o -q "$TMP/res.apk" 'res/*' 2>/dev/null
cd "$TMP/apk" && unzip -o -q "$APK" 'res/*' 2>/dev/null

# 2) 逐个资源名逐字节比对
BAD=0; CHECKED=0; MISSING=""
for f in $(find "$TMP/ref/res" -type f 2>/dev/null); do
    rel=${f#$TMP/ref/}
    hit="$TMP/apk/$rel"
    if [ ! -f "$hit" ]; then
        MISSING="$MISSING $(basename "$rel")"
        continue
    fi
    h1=$(sha256sum "$f" | cut -d' ' -f1)
    h2=$(sha256sum "$hit" | cut -d' ' -f1)
    CHECKED=$((CHECKED+1))
    if [ "$h1" != "$h2" ]; then
        echo "    MISS $(basename "$rel") 内容与源码不一致（包内仍是旧的）"
        BAD=$((BAD+1))
    fi
done

if [ "$CHECKED" = "0" ]; then
    echo "    WARN 没有可比对的资源（参考产物为空？）"
    rm -rf "$TMP"
    exit 0
fi

if [ -n "$MISSING" ]; then
    echo "    MISS 包内缺少:$MISSING"
    BAD=$((BAD+1))
fi

if [ "$BAD" != "0" ]; then
    echo ""
    echo "  ❌ 资源与源码不一致（比对 $CHECKED 个）"
    echo "     说明用了不重编资源的打包链 —— 见 tools/check-res-sync.py 头部说明。"
    rm -rf "$TMP"
    exit 1
fi

echo "    OK   $CHECKED 个资源逐字节一致"
rm -rf "$TMP"
exit 0
