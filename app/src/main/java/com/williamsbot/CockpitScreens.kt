package com.williamsbot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Context
import androidx.core.content.edit

/**
 * Human-readable Russian status labels. Raw exchange/diagnostic codes are still
 * retained in the exported JSON for reproducibility and Binance support.
 */
internal fun williamsRuStatus(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
    "RUNNING" -> "Выполняется"
    "STOPPED" -> "Остановлено"
    "READY" -> "Готово"
    "READY_BLOCKED" -> "Анализ завершён, входы заблокированы"
    "PARTIAL_BLOCKED" -> "Частичный анализ, входы заблокированы"
    "PARTIAL", "INCOMPLETE_SCAN" -> "Анализ выполнен частично"
    "FAILED", "FAIL" -> "Ошибка"
    "PASS" -> "Успешно"
    "WARNING", "WARN" -> "Предупреждение"
    "NOT_VERIFIED", "NOT VERIFIED" -> "Не проверено"
    "RECONCILE_REQUIRED", "BLOCKED_FUTURES_RECONCILIATION" -> "Требуется сверка с биржей"
    "ENTRY_PENDING" -> "Ожидается исполнение входа"
    "EXIT_PENDING" -> "Ожидается подтверждение выхода"
    "OPEN_PROTECTED" -> "Позиция защищена стопом"
    "POSITION_UNPROTECTED" -> "Защита позиции не подтверждена"
    "PAUSED" -> "Новые входы на паузе"
    "TRIGGERING", "TRIGGERED" -> "Условный ордер срабатывает; исполнение не подтверждено"
    "NEW", "PENDING", "WORKING", "ACCEPTED" -> "Ордер ожидает исполнения"
    "FILLED", "FINISHED" -> "Исполнено"
    "CANCELED", "CANCELLED" -> "Отменено"
    "REJECTED" -> "Отклонено"
    "COMPLETE_OBSERVATION_ONLY" -> "Полный снимок наблюдений; не допуск к торговле"
    "BLOCKED_ACCOUNT" -> "Ожидание данных счёта"
    "WAITING_FOR_HISTORY" -> "Загрузка исторических свечей"
    "WAITING_FOR_MARKET_DATA" -> "Ожидание котировок"
    "BLOCKED_KILL", "KILL_SWITCH_LATCHED" -> "Аварийная блокировка"
    else -> raw
}

internal fun williamsRuRuntimeMessage(raw: String): String = when {
    raw.startsWith("FUTURES_DAILY_BASELINE_UNKNOWN", ignoreCase = true) ->
        "Не удалось подтвердить капитал на начало суток UTC. Новые входы запрещены; анализ рынка и защита позиций продолжаются. Требуется сверка истории доходов, позиций и ожидающих ордеров Binance."
    raw.contains("exchange_confirmed_pending_entry_or_fill_not_yet_resolved", ignoreCase = true) ->
        "Binance подтверждает ожидающий вход или ещё не завершённое исполнение. Новые заявки заблокированы до сверки."
    raw.contains("Unable to resolve host", ignoreCase = true) ->
        "Не удалось подключиться к Binance: ошибка DNS. Проверьте интернет и повторите сверку."
    else -> williamsRuStatus(raw)
}

@Composable
internal fun CockpitAppearanceCard(context: Context) {
    val preferences = remember { context.getSharedPreferences("cockpit_appearance", Context.MODE_PRIVATE) }
    CockpitCard("Интерфейс") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Светлая тема")
            Switch(AppAppearance.light, {
                AppAppearance.light = it
                preferences.edit { putBoolean("light", it) }
            })
        }
        Text("Размер текста", color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1f, 1.15f, 1.3f).forEach { scale ->
                FilterChip(AppAppearance.textScale == scale, {
                    AppAppearance.textScale = scale
                    preferences.edit { putFloat("text_scale", scale) }
                }, label = { Text("${(scale * 100).toInt()}%") })
            }
        }
    }
}

internal fun cockpitNumber(value: Double?, digits: Int = 2): String =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "—"

internal fun cockpitTime(at: Long): String = if (at <= 0L) "не подтверждено" else
    SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(at))

