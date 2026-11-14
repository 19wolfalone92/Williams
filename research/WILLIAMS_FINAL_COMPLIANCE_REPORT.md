# Williams Master Research: приёмка НЕ ЗАВЕРШЕНА

Контрольная точка14.11.2026, Williams Bot4.64.31, ветка
`research/master-primary-sources-20261113`, draft PR240 поверх PR239.
Название файла задано исходным заданием; это статус, а не финальная сертификация.
Актуальные машинные показатели воспроизводятся из `MASTER_RESEARCH_PROGRESS.json`.

| Требование | Проверенный статус |
|---|---|
| Материалы4 направлений |6 предоставленных PDF,1213 физических страниц с SHA |
| Реестр страниц |1213 acquisition rows; старые hashes/lengths сохранены |
| Первое чтение основных файлов |TC1_RU140/140, NTD_RU156/156, TC2_EN257/257, Course122/122 |
| Все предоставленные страницы изучены |НЕТ:724 first-pass receipts,216 full-page visual receipts;489 страниц двух русских TC2 ещё без полного первого чтения |
| Карта трёх TC2 |Все795 физических страниц распределены по разделам;68 просмотренных crops заголовков,16 выбранных смысловых anchors. Это не полная смысловая карта |
| Глава12 TC2 |Сопоставлена полностью: EN174–187, RU224153–166, RU314201–218, включая title/blank leaves |
| Рисунки |157 качественных записей, включая повторные версии тех же рисунков; знаменатель всех рисунков неизвестен |
| Независимые повторные чтения |0. Совпадение двух переводов не является независимой проверкой |
| Правила |147 зарегистрировано,142 source-context rechecked; полный знаменатель неизвестен. Только4 полных atomic specs, все intraday MFI |
| Source conflicts/caveats |38 записей: TC1=10, NTD=15, Course=7, cross-edition TC2=6; не все являются ошибками автора |
| Реальные исправления |MFI ties/causal units, SMMA, WM2 warmup, NEW risk10/management15, AccountV3 permissions, missing OHLC/index guards, finite per-entry margin config |
| TC2-only new source |Сохранено; TC1/NTD/Course observation-only |
| Риск/STOP |Общий50% margin ledger/final-submit ещё не реализован; actual signed Demo STOP acceptance и physical Android открыты |
| Инструменты |NEW risk10, management/scan15; старые pending/partial/unknown liabilities сохраняются |
| Книжная арифметика |TC1/NTD/TC2 отдельные Decimal audits. Reported/book gross не подменяет фактические fills, costs/net или equal-risk backtest |
| История |Архивный seen replay, не новый независимый OOS |
| AI adapter |Offline candidate/citation contract; ABSTAIN, authority/training false. Separate Williams-AI, веса и frozen train/test не изменены |
| Соответствие99/100% |Не заявлено; измеримого знаменателя всех применимых правил нет |
| Main integration |НЕТ: source/atomic/independent/Demo/device/holdout gates открыты |
| Android/APK |Exact-head CI отдельно в PR240; APK/emulator не доказывают безопасную торговлю на реальном устройстве |

В главе12 обнаружены два общих расхождения перевода (название short setup,
buy-stop offset), отдельная неверная подпись10 у RU314 и общая исходная
неоднозначность AO/price-bar terminology. Все трактовки ограничены страницами
и сохранены в `TC2_TRANSLATION_CONFLICT_REGISTER.json`. Ошибка нашей ссылки
на Appendix3 исправлена на Appendix7; формула AO не менялась.

Следующий маршрут: полное сопоставление технических глав8–11 двух переводов,
затем оставшиеся главы/приложения, независимые сложные рисунки/atomic specs;
aggregate margin ledger обоих runtimes, expert cohort, заранее зафиксированный
holdout и actual Demo/device. Объединение main закрыто до критических gates.
