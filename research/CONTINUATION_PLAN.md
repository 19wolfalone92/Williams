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
4. Продолжать TC2 EN182-204, затем Alligator chapter8 и source code appendix;
   сделать межпереводные page maps RU224/RU314. Далее постранично TC1/NTD/Course,
   выбирая из реестра ещё не REVIEWED страницы, без пропуска психологии/сносок.
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
