# Воспроизводимое продолжение

Текущая ветка `research/master-primary-sources-20261113`, PR240, stacked PR239.
Перед записью сверить main и ветку; не перезаписывать новые чужие SHA.
Не объединять15 старых PR пачкой. Два новых main commits проверить отдельно.

Актуальное продолжение14.11,4.64.32: aggregate half-margin gate обоих
runtimes реализован, shared fixtures и финальные transport fences добавлены.
Подробности/границы: `ACCOUNT_HALF_MARGIN_IMPLEMENTATION_20261114.md`.
Исторические «ещё не подключён» ниже относятся к прошлым checkpoints.
Signed Demo/device acceptance по-прежнему открыта; main не объединён.

Актуальный source checkpoint: первый текстовый/cover/blank проход всех1213
физических листов завершён; RU224224/224, RU314314/314. Для второго перевода
в17 общих разделах явно отмечено повторное использование совпавших токенов
из прочитанного первого и ручное чтение всех изменённых фрагментов; это
не независимое повторное чтение и не полная двусторонняя semantic map.
Visual290,figures208,independent0; технических page mappings104, selected
anchors16. Отчёт: `TC2_TRANSLATION_FIRST_TEXT_PASS_20261114.md`.
Предыдущие823/1213 и «390 осталось» ниже — исторические checkpoints.
Следующий source route — полная двусторонняя карта с учётом пропусков,
независимая проверка рисунков и atomic specs. Source/time admission повторно
проверяется транспортом после margin reads, без продления сигнала.
Проверенный APK4.64.32 и все7 успешных code CI закреплены отдельно:
`ANDROID_46432_VERIFICATION_20261114.md`.

1. Повторно получить те же6 пользовательских PDF; сверить `SOURCE_CATALOG`.
   Текст и OCR являются приватными промежуточными файлами и не входят в Git.
2. `python tools/williams_master_inventory.py --source-dir <PDF_DIR>` с
   локальным OCR directory воспроизводит реестр; `review_notes.json` сохраняет
   ручные receipts (сейчас1213) и не выдаёт их за независимую сертификацию.
   Без старого Course private OCR сохранять прежние122 acquisition rows;
   нельзя заменять их пустым text-layer extraction.
3. `python tools/audit_williams_master_research.py --require-complete` должен
   завершаться2, пока source/figure/implementation/Device gates не завершены.
   Успех integrity CLI без этого флага означает только валидный реестр.
4. Статус14.11: TC2_EN257/257, TC1_RU140/140, NTD_RU156/156 и
   Course122/122 имеют первое текстовое/OCR чтение предоставленного файла.
   Глава12 сопоставлена с обоими переводами. Структурная карта всех795 листов
   TC2 готова, но не заменяет чтение/смысловую карту;68 heading crops отдельно.
   Главы8–11 обоих переводов прочитаны и сопоставлены; остальные разделы
   завершили первый текстовый проход с указанным token-reuse методом.
   Следующий source route — полная смысловая двусторонняя page map.
   Course external tutorial/Computorial отсутствуют вPDF;
   первое чтение122 физических листов не закрывает этот пробел.
5. Независимое повторное чтение сложных9.10/10.7/11.2/11.7/12.1-4. Не
   реконструировать точные OHLC из нечисленных рисунков; отдельные synthetic cases.
6. Для10 requested symbols: separate NEW risk allowlist во всех final doors,
   существующие15 остаются доступными reconciliation/STOP/exit; отдельно проверить
   unfilled, partial, triggering, unknown old intents до изменения допуска.
7. Actual Demo/device: LONG+SHORT entry→partial/full→owned STOP readback→trail,
   disconnect, unknown POST, process restart, screen off; измерить сегменты latency.
8. Реализовывать только source-confirmed расхождения; большие WM1 углы и
   расширенные фракталы сначала закрепить качественными эталонами, не ATR-fit.
9. AI: versioned candidate schema adapter без изменения старого train/test;
   expert source/market cohort прежде нового обучения.
