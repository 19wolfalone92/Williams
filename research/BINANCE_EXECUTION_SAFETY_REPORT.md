# Binance: границы проведённой проверки

**Signed Demo E2E: NOT_RUN. Physical Android E2E: NOT_RUN.** Пользовательские
средства, API keys, секреты и настройки аккаунта не использованы и не изменены.
Не получены authenticated receipts о размещении/исполнении/защитном STOP.

Проверенные code/test области baseline: Futures endpoint pinning, unknown
POST handling, `order_safe` query-before-retry, ExecutionBarrier intents,
actual position risk reservations, pending risk, partial fills, protective
STOP replace/new-before-cancel, daily3% и UTC baseline, restart/reconciliation,
source-bound orders и signed exit attribution. Все2086 baseline offline tests
прошли; это не независимая инспекция каждой строки coordinator и не биржа.

Подтверждённые официальные контракты на дату проверки:

- [USD-M Trade API](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/trade):
  conditional algo имеет свой clientAlgoId; Query Algo Order отдельно от
  обычного order; actualOrderId появляется после trigger. Наличие pending
  entry STOP не доказывает защиту исполненной позиции.
- [USD-M Market Data](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data):
  kline содержит отдельно volume, quote volume и number of trades. Это не
  прямое свидетельство исходного TC1 количества изменений цены.

При неизвестном результате запретить новое добавление риска и восстановить
сверку. Существующие STOP сохранять до безопасного подтверждённого replacement;
не очищать по одному символу без ownership. После FILLED нужна доказанная
защита фактического объёма, а не локально рассчитанная цена SL.

Открытый P0 базовой ветки: реальная длительность FILLED→owned STOP на устройстве
не доказана. Уже существующие исправления urgent reconciliation / exact exit
binding сохранены. Приёмку по этому пункту нельзя закрыть зелёным APK CI.
