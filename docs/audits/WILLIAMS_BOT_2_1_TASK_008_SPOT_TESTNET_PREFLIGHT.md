# WILLIAMS BOT 2.1 — TASK 008
## Binance Spot Testnet preflight and controlled order cycle

**Status:** `BLOCKED — SOURCE AUDIT ONLY; READ-ONLY PREFLIGHT NOT EXECUTED`  
**Repository:** `19wolfalone92/Williams`  
**Observed main HEAD:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Base immediately before HEAD:** `3fe008d85c06c90ed00d8035484b22aae657317b`  
**Application version in `server.py`:** `4.25.0`  
**Python Binance client version:** no independent semantic version constant found in `binance_client.py`.  
**Audit branch:** `audit/williams-2.1-task-008-spot-testnet`

> No API key/secret values were read, requested, copied, logged, or put in this report. No Binance order mutation was sent. No Mainnet request was made. A successful read-only account preflight cannot be claimed without executing it in the authorized local Testnet runtime.

## 1. Executive result

The repository has the beginnings of a Spot Testnet-only manual read-only gate, and its REST and WebSocket endpoint strings are consistent with the current Binance Spot Testnet docs. The code also uses the post-listenKey user-stream protocol: `userDataStream.subscribe.signature` on the Spot WebSocket API, rather than REST `/api/v3/userDataStream` listenKey creation/keepalive calls.

However, the present `PreflightCheckService.verify_all()` can set `ready=true` after only a time sync, ping, account request, symbol/filter check, and open-order/open-order-list check. It does **not** prove the full preflight requested in Task 008. In particular, it does not verify usable/fresh balances, candles, market WebSocket freshness, private-stream event delivery, scanner heartbeat/candidate output, market-context freshness, diagnostic advancement, Android state parity, or a final exchange/SQLite reconciliation. Several exceptions are also allowed to escape rather than be returned as structured blocking checks.

The 30-entry release-gate tuple is a checklist/manifest, not 30 exercised safety mechanisms. Its current test functions check the manifest shape, textual partial-OCO phrases, endpoint constructor values, and opt-in defaults. The source file does not execute or prove the described end-to-end behaviors.

**Acceptance decision: NOT ACCEPTED. Preflight is unverified; trading remains blocked.** No controlled order cycle or failure-injection order test was run. Separate Task 008 trade permission remains unissued, as required.

## 2. Exact source reviewed

| File | Blob SHA on `main` | Finding summary |
|---|---|---|
| `binance_client.py` | `774b17505729b5b9f10d4f4c961e9defc046468e` | Testnet REST base, signed requests, time offset, order/trade reads, order methods, and signature-based user-stream params |
| `preflight_gate.py` | `f0ecf095aa57cb24ad979777c6a668292329f28e` | Blocking gate exists, but scope is materially narrower than the 14-point preflight and exception handling is incomplete |
| `test_testnet_release_gate.py` | `e7eeadff243f81c75c485f15ac4ff4fcfad6c642` | 30-step manifest; not a 30-step proof suite |
| `server.py` | `f7d93c014975949a7172323801f0fb485013895e` | App version `4.25.0`; status surfaces WS/scanner/preflight flags, but that is not evidence those mechanisms were actually healthy |
| `ws_hub.py` | `77df384294374ae80c5e4df6551211abdfcba3d1` | Testnet market stream and signature-based Spot user-data WebSocket API; reconnect path attempts REST resync |
| `credentials_store.py` | `bea12f00bd54cb36d23fd94f0f2fdff93918efb0` | Fernet encrypted credential file; key can be stored in a separate file in the same data directory |

The observed `main` HEAD commit changes only `README.md`, deleting its 202 lines. The compared head is one commit ahead of `3fe008d85c06c90ed00d8035484b22aae657317b`; this unrelated documentation deletion should be restored/reviewed separately. Combined status for the observed HEAD returned no status entries, which is not evidence of a green CI run.

## 3. Endpoints and user stream

### REST

