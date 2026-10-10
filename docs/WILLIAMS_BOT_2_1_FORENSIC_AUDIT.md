# WILLIAMS BOT 2.1 — Williams Strategy Forensic Audit

**Status: IN PROGRESS — NOT ACCEPTED / NOT A CONFORMANCE APPROVAL**  
**Audit branch:** `audit/williams-2.1-forensic-20261010`  
**Baseline ref:** `main`  
**Baseline commit:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Audit start:** 2026-10-10  
**Scope:** evidence-only source-to-code inspection; no trading logic, indicator parameters, risk rules, or execution behavior changed.

## 1. Baseline and reproducibility

- Repository: `19wolfalone92/Williams`; default branch is `main`.
- `main` SHA was rechecked through GitHub and remains `04da5f9c09572e99ff310be3a1908179165e528c`.
- Audit branch exists: `audit/williams-2.1-forensic-20261010`. Last report commit known before the appendix: `1cc284f1350cba8faf1fd7e26fd825ac3b3af835`; this follow-up created appendix commit `2445e266a5a6f912a25042872e0bf1ff6afc8b8a`. The connector cannot return the current SHA of a branch containing slash characters through the attempted ref endpoint; exact branch HEAD after the appendix commit is therefore **UNRESOLVED** until independently confirmed.
- **Local working-directory status is not observable through the connected GitHub API.** Local uncommitted files, local branches, and index/worktree state were not inspected.
- No orders were submitted and no Mainnet was used. No strategy, indicator, risk or execution code was changed by this audit.

## 2. Primary-source availability and initial text inspection

The four requested Library PDFs were retrieved as raw files and processed with PDF metadata and text extraction. Full acquisition metadata, text extraction condition, PDF page counts, and provisional source locators are documented in the appendix.

- `1. Торговый Хаос - 1.pdf`: 140 PDF pages; substantial searchable text.
- `Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга.pdf`: 156 PDF pages; substantial searchable text.
- `Торговый Хаос 2 (3книга).pdf`: 224 PDF pages; substantial searchable text; title page identifies the Second Edition.
- `Bill Williams - Trading Chaos Course.pdf`: 122 PDF pages; almost no text layer (~130 extracted characters), indicating a scan that requires visual/OCR review. No page-specific rule is asserted from text extraction alone.

Provisional TC2 PDF locators include angulation at PDF p.122, First Wise Man discussion at PDF p.125, AO description at PDF p.136, and the “Profitunity strategy for entry into any market and exit from any market” checklist at PDF p.155. These are PDF indices, not verified printed folios. Exact printed page/figure mapping remains open.

## 3. Baseline code evidence captured

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

These are baseline blob IDs. The audit appendix records the source-to-code observations and evidence limitations. A complete final report must recheck all blobs against the exact commit analyzed.

## 4. Preliminary code observations — not yet final source adjudications

- **OBS-001 — SMMA:** Python seeds its first valid point with an arithmetic mean of the initial period samples; Android initializes outputs to the first sample and recursively updates. This is a confirmed implementation difference. Numerical Alligator divergence on shared inputs has not yet been tested.
- **OBS-002 — Alligator:** Android's `buildIndicatorSnapshot` uses close-price SMMA(13/8/5) and does not mirror the campaign path's median-price and shifted-line semantics. The snapshot is not equivalent by construction; its actual consumers and any trading-decision influence remain to be proven.
- **OBS-003 — Super AO:** baseline Python and Android campaign paths impose a fractal-related prerequisite on the third same-colour AO bar. Whether that prerequisite is canonical for the selected TC2 edition remains UNRESOLVED pending page/figure adjudication.
- **OBS-004 — Angulation:** Python uses a windowed regression/distance heuristic; Android uses a separate simplified distance-delta score. Formula parity is not established, and the source's qualitative geometry does not yet establish an author-exact numerical threshold.
- **OBS-005 — Multiple decision paths:** scanner/legacy Python paths and a campaign SignalSpec path exist; Android includes campaign detection and a strict-signal fallback. The active configuration-to-order call path is not yet completely proven.

Do not promote these observations to definitive book mismatches without the source and test evidence required by the matrix.

## 5. PR and CI boundary

