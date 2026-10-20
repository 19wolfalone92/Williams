package com.williamsbot

/**
 * Russian text at the UI boundary. Exchange fields, HTTP codes, schema keys,
 * client order IDs and stored diagnostic originals are NEVER translated.
 */
internal fun russianStatus(value: String?): String {
    val raw=value?.trim().orEmpty()
    return when(raw.uppercase(java.util.Locale.US)) {
        "RUNNING" -> "Работает"
        "READY" -> "Готов"
        "READY_BLOCKED" -> "Анализ готов · входы ограничены"
        "PARTIAL_BLOCKED" -> "Частичный анализ · входы ограничены"
        "PARTIAL" -> "Частичный анализ"
        "WAITING_FOR_HISTORY" -> "Загружается история"
        "LOADING" -> "Загрузка"
        "FAILED", "FAIL" -> "Ошибка"
        "PASS", "SUCCESS", "OK" -> "Пройдено"
        "WARNING" -> "Предупреждение"
        "NOT_VERIFIED", "UNVERIFIED" -> "Не подтверждено"
        "PAUSED" -> "Приостановлен"
        "STOPPED" -> "Остановлен"
        "CONNECTED" -> "На связи"
        "DISCONNECTED", "OFFLINE" -> "Нет соединения"
        "DEGRADED" -> "Работа с ограничениями"
        "BLOCKED" -> "Заблокировано"
        "ENABLED" -> "Включено"
        "DISABLED" -> "Выключено"
        "UNKNOWN", "NULL" -> "Не установлено"
        "RECONCILE_REQUIRED" -> "Нужна сверка с Binance"
        "ENTRY_PENDING" -> "Ожидается исполнение заявки"
        "OPEN_PROTECTED" -> "Открыта и защищена стопом"
        "POSITION_UNPROTECTED" -> "Открыта без подтверждённого стопа"
        "EXIT_PENDING" -> "Ожидается закрытие"
        "TRIGGERING" -> "Срабатывание заявки уточняется"
        "NEW" -> "Заявка активна"
        "FILLED" -> "Исполнено"
        "PARTIALLY_FILLED" -> "Исполнено частично"
        "CANCELED", "CANCELLED" -> "Отменено"
        "NO_OPEN_POSITIONS" -> "Открытых позиций нет"
        "HISTORY_UNVERIFIED" -> "История не подтверждена"
        "READY_FLAT" -> "Готов · открытых позиций нет"
        "UNINITIALIZED", "NOT_RUN", "NEVER_RUN", "IDLE" -> "Ещё не запущено"
        "SEARCH_PAUSED" -> "Поиск приостановлен"
        "WAITING_RETRY" -> "Ожидается повторная попытка"
        "WAITING_FOR_MARKET_DATA" -> "Ожидаются свежие рыночные данные"
        "BLOCKED_ACCOUNT" -> "Требуется проверка счёта"
        "BLOCKED_FUTURES_RECONCILIATION", "BLOCKED_RECONCILE" -> "Входы ограничены до сверки Binance"
        "BLOCKED_KILL", "KILL_SWITCH_LATCHED", "BLOCKED_LOCK" -> "Действует защитная блокировка"
        "BLOCKED_RUNTIME_RECOVERY" -> "Восстанавливается состояние движка"
        "SAFE_MONITORING_BLOCKED" -> "Наблюдение работает · новые входы ограничены"
        "H1_READY_CONTEXT_LOADING" -> "H1 готов · контекст загружается"
        "H1_READY_CONTEXT_PARTIAL", "PARTIAL_H1_SCANNING_AVAILABLE" -> "H1 доступен · контекст неполный"
        "POSITION_OPEN" -> "Позиция открыта"
        "CLOSED_OR_STOPPED", "CLOSED", "EXIT_CONFIRMED" -> "Позиция закрыта"
        "PROTECTED" -> "Защита подтверждена при сверке"
        "NOT_SUBMITTED", "NOT_YET_VERIFIED" -> "Защита ещё не подтверждена"
        "SUBMISSION_UNKNOWN", "RESULT_REQUIRES_RECONCILIATION" -> "Результат отправки требует сверки"
        "UNRESOLVED_EXTRA_ORDER" -> "Обнаружена неразрешённая дополнительная заявка"
        "TRIGGERED" -> "Триггер сработал · исполнение уточняется"
        "EXPIRED", "EXPIRED_IN_MATCH" -> "Срок заявки истёк"
        "REJECTED" -> "Отклонено"
        "PROVISIONAL" -> "Предварительная гипотеза"
        "UNRESOLVED" -> "Не определено"
        "ALTERNATIVE", "ALTERNATIVES" -> "Альтернативная разметка"
        "SLEEPING" -> "Сон Аллигатора"
        "AWAKENING" -> "Пробуждение Аллигатора"
        "EATING" -> "Развитие тренда"
        "SATIATED" -> "Затухание движения"
        "TREND" -> "Тренд"
        "CORRECTION" -> "Коррекция"
        "RANGE", "CHOP" -> "Боковое движение"
        "NO_VALID_FORMATION" -> "Действующей формации нет"
        "FORMATION_OBSERVED" -> "Формация обнаружена"
        "ANALYSIS_FAILED" -> "Ошибка анализа"
        "LIVE" -> "Исполнение"
        "OBSERVE", "OBSERVATION_ONLY" -> "Наблюдение"
        else -> raw
    }
}

