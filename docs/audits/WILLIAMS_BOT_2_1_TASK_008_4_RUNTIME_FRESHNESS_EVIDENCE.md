# WILLIAMS BOT 2.1 — TASK 008.4
## Runtime Freshness Integration & P0 Evidence Closure

**Status: BLOCKED / NOT ACCEPTED**  
**Branch:** `audit/williams-2.1-task-008-spot-testnet`  
**Observed HEAD before this audit commit:** `f9294768eb60033f307af3ad6144bcd92a8af737`  
**Baseline:** `d7634adc1f6a1d25ef334294a4903f7c9134e06b`

This document records source-level evidence, not a claim that the P0 requirements are closed. No trading orders, cancellations, replacements, OCO mutations, or Mainnet requests were performed. No read-only Testnet request was made.

## 1. Initial state verification

- Branch exists and resolves to `f9294768eb60033f307af3ad6144bcd92a8af737`.
- Branch is 27 commits ahead of baseline and 0 behind.
- Search for a PR with this exact head branch returned no result; no duplicate PR was created.
- Combined status for the observed HEAD is empty; workflow-run lookup returned no runs. CI is **UNKNOWN**, not PASS.
- `main` was not modified.

## 2. Runtime freshness source matrix

The `runtime_freshness.py` module is a validator, not a measurement producer. `DEFAULT_MAX_AGE_MS` is empty, correctly preventing unapproved thresholds from being inferred. The required components in `PreflightCheckService.REQUIRED_CHECKS` remain mandatory; any component not proven by `verify_all()` remains UNKNOWN and makes `ready=False`.

| Component | Candidate owner/source found in source | Confirmed measurement available to mandatory Preflight? | Approved max age | Generation/session | Gate consequence |
|---|---|---|---|---|---|
| Binance REST | `BinanceSpotClient._request` and REST read methods | Only the synchronous call outcome is checked; no source-bound freshness evidence object is emitted | UNKNOWN / not approved | Not bound | Existing API reachability check alone does not prove all-source freshness |
| Server time | `BinanceSpotClient.sync_time`, `time_offset_ms`; Preflight checks offset and serverTime field | Partial direct check, but no evidence contract/generation | Existing offset policy is not a freshness-age budget | Not bound | Invalid/failed sync blocks; freshness evidence remains incomplete |
| Account / balances | `PreflightCheckService.verify_all` calls authenticated `account()` and checks shape/status | Synchronous response only; no timestamped evidence record | UNKNOWN / not approved | Not bound | Basic account check can PASS, but requested end-to-end freshness contract remains incomplete |
| Closed candles | Binance market-data client and candle persistence | No verified last-closed-candle timestamp passed into mandatory Preflight | UNKNOWN / not approved | Not bound | `candles` stays UNKNOWN; blocks execution |
| Market WebSocket | `ws_hub.py` has connection/message state, including `market_last_message_at` | Not wired as source-bound freshness evidence in Preflight | UNKNOWN / not approved | Reconnect/session generation not validated by Preflight | `market_websocket` stays UNKNOWN; blocks execution |
| Private/User Data Stream | `ws_hub.py` user connection/event handling | Not wired as source-bound evidence in Preflight | UNKNOWN / not approved | User-stream generation not validated | `user_data_stream` stays UNKNOWN; blocks execution |
| Scanner | Scanner modules exist, but a verified heartbeat evidence producer was not located in this source audit | No | UNKNOWN / not approved | Not bound | `scanner_heartbeat` stays UNKNOWN; blocks execution |
| Market Context | `market_context.py`: immutable snapshot has `generation` and `created_at_ms`; per-context candle close time exists | Snapshot timing exists, but no verified production handoff into Preflight freshness evaluation | UNKNOWN / not approved | Snapshot generation exists; invalidation/consumption not wired to freshness gate | `market_context` stays UNKNOWN; blocks execution |
| SQLite / critical local state | `db.py`: SQLite, WAL, synchronous=NORMAL, schema init, execution intent/event persistence | No mandatory SQLite integrity/critical-state freshness proof in Preflight | UNKNOWN / not approved | DB/runtime generation not bound | `storage_integrity` stays UNKNOWN; persistence failure blocks in barrier, but this is not an integrity check |
| Reconciliation | `PreflightCheckService._check_orders` reads open orders and lists and classifies identifiers | Partial open-order read; no completed exchange-vs-local fills/fees/position reconciliation proof | UNKNOWN / not approved | Recovery cycle/generation not bound | Unknown/open Williams orders block new admission; comprehensive reconciliation remains UNKNOWN |
| Diagnostics | Report timestamps are generated at serialization time | No component heartbeat is derived from report creation; correctly not accepted as source freshness | UNKNOWN / not approved | Not bound | `diagnostics_freshness` stays UNKNOWN; blocks execution |
| Android runtime | Android source exists, but no verified backend-consumable heartbeat or generation-bound execution attestation found | No | UNKNOWN / not approved | Not bound | `android_freshness` stays UNKNOWN; blocks execution |

### Negative-case disposition

- Missing measurement: UNKNOWN.
- Stale measurement: validator returns FAIL when an explicitly approved budget is supplied.
- Future timestamp / negative clock skew: UNKNOWN.
- Disconnected source: not currently wired for every component; component remains UNKNOWN rather than inferred healthy.
- Previous runtime generation: validator requires a non-empty generation, but production generation matching/invalidation is not wired.
- Unconfirmed update: no verified source producer exists for every component; remains UNKNOWN.
- SQLite integrity cannot be confirmed: required storage check remains UNKNOWN.
- Reconciliation incomplete: barrier/preflight blocks; no full reconciliation proof is implemented here.
- Android/backend contradiction: no cross-runtime consistency contract found; Android freshness remains UNKNOWN.

