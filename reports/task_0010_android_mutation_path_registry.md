# TASK 001.0 — Android Mutation-Path Registry
Date: 2026-10-12 UTC

## Baseline
- PR #66 is OPEN, DRAFT, NOT MERGED; base `fix/pytest-safe-collection`.
- Required starting HEAD verified exactly: `7c034dcb359a62cd8fedfa14f89f7072334c4900`.
- Source SHA inspected: `7c034dcb359a62cd8fedfa14f89f7072334c4900`.
- Main mutation source inspected: `app/src/main/java/com/williamsbot/StandaloneRuntime.kt` (blob `c971551ca65eedcfa5c9124576dc57aadd1608c8`); `ExecutionGate.kt` (blob `aec1c01d12fe460a62a48c1fa81af527cce4afd5`); `TradingAuditStore.kt`; `ExecutionAccumulator.kt`.
- Discovery method: retrieved exact source at immutable SHA and enumerated all textual definitions/call sites of `signedPost(`, `signedDelete(`, `signedCancelReplace(`, and `withCampaignMutation(`. This is a source inventory, not a whole-program reachability proof.

## Source-to-sink inventory (discovered call sites)
All discovered wrappers ultimately reach `NativeEngine.signedRequest(method, path, params)`, which constructs and executes OkHttp requests. This central network sink currently has no durable pre-mutation intent guard.

| Initiating path / location in StandaloneRuntime.kt | Call chain to sink | Mutation | Existing protection | Disposition |
|---|---|---|---|---|
| Campaign entry, around line 2683 | candidate → `withCampaignMutation` → `signedPost` → `signedRequest` | POST `/api/v3/order` STOP_LOSS entry | In-memory symbol reservation + SharedPreferences marker; no durable order-intent transaction proven | **NOT VERIFIED** |
| Campaign protection, around 2747 | `createCampaignStop` → `withCampaignMutation` → `signedPost` → `signedRequest` | POST `/api/v3/order` stop | Same | **NOT VERIFIED** |
| Campaign trailing stop, around 2790 | trail worker → `withCampaignMutation` → `signedCancelReplace` → `signedPost` → `signedRequest` | POST `/api/v3/order/cancelReplace` | In-memory gate; cancel-replace may be partially successful | **NOT VERIFIED** |
| Campaign protection cancellation, around 2842 | exit → `withCampaignMutation` → `signedDelete` → `signedRequest` | DELETE `/api/v3/order` | In-memory gate; no durable cancel intent shown at network boundary | **NOT VERIFIED** |
| Campaign market exit, around 2867 | exit → `withCampaignMutation` → `signedPost` → `signedRequest` | POST market SELL | In-memory gate; exit result still requires robust ambiguity handling | **NOT VERIFIED** |
| Partial-entry cancellation, around 2946 | campaign recovery → `withCampaignMutation` → `signedDelete` → `signedRequest` | DELETE order | In-memory gate only | **NOT VERIFIED** |
| Add-on protection resize, around 3066 | add-on → `withCampaignMutation` → `signedCancelReplace` → `signedPost` → `signedRequest` | POST cancel-replace | In-memory gate only | **NOT VERIFIED** |
| Campaign emergency exit, around 3254 | emergency path → `withCampaignMutation` → `signedPost` → `signedRequest` | POST market SELL | Gate present, but durable emergency intent/reconciliation contract unproven | **NOT VERIFIED** |
| Add-on entry, around 3566 | candidate → `withCampaignMutation` → direct `signedPost` → `signedRequest` | POST `/api/v3/order` | In-memory gate only | **NOT VERIFIED** |
| Kill-switch pending-entry cancellation, around 1318 | `kill()` → loop pending entries → `signedDelete` → `signedRequest` | DELETE order | **Bypasses `withCampaignMutation`**; unresolved outcome persistence not proven | **CRITICAL BYPASS** |
| Emergency SELL fallback, around 4146 | recovery/fallback branch → `signedPost` → `signedRequest` | POST market SELL | **Direct call outside `withCampaignMutation`** | **CRITICAL BYPASS** |
| Legacy buy execution, around 5796 | legacy method → `signedPost` → `signedRequest` | POST order | Method context requires complete reachability proof; code contains a disabled legacy BUY entry point elsewhere, but this call site remains in source | **NOT VERIFIED** |
| Emergency SELL after protection failure, around 6013 | protection failure → `runCatching` → direct `signedPost` → `signedRequest` | POST market SELL | **Direct call outside `withCampaignMutation`** | **CRITICAL BYPASS** |
| OCO protection creation, around 6134 | protection setup → `signedPost` → `signedRequest` | POST `/api/v3/orderList/oco` | No durable pre-submit intent proven at sink | **NOT VERIFIED** |
| Manual SELL, around 6242–6302 | `sell()` → direct `signedDelete` OCO → account read → direct `signedPost` market SELL → journal/state update | DELETE orderList then POST market SELL | Two-step partial-success window; neither mutation is wrapped by `withCampaignMutation` | **CRITICAL BYPASS** |
| Campaign add-on protection and other call sites | `withCampaignMutation` or direct wrapper → `signedRequest` | POST/DELETE/cancel-replace | No universal durable boundary | **NOT VERIFIED** |

Textual inventory at inspected SHA: 10 direct order `signedPost` call sites (excluding definitions/wrapper delegation), 7 direct `signedDelete` call sites, 2 `signedCancelReplace` call sites, and 1 wrapper definition each. There may be additional indirect paths; exhaustive reachability is not established.

## Actual boundary findings
- `ExecutionGate.kt` is an in-memory `LinkedHashSet` reservation. It is not persistent and cannot by itself prevent a post-restart duplicate mutation.
- `withCampaignMutation` checks runtime/pause/reconciliation/kill flags, reserves a symbol, and writes a SharedPreferences marker using asynchronous `.apply()`; it releases the gate in `finally`. It does not record a durable per-operation intent or idempotency key before network I/O.
- `signedRequest` performs HTTP directly. It records REST calls through `TradingAuditStore.recordRestCall` after a response; this is not a pre-mutation intent record. Transport exceptions can occur before this audit write.
- `TradingAuditStore` has durable `orders`, `executions`, `events`, and `rest_calls` tables, but inspected schema does not provide a dedicated transactional mutation-intent ledger with operation key, intent state, and reconciliation lock.
- `signedCancelReplace` sends a POST and its own comment acknowledges that cancel-replace is not transactional.
- `ExecutionAccumulator` de-duplicates fills in in-memory maps/sets; its shown state is not itself a durable order-mutation boundary.

## Integration blocker and safe disposition
A safe retrofit must atomically persist an intent before each network mutation, bind a stable idempotency identity to the exchange client ID/list ID or cancellation operation, transition ambiguous outcomes durably, and make startup recovery reconcile those intents before enabling entries. The current API (`signedPost(path, params)`, `signedDelete(path, params)`) does not require an intent token and is called directly by several unrelated paths. Automatically inventing intent IDs in the network wrapper would not safely bind existing operations to recovery semantics; blocking every POST/DELETE centrally would also block protective/emergency exits without a designed safety protocol.

Therefore **no unsafe compatibility wrapper was added**. A full call-site migration and a tested durable intent/reconciliation contract are required before source changes can be safely accepted. This report is not evidence of implementation completion.
