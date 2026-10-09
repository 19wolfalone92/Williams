# WILLIAMS BOT 2.1 — Long/Short Execution Contract

Status: **IMPLEMENTATION IN PROGRESS — NOT LIVE-APPROVED**

Baseline: `3fe008d85c06c90ed00d8035484b22aae657317b`

Working branch: `audit/williams-2.1-futures-safety`

## 1. Product decision: both LONG and SHORT

The existing execution adapter uses Binance Spot REST routes (`/api/v3/order`). A Spot BUY/SELL lifecycle is not a short-position engine. To implement real short exposure without disguising a sell-to-close as an entry, the new directional execution path must use **Binance USDⓈ-M Futures**.

The strategy remains exchange-agnostic. The futures adapter is a separate execution implementation; legacy Spot code must not be relabeled as futures execution.

Official reference:
- USDⓈ-M Futures new order: https://developers.binance.com/docs/derivatives/usds-margined-futures/trade/rest-api/New-Order
- USDⓈ-M Futures conditional/algo order: https://developers.binance.com/docs/derivatives/usds-margined-futures/trade/rest-api/New-Algo-Order
- USDⓈ-M Futures general info and demo environment: https://developers.binance.com/docs/derivatives/usds-margined-futures/general-info

## 2. Direction is a domain concept, not an exchange-side shortcut

- LONG entry: BUY.
- SHORT entry: SELL.
- LONG exit or protective stop: SELL, strictly reduce-only or close-position.
- SHORT exit or protective stop: BUY, strictly reduce-only or close-position.

A SELL used to close a LONG is not a SHORT signal. Entry permission, campaign direction, exposure accounting and order side must remain separate fields.

The selected initial integration is one-way position mode. The adapter must reject Hedge Mode rather than silently changing a mode when open orders/positions may exist. Reversal is sequential: close and reconcile the existing exposure before opening the opposite direction. Do not run simultaneous long and short legs on the same symbol under this one-way contract.

## 3. Protective order contract

Normal order mutation uses `POST /fapi/v1/order`. USDⓈ-M Futures conditional orders (including STOP_MARKET / TAKE_PROFIT_MARKET) use the current algo endpoint `POST /fapi/v1/algoOrder`, with `algoType=CONDITIONAL`; old conditional routes must not be copied from historic examples.

- Initial stop-entry: conditional order with a stable `clientAlgoId`.
- LONG protection: SELL STOP_MARKET.
- SHORT protection: BUY STOP_MARKET.
- Full-position protective stop: `closePosition=true`, without quantity or reduceOnly.
- Explicit market exit: opposite side, quantity from authoritative position state, `reduceOnly=true`.
- Stable client IDs are mandatory; an unknown POST/DELETE result must be reconciled, never blindly resubmitted.
- Mark-price triggers are used by default for protection; the actual order/position state must still be queried after a trigger.

## 4. Initial operational limits

- Demo/Testnet base URL is the default.
- Mainnet requires both an explicit constructor opt-in and `ALLOW_LIVE=true`.
- Initial leverage ceiling is 1x; configuration above the ceiling is rejected, not silently clamped.
- Isolated margin and one-way mode are required by the initial futures execution path.
- No withdrawals are needed or requested.
- The bot must stop creating new exposure when data, risk, persistence, account state or order reconciliation is invalid.
- Exits, cancellations, protection and recovery must remain operable while new-entry admission is blocked.
- Kill/stop prevents new entries; it must not make an emergency reduce-only exit impossible.

## 5. Risk and strategy invariants

For LONG, structural invalidation is below the entry trigger. For SHORT, it is above. Directional sizing uses the distance from entry trigger to structural invalidation plus fee, funding and slippage reserves. Quantity is rounded down to exchange lot size and re-checked against min quantity, notional, margin, leverage and total portfolio risk.

A short is not opened merely because a long setup failed. Every direction requires its own Williams context, structure, location/angulation, momentum and valid price trigger. Higher-timeframe context can veto an entry; indicators alone are not independent permission. Add-ons must maintain the original campaign direction and undergo fresh aggregate risk calculation. Opposite valid structure may request a reversal only after close/reconciliation and new risk admission.

Funding, mark/index basis, maintenance margin, liquidation distance, ADL/exchange status and futures-specific filters are additional risks not present in Spot and must be represented in preflight, observability and tests before mainnet can be considered.

## 6. Verification gates

1. Unit-test direction-to-order-side mapping and reduce-only/close-position invariants.
2. Unit-test request signing, error classification, client-ID reconciliation and no-blind-retry behavior.
3. Test both LONG and SHORT campaigns on demo/Testnet with forced partial fills, timeouts, restarts and stop-trigger scenarios.
4. Prove startup reconciliation against actual positions and normal/algo open orders before enabling scans.
5. Run paper/live-data backtests with realistic fees, funding and slippage; report expectancy, drawdown, exposure and per-regime results.
6. Keep production status NO-GO until the full integration and release suite passes and the strategy has adequate out-of-sample evidence.

A passing adapter test is not evidence of a profitable strategy or production readiness.
