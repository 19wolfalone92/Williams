package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

internal const val MAX_CONFIGURED_FUTURES_LEVERAGE = 125
// Separate safety cap for NEW Williams Demo exposure; configuration and
// recovery of older exchange positions remain backward compatible.
internal const val WILLIAMS_NEW_ENTRY_MAX_LEVERAGE = 2

internal fun parseFuturesLeverage(value: Any?): Int {
    val text = value?.toString()?.trim().orEmpty()
    require(text.matches(Regex("[0-9]+"))) { "Плечо должно быть целым числом от 1 до 125" }
    return requireNotNull(text.toIntOrNull()?.takeIf { it in 1..MAX_CONFIGURED_FUTURES_LEVERAGE }) {
        "Плечо должно быть целым числом от 1 до 125"
    }
}

internal data class FuturesLeverageSettings(
    val defaultLeverage: Int = 2,
    val symbolLeverages: Map<String, Int> = emptyMap()
) {
    init {
        parseFuturesLeverage(defaultLeverage)
        require(symbolLeverages.keys.all { it in BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS }) {
            "Настройка плеча содержит неизвестную пару"
        }
        symbolLeverages.values.forEach { parseFuturesLeverage(it) }
    }

    fun forSymbol(symbol: String): Int = symbolLeverages[symbol] ?: defaultLeverage

    fun toJson(): JSONObject = JSONObject()
        .put("default_leverage", defaultLeverage)
        .put("symbol_leverages", JSONObject(symbolLeverages))

    fun resolvedJson(): JSONObject = JSONObject().apply {
        BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS.forEach { put(it, forSymbol(it)) }
    }

    companion object {
        fun fromJson(json: JSONObject): FuturesLeverageSettings {
            val default = parseFuturesLeverage(json.get("default_leverage"))
            val overrides = json.getJSONObject("symbol_leverages")
            val parsed = overrides.keys().asSequence().associateWith { parseFuturesLeverage(overrides.get(it)) }
            return FuturesLeverageSettings(default, parsed)
        }
    }
}

/** Conservative admission check, not a liquidation-price prediction. */
internal fun futuresLeverageBlocker(
    payload: Any,
    symbol: String,
    leverage: Int,
    notional: Double,
    entry: Double,
    stop: Double,
    costFraction: Double = 0.0035,
    liquidationBuffer: Double = 0.01
): String? = runCatching {
    parseFuturesLeverage(leverage)
    require(listOf(notional, entry, stop, costFraction, liquidationBuffer).all { it.isFinite() } &&
        notional > 0.0 && entry > 0.0 && stop > 0.0 && costFraction >= 0.0 && liquidationBuffer >= 0.0) {
        "Invalid Futures leverage risk inputs"
    }
    val rows = if (payload is JSONArray) payload else JSONArray().put(payload)
    val matches = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        .filter { it.optString("symbol") == symbol }
    require(matches.size == 1) { "Missing or ambiguous Futures leverage bracket symbol" }
    val tiers = matches.single().getJSONArray("brackets")
    require(tiers.length() > 0) { "Missing Futures leverage brackets" }
    val stopNotional = notional * stop / entry
    val worstNotional = max(notional, stopNotional)
    require(worstNotional.isFinite()) { "Invalid Futures leverage exposure" }
    var previousCap = 0.0
    var worstMmr = 0.0
    var selectedMax: Int? = null
    var selectedMmr = 0.0
    for (index in 0 until tiers.length()) {
        val tier = tiers.getJSONObject(index)
        val floor = tier.getDouble("notionalFloor")
        val cap = tier.getDouble("notionalCap")
        val mmr = tier.getDouble("maintMarginRatio")
        val maximumText = tier.get("initialLeverage").toString()
        val maximum = maximumText.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
        require(listOf(floor, cap, mmr).all { it.isFinite() } && floor == previousCap &&
            cap > floor && mmr >= 0.0 && mmr < 1.0 && maximum != null && maximum > 0) {
            "Invalid Futures leverage bracket limits"
        }
        worstMmr = max(worstMmr, mmr)
        if (worstNotional >= floor && worstNotional < cap) {
            selectedMax = maximum
            selectedMmr = worstMmr
        }
        previousCap = cap
    }
    val maximum = requireNotNull(selectedMax) { "FUTURES_LEVERAGE_NOTIONAL_CAP: exposure exceeds exchange brackets" }
    require(leverage <= maximum) { "FUTURES_LEVERAGE_LIMIT: $symbol allows at most ${maximum}x for this exposure" }
    val lossAndCosts = notional * (abs(entry - stop) / entry + costFraction)
    require(notional / leverage > lossAndCosts + worstNotional * (selectedMmr + liquidationBuffer)) {
        "FUTURES_LEVERAGE_STOP_BUFFER: isolated margin cannot cover structural stop, costs and maintenance"
    }
}.exceptionOrNull()?.let { it.message ?: "Invalid Futures leverage brackets" }
