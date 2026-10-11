# WILLIAMS BOT 2.1 — TASK 006
## ANDROID AUTONOMOUS RUNTIME & DEVICE EVIDENCE

**Итоговый статус: BLOCKED — TRUSTED SOURCE NOT PROVIDED / PHYSICAL DEVICE EVIDENCE NOT AVAILABLE**  
**Приоритет:** P0 — CRITICAL  
**Рабочая ветка:** `audit/williams-2.1-task-006-android-runtime`  
**PR:** #46 — Open / Draft / Unmerged (по доступному снимку GitHub)  
**Baseline main в описании PR:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Workflow head SHA для полученного APK:** `dc634573eb26c491d125feecd158319f1f5daa79`  
**Режим:** аудит доказательств; Mainnet и реальные торговые операции не использовались.

## 1. Итог

TASK 006.1 остаётся **BLOCKED**. Целевая ветка обнаружена, и фактический unsigned APK удалось получить из GitHub Actions. SHA-256 ZIP и вложенного APK независимо вычислены. Однако trusted source gate не пройден: независимый доверенный checkout/Git bundle и локальные результаты `git bundle verify`, `git fsck --full`, `git status` отсутствуют. Физическое Android-устройство и оригинальные ADB-логи также недоступны.

Наличие APK и успешный workflow не доказывают автономную работу приложения на устройстве. Синтетические device logs не создавались.

## 2. Trusted Source Gate

| Проверка | Результат | Примечание |
|---|---|---|
| Целевая ветка | FOUND | `audit/williams-2.1-task-006-android-runtime` обнаружена в GitHub |
| Фактический branch HEAD после текущих документов | PARTIALLY VERIFIED | Workflow run связан с head SHA `dc634573eb26c491d125feecd158319f1f5daa79`; необходимо повторно проверить актуальный HEAD после всех операций |
| Baseline | NOT VERIFIED independently | SHA `04da5f9c09572e99ff310be3a1908179165e528c` взят из описания PR |
| Trusted Git bundle/full checkout | BLOCKED | Не предоставлен как проверяемый артефакт |
| SHA-256 исходного bundle/архива | NOT RUN | Нет доверенного архива |
| `git bundle verify` | NOT RUN | Bundle отсутствует |
| `git fsck --full` | NOT RUN | Нет локального Git object database |
| `git status --short` | NOT RUN | Нет локального checkout |
| Родители и полная цепочка refs | NOT VERIFIED | Требуется локальная проверка доверенного checkout |
| Происхождение исходников | BLOCKED | GitHub доступен для чтения, но это не заменяет независимое подтверждение происхождения |

**Gate result: BLOCKED — TRUSTED SOURCE NOT PROVIDED.** Не изменять торговую логику, схему хранения или защитные механизмы до прохождения gate.

## 3. APK и workflow provenance

Из GitHub Actions workflow run `38143567123` (run #2117) получен artifact `williams-bot-release-unsigned-2117`, artifact ID `11703862596`. GitHub metadata связывает workflow artifact с веткой `audit/williams-2.1-task-006-android-runtime` и head SHA `dc634573eb26c491d125feecd158319f1f5daa79`.

- SHA-256 ZIP, независимо вычисленный: `394ff75cf04a4f4cbbf458b7df311f83fd3e760c255940a1a89395e377e781ad`.
- GitHub-reported artifact digest: совпадает.
- Вложенный файл: `app-release-unsigned.apk`.
- SHA-256 APK, независимо вычисленный: `08fdd472bcb6ab0fc88f3ca03593636582d76beea4ca2e981dd27bf8e88ccad1`.
- Вложенный checksum-файл совпадает с вычисленным SHA-256 APK.
- APK явно обозначен как unsigned.

Ранее зарегистрированный справочный SHA-256 `4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5` **не совпадает** с полученным APK. Их нельзя считать одним артефактом.

applicationId, versionName/versionCode, сертификат, воспроизводимость сборки и соответствие APK установленному на устройстве приложению пока не проверены. В доступном окружении `adb`, `aapt` и `apksigner` не найдены в PATH. Полная таблица — в `APK_PROVENANCE.md`.

## 4. Физическое устройство

Модель устройства, Android/API level, установленная версия APK, ADB, временные метки испытаний и оригинальные device logs не получены. DEV-001…DEV-010 на физическом устройстве не выполнялись. Статусы находятся в `DEVICE_EVIDENCE_MATRIX.md` и `RECOVERY_TEST_MATRIX.md`.

## 5. Runtime-компоненты

| Компонент/утверждение | Статус | Причина |
|---|---|---|
| NativeEngine запускается на устройстве | NOT VERIFIED | Нет device logs |
| Dashboard UI не владеет торговым состоянием | NOT VERIFIED | Нет физического runtime trace |
| SQLite/FSM переживают перезапуск | NOT RUN | Нет снимков до/после |
| Recover → Reconcile → Validate → Trade Eligibility | NOT RUN | Нет событий устройства с UTC timestamp/run ID |
| Binance state сверяется до входов | NOT VERIFIED | Нет Testnet device trace |
| REST/WebSocket/history/scanner/balances | NOT RUN | Нет runtime samples |
| Credentials и авторизация | NOT RUN | Нет контролируемого device/Testnet теста |
| Защита секретов в сетевых путях | NOT VERIFIED | Контролируемый сетевой аудит не проводился |
| Safe Mode / kill switch / fail-closed | NOT RUN | Нет испытаний на устройстве |
| Локальное исполнение без обязательного VPS | NOT VERIFIED | Runtime-независимость не доказана |

## 6. Следующие шаги

1. Получить доверенный Git bundle или checkout и пройти Trusted Source Gate.
2. Проверить APK metadata/signing state в Android SDK environment.
3. Выполнить controlled rebuild из доверенного SHA и зафиксировать сравнение артефактов.
4. Подключить физическое Android-устройство, сохранить оригинальные ADB-логи и выполнить DEV-001…DEV-010 в безопасной Testnet-конфигурации без отправки ордеров.
5. Сохранить sanitized SQLite/FSM snapshots, runtime diagnostics и сетевые evidence.
6. Привязать каждый PASS к точному source SHA, APK SHA, версии устройства/приложения, временным меткам и воспроизводимым командам.

## 7. Решение

**TASK 006.1: BLOCKED — NOT ACCEPTED.** Подтверждено получение и хеширование unsigned APK из workflow; это частичный прогресс по APK acquisition. Trusted source provenance и физические испытания остаются блокирующими. Не закрывать задачу и не разрешать live trading.
