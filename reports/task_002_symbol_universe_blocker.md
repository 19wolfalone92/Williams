# WILLIAMS BOT 2.1 — Futures Symbol Universe Decision

**Status:** CONFIGURED — 10 selected liquid USDⓈ-M USDT perpetual candidates; exchange validation still required  
**Severity:** P0 acceptance gate until all ten validate and receive market-data/scanner evidence  
**Repository:** 19wolfalone92/Williams  
**Branch:** `futures-williams-campaign`

## Selected symbols

`BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT,DOGEUSDT,ADAUSDT,LINKUSDT,AVAXUSDT,LTCUSDT`

These are a practical initial universe selected by the user’s instruction to use ten popular pairs. This is a configuration decision, not a claim that every symbol is available on every Demo/Testnet environment at this moment.

## Runtime contract

- The runtime uses this fixed list by default; `FUTURES_APPROVED_SYMBOLS` may override it only with exactly ten unique USDT symbols.
- Every symbol must pass live USDⓈ-M `exchangeInfo` checks: TRADING status, USDT quote asset, PERPETUAL contract type, and required price/lot/notional filters.
- Any invalid/unavailable symbol blocks the universe as a whole. No silent substitution, top-volume ranking, or automatic expansion.
- LONG and SHORT candidates are both enabled by the Futures runtime; Shorts are Futures positions, not Spot sells.
- Safety remains `TESTNET=true`, `ALLOW_LIVE=false`, `DRY_RUN=true` by default.

## Required proof before readiness

1. Exact-HEAD CI passes after this change.
2. USDⓈ-M Demo/Testnet `exchangeInfo` validates all ten symbols.
3. Each symbol receives sufficient closed H1 history and a scanner evaluation; partial failures are reported by symbol.
4. LONG and SHORT signal paths pass deterministic tests, while DRY_RUN prevents order mutation.
5. Demo/Testnet order lifecycle tests prove entry, protective stop, close/reduce-only, restart recovery, idempotency, and unknown-state fail-closed behavior.
6. A debug APK is built from the same exact HEAD and its version/signature/manifest are verified.
7. Device-level install/start/diagnostics evidence is captured. CI build success alone is not device proof.

## Acceptance limitation

The ten symbols are now configured. This report does **not** claim the Demo/Testnet exchange has validated all ten or that an end-to-end Futures trade cycle has passed. Keep live Mainnet disabled until those proofs are complete.
