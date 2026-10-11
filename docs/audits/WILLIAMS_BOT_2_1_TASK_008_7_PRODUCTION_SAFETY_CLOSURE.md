# WILLIAMS BOT 2.1 — TASK 008.7
## Production Safety Closure: Freshness Gate, ExecutionBarrier, Recovery & CI

**Status: BLOCKED / NOT ACCEPTED**  
**Branch:** `audit/williams-2.1-task-008-spot-testnet`  
**Requested starting SHA:** `afe90cc71a572869d4f8d58ee381c8a70424effc`  
**Actual HEAD found at the start of this continuation:** `34a3472c13d86be332a536a9b8f8939c647a6616` (different from the requested SHA; continued from actual HEAD without reset)  
**Source/test HEAD after bounded changes:** `37401a82f91df3ee6f1a2e3c842a0bb1f3909a6e`  
**Baseline:** `d7634adc1f6a1d25ef334294a4903f7c9134e06b`

## 1. Repository integrity

- The existing branch was found and used; no branch was created.
- At task start, resolving the branch name returned `afe90cc71a572869d4f8d58ee381c8a70424effc`, matching the last reported SHA.
- The baseline comparison at start reported 32 commits ahead and 0 behind.
- Changes in this task were committed sequentially to the existing branch.
- No PR was created or merged. No Mainnet request or trading mutation was performed.
- The available GitHub status query returned no status entries. The available workflow-run connector returns only PR-triggered runs; an empty response is not proof of no workflow activity. CI is UNKNOWN unless an actual final-SHA result is retrieved.

## 2. Source changes in TASK 008.7

1. `defc651b776e988f50307f0067373bf7770046e8` — `runtime_freshness.py`
   - Freshness budgets of zero, negative, or non-convertible values now produce UNKNOWN (`freshness_budget_invalid`) instead of being silently coerced to a 1 ms budget.
   - `evaluate_freshness_set` accepts an explicit `expected_generations` mapping. A supplied mismatch blocks as UNKNOWN. Equality is not inferred between unrelated components; callers must provide the generation contract.

2. `d52328a2b2f7adbf6e6be42f756738e57d71db2e` — `preflight_gate.py`
   - Hardened legacy `_check_time()`: malformed/failed server-time reads, absent clock-offset budget, negative budget and excessive offset fail closed. The missing-budget path no longer risks a comparison against `None`.

3. `f68f9604eb70035a6cb02a5a32476d4a94f2b44a` — `test_runtime_freshness.py`
   - Added invalid-budget, explicit generation mismatch and no-implicit-generation-equality tests.

4. `95a7c02ae7e64f4c1db3b7a6a5d9e740c99329de` — `test_preflight_gate.py`
   - Added tests for the legacy time-check path without an approved budget and for malformed server-time data.

5. `15ea3cd9e35fdfde721ae038d655277b03df1c3d` — `runtime_freshness.py`
   - Added optional monotonic observation timestamps bound to a monotonic session identifier.
   - If monotonic evidence is supplied, missing evaluation context, a session mismatch, invalid monotonic timestamps, or a monotonic timestamp in the future returns UNKNOWN. Elapsed age uses monotonic time only within the same declared session; wall-clock source time is still validated.
   - This is an evidence-contract capability only; no producer-to-Preflight integration is claimed.

6. `37401a82f91df3ee6f1a2e3c842a0bb1f3909a6e` — `test_runtime_freshness.py`
   - Added tests for stale candle/account/reconciliation evidence, missing scanner heartbeat, WebSocket with no recent event, failed SQLite integrity probe, non-numeric freshness budget, and monotonic-session enforcement.

These tests were added but **were not executed** in a local test runner or confirmed by CI. Their status is NOT RUN, not PASS.

## 3. Freshness evidence trace

