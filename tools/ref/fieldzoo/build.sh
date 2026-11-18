#!/usr/bin/env bash
# Builds tools/ref/fieldzoo into fieldzoo.apk without Gradle (javac + d8 + aapt2).
# Run inside the devshell: nix develop . --command tools/ref/fieldzoo/build.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME}}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$(ls -d "$sdk"/platforms/android-36/android.jar)
out=${OUT:-$here/build}
rm -rf "$out"; mkdir -p "$out/classes" "$out/dex"
javac --release 11 -classpath "$jar" -d "$out/classes" $(find "$here/src" -name '*.java')
"$bt/d8" --min-api 29 --lib "$jar" --output "$out/dex" $(find "$out/classes" -name '*.class')
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$jar" --manifest "$here/AndroidManifest.xml"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
ks="$out/debug.keystore"
keytool -genkeypair -keystore "$ks" -storepass android -keypass android -alias k -keyalg RSA \
  -dname CN=fieldzoo -validity 10000 >/dev/null 2>&1
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --out "$out/fieldzoo.apk" "$out/aligned.apk"
echo "$out/fieldzoo.apk"