@Composable
internal fun CockpitCard(title: String, accent: Color = AppColors.primary, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = accent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun CockpitField(label: String, value: String, color: Color = AppColors.textMuted) {
    ResponsiveCockpitField(label, value, color)
}

/**
 * Never mistake a stale UI envelope for loss of encrypted credentials.
 * Diagnostic 17: the signed Demo account was readable with 4 live positions
 * although a settings screen showed "keys not set". An old balance alone
 * does NOT prove currently stored credentials, so call out the conflict.
 */
internal fun cockpitCredentialTruth(status:Status):String = when {
    status.binanceConfigured ->
        "Ключи сохранены в защищённом хранилище Runtime. Secret не отображается."
    status.accountSnapshotAtMs>0L || status.balance!=null || status.running ->
        "⚠️ Сведения противоречивы: раньше получен баланс Binance или работал движок, " +
        "но текущий экран не подтверждает наличие ключей. Не удаляй приложение и ключи; " +
        "обнови статус и экспортируй диагностику. Торговая готовность НЕ доказана."
    status.snapshotAtMs<=0L ->
        "Статус ключей ещё не получен от Runtime. Это НЕ доказательство их потери."
    else ->
        "Runtime сообщает, что API-ключи не настроены в этом экземпляре приложения."
}

@Composable
internal fun CockpitFreshness(status: Status) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val fresh = status.positionsSnapshotOk && CockpitDiagnostics.fresh(status.positionsSnapshotAtMs, now, 15_000L)
    val screenFresh = CockpitDiagnostics.fresh(status.snapshotAtMs, now, 30_000L)
    Text(
        when {
            !screenFresh -> "Данные экрана не подтверждены • последнее обновление ${cockpitTime(status.snapshotAtMs)}"
            !fresh -> "Нет свежей сверки позиций • предыдущий снимок ${cockpitTime(status.positionsSnapshotAtMs)}; текущая защита неизвестна"
            else -> "Binance подтвердил позиции ${cockpitTime(status.positionsSnapshotAtMs)}"
        }, color = if (fresh && screenFresh) AppColors.textMuted else AppColors.amber,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
internal fun CockpitReadiness(status: Status) {
    val r = status.cockpitReadiness
    CockpitCard("BINANCE FUTURES TESTNET") {
        CockpitField("Движок", if (status.running) "Работает" else "Остановлен", if (status.running) AppColors.green else AppColors.amber)
        CockpitField("Связь", if (r.optBoolean("technical_ready")) "Готова" else "Требует внимания")
        CockpitField("Новые входы", if (r.optBoolean("new_entries_allowed")) "Разрешены" else "Ограничены",
            if (r.optBoolean("new_entries_allowed")) AppColors.green else AppColors.amber)
        CockpitField("Сопровождение", when {
            !status.running -> "Движок остановлен"
            r.optBoolean("position_management_ready") -> "Готово"
            r.optBoolean("stop_only_management_ready") -> "Готово • только защитный SL"
            else -> "Требуется сверка"
        })
        val awaiting=r.optInt("pending_orders",0)
        if(awaiting>0) {
            CockpitField("Ожидающие заявки на вход",awaiting.toString(),AppColors.amber)
            Text("Ожидающая STOP-заявка — НЕ открытая позиция. " +
                "До фактического исполнения входа отдельный защитный SL " +
                "может ещё не быть выставлен. После любого исполнения " +
                "защита должна быть подтверждена Binance.",
                style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
            if(!status.running || status.paused ||
                !r.optBoolean("position_management_ready") &&
                    !r.optBoolean("stop_only_management_ready")) {
                Text("⚠️ Возможны биржевые STOP-входы, даже когда приложение " +
                    "остановлено или сопровождение недоступно. Это не значит, " +
                    "что они уже исполнились. Проверь реальные открытые заявки " +
                    "и позиции в Binance Demo. Не удаляй приложение и не " +
                    "считай старый экран доказательством защиты.",
                    style=MaterialTheme.typography.bodySmall,color=AppColors.red)
            }
        }
        Text(russianExecutionBlocker(r.optString("new_entries_reason", "Ожидание первого снимка")), style = MaterialTheme.typography.bodySmall)
        Text(r.optString("utc_loss_reset_explanation", "Суточные ограничения уточняются по времени Binance UTC."),
            style = MaterialTheme.typography.bodySmall, color = AppColors.amber)
        if (!r.optBoolean("daily_risk_baseline_known", false)) {
            Text("⚠️ Дневная база риска не подтверждена. 00:00 UTC не снимает этот независимый блок.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.red)
        }
        val exposed=r.optJSONArray("unprotected_positions")
        if(exposed!=null && exposed.length()>0) {
            val names=(0 until exposed.length()).mapNotNull { i ->
                exposed.optJSONObject(i)?.optString("symbol")?.takeIf { it.isNotBlank() }
            }.distinct().joinToString(", ")
            Text("⚠️ КРИТИЧНО: для $names сейчас нет свежего доказательства защитного SL Binance. " +
                "Это НЕ означает, что ордер отсутствует; до сверки считать защиту неподтверждённой.",
                color=AppColors.red,style=MaterialTheme.typography.bodySmall)
        }
        CockpitFreshness(status)
    }
}

/** Legible full-width metric cards on narrow Samsung screens and with large text. */
@Composable
private fun ResponsiveDashboardPair(
    first: @Composable () -> Unit, second: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow=maxWidth < 420.dp ||
            androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.25f
        if(narrow) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                first()
                second()
            }
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { first() }
                Box(Modifier.weight(1f)) { second() }
            }
        }
    }
}

