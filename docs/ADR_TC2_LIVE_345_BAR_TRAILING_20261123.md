# QEP TC2 — live 3/4/5 closed-H1-bar trailing (2026-11-23)

## Source and scope
- Source: diagnostic `Williams_Diagnostics (15).zip` supplied by the user; specifically `stop_trail_decisions_full.json` and its `tc2_three_five_bar_shadow` (3/4/5 book comparison). The ZIP is evidence from its capture time, not a current Binance signed query.
- TC2 source metadata in `Tc2ThreeFiveBarStudy.kt`: Russian edition chapter 12, scanned PDF pp. 161 and 165; **three, four and five last completed H1 bars**. The book provides permitted alternatives; it does not provide this automatic choice algorithm.
- **Additional P0 discovered by red test:** `FuturesStopTrailPolicy.kt::planWilliamsCampaignStop` hard-coded `window !in listOf(3,5)` and returned `null` for FOUR bars even if the selector correctly chose 4. It now accepts `{3,4,5}`, with executable tests and CI static contract.
- Regression before change: `WilliamsStopTrailReview.kt` called `planWilliamsCampaignStop(... window=5)` exclusively. 3/4 were exported in `tc2_three_five_bar_shadow` with `execution_authority=false`. The old five-bar STOP could be ratcheted too near the mark (DIAG15); the separate 110% median H1 noise guard and H1/H4 correction veto were added in PR #269.
- Keep separate Williams AI completely untouched.

## Implementation
- NEW: `Tc2AdaptiveBarChoice.kt`, pure, deterministic selector. Inputs: independently verified **fully closed and contiguous** H1/H4 price bars and first verified position observation.
- Select **3** when H4 is directionally aligned (higher H4 high/low/close for LONG, inverted for SHORT), last three H1 high/low/close bars are in the same direction, closed H1 price move >=1.25 of trailing median range, and >=3 fully post-entry closed bars exist.
- Select **4** when H4 aligned, last two H1 are orderly and closed H1 move >=0.60 median range and >=4 post-entry closed bars exist.
- Select **5** when context mixed, H4 not aligned, H1 momentum weak/confirmations absent. This favors allowing a normal correction rather than default tightening to 3.
- **Engineering interpretation**, not a quotation of TC2. Thresholds are explicitly not statistically validated; avoid claims of profitability or superior trade outcomes.
- `WilliamsStopTrailReview` now selects one mode before forming the **live** `planWilliamsCampaignStop` proposal. The original `CONFIRMED_H1_FRACTAL` mode is still independent. The three-mode comparison remains a read-only shadow with no authority; live chosen source is separately logged as `tc2_live_345_bar_choice`, `tc2_live_bar_window` and `TC2_LAST_N_CLOSED_BARS`.
- Full post-entry H1 proof is evaluated for **the selected window**. Never accidentally use a pre-entry H1 in a shorter-mode execution.
- Existing mandatory signed Binance STOP is always retained when proposed tightening lacks evidence, post-entry bars, senior H4 proof, acceptable mark gap, H1/H4 corrective context, durable event or exchange acknowledgments.
- Stop changes remain stricter only; never enlarge stop distance / reverse / add new risk; only Demo.
- No impact on independent opposite TC2 signal exit, mandatory FILLED→STOP, MAX margin 50%, day loss 3%, three consecutive losses block, separate five percent CB, or H1 new entry gating.

## Negative falsification
- Both LONG/SHORT: actual live window3 and window4 source reasons, distinct 3/4 stop boundaries.
- Slow or H4 adverse: 5.
- Less than 3 or 4 **fully post-observation** H1 bars: cannot choose the shorter window.
- Bad H1/H4 data: selector remains conservative; caller rejects stale, forming, gapped bars.
- Exact tick selection; risk tightening only; never widen original protected stop.
- Stop shift inside ordinary range still fails the DIAG15 110%-of-median noise veto.
- CI `ci_release_gate.py` requires source integration AND tests; Android/Gradle and release signature + device emulator are separate jobs.

## Limits
- No independent out-of-sample superiority study for the engineering selection thresholds.
- A static screenshot cannot prove live signed open stop. Fresh signed Binance Demo and real Samsung Android 17 lifecycle/Doze acceptance needed.
- Signed existing STOP must **never** be moved farther away to 'fix' an older premature tightening; only re-evaluate safer future tightening with fresh signed inputs.
- Do not merge to `main` until all P0 safeguards and original stacked PR chain are reviewed.
