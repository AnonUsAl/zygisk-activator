#!/usr/bin/env bash
#
# 免 Gradle 直编 APK —— 只用本机已有的 Android SDK + JDK，零网络下载
#
# 依赖：
#   SDK   /Users/anonusal/Library/Android/sdk
#   JDK   /Library/Java/JavaVirtualMachines/temurin-21.jdk
#
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${SDK:-/Users/anonusal/Library/Android/sdk}"
JDK="${JDK:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"

PLATFORM="$SDK/platforms/android-36/android.jar"
BT="$SDK/build-tools/36.0.0"
KS="$HOME/.android/debug.keystore"

APP_NAME="zygisk-activator"
OUT="$PROJ/build"
APK_UNSIGNED="$OUT/unsigned.apk"
APK_ALIGNED="$OUT/aligned.apk"
APK_FINAL="$PROJ/$APP_NAME.apk"

for f in "$PLATFORM" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JDK/bin/javac" "$KS"; do
  [ -e "$f" ] || { echo "缺少依赖：$f"; exit 1; }
done

echo "==> 清理"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile 资源"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -o "$APK_UNSIGNED" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  -A "$PROJ/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "==> javac"
"$JDK/bin/javac" \
  -source 11 -target 11 -nowarn \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  $(find "$PROJ/src" "$OUT/gen" -name '*.java')

echo "==> d8 生成 dex"
"$BT/d8" \
  --release \
  --min-api 26 \
  --lib "$PLATFORM" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "==> 把 dex 塞进 apk"
cp "$APK_UNSIGNED" "$APK_ALIGNED"
( cd "$OUT/dex" && zip -q -X "$APK_ALIGNED" classes.dex )

echo "==> zipalign"
"$BT/zipalign" -f -p 4 "$APK_ALIGNED" "$OUT/aligned2.apk"

echo "==> apksigner 签名（debug keystore）"
"$BT/apksigner" sign \
  --ks "$KS" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v4-signing-enabled false \
  --out "$APK_FINAL" \
  "$OUT/aligned2.apk"

echo "==> 校验签名"
"$BT/apksigner" verify --print-certs "$APK_FINAL" | head -4

echo
echo "✅ 完成：$APK_FINAL"
ls -lh "$APK_FINAL"
echo
echo "安装： adb install -r \"$APK_FINAL\""
