# WILLIAMS BOT 2.1 — Approved Futures Symbol Universe Blocker

**Status:** BLOCKED — approved ten-symbol list not recovered  
**Severity:** P0 — trading scanner must remain fail-closed  
**Repository:** 19wolfalone92/Williams  
**Repair branch:** `task-002-futures-runtime-repair-20261013`  
**Baseline:** `7909e77ccfe72ec7c07fecf22e6553d67466cde7`

## Sources inspected

1. Futures PR #36 branch `futures-williams-campaign` at the recorded baseline. Its `.env.example` declares `WILLIAMS_SYMBOLS=BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT` (five symbols) and `FUTURES_SCAN_TOP_N=40`; this is not evidence of the user-approved ten-symbol list.
2. `trading_config.py` at the same branch has `DEFAULT_SYMBOLS = ("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT")`, again five symbols.
3. `futures_williams_runtime.py` previously discovered all eligible USDT perpetuals from `exchange_info()`, ranked them by 24-hour quote volume, and returned the top N. That behavior does not preserve an approved fixed universe.
4. Commit search in `19wolfalone92/Williams` for symbol configuration / Futures universe found general configuration changes, but no commit that establishes an approved list of exactly ten symbols.
5. PR #36 description and the known baseline commit do not identify ten approved symbols.

## Correction applied

The Futures scanner no longer auto-discovers or ranks arbitrary contracts for trading. It now requires `FUTURES_APPROVED_SYMBOLS` to contain exactly ten unique, syntactically valid USDT symbols and validates each against Futures `exchangeInfo` status, quote asset, perpetual contract type, and required price/lot/notional filters. Missing, duplicate, or exchange-ineligible entries produce an empty universe with an explicit `last_universe_error`; no fallback list is invented.

Regression tests cover missing configuration, duplicate rejection, exchange-ineligible rejection, exact-ten validation, and prevention of dynamic additions.

## What remains blocked

- The original ten-symbol list still requires recovery from the user's approved project record, specification, or another authoritative source.
- No claim is made that any real exchange has validated ten symbols, that historical data is loaded, or that the scanner evaluated ten real symbols.
- The new configuration gate is currently a scanner boundary; integration of this canonical universe into all risk/execution/recovery/diagnostics/UI consumers still requires verification.

## Required next action

Recover the exact approved ten symbols from an authoritative project record. Then populate `FUTURES_APPROVED_SYMBOLS`, run the official USDⓈ-M Demo `exchangeInfo` validation and all-ten market-history/scanner evidence tests, and only then consider the universe acceptance criterion satisfied.

## Safety decision

Until the list is recovered and end-to-end verification passes, keep `DRY_RUN=true`, `ALLOW_LIVE=false`, and block new entries. Do not substitute Spot symbols, a guessed list, or top-volume discovery.
