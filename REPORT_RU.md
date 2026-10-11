# WILLIAMS BOT 2.1 — TASK 002
## Trusted Baseline, Version Contract & Release Gate

**Текущий статус: BLOCKED — NOT ACCEPTED.**  
Дата повторной проверки: 2026-10-11 UTC. Источник: GitHub REST API, workflow metadata, workflow job logs и артефактная метаинформация. Локальный checkout в этой среде не выполнялся.

## 1. Зафиксированные refs и Git-объекты

| Объект | SHA | Tree / parents | Статус |
|---|---|---|---|
| `main` | `04da5f9c09572e99ff310be3a1908179165e528c` | Tree SHA здесь не извлечён повторно | Ref подтверждён через GitHub API |
| Рабочая ветка, исходный HEAD до отчётов | `bb6cd92ca7bb07369e26e77cab214b04229c3435` | Tree `b00d3a46381186bdfad25a71e02d52ff90fa22fc`; parent `af5a73ef23ad693c3438a7343036e120e292a01a` | Commit metadata подтверждены |
| Рабочая ветка после отчётных коммитов | `fd1d5b9059f9b3bfc08eb2c1773221ff1972cee3` | Ref подтверждён до начала текущего обновления | Предыдущий проверенный HEAD |
| CI checkout SHA для найденных runs | `89c866cfe63ac8a76f4ebb038d6166fafb745bd8` | Merge commit: `bb6cd92...` в `04da5f9...`; tree `3130d0694954f3626f66d8ac71a83a09e46be3f1` | **CI проверял merge result, не branch HEAD напрямую** |

URL репозитория: https://github.com/19wolfalone92/Williams. Данные получены из GitHub API 2026-10-11 UTC.

GitHub Actions checkout в доступных job logs использовал `fetch-depth: 1` и detached HEAD на merge commit `89c866c...`. Это не полный checkout с историей. Локальные `git fsck --full`, `git bundle verify`, проверка чистоты worktree и полноценная проверка всех refs не выполнялись. Успешный CI не заменяет эти проверки.

## 2. Найденные workflow evidence для исходного SHA

Для исходного SHA `bb6cd92ca7bb07369e26e77cab214b04229c3435` GitHub API показывает:

| Workflow run | Run ID | Результат | Примечание |
|---|---:|---|---|
| Build Android APK | `38077212239` | SUCCESS | Checkout merge SHA `89c866c...`; debug/release APK artifacts опубликованы |
| Python backend tests | `38077212234` | SUCCESS | Checkout merge SHA `89c866c...` |
| Williams Campaign CI | `38077212236` | SUCCESS | Checkout merge SHA `89c866c...` |
| Williams Campaign CI | `38077209262` | SUCCESS | Checkout merge SHA `89c866c...` |
| Williams Campaign CI | `38135295982` | CANCELLED | Поздний run; не считать PASS |

Workflow results подтверждают, что соответствующие CI job завершились успешно для merge checkout. Они не подтверждают прохождение тестов непосредственно на branch HEAD `bb6cd92...`, и тем более не подтверждают baseline на последнем отчётном HEAD `fd1d5b9...`.

### Логи тестов

Из сохранённых workflow job logs:

- Python backend tests: `python -m pytest -q` — **158 passed, 4 warnings in 4.32s**.
- Campaign CI: `python -m pytest -q ...` — **169 passed, 4 warnings in 4.52s**.
- Release gate выводил `WILLIAMS RELEASE CONTRACT GATE: PASS (VERSION=4.25.0)`.
- Android job: JDK Temurin `17.0.20+1`; в логах указаны Gradle `8.14.5`, Android platform `android-36`, build-tools `36.0.0`. Android tasks завершились сообщениями `BUILD SUCCESSFUL`.

Эти результаты — фактические CI результаты, но они относятся к checkout SHA `89c866c...`, а не к точному branch HEAD. Python dependencies устанавливались командой `python -m pip install -r requirements.txt` (и в backend job дополнительно `pytest`); lock-файл и полный повторяемый dependency snapshot в репозитории не подтверждены.

## 3. Version contract

