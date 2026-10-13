# TASK 001.5 — Baseline and Change Inventory

**Executable source SHA audited:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Required starting SHA:** `8a5ea866a6d7fa10960aca25b2123a34c11601c0`  
**PR #66:** OPEN, Draft, unmerged  
**Base:** `fix/pytest-safe-collection` at `0007e79b1afde86b8c736128e6d09bfdd370106f`

## Baseline and ancestry

The required starting SHA is an ancestor of the working branch. Compare reports 21 commits ahead and 0 behind. No base-branch mutation, merge, close, reset, or force-push was performed.

## Commit sequence since the required starting SHA

1. `e093e80a1e63ccee07372dd2366e446987c6313e` — prevent transport retry of signed mutations.
2. `1619b0ed7ef1949b3458f34a73f926e439a75cb3` — require evidence for RECONCILED.
3. `fc2c6a9222354a33942191c5bc1278b5f706af44` — repair test class scope and evidence tests.
4. `525733629235532ab711004bada823e4ca86aa34` — add migration/evidence tests.
5. `a2362550c76bb59dcde791fa792c7b3c35692c92` — isolate transport retry policy.
6. `fc3bb6827e54c4f6644091a61948c554449b965b` — use retry policy in runtime.
7. `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf` — add retry-policy tests.
8. `187c4824a68d03fd45a6d3f1545c746de34f5068` — first evidence snapshot (superseded by this update).
9. `0f17ed87783bd4c98264daaf6805b9142eb51427` — explicit null assertions.
10. `971feebc86d0d50489db263f28ea297d6de8c503` — scope evidence to Testnet order mutations.
11. `b4d0de3c97452adbb97c63eca1de77f37c286313` — test evidence scoping.
12. `c83efe6bf981f8a8ffddce9a1e1051c0062fc952` — explicit SQLite bind argument typing.
13. `159804b25871d9bfe256b218a03cb086eb43c064` — client-ID uniqueness and audit redaction.
14. `c0c0b5a7712cce43d1eef63429efa45cab535d07` — exact-SHA and APK provenance workflow changes.
15. `b7abebbfdcdac81c4dbbaa643ed818b55dc2c139` — provenance newline correction.
16. `3efb9065fea9b32dc063c99a4042a49b087a8182` — wrapper bootstrap attempt.
17. `cda4b35c0ed98410f0b3f8b869ed140b723b898d` — upload verified wrapper JAR artifact.
18. `b63de69d3df6b0d281a4d0e042baf6fa58db6a0f` — direct-download attempt (HTTP 404; superseded).
19. `93480cfbd186837b1ed5aaf1640dcd9a79c2cd91` — generate wrapper using trusted Gradle.
20. `523b1ee23831d0d0e97ddea2a1e8c6b1129d4461` — persistent audit sink secret test.
21. `cf35f2fb8d2f68770f191efaa272864e7debe04c` — explicit assertions in audit redaction test.

## Actual source inspected

Inspected `MutationIdentity.kt`, `DurableMutationLedger.kt`, `StandaloneRuntime.kt`, `ExecutionGate.kt`, `TradingAuditStore.kt`, `execution_barrier.py`, `execution_recovery.py`, `binance_client.py`, `db.py`, `trader.py`, `ci_release_gate.py`, `risk_engine.py`, Android ledger/transport/redaction tests, Python recovery/barrier/SQLite/secret tests, and both Android workflow definitions.

Android mutation wrappers currently converge on `StandaloneRuntime.signedRequest()`; Python submission is guarded by `ExecutionBarrier` and recovery uses read-only query paths. This is not yet an exhaustive call-graph proof for every caller or legacy route.

## Exact-SHA CI

- Python backend run `38268195155`: SUCCESS; 219 passed, 4 warnings. Release contract gate passed; Python compilation and static safety audit ran.
- Campaign CI run `38268195164`: SUCCESS. Android unit-test task, lint, debug APK build, checksum/signing/provenance step, and artifact upload succeeded.
- Standalone Android workflow run `38268196664`: `startup_failure`, zero jobs. It remains distinct from Campaign CI and provides no build result.
- APK artifact `11756315887`; lint artifact `11755463715`; verified wrapper artifact `11755682525`.

No real credentials or live trading mutations were used.
