# WILLIAMS BOT — Four-book / signed Demo diagnostic #18 audit (2026-11-24)

**Scope:** `19wolfalone92/Williams` ONLY. Source: user-supplied `Williams_Diagnostics (18).zip`, Binance Demo UI screenshots, independently fetched repository source from branch `fix/diag17-stop-rearm-chronology-20261123` SHA `427a94902cabe294f1f7b6ca74ef775561422685`, and five user-supplied edition PDFs. **No modification of `Williams-AI`, no main merge, no live funds, no signed trading requests by this audit.**

## 1. Ground truth — diagnostics 18, not a hypothetical backtest

The report has **79 checks: 55 PASS, 6 FAIL, 4 WARN, 14 NOT VERIFIED**. Failed: `EXE-03` protected filled position, `EXE-04` active STOP evidence, `EXE-13` all-position signed STOP, `RISK-01` observed position downside risk, `RISK-02` journal reserved risk, `STR-07` strategy source provenance.

Captured signed USDⓈ-M Demo: **ETHUSDT LONG +0.463 ETH at 1641.32; MARK 1707.8189; no qualified live protective STOP in open Algo results; former locally journaled STOP 1717.12 now above MARK**. The exchange order absence is at the snapshot, not immutable evidence that the former STOP was never accepted. The older `SHORT 0.031` campaign remains locally `OPEN_PROTECTED`, although the signed net exposure is LONG. An existing `MARKET_REDUCE_ONLY` EXIT for 0.463 LONG is `SUBMISSION_UNKNOWN`, hence neither replay nor claim of flatness is permitted.

The UI displayed **0.64% journal reserve vs 0.60% aggregate limit**, consisting of conservative 0.60% recovery exposure plus the historical 0.04% SHORT campaign reservation (not proof that Binance holds two simultaneous positions). Measured worst-case loss on an unprotected position is UNKNOWN. Do NOT reset the risk journal without exchange-backed resolution.

Binance Demo UI also displayed a *manual close* error, “ReduceOnly Order is rejected”. **This screenshot does NOT prove the Android bot received a signed `-2022` error**; instead it motivated defensive code tests that explicitly rejected reduce-only MARKET POSTs must not be treated as unknown transport outcomes. The old unresolved EXIT remains unresolved until separately proven.

## 2. Books — physically reviewed source anchors and literal-vs-engineering distinction

| Source | Original supplied PDF and evidence checked | Live-bot relevance and current certification |
| --- | --- | --- |
| Trading Chaos, first edition (TC1) | `1. Торговый Хаос - 1(1).pdf`, physical PDF p.84: Wave-2 retracement, structurally anchored defensive action | **OBSERVE** only for NEW exposure; wave interpretation still NOT expert-certified against historical H1/H4 cases. Never authorize a live trade solely from a wave label. |
| New Trading Dimensions (NTD) | `Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга(1).pdf`, physical p.52: fractal plus Alligator Teeth examples | **OBSERVE** for NEW risk; retain protection/exit for already verified NTD-owned legacy campaigns. Not full five-dimensional figure-by-figure certification. |
| Trading Chaos 2nd edition (TC2) | `Торговый Хаос 2 (3книга)(1).pdf`, physical p.161 (book p.146): exits relative to the extremes of last 3–5 completed bars; physical p.165 (book p.150): accompanying stops / profit-taking and opposite signals | **ONLY live new-risk source.** Current code `Tc2AdaptiveBarChoice.kt` allows 3/4/5 closed H1 bars. The automatic 1.25×/0.60× range selector is **project engineering**, not words from the book; the 110% noise guard and 1.5× shock veto are also extensions. No claim of superiority absent same-cohort walk-forward. |
| Profitunity Course | `Bill Williams - Trading Chaos Course(1).pdf`, scanned physical p.87 (Level III p.12): stop/rhythm discussion, 2-bar reversal and 5/13/34 rhythms | **OBSERVE** for NEW exposure. Course is scanned: independent diagram-by-diagram OCR/manual verification is NOT complete. No full page or rule certification. |

Canonical runtime intent: H1 decisions, D1/H4 context, M15/M5 diagnosis only, no M15 entry confirmation; three Wise Men can appear independently, confirmed five-bar fractals with no look-ahead, valid STOP entries, LONG+SHORT, one active strategy-owned campaign per pair, TC2 exit via structural trailing/opposing signal. Do not turn independent research filters into fictitious mandatory book restrictions.

The earlier draft PR #271 has a separate **partial** four-book/UTC-candle audit (57 indexed sections/642 PDF pages, zero independently figure-verified rules). **After verifying unchanged parent blobs, this branch imported its 3 independently reviewed native UTC-grid code corrections + 2 associated Kotlin tests and 15-asset historical replay corrections (SUI rather than UNI) with a 480-closed-H1 SUI fixture + Python tests.** The accompanying independent audit conclusions still require separate acceptance. Green CI does not certify full book fidelity.

## 3. Root-cause review and repairs made on THIS branch

