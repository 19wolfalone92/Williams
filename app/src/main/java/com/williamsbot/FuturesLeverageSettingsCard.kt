package com.williamsbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun FuturesLeverageSettingsCard(status: Status, onSave: (JSONObject) -> Unit) {
    val loaded = remember(status.futuresLeverageSettingsJson) {
        runCatching { FuturesLeverageSettings.fromJson(JSONObject(status.futuresLeverageSettingsJson)) }.getOrNull()
    }
    var defaultText by remember(status.futuresLeverageSettingsJson) {
        mutableStateOf(loaded?.defaultLeverage?.toString().orEmpty())
    }
    var overrides by remember(status.futuresLeverageSettingsJson) {
        mutableStateOf(loaded?.symbolLeverages?.mapValues { it.value.toString() }.orEmpty())
    }
    // The selector of mutually exclusive books is retired. Changing the
    // runtime selection still requires a flat, reconciled Futures account.
    val loadedTime=remember(status.entryTimePolicyJson) { runCatching { WilliamsEntryTimePolicy.fromJson(JSONObject(status.entryTimePolicyJson)) }.getOrDefault(WilliamsEntryTimePolicy()) }
    var timeMode by remember(status.entryTimePolicyJson) { mutableStateOf(loadedTime.mode) }
    var startUtc by remember(status.entryTimePolicyJson) { mutableStateOf(loadedTime.startUtc) }
    var endUtc by remember(status.entryTimePolicyJson) { mutableStateOf(loadedTime.endUtc) }
    val timePolicy=runCatching { WilliamsEntryTimePolicy(timeMode,startUtc,endUtc) }.getOrNull()
    var selectedSymbol by remember { mutableStateOf("BTCUSDT") }
    var symbolsExpanded by remember { mutableStateOf(false) }
    val payload = runCatching {
        FuturesLeverageSettings(
            parseFuturesLeverage(defaultText),
            overrides.filterValues { it.isNotBlank() }.mapValues { parseFuturesLeverage(it.value) }
        ).toJson().put("strategy_profile",williamsUnifiedProfile).put("entry_time_policy",requireNotNull(timePolicy).toJson())
    }.getOrNull()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Стратегия и плечо Futures", style = MaterialTheme.typography.titleMedium)
            Text("Общее плечо и отдельное для каждой пары • LONG и SHORT • ISOLATED",
                style = MaterialTheme.typography.bodySmall)
            Text("Плечо фиксируется при подготовке входа. Binance подтвердит его перед ордером; лимит биржи, маржа и структурный стоп могут заблокировать вход.",
                style = MaterialTheme.typography.bodySmall)
            Text("Единая стратегия Вильямса",style=MaterialTheme.typography.titleSmall)
            Text("Торговый хаос 1/2: контекст, волны и три мудреца • Новые измерения: фракталы, AO, AC, зона и Balance Line • Course: Hump, Thumb, Squat и Air Bag.",
                style=MaterialTheme.typography.bodySmall)
            Text("Система анализирует все источники одновременно, но каждый ордер сохраняет конкретный источник и его защитные правила. Встречные подтверждённые входы блокируются. Активация единого режима — только без позиций и ожидающих заявок, после сверки Binance.",
                style=MaterialTheme.typography.bodySmall)
            if(status.strategyProfile!=williamsUnifiedProfile) Text(
                "Сейчас включён старый профиль. Нажми «Сохранить настройки», чтобы перейти на единую стратегию при безопасном состоянии счёта.",
                style=MaterialTheme.typography.bodySmall)
            Text("Время новых входов",style=MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected=timeMode=="ALWAYS",onClick={ timeMode="ALWAYS" },label={ Text("Круглосуточно") })
                androidx.compose.material3.FilterChip(selected=timeMode=="UTC_WINDOW",onClick={ timeMode="UTC_WINDOW" },label={ Text("Окно UTC") })
            }
            if(timeMode=="UTC_WINDOW") {
                OutlinedTextField(value=startUtc,onValueChange={ startUtc=it },label={ Text("Начало UTC · HH:MM") },singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=endUtc,onValueChange={ endUtc=it },label={ Text("Конец UTC · HH:MM") },singleLine=true,modifier=Modifier.fillMaxWidth())
                if(timePolicy==null) Text("Укажи разные времена в формате HH:MM",color=MaterialTheme.colorScheme.error)
            }
            Text("Окно ограничивает только новые входы и добавления. Защита, выходы и сверка работают круглосуточно. Ожидающие входы вне выбранного окна отменяются с подтверждением Binance.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = defaultText, onValueChange = { defaultText = it },
                label = { Text("Общее плечо, 1–125×") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Box {
                OutlinedButton(onClick = { symbolsExpanded = true }) { Text("Пара: $selectedSymbol ▾") }
                DropdownMenu(expanded = symbolsExpanded, onDismissRequest = { symbolsExpanded = false }) {
                    BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS.forEach { symbol ->
                        val draft = overrides[symbol]?.takeIf { it.isNotBlank() } ?: defaultText
                        DropdownMenuItem(text = { Text("$symbol · ${draft}×") }, onClick = {
                            selectedSymbol = symbol
                            symbolsExpanded = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = overrides[selectedSymbol].orEmpty(),
                onValueChange = { overrides = overrides + (selectedSymbol to it) },
                label = { Text("$selectedSymbol • пусто = общее") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Text("Риск на вход и общий лимит риска сохраняются. Настройка применяется к следующим входам.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { payload?.let(onSave) }, enabled = loaded != null && payload != null,
                modifier = Modifier.fillMaxWidth()) { Text("СОХРАНИТЬ НАСТРОЙКИ") }
        }
    }
}
