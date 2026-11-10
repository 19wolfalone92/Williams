# Williams Bot 4.64.16 — цепочка P0/P1 и повторный аудит Samsung (10.11.2026)

## Доказательства из нового пользовательского архива

Файл: `Williams_Diagnostics (2).zip`; `source_revision=c6a83d469cb888732d343e984025cd99aed8ae59`; `4.64.15-debug`; Samsung SM-S918B / Android SDK 37; отчёт **2026-11-10 13:37:24.818 UTC**.

- 73 проверки: **53 PASS, 3 WARNING, 17 NOT_VERIFIED, 0 FAIL**. По сравнению с предыдущей диагностикой 13 проблем перестали наблюдаться, но «не наблюдается» **не** означает доказанного исправления.
- 15/15 утверждённых инструментов имеют свежие REST-котировки, закрытые H1/H4 и данные индикаторов. Сканер `READY`; SQLite `quick_check=ok`.
- `running=true`, `paused=false`, `execution_allowed=false`, `reconcile_required=false`. Координатор `RECONCILE_REQUIRED` **только во время активного** account-wide signed REST: `blocker=exchange_reconciliation_in_progress`. Не является основанием вручную открыть шлюз.
- Предыдущая подписанная сверка закончилась **13:34:55.915 UTC**. Её exchange snapshot был начат **13:34:30.437 UTC** (на момент архива ему **174,381 с**, норматив для входа 90 с). Новая сверка началась 13:37:10.317 UTC; к моменту снимка ей было лишь **14,501 с**, стадия `READING_OPEN_ALGO_ORDERS`; **нет доказательства зависшего потока**.
- Private WS подключён, `user_stream_sync_required=false`, `CURRENT_SOCKET_SIGNED_SYNC_PROVEN`. Последнее успешное socket-generation доказательство около 13:17:53 UTC; отсутствие пользовательских WS-событий само по себе не является ошибкой. Без новой signed REST-сверки это не заменяет свежий 90s safety fence.
- По снимку **нет открытых позиций и pending-ордеров**, значит initial protective STOP для LONG/SHORT и исполнение в Demo **не проверены**.
- Android FGS жив, wake-lock удерживается, heartbeat ~6,5 c, Java heap ~138,8 МБ / лимит ~268,4 МБ. Системная история по-прежнему содержит **две более ранние** аварии (в том числе OutOfMemoryError), но они были **до установки 4.64.15**. 24h endurance и reboot->signed proof НЕ VERIFIED.
- Упакованный ZIP ~5,1 МБ, несжатый `diagnostic_report.json` **30,99 MiB**; в 4.64.14 отчёт был >42 МБ. Оптимизация реально уменьшила сериализованную диагностическую нагрузку; это не доказывает отсутствие OOM.

## Пройденная цепочка и исправленные первопричины

### P0-01 — scanner starvation / стареющий signed account proof

`StandaloneRuntime.shouldScheduleIndependentFuturesMonitor` **безусловно возвращал false при `scanning=true`** — даже если на Binance уже существовала защищённая открытая позиция, прежний подписанный снимок стал старше срока или `RECONCILE_REQUIRED` требовал срочной сверки. Периодический H1 сканер — **P1, read-only**, но блокировал **P0 account/STOP**.

**Каскад:** длительный параллельный 15-символьный скан → native position manager не запускает REST → старение доказательства 90s → закрытие торгового шлюза / потенциально задержанное наблюдение фактического STOP. Конкретные 174 с в ZIP **согласуются** с этим механизмом, но архив без трассы каждой секунды **не доказывает**, что именно скан был единственной причиной.

**Исправление:** удалить `scanning` из условия безусловного отказа. Интервалы `READY=45s`, `OPEN_PROTECTED=3s`, `ENTRY_PENDING=2.5s`, `EXIT_PENDING=1.5s`, latch backoff и запрет параллельной account-wide сверки сохранены. `Execution Door` и stale signed gate по-прежнему **fail-closed**. Добавлен `FuturesSafetyMonitorPriorityTest`: при активном скане просроченный proof обязан инициировать safety supervision, но свежий proof и уже запущенная сверка не создают дубли.

### P1-02 — двойная копия полного исторического исполнительно-журнального JSON

`NativeFuturesCampaignCoordinator.intentsSnapshot()` делал `JSONArray(intents().toString())`, хотя `intents()` **уже возвращает новую JSON-модель из SQLite** `JSONArray(raw)`. Это создавало одну лишнюю полную копию истории при каждом UI/management/diagnostic запросе. Дублирование не добавляло атомарности или независимости, так как `intents()` и так создаёт новый объект.

**Исправление:** `intentsSnapshot() = intents()`; только одна SQLite-строка → один JSON parse → изолированная mutable snapshot. Durable source и модель исполнения не меняются; snapshot не может изменить persisted data без отдельного `writeState`. Source contract regression добавлен.

### OBS-03 — старый SQLite инцидент отображался как последний без возраста

`last_incident.json` в новом ZIP по-прежнему содержит **старый** инцидент из прежней истории, когда терминальные кампании ошибочно производили UI events. Поскольку `lastCockpitIncident()` возвращал только `raw_json`, экран/архив не мог отличить историческую запись от свежего Binance proof.

**Исправление:** не удаляя forensic evidence, добавить в возвращаемый объект `stored_incident_at_ms`, `stored_incident_age_ms`, `source_scope=HISTORICAL_SQLITE_INCIDENT_NOT_LIVE_BINANCE_PROOF`. История сохраняется, не используется для получения новых прав на торговлю/STOP и явно размечается по времени.

### OBS-04 — нет разреза времени по фазам coordinator reconcile

Один общий интервал signed REST (например, около 25 секунд прошлой успешной сверки) не объясняет, где именно прошла задержка: server time, account, positions, standard orders, algo orders, historical flat STOP settlement, Course replay, SQLite observer.

**Исправление:** добавить компактную нетаинтующую risk authority телеметрию `reconciliation_phase_elapsed_ms` и `reconciliation_phase_durations_ms` с таймингами в будущем ZIP; timestamps актуальной фазы и фаз завершённой сверки не используются как safety proof. Цель — отличать I/O Binance от CPU/SQLite-истории и запрещать выводы «зависло» на основании только одного снимка.

## Незакрытые проверки

- Реальное подписанное начальное STOP при исполненном Demo LONG и SHORT, ID и количество; handling partial fill; 24h с экраном off, разрыв сети и reboot, полный signed reconnect.
- Причина 174-секундной старости доказательства из **последовательности** нескольких будущих снимков и таймингов фаз. Данные снимка не доказывают, что coordinator hang >300s.
- Исторический java OOM требует нового процесса на свежей версии, heap monitoring и статус OS process exits после установки, не переноса обвинения из старых архивов.
- `main` и live account — не менять без принятой подписанной Binance Demo приёмки. Кандидат 4.64.16 — **HOLD**, изменения на уровне orchestration/performance/diagnostics, stop/TC2/risk/плечо/ключи неизменны.

## Релизные критерии CI

Android lint, unit tests, Python backend safety pytest, campaign CI, APK SHA256/signature, API 35/36 emulator UI/lifecycle — должны пройти на итоговом SHA ветки. Если runner выдаёт публичный Binance Demo HTTP 451, не подменять источник и не выдавать его за устройство.
