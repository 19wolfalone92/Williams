# WILLIAMS BOT 2.1 — TASK 008.6
## Production Freshness Gate, ExecutionBarrier & Recovery Closure

**Status: BLOCKED / NOT ACCEPTED**  
**Branch:** `audit/williams-2.1-task-008-spot-testnet`  
**Baseline:** `d7634adc1f6a1d25ef334294a4903f7c9134e06b`  
**Final source revision inspected:** `782d880d45b8c6d38de73c43e55f3f2d7d51014b`

No Binance Mainnet access, order submission, cancellation, replacement, OCO mutation, or other exchange mutation was performed. No Spot Testnet network check was attempted because local P0 and CI are not proven closed.

## 1. Repository state and evidence

- The named existing branch was found; no duplicate branch was created.
- Comparison against the stated baseline reports **31 commits ahead, 0 behind**.
- The branch comparison lists the cumulative changed files below in section 2.
- No PR matching this exact head branch was found by the available GitHub PR search. No PR was created and no merge was performed.
- On final SHA `782d880d45b8c6d38de73c43e55f3f2d7d51014b`, combined status returned no status entries and the connector returned no workflow runs. This is **CI UNKNOWN**, not a pass.
- CI workflow execution could not be triggered through the available GitHub actions tools. No claim is made that tests were run locally; the connector exposes repository source but not an execution environment for the project's test suite.
- `main` was not modified.

## 2. Changes made in the existing branch

1. Commit `29c1b06522f629253c54c129dd35c32160cb8472` — `fix: fail closed when clock offset budget is unapproved`
   - `preflight_gate.py`: removed the silent 1000 ms fallback. With neither an explicit constructor budget nor `LIVE_MAX_TIME_OFFSET_MS`, the server-time check remains UNKNOWN with reason `clock_offset_budget_unapproved`.
   - `preflight_gate.py`: a negative configured offset budget fails closed.
   - `preflight_gate.py`: `allow_reconciled_orders=True` no longer turns known open Williams orders into PASS. The flag is explicitly recorded as ignored; without durable reconciliation evidence the open-order check FAILS.

2. Commit `782d880d45b8c6d38de73c43e55f3f2d7d51014b` — `test: cover unapproved clock budget and reconciliation override`
   - `test_preflight_gate.py`: added tests for missing clock-offset budget, explicit budget configuration while other unknown checks still block, and the ineffective boolean reconciliation override.

These changes are source changes, not proof that all P0 requirements are closed. The changes have **not been executed by CI or a local test runner in this task**.

## 3. Runtime freshness and mandatory Preflight

`runtime_freshness.py` currently validates supplied evidence objects, but production Preflight does not yet receive a complete set of real producer-bound evidence from the runtime. The validator alone is not production integration.

| Component | Observed producer / available source | Last-success timestamp and monotonic observation | Generation/session binding | Approved freshness budget | Production Preflight conclusion |
|---|---|---|---|---|---|
| REST reachability | Direct Spot Testnet client ping | Request is synchronous; no reusable freshness evidence | Not bound | UNKNOWN | Basic reachability may PASS, component freshness incomplete |
| Server time | `sync_time()`, serverTime and client offset | Server time is read; no complete evidence record tied to monotonic observation | Not bound | Offset budget is only explicit configuration; approval source not verified | UNKNOWN when budget absent; otherwise only offset check is measured |
| Account | Authenticated `account()` | No durable source timestamp/monotonic observation in evidence record | Not bound | UNKNOWN | Basic response validation only |
| Balances | Account response | No independent measurement evidence | Not bound | UNKNOWN | Basic shape validation only |
| Last confirmed closed candle | Kline path exists | No closed-candle evidence object passed to mandatory Preflight | Not bound | UNKNOWN | UNKNOWN |
| Market WebSocket | Hub has connection and last-message state | Existing timestamp is not consumed by Preflight as trusted evidence | Reconnect generation not checked end-to-end | UNKNOWN | UNKNOWN |
| User Data Stream | Stream state/event code exists | Not wired as mandatory evidence | Generation/session binding unverified | UNKNOWN | UNKNOWN |
| Scanner heartbeat/progress | No verified completed-cycle producer wired to Preflight | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |
| Market Context | Snapshot has generation, creation time and candle close time | Snapshot fields exist but no mandatory Preflight handoff | Current-generation consistency unverified | UNKNOWN | UNKNOWN |
| SQLite integrity/critical tables | SQLite schema, WAL, durable intent/event writes | No mandatory integrity evidence in Preflight | DB/runtime generation not bound | UNKNOWN | UNKNOWN |
| Reconciliation | Exchange open-order reads and classification | No durable completed reconciliation result consumed by Preflight | Recovery-cycle generation unverified | UNKNOWN | UNKNOWN / block |
| Orders and pending intents | Open orders and execution intent persistence exist | No complete exchange/local lifecycle proof | No comprehensive state generation | UNKNOWN | Known/unknown orders block; full state remains unproven |
| Android runtime | Android source exists | No verified backend-consumable runtime heartbeat | Cross-runtime generation/session contract unverified | UNKNOWN | UNKNOWN |
| Diagnostics | Report generation timestamp | Report time is not component last-success time | Not bound | UNKNOWN | Diagnostics cannot authorize execution |

