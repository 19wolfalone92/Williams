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


### TASK 005.1 follow-up on SHA `0668c4289c1c217aa881dc1c1786d60292f304c9`

Campaign CI on that exact SHA reported 1 failing test (`test_third_asset_commission_uses_fill_minute_conversion`); the other 162 tests passed. The failure was fail-closed behavior: historical BNB/USDT conversion could not be retrieved through the mock's generic klines path, so the code did not write a trade with zero fees. The mock and adapter now expose a dedicated historical-price contract, and an additional regression test verifies that missing authoritative trade history keeps risk reserved, attempts a protective stop using exchange-available base inventory, and leaves the campaign in `RECONCILE_REQUIRED`.

The DB now also rejects conflicting replays of an existing `(symbol, trade_id)` rather than silently ignoring them. These changes require fresh exact-head CI. Previous results remain tied to their own SHA.


## TASK 005.1 — Execution intent durability

Audit found that `ExecutionBarrier._persist` swallowed database exceptions and did not read back the durable intent before submitting. That violated the required no-order-on-persistence-failure contract. The barrier now requires a database intent store, writes the intent, reads back its status/reason/client order ID, and raises `ExecutionPersistenceError` on any write/readback mismatch. Added fault-injection tests prove the adapter submit callback is not invoked when intent persistence or readback fails. These tests are pending exact-head CI.


## TASK 005.1 — Exit fills and partial-exit handling

Exit execution now reads and persists authoritative per-trade SELL fills, values fees in quote/base/third assets using the same fill-accounting ledger, and includes accumulated exit proceeds and fees in final campaign PnL. It sells no more than the campaign's accounted quantity and current free base inventory. A partial exit no longer treats an arbitrary percentage/tiny-quantity threshold as closed: it updates the residual position, attempts to restore a stop for the residual, preserves risk reservation, and enters `RECONCILE_REQUIRED`. Missing trade history or persistence causes fail-closed reconciliation and a best-effort residual protection attempt.

Regression tests cover quote-fee accounting on a full exit and a partial exit with residual protection. These changes are pending exact-head CI. This still does not by itself prove every process-crash point around exit order submission/recovery; those remain acceptance gates.



## TASK 005.9 — Mutation-sink enforcement checkpoint (2026-10-11)

**Status: BLOCKED — NOT ACCEPTED.** This checkpoint records production changes made after TASK 005.8; it does not claim that the full TASK 005.9 contract is complete.

### Git state at this checkpoint

- Repository: `19wolfalone92/Williams`
- Branch: `audit/williams-2.1-task-005-risk-management`
- PR #50: Open, Draft, not merged.
- Base SHA: `04da5f9c09572e99ff310be3a1908179165e528c`
- Implementation checkpoint before CI-trigger adjustment: `99e9367ca68405ab2173449f29a49dddae820bc1`
- Subsequent CI path-trigger adjustment: `21dc92bcea51b258e798b7177b5e3b15e8d4183f`
- No Mainnet/Testnet trading mutation was executed.

### Production changes

1. `db.py`
   - Added `mutation_started_at` to `emergency_sell_intents`, including a safe additive migration for existing databases.
   - Added `authorize_emergency_sell_mutation()`, which verifies the durable intent's saved client ID, symbol, account/environment fingerprint, quantity and `SUBMITTING` state, then atomically consumes a one-shot permit under SQLite `BEGIN IMMEDIATE`.
   - Bumped the schema marker to 8 and changed SQLite synchronous mode from NORMAL to FULL to strengthen commit durability.
2. `binance_client.py`
   - Added an enforcement check at the REST request boundary for new emergency market SELL requests using the `WILLV4_EMERGENCY_` client-ID namespace.
   - Direct use of the REST-backed `order()` path is denied if the authorizer is missing, the durable permit cannot be proven, or the permit was already consumed.
   - Unsupported emergency-ID POST/DELETE mutation shapes fail closed.
3. `portfolio_trader.py`
   - Binds the configured durable DB authorizer to the Binance client during `MultiPositionTrader` initialization.
4. `test_task005_risk_safety.py`
   - Added offline mutation-sink tests for missing authorization, exact-quantity/account matching, one-shot consumption, and eight independent processes contending for the same permit.
5. `.github/workflows/android-apk.yml`
   - Added root Python/test paths to the build workflow filters so backend execution changes trigger an Android APK build as well.

### Evidence classification

