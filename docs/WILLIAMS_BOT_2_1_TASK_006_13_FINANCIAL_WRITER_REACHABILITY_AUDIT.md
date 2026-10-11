# WILLIAMS BOT 2.1 — TASK 006.13
## Complete Financial Writer Inventory, Reachability Proof & Risk-Gate Closure

**Disposition: BLOCKED — NOT ACCEPTED**  
**Audit branch:** `audit/williams-2.1-task-006-android-runtime`  
**PR:** [#46](https://github.com/19wolfalone92/Williams/pull/46), Open / Draft / unmerged  
**Audited pre-report HEAD:** `1bdf90c9754e6df5690056d9c2d96812406f4c6c`  
**Base/main SHA reported by PR:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Production changes:** none  
**Financial applier:** not implemented  
**Exchange mutations:** none

## 1. Decision

The requested completeness proof cannot be established from the evidence gathered in this task. The code confirms several independent financial mutation surfaces, SQLite projections that still use REAL/float, and a canonical fill evidence table that is not itself proof that all projections were applied exactly once. The repository connector's code search did not return reliable code-search results for the requested symbols, so names/imports and prior audit notes are not being misrepresented as a complete call graph.

The acceptance rule explicitly says that any reachable writer or risk-increasing path not verified means BLOCKED. Therefore this task remains **BLOCKED — NOT ACCEPTED**. No production writer, schema, strategy rule, risk limit, or execution path was changed; no financial applier or second ledger was added.

## 2. Fresh baseline and CI

| Check | Observed result |
|---|---|
| PR #46 | Open, Draft=true, merged=false |
| PR head before this report | `1bdf90c9754e6df5690056d9c2d96812406f4c6c` |
| PR base | `main` |
| PR base SHA | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Python backend tests on audited SHA | workflow run `38126587346`: completed / success |
| Android APK build on audited SHA | workflow run `38126587299`: completed / success |
| Williams Campaign CI on audited SHA | workflow run `38126587301`: completed / success |
| Combined commit statuses | no status entries returned by the connector |

These CI runs were associated with the **pre-report** SHA and do not prove the new report commit's CI state. Even green general CI is not a substitute for the required financial fault-injection, independent-process concurrency, Android runtime and Futures reachability tests.

## 3. Confirmed schema and ownership facts

### 3.1 SQLite and projections — `db.py`

Observed in the current branch source:

- `Database.__init__` creates a SQLite connection with `check_same_thread=False`, a 20-second timeout, WAL, `synchronous=NORMAL`, and a 20-second busy timeout.
- `Database.transaction` uses `BEGIN IMMEDIATE`, then commit or rollback. Its `_transaction_active` flag is an instance field, so nested-transaction handling is local to that Python object; this is not proof that every writer uses the same connection/transaction.
- The schema defines legacy `trades`, `campaigns`, `campaign_orders`, and `campaign_fills` projections with financial numeric columns typed `REAL`.
- `exchange_fill_ledger` stores decimal values as TEXT and has primary key `(exchange, account_id, symbol, trade_id)`. It includes order/intent/campaign IDs, side, quantity, price, quote quantity, commission, commission asset, `effect_state` defaulting to `PENDING`, and raw payload.
- `campaign_fills` has a partial unique index on `(exchange, account_id, symbol, trade_id)` when trade_id is not NULL. Legacy rows lacking matching canonical-ledger evidence set `recovery_status=RECOVERY_REQUIRED` and a blocked migration marker.
- `state_set` and `try_claim_state` commit automatically when `_transaction_active` is false. Callers that need a multi-write atomic unit must prove they entered the transaction context on the same Database instance.
- The schema's REAL projections and legacy defaults remain incompatible with claiming end-to-end Decimal exactness.

Source: [db.py on audited branch](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/db.py).

### 3.2 Fill extraction — `execution_accumulator.py`

Observed:

- `accumulate_fills` validates trade ID, quantity, price, commission and commission asset, uses Decimal conversion, and rejects repeated IDs within one response; differing duplicate payloads are rejected.
- `ExecutionSummary` still has default `Decimal("0")` values for fee fields and `financials_complete=True`. This is not a durable fee-state machine and does not establish that an absent fee is UNKNOWN rather than zero at every caller.
- The in-response duplicate check does not by itself prove cross-source deduplication across REST, WebSocket, order history, process restart, accounts, environments and markets. The ledger identity must be compared with full canonical payload and conflict evidence at every durable ingestion path.

Source: [execution_accumulator.py on audited branch](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/execution_accumulator.py).

### 3.3 Spot order route — `portfolio_trader.py`

Observed:

- `MultiPositionTrader` constructs or accepts a `Database`, constructs `CampaignExecutionService`, and accepts an `ExecutionBarrier`.
- `_submit_order` documents routing legacy portfolio order mutations through the barrier.
- This demonstrates an intended order-mutation boundary, but does not prove all fill projection writes share one financial-effect owner or that every risk-increasing entry/add-on caller is reachable only through this method.
- Constructor configuration still uses Python float for risk limits and tolerances. This task does not alter the risk policy; it records that these values are not evidence of Decimal financial projection integrity.

Source: [portfolio_trader.py on audited branch](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/portfolio_trader.py).

### 3.4 Campaign engine — `campaign_engine.py`

Observed:

- `CampaignEngine.create_campaign` writes the campaign, signal and campaign event through separate DB method calls. The fetched implementation does not show these three writes enclosed in one visible transaction.
- Campaign tags serialize risk and price values using float conversions.
- Campaign engine is therefore a separate durable campaign state/projection surface. The exact fill writers, their callers, and whether all relevant mutations are inside a single transaction were not exhaustively enumerated in this task.

Source: [campaign_engine.py on audited branch](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/campaign_engine.py).

### 3.5 Order execution barrier and recovery resolver

- `ExecutionBarrier` is an order-mutation admission/persistence boundary, not a demonstrated canonical financial fill applier.
- `ExecutionRecoveryResolver` explicitly performs read-only order reconciliation and does not submit a mutation. It resolves durable execution intents using exact order/list evidence and transactionally persists order rows plus intent resolution.
- Order-intent reconciliation is not proof that per-fill financial effects, fees, position projections, reserves and PnL are atomically reconciled.
- The barrier's final pre-submit checks and the resolver's intent status must not be assumed to cover financial-effect recovery unless the actual call graph proves this for each caller.

Sources: [execution_barrier.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/execution_barrier.py), [execution_recovery.py](https://github.com/19wolfalone92/Williams/blob/audit/williams-2.1-task-006-android-runtime/execution_recovery.py).

## 4. Writer and sink register — partial, explicitly NOT exhaustive

| Sink / owner candidate | Mutated state / effects | Transaction and identity evidence | Recovery Gate proof | Disposition |
|---|---|---|---|---|
| `Database._migrate_campaign_fill_identity` | `campaign_fills` identity metadata; recovery state | Partial unique index; detects duplicate explicit identities; legacy rows without matching ledger are blocked | Migration can set `RECOVERY_REQUIRED`; all callers' response to this flag not proven | Confirmed, migration-level only |
| `Database.save_campaign_fill` | `exchange_fill_ledger`, `campaign_fills` | Ledger primary key includes exchange/account/symbol/trade ID; end-to-end transaction and payload-conflict behavior not proven here | No proof every call is gated and projection status advances atomically | Ownership unresolved |
| `Database.save_trade` / other trade writers | `trades`, position and PnL fields | REAL columns in schema; exhaustive callers not enumerated | No complete risk-gate matrix | Unverified |
| `Database.save_campaign` / campaign update methods | `campaigns` quantities, average entry, risk/reserve/PnL/state | REAL projection columns; transaction pairing with fill ledger not proven | No complete risk-gate matrix | Unverified |
| `Database.save_campaign_fill` compatibility projection | `campaign_fills` quantity/price/quote/fee/commission | REAL columns and legacy default values; per-fill idempotency across all ingress paths not proven | No complete risk-gate matrix | Unverified |
| `CampaignEngine` fill and reservation methods | campaign quantity, cost basis, add-on count, reserve/risk and lifecycle | Direct methods/callers and transaction boundaries not exhaustively traced | BUY/add-on reachability not proven closed | Unverified |
| `CampaignExecutionService` | order/fill reconciliation and campaign execution handoff | Known independent path; complete callers and writer ownership not proven | Gate bypass closure not proven | Unverified |
| `MultiPositionTrader` | Spot entry/exit, trade rows, order and fill reconciliation | Order route has barrier hook; all financial writes/callers not exhaustively traced | Barrier is not financial applier; every BUY/add-on gate unproven | Unverified |
| `Trader` in `trader.py` | Alternate Spot lifecycle/entry-exit path | Separate class/path is present in prior source inspection; exact all callers/sinks not proven in this task | Must prove delegation or disable entry path | Unverified |
| Android `StandaloneRuntime` / `TradingAuditStore.kt` / related stores | Local position, audit, readiness and execution state | Separate local SQLite/runtime ownership; exact current file paths, all write callers and process-death tests not established in this run | Cross-platform recovery gate and every risk-increasing path not proven | Unverified / blocks acceptance |
| Futures execution sinks | Futures orders, positions and financial projections if reachable | No exhaustive source-to-sink pass and no exact per-sink transaction proof in this task | Cannot claim blocked without direct reachability proof | Unverified / blocks acceptance |

This register intentionally distinguishes confirmed source facts from unknown reachability. It must not be read as the completed writer inventory requested by the task.

## 5. Reachability graph status

The evidence currently supports only this **partial** graph:

```text
Spot entry/exit orchestration
  ├─ Trader (trader.py)
  └─ MultiPositionTrader (portfolio_trader.py)
       ├─ ExecutionBarrier (order mutation admission)
       ├─ CampaignExecutionService
       │    └─ CampaignEngine / campaign and order projections
       └─ Database
            ├─ exchange_fill_ledger (durable fill evidence)
            ├─ campaign_fills (legacy/reporting projection)
            ├─ trades (legacy position/PnL projection)
            ├─ campaigns (campaign/position/risk projection)
            └─ bot_state / execution_intents / execution_events
                 (durable state and order-intent recovery)
```

Missing edges include all indirect callers, callbacks, workers/schedulers, background restart paths, every raw SQL mutation, all Android store callers, all Futures sinks, and all entry/add-on paths. Therefore the graph is **not complete** and no claim of closed reachability is made.

## 6. Recovery Gate matrix

| Path | Required gate proof | Current result |
|---|---|---|
| Spot BUY / initial entry | Recovery complete; no unresolved order/fill/commit; identity conflicts absent; ledger/projections reconciled; reserves and balance consistent; required protection admitted | NOT PROVEN |
| Spot add-on BUY | Same checks plus current campaign and reserved-risk consistency | NOT PROVEN |
| Replacement / retry after unknown exchange result | Durable intent and exact exchange reconciliation; never blind retry | Order barrier/resolver have partial intent controls; complete caller coverage NOT PROVEN |
| Normal SELL / reduction | Must reconcile state and avoid duplicate/oversell | NOT PROVEN end-to-end |
| Emergency risk-reducing SELL | Must remain available through a separately proven safe path without permitting BUY/add-on risk | NOT PROVEN; do not disable blindly |
| Android entry/add-on | Durable local recovery gate before any mutation; process-death and duplicate-worker proof | NOT PROVEN |
| Futures entry/add-on | Independent Futures path audit or proof it is unreachable/disabled | NOT PROVEN |

No assertion is made that the missing paths are safe. Until the reachability matrix is proven, the operational acceptance state must remain fail-closed for risk-increasing execution. This report does not change runtime behavior; it records the lack of proof.

## 7. Financial identity and fee-state requirements

Required durable fee state machine remains:

- `KNOWN`: exchange commission amount and asset validated; any quote equivalent has a documented conversion source and timestamp.
- `UNKNOWN`: fill identity/quantity is known but commission data or conversion is not yet authoritative; quantity/cost effects must not be applied again when the fee resolves.
- `INVALID`: malformed or impossible commission evidence; block financial completion.
- `CONFLICTED`: same canonical identity arrives with a different immutable payload; preserve both evidence records or conflict evidence and block risk increase.

Canonical identity must include exchange, account, environment, market/symbol and trade ID (or an explicitly documented exchange-scoped equivalent). A same-identity different-payload event must not be treated as a harmless duplicate. Current ledger key includes exchange/account/symbol/trade ID but has no explicit environment or market-type column in the observed schema; the uniqueness contract for Testnet/Mainnet and Spot/Futures therefore requires further proof. No new fee-state implementation was introduced.

## 8. Required tests not demonstrated on this task SHA

The three general CI workflows on the pre-report SHA succeeded, but the following acceptance evidence was not obtained:

- direct invocation of each unmigrated writer while financial recovery is incomplete, verifying actual DB state and no exchange mutation;
- BUY and add-on blocked under unresolved financial effects;
- cross-source duplicate fill delivery and conflicting same-identity payload across independent process/restart;
- SQLite fault injection at each write boundary and rollback/read-after-restart assertions;
- commit ambiguity and process death between ledger insert, projection writes and completion state;
- independent OS processes racing the same fill;
- UNKNOWN fee later resolved without reapplying quantity/cost basis;
- position/reserve mismatch blocks all risk-increasing paths;
- emergency reduction remains available through a separately verified safe route;
- Android instrumentation/process-death and local DB recovery;
- Futures bypass tests or evidence the Futures mutation paths are unreachable/disabled;
- exact-final-SHA Python, Android and Campaign CI results after this report commit.

No tests were added because no specific bypass was proven sufficiently isolated to safely patch without first establishing its callers and alternate paths. Adding a superficial test that only checks a return value would not satisfy the task's DB-state/no-mutation criteria.

## 9. Next safe sequence

1. Enumerate every SQL write to financial tables and every call site on the branch, including dynamic dispatch, scheduler/worker registration, callbacks, recovery and startup.
2. Build an executable call graph rooted at all process entry points; verify every risk-increasing sink's reachability.
3. For each writer, record the exact DB connection/transaction and durable idempotency key.
4. Add tests around the actual reachable call paths, asserting persisted DB rows and fake-exchange mutation counts.
5. Prove emergency reduction separately; do not close all execution merely to avoid BUY bypass.
6. Audit Android and Futures as independent execution domains, or provide code-level proof that those mutation routes are disabled and unreachable.
7. Only after all conflicting writers are migrated or safely gated may the canonical financial applier be designed and implemented in a later task.

## 10. Final status

**TASK 006.13 — BLOCKED — NOT ACCEPTED**

Blocking reasons: incomplete source-to-sink reachability graph; no complete writer/projection ownership map; Recovery Gate not proven at every Spot/Android/Futures risk-increasing sink; no required financial concurrency/crash/fee-resolution tests; and no CI evidence yet for the post-report final SHA. PR #46 remains Draft and unmerged; `main` is not changed.
