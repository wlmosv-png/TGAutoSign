#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
TASK="${1:-:app:assembleDebug}"

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

java_home_from_bin() {
  "$1" -XshowSettings:properties -version 2>&1 \
    | sed -nE 's/^[[:space:]]*java.home = (.*)$/\1/p' \
    | head -n 1
}

is_jdk21_home() {
  local home="$1"
  [[ -n "$home" && -x "$home/bin/java" && "$(java_major "$home/bin/java")" == "21" ]]
}

detect_jdk21() {
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
    home="$(java_home_from_bin "$(command -v java)")"
    if is_jdk21_home "$home"; then
      printf '%s\n' "$home"
      return 0
    fi
  fi

  return 1
}

is_android_sdk() {
  local dir="$1"
  [[ -n "$dir" && -d "$dir/platforms" && -d "$dir/build-tools" ]]
}

sdk_from_local_properties() {
  local file="$PROJECT_DIR/local.properties"
  [[ -f "$file" ]] || return 1
  sed -nE 's/^sdk\.dir=(.*)$/\1/p' "$file" | head -n 1
}

detect_android_sdk() {
  local dir

  for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$(sdk_from_local_properties || true)" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$HOME/Android/sdk"; do
    if is_android_sdk "$dir"; then
      printf '%s\n' "$dir"
      return 0
    fi
  done

  return 1
}

JDK21_HOME="$(detect_jdk21 || true)"
if [[ -z "$JDK21_HOME" ]]; then
  cat >&2 <<'EOF'
Java 17 was not found.
Install/provide JDK 21, or set one of:
  export JDK_HOME=/path/to/jdk21
  export JAVA_HOME=/path/to/jdk21
Then rerun this script.
EOF
  exit 126
fi

export JAVA_HOME="$JDK21_HOME"

ANDROID_SDK_HOME="$(detect_android_sdk || true)"
if [[ -z "$ANDROID_SDK_HOME" ]]; then
  cat >&2 <<'EOF'
Android SDK was not found.
Install Android SDK, or set one of:
  export ANDROID_HOME=/path/to/android-sdk
  export ANDROID_SDK_ROOT=/path/to/android-sdk
or create local.properties with:
  sdk.dir=/path/to/android-sdk
Then rerun this script.
EOF
  exit 125
fi

export ANDROID_HOME="$ANDROID_SDK_HOME"
export ANDROID_SDK_ROOT="$ANDROID_SDK_HOME"
printf 'sdk.dir=%s\n' "$ANDROID_SDK_HOME" > "$PROJECT_DIR/local.properties"

GRADLE_ARGS=(-Dorg.gradle.java.home="$JDK21_HOME" -p "$PROJECT_DIR" "$TASK")

if [[ -x "$PROJECT_DIR/gradlew" ]]; then
  exec "$PROJECT_DIR/gradlew" "${GRADLE_ARGS[@]}"
fi

if [[ -n "${GRADLEW:-}" && -x "$GRADLEW" ]]; then
  exec "$GRADLEW" "${GRADLE_ARGS[@]}"
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "${GRADLE_ARGS[@]}"
fi

cat >&2 <<'EOF'
No Gradle wrapper or global gradle was found.
Run `gradle wrapper`, copy a Gradle wrapper into this project, set GRADLEW=/path/to/gradlew, or build from Android Studio.
EOF
exit 127
