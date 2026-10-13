# TASK 002.2 — Environment Manifest

**Status: PARTIAL / REPRODUCIBILITY BLOCKED**  
Environment evidence below belongs to the stated GitHub Actions jobs; do not merge values from different jobs into one supposedly identical environment.

## Current workflow evidence

| Job / run | Checked-out SHA | OS / runner | Runtime/toolchain | Result |
|---|---|---|---|---|
| Backend, run `38142664759`, job `114479536827` | Merge `0330466f75d17cec1a13cd931563f402e394dbb0` | Ubuntu 24.04.5; image `ubuntu-24.04` / `20261004.327.1`; Git 2.55.0 | CPython 3.13.16 | release gate PASS; 158 passed, 4 warnings, 4.29s |
| Campaign, run `38142664806`, job `114479557254` | Merge `0330466f75d17cec1a13cd931563f402e394dbb0` | Ubuntu 24.04.5; Git 2.55.0 | CPython 3.12.15 | release gate PASS; 169 passed, 4 warnings, 3.26s |
| Android build, run `38142664769`, job `114479536954` | Merge `0330466f75d17cec1a13cd931563f402e394dbb0` | Ubuntu 24.04.5; image `20261004.327.1`; Git 2.55.0 | Temurin JDK 17.0.20+1; explicit Gradle 8.14.5; Android platform 36; build-tools 36.0.0 | build and metadata/signature workflow steps succeeded |

## Dependency reproducibility

- Python install command in backend workflow: `python -m pip install -r requirements.txt`; no repository lock file or hash-pinned complete dependency lock was verified.
- Campaign workflow also installs from `requirements.txt`; it uses Python 3.12 while backend uses Python 3.13. The jobs are separate evidence, not a common pinned environment.
- Gradle wrapper file at target source SHA `0189f85...` specifies Gradle `8.14.6`; Android workflow environment explicitly provisions Gradle `8.14.5` and invokes the installed Gradle executable. This is a version contract discrepancy.
- JDK is pinned by major version 17, but the runner image and patch build are mutable workflow environment components.
- Android SDK platform/build-tools were specified as `platforms;android-36` and `build-tools;36.0.0`.

## Required reproducibility evidence still missing

- Exact-branch-SHA test/build run.
- Locked and hash-verified Python dependency graph.
- A single declared canonical Python minor version across Python workflows.
- Workflow invocation using the repository Gradle wrapper and matching wrapper version.
- Captured local stdout/stderr and exit codes for `git bundle verify`, `git fsck --full`, clean-worktree check, and direct exact-SHA test execution.
- Physical-device/ADB environment and runtime test logs.

No secrets or signing key material are included in this manifest.
