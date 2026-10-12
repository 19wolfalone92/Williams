# TASK 001.7 — Exact-HEAD CI Recovery

PR #66 remains OPEN, Draft, unmerged; base `fix/pytest-safe-collection`. Required starting SHA `f9d96b98c639c5a7a6232f4ef9a5f15be3342cb4` matched the PR head at task start.

## Current source and exact-SHA CI snapshot
- Current code/test SHA: `7e7b240f07d11e0077d1b1d741a8087f4846a750`.
- Python backend run `38278337094`: queued/in progress when this snapshot was written; do not count as passed until completed.
- Williams Campaign CI run `38278336855`, same SHA: Python job SUCCESS, `233 passed, 4 warnings`, including `tests/test_mutation_boundary_inventory.py`; Android job was still executing `Build debug APK and run unit tests`.
- Standalone Android APK run `38278338697`, same SHA: `startup_failure`, zero jobs and zero artifacts. The same no-job outcome was observed on runs `38277629884`, `38277478791`, `38277180534`, `38276976147`, and earlier runs. The available rerun API returned HTTP 403, “This workflow run cannot be retried.”
- A prior Campaign Android run `38277628808` on SHA `f60e41b31f229d616403e79e34f477aa86663cdb` reached Gradle and failed at `compileDebugUnitTestKotlin`: `BinanceTradeEvidenceTest.kt:79`, where `JSONObject.remove()` was incorrectly assigned as a JSONObject. Corrected in `7e7b240...`; the fresh Android job must establish whether this correction resolves compilation and whether remaining tests/lint/APK validation pass.
- The standalone failure is not a Gradle/test failure: GitHub recorded a PR workflow run but created no job, logs, or artifact. The exposed API gives no deeper scheduler diagnostic; cause remains BLOCKED BY EXTERNAL DEPENDENCY.
- `.github/workflows/android-apk.yml` now includes `tests/**` and `reports/task_0017_*.md` path filters and explicitly verifies expected debug package `com.williamsbot.debug`, release package `com.williamsbot`, target SDK 36, APK signature and SHA-256/provenance.
- Android APK, signature, checksum and metadata are NOT VERIFIED on the current SHA until the Campaign Android job finishes; the standalone workflow has not executed a step.
