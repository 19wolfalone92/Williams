# Williams Bot — ревизия ВСЕХ веток перед сборкой 4.64.40

Дата: 17 ноября 2026. Репозиторий: `19wolfalone92/Williams` (отдельно от Williams-AI).

## Объём и происхождение

Через GitHub API перечислены **48 веток**, включая `main`, активную `fix/nov17-diag21-15pairs-margin-3pct` и ещё **46**. Для каждой из этих 46 выполнено сравнение графов Git `<branch>...<head-4.64.39>` через GitHub compare API. Аудит версий касается именно представленных в репозитории ссылок, а не закрытых/уже удалённых старых веток.

Состояние до интеграции: `main=32e9dd64fab9e0488ea4b566347246ac44627199` (4.64.18), кандидат `38532f06c561196cb4fb87c02da6810440cd97a1` (4.64.39). `main` содержит **два собственных коммита** поверх общего базиса `b3f18f98448269811e73b724885907c14c91a56a`, оба относятся к `.github/workflows/safe-integrated-branch-cleanup.yml`. Их нельзя потерять. План: сохранить исходную ветку и полный cleanup workflow посредством двухродительского Git merge; не force-push `main`.

Среди 46 прочих веток: **28 являются строго предками кандидата** (`behind=0`) и не содержат уникальных коммитов; **18 разошлись** и содержат уникальные коммиты. Пока их уникальные изменения не сравнили и не приняли вручную, такие ветки НЕ удаляются, независимо от возраста. Ветки backup сохранены как известные точки отката.

## A. Полностью включены в кандидат (Git ancestor, behind = 0)

- `backup/main-4.64.5-before-20261111-consolidation`
- `backup/main-before-consolidation-20261023`
- `canonical-v2-integration`
- `feat/four-book-replay-evidence-curriculum-20261027`
- `feat/nov16-intent-ledger-shadow-4.64.38-stacked-v5`
- `feature/nov11-four-book-causal-understanding-4.64.21`
- `feature/nov12-full-four-book-chapter-coverage-4.64.23`
- `feature/nov12-tc2-exit-structure-4.64.22`
- `fix/diag20-boot-fgs-order-evidence-20261116`
- `fix/nov10-entry-gate-flapping-4.64.18`
- `fix/nov10-signed-historical-stop-preflight-4.64.17`
- `fix/nov11-diagnostics9-reboot-heartbeat-4.64.20`
- `fix/nov12-diag14-urgent-fill-and-tc2-research`
- `fix/nov12-historical-stop-exit-visibility-4.64.25`
- `fix/nov12-p0p1-root-performance-4.64.27`
- `fix/nov12-tc2-wm3-supersession-4.64.24`
- `fix/nov13-ltc-exit-attribution-missed-entry-46428`
- `fix/nov15-diagnostics19-4.64.35`
- `fix/nov15-wm1-angulation-4.64.36`
- `fix/nov16-durable-private-event-inbox-4.64.37`
- `night-shift-audit-20261012`
- `release/consolidate-williams-4.64.5-20261030`
- `release/4.64.18-ci-green-20261111`
- `research/master-primary-sources-20261113`
- `research/nov05-tc2-structural-walkforward-validation`
- `research/nov07-tc2-345-period-robustness-gate`
- `research/nov07-tc2-three-four-five-historical-audit`
- `security/codeql-autofix-hardened-on-latest-20261030`

Сохранять эту группу как отдельные активные линии разработки не требуется после успешной интеграции в `main`, **кроме backup**. Старые связанные PR можно закрывать только после подтверждения слияния. Для удаления необходимо заново подтвердить ancestry по текущим SHA на самом `main` и не удалять открытые PR, protected, backup или изменившиеся refs.

## B. Ветви с НЕ включёнными уникальными коммитами (Git diverged)

| Ветка | Уникальные коммиты вне кандидата | Действие |
|---|---:|---|
| `alert-autofix-1` | 1 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `alert-autofix-2` | 1 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `alert-autofix-3` | 1 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `audit/book-course-terminal-evidence-20261023` | 5 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `audit/four-book-alignment-20261023` | 23 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `feat/nov16-intent-ledger-shadow-4.64.38-stacked` | 10 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `feat/nov16-intent-ledger-shadow-4.64.38-stacked-v2` | 10 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `feat/nov16-intent-ledger-shadow-4.64.38-stacked-v3` | 10 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `feat/nov16-intent-ledger-shadow-4.64.38-stacked-v4` | 11 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `feat/nov16-intent-ledger-shadow-4.64.38` | 8 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `fix/ao-zero-side-wave-diagnostics-20261030` | 2 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `fix/diag10-memory-pressure-20261112` | 12 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `fix/diagnostics15-truth-mtf-20261023` | 16 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `fix/nov04-ntd-h1-source-exit-parity` | 1 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `fix/nov07-tc2-no-fail-open-on-unverified-regime` | 1 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `fix/nov10-accurate-gate-and-entry-preflight-4.64.18` | 13 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `fix/oct31-futures-clock-risk-diagnostics` | 12 | Сохранить: нельзя терять независимые/конфликтующие изменения |
| `policy/single-writer-safe-prs-20261024` | 1 | Сохранить: нельзя терять независимые/конфликтующие изменения |

**Предметные наблюдения:**
- `fix/diag10-memory-pressure-20261112`: 12 уникальных коммитов, 10 файлов, Android heap/clock/battery diagnostic. Проверка исходников показала, что текст AND-01 `battery_optimization_ignored` и проверка часовой синхронизации уже присутствуют в последнем кандидате; принудительно мержить старую ветку поверх более нового кода опасно. Её уникальные SHA остаются доступны для точечной сверки.
- `fix/nov10-accurate-gate-and-entry-preflight-4.64.18`: 13 уникальных коммитов, альтернативная ветка того же порога 4.64.18. В текущем кандидате уже есть preflight refresh и независимый UI execution gate; старые версии `VERSION`/Gradle/серверного API нельзя автоматически переносить в 4.64.40.
- `fix/oct31-futures-clock-risk-diagnostics`: 12 уникальных коммитов, перенос старого часовóго пробника может повредить текущий P0 адаптер. Не смешивать неэквивалентные clock-ownership алгоритмы без отдельных слабосетевых испытаний.
- `feat/nov16-intent-ledger-shadow-*v4` и старые черновики: многократно переигранные реализации durable shadow; последняя v5 интегрирована по Git ancestry, старые черновики пока сохранены, так как содержат уникальные SHA. Это не является доказательством, что у v5 отсутствуют миграционные дефекты.
- `audit/*`, `alert-autofix-*`, `policy/single-writer-safe-prs-20261024`: diverged, повторное слепое слияние недопустимо.

## Тесты и правила допуска

Версия после интеграции **4.64.40** в трёх источниках: `VERSION`, `server.py`, `app/build.gradle.kts` (плюс release-contract). Включённая линия содержит изменения 4.64.20→4.64.39: книги/TC2, signed STOP, private user event inbox, SQLite intent shadow (НЕ authority switch), STOP_MARKET deferred-spread fix, 15 NEW-risk символов, 3% дневной лимит, проверка foreign fee-wallet. Код Python/Android требует свежего exact-SHA CI, unit/lint и APK signer fingerprint. Успешный CI не является подписанным Demo доказательством LONG/SHORT FILLED→STOP.

**Принцип удаления:** только строго предков уже подтверждённого `main`, после защиты открытых PR/backup, без удаления diverged и без изменения `main` force-push. Для каждого удалённого ref отчёт GitHub Actions должен содержать имя и сохранённый main SHA.
