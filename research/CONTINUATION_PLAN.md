# Воспроизводимое продолжение

Текущая ветка `research/master-primary-sources-20261113`, PR240, stacked PR239.
Перед записью сверить main и ветку; не перезаписывать новые чужие SHA.
Не объединять15 старых PR пачкой. Два новых main commits проверить отдельно.

1. Повторно получить те же6 пользовательских PDF; сверить `SOURCE_CATALOG`.
   Текст и OCR являются приватными промежуточными файлами и не входят в Git.
2. `python tools/williams_master_inventory.py --source-dir <PDF_DIR>` с
   локальным OCR directory воспроизводит реестр; `review_notes.json` сохраняет
   именно60 ручных receipts и не выдаёт их за независимую сертификацию.
3. `python tools/audit_williams_master_research.py --require-complete` должен
   завершаться2, пока source/figure/implementation/Device gates не завершены.
   Успех integrity CLI без этого флага означает только валидный реестр.
4. Статус13.11: TC2_EN257/257 и TC1_RU140/140 имеют первое текстовое чтение.
   Полные визуальные/независимые проверки и межпереводные page maps не завершены.
   Следующий source route — NTD_RU, затем Course, выбирая ещё не REVIEWED страницы
   строго по очереди, без пропуска психологии/сносок.
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
