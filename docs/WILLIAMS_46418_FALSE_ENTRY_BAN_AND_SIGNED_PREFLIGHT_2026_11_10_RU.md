# Williams Bot 4.64.18 — реальные причины «входы запрещены почти постоянно»

**Доказательство:** `Williams_Diagnostics (5).zip`, Samsung Android 17, версия `4.64.17-debug`, 2026-11-10 16:10:02.800 UTC (19:10 Минск). Осмотрены `diagnostic_report.json`, `execution.log`, `binance.log`, `scanner_snapshot.json`, `event_timeline.json` и соответствующий исходный код.

## Факт №1: ложная блокировка на экране

| Источник | Реальное состояние |
| --- | --- |
| `status.execution_enabled` | `true` |
| `futures_campaign_coordinator.state` | `READY` |
| `cockpit_readiness.execution_door_ready` | `true` |
| `execution_gate.ready` | `true` |
| `execution_gate.reasons` | `[]` |
| `status.reconcile_required` | `false` |
| `cockpit_readiness.entry_reserved_risk_pct` | `0.0` из `0.006` |
| `scanner_state` | `RUNNING` |
| `scanner_last_scan_at` | 1794326957717; за ~45 с до ZIP |
| `cockpit_readiness.new_entries_allowed` | **`false`** |
| `cockpit_readiness.new_entries_reason` | **«Сканер: Работает»** |

Корень: `CockpitDiagnostics.kt` старой версии включал `scannerReady = (scanner_state == READY)` как **обязательное** условие `entries`. Отдельный H1-скан каждые ~30 с превращал работающий signed Binance Execution Door в ложный визуальный «запрет» на весь цикл сканирования.

**Исправление:** индикатор «Новые входы» следует фактическому `execution_enabled`, `coordinator.ready_for_new_entry`, паузе, kill, связи, лимитам и риску. Сканер имеет **отдельный** информационный статус `scanner_read_only_state` / `scanner_last_completed_observation_ready`. Это не расширяет полномочия на биржевую заявку: нужны настоящая закрытая свеча H1, свежий сигнал, исходные STOP-подтверждения, бюджет и финальная проверка Binance.

## Факт №2: реальная заявка была отклонена до Binance POST

В архивном журнале обнаружена `XRPUSDT SHORT` запись `COCKPIT_ORDER_RESPONSE`: `FUTURES_ENTRY_CONTEXT_REJECTED: Futures entry was not submitted: SIGNED_RECONCILIATION_STALE_BEFORE_ENTRY`. Реальный STOP-ордер *не* был отправлен; это не успешный вход.

Корень цепочки: после многосимвольного сканирования выполняются H1/D1/H4 контекст, размер, риск, биржевые GET и финальное подтверждение H1. Исходный `lastRestReconcileMs` привязан к **началу** signed exchange snapshot, а не моменту конца обработки. За это время доказательство может пересечь строгую границу **90 секунд**.

**Исправление:** после обнаружения хотя бы одного подтверждённого кандидата и *до* получения подписанных позиций, расчёта риска и сохранения нового ENTRY проверять возраст последнего signed proof. Если осталось менее 45 секунд из 90, выполнять дополнительную account-wide signed сверку через существующий сериализованный `Execution Door`. После завершения ещё раз проверять свежесть **не старше 45 секунд** и `executionReady()`. Если новая сверка не удалась или длилась слишком долго — **никакого POST**, кандидат отклоняется с `PRE_ENTRY_SIGNED_EXCHANGE_PROOF_UNVERIFIED`, штатное P0-сопровождение продолжает работу. Старые стопы не отменяются, дневной лимит, риск, частичные исполнения и проверка H1 не смягчаются.

## Отдельный настоящий транспортный риск

В журнале несколько прошлых `Futures server clock probe failed; signed request blocked`. Это реальные ошибки сетевого/биржевого подтверждения времени, а не UI-артефакты. Они по-прежнему строго блокируют отправку заявки при непроверенном UTC. Отсутствие HTTP-связи или ошибку подписи **нельзя** исправлять смягчением защитных ограничений; причина требует измерений в следующих ZIP.

## Приёмка

- Android unit tests: `CockpitDiagnosticsTest.runningScannerDoesNotFlickerSignedEntryPermissionDuringFreshH1Scan`, реальные ограничения останутся красными; `SignedEntryRefreshPolicyTest` проверяет границу headroom, 90s и rollback.
- `test_android_46418_entry_gate.py`: доказать, что UI уже не использует scanner READY как второй торговый latch, а preflight идёт до signed position/risk и не смягчает STOP/UTC.
- GitHub Actions Android lint, unit, Python, Campaign CI, APK signing, API 35/36 emulator; историческая приёмка LONG/SHORT STOP в Binance Demo на физическом Android остаётся **NOT VERIFIED**.
- Реальная 24h стабильность и восстановления после отключения интернета/перезагрузки — не доказаны. `main` не менять до полной приёмки; разрешение UI не означает, что текущая H1-стратегия нашла действующий вход.
