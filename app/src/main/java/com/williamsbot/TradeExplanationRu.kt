package com.williamsbot

import java.util.Locale

/**
 * Human explanations of persisted trading evidence, deliberately kept at the
 * presentation boundary. Never rewrite exchange order IDs, stored exit codes,
 * signed fills or JSON diagnostics. Unknown close causes must stay unknown.
 */
internal fun russianTradeEntryExplanation(raw: String?, direction: String?): String {
    val source = raw?.trim().orEmpty()
    val side = when (direction?.uppercase(Locale.US)) {
        "LONG" -> "покупку (LONG)"
        "SHORT" -> "продажу (SHORT)"
        else -> "позицию с неустановленным направлением"
    }
    return when {
        source.startsWith("BOOK H1 WM3", ignoreCase = true) ->
            "Торговая система TC2, третий Мудрец (WM3). На закрытых свечах H1 " +
            "обнаружен подтверждённый фрактал за линией «Зубы» Аллигатора. " +
            "Для $side был выбран вход условной STOP-заявкой на пробой уровня сигнала H1. " +
            "Запись о подготовке сигнала не доказывает исполнение: фактические цену, объём и время " +
            "нужно сверять с исполнениями Binance. Подтверждение M15 этой политикой не требуется."
        source.startsWith("BOOK H1 WM2", ignoreCase = true) ->
            "Торговая система TC2, второй Мудрец (WM2). На H1 проверялась последовательность " +
            "трёх столбцов Awesome Oscillator (AO) и уровень сигнального бара. " +
            "Для $side предусматривалась условная STOP-заявка после проверки уровня, " +
            "контекста и риска. Наличие сигнала не равно исполнению на бирже."
        source.startsWith("BOOK H1 WM1", ignoreCase = true) ->
            "Торговая система TC2, первый Мудрец (WM1). На H1 обнаружен разворотный бар; " +
            "проверялись его положение и ангуляция относительно Аллигатора. " +
            "Для $side предусматривалась условная STOP-заявка при пробое рассчитанного уровня. " +
            "Факт исполнения определяется биржевыми сделками, а не наличием формации."
        source.isBlank() ->
            "Причина формирования входа в сохранённом журнале отсутствует. " +
            "Направление и исполнение нельзя выводить только из отсутствующего сигнала."
        source.any { it in '\u0400'..'\u04FF' } -> source
        else ->
            "Техническая причина входа сохранена в диагностическом архиве, " +
            "но не имеет проверенной русской расшифровки. Не следует считать её подтверждённым сигналом TC2."
    }
}

internal fun russianTradeExitExplanation(raw: String?, verifiedFills: Boolean): String {
    val reason = raw?.trim().orEmpty()
    val code = reason.uppercase(Locale.US)
    val proof = if (verifiedFills) "Биржевые исполнения закрытия учтены в истории. "
        else "Полнота биржевых исполнений закрытия не подтверждена. "
    return when {
        code == "VERIFIED_PROTECTIVE_STOP_FILLED" ->
            "Защитный STOP исполнен на Binance. Точное совпадение идентификатора " +
                "стратегического Algo STOP, дочернего биржевого ордера и подписанных " +
                "исполнений подтверждено в сохранённых доказательствах. " + proof
        code == "EXCHANGE_RECONCILED_CLOSE" ->
            proof + "Система обнаружила и сверила закрытие с Binance, но не установила " +
                "его непосредственную причину. По одному факту сверки нельзя утверждать, " +
                "что сработал защитный SL, сигнал TC2 или ручная команда. " +
                "Точный механизм закрытия остаётся не подтверждён."
        code == "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED" ->
            "Выход инициирован встречным сигналом Мудреца TC2: цена достигла " +
                "проверяемого уровня противоположного сигнала на H1. " +
                "Такой сигнал служит основанием книжной логики выхода, " +
                "но сам по себе не доказывает разворот старшего тренда. " + proof
        code == "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION" ->
            "Выход инициирован встречным сигналом TC2 на H1; при этом старший " +
                "контекст рассматривался как возможная коррекция. " +
                "Гипотеза о коррекции или развороте не является установленным фактом. " + proof
        code.startsWith("MANUAL") || code.contains("USER_REQUEST") ||
            code.contains("OPERATOR_REQUEST") ->
            "В журнале зарегистрирована команда ручного закрытия позиции. " +
                "Это не сигнал автоматической стратегии. " + proof
        code == "COURSE_STAGE_TRIM" ->
            "В журнале отмечено частичное сокращение позиции по правилам Course. " +
                "Это не доказательство полного закрытия всей позиции. " + proof
        code == "UNPROTECTED_POSITION_GUARD" ->
            "Сработала аварийная логика при отсутствии подтверждённой защиты позиции. " +
                "Причина требует проверки по биржевым ордерам и журналу безопасности. " + proof
        code == "LIQUIDATION_GUARD" ->
            "Была задействована защитная логика риска ликвидации. " +
                "Это не означает, что Binance действительно ликвидировал позицию. " + proof
        code.contains("EXACT_STOP_CHILD_AND_COMPLETE_USER_TRADES") ||
            reason.contains("Стоп-лосс исполнен на Binance", ignoreCase = true) ->
            "Исполнение защитного STOP подтверждено по конкретной биржевой STOP-заявке, " +
                "её дочернему ордеру и исполненным сделкам. " + proof
        reason.isBlank() ->
            proof + "Причина выхода в сохранённых данных отсутствует; " +
                "нельзя приписывать его SL или торговой системе."
        reason.any { it in '\u0400'..'\u04FF' } ->
            reason + " " + proof
        else ->
            proof + "Техническая причина выхода не получила однозначной расшифровки. " +
                "Исходный код сохранён в диагностическом архиве; причина не подменяется предположением."
    }
}

