# WILLIAMS — FINAL BOOK ALIGNMENT AUDIT

Дата: 2026-10-08

## Статус

Стратегическая истина Core проверена по трем загруженным книгам:

- «Торговый Хаос»;
- «Новые измерения в биржевой торговле»;
- «Торговый Хаос 2».

### Матрица

| Авторская механика | Final Core | Комментарий |
|---|---|---|
| Alligator 13/8/5 + смещения | PASS | Канонические параметры |
| Sleeping/awakening context | PASS | Используется как режим/контекст |
| WM1 reversal bar | PASS | Extreme + close in half |
| WM1 outside Alligator mouth | PASS | Жесткое условие; расстояние — quality |
| WM1 angulation | PASS* | Геометрия side-specific; числовая формула — инженерная аппроксимация визуального правила |
| WM1 BUY STOP | PASS | High + tick |
| WM1 protective stop | PASS | Low - tick |
| WM1 expected AO state | PASS* | Красный AO для long reversal используется как основной momentum-state |
| WM2 third green AO | PASS | Событие 2→3 |
| WM2 can be first | PASS | Нет обязательного fractal prerequisite |
| WM3 5-bar fractal | PASS | Basic fractal |
| WM3 Teeth validation | PASS | Проверка на activation/trigger |
| Newer fractal supersedes older | PASS | Старый pending не возвращается |
| First valid Wise Man starts campaign | PASS | Chronology-first |
| Later Wise Men are adds | PASS | Campaign semantics |
| Structural 3/5-bar trail | PASS | Lowest recent low / highest recent high |
| Profitunity Zone | PASS | AO+AC agreement |
| Gray Zone no Zone-based add | PASS | Diagnostic/add-on rule |
| 5 same-color Zone-bar protection | PASS | Special profit trail |
| Opposite reversal / stop-and-reverse | ADAPTER | Возможность зависит от Spot/Futures execution |
| Big Thumb | SEPARATE ENGINE | Range/transition branch, не core WM gate |
| Balance Line | SEPARATE ENGINE | Отдельная fifth-dimension branch |
| Zero Point / five bullets | CONTEXT ENGINE | Exhaustion/reversal context |
| Wave count | ADVISORY | Не источник Strategy Truth |
| MFI | PROXY | Binance data semantics differ from historical tick volume |

* В книге визуальная геометрия не задается численной формулой. Код должен сохранять это различие.

## Главные запрещенные ошибки

1. Не требовать одновременно WM1 + WM2 + WM3.
2. Не требовать fractal перед WM2.
3. Не блокировать WM1 только потому, что AO еще не стал bullish.
4. Не использовать candle-body color как замену Profitunity Zone.
5. Не проверять fractal Teeth только в момент formation.
6. Не расширять stop после входа.
7. Не подменять structural campaign exit фиксированным процентным TP.
8. Не позволять AI/quant/wave создать второй источник Strategy Truth.
9. Не создавать сигнал по M5 задним числом, если его не было на закрытом M15.
10. Не возвращаться к старому fractal после появления нового однонаправленного fractal.

## Инженерные адаптации

Следующие элементы являются специально добавленными для production-брокера/Binance и не выдаются за буквальные авторские правила:

- MTF 1D → H4 → H1 → M15 → M5;
- risk caps;
- one active campaign for small-deposit profile;
- daily stop;
- session window;
- execution economics;
- reconciliation barrier;
- persistence;
- API safety;
- exact numeric angulation score;
- event-driven backtest;
- Spot LONG-only adapter.

## Final principle

Книга определяет, **что считать Williams-сигналом**.

Инженерия определяет, **можно ли этот сигнал безопасно и физически исполнить**.

Эти два уровня не смешиваются.
