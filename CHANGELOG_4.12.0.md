# Williams Trader 4.12.0

## Wave Engine

- Добавлен `wave_engine.py` с multi-timeframe structural wave context.
- Подтверждённые Williams-фракталы используются как базовый swing layer.
- Добавлены `wave_position`, `wave_score`, `wave_confidence`, `wave_exhaustion_risk`, `wave_path`.
- Поддерживается вложение младшей W3 в родительскую W5.
- Родительская W5 не блокирует дочернюю W3 автоматически.
- Использование Wave Score ограничено ±10 баллами относительно legacy score.
- Не используются авторские Fibonacci ratio, которые отсутствуют в загруженной книге.

## Scanner

- Полный Binance Spot/USDT universe теперь является default режимом.
- Есть фильтрация leveraged-токенов `UP/DOWN/BULL/BEAR`.
- Параллельный base scan ограничен `SCAN_WORKERS`.
- Universe metadata кэшируется.
- Wave analysis ограничивается strict + `WAVE_SCAN_TOP_N` watch-кандидатами.

## Trader / safety

- Полный автоматический scan ограничен `AUTO_SCAN_MIN_INTERVAL_SECONDS`.
- `market_buy()` имеет дополнительный live guard помимо `setup()`.
- Сохраняется single-position `MAX_OPEN_POSITIONS=1`.
- Сохраняется `DRY_RUN=true` default.

## Android / API

- API scanner теперь сообщает TTL/freshness cache.
- Android показывает Wave, WScore, confidence, exhaustion и nested-W3 context.
- Version: 4.12.0.

## Tests

Добавлены offline tests для wave structure, MTF nesting, full-universe filtering и direct BUY safety. GitHub Actions получил отдельный Python backend CI workflow.
