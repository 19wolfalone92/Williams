# June 29, 2026 Mark Price repair overlay

This directory contains the 288 missing 5-minute Mark Price candles for each of the 15 approved USD-M Futures symbols.

- Source: official Binance USD-M Futures public Mark Price Kline endpoint, GET /fapi/v1/markPriceKlines.
- UTC coverage: 2026-06-29 00:00 through 23:55 inclusive.
- Original archive run: 45633468011.
- All 15 original June monthly archives were inspected from their existing GitHub Actions artifacts. Each had exactly 8,352 of 8,640 expected rows and the same missing 288 timestamps: June 29, 2026.
- The repair overlay contains 15 x 288 = 4,320 real API rows. It does not modify or replace the original monthly ZIP archives.
- Before backtesting, merge each overlay into its corresponding June Mark Price archive by open_time, reject overlaps, sort chronologically, and verify exactly 8,640 unique rows.
- No candles are interpolated. No contract-price klines are substituted.

See manifest.json for per-symbol SHA-256 digests and validation results.
