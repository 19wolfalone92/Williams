# WILLIAMS BOT 2.0 — P0 Execution Scope and Closure Findings

Document: WMS-PCD-1.1
Status: controlled continuation; P0 remains PARTIALLY IMPLEMENTED.
Base: 3fe008d85c06c90ed00d8035484b22aae657317b
Working branch: audit/p0-controlled-corrections

## 1. Agreed project scope versus implemented execution

The repository README identifies the live execution universe as Binance Spot USDT pairs, with Binance Spot Testnet as the required initial environment. The Android application is primarily a remote cockpit for a backend/VPS process. Derivatives features are optional research/feature normalization and explicitly do not call an exchange API. No Futures, margin, or second-exchange order adapter was found in the reviewed repository tree.

| Market / mode | Project evidence | Actual execution implementation | Entry and protective orders | Deadline/cancel/recovery | Status |
|---|---|---|---|---|---|
| Binance Spot, campaign path | README; `BinanceSpotClient`; `CampaignExecutionService` | Implemented backend adapter to Spot REST | Initial/add-on BUY: conditional `STOP_LOSS` (trigger creates market order). Protection: separate SELL `STOP_LOSS`; Campaign Engine does not currently place native OCO for this path. | Signal deadline enforced before submit and checked during recovery; persisted conditional orders cancelled on recovery after expiry. No server-side absolute expiry established for the resting conditional order. Order lookup/cancel by exchange ID or client ID; ambiguous submissions require reconciliation. | Implemented with known deadline gap; no Testnet readiness claim |
| Binance Spot, legacy Trader/auto-scan path | `trader.py`; `apply_autoscan_integration.py`; P0 changes | Legacy autonomous `market_buy(quote)` now fails closed | Old code includes MARKET/OCO lifecycle and recovery, but the legacy BUY entry method is disabled because it cannot satisfy the canonical Williams signal/expiry/context contract. Existing exits/recovery remain separate paths. | Legacy OCO API wrapper exists; this is not the approved Campaign Entry path. Must not be mistaken for current campaign execution semantics. | New autonomous entry disabled; legacy open-position recovery needs separate regression coverage |
| Binance Spot market-data / Android | README; Android Binance stream classes | Market-data stream, account/state UI and backend integration components exist | No evidence that Android UI is the canonical autonomous order executor; server-side Python campaign adapter is the reviewed entry path. | Stream reconnect and display are not order-deadline guarantees. | Partial component scope; integration not verified |
| Binance Spot Testnet | README and release gate | Testnet configuration and read-only/release-gate workflows exist; deterministic mock tests exist | No real Testnet order exercise was performed in this closure task. | Mock tests do not establish matching-engine behavior or real cancel/trigger races. | NOT VERIFIED |
| Binance USDⓈ-M / COIN-M Futures | No executable exchange adapter or Futures order endpoint found | No actual order adapter found | No implemented Futures order contract | No guarantees can be claimed | Not implemented |
| Margin / borrowing | No executable margin order/borrow adapter found | No actual adapter found | Not implemented | Not applicable | Not implemented |
| Other exchanges (Bybit, OKX, Coinbase, Kraken, etc.) | No matching order adapter found | Not implemented | Not implemented | Not applicable | Not implemented |
| External derivatives data / shadow execution | `derivatives_features.py`, `shadow_execution.py` | Feature normalization and no-order simulation only | No exchange order submission; no account credentials in shadow path per README | No live order lifecycle guarantees | Research-only, not a trading venue adapter |

### Scope consequence

The common Execution Contract can be specified now at the abstract intent/state level, but exchange-specific deadline and order semantics must remain adapter-specific. The current concrete implementation is Binance Spot. Do not silently extend its assumptions to Futures, margin, or another exchange.

## 2. Four separate time properties

1. **Entry-admission deadline:** the local barrier refuses to initiate a new submission once the signal deadline has passed.
2. **Intent lifetime:** the intent must have a valid creation timestamp and positive maximum age; the barrier rejects missing, future, or stale creation times.
3. **Exchange-order lifetime:** the current Binance Spot conditional `STOP_LOSS` has no documented per-order absolute expiry parameter in the reviewed Spot `POST /api/v3/order` contract. `timeInForce` applies to supported working-order types, not a TTL for the untriggered conditional order.
4. **Actual execution time:** if the exchange-hosted conditional order remains active, it can trigger independently of the client. A local cancel request may race with trigger/acceptance and cannot retroactively guarantee no fill after the signal deadline.

The Binance Spot glossary defines `STOP_LOSS` as a conditional order that places a MARKET order when the stop price is reached; `STOP_LOSS_LIMIT` places a LIMIT order. The glossary describes `timeInForce` in relation to how long an order remains active on the book. These semantics do not establish an absolute deadline for an untriggered conditional order.

References:
- Binance Spot REST API: https://developers.binance.com/en/docs/products/spot/rest-api
- Binance Spot API Glossary: https://developers.binance.com/en/docs/products/spot/faqs/spot_glossary

## 3. Deadline architecture options — decision required

### Option A — retain exchange-hosted conditional entry

- Williams price-trigger semantics and low client-side trigger latency are preserved.
- The application enforces admission deadline and best-effort cancellation/reconciliation.
- Residual risk: the order can trigger after signal expiry while the process/network is unavailable, or during a cancel/trigger race.
- Requires explicit acceptance of that residual risk. Must not be described as a strict execution deadline.

