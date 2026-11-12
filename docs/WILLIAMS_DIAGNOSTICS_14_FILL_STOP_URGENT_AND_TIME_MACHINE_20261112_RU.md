# Williams Bot — диагностика №14, FILLED→STOP и исследовательская Time Machine

**Инцидент:** архив `Williams_Diagnostics (14).zip`, снимок 2026-11-12 13:10:25 UTC, `4.64.24-debug`.
**Граница изменений:** ветка `fix/nov12-diag14-urgent-fill-and-tc2-research` поверх PR #236, только Futures Demo; `main` не изменять без независимой приёмки.
**Приватный ZIP, сырые идентификаторы и ключи Binance не хранятся в GitHub.**

## 1. Подтверждённые наблюдения с телефона

- `diagnostic_triage.json`: 60 PASS, 3 WARNING, 15 NOT_VERIFIED, 0 FAIL по текущему снимку — это не сертификат защищённости предыдущих исполнений.
- `futures_fill_stop_timing.json`: BNBUSDT signed-source-independent private `FILLED` 09:13:28.509 UTC; первая наблюдаемая `ALGO_UPDATE NEW` 09:14:11.894 (43.385 с); `OPEN_PROTECTED` 09:14:17.925 (49.416 с). Корреляция **только по символу и последовательности**; это не подтверждённое время размещения STOP на стороне Binance.
- Отдельный BNBUSDT `FILLED` 10:23:42.874 UTC не имеет связанного события `OPEN_PROTECTED` в сохранённом ограниченном буфере; нельзя выводить отсутствие STOP из пропущенной записи.
- `android_background_health.json`: 3 неожиданных CRASH по истории Android, последний `OutOfMemoryError` 12:28:13.823 UTC; 5 завершений по обновлению APK не считать авариями. `battery_optimization_ignored=false`, исключение из экономии батареи должно быть разрешено пользователем в настройках Samsung.
- `futures_intent_stop_lifecycle.json`: LTCUSDT LONG STOP-entry 49.28, первоначальный SL 48.85, qty 3.315, ещё не исполнен, поэтому PROTECTION `NOT_SUBMITTED` закономерен. `liquidation_guard_status=NOT_VERIFIED` по ожидающей заявке НЕ доказывает безопасный риск после исполнения.
- В момент диагностики открытых позиций 0, поэтому `futures_exchange_stop_proofs.json` пуст: исторический STOP из этого не доказать.

## 2. Причинная цепочка и конкретная правка

**Исходная проблема в коде:** private Futures WS callback немедленно отзывал входную авторизацию, но событие `ORDER_TRADE_UPDATE FILLED` ставилось в общую `tradingEventLoop` очередь. Только `handleUserEventSerialized` запрашивал новую signed reconciliation. Если очередь занята SQLite/другими сериализованными задачами, защита запрашивается с дополнительной задержкой **ещё до любого REST**.

**Изменено:** строго структурированный `ORDER_TRADE_UPDATE` со статусом `FILLED`/`PARTIALLY_FILLED`, положительным суммарным исполненным количеством и символом незамедлительно вызывает `scheduleFuturesStreamReconciliation("PRIVATE_WS_FILL_URGENT", force=true)` прямо из private callback. Обычная записываемая обработка события остаётся в `tradingEventLoop`. Для этого же fill повторный запуск из сериализованного обработчика не вызывается.

**Сохранённые инварианты:** сообщение WS не создаёт STOP, не определяет владельца стратегии, не снимает Execution Door; постановка защитного STOP и подтверждение после `FILLED` по-прежнему подчинены исходной сериализованной `NativeFuturesCampaignCoordinator.reconcile()`, signed account/positions/algo id, свежести MARK и durable intent/write-before-network. Ошибки и неизвестный outcome остаются fail-closed. Не создаётся вторая заявка без доказательства.

**Ограничение:** это исправляет **очередь до начала reconcile**, но не доказывает устранение всей задержки 49.416 с. Нужен тайминг по сегментам callback → Execution Door → signed account → STOP POST → signed STOP ID confirmed. Для изменения сложной последовательности signed REST необходимы точные receipts, профилирование и тест на настоящем устройстве. Не маскировать проблему искусственным `PASS` при отсутствии доказательства.

