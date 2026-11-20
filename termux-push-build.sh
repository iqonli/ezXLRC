#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_NAME="iqonli/ezXLRC"
CONFIG_DIR="$HOME/.config/ezxlrc"
KEYSTORE="$CONFIG_DIR/iqonli-1.jks"
SIGNING_ENV="$CONFIG_DIR/signing.env"
EXPECTED_CERT="4c281c59b8e1e8a9fb5b16020540128dcca608329255609ce9881fd9b75d12e1"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ -f "$ROOT/app/build.gradle.kts" && -f "$ROOT/.github/workflows/android.yml" ]] ||
  fail "Run this script from the extracted ezXLRC project directory."

# Install the small Termux-side dependencies automatically when possible.
missing=()
for cmd in gh git unzip base64 tar; do
  command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
done
if ((${#missing[@]})); then
  if command -v pkg >/dev/null 2>&1; then
    echo "Installing Termux dependencies..."
    pkg install -y git gh unzip coreutils tar
  else
    fail "Missing commands: ${missing[*]}. Install them first."
  fi
fi

if command -v termux-setup-storage >/dev/null 2>&1 && [[ ! -d "$HOME/storage/downloads" ]]; then
  termux-setup-storage || true
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "GitHub login is required. Complete the browser authorization..."
  gh auth login --hostname github.com --web --git-protocol https
fi
gh auth setup-git

mkdir -p "$CONFIG_DIR"
chmod 700 "$CONFIG_DIR"

# Find the uploaded private signing archive, unless it has already been installed.
BUNDLE="${1:-}"
if [[ -z "$BUNDLE" ]]; then
  for candidate in \
    "$HOME/storage/downloads/iqonli-1-private-signing.zip" \
    "$HOME/storage/downloads/iqonli-1-private-signing(1).zip" \
    "$HOME/storage/shared/Download/iqonli-1-private-signing.zip" \
    "$HOME/storage/shared/Download/iqonli-1-private-signing(1).zip" \
    "$ROOT/iqonli-1-private-signing.zip" \
    "$ROOT/iqonli-1-private-signing(1).zip"; do
    if [[ -s "$candidate" ]]; then
      BUNDLE="$candidate"
      break
    fi
  done
fi

if [[ -n "$BUNDLE" ]]; then
  [[ -s "$BUNDLE" ]] || fail "Signing archive not found: $BUNDLE"
  echo "Importing iqonli-1 into the private Termux config directory..."
  TEMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TEMP_DIR"' EXIT

  unzip -tq "$BUNDLE" >/dev/null || fail "The signing archive is damaged."
  unzip -p "$BUNDLE" signing/iqonli-1.jks > "$TEMP_DIR/iqonli-1.jks" ||
    fail "signing/iqonli-1.jks is missing from the signing archive."
  unzip -p "$BUNDLE" signing/iqonli-1-password.txt > "$TEMP_DIR/password.txt" ||
    fail "signing/iqonli-1-password.txt is missing from the signing archive."
  unzip -p "$BUNDLE" signing/iqonli-1-certificate.txt > "$TEMP_DIR/certificate.txt" ||
    fail "signing/iqonli-1-certificate.txt is missing from the signing archive."

  STORE_PASSWORD="$(awk '/^Keystore 和 Key 密码相同：$/ { getline; sub(/\r$/, ""); print; exit }' "$TEMP_DIR/password.txt")"
  [[ -n "$STORE_PASSWORD" ]] || fail "Could not read the password from the signing archive."

  CERT_SHA256="$(sed -n 's/^[[:space:]]*SHA256: //p' "$TEMP_DIR/certificate.txt" | head -n 1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
  [[ "$CERT_SHA256" == "$EXPECTED_CERT" ]] ||
    fail "The signing archive certificate does not match the expected iqonli-1 certificate."

  [[ -s "$TEMP_DIR/iqonli-1.jks" ]] || fail "The JKS file is empty."
  install -m 600 "$TEMP_DIR/iqonli-1.jks" "$KEYSTORE"

  {
    printf 'export IQONLI_KEYSTORE_PATH=%q\n' "$KEYSTORE"
    printf 'export IQONLI_STORE_PASSWORD=%q\n' "$STORE_PASSWORD"
    printf 'export IQONLI_KEY_PASSWORD=%q\n' "$STORE_PASSWORD"
    printf 'export IQONLI_KEY_ALIAS=%q\n' "iqonli-1"
  } > "$SIGNING_ENV"
  chmod 600 "$SIGNING_ENV"
  unset STORE_PASSWORD
  rm -rf "$TEMP_DIR"
  trap - EXIT
elif [[ ! -s "$KEYSTORE" || ! -s "$SIGNING_ENV" ]]; then
  cat >&2 <<'EOF'
The iqonli-1 signing archive was not found.

Put iqonli-1-private-signing.zip in your phone's Download folder,
or pass its full path as the first argument:

  bash termux-push-build.sh /sdcard/Download/iqonli-1-private-signing.zip
EOF
  exit 1
fi

# Load the locally protected config, then publish the values as GitHub Actions secrets.
# Secret values are sent through stdin; they are never placed in command arguments or logs.
# shellcheck disable=SC1090
source "$SIGNING_ENV"
[[ -s "$IQONLI_KEYSTORE_PATH" ]] || fail "The local iqonli-1 JKS is missing."
[[ -n "${IQONLI_STORE_PASSWORD:-}" && -n "${IQONLI_KEY_PASSWORD:-}" ]] ||
  fail "The local signing configuration is incomplete."

echo "Configuring encrypted GitHub Actions signing secrets for $REPO_NAME..."
KEYSTORE_B64="$(base64 < "$IQONLI_KEYSTORE_PATH" | tr -d '\r\n')"
printf '%s' "$KEYSTORE_B64" | gh secret set MCX_KEYSTORE_B64 --repo "$REPO_NAME" >/dev/null
printf '%s' "$IQONLI_STORE_PASSWORD" | gh secret set MCX_STORE_PASSWORD --repo "$REPO_NAME" >/dev/null
printf '%s' "$IQONLI_KEY_PASSWORD" | gh secret set MCX_KEY_PASSWORD --repo "$REPO_NAME" >/dev/null
unset KEYSTORE_B64 IQONLI_STORE_PASSWORD IQONLI_KEY_PASSWORD
echo "GitHub signing secrets configured. Secret values were not displayed."

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
REPO_DIR="$WORK/ezXLRC"

echo "Cloning $REPO_NAME ..."
gh repo clone "$REPO_NAME" "$REPO_DIR" >/dev/null

# Copy source while excluding local build output, Git metadata and signing material.
tar -C "$ROOT" \
  --exclude='./.git' \
  --exclude='./.gradle' \
  --exclude='./build' \
  --exclude='./app/build' \
  --exclude='./dist' \
  --exclude='./signing' \
  --exclude='./android/signing.properties' \
  --exclude='*.jks' \
  --exclude='*.keystore' \
  --exclude='*.p12' \
  --exclude='*private-signing*.zip' \
  --exclude='*password*.txt' \
  --exclude='signing.env' \
  -cf - . | tar -C "$REPO_DIR" -xf -

# The remote repository used to store an archive-only source layout.
rm -f "$REPO_DIR/ezXLRC-source.tar.xz"

# Defense in depth: remove signing material even if it was placed somewhere unexpected.
rm -rf "$REPO_DIR/signing"
rm -f "$REPO_DIR/android/signing.properties" "$REPO_DIR/signing.properties"
find "$REPO_DIR" -type f \( \
  -iname '*.jks' -o -iname '*.keystore' -o -iname '*.p12' -o \
  -iname '*private-signing*.zip' -o -iname '*password*.txt' -o \
  -iname 'signing.properties' -o -iname 'signing.env' \
\) -delete

LEAK="$(find "$REPO_DIR" -type f \( \
  -iname '*.jks' -o -iname '*.keystore' -o -iname '*.p12' -o \
  -iname '*private-signing*.zip' -o -iname '*password*.txt' -o \
  -iname 'signing.properties' -o -iname 'signing.env' \
\) -print -quit)"
[[ -z "$LEAK" ]] || fail "Refusing to push signing material: $LEAK"

BEFORE_RUN_ID="$(gh run list --repo "$REPO_NAME" --workflow android.yml --branch main --limit 1 --json databaseId --jq '.[0].databaseId // 0')"
GH_USER="$(gh api user --jq .login)"
GH_ID="$(gh api user --jq .id)"
git -C "$REPO_DIR" config user.name "$GH_USER"
git -C "$REPO_DIR" config user.email "${GH_ID}+${GH_USER}@users.noreply.github.com"
git -C "$REPO_DIR" add -A

if git -C "$REPO_DIR" diff --cached --quiet; then
  echo "Source is already current; dispatching a fresh build."
  gh workflow run android.yml --repo "$REPO_NAME" --ref main
else
  git -C "$REPO_DIR" commit -m "Update ezXLRC Android app and iqonli-1 signed build"
  git -C "$REPO_DIR" push origin main
fi

echo "Waiting for the Android Build workflow..."
RUN_ID=""
for attempt in $(seq 1 60); do
  RUN_ID="$(gh run list --repo "$REPO_NAME" --workflow android.yml --branch main --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
  [[ -n "$RUN_ID" && "$RUN_ID" != "$BEFORE_RUN_ID" ]] && break
  sleep 2
done
[[ -n "$RUN_ID" && "$RUN_ID" != "$BEFORE_RUN_ID" ]] ||
  fail "Source was pushed, but the new Actions run was not detected. Open https://github.com/$REPO_NAME/actions"

if ! gh run watch "$RUN_ID" --repo "$REPO_NAME" --exit-status; then
  if [[ -d "$HOME/storage/shared/Download" ]]; then
    FAIL_DEST="$HOME/storage/shared/Download/ezXLRC-failed-$RUN_ID"
  else
    FAIL_DEST="$ROOT/dist/ezXLRC-failed-$RUN_ID"
  fi
  mkdir -p "$FAIL_DEST"
  gh run download "$RUN_ID" --repo "$REPO_NAME" --name ezXLRC-source-and-reports --dir "$FAIL_DEST" >/dev/null 2>&1 || true
  echo "Build failed. Diagnostics (if available): $FAIL_DEST" >&2
  exit 1
fi

if [[ ! -d "$HOME/storage/shared/Download" ]] && command -v termux-setup-storage >/dev/null 2>&1; then
  termux-setup-storage || true
fi
if [[ -d "$HOME/storage/shared/Download" ]]; then
  DEST="$HOME/storage/shared/Download/ezXLRC-build-$RUN_ID"
else
  DEST="$ROOT/dist/ezXLRC-build-$RUN_ID"
fi
mkdir -p "$DEST"
gh run download "$RUN_ID" --repo "$REPO_NAME" --name ezXLRC-1.0.0-iqonli-1-signed --dir "$DEST"

echo
echo "Done. Signed APK and SHA-256 file:"
find "$DEST" -maxdepth 1 -type f -print
echo "Saved to: $DEST"