| Component | Source currently present in repository | Actual producer evidence consumed by mandatory Preflight | Approved budget | Status |
|---|---|---|---|---|
| REST connectivity | Direct client ping | A synchronous ping result exists; no full timestamped evidence record | No freshness budget verified | PARTIAL / BLOCKED |
| Server time | `sync_time()`, `serverTime`, client offset | Offset is checked; no approved budget source proven | UNKNOWN unless explicitly configured; approval itself unverified | BLOCKED without budget |
| Account and balances | Authenticated account response | Basic response/shape checks; not a complete freshness evidence contract | UNKNOWN | PARTIAL / BLOCKED |
| Closed H1 candle | Kline/candle paths exist | No verified closed-H1 evidence record wired to Preflight | UNKNOWN | UNKNOWN |
| Market WebSocket | Hub tracks connection and last-message time | Not wired as trusted mandatory evidence with source time and reconnect generation | UNKNOWN | UNKNOWN |
| User Data Stream | Hub tracks connection/events | No complete freshness/generation proof wired to Preflight | UNKNOWN | UNKNOWN |
| Scanner heartbeat/progress | No verified completed-cycle producer consumed by Preflight | None established | UNKNOWN | UNKNOWN |
| Market Context | Snapshot has generation and context candle close time | No end-to-end Preflight handoff proving coherent source generations | UNKNOWN | UNKNOWN |
| SQLite integrity/critical writes | SQLite/WAL and intent/event persistence exist | No mandatory integrity/critical-table probe proven in Preflight | UNKNOWN | UNKNOWN |
| Reconciliation | Open-order reads and recovery-related code exist | No generation-bound durable completed reconciliation result consumed by Preflight | UNKNOWN | BLOCKED |
| Position/protection state | Orders/position/protection logic exists | No complete current exchange-vs-local proof consumed by Preflight | UNKNOWN | UNKNOWN / BLOCKED |
| Android runtime | Android execution/runtime sources exist | No verified heartbeat contract consumed by the Python execution gate | UNKNOWN | UNKNOWN |
| Diagnostics | Report creation timestamp | Report timestamp is not treated as component last-success time | Not applicable as execution evidence | Cannot authorize execution |

The validator changes harden the evidence API, including optional monotonic-age validation and session binding, but they do **not** connect all producers to Preflight. No age threshold has been invented. `LIVE_MAX_TIME_OFFSET_MS` remains a configuration value, not a verified approved policy contract.

**P0-A: BLOCKED.** The required real producer-to-Preflight-to-barrier trace is incomplete. The existing required checks remain UNKNOWN where proofs are absent, which should prevent readiness.

## 4. Preflight freshness and time-of-check/time-of-use

- Missing clock-offset budget is now fail-closed in both `verify_all()` and legacy `_check_time()`.
- Invalid freshness budget values no longer silently become a 1 ms budget.
- Explicit expected-generation mismatches are represented as UNKNOWN.
- These changes do not establish end-to-end atomicity between component evidence and exchange mutation. The barrier still needs evidence-generation invalidation/locking across every source and execution path.
- A source changing after Preflight must trigger a new admission or block the mutation; that behavior has not been proven across all production producers.

**P0-A acceptance: NOT MET.**

## 5. ExecutionBarrier and mutation paths

| Path | Current evidence | Task 008.7 result |
|---|---|---|
| Spot entry BUY | Barrier, permit and durable intent code exist | Full call graph and runtime bypass tests NOT RUN |
| Spot SELL / reduction / close | No fully proven bounded reduction contract | BLOCKED where contract is absent |
| Protection create/update | Complete production caller inventory not verified | UNKNOWN |
| Cancel and cancel/replace | Full caller inventory, races and recovery invariants not verified | UNKNOWN / BLOCKED |
| OCO / conditional orders | Some identity/classification logic exists | Complete mutation route NOT VERIFIED |
| Emergency exit | Generic path has fail-closed restrictions | BLOCKED absent a dedicated verified contract |
| Background jobs and retries | Complete call graph not established | UNKNOWN |
| Recovery/reconciliation | No blind retry should occur after ambiguity; all production paths not traced | UNKNOWN |
| Android runtime | End-to-end mutation route not proven | UNKNOWN |
| Legacy/direct client calls | No exhaustive call graph plus runtime proof completed | UNKNOWN |

The context-local mutation permit and barrier checks provide useful controls, but are not proof that every mutation uses them. A Python-private token is not a security boundary against arbitrary code executing in the same process.

**P0-B: BLOCKED.** No bypass was declared fixed without evidence.

## 6. Risk-reducing Spot SELL contract

No universal bypass was introduced. A SELL cannot be considered safe solely from its side. A verified reduction requires current position/account identity, actual available asset quantity, unresolved order/fill effects, exact campaign/order association, bounded quantity, durable idempotency and post-operation reconciliation.

Those facts are not currently proven together by the available evidence. Therefore risk-reducing SELL/emergency operations that require the missing contract remain BLOCKED. This may reduce availability, but prevents an unknown quantity or unresolved order from authorizing an exposure-changing mutation.

Spot SELL is not classified as Futures SHORT.

## 7. Recovery matrix — all 22 cases

The 22 scenarios preserved from the TASK 008.6 report remain the required matrix. The following are NOT RUN in this task; none is represented as passing.

