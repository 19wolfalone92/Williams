# WILLIAMS BOT 2.1 — TASK 001.14
## Cross-Platform Financial Writer Closure, Authoritative State Ownership & Transaction-Boundary Proof

**Result: BLOCKED — WRITER INVENTORY / OWNERSHIP / RECOVERY EVIDENCE INCOMPLETE**  
**Mode:** READ-ONLY source audit; report-only change.  
**Repository:** `19wolfalone92/Williams`  
**Branch:** `audit/williams-2.1-task-001-architecture`  
**Audit revision:** `22cea3f683c0731f8dc31958bd5d383bba3182a2`  
**Underlying code anchor:** `d064c814c2fdaa52470cfb7d18a0270cb9b4b910`  
**Prior report commit:** `22cea3f683c0731f8dc31958bd5d383bba3182a2`  
**main/base:** `04da5f9c09572e99ff310be3a1908179165e528c`

This report extends TASK 001.13. It is not a declaration of financial safety. A recursive GitHub tree and exact-ref source reads are available, but no local executable checkout, source-wide compiler-backed call graph, or fault-injection run was available in this task. Static scans were run over the complete fetched contents of the eleven named core files listed below; this is broader than a fragment-only read, but it is not proof that every dynamic/configuration-dependent path in the repository has been closed.

## 1. Source provenance and change control

| Item | Evidence | Status |
|---|---|---|
| PR #47 | Open, draft, not merged; base `main`; head was `22cea3f683c0731f8dc31958bd5d383bba3182a2` at task start | VERIFIED via PR metadata |
| Base/main SHA | `04da5f9c09572e99ff310be3a1908179165e528c` | VERIFIED |
| Previous audit report commit | `22cea3f683c0731f8dc31958bd5d383bba3182a2` | VERIFIED |
| Code-source anchor | `d064c814c2fdaa52470cfb7d18a0270cb9b4b910` | VERIFIED |
| Local `git rev-parse`, `git status`, runtime/toolchain | Not available in this environment | NOT VERIFIED |
| Exact-ref fetched source files | 11 core files; blob hashes recorded below | VERIFIED |
| Recursive tree | Prior task recorded 220 blobs and `truncated=false` at the source anchor | Metadata VERIFIED; not a local checkout |
| Current CI workflow runs | Python backend tests, Campaign CI, Android APK workflow reported completed/success at the report head | CI metadata VERIFIED; does not prove financial atomicity |
| Task-specific fault-injection tests | None run | NOT RUN |
| Production code/schema/strategy changes | None | VERIFIED |
| Binance mutations / real keys | None | VERIFIED |

Fetched file blob SHAs at the audited ref:

| File | Blob SHA |
|---|---|
| `db.py` | `129645290a074d7958ac75a4128de455754db13e` |
| `campaign_execution.py` | `699b28b1de25f90465336060ab6162537ef82668` |
| `campaign_engine.py` | `2183825fcab8f46e47a7ea8b2833c259a367229e` |
| `portfolio_trader.py` | `5c38ed933edf17e0a9d3376363c50786a94a737d` |
| `trader.py` | `b2d9287a10da7d803c8ce14912c24745453033d7` |
| `execution_barrier.py` | `c0e7cf4eea424d037b273e6150fa2d3d0ea93b40` |
| `StandaloneRuntime.kt` | `d462682601c86fe5145646d16d417435cce032d4` |
| `TradeJournal.kt` | `2822b22b4ed7e06b8384fabf343a7dd2f8059057` |
| `TradingAuditStore.kt` | `5ba02be31c5dbc31657c46ec579d7906da59d3dd` |
| `ExecutionAccumulator.kt` | `3dbc8ef7af27f45afe94ce847f2c3686c00e3290` |
| `ExecutionGate.kt` | `aec1c01d12fe460a62a48c1fa81af527cce4afd5` |

