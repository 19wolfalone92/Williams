# WILLIAMS BOT 2.1 — MASTER TASK 009.3
## Exchange-Backed Recovery Coordinator & Campaign Fill Integration

**Status: BLOCKED — implementation and fault injection are in progress; acceptance invariants are not yet fully proven.**  
**Scope:** Python Binance Spot campaign execution only. This document does not claim Android or Futures readiness.

### Baseline and safety boundary

- Requested input HEAD: `a3819c64924de93e7337b653c3848fb3b200ba9c`.
- Required base/main SHA: `04da5f9c09572e99ff310be3a1908179165e528c`.
- Work branch: `audit/williams-2.1-task-009-integration`.
- PR #51 remains Draft/open; not merged. No mainnet/live exchange mutations were performed.
- Latest accepted result must always be tied to the exact HEAD and its CI run. Earlier CI results do not prove the current HEAD.

### New recovery design

1. The database stores an immutable execution intent before the external mutation. A stable `clientOrderId` is protected by a unique index; the operation also records exchange, execution scope, account scope, campaign, order id, and—on cancellation—the target order id/client order id.
2. A durable state in `PENDING`, `SUBMITTING`, `SUBMITTED`, `UNKNOWN`, `AMBIGUOUS`, `RECONCILE_REQUIRED`, or `PROTECTION_PENDING` blocks ordinary mutations in the corresponding execution/account contour.
3. `ExecutionRecoveryCoordinator` reads unresolved intents and a bounded monitor set of recently resolved entry/add-on orders. It queries Binance, validates identity, reads per-trade history, compares executed quantity and quote totals, and never resubmits an order.
4. A missing/ambiguous order response, exchange outage, incomplete history, 1000-trade page limit, unexpected status, or identity/quantity mismatch retains the lock. Absence from a single `GET /api/v3/order` response is not treated as proof of non-submission.
5. The coordinator issues a typed, sealed `VerifiedReconciliationEvidence` object only after its validations. The database resolver no longer accepts a caller-supplied state string plus arbitrary evidence id. This is an in-process architectural contract, not a security boundary against hostile Python code executing in the same interpreter.
6. A recovered position is not considered ready merely because the entry order is reconciled: an initial BUY fill must also have a verifiably live hard stop. A scoped `PROTECTION_PENDING` lease permits only stop-placement or risk-reducing cancellation; ordinary entries remain blocked.

### Exchange APIs and proof limits

- Signed `GET /api/v3/order`: look up by durable order id or client order id and compare symbol, side, target id and returned ids.
- Signed `GET /api/v3/myTrades`: obtain individual Binance trade ids, quantity, price, commission and commission asset. These identifiers form the unique fill key `(exchange, account_scope, symbol, trade_id)`.
- `exchangeInfo` is read before entering a fill transaction to resolve base and quote assets. No exchange network read is intended to occur inside the SQLite financial transaction.
- The recorded `account_scope` is currently an operator-configured logical label (for example, `WILLIAMS_ACCOUNT_SCOPE`), not a Binance-attested immutable account UID. If credentials change while the label stays the same, the recovery service has no independent identity primitive that proves the account is unchanged. Account binding/credential fingerprint verification is therefore a P0 acceptance gap.
- The current implementation blocks if trade history reaches the configured 1000-row limit or does not match `executedQty`/`cummulativeQuoteQty`. Full pagination and retention-proof guarantees remain an acceptance gate. REST snapshots can go stale between calls; conflicting evidence must cause another reconciliation pass or a retained lock.
- SQLite transactions do not make an external Binance POST/DELETE atomic. Each mutation requires a durable intent and subsequent exchange reconciliation.

### Durable state and transaction boundaries

- Schema version: 9 (adds scoped execution identity and persisted cancellation target identity; prior rows without scope become `legacy-unscoped` and block pending explicit reconciliation).
- `BEGIN IMMEDIATE`, per-instance serialized DB access, and SQLite `synchronous=FULL` protect local admission/effect commits.
- `save_execution_result`: exchange response event and `SUBMITTED` state in one transaction.
- `apply_execution_fill`: unique fill registration and DB-only financial callback in one transaction. A duplicate returns without applying another callback. Callback errors roll back the fill row and callback's DB-only effects.
- BUY fill callback updates the campaign position, `campaign_fills`, trade quantity/average entry, fees and campaign-to-trade mapping. It atomically changes the source intent to `PROTECTION_PENDING` before external stop repair.
- SELL fill callback updates remaining position, allocated entry fees, exit fees and realized PnL per actual trade; it does not reuse aggregated `executedQty` as an individual fill identifier. A residual SELL position remains blocked until protection is reconciled.
- Stop creation/replacement, cancel requests and the later exchange verification are separate external operations, never claimed atomic with the fill transaction.

### Status transitions (intended contract)

`PENDING → SUBMITTING → SUBMITTED → OPEN/PARTIALLY_FILLED/FILLED/CANCELED/EXPIRED/REJECTED`

Any ambiguous call or failed persistence after a possible external mutation becomes `UNKNOWN`/`RECONCILE_REQUIRED`. Known fill effects are committed per unique exchange trade ID. A BUY with an uncovered position stays `PROTECTION_PENDING`; after a scoped stop repair and a subsequent successful order/trade reconciliation, the contour may become ready. Any failed or conflicting step remains locked.

### Current known blockers — P0

- Add-on recovery currently fails closed if safe protective-stop resize/cancel-replace cannot be proven; it is not an accepted complete workflow.
- Partial/late SELL fills that leave residual inventory are marked reconciliation-required; full residual-stop resize and restart proof remain incomplete.
- Recovery evidence and fill integration still require exact-head CI and more cancellation-race/restart tests before this can be accepted end-to-end.
- `portfolio_trader.py` still contains separate direct `order_safe` calls (inventory/containment remains separate); `trader.py` has direct `order` and `create_oco_sell` mutation lambdas. They are not proven to share this campaign execution contour. Do not infer their protection from the Python campaign barrier.
- Android signed POST/DELETE is a separate mutation domain and is not coordinated by Python SQLite. Futures is not enabled or certified by Spot tests.
- The backend release gate and Android APK workflow remain blocked by the existing version contract (`server.py: missing "VERSION = '4.24.0'`). Do not change that contract as a side effect of financial-accounting work. No APK is declared ready.

### Fault-injection suite

`test_task0093_exchange_backed_recovery.py` adds 20 offline synthetic-exchange scenarios: single/multiple unresolved intents after restart; unavailable exchange; exchange order with missing local response; missing history; full fill before crash; late/cancelled fills; duplicate delivery; concurrent fill consumers; rollback during fill/effect commit; effect persistence across restart; cancel/fill mismatch; lost submit response; persistence faults for reconciliation; forged evidence rejection; idempotent restart; no resend; no duplicated fee/PnL effect; and entry gating.

A passing unit test only proves its asserted fixture/model. Acceptance requires current-head CI plus production-path campaign tests and restart evidence. Earlier runs found test-fixture callback mismatch (fixed) and a partial-entry recovery failure (the path was revised afterward); neither earlier run proves the current HEAD. See the exact current-head run before changing this status.

### Decision

**Do not merge PR #51.** Task 009.3 stays **BLOCKED** until current-head Python/Campaign CI passes; initial entry, partial/cancel/fill races, stop coverage, trade fees/PnL and restart are proven together; add-on and residual SELL protection paths are either verified or explicitly remain blocked from production release; remaining direct mutation paths are contained; and release/APK status is independently resolved.
