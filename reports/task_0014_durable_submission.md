# WILLIAMS BOT 2.1 — TASK 001.4: Durable Submission / Reconciliation Progress

**Status: PARTIALLY IMPLEMENTED — P0 OPEN**  
**PR:** #66 — `night-shift-audit-20261012` (must remain OPEN, Draft, unmerged)  
**Scope of this report:** durable reconciliation progress marker and restart-safe continuation for the existing single-order POST reconciliation path.

## Changes in this increment

- Added `RECONCILING` to `DurableMutationLedger.State`.
- Included `RECONCILING` in the unresolved query, so a process crash during an exchange read cannot make the execution barrier appear clear.
- Extended legal transitions so SUBMITTING / UNKNOWN / ACKNOWLEDGED can enter RECONCILING, and RECONCILING can only become RECONCILED or UNKNOWN.
- Updated `reconcileJournaledSingleOrders()` to durably transition to RECONCILING before querying Binance Testnet by exact persisted `origClientOrderId`. A subsequent recovery can resume rows already in RECONCILING.
- Added ledger tests for crash persistence while RECONCILING and for the rule that only RECONCILED removes the unresolved barrier.

## Safety boundary

This does **not** establish exactly-once execution. The Android SQLite journal and Binance do not share a transaction. A positive exact-client-ID order response is required for the current single-order path. Query failures remain unresolved. This increment does not yet reconcile OCO lists/children, cancellation, cancel-replace, complete fills/trades, account exposure, or protective-order gaps. It does not yet prove the production worker through a fake-exchange integration test.

## Verification

- Source edits were committed to the existing PR branch, not the base branch.
- Latest observed PR head after this increment: `13b92f49626514d552f5c3696f46d4f67a3b3df9`.
- No workflow runs were returned for that exact HEAD at the time of report generation. Therefore Android compilation/tests and Python regression are **not verified on this HEAD**.
- Prior Android CI was reported as `startup_failure`; this remains an unresolved P0 blocker until diagnosed and rerun on the final SHA.
- Do not infer a green build from source changes or unit-test additions.

## Remaining P0 requirements

1. Inject a read-only exchange-query boundary and test the actual reconciliation worker using a deterministic fake exchange.
2. Add operation-specific reconciliation for cancel, cancel-replace, OCO parent/children, executions/fills, account exposure, and protection gaps.
3. Implement and run FI-01–FI-12 against production mutation/recovery abstractions with exact mutation-count and restart assertions.
4. Prove every Android/Python mutation callsite crosses the required boundary; add automated bypass detection.
5. Complete concurrency and migration tests, BL-004 secret-isolation sentinels, Android startup failure diagnosis, exact-HEAD build/lint/unit/APK metadata/signature/checksum, and Python exact-HEAD regression.
6. Publish the remaining required Task 001.4 evidence reports and perform a final SHA consistency check.

**Conclusion:** durable reconciliation progress is now explicitly journaled, but Task 001.4 remains **PARTIALLY IMPLEMENTED — P0 OPEN**.