# TASK 001.5 — Exchange Reconciliation

**Executable source SHA:** `cf35f2fb8d2f68770f191efaa272864e7debe04c`  
**Status:** PARTIALLY IMPLEMENTED — P0 OPEN.

## Existing capabilities inspected

Python `ExecutionRecoveryWorker` performs read-only order/trade queries, checks order identifiers/status/quantities, validates nonzero execution quantities against trade history, normalizes OCO parent/child records, and leaves missing/malformed/mismatched/unavailable responses unresolved with backoff. Python tests use a deterministic fake exchange.

Android recovery and user-stream code exists, but it is not integrated with a complete durable reconciliation worker covering all mutation types and account/campaign/protection state.

## Implemented

- SQLite schema v3 adds persisted `reconciliation_evidence_json`.
- A transition to `RECONCILED` requires `source=BINANCE_SPOT_TESTNET`, `scope=ORDER_MUTATION`, `query_complete=true`, `order_verified=true`, `fills_verified=true`, and positive `checked_at_ms`.
- Evidence is stored with the ledger row.
- Schema migration reopens legacy `RECONCILED` rows without evidence as unresolved `RECONCILING`.
- Schema v4 adds uniqueness protection for new client IDs.

## Still missing

- A complete Android read-only exchange-query abstraction and recovery worker.
- End-to-end OCO parent/child, cancel-replace, partial-fill, duplicate/out-of-order event, balance/exposure, campaign/position, protection-gap, and REST-versus-user-stream reconciliation.
- A documented and tested protocol under which a not-found order result can establish non-acceptance.
- Typed evidence that proves the query result rather than accepting caller-supplied boolean fields.
- Durable recovery progress across all records and safe partial-resume tests.

A single-order ledger transition is not treated as campaign or protection certification.
