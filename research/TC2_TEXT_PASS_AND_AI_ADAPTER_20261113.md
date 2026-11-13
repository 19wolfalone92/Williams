# TC2: завершение первого чтения и исследовательский AI-адаптер

Репозиторий `19wolfalone92/Williams`, ветка
`research/master-primary-sources-20261113`, PR240, продолжение от
`56e35c568cc5a70d1440ebd0dda5b1b730a1198e`. Main перед работой:
`32e9dd64fab9e0488ea4b566347246ac44627199`; не изменялся.
Единственный текущий поток — эта исследовательская ветка. Открытые PR и workflow
проверены; новых пересекающихся изменений не обнаружено. Версия приложения
остаётся4.64.30: здесь добавлены исследовательский Python-контракт и доказательства,
Android-торговая логика в этом продолжении не изменялась.

## Выполненные пункты плана

| Пункт | Фактический результат этого продолжения |
|---|---|
|1, исходники|Повторно сверены локальные байты6 PDF: SHA/число страниц совпадают с SOURCE_CATALOG. Приватные PDF/текст/рендеры не публикуются.|
|2–4, чтение|Прочитаны оставшиеся EN1–123,205–244,248–257. Теперь все257 физических страниц английского TC2 имеют первый receipt: текст, front matter, приложения, библиография/индекс и пустые разделители учитываются раздельно.|
|4, рисунки/переводы|Визуально прочитаны33 новых концептуальных/платформенных рисунка; новый8-й смысловой якорь: EN212–213 ↔ RU224/192 ↔ RU314/255. Оба русских FAQ также просмотрены в рендерах.|
|5, независимое чтение|Не завершено. Повторные просмотры тем же автором не выдаются за независимые. Сложные9.10/10.7/11.2/11.7/12.1–4 остаются открыты.|
|6, символы|Сохраняются предыдущие10 NEW risk /15 management проверки; новый допуск символов не менялся.|
|7, Demo/device|Не выполнялись signed Demo/live ордера, физический телефон и восстановление в реальном окружении.|
|8, подтверждённые расхождения|Разделены фактические формулы, discretionary/psychological контекст, ошибочная точная Fibonacci-пропорция и неподтверждённые performance claims. Общий half-margin найден как незавершённый mapping/implementation gap.|
|9, AI|Реализованы offline V1 adapter, CLI и synthetic roundtrip. Старые source cards/train/test Williams-AI не менялись. Вызова модели, GPU, обучения или expert-market экзамена нет.|
|10, проверки|2336 Python tests passed,2 skipped; release gate, ruff и static CI-mode safety прошли. Новый независимый holdout не проводился; старый seen replay не переименовывался в OOS. Точные CI результаты нового SHA — в PR.|

До этого checkpoint:120 страниц/52 рисунка/81 правило. Теперь:
**295 физических first-pass receipts,85 qualitative figure entries,91 правило**.
Из295 страниц115 с визуальным receipt и180 text-only; из257 английских98 визуально
просмотрены. Первое чтение включает навигацию и пустые страницы, это не295 страниц
новых торговых формул. В новой английской части отдельно указаны14 front-matter,
11 visually confirmed blank,12 navigation и136 substantive text страниц;
84 предыдущих receipts пока не переклассифицированы по этой новой типологии.

Независимо проверенных страниц/рисунков по-прежнему0. SOURCE_CATALOG
`full_content_review_complete:false`, whole_book_certified:false,
main_merge_allowed:false. Целочисленный реестр1213 страниц не означает полноту
понимания, реализации или тестирования. Неизвестные знаменатели не заменяются
процентами95/99/100.

## Новые выводы и границы применения

**EN212–213, печатные183–184:** автор не задаёт универсальный процент риска на
сделку и советует не занимать маржой больше половины счёта одновременно. RU224
PDF192/печатная177 и RU314 PDF255 подтверждают половину счёта, но переводят margin
более общо как использование счёта. STOP должен следовать рынку; размер позиции
можно уменьшать/отклонять по проектному бюджету. Это не причина удалить дневной
лимит3%, fee/slippage buffers, резерв stop-risk или protective STOP.

