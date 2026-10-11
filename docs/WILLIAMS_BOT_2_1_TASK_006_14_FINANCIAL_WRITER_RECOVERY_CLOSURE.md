# WILLIAMS BOT 2.1 — TASK 006.14
## Complete Financial Writer Reachability, Recovery Gate Enforcement & Cross-Platform Safety Proof

**Final disposition: BLOCKED — NOT ACCEPTED**  
**Repository:** `19wolfalone92/Williams`  
**Branch:** `audit/williams-2.1-task-006-android-runtime`  
**PR #46:** Open / Draft / unmerged  
**Base SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Audited source SHA before this report:** `4b57635aeefdd2d9720e33b850583ddc5c708af9`  
**Production changes in this task:** none  
**Financial applier / second ledger:** not added  
**Exchange mutations:** none

## 1. Executive decision

The current source inspection found concrete reachable financial write surfaces and unresolved safety contracts. This task does not meet the completeness requirement: the full repository-wide call graph from all process entry points, workers, callbacks and execution clients to every SQL and exchange mutation sink has not been proven. Android and Futures source-to-sink graphs are not closed. Required financial concurrency, independent-process, process-death and fee-resolution tests were not executed as part of this task.

Therefore **TASK 006.14 remains BLOCKED — NOT ACCEPTED**. No speculative production correction was made: changing an individual writer before all its callers and alternative paths are mapped could create another financial owner or block emergency risk reduction incorrectly.

## 2. Fresh baseline and CI evidence

At task start, PR #46 was freshly queried:

| Item | Observed |
|---|---|
| PR state | Open |
| Draft | true |
| Merged | false |
| Base branch / SHA | `main` / `04da5f9c09572e99ff310be3a1908179165e528c` |
| Head branch / SHA | `audit/williams-2.1-task-006-android-runtime` / `4b57635aeefdd2d9720e33b850583ddc5c708af9` |

Workflow lookup for audited SHA `4b57635aeefdd2d9720e33b850583ddc5c708af9` returned:

| Workflow | Run ID | Result | Link |
|---|---:|---|---|
| Williams Campaign CI | `38128162990` | completed / success | https://github.com/19wolfalone92/Williams/actions/runs/38128162990 |
| Build Android APK | `38128163049` | completed / success | https://github.com/19wolfalone92/Williams/actions/runs/38128163049 |
| Python backend tests | `38128162999` | completed / success | https://github.com/19wolfalone92/Williams/actions/runs/38128162999 |

These are valid general CI results for the audited pre-report SHA. They do **not** prove the report commit's final-SHA CI or satisfy the dedicated financial fault-injection, multi-process, Android runtime and Futures tests required here.

## 3. Source-to-sink inventory: confirmed findings

The following files were fetched from the target branch at audited SHA `4b57635aeefdd2d9720e33b850583ddc5c708af9`. The findings below are concrete but the inventory remains explicitly partial.

### 3.1 `db.py` — SQL sinks and transaction behavior

Source: [db.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/db.py)

Confirmed write functions/sinks from source inspection:

- `Database.state_set`: writes `bot_state`; commits automatically when the instance's `_transaction_active` flag is false.
- `Database.save_order`: writes order rows; complete caller graph not established.
- `Database.save_trade`: INSERT into `trades`.
- Trade update/close methods: UPDATE `trades`, including quantity and financial/exit fields.
- `Database.save_campaign`: INSERT/replace-style campaign persistence to `campaigns`.
- `Database.save_campaign_order`: writes `campaign_orders`.
- `Database.save_campaign_fill`: writes both `exchange_fill_ledger` and compatibility projection `campaign_fills`.
- `Database.save_execution_event` and `log_campaign_event`: persist events that may be relevant to recovery/audit.

Transaction primitive `Database.transaction` uses `BEGIN IMMEDIATE`, commit and rollback. That primitive is not proof that all of these calls share the same connection and transaction: the transaction-active flag is per Database object, and some methods commit when the flag is false.

#### Canonical fill persistence

`save_campaign_fill` requires a fill identity, order ID, side, quantity, price, quote quantity, commission amount and commission asset. It canonicalizes numeric values through Decimal and stores canonical decimal strings in `exchange_fill_ledger`. Existing same-key rows are compared against selected immutable fields; a mismatch sets `recovery_status=RECOVERY_REQUIRED`, emits a conflict event and raises/reports a conflict.

