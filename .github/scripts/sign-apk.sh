#!/usr/bin/env bash
# Podpisuje APK (apksigner) i zapisuje sumę SHA-256 obok pliku.
# Użycie: sign-apk.sh <keystore> <niepodpisany.apk> <wynik.apk>
# Hasła i alias wyłącznie ze zmiennych środowiskowych (nie z linii poleceń):
#   ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
# Wypisuje certyfikat podpisu (publiczny) i zapisuje go do <wynik.apk>.certs.txt.
set -euo pipefail
ks="$1" unsigned="$2" apk="$3"
: "${ANDROID_KEYSTORE_PASSWORD:?}" "${ANDROID_KEY_ALIAS:?}" "${ANDROID_KEY_PASSWORD:?}"

bt=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)
mkdir -p "$(dirname "$apk")"
"$bt/apksigner" sign --ks "$ks" --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
  --out "$apk" "$unsigned"
"$bt/apksigner" verify --print-certs "$apk" | tee "$apk.certs.txt"
cd "$(dirname "$apk")"
sha256sum "$(basename "$apk")" | tee "$(basename "$apk").sha256"
