# TASK 000.9.5 — Android Signing and Dependency Security
Date: 2026-10-12 UTC

## Baseline and artifact provenance
- Source inspected: `991b9d9e018af562af21749a58509d640dfbf87b`.
- Successful artifact-producing Campaign run: `38234529976`, attempt 2, source SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`.
- Debug APK artifact ID `11740491547`; enclosing ZIP SHA-256 `232aae96817230c8413c750fedb3096918eb249a06badd0f4f27e595053b4c80`.
- Extracted `app-debug.apk` SHA-256 `795dda02251cf4fad631cc7966f4ef6372cb0c12a170e91797d14262d592f5c6`.
- Lint artifact ID `11739789026`; ZIP SHA-256 `05c4d70947cd41b2cd2abe29f35713da35cf2e66ae15215af8b5394169e9f3f5`.
- APK is the **debug** artifact, not a release APK. Gradle config sets debug application ID suffix `.debug`, version suffix `-debug`, and `isDebuggable = true`.
- Certificate extracted from the APK v2 signing block: subject and issuer `CN=Android Debug, O=Android, C=US`; SHA-256 fingerprint `4C:E2:C5:9F:E2:C9:28:07:63:9D:0A:1F:B4:A8:EA:F9:BE:CA:2E:12:7E:F9:71:D9:B0:BA:04:B1:0A:D4:7E:00`. No independently supplied expected certificate fingerprint exists; trusted release signature is **BLOCKED**, not verified. Independent `apksigner verify` was unavailable in the inspection environment.

## Build and dependencies
- `app/build.gradle.kts`: compile/target SDK 36, min SDK 26, Java/Kotlin target 17. Direct dependencies include pinned versions for AndroidX core/activity/lifecycle, OkHttp 4.12.0, and Android security crypto 1.1.0; Compose dependencies are controlled in part by a Compose BOM.
- `android-apk.yml` pins GitHub Actions by full commit SHA, runs the release contract gate, lint, unit tests, builds debug and unsigned release APKs, and in the trusted main/master path checks a persistent **debug** keystore. The Campaign workflow uses SHA-pinned checkout/setup actions and runs `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- Campaign Android build/lint/unit-test task succeeded on test-fix SHA `a78a3f7...`; this is not a build result for current report-only HEAD `991b9d9...`.
- No Gradle dependency vulnerability audit is present in Campaign workflow evidence. `app/build.gradle.kts` explicitly disables the `GradleDependency` lint check. No dependency lockfile/hash-locked Gradle resolution was established from the inspected tree. Gradle cache/toolchain and transitive resolution mean reproducible dependency integrity is **not verified**.
- The Campaign artifact is a debug APK; the release artifact from the separate standalone workflow was not produced in the failing startup run. No claim is made that a trusted release APK exists.
- Current-HEAD standalone APK run `38234751836` is `startup_failure` with no job records/artifacts; root cause requires Actions administrator diagnostics.

## Status
- APK SHA-256 and debug certificate identity: **VERIFIED for the downloaded debug artifact**.
- Trusted release signing identity: **BLOCKED — no expected fingerprint/trust anchor**.
- Android SCA/vulnerability report and dependency integrity/reproducibility: **INCOMPLETE — EVIDENCE MISSING**.
- Current-HEAD standalone APK CI: **BLOCKED — ACCESS REQUIRED**.