| # | Scenario | Result |
|---:|---|---|
| 1 | Failure before durable intent | NOT RUN |
| 2 | Intent write failure | NOT RUN |
| 3 | Preflight denial | NOT RUN |
| 4 | Stale measurement | NOT RUN end-to-end |
| 5 | Missing measurement | NOT RUN end-to-end |
| 6 | Source disconnect after Preflight | NOT RUN |
| 7 | Source generation changes | NOT RUN end-to-end |
| 8 | Exchange accepts order, response lost | NOT RUN with complete financial invariants |
| 9 | Crash after response before local result save | NOT RUN |
| 10 | Partial fill with unsaved fills/fees | NOT RUN |
| 11 | Full fill with unsaved fills/fees | NOT RUN |
| 12 | WebSocket loss then REST recovery | NOT RUN |
| 13 | Restart with unresolved intent | NOT RUN end-to-end |
| 14 | Exchange protection exists but no local campaign link | NOT RUN |
| 15 | Cancel-vs-fill race | NOT RUN |
| 16 | Ambiguous cancel-replace | NOT RUN |
| 17 | Repeated reconciliation | NOT RUN |
| 18 | Unknown fee | NOT RUN |
| 19 | Fills/position/order-history disagreement | NOT RUN |
| 20 | Corrupted SQLite/critical write failure | NOT RUN end-to-end |
| 21 | Concurrent recovery cycles | NOT RUN |
| 22 | Conflicting mutation during reconciliation | NOT RUN |

For each case, the required assertions still include durable intent, actual mutation count, stable clientOrderId, exchange order state, unique fills, fee asset/amount and quote cost, local campaign/position, protection, risk reservation, Recovery Gate, and whether a subsequent mutation is permitted. A return status alone is insufficient.

**P0-C Recovery: BLOCKED.**

## 8. Concurrent SQLite recovery

Two independent service instances against one shared database were not run. The following remain UNKNOWN/NOT RUN: cross-process intent claims, fill deduplication, risk reservation races, protection races, cancel/replace concurrency, write failure during reconciliation, partial commit recovery and uniqueness of mutation admission.

The repository's SQLite WAL configuration and in-process locks do not themselves prove those properties. **P0-C concurrency: BLOCKED.**

## 9. Synthetic-secret audit

No real credentials were read or included. No end-to-end synthetic marker was executed across all requested channels in this task.

| Channel | Result |
|---|---|
| Backend logs and tracebacks | UNKNOWN / NOT RUN |
| Preflight/recovery diagnostics | UNKNOWN / NOT RUN |
| SQLite database, events and backups | UNKNOWN / NOT RUN |
| HTTP request/response and errors | UNKNOWN / NOT RUN |
| Diagnostic exports | UNKNOWN / NOT RUN |
| Android logs | UNKNOWN / NOT RUN |
| CI artifacts and reports | UNKNOWN; no final-SHA CI evidence available |

**P0-D: UNKNOWN / NOT CLOSED.**

## 10. LONG / SHORT

| Mode | Status | Evidence gap |
|---|---|---|
| Binance Spot LONG | PARTIAL / NOT VERIFIED end-to-end | Complete freshness, barrier, financial recovery and CI proof missing |
| Spot SELL reducing/closing LONG | BLOCKED where required contract is missing | Exposure and available-quantity proof not complete |
| Futures Demo/Testnet LONG | UNKNOWN / NOT VERIFIED | Futures environment, margin, protection, recovery and barrier proof not established |
| Futures Demo/Testnet SHORT | UNKNOWN / NOT VERIFIED | Same; Spot SELL is not a SHORT position |

No trading operations were performed.

## 11. CI and read-only Spot Testnet

- Final-SHA combined status: no status entries returned by the available connector.
- Workflow-run lookup: no runs returned; that connector only surfaces PR-triggered runs and cannot establish all workflow activity.
- Python backend tests: NOT RUN / UNKNOWN.
- Freshness and Preflight tests: NOT RUN; additional tests in `test_runtime_freshness.py` and `test_preflight_gate.py` were not executed locally or by a confirmed workflow.
- ExecutionBarrier and recovery tests: NOT RUN.
- Campaign CI: UNKNOWN.
- Android lint/unit/APK build: UNKNOWN.
- Secret audit tests/artifacts: UNKNOWN.
- Read-only Spot Testnet: **NOT RUN**. Local P0, CI and technical mutation denial are not proven.
- Binance Mainnet and order mutations: NOT used.

No CI workflow is marked PASS without an actual result tied to the final SHA.

## 12. Remaining blockers

1. Wire trusted, source-bound measurements and generations from all required producers into mandatory Preflight.
2. Obtain/identify approved freshness and clock-offset contracts; do not infer budgets.
3. Prove all production mutation routes and runtime denial of direct/bypass calls.
4. Either implement and prove a constrained risk-reduction contract or keep affected actions blocked.
5. Execute all 22 recovery scenarios with full exchange/local/financial assertions.
6. Run real two-instance SQLite concurrency tests.
7. Run synthetic-marker scans over all available channels.
8. Complete LONG/SHORT mode audits with code and test evidence.
9. Obtain all mandatory CI results on one final SHA.
10. Prove read-only capability technically before any Testnet request.

