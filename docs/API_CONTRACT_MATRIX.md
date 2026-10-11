# Python / Android API contract matrix

Audit baseline: `main`, 2026-10-11. Sources: `server.py` and
`app/src/main/java/com/williamsbot/StandaloneRuntime.kt`.

## Scope and interpretation

The Android dispatcher is an embedded device-local API (loopback
`127.0.0.1:18080`), not a second deployment of the Python service. A path
present on only one side is not automatically a defect. In particular, local
Android-only diagnostics/history/self-heal routes and Python-only server
analytics routes are recorded as intentional differences unless product
requirements say otherwise.

All JSON response shapes below are endpoint-specific. Most endpoints return
JSON objects or arrays; errors are not yet normalized across implementations.
The Python service uses FastAPI validation/error responses, while the Android
dispatcher returns `{"error":"Not found"}` for unmatched routes (HTTP status
handling is determined by its local HTTP server wrapper and must be verified
there). Do not infer wire-level status parity from the dispatcher's string.

## Route matrix

| Method | Path | Python | Android local | Request fields / query | Response contract / notes |
|---|---|---|---|---|---|
| GET | `/api/v1/health` | Yes, public | Yes | none | Health/status object; fields differ by runtime. Python creates trader/multi state to compute health. |
| GET | `/api/v1/status` | Yes, auth | Yes | Android: `fast=true|false` | Status object; Python exposes server execution state; Android status has local-runtime fields. |
| GET | `/api/v1/williams/core` | Yes, auth | Yes | none | Core contract object. Python calls `digital_williams.contract()`; Android emits a local contract object. Verify schema version/fields before treating as byte-for-byte parity. |
| GET | `/api/v1/config/binance` | No | No | — | Unsupported on both; credentials must not be returned. |
| POST | `/api/v1/config/binance` | Yes, auth | Yes | JSON: `api_key`, `api_secret`, optional `testnet` | Python: `configured`, `testnet`; errors include 400 and 409. Android response/error contract requires focused review. Never use real credentials in tests. |
| DELETE | `/api/v1/config/binance` | Yes, auth | Yes | none | Python refuses with 503 if account state cannot be verified, 409 if managed positions/open orders exist; otherwise clears credentials. Android semantics need separate local-runtime validation. |
| POST | `/api/v1/control/start` | Yes, auth | Yes | none | Object with `started` (Python); Android returns local start result. Side-effecting: tests must mock/stub, never start a live trading engine. |
| POST | `/api/v1/control/stop` | Yes, auth | Yes | none | Object with `stopped`; local implementations differ. |
| POST | `/api/v1/control/pause` | Yes, auth | Yes | none | Object with `paused`; local implementations differ. |
| POST | `/api/v1/control/resume` | Yes, auth | Yes | none | Object with `resumed`; local implementations differ. |
| POST | `/api/v1/control/panic` | Yes, auth | **No** | none | Python pauses new entries and explicitly does not close positions. Do not add to Android without product/security approval. |
| POST | `/api/v1/control/kill` | Yes, auth | Yes | none | Emergency behavior is safety-critical and differs by runtime; do not exercise against a real account. |
| POST | `/api/v1/control/kill/reset` | No | Yes | none | Android-only local latch reset; intentionally different until server requirement is approved. |
| POST | `/api/v1/control/sell` | Yes, auth | Yes | required `symbol` query parameter | Python 409 when reconciliation is required. Side-effecting; no live-order tests. |
| POST | `/api/v1/control/recover` | Yes, auth | Yes | none | Recovery result includes state/details in Python. Safety-sensitive; do not call against live services in contract tests. |
| POST | `/api/v1/control/self-heal` | No | Yes | none | Android-only safe local recovery orchestration; intentionally different. |
| GET | `/api/v1/positions` | Yes, auth | No | none | Python-only positions endpoint; Android status/portfolio may expose overlapping but not identical data. |
| GET | `/api/v1/portfolio` | Yes, auth | Yes | none | Portfolio object; Python and Android calculations/fields differ. |
| GET | `/api/v1/scanner` | Yes, auth | Yes | Android: optional `refresh=true|false` | Candidate list/snapshot; cache metadata and response fields differ. |
| GET | `/api/v1/market/klines` | Yes, auth | Yes | optional `symbol`, `interval` | Kline/candle data; compare defaults, limits and candle field names before claiming schema parity. |
| GET | `/api/v1/market/indicators` | No | Yes | optional `symbol`, `interval` | Android-only local computed indicators. |
| GET | `/api/v1/market/tickers` | No | Yes | none | Android-only ticker aggregation. |
| GET | `/api/v1/market/snapshot` | Yes, auth | No | inspect Python function for optional market fields | Python-only market snapshot. |
| GET | `/api/v1/trades` | Yes, auth | Yes | Python: `limit` (1–200); Android ignores query limit | Arrays of trade rows; query parity gap: Android does not apply the Python limit contract. |
| GET | `/api/v1/orders` | Yes, auth | **No** | Python: `limit`, optional `symbol` | Python order-history read route; absent from Android local API. Intentional only if local UI has no order-history consumer. |
| GET | `/api/v1/trade-stats` | No | Yes | none | Android-only local trade statistics. |
| GET | `/api/v1/trade-journal` | Yes, auth | No | Python: `limit` | Python-only journal route. |
| GET | `/api/v1/logs` | Yes, auth | Yes | Python: `limit` (1–300); Android local log snapshot | Response rows and limit behavior differ. |
| GET | `/api/v1/settings` | Yes, auth | Yes | none | Settings object; compare field names/defaults; Android includes device-local runtime fields. |
| GET | `/api/v1/diagnostics` | No | Yes | none | Android-only local diagnostics and self-tests. |
| GET | `/api/v1/diagnostics/incidents` | Yes, auth | No | Python: `limit` (1–100) | Python-only diagnostic incident records. |
| GET | `/api/v1/scanner/diagnostics` | Yes, auth | No | none | Python-only scanner diagnostic snapshot. |
| GET | `/api/v1/insights` | Yes, auth | No | none | Python-only learning summary. |
| GET | `/api/v1/quant/health` | Yes, auth | No | none | Python-only read-only quant/shadow status. |
| GET | `/api/v1/quant/features` | Yes, auth | No | optional `symbol`, `limit` (1–200) | Python-only feature rows. |
| GET | `/api/v1/quant/shadow` | Yes, auth | No | `limit` (1–200) | Python-only shadow records. |
| GET | `/api/v1/quant/shadow-execution` | Yes, auth | No | `limit` (1–200) | Python-only shadow execution records. |
| GET | `/api/v1/history/status` | No | Yes | none | Android-only local history lifecycle status. |
| GET | `/metrics` | Yes, public | No | none | Prometheus text exposition, not JSON; server observability route. |
| WebSocket | `/api/v1/ws` | Yes | No matching dispatcher route | WebSocket protocol | Python-only server push channel. Android uses its own local/runtime mechanisms. |

