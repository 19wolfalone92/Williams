# WILLIAMS_MASTER_BOOK_COMPLIANCE_MATRIX

Evidence ledger initiated 2026-11-18, base `main` 4.64.40 (`1235a02773b178af64aa909880612c77bdea98d2`).

This document tracks **selected actionable rules**, not a purported full inventory. The canonical existing extraction is `research/BOOK_TO_CODE_MASTER_MATRIX.md`, `research/MASTER_RESEARCH_PROGRESS.json`, the source registry and figure receipts. Existing progress (at prior checkpoint): 148 registered rules, 143 rechecked against source context, **zero independently verified**, six bounded numeric definitions, 210 single-review figure records; total rule denominator unknown. These numbers are *prior repository-reported evidence*, not newly audited books in this PR. Source PDFs were not supplied to this session; page hints below derive only from those earlier records and **are not newly authenticated**.

## Schema / evidence bar

Every row eventually requires: **ID | source/edition/hash | physical page | figure | verbatim/precise source rule | formal rule incl. exceptions | Kotlin function | Python function | shared fixtures | independent visual review | code discrepancy | status | Demo evidence**.

Status vocabulary:
- `SOURCE_PENDING` — book page/figure not independently inspected in this work.
- `CODE_LOCATED` — implementation exists; correctness not presumed.
- `TEST_EVIDENCE_PRESENT` — tests or archived fixtures exist; does not mean complete.
- `METHOD_CONFLICT` — interpretation or edition conflict requires adjudication.
- `EXCHANGE_PENDING` — signed Demo + physical STOP proof absent.
- `CONFIRMED` — only after complete source + independent tests + relevant Demo where applicable.

| ID / methodology | Source page / figure lead (requires edition+SHA recheck) | Core behavior to prove | Kotlin target | Python target | Evidence gap / status |
|---|---|---|---|---|---|
| TC2-01 WM1 divergent bar | TC2 EN PDF 139–152, chapter 9 | Entire Alligator mouth, opposite extreme, causal timing | `WilliamsSetupRules.kt` | `williams_signals.py` | SOURCE_PENDING; test positives, negatives and LONG/SHORT |
| TC2-02 WM1 angulation | TC2 EN chapter 9; archived fig. 10.7 cross-reference needs review | Distinguish actual divergent trajectories from distance/parallel geometry | `WilliamsMarketGeometry.kt` | `williams_signals.py` | METHOD_CONFLICT: subjective geometry; independent drawings missing |
| TC2-03 WM2 AO chronology | TC2 EN 154–162, chapter 10 | The **first** third consecutive direction change, exact price bar; no duplicate third-of-series | `WilliamsTc2AoSourceProof.kt` | `williams_signals.py` | CODE_LOCATED; ordinal fixture; source/trigger/STOP end-to-end pending |
| TC2-04 WM3 extended fractal | TC2 EN 165–173, fig. 11.1–11.2 | Two left/two right of equal-extreme group, no future | `WilliamsSetupRules.kt` | `williams_signals.py` | METHOD_CONFLICT on separated equal-extreme timing |
| TC2-05 WM3 Teeth activation | TC2 EN chapter 11, figs. 11.4–11.7 | Valid Teeth relationship at *trigger*, exact tick | `WilliamsBookH1EntryPolicy.kt` | `williams_execution_policy.py` | EXCHANGE_PENDING: trigger-time exchange race |
| TC2-06 first Wise Man | TC2 EN chapter 12 | Any qualified WM1 / WM2 / WM3 may be first; no mandatory WM1→WM2→WM3 chain | `WilliamsExecutionPolicy.kt` | `williams_execution_policy.py` | CODE_LOCATED; require independent book scenario cases |
| TC2-07 corrections/congestion | TC2 EN chapter 11 fig. 11.7 | Do not equate all sideways with complex unresolved correction | `WilliamsTc2Congestion.kt` | `williams_wave_hierarchy.py` | PROJECT overlay; arbitrary 24-bar veto cannot be attributed to Williams |
| TC2-08 waves 3/4/5 | TC2 EN chapters 9–12; precise figure registration pending | Nested hypotheses, degree, invalidation, ambiguity, no look-ahead | `WilliamsWaveHierarchy.kt` | `williams_wave_hierarchy.py` | SOURCE_PENDING; independent chart labels absent |
| TC2-09 3/5-bar follow + opposite exit | TC2 EN chapter 12, pages per source registry | Preserve protected position unless source-qualified exit/structural stop | `WilliamsTc2ExitHierarchyLens.kt` | `williams_tc2_reversal.py` | METHOD_CONFLICT; exchange attribution and early-exit comparison pending |
| TC2-10 campaign pyramid | TC2 EN chapter 12 | Actual fill-anchored adds, combined stop/risk, no average-down | `WilliamsTc2Pyramid.kt` | `williams_tc2_pyramid.py` | EXCHANGE_PENDING, especially partial fills |
| TC2-11 portfolio half margin | TC2 EN archived 212–213 (verify exact copy) | All held+pending margin ≤50% of declared account basis | `FuturesHalfMargin.kt` | `futures_half_margin.py` | CODE_LOCATED; synthetic fixtures exist; signed Demo unverified |
| NTD-01 five dimensions | NTD Russian source registry, exact pages pending | AO, AC, zones, Balance Line remain **observer only** for new risk | `WilliamsNtdExecution.kt` | `williams_ntd_execution.py` | SOURCE_PENDING; not a TC2 veto |
| TC1-01 MFI and volume | TC1 Russian source registry, pages 40–45 lead | Explicit tick volume proxy, MFI equality and unit semantics | `WilliamsTc1MarketFacilitation.kt` | `strategy.py` | CODE_LOCATED; confirm original volume language |
| Course-01 corrective waves | Course EN source registry, exact figure pending | Own corrections, parent-child structure; not TC2 executable admission | `WilliamsCourseCorrection.kt` | `williams_wave_hierarchy.py` | SOURCE_PENDING; no execution authority |
| ENG-01 pending order evidence | Project safety (NOT book rule) | Local creation timestamp **cannot** masquerade as signed Binance pending confirmation | `WilliamsSignalLifecycle.kt` | `williams_signal_lifecycle.py` | FIX IN THIS BRANCH; unit tests added; independent CI pending |
| ENG-02 FILLED → STOP | Project safety (NOT book rule) | Every live qty must have signed exchange-side STOP and verified protection ownership | `NativeFuturesCampaignCoordinator.kt` | `futures_williams_runtime.py` | P0 EXCHANGE_PENDING, do not declare safe based on emulator |

## Discipline for completing rows

1. Reacquire original, hash-identified PDF and verify physical page/figure before attaching a source citation. OCR, labels and translations do not establish independent visual truth.
2. Separate textbook MUST/MAY/SHOULD statements from project engineering guardrails, numeric geometry proxies, account-risk policy and unresolved interpretation.
3. Build monotone closed-H1 test vectors, bar-by-bar expected answer, both directions, and shared Kotlin/Python execution. Report all mismatches rather than rounding them away.
4. Reject look-ahead, stale H1, uncleared exchange intents, false pending/STOP claims and duplicate client IDs.
5. Do not change execution authority, disable risk blocks or merge on the strength of this source inventory alone.

Full-methodology status: **NOT VERIFIED**. Trading readiness: **NOT APPROVED**.