| Case | Root cause | Changed files | Proof status |
| --- | --- | --- | --- |
| `STR-07` false source corruption | Historical opposite SHORT plus recovered unowned LONG were interpreted as two contemporary strategy-owned orders; source snapshots incorrectly flagged `MULTIPLE_ACTIVE_CAMPAIGN_OWNERS`. | `WilliamsUnifiedEvidence.kt`, unit tests | Reclassified as `ACCOUNT_QUARANTINED_OBSERVATION_ONLY` **only** when one verified historic opposite owner and one unowned recovery record. Same-direction conflicts or two strategy owners still INVALID. No risk release, new entry, STOP or close authorization. CI pending at writing. |
| `RISK-02` misleading main UI | UI card described 0.64% `reservedRiskPct` as if it were the exchange-confirmed downside. | `CockpitScreens.kt` | Shows journal reservation explicitly, warns actual risk UNKNOWN while STOP unconfirmed. **Numerical breach/old campaign retirement NOT fixed.** |
| Possible reduce-only POST rejection forever `SUBMISSION_UNKNOWN` | `closePositionInternal` catch labeled ALL failures unknown, even exact HTTP400 `-2022` negative MARKET POST receipts. | `FuturesQuarantinedExitObservation.kt`, `NativeFuturesCampaignCoordinator.kt`, negative tests | Only definitive `/fapi/v1/order` POST -2022 *before response* is persisted as `REJECTED`, `EXIT_TERMINAL_POSITION_OPEN`; restore prior campaign lifecycle, preserve exact rejected ID, no replay, no flatness assumed. Any timeout, 5xx, other 4xx, exception after POST response remains ambiguous. A previously unresolved historical EXIT is NOT automatically relabeled rejected. |
| Conflicting source-policy comments | Code comment reported NTD as independently LIVE despite TC2-only policy. | `WilliamsUnifiedEvidence.kt` | Comment and JSON completeness-warning corrected. No book/source policy admission change. |

## 4. Entire execution chain — acceptance status

| Chain | Status | Requirement to pass |
| --- | --- | --- |
| Native Binance UTC D1/H4/H1 timestamps, bounded cache, confirmed fractal and no look-ahead | SOURCE FIX PORTED / TEST REQUIRED | PR #271 UTC grid source fixes and Kotlin negatives imported without conflict; exact-head CI and full signed runtime not yet proven. |
| All WM1/WM2/WM3 order-independent TC2 entry long and short | NOT VERIFIED | Independently annotated book figure cases plus blind out-of-sample H1 signals and exact tick/Teeth/AO/AC test fixtures. Historical 15-asset SUI-vs-UNI research-set bug fixed, NOT human-expert signal truth. |
| New-risk admission, 15-symbol slots, 0.60% reservation, 3% day, 3 consecutive losses, 5% breaker, 50% margin | PARTIAL | Unit tests only; open-risk projection and turnover ledger with signed Demo / day-boundary / partial fills not acceptance-proven. |
| Original native STOP upon confirmed fill, account-wide STOP readback, partial-fill adjustments and no duplicate POST | **FAIL / P0** | Fresh private signed Binance Demo `FILLED`→`STOP_MARKET`, signed Algo detail in both scopes and closePosition evidence. |
| Former STOP canceled, stale trigger crossed; recovered/unowned LONG emergency risk resolution | **FAIL / P0** | Signed exact historical Algo terminal/no-child proof, two stable signed account positions, pending EXIT resolution, protected-first replacement or controlled reduce-only emergency path. No blind market close/new trigger. |
| Explicit reduce-only MARKET refusal / manual close mismatch | CODE FIX + E2E NOT VERIFIED | Tests for -2022 vs timeouts; independent Binance Demo rejection/side/size/order book and exchange ID forensic proof. |
| TC2 3/4/5/fractal STOP trailing, opposite-signal exits, returns and funding | PARTIAL / NOT VERIFIED | Same-cohort head-to-head walk-forward, protection handoff signed proof, fee+funding+reason journal. |
| Android Doze/reboot, private WebSocket reconnect, SQLite durability and 15-pair no memory exhaustion | NOT VERIFIED on user device | Samsung Android17 lifecycle with signed Demo and forced network loss/reboot while protected. |
| Signed installer and APK release | BUILD REQUIRED | New exact-commit signed APK from `Build Android APK`, Android lint/tests and pinned certificate SHA-256; emulator smoke; no release to `main` without P0. |

## 5. Non-negotiable trading constraints

Do not claim old ETH SHORT history is live Binance exposure. Do not release its 0.04% reservation until historic order/execution confirmation. Do not treat an **empty OPEN_ALGO** snapshot alone as proof an *old* STOP POST never happened. Do not move a LONG STOP already above MARK to the same stale level and expect Binance to accept it. Do not fabricate a protective receipt. Do not disable `reduceOnly` to get around a -2022 refusal.

**Status: HOLD / PARTIAL** — unit static fixes must pass exact SHA CI; live signed Binance Demo P0 protection and ownership remain unproven. PR stays separate, no automatic merge or live-money trades.
