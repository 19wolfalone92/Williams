# Williams Binance Bot + Android Dashboard 4.21.0

<!-- CI verification branch for current main -->

Полноценная Testnet-first версия торгового бота по Williams (Alligator + AO + Fractals) с Android Dashboard, SQLite recovery, Binance WebSocket и защитными risk-фильтрами.

## Что сделано в 4.10.0

### Торговое ядро
- Настоящая state machine: `FLAT → ENTRY_PENDING → OPEN → EXIT_PENDING → FLAT`.
- `RECONCILE_REQUIRED` является жёсткой блокировкой новых входов.
- Если BUY выполнился, но ответ API потерялся, recovery ищет его по уникальному `clientOrderId`.
- Entry привязывается к конкретному OCO `orderListId`.
- Recovery не принимает чужой BTC на кошельке за позицию бота.
- Любое существенное расхождение ожидаемого и фактического BTC переводит систему в `RECONCILE_REQUIRED`.
- Missing OCO восстанавливается только для подтверждённой позиции бота.
- Выходная сделка при recovery привязывается к зарегистрированному OCO, когда это возможно.
- Все критические действия журналируются в SQLite.

### Risk engine
Перед каждым новым входом проверяются:
- дневной лимит убытка;
- максимум сделок в день;
- максимум последовательных убытков;
- cooldown после сделки;
- минимальное Risk/Reward;
- spread;
- ATR/волатильность;
- подтверждение старшего таймфрейма;
- размер позиции ограничивается одновременно долей капитала и риском на сделку.

По умолчанию:
- риск на сделку: максимум 0,5%;
- совокупный открытый риск: максимум 1%;
- одновременно допускается ровно 1 управляемая позиция; после полного закрытия бот возвращается к сканированию;
- максимум дневного убытка: 3%;
- максимум 5 сделок в день;
- максимум 3 последовательных убытка;
- cooldown 30 минут;
- minimum R/R 1.5;
- старший таймфрейм: 4h.

Это защитные ограничения, а не гарантия прибыли.

### WebSocket
- realtime market stream;
- Binance user-data stream;
- автоматический reconnect с backoff;
- обработка `eventStreamTerminated`;
- проактивная ротация user stream до 24-часового срока;
- Android получает realtime snapshot/candles/account updates.

REST остаётся обязательным reconciliation-слоем: WebSocket не используется как единственный источник истины для финансового состояния.

### Android 4.21
- Android может работать как локальный Testnet runtime через Foreground Service;
- для 24/7 торговли предпочтительна архитектура Android cockpit → HTTPS/WSS → backend/VPS → Binance;
- одновременно допускается 1 позиция; на неё действует максимум 0,5% риска, а общий защитный бюджет не превышает 1%;
- отдельный SELL для каждой открытой позиции;
- Android Foreground Service + системная настройка исключения из Battery Optimization для локального runtime;
- зашифрованный переносимый backup с восстановлением на другом телефоне;
- после restore торговое состояние сверяется с Binance, а автоторговля автоматически не запускается;
- Compose dashboard;
- свечной график с Alligator;
- BUY/TP/SL;
- PnL;
- сделки и логи;
- START / PAUSE / RESUME / STOP / RECOVER;
- API key/secret хранятся через Android Keystore-backed encrypted storage;
- release manifest запрещает cleartext HTTP;
- debug build разрешает HTTP только для локального Testnet/LAN;
- отображается реальное состояние state machine, включая `RECONCILE_REQUIRED`.

## Безопасность

1. Начинать только с Binance Spot Testnet.
2. `TESTNET=true` — значение по умолчанию.
3. LIVE требует явного `ALLOW_LIVE=true`.
4. Никогда не помещать реальные API keys в Git или ZIP.
5. Для API приложения использовать длинный случайный `MOBILE_API_TOKEN`.
6. Для LIVE использовать HTTPS/WSS.
7. API key Binance должен иметь только необходимые торговые права; вывод средств не нужен.

