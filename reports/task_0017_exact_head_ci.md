# TASK 001.8 — Exact-HEAD CI and APK Verification

- Audited source/test SHA: `31ba73ff81c43edb5be4606b200dce752148967a`.
- PR #66: OPEN, Draft, unmerged; base `fix/pytest-safe-collection`.
- This report update is a documentation-only commit after the audited source snapshot; CI must be rerun for the resulting report commit before it can be called the final exact HEAD.

## Exact-SHA CI evidence for 31ba73f

- Python backend tests: run [38301629007](https://github.com/19wolfalone92/Williams/actions/runs/38301629007), completed SUCCESS. Job `backend-test` passed dependency audit, release contract gate, Python compile, static safety audit, safe offline tests, and diagnostics upload.
- Williams Campaign CI: run [38301629067](https://github.com/19wolfalone92/Williams/actions/runs/38301629067). Python tests job completed SUCCESS. Android job `Android build and static checks` was still IN_PROGRESS at last inspection; step `Build debug APK and run unit tests` was still running. Lint, APK verification, and upload steps were pending. Do not claim Android tests/lint/APK verified.
- Standalone Build Android APK: run [38301630392](https://github.com/19wolfalone92/Williams/actions/runs/38301630392), completed `startup_failure`; jobs list empty; artifacts empty. This is not evidence of a Gradle failure. GitHub's exposed result contains no job logs or scheduler cause; external blocker remains unresolved.
- Combined status endpoint returned no status records. This is not a success signal.
- The only artifact returned for Campaign run 38301629067 at inspection time was `verified-gradle-wrapper-jar`, SHA-256 digest `6fe47c5b288c0ed25692b5e40103bb26f76783b5051bb4810852a972bc7b5884`. It is not an APK artifact.
- APK package identity, Android unit-test result, lint result, APK signature, APK SHA-256, source provenance and APK upload: NOT VERIFIED at this inspection point.

## Scope/results

- Python backend tests: TESTED/VERIFIED PASS on audited SHA 31ba73f.
- Campaign Python tests: TESTED/VERIFIED PASS on audited SHA 31ba73f.
- Campaign Android build/unit tests/lint: IN PROGRESS; not verified.
- Separate APK workflow: EXTERNAL STARTUP BLOCKER, no jobs/artifacts.
- Do not treat Campaign CI as a substitute for the separate APK workflow.

## Remaining P0 blockers

- Wait for and inspect Campaign Android job steps and artifacts.
- Resolve or obtain actionable diagnostics for the separate workflow's repeated `startup_failure`.
- Inspect actual APK metadata/signature/checksum/provenance/artifact contents for the final tested SHA.
- FI-01–FI-12, complete mutation-path proof, compound-order recovery, full SQLite migration/concurrency matrix, and end-to-end BL-004 sentinel verification remain open.

Status: `PARTIALLY IMPLEMENTED — P0 OPEN`. No live credentials or live orders used.