# R49 — Alligator warmup requalification (581 comparisons)

## Method

Each comparison was aligned to the last closed H1 candle available at `first_observed_A`: `target_open_ms = first_observed_A - 1 hour`. The source seed file supplies the 15-symbol historical anchor. Public Binance USDⓈ-M H1 klines were retrieved from 1,000 hours before the seed timestamp through 500 hours after it (1,500 bars per symbol). Median Price = (High + Low) / 2. AO/AC use SMA(5)/SMA(34); Jaw/Teeth/Lips use recursive SMMA periods 13/8/5 with display displacements 8/5/3.

Strict comparison tolerance was preserved: `max(1e-10, abs(reference) * 1e-12)`. No tolerance was widened.

## Results

Comparisons re-evaluated: 581; source symbols: 15; missing target bars: 0.

| Indicator | Cases | Original mismatches | 220-bar matches to reference | 500-bar matches | 744-bar matches | 1000-bar matches |
|---|---:|---:|---:|---:|---:|---:|
| AO | 19 | 0 | 19/19 | 19/19 | 19/19 | 19/19 |
| AC | 19 | 0 | 19/19 | 19/19 | 19/19 | 19/19 |
| JAW | 181 | 70 | 19/181 | 181/181 | 181/181 | 181/181 |
| TEETH | 181 | 31 | 180/181 | 180/181 | 180/181 | 180/181 |
| LIPS | 181 | 7 | 181/181 | 181/181 | 181/181 | 181/181 |

Across all 581 rows, the original audit recorded 108 mismatches. The 500-bar window reproduces the independent reference in all 108 formerly mismatching rows. The archived differences remain historical differences and are not overwritten or claimed as fixed archived outputs.

## Remaining discrepancy

- LINKUSDT:LINKUSDT:1h:SELL:FRACTAL:1730610000000 / TEETH: archived=11.189509591228251, reference=11.189509591228251, 500=11.189509590883734, 744=11.189509590883734, 1000=11.189509590883734, abs_error=3.44517303574321e-10. The 500/744/1000 results are stable; this is not explained by insufficient warmup. Exact archived candle bytes/reference arithmetic are still required to distinguish historical data revision from floating-point/source precision.

## Conclusion

500 closed bars are supported by this historical comparison as a sufficient production warmup for the tested Alligator lines: 500/744/1000 windows converge, while the 220-bar Jaw window does not. This is an independent terminal-value comparison, not a production analyzer or admission test. It does not close the one residual strict-tolerance Teeth discrepancy, and it does not by itself prove full/incremental/restart parity or Android/Python/replay parity.

Raw row-level evidence is in `581-warmup-requalification.csv`. The original archived values and the independent references are preserved unchanged.
