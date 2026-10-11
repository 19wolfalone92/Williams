# WILLIAMS BOT 2.1 — TASK 002.2
## Trusted Baseline, Version Contract & Reproducible Release Gate

**Status: BLOCKED — NOT ACCEPTED**  
Audit date: 2026-10-11 UTC. Main was not modified and PR #45 was not merged.

## 1. Source/ref snapshot

- Repository: `19wolfalone92/Williams`
- Branch: `audit/williams-2.1-task-002-release-contract`
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c`
- Branch head before TASK 002.2 changes: `0189f85f3268dc96f4950f700628376d71cd57d4`
- PR #45 head at that point: `0189f85...`, base `main`; PR remained open/unmerged.
- Prior CI merge SHA: `0330466f75d17cec1a13cd931563f402e394dbb0`; its parents are the branch head `0189f85...` and main `04da5f9...`.
- TASK 002.2 workflow correction commit: `b109a3b1351e8e7862c42aab056f164bd1916570`. It changes the Android workflow to check out the exact PR head SHA and align Gradle with wrapper 8.14.6.
- Latest CI triggered by that workflow correction at report time: Android run `38144417385`, backend run `38144417350`, campaign run `38144417360`; all queued when last checked. These are not PASS evidence until completed and logs are inspected.

GitHub refs/PR metadata were retrieved through the GitHub API. A complete local Git checkout was not created; worktree status and all local refs are therefore unknown. `git bundle verify` was NOT RUN (no bundle); `git fsck --full` was NOT RUN (no full local object database). No source bundle/archive was generated, so no source-archive SHA-256 applies. GitHub object hashes alone do not independently establish the trustworthiness of the source origin.

## 2. Previous CI evidence — keep SHA boundaries explicit

The completed runs `38142664759`, `38142664806`, and `38142664769` reported PR head metadata `0189f85...`, but each job log explicitly checked out merge SHA `0330466...` with shallow `fetch-depth: 1`.

- Backend: CPython 3.13.16; release gate PASS for version 4.25.0; `python -m pytest -q`: **158 passed, 4 warnings in 4.29s**.
- Campaign: CPython 3.12.15; release gate PASS for version 4.25.0; deterministic safety/campaign tests: **169 passed, 4 warnings in 3.26s**.
- Android: Ubuntu 24.04.5, Git 2.55.0, Temurin 17.0.20+1, explicit Gradle 8.14.5, Android platform 36/build-tools 36.0.0. APK build/version checks and CI debug-signature checks succeeded for the merge tree.
- These results are successful CI results for merge SHA `0330466...`, not exact isolated branch-head test results.

## 3. Version and environment contract

- Canonical app/backend version: `4.25.0`.
- CI `aapt` output for the prior merge-tree build: debug `com.williamsbot.debug`, versionName `4.25.0-debug`, versionCode `2114`; release `com.williamsbot`, versionName `4.25.0`, versionCode `2114`.
- Target source Gradle wrapper: `8.14.6`; previous Android workflow explicitly provisioned `8.14.5`. The workflow was corrected in `b109a3b...` to provision 8.14.6, validate the wrapper, and invoke `./gradlew`; that correction remains pending its new CI results at the time of this report.
- Python workflows used different minor versions: 3.13.16 and 3.12.15.
- `requirements.txt` is not a complete hash-pinned dependency lock. One-run dependency resolution is not a reproducible lock.

## 4. APK byte evidence (prior merge-tree run)

Downloaded debug and unsigned release artifact ZIPs from Android run `38142664769`. The artifact ZIP SHA-256 values independently computed in the audit container matched GitHub artifact metadata digests. The extracted APK SHA-256 values independently computed over the bytes matched the checksum files packaged in each ZIP.

| Variant | Artifact ID | ZIP SHA-256 | APK bytes | APK SHA-256 |
|---|---:|---|---:|---|
| Debug | `11703611504` | `482d9f353599be4a78452c0c2d60b5f470e0aa9ee35529c319f4768da3b55912` | 21,312,601 | `9f7c0ad5dba71fb8a2761727902b2ddeade99b09b19424760a956d8753e7ae55` |
| Release unsigned | `11703805990` | `95eabc91cf7503ea0eaeb7ae9999a5bf57199a38f85ac06929ac6c25eb9aff2b` | 14,842,560 | `340a1eeccd3dae45685de0fde4a1191e3a483912e5e7996a35fcf318094e5a3e` |

The debug signature passed Android v2 verification in CI, and CI compared the APK signer certificate digest to the configured persistent debug keystore. Independent local signature verification and certificate fingerprint extraction were not possible because Android `apksigner` was unavailable in the audit container. The release APK is explicitly unsigned and is not a signed release candidate.

These APKs were built from merge SHA `0330466...`, not the isolated target branch tree. Their hashes and metadata are now checked, but exact source-to-APK provenance is still BLOCKED. The historical SHA `4823d5a4...` is not the current debug APK hash and remains carry-forward-only.

## 5. Release gate matrix summary

| Gate | Status | Reason |
|---|---|---|
| Trusted source origin | BLOCKED | No independent source-origin attestation |
| Exact-commit build/test | BLOCKED / PENDING | Previous jobs used merge SHA; new exact-head workflow runs were queued |
| Git bundle / full object integrity | NOT RUN | No bundle/full local clone |
| Locked environment | BLOCKED | No hash-locked Python dependencies; Python minor mismatch |
| Version metadata | PARTIAL PASS | CI checks agree on 4.25.0, but previous build used mismatched Gradle |
| APK bytes/hash | PASS for downloaded files | Hashes independently recalculated and match artifact metadata/checksum files |
| APK source provenance | BLOCKED | Previous APKs are from merge SHA |
| Debug signature | PARTIAL | CI verification passed; no independent local certificate verification |
| Signed release APK | FAIL / OPEN | Available release APK is unsigned |
| Physical-device runtime | NOT RUN | No ADB/device evidence |
| Real trading/Mainnet | NOT performed | No order APIs called; no orders submitted |

Hard rule: any critical BLOCKED, FAIL, or NOT RUN gate blocks release. Successful build does not establish app safety or physical-device runtime correctness.

## 6. Required next evidence

1. Inspect completion and logs for runs `38144417385`, `38144417350`, `38144417360`; verify the Android checkout SHA equals the exact PR head SHA and the wrapper/Gradle versions match.
2. Independently verify the newly produced APK ZIP digest, APK SHA-256, package/version metadata, and debug certificate identity.
3. Pin a canonical Python minor version and add a fully locked, hash-verified dependency environment.
4. Obtain a trusted full clone or signed source bundle and run `git fsck --full`; run `git bundle verify` only if a bundle is supplied.
5. Perform physical-device install/runtime and recovery/scanner/execution-gate checks separately.

**Decision: TASK 002.2 remains BLOCKED — NOT ACCEPTED.**
