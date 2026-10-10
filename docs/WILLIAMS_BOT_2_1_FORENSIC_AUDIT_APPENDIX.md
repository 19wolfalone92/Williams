# WILLIAMS BOT 2.1 — Forensic Audit Appendix
**Status: IN PROGRESS — NOT ACCEPTED**  
**Scope:** source evidence inventory, baseline-vs-PR boundary, preliminary rule matrix, and CI evidence. No trading logic changed.

## A. State and evidence boundaries

- Baseline `main`: `04da5f9c09572e99ff310be3a1908179165e528c` (verified from GitHub branch endpoint at audit follow-up).
- Audit branch exists: `audit/williams-2.1-forensic-20261010`. Last report commit known before this appendix: `1cc284f1350cba8faf1fd7e26fd825ac3b3af835`. GitHub connector branch-search returns the branch name but not its head SHA; direct API ref lookup for slash-containing branch names is rejected by the connector. Therefore the exact current audit-branch HEAD is **UNRESOLVED**, not assumed.
- Local working tree, local branch list, and uncommitted changes are unavailable through GitHub API and were not inspected.
- No orders submitted; no Mainnet; no strategy/indicator/risk/execution code modified in this audit branch.
- Baseline blob SHAs from earlier read: `strategy.py` `53b56c828ed626607e30d16b47733b6ffbe81429`; `williams_signals.py` `a19937002e00c3731a1f619275d7e675dd51cf00`; `market_scanner.py` `b8e0b441726209319304a951e51393e5e8662422`; `campaign_execution.py` `4478e30c0bf09fc3c2d1e2c18ffb00b843745713`; `digital_williams_core.py` `f62bbbb45cebeb3a83b7899160b1d54cf4c73a53`; Android `StandaloneRuntime.kt` `c971551ca65eedcfa5c9124576dc57aadd1608c8`. These are blob IDs, not commit IDs.

## B. Four primary-source files — acquisition and text condition

All four exact requested Library PDFs were materialized and processed with `pdfinfo` and `pdftotext -layout`. Page numbers below are **PDF page indices (1-based)**, not printed book pages.

| Source file | PDF pages | Extracted text evidence | Limits |
|---|---:|---|---|
| `1. Торговый Хаос - 1.pdf` | 140 | Text layer substantial (~486k characters). | Bibliographic edition/translator details need title-page verification; printed page locators must be separately confirmed. |
| `Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга.pdf` | 156 | Text layer substantial (~427k characters). | PDF first pages include front matter; printed page numbering differs from PDF index. Edition metadata needs title-page verification. |
| `Торговый Хаос 2 (3книга).pdf` | 224 | Text layer substantial (~514k characters); title page says “Trading Chaos — Maximize Profits with Proven Technical Techniques — Second Edition”. | This is the second-edition TC2 source. Russian text extraction includes mixed English headings; page/figure mapping must be checked visually for final citations. |
| `Bill Williams - Trading Chaos Course.pdf` | 122 | `pdftotext` produced only ~130 characters; nearly all pages are image scans. | OCR/image-level review is required. This source cannot support page-specific text claims from extracted text alone. |

Duplicate Library copies exist for TC1, New Trading Dimensions, and the Course. This audit uses the four paths above, not silent edition substitution.

### Source locators recovered from searchable text

- TC2: PDF p.122 discusses angulation as price moving away at a steeper angle than the Alligator/Jaw and references Fig. 9-3 and Fig. 9-4; exact printed page number remains unverified in this text-only pass.
- TC2: PDF p.125 discusses First Wise Man divergent/reversal bars and stop placement beyond the signal bar; printed page and figure locator require visual confirmation.
- TC2: PDF p.136 describes AO as comparing momentum over the last five bars with the last 34 bars and references Fig. 10.2; printed page remains unverified.
- TC2: PDF p.155 contains a “Profitunity strategy for entry into any market and exit from any market” checklist: divergent bar with lower low and close in upper half, greater angulation relative to Alligator/Jaw, conditional buy stop at/above the bar high, and protective stop below the bar low. The PDF page is verified; printed page and Fig. 12.1 number need visual confirmation.
- New Trading Dimensions: PDF p.55 describes AO as a 5-period SMA of median price minus a 34-period SMA of median price and references Fig. 5-2. The extracted text contains an apparent OCR/typesetting ambiguity in one occurrence of the median-price expression; the conventional `(H+L)/2` interpretation must be checked against the actual page image before a definitive source citation.
- TC1: searchable text on PDF p.12 names single-bar analysis, volume, trend, MFI, Four Windows and the Profitunity air bag as tools at a described skill level; this is not proof that every item is a mandatory signal gate.
- Course: text extraction is effectively unavailable; no rule is attributed to this scan without image/OCR review.

No invented printed page numbers are supplied. Until page images and printed folios are checked, these locators are provisional research locators, not final bibliographic citations.

## C. Preliminary rule matrix

Status vocabulary is exactly one of MATCH / PARTIAL / MISMATCH / MISSING / UNRESOLVED. “UNRESOLVED” is used where primary-source adjudication or a real consumer/call path is not yet established. No MATCH is granted on module names or documentation alone.

