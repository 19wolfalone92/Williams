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
