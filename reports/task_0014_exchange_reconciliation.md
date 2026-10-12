# TASK 001.4 — Bounded single-order reconciliation implementation

Status: PARTIALLY IMPLEMENTED — P0 OPEN

## Revision

- Current implementation revision: `4ce14997341c82647ca847e93073c36200db1609`
- PR #66 remains OPEN, Draft, unmerged.
- Base remains `fix/pytest-safe-collection`.

## Implemented

`StandaloneRuntime.recover()` now calls a read-only `reconcileJournaledSingleOrders()` before deciding that unresolved mutation intents require an immediate recovery stop.

The worker is intentionally restricted to durable `POST:/api/v3/order` intents in SUBMITTING, UNKNOWN, or ACKNOWLEDGED state that have a non-empty persisted exchange client order ID. For each candidate it queries Binance using both the symbol and exact `origClientOrderId`, then requires:
- returned symbol matches the journal symbol;
- returned `clientOrderId` exactly matches the persisted exchange client ID;
- a non-empty exchange order ID is present;
- the order status is from the known Binance order-status set;
- original and executed quantities parse and are non-negative, with executed quantity no greater than original quantity.

Only after those checks does it persist the journal transition to RECONCILED with the exchange order ID. Any failed/ambiguous query throws into the existing fail-closed recovery path; absence or failure is not treated as proof that no order exists.

Tests were added for the ledger state transitions UNKNOWN -> RECONCILED and ACKNOWLEDGED -> RECONCILED, including persistence of exchange order ID and removal from the unresolved query after a transition.

## Explicit limits

This is not full Binance reconciliation:
- OCO parent/child orders are not reconciled by this worker.
- DELETE/cancel, cancel-replace, and other operation kinds are deliberately left unresolved.
- The worker does not independently certify campaign exposure, fill/trade history completeness, or protective coverage; existing campaign/position reconciliation and protection audits remain separate requirements.
- The newly added tests exercise ledger transition semantics, not a fake-exchange integration test of the worker.
- Android CI still ends in `startup_failure` with no exposed jobs; no successful Android compilation/test result is claimed.

## Required next work

1. Add an injectable read-only exchange query interface and deterministic fake exchange tests around this worker.
2. Reconcile OCO list identity and every child order; verify fill/execution quantities and protection coverage.
3. Reconcile DELETE and cancel-replace against target and replacement outcomes, preserving ambiguity when either side is incomplete.
4. Execute FI-01–FI-12, concurrent-worker and migration tests, BL-004 secret isolation, and exact-SHA Android/Python regression.
5. Diagnose the Android workflow startup failure and obtain verifiable APK/build evidence.

Do not close P0 or declare TASK 001.4 complete until operation-specific reconciliation and required fault-injection evidence are complete.
