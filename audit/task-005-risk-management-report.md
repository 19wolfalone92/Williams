# WILLIAMS BOT 2.1 — Task 005: Risk Engine / Portfolio Limits / Position Sizing

**Status:** IN PROGRESS — NOT APPROVED. The TASK 005 branch is isolated; no associated PR exists at the time of this audit. CI remains blocked pending exact-head verification and Testnet recovery evidence.  
**Baseline main:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**PR mapping correction (2026-10-10):** PR #43 is TASK 007 (`fix/task-007-risk-management`, HEAD `c4892b6ab08aafcf75485be2e548eb767cd571df`, base `3fe008d85c06c90ed00d8035484b22aae657317b`). It is not TASK 005 and must not be used as TASK 005 evidence. The TASK 005 branch `audit/williams-2.1-task-005-risk-management` currently has HEAD `4e5652eaf6f83ea7063af21853b67d03d4253765`, base `main` HEAD `04da5f9c09572e99ff310be3a1908179165e528c`, and no associated PR was found in the repository PR listing. The failed run referenced below is exactly this TASK 005 HEAD; no result is transferred from TASK 007 or another SHA.  
**Live trading:** no real exchange order was sent by this task; entry-path tests use `MockExchange`.

## Execution graph and control points

```text
strategy signal / PendingSignal
   -> PortfolioController / PortfolioTrader selector
   -> RiskEngine (finite inputs, structural stop geometry, fees/slippage, exposure cap)
   -> CampaignExecutionService.arm_initial_entry
       -> exchange metadata + current price + free USDT
       -> normalize trigger/stop; LOT_SIZE + MARKET_LOT_SIZE + NOTIONAL checks
       -> BEGIN IMMEDIATE transaction
            -> active-campaign check
            -> aggregate reserved-risk check
            -> durable global entry-reservation claim
            -> persist campaign, pending quantity, capital and risk
            -> persist ENTRY_PENDING state
       -> ExecutionBarrier (sole order mutation barrier)
       -> Binance Spot conditional BUY
       -> durable order acknowledgement / clientOrderId
       -> reconciliation of status, actual fills and exchange balance
       -> protective stop for actual residual quantity
       -> campaign risk reservation retained until reconciled FLAT/CLOSED

Legacy Trader.market_buy -> hard disabled
Android candidate -> risk/input gate -> global ExecutionGate -> durable SharedPreferences
   -> strict execution arithmetic + exchange filters + last-mile free balance
   -> existing single execution barrier -> Spot order -> exchange reconciliation
```

No second execution barrier was introduced. An ambiguous order, corrupt persistence, unknown PnL, invalid market metadata, insufficient free quote, or unknown active exposure must block new entries.

## Canonical contract applied

| Control | Approved limit | Code enforcement / result |
|---|---:|---|
| Initial entry | 0.20–0.25% equity | Campaign initial allocation caps at 0.25%; canonical campaign default is 0.24% (0.0024). |
| Campaign loss budget | 0.60% equity | Engine/controller/executor hard ceilings set to 0.006; unresolved/pending risk is counted. |
| Total portfolio risk | 0.60% equity | New order capacity uses persisted active + pending campaign risk. One campaign limits simultaneous initial exposure further. |
| Daily equity loss | 1.00% | Python and Android breaker defaults/caps set to 0.01; persistent latch and unknown-data block implemented. |
| Active campaigns | 1 | Campaign executor and alternative selectors/preflight reject a second position/reservation. Zero capacity disables entries. |
| Full stop-outs/day | 2 | Python and Android guards block new entries after two negative closed trades for that UTC day. |
| Leverage | 0 | The audited trading routes are Binance Spot routes and make Spot BUY/SELL calls; no leverage setting is used by this entry flow. Confirm environment/endpoint wiring during release preflight. |
| Averaging down | prohibited | Android add-on candidate requires current price above the managed average entry; initial-entry gate allows no second pending/active campaign. |

Environment variables may lower risk limits but are capped by the canonical maxima. Oversized settings can no longer increase these caps in the revised paths.

## Sizing and filter rules

