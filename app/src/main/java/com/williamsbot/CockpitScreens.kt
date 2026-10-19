package com.williamsbot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppColors.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun CockpitField(label: String, value: String, color: Color = AppColors.textMuted) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(1.15f), color = color, style = MaterialTheme.typography.bodySmall)
    }
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
            !fresh -> "Снимок позиций обновляется • последнее подтверждение ${cockpitTime(status.positionsSnapshotAtMs)}"
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
            else -> "Требуется сверка"
        })
        Text(r.optString("new_entries_reason", "Ожидание первого снимка"), style = MaterialTheme.typography.bodySmall)
        CockpitFreshness(status)
    }
}

@Composable
internal fun CockpitDashboard(
    padding: PaddingValues, status: Status, candidates: List<Candidate>, message: String,
    onMarket: (String) -> Unit, onSystem: () -> Unit, controls: @Composable () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item { CockpitReadiness(status) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { CockpitCard("Средства / USDT") {
                    Text(cockpitNumber(status.balance), style = MaterialTheme.typography.headlineSmall)
                    Text("Доступно • ${cockpitTime(status.accountSnapshotAtMs)}", style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
                } }
                Box(Modifier.weight(1f)) { CockpitCard("Результат дня", AppColors.green) {
                    Text(if (status.accountSnapshotAtMs > 0L) cockpitNumber(status.dailyPnlUsdt) else "—", style = MaterialTheme.typography.headlineSmall)
                    Text("USDT • день UTC", style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
                } }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { CockpitCard("Открытые позиции") {
                    Text(status.openPositions.toString(), style = MaterialTheme.typography.headlineSmall)
                    Text(if (status.positionsSnapshotOk) "Последний снимок Binance" else "Снимок устарел", color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
                } }
                Box(Modifier.weight(1f)) { CockpitCard("Резерв риска", AppColors.amber) {
                    Text(cockpitNumber(status.reservedRiskPct * 100, 2) + "%", style = MaterialTheme.typography.headlineSmall)
                    Text("Лимит ${cockpitNumber(status.maxTotalRiskPct * 100)}%", style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
                } }
            }
        }
        item { Text("Активность бота", style = MaterialTheme.typography.titleLarge) }
        items(status.futuresIntents.take(4)) { intent ->
            CockpitIntentCard(intent, onMarket)
        }
        if (status.futuresIntents.isEmpty()) item {
            CockpitCard("Наблюдение за рынком") {
                Text("Сканер: ${status.scannerState} • ${status.scannerSymbols}/10 пар")
                Text(candidates.firstOrNull()?.let { "${it.symbol}: ${it.reason}" }
                    ?: "Подтверждённых торговых кампаний в текущем снимке нет.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
            }
        }
        item { controls() }
        if (status.error != null || message.startsWith("Частичная ошибка")) item {
            CockpitCard("Требует внимания", AppColors.amber) {
                Text(status.error ?: message, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onSystem) { Text("Открыть диагностику") }
            }
        }
    }
}

@Composable
internal fun CockpitIntentCard(intent: JSONObject, onMarket: (String) -> Unit) {
    val symbol = intent.optString("symbol")
    val direction = intent.optString("direction")
    var expanded by remember(symbol, intent.optString("client_id_suffix")) { mutableStateOf(false) }
    CockpitCard("$symbol • $direction", if (direction == "SHORT") AppColors.red else AppColors.green) {
        Text(intent.optString("campaign_status"), fontWeight = FontWeight.SemiBold)
        CockpitField("Binance", intent.optString("order_status", "не подтверждено"))
        Text(intent.optString("signal_reason").ifBlank { "Причина сигнала для этой кампании не записана" },
            color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
        CockpitField("Вход / стоп", cockpitNumber(intent.optDouble("entry_price"), 4) + " / " +
            cockpitNumber(intent.optDouble("persisted_protective_stop"), 4))
        CockpitField("Защита", when {
            CockpitDiagnostics.protectionProven(intent, System.currentTimeMillis()) -> "Подтверждена Binance"
            intent.optString("campaign_status") == "ENTRY_PENDING" && intent.optString("order_status") != "PARTIALLY_FILLED" -> "Выставляется после исполнения"
            else -> "НЕ ПОДТВЕРЖДЕНА"
        }, if (CockpitDiagnostics.protectionProven(intent, System.currentTimeMillis())) AppColors.green else AppColors.amber)
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
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
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
                        Text(active?.optString("campaign_status") ?: when {
                            c == null -> "Нет результата"
                            c.campaignReady -> "Проверка $direction"
                            c.signal -> "Сигнал $direction"
                            else -> "Наблюдение"
                        }, color = AppColors.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (selected == symbol) {
                        CockpitField("Контекст H4", c?.let { if (it.htfConfirmed) "Подтверждён для кандидата" else "Не подтверждён" } ?: "Не проверен")
                        CockpitField("H1: семейство сигнала", c?.entrySignalType?.ifBlank { "Нет" } ?: "Не проверено")
                        CockpitField("Триггер / стоп", cockpitNumber(c?.entryTriggerPrice, 4) + " / " + cockpitNumber(c?.entryProtectivePrice, 4))
                        CockpitField("Оценка сканера", c?.let { "Риск ${cockpitNumber(it.riskPct)}% • R/R ${cockpitNumber(it.riskReward)}" } ?: "Нет данных")
                        Text(c?.reason ?: "Нет фактического результата; направление не предполагается.", style = MaterialTheme.typography.bodySmall)
                        Text(status.cockpitReadiness.optString("new_entries_reason"), color = AppColors.amber, style = MaterialTheme.typography.bodySmall)
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
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Активные", "Ожидающие", "История").forEachIndexed { index, title ->
                FilterChip(selected == index, { selected = index }, label = { Text(title, style = MaterialTheme.typography.labelSmall) })
            }
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { CockpitFreshness(status) }
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> active()
                1 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pending = status.futuresIntents.filter { it.optString("campaign_status") in setOf("ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "EXIT_PENDING") }
                    if (pending.isEmpty()) item { Text("Ожидающих заявок в последнем снимке нет.", color = AppColors.textMuted) }
                    items(pending) { CockpitIntentCard(it, onMarket) }
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
            Text(if (!status.running) "Запустить движок" else if (status.paused) "Возобновить поиск" else "Остановить поиск новых сделок")
        }
        if (status.running) {
            Text(if (status.paused) "Сопровождение работает. Уже размещённые заявки могут исполниться."
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
internal fun CockpitDiagnosticScreen(padding: PaddingValues, status: Status, report: JSONObject?, message: String,
    onRun: () -> Unit, onExport: () -> Unit, onSelfHeal: () -> Unit, running: Boolean = false) {
    var filter by remember { mutableStateOf("Все") }
    var engineering by remember { mutableStateOf(false) }
    val tests = CockpitDiagnostics.rows(report?.optJSONArray("tests"))
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item { Text("Диагностика", style = MaterialTheme.typography.headlineSmall) }
        item { CockpitReadiness(status) }
        item {
            Button(onClick = onRun, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Проверка выполняется…" else "Проверить всю систему") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = !running && report != null, modifier = Modifier.weight(1f)) { Text("Экспорт ZIP") }
                OutlinedButton(onClick = onSelfHeal, enabled = !running, modifier = Modifier.weight(1f)) { Text("Сверка / восстановление") }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
        }
        item {
            val summary = report?.optJSONObject("diagnostic_summary")
            Text("Результат: " + (summary?.optString("overall") ?: "NOT VERIFIED"), fontWeight = FontWeight.Bold)
            Text("PASS ${summary?.optInt("passed_test_count") ?: 0} • WARNING ${summary?.optInt("warning_test_count") ?: 0} • FAIL ${summary?.optInt("failed_test_count") ?: 0} • NOT VERIFIED ${summary?.optInt("not_verified_test_count") ?: 0}", style = MaterialTheme.typography.bodySmall)
            Text("Проверено ${cockpitTime(report?.optLong("created_at_ms") ?: 0L)}", color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
        }
        item {
            val evidence = report?.optJSONObject("unified_strategy_evidence")
            var showPairs by remember(report?.optLong("created_at_ms", 0L)) { mutableStateOf(false) }
            CockpitCard("Единая стратегия · книги и сигналы") {
                if (evidence == null) {
                    Text("Сначала запусти диагностику: данные TC2, NTD и Course ещё не получены.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    val snapshotStatus = evidence.optString("status")
                    Text("Снимок: $snapshotStatus • сигналов ${evidence.optInt("observed_signal_count")}",
                        color = if (snapshotStatus == "COMPLETE_OBSERVATION_ONLY") AppColors.green else AppColors.amber,
                        style = MaterialTheme.typography.bodySmall)
                    val labels = listOf(williamsTc2Profile to "Trading Chaos 2",
                        williamsNtdProfile to "New Trading Dimensions",
                        williamsCourseProfile to "Trading Chaos Course")
                    for ((id, title) in labels) {
                        val row = evidence.optJSONObject("by_source")?.optJSONObject(id)
                        CockpitField(title,
                            "Σ${row?.optInt("total") ?: 0} · вход ${row?.optInt("entry") ?: 0}" +
                            " · LONG ${row?.optInt("long") ?: 0}/SHORT ${row?.optInt("short") ?: 0}")
                    }
                    Text("Встречных сигналов до фильтра D1/H4: " +
                        evidence.optInt("pre_context_opposing_symbol_count") + " пар.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                    Text("Это сигналы из последнего снимка сканера, НЕ разрешённые ордера. " +
                        "Причины, по которым детектор не создал сигнал, и полный путь допуска Binance здесь пока не доказаны.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                    TextButton(onClick = { showPairs = !showPairs }) {
                        Text(if (showPairs) "Скрыть 10 пар" else "Подробнее по 10 парам")
                    }
                    if (showPairs) {
                        for (row in CockpitDiagnostics.rows(evidence.optJSONArray("by_symbol"))) {
                            val counts = row.optJSONObject("source_counts")
                            val brief = listOf("TC2 ${counts?.optInt(williamsTc2Profile) ?: 0}",
                                "NTD ${counts?.optInt(williamsNtdProfile) ?: 0}",
                                "Course ${counts?.optInt(williamsCourseProfile) ?: 0}").joinToString(" · ")
                            Text(row.optString("symbol") + " · " + brief +
                                (if (row.optBoolean("fresh_opposing_entries_before_context")) " · ⚠️ LONG/SHORT" else ""),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "FAIL", "WARNING", "NOT_VERIFIED", "PASS").forEach { label ->
                    FilterChip(filter == label, { filter = label }, label = { Text(label.replace('_', ' ')) })
                }
            }
        }
        items(tests.filter { filter == "Все" || it.optString("severity") == filter }) { check ->
            var expanded by remember(check.optString("id"), check.optString("name")) { mutableStateOf(false) }
            val severity = check.optString("severity", "NOT_VERIFIED")
            val color = when (severity) { "PASS" -> AppColors.green; "FAIL" -> AppColors.red; "WARNING" -> AppColors.amber; else -> AppColors.textMuted }
            Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }, colors = CardDefaults.cardColors(containerColor = AppColors.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(check.optString("id").ifBlank { check.optString("domain") } + " • " + severity.replace('_', ' '), color = color, style = MaterialTheme.typography.labelSmall)
                    Text(check.optString("name"), style = MaterialTheme.typography.bodyMedium)
                    if (expanded || severity == "FAIL" || severity == "WARNING") Text(check.optString("detail"), style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                }
            }
        }
        item { TextButton(onClick = { engineering = !engineering }) { Text(if (engineering) "Скрыть инженерную диагностику" else "Журналы, временная шкала и JSON") } }
        if (engineering) {
            item { Text("Последние события перед ошибками сохраняются автоматически. ZIP содержит временную шкалу и снимок последнего инцидента.", style = MaterialTheme.typography.bodySmall) }
            items(CockpitDiagnostics.rows(report?.optJSONArray("event_timeline"))) { event ->
                Text("${cockpitTime(event.optLong("E"))} • ${event.optString("symbol")} • ${event.optString("state")}\n${event.optString("detail")}", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
            }
            item { SelectionContainer { Text(report?.toString(2) ?: "Диагностика ещё не выполнена", style = MaterialTheme.typography.labelSmall) } }
        }
    }
}