| Rule ID | Source / locator | Rule summary (paraphrase) | Python evidence | Android evidence | Test evidence | Status | Impact / priority |
|---|---|---|---|---|---|---|---|
| SRC-001 | TC2 PDF p.136, Fig. 10.2 locator; New Trading Dimensions PDF p.55, Fig. 5-2 locator | AO compares a 5-bar median-price SMA with a 34-bar median-price SMA. Exact printed page/image to confirm. | `strategy.py::calculate_indicators` blob `53b56c8…` | `StandaloneRuntime.kt::ao` blob `c971551…` uses median price and 5/34 windows, but has an explicit early zero return before index 34. | No baseline cross-runtime vector run. PR #49 has a staged test suite, but its CI is not green. | UNRESOLVED | Warm-up and formula parity can affect WM2. P1 pending same-input vector. |
| SRC-002 | TC2 PDF p.155; First Wise Man chapter at PDF p.125 | Bullish divergent bar: lower low than prior bars and close in upper half; entry trigger above signal-bar high; protective stop below low. Mirror for bearish case. | `strategy.py` reversal-bar logic; `williams_signals.py::_latest_reversal`; blobs above | Campaign WM1/reversal extraction in `StandaloneRuntime.kt`; blob `c971551…` | No approved source-derived end-to-end fixture yet. | PARTIAL | Bot adds Alligator-mouth/angulation and safety gates; must distinguish source conditions from bot policy. P1. |
| SRC-003 | TC2 PDF p.122, Figs. 9-3/9-4 references | Angulation is described geometrically as price moving away at a steeper angle than Alligator/Jaw; a deterministic threshold is not established by this locator alone. | `williams_signals.py::_angulation` uses regression/window/distance heuristic; blob `a199370…` | Android campaign path uses a separate simplified distance-delta score; blob `c971551…` | No validated cross-runtime angulation vectors; no author-exact numeric threshold established. | UNRESOLVED | Could change WM1 eligibility and Python/Android decisions. P1 pending source/image review. |
| SRC-004 | TC2 PDF p.155 checklist; exact printed page/figure pending | Alligator/Jaw relationship is part of WM1 context; specific angle is compared with the Alligator. | Python indicator lines use median price and shifted series; exact seed/displacement parity needs vectors. | Android has both campaign Alligator and a diagnostic snapshot path with different source-price/shift semantics. | No identical-vector comparison. | PARTIAL | Divergent indicator values can alter context. P1 for campaign path; P2 for UI-only snapshot if confirmed non-decision-only. |
| SRC-005 | TC2 (Second Edition) — exact WM2 page/figure not yet identified; must inspect source text and graphics | Super AO / Three Wise Men second signal interpretation must be derived from the selected edition, not inferred from a module name. | `williams_signals.py::_latest_super_ao` on baseline requires `ao_green_streak`/red streak equal to 3 and a prior-row fractal/Balance-Line gate. | Android baseline campaign extraction includes `priorFractalValid` prerequisite. | PR #49 proposes a vector for independent WM2, but tests were not accepted; CI failure below. | UNRESOLVED | Gate can change chronology and eligibility. P1 pending source adjudication. |
| SRC-006 | TC2 and New Trading Dimensions; specific fractal page/figure not yet verified | Fractal confirmation must be causally available only after the required right-side bars. Equal-extreme handling must be sourced, not assumed. | `strategy.py` confirmed-fractal columns and `williams_signals.py::_latest_confirmed_fractal`; baseline call path needs full trace. | Android fractal calculation in `StandaloneRuntime.kt`; exact event timestamp/consumers not fully traced. | No baseline cross-runtime look-ahead vector. | UNRESOLVED | Premature confirmation would be look-ahead / repaint. P0 only if executable path uses future bars; not yet proven. |
| SRC-007 | New Trading Dimensions PDF p.55, Fig. 5-2 locator | AO source describes 5-vs-34 median-price SMA. | Python formula in `strategy.py`; exact seed not applicable to SMA AO but missing-history behavior is relevant. | Android `ao` returns 0 when index <34, then uses slices of 5 and 35 samples as coded; verify exact window boundaries. | Not executed on shared vectors. | UNRESOLVED | Off-by-one/warm-up differences can alter signals. P1. |
| SRC-008 | New Trading Dimensions, acceleration/deceleration chapter; page/figure not yet recovered | AC is a momentum-acceleration measure and must not be assumed to be an independent entry Boolean absent source evidence. | Baseline call-site inventory incomplete. | Android call-site inventory incomplete. | No tests proven on exact baseline. | UNRESOLVED | Role in final decision not yet proven. |
| SRC-009 | TC1 PDF p.12 and later MFI discussion; exact formula page/figure pending | MFI/volume interpretation appears in TC1; book-specific role differs from simply making it a mandatory gate. | Exact formula/callers not yet fully inventoried. | Exact formula/callers not yet fully inventoried. | No source-derived volume vectors. | UNRESOLVED | Tick volume vs exchange volume and missing data may affect interpretation. P2 until entry-path effect known. |
| SRC-010 | TC2 checklist PDF p.155; printed page/figure pending | Stop entry is a conditional break beyond the signal bar; a signal bar is not itself necessarily a market-entry instruction. | SignalSpec stores trigger/protective references; `campaign_execution.py::arm_initial_entry` rejects a missed trigger rather than substituting a market entry. | Pending/conditional order and campaign path exist in `StandaloneRuntime.kt`; full active-path trace remains open. | Static code evidence only. | PARTIAL | Trigger semantics appear explicit, but all legacy paths and real call chain are not fully proven. P1. |
| SRC-011 | TC1/New Trading Dimensions/TC2 edition-specific structure; no final page map yet | Indicator presence does not establish that each named indicator is an entry prerequisite. | Multiple candidate and legacy paths exist in `market_scanner.py`, `trader.py`, `williams_signals.py`. | Campaign path and strict-signal fallback exist in `StandaloneRuntime.kt`; active flags/callers not yet fully traced. | No exact runtime-flag integration test reviewed in this audit. | UNRESOLVED | Could create platform/config-dependent strategy. P1. |
| SRC-012 | All four sources; exact rule depends on edition/profile | Exhaustion/exit/campaign-add logic must be source- and policy-separated. | `campaign_engine.py`, `campaign_monitor.py`, `campaign_execution.py`; complete entry-to-exit path not yet traced. | Campaign FSM, protection and trailing routines in `StandaloneRuntime.kt`; full path not yet traced. | No end-to-end campaign lifecycle vector run. | UNRESOLVED | Exit semantics affect realized exposure; P1. |
| SRC-013 | Trading Chaos Course scan, 122 PDF pages | No page-specific claim may be asserted until scan text/images are reviewed. | N/A | N/A | None | UNRESOLVED | Source evidence blocker, not a code defect. |

