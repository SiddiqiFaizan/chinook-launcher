#!/usr/bin/env bash
# Builds a signed APK without Gradle or the Android SDK manager.
# Needs: JDK 17+, aapt, dx (dalvik-exchange), zipalign, apksigner, and an android.jar (API 34).
#   Debian/Ubuntu: sudo apt install aapt apksigner zipalign dalvik-exchange openjdk-21-jdk
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-sdk/android.jar}
DX=$(command -v dalvik-exchange || command -v dx)
OUT=build
APK=CascadeLauncher.apk
KEYSTORE=keystore/cascade.jks

if [ ! -f "$ANDROID_JAR" ]; then
  mkdir -p sdk
  curl -fSL -o "$ANDROID_JAR" https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "==> Compiling resources"
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -A assets -0 ttf -I "$ANDROID_JAR" -F "$OUT/app.unaligned.apk"

echo "==> Compiling Java"
javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "==> Dexing"
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && aapt add app.unaligned.apk classes.dex)

echo "==> Aligning & signing"
zipalign -f -p 4 "$OUT/app.unaligned.apk" "$OUT/app.aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KEYSTORE" -alias cascade -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Cascade Launcher" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias cascade --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/app.aligned.apk"
apksigner verify --print-certs "$APK" | head -1
echo "==> Built $APK ($(du -h "$APK" | cut -f1))"
