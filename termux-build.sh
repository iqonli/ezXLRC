#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

CONFIG_DIR="$HOME/.config/ezxlrc"
SIGNING_ENV="$CONFIG_DIR/signing.env"
if [[ -s "$SIGNING_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$SIGNING_ENV"
fi

GRADLE_BIN="${GRADLE_BIN:-gradle}"
command -v "$GRADLE_BIN" >/dev/null 2>&1 ||
  { echo "Gradle 8.13 was not found." >&2; exit 1; }

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK_ROOT" && -d "$SDK_ROOT/platforms/android-36" ]] ||
  { echo "Android SDK 36 is required for local builds. Use termux-push-build.sh for GitHub Actions builds." >&2; exit 1; }
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

APKSIGNER="$SDK_ROOT/build-tools/36.0.0/apksigner"
[[ -x "$APKSIGNER" ]] || { echo "Missing apksigner: $APKSIGNER" >&2; exit 1; }

GRADLE_VERSION="$("$GRADLE_BIN" --version | awk '/^Gradle / { print $2; exit }')"
[[ "$GRADLE_VERSION" == "8.13" ]] ||
  { echo "Gradle 8.13 is required; found ${GRADLE_VERSION:-unknown}." >&2; exit 1; }

KEYSTORE="${1:-${IQONLI_KEYSTORE_PATH:-$CONFIG_DIR/iqonli-1.jks}}"
[[ -s "$KEYSTORE" ]] || { echo "iqonli-1 JKS not found: $KEYSTORE" >&2; exit 1; }

export IQONLI_KEYSTORE_PATH="$KEYSTORE"
export IQONLI_KEY_ALIAS="${IQONLI_KEY_ALIAS:-iqonli-1}"

if [[ -z "${IQONLI_STORE_PASSWORD:-}" ]]; then
  read -r -s -p "iqonli-1 keystore password: " IQONLI_STORE_PASSWORD
  printf '\n'
  export IQONLI_STORE_PASSWORD
fi
if [[ -z "${IQONLI_KEY_PASSWORD:-}" ]]; then
  read -r -s -p "iqonli-1 key password (Enter to reuse keystore password): " IQONLI_KEY_PASSWORD
  printf '\n'
  IQONLI_KEY_PASSWORD="${IQONLI_KEY_PASSWORD:-$IQONLI_STORE_PASSWORD}"
  export IQONLI_KEY_PASSWORD
fi

echo "Building ezXLRC release with alias $IQONLI_KEY_ALIAS..."
"$GRADLE_BIN" --no-daemon --warning-mode all clean testDebugUnitTest lintDebug assembleRelease --stacktrace

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[[ -s "$APK" ]] || { echo "Release APK was not generated: $APK" >&2; exit 1; }

SIGNATURE_OUTPUT="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)"
printf '%s\n' "$SIGNATURE_OUTPUT"
ACTUAL_CERT="$(printf '%s\n' "$SIGNATURE_OUTPUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
EXPECTED_CERT="4c281c59b8e1e8a9fb5b16020540128dcca608329255609ce9881fd9b75d12e1"
[[ "$ACTUAL_CERT" == "$EXPECTED_CERT" ]] || {
  echo "Wrong signing certificate. Expected iqonli-1: $EXPECTED_CERT" >&2
  echo "Actual certificate: ${ACTUAL_CERT:-not found}" >&2
  exit 1
}

OUT_DIR="$HOME/storage/shared/Download"
if [[ ! -d "$OUT_DIR" ]]; then
  OUT_DIR="$ROOT/dist"
  mkdir -p "$OUT_DIR"
fi
OUT_APK="$OUT_DIR/ezXLRC-1.0.0-iqonli-1.apk"
cp -f "$APK" "$OUT_APK"
sha256sum "$OUT_APK" > "$OUT_APK.sha256"

printf '\nSigned APK: %s\n' "$OUT_APK"
printf 'SHA-256: '
sha256sum "$OUT_APK" | awk '{print $1}'
