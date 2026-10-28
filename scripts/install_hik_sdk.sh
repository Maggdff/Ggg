#!/usr/bin/env bash
# ينزّل zip يحتوي: libs/HCNetSDK.jar, libs/PlayerSDK.jar (+ jars أخرى) و jniLibs/<abi>/*.so (+ HCNetSDKCom)
# ويضعها في مواضعها داخل المشروع. الاستخدام: install_hik_sdk.sh <url-or-local-zip>
set -euo pipefail
SRC="$1"; cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
if [[ "$SRC" == http* ]]; then curl -fsSL "$SRC" -o "$TMP/sdk.zip"; else cp "$SRC" "$TMP/sdk.zip"; fi
unzip -q "$TMP/sdk.zip" -d "$TMP/x"
mkdir -p app/libs app/src/main/jniLibs
cp -r "$TMP"/x/libs/*.jar app/libs/
cp -r "$TMP"/x/jniLibs/* app/src/main/jniLibs/
test -f app/libs/HCNetSDK.jar && test -f app/libs/PlayerSDK.jar && echo "تم تركيب Hikvision SDK"