The exact-ref file fetches returned full contents for these files (including `StandaloneRuntime.kt`, 8,194 lines and `db.py`, 1,433 lines). A static pattern scan enumerated SQL and financial-writer-shaped calls in those fetched files. It did not cover every other file, dynamic dispatch, reflection, build flavor, runtime wiring, or every deployment configuration; therefore complete writer closure remains unresolved.

## 2. Executive result

**The acceptance criteria are not met.** Concrete Python and Android financial-state writers are confirmed, but their complete runtime reachability, ownership and coordination are not proven. Python fill-observation deduplication exists; exactly-once application of the resulting effect to every position/trade/Campaign/fee/PnL/risk projection is not proven. Android has its own position/campaign lifecycle and persistence stores outside Python's SQLite transaction.

The CI metadata at this revision reports three successful workflow runs. This is useful build/test evidence only; it is not a substitute for tests that race a fill, interrupt transactions, simulate an unknown COMMIT, or prove cross-platform financial ownership.

No `FinancialEffectApplier` should be created or connected to the execution path under this result.

## 3. Python writer inventory

The source-wide pattern scan of the fetched Python core files found direct financial persistence calls in the following groups. “Confirmed” means the writer or call is visible in the fetched source; it does not imply every caller or deployed route has been identified.

| ID | File / function or sink | Trigger and state affected | Transaction / idempotency evidence | Consumers / risk | Status |
|---|---|---|---|---|---|
| PY-01 | `db.py:record_exchange_fill`, `_record_exchange_fill_transactional` (1126–1324) | Fill receipt from REST/recovery; writes `exchange_fill_ledger`, `financial_effect_journal`, reconciliation latches | Local transaction for observation/journal state; unique identity observed as `(symbol, exchange_trade_id)`; not a transaction spanning all downstream projections | Used by entry/exit fill readers and recovery; journal state gates risk increase | VERIFIED observation writer; financial application PARTIAL |
| PY-02 | `campaign_execution.py:_executed_fee_quote` (around 427; fill ingestion around 463) | Reads order-scoped Binance trade history and records each trade | Calls PY-01; fee summary returned as float; does not prove application to all projections | Campaign entry/exit processing | VERIFIED call path; exact application PARTIAL |
| PY-03 | `campaign_execution.py:reconcile_pending_entries` (511 onward) | Pending entry/order recovery; initial fill and add-on fill handling | Multiple persistence calls; direct SQL updates `trades` entry/stop fields around 667–682; separate `save_trade` and `save_campaign` calls | Changes local trade quantity/entry price/stop and Campaign; can affect later risk/exit decisions | VERIFIED multi-step writer |
| PY-04 | `campaign_engine.py:record_initial_fill`, `record_add_on_fill` and related state transitions | Mutates Campaign object and persists via `save_campaign` | Campaign write separate from fill receipt/trade writes in observed call paths | Campaign quantity, average entry, risk/reservation state | VERIFIED Campaign projection writer |
| PY-05 | `portfolio_trader.py:_exit_fee_quote` (around 630–684) | Exit fill history / fee enrichment | Calls PY-01; quote fee summary uses float-compatible return | Exit PnL and close/remainder logic | VERIFIED call path; projection atomicity UNRESOLVED |
| PY-06 | `portfolio_trader.py` fill ingestion around 924; `_reconcile_trade` (1399 onward) | REST reconciliation, balance/order/trade-history recovery | Writes fill observation and later uses `update_trade_quantity` / `close_trade` in distinct paths | Local position/trade and close decisions | VERIFIED competing writer path |
| PY-07 | `portfolio_trader.py:recover_pending_entries` (around 1282; `save_trade` around 1333) | Startup/pending-entry recovery | Legacy trade write; no shared atomic fill-effect receipt proven | Reconstructs local open trade | VERIFIED candidate; exact overlap unresolved |
| PY-08 | `portfolio_trader.py` emergency exit/partial-close paths | Exit, residual balance, partial fill and recovery | Multiple `close_trade` and `update_trade_quantity` calls (e.g. around 1062, 1076, 1453, 1573, 1595, 1612, 1786, 2025, 2058) | Changes remaining quantity and realized result; repeat recovery may matter | VERIFIED multiple sinks; per-path idempotency PARTIAL |
| PY-09 | `trader.py:_recover_entry_intent`, `recover_state`, `_recover_closed_trade`, plus `save_trade` around 1375 | Legacy startup/recovery and trade lifecycle | Calls `save_trade` / `close_trade`; runtime exclusion from campaign/portfolio configurations not proven | Can alter same `trades` domain | VERIFIED legacy writer; reachability UNRESOLVED |
| PY-10 | `db.py:save_trade`, `update_trade_quantity`, `close_trade` (around 669, 712, 725) | Generic insert/update/close sink | Commits when no active transaction; no canonical fill identity argument or unique fill constraint visible on trade row | Used by campaign, portfolio and legacy recovery | VERIFIED generic mutation sinks |
| PY-11 | `db.py:save_campaign`, `save_campaign_fill` (around 1015, 1332) | Campaign state/fill persistence | Separate tables and calls; full caller closure and unique fill linkage not proven | Campaign recovery, status and risk decisions | VERIFIED sink; identity/atomicity PARTIAL |
| PY-12 | `db.py` direct writes to `orders`, `execution_intents`, `cancel_replace_operations`, `execution_events`, `bot_state`, reconciliation state | Order lifecycle, cancel/replace, state and recovery markers | Some methods use `INSERT OR IGNORE/REPLACE`; intent identity is not financial fill identity | Order state and execution gating | VERIFIED order/state writers; financial effect not implied |
| PY-13 | `execution_barrier.py` journal/intent state methods | Startup recovery and execution admission | Reads pending effect journal and blocks risk-increasing operations on unresolved effects/fee state; marks intents reconcile-required/reconciled | Controls entry admission, but does not atomically update all financial projections | VERIFIED gate/recovery writer, not financial applier |

