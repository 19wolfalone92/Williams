# TASK 000.7.4 — Order Ambiguity and Recovery
Date: 2026-10-12 UTC

## Confirmed design in source
ExecutionBarrier requires durable intent persistence and unresolved-intent inspection before submit. It claims an intent before submission, persists PENDING before calling the transport, does not retry a failed mutation, marks ambiguous outcomes where persistence succeeds, and sets position/campaign reconciliation state. A response without authoritative status is treated as ambiguous/reconciliation-required. A post-submit persistence failure does not trigger a second submit.

BinanceSpotClient documents GET-only transient retries; POST/DELETE transport failures are not blindly retried. The signed -1021 timestamp rejection path is treated separately as a server rejection eligible for one time-sync retry. HTTP 409 cancelReplace is treated as partially successful/ambiguous.

## Required nine-scenario matrix
| Scenario | Current evidence | Status |
|---|---|---|
| Request sent, response lost | mock timeout test exists | OPEN — FIX NOT VERIFIED |
| Exchange accepted order, client timeout | no full fake-exchange confirmation scenario proven | INCOMPLETE — EVIDENCE MISSING |
| Local state persistence fails | persistence-failure tests exist | OPEN — FIX NOT VERIFIED |
| Process dies before result commit | restart/SQLite tests partial | INCOMPLETE — EVIDENCE MISSING |
| Partial fill | no complete cumulative-fill proof in this task | INCOMPLETE — EVIDENCE MISSING |
| Unknown status after recovery | code path marks reconciliation required; full simulation missing | INCOMPLETE — EVIDENCE MISSING |
| Duplicate fill event | not proven end-to-end | INCOMPLETE — EVIDENCE MISSING |
| Local order not exchange-confirmed | not proven end-to-end | INCOMPLETE — EVIDENCE MISSING |
| Exchange order missing locally | not proven end-to-end | INCOMPLETE — EVIDENCE MISSING |

No authenticated Binance calls or real orders were used. Exchange-authoritative state and reconciliation behavior require a mock-exchange fault-injection run on the exact current SHA.

Status: **INCOMPLETE — EVIDENCE MISSING**.
