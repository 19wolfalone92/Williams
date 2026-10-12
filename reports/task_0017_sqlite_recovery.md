# TASK 001.8 — SQLite Migration, Concurrency and Recovery

- Audited code/test SHA: `31ba73ff81c43edb5be4606b200dce752148967a`.
- Status: PARTIAL — P0 OPEN.

## IMPLEMENTED (source inspection)

- Android ledger persists operation identity, request fingerprint, client ID, state, timestamps, exchange order ID and reconciliation evidence.
- Ledger transitions are transaction-wrapped; schema v4 adds a partial unique index for POST client IDs.
- Migration reopens legacy RECONCILED rows without evidence as unresolved RECONCILING.
- Python `db.py` stores execution-intent lease/recovery fields; Python recovery and SQLite lifecycle tests are in Campaign CI.
- Android SQLite and Python SQLite are separate transactions/databases; no cross-runtime atomicity is assumed.

## TESTED / VERIFIED at audited SHA

- Campaign Python test job run [38301629067](https://github.com/19wolfalone92/Williams/actions/runs/38301629067): SUCCESS.
- Android ledger unit tests are not marked verified until the Campaign Android job finishes on the exact SHA; it was still running at last inspection.
- No end-to-end cross-runtime atomicity test is applicable; correctness must rely on exchange reconciliation and fail-closed gates.

## NOT YET TESTED / BLOCKED

- Every supported old Android schema version and every Python DB migration path.
- Interrupted DDL/DML migrations, rollback at each migration boundary, repeated migration after partial completion.
- Duplicate legacy client IDs that may cause unique-index creation to fail.
- Corrupt/legacy rows and historical RECONCILED rows with incomplete or invalid evidence.
- Multi-process concurrent claims and concurrent reconciliation workers.
- Crash/restart at each SUBMITTING, UNKNOWN and RECONCILING transition for all operation types.
- Atomicity/rollback and duplicate mutation assertions across the production transport boundary.

Do not claim migration/concurrency closure based on code inspection or selected unit tests. P0 remains open.