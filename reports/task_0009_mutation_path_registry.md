# TASK 000.9.4 — Python and Android Mutation-Path Registry
Date: 2026-10-12 UTC

## Baseline and scope
- Inspected source SHA: `991b9d9e018af562af21749a58509d640dfbf87b`.
- Files inspected: `execution_barrier.py`, `binance_client.py`, `execution_recovery.py`, `app/src/main/java/com/williamsbot/ExecutionGate.kt`, `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`, and `.github/workflows/campaign-ci.yml`.
- This is a partial interprocedural registry from direct call-site inspection. A complete whole-repository call graph/reachability proof has not been established.

## Python paths
| Source / call chain | Mutation sink | Durable intent / idempotency | Ambiguity / reconciliation | Evidence and gap |
|---|---|---|---|---|
| Strategy/campaign caller → `ExecutionBarrier.execute(intent, submit)` → submit callback | Callback-provided exchange operation | Barrier requires durable intent API and atomically claims intent; persists PENDING before callback; stable `client_order_id` is part of `OrderIntent` | Submit exceptions and invalid responses become AMBIGUOUS/RECONCILE_REQUIRED; no retry in barrier | `execution_barrier.py`; direct caller enumeration and proof all submit callbacks use barrier remain incomplete |
| `BinanceSpotClient._request` via `new_order`/cancel/order-list wrappers | Signed REST POST/DELETE for order, orderList, openOrders mutation endpoints | Client itself does not establish durable intent; relies on caller boundary | Transport exceptions redact details; POST/DELETE not blindly retried for transport/5xx; signed timestamp resync has a narrow retry condition | `binance_client.py`; must enumerate all wrapper callers and prove no direct client bypass |
| `ExecutionRecoveryWorker.run_once` → exchange read adapter | No mutation sink (read-only lookup) | Reads durable PENDING/AMBIGUOUS intent and uses leases/backoff | Missing/mismatched/unknown lookup remains unresolved; worker never submits, retries, or cancels | `execution_recovery.py`, `tests/test_execution_recovery_worker.py`; adapter call graph still needs full inventory |
| Other Python modules and alternate clients | **Not fully enumerated** | Unknown | Unknown | Full repository-wide sink-to-source enumeration remains a blocker |

## Android paths — critical finding
`ExecutionGate.kt` is an in-memory symbol reservation set; its own comment says network I/O must not run while holding the gate. It is not a durable execution-intent barrier and does not by itself prove all mutation paths pass through a single execution door.

`StandaloneRuntime.kt` contains the private signed network helpers `signedPost`, `signedDelete`, and `signedCancelReplace`; `signedCancelReplace` delegates to signed POST `/api/v3/order/cancelReplace`. The file contains multiple direct call sites for:
- POST `/api/v3/order` — conditional campaign entry, campaign stop/protection, campaign exit, emergency exit, ordinary BUY/SELL and emergency protection fallback;
- POST `/api/v3/orderList/oco` — OCO protection creation;
- POST `/api/v3/order/cancelReplace` — protective-stop replacement;
- DELETE `/api/v3/order` — kill-switch pending-entry cancel, campaign protection cancel, partial-entry cancel, and managed-order cancellation;
- DELETE `/api/v3/orderList` — OCO cancel/replacement flows.
Some campaign calls are wrapped in `withCampaignMutation`; other observed paths call signed helpers directly. The complete reachable caller graph, durable intent semantics for every path, and proof of a universal barrier are **not verified**.

| Android path family | Current protection evidence | Unresolved gap |
|---|---|---|
| Campaign entry / stop / trailing replacement / campaign exit | Some use `withCampaignMutation`; client IDs and some reconciliation calls are present | Prove durable intent is committed before every mutation; test timeout-after-acceptance and post-submit persistence failure |
| Kill switch and pending-entry cancellation | Direct `signedDelete` call from `kill()` | No universal barrier proof; cancellation outcome and persistence/recovery path require tests |
| Ordinary BUY and emergency SELL fallback | Direct `signedPost` call sites observed | No universal durable intent/barrier proof; emergency retry/mutation count must be fault-injected |
| OCO protection create/cancel | Direct `signedPost` / `signedDelete` paths | OCO list and leg identities must be reconciled; no complete mutation boundary proof |
| Manual `sell(symbol)` | Direct managed OCO delete then direct market SELL; errors set reconciliation-required state | Two-step partial-success risk; explicit durable intent and recovery test needed |
| Cancel-replace | `signedCancelReplace` sends POST `/api/v3/order/cancelReplace` | Partial success must be reconciled; prove no caller bypass and durable pre-submit state |

## Required closure work
1. Generate a repository-wide mutation-sink inventory, including order, orderList/OCO, cancelReplace, openOrders cancellation, wrappers, alternate clients, workers, kill-switch and emergency paths.
2. For each call site, trace all reachable callers to the actual exchange sink; static search alone is insufficient.
3. Route every Android mutation through one durable, serialized Execution Door with stable client IDs and pre-mutation persistence, or explicitly justify and verify an equivalent invariant.
4. Add mock HTTP/fault-injection tests proving mutation counts, ambiguity handling, reconciliation, and entry blocking for every family.
5. Re-run the complete configured Python and Android suites after any code changes.

## Conclusion
**INCOMPLETE — P0 BLOCKER.** Current evidence identifies direct Android mutation paths that are not proven to share a universal durable barrier. Do not mark the registry complete and do not authorize live trading based on the existence of `ExecutionBarrier` or `ExecutionGate` alone.
