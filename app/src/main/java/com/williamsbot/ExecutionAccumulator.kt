package com.williamsbot

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/**
 * Persistent-friendly execution accumulator.
 *
 * Trade identity is scoped by (symbol, orderId, tradeId), not event timestamp.
 * Missing or contradictory commission data is never silently treated as zero.
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
        val seen: MutableMap<String, String> = mutableMapOf()
    )

    private val orders = mutableMapOf<String, Mutable>()

    private fun storageKey(symbol: String, orderId: String): String =
        "${symbol.trim().uppercase()}:$orderId"

    @Synchronized
    fun accept(event: JSONObject, exitPriceForUnknownFee: BigDecimal? = null): Snapshot? {
        val orderId = event.optString("i").ifBlank { return null }
        val symbol = event.optString("s").trim().uppercase()
        if (event.optString("x").uppercase() != "TRADE") return snapshot(symbol, orderId)
        val commissionRaw = event.optString("n")
        val commission = commissionRaw.toBigDecimalOrNull()
        return acceptFill(
            orderId = orderId,
            symbol = symbol,
            tradeId = event.optString("t", "-1"),
            eventTime = event.optLong("E", 0L),
            qty = decimal(event.optString("l")),
            price = decimal(event.optString("L")),
            commission = commission ?: BigDecimal.ZERO,
            commissionAsset = event.optString("N"),
            exitPriceForUnknownFee = exitPriceForUnknownFee,
            commissionKnown = commission != null
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
        exitPriceForUnknownFee: BigDecimal? = null,
        commissionKnown: Boolean = true
    ): Snapshot? {
        if (orderId.isBlank()) return null
        val normalizedSymbol = symbol.trim().uppercase()
        val state = orders.getOrPut(storageKey(normalizedSymbol, orderId)) {
            Mutable(symbol = normalizedSymbol)
        }
        if (
            tradeId.isBlank() || tradeId == "-1" ||
            normalizedSymbol.isBlank() ||
            qty <= BigDecimal.ZERO || price <= BigDecimal.ZERO ||
            commission < BigDecimal.ZERO || eventTime <= 0L
        ) {
            state.feeKnown = false
            return snapshot(normalizedSymbol, orderId)
        }

        val feeAsset = commissionAsset.trim().uppercase()
        val fingerprint = "$qty|$price|$commission|$feeAsset"
        val previous = state.seen[tradeId]
        if (previous != null) {
            if (previous != fingerprint || !commissionKnown || feeAsset.isBlank()) {
                state.feeKnown = false
            }
            return snapshot(normalizedSymbol, orderId)
        }
        state.seen[tradeId] = fingerprint

        state.qty = state.qty.add(qty)
        state.quote = state.quote.add(qty.multiply(price))

        if (!commissionKnown || feeAsset.isBlank()) {
            state.feeKnown = false
        } else if (commission > BigDecimal.ZERO) {
            when {
                feeAsset == "USDT" ->
                    state.feeUsdt = state.feeUsdt.add(commission)
                feeAsset == normalizedSymbol.removeSuffix("USDT") ->
                    state.feeUsdt = state.feeUsdt.add(commission.multiply(price))
                else -> {
                    state.feeKnown = false
                    // Preserve the original third-asset commission in the event
                    // ledger. An exit/current price is not historical conversion
                    // evidence; feeUsdt stays unresolved and feeKnown stays false.
                }
            }
        }
        state.fills += 1
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
    fun restore(orderId: String, fills: List<JSONObject>): Snapshot? {
        val symbol = fills.firstOrNull()?.optString("s")?.trim()?.uppercase().orEmpty()
        if (symbol.isBlank()) return null
        return restore(symbol, orderId, fills)
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
    fun snapshot(orderId: String): Snapshot? {
        val matches = orders.keys.filter { it.endsWith(":$orderId") }
        if (matches.size != 1) return null
        val state = orders[matches.single()] ?: return null
        return snapshot(state.symbol, orderId)
    }

    @Synchronized
    fun clear(symbol: String, orderId: String) {
        orders.remove(storageKey(symbol, orderId))
    }

    @Synchronized
    fun clear(orderId: String) {
        orders.keys.filter { it.endsWith(":$orderId") }.toList().forEach { orders.remove(it) }
    }

    private fun decimal(value: String): BigDecimal =
        value.toBigDecimalOrNull() ?: BigDecimal.ZERO
}
