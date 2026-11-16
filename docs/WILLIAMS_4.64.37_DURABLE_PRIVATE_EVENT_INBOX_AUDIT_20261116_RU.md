# Williams Bot 4.64.37 — долговечный inbox приватных Futures events и контрольный аудит

Дата: 16.11.2026  
Рабочая ветка: fix/nov16-durable-private-event-inbox-4.64.37  
База: кандидат 4.64.36, PR #242, commit c2d815de1a8c75afea4537fd807285acea9a223f  
main на момент работы: 32e9dd64fab9e0488ea4b566347246ac44627199 (VERSION 4.64.18)

## 1. Что изменено в кандидате 4.64.37

### 1.1 Durable private Futures inbox

В TradingAuditStore добавлена схема SQLite v7:

- исходное WebSocket-событие и запись PENDING inbox добавляются одной SQLite-транзакцией;
- raw JSON остаётся в append-only таблице events;
- отдельная private_user_event_inbox содержит account fingerprint, event fingerprint, статус, число повторных попыток и временные отметки;
- fingerprint API key вычисляется SHA-256. Сам API key/secret в новую таблицу не записывается;
- одинаковый raw payload для одного account fingerprint дедуплицируется;
- pending события выбираются по возрастанию event_id;
- PENDING переводится в PROCESSED только после успешного serialized handler;
- при ошибке обработчика запись остаётся PENDING, сохраняет причину и счётчик попыток;
- схема добавляется как при создании новой базы, так и при upgrade существующей базы. Старые таблицы и финансовые записи не очищаются.

Это даёт локальную модель доставки at-least-once, а не exactly-once. Повторная обработка безопасна только для уже идемпотентных операций журнала; финансовое состояние по-прежнему подтверждает signed Binance REST.

### 1.2 Устранение неограниченной очереди Runnable на каждое WS-событие

Private WS callback больше не ставит отдельный Runnable на каждое событие. Он:
1. немедленно отзывает свежесть user-stream и тем самым блокирует новые входы;
2. записывает событие и PENDING receipt в SQLite;
3. немедленно запрашивает signed REST reconciliation для FILLED/частичных исполнений и account/order/protection updates;
4. будит один coalesced serialized inbox drainer.

Один actor drain читает inbox пакетами до 64 событий в порядке получения. После рестарта runtime снова поднимает pending события. Fill-событие, обнаруженное при replay, повторно будит signed reconciliation. WS payload не является разрешением на POST/DELETE, не устанавливает владельца позиции и не является доказательством активного STOP.

При ошибке записи inbox или потере account identity торговая свежесть отзывается, новые входы остаются заблокированы, запрашивается signed reconciliation. При устойчивой ошибке обработки нет немедленного бесконечного цикла retry: событие сохраняется PENDING и будет повторно проверено при новом receipt или следующем старте runtime.

### 1.3 Диагностика

В runtime resource report добавлены:
- pending/processed count;
- pending count для другого account fingerprint;
- возраст старейшего PENDING;
- число попыток повторной обработки;
- размер/peak RAM actor queue;
- признак запланированного drain.

Resource diagnostics теперь отображает backlog inbox и предупреждает об очереди/возрасте/другом account fingerprint. Отсутствующая inbox evidence обозначается NOT_VERIFIED, а не PASS.

## 2. Что этим НЕ объявляется исправленным

