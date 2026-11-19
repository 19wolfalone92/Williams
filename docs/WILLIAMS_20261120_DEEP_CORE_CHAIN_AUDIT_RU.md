# Williams Bot — глубокий аудит ядер, связей и финансовых переходов

**Аудит:** 20 ноября 2026. **Объект:** Android автономный Binance USDⓈ-M Futures **Demo/Testnet**; альтернативный Python runtime сверялся отдельно, без смешения с Android.  
**Базовый код:** `fix/diag4-null-loss-blocker-ui-20261120` (PR #258), родительская цепочка #257→#256→#255→#253; `main` не изменена.  
**Материалы:** исходные ZIP `Williams_Diagnostics (3).zip` и `Williams_Diagnostics (4).zip`, их 79 диагностических тестов/25 компонентов/42 взаимосвязи, 150 событий кольцевого журнала ZIP #4; исходники и существующие тесты GitHub.  
**Ограничение:** это глубокий *выборочный проверяемый code-and-chain audit*, **не** построчный ручной просмотр всех 991 файлов и **не** биржевая/физическая приёмка: подписанные мутации Binance или тесты Doze/reboot/force-stop на телефоне в этом аудите **не выполнялись**. Ни один PASS в локальной диагностике не выдаётся за доказательство биржевого FILLED→STOP.

## 1. Результат снимков, точные значения

| Показатель | ZIP #3 | ZIP #4 | Значение |
|---|---|---|---|
| Установленный APK | 4.64.46-debug (синтетический коммит 9fa7bdcc) | 4.64.46-debug (синтетический merge 18a5e2cc) | Исторические APK-снимки, не новый release |
| Тесты | 46 PASS / 7 WARNING / 26 NOT_VERIFIED | **58 PASS / 2 WARNING / 19 NOT_VERIFIED**, 0 FAIL | Компонентная диагностика |
| OOM | Подтверждён Java `OutOfMemoryError` | Нового OOM после обновления не обнаружено | Не доказывает 24 ч стабильности |
| H1 и контекст | Не вся цепь допуска доказана | 15/15 H1; наблюдения 75/75 D1/H6/H4/H1/M15 contiguous closed | Валидность выборки, не истинность сигналов |
| Новый биржевой ордер | Не доказан успешный полный цикл | **SUIUSDT SHORT H1 WM3**, qty **77.9**, STOP-entry **0.7059**, exchange **NEW**, executed **0** | Реальный отложенный вход, но нет открытой позиции |
| Планируемая защита | Не подтверждена | 0.7291, `NOT_SUBMITTED` до исполнения | Биржевой защитный ордер **не подтверждён** |
| Новые входы | Не READY | `RECONCILE_REQUIRED:exchange_reconciliation_in_progress` в момент ZIP | Временная сверка, не блокировка за 3 убытка |
| Риски | 3%/3 подряд | Суточное изменение 0%; серия 0/3; доступный риск | Риск-состояние не объясняет этот временный запрет |

В ZIP #4 сам граф целостности сообщает: 25 компонентов, 42 ребра, 14 PASS / 2 WARNING / 9 NOT_VERIFIED. Это **внутренняя классификация источника** и должна проверяться независимо. Есть `UNMAPPED_DIAGNOSTIC_TEST_IDS`: отображение всех диагностических проверок на ядра пока неполное. `e2e_demo_certified=false`, `independent_book_accuracy_certified=false`.

## 2. Трассировка всех критических финансовых цепей

| № | Проверенная связка | Контракт / исследованный код | Вывод |
|---|---|---|---|
| 01 | Android process → FGS → native worker | `AndroidManifest.xml`, `TradingBootReceiver.kt`, `TradingForegroundService.kt`, `StandaloneRuntime.kt` | Реальный FGS, heartbeat, повторный bootstrap и проверки Keystore. Samsung Doze/reboot 24ч **не доказаны**. |
| 02 | Clock → подписанные REST чтения/записи | `BinanceUsdMFuturesTestnetAdapter.kt`, `FuturesDemoVerifiedClock.kt` | UTC anchor из Binance, RTT-фильтр, запрет устройства как часов, timestamp fence перед транспортом; реальные потери связи/451 не исключены. |
| 03 | Public REST / WS → свечи → SQLite | `BinanceUsdMFuturesMarketData.kt`, `FuturesCandleCacheIntegrity.kt`, `MarketHistoryStore.kt` | Проверки closed bars, интервальных дыр и истории. 75/75 снимков без зазоров. No-look-ahead подтверждён только техническими тестами, не независимыми эталонами. |
| 04 | D1/H4 контекст → H1 решение → M15 мониторинг | `WilliamsWaveHierarchy.kt`, `WilliamsBookH1EntryPolicy.kt`, `WilliamsMtfEntryPolicy.kt`, `WilliamsExecutionPolicy.kt` | H1 — authority, M15 не создаёт самостоятельный сигнал. Верность волн/ангуляции 4 книгам не доказана независимо. |
| 05 | TC2 источник → WM1/WM2/WM3 → профили | `WilliamsExecutionPolicy.kt`, `WilliamsSetupRules.kt`, `WilliamsTc2Pyramid.kt` | Новая экспозиция только TC2; NTD и Course наблюдение. Для старых открытых источников допускается безопасное сопровождение, не новая несанкционированная позиция. |
| 06 | Scanner → паспорт → Risk → Execution Door | `StandaloneRuntime.kt`, `WilliamsEntryPassport.kt`, `ExecutionGate.kt`, `TradingStateMachine.kt` | Несколько независимых admission veto; журнал попытки до POST. Диагностика не записывает **каждый** отвергнутый неоформленный сигнал. |
| 07 | Суточная база → PnL/fee → 3%/3 подряд | `TradeJournal.kt`, `FuturesDailyEntryHalt.kt`, `EquityCircuitBreaker.kt` | Порог 3% или три **последовательных** verified LOSS; отдельный 5% emergency kill. Найден cross-midnight дефект в связке owner-close/journal (см. F-01). |
| 08 | Общее обеспечение → 50% margin → final POST fence | `FuturesHalfMargin.kt`, `NativeFuturesCampaignCoordinator.kt`, `BinanceUsdMFuturesTestnetAdapter.kt` | Два signed снимка/идентичность обязательств и окончательный 15с lease. Ранее BNB отказ `...:STALE`; нельзя обходить/растягивать guard без новой проверки. |
| 09 | Entry intent → signing → LIMIT/STOP/MARKET | `NativeFuturesCampaignCoordinator.kt`, `BinanceUsdMFuturesTestnetAdapter.kt` | Durable intent до POST, идентичность client id, консервативная `SUBMISSION_UNKNOWN` при сомнительном ответе, без слепого POST retry. SUI NEW в ZIP #4. |
| 10 | Private WS → durable inbox → REST reconcile | `BinanceUserDataStream.kt`, `TradingAuditStore.kt`, `PrivateUserEventPolicy.kt`, `TradingEventLoop.kt` | SQLite inbox до ack и generation guard. **Неограниченная** очередь actor может расти в RAM при storm; стресс-предел не доказан. |
| 11 | Partial/FILLED → signed position → initial STOP | `NativeFuturesCampaignCoordinator.kt` reconcile/STOP, `BinanceUsdMFuturesTestnetAdapter.kt` | Политика fail-closed и экстренного восстановления есть. **Нет реального signed FILLED→подтверждённый exchange STOP** ни для LONG, ни для SHORT; P0. |
| 12 | Текущий STOP → новый STOP → проверка старого → отмена | `FuturesNativeStopReplacement.kt`, `FuturesStopTrailPolicy.kt`, координатор | Защита нового перед удалением предшественника, identity/qty/side checks. Нет фактической Demo замены/тайминг-доказательства. |
| 13 | Оппозиционный TC2 exit → reduce-only → confirmed flat | `Tc2OppositeExitLifecycle.kt`, `WilliamsTc2Reversal.kt`, координатор | Переход на противоположную сторону требует закрытия и новой авторизации; обменный полный цикл не доказан. |
| 14 | Signed fills/commissions → журнал → 3-loss guard | `FuturesTradeFeeLedger.kt`, `TradeJournal.kt`, `TradingAuditStore.kt` | Fee/trade ID dedup, unknown fee fail closed, durable SharedPrefs commit. Межхранилищная временная несвязность учитывается отдельным guard, но обнаружен F-01. |
| 15 | SQLite canonical intents ↔ shadow → export/UI | `FuturesExecutionStore.kt`, `FuturesIntentShadowProjection.kt`, `DiagnosticsArchive.kt`, `MainActivity.kt` | 399/399 parity на ZIP #4, но authority всё ещё `LEGACY_JSON_ONLY`; shadow не заменил canonical торговый журнал. |

Дополнительно проверены: приоритизация подписанных STOP/позиционных запросов перед read-only forensics; конкурентные редукции и аварийная разметка восстановления; отсутствие допустимого реального Spot для ордеров; `positionSide=BOTH`, `ISOLATED`, контроль Binance `algoId`, STOP `MARK_PRICE`, qty/направление и запрещённый произвольный SELL/BUY; межветочный статус PR и подписанные CI.

## 3. Обнаруженные дефекты и остаточные риски

**F-01 — P0: owner из прошлого UTC дня без close timestamp может обходить сегодняшний 3-loss gate. Фактически найден в коде.**  
Исходная ветка `futuresUnjournalledClosedCampaignBlocker`: для ранее созданного, FILLED, но признанного CLOSED кампаний с `closed_observed_at_ms=0` и `confirmed_flat_at_ms=0` выполнялся `continue`, если `created_at_ms < utcStart`. Вчерашняя дата входа **не доказывает** вчерашнюю дату закрытия: выход мог произойти сегодня. Даже при `RECORDED` без сверки UTC-даты второй журнал мог содержать более старую запись с тем же id. Это нарушает полноту последовательной серии трёх убыточных закрытий. **Исправлено в отдельной audit-ветке**: неизвестный timestamp требует полного signed journal close для того же клиента; если owner CLOSE текущего дня, journal time должен быть в той же UTC-сессии. 4 регрессионных сценария, точная CI-проверка обязательна. На живом аккаунте условие пока не воспроизводилось; не выдавать за реальную уже случившуюся потерю.

**F-02 — P0 / НЕ ПОДТВЕРЖДЕНО: защитная биржевая заявка отсутствует до FILLED условного entry.**  
В устройстве SUI NEW qty filled=0, planned STOP NOT_SUBMITTED — ожидаемая архитектура. Но если биржевой STOP-entry выполнится в период потери связи/Doze, STOP ставится **позже отдельным signed Android POST**, а не в одной атомарной биржевой транзакции. Нужен независимый Demo тест на `NEW→PARTIAL→FILLED→POST STOP→SIGNED GET STOP`, включая убийство процесса между каждым этапом, рыночный gap и аварийный reduce-only. До него нельзя считать инвариант «любая открытая позиция защищена в каждый момент» доказанным.

**F-03 — P1: длительность signed margin lease мешает фактическим входам.**  
BNB архивно отклонён с `FUTURES_HALF_MARGIN_UNKNOWN:STALE`; `assessFuturesHalfMargin(... elapsedMs <=15000)`, затем повторная проверка `futuresHalfMarginLeaseFresh` перед транспортом. Предлагаемый ремонт — перестроить schedule последних signed reads и момент final proof непосредственно перед POST при неизменной безопасной идентичности обязательств; **не** снять этот guard, не вернуть старые snapshots, не расширять допустимую маржу.

**F-04 — P1: общий lock на сетевую сверку.**  
`NativeFuturesCampaignCoordinator.reconcile(): synchronized(lock)` удерживает lock во время последовательных signed GET (account, positions, orders, algos). В этот момент entry gate обязан закрываться — это корректно. Но длительность исполнения и задержка STOP critical path при rate-limit/сети не имеют полного p99, cancel/stale deadline и offline proofs. Нужен инструментированный phase-budget, проверка fairness и отказ от необоснованного распараллеливания финансовых мутаций.

**F-05 — P1: очередь Actor может переполнить heap при шторме.**  
`TradingEventLoop` использует `LinkedBlockingQueue<Runnable>()` без лимита. Нельзя просто сбрасывать private WS: потеря заполнений и STOP reconciliation опасна. Архитектурный выход — durable first SQL spool + backpressure на необязательных наблюдателях; при росте очереди блокировать новые входы и ускорять критические signed reconciliations. Проверить Doze/GC/OOM под burst.

**F-06 — P1: два независимых runtime с разными telemetry/equity semantics.**  
Python `server.py` вычисляет `consecutive_losses` из **текущих открытых позиций**, поэтому при flat отображается 0 даже если сегодня было три LOSS. Это UI/backend-telemetry defect, не доказательство того, что Android 3-loss gate пропускает сделки. Python `_equity()` при проблеме полей fallback к `availableBalance`, это не эквивалентно total equity и требует отдельного risk hardening, если Python когда-либо получает право торговать. В Android используется иная цепочка. Не заменять Kotlin risk telemetry Python значениями.

**F-07 — P1/P2: неполная карта диагностики и полнота наблюдения.**  
`UNMAPPED_DIAGNOSTIC_TEST_IDS` и `SCANNER/TC2_RULES NOT_VERIFIED`; даже 58 PASS не дают независимой разметки WM1 ангуляции, коррекций W2/W4/ABC, WM2 AO, WM3 Teeth, no-look-ahead, real matching spot to signed exchange. Для каждого decision trace нужен недостающий источник rejection stage и его инвариант на последних закрытых H1; не вводить новые запреты ради покрытия.

**F-08 — P0 acceptance: устройство в ZIP #4 не показало действующий позиционный STOP, Stop trail или fully signed close → fee/PnL.**  
Эти ядра честно маркированы NOT_VERIFIED, а не PASS. Нельзя переписать это в «работает» по APK build result или по `SUI NEW`. Old Android OOM после ZIP3 исправлен в #257; на ZIP4 повторения нет, но длительность тестирования недостаточна.

## 4. Инварианты на реальной границе сделки

Никакие дальнейшие оптимизации не вправе менять эти правила:

1. Только Binance USDⓈ-M **Demo/Testnet** и новые TC2 сделки; LONG/SHORT, H1 decision, M15 только мониторинг.
2. Новые входы прекращаются **независимо** при суточном убытке **≥3%** или **трёх подряд** биржево подтверждённых убыточных закрытиях; предыдущая выигрышная/нулевая сделка обнуляет серию до первого тройного достижения. Уже достигнутый дневной trip защёлкивается до проверенного следующего UTC-дня.
3. Не расширять лимит маржи **50%**, исходный конструкционный SL, максимальный риск сделки, общепортфельный 0.6%, и отдельный emergency breaker 5% для «улучшения частоты».
4. UNKNOWN exchanges, latency, fees, STOP identity, partial fills и foreign asset margin — **fail closed для НОВОЙ экспозиции**, но сохраняется защита/управление существующей.
5. Не повторять POST после неопределённого результата; idempotent signed GET reconciliation только по точному client id/order id; не снимать прежний STOP без подписи нового.
6. `main` не меняется; новые audit commits в отдельной ветке; до реальной независимой Demo приёмки — DRAFT, не разрешать live funds.

## 5. Практическая приёмка, прежде чем говорить «полностью исправлено»

- **Exact-head CI:** Gradle Kotlin unit suite, Python unit suite, lint, build installable signed debug APK, code-source parity. Логи и SHA сборки должны совпадать с финальным PR head.
- **Transition matrix:** LONG/SHORT × STOP/LIMIT/MARKET × NEW/PARTIAL/FILLED × cancel/timeout/HTTP 451/429/5xx/unknown POST × process kill/network loss/Doze/reboot. Для каждого FILLED требовать подписанный актуальный STOP `symbol, side, positionSide, algoId, workingType, trigger, quantity/closePosition`.
- **STOP replacement:** проверить новый активный SL до удаления предшественника; concurrent gap-fill и mark price crossed; возобновление после crash.
- **Risk replay:** три подтверждённых LOSS; LOSS-WIN-LOSS-LOSS; тройка затем WIN; UTC midnight; вчерашний вход закрыт сегодня; missing fee/amount/timestamp; изменение account identity.
- **Liveness:** p50/p95/p99 по фаза подписанного GET/нового POST/stop; долгий reconcile с работающими WS; 24 часа Samsung Doze; watchdog post-restart с актуальными signed positions; heap maximum при ZIP/15 chart и private event burst.
- **Book fidelity:** слепая независимая разметка закрытых H1 и сопутствующих D1/H4 по четырём точным книгам, без future bars; сравнение решения с фактическим TC2 intent.

## 6. Статус исправления

В этой ветке реализован **только** доказуемый кодовый дефект **F-01** с тестами. F-02…F-08 — описанные независимые проверки и архитектурные ограничения; **не** считать их исправленными одним изменением. Никаких Binance POST, реальной торговли или автоматического merge этот аудит не делал.

**Состояние release:** HOLD; требуется зелёный exact-head CI, далее Samsung + Binance Demo FILLED→STOP обеих сторон. 