### 3.1 Direct SQL and alternate call paths

The scan also found direct `self.db.conn.execute(...)` usage in `campaign_execution.py`, including trade updates that bypass a named DB helper, and direct connection queries in `portfolio_trader.py` used by reconciliation and reporting. This means auditing only calls to `save_trade` / `close_trade` is insufficient. A final inventory must enumerate every SQL mutation in every Python file, trace callers back to runtime entry points, and distinguish writes to execution state from writes to financial projections.

The fetched core files also show `Database.__init__` uses `sqlite3.connect(..., check_same_thread=False, timeout=20)`, `busy_timeout=20000`, `journal_mode=WAL`, and `synchronous=NORMAL`. The transaction context uses `BEGIN`, commit on success and rollback on `Exception`, with an instance-level `_transaction_active` flag. The flag is not a cross-process lock; no thread/process race test was run. The exact behaviour for cancellation/BaseException/process death and unknown COMMIT outcome remains untested.

## 4. Android writer inventory

| ID | File / function | Persisted or mutated state | Transaction / identity evidence | Downstream impact | Status |
|---|---|---|---|---|---|
| AN-01 | `StandaloneRuntime.kt:applyCampaignAddOnFill` (3456), `applyCampaignFill` (3552), `createCampaignPositionFromFill` (3577) | In-memory position/Campaign quantities and fill-derived state | Not within Python SQLite; durable linkage to a shared canonical fill-effect receipt not established | Can change open position and Campaign state used by runtime | VERIFIED writer path; ownership UNRESOLVED |
| AN-02 | `StandaloneRuntime.kt:recoverPendingEntries` (4847), `reconcilePositionsWithExchange` (5073), startup/reconnect invocations (e.g. 675–676, 1168–1169, 4750–4751, 8125) | Recovered positions, order state and local execution state | Exchange REST/recovery is present; complete event idempotency and durable cross-store atomicity not proven | Can change local position and admission/exit decisions | VERIFIED recovery paths; closure PARTIAL |
| AN-03 | `StandaloneRuntime.kt` sell/close paths (including calls to `TradeJournal.close` around 4505, 5242, 6173) | Position close/remaining quantity, journal outcome, local PnL | Several state writes/stores; no common transaction across them | Exit decisions, realized result and future local risk state | VERIFIED independent mutation path |
| AN-04 | `TradeJournal.kt:recordEntry/updateExcursion/close` | SharedPreferences JSON rows; entry/exit price, quantity, stop/take, risk %, notional and derived outcomes | Synchronized within object, but separate from SQLite and Python DB; generated journal ID uses timestamp + random suffix, not exchange fill ID | `close` computes PnL with `Double`; exact callers' use of computed value as execution/risk truth is not fully closed | VERIFIED local journal writer; canonicality UNRESOLVED |
| AN-05 | `TradingAuditStore.kt` schema and record methods, including `recordTrade` (325) | Local SQLite `orders`, `executions`, `events`, `trades`, and state transitions | `orders` / `executions` contain REAL quantity/price/commission columns; `executions.event_key` primary key; observed execution insert uses conflict-ignore and trade persistence uses conflict-replace | Audit/recovery source and possible trade projection; atomic relation to position/Campaign updates not proven | VERIFIED separate persistence surface |
| AN-06 | `ExecutionAccumulator.kt` | Aggregates execution quantities/quote amounts and supports serialization/restore using `BigDecimal` | In-memory/helper representation; not by itself proof of durable exactly-once application | May feed local order/fill interpretation | VERIFIED helper; downstream closure PARTIAL |
| AN-07 | `ExecutionGate.kt` | Admission gate state | Small atomic admission gate; does not provide cross-store financial transaction | Prevents some concurrent execution admissions, not proof of fill-effect exactly-once | VERIFIED gate; not ledger |

