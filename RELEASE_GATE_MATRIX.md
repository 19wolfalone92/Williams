# TASK 002.2 — Release Gate Matrix

**Overall: BLOCKED — NOT ACCEPTED**

The release gate must fail closed. Unknown is not PASS. A successful build is not evidence of strategy correctness, trading safety, or physical-device runtime behavior.

| Gate | Required evidence | Current status | Blocking reason |
|---|---|---|---|
| Trusted source | Approved origin / trusted commit identity | BLOCKED | GitHub metadata observed, independent origin attestation not verified |
| Exact source SHA | Checkout/build tree equals declared branch commit | BLOCKED | Jobs checked out merge SHA `0330466...`, target branch HEAD was `0189f85...` |
| Git integrity | Full clone, `git fsck --full`; `git bundle verify` if bundle used | NOT RUN | No full local object database or bundle |
| Clean worktree/refs | Exact commands and output | NOT RUN | No local checkout |
| Exact-SHA tests | Required tests run on the declared source SHA with exit codes/logs | BLOCKED | Current runs test merge tree, not isolated head |
| Critical tests | Full safety audit and required test suites, not silently skipped | PARTIAL | CI jobs pass on merge tree; does not prove exact-HEAD coverage |
| Python dependencies | Lock with exact versions and hashes | BLOCKED | Requirements are not a complete hash-locked environment |
| Python runtime | Canonical pinned minor version | FAIL / OPEN | Backend 3.13.16; campaign 3.12.15 |
| Gradle contract | Workflow and wrapper use same declared version | FAIL / OPEN | Wrapper 8.14.6 vs workflow 8.14.5 |
| Version metadata | applicationId/versionName/versionCode agree with canonical contract | PARTIAL PASS | CI aapt checks passed; not independently parsed locally |
| APK hashes | Downloaded APK SHA-256 independently recomputed | PASS for bytes | Debug `9f7c0ad5...`; unsigned release `340a1eec...` |
| Debug signature | Android signature scheme and certificate identity independently verified | PARTIAL | CI v2 verification passed; independent local apksigner/cert fingerprint unavailable |
| Release signature | Signed release artifact verified | FAIL / OPEN | Available release APK is unsigned |
| Source-to-APK provenance | Exact build SHA + artifact hashes + verifiable attestation | BLOCKED | Build tree is merge SHA, no provenance attestation linking APK to exact branch tree |
| Physical device runtime | Install, app startup, recovery/reconcile, scanner and execution gate evidence | NOT RUN | No ADB/device session |
| Safety scope | No real orders/Mainnet actions during audit | PASS (audit activity) | No Binance order operations were submitted |

## Hard release rule

Release must remain blocked if any critical gate is BLOCKED, FAIL, or NOT RUN. Do not convert unknown results to PASS, weaken tests, infer APK identity from version strings, or treat artifact upload as trust proof. Do not store or expose signing secrets. Mainnet and live trading are outside this gate.
