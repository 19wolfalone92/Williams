# WILLIAMS BOT 2.1 — TASK 003: Execution Safety

**Status:** IMPLEMENTATION DRAFT — final acceptance requires green CI for the exact PR head and review of all mutation routes.  
**Safety scope:** Binance Spot/Testnet code only. This change does not enable Mainnet or initiate any trade.  
**Branch:** `audit/williams-2.1-task-003-execution-safety`  
**Base:** `main` (must remain unchanged until explicit review/merge).

## Mandatory execution contract

A mutating request may reach the exchange only after its immutable logical identity and request parameters have been written to the durable intent store and read back successfully. The pre-submit state is `PENDING`; immediately before network submission it is `SUBMITTING`. A missing database, missing writer, SQL error, unique-identity collision, failed commit, or failed read-back rejects the request before the network call.

An exchange response is also mandatory state, not telemetry. The response JSON, exchange order/list ID, status, executed quantity and cumulative quote quantity are committed and read back before the caller is told that a mutation has a known result. If this record cannot be confirmed, the intent is marked ambiguous where possible, a durable reconciliation latch is set, and execution is not retried.

A transport timeout, unexpected HTTP failure, malformed response, non-terminal cancellation, partial/filled cancel race, or non-authoritative query is **UNKNOWN**, not rejection or success. The same stable identity is retained while recovery queries the exchange. No new exposure is permitted while unresolved intent or reconciliation state remains.

Optional REST/event telemetry is best effort. It is not used as proof of submission or fill, and a telemetry-write failure alone does not prevent a safe, already-admitted protective/reducing operation.

## Mutation-path inventory

| Path | Required boundary | Durable identity / checks |
|---|---|---|
| Canonical campaign entry | `campaign_execution.py` → `ExecutionBarrier` | Stable client order ID and signal identity; versioned context and signal-age checks; directional admission; mandatory risk and campaign ownership callback; risk/capital reservation; global/symbol/campaign reconcile gates |
| Campaign add-on | `campaign_execution.py` → `ExecutionBarrier` | Stable signal/campaign identity; pending add-on ownership; expiry/staleness; current position; live equity/risk reservation; position/order capacity |
| Campaign hard stop / stop replacement | `campaign_execution.py` → `ExecutionBarrier` | Stable stop identity; confirmed campaign quantity; exact old-order ownership/status; no duplicate active protective stop; old/new cancelReplace states independently checked |
| Campaign market exit | `campaign_execution.py` → `ExecutionBarrier` | Stable exit identity; current campaign owner and confirmed position quantity; quantity capped to that campaign; no blind retry; actual exchange fills remain authoritative |
| Campaign order cancellation | `campaign_execution.py` → `ExecutionBarrier` | Stable cancel intent; target order ID/client ID and campaign owner verified; cancel/fill conflict triggers reconciliation |
| Portfolio/legacy BUY | `portfolio_trader.py` / `trader.py` → `ExecutionBarrier` | Stable signal-based ID; pending claim; current context versions, equity/drawdown/risk/capital and balance rechecked before send |
| Portfolio OCO creation/cancellation | `portfolio_trader.py` → `ExecutionBarrier` | Stable list identity; managed trade and inventory ownership; protective prices and target list identity; all child order states checked after cancellation |
| Portfolio/manual sell | `portfolio_trader.py` / `trader.py` → `ExecutionBarrier` | Stable logical exit identity; managed trade and confirmed free inventory checked; no random-ID retry after ambiguity |
| Android native order/exit/stop/OCO/cancelReplace | `StandaloneRuntime.kt` `signedRequest` | Low-level fence classifies every supported signed write; unknown order-like write endpoints are rejected before network; SQLite write-ahead intent and response result/read-back required; stable `clientOrderId`/`listClientOrderId`; kill/reconcile and unresolved-intent gates |
| Android REST audit log | `TradingAuditStore.kt` diagnostics | Optional only; not an execution authority and not a prerequisite for an otherwise safe mutation |

Low-level exchange client methods in `binance_client.py` are transport adapters, not an alternate strategy execution boundary. All observed Python strategy order operations are intended to enter through `ExecutionBarrier`; the Android runtime now has its own local durable fence because its network adapter is independent of the Python backend.

## State and recovery rules

- `PENDING` means a durable write-ahead intent exists but submission is not yet proved.
- `SUBMITTING` means the network request may be in flight; process death or lost response requires exchange reconciliation.
- `SUBMITTED` means the exchange response and its mandatory durable result record were verified; it is not synonymous with `FILLED`.
- `AMBIGUOUS` / `RECONCILE_REQUIRED` block new exposure. They cannot be cleared by telemetry or a generic retry.
- `PARTIALLY_FILLED` retains exact observed quantity. Any cancel/fill race or positive fills on a supposed zero-fill cancel path require position/fill accounting.
- A protective OCO is not resolved from a list-level acknowledgement alone. Child orders must be queried and inspected individually if the response lacks per-leg reports.
- Definitive absence is accepted only from an authoritative, exact-identity exchange query. Transport errors, 5xx responses, rate limits and malformed responses do not prove absence.
- Reconciliation that finds a fill does not silently mark the campaign clean. Where the execution result has not yet been applied to campaign/position accounting, the intent remains unresolved.

## Regression evidence expected on the exact PR head

The Task 003 Python tests include fault-injection for mandatory intent-write/commit failures, missing persistence support, parallel duplicate entries, response-loss timeout, partial-fill state preservation, cancel/fill races, stale contexts, `RECONCILE_REQUIRED`, telemetry outage on a safe exit, missing/corrupt storage, and mandatory exchange-result write failure. Source-contract checks cover the native Android write-ahead boundary and separate telemetry path.

**Acceptance is not complete until CI has passed for the exact final branch SHA and the full mutation-route review is confirmed.** No Mainnet execution is part of this task.
