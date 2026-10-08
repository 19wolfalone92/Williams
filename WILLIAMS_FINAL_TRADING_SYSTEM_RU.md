# WILLIAMS — FINAL TRADING SYSTEM

## 0. Назначение

Этот документ является каноническим контрактом торговой системы Williams для внутридневного Binance-бота.

**Нельзя смешивать:**
1. авторскую механику Bill/Justin Gregory Williams;
2. инженерные решения бота для MTF, Binance, риска, исполнения, восстановления и тестирования.

Авторские правила являются стратегической истиной. Инженерные правила не должны менять смысл сигнала — они только определяют, когда его можно безопасно исполнить.

---

## 1. Единая архитектура

```
1D  ──> макроконтекст / Air Bag
          │
H4  ──> направление / разрешение
          │
H1  ──> структурный контекст
          │
M15 ──> WILLIAMS SIGNAL TRUTH
          │
M5  ──> trigger / fill / intrabar execution
          │
Campaign Engine
          │
Risk → Protection → Add-ons → Trail → Exhaustion → Exit → Recovery
```

**Только M15 создает Williams-вход.**

H4 и H1 не создают вход. Они отвечают на другие вопросы:

- H4: «в каком направлении разрешено преимущество?»
- H1: «это тренд, импульс или коррекция?»
- M15: «какое конкретно поведение только что изменилось?»
- M5: «сработал ли уже мой условный уровень и по какой цене произошел fill?»

Конкретная цепочка 1D/H4/H1/M15/M5 — инженерная адаптация для внутридневной системы. Она не является утверждением, что именно эти таймфреймы предписаны авторами.

---

## 2. Главный принцип входа

Williams-вход — это **последовательность состояний**, а не `A AND B AND C AND D`.

```
CONTEXT
  ↓
SETUP / BEHAVIOR CHANGE
  ↓
WISE MAN SIGNAL
  ↓
VALIDATION
  ↓
CONDITIONAL PRICE TRIGGER
  ↓
ACTUAL FILL
  ↓
CAMPAIGN
```

### Критическая инварианта

**WM1, WM2 и WM3 — не три обязательных фильтра.**

Это три разные семьи сигналов.

Первый действительно валидный сигнал становится началом кампании.

Позднее возникшие валидные сигналы той же стороны становятся добавлениями, если риск кампании позволяет.

---

# 3. Режимы рынка

## 3.1 TREND_CAMPAIGN — основной режим

Используются:

- Alligator как навигатор;
- WM1;
- WM2;
- WM3;
- Zone;
- структурный trailing;
- fractal protection;
- exhaustion watch.

Это основной режим production Core.

## 3.2 RANGE_TRANSITION — отдельная ветка

Когда Alligator запутан/спит, трендовую логику нельзя притворяться продолжившейся.

В этом режиме используются отдельные механики:

- Big Thumb;
- Balance Line;
- раннее распознавание перехода диапазон → тенденция.

Эти сигналы не должны незаметно превращаться в еще один фильтр WM1.

## 3.3 MAJOR_REVERSAL — режим контекста

Zero Point / пять «волшебных пуль» / волновое истощение используются для определения возможного окончания крупного движения.

Они не должны становиться огромным `AND`-замком перед первым Williams-сигналом.

После подтверждения контекста бот возвращается к локальной торговой механике WM1/WM2/WM3.

---

# 4. Alligator

Alligator — компас системы.

Основные линии:

- Jaw: SMMA(13), shift 8;
- Teeth: SMMA(8), shift 5;
- Lips: SMMA(5), shift 3.

### Смысл

- запутанная пасть → рынок спит/переходный;
- раскрывающаяся пасть → движение набирает структуру;
- цена далеко от пасти → импульсивное движение.

**В Core нельзя торговать так, будто запутанный Alligator автоматически превращается в сильный тренд.**

---

# 5. Первый Мудрец — WM1

## 5.1 Long

Полный сигнал:

1. рынок создаёт новый локальный lower low;
2. формируется бычий reversal bar;
3. close находится в верхней половине бара;
4. сигнал находится вне пасти Alligator;
5. чем дальше от пасти, тем выше качество;
6. присутствует растущая angulation;
7. для Long цена рассматривается по нижним краям баров;
8. AO в старом нисходящем импульсе обычно остается красным.

### Trigger

```
BUY STOP = HIGH(WM1) + 1 tick
```

### Initial protection

```
STOP = LOW(WM1) - 1 tick
```

### Правило качества

Расстояние от пасти — **качество**, а не произвольный числовой порог.

Если рынок находится едва за пастью, такой WM1 слабее.

## 5.2 Short

Полная зеркальная логика:

- higher high;
- bearish reversal;
- close в нижней половине;
- далеко выше/вне пасти;
- angulation;
- используется верхний край баров;
- SELL STOP ниже low бара;
- защитный stop выше high.

### Почему WM1 особенный

Это точка с наименьшим денежным риском, потому что рынок дает очень близкий структурный invalidation.

Это преимущество расположения сделки, а не обещание максимального win-rate.

---

# 6. Angulation

Это не обычный slope индикатора.

Алгоритм Core:

1. найти область, где цена пересекла/прошла через пасть;
2. провести reference geometry вдоль Alligator/Jaw;
3. для Long использовать нижние края ценовых баров;
4. для Short использовать верхние края;
5. проверить, что две линии расходятся;
6. проверить, что угол увеличивается.

Числовой score в коде — **детерминированная инженерная аппроксимация визуального правила книги**, а не «новая формула Билла Вильямса».

Если цена движется примерно под тем же углом, что и Alligator, reversal signal не признается полноценной WM1-конфигурацией.

---

# 7. Второй Мудрец — WM2

AO:

```
AO = SMA(5, Median Price) - SMA(34, Median Price)
```

Цвет:

- AO растет относительно предыдущего → Green;
- AO падает → Red.

## Long WM2

Событие возникает на **третьем последовательном зеленом AO**.

В программной реализации оно фиксируется именно на переходе:

```
2 green → 3 green
```

Это защищает сигнал от потери, если следующий scan произойдет уже на четвертом/пятом зеленом баре.

### Trigger

```
BUY STOP = HIGH соответствующего price bar + 1 tick
```

### Важнейшее правило

**WM2 НЕ требует предварительного WM3/фрактала.**

Если WM2 появился первым, он может быть первой сделкой кампании.

---

# 8. Третий Мудрец — WM3

Fractal — формация минимум из пяти баров.

## Long WM3

1. сформирован up fractal;
2. есть подтверждение справа;
3. уровень становится активным;
4. в момент реального запуска:
   ```
   trigger > Teeth
   ```
5. вход:
   ```
   BUY STOP = fractal high + 1 tick
   ```

### Главный принцип

**Важно, где произошло срабатывание, а не только где был сформирован фрактал.**

Поэтому Teeth повторно проверяется в момент trigger.

### Lifecycle

```
CREATED
  ↓
ACTIVE
  ↓
VALID
  ↓
TRIGGERED
```

Новый однонаправленный фрактал может заменить старый ожидающий сигнал.

Нельзя после появления нового фрактала неожиданно вернуться к старому уже вытесненному сигналу.

---

# 9. Кто первый — тот начинает кампанию

Классическая последовательность:

```
WM1 → WM2 → WM3
```

Но допустимы:

```
WM2 → WM3
WM3 → WM2
WM2 first
WM3 first
```

Каноническая логика:

- первый валидный сигнал = initial;
- последующий валидный сигнал = add-on;
- отсутствие WM1 не означает, что рынок нельзя торговать;
- WM1 не должен искусственно ждать WM2/WM3.

Если несколько сигналов имеют одну и ту же event-time отметку, допустимый tie-break:

```
WM1 > WM2 > WM3
```

Это только разрешение ничьей, а не глобальный приоритет, который переставляет сигналы во времени.

---

# 10. Что является HARD GATE

### Торговля запрещена, если:

- свеча еще не закрыта;
- нет корректной структуры данных;
- отсутствует необходимый рынок/ликвидность;
- trigger уже пересечен и условный вход потерян;
- риск кампании превышает лимит;
- execution barrier заблокировал изменение;
- есть unresolved reconciliation;
- стоп нельзя построить;
- исполнение экономически нецелесообразно.

### Это не должно ломать Williams Truth

Например, если execution economics отказала в сделке, состояние должно оставаться:

```
CORE_VALID = true
TRADE_ALLOWED = false
BLOCK_REASON = ECONOMICS
```

Иначе аналитика перестает различать плохую стратегию и невозможность исполнить хорошую.

---

# 11. Что является SOFT / QUALITY

Не превращать в жесткое AND:

- расстояние от пасти;
- quality angulation score;
- AO/AC;
- Zone;
- Wave score;
- MFI proxy;
- quantitative shadow;
- AI shadow;
- L2 features.

Они должны:

- улучшать ranking;
- изменять размер риска;
- усиливать диагностику;
- определять агрессивность добавления.

Но не должны молча уничтожать валидный WM1 только потому, что другой индикатор не успел перестроиться.

---

# 12. Campaign Engine

Кампания:

```
FLAT
 ↓
SIGNAL_DETECTED
 ↓
ENTRY_ARMING
 ↓
ENTRY_PENDING
 ↓
ENTRY_TRIGGERED
 ↓
OPEN_INITIAL
 ↓
ADD_ON_ARMING
 ↓
ADD_ON_PENDING
 ↓
POSITION_EXPANDING
 ↓
TREND_ACTIVE
 ↓
TRAILING
 ↓
EXHAUSTION_WATCH
 ↓
EXIT_SIGNALLED
 ↓
EXIT_PENDING
 ↓
CLOSED
```

