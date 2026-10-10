# WILLIAMS BOT 2.1 — TASK 008.7
## Production Safety Closure: Freshness Gate, ExecutionBarrier, Recovery & CI

**Status: BLOCKED / NOT ACCEPTED**  
**Branch:** `audit/williams-2.1-task-008-spot-testnet`  
**Requested starting SHA:** `afe90cc71a572869d4f8d58ee381c8a70424effc`  
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

The new validator change hardens the evidence API, but it does **not** connect all producers to Preflight. No age threshold has been invented. `LIVE_MAX_TIME_OFFSET_MS` remains a configuration value, not a verified approved policy contract.

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
- Freshness and Preflight tests: NOT RUN.
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
