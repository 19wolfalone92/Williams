# WILLIAMS BOT 2.1 — TASK 001.2
## Cross-Module Call Graph and State Transitions

**Result: BLOCKED — CALL GRAPH IS PARTIAL; DYNAMIC AND CROSS-PLATFORM REACHABILITY NOT CLOSED**  
**Source ref:** `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc`  
**Base/main:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**PR #47:** OPEN / DRAFT / NOT MERGED  
**Method:** remote exact-ref source inspection and existing report review; not an executable local checkout.

## 1. Evidence labels

- **OBSERVED** — file/function or persisted object is present in the fetched source.
- **DESCRIBED** — prior branch audit describes the route, but this task did not independently execute it.
- **PARTIAL** — only a portion of callers or runtime wiring is closed.
- **UNRESOLVED** — the full edge, authority, or error semantics cannot be established from the evidence collected.
- No label in this document means that the code path passed a live exchange test.

## 2. Python campaign execution path

~~~text
Binance REST / market stream
  ├─ market history/candles → market frames and persisted market history
  └─ account/order/order-list/trade reads → reconciliation inputs

market_context.py / ContextCache
  ↑ populated by mtf_context_service.py / MultiTimeframeContextService
  └─ immutable/versioned TFMarketContext snapshots
       ├─ Trader / strategy consumers
       └─ ExecutionBarrier snapshot and freshness validation

strategy.py / Trader.process()
  → digital_williams_core.py / williams_signals.py (signal calculation path)
  → SignalSpec / hypothesis / context-version contract
  → CampaignEngine and PendingSignal (campaign model)
  → MultiPositionTrader.execute_campaign(...)
  → CampaignExecutionService.arm_initial_entry(...) or arm_add_on(...)
  → durable campaign / reservation / intent persistence in db.py
  → ExecutionBarrier.execute(OrderIntent, adapter callback)
  → BinanceSpotClient in binance_client.py
  → exchange order acknowledgement / order query / trade query
  → durable order/fill observations and reconciliation
  → campaign / position / trade projections
  → protection and campaign exit through execution service + barrier
~~~

### Edge contracts

| Edge | Producer → consumer | Contract visible in source | Failure requirement | Evidence / status |
|---|---|---|---|---|
| Market data → context | Binance adapters → `mtf_context_service.py` / `ContextCache` | Symbol, interval, candle close timestamp, versioned context | Unknown interval/time, stale context or data gap must block new entry | `mtf_context_service.py` branch blob SHA `ac38e50ec7ea125047ce7e39d8de3c0ee711aada`; exact live gap-recovery proof unresolved |
| Context → strategy | `ContextCache` → `Trader` / strategy | Context snapshot and version | Missing/inconsistent context must not be interpreted as permission | Existing audit describes shared-cache injection; integration regression exists, but runtime trace not executed here |
| Strategy → pending signal | `strategy.py`, Williams core → `SignalSpec` / `PendingSignal` | Candidate/signal identity, side, invalidation, context/hypothesis versions | Signal is not an order; incomplete or stale proof blocks | Observed modules; full source-to-signal parity is TASK 004 |
| Signal → campaign | `MultiPositionTrader.execute_campaign` → `CampaignEngine` / `CampaignExecutionService` | Campaign ID, signal ID, symbol, risk proposal, intent purpose | Unknown symbol state or absent campaign/signal identity blocks | Described in `docs/WILLIAMS_BOT_2_1_TASK_001_ARCHITECTURE_AUDIT.md`; source path partially reviewed |
| Campaign → OrderIntent | `CampaignExecutionService` → `OrderIntent` | Side/type, context versions, purpose, campaign/signal IDs, quantity/notional, client order ID | Durable intent must exist before network I/O | `campaign_execution.py` blob `699b28b1de25f90465336060ab6162537ef82668`; `execution_barrier.py` blob `c0e7cf4eea424d037b273e6150fa2d3d0ea93b40` |
| Intent → barrier | caller → `ExecutionBarrier.execute` | Valid intent, shared context lock, state/preflight, adapter callback | Fail closed on missing store/context/state; no blind mutation retry | Barrier source observed; complete caller closure remains TASK 003 |
| Barrier → Binance | `ExecutionBarrier` → `BinanceSpotClient` | Approved Spot order route; persisted intent; stable client ID | POST/DELETE ambiguous outcome becomes UNKNOWN/reconcile, not blind resend | `binance_client.py` blob `df3394990addd03dff4225ff8e62111412191f7e`; full endpoint inventory still required |
| Binance → fill | Exchange order/trade facts → accumulator/reconciler | Exchange order ID, client order ID, trade/fill identity, executed quantity, quote, fee asset/amount | Ack is not fill; repeated events must not duplicate financial effects | Python and Android accumulators/stores exist; full exactly-once consumer chain not proven |
| Fill → Campaign/Position | Fill consumer → Campaign/Position projection | Executed quantity and fill identity | Only confirmed exchange executions may create/alter live exposure | Multiple Python writers and independent Android state observed; owner conflict remains |
| Fill → financial ledger | fill record → trade/fee/PnL projection | Quantity, quote, fees, net/gross PnL, stable trade identity | Financial effects must be atomic or journaled for exactly-once replay | `db.py`, `campaign_execution.py`, `portfolio_trader.py`, `trader.py`; TASK 001.15 leaves closure blocked |
| Restart → recovery | startup/recovery → exchange reconciliation → local projections | Durable unresolved intents, open orders, order history, trades, balances | Reconcile before new entry; unresolved state blocks | Recovery functions exist in campaign/portfolio/trader and Android; two-process/fault-injection evidence absent |