1. **Это не подтверждение безошибочной доставки Binance.** Событие может не дойти до callback, а на постоянном storage failure callback не сохраняет его. Runtime обязан оставаться unsynchronized и опираться на signed REST reconciliation и подписанную историю исполнений.
2. **Дисковая квота и компактация не реализованы.** Inbox и append-only event history остаются долговечными; рост диска, retention и восстановление после заполнения диска требуют отдельного проекта. Старые raw events не удаляются автоматически.
3. **Intent ledger не мигрирован.** futures_campaign_intents_v2 всё ещё хранится как JSON-массив в runtime_state и целиком читается/перезаписывается. Новый inbox не является заменой campaign intent ledger.
4. **Непрерывность FILLED → защитный STOP не доказана.** Нужны точные биржевые order/algo IDs и segment timestamps от fill до действующего STOP.
5. **Signed Demo LONG/SHORT, partial fill, replacement STOP, screen-off, Doze, потеря сети, процесс kill, reboot и обновление APK не приняты на физическом Android.**
6. **main protection не включена.** GitHub API при аудите возвращал protected=false и пустой required-status-check set. В доступном GitHub API-коннекторе есть read, но нет write endpoint для настройки branch protection/rulesets. Изменение необходимо выполнить через GitHub repository Settings или поддерживаемый административный API.
7. **Независимая экспертная разметка четырёх книг и графиков не создаётся программным тестом.** Она остаётся отдельным human review gate.

## 3. Риск-параметры: сверка Python / Android

В текущих исходниках кандидата совпадают следующие верхние пределы:

| Параметр | Python | Android |
|---|---:|---:|
| Initial entry risk cap | 0,20% | 0,20% |
| Maximum risk per entry | 0,25% | 0,25% |
| Campaign / portfolio cap | 0,60% | 0,60% |
| Daily loss cap | 3,00% | 3,00% |
| Concurrent campaigns | 10 | 10 |
| LONG / SHORT | enabled | enabled |
| Default leverage | 2x | 2x |

TC2 pyramid policy 1:5:4:3:2 резервирует initial unit через формулу min(initial cap, campaign cap / 15, entry cap / 5). При параметрах 0,20%, 0,60%, 0,25% фактическая начальная единица ограничена **0,04%** (0,60% / 15), а 0,20% является верхней границей, а не фактически используемым initial unit risk. Python и Kotlin используют одинаковый принцип. Это engineering risk-budget для пропорции единиц, а не формула риска из книги Вильямса. Увеличивать initial unit без повторного расчёта суммарного риска кампании нельзя.

Дневной лимит 3% оставлен: он зафиксирован как текущая проектная политика в AGENTS.md и release contract. Никакие лимиты в этой работе не повышались и не ослаблялись.

## 4. Проверки и статус

На commit 0a42761a7a76d4786853dd8288b5ed2db0200884:
- Python tests: SUCCESS;
- release contract gate: SUCCESS;
- Ruff F821/F822/F823/E9: SUCCESS;
- Python compileall: SUCCESS;
- Android compile выявил Kotlin compile error в новой inbox API: public method отдавал internal return type. Этот дефект исправлен в последующих коммитах; окончательный exact-head Android CI требуется повторить.

После исправления компиляции и добавления resource-health coverage повторно запустить все проверки на точном head. До успешного exact-head CI статус Android части не считается PASS.

## 5. Ограничения релиза

Кандидат 4.64.37 — не релиз. main не менялся. Реальные средства не использовались, Binance credentials не использовались, signed Demo и физический телефон не проверялись. Williams-AI не затрагивался.

Запрет на Live остаётся в силе до подписанной Demo приёмки обоих направлений и физического Android acceptance.

## Дополнительные меры после первичной реализации

- Новые входы дополнительно блокируются, пока не получен читаемый snapshot inbox текущего credential fingerprint, поле schema соответствует V1, fingerprint присутствует, счётчики подтверждены и число PENDING равно нулю. Проверка стоит в entry readiness и повторяется в двух entry-only fences непосредственно перед финансовой мутацией и транспортом.
- Inbox admission проверяется только для операций, увеличивающих экспозицию. Reduce-only EXIT и защитные STOP сохраняют отдельные safety path и не блокируются наличием PENDING в inbox.
- Coalesced actor drain обрабатывает не более 64 событий за один actor turn. Если события остаются, следующий единственный drain ставится в хвост очереди; это не позволяет большому inbox бесконечно монополизировать общий actor.
- Запуск пересверки на private fill остаётся немедленным; event payload не заменяет signed REST и не даёт разрешения на финансовую мутацию.

