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
        else -> raw
    }
}

internal fun russianExecutionBlocker(value: String?): String {
    val code=value?.trim().orEmpty()
    return when {
        code.isEmpty() -> ""
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
        "Серия убытков обнулена в 00:00 UTC. Но база дневного риска не восстановлена, " +
        "а на Binance ещё есть ожидающая заявка ($pendingEntries). Это независимые блокировки."
    !baselineReady ->
        "Серия убытков обнулена в 00:00 UTC. База дневного риска ещё не подтверждена."
    pendingEntries>0 ->
        "Серия убытков обнулена. Ожидаются подтверждения по активным заявкам Binance ($pendingEntries)."
    else -> "Серия убытков обнулена; ограничений по количеству подряд убыточных сделок нет."
}