No operational age threshold has been invented. `LIVE_MAX_TIME_OFFSET_MS` is a configuration input, but its approval as a project-level safety contract is **not verified**. The production integration still needs actual measurements, true source timestamps, monotonic observations where applicable, generation/session matching, and approved budgets. Preflight's existing required checks default to UNKNOWN; this continues to block readiness.

**P0-A: NOT CLOSED.**

## 4. ExecutionBarrier and risk-reduction contract

The intended mutation path remains:

`decision → risk gate → durable intent → trusted Preflight → one-shot permit → ExecutionBarrier → Binance adapter → durable result → reconciliation`

| Mutation family | Current evidence | Status |
|---|---|---|
| Spot BUY / entry | Barrier and durable intent code exist; complete production call graph not proven | UNKNOWN / NOT ACCEPTED |
| Spot SELL reducing/closing LONG | Some legacy emergency paths fail closed; no complete reduction contract proven | BLOCKED |
| Protective order create | All production callers not enumerated and runtime-tested | UNKNOWN |
| Protective cancel/replace | Race-safe lifecycle and every caller not proven | UNKNOWN / BLOCKED |
| OCO/conditional | Prefix classification exists; full mutation path not proven | UNKNOWN |
| Emergency exits | Existing generic path deliberately blocks where dedicated contract absent | BLOCKED |
| Background jobs/retries | Complete call graph and no-retry behavior not proven for all callers | UNKNOWN |
| Recovery/reconciliation mutation | Complete call graph not proven | UNKNOWN; ambiguous outcome must not blindly resend |
| Android-triggered mutation | End-to-end call graph not verified | UNKNOWN |
| Legacy/direct API and auxiliary clients | Repository-wide call graph and runtime proof incomplete | UNKNOWN |

`_check_orders()` no longer treats a boolean caller override as reconciliation evidence. No global bypass was added. A risk-reducing exception is not implemented because the available proof does not establish exact campaign/position identity, originating order identity, monotonic exposure reduction, bounded quantity against authoritative free balance, protection state, completed reconciliation, and durable idempotency together. Cancellation is not assumed to reduce risk.

**P0-B: NOT CLOSED.** This source review does not prove that every production mutation is routed through the barrier; static search alone would not establish that either.

## 5. Exact 22-case Recovery matrix retained from TASK 008.5

The case names below preserve the original matrix; they are not represented as passed. Required assertions for every case remain: durable intent, mutation/send count, stable `clientOrderId`, authoritative exchange order state, unique fills, quote cost and fees, local position/campaign, protection, risk reservation, Recovery Gate, and whether the next mutation is allowed.

| # | Original scenario | Evidence status for TASK 008.6 |
|---:|---|---|
| 1 | Failure before durable intent | NOT RUN; existing partial barrier test is not full restart proof |
| 2 | Intent write failure | NOT RUN; existing partial barrier test is not full lifecycle proof |
| 3 | Preflight denial | NOT RUN against full recovery invariants |
| 4 | Stale measurement | NOT RUN end-to-end; validator unit test is not production wiring proof |
| 5 | Missing measurement | NOT RUN end-to-end |
| 6 | Source disconnect after preflight | NOT VERIFIED |
| 7 | Source generation changes | NOT VERIFIED end-to-end |
| 8 | Exchange accepts order, response lost | Partial ambiguous-result test exists; full recovery/send-count invariants NOT VERIFIED |
| 9 | Crash after response before local result save | NOT VERIFIED |
| 10 | Partial fill with unsaved fills/fees | NOT VERIFIED |
| 11 | Full fill with unsaved fills/fees | NOT VERIFIED |
| 12 | WebSocket loss then REST recovery | NOT VERIFIED |
| 13 | Restart with unresolved intent | NOT VERIFIED as complete matrix case |
| 14 | Exchange protection exists but no local campaign link | NOT VERIFIED |
| 15 | Cancel-vs-fill race | NOT VERIFIED |
| 16 | Ambiguous cancel-replace | NOT VERIFIED |
| 17 | Repeated reconciliation | NOT VERIFIED idempotently |
| 18 | Unknown fee | NOT VERIFIED |
| 19 | Fills/position/order-history disagreement | NOT VERIFIED |
| 20 | Corrupted SQLite/critical write failure | Partial write-failure tests exist; corruption/restart recovery NOT VERIFIED |
| 21 | Concurrent recovery cycles | NOT VERIFIED |
| 22 | Conflicting mutation during reconciliation | NOT VERIFIED |

