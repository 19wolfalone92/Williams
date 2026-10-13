# WILLIAMS BOT 2.1 — полный реестр индикаторов и торговых моделей: первоисточники ↔ код

Дата аудита: 2026-10-13  
Рабочая ветка: `futures-williams-campaign`  
Статус: `SOURCE/CODE CROSSWALK UPDATED — BEHAVIOUR NOT YET FULLY VERIFIED`

## 1. Как читать этот документ

Проверяемые источники в Library:

1. Bill Williams, **Trading Chaos** / «Торговый Хаос» (TC1).
2. Bill Williams, **New Trading Dimensions** / «Новые измерения в биржевой торговле» (NTD).
3. Bill Williams и Justine Gregory-Williams, **Trading Chaos, Second Edition** / «Торговый Хаос 2» (TC2).
4. **Trading Chaos Course** — предоставленный скан на 122 страницы.

Это не четыре копии одной стратегии. TC1, NTD, TC2 и Course отражают развитие методологии. Поэтому основной production-профиль здесь — `TC2_THREE_WISE_MEN`; NTD имеет отдельный `NTD_FIVE_DIMENSIONS`. Исторические сценарии TC1/Course не следует незаметно добавлять как обязательные Boolean-гейты к TC2.

Статусы:
- **SOURCE-REVIEWED** — концепция прослежена в источнике/предыдущем постраничном аудите.
- **CODE-PRESENT** — формула/детектор найдены в текущей ветке.
- **PARTIAL** — похожая часть есть, но точное правило или вся цепочка не доказаны.
- **MISSING** — самостоятельный полный детектор/профиль не найден.
- **UNRESOLVED** — в источнике недостаточно оснований для однозначной формализации; разработчик не должен угадывать.
- **SYSTEM/IMPLEMENTATION RULE** — ограничение безопасности или техническая адаптация, не правило Вильямса.

Наличие поля/индикатора не доказывает совпадение поведения. Для полного подтверждения нужны эталонные векторы, causal replay и сквозной путь до исполнения.

## 2. Реестр индикаторов

