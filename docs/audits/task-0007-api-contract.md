# TASK 000.7 — Python ↔ Kotlin API contract audit

- Repository: `19wolfalone92/Williams`
- Audit branch: `audit/task-0007-api-contract`
- Base ref/SHA captured before audit: `main` / `04da5f9c09572e99ff310be3a1908179165e528c`
- Inspected branch HEAD before audit: same SHA (branch created from this commit)
- Scope: source-level contract inventory only. No exchange requests were made; no real/Testnet orders were submitted; no trading automation was enabled.
- Decision: **BLOCKED**. This is not a release approval.

## Important architecture observation

There are two API surfaces in the repository: (A) the Python FastAPI service in `server.py`, and (B) the Android embedded loopback API routed by `StandaloneRuntime.kt` to `NativeEngine`. The Android app header and Gradle config explicitly describe the local autonomous runtime; it is not merely a DTO client for the Python service. These surfaces overlap in path names but are not proven wire-compatible. They must be treated as separate contracts until owners #1 and #2 approve a canonical boundary.

## API contract matrix (observed source; not a claim of parity)

| Operation | Python FastAPI | Android local API | Request / response shape observed | Error / status semantics | Retry / idempotency assessment | Contract tests |
|---|---|---|---|---|---|---|
| Health | `GET /api/v1/health` (no auth dependency) | `GET /api/v1/health` | Python: `ok, service, version, execution_state_contract{version,state,execution_enabled,reconciliation_required,kill_switch_latched}, unresolved_symbols, pending_entry_symbols, websocket, auth_configured, max_open_positions, risk limits, open_positions, testnet`. Android delegates to `x.health()`; exact field parity not established. | Python response 200 on normal path; runtime exceptions may become framework 500. | Read-only; repeatable. | Static route/model inventory; parity unverified. |
| Status | Python route not confirmed in inspected route slice | `GET /api/v1/status?fast=true|false` | Android JSON from `x.status(fast)`; exact fields not yet compared. | Local dispatcher returns JSON; generic unknown path returns `{"error":"Not found"}` without explicit HTTP status mapping in inspected dispatch. | Read-only. | Gap in inventory. |
| Start | `POST /api/v1/control/start` → `{"started": bool}` | Same path → `x.start()` JSON | Python boolean is whether a new worker was started, not whether an order was filled. Android response schema not proven equivalent. | Python default 200; auth failure 401; missing/short `MOBILE_API_TOKEN` gives 503. | Mutation; no request ID/idempotency key observed. Duplicate calls can have state-dependent results. Never blindly retry after timeout without state reconciliation. | Must assert ACK != execution. |
| Stop | `POST /api/v1/control/stop` → `{"stopped": bool}` | Same path → `x.stop()` | Control-plane ACK only. | Python default 200; auth 401/503. | Repeated stop likely state-safe but response meaning needs explicit contract. | Offline only. |
| Pause / Resume | `POST /api/v1/control/pause` → `{"paused": bool}`; `POST /api/v1/control/resume` → `{"resumed": bool}` | Same paths; `x.pause()`, `x.resume()` | Boolean ACKs; not order-state results. | Python default 200; auth 401/503. | Desired-state semantics should be idempotent; no command ID. | Offline only. |
| Panic stop | `POST /api/v1/control/panic` → `panic_stopped, trading_stopped, positions_closed=false` | No matching route found in inspected Android dispatch | No request body. Python explicitly says existing position is left intact. | Python default 200; auth 401/503. | Desired-state mutation; Android/Python route mismatch likely. | Route parity test required. |
| Kill | `POST /api/v1/control/kill` returns `killed, trading_stopped, closed_positions, errors, state` | Same path → `x.kill()` | Python can invoke `manual_sell` for managed open trades; result may include `RECONCILE_REQUIRED`. Android schema not proven equivalent. | Python may raise runtime/DB errors; explicit status mapping not comprehensive. | High-risk mutation; no idempotency key observed. On timeout, query health/orders/positions and reconcile before any retry. | Must use mocked services only. |
| Manual sell | `POST /api/v1/control/sell?symbol=...`; result from `multi.manual_sell`; if unsold and `RECONCILE_REQUIRED`, raises HTTP 409 | Same path; missing symbol throws local dispatcher error; `x.sell(symbol)` | Symbol is query parameter in both inspected dispatchers. Response DTO parity and decimal quantity semantics unverified. | Python 409 for reconcile-required; auth 401/503; validation may be 422. Android generic dispatcher error mapping not shown to preserve HTTP status. | Trading mutation; no client request ID/idempotency key proven. Do not retry after timeout; reconcile authoritative order and inventory first. | Mock-only duplicate/timeout tests required. |
| Recover | `POST /api/v1/control/recover` → `recovered,state,unresolved_symbols,pending_entry_symbols,details` | Same path → `x.recover()` | Python canonical execution state is `RECONCILE_REQUIRED`, `OPEN`, or `READY_FLAT`; Android response shape not compared. | Python default 200; auth 401/503. | Recovery is not equivalent to order execution; repeated calls need specified semantics. | Reconnect/recovery tests required. |
| Configure credentials | `POST /api/v1/config/binance`, Pydantic `CredentialPayload(api_key:str, api_secret:str, testnet:bool=true)` → `configured,testnet`; invalid fields 400, configure conflict 409 | Same path → `x.configure(JSONObject(body))` | Request keys match at name level; response and validation parity unverified. Secrets must never be logged. | Python validation: 400 / 409; auth 401/503; Pydantic malformed payload commonly 422. Android HTTP error propagation unverified. | Configuration mutation; no idempotency key. | Offline schema/secret-redaction tests required. |
| Clear credentials | No matching DELETE route confirmed in inspected Python route inventory | `DELETE /api/v1/config/binance` → `x.clear()` | Route parity not established. | Android dispatch generic error if route absent. | Mutation. | Confirm endpoint ownership before any contract changes. |
| Orders | `GET /api/v1/orders?limit=50&symbol=...`; limit clamped 1..200 for symbol, 1..500 all-symbol | No `/api/v1/orders` route in inspected Android dispatcher | Python returns DB order rows; JSON row field types depend on DB schema. Android portfolio/status may include embedded orders; parity not proven. | Query values cast/clamped by Python; invalid integer conversion may 422. | Read-only; snapshot freshness/authoritative exchange status not guaranteed by DB-only endpoint. | REST snapshot freshness and pagination tests required. |
| Trades / logs / settings | Python `GET /api/v1/trades`, `/api/v1/logs`, `/api/v1/settings`; authenticated | Android has trades/logs/settings routes; methods are `x.trades/logs/settings()` | Python list limits clamp; settings returns `version=4.25.0`, numeric strategy/risk config and testnet flag. Field/type parity unverified. | Auth 401/503; malformed query commonly 422. | Read-only; DB-derived values may be stale relative to exchange. | Snapshot and numeric schema tests required. |
| Market data / scanner | Python exposes market/scanner endpoints (full list needs a dedicated route extraction) | `GET /api/v1/market/indicators`, `/market/klines`, `/market/tickers`, `/scanner?refresh=...` | Android uses query params `symbol,interval,refresh`; Python types/defaults not all compared. | Local dispatcher unknown route returns JSON error; HTTP status mapping unclear. | Read-only, but stale/gap handling must be explicit. | Candle timestamp, duplicates, closed-candle and freshness fixtures required. |
| WebSocket | Python `WebSocketHub` emits initial `{"type":"snapshot","data":...}`; snapshot includes `server_time` UTC ISO string, connection flags, reconnect counts, `user_sync_required`, position/orders/candles | Android NativeEngine has a market socket, event timestamp tracking, user-stream sync flags and REST reconciliation timestamps; this is exchange-facing, not proven to be the same app event protocol | Event names, sequence numbers, schema version, replay cursor, event IDs and monotonic ordering are not established as shared contract. | Queue errors are swallowed in Python `_queue`; gap recovery depends on separate sync logic. | Duplicate/out-of-order events must be tolerated; reconnect must trigger authoritative REST snapshot/catch-up. | Offline fixtures for duplicate, stale, gap and reconnect required. |

