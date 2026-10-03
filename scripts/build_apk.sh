#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=(gradle)
elif [[ -x ./gradlew ]]; then
  GRADLE_CMD=(./gradlew)
else
  echo "Gradle is not installed and Gradle Wrapper is not bundled as a binary in this source archive."
  echo "Open the project in Android Studio or install Gradle 8.9, then run this script again."
  exit 2
fi
"${GRADLE_CMD[@]}" --version
"${GRADLE_CMD[@]}" :app:assembleDebug
if [[ -f keystore.properties ]]; then
  "${GRADLE_CMD[@]}" :app:assembleRelease
  echo "Release APK: app/build/outputs/apk/release/app-release.apk"
else
  echo "Debug APK: app/build/outputs/apk/debug/app-debug.apk"
  echo "No keystore.properties found; release signing is intentionally skipped."
fi
