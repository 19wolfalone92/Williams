# TASK 001.6 — Android Single-Order Fill Evidence Gate

**Implementation commit:** `5a43bd7420360203b064ffed1aa47f9fd858bc40`  
**PR:** #66 — OPEN, Draft, unmerged  
**Scope:** Android single-order reconciliation only  
**Status:** IMPLEMENTED — AWAITING EXACT-HEAD CI; P0 OPEN

## Defect found

At the previous PR head `84543e54123027f98fe60e93e8e4196f6cfdcfce`, `StandaloneRuntime.reconcileJournaledSingleOrders()` transitioned a positively queried order to `RECONCILED` without passing `reconciliationEvidenceJson`. The durable ledger correctly rejects that transition, because it requires positive order and fill evidence. Therefore the path could not complete reconciliation and would remain blocked.

## Correction

The implementation commit `5a43bd7420360203b064ffed1aa47f9fd858bc40` now:

1. Queries the order by exact `origClientOrderId` and validates symbol, client ID, exchange order ID, status, and quantities.
2. Queries `/api/v3/myTrades` for that exact symbol/order ID.
3. Fails closed if the trade query is malformed, reaches the 1,000-row limit (potentially truncated), contains a mismatched order ID, has non-positive/invalid quantities, or the sum of trade quantities differs from the order's `executedQty`.
4. Persists explicit `BINANCE_SPOT_TESTNET` / `ORDER_MUTATION` evidence with query completeness, order verification, fill verification, quantities, trade count, and timestamp when transitioning to `RECONCILED`.

No missing order is interpreted as proof of non-acceptance. No order is resubmitted. No live exchange mutation or real credential was used.

## Scope limitations

This is evidence for a single order and its recorded trades only. It does **not** establish OCO parent/child completeness, cancel or cancel-replace outcome, account balances, aggregate exposure, campaign reconstruction, protective coverage, user-stream ordering, or REST-versus-stream convergence. Unsupported mutation types must remain unresolved and keep the execution barrier set.

## Verification

- Source-level change committed; exact-HEAD CI result must be recorded separately.
- No claim that Android unit tests, lint, APK build, or the twelve FI scenarios passed on this commit until fresh workflow evidence exists.
- Standalone `Build Android APK` workflow startup failure remains an external check requiring separate diagnosis.
- Final status remains **PARTIALLY IMPLEMENTED — P0 OPEN**.