10. Полные Python/Android CI и независимый исторический holdout после фиксации
    правил; комиссии/funding/slippage, combined pending/position risk; повторный
    root audit. Merge main разрешён только после всех критических gates.

Для существующего seen replay:
`python tools/replay_frozen_h1_five_symbol.py --outdir build/master-replay`.
Это воспроизведение уже изученных2024 данных, не новая независимая проверка.

## Продолжение 13.11.2026 — 4.64.30

Полная проверка десяти пунктов: `CONTINUATION_AUDIT_20261113.md`.
Повторно проверены6 исходных PDF; baseline2111 passed/2 skipped воспроизведён.
Прочитаны EN182–204, глава8 EN124–137, Appendix7 EN245–246 и выбранные
русские соответствия. Теперь120 semantic receipts,52 рисунка,81 правило;
независимых проверок по-прежнему0. Appendix с исходным кодом —7, не3.

Исправлен Python SMMA seed, пропускавший NaN; неконечные расчёты UNKNOWN.
NEW risk ограничен10 парами независимо от management/scan15. Старые уже
размещённые pending/unknown заявки сохраняют идентичность и резерв и могут
исполниться; никакой отмены или повторного POST по одному символу.

Следующий маршрут: TC2 EN205–244 (Appendices2–7 до уже прочитанного кода),
затем непрочитанные EN1–123, TC1/NTD/Course строго по очереди из register.
Отдельно расширять `TC2_TRANSLATION_PAGE_MAP.json`: сейчас только7 смысловых
якорей, не полная карта. Сложные фигуры из п.5 всё ещё требуют независимого
чтения. Не удалять старые119 OCR hashes, пока не получен тот же private OCR.
Устройство/Demo, expert cohort/adapter, полноценный OOS и merge gates открыты.

## Продолжение 13.11.2026 — первое чтение TC2 EN и adapter V1

Отчёт: `TC2_TEXT_PASS_AND_AI_ADAPTER_20261113.md`. Все257 страниц TC2_EN теперь
имеют first-pass receipt; всего295/1213 физических страниц,85 рисунков,91 правило.
В числе страниц также front matter, blank и navigation; independent review0.
Психологические упражнения/авторские performance claims не стали machine facts.
Добавлен8-й смысловой translation anchor half-margin FAQ; полноценной карты нет.
Aggregate50% margin — подтверждённый источник, но mapping/account ledger и
операционное ограничение пока не реализованы. Старые risk/STOP gates сохранены.

Пункт9 продвинут: `williams_ai_candidate_adapter.py` и offline CLI реализованы,
source/observation/request pins и citation allowlist проверяются. Authority/training
запрещены; направление отклоняется на неполной atomic spec. Qwen, expert cohort,
новое обучение и изменения frozen train/test не выполнялись. Код к trading path
не подключён. Детали: `AI_CANDIDATE_ADAPTER_V1.md`.

Следующий source route: TC1_RU от первого ещё не REVIEWED листа, затем NTD_RU и
Course. Доработать aggregate-margin contract/проверки двух runtimes. Пункты5,7,
independent expert/holdout и merge gates остаются открыты. Новые локальные проверки:
2337 passed/2 skipped; CI результаты точного нового SHA сохраняются в PR240.

Повторная инвентаризация воспроизвела1091 non-Course rows; page_kind теперь
сохраняется exporter и сверяется audit с manual receipt.122 исходных Course OCR
rows сохранены. Контракт также проверен на45 seen historical H1 срезах;
будущая свеча отвергнута во всех45 случаях, gold/model экзамен не заявляется.

## Продолжение13.11.2026 —4.64.31, полный первый проходTC1

Отчёты: `TC1_FIRST_PASS_AND_NUMERIC_AUDIT_20261113.md` и
`ACCOUNT_V3_PERMISSION_CONTINUATION_20261113.md`. Исторические checkpoints
выше сохранены; актуальный следующий маршрут задан здесь и в пункте4.

