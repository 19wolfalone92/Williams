# Неконечная настройка маржи: воспроизведение и исправление

Продолжение PR240, `research/master-primary-sources-20261113`,
`19wolfalone92/Williams`. База `7d1f322ec2cf291443141937ae5a6264eca6dcb3`.
Main `32e9dd64fab9e0488ea4b566347246ac44627199` не менялся.
Это ограниченное исправление Python sizer, не реализация общего50% margin cap.

В `_size_from_risk` настройка `FUTURES_MAX_MARGIN_FRACTION` сразу проходила
через `max(0.01,min(0.90,value))`. Такой clamp не проверяет конечность:
`min(0.90,NaN)` выбирает0.90. NaN/Infinity становились благоприятным лимитом;
−Infinity превращался в0.01 вместо UNKNOWN.

`MARGIN_CONFIG_BEFORE_AFTER.json` получен реальным запуском исходного sizer
из указанного Git commit и исправленного sizer с одним scripted account:
equity/available1000USDT, leverage2, trigger100, stop99.99, risk0.25%,
штатные fee/slippage allowances. Биржевых вызовов0, это не backtest.

| Настройка | До | После |
|---|---|---|
|0.25 |Маржа250USDT, quantity5 |Тот же результат |
|NaN |Маржа347.2222USDT, quantity6.9444 |Расчёт отклонён |
|Infinity |Маржа347.2222USDT, quantity6.9444 |Расчёт отклонён |
|−Infinity |Маржа10USDT вместо неизвестного лимита |Расчёт отклонён |

Теперь значение сначала разбирается и проверяется `math.isfinite`, только
затем применяется прежний clamp к **конечному** числу. Неизвестное/нечисловое
значение выдаёт `FUTURES_MARGIN_FRACTION_INVALID` до нормализации quantity.
Конечные настройки, стоимость риска, leverage, STOP и management не меняются.
Смысл25% здесь — прежняя инженерная настройка **одного входа**, не дословное
правило Вильямса и не доказательство account-wide margin accounting.

8 новых тестов проверяют6 некорректных значений и оба направления stop distance
при допустимом0.25. Целевые risk/runtime tests:71 passed. Полный локальный
Python: **2458 passed,2 skipped,4 прежних FastAPI warnings,27.32s**.
Ruff/release contract:PASS; clean-CI static0failures/11 прежних warnings.
Новый exact-head CI сохраняется вPR240 после завершения.

Актуальный evidence checkpoint остаётся698/1213 receipts,190 visual,
143 figures,147 rules,142 source/context rechecked, independent0.
Сохранены все acquisition identities и запрет training/execution authority
из evidence registry. Число исправленных numeric defect classes теперь6.

Остаются aggregate half-account ledger с pending/partial/unknown liability
deduplication, final-submit integration обоих runtimes, полная translation map,
независимые фигуры/atomic specs/expert cohort, signed Demo, физический Android
и новый заранее зафиксированный holdout. Этот leaf guard их не подменяет.