Python sizing includes stop distance, two-side fee reserve and slippage reserve, then applies the capital cap. After rounding, the computed notional and estimated loss must remain within the approved values or the entry is rejected. Conditional trigger price rounds upward (not downward, which could trigger before the approved breakout); protective stop rounds to the exchange price grid. Quantity must satisfy both LOT_SIZE and MARKET_LOT_SIZE grids, min/max quantity, minimum/maximum notional when supplied, plus PRICE_FILTER min/max. Current price and fresh free USDT are checked both before reservation and immediately before submission.

Android was tightened similarly: invalid numeric inputs and missing required filters no longer substitute permissive defaults; rounded quantity/notional/risk are checked; a last-mile free-USDT check occurs before order submission; durable state writes use synchronous commit and failure blocks execution.

## Illustrative calculations

**Example A — Risk Engine.** Equity = 10,000 USDT; permitted initial risk = 0.25% = 25 USDT. Stop distance = 2.00%; estimated round-trip commission reserve = 0.20%; slippage reserve = 0.15%. Effective loss fraction = 2.35%. Risk-sized notional = 25 / 0.0235 = **1,063.83 USDT**, below the independent 25% exposure cap of 2,500 USDT. Actual quantity is rounded down; a rounded quantity whose notional or risk exceeds its limit is rejected.

**Example B — Structural campaign.** Equity = 10,000 USDT; trigger = 101; structural stop = 97. Stop distance fraction = 4 / 101 = 3.9604%. Add 0.20% two-side fee reserve plus 0.15% slippage gives ~4.3104% planned loss fraction. At 0.24% initial allocation, the risk budget is 24 USDT and the approximate pre-rounding notional is 24 / 0.043104 = **556.8 USDT** (subject to the 25% capital ceiling, free balance and symbol filters).

**Example C — Aggregate exposure.** Equity = 10,000 USDT; total risk ceiling = 60 USDT. A campaign that reserves 24 USDT consumes that budget until the exchange proves it flat. A second campaign is blocked by the one-campaign cap, and unresolved/unknown states cannot release the reservation.

These are deterministic arithmetic examples, not profitability forecasts or a claim that the exchange will fill at the trigger price.

## Corrections made on this branch

- Replaced old defaults (0.50% per entry / 1% total / 3% daily / 5 positions / Android 5% drawdown) with the canonical risk ceilings where found.
- Validated finite values, risk overrides and long/short stop geometry in RiskEngine; fail closed on invalid values.
- Added SQLite in-process locking and `BEGIN IMMEDIATE` reservation transactions, active-campaign count and cross-symbol global reservation state.
- Made unresolved campaigns retain risk until closed/flat; include orphan legacy positions when computing aggregate reservation and recover legacy exposure without re-managing campaign-owned symbols.
- Added durable daily baseline, same-day latch and fail-closed PnL/baseline recovery behavior.
- Prevented legacy `Trader.market_buy` from bypassing campaign risk reservation and execution controls.
- Tightened Python and Android filter/rounding/free-balance checks.
- Avoided double fee deductions in one PnL query path; unknown PnL does not mean zero risk.
- Added safety regressions for malformed numeric inputs, stop geometry, missing filters, insufficient quote balance, concurrent two-symbol reservations, unresolved risk retention, daily-limit/restart latch and unknown PnL. Existing recovery tests include restart adoption and partial fills; the partial-fill test now asserts protection quantity matches the materialized remaining position.

## Verification status

The repository CI workflow `Williams Campaign CI` runs Python tests/static compile and Android build/unit tests. Runs are automatically superseded when a new commit is pushed; the current head's run must finish before this task can be accepted. Run `38078333681` on exact HEAD `4e5652eaf6f83ea7063af21853b67d03d4253765` failed: Python 5 failed / 155 passed; Android unit tests 1 failed / 27 completed. The regression fixes in the current revision must be validated on a new exact-head run before acceptance.

**Do not enable live trading based on this report alone.** Acceptance requires a successful run on the exact final SHA and simulated/Testnet cases proving the entry → partial fill → stop protection → exit → restart/reconciliation path. No actual order has been placed by this task.

## Remaining questions / blockers

