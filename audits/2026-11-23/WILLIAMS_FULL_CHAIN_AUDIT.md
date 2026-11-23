# WILLIAMS FULL CHAIN AUDIT — evidence snapshot (2026-11-23)

**Scope:** `19wolfalone92/Williams` ONLY; candidate v4.64.52 from PR #270, source `301833bd0007817b6dd3ed0cfbd686ad2c72faf8`. This is a **PARTIAL static + regression audit**, NOT full chain acceptance. Source PDFs, account identifiers, secrets and live position details are excluded.

## Actual end-to-end code touchpoints and verification state

| Component | Primary source inspected/identified | Status |
|---|---|---|
| REST / public klines / clock | `BinanceUsdMFuturesMarketData.kt` | PARTIAL; native-open UTC grid integrity code repaired, signed timing unverified |
| Persisted closed-candle cache | `FuturesCandleCacheIntegrity.kt` | PARTIAL; timestamp grid and gap checks added, restart integration not proved |
| Wave / structure causality | `WilliamsWaveHierarchy.kt` | PARTIAL; off-grid observation now fails closed, no independent expert W1–W5 certification |
| D1/H4/H1 context → setup | `WilliamsBookH1EntryPolicy.kt`, `NativeWaveDirectionContext.kt` | NOT_VERIFIED as a full executable path |
| WM1 / WM2 / WM3 signaling | `WilliamsSignalLifecycle.kt`, `WilliamsBookH1EntryPolicy.kt` | NOT_VERIFIED relative to all TC2 primary figures |
| Price trigger → risk → authorization | `NativeFuturesCampaignCoordinator.kt`, `FuturesHalfMargin.kt`, `FuturesDailyEntryHalt.kt` | NOT_VERIFIED as full signed multi-pair chain |
| Exchange execution and idempotency | `BinanceUsdMFuturesTestnetAdapter.kt`, `NativeFuturesCampaignCoordinator.kt` | NOT_VERIFIED for new audit commit with private Demo signed order receipts |
| Signed STOP replacement | `FuturesNativeStopReplacement.kt`, `FuturesTerminalStopRearm.kt` | NOT_VERIFIED with controlled Demo partial fills/lost confirmations |
| 3/4/5 bar trailing | `FuturesStopTrailPolicy.kt`, `WilliamsStopTrailReview.kt`, `Tc2AdaptiveBarChoice.kt` | PARTIAL source-level, complete signed STOP transition and out-of-sample trial missing |
| Exit attribution, fees and PnL | `NativeStopExitAttribution.kt`, `FuturesTradeFeeLedger.kt` | NOT_VERIFIED on signed Demo closeout |
| Android service / reboot | `TradingForegroundService.kt`, `TradingBootReceiver.kt` | Earlier API 35/36 UI-only smoke PASS; Samsung Android 17 and signed trade continuity NOT_VERIFIED |

This table identifies actual source files but **does not claim** all call edges, race windows, intermediate JSON transitions or failover paths have been exercised. That missing proof is an acceptance blocker.

## Source defects reproduced and patched

1. `WILL-CAUSAL-001` (P1): a continuous H1/H4 series shifted off Binance's UTC opening grid passed Kotlin wave chronology checks. `WilliamsWaveHierarchy.kt` now rejects it, with regression coverage.
2. `WILL-UNIVERSE-002` (P1): old 15-pair historical exam replayed **UNIUSDT**, not approved **SUIUSDT**. Approved universe equality is now an assertion, and authentic 480 SUI H1 candles from public Binance are frozen. Legacy UNI file retained for provenance, but no longer in the 15-pair exam.
3. `WILL-CAUSAL-003` (P1): native public/SQLite cache could accept off-grid but continuous M15/H1/H4, week or month candles. Both native fixed grid and UTC calendar month checks now enforced, including last-closed cache freshness.

Regression tests added or changed: `WilliamsCausalCandleGridTest.kt`, `FuturesNativeUtcCandleGridRegressionTest.kt`, `test_williams_blind_30_wave_review.py`, `test_williams_frozen_five_symbol_replay.py`. All changes require green head-SHA CI before acceptance.

## Existing baseline vs new evidence

- Prior PR #270 SHA `301833bd...`: Python 2746 passed, 2 skipped; Android build/lint passed; emulator API 35/36 UI/lifecycle passed with trading disabled.
- Baseline **does not certify** book fidelity, signed Binance Demo STOP reliability, profit, physical Android 17, or a stable update signing key.
- The new audit SHA and associated regressions must be assessed independently; old CI artifacts cannot certify new code.

## Hard restrictions

- `Williams-AI` untouched.
- No live trading. No signed Demo order mutation was attempted in this audit.
- No actual trading permission or main merge follows from static edits, book index coverage or green unit tests.
