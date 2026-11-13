# Williams Bot 4.64.28 — LTCUSDT: выход, отсутствие SHORT и корневая атрибуция

**Источник:** `Williams_Diagnostics (17).zip` от 13.11.2026; предыдущий код 4.64.27, commit `f1a56023`. **Торговая политика:** Binance Futures Demo, TC2 ONLY; NTD/Course research. **Приоритет:** не объявлять стопом то, что невозможно доказать точной цепочкой ордеров.

## Факты

LTCUSDT LONG: вход 49.04, объём 2.693, закрытие 49.92, net +2.2632 USDT. Private WS последовательность 05:14:11 по Минску `ALGO_UPDATE TRIGGERING → ORDER_TRADE_UPDATE FILLED 2.693 → ALGO_UPDATE TRIGGERED / FINISHED`. Соответствие самого дочернего `ORDER_TRADE_UPDATE.o.i` конкретному clientAlgoId из выложенного архивного UI-среза ещё НЕ доказано. Журнал до этой правки записал `EXCHANGE_RECONCILED_CLOSE`; UI правильно отказывался уверенно приписать SL без exchange proof.

LTCUSDT SHORT: пользователь видит резкое снижение H1, но последний квалифицированный live TC2 `books` пуст. В snapshot `edition_observations.TC2` = 1 RAW формация, а `campaign_signals` содержат только наблюдательные NTD/Course; `campaign_ready=false`, новый SHORT не создан. NTD `SHORT_AO_ZERO_LINE` = ADD_ON (47.96, стоп 49.52), NTD `SHORT_ZONE_ADD_ON` = ADD_ON, не разрешают новый TC2 риск. Raw TC2 сигнал мог быть отфильтрован по времени, по развёртывающемуся H1 родителю, встречному сигналу/invalidated trigger; старый pipeline не экспортировал точную причину. Действующий `exchange_reconciliation_in_progress` — отдельный барьер, не доказательство допустимого SHORT.

## Исправления в коде — без изменения торговых приказов

### A. Защитный STOP: точная причинная цепочка

`NativeStopExitAttribution.kt` — чистый read-only верификатор, принимающий уже полученный **signed Binance `userTrades`**, сохранённые приватные `ALGO_UPDATE` и owner intent. Требуется одновременно:
- единственный действующий `protection_client_id` именно этого владельца; нет открытой STOP replacement неопределённости;
- `ALGO_UPDATE.o.caid` = exact owner ID, `o.ai` = CHILD биржевой `orderId`; тип `STOP_MARKET`, правильная сторона, статус `TRIGGERED` или `FINISHED`;
- **все** closing userTrades имеют один и тот же signed `orderId`, полностью равный child order ID; объём положительный, окно времени и symbol/direction совпадают.
- при пропуске, смешанном закрытии, неизвестном owner, отсутствующей WebSocket записи, разном ID — `NOT_VERIFIED`, не «скорее всего STOP».

`TradingAuditStore.recentPrivateAlgoExitEvents` читает не более 500 локальных event receipts около закрытия, возвращает null при потенциальной неполноте, не очищает финансовый журнал. `journalClosedNativeFuturesTrades()` теперь **не выбирает чужой EXIT той же пары**: exact `entry_client_id == campaign.client_id`, только полный `EXIT_CONFIRMED`. В `TradeJournal` сохраняется полный объект `exit_attribution`; лишь при точной цепочке используется `VERIFIED_PROTECTIVE_STOP_FILLED`; другие причины прежние, unknown остаётся unknown.

**Исторический LTCUSDT:** исправление ориентировано на записи с достаточными исходными owner+WS+signed userTrades, а не на переписывание задним числом всех сделок без доказательств. У ранее записанного `RECORDED` закрытия нет автоматического разрешения переписать неизвестную причину без нового signed детального аудита.

### B. Воронка «почему не SHORT» по TC2

В `h1ContextCampaignSignals()` собирается read-only `decision_h1_entry_funnel_{symbol}` на **той же закрытой H1-серии**:
`raw_edition_source → time eligibility → parent structure invalidation → later H1 opposition lifecycle → qualified TC2 candidate`.
Для каждого RAW сигнала сохраняются: ID, source, signalBarTime/knownAt, direction, роль ENTRY/ADD_ON, trigger, protectivePrice, wasQualified, точная категория отказа. Не меняет `return valid` и ни один risk/execution/STOP gate.

В `edition_observations` добавлена `evidence_scope=RAW_CLOSED_H1_BEFORE_PARENT_LIFECYCLE`, `books` — `QUALIFIED_H1_CONTEXT_AFTER_LIFECYCLE`, plus `raw_formation_count`, freshness и qualified count. Так `TC2 raw=1` / `eligible=0` больше не выдаётся за согласованный торговый сигнал. `WilliamsDecisionTraceCard` явно показывает причины непринятого SHORT по-русски и отличает наблюдение NTD/Course от live TC2.

### C. Дополнительные обнаруженные ошибки / адресованные последствия

- Статистика закрытия привязана к **правильному owner** вместо поиска `EXIT` только по паре и временному диапазону; частичный EXIT не становится причиной полного закрытия.
- Неизвестная историческая причина больше не маскируется приблизительным временем ALGO/ценой выхода. Данные о причине и цепочке ID сохраняются отдельно.
- Устранено смешение «сырой источник увидел формацию» с «H1 lifecycle разрешил торговый сигнал»; оба независимых этапа доступны в diagnostic ZIP и UI.

## Тесты

`NativeStopExitAttributionTest.kt`: exact owner/child/2 partial fills → VERIFIED; чужой stop, другой child, BUY вместо SELL, другой type, событие вне окна, две выходные заявки, незавершённая замена, отсутствие WS → NOT_VERIFIED. `TradeExplanationRuTest.kt`: русский proof и без ложного сигнала. `test_46428_h1_entry_and_stop_attribution_contract.py`: invariants live/observe, parent lifecycle and owner, protected exit contract.

## Нельзя утверждать до испытания на телефоне

- Единственный архив не подтверждает, что именно защитный STOP породил историческую LTCUSDT дочернюю заявку: требуется `ALGO_UPDATE.o.ai` ↔ signed `userTrades.orderId`. Старые сделки с неизвестным `exit_reason` без полного исходного proof не переписываются.
- Почему конкретный raw TC2 сигнал был отфильтрован на прошлой свече можно подтвердить только новым экспортом после обновления; система сохраняет точные **новые** события причин, не приписывает старым вымышленные.
- Успешный CI не подтверждает автономную защиту после `FILLED` в физическом Android Demo. Запрещены live/production exchange orders.
