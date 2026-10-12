# TASK 001.5 — Baseline and Change Inventory

- Audited executable-source SHA: `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf`
- Required starting SHA: `8a5ea866a6d7fa10960aca25b2123a34c11601c0`
- PR #66: OPEN, Draft, unmerged
- Working branch: `night-shift-audit-20261012`
- Base branch: `fix/pytest-safe-collection` at `0007e79b1afde86b8c736128e6d09bfdd370106f`
- Compare result at audit time: 7 commits ahead, 0 behind; required starting SHA is the merge base/ancestor.

## Commits since the required starting SHA

1. `e093e80a1e63ccee07372dd2366e446987c6313e` — never retransmit signed mutations from transport retry loop.
2. `1619b0ed7ef1949b3458f34a73f926e439a75cb3` — require durable evidence before reconciliation closure.
3. `fc2c6a9222354a33942191c5bc1278b5f706af44` — update Android ledger tests and restore test-class scope.
4. `525733629235532ab711004bada823e4ca86aa34` — add evidence-required and migration tests.
5. `a2362550c76bb59dcde791fa792c7b3c35692c92` — add transport retry policy.
6. `fc3bb6827e54c4f6644091a61948c554449b965b` — use the retry policy in StandaloneRuntime.
7. `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf` — add transport-policy unit tests.

## Source inventory

Inspected actual source for `MutationIdentity.kt`, `DurableMutationLedger.kt`, `StandaloneRuntime.kt`, `ExecutionGate.kt`, `execution_barrier.py`, `execution_recovery.py`, `binance_client.py`, `db.py`, `trader.py`, `ci_release_gate.py`, `risk_engine.py`, Android ledger tests, Python recovery/barrier/SQLite tests, and `.github/workflows/android-apk.yml`.

Android mutation transport currently centralizes signed REST mutations in `StandaloneRuntime.signedRequest()`, with wrappers including `signedPost()`, `signedDelete()`, and `signedCancelReplace()`. This is not yet a proof that every Android mutation caller and every Python mutation caller is contained; the complete caller-to-sink graph and automated bypass checker remain open.

## CI observed for this source SHA

- Python backend workflow run `38264561046`: SUCCESS. Logs report 219 passed, 4 warnings; release contract gate, Python compilation, static safety audit, and safe offline tests completed.
- Williams Campaign CI run `38264561079`: Python job SUCCESS; Android build/static-check job was still in progress at last observation.
- Standalone Android APK workflow run `38264563243`: `startup_failure`, zero available jobs. This is distinct from the Campaign CI Android build and does not establish a Kotlin compilation result.

## Findings and limitations

A previous Campaign CI Android build at SHA `8a5ea866a6d7fa10960aca25b2123a34c11601c0` failed Kotlin compilation because tests in `DurableMutationLedgerTest.kt` were outside the test class (unresolved `ledger` references). The class scope was repaired in this task. Recheck the latest run before drawing a final conclusion. No real credentials or live trading mutations were used by the checks described here.
