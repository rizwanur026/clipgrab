#!/usr/bin/env bash
# Fallback: remove AI Enhance so the app still builds (the Enhance page then shows the Colab links).
set -e
cd "$(dirname "$0")/.."
APP=android/app/src/main/java/com/clipgrab/app
rm -f "$APP/Enhancer.java"
rm -rf android/app/src/main/cpp android/app/src/main/assets/ai android/app/.cxx
sed -i '/\/\/ AI$/d' android/app/build.gradle
sed -i '/\/\/AI$/d' "$APP/MainActivity.java"
echo "Stripped AI Enhance"
