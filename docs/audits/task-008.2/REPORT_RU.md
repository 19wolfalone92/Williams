# WILLIAMS BOT 2.1 — TASK 008.2
## Baseline reproduction and failure trace recovery

**Статус:** `FAIL — BASELINE REPRODUCED; 11 PYTHON TEST FAILURES`  
**Исследуемый SHA:** `1a7e7209c99152c3bf29f3e2f07e7db088d9243b`  
**Ветка на момент запуска CI:** `audit/williams-2.1-task-008-spot-testnet`  
**Workflow run:** [38129367877](https://github.com/19wolfalone92/Williams/actions/runs/38129367877)  
**Время запуска:** 2026-10-11 09:43:12–09:47:50 UTC.

### 1. Результат

Исторический результат `230 passed / 11 failed` **подтверждён фактическим GitHub Actions запуском** на точном SHA `1a7e7209c99152c3bf29f3e2f07e7db088d9243b`. Это удалённый CI-запуск, а не независимый локальный запуск в среде аудитора.

- Python: `230 passed, 11 failed, 4 warnings`; pytest завершился с exit code `1`.
- `python -m compileall -q .`: шаг CI — success.
- `python full_safety_audit.py`: шаг CI — success.
- Android unit tests + lint + debug APK build: шаг CI — success.
- Workflow overall: **failure**, поскольку Python test step завершился с ошибками.
- Исторический результат не подменялся текущим HEAD; checkout в логах подтверждает исследуемый SHA.

Полные исходные логи сохранены рядом:
- [Python job raw log](baseline-logs/python-job-114440299305.log)
- [Android job raw log](baseline-logs/android-job-114440299494.log)

### 2. Зафиксированная среда

Из Python job log: Ubuntu 24.04.5 LTS, hosted image `ubuntu-24.04` version `20261004.327.1`, Python `3.12.15`, Git `2.55.0`. CI использовал `python -m pip install -r requirements.txt`; зависимости в requirements заданы диапазонами, lock-файл не указан. Точные фактически установленные версии видны в полном логе установки.

Android workflow задаёт Temurin JDK 17 и Gradle 8.14.6. Версия Android SDK и версия SQLite не зафиксированы в сохранённом отчёте CI и отмечены как NOT RECORDED, а не предполагаются.

### 3. Все 11 падений

1. `test_production_architecture.py::test_execution_barrier_serializes_publish_and_submit` — admission rejected: `trusted_preflight_service_missing_or_invalid`.
2. `test_execution_barrier_fail_closed.py::test_legacy_portfolio_mutation_wrapper_requires_barrier_and_blocks_submit` — `AttributeError: 'MultiPositionTrader' object has no attribute 'db'`.
3. `test_multi_position_execution.py::test_multi_position_execution_creates_independent_trades` — ожидаемое количество 2, фактически 1; результат содержит `Portfolio mutation blocked: canonical ExecutionBarrier unavailable`.
4. `test_multi_position_lifecycle.py::test_partial_manual_sell_keeps_residual_position_protected` — assertion ожидал True, получил False.
5. `test_multi_position_lifecycle.py::test_partial_emergency_sell_never_closes_trade_as_flat` — `Emergency exit blocked: dedicated emergency execution contract is not implemented`.
6. `test_binance_json_shapes.py::test_signed_post_retries_once_only_for_timestamp_rejection` — второй запрос блокируется: `one mutation per ExecutionBarrier permit`.
7. `test_preflight_gate.py::test_testnet_preflight_keeps_unknown_runtime_proofs_blocked` — `api_restrictions`: expected PASS, actual UNKNOWN.
8. `test_preflight_gate.py::test_testnet_preflight_blocks_withdrawals` — `api_restrictions`: expected FAIL, actual UNKNOWN.
9. `test_preflight_gate.py::test_testnet_preflight_blocks_foreign_order` — `open_orders`: expected FAIL, actual UNKNOWN.
10. `test_preflight_gate.py::test_known_orders_cannot_be_marked_reconciled_by_boolean_override` — `open_orders`: expected FAIL, actual UNKNOWN.
11. `test_runtime_freshness.py::test_future_timestamp_remains_unknown` — expected reason `measurement_clock_skew`, actual `measurement_timestamp_in_future`; status remains UNKNOWN.

Подробные traceback и окружающие CI-сообщения находятся в полном Python job log и в матрице первопричин.

### 4. Первопричины и границы вывода

- Падения 7–10 имеют подтверждённый общий непосредственный механизм: `PreflightCheckService.verify_all()` прекращает проверки и возвращает report, если не задан утверждённый `max_offset_ms` / `LIVE_MAX_TIME_OFFSET_MS`. Поэтому более поздние проверки остаются UNKNOWN. Тесты создают сервис без явного offset budget. Это подтверждённое несоответствие тестового setup предусловию, но не основание ослаблять fail-closed контракт.
- Падение 6 — тест ожидает повторную попытку внутри одного permit, тогда как `require_mutation_permit()` помечает permit consumed до сетевого вызова и запрещает его повторное использование. Нужно отдельно определить безопасный контракт повторной admission для однозначно отклонённого timestamp запроса; слепо переиспользовать permit нельзя.
- Падение 5 подтверждает отсутствующий dedicated emergency-exit contract: код намеренно переводит состояние в RECONCILE_REQUIRED и выбрасывает RuntimeError. Это функциональный пробел относительно тестируемого сценария, а не доказательство, что аварийное исполнение безопасно реализовать простым снятием блокировки.
- Для падений 1–4 и 11 фактические симптомы воспроизведены, но окончательная причинная цепочка/владение контрактом не доказаны в рамках этого этапа; в матрице первопричина отмечена UNKNOWN или как непосредственное наблюдение, не как окончательный root cause.

### 5. Безопасность и изменения

- Не выполнялись Binance Mainnet запросы или реальные ордера.
- Это анализ CI с тестовыми fake clients; не является подтверждением сквозного Binance Spot Testnet цикла.
- Производственный код не изменялся в рамках TASK 008.2. Добавлены только отчётные артефакты и сохранённые CI-логи в целевой ветке.
- Не выполнялись merge, изменения `main`, удаление веток или ослабление assertions.
- Компиляция, static audit и Android CI success не компенсируют 11 Python test failures.

### 6. Следующие действия

1. Сохранить baseline неизменным; не чинить код в этой задаче.
2. Для падений 1–4 и 11 провести отдельное прослеживание теста → контракт → production call chain.
3. Для preflight-тестов добавить корректно утверждённый тестовый offset budget в setup, сохранив отдельный тест, который подтверждает fail-closed поведение при отсутствии budget.
4. Разработать отдельный контракт retry после timestamp rejection с новой admission/permit и доказательством, что первая попытка не могла создать ордер.
5. Реализовать emergency-exit только в отдельной согласованной задаче с durable intent, идемпотентностью и recovery-тестами.
6. После исправлений повторить весь неизменённый CI-набор и отдельный сквозной Spot Testnet тест; успех текущего Android job не является доказательством готовности интеграции.
