# TASK 000.5.3 — Mutation-path registry

## Scope and evidence reviewed
Source review includes `execution_barrier.py`, `binance_client.py`, `execution_recovery.py`, plus prior TASK 000.4 evidence. This is a partial source audit, not a complete whole-repository interprocedural call graph. The GitHub API integration does not provide a full local clone, and indexed code search is not sufficient to prove absence of other sinks.

| Path / owner | Known safety contract | Evidence / status |
|---|---|---|
| Order submit through `ExecutionBarrier.execute` | Persist PENDING before submit; serialized lock; unresolved intent blocks; submit exception becomes AMBIGUOUS; RECONCILE_REQUIRED; no retry | Source reviewed and exception redaction patched; runtime test execution pending |
| Binance REST transport `BinanceSpotClient._request` | GET-only transport retries; POST/DELETE ambiguity must reconcile; sanitized errors | Source patched; mock regression tests committed; execution pending |
| HTTP 409 cancelReplace | Treat partial mutation as ambiguous; do not blind retry; expose only safe code | Source reviewed and body redacted; test execution pending |
| HTTP 418/429/5xx mutation handling | No mutation retry; preserve conservative unknown state | Source reviewed; dedicated end-to-end fault matrix incomplete |
| Execution recovery worker | Read-only exchange lookups in inspected source; reconcile exchange evidence before retry | Partial source review from prior report; all call chains not proven |
| SQLite persistence/state updates | Must fail closed if PENDING/AMBIGUOUS persistence or reconciliation-state storage fails | Existing persistence-failure tests and barrier source reviewed; isolated crash/restore drill outstanding |
| Campaign/position state transitions | No new entry while unresolved; exchange state remains authoritative | Partial coverage only; full campaign lifecycle and all writers not inventoried |
| Protective orders/OCO/cancel paths | Stable client IDs and reconciliation required on uncertain outcome | Partial source evidence; complete caller-chain and idempotency proof outstanding |
| Android execution boundary | Must prevent direct bypass of native Execution Door | Not proven by this partial Python/API review; Android source-to-sink trace required |
| Background tasks, exception handlers, wrappers | Must not retry unknown mutations or leak exception/response data | Not exhaustively inventoried; open blocker |

## Required missing evidence
A complete registry still needs every mutation sink and caller chain across Python and Android, condition/guard, stable idempotency key, persistence contract, timeout behavior, restart behavior, recovery evidence, and a test reference. Search results or function names alone do not satisfy this criterion.

## Status
**INCOMPLETE — EVIDENCE MISSING**. Do not interpret this partial registry as proof that every path passes through Execution Door.