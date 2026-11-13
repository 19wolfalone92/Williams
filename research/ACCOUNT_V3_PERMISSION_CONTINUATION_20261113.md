# USD-M V3: корректное получение торгового разрешения

Продолжение плана в Williams, ветка research/master-primary-sources-20261113,
PR240. Начальный HEAD b9d45bebe41a5cd5a8cf656a0592954a6e07d647.
main32e9dd64fab9e0488ea4b566347246ac44627199 не изменялся.
Версия4.64.31 / минимальный Android versionCode46431.

## Воспроизведённая ошибка

Python `BinanceFuturesClient.account()` требовал canTrade непосредственно в
`/fapi/v3/account`. Официальная V3-форма без этого поля отвергалась как
`BinanceAPIError: Malformed Futures account snapshot` при корректных balances.
Прежний тест использовал V3 URL с V2-подобным permission field и не обнаруживал ошибку.

Официальные источники проверены13.11.2026:

- [Account Information V3](https://developers.binance.com/docs/derivatives/usds-margined-futures/account/rest-api/Account-Information-V3)
- [Futures Account Configuration](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/account#futures-account-configuration-user_data)

V3 представляет balances/positions; permission и account mode доступны через
подписанный GET /fapi/v1/accountConfig. V2 всё ещё присутствует в документации:
старый комментарий о якобы удалённом V2 не используется как доказательство.

## Изменение и границы

Python и Kotlin объединяют V3 account и configuration только при отсутствии
canTrade. Если подписанный account уже содержит literal boolean, сохраняют его
и прежний один GET. Невалидный явно присутствующий флаг не заменяется более
выгодным configuration. Unknown не получает true. Явный false остаётся false,
а Python status — NOT_TRADING; чтение известных данных не объявляется запрещённым.

Из config копируются только известные boolean flags. Балансы, assets, positions,
маржа и суммы не заменяются полями configuration. Противоречивые режимы, неверные
типы, отсутствие permission или неудавшийся GET приводят к ошибке. Это два чтения,
не атомарный снимок биржи и не подтверждение существования STOP/исполненного ордера.

Все запросы остаются signed GET на том же Futures host и с теми же credentials.
Spot/SAPI, live-host fallback, POST или обучение не добавлены. В тестах используются
локальные ответы/interceptors; signed Demo и физический Android не проверены.
Прежние clock/retry тесты сохраняют свой предмет: их заглушки account теперь
явно возвращают boolean, а отдельный HTTP-тест проверяет документированную V3-форму
и второй подписанный GET configuration.

## Проверки и оставшийся margin пункт

Целевой Python-прогон206 passed; полный Python-прогон2360 passed/2 skipped
(4 существующих FastAPI warnings,27.34s). Release contract и ruff PASS. Android
CI точного финального SHA фиксируются в PR240 после завершения продолжения.
До account patch baseline2337 passed/2 skipped.

Проверка half-account margin остаётся самостоятельным пунктом. Нельзя считать
available balance занятой маржой или объявить один totalInitialMargin достаточным
для pending conditional, partial и unknown liabilities. В этом изменении лимит
половины счёта ещё не подключён к execution. Следующий контракт должен сохранять
неизвестные обязательства и различать root/asset units, режим аккаунта и позиции.
