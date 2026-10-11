# TASK 002.1 — Artifact Provenance

**Status: PARTIAL EVIDENCE / BLOCKED FOR ACCEPTANCE**

## Workflow and source metadata observed

GitHub Actions run `38077212239` (“Build Android APK”) is associated by GitHub artifact metadata with branch `audit/williams-2.1-task-002-release-contract` and head SHA `bb6cd92ca7bb07369e26e77cab214b04229c3435`.

Run page: https://github.com/19wolfalone92/Williams/actions/runs/38077212239

Artifacts listed by GitHub API:
- ID `11679353038`: `williams-bot-debug-apk-1531`, 20,638,869 bytes, not expired.
- ID `11679357932`: `williams-bot-release-unsigned-1531`, 14,176,611 bytes, not expired.
- ID `11679462492`: `williams-debug-build-log-1531`, 1,024 bytes, not expired.
- ID `11679342429`: `williams-android-unit-test-diagnostics-1531`, 24,529 bytes, not expired.

**Important:** the build job log shows shallow checkout `fetch-depth: 1` of merge commit `89c866cfe63ac8a76f4ebb038d6166fafb745bd8`, with merge parents representing branch commit `bb6cd92...` and main `04da5f9...`. Thus the artifacts are linked to a workflow run whose event/head metadata references `bb6cd92...`, but the actual checked-out build tree is merge SHA `89c866c...`. Do not describe the artifact as proven to be built from the isolated branch tree until that distinction is resolved.

## Required independent verification — still open

| Evidence | Status |
|---|---|
| Downloaded APK bytes | Not downloaded in this audit |
| New independent SHA-256 calculation | Not performed |
| Previously reported SHA-256 `4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5` | Carried-forward only; not revalidated |
| applicationId / versionName / versionCode | Not independently extracted from APK |
| Signing certificate fingerprint | Not independently extracted |
| Build manifest / provenance attestation linking exact tree SHA | Not verified |
| CI logs and artifact metadata | Retrieved via GitHub connector; build steps successful |
| Physical device install/runtime | Not performed; no ADB evidence available |

CI build workflow used Temurin JDK `17.0.20+1`, Gradle `8.14.5`, Android platform 36 and build-tools 36.0.0. The repository's working-branch Gradle wrapper currently declares `8.14.6`, so this discrepancy must be resolved and the build repeated before reproducibility can be claimed.

## Gate

The chain currently reaches **GitHub workflow run → artifact metadata → declared head SHA**, but not independently verified APK bytes → APK SHA-256 → actual build tree/source commit → device-installed APK. Do not mark provenance PASS until all links are independently checked.
