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
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/PickEntry.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/SoundGate.java" \
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

# ── 判定链纯逻辑测试组（2026-11-12 接线）──
# 为什么单列：这 9 个测试文件此前**从未被 build.sh 跑过**（含 StageTableTest 79 条、
#   TriggerPolicyTest 174 条、VerdictEvidenceTest、DualChainDiffTest 两条判定链互斥基线）——
#   "门禁 1051 条"里有相当一部分是写了不跑，于是判定链缺陷（成功词被文档尾缀吞掉、
#   命中取表序首个、EXHAUSTED 被长推广污染）一直没人发现。
#   tools/check-tests-wired.py 现在强制每个 *Test.java 必须被引用。
# 跳过：TGAS_SKIP_JUDGESET=1
if [ "${TGAS_SKIP_JUDGESET:-0}" != 1 ]; then
  echo "== 判定链纯逻辑测试组 =="
  _gcls="${TMPDIR:-/tmp}/tgas-gcls-$$"
  rm -rf "$_gcls"; mkdir -p "$_gcls"
  _gsrc="$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign"
  _gtests="AnnouncementLengthTest DualChainDiffTest MatchPositionTest ReplyMatchTest StageTableTest TriggerPolicyTest VerdictEvidenceTest StateLeakTest"
  _gj=""
  for _t in $_gtests; do _gj="$_gj $TGAS_SRC/tools/tests/$_t.java"; done
  if javac -nowarn -encoding UTF-8 -d "$_gcls" \
        "$_gsrc/SignLogic.java" "$_gsrc/PickEntry.java" $_gj 2>&1; then
    _gfail=0
    for _t in $_gtests; do
      java -cp "$_gcls" io.github.wlmosv_png.tgautosign.$_t || _gfail=1
    done
    rm -rf "$_gcls"
    if [ "$_gfail" != 0 ]; then
      echo "FATAL: 判定链纯逻辑测试组失败（临时跳过：TGAS_SKIP_JUDGESET=1）" >&2; exit 1
    fi
  else
    rm -rf "$_gcls"
    echo "FATAL: 判定链测试组编译失败（临时跳过：TGAS_SKIP_JUDGESET=1）" >&2; exit 1
  fi
fi

# ── 跨天状态泄漏 / 判定词顺序 单测（2026-11-12 P0，事故驱动）──
# 事故：① 回复尾部带「📝 使用说明」→ 整条判「回复判不出」
#        （真机 7999853294 / 8353929739，prefs 里 last_unk_ 有原始回复）；
#      ② 用户词表里「成功」排在「签到成功」之前，命中取**表序首个** → 强词被泛词顶掉；
#      ③ ev_grade_ / pendcfm_ 没有日期 → 昨天的「判不出」泄漏到今天，且永久不再自动重试。
# 三类缺陷判据完全不同，单独一个 runner 才能看出"哪条不变量被破坏"。
# 跳过：TGAS_SKIP_STATELEAK=1
if [ "${TGAS_SKIP_STATELEAK:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/tests/StateLeakTest.java" ]; then
  echo "== 跨天状态泄漏 / 判定词顺序 单测 =="
  _lcls="${TMPDIR:-/tmp}/tgas-lcls-$$"
  rm -rf "$_lcls"; mkdir -p "$_lcls"
  if javac -nowarn -encoding UTF-8 -d "$_lcls" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java" \
        "$TGAS_SRC/tools/tests/StateLeakTest.java" 2>&1; then
    if ! java -cp "$_lcls" io.github.wlmosv_png.tgautosign.StateLeakTest; then
      rm -rf "$_lcls"
      echo "FATAL: 跨天状态泄漏单测失败（临时跳过：TGAS_SKIP_STATELEAK=1）" >&2; exit 1
    fi
  else
    rm -rf "$_lcls"
    echo "FATAL: 跨天状态泄漏单测编译失败（临时跳过：TGAS_SKIP_STATELEAK=1）" >&2; exit 1
  fi
  rm -rf "$_lcls"
fi

