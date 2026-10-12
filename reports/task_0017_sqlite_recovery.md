# TASK 001.7 — SQLite Migrations, Concurrency and Recovery

- Code SHA: `e5f82ae60b0b5c1e9512f9efe3bedc1d018ab132`.
- Status: PARTIAL — P0 OPEN.

## Existing implementation / evidence
- Android `DurableMutationLedger.kt` persists operation identity, request fingerprint, client ID, state, timestamps, exchange order ID and reconciliation evidence; transitions are transaction-wrapped.
- Schema v4 adds a partial unique index for POST client IDs and reopens legacy RECONCILED rows lacking evidence as unresolved RECONCILING.
- Existing Android tests cover competing claims, conflicting operation reuse and selected migration/evidence transitions.
- Python `db.py` stores execution-intent leases/recovery fields; Python recovery/SQLite tests are part of Campaign CI.

## Not tested or not proven
- Every supported schema migration version, interrupted migration and transaction rollback at each DDL/DML boundary.
- Corrupt/legacy rows, duplicate client IDs causing unique-index migration failure, and all historical falsely reconciled records.
- Concurrent reconciliation workers across processes; restarts during each of SUBMITTING, UNKNOWN and RECONCILING; idempotent recovery replay across every operation kind.
- A single atomic transaction across Android and Python stores does not exist. The exchange remains authoritative; cross-runtime recovery requires reconciliation, not assumptions about database atomicity.
- No migration/concurrency closure is claimed. Exact-SHA Android results remain blocked by the standalone APK workflow startup failure; the Campaign Android job outcome must be checked separately.
