# WILLIAMS BOT 2.1 — TASK 002
## Trusted Baseline, Version Contract & Release Gate

**Итоговый статус: BLOCKED — TRUSTED CHECKOUT / REPRODUCIBLE BASELINE NOT ESTABLISHED**

Дата проверки: 2026-10-11 (UTC).  
Метод: чтение GitHub REST/API-содержимого и метаданных. Это удалённая инвентаризация, а не локальный Git checkout.

### 1. Идентификаторы источников

| Объект | SHA | Что доказано |
|---|---|---|
| `main` | `04da5f9c09572e99ff310be3a1908179165e528c` | GitHub ref указывает на этот commit |
| Рабочая ветка `audit/williams-2.1-task-002-release-contract` | `bb6cd92ca7bb07369e26e77cab214b04229c3435` | GitHub ref указывает на этот commit |
| APK из предыдущего отчёта TASK 002.16 | SHA-256 `4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5` | Перенесено как ранее заявленное значение; в рамках этой проверки APK не пересчитан и связь с исходным commit заново не подтверждена |

GitHub API metadata не заменяет trusted Git bundle или полноценный checkout. В этой среде отсутствуют проверенный bundle/checkout и возможность выполнить локальные Git-проверки. Поэтому точные локальные HEAD, tree, parent/ref consistency, `git bundle verify`, `git fsck`, submodule состояние и baseline-тесты не подтверждены.

### 2. Version contract: найденные расхождения

- На `main` файл `server.py` задаёт `VERSION = '4.25.0'` непосредственно.
- В рабочей ветке `server.py` использует `from version_contract import APP_VERSION` и `VERSION = APP_VERSION`; `version_contract.py` читает канонический файл `VERSION`, в котором указано `4.25.0`.
- На `main` `gradle-wrapper.properties` указывает Gradle `8.14.5`; в рабочей ветке — `8.14.6`.
- В рабочей ветке Android Gradle Plugin указан как `8.13.2`, Kotlin plugin — `2.3.21`, `compileSdk=36`, `targetSdk=36`, `minSdk=26`, Java/Kotlin target — 17. Это значения конфигурации, а не доказательство реально использованных версий JDK/SDK в сборке.
- `requirements.txt` содержит нижние границы зависимостей (например, `pytest>=8.3`), но не lock-файл с точными разрешёнными версиями. Воспроизводимость Python-окружения не доказана.

### 3. Сопоставление audit/test-кода и реализации

Удалённо просмотрены начала `server.py`, `full_safety_audit.py` и `test_production_architecture.py` в обеих доступных точках (main и рабочая ветка для названных файлов). В рабочей ветке сервер подключает канонический version contract и `execution_start_blocker`; на main сервер версии захардкожен. `full_safety_audit.py` — статический сканер локального дерева, его результат зависит от фактического содержимого checkout и сам по себе не доказывает runtime-корректность. `test_production_architecture.py` содержит тесты контрактов отдельных компонентов, но их наличие не доказывает, что тесты запускались на указанном HEAD или что весь релизный путь работает.

Полное сравнение всех путей исполнения и запуск этих файлов не выполнены: нет trusted checkout и воспроизводимого окружения. Это остаётся открытым пунктом, а не PASS.

### 4. Baseline и CI

Baseline-тесты на точном локальном HEAD **не запускались**. Нет локального stdout/stderr, exit code, версии интерпретатора/инструментов и зафиксированного dependency resolution. Поэтому baseline считается NOT RUN, а не PASS/FAIL.

Статус CI, README или наличие APK не используются как замена локальному baseline. Даже успешная CI-сборка подтверждает только выполнение соответствующего CI job на его commit/environment, но не физический запуск приложения.

### 5. APK provenance и device runtime

В предыдущем TASK 002.16 указан APK SHA-256 `4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5`; там же отмечено отсутствие физического Android-устройства и непроверенные runtime, SQLite recovery, Scanner, Gate и collector. В этой проверке не получены сам APK, независимый повторный SHA-256, package/version metadata, build manifest с исходным SHA и подтверждённая цепочка artifact → workflow run → commit. Следовательно, APK-to-source linkage и device readiness не подтверждены.

### 6. Release gate

| Критерий | Статус | Основание |
|---|---|---|
| SHA refs main/рабочей ветки зафиксированы по GitHub API | PARTIAL | Удалённые refs известны, но локальный checkout не подтверждён |
| Trusted source и локальная Git целостность | BLOCKED | Нет bundle/checkout; `git bundle verify`/`git fsck` не выполнены |
| Согласованность версий | FAIL / OPEN | Gradle wrapper различается; main и рабочая ветка используют разные способы задания backend version |
| Точные версии Python-зависимостей | BLOCKED | Нет lock-файла/результата разрешения зависимостей |
| Baseline-тесты на exact HEAD | BLOCKED — NOT RUN | Нет воспроизводимого checkout и логов запуска |
| APK связан с проверенным source SHA | BLOCKED | Независимый APK и build provenance не проверены |
| Физическое устройство / runtime | BLOCKED | По TASK 002.16 устройство отсутствует |
| Полный PASS | **NO-GO** | Несколько обязательных критериев не подтверждены |

### 7. Что необходимо для снятия блокировки

1. Предоставить доверенный Git bundle либо доступный полноценный checkout с зафиксированными refs; проверить SHA-256 bundle, выполнить `git bundle verify`, `git fsck --full`, сверить HEAD/tree/parents и refs.
2. На этом checkout запустить baseline-команды, сохранить команду, cwd, commit SHA, OS, Python/JDK/SDK/Gradle versions, lock resolution, stdout/stderr и exit codes.
3. Зафиксировать единый version contract для backend/app/protocol/schema и тесты, проверяющие его.
4. Получить APK как файл, пересчитать SHA-256, извлечь package/version/signature metadata, привязать к workflow run и commit SHA.
5. Для device-ready заявления выполнить отдельный TASK 002.16/002.17 с физическим устройством и ADB/runtime evidence.

**Решение:** release gate закрыт. Не объявлять PASS и не заявлять готовность к устройству/релизу на основании текущих доказательств.
