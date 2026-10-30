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

Inputs: the **same frozen 15-asset × 6-date benchmark selection** used
for the 4.64.5 report: BTC, ETH, BNB, SOL, XRP, DOGE, ADA, LINK, AVAX,
LTC, TRX, DOT, UNI, BCH and NEAR. Each sample has 160 strictly closed,
contiguous H1 candles, with the six UTC cutoffs from 18 to 28 November
2024. This audit reports the same classifier's abstentions and newly exposed
causal structural evidence. Equal sample selection permits comparison of the
raw label counts, but newly added descriptive phase tags are **not** additional
expert wave classifications.

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
- For **each same cutoff**, separately re-evaluate using **all genuinely closed
  earlier H1 candles** (not just the last 160): record the changed classifier
  output, backward-history length, and the number of changed labels. This is
  a controlled lookback-sensitivity experiment, **not** future hindsight or
  human wave truth; senior H4/D1 candles are not invented.

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

## Extended H4 research (same frozen 90 cutoffs)

The additional `tools/wave_h4_causal_research.py` observes exactly four
consecutive already-closed UTC H1 candles for each **research-only**
reconstructed H4. It refuses missing/duplicated source hours and never pads
an incomplete H4. Source provenance is explicitly
`UTC_H4_EXACTLY_FOUR_FROZEN_BINANCE_CLOSED_H1_NOT_NATIVE_H4`;
this is **not** an independent Binance H4 feed.

On the 15×6 frozen cases, the GitHub audit observed **74** price-containment
H4→H1 parent-child links across **43** of the 90 snapshots, without adding
order authority. These are structural alternative links, **not** 43 proven
wave counts, confirmed directional forecasts or certified Elliott degrees.
The 160-H1/extended-H1 classifier still reported 4 provisional W5
hypotheses and 86 abstentions, regardless of this contextual observation.

The GitHub Actions `Read-only 90 H1 wave uncertainty audit` additionally
exports **90 blind charts**: closed-candle OHLC, AO 5/34 and volume, plus the
SHA-pinned input manifest. Its **separate** reviewer artifact does not
contain model/proxy labels; the internal staging output is intentionally not
uploaded to blind reviewers. Independent experts can therefore evaluate
the same historical prefixes without knowing the algorithm's answers.

For a definitive scientific improvement, full independently downloaded
H4/D1/M15 histories for the same dates, two blinded expert wave reviews,
cross-degree pivot agreement and unknown-future stress tests are still
necessary. A real 20-day H1 window does not by itself provide 40 complete
D1 bars, so we never fabricate the missing degree.

## Получение настоящей независимой оценки по всем 90 срезам

В артефакте `williams-wave-90-blind-review-packet-no-model-labels`
расположены 90 диаграмм, 90 отдельных CSV с **ровно 160 доступными на
момент среза закрытыми H1-свечами**, пустой `blank_annotator_form.csv`
и инструкции. В будущие свечи или метки модели заглядывать запрещено.
Целостность CSV проверяется по SHA256 исходных числовых свечей из
`blind_90_selection.json`; несовпадение отменяет формирование пакета.

Два реально независимых эксперта заполняют **две отдельные** копии
формы и замораживают их с UTC-временем до сравнения. После этого:

```bash
python tools/score_blind_human_wave_review.py \
  --all-fifteen \
  --reviewer-a reviewer_a_frozen.csv \
  --reviewer-b reviewer_b_frozen.csv \
  --output build/wave-90-expert-scoring.json
```

В скоринге согласие по этикетке отдельно от согласия по **степени,
направлению и известным в момент среза экстремумам**. Совпадающий текст
`MOTIVE_FIVE` при разных направленностях или pivot-временах теперь
обязательно отправляется на независимое разрешение разногласия, а не
превращается в консенсус. Коэффициент Cohen kappa остаётся метрикой
совпадения классов, а не гарантией совпадения волновой генеалогии.
Самостоятельно набранные reviewer IDs не являются подтверждением личностей
экспертов, поэтому даже успешно заполненные формы по-прежнему
`human_elliott_accuracy_certified=false` без внешней проверки.
