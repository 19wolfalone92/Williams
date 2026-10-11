# WILLIAMS BOT 2.1 — TASK 001.2
## Integration Contract Matrix — TASK 002–008

**Result: BLOCKED — CROSS-TASK CONTRACT COMPATIBILITY NOT PROVEN ON ONE INTEGRATION SHA**  
**Audit ref / PR #47 head:** `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc`  
**Base/main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**PR #47:** OPEN, DRAFT, NOT MERGED  
**Scope:** contracts, versioning, order/fill identities, risk, campaign, recovery, Android, Spot Testnet and release gate.

## 1. Contract matrix

| Contract | Producer → consumer | Required canonical fields / invariant | Source evidence at audited branch | Compatibility status / blocker |
|---|---|---|---|---|
| Source provenance / version | TASK 002 → all modules and release artifact | source SHA, base SHA, versionName/versionCode, build variant, APK SHA-256, test evidence all refer to one candidate | PR #47 base `04da5f9c...`, head `cb0a5b1...`; `ci_release_gate.py`; Android Gradle metadata and CI workflows | **PARTIAL**: branch/base known; installed APK ↔ source and device artifact provenance not proven |
| App version contract | `ci_release_gate.py` ↔ `server.py` ↔ Android metadata | one explicit release version; mismatch fails release | Architecture audit reports main gate expected 4.24.0 while server/app metadata were 4.25.0; branch aligns gate to 4.25.0 | **SOURCE CHANGE OBSERVED, RELEASE ACCEPTANCE BLOCKED** until exact build/release checks tied to current candidate |
| Market-data context | Binance REST/WS + candle store → `mtf_context_service.py` / strategy / barrier | symbol, interval, closed-candle close time, monotonic context version, stale/gap flag, permission timeframe | `mtf_context_service.py`, `market_context.py`, `execution_barrier.py` | **PARTIAL**: version/freshness validation exists; all producers, Android parity and gap/reconnect proof not established |
| Williams signal | TASK 004 strategy → PendingSignal / CampaignEngine | deterministic signal ID, symbol/side, source bar time, invalidation, context versions, confirmed fractal timing, strategy version | `strategy.py`, `digital_williams_core.py`, `williams_signals.py`, campaign model | **BLOCKED**: source-to-code and Python–Android parity not accepted by this architecture audit |
| Risk proposal / reservation | TASK 005 Risk Engine → Campaign / OrderIntent | Decimal monetary values, equity snapshot/time, stop/invalidation, qty/notional, fees/filter rounding, reservation ID, risk limits | `campaign_execution.py`, CampaignEngine and DB reservation persistence | **PARTIAL**: risk/capacity checks exist; single reservation owner, atomicity and exact Decimal contract not proven |
| OrderIntent | campaign/risk → ExecutionBarrier / Android gate | stable intent ID, clientOrderId, symbol, side/type, purpose, campaign/signal IDs, context versions, risk reservation ID, creation time | `execution_barrier.py` OrderIntent; `db.py.execution_intents`; Android mutation-intent journal | **PARTIAL**: Python and Android intent records are different schemas and stores; no cross-platform shared identity or fencing |
| Binance order identity | OrderIntent → Binance → reconciliation | local intent ID; stable `clientOrderId`; exchange `orderId`; order-list ID where relevant; symbol/side; status; executedQty | `binance_client.py`, `execution_barrier.py`, DB orders; Android audit orders table | **BLOCKED**: mapping completeness across cancel-replace/OCO/restart not tested |
| Fill identity / exactly once | Binance trade/user event + REST history → accumulator → all projections | stable exchange trade/fill identity scoped to symbol/order; executed quantity, quote, price, commission amount+asset, event/transaction time; idempotent financial-effect key | Python execution accumulator/DB fill records; Android `ExecutionAccumulator.kt` and `TradingAuditStore.executions` | **BLOCKED**: event dedupe does not prove every downstream effect is applied exactly once |
| Campaign / Position | fills + reconciler → Campaign/Position projection | one owner, campaign ID, position key, confirmed executed quantity, average entry, open/residual quantity, state version | Python `campaign_engine.py`, `campaign_execution.py`, `portfolio_trader.py`, `trader.py`; Android `StandaloneRuntime.kt` | **BLOCKED**: multiple Python writers plus independent Android state; ownership conflict remains |
| Fees / PnL ledger | Fill + fee data → financial effect applier | Decimal/scale, fee amount/asset, conversion provenance, gross/net PnL, trade identity, applied-effect identity | `db.py`, campaign/portfolio/trader persistence; Android TradeJournal/TradingAuditStore | **BLOCKED**: no proven atomic transaction over fill, fee, PnL, trade, position and Campaign projections |
| Recovery / UNKNOWN | execution barrier / adapter → recovery and release of entry lock | durable intent before network; UNKNOWN never maps to REJECTED/FLAT; reconcile by stable identity; no blind retry | `binance_client.py` documents no blind POST/DELETE retry; `ExecutionBarrier.reconcile_unresolved_intents`; Android mutation journal | **PARTIAL**: recovery components exist; complete fault-injection, multi-process and Android-device evidence absent |
| Protection / cancel-replace | campaign stop logic → order-list operations → position reconciliation | old/new order IDs, cancel result, replacement result, executed quantity, protected residual amount | Python campaign/portfolio protection paths and Spot order-list routes; Android stop/OCO paths | **BLOCKED**: complete operation-state matrix and actual Testnet lifecycle not supplied |
| Kill Switch / Safe Mode | global control → every entry caller and exit path | durable latch/version, reason, timestamp; entries blocked globally; exits reconcile to exchange truth | Python server/control path and Android ExecutionGate/runtime guards | **BLOCKED**: global cross-platform latch and all-route coverage not proven |
| Android runtime / authority | TASK 006 APK → credentials/runtime/execution | APK SHA ↔ source SHA, installed package/version, exact endpoint, Keystore credential store, runtime logs, one executor per account | `StandaloneRuntime.kt`, `TradingAuditStore.kt`, Android APK workflow | **BLOCKED**: no physical-device evidence or installed artifact provenance in this task |
| Spot Testnet configuration | TASK 008 runtime → BinanceSpotClient / Android endpoint | explicit Testnet-only host, credentials source, config hash, clock sync, symbol filters, no Mainnet fallback | `binance_client.py` defaults to Testnet but supports `testnet=False`; Android runtime has signed REST path | **BLOCKED**: default is not an enforced release invariant; full lifecycle evidence missing |
| Final release gate | TASK 009 → release | one candidate integration SHA; all required CI checks complete; all P0 accepted; artifacts and evidence manifest immutable | `.github/workflows/python-ci.yml`, `campaign-ci.yml`, `android-apk.yml`, `ci_release_gate.py` | **BLOCKED**: green CI does not establish device, Testnet, ownership, or recovery acceptance |