1. **CI exact-SHA gate:** awaiting current-head Python + Android run. Any failure must be fixed and rerun against the final SHA.
2. **Daily-boundary freshness:** daily rollover intentionally blocks if the phone/process missed a trustworthy near-midnight equity sample. This is fail-closed, but requires a defined operator recovery flow that reconstructs the daily base from reliable exchange/ledger evidence rather than bypassing the breaker.
3. **Legacy PnL history:** historical rows can have differing fee-accounting conventions. The daily equity breaker supplies a marked-equity cross-check, but old PnL records should be migrated/verified before claiming exact daily PnL accounting.
4. **Exchange filter compatibility:** combined LOT_SIZE/MARKET_LOT_SIZE steps that cannot be jointly satisfied are rejected. Real-symbol Binance Spot Testnet metadata must be checked end-to-end on all enabled pairs.
5. **Android lifecycle test coverage:** SharedPreferences persistence and restart logic are updated, but an Android process-kill/relaunch test around UTC rollover, partially-filled market-triggered conditional orders and failed `commit()` must be executed on the final APK.
6. **End-to-end financial source of truth:** confirm every enabled route computes account equity (free + managed assets), commissions (including non-quote commission assets), actual partial fill quantity and residual protection from Binance data. Until that is proven for exact release artifacts, the bot is not accepted for real trading.


## TASK 005.1 — 2026-10-10 CI follow-up

Exact-head checks on `2125caa9646ad6dec59daba5208bdc4985f67ab4` completed with failures:
- Python pytest step: PASS, but the Python backend workflow failed in `ci_release_gate.py` because it still required the obsolete five-position default.
- Android unit-test task completed; combined Android job failed in lint because `AndroidGradlePluginVersion` reported Gradle 8.14.6 available while the wrapper remained 8.14.5.
- APK workflow also failed at the same stale release-contract gate.

Corrective changes now align the release gate and example environment with the actual fail-closed contract: max one active campaign, entry risk <=0.25%, aggregate risk <=0.60%, daily loss <=1.00%. Gradle wrapper and CI setup are moved to 8.14.6 to address the explicit lint diagnostic. These changes require fresh exact-head CI; no prior run is evidence for the new SHA.

Fee-adjusted fill accounting, third-asset fee conversion, idempotent per-fill ledgering, SQLite fault injection, and comprehensive UTC-boundary restart tests are still under audit. Status remains IN PROGRESS — NOT APPROVED.


### Follow-up finding on SHA `952a73bc557f446cdf2272e9e370013dd366b25f`

The Python backend workflow failed in the release gate at `trading_config.py` because a second legacy assertion still expected `min(0.005)`, despite the current hard cap being 0.0025. This is another stale gate assertion, not a runtime test failure. The assertion has been corrected to 0.0025 and an explicit 0.006 aggregate-cap assertion added. New exact-head CI is required.


## TASK 005.1 fee-accounting changes (pending exact-head CI)

Changes staged for this task's working branch:
- Added schema migration for an exchange trade ID and a partial unique index on `(symbol, trade_id)`.
- Added idempotent fill persistence and order-scoped fill reads.
- Campaign entry reconciliation now loads authoritative `myTrades` even when the order response already has cumulative quote quantity, validates gross quantity/quote against the exchange order, converts quote-asset fees directly, base-asset fees at fill price, and third-asset fees using the close of the historical 1-minute candle containing the fill. If third-asset conversion cannot be retrieved, accounting fails closed.
- Net Spot inventory subtracts base-asset commissions before sizing protection. If normalized protective quantity is less than net inventory, the campaign is explicitly tagged with `protection_state=PARTIAL` and `unprotected_exposure_qty`, and transitions to `RECONCILE_REQUIRED`; it is not reported fully protected.
- Partial-entry cancellation now re-reads the order and trade history to capture fills racing with cancellation.
- Mock exchange and regression tests cover quote/base/third-asset fees and repeated fill-history processing.
- Release gate risk ceilings and Gradle lint issue have been corrected; older run results do not prove the newer head.

**Known limitations/blockers:** the fill ledger and entry path are now under validation, but full exit-fill fee accounting, unknown-history emergency protection, crash recovery across each exchange/DB boundary, multi-process daily-baseline initialization, and Futures SHORT behavior still require explicit tests and audit. Do not treat this section as acceptance evidence until exact-head CI and those scenarios are complete.
