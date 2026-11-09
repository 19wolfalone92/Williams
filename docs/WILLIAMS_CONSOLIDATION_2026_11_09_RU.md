# Williams Bot — аудит всех веток и план консолидации (9 ноября 2026)

## Источники и подтверждённая структура
- Репозиторий: `19wolfalone92/Williams`.
- Проверено **153 ветки** через GitHub branch discovery и `compare_commits` против ветки `fix/4.64.10-unified-cockpit-audit-20261109`.
- **142** ветки не содержат уникальных коммитов сверх актуального предка (в том числе `main`, `perf/nov07-tc2-complex-h1-prefix-bounded`, и все последовательные PR #216–219); **11** исторически разошлись с ним. Это факт графа Git, не утверждение о прохождении тестов каждой исторической ветки.
- До интеграции `main` был на **4.64.5** и на 409 коммитов позади 4.64.10 (fast-forward возможен, force push не требуется).
- Последняя модель исполнения: Android / Kotlin `StandaloneRuntime` + `NativeFuturesCampaignCoordinator` + подписанный `BinanceUsdMFuturesTestnetAdapter`. Python `futures_williams_runtime.py` и `server.py` присутствуют как отдельный стек, **не** второй подписанный исполнитель внутри текущего APK. Не соединять исполнение двух стеков автоматически.
- На устройстве доступно **15** строго фиксированных USDⓈ-M Demo USDT контрактов, сохранены только входы TC2 H1, STOP-entry, защита, совокупный 0,6% риск, дневной предел 3% и максимум **10 одновременно открытых кампаний**.

## 11 исторически разошедшихся веток: решение
| Ветка | Отличия по GitHub compare | Решение в интеграции |
| --- | --- | --- |
| `alert-autofix-1` | 1 commit, старый Python CI | Не переносить вслепую: CI сильно устарел |
| `alert-autofix-2` | 1 commit, старый Android test harness | Сохранить отдельно: регрессии уже покрыты актуальным CI |
| `alert-autofix-3` | 1 commit, старый `server.py` | Не замещать новый backend старой версией |
| `audit/book-course-terminal-evidence-20261023` | 5 commits, исследовательская волновая терминальная разметка | Архив; TC1/Course без права LIVE TC2 |
| `audit/four-book-alignment-20261023` | 23 commits, отдельные read-only TC1/Course схемы, аналитический UI | Сохранить для книжной верификации, не переносить риск и старый UI |
| `fix/ao-zero-side-wave-diagnostics-20261030` | 2 commits, trace + test | Не заменять текущие decision traces без regression parity |
| `fix/diagnostics15-truth-mtf-20261023` | 16 commits, тот же старый research UI | Устаревшее представление, не подменять новый cockpit |
| `fix/nov04-ntd-h1-source-exit-parity` | 1 commit, NTD test | Только наблюдения, НЕ допуск к ордерам |
| `fix/nov07-tc2-no-fail-open-on-unverified-regime` | 1 commit, старый congestion test | Текущее fail-closed gate оставлено; исследовательский тест без unsafe переноса |
| `fix/oct31-futures-clock-risk-diagnostics` | 12 commits, bounded public Binance time, continuity и tests | **Выбрано и перенесено**: 20s proactive clock renewal, ограниченная 5s попытка, защита 30s TTL, без signed endpoint mutations, тесты |
| `policy/single-writer-safe-prs-20261024` | 1 commit, `AGENTS.md` | Политика единого писателя и защищённого main сохранена и дополнена |

Решение об «архиве» означает **оставить существующие ветки/коммиты на GitHub**. Никаких веток не удалено, история сохранена. Графически «diverged» ≠ «лучше» или «готово к исполнению».

## Устранённые несогласованности в текущем кандидате
1. `main` 4.64.5 → единый кандидат 4.64.11, созданный поверх цепочки 4.64.7/4.64.8/4.64.9/4.64.10. Не потеряны hotfix защитного STOP и OOM, 15 пар или новый UI.
2. Старые подписи `WILLIAMS BOT 2.1` и `10 пар` в cockpit заменены на `BuildConfig.VERSION_NAME` и канонический список.
3. Общий Kotlin-перечень `APPROVED_FUTURES_SYMBOLS` — один источник для графика, scanner и signed adapter. Число открытых кампаний **отдельно** зафиксировано 10; нельзя случайно увеличить его до 15.
4. Python `DEFAULT_FUTURES_SYMBOLS` = те же 15 контрактов; приёмочный `tools/verify_public_futures_demo.py` теперь читает **этот же набор**, вместо старых 10.
5. Read-only public BTC/USD-M exchange time имеет независимый bounded time probe: медленная/ошибочная сеть не должна самовольно разрешать закрытые свечи или UTC risk reset.
6. Приложение различает START/WARMUP/RUNNING/READY/READY_BLOCKED, не выдаёт 0/15 за отсутствие сигналов и не маскирует устаревшую сверку Binance зелёной метрикой.
7. `README.md` уточняет, что актуальный APK действительно исполняет native Futures coordinator, а не только старую удалённую VPS-панель.

## Quality gates
| Контроль | Автоматическая проверка | Ограничения |
| --- | --- | --- |
| TC2 H1 / LONG + SHORT / no-lookahead | Kotlin unit, Python regression, CI release contract | Книжные рисунки и реальные fills не доказываются unit-тестом |
| Сканер 15/15, тайм-ауты, fallback, market metadata | Scan / MarketData / release tests, public Demo probe | Сетевой мониторинг на физическом Samsung дополнителен |
| Binance API signed adapter, timestamp, rate limit | Negative and positive mocked HTTP tests | Не доказывает действующую привязку API-ключа к пользователю |
| STOP-ордера, owner ID, replacement, flat, reboot | Native campaign and safety unit suites, persistent-state tests | Обязателен отдельный signed Demo exchange receipt на реальном устройстве |
| Риск: 3% в UTC, 0,6%, 10 слотов, плечо 1–125 с биржевой проверкой | Kotlin/Python risk regression and CI | 125× никогда не выбирается автоматически |
| Сборка Android + подпись + CI | Lint, unit, debug APK SHA/certificate match | APK installed on Samsung и 24h lifecycle — отдельная приёмка |

## Важный release HOLD
Зелёные CI и публичный `GET /fapi/v1/time`/exchangeInfo/klines **не доказывают**, что реальный подтверждённый Binance STOP защищает открытый LONG/SHORT после fill, reboot и потери сети.
В `docs/WILLIAMS_TESTNET_APK_RELEASE_HOLD_2026-10-28_RU.md` уже есть запрет объявлять систему прошедшей production/Testnet acceptance без независимых физических signed receipts.
Не подменять это «100% по книгам». Если приёмка не завершена, держать кандидата в PR, **не объявлять main стабильной торговой версией**.