## 2. Identifier contract that must be made explicit

The implementation should preserve distinct identities; do not overload one identifier for another:

1. `signal_id` — deterministic strategy event; never proof of order.
2. `campaign_id` — lifecycle identity; never an exchange order identity.
3. `intent_id` — local durable requested mutation.
4. `clientOrderId` — stable exchange-facing idempotency/reconciliation key.
5. `orderId` — exchange order identity; may be unknown until reconciliation.
6. `orderListId` / client order-list ID — OCO/order-list identity.
7. Exchange `tradeId` / fill identity — one actual execution event.
8. `financial_effect_id` — idempotency key for applying that fill to fee/PnL/trade/position/Campaign projections.
9. `risk_reservation_id` — reservation lifecycle independent of order acknowledgement.

For cancel-replace, persist both old-order and replacement-order identities and the operation identity before dispatch. For UNKNOWN, preserve uncertainty instead of fabricating an exchange ID or converting to rejected/flat.

## 3. Required compatibility tests

- Python signal → CampaignEngine → OrderIntent → barrier contract round-trip, including missing/stale context and invalid risk reservation.
- Python and Android fixed-fixture parity for symbol/side/trigger, signal timestamp, invalidation and entry permission; no M15/M5-created signals where the canonical strategy contract forbids them.
- Duplicate/reordered fill events, fee update after fill, partial fill, commission in non-quote asset, and restart at every persistence boundary.
- Cancel-replace/OCO old/new outcome matrix including timeout after exchange acceptance.
- Python/Android attempts to execute simultaneously for one account; prove one authority is fenced and the other cannot mutate.
- Testnet order → partial/full fill → protection → cancel/exit → process restart → reconcile, with exchange IDs and config hash.
- Release workflow checks exact candidate SHA and reports version mismatch, missing APK artifact or incomplete required checks as a failure.

## 4. Cross-branch contract control

The branch search previously identified TASK 002–008 working branches, but the existence of a branch is not proof of its HEAD, PR base, accepted schema or tests. This audit has not established a common integration SHA for those branches. No other task branch was changed.

**Final status: BLOCKED.** The contract fields and required compatibility gates are defined, but end-to-end compatibility is not proven on one candidate SHA.