### 4.1 TradeJournal Double classification

`TradeJournal.kt` accepts `Double` for entry, quantity, stop/take, risk percentage and notional; stores those values in JSON; calculates planned risk/reward and excursion metrics; and calculates close PnL locally. This is a precision-risk surface, but use of `Double` alone is not proof of a defect. The unresolved question is whether any resulting journal values are read back into order sizing, exposure/risk limits, position quantity or exit decisions. That downstream dataflow was not completely closed, so the correct status is **PARTIAL**, not “display-only” and not “confirmed trading defect”.

### 4.2 TradingAuditStore semantics

The inspected schema defines `orders` with executed/cumulative quantity and average price, `executions` with event key, trade ID, last quantity/price and commission, and a separate `trades` table. Those tables form an Android audit/recovery store, but the code alone does not establish whether they are the canonical financial owner or merely projections. Event-key dedupe and conflict-ignore can dedupe a repeated audit event; they do not by themselves prove that a corresponding position/Campaign/PnL mutation is applied exactly once. Conflict-replace on a trade row may replace a projection without a shared effect receipt. Android/Python coordination remains unproven.

## 5. Ownership matrix

| Domain | Observed writers / candidate owner | Consumers | Required ownership status |
|---|---|---|---|
| Actual exchange fills and order facts | Binance Spot trade/order history is external factual source; Python and Android both ingest exchange responses/events | Recovery, local positions, fees, order state | **AUTHORITATIVE — VERIFIED** for exchange-recorded executions/order facts only |
| Canonical fill identity | Python `exchange_fill_ledger` uses symbol + exchange trade ID; Android execution `event_key` uses an event identity; exact mapping between them not proven | All fill consumers | **OWNERSHIP UNRESOLVED** |
| Applied financial effect | Python journal observes effect/fill state; multiple Python writers mutate projections; Android independently mutates projections | Trade/position/Campaign/fee/PnL/risk logic | **MULTIPLE WRITERS — COORDINATION UNPROVEN** |
| Position quantity / average cost | Python trade/quantity writers; Android StandaloneRuntime position/Campaign mutation and exchange reconciliation | Entry gates, sell/close sizing, exposure | **MULTIPLE WRITERS — COORDINATION UNPROVEN** |
| Commissions | Exchange trade history is factual source; Python stores raw fee facts and computes quote fee; Android stores commission as REAL in execution audit | Net PnL, sizing/risk projections, reconciliation | **OWNERSHIP UNRESOLVED** for canonical accounting/conversion |
| Realized PnL | Python trade close/update helpers and legacy recovery; Android TradeJournal close calculation and trade store | Reports and possibly subsequent risk logic | **MULTIPLE WRITERS — COORDINATION UNPROVEN** |
| Campaign state | Python campaign engine/DB; Android StandaloneRuntime campaign methods | Entries/add-ons/stops/trailing/exits | **MULTIPLE WRITERS — COORDINATION UNPROVEN** |
| Risk reservations / available risk | Python Campaign/portfolio state and Android local risk/execution state | Risk gates and order sizing | **OWNERSHIP UNRESOLVED** |
| Android execution state | `StandaloneRuntime`, `TradingAuditStore`, `ExecutionAccumulator`, gate state | Native order submission, recovery and local decisions | **AUTHORITATIVE — UNPROVEN**; runtime acts as execution owner locally but durable authority contract not established |
| Android financial projection | SharedPreferences journal + SQLite audit store + runtime position/Campaign state | Local entry/exit/reconciliation logic | **PROJECTION — UNPROVEN**; cannot label read-only/display-only without consumer closure |

