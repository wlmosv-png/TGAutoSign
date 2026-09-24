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

# ── 纯逻辑单测门禁：SignLogic 回归测试（零依赖，javac+java 直接跑）──
# 为什么有这道：Core 8400+ 行没有一行测试，2026-09-23 一天内三个线上事故
# （按钮学习默认值、账号越界、捕获无效）全是「代码里有、从没验证过行为」——
# i18n/版本/双语三道门禁一条都拦不住。纯逻辑抽进 SignLogic 后在这里跑断言。
# 跳过：TGAS_SKIP_TESTS=1
if [ "${TGAS_SKIP_TESTS:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/tests/SignLogicTest.java" ]; then
  echo "== 纯逻辑单测 =="
  _tcls="${TMPDIR:-/tmp}/tgas-tcls-$$"
  rm -rf "$_tcls"; mkdir -p "$_tcls"
  if javac -nowarn -encoding UTF-8 -d "$_tcls" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java" \
        "$TGAS_SRC/tools/tests/SignLogicTest.java" 2>&1; then
    if ! java -cp "$_tcls" io.github.wlmosv_png.tgautosign.SignLogicTest; then
      rm -rf "$_tcls"
      echo "FATAL: 纯逻辑单测失败（临时跳过：TGAS_SKIP_TESTS=1）" >&2; exit 1
    fi
  else
    rm -rf "$_tcls"
    echo "FATAL: 单测编译失败（临时跳过：TGAS_SKIP_TESTS=1）" >&2; exit 1
  fi
  rm -rf "$_tcls"
fi

# ── 更新日志双语门禁：最新版本段中英条目必须配对（写不全就出不了包）──
# 模块界面已国际化，更新日志却长期只有中文；这类"忘记写"不会报错、只会静默存在。
# 判定：英文条译 >= 中文条目 - 1（允许 1 条豁免）。跳过：TGAS_SKIP_CHLOG=1
if [ "${TGAS_SKIP_CHLOG:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-chlog-i18n.py" ]; then
  echo "== 更新日志双语检查 =="
  python3 "$TGAS_SRC/tools/check-chlog-i18n.py" || {
    echo "FATAL: 更新日志英文不完整（临时跳过：TGAS_SKIP_CHLOG=1）" >&2; exit 1; }
fi

# ── 版本四查：build.gradle / UpdateChecker / module.prop / CHANGELOG 必须同版本 ──
if [ "${TGAS_SKIP_VERCHK:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-versions.py" ]; then
  echo "== 版本四查 =="
  python3 "$TGAS_SRC/tools/check-versions.py" || {
    echo "FATAL: 版本号四查未通过（临时跳过：TGAS_SKIP_VERCHK=1）" >&2; exit 1; }
fi

# ── 接线门禁：新增的方法/菜单入口真的被调用了吗 ──
# 来历：v1.4.1「复制目标到其它账号」界面方法写好了、菜单项也插了，但 runAction 漏了
# 派发分支；dex 字符串自检全部命中，装上却看不到入口。只看"字符串在不在"是不够的。
# 跳过：TGAS_SKIP_WIRING=1
if [ "${TGAS_SKIP_WIRING:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-wiring.py" ]; then
  echo "== 接线自检 =="
  python3 "$TGAS_SRC/tools/check-wiring.py" "$TGAS_SRC" || {
    echo "FATAL: 有孤儿方法或菜单项没派发（临时跳过：TGAS_SKIP_WIRING=1）" >&2; exit 1; }
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

