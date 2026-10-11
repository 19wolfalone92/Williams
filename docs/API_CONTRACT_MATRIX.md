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


## Verification report (2026-10-11; PR #64 follow-up)

### Scope and evidence labels

This report distinguishes executed CI results from source inspection. Source inspection is not counted as a passing HTTP test. No exchange credentials were supplied and no trading operation was invoked.

| Behavior | Expected contract | Actual evidence/result | Test or method | Python vs Android difference | Open issue |
|---|---|---|---|---|---|
| Unknown Android path | HTTP 404 with JSON error body | **Defect confirmed by source inspection:** the handler writes HTTP 200 for any normally returned `route()` string; route fallback is `{"error":"Not found"}`. Not runtime-tested. | Read `StandaloneRuntime.kt` handler and route fallback; no socket test executed | FastAPI unknown paths return 404; Android currently emits 200 for fallback | Blocking: implement status-bearing route result and add socket-level regression test |
| Expected exceptions / invalid input | Stable 4xx for client errors; 5xx only for server errors; documented error envelope | **Defect/risk confirmed by source inspection:** all exceptions caught by Android handler become 500 with `{"error":...}`. Not runtime-tested. | Read catch block and Python FastAPI exception/validation model; no HTTP test executed | Python uses HTTPException status + `detail` and request validation errors; Android uses generic `error` and 500 | Blocking: define exception-to-status mapping and test malformed JSON, invalid/missing parameters, and unexpected exception |
| Request body in chunks | Read exactly Content-Length bytes, bounded and reject malformed/oversized bodies | **Risk confirmed by source inspection:** one `reader.read(chars)` call; API does not guarantee full buffer fill. Not runtime-tested. | Read body parser; no fragmented socket request executed | FastAPI/ASGI handles body stream; Android uses a single character-array read | Blocking: bounded read loop and socket test sending chunks with pauses |
| Connect/read timeout | Requests fail within declared deadline and expose a predictable error | Source confirms Android OkHttp connect timeout 10s, read timeout 45s, `retryOnConnectionFailure(false)`. No timeout experiment performed; these settings do not set incoming server socket deadlines. | Inspect OkHttp builder only | No shared Python-vs-Android timeout contract demonstrated | Blocking: test with local delayed stub server; document Python outbound client policy and UI caller behavior |
| Retries / duplicate side effects | Retry policy must not duplicate non-idempotent actions | No runtime result. OkHttp automatic connection retry is disabled for this client; this does **not** prove application call sites never retry. | Static inspection only; full caller/retry graph not established | Not established | Blocking: inspect all HTTP call sites and any retry wrappers; add mock-call tests for timeout-after-commit and duplicate suppression. Never blindly retry start/stop/kill/sell/credential/recovery operations |
| Authorization | Protected Python routes reject missing/invalid token; public routes remain public; Android loopback API must remain loopback-only or have its own auth | Static source shows Python auth dependencies on sensitive routes and Android binds to 127.0.0.1. No live HTTP auth test run. | Existing static unittest source; bind/auth source inspection | Android does not share FastAPI's token dependency | Needs isolated FastAPI TestClient tests and local Android exposure/access-control decision |
| Query parsing and validation | Same required parameters, duplicate-key semantics and bounds where endpoints are declared equivalent | Static source shows Android parses query with `associate` (duplicate keys collapse; last value wins) and ignores Python `GET /api/v1/trades?limit=` contract. No HTTP test run. | Route/query source inspection | Duplicate-key and validation behavior can differ; Android trades ignores limit | Add parameterized HTTP tests for missing/invalid symbol, limit bounds, duplicate keys, URL-encoded values; decide whether `trades` parity is required |
| Response/error DTOs | Equivalent endpoints return documented fields and consistent error envelopes | No field-by-field HTTP comparison executed | Existing route matrix/source inspection only | Error envelope and endpoint-specific fields differ; route coverage is not schema parity | Blocking: add isolated response snapshots/schema assertions per shared route |
| Idempotency | Repeating a request must not cause duplicate side effects after an ambiguous timeout | Not proven; no request replay or fault-injection test performed | No call-site or socket test executed | Not established | Blocking: define idempotency for side-effecting controls; no live exchange tests |

### Actual test/CI results

