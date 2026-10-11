# Baseline logs — partial CI evidence, exact-HEAD baseline NOT RUN

This directory distinguishes remote CI logs from an exact-branch-head local baseline. Do not fabricate local output or present CI merge results as exact-HEAD results.

## GitHub Actions evidence retrieved

Repository: `https://github.com/19wolfalone92/Williams`  
Source branch: `audit/williams-2.1-task-002-release-contract`  
Branch commit reported by workflow metadata: `bb6cd92ca7bb07369e26e77cab214b04229c3435`  
Actual checkout SHA shown in logs: `89c866cfe63ac8a76f4ebb038d6166fafb745bd8` (shallow checkout, `fetch-depth: 1`, PR merge commit).

### Backend job — run 38077212234, job 114286589019

- OS: Ubuntu 24.04.5, runner image ubuntu-24.04 (20261004.327.1).
- Git: 2.55.0.
- Python: CPython 3.13.16.
- Install command: `python -m pip install -r requirements.txt pytest`.
- Release contract command: workflow step `Run release contract gate`; output `WILLIAMS RELEASE CONTRACT GATE: PASS (VERSION=4.25.0)`.
- Test command: workflow step `Run safe offline tests`, `python -m pytest -q`.
- Result: `158 passed, 4 warnings in 4.32s`.
- Workflow conclusion: success.
- Note: this run used merge checkout SHA, not branch HEAD.

### Campaign job — run 38077212236, job 114286588784

- OS: Ubuntu 24.04.5, runner image ubuntu-24.04 (20261004.327.1).
- Git: 2.55.0.
- Python: CPython 3.12.15.
- Install command: `python -m pip install -r requirements.txt`.
- Test command: `python -m pytest -q ...` (workflow step `Run deterministic safety and campaign tests`; output captured as `campaign-pytest-output.txt`).
- Result: `169 passed, 4 warnings in 4.52s`.
- Workflow conclusion: success.
- Note: this run used merge checkout SHA, not branch HEAD.

### Android build — run 38077212239, job 114286588902

- OS: Ubuntu 24.04.5; Git 2.55.0.
- Checkout: shallow merge SHA `89c866c...`.
- JDK: Temurin 17.0.20+1.
- Workflow Gradle setting: 8.14.5.
- Android platform: `platforms;android-36`; build-tools `36.0.0`.
- Steps reported success: release contract gate, lint, Android unit tests, debug APK build, release APK build, APK version checks, metadata inspection, persistent debug signature verification, SHA-256 checksum generation, artifact upload.
- Multiple Gradle invocations logged `BUILD SUCCESSFUL`.
- Workflow conclusion: success.
- Limitation: artifact bytes/checksums were not downloaded for independent recalculation; CI logs do not establish physical-device runtime.

## Exact-HEAD checks not performed

- Full checkout/bundle validation, `git bundle verify`, `git fsck --full`, clean worktree check.
- Python and Android baseline run directly on branch HEAD `bb6cd92...` or report HEAD `fd1d5b9...`.
- Repeated exact-HEAD run with dependency lock/snapshot.
- Protocol and SQLite migration compatibility suite.
- Independent APK SHA-256/package/signature inspection.
- Physical device installation, ADB diagnostics, recovery/scanner/gate runtime validation.

## Conclusion

Remote CI provides useful partial evidence, but it ran on merge SHA `89c866c...` and used different Python minor versions across workflows (3.13.16 and 3.12.15). The working branch wrapper declares Gradle 8.14.6 while the Android workflow provisions 8.14.5. Exact-HEAD baseline and release provenance remain **BLOCKED**.
