# TASK 001.5 — Durable Submission and Crash Safety

**Executable source SHA:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Status:** PARTIALLY IMPLEMENTED.

## Changes

- Signed mutation transport no longer automatically retransmits POST/DELETE on timestamp-error responses. `MutationTransportPolicy` allows one retry only for a read-only GET.
- A mutation's durable intent and claim precede submission.
- Ambiguous outcomes remain unresolved; the transport does not assume that a missing response means non-acceptance.
- Ledger schema v3 requires persisted structured exchange evidence for `RECONCILED`.
- Legacy `RECONCILED` rows without evidence are reopened as `RECONCILING`.
- Schema v4 adds a partial unique index for new POST-type client IDs.
- Android tests cover retry policy, evidence requirements, migration, client ID uniqueness, and persistent audit redaction.

## CI evidence

Campaign CI run `38268195164` succeeded on exact source SHA `cf35f2fb8d2f68770f191efaa272864e7debe04c`: Android unit-test task, lint, and debug APK build passed. Python backend run `38268195155` succeeded (219 passed, 4 warnings).

## Remaining

- No integrated Android FI-01–FI-12 harness covers each wire/persistence boundary.
- No append-only attempt journal records each individual HTTP transmission.
- A typed, durable proof for safe `REJECTED` classification is not complete.
- Database persistence failure after exchange acceptance still requires full fault-injection proof.
- Recovery is not proven idempotent across all mutation types and process restarts.

No exactly-once execution claim is made.