This matrix is deliberately partial. It is not a claim that only 13 rules matter; the full independent-rule register remains outstanding.

## D. OBS-001–OBS-005 evidence split

### OBS-001 — SMMA
1. **Code fact:** Python seeds the first valid SMMA point with an arithmetic mean of the initial period samples; Android initializes outputs to the first sample and recursively updates from index 1.
2. **Behavioral difference:** These recurrences have different early-history outputs. A numerical shared-vector comparison was not run here.
3. **Source conclusion:** UNRESOLVED; source edition/platform seed convention and convergence tolerance require direct page/platform evidence.
4. **Decision impact:** Alligator lines and any signals depending on them may diverge.
5. **Remaining work:** Compare short history, long history, NaN/gap, and warm-up behavior on identical candle arrays. No test outcome claimed.

### OBS-002 — Alligator / `buildIndicatorSnapshot`
1. **Code fact:** Android `buildIndicatorSnapshot` uses close-price SMMA(13/8/5) and does not mirror the campaign path's median-price plus shifted lines; Python `calculate_indicators` uses median price and shifted lines.
2. **Behavioral difference:** The snapshot values are not numerically equivalent to campaign Alligator values by construction.
3. **Source conclusion:** The source rule favors median-price Alligator, but whether the snapshot is only diagnostic or influences a decision has not yet been proven by enumerating every consumer.
4. **Decision impact:** Confirmed impact to UI/diagnostics; trading impact remains unresolved until consumer graph is complete.
5. **Remaining work:** Search all consumers and compare call sites/feature flags.

### OBS-003 — Super AO
1. **Code fact:** Baseline Python and Android campaign logic apply a fractal-related prerequisite before treating a three-bar AO streak as an actionable WM2 candidate.
2. **Behavioral difference:** WM2 candidates without the prerequisite are suppressed by those baseline functions.
3. **Source conclusion:** UNRESOLVED until the exact TC2 edition page/figure establishes whether WM2 may present independently.
4. **Decision impact:** Changes candidate chronology and possibly entry opportunities.
5. **Remaining work:** Build paired cases with/without the prerequisite after source adjudication.

### OBS-004 — Angulation
1. **Code fact:** Python uses a windowed regression/distance score; Android uses a separate simplified distance delta.
2. **Behavioral difference:** Formulas and dimensional scaling differ. No cross-runtime values were run on identical input.
3. **Source conclusion:** Source describes qualitative/geometric separation; an author-exact numeric threshold has not been established.
4. **Decision impact:** May change WM1 detection/eligibility.
5. **Remaining work:** Inspect figure images and all relevant text; preserve UNRESOLVED if no deterministic source rule exists.

### OBS-005 — Active decision paths
1. **Code fact:** Baseline includes scanner/legacy Python paths and campaign SignalSpec path; Android contains campaign detection and a strict-signal fallback.
2. **Behavioral difference:** There are multiple implementations; which one reaches order mutation under each runtime flag has not yet been proven end-to-end.
3. **Source conclusion:** This is an implementation architecture question, not a direct book rule.
4. **Decision impact:** Different flags/platforms could produce different candidate sets.
5. **Remaining work:** Trace startup/config → scanner → signal → risk gates → ExecutionBarrier/Android signed order methods and cancellation methods.

## E. PR separation and test evidence

- `main` remains at `04da5f9c09572e99ff310be3a1908179165e528c` at this follow-up check.
- PR #49 (`audit/williams-2.1-task-004-williams-strategy`) is **open and draft**, head SHA `282d9c5364cae3215f5e200789efdbce77894fb8`; merge test commit is `efa2424c3e995a45e46c5f92596db39f2cf6e1a4`. Its code changes are not part of `main` and are not attributed to baseline.
- PR #49 CI for exact head: Android APK workflow **in progress**; Python backend tests **failure**; Williams Campaign CI **cancelled**.
- Python backend job run `38078658818`, job `114290840124`: static safety audit and compile steps passed; offline pytest failed **1 test / 165 passed**. Failure: `test_williams_book_alignment.py::test_ao_green_streak_drives_super_ao`; it asserts `super_ao_long` is true for a monotonically rising series but got false. This proves the PR's current test suite is not green; it does not by itself establish which rule is canonical.
- No tests were executed from this audit branch. CI on a different PR head is not proof of baseline behavior or audit-branch results.

## F. Golden vectors and remaining blockers