**Final status: BLOCKED / NOT ACCEPTED.** The source changes harden invalid-budget and generation-mismatch handling, but TASK 008.7 acceptance criteria remain unmet. No trading-readiness conclusion is justified.


## 13. Continuation addendum — source/test SHA `37401a82f91df3ee6f1a2e3c842a0bb1f3909a6e`

The branch HEAD was rechecked before this continuation. It was `34a3472c13d86be332a536a9b8f8939c647a6616`, not the requested starting SHA `afe90cc71a572869d4f8d58ee381c8a70424effc`. Work continued from the actual branch HEAD; no reset or force push was performed.

The two continuation commits are:
- `15ea3cd9e35fdfde721ae038d655277b03df1c3d`: optional monotonic freshness age and same-session binding in `runtime_freshness.py`.
- `37401a82f91df3ee6f1a2e3c842a0bb1f3909a6e`: additional freshness unit tests in `test_runtime_freshness.py`.

Current verification:
- Branch comparison against baseline: 39 commits ahead, 0 behind.
- Combined status query: no status entries returned.
- Available workflow-run query: no runs returned; this connector exposes PR-triggered runs only.
- PR search for this head branch: no matching PR found.
- The new tests were **not run**. No PASS is claimed.
- The monotonic/session functionality is not yet wired to trusted runtime producers or mandatory Preflight.
- P0-A, P0-B, P0-C, P0-D and read-only Testnet criteria remain BLOCKED/UNKNOWN as detailed above.

The report itself is documentation, not a substitute for the missing production integration or executed tests.


## 14. TASK 008.8 continuation — production integration and closure audit

**Audit result: BLOCKED / NOT ACCEPTED.** This continuation does not claim production freshness integration, recovery closure, mutation-path completeness, executed tests, or CI success.

### 14.1 Actual GitHub state at continuation

- Existing branch: `audit/williams-2.1-task-008-spot-testnet`.
- Actual branch HEAD resolved from the branch ref: `328a1346522657f67bb7cc09185a35544438e5d8`.
- Requested last-reported SHA matches this HEAD.
- Base: `d7634adc1f6a1d25ef334294a4903f7c9134e06b`.
- Comparison: 40 commits ahead, 0 behind.
- Exact-head PR search returned no matching PR. No PR was created.
- Combined commit status returned an empty status list. The available workflow-run connector returned no runs and only supports PR-triggered runs; this does not prove that no other workflow runs exist. CI remains UNKNOWN.
- No repository test runner or workflow dispatch capability was available in this execution path. Tests were not run here; no test is marked PASS.
- No branch reset, force-push, merge, Mainnet access, or exchange mutation was performed.

### 14.2 Source-to-Preflight evidence trace