# ── 孤儿键前缀门禁（2026-11-12 P0）──
# 事故：ORPHAN_HEADS 里 "pendcfm_" 排在 "pendcfm_day_" 之前，而孤儿清理是
#   「首个前缀命中即 break」→ 日戳类伴随键每次启动都被删 → 跨天判据全废
#   （真机 6 个目标因此永久卡在「判不出」）。
# 跳过：TGAS_SKIP_ORPHAN=1
if [ "${TGAS_SKIP_ORPHAN:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-orphan-prefix.py" ]; then
  echo "== 孤儿键前缀门禁 =="
  python3 "$TGAS_SRC/tools/check-orphan-prefix.py" "$TGAS_SRC" || {
    echo "FATAL: 孤儿键前缀序被破坏（临时跳过：TGAS_SKIP_ORPHAN=1）" >&2; exit 1; }
fi

# ── 「判不出」升级门槛门禁（2026-11-12 P0，事故驱动）──
# 事故：bot 主动推一条与签到无关的广告 → 判定链判不出 → **无条件**写
#   markResultCode(R_REPLIED_UNK) → 目标钉成「待确认」：进「需处理」+ 当天不再自动重签。
#   用户现象：「还没签到就判断了，一大片要处理」（真机 6 个目标里 4 个如此）。
# 规则：每个写入点必须有 mayPromoteUnknown 门槛，或紧随其上的 // PROMOTE-OK: 理由。
# 跳过：TGAS_SKIP_PROMOTE=1
if [ "${TGAS_SKIP_PROMOTE:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-unknown-promote.py" ]; then
  echo "== 判不出升级门槛门禁 =="
  python3 "$TGAS_SRC/tools/check-unknown-promote.py" "$TGAS_SRC" || {
    echo "FATAL: 有判不出写入点缺门槛（临时跳过：TGAS_SKIP_PROMOTE=1）" >&2; exit 1; }
fi

# ── 测试接线门禁（2026-11-12）──
# 事故：测试写了却没挂进门禁链（或引用编译集里没有的类）→ "测试通过"是假象。
# 跳过：TGAS_SKIP_TESTWIRE=1
if [ "${TGAS_SKIP_TESTWIRE:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-tests-wired.py" ]; then
  echo "== 测试接线门禁 =="
  python3 "$TGAS_SRC/tools/check-tests-wired.py" "$TGAS_SRC" || {
    echo "FATAL: 有测试没被编译/运行（临时跳过：TGAS_SKIP_TESTWIRE=1）" >&2; exit 1; }
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

# ── 写 last_ 的依据门禁（2026-10-23 新增，事故驱动）──
# 事故：hook 打在 TL_messages_sendMessage 的**请求对象**上，早于网络往返与 bot 回复，
#   于是「命令一发出」就写 last_（界面直接显示已签）。实测 10-17~10-22 每天
#   1/3~1/2 的目标"假绿"（无任何 bot 回复依据却被标已签），六道门禁一条没拦住 ——
#   因为没有一道管「写 last_ 的依据」。规则与证据见 tools/check-last-write.py 头注释。
# 跳过：TGAS_SKIP_LASTWRITE=1
if [ "${TGAS_SKIP_LASTWRITE:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-last-write.py" ]; then
  echo "== 写 last_ 依据 =="
  python3 "$TGAS_SRC/tools/check-last-write.py" || {
    echo "FATAL: 有路径把目标标成已签却缺 bot 回复依据（临时跳过：TGAS_SKIP_LASTWRITE=1）" >&2; exit 1; }
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
        "$TGAS_SRC/tools/tests/GroupScopeTest.java" \
        "$TGAS_SRC/tools/tests/SuggestWordTest.java" 2>&1; then
    _jfail=0
    java -cp "$_jcls" io.github.wlmosv_png.tgautosign.JudgeTest || _jfail=1
    java -cp "$_jcls" io.github.wlmosv_png.tgautosign.GroupScopeTest || _jfail=1
    java -cp "$_jcls" io.github.wlmosv_png.tgautosign.SuggestWordTest || _jfail=1
    rm -rf "$_jcls"
    if [ "$_jfail" != 0 ]; then
      echo "FATAL: judge / 群聊作用域 单测失败（临时跳过：TGAS_SKIP_JUDGETEST=1）" >&2; exit 1
    fi
  else
    rm -rf "$_jcls"
    echo "FATAL: judge 单测编译失败（临时跳过：TGAS_SKIP_JUDGETEST=1）" >&2; exit 1
  fi
fi

