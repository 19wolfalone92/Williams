# Регрессионные проверки

| Проверка | Факт | Предел доказательства |
|---|---|---|
| Полный baseline до правок |2086 passed,2 skipped,4 warnings,40.57s | Offline программные тесты |
| Полный после MFI/WM2 исходников |2107 passed,2 skipped,4 warnings,44.99s | Kotlin выполняется отдельно в Android CI |
| Финальный локальный после evidence tools |2111 passed,2 skipped,4 warnings,48.66s; ruff PASS | Все новые Python тесты; независимое прочтение книг не доказано |
| Целевые MFI/indicator/diagnostic |34 passed,4.49s | Сконструированные численные примеры, не оцифрованные книжные свечи |
| Release contract |PASS | Статические контракты не доказывают прибыльность/STOP на бирже |
| BEFORE/AFTER |MFI tie: SQUAT→STABLE; prefix causal: false→true | Воспроизведение исходного strategy из локального baseline Git; `MFI_BEFORE_AFTER.json` |
| Новые evidence tests |Проверяют подложный VERIFIED, потерянную/дублированную страницу, ложную authority | Не независимое прочтение книги |
| Исторический replay |15×480 реальных архивных H1;473 условные сделки | Данные ранее изучены; это не новый OOS и не Binance fill |

Replay выполняется последовательно, stop-first при неоднозначности H1,
fee0.04% и adverse slippage0.05% на сторону, cost stress×2. Исторические tick
filters2024 не подтверждены: применены сохранённые ticks2026. Не моделируются
funding/liquidation/order book/partial fills/reboot и reverse-pyramid additions.
Каждый символ имеет отдельные5000USDT; суммарный результат не является портфелем.

Не менялись книжные правила для улучшения PnL. После прочтения результатов
этот набор остаётся seen. Полный ledger исходных SHA и ограничения —
`HISTORICAL_VALIDATION_RECEIPT.json`; CI ссылки сохранены отдельно после проверки.

До объявления успеха требуются exact-final-SHA CI, signed Demo LONG/SHORT
fill→owned protective STOP→trail/restart/reconcile и физический Android.

## Продолжение4.64.30

- Повторён исходный SHA4ae2b2a0: **2111 passed,2 skipped,4 warnings,52.52s**.
- После новых исправлений, lifecycle/symbol и evidence tests:
  **2237 passed,2 skipped,4 warnings,47.73s**. Это полный Python набор.
- Окончательный прогон после явной проверки nullable `Float64`/`Int64` с
  `pd.NA`: **2239 passed,2 skipped,4 warnings,61.37s**. Пропуск не может
  обойти проверку ни в seed, ни в рекурсии независимо от dtype чисел.
- `ruff --select F821,F822,F823,E9`: PASS; release contract: PASS.
- Integrity CLI: PASS; `--require-complete`: ожидаемый **exit2**, не сертификация.
- Fresh inventory:1213 строк,120 reviewed receipts;1091 строк совпали с
  сохранённым реестром полностью.122 Course acquisition rows требуют прежнего
  private OCR каталога (119 OCR-stage и3 reviewed с OCR). Старые bytes/hashes
  не перезаписаны свежим пустым текстовым слоем.
- `ALLIGATOR_BEFORE_AFTER.json`: прежний Python выдавал seed100 при одном NaN
  в пяти входах; теперь UNKNOWN, как Android. Полные конечные серии совпадают.
- Сценарии всех пяти management-only символов в обе стороны моделируют
  unfilled/partial/triggering/unknown и восстановление того же owner/STOP,
  без нового entry POST. Это локальная модель биржи, не signed Demo.
- Прямой запуск static safety audit без production DB сообщает её отсутствие;
  такая DB в checkout не поставляется. Режим clean-CI проверяется отдельно:
  это статический аудит без инспекции пользовательской production DB.
- Новые Kotlin unit/lint/APK/emulator результаты должны быть привязаны
  к новому head SHA в Actions; старый зелёный4ae2b2a0 за них не принимается.

## Продолжение4.64.31 иTC1 first pass

- AccountV3/config targeted: **206 passed**, включая lifecycle и endpoint
  regression; полного набора после account repair —2360 passed/2 skipped.
- После TC1 evidence integrity и Decimal ledger: **2387 passed,2 skipped,
  4 прежних FastAPI deprecation warnings,26.72s**;131 focused source/adapter/
  arithmetic tests passed отдельно, в том числе24 для явных чиселTC1.
- `ruff --select F821,F822,F823,E9`: PASS; `ci_release_gate.py`: PASS.
- Clean-CI root static safety: **0 failures,11 warnings**. Предупреждения
  относятся к существующим одноимённым методам разных классов, subprocess
  emulator/replay helpers и AST regression test. Production DB здесь отсутствует.
- Master evidence integrity: PASS; `--require-complete`: ожидаемыйexit2.
  Printed-number audit воспроизводит сохранённыйJSON, но
  `--require-reconciled`: ожидаемыйexit2, поскольку исходные итоги расходятся.
- Повторно сверены6 PDF и1091 non-Course inventory rows: совпали все поля,
  включая page_kind.122 исходных Course OCR acquisition rows сохранены.
- Исторические OOS/фактические fills/стоимость сделок/портфельная маржа
  не получены из книжных таблиц. Нет новой прибыли или процента понимания модели.
