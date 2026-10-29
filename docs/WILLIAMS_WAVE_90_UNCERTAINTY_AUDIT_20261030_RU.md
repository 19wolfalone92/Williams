# Wave-gap reduction: observable partial counts, frozen 90-slice audit (2026-10-30)

**Scope:** only `19wolfalone92/Williams` (Williams Bot), branch
`fix/wave-audit-evidence-20261030` based on the actual 4.64.5 candidate
commit `81f80fd3`. No Williams-AI changes. No changes to entry authority, leverage,
risk, Binance orders, stop-loss logic or Android service.

## A defect that concealed real lower-degree observations

Previously, `prove_wave_subdivisions()` and its production Kotlin twin discarded
**all preceding confirmed lower-degree leg patterns** if a subsequent leg failed
a count/geometry check. Example: W1 has a valid observed five-swing child
skeleton, W2 has a valid three-swing correction, but an attempted W3 child
fails its impulse geometry; the output said `UNRESOLVED` and lost W1/W2's
already observed patterns.

Both implementations now retain the prefix of **proved child price skeletons**
in `leg_patterns` / `legPatterns`, while keeping the overall status
`UNRESOLVED`. This makes progress inside a wave observable and avoids
representing unconfirmed final legs as confirmed. A Python and Kotlin mirrored
LONG/SHORT regression verifies the distinction. Nothing grants trading rights.

## A reproducible 90-snapshot uncertainty audit

Run:

```bash
python tools/audit_wave_90_uncertainty.py --output artifacts/wave_90
pytest -q tests/test_wave_90_uncertainty_audit.py
```

Inputs: five frozen real Binance USDT-M H1 historical samples already in the
repo (BTC, ETH, BNB, SOL, XRP); 18 **preselected calendar cutoffs** per
instrument; 160 strictly closed, contiguous H1 candles per cutoff. This is
a **new frozen sample**; the 86/90 number in the 4.64.5 release report
comes from a different earlier audit, so the two must **not** be compared as a
before/after improvement without identical snapshots.

Outputs:

- `blind_90_selection.json`: IDs, source and 160-bar prefix SHA-256,
  **no model prediction or supposed expert label**, to enable proper blinded
  independent review.
- `wave_90_phase_diagnostics.json`: completed and developing wave
  hypotheses, direction conflicts, confirmed-fractal recency, W4 AO and W3
  momentum status where present, invalidation reasons, incomplete alternative
  structures and current classification.
- `wave_90_summary.json`: true model abstentions, descriptive phase counts
  and specific evidence blockers. Descriptive phase coverage is **not**
  Elliott-label classification coverage.

**Limits that software alone cannot remove:** no two independently verified
expert Elliott count labels have been provided for these candles; no verified
authoritative wave degree or unique interpretation exists when counts conflict.
Synthetic tests, book schematics, self-generated model labels, and extra
non-trading descriptive tags cannot yield valid expert accuracy percentages.
The audit therefore always reports `accuracy_vs_human=null`, no certification,
and `can_place_orders=false`.

## Acceptance required to close the remaining scientific gap

Have two independent qualified annotators label these **same blinded prefixes**
before receiving the separate predictions, with signed review timestamps and
an explicit `NO_VALID_COUNT` option, including disagreement resolution and
degree-aware pivots. Run the existing two-reviewer scoring and causal,
prefix/future-poison checks; report per-class precision, coverage, abstention,
inter-rater agreement, and disagreement examples. Only then can one quantify
real improvement in wave identification. Keep the result separate from Binance
Demo profitability and exchange safety tests.

**Sources:** TC1 chapter 7 and Course PDF59–60 wave checklists as already
tracked in this repository; additional swing geometry remains an engineering
approximation, not an independently transcribed book chart.