## Cross-cutting contract findings

- **Authorization:** Python routes marked with `Depends(auth)` require the configured mobile API token. `/api/v1/health` and `/metrics` are public; the core route is auth-protected despite a nearby stale docstring calling it public. Android is a loopback-only embedded server and does not use the same FastAPI dependency; verify bind address and any local access controls in the HTTP server wrapper before exposing it beyond loopback.
- **HTTP errors:** Python uses FastAPI status codes and JSON `detail` errors for HTTPException, plus validation errors for invalid inputs. Android's dispatcher has a JSON `error` fallback; status mapping belongs to the local server wrapper and is not proven equivalent here.
- **Timeout/retry/idempotency:** No shared client timeout/retry contract was established from the dispatch table alone. Do not blindly retry side-effecting controls (`start`, `kill`, `sell`, credential changes, recovery); order/execution semantics explicitly forbid blind order retries. Read-only GETs may be retried only according to each transport's configured timeout policy.
- **Confirmed parity issue:** Android `GET /api/v1/trades` currently ignores the Python `limit` query contract. This should be resolved only if the Android local API is required to preserve the shared contract; otherwise document it as a local API difference.
- **Potential required-parity gap, not an automatic defect:** Python `GET /api/v1/orders` and Android `POST /api/v1/control/panic` are not implemented by Android. Confirm consumers and product intent before adding routes.
- **No live order tests:** Contract tests should inspect route registration and use isolated/mock dependencies only. They must not configure real exchange credentials or invoke trading side effects.

