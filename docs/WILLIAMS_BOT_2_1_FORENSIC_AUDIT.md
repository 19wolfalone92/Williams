# WILLIAMS BOT 2.1 — Williams Strategy Forensic Audit

**Status: IN PROGRESS — NOT A CONFORMANCE APPROVAL**  
**Audit branch:** `audit/williams-2.1-forensic-20261010`  
**Baseline ref:** `main`  
**Baseline commit:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Audit start:** 2026-10-10  
**Scope:** evidence-only source-to-code inspection; no trading logic, indicator parameters, risk rules, or execution behavior changed.

## 1. Baseline and reproducibility

- Repository: `19wolfalone92/Williams`; default branch is `main`.
- GitHub's `main` ref was directly fetched and confirmed at `04da5f9c09572e99ff310be3a1908179165e528c` (`Update README.md`, 2026-10-09T17:30:49Z).
- Audit branch was created from that exact SHA. Audit artifact commit: `c2468ebe8eb0f8cf144ed1d4be63d7afdfa1eecb`.
- **Local working-directory status is not observable through the connected GitHub API.** Local uncommitted files, local branches, and index/worktree state cannot truthfully be reported here. GitHub branch refs are not a substitute for local `git status`.
- GitHub reports the `main` branch as unprotected. This is repository metadata, not a strategy finding.
- No orders were submitted and no trading tests were run as part of this audit step. Mainnet was not used.

## 2. Primary-source availability

The user Library contains the four approved source categories:
1. `1. Торговый Хаос - 1.pdf`
2. `Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга.pdf`
3. `Торговый Хаос 2 (3книга).pdf`
4. `Bill Williams - Trading Chaos Course.pdf`

The Library also contains duplicate copies and additional editions/files (including `Bill Williams - Trading Chaos 2nd Ed.pdf`). Do not silently treat these as the same edition. Each rule record must identify the exact file/edition and distinguish printed page numbers from PDF page indices. The Course scan's exact publication state/date is not established by its filename alone.

## 3. Code evidence captured at baseline

File blob SHAs fetched from `main`:
- `strategy.py` — `53b56c828ed626607e30d16b47733b6ffbe81429`
- `williams_signals.py` — `a19937002e00c3731a1f619275d7e675dd51cf00`
- `market_scanner.py` — `b8e0b441726209319304a951e51393e5e8662422`
- `trader.py` — `b2d9287a10da7d803c8ce14912c24745453033d7`
- `campaign_engine.py` — `2183825fcab8f46e47a7ea8b2833c259a367229e`
- `campaign_execution.py` — `4478e30c0bf09fc3c2d1e2c18ffb00b843745713`
- `campaign_monitor.py` — `7ef9e1bed030a3fe18bc5299170853b3926c5f7f`
- `digital_williams_core.py` — `f62bbbb45cebeb3a83b7899160b1d54cf4c73a53`
- `pending_signal.py` — `7240cf94bf5b0156160e6be26f6b391f4cae6a0f`
- `campaign_model.py` — `f30ec324c3f33167d73b6c21f36151832bb285c8`
- `campaign_order_fsm.py` — `35ecb8c74e1310c7f53e15c25769ba8d0917df01`
- `app/src/main/java/com/williamsbot/StandaloneRuntime.kt` — `c971551ca65eedcfa5c9124576dc57aadd1608c8`
- `app/src/main/java/com/williamsbot/TradingStateMachine.kt` — `ac22e4e3ec58f0e14bd0ce6473684042ba92a06d`
- `app/src/main/java/com/williamsbot/MarketHistoryStore.kt` — `9676af2d055e5d6636767825c5d467f0d100651d`
- `docs/WILLIAMS_BOOK_RULES.md` — `4914fdca76fad4e054dd2ae9075877908361db99`

These are baseline blobs, not the complete inventory. Every final finding must be tied to an exact commit and file blob SHA. Project documentation is an index of claims to verify, not proof of conformance.

## 4. Preliminary code observations — findings not yet source-adjudicated

### OBS-001 — Python and Android SMMA seed conventions differ

- Python `strategy.py::smma` seeds index `period - 1` with the arithmetic mean of the first `period` samples, then recursively updates.
- Android `StandaloneRuntime.kt::smma` initializes the entire output with the first input value and recursively updates from index 1.
- **Classification:** confirmed Python/Android implementation divergence; Williams-source/platform equivalence not yet adjudicated.
- **Potential impact:** Alligator lines and any context/trigger depending on them can diverge during warm-up and potentially beyond it. Priority provisional **P1** until shared vectors establish practical impact and selected source convention.
- **Required proof:** identical OHLC vectors across both implementations, including minimum history and long warm-up; compare every Alligator line and downstream signal.

### OBS-002 — Android contains multiple Alligator calculations with different input/shift semantics

- Android campaign signal extraction computes Alligator from median price `(high + low) / 2` and applies shifts 8/5/3.
- Android `buildIndicatorSnapshot` instead feeds close prices to SMMA(13/8/5) and exposes those values without the campaign path's displayed shifts.
- Python `calculate_indicators` uses median price and creates shifted series for Jaw/Teeth/Lips.
- **Classification:** confirmed divergence between Android diagnostic snapshot and campaign signal calculation; not yet evidence that the order path uses the diagnostic snapshot.
- **Potential impact:** diagnostic/UI context may not describe the values that actually gate campaign signals. Provisional **P2**; raise if a caller is found to use this snapshot for execution decisions.
- **Required proof:** trace every consumer of `buildIndicatorSnapshot`; create golden vectors comparing only semantically equivalent outputs.

