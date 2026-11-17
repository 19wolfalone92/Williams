# TC2: численные спецификации и исходная третья свеча AO

14.11.2026, Williams Bot4.64.33. Продолжение PR240 от `fa81228c`.
Williams-AI, main, реальные ордера и исходные train/test не изменялись.

## Подтверждённый дефект WM2

В Python проверялись три направленных изменения AO, но не начало серии.
При согласованной реальной серии из6 изменений и ошибочном cached count4
`_latest_super_ao` принимал свечу38 вместо исходной36. Ошибка воспроизведена
для LONG и SHORT на реальном extractor, не на подменённом signal helper.
Менялись исходные high/low, signal ID/time, trigger, начальный STOP и expiry.
Неконечный cached count также мог вызвать `OverflowError` и прервать сканер.

Теперь count должен быть конечным целым, а доказательство AO проверяет
настоящую границу начала серии. Четвёртая/пятая/шестая свеча той же серии не
получает новую идентичность «третьей». При испорченном текущем count можно
сохранить ранее доказанную исходную свечу; неизвестный count не заменяет AO.
Пропуск перед первым конечным AO разрывает серию и не создаёт неизвестное
сравнение. Будущие свечи для проверки начала серии не читаются.

Новые22 Python-регрессии до исправления дали18fail/4pass. После исправления
фокусный набор WM2/geometry/exact-grid дал87pass. Полный цикл после всех
дополнений:2670passed/2skipped,39.16s,10 прежних warnings среды/FastAPI/pandas.
Kotlin JVM:7tests passed;29 shared vectors выполняются внутри test methods,
а не выдаются за29 отдельных JUnit tests. Перед исправлением finite-overflow
case воспроизвёл1failure в7tests на старом извлечённом production helper.
Release/source/numeric audits проходят; root static0failures/11 прежних
warnings. Android SDK build/API35/36 проверяются отдельно в CI точного commit.
Эти искусственные данные не устанавливают причину прошлых пользовательских
сделок и не восстанавливают авторские OHLC.

## Alligator и AO как два отдельных численных правила

`TC2_INDICATOR_ATOMIC_SPEC_20261114.json` фиксирует:

- median=(H+L)/2; SMMA seed ровноN первых конечных samples, затем рекурсия;
- пары13/8,8/5,5/3; на отображаемой свечеt используется raw[t-shift];
- AO=SMA5(median)-SMA34(median); первый AO33, первое наблюдаемое сравнение34,
  третье последовательное изменение возможно36 при целой начальной истории;
- GREEN/RED определяются строгим сравнением AO с предыдущим AO. Положение
  относительно нуля и цвет тела ценовой свечи этого не определяют;
- UNKNOWN при недостоверных данных. SMMA не пропускает дырку и не создаёт
  короткий seed; AO может восстановиться после полного чистого окна.

Первый supplied-window bootstrap, gap/overflow guards — явные инженерные
границы. Скрытая TradeStation prehistory/MaxBarsBack не сертифицируется.
Формулы не дают самостоятельно правила входа, выхода или управления STOP.
Видимый tail содержит уже вычисленные значения; новых OHLC из него нет.

Общий `tc2_indicator_atomic_contract.json`:15 численных наборов и14 AO traces
для двух направлений. Числа рассчитаны отдельной Decimal60 реализацией,
не экспортированы из проверяемых Python/Kotlin функций. Векторы проверяют
фактический Python indicator bundle и Kotlin helpers, включая prefix stability,
независимость от close, начальные offsets, zero touch/cross, ties и gaps.
Это независимый численный расчёт одним автором, не второй экспертный обзор.

Отдельный Kotlin тест воспроизвёл нечисленный результат AO при переполнении
суммы конечных синтетических input prices. `awesomeOscillatorAt` теперь
возвращает UNKNOWN/NaN вместо Infinite AO. Этот пример не заявляет, что
подобные цены наблюдались на Binance. Неаттестованные числовые значения не
должны попадать в направленную интерпретацию или JSON-диагностику.

Теперь148 зарегистрированных правил,143 source-context checked и6 завершённых
**ограниченных** atomic definitions: прежние4 MFI + Alligator + AO numeric.
Полный WM2 lifecycle, figure interpretation и весь корпус правил не объявлены
завершёнными. `FULL_ATOMIC_SPEC_COMPLETE` не означает independent/execution
certification; источник по-прежнему не разрешает обучение или ордера.

Полный Python прогон обнаружил регрессию при расширении реестра: старый
research candidate adapter мог принять complete numeric definition за достаточную
directional definition. Guard исправлен: numeric-only записи не разрешают
LONG/SHORT; для directional candidate требуется отдельная явная пригодность
правила. Все execution/training/STOP authorities остаются literal false.
Это локальный offline adapter в Williams Bot; Williams-AI не изменён.

## Сверка приложения7

`TC2_APPENDIX7_CODE_ALIGNMENT_20261114.json` содержит17 постраничных
сопоставлений обоих переводов с EN240–247. Title-only, blank294,
caption-only/screenshots и содержательные code/text spans различены.
К исходным104 technical mappings глав8–11 добавлена отдельная метрика17,
а не ложное утверждение о полной semantic map всех795 листов TC2.

Полностью просмотрены8 code-page renders EN245–247, RU224223–224,
RU314300–302. В RU224 ошибки identifiers/median/punctuation действительно
напечатаны на странице, а не возникли только при OCR. Численная семантика
сверена с EN и RU314; никакой EasyLanguage listing не объявлен compiler-tested.
Новое сравнение увеличило TC2 conflict register до15.

First-pass receipts остаются1213/1213, actual visual295, qualitative figures208,
independent0. Все source/acquisition identities и122 Course OCR rows сохранены.
Полные PDF, извлечённый текст и renders в Git не публикуются.

## Проверка и следующий этап

`audit_tc2_indicator_spec.py` проверяет provenance/parameters/fixture SHA,
но прямо сообщает, что не выполняет runtime contract и не сертифицирует
рынок. Master audit включает эти проверки; complete gate остаётся exit2.

Следующий source этап — полная двусторонняя сверка остальных приложений,
разделов и пропусков; независимые сложные рисунки и остальные atomic specs.
Signed Demo LONG/SHORT fill→owned STOP→trail→disconnect/restart и физический
телефон остаются открытыми. CI/синтетика не заменяют эту приёмку.
