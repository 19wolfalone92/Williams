# WILLIAMS BOT 2.1 — Williams Strategy Forensic Audit

**Status: IN PROGRESS — NOT A CONFORMANCE APPROVAL**  
**Audit branch:** `audit/williams-2.1-forensic-20261010`  
**Baseline ref:** `main`  
**Baseline commit observed through repository commit listing:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Audit start:** 2026-10-10  
**Scope:** evidence-only source-to-code inspection; no trading logic, indicator parameters, risk rules, or execution behavior changed.

## 1. Baseline and reproducibility

- Repository: `19wolfalone92/Williams`; default branch reported as `main`.
- The latest commit returned by the connected repository commit listing at audit start is `04da5f9c09572e99ff310be3a1908179165e528c` (message: `Update README.md`, timestamp reported by GitHub: 2026-10-09T17:30:49Z). Reconfirm the main ref SHA immediately before finalizing the audit.
- This audit runs in a new isolated branch created from that exact SHA. The only change made so far is this audit artifact.
- **Local working-directory status is not observable through the connected GitHub API.** Therefore local uncommitted files, local branches, and local index/worktree state cannot be truthfully reported here. GitHub branch refs are not a substitute for local `git status`.
- No orders were submitted and no trading tests were run as part of this initial baseline step. Mainnet was not used.

## 2. Primary-source availability

The user Library contains the four approved source categories:
1. `1. Торговый Хаос - 1.pdf`
2. `Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга.pdf`
3. `Торговый Хаос 2 (3книга).pdf`
4. `Bill Williams - Trading Chaos Course.pdf`

The Library also contains duplicate copies and additional editions/files (including `Bill Williams - Trading Chaos 2nd Ed.pdf`). Do not silently treat these as the same edition. Each rule record must identify the exact file/edition and distinguish printed page numbers from PDF page indices. The Course scan's exact publication state/date is not established by its filename alone.

## 3. Code evidence captured at baseline

The following file blob SHAs were fetched from `main`:
- `strategy.py` — `53b56c828ed626607e30d16b47733b6ffbe81429`
- `williams_signals.py` — `a19937002e00c3731a1f619275d7e675dd51cf00`
- `digital_williams_core.py` — `f62bbbb45cebeb3a83b7899160b1d54cf4c73a53`
- `docs/WILLIAMS_BOOK_RULES.md` — `4914fdca76fad4e054dd2ae9075877908361db99`

These are starting points, not the complete implementation inventory. Each file SHA must be rechecked against the exact commit/ref actually analyzed in the final report. Project documentation is an index of claims to verify, not proof of strategy conformance.

### Immediate inspection notes (not yet final findings)

- `strategy.py` defines an SMMA implementation seeded with the arithmetic mean of the first `period` values, then recursively updates it. Whether that seed and all downstream displacement conventions match the selected primary source/platform implementation remains a source/version question; this observation alone does not establish a defect.
- `williams_signals.py` explicitly describes its angulation implementation as an engineering approximation, not an author-certified formula. This must remain labeled as an implementation approximation until a source-backed, reproducible interpretation is approved.
- `digital_williams_core.py` describes itself as a composition boundary that does not replace the existing strategy/execution implementations. The audit must therefore continue through the actual callers and legacy paths; inspecting this adapter alone cannot prove the production decision path.
- `docs/WILLIAMS_BOOK_RULES.md` states adopted rules, but those statements are not accepted as evidence until independently mapped to the primary-source pages/figures and executable call paths.

## 4. Required continuation

1. Capture exact repository refs and relevant open PR heads; compare any existing audit/correction branches to baseline without treating their descriptions as code proof.
2. Build a file/function/call-site inventory for market data → OHLCV normalization → indicators → candidate signals → context/structure → confirmation/trigger → PendingSignal → order mutation → campaign/position FSM → protection/trailing/exit.
3. Inspect Python and Kotlin implementations at exact commit SHAs; compare formulas, timestamp/candle-finality rules, rounding, history warm-up, and state transitions.
4. Build a source rule registry from the four approved source files, including exact edition, printed page, PDF page, figure/caption when available, quote/paraphrase, interpretation confidence, and source ambiguity.
5. Produce one matrix row per independent rule and one defect record per confirmed discrepancy. Do not label a rule MATCH merely because a test exists or a module has a matching name.
6. Define shared golden vectors only for rules whose source interpretation is sufficiently established. Do not execute exchange orders or use Mainnet.
7. Finish with an evidence ledger separating primary-source proof, code proof, test proof, and unresolved claims. Report unresolved behavior as UNRESOLVED/BLOCKED.

## 5. Initial status

**Overall strategy conformance: UNRESOLVED / NOT APPROVED.**  
This is not a completed audit. The initial inspection confirms that documentation, an orchestration adapter, and a named signal module exist; it does not prove the real trading path conforms to any one edition of Williams's method. No implementation correction is authorized by this artifact.
