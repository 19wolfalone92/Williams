# TASK 001.1 — Mutation-path migration
**Status: PARTIAL — P0 OPEN.** Inventory is based on source inspection; reachability closure is not proven.

## Common source-to-sink path
Authenticated mutation calls in `StandaloneRuntime.kt` use `signedPost` / `signedDelete` / `signedCancelReplace`, which route to `signedRequest`. The latest implementation journals POST/DELETE inside `signedRequest` before network I/O. A repository-wide proof that no alternate authenticated mutation sink exists is still outstanding.

## Identified callers requiring coverage
| Operation | Source location/function | Status |
|---|---|---|
| Pending-entry cancellation / kill switch | `kill`, around lines 1292–1324 in baseline | Routed through signedDelete; no path-specific fault test yet |
| Campaign entry | `submitCampaignEntry`, baseline around 2609 onward | Routed through signedPost; no path-specific fault test yet |
| Campaign protection | `withCampaignMutation` caller, baseline around 2749 | Routed through signedPost; no path-specific fault test yet |
| Trailing protection cancel-replace | `signedCancelReplace`, baseline around 2791 and function around 5099 | Intent journaled; both-leg reconciliation incomplete |
| Campaign exit / emergency exit | baseline around 2846–2868 and 3258 | Routed through signedDelete/signedPost; no path-specific fault test yet |
| Partial-entry cancellation | baseline around 2950 | Routed through signedDelete; no path-specific fault test yet |
| Recovery/legacy protection | baseline around 3071 and 3574 | Routed through signedCancelReplace/signedPost; no path-specific fault test yet |
| OCO cancellation | baseline around 4076–4083 and 6242–6248 | Routed through signedDelete; reconciliation test absent |
| Emergency SELL | baseline around 4146 and 6013 | Routed through signedPost; no fault proof yet |
| Legacy MARKET BUY | baseline around 5794–5799 | Routed through signedPost; no fault proof yet |
| OCO creation | baseline around 6133–6136 | Routed through signedPost; no fault proof yet |
| Manual SELL | baseline around 6301–6311 | Routed through signedPost; no fault proof yet |

## Bypass / limitations
- The common sink currently creates its own intent; it does not yet require an explicit caller-supplied authorization capability/context.
- Reachability analysis of every OkHttp/request construction and every legacy/fallback branch has not been completed.
- No bypass test currently proves direct network mutation is impossible.
- Emergency/kill/manual sell paths are intentionally not blanket-blocked by the entry-admission check, but their reconciliation behavior remains unproven.
