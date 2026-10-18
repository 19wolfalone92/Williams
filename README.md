# Williams Binance USDⓈ-M Futures Bot + Android Dashboard

Testnet-first реализация торгового бота по Williams (Alligator + AO + Fractals) для Binance USDⓈ-M Futures с симметричными Long/Short кампаниями, SQLite recovery и жёсткими execution/risk gates.

## Текущая версия 4.30.1

- Добавлена воспроизводимая историческая проверка сложных волн: общий источник M15 для D1/H6/H1/M15, независимый расчёт фракталов, проверка внутренних подволн и причинной отмены сценария.
- Плоскость с C, не достигшей A, остаётся незавершённой/усечённой альтернативой. Последующая цена отсчитывается после экстремума, без предположения о порядке high/low внутри свечи.
- Исправлены срок зонального MARKET-сигнала NTD и исполнение разных измерений на одном баре; закрытое противоположное событие учитывается для выхода.

Воспроизведение: [Исторический аудит 4.30.1](docs/WILLIAMS_HISTORICAL_AUDIT_4_30_1_RU.md).

- Три отдельных исполняемых профиля: `TC2_THREE_WISE_MEN`, `NTD_FIVE_DIMENSIONS`, `COURSE_PROFITUNITY`. В Android книга выбирается в настройках только после сверки Binance, без позиций и ожидающих входов; Python использует `WILLIAMS_SIGNAL_PROFILE`. Открытая кампания сохраняет свою книгу.
- NTD: внешний фрактал, AO, AC, зона и Balance Line; зональное добавление через MARKET после закрытия H1, Teeth/Lips и постоянный стоп после пяти зональных баров, повторный вход после подтверждённой прибыльной кампании.
- Course: Hump, Thumb, Squat, стоп по двум фракталам и H6 Air Bag; при однозначном закреплённом начале H1 импульса работает отдельный поэтапный план с частичными сокращениями и увеличениями. Соотношения примера из книги масштабируются по фактическому исполнению и ограничиваются риском.
- Доливки и частичные выходы имеют отдельные сохраняемые намерения. Количество и средняя цена позиции восстанавливаются по фактическим исполнениям; потерянный HTTP-ответ не разрешает повторную отправку.
- Разделены последняя подтверждённая и текущая формирующаяся волна. Вложенный H6 W4 после W3 проверяется по AO, реальному сигналу H1 и причинному графу D1/H6/H1/M15.
- Сложные ценовые структуры получили проверку внутренних 5–3–5 / 3–3–5 / 3–3–3–3–3 подволн по подтверждённым младшим экстремумам. Неоднозначная разметка остаётся альтернативой.

Подробности выбора книги, риск-ограничений и воспроизводимой проверки: [Исполнение книжных профилей](docs/WILLIAMS_EDITION_EXECUTION_4_30_RU.md). Строгое совпадение с каждым субъективным указанием всех книг и приёмка на телефоне не объявляются автоматически доказанными.

### Сохранённые изменения 4.29.0

- Добавлен причинный граф D1/H6/H1/M15: время экстремума отделено от подтверждения, хранятся альтернативы и отмена сценариев. Номера таймфреймов сами по себе больше не доказывают родство волн.
- Аналитический масштаб выбирается по реальной волновой последовательности и запрашивает реальные свечи. Минимум W4 по пересечению нуля не считается завершением W4 или самостоятельным приказом.
- Добавлен отдельно проверяемый режим CORRECTION в обоих направлениях, структурные границы родителя, повторная проверка перед входом и отмена утратившего допуск ожидающего ордера.
- Исправлены WM1 вне всей пасти, подтверждение/пригодность WM3, защитный тик Python и стоп по пяти закрытым барам TC2 до 1R. Сопровождение продолжается при недоступном сканере и на паузе.
- Противоположный setup вызывает выход после фактического срабатывания уровня, возникшего после открытия. NTD Teeth не смешивается с активным TC2; цвет NTD-зоны определяется AO+AC.
- Финальная WebSocket-свеча требует `x=true`; запоздавшее событие не превращает частичную свечу в подтверждённую и не затирает финальный бар.

Историческая карта правил выпуска 4.29: [Williams rule traceability](docs/WILLIAMS_RULE_TRACEABILITY_4_29_RU.md). Текущие режимы описаны в разделе 4.30 выше.

### Сохранённые исправления 4.28.2