The client selects `https://testnet.binance.vision` when `testnet=True` and sends Spot requests under paths such as `/api/v3/time`, `/api/v3/account`, `/api/v3/exchangeInfo`, `/api/v3/klines`, `/api/v3/openOrders`, and `/api/v3/myTrades`. The resulting Testnet account REST URL is therefore `https://testnet.binance.vision/api/v3/…`, consistent with official Spot Testnet documentation.

The release-gate helper constructs the client with `testnet=True` and checks its base URL before running the preflight. That proves only this helper's constructor setting; it does not prove every app runtime is permanently pinned to Testnet. Other code paths can construct `BinanceSpotClient(testnet=False)`; the trader currently relies on its separate `ALLOW_LIVE` guard when starting live execution. The Task 008 Testnet gate must reject a non-Testnet client unconditionally, regardless of live-mode permissions.

### Market WebSocket

`ws_hub.py` builds a combined stream against `wss://stream.testnet.binance.vision/stream?streams=…`. Binance's current Spot Testnet stream documentation specifies `wss://stream.testnet.binance.vision/ws` for raw streams and `/stream?streams=…` for combined streams. The combined form used by the code is consistent with that documented route.

### User-data WebSocket

The code builds `wss://ws-api.testnet.binance.vision/ws-api/v3` and requests `userDataStream.subscribe.signature` with signed parameters. The old REST listenKey create/keepalive/close endpoints are not used in the inspected path; `create_user_listen_token()` explicitly declines the legacy route. This is the correct protocol direction after Binance's 20 February 2026 Spot listenKey-system discontinuation. The endpoint/method strings are source-verified, but a real authenticated connection and real `executionReport` delivery have not been proven in a live read-only run.

## 4. Credential, signing, and error-handling findings

1. **Storage:** `CredentialStore` encrypts the saved payload using Fernet. It prefers `CREDENTIALS_ENCRYPTION_KEY`; if absent, it creates `data/credential_master.key` and attempts mode `0600`. Because the fallback master key sits alongside the encrypted credential file, this is encryption-at-rest against isolated-file disclosure, not a host-keystore boundary. Protect the entire data directory and its backups. For a phone-native deployment, this server-side store is not a substitute for Android Keystore-backed storage.
2. **REST signature:** the client uses HMAC-SHA256 over `urlencode(params)`, includes `timestamp`, and defaults `recvWindow` to 5000 ms. Time offset is calculated from Binance `serverTime`. No real signed request was executed in this audit; no claim of successful credentials is possible.
3. **Time check:** `_check_time()` calls `sync_time()` before a protective exception boundary. A DNS/TLS/timeout/API failure may abort `verify_all()` instead of returning a structured failed check.
4. **Failure reporting:** request exceptions include `str(exc)`; requests transport exceptions can contain a fully encoded request URL. Signed parameters include a signature. Error messages and structured logs should use endpoint/method/error-code summaries only, never raw request URLs, headers, signing payloads, credential fields, or raw exception objects.
5. **Spot/Futures separation:** the inspected public client is a Spot client using `/api/v3` and Spot Testnet hostnames. A dedicated configuration discriminator should make product `SPOT` explicit rather than relying on the generic `TESTNET` Boolean alone. Do not permit a Futures endpoint/configuration to pass the Spot release gate.
6. **Structured errors:** some gate stages are caught and reported, but exceptions in time/account/symbol/order checks are not consistently normalized. A preflight result should always carry a stable check ID, sanitized reason, timestamp, environment, and `ready=false` on an unhandled dependency failure.

## 5. Fourteen required read-only checks

