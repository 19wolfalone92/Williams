# WILLIAMS BOT 2.1 — TASK 004
## Complete source-to-code audit: first evidence baseline

**Branch:** `audit/williams-2.1-task-004-williams-strategy`  
**Audit base / main SHA at start:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Audited pending change:** PR #44, head ref `fix/williams-fractal-stop-provenance-20261010`, SHA `dbe755a942276ac3b369afd47d424fff48bbb608`, **open / not merged** at audit time.  
**Trading safety:** no Mainnet enablement, no real order submission, no Testnet order submission. Source inspection and code/unit-test changes only.

> **Acceptance state at this checkpoint: BLOCKED / audit in progress.** This report is a source-to-code baseline; a row marked “known on PR #44” is not a fix on `main` unless this branch explicitly ports it. Nothing here establishes full source parity.

## 1. Source inventory and edition separation

These are the original source files available in the project Library and used directly for this audit. Page locators distinguish PDF page index from printed pagination where the scan exposes both.

| Source | Direct source locator inspected | What the source is authoritative for | Do not conflate with |
|---|---|---|---|
| **Trading Chaos**, original / first edition (1994; Russian scan) | `1. Торговый Хаос - 1.pdf`, PDF pages 67–84, chapter 8 “Использование фракталов и рычага”; also prior page references in the scan’s MFI / Zero Point material | Earlier Fractal Start/Signal/Stop, leverage, Market Facilitation Index and four Profitunity windows, Elliott/Zero Point context | TC2’s later Three Wise Men sequence; a current Binance base-volume field is not original tick-count volume |
| **Trading Chaos Course** (scan in Library; edition/issue date not established) | `Bill Williams - Trading Chaos Course.pdf`, scan pages 61–80 were inspected as page images; indexed text is unavailable for these pages | Historical teaching material and its visual checklists/illustrations, to be audited separately by image/page | Not automatically the same edition or rule set as TC2; exact quotations/printed folios cannot be supplied from the text index |
| **New Trading Dimensions** | `Билл_Вильямс_Новые_измерения_в_биржевой_торговле_2_книга.pdf`, PDF pp. 34–40 (Alligator), 41–54 (Fractal), 55–71 (AO), 72–90 (AC), 91–98 (Zones), 99–114 (Balance Line trades), 115–122 (profit taking), 123–138 (system integration) | Five linked dimensions and their separate AO/AC signal families, Alligator, fractal breakout, zones, Balance Line trades, exits | TC2’s Super AO; the names “AO” and “fractal” alone do not establish the same entry logic |
| **Trading Chaos 2**, second edition (2005; Russian translation scan in Library) | `Торговый Хаос 2 (3книга).pdf`, PDF pp. 136–138 / printed pp. 121–123, chapter 10 and Figs. 10.1–10.3; PDF pp. 145–150 / printed pp. 130–135, chapter 11 and Figs. 11.1–11.5; PDF pp. 154–157 / printed pp. 139–142, chapter 12 | Present working profile: WM1 reversal bar, WM2 three same-colour/rising-or-falling AO bars, WM3 confirmed fractal breakout, Teeth validity at activation, campaign order and trailing examples | TC1/NTD patterns such as AO Saucer/Twin Peaks, MFI windows, Thumb trade or older leverage prescriptions |

### Edition rule

The four sources describe an evolving family of systems, **not a single timeless checklist**. The bot’s current primary live profile is treated as **TC2 Three Wise Men**. TC1, Course and NTD remain separately labelled historical/optional profiles until their complete rules are implemented and separately accepted. Their additional methods must not silently become mandatory TC2 entry gates.

### Important image-reading evidence

- TC2 Fig. 10.3 (PDF p. 138 / printed p. 123) explicitly describes three green AO bars as a signal to add to a position. The corresponding price-bar trigger is a conditional level; it is not evidence that a market order should be fired at an arbitrary later scan.
- TC2 Fig. 11.1 (PDF pp. 145–146 / printed pp. 130–131) includes a six-bar example because an equal right-side extreme does not count as one of the required lower/higher bars.
- TC2 text on PDF p. 149 / printed p. 134 says the fractal remains a signal until it triggers or a newer fractal forms, and that the Teeth/Balance-Line relationship is evaluated when the signal activates.

## 2. Proof vocabulary

- **SOURCE-CONFIRMED**: supported by an inspected primary source text/figure and locator below.
- **CODE-CONFIRMED**: the named implementation is present at the baseline SHA.
- **BEHAVIOUR-CONFIRMED**: demonstrated by tests/replay at the exact commit. Source inspection alone does not qualify.
- **WILLIAMS RULE**: a source-supported trading rule.
- **SYSTEM SAFETY RULE**: a fail-closed execution constraint.
- **IMPLEMENTATION RULE**: a deterministic engineering convention (for example, order TTL).
- **RESEARCH REQUIRED**: source/edition does not settle the rule. **UNRESOLVED = BLOCKED**, not permission for developer interpretation.

## 3. Source-to-code crosswalk

The “baseline result” column describes the exact `main` snapshot from section 1, before this task’s corrections. PR #44 is reported separately when it already contains a correction on an unmerged branch.

