# TASK 001.2 — Mutation path registry
**Status: PARTIAL — P0 OPEN**

## Source-derived initial registry
The following paths were identified in the existing TASK 001.1 report and spot-checked in `StandaloneRuntime.kt`. Line numbers in earlier reports are baseline line references and may drift after edits.

| Path ID | Operation family | Caller/function evidence | Central sink | Reconciliation | Status |
|---|---|---|---|---|---|
| AND-MUT-01 | Campaign entry / MARKET or LIMIT order | `submitCampaignEntry` | `signedPost → signedRequest` | Client-order query/history not wired to durable intents | PARTIAL |
| AND-MUT-02 | Campaign protection creation | campaign mutation / protection caller | signed sink | Not operation-complete | PARTIAL |
| AND-MUT-03 | Protection trailing cancel-replace | `signedCancelReplace` caller in trailing logic | signed cancel-replace path | Both legs not durably reconciled | PARTIAL |
| AND-MUT-04 | Protection cancellation | `campaignExitMarket` / protection paths | `signedDelete → signedRequest` | Not complete | PARTIAL |
| AND-MUT-05 | Automated/emergency campaign exit | `campaignExitMarket` | signed sink | No proof of no duplicate SELL under uncertainty | PARTIAL |
| AND-MUT-06 | Kill-switch / pending-entry cancellation | `kill` path | signed sink | Not complete | PARTIAL |
| AND-MUT-07 | OCO creation/cancellation | OCO creation and cancellation callers | signed sink | List and leg state not reconciled from durable intent | PARTIAL |
| AND-MUT-08 | Manual SELL | manual order handler | signed sink | Not complete | PARTIAL |
| AND-MUT-09 | Legacy/fallback orders | legacy MARKET BUY and recovery/protection paths | appears to use signed helpers in inspected references | Complete reachability proof missing | PARTIAL |

This table is an initial inventory, not an exhaustive verified registry. Python mutation paths are separate and are not governed by the Android ledger.

## Closure blockers
- A repository-wide interprocedural mutation graph has not been completed.
- The sink does not require a caller-created authorization capability/context.
- No test demonstrates that direct HTTP mutation bypass is impossible.
- Test/debug-only versus production-reachable paths have not all been classified.
- Every registry row lacks an executed operation-specific reconciliation test.
