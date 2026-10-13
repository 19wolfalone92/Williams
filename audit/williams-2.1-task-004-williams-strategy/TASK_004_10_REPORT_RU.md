# WILLIAMS BOT 2.1 — TASK 004.10
## Baseline and P0 blocker report

**Status: BLOCKED — NOT ACCEPTED**  
**Repository:** `19wolfalone92/Williams`  
**Branch:** `audit/williams-2.1-task-004-williams-strategy`  
**PR:** #49 — OPEN, Draft, not merged  
**Base/main SHA observed:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Production code audited:** `e8a741a8799dcd13b467557dfb6cbe0f5ffc9ad7`  
**Report-only follow-up commits:** `f9f411587cfb3c04bbe05fc8038c729bf1c8b9ad` and `9750327cf7aef2c2edcce4ce861153bc9d9fd8c1`. These commits update audit documentation only; they do not contain production implementation changes. The three listed CI runs belong to the audited code SHA above, not to the report-only commits.  
**Scope of this report:** live GitHub baseline, targeted source inspection, CI evidence and explicit blockers. This report does not claim implementation or exhaustive mutation-sink closure.

## 1. Baseline recheck

GitHub PR metadata returned PR #49 as OPEN, Draft, base `main`, base SHA `04da5f9c09572e99ff310be3a1908179165e528c`, head branch `audit/williams-2.1-task-004-williams-strategy`, head SHA `e8a741a8799dcd13b467557dfb6cbe0f5ffc9ad7`, and `merged=false`. The branch search returned the expected branch. The PR changed-file list was re-read; it includes Python campaign/execution/DB files, Android runtime/audit store, strategy fixtures/tests and workflow/report files. No action was taken on `main`; PR was not merged or moved out of Draft.

### CI on the exact audited HEAD

| Workflow | Run ID | Result | Jobs reported |
|---|---:|---|---|
| Python backend tests | 38126230479 | SUCCESS | `backend-test` |
| Williams Campaign CI | 38126230493 | SUCCESS | `Android build and static checks`; `Python tests` |
| Build Android APK | 38126230472 | SUCCESS | `Build APKs` |

These results establish only that the configured jobs succeeded at SHA `e8a741a8799dcd13b467557dfb6cbe0f5ffc9ad7`. They do **not** prove durable-intent enforcement, process-death recovery, exhaustive sink coverage, Android instrumentation recovery, or the task's unconfigured fault-injection scenarios.

## 2. Source evidence observed at HEAD

### Android network boundary

File: `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`.

- `signedGet(path, params)` delegates to `signedRequest("GET", ...)`.
- `signedPost(path, params)` delegates to `signedRequest("POST", ...)`.
- `signedCancelReplace(...)` builds `/api/v3/order/cancelReplace` parameters and delegates to `signedPost`.
- `signedRequest(method, path, params)` constructs signed POST, DELETE or GET requests and calls `http.newCall(request).execute()`.
- The inspected common request boundary does not accept or validate a durable operation ID, persisted intent record, or operation-bound permit before the network call.
- `ExecutionGate.kt` contains an in-memory synchronized symbol reservation set; it is not a durable operation-intent ledger or a permit validated by the HTTP mutation boundary.
- `TradingAuditStore.kt` is an audit/order store, but the inspected contract does not demonstrate a mandatory durable-intent gate at `signedRequest`.

This is a P0 blocker. Caller-side checks, campaign mutation locks, and signal claims do not establish that every request reaching this network boundary has a durable intent.

### Python execution-intent persistence versus network boundary

Files: `execution_barrier.py`, `db.py`, `binance_client.py`.

