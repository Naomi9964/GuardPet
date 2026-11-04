#!/bin/bash
# Manual APK build without Gradle (Gradle daemon cannot run in this sandbox).
set -e
export JAVA_HOME="$HOME/workspace/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
ANDROID_HOME="$HOME/workspace/android-sdk"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$PROJ/build/manual"
KOTLINC="$HOME/workspace/kotlinc"

rm -rf "$BUILD"
mkdir -p "$BUILD"

echo "== 1/7 aapt2 compile =="
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$BUILD/res.zip"

echo "== 2/7 aapt2 link =="
VCODE=$(grep -oP 'versionCode \K\d+' "$PROJ/app/build.gradle" | head -1)
VNAME=$(grep -oP 'versionName "\K[^"]+' "$PROJ/app/build.gradle" | head -1)
ASSETS_ARG=""
[ -d "$PROJ/app/src/main/assets" ] && ASSETS_ARG="-A $PROJ/app/src/main/assets"
"$BT/aapt2" link -o "$BUILD/app.unaligned.apk" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  -I "$PLATFORM" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "$VNAME" \
  $ASSETS_ARG \
  "$BUILD/res.zip"

echo "== 3/7 kotlinc =="
# shellcheck disable=SC2046
"$KOTLINC/bin/kotlinc" \
  -cp "$PLATFORM" \
  -d "$BUILD/classes" \
  $(find "$PROJ/app/src/main/java" "$BUILD/gen" -name "*.kt" -o -name "*.java")

echo "== 4/7 d8 =="
mkdir -p "$BUILD/dex"
# shellcheck disable=SC2046
"$BT/d8" --lib "$PLATFORM" --min-api 26 \
  --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name "*.class") \
  "$KOTLINC/lib/kotlin-stdlib.jar"

echo "== 5/7 add classes.dex =="
python3 - "$BUILD/dex/classes.dex" "$BUILD/app.unaligned.apk" <<'EOF'
import sys, zipfile
dex, apk = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("dex added")
EOF

echo "== 6/7 zipalign =="
"$BT/zipalign" -f 4 "$BUILD/app.unaligned.apk" "$BUILD/app.aligned.apk"

echo "== 7/7 apksigner =="
KEYSTORE="$BUILD/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/app-debug.apk" "$BUILD/app.aligned.apk"

echo "== verify =="
"$BT/apksigner" verify "$BUILD/app-debug.apk" && echo "SIGNATURE OK"
"$BT/aapt2" dump badging "$BUILD/app-debug.apk" | head -3
ls -la "$BUILD/app-debug.apk"
