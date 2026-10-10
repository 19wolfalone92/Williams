# WILLIAMS BOT 2.1 — Task 006
## Android Autonomous Runtime & Operational Reliability Audit

**Status: IN PROGRESS — NOT ACCEPTED FOR AUTONOMOUS LIVE OPERATION**

**Audit branch:** `audit/williams-2.1-task-006-android-runtime`  
**Base `main` SHA at branch creation:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Exchange scope:** Binance Spot Testnet endpoints only; no Mainnet endpoint was enabled.  
**Device validation:** Not performed. No physical-device control channel was available, so this report does not claim real phone, Doze, process-death, or long-duration runtime testing.

## Executive result

The Android application contains a native on-device engine, local loopback API, SQLite market-history store, Spot Testnet REST and WebSocket clients, a foreground service, and execution gating. It is not merely a Compose UI. Static source inspection nevertheless does not prove that the engine remains reliable after Android kills its process, the device enters Doze, network access disappears, or the APK is upgraded.

This branch hardens several confirmed seams: local API authentication and URL policy, freshness-gated entries and candidate visibility, verified persistence of backend settings and portable-backup restore, and explicit persistence/preflight for pause, stop and resume. These fixes still require a successful exact-head CI run and physical-device validation. No new order was sent during this audit.

## Baseline and scope inspected

The branch was created from `main` SHA `04da5f9c09572e99ff310be3a1908179165e528c`. The audit covered:

- `MainActivity.kt`: UI startup, backend selection, request errors, credential configuration and diagnostics rendering.
- `StandaloneRuntime.kt`: local listener, REST/WebSocket clients, scanner, history warm-up, campaign entry path, state machine, recovery, credential persistence and execution gates.
- `TradingForegroundService.kt` and `AndroidRuntimeHealth.kt`: notification, sticky service behavior and best-effort OS restrictions/health telemetry.
- `ExecutionGate.kt`, `TradingStateMachine.kt`, `TradingEventLoop.kt`, `BinanceUserDataStream.kt`.
- `MarketHistoryStore.kt`, `TradingAuditStore.kt`, `BackupManager.kt`, Android manifest, Gradle and CI workflows.
- Existing JVM unit-test sources and root `test_android_remote_only.py` checks.

The manifest declares INTERNET, notification and foreground-service permissions; the service is non-exported and declares the `specialUse` foreground-service subtype. `MainActivity` starts it, and the service publishes a notification before dispatching slower runtime auto-start work. This is a code-level observation only; Android OS scheduling behavior has not been device-tested.

## Changes present on the audit branch

### Local autonomy and authentication
- The local API binds to `127.0.0.1:18080`; local mode does not require an external trading server.
- Calls from the UI use the per-install local bearer token stored in Keystore-backed encrypted preferences, not the optional remote-backend token.
- The local server rejects calls without valid bearer authentication and limits request size / handler concurrency.
- `BackendUrlContract` centralizes URL validation. Plain HTTP is accepted only for the exact loopback runtime URL; a remote backend requires a structurally valid HTTPS URL. User-info, arbitrary loopback paths, look-alike hosts and query/fragment URL tricks are rejected.
- The local bridge token is not exported in a portable backup; the native installation creates/retains its own token.

### Persistence and command truthfulness
- Binance credentials are written through the native encrypted store, committed and read back before configuration is reported successful; failed validation rolls back and verifies the prior values.
- Credential deletion commits and verifies removal; unresolved managed positions or pending entries prevent deletion.
- Optional backend settings are committed and read back before the UI can report success.
- Portable backup/restore supports standalone mode as well as HTTPS remote mode, and restore verifies preference write/read-back. Binance credentials and the per-install local API token are explicitly not restored from a portable backup.
- `auto_run` startup intent is committed before the engine starts. Pause intent is persisted and restored after process recreation. Stop verifies that restart intent is durably cleared; if this cannot be verified, it reports the failure and attempts to latch execution off.
- Resume is no longer a Boolean flip: it requires an active paused runtime, Testnet credentials, exchange recovery/reconciliation, no unresolved pending entries, account data, fresh market/history/user-stream state, and verified protection for restored positions.

### Data freshness and execution gates
- Runtime freshness contracts fail closed for missing timestamps, unavailable sources, and backward wall-clock movement.
- The scanner does not start on stale REST data. Scanner responses hide stale candidates and identify whether the candidate snapshot is fresh.
- Initial entries and campaign add-ons recheck the full execution gate immediately before entering the durable order path.
- A cached campaign price is not trusted after the REST freshness window; an ad-hoc ticker read does not falsify the five-symbol REST-batch freshness timestamp.
- Status reports do not expose the main price or unrealized PnL as live when market telemetry is stale. Diagnostics expose REST age/error, freshness limits, WebSocket status, scan age/progress, execution blockers, local listener/auth state and device/service telemetry.
- The generic `recovered` value is derived from reconciliation / safety state rather than hardcoded true.