- The current branch does have a campaign execution-intent persistence path: `ExecutionBarrier._persist(...)` calls `Database.save_execution_intent(...)`; required event persistence is exposed through `_persist_event_required(...)`. The barrier also has a separate `_claim_signal_once(...)` for entry-signal deduplication. These are distinct mechanisms, and signal claim is not operation intent.
- The inspected `Database.save_execution_intent(...)` writes an `execution_intents` row keyed by `intent_id`, but uses `INSERT OR REPLACE`; the current excerpt does not prove immutable payload-conflict semantics, a full operation lifecycle schema, or permit exclusivity at the network boundary.
- The campaign barrier is not universally enforced by the common network client: `BinanceSpotClient._request(method, path, ...)` still does not require an operation-bound permit immediately before `session.request(...)`.

File: `binance_client.py`.

- `BinanceSpotClient._request(method, path, ...)` is a common REST boundary used by wrappers; it classifies POST/DELETE transport errors and many server/rate-limit results as `unknown_execution`.
- The code comment explicitly says POST/DELETE are not blindly retried after transport failures and require reconciliation.
- This conservative UNKNOWN handling is useful but is not a durable-intent gate: the inspected `_request` interface does not require a persisted operation intent or an operation-bound permit immediately before `session.request(...)`.
- `campaign_execution.py::_submit` calls `ExecutionBarrier.execute` for its campaign execution route. That route alone does not prove that every direct, legacy, REST, emergency, OCO, cancel, cancel-replace, or other client path passes through it.

### Signal claim is not operation intent

Files: `campaign_model.py`, `db.py`, `campaign_execution.py`.

- `SignalSpec` stores signal provenance and `SignalSpec.new` forms a signal ID. A signal ID is not the identity of each distinct exchange mutation.
- Existing signal-claim/process tests do not prove a separately persisted intent for every network mutation.
- No inspected evidence demonstrates one shared, versioned operation-intent contract consumed by both Python and Android.
- No evidence from this audit proves that Python and Android read the same shared signal-identity fixture files for all required identity edge cases.
- Existing order/fill/audit data cannot be assumed to provide atomic exactly-once financial effects without targeted tests.

## 3. Mutation-sink inventory — known entries, not exhaustive

The following entries are confirmed from the inspected source; this is **not** a complete inventory because all direct callers, wrappers, callbacks and alternative clients have not been exhaustively enumerated and dynamically spied.

| Platform | Operation / sink | Observed path | Durable intent / boundary permit | UNKNOWN and recovery | Status |
|---|---|---|---|---|---|
| Android | POST order/OCO/cancel-replace routes | `signedPost → signedRequest → http.newCall(...).execute()` | No mandatory durable-intent/permit argument or validation observed at sink | Per-operation authoritative recovery not proven for all POST categories | BLOCKED |
| Android | DELETE order/order-list routes | `signedRequest("DELETE", ...)` and signed delete wrappers | No mandatory durable-intent/permit validation observed at sink | Cancel/fills/OCO-list resolution not proven end-to-end | BLOCKED |
| Android | cancel-replace | `signedCancelReplace → signedPost → signedRequest` | No operation-bound durable permit at sink | Both old/new order outcomes not proven under process death/unknown response | BLOCKED |
| Python | Spot mutation REST calls | `BinanceSpotClient._request → session.request` | No mandatory durable-intent/permit validation observed at sink | Transport ambiguity is conservatively marked UNKNOWN in inspected cases; complete recovery is unproven | BLOCKED |
| Python | Campaign submission route | `CampaignExecutionService._submit → ExecutionBarrier.execute → submit callback` | Barrier exists on this route, but durable operation intent and universal sink enforcement are not proven | Full post-send crash recovery not proven | BLOCKED |
| Python | Other order/OCO/cancel/cancel-replace/legacy/Futures paths | Full caller-to-sink enumeration not completed | NOT VERIFIED | NOT VERIFIED | BLOCKED |

The inventory criterion explicitly requires every reachable production mutation path and a mutation-spy that fails on unregistered sinks. Neither is established by the current evidence. Therefore this table must not be interpreted as exhaustive.

