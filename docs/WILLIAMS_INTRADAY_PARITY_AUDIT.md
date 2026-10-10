# Williams Bot 2.1 — Intraday Williams Parity Audit

**Branch:** `fix/williams-fractal-stop-provenance-20261010`  
**Audit snapshot:** 2026-10-10  
**Status:** PARTIAL — full source parity is NOT established. Do not describe this branch as 100% Bill Williams-compliant or production-ready.

## Universe and market context update (2026-10-10)

- Default configured fallback universe is now **CORE_10**: BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT, ADAUSDT, DOGEUSDT, LINKUSDT, AVAXUSDT, LTCUSDT.
- Python's Spot scanner now defaults to CORE_10, with liquidity preselection and a 10-symbol cap. Android's autonomous Spot scanner also uses the same ten-symbol list, independently checks per-symbol ticker/book availability, filters zero-volume and invalid-spread markets, and reports active/unavailable members in diagnostics. Futures runtime defaults to the same list, but exchange support and account permissions must still be checked on the actual endpoint.
- Concurrent campaigns are capped at three; each campaign has a 1% risk budget, total open plus pending portfolio risk is capped at 3%, one campaign loses no more than its reserved budget, daily loss is capped at 1%, and two consecutive losses pause new entries. Python initial-entry/add-on reservations are rechecked transactionally; Android reservations are rechecked under the pending-entry lock. These contracts have new regression tests, but have not yet been validated on the exact current head.
- **D1 is permitted as informational broad-market context**, but must not independently create an entry signal or act as a blanket directional veto. H4 remains the sole required higher-timeframe context for the current intraday admission contract; H1 remains the canonical decision timeframe. D1 is not part of the default structural decision chain.
- This is a proposed default universe, not a guarantee that every symbol is tradable on both Spot and USDⓈ-M Futures in every environment. Validate each symbol against the relevant exchangeInfo and account permissions at startup; invalid symbols fail closed.

## Required timeframe contract

- **H4:** sole higher-timeframe context for this intraday configuration.
- **H1:** canonical decision timeframe; typed Williams signals are detected here.
- **M15:** execution/fill monitoring only; must not originate TC2 signals.
- **M5:** diagnostics/replay only; must not originate TC2 signals.
- **D1:** available as a best-effort informational market overview in Android/Futures status. D1 must not create or score TC2 signals or veto direction. It is excluded from default intraday decision chains and final execution dependencies; a D1 outage must not block H1/H4 analysis or protective exits.

The selected H4/H1/M15/M5 arrangement is an explicit engineering mapping for this bot, not a universal timeframe prescription stated by Williams. The Alligator itself encodes nested balance-line horizons within a chart; timeframe mapping must not be represented as a verbatim book rule.

## Changes made in this audit pass

1. Expanded Python and Android default spot universe to CORE_10; Futures fallback uses TradingConfig's same list. Unsupported or unavailable instruments should be omitted by market-data checks rather than inventing data.
2. Made D1 informational only. Android writes a per-symbol completed-candle snapshot to diagnostics; Python Futures runtime has a best-effort cache in status. D1 is removed from entry context-version dependencies and must not veto entries.
3. Kept H1 as the TC2 decision interval and dropped the still-forming last candle before Android H1 signal analysis. D1/H4 context fetches use completed bars. M5/M15 do not create Native TC2 signals.
4. Added typed, family-specific final entry checks: WM2 has no prior-fractal or universal directional-Alligator requirement; WM3 must remain beyond current H1 Teeth on the correct side; WM1 numeric angulation remains blocked by default because it is not yet an exact source-derived rule. Pending TC2 signals now carry a trigger and expiry into the final Python execution barrier; Android rechecks trigger/expiry immediately before order submission.
5. Allowed WM2/WM3 to be the first presenting Wise-Man evidence when no campaign exists, without mutating the detector's original signal role.
6. Added aggregate portfolio/campaign reservation checks for initial entries and add-ons in Python and Android. Android's daily guard includes marked open PnL and blocks entries if an open position lacks a valid mark.
7. Added deterministic regression tests for CORE_10, H1/H4 context only, typed TC2 gate behavior, expiry, WM3 Teeth, risk reservation, and first-presenting WM2/WM3. Tests still need to be run by CI for the exact final commit.

## Confirmed parity blockers / unresolved source questions

### P0 — must be resolved before claiming source parity

- **WM1 / angulation:** the current numeric angulation is explicitly an engineering approximation. The live detector blocks it by default unless `WILLIAMS_ALLOW_APPROXIMATE_ANGULATION=true`. Enabling that flag is not proof of source parity; the exact visual rule and figure interpretation must be source-verified.
- **Alligator causal/display semantics:** `strategy.py` computes SMMA lines and separately shifts them for display/decision columns. The precise raw value used for decisions, chart displacement, seeding/warm-up, and no-lookahead behavior require golden-vector tests against the book figures. Do not equate a plotting shift with future data.
- **Complete Three Wise Men lifecycle:** the detectors can emit typed WM1/WM2/WM3 signals, but parity requires proving pending stop activation, expiry, trigger-not-already-crossed checks, invalidation, re-entry/add-on handling, and campaign sequencing with deterministic fixtures.
- **Five Magic Bullets / Zero Point / campaign exit:** no evidence from this pass proves a complete, structurally linked implementation of all source-defined Five Magic Bullets and Zero Point rules. These must not be inferred from a Boolean score or generic AO/AC conditions.
- **Structural exits:** the live core contract declares no fixed take-profit and uses structural trailing/exhaustion. Verify every execution and recovery path honors that contract and that no legacy target can attach to a TC2 campaign.
- **Source completeness:** scanned Trading Chaos Course material cannot be treated as fully text-verified. Any rule depending on a diagram must be checked against the actual page/figure, not a summary.

