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


---

# TASK 004.6 — Fail-closed persistence and signal replay guard (interim)

## Repository state and scope

- TASK 004.6 began from the verified branch head `b1f39cd043d0310593900c447d403740fc9ebaf9`.
- The working branch remains `audit/williams-2.1-task-004-williams-strategy`; PR #49 remains OPEN, Draft, unmerged; base/main remains `04da5f9c09572e99ff310be3a1908179165e528c`.
- No Mainnet access or real exchange orders were used.
- This section records code changes and tests added. It does not claim all P0 acceptance criteria are met.

## Changes implemented in this checkpoint

### Python — `execution_barrier.py`

1. `_persist()` no longer swallows exceptions from `save_execution_intent`. When a DB is configured but the required writer is unavailable, or a write/commit raises, execution stops with an explicit persistence error.
2. Added `_persist_event_required()` for critical execution events. `ADMISSION_STARTED` and `SUBMIT_ATTEMPT_STARTED` must persist before the submit callback can be invoked.
3. Added a durable entry-signal claim using the existing SQLite `try_claim_state()` primitive and key `consumed_signal:<signal_id>`. It runs after read-only admission checks and before the network boundary. An existing claim blocks replay across barrier/runtime instances. The claim is deliberately conservative: a crash or persistence ambiguity after reservation may require reconciliation/manual resolution; it does not free the signal for blind retry.
4. The barrier persists `SUBMITTING` and `SUBMIT_ATTEMPT_STARTED` before the network callback. This is the durable boundary: after restart, that state must be treated as potentially sent unless exchange reconciliation proves otherwise.
5. If persistence of the post-submit status/event fails, the caller receives a reconciliation-required exception. The code attempts to record `AMBIGUOUS`; if SQLite remains unavailable, it does not claim the local outcome is known.
6. Noncritical diagnostic `log_event` failures remain best-effort. They are distinct from required execution-intent and execution-event writes.

### Python tests added — `test_production_architecture.py`

- `test_execution_barrier_required_intent_persistence_failure_never_calls_submit`: injects a required-intent write/commit failure and asserts the network callback count is zero.
- `test_execution_barrier_required_admission_event_failure_never_calls_submit`: injects an admission-event failure and asserts the network callback count is zero.
- `test_execution_barrier_durably_blocks_replayed_signal_before_second_mutation`: uses a shared durable-claim test double across two barrier instances; asserts the second replay cannot invoke the callback.

These are focused regression tests. They do **not** yet replace required tests using real SQLite, two independent OS processes, commit/busy/corruption fault injection, or the full campaign execution path.

## Important limits / remaining P0 blockers

- `SignalSpec.new()` currently derives its deterministic ID from symbol, timeframe, signal type and source-bar time. The ID does not include side or an explicit strategy-contract version. Conflicting canonical payloads are conservatively blocked when they reuse an ID, but the canonical identity contract is not yet cross-platform verified.
- The existing SQLite `try_claim_state()` primitive is atomic at the database insert/commit boundary, but a two-independent-process test using the same on-disk DB has not yet been run. Transaction rollback, busy/locked, commit-failure and process-kill windows remain unverified.
- Android has not yet been changed to persist the same signal-claim contract before its actual signed mutation sink. `ExecutionGate` alone is not durable idempotency proof.
- No Android mutation-spy suite has yet demonstrated zero calls for all negative entry vectors and allowed safe exits.
- No shared Python/Android golden execution fixture has yet compared signal ID, reason, recovery state and action.
- No complete causal replay has yet demonstrated invariance under future-candle mutation.
- Exit/protection/recovery sink tests, SQLite migration matrix, physical-device process-death tests, and Binance Spot Testnet checks remain unverified.

## TASK 004.6 interim acceptance matrix

| Criterion | Status | Evidence |
|---|---|---|
| Required intent persistence failure blocks submit callback | IMPLEMENTED — CI pending | Added regression test with observed callback count |
| Required admission event failure blocks submit callback | IMPLEMENTED — CI pending | Added regression test with observed callback count |
| Durable signal replay guard in Python barrier | IMPLEMENTED — partial | Existing `try_claim_state()` used; test double covers replay across barrier instances |
| Real SQLite + two independent process concurrency | UNRESOLVED | Not yet run |
| Full Python entry-path and legacy-path closure | UNRESOLVED | Requires exhaustive call-path tests |
| Android mutation-spy and durable gate | UNRESOLVED | No complete sink-level proof yet |
| Python↔Android golden replay | UNRESOLVED | Not yet implemented |
| Causal replay / no look-ahead | UNRESOLVED | Not yet implemented |
| Exit/protection/recovery under failures | UNRESOLVED | No complete sink-level proof yet |
| SQLite migration and failure-window matrix | UNRESOLVED | Not yet run |
| Physical Android | NOT VERIFIED | No device evidence |
| Binance Spot Testnet | NOT VERIFIED | No Testnet order tests performed |

