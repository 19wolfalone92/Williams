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
    var profile by remember(status.strategyProfile) { mutableStateOf(status.strategyProfile) }
    var profilesExpanded by remember { mutableStateOf(false) }
    var selectedSymbol by remember { mutableStateOf("BTCUSDT") }
    var symbolsExpanded by remember { mutableStateOf(false) }
    val payload = runCatching {
        FuturesLeverageSettings(
            parseFuturesLeverage(defaultText),
            overrides.filterValues { it.isNotBlank() }.mapValues { parseFuturesLeverage(it.value) }
        ).toJson().put("strategy_profile",profile)
    }.getOrNull()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Стратегия и плечо Futures", style = MaterialTheme.typography.titleMedium)
            Text("Общее плечо и отдельное для каждой пары • LONG и SHORT • ISOLATED",
                style = MaterialTheme.typography.bodySmall)
            Text("Плечо фиксируется при подготовке входа. Binance подтвердит его перед ордером; лимит биржи, маржа и структурный стоп могут заблокировать вход.",
                style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick={ profilesExpanded=true }) { Text("Книга: "+when(profile) {
                    williamsNtdProfile -> "Новые измерения"
                    williamsCourseProfile -> "Profitunity Course"
                    else -> "Торговый хаос 2"
                }) }
                DropdownMenu(expanded=profilesExpanded,onDismissRequest={ profilesExpanded=false }) {
                    williamsEditionProfiles.forEach { edition ->
                        DropdownMenuItem(text={ Text(when(edition) {
                            williamsNtdProfile -> "NTD · пять измерений"
                            williamsCourseProfile -> "Course · Hump, Thumb, Air Bag"
                            else -> "TC2 · три мудреца"
                        }) },onClick={ profile=edition;profilesExpanded=false })
                    }
                }
            }
            Text("Книга меняется после сверки Binance, без позиций и ожидающих входов. Стопы открытой кампании следуют выбранной при входе книге.",style=MaterialTheme.typography.bodySmall)
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