internal fun russianExecutionBlocker(value: String?): String {
    val code=value?.trim().orEmpty()
    return when {
        code.isEmpty() -> ""
        code=="TC2_PROTECTIVE_STOP_ONLY" ->
            "TC2: обычный выход разрешён только через биржевой защитный SL."
        code=="EXIT_STRATEGY_OWNER_UNVERIFIED" ->
            "Владелец стратегии не подтверждён; автоматическое закрытие запрещено."
        russianBlockerPrefixes.entries.firstOrNull { code.startsWith(it.key,ignoreCase=true) }!=null ->
            russianBlockerPrefixes.entries.first { code.startsWith(it.key,ignoreCase=true) }.value
        code.startsWith("FUTURES_DAILY_BASELINE_UNKNOWN") ->
            "Не удалось подтвердить начальный капитал на текущие сутки UTC. " +
            "Серия убытков могла обнулиться, но это другая проверка. " +
            "Новые входы безопасно заблокированы до проверки Binance."
        code.startsWith("FUTURES_DAILY_INCOME_UNKNOWN") ->
            "Не подтверждена полная история доходов и комиссий Binance за текущие сутки UTC."
        code.startsWith("FUTURES_DAILY_LOSS_LIMIT") ->
            "Достигнут установленный суточный лимит убытка 3%. Повторный запуск его не сбрасывает."
        code.startsWith("FUTURES_LOSS_STREAK_UTC_CLOCK_UNVERIFIED") ->
            "Время Binance не подтверждено; сброс серии убытков требует сверки часов."
        code.contains("exchange_confirmed_pending_entry_or_fill_not_yet_resolved") ->
            "На Binance остаётся ожидающая заявка; её исполнение или отмена ещё не подтверждены."
        code.contains("exchange_reconciliation_in_progress") ->
            "Выполняется сверка заявок и позиций Binance."
        code.contains("recvWindow",ignoreCase=true) ||
            code.contains("Timestamp for this request",ignoreCase=true) ->
            "Binance отклонил запрос из-за рассинхронизации времени (код −1021)."
        code=="paused" || code=="PAUSED" -> "Новые входы приостановлены."
        code=="fsm_not_execution_allowed" -> "Текущее состояние исполнения не разрешает новую заявку."
        code=="futures_daily_risk_baseline_unverified" ->
            "Не подтверждена суточная база риска Binance."
        else -> russianStatus(code)
    }
}

/** User-visible UTC status is independent of all account-level safety gates. */
internal fun russianLossResetExplanation(
    consecutiveLosses: Int, utcClockReady: Boolean,
    baselineReady: Boolean, pendingEntries: Int
): String = when {
    !utcClockReady -> "Счётчик убытков не подтверждён: ожидается время Binance (UTC)."
    consecutiveLosses>0 -> "В текущих сутках UTC подряд убыточных сделок: $consecutiveLosses."
    !baselineReady && pendingEntries>0 ->
        "Серия убытков обнулена в 00:00 UTC. Но суточная база риска не восстановлена, " +
        "а на Binance ещё есть ожидающая заявка ($pendingEntries). Это независимые блокировки."
    !baselineReady ->
        "Серия убытков обнулена в 00:00 UTC. Суточная база риска ещё не подтверждена."
    pendingEntries>0 ->
        "Серия убытков обнулена. Ожидаются подтверждения по активным заявкам Binance ($pendingEntries)."
    else -> "Серия убытков обнулена; ограничений по количеству подряд убыточных сделок нет."
}

