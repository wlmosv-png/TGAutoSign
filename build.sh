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
# 2026-10-04 修订：旧版只有在设了 TGAS_BASE_REF 时才检查，否则只打 WARN ——
#   实际出包时从没人设过它，守卫等于不存在。后果：改了图标与模块描述，
#   却一直走「只换 dex」的链，res/ 与 resources.arsc 原样沿用 donor，
#   图标与描述**根本没进包**，而且全程没有任何报错（用户实测反馈）。
#   现在无论如何都要查一遍：先按基线 diff（若给了），再叠加「工作区未提交改动」。
#   TGAS_SKIP_RES_GUARD=1 可临时跳过（不推荐）。
if [ "${TGAS_SKIP_RES_GUARD:-0}" = 1 ]; then
  echo "WARN: 已按 TGAS_SKIP_RES_GUARD=1 跳过资源守卫" >&2
elif command -v git >/dev/null 2>&1 && [ -d "$TGAS_SRC/.git" ]; then
  res_paths="app/src/main/res app/src/main/AndroidManifest.xml"
  changed=""
  if [ -n "${TGAS_BASE_REF:-}" ]; then
    changed=$(cd "$TGAS_SRC" && git diff --name-only "$TGAS_BASE_REF"...HEAD -- $res_paths 2>/dev/null || true)
  fi
  # 叠加工作区未提交改动（含未跟踪的新资源）
  dirty=$(cd "$TGAS_SRC" && (git status --porcelain -- $res_paths 2>/dev/null || true) | awk '{print $NF}')
  if [ -n "$dirty" ]; then
    changed=$(printf '%s\n%s\n' "$changed" "$dirty" | sed '/^$/d' | sort -u)
  fi
  if [ -n "$changed" ]; then
    echo "FATAL: 存在 res/ 或 AndroidManifest 改动，本机手工链**无法重编资源表**：" >&2
    echo "$changed" | sed 's/^/  /' >&2
    echo "" >&2
    echo "  请改走资源重编链（有 aapt2 的环境）：" >&2
    echo "    aapt2 compile --dir app/src/main/res -o flat.zip" >&2
    echo "    aapt2 link --manifest app/src/main/AndroidManifest.xml -I <android.jar> \\" >&2
    echo "             --min-sdk-version 26 --target-sdk-version 36 \\" >&2
    echo "             --version-code N --version-name X.Y.Z -o res.apk flat.zip" >&2
    echo "    合流 res.apk 内的 res/ AndroidManifest.xml resources.arsc 进 repack/" >&2
    echo "    python3 pack_repack.py   # 整目录打包 + 4 字节对齐" >&2
    echo "" >&2
    echo "  出包后必须过产物校验：" >&2
    echo "    python3 tools/check-res-sync.py <apk>            # manifest 属性 + 资源名" >&2
    echo "    sh tools/check-res-sync-device.sh <apk>          # 逐字节内容比对（Android 侧）" >&2
    exit 1
  fi
  echo "资源守卫通过（res/manifest 无改动）"
else
  echo "WARN: 无 git，跳过资源守卫 —— 请自行确认 res/ 与 manifest 无改动" >&2
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
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/UpdateLogic.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/DateUtils.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/StatsData.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/StatsSnapshot.java" \
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

# ── 界面文案双语门禁：Lang.tr/tf 的中文串必须有 EN 映射 ──
# 为什么单列一道（2026-09-30 定）：check-chlog-i18n 只管 CHANGELOG，
# 而日志改造一口气新增 40+ 条界面文案全部漏译 —— 英文设备上显示中文，
# 不报错、不崩溃，只能靠人眼发现。这与「更新日志漏译」是同一类问题，
# 但覆盖面完全不同，故独立成门。
# 跳过：TGAS_SKIP_UI18N=1
if [ "${TGAS_SKIP_UI18N:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-ui-i18n.py" ]; then
  echo "== 界面文案双语检查 =="
  python3 "$TGAS_SRC/tools/check-ui-i18n.py" || {
    echo "FATAL: 界面文案英文映射不完整（临时跳过：TGAS_SKIP_UI18N=1）" >&2; exit 1; }
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