| Component | Source measurement found | Timestamp / clock domain | Generation | Path into mandatory Preflight | UNKNOWN behavior / finding |
|---|---|---|---|---|---|
| Binance REST reachability | Synchronous client ping in `preflight_gate.py` | Request-time result; no durable observation record | No producer generation bound | Basic reachability check only; `runtime_freshness.py` is not wired as a complete producer evidence set | Cannot prove age between checks; other missing required checks keep readiness false |
| Server time | `client.sync_time()` and `serverTime` in `preflight_gate.py` | Binance wall clock compared with local wall clock offset | No process/session generation | Legacy offset check, not a complete `FreshnessEvidence` handoff | Missing/unapproved offset budget blocks; approval provenance is not present |
| Account / balances | Authenticated account response | Request response time not captured as trusted evidence | No account snapshot generation | Basic shape/checks; no freshness evidence passed to validator | Account response alone does not prove balance age or reservation consistency |
| H1 closed candle | Binance kline paths and local candle data | Candle close timestamp exists in some context data; source-observation timestamp not consistently bound | Context version exists; not bound to a Preflight evidence generation | No proven closed-H1 producer-to-Preflight evidence path | UNKNOWN; cannot authorize a new entry |
| Market WebSocket | `ws_hub.py` tracks `market_last_message_at`, connection and reconnect counters | Existing field uses process wall-clock time; not a compatible monotonic observation record | Reconnect count exists but is not proven as a generation contract | Not consumed as trusted mandatory freshness evidence | Missing/disconnected/old message must block; integration proof absent |
| User Data Stream | `ws_hub.py` tracks last event, connection and reconnect state | Process timestamp; no full event-age evidence contract | Reconnect count/subscription fields exist, but no verified generation handoff | Not wired to mandatory Preflight evidence | UNKNOWN; account/order state cannot be presumed current |
| Scanner | No trusted completed-cycle producer was found in the reviewed evidence | No verified last-success clock | None proven | No verified path | Missing heartbeat must block; remains UNKNOWN |
| Market Context | `market_context.py`: snapshot generation, creation time and context candle-close times | `created_at_ms` is wall-clock; no monotonic session evidence proven at Preflight | Snapshot generation and per-context version exist | `ExecutionBarrier` checks declared context versions for an intent, but this is not the full runtime freshness set | Context version mismatch blocks that intent; global source freshness remains unproven |
| SQLite integrity / critical persistence | `db.py`: SQLite WAL, synchronous=NORMAL, intent/event methods | DB timestamps include SQLite wall-clock defaults; no integrity-probe observation timestamp consumed by Preflight | Schema version exists; runtime/database generation binding not proven | No mandatory integrity evidence is passed to Preflight | Missing integrity/critical-write proof must block; integration absent |
| Open orders / fills | Preflight reads open orders and classifies known/unknown client IDs; other exchange/local order and fill paths exist | Exchange response time and local reconciliation completion time are not bound in one evidence object | No completed reconciliation generation consumed by Preflight | Open-order check only; not equivalent to complete financial reconciliation | Unknown orders fail and reconciliation-required blocks; completeness of order/fill/position reconciliation remains unproven |
| Position / campaign / protection | Local campaign and position state plus exchange order reads | Local DB timestamps and exchange response times are not tied to one consistent observation | Campaign/context versions exist but no reconciliation epoch proven | No full state snapshot passed as mandatory freshness evidence | Unknown/mismatched protection or position must block new entries; end-to-end proof absent |
| Recovery / reconciliation | Recovery and reconciliation code paths exist | No single trusted last-completed timestamp with same-session monotonic age proven | No durable completed-recovery generation consumed by Preflight | `requires_reconciliation` and required checks fail closed in the gate | UNKNOWN/recovery incomplete blocks new entries; safe risk-reduction has no separate fully proven admissibility contract |
| Android runtime | Android code and runtime diagnostics exist | No verified backend-consumable source timestamp and clock domain | No verified heartbeat generation consumed by Preflight | No proven Python Preflight integration | UNKNOWN whenever Android is part of the selected execution mode |

The validator in `runtime_freshness.py` has no approved default budgets (`DEFAULT_MAX_AGE_MS = {}`) and supports optional same-session monotonic age checking. That is a useful validation primitive, **not production integration**. The mandatory Preflight implementation does not demonstrate that real producer-bound evidence for every required component is supplied and evaluated. The `checked_at_ms` generated by `PreflightReport.as_dict()` timestamps report serialization, not the underlying source measurements. The ExecutionBarrier's report-age check therefore cannot replace component freshness validation.

### 14.3 Freshness budget approval

No authoritative policy/source in the reviewed branch was found that approves operational freshness budgets for REST, account/balances, H1 closed candles, market/user streams, scanner heartbeat, context, SQLite integrity, reconciliation, or Android runtime. Do not infer approval from test constants, environment variable presence, the report timestamp, or `ExecutionBarrier.max_preflight_age_ms`.

| Budget | Approved value / units | Provenance | Status |
|---|---|---|---|
| REST response freshness | Not established | No approved policy source found | BLOCKED |
| Server clock offset | Configurable `LIVE_MAX_TIME_OFFSET_MS`; approval/source not established | Environment/configuration only | BLOCKED unless policy is independently approved |
| Account/balance freshness | Not established | No approved policy source found | BLOCKED |
| Closed H1 candle age | Not established | No approved policy source found | BLOCKED |
| Market/user WebSocket event age | Not established | No approved policy source found | BLOCKED |
| Scanner heartbeat | Not established | No verified heartbeat producer/policy | BLOCKED |
| Market Context age/generation | Not established | Context generation exists, approved age budget does not | BLOCKED |
| SQLite integrity / reconciliation age | Not established | No approved policy source found | BLOCKED |
| Android heartbeat | Not established | No verified contract/policy | BLOCKED |

A missing, malformed, cross-session, cross-generation, negative-age, future, or contradictory measurement must remain UNKNOWN/BLOCKED. A process restart invalidates prior process-local monotonic evidence unless a fresh measurement is produced in the new session.

### 14.4 ExecutionBarrier source-to-sink findings

