# WILLIAMS BOT 2.1 — первичный реестр расхождений «книги → код»
Дата: 2026-10-13. Статус: ACTIVE AUDIT — не APPROVED.

## Проверенный baseline
- main strategy.py: 53b56c828ed626607e30d16b47733b6ffbe81429
- main execution_barrier.py: 860edaa38ffc569b67533822b470a7b7a3fe7996
- main risk_engine.py: bf858824a495397534e956255c63ac9c019dee0a
- task branch strategy.py: 60e94b1ded0039dc35029cbfc4dae050c4c9264b
- Источники в Library: Trading Chaos (TC1), New Trading Dimensions (NTD), Trading Chaos 2 (TC2), сканированный Trading Chaos Course. Издания нельзя смешивать: официальный Profitunity подтверждает эволюцию методики между книгами: https://profitunity.com/our-books/

## D-001 — время фрактального события в main допускает look-ahead
Источник: пятибарная формация требует правых баров до подтверждения. В main strategy.py флаг fractal_up/down ставится на центральной свече после просмотра правого окна; отдельный confirmed_*_level записывается позднее. Потребитель, читающий центральный флаг, может получить сигнал задним числом.
Статус: task branch разделяет центр и событие подтверждения; требуется проверить каждого consumer Python/Android, prefix-invariance и replay на одинаковых свечах. Принцип причинности VERIFIED, сквозная коррекция PARTIALLY VERIFIED.

## D-002 — равные экстремумы: main и task branch расходятся, канон требует точной ссылки
В main центр сравнивается строго с максимумом/минимумом правого окна; равный соседний экстремум исключает кандидата. В task branch равные экстремумы справа не считаются подтверждающими барами и не отменяют кандидата, а строго более экстремальный бар отменяет его.
Нельзя объявлять политику task branch окончательным правилом Вильямса без точного издания, страницы и рисунка для extended configurations. Регрессионный файл tests/test_williams_fractal_confirmation.py добавлен, но результат CI ещё требуется подтвердить. Статус: точная обработка равенства UNRESOLVED по источнику.

## D-003 — повторные события Super AO в main
TC2 описывает WM2 как сигнал третьего AO-бара соответствующего цвета и ценовой триггер за соответствующим ценовым баром. Main strategy.py использует ao_green_streak >= super_ao_bars и зеркальное условие, поэтому при непрерывном ходе WM2-флаг остаётся истинным и на последующих барах. Task branch использует точное равенство длине серии, чтобы событие возникало только на третьем баре.
Статус: mismatch подтверждён по коду; task branch correction needs golden-vector + consumer duplicate tests.

## D-004 — ExecutionBarrier в main fail-open при ошибке записи intent
Это SYSTEM SAFETY RULE, а не правило из книги. Main execution_barrier.py подавляет исключения из save_execution_intent в _persist(). Значит, отказ durable persistence не обязательно блокирует биржевой submit; это ослабляет восстановление после перезапуска и защиту от дублей.
Статус: P0 defect confirmed; требуется fail-closed correction и fault-injection tests (DB locked/disk full/write failure) proving no Binance mutation.

## D-005 — дневной лимит 3% задан параметром, но enforcement этим API не доказан
Main risk_engine.py принимает и сохраняет max_daily_loss_pct=0.03, но analyse() не принимает дневной realized/unrealized PnL или состояние дневного breaker. Один параметр конструктора не доказывает, что лимит реально запрещает новые входы.
Статус: ENFORCEMENT UNVERIFIED; проследить все call sites Python Spot, Python Futures и Android, формулу PnL, комиссии/funding, перезапуск и reset. При лимите 3% новые риск-входы блокируются, но аварийные закрытия/reduce-only должны оставаться доступными.

## D-006 — angulation является приближением, а не формулой автора
Task branch williams_signals.py прямо описывает _angulation() как regression/slope approximation. Официальный Profitunity сообщает, что метод определения angulation менялся; более позднее руководство рассматривает расстояние signal bar до Lips: https://profitunity.com/angulation-for-the-wiseman-bullish-and-bearish-divergent-signal-bar/
Статус: ENGINEERING APPROXIMATION disclosed; exact numeric equivalence to TC2 UNRESOLVED. Нельзя придумывать авторский порог; нужны annotated chart fixtures и версия источника.

## D-007 — правила выхода и трейлинга не доказаны как буквальный TC2
Ранее подготовленная сверка отмечает, что двухзакрытый-H1-барный hard exit и Teeth/fractal/ATR trailing — гибридная инженерная политика, а не доказанный дословный алгоритм книги. Каждое условие выхода нужно привязать к точной главе/рисунку либо маркировать ENGINEERING OVERLAY. Статус: PARTIALLY VERIFIED.

## D-008 — равенство Python Spot / Futures / Android не доказано
Предыдущая сверка выявила возможное расхождение: WM2/WM3 помечаются ADD_ON, тогда как новый campaign selector может требовать ENTRY; Android Futures реализует ограниченный subset без полного pyramiding. Эти findings нужно повторно проверить по текущему caller chain, а не принимать старый отчёт за доказательство актуального состояния. Нужны одинаковые golden candle replays и сравнение signal → trigger → risk → order → fill → campaign → exit.