- Requested command `python -m unittest tests.test_api_route_contract -v`: **not executed in this tool environment**. Therefore test count, errors and traceback are unavailable; no pass is claimed.
- Requested command `python -m unittest discover -s tests -v`: **not executed in this tool environment**. Therefore test count, errors and traceback are unavailable; no pass is claimed.
- GitHub Actions run [Python backend tests #2624](https://github.com/19wolfalone92/Williams/actions/runs/38173817428), associated with PR #64: **FAIL** at `python3 ci_release_gate.py`; traceback ends with `AssertionError: server.py: missing "VERSION = '4.24.0'"`. The gate pins `EXPECTED_VERSION = '4.24.0'`, while `server.py` declares `VERSION = '4.25.0'`. Subsequent production compile, static safety audit and safe offline tests were skipped. This is a version-contract gate mismatch; version/Gradle/CI changes are outside this API audit scope and were not made.
- GitHub Actions run [Williams Campaign CI #4921](https://github.com/19wolfalone92/Williams/actions/runs/38173817513): Python tests job **PASS**, with **159 passed, 4 warnings in 4.56s**. This is the workflow's listed pytest subset, **not** either requested unittest command and does not include the new route-contract test in its invocation. Android build/unit-test job was **in progress at the last observation**; no final result is claimed.
- The Actions jobs checked out merge commit `8c18d2da4bca77343f460ff9f6d634ab4fc892ef`, which incorporates PR head `0e54b01717c7e34c6a770cf4f5b87135c50dae90` and base `04da5f9c09572e99ff310be3a1908179165e528c`. PR head SHA reviewed: `0e54b01717c7e34c6a770cf4f5b87135c50dae90`.
- No manual workflow dispatch/re-run was attempted: the available GitHub integration exposes run inspection and failed-job reruns, but no workflow-dispatch action. Existing CI was inspected instead.

### Completion status

**BLOCKED / NOT COMPLETE.** Isolated HTTP tests have not yet been added or executed against the actual Android socket handler and FastAPI app; requested unittest commands remain unexecuted; Android CI has no final result in the evidence collected. Do not mark this PR ready or merge it until these are resolved. PR should remain Draft.

## P0 follow-up: implementation changes and remaining verification (2026-10-11)

### Branch and scope

- Branch: `audit/api-contract-parity`
- HEAD at this report: `f5848d6f501345b206ceceb3f4c8e087d33f4e5d`
- Compared with `main` at `04da5f9c09572e99ff310be3a1908179165e528c`: **16 commits ahead, 0 behind**.
- Files changed relative to main: `StandaloneRuntime.kt`, new Android `ApiHttpContractTest.kt`, new Python `test_api_http_contract.py`, `test_api_route_contract.py`, and this matrix. No trade execution/state-machine code, DB schema, Gradle files, or workflow YAML was changed. The Android runtime edits are limited to HTTP framing/error mapping and the local trades response limit.

### Expected vs implementation status

The “after” column below is **source-level implementation status**, not runtime confirmation.

| Behavior | Expected | Before | After in branch | Test evidence | Remaining difference / blocker |
|---|---|---|---|---|---|
| Unknown Android route | 404 JSON `{"detail":"Not Found"}`, matching FastAPI's status and detail envelope | Handler returned 200 with `{"error":"Not found"}` | Route fallback now throws a typed 404; response writer emits that status and a `detail` envelope | Android helper test for 404 mapping added; **NOT RUN**. No live socket handler test yet | Actual Android socket response still needs runtime assertion |
| Request-body framing | Read exactly Content-Length bytes; tolerate partial reads; reject incomplete/oversized body | Single character-reader call could return partial body and counted characters, not UTF-8 bytes | Raw input stream reads exact bytes in a loop; 1 MiB cap; incomplete body -> 400; socket read timeout 10 seconds -> 408 | Kotlin tests added for partial reads with multibyte UTF-8, incomplete and oversized bodies, and timeout mapping; **NOT RUN** | Real socket fragmentation/timeout test still required; chunked transfer encoding is not implemented and should be documented/explicitly rejected |
| Error status mapping | 4xx for request errors; 5xx for unexpected internal errors | All caught exceptions returned 500 | Typed request errors preserve status; unknown path -> 404; malformed JSON -> 422; invalid query encoding -> 400; unexpected exceptions -> generic 500 | Kotlin helper tests added; Python TestClient tests cover 404, auth 401, invalid limit 422; **NOT RUN** | Error envelope contents still differ: FastAPI validation `detail` is generally structured, Android uses a string; compare representative malformed-body responses at runtime |
| Query duplicates | For scalar query keys, last occurrence wins | Android `associate` collapsed duplicates with last value; behavior not tested | Explicit ordered map preserves last-value-wins behavior | Python TestClient and Kotlin parser tests added; **NOT RUN** | Runtime parity test on actual Android HTTP handler remains |
| `GET /api/v1/trades?limit=` | Default 50; integer values bounded to 1..200; newest-first results | Android ignored limit | Android parses limit, rejects non-integer with 422 and bounds numeric values to 1..200; `TradeJournal.trades` is newest-first, so bounded prefix is used | Python TestClient checks limit + duplicate query; Kotlin helper tests cover default/clamping/invalid values; **NOT RUN** | Actual route test on Android server remains |
| Authorization | Sensitive Python endpoints reject missing/invalid token; equivalent protected operations should have an explicit policy | Python uses bearer-token dependency; Android loopback dispatcher has no equivalent token dependency | **Unchanged intentionally pending compatibility/security decision**; Android server binds to 127.0.0.1 only | Python auth test added; **NOT RUN**. No Android auth test because no Android auth behavior was introduced | Loopback binding reduces exposure but is not equivalent to bearer auth; product/security owner must decide whether local callers are trusted or auth parity is required |
| Timeouts / retries / idempotency | Bounded request wait; no unsafe automatic replay of non-idempotent controls | Outbound OkHttp: 10s connect, 45s read, automatic connection retry disabled; incoming handler had no explicit read timeout | Incoming socket read timeout set to 10s; outbound settings unchanged | Timeout mapper test added; **NOT RUN** | Caller-level retries/idempotency were not exhaustively inspected; no fault-injection or replay test. No claim of idempotency |
| Success response DTOs | Shared endpoints should agree on fields/types | Not compared over HTTP | No broad DTO normalization attempted to avoid changing consumers blindly | No cross-runtime response snapshot suite yet | Blocking for full contract parity; route-specific differences remain in the route matrix |

### Test execution status

- `python -m unittest tests.test_api_route_contract -v`: **NOT RUN in this environment**; exact exit code, test count, and traceback unavailable.
- `python -m unittest discover -s tests -v`: **NOT RUN in this environment**; exact exit code, test count, and traceback unavailable.
- New `tests/test_api_http_contract.py`: added, **NOT RUN**.
- New Android `ApiHttpContractTest.kt`: added, **NOT RUN**.
- These are tests committed to the branch, not evidence that the real Android socket handler has passed isolated HTTP tests.

### CI and version gate

- Earlier [Python backend tests #2624](https://github.com/19wolfalone92/Williams/actions/runs/38173817428) failed in `ci_release_gate.py` with exit code 1 and traceback `AssertionError: server.py: missing "VERSION = '4.24.0'"`. The gate has `EXPECTED_VERSION = '4.24.0'`; both `server.py` and `app/build.gradle.kts` declare `4.25.0`. Main contains commit `1d2fcba4d76b3380576a46f05caa0acdf8bc01c4`, titled “Digital Williams APK: bump Android version to 4.25.0”, which is evidence that 4.25.0 is the intended version. The release gate appears stale, but it has **not** been changed because that is a release-policy/CI decision outside this API patch.
- Earlier [Williams Campaign CI #4921](https://github.com/19wolfalone92/Williams/actions/runs/38173817513): Python job passed `159 passed, 4 warnings`; Android job **FAILED** at `:app:lintDebug`, not at the unit-test task. Lint reported one error: `gradle/wrapper/gradle-wrapper.properties:3: A newer version of Gradle than 8.14.5 is available: 8.14.6 [AndroidGradlePluginVersion]`. Android unit tests were not reached.
- Current HEAD `f5848d6f501345b206ceceb3f4c8e087d33f4e5d` has newly observed workflow runs: Python backend tests #2641 **QUEUED**, Build Android APK #2266 **QUEUED**, Williams Campaign CI #4956 **PENDING** at the time of this report. No final result is available yet. These runs must be rechecked before any completion claim.

### Completion status

**BLOCKED / NOT COMPLETE.** The implementation changes and isolated test files are committed, but neither requested unittest command nor either new test suite has been executed in this environment. Actual Android socket-level parity, full response DTO parity, Android authorization policy, and idempotency/caller retry behavior remain unverified. Do not mark ready or merge; keep PR in Draft until test output and final CI results are recorded.
