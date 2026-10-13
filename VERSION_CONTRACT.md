# TASK 002.2 — Version Contract

**Status: VERSION METADATA PARTIALLY VERIFIED; TOOLCHAIN CONTRACT OPEN**

## Canonical application version

At source SHA `0189f85f3268dc96f4950f700628376d71cd57d4`:

- Root `VERSION`: `4.25.0`.
- Release contract gate output in merge-SHA CI: `WILLIAMS RELEASE CONTRACT GATE: PASS (VERSION=4.25.0)`.
- Android build job reports debug APK: package `com.williamsbot.debug`, versionCode `2114`, versionName `4.25.0-debug`.
- Android build job reports release APK: package `com.williamsbot`, versionCode `2114`, versionName `4.25.0`.
- Both APK metadata verification steps returned `APK VERSION CONTRACT: PASS`.

These metadata values are from the workflow's `aapt dump badging` output for merge SHA `0330466...`; the APK files were also downloaded and their SHA-256 values independently recalculated. A separate local APK parser was not available in the audit container, so package/version extraction remains CI-observed, not independently re-extracted locally.

## Version/toolchain discrepancies

| Contract | Declared | Actual workflow | Status |
|---|---|---|---|
| App version | `VERSION=4.25.0` | Gate and APK version checks agree | PARTIAL PASS |
| Gradle | Wrapper `8.14.6` | Workflow provisions `8.14.5` and invokes that executable | FAIL / OPEN |
| Python | No complete lock/pin proven | Backend 3.13.16; campaign 3.12.15 | FAIL / OPEN |
| Android SDK | compile/target 36 | platform 36; build-tools 36.0.0 | CI-observed |
| Java | target 17 | Temurin 17.0.20+1 | CI-observed |

Do not treat a matching `versionName` as proof of identical APK bytes or source provenance. Version contract is not fully PASS while the build tool and dependency environments are not reproducibly aligned.
