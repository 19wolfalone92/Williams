# Williams Bot — повторный сквозной аудит Binance Demo, 20.11.2026

Объект: автономное Android-приложение Binance USDⓈ-M Futures Demo/Testnet. Это НЕ Williams AI и НЕ live Binance.
Ветка: fix/binance-end-to-end-reliability-20261120, draft PR #260 поверх #259/#258/#257. Main не менялась.
Проверено: реальный код Binance REST + private/public WS, SQLite durable inbox, event actor, execution coordinator, STOP replacement, H1 TC2 admission, риск 3%/3 подряд, unit-моки Binance; не реальные подписанные Demo POST на телефоне.

## Исправления

1. **B-01: неизвестный private WS envelope**. Валидный JSON без e или с неизвестным e ранее мог быть отброшен при connected=true. Теперь отзыв соединения, безопасное переподключение и обязательная подписанная REST-сверка. Устаревший WS не даёт право открывать новые позиции.
2. **B-02: priceProtect у подтверждённого STOP**. После POST защитного STOP с MARK_PRICE и priceProtect=false подписанный GET должен подтвердить priceProtect=false. true/null не гарантируют такое же срабатывание в периоды расхождения MARK и CONTRACT. Это правило применено к owned STOP, external full stop и bridge перед удалением старого. Добавлены unit-тесты LONG/SHORT.
3. **B-03: private inbox memory/actor**. Биржевые события записываются транзакционно в SQLite до wake-up. Неограниченная очередь actor заменена на bounded очередь 256 задач с явным RejectedExecutionException; ни silent discard, ни CallerRunsPolicy. Ошибка wake-up сохраняет pending inbox и закрывает право новой экспозиции, не стирая финансовые события.
4. **B-04: ошибочное ACK на POST HTTP 200**. Раньше JSON без идентификатора или с чужим order мог называться accepted. Теперь проверяются точные symbol, BUY/SELL, BOTH, clientAlgoId/standard clientOrderId, exchange order/algo ID, тип, количество, цены и статус. На невалидный ответ — durable SUBMISSION_UNKNOWN, signed REST reconcile по точному ID и полный запрет слепого повторного POST.
5. **B-05: регрессионные тесты и fixtures**. Добавлены тесты неизвестного WS события, overflow/shutdown, неподтверждённого Binance POST, priceProtect false и STOP bridge. Исправлен старый Python тест, который ошибочно требовал неограниченную LinkedBlockingQueue. Синтетические подписанные STOP в моделях теперь явно показывают priceProtect=false.

## Связки Binance, которые проверены в исходниках, но не ослаблены

| Участок | Инвариант |
| --- | --- |
| Host | Только demo-fapi.binance.com; никаких live orders или Spot подписанных URL |
| UTC | Binance server clock, monotonic TTL, mutations recvWindow 5000ms; никакой авторизации по часовому поясу телефона |
| HTTP retry | GET может повторяться после transient failure; POST/DELETE не повторяется при ambiguous transport; -1021 допускается только при доказанном отказе Binance |
| Rate governor | Общая 418/429 блокировка, сохранение Retry-After; без обхода cooldown даже для STOP |
| Entry | Durable intent before POST, exact signed exchange ACK, нет повторного submit без независимой сверки |
| Private events | Durable SQLite inbox → serialized actor → срочная signed REST сверка, события WS не являются авторизацией |
| Partial/FILLED | Реальный signed positionAmt и идентичность дочернего заказа перед подтверждением защиты |
| STOP | STOP_MARKET + MARK_PRICE + priceProtect=false + BOTH + side + algo/client ID + active + full-position qty |
| Replacements | Новый подтверждённый bridge до удаления старого STOP; никакого необоснованного нового риска |
| Risk | 3% суточного убытка, 3 подряд биржевых LOSS, 50% общая маржа, TC2-only entry |

## Остаточные ограничения — НЕ объявлять готовым к безнадзорной Demo-торговле

- **P0**: нет независимой физической проверки двух сторон LONG/SHORT NEW→PARTIAL→FILLED→POST STOP→подписанный GET STOP. Android и биржа делают разные транзакции: мобильная сеть может упасть между FILLED и STOP. Один только unit-test не может доказать нулевое окно без защиты.
- **P1**: критический signed REST reconcile выполняется с координационным lock; требуется p50/p95/p99 по фазам и реальный стресс с 429, Doze, reboot, radio-loss.
- **P1**: signed margin check TTL 15s иногда приводит к FUTURES_HALF_MARGIN_UNKNOWN:STALE; нельзя увеличить TTL ради частоты входов без доказательства сохранения account/position identity.
- **P1**: глубочайшая книжная экспертиза ангуляции, волн и WM1/WM2/WM3 требует независимого эталона; этот PR не делает фиктивных claims о соответствии.

## Выпуск

Проверить именно финальный head SHA: Python CI + Android Kotlin unit/lint/build APK. Затем физическая Android/Binance Demo приёмка исполнения обеих сторон и аварийного восстановления. Main не трогать, оставить PR draft до реальных доказательств.

## Повторный проход, 20 ноября — обнаружены новые проблемы после первого зелёного CI

**B-07 (реальная несовместимость с официальными событиями USDⓈ-M)**. Предыдущий строгий список private WebSocket пропускал законные Binance `TRADE_LITE`, `MARGIN_CALL`, `STRATEGY_UPDATE` и `GRID_UPDATE`. Вместо своевременной реакции на торговый/маржинальный сигнал клиент считал их ошибкой протокола, разрывал соединение и снова блокировал допуск. Исправлено: перечисленные известные типы не разрывают WS, но **обязательно** ставят закрытую risk/reconcile защёлку и требуют отдельный подписанный REST account/position/order/STOP refresh. Неизвестные/некорректные типы по-прежнему fail-closed. Для `TRADE_LITE` с положительным `l` добавлено немедленное пробуждение signed reconciliation: этот предварительный сигнал никогда сам не утверждает FILLED или STOP. Проверено source + регрессионными тестами fake-private-WS.

**B-08 (потенциально постоянная блокировка после actor backpressure)**. При переполненной ограниченной очереди задача drain отклонялась, сохраняя записи в SQLite и запрещая новые входы — безопасно, но без следующего события private WS или перезапуска не было гарантированного повторного пробуждения. Добавлен периодический мониторинг durable pending inbox с monotonic backoff 10 секунд; когда actor свободен — повторно ставится один drain. Невозможность прочитать pending состояние продолжает блокировать новые входы. Регрессионный тест проверяет повтор, остановку, занятость, откат monotonic clock.

**Примечание о границах приёмки:** после новых коммитов прежний успешный CI на старом SHA более не является финальной проверкой. Не считать PR готовым, пока *последний* SHA не пройдёт все тесты и сборку. В отсутствие реальных API-ключей/физического телефона не подтверждены биржевые LONG/SHORT FILLED→STOP и поведение Android Doze/offline.