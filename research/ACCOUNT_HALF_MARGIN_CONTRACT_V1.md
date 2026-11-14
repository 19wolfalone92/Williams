# Половина счёта: контракт данных до подключения ограничения

Статус14.11.2026: **IMPLEMENTED IN PR240 / DEMO ACCEPTANCE OPEN**.
Оба финальных NEW-risk допуска используют общий50% envelope; алгоритм,
инженерные допущения и проверки: `ACCOUNT_HALF_MARGIN_IMPLEMENTATION_20261114.md`.
Ниже сохранён исходный дизайн13.11; таблица биржевой приёмки не стала
списком выполненных signed Demo испытаний.

## Что подтверждено книгами

- TC2_EN PDF212–213, FAQ, и выбранные соответствия RU224/192, RU314/255:
  ограничение относится к использованной марже всего счёта. Не к проценту
  стоп-убытка одной сделки. Source pins — в `TC2_TRANSLATION_PAGE_MAP.json`.
- TC1_RU PDF91/93 также описывает примеры с маржой не более половины счёта.
  Арифметические ошибки этих примеров учтены отдельно. Реального временного
  ряда equity/margin в книгах нет, соблюдение лимита по ним не воспроизведено.
- TC1_RU PDF136–137 предлагает суммировать гипотетические стоп-убытки и
  уменьшать неприемлемый риск. Это другая величина. «Без риска» там прямо
  поясняется как психологическое принятие возможной потери денег.

Ни одна из формулировок не гарантирует, что при ликвидации, гэпе, funding или
проскальзывании останется ровно половина средств. Книга не задаёт Binance API,
atomic snapshot protocol или числовую трактовку isolated collateral.

## Проверенный API и исправление4.64.31

Первичный источник, проверен13.11.2026:
[Binance USDⓈ-M Account REST](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/account).

| Данные | Значение для реализации |
|---|---|
| `/fapi/v3/account` | Балансы, маржинальные требования и позиции; ответV3 не документирует флаг `canTrade` |
| `/fapi/v1/accountConfig` | Signed чтение `canTrade`, `dualSidePosition`, `multiAssetsMargin` |
| `totalInitialMargin` | Требуемая initial margin; root в single-asset относится кUSDT, в multi-assets — кUSD и cross exposure |
| `totalWalletBalance`, `totalUnrealizedProfit`, `totalMarginBalance` | Различные величины; нельзя подменять одну другой без выбранной политики |
| `availableBalance` | Доступный баланс, не доказательство уже занятой маржи |
| `assets[]`, `positions[]` | Детализация; строки позицийV3 относятся к имеющимся позициям/открытым ордерам |
| `isolatedWallet`, `isolatedMargin`, `initialMargin` | Разные показатели; collateral не тождественен требуемой initial margin |

В Python и Kotlin исправлено соединениеV3 с signed accountConfig при
отсутствующем `canTrade`: только буквальные boolean, запрет благоприятных
значений по умолчанию, сохранениеfalse и запрет перезаписи балансов конфигом.
Подробные проверки — `ACCOUNT_V3_PERMISSION_CONTINUATION_20261113.md`.

Документация не дала достаточного доказательства, что все ещё не сработавшие
conditional orders уже включены в root margin. Это **не** доказательство их
отсутствия: нужен signed readback с известными ordinary/algo orders и fills.

## Предлагаемое правило NEW risk

Следующая инженерная политика требует отдельной реализации/приёмки:

`M_confirmed + R_unrepresented + M_new + C_buffer <= 0.5 * B`

- `B = min(wallet, equity)` вUSDT при подтверждённом single-asset/one-way
  режиме, положительных конечных значениях. Использование меньшего из двух
  — консервативное инженерное решение, **не** дословная формула книги.
- `M_confirmed`: подтверждённая занятая маржа всего аккаунта, включая
  внешние позиции и инструменты вне NEW-risk allowlist. Отдельно определить,
  как учитывается дополнительно внесённый isolated collateral. Не складывать
  root и те же строки повторно.
- `R_unrepresented`: непредставленная в `M_confirmed` часть liabilities из
  ordinary/algo orders и durable intents. Для partial — только оставшаяся
  часть после подтверждённой сверки fills/child IDs. Не освобождать unknown
  POST, triggering parent или cancel без terminal readback.
- `M_new`: верхняя оценка дополнительной маржи по запрашиваемому действию,
  с реальным leverage/bracket, ценой и quantity. Reverse отдельно раскладывается
  на закрываемую и вновь открываемую части; TC1 order5 ≠ exposure5 при long3.
- `C_buffer`: отдельно обоснованный резерв издержек. Нельзя выдавать0 за
  доказанное отсутствие комиссий/funding/slippage.

При невозможности доказать дедупликацию, единицы или свежесть результат
`UNKNOWN`: запрещается NEW risk, а management/STOP/exit/reconciliation
продолжаются. Не выводить положительное разрешение из одного
`availableBalance`, локального notional/leverage или признака `canTrade`.

## Места подключения и порядок

Python: `futures_williams_runtime.py`, `_size_from_risk` и окончательная
`_futures_pre_submit_checks` внутри общего serialized execution barrier.
Android: `NativeFuturesCampaignCoordinator.kt`, serialized submit entry/add/
reverse с повторной оценкой перед transport. `parseFuturesAccountSnapshot`
сейчас теряет поля initial margin; тип snapshot сначала нужно расширить.

Сначала сохранить account-wide snapshot с account/mode/asset identity,
ordinary+algo order IDs, fill quantities и журналом резервов. Затем определить
правила объединения и freshness, добавить общий fixture contract для
Python/Kotlin, подключить NEW-risk gate к каждому final submit door и выполнить
signed Demo readback. Разные HTTP GET не являются атомарным снимком сами по себе.

## Критерии следующей приёмки — пока НЕ выполнены

| Сценарий | Требуемое поведение |
|---|---|
|1000USDT base,440 confirmed,40 residual,30 new | Блокировать:510>500 до дополнительных costs |
|Partial parent и уже учтённый child | Не считать исполненную часть дважды; неизвестное соответствие блокирует NEW risk |
|Unknown POST / triggering / unconfirmed cancel | Сохранить резерв и прежний client/parent/child identity |
|Позиция management-only или внешний ордер | Учесть в account-wide exposure, не открывать дублирующую кампанию |
|USDC/multi-assets/hedge либо неподтверждённый mode | UNKNOWN для первойUSDT-версии контракта |
|Isolated top-up или изменившийся leverage | Повторная оценка, без использования устаревшей оценки notional/leverage |
|Недостающие/NaN/отрицательные поля, рассогласованные reads | UNKNOWN; management остаётся доступен |
|Одновременные сигналы разных символов | Одна сериализованная проверка+durable reservation перед каждым POST |
|Лимит уже превышен | Запретить NEW risk; само превышение не является командой снять STOP или рыночно закрыть всё |

Это таблица приёмки, а не список выполненных биржевых тестов. Действующие
daily3%, максимум10 кампаний, NEW10/management15 и защитные STOP gates сохраняются.
