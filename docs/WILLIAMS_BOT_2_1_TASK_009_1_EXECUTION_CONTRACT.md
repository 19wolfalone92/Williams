# WILLIAMS BOT 2.1 — TASK 009.1 Execution Contract and Barrier Findings

**Status:** BLOCKED — first fail-closed persistence correction is staged; end-to-end execution safety is not established.  
**Branch:** `audit/williams-2.1-task-009-integration`  
**Baseline main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Scope:** execution contract only. No strategy or risk-limit changes. No live exchange operations.

## 1. Source identity

The main baseline was read from GitHub at the SHA above. The integration branch started from that exact commit and contains no code from the unmerged PRs merely because they exist.

### Main-source file blob SHAs

These are **Git blob SHAs for file contents**, not commit SHAs:

| File | Blob SHA |
|---|---|
| `execution_barrier.py` | `860edaa38ffc569b67533822b470a7b7a3fe7996` |
| `campaign_execution.py` | `4478e30c0bf09fc3c2d1e2c18ffb00b843745713` |
| `db.py` | `2b27a610df69f639dcbf328c8aa41d5761be7f46` |
| `portfolio_trader.py` | `c2be9d6faaa5d7e16610fd2941a31995e36ed945` |
| `trader.py` | `b2d9287a10da7d803c8ce14912c24745453033d7` |
| `app/src/main/java/com/williamsbot/StandaloneRuntime.kt` | `c971551ca65eedcfa5c9124576dc57aadd1608c8` |

### Candidate commit SHAs compared by source

| Candidate | Exact commit SHA | Use in this pass |
|---|---|---|
| TASK 001 architecture | `c599abaf4c4f345c6f113693d75dd732b282ab93` | Contract comparison only |
| TASK 003 execution safety / PR #48 | `cd22ddfe7931377fe168bdb5411a6b438e47a5b9` | Contract comparison only; not merged wholesale |
| TASK 007 recovery / ledger | `e7ab3e72a0a7a85823ec898da28a1761476b84de` | Recovery comparison only |
| TASK 008 Spot Testnet | `7c4514d22ebca19861de2183317f7c39f3263e0c` | Spot-path comparison only |
| PR #42 Futures | `40a82056dee88a9c79e799912b07a8ddb1a1859b` | Separate Futures candidate |
| PR #43 risk hardening | `c4892b6ab08aafcf75485be2e548eb767cd571df` | Persistence/risk comparison only |
| PR #44 Williams/Futures patch | `dbe755a942276ac3b369afd47d424fff48bbb608` | Based on Futures SHA `80fbafd8b7899a70c8039eac5045f3d4af25baa1`; comparison only |

The TASK 003 branch contains a broader durable barrier design, including identity claims, a mutation lock and result recording, but its full DB/schema/call-site changes are not imported by this task. They require their own compatibility and regression proof.

## 2. Required canonical contract

### Mutation authority

1. A strategy or campaign component may create a proposed intent, but may not call an exchange mutation endpoint directly.
2. A durable execution-intent store must accept and verify the intent before an exchange mutation is attempted.
3. Each runtime/execution domain must have exactly one enforced mutation gate. A Python class does not control Android or another process using the same Binance account.
4. The gate owns final admission, stable identity, serialization, mutation invocation, authoritative response recording and ambiguity classification.
5. The exchange remains authoritative for order state, fills and actual inventory/position. Local intent, order, campaign and ledger rows are projections, not substitutes for exchange evidence.

### Required state progression

`CREATED → PENDING_DURABLE → ADMITTED → SUBMITTING → EXCHANGE_RESULT_DURABLE → RECONCILE`

After exchange reconciliation, the order can be classified as `OPEN`, `PARTIALLY_FILLED`, `FILLED`, `CANCELED`, `EXPIRED` or `REJECTED`, using authoritative exchange evidence. Position/campaign and financial effects must then be projected idempotently from the actual fills.

Fail-closed branches:

- Intent write fails before submit → `BLOCKED`; submit callback must not run.
- Timeout, connection loss or an exception after submit may have started → `UNKNOWN / RECONCILE_REQUIRED`; no blind resubmit.
- Exchange returns a response but the response cannot be durably recorded → `UNKNOWN / RECONCILE_REQUIRED`; no success result may be returned and no retry is permitted before reconciliation.
- Process restarts with an unresolved intent → reconciliation must run before any new exposure is admitted.
- Cancel response is not proof of zero fills. The order and fills must be reconciled; partial quantity remains a live inventory effect.
- Critical DB write failure blocks subsequent mutations. Best-effort telemetry cannot stand in for execution state.

### Idempotency and financial linkage

The target contract needs a stable identity for each logical operation, uniqueness enforcement for both intent and client order ID, immutable intent fields, and a persisted result keyed to the intent. Fills require exchange-scoped unique identity so duplicate trade/fill delivery cannot duplicate position or financial effects. A campaign must not be marked flat merely because an exit request or cancellation was accepted.

Spot and Futures remain separate execution/risk adapters. Spot inventory is asset balance and cannot create a short position. Futures must separately reconcile side/position mode, quantity, leverage, margin, reduce-only semantics, fills and liquidation/margin state. No Futures changes are imported in TASK 009.1.

## 3. Confirmed source-to-sink paths in the main baseline

This is a source-level inventory, not proof that the full graph is closed.

### Python