## 3. Интеграция идей Day Trader Skill (только исследование)

Источник идеи: `Jakeschincariol/day-trader-skill`, `trader-timemachine/timemachine.py` (MIT). **Не копировали** реализацию биржевого сеансового bootstrap, не копировали Alpaca, Entry Timer или риск-движок в production.

Реализованы `tc2_time_machine_research.py` и офлайн-адаптер `tc2_time_machine_from_diagnostics.py`:
- Только закрытые и непрерывные H1 свечи из Android диагностического ZIP; **24/7** Binance, без американских торговых сессий.
- Seeded 10,000 повторений, ресемплинг **целых непрерывных 8-свечных блоков** (а не перемешивание отдельных баров), embargo 24 H1, горизонты до 24 H1 по умолчанию, ни одной будущей свечи.
- STOP-вход LONG/SHORT, консервативная двусмысленность одного OHLC бара, комиссии и проскальзывание; явные исходы STOP/HORIZON/UNFILLED.
- Не подменяет `williams_paper_replay.py` (полная историческая TC2 экономика со структурным трейлингом); `cpcv_backtester.py` остаётся отдельной purged-CV проверкой.
- Результат **не проходит** в `execution_barrier`, `risk_engine`, Android state machine, сигналы или биржевые операции. Ни одна текущая заявка не меняется.

Пример исследования ТОЛЬКО условного плана по последним H1 свечам архива (не проверка исторической сделки):

```bash
python tc2_time_machine_from_diagnostics.py \
  --zip "Williams_Diagnostics (14).zip" \
  --symbol BNBUSDT --side SHORT --trigger 670.53 --stop 690.69 \
  --replays 10000 --seed 19
```

**Нельзя** использовать уже поздний снимок 13:10 для предсказания сделанной в 09:13 сделки: это прямая утечка будущих данных. Для out-of-sample сравнения передать в модель только закрытые H1, доступные на момент первоначального сигнала, заморозить параметры и использовать поздние бары лишь для независимой оценки.

## 4. Корневой аудит связей и блокирующие задачи приёмки

| Слой | Статус | Требование |
| --- | --- | --- |
| Incoming WS → urgent wake-up | Изменение в этой ветке; регрессионный JUnit | Точный порядок callback/handler на Android, без изменения решающего signed gate |
| Execution Door / durable intent / STOP | Без изменения опасного кода | На Demo физически проверить FILLED → однозначный clientAlgoId → правильный active STOP по qty/side/symbol/price |
| Альтернативное происхождение 49 с (REST, clock, account) | Не установлено | Сегментные timestamps, подписанные REST расходы, запрет пропуска hard gate |
| Android heap / crash | PR #234, **не включён** в этот PR | CI+телефон после включения #234; OOM из архива не считать починенным |
| Новейший WM3 / старый BNB STOP-entry | PR #235 — родительский | Проверка на свежих закрытых H1 и реальных заявках |
| График STOP / фактический выход | PR #236 — родительский | Не путать журнал initial SL и реально выставленный STOP |
| Monte Carlo / CPCV / historical parity | Исследовательская утилита, не trading authority | Purged walk-forward без утечки, тест на замороженном независимом периоде |
| Signed Demo LONG и SHORT + Android screen-off/reboot | **НЕ ПРОВЕРЕНО** | Жёсткая блокировка распространения как «готового безопасного» APK |
| Android battery unrestricted | `battery_optimization_ignored=false` | Только пользователь может включить в настройках телефона |

## 5. Правила релиза

- Пройти Python pytest, Android JUnit/lint, exact-SHA CI и выпустить APK с постоянным **подписывающим сертификатом**. Не считать debug-подпись из случайного keystore пригодной для установки поверх существующей.
- Не сливать PR в `main`, пока не решены открытые зависимости #234–#236 и P0 signed Binance Demo FILLED → STOP.
- Не разрешать live-торговлю и не ослаблять signed freshness, risk caps или SL ради числа зелёных тестов.