## Запуск Python backend

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
python recovery_test.py
python run_server.py
```

Windows:

```text
.venv\\Scripts\\activate
```

## Проверки

```bash
python -m compileall -q .
python recovery_test.py
```

Recovery suite включает 10 сценариев:
- BUY → OCO → crash → TP;
- BUY → OCO → crash → SL;
- BUY → crash → missing OCO;
- restart с открытой позицией;
- чужой BTC не становится позицией;
- избыток BTC блокирует торговлю;
- `RECONCILE_REQUIRED` блокирует новый BUY;
- timeout после BUY восстанавливается по clientOrderId;
- Entry связан с точным OCO list.

## Backtester

```bash
python run_backtest.py --symbol BTCUSDT --interval 1h --start 2024-01-01 --end 2026-10-01
```

Исторический `fetch_klines()` поддерживает как live-вызов через Binance client, так и позиционный вызов `fetch_klines(symbol, interval, start, end)`.

## Android

Открыть корень проекта в Android Studio и собрать `app`.

Версия приложения: **4.20.1**, versionCode формируется GitHub Actions.

Среда, в которой подготовлен этот архив, не содержит Android SDK/Gradle distribution, поэтому APK здесь не заявляется как собранный. Исходники Gradle-проекта подготовлены для сборки Android Studio.

## Важное ограничение

Даже после всех защит автоматическая торговля остаётся рискованной. Recovery и risk engine предназначены для снижения технических и риск-ошибок, а не для гарантии положительного результата стратегии.

## Сборка APK

Полная инструкция: `BUILD_APK_RU.md`. Текущая версия Android: **4.21.0**.

Проект подготовлен с AGP 8.7.3, Gradle 8.9, Java/Kotlin target 17 и compile/target SDK 35. Для локальной сборки можно использовать Android Studio. Для автоматической debug-сборки в GitHub предусмотрен workflow `.github/workflows/android-apk.yml`.

Release-подпись не хранится в проекте: создайте собственный keystore и локальный `keystore.properties`. Это необходимо для безопасного выпуска обновлений приложения.

## Автосканирование

- Binance Spot USDT universe: динамическое обнаружение допустимых Spot/USDT пар.
- Для рабочего цикла используется liquidity preselection Top-50, чтобы не перегружать Binance REST; все строгие сигналы внутри выбранных 50 проходят MTF/Wave-проверку, watch-only кандидаты ограничиваются Wave Top-N.
- Исполнение выбирает только одного лучшего кандидата и повторно проверяет его непосредственно перед BUY.
- Получаются OHLCV, bookTicker/depth, aggTrades и Binance exchangeInfo; L2/flow/quant остаются фильтрами качества и не создают сигнал самостоятельно.

## 24/7 VPS mode

The intended production architecture is now:

`Android APK -> HTTPS/WSS -> Caddy -> Williams backend on VPS -> Binance`

The backend persists encrypted Binance credentials and bot state on the VPS. Docker is configured with `restart: unless-stopped`, and `AUTO_START=true` resumes the bot after a VPS/container restart when credentials are present. See `deploy/README_RU.md`.

## 4.10.0 — запуск без домена

Для удалённой работы с телефона собственный домен больше не обязателен. Используйте `deploy/install_no_domain.sh`: после установки VPS он настраивает Tailscale Funnel и выдаёт HTTPS URL. Телефон работает как панель управления, а торговый процесс остаётся на VPS 24/7.


## Автоматическая сборка APK через GitHub Actions

См. `APK_BUILD_AUTO_RU.md`. После push в `main` GitHub Actions автоматически собирает устанавливаемый `app-debug.apk` и публикует его в Artifacts.


## Williams 4.12 strategy alignment

The strategy layer now keeps separate Williams First/Second/Third Wise-Man signals, the Super AO three-bar condition, the fractal/Teeth gate, Market Facilitation diagnostics, and multi-timeframe wave context. An active Wave-5 exhaustion gate is applied conservatively on the execution timeframe; a nested lower-timeframe Wave 3 inside a higher-timeframe Wave 5 is not vetoed automatically. See `WILLIAMS_BOOK_ALIGNMENT_RU.md`.


## 4.17 deep market model

- The scanner discovers valid Binance Spot/USDT pairs dynamically, then applies exchange-status, liquidity and risk filters. The five pairs are only the default structural set for realtime MTF context.
- Standalone Android maintains realtime Binance Testnet kline streams for all supported native timeframes from 1m through 1M.
- Indicators are recalculated per pair/timeframe from the live candle cache: Alligator, AO, AC, fractals and divergence context.
- Historical data is treated as a persistent research layer; the in-memory layer is bounded to prevent Android OOM while the wave engine uses the full historical store where available.
- Binance exchangeInfo/bookTicker/account/order data remain execution gates; strategy signals are generated by Williams logic, not by Binance.
- Initial protection uses market structure/Alligator Teeth with ATR as a volatility guard. Profit protection follows the Williams five-green-zone/Teeth trailing model rather than a fixed 1.5R take-profit.


## 4.21 quantitative shadow layer

The quant layer is integrated ahead of ML admission and remains non-executing:
- unified `MarketFeatureVector` combines OHLCV, Dollar/Volume Bars, L2 order-book imbalance, trade-flow, Williams indicators and MTF Wave context;
- persistent SQLite Feature Store keeps feature snapshots and AI-shadow decisions;
- deterministic regime baseline labels `LOW_VOL_FLAT`, `TRENDING_EXPANSION`, `HIGH_NOISE_WASH` or `UNKNOWN`;
- XAI attribution is recorded as deterministic factor contribution, without pretending it is SHAP until a calibrated model exists;
- CPCV purged/embargoed validation and latency/slippage stress are part of the backtest CLI;
- Mock Exchange provides deterministic partial-fill, timeout and sequence-gap scenarios for recovery testing;
- `AIOrderIntent` is shadow-only and has no Binance credential or order-submission path;
- quant ranking is bounded and configurable; it never creates a BUY and never bypasses P0 execution/reconciliation/risk gates.

Research backtest now also reports CPCV signal-return stability and adverse latency/slippage stress. Runtime telemetry is available via `/api/v1/quant/health`, `/api/v1/quant/features`, `/api/v1/quant/shadow` and `/api/v1/quant/shadow-execution`. Optional ML research dependencies are isolated in `requirements-research.txt`; they are not required for the production/Testnet runtime.


### Optional ML admission layer

HMM/GMM regime models, LightGBM direction, isotonic probability calibration, SHAP explanation and the unified ShadowMLPipeline are implemented as optional research modules. They fail closed when optional dependencies are unavailable and do not have access to Binance credentials or order submission. Funding/OI/liquidation features are normalized through derivatives_features.py and enter the same MarketFeatureVector when an external derivatives provider supplies them.


## AI-Forge Council Core

The repository now contains a separate server-side AI Council under `ai_forge/`. It queries GPT, Gemini, DeepSeek, Grok and Mistral independently, then applies a quorum + weighted-consensus verifier. Provider API keys remain server-side and are never returned to the Android client.

Safety boundary: AI-Forge produces analytical decisions only. It does not submit Binance orders and does not receive Binance credentials. For trading analysis, `PROCEED` is not a BUY/SELL command.

See `AI_FORGE_README_RU.md`. GitHub Actions validates Python compilation/tests and performs a Docker smoke test in mock mode. A permanent public Core URL still requires a continuously running host; GitHub repository and Actions are not a permanent HTTP runtime.