private val russianBlockerPrefixes=linkedMapOf(
    "ENTRY_PASSPORT_AUDIT_UNAVAILABLE" to "Не удалось сохранить паспорт заявки. Новая заявка не отправлялась; сопровождение позиций продолжается.",
    "H1_FRACTAL_TEETH_INVALID" to "Фрактал H1 больше не проходит проверку относительно Зубов Аллигатора.",
    "H1_BOOK_TRIGGER_ALREADY" to "Уровень входа H1 уже пройден. Требуется действующая формация с непробитым уровнем.",
    "H1_BOOK_BREAKOUT_ALREADY" to "Пробой H1 уже произошёл до отправки заявки.",
    "H1_STRUCTURAL_TRIGGER_ALREADY" to "Структурный уровень H1 уже пройден.",
    "H1_PRIOR_ATTEMPT_FILL_OR_CANCELLATION_UNVERIFIED" to "Результат предыдущей попытки входа не подтверждён. Нужна сверка исполнения или отмены.",
    "H1_PARENT_ALREADY_FILLED" to "Этот сигнал H1 уже использован для первоначального входа.",
    "FORMATION_ALREADY_ADMITTED" to "Заявка по этой формации уже была допущена. Повторная отправка запрещена.",
    "Formation client id already admitted" to "Заявка с этим идентификатором уже существует. Повторная отправка запрещена.",
    "H1_SIGNAL_EXPIRED" to "Срок действия сигнала H1 истёк.",
    "WILLIAMS_SIGNAL_EXPIRED" to "Срок действия торгового сигнала истёк.",
    "H1_BOOK_SIGNAL_NO_LONGER_VALID" to "Формация H1 больше не действительна.",
    "H1_BOOK_PROOF_INVALID" to "Доказательство формации H1 не прошло повторную проверку.",
    "H1_BOOK_PENDING_REVALIDATION_UNAVAILABLE" to "Не удалось повторно проверить ожидающую заявку H1.",
    "H1_BOOK_PENDING_CANCEL_UNRESOLVED" to "Отмена недействительной заявки H1 ещё не подтверждена Binance.",
    "FRACTAL_REANCHOR_CANCEL_UNRESOLVED" to "Перенос входа на новый фрактал ждёт подтверждения отмены прежней заявки.",
    "FRACTAL_REANCHOR_TARGET_NOT_CURRENTLY_VALID" to "Новый фрактал пока не подтверждён как действующий уровень входа.",
    "NEWER_CONFIRMED_TC2_FRACTAL" to "Обнаружен новый подтверждённый фрактал TC2.",
    "WILLIAMS_SOURCE_OBSERVATION_ONLY" to "Эта стратегия работает в режиме наблюдения и не создаёт новые позиции.",
    "WILLIAMS_NO_BOOK_ELIGIBLE_H1_SIGNAL" to "Действующего книжного сигнала H1 для входа нет.",
    "NO_VALID_INITIAL_FORMATION" to "Действующая формация для первого входа не обнаружена.",
    "OPPOSING_ENTRIES_BEFORE_CONTEXT" to "Обнаружены встречные формации; требуется проверка контекста.",
    "CLOSED_H1_HISTORY_NOT_PROVEN" to "Полнота закрытых свечей H1 не подтверждена.",
    "H1_SNAPSHOT_STALE" to "Снимок H1 устарел; ожидается обновление закрытых свечей.",
    "H1_HISTORY_GAP" to "В истории H1 есть пропуски.",
    "PORTFOLIO_RISK_EXHAUSTED" to "Общий допустимый риск уже зарезервирован действующими кампаниями и заявками.",
    "ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP" to "Фактический риск позиций превышает установленный предел.",
    "INVALID_STRUCTURAL_RISK_DISTANCE" to "Не удалось подтвердить корректное расстояние от входа до структурного стопа.",
    "INVALID_TRIGGER_OR_STOP" to "Уровень входа или защитного стопа некорректен.",
    "FUTURES_ENTRY_SPREAD_BLOCKED" to "Спред превышает разрешённое значение для нового входа.",
    "FUTURES_QUANTITY_FILTER_BLOCKED" to "Объём заявки не соответствует ограничениям контракта Binance.",
    "ENTRY_TIME_POLICY_BLOCKED" to "Текущее время находится вне настроенного окна новых входов.",
    "OUTSIDE_CONFIGURED_ENTRY_TIME" to "Новые входы вне настроенного торгового окна приостановлены.",
    "RUNTIME_OR_CAMPAIGN_NOT_READY" to "Движок или кампания пока не готовы к новой заявке.",
    "ENTRY_GATE_BLOCKED" to "Допуск новой заявки заблокирован; требуется проверка причины в диагностике.",
    "REQUIRED_ENTRY_EVIDENCE_UNAVAILABLE" to "Не получены обязательные доказательства для входа.",
    "H1_CONTEXT_EVIDENCE_NOT_DURABLE" to "Контекст H1 не удалось сохранить; новая заявка не отправляется.",
    "CONDITIONAL_TRIGGER_IN_PROGRESS" to "Условная заявка срабатывает; необходимо сверить дочерний ордер и позицию.",
    "FUTURES_DAILY_ENTRY_HALT_STATE_INVALID" to "Сохранённое состояние дневного запрета некорректно; нужна проверка.",
    "FUTURES_DAILY_ENTRY_HALT_CLOCK_UNVERIFIED" to "Время Binance не подтверждено; дневной запрет пока нельзя считать истёкшим.",
    "FUTURES_DAILY_ENTRY_HALT_PERSIST_FAILED" to "Не удалось сохранить дневное ограничение. Новые входы приостановлены.",
    "DAILY_LOSS_OR_EQUITY_BASELINE_BLOCKED" to "Не пройдена проверка дневного риска или исходного капитала.",
    "FUTURES_DAILY_LOSS_CHECK_FAILED" to "Проверка дневного риска завершилась ошибкой; новые входы ограничены.",
    "FUTURES_DAILY_BASELINE_PERSIST_FAILED" to "Не удалось сохранить суточную базу риска.",
    "FUTURES_ACCOUNT_UNFUNDED" to "На счёте нет подтверждённых средств для нового входа.",
    "FUTURES_ACCOUNT_REFRESH_FAILED" to "Не удалось обновить данные счёта Binance.",
    "ACCOUNT_OR_MARKET_DATA_NOT_READY" to "Данные счёта или рынка пока не готовы.",
    "futures_daily_risk_baseline_unverified" to "Суточная база риска Binance пока не подтверждена.",
    "history_not_ready" to "История для поиска сигналов ещё загружается.",
    "rest_account_not_ready" to "Данные счёта Binance ещё не подтверждены.",
    "market_data_stale" to "Рыночные данные устарели; ожидается обновление.",
    "EXCHANGE_CLOCK_STALE" to "Время Binance устарело; требуется синхронизация.",
    "RISK_BASELINE_UNKNOWN" to "Суточная база риска пока не установлена.",
    "RISK_BASELINE_UNSAFE_EXECUTION" to "Исходный капитал нельзя восстановить без подтверждения исполнений.",
    "PASS_RESERVATION_ADJUSTED" to "Проверка пройдена; резерв риска уточнён по фактической позиции."
)