- PR #49, “WILLIAMS BOT 2.1 — TASK 004: source-to-code strategy audit”, is open and draft on branch `audit/williams-2.1-task-004-williams-strategy`, head `282d9c5364cae3215f5e200789efdbce77894fb8`; merge test commit `efa2424c3e995a45e46c5f92596db39f2cf6e1a4`. None of that PR's proposed corrections are part of the baseline `main` analyzed above.
- Exact-head CI at PR #49 head: Android APK workflow **in progress**; Python backend tests **failure**; Williams Campaign CI **cancelled**.
- Python job `114290840124`, run `38078658818`: static safety audit and production compile passed; offline pytest failed **1 test / 165 passed**. Failure: `test_williams_book_alignment.py::test_ao_green_streak_drives_super_ao`; monotonic rising test expected `super_ao_long=True` but got `False`. Thus the PR's test suite is not green. This is not a baseline test result and does not decide the source interpretation of WM2.
- CI evidence from another branch/PR is not proof of baseline or audit-branch behavior.

## 6. Detailed appendix

The companion file contains the current partial rule matrix, four-source extraction audit, OBS-001–OBS-005 evidence split, initial call-path inventory, staged-vs-run test distinctions, defect/hypothesis separation, and remaining blockers:

- `docs/WILLIAMS_BOT_2_1_FORENSIC_AUDIT_APPENDIX.md`

The appendix intentionally marks unresolved claims UNRESOLVED and does not claim full rule coverage or Python/Android parity.

## 7. Required continuation

1. Obtain an independently verifiable current audit-branch HEAD SHA and recheck `main`.
2. Visually inspect primary-source page images and figures, including OCR of the Course scan; establish edition, printed page and figure locators.
3. Complete caller/consumer graph in Python and Kotlin, including feature flags and all order/cancel mutation calls.
4. Build the full one-rule-per-row matrix for all relevant source rules and adopted edition/profile.
5. Run offline golden vectors only against fixed commits and identical inputs; no exchange orders.
6. Record fixture hash, expected result with source locator, Python output, Android output and exact test-run SHA.
7. Produce defect cards and priority plan only where the source rule, code behavior and test evidence are sufficient.

## 8. Status

**Overall strategy conformance: IN PROGRESS — NOT ACCEPTED / UNRESOLVED.**

The primary-source files have been acquired, their text-layer condition assessed, and initial code differences documented. The complete rule registry, printed-page/figure verification, active entry-to-order/exit call graph, baseline cross-runtime golden-vector results, and full test proof are not complete. No strategy correction is authorized by this audit artifact.


## Latest continuation — 2026-10-10

**Status remains: IN PROGRESS — NOT ACCEPTED.** This is an evidence update, not acceptance and not a strategy-code change.

### Repository state and separation

- Baseline `main`: `04da5f9c09572e99ff310be3a1908179165e528c`. GitHub's default-branch commit search still returns this as the newest commit; the compare endpoint resolves it as the `main` base.
- Audit documentation commit immediately before this report update: `6ca90a5412c50b5b3c93e0e43718304a1bf2c8d8` (“docs: verify AO and MFI source-page images”).
- A GitHub compare of `main...6ca90a5412c50b5b3c93e0e43718304a1bf2c8d8` returned `ahead`, 6 commits ahead / 0 behind. The six commits contain audit documentation only. The exact new tip after this report update is the commit SHA returned by the write operation; do not use the preceding SHA as the final tip once the write completes.
- This runtime has no local Git checkout. `git status`, `git branch --show-current`, `git rev-parse HEAD` and `git log` cannot report local worktree/staging state. The GitHub branch compare is the available independent ancestry comparison; it does not substitute for a local worktree status.
- PR #49 remains open, draft, unmerged, based on `main` at `04da5f9c09572e99ff310be3a1908179165e528c`; its current head is `334967f55a10f47a798d31951d31d8278c9a15d5` on `audit/williams-2.1-task-004-williams-strategy`. Do not conflate that PR branch with this forensic documentation branch.

### Source-image findings added to the appendix

- **TC2, Russian second edition (Moscow, IK Analitika, 2005):** visually verified PDF p.122 / printed p.107 (angulation; Figs. 9-3/9-4 referenced), p.125 / printed p.110 (conditional trigger and protective stop; Fig. 9.6), p.136 / printed p.121 (AO; Fig. 10.1), and pp.153–155 / printed pp.138–140 (ordered WM1 → WM2 → WM3; Fig. 12.1 and strategy checklist).
- **New Trading Dimensions:** visually verified PDF/printed p.55, Fig. 5-2. This Russian page also prints the median-point expression as `(H-L)/2`, not the conventional `(H+L)/2`. Because the supplied Russian TC2 page has the same notation, source-vs-translation-vs-conventional-formula interpretation remains **UNRESOLVED**; the code must not be silently changed based on convention.
- **Trading Chaos (TC1):** visually verified PDF/printed pp.42–44: MFI is `(High − Low) / Volume`, explicitly tied to tick volume, and the four Profitunity windows combine tick-volume direction and MFI direction. The source does not establish MFI as a universal entry Boolean. Python four-state mapping exists; Binance volume equivalence and active entry-path use remain unresolved.
- **Trading Chaos Course:** visual review confirms PDF p.62 / Level II page 30 “PRISTINE FRACTAL” diagrams and text, including shared up/down fractals and a six-bar equal-high case. The 122-page scan is not exhaustively reviewed; no whole-course completeness claim is made.

