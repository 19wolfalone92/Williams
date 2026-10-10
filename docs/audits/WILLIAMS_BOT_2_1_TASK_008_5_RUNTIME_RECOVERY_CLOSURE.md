# WILLIAMS BOT 2.1 — TASK 008.5
## Runtime Freshness Integration, ExecutionBarrier & Recovery Closure

**Status: BLOCKED / NOT ACCEPTED**  
**Branch:** `audit/williams-2.1-task-008-spot-testnet`  
**Initial HEAD verified before this task:** `43806f780883627b62def540d104d8a909cb334c`  
**Baseline:** `d7634adc1f6a1d25ef334294a4903f7c9134e06b`

This report records a fresh source audit. It does not claim implementation closure. No Mainnet access, order submission, cancellation, replacement, OCO mutation, or other exchange mutation was performed.

## 1. Initial GitHub state

- The branch resolved to `43806f780883627b62def540d104d8a909cb334c` at task start.
- The branch was 28 commits ahead of the stated baseline and 0 behind.
- No pull request with this head branch was found by the connector search.
- Combined status returned no status entries; the connector returned no workflow runs for the commit. CI therefore remains **UNKNOWN**.
- No changes were made to `main`.

## 2. Source audit findings

### Runtime freshness and Preflight

Files inspected: `runtime_freshness.py`, `preflight_gate.py`, `execution_barrier.py`, `execution_permit.py`, `ws_hub.py`, `market_context.py`, `db.py`, `test_runtime_freshness.py`, and `test_execution_barrier_fail_closed.py`.

Confirmed:
- `runtime_freshness.py` validates supplied evidence and has no approved default age budgets. This is correct: no budget must be inferred.
- `PreflightCheckService.REQUIRED_CHECKS` includes environment, API, server time, credentials, authentication, account, balances, permissions/trading status, exchange metadata/filters, candles, market/user streams, scanner heartbeat, Market Context, diagnostics freshness, storage integrity, reconciliation, execution-state-known, open orders, and Android freshness.
- Preflight initializes required checks to UNKNOWN and only passes checks for which it performs a direct verification. Its final `ready` value is false when any required check is not PASS.
- Server-time offset currently has an environment-backed threshold (`LIVE_MAX_TIME_OFFSET_MS`, default 1000 ms). This is a clock-offset guard, not an approved data-age contract. Its policy owner/source has not been verified as an approved project requirement.
- `_check_orders()` classifies exchange open orders by client-ID prefix. Unknown open orders fail; known Williams orders set `requires_reconciliation` and fail unless the caller passes `allow_reconciled_orders`. The caller flag is not, by itself, evidence that exchange/local reconciliation actually completed.
- `ExecutionBarrier` requires a concrete `PreflightCheckService` bound to the exact Spot Testnet client, checks its report, and persists intent/admission before submission. The source audit does not establish that every production mutation path is routed through it.
- WebSocket timestamps/connection flags exist, but they are not wired into mandatory source-bound freshness evidence in Preflight.
- Market Context has generation and timestamps, but no verified freshness handoff to Preflight was found.
- SQLite has durable intent/event persistence and WAL configuration, but no verified mandatory integrity/freshness evidence check is performed by Preflight.
- Scanner heartbeat, completed reconciliation evidence, and Android/backend generation-bound heartbeat were not found wired into Preflight.
- Existing tests include a test-only class-level monkeypatch seam for controlled Preflight reports. Those tests are useful for barrier behavior but do not prove production source measurements are integrated.

### Runtime source contract matrix