## Confirmed defects / blockers

### P0 — Version contract mismatch (inherited from Issue #41)
- `ci_release_gate.py`: `EXPECTED_VERSION = '4.24.0'` (blob SHA `437b2002715d0bf7acd676aebb9bf6050d8bfc30`).
- `server.py`: `VERSION = '4.25.0'` (blob SHA `f7d93c014975949a7172323801f0fb485013895e`).
- `app/build.gradle.kts`: `versionName = "4.25.0"` (blob SHA `0cc17442a89926b3049e57fb0a823e6b4e9a6387`).
- The release gate explicitly asserts the server, Android UI and Gradle version equal 4.24.0, so the current source violates the gate by inspection. Do not adjust versions as part of this audit; owner approval required.

### P1 — Two overlapping API implementations are not contract-tested as one
- Python FastAPI `server.py` and embedded Android loopback dispatcher `StandaloneRuntime.kt` independently implement several identical paths (health, start/stop/pause/resume/kill/recover/sell/settings/trades/logs), but their response DTOs/status mapping have not been shown to be equivalent.
- Android is an autonomous local runtime (MainActivity header and StandaloneRuntime NativeEngine), so assuming that Android calls Python is incorrect without an explicit deployment/configuration map.

### P1 — No proven idempotency/request identity contract for trade mutations
- The inspected Python command endpoints do not accept a client request ID / idempotency key.
- Android `PendingEntry` and `PositionState` contain `clientOrderId` / `entryClientOrderId` fields internally, but that does not establish an HTTP command idempotency contract or exactly-once behavior after an ambiguous client timeout.
- For sell/kill/recover timeout: do not replay blindly; reconcile current order/position state first.

