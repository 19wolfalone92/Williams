# TASK 002.2 — Version Matrix

**Status: PARTIAL / BLOCKED FOR RELEASE**

| Layer | Declared | Observed | Assessment |
|---|---|---|---|
| Canonical app/backend version | Root `VERSION=4.25.0` | Release gate prints 4.25.0 | CI-observed on merge tree |
| Debug APK | `4.25.0-debug` | `com.williamsbot.debug`, versionCode 2114 | CI metadata check passed on merge tree |
| Release APK | `4.25.0` | `com.williamsbot`, versionCode 2114 | CI metadata check passed; artifact is unsigned |
| Gradle wrapper | `8.14.6` | Workflow correction `b109a3b...` now provisions 8.14.6 and calls `./gradlew` | Pending new CI completion |
| JDK | Target 17 | Temurin 17.0.20+1 | CI-observed |
| Android SDK | compile/target 36 | platform 36, build-tools 36.0.0 | CI-observed |
| Backend Python | Needs canonical pin | CPython 3.13.16 | CI-observed |
| Campaign Python | Needs same canonical pin | CPython 3.12.15 | Runtime mismatch remains |
| Python dependency lock | Exact versions and hashes required | No complete hash-locked lock file verified | BLOCKED |

The prior Android build used Gradle 8.14.5 despite wrapper 8.14.6. The workflow has been corrected but the corresponding run was queued at report time. Do not call the contract PASS until exact-head build/test logs confirm the corrected path and dependency reproducibility is addressed.
