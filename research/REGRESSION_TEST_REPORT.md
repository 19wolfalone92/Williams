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
