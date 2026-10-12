# TASK 001.5 — Exchange Reconciliation

- Audited executable-source SHA: `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf`
- Status: PARTIALLY IMPLEMENTED — P0 remains open.

## Existing capabilities inspected

Python `ExecutionRecoveryWorker` uses read-only adapter calls, validates order identifiers/status/quantities, verifies nonzero execution quantity against trade history, normalizes OCO parent/child records, and leaves missing, malformed, mismatched, or unavailable lookups unresolved with backoff. The current Python tests use a deterministic fake exchange.

Android has REST/user-stream recovery code and a durable mutation ledger, but the ledger is not yet integrated with a complete reconciliation worker that persists comprehensive evidence for every mutation type.

## Implemented in this task

- Schema v3 adds `reconciliation_evidence_json`.
- Transition to `RECONCILED` requires non-empty structured evidence with `source`, `query_complete=true`, `order_verified=true`, `fills_verified=true`, and a positive `checked_at_ms`.
- Evidence is stored in the same SQLite row as the transition.
- Legacy `RECONCILED` rows without evidence are moved to unresolved `RECONCILING` during migration.
- A single-order evidence record is not defined as campaign/protection certification.

## Still missing

- Unified Android read-only exchange-query abstraction and durable recovery worker.
- End-to-end reconciliation for OCO parent and both child legs, cancel-replace partial outcomes, fills/event deduplication, account balances/managed exposure, campaign state, protection coverage, and REST-versus-user-stream discrepancies.
- A tested protocol for when a not-found result can establish non-acceptance.
- Evidence completeness/ownership checks across all production callers.

No claim is made that full reconciliation is implemented or verified.