### P1 — Transport acknowledgement vs execution result is not explicitly uniform
- `{"started": true}`, `{"stopped": true}`, `{"configured": true}` and similar command responses are accepted-command/control-state acknowledgements, not evidence of order fill.
- Canonical state/fills must be obtained from order state, trade/order history and exchange reconciliation. UI must not render a command ACK as FILLED/EXECUTED.

### P1 — WebSocket protocol lacks a demonstrated cross-client sequence/replay contract
- Python snapshots have timestamps and reconnect counters but the inspected schema does not expose a monotonic event sequence, stable event ID, or replay cursor.
- Python outbound queue failures are swallowed. The source indicates user-stream sync is required after reconnect, but there is no verified proof in this audit that all lost events are caught up through REST before execution resumes.
- REST `/api/v1/orders` is DB-backed in the inspected route and is not by itself proven to be a fresh exchange-authoritative snapshot.

### P1 — Python execution persistence remains fail-open (Issue #41)
- Per Issue #41, `execution_barrier.py` swallows persistence/recording exceptions and can proceed toward exchange submission without verified durable intent. This audit does not alter that code and does not close the blocker.

### P2 — Error / numeric / timestamp schemas remain incompletely aligned
- Python FastAPI has HTTP statuses including 400, 401, 409, 422, 503; Android local dispatcher appears to return JSON `Not found` without an explicit status in the inspected dispatch.
- Server timestamps include UTC ISO strings and milliseconds; no unified timestamp unit/nullability/schema version has been proven across DTOs.
- Python uses floats for several derived market metrics and numeric config values; Android uses Kotlin `Double` and raw JSON. Decimal precision, quantity/price formatting, null/default semantics and enum case compatibility need fixtures.

## Proposed canonical safety contract (proposal only; do not implement without owners #1 and #2)

1. Every mutating command returns `command_id`, `accepted`, `state`, `server_time_ms`, `schema_version`; `accepted` means only accepted/queued.
2. Order lifecycle is separate: `order_id`, `client_order_id`, `status` (closed enum), cumulative executed quantity, average fill price, update timestamp and reconciliation state.
3. Mutations that can create/close exposure require stable caller-generated idempotency key, durably recorded before exchange submission. Same key + same payload returns the existing command; same key + different payload is 409. Ambiguous timeout leads to status lookup/reconciliation, never blind mutation retry.
4. Define errors as stable JSON: `{schema_version, code, message, retryable, command_id?}`, retaining HTTP status semantics (400 invalid, 401 unauthenticated, 409 conflict/reconciliation, 422 schema validation, 503 unavailable).
5. Define all times as UTC epoch milliseconds in machine payloads; document nullable fields and defaults. Use decimal strings for exchange prices/quantities where exact precision is required; explicitly document percentages as fraction vs percent points.
6. WebSocket events carry `schema_version`, stable `event_id`, monotonic per-account sequence, `event_time_ms`, entity version and type. On reconnect or sequence gap, stop treating stream state as current, fetch authoritative REST snapshot/catch-up, deduplicate by event ID/sequence and only then mark synchronized.
7. No command ACK may map to order `FILLED`; fills/order status are authoritative only after exchange response/events and reconciliation.

## Ownership / change split

- **First: audit/report only** (this branch): matrix, defect evidence and offline static contract checks. No production Python/Kotlin changes.
- **Backend owner (#1):** Python DTO/error/idempotency/reconciliation changes and Python fixtures in a separate backend PR.
- **Android owner (#2):** Kotlin DTO/error/retry/reconnect/UI-state changes and Kotlin fixtures in a separate client PR.
- **Joint contract PR only after both owners approve:** shared schema/examples and compatibility tests. Do not change live API behavior before approval.
- Keep Issue #41 version and fail-closed persistence blockers open until separately fixed and verified.

## Verification status

- Source files inspected through GitHub connector at the recorded base SHA.
- Branch created from base SHA above.
- No Python/Kotlin production code changed.
- No exchange/network tests or order placement performed.
- Offline tests are added in this branch but cannot be reported as executed from the repository connector; run the commands in the test file in a checked-out workspace and attach output.
- **Final status: BLOCKED** — incomplete DTO parity, no execution evidence for offline tests, unresolved P0/P1 blockers from #41, and no owner sign-off.
