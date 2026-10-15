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
