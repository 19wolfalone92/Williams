# WILLIAMS BOT 2.1 — NTD / Course / complex waves follow-up, 2026-10-18

## Evidence, edition boundary

* New Trading Dimensions (NTD), Russian PDF **pp. 99–114**, especially diagrams 8-8, 8-9, 8-13, 8-16, 8-20 and textual checklist p.114: **Balance Line fifth dimension**.
* Trading Chaos 1, PDF **pp. 59–63**: Profitunity 5/34/5 oscillator, 100–140 genuine bars for the *wave sequence*, W4 minimum condition after W3, not W4 completion.
* Trading Chaos 2, PDF **pp. 121–165**: First, Second and Third Wise Men and campaign risk. Different standalone execution profile; not an alias for NTD.
* Trading Chaos Course, PDF **pp. 77–84**: *Planned Profitunity Trades* worked examples, phased position building, profit-taking / temporary reduction then re-expansion. The sample **3 → 1 → 6 → 10 → 3 ...** positions are NOT universal mandatory lot ratios or permission to bypass account risk.
* Elliott complex motive structures: ordinary impulses do not overlap W4 into W1 territory. A possible **diagonal** may overlap; its internal subwaves must be verified before asserting such a label. Truncated W5 is possible. All classifications are scenarios, never infallible counts.

## New implementation in this PR

### NTD fifth dimension, independent evidence model (Python and Android)

* Uses BLUE Jaw as the Balance Line, not RED Teeth (which has a separate external-fractal role).
* Reads **from right to left**; BUY examines only HIGHs, SELL examines only LOWs.
* Locates a recent base and counts progressively higher highs / lower lows to its LEFT.
* Requires one reference extreme moving AWAY from the Jaw, two moving TOWARD it.
* Doubles the number of required reference extremes for BUY in a RED AO/AC zone and SELL in a GREEN zone. Gray does not double.
* Preserves the full Alligator-mouth constraint and excludes previously touched triggers. For actual execution, formation lifetime and protective orders require separate proofs; a preliminary `protective_reference` does not authorize Futures trading.
* Emits research-only `BALANCE_LINE` add-on `SignalSpec` in Python and `ntd_balance_line_diagnostics` for LONG and SHORT in Android market JSON.
* Independently testable in both languages. This patch does **not** enable `NTD_FIVE_DIMENSIONS` as an exchange-executable profile, because a five-dimensional campaign and exchange lifecycle has not undergone Android Demo acceptance.

### Complex wave correction

* A W4/W1 overlap is **not** valid evidence of an ordinary impulse, but can be carried as `ALTERNATIVE` / `UNVERIFIED_LEADING_OR_ENDING_DIAGONAL_W4_OVERLAP` when price still respects the W3 origin.
* A W4 that breaches W3 origin / otherwise breaks geometry stays `INVALIDATED`.
* Diagonal alternatives are intentionally excluded from `PROVISIONAL` genealogy and cannot authorize a correction LONG or SHORT.
* Added identical Python / Android tests for ordinary, possible-diagonal and structurally invalid W4.

## New gaps and release criteria

1. **NTD:** current fifth-dimension stage is read-only, not a complete `Balance Line` order lifecycle. Complete the rest of NTD profile: five-dimensional initialization, add-on progression, exchange reconciliation, slot/risk safety, stop modifications and distinct edition-based live execution. TC2 launch must remain unaffected.
2. **Course:** actual planned trades involve condition-dependent partial closes, re-entries, and multi-stage capital/risk management. The TC2 reverse-pyramid weights 1:5:4:3:2 cannot be substituted for the Course *example* 3/1/6/10 etc. A dedicated Course state machine, role-specific lifecycle, fill ownership and replay are still required.
3. **Wave grammar:** leading/ending diagonals, flats, expanded flats, zigzags, double/triple corrections, triangles, truncated W5 and continuing W3 need explicit alternative models with causality tests. A single label must never forcibly overwrite another possible wave degree. A book does not support claiming 100% deterministic Elliott labels.
4. **Futures Demo:** after exact-head Python/Android lint/unit/build verification, require live phone acceptance across 10 USDⓈ-M pairs; API/data clock, closed H1 signals, H6/H1/M15 context, intentional LONG and SHORT fills, stop placement/progression, cancel/replace, manual close, expiry, offline recovery and double-order protection.
5. **Quant replay:** historical multi-symbol walk-forward with fees, slippage, funding, data gaps and delayed exchange outcomes; compare P&L/giveback/false exits against baseline. Synthetic tests are not trading profitability proof.

## Safety boundary

The only currently approved live execution strategy remains `TC2_THREE_WISE_MEN`. All new evidence is *non-executable*. It must not override D1/H6/H1 context, trading risk policies, exchange admission or protective STOP_MARKET.