internal fun russianDirection(value:String?):String = when(value?.uppercase(java.util.Locale.US)) {
    "LONG","BUY" -> "Покупка (LONG)"
    "SHORT","SELL" -> "Продажа (SHORT)"
    "UP","RISING","BULLISH" -> "вверх"
    "DOWN","FALLING","BEARISH" -> "вниз"
    "FLAT","NEUTRAL" -> "без направления"
    null,"","UNKNOWN","UNRESOLVED" -> "не определено"
    else -> value.orEmpty()
}

internal fun russianSignalFamily(value:String?):String {
    val family=value.orEmpty().removePrefix("SHORT_").removePrefix("LONG_")
    return when(family) {
        "REVERSAL","DIVERGENT_BAR","WM1" -> "Первый Мудрец · разворотный бар"
        "SUPER_AO","WM2" -> "Второй Мудрец · три столбца AO"
        "FRACTAL","WM3" -> "Третий Мудрец · фрактал"
        "AO_ZERO_LINE","AO_ZERO_CROSS" -> "Пересечение нуля AO"
        "AO_SAUCER" -> "Блюдце AO"
        "AO_TWIN_PEAKS" -> "Два пика AO"
        "AC_SIGNAL" -> "Сигнал ускорения AC"
        "ZONE_ADD_ON","ZONE" -> "Зональный сигнал"
        "BALANCE_LINE" -> "Линия баланса"
        "COURSE_HUMP" -> "Course · Горб"
        "COURSE_THUMB" -> "Course · Большой палец"
        "COURSE_SQUAT" -> "Course · Приседающий бар"
        "OPERATOR_FORCED_DIRECTION" -> "Ручная заявка оператора"
        "" -> "Семейство не записано"
        else -> family
    }
}