Исполнение и стратегия разделены.

SignalSpec хранит:

- signal_id;
- symbol;
- side;
- family;
- role;
- timeframe;
- signal bar time;
- trigger;
- protective reference;
- invalidation;
- Alligator state;
- Teeth;
- angulation;
- AO;
- Zone;
- Wave;
- quality;
- detected time;
- execution timeframe.

---

# 13. Риск

Для небольшого депозита Core по умолчанию:

- initial risk = 0.25% equity;
- campaign cap = 0.60%;
- daily loss cap = 1.00%;
- максимум активных кампаний = 1;
- максимум двух полных stop-outs/day;
- leverage = 0;
- averaging down = OFF;
- fixed TP = OFF.

Это **инженерная политика**, не авторское число из книг.

## Реальный риск единицы

```
risk_per_unit =
    abs(trigger_price - structural_stop)
    + fees
    + slippage reserve
```

```
qty =
    allowed_risk_quote / risk_per_unit
```

Размер позиции не определяет стоп.

**Сначала структура. Потом размер.**

---

# 14. Пирамида

Авторская идея reverse pyramid сохраняется как логика распределения риска:

```
1 : 5 : 4 : 3 : 2
```

Но это не абсолютные количества монет.

Это бюджет риска между траншами.

Каждое добавление обязано:

- идти в сторону уже работающего движения;
- не усреднять убыток;
- не нарушать campaign cap;
- не ослаблять существующий stop.

---

# 15. Structural trailing

После входа основная защита структурная.

Для Long:

```
stop ≈ lowest low of recent completed 3 or 5 bars - 1 tick
```

Для Short:

```
stop ≈ highest high of recent completed 3 or 5 bars + 1 tick
```

Stop может двигаться только в сторону меньшего риска.

```
LONG:   new_stop >= old_stop
SHORT:  new_stop <= old_stop
```

Никогда не расширять stop.

---

# 16. Profitunity Zone

Zone определяется двумя измерениями:

- AO = momentum;
- AC = acceleration/deceleration.

### Цвет

```
GREEN = AO rises + AC rises
RED   = AO falls + AC falls
GRAY  = disagreement
```

### Торговый смысл

- Green Zone → long-side aggressive additions;
- Red Zone → short-side aggressive additions;
- Gray Zone → не делать Zone-based add.

Zone — не обязательный initial-entry gate.

---

# 17. Пять последовательных Zone-баров

После пятого одноцветного Zone-бара запускается специальная profit-protection логика.

Для Long:

1. после пятого Green поставить stop на 1 tick ниже low пятого бара;
2. если движение продолжается, переносить stop под каждый следующий завершенный бар;
3. продолжать независимо от цвета последующих баров.

Это отдельный ускоренный режим извлечения прибыли.

---

# 18. Exhausion Engine

Одного сигнала недостаточно для принудительного выхода.

Следить совместно за:

- AO divergence;
- AC/Zone переходом в Gray;
- SQUAT;
- target zone;
- W5 / wave exhaustion;
- потерей направленной структуры;
- противоположным reversal;
- fractal failure;
- structural stop.

Принцип:

```
ONE WARNING ≠ EXIT
MULTIPLE CONVERGING WARNINGS = EXHAUSTION WATCH
STRUCTURAL FAILURE = EXIT
```

---

# 19. Zero Point

Zero Point — отдельный режим крупного разворота.

Последовательность:

```
divergence
+ target zone
+ developing fractal
+ SQUAT
+ momentum change
→ Zero Point hypothesis
→ descend to lower timeframe
→ find executable Williams entry
```

Это не следует превращать в пять одновременных Boolean-фильтров перед каждой сделкой.

---

# 20. Big Thumb

Big Thumb — ранний механизм внутри/перед подтверждением фрактала.

Базовая установка:

### Long

Минимум три бара:

- Higher High;
- Higher Low.

### Short

Зеркально:

- Lower Low;
- Lower High.

В авторской методике дополнительно учитываются объёмные признаки; внутренние/параллельные бары не считаются полноценной установкой.

Big Thumb используется прежде всего для более раннего обнаружения перехода диапазон → тенденция.

---

# 21. Balance Line

Balance Line — отдельная механика пятого измерения.

Главная идея:

- дальше от Balance Line → путь меньшего сопротивления;
- движение к Balance Line → «в гору»;
- движение от неё → «с горы».

Для базовой схемы покупки:

- движение к Balance Line → base + 2 higher highs;
- движение от Balance Line → base + 1 higher high.

