# WILLIAMS BOT 2.1 — TASK 001.2
## Architecture Ownership Matrix

**Result: BLOCKED — SINGLE-OWNER AND CROSS-PLATFORM EXCLUSIVITY NOT PROVEN**  
**Repository:** `19wolfalone92/Williams`  
**Branch / PR:** `audit/williams-2.1-task-001-architecture` / #47 (OPEN, DRAFT, NOT MERGED)  
**Audit ref:** `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc`  
**Base/main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Mode:** Remote source inspection; no local checkout or task-specific test execution in this session.

## 1. Decision rule

“Owner” below means the only component authorized to commit the authoritative transition for that object. A class that reads, calculates, caches, or projects the object is not thereby its owner. A proposed owner is not accepted until every writer and runtime route is closed. Exchange facts remain authoritative for live Binance orders, fills and balances.

## 2. Ownership matrix

| Object | Candidate canonical owner / boundary | Observed persistence or state holders | Ownership finding | Required closure evidence |
|---|---|---|---|---|
| Trading runtime/control state | One selected runtime authority; Python ControlState or Android NativeEngine, not both for the same account | `server.py` / `ControlState`; Android `StandaloneRuntime.kt`, foreground service and local state | **UNRESOLVED**: backend execution path and Android standalone path both exist in the branch; deployed mode and fencing not proven | Release configuration, startup wiring, device/backend-offline test, proof only one executor can mutate account |
| Order FSM | `OrderStateMachine` for Python execution; Android order state must be an exchange projection or separately fenced executor | `order_state_machine.py`; `execution_barrier.py`; Android `TradingAuditStore.kt` order rows and runtime order status | **PARTIAL**: Python FSM is imported by barrier; cross-platform ownership and transition parity not proven | Full caller inventory, transition table, duplicate/out-of-order/restart tests, shared account authority decision |
| PendingSignal / SignalSpec | Strategy/CampaignEngine may create and advance a signal, but cannot assert an exchange order/fill | `strategy.py`, `digital_williams_core.py`, `williams_signals.py`, `campaign_model.py`, `campaign_engine.py`; Android `StandaloneRuntime.kt` signal/pending-entry model | **UNRESOLVED**: Python campaign path and Android signal path are separate implementations; parity not proven | Exact signal schema/version, deterministic fixtures, no-look-ahead tests, runtime reachability map |
| Order Intent | `ExecutionBarrier` admission boundary plus durable intent store | Python `OrderIntent`, `execution_barrier.py`, `db.py.execution_intents`; Android `signedRequest` / `TradingAuditStore.beginMutationIntent` | **PARTIAL**: Python and Android each have a durable intent mechanism; no single shared writer is demonstrated | Mutation-by-mutation barrier proof; durable-before-network fault injection; stable client IDs; unresolved intent blocks entries |
| Actual exchange order | Binance Spot exchange is authority; one executor may request mutations | Python `binance_client.py`; Android `StandaloneRuntime.kt` signed REST transport | Exchange authority is clear; **executor exclusivity unresolved** | Exhaustive POST/DELETE inventory, only one active executor, account-level fencing/lease if more than one process exists |
| Campaign | One CampaignEngine/service per canonical executor | Python `campaign_engine.py`, `campaign_execution.py`, `portfolio_trader.py`, `db.py`; Android campaign/position functions in `StandaloneRuntime.kt` | **NOT SINGLE-OWNER PROVEN**: multiple Python recovery/projection writers plus separate Android campaign state | All writers/callers including legacy paths; one transaction boundary; restart/replay and race tests |
| Position / inventory projection | Exchange balances + executed fills are truth; one projection applier per executor | Python trade/position state in `db.py`, `campaign_execution.py`, `portfolio_trader.py`, `trader.py`; Android `PositionState`, `TradingAuditStore.kt`, `TradeJournal.kt` | **NOT SINGLE-OWNER PROVEN**: both platform code paths maintain position-like state | Explicit authoritative runtime; compare projection with exchange snapshot; prove no concurrent writer and fail closed on mismatch |
| Fill observation | Exchange trade/fill identity, persisted once | Python execution accumulator / DB fill records; Android `ExecutionAccumulator.kt` / `TradingAuditStore.executions` | Deduplication primitives exist, but complete all-consumer exactly-once semantics **NOT PROVEN** | Exchange trade ID uniqueness, atomic event + financial-effect commit, replay and crash tests across every projection |
| Fees / PnL / financial ledger | One transactional financial-effect applier, not currently accepted as proven | Python `db.py`, `campaign_execution.py`, `portfolio_trader.py`, `trader.py`; Android `TradeJournal.kt`, `TradingAuditStore.kt` | **BLOCKED**: direct SQL/generic DB writers and Android Double/REAL-based stores coexist | Exhaustive writer closure, Decimal/scale contract, atomic fee/quantity/PnL/Campaign commit, duplicate/restart/fault tests |
| Risk Reservation | One reservation ledger coupled atomically to campaign/order intent | `CampaignEngine`, `CampaignExecutionService`, risk/preflight logic, `db.py` | **PARTIAL**: reservation concepts and writes exist; single-writer and atomic release/recovery not established | Unique reservation identity, transaction boundary, concurrent capacity test, rollback/UNKNOWN recovery |
| Kill Switch / Safe Mode | One durable global control latch consumed by every entry path | Python `server.py` / control state and entry preflight; Android runtime/gates | **UNRESOLVED**: both runtime families contain control/guard logic; cross-path latch semantics unproven | Enumerate every entry endpoint and startup path; durable latch-before-action; cannot clear on restart or error without explicit policy |
| Market Context | One injected versioned ContextCache producer for each runtime; consumers read immutable snapshots | `mtf_context_service.py`, `market_context.py`, `Trader`, `ExecutionBarrier`; Android has its own scanner/context calculations | **PARTIAL**: Python shared-cache injection is described in audit/tests; Android parity and stale-data recovery not proven | Identity/version/freshness tests on exact build, closed-bar timestamps, WS gap → REST resync → entry block |

