# WILLIAMS BOT 2.1 — TASK 006
## Android Autonomous Runtime & Operational Reliability Audit

**Status:** IMPLEMENTATION CHANGES PREPARED — CI, APK and physical-device acceptance pending  
**Target:** Binance Spot Testnet only  
**Main baseline:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Working branch:** `audit/williams-2.1-task-006-android-runtime`  
**Safety invariant:** no Mainnet endpoint was introduced and no real exchange order was submitted during this audit.

## Scope inspected

The Android path was traced through `MainActivity.kt`, `StandaloneRuntime.kt` and its private `NativeEngine`, `TradingForegroundService.kt`, `ExecutionGate.kt`, `BinanceUserDataStream.kt`, `AndroidRuntimeHealth.kt`, local SQLite history/audit stores, EncryptedSharedPreferences, `AndroidManifest.xml`, Gradle configuration, Android unit tests and CI workflows. Runtime control is implemented on-device; the optional remote backend is not the required trading authority.

At baseline the foreground service and loopback API existed, but their presence alone did not establish operational reliability. In particular, the local control API did not authenticate callers, local server request handling spawned an unbounded thread per connection, a stale server singleton could survive listener startup failure, Android status widgets could keep showing a previous successful snapshot after refresh failures, and several self-diagnostic checks tested constants rather than current runtime facts. Some trading snapshots and credentials used asynchronous preference writes without read-back verification.

## Corrections prepared on this branch

### Local control bridge and duplicate-worker protection

- The loopback server remains bound to `127.0.0.1:18080`; it now requires a per-install random bearer token stored in the encrypted native preferences. Authorization compares bytes in constant time. The token is not included in diagnostic output and is not the remote-backend token.
- Request handling uses a bounded worker pool, socket read timeout, and a 1 MiB request-body ceiling. Requests without a valid bearer token receive HTTP 401 before routes (including control routes) are reached.
- Runtime server startup is synchronized and verifies the listener/accept thread before publishing the singleton. A failed startup disposes that candidate server.
- A server teardown now disposes the native engine's REST probe and owned scan, history-backfill, indicator and execution worker pools rather than leaving daemon workers running after a runtime recreation.
- Native engine creation is synchronized through the server instance to reduce the risk that concurrent dashboard requests create multiple engines/probes.

### Truthful data and execution readiness

- Freshness is explicit: REST market data must be present and at most 30 seconds old; a scan snapshot must have an allowed state, no scanner error, valid timestamp and be at most 60 seconds old. Unknown timestamps and clock reversal fail closed.
- Stale/failed status or scanner reads no longer keep old “green” state or candidates on the main UI. Cached prices and PnL are suppressed after the market freshness gate expires. Portfolio valuation returns unknown/null when current mark or account data cannot be established rather than substituting entry price.
- New execution readiness requires the user stream to be connected and reconciled; the gate reports separate blockers for disconnection and pending stream synchronization.
- UI control feedback interprets runtime results, execution blockers and reconciliation states instead of unconditionally presenting every successful HTTP response as a successful trading start.
- The diagnostic self-tests check the current listener, token contract, endpoint hosts, status/data freshness and execution blockers rather than treating the fixed port or Testnet mode constant as proof of a healthy runtime.

### Credentials, persistence, pause/restart and recovery

- Binance credentials are committed synchronously and read back. Validation failure rolls back the previous values and verifies the rollback. Failure to durably delete credentials is surfaced rather than reported as a successful clear.
- Position and pending-entry snapshots are committed and read back. If stored JSON cannot be decoded, runtime startup persists a reconciliation-required/autostart-off latch where possible and remains fail-closed.
- The reconciliation latch is not cleared until pending orders, exchange positions and managed open orders are recovered/audited successfully. A failed durable reconciliation write is a safety incident.
- Start, pause, resume, stop and kill paths use durable state for their safety intent. Kill-switch reset is not reflected in memory until its persisted latch is cleared and read back. Kill reports whether latch persistence was verified.
- Android lifecycle diagnostics capture SDK/model, foreground-service visibility where available, battery/power restrictions and a permission-safe standby-bucket probe. The foreground notification now says that the service is alive and Testnet-only, not that execution is necessarily active. Autostart recovery is moved off the service main lifecycle thread.

### Build contract and tests added

- The release contract gate expected version `4.24.0` while `server.py`, Gradle and Android source declared `4.25.0`. The task branch aligns the gate to `4.25.0` and asserts presence of key Android safety contracts.
- The first Android CI run failed before unit tests because required Android Lint flagged the wrapper's Gradle `8.14.5` as behind the available `8.14.6`. The task branch now aligns `gradle-wrapper.properties`, `.github/workflows/android-apk.yml` and `.github/workflows/campaign-ci.yml` to `8.14.6`. A fresh CI run against the new head is the acceptance check; the earlier lint failure remains recorded as a resolved configuration issue, not a passing build.
- Added pure unit tests for local bearer authorization, backend URL validation, and runtime freshness boundary/error cases. Existing unit tests and Android workflow remain the authoritative compile/test gate.

## Acceptance matrix

| Criterion | Current result | Evidence still required |
|---|---|---|
| Main is unchanged | PASS by branch workflow: edits targeted the task branch; baseline comparison is against `04da5f9c09572e99ff310be3a1908179165e528c` | Recheck branch diff/PR base SHA before merge |
| Standalone on-device architecture | Source path present; corrections prepared | Successful CI compile and APK installation |
| Local API access control | Implemented; unit tests added | Unit-test results from CI and Android same-device smoke test |
| Freshness and stale-data fail-closed behavior | Implemented; pure contract tests added | Unit-test results plus offline/market-outage runtime exercise |
| Credentials/persistence read-back | Implemented in native preference paths | Testnet credential setup, failed validation/clear, process death, upgrade and clean-install device tests |
| Recovery and duplicate-worker cleanup | Source changes prepared | Restart/force-stop/process-kill cycles and proof that only one engine/probe/scanner is alive |
| Android foreground/background reliability | Manifest, sticky FGS, notification and diagnostics inspected/hardened | Long-duration physical-device test with screen off, power saving, system process kill, app swiping and notification permission states |
| Python/Android golden-vector parity for Alligator, AO/AC, fractals, confirmations, risk, structural stops, campaign transitions/recovery | **NOT VERIFIED in this task run** | Execute the canonical golden vectors on both implementations; do not alter expected values without source/spec evidence |
| Unit tests | Python backend: **PASS, 148 passed** on the prior tested head; new Android unit tests are being re-run on the Gradle-corrected head | Green `:app:testDebugUnitTest` and relevant Python test workflows on the exact final head |
| Static checks and APK | **PENDING**; earlier Android lint run failed because Gradle wrapper was `8.14.5`, now aligned to `8.14.6` in wrapper and workflows | Green release gate, Android lint, debug/release APK build and artifact inspection on the exact final head |
| Physical Android acceptance | **NOT RUN** | Record device model, Android version, APK SHA, launch, background soak, network loss, restart and diagnostics |
| Mainnet/real orders | NOT used | None; keep disabled |

## Required Testnet/device sequence before calling this accepted

1. Pass the exact branch commit through the Android and Python CI workflows: release contract, static checks, unit tests, lint and APK build.
2. Install the built APK on a physical device and record device/OS/APK SHA. Check first launch with no credentials; save an explicitly Testnet-only key and verify read-back; reject invalid keys without losing the old values; clear credentials and verify absence after process restart.
3. On Testnet, exercise start → history warm-up → user-stream synchronization → execution-gate blockers; pause/resume must reconcile before returning to execution-ready. No real order or Mainnet endpoint is permitted.
4. While stopped, disable network; while running on Testnet, disconnect network/WebSockets, wait past the freshness threshold and verify stale prices/candidates disappear and execution is blocked. Restore network and verify a fresh REST snapshot, history readiness and user-stream reconciliation are all required before resuming.
5. Test ordinary STOP, PAUSE, application process death, system process reclaim, service recreation, force-stop, device reboot, upgrade install and clean install. Verify durable intents, recovery-required behavior, no duplicate REST probes/scanners and no unprotected or ambiguously reconciled position.
6. Run existing canonical Python/Android strategy golden vectors and compare numerical and transition outputs, including fractal confirmation timing, signal cancellation, sizing, structural stops, campaign events and recovery. A matching build is not a substitute for this check.