### OBS-003 — Super AO implementation includes a fractal/Balance-Line prerequisite in baseline Python and Android campaign paths

- Python `williams_signals.py::_latest_super_ao` accepts a third same-colour AO bar only if the preceding row passes `long_fractal_outside` / `short_fractal_outside` when those columns exist.
- Android `StandaloneRuntime.kt` campaign signal extraction checks `priorFractalValid` before emitting WM2/SUPER_AO.
- **Classification:** behavior confirmed in baseline code. Whether this prerequisite is a canonical rule for the selected Trading Chaos 2 edition is **UNRESOLVED** until page/figure evidence is mapped. Do not silently mark it a source mismatch based only on architecture notes or PR descriptions.
- **Potential impact:** Super AO cannot be an independent first-presenting signal unless the fractal gate is already satisfied; this may alter campaign chronology. Provisional **P1**, pending source adjudication.
- **Required proof:** exact edition/page/figure review, followed by tests for WM2-first chronology with and without prior fractal.

### OBS-004 — Angulation is explicitly heuristic, not a source-certified formula

- Python `williams_signals.py::_angulation` uses a finite window, distance-to-Jaw deltas, regression slope and price-normalized score.
- The module itself labels this as an engineering approximation of a visual rule.
- Android campaign WM1 uses a separate simplified `angulationScore` based on a four-bar distance delta.
- **Classification:** confirmed heuristic implementations and likely cross-runtime formula divergence; source-defined exact numeric formula remains **UNRESOLVED**.
- **Potential impact:** WM1 eligibility can differ across runtimes and histories. Provisional **P1**.
- **Required proof:** establish whether the chosen source requires qualitative visual angulation or provides a deterministic rule; until then, label both as approximations and compare their outcomes on fixed cases.

### OBS-005 — More than one decision path exists

- Python contains a scanner/candidate path (`market_scanner.py`), legacy `trader.py::process`, a signal-spec extractor (`williams_signals.py`), and campaign lifecycle/execution modules.
- Android `StandaloneRuntime.kt` contains campaign signal extraction and a separate strict-signal fallback that can classify a signal from Alligator + AO + fractal breakout when the campaign engine is disabled.
- `digital_williams_core.py` explicitly describes itself as a composition boundary that does not replace the existing implementations.
- **Classification:** multiple strategy/legacy paths confirmed; the active production path depends on runtime flags and callers and is not yet fully established.
- **Potential impact:** the effective strategy may vary by configuration/platform. Provisional **P1** until all entry points and flags are mapped.
- **Required proof:** trace server startup/configuration, runtime flags, scanner-to-trader handoff, every order mutation caller, and Android feature flags to actual order admission.

## 5. Call-path inventory — first pass, not yet exhaustive

Python modules observed in baseline:
`data.py` (market-data acquisition; trace pending) → `market_scanner.py` / `trader.py` → `strategy.py::calculate_indicators` → `williams_signals.py::extract_long_signal_specs` (campaign candidates) → `campaign_model.py::SignalSpec` → `campaign_engine.py` / `pending_signal.py` → `campaign_execution.py` / `execution_barrier.py` → exchange mutation; `campaign_monitor.py` handles ongoing campaign monitoring. This is a candidate map only; caller-to-caller edges and alternate legacy paths remain to be proven.

Android baseline modules observed:
`StandaloneRuntime.kt` contains `NativeEngine`, candle/indicator snapshots, campaign signal extraction, entry/add-on submission and order/protection routines; state contracts also appear in `TradingStateMachine.kt` and market history in `MarketHistoryStore.kt`. Exact method-to-method call graph, feature-flag selection, candle-finality and execution linkage remain under audit.

## 6. Open PR / branch context

At the time of the initial read, open PRs included execution-safety work and a separate correction PR describing Alligator, fractal, WM2 and stop-provenance changes. These PR descriptions are not evidence that changes are on `main`; the baseline audit is against the exact `main` SHA above. For each PR considered, inspect the diff and exact head SHA independently before attributing behavior to the baseline.

## 7. Required continuation

1. Reconfirm `main` SHA and capture the audit branch/head SHA before finalization.
2. Capture exact open PR heads; compare relevant code diffs without treating PR descriptions as code proof.
3. Complete file/function/call-site inventory: market data → OHLCV normalization → indicators → candidate signals → context/structure → confirmation/trigger → PendingSignal → order mutation → campaign/position FSM → protection/trailing/exit.
4. Review each approved primary source directly, including text, figures, captions and examples. Record exact file/edition, printed page, PDF page and figure. If page-level evidence cannot be recovered, mark the rule UNRESOLVED.
5. Compare Python/Kotlin formulas, timestamp/candle-finality rules, rounding, history warm-up, fractal confirmation and state transitions.
6. Produce one matrix row per independent rule and one defect record per confirmed discrepancy.
7. Define shared golden vectors only for rules with established source interpretation. Do not execute exchange orders or use Mainnet.
8. Finish with an evidence ledger separating primary-source proof, code proof, test proof, and unresolved claims. Report unresolved behavior as UNRESOLVED/BLOCKED.

## 8. Initial status

**Overall strategy conformance: UNRESOLVED / NOT APPROVED.**  
The initial read has identified concrete cross-runtime formula/diagnostic differences and multiple decision paths requiring full call-graph proof. It has not completed the book-by-book rule matrix, final defect adjudication, shared-vector tests, or end-to-end decision trace. No implementation correction is authorized by this artifact.
