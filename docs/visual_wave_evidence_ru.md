# WILLIAMS BOT 2.1 — visual wave and momentum handoff (2026-10-16)

## Status and source boundaries

Based on five user screenshots: NQ100 M15 bearish 1–5 structures, annotated discretionary decision process, alternate scenario/levels M15, projected A-B-C M15, and USD/CAD M15 bearish AO divergence; plus six-page Russian handout "Памятка для подсчета волн" (after D. Возный, "Код Эллиотта").

**The Vozny memo is an Elliott-taxonomy reference, not a Bill Williams book.** Screenshots and hand-drawn projected prices are candidate interpretations, not ground truth without the matching historical OHLCV and closed-bar replay.

**Williams and Elliott must stay separate.** Alligator, fractals, AO, AC, MFI, Wise Men and campaign lifecycle are signal/decision evidence. Elliott hypotheses are structural alternatives; neither alone grants Futures BUY/SELL authority.

## Image 1: NQ100 M15 bearish five-wave structures, nested degree, AO/AC

The down 1–5 overlays include smaller motive and corrective subwaves. A down W3 can show a strongly negative AO. A W4 is a countertrend recovery. An apparent W5 low with weaker negative AO is possible bullish divergence/exhaustion, not an automatic LONG. A rebound after W5 could be corrective A or a new uptrend: record alternatives and invalidation.

For both directions, inverted as appropriate, W2 must not breach W1 origin; W3 must extend beyond W1 and cannot be shortest of motive legs; W4 overlap invalidates an ordinary impulse but may suggest a diagonal hypothesis. Internal wave degree still needs verification.

## Image 2: discretionary ten-step trading process

Top down: D1 → H4 → H1 direction and targets, M15/M5 (M1 if required) wave position, beginning/acceleration/completion or correction, remaining potential, stop location, fully confirmed vs forming entry trigger, risk/reward, placement, management. Translate to sequential CONTEXT → STRUCTURE → SETUP → CONFIRMATION → PRICE TRIGGER → RISK → FUTURES EXECUTION → POSITION MANAGEMENT; not simultaneous indicator conjunctions.

Project trading requirements remain 10 USDⓈ-M Binance Futures Testnet USDT contracts, both LONG and SHORT, H1 decision timeframe, D1/H4 context, M15/M5 intraday observation and entry execution timing.

## Image 3: blue/green/red possible paths and horizontal levels

Hand-drawn colored lines show conditional alternative market paths. Future endpoints on the right are forecast scenarios, not completed bars. Track historical confirmed pivots, pending pivots, confirmed support/resistance, primary scenario, alternative scenario and their invalidations separately. A low-TF impulse can occur within a high-TF correction.

## Image 4: A-B-C chart

A and B are historical candidate swing labels; right-side C is explicitly a projection. An ABC correction, larger motive turn or complex combination may fit overlapping raw price sequences. The projected price is NOT a filled stop/entry/target. Check parent-degree context, Alligator, AO/AC, confirmed fractal and risk before acting.

## Image 5: USD/CAD M15 AO divergence

Marked price peaks are near/higher at the second peak, but the second AO positive hump is smaller. Treat as potential bearish divergence pending exact OHLC checks. AO may remain positive while momentum declines; AO/AC histogram color reflects CHANGE, not value sign. Alligator will often lag at the top. Divergence is a reversal/exhaustion warning, never sufficient for an automatic SHORT. The screenshot author explicitly reports a losing short and acknowledges failing to check full context.

Important: AO peak can precede corresponding price swing high. Comparing AO only ON the pivot candle (old Wave Engine behavior) can miss this. The new diagnostic compares the AO maximum on each completed up-leg or minimum on each completed down-leg, anchored to confirmed price pivots.

## Wave handout: rigorous separation of evidence

Ordinary impulse = five main waves with 5-3-5-3-5 internal sequence; zigzag ABC = 5-3-5; flat ABC = 3-3-5. A diagonal may overlap W1 with W4. W5 truncation may be valid. Compound corrections include W-X-Y and W-X-Y-X-Z; triangles involve A-B-C-D-E. Fibonacci ratios and alternation are context/expectations, not unique proofs.

Six alternating *confirmed* pivot centers prove only the skeletal topology, not the interior 5-3-5 degree. Therefore labels are IMPULSE_CANDIDATE, DIAGONAL_CANDIDATE, ABC_UNRESOLVED. Apply W3-shortest rule only once all motive lengths exist. A developing structure may legitimately stay INCOMPLETE rather than falsely be called invalid.

## Code implemented in this PR

- williams_wave_evidence.py: symmetrical UP/DOWN impulse six-pivot checks; W2 origin, W3 extension/not-shortest, W4 overlap vs diagonal, W5 truncation. Recent ABC is flagged only as alternative, not certain.
- AO leg divergence: compare two price swing highs/lows to two AO extrema measured over corresponding legs, record numerical anchors and confirmation indexes, with no future-bar reads.
- wave_engine.py: attach results as WaveSnapshot.structure.visual_wave_evidence computed from closed candles and already-confirmed pivots.
- test_williams_wave_evidence.py: seven standalone tests passed locally, including mirrored directions, invalidation, overlap, truncation, AO divergence and partial bars.

Explicit contract: authority = DIAGNOSTIC_ONLY; can_place_orders = false. This change does not alter primary wave labels, Williams entry logic, stops, risk rules or exchange calls.

## Not yet demonstrated

The screenshots are NOT numerical OHLC labels. Historical bar-by-bar replay and comparison to original annotations are pending. Rich primary/alternate scenario tracking, wave-degree nesting, exact AO/AC/MFI visual interpretation, Android chart DecisionTrace parity, and Binance Futures ten-contract LONG/SHORT end-to-end tests are pending. No Testnet-ready APK claim follows from this PR.
