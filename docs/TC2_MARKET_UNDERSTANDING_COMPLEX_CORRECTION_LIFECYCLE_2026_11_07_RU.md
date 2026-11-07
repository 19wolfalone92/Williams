# Williams Bot 2.1 — source-indexed TC2 market reading and complex corrections (7 November 2026)

## Evidence sources (user-provided, SHA-256 verified)
| Book | Pages (physical PDF) | SHA-256 | Authority |
|---|---:|---|---|
| TC1, Торговый хаос 1 | 140 | `36f6822811088a06254009181480aa1c27af705a76960ecf0342023df803331c` | OBSERVE: Elliott degree, wave alternation, MFI, five bullets |
| NTD, Новые измерения | 156 | `855eb052353c852f208436952b12e46d9a18badf7c1dac7ade7580d57b9f4bb4` | OBSERVE: five dimensions, AO/AC, independent systems |
| TC2, Торговый хаос 2 | 224 | `055b7924f9a4d165294bf3e12069e74b121f6ca510f25ad310a07c94f0a6107f` | **ONLY EXECUTABLE STRATEGY** |
| Profitunity Trading Chaos Course | 122 | `fc57f707378087bfc5146db5e242c51ce8135e0b359f0c607305b364e0decfca` | OBSERVE: Level III, 4 terminal signs, wave stage |
These files match the fingerprints in `docs/williams_book_rule_registry.json`. OCR text or an indexed book page is not itself proof that every figure has been independently checked.

## Verified rule reading vs code
| Source and page | Meaning for decisions | Active implementation / evidence |
|---|---|---|
| TC2 120–132, particularly 125, 131 | WM1: prior-trend AO colour; price bar reverses and lies outside the Alligator; angulation; actual STOP break over high / below low; stop opposite bar extreme | `WilliamsMarketGeometry`, `WilliamsSetupRules`, `exactTc2WiseManPriceBarLevels`; angulation still a **numerical proxy**, not a verified drawing interpretation |
| TC2 137–139 | WM2: third *consecutive directional change* in AO colour (not third positive AO value), tied to its exact price bar; one of three admissible first Wise Men | `WilliamsTc2AoSourceProof` / `StandaloneRuntime.longCampaignSignals`; signal/fill separated |
| TC2 145–151, particularly 147 and 149 | WM3: two actual confirming bars to right, more than five bars with ties; at actual trigger price must be above (LONG) / below (SHORT) displaced red Teeth | `WilliamsSetupRules.selectWilliamsPendingFractal`, `WilliamsBookH1EntryPolicy`, source tick parity, H1 cancel/replace |
| TC2 153–165 | STOP-entry only, stage-dependent additions, protective H1 3- or 5-bar range or fractal; opposite triggered signal may require exit/reversal; do not confuse a *formed* signal with a fill | `WilliamsTc2Pyramid`, `FuturesStopTrailPolicy`, `Tc2OppositeExitLifecycle`, protected Binance coordinator; 3/5 comparison from draft #204 |
| TC1 53–57 | Simple zigzag vs complex W4 (flat, irregular, triangle, doubles/triples), wave alternation, four-wave whipsaw risk. Shape matters: ordinary ABC != proved complex W4 | `WilliamsComplexWave` alternatives, `WilliamsTc2Congestion`; shape is **candidate, not certified Elliott degree** |
| TC1 61–65; Course 77 | A particular wave should span ~100–140 bars on an *appropriate* frame; W3/W5 divergence, zone/fractal/squat on compatible degree; no unique count from one indicator | `WilliamsWaveHierarchy`, `WilliamsMarketPhase`, `WilliamsRecursiveWaveDegree`; Binance volume **proxy**, no automatic closure authority |
| Course 79 and 81–84 | Course's wave-campaign flow/stop-reverse and position sizes are different from TC2 1:5:4:3:2; never silently merge them | `WilliamsCourseCampaign`, `WilliamsCourseCorrection` are **OBSERVE ONLY** |
| NTD 34–138 | SMMA Alligator, AO, AC and source-specific zones and Balance Line; cannot replace TC2 trade rules | `WilliamsNtd* / WilliamsEditionRules` **OBSERVE ONLY** |

## What this commit series actually changes