| Route / operation | Observed control | Mutation sink proof | Current result |
|---|---|---|---|
| Initial entry | `ExecutionBarrier.execute()`, durable intent/event attempts, context checks, Preflight, stable client order ID claim, one-use mutation permit | The reviewed barrier calls a supplied `submit()` callback under the permit; repository-wide exhaustive sink inventory and direct-network bypass exclusion are not proven | PARTIAL / BLOCKED |
| Add-on / campaign order | Campaign and portfolio wrappers can route through barrier | Every add-on and recovery callback is not proven to be mutation-free except for one intended sink | BLOCKED |
| Duplicate intent / client order ID | Durable claim via `try_claim_state` and stable client ID requirement | Database cross-process atomicity and all recovery re-entry paths not proven by executed tests | NOT VERIFIED |
| Timeout / unknown exchange response | Barrier marks ambiguous/reconciliation-required and does not itself blindly retry | Every upstream retry wrapper and all mutation call sites are not exhaustively traced | PARTIAL / BLOCKED |
| Cancel / replace | Must use a valid permit and same Testnet client; permit is consumed before mutation | Full cancel/replace call graph and multi-call callback behavior not proven | BLOCKED |
| Protection recovery / OCO | Existing order/protection paths are present | No complete durable protection-state reconciliation and no full sink trace proved | BLOCKED |
| Close / reduction | `portfolio_trader.py` refuses `EMERGENCY_EXIT` without a dedicated contract; other mutation routes require barrier | No approved separate risk-reduction admission contract or safe quantity proof established | BLOCKED |

A key semantic hazard was found in `execution_barrier.py`: the current generic direction mapping treats every `SELL` as `short` for entry-context permission checks. Spot SELL reduction/exit must not be modeled as opening a short, and ordinary Spot SELL cannot establish a Futures short position. The correct fix requires explicit execution-purpose/position semantics and dedicated tests; this continuation does not weaken the gate or introduce a speculative workaround.

The permit is a process-level guard for code using the client wrapper, not by itself proof that every network mutation in the repository passes through it. Exhaustive direct HTTP/client calls, callbacks, retries and recovery sinks still need static inventory plus sink-interception tests.

### 14.5 Spot SELL, recovery, concurrency, secrets, and mode status

- **Spot SELL safety:** BLOCKED. Available balance, reserved quantity, open SELL quantity, partial fills, base-asset commissions, exchange filters, campaign/position identity and idempotent close are not proven as one atomic admissibility contract.
- **22-scenario recovery matrix:** all 22 cases remain NOT RUN in this continuation. Existing matrix remains the required baseline; no scenario is upgraded to PASS without automated execution and financial assertions.
- **SQLite concurrency:** NOT RUN. WAL and an in-process lock do not prove cross-connection/process exactly-once financial effects, atomic risk reservation, fee deduplication or crash recovery.
- **Synthetic-secret audit:** NOT COMPLETE. Source/config/test/report files were sampled, but no complete repository-wide scan, workflow/log audit or synthetic-marker redaction test was executed. No real secrets were requested or printed.
- **Execution modes:** Spot LONG PARTIAL/NOT VERIFIED end-to-end; Spot reduction/exit BLOCKED; Spot SHORT is not supported by ordinary Spot SELL; Futures Demo/Testnet LONG and SHORT UNKNOWN/NOT VERIFIED; unknown mode must fail closed.
- **Read-only Spot Testnet:** NOT VERIFIED; no network request was made.

### 14.6 Verification on the current branch head

| Check | Result |
|---|---|
| Branch HEAD / baseline compare | Verified: `328a1346522657f67bb7cc09185a35544438e5d8`; 40 ahead / 0 behind |
| Matching PR search | No matching PR found by available search |
| Combined status checks | Empty response; UNKNOWN, not PASS |
| Workflow runs / failed or incomplete job logs | No runs returned by available PR-only workflow-run connector; complete run/job inventory unavailable |
| Python tests / freshness tests / Preflight tests | NOT RUN |
| ExecutionBarrier / mutation-sink tests | NOT RUN |
| All 22 recovery scenarios | NOT RUN |
| SQLite concurrency suite | NOT RUN |
| Android build / lint / unit tests | UNKNOWN / NOT RUN |
| Synthetic-secret redaction suite | NOT RUN |
| Read-only Spot Testnet | NOT VERIFIED |

### 14.7 Required closure work

1. Add a trusted evidence provider to the real producers and inject its immutable evidence snapshot into mandatory Preflight; bind wall-clock timestamp, monotonic session, component generation and source identity.
2. Establish approved freshness budgets from an explicit reviewed policy contract before enabling any corresponding entry gate.
3. Separate Spot entry, Spot reduction/exit, cancellation, protection recovery, and Futures order semantics; correct the SELL/SHORT conflation without creating a bypass.
4. Complete repository-wide mutation sink inventory and prove every mutation sink rejects calls without a valid one-use permit; prove the callback cannot perform multiple mutations with one permit.
5. Implement and run sink-interception tests that assert no network mutation occurs for every blocked case.
6. Run the existing complete 22-case recovery matrix with exchange/local/financial assertions.
7. Run independent-connection and process-level SQLite race/restart tests.
8. Complete synthetic-secret scans and redaction tests over all available repository channels.
9. Retrieve or trigger all applicable CI workflows and collect job logs tied to the same final SHA.
10. Only after P0 closure, prove read-only Spot Testnet access technically and run read-only requests.

