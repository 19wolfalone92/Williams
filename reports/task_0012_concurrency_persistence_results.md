# TASK 001.2 — Concurrency and persistence
**Status: PARTIAL — P0 OPEN**

## Inspected safeguards
- Ledger uses SQLite transactions and a unique primary key on operation_id.
- State changes use an expected-state update and require exactly one row to change.
- Kotlin methods are synchronized per ledger instance; this alone does not establish cross-instance correctness, though SQLite state compare-and-update adds a second guard.
- The existing TASK 001.1 Robolectric test file covers persistence after reopen, identical duplicate intent, conflicting operation ID, UNKNOWN persistence, ACKNOWLEDGED classification and invalid transition rejection.

## TASK 001.2 additions
- Tests added for repeatable generated client-order IDs for identical unsigned requests, different IDs for different side parameters, and 64-character credential-free fingerprint output.
- These tests are NOT RUN/NOT VERIFIED until a workflow result on the implementation SHA is retrieved and inspected.

## Still missing
No executed tests yet prove concurrent network submission count, cross-instance claim races, SQLite failure before submission, response persistence failure, corrupt schema recovery, migration with unresolved intents, duplicate client IDs, reconciliation races, partial-fill cancellation race, kill-switch races, OCO races, cancel-replace mixed outcomes, or resumable worker interruption. Do not interpret the existing synchronized methods as proof of liveness or end-to-end idempotency.