**Cross-platform conclusion:** the current source evidence does not show Android as read-only. The Android runtime contains direct position/Campaign fill application, exchange reconciliation and order execution logic. No durable handoff protocol with a shared financial effect identity/receipt was proven between Android and Python. Until source-level consumer closure proves otherwise, treat both as independent writer candidates. New risk-increasing actions must remain blocked when financial reconciliation is unresolved.

## 6. Fill-to-financial-state and transaction-boundary map

This is the observed partial map, not an assertion that all code follows one universal sequence.

### Python entry / add-on

1. Campaign execution obtains order-scoped exchange trade history.
2. `_executed_fee_quote()` calls `db.record_exchange_fill()`.
3. The fill observation and financial-effect journal state are persisted under the DB transaction helper.
4. Campaign execution invokes `record_initial_fill()` or `record_add_on_fill()`.
5. Trade/quantity state is persisted through `save_trade()`, `update_trade_quantity()`, and in one path direct SQL to `trades`.
6. Campaign state is persisted separately through `save_campaign()`.
7. Later recovery/reconciliation may revisit exchange history and independently update the trade or Campaign.

Observed transaction boundary: step 3 has a local transaction for fill observation/journal. The inspected source does **not** prove steps 3–6 are in the same transaction. Generic DB helpers commit if `_transaction_active` is false; call paths include separate writes and commits. Therefore a partial durable state is possible in principle between those boundaries; exact impact and recovery outcome require fault-injection tests.

### Python exit / partial close

1. Exit order/recovery path obtains authoritative trade history and calls `record_exchange_fill()`.
2. Fee conversion/summary is returned from a separate helper.
3. Exit/reconciliation path calculates residual quantity / close state.
4. `close_trade()` and/or `update_trade_quantity()` writes the local trade.
5. Campaign/risk state may be updated separately.

No evidence establishes one transaction containing fill identity, fee, quantity, realized PnL, Campaign and risk-reservation effects. Repeated close/recovery idempotency remains unproven.

### Android runtime

1. REST/WebSocket event or recovery result is parsed.
2. Execution accumulator / audit store may persist event facts.
3. Runtime applies campaign/position changes.
4. TradeJournal SharedPreferences and/or TradingAuditStore trade state is written separately.
5. Restart recovery replays/reconciles order, position and trade history.

These operations do not share Python's SQLite transaction. Even within Android, a single transaction joining audit execution, runtime position/Campaign and SharedPreferences journal was not established. An audit event dedupe key cannot make separate stores atomic.

## 7. Schema, transaction and idempotency findings

### Python SQLite