The ledger identity observed in the code is `(exchange, account_id, symbol, trade_id)`. The inspected identity does not explicitly include environment or market type. The full contract for isolating Testnet/Mainnet and Spot/Futures identities remains unproven.

**Important confirmed precision gap:** the same method converts `quantity_dec`, `price_dec`, `quote_dec`, `fee_quote`, `commission_base` and `commission_dec` to Python `float` for `campaign_fills`. Therefore canonical TEXT evidence coexists with a REAL/float compatibility projection; end-to-end Decimal exactness is not established.

The method's docstring says ledger rows remain `PENDING` until a separately proven applier commits dependent financial effects. That is evidence that the ledger row itself is not proof of applied position/campaign/risk effects. No applier was added in this task.

### 3.2 `portfolio_trader.py` — Spot orchestration and recovery

Source: [portfolio_trader.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/portfolio_trader.py)

Confirmed methods include `recover_pending_entries`, `reconcile_open_positions`, `recover`, `execute_campaign` and `execute`, with direct `db.save_trade` call sites. These establish multiple entry/recovery/lifecycle surfaces, but the complete incoming call graph and all final exchange mutation sinks were not proven.

The inspected `reconcile_pending_entries` path reads an existing conditional order from Binance and can process a recovered executed quantity. The visible path uses `float` for executed quantity and cumulative quote quantity, may reconstruct quote by summing float price × quantity from `my_trades`, and for an add-on calls `record_add_on_fill` with `fee_quote=0.0`. It also updates trade/campaign state and commits trade updates in a later step. This path is a concrete reason the task cannot claim fee-state completeness, exact Decimal propagation, or atomicity across all effects.

This finding is not proof that every runtime reaches the path; it is proof that a recovery-side financial path exists and needs end-to-end tests and ownership analysis.

### 3.3 `trader.py` — alternative Spot lifecycle

Source: [trader.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/trader.py)

Confirmed separate `Trader` lifecycle/recovery methods and `db.save_trade` call sites. The exact relationship between this path and `MultiPositionTrader` is not proven to be exclusive delegation. It must be treated as a potentially independent writer/execution route until the callers and construction roots establish otherwise.

### 3.4 `campaign_execution.py` — campaign fill and projection writes

Source: [campaign_execution.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/campaign_execution.py)

The source contains calls to `_submit`, `save_campaign_order`, `save_campaign`, `record_add_on_fill`, `record_initial_fill`, `save_trade`, and direct SQL `UPDATE trades` operations. It also contains pending-entry reconciliation and active-campaign reconciliation.

These are separate business logic and projection mutation surfaces. The complete set of callers, transaction boundaries for each sequence, and whether every mutation is guarded by a final financial Recovery Gate remain unproven. The existence of an order submission barrier does not establish atomicity of the subsequent fill/campaign/trade projections.

### 3.5 `campaign_engine.py` — campaign state mutation

Source: [campaign_engine.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/campaign_engine.py)

Confirmed `record_add_on_fill`, `record_initial_fill`, and campaign persistence calls. These update campaign-level quantity/cost/risk/lifecycle state through the engine and DB methods. Their full caller graph and transaction relationship to ledger/fill rows and trade projections were not exhaustively traced.

### 3.6 `execution_accumulator.py`

Source: [execution_accumulator.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/execution_accumulator.py)

Contains `accumulate_fills` and `accumulate_order`. In-memory normalization/deduplication does not prove durable cross-source exactly-once effects after restart. Default zero-valued fee fields and caller-supplied summaries require auditing at every caller.

### 3.7 `execution_barrier.py` and `execution_recovery.py`

Sources: [execution_barrier.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/execution_barrier.py), [execution_recovery.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/execution_recovery.py)

The barrier's `execute` path calls a supplied `submit()` after checks. The recovery resolver handles order-intent resolution. Neither fact alone proves every client mutation uses this barrier or that financial effects and fees are committed atomically with the order/fill recovery outcome.

### 3.8 Partial reachability graph

The code evidence currently establishes these partial edges:

