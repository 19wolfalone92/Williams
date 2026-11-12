# Williams Bot 4.64.27 — корневой P0/P1 аудит и безопасный проход оптимизаций

**Дата:** 2026-11-12. **Репозиторий:** `19wolfalone92/Williams`. **Целевая база:** кандидат 4.64.26, PR #237. **Торговый контур:** только Binance Futures Demo, 15 инструментов TC2. **Авторитет:** живое signed Binance REST и долговечный Execution Door, не данные UI и не это исследование.

## Объём проверки и исходные наблюдения

Изучены `StandaloneRuntime.kt` (~13.3k строк), `NativeFuturesCampaignCoordinator.kt` (~5.6k), `MainActivity.kt`, `CockpitScreens.kt`, `BinanceRateGuard.kt`, `BinanceUsdMFuturesTestnetAdapter.kt`, `TradingEventLoop.kt`, `TradingAuditStore.kt`, `MarketHistoryStore.kt`, `FuturesExecutionStore.kt`, `TradingForegroundService.kt`, `BinanceUserDataStream.kt`, `DiagnosticsArchive.kt`, история PR #230–237 и автоматических CI. Исходный файл диагностики #14, снятый с **4.64.24-debug**, является историческим наблюдением, не замером новой 4.64.27.

Из #14: H1 скан 15 символов ~27.16s; account-wide signed reconcile ~10.13s; наиболее дорогой этап open ALGO orders ~4.72s; Java heap snapshot ~33.25%, после отчёта ~42.8%; HTTP weight ~5% от лимита. Для BNBUSDT после private FILLED через 49.416s наблюдалось `OPEN_PROTECTED`, но первичное authenticated время принятия защитного STOP не доказано. Ранее зарегистрирован OOM и Android battery exemption=false. Версия 4.64.26 добавила срочное пробуждение signed reconciliation — **время до STOP после установки 4.64.26/27 на телефоне не измерено**.

## Исправлено в этом единичном интеграционном проходе 4.64.27

| ID | Подтверждённая корневая причина | Фактическая правка | Неизменный торговый инвариант |
|---|---|---|---|
| RATE-01 / P0 | `BinanceRateGuard.observe`: `@Volatile cooldownUntilMs = max(old,new)` — read/modify/write без синхронизации; одновременный 1s Retry-After мог перезаписать 180s; `seconds*1000+now` мог переполнить Long | Единый lock и saturating timestamp arithmetic; JUnit contention+overflow regression | 429/418 никогда не позволяют запрос раньше установленного cooldown |
| UI-01 / P1 | Пятитисекундный `/cockpit` на главной/сделках создавал 15 `decisionTraceSnapshot()` и SQL/JSONArray журналы, не используемые этими экранами | Добровольный `?compact=true`; market/analysis и стандартный API остались полными | UI-проекция не участвует в разрешении заявок; signed STOP статус сохраняется |
| UI-02 / P1 | `scannerSnapshot()` сериализовал глубокие кандидаты с многокилобайтным `decision_trace` даже для главного экрана | Read-only компактный кандидат по **всем** 39 полям реально читаемым `parseCandidates`; сверено прямым разбором UI | Полный scanner/diagnostic source остаётся доступным; нет молчаливого изменения score/signal |
| UI-03 / P1 | Однократная загрузка экрана market/analysis могла проиграть гонку с предыдущим компактным refresh из-за `tryLock` | При открытии подробного экрана `loadAll(waitForLock=true)`; периодический polling как прежде skip-when-busy | Не заменять отсутствие доказательств на готовность |
| SCAN-01 / P1 | Часть H1 scanning tasks выполняла наблюдательное shadow-исследование в критическом 60s scan budget, включая отдельный обмен временем и SQLite | Shadow вынесен в самостоятельный bounded 1-worker executor с de-dup `SCANNER_SHADOW:symbol`; удалён из H1 анализа | Нет новых входов, выводов, смены STOP, автоматической статистической фильтрации |
| WS-01 / P1 | `indicatorExecutor` получал работу на каждый формирующийся kline, включая новые high/low с тем же `openTime`; прежняя проверка только `openTime` позволяла старому результату переписать актуальный | Не более одного незавершённого indicator rebuild для каждой пары/frame; generation-aware выбор актуального результата и catch-up/requeue с ограничением 2 расчёта за исполнение | Все события закрытия свечи независимо продолжают `historyStore.upsertBatch` — никакой loss/skip для H1 source |
| DEPTH-01 / P2 | Буря неверных L2 incremental depth update IDs могла запускать неограниченные `Thread` с REST `syncOrderBookSnapshot` | Добавлен per-symbol singleflight + `finally` cleanup, максимум 15 одновременно, наблюдательный счётчик очереди | Стакан — только рыночное наблюдение; на signed STOP это не распространяется |
| EVENTS-01 / P0 наблюдаемость | Неограниченная очередь `TradingEventLoop` не имела наблюдаемого queue-depth / high-water | Эквивалентный однопоточный `ThreadPoolExecutor`; queue size и peak в `runtime_resource_diagnostics.json`; тест последовательности/сохранения 90 событий | **Не** вводить `DiscardOldest`, `CallerRuns` или принудительное удаление private FILLED без durable spool |
| VER-01 | Неоднозначная версия/сборка | VERSION, Gradle versionCode/versionName и server.VERSION согласованы до 4.64.27 | APK только прежний debug signer через `Build Android APK`; нет live-enabled изменений |