| Component | Real producer observed | Timestamp provenance | Generation/session validation | Approved freshness budget | Preflight outcome |
|---|---|---|---|---|---|
| REST | Synchronous client REST calls | Request completion is not a persistent freshness measurement | No shared generation contract | UNKNOWN / not approved | Reachability can pass; freshness contract incomplete |
| Server time | `sync_time()` | Server-provided time and client offset | No source generation | Offset limit exists, but approval provenance unknown | Partial check only |
| Account | Authenticated `account()` | Response is synchronous; no evidence object | No generation binding | UNKNOWN / not approved | Basic account check can pass; freshness contract incomplete |
| Balances | Account response balances | Same as account response | No generation binding | UNKNOWN / not approved | Basic shape check can pass; freshness contract incomplete |
| Closed candles | Kline/data path exists | No last-confirmed-closed-candle evidence passed to Preflight | Not bound | UNKNOWN / not approved | UNKNOWN |
| Market WebSocket | `market_last_message_at`, connection flags | Event timestamp exists in hub | Reconnect generation not checked by Preflight | UNKNOWN / not approved | UNKNOWN |
| User Data Stream | User stream event/connection state | Event timestamp exists in hub | Reconnect generation not checked by Preflight | UNKNOWN / not approved | UNKNOWN |
| Scanner | No verified completed-cycle evidence producer found | UNKNOWN | UNKNOWN | UNKNOWN / not approved | UNKNOWN |
| Market Context | Snapshot `created_at_ms`, generation, candle close time | Snapshot/candle fields | Generation exists in cache, not validated by freshness gate | UNKNOWN / not approved | UNKNOWN |
| SQLite | Durable persistence and schema | DB timestamps/transaction results do not prove integrity | No runtime generation contract | UNKNOWN / not approved | UNKNOWN |
| Reconciliation | Open-order reads and classification | Request results only; no completed local-vs-exchange proof | No completed recovery-cycle generation | UNKNOWN / not approved | UNKNOWN unless a caller-provided flag is trusted; that flag alone is insufficient evidence |
| Execution state | Intent table and barrier persistence | Durable intent timestamps/status | No comprehensive exchange-state proof | UNKNOWN / not approved | UNKNOWN |
| Diagnostics | Report creation time | Report timestamp is not component observation time | No | UNKNOWN / not approved | UNKNOWN |
| Android runtime | Android source exists | No verified backend-consumable heartbeat found | No cross-runtime generation contract | UNKNOWN / not approved | UNKNOWN |

### Negative-case behavior

| Condition | Required behavior | Evidence in current implementation |
|---|---|---|
| Missing evidence | UNKNOWN and block | Validator supports UNKNOWN; production wiring incomplete |
| Stale evidence | FAIL and block | Validator supports this only when an explicit budget is supplied |
| Future timestamp | UNKNOWN and block | Validator returns UNKNOWN |
| Disconnected stream | UNKNOWN/FAIL and block | No end-to-end mandatory wiring established |
| Previous runtime generation | UNKNOWN and block | Non-empty generation is checked; current-generation matching is not wired |
| Unconfirmed update | UNKNOWN and block | No verified confirmation contract |
| SQLite integrity unavailable | UNKNOWN and block | No mandatory integrity evidence |
| Reconciliation incomplete | Block conflicting mutations | Partial open-order gate; full reconciliation proof absent |
| Android/backend contradiction | UNKNOWN and block | No cross-runtime consistency contract |

**P0-A: NOT CLOSED.** No approved age budget is invented in this report. There is no safe basis to claim production freshness integration complete.

## 3. ExecutionBarrier call graph and risk-reducing operations

The intended chain is:

`Decision → Risk Gate → Durable Intent → Trusted Preflight → One-shot Permit → ExecutionBarrier → Binance Adapter → Durable Result → Reconciliation`

Source-level path inventory:

| Mutation family | Source-level evidence | Status |
|---|---|---|
| New Spot BUY / entry | Trader/campaign code and barrier intent model exist | Full end-to-end call graph and behavioral proof incomplete |
| Spot SELL to reduce/close LONG | Legacy portfolio paths were changed to fail closed for unsupported emergency contract | Safe exit capability not proven; remains blocked where contract absent |
| Protective order create | Order/campaign execution code exists | All call sites and barrier routing not proven |
| Protective cancel/replace | Not proven as a complete race-safe path | BLOCKED / UNKNOWN |
| OCO/conditional order operations | OCO identifiers are classified by Preflight | Full mutation path and barrier coverage not proven |
| Emergency exit | Generic portfolio emergency path deliberately fails closed | BLOCKED until dedicated contract and tests |
| Background jobs/retries | Full call graph not proven | UNKNOWN |
| Recovery/reconciliation mutations | Full call graph not proven | UNKNOWN; ambiguous outcomes must not resend |
| Android-triggered execution | No complete cross-runtime call graph/evidence | UNKNOWN |
| Legacy/direct adapter calls | No complete repository-wide call graph proof in this pass | UNKNOWN; cannot claim no bypass |

### Open-order gate finding

`_check_orders()` can block all further execution when known Williams orders are unresolved. This correctly prevents new entries but may also prevent risk-reducing cancel/exit actions. The `allow_reconciled_orders` boolean does not itself prove reconciliation and is not an acceptable substitute for a durable, generation-bound reconciliation result.

No permissive exception was added. Without a tested purpose-bound contract that validates exact order/position identity, requested reduction quantity, race state, stable client ID, durable intent, and authoritative reconciliation, the operation remains blocked. This is safer than sending an ambiguous cancel/replace/exit.

**P0-B: NOT CLOSED.**

## 4. Recovery/fault-injection matrix

The following matrix records whether complete evidence for each required scenario was verified at the initial HEAD. “Not verified” does not mean the scenario passed.

