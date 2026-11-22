# Binance: границы проведённой проверки

**Signed Demo E2E: NOT_RUN. Physical Android E2E: NOT_RUN.** Пользовательские
средства, API keys, секреты и настройки аккаунта не использованы и не изменены.
Не получены authenticated receipts о размещении/исполнении/защитном STOP.

Проверенные code/test области baseline: Futures endpoint pinning, unknown
POST handling, `order_safe` query-before-retry, ExecutionBarrier intents,
actual position risk reservations, pending risk, partial fills, protective
STOP replace/new-before-cancel, daily3% и UTC baseline, restart/reconciliation,
source-bound orders и signed exit attribution. Все2086 baseline offline tests
прошли; это не независимая инспекция каждой строки coordinator и не биржа.

Подтверждённые официальные контракты на дату проверки:

- [USD-M Trade API](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/trade):
  conditional algo имеет свой clientAlgoId; Query Algo Order отдельно от
  обычного order; actualOrderId появляется после trigger. Наличие pending
  entry STOP не доказывает защиту исполненной позиции.
- [USD-M Market Data](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data):
  kline содержит отдельно volume, quote volume и number of trades. Это не
  прямое свидетельство исходного TC1 количества изменений цены.

При неизвестном результате запретить новое добавление риска и восстановить
сверку. Существующие STOP сохранять до безопасного подтверждённого replacement;
не очищать по одному символу без ownership. После FILLED нужна доказанная
защита фактического объёма, а не локально рассчитанная цена SL.

Открытый P0 базовой ветки: реальная длительность FILLED→owned STOP на устройстве
не доказана. Уже существующие исправления urgent reconciliation / exact exit
binding сохранены. Приёмку по этому пункту нельзя закрыть зелёным APK CI.

Историческая политика 4.64.30 разделяла NEW risk10 и management15,
однако она **заменена** пользовательским требованием NEW15/management15.
Текущие `williams_symbol_policy.py`, `WilliamsSymbolPolicy.kt`,
`tests/test_new_risk_symbol_policy.py` подтверждают статическую реализацию
15/15. Существующие pending/unknown остаются обязательствами, их резервы
не освобождаются сменой допуска; STOP/reduceOnly продолжают сопровождаться.
Первая подписанная Demo/device приёмка каждой пары **не выполнена**.

## QEP v1.0 — независимая кодовая контрпроверка от 22.11.2026

**Scope и контрольная точка:** `19wolfalone92/Williams`, унаследованный
PR #264 SHA `f9e263b0ac6f477d57f3c98a0798f1efe15c5ee9`,
текущий stacked draft PR #265, `main` `1235a02773b178af64aa909880612c77bdea98d2`.
Исходный код тестировался через fake signed HTTP; никакой signed Demo/account
write, реальной передачи ETH STOP или физической Android проверки не было.

**P0 находки, воспроизводящие контрпримеры, исправления:**

1. **Неполное обнаружение**. До PR265 кандидат ручного `priceProtect=true`
   STOP находился лишь при исторически противоположной позиции. Для обычного
   signed ETH LONG с таким STOP запрос к handoff-кандидатам давал пусто,
   хотя Binance сообщал позицию и SL. Исправление:
   `signedManualPriceProtectedStopHandoffHints` для всех 15 разрешённых
   позиций по общему подписанному снимку account positions + algoOrders,
   отдельная свежесть и повторный подписанный order-book/position preflight
   **до POST**. Тест `auditP0SameDirectionUnownedSignedManualStopIsDiscoverableWithoutHistoricalReversal`.

2. **Постоянная блокировка после DONE**. Durable state одного `symbol`
   не позволял мигрировать STOP новой сделки с тем же `symbol`.
   Исправление: при новой уникальной signed позиции и manual STOP
   сначала архивировать прежнюю `DONE` под собственной durable identity
   с запретом конфликтующего overwrite, затем открыть новое состояние.
   Тест `auditP0DoneHandoffCanRearmLaterDifferentSignedPositionSameSymbol`.

