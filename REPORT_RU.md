# WILLIAMS BOT 2.1 — TASK 001
## Architecture & Integration Control — отчёт аудита

**Статус: BLOCKED — NOT ACCEPTED AS PASS**

Дата: 2026-10-11. Репозиторий: 19wolfalone92/Williams. Целевая ветка: audit/williams-2.1-task-001-architecture.

## 1. Baseline и provenance

| Проверка | Результат | Основание |
|---|---|---|
| Ветка существует | PASS | GitHub branch lookup подтвердил имя ветки |
| Точный commit HEAD | BLOCKED | Полученный branch lookup не вернул SHA вершины |
| Чистота рабочего дерева | BLOCKED | GitHub Contents API не показывает локальный git status |
| Воспроизводимый baseline | BLOCKED | Clean checkout и полный запуск тестов на одном SHA в этой проверке не выполнены |
| main/merge | Не изменялись в рамках этого действия | Читались файлы; отчёт записывается только в целевую audit-ветку |
| Физический Android/Testnet | BLOCKED | Нет свежего device run, APK provenance и связанного exchange evidence |

SHA из прежних сообщений не принимаются за доказательство текущего HEAD. Для закрытия нужны полный HEAD/base SHA, git status --porcelain, git rev-parse HEAD, git diff --check и логи тестов с тем же checkout.

## 2. Карта компонентов, подтверждённая просмотренным кодом

```text
Market Data:
  BinanceSpotClient / data.py / ws_hub.py
       ↓
market_context.py / mtf_context_service.py / hypothesis_engine.py
       ↓
ContextCache (versioned market-context snapshots)
       ↓
strategy.py / Trader.process()
       ↓
MultiPositionTrader / campaign services / risk & preflight gates
       ↓
OrderIntent
       ↓
ExecutionBarrier.execute()
       ↓
BinanceSpotClient → Binance Spot REST / Testnet

Persistence:
  Database / SQLite:
  bot_state, orders, trades, campaigns, campaign_signals,
  execution_intents, execution_events, cancel_replace_operations

Android:
  Dashboard/API client → server.py control/status API
  Backend code creates ControlState, Trader, scanner, MTF services,
  database and Binance stream/control components.
```

Это подтверждает наличие backend-owned execution path в проверенных файлах. Это НЕ доказывает, что установленный APK соответствует этому исходнику или что Android NativeEngine автономно исполняет ордера. Архитектурные режимы backend-owned и phone-native execution нельзя считать эквивалентными без явного выбора единственного execution authority и device evidence.

## 3. Критические мутации и владельцы

| Состояние | Кандидат-владелец по коду | Открытый риск |
|---|---|---|
| Order admission | ExecutionBarrier | Не доказано, что все mutating Binance REST calls проходят через него |
| Exchange order/fill/balance facts | Binance; локальная БД — проекция | Неизвестный результат запроса требует reconcile, а не слепого повтора |
| Signals/context | strategy.py, market_context.py, ContextCache | Signal должен оставаться намерением, не побочным ордерным эффектом |
| Campaign/position | MultiPositionTrader + campaign services + SQLite | Coexist legacy trade/position state и campaign tables; единственный writer не доказан |
| Fees/PnL/financial ledger | Database + execution accumulation paths | Exactly-once и атомарность всех writers не доказаны |
| Kill switch | ControlState.kill() + SQLite latch + close path | Код ставит latch перед закрытием; гонки и частичный выход требуют тестов |
| Market data | REST/WS + context services | Gap/stale data должны блокировать входы до восстановления |
| Android UI | Dashboard/API client | Не должен быть параллельным владельцем order/campaign/financial state |

Обязательные ownership rules:
1. Binance — источник истины по ордерам, fills, балансам и открытым ордерам.
2. ExecutionBarrier — единственная точка локального admission ордера, но не источник истины об исполнении.
3. Назначить один canonical campaign/position writer после инвентаризации всех mutation call sites.
4. Один canonical financial application service должен атомарно применить каждый уникальный fill, fees, PnL и количество позиции.
5. Если execution authority — backend, Android остаётся read/control client. Два независимых execution writers на один аккаунт без fencing запрещены.

## 4. Контракты восьми рабочих направлений

| Задача | Контракт и критерий блокировки |
|---|---|
| TASK 002 — Baseline/Android provenance | Trusted checkout, base/head SHA, APK SHA, installed package/version и device logs. Неизвестный APK provenance блокирует readiness. |
| TASK 003 — Execution Safety | Полный order mutation call graph. Любой неучтённый прямой mutation path блокирует входы. |
| TASK 004 — State Machine | Версионированная таблица переходов order/campaign/position. UNKNOWN, конфликт или неполная транзакция → RECONCILE_REQUIRED. |
| TASK 005 — Entry Engine | Immutable PendingSignal/SignalSpec; signal не размещает ордер напрямую. Нет контекста/подтверждения/структурной отмены — no entry. |
| TASK 006 — Momentum/Market Context | Синхронизированные свечи, версии TF, валидные данные. Stale/gap/unconfirmed data — no entry. |
| TASK 007 — Risk/Financial | Equity, fees, filters, structural invalidation, open/pending risk; атомарный reservation и exactly-once fill application. |
| TASK 008 — Data & Execution | Stable client IDs, submit/reconcile/cancel/replace/protection. UNKNOWN/partial/orphan protection → reconcile, no blind retry. |
| TASK 009 — Backtest | Versioned data/rules, fees/slippage, deterministic replay, no look-ahead. Иначе результат недействителен. |
| TASK 010 — Integration & QA | Evidence records TASK 002–009 с точными SHA и логами; любой P0 BLOCKED/FAIL → release BLOCKED. |

