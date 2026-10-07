# WILLIAMS TESTNET RELEASE GATE

Этот чек-лист является обязательным регрессионным контрактом перед любой новой установкой APK и перед включением Binance Spot Testnet.

## Правило

Релиз не считается зелёным по одному BUILD или pytest.

Обязательная комбинация:
**BUILD PASS + UNIT PASS + INTEGRATION PASS + BINANCE TESTNET E2E PASS + RECOVERY PASS + APK PASS.**

## 30 шагов

1. Чистая установка APK.
2. TESTNET выбран и сохранён.
3. Backend URL/token проходят проверку.
4. Binance Spot Testnet аутентификация проходит.
5. Account/trading status проходит.
6. exchangeInfo и символы доступны.
7. PRICE/LOT/MIN_NOTIONAL фильтры соблюдаются.
8. Market data свежие.
9. Scanner стартует и heartbeat обновляется.
10. Scanner выбирает корректный universe/candidates.
11. Signal → Wave → MTF связка проходит.
12. Wave 3 предпочтительнее исчерпанной Wave 5.
13. Nested lower-TF Wave 3 внутри higher-TF Wave 5 обрабатывается.
14. Risk/RR/ATR/spread/breakers проходят.
15. Execution intent записан до Binance submission.
16. Конкурирующие workers не создают двойной BUY.
17. BUY отправлен один раз и order ID сохранён.
18. Фактические fills определяют quantity/VWAP.
19. Native OCO создан и подтверждён Binance.
20. Две OCO ноги являются одним общим quantity, не суммой.
21. Одна OCO нога PARTIALLY_FILLED, вторая остаётся активной.
22. После partial fill защищается только реальный residual.
23. Crash после BUY до OCO восстанавливается безопасно.
24. Crash после OCO/exit event восстанавливается.
25. WebSocket reconnect делает REST catch-up.
26. После restart DB state совпадает с Binance.
27. APK получает scanner/Binance/WebSocket state от backend.
28. Reinstall/backup не возвращает локальные Binance credentials.
29. Portfolio/balances/positions/orders совпадают с Binance.
30. Итог CLEAN/PASS; только после этого Testnet считается включённым.

## Критический OCO регресс

Для позиции 1.000:
- TP child: PARTIALLY_FILLED, executedQty=0.300.
- SL child: NEW, origQty=1.000.
- Реальный residual: **0.700**.
- Нельзя считать protection как 1.300/2.000.
- Нельзя создавать второй OCO поверх ещё активного OCO.
- Повторная reconciliation не должна повторно вычитать 0.300.

## Запрет

Старый FAIL после исправления не закрывается удалением или ослаблением проверки. Добавляется регресс-тест, исправляется первопричина, затем повторно проходит весь Gate.

Полный исполняемый контракт находится в `test_testnet_release_gate.py`.
