# WILLIAMS BOT 2.1 — TASK 005.2 P0 Closure Audit

**Status: IN PROGRESS — NOT APPROVED**  
**Branch:** `audit/williams-2.1-task-005-risk-management`  
**PR:** #50 (open draft; do not merge)  
**Baseline main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Audit snapshot before this report/test commit:** `6f10a2056d2b8f9c854ed8022dafbf675bc5cc52`

## Fixed and checked in code (execution evidence still pending)

- `db.py`: fill ledger requires an exchange trade ID, validates fill/fee fields, uses a database unique index on `(symbol, trade_id)`, rejects conflicting replays instead of silently overwriting, and blocks migration if historical duplicate keys are detected rather than deleting data.
- `campaign_execution.py`: campaign fill accounting reads Binance `myTrades`, requires exchange `orderId`/trade IDs and explicit commission amount/asset, persists fills transactionally, reconciles gross fill quantity and quote notional to the order, and derives Spot net inventory with base-asset commission treated separately.
- `portfolio_trader.py` (TASK 005.2): an executed order without authoritative fill rows, failed/empty `myTrades`, or missing/invalid commission amount/asset now fails closed. It logs lookup failure and requires reconciliation instead of using order-level executed quantity as proof of zero fees.
- `test_task005_risk_safety.py`: regression tests cover failed trade-history lookup and missing commission metadata. The database ledger tests cover idempotent replay and conflicting replay.

## Implemented but not yet proven by final-SHA CI

- SQLite uniqueness and independent-process reservation tests exist, but the full fault-injection matrix for reserve/intent/commit/readback failures and all opening paths is not complete.
- Third-asset commission valuation still requires a full source/time audit. Current conversion helpers include ticker-based conversion; a current ticker is not sufficient proof of historical realized-fee valuation.
- Partial-fill protection reconciliation has implementation paths, but the requested race matrix for partial fill vs cancel/replace and duplicate protection is not complete.
- Daily equity breaker has Python/Android persistence logic and baseline checks, but full outage, corrupt-state, concurrent initialization, and PnL/fee exactly-once matrices are not complete.
- Binance filter validation has checks and selected tests, but the full LOT_SIZE/MARKET_LOT_SIZE/PRICE_FILTER/NOTIONAL matrix is not complete.

## Not implemented / UNKNOWN

- Full transactionally coupled exchange fill ledger + campaign quantity + fee ledger application across every recovery path: **UNKNOWN / not accepted**.
- Full open-order path inventory proving every Python/background/recovery/Android entry passes the same durable-reservation and ExecutionBarrier contract: **UNKNOWN**.
- Futures Demo/Testnet LONG and SHORT lifecycle: **NOT VERIFIED**. Spot SELL is only a Spot LONG reduction/close.
- Full fee-adjusted realized PnL exactly-once evidence: **UNKNOWN**.

## CI evidence

CI must be rerun and assessed against the final SHA after this commit. Prior successful runs on another SHA do not satisfy acceptance. At report creation, final-SHA CI is **NOT RUN**. Do not infer a pass from queued/in-progress runs.

## Safety constraints

No Binance Mainnet, no real order submission, no risk-limit increases. Canonical caps remain per-entry <= 0.25%, aggregate <= 0.60%, daily loss <= 1.00%, one active campaign, no averaging down.

## Acceptance blockers

1. Final-SHA Python/Campaign/architecture/fee-idempotency/recovery/filter/Android unit/lint/debug APK evidence.
2. Historical third-asset commission valuation must be source/time-qualified or remain UNKNOWN and block dependent PnL/risk decisions.
3. Complete reserve/intent/commit/readback fault-injection proof and all-entry-path inventory.
4. Complete partial-fill protective-order race and quantity/filter matrix.
5. Complete daily baseline/commission/PnL exactly-once and UTC rollover matrix.
6. Verified LONG/SHORT separation; Futures SHORT is not accepted without dedicated Demo/Testnet evidence.