## Explicit limitations

No physical Android device was available through this audit session, and the Android Gradle build/unit tests were not run here. Therefore this report does **not** claim a completed APK validation, hardware soak test, successful end-to-end trading runtime, golden-vector parity, or readiness for real trading. A build/test success is necessary but does not close the device acceptance matrix above.

One high-risk scenario remains to prove under controlled Spot Testnet testing: if an order is already accepted by the exchange and the user stream disconnects before its fill event is consumed, the newly tightened readiness gate blocks new execution, but acceptance still requires demonstrating reliable reconciliation/protection of that in-flight order after the stream returns. Do not enable live trading until this and all recovery/device tests pass.


---

## Follow-up — durable execution-intent recovery, exact-head gates, and remaining acceptance blockers

### Scope and safety
This follow-up continues only on `audit/williams-2.1-task-006-android-runtime` and PR #46. It does not modify `main`, merge the PR, enable Mainnet, or send exchange mutations. Recovery tests use a local SQLite database and a fake exchange only.

The production recovery implementation source is `0d25d7337225563c83a58dca756f3e5382c9435b`; this final validation commit adds two more offline regressions (unknown OCO-create result and full portfolio-recovery fail-closed behavior) plus audit/workflow metadata. The audit/CI-trigger commit adds this report and comments to workflow files to force all required checks to run again on one exact candidate SHA. **Do not use the earlier CI results below as acceptance evidence for the final candidate SHA; use only workflow runs whose `head_sha` exactly equals the PR's final head.**

### Recovery resolver implementation
- Added `execution_recovery.py`, a read-only resolver. It never invokes order mutations.
- Supported mutation kinds: normal order, emergency exit, OCO create, single-order cancel, OCO cancel, and cancel-replace.
- Durable execution intents now store mutation kind and target exchange identity fields: order ID/client order ID, order-list ID/list client order ID, and replacement client order ID.
- SQLite schema migration adds those fields and an atomic `resolve_execution_intent` operation. Order snapshots, terminal intent status, and `EXCHANGE_RECONCILED` evidence are committed in one transaction.
- `SUBMIT_ATTEMPT_STARTED` is durably recorded before invoking the mutation callable. If that marker exists and exact exchange evidence is missing, the intent remains unresolved and the global barrier stays fail-closed.
- A pre-submit intent can be cleared only when the marker is absent and Binance explicitly returns the exact-ID not-found code for a supported create operation. A missing replacement order in cancel-replace is not treated as proof of rejection; it remains blocked.
- The resolver is invoked before portfolio/campaign recovery. The stale-symbol repair path now refuses to clear a symbol barrier while an execution intent for that symbol remains unresolved.

### Recovery test matrix
The new offline tests cover:
- lost POST response with exact `clientOrderId` lookup and no second mutation;
- partial fill persistence as exchange authority;
- unknown order outcome remains blocked;
- OCO create requires an exact list identity and both confirmed legs;
- cancel uses the target exchange order ID, not the cancel-intent ID;
- cancel-replace requires both the old order and replacement order;
- unknown/missing replacement remains blocked;
- OCO cancel requires exact list identity and confirmed leg states;
- local SQLite write failure rolls back resolution and preserves the unresolved intent;
- explicit exact-ID not-found can resolve a pre-submit crash only when the durable submit-attempt marker is absent;
- the same reconciled intent cannot be replayed;
- stale per-symbol reconciliation state cannot be cleared while an unresolved intent exists;
- AST inventory asserts known production Binance mutation call sites in `trader.py`, `campaign_execution.py`, and `portfolio_trader.py` are enclosed by the barrier/submission helper;
- synthetic secrets are exercised through the local ASGI HTTP error response and diagnostic/SQLite redaction tests.

### Production mutation-path inventory
| Call site | Barrier path | Durable intent identity | Recovery behavior | Evidence/tests |
|---|---|---|---|---|
| `Trader.market_buy` → `BinanceSpotClient.order` | `ExecutionBarrier.execute` | client order ID + write-ahead events | exact order lookup; missing/ambiguous after submit stays blocked | execution-barrier durability, lost-POST and call-site inventory tests |
| `Trader.place_oco` → `create_oco_sell` | `ExecutionBarrier.execute` | OCO list client ID | exact list lookup + both leg order lookups | OCO resolver tests |
| `CampaignExecutionService` entry/exit/protection/trail | `_submit` → barrier | order CID; cancel target ID; cancel-replace old ID + replacement CID | order lookup; cancel target lookup; both cancel-replace legs required | recovery resolver tests |
| `MultiPositionTrader` entry/manual exit/emergency exit/OCO create | `_submit_order` → barrier | order CID; emergency exit CID; OCO list CID | exact order/list lookup; no automatic replay | resolver and lifecycle tests |
| `MultiPositionTrader.manual_sell` OCO cancel | `_submit_order` → barrier | target order-list ID and/or list client ID | exact list + leg lookup; never reissues cancel | OCO-cancel resolver test |
| `BinanceSpotClient.cancel_open_orders` | No production caller found in the inspected call-site inventory | No intent supplied by an external caller | Treat any future use as a blocker until wrapped by the barrier or removed | static inventory; no direct production caller identified |

This inventory is code/static evidence, not a proof of every runtime interleaving. The resolver intentionally leaves unsupported mutation kinds or incomplete identity/evidence blocked.

### Exact-head validation and APK evidence
The earlier candidate `62cf3e9db785d72a7911cba36887941abdfb3570` had Python backend CI `182 passed, 4 warnings` and Campaign CI Python `193 passed, 4 warnings`. Those results are historical only because a later fail-closed stale-symbol-barrier fix and additional test were committed afterward.

The final audit/CI-trigger commit changes this document and workflow comments only; it forces Python backend CI, Campaign CI, and the Android APK workflow to execute against the same final head. Record acceptance results only from those exact-head runs. APK version metadata, checksums, and signature status are **NOT VERIFIED for the final candidate until its own Build Android APK workflow finishes and its artifacts are downloaded and inspected**. The expected release output is intentionally named `app-release-unsigned.apk`; do not describe it as signed. The debug APK is accepted only if the workflow's `apksigner verify` and certificate-digest comparison both succeed.

### Remaining blockers — do not accept
- Full physical-device execution has not been performed. Android logs, backup contents on a device, power/Doze/background behavior, process-kill/restart behavior, and hardware recovery remain **NOT VERIFIED**.
- WebSocket loss/freshness and user-stream REST catch-up have unit/integration coverage, but a single end-to-end test that joins lost WebSocket → SQLite/FSM → campaign/order/protection recovery after process restart is not yet proven.
- Unknown cancel-replace outcomes where one leg is absent deliberately remain blocked; there is no automatic retry.
- A complete runtime proof that every conceivable external/plugin caller is unable to reach the low-level Binance mutation client without the barrier is not established by static inventory alone.
- Do not enable live trading or remove any fail-closed guard based on CI alone.

**Acceptance status: BLOCKED — NOT ACCEPTED.** Keep PR #46 open as Draft, keep `main` unchanged, and do not enable Mainnet or send real orders.


### Additional fail-closed correction before final validation
The resolver was tightened after review of cancellation semantics:
- A single-order cancel is not resolved while the target order remains `NEW` or `PARTIALLY_FILLED`; it stays globally blocked until the target is in a terminal, exchange-confirmed state.
- An OCO cancel is not resolved while the list remains `EXEC_STARTED`/`EXECUTING`, or while either leg remains live/partially filled.
- Cancel-replace is not resolved while the original order remains live, even if a replacement order is also visible.
- Added regressions for each live/ambiguous case. They assert the intent stays unresolved and no mutation method is called.
These changes are included in the final validation candidate; results from SHA `50465d88a5cbc6c83e4a01d824c36e905be41fec` are not acceptance evidence for this updated code.