No shared golden vector has been marked as passing in this audit. A staged suite in PR #49 is evidence of proposed tests only; its exact-head CI is currently failing. The required vectors remain:
- SMMA: short/long history, seed, gaps/NaN and convergence;
- Alligator: median price, shift alignment, timestamps;
- AO/AC: exact 5/34 and warm-up boundaries;
- Fractals: center vs confirmation index, equal/right-side extremes, no look-ahead;
- WM1: source-derived divergent bar and trigger;
- WM2: three AO bars with/without WM3 prerequisite, after source rule adjudication;
- WM3: confirmed fractal and conditional trigger;
- closed vs unfinished candle;
- stale/expired/cancelled PendingSignal;
- campaign add, structural invalidation, protection/trailing/exit.
For every case, the final evidence must show fixture hash, Python output, Android output, expected result source and test-run SHA. Until then, mark UNRESOLVED.

## G. Confirmed defects vs hypotheses

- **Confirmed code divergence:** Python and Android SMMA initialization differs. Impact is plausible but exact magnitude/decision effect still needs vectors. Provisional P1 investigation item, not a final book mismatch.
- **Confirmed code divergence:** Android diagnostic Alligator snapshot uses different price input and shift semantics than campaign path. UI/diagnostic mismatch is established; trade impact depends on consumers. Provisional P2.
- **Confirmed code divergence:** Python/Android angulation implementations differ. Because the source does not yet yield a deterministic numeric threshold, this is a parity defect/heuristic limitation, not yet a confirmed violation of a precise source formula. Provisional P1.
- **Confirmed baseline gating behavior:** Super AO depends on prior fractal gate in baseline paths. Whether this is a source mismatch remains UNRESOLVED.
- **Confirmed architecture risk to investigate:** multiple decision paths exist. Do not call this a production bypass until each path is traced to actual order mutation.
- **No confirmed P0 yet:** no evidence has been shown in this pass that an executable path actually consumes future candles. Look-ahead risk remains a blocker to acceptance until proven absent.

## H. Next required steps

1. Get an exact audit-branch HEAD SHA via a branch ref endpoint/clone path that supports slash names; then recheck current `main` and compare.
2. Visually inspect the source pages and figures above; OCR the Course scan; capture title/edition/printed page details.
3. Complete callers/consumers graph for Python and Kotlin, including all Binance order/cancel mutations and feature flags.
4. Build source rule registry across all four books, one independent rule per row.
5. Run only offline tests against a fixed commit and identical fixtures; no exchange orders.
6. Update each matrix status only when primary-source, code and test evidence agree.
7. Produce defect cards and final priority plan. Until every relevant path and source ambiguity is resolved, overall status remains **IN PROGRESS — NOT ACCEPTED**.


## I. Source-image verification update (2026-10-10)

This section records only pages whose rendered PDF images were inspected. PDF page index is 1-based; printed folios are copied from the visible footer. These are source locators, not yet a complete source registry.

### TC2 — `Торговый Хаос 2 (3книга).pdf`

| PDF page | Printed page | Visible heading / figure | Verified rule or observation | Implementation linkage / status |
|---:|---:|---|---|---|
| 122 | 107 | Chapter 9 “Первый мудрец”; heading “АНГУЛЯЦИЯ”; text references Figs. 9-3 and 9-4 | A bullish reversal bar has a lower low and closes in the upper half; bearish is inverse. Price should move away from the Alligator. Angulation is presented as geometric separation/angle; the page says to identify where bars cross/pass the Alligator “mouth” and imagine a line along the Alligator and another along prices. It does not define a deterministic numeric slope/window/threshold. | Python `williams_signals.py::_latest_reversal` and `_angulation`; Android `StandaloneRuntime.kt::angulationScore` / campaign signal detection. Source supports qualitative geometry, not either implementation’s exact score. SRC-002 PARTIAL; SRC-003 UNRESOLVED. |
| 125 | 110 | Chapter 9; Fig. 9.6 “График японской иены” | Describes placing a buy stop one tick above the bullish reversal bar high and a sell stop below the bearish reversal bar low; protective stop beyond the opposite end of the signal bar. It also warns the reversal signal is against the prior trend and discusses expected AO colour/context. | Python `campaign_execution.py::arm_initial_entry`; Android campaign conditional entry and protection routines. This supports conditional trigger vs immediate market entry; does not alone prove every live path. SRC-010 PARTIAL. |
| 136 | 121 | Chapter 10 “Второй мудрец”; Fig. 10.1 caption visible | The rendered Russian text describes AO as comparing recent momentum over five bars with a 34-bar simple moving average and displays median-point expressions as “(H-L)/2”. This is inconsistent with the conventional median-price expression and must not be silently corrected from OCR or convention. The visible page does not settle the exact source formula for this Russian edition. | Python `strategy.py::calculate_indicators`; Android `StandaloneRuntime.kt::ao`. AO formula interpretation remains UNRESOLVED for this edition until adjacent formula/figure pages and New Trading Dimensions are reconciled. SRC-001/SRC-007 UNRESOLVED. |
| 153 | 138 | Chapter 12 “Что происходит, когда мудрецы собираются вместе” | The page explicitly lists the classic signal order: (1) bullish/bearish reversal bar, (2) Super AO — three consecutive bars of one colour, (3) fractal signal. The worked long-position sequence uses the reversal-bar trigger first, then the third green AO bar as the second/add-on signal, then fractal breakouts. This directly does **not** establish a requirement for a prior WM3/fractal before WM2; WM3 is listed third. It does establish the illustrated canonical campaign order WM1 → WM2 → WM3, with WM2 described as a later/additional signal, not the first presenting entry in this example. | Python `williams_signals.py::_latest_super_ao` has a prior-row fractal/Balance-Line gate; Android baseline campaign signal extraction has a `priorFractalValid` gate. The source-image evidence contradicts treating WM3 as a prerequisite to WM2 in the classic sequence, but exact gate semantics and any alternative interpretation must still be traced in full context. SRC-005 remains UNRESOLVED pending code-path/vector adjudication; do not modify strategy in this audit. |
| 154 | 139 | Fig. 12.1 “График золота во время бычьего движения” | Figure legend labels item 2 as the three green AO bars and item 3 as fractal signal; the table lists the three-green-AO signal before subsequent fractal entries. | Corroborates PDF p.153 / printed p.138. Same linkage as above. |
| 155 | 140 | Heading “Стратегия Profitunity для входа на любой рынок и выхода с любого рынка”; Fig. 12.1 referenced | The first presenting signal is a bullish/bearish divergent bar with good angulation relative to Alligator. For a pristine bullish signal: lower low than preceding bars and close in upper half; angulation should be steeper than Alligator/Jaw. Place conditional buy stop at the high or a few ticks above, and protective stop just below the signal-bar low. If price continues up for another two bars and the stop is not triggered, the text says a bearish fractal may have formed; it then describes adding on three green price bars/AO context and later fractal breakouts. | Python reversal/trigger/protection and campaign add-on; Android `longCampaignSignals`, `submitCampaignAddOn`, campaign stop/trailing. Full exit/campaign parity is not proven by this source page alone. SRC-002/SRC-010 PARTIAL; SRC-012 UNRESOLVED. |

