# WILLIAMS BOT 2.1 — TASK 004.1 Strategy Fidelity Closure
## Status: IN PROGRESS — NOT APPROVED

Snapshot: 2026-10-10 UTC CI state; current reviewed branch SHA before final verification: `bef2f21679009805912b03ca05f0c77303fca81d`.
Repository: `19wolfalone92/Williams`.
Branch: `audit/williams-2.1-task-004-williams-strategy`.
PR: #49, existing draft; do not merge or create a duplicate PR.
Base `main` recorded in the preceding audit: `04da5f9c09572e99ff310be3a1908179165e528c`.

## 1. Exact CI evidence

On `df1ab4c50f0d83f56dd7af2c144ade7725afc793`:
- Python backend tests: PASS — [run 38082218018](https://github.com/19wolfalone92/Williams/actions/runs/38082218018).
- Williams Campaign CI: FAIL — [run 38082218023](https://github.com/19wolfalone92/Williams/actions/runs/38082218023).
- Build Android APK: FAIL — [run 38082218072](https://github.com/19wolfalone92/Williams/actions/runs/38082218072).

The two failed Android jobs each reported 29 tests completed, 1 failed:
`WilliamsStrategyGoldenVectorsTest > aoAndAcMatchSharedFormulaVectorsAndUseExactly34SlowSamples`.
The release contract gate and Android lint passed; the unit-test failure prevented APK build/verification. The APK job log also noted that the optional persistent debug-keystore secret was not configured, but this was not the failing step: Android unit tests failed first and APK build was skipped.

## 2. Confirmed immediate mismatch and correction

The shared fixture `app/src/test/resources/williams_strategy_golden_vectors.json` supplied AO values:
`[0.5, 0.4, 0.6, 0.7, 0.8, 0.9]`.
The third consecutive rising AO histogram bar is index **4**, not index 5: AO at indices 2, 3 and 4 is higher than the preceding AO value. The old Android assertion expected index 5 and rejected index 4, contradicting both the shared vector and the implemented third-same-colour-bar rule.

Correction committed in the existing branch:
- Golden vector `thirdRisingIndex`: 5 → 4.
- Android unit test reads the expected event index from the shared vector and asserts that the previous bar is not the third-bar event.
- This is a test-fixture/expectation correction; the oscillator formula and signal detector were not weakened or changed.

Relevant commits:
- `9f7c6177e031249bb12b819fcdaece3367343d09` — shared vector correction.
- `bef2f21679009805912b03ca05f0c77303fca81d` — Android assertion bound to shared vector.

## 3. Source-to-code evidence currently established

| Rule | Source locator available in project Library | Code evidence | Current verdict |
|---|---|---|---|
| Alligator inputs/periods/display shifts | *New Trading Dimensions*, PDF pp. 36–37 in the project scan; exact printed-page correspondence must be recorded from that edition | Python `strategy.py: smma(), calculate_indicators()`; Android `WilliamsAlligatorMath.kt`; shared vector | MATCH on the tested arithmetic convention; SMMA seed remains an implementation convention, not author-confirmed for every edition |
| AO | *Trading Chaos 2*, scanned Russian edition, PDF p. 136 / printed p. 121, Ch. 10, Fig. 10.1 | Python `strategy.py`; Android `WilliamsOscillatorMath.kt`; shared vector AO values | Formula vector present; final cross-platform CI pending |
| AC | *New Trading Dimensions*, Ch. 6, PDF pp. 72–90 | Python `strategy.py`; Android `WilliamsOscillatorMath.kt`; shared vector AC values | Formula vector present; final cross-platform CI pending |
| Three rising/falling AO bars / WM2 | *Trading Chaos 2*, PDF p. 138 / printed p. 123, Fig. 10.3, per the fixture's provenance | Python `super_ao_long/short` event from streak == 3; Android `isThirdSameColorAoBar()` | The previous fixture index was wrong; corrected expected event is index 4 |
| Fractal confirmation | *Trading Chaos 2*, PDF pp. 145–149 / printed pp. 130–134, Ch. 11, Fig. 11.1 A–D, per fixture provenance | Python `calculate_indicators()`; Android `WilliamsFractalMath`; shared strict/equal-extreme/cancellation vectors | Tests exist; production consumers and full live lifecycle still require proof |
| Campaign first-entry sequence | *Trading Chaos 2*, PDF pp. 154–157 / printed pp. 139–142, Ch. 12, per fixture provenance | Python campaign engine and Android runtime | NOT fully matched: prior audit evidence says Android Native Futures declares `maxPositions=1` and disables pyramiding while Python supports add-ons |
| WM1 angulation | Exact source locator/operational threshold not yet verified | Python reversal detector includes a numeric approximation | UNRESOLVED; do not claim an author-defined numeric formula |
| MFI volume semantics | *Trading Chaos* / Course must be edition-located against the source scans | Python explicitly labels Binance traded-volume calculation a proxy | UNRESOLVED as a literal historical tick-volume equivalent; do not equate the feeds |
| Pending-signal TTL / cancellation | No universal source-defined TTL established | PendingSignal and cancellation/revalidation path | IMPLEMENTATION RULE / SYSTEM SAFETY RULE unless a source explicitly proves otherwise |

The four source families—*Trading Chaos*, *New Trading Dimensions*, *Trading Chaos 2*, and *Trading Chaos Course*—must remain distinct profiles. Do not combine historical MFI/Profitunity methods and later Three Wise Men into an artificial set of mandatory entry gates.

## 4. Current CI state after correction

The following runs were started for `bef2f21679009805912b03ca05f0c77303fca81d`; at last polling they were still running:
- [Python backend tests — run 38086062274](https://github.com/19wolfalone92/Williams/actions/runs/38086062274)
- [Williams Campaign CI — run 38086062207](https://github.com/19wolfalone92/Williams/actions/runs/38086062207)
- [Build Android APK — run 38086062192](https://github.com/19wolfalone92/Williams/actions/runs/38086062192)

Do not treat these as passing until GitHub reports a completed successful conclusion for the exact SHA. If additional commits are made, all three checks must be revalidated on the new final SHA.

## 5. Remaining blockers to READY FOR STRATEGY REVIEW

1. Confirm final Python, Campaign CI, and Android APK conclusions on the same final SHA.
2. Run sequential-candle causal replay for every production signal consumer; prove no look-ahead, no premature fractal confirmation, and deterministic restart/reload.
3. Prove Python ↔ Android identity for WM1/WM2/WM3, context gates, signal identity, confirmation timestamp, invalidation/expiry reason, and permitted action—not only indicator values.
4. Resolve/document the Android vs Python campaign/add-on discrepancy by an explicit Spot/Futures mode contract; do not transfer Futures short semantics into Spot.
5. Keep WM1 angulation, exact Alligator seed provenance, MFI volume equivalence, and any unverified exit semantics marked UNRESOLVED.
6. Verify PendingSignal revalidation, cancellation ambiguity, partial fills, durable state, protection, and exchange reconciliation through failure-injection tests.
7. Finish a source-locator matrix for all four original source materials with edition and page/figure/caption evidence. Where the actual page is not accessible, say so rather than inventing a locator.

## 6. Safety boundary

No Mainnet activation, real orders, or changes to `main` are part of this task. Keep PR #49 open and draft until review criteria are satisfied.

**Current decision: IN PROGRESS — NOT APPROVED.** The corrected golden-vector assertion addresses a concrete test-fixture defect; it does not establish full strategy fidelity.
