# Williams Bot — диагностика №20, 16 ноября 2026

Источник: пользовательский ZIP **Williams_Diagnostics (20).zip**,
создан 2026-11-16 14:29:44 UTC, версия **4.64.35-debug**,
исходная ревизия `4f644ccff4a93b914cb3b9f987c582fd01cdd61d`.
Оценка относится к *снимку устройства*, а не к текущему состоянию Binance.

## Проверено по исходным данным

- 78 компонентных проверок: **58 PASS, 3 WARNING, 17 NOT_VERIFIED, 0 FAIL**.
  PASS не означает подтверждённую непрерывность выставления защитного STOP
  после FILLED, физическую Demo-приёмку или успешность книжной стратегии.
- Позиции на снимке: **0**. Поэтому отсутствие данных в
  `futures_exchange_stop_proofs.json` и нулевой
  `futures_fill_stop_timing.fill_event_count` означает **NOT_VERIFIED**,
  а не «SL работает» или «нет риска».
- **AND-02: доказанный сбой причинной интерпретации журнала.**
  `SERVICE_FOREGROUND_CREATED` был записан в **1794838971124**
  (elapsedRealtime=54030 ms), а `BOOT_COMPLETED_OBSERVED` —
  в **1794838971145** (elapsedRealtime=54051 ms): Foreground Service
  был создан на **21 ms раньше записи BOOT_COMPLETED**. После этого
  зафиксированы BOOT_OPT_IN_VERIFIED, BOOT_FGS_START_REQUESTED,
  SERVICE_RUNTIME_OBSERVED и подписанная синхронизация.
  Старый алгоритм искал создание FGS исключительно **после**
  BOOT_FGS_START_REQUESTED и выдавал `BOOT_FGS_CREATION_NOT_OBSERVED`.
  Исправление учитывает только FGS за 0–2000 ms до BOOT_COMPLETED
  при согласованности timestamp и elapsedRealtime. Кроме того,
  для этой ветки требуется отдельный SERVICE_RUNTIME_OBSERVED после
  BOOT_COMPLETED, актуальный worker heartbeat и signed REST sync.
  Отсутствие любого доказательства продолжает давать NOT_VERIFIED.
- **AND-04:** в сохранённой истории Android есть **1** завершение
  процесса со статусом OTHER. Это *не* подтверждённый OOM/краш;
  нельзя объявлять проблему исправленной. Полная 24-часовая
  фоновая непрерывность по кольцевому журналу не доказана.
- **HIST-02, BNB:** наблюдаются подписанные BUY **0.23**, SELL **0.23**
  по **4** биржевым orderIds, но отсутствует однозначная привязка
  к исходному ENTRY кампании. **Не реконструировать** несуществующий
  PnL/закрытие по совпадению агрегированных объёмов.
- **Java heap:** начальный снимок **106,157,984 / 268,435,456 bytes**
  (**39.5%**). После сборки большого диагностического отчёта —
  **219,342,752 bytes** (**81.7%**, запас около **46.8 MiB**).
  Снимок не доказывает OOM, но подтверждает существенное выделение памяти
  при диагностике. `START_HERE_RU.txt` теперь раскрывает это
  **после-сборочное WARNING**, не позволяя скрыть его начальным PASS.
- **Executor queues:** очереди history и indicator всё ещё потенциально
  неограниченные; их размер **0** в конкретный момент не устраняет
  архитектурный риск. Аналогично GET Binance REST weight 307/6000
  не доказывает безопасность при пиковом объёме запросов.
- **H1/H4 наборы:** 15/15 представлены. Исполнительная политика в архиве:
  **10** разрешённых пар для нового риска + **15** для сопровождения;
  `FUTURES_SYMBOL_MANAGEMENT_ONLY` для NEAR/BCH/DOT — ожидаемый
  policy rejection, а не обоснование для обхода gate.
  `FUTURES_HALF_MARGIN_UNKNOWN:FOREIGN_ASSET` для BNB/LINK
  остаётся fail-closed до отдельного подписанного аудита, не надо
  отключать ограничение.
- Книжная истинность TC1/NTD/TC2/Course и точность WM1/WM2/WM3
  остаются независимыми от компонентных PASS.
  Последние значения `SCAN_IN_PROGRESS` нельзя выдавать за
  завершённую атрибуцию всех сигналов.

## Фактические изменения этой ветки

1. Исправлена гонка порядка событий FGS ↔ BOOT_COMPLETED в
   `AndroidRebootRecoveryEvidence.kt` без ослабления торговых
   барьеров, STOP, risk gate или проверки подписанной Binance REST.
2. Добавлены regression-тесты на хронологию из телефона, чужую epoch,
   отсутствие runtime-start, несвежий worker и неподтверждённый sync.
3. В кратком отчёте виден отдельный предупреждающий показатель
   Java heap **после** сборки диагностики, а не только до неё.

## Остаётся открытым, не имитировать исправление

- Независимый signed Demo LONG и SHORT, включая частичные исполнения:
  FILLED → STOP NEW → signed GET активной защиты → reboot/reconcile;
  без повторного POST при UNKNOWN.
- Квалификация необычного process-exit OTHER, физическая приёмка
  Samsung screen-off/Doze/reboot/network loss минимум 24 часа.
- Профилирование peak/retained heap и streaming-экспорт
  decision-history/market evidence без пикового удержания крупных
  JSON-деревьев. Проверить рост RSS и ограничения очередей.
- BNB entry/exit causal linkage по signed individual fills
  и campaign/client-order identifiers, а не только сумме buy/sell.
- Продолжение staged PR #244 (durable private event inbox)
  и #249 (SQLite intent shadow) с отдельным fault-injection, parity
  и end-to-end acceptance. Никакой немедленной миграции authority.

**Ограничения:** это диагностические изменения. Ни `main`, ни
торговая методология, ни управление ордерами и STOP не меняются.
APK не объявляется готовым по данному ZIP и не допускается к real money.