1. TC1 прочитан140/140, включая психологию/вопросы/заключение. Теперь428/1213
   first-pass receipts,136 visual,110 qualitative figure entries,117 rules,
   independent0. Полных atomic specs по-прежнему4, все для intraday MFI signs.
   Это не полное прочтение всех рисунков и не сертификация четырёх книг.
2.10 видимых source conflicts записаны сSHA/страницами. В частности, рисунок
   7-20 повторяет15-minute изображение под5-minute подписью; не считать это
   доказательством настоящего младшего ряда. Не выбирать выгодную опечатку.
3. Реализован Decimal FIFO аудит явно напечатанных цен/количеств CHF/soy:
   `tools/audit_tc1_worked_examples.py`. Gross/net, actual/hypothetical,
   body/chart prices разделены. Report воспроизводится,24 новых целевых теста
   прошли; `--require-reconciled` ожидаемоexit2 из-за ошибок источника.
4. В Python/Kotlin исправлено чтение отсутствующего в документированнойV3
   форме `canTrade` через signed accountConfig. Буквальныеfalse сохраняются,
   неверные типы/конфликты отклоняются, баланс конфигом не перезаписывается.
5. Общий50% margin cap **ещё не подключён**. Mapping, pending/partial/unknown
   liabilities, units, isolated collateral и final-submit gate определены в
   `ACCOUNT_HALF_MARGIN_CONTRACT_V1.md` как следующий контракт реализации.
6. Локально2387 passed/2 skipped/4 прежних FastAPI warnings; ruff/release PASS.
   Static safety в clean-CI режиме:0 failures,11 разобранных предупреждений
   по существующим duplicate-name/subprocess/AST-test patterns, без production DB.
   Fresh1091 non-Course rows совпали полностью;122 старых Course OCR rows сохранены.

Продолжать NTD_RU → Course, параллельно по смыслу завершать margin ledger,
затем независимые source/figure/expert и заранее зафиксированный holdout.
Signed Demo/physical Android из пункта7 не выполнены. Main merge закрыт;
exact-head CI этой контрольной точки сохраняется в PR240, не приписывается старомуSHA.

## Продолжение13.11.2026 — NTD и проверка неизвестных цен

Отчёт: `NTD_FIRST_PASS_AND_DATA_GUARD_20261113.md`. NTD156/156 теперь имеют
текстовые first-pass receipts, включая психологию и редакторское предисловие;
35 visual,22 новых качественных figure entries. Глобально579/1213 receipts,
168 visual,132 figures,135 rules,111 source-rechecked, independent0.
Полных atomic specs осталось4; спорные формулы/тексты не стали gold.

Исправлен воспроизведённый Python NTD дефект: поздний NaN экстремума больше
не доказывает, что старый фрактал не был пробит. Неконечные/невозможные OHLC
и неверные индексы не создают кандидата; обе стороны покрыты регрессиями.
Добавлены15 NTD source conflicts, аудит двух напечатанных таблиц и явной
арифметики. AC таблица сходится; Zone строка6 содержит7.55 против78.55.
Фактические fills, fees/net/margin path из этих таблиц неизвестны.

Полный локальный набор2444 passed/2 skipped/4 warnings,27.32s; ruff/releasePASS,
static safety0failures/11existing warnings.1091 non-Course inventory rows
воспроизведены;122 старых Course OCR rows и все acquisition hashes сохранены.
Complete и printed-number reconciled gates ожидаемоexit2. Новое exact-headCI
сохраняется вPR240 после завершения; версияAndroid остаётся4.64.31.

Продолжать Course по настоящим изображениям/OCR, не считать пустой raw text
прочитанным содержимым. Затем полная translation map, независимые фигуры,
margin ledger/final-submit, expert/holdout и actual Demo/device. Main не изменён.

## Продолжение13.11.2026 — Course и отдельная OCR identity

Отчёт: `COURSE_FIRST_PASS_AND_OCR_PROVENANCE_20261113.md`.
122/122 Course physical first-pass receipts,25 visual,11 новых рисунков;
все основные supplied копии четырёх семей имеют первое чтение. Глобально
698/1213 receipts,190 visual,143 figures,147 rules,142 source-context rechecked,
independent0. Остальные515 физических страниц — два переводаTC2.