- **CODE-CONFIRMED:** one-shot durable permit and emergency-ID sink guard are present in the branch source.
- **TEST-PROVEN:** Python backend run `38121886860` on SHA `99e9367ca68405ab2173449f29a49dddae820bc1` reported `151 passed, 4 warnings`. This is an intermediate SHA only, not acceptance proof for the latest commit.
- **CI-PROVEN:** not yet established for the latest SHA. The runs initiated for `21dc92bcea51b258e798b7177b5e3b15e8d4183f` were still pending/in progress at this checkpoint; they must not be treated as PASS.
- **TESTNET-OBSERVED:** not performed; no trading mutations were used.

### Remaining P0 blockers

- The sink guard currently enforces the emergency client-ID namespace; the complete inventory and proof that every emergency-equivalent or alternate order path cannot bypass it is still outstanding.
- A production recovery worker is not yet proven to reconcile every persisted intent against authoritative Binance order history and fills after UNKNOWN POST.
- Fill ledger, fee resolution, campaign/position projections, and intent completion do not yet have a demonstrated single atomic or durable exactly-once protocol across every crash point.
- Late-fee corrections, unresolved-fee handling, exact Decimal flow and SQLite REAL/float use remain incompletely audited across the full emergency exit path.
- Remaining Spot exposure is not yet proven by a complete reconciliation of free/locked balances, open orders, fills and commissions.
- Global Recovery Gate enforcement across every BUY/add-on/worker/Android path remains unproven.
- Android execution-contract parity or fail-closed blocking has not been demonstrated by process-death and recovery tests.
- Full adversarial test matrix ES-01 through ES-20 has not been proven on one final SHA.

### Next required step

Wait for all required workflows on the final source SHA, inspect their exact checkout SHA and logs, fix any failures without weakening assertions, and continue the missing recovery/financial-ledger/Decimal/Global Gate work. PR #50 remains Draft. No acceptance is implied by this checkpoint.


## TASK 005.10 — Emergency SELL authoritative recovery and sink audit (2026-10-11)

**Status: BLOCKED — NOT ACCEPTED.** This is a source audit and CI checkpoint, not an assertion that the missing recovery/financial-owner contract has been implemented.

### Git/CI baseline revalidated before work

