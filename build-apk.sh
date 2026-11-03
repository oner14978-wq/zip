#!/usr/bin/env bash
# Requer: Node 18+, JDK 17, Android SDK (ANDROID_HOME) com platform 34 e build-tools, Gradle 8.2.1+ e internet.
set -euo pipefail
cd "$(dirname "$0")"
npm install
npx cap sync android
cd android
if [ -x ./gradlew ]; then ./gradlew assembleDebug; else gradle assembleDebug; fi
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] && echo "APK gerado: android/$APK" || { echo "FALHA: APK não foi gerado"; exit 1; }