| Индикатор / понятие | Источник и назначение | Текущий код | Вердикт и необходимая граница |
|---|---|---|---|
| **Median Price** `(H+L)/2` | Основа Alligator и AO | `strategy.py: calculate_indicators` | **CODE-PRESENT**. Требуется единый golden vector на всех consumers. |
| **Alligator Jaw** SMMA(13), shift 8 | Контекст/«челюсть» | `strategy.py: smma, jaw_shifted` | **CODE-PRESENT**. SMA seed периода используется как инженерная инициализация; точная seed convention не объявляется доказанной авторской формулой без отдельного подтверждения. |
| **Alligator Teeth** SMMA(8), shift 5 | Balance Line и контроль внешнего фрактала | `strategy.py: teeth_shifted` | **CODE-PRESENT**. Используется как внешний/текущий gate в разных профилях; это не равно полной реализации самостоятельных Balance-Line trades NTD. |
| **Alligator Lips** SMMA(5), shift 3 | Быстрая линия, контекст и возможный быстрый выход | `strategy.py: lips_shifted` | **CODE-PRESENT**. Числовой порог `MIN_ALLIGATOR_SPREAD_PCT` — **SYSTEM/IMPLEMENTATION RULE**, не авторская универсальная граница «проснулся». |
| **Сон / пробуждение / еда / насыщение Alligator** | Описывает режим рынка, а не единственный сигнал входа | `alligator_awake`, порядок линий | **PARTIAL**. Есть spread threshold и бычий/медвежий порядок, но полноценная авторская state machine всех режимов не доказана. |
| **AO (Awesome Oscillator)** SMA(5, Median) − SMA(34, Median) | Momentum | `strategy.py: ao` | **CODE-PRESENT**. Нужна parity-проверка Python/другие consumers и закрытых баров. |
| **AO colour / momentum** | Цвет определяется изменением AO относительно предыдущего столбца, а не только знаком AO | `ao_green`, `ao_red`, streak | **CODE-PRESENT**. AO > 0 / < 0 — отдельное свойство; нельзя подменять им цвет/направление. |
| **AC (Accelerator/Decelerator)** AO − SMA(5, AO) | Изменение momentum | `strategy.py: ac` | **CODE-PRESENT**. Нулевая линия AC сама по себе не должна превращаться в отдельный entry trigger. |
| **AO Saucer** | NTD: отдельная AO формация относительно нуля | `extract_ntd_ao_signal_specs` | **CODE-PRESENT, отдельный NTD-профиль**. Не является синонимом WM2 Super AO. Нужны эталонные рисунки LONG/SHORT и replay. |
| **AO Zero-Line Crossing** | NTD: пересечение AO нуля | `extract_ntd_ao_signal_specs` | **CODE-PRESENT, отдельный NTD-профиль**. Не включать молча в TC2. |
| **AO Twin Peaks** | NTD: пара экстремумов AO по нужную сторону нуля | `extract_ntd_ao_signal_specs` | **PARTIAL**. Код ищет локальные пики/впадины и сравнивает значения; нужны фигуры с несколькими пиками, пропуском нуля и пограничными барами для доказательства соответствия. |
| **Super AO / WM2** | TC2: третий последовательный столбец AO того же направления; может быть первым сработавшим Мудрецом | `_latest_super_ao`, `extract_long/short_signal_specs` | **CODE-PRESENT**. Триггер за соответствующим ценовым баром; фрактал не является обязательным условием. Проверять end-to-end, а не только детектор. |
| **Fractal up/down** | TC1/NTD/TC2/Course: ценовая формация; образование, подтверждение и прорыв — разные моменты | `strategy.py`, `williams_signals.py`, `wave_engine.py` | **КОД ИСПРАВЛЕН В ЭТОЙ ВЕТКЕ**: явные confirmation indices, равные смежные экстремумы как группа, подтверждение после правых баров. Триггер не может использоваться до подтверждения. Нужны golden vectors для раздельных равных экстремумов, двойного фрактала и всех фигур Course. |
| **Fractal trigger / WM3** | TC2: условный вход за подтверждённым фракталом; Teeth/Balance Line — отдельная проверка | `_latest_confirmed_fractal`, `extract_ntd_fractal_signal_specs` | **PARTIAL→IMPROVED**. Consumers теперь используют фактическое соответствие confirmation→pivot и защитный экстремум всей группы равных баров. Проверка Teeth и trigger должна повторяться непосредственно перед биржевым submit. |
| **Fractal Start / Signal / Stop** | TC1/Course: различные роли фракталов в планировании и защите | В коде есть подтверждённые фракталы и campaign stop, но полная классификация всех трёх ролей не найдена | **PARTIAL**. Нельзя считать их полностью реализованными только по наличию WM3. |
| **Divergent / Reversal Bar, WM1** | TC2: ранний разворотный бар; экстремум и закрытие в нужной половине; stop за сигнальным экстремумом | `bullish_reversal_bar`, `bearish_reversal_bar`, `_latest_reversal` | **PARTIAL**. Геометрическая часть есть. Обязательная линейная регрессия/численный score ангуляции — **IMPLEMENTATION APPROXIMATION**, не подтверждённая авторская формула. Строгий H1/H4 trend gate может подавлять ранний WM1. |
| **Angulation** | WM1: цена отходит от Alligator/Jaw под более крутым углом | `_angulation` | **UNRESOLVED/PARTIAL**. Код считает растущее расстояние/наклон за окно. Источник не даёт подтверждённой универсальной регрессионной формулы или порога; score нельзя выдавать за каноническую метрику. |
| **Three price bars + AO** | TC2 continuation/add-on: последовательность ценовых баров и AO того же направления | `_latest_three_price_bar_signal` | **CODE-PRESENT как ADD_ON**. Нужны точные source vectors и проверка, что это добавление к уже открытой кампании, а не случайный flat entry. |
| **Profitunity MFI / Market Facilitation Index** `(H-L)/Volume` | TC1/Course: сравнение изменения диапазона на единицу активности; оригинальный смысл связан с tick volume | `mfi_proxy`, `mfi_proxy_source`, `mfi_proxy_valid` | **PROXY, НЕ КАНОНИЧЕСКИЙ MFI**. Binance trade count/base volume не следует объявлять равным историческому tick volume. Источник прокси теперь явно маркируется. |
| **Четыре MFI/volume окна** Green / Fading / Fake / Squat | TC1/Course: сочетания направления изменения объёма и MFI | `_profitunity_window`, `profitunity_window`, `squatting_bar` | **КОД ИСПРАВЛЕН В ЭТОЙ ВЕТКЕ**: отсутствие текущего/предыдущего значения даёт `UNKNOWN`, а не ложный Fading. `STABLE` — диагностическое расширение, не пятый канонический Profitunity window. |
| **Squat Bar** | Рост активности при снижении MFI; один из признаков возможного противоборства/истощения | `squatting_bar` от MFI proxy | **PARTIAL**. Из-за источника объёма это proxy; канонический squat нельзя заявлять до подтверждения volume semantics. |
| **Zones: Green / Red / Gray** | NTD: совпадение/расхождение направления изменения AO и AC | `williams_zone`, `extract_ntd_zone_add_on_specs` | **CODE-PRESENT**. В NTD-профиле это продолжение/добавление, а не standalone entry по одному цвету. Порог «не добавлять после пяти зонных баров» и полная авторская последовательность требуют отдельной проверки. |
| **Balance Line trades** | NTD: отдельные торговые правила по линии баланса/Teeth и текущей зоне | Teeth используется как fractal gate; отдельный полный набор Balance-Line signal specs не найден | **PARTIAL/MISSING**. Нельзя объявлять пять измерений NTD полностью реализованными. |
| **Gator Oscillator** | Производное визуальное отображение разницы линий Alligator; не подтверждено как обязательный самостоятельный вход в выбранном TC2 профиле | Отдельные `gator_upper/lower` колонки не найдены в проверенных indicator path | **NOT A TC2 ENTRY GATE**. Не добавлять как обязательный фильтр без source requirement; можно рассматривать только как отдельную диагностическую визуализацию. |
| **5/34/5 MACD / momentum view** | Course/NTD: родственная AO/AC-представлению визуализация momentum | AO и AC вычисляются; отдельный независимый 5/34/5 output-contract не найден | **PARTIAL**. Требуется определить по конкретному рисунку/платформе, какие линии/гистограммы выводятся; не считать одно название доказательством идентичности. |
| **Zero Point** | Ранняя TC1/Profitunity wave/exhaustion framework | `wave_engine.py` и Point-Zero diagnostics | **PARTIAL/HEURISTIC**. Не должен становиться обязательным TC2 gate по умолчанию; `REQUIRE_POINT_ZERO_FOR_WM1=false` сохранён. |
| **Five Magic Bullets** | Дивергенция, целевая волновая зона, терминальный фрактал, Squat в терминальной области, изменение momentum; поздний Profitunity material может менять Bullet #2 | `magic_bullets.py`, `wave_engine.py` | **PARTIAL**. Пять полей/score не доказывают авторскую wave count и правильность всех пять признаков. Book-era и modern replacement должны быть version-tagged. |
| **Elliott wave count / W1–W5 / ABC** | TC1 и TC2 связывают фракталы, Alligator и wave context; полный Elliott method не воспроизводится одной формулой | `wave_engine.py` adaptive structural estimate | **HEURISTIC**, честно помечен как structural estimate. Не должен менять protective stop исходного Мудреца или выдавать вероятностный count за доказанную авторскую разметку. |
| **Wave-5 target zone** | TC1/NTD/Profitunity: волновая целевая область | `magic_bullets.py: target_zone`, wave snapshot | **PARTIAL**. Фиксированный диапазон коэффициентов — модельная настройка; надо подтвердить против точных исходных рисунков и волнового счёта. |
| **Two-Bar Reversal / Air Bag** | TC1/Course: историческая защитная/разворотная конструкция и старший временной контекст | MTF context / safety exits присутствуют, но полного source-matched Air Bag detector не найдено | **PARTIAL/MISSING**. Safety gate не равно авторскому Air Bag. |
| **Thumb Trade** | Course: отдельная формация «пальцев», внутренние бары не считаются, важны экстремумы и типы баров | Полный detector не найден | **MISSING** как самостоятельный исполняемый профиль. |
| **Hump Formation** | Course: Hump Start/Signal и отношение противоположных фракталов | Полный detector не найден | **MISSING/PARTIAL**; не подменяется обычным fractal breakout. |
| **T-W-R / Profitunity Partner / Planned Trade** | Course: последовательный checklist контекста, momentum, структуры, триггера и плана | Есть MTF/campaign/risk components, но полный обязательный workflow не найден | **PARTIAL**. Диагностическая запись решения не доказывает полный старый учебный процесс. |
| **Exits: 3/5-bar trailing** | TC2: динамический стоп за экстремумом последних 3 или 5 ценовых баров | Campaign trail policy и структурные/ATR правила | **PARTIAL/HYBRID**. В этой ветке требуется подтвердить точную production exit path; 3-bar и 5-bar должны быть отдельными профилями, а не скрытой смесью. |
| **Teeth-close / Lips-fast exit** | NTD: close относительно Teeth; Lips возможна как более быстрая защита в быстром рынке | Есть Teeth/ATR/fractal logic в campaign/legacy layers | **PARTIAL**. Отделить книжные exit profiles от hard safety exit. |
| **Opposite signal / exhaustion exit** | TC2/NTD: альтернативные способы закрытия; не каждый обратный признак должен закрывать позицию автоматически | Campaign exit, wave exhaustion and hard guards | **PARTIAL**. Должна быть явная причина выхода и provenance. |
| **Reverse pyramid 1:5:4:3:2** | TC2: исторический пример распределения размера, не универсальный риск-процент | Campaign risk weights | **ENGINEERING ADAPTATION**. Нельзя обещать точные исторические количества; фактический риск пересчитывается по stop, fills, fees и exposure. |
| **Psychology / belief systems** | Во всех книгах/Course — важная часть метода и дисциплины | Diagnostics/decision traces частично объясняют решения | Не является напрямую OHLC-индикатором; не надо имитировать его как булев сигнал. |

