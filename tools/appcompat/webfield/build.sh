#!/usr/bin/env bash
# Builds the app-compatibility test page app (WebView + content provider for Chrome) without Gradle.
# Run inside the devshell: nix develop . --command tools/appcompat/webfield/build.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME}}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$(ls -d "$sdk"/platforms/android-36/android.jar)
out=${OUT:-$here/build}
rm -rf "$out"; mkdir -p "$out/classes" "$out/dex"
javac --release 11 -classpath "$jar" -d "$out/classes" $(find "$here/src" -name '*.java')
"$bt/d8" --min-api 29 --lib "$jar" --output "$out/dex" $(find "$out/classes" -name '*.class')
# assets stored uncompressed so the provider can hand out an AssetFileDescriptor
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$jar" --manifest "$here/AndroidManifest.xml" -A "$here/assets" -0 html
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
ks="${WEBFIELD_KEYSTORE:-$HOME/.android/appcompat-webfield.keystore}"  # kept across builds: updates install in place
mkdir -p "$(dirname "$ks")"
[ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -storepass android -keypass android -alias k -keyalg RSA \
  -dname CN=webfield -validity 10000 >/dev/null 2>&1
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --out "$out/webfield.apk" "$out/aligned.apk"
echo "$out/webfield.apk"
