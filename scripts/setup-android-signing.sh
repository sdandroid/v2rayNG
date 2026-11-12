#!/usr/bin/env bash
# One-time generator for a persistent Android signing key.
# Run in a trusted terminal (GitHub Codespaces is fine). Never paste your password into a chat.
set -euo pipefail
umask 077

REPO="${REPO:-sdandroid/v2rayNG}"
KEYSTORE_PATH="${KEYSTORE_PATH:-$PWD/v2rayng-release.jks}"
KEY_ALIAS="v2rayng"
SETUP_MODE="${1:-}"
if [[ -n "$SETUP_MODE" && "$SETUP_MODE" != "--manual" ]]; then
  echo "Usage: bash scripts/setup-android-signing.sh [--manual]" >&2
  exit 1
fi

for tool in keytool base64; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing: $tool. Install a JDK (Java 17+) first." >&2
    exit 1
  fi
done

if [[ "$SETUP_MODE" == "--manual" ]]; then
  echo "Manual mode: this script cannot check existing repository secrets."
  echo "If a keystore secret already exists, do NOT create a new key."
  printf "Confirm you checked the repository Secrets and wish to continue (type YES): "
  read -r confirmation
  if [[ "$confirmation" != "YES" ]]; then
    echo "Aborted."
    exit 1
  fi
else
  if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI missing. Install 'gh' or use --manual and configure Secrets in the browser." >&2
    exit 1
  fi
  if ! gh auth status >/dev/null 2>&1; then
    echo "Authenticate using: gh auth login (or use --manual)." >&2
    exit 1
  fi

  # Actions secrets cannot be retrieved to reconstruct a lost keystore.
  # Refuse to silently replace an already configured signing identity.
  if ! secret_names="$(gh secret list --repo "$REPO" --app actions --json name --jq '.[].name')"; then
    echo "Cannot list Actions Secrets with current GitHub CLI credentials." >&2
    echo "Use a terminal authenticated with repository admin rights, or run --manual." >&2
    exit 1
  fi
  if grep -Fxq "APP_KEYSTORE_BASE64" <<< "$secret_names"; then
    echo "APP_KEYSTORE_BASE64 already exists in $REPO." >&2
    echo "Refusing to overwrite the fixed signing key. Keep using your existing keystore." >&2
    exit 1
  fi
fi

printf "Create/reuse fixed keystore: %s\n" "$KEYSTORE_PATH"
printf "Enter signing password (12+ characters; input hidden): "
IFS= read -r -s APP_KEYSTORE_PASSWORD
printf "\n"
export APP_KEYSTORE_PASSWORD
trap 'unset APP_KEYSTORE_PASSWORD' EXIT

if (( ${#APP_KEYSTORE_PASSWORD} < 12 )); then
  echo "Password must be at least 12 characters." >&2
  exit 1
fi

if [[ -f "$KEYSTORE_PATH" ]]; then
  echo "Reusing existing keystore file (will not generate a new signing identity)."
else
  printf "Repeat password: "
  IFS= read -r -s confirm_password
  printf "\n"
  if [[ "$APP_KEYSTORE_PASSWORD" != "$confirm_password" ]]; then
    echo "Passwords do not match." >&2
    exit 1
  fi
  unset confirm_password
  mkdir -p "$(dirname "$KEYSTORE_PATH")"
  keytool -genkeypair -noprompt -storetype PKCS12 \
    -keystore "$KEYSTORE_PATH" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=v2rayNG, OU=Android, O=sdandroid, C=US" \
    -storepass:env APP_KEYSTORE_PASSWORD \
    -keypass:env APP_KEYSTORE_PASSWORD
fi

keytool -list -keystore "$KEYSTORE_PATH" \
  -storepass:env APP_KEYSTORE_PASSWORD -alias "$KEY_ALIAS" >/dev/null

# Never commit or upload the raw keystore as an Actions artifact.
if [[ "$SETUP_MODE" == "--manual" ]]; then
  echo "Keystore generated/verified locally. Configure BOTH secrets via:"
  echo "  https://github.com/$REPO/settings/secrets/actions"
  echo "  APP_KEYSTORE_PASSWORD = the password you entered (do not share it)"
  echo "  APP_KEYSTORE_BASE64 = the output of:"
  printf "    base64 < %q | tr -d '[:space:]'\n" "$KEYSTORE_PATH"
else
  # GitHub CLI encrypts secret values locally before uploading them.
  base64 < "$KEYSTORE_PATH" | tr -d '[:space:]' | gh secret set APP_KEYSTORE_BASE64 --repo "$REPO" --app actions
  printf '%s' "$APP_KEYSTORE_PASSWORD" | gh secret set APP_KEYSTORE_PASSWORD --repo "$REPO" --app actions
  echo "Signing secrets configured: APP_KEYSTORE_BASE64 and APP_KEYSTORE_PASSWORD."
fi

echo "BACK UP $KEYSTORE_PATH AND YOUR PASSWORD to an offline, secure location."
echo "Never regenerate the keystore: using a new key breaks APK signature continuity."
