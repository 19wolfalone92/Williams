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