### Final additional recovery regressions
- Unknown OCO-create result after the durable submit-attempt marker is present stays unresolved when Binance cannot confirm the exact list; no OCO retry is called.
- Full `MultiPositionTrader.recover()` integration with an unknown order result preserves both `position_state:BTCUSDT=RECONCILE_REQUIRED` and the unresolved SQLite intent; the recovery report remains blocked and records zero mutations.


---

## TASK 006.4 — Financial Integrity & Global Recovery Gate (in progress)

**Status: BLOCKED — NOT ACCEPTED.** This section records partial implementation; it does not claim complete financial recovery.

### Changes made on the task branch

- `execution_accumulator.py`: fill parsing now rejects missing/invalid quantities, prices, commission amounts/assets and trade IDs rather than silently skipping them. Repeated IDs within a response are rejected. Executed order totals must match the per-trade quantity and quote totals. Non-zero third-asset commissions require an explicit positive conversion rate.
- `portfolio_trader.py`: the authoritative execution path preserves Binance trade IDs from `myTrades`; an unavailable trade-history lookup or executed order without per-trade fills now raises and remains unresolved rather than falling back to order-level aggregates as if commissions were known.
- `db.py`: schema version 8 adds exchange/account/trade identity, commission amount and financial state to `campaign_fills`, plus a partial unique index on `(exchange, account_id, symbol, trade_id)`. Existing rows are preserved and marked `LEGACY_UNVERIFIED`; the migration does not pick or delete a duplicate. Fill insertion is idempotent for an identical identity and fails closed on conflicting financial fields.
- `execution_barrier.py`: new Spot BUY exposure is blocked unless persisted `recovery_status=RECOVERY_COMPLETE`. SELL-side exit/reduction intents are not rejected by this entry-only gate.
- `portfolio_trader.py`: startup recovery persists `RECOVERY_REQUIRED` before recovery I/O and does not clear it merely because an individual intent is `RECONCILED`.
- `test_financial_integrity.py`: added regression coverage for complete/zero/unknown/invalid commissions, duplicate/conflicting trade IDs, aggregate mismatch, DB idempotence, cross-connection concurrent insertion, and entry-vs-exit gate behavior. Added this suite to Python backend CI and Campaign CI.

### Important remaining blockers

1. The global gate intentionally remains `RECOVERY_REQUIRED`: the current recovery path has not yet established a single authoritative, end-to-end proof for account balances, managed position, all fills/fees and protective orders. It must not be changed to `RECOVERY_COMPLETE` until those proofs are implemented and persisted.
2. The idempotent `campaign_fills` ledger is now available, but the production execution/campaign flow has not yet been fully wired to persist every authoritative exchange trade into this ledger before applying all financial aggregates. This prevents claiming end-to-end exactly-once PnL/position accounting.
3. Existing legacy fill rows have no trustworthy exchange trade identity; they remain `LEGACY_UNVERIFIED` and need a diagnostic/reconciliation path before they can support entry admission.
4. Android's native runtime still needs an audited, durable contract that consumes this Python/global recovery state (or an equivalent on-device reconciliation proof) and preserves it across process death. JVM CI is not physical-device evidence.
5. The required full fault matrix, real two-process recovery concurrency, balance/position/protection reconciliation, Android process-death test, and controlled Spot Testnet run remain **NOT VERIFIED** until actual test results are recorded.

### Validation policy

All CI results must be re-fetched for one final branch SHA after code changes. No Mainnet calls, live orders, or PR merge are permitted. Keep PR #46 open as Draft and keep new-entry admission blocked until the remaining proof obligations are closed.


---

## TASK 006.5 — Global Recovery Gate, Financial Reconciliation & CI Closure
**Audit snapshot:** 2026-10-11  
**Acceptance:** BLOCKED — NOT ACCEPTED  
**Source branch HEAD checked:** `35f299c86d9faf757ecaabc558757598b8d80f91`  
**PR:** #46, open Draft, base `main` at `04da5f9c09572e99ff310be3a1908179165e528c`.

### Verified repository and CI facts

- The PR head is still `35f299c86d9faf757ecaabc558757598b8d80f91`; PR #46 is open, Draft, not merged. No branch or PR was created by this audit.
- TASK 006.4 edits are present: strict `execution_accumulator.py`, SQLite `BEGIN IMMEDIATE`, `campaign_fills` exchange/account/symbol/trade ID uniqueness, conflict latching, entry-only barrier check, and offline fill-history fixtures.
- Exact-head workflow runs associated with this candidate:
  - Python backend tests run **38091128215**: **FAILURE**; step `Run safe offline tests` failed. The job summary is **204 passed, 5 failed, 4 warnings**.
  - Williams Campaign CI run **38091128205**: **FAILURE**; Python job **215 passed, 5 failed, 4 warnings**; Android build/static-check job **SUCCESS**.
  - Build Android APK run **38091128238**: **SUCCESS**.
