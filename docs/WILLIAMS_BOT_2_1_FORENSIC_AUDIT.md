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