### OBS-003 — clarified, not closed

The TC2 page image at PDF p.153 / printed p.138 explicitly lists the classic order as reversal bar (WM1), Super AO (three consecutive same-colour bars, WM2), then fractal (WM3). Its worked example places the three-green-AO signal before the later fractal entries. A requirement for a previously confirmed WM3/fractal is therefore **not supported by this illustrated classic sequence**. The page does not establish that WM2 is always the first presenting entry when WM1 is absent; it illustrates WM2 as the second/add-on stage. Baseline Python and Android have prior-fractal-related gates. Keep final rule status **UNRESOLVED** until the field semantics and every active caller are traced. No strategy change was made.

### Exact-head PR #49 CI (not baseline CI)

For PR head `334967f55a10f47a798d31951d31d8278c9a15d5`:

| Workflow | Run ID | Conclusion | Specific result |
|---|---:|---|---|
| Python backend tests | `38078748291` | SUCCESS | Release gate, compile, static safety audit and offline tests passed on this PR head only. |
| Build Android APKs | `38078748322` | FAILURE | Android test `WilliamsStrategyGoldenVectorsTest.aoAndAcMatchSharedFormulaVectorsAndUseExactly34SlowSamples` failed at `WilliamsStrategyGoldenVectorsTest.kt:64`; 29 tests, 1 failed. APK build/version/signature steps skipped. |
| Williams Campaign CI | `38078748306` | FAILURE | 5 failed, 164 passed; five campaign-execution tests were rejected by the changed H1-only signal gate. Android unit tests also failed with the AO/AC vector assertion. |

The `main` workflow lookup returned no runs for the baseline SHA; an empty API result is not a passing CI result. None of the PR outcomes above is attributed to `main` or to this audit branch.

### Remaining proof blockers

1. Full branch-ref SHA/worktree status cannot be retrieved from the current runtime; the local checkout is absent. Use the commit returned by the latest write and verify ancestry with the compare endpoint.
2. The Course scan has only partial OCR/visual coverage; bibliographic metadata is not established.
3. AO formula notation in the two supplied Russian source pages needs source/translation adjudication; do not resolve by assumption.
4. Full caller graph from configuration/OHLCV through all Python and Android decision routes to every order/cancel mutation is not yet complete.
5. The 13-row matrix remains preliminary. TC1, New Trading Dimensions, TC2 and Course rules still need an exhaustive independent-rule registry.
6. No baseline Python/Android shared golden vectors have been run in this audit. PR #49's staged tests are not a substitute and the current PR CI is not fully green.
7. No source-to-code proof has yet established look-ahead absence across all active routes.
8. No claim is made that all four books have been fully audited. The audit cannot be accepted until the rule registry, active call graph, causal-time tests, reproducible defect cards, and exact-SHA cross-runtime vectors are complete.


## Fresh follow-up checkpoint — 2026-10-10