- Workflow links: [Python backend tests](https://github.com/19wolfalone92/Williams/actions/runs/38091128215), [Williams Campaign CI](https://github.com/19wolfalone92/Williams/actions/runs/38091128205), [Build Android APK](https://github.com/19wolfalone92/Williams/actions/runs/38091128238). These are the runs fetched for source head `35f299c86d9faf757ecaabc558757598b8d80f91`; job logs show GitHub checked out the PR merge ref, so the final acceptance check must re-confirm the associated PR head SHA and all required workflow conclusions together.

### Confirmed blockers from source and test evidence

1. **Global completion proof is incomplete.** `MultiPositionTrader.recover()` durably sets `recovery_status=RECOVERY_REQUIRED`, invokes the read-only intent resolver, and checks unresolved symbols/pending entries/intents. It does not establish a global proof covering all fills, fee valuation, balances, positions/campaigns, risk reservations and actual protective coverage before transitioning to `RECOVERY_COMPLETE`. This is safely fail-closed but not an operationally complete recovery contract.
2. **Intent-level resolution remains narrower than financial recovery.** `ExecutionRecoveryResolver.resolve_pending()` writes order snapshots and resolves intent state in a transaction; its output concerns mutation-intent/exchange-order evidence. It does not itself prove fill-history completeness or downstream financial effects. An intent marked `RECONCILED` must not be used as global completion evidence.
3. **Exactly-once ledger integration is not yet proven end-to-end.** `db.py::save_campaign_fill` uses SQLite rows and identity checks, but stores key financial columns as SQLite `REAL` and parses them through Python `float`. The existing unique index and transaction are useful safeguards, not proof of exact Decimal preservation or atomic application to fill ledger + position + campaign + risk reservation.
4. **Fill-history authority is incomplete across existing paths.** `accumulate_order()` correctly rejects executed totals without authoritative per-trade fills, but production callers and historical recovery paths still require proof that every fill is persisted and financially applied exactly once. A terminal order status is insufficient.
5. **CI has real failures, not just pending results.** Failures reported on this candidate include:
   - `test_multi_position_execution.py::test_multi_position_execution_creates_independent_trades` — one symbol was marked `order_fill_totals_mismatch`, so only one of two positions was created.
   - `test_multi_position_lifecycle.py::test_partial_manual_sell_keeps_residual_position_protected` — expected partial-sell lifecycle did not complete.
   - `recovery_test.py::test_recovery_suite` — legacy synthetic executed order lacks authoritative fill history and is rejected.
   - two `test_integration_bindings.py` accumulator tests fail because their synthetic fill rows omit required `tradeId`.
   These must be corrected by making fixtures faithfully model authoritative exchange responses, or by fixing a production defect if the data is already authoritative. Do not weaken fail-closed assertions.
6. **Balance/position/protection reconciliation is not globally closed.** The current recovery return path is based on unresolved symbols, pending entries and intents. It does not provide a single durable proof object demonstrating reconciled account balances, fill-derived inventory, campaign PnL, risk reservation, and actual OCO/stop coverage including partially filled legs.
7. **Android global-gate parity is not proven.** Android source contains a native execution gate/runtime, but this audit has not demonstrated that all Android entry paths consume a durable, equivalent global recovery completion contract across process death. Physical process-death remains NOT VERIFIED.
8. **Cross-process recovery and controlled Testnet remain unverified.** `BEGIN IMMEDIATE` improves write serialization; it is not itself a test of two independent recovery processes, crash points, fill effect atomicity, protection races, or restart closure. Spot Testnet was not exercised.

### Entry/exit path inventory — evidence status

| Production path | Current observed guard/path | Remaining proof gap |
|---|---|---|
| `MultiPositionTrader.execute()` → `execute_campaign()` or legacy selection path | Campaign path submits through `CampaignExecutionService._submit()` → `ExecutionBarrier.execute()`; legacy orders use `_submit_order()` → barrier. | Need path-by-path test showing blocked global recovery prevents every entry, including lower-level/direct invocation. |
| `CampaignExecutionService.arm_initial_entry()` | Requires the canonical barrier; entry order mutation is submitted through `_submit()`. | Gate relies on persisted `recovery_status`; no completed global proof producer exists yet. |
| `Trader.market_buy` / OCO and other mutation paths | Prior static inventory identifies barrier-wrapped submission helpers and durable intent resolver. | Static inventory alone does not prove no unlisted direct caller or all crash interleavings. |
| `ExecutionRecoveryResolver.resolve_pending()` | Read-only exact-identity order/list reconciliation; no blind retry. | Resolves individual intents only; not a global financial-recovery proof. |
| Android `StandaloneRuntime` / native execution gate | Runtime readiness checks and durable-state mechanisms exist. | Need explicit Android entry-call inventory, process-death test, and parity with global financial gate. |
| Emergency/manual SELL, cancellation, protection repair | Designed to route through barrier with entry-only global check; UNKNOWN mutation outcomes remain unresolved. | Need tests proving safe reduction/protection remain possible without bypassing order-state, idempotency and barrier checks. |

### Required next corrections

1. Implement one durable global recovery contract and only allow entry admission after all required order, fill, fee, balance, position/campaign, risk and protection checks have completed. Keep any unknown stage blocked.
2. Replace float-based financial persistence/calculation on the fill-ledger path with validated decimal-string/Decimal handling and a migration strategy that preserves legacy rows and blocks unverified identities.
3. Make fill-ledger insertion and financial-effect application atomic/idempotent across the campaign/position/risk state; conflicting identity must durably latch recovery before surfacing the error.
4. Fix the five observed test failures using authoritative synthetic trade history and order-specific fixture lookups; retain rejection of missing trade IDs and missing fills.
5. Add the requested independent-process recovery/crash tests and verify every Python and Android entry path against the same durable gate.
6. Re-run every required workflow after the fixes and record the same final source SHA, run IDs and conclusions. Physical Android process-death and Binance Spot Testnet remain **NOT VERIFIED** until actually performed.

**Decision for TASK 006.5:** BLOCKED — NOT ACCEPTED. No Mainnet use, real trades, PR merge, or `main` changes.


### TASK 006.5 implementation update — 2026-10-11

After the audit above, the following narrow corrections were committed on the same task branch:

- `trader.py`: the authoritative execution helper now routes inline or `myTrades` fills through `accumulate_order()`, ensuring per-trade totals are checked against the exchange order's executed quantity and cumulative quote quantity instead of accepting a non-empty fill list by itself.
- `execution_accumulator.py`: an unknown/unvalued commission asset now blocks even when the reported commission amount is zero. A zero numeric fee does not establish that an unknown asset is correctly classified.
- `test_financial_integrity.py`: added a regression for zero commission with an unknown asset.
- `test_integration_bindings.py`: accumulator fixtures now include authoritative trade IDs and order totals.
- `test_multi_position_execution.py`: the synthetic exchange response now keeps rounded executed quantity, cumulative quote amount, and fill-level quote quantity consistent.
- `test_multi_position_lifecycle.py`: partial manual-exit fixture provides explicit synthetic trade history rather than relying on aggregate order status.
- `recovery_test.py`: offline recovery client now exposes per-order synthetic trade history with stable identities and explicit fee fields.
- This report was extended with the verified 006.5 blockers and path inventory.

**Latest source head before the report refresh:** `18071fdee99c792a7c5752b85f284b8f070e1948`. The current source head must be re-read after this report commit. Workflow runs for the latest code commits were still queued or cancelled due to subsequent pushes at the time of this update; therefore the five earlier failures are known evidence, and no post-fix green result is claimed. The 006.5 acceptance status remains **BLOCKED — NOT ACCEPTED** until the exact final head's required workflows complete successfully and the global financial-recovery proof is implemented.

No production gate was bypassed and no Mainnet/live trading was used.


---

## TASK 006.6 — Global Recovery Closure, Atomic Financial Ledger & Decimal Integrity
**Status: BLOCKED — NOT ACCEPTED**  
**Task baseline:** `77a7f1d4abb1c0bbd44aeb31a29557733cf41795`  
**Source candidate before this report update:** `8789031d10ade43760d2ebef438db7ef6873182d`  
**Base:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**PR #46:** Open, Draft, unmerged.

### TASK 006.6 changes made on the existing branch

1. **Canonical exact-decimal fill evidence.** Added `exchange_fill_ledger`, keyed by `(exchange, account_id, symbol, trade_id)`, with quantity, price, quote quantity and commission persisted as validated decimal strings. Canonical input rejects binary `float`, booleans, malformed/non-finite values, missing order identity, invalid side and unknown commission fields.
2. **Legacy table demotion.** `campaign_fills` is now explicitly a compatibility/reporting projection. Its `REAL` columns are not the exact-value source of truth. The canonical ledger and projection are written within the same SQLite transaction; a failed projection insert rolls back the canonical insert.
3. **Effect state remains pending by design.** A new canonical fill starts with `effect_state='PENDING'`. No production financial-effect applier has been proven to atomically update position, campaign/PnL, fees and risk reservations, so the code does not falsely mark it `APPLIED`. `Database.pending_exchange_fill_effects()` exposes pending rows, and `MultiPositionTrader.recover()` now refuses a clean recovery result while any such effect remains pending.
4. **Fail-closed legacy migration.** Existing `campaign_fills` rows without matching canonical decimal evidence are preserved and recorded in `legacy_fill_decimal_migration`; the persistent global gate is set to `RECOVERY_REQUIRED`. The migration does not invent trade IDs or reconstruct exact values from SQLite `REAL`.
5. **Android entry gate.** Added `GlobalRecoveryGateContract` and wired native execution readiness, campaign-entry preflight and the final campaign mutation boundary to it. The runtime persists `global_financial_recovery_complete=false` on state load and at recovery start. Because a full Android-side financial proof producer is not implemented, the runtime intentionally does not claim recovery success and new entries remain blocked. Existing safe recovery/reduction routes are not reclassified as entry permission.
6. **Regression tests.** Added tests for high-precision decimal preservation, rejection of float inputs, atomic rollback when the compatibility projection fails, legacy-row migration blocking, pending financial effects and the Android gate contract.

### Important limitation — exactly-once financial application is still not closed

The canonical ledger prevents identity duplication and preserves source decimals, but it is **not** yet an exactly-once financial-effect engine. No complete transactional applier currently moves each fill through fee valuation → position → campaign/PnL → risk reservation → protection quantity and then marks the effect applied. This is deliberately represented as `PENDING`; it must not be bypassed or manually changed to `APPLIED`.

The global recovery contract also still lacks one end-to-end authoritative proof for order/fill history completeness, quote/base/third-asset fee valuation, exchange balances versus locked funds and residual inventory, campaign PnL, risk reservations and actual OCO/protective coverage. Python's broader campaign/position models still contain legacy `REAL` / `float` fields; the new canonical fill ledger does not prove that all downstream financial decisions have been migrated to decimal strings.

### Android proof status

- Android code gate: **IMPLEMENTED, fail-closed** while global financial proof is absent.
- JVM unit test for the isolated gate contract: **ADDED; CI result pending for this candidate**.
- Mutation-spy proof across every Android network sink: **NOT VERIFIED**.
- Physical Android process-death/restart: **NOT VERIFIED**.
- Spot Testnet authenticated read-only gate: **NOT RUN in this task update**; the workflow requires an explicit dispatch and configured Testnet secrets.
- Futures mode/position-mode accounting: **NOT VERIFIED** and must remain disabled for unproven scenarios.

### CI evidence and exact-SHA discipline

- The earlier baseline `77a7f1d4abb1c0bbd44aeb31a29557733cf41795` had successful Python backend, Campaign CI and APK workflows (runs `38115784703`, `38115784711`, `38115784741`). These are **baseline only**, not proof for TASK 006.6 changes.
- On intermediate candidate `4a7af9e9eeb70891469cb15e63f488fd20ea4029`, Python backend run `38116775884` failed **3 tests / 215 passed** because existing tests expected a generic financial validation error while the new Decimal parser emitted precise field-specific errors. The tests were updated to assert those specific errors; this was not a production-gate relaxation.
- At the time of this report addition, exact-head checks for candidate `8789031d10ade43760d2ebef438db7ef6873182d` were queued:
  - [Python backend tests — run 38116861911](https://github.com/19wolfalone92/Williams/actions/runs/38116861911) — QUEUED
  - [Build Android APK — run 38116861915](https://github.com/19wolfalone92/Williams/actions/runs/38116861915) — QUEUED
  - [Williams Campaign CI — run 38116861971](https://github.com/19wolfalone92/Williams/actions/runs/38116861971) — PENDING
- The report commit itself creates a new candidate SHA. Re-fetch the branch head and verify all mandatory workflow conclusions against that exact final SHA before making any acceptance decision.

### TASK 006.6 acceptance matrix

| Requirement | Status | Evidence / blocker |
|---|---|---|
| Current PR/base/branch verified | PASS | PR #46 remains open Draft, base unchanged |
| Canonical decimal fill identity | IMPLEMENTED; CI PENDING | `exchange_fill_ledger` and decimal-string tests |
| Unknown fee is not silently zero | PASS in accumulator contract | Existing strict accumulator; broader fee valuation still incomplete |
| Atomic ledger + compatibility projection | TEST ADDED; CI PENDING | Trigger-injected rollback regression |
| Exactly-once downstream financial effect | FAIL / NOT IMPLEMENTED | No atomic position/campaign/PnL/risk effect applier |
| Global Python recovery proof | BLOCKED | Orders/fills/fees/balances/positions/risk/protection not closed end-to-end |
| Populated v6/v7/v8 migration matrix | NOT VERIFIED | Only a focused legacy-row migration regression added; supported-version fixtures still required |
| Two independent OS-process recovery matrix | NOT VERIFIED | Existing thread-level race test is not cross-process proof |
| Fault injection at all required crash points | NOT VERIFIED | Only a projection-write rollback injection added in this update |
| Android entry gate fails closed | IMPLEMENTED; CI PENDING | Native contract and unit test; no global proof producer |
| Android mutation-spy / physical process-death | NOT VERIFIED | Requires broader test harness and real device |
| Spot Testnet read-only gate | NOT RUN | Explicit workflow dispatch/secrets required |
| Futures accounting | NOT VERIFIED | Separate contract/test matrix required |
| All mandatory CI on final SHA | PENDING | Recheck after report commit |

**TASK 006.6 decision: BLOCKED — NOT ACCEPTED.** Do not set `RECOVERY_COMPLETE` or `effect_state='APPLIED'` without the missing proof. No `main` changes, no merge, no Mainnet or live orders.


### TASK 006.6 follow-up — Python entry barrier, supported legacy schemas, exact-head recheck

Further changes after the first TASK 006.6 report section:

- `execution_barrier.py` now checks both the durable `recovery_status` and the canonical fill ledger before admitting a Spot BUY. A missing/unreadable ledger API or any row whose `effect_state` is not `APPLIED` blocks the entry even if a status string was manually changed to `RECOVERY_COMPLETE`.
- `test_financial_integrity.py` adds a regression proving that a manually set completion string cannot override a pending canonical financial effect.
- Added populated legacy-database migration tests for schema versions 6, 7 and 8. They preserve the existing row, create no fabricated canonical fill, upgrade the schema version, and leave recovery blocked with a diagnostic until explicit reconciliation.
- Added a cross-platform contract test confirming that Android cannot admit entries with an absent global financial proof even when the legacy reconciliation flag is clear.

**Interim exact-code candidate evidence (not the current final SHA):** on `ffc6951e3da515f031fc8ad6c40483b33edc19cf`, Python backend run [38116951301](https://github.com/19wolfalone92/Williams/actions/runs/38116951301) completed **SUCCESS — 219 passed, 4 warnings**; Campaign CI Python job run [38116951243](https://github.com/19wolfalone92/Williams/actions/runs/38116951243) completed **SUCCESS — 230 passed, 4 warnings**. On that same SHA, APK run [38116951232](https://github.com/19wolfalone92/Williams/actions/runs/38116951232) and Campaign CI Android job were still **IN_PROGRESS** at the last check. The populated v6/v7/v8 migration tests and the last report/code commits postdate this interim candidate, so these successes do not prove the final SHA.

**Revalidation rule:** after this report commit, re-fetch the actual PR head and verify Python backend, Campaign Python, Campaign Android and APK conclusions on that exact SHA. Keep TASK 006.6 blocked if any required job is pending, failing, cancelled or absent.


### Final production call-site caveat

A static search across `portfolio_trader.py`, `trader.py`, `campaign_execution.py`, `execution_recovery.py`, `execution_barrier.py`, `binance_client.py`, `strategy.py`, `risk_engine.py` and `server.py` found no production call to `Database.save_campaign_fill()` or a production reader of `exchange_fill_ledger`; the current explicit call sites are regression tests and the new barrier/recovery pending-effect checks. `portfolio_trader.py` and `trader.py` validate authoritative fills for order execution, but do not yet persist every confirmed fill through the canonical ledger. Therefore the exact-decimal ledger is **implemented but not yet connected end-to-end to production fill ingestion**. This is an additional P0 blocker, not a completed acceptance criterion. The safe gate remains closed by default and no global completion producer is present.


---

# WILLIAMS BOT 2.1 — TASK 006.7 Production Fill Ingestion Audit

**Status: BLOCKED — NOT ACCEPTED**  
**Audit baseline HEAD:** `ef061c8b4e00ff2756013f8bf67118cf1e541e12`  
**PR:** #46, open Draft, base `main`, base SHA `04da5f9c09572e99ff310be3a1908179165e528c`  
**Scope:** source-to-sink audit and exact-SHA CI recheck before any risky integration changes.  
**Safety:** no Mainnet was used, no order mutation was performed, and `main` was not changed.

## 1. GitHub and CI evidence

The PR metadata was re-read from GitHub. The PR head is `ef061c8b4e00ff2756013f8bf67118cf1e541e12`, the task branch is `audit/williams-2.1-task-006-android-runtime`, and the PR remains open, Draft, and unmerged.

All three workflows below were fetched by that exact commit SHA and completed successfully:

| Workflow | Run ID | Result | Scope / limitation |
|---|---:|---|---|
| Python backend tests | 38117131497 | CI-PROVEN: SUCCESS | Backend contract, compile, static safety audit and offline tests; this is not proof of unimplemented accounting invariants |
| Build Android APK | 38117131525 | CI-PROVEN: SUCCESS | Required lint, Android unit tests, debug/release APK build, metadata/signature/checksum steps |
| Williams Campaign CI | 38117131465 | CI-PROVEN: SUCCESS | Python deterministic safety/campaign tests and Android build/static checks |

Run links:  
- https://github.com/19wolfalone92/Williams/actions/runs/38117131497  
- https://github.com/19wolfalone92/Williams/actions/runs/38117131525  
- https://github.com/19wolfalone92/Williams/actions/runs/38117131465  

These results establish only that the listed workflows passed on the audit baseline SHA. They do **not** establish production fill ingestion, atomic financial effects, cross-process exactly-once accounting, full fault-injection coverage, physical Android process-death recovery, or Spot Testnet observations.

## 2. Source-to-sink inventory

| Source | Ingestion / handling path | Canonical write | Effect applier | Projection sinks / transaction boundary | Idempotency / recovery | Evidence and status |
|---|---|---|---|---|---|---|
| Binance order response with inline fills | `portfolio_trader.py::_authoritative_execution` validates order/symbol and passes payload to `accumulate_order` | No production call to `Database.save_campaign_fill` observed in inspected runtime call paths | No production applier observed | Caller subsequently updates legacy `trades`, campaign and protection state through separate calls; no shared transaction proven | Summary is order-level aggregation; not an effect identity | CODE-CONFIRMED: source path exists. Canonical ingestion TEST-PROVEN: NOT VERIFIED |
| Binance `my_trades` history for portfolio orders | `portfolio_trader.py::_authoritative_execution` loads per-order trade rows when inline fills lack trade identity | No canonical ledger write observed | None observed | Fee aggregation and later legacy trade updates are not shown to share a transaction | Trade IDs are read for aggregation, but no durable fill-effect claim in this path | CODE-CONFIRMED: authoritative-history lookup. End-to-end ledger path: NOT VERIFIED |
| Binance order response / trade history in legacy single-symbol trader | `trader.py::_authoritative_execution` uses `my_trades` when inline fills are absent, then calls `accumulate_order` | No canonical ledger write observed | None observed | Existing trade records and OCO protection are managed separately | No canonical fill-level effect state in this method | CODE-CONFIRMED: lookup and aggregation. Canonical ingestion: NOT VERIFIED |
| Conditional/campaign entry recovery | `campaign_execution.py::reconcile_pending_entries` fetches an order; optionally fetches `my_trades` only to reconstruct quote quantity | No canonical ledger write observed | None observed | Campaign transition, hard-stop placement, `record_initial_fill`/`record_add_on_fill`, trade save/update and state changes occur through separate operations; no encompassing SQLite transaction proven | Uses order/client ID for recovery, not canonical per-trade effect identity | CODE-CONFIRMED: recovery flow. Atomic fill application: NOT VERIFIED |
| Campaign exits | `campaign_execution.py` exit path consumes order-level `executedQty` and `cummulativeQuoteQty` | No canonical ledger write observed in inspected path | None observed | Campaign closure, trade PnL update and position-state update are separate operations; fees are not established from a canonical fill ledger here | Ambiguous result and crash consistency for this entire chain not proven | CODE-CONFIRMED: exit path. Complete accounting: NOT VERIFIED |
| Binance user-data stream on Android | `TradingAuditStore.recordUserEvent` stores `executionReport` rows in Android `executions` | No shared canonical decimal ledger integration observed | No effect applier observed | `executions` uses SQLite REAL columns; values are parsed through `toDoubleOrNull()`; absent commission becomes `0.0` in the current code path | Event key includes event time and is not the Python canonical key `(exchange, account_id, symbol, trade_id)` | CODE-CONFIRMED: audit-event storage. Decimal/fee integrity and shared accounting: NOT VERIFIED |
| Canonical fill ledger API | `db.py::Database.save_campaign_fill` | Writes `exchange_fill_ledger` using decimal TEXT and primary key `(exchange,account_id,symbol,trade_id)`; also writes compatibility `campaign_fills` inside a SQLite transaction | No production applier that commits all dependent projections and changes `PENDING` to `APPLIED` was found in inspected production files | Compatibility row still converts quantity, price, quote and commission to REAL; therefore it is not a precision-preserving canonical sink | Duplicate payload mismatch blocks; matching duplicate returns without creating a second ledger row | CODE-CONFIRMED: isolated persistence method. Production caller and completed effect: NOT VERIFIED |
| BUY execution admission | `execution_barrier.py` checks recovery status and `pending_exchange_fill_effects()` | Read-only gate | N/A | BUY is blocked if recovery is not complete or unresolved ledger effects exist; this is a useful fail-closed guard, not recovery proof | Status string alone is insufficient when pending effects exist | CODE-CONFIRMED; behavior exercised by existing CI only to the extent covered by its tests |

**Completeness caveat:** this inventory is based on runtime method/call-path inspection of the named Python and Android files, not only a repository-wide text search. It does not certify that every indirect callback or every alternate order mutation path has been exhaustively exercised. The missing canonical writer calls and effect applier are themselves acceptance blockers.

## 3. Canonical ledger and effect-state invariants

**CODE-CONFIRMED**
- Python schema version is 9.
- `exchange_fill_ledger` stores quantity, price, quote quantity and commission as decimal text.
- The primary key is `(exchange, account_id, symbol, trade_id)`.
- `save_campaign_fill` rejects missing commission amount or commission asset, invalid identity, non-finite values and binary floats.
- Conflicting duplicate identity/payload sets `recovery_status=RECOVERY_REQUIRED`; a matching duplicate does not insert a second ledger row.
- New ledger rows start `PENDING`.
- `execution_barrier.py` blocks BUY if the global recovery status is not complete or pending effects remain.

**Not proven / blockers**
- No production component was found that applies one canonical fill exactly once to every dependent position, campaign, fee, PnL, risk-reservation and protection projection, then marks the effect `APPLIED` in the same transaction.
- `campaign_fills` compatibility projection converts exact Decimal values to Python float / SQLite REAL. It is not safe as a canonical financial projection.
- Existing `campaigns`, `trades`, `orders`, `campaign_orders` and Android `orders`/`executions` schemas include REAL/Double financial fields. A downstream-wide Decimal audit and migration strategy remain incomplete.
- `campaign_execution.py::reconcile_pending_entries` currently aggregates fallback fills with float arithmetic and has legacy paths that write `fees=0.0`; these cannot establish exact commission accounting.
- Android `TradingAuditStore.recordUserEvent` converts absent or malformed commission `n` to `0.0`; that masks unknown commission and must be corrected before accounting can be trusted.
- A row in `exchange_fill_ledger`, a FILLED order, or `recovery_status=RECOVERY_COMPLETE` does not prove all financial projections agree.

## 4. Migration assessment

**CODE-CONFIRMED:** schema version is 9; `_migrate_campaign_fill_identity` adds identity columns to `campaign_fills`, installs a partial unique index, preserves legacy rows, and sets `RECOVERY_REQUIRED` when legacy rows cannot be matched to canonical evidence. It fails closed on detected duplicate explicit identities.

**NOT VERIFIED:** successful end-to-end migrations from populated v6, v7 and v8 databases, all legacy-value preservation invariants, proof that no fabricated canonical fill is created, and recovery of every migration-produced unresolved effect. Creating tables or passing isolated schema tests is not sufficient evidence for this criterion.

## 5. Fault injection and independent-process proof

| Required family | Status | Reason |
|---|---|---|
| Crash after exchange fill but before local ledger write | NOT VERIFIED | No end-to-end authoritative-history replay test proving the fill is re-ingested |
| Crash after ledger write, before effect application | NOT VERIFIED | No production effect applier / resume protocol proven |
| Crash after each financial projection update | NOT VERIFIED | No demonstrated single transaction spanning all projections |
| Crash immediately before/after APPLIED commit | NOT VERIFIED | APPLIED invariant is not established by a production applier |
| Duplicate and concurrent same-fill ingestion | NOT VERIFIED | Unique key exists at ledger level, but production path integration and independent OS-process proof are absent |
| Two independent processes applying same effect | NOT VERIFIED | No verified OS-process test evidence for this task |
| SQLite BUSY/LOCKED and compatibility-projection failure | NOT VERIFIED | Transaction helper exists, but required fault matrix is not demonstrated end-to-end |
| Unknown/missing commission | PARTIALLY CODE-CONFIRMED / end-to-end NOT VERIFIED | Canonical writer rejects missing commission; Android audit path currently coerces missing commission to zero |
| Balance/position mismatch or unconfirmed protection | NOT VERIFIED | Full global reconciliation proof not established |
| Android mutation-spy with unresolved recovery | NOT VERIFIED | JVM contract test is not a complete mutation-sink spy and does not prove physical process death |
| Physical Android process-death recovery | NOT VERIFIED | No device execution evidence |
| Binance Spot Testnet read-only reconciliation | NOT VERIFIED | No Testnet observation recorded; no trading mutation was performed |

No existing full fault-injection matrix was demonstrated by the checked CI runs. This report does not substitute a smaller invented matrix for the required project matrix.

## 6. Acceptance matrix for TASK 006.7

| Criterion | Status | Evidence / blocker |
|---|---|---|
| A1 Full production ingestion graph | BLOCKED | Paths inventoried; full graph coverage across all indirect handlers not proven |
| A2 Every authoritative fill reaches canonical ledger | BLOCKED | Production writers not observed in portfolio, legacy trader, campaign recovery/exit or Android event paths |
| A3 Duplicate fill has one financial effect | BLOCKED | Ledger uniqueness exists; effect-level and cross-process proof absent |
| A4 Production effect applier integrated | BLOCKED | No complete applier observed |
| A5 All projections atomic or covered by proven recovery protocol | BLOCKED | Existing state changes span separate operations |
| A6 APPLIED means all effects complete | BLOCKED | No production transition contract proved |
| A7 Downstream Decimal integrity | BLOCKED | REAL/float/Double and float-based fallback paths remain |
| A8 Unknown fees/positions not masked | BLOCKED | Android missing fee currently defaults to zero; legacy campaign paths use zero fee |
| A9 Global reconciliation of all required components | BLOCKED | No evidence covering orders, fills, fees, balances, positions, campaigns, PnL, risk and protection together |
| A10 v6/v7/v8 migrations safe | BLOCKED | Complete populated-version migration evidence not verified |
| A11 Independent OS-process exactly-once | BLOCKED | NOT VERIFIED |
| A12 Full fault-injection matrix | BLOCKED | NOT VERIFIED |
| A13 Android global gate/mutation-spy | BLOCKED | Gate contract exists; full mutation-spy/runtime proof absent |
| A14 Android APK on baseline SHA | PASS for build only | CI-PROVEN run 38117131525 succeeded on audit baseline SHA |
| A15 Required CI on one SHA | PASS for listed baseline workflows only | Runs 38117131497, 38117131525 and 38117131465 succeeded on `ef061c8b4e00ff2756013f8bf67118cf1e541e12`; task-specific missing tests remain |
| A16 No secrets/Mainnet in tests | PARTIALLY VERIFIED | Audit reports no Mainnet or live order used; a separate full configuration/log artifact review for the requested Testnet scenario is NOT VERIFIED |
| A17 PR/Git state checked | PASS | PR #46 open Draft, unmerged; base SHA unchanged |
| A18 Evidence classifications and residual risks documented | PASS for this report | This report explicitly separates code, CI and unverified evidence |

## 7. Required next implementation sequence

1. Add one shared, strict authoritative-fill ingestion contract and route every Python fill source through it before any local fill-dependent financial projection is considered reconciled.
2. Treat exchange-confirmed/local-write-failed and ambiguous network outcomes as durable recovery-required states; replay authoritative trade history without repeating already-applied effects.
3. Implement one transactional effect applier only after enumerating the actual projection sinks. It must verify expected prior state and update all same-database projections plus effect state in one SQLite transaction. External protection cannot be included in that transaction and therefore requires a separate explicit protection/reconciliation protocol.
4. Keep unknown commission amount/asset unknown; remove silent zero substitution in the accounting path. Preserve exact exchange decimals and keep REAL projections explicitly non-authoritative.
5. Add cross-process tests and the complete project fault-injection matrix; demonstrate database state and gate behavior after each injected failure.
6. Bring Android execution events under a compatible durable contract, add a mutating-sink spy, and perform physical process-death tests where available.
7. Run the separate Spot Testnet read-only history/reconciliation workflow only after local P0 tests pass and its no-mutation contract is verified.

## Final TASK 006.7 disposition

**BLOCKED — NOT ACCEPTED.**  
The exact-SHA CI results are green, but the essential production ledger integration, atomic effect application, downstream Decimal integrity, full global recovery proof, independent OS-process tests, fault-injection matrix, Android mutation-spy/physical-device evidence and Spot Testnet read-only evidence are not established. No entry gate may be opened on the strength of the current evidence. Keep PR #46 Draft and do not merge.


---

# WILLIAMS BOT 2.1 — TASK 006.8 Re-audit

**Disposition: BLOCKED — NOT ACCEPTED**  
**Verified pre-report HEAD:** `220b3da21bf809f80c1b3d02177e0358300ceaff`  
**Base/main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**PR:** #46, open Draft, unmerged. No Mainnet order execution or Spot Testnet mutation was performed.

## 1. Git and exact-SHA CI recheck

The current branch and PR were re-read before this audit. The branch points to the PR head above; base remains `main` at `04da5f9c09572e99ff310be3a1908179165e528c`. The PR is still Draft and unmerged. `main` was not modified.

The following runs are associated with the exact pre-report HEAD `220b3da21bf809f80c1b3d02177e0358300ceaff` and all completed successfully:

| Workflow | Run ID | Conclusion | Jobs |
|---|---:|---|---|
| Python backend tests | 38118768316 | CI-PROVEN: SUCCESS | `backend-test` |
| Williams Campaign CI | 38118768306 | CI-PROVEN: SUCCESS | `Android build and static checks`, `Python tests` |
| Build Android APK | 38118768318 | CI-PROVEN: SUCCESS | `Build APKs` |

Runs:  
- https://github.com/19wolfalone92/Williams/actions/runs/38118768316  
- https://github.com/19wolfalone92/Williams/actions/runs/38118768306  
- https://github.com/19wolfalone92/Williams/actions/runs/38118768318  

These are exact-SHA CI successes for the pre-report HEAD only. The report update below creates a new commit; these runs must not be represented as CI evidence for that new commit. Even the exact-SHA successes do not prove missing accounting behavior.

## 2. Production fill source-to-sink findings

### Python Spot paths

| Source/path | Current observed behavior | Ledger/effect result | Status |
|---|---|---|---|
| `portfolio_trader.py::_authoritative_execution` | Validates inline fills or fetches per-order `my_trades`; requires a complete order summary and commission data for the summary calculation | The inspected method returns `accumulate_order(...)`; no call to `db.save_campaign_fill` is present in this method. Downstream callers update legacy trade/campaign state separately. | CODE-CONFIRMED path; canonical ingestion NOT VERIFIED |
| `trader.py::_authoritative_execution` | Fetches `my_trades` only when the order payload has no inline fills; validates via `accumulate_order` | No canonical fill write in the inspected method. On lookup exception it logs a warning and proceeds to the accumulator, so the exact completeness guarantee needs further call-path proof. | CODE-CONFIRMED path; canonical ingestion NOT VERIFIED |
| `campaign_execution.py::reconcile_pending_entries` | Loads pending client-ID orders; reads order-level `executedQty` and `cummulativeQuoteQty`; fallback history aggregation multiplies `float(price) * float(qty)` and suppresses lookup exceptions | No canonical ledger ingestion was observed in this recovery block. Campaign transitions and fill recording are performed separately. | CODE-CONFIRMED path; Decimal and atomic application BLOCKED |
| Emergency SELL in `portfolio_trader.py` | Submits through the existing execution barrier, confirms FILLED, calls `_authoritative_execution`, then computes quantity/quote/fees using float projections and updates trade/state separately | No canonical per-fill ingestion/applier was demonstrated for this path. A partial exit leaves `RECONCILE_REQUIRED`, which is fail-closed but not a substitute for exact ledger application. | CODE-CONFIRMED path; financial effect NOT VERIFIED |
| `db.py::save_campaign_fill` | Validates identity and decimal inputs, checks duplicate payload, writes canonical row as `PENDING`, then writes legacy compatibility projection in the same transaction | Isolated persistence API exists, but no production call sites were observed in the inspected Python execution/recovery methods. Compatibility projection casts exact values to float/REAL. | CODE-CONFIRMED isolated writer; production integration BLOCKED |
| `execution_barrier.py` | Blocks BUY when recovery status is not complete or pending/unavailable canonical ledger checks cannot be satisfied | A protective gate is present; it does not ingest fills, apply their financial effects, or prove global recovery. | CODE-CONFIRMED gate; recovery proof NOT VERIFIED |

### Android path

The previous TASK 006.7 audit identified `TradingAuditStore.recordUserEvent` as an execution-report audit sink, not a proven canonical financial ledger/effect applier. The available evidence still does not establish a complete Android production path that shares the backend fill identity contract and atomically applies financial projections. Android event storage must not be treated as equivalent to canonical financial accounting.

**Inventory completeness:** the paths above cover the named high-risk execution and recovery methods that were inspected. This is not a claim that every indirect callback, API client, worker and legacy mutation sink has been exhaustively enumerated or exercised. A complete mutation-sink graph remains an acceptance deliverable.

## 3. Financial effect and transaction boundary

**CODE-CONFIRMED**
- The canonical Python ledger uses the key `(exchange, account_id, symbol, trade_id)` and exact decimal text fields.
- New ledger rows start `PENDING`; matching duplicate payloads do not insert another ledger row; conflicting payloads trigger reconciliation-required behavior.
- Ledger insert and compatibility `campaign_fills` insert are enclosed by the writer's SQLite transaction.

**BLOCKED**
- No integrated production effect applier was found that consumes `PENDING`, verifies the fill/effect identity, updates all local financial projections, and transitions to `APPLIED` in the same transaction.
- No proof was found that position quantity, campaign state, commission amount/asset, PnL, risk reservation and protection state are atomically updated as a single local effect.
- Protection on Binance is an external side effect and cannot be made atomic with SQLite; it needs a durable intent plus explicit exchange reconciliation before the global gate can open.
- No production reader/writer graph demonstrates that every legacy path is prevented from independently applying an effect.

Therefore **do not add a partial ledger call to one path and leave other paths active**. That would create competing sources of truth and could falsely suggest global exactly-once accounting. The safe result of this audit is to retain the current fail-closed gate and leave the production integration blocker explicit.

## 4. Decimal Integrity / commission uncertainty

**CODE-CONFIRMED**
- The canonical ledger API rejects binary float inputs for canonical decimal fields and rejects absent commission amount or commission asset.
- Existing financial projections remain mixed-type; inspected code in campaign recovery uses float arithmetic, and the emergency SELL path converts execution values and fee projections to float.

**BLOCKED**
- The canonical writer still writes compatibility projection values as Python float / SQLite REAL.
- The inspected campaign-recovery fallback suppresses history-query exceptions and uses float multiplication for quote reconstruction.
- Full cross-platform Python/SQLite/Kotlin exact-decimal parity vectors are not demonstrated.
- End-to-end handling of unknown commission is not proven for every legacy and Android path. The canonical writer's rejection alone does not repair callers that bypass it or substitute zero elsewhere.

No claim is made that legacy REAL values can be converted losslessly without authoritative exchange evidence.

## 5. Crash recovery, concurrency and migrations

| Requirement | Result | Evidence |
|---|---|---|
| Replay missing fills from authoritative history | NOT VERIFIED | No end-to-end replay test shown that routes recovered fills through a canonical ingestion contract |
| Effect applier crash/restart | BLOCKED | Production effect applier not found |
| Crash at every financial projection boundary | NOT VERIFIED | No complete fault-injection matrix run recorded |
| Independent OS processes applying same fill | NOT VERIFIED | No verified independent-process test evidence |
| SQLite BUSY/LOCKED and commit failure behavior | NOT VERIFIED end-to-end | Transaction helper exists, but no full effect transaction to exercise |
| Unknown commission preserved across all paths | BLOCKED | Mixed legacy/Android paths remain |
| Populated v6/v7/v8 migration safety and rollback | NOT VERIFIED | Prior tests preserve legacy rows and block unresolved data, but complete migration/recovery proof is not established |
| Android physical process-death recovery | NOT VERIFIED | No physical-device evidence |
| Spot Testnet read-only reconciliation | NOT VERIFIED | Not run; no Testnet mutation was performed |

## 6. TASK 006.8 acceptance matrix

| Criterion group | Status | Reason |
|---|---|---|
| A — Complete production fill inventory | BLOCKED | High-risk paths inspected; exhaustive indirect-path/mutation-sink proof remains |
| B — One ingestion contract for every source | BLOCKED | Existing writer has no demonstrated production integration across all paths |
| C — Atomic financial effect applier | BLOCKED | No integrated applier found |
| D — Projections/global reconciliation | BLOCKED | No complete evidence covering fills, fees, balances, positions, campaigns, PnL, risk and protection |
| E — Decimal/unknown fee integrity | BLOCKED | Float/REAL/Double paths remain; no cross-platform golden-vector proof |
| F — Full crash/fault injection | NOT VERIFIED | Full project matrix not demonstrated |
| G — Independent OS-process safety | NOT VERIFIED | No verified OS-process test evidence |
| H — Android production/process-death recovery | NOT VERIFIED | JVM/CI evidence does not prove physical process death or full mutation-sink coverage |
| I — v6/v7/v8 migration preservation | NOT VERIFIED | Complete populated DB + restart + rollback/recovery proof missing |
| J — CI on a single final SHA | PENDING after this report commit | The listed runs passed on pre-report SHA only; rerun/check all required workflows on the new head |

## 7. Next implementation order

1. Finish a call-graph and mutation-sink inventory, including emergency/recovery SELL, partial exits, OCO paths, user-stream callbacks, history backfill, background workers and Android sinks.
2. Define one canonical fill contract that can represent exact source data, commission certainty, order identity and stable trade identity. Reject incomplete or conflicting data and persist a recovery latch on failure.
3. Design the effect transaction against the actual schema and data owners first. Include all same-SQLite projections and the effect state in one transaction; model external protection as a durable separately reconciled obligation.
4. Route all authoritative sources through the same ingestion/effect protocol and disable or gate any legacy path that could mutate the same financial projection independently.
5. Build the original project fault-injection matrix and independent OS-process tests against the integrated applier, not isolated helpers.
6. Verify populated migration copies, cross-platform decimal vectors, Android mutation spy and physical process-death recovery.
7. Run required workflows again on one final SHA. Run Spot Testnet read-only reconciliation only after local P0 checks pass and only after the selected workflow is proven non-mutating.

## Final TASK 006.8 disposition

**BLOCKED — NOT ACCEPTED.** The exact-SHA CI runs listed above passed on the pre-report head, but production fill ingestion, atomic effect application, full Decimal Integrity, global reconciliation, cross-process exactly-once, complete fault injection, Android physical recovery and migration safety remain unproven. Keep PR #46 Draft/unmerged. No Mainnet execution or Spot Testnet mutation is permitted; new entries remain blocked while financial state is unresolved.