- `trader.py` constructs an `ExecutionBarrier` and the inspected entry path at line 1071 calls `execution_barrier.execute(...)`, whose callback invokes `client.order(...)`.
- `campaign_execution.py` has a `_submit` helper that requires a barrier and invokes it for inspected entry/protection/exit/cancel paths (main lines 342, 1050, 1126, 1172, 1264).
- **Confirmed bypass candidates:** `portfolio_trader.py` calls `self.client.order_safe(...)` directly at main lines 722, 1678 and 2217. These include emergency sell, managed sell and buy paths; they do not pass through the inspected `ExecutionBarrier` call in those code locations.
- `binance_client.py` contains the signed REST transport and explicitly treats POST/DELETE ambiguity conservatively, but transport retry rules do not establish a single application-level authority.

### Android

- `StandaloneRuntime.kt` contains a shared `signedRequest` transport with `signedPost` and `signedDelete`, but the inspected runtime contains multiple direct call sites to those helpers.
- Confirmed order mutation call sites include direct POSTs for entry/protection/exit/emergency/add-on at main lines 2697, 2749, 2868, 3258, 3574, 4146 and 5796; direct DELETEs include 1318, 2846, 2950, 4076 and 4083. Additional OCO/cancel-replace paths exist.
- Some Android calls are wrapped by `withCampaignMutation`, but that wrapper has not been proven to enforce the same durable intent, stable idempotency, authoritative result persistence and restart reconciliation contract as the required barrier. The shared HTTP helper is a transport sink, not proof of a complete policy gate.

### Conclusion on path closure

**No bypass-free graph is established.** The Python portfolio trader has confirmed direct mutation call sites, and Android has several signed mutation call sites whose equivalence to the required contract is unproven. This blocks any claim that ExecutionBarrier is currently the sole permitted mutation point.

## 4. Findings and minimal code changes on this branch

| Finding | Evidence in baseline | TASK 009.1 change |
|---|---|---|
| Critical intent persistence exceptions were swallowed | `ExecutionBarrier._persist` caught and ignored all exceptions; absent DB silently skipped persistence | `ExecutionPersistenceError`; missing writer, raised write error or explicit `False` now blocks before submit |
| Exchange response persistence was best-effort telemetry only | Post-submit status update could be returned as accepted without a mandatory durable response record | Require `save_execution_event(intent_id, "BINANCE_RESULT", response)`; failure raises `ExecutionOutcomeUnknown` |
| Duplicate status-transition block | Same exchange status was observed twice, which could turn a valid OPEN transition into `RECONCILE_REQUIRED` | Removed the duplicate block |
| Durable idempotency/recovery/path closure | Not established by these local changes | **Not fixed in this slice; P0 blocker** |

The response record uses the existing `execution_events` store to avoid a schema rewrite at this stage. This is only a minimal fail-closed correction: the table is append-only and does not yet provide the unique result key, read-back verification, immutable identity enforcement or a complete restart gate required for final approval.

## 5. Added regression tests

File: `test_task0091_execution_barrier_fail_closed.py`

| Test | Expected state/result | Reason |
|---|---|---|
| `test_intent_persistence_failure_blocks_submit` | `ExecutionPersistenceError`; submit count 0 | No order may be sent without durable intent |
| `test_missing_durable_store_blocks_submit` | `ExecutionPersistenceError`; submit count 0 | No volatile execution fallback |
| `test_result_persistence_failure_after_submit_is_unknown_not_success` | `ExecutionOutcomeUnknown`; submit count 1; intent remains unresolved | Exchange may have accepted the order; do not retry |
| `test_exchange_response_persistence_failure_is_unknown_not_success` | `ExecutionOutcomeUnknown`; submit count 1; no success result | Exchange response loss is an unresolved execution outcome |

These tests were added to the integration branch. They are not considered passed unless a CI run on the exact final branch HEAD completes the relevant test steps successfully.

## 6. Explicit P0 blockers

1. **Intent uniqueness/idempotency:** current main `db.py` uses `INSERT OR REPLACE` for `execution_intents`; there is no verified unique client-order identity claim in the baseline.
2. **Recovery-before-entry:** no verified global gate in the baseline proves that every unresolved PENDING/SUBMITTING/UNKNOWN intent is reconciled before new entries after restart.
3. **Post-submit persistence:** the minimal patch records the response through `execution_events`, but does not yet verify a read-back or use a unique, immutable exchange-result record. Failure to write the subsequent status remains unknown; the global cross-process mutation lock is not established.
4. **Cancel/fill race:** CANCEL responses are not currently processed through the same authoritative order-state classification path as placements.
5. **Partial fills and campaign/ledger atomicity:** this slice does not prove that partial execution, duplicate fill delivery, campaign position updates and financial journal projection are atomic and exactly-once.
6. **Python bypass:** direct order mutation calls remain in `portfolio_trader.py`.
7. **Android bypass/independent authority:** multiple direct signed mutation call sites remain; no evidence yet shows a shared durable barrier that closes every path across runtime/restart.
8. **Futures separation:** no Futures adapter/risk/recovery contract has been integrated or proven. PR #42/#44 remain comparison-only.
9. **Execution tests:** all required concurrency, timeout, crash/restart, cancel/fill, duplicate-fill and path-closure tests still need to run against the exact final commit. No live trading is permitted.

## 7. Acceptance gate

Do not mark TASK 009.1 complete until all of the following are proven on one exact commit SHA:

- intent durability and identity claims are atomic and read-back verified;
- unknown outcomes persist a cross-process mutation lock and block all new exposure until verified reconciliation;
- exchange result and fills are idempotently persisted and reconciled;
- cancel/fill races and partial exits are covered by deterministic tests;
- static call graph and tests show no Python or Android bypass;
- relevant Python safety/campaign suites and Android unit/build checks pass on the same final commit;
- `main` remains unchanged.

Current result: **BLOCKED**. The changes in this task close two specific fail-open persistence paths and remove a duplicate state transition, but they do not satisfy the full execution-safety acceptance gate.
