#!/bin/bash
# Build manual APK TG Drive (tanpa Gradle).
# Hasil: build/manual/tgdrive.apk  (versionCode & versionName via flag di bawah)
# Keystore: build/manual/tgdrive.keystore  (password tersimpan di file ini? TIDAK —
#   isi manual saat diminta, atau set env TGDRIVE_KS_PASS)
set -e
cd "$(dirname "$0")"

VER_CODE="${VER_CODE:-2}"
VER_NAME="${VER_NAME:-1.1}"
KS_PASS="${TGDRIVE_KS_PASS:?set TGDRIVE_KS_PASS dulu}"

export PATH=/home/hatch/jdk17/bin:$PATH
BT=~/android-sdk/build-tools/34.0.0
AJAR=~/android-sdk/platforms/android-34/android.jar

$BT/aapt2 compile --dir app/src/main/res -o build/manual/compiled.zip
$BT/aapt2 link -o build/manual/app-unsigned.apk -I "$AJAR" \
  --manifest app/src/main/AndroidManifest.xml --java build/manual/gen \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" \
  build/manual/compiled.zip

rm -rf build/manual/classes && mkdir -p build/manual/classes
# shellcheck disable=SC2046
javac -encoding UTF-8 -source 17 -target 17 -classpath "$AJAR" \
  -d build/manual/classes $(find app/src/main/java build/manual/gen -name "*.java")

rm -rf build/manual/dex && mkdir -p build/manual/dex
# shellcheck disable=SC2046
$BT/d8 --lib "$AJAR" --output build/manual/dex \
  $(find build/manual/classes -name "*.class")

cp build/manual/dex/classes.dex build/manual/classes.dex
(cd build/manual && zip -uj app-unsigned.apk classes.dex >/dev/null)
$BT/zipalign -f 4 build/manual/app-unsigned.apk build/manual/app-aligned.apk
$BT/apksigner sign --ks build/manual/tgdrive.keystore \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out build/manual/tgdrive.apk build/manual/app-aligned.apk
$BT/apksigner verify build/manual/tgdrive.apk
$BT/aapt dump badging build/manual/tgdrive.apk | head -1
echo "SELESAI: build/manual/tgdrive.apk"
