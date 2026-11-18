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
 * Every TRADE execution is scoped to the (symbol, orderId) pair.
 * Binance Futures order IDs may overlap across independent symbols.
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

    private data class OrderKey(val symbol:String,val id:String)
    private val orders = mutableMapOf<OrderKey, Mutable>()
    private fun key(symbol:String,orderId:String):OrderKey? =
        if(symbol.isNotBlank() && orderId.isNotBlank()) OrderKey(symbol,orderId) else null

    @Synchronized
    fun accept(event: JSONObject, exitPriceForUnknownFee: BigDecimal? = null): Snapshot? {
        val payload = normalizedExecutionPayload(event) ?: return null
        val orderId = payload.optString("i").ifBlank { return null }
        if (payload.optString("x").uppercase() != "TRADE")
            return snapshot(orderId,payload.optString("s"))
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
        val identity=key(symbol,orderId) ?: return null
        // A malformed/zero fill is not a trade. Preserve the last valid
        // accumulator instead of silently fabricating a fill and VWAP.
        if (eventTime <= 0L || qty.signum() <= 0 || price.signum() <= 0)
            return snapshot(orderId,symbol)
        val state = orders.getOrPut(identity) { Mutable(symbol = symbol) }
        // Binance tradeId is a stable fill identity; event time may change
        // on WS replay. Deduplicating (tradeId,time) double-counted such fills.
        // Old anonymous -1 receipts retain the best observable time identity.
        val stableTradeId=privateBinanceTradeHasStableId("TRADE",tradeId)
        val key = if(stableTradeId) "$orderId:TRADE:$tradeId"
            else "$orderId:LEGACY:$eventTime"
        if (!state.seen.add(key)) return snapshot(orderId,symbol)
        // Unknown/anonymous trade ID has no stable deduplication across
        // changed Binance WS timestamps. Position/fees are provisional
        // until independently signed REST fill history is reconciled.
        if(!stableTradeId) state.feeKnown=false

        state.qty = state.qty.add(qty)
        state.quote = state.quote.add(qty.multiply(price))
        state.fills += 1

        if (commission > BigDecimal.ZERO) {
            when {
                commissionAsset == "USDT" ->
                    state.feeUsdt = state.feeUsdt.add(commission)
                commissionAsset == symbol.removeSuffix("USDT") ->
                    state.feeUsdt = state.feeUsdt.add(commission.multiply(price))
                commissionAsset.isBlank() -> state.feeKnown = false
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
        return snapshot(orderId,symbol)
    }

    @Synchronized
    fun restore(orderId: String, fills: List<JSONObject>, symbol:String?=null): Snapshot? {
        // A second market can have the SAME numeric Binance order ID. A
        // missing ETH journal must NEVER erase already-restored BTC fills.
        if(fills.isEmpty()) {
            if(symbol!=null) clear(orderId,symbol)
            return null // legacy caller without symbol cannot clear any data
        }
        val payloads=fills.map { normalizedExecutionPayload(it) ?: return null }
        val symbols=payloads.map { it.optString("s") }.toSet()
        if(symbols.size!=1 || symbols.single().isBlank() ||
            (symbol!=null && symbols.single()!=symbol) ||
            payloads.any { it.optString("i")!=orderId })
            return null // mixed/wrong-symbol replay never wipes existing state
        val identity=key(symbols.single(),orderId) ?: return null
        // Durable journal also includes NEW, CANCELED, EXPIRED and other
        // order-status receipts. Those are NOT fills. Rejecting a history
        // merely because NEW preceded TRADE discarded ALL restored fills.
        val tradePayloads=payloads.filter { it.optString("x").uppercase()=="TRADE" }
        if(tradePayloads.isEmpty()) {
            if(symbol!=null) orders.remove(identity)
            return null
        }
        // Validate the ENTIRE set before replacing a live in-memory state:
        // a corrupt final row must not erase previously reconstructed fills.
        for (fill in tradePayloads) {
            val q=fill.optString("l").toBigDecimalOrNull()
            val p=fill.optString("L").toBigDecimalOrNull()
            val t=fill.optLong("T",fill.optLong("E",0L))
            if(q==null || q.signum()<=0 || p==null || p.signum()<=0 || t<=0L)
                return null
        }
        val candidate=ExecutionAccumulator()
        for(fill in tradePayloads) {
            if(candidate.accept(fill)==null) return null
        }
        val staged=candidate.orders[identity] ?: return null
        orders[identity]=staged
        return snapshot(orderId,symbols.single())
    }

    @Synchronized
    fun snapshot(orderId: String,symbol:String?=null): Snapshot? {
        // Historical orderId-only lookups are accepted solely when unique.
        val state = if(symbol!=null) {
            val identity=key(symbol,orderId) ?: return null
            orders[identity] ?: return null
        } else {
            val matches=orders.filterKeys { it.id==orderId }.values
            if(matches.size!=1) return null
            matches.single()
        }
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
    fun clear(orderId: String,symbol:String?=null) {
        if(symbol!=null) key(symbol,orderId)?.let { orders.remove(it) }
        else orders.keys.singleOrNull { it.id==orderId }?.let { orders.remove(it) }
    }

    private fun decimal(value: String): BigDecimal =
        value.toBigDecimalOrNull() ?: BigDecimal.ZERO
}