- Коммит account repair44ffcef4 отдельно завершил всеCI: Python43454243137,
  Campaign43454243151/43454228110, APK+API35/36 smoke43454243156,
  research43454242985/43454228116. Это доказательства именно44ffcef4.
  Результаты конечного source/audit commit будут указаны в PR240 по егоSHA.

## NTD first pass и unknown-price regression

- Полный Python: **2444 passed,2 skipped,4 прежних FastAPI warnings,27.32s**.
  Новые30 NTD price/index cases,24 printed-number/provenance и3 master evidence
  cases. Nullable/nonfinite данные не являются доказательством непробитого уровня.
- Ruff/release contract:PASS; clean-CI static safety0failures/11existing warnings.
- Две NTD таблицы и явная арифметика воспроизводятся отдельным Decimal CLI.
  AC table6-1 служит положительным контролем; Zone table7-1 содержит typo
  point cell7.55 против78.55. Signal price не подставляется вместо reported fill.
- Reinventory1091 non-Course rows совпал;122 original Course OCR rows и
  все1213 acquisition hashes сохранены. IntegrityPASS, complete/numeric
  reconciled gates ожидаемоexit2. Никакого нового backtest/OOS/PnL доказательства.
- Добавлен Kotlin зеркальный regression; native production не менялся.
  Exact-head CI нового commit проверяется отдельно и фиксируется вPR240.

## Course first pass и OCR provenance

- Полный Python: **2450 passed,2 skipped,4 прежних warnings,27.80s**.
 6 новых regression cases покрывают provenance, physical scan/external gap
 и сохранение original acquisition hash отдельно от нового review-text hash.
- Ruff/releasePASS, clean-CI static0failures/11existing warnings. Новый source
 commit не меняет Android production; его exact-head CI проверяется отдельно.
-1091 non-Course rows воспроизведены полностью,1213 old acquisition hashes/lengths
 сохранены,122 derived OCR hashes совпали с фактическими новыми private bytes.
 Пустой OCR на graphic page56 без visual receipt не считается прочтением.
- Evidence integrityPASS; complete gate ожидаемоexit2. Первое чтение Course122
 не доказывает получение отсутствующих Computorial/personal tutorial материалов,
 полного понимания рисунков, экспертную/model accuracy или прибыльность.
- Предыдущий NTD commit2ebadb0f завершил все7CI success: Python43502176524,
 Campaign43502176421/43502162808, research43502176265/43502162760,
 dependency43502162533, APK/lint/unit/API35+36 UI/lifecycle43502176443.

## Неизвестная конфигурация per-entry маржи

- До/после выполнен исходный sizer7d1f322e и исправленный код на одном
 scripted account. NaN/Infinity больше не повышают margin fraction до0.90.
 При конечном0.25 результат сохранён:250USDT из1000,quantity5.
-8 новых случаев,71 targeted risk/runtime tests. Полный Python:
 **2458 passed,2 skipped,4 прежних warnings,27.32s**.
- Ruff/releasePASS; clean-CI static0failures/11existing warnings.
 Новая quantity не рассчитывается при неконечном/нечисловом лимите.
- Это не account-wide50% margin gate, не real Demo и не новый market backtest.
 Source/OCR identities не менялись; exact-final-SHA CI фиксируется вPR240.

## TC2 structure и глава12 —14.11.2026

- Полный Python после финального уточнения неизвестного пятого исполнения:
  **2495 passed,2 skipped,4 прежних FastAPI warnings,39.04s**.
  Включены37 новых случаев `tests/test_tc2_translation_audit.py`.
- Полные795 физических листов трёх TC2 распределены по разделам, но68 crops
  заголовков не увеличивают semantic/visual full-page counters.16 выбранных
  semantic anchors не являются полным переводным сопоставлением.
- Сопоставлены все Chapter12 leaves трёх файлов и7 рисунков в каждом переводе.
  Новых first-pass receipts26: итого724/1213,216 visual,157 figure entries.
  Остальные489 страниц двух переводов не объявлены прочитанными; independent0.
- Три видимых translation/label discrepancies и три контекстных caveats
  закреплены6 cross-edition records. Scope/authority, source hashes, повторные
  edition IDs, перекрытия и unread citations проверяются новым CLI и master audit.
- Decimal gold totals14660/47100 сходятся при4/13контрактах; matched-risk/net
  не установлен. Fifth profit row отсутствует, реальное пятое исполнение неизвестно.
  Costco displayed gross9.30 при условном1share/entry против printed9.29
  не сертифицируется как ошибка при приблизительных ценах/неизвестных весах.
- AO PDF247 metadata исправлены с Appendix3 на Appendix7 без смены формулы.
  AI synthetic example заново закреплён и остаётся ABSTAIN/authority=false.
- Ruff/release/book-registry integrity:PASS. Static safety в clean-CI режиме:
  **0failures/11existing warnings**; production DB не инспектировалась.
- Все6 source SHA повторно совпали;1091 non-Course inventory rows воспроизведены
  полностью;1213 acquisition identities/lengths сохранены,122 Course rows неизменны.
- Master/translation integrityPASS; оба `--require-complete` ожидаемоexit2.
  Native production не менялся; CI нового точного SHA будет зафиксирован вPR240.