- Branch HEAD: `6e97611465e483430adf9093f25d44ef3f37af55`.
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c`; unchanged from the task base.
- PR #50: Open, Draft, not merged; base remains `main`; head matches the branch SHA above.
- No local working-tree metadata is exposed by the GitHub connector; remote branch/PR refs were checked directly.
- No real Binance Mainnet or Testnet trading mutation was performed.

### Exact-SHA CI results for baseline `6e97611465e483430adf9093f25d44ef3f37af55`

| Workflow | Run | Final status | Evidence |
|---|---:|---|---|
| Python backend tests | 38121986509 | SUCCESS | Exact `head_sha`; backend-test job succeeded. |
| Build Android APK | 38121986490 | SUCCESS | Exact `head_sha`; Build APKs job succeeded. |
| Williams Campaign CI | 38121982624 | SUCCESS | Exact `head_sha`; Android build/static checks and Python tests succeeded. |
| Additional Williams Campaign CI | 38121986442 | FAILURE | Exact `head_sha`; Python job failed, Android job succeeded. |

Failure log: [run 38121986442](https://github.com/19wolfalone92/Williams/actions/runs/38121986442), job `114418965385`. Result: `1 failed, 196 passed, 4 warnings`; failing test `test_task005_risk_safety.py::test_two_symbols_cannot_reserve_same_portfolio_capacity_concurrently`. The exception is `sqlite3.OperationalError: cannot start a transaction within a transaction` from `db.py:36` when `campaign_execution.py:396` enters `Database.transaction()`. This is a real concurrency/transaction-ownership defect, not an acceptable flaky-test waiver. It must be corrected and re-tested on a new exact SHA. The three successful runs do not cancel this failure.

### Source-to-sink audit — initial inventory

The repository tree at the baseline SHA contains no `execution_recovery.py`. That absence is material: there is no dedicated production worker file with which to prove durable UNKNOWN POST recovery across restart, read-only retries/backoff, multi-process ownership, and authoritative order/fill reconciliation.

| Mutation type / path | Caller → network sink | Durable intent / authorization | UNKNOWN POST / financial handoff | Status |
|---|---|---|---|---|
| Emergency MARKET SELL | `portfolio_trader.py::_emergency_market_sell()` → `client.order_safe(... SELL, MARKET ...)` → `binance_client.py::order()` → `_request(POST, /api/v3/order)` | `claim_emergency_sell_intent()` and transition to `SUBMITTING` occur before submission; REST boundary checks the `WILLV4_EMERGENCY_` namespace and consumes a one-shot DB permit. | Caller marks ambiguity `RECONCILE_REQUIRED` and refuses resubmit, but no complete restart worker is present. The caller directly updates local trade quantity/PnL and then transitions intent to `APPLIED`; a single canonical financial effect owner and durable per-fill exactly-once handoff are not demonstrated. | CODE-CONFIRMED for this path; end-to-end TEST-PROVEN: NOT VERIFIED |
| Ordinary Python order creation (BUY/SELL/STOP/OCO-related) | `portfolio_trader.py`, `campaign_execution.py`, other callers → `BinanceSpotClient.order()/order_safe()` → REST `_request` | The emergency-specific namespace guard does not apply to general client IDs. Campaign paths use `ExecutionBarrier` in selected routes, but the whole repository sink/caller graph has not been proven to converge on one universal barrier. | Generic POST transport failures are marked unknown and not blindly retried, but durable intent/recovery coverage varies by caller and mutation type. | NOT VERIFIED globally |
| Cancel / cancel-replace | REST mutation paths in `binance_client.py::_request` including order/cancelReplace handling | Emergency namespace rejects unsupported emergency-ID POST/DELETE shapes, but this is not universal authorization for every ordinary cancellation or replacement. | HTTP 409 is classified ambiguous; source inventory does not prove every caller persists/reconciles both sides of a partial cancel-replace. | CODE-CONFIRMED for 409 handling; full sink proof NOT VERIFIED |
| OCO / order-list create/cancel | `order_list` and `open_order_lists/all_order_lists` are read methods; order-list mutation paths and callers require a complete separate inventory. | No universal emergency-intent authorization established. | No complete durable reconciliation proof established for all order-list outcomes. | NOT VERIFIED |
| Direct REST / alternate Python callers | `BinanceSpotClient._request()` is the low-level network boundary; repository-wide callers include order wrappers and additional execution modules. | Current special guard is selected by client-ID prefix, not by an operation capability attached to every SELL/cancel/replace sink. An ordinary SELL without that prefix is not forced through the emergency permit. | Cannot prove bypass exclusion across all callers from the current guard. | NOT VERIFIED; universal Execution Barrier NOT PROVEN |
| Android execution | `StandaloneRuntime.kt` and `ExecutionGate.kt`; local Android execution uses its own execution gate and signed REST helpers in the runtime. | `ExecutionGate` shown in source is an in-memory synchronized symbol reservation, not a durable emergency-intent/recovery lock. No cross-platform atomicity is implied. | Android process-death UNKNOWN POST recovery and exactly-once financial effect handoff were not demonstrated. | CODE-CONFIRMED for in-memory gate; DEVICE-PROVEN: NOT VERIFIED |

This is an initial source audit, not a claim that every individual reachable sink is enumerated and tested. The required complete source-to-sink graph remains an acceptance blocker. In particular, the absence of a dedicated recovery worker and the general order API remaining usable outside the emergency client-ID namespace mean universal mutation-sink enforcement is not proven.

### Emergency intent and identity findings

The existing `emergency_sell_intents` table uses `operation_id` as its primary key and stores environment, account scope, symbol, trade ID, operation type, reason, requested quantity, parameter hash, client order ID, state, exchange order ID/status, timestamps, and `mutation_started_at`. Schema marker is 8; an additive migration adds `mutation_started_at`. `claim_emergency_sell_intent()` verifies immutable identity fields and rejects a conflicting client ID. `authorize_emergency_sell_mutation()` checks symbol, quantity, environment/account scope, client ID, `SUBMITTING` state and one-shot permit consumption.

Current state contract in `db.py` is `PREPARED → SUBMITTING → SUBMITTED/RECONCILE_REQUIRED → APPLIED`, with `RECONCILE_REQUIRED` permitted to remain unresolved or move to `SUBMITTED/APPLIED`. This is narrower than a fully reconciled exchange lifecycle: it does not by itself persist a complete reconciliation cursor, every fill identity, commission-resolution state, or a recovery lease/owner. The one-shot permit proves only authorization to attempt a mutation; it does not prove order acceptance, fills, financial application, or terminal exposure reconciliation.

The caller uses a stable client order ID derived from the operation identity. Exchange order ID and fill identity are not shown to have a complete durable, conflict-checked association to the operation across every crash point. First lookup miss, temporary Binance unavailability, contradictory order/fill sources, and recovery lease contention are not covered by a proven production worker. Required rule remains: unknown result → durable reconciliation-required gate; never infer rejection from a lookup miss and never issue a replacement SELL while unresolved.

### Financial handoff and residual exposure — blockers

The current emergency caller invokes `_authoritative_execution()`, but then uses floating-point conversions for executed quantity, average price, commissions, managed quantity and PnL. It directly updates the local trade projection before setting the intent to `APPLIED`. The source does not establish that all those writes are one atomic effect transaction keyed by durable fill identity and owned exclusively by the canonical Financial Effect Applier. A crash between projection update and intent finalization can therefore leave recovery unable to distinguish “effect already applied” from “effect not applied” unless a separate durable idempotency contract exists; that contract is NOT VERIFIED here.

The existing campaign fill ledger is not sufficient proof of emergency exit accounting without tracing the exact emergency call path into the same ledger and proving uniqueness/conflict handling, late fee correction, UNKNOWN/INVALID/CONFLICTED fee states, and atomic effect application. No second ledger is introduced by this audit.

Residual Spot quantity is not proven to be reconstructed from a consistent snapshot of free/locked base balance, open SELL orders, fills, base-asset commissions and pending mutations before a subsequent SELL. The local managed trade quantity is not equivalent to currently available Spot balance. Decimal exactness is also not proven because the audited emergency path converts authoritative values to `float`.

### Global Recovery Gate / Android

Python has local `RECONCILE_REQUIRED` state checks in selected entry paths, but the audit has not established a single durable gate consulted by every BUY, add-on, retry, background worker, alternate client, cancel-replace and OCO mutation sink. The REST emergency-prefix check is not a global gate.

Android's `ExecutionGate` is an in-memory one-campaign reservation; it does not prove durable recovery of an emergency order after process death. Android uses a separate local store and no shared atomic transaction with Python/SQLite. No emulator/device process-kill recovery test was run. Classification: `EMULATOR-PROVEN — NOT VERIFIED`; `DEVICE-PROVEN — NOT VERIFIED`; `TESTNET-OBSERVED — NOT PERFORMED`.

### Required next minimal safe steps

1. Fix the real SQLite transaction ownership defect; add a regression proving concurrent callers cannot enter a transaction while the connection already has an unowned active transaction. Do not suppress the exception or weaken the concurrency assertion.
2. Build a repository-wide source-to-sink inventory for every Python and Android network mutation, including ordinary SELL, cancel, replace and order-list operations; ensure each sink requires a durable mutation capability or blocks the operation.
3. Implement one durable emergency-intent recovery worker using authoritative read-only Binance lookups and bounded backoff, with an atomic recovery claim/lease and no re-POST while any prior result remains unknown.
4. Integrate recovered fills with the existing canonical Financial Effect Applier only after verifying its contract; if unavailable, persist fills as pending and keep the global gate closed.
5. Replace float-based emergency accounting with exact Decimal/string persistence; reconcile available Spot quantity from authoritative balances, open orders, fills and commissions before permitting another SELL.
6. Add SQLite/process fault-injection for the specified ES scenarios, then rerun all required CI after the last code/report change and attach only exact-final-SHA evidence.

### TASK 005.10 acceptance matrix

- Durable UNKNOWN POST recovery worker: **NOT VERIFIED / P0 BLOCKER**.
- Complete SELL/cancel/replace/OCO mutation-sink enforcement: **NOT VERIFIED / P0 BLOCKER**.
- Exactly-once financial effect handoff: **NOT VERIFIED / P0 BLOCKER**.
- Decimal-exact emergency financial path: **NOT VERIFIED / P0 BLOCKER**.
- Residual Spot exposure reconciliation: **NOT VERIFIED / P0 BLOCKER**.
- Global Recovery Gate across Python and Android: **NOT VERIFIED / P0 BLOCKER**.
- Full SQLite/process fault-injection matrix on one SHA: **NOT VERIFIED / P0 BLOCKER**.
- Exact-SHA CI at baseline: 3 SUCCESS, 1 FAILURE; therefore CI acceptance gate **FAIL**.
- Main unchanged; PR #50 remains Open/Draft/unmerged; no real trading mutation performed.

**TASK 005.10 — BLOCKED — NOT ACCEPTED.**
