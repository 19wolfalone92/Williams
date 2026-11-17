# Course: первый проход скана и происхождение OCR

Репозиторий `19wolfalone92/Williams`, ветка
`research/master-primary-sources-20261113`, PR240. База этапа
`2ebadb0f59894a3f0d48353709f8e1751a9cfa71`: все7 CI workflows этой базы,
включая APK/lint/unit и API35/36 UI/lifecycle, завершилисьsuccess.
Новый итоговый source commit проверяется отдельно. Main
`32e9dd64fab9e0488ea4b566347246ac44627199` не изменялся.

## Прочитано и сохранено

В Course нет текстового слоя. Из того же PDF сSHA256
`fc57f707378087bfc5146db5e242c51ce8135e0b359f0c607305b364e0decfca`
получен новый приватный OCR всех122 страниц: Tesseract5.5.0,eng,psm3,
render scale2.5,192297 символов. Это отдельная производная, **не прежние
acquisition bytes**. Полный OCR и изображения не публикуются вGit.

Все122 физических страницы имеют ручной first-pass receipt текста/подписей;
25 страниц — визуальный receipt,11 новых качественных figure entries.
Психология, вопросы, дневники, навигация и заключительный notice не пропущены.
Сложные непросмотренные диаграммы имеют text-only receipt и явную неопределённость,
а не заявление о полном визуальном понимании или точной оцифровке свечей.

Глобально698/1213 физических first-pass receipts,190 visual,143 figure entries,
147 правил,142 source-context rechecked. Первое чтение основной предоставленной
копии каждой семьи завершено:TC1_RU140,NTD_RU156,TC2_EN257,Course122.
Оставшиеся515 страниц относятся к двум переводамTC2; полная page map ещё не готова.
Полных atomic specs осталось4 intraday MFI. Независимых вторых проверок0.

`COURSE_REVIEW_OCR_PROVENANCE.json` хранит для каждого листа отдельно новый
review-text hash и прежний acquired-text hash. Все1213 прежних acquisition
hashes/lengths сохранены, включая122 Course rows. Inventory exporter переносит
review metadata отдельно; аудит отвергает подмену старого hash новым, дубликаты
страниц, несогласованный receipt и пустой OCR без визуального чтения.

## Существенные результаты

| Источник | Уточнение и его применение |
|---|---|
|21–24,35 |Tick volume — число изменений цены. Binance number-of-trades и base volume остаются явно обозначенными proxies, не точным tick volume курса|
|22–28 |Daily volume deadband отличается от intraday strict signs. MFI относителен к предыдущему наблюдению; squat не требует всегда более узкого диапазона и может продолжать тренд|
|32,43–45 |100–140 относится к выбранной волновой последовательности. Нужен реальный младшийTF; ноль осциллятора — минимумW4, не её завершение|
|40–45 |5/34/5 — практическая price-based аппроксимация per-wave MFI, не равенство range/volume. Histogram-versus-signal, zero side и цвет различаются|
|61–72 |Роли start/signal становятся известны после подтверждения. Аналитическая отмена не заменяет signed cancel/readback. Идеальный mixed Green/Squat thumb отличается от более слабого допустимого|
|78–84 |Порядок контракта и новое net exposure различаются. В Course приW4 описан reverse кNETshort7, отличный от TC1 plain-stop; TC2 не получает этот reverse автоматически|
|85–92 |Airbag — защита на фактическом старшемTF. Executive Decision включает equity/имеющиеся позиции; четыре согласные стрелки не являются универсальным entry gate|
|99–118 |Клинические/мозговые/типологические/успешностные утверждения — приписанные источнику human coaching claims, не machine facts, наблюдаемые эмоции или подтверждённая доходность|
|119–122 |Level5 divider, дневники и явная отсылка к личному tutorial. Полного индивидуального материала и внешнего Computorial вPDF нет|

12 новых записей не получили execution/training authority. У уже существующих
engineering mappings signed fills/ownership/price grammar отдельно уточнена
классификация: чтение source context не превращает инженерный контракт
в дословное книжное правило или независимо проверенный алгоритм.

## То, чего OCR не показал

- На19 напечатан повтор1-2 в drifter list, поверх него рукописное1-3.
  На52 рукописное **not** меняет смысл фразы о коррекции. Автор правок
  неизвестен; машинный текст не выдаётся за полный авторский оригинал.
- На56 находится вложенная волновая схема с пустым heading, хотя OCR пуст.
  Страница классифицированаGRAPHIC_DOMINANT, а не как blank или missing data.
-60 и103 просмотрены после поворота270°. Первое чтение flow chart не является
  независимой сертификацией всех ветвей алгоритма.
-84 сохраняет видимую сторону «оставить1 long» внутри short campaign;
  частичное покрытие не интерпретируется как реальный reverse.
-51 буквально пишет(W1+W3), в то время как39/44 используют расстояниеp0→p3.
  Формулировка и знак/reference airbag86 остаются неразрешёнными.

Всего7 Course caveats сохранены в `COURSE_SOURCE_CONFLICT_REGISTER.json`.
Это не7 доказанных ошибок автора: список включает неизвестное авторство
пометок, различие ideal/weak, неоднозначности и отсутствующий внешний материал.
В совокупности сTC1/NTD —32 source conflict/caveat records, independent0.

## Проверки и продолжение

Полный локальный Python: **2450 passed,2 skipped,4 прежних FastAPI warnings,
27.80s**. Ruff и release contract:PASS. Clean-CI static safety:
0 failures,11 прежних warnings, без production DB. Новые6 регрессий проверяют
Course coverage/gap, OCR provenance и сохранение старой identity при экспорте.
Уточнённый отрицательный тест пустого OCR дополнительно выполнен в focused suite.

Свежий inventory воспроизвёл1091 non-Course rows полностью; все1213 старых
hashes/lengths сохранились.122 новых OCR hashes дополнительно сверены с
реальными приватными bytes. Evidence integrity:PASS; complete gate ожидаемоexit2.
AI synthetic packet пересобран на новых registry/catalog/page hashes;
model/training не вызывался, Williams-AI/frozen train/test не менялись.

Далее — полная сверка двух переводовTC2, независимые сложные фигуры/atomic specs,
экспертный набор и account-wide half-margin ledger/final-submit gate по уже
подготовленному контракту. Aggregate50% cap пока не подключён. Signed Demo,
физический телефон и новый заранее зафиксированный holdout не выполнены.
Первая обработка четырёх supplied файлов не закрывает эти gates и не разрешаетmerge.
