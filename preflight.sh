#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHECK_DEVICE=0
MIN_VECTOR_VERSION_CODE="${MIN_VECTOR_VERSION_CODE:-3043}"
CLI="${CLI:-/data/adb/lspd/cli}"

if [[ "${1:-}" == "--device" ]]; then
  CHECK_DEVICE=1
fi

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

warn() {
  printf 'WARN: %s\n' "$*" >&2
}

ok() {
  printf 'OK: %s\n' "$*"
}

java_major() {
  local java_bin="$1"
  local line
  line="$("$java_bin" -version 2>&1 | head -n 1 || true)"

  if [[ "$line" =~ \"1\.([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  elif [[ "$line" =~ \"([0-9]+)(\.|-) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  elif [[ "$line" =~ version[[:space:]]+\"([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  fi
}

is_jdk21_home() {
  local home="$1"
  [[ -n "$home" && -x "$home/bin/java" && "$(java_major "$home/bin/java")" == "21" ]]
}

find_jdk21() {
  local home

  for home in "${JDK_HOME:-}" "${JAVA_HOME:-}"; do
    if is_jdk21_home "$home"; then
      printf '%s\n' "$home"
      return 0
    fi
  done

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if is_jdk21_home "$home"; then
      printf '%s\n' "$home"
      return 0
    fi
  fi

  for home in \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/temurin@21/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/temurin@21/libexec/openjdk.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/*/Contents/Home \
    "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if is_jdk21_home "$home"; then
      printf '%s\n' "$home"
      return 0
    fi
  done

  if command -v java >/dev/null 2>&1 && [[ "$(java_major "$(command -v java)")" == "21" ]]; then
    java -XshowSettings:properties -version 2>&1 \
      | sed -nE 's/^[[:space:]]*java.home = (.*)$/\1/p' \
      | head -n 1
    return 0
  fi

  return 1
}

sdk_from_local_properties() {
  local file="$PROJECT_DIR/local.properties"
  [[ -f "$file" ]] || return 1
  sed -nE 's/^sdk\.dir=(.*)$/\1/p' "$file" | head -n 1
}

is_android_sdk() {
  local dir="$1"
  [[ -n "$dir" && -d "$dir/platforms" && -d "$dir/build-tools" ]]
}

find_android_sdk() {
  local dir
  for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$(sdk_from_local_properties || true)" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$HOME/Android/sdk"; do
    if is_android_sdk "$dir"; then
      printf '%s\n' "$dir"
      return 0
    fi
  done
  return 1
}

JDK21_HOME="$(find_jdk21 || true)"
[[ -n "$JDK21_HOME" ]] || fail "JDK 21 not found. Set JDK_HOME or JAVA_HOME to a JDK 21 install."
ok "JDK 21: $JDK21_HOME"

ANDROID_SDK_HOME="$(find_android_sdk || true)"
[[ -n "$ANDROID_SDK_HOME" ]] || fail "Android SDK not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or local.properties sdk.dir."
ok "Android SDK: $ANDROID_SDK_HOME"

if [[ -x "$PROJECT_DIR/gradlew" ]]; then
  ok "Gradle wrapper: $PROJECT_DIR/gradlew"
elif [[ -n "${GRADLEW:-}" && -x "$GRADLEW" ]]; then
  ok "Gradle wrapper from GRADLEW: $GRADLEW"
elif command -v gradle >/dev/null 2>&1; then
  ok "Global gradle: $(command -v gradle)"
else
  fail "No Gradle wrapper/global gradle found. Add Gradle wrapper or set GRADLEW=/path/to/gradlew."
fi

if [[ "$CHECK_DEVICE" == "0" ]]; then
  warn "Device checks skipped. Run ./preflight.sh --device to check adb/root/Vector CLI."
  exit 0
fi

command -v adb >/dev/null 2>&1 || fail "adb not found on PATH."
adb get-state >/dev/null 2>&1 || fail "No authorized adb device."
ok "adb device connected"

adb shell su -c id >/dev/null 2>&1 || fail "adb root through su is unavailable."
ok "root su available"

STATUS="$(adb shell su -c "$CLI status --json" 2>/dev/null || adb shell su -c "$CLI status" 2>/dev/null || true)"
VERSION_CODE="$(printf '%s\n' "$STATUS" | sed -nE 's/.*"Version Code"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p; s/.*Version Code:[[:space:]]*([0-9]+).*/\1/p' | head -n 1)"

if [[ -z "$VERSION_CODE" ]]; then
  warn "Vector CLI unavailable. Automatic enable/scope will be skipped; use Vector/LSPosed Manager manually."
elif (( VERSION_CODE < MIN_VECTOR_VERSION_CODE )); then
  warn "Vector version code $VERSION_CODE < $MIN_VECTOR_VERSION_CODE. Automatic enable/scope will be skipped."
else
  ok "Vector CLI version code: $VERSION_CODE"
fi
