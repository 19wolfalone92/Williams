package com.williamsbot

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** User-facing progress must distinguish working H1 data from archive research. */
internal fun russianWaveArchiveProgress(replay:JSONObject?,expectedPairs:Int=10):String {
    if(replay==null) return ""
    val items=replay.optJSONArray("items")
    var processed=0L
    var caughtUp=0
    for(i in 0 until (items?.length()?:0)) {
        val row=items?.optJSONObject(i)?:continue
        processed+=row.optLong("processed_h1_closes",0L).coerceAtLeast(0L)
        if(row.optString("status")=="CAUGHT_UP_AVAILABLE_H1") caughtUp++
    }
    val state=when(replay.optString("worker_state")) {
        "WAITING_FULL_ARCHIVE" -> "ожидание полной загрузки контекстных масштабов"
        "WAITING_DEVICE_STORAGE" -> "недостаточно свободного места"
        "WAITING_RUNTIME_PRIORITY" -> "приоритет у сопровождения позиций и сверки"
        "CAUGHT_UP_AVAILABLE_H1" -> "ожидание новых закрытых H1-свечей"
        "ANALYSIS_REPLAY_ERROR" -> "ошибка прохода; причина записана в диагностике"
        "SOURCE_CHANGED_RETRY" -> "исходные свечи изменились; расчёт будет повторён"
        "STOPPED" -> "проход остановлен"
        "IDLE" -> "проход готовится"
        else -> "фоновый проход сохранённой истории"
    }
    return "Волны: обработано $processed часовых срезов; доступный архив пройден для "+
        "$caughtUp из $expectedPairs пар. $state. Разметка остаётся гипотезой."
}

internal fun russianDailyEntryHaltText(halt:JSONObject?):String {
    if(halt==null) return ""
    if(!halt.optBoolean("available",false))
        return "Срок дневного запрета не подтверждён; ожидается проверка времени и сохранённого состояния."
    if(halt.optBoolean("allow_new_entries",false))
        return "Дневного запрета нет. Граница торговых суток: 00:00 UTC."
    val until=halt.optLong("until_ms",0L)
    if(until<=0L) return "Дневные входы ограничены; срок сброса не подтверждён."
    val format=SimpleDateFormat("dd.MM HH:mm 'UTC'",Locale.US).apply {
        timeZone=TimeZone.getTimeZone("UTC")
    }
    return "Дневной запрет до "+format.format(Date(until))+
        ". Сопровождение позиций продолжается."
}
