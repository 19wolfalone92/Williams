# Общая маржа счёта — реализация 4.64.32

14.11.2026. Продолжение PR240 из `1a1fbee105c0b12b33e93e30d453bea5b65d09e7`.
Репозиторий только `19wolfalone92/Williams`; единственный продолжаемый поток —
`research/master-primary-sources-20261113`. Main при начале:
`32e9dd64fab9e0488ea4b566347246ac44627199`. Другие PR не объединялись.

## Что уже было сделано до возобновления

Указанный пользователем `0046ff57` не был последним: в ветке сохранён также
`1a1fbee`, структура всех795 листов TC2 и смысловая сверка главы12.
Актуальные receipts724/1213, visual216, qualitative figures157, selected
anchors16; независимых проверок0. Осталось489 физических листов переводов.
Первый текстовый проход TC1, NTD, TC2 EN и supplied Course действительно сохранён.
Базовый Python воспроизведён:2495 passed/2 skipped. Четыре PR-triggered workflows
точного `1a1fbee` успешны, включая Android build. Старое описание PR устарело.

Повторно получены те же6 PDF. Все SHA256 и количества140/156/257/224/314/122
совпали с `SOURCE_CATALOG.json`. Исходные PDF/тексты не добавлены в Git.

## Как изменился торговый допуск

Книжный источник: TC2 EN PDF212–213 и translation anchors FAQ половины счёта.
Это общий лимит занятой маржи, а не стоп-риск одного входа. Приёмка биржевого
отображения пока не выполнена; перечисленные ниже решения инженерные.

Python `futures_half_margin.py` и Kotlin `FuturesHalfMargin.kt` используют
один набор JSON-сценариев и Decimal/BigDecimal. Оба финальных допуска,
включая entry/add-on и Android confirmed-flat reversal, теперь требуют:

`used_account_margin + new_margin + cost_buffer <= min(wallet,equity)/2`.

- Проверяется signed accountConfig: canTrade=true, one-way, single-asset;
  конфликтующие присутствующие flags в account отвергаются.
- Считается весь USDT-счёт, включая management-only и внешние позиции.
  Ненулевой foreign collateral, USDC-контракты, hedge, отсутствующие,
  отрицательные, неконечные или неоднозначные данные дают UNKNOWN.
- Position bucket — максимум root/asset/суммы строк initial margin плюс
  дополнительный isolated collateral сверх initial requirement. Он не
  складывается повторно с root totalInitialMargin.
- Open-order bucket — максимум подтверждённого root/asset order margin и
  независимой суммы остатков ordinary+algo orders. Весь bucket учтён один
  раз. Parent/child связываются по signed actualOrderId; цена/время не создают
  связь. Два durable parent не могут присвоить один child.
- Для partial учитывается только `origQty-executedQty`, уже исполненная
  часть представлена position bucket. Необработанные triggering/unknown
  POST/cancel/fill или пропавший readback запрещают NEW risk.
- Предлагаемая маржа использует подтверждённое плечо, количество и большую
  из плановой/наблюдаемой mark цены, с0.15% ценовым запасом. Дополнительно
  резервируются0.35% notional для издержек pending+new; сохраняется отдельная
  проверка95% available balance. Это допущения, не доказательство нулевого
  funding/slippage и не гарантированная верхняя цена гэп-исполнения.
- Два account-wide наблюдения требуют неизменных wallet, количества позиций,
  isolatedWallet, order/client IDs, статусов, исполненных количеств и настроек
  pending orders. Берутся худшие margin/base/price значения; сами GET не
  объявляются атомарной транзакцией. Общий срок доказательства15 секунд.

Проверка и durable reservation находятся внутри существующего account execution
lock. Расчёт записывается до POST. После rate/clock waits транспорт ещё раз
проверяет срок и точный symbol/side/quantity/client ID/limit-or-trigger price;
только явный -1021 может вызвать
единственный повтор и обязан повторить fence. Потерянный ответ не повторяет POST.

Отказ до отправки получает BLOCKED/REJECTED и освобождает только текущий,
доказанно неотправленный intent. AMBIGUOUS сохраняет прежние ID и резерв.
Для Android сохраняется отдельный signed child status/time receipt полного
исполнения; статус parent не подменяется FILLED. Новые receipts не меняют
архивную кампанию, когда текущая позиция уже противоположного направления.

STOP, trail, reduce-only exit, cancel и reconciliation не требуют этого
NEW-risk разрешения. Превышение50% само по себе не вызывает закрытие позиции
или снятие STOP. Daily3%, десять кампаний, NEW10/management15 и прежние
source/ownership/risk gates сохранены.

## Реальная локальная проверка

- Полный Python после финальной доработки: **2582 passed,2 skipped**,
  27.93s;10 предупреждений среды/
  существующего FastAPI/pandas кода. Baseline имел те же10 предупреждений.
-287 focused Python risk/transport/lifecycle случаев прошли, включая lost
  initial POST с недоступным readback, затем восстановление того же ID без
  повторного exposure POST и установку owned STOP.
-51 общих Python/Kotlin acceptance fixture: both sides, root+pending,
  partial+child, isolated top-up, management-only/external, modes/units,
  NaN/missing/negative, freshness, costs и точная граница50%.
- Рассчитанное510.595735USDT при лимите500 отклоняется. Partial с уже
  представленным order bucket даёт490.425525, без повторного счёта parent.
  Ровно500 с издержками принимается; превышение блокируется.
- Kotlin2.3.21 JVM-компиляция120 business/helper/test файлов выполняется
  локально без Android SDK; helper functions StandaloneRuntime извлечены
  без изменений, источники coordinator/adapter — настоящие. Это не APK,
  не Android lint и не physical-device acceptance. Итог CI точного коммита
  фиксируется в PR после завершения.
- Локальный настоящий coordinator/adapter lifecycle + transport/shared/Source
  JUnit: **139 tests PASS**,5 существующих ignored. Сохранились проверки
  неизменности архивной кампании после внешнего разворота, все четыре
  TC2 добавления, STOP replacement и восстановление после lost reply.
- Ruff undefined names/syntax и `ci_release_gate.py`: PASS. Версия4.64.32,
  code46432 синхронизирована в VERSION/server/Gradle.

## Что не закрыто этой реализацией

Signed Demo account/order/isolated readback и LONG/SHORT fill→STOP→trail→
disconnect/restart на физическом телефоне не выполнялись. Нет доступных
в этой сессии Binance credentials/Android transport. CI/API model не заменяет
эти результаты. Повторные observations ограничивают, но не исключают изменение
биржи после последнего GET; поведение гэпов/funding не сертифицировано.

После следующего прохода глав8–11 достигнуто823/1213 first-pass receipts,
256 visual и208 qualitative figures;104 страницы двух переводов сопоставлены
с английскими главами. Подробности и четыре дополнительных source conflicts —
в `TC2_TECHNICAL_TRANSLATION_CONTINUATION_20261114.md`.
390 непрочитанных листов переводов, независимые сложные фигуры/atomic specs,
expert cohort и заранее зафиксированный независимый holdout остаются отдельными
этапами. Старые исторические выборки не объявляются новым OOS. Полное чтение
Course PDF не заменяет отсутствующее внешнее tutorial/Computorial.

Main merge остаётся закрыт до критических проверок. Williams-AI не изменён.
