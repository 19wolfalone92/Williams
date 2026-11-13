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