# ── judge 子系统单测门禁（2026-10-06 补做第 5 项）──
# 来历：judge/UnkPool 依赖 android.content.SharedPreferences，SignLogicTest 的
#   最小编译集里没有它，所以这块一直没测试。这里用 tools/tests/stubs/ 的桩 +
#   内存版假实现，让 UnkPool 在纯 Java 环境也能被真正测到。
# 注意：桩只放 tools/tests/ 下，**不进 app/src/main/**，不参与正式构建。
# 跳过：TGAS_SKIP_JUDGETEST=1
if [ "${TGAS_SKIP_JUDGETEST:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/tests/JudgeTest.java" ]; then
  echo "== judge 子系统单测（含群聊/作用域）=="
  _jcls="${TMPDIR:-/tmp}/tgas-jcls-$$"
  rm -rf "$_jcls"; mkdir -p "$_jcls"
  _jcommon="\
        $TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/DateUtils.java \
        $TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java \
        $TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/judge/UnkPool.java \
        $TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/judge/ReplyNormalizer.java"
  _jstub="\
        $TGAS_SRC/tools/tests/stubs/android/content/SharedPreferences.java \
        $TGAS_SRC/tools/tests/stubs/android/content/Context.java"
  if javac -nowarn -encoding UTF-8 -d "$_jcls" \
        $_jstub $_jcommon \
        "$TGAS_SRC/tools/tests/JudgeTest.java" \
        "$TGAS_SRC/tools/tests/GroupScopeTest.java" 2>&1; then
    _jfail=0
    java -cp "$_jcls" io.github.wlmosv_png.tgautosign.JudgeTest || _jfail=1
    java -cp "$_jcls" io.github.wlmosv_png.tgautosign.GroupScopeTest || _jfail=1
    rm -rf "$_jcls"
    if [ "$_jfail" != 0 ]; then
      echo "FATAL: judge / 群聊作用域 单测失败（临时跳过：TGAS_SKIP_JUDGETEST=1）" >&2; exit 1
    fi
  else
    rm -rf "$_jcls"
    echo "FATAL: judge 单测编译失败（临时跳过：TGAS_SKIP_JUDGETEST=1）" >&2; exit 1
  fi
fi

# ── 宿主作用域一致性门禁（2026-10-06 新增，P1）──
# 来历：Turrit（org.telegram.group）曾在 Hosts.KNOWN 与 scope.list 里都有、
#   module.prop 的 scope= 缺失，当时没有任何门禁能发现 ——
#   用户装完才发现 Turrit 不在作用域里。三处任一不同步都会造成
#   「声明支持但实际不注入」。这里做集合比对，不一致就失败并指出差异。
# 跳过：TGAS_SKIP_SCOPE=1
if [ "${TGAS_SKIP_SCOPE:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-scope-sync.py" ]; then
  echo "== 宿主作用域一致性 =="
  python3 "$TGAS_SRC/tools/check-scope-sync.py" "$TGAS_SRC" || {
    echo "FATAL: Hosts.KNOWN / scope.list / module.prop 的宿主集合不一致（临时跳过：TGAS_SKIP_SCOPE=1）" >&2; exit 1; }
fi

# ── README 同步门禁：README 的更新日志段必须与 CHANGELOG.md 一致 ──
# 来历：README 的更新日志段是从 CHANGELOG 生成的「手写副本」，2026-09-23 写脚本
# 就是为了治它跑偏；结果 1.6.0 发布时又忘了跑 —— 模块仓 README 停在 1.5.8，
# 用户从 LSPosed 进来看到的是旧版。这次把「跑没跑」变成构建时能拦下来的事。
# 判定：用生成器重算段落，与 README 里现有段落逐字节比对，不一致即失败。
# 跳过：TGAS_SKIP_READMESYNC=1
if [ "${TGAS_SKIP_READMESYNC:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/gen-readme-changelog.py" ]; then
  echo "== README 更新日志同步 =="
  _rdtmp="${TMPDIR:-/tmp}/tgas-rd-$$"
  rm -rf "$_rdtmp"; mkdir -p "$_rdtmp"
  if ( cd "$TGAS_SRC" && python3 tools/gen-readme-changelog.py --versions 2 ) > "$_rdtmp/zh.new" 2>/dev/null \
     && ( cd "$TGAS_SRC" && python3 tools/gen-readme-changelog.py --versions 2 --lang en ) > "$_rdtmp/en.new" 2>/dev/null; then
    _bad=0
    for pair in "README.md:zh" "README.en.md:en"; do
      _f="${pair%%:*}"; _lang="${pair##*:}"
      [ -f "$TGAS_SRC/$_f" ] || continue
      python3 - "$TGAS_SRC/$_f" "$_rdtmp/$_lang.new" <<'PYEOF' || _bad=1
import io, re, sys
path, gen = sys.argv[1], sys.argv[2]
s = io.open(path, encoding="utf-8").read()
new = io.open(gen, encoding="utf-8").read().rstrip("\n")
m = re.search(r"^##\s*📜[^\n]*\n", s, re.M)
if not m:
    print("  FATAL: %s 里没有更新日志段" % path); sys.exit(1)
end = m.end() + re.search(r"^##\s", s[m.end():], re.M).start()
cur = s[m.start():end].rstrip("\n")
if cur != new:
    print("  FATAL: %s 的更新日志段与 CHANGELOG 不一致" % path)
    print("         跑: python3 tools/gen-readme-changelog.py [--lang en] 并替换该段")
    sys.exit(1)
print("  OK   %s" % path)
PYEOF
    done
    if [ "$_bad" = 1 ]; then
      rm -rf "$_rdtmp"
      echo "FATAL: README 更新日志段未同步（临时跳过：TGAS_SKIP_READMESYNC=1）" >&2
      exit 1
    fi
  else
    echo "WARN: 生成 README 段落失败，跳过该门禁" >&2
  fi
  rm -rf "$_rdtmp"
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

