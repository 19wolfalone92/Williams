package com.williamsbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

@Composable
internal fun WilliamsEntryPassportCard(passport:JSONObject) {
    var details by remember(passport.optString("client_id")) { mutableStateOf(false) }
    fun value(row:JSONObject?,key:String)=row?.opt(key)?.takeUnless { it==JSONObject.NULL }
        ?.toString()?.takeIf { it.isNotBlank() }?:"нет данных"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Паспорт последней заявки H1",style=MaterialTheme.typography.titleSmall)
            Text(when(passport.optString("state")) {
                "NO_EXECUTION_DECISION" -> "Попытка размещения заявки ещё не записана."
                "LEGACY_DECISION_UNVERIFIED" -> "Старый снимок решения: привязка к заявке не подтверждена."
                "PREPARED_NOT_CONFIRMED" -> "План сохранён. Результат отправки не записан; требуется сверка."
                "REJECTED_WITHOUT_MUTATION" -> "Заявка отклонена до биржевых изменений."
                "SUBMISSION_OUTCOME_UNKNOWN" -> "Результат отправки неизвестен. Требуется сверка Binance."
                "EXCHANGE_RECONCILED_AFTER_UNKNOWN" -> "Результат восстановлен по сверке Binance; исходный ответ не был подтверждён."
                "INTENT_JOURNALED" -> "Заявка записана в журнал. Исполнение и защита показаны отдельно."
                "ACCEPTED_UNVERIFIED" -> "Координатор принял запрос; соответствующая биржевая запись не подтверждена."
                "JOURNAL_UNAVAILABLE" -> "Журнал исполнения недоступен; результат требует сверки."
                else -> "Результат заявки требует сверки."
            },style=MaterialTheme.typography.bodySmall)
            if(passport.optString("client_id").isNotBlank()) {
                Text(russianDirection(passport.optString("direction"))+" · "+
                    russianSignalFamily(passport.optString("signal_type")),style=MaterialTheme.typography.bodySmall)
                Text("Решение: "+cockpitTime(passport.optLong("observed_at_ms"))+
                    " · бар H1: "+cockpitTime(passport.optLong("signal_bar_time_ms")),
                    style=MaterialTheme.typography.bodySmall)
                Text("Уровень входа: "+value(passport,"entry_price")+
                    " · начальный SL: "+value(passport,"protective_stop"),style=MaterialTheme.typography.bodySmall)
                val risk=passport.optDouble("risk_pct",Double.NaN)
                Text("Объём: "+value(passport,"quantity")+" · риск: "+
                    if(risk.isFinite() && risk>=0) String.format(Locale.US,"%.3f%%",risk*100) else "не подтверждён",
                    style=MaterialTheme.typography.bodySmall)
                val outcome=passport.optJSONObject("execution_outcome")
                val blocker=outcome?.optString("blocker").orEmpty()
                if(blocker.isNotBlank()) Text(russianExecutionBlocker(blocker),style=MaterialTheme.typography.bodySmall)
                val order=passport.optJSONObject("order")
                if(order!=null) Text("Заявка: "+russianStatus(order.optString("order_status"))+
                    " · кампания: "+russianStatus(order.optString("campaign_status")),
                    style=MaterialTheme.typography.bodySmall)
                val owner=passport.optJSONObject("protection_owner")
                Text(when(passport.optString("protection_observation")) {
                    "EXCHANGE_PROTECTION_OBSERVED" -> "Защитный стоп подтверждён при последней сверке: "+value(owner,"protective_stop")
                    "SHARED_CAMPAIGN_PROTECTION_OBSERVED" -> "Стоп родительской кампании подтверждён: "+value(owner,"protective_stop")
                    "CAMPAIGN_NOT_OPEN" -> "Активная открытая кампания этой заявки не подтверждена."
                    "JOURNAL_PROTECTION_STALE" -> "Подтверждение защиты устарело; ожидается новая сверка."
                    "TIME_UNVERIFIED" -> "Время Binance не подтверждено; свежесть защиты неизвестна."
                    "PROTECTION_NOT_CONFIRMED" -> "Защитный стоп кампании пока не подтверждён."
                    else -> "Связанная с заявкой защита не подтверждена."
                },style=MaterialTheme.typography.bodySmall)
                if(owner!=null) Text("Сверка защиты: "+cockpitTime(passport.optLong("exchange_observed_at_ms")),
                    style=MaterialTheme.typography.bodySmall)
                if(passport.optString("protection_scope")=="SHARED_PARENT_CAMPAIGN")
                    Text("Доливка относится к общему стопу родительской кампании.",
                        style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={details=!details}) {
                    Text(if(details) "Скрыть идентификаторы" else "Сигнал, источник и идентификаторы")
                }
                if(details) {
                    Text("Источник: "+value(passport,"strategy_profile")+" · сигнал: "+value(passport,"signal_id")+
                        "\nЗаявка: "+value(passport,"client_id")+"\nПопытка: "+value(passport,"attempt_id"),
                        style=MaterialTheme.typography.bodySmall)
                    if(owner!=null) Text("Владелец стопа: "+value(owner,"client_id")+
                        "\nСтоп-заявка: "+value(owner,"protection_client_id"),style=MaterialTheme.typography.bodySmall)
                    Text("Основание: "+russianExecutionBlocker(passport.optString("signal_reason")),
                        style=MaterialTheme.typography.bodySmall)
                    if(blocker.isNotBlank()) Text("Код причины: "+blocker,style=MaterialTheme.typography.bodySmall)
                }
            }
            Text("Паспорт отражает последнюю попытку. Допуск новой сделки проверяется отдельно.",
                style=MaterialTheme.typography.labelSmall)
        }
    }
}