```text
Spot lifecycle roots (exact construction/caller roots not fully traced)
  ├─ Trader (trader.py)
  │    └─ Database.save_trade / recovery and order paths
  └─ MultiPositionTrader (portfolio_trader.py)
       ├─ recover_pending_entries / reconcile_open_positions / execute
       ├─ CampaignExecutionService (campaign_execution.py)
       │    ├─ CampaignEngine.record_initial_fill / record_add_on_fill
       │    ├─ Database.save_campaign_order / save_campaign / save_trade
       │    └─ direct UPDATE trades
       └─ Database
            ├─ exchange_fill_ledger
            ├─ campaign_fills (REAL/float compatibility projection)
            ├─ trades (legacy financial projection)
            ├─ campaigns (campaign/position/risk projection)
            ├─ campaign_orders
            └─ bot_state / execution_events
```

Unresolved edges include the full set of external entry points, object construction and dependency injection, callbacks, workers/schedulers, raw SQL throughout the repository, every Binance client mutation call, Android runtime/store callers, and Futures sinks. Therefore this graph is not complete.

## 4. Recovery Gate closure matrix

| Path | Evidence available | Required proof still missing | Result |
|---|---|---|---|
| Spot initial BUY | Spot orchestration and barrier-related code exists | All callers/sinks; final gate directly before each mutation; no bypass through client/writer | BLOCKED |
| Spot add-on BUY | Campaign add-on writer and recovery reconciliation exist | Recovery-state, exposure/reserve, pending-effect and protection checks at every mutation sink | BLOCKED |
| Retry/replacement after UNKNOWN result | Durable intent and order recovery mechanisms exist | Full client-mutation coverage and exact no-blind-retry proof across all callers | BLOCKED |
| Normal SELL | Multiple lifecycle/order paths exist | Current exchange free balance, open-order/UNKNOWN reconciliation, duplicate prevention and end-to-end test proof | BLOCKED |
| Emergency SELL | No complete isolated safe-path proof obtained | Actual available balance, LOT_SIZE/MIN_NOTIONAL, in-flight sells, ambiguous result recovery and no oversell | BLOCKED |
| Android BUY/add-on | Android app/runtime exists in repository | Full Kotlin mutation call graph, local persistence/recovery gate and process-death instrumentation proof | BLOCKED |
| Futures BUY/add-on | Repository-wide Futures graph not closed | Either all Futures sinks gated or code proof that risk-increasing operations are unreachable/disabled | BLOCKED |

No code change was made to block all SELL operations. That would not be an acceptable substitute for a proven emergency risk-reduction path. Equally, no claim is made that emergency SELL is currently safe.

## 5. Financial identity and fee-state contract

The current evidence does not establish a durable `KNOWN / UNKNOWN / INVALID / CONFLICTED` fee state machine end to end.

- `save_campaign_fill` rejects missing commission or commission asset instead of persisting a durable UNKNOWN state for later resolution.
- The recovery-side add-on path can pass `fee_quote=0.0`; this cannot be treated as proof of zero exchange commission.
- The compatibility projection converts Decimal values to float.
- Same-key payload conflict handling exists for selected ledger fields, but environment and market-type identity scope, all ingestion sources and durable conflict recovery are not proven.
- No test evidence demonstrates that a later fee update changes only the fee effect and does not reapply quantity/cost basis.

These are blockers, not claims of a particular realized monetary loss.

## 6. SQLite, concurrency and crash-recovery test evidence

The repository contains tests including `test_atomic_real_db.py`, `test_execution_recovery.py`, `test_financial_integrity.py`, `test_execution_barrier_durability.py`, and `test_execution_barrier_double_failure.py`. Their existence is not equivalent to the task's full acceptance proof.

No dedicated run results were obtained in this task for the complete required matrix:

1. REST, WebSocket and recovery duplicate delivery;
2. same-identity conflicting payload;
3. concurrent workers and independent OS processes;
4. UNKNOWN fee later resolved without replaying quantity/cost;
5. fault injection before/during/between projection writes;
6. process death and restart between ledger and projection state;
7. BUY/add-on blocked by unresolved recovery with DB-state and exchange-mutation assertions;
8. exposure/reserve mismatch blocks risk increase;
9. emergency SELL remains safe and available under ambiguity.