- Remote audit branch HEAD independently re-confirmed as `f2d2eb24d8c78be871567f93dcf5fc973dd3ff97`: comparison of the branch ref to that commit returned `identical` (0 ahead/behind). Remote `main` independently compares identical to `04da5f9c09572e99ff310be3a1908179165e528c`. Audit branch is four commits ahead of main, with changes confined to the two audit documents.
- Local `git status` cannot be established because the available runtime is not a local Git clone. Do not interpret remote ref verification as evidence about local staged/unstaged files.
- PR #49 has advanced: current head is `334967f55a10f47a798d31951d31d8278c9a15d5`, merge-test SHA `83a6581f3a0870ecae9b39c7af45a092c42e62d7`; it remains open/draft/unmerged. CI for the PR merge ref: Python backend 166 passed; Android unit test 1 failure (29 tests; AO/AC golden vector at `WilliamsStrategyGoldenVectorsTest.kt:64`, APK build skipped); campaign CI 5 failures/164 passed on Python, Android build/unit tests failed. None of these are main or forensic-branch results.
- Visual source review now verifies course scan locators (PDF pp.61–64, printed pp.29–32; PDF p.91, printed p.15) and selected TC2 Second Edition pages/figures. The source proves that Super AO may be the first actionable signal before WM1 or WM3/fractal (TC2 PDF p.155, printed p.140, Chapter 12; Fig. 12.1 cited). Angulation is qualitative/geometric in the inspected text; no author-exact universal numeric formula was found in those pages.
- New primary-source ambiguity: TC2 PDF p.136 / printed p.121, Fig. 10.1 and New Trading Dimensions PDF p.55 / printed p.55, Figs. 5-1/5-2 visibly print the midpoint expression `(H-L)/2`. This conflicts with the conventional high/low midpoint `(H+L)/2`; the audit leaves the AO input formula UNRESOLVED pending independent source adjudication rather than silently correcting it.
- Status remains **IN PROGRESS — NOT ACCEPTED**. No strategy/runtime code changed; no PR merged; no orders submitted; no Mainnet used. See the appendix for run/job IDs, per-page source notes, OCR limits, and rule-level consequences.


## Active-path audit progress — baseline code trace

The baseline Python campaign route is now traced from `server.py::State.loop` → `Trader.process` → `Trader._auto_scan_process` → `MultiPositionTrader.scan_and_execute` → `MarketScanner._analyse_base` / `calculate_indicators` / `extract_long_signal_specs` → `MultiPositionTrader.execute_campaign` → `CampaignExecutionService` → `ExecutionBarrier.execute` → `BinanceClient.order_safe/order`. Campaign protection/trailing/cancel/exit methods are owned by the campaign service.

A proven exception/alternate route remains: authenticated `POST /api/v1/control/sell` reaches `MultiPositionTrader.manual_sell`, which directly calls `cancel_oco` and `order_safe` without `ExecutionBarrier.execute`; the legacy entry branch when `CAMPAIGN_ENGINE=false` also directly submits via `order_safe`. This establishes an architecture-contract discrepancy to resolve, not permission to change code.

The Android campaign route is traced from `requestScan` → `performCampaignScan` → `analyseBase` → `longCampaignSignals` → `submitCampaignEntry/AddOn` → `withCampaignMutation` → `signedPost/signedRequest` to Binance REST order mutation; recovery, fills, protection, trailing and exits have distinct call sites. Android’s `ExecutionGate` is not the Python `ExecutionBarrier`, and exact safety-contract parity remains open.

**OBS-002 narrowed:** `buildIndicatorSnapshot` is private and all in-file callers write to `indicatorSnapshots` / the indicators API. The campaign decision path uses candle arrays directly and does not read that snapshot, so the known Close/unshifted diagnostic discrepancy is not shown to affect trade decisions on baseline.

**OBS-003 resolved against TC2 Second Edition:** the source explicitly allows Super AO to be the first actionable signal before WM1/WM3/fractal (PDF p.155, printed p.140, Chapter 12, Fig. 12.1 cited). Both baseline Python campaign signal extraction and Android `longCampaignSignals` require a prior valid fractal/Balance-Line condition. This is now a static source-to-code **MISMATCH** on both paths; no golden vector was run and no code changed.

The complete mutation inventory, full rule registry, golden vectors and all campaign lifecycle proofs remain incomplete. Overall status remains **IN PROGRESS — NOT ACCEPTED**.

## New source locators and defect cards

- Alligator: New Trading Dimensions PDF p.36 / printed p.36, Fig. 3-4; TC2 Second Edition PDF p.117 / printed p.102, Fig. 8.9. TC2 confirms Jaw 13/8, Teeth 8/5, Lips 5/3; New Trading Dimensions has an internal prose-vs-box discrepancy (Lips shift 2 vs 3). SMMA seed convention remains unresolved.
- Angulation: TC2 PDF pp.123–124 / printed pp.108–109, Figs. 9.3–9.5; source establishes increasing geometric angle relative to Alligator/Jaw, not a unique numeric regression formula.
- Defect cards now recorded in the appendix: DEF-WM2-001 (source/code mismatch), DEF-SNAPSHOT-001 (diagnostic-only divergence), DEF-ORDER-DOOR-001 (manual sell mutation path outside Python ExecutionBarrier), DEF-SMMA-001 and DEF-ANGULATION-001 (confirmed code divergence, numerical tests not run).
- Golden-vector status remains NOT RUN on the forensic branch. No trading code or test files were changed.