**Edition caution:** These are page-image facts for the supplied Russian TC2 PDF, whose visible footer identifies the book as “Джастин Грегори-Вильямс, Билл М. Вильямс ‘ТОРГОВЫЙ ХАОС 2’”. Do not substitute page numbers from another edition. Printed folios above are image-verified; PDF page indices are separate.

### Trading Chaos Course — scan-level visual verification

The supplied `Bill Williams - Trading Chaos Course.pdf` is a 122-page image scan. OCR is being used only as a locator; OCR output is not accepted as source proof unless the corresponding page image is inspected.

| PDF page | Printed course folio | Visible material | Verified source observation | Limits |
|---:|---:|---|---|---|
| 38 | Level II, page 7 | Wave 3 / Wave 4 course sheet | This sheet describes wave-3 slope as generally steeper than wave 1 and lists four confluence clues for wave-3 completion: MFI divergence, target zone, a fractal at the extreme, and a squat among the extreme bars. | Context is Elliott-wave/wave-exhaustion education, not proof of a universal entry gate. No bot-rule status assigned yet. |
| 62 | Level II, page 30 | “PRISTINE FRACTAL” diagrams A–D | Image text says an up fractal has the middle bar’s high above the two preceding and two following bars; down fractal is the inverse. The examples show shared up/down fractals, inside bars, and a six-bar case when the fifth bar’s high equals the previous high. Therefore, the course sheet does not support the simplified assertion that all fractals require strict inequality against both right-side bars in every configuration. | The full course’s edition/version and relation to the TC2 second edition remain unverified. Need compare course examples against Python and Android tie-handling before any status change. |
| 74 | Level II, page not visually confirmed in this pass | OCR identifies review questions about fractal essential characteristics/start/signal. | Locator only; no rule extracted from OCR alone. | UNRESOLVED until page image inspected. |

The course is **not fully reviewed**. A first OCR pass was attempted but did not finish across all 122 pages within the available execution window. Only pages listed above have been visually inspected; no claim of exhaustive course coverage is made.

### Newly clarified OBS-003 source interpretation

The TC2 supplied edition’s Chapter 12 page image (PDF p.153 / printed p.138) gives a classic ordered sequence WM1 reversal bar → WM2 Super AO (three consecutive same-colour bars) → WM3 fractal. The worked example places WM2 before the fractal signal, so a prerequisite that specifically demands an already-confirmed WM3/fractal is not supported by this illustrated sequence. However, the book calls the reversal bar the “first presenting signal” and illustrates the AO event as the second/add-on stage. Therefore:

- **Established:** WM2 does not require a prior WM3 merely by the source’s stated classic ordering.
- **Not established:** WM2 is always a standalone *first presenting entry* when WM1 is absent.
- **Code status:** baseline Python and Android contain prior-fractal-related gates; active path and exact field semantics still require full trace.
- **Disposition:** keep Rule SRC-005 / OBS-003 UNRESOLVED for final parity; do not change trading logic until separate authorization.

## J. Current Git / CI evidence limits