**TASK 008.8 final status: BLOCKED / NOT ACCEPTED.** No new production integration or test pass is claimed by this addendum.


## 15. TASK 008.9 — Production Preflight Closure, Spot SELL Safety & Recovery Proof

**Decision: BLOCKED — NOT ACCEPTED.** This pass verifies repository state and performs source-level inspection. It does not claim the requested P0 implementation or test closure. No production code was changed in this pass: approved freshness budgets and authoritative producer contracts are still missing, and guessing those contracts or injecting synthetic production evidence would be unsafe.

### 15.1 Actual branch state

- Repository: `19wolfalone92/Williams`.
- Existing branch resolved: `audit/williams-2.1-task-008-spot-testnet`.
- Actual HEAD before this pass: `579ddf76d0c9c34852bc5f9bcbf1f86e39fcc483`, matching the previously reported SHA.
- Base: `d7634adc1f6a1d25ef334294a4903f7c9134e06b`.
- Comparison before this pass: 41 commits ahead, 0 behind.
- The branch exists. Exact-head PR search returned no matching PR; none was created.
- Combined status query returned no status entries. Workflow-run connector returned no runs and is limited to PR-triggered runs. CI status remains UNKNOWN, not PASS.
- Existing workflow `.github/workflows/campaign-ci.yml` defines a push/pull_request/workflow_dispatch trigger and includes Python tests, `full_safety_audit.py`, safety tests, and Android unit tests/lint/debug APK build. No verified run ID or job logs for this SHA were available.
- No test suite was executed in this pass. No Mainnet or exchange mutation was used.

### 15.2 Code vs documentation: key source findings

| Requirement | Code evidence inspected | Assessment |
|---|---|---|
| Freshness validator | `runtime_freshness.py`: `FreshnessEvidence`, source allowlist, generation, wall-clock timestamp and optional monotonic session fields; `DEFAULT_MAX_AGE_MS = {}` | Validator exists; no approved default budgets |
| Mandatory Preflight integration | `preflight_gate.py`: checks server time, account, symbols/filters, orders and required checks; `as_dict()` generates `checked_at_ms` at serialization | No demonstrated producer-bound evidence set is passed through the real measurement graph into the final report |
| Preflight report age | `execution_barrier.py` compares `checked_at_ms` against `max_preflight_age_ms` | This is report-age validation, not proof of age of source measurements |
| Entry gate | `ExecutionBarrier._verify_preflight()` requires concrete `PreflightCheckService`, matching client identity, TESTNET Spot pinning, required checks PASS and no reconciliation flag | Fail-closed checks exist, but full current-source evidence and exhaustive mutation sink coverage remain unproven |
| Durable intent | `execution_barrier.py` persists intent and issues a one-use context-local permit | Useful control; not proof of all network calls passing through the barrier or cross-process exactly-once behavior |
| Spot SELL purpose | `OrderIntent` has a generic `purpose` field; no verified complete operation-purpose contract was established for all callers | Spot reduction/exit remains insufficiently distinguished from Futures short semantics |
| Database | `db.py` uses SQLite WAL, `synchronous=NORMAL`, transaction helpers and schema versioning | No completed two-connection/process recovery race results available |
| WebSocket | `ws_hub.py` tracks connection/message/reconnect state | No proof that all actual producer observations are generation-bound and consumed by mandatory Preflight |
| Scanner | No trusted, completed-cycle evidence producer and mandatory Preflight handoff were established by inspected materials | UNKNOWN must block entry |
| Recovery matrix | Existing reports preserve a 22-case matrix; no full execution results are available | All cases remain NOT RUN or NOT VERIFIED for this task |

### 15.3 Required budgets: no approved values established

No explicit reviewed policy source with approved per-component freshness budgets was established in the inspected files. Consequently, this pass does not assign TTLs or heartbeat intervals.

| Component | Approved budget/source | Gate result until approval and integration |
|---|---|---|
| REST / server time | No authoritative budget contract established | BLOCKED |
| Account and balances | No authoritative budget contract established | BLOCKED |
| Closed H1 candle | No authoritative budget contract established | BLOCKED |
| Market WebSocket / User Data Stream | No authoritative budget contract established | BLOCKED |
| Completed scanner cycle | Producer and budget contract not established | BLOCKED |
| Market Context generation | Generation exists, approved age budget and complete evidence binding not established | BLOCKED |
| SQLite integrity / reconciliation | No approved freshness contract and complete evidence handoff established | BLOCKED |
| Android runtime | Must only be required when the selected execution architecture actually depends on it; no universal dependency is inferred here | UNKNOWN unless required by selected mode |