### P1 — implementation audit required

- `strategy.py` still contains a legacy aggregate signal requiring fractal-outside, directional Alligator, awake state, and a minimum Wise-Men count. The TC2 live path appears to use typed signal specs instead, but all call paths must be traced to prove the legacy Boolean path cannot authorize or suppress a live TC2 order.
- `backtester.py` is a generic fixed-percentage stop/target simulator (default target 4%); it is not a source-faithful Three Wise Men campaign backtester. Do not use its output as proof of TC2 parity or performance.
- `risk_engine.py` retains legacy target/R:R calculations for profiles that opt in. Confirm the TC2 call site always disables target-based admission and that campaign exits remain structural.
- H4 freshness and data validity are checked, but a policy decision is still required for exactly how H4 context influences each Wise Man. A universal directional veto can incorrectly suppress WM1's countertrend presenting signal; no blanket veto should be added without source-backed semantics.
- The live H1 detector, candle-close handling, and H4 context cache need tests for stale/future candles, missing intervals, duplicated/out-of-order bars, and restart recovery.

## Required evidence before the “100%” claim

1. Exact commit SHA and green full CI.
2. Golden tests from book figures for Alligator, AO, AC, MFI/market-facilitation proxy, fractal confirmation, WM1/WM2/WM3, Zero Point and Five Magic Bullets.
3. Causal tests proving no lookahead/repainting across H4/H1/M15/M5.
4. Full entry-to-exit/recovery tests for LONG and SHORT, including partial fills, protection, add-ons, structural trailing, fees, ambiguous order outcomes and restart.
5. Demo/Testnet event logs tied to the exact tested commit.
6. Explicit separation in docs and DecisionTrace between source rule, engineering overlay and exchange safety rule.

## Verification status

Repository content was inspected and changes were committed through GitHub. Tests were **not executed in a local checkout** during this pass. GitHub combined statuses and workflow-run results returned no entries for the latest test commit; this is **not** a green CI result. No live or Demo/Testnet execution was performed by this audit pass.

## End-to-end audit notes (current working branch)

### Signal and execution flow reviewed
- **Universe/data:** approved CORE_10 -> per-symbol market-data validity/spread -> completed H1 candles -> typed WM1/WM2/WM3 evidence -> H4 data-quality/freshness context -> size from the actual structural stop -> durable pending-risk reservation -> final ExecutionBarrier / mutation wrapper -> conditional exchange order -> reconcile exchange order/fill/position -> attach hard protection -> trail/manage campaign -> structural/exhaustion exit -> reconcile and recover after restart.
- **WM1:** bullish Divergent Bar geometry in the Native Spot detector is currently only an approximate implementation; the preference flag defaults off. Python likewise documents and blocks the approximate formula unless explicitly enabled. This is a deliberate fail-closed blocker, not proof of 100% WM1 compliance.
- **WM2:** three same-colour AO evidence is detected independently of a fractal. It is still necessary to validate the exact book/course wording and figure-based trigger/stop behavior through golden fixtures.
- **WM3:** actual fractal confirmation index is preserved; Native uses confirmation time rather than center time to make the signal actionable. Final Python barrier rechecks the trigger/Teeth relation; Native checks the trigger has not been crossed and the structural low has not been breached. Exact expiry windows are engineering safety overlays, not claimed book rules.
- **Stop / exit:** Native TC2 creates protective STOP_LOSS orders and updates them from completed H1 candle structure; no fixed take-profit was added to the TC2 campaign path. Exhaustion/Teeth market exits are partly optional preferences and require source acceptance vectors and replay evidence before claiming canonical exit parity.
- **Safety:** any ambiguous post-submit order result is reconciled by stable client identity rather than blindly resubmitted. A pre-submit validation failure should release its pending reservation; an unknown exchange outcome remains a reconciliation blocker. These paths still need exact-head tests and Demo/Testnet event-log evidence.

### What this review does not establish
- The current source does not justify a claim of 100% book equivalence. We have not produced golden numerical vectors for SMMA seeding/display displacement, AO/AC, MFI semantics, every WM1/WM2/WM3 illustration, Zero Point, all Five Magic Bullets, reverse-pyramid sizing, and each exit rule.
- The scanned Trading Chaos Course pages/figures still require page-by-page visual verification and a source-to-code rule matrix.
- No physical Android build/run or Binance Demo/Testnet end-to-end execution has been performed in this pass. Do not enable Mainnet/live execution based on source review alone.
