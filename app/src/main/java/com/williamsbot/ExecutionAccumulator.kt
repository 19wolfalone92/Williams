package com.williamsbot

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/**
 * Persistent-friendly execution accumulator.
 *
 * Every TRADE execution is accumulated by orderId using exact decimal
 * arithmetic. Cumulative quantity/quote and VWAP are derived from fills,
 * rather than trusting a single websocket message.
 */
class ExecutionAccumulator {
    data class Snapshot(
        val orderId: String,
        val symbol: String,
        val executedQty: BigDecimal,
        val executedQuote: BigDecimal,
        val vwap: BigDecimal,
        val feeUsdt: BigDecimal,
        val feeKnown: Boolean,
        val fills: Int
    )

    private data class Mutable(
        var symbol: String = "",
        var qty: BigDecimal = BigDecimal.ZERO,
        var quote: BigDecimal = BigDecimal.ZERO,
        var feeUsdt: BigDecimal = BigDecimal.ZERO,
        var feeKnown: Boolean = true,
        var fills: Int = 0,
        val seen: MutableSet<String> = mutableSetOf()
    )

    private val orders = mutableMapOf<String, Mutable>()

    @Synchronized
    fun accept(event: JSONObject, exitPriceForUnknownFee: BigDecimal? = null): Snapshot? {
        val orderId = event.optString("i").ifBlank { return null }
        val symbol = event.optString("s")
        val executionType = event.optString("x").uppercase()
        if (executionType != "TRADE") return snapshot(orderId)

        val tradeId = event.optString("t", "-1")
        val eventTime = event.optLong("E", 0L)
        val key = "$orderId:$tradeId:$eventTime"
        val state = orders.getOrPut(orderId) { Mutable(symbol = symbol) }
        if (!state.seen.add(key)) return snapshot(orderId)

        val qty = decimal(event.optString("l"))
        val price = decimal(event.optString("L"))
        state.qty = state.qty.add(qty)
        state.quote = state.quote.add(qty.multiply(price))
        state.fills += 1

        val commission = decimal(event.optString("n"))
        val asset = event.optString("N")
        if (commission > BigDecimal.ZERO) {
            when {
                asset == "USDT" -> state.feeUsdt = state.feeUsdt.add(commission)
                asset == symbol.removeSuffix("USDT") ->
                    state.feeUsdt = state.feeUsdt.add(
                        commission.multiply(price)
                    )
                asset.isBlank() -> Unit
                else -> {
                    state.feeKnown = false
                    if (exitPriceForUnknownFee != null) {
                        state.feeUsdt = state.feeUsdt.add(
                            commission.multiply(exitPriceForUnknownFee)
                        )
                    }
                }
            }
        }
        return snapshot(orderId)
    }

    @Synchronized
    fun restore(orderId: String, fills: List<JSONObject>): Snapshot? {
        orders.remove(orderId)
        var result: Snapshot? = null
        fills.forEach { result = accept(it) }
        return result
    }

    @Synchronized
    fun snapshot(orderId: String): Snapshot? {
        val state = orders[orderId] ?: return null
        val vwap =
            if (state.qty > BigDecimal.ZERO)
                state.quote.divide(state.qty, 18, RoundingMode.HALF_UP)
            else BigDecimal.ZERO
        return Snapshot(
            orderId = orderId,
            symbol = state.symbol,
            executedQty = state.qty,
            executedQuote = state.quote,
            vwap = vwap,
            feeUsdt = state.feeUsdt,
            feeKnown = state.feeKnown,
            fills = state.fills
        )
    }

    @Synchronized
    fun clear(orderId: String) {
        orders.remove(orderId)
    }

    private fun decimal(value: String): BigDecimal =
        value.toBigDecimalOrNull() ?: BigDecimal.ZERO
}