В `futures_williams_runtime.py::_size_from_risk` есть25% default per-order margin
cap (настраивается) и95% available-buffer. Android
`assessFuturesEntryRisk` сравнивает начальную маржу отдельного входа с buffered
available balance; campaign-risk отдельно считает open+pending loss. Эти проверки
**не доказывают** aggregate occupied-margin<=50% с позициями, pending, partial и
unknown. Enforcing gate в этом продолжении не добавлялся: ещё надо закрепить
семантику exchange account fields, атомарный ledger/reservation и parity Python /
Android. Available balance нельзя без доказательств объявить total initial margin.
Авторская фраза о сохранении половины счёта не является гарантией при плечах,
гэпах, funding и проскальзывании.

**EN51–52, печатные22–23:** рекурсия Fibonacci описана, но утверждение о точном
.618 для любых соседних чисел неверно:1/1=1,1/2=.5,2/3≈.667. Предел отношений
раннего/следующего положительного члена —(sqrt(5)-1)/2. Ошибочное утверждение
quarantined; точные wave thresholds из него не выводились.

**Психология и обучение:** главы1–7 и Appendix3 прочитаны, включая упражнения,
метафоры, health/brain claims. Субъективные эффекты и духовные/медицинские заявления
не становятся доказанными машинными состояниями или причинными рыночными фактами.
Пять human-learning уровней не дают bot competence score. One-minute chart в EN107
служит упражнением внимания, FAQ EN210 discourages sub-five-minute trading:
наблюдение и новая торговая инструкция здесь различны.

**Claims и отображение:** testimonials,75–85% student success,90% уменьшение
wave ambiguity и10% monthly aspiration не измерялись для бота. Appendix4–7 меню
и доли subgraph — историческая настройка картинки. Bars to Right=8 освобождает
место под известный Alligator tail, не создаёт восемь будущих OHLC. Рисунки
не оцифровывались в вымышленные book OHLC.

## Что реализовано в адаптере

Подробный контракт: `AI_CANDIDATE_ADAPTER_V1.md`. Exact edition/SHA/page retrieval,
четыре evidence-file hashes, immutable-by-copy request, closed H1 causal input,
allowlist citations, запрет неизвестных полей/версий и любого authority escalation.
Валидность структуры не доказывает semantic entailment. Из91 atomic specs87 неполны, включая операционные TC2; четыре полных TC1 MFI
specs остаются observer-only и также не допускают TC2 LONG/SHORT. Поэтому нынешние
направленные proposals отклоняются; review,
conflict и abstain допустимы. Даже после завершения спецификаций такой research
receipt не разрешит торговлю/STOP/обучение. Код не подключён к торговому маршруту.

## Завершённая локальная проверка

- `pytest -q`:2336 passed,2 skipped,4 existing FastAPI deprecation warnings,
  26.24s. Целевой adapter/evidence прогон103 passed.
- `ruff check --select F821,F822,F823,E9 .`:PASS.
- `python ci_release_gate.py`:PASS.
- `CI=true python full_safety_audit.py`:0 failures,11 warnings. Это static checkout
  без production database/credentials; CI-mode результат не является runtime E2E.
- `python tools/audit_williams_master_research.py`:integrity PASS.
  `--require-complete` ожидаемо завершает2: общий план не закрыт.
- Сохранённый вручную составленный SYNTHETIC example воспроизведён адаптером.
  Никаких model calls, экспертных подписей и внешних торговых операций.

## Следующий маршрут

Продолжить TC1_RU по ещё не REVIEWED страницам от начала; затем NTD_RU/Course.
Воспроизвести Course OCR прежде замены119 оригинальных OCR hashes. Для TC2
закончить независимую визуальную сверку сложных фигур и атомарные rule specs.
Для aggregate half-margin закрепить account-field contract и полную совокупность
обязательств, проверить гонки/partial/unknown/restart в обоих runtimes прежде допуска.
Подготовить внешний source/market expert cohort и отдельный новый опыт, не менять
frozen holdout. Выполнить signed Demo/device matrix; только после критических gates
рассматривать совместимое поочерёдное слияние PR, без прямой записи main.