A measurement must be source-bound, successful, non-future, generation-consistent, and evaluated with a valid budget. If monotonic evidence is used, it must be evaluated in the same monotonic session. Process restart cannot make old process-local monotonic evidence fresh.

### 15.4 Entry and mutation-path status

The barrier code inspected requires a concrete Preflight service and a Testnet Spot client, checks the required check names and PASS statuses, and issues a mutation permit. However, these controls alone do not prove that every production route reaches that barrier or that every callback performs only its intended single mutation.

| Route / invariant | Status |
|---|---|
| Initial Spot entry and add-on | BLOCKED — no complete source-to-sink inventory plus executed mutation-spy proof |
| Delayed/replayed intent and restart recovery | BLOCKED — no executed exactly-once proof |
| Timeout / unknown POST result | PARTIAL — barrier-level ambiguity handling exists; all caller retry paths not proven |
| Cancel / replace | BLOCKED — full sink and callback inventory not proven |
| Protection recovery | BLOCKED — protection state and recovery admissibility not proven end-to-end |
| Safe close/reduction | BLOCKED — separate risk-reduction admission contract and confirmed quantity invariants not proven |
| Direct REST / Android signed mutation routes | NOT VERIFIED exhaustively |
| New entry when required evidence or financial reconciliation is UNKNOWN | Must remain BLOCKED; complete producer-to-gate integration not proven |

### 15.5 Spot SELL and financial invariants

**Spot SELL safety: BLOCKED.** The generic `OrderIntent.purpose` field is not, by itself, evidence that every caller enforces the distinctions between `SPOT_ENTRY_BUY`, `SPOT_REDUCTION_SELL`, `SPOT_EXIT_SELL`, `PROTECTION_RECOVERY`, `ORDER_CANCEL`, `ORDER_REPLACE`, `FUTURES_LONG`, and `FUTURES_SHORT`.

Before enabling reduction/exit, the implementation must prove from authoritative exchange and durable local state: free base quantity minus reservations and outstanding sells; confirmed fills and fee asset; symbol filters; campaign/position identity; unresolved prior mutations; stable intent/client order identity; and no increase in exposure. Unknown state must trigger reconciliation or block the mutation. Ordinary Spot SELL must never be interpreted as opening a Futures short.

### 15.6 Recovery matrix and SQLite concurrency

The existing TASK 008.5–008.7 reports retain the 22 scenarios; they are not replaced with a shorter set. For TASK 008.9, **all 22 remain NOT RUN / NOT VERIFIED** because no complete test execution with financial and mutation-count assertions was available. No individual scenario is promoted to PASS.

SQLite concurrency and crash recovery remain NOT RUN. No evidence is available from two independent connections/processes for intent reservation, tradeId/fill deduplication, commission uniqueness, simultaneous recovery, failed commit, SQLITE_BUSY, migration interruption, or restart after partial processing. Exactly-once financial effects are therefore NOT VERIFIED.

### 15.7 Secret audit, CI and Spot Testnet

- **Synthetic-secret audit:** NOT COMPLETE. This pass did not execute a repository-wide marker scan or redaction tests over code, config, fixtures, reports, CI logs, Android assets and serialized state. No real secrets were extracted or printed.
- **CI:** UNKNOWN. No verified run ID, terminal status, or failed/incomplete job logs tied to this final SHA were available. Empty status/run results are not success.
- **Read-only Spot Testnet:** NOT VERIFIED. No request was made because the local P0/CI prerequisites are not proven closed.
- **Execution modes:** Spot LONG remains not verified end-to-end; Spot reduction/exit blocked; ordinary Spot SELL does not establish SHORT; Futures LONG/SHORT remain UNKNOWN without proof of a complete mode-specific execution path.

### 15.8 TASK 008.9 acceptance checklist

- [ ] Real producer measurements passed into mandatory Preflight.
- [ ] Approved freshness budgets and source provenance established.
- [ ] All new-entry paths gated immediately before mutation.
- [ ] Exhaustive mutation sink bypass audit and mutation-spy tests.
- [ ] Explicit Spot/Futures operation purpose and safe Spot SELL invariants.
- [ ] Global recovery gate tied to authoritative reconciliation.
- [ ] All 22 recovery scenarios executed with financial assertions.
- [ ] SQLite concurrency and crash-recovery tests executed.
- [ ] Protection state verified from exchange and local evidence.
- [ ] Synthetic-secret and redaction audit completed for available channels.
- [ ] All required CI workflows passed on one final SHA.
- [ ] Read-only Spot Testnet checked after local closure, or marked NOT VERIFIED.

**Final status: BLOCKED — NOT ACCEPTED.** This section records the inspected evidence and outstanding work; it is not a substitute for production implementation, automated test results, or CI evidence.