@Composable
internal fun CockpitDashboard(
    padding: PaddingValues, status: Status, candidates: List<Candidate>, message: String,
    onMarket: (String) -> Unit, onSystem: () -> Unit, controls: @Composable () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item { CockpitReadiness(status) }
        item {
            ResponsiveDashboardPair(
                first={ CockpitCard("Средства / USDT") {
                    Text(cockpitNumber(status.balance), style = MaterialTheme.typography.headlineSmall)
                    Text("Доступно • ${cockpitTime(status.accountSnapshotAtMs)}", style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
                } },
                second={ CockpitCard("Результат дня", AppColors.green) {
                    Text(if (status.accountSnapshotAtMs > 0L) cockpitNumber(status.dailyPnlUsdt) else "—", style = MaterialTheme.typography.headlineSmall)
                    Text("USDT • день UTC", style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
                } }
            )
        }
        item {
            ResponsiveDashboardPair(
                first={ CockpitCard("Открытые позиции") {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(1000); now=System.currentTimeMillis() } }
                    Text(cockpitKnownOpenPositionsLabel(status,now), style = MaterialTheme.typography.headlineSmall)
                    Text(cockpitKnownOpenPositionsDetail(status,now),
                        color = if(cockpitKnownOpenPositionsLabel(status,now)=="—") AppColors.amber else AppColors.textMuted,
                        style = MaterialTheme.typography.labelSmall)
                } },
                second={ CockpitCard("Резерв риска", AppColors.amber) {
                    Text(cockpitNumber(status.reservedRiskPct * 100, 2) + "%", style = MaterialTheme.typography.headlineSmall)
                    Text("Лимит ${cockpitNumber(status.maxTotalRiskPct * 100)}%", style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
                } }
            )
        }
        item { Text("Активность бота", style = MaterialTheme.typography.titleLarge) }
        items(status.futuresIntents.take(4)) { intent ->
            CockpitIntentCard(intent, status, onMarket)
        }
        if (status.futuresIntents.isEmpty()) item {
            CockpitCard("Наблюдение за рынком") {
                Text("Сканер: ${russianStatus(status.scannerState)} • ${status.scannerSymbols}/10 пар")
                Text(candidates.firstOrNull()?.let { "${it.symbol}: ${russianExecutionBlocker(it.reason)}" }
                    ?: "Подтверждённых торговых кампаний в текущем снимке нет.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
            }
        }
        item { controls() }
        if (status.error != null || message.startsWith("Частичная ошибка")) item {
            CockpitCard("Требует внимания", AppColors.amber) {
                Text(russianExecutionBlocker(status.error ?: message), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onSystem) { Text("Открыть диагностику") }
            }
        }
    }
}

/**
 * A persisted OPEN_PROTECTED campaign and a last reconciled stop are NOT live
 * Binance proof. A green UI badge requires a fresh signed positions snapshot
 * containing the exact exchange stop for the same owned symbol and direction.
 * UI-only: never place, cancel or replace an exchange order.
 */
internal fun cockpitSignedStopVerified(
    intent:JSONObject,status:Status,now:Long
):Boolean {
    if (!status.positionsSnapshotOk ||
        !CockpitDiagnostics.fresh(status.positionsSnapshotAtMs,now,CockpitDiagnostics.POSITION_TTL_MS) ||
        !CockpitDiagnostics.fresh(status.snapshotAtMs,now,30_000L) ||
        intent.optString("campaign_status")!="OPEN_PROTECTED" ||
        intent.optString("protection_status")!="PROTECTED" ||
        !intent.optBoolean("strategy_ownership_verified",false) ||
        intent.optString("protection_client_id_suffix").isBlank()) return false
    val expected=intent.optString("persisted_protective_stop").toDoubleOrNull()
        ?.takeIf { it.isFinite() && it>0 } ?: return false
    return status.positions.count { position ->
        position.symbol==intent.optString("symbol") &&
        position.direction==intent.optString("direction") &&
        kotlin.math.abs(position.qty)>0.0 &&
        position.stopVerificationStatus in setOf(
            "OWNED_STOP_SEEN","NATIVE_REPLACEMENT_PROTECTION_SEEN","EXTERNAL_PROTECTED"
        ) && position.stop?.let { actual ->
            actual.isFinite() && kotlin.math.abs(actual-expected)<=
                maxOf(1e-8,kotlin.math.abs(expected)*1e-8)
        }==true
    }==1
}

/**
 * Show a stale historical STOP observation in amber only, never as a current
 * protective order. This is informational; no trading authorization results.
 */
internal fun cockpitPreviousStopEvidence(intent:JSONObject,status:Status,now:Long):String? {
    if (cockpitSignedStopVerified(intent,status,now)) return null
    val previous=status.positions.singleOrNull { p ->
        p.symbol==intent.optString("symbol") &&
        p.direction==intent.optString("direction") &&
        kotlin.math.abs(p.qty)>0.0 && p.stop!=null &&
        p.stopVerificationStatus in setOf("OWNED_STOP_SEEN","NATIVE_REPLACEMENT_PROTECTION_SEEN","EXTERNAL_PROTECTED") &&
        p.stopConfirmedAtMs>0L
    } ?: return null
    return "Последнее известное биржевое подтверждение SL " +
        formatChartPrice(previous.stop ?: return null) + " • " +
        cockpitTime(previous.stopConfirmedAtMs) +
        ". СВЕДЕНИЯ УСТАРЕЛИ, действующий ордер нужно подтвердить заново."
}

internal fun cockpitKnownOpenPositionsLabel(status:Status,now:Long):String {
    val archivedOpenExposure=status.futuresIntents.any { intent ->
        intent.optString("role","ENTRY")=="ENTRY" &&
        intent.optString("campaign_status") in setOf("POSITION_OPEN","OPEN_PROTECTED","EXIT_PENDING") &&
        intent.optString("confirmed_position_qty").toDoubleOrNull()?.let { it>0.0 }==true
    }
    val counted=status.positions.count { kotlin.math.abs(it.qty)>0.0 }
    return if(status.positionsSnapshotOk &&
        status.openPositions==counted && (!archivedOpenExposure || counted>0) &&
        CockpitDiagnostics.fresh(status.positionsSnapshotAtMs,now,CockpitDiagnostics.POSITION_TTL_MS) &&
        CockpitDiagnostics.fresh(status.snapshotAtMs,now,30_000L))
        status.openPositions.toString() else "—"
}

internal fun cockpitKnownOpenPositionsDetail(status:Status,now:Long):String {
    if(cockpitKnownOpenPositionsLabel(status,now)!="—") return "Позиции Binance • подтверждены"
    val journalCount=status.futuresIntents.count { intent ->
        intent.optString("role","ENTRY")=="ENTRY" &&
        intent.optString("campaign_status") in setOf("POSITION_OPEN","OPEN_PROTECTED","EXIT_PENDING") &&
        intent.optString("confirmed_position_qty").toDoubleOrNull()?.let { it>0.0 }==true
    }
    val lastCount=maxOf(status.openPositions,status.positions.count { kotlin.math.abs(it.qty)>0.0 },journalCount)
    return when {
        status.positions.any { kotlin.math.abs(it.qty)>0.0 } || status.openPositions>0 ->
            "Нет свежей сверки • ранее учтено позиций: $lastCount"
        journalCount>0 -> "Нет свежей сверки • в журнале открытых кампаний: $journalCount"
        else -> "Нет свежей сверки • отсутствие позиций НЕ подтверждено"
    }
}

internal fun cockpitBookSignalRu(raw:String):String=when {
    raw.startsWith("BOOK H1 WM3",ignoreCase=true) ->
        "TC2, третий Мудрец: подтверждённый фрактал H1 и проверка линии Teeth. " +
        "Условный вход при пробое уровня H1; подтверждение M15 не требуется."
    raw.startsWith("BOOK H1 WM2",ignoreCase=true) ->
        "TC2, второй Мудрец: последовательность трёх баров AO на H1; " +
        "условный вход после проверки фактического сигнала и риска."
    raw.startsWith("BOOK H1 WM1",ignoreCase=true) ->
        "TC2, первый Мудрец: разворотный бар и ангуляция к Аллигатору. " +
        "Условный вход требует подтверждения цены."
    else -> russianExecutionBlocker(raw)
}

/** Displays uncertain/partial Futures fills as exposure requiring STOP evidence,
 * never as an unfilled order just because the positions REST snapshot lags.
 */
internal fun cockpitEntryMayHaveFilled(intent:JSONObject):Boolean =
    (intent.optString("confirmed_position_qty").toDoubleOrNull() ?: 0.0)>0.0 ||
    (intent.optString("executed_qty").toDoubleOrNull() ?: 0.0)>0.0 ||
    intent.optString("order_status") in setOf("FILLED","PARTIALLY_FILLED") ||
    intent.optString("campaign_status") in setOf("POSITION_OPEN","OPEN_PROTECTED")

@Composable
internal fun CockpitIntentCard(intent: JSONObject, status:Status, onMarket: (String) -> Unit) {
    val symbol = intent.optString("symbol")
    val direction = intent.optString("direction")
    var expanded by remember(symbol, intent.optString("client_id_suffix")) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(symbol,intent.optString("client_id_suffix")) {
        while(true) { kotlinx.coroutines.delay(1000); now=System.currentTimeMillis() }
    }
    val hasFilledExposure=cockpitEntryMayHaveFilled(intent)
    val pendingUnfilled=intent.optString("campaign_status")=="ENTRY_PENDING" &&
        !hasFilledExposure && intent.optString("order_status") in setOf("NEW","WORKING","ACCEPTED")
    val protected=cockpitSignedStopVerified(intent,status,now)
    CockpitCard("$symbol • $direction", if (direction == "SHORT") AppColors.red else AppColors.green) {
        Text(if(intent.optString("campaign_status")=="OPEN_PROTECTED" && !protected)
            "Позиция учтена • защита сейчас не подтверждена"
            else russianStatus(intent.optString("campaign_status")), fontWeight = FontWeight.SemiBold,
            color=if(hasFilledExposure && !protected) AppColors.amber else MaterialTheme.colorScheme.onSurface)
        CockpitField(if(hasFilledExposure) "Исходная заявка Binance" else "Binance",
            intent.optString("order_status","не подтверждено") +
            if(hasFilledExposure) " • исполнение позиции учтено отдельно" else "")
        Text(cockpitBookSignalRu(intent.optString("signal_reason")).ifBlank {
            "Причина сигнала для этой кампании не записана"
        }, color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
        CockpitField(if(pendingUnfilled) "План: вход / будущий SL" else "Вход / структурный SL",
            formatChartPrice(intent.optDouble("entry_price")) + " / " +
                formatChartPrice(intent.optDouble("persisted_protective_stop")))
        if(pendingUnfilled) Text(
            "Биржевой вход ожидает активации. Показанный SL — расчётный уровень, " +
                "а не доказательство уже выставленной защитной заявки.",
            style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
        CockpitLastStopMove(intent)
        CockpitField("Защита", when {
            protected -> "Подтверждена свежим снимком Binance"
            hasFilledExposure -> "ВОЗМОЖНОЕ ИСПОЛНЕНИЕ • проверить фактическую позицию и STOP"
            intent.optString("campaign_status")=="ENTRY_PENDING" &&
                intent.optString("order_status")!="PARTIALLY_FILLED" -> "Ожидает исполнения входа"
            else -> "НЕ ПОДТВЕРЖДЕНА СЕЙЧАС"
        }, if(protected) AppColors.green else if(hasFilledExposure) AppColors.red else AppColors.amber)
        cockpitPreviousStopEvidence(intent,status,now)?.let { previous ->
            Text(previous,color=AppColors.amber,style=MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть детали" else "Риск, время и сверка") }
        if (expanded) {
            CockpitField("Плановый резерв", cockpitNumber(intent.optDouble("risk_pct") * 100) + "%")
            CockpitField("Фактический риск", intent.optString("actual_risk_audit_status", "UNKNOWN"))
            CockpitField("Измеренный риск", cockpitNumber(intent.optDouble("actual_all_in_risk_pct") * 100, 4) + "%")
            CockpitField("Создана", cockpitTime(intent.optLong("created_at_ms")))
            CockpitField("Последняя сверка", cockpitTime(intent.optLong("last_reconciled_ms")))
        }
        TextButton(onClick = { onMarket(symbol) }) { Text("График и решение Williams") }
    }
}

@Composable
internal fun CockpitAnalysis(padding: PaddingValues, status: Status, candidates: List<Candidate>, onMarket: (String) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item {
            Text("Анализ Williams", style = MaterialTheme.typography.headlineSmall)
            Text("D1 / H4 → H1 → сигнал → риск → решение", color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
            CockpitFreshness(status)
        }
        items(APPROVED_FUTURES_SYMBOLS) { symbol ->
            val c = candidates.firstOrNull { it.symbol == symbol }
            val active = status.futuresIntents.firstOrNull { it.optString("symbol") == symbol }
            val direction = c?.entrySignalType?.let { if (it.startsWith("SHORT")) "SHORT" else if (it.isNotBlank()) "LONG" else "—" } ?: "—"
            Card(Modifier.fillMaxWidth().clickable { selected = if (selected == symbol) null else symbol },
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(symbol.removeSuffix("USDT"), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(active?.optString("campaign_status")?.let(::russianStatus) ?: when {
                            c == null -> "Нет результата"
                            c.campaignReady -> "Проверка $direction"
                            c.signal -> "Сигнал $direction"
                            else -> "Наблюдение"
                        }, color = AppColors.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (selected == symbol) {
                        CockpitField("Контекст H4", c?.let { if (it.htfConfirmed) "Подтверждён для кандидата" else "Не подтверждён" } ?: "Не проверен")
                        val phase=runCatching {
                            JSONObject(status.decisionTracesJson).optJSONObject(symbol)
                                ?.optJSONObject("market_phase")
                        }.getOrNull()
                        val story=phase?.optJSONObject("market_narrative")
                        if(story!=null) {
                            Text("Где находится рынок • проверенные ТФ",
                                style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                            val frames=story.optJSONArray("frames")
                            if(frames!=null) for(i in 0 until frames.length()) {
                                val view=frames.optJSONObject(i) ?: continue
                                Text(view.optString("explanation_ru"),
                                    style=MaterialTheme.typography.bodySmall,
                                    color=if(view.optBoolean("chart_observed")) AppColors.textMuted else AppColors.amber)
                            }
                            Text(story.optString("cross_timeframe_caution_ru"),
                                color=AppColors.amber,style=MaterialTheme.typography.bodySmall)
                            val structure=story.optJSONArray("structural_relations")
                            if(structure!=null) for(i in 0 until structure.length()) {
                                val relation=structure.optJSONObject(i) ?: continue
                                Text(relation.optString("explanation_ru"),style=MaterialTheme.typography.bodySmall,
                                    color=AppColors.textMuted)
                            }
                            Text("Волновая разметка — гипотеза. Эти показания НЕ открывают и НЕ закрывают сделки.",
                                style=MaterialTheme.typography.labelSmall,color=AppColors.textMuted)
                        } else {
                            Text("Мультитаймфреймовый анализ ещё не подтверждён: проверь последнюю диагностику.",
                                color=AppColors.amber,style=MaterialTheme.typography.bodySmall)
                        }

                        CockpitField("H1: семейство сигнала", c?.entrySignalType?.ifBlank { "Нет" } ?: "Не проверено")
                        CockpitField("Триггер / стоп", cockpitNumber(c?.entryTriggerPrice, 4) + " / " + cockpitNumber(c?.entryProtectivePrice, 4))
                        CockpitField("Оценка сканера", c?.let { "Риск ${cockpitNumber(it.riskPct)}% • R/R ${cockpitNumber(it.riskReward)}" } ?: "Нет данных")
                        Text(russianExecutionBlocker(c?.reason ?: "Нет фактического результата; направление не предполагается."), style = MaterialTheme.typography.bodySmall)
                        Text(russianExecutionBlocker(status.cockpitReadiness.optString("new_entries_reason")), color = AppColors.amber, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onMarket(symbol) }) { Text("Открыть график") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CockpitTrades(
    padding: PaddingValues, status: Status, trades: List<Trade>, onMarket: (String) -> Unit,
    onTradeChart: (Trade) -> Unit, active: @Composable () -> Unit, history: @Composable () -> Unit
) {
    var selected by remember { mutableIntStateOf(0) }
    val pendingCount=status.futuresIntents.count {
        it.optString("campaign_status") in setOf("ENTRY_PENDING","FILL_CONFIRMATION_PENDING","EXIT_PENDING")
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Активные",if(pendingCount>0) "Ожидающие ($pendingCount)" else "Ожидающие","История")
                .forEachIndexed { index, title ->
                FilterChip(selected == index, { selected = index }, label = { Text(title, style = MaterialTheme.typography.labelSmall) })
            }
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { CockpitFreshness(status) }
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> active()
                1 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pending = status.futuresIntents.filter { it.optString("campaign_status") in setOf("ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "EXIT_PENDING") }
                    if (pending.isEmpty()) item { Text("Ожидающих заявок в последнем снимке нет.", color = AppColors.textMuted) }
                    items(pending) { CockpitIntentCard(it, status, onMarket) }
                }
                else -> Column {
                    Box(Modifier.weight(1f)) { history() }
                }
            }
        }
    }
}

@Composable
internal fun CockpitControls(status: Status, onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onSuspend: () -> Unit, onShutdown: () -> Unit, onKill: () -> Unit) {
    var confirmation by remember { mutableStateOf("") }
    CockpitCard("Управление") {
        Button(onClick = if (status.running) { if (status.paused) onResume else onPause } else onStart,
            modifier = Modifier.fillMaxWidth(), enabled = !status.executionKillLatched) {
            Text(if (!status.running) "Запустить движок" else if (status.paused) "Возобновить новые входы" else "Приостановить новые входы")
        }
        if (status.running) {
            Text(if (status.paused) "H1-анализ и сопровождение продолжаются. Новые входы на паузе; ранее выставленные заявки могут исполниться."
                else "Бот анализирует 10 пар и сопровождает позиции.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
            OutlinedButton(onClick = { confirmation = "suspend" }, modifier = Modifier.fillMaxWidth()) { Text("Приостановить входы и снять заявки") }
        }
        TextButton(onClick = { confirmation = "shutdown" }, modifier = Modifier.fillMaxWidth()) { Text("Полностью выключить движок") }
        TextButton(onClick = { confirmation = "kill" }, modifier = Modifier.fillMaxWidth()) { Text("Аварийно заблокировать новые входы", color = AppColors.red) }
    }
    if (confirmation.isNotEmpty()) AlertDialog(onDismissRequest = { confirmation = "" },
        title = { Text(when (confirmation) { "suspend" -> "Приостановить входы?"; "shutdown" -> "Выключить движок?"; else -> "Аварийная блокировка?" }) },
        text = { Text(when (confirmation) {
            "suspend" -> "Отменить ожидающие входные заявки. Открытые позиции продолжат сопровождаться. Неподтверждённая отмена потребует сверки."
            "shutdown" -> "Полное выключение разрешено только при подтверждённом отсутствии позиций. Связь и сканирование будут остановлены."
            else -> "Заблокировать новые входы и отменить ожидающие входные заявки. Сопровождение открытых позиций продолжится. Для возобновления потребуется явный сброс блокировки."
        }) },
        confirmButton = { TextButton(onClick = {
            when (confirmation) { "suspend" -> onSuspend(); "shutdown" -> onShutdown(); else -> onKill() }
            confirmation = ""
        }) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = { confirmation = "" }) { Text("Отмена") } })
}

@Composable
internal fun CockpitDiagnosticScreen(
    padding: PaddingValues,
    status: Status,
    report: JSONObject?,
    message: String,
    onRun: () -> Unit,
    onExport: () -> Unit,
    onSelfHeal: () -> Unit,
    running: Boolean = false,
    archiveReady: Boolean = false
) {
    // One dedicated diagnostics screen. Filtering never changes Runtime
    // or sends an exchange order; the exported ZIP retains all raw evidence.
    var filter by rememberSaveable { mutableStateOf("Проблемы") }
    var domain by rememberSaveable { mutableStateOf("Все") }
    var engineering by rememberSaveable { mutableStateOf(false) }
    var rawJson by rememberSaveable { mutableStateOf(false) }
    var confirmRecovery by rememberSaveable { mutableStateOf(false) }
    val tests = remember(report) { CockpitDiagnostics.rows(report?.optJSONArray("tests")) }
    val triage = remember(report) {
        report?.optJSONObject("diagnostic_triage")
            ?: williamsDiagnosticTriage(report?.optJSONArray("tests") ?: org.json.JSONArray(),false)
    }
    val priority = CockpitDiagnostics.rows(triage.optJSONArray("priority_issues"))
    val domains = remember(report) { listOf("Все") + tests.map {
        it.optString("domain","unknown").ifBlank { "unknown" }
    }.distinct().sorted() }
    val filtered = tests.filter { row ->
        val severity = row.optString("severity")
        (filter == "Все" || (filter == "Проблемы" && severity !in setOf("PASS")) ||
            (filter == "WARNING" && severity in setOf("WARN","WARNING")) ||
            severity == filter) &&
            (domain == "Все" || row.optString("domain","unknown") == domain)
    }.sortedWith(compareBy<JSONObject> { row -> when(row.optString("severity")) {
        "FAIL" -> 0; "WARNING", "WARN" -> 1; "NOT_VERIFIED" -> 2
        else -> 3
    } }.thenBy { it.optString("id",it.optString("name")) })
    val summary = report?.optJSONObject("diagnostic_summary")
    val reportAtMs=report?.optLong("created_at_ms") ?: 0L
    var clockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(reportAtMs) {
        while(true) { kotlinx.coroutines.delay(30_000L); clockMs=System.currentTimeMillis() }
    }
    val reportAgeMs=if(reportAtMs in 1..clockMs) clockMs-reportAtMs else Long.MAX_VALUE
    val snapshotFresh=reportAgeMs<=120_000L
    val comparison=report?.optJSONObject("diagnostic_comparison")
    val overall = triage.optString("overall","NOT_VERIFIED")
    val headlineColor = when {
        overall=="FAIL" -> AppColors.red
        !snapshotFresh -> AppColors.amber
        overall=="WARNING" -> AppColors.amber
        overall=="PASS" -> AppColors.green
        else -> AppColors.textMuted
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 36.dp)
    ) {
        item {
            Text("Диагностика системы",style=MaterialTheme.typography.headlineSmall,
                fontWeight=FontWeight.Bold)
            Text("Один центр проверки • понятные причины • полный отчёт ZIP",
                style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
        }
        item { CockpitReadiness(status) }
        item {
            CockpitCard("Общее состояние",headlineColor) {
                Text(williamsRuStatus(overall),style=MaterialTheme.typography.titleLarge,
                    fontWeight=FontWeight.Bold,color=headlineColor)
                Text("ОШИБКИ ${triage.optInt("failed_count")}   •   ПРЕДУПРЕЖДЕНИЯ ${triage.optInt("warning_count")}",
                    style=MaterialTheme.typography.bodyMedium)
                Text("НЕ ПРОВЕРЕНО ${triage.optInt("unverified_count")}   •   ПРОЙДЕНО ${triage.optInt("passed_count")}",
                    style=MaterialTheme.typography.bodyMedium,color=AppColors.textMuted)
                CockpitField("Снимок",cockpitTime(reportAtMs))
                if(!snapshotFresh) Text(
                    "⚠️ ДАННЫЕ ДИАГНОСТИКИ УСТАРЕЛИ или время устройства изменилось. " +
                        "Запустите новую проверку; успешность старого снимка не доказывает готовность сейчас.",
                    color=AppColors.amber,style=MaterialTheme.typography.bodySmall)
                CockpitField("Новые входы по снимку",
                    if(!snapshotFresh) "Неизвестны — снимок устарел"
                    else if(summary?.optBoolean("execution_allowed")==true)
                        "Допуск заявлен; нужна биржевая сверка"
                    else "Не подтверждены")
                CockpitField("ZIP последней проверки",
                    if(archiveReady) "Сохранён для этого снимка"
                    else "Не сохранён или ещё не проверен")
                Text(triage.optString("status_note"),style=MaterialTheme.typography.bodySmall,
                    color=AppColors.textMuted)
            }
        }
        item {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact=maxWidth < 440.dp
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=onRun,enabled=!running,modifier=Modifier.fillMaxWidth()) {
                        if(running) CircularProgressIndicator(
                            modifier=Modifier.size(18.dp),strokeWidth=2.dp)
                        else Text("Проверить всю систему")
                    }
                    if(compact) {
                        OutlinedButton(onClick=onExport,enabled=!running && report!=null && archiveReady,
                            modifier=Modifier.fillMaxWidth()) { Text("Сохранить полный отчёт ZIP") }
                        OutlinedButton(onClick={confirmRecovery=true},enabled=!running,
                            modifier=Modifier.fillMaxWidth()) { Text("Сверить Binance и восстановить") }
                    } else {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick=onExport,enabled=!running && report!=null && archiveReady,
                                modifier=Modifier.weight(1f)) { Text("Экспорт ZIP") }
                            OutlinedButton(onClick={confirmRecovery=true},enabled=!running,
                                modifier=Modifier.weight(1f)) { Text("Сверка / восстановление") }
                        }
                    }
                    if(message.isNotBlank())
                        Text(message,style=MaterialTheme.typography.bodySmall,
                            color=AppColors.textMuted)
                }
            }
        }
        item {
            CockpitCard("Что требует внимания",if(priority.isNotEmpty()) AppColors.amber else AppColors.primary) {
                if(report == null) {
                    Text("Запустите диагностику. Пока проверки нет, исправность неизвестна.")
                } else if(priority.isEmpty()) {
                    Text("В этом снимке ошибок не обнаружено. Это не доказывает точность волн, полный книжный охват или исполнение Demo.")
                } else {
                    priority.take(6).forEach { issue ->
                        val sev=issue.optString("severity")
                        val c=if(sev=="FAIL") AppColors.red else if(sev=="WARNING")
                            AppColors.amber else AppColors.textMuted
                        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text("${williamsRuStatus(sev)} • ${issue.optString("id")}",
                                color=c,style=MaterialTheme.typography.labelSmall)
                            Text(russianExecutionBlocker(issue.optString("name")),
                                style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold)
                            Text(issue.optString("next_action"),style=MaterialTheme.typography.bodySmall,
                                color=AppColors.textMuted)
                            HorizontalDivider(color=AppColors.surface2)
                        }
                    }
                    if(priority.size>6 || triage.optBoolean("priority_issues_truncated"))
                        Text("Остальные пункты — ниже в проверках и в полном архиве.",
                            style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
                }
            }
        }
        item {
            CockpitCard("Изменения после предыдущей диагностики") {
                if(comparison?.optString("status")!="COMPARABLE") {
                    Text("Пока нет двух сопоставимых сохранённых снимков. " +
                        "Сравнение появится после следующей успешной проверки.",
                        style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
                } else {
                    CockpitField("Предыдущая проверка",cockpitTime(comparison.optLong("previous_at_ms")))
                    CockpitField("Новые проблемы",comparison.optInt("new_count").toString(),
                        if(comparison.optInt("new_count")>0) AppColors.red else AppColors.textMuted)
                    CockpitField("Ухудшились",comparison.optInt("worsened_count").toString(),
                        if(comparison.optInt("worsened_count")>0) AppColors.red else AppColors.textMuted)
                    CockpitField("Менее серьёзны",comparison.optInt("less_severe_count").toString())
                    CockpitField("Больше не видны",comparison.optInt("no_longer_reported_count").toString())
                    val changes=CockpitDiagnostics.rows(comparison.optJSONArray("new_issues")) +
                        CockpitDiagnostics.rows(comparison.optJSONArray("worsened"))
                    if(changes.isNotEmpty()) {
                        for (issue in changes.take(4)) {
                            Text("• ${issue.optString("id")} — ${russianExecutionBlocker(issue.optString("name"))}",
                                style=MaterialTheme.typography.bodySmall,color=AppColors.red)
                        }
                    }
                    Text("«Не видны» не означает «исправлены»: сравнение фиксирует только различия отчётов.",
                        style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
                }
            }
        }
        item {
            val android=report?.optJSONObject("status")?.optJSONObject("android_runtime")
            CockpitCard("Android · работа в фоне") {
                if(android==null) {
                    Text("Ещё нет подтверждённого снимка Android.",
                        style=MaterialTheme.typography.bodySmall)
                } else {
                    CockpitField("Фоновая служба",if(android.optBoolean("foreground_service_active"))
                        "Активна на момент снимка" else "Не подтверждена")
                    CockpitField("CPU WakeLock",if(android.optBoolean("cpu_wake_lock_held"))
                        "Удерживается" else "Не удерживается / неизвестно")
                    CockpitField("Энергосбережение",if(android.optBoolean("battery_optimization_ignored"))
                        "Ограничение батареи снято" else "Нужна проверка разрешений Android")
                    val workerAge=android.optLong("runtime_worker_heartbeat_age_ms",-1L)
                    CockpitField("Пульс движка",when {
                        workerAge<0L || android.optLong("runtime_worker_last_loop_elapsed_ms")<=0L ->
                            "Нет подтверждённого heartbeat"
                        android.optBoolean("runtime_worker_heartbeat_stale") ->
                            "Устарел — проверить фоновую работу"
                        else -> "Последняя активность ${workerAge/1000L} сек назад"
                    })
                    val fail=android.optJSONObject("last_boot_startup_failure")?.optString("code").orEmpty()
                    if(fail.isNotBlank()) Text("Последний сбой запуска: ${safeDiagnosticText(fail)}",
                        color=AppColors.red,style=MaterialTheme.typography.bodySmall)
                    Text("Этот снимок не подтверждает непрерывную работу 24 часа. Не отключайте системные защитные блокировки.",
                        style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
                }
            }
        }
        item {
            val evidence=report?.optJSONObject("unified_strategy_evidence")
            var showPairs by remember(report?.optLong("created_at_ms",0L)) { mutableStateOf(false) }
            CockpitCard("Книги и рыночные сигналы") {
                if(evidence==null) {
                    Text("Нет снимка источников. Наличие сигнала ещё не означает допустимую сделку.")
                } else {
                    Text("Статус: ${williamsRuStatus(evidence.optString("status"))} • сигналов ${evidence.optInt("observed_signal_count")}",
                        style=MaterialTheme.typography.bodySmall)
                    for((id,title) in listOf(
                        williamsTc2Profile to "Trading Chaos II",
                        williamsNtdProfile to "New Trading Dimensions",
                        williamsCourseProfile to "Trading Chaos Course"
                    )) {
                        val e=evidence.optJSONObject("by_source")?.optJSONObject(id)
                        CockpitField(title,"сигналов ${e?.optInt("total") ?: 0} • LONG ${e?.optInt("long") ?: 0} / SHORT ${e?.optInt("short") ?: 0}")
                    }
                    Text("Это наблюдения сканера, а не подтверждённая точность волновой разметки или допуск Binance.",
                        style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
                    TextButton(onClick={showPairs=!showPairs}) {
                        Text(if(showPairs) "Свернуть пары" else "Показать сигналы по парам")
                    }
                    if(showPairs) for(row in CockpitDiagnostics.rows(evidence.optJSONArray("by_symbol"))) {
                        val counts=row.optJSONObject("source_counts")
                        CockpitField(row.optString("symbol"),
                            "TC2 ${counts?.optInt(williamsTc2Profile) ?: 0} • NTD ${counts?.optInt(williamsNtdProfile) ?: 0} • Course ${counts?.optInt(williamsCourseProfile) ?: 0}")
                    }
                }
            }
        }
        item {
            Text("Все проверки",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Проблемы","Все","FAIL","WARNING","NOT_VERIFIED","PASS").forEach { tag ->
                    FilterChip(selected=filter==tag,onClick={filter=tag},
                        label={Text(if(tag in setOf("Все","Проблемы")) tag else williamsRuStatus(tag))})
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                domains.forEach { tag ->
                    FilterChip(selected=domain==tag,onClick={domain=tag},
                        label={Text(if(tag=="Все") "Все разделы" else tag)})
                }
            }
            Text("Показано ${filtered.size} из ${tests.size}",style=MaterialTheme.typography.labelSmall,
                color=AppColors.textMuted)
        }
        if(filtered.isEmpty()) item {
            Text(if(report==null) "Сначала выполните проверку." else
                "Для выбранных фильтров проверок нет.",color=AppColors.textMuted)
        }
        itemsIndexed(filtered,key={index,row ->
            "${row.optString("domain")}:${row.optString("id").ifBlank { row.optString("name") }}:$index"
        }) { _,check ->
            var expanded by rememberSaveable(check.optString("id",check.optString("name"))) { mutableStateOf(false) }
            val severity=check.optString("severity","NOT_VERIFIED")
            val accent=when(severity) {"FAIL" -> AppColors.red
                "WARNING","WARN" -> AppColors.amber
                "PASS" -> AppColors.green
                else -> AppColors.textMuted
            }
            Card(Modifier.fillMaxWidth().clickable{expanded=!expanded},
                shape=RoundedCornerShape(16.dp),
                colors=CardDefaults.cardColors(containerColor=AppColors.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("${check.optString("id").ifBlank { check.optString("domain") }} • ${williamsRuStatus(severity)}",
                        style=MaterialTheme.typography.labelSmall,color=accent)
                    Text(russianExecutionBlocker(check.optString("name")),
                        style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                    if(expanded || severity=="FAIL") {
                        Text(russianExecutionBlocker(check.optString("detail")),
                            style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
                    } else Text("Нажмите для подробностей",style=MaterialTheme.typography.labelSmall,
                        color=AppColors.textMuted)
                }
            }
        }
        item {
            TextButton(onClick={engineering=!engineering}) {
                Text(if(engineering) "Скрыть технические журналы" else "Технические журналы и данные")
            }
        }
        if(engineering) {
            item {
                Text("Последние события здесь сокращены ради быстрого интерфейса. ZIP сохраняет исходный JSON, решения и доказательства Binance; диагностический экран не отправляет ордера.",
                    style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
            }
            items(CockpitDiagnostics.rows(report?.optJSONArray("event_timeline")).take(80)) { event ->
                Text("${cockpitTime(event.optLong("E"))} • ${event.optString("symbol")} • ${williamsRuStatus(event.optString("state"))}\n${williamsRuRuntimeMessage(event.optString("detail"))}",
                    style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
            }
            item {
                TextButton(onClick={rawJson=!rawJson}) {
                    Text(if(rawJson) "Свернуть JSON" else "Показать фрагмент JSON (полный в ZIP)")
                }
                if(rawJson) SelectionContainer {
                    Text(report?.toString(2)?.take(20000) ?: "Нет снимка",
                        style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if(confirmRecovery) AlertDialog(
        onDismissRequest={confirmRecovery=false},
        title={Text("Подтвердить восстановление Runtime?")},
        text={Text("Восстановление может перезапустить движок и инициировать " +
            "биржевую сверку. После успешной проверки автоматически разрешённые " +
            "входы могут возобновиться, если нет других защитных блокировок. " +
            "Аварийный KILL и неподтверждённые позиции не обходятся. " +
            "Простая диагностика ордера не отправляет.")},
        confirmButton={Button(onClick={
            confirmRecovery=false
            onSelfHeal()
        }) {Text("ПОДТВЕРДИТЬ ВОССТАНОВЛЕНИЕ")}},
        dismissButton={TextButton(onClick={confirmRecovery=false}) {Text("Отмена")}}
    )
}
