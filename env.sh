#!/bin/sh
# 本机出包环境（build.sh 会 source 本文件）
TGAS_SRC="$(cd "$(dirname "$0")" && pwd)"
TGAS_BUILD_ROOT="/storage/emulated/0/Download/TGAutoSign-build"
TGAS_WORK="${TGAS_WORK:-/data/local/tmp/eta/tgbuild}"
TGAS_ANDROID_JAR="$TGAS_BUILD_ROOT/tools/libs/android.jar"
TGAS_LIBXPOSED="$TGAS_BUILD_ROOT/tools/libs/libxposed-api-102.jar"
TGAS_R8="$TGAS_BUILD_ROOT/tools/libs/android-16/lib/d8.jar"
TGAS_APKSIGNER="$TGAS_BUILD_ROOT/tools/libs/android-16/lib/apksigner.jar"
TGAS_JAVA_RELEASE="17"
TGAS_MIN_API="26"
TGAS_DONOR="$TGAS_BUILD_ROOT/TGAutoSign-v1.7.8-178-release.apk"
TGAS_KIT="$TGAS_WORK"
tgas_need() { [ -e "$1" ] || { echo "FATAL: 缺 $2: $1" >&2; exit 1; }; }
