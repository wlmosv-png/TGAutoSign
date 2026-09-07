#!/usr/bin/env bash
set -euo pipefail

MODULE_PKG="${1:?usage: $0 <module.pkg> <target.pkg> [user_id]}"
TARGET_PKG="${2:?usage: $0 <module.pkg> <target.pkg> [user_id]}"
USER_ID="${3:-0}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
CLI="${CLI:-/data/adb/lspd/cli}"
MIN_VECTOR_VERSION_CODE="${MIN_VECTOR_VERSION_CODE:-3043}"

vector_version_code() {
  local status
  if ! status="$(adb shell su -c "$CLI status --json" 2>/dev/null)"; then
    status="$(adb shell su -c "$CLI status" 2>/dev/null || true)"
  fi

  printf '%s\n' "$status" \
    | sed -nE 's/.*"Version Code"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p; s/.*Version Code:[[:space:]]*([0-9]+).*/\1/p' \
    | head -n 1
}

adb install -r "$APK"

VERSION_CODE="$(vector_version_code)"
if [[ -z "$VERSION_CODE" ]]; then
  cat >&2 <<EOF
Vector CLI is unavailable or did not report a version.
APK installed, but automatic enable/scope was skipped.
Open Vector/LSPosed Manager manually, enable $MODULE_PKG, and add scope $TARGET_PKG/$USER_ID.
EOF
  exit 2
fi

if (( VERSION_CODE < MIN_VECTOR_VERSION_CODE )); then
  cat >&2 <<EOF
Vector version code $VERSION_CODE is older than required $MIN_VECTOR_VERSION_CODE.
APK installed, but automatic enable/scope was skipped for safety.
Open Vector/LSPosed Manager manually, enable $MODULE_PKG, and add scope $TARGET_PKG/$USER_ID.
EOF
  exit 2
fi

adb shell su -c "$CLI modules enable $MODULE_PKG"
adb shell su -c "$CLI scope add $MODULE_PKG $TARGET_PKG/$USER_ID"
adb shell su -c "$CLI scope ls $MODULE_PKG"

echo "Installed and scoped with Vector $VERSION_CODE: $MODULE_PKG -> $TARGET_PKG/$USER_ID"