3. **Опасная идентичность spawned child**. Старый `STOP_MARKET` со
   статусом `NEW`, но ненулевым `actualOrderId`, принимался за resting
   и мог быть отменён. Исправление: точное signed
   `actualOrderId` только `0`/пустое, включая в fingerprint для
   account/scoped согласованности. Тест
   `auditP0ManualStopWithSpawnedChildCannotBeMigrated` и
   `SignedManualHandoffHintsTest`.

4. **Голодание других символов**. `pendingManualHandoffs.take(1)`
   постоянно выбирал первую неудачную пару и не переходил к следующим.
   Исправление: bounded round-robin + `FuturesManualHandoffSchedulingTest`.
   Это справедливая очередность, **не** гарантия скорости REST/STOP.

5. **Недостаточное подтверждение нового SL**. Хватало
   `isFullPositionFuturesStop` для fixed `reduceOnly` с полной текущей
   `qty`, хотя handoff специально требует future-proof `closePosition=true`.
   Исправление: точное `closePosition=true`, не reduceOnly,
   `MARK_PRICE`, не защищённый `priceProtect` STOP до отмены ручного.
   Тест `auditP0ManuallyProtectedStopMustNotBeCancelledAgainstWeakFixedQuantityReplacement`.

6. **Аварийный kill switch оставлял открытые позиции без мониторинга**.
   Старый `NativeEngine.kill()` немедленно отключал worker, WS и persisted
   `auto_run` *до* подписанной попытки закрыть всё. Если текущий Binance
   LONG/SHORT не был подтверждён как стратегия Williams, аварийное
   рыночное закрытие намеренно не выполнялось; позиция и STOP оставались
   на бирже, но бот уже не пытался восстановить сеть/сопровождение.
   Исправлено: `signedFlatForMonitoringShutdown()` требует две отдельные
   подписанные account-wide проверки flat, полное отсутствие обычных и
   условных заявок и стабильность снимков. Если flat не доказан,
   `kill_latched` и блокировка новых входов остаются, но запускается
   **monitor-only** REST/WS supervision и сохраняется auto-run lease для
   проверки после рестарта. Тест `auditP0KillSwitchMustNotStopMonitoringUntilTwoSignedFlatSnapshotsAndNoOrders`.
   Временное окно между аварийной остановкой и восстановлением мониторинга
   остаётся для физического E2E; это НЕ гарантирует непрерывность при сбое
   сети или Android OS.

7. **Определённое отклонение Binance STOP**. Коды `-4130` и
   `-2021` означают отказ на указанном POST, а не успех.
   После отклонения запрещено удалять единственный ручной SL или отправлять
   тот же конфликтующий STOP каждую секунду. Durable фаза
   `POST_DEFINITIVELY_REJECTED`, исходный STOP сохранён, новых
   входов/снятия HOLD нет. Тест
   `auditP0BinanceDefinitiveStopConflictRetainsHumanProtectionWithoutRetryStorm`.

8. **Нестабильность проверки WS**. На историческом CI SHA
   `bc858c72fe88ef882727cf65b374929a85cec633` 1/1399
   unit tests падал в `BinanceUserDataStreamTest` с
   `ConcurrentModificationException`, из-за одновременной callback
   записи/итерации synchronizedList без synchronized iteration.
   Исправлена синхронизация тестового снимка; это ошибка test harness,
   а не доказанная порча production private events.

**Обязательные незакрытые gates:** точный CI всей финальной ветки,
реальный подписанный Binance Demo LONG/SHORT FILLED→SL,
сервис/reboot/Doze/WS outage/POST timeout/DELETE timeout на физическом
Android, независимая вторая экспертиза, независимый review полного корпуса
книг. Отдельные REST операции входа и SL **не атомарны**: даже после
успешных offline тестов окно без доказанной защиты возможно.
Fail-closed HOLD до подтверждения. Отдельно нужно проверить, что
трёхубыточная серия сейчас вычисляется **в рамках UTC-дня**:
`evaluateFuturesLossStreak` фильтрует закрытия с `closed_at>=dayStart`.
Если правило пользователя «3 подряд» должно охватывать переход через полночь,
эта граница остаётся семантическим расхождением, а не сертифицированным PASS.
До прояснения/независимой проверки нельзя выдавать всю risk-модель за
буквально подтверждённую.
