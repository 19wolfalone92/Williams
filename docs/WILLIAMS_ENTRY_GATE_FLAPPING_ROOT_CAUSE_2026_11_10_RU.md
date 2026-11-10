# Williams Bot 4.64.18 — устранение мигающего допуска входов и истечения signed REST перед ENTRY

## Доказательства, не предположения

Источник: реальный `Williams_Diagnostics (5).zip` с Samsung SM-S918B, версия `4.64.17-debug`, снимок **2026-11-10 16:10:02.800 UTC** (19:10:02.800 Минск).

- `status.execution_enabled=true`; `execution_gate.ready=true` и `execution_gate.reasons=[]`.
- `futures_campaign_coordinator.state=READY`, `ready_for_new_entry=true`; нет открытых позиций, pending и заблокированного риска.
- `cockpit_readiness.technical_ready=true`, `execution_door_ready=true`, но `new_entries_allowed=false`, `new_entries_reason="Сканер: Работает"`.
- Сканер `RUNNING`; последний завершённый анализ был около 45 секунд назад, соответствует его 120-секундной UI свежести. Нативный шлюз по-прежнему разрешён.
- `COCKPIT_ORDER_RESPONSE` по XRPUSDT SHORT_SUPER_AO около 16:22 Минск записал `FUTURES_ENTRY_CONTEXT_REJECTED: Futures entry was not submitted: SIGNED_RECONCILIATION_STALE_BEFORE_ENTRY`; непосредственно перед этим есть `COCKPIT_SIGNAL_RISK` и неоднозначный `SUBMISSION_UNKNOWN`, после был `REJECTED/CLOSED_OR_STOPPED`. ЭТО не подписанная сделка и не доказательство исполнения.
- В истории есть и отдельные сообщения `Futures server clock probe failed; signed request blocked`; эти прошлые транспортные сбои не исправляются простым отключением 90s флага и могут продолжиться при настоящей ошибке сети.
- 73 самотеста: 55 PASS, 1 WARNING (история Android с прежней аварией), 17 NOT_VERIFIED, 0 FAIL. Нет действующего STOP-подтверждения для исполненного нового Demo LONG/SHORT.

## R18-01. Ошибка UI: кратковременный реальный READY выглядел как почти постоянный запрет

`CockpitDiagnostics.readiness` использовал `scanner_state == READY` как составную часть `new_entries_allowed`. Каждый новый цикл чтения 15 H1-комбинаций меняет состояние на `RUNNING`, при этом `status.execution_enabled` и `coordinator.ready_for_new_entry` могут оставаться true. Результат — красный индикатор, хотя нет оснований объявлять новый запрет реального исполнительного шлюза.

Изменено: **только отображение** — допуск берётся из настоящего `execution_enabled`, подписанного coordinator, режима/связи, паузы, защиты и свободного резервного риска. Отдельно экспортируются `scanner_analysis_state`, `scanner_currently_running`, `scanner_last_complete_analysis_fresh`. **Ни одного нового пути вызова Binance POST, GET, STOP или изменения торгового разрешения UI не появилось**. Сигнал по-прежнему обязан пройти полный H1 анализ и все последние проверки перед Binance.

## R18-02. Техническая блокировка: TTL 90 секунд истекал во время долгого кандидата

`beforeExposureTransport` внутри Binance адаптера строго проверяет возраст подписанной account/positions/STOP сверки — максимум **90 секунд по времени НАЧАЛА снимка**. Это обязательная безопасность. Сканер может сформировать TC2-кандидат, сделать H1/H4/D1, AO/Teeth, daily equity, mark/contract price, фильтры объёма/риска и перейти в `withCampaignMutation`, когда прежний signed proof уже истёк. Адаптер корректно отклонял заявку `SIGNED_RECONCILIATION_STALE_BEFORE_ENTRY`.

Изменено: для уже обнаруженного кандидата, если осталось **45 секунд или меньше** до истечения исходного 90-секундного signed proof, запускается НОВАЯ сериализованная подписанная сверка **вне Execution Door**, с сохранением единственного in-flight владельца и запретом при `paused/reconcile_required/kill/unsynced WS`. После всех дорогих префлайтов — вторая проверка резерва TTL перед бронированием Door. Нет свежей биржевой сверки → entry остаётся запрещённым.

Если при финальной повторной проверке H1 **внутри Door** подписанный снимок всё же истёк, ещё ДО отправки заявки возвращается `safe_rejection=true, mutation_attempted=false`; Door освобождается, выполняется отдельная актуальная подписанная сверка и запрашивается быстрый **новый полный H1 скан**. Предыдущий уровень не отправляется повторно вслепую. Только свежие подписанные данные + всё ещё действительный H1 сигнал могут привести к отправке ордера.

Исходный `futuresExposureTransportBlocker` и лимит **90 секунд не изменены**, WebSocket generation, STOP/position owner, daily risk 3%, portfolio risk 0.60%, TC2 entry stop-only, Binance Demo также не ослаблены.

## Проверки

- Kotlin UI fixtures воспроизводят телефон `RUNNING` / последнее `READY` 45 с назад / `execution_enabled=true` без ложной красной лампы.
- В тех же тестах `execution_enabled=false`, active account reconciliation, `reconcile_required`, private-stream unsynced и исчерпанный риск оставляют запрет.
- Kotlin TTL unit: 44,999/45,000/90,001 мс; строгий signed `beforeExposureTransport` отвергает 90,001 мс, `deferrable` предвходной proof не может потеснить активную подписанную сверку.
- CI Android lint/unit, Python safety suite, APK signature+hash, Android API35/36 emulator должны завершиться на точном последнем SHA до объявления release-кандидата готовым.

## Ограничения и честность

Снимок НЕ показывает исполненный Binance Demo STOP после LONG/SHORT, поэтому **не подтверждает автономную безопасную торговлю**. Хостинговые GitHub Actions могут получить HTTP 451 на Binance Demo, что не означает ошибок локального Android пользователя. Нельзя заявлять, что сеть гарантированно исправлена: signed clock probe может законно оставаться fail-closed при недоступности Binance.