- Connection configuration observed: `check_same_thread=False`, timeout 20 seconds, `busy_timeout=20000`, WAL, `synchronous=NORMAL`.
- Transaction helper: explicit `BEGIN`, commit on normal completion, rollback on `Exception`, instance-level nesting flag.
- `exchange_fill_ledger`: unique `(symbol, exchange_trade_id)` observation key. Account/API environment is not part of the observed key contract.
- `financial_effect_journal`: fill identity/status journal exists; this is not evidence that all projections are atomically applied.
- `trades`, Campaign-related tables and analytics contain REAL-compatible values in inspected schema; fill raw facts retain text/exact metadata for some fields.
- `save_trade`, `update_trade_quantity`, `close_trade`, `save_campaign` are separate mutation APIs and can commit independently outside an active transaction.
- A schema in source does not prove the deployed SQLite file completed every migration. Production DB migration state was not accessed.
- `RLock`/an instance transaction flag, where used, would not establish inter-process exclusion. The observed DB helper does not prove safe concurrent use of one connection by independent writers.

### Android persistence

- `TradingAuditStore` is a separate SQLite database (`williams_trading_audit.db`, schema version 4 in inspected source) with REAL-valued order/execution/trade fields.
- `TradeJournal` writes a JSON array to SharedPreferences and performs local PnL calculations with `Double`.
- `ExecutionAccumulator` uses `BigDecimal` for some aggregation, but its existence does not establish exact end-to-end values once data crosses REAL/Double/JSON boundaries.
- No cross-store transaction or shared effect-application receipt spanning these stores and Python SQLite was found in the inspected code.

### Exactly-once status

The practical invariant “one confirmed fill produces at most one complete local financial effect” is **UNPROVEN**. The raw observation dedupe is not equivalent to idempotent projection application. Unknown COMMIT, two-process races, duplicate recovery, later fee enrichment, and concurrent position/Campaign updates have not been tested.

## 8. Recovery interaction and execution safety

- Python's `execution_barrier.py` checks unresolved financial journal entries/fee status and unresolved execution intents before allowing risk-increasing execution. This is a useful fail-closed gate, but it does not atomically apply the financial effect.
- Python campaign recovery, portfolio recovery and legacy `Trader` recovery are distinct paths that can reach overlapping generic trade mutation helpers. Complete configuration/reachability exclusion has not been proven.
- Android starts/reconnects into `recoverPendingEntries()` and `reconcilePositionsWithExchange()` from multiple lifecycle points. Those paths can update local state and must be tested against duplicate/reordered events and process death.
- The exchange is authoritative for whether an order/fill occurred. Local state may not be used to infer a fill that is not confirmed by exchange evidence.
- If exchange history is unavailable, fill identity conflicts, fee facts are unknown, or local projections disagree, risk-increasing operations must remain blocked until reconciliation establishes a deterministic state. A successful API response alone is not proof that local effects have been applied exactly once.

## 9. Required executable test matrix — all NOT RUN

All tests must use existing production entry points, temporary isolated DBs, synthetic Binance responses, and separate processes where relevant. No real API keys and no order mutations. These rows are test specifications, not results.