- This execution environment has no local checkout at its working directory: `git status`, `git rev-parse HEAD`, `git branch --show-current`, and `git log` fail with “not a git repository”. Therefore there is no honest local working-tree/staged-change report available from this runtime.
- GitHub connector independently resolves `main` to `04da5f9c09572e99ff310be3a1908179165e528c` (commit title “Update README.md”). Its workflow-run lookup returned an empty list; this is **not** evidence of a passing or absent CI run.
- GitHub confirms audit branch `audit/williams-2.1-forensic-20261010` exists, but branch search returns the name only, not the current ref SHA. The prior report SHA `f2d2eb24d8c78be871567f93dcf5fc973dd3ff97` is verified as a commit object, but cannot be asserted as the branch’s current HEAD without an independently readable ref.
- Current PR #49 metadata is different from the prior cached observation: open, draft, unmerged, base `main` at `04da5f9c09572e99ff310be3a1908179165e528c`, head branch `audit/williams-2.1-task-004-williams-strategy`, head SHA `334967f55a10f47a798d31951d31d8278c9a15d5`. Do not use the previously observed `282d9c5...` as current head.
- Exact-head PR #49 workflow results returned for `334967f55a10f47a798d31951d31d8278c9a15d5`:
  - Python backend tests, run `38078748291`: completed / success. Job `114291104126`: release contract gate, compile, static safety audit, and offline tests all passed. This is PR-head evidence only.
  - Build Android APKs, run `38078748322`: completed / failure. Job `114291103213`: Android unit test `WilliamsStrategyGoldenVectorsTest > aoAndAcMatchSharedFormulaVectorsAndUseExactly34SlowSamples` failed at `WilliamsStrategyGoldenVectorsTest.kt:64`; 29 tests completed, 1 failed. APK build/version/signature steps were skipped after unit-test failure. The log also emitted a missing `WILLIAMS_DEBUG_KEYSTORE_B64` configuration error; exact causal relation to the unit-test failure is not established.
  - Williams Campaign CI, run `38078748306`: completed / failure. Job `114291334892`: 5 failed, 164 passed; five `test_campaign_execution.py` cases fail with `CampaignExecutionError: Canonical Spot campaign entries require H1 signals` because the tests supply signals rejected by the changed H1 gate. Job `114291335137` also failed Android unit tests with the same AO/AC golden-vector assertion.
- PR #49 changes are not part of the baseline `main`; none of the above outcomes may be represented as baseline CI or audit-branch CI.


## K. Further primary-source image checks (2026-10-10)

### New Trading Dimensions — `Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга.pdf`

- **Edition/bibliography:** The supplied title page visibly reads “Новые измерения в биржевой торговле; как извлечь прибыль из хаоса: рынки акций, облигаций и фьючерсов”, Bill Williams, Doctor of Science. The supplied title page does not visibly establish a publication year, publisher, or edition number; those bibliographic fields remain UNRESOLVED.
- **PDF p.55 / printed p.55 / Fig. 5-2, “Что такое AO?” — image inspected.** The page says AO measures current market force over the last five bars relative to the last 34 bars. It explicitly prints median-point expressions as “(H-L)/2” and describes subtracting a five-period simple moving average from a 34-period simple moving average in the surrounding text/figure callout. This supplied Russian edition therefore contains a formula notation that does not match the conventional median price `(H+L)/2` used by the baseline code. Do not silently “fix” the book or treat the code as author-certified from convention; reconcile translation/edition and figure context. **SRC-001/SRC-007: UNRESOLVED.**
- **PDF p.35 / printed page visible in footer to be recorded after image check; Alligator chapter** is a further locator for the Alligator explanation, but this pass did not complete its detailed diagram/shift review. No Alligator MATCH is claimed.

### Trading Chaos, supplied Russian copy — `1. Торговый Хаос - 1.pdf`

- **Title image, PDF p.1 / printed p.1:** visibly titled “Торговый хаос: экспертные методики максимизации прибыли”, “Д-р Билл Вильямс”. This copy’s visible title page does not establish the original edition, translator, publisher, or year; those remain UNRESOLVED.
- **PDF p.42 / printed p.42, heading “Индекс облегчения рынка (MFI)” — image inspected.** The formula is visibly `MFI = Range (High − Low) / Volume`; the prose describes volume as **tick volume**. The page does not make MFI a universal entry Boolean.
- **PDF p.43 / printed p.43:** the text explicitly compares the current bar’s MFI to the immediately preceding bar’s MFI and says the approach is not based on optimizing a fixed value. The page describes MFI as an effectiveness/price-movement measure.
- **PDF p.44 / printed p.44:** the four Profitunity windows are shown: Green `(+ tick volume, +MFI)`, Fading `(−, −)`, Fake `(−, +)`, Squat `(+, −)`. The accompanying discussion interprets the combinations as market behaviour, not a universal buy/sell order by themselves.
- **Implementation linkage:** baseline Python `strategy.py::_profitunity_window` maps the four volume/MFI directions to GREEN/FADING/FAKE/SQUAT. Its `calculate_indicators` comment explicitly treats Binance traded volume as a proxy because it is not necessarily original tick-count volume. Android `buildIndicatorSnapshot` uses `(H-L)/volume` as `mfiProxy`, with no proof that it is a mandatory campaign entry gate. Formula resemblance is established; semantic parity of volume input and every consumer is not. **Rule MFI-001: PARTIAL** for four-state classification; volume equivalence and entry-path role remain UNRESOLVED.

### Edition and locator policy

The first-page images establish that the TC2 PDF is the Russian **second edition** of *Trading Chaos: Maximize Profits with Proven Technical Techniques*, by Justine Gregory-Williams and Bill M. Williams, published in the visible imprint as Moscow, IK Analitika, 2005. This is the only one of the four supplied files whose edition and year are fully visible in the inspected title image. TC1, New Trading Dimensions, and the Course must retain their supplied filenames and provisional edition metadata until their own bibliographic pages are verified. A PDF page index and a printed folio are never interchangeable.

### Current audit branch commit sequence

- Before the source-image appendix update, the branch was advanced by commit `b489227576eed06eadcacafe92714c8d8d7b560e` (“docs: add visually verified Williams source locators and exact PR CI evidence”). The file read back from the named audit branch returned the new appendix blob `4dc609dec45577ed9bd61297a3ab401591210f67`, confirming the write is visible on that branch.
- This does not supply a local Git working tree or staged-diff report. No local checkout is present in this execution runtime. After the next documentation commit, use the returned commit SHA as the exact new audit-branch tip at the time of write; the connector still lacks a branch-ref endpoint that directly returns the ref SHA.