## Различия методологических профилей
- TC1: Zero Point/five magic bullets, Elliott context, MFI windows, Fractal Start/Signal/Stop, leverage.
- NTD: отдельные AO Saucer/Zero-Line Crossing/Twin Peaks, AC patterns, Zones, Balance Line trades.
- TC2: Alligator context, First Wise Man divergent bar, Second Wise Man Super AO, Third Wise Man Fractal, campaign/add-on/exit.
- Trading Chaos Course — отдельный исторический скан; его точная редакция/дата не установлена. Нельзя превратить все методы в один универсальный AND-гейт.
- Вычисление AO/AC не доказывает реализацию полных NTD-семейств. Binance traded volume не автоматически эквивалентен историческому tick volume для MFI.

## Приоритет реальных исправлений
1. P0: ExecutionBarrier fail-closed durable intent + DB fault-injection.
2. P0: доказать и обеспечить лимит дневного убытка 3% на общем admission path Spot/Futures/Android; не блокировать закрытия/снижение риска.
3. P1: завершить source-resolved fractal rules; устранить backdated event semantics во всех consumers.
4. P1: зафиксировать WM2 как единичное событие на третьем AO-баре и проверить downstream idempotency.
5. P1: трассировать первый допустимый сигнал Wise Man для Spot/Futures/Android и устранить расхождения.
6. P1: разделить source-rule exits и engineering overlays; протестировать partial fills, missing protection, restart и unknown order state.

## Критерий закрытия
Точный edition/chapter/page/figure → формальное правило → функция и call chain → воспроизводящий тест → correction commit → passing CI на конкретном SHA → replay/fault-injection evidence. Этот реестр фиксирует расхождения, но сам по себе не закрывает кодовые задачи.


## Дополнение: выполненные исправления и актуальные ограничения (2026-10-13)

Это дополнение обновляет статусы выше; исходные findings сохранены как история аудита.

### Исправлено в task branch
- **D-004 / ExecutionBarrier:** mutation блокируется при отсутствии DB, при ошибке сохранения durable intent и при ошибке обязательного admission-event. Если биржевой submit уже мог состояться, а последующая запись результата/события падает, операция переводится в неоднозначное состояние с требованием reconciliation; автоматический повтор submit не выполняется. Добавлены fault-injection regression tests. Код и тесты в commits `0331bde8e40e`, `4c85f4caf435` и предшествующих исправлениях этой ветки.
- **D-005 / Python Spot admission:** `EquityCircuitBreaker` теперь сохраняет дневной trip latch до следующего UTC-дня; восстановление PnL внутри дня не открывает новые входы повторно. Отсутствующий/несогласованный baseline, повреждённый или будущий day key, недоступный PnL ledger и ошибка сохранения latch блокируют допуск. `scan_and_execute()` проверяет дневной риск до обычного adoption условных входов, а `execute_campaign()` повторно проверяет риск на канонической границе допуска, чтобы прямой вызов не обходил gate. При блокировке отменяются только условные входы; выходы и аварийное снижение риска остаются отдельными путями. Основные commits: `1f7def01b1a2`, `b7fe3e1e3467`, `dec85f34f8b3`, `88b03db3dce4`, `9bfc096fda22`.
- **D-005 / Android Spot runtime:** добавлено отменяемое через execution barrier снятие ожидающих условных BUY при срабатывании дневного лимита или отказе монитора; неизвестное состояние переводится в reconciliation-required, а не считается успешно отменённым. Логика находится в `StandaloneRuntime.kt`; связанные CI ранее подтвердили сборку на SHA `4c85f4caf435`.
- **D-001 / Fractal timing:** task branch содержит разделение центра формации и времени подтверждения плюс регрессионные сценарии. Сквозная проверка каждого consumer и source-resolved правило равных экстремумов всё ещё не закрыты.
- **D-003 / Super AO:** task branch использует точное совпадение длины серии, чтобы не повторять WM2 на четвёртом и последующих барах одной непрерывной серии. Полная cross-runtime golden-replay проверка остаётся открытой.

### Проверка CI
- На SHA `4c85f4caf435` ранее успешно завершились Python backend tests, Williams Campaign CI и Android APK build.
- На SHA `b9b8218951a0` Python backend tests завершились успешно после добавления проверки порядка дневного gate и отмены ожидающих входов.
- На SHA `8c1d5e02fafe` Python backend tests завершились успешно; Android APK build и Williams Campaign CI на момент этого обновления ещё выполнялись. Итог для более позднего SHA должен быть подтверждён отдельно.

### Блокирующий разрыв: Futures
По текущему дереву репозитория не найдено Futures-специфичных файлов, вызовов Binance Futures API (`/fapi`, `UMFutures`) или отдельного Futures execution path. Следовательно, **Futures не считается реализованным и не может быть сертифицирован на лимит 3% или на parity с Spot/Android**. Нельзя закрывать D-005/D-008 формулировкой «Spot/Futures/Android исправлено»: подтверждены только существующие Spot backend и Android Spot пути. Для Futures требуется отдельная реализация/аудит, включая position mode, margin, leverage, reduce-only, funding/fees, partial fills, stop protection и recovery contracts.

### Открытые пункты
1. Futures execution/risk path отсутствует — P0 scope gap относительно требования Spot + Futures.
2. Fractal equal-extreme policy всё ещё UNRESOLVED до привязки к конкретным изданию/странице/рисунку; нужны одинаковые данные и prefix-invariance/replay всех consumers.
3. WM2: завершить golden-vector и downstream idempotency tests на Python и Android.
4. Angulation и exit/trailing: отделить подтверждённые книжные правила от инженерных overlays с точными source references.
5. После последних изменений повторно подтвердить все обязательные CI checks на одном и том же финальном SHA; не переносить результат CI с предыдущего коммита на новый.
