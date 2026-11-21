#!/usr/bin/env bash
# Fallback: remove the on-phone YouTube engine so the app still builds (YouTube then uses the server engine only).
set -e
cd "$(dirname "$0")/.."
rm -f android/app/src/main/java/com/clipgrab/app/YtNative.java
sed -i '/\/\/YT$/d' android/app/src/main/java/com/clipgrab/app/MainActivity.java
sed -i '/junkfood02/d' android/app/build.gradle
echo "Stripped on-phone YouTube engine"
