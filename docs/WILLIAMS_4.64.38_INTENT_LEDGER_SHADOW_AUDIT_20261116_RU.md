# Williams Bot 4.64.38 — shadow dual-write для Futures campaign intent ledger

Дата: 16.11.2026  
Repository: 19wolfalone92/Williams  
Branch: feat/nov16-intent-ledger-shadow-4.64.38-stacked  
Base: draft PR #244 / 4.64.37  
Main на момент аудита: 32e9dd64fab9e0488ea4b566347246ac44627199, VERSION 4.64.18

## 1. Исходный риск

Legacy Android execution store сохранял весь массив `futures_campaign_intents_v2` одной JSON-строкой в `runtime_state`. Записи обновляли значение целиком. Это сохраняло атомарность одной строки SQLite, но затрудняло адресный поиск/сверку кампаний и увеличивало объём полного сериализованного состояния.

Нельзя сразу переключить торговое чтение на новую таблицу: ошибка миграции может потерять владельца заявки, STOP, pending POST или резерв риска после перезапуска.

## 2. Что сделано

### SQLite schema v2

Добавлена таблица `futures_intent_shadow`:
- ordinal — устойчивый порядок строк исходного JSON-массива;
- client_id, symbol, intent_role;
- campaign_status, order_status, exchange_status, protection_status;
- strategy_profile;
- SHA-256 точного JSON конкретной строки;
- время построения проекции.

Добавлены индексы по client_id и по symbol/role/status.

### Transactional dual-write

При записи `futures_campaign_intents_v2`:
1. JSON остаётся записан в существующую таблицу `runtime_state`.
2. Из того же JSON строится нормализованная shadow-проекция.
3. Старая shadow-проекция заменяется новыми строками.
4. Все операции выполняются внутри одной SQLite-транзакции.

Если JSON нельзя разобрать или запись shadow-строк не удалась, транзакция откатывается и `writeState` возвращает failure. Следующий финансовый шаг не должен считать намерение сохранённым.

При первоначальной миграции SharedPreferences → SQLite старый JSON сохраняется без переписывания. Невалидное legacy-значение не удаляется и не нормализуется; shadow остаётся NOT_VERIFIED. Upgrade схемы не очищает runtime_state и не переименовывает старый источник.

### Read-only parity

Добавлена проекция `FuturesIntentShadowProjection` и диагностическая сверка:
- число строк;
- их порядок;
- ключевые идентификаторы, symbol, role, execution/protection statuses, profile;
- SHA-256 payload каждой строки.

Результат попадает в диагностический ZIP как `futures_intent_shadow_parity` и отдельный пункт database self-test. Отсутствующая сторона сравнения даёт NOT_VERIFIED; несовпадение — WARNING.

## 3. Почему authority switch НЕ выполнен

В 4.64.38 **legacy JSON остаётся единственным источником торговой истины**. Runtime чтение не переключено на shadow-таблицу. Это намеренное ограничение безопасной миграции, а не завершённая нормализованная модель.

До authority switch ещё нужны:
1. Read-only dual-read сравнение на установленных базах с реальными активными/закрытыми кампаниями и pending/unknown intents.
2. Fault injection на сбоях SQLite до, во время и после dual-write, включая заполнение диска.
3. Restart/recovery на старой БД, upgrade DB v1→v2 и rollback приложения.
4. Доказательство сохранения exact client IDs, order/algo ownership, STOP, risk reservation, partial fills и UNKNOWN outcomes.
5. Профиль p50/p95 времени чтения/записи и heap/DB размера.
6. Отдельный review перед любым чтением из shadow как authoritative source.

Новая таблица — это наблюдаемый transactional shadow, не утверждение, что весь legacy JSON ledger уже удалён или заменён.

## 4. Текущие risk parameters — parity check

| Параметр | Текущее значение |
|---|---:|
| Initial entry risk cap | 0,20% |
| Max risk per entry | 0,25% |
| Campaign / portfolio cap | 0,60% |
| Daily loss cap | 3,00% |
| Max concurrent campaigns | 10 |
| Default leverage | 2x |
| Position mode / margin | One-Way / Isolated |

Для TC2 quantity pyramid 1:5:4:3:2 total units = 15, largest tranche = 5. Следовательно, initial unit risk при этих cap values:
`min(0.20%, 0.60%/15, 0.25%/5) = 0.04%`.

Поэтому 0,20% — верхний cap, а не фактический initial unit risk текущей TC2 пирамиды. Python и Android используют одинаковую формулу. Добавлены регрессионные проверки для значения 0,04% и fail-closed некорректных лимитов. Дневной лимит оставлен 3%; он уже закреплён в проектной политике и не повышался.

## 5. Статус проверок

- Для 4.64.37 exact-head Python tests сообщали SUCCESS; Android build/unit/lint проверка продолжалась на момент обновления.
- Для 4.64.38 создан exact-head Campaign CI; финальный Android/Python результат нужно смотреть именно на HEAD этой ветки после последних документационных и version metadata commits.
- Добавлены pure Kotlin unit tests для проекции, malformed rows, row parity/mismatch и NOT_VERIFIED. Это **не** подменяет Android SQLite instrumentation/fault-injection тестирование фактической БД.
- VERSION, Android Gradle versionCode/versionName и Python API VERSION приведены к 4.64.38. PR release contract gate проверяет их согласованность.

## 6. Что остаётся заблокированным

- FILLED → exact signed active Binance STOP и задержка по биржевым ID.
- Подписанные Demo LONG/SHORT полные циклы, включая partial fills, STOP replace и restart.
- Физический Samsung Android: screen-off, Doze, LTE/Wi-Fi loss, process kill, reboot и update.
- Durable private event inbox ещё не имеет disk quota/компактации. Raw финансовые события не удаляются; дальнейшая политика хранения требует отдельного доказательства отсутствия потери forensic evidence.
- Независимая человеческая экспертная разметка правил четырёх книг и графиков отсутствует.
- Полная Python/Android execution parity не доказана.
- main branch protection по-прежнему требует настройки администратором; Issue #243.

## 7. Release disposition

4.64.38 — stacked draft candidate, не релиз. Base — PR #244 (4.64.37). Main не менялся. Binance credentials не использовались, ордера не отправлялись, реальные средства не затрагивались, Williams-AI не изменялся. Live-торговля запрещена до завершения P0 signed Demo и физической Android acceptance.

## Дополнение из базовой версии 4.64.37

- Базовая ветка PR #244 дополнительно блокирует новый риск до верифицированного пустого inbox текущего credential fingerprint; reduce-only exits и защитные STOP не затрагиваются.
- Inbox actor обрабатывает до 64 событий за один turn и yield-ит в конец очереди, чтобы большой backlog не монополизировал общий actor.