- Позиция Android подтверждается конкретными исполнениями входа и выхода; стороннее увеличение количества не становится частью кампании. Дочерние ордера Algo API распознаются по `actualOrderId`, их частично исполненный остаток отменяется перед защитой.
- Перед переносом стопа и автоматическим выходом повторно проверяются количество, направление и цена входа. На границе HTTP повторно проверяются команды остановки новых входов.
- Python сохраняет защиту при неизвестной ликвидации, продолжает сверку при сбое сканера/паузе, отменяет ожидающие добавления перед выходом и учитывает подтверждённые частичные выходы.
- Дневной риск Python учитывает закрытые сделки всех пар и записанные комиссии. База дня сохраняется атомарно, неизвестный PnL блокирует входы, срабатывание лимита сохраняется до следующего UTC-дня.
- История Futures загружается через `/fapi/`, кэш разделён по рынку/среде, последняя свеча обновляется повторно, запись CSV заменяется атомарно.

- Плечо настраивается по умолчанию и отдельно для каждой из 10 разрешённых пар. Диапазон настройки 1–125×; фактический допуск зависит от лимитов Binance, размера позиции, маржи и расстояния до структурного стопа.
- Плечо не увеличивает лимиты риска: до 0,25% на вход, 0,60% суммарно и 3% дневного убытка. Направления LONG и SHORT используют одинаковые проверки; решения принимаются на H1 с контекстом H4/D1.
- После исполнения входа Android сверяет защитный стоп с фактической ценой ликвидации Binance. Подтверждённое нарушение запаса запускает повторно проверенный reduce-only выход с сохранённым уникальным ID. Нулевая или отсутствующая цена блокирует новые входы, но не разрешает автоматическое закрытие.
- Аварийная защита неизвестной позиции не доказывает принадлежность стратегии. Для неё остаётся явное ручное закрытие после свежей проверки направления и количества; автоматические выходы требуют подтверждённой кампании.
- Диагностика показывает фактический жизненный цикл сверки и единое состояние исполнения. Восстановление принудительных входов распознаёт ID `W2F_`.

Разделы с номерами прежних версий ниже описывают историю проекта.

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
- одновременно допускается до 5 управляемых позиций; совокупный открытый риск остаётся ограничен 1%;
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

### Android 4.22
- Android работает как remote cockpit: торговый процесс и Binance execution выполняются на backend/VPS;
- Android подключается к backend по HTTPS/WSS и не является 24/7 торговым процессом;
- одновременно допускается несколько позиций; каждая имеет максимум 0,5% риска, а суммарный открытый защитный риск не превышает 1%;
- отдельный SELL/OCO protection для каждой открытой позиции;
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

1. Начинать только с Binance USDⓈ-M Futures Testnet.
2. `TESTNET=true` — значение по умолчанию.
3. `BINANCE_MARKET=futures_usdt` — основной production market.
4. LIVE требует явного `ALLOW_LIVE=true`.
5. Никогда не помещать реальные API keys в Git или ZIP.
6. Для API приложения использовать длинный случайный `MOBILE_API_TOKEN`.
7. Для LIVE использовать HTTPS/WSS.
8. API key Binance должен иметь только необходимые Futures/reading права; вывод средств не нужен.

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

# Williams Binance USDⓈ-M Futures Bot + Android Remote Cockpit

Production-oriented, Testnet-first Williams campaign runtime for Binance USDⓈ-M Futures. The active execution core is bidirectional LONG/SHORT, campaign-based, SQLite-persistent and fail-closed under uncertain exchange state.

## Active architecture in 4.24.0

### Williams trading core

The execution authority is:

`closed candles → Williams signals → first-presenting Wise Man → PendingSignal → CampaignEngine → Risk → ExecutionBarrier → Binance Futures → reconciliation`

Canonical Wise-Man triggers are implemented in `williams_signals.py`:

- WM1 reversal: conditional BUY/SELL STOP beyond the reversal-bar extreme, with direction-specific angulation validation.
- Super AO: three consecutive same-colour AO bars; it may be the first presenting entry.
- Fractal: confirmed five-bar fractal breakout, with Teeth validity.
- LONG and SHORT use mirrored trigger, protection and exit semantics.
- Later valid Wise Men become campaign add-ons rather than independent campaigns.

The reverse-pyramid allocation is normalized to the historical Williams 1:5:4:3:2 sequence and applied as risk-budget weights rather than literal contract counts.

### Campaign and state safety

Every active campaign is durable in SQLite.

`PendingSignal` tracks:

`DETECTED → VALIDATED → ARMED → TRIGGERED/FILLED`

with terminal or fail-safe states including `SUPERSEDED`, `EXPIRED`, `CANCELLED` and `RECONCILE_REQUIRED`.

Order mutations use one Futures execution door. Unknown/ambiguous exchange outcomes are reconciled by client order id instead of blind retry.

### Risk

Risk is bounded at multiple levels:

- initial campaign risk = campaign cap × 1/15;
- add-ons follow 1:5:4:3:2 weights but cannot exceed campaign cap;
- aggregate `MAX_TOTAL_RISK_PCT` is enforced before sizing each new campaign/add-on;
- sizing is based on loss to the structural stop plus bounded fee/slippage reserve;
- leverage changes margin efficiency, not the intended loss budget;
- liquidation-buffer checks can fail-safe flatten a position when a structural stop cannot safely protect it.

The default environment is `DRY_RUN=true`, `TESTNET=true`, `ALLOW_LIVE=false`, `FUTURES_LEVERAGE=2`, `FUTURES_MARGIN_TYPE=ISOLATED` and `FUTURES_FORCE_ONE_WAY=true`.

### Variable Futures leverage

Android: **Ещё → Настройки → Плечо Futures** (also available under Ещё).
Set a default and optionally override each of the ten contracts; an empty field inherits the default.
Python: `FUTURES_LEVERAGE=5` and optional `FUTURES_SYMBOL_LEVERAGES=BTCUSDT:3,ETHUSDT:10`.
Settings accept integers from 1 to the application's ceiling of 125; malformed values are rejected, never silently clamped.
The same setting applies to LONG and SHORT for that symbol in One-way mode.

Each entry checks fresh, account-specific `/fapi/v1/leverageBracket` data, available margin,
and a conservative isolated-margin buffer covering the structural stop, costs and maintenance margin.
The signed `/fapi/v1/leverage` response must confirm the exact symbol, leverage and notional ceiling before an order is sent.
Excessive leverage is rejected with an explicit blocker; the bot does not silently lower it or move the stop.
Risk sizing and the existing risk caps remain in force. Settings affect subsequently prepared entries;
existing positions are not re-leveraged, and Python add-ons require the existing leverage to match.
Unknown configuration outcomes are reconciled without replaying an entry.