- `main/server.py` задаёт backend version `4.25.0` непосредственно.
- В рабочей ветке `server.py` импортирует `APP_VERSION` из `version_contract.py`; файл `VERSION` содержит `4.25.0`.
- Рабочая ветка `gradle-wrapper.properties` указывает `8.14.6`; в найденном Android CI workflow переменная `GRADLE_VERSION` указывала `8.14.5`. Это **реальное расхождение между объявленным wrapper и инструментом, используемым CI workflow**; нужно устранить после проверки совместимости, а не считать значения эквивалентными.
- Android Gradle Plugin: `8.13.2`; Kotlin plugins: `2.3.21`; compile/target/min SDK: `36/36/26`; Java/Kotlin target: 17.
- Android workflow лог показывает JDK Temurin `17.0.20+1`; Android platform `platforms;android-36`, build-tools `36.0.0`.
- Backend Python CI использовал CPython `3.13.16`; Campaign CI — CPython `3.12.15`. Это разные Python minor versions для разных workflows.
- В одном backend workflow фактически разрешены, среди прочего: pandas `3.0.6`, numpy `2.5.3`, pytest `9.1.1`, FastAPI `0.143.0`, Pydantic `2.14.0`, requests `2.34.2`, cryptography `50.0.2`. Это лог одного запуска, не lock-файл и не гарантия повторного разрешения.
- Версии протоколов и схем SQLite с миграциями не были полностью сопоставлены. Необходимо отдельное контрактное доказательство.

## 4. APK provenance

Для run `38077212239` GitHub API возвращает неистёкшие artifacts, связанные с head SHA `bb6cd92...`:
- artifact `11679353038`, `williams-bot-debug-apk-1531`, 20,638,869 bytes;
- artifact `11679357932`, `williams-bot-release-unsigned-1531`, 14,176,611 bytes;
- artifact `11679462492`, `williams-debug-build-log-1531`, 1,024 bytes;
- artifact `11679342429`, `williams-android-unit-test-diagnostics-1531`, 24,529 bytes.

Это улучшает цепочку происхождения до **CI run → artifact metadata → source head SHA**, но APK не скачан и его SHA-256 независимо не пересчитан в этой проверке. Метаданные package/version/signature и соответствие ранее записанному SHA `4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5` не подтверждены. Кроме того, CI checkout был merge commit `89c866c...`, поэтому нельзя без дополнительных данных заявлять, что APK побайтно соответствует branch commit `bb6cd92...` как изолированному source tree.

## 5. Физическое устройство

В рамках этой проверки нет ADB-сеанса или доказательства доступного физического Android-устройства. Установка APK, runtime logs, SQLite recovery, Scanner, Execution Gate при UNKNOWN состоянии и запрет новых входов до reconcile не проверены. CI build success не является device evidence.

## 6. Итоговая матрица

| Критерий | Статус | Основание |
|---|---|---|
| Удалённые refs и commit metadata | PARTIAL PASS | Ref/commit metadata прочитаны через GitHub API |
| Полная Git object integrity | BLOCKED | Нет полного checkout; `git fsck --full` и `git bundle verify` не запускались |
| CI baseline на merge SHA | PASS (CI only) | Python 158 passed и 169 passed; Android build успешен |
| Baseline на точном branch HEAD | BLOCKED | CI checkout использовал merge SHA `89c866c...` |
| Повторяемость Python dependency resolution | BLOCKED | Нет lock-файла; разные Python minor versions и свободные нижние границы |
| Версионный контракт | FAIL / OPEN | Wrapper `8.14.6` против CI Gradle `8.14.5`; Python 3.12 и 3.13 в разных workflow |
| APK artifact связанный с workflow/head SHA | PARTIAL PASS | Artifact metadata найдена, но CI собирал merge checkout |
| Независимый APK SHA-256 и package/signature metadata | BLOCKED | APK bytes не скачаны/не проверены |
| Физический Android runtime | BLOCKED | Нет device/ADB evidence |
| Общий статус | **BLOCKED — NOT ACCEPTED** | Критерии PASS не выполнены |

## 7. Дальнейшие обязательные действия

1. Получить полный checkout или доверенный bundle; зафиксировать его SHA-256 и проверить `git bundle verify`, `git fsck --full`, refs, HEAD, tree, parents и clean worktree.
2. Запустить baseline на конкретном branch HEAD, а не на PR merge ref; сохранять exit codes и логи.
3. Согласовать Gradle wrapper и версию Gradle в workflow; определить каноническую Python версию и lock dependency resolution.
4. Проверить протоколы, схемы SQLite и миграции старых данных.
5. Скачать конкретный APK artifact, пересчитать SHA-256, проверить package/version/signature и связать provenance с фактическим commit, из которого собран APK.
6. Выполнить физический тест в отдельной процедуре TASK 002.16.

**Решение:** TASK 002.1 остаётся BLOCKED — NOT ACCEPTED. Найдены дополнительные CI-доказательства, но они не снимают блокировку точного baseline, Git integrity, dependency reproducibility, полного APK provenance и физического runtime.