**P0-C Recovery: NOT CLOSED.** The exact matrix has not been replaced by a smaller one; complete fault injection and repeated-recovery assertions were not executed.

## 6. Multi-instance SQLite concurrency

| Requirement | Status |
|---|---|
| Two independent service instances sharing one SQLite DB | NOT RUN |
| Concurrent recovery of the same intent | NOT VERIFIED |
| Concurrent admission/claim for one operation across processes | NOT VERIFIED |
| Deduplication of one fill across processes | NOT VERIFIED |
| Competing protection changes | NOT VERIFIED |
| DB write failure during incomplete reconciliation | NOT VERIFIED |
| Restart after partial transaction/commit | NOT VERIFIED |

Existing in-process locking or fake-database tests do not establish cross-process uniqueness and transaction guarantees. Production-level unique constraints and conditional state transitions need an exercised two-instance test.

## 7. Synthetic-secret audit

No real credentials were read or included in this report. The existing Preflight exception sanitizer avoids serializing raw exception text, but the required synthetic-marker audit was not executed across all channels.

| Channel | Result |
|---|---|
| Backend logs and tracebacks | UNKNOWN / NOT RUN |
| Preflight/recovery diagnostics | UNKNOWN / NOT RUN |
| HTTP request/response serialization and error payloads | UNKNOWN / NOT RUN |
| SQLite events, DB files and backups | UNKNOWN / NOT RUN |
| Diagnostic exports | UNKNOWN / NOT RUN |
| Android logs | UNKNOWN / NOT RUN |
| Test output and CI artifacts | UNKNOWN / CI unavailable |
| Real credential storage architecture | Source-level review incomplete; no secrets extracted |

**P0-D: UNKNOWN / NOT CLOSED.** No claim of clean secret propagation is made.

## 8. LONG / SHORT mode classification

| Mode | Source-level status | Key gaps |
|---|---|---|
| Binance Spot LONG | PARTIALLY PRESENT / NOT VERIFIED end-to-end | Freshness gate, full mutation-path closure, recovery/financial invariants and CI unverified |
| Spot SELL to reduce/close LONG | BLOCKED where dedicated reduction contract is absent | Exact position/order identity, exposure-reduction proof, balance/protection race safety, durable idempotency |
| Futures Demo/Testnet LONG | UNKNOWN / NOT VERIFIED | Futures adapter, environment pinning, margin/risk/protection/recovery and barrier coverage not established in this task |
| Futures Demo/Testnet SHORT | UNKNOWN / NOT VERIFIED | Same gaps; Spot SELL is not a SHORT position |

No trading operations were performed.

## 9. CI, final SHA and read-only Spot Testnet

Final inspected source SHA: `782d880d45b8c6d38de73c43e55f3f2d7d51014b`.

- Combined status checks: empty response.
- Commit workflow runs exposed by the available connector: empty response (the connector filters to PR-triggered runs, so this is not proof that no other workflow ran).
- Python backend CI: UNKNOWN.
- Freshness/Preflight tests: NOT RUN by this agent; added test cases are not execution evidence.
- ExecutionBarrier/recovery/fault injection: NOT RUN.
- Campaign CI: UNKNOWN.
- Android lint/unit tests and APK build: UNKNOWN.
- Secret audit CI/artifacts: UNKNOWN.
- Other mandatory workflows: UNKNOWN.
- Read-only Spot Testnet: **NOT RUN**. Local P0/CI is not closed and a technically enforced read-only capability was not demonstrated. No Testnet request was made.
- Mainnet and order mutations: NOT used.

## 10. Remaining blockers and acceptance

1. Real source-bound measurements are not wired into mandatory production Preflight.
2. Freshness budgets and clock-offset policy approval are not evidenced.
3. Full mutation call graph and runtime bypass audit remain incomplete.
4. Safe risk-reduction contract remains blocked.
5. All 22 recovery scenarios lack complete executed assertions.
6. Multi-instance SQLite concurrency is untested.
7. End-to-end synthetic-secret scan is unrun.
8. LONG/SHORT classifications remain incomplete.
9. Required CI results on one SHA are unavailable.
10. Read-only Testnet capability is not technically demonstrated.

**Final status: BLOCKED / NOT ACCEPTED.** The two source changes improve fail-closed behavior, but do not satisfy TASK 008.6 acceptance criteria. No readiness-to-trade conclusion follows from source changes or APK build status.