| Rule / category | Primary-source locator and meaning | Python baseline | Android baseline | Result and required correction | Regression / proof | Unresolved question |
|---|---|---|---|---|---|---|
| **Profile separation** — WILLIAMS RULE | TC1 ch. 8; NTD chs. 3–10; TC2 chs. 10–12/Figs. 10.3, 11.1–11.5, 12.1–12.2. Different eras and named signal families | `strategy.py` merges AO/fractal/legacy diagnostic outputs into one boolean gate; `williams_signals.py` and campaign classes represent a separate candidate path | `StandaloneRuntime.kt` has a campaign path and legacy scan/scoring path | **MISMATCH**: one path can disagree with the other. Declare TC2 as the canonical live profile, keep TC1/NTD/Course outputs diagnostic unless separately selected, and fail closed on disagreements | Profile-isolation test must demonstrate that NTD Saucer/Twin Peaks and TC1 MFI do not silently gate TC2 | Which optional historical profile, if any, should ever be live-enabled? Blocked pending explicit owner decision |
| **D1/H4/H1/M15/M5** — IMPLEMENTATION RULE | No inspected primary source specifies this exact modern digital time-frame contract; it is the owner-approved system policy | `market_scanner.py` defaults its interval to H1, but `trading_config.py` defaults `execution_timeframe` to M5 and its chain includes M5 | `StandaloneRuntime.kt` main defaults `campaignExecutionTimeframe` to “5m”; this is used to generate campaign signals | **MISMATCH**: M5 creates canonical signal candidates. Set H1 as canonical signal TF; D1/H4 provide context; M15 only monitors/executions; M5 only diagnostics/replay. Failed H1 data fetch must not fall back to another TF | Test H1-only canonical signals, and assert M15/M5 cannot create a new entry | Exact policy for using D1/H4 as hard permission vs context-only remains an owner/system contract; do not add a hard veto merely because direction differs |
| **Alligator input** — WILLIAMS RULE | NTD ch. 3, PDF pp. 36–37: Median Price `(H+L)/2`; Jaw/Teeth/Lips are balance lines | `strategy.py` uses Median Price and SMA-seeded SMMA | Campaign detector computes Median Price, but main `analyseBase` calls `alligator(closes)`, uses close prices and unshifted lines | **MISMATCH** in the Android analysis path. Reuse a single parity helper; do not calculate the live Alligator from Close | Shared OHLC/Median-price golden vector in both runtimes | Seed convention is not described identically by every scanned edition/platform; SMA seed is an implementation convention, must be documented |
| **Alligator periods and displayed offsets** — WILLIAMS RULE | NTD ch. 3, PDF pp. 36–37: 13/8, 8/5, 5/3; Jaw offset 8 bars forward, Teeth 5, Lips 3 | `strategy.py` shifts series by 8/5/3 | Campaign path shifts series; main scoring path uses last unshifted close-based values; Kotlin local SMMA seeds recursively from first observation | **MISMATCH** across Android paths. Use the same helper for campaign, score/context and chart consumers; shifts must be line displacement, not future-price lookup | Assert exact values at pre-shift and first valid shifted indices; compare Python and Kotlin | How much warm-up is required for seed convergence? Until source convention is settled, short/invalid history must block live decisions |
| **Alligator sleep/awake** — WILLIAMS RULE + RESEARCH REQUIRED | NTD ch. 3 describes sleeping/awakening/eating/closing qualitatively | `strategy.py` maps state to order/close and a configurable spread-percent threshold | Runtime mostly maps line order/spread and trend states | **PARTIAL**: “awake” numeric spread threshold is a bot policy, not established here as an author-defined formula. Do not let a heuristic threshold redefine an individual WM signal | Tests for line ordering, no-data/warm-up fail-closed; separate tests for the implementation threshold | Exact objective formula for sleep/awakening and angulation is not proven from the scanned source |
| **AO formula and histogram color** — WILLIAMS RULE | TC2 ch. 10, PDF p. 136 / printed p. 121, Fig. 10.1; PDF p. 137 / printed p. 122: SMA(5, Median) − SMA(34, Median); green if AO exceeds previous bar, red if below | `strategy.py` uses correct rolling 5/34 medians | Android `ao()` uses 5 bars correctly, but `subList(index - 34, index + 1)` contains **35** prices for the slow SMA | **CONFIRMED FORMULA ERROR** in Android: compute exactly 34 slow samples (indices `i-33..i`); make warm-up explicit | Golden AO values at indices 33, 34 and 39; cross-runtime parity assertion | None for the 5/34 median formula; bar color at equal adjacent AO values should be neutral, not green/red |
| **AC formula and role** — WILLIAMS RULE / profile separation | NTD ch. 6, PDF pp. 72–90: AC is AO minus its 5-period SMA; the NTD chapter defines its own patterns/counts | `strategy.py` formula is correct; boolean `ac > 0` exists as legacy diagnostic | No independent AC gate is used in the current TC2 campaign path; Android momentum/flow scores are not NTD AC | **PARTIAL**. Do not equate positive AC to WM entry or add it as a TC2 gate. Keep full NTD AC pattern logic out of TC2 unless it becomes a separately approved profile | AC vector must compare AO minus rolling SMA5(AO), never a standalone positive/negative entry | Full NTD AC pattern implementation is not proven in the bot |
| **WM1 reversal bar / angulation** — WILLIAMS RULE + RESEARCH REQUIRED | TC2 ch. 10 and ch. 12, PDF pp. 139–142; the reversal bar is away from the Alligator mouth and described with “good angulation”; buy trigger is above that bar's high (mirror short) | `strategy.py` detects new local extreme/half-bar close, but legacy actionable entry is gated by an already-bullish Alligator unless countertrend setting is enabled | Main Kotlin computes a numerical Jaw-distance approximation and treats it as a gate; no author-specified numeric angulation threshold | **MISMATCH + unresolved heuristic**. Preserve the bar as a candidate, but do not claim numeric angulation is source-exact. Default must block automated WM1 execution while only approximate angulation is available; enable only via explicit opted-in test policy | WM1 trigger, structural invalidation, approximate-flag default, and no stale trigger tests | Exact deterministic author definition/threshold for “good angulation” is not established in the available scan |
| **WM2 Super AO** — WILLIAMS RULE | TC2 ch. 10, PDF pp. 136–138 / printed pp. 121–123, Fig. 10.3; third consecutive rising/falling AO histogram bar is the named event and its corresponding price bar provides a conditional trigger | Main `strategy.py` and `williams_signals.py` require a prior valid outside fractal before WM2 can be signalled | Main Kotlin also requires a prior valid buy-fractal/Teeth context for WM2 | **CONFIRMED STRATEGY ERROR**: WM2 is not itself conditional on a separate prior WM3 fractal; it can be the first valid Wise Man. Use exactly the third colour bar (not every later bar in a longer streak), then track its price trigger separately | Golden AO run with no fractal; reject already-crossed trigger; expiration and invalidation tests | Other trading-context permission may exist, but must be separate from the identity of WM2 and supported by its own contract |
| **WM3 Fractal shape and confirmation** — WILLIAMS RULE | TC2 ch. 11, PDF pp. 145–146 / printed pp. 130–131, Fig. 11.1 A–D: centre extreme with two strictly qualifying bars on each side; a tie can lengthen it to six or more bars | Main `strategy.py`, `williams_signals.py` assume exactly 2 right-side bars and do not expose the real confirmation index | Main Kotlin uses strict centre ±2 detection, so an equal adjacent extreme cannot be handled as the six-bar example | **CONFIRMED RULE MISMATCH**. Track centre and true confirmation index separately. Equal right-side highs/lows do not count; a strict new extreme invalidates the candidate; no signal before confirmation | Shared strict, tied-six-bar, cancelled-candidate and prefix/no-lookahead vectors | Source allows different observed bar shapes; exact behavior for malformed/missing candles must be fail closed |
| **WM3 Teeth gate** — WILLIAMS RULE | TC2 ch. 11, PDF pp. 148–150 / printed pp. 133–135: evaluate the red Teeth/Balance Line at activation; a buy trigger below Teeth or sell trigger above it is invalid | Main Python has a comparison to shifted Teeth, but fixed confirmation can use the wrong time row | Main Android checks a current trigger relation but does not preserve the actual extended confirmation bar/time | **PARTIAL**. Evaluate the fractal extreme relative to shifted Teeth at actual confirmation and again at trigger/activation; unknown Teeth blocks | Test for pass/fail at the confirmation candle and a changed current Teeth value | Whether bot policy should require both extreme and trigger strictly outside Teeth with a tick buffer is an execution contract; the original source’s stated balance-line gate remains authoritative |
| **First eligible Wise Man / chronology** — WILLIAMS RULE | TC2 ch. 12, PDF pp. 154–157 / printed pp. 139–142: examples and process allow WM2 before WM1/WM3; subsequent valid Wise Men can add; the campaign is chronological | `CampaignEngine.choose_initial_signal` selects earliest current signal by formation timestamp, but legacy `long_signal` requires outside fractal + Alligator + at least 2 Wise Men | Campaign path lists candidates separately; legacy scoring path can mask a single signal | **MISMATCH between entry paths**. Every independently qualified signal may start a flat campaign; later valid signals are adds only under campaign/risk limits. Do not AND all three signals or demand two simultaneously | Same bar replay with WM2 first, WM1 first, WM3 first; ensure earliest valid candidate is chosen by event chronology | Exact signal expiry is not a universal author rule and must remain an explicitly labelled implementation policy |
| **Signal cancellation / expiry** — SYSTEM SAFETY RULE + IMPLEMENTATION RULE | TC2 says a fractal stays live until activation or a newer fractal, but does not define a universal time-to-live for all signal families | Main Python checks current close vs trigger, but doesn’t consistently invalidate on intervening crossing or structural breach | Main Android campaign path has age checks only partially; no shared contract across families | **PARTIAL / SAFETY BLOCKER**: never re-arm after trigger already traded; invalidate if structural extreme is breached; require closed, fresh candles; explicit TTL is labeled implementation policy and applied consistently | Golden tests: already crossed, structural breach, expired, missing bars; persist/restore should preserve same reason | Author does not supply a single TTL for WM1/WM2/WM3; chosen TTL is not a WILLIAMS RULE |
| **Alligator/AO warm-up** — SYSTEM SAFETY RULE | NTD/TC2 formulas need 13/8/5, AO 34 plus displacement/confirmation history; the source does not define this bot’s exact device-history threshold | Some paths allow 40+ bars and configs mention a 140-bar wave window | Main Android campaign allows 45 bars; Alligator local recursive series is defined even on the first bar | **FAIL-CLOSED GAP**. The canonical signal engine requires a complete closed-candle window (minimum 140 H1 candles under the accepted bot contract) and rejects partial/warm-up NaNs | Tests with 0/33/39/139/140 closed bars and unfinished final candle | Exact seed-convergence horizon is an implementation quality threshold, not a book-authored number |
| **Stop / trail / campaign exit** — WILLIAMS RULE + SYSTEM SAFETY RULE | TC2 ch. 12, PDF pp. 154–157 / printed pp. 139–142, discusses reversal/structural stops and trailing behind recent 3–5 price bars; TC1/NTD contain other exit methods | Python campaign/monitor modules use structural stop proposals; initial WM3 stop provenance needs explicit source-to-code tracing | Android campaign WM3 uses the fractal centre low as protective price in the main path; trail default is 5 bars, while another branch has 3–5 | **PARTIAL / parity blocker**. Separate signal invalidation from initial protective stop. No invented fixed TP as TC2 core. Trail only on closed H1 bars under campaign policy; protection gap/unknown order blocks new entries | Fill/partial-fill/restart tests; prove stop origin, order path, and 3/5-bar trailing on exact H1 closes | Source descriptions show examples and a 3–5 bar range, not one universally mandatory number or exact Binance order policy |
| **Partial fills and protection** — SYSTEM SAFETY RULE | Not a William indicator rule; actual trade status is the exchange’s authority | `campaign_execution.py` / `execution_barrier.py` handle the order path (PR #44 changes these); main branch is separately audited in Task 003 | Android Runtime has OCO/reconcile paths, but Task 003 is the owning execution-safety task | **Not accepted by strategy audit alone**. No campaign is regarded protected until exchange-confirmed protective quantity covers actual fills; unknown state blocks | Partial-fill, OCO-ack-loss, stop-orphan, restart/reconcile tests on Demo/Testnet are mandatory before trading readiness | Task 004 cannot certify Task 003’s complete execution integrity |
| **MFI / four windows** — TC1 RULE, not core TC2 gate | TC1 source ch. 8 and MFI chapters (edition-specific PDF locators must be confirmed against visible page captions); original concept uses tick activity, not Binance’s base quantity by default | `strategy.py` calls `(high-low)/volume` a proxy and says diagnostic-only; current volume is Binance traded base quantity | Android trade-flow imbalance is buy/sell quote-flow, a different metric | **PARTIAL but correctly non-gating**. Keep names “proxy”, no trade entry/exit gate, no claim of equivalent source values | Tests show changes to volume proxy do not change canonical Wise-Man eligibility | Whether trade count is a useful proxy for historical tick volume remains empirical/research-only |
| **AO Saucer / Zero-line / Twin Peaks / NTD AC / TC1 methods / pyramiding ratio** — profile separation | NTD ch. 5 and TC1/TC2 distinct chapters; TC2’s AO green/red sequence does not mean NTD’s named patterns | Legacy AO-positive/AC-positive fields and MFI-window fields exist, not complete NTD independent pattern profile | Android ranking uses other momentum/flow diagnostics, not full historical profile implementation | **Do not add as compulsory TC2 gates**. Mark each missing historical strategy as a separate optional profile | Profile-isolation regression | Which optional profile should be implemented next? Owner decision required |
| **Spot vs Futures** — SYSTEM SAFETY RULE | The book’s short-side examples are not permission to create Spot short exposure | `trading_config.py` defaults `allow_short=False`; scanner imports only `extract_long_signal_specs`; legacy Python Trader submits market BUY after its boolean gate | Native Spot campaign entry is BUY; manual SELL requires an existing managed Spot position and exits/reduces it; Futures is a separate runtime | Spot invariant: SELL only reduces owned base asset; no order representing a new Spot short. The legacy direct Market BUY path still does not preserve the book’s conditional trigger and must be disabled until routed through campaign entry contract | Unit tests assert Spot signal side is BUY and SELL has an existing inventory/managed-position precondition; search/static release assertion forbids legacy signal→MARKET BUY | Futures LONG/SHORT lifecycle belongs in a separate contract/PR |
| **Python/Android parity** — IMPLEMENTATION RULE | Cross-platform parity is an implementation requirement, not a Williams rule | Main Python math is generally more source-aligned for Median Price/AO, but fixed fractal delay and WM2 gate remain wrong | Main Android has close-based/unshifted Alligator scoring, different SMMA seed and 35-bar slow AO window, plus fixed fractals | **NOT PARITY-CONFIRMED** until the same shared vectors pass both engines, and online closed-candle replays produce identical candidate identity, confirmation time, cancel/expiry reasons | Shared vector fixture consumed by Python and Kotlin tests; same signal IDs/time/index values; no lookahead/repaint checks | Bar timestamps, Binance kline close boundary and float/tick rounding must remain explicit in implementation contracts |

## 4. PR #44 overlap and non-duplication decision

PR #44 (`dbe755a942276ac3b369afd47d424fff48bbb608`) is open and not merged. Its reviewed diff already introduces reusable Python/Kotlin Alligator and Fractal helpers, true fractal confirmation indices, a WM2 detector independent of a prior fractal, WM3 Teeth revalidation, improved signal invalidation, and some stop provenance work. **That is evidence of an existing fix in a separate branch, not evidence that main is fixed.**

This task will not copy the whole PR into the task branch because its base is `audit/williams-2.1-futures-safety` and it contains unrelated Futures/risk/execution changes. The task branch instead ports only the source-to-code fixes needed by the Spot canonical path and adds an independent cross-runtime golden-vector contract. When PR #44 is merged or rebased, identical portions should be reconciled rather than mechanically duplicated.

At the time of inspection, the connector did not return useful commit-status or workflow-run entries for the PR head. No green CI claim is made from that absence.

## 5. Required acceptance gates

1. The source crosswalk above is backed by direct source locators; no repository summary is substituted for an original passage/figure.
2. All source-confirmed math/timing defects that are changed in this task are covered by regression tests at the resulting exact SHA.
3. Golden vectors under `app/src/test/resources/williams_strategy_golden_vectors.json` are the same fixture Python and Android tests read.
4. Look-ahead/repaint, actual fractal confirmation bar, Teeth-at-confirmation/current, signal invalidation and expiry are tested.
5. A lone WM1/WM2/WM3 event can be the first candidate only when it is independently qualified and its conditional price trigger has not already traded; future additions remain campaign-engine decisions.
6. M15 and M5 cannot generate canonical signals; failed H1 data requests do not fall back to another interval.
7. Spot SELL requires existing inventory; no Spot signal can create a short.
8. Unresolved angulation, seed convention, signal TTL and campaign stop provenance remain explicitly marked and block the affected automated rule where the source cannot justify a deterministic decision.
9. Full pass of Python CI + Android unit tests at the exact new commit; followed by closed-bar historical replay, cancellation/restart tests, and read-only Demo validation. The task is **not accepted** until all gates are evidenced.


## 9. TASK 004.2 — Канонический H1: повторная проверка и изменения

Проверенный базовый SHA перед этой серией изменений: `b3f6c03bccc2009730ea905be2ea75efc7d51269`. PR #49 оставался открытым Draft; base `main` — `04da5f9c09572e99ff310be3a1908179165e528c`. Ниже описаны изменения в существующей ветке; окончательный SHA и CI должны быть перепроверены после этого обновления отчёта.

### Исправления в коде

| Область | Файл/символ | Изменение | Статус доказательства |
|---|---|---|---|
| Конфигурация Python | `trading_config.py::execution_timeframe_blocker` | Добавлена явная проверка: только `EXECUTION_TIMEFRAME=1h`; M5/M15, пустое и неизвестное значение блокируются | CODE-CONFIRMED; поведенческие тесты ожидают CI |
| Preflight Python | `strategy.py::canonical_williams_environment_blockers` | Неканонический `EXECUTION_TIMEFRAME` включается в блокирующие причины | CODE-CONFIRMED; поведенческие тесты ожидают CI |
| Сканер Python | `market_scanner.py::MarketScanner._analyse_base` | Сканер блокирует кандидата, если собственный интервал или `EXECUTION_TIMEFRAME` не равен H1; проверка выполняется до обращения к market data | CODE-CONFIRMED; поведенческие тесты ожидают CI |
| Временная математика сигналов | `williams_signals.py::_timeframe_ms` | Неизвестный таймфрейм больше не превращается в M5; возвращается 0 | CODE-CONFIRMED; поведенческий тест добавлен |
| Android timeframe contract | `app/src/main/java/com/williamsbot/WilliamsTimeframeContract.kt` | В существующем коде canonical decision уже зафиксирован как `1h`; `5m` и `15m` не проходят `canCreateCanonicalSignal` | CODE-CONFIRMED; существующий Kotlin-тест проверяет H1/M15/M5 |

### Новые регрессионные проверки

- `test_market_scanner.py`: сканер не создаёт кандидата при интервале M5; блокирует сигнал при `EXECUTION_TIMEFRAME=15m`, даже если scanner interval равен H1; пустая конфигурация блокируется.
- `tests/test_williams_strategy_golden_vectors.py`: H1-only environment guard, блокировка M5/M15/пустого/неизвестного timeframe, разрешение H1/default, отсутствие скрытого M5 fallback в `_timeframe_ms`.
- Существующий Android-тест `WilliamsStrategyGoldenVectorsTest.onlyH1CanCreateCanonicalCampaignSignals` проверяет только контрактный helper. Он сам по себе не доказывает, что каждый legacy/persisted/runtime execution path подчиняется этому контракту.

### Оставшиеся критические проверки

1. `TradingConfig.from_env` хранит переданное значение таймфрейма, даже когда helper выдаёт блокировку. Все production entry points должны обязательно применять блокирующий результат до допуска к сигналам; одного helper недостаточно.
2. Python scanner получает интервал через `INTERVAL`, а config — через `EXECUTION_TIMEFRAME`. Теперь несоответствие блокируется в `_analyse_base`, но требуется CI и проверка всех иных путей запуска/сканеров.
3. Android campaign path фиксирует H1 и при ошибке запроса H1 возвращает пустые свечи/состояние без сигнала. При этом в `StandaloneRuntime.kt` остаются MTF/legacy wave-analysis участки с M5/M15. Не доказано, что каждый такой участок не может влиять на путь исполнения через косвенные данные; нужен call-graph до фактического order intent/execution gate.
4. `ExecutionGate.kt` резервирует символ и не валидирует timeframe. Доказательство H1-only на фактическом execution admission path не завершено; требуется проверить, что в него невозможно передать сигнал, сформированный вне canonical H1 пути.
5. Persisted preferences/restart, release/preflight gates и все launch profiles должны быть проверены end-to-end. Не заявляется, что они уже все доказаны.
6. Полный WM1/WM2/WM3 replay, cross-runtime behavioural parity, source-page audit и безопасный жизненный цикл исполнения остаются отдельными P0/P1/P2 блокерами.

**Текущий статус задачи: IN PROGRESS — NOT APPROVED.** Указанные изменения — реализация fail-closed защиты H1, но не доказательство полного отсутствия M5/M15 влияния на все execution paths. Успешный CI не заменяет call-graph, replay и первоисточниковый аудит.


## 10. TASK 004.3 — Execution Barrier H1 admission contract

**Status: IN PROGRESS — NOT APPROVED.** This section records the current implementation delta and the remaining proof obligations. It does not claim universal H1-only execution across Python and Android.

### IMPLEMENTED (code inspection at this report revision)

- `execution_barrier.py :: OrderIntent` now carries immutable entry-signal provenance fields: `signal_timeframe`, `signal_bar_time_ms`, `confirmation_time_ms`, and `expires_at_ms`.
- `ExecutionBarrier._validate()` applies an additional gate only to `purpose == CAMPAIGN_ENTRY`: runtime `EXECUTION_TIMEFRAME` must be H1, both signal timeframe and permission interval must be H1, signal-bar time must be positive, confirmation must follow the signal-bar time, and expiry must be in the future.
- `CampaignExecutionService.arm_initial_entry()` applies the runtime config/timeframe/timestamp/expiry/protective-reference checks before persisting a campaign, then copies provenance into `OrderIntent`. This is an early rejection plus a separate last-mile barrier check.
- `test_campaign_execution.py` adds fake-exchange tests asserting no submit callback is called for M5/M15/empty signal timeframes, expired entries, and H1 signals under M5 runtime config.

### TESTED

- Earlier Python backend run on SHA `195630b40a519f6066a8be1c87820b4e1fb0c46d` completed successfully, but it predates the TASK 004.3 barrier changes and therefore does **not** validate these latest edits.
- The new tests have been committed but still require a completed CI run on the final SHA. Do not treat an in-flight run as proof.

### NOT VERIFIED / BLOCKED

1. **Source candle and structural revalidation at the last-mile boundary:** the barrier currently validates time-frame provenance and time fields, but it does not independently fetch and prove the source candle, confirmation candle, and later structural invalidation state for every WM1/WM2/WM3 entry. The existing scanner/extractor checks are not sufficient proof against direct-call or stale-SQLite bypass.
2. **All signal creation paths:** exhaustive repository-wide inventory of `SignalSpec`, `PendingSignal`, campaign creation, order intents, and exchange mutation callsites is still incomplete.
3. **Android actual mutation boundary:** `WilliamsTimeframeContract` fixes the campaign timeframe to H1 and the legacy `submitOrderIntent()` path begins with an unconditional error, but the full persisted-pending-entry/recovery path and every `signedPost`/cancel-replace entry mutation have not yet been proven to revalidate timeframe, expiry, source candle, and structural invalidation at the last admission point. `ExecutionGate` itself is only a symbol reservation gate, not a timeframe contract.
4. **Restart/persisted state:** Android pending entries and preferences require explicit tests that mutate stored state, restart the runtime, and prove invalid M5/M15/unknown or stale signals cannot reach the order mutation callback. This is not yet behaviour-confirmed.
5. **Cross-runtime parity/replay:** shared vectors for indicator arithmetic and fractal timing exist, but end-to-end Python/Android admission parity and future-candle mutation replay have not been completed for all fields requested in TASK 004.3.
6. **WM1/WM2/WM3 source audit:** qualitative WM1 angulation remains unresolved and blocked for automated use unless the explicitly marked research override is enabled. Other source-vs-code questions in sections above remain open where marked RESEARCH REQUIRED.

### Acceptance matrix (current evidence)

| Scenario | Current evidence | Status |
|---|---|---|
| H1 signal + H1 config | Barrier permits it to continue to independent checks if timestamps/expiry are valid | PARTIAL — final execution tests pending |
| M5/M15/empty/unknown signal timeframe | Python barrier rejects campaign entry before submit callback | IMPLEMENTED — CI pending |
| H1 signal + M5/M15/unknown runtime config | Python barrier rejects campaign entry before submit callback | IMPLEMENTED — CI pending |
| M5/M15 data for monitoring/diagnostics | Separate from canonical signal contract | NOT VERIFIED end-to-end |
| H1 data fetch failure without lower-TF fallback | Existing code claims fail-closed H1 campaign fetch; full all-path proof pending | PARTIAL |
| Old SQLite/persisted signal revalidated after restart | Expiry metadata exists, but universal last-mile structural/timeframe proof missing | BLOCKED |
| Direct signal injection bypassing scanner | Python campaign barrier blocks invalid timeframe, but source-candle/structural validation remains incomplete; Android universal boundary not proven | BLOCKED |
| Safe exit/protection remains available | New Python checks are scoped to `CAMPAIGN_ENTRY`; no intended exit gate was added | CODE-CONFIRMED only |

**Next mandatory step:** run all required workflows against the final SHA after this report commit, inspect individual jobs, and continue the Android execution-path audit. TASK 004.3 remains **IN PROGRESS — NOT APPROVED** until the blocked rows have behavioural evidence on both runtimes.


## 11. TASK 004.4 — End-to-End H1 Execution Contract

**Status: IN PROGRESS — NOT APPROVED.** Source revision inspected before this section: `f8c61013225d95640c870139058aadd86c098fc4`. The report commit itself will advance HEAD; CI must be checked again against that final report commit. Base `main` remains `04da5f9c09572e99ff310be3a1908179165e528c`; PR #49 remains open Draft and unmerged.

### IMPLEMENTED

#### Python

- `execution_barrier.py :: ExecutionBarrier._validate / execute`
  - Entry-like purposes now include `ENTRY`, `CAMPAIGN_ENTRY`, `ADD_ON`, and `CAMPAIGN_ADD_ON`.
  - Each must use runtime H1 configuration, H1 signal timeframe and H1 permission interval, have valid epoch-millisecond source/confirmation/expiry timestamps, a confirmation no earlier than source-H1 close, a non-future confirmation, and non-empty signal/campaign IDs.
  - Entry-like intents without a last-mile validation callback are blocked before the submit callback.
  - Exit/protection/cancel purposes are not subjected to the entry timeframe gate.
- `campaign_execution.py :: CampaignExecutionService.arm_initial_entry / arm_add_on`
  - Add-on OrderIntent now carries the same signal provenance as initial entry.
  - `_validate_h1_signal_market_state()` independently requests H1 candles immediately before entry submission; rejects unavailable/malformed history, missing or unclosed source candle, stale latest closed H1 candle, invalid source OHLC/trigger/invalidation, premature/future confirmation and later closed H1 structural invalidation.
  - WM3 Teeth validation remains an additional strategy-specific check.
- `mock_exchange.py`
  - Adds deterministic synthetic klines for isolated tests; no external exchange is contacted by these tests.
- `test_campaign_execution.py`
  - Adds tests for entry aliases/add-ons, no callback, H1 config mismatch, non-H1 signal, expiry, missing source candle, structural invalidation, no submit callback, and preserving the campaign exit path.

#### Android

- `WilliamsTimeframeContract.kt`
  - Adds deterministic fail-closed provenance validation for timeframe, source/confirmation/expiry timestamps, symbol/signal/campaign identity and trigger/invalidation values.
- `StandaloneRuntime.kt`
  - `CampaignSignalN` now carries explicit timeframe; WM1/WM2/WM3 candidates record the timeframe used to calculate them.
  - `PendingEntry` persists `signalTimeframe`; old persisted entries lacking it restore with an empty value and are treated as invalid.
  - `withCampaignMutation()` applies last-mile validation only to `CAMPAIGN_ENTRY` and `CAMPAIGN_ADD_ON`, including H1 metadata, fresh market data, a fresh closed H1 history, source candle presence, structural invalidation and trigger-not-crossed checks.
  - Pending-entry recovery rejects unknown/non-H1 timeframe or invalid temporal provenance and routes it through cancellation/reconciliation. Cancellation, protection, trail replacement, and exit purposes do not inherit the new-entry timeframe gate.
- `WilliamsStrategyGoldenVectorsTest.kt`
  - Adds Android tests for H1 valid metadata and rejection of M5, M15, empty/unknown timeframe, expired/future/invalid timestamps, missing identifiers and invalid price fields.

### Production-path inventory (current evidence, not an assertion that every path is closed)

| Path / entry point | Timeframe or provenance check | Result / remaining gap |
|---|---|---|
| `market_scanner.py :: MarketScanner._analyse_base` | H1 scanner interval and `EXECUTION_TIMEFRAME` checks | IMPLEMENTED; all callers/alternate launch profiles still require full call-graph proof |
| `market_scanner.py :: SignalSpec construction` and `williams_signals.py` | Signal timeframe and event timestamps flow into signal spec | PARTIAL; source-candle provenance is now rechecked for Python campaign entry, but every construction callsite is not yet exhaustively covered |
| `campaign_execution.py :: arm_initial_entry` | H1 config, H1 signal, timestamps, expiry, protection; final callback checks H1 source candle/freshness/structure | IMPLEMENTED; tests pending on final SHA |
| `campaign_execution.py :: arm_add_on` → `OrderIntent(purpose=CAMPAIGN_ADD_ON)` | Now carries H1 provenance; ExecutionBarrier gates add-on aliases too | IMPLEMENTED; universal replay and used-signal dedupe proof remain incomplete |
| `trader.py` legacy `OrderIntent(purpose=ENTRY)` | New barrier rejects absent/non-H1 provenance and missing last-mile callback | CODE-CONFIRMED; every live caller and no-op callback misuse not behaviour-proven |
| `portfolio_trader.py` recovery, campaign selection and legacy signal paths | Routes include recovery, SignalSpec reconstruction and `arm_initial_entry` | PARTIAL; restored-signal provenance and all branches need targeted tests |
| `ExecutionBarrier.execute` → submit callback | Final H1/provenance gate plus mandatory callback for entry-like purpose | IMPLEMENTED; callback identity is not cryptographically bound to a validator; a caller supplying a fake no-op callback remains an architectural concern |
| Android `submitCampaignEntry` → `withCampaignMutation("CAMPAIGN_ENTRY")` → `signedPost` | Pending signal H1/provenance, fresh H1 candle history, invalidation and trigger checks | IMPLEMENTED in code; mutation-free Android integration test is still NOT VERIFIED |
| Android `submitCampaignAddOn` → `withCampaignMutation("CAMPAIGN_ADD_ON")` → `signedPost` | Same persisted H1 admission gate | IMPLEMENTED in code; mutation-free Android integration test is still NOT VERIFIED |
| Android `recoverCampaignPendingEntries` | Rechecks timeframe, timestamps, expiry and structural invalidation; unknown data cancels/reconciles | PARTIAL; complete process-restart tests with mutated SharedPreferences remain NOT VERIFIED |
| Android legacy `submitOrderIntent` / `executeBuyWithProtection` | Function begins with unconditional error that disables legacy entry route | CODE-CONFIRMED by inspection, but runtime reachability test is NOT VERIFIED |
| Android `signedPost` / `signedDelete` / `signedCancelReplace` | Called by campaign entry/add-on, protection/trailing, cancel/recovery and exit/emergency paths | Calls were inventoried by caller. Generic signed wrappers are not globally H1-gated by design; correctness depends on each new BUY path going through the purpose-specific admission gate. Exhaustive runtime reachability is NOT VERIFIED |
| Android protection resize, cancel, recovery and SELL exit | Separate operation purposes; not subjected to entry H1 gate | CODE-CONFIRMED intent; end-to-end safety tests still required |

### TESTED / CI

- New Python tests include synthetic H1 source-candle absence and structural invalidation with an assertion that `MockExchange.orders` remains empty.
- Android JVM tests exercise the same deterministic H1 metadata contract.
- At the time this report section was drafted, workflows on `f8c61013225d95640c870139058aadd86c098fc4` were queued/in progress. No workflow result from an earlier SHA is inherited as proof. Final-sha CI links/results must be appended after this report commit and after every required job completes.

### NOT VERIFIED / BLOCKED

1. **Used-signal idempotency across a completed campaign and process restart:** order client IDs and pending reservations exist, but a global durable consumed-signal ledger is not proven across every entry path.
2. **Android mutation-free integration tests:** pure provenance vectors pass/fail deterministically, but tests that inject a bad persisted PendingEntry and assert the actual signed request/order mutation is never invoked are still missing.
3. **Persisted preference tampering/restart:** missing timeframe is fail-closed by default, but all malformed/tampered JSON and restart/reconciliation permutations are not yet tested.
4. **Full Python↔Android golden replay:** the two runtimes share indicator/fractal vectors and now test the Android provenance contract; they do not yet share all end-to-end decisions/reasons/action types for stale market data, source candle missing, recovery, duplicate signal and safe exit.
5. **Look-ahead replay:** no final automated sequential replay yet proves that changing only future candles cannot alter previously confirmed decisions across all WM1/WM2/WM3 routes.
6. **All dynamic mutation callsites:** the caller inventory is based on repository source inspection. No exhaustive runtime instrumentation trace proves every dynamically formed `signedPost`/cancel-replace route and every Python order-client mutation is reachable only through the expected gate.
7. **CI:** pending/queued/in-progress jobs are not success. Task remains blocked until Python, Campaign CI, Android APK, lint/unit tests and golden replay complete on the exact final report SHA.

**Acceptance decision:** H1 admission has been strengthened on both runtimes, but TASK 004.4 is **NOT ACCEPTED**. Do not mark the PR ready, merge it, or enable live trading on the basis of these changes alone.


### Изменённые файлы TASK 004.4

- `execution_barrier.py`
- `campaign_execution.py`
- `mock_exchange.py`
- `test_campaign_execution.py`
- `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`
- `app/src/main/java/com/williamsbot/WilliamsTimeframeContract.kt`
- `app/src/test/java/com/williamsbot/WilliamsStrategyGoldenVectorsTest.kt`
- `audit/williams-2.1-task-004-williams-strategy/REPORT_RU.md`

### CI run links recorded before this report revision

These runs target code SHA `c7d868130839eb87db3bb6da076042a86810b7cd`; they are included for traceability only and are **not** proof for the new report commit SHA:

- [Python backend tests — c7d8681](https://github.com/19wolfalone92/Williams/actions/runs/38088806286)
- [Williams Campaign CI — c7d8681](https://github.com/19wolfalone92/Williams/actions/runs/38088806287)
- [Build Android APK — c7d8681](https://github.com/19wolfalone92/Williams/actions/runs/38088806293)

A fresh set of workflows is expected for the final report commit. Acceptance requires checking each job on that exact final SHA; queued, pending, running, cancelled, or failed jobs do not count as success.


### TASK 004.4 revision update

Implementation revision after the first report draft: `2a6fa25b2ded43a2d31ac3e0690f69d9a314342c`. This includes the correction to synthetic kline shape/UTC candle alignment, the explicit structural-invalidation comparison, and the legacy OCO recovery fixture change described below.

Additional changed files:
- `recovery_oco_failure_test.py` — OCO recovery test now seeds a synthetic pre-existing fill directly in FakeClient instead of invoking a new BUY through the legacy entry route without a signal. This preserves the recovery test's purpose while keeping the production H1 gate fail-closed.
- `test_production_architecture.py` — barrier tests now use complete H1 signal provenance and a last-mile callback, so they test stale-context/serialization behavior after satisfying the new entry contract.

CI evidence during development:
- Python backend tests passed on `cd74f1fe6e577a1fe1bec021808f8b5f88943dfa`: [run 38088931777](https://github.com/19wolfalone92/Williams/actions/runs/38088931777).
- Williams Campaign CI on that SHA failed in campaign tests: the synthetic mock initially returned 7-column klines, which did not match the 12-column Binance response contract; a structural invalidation assertion also exposed an insufficiently explicit timestamp comparison. Both were corrected in later commits (`86c78b42` and `2a6fa25b`).
- An earlier backend run failed during collection because a legacy OCO recovery fixture called `Trader.market_buy()` without signal provenance. The fixture now seeds a fake prior fill without invoking production entry admission (`a7936638`).
- These are intermediate results only. They do not establish CI success on the final report SHA. The latest code and report commit must pass all required workflows before any acceptance decision.

**Final evidence still outstanding:** the final SHA CI set, Android mutation-free integration tests, persisted-preference restart permutations, consumed-signal durable idempotency, full Python↔Android end-to-end replay and future-candle mutation replay.


### Final TASK 004.4 verification note

Latest implementation revision before this note: `19b5b1c880aae7138b41fdaf1a95824e80719671`.

Additional changed test files:
- `test_production_architecture.py` — entry barrier unit tests now provide explicit H1 signal provenance and last-mile callback.
- `recovery_oco_failure_test.py` — seeds a fake already-filled order for OCO recovery, rather than attempting a fresh BUY without a canonical signal.
- `app/src/test/java/com/williamsbot/WilliamsStrategyGoldenVectorsTest.kt` — provenance test was moved inside the JUnit test class after Android unit-test discovery reported an `InvalidTestClassError`.

Latest visible Campaign CI at this implementation revision: [run 38089129878](https://github.com/19wolfalone92/Williams/actions/runs/38089129878) — queued when checked. The Python backend workflow passed on earlier revision `cd74f1fe6e577a1fe1bec021808f8b5f88943dfa` ([run 38088931777](https://github.com/19wolfalone92/Williams/actions/runs/38088931777)); it does not cover the later synthetic-kline and structural-invalidation corrections. Prior Android unit-test failure was an `InvalidTestClassError` due to the provenance test being outside the class; the test was moved, but a successful rerun is not yet evidenced.

**This note is not an acceptance sign-off.** The report commit changes HEAD again and triggers fresh CI. TASK 004.4 remains blocked until the final SHA's Python backend, Campaign CI, Android APK, Android unit/static checks all complete successfully and the remaining behavioural gaps are addressed.


### Verification refresh — SHA `154bff80751bbe3b975dc5dc8e3503bb289edb2e`

This refresh supersedes earlier statements above that these required workflows were still pending. At the time of this verification, branch HEAD and PR #49 head both resolved to `154bff80751bbe3b975dc5dc8e3503bb289edb2e`; base `main` remained `04da5f9c09572e99ff310be3a1908179165e528c`. PR #49 was open, Draft, and unmerged.

Required workflow outcomes on that exact SHA:
- Python backend tests: **SUCCESS**, run [38089187193](https://github.com/19wolfalone92/Williams/actions/runs/38089187193), job `backend-test`.
- Williams Campaign CI: **SUCCESS**, run [38089187219](https://github.com/19wolfalone92/Williams/actions/runs/38089187219), jobs `Python tests` and `Android build and static checks`.
- Build Android APK: **SUCCESS**, run [38089187276](https://github.com/19wolfalone92/Williams/actions/runs/38089187276), job `Build APKs`; Android lint, unit tests, debug/release APK build and APK validation steps completed successfully.

The compare against base `04da5f9c09572e99ff310be3a1908179165e528c` reports 123 commits ahead and 0 behind. No change was made to `main`.

Passing CI on this SHA verifies the configured jobs only; it does **not** close the behavioural gaps below. Acceptance remains **IN PROGRESS — NOT APPROVED** because:
1. No Android end-to-end mutation-spy test yet proves that direct/legacy entry invocation with invalid persisted signal never reaches the actual signed order mutation.
2. Persisted-preference corruption and process-restart permutations are not exhaustively tested.
3. Durable consumed-signal idempotency across all initial-entry/add-on/recovery paths is not established.
4. Python and Android do not yet execute one common end-to-end replay corpus comparing decision, rejection reason and allowed action for every requested case.
5. A sequential future-candle mutation replay proving no historical decision changes has not been completed.
6. Dynamic order-mutation callsites have source-level inventory, but exhaustive runtime instrumentation evidence for every production route is not available.

This section records the passing CI for SHA `154bff8` specifically. If this report is edited again, the resulting new HEAD requires its own fresh CI run; success on `154bff8` must not be attributed to a later SHA.


---

# TASK 004.5 — H1 mutation-path and durable-idempotency audit (initial evidence, 2026-10-11)

## Repository guard verification

- Actual branch HEAD at start of TASK 004.5: `78e713140acf28b90077df87976de530e279c14c`.
- PR #49 head: `78e713140acf28b90077df87976de530e279c14c`; PR remains OPEN, Draft, unmerged.
- Actual PR base and `main`: `04da5f9c09572e99ff310be3a1908179165e528c`.
- No write to `main`, no PR merge, and no exchange order was submitted as part of this audit.
- Required workflows on the starting SHA were successful: Python backend tests run 38090876313; Williams Campaign CI run 38090876307; Build Android APK run 38090876283. These are baseline runs only and do not verify any future commit.

## Findings from direct source inspection

### Python mutation path inventory (source-level; not yet complete behavioural proof)

| Path | Source / validation | Last-mile barrier and mutation sink | Current assessment |
|---|---|---|---|
| Initial conditional campaign entry | `CampaignExecutionService.arm_initial_entry` in `campaign_execution.py`; H1 history/source candle/freshness/trigger/invalidation checks in `_validate_h1_signal_market_state`; pending campaign and order intent are persisted before submit | `_submit` → `ExecutionBarrier.execute` → supplied callback to `client.order_safe`; database claim currently uses `entry_client_order_id:<symbol>`, not a durable consumed-signal key | H1 path exists; durable per-signal idempotency UNRESOLVED |
| Add-on | Add-on arm/validation path in `campaign_execution.py`; checks existing campaign signal state and calls `try_claim_state(entry_client_order_id:<symbol>, cid)` | `_submit` → `ExecutionBarrier.execute` → `client.order_safe` | Per-symbol pending-order exclusion exists; proof that one signal cannot be reused across campaign/initial/add-on/restart is UNRESOLVED |
| Generic/legacy trader entry | `trader.py:1057-1073` includes a pre-submit callback and `client.order(...)` submit callback; barrier is instantiated at `trader.py:47` | Must be traced through all caller aliases and purpose values; this path is not yet proven to require immutable campaign signal provenance in every invocation | UNRESOLVED |
| Campaign cancel/exit/stop replacement/protection | `campaign_execution.py` uses `_submit` at source lines 1423, 1469-1471, 1561, 1675 (cancel callback includes `client.cancel_order`) | Canonical barrier is invoked, but full purpose-by-purpose contract proof is outstanding | UNRESOLVED until action-specific integration tests prove safe exits/protection remain available |
| Durable database primitives | `db.py` has SQLite `try_claim_state` (atomic `INSERT OR IGNORE`) and campaign-signal table primary key `signal_id` | Existing initial/add-on code claims a per-symbol pending key; `ExecutionBarrier._persist` catches and ignores persistence exceptions, and `execute` also swallows execution-event write failures | **P0 risk**: barrier persistence failure is not a fail-closed admission condition; must fix and test before approval |

### Android mutation sink inventory (source-level; direct mutation-spy proof not yet implemented)

| Path | Source / validation | Mutation sink | Current assessment |
|---|---|---|---|
| Canonical initial campaign entry | `StandaloneRuntime.kt` `submitCampaignEntry`; `validateNewCampaignEntryAtMutation` checks H1 provenance, source candle, freshness and structural invalidation; `withCampaignMutation` checks runtime/recovery/kill state and reserves `ExecutionGate` | `signedPost("/api/v3/order", ... STOP_LOSS ...)` at approximately line 2801 | Gate exists, but no mutation-spy integration test proves every rejection leaves the sink untouched |
| Add-on | `submitCampaignAddOn`; persisted `PendingEntry`, same H1 mutation validation and campaign mutation gate | `signedPost("/api/v3/order", ... STOP_LOSS ...)` around lines 3834-3835 | **P0 idempotency gap**: generated client order ID contains `System.nanoTime()`; no durable consumed-signal uniqueness was proven across process restart/requeue |
| Campaign protective stop | `createCampaignStop` through `withCampaignMutation` | `signedPost("/api/v3/order", ... STOP_LOSS ...)` | Must remain available when new-entry H1 contract blocks entries; integration proof outstanding |
| Emergency exit after protection failure | Campaign fill/protection recovery path; `withCampaignMutation(..., "CAMPAIGN_EMERGENCY_EXIT")` | `signedPost("/api/v3/order", ... MARKET SELL ...)` around lines 3494-3495 | Safety-critical exception path; must be tested against explicit exit contract and unknown exchange state |
| Legacy/manual sell | `sell(symbol)`; managed campaign exits use `campaignExitMarket`; non-campaign path validates managed OCO identifiers and reconciles balances | `signedDelete("/api/v3/orderList")` and `signedPost("/api/v3/order", ... MARKET SELL ...)` around lines 6475-6536 | H1 entry block is not meant to disable exits; actual path still needs mutation-spy and duplicate-exit tests |
| Pending order cancellation / restart recovery | `kill`, `recover`, `recoverPendingEntries`, and campaign pending-entry recovery | `signedDelete("/api/v3/order")`, `signedDelete("/api/v3/orderList")`, and order lookup/reconciliation; cancel-replace funnels through `signedPost("/api/v3/order/cancelReplace")` | Full call graph and sink interception not yet complete |
| Shared network mutation layer | `signedPost` delegates to `signedRequest("POST", ...)`; `signedDelete` delegates to the same signed request mechanism | This is the common HTTP mutation sink family, but a test-injectable transport/mutation observer is not currently proven | UNRESOLVED |

### Cross-platform idempotency and recovery assessment

- Python SQLite already has durable primitives, but current claims are primarily per-symbol pending-entry reservations, not a demonstrated, stable, globally consumed signal identity across all initial/add-on/legacy/recovery paths. A per-symbol lock is not equivalent to signal idempotency.
- Android `ExecutionGate` uses an in-memory synchronized set. It prevents concurrent reservations in one runtime instance, but by itself cannot prove exclusivity across runtime restart or independent processes. Persisted `PendingEntry` state is useful recovery evidence, not proof that a signal remains valid.
- Android client IDs containing `System.nanoTime()` are unique per attempt but are not stable identity for replaying the same signal. On an ambiguous POST result, the code does query by the attempt's client ID and requests reconciliation if no order is observed; cross-restart re-presentation still needs a durable signal-level guard.
- Python `ExecutionBarrier._persist` currently catches all exceptions from `save_execution_intent` and ignores them. It also swallows `save_execution_event` failures. Thus the implementation does not yet prove the required invariant “persistence failure cannot authorize mutation.”
- These findings are source-inspection results, not claims that the listed negative scenarios have passed integration tests.

## TASK 004.5 acceptance matrix at this checkpoint

| Requirement | Status | Evidence / remaining work |
|---|---|---|
| Complete Python production mutation path inventory | UNRESOLVED | Initial entry/add-on/generic trader/cancel paths identified; every caller and legacy alias still needs exhaustive tracing |
| Complete Android production mutation path inventory | UNRESOLVED | Major order, cancel, cancel-replace, OCO, stop and sell sinks located; exhaustive dynamic call-path trace remains |
| Android mutation-spy negative and positive integration tests | UNRESOLVED | No sink-count integration suite has yet been run/evidenced |
| Durable signal idempotency and two-connection/process race | UNRESOLVED | Existing per-symbol SQLite claim does not establish all requested per-signal invariants; Android guard is process-local |
| Persisted preferences/restart matrix | UNRESOLVED | Malformed/missing fields and unknown previous POST outcomes require systematic matrix tests |
| Shared Python↔Android golden replay | UNRESOLVED | Existing strategy golden vectors do not yet prove all requested execution decisions/reasons/actions from one shared fixture |
| Future-candle causal replay | UNRESOLVED | Not yet evidenced for signal formation, fractal confirmation, Alligator indexing, or restored signals |
| Exit/protection/recovery under entry block | UNRESOLVED | Source paths found; sink-spy integration proof still required |
| Migration/concurrency tests | UNRESOLVED | Existing SQLite schema has campaign tables and unique IDs, but migration preservation and two independent connection/process race tests for signal consumption are not yet evidenced |
| Physical Android device | NOT VERIFIED | No device-run evidence supplied or collected |
| Binance Spot Testnet | NOT VERIFIED | No Testnet execution performed as part of this audit |

**Decision: IN PROGRESS — NOT APPROVED.** The present evidence is intentionally an initial TASK 004.5 audit checkpoint. Green baseline CI does not close any of the outstanding behavioural or concurrency blockers. No claim is made that the full Bill Williams strategy is implemented or that this branch is ready for live trading.