## Findings and acceptance matrix

| Acceptance area | Source-level result | Evidence still required |
|---|---|---|
| Autonomous runtime exists on device | Native engine, SQLite, local API and Testnet clients are present | Install and operate the APK on a physical phone |
| Optional remote backend | Local URL path and bearer separation implemented | Test with remote backend absent and with malformed/failed local listener |
| Foreground service / notification | Manifest, notification channel, non-exported service and sticky restart path present | Test notification denial, background restrictions, Doze, swipe-away, OS process kill and device reboot on several supported Android versions |
| Lifecycle and no duplicate engine | Synchronized server/engine startup and control paths are present | Observe worker/thread/socket counts across pause, stop, service recreation, process death and restart |
| Network loss / stale telemetry | Freshness contract and entry/scanner gates present | Fault-injected network-loss test during warm-up, scan, open position and reconnect |
| Credentials | Encrypted storage, commit/read-back and rollback checks present | Test save, invalid credentials, clear, process restart, APK upgrade and clean install with Testnet-only credentials |
| SQLite history | Persistent candle and sync-state schema present | Verify counts, close-time continuity, corruption behavior and recovery on-device |
| Strategy parity | Android and Python strategy implementation/tests exist in the repository | **BLOCKER:** the branch tree contains no identifiable shared golden-vector fixture/test suite proving equivalent Python/Android results for every requested strategy and campaign transition |
| Unit tests and lint | JVM unit tests and CI workflows exist | Green results must belong to the exact final branch SHA |
| Debug APK | CI has an Android debug APK build job | Build, inspect SHA-256/signature and install the artifact from the exact final SHA |
| Real Android runtime tests | Not run | **BLOCKER:** physical-device evidence is mandatory for the requested acceptance |

## Important remaining risks

1. **No device test has been run.** Service declaration/compilation is not evidence of continued execution under Android power management. The user explicitly requires device/model, Android version, APK hash, foreground/background duration, process termination, restart, network-offline, and data-recovery evidence.
2. **No instrumentation test source was found under `app/src/androidTest`.** Existing Kotlin tests are JVM/unit-level and cannot prove actual Android service lifecycle behavior.
3. **No shared golden-vector suite was found in the branch tree** (no identifiable vector/golden fixture paths). Python/Android equivalence for OHLC/timestamps, Alligator, AO/AC, confirmed fractals, signal expiry/cancellation, stops, risk, campaign transitions and recovery remains unproven. Do not treat matching indicator names or the presence of a core-contract endpoint as parity proof.
4. **Testnet end-to-end order lifecycle is not part of this completed audit.** No Testnet mutation was exercised. Before any Testnet order-path validation, use a dedicated approved test plan and verify the account/environment in the live diagnostics; do not enable Mainnet.
5. The foreground service uses Android's `specialUse` type and reports best-effort power/background telemetry. Device-vendor limits and notification permission effects remain unknown until measured.
6. The local API still serves a small custom HTTP parser rather than a mature embedded HTTP server; malformed header/body, concurrent request, service shutdown and listener restart behavior require adversarial tests.
7. Static source and unit tests do not establish exchange behavior after partial fills, protective-order races, fee reconciliation, or process death during an order mutation. Existing fail-closed/reconciliation mechanisms need fault-injected Testnet validation.

## Release gate

Do not describe Task 006 as complete until all of the following evidence is attached to the **same exact commit**:

- Python safety tests and Android JVM tests pass.
- Android lint passes with warnings-as-errors.
- Debug APK build succeeds; APK SHA-256 and signing verification are recorded.
- All cross-language golden vectors pass without deleting tests or weakening expected values without source/specification evidence.
- Physical-device test records cover foreground operation, long background operation, service/process termination and recreation, airplane mode/network loss, credential reload/clear, SQLite persistence, stale-data blocking, no duplicate worker, and resume preflight.
- Read-only diagnostics show actual Testnet environment, history counts/timestamps, scan counts/candidates/blockers and execution state. If order-path testing is later authorized, it must be an isolated Testnet test with no Mainnet keys or endpoints.

**Final status: IN PROGRESS — NOT APPROVED for live trading. A successful APK build alone is insufficient.**
