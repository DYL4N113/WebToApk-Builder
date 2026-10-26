#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERSION="35.0.2"
DOWNLOAD_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/${VERSION}/android-sdk-tools-static-aarch64.zip"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

curl --fail --location --show-error "$DOWNLOAD_URL" --output "$TMP_DIR/android-sdk-tools.zip"

unzip -p "$TMP_DIR/android-sdk-tools.zip" build-tools/aapt2 > "$TMP_DIR/libaapt2.so"
unzip -p "$TMP_DIR/android-sdk-tools.zip" build-tools/zipalign > "$TMP_DIR/libzipalign.so"

chmod 755 "$TMP_DIR/libaapt2.so" "$TMP_DIR/libzipalign.so"
mv "$TMP_DIR/libaapt2.so" "$SCRIPT_DIR/libaapt2.so"
mv "$TMP_DIR/libzipalign.so" "$SCRIPT_DIR/libzipalign.so"

printf 'Downloaded aapt2 and zipalign to %s\n' "$SCRIPT_DIR"
