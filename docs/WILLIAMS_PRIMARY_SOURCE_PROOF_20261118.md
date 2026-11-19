# Williams Bot — verified primary-source receipts (18 Nov 2026)

These are independently inspected files in the user's private ChatGPT Library, **not GitHub files**. Public source-control does not receive copyrighted scans, and CI cannot claim to have these PDF bytes.

| Book | Physical PDF pages | SHA-256 of bytes |
| --- | ---: | --- |
| Trading Chaos (TC1, Russian) | 140 | `36f6822811088a06254009181480aa1c27af705a76960ecf0342023df803331c` |
| New Trading Dimensions (Russian) | 156 | `855eb052353c852f208436952b12e46d9a18badf7c1dac7ade7580d57b9f4bb4` |
| Trading Chaos, Second Edition (English TC2) | 257 | `eb734089bb19e72a92e023602bf558e43b7cb20aecf77469fdfe28f8e6cd3523` |
| Trading Chaos Course (English, scanned) | 122 | `fc57f707378087bfc5146db5e242c51ce8135e0b359f0c607305b364e0decfca` |

## Source-verified correction: TC2 WM3 extended right plateau

- Source: `Bill Williams - Trading Chaos 2nd Ed.pdf`, **physical PDF p.166**, printed p.137, **Figure 11.1(D)**. Both the diagram and the accompanying original text were visually inspected.
- Book invariant: an equal fifth-bar high does not invalidate the original central up-fractal; it delays completion until the **sixth** bar supplies the second strictly lower right-hand high. For down-fractals, mirror highs/lows. Confirmation cannot be credited before the requisite bar closes.
- Previous source-code gap: `selectWilliamsPendingFractal` and `confirmedWilliamsEditionFractals` rejected any right-side equality; `exactTc2ShortFractalLevels` and `exactTc2LongFractalLevels` hard-coded `confirmation=center+2`. These combined to silently discard the book's Figure D pattern.
- Implemented: equality on the right does not count as a flank; only two strictly lower/higher right flanks confirm, including extended formation windows. The selector and exact price source validators now share the confirmed book geometry; unobserved future candles are never consulted.
- Price fidelity: `williamsBookExactPriceTick` computes exact source-OHLC, one-exchange-tick stop-entry and protective levels; off-grid pivots are rejected, not rounded into entry authorizations.
- Regression proof: `WilliamsSetupRulesTest.tc2Figure11_1DFractalConfirmsAtSixthBarDespiteEqualFifthHigh`, `WilliamsEditionRulesTest.originalTc2Figure11_1DDelayedFractalIsNotLostInEditionObserver`, and `WilliamsSetupRulesTest.sourceFractalSelectorUsesDecimalExactBinanceTicksOrFailsClosed`. These tests cover no premature five-bar signal, sixth-bar acceptance, both directions, exact tick, consumed level, and malformed grid.

## Narrow scope

This receipt verifies **one TC2 rule with its original diagram**. Merely locating all four books is not full independent chapter-by-chapter certification or a prospective market win-rate result. The scanned Course remains image-only and needs deliberate page/figure review. All trade admission, signed STOP, daily loss, half-margin, Android endurance, and Binance Demo acceptance gates remain independent of this source note.
