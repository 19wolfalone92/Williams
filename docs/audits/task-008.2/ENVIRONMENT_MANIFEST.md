# Environment manifest — TASK 008.2

## Source identity

- Repository: `19wolfalone92/Williams`
- Branch at baseline run: `audit/williams-2.1-task-008-spot-testnet`
- Exact checkout SHA in job log: `1a7e7209c99152c3bf29f3e2f07e7db088d9243b`
- Workflow run: [38129367877](https://github.com/19wolfalone92/Williams/actions/runs/38129367877)
- Run window: 2026-10-11 09:43:12–09:47:50 UTC
- CI used a shallow checkout (fetch-depth 1); therefore the CI log does not prove a complete local clone, all refs, or clean local worktree status.

## Python job

- Runner OS: Ubuntu 24.04.5 LTS
- Runner image: `ubuntu-24.04`, version `20261004.327.1`
- Runner version: `2.337.0`
- Git: `2.55.0`
- Python: `3.12.15`
- Dependency installation: `python -m pip install -r requirements.txt`
- Dependency manifest blob SHA at target commit: `c020cd90b7f62b04f383d1181775bfd6803d13e8`
- Dependencies are specified with lower-bound ranges; no pinned lock file was used by this workflow.
- SQLite runtime version: **NOT RECORDED** in the preserved workflow output.
- Environment variables: workflow-provided Python paths and CI variables are in the raw log. No Binance secret values are required for this fake-client test suite; any masked CI credentials remain redacted by GitHub.

## Android job

- JDK distribution/version requested by workflow: Temurin 17
- Gradle version requested: `8.14.6`
- Commands: `gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace`
- Android SDK version: **NOT RECORDED as a pinned version in the workflow manifest**.
- Android job conclusion: success. See raw log for task-by-task output.

## Exact workflow command order

Python job, in order:
1. checkout exact SHA
2. setup Python 3.12
3. `python -m pip install -r requirements.txt`
4. `python -m compileall -q .`
5. `python full_safety_audit.py`
6. run the full explicit pytest list in `.github/workflows/campaign-ci.yml`, piping combined output to `campaign-pytest-output.txt` with `tee`.

Android job:
1. checkout
2. setup JDK 17
3. setup Gradle 8.14.6
4. `chmod +x ./gradlew`
5. `gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace`
6. upload debug APK and lint report.

## Exit codes / results

- Python compile step: success (exit 0 implied by completed successful CI step).
- Full static safety audit: success (exit 0 implied by completed successful CI step).
- Python pytest step: failure, exit code 1; `230 passed, 11 failed, 4 warnings in 6.40s`.
- Android build/unit/lint step: success.
- Overall workflow: failure because Python tests failed.

## Evidence files

- `baseline-logs/python-job-114440299305.log` — complete raw Python job log recovered from GitHub Actions.
- `baseline-logs/android-job-114440299494.log` — complete raw Android job log recovered from GitHub Actions.

## Limitations

This is a recorded remote CI execution on the exact SHA, not an independent local replay. No local OS checkout, SQLite version, full refs inventory, archive SHA-256, or Android SDK pin was produced in this task. Those fields remain NOT RECORDED; they are not inferred.
