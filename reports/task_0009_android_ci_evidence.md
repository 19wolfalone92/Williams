# TASK 000.9.1 — Android CI Evidence
Date: 2026-10-12 UTC

## Baseline and evidence scope
- PR #66: OPEN, DRAFT, NOT MERGED.
- Inspected branch HEAD: `991b9d9e018af562af21749a58509d640dfbf87b`.
- Branch: `night-shift-audit-20261012`; base: `fix/pytest-safe-collection`.
- The passing Campaign rerun was for code/test-fix SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`, not the inspected current HEAD.
- Evidence collected from GitHub run metadata, job list, job steps/logs, artifact metadata, workflow YAML, and the downloaded APK artifact. No authenticated exchange mutations were used.

## Campaign CI — test-fix SHA
Run `38234529976`, attempt 2, workflow `Williams Campaign CI`, checked-out SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`: **SUCCESS**.
- Python job `114762930396`: SUCCESS; **230 passed, 4 warnings**.
- Android job `114762929031`: SUCCESS.
- Gradle step completed successfully. Log shows `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` tasks completed; `BUILD SUCCESSFUL in 4m 15s`.
- Unit-test task passed, but the accessible job log does not expose a test count. Do not infer a count.
- Debug APK artifact `11740491547`, name `williams-debug-apk`, 20,637,560-byte ZIP, artifact ZIP SHA-256 `232aae96817230c8413c750fedb3096918eb249a06badd0f4f27e595053b4c80`.
- Extracted `app-debug.apk` SHA-256: `795dda02251cf4fad631cc7966f4ef6372cb0c12a170e91797d14262d592f5c6`. This is the APK digest, not the ZIP digest.
- Certificate extracted from the APK v2 signing block: subject/issuer `CN=Android Debug, O=Android, C=US`; SHA-256 fingerprint `4C:E2:C5:9F:E2:C9:28:07:63:9D:0A:1F:B4:A8:EA:F9:BE:CA:2E:12:7E:F9:71:D9:B0:BA:04:B1:0A:D4:7E:00`. This is a debug identity, not an independently trusted release identity. `apksigner` was not available for independent verification in the inspection environment.
- Lint artifact `11739789026`, `williams-lint-report`, ZIP SHA-256 `05c4d70947cd41b2cd2abe29f35713da35cf2e66ae15215af8b5394169e9f3f5`.
- Artifact metadata ties both artifacts to the test-fix SHA above.

## Current-HEAD Campaign run
Run `38234750880`, attempt 1, SHA `991b9d9e018af562af21749a58509d640dfbf87b`: **CANCELLED**, with zero job records and no artifacts. Empty jobs are not success.
- Workflow `.github/workflows/campaign-ci.yml` uses concurrency group `williams-ci-${{ github.ref }}` with `cancel-in-progress: true`.
- Current-HEAD run began at 10:11:27Z; rerun attempt 2 of the older `a78a3f7` run began at 10:11:32Z on the same ref. This timing and the shared-ref concurrency policy strongly support cancellation by the competing rerun, but the cancellation event itself has no job log. Root-cause confidence: **supported inference, not a direct cancellation log**.

## Standalone Android workflow startup failure
- Current-HEAD run `38234751836`, SHA `991b9d9e018af562af21749a58509d640dfbf87b`: `startup_failure`, zero jobs, zero artifacts.
- Earlier run `38234530951`, test-fix SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`: also `startup_failure`, zero jobs, zero artifacts.
- The authorized job-log request returned 404/BlobNotFound because no job log was exposed. The authorized rerun-failed-jobs request for `38234751836` returned 403: “This workflow run cannot be retried.”
- Workflow metadata proves failure before any job record or step was created. Available evidence cannot distinguish runner allocation from workflow initialization/service-side startup.
- Required owner action: repository Actions administrator must inspect the Actions UI/service diagnostics and manually dispatch or rerun `.github/workflows/android-apk.yml` on a known SHA; preserve event/check-suite/runner diagnostics.

## Disposition
Campaign Android build, Android unit-test task, lint, and artifact upload are **VERIFIED on SHA `a78a3f7...` only**. Current-HEAD Campaign is cancelled; standalone workflow is **BLOCKED — ACCESS REQUIRED**. No release signing trust anchor was provided. This report is documentation-only and does not establish execution safety.