Сигнал способен сдвигаться при появлении нового соответствующего экстремума («blue light»).

Balance Line не должна быть тайным дополнительным фильтром WM1.

---

# 22. Spot vs Short

Торговая истина должна быть симметричной:

```
LONG
SHORT
```

Но обычный Binance Spot не дает обычного short-position без соответствующего продукта.

Поэтому архитектура:

```
Williams Core
   ├── Spot Adapter → LONG execution
   └── Futures/Margin Adapter → LONG + SHORT execution
```

Это ограничение execution-layer, а не стратегии Williams.

---

# 23. Что делать при Sleeping Alligator

Sleeping Alligator:

- не превращать наблюдение диапазона в тренд;
- не добавлять агрессивно только из-за одного осциллятора;
- разрешать отдельный range/transition анализ;
- ждать нормального подтверждения выхода.

WM1 при этом не должен исчезать из логики наблюдения только потому, что Alligator еще не проснулся.

---

# 24. М5

M5 **не создает новый Williams signal**.

Его функция:

- увидеть intrabar crossing;
- уточнить fill price;
- определить порядок trigger/stop;
- определить gap-through;
- реконструировать фактическое исполнение.

Это execution microscope.

Нельзя по одному М5-движению задним числом создать WM1/WM2/WM3, которого не было на закрытом M15.

---

# 25. Backtest chronology

Правильный порядок:

```
M15 close
→ create pending signal
→ next price path
→ M5 replay, если доступен
→ trigger
→ fill
→ stop/protection
→ add-ons
→ trail
→ exhaustion
→ exit
```

Запрещено использовать M5-бары, которые произошли **до fill**, чтобы утверждать, что позиция была уже защищена после fill.

---

# 26. Final decision contract

Каждое решение должно отвечать:

### Где рынок?

- D1;
- H4;
- H1;
- Alligator state;
- Wave state.

### Что произошло?

- WM1;
- WM2;
- WM3;
- Big Thumb;
- Balance Line;
- Zero Point context.

### Почему это действительно сигнал?

- structure;
- extreme;
- half-close;
- angulation;
- Teeth/fractal validation;
- AO event.

### Где вход?

```
trigger_price
```

### Где ошибка идеи?

```
protective_reference / invalidation
```

### Сколько денег мы рискуем?

```
risk_quote
```

### Разрешено ли исполнение?

```
core_valid
economics_valid
execution_valid
risk_valid
trade_allowed
```

### Почему вышли?

```
STRUCTURAL_STOP
ZONE_TRAIL
FRACTAL_TRAIL
EXHAUSTION
OPPOSITE_REVERSAL
EOD
MANUAL/KILL
```

---

# 27. Финальная аксиома системы

Не спрашивать:

> «Сколько индикаторов сейчас зеленые?»

Спрашивать:

> «Какое поведение рынка только что изменилось, где находится это изменение относительно пасти Аллигатора, каким Williams-сигналом оно выражено, какой ценой рынок должен доказать мою идею, где я признаю ошибку и как я буду наращивать/сокращать позицию после фактического движения?»

Именно это превращает набор индикаторов в **Williams Trading System**.

---

# 28. Implementation status — 2026-10-08

### Реализовано в Core

- canonical MTF contract 1D/H4/H1/M15/M5;
- WM1/WM2/WM3 non-AND semantics;
- chronological first-valid campaign start;
- WM1 half-bar + outside-mouth + side-specific angulation;
- WM2 2→3 event persistence;
- WM2-first;
- WM3-first;
- trigger-time Teeth validation;
- fractal supersession;
- structural stop invariant;
- 3/5-bar structural trailing;
- Profitunity Zone;
- five-Zone-bar profit trail;
- fixed-TP disabled in Core;
- explicit risk/economics/execution separation;
- M5 as execution microscope;
- regression suite and dedicated Core CI workflow.

### Отдельные advanced modules

Big Thumb, Balance Line и Zero Point должны оставаться отдельными режимами/engines, а не разрушать deterministic Three Wise Men Core.

---

# 29. Final verification rule

Перед production merge должны быть одновременно выполнены:

1. Core compile passes.
2. Canonical Core regression tests are green.
3. No duplicate Strategy Truth exists in legacy entry path.
4. Live scanner uses M15 as decision timeframe.
5. Campaign execution uses the signal's real structural trigger/stop.
6. Risk never widens after entry.
7. Fixed TP does not override campaign exits.
8. Zone is based on AO+AC, not candle-body colour.
9. WM2 does not depend on a prior fractal.
10. WM3 Teeth is checked at trigger.
11. Book-vs-code audit documents every engineering deviation explicitly.
12. Full repository CI failures unrelated to Core are not hidden or reclassified as Core success.
