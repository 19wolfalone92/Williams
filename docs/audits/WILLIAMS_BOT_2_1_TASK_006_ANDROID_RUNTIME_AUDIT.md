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

- The release contract gate expected version `4.24.0` while `server.py`, Gradle and the Android source declared `4.25.0`. The task branch aligns the gate to `4.25.0` and asserts presence of key Android safety contracts.
- Added pure unit tests for local bearer authorization and runtime freshness boundary/error cases. Existing unit tests and Android workflow remain the authoritative compile/test gate.

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
| Unit tests | Tests added/updated; **not executed in this environment** | Green `:app:testDebugUnitTest` and relevant Python test workflows |
| Static checks and APK | **PENDING** | Green release gate, Android lint, debug/release APK build and artifact inspection |
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