## I. Follow-up evidence — 2026-10-10 (remote Git, PR CI, visual source review)

### I.1 Exact remote Git state

- Remote ref resolution: comparing `audit/williams-2.1-forensic-20261010` to candidate commit `f2d2eb24d8c78be871567f93dcf5fc973dd3ff97` returned `status=identical`, `ahead_by=0`, `behind_by=0`. The branch therefore currently resolves to **`f2d2eb24d8c78be871567f93dcf5fc973dd3ff97`**. This is a fresh comparison, not an assumption from the previous report.
- `main` compared to `04da5f9c09572e99ff310be3a1908179165e528c` also returned `identical`, zero ahead/behind. Baseline remains unchanged.
- `main...audit branch`: `ahead_by=4`, `behind_by=0`; changed paths are only `docs/WILLIAMS_BOT_2_1_FORENSIC_AUDIT.md` and `docs/WILLIAMS_BOT_2_1_FORENSIC_AUDIT_APPENDIX.md`.
- **Local Git working-tree state is unavailable.** The active container's working directory is `/`, and `git status` reports “not a git repository”; there is no local clone at the runtime root. Therefore local branch checkout, staged/unstaged changes, and untracked files cannot be attested. Remote ref comparisons do not substitute for local `git status`.

### I.2 PR #49 is a separate branch and current CI is not green

