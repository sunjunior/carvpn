#!/usr/bin/env bash
# Build the release APK: compile relay -> stage into jniLibs -> assembleRelease -> zipalign -> sign.
#
# Required environment variables (never commit real values):
#   KEYSTORE   path to the release keystore (.jks)
#   KS_PASS    keystore password
#   KEY_PASS   key password (defaults to KS_PASS)
# Optional:
#   KS_ALIAS   key alias (defaults to the first alias in the keystore)
#   OUT        output APK path (default: ./carvpn.apk)
set -euo pipefail
cd "$(dirname "$0")/.."

: "${KEYSTORE:?set KEYSTORE to your release keystore path}"
: "${KS_PASS:?set KS_PASS to your keystore password}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"
OUT="${OUT:-$(pwd)/carvpn.apk}"

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME (or ANDROID_SDK_ROOT) is required" >&2
  exit 1
fi
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"

echo "[1/5] assembleDebug (build native relay)"
./gradlew assembleDebug --console=plain

echo "[2/5] stage relay executable into jniLibs"
mkdir -p app/src/main/jniLibs/armeabi-v7a
cp app/build/intermediates/cxx/Debug/*/obj/local/armeabi-v7a/carvpnrelay \
   app/src/main/jniLibs/armeabi-v7a/libcarvpnrelay.so

echo "[3/5] assembleRelease"
./gradlew assembleRelease --console=plain

echo "[4/5] zipalign"
"$BUILD_TOOLS/zipalign" -p -f 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk "$OUT.unsigned"

echo "[5/5] sign (v1 + v2)"
ALIAS_ARGS=()
if [ -n "${KS_ALIAS:-}" ]; then
  ALIAS_ARGS=(--ks-key-alias "$KS_ALIAS")
fi
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  "${ALIAS_ARGS[@]}" --min-sdk-version 21 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --out "$OUT" "$OUT.unsigned"
rm -f "$OUT.unsigned"

echo "RELEASE OK: $OUT"