Новый приватный OCR получен отдельно и не заменил старые122 acquisition hashes.
`COURSE_REVIEW_OCR_PROVENANCE.json` и тесты различают их, запрещают пустой OCR
как прочтение без render и не выдают Level5 external tutorial за полученный материал.
На56 действительно находится рисунок, на52 рукописноеnot, на122 — явная отсылка
к личному занятию.7 caveat records не являются сертифицированными errata автора.

Полный Python2450 passed/2 skipped/4 warnings,27.80s;ruff/releasePASS,
static0failures/11warnings.1091 non-Course rows воспроизведены; все1213 старых
hashes/lengths и122 новых derived hashes сверены. Complete gate ожидаемоexit2.
Android production не менялся; exact-head CI нового source commit фиксируетсявPR240.

Приоритет продолжения: полная translation map, независимые figures/atomic specs,
aggregate half-margin ledger и final-submit обоих runtimes, expert cohort и
заранее зафиксированный holdout. Actual Demo/device и main merge gates открыты.

## Продолжение13.11.2026 — неконечный per-entry margin limit

Проверка risk sizing нашла ещё один воспроизводимый дефект: clamp разрешал
NaN/Infinity превратиться в0.90, а не UNKNOWN. В scripted account1000USDT
маржа становилась347.22 вместо установленного предела250. Теперь parser/finite
guard отклоняет такой sizing до quantity normalization. Конечные настройки
сохранили прежнее поведение; общий50% cap этим не реализован.

Отчёт `MARGIN_CONFIG_NONFINITE_GUARD_20261113.md`, реальный BEFORE/AFTER двух
версий `MARGIN_CONFIG_BEFORE_AFTER.json`.8 новых tests,71 targeted,
полный Python2458 passed/2 skipped/4 warnings,27.32s;ruff/releasePASS,
static0failures/11existing warnings. Source counters698/190/143/147 и все
старые acquisition hashes не менялись; complete gate остаётсяexit2.
Следующие этапы и запрет main merge из предыдущей контрольной точки сохранены.

## Продолжение14.11.2026 — структураTC2 и глава12

Отчёт `TC2_TRANSLATION_CH12_CONTINUATION_20261114.md`. Навигационная карта
`TC2_EDITION_STRUCTURE_MAP.json` покрывает все795 физических листов трёх TC2,
но68 heading crops не засчитываются как полное чтение страниц. Все32 листа
Chapter12 двух переводов сопоставлены с English174–187; новых receipts26,
итого724/1213,216 visual,157 qualitative figure entries,16 selected anchors.
147 rules/142 source-rechecked/4 atomic specs/independent0 сохранены.

Три видимых расхождения перевода/подписей и три caveats закреплены6 records.
Short bearish geometry и положительный buy-stop tick offset не заменены
ошибочными Russian labels. AO code PDF247 относится к Appendix7; старое
указание Appendix3 исправлено в наших реестрах. Production trading path не менялся.
Gold47100/14660 сравнивает разные4/13контрактов, не equal-risk return;
Costco9.30/9.29 при approximate prices не стал сертифицированным erratum/net.

Новый audit/37 regression cases не допускают ложной полноты, подмены headings
за full-page review или выдуманной пятой profit row. Полный Python2495 passed,
2 skipped/4existing warnings,39.04s;ruff/releasePASS,static0failures/11warnings.
1091 non-Course rows воспроизведены,1213 acquisition identities сохранены;
master/translation complete gates ожидаемоexit2. Новый exact-head CI — вPR240.

Следующий source route: полная смысловая сверка технических глав8–11 двух
переводов, затем оставшиеся главы/приложения (489 непрочитанных физических листов).
Независимые figures/atomic specs, aggregate half-margin ledger/final-submit,
expert cohort/holdout и actual signed Demo/physical Android остаются открыты.
Main и отдельный Williams-AI не изменены. Не объединять старые PR пачкой.