| # | Scenario | Verified complete assertions at initial HEAD? |
|---:|---|---|
| 1 | Failure before durable intent | Partial barrier test exists; full crash/restart assertions not verified |
| 2 | Intent write failure | Partial barrier test exists; complete lifecycle assertions not verified |
| 3 | Preflight denial | Partial barrier tests exist |
| 4 | Stale measurement | Validator unit test exists; production mutation integration not verified |
| 5 | Missing measurement | Validator unit test exists; production mutation integration not verified |
| 6 | Source disconnect after preflight | Not verified |
| 7 | Source generation changes | Not verified end-to-end |
| 8 | Exchange accepts order, response lost | Partial ambiguous-result coverage; full recovery/send-count proof not verified |
| 9 | Crash after response before local result save | Not verified |
| 10 | Partial fill with unsaved fills/fees | Not verified |
| 11 | Full fill with unsaved fills/fees | Not verified |
| 12 | WebSocket loss then REST recovery | Not verified |
| 13 | Restart with unresolved intent | Not verified as complete matrix case |
| 14 | Exchange protection exists but no local campaign link | Not verified |
| 15 | Cancel-vs-fill race | Not verified |
| 16 | Ambiguous cancel-replace | Not verified |
| 17 | Repeated reconciliation | Not verified idempotently |
| 18 | Unknown fee | Not verified |
| 19 | Fills/position/order-history disagreement | Not verified |
| 20 | Corrupted SQLite/critical write failure | Write-failure cases partially tested; corruption/restart recovery not verified |
| 21 | Concurrent recovery cycles | Not verified |
| 22 | Conflicting mutation during reconciliation | Not verified |

For every scenario, the required combined assertions—adapter call count, stable `clientOrderId`, authoritative order status, fills/quote cost/fees, local position/campaign, protection, risk reservation, reconciliation result and next-mutation gate—have not been proven across the complete matrix.

**P0-C: NOT CLOSED.** Mock coverage is not live Binance integration.

## 5. Secret audit

- A secret-redaction test file exists and Preflight sanitizes request exceptions by avoiding raw exception text.
- No real credentials were used.
- Synthetic-marker scans across traceback, backend logs, Android logs, SQLite events, Preflight/recovery, HTTP errors/serialized responses, diagnostics exports, test reports and CI artifacts were not run or verified on the initial HEAD.
- Therefore no claim of end-to-end secret safety is made.

**P0-D: UNKNOWN / NOT CLOSED.**

## 6. LONG / SHORT separation

| Mode | Result | Reason |
|---|---|---|
| Spot LONG | UNKNOWN | Lifecycle not proven by this source audit |
| Spot SELL to reduce/close LONG | UNKNOWN | Not a SHORT position |
| Futures Demo/Testnet LONG | UNKNOWN | Futures adapter/lifecycle evidence not established here |
| Futures Demo/Testnet SHORT | UNKNOWN | Futures adapter/lifecycle evidence not established here |

No trading operation was performed. This task does not authorize order placement.

## 7. CI and Testnet gate

- Combined status on the initial HEAD returned no status entries.
- Workflow-run lookup returned no runs for the initial HEAD.
- CI is **UNKNOWN**, not PASS. No test counts or run IDs can be truthfully reported.
- Read-only Spot Testnet verification is **NOT RUN** because local P0 and final-SHA CI are not closed. No mock result is presented as a real network result.

## 8. Implementation decision and acceptance

No production code was changed in this task pass because the critical missing facts are approved freshness budgets, actual producers/generation ownership, and complete mutation/recovery call paths. Inventing these contracts or wiring synthetic timestamps into Preflight would create false evidence and could weaken safety. The next code changes must be made against source-owner interfaces and followed by tests at the exact resulting SHA.

**Final status: BLOCKED / NOT ACCEPTED.**

Required blockers:
1. Wire trusted source-owned measurements for every required component into mandatory Preflight.
2. Obtain explicit approval/source documentation for each freshness budget; otherwise remain UNKNOWN.
3. Bind evidence to current runtime/session generation and reject unconfirmed or contradictory updates.
4. Complete repository-wide mutation call graph and prove every mutation uses the barrier.
5. Implement and test a constrained risk-reducing contract, or retain the block.
6. Complete the 22-scenario fault-injection matrix with all specified state assertions.
7. Run synthetic-secret marker scans across all requested channels and artifacts.
8. Obtain actual CI PASS evidence on one final SHA, including Python, architecture, freshness, recovery, security, Android tests/lint/APK.
9. Only after the preceding blockers close, perform authorized read-only Spot Testnet verification.

No trading authorization is granted by this report.
