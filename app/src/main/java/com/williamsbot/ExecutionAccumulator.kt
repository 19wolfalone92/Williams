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

    private fun storageKey(symbol: String, orderId: String): String =
        "${symbol.trim().uppercase()}:$orderId"

    @Synchronized
    fun accept(event: JSONObject, exitPriceForUnknownFee: BigDecimal? = null): Snapshot? {
        val orderId = event.optString("i").ifBlank { return null }
        val symbol = event.optString("s").trim().uppercase()
        if (event.optString("x").uppercase() != "TRADE") return snapshot(symbol, orderId)
        return acceptFill(
            orderId = orderId,
            symbol = symbol,
            tradeId = event.optString("t", "-1"),
            eventTime = event.optLong("E", 0L),
            qty = decimal(event.optString("l")),
            price = decimal(event.optString("L")),
            commission = decimal(event.optString("n")),
            commissionAsset = event.optString("N"),
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
        val normalizedSymbol = symbol.trim().uppercase()
        val stateKey = storageKey(normalizedSymbol, orderId)
        val state = orders.getOrPut(stateKey) { Mutable(symbol = normalizedSymbol) }
        if (
            tradeId.isBlank() || tradeId == "-1" ||
            normalizedSymbol.isBlank() ||
            qty <= BigDecimal.ZERO || price <= BigDecimal.ZERO ||
            commission < BigDecimal.ZERO
        ) {
            state.feeKnown = false
            return snapshot(normalizedSymbol, orderId)
        }

        // Binance trade IDs are stable across duplicate event delivery; event time
        // is not part of identity because a replay may be timestamped differently.
        if (!state.seen.add(tradeId)) return snapshot(normalizedSymbol, orderId)

        state.qty = state.qty.add(qty)
        state.quote = state.quote.add(qty.multiply(price))
        state.fills += 1

        val feeAsset = commissionAsset.trim().uppercase()
        if (feeAsset.isBlank()) {
            state.feeKnown = false
        } else if (commission > BigDecimal.ZERO) {
            when {
                feeAsset == "USDT" ->
                    state.feeUsdt = state.feeUsdt.add(commission)
                feeAsset == normalizedSymbol.removeSuffix("USDT") ->
                    state.feeUsdt = state.feeUsdt.add(commission.multiply(price))
                else -> {
                    state.feeKnown = false
                    // A caller-supplied price may be useful diagnostically, but it
                    // does not make an unverified third-asset valuation authoritative.
                    if (exitPriceForUnknownFee != null &&
                        exitPriceForUnknownFee > BigDecimal.ZERO
                    ) {
                        state.feeUsdt = state.feeUsdt.add(
                            commission.multiply(exitPriceForUnknownFee)
                        )
                    }
                }
            }
        }
        return snapshot(normalizedSymbol, orderId)
    }

    @Synchronized
    fun restore(symbol: String, orderId: String, fills: List<JSONObject>): Snapshot? {
        val normalizedSymbol = symbol.trim().uppercase()
        orders.remove(storageKey(normalizedSymbol, orderId))
        fills.forEach { event ->
            if (
                event.optString("i") == orderId &&
                event.optString("s").trim().uppercase() == normalizedSymbol
            ) {
                accept(event)
            }
        }
        return snapshot(normalizedSymbol, orderId)
    }

    @Synchronized
    fun snapshot(symbol: String, orderId: String): Snapshot? {
        val normalizedSymbol = symbol.trim().uppercase()
        val state = orders[storageKey(normalizedSymbol, orderId)] ?: return null
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
    fun clear(symbol: String, orderId: String) {
        orders.remove(storageKey(symbol, orderId))
    }

    private fun decimal(value: String): BigDecimal =
        value.toBigDecimalOrNull() ?: BigDecimal.ZERO
}