Стандарт evidence record: task_id, status, repository, branch, commit_sha, base_sha, file/blob SHAs, test command, run URL/log SHA-256, environment, result, blockers, reviewer. Отсутствующие результаты не подменять предположениями.

## 5. UNKNOWN, restart, duplicate delivery, partial execution

| Сценарий | Требуемое поведение | Текущее доказательство |
|---|---|---|
| Timeout/UNKNOWN после POST | Сохранить clientOrderId/intent, запросить exchange order/trade history, не повторять POST вслепую | Barrier документирует no-blind-retry; все пути отправки ещё не инвентаризированы |
| Перезапуск | Recover → reconcile exchange facts → validate → разрешить входы | Trader имеет recovery/preflight path; crash-injection run не выполнен здесь |
| Повторный fill/user event | Уникальный fill identity и атомарный exactly-once apply | Все writers и уникальные ключи не проверены исчерпывающе |
| Partial fill | Учесть executedQty, quote, fees; защитить подтверждённый остаток; не считать FLAT раньше exchange evidence | Accumulator/campaign components существуют; полный OCO/partial matrix не доказан |
| Cancel/replace | Сохранить обе стороны операции и reconcile старый/новый ордер | SQLite schema содержит cancel_replace_operations; таблица не доказывает корректность алгоритма |
| WS gap / stale data | Инвалидировать контекст, восстановить через REST, блокировать новые входы | Компоненты существуют; реальный recovery run не подтверждён |
| Kill во время выхода | Latch до close; ошибки/остаток → reconcile-required | В server.py latch выставляется до закрытия; гонки и частичный выход требуют тестов |

## 6. Матрица требований / реализации / тестов

| Требование | Кодовый индикатор | Что видно | Что нужно для PASS |
|---|---|---|---|
| Общая execution boundary | execution_barrier.py, MultiPositionTrader._submit_via_execution_barrier | Wrapper блокирует отправку без barrier; валидирует context | Полный AST/call graph всех REST mutation calls |
| Durable intent | execution_intents / execution_events в db.py | Схема и persistence hook присутствуют | Crash tests между persist, POST и response |
| Order FSM | order_state_machine.py | Barrier импортирует FSM | Все переходы, invalid transition, duplicate/restart tests |
| Campaign ownership | campaigns/campaign_signals + campaign services | Схема и сервисы присутствуют | Единственный writer, транзакционные границы и replay tests |
| Exactly-once finance | execution_accumulator.py + DB | Финансовые компоненты присутствуют | Уникальный fill ID, Decimal end-to-end, атомарность и crash tests |
| Context freshness | ExecutionBarrier._validate | Проверяет version, age, permission timeframe | Интеграционный тест реального producer/consumer и race |
| Android | Kotlin source / server.py | Backend control/status path существует | APK SHA, endpoint config, device logs, authority proof |
| Spot Testnet | Binance client / credential checks | Есть Spot/Testnet abstraction | Связанный с SHA полный order/fill/cancel/recovery test |
| Backtest | backtester.py / run_backtest.py — область TASK 009 | В этой проверке не запускался | Детерминированный replay, fees/slippage, anti-lookahead |

## 7. Точные SHA просмотренных файлов

Ниже SHA Git blob, не commit SHA:

| Файл | Blob SHA |
|---|---|
| server.py | f7d93c014975949a7172323801f0fb485013895e |
| execution_barrier.py | c0e7cf4eea424d037b273e6150fa2d3d0ea93b40 |
| portfolio_trader.py | 5c38ed933edf17e0a9d3376363c50786a94a737d |
| db.py | 129645290a074d7958ac75a4128de455754db13e |
| trader.py | b2d9287a10da7d803c8ce14912c24745453033d7 |
| test_production_architecture.py | 867ab7979b63c0528984aa82d76365a932824bcc |
| full_safety_audit.py | 96a8347219c175631d0b733880e8f9efef555beb |

## 8. Открытые блокеры

**P0**
1. Не получен точный HEAD/base SHA и clean-tree baseline.
2. Не завершён inventory всех Binance mutation call sites, включая cancel/replace, OCO, protection, emergency и manual paths.
3. Не доказаны единственный campaign/position writer и canonical financial fill writer; legacy и campaign state coexist.
4. Нет воспроизводимых crash/restart/duplicate/partial-fill/cancel-replace результатов, привязанных к проверенному SHA.
5. Нет физического Android evidence bundle и APK provenance; phone-native и backend-owned execution не доказаны как один выбранный режим.
6. Нет завершённых evidence records TASK 002–008 в этом отчёте.

**P1**
7. Не доказано, что market-data gaps блокируют входы до reconciliation.
8. Не доказана атомарность Decimal/fees/PnL/executed quantity с exactly-once fill application.
9. Не выполнен полный reproducible backtest/anti-lookahead audit.
10. Не проведён полный Spot Testnet lifecycle run на исходниках и конфигурации, связанных с APK.

## 9. Решение

**TASK 001: BLOCKED — NOT ACCEPTED AS PASS.**

В коде видны versioned market context, ExecutionBarrier, durable intent/cancel-replace schema, recovery/preflight components и backend-managed execution path. Это не доказывает отсутствие обходных путей, единственность writers, exactly-once финансовое применение или готовность Android/Testnet к автономной торговле.

Для закрытия нужны точный branch HEAD/base SHA; clean checkout и воспроизводимый baseline; результаты TASK 002–008 с SHA и логами; полный mutation call graph; назначенные единственные owners campaign/position/financial state; интеграционные тесты UNKNOWN/restart/duplicate/partial/cancel-replace; APK/device/Testnet evidence. До закрытия P0 блокеров новые реальные ордера не разрешать.