**P0-A result: FAIL / INCOMPLETE.** No source can be accepted merely because a module, timestamp field, connection flag, or diagnostics report exists.

## 3. ExecutionBarrier source audit

Verified source behavior:
- `ExecutionBarrier` requires a concrete `PreflightCheckService` bound by object identity to the same client.
- Client must be explicitly pinned to Spot Testnet host `https://testnet.binance.vision`.
- The barrier calls the class implementation of `verify_all`, validates required check names/statuses and report age, and rejects any non-PASS mandatory check.
- Durable intent and admission event are written before the submit callback; failure blocks submission.
- The mutation permit is intended to be one-shot and consumed before network I/O; ambiguous exceptions do not cause a blind retry in the barrier.
- The generic emergency-exit path in `portfolio_trader.py` deliberately fails closed pending a dedicated emergency execution contract.

### Material blockers
1. `verify_all()` deliberately leaves candles, market/private stream, scanner, context, diagnostics, storage integrity, reconciliation, execution-state-known, and Android freshness UNKNOWN. Therefore production execution is blocked until genuine evidence and approved age contracts are wired.
2. `_check_orders()` marks known Williams open orders as requiring reconciliation unless explicitly told they were reconciled. Since the barrier re-runs preflight before each mutation, a blanket open-order condition can prevent risk-reducing cancellation/exit paths too. Do not loosen this by allowing arbitrary callbacks. A purpose-aware, evidence-backed reconcile/admission contract is required so safe cancellation/exit is not silently impossible.
3. Source inspection is not proof of all runtime call paths. Background jobs, recovery, conditional/OCO operations, cancel-replace and Android-triggered execution still require full call graph and behavioral tests at the final SHA.
4. A Python module-level token is an in-process capability convention, not protection against arbitrary code execution inside the process.

**P0-B result: PARTIAL / NOT ACCEPTED.**

## 4. Recovery fault-injection evidence

The existing barrier tests cover selected cases including intent/event persistence failure, duplicate client order ID, preflight missing/stale states, one-shot mutation attempts, and ambiguous post-submit failures. The requested 16-case matrix is not complete or proven at this SHA. In particular, no complete evidence table has been verified for process crashes at every boundary, accepted-but-lost responses, unsaved partial/full fills and fees, WS catch-up, orphan protection, protection-cancel race, ambiguous replace, damaged SQLite, conflicting histories, unknown fees, or competing recovery loops.

Required assertions still outstanding for each scenario: adapter send count, stable intent/clientOrderId, authoritative order state, fills/quote cost/fees, campaign/position/protection, reserved risk, reconciliation state, and whether a conflicting follow-up mutation is blocked.

**P0-C result: INCOMPLETE.** Mock tests, when added, do not establish real Binance integration.

## 5. Secret audit

A secret-redaction test file exists, and Preflight sanitizes exception text by emitting exception type/status/code rather than raw request exception text. This does not prove end-to-end secrecy across all required channels. Synthetic-marker scanning of tracebacks, backend/Android logs, SQLite events, Preflight/recovery, HTTP errors/responses, serialization, diagnostics export and test artifacts has not been verified on this HEAD.

**P0-D result: UNKNOWN / INCOMPLETE.** No real credentials were used in this audit.

## 6. LONG / SHORT evidence

- Spot LONG lifecycle: UNKNOWN; no order operations were performed.
- Spot SELL to reduce/close a long: UNKNOWN; not evidence of SHORT.
- Futures Demo/Testnet LONG: UNKNOWN; outside this Spot-only source check.
- Futures Demo/Testnet SHORT: UNKNOWN; outside this Spot-only source check.
- No order operation was performed as part of TASK 008.4.

**P0-E result: UNKNOWN.**

## 7. CI and Testnet

- The workflow definition `.github/workflows/campaign-ci.yml` includes Python compile, static safety audit, Python safety/campaign tests including runtime freshness, and Android unit tests/lint/debug APK build.
- No verified Actions run/status was returned for the observed HEAD. Workflow evidence must be fetched from an actual run on the final SHA; absence of statuses is not success.
- Read-only Binance Spot Testnet: **NOT RUN**. Local P0 and final-SHA CI are not closed, so the task's own ordering rule forbids starting Testnet verification. No live credentials or network result are inferred.

**P0-F: UNKNOWN. P0-G: NOT RUN by gate condition.**

## 8. Acceptance decision

**BLOCKED / NOT ACCEPTED.**

Do not promote to READY FOR READ-ONLY TESTNET VERIFICATION until:
1. each required runtime source emits trusted, generation-bound measurements and every required age budget has an explicit approved contract;
2. negative freshness evidence blocks mutation through production Preflight/Barrier, with integration tests for every source;
3. the complete mutation call graph and behavioral barrier tests are closed, including a safe evidence-backed path for risk-reducing actions while unresolved orders exist;
4. the full recovery/reconciliation fault-injection matrix passes;
5. synthetic secret markers are proven absent from all requested channels and artifacts;
6. final-SHA CI passes with run IDs and test counts, with safety-relevant warnings resolved;
7. only then, authorized read-only Spot Testnet verification is completed.

No acceptance claim is made by this report.