1. **Recognize a complex pattern as a time-bounded price state, not an eternal label.** `WilliamsTc2CongestionEvidence` already screened fresh, confirmed *H1* triangles/complex price grammars. Now it checks all **closed H1 bars strictly AFTER the pattern becomes known**. If a real H1 close leaves the previously confirmed high/low range, the old pattern is marked `RESOLVED_BY_CLOSED_H1_PRICE`. A transient high/low wick does not suffice. This is an **engineering rule**, not a quantitative rule claimed from Williams.
2. **Only block new TC2 exposure inside an *unresolved* candidate's price range.** Still deny new initial entries, add-ons, pending replacements and new reversal exposure behind the runtime's existing mutable-order fence. Still allow ordinary flat/zigzag/sideways observations and fresh STOP-breakouts outside the structure under all ordinary TC2/Binance safety gates.
3. **Keep prior protection authoritative.** Market-phase analysis, alternative wave labels, source/OHLC contradictions and complex-correction hypotheses have `can_place_orders=false`. They cannot alter a confirmed signed Binance STOP, automatically close a position, override active safety exits, or convert NTD/Course observations to trades.
4. **Explain the result in Russian.** The detailed H1 diagnostic differentiates: no complex evidence, ordinary range, active geometry with signal inside (no new order), signal outside, already resolved historical candidate. Records retain formation times, known-at, broken boundaries and closing price time.
5. **Reflect older/senior hypotheses separately.** `WilliamsMarketPhase` and `WilliamsMarketNarrative` expose D1/H6/H4/H1/M15 complex-pattern alternatives with their internal and recursive confirmation status. Parent level alone is not an authorized entry veto; no unsupported assertion that the senior trend has reversed.

## How to interpret market movement correctly

Each market judgment distinguishes **facts** (genuine closed timestamps, exchange OHLC, signal bar level, Alligator/AO, known-at confirmation, signed trade/STOP) from **hypotheses** (motive 1–5 / corrective A–E, compound W–X–Y, W4 crossing zero, nested degree and future reversal). A new junior impulse against senior direction could be a corrective leg or a genuine reversal. Until competing scenarios are independently invalidated, the system must preserve those alternatives and avoid claiming absolute understanding. AO/AC/5–34 and wave momentum are correlated, **not** independent probability votes.

## Acceptance and remaining gaps (do not claim 100%)

- Golden charts: WM1 angulation as in figures 9.1–9.13, including parallelism, mouth touch, reversed sides, historical shorts.
- WM2 third-bar exact tick and expiry; WM3 5/6/7/9-bar tie/plateau vs Teeth **at real trigger**; both under closed H1 causality.
- Complex pattern counterexamples: confirmed triangle inside blocked, ordinary range allowed, simple ABC allowed, old range resolved by H1 **close** allowed, wick-only breakout remains blocked, structural history gaps fail closed, LONG and SHORT mirror, non-tc2 observer unaffected.
- Hierarchy: independently sourced D1/H4/H1/M15 closed bars, actual parent-child containment, alternative reconstructions and no future candles, complexity confirmed only to the degree supported by child pivots.
- STOP: exact source tick, signed Binance Demo protection proof before/after replacement; fail-closed if uncertain ACK, partial fill, disappearing app, reconnect, restart.
- Compare exit methods 3 and 5 bars and senior-regime caution on held-out history, quantify early stopouts **and** extra loss from delayed exits, fees, funding, slippage; do not optimize or certify from one sample.
- Exact HEAD Android CI, signed APK key identity, emulator API35/36, 24h on Samsung/device, trade history + verifiable order IDs.
- **No validated numerical percent** of all book rules or future market-prediction accuracy is available. Book equivalence and profitability are different tests. Psychology passages and discretionary illustrations do not map to fixed machine-readable rules without independently agreed interpretation.

## Tests added/extended
- `WilliamsTc2CongestionTest.completedH1CloseBeyondTriangleEndsTheOldComplexRangeVeto`
- `WilliamsTc2CongestionTest.wickBeyondBoundaryWithoutClosingOutsideDoesNotResolveTriangle`
- ten-symbol frozen H1 replay expanded to include explicit resolved regime.
- `WilliamsMarketNarrativeTest.seniorComplexCorrectionIsExplainedButCannotActAsTc2ExecutionAuthority`
- Existing regression suites for TC2 WM1/WM2/WM3, entry, replay, wave and native exchange safety remain mandatory.