| ID | Initial state / input | Failure or race | Expected durable state and financial effect | Position / fee / PnL / Campaign expectation | PASS criterion | Status |
|---|---|---|---|---|---|---|
| T01 | Empty isolated DB; one confirmed fill | Deliver same fill twice to `record_exchange_fill` and actual application path | One canonical observation and at most one applied effect | Position delta equals one fill; fee/PnL applied once; Campaign changes once | No second delta on replay | NOT RUN |
| T02 | One order, two distinct trade IDs | Deliver fills separately and replay both | Two fill receipts/effects, one per distinct trade ID | Quantity equals sum of both fills; commissions sum once | Exact reference totals match | NOT RUN |
| T03 | Same fill delivered through REST and WS | Duplicate source delivery | One effect receipt | No duplicate quantity, fee, PnL or Campaign delta | Stable totals after second delivery | NOT RUN |
| T04 | Fill initially has unknown commission | Later fetch authoritative commission | Same fill identity, explicit fee enrichment | Fee transitions unknown→confirmed once; net PnL updated once; no assumed zero | Repeated enrichment is stable | NOT RUN |
| T05 | Existing fill identity; immutable qty/price differs | Replay conflicting facts | Durable reconciliation latch; no silent overwrite | Position/Campaign unchanged pending repair; risk increase blocked | Conflict visible after restart | NOT RUN |
| T06 | Two independent SQLite connections, same fill | Concurrent apply | Unique identity arbitrates; one effect | One quantity/fee/PnL/Campaign delta | Both callers converge to same durable state | NOT RUN |
| T07 | Two OS processes and independent DB objects | Race same fill | One receipt/effect; loser re-reads durable state | No duplicated financial projection | Reproducible process-level test passes | NOT RUN |
| T08 | No transaction started | Fail before fill insert | No receipt/projection change | All financial totals unchanged | Retry applies once | NOT RUN |
| T09 | Fill observation insert performed | Raise before downstream projection | Full rollback or durable explicit PENDING; never APPLIED with missing projection | No partial state accepted for trading | Recovery detects/repairs deterministically | NOT RUN |
| T10 | All projection writes staged | Raise after each individual projection write before COMMIT | All-or-nothing local transaction | Position, fee, PnL, Campaign and reservation all revert or all commit | No mixed committed state | NOT RUN |
| T11 | Commit outcome is deliberately ambiguous to caller | Drop acknowledgement after COMMIT attempt | Reopen a new connection and resolve receipt/projections | Do not blindly reapply; final totals correct once | Deterministic outcome without duplicate | NOT RUN |
| T12 | Commit succeeded | Kill process before caller receives success | Durable completion is discoverable on restart | Replay leaves all financial totals unchanged | Restart/replay idempotent | NOT RUN |
| T13 | Pending entry and fill exist | Run startup recovery repeatedly | Recovery converges to one durable trade/position | Quantity/Campaign/fees/PnL stable after first completed recovery | N repeated recoveries are idempotent | NOT RUN |
| T14 | Local position behind exchange history | Exchange history returns newer fills | RECONCILE_REQUIRED until matched | No risk-increasing action during mismatch | Gate rejects entry/add-on | NOT RUN |
| T15 | Force duplicate unique-key insert | Same canonical fill key inserted concurrently | One receipt; collision handled explicitly | No second financial delta | Unique constraint cannot be bypassed by caller retry | NOT RUN |
| T16 | Ledger says fill observed, projection deliberately stale | Start recovery | Mismatch detected; no false APPLIED | No entry until repair; campaign reconciled to reference | Recovery detects discrepancy | NOT RUN |
| T17 | Two different fills update one position concurrently | Interleave quantity/average-cost updates | Serializable deterministic result | Position equals sum and weighted-average reference; PnL/fees correct | Result independent of schedule | NOT RUN |
| T18 | Android receives same execution event twice across process restart | Replay event + restore accumulator | One effective fill application or explicitly non-authoritative audit-only record | Runtime quantity/Campaign not duplicated | Android restart test stable | NOT RUN |
| T19 | Android price/qty/commission values cross Double/REAL/JSON boundaries | Use decimal edge values and values beyond binary exact integer range | Exact canonical values retained elsewhere or lossy values never become authority | Risk/size/PnL not computed from lossy projection | Numeric reference comparison passes | NOT RUN |
| T20 | Android and Python ingest same confirmed exchange fill independently | Force divergent timing/replay | Single explicit ownership protocol or both stores marked projections and reconciled | No independent risk increase from stale local projection | Handoff/authority invariant verified | NOT RUN |
| T21 | Exchange history unavailable during recovery | Simulate REST unavailable / incomplete page | State remains unresolved | No inferred fill; no risk-increasing entry | Fail-closed gate observed | NOT RUN |
| T22 | Partial fill then order cancel | Fill events then cancellation response | Persist only confirmed individual fills; canceled remainder is not a fill | Position equals confirmed fill sum; Campaign residual accurate | No phantom quantity | NOT RUN |
| T23 | Repeated close/recovery event | Replay sell fill and recovery close path | One exit effect and one final close state | Realized PnL/fees and remaining quantity stable | Second close is a no-op or deterministic reconcile | NOT RUN |
| T24 | Interrupted schema migration in isolated DB | Kill/reopen at each migration boundary | Valid old or new schema, never silently mixed | No loss/duplication of fill/effect receipts | Migration/recovery repeatable | NOT RUN |

