#!/bin/sh
# 全量 javac + r8 D8 出 classes.dex。不碰资源（资源由 donor APK 提供）。
# 用法: ./build.sh                产物 $TGAS_WORK/dex/classes.dex
#       TGAS_SKIP_D8=1 ./build.sh 只做编译校验，不出 dex
set -eu
cd "$(dirname "$0")"
. ./env.sh
TGAS_EXTRA_CP="${TGAS_EXTRA_CP:-}"
TGAS_EXTRA_LIBS="${TGAS_EXTRA_LIBS:-}"
TGAS_BUNDLE="${TGAS_BUNDLE:-}"

SRC_J="$TGAS_SRC/app/src/main/java"
tgas_need "$TGAS_SRC"              "源码工作副本"
tgas_need "$SRC_J"                 "源码目录"
tgas_need "$TGAS_ANDROID_JAR"      "android.jar"
tgas_need "$TGAS_LIBXPOSED"        "libxposed api jar"
tgas_need "$TGAS_R8"               "r8.jar"

# 资源守卫：改了 res/ 或 resources.arsc 就别走本机链
if [ -n "${TGAS_BASE_REF:-}" ] && command -v git >/dev/null 2>&1 && [ -d "$TGAS_SRC/.git" ]; then
  changed=$(cd "$TGAS_SRC" && git diff --name-only "$TGAS_BASE_REF"...HEAD -- app/src/main/res app/src/main/AndroidManifest.xml 2>/dev/null || true)
  if [ -n "$changed" ]; then
    echo "FATAL: 相对 $TGAS_BASE_REF 有资源/清单改动，本机手工链无法重编资源表，交给有 aapt2 的环境：" >&2
    echo "$changed" | sed 's/^/  /' >&2
    exit 1
  fi
  echo "资源守卫通过（$TGAS_BASE_REF 起 res/manifest 无改动）"
else
  echo "WARN: 未设 TGAS_BASE_REF，跳过资源守卫 —— 请自己确认没新增资源" >&2
fi

# ── i18n 门禁：UI 文案漏翻译 / 字典缺条目 → 构建失败（方案见 i18n/README.md）──
if [ "${TGAS_SKIP_I18N:-0}" != 1 ] && [ -f "$TGAS_SRC/i18n/check_i18n.py" ]; then
  echo "== i18n 检查 =="
  if command -v python3 >/dev/null 2>&1; then
    ( cd "$TGAS_SRC" && python3 i18n/check_i18n.py ) || {
      echo "FATAL: i18n 检查未通过（临时跳过：TGAS_SKIP_I18N=1）" >&2; exit 1; }
  else
    echo "WARN: 无 python3，跳过 i18n 检查" >&2
  fi
fi

mkdir -p "$TGAS_WORK"
rm -rf "$TGAS_WORK/cls" "$TGAS_WORK/dex"; mkdir -p "$TGAS_WORK/cls" "$TGAS_WORK/dex"
find "$SRC_J" -name '*.java' > "$TGAS_WORK/files.txt"
echo "== javac --release $TGAS_JAVA_RELEASE ($(wc -l <"$TGAS_WORK/files.txt") 个源文件) =="
javac --release "$TGAS_JAVA_RELEASE" -nowarn -encoding UTF-8 \
      -cp "$TGAS_ANDROID_JAR:$TGAS_LIBXPOSED$TGAS_EXTRA_CP" -d "$TGAS_WORK/cls" @"$TGAS_WORK/files.txt"
# v1.4.3：把随包携带的 AIDL 库类并入 classes（必须在收集 .class 清单之前）
if [ -n "${TGAS_BUNDLE:-}" ]; then
  for j in $TGAS_BUNDLE; do ( cd "$TGAS_WORK/cls" && unzip -o -q "$j" '*.class' ); done
fi
find "$TGAS_WORK/cls" -name '*.class' > "$TGAS_WORK/cls.txt"
echo "   classes: $(wc -l <"$TGAS_WORK/cls.txt")"

[ "${TGAS_SKIP_D8:-0}" = 1 ] && { echo "COMPILE_CHECK_OK（跳过 D8）"; exit 0; }

tgas_need "$TGAS_R8" "r8.jar"
echo "== D8 (min-api $TGAS_MIN_API) =="
java -cp "$TGAS_R8" com.android.tools.r8.D8 --release --min-api "$TGAS_MIN_API" \
     --lib "$TGAS_ANDROID_JAR" --lib "$TGAS_LIBXPOSED" $TGAS_EXTRA_LIBS \
     --output "$TGAS_WORK/dex" @"$TGAS_WORK/cls.txt" 2>&1 | grep -viE 'WARNING|Warning:' || true
DEX="$TGAS_WORK/dex/classes.dex"
tgas_need "$DEX" "D8 产物"
echo "BUILT $DEX $(wc -c <"$DEX") B"
echo "下一步: ./repack.py \$TGAS_DONOR $DEX ${TGAS_MANIFEST_BIN:+$TGAS_MANIFEST_BIN }$TGAS_KIT/out.unsigned.apk"