Binance references: [Change Initial Leverage](https://developers.binance.com/docs/derivatives/usds-margined-futures/trade/rest-api/Change-Initial-Leverage)
and [Notional and Leverage Brackets](https://developers.binance.com/docs/derivatives/usds-margined-futures/account/rest-api/Notional-and-Leverage-Brackets).

### Protection and exit

The campaign has no fixed strategic take-profit.

For LONG, the structural trail is the lowest low of the selected last 3/5 closed bars, moved only in the favorable direction. For SHORT, it is the highest high of the selected last 3/5 closed bars, again only tightening risk.

Protection replacement is recovery-safe:

`create new protective STOP_MARKET → persist durable ownership → cancel old stop`

All protection orders are reduce-only and sized to the actual Futures position.

After a market exit, the position must first be verified flat; only then is the protective order cancelled.

### Futures account safety

Startup performs a Futures-specific preflight:

- credentials/account reachability;
- TESTNET/LIVE permission contract;
- Single-Asset/Multi-Assets mode validation;
- One-Way/Hedge mode validation;
- foreign-position rejection;
- foreign-open-order rejection;
- exchange symbol/filter availability.

When `DRY_RUN=true`, account-mode mutations are not performed.

## Android 4.25.0 — autonomous Futures runtime

Android runs the USDⓈ-M Futures Demo/Testnet engine on the phone; no VPS or remote
execution backend is required. The foreground service starts the local runtime after
an explicit START request and restores it only when auto-run was persisted.

- REST is pinned to `https://demo-fapi.binance.com`; market/public/private streams use the
  Futures Demo WebSocket hosts and paths.
- The approved universe is exactly ten USDT-M perpetual contracts.
- H1 is the only decision timeframe; D1 is the macro veto, H4 supplies context, and M15
  is monitoring only. M5 is diagnostics/replay only.
- LONG and SHORT entries are both supported through the native Futures campaign coordinator.
- Closed candles are persisted in SQLite. A valid zero USDT balance is displayed as zero,
  while order execution remains blocked until positive available balance/equity, current
  market streams, history, and exchange reconciliation all pass the execution gate.
- API credentials remain on-device in encrypted storage and are not sent to a remote backend.

A CI APK build verifies source compilation and automated tests. It does not replace
device-level diagnostics or authenticated Futures Demo verification.

## Release and testing

The release contract is Futures-native. It validates:

- Python syntax and active Futures runtime files;
- bidirectional Williams signal extraction;
- PendingSignal lifecycle;
- execution-door safety;
- Futures Demo endpoint contract;
- reduce-only STOP_MARKET protection;
- crash/reconciliation invariants;
- Android remote-only behavior;
- APK build/signing workflow.

Authenticated Futures Demo validation is explicitly opt-in through `WILLIAMS_TESTNET_E2E=1`; the default release tests do not submit trading orders.

## Backtester

```bash
python run_backtest.py --symbol BTCUSDT --interval 1h --start 2024-01-01 --end 2026-10-01
```

Исторический `fetch_klines()` поддерживает как live-вызов через Binance client, так и позиционный вызов `fetch_klines(symbol, interval, start, end)`.

## Historical Android build notes (4.22.3; superseded)

Открыть корень проекта в Android Studio и собрать `app`.

Архивная версия приложения: **4.22.3**; versionCode формируется GitHub Actions.

Среда, в которой подготовлен этот архив, не содержит Android SDK/Gradle distribution, поэтому APK здесь не заявляется как собранный. Исходники Gradle-проекта подготовлены для сборки Android Studio.

## Важное ограничение

Даже после всех защит автоматическая торговля остаётся рискованной. Recovery и risk engine предназначены для снижения технических и риск-ошибок, а не для гарантии положительного результата стратегии.

## Сборка APK

Полная инструкция: `BUILD_APK_RU.md`. Текущая версия Android: **4.22.3**.

Проект подготовлен с AGP 8.7.3, Gradle 8.9, Java/Kotlin target 17 и compile/target SDK 35. Для локальной сборки можно использовать Android Studio. Для автоматической debug-сборки в GitHub предусмотрен workflow `.github/workflows/android-apk.yml`.

Release-подпись не хранится в проекте: создайте собственный keystore и локальный `keystore.properties`. Это необходимо для безопасного выпуска обновлений приложения.

## Автосканирование Futures

- Binance USDⓈ-M USDT perpetual universe: динамическое обнаружение контрактов со статусом TRADING.
- Сканируются обе стороны: LONG и SHORT. Первый валидный представляющий Wise Man может открыть кампанию; WM2/WM3 после открытия становятся add-ons.
- Начальный размер кампании использует минимальную долю reverse pyramid; следующие добавления следуют весам 1:5:4:3:2, ограниченным жёстким campaign/portfolio risk budget.
- Защита — единичный reduce-only STOP_MARKET; основной выход — Williams 3/5-bar structural trail или подтверждённый противоположный сигнал. Fixed TP не является стратегическим выходом.
- Long и Short используют одинаковые safety/recovery gates; leverage не увеличивает допустимый риск.

## 24/7 VPS mode

The intended production architecture is now:

`Android APK -> HTTPS/WSS -> Caddy -> Williams backend on VPS -> Binance`

The backend persists encrypted Binance credentials and bot state on the VPS. Docker is configured with `restart: unless-stopped`, and `AUTO_START=true` resumes the bot after a VPS/container restart when credentials are present. See `deploy/README_RU.md`.

## 4.10.0 — запуск без домена

Для удалённой работы с телефона собственный домен больше не обязателен. Используйте `deploy/install_no_domain.sh`: после установки VPS он настраивает Tailscale Funnel и выдаёт HTTPS URL. Телефон работает как панель управления, а торговый процесс остаётся на VPS 24/7.


## Автоматическая сборка APK через GitHub Actions

См. `APK_BUILD_AUTO_RU.md`. После push в `main` GitHub Actions автоматически собирает устанавливаемый `app-debug.apk` и публикует его в Artifacts.


## Williams strategy alignment

The strategy layer now keeps separate Williams First/Second/Third Wise-Man signals, the Super AO three-bar condition, the fractal/Teeth gate, Market Facilitation diagnostics, and multi-timeframe wave context. An active Wave-5 exhaustion gate is applied conservatively on the execution timeframe; a nested lower-timeframe Wave 3 inside a higher-timeframe Wave 5 is not vetoed automatically. See `WILLIAMS_BOOK_ALIGNMENT_RU.md`.


## 4.17 deep market model

- The active production scanner discovers valid Binance USDⓈ-M USDT perpetual contracts dynamically, then applies exchange-status, liquidity and risk filters. The five pairs are only the default structural set for realtime MTF context.
- Android acts as a remote cockpit; native Spot trading loops are not started by the application.
- Indicators are recalculated per pair/timeframe from the live candle cache: Alligator, AO, AC, fractals and divergence context.
- Historical data is treated as a persistent research layer; the in-memory layer is bounded to prevent Android OOM while the wave engine uses the full historical store where available.
- Binance Futures exchangeInfo/position/order data remain execution gates; strategy signals are generated by Williams logic, not by Binance.
- Initial protection uses market structure/Alligator Teeth with ATR as a volatility guard. Profit protection follows the Williams five-green-zone/Teeth trailing model rather than a fixed 1.5R take-profit.


## 4.22 quantitative shadow layer

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