For every test, record production entry point, exact source SHA, DB schema version, injected boundary, before/after table snapshots, expected and actual Decimal totals, and command/log output. A green general CI run does not turn any row above into PASS.

## 10. Proven issues vs unresolved hypotheses

### Proven from inspected source

1. Python raw fill ingestion and financial projection mutation are distinct APIs and not shown in one universal transaction.
2. Python Campaign, portfolio and legacy trader paths contain multiple trade/Campaign writers and direct SQL mutations.
3. Android contains active fill-to-position/Campaign mutation and recovery paths; it is not demonstrated to be a read-only UI.
4. Android persists order/execution/trade data in a separate SQLite store and stores trade journal state in SharedPreferences.
5. Python and Android persistence do not share a SQLite transaction.
6. Several financial representations use REAL/Double-compatible storage or calculations.
7. The CI metadata shows successful general workflows, but no task-specific crash/concurrency evidence was run.

### Not proven; must remain UNRESOLVED

1. Complete closure of every Python and Android writer/caller.
2. Whether legacy `Trader` and `PortfolioTrader` can run simultaneously or share state in all deployment modes.
3. Whether Android local position/Campaign/PnL is used as authoritative input to order sizing, risk or close decisions in every path.
4. One canonical fill ID shared by Binance facts, Python journal, Android audit events and all projections.
5. Atomicity spanning fill identity, fees, trade quantity, realized PnL, Campaign and risk reservations.
6. Cross-process concurrency safety and unknown-COMMIT recovery.
7. Exact deployed DB migration state and all production schemas.
8. Whether `TradeJournal`-derived values flow into trading decisions; Double usage is a risk surface, not by itself a proven defect.
9. Complete deterministic policy for late fees and non-quote commission conversion.
10. Whether every unresolved state reliably blocks every risk-increasing execution path, including Android-only paths.

## 11. Minimal plan before a separate implementation task

1. Obtain a complete exact-SHA checkout or verifiable CI runner snapshot and record worktree cleanliness, Python/Kotlin toolchains and dependency locks.
2. Build a source-wide writer inventory over all Python `execute/executemany`, SQL literals, DB helper calls, Android SQLite/SharedPreferences writes, order-event callbacks and state mutations.
3. Trace each writer to all HTTP/API entry points, scheduled tasks, startup paths, WebSocket handlers and runtime construction sites; prove exclusion for legacy/alternate paths rather than assuming it.
4. Add an ownership contract before implementation: exchange facts are authoritative; designate exactly one durable application owner per fill-effect domain; explicitly label all other stores as projections with defined rebuild/reconciliation behaviour.
5. Establish the exact deployed schema/migration and SQLite connection/transaction contract.
6. Use existing production entry points for T01–T24 in isolated tests, including two independent OS processes, injected exceptions, process kill and unknown COMMIT.
7. Only after inventory closure and a reviewed design should a separate implementation task decide whether a single applier is necessary. Do not create a second ledger or wire a new component based on assumptions.

## 12. Final decision

**BLOCKED — WRITER INVENTORY INCOMPLETE / OWNERSHIP UNRESOLVED / ATOMICITY UNPROVEN / RECOVERY AND TEST EVIDENCE INCOMPLETE.**

The source evidence confirms competing Python and Android financial-state writer candidates and independent persistence boundaries. It does not prove complete writer closure, a single authoritative financial application owner, or exactly-once effect application. No production financial changes were made. `FinancialEffectApplier` remains out of scope and must not be connected to the production execution path.

**Production code changed:** none.  
**Schema / strategy / risk logic changed:** none.  
**Binance order mutations:** none.  
**Task-specific tests:** NOT RUN.  
**General CI metadata:** successful runs observed; not acceptance evidence for atomic financial safety.  
**Conclusion applies only to this audit and does not clear any other P0 blocker.**
