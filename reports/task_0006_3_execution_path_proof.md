# TASK 000.6.3 — Execution-path proof

## Evidence scope
Reviewed the current `execution_barrier.py`, `binance_client.py`, existing recovery tests and prior mutation registry. This is not a complete repository-wide call graph and does not prove absence of all direct bypasses in Python and Android.

| Path | Known chain / guard | Idempotency and unknown-result handling | Proof status |
|---|---|---|---|
| Submit | caller → `ExecutionBarrier.execute` → persisted PENDING → validation/pre-submit checks → submit callback → `BinanceSpotClient._request` | durable intent claim; UNKNOWN/AMBIGUOUS on exception; reconciliation required; unresolved intents block further submits | PARTIAL; exact runtime tests pending |
| REST transport retry | `BinanceSpotClient._request` → HTTP transport | GET may retry; mutating POST/DELETE must not be blindly retried after ambiguous transport result | PARTIAL; client tests committed, not rerun at final SHA |
| Cancel/cancelReplace | wrapper → client mutation method → exchange REST | uncertain result requires exchange lookup/reconciliation; 409 partial result must not be treated as clean rejection | INCOMPLETE; full caller-chain proof missing |
| Recovery | recovery worker → read-only exchange lookup → durable state update | exchange evidence must dominate local assumptions; unresolved order blocks new entry | INCOMPLETE; all recovery callers not traced |
| Partial fills/events | exchange event/order response → order/campaign persistence | event deduplication and cumulative executed quantity must be idempotent | INCOMPLETE; no full duplicate-event/partial-fill fault matrix |
| Campaign/position writers | campaign/trader/risk code → DB state/position update | every writer must be identified and have one owner/contract | INCOMPLETE; whole-repo write inventory not completed |
| Protective/OCO orders | campaign/protection code → barrier/client → exchange | stable client/list IDs and reconciliation on unknown result | INCOMPLETE; every protection caller not traced |
| Android | UI/background worker → native execution gate/door → signed REST call | direct bypass must be excluded by source-to-sink proof | INCOMPLETE; Kotlin call chains and signedPost/signedDelete sinks not exhaustively inventoried |
| Background tasks/API wrappers | scheduler/handler → trading/recovery wrappers → state mutation | exceptions must not trigger duplicate mutations or leak payloads | INCOMPLETE; complete graph not established |

## Required proof to close
Build a complete static call graph from every order mutation and persistent state write, identify the single owner and guard, then add runtime tests that instrument mutation sinks and assert every call has the expected boundary. Search for direct `requests`, `session.request`, signed Android HTTP calls, SQLite writers, background schedulers, and indirect callbacks. Function names or presence of a gate call are not proof of enforced containment.

## Status
**INCOMPLETE — EVIDENCE MISSING**.