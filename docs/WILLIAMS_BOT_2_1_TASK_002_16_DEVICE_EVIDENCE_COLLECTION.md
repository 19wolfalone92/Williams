# WILLIAMS BOT 2.1 — TASK 002.16
## Reproducible Android Device Evidence Collection, APK Provenance Closure & Runtime Readiness Gate

**Assessment date:** 2026-10-11 UTC  
**Status:** `BLOCKED — DEVICE EVIDENCE INCOMPLETE`  
**Separate APK provenance result:** `PASS — downloaded CI ZIP and embedded APK SHA-256 verified`  
**Device runtime:** `NOT RUN — no authorized physical Android device/ADB evidence available in this execution environment`

## 1. Scope and safety

This task collected public CI provenance, downloaded the debug artifact, independently calculated hashes, and prepared a read-only ADB evidence collector with deterministic helper tests. No APK was installed on a device. No application data was reset. No exchange API was called. No BUY/SELL/OCO or other trading mutation was submitted. Mainnet was not used. PR #45 remains open and unmerged; `main` was not modified.

This report does not establish strategy correctness, financial-writer ownership, ledger atomicity, exactly-once accounting, or trading readiness.

## 2. Source and CI provenance

| Field | Observed value |
|---|---|
| Repository | `19wolfalone92/Williams` |
| Control branch | `audit/williams-2.1-task-002-release-contract` |
| PR | [#45](https://github.com/19wolfalone92/Williams/pull/45) |
| PR state | OPEN, not merged |
| PR head SHA | `bb6cd92ca7bb07369e26e77cab214b04229c3435` |
| CI checkout / merge SHA | `89c866cfe63ac8a76f4ebb038d6166fafb745bd8` |
| CI run | [Build Android APK #1531](https://github.com/19wolfalone92/Williams/actions/runs/38077212239) |
| Android job | `Build APKs`, job ID `114286588902` |
| Job conclusion | SUCCESS |
| Artifact creation time | 2026-10-10 18:55:17 UTC (debug APK artifact) |

The merge commit has parents `04da5f9c09572e99ff310be3a1908179165e528c` and `bb6cd92ca7bb07369e26e77cab214b04229c3435`. GitHub reports the same tree SHA for the PR head and merge checkout: `b00d3a46381186bdfad25a71e02d52ff90fa22fc`. This supports that the file tree used by the merge checkout is identical to the PR head tree; the commit identities themselves are not identical.

The Android workflow steps for release contract gate, lint, Android unit tests, debug/release APK builds, APK version checks, APK metadata inspection, signature check and checksum generation all report success. CI success alone is not physical-device evidence.

## 3. Independent artifact verification

Downloaded the debug ZIP using the GitHub Actions artifact download endpoint and verified it in the local working environment.

| Item | Observed value | Result |
|---|---|---|
| Artifact | `williams-bot-debug-apk-1531.zip` | Downloaded |
| Artifact ID | `11679353038` | Present, not expired at inspection |
| ZIP size | `21,312,713` bytes | Measured after download |
| ZIP SHA-256 | `8b60b7916f9c010d9e0e7433b206e47512d843a27c77a78fb302c1ede0f7a8d6` | Independently calculated |
| Nested APK | `app-debug.apk` | Extracted in isolated temporary directory |
| APK size | `21,312,605` bytes | Measured after extraction |
| APK SHA-256 | `4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5` | **MATCH** expected control |
| Sidecar checksum | `app-debug.apk.sha256` | Contents match independently calculated APK hash |

The ZIP digest and nested APK digest are different and were checked independently. The expected APK SHA-256 matches exactly.

Expected CI metadata is package `com.williamsbot.debug`, version `4.25.0-debug`, versionCode `1531`. The Android workflow's APK version verification and metadata inspection steps passed. This execution environment did not have `adb`, `aapt` or `apkanalyzer` available, so it did not independently decode package metadata from the downloaded binary with Android SDK tooling.

The unsigned release APK was not installed or used.

## 4. Device evidence collection tool

Added on a separate audit branch, based on the control PR head:

- `tools/device_evidence/collect_android_evidence.py`
- `tools/device_evidence/test_collect_android_evidence.py`

The collector is read-only with respect to app data and exchange state. It does not install an APK, clear logcat, reset app storage, invoke exchange endpoints, or submit orders. It refuses to silently select a device when multiple devices are connected. It checks package/version metadata, attempts to pull and hash the installed base APK separately, captures a bounded logcat window without clearing prior logs, conservatively redacts sensitive log fields, and creates a JSON snapshot plus SHA-256 manifest.

Example invocation from a checkout containing the tool:

```bash
python3 tools/device_evidence/collect_android_evidence.py --output evidence/task-002-16
```

If multiple authorized devices are connected, explicitly select the target:

```bash
python3 tools/device_evidence/collect_android_evidence.py --serial <ADB_SERIAL> --output evidence/task-002-16
```

The output directory must not already exist. A non-zero exit code indicates blocked or incomplete evidence. Review logs locally before sharing them. The generic collector intentionally labels NativeEngine, SQLite, market data, Scanner, Execution Gate and Recovery Gate as UNVERIFIED because generic ADB commands cannot establish those application-specific states.

### Test status

A deterministic `unittest` suite was added for device-list parsing, package metadata parsing, redaction behavior and hash determinism. The tests were **not executed in a trusted repository checkout in this task**, so they are prepared but not claimed as passing. CI must run them before acceptance. The tests do not replace physical-device verification.

## 5. Evidence matrix

| Requirement | Result | Evidence / limitation |
|---|---|---|
| PR and CI identity | PASS | PR API metadata and workflow run/job metadata |
| Checkout relationship | PASS — tree-level relationship established | Merge commit parents and identical tree SHA |
| Downloaded ZIP hash | PASS | Local SHA-256 calculation |
| Nested APK hash | PASS | Local SHA-256 calculation equals expected value |
| APK package/version decoded independently | PARTIAL | CI metadata/version steps passed; local Android SDK tools unavailable |
| Physical device present | BLOCKED | No authorized ADB device available in this execution environment |
| Installed package identity | NOT RUN | No physical device |
| Installed APK SHA-256 | NOT RUN | No physical device |
| Cold launch / Activity | NOT RUN | No physical device |
| NativeEngine / autonomous runtime | NOT RUN | No app runtime diagnostics |
| SQLite and market-data state | NOT RUN | No app diagnostic snapshot |
| WebSocket and Scanner | NOT RUN | No real device event evidence |
| Recovery Gate / Execution Gate | NOT RUN | No device test; no mutation spy run |
| Secret scan of actual device logs | NOT RUN | No device logs |
| Financial ownership / atomicity | OUT OF SCOPE / UNRESOLVED | Separate P0 tasks remain required |

## 6. Exact next steps

1. Run the collector from a trusted checkout and execute the unit tests.
2. Connect the intended physical Android phone with USB debugging enabled. If more than one device is connected, specify `--serial`.
3. Confirm the installed package/version. Do not install or overwrite an existing app before capturing its initial state.
4. Compare the pulled installed base APK hash with the expected hash. If it cannot be pulled, record the limitation; do not substitute the downloaded APK hash.
5. Inspect the redacted log and JSON snapshot. Do not publish a log if secret redaction is uncertain.
6. Obtain application-specific diagnostics for NativeEngine, SQLite, REST/WebSocket freshness, canonical symbol list, Scanner input/processed counts and Gate blockers. The generic collector does not claim to expose these.
7. Run safe process/network interruption scenarios only after preserving the initial snapshot. Do not fabricate production fills or unknown orders.

## 7. Final decision

**TASK 002.16: `BLOCKED — DEVICE EVIDENCE INCOMPLETE`.**

APK artifact provenance is verified to the level supported by CI metadata and independent ZIP/APK SHA-256 calculations. The PR-head and CI merge-checkout relationship is also established at the Git tree level.

The task cannot be accepted as a complete device evidence package yet because no physical device was available, the installed APK was not identified, the collector tests were not executed, and application-specific runtime diagnostics were not observed. No result marked NOT RUN is treated as PASS.

Trading remains prohibited. This report does not authorize Mainnet, Testnet trading mutations, or removal of any P0 execution block.