## 4. Acceptance matrix

| Requirement | Status | Evidence / blocker |
|---|---|---|
| Recheck GitHub baseline | PASS | PR metadata, branch and changed-file list re-read at task start |
| All three configured CI workflows on one SHA | PASS (configured CI only) | Run IDs in §1 all report SUCCESS for HEAD `1585355...` |
| Exhaustive Python and Android mutation-sink inventory | BLOCKED | Complete caller graph and runtime mutation-spy coverage not established |
| Durable intent persisted before every mutation | BLOCKED | Neither inspected common network boundary requires durable intent |
| Operation-bound permit checked at actual sink | BLOCKED | No permit validation at Android `signedRequest` or Python `_request` demonstrated |
| Separate signal / operation / order / fill identity | BLOCKED | No complete shared operation-intent contract and cross-platform proof established |
| Shared identity fixtures consumed by Python and Android | NOT VERIFIED | Existing strategy vectors are not proof of complete signal/operation identity fixtures |
| UNKNOWN POST recovery for all mutation categories | BLOCKED | Per-operation authoritative recovery, crash windows and no-blind-retry proofs incomplete |
| OCO recovery | NOT VERIFIED | Lost response, list plus child-order reconciliation and partial discovery not proven |
| Cancel-replace recovery | NOT VERIFIED | Old/new order mixed outcomes and process-death scenarios not proven |
| Cancel/fill race handling | NOT VERIFIED | Authoritative final order state and pre-cancel fills not proven end-to-end |
| SQLite commit ambiguity / multi-process permit exclusivity | BLOCKED | Existing signal-claim concurrency tests do not cover full durable-intent state machine and permit lifecycle |
| Fill/fee/financial-effect integration | BLOCKED | Separate exactly-once fill/fee proof required; signal claims are insufficient |
| Causal replay / no look-ahead | NOT VERIFIED | Not established by identity tests or the three CI runs |
| Android instrumentation / process-death recovery | NOT VERIFIED | APK build and static/unit jobs are not instrumentation/process-death evidence |
| No Mainnet or Spot Testnet trading mutations | PASS for this task run | No trading mutation was issued as part of this audit |
| PR remains Open Draft; main untouched | PASS | PR metadata rechecked; no merge/ref update performed |

## 5. Required next implementation steps

1. Introduce a durable operation-intent contract in the existing execution architecture; define stable operation IDs and canonical semantic payloads, reject same-ID/different-payload conflicts, and persist before network mutation.
2. Extend the existing Python execution barrier and Android execution architecture so the **actual common mutation network boundary** validates an operation-bound permit against durable storage immediately before sending. Read-only requests must remain usable without a mutation permit.
3. Enumerate every reachable mutation sink and caller, including emergency exits, protection replacement, OCO, cancel, cancel-replace, recovery, legacy and any Futures paths. Add a mutation-spy/allowlist test that fails on newly discovered sinks.
4. Make UNKNOWN durable and non-retryable until operation-specific authoritative reconciliation resolves it. Define separate recovery logic for ordinary orders, OCO lists/children, cancel and cancel-replace.
5. Add process/multiprocess/SQLite fault injection, shared identity fixtures actually read by both platforms, financial fill/fee tests and causal replay tests.
6. Re-run all required workflows after implementation and record the final SHA, jobs and results. Do not promote this task to PASS based on documentation or existing general CI.

## Final decision

**TASK 004.10 — BLOCKED — NOT ACCEPTED**

The current HEAD has green configured CI, but the core acceptance invariant is not proven and is contradicted by the inspected mutation-boundary interfaces: the common network calls do not require a validated durable-intent permit. The safe next step is implementation at the existing mutation boundary plus complete sink enumeration and targeted tests—not a caller-only wrapper or another signal-claim check.

No Binance Mainnet or Spot Testnet order mutations were performed. PR #49 remains OPEN / Draft; `main` was not changed.