Fresh PR read: [PR #49](https://github.com/19wolfalone92/Williams/pull/49) is open, Draft, unmerged, base `main` at `04da5f9c09572e99ff310be3a1908179165e528c`; head branch `audit/williams-2.1-task-004-williams-strategy`, head SHA **`334967f55a10f47a798d31951d31d8278c9a15d5`**. Its merge-test commit is **`83a6581f3a0870ecae9b39c7af45a092c42e62d7`**, whose commit message explicitly says it merges PR head into the baseline. Workflow logs check out `refs/remotes/pull/49/merge`; record these results against that PR merge-test ref, not as baseline-main results.

| Workflow | Run ID | Job ID | Tested PR merge ref | Outcome and evidence |
|---|---:|---:|---|---|
| Python backend tests | `38078748291` | `114291104126` | `83a6581f3a0870ecae9b39c7af45a092c42e62d7` | **SUCCESS**; release gate, compile, static safety audit and offline suite passed; `166 passed, 4 warnings`. |
| Build Android APK | `38078748322` | `114291103213` | `83a6581f3a0870ecae9b39c7af45a092c42e62d7` | **FAILURE**; `29 tests completed, 1 failed`: `WilliamsStrategyGoldenVectorsTest > aoAndAcMatchSharedFormulaVectorsAndUseExactly34SlowSamples`, assertion at `WilliamsStrategyGoldenVectorsTest.kt:64`. Debug/release APK build, version verification, signing verification and artifact publication were skipped after the unit-test failure. |
| Williams Campaign CI | `38078748306` | Python `114291334892`; Android `114291335137` | `83a6581f3a0870ecae9b39c7af45a092c42e62d7` | **FAILURE** in both jobs. Python: **5 failed, 164 passed**; each failed campaign-execution test passes a `5m` signal and now receives `Canonical Spot campaign entries require H1 signals`. Android build/unit-test job failed; debug version check/upload skipped. |

These are the current observed runs associated with PR #49. No successful APK build is established for this SHA. They are not evidence of test status on `main`, nor were they run on the forensic branch. The combined-status endpoint returned an empty list for the queried commits; an empty list is **not** treated as a pass. Run/job logs above provide the non-empty CI evidence.

### I.3 Primary-source visual checks and OCR cross-check — course scan

Exact Library file: `Bill Williams - Trading Chaos Course.pdf`, 122 PDF pages. PDF metadata contains no edition/year; page 1 visually identifies the document as Profitunity Trading Group material, so edition/year remains **UNRESOLVED**. Contact sheets for all 122 pages were generated and visually reviewed for page-level structure; targeted high-resolution images and OCR were cross-checked manually. Full-document OCR did not complete within the runtime limit; do not describe the scan as fully OCR-transcribed.

| Source locator | Visual/OCR result | Rule/evidence in own words | Code link and confidence |
|---|---|---|---|
| Course PDF p.61, printed p.29, heading “FRACTALS AND LEVERAGE” | Page image and targeted OCR agree. | A fractal uses at least five consecutive bars; the middle bar must have a higher high (up) or lower low (down) than the two bars on each side. | Relates to Python confirmed-fractal helpers and Android fractal math, but active consumer/timing path is not yet fully proven. High confidence for what this page says. |
| Course PDF p.62, printed p.30, “PRISTINE FRACTAL” diagram A–D | Page image and targeted OCR agree. | The minimum 5-bar structure may create both up/down fractals; equal high/low on the fifth bar can require a 6th bar before the middle bar is strictly extreme. This is an explicit source edge case, not a universal assumption that exactly five bars always suffice. | Golden vector needed for equal extreme and confirmation time. High confidence. |
| Course PDF p.64, printed p.32, “FRACTAL START / FRACTAL SIGNAL” | Page image and targeted OCR agree. | The course distinguishes a fractal start (followed by opposite-direction fractal) from a fractal signal (following an opposite-direction fractal); the page says start and signal are generated at the same time in the illustrated sequence. | Do not collapse start/signal roles without checking the selected strategy profile and actual code consumer. High confidence for page text. |
| Course PDF p.91, printed p.15, “USING THE PROFITUNITY ‘TRADING PARTNER’” | Page image and targeted OCR agree. | This course section lists “Air Bag Protection” as a two-bar reversal on a significantly higher timeframe and explicitly says it is not an initiating signal, but protection from erratic movement. The same page’s “Rhythms of the Market” section describes 5/13/34 moving-average position as long/below-short/between-out. These are course-level material; do not automatically convert every line into the current bot’s entry gate. | Relevant to policy/source separation; timeframe hierarchy in the bot remains product policy unless independently tied to the selected strategy. High confidence for the page content. |

### I.4 Visually verified TC2 source locators — Second Edition

The title page (TC2 PDF p.1) explicitly identifies *Trading Chaos: Maximize Profits with Proven Technical Techniques*, **Second Edition**, Justine Gregory-Williams and Bill M. Williams, John Wiley & Sons; the Russian title page identifies the 2005 Moscow edition. Printed page numbers below are confirmed visually.

| PDF page | Printed page | Figure / heading | Source rule and implication |
|---:|---:|---|---|
| 122 | 107 | Chapter 9, “Первый мудрец”; “Ангуляция”; references Figs. 9-3 and 9-4 | Describes angulation geometrically: price bars move away from the Alligator/Jaw at a steeper angle; the passage calls for drawing/considering a line along the Alligator’s Jaw and another along price movement. The visible text does not define one universal numeric regression window, distance unit, or threshold. Thus Python regression score vs Android distance-delta is a confirmed implementation divergence, but neither may be called the author-exact formula. |
| 125 | 110 | Fig. 9.6, “График японской иены” | Visually documents a bullish reversal bar far from the Alligator and a conditional buy stop one tick above the marked reversal bar. Supports separating signal recognition from the price trigger. |
| 136 | 121 | Fig. 10.1, “Что делает осциллятор awesome oscillator” | Describes AO as 5-bar vs 34-bar simple moving averages of bar midpoints. **Important literal-source ambiguity:** both this page and New Trading Dimensions PDF p.55 visibly print midpoint notation as `(H-L)/2`, which is inconsistent with the usual high/low midpoint expression `(H+L)/2`. Do not silently “correct” the primary source. AO input formula remains **UNRESOLVED** until this apparent print/translation error is adjudicated using independent source evidence. |
| 137 | 122 | Fig. 10.3, “Три зеленых бара АО создают сигнал для увеличения позиции” | After an initial Wise-Man-1 reversal entry, the text says the second entry is normally added after three consecutive green AO bars; buy stop is placed just above the price bar corresponding to the third green AO bar. This is a continuation/add-on context, not proof that every WM2 must require a fractal. |
| 155 | 140 | Chapter 12, “Что происходит, когда мудрецы собираются вместе”; Fig. 12.1 cited | The text lists the classical sequence as reversal bar, Super AO (three consecutive bars of one color), then fractal signal. Crucially, it separately asks what happens if Super AO is the first signal after reversal and answers: enter just above the price bar corresponding to the third green Super AO bar and place a protective stop near the trend-reversal base or below the signal bar. **Source conclusion: WM2/Super AO can be the first actionable signal before WM1 or WM3/fractal.** The baseline implementation’s prior-fractal prerequisite is therefore inconsistent with this selected TC2 Second Edition scenario if that gated function is confirmed as the active path. No strategy code changed. |

### I.5 New Trading Dimensions — visually verified AO locator

Exact Library file: `Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга.pdf`. PDF p.1 title page visually identifies *Новые измерения в биржевой торговле; как извлечь прибыль из хаоса: рынки акций, облигаций и фьючерсов*, by Bill Williams. PDF p.55 has printed page **55**, Chapter 5 “Удивительный осциллятор”, Fig. 5-1 “Удивительный Осциллятор (AO)” and Fig. 5-2 “Что такое ‘AO’?”. The page describes 5-bar vs 34-bar SMA of bar midpoints; the literal printed midpoint formula again reads `(H-L)/2`. This independently confirms the source-text inconsistency, not a resolved mathematical definition. Printed page 55 is confirmed visually.

### I.6 Rule resolution update and remaining boundary

- **OBS-003 source question is now resolved:** TC2 Second Edition explicitly permits Super AO to be the first actionable signal before either reversal-bar or fractal signal (printed p.141 in the “what if Super AO is first?” passage; the same chapter’s classical sequence is printed p.138). Implementation parity remains open until all baseline callers and the active path to order mutation are proven.
- **OBS-004 source interpretation is narrowed:** angulation is a qualitative/geometric principle in the inspected TC2 passage; no author-exact universal numeric formula is established by these pages. Keep numeric thresholds as implementation heuristics, not source rules.
- **Fractal edge case added:** Course PDF p.62 / printed p.30 gives an equal-extreme case requiring six bars. It needs a golden vector before declaring Python/Android parity.
- **AO formula remains UNRESOLVED:** source scans visibly contain `(H-L)/2` notation in both books, while the expected arithmetic midpoint is ordinarily `(H+L)/2`. Do not change the bot or rewrite source wording without further independent primary-source adjudication.
- The full all-book independent-rule register, complete Python/Android call graph to every order/cancel mutation, and executed identical golden vectors are still incomplete. No test has been run on the forensic branch in this pass; no order was submitted; no Mainnet was used; no strategy code was modified.