## 3. Python legacy / alternate routes

- `Trader` still contains legacy single-symbol and recovery functions. Prior architecture report states the score-only legacy entry route is rejected by the barrier when canonical context is absent; this is not proof that every deployed caller is unreachable.
- `portfolio_trader.py` includes exit/protection/manual/legacy execution methods. The prior audit says active order writes were routed through a barrier wrapper. A complete AST-based caller graph and negative tests for each adapter mutation remain required.
- `campaign_execution.py` has its own startup unresolved-intent reconciliation and pending-entry reconciliation. `portfolio_trader.py` has pending-entry and trade reconciliation; `trader.py` has legacy recovery. Their concurrency and ownership boundaries are not proven to be mutually exclusive.
- `db.py` exposes both high-level methods and direct SQL/persistence sites. An intent transaction is not proof that Campaign, Position, Fill, fees and PnL are one atomic financial transaction.

## 4. Android standalone path

~~~text
TradingForegroundService / StandaloneRuntime worker
  → REST + stream/history warmup and candidate scan
  → Williams signal / PendingEntry
  → persisted local state + ExecutionGate reservation
  → signedPost / signedDelete
  → signedRequest (central mutation policy)
  → authorizeExchangeMutation
  → TradingAuditStore.beginMutationIntent (SQLite, before network)
  → performSignedRequest
  → persist exchange response or UNKNOWN
  → recovery / reconciliation before another BUY
~~~

**Source evidence:** `StandaloneRuntime.kt` blob `d462682601c86fe5145646d16d417435cce032d4`; `TradingAuditStore.kt` blob `5ba02be31c5dbc31657c46ec579d7906da59d3dd`.

The local UI is not the exchange authority. Android keeps its own signal, campaign, position, order, execution and trade representations. This is a distinct mutation and financial projection path from Python, not proven to be a read-only dashboard. The actual installed APK, active build flavor, device execution path and account-level exclusivity are not evidenced in this task.

## 5. Cross-platform conflict boundary

The intended high-level sequence “Market Data → Strategy → PendingSignal → Risk → Campaign → Intent → Barrier → Binance → Fill → Position → Ledger → Recovery” is useful as an audit spine, but **does not describe one proven runtime**. The repository contains:
1. a Python backend/campaign execution path; and
2. an Android standalone runtime with its own order transport and persistent execution journal.

Until one execution authority is chosen and enforced, Python and Android must be treated as potentially competing executors/writers for the same account. Separate SQLite databases cannot provide a shared atomic transaction or shared exactly-once guarantee.

## 6. Graph closure requirements

1. Enumerate every call to POST/DELETE and every wrapper capable of order, OCO/order-list, cancel-replace, protection, emergency and manual mutations.
2. Trace every caller to one gate and prove all other routes fail closed.
3. Enumerate all fill consumers and all downstream Campaign/Position/fee/PnL/risk writes.
4. Prove restart/replay idempotency for every writer, not only event insertion.
5. Add runtime trace IDs linking `signal_id → campaign_id → intent_id → client_order_id → exchange order_id → trade_id/fill identity → ledger effect`.
6. Run failure injection on the exact source SHA and retain command, exit code, logs and environment metadata.

**Final status: BLOCKED.** This is a partially evidenced static call graph, not proof of complete runtime reachability or cross-platform ownership.