## Follow-up verification needed

The source dispatch establishes method/path and some query inputs, but it does not by itself establish local HTTP status codes, auth boundaries, timeout values, retry policy, or full DTO equivalence. Verify the Android local HTTP server wrapper and its callers before claiming those dimensions are aligned.


## Verification addendum (2026-10-11)

### Confirmed by source inspection

- Android embedded server binds to `127.0.0.1:18080`, which limits network exposure to loopback on the device.
- Android's HTTP handler writes `HTTP/1.1 200 OK` for every successfully dispatched route result, including the dispatcher's `{"error":"Not found"}` fallback. Thus an unknown route is returned with HTTP 200, unlike FastAPI's 404. This is a confirmed protocol-contract defect.
- Android's handler maps caught exceptions to HTTP 500 with a JSON `error` field. It does not currently map input/validation errors to 400/422 or auth failures to 401/403 at this layer. Exact errors thrown inside route functions depend on exception type and are not normalized.
- Android server's OkHttp client is configured with 10-second connect timeout, 45-second read timeout, and `retryOnConnectionFailure(false)`. These are confirmed client settings for outbound calls made through this client, not a server-side request deadline for incoming loopback HTTP requests.
- Android route query parsing uses `URLDecoder` and `associate`; duplicate query keys collapse to one value (last value wins). Query parsing filters out items without `=`. Python/FastAPI may handle duplicate and malformed query values differently.
- The handler reads request body into a character array using a single `reader.read(chars)` call. A single read is not guaranteed to fill the requested buffer; requests with larger/fragmented bodies can be truncated. This is a confirmed robustness concern.
- Python `GET /api/v1/health` is unauthenticated; sensitive routes listed in the table use `Depends(auth)`. Android's loopback server does not share FastAPI's token dependency. Keep it loopback-only unless a separate local access-control design is implemented.
- Android's outbound Binance REST helpers include retry loops for selected transient failures and timestamp error `-1021`. Those are exchange transport retries, not retries of the Android local API. This audit did not invoke them.

### Static-test review

The route tests are source-text checks, not HTTP integration tests. They can detect selected route declarations and selected auth decorators, but do not prove actual HTTP status codes, middleware/dependency behavior, query validation, response DTO equivalence, timeout handling, or retries. The Android route parser is regex-based and should not be treated as a complete Kotlin parser. The auth decorator check should be validated by actually running the unittest command before relying on it.

### Test execution status

**Not run in this environment.** The connected GitHub operations allow reading and committing repository files, but do not provide a shell/runner for executing `python -m unittest tests.test_api_route_contract -v` or the complete Python test suite. No test pass/fail result is claimed. Run the command and the project's full available Python suite in a checkout of this branch; record exact output before marking this PR ready.

### Remaining blockers / recommended follow-up

1. Fix Android HTTP status handling so unknown routes produce 404 and malformed/invalid requests use a documented 4xx response without exposing secrets. Add loopback-only socket-level tests for unknown path, unsupported method, malformed body, missing required `symbol`, and handler exceptions.
2. Replace single-read request-body handling with a bounded read loop; reject invalid/oversized `Content-Length` explicitly.
3. Add isolated FastAPI TestClient tests using dependency overrides/mocks for 401/403, validation errors, 404, and documented 409/503 paths. Do not instantiate live exchange clients or call execution controls.
4. Verify Android UI/call-site timeouts and retry wrappers separately. Never automatically retry `sell`, kill/reset, credential changes, start/stop, or recovery unless operation idempotency is proven.
5. Fix and execute the static unittest suite; run the full Python tests and append exact commands, counts, and outcomes here and to PR conversation.
6. Review response DTO field-level parity endpoint by endpoint. Current matrix identifies route coverage and known differences but does not claim complete JSON schema equivalence.

No new routes were added, no trading orders were sent, and no database schema, Gradle, or CI files were changed.
