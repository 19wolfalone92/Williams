package com.williamsbot

internal data class DirectionalTradeMetrics(
    val direction: String,
    val entrySide: String,
    val grossPnlUsdt: Double,
    val netPnlUsdt: Double,
    val initialRiskUsdt: Double,
    val rMultiple: Double,
    val outcome: String
)

/**
 * Trade accounting must respect the opened direction. A SHORT earns when exit is
 * below entry; a LONG earns when exit is above entry. Risk is the persisted initial
 * risk reservation, not the subsequently trailed stop distance.
 */
internal fun calculateDirectionalTradeMetrics(
    direction: String,
    entryPrice: Double,
    exitPrice: Double,
    quantity: Double,
    feeUsdt: Double,
    initialRiskUsdt: Double
): DirectionalTradeMetrics {
    require(direction == "LONG" || direction == "SHORT") { "Trade direction must be LONG or SHORT" }
    require(entryPrice.isFinite() && entryPrice > 0.0) { "Trade entry price is invalid" }
    require(exitPrice.isFinite() && exitPrice > 0.0) { "Trade exit price is invalid" }
    require(quantity.isFinite() && quantity > 0.0) { "Trade quantity is invalid" }
    require(feeUsdt.isFinite() && feeUsdt >= 0.0) { "Trade fees are invalid" }
    require(initialRiskUsdt.isFinite() && initialRiskUsdt > 0.0) { "Initial trade risk is invalid" }

    val priceMove = if (direction == "LONG") exitPrice - entryPrice else entryPrice - exitPrice
    val gross = priceMove * quantity
    val net = gross - feeUsdt
    require(gross.isFinite() && net.isFinite()) { "Trade PnL arithmetic is not finite" }
    val rMultiple = net / initialRiskUsdt
    val outcome = when {
        net > 0.0 -> "WIN"
        net < 0.0 -> "LOSS"
        else -> "BREAKEVEN"
    }
    return DirectionalTradeMetrics(
        direction = direction,
        entrySide = if (direction == "LONG") "BUY" else "SELL",
        grossPnlUsdt = gross,
        netPnlUsdt = net,
        initialRiskUsdt = initialRiskUsdt,
        rMultiple = rMultiple,
        outcome = outcome
    )
}


/** Reconstructed, exchange-reported USD-M campaign fills; null means evidence is incomplete. */
internal data class FuturesCampaignTradeSummary(
    val symbol: String,
    val direction: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val grossPnlUsdt: Double,
    val feeUsdt: Double,
    val feeKnown: Boolean,
    val netPnlUsdt: Double,
    val notionalUsdt: Double,
    val initialRiskUsdt: Double,
    val rMultiple: Double,
    val openedAtMs: Long,
    val closedAtMs: Long,
    val fillCount: Int
)

/**
 * Reconstruct a campaign from authenticated Binance USD-M userTrades data.
 * Works for LONG and SHORT and supports multiple entry/add-on fills; it refuses
 * to fabricate a result if filled quantities, trade timestamps, or PnL are incomplete.
 */
internal fun summarizeFuturesCampaignTrades(
    symbol: String,
    direction: String,
    openedAtMs: Long,
    closedAtMs: Long,
    userTrades: org.json.JSONArray,
    initialRiskUsdt: Double
): FuturesCampaignTradeSummary? {
    if (symbol.isBlank() || direction !in setOf("LONG", "SHORT") ||
        openedAtMs <= 0L || closedAtMs < openedAtMs ||
        !initialRiskUsdt.isFinite() || initialRiskUsdt <= 0.0
    ) return null

    fun number(row: org.json.JSONObject, key: String): Double? {
        val raw = row.opt(key) ?: return null
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }
        return value?.takeIf { it.isFinite() }
    }

    var entryQty = 0.0
    var entryQuote = 0.0
    var exitQty = 0.0
    var exitQuote = 0.0
    var grossRealizedPnl = 0.0
    var feeUsdt = 0.0
    var feeKnown = true
    var fillCount = 0
    var firstEntryFillTime = Long.MAX_VALUE
    var lastExitFillTime = 0L
    val baseAsset = symbol.removeSuffix("USDT")

    for (i in 0 until userTrades.length()) {
        val row = userTrades.optJSONObject(i) ?: return null
        if (!row.optString("symbol").equals(symbol, ignoreCase = true)) continue
        val time = row.optLong("time", -1L)
        if (time < openedAtMs || time > closedAtMs) continue
        val quantity = number(row, "qty") ?: return null
        val price = number(row, "price") ?: return null
        val realized = number(row, "realizedPnl") ?: return null
        val commission = number(row, "commission") ?: return null
        val buyer = row.opt("buyer") as? Boolean ?: return null
        val positionSide = row.optString("positionSide", "BOTH").uppercase()
        if (positionSide.isNotBlank() && positionSide != "BOTH") return null
        if (quantity <= 0.0 || price <= 0.0) return null
        val isEntryFill = if (direction == "LONG") buyer else !buyer
        if (isEntryFill) {
            entryQty += quantity
            entryQuote += quantity * price
            firstEntryFillTime = minOf(firstEntryFillTime, time)
        } else {
            exitQty += quantity
            exitQuote += quantity * price
            lastExitFillTime = maxOf(lastExitFillTime, time)
        }
        grossRealizedPnl += realized
        val commissionAsset = row.optString("commissionAsset")
        when {
            commission == 0.0 -> Unit
            commissionAsset == "USDT" -> feeUsdt += commission
            commissionAsset == baseAsset -> feeUsdt += commission * price
            else -> feeKnown = false
        }
        fillCount++
    }

    if (fillCount == 0 || entryQty <= 0.0 || exitQty <= 0.0 ||
        firstEntryFillTime == Long.MAX_VALUE || lastExitFillTime < firstEntryFillTime ||
        !entryQty.isFinite() || !exitQty.isFinite() ||
        !entryQuote.isFinite() || !exitQuote.isFinite() ||
        !grossRealizedPnl.isFinite() || !feeUsdt.isFinite()
    ) return null
    val qtyTolerance = maxOf(1e-10, maxOf(entryQty, exitQty) * 1e-8)
    if (kotlin.math.abs(entryQty - exitQty) > qtyTolerance) return null

    val entryPrice = entryQuote / entryQty
    val exitPrice = exitQuote / exitQty
    val notionalUsdt = entryQuote
    val netPnlUsdt = grossRealizedPnl - feeUsdt
    val rMultiple = netPnlUsdt / initialRiskUsdt
    if (listOf(entryPrice, exitPrice, notionalUsdt, netPnlUsdt, rMultiple).any { !it.isFinite() } ||
        entryPrice <= 0.0 || exitPrice <= 0.0
    ) return null

    return FuturesCampaignTradeSummary(
        symbol = symbol,
        direction = direction,
        entryPrice = entryPrice,
        exitPrice = exitPrice,
        quantity = entryQty,
        grossPnlUsdt = grossRealizedPnl,
        feeUsdt = feeUsdt,
        feeKnown = feeKnown,
        netPnlUsdt = netPnlUsdt,
        notionalUsdt = notionalUsdt,
        initialRiskUsdt = initialRiskUsdt,
        rMultiple = rMultiple,
        openedAtMs = firstEntryFillTime,
        closedAtMs = lastExitFillTime,
        fillCount = fillCount
    )
}
