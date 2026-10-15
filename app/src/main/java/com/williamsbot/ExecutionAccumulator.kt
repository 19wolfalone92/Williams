package com.williamsbot

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/**
 * Normalize Binance spot-style executionReport and USD-M Futures
 * ORDER_TRADE_UPDATE (whose execution fields are nested under "o").
 * The original event is retained separately in the durable audit journal.
 */
internal fun normalizedExecutionPayload(event: JSONObject): JSONObject? {
    val eventType = event.optString("e")
    return when {
        eventType == "ORDER_TRADE_UPDATE" -> {
            val order = event.optJSONObject("o") ?: return null
            JSONObject(order.toString()).apply {
                if (!has("E")) put("E", event.optLong("E", optLong("T", 0L)))
                if (!has("T")) put("T", event.optLong("T", optLong("E", 0L)))
            }
        }
        eventType == "executionReport" -> JSONObject(event.toString())
        // Backward compatibility for locally stored normalized/legacy execution
        // payloads which predate the event-type marker.
        eventType.isBlank() && event.has("i") && event.has("x") ->
            JSONObject(event.toString())
        else -> null
    }
}

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
        val payload = normalizedExecutionPayload(event) ?: return null
        val orderId = payload.optString("i").ifBlank { return null }
        if (payload.optString("x").uppercase() != "TRADE") return snapshot(orderId)
        return acceptFill(
            orderId = orderId,
            symbol = payload.optString("s"),
            tradeId = payload.optString("t", "-1"),
            eventTime = payload.optLong("T", payload.optLong("E", 0L)),
            qty = decimal(payload.optString("l")),
            price = decimal(payload.optString("L")),
            commission = decimal(payload.optString("n")),
            commissionAsset = payload.optString("N"),
            exitPriceForUnknownFee = exitPriceForUnknownFee
        )
    }

    @Synchronized
    fun acceptFill(
        orderId: String,
        symbol: String,
        tradeId: String,
        eventTime: Long,
        qty: BigDecimal,
        price: BigDecimal,
        commission: BigDecimal = BigDecimal.ZERO,
        commissionAsset: String = "",
        exitPriceForUnknownFee: BigDecimal? = null
    ): Snapshot? {
        if (orderId.isBlank()) return null
        val state = orders.getOrPut(orderId) { Mutable(symbol = symbol) }
        val key = "$orderId:$tradeId:$eventTime"
        if (!state.seen.add(key)) return snapshot(orderId)

        state.qty = state.qty.add(qty)
        state.quote = state.quote.add(qty.multiply(price))
        state.fills += 1

        if (commission > BigDecimal.ZERO) {
            when {
                commissionAsset == "USDT" ->
                    state.feeUsdt = state.feeUsdt.add(commission)
                commissionAsset == symbol.removeSuffix("USDT") ->
                    state.feeUsdt = state.feeUsdt.add(commission.multiply(price))
                commissionAsset.isBlank() -> Unit
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