## 3. Evidence anchors (remote source blobs at audit ref)

- `execution_barrier.py` — blob `c0e7cf4eea424d037b273e6150fa2d3d0ea93b40`: `OrderIntent`, `ExecutionBarrier._persist`, `_validate`, serialized execution boundary.
- `binance_client.py` — blob `df3394990addd03dff4225ff8e62111412191f7e`: Spot REST client, POST/DELETE unknown-outcome behavior and exchange adapter.
- `campaign_execution.py` — blob `699b28b1de25f90465336060ab6162537ef82668`: campaign entry/exit/protection service and recovery entry points.
- `db.py` — blob `129645290a074d7958ac75a4128de455754db13e`: SQLite schema/transaction helper and multiple persistence APIs.
- `StandaloneRuntime.kt` — blob `d462682601c86fe5145646d16d417435cce032d4`: Android runtime, local campaign/position model and signed REST transport.
- `TradingAuditStore.kt` — blob `5ba02be31c5dbc31657c46ec579d7906da59d3dd`: Android SQLite orders/executions/trades and mutation journal.
- `docs/WILLIAMS_BOT_2_1_TASK_001_15_COMPLETE_WRITER_CLOSURE_FINANCIAL_OWNERSHIP_ATOMICITY_PROOF.md` — blob `b922b3231f095fb78ee08960b7ad42c9513546ad`: prior static inventory also leaves writer closure and atomicity blocked.

Blob SHA identifies file contents; it is not a commit SHA. Source reads were remote; no local `git status`, runtime trace, or task-specific test command was executed.

## 4. Required ownership decisions

1. Choose and document a single execution authority for a Binance account. “Backend optional” and “Android autonomous” cannot coexist with an unfenced backend order writer.
2. Define authoritative owners for Campaign, Position projection, fill application, fees/PnL, risk reservation and control latches.
3. Demote all non-owner stores to explicitly derived projections or read-only caches.
4. Use exchange trade/order identities as idempotency keys; make fill observation and all dependent financial effects atomic or recoverably journaled.
5. Reject release acceptance until TASK 003, 006, 007 and 008 provide exact-SHA mutation, device, ledger and Testnet evidence.

**Final status: BLOCKED.** This matrix documents observed writer candidates and unresolved authority conflicts; it does not claim that single-writer invariants have been proven.
