# TASK 001.9 — Mutation Boundary Proof

- Code/test SHA: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Campaign Python test step [38306238359](https://github.com/19wolfalone92/Williams/actions/runs/38306238359): 259 passed, 4 warnings; overall run was cancelled during cleanup/concurrency. Android build had not reached APK validation.
- Branch HEAD before this report refresh: `1b051659dba73913ad3c2e0a39f8278042cf4729`.

## Implemented controls

- `tests/test_mutation_boundary_inventory.py` checks raw Android OkHttp call-site inventory and requires Binance mutations to remain in the signed durable sink.
- Python HTTP mutations are centralized through `binance_client.py`; `ExecutionBarrier` is the final admission point and `ExecutionRecoveryWorker` performs read-only reconciliation.
- FI fake-exchange tests assert SQLite state and actual mutation counts for submission failure, lost response, failed persistence and retry while unresolved.
- Recovery explicitly refuses compound cancel-replace and order-list cancellation types before exchange lookup.
- OCO recovery rejects a persisted-vs-returned order-list ID conflict.

## Remaining gaps

- Static inventory is not a full dynamic call graph for every Android/Python emergency exit, background worker, adapter, diagnostic and test path.
- The fake-exchange suite is Python-side; no complete Android production-boundary fake-exchange matrix exists.
- Compound mutation recovery is blocked rather than falsely claimed complete.
- Android and Python SQLite are separate stores, not a shared atomic transaction.

Status: PARTIAL — P0 OPEN.

## TASK 001.12 exact-HEAD addendum

- At SHA `4e7ad93990b4b9d3b5291bed9d83909fbfac3bcf`, source inspection covered `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`, `DurableMutationLedger.kt`, `BinanceTradeEvidence.kt`, `ExecutionGate.kt`, and Python `execution_barrier.py` / `execution_recovery.py`.
- Campaign Python suite passed 262 tests on [run 38341931762](https://github.com/19wolfalone92/Williams/actions/runs/38341931762). Android job 115114647875 had not yet completed unit tests, lint or APK proof when this addendum was written.
- Android source has durable mutation identity/evidence checks and rejects a conflicting previously persisted exchange order ID. Python recovery rejects compound cancel/cancel-replace/list cancellation when a single-order lookup cannot prove the complete outcome.
- This is source review plus Python tests, not complete dynamic proof that every Android order-create/cancel/replace entry point is reachable only through one boundary. Android tests and a full call-site inventory remain required; P0 remains open.