No test is marked PASS here merely because a related test file exists.

## 7. Android and Futures

Android is an independent runtime and local persistence domain. This audit did not establish every Kotlin order mutation sink, all callers of `TradingAuditStore`, process-death semantics, or proof that every risk-increasing path is Recovery-Gate-protected.

Futures is also not proven unreachable or fully gated. Absence of a complete map is not proof that the route is disabled.

Both domains remain P0 blockers until either:
- the complete source-to-sink graph and runtime tests prove safety, or
- a code-level, test-backed restriction proves all risk-increasing mutation routes unreachable while preserving separately verified risk reduction.

## 8. Final-SHA CI status

The three workflows listed in section 2 succeeded on audited SHA `4b57635aeefdd2d9720e33b850583ddc5c708af9`. This report will create a new commit SHA; those run IDs cannot be represented as CI for that new final SHA. A final-SHA workflow lookup and exact-head verification are required after the report commit.

The workflows also do not substitute for the missing dedicated financial concurrency, crash-recovery, Android process-death and Futures safety tests.

## 9. Minimal next safe task

1. Establish a machine-checkable inventory of all SQL writes to financial tables and all exchange mutation calls from the exact repository tree.
2. Trace all process roots, constructors, workers, callbacks, schedulers and recovery paths to each sink; identify all direct-client bypasses.
3. For each sink, prove its DB connection/transaction, identity key, durable conflict handling and Recovery Gate checks.
4. Add path-level tests using a real temporary SQLite database and a fake exchange that counts mutations; test both DB state and mutation count.
5. Test independent-process duplicate handling and commit/process-death ambiguity.
6. Audit Android and Futures as independent domains; prove gates or prove risk-increasing paths unreachable.
7. Prove emergency SELL separately using current exchange free balance and in-flight order reconciliation.
8. Only after the graph and gates are closed may a separate task implement the canonical financial applier.

## 10. Final status

**TASK 006.14 — BLOCKED — NOT ACCEPTED**

- **Base SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`
- **Audited source SHA:** `4b57635aeefdd2d9720e33b850583ddc5c708af9`
- **Final report commit SHA:** recorded after commit in task response; final-SHA CI must be queried separately.
- **Changed files:** this report only.
- **Production fixes:** none; no sufficiently isolated correction was proven safe without full reachability evidence.
- **Applier / new production fill path:** not added.
- **P0 blockers:** incomplete source-to-sink graph; unresolved direct writers and transaction boundaries; Recovery Gate coverage not proven for all BUY/add-on paths; float/REAL compatibility projection and recovery-side zero-fee fallback; no full duplicate/concurrency/crash/fee-resolution tests; Android and Futures reachability unresolved; emergency SELL safety not proven.
- **PR:** remains Open / Draft / unmerged; `main` unchanged.


## 11. Post-report exact-SHA CI verification (TASK 006.15 baseline)

A fresh workflow lookup was performed for the TASK 006.14 final report commit
`42fa73dc48a4aa7a522cec45f3e96e06ae159eb7`. All three workflows have now
completed successfully on that exact SHA:

| Workflow | Run ID | Conclusion | Verified job-level evidence |
|---|---:|---|---|
| Python backend tests | `38130095367` | success | release contract gate, Python compile, full static safety audit, safe offline tests |
| Build Android APK | `38130095335` | success | release contract gate, required Android lint, Android unit tests, debug/release APK builds, APK metadata/signature/checksum verification |
| Williams Campaign CI | `38130095342` | success | deterministic safety/campaign tests; Android build and static checks |

Run links:
- https://github.com/19wolfalone92/Williams/actions/runs/38130095367
- https://github.com/19wolfalone92/Williams/actions/runs/38130095335
- https://github.com/19wolfalone92/Williams/actions/runs/38130095342

This closes only the previous report's missing *general exact-SHA CI status*. It does
not establish the dedicated acceptance matrix required by TASK 006.15: independent
process concurrency, financial projection crash injection, late fee resolution,
cross-source exactly-once application, all-path Recovery Gate reachability, Android
process-death financial recovery, Futures mutation coverage, or emergency SELL
no-duplicate proof. The report's overall disposition therefore remains
**BLOCKED — NOT ACCEPTED**.
