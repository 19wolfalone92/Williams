# WILLIAMS 4.29.2 — complex waves / Course / NTD implementation ledger

## Verified source boundaries

* Trading Chaos, first edition, chapter 7: nested impulse and AO 5/34 wave rhythm.
* Trading Chaos Course, Level III, PDF pp 77–84, especially Planned Profitunity Trades examples 3/1/6/10/3/6/flat. They are **illustrations**, not a perpetual fixed leverage schedule.
* New Trading Dimensions, chapter 8, PDF 99–114: NTD fifth-dimension Balance Line research implementation from 4.29.1 persists and is edition-separated.
* Trading Chaos 2, chapters 9–12: independently executable TC2 Three Wise Men runtime must remain safe.
* Complex zigzags/flats/diagonals/triangles/WXY (general Elliott terminology) are **engineering interpretation hypotheses**. These are **not** complete mechanically verifiable rules specified by the books.

## Critical bug discovered and fixed

An ordinary impulse has **five motive legs but six endpoints**. The previous `hypotheses_from_anchors()` and `williamsHypothesesFromAnchors()` mapped two pivots to W2, five to W5 and six to **A**. This is a one-wave count error capable of mistaking the terminal impulse for a correction and falsely admitting countertrend entries. Both languages now map 2..6 pivots to **W1..W5**, 7 to **A**, 8 to **B**, 9 to **C**. Live-price invalidation now checks a six-pivot completed impulse. Existing fixtures updated.

**Important effect:** A formerly "successful" 4-frame countertrend test was actually *false positive*; its four-pivot higher-scale segment is W3, not W4. It is now explicitly required to BLOCK an unproved reversal rather than retain a bogus green test. Synthetic correction admission tests use an explicit five-pivot W4.

## New modules

* `williams_complex_waves.py` and `WilliamsComplexWave.kt` classify price geometry for ordinary impulses, truncated W5, possible leading/ending diagonals, zigzag ABC, regular/expanded/running flats, contracting triangles and WXY/WXYXZ unresolved combinations. All output `ALTERNATIVE` with `can_place_orders=false`. They require confirmed pivot timestamps no later than `as_of` and alternating time-ordered pivots. Child wave internals remain unproven and clearly labelled as such.
* `williams_wave_hierarchy.py` and `WilliamsWaveHierarchy.kt` now surface these complex alternatives in actual per-symbol hierarchical diagnostic JSON.
* `williams_course_campaign.py` and `WilliamsCourseCampaign.kt` implement independent staged Course risk-unit plans with mirrored LONG/SHORT policy, sequential phases W1 -> W2 -> W3 -> W3_EXTENSION -> W4 -> W5 -> EXIT, explicit exchange-reconciled fill acknowledgment, immutable stage revision, idempotent fill IDs, rejecting partial/mismatched fills, stale signals, missing protection and exceeded risk-slot budgets. A proposed state transition **does not change the actual campaign** until an independently confirmed fill is acknowledged.
* Added Python and Android targeted regression suites, including mirrors and no-lookahead.
* Added `williams_ntd_research.py` and scanner `latest_ntd_research[symbol]`: the five *separate* NTD families (external fractal, AO 5/34, AC, zone, Balance Line) are now evaluated on closed H1 candles for evaluated Futures pairs, always with `can_place_orders=false`. No TC2 order or profile admission changes.

## Intentional stop before unsafe live execution

Full real NTD/Course exchange execution is STILL NOT DONE. The Python Course planner uses abstract *risk units*, NOT Binance quantity, and there is no complete integration into live order/stop/cancel/reconcile flows. Launching the mode without that integration would be unsafe and would incorrectly claim 100% book coverage. NTD remains research-only until the fifth-dimension signal ownership and all add-on/partial-exit rules are proven through the Futures execution coordinator. TC2 live gate unchanged.

## Remaining wave gaps

Confirmed internal 5-3-5 / 3-3-5 / 3-3-3-3-3 subdivisions, alternative wave degrees, leading/ending diagonal rules, complex X connectors, triangles in fourth waves, wave 2/4 targets, multi-interval parent proof, the correct 100-140 bar analytical resolution, and objective divergence/breakout invalidation require further historical replay with genuine closed OHLC. A rough price skeleton is never proof of unique Elliott counts or reason to issue an order.

## Release gates

Exact-HEAD Python tests + Android unit/lint/CI + APK build/signature, then Futures Demo device tests: ten USDT pairs, both sides, protected fills, stepwise trims/adds with risk, stop/replace, manual close, exchange disconnection, order reconciliation, and restarting after ambiguous fills. Signed debug APK with version 4.29.2 is only a testing build; no Demo acceptance claimed.
