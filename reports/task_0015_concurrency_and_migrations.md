# TASK 001.5 — Concurrency, SQLite Migrations and Recovery

**Executable source SHA:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Status:** PARTIAL.

## Android

- Durable mutation ledger schema is version 4.
- Schema v3 adds persisted reconciliation evidence; legacy `RECONCILED` rows without evidence become unresolved `RECONCILING`.
- Schema v4 adds a partial unique index for new POST-type client IDs per exchange/symbol.
- Tests cover competing claims, conflicting operation reuse, evidence-required closure, a v2-to-v4 migration with a legacy terminal row, duplicate new client IDs, and DELETE references to an existing client ID.
- Campaign CI run `38268195164` passed Android unit tests, lint and debug build.

## Python

The exact-SHA Python suite passed 219 tests with 4 warnings, including existing SQLite reopen, transaction, concurrency and recovery coverage.

## Remaining

- Migration from every supported prior schema version is not demonstrated.
- Interrupted/corrupt migration behavior and all partial-write cases need a dedicated matrix.
- Multi-process concurrency and repeated recovery idempotency across every mutation kind remain unproven.
- A duplicate client ID discovered during migration will cause unique-index creation to fail closed; no automatic repair of ambiguous historical rows is implemented.
