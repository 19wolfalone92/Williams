# Williams Bot 2.1 — Intraday Williams Parity Audit

**Branch:** `fix/williams-fractal-stop-provenance-20261010`  
**Audit snapshot:** 2026-10-10  
**Status:** PARTIAL — full source parity is NOT established. Do not describe this branch as 100% Bill Williams-compliant or production-ready.

## Universe and market context update (2026-10-10)

- Default configured fallback universe is now **CORE_10**: BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT, ADAUSDT, DOGEUSDT, LINKUSDT, AVAXUSDT, LTCUSDT.
- The Spot scanner defaults to liquidity preselection and a maximum of 10 symbols (`LIQUIDITY_PRESELECT=10`, `SCAN_MAX_SYMBOLS=10`). Explicit environment overrides can change those values; verify the effective runtime config before a test run.
- **D1 is permitted as informational broad-market context**, but must not independently create an entry signal or act as a blanket directional veto. H4 remains the sole required higher-timeframe context for the current intraday admission contract; H1 remains the canonical decision timeframe. D1 is not part of the default structural decision chain.
- This is a proposed default universe, not a guarantee that every symbol is tradable on both Spot and USDⓈ-M Futures in every environment. Validate each symbol against the relevant exchangeInfo and account permissions at startup; invalid symbols fail closed.

## Required timeframe contract

- **H4:** sole higher-timeframe context for this intraday configuration.
- **H1:** canonical decision timeframe; typed Williams signals are detected here.
- **M15:** execution/fill monitoring only; must not originate TC2 signals.
- **M5:** diagnostics/replay only; must not originate TC2 signals.
- **D1:** may be inspected as informational macro context, but is excluded from the intraday TC2 admission dependency and from default intraday timeframe chains.

The selected H4/H1/M15/M5 arrangement is an explicit engineering mapping for this bot, not a universal timeframe prescription stated by Williams. The Alligator itself encodes nested balance-line horizons within a chart; timeframe mapping must not be represented as a verbatim book rule.

## Changes made in this audit pass

1. Removed D1 from the default structural hierarchy for H1/H4/M15/M5 and other intraday chain fallbacks.
2. Removed D1 as a required context for TC2 admission and excluded it from the TC2 signal context-version dependency.
3. Updated the campaign-execution test context fixture to publish only H1 and H4, so tests no longer silently rely on D1.
4. Added regression assertions for D1-free intraday timeframe hierarchies.
5. Retained the configured risk ceilings: maximum 1% equity risk per campaign and maximum 3% aggregate portfolio open risk; default simultaneous campaign cap is 3.

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