# ── 判定接线测试组（2026-11-12 接线，清掉最后 5 个欠账）──
# 这 5 个测试此前因编译集未就绪而**从未被执行**（门禁里显式列债）：
#   UnkPoolSourcesTest / LearnFlowTest / SavedMsgPolicyTest / ScopedWireTest / JudgeOnGateTest
# 现在统一用「stubs + android.jar」两段式 classpath：
#   · 编译期 cp = stubs:android.jar —— 既能编过需要 android 类型的类，又优先用桩的签名；
#   · 运行期 cp = out:stubs        —— 纯 Java 环境里真正跑到桩实现。
# 跳过：TGAS_SKIP_WIRESET=1
if [ "${TGAS_SKIP_WIRESET:-0}" != 1 ]; then
  echo "== 判定接线测试组 =="
  _wcls="${TMPDIR:-/tmp}/tgas-wcls-$$"
  rm -rf "$_wcls"; mkdir -p "$_wcls"
  _wsrc="$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign"
  if javac -nowarn -encoding UTF-8 -d "$_wcls" \
        -cp "$TGAS_SRC/tools/tests/stubs:$TGAS_ANDROID_JAR" \
        "$TGAS_SRC"/tools/tests/stubs/android/content/*.java \
        "$TGAS_SRC/tools/tests/persist/FileSharedPreferences.java" \
        "$_wsrc/SignLogic.java" "$_wsrc/Keys.java" "$_wsrc/PrefsStore.java" \
        "$_wsrc/SignStateStore.java" "$_wsrc/SoundGate.java" "$_wsrc/Diag.java" \
        "$_wsrc/DateUtils.java" "$_wsrc/PickEntry.java" \
        "$_wsrc"/judge/*.java "$_wsrc"/savedmsg/*.java \
        "$TGAS_SRC/tools/tests/JudgeTest.java" \
        "$TGAS_SRC/tools/tests/UnkPoolSourcesTest.java" \
        "$TGAS_SRC/tools/tests/LearnFlowTest.java" \
        "$TGAS_SRC/tools/tests/SavedMsgPolicyTest.java" \
        "$TGAS_SRC/tools/tests/ScopedWireTest.java" \
        "$TGAS_SRC/tools/tests/JudgeOnGateTest.java" 2>&1; then
    _wfail=0
    for _t in UnkPoolSourcesTest LearnFlowTest SavedMsgPolicyTest ScopedWireTest JudgeOnGateTest; do
      java -cp "$_wcls:$TGAS_SRC/tools/tests/stubs" io.github.wlmosv_png.tgautosign.$_t || _wfail=1
    done
    rm -rf "$_wcls"
    if [ "$_wfail" != 0 ]; then
      echo "FATAL: 判定接线测试组失败（临时跳过：TGAS_SKIP_WIRESET=1）" >&2; exit 1
    fi
  else
    rm -rf "$_wcls"
    echo "FATAL: 判定接线测试组编译失败（临时跳过：TGAS_SKIP_WIRESET=1）" >&2; exit 1
  fi
fi

# ── 宿主符号层门禁（2026-11-08 新增，为 Telegram 13.2.0 的 R8 变更）──
# 来历：13.2.0 删除了 -keep class org.telegram.messenger.* { *; }，核心类名被改写，
#   模块整条会话/Peer/账号/判定链静默失效 —— 而白名单硬放行还让它显示「已加载」。
#   引入 HostSymbols 后，「名字没了但没人发现」必须能被离线单测拦住：
#   夹具与 android.util.Log 桩只放 tools/tests/ 下，不进 app/src/main/，不参与正式构建。
# 跳过：TGAS_SKIP_SYMBOLTEST=1
if [ "${TGAS_SKIP_SYMBOLTEST:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/tests/HostSymbolsTest.java" ]; then
  echo "== 宿主符号层单测 =="
  _scls="${TMPDIR:-/tmp}/tgas-scls-$$"
  rm -rf "$_scls"; mkdir -p "$_scls"
  _sfiles=""
  for _sf in "$TGAS_SRC"/tools/tests/fixtures/org/telegram/messenger/*.java "$TGAS_SRC"/tools/tests/fixtures/org/telegram/tgnet/*.java; do
    [ -f "$_sf" ] && _sfiles="$_sfiles $_sf"
  done
  if javac -nowarn -encoding UTF-8 -d "$_scls" \
        "$TGAS_SRC/tools/tests/stubs/android/util/Log.java" \
        $_sfiles \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/HostSymbols.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/host/DexReader.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/host/HostLocator.java" \
        "$TGAS_SRC/tools/tests/HostSymbolsTest.java" 2>&1; then
    if ! java -cp "$_scls" io.github.wlmosv_png.tgautosign.HostSymbolsTest; then
      rm -rf "$_scls"
      echo "FATAL: 宿主符号层单测失败（临时跳过：TGAS_SKIP_SYMBOLTEST=1）" >&2; exit 1
    fi
  else
    rm -rf "$_scls"
    echo "FATAL: 宿主符号层单测编译失败（临时跳过：TGAS_SKIP_SYMBOLTEST=1）" >&2; exit 1
  fi
  rm -rf "$_scls"
fi

# ── 宿主符号自愈层单测（2026-11-12）：DexReader + HostLocator ──
# 为什么单独一道：模块不再只靠「逐版本硬编码映射表」，而是能在运行时自己解析宿主 dex 把类找回来。
# 这条链一旦写错（dex 偏移算错、判据不唯一）会**静默失效**，必须拿真实 APK 端到端回归。
# APK 来源（按序）：$TGAS_HOST_APK → $TGAS_BUILD_ROOT/fixtures/host-test.apk；
#   都没有则只做编译校验（打印用法后退出 0）。
# 跳过：TGAS_SKIP_HOSTLOCATETEST=1
if [ "${TGAS_SKIP_HOSTLOCATETEST:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/tests/HostLocateTest.java" ]; then
  echo "== 宿主符号自愈层单测（DexReader / HostLocator）=="
  _hcls="${TMPDIR:-/tmp}/tgas-hcls-$$"
  rm -rf "$_hcls"; mkdir -p "$_hcls"
  if javac -nowarn -encoding UTF-8 -d "$_hcls" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/host/DexReader.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/host/HostLocator.java" \
        "$TGAS_SRC/tools/tests/HostLocateTest.java" 2>&1; then
    _hapk="${TGAS_HOST_APK:-}"
    if [ -z "$_hapk" ] && [ -f "$TGAS_BUILD_ROOT/fixtures/host-test.apk" ]; then
      _hapk="$TGAS_BUILD_ROOT/fixtures/host-test.apk"
    fi
    if [ -n "$_hapk" ] && [ -f "$_hapk" ]; then
      if ! java -cp "$_hcls" io.github.wlmosv_png.tgautosign.HostLocateTest "$_hapk"; then
        rm -rf "$_hcls"
        echo "FATAL: 宿主符号自愈层单测失败（临时跳过：TGAS_SKIP_HOSTLOCATETEST=1）" >&2
        exit 1
      fi
    else
      echo "  （跳过硬验证：未提供宿主 APK —— 设 TGAS_HOST_APK 或放 fixtures/host-test.apk）"
      java -cp "$_hcls" io.github.wlmosv_png.tgautosign.HostLocateTest || true
    fi
  else
    rm -rf "$_hcls"
    echo "FATAL: 宿主符号自愈层单测编译失败（临时跳过：TGAS_SKIP_HOSTLOCATETEST=1）" >&2
    exit 1
  fi
  rm -rf "$_hcls"
fi

# ── 接收链观测单测（2026-11-08 F8）：真跑 Diag / InboundGate 纯逻辑 ──
# 为什么单独一道：接收链的判断全在 TGAutoSignCore 的实例方法里（依赖 Android），
#   纯 JVM 跑不起来；F8 把它们抽成无依赖纯函数，这道门禁**真正执行**这些函数。
# 跳过：TGAS_SKIP_RECVCHAIN=1
if [ "${TGAS_SKIP_RECVCHAIN:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/tests/ReceiveChainTest.java" ]; then
  echo "== 接收链观测单测 =="
  _rcls="${TMPDIR:-/tmp}/tgas-rcls-$$"
  rm -rf "$_rcls"; mkdir -p "$_rcls"
  if javac -nowarn -encoding UTF-8 -d "$_rcls" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/Diag.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/InboundGate.java" \
        "$TGAS_SRC/tools/tests/ReceiveChainTest.java" 2>&1; then
    if ! java -cp "$_rcls" io.github.wlmosv_png.tgautosign.ReceiveChainTest; then
      rm -rf "$_rcls"
      echo "FATAL: 接收链观测单测失败（临时跳过：TGAS_SKIP_RECVCHAIN=1）" >&2; exit 1
    fi
  else
    rm -rf "$_rcls"
    echo "FATAL: 接收链观测单测编译失败（临时跳过：TGAS_SKIP_RECVCHAIN=1）" >&2; exit 1
  fi
  rm -rf "$_rcls"
fi

# ── 待处理状态机门禁（2026-11-08 P0/P1 配套）──
# 来历：界面上「未回复」由"距发送超过 90 秒"推导而来 —— 不崩、不报错、日志里看不出，
#   只能靠静态检查把它钉死。P1-P8 见 tools/check-pending-state.py 头注释。
# 跳过：TGAS_SKIP_PENDINGGATE=1
if [ "${TGAS_SKIP_PENDINGGATE:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-pending-state.py" ]; then
  echo "== 待处理状态机 =="
  python3 "$TGAS_SRC/tools/check-pending-state.py" || {
    echo "FATAL: 待处理状态机门禁未通过（临时跳过：TGAS_SKIP_PENDINGGATE=1）" >&2; exit 1; }
fi

# ── 待处理状态机单测（纯函数 + 内存 PrefsStore）──
# 跳过：TGAS_SKIP_PENDINGTEST=1
if [ "${TGAS_SKIP_PENDINGTEST:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/tests/PendingStateTest.java" ]; then
  echo "== 待处理状态机单测 =="
  _pcls="${TMPDIR:-/tmp}/tgas-pcls-$$"
  rm -rf "$_pcls"; mkdir -p "$_pcls"
  if javac -nowarn -encoding UTF-8 -d "$_pcls" \
        "$TGAS_SRC/tools/tests/stubs/android/content/SharedPreferences.java" \
        "$TGAS_SRC/tools/tests/stubs/android/util/Log.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/DateUtils.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/Keys.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/SignLogic.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/PrefsStore.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/SignStateStore.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/SoundGate.java" \
        "$TGAS_SRC/app/src/main/java/io/github/wlmosv_png/tgautosign/Diag.java" \
        "$TGAS_SRC/tools/tests/PendingStateTest.java" 2>&1; then
    if ! java -cp "$_pcls" io.github.wlmosv_png.tgautosign.PendingStateTest; then
      rm -rf "$_pcls"
      echo "FATAL: 待处理状态机单测失败（临时跳过：TGAS_SKIP_PENDINGTEST=1）" >&2; exit 1
    fi
  else
    rm -rf "$_pcls"
    echo "FATAL: 待处理状态机单测编译失败（临时跳过：TGAS_SKIP_PENDINGTEST=1）" >&2; exit 1
  fi
  rm -rf "$_pcls"
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

# ── sent_at_ 新鲜度门禁（2026-10-16 新增，事故驱动）──
# 事故：roundProblemsOf 读 sent_at_ 时只判「够不够久」、没判「是不是今天」，
#   而 sent_at_ 是**裸毫秒、跨天不清** —— 几天前的残留会在第二天被无条件判成
#   「未回复」，用户看到「全部目标待处理 / 已发出 4999 分钟」，而当天压根没发过。
#   这类错误不报错、不崩溃、日志里一行都没有，只能靠门禁拦。
# 判定：每处 getLong(sent_at_) 的邻近上下文必须出现「今天」判据（或 ≤30 分钟短窗口）。
# 跳过：TGAS_SKIP_SENTAT=1
if [ "${TGAS_SKIP_SENTAT:-0}" != 1 ] && [ -f "$TGAS_SRC/tools/check-sentat-freshness.py" ]; then
  echo "== sent_at_ 新鲜度 =="
  python3 "$TGAS_SRC/tools/check-sentat-freshness.py" "$TGAS_SRC" || {
    echo "FATAL: 有 sent_at_ 读取缺「今天」判据，跨天残留会被误判（临时跳过：TGAS_SKIP_SENTAT=1）" >&2; exit 1; }
fi

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
# 2026-11-12：段头以「生成器实际输出 + README 实际内容」为准。
#   生成器 tools/gen-readme-changelog.py 输出「## 更新日志 · Changelog」，两个 README 也是；
#   这里原先却要求段头带 📜 → 本门禁**永远不可能通过**（红在既有基线上，没人发现）。
m = re.search(r"^##\s*(?:📜\s*)?更新日志[^\n]*\n", s, re.M)
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