## 3. Исправления кода, сделанные в этом проходе

1. `strategy.py`: фрактал больше не представлен только строгой одиночной пятибарной проверкой. Обрабатывается смежная группа равных экстремумов; фиксируются начало группы, якорь и индекс подтверждения; отдельный флаг ставится на фактическом баре подтверждения.
2. `williams_signals.py`: WM3/NTD потребители используют явную связь confirmation→pivot, а защитный reference охватывает противоположный экстремум всей равной группы.
3. `wave_engine.py`: structural pivots читают сохранённый confirmation index; двойной up/down fractal не превращается в два упорядоченных pivots.
4. `strategy.py`: окна Profitunity не маркируют отсутствующие данные как Fading; невалидные сравнения получают `UNKNOWN`.
5. Добавлен `test_williams_indicator_fidelity.py`: extended equal-high/equal-low confirmation, no-premature-confirmation и MFI warm-up/proxy checks.
6. Оба Python CI workflow включают новый регрессионный файл; release gate проверяет наличие новых causal metadata и MFI validity state.

## 4. Оставшиеся проблемы, которые нельзя честно объявить решёнными

- Не доказана авторская численная формула angulation: текущий score — аппроксимация.
- Не доказано, что trade count/base volume эквивалентны tick volume; MFI остаётся proxy.
- Не завершён полный самостоятельный Balance Line trade engine NTD.
- Нет подтверждённого полного исполняемого набора Thumb/Hump/Fractal Start-Signal-Stop/старого Profitunity Partner.
- Five Magic Bullets/Zero Point/Elliott target остаются частично эвристическими и должны иметь source/profile version.
- Не доказана идентичность стратегического поведения Python/Android.
- Не доказана end-to-end эквивалентность выходов выбранному книжному профилю.
- Для этой серии изменений пока требуется CI на точном HEAD и replay golden vectors. До получения результатов не заявлять PASS.

## 5. Правило для дальнейших исправлений

Каждый indicator/signal должен иметь: `source_profile`, `source_rule_id`, `formation_index`, `confirmation_index`, `trigger_index/time`, `trigger_price`, `protective_reference`, `invalidation_reason` и `decision_reason`. Отсутствие обязательных полей блокирует увеличение экспозиции.

**Финальный статус:** `PARTIALLY VERIFIED — INDICATOR INVENTORY EXPANDED; DO NOT CLAIM FULL BOOK FIDELITY OR LIVE READINESS`.
