#!/usr/bin/env bash
#
# One-time setup so the Release workflow can produce an installable APK.
#
# Creates a signing keystore on this machine and uploads it to GitHub as repository
# secrets. The keystore never enters the repository, and the password is only ever
# typed by you.
#
# Keep the generated file. Android identifies an app by its signature, so losing the
# key means no future build can be installed as an update over the current one --
# you would have to uninstall the app, losing every run recorded on the phone.

set -euo pipefail

KEYSTORE="${1:-$HOME/.snail-run/release.jks}"
ALIAS="snail-run"
VALIDITY_DAYS=10950   # 30 years; a personal app should outlive its certificate

command -v keytool >/dev/null || { echo "keytool not found; install a JDK" >&2; exit 1; }
command -v gh >/dev/null || { echo "gh not found; install the GitHub CLI" >&2; exit 1; }

if [ -f "$KEYSTORE" ]; then
  echo "Using the existing keystore at $KEYSTORE"
  read -rsp "Keystore password: " STORE_PASSWORD; echo
else
  mkdir -p "$(dirname "$KEYSTORE")"
  echo "Creating a new keystore at $KEYSTORE"
  read -rsp "Choose a keystore password: " STORE_PASSWORD; echo
  read -rsp "Confirm: " CONFIRM; echo
  [ "$STORE_PASSWORD" = "$CONFIRM" ] || { echo "Passwords differ." >&2; exit 1; }

  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$STORE_PASSWORD" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 \
    -validity "$VALIDITY_DAYS" \
    -dname "CN=snail run, OU=personal, O=snail run, L=, S=, C=FR"

  chmod 600 "$KEYSTORE"
  echo "Keystore created. Back it up somewhere safe."
fi

echo "Uploading secrets to $(gh repo view --json nameWithOwner -q .nameWithOwner)"
base64 -w0 "$KEYSTORE" | gh secret set KEYSTORE_BASE64
printf '%s' "$STORE_PASSWORD" | gh secret set KEYSTORE_PASSWORD
printf '%s' "$ALIAS"          | gh secret set KEY_ALIAS
printf '%s' "$STORE_PASSWORD" | gh secret set KEY_PASSWORD

echo
echo "Done. Cut a release with:"
echo "    git tag v0.1.0 && git push origin v0.1.0"