| # | Check requested | Source status | Acceptance |
|---:|---|---|---|
| 1 | Environment is Binance Spot Testnet | Constructor and gate URL assertion exist; not an all-runtime proof | **Partial** |
| 2 | Connection works | REST ping exists in gate; Testnet ping not executed here | **Unverified** |
| 3 | Time is synchronized | `sync_time()` and offset threshold exist; no structured exception handling | **Partial** |
| 4 | Authentication succeeds | Signed account request is attempted in gate; no credentials used here | **Unverified** |
| 5 | Spot account is accessible | Account status/type checked if request succeeds | **Partial** |
| 6 | Balances are returned | Account response includes balances, but the gate does not validate non-empty/valid balance schema or freshness | **Missing gate** |
| 7 | Symbol metadata returned | `exchangeInfo` and `TRADING` status are checked for selected symbols | **Partial** |
| 8 | Quantity/price/notional filters | Presence of PRICE_FILTER plus LOT_SIZE/MARKET_LOT_SIZE and MIN_NOTIONAL/NOTIONAL is checked; actual order-quantity and price quantization is not tested by this gate | **Partial** |
| 9 | Candles available | Client has a klines method; gate does not request or validate candles | **Missing gate** |
| 10 | REST and WebSocket work | Source contains both connections; no readiness probe proving messages are fresh | **Missing proof** |
| 11 | Orders and executions readable | Open orders/order lists are checked; historical fills/myTrades are not checked by preflight | **Partial** |
| 12 | Scanner truly runs | Scanner status exists in app telemetry; gate does not start or verify a new scan/heartbeat/candidate list | **Missing gate** |
| 13 | Diagnostics advance | Status fields exist; gate does not assert changing timestamps/counters | **Missing gate** |
| 14 | Market context and Android are current | App has context/status fields; neither freshness nor Android parity is checked by preflight | **Missing gate** |

## 6. Release Gate P01–P30 audit

“Manifest only” means the item is named in `GATE_STEPS` but the included test functions do not exercise the described behavior.

| ID | Claimed gate | Evidence status |
|---|---|---|
| P01 | Testnet endpoint selected everywhere | **Partial** — Spot Testnet client URL is correct; only one helper's construction is asserted |
| P02 | Mainnet impossible without explicit live permission | **Partial** — trader has a live guard; release test does not comprehensively prove all execution entry points fail closed |
| P03 | API credentials accepted | **Not run** — needs local Testnet credentials |
| P04 | Signature/timestamp/recvWindow valid | **Not run** — no signed request/vector test in this gate |
| P05 | Account reachable and not locked | **Partial** — basic account status check; Testnet lock/trading-status path not checked |
| P06 | Exchange info and symbol status | **Partial** — implemented for configured symbols |
| P07 | PRICE/LOT/MIN_NOTIONAL enforcement | **Manifest only in this gate** — no boundary/rounding test here |
| P08 | Fresh, internally consistent market data | **Missing** — no ticker/klines freshness comparison |
| P09 | Scanner heartbeat/age updates | **Missing** — no scan is triggered or awaited |
| P10 | Intended universe/candidate set | **Manifest only** — no asserted universe/candidate output |
| P11 | Signal → Wave Engine → MTF gate binding | **Manifest only** — no trace linkage asserted |
| P12 | Prefer Wave 3 over exhausted Wave 5 where appropriate | **Manifest only** |
| P13 | Nested lower-TF Wave 3 inside higher-TF Wave 5 | **Manifest only** |
| P14 | Risk, RR, ATR, spread, breakers agree | **Manifest only** |
| P15 | Durable intent before submission | **Manifest only** — this gate does not verify SQLite commit ordering |
| P16 | Concurrent duplicate BUY blocked | **Manifest only** |
| P17 | BUY once and Binance order ID persisted | **Not executed** — no Testnet order was sent |
| P18 | Actual fills determine quantity and VWAP | **Not executed** |
| P19 | Native OCO created and verified on Binance | **Not executed** |
| P20 | OCO legs share one position quantity | **Text assertion only** — no behavioral partial-fill test in this file |
| P21 | One OCO leg partially filled while sibling remains active | **Text assertion only** |
| P22 | Residual inventory protected after partial OCO execution | **Text assertion only** |
| P23 | Crash after BUY before OCO | **Manifest only** — no failure injection in this file |
| P24 | Crash after OCO/exit event | **Manifest only** |
| P25 | REST catch-up closes WebSocket gaps | **Manifest only** |
| P26 | DB and Binance agree after restart | **Not executed** — no account/order reconciliation run |
| P27 | APK receives fresh backend state | **Not run** — requires app/backend integration |
| P28 | Clean reinstall does not restore credentials | **Manifest only** — no Android reinstall test |
| P29 | Portfolio balances/positions/orders agree | **Not run** |
| P30 | Final state CLEAN/PASS enables trading | **Not proven** — current read-only report can pass without the full runtime proof required by Task 008 |

