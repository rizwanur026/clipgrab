#!/usr/bin/env bash
# Fallbacks so the app always builds.
#   strip-yt.sh new : drop only the newest extras (built-in merger + libc++ fix), keep the on-phone engine
#   strip-yt.sh     : remove the whole on-phone YouTube engine (YouTube then uses the server engine only)
set -e
cd "$(dirname "$0")/.."
bash android-extra/strip-ai.sh
APP=android/app/src/main/java/com/clipgrab/app
rm -f "$APP/Mux.java" "$APP/DlService.java"
sed -i 's#<service android:name="\.DlService"[^>]*/>##' android/app/src/main/AndroidManifest.xml
sed -i '/\/\/ CPP$/d' android/app/build.gradle
rm -rf android/app/src/main/jniLibs
if [ "${1:-}" = "new" ]; then
  echo "Stripped the newest extras (built-in merger, libc++ fix)"
  exit 0
fi
rm -f "$APP/YtNative.java"
sed -i '/\/\/YT$/d' "$APP/MainActivity.java"
sed -i '/junkfood02/d' android/app/build.gradle
echo "Stripped on-phone YouTube engine"
