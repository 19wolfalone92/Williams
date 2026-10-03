#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f keystore.properties ]]; then
  ./scripts/generate_release_keystore_auto.sh
fi

if [[ -x ./gradlew ]]; then
  GRADLE_CMD=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=(gradle)
else
  echo "Gradle/Gradle Wrapper is unavailable. Open this project in Android Studio and run Build > Generate Signed App Bundle / APK."
  exit 2
fi

"${GRADLE_CMD[@]}" :app:assembleRelease --stacktrace
printf '\nRelease APK: %s\n' "$PWD/app/build/outputs/apk/release/app-release.apk"