### Option B — client-managed trigger, then MARKET submission

- No resting exchange conditional BUY exists before the client detects the trigger.
- Client can refuse to initiate a new submission after deadline and can pin context immediately before sending.
- Changes operational semantics: market-data delay, disconnects, missed events, client scheduling and network latency affect the trigger; slippage is not bounded.
- It still cannot prove the exchange will accept/process the request before deadline after a local check; strict atomic server-side deadline semantics remain absent.
- Requires dedicated event-gap, latency, duplicate-event, reconnect, restart, ambiguous-submit and slippage tests.

### Option C — client-managed trigger, then LIMIT submission

- Adds a price bound but can miss the trade or fill only partially.
- The submitted LIMIT order has its own exchange lifetime semantics and must be reconciled/cancelled; no absolute TTL should be assumed without a supported parameter.
- Changes execution semantics and requires separate approval and regression coverage.

### Option D — require an execution venue/order type with a documented server-side deadline

- Potentially the cleanest path for strict expiry, but only if the selected venue/type documents that deadline at the matching/trigger layer.
- No such capability has been established for the current Binance Spot conditional order.
- Requires venue/API research and an approved adapter; not implemented.

**No alternative is selected by this document.** Keep current trading semantics unchanged until the architecture owner explicitly accepts Option A's residual risk or approves a different model. If strict no-fill-after-deadline is mandatory, the current Binance Spot conditional model cannot currently be certified against that requirement.

## 4. ExecutionBarrier contract correction

The controlled branch now adds checks for:
- nonempty intent ID;
- normalized Spot symbol format;
- side in BUY/SELL;
- recognized order type, with autonomous entry order types limited to the current supported Spot model (`MARKET` or `STOP_LOSS`);
- mandatory client order ID for entry, with a conservative 1–36 character allowed-character check;
- exactly one positive finite base quantity or quote quantity;
- positive integer creation timestamp and max age; rejects future creation time and stale intent;
- nonempty signal ID, positive integer absolute expiry, permission interval, required version mapping, campaign identity for campaign purposes;
- nonnegative integer context versions, present matching context, positive finite context price and valid non-future candle close timestamp;
- mandatory successful persistence of the entry intent before submission; absent/failing persistence blocks new entries;
- revalidation immediately before the exchange submission.

A maximum age for already-published market context is deliberately **not** invented here. Version equality and valid data shape do not prove freshness. The age policy requires a separately sourced/approved rule per relevant context role/timeframe. Current validation rejects malformed/future context timestamps but does not claim that an old timestamp is fresh.

Protection/exit/cancel/reconciliation purposes are not subjected to the autonomous entry identity/expiry contract, so they remain available when entry admission is blocked. Durable entry status failure after submission is treated as reconciliation-required, not as a clean success.

## 5. Recovery semantics and implementation discrepancy

The current Campaign Engine's protective path uses a separately managed hard SELL stop, not native OCO. The legacy Trader has native OCO creation/recovery code, but its uncontracted autonomous BUY entry is fail-closed. Tests must target the currently enabled Campaign Engine path rather than revive the legacy BUY harness.

The branch also introduces a scoped submission capability in `execution_authority.py`. The production `BinanceSpotClient.order_safe()`, raw `order()`, and `cancel_replace()` reject BUY submissions unless the synchronous call is inside the final ExecutionBarrier submit scope. The barrier opens this scope only after successful entry validation and required pre-submit checks. This is defense-in-depth against accidental direct-call bypasses; code review is still required for any new raw REST mutation path.

The branch now adds recovery for the case where:
- the conditional BUY is authoritatively found filled;
- protective-stop setup failed before local fill state was committed;
- restart reconciliation finds the same durable client order ID;
- the campaign is returned to the controlled entry-recovery path without placing another BUY;
- a unique existing managed protective stop is adopted if its quantity and stop price match; multiple/mismatched stops fail closed.

This is still mock-based regression coverage. It is not proof of live Binance behavior, and ambiguous exchange responses remain reconciliation conditions.

## 6. Context freshness decision

Do not use a universal HTF age threshold without methodology evidence. Required policy inputs are:
- timeframe and role of context;
- whether its candle is expected to be closed or live;
- source timestamp and data availability/connection health;
- whether the signal model uses this context as a hard permission or descriptive evidence;
- an approved maximum age or an explicit fail-closed rule for missing/late data.

Until this is approved, context version pinning, valid timestamps and direction permission are necessary but insufficient to claim full freshness verification.

## 7. Android build compatibility correction

The Gradle wrapper and both Android CI setup declarations were aligned to Gradle 8.14.6. The root Android Gradle Plugin is 8.13.2 and Kotlin plugins are 2.3.21; the selected Gradle version is above AGP 8.13's minimum Gradle 8.13 baseline and uses JDK 17 as configured. Actual build/lint/unit-test status must be taken from the workflow run for the final SHA, not inferred from this compatibility check.

## 8. Remaining blockers

- Decide whether Option A's best-effort expiry is acceptable, or approve a different execution model.
- Approve a sourced context-age policy.
- Complete CI and review results on the exact final SHA.
- Complete route-level tests for all autonomous entry surfaces and all protection/recovery paths.
- No Futures, margin or other exchange implementation is present; those markets cannot be called supported.
- No Testnet or real exchange order exercise is authorized by this work.
