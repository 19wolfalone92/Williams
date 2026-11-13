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
