# WILLIAMS BOT 2.1 — TASK 004.13
## Causal Replay, Fractal Confirmation & Python–Android Parity

**Итоговый статус: BLOCKED — NOT ACCEPTED** 🔴

### 1. Проверенный Git baseline

- Репозиторий: `19wolfalone92/Williams`
- Ветка: `audit/williams-2.1-task-004-williams-strategy`
- PR #49: OPEN / Draft, base `main`, merge не выполнялся.
- Base SHA: `04da5f9c09572e99ff310be3a1908179165e528c`
- Проверяемая реализация: `82ecc9e8f899b1128892e66c773d12763986425f`
- Фактический HEAD перед добавлением отчёта TASK 004.13: `dd1eb3d462f3a8641bf2df00ad2086192ea9b88c`
- Сравнение `82ecc9e...82ecc9e` → текущий HEAD: один коммит, только `TASK_004_11_REPORT_RU.md`; implementation code в этом последнем коммите не менялся.
- Отчёт `REPORT_RU.md` дополнен доказательствами и ограничениями TASK 004.13. Созданы пять отсутствовавших матриц и этот отчёт; стратегический код не менялся.

### 2. Подтверждённые артефакты

1. Shared fixture `app/src/test/resources/williams_strategy_golden_vectors.json`, blob `d0a6e221c4382150339ece9bc68540b08e8b9a1c`.
2. Python consumer `tests/test_williams_strategy_golden_vectors.py`, blob `d0e136c5298d971df35a5a05e1bb00915f4e8f7b).
3. Android consumer `app/src/test/java/com/williamsbot/WilliamsStrategyGoldenVectorsTest.kt`, blob `893946b46ba25d3c2b2120455cfbf4071eec6531`.
4. Android fractal helper `WilliamsFractalMath.kt`, blob `b9830e6f9bca360dcf1b73d399fc450266d1b0dd`.
5. Timeframe helper `WilliamsTimeframeContract.kt`, blob `ceacc115d53396836f6fdc745cecb3fb98c0ecdd`.

Существующие тесты проверяют выбранные значения Alligator/AO/AC, некоторые случаи фрактала (до подтверждения, равные экстремумы, отмена более сильным экстремумом), независимость Python WM2 от WM3, цену-триггер/инвалидацию и helper-контракт таймфреймов. Это реальная полезная частичная проверка, но не полный причинный replay всех состояний.

### 3. CI

На implementation SHA `82ecc9e...`:
- Python backend tests — run `38129894739`, SUCCESS.
- Android APK — run `38129894745`, SUCCESS.
- Williams Campaign CI — run `38129894732`, CANCELLED.

На HEAD `dd1eb3d...` по последней зафиксированной проверке:
- Python backend tests — `38129954698`, SUCCESS.
- Williams Campaign CI — `38129954685`, SUCCESS.
- Android APK — `38129954731`, SUCCESS.

Ссылки: [Python implementation run](https://github.com/19wolfalone92/Williams/actions/runs/38129894739), [Android implementation run](https://github.com/19wolfalone92/Williams/actions/runs/38129894745), [Campaign implementation run](https://github.com/19wolfalone92/Williams/actions/runs/38129894732), [Python HEAD run](https://github.com/19wolfalone92/Williams/actions/runs/38129954698), [Campaign HEAD run](https://github.com/19wolfalone92/Williams/actions/runs/38129954685), [Android HEAD run](https://github.com/19wolfalone92/Williams/actions/runs/38129954731).

CI success не доказывает отсутствие look-ahead во всех путях и не заменяет полный differential replay.

### 4. Что доказано / что не доказано

| Требование | Результат |
|---|---|
| Shared fixture реально читается тестами Python и Android | CODE-CONFIRMED; оба CI имеют соответствующие тестовые вызовы |
| Выбранные формулы Alligator/AO/AC | PARTIAL PASS |
| Отдельные fractal prefix/tie/cancel cases | PARTIAL PASS |
| WM2 не зависит от prior WM3 в Python candidate path | PARTIAL PASS |
| All-prefix replay для Alligator/AO/AC/WM1/WM2/WM3/Structure/Setup/Confirmation/Trigger/Campaign/Exit | BLOCKED |
| Полное совпадение конечного сигнала и времени доступности между Python и Android | BLOCKED |
| Закрытые/незакрытые свечи, дубли времени, пропуски и порядок event-time | BLOCKED |
| Перезапуск между экстремумом и подтверждением, сигналом и trigger, entry и exit | BLOCKED |
| Исчерпывающая проверка H1-only по каждому entry-producing call path | BLOCKED |
| Полная campaign/trailing/exit/re-entry parity | BLOCKED |

### 5. Причины BLOCKED

- Текущие тесты содержат отдельные prefix-примеры, но не универсальный causal replay harness для всех префиксов и всех промежуточных состояний.
- Golden fixture сравнивает выбранные числовые результаты и отдельные helper outcomes, но не полные сериализованные Python/Android DecisionTrace для каждого бара.
- Restart, открытая свеча, live path reachability, campaign/exit, trailing и повторный вход не покрыты полным дифференциальным replay.
- WM1 angulation и другие неразрешённые source semantics не должны автоматически превращаться в обязательные торговые gates.

### 6. Следующие обязательные действия

1. На одном зафиксированном implementation SHA выполнить all-prefix replay по одинаковому timestamped OHLCV набору.
2. На каждом префиксе сравнивать ранее опубликованные event IDs, индикаторы, WM1/2/3, Setup/Confirmation, trigger, campaign и exit; разрешать только явно задокументированные переходы.
3. Добавить replay cases для закрытой/открытой свечи, равных экстремумов, нового более сильного экстремума, пропусков/дубликатов, restart до подтверждения и restart во время PendingSignal.
4. Сравнить полные Python и Android результаты: конечный сигнал, signal-bar time, confirmation/availability time, trigger/invalidation, campaign/exit state.
5. Записать dataset hash, implementation SHA, точную команду, количество тестов, CI IDs и результаты.
6. Оставить PR #49 Draft; не менять main, не выполнять merge и не отправлять ордера.

**Финальное решение: TASK 004.13 — BLOCKED — NOT ACCEPTED.** Никакие реальные ордера не отправлялись; стратегия-код этим документальным checkpoint не изменён.