**Decision: IN PROGRESS — NOT APPROVED.** The new Python barrier changes are a partial safety improvement, not completion of TASK 004.6 and not proof of full Williams strategy parity.


---

# TASK 004.7 — Cross-platform durable idempotency and recovery (interim checkpoint)

## Verified repository baseline

- TASK 004.7 start SHA supplied by the previous checkpoint: `982840a5f81d0c3eac7eb6e026b8ce2e8bb5f364`. This was confirmed as the branch HEAD before TASK 004.7 changes.
- At baseline, Python backend tests run [38115797177](https://github.com/19wolfalone92/Williams/actions/runs/38115797177), Williams Campaign CI run [38115797174](https://github.com/19wolfalone92/Williams/actions/runs/38115797174), and Build Android APK run [38115797222](https://github.com/19wolfalone92/Williams/actions/runs/38115797222) all completed SUCCESS on exactly that SHA.
- PR [#49](https://github.com/19wolfalone92/Williams/pull/49) remains OPEN, Draft, unmerged. Base/main is `04da5f9c09572e99ff310be3a1908179165e528c`; `main` was not modified.
- These CI results validate the TASK 004.6 baseline only. They are not evidence for TASK 004.7 changes below.

## Changes made in this checkpoint

- `db.py`: added `Database.claim_signal_once(signal_id, canonical_payload)`. It uses SQLite `BEGIN IMMEDIATE`, a unique `bot_state.key` primary key and `INSERT OR IGNORE`, compares the persisted canonical payload, commits, and performs a post-commit read-back before returning `CLAIMED`. It returns `DUPLICATE` for a matching prior claim and `CONFLICT` when the same signal ID is presented with different canonical data. SQLite, commit, or read-back errors raise and therefore must block the caller.
- `execution_barrier.py`: production `Database` instances now use the canonical claim method. The currently available canonical fields are symbol, side, signal timeframe, source-bar timestamp, confirmation timestamp, campaign ID and execution purpose. Missing durable DB or missing signal ID blocks entry mutation.
- `test_signal_claim_processes.py`: added independent OS-process tests against one temporary on-disk SQLite DB, restart persistence with a new attempt identity, and same-ID/different-payload conflict detection.
- `.github/workflows/python-ci.yml` and `.github/workflows/campaign-ci.yml`: included the new process tests in both Python CI suites.

## Concurrency and transaction interpretation

The claim relies on SQLite's database-level write serialization and the primary-key constraint, not an in-memory lock shared between workers. `BEGIN IMMEDIATE` serializes competing writers; only one process can insert the key. A duplicate or conflicting canonical payload is denied. If the operation raises due to `SQLITE_BUSY`, `SQLITE_LOCKED`, commit failure, or read-back failure, the barrier does not receive `CLAIMED` and must not call the network callback. If a commit succeeded but the subsequent read-back failed, the claim may remain consumed; this is intentionally conservative and does not authorize a retry.

The newly added tests cover two independently spawned OS processes racing for one signal, persistence after closing/reopening the DB, and canonical conflict detection. They do **not** yet cover all ten requested fault-injection windows (especially deterministic commit failure, post-commit read-back failure, process kill at every boundary, or forced SQLITE_BUSY/LOCKED recovery). Those remain open P0 verification items until CI and dedicated fault-injection tests prove them.

## Canonical identity — incomplete

The canonical payload currently captures the fields that are present on `OrderIntent`, but the contract is not yet complete: explicit strategy/rule-set version, input dataset/version, and a first-class Williams signal type distinct from execution purpose are not yet carried end-to-end by Python producers and Android. A timestamp by itself is not treated as identity; the persistent key is the supplied stable `signal_id`, and the payload is compared to detect conflicts. The fallback compatibility path for narrow test doubles/legacy DB adapters still uses `try_claim_state`; production `Database` uses `claim_signal_once`.

## Android and end-to-end status

- `ExecutionGate.kt` is a process-local in-memory symbol reservation and is not a durable consumed-signal ledger.
- `TradingAuditStore.kt` currently has order/execution/event tables, but this checkpoint has not yet wired a persistent canonical signal claim into the Android entry/add-on path before the signed network sink.
- Android mutation-spy negative tests, process-restart idempotency, shared Python/Android golden replay, causal replay, complete mutation-sink call graph closure, UNKNOWN POST fault injection, and exit/protection/reconciliation recovery have not been completed in this checkpoint.
- `PHYSICAL DEVICE — NOT VERIFIED`; Binance Spot Testnet execution was not performed; Futures scenarios are `NOT VERIFIED`.

## TASK 004.7 acceptance matrix at this checkpoint

| Requirement | Status | Evidence / remaining work |
|---|---|---|
| Verify actual starting HEAD, PR and baseline CI | PASS for baseline only | Branch/PR/main and three required workflows checked on `982840a` |
| Durable atomic SQLite signal claim | IMPLEMENTED — CI pending | New `claim_signal_once`, unique primary key, `BEGIN IMMEDIATE`, commit and read-back |
| Two independent OS processes compete for one signal | TEST ADDED — CI pending | `test_signal_claim_processes.py`; exact-SHA CI required |
| Restart/new attempt replay and conflicting canonical payload | TEST ADDED — CI pending | Same stable signal ID is reused despite any new attempt ID; conflicting payload returns `CONFLICT` |
| SQLite BUSY/LOCKED, commit and read-back fault injection | UNRESOLVED | Full deterministic fault matrix not yet implemented |
| Complete canonical contract including strategy/rule version and dataset identity | UNRESOLVED | Producers and shared cross-platform contract not yet updated |
| Python all-production mutation sinks and UNKNOWN POST | UNRESOLVED | Full call graph and fault-injection closure not yet demonstrated |
| Android durable ledger and mutation-spy | UNRESOLVED | Store exists but signal claim is not wired before every actual signed mutation |
| Python↔Android golden replay and causal replay | UNRESOLVED | Not implemented/verified |
| Exit, protection, cancel-replace and recovery tests | UNRESOLVED | Separate sink-level proof outstanding |
| Final required CI on one final SHA | PENDING | Changes trigger new runs; no result on final TASK 004.7 report SHA yet |
| Physical Android / Spot Testnet / Futures | NOT VERIFIED | No physical-device or exchange execution evidence |
| main unchanged / PR not merged | PASS | `main` remains `04da5f9c09572e99ff310be3a1908179165e528c`; PR #49 open Draft |

**Decision: IN PROGRESS — NOT APPROVED.** This checkpoint improves Python SQLite signal claiming but does not close the cross-platform P0 requirements. Do not resume trading or treat the branch as execution-safe until the remaining acceptance criteria are independently proven.


## TASK 004.7 implementation amendment — Android durable entry/add-on claim

This amendment supersedes the earlier statement in the interim checkpoint that Android signal claiming had not yet been wired.

- `app/src/main/java/com/williamsbot/TradingAuditStore.kt`: schema version raised to 4; added persistent `signal_claims` table with `signal_id TEXT PRIMARY KEY`, canonical payload and claim time; migration for existing version < 4 databases; `claimSignalOnce` performs a SQLite transaction, checks existing payload, ends/commits the transaction, and performs a post-commit read-back. It returns `CLAIMED`, `DUPLICATE`, or `CONFLICT`; database errors throw.
- `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`: the canonical campaign entry and add-on submit paths call `claimCampaignSignalOnce` after last-mile checks and before their `signedPost("/api/v3/order", ...)`. The durable key is the stable `signal.signalId`, not the per-attempt `clientOrderId` that includes `System.nanoTime()`. Canonical payload currently includes symbol, BUY side, timeframe, signal-bar time, confirmation time, signal type and `BuildConfig.VERSION_NAME`.
- The Android change is source-level implementation only until the latest APK workflow completes. There is still no Android runtime mutation-spy test proving zero calls for all requested negative scenarios, and other direct mutation paths (emergency SELL, managed exits, cancellation/cancel-replace, protection/recovery) have not yet been proven to share this durable claim contract. The Python and Android canonical contracts are not yet identical: Python currently lacks an explicit strategy-version field and the input dataset/version, and its available payload shape differs. Cross-platform parity remains OPEN.

## CI failure investigation and correction

The first Python backend run after the barrier change failed on SHA `8ac2efe1f2dd8d4942449055547237a213563373` (run [38116577327](https://github.com/19wolfalone92/Williams/actions/runs/38116577327)); the next affected run on `972617ef83ae9bf90176ea27b69253f8355449bc` also failed (run [38116588109](https://github.com/19wolfalone92/Williams/actions/runs/38116588109)). Both failures had the same root cause: `test_execution_barrier_serializes_publish_and_submit` constructed an entry barrier without a database and expected the network callback to run. The production code correctly rejected the DB-less entry. The test fixture was corrected to supply a durable-claim test DB; production fail-closed behavior was not weakened. These failed runs are retained as failures and are not counted as passing evidence.

At the time of this amendment, the latest implementation commit is `04081cf4d44cb1dec1b876304ca04ec00c1f1b98`; the report amendment itself will create a new SHA and trigger fresh CI. The exact final-SHA statuses must be rechecked before any acceptance decision.


---

# TASK 004.8 — checkpoint: identity, mutation sinks, SQLite concurrency

**Checkpoint status: IN PROGRESS — NOT APPROVED.** This section records source-confirmed findings and the exact CI evidence available during TASK 004.8 work. It does not claim completion of the acceptance matrix.

## Repository / previous SHA CI

- Branch: `audit/williams-2.1-task-004-williams-strategy`
- Base/main SHA verified unchanged: `04da5f9c09572e99ff310be3a1908179165e528c`
- PR #49 verified OPEN, Draft, not merged.
- HEAD at start of TASK 004.8: `5b3afd83aa9714023b0d57b60f67e06bb3d57b61`
- Subsequent implementation commits: Android KTX transaction fix `411b1372d4ceb6516330e8b6efb3b58ac27e5150`; expanded SQLite process tests `4ddbe949799a8085d9ffd3425a81688c2fe938ea`; BUSY test setup correction `75be2aa2532ff1bd9477f53958cbf3b1faad8fb1`. This SHA is the pre-report checkpoint, not the final SHA after this report edit.

| Check | Run ID | Tested SHA | Actual result | Evidence |
|---|---:|---|---|---|
| Python backend tests | 38116792057 | `5b3afd83aa9714023b0d57b60f67e06bb3d57b61` | SUCCESS | https://github.com/19wolfalone92/Williams/actions/runs/38116792057 |
| Williams Campaign CI | 38116792054 | `5b3afd83aa9714023b0d57b60f67e06bb3d57b61` | FAILURE — Android build/static-check job failed; Python job passed | https://github.com/19wolfalone92/Williams/actions/runs/38116792054 |
| Build Android APK | 38116792048 | `5b3afd83aa9714023b0d57b60f67e06bb3d57b61` | FAILURE — required Android lint failed | https://github.com/19wolfalone92/Williams/actions/runs/38116792048 |

### Root cause from failed Android runs

Both failing Android jobs identify the same lint error in `TradingAuditStore.kt`: `UseKtx` requires the SQLite KTX `SQLiteDatabase.transaction` extension instead of manual `beginTransaction()/setTransactionSuccessful()/endTransaction()`. The transaction body was changed to use `androidx.core.database.sqlite.transaction` in commit `411b1372d4ceb6516330e8b6efb3b58ac27e5150`; production lint rules were not disabled. New workflows must prove this fix on the final SHA.

## TASK 004.8 changes in this checkpoint

- `app/src/main/java/com/williamsbot/TradingAuditStore.kt`: claim transaction now uses the required KTX transaction extension; post-commit read-back remains mandatory.
- `test_signal_claim_processes.py`: retained existing real-OS-process tests and added an eight-process contention case, a conflicting-payload two-process case, and a real SQLite write-lock contention test. These are intended regression tests; they are not marked TEST-PROVEN until the relevant workflow completes successfully.
- Existing `db.py::Database.claim_signal_once` uses a primary-keyed `bot_state` key and `BEGIN IMMEDIATE`, canonical JSON serialization, commit, and post-commit read-back. The method rejects nested/uncommitted caller transactions. This is CODE-CONFIRMED, not a full proof of every commit/read-back fault mode.

## Canonical identity audit — unresolved P0

Python `campaign_model.py::SignalSpec.new` currently creates `SYMBOL:timeframe:signal_type:signal_bar_time_ms`; Android `StandaloneRuntime.kt::campaignSignalId` creates a similar string. Neither generated ID includes side, market type, strategy-contract version, schema version, operation role, or data/configuration version. Android's durable claim payload uses `BuildConfig.VERSION_NAME`, which is the application version (and can include a build-type suffix), not an independently governed strategy version. Python's barrier payload includes symbol, side, timeframe, signal/confirmation times, campaign ID and purpose-derived type but has no explicit strategy/schema/configuration version. The payloads are therefore not cross-platform equivalent.

No guessed strategy version or fallback identity was silently introduced in this checkpoint. A shared versioned contract, deterministic normalization and common Python/Android fixtures remain required before the identity P0 can close.

## Mutation-sink audit — source-confirmed gaps

### Python campaign path

`CampaignExecution` uses `_submit(...)` → `ExecutionBarrier` → `BinanceClient.order_safe(...)` for the campaign conditional-entry path. The adapter's `order_safe` explicitly handles ambiguous submission by querying the exchange rather than blindly retrying. However, the audit has not established that every production caller, exit/cancel/protection path and server control endpoint is routed through that barrier. No full sink coverage claim is made.

### Android direct sink inventory

The following production mutation paths were found in `StandaloneRuntime.kt`:

| Operation | Source path / sink | Durable signal claim currently proven in source? | Status |
|---|---|---|---|
| Campaign initial conditional BUY | `submitCampaignEntry` → `claimCampaignSignalOnce` → `signedPost("/api/v3/order")` | Yes, signal claim before POST | CODE-CONFIRMED; no Android mutation-spy |
| Campaign add-on conditional BUY | add-on submit → `claimCampaignSignalOnce` → `signedPost("/api/v3/order")` | Yes, signal claim before POST | CODE-CONFIRMED; no Android mutation-spy |
| Legacy/direct market BUY | `signedPost("/api/v3/order")` near the legacy buy path around line 6055 | No equivalent campaign signal claim confirmed | P0 GAP |
| Regular SELL / manual sell | `sell(symbol)` → `signedPost("/api/v3/order")` near line 6561; kill path calls `sell` | No operation-level durable intent/claim proven | P0 GAP |
| Emergency SELL after OCO replacement failure | direct `signedPost("/api/v3/order")` near line 4399 | No durable emergency SELL intent proven in this path | P0 GAP |
| OCO cancellation/replacement | `signedDelete("/api/v3/orderList")`, then `createProtection`; other OCO create/cancel sites include lines ~6393 and ~6501 | No complete durable operation identity/recovery proof | P0 GAP |
| Pending-entry cancellation | `signedDelete("/api/v3/order")` in kill and pending-entry cleanup | No common durable cancellation intent proven | P0 GAP |
| Cancel-replace | `signedCancelReplace` → `signedPost("/api/v3/order/cancelReplace")` | No common durable intent proven | P0 GAP |

These are direct source paths, not a complete tested call graph. `signedRequest` is a shared HTTP sink, but currently does not itself require a durable operation permit before POST/DELETE. The retry branch for Binance timestamp error `-1021` also requires an explicit endpoint/method safety test before it can be treated as safe for every mutation type.

## SQLite process-test scope

The new tests exercise genuine independent spawned OS processes against one temporary SQLite file, plus actual SQLite write-lock contention. They do **not yet** cover every requested case: injected commit failure, post-commit read-back failure, SQLITE_LOCKED, corruption, process termination at every transaction boundary, concurrent migration, or Android SQLite fault injection. Those cases remain NOT VERIFIED; no fake exception is being represented as proof of a real SQLite commit failure.

## Not yet tested / remaining P0

- Cross-platform shared canonical identity and golden fixtures.
- Complete mutation-sink enforcement and mutation-spy on Android.
- Durable intent and recovery proof for direct BUY, SELL, emergency SELL, OCO, cancellation and cancel-replace.
- Fault injection for UNKNOWN POST, lost response, late fills/fees and two concurrent recovery processes.
- Causal replay against future-candle mutations and deterministic replay fixtures.
- Exit/protection residual exposure and financial-effect exactly-once tests.
- Android schema migration and claim concurrency tests on-device/emulator.
- Physical Android device: NOT VERIFIED.
- Binance Spot Testnet: NOT VERIFIED; no order operations performed.
- Futures: NOT VERIFIED.

No Mainnet access or live trade was used. No merge was performed. TASK 004.8 remains IN PROGRESS — NOT APPROVED until the above P0 evidence exists on one final SHA.


---

# TASK 004.9 — Canonical Signal Identity / Durable Mutation Boundary (baseline checkpoint)

**Checkpoint date:** 2026-10-11  
**Baseline HEAD:** `5a217774cd00e21d83a1c7274c16eef5e468ebda`  
**Base/main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Branch:** `audit/williams-2.1-task-004-williams-strategy`  
**PR:** [#49](https://github.com/19wolfalone92/Williams/pull/49), OPEN / Draft / not merged.  
**Task status:** **BLOCKED — NOT ACCEPTED**. This section records re-verified baseline evidence; it does not claim implementation of the full TASK 004.9 contract.

## A. Re-check of repository and CI

The current PR head was re-read from GitHub and is still `5a217774cd00e21d83a1c7274c16eef5e468ebda`; PR base is still `04da5f9c09572e99ff310be3a1908179165e528c`. The PR is OPEN and Draft. No merge was performed; `main` was not changed by this audit.

| Workflow | Run | SHA checked | Final conclusion | Evidence |
|---|---:|---|---|---|
| Python backend tests | [38119669631](https://github.com/19wolfalone92/Williams/actions/runs/38119669631) | `5a217774cd00e21d83a1c7274c16eef5e468ebda` | **CI-PROVEN: SUCCESS** | Final run and required `backend-test` job completed successfully |
| Williams Campaign CI | [38119669643](https://github.com/19wolfalone92/Williams/actions/runs/38119669643) | `5a217774cd00e21d83a1c7274c16eef5e468ebda` | **CI-PROVEN: SUCCESS** | Both `Python tests` and `Android build and static checks` completed successfully |
| Build Android APK | [38119669661](https://github.com/19wolfalone92/Williams/actions/runs/38119669661) | `5a217774cd00e21d83a1c7274c16eef5e468ebda` | **CI-PROVEN: SUCCESS** | `Build APKs` completed successfully |
| Additional Campaign CI push run | [38119666885](https://github.com/19wolfalone92/Williams/actions/runs/38119666885) | `5a217774cd00e21d83a1c7274c16eef5e468ebda` | **CI-PROVEN: SUCCESS** | Push-triggered run also completed successfully |

These are results for the stated baseline SHA only. They do not apply to any later commit. These workflows do **not** prove all TASK 004.9 acceptance criteria: in particular, they do not establish a shared versioned Python/Kotlin identity contract, universal sink gating, full fault injection, or exchange-observed recovery.

## B. Source audit: signal identity

### Python — CODE-CONFIRMED
- `campaign_model.py::SignalSpec.new` constructs `signal_id` as `SYMBOL:timeframe:signal_type:signal_bar_time_ms`.
- The ID does not include side, operation role, strategy-contract version, identity-schema version, market type, or a configuration identity.
- `SignalSpec.to_dict()` serializes the full dataclass via `asdict`; that payload is a separate representation and is not itself a documented cross-platform canonical serialization.
- `db.py::Database.claim_signal_once` uses a persistent unique key and compares canonical payloads, but the claim API accepts a caller-provided ID and payload; it does not establish that either platform computed them from the same versioned contract.

### Android — CODE-CONFIRMED
- `StandaloneRuntime.kt::campaignSignalId` constructs an ID from uppercased symbol, lowercased frame, signal type and bar timestamp.
- `TradingAuditStore.kt::claimSignalOnce` persists a `signal_id` and caller-provided canonical payload; it is currently used for campaign entry/add-on paths.
- `BuildConfig.VERSION_NAME` is application/build metadata and is not a governed strategy version. It must not be substituted for a strategy contract version.
- There is no demonstrated shared canonical fixture consumed by both the Python tests and Android tests that proves byte-identical canonical payloads and IDs.

### Contract decision remains BLOCKED
Do **not** silently pick a strategy version or config fingerprint. The repository evidence reviewed here does not establish a separately governed strategy-version source or a complete rule for which configuration changes alter decision identity. Until these are explicitly defined, the system cannot claim a complete canonical identity contract. Any new identity implementation must preserve old persisted claims and must not turn an app upgrade into a new executable opportunity.

## C. Android mutation-sink inventory — source-confirmed call sites, gate coverage NOT VERIFIED

A fresh scan of `app/src/main/java/com/williamsbot/StandaloneRuntime.kt` found these mutation families. This is a source inventory, not proof that every indirect caller is enumerated or protected.

| Source location / method family | Mutation | Current evidence | TASK 004.9 status |
|---|---|---|---|
| `submitCampaignEntry` path around line 2824 | Conditional campaign BUY | Caller calls `claimCampaignSignalOnce` before `signedPost` | **CODE-CONFIRMED caller claim only; sink permit NOT VERIFIED** |
| Add-on path around line 3860 | Conditional add-on BUY | Caller calls `claimCampaignSignalOnce` before `signedPost` | **CODE-CONFIRMED caller claim only; sink permit NOT VERIFIED** |
| `createCampaignStop` around line 2876 | Stop creation | Calls `signedPost` inside `withCampaignMutation` | **BLOCKED: no demonstrated durable operation permit enforced at common sink** |
| `signedCancelReplace` around lines 2918, 3330 | Cancel-replace / protection replacement | Common helper delegates to `signedPost("/api/v3/order/cancelReplace", ...)` | **BLOCKED: ambiguous outcome recovery and common durable permit not proven** |
| Exit path around line 2995 | Campaign market SELL | Calls `signedPost` inside campaign mutation wrapper | **BLOCKED: caller wrapper is not proof of durable intent at sink** |
| Pending-entry cancellation around lines 1345, 3151, 3237 | Cancel pending order | Calls `signedDelete("/api/v3/order", ...)` | **BLOCKED: per-operation durable cancellation intent and UNKNOWN recovery not proven** |
| Emergency campaign SELL around line 3517 | Emergency market SELL | Calls `signedPost` after protection failure | **BLOCKED: stable durable emergency operation ID not proven** |
| OCO cancellation around lines 4329, 4336, 6501, 6507 | Cancel OCO list | Calls `signedDelete("/api/v3/orderList", ...)` | **BLOCKED: common durable permit and cancel-result reconciliation not proven** |
| Emergency SELL around line 4399 and fallback around line 6272 | Emergency/legacy SELL | Direct `signedPost` call sites | **BLOCKED: independent paths lack sink-level proof** |
| Legacy/direct BUY around line 6055 | Market BUY | Direct `signedPost` call | **BLOCKED: durable operation intent and common gate not proven** |
| OCO creation around line 6393 | OCO create | Direct `signedPost("/api/v3/orderList/oco", ...)` | **BLOCKED: stable OCO intent and UNKNOWN recovery not proven** |
| Regular/manual SELL around line 6561 | Market SELL | Direct `signedPost` call | **BLOCKED: durable operation intent and common gate not proven** |
| Shared helpers around lines 1554, 5340–5375, 6629 | Signed HTTP mutation boundary | `signedPost`, `signedDelete`, and cancel-replace delegate to `signedRequest` | **P0 BLOCKER: the shared boundary does not yet demonstrate mandatory verification of a durable, operation-bound permit for every mutation** |

The `withCampaignMutation` wrapper is not accepted as a durable-intent gate unless the common mutation boundary independently verifies a persisted permit bound to operation identity, method, endpoint and canonical parameters. The current source scan did not find mutation-spy evidence proving that direct/legacy callers cannot bypass such a permit.

## D. Durable intent, UNKNOWN POST, exits and financial effects

| Requirement | Evidence at baseline | Status |
|---|---|---|
| Durable intent before every BUY/SELL/OCO/cancel/cancel-replace mutation | Existing campaign signal claim covers only some entry/add-on call paths; no universal operation-intent gate proven | **BLOCKED** |
| Stable operation identity distinct from signal identity | Several paths generate client IDs using runtime time values; complete recovery-stable operation identity not established | **BLOCKED** |
| UNKNOWN POST recovery from authoritative exchange state | Source review does not prove every mutation category reconciles orders, trades/fills, residual balances and protection before retry | **BLOCKED** |
| Cancel-replace/OCO replacement recovery | Non-atomic multi-request windows remain; complete crash-state matrix not proven | **BLOCKED** |
| Partial fills, commissions and exactly-once financial effects | Not proven by signal-claim tests; signal idempotency is a different identity layer from fill/effect idempotency | **BLOCKED** |
| Common runtime mutation-spy covering all sinks | No evidence of complete sink-by-sink mutation-spy matrix | **NOT VERIFIED** |
| Binance Mainnet / live mutations / Spot Testnet mutation | Not performed | **NOT VERIFIED / PROHIBITED by task constraints** |
| Physical Android behavior and real exchange-observed recovery | Not performed in this source/CI checkpoint | **NOT VERIFIED** |

## E. SQLite claim tests — limited evidence only

At this baseline, `test_signal_claim_processes.py` contains process-based claim tests, including eight spawned processes competing for one signal, a conflicting payload case, and a real SQLite write-lock contention case. These are valuable regressions but do not establish the full TASK 004.9 fault matrix. Commit-failure and post-commit read-back-failure injection, process termination at every transaction boundary, schema-migration races, and Android SQLite fault injection are not proven by the cited CI summary alone. A test being present is not itself a pass; only the completed workflow result above proves that the workflow's configured tests passed.

## F. Causal replay and shared fixtures

The existing Campaign CI has Python and Android test stages, but no evidence in this checkpoint proves that a single shared identity fixture file is consumed by both runtimes for all required version, normalization, time-equivalence, missing-field, unknown-version, and legacy-claim cases. Full cross-platform identity golden proof and the requested causal replay matrix remain **NOT VERIFIED**.

## G. P0 acceptance blockers

1. Define an explicit, independently governed strategy version source and a documented rule for configuration identity; do not use app version as strategy version.
2. Implement one versioned canonical signal identity contract and migration/compatibility behavior for persisted legacy claims.
3. Add common golden fixtures consumed by both Python and Android tests, and demonstrate identical canonical payload and ID.
4. Put a durable operation-intent permit at the actual shared mutation boundary and prove no direct/legacy client bypass.
5. Give each BUY, SELL, emergency SELL, OCO create/cancel/replace, pending cancellation and cancel-replace a stable durable operation ID and pre-mutation intent.
6. Add runtime mutation-spy coverage for every enumerated sink and negative cases (missing/failed/conflicting intent, UNKNOWN prior result, recovery latch).
7. Add honest SQLite commit/read-back fault injection and cross-process crash-recovery tests; keep unsupported failure simulations marked NOT VERIFIED.
8. Prove endpoint-specific UNKNOWN POST recovery for orders, OCO and cancel-replace without blind retry.
9. Prove partial-fill, commission and exactly-once financial-effect recovery separately from signal claims.
10. Complete causal replay and confirm all required CI workflows on the eventual final SHA.
11. Keep PR #49 Draft and do not merge. No Mainnet or Spot Testnet mutation is authorized.

**Acceptance remains: BLOCKED — NOT ACCEPTED.** The baseline workflows passed on `5a217774cd00e21d83a1c7274c16eef5e468ebda`, but the mandatory safety architecture and cross-platform proof criteria above remain unresolved.


---

## TASK 004.13 — Causal replay, fractal confirmation & Python–Android parity

**Checkpoint checked:** 2026-10-11  
**Observed branch HEAD before this documentation update:** `dd1eb3d462f3a8641bf2df00ad2086192ea9b88c`  
**Implementation/test SHA under examination:** `82ecc9e8f899b1128892e66c773d12763986425f`  
**PR #49:** Open / Draft; base `main` at `04da5f9c09572e99ff310be3a1908179165e528c`.  
**Decision:** **BLOCKED — NOT ACCEPTED.** No strategy-code changes or trading mutations were made in this task checkpoint.

### Evidence verified at the implementation SHA

- Shared fixture: `app/src/test/resources/williams_strategy_golden_vectors.json`, blob `d0a6e221c4382150339ece9bc68540b08e8b9a1c`. It contains deterministic median-price, Alligator, AO/AC, fractal tie/cancellation, conditional-trigger, timeframe and Spot-side cases.
- Python test: `tests/test_williams_strategy_golden_vectors.py`, blob `d0e136c5298d971df35a5a05e1bb00915f4e8f7b`. It checks SMMA invalid seed/gap behavior, indicator vectors, prefix-before-confirmation for selected fractals, WM2 independence from WM3, trigger/invalidation and timeframe constraints.
- Android test: `app/src/test/java/com/williamsbot/WilliamsStrategyGoldenVectorsTest.kt`, blob `893946b46ba25d3c2b2120455cfbf4071eec6531`. It reads the same JSON resource and tests Alligator/AO/AC vectors, fractal timing/ties/cancellation, pending trigger and H1-only provenance.
- Android fractal helper: `WilliamsFractalMath.kt`, blob `b9830e6f9bca360dcf1b73d399fc450266d1b0dd`. It returns center index separately from confirmation index; equal right-side extremes do not qualify as lower highs/higher lows, while a stricter new extreme cancels the candidate.
- Timeframe contract: `WilliamsTimeframeContract.kt`, blob `ceacc115d53396836f6fdc745cecb3fb98c0ecdd`. H1 is canonical; D1/H4 are context; M15 is execution/fill monitoring; M5 is diagnostics/replay only. This is a bot policy, not a claim about a rule stated in Williams's books.
- The current branch HEAD is one documentation-only commit ahead of the implementation SHA: comparison `82ecc9e8f899b1128892e66c773d12763986425f..dd1eb3d462f3a8641bf2df00ad2086192ea9b88c` adds only `TASK_004_11_REPORT_RU.md`.

### CI evidence (do not over-interpret)

On `82ecc9e8f899b1128892e66c773d12763986425f`, Python backend workflow `38129894739` completed SUCCESS, Android APK workflow `38129894745` completed SUCCESS, and Campaign CI `38129894732` was CANCELLED. On observed branch HEAD `dd1eb3d462f3a8641bf2df00ad2086192ea9b88c`, the three latest workflows were reported SUCCESS (Python `38129954698`, Campaign `38129954685`, APK `38129954731`). These CI outcomes establish only that configured jobs passed; they do not establish comprehensive causal replay or full strategy parity.

The Python backend workflow includes `tests/test_williams_strategy_golden_vectors.py`; Campaign CI runs Android `testDebugUnitTest`. The fixtures therefore have test-consumer coverage in both environments. This is **partial formula/vector parity**, not a proof that every live strategy path consumes the helpers or that all signal states match.

### Acceptance matrix

| Requirement | Evidence at inspected SHA | Status |
|---|---|---|
| AO = SMA(5, median) − SMA(34, median); AC = AO − SMA(5, AO) | Shared vectors consumed by Python and Android unit tests | PARTIAL PASS; not full runtime-path parity |
| Alligator periods 13/8/5, display shifts 8/5/3 and SMA-seeded SMMA | Shared formula vectors and Android/Python unit assertions | PARTIAL PASS; seed convergence/warm-up policy still needs explicit proof |
| Fractal not available before required right-side bars; ties extend confirmation; stronger extreme cancels | Selected prefix and full-vector assertions on both platforms | PARTIAL PASS; full live/restart/replay matrix not proven |
| WM2 is independent of prior WM3 | Python test checks candidate without strict price fractal; shared AO colour vector | PARTIAL PASS; Android end-to-end WM2 candidate path not fully demonstrated |
| Prefix replay for every indicator and all strategy/campaign/exit states | No exhaustive all-prefix replay harness/result found in reviewed evidence | BLOCKED |
| Open-vs-closed candle semantics and exchange timestamp ordering | Not fully demonstrated by the cited fixtures | BLOCKED |
| Same complete signals, setup/confirmation and availability timestamps in Python and Android | Shared indicator fixtures exist; full decision-output differential suite not proven | BLOCKED |
| D1/H4/H1/M15/M5 enforcement in every signal entry point | Helper-level tests exist; exhaustive runtime call-path test not proven | BLOCKED |
| Campaign, trailing, exit, repeat signal and process restart parity | Not covered end-to-end by cited golden fixture tests | BLOCKED |
| All six requested audit matrices maintained | See the task-scoped matrices added alongside this section | DOCUMENTATION ADDED; behavior remains BLOCKED |

### Rule classification

- **WILLIAMS RULE:** AO formula; Alligator median-price periods and displayed offsets; TC2 WM2's third same-direction AO bar; fractal formation/confirmation requirements as supported by the source profile.
- **SYSTEM SAFETY RULE:** never trade a not-yet-confirmed fractal; never use future bars; unknown/missing data blocks entry; Spot SELL reduces/closes inventory and does not open a short.
- **IMPLEMENTATION RULE:** SMA seed for SMMA, shared JSON fixture format, TTL/expiry, exact event timestamp representation, numerical tolerance. These conventions must be explicit and deterministic.
- **RESEARCH REQUIRED / UNRESOLVED = BLOCKED:** exact numeric WM1 angulation; complete seed-convergence requirement; universal source-defined signal TTL; any ambiguous tie semantics beyond the inspected source examples; all live path and restart parity not demonstrated by tests.

### Required next proof

Add and execute an all-prefix causal replay harness against the exact same timestamped OHLCV dataset in Python and Android/JVM. At every prefix, compare all previously emitted events and state transitions, allowing only documented forward state transitions. Include closed-bar boundary tests, equal-extreme and stricter-extreme fractal cases, restart between pivot and confirmation, WM2 without WM3, and full PendingSignal → trigger → campaign → trail/exit sequences. Record input fixture hash, implementation SHA, test command, test count and CI run IDs. Until those artifacts exist, **TASK 004.13 remains BLOCKED**.