The current tests establish that the tuple has 30 ordered IDs, that a few partial-OCO words exist in descriptions, that a Testnet client uses the expected base hostname, and that the manual read-only entry point requires an opt-in environment flag. They do not prove the listed runtime contract.

## 7. Required blockers before Task 008 acceptance

- Expand the read-only gate so it explicitly checks/records endpoint/product identity, sanitized connectivity/auth failures, account balance schema, per-symbol exchange filters, non-empty/fresh candles, REST order/fill visibility, and timestamps for the latest successful probes.
- Add independent market-WebSocket freshness proof and authenticated user-WebSocket subscription proof, including a post-subscription REST catch-up check; a connected socket without current events is not a pass.
- Make scanner and market-context readiness mandatory: require a scan generation newer than gate start, expected symbol universe, non-zero or explicitly explained candidate output, history/context freshness, and diagnostic heartbeat advancement. No stale cached snapshot should count as a pass.
- Add an explicit Android/backend freshness handshake. If Task 008 is run outside the Android device, mark this stage `NOT_RUN` and block final acceptance.
- Normalize all preflight exceptions to sanitized failures. Remove raw request URL/exception text from API errors before those errors can reach logs or diagnostics.
- Make P01/P02 test all order-entry paths and fail if the configured product is not `SPOT` or host is not Testnet. Testnet release gate must not permit a Mainnet fallback.
- Replace manifest-only P07/P10–P16/P20–P25/P28 tests with behavioral tests against deterministic mocks. Run them in CI with no live exchange credentials and no network order path.
- Keep trade E2E out of ordinary CI/APK build. Require a separate Testnet-only execution runner and a separately issued permission after previous tasks have passed. It must reject an absent/false Testnet marker, wrong host, Mainnet host, wrong product, or unclean reconciliation state before reaching the order mutation API.

## 8. Order-cycle and fault-injection status

**Not run.** This branch does not grant permission to place Testnet orders. No order IDs exist for this audit. No timeout, partial-fill, WebSocket-disconnect, process-restart, protection-recovery, cancellation, or DB/exchange divergence test was executed against an account.

When separately authorized, each order-cycle stage must persist intent before submission, use one stable client order ID, reconcile ambiguous results before retrying, derive final inventory from actual trades/commissions, verify the server-side protection quantity against residual inventory, close using the approved controlled path, and confirm that SQLite and Binance agree after restart. The report for that run must include the tested code SHA, sanitized order/list IDs, fills, commissions, realized PnL, and final reconciliation—not API secrets.

## 9. Official Binance references checked

- Spot Testnet REST API documentation: `https://developers.binance.com/docs/binance-spot-api-docs/testnet/rest-api`
- Spot Testnet WebSocket Streams: `https://developers.binance.com/docs/binance-spot-api-docs/testnet/web-socket-streams`
- Spot WebSocket API user-data subscription documentation: `https://developers.binance.com/en/docs/catalog/core-trading-spot-trading/api/ws-api/user-data-stream`
- Spot API changelog / listenKey discontinuation notice (effective 2026-02-20 07:00 UTC): `https://binance-docs.github.io/apidocs/spot/en/` and Binance notice dated 2026-01-21.

## Final decision

**TASK 008 = BLOCKED / NOT ACCEPTED.** Endpoint strings and parts of the gate are source-consistent, but the full read-only preflight has not been run and the code's current `ready=true` condition is insufficient evidence for the requested operational state. Do not run a trading cycle until the missing gates are implemented and proven, prior tasks are accepted, and separate Testnet order permission is issued.
