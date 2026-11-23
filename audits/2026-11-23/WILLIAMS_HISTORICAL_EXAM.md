# WILLIAMS HISTORICAL EXAM — NOT VERIFIED (2026-11-23)

**NOT an independently adjudicated 300-window/150-campaign/50-replay TC2 exam.** This document distinguishes previously recorded proxy runs from full human-verified strategy acceptance.

## Previous baseline on PR #270, source SHA `301833bd0007817b6dd3ed0cfbd686ad2c72faf8`

GitHub Actions `45169894054` emitted:
- 90 frozen H1 snapshots from a **legacy** 15-symbol set (contained `UNIUSDT` instead of required `SUIUSDT`).
- Wave model classified **4/90** snapshots and abstained on **86/90**.
- Model agreement on these four classifications against an **independent implementation, not human experts**, was **25%**. Human expert annotations: **0**. This is **not** a trade accuracy number.
- Prior 15-symbol paper replay reported **499 hypothetical individual trades**, aggregate **-170.0596 USDT** across separately seeded per-symbol 5,000 USDT simulations. This is *not* a portfolio equity curve or live/demo PnL and includes the incorrect UNI contract.
- Paper trading code expressly did **not** model full pyramiding `1+5+4+3+2`, funding, liquidation, real order-book fill or signed Binance receipt.

## Repair in PR #271

- Added Binance public USD-M closed H1 SUIUSDT history, **480 contiguous rows** for 2024-11-09 00:00 UTC to 2024-11-29 00:00 UTC, provenance recorded in `app/src/test/resources/blind_wave_20241109_29_SUIUSDT.json`.
- Both `tools/replay_frozen_h1_five_symbol.py` and `tools/benchmark_blind_30_h1_wave_cases.py` now use the exact configured fifteen-symbol universe, with assertions against `trading_config.DEFAULT_FUTURES_SYMBOLS`.
- Current SUI price tick observed on Binance exchangeInfo as 0.000100; applying a 2026 filter to 2024 candles is explicitly an **engineering assumption**, not historical exchange rule proof.

**The previous 499-trade PnL is no longer comparable to the repaired cohort.** Revised historical result must come from successful HEAD CI, and still does not satisfy independent, sealed human-label or live Demo criteria.

## Unresolved acceptance

- 100 independently graded book cases: **NOT_VERIFIED**.
- 300 independent H1 windows: **NOT_VERIFIED**.
- 150 full TC2 campaigns with additions, bar/fractal trailing, actual priority of exits: **NOT_VERIFIED**.
- 50 deterministic repeats and >100 fault-injection scenarios: **NOT_VERIFIED**.
- Same-cohort fixed 3/4/5, adaptive and fractal stop comparison: **NOT_VERIFIED**.
- Strict blind expert validation, signed Demo read/write reconciliation, market fill/slippage: **NOT_VERIFIED**.