**Повторная локальная проверка:** все 39 используемых `parseCandidates` ключей сопоставлены один-к-одному с полями компактного кандидата. Полная функция `status(fast=true)` в обычном API и default `/cockpit` сохраняет `decision_traces`; только явный флаг исключает их.

## Идентифицированные остаточные архитектурные риски — НЕ объявлять «исправлено»

1. **Signed STOP latency — P0.** `reconcile()` делает `serverTime → one-way → account → positions → standard orders → algo orders → state operations → final positions`, в некоторых случаях много повторных signed вызовов. **НЕ** сокращать доказательства, менять order mutation или обходить lock на основе одного архива. Нужны сегментные timestamps `WS → Door → signed position → STOP POST → exact signed algo ID` на телефоне и строгая модель idempotence.
2. **Intent ledger — P0/P1.** JSON-массив в `FuturesExecutionStore.runtime_state` читается `JSONArray(raw)` и целиком переписывается `saveIntents()`; много мест вызова в координаторе. Миграция на нормализованные SQLite строки **НЕ выполнена**. Это потенциальная потеря/рассинхронизация стратегии при неатомарном переключении; сначала read-only dual comparison, sealed export, shadow checks, затем атомарная миграция и rollback.
3. **Безлимит private event queue — P0.** Нельзя задавать жёсткий лимит и сбрасывать private order events, пока нет долговечного spool и signed replay. В этой версии добавлены только измерение и сохранение исходной однопоточной очереди.
4. **SQLite audit scaling — P1.** `TradingAuditStore.events` хранит критическую историю бессрочно; выборки по event_type и выборке кампании могут читать много старых записей. Ретенцию financial/STOP receipts запрещено уменьшать без независимого неизменяемого архива. Требуются миграция индексов и план быстрого неблокирующего database upgrade.
5. **Diagnostics — P1.** Даже после 4.64.26 дедупликации отчёт объединяет крупные JSON деревья до записи ZIP. Стриминг истории частично есть, но сквозной streaming по подсистемам не завершён. Нужны parity/CRC тесты для воспроизводства прежних файлов.
6. **Android lifecycle — P0.** Нельзя программно включить `Unrestricted battery` на Samsung; устройство может закрывать процесс. Решение — физическая настройка пользователя + длительные экран-выключен/смена сети/перезапуск испытания.
7. **Order Book resync threads — P2 mitigated.** В этом PR внедрён per-symbol singleflight; регрессионный static source test есть. Стресс с десятками параллельных faulty L2 updates на настоящем Android НЕ выполнен.
8. **Full H1 backfill — P2.** 6 workers, 15 symbols и 12 длительных TF; возможны большие очереди, но ускорять только с ограничениями диска, подписанных REST и без потери closed H1.
9. **Chart 6000 candles/30s — P2.** Polling целого массива вместо инкрементальных diff; оптимизировать только на доказанной `market_history_revision` и с явным invalidation при перезапуске/смене TF.
10. **TC2 статистика — research.** `tc2_time_machine_research.py` не меняет торговлю; edge/profit не доказаны. Нельзя оценивать прошлую сделку по поздней диагностике и допускать lookahead.

## Обязательные этапы приёмки перед main/live

1. **CI на точном SHA:** `python -m pytest -q`, `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, `ci_release_gate.py`, подписанный artifact. При красном CI исправить причину и повторить; не снижать тесты.
2. **Сравнение 4.64.26 / 4.64.27:** одинаковые закрытые H1 на всех 15, побайтово эквивалентный сигнал/направление/trigger/stop/risk, отсутствие новых entry authority у research. Время/CPU/heap p50/p95: скан, Cockpit, REST Door, JDBC/SQLite, indicator queue.
3. **Signed Demo LONG и SHORT:** partial+full FILLED, exact owner, existing/missing STOP, POST-accepted-lost-response, -2021, out-of-order WS, restart/screen-off/LTE→WiFi, no duplicate exposure, fail-closed.
4. **Контроль Android:** `ApplicationExitInfo` OOM, ограничения энергосбережения, размеры и peaks queues, сертификация signed lifecycle по Binance. Никакой `PASS` при `NOT_VERIFIED`.
5. **Безопасное развёртывание:** draft stacked PR, тест exact commit, никогда напрямую `main`, сохранение прежнего подписывающего сертификата. Реальные средства не разрешены.

**Итог:** данный проход устранил конкретную математическую гонку cooldown и облегчает UI/скан/индикаторы, не переписывая финансовый state machine. **Это не доказательство полного исправления всех возможных ошибок и не разрешение для live-трейдинга.** Подтверждение STOP и миграция финансового журнала остаются отдельными safety-critical задачами с физической приёмкой.
