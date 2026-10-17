# Williams BOT 2.1: wave-degree / zero-line audit (2026-10-17)

## Scope and evidence

Source checked directly in the user's reference PDFs, **not** just existing project summaries:

| Source | Actual PDF pages | Verified point |
|---|---:|---|
| *Trading Chaos* (first book, Russian edition) | 50-55 | Impulse 1-5 / correction A-B-C, nested degrees, W2 and W4 retracement areas, late W5 exhaustion. |
| *Trading Chaos* | 59-63, figures 7-17 to 7-20 | Profitunity MACD 5/34/5, W3 momentum peak, W4 zero-line minimum, 100-140 bars of the **wave sequence being counted**, smaller interval for disambiguation. |
| *New Trading Dimensions*, ch. 7 and 9 | 91-96, 115-122 | AO/AC zone, fifth zone bar trailing, red/green Alligator-line close-only stops, terminal-wave clues (target, divergence, fractal, Squat, momentum). |
| *Trading Chaos*, second edition, ch. 9-12 | 121-132, 153-165 | Angulated bullish/bearish divergent-bar First Wise Man; Super AO; confirmed fractal entries; 3-5 bar / fractal protective exits; campaign add-ons and partial exits. |
| *Trading Chaos Course* | PDF 39, 42-45, 77-84, 91-92 | Same W4 zero rule at correct scale; degree-based wave interpretation; planned trades and Tide / Wave / Ripple. |

## Newly validated distinctions

1. Williams's **Wave 4 zero-line diagnostic refers to the 5/34 oscillator of Profitunity MACD 5/34/5**. Its 5-period signal average is not the zero-crossing itself. AC = AO - SMA5(AO) is **not interchangeable** with this zero line.
2. With approximately **100-140 bars for the proposed wave sequence**, return/crossing of the 5/34 oscillator through zero after W3 marks the **minimum requirement for W4**, **not** completion of W4 and **not** automatic authorization to enter W5. A crossing can be observed at another scale while absent on the coarser display (TC1, Japanese yen example pp. 61-63).
3. A divergence *without* the appropriate zero-line behavior can be evidence of a **W3-within-W3 or W5-within-W3 structure**, rather than a completed higher-degree W5. Thus a naive close/reversal on every lower-TF AO divergence may be profoundly wrong.
4. Near zero **without crossing** is not book confirmation. Relative near-zero thresholds are engineering diagnostics, must remain marked **unconfirmed** and be calibrated; this patch uses 5% of the identified W3 AO extreme as a provisional observation threshold.
5. The **whole 100-140-bar sequence** matters, not the amount of cached history and not an arbitrary fixed 140-bar lookback. Actual exchange candles from the recommended resolution must be used; zoom or interpolation do not create new bar information.
6. Bill Williams has multiple exit approaches: close-only Alligator Teeth / Lips, five zone-coloured bars followed by trailing the subsequent candle, price-based last 3-5 bar extremes, and reverse-side signals. **AO divergence alone is not an unconditional exit**. Second-edition WM1 can enter before an Alligator change, whereas the earlier book's fractal-first entry policy is a distinct profile.
7. A 5-wave lower-TF cycle may be part of W1, W3 or W5 of a larger cycle. Assigning a parent by **timeframe alone** is invalid. The existing runtime MTF fields are *provisional hypotheses*, not confirmed wave genealogy.
8. Wave 2 often retraces roughly 38-62% of W1; Wave 4 is often discussed near 38-50% of W3. These are descriptive scenarios and never price-certainty or independent order triggers. Futures market can ignore these areas.

## Implemented in this PR

* Introduced `WilliamsWaveRhythm.kt`: a **pure** Wave-4 zero-line and resolution diagnostic. Direction-sensitive for LONG/SHORT, closed samples only, numerical validation, strict separation of crossing vs near-zero vs wrong resolution; `isTradingSignal=false`.
* Added diagnostic output `wave4_zero_line_diagnostic` to Android scanner candidate JSON using candidate **closed H1** candles and *confirmed* price pivots. Pivot confirmation timestamps are converted back to the underlying price-extreme timestamp. Output marks wave labels provisional and `can_place_orders=false`.
* Introduced `suggestWilliamsWaveScale` to select a real candle resolution near 120 bars from observed duration. This helper does **not** request, interpolate or synthesize candles and does **not** affect execution.
* Fixed Futures stop trailing when the latest confirmed pivot yields a currently **unusable** stop. The policy now considers an older eligible post-entry confirmed pivot before giving up, while retaining tightening-only and mark-price-gap conditions. Added symmetric LONG/SHORT regression cases.
* Added regression tests for missing W4 crossing, positive/negative W3, near zero, unsuitable resolution, malformed data and scale selection.

## Still absent / not implemented

**Do not advertise a complete hierarchical Elliott-wave trading brain.** This PR does not implement a proven full genealogy across D1/H6/H4/H1/M15, wave A-B-C classification, a calibrated scenario posterior, a production intraday entry engine, reversal orders, zone stops or other missing book pathways. Existing H1 authority and Binance Futures safety gate are intentionally unchanged.

Current `enrichWithMtf()` estimates relationships from nearest higher TF and may conflate coincident wave numbers with actual parentage. The existing bonus for a lower W3 inside a higher W5 must be revisited as a subsequent targeted change with a proper causal hierarchy test. It is **not** enough to concatenate MTF labels.

## Required follow-up and release gates

1. Record complete price pivot timestamps separately from **confirmation timestamps**; never use future candles to manufacture prior signals. Persist alternating candidate wave trees, parent-child *time intervals*, invalidation levels, observed evidence and revision history.
2. Fetch candidate alternative intervals for each 10 symbols via a rate-limited history cache. Include H6; preserve H1 decision authority until E2E tests validate another mode. Require all bars to be exchange-closed.
3. Add false-positive and nested-degree historical fixtures from the books' diagrams: W3-within-W3, W3-within-W5, W5 of H1 W1 vs H6 W3, ambiguous early AO green bars, and incorrectly scaled W4 crossing. No numeric classification can be claimed 100% accurate.
4. Implement separated `EXHAUSTION_WARNING / CANDIDATE_REVERSAL / VALIDATED_ENTRY / CLOSE` pathways: valid WM1/WM2/WM3 trigger and exchange risk approval is always required. Keep close, reverse, add, and protection changes transactional and independently idempotent.
5. Regression + no-lookahead walk-forward replay, then Android exact-HEAD CI, signed APK artifact, on-device Demo verification with 10 USDⓈ-M pairs, bidirectional cycle and real protection/reconciliation. CI green alone does not establish exchange-operational readiness.

## Release caution

The proof obligations in this document are **hypotheses to validate** on historical and Demo data, not promises of profitable trades. In particular, entry and exit cannot be triggered solely by W4 zero crossover, AO divergence or a numerical Elliott label.