/** Read-only explanation of H1 raw→eligible rule filtering. */
internal fun russianH1EntryFunnelReason(code: String): String = when {
    code == "QUALIFIED_H1_SIGNAL_NOT_ORDER_AUTHORITY" ->
        "H1-сигнал прошёл проверку структуры, но заявка требует отдельной сверки Binance и риска"
    code == "H1_SIGNAL_TIME_NOT_ELIGIBLE" ->
        "срок действия сигнала истёк либо свеча ещё не допускает вход"
    code.startsWith("PARENT_INVALIDATED:") ->
        "исходный H1-сигнал утратил силу: " +
            code.removePrefix("PARENT_INVALIDATED:").take(160)
    code == "H1_OPPOSITION_OR_PARENT_LIFECYCLE_REJECTED" ->
        "встречный сигнал или проверка жизненного цикла H1 отклонили формацию"
    code == "ADDITION_REQUIRES_OWNED_CAMPAIGN" ->
        "сигнал добавления, а не самостоятельный первоначальный вход"
    code == "OBSERVATION_ONLY_DIFFERENT_BOOK_PROFILE" ->
        "обнаружен исследовательским профилем NTD/Course, которому запрещены торговые заявки"
    code == "PARENT_INVALIDATION_CHECK_UNVERIFIED" ->
        "проверка отмены сигнала недоступна; вход запрещён"
    else -> "правило допуска: " + code.take(120)
}

internal fun russianTradeClassification(code: String?, feeKnown: Boolean = true): String {
    val normalized = code?.trim()?.lowercase(Locale.US).orEmpty()
    return when (normalized) {
        "exchange_reconciled" ->
            if (feeKnown) "Исполнения и учтённые комиссии сверены с Binance. " +
                "Это статус достоверности данных, а не причина выхода."
            else "Закрытие сверено с Binance, но информация о комиссиях неполная."
        "fees_incomplete" ->
            "Исполнения обнаружены, но часть комиссий не восстановлена. " +
                "Чистый финансовый результат пока нельзя считать окончательным."
        "manual_exit" ->
            "В журнале отмечено ручное закрытие; причину следует проверять по команде оператора."
        "technical_or_recovery" ->
            "Закрытие обнаружено при технической сверке. Источник команды закрытия не установлен."
        "signal_valid_or_normal_variance" ->
            "В журнале сохранён результат сделки, но это не независимая проверка правильности сигнала."
        "loss_during_complex_correction" ->
            "Убыток пришёлся на область предполагаемой сложной коррекции. Разметка не является доказанной."
        "" -> "Итоговая классификация сделки в журнале отсутствует."
        else -> "Автоматическая классификация не расшифрована; исходное значение сохранено в диагностике."
    }
}

internal fun russianTradeAuditEvidence(source: String?, fillCount: Int): String =
    when (source?.uppercase(Locale.US)) {
        "BINANCE_USDM_USER_TRADES" ->
            "Источник: история исполнений Binance USDⓈ-M Futures. " +
                "Учтено биржевых исполнений: " + fillCount.coerceAtLeast(0) + "."
        "OPERATOR_APPROVED_STOP_ONLY" ->
            "Источник: отдельно подтверждённое оператором сопровождение позиции. " +
                "Исходный вход и его комиссия могут быть неизвестны."
        "FUTURES_CAMPAIGN_SNAPSHOT" ->
            "Источник: сохранённый снимок торговой кампании. " +
                "Он не заменяет подтверждение исполнений и защитного STOP на Binance."
        else -> "Источник данных не указан или не подтверждён."
    }

internal fun russianTradeManagementExplanation(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return "Причина действия не записана."
    val interpreted = russianExecutionBlocker(text)
    if (interpreted != text) return interpreted
    if (text.any { it in '\u0400'..'\u04FF' }) return text
    return when {
        text.contains("STOP", ignoreCase = true) ->
            "Событие контроля защитного STOP; фактическое состояние определяется сверкой с Binance."
        text.contains("RECONCIL", ignoreCase = true) ->
            "Техническая сверка состояния с биржей; сама по себе не означает исполнения или закрытия."
        else ->
            "Техническое событие записано в диагностике. Достоверная причина не установлена; подробности доступны в диагностическом архиве."
    }
}
