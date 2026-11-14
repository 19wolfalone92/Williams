package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext

/** Account-wide USDT envelope; unknown data blocks NEW exposure only.
 * Mirrors futures_half_margin.py. No strategy or exchange mutation authority.
 * Repeated observations are a consistency fence, not an atomic exchange API.
 */
private val marginMath = MathContext(28)
private val marginZero = BigDecimal.ZERO
private fun marginRequire(ok: Boolean, detail: String) {
    check(ok) { "FUTURES_HALF_MARGIN_UNKNOWN:$detail" }
}
private fun marginNumber(value: Any?, signed: Boolean = false): BigDecimal {
    val result = if (value is String || value is Number) value.toString().toBigDecimalOrNull() else null
    marginRequire(result != null && (signed || result.signum() >= 0), "NUMBER")
    return result!!
}
private fun marginRows(value: JSONArray?): List<JSONObject> {
    marginRequire(value != null, "ROWS")
    return (0 until value!!.length()).map {
        value.optJSONObject(it) ?: error("FUTURES_HALF_MARGIN_UNKNOWN:ROWS")
    }
}
private fun marginFlag(row: JSONObject, key: String): Boolean {
    val value = row.opt(key)
    marginRequire(value is Boolean || value == "true" || value == "false", "BOOLEAN:$key")
    return value == true || value == "true"
}
private fun marginSymbol(value: String): String {
    marginRequire(value.endsWith("USDT") && value.matches(Regex("[A-Za-z0-9]+")), "USDT_SYMBOL")
    return value
}
private fun marginLeverage(row: JSONObject): BigDecimal {
    val value = marginNumber(row.opt("leverage"))
    marginRequire(value >= BigDecimal.ONE && value <= BigDecimal(125) && value.stripTrailingZeros().scale() <= 0,
        "LEVERAGE")
    marginRequire(row.optString("marginType") in setOf("ISOLATED", "CROSSED"), "MARGIN_MODE")
    return value
}
private fun canonicalMargin(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

internal fun futuresHalfMarginLeaseFresh(startedNs: Long, nowNs: Long): Boolean =
    startedNs > 0L && nowNs >= startedNs && nowNs - startedNs <= 15_000_000_000L

internal data class HalfMarginOrder(
    val symbol: String = "", val side: String = "", val quantity: BigDecimal = marginZero,
    val executed: BigDecimal = marginZero, val orderId: String = "", val algo: Boolean = false,
    val reduction: Boolean = false
)
internal data class HalfMarginObservation(
    val base: BigDecimal, val available: BigDecimal, val confirmed: BigDecimal,
    val used: BigDecimal, val costNotional: BigDecimal, val identity: String,
    val orders: Map<String, HalfMarginOrder>, val symbols: Map<String, JSONObject>
)

internal fun futuresMarginObservation(account: JSONObject, configuration: JSONObject,
    orders: JSONArray, symbols: Map<String, JSONObject>): HalfMarginObservation {
    marginRequire(configuration.opt("canTrade") == true && configuration.opt("dualSidePosition") == false &&
        configuration.opt("multiAssetsMargin") == false, "ACCOUNT_MODE")
    for (field in listOf("canTrade", "dualSidePosition", "multiAssetsMargin")) {
        if (account.has(field)) marginRequire(account.opt(field) is Boolean && account.opt(field) == configuration.opt(field),
            "ACCOUNT_MODE_CONFLICT")
    }
    val wallet = marginNumber(account.opt("totalWalletBalance"))
    val pnl = marginNumber(account.opt("totalUnrealizedProfit"), true)
    val equity = marginNumber(account.opt("totalMarginBalance"))
    val available = marginNumber(account.opt("availableBalance"))
    marginRequire(wallet.signum() > 0 && equity.signum() > 0 &&
        (wallet + pnl - equity).abs() <= BigDecimal("0.00000001"), "EQUITY")
    val rootInitial = marginNumber(account.opt("totalInitialMargin"))
    val rootPositions = marginNumber(account.opt("totalPositionInitialMargin"))
    val rootOrders = marginNumber(account.opt("totalOpenOrderInitialMargin"))
    val assets = marginRows(account.optJSONArray("assets"))
    marginRequire(assets.map { it.optString("asset") }.toSet().size == assets.size, "DUPLICATE_ASSET")
    val usdt = assets.singleOrNull { it.optString("asset") == "USDT" }
    marginRequire(usdt != null, "USDT_ASSET")
    for (row in assets.filter { it.optString("asset") != "USDT" }) {
        for (key in listOf("walletBalance", "unrealizedProfit", "initialMargin"))
            marginRequire(marginNumber(row.opt(key), true).signum() == 0, "FOREIGN_ASSET")
    }
    marginRequire((marginNumber(usdt!!.opt("walletBalance")) - wallet).abs() <= BigDecimal("0.00000001"), "USDT_WALLET")
    val assetInitial = marginNumber(usdt.opt("initialMargin"))
    val assetPositions = marginNumber(usdt.opt("positionInitialMargin"))
    val assetOrders = marginNumber(usdt.opt("openOrderInitialMargin"))
    var positionInitial = marginZero
    var isolatedExtra = marginZero
    val positionIdentity = mutableListOf<String>()
    val seenPositions = mutableSetOf<String>()
    for (row in marginRows(account.optJSONArray("positions"))) {
        val symbol = marginSymbol(row.optString("symbol"))
        marginRequire(seenPositions.add(symbol) && row.optString("positionSide") == "BOTH", "POSITION_IDENTITY")
        val amount = marginNumber(row.opt("positionAmt"), true)
        val initial = marginNumber(row.opt("initialMargin"))
        val isolatedWallet = marginNumber(row.opt("isolatedWallet"))
        val isolatedMargin = marginNumber(row.opt("isolatedMargin"))
        marginRequire(amount.signum() == 0 || initial.signum() > 0 || isolatedWallet.signum() > 0, "POSITION_MARGIN")
        positionInitial += initial
        isolatedExtra += maxOf(marginZero, maxOf(isolatedWallet, isolatedMargin) - initial)
        positionIdentity += JSONArray().put(symbol).put(canonicalMargin(amount)).put(canonicalMargin(isolatedWallet))
            .put(row.opt("updateTime")).toString()
    }
    val positionMargin = maxOf(rootPositions, assetPositions, positionInitial) + isolatedExtra
    var pending = marginZero
    var costNotional = marginZero
    val orderIdentity = mutableListOf<String>()
    val byClient = linkedMapOf<String, HalfMarginOrder>()
    val seenIds = mutableSetOf<String>()
    for (row in marginRows(orders)) {
        val symbol = marginSymbol(row.optString("symbol"))
        marginRequire(row.optString("positionSide") == "BOTH" && row.optString("side") in setOf("BUY", "SELL"), "ORDER_SIDE")
        val algo = row.has("algoId")
        val identifier = row.optString(if (algo) "algoId" else "orderId")
        val client = row.optString(if (algo) "clientAlgoId" else "clientOrderId")
        marginRequire(identifier !in setOf("", "0", "null") && client.isNotBlank(), "ORDER_IDENTITY")
        val identity = JSONArray().put(if (algo) "ALGO" else "ORDER").put(symbol).put(identifier).toString()
        marginRequire(seenIds.add(identity) && !byClient.containsKey(client), "DUPLICATE_ORDER")
        val reduce = marginFlag(row, "reduceOnly")
        val close = if (algo) marginFlag(row, "closePosition") else false
        val status = row.optString(if (algo) "algoStatus" else "status")
        marginRequire(status in if (algo) setOf("NEW") else setOf("NEW", "PARTIALLY_FILLED"), "ORDER_TRANSITION")
        if (algo) marginRequire(row.optString("actualOrderId") in setOf("", "0"), "ALGO_CHILD_UNRESOLVED")
        if (reduce || close) {
            orderIdentity += JSONArray().put(identity).put(client).put(row.optString("side")).put(status).put("REDUCTION").toString()
            byClient[client] = HalfMarginOrder(reduction = true)
            continue
        }
        val quantity = marginNumber(row.opt(if (algo) "quantity" else "origQty"))
        val executed = if (algo) marginZero else marginNumber(row.opt("executedQty"))
        marginRequire(quantity.signum() > 0 && executed >= marginZero && executed < quantity, "ORDER_QUANTITY")
        val config = symbols[symbol]
        marginRequire(config != null && config.optString("symbol") == symbol, "SYMBOL_CONFIGURATION")
        val leverage = marginLeverage(config!!)
        val mark = marginNumber(config.opt("mark_price"))
        val rawPrice = marginNumber(row.opt(if (algo) "triggerPrice" else "price"))
        val price = maxOf(rawPrice, mark)
        marginRequire(mark.signum() > 0 && price.signum() > 0, "ORDER_PRICE")
        val notional = (quantity - executed) * price * BigDecimal("1.0015")
        pending += notional.divide(leverage, marginMath)
        costNotional += notional
        byClient[client] = HalfMarginOrder(symbol, row.getString("side"), quantity, executed, identifier, algo)
        orderIdentity += JSONArray().put(identity).put(client).put(row.getString("side")).put(status)
            .put(canonicalMargin(quantity)).put(canonicalMargin(executed)).put(canonicalMargin(rawPrice))
            .put(canonicalMargin(leverage)).put(config.getString("marginType")).toString()
    }
    val confirmedOrders = maxOf(rootOrders, assetOrders)
    val confirmed = maxOf(rootInitial, assetInitial, positionMargin + confirmedOrders)
    val used = maxOf(rootInitial, assetInitial, positionMargin + maxOf(confirmedOrders, pending))
    val identity = JSONArray().put(canonicalMargin(wallet)).put(JSONArray(positionIdentity.sorted()))
        .put(JSONArray(orderIdentity.sorted())).toString()
    return HalfMarginObservation(minOf(wallet, equity), available, confirmed, used, costNotional, identity, byClient, symbols)
}

internal fun assessFuturesHalfMargin(first: HalfMarginObservation, second: HalfMarginObservation,
    proposal: JSONObject, liabilities: JSONArray = JSONArray(), elapsedMs: Long = 0L): JSONObject {
    marginRequire(elapsedMs in 0L..15_000L, "STALE")
    marginRequire(first.identity == second.identity, "READS_CHANGED")
    val symbol = marginSymbol(proposal.optString("symbol"))
    val client = proposal.optString("client_id")
    marginRequire(client.isNotBlank(), "PROPOSAL_IDENTITY")
    marginRequire(!first.orders.containsKey(client) && !second.orders.containsKey(client), "PROPOSAL_ALREADY_PRESENT")
    marginRequire(proposal.optString("side") in setOf("BUY", "SELL"), "PROPOSAL_SIDE")
    val quantity = marginNumber(proposal.opt("quantity"))
    val price = marginNumber(proposal.opt("price"))
    val expectedLeverage = marginNumber(proposal.opt("leverage"))
    marginRequire(quantity.signum() > 0 && price.signum() > 0, "PROPOSAL_QUANTITY_PRICE")
    var boundPrice = price
    for (observation in listOf(first, second)) {
        val config = observation.symbols[symbol]
        marginRequire(config != null && config.optString("symbol") == symbol, "PROPOSAL_CONFIGURATION")
        val leverage = marginLeverage(config!!)
        marginRequire(config.getString("marginType") == "ISOLATED" && leverage.compareTo(expectedLeverage) == 0, "PROPOSAL_LEVERAGE_CHANGED")
        val mark = marginNumber(config.opt("mark_price"))
        marginRequire(mark.signum() > 0, "PROPOSAL_MARK")
        boundPrice = maxOf(boundPrice, mark)
    }
    val seen = mutableSetOf<String>()
    val claimedOrders = mutableSetOf<Triple<String, Boolean, String>>()
    for (liability in marginRows(liabilities)) {
        val cid = liability.optString("client_id")
        marginRequire(cid.isNotBlank() && seen.add(cid), "LIABILITY_IDENTITY")
        if (cid == client) {
            marginRequire(liability.opt("current_proposal") == true, "LIABILITY_PROPOSAL")
            continue
        }
        val status = liability.optString("status")
        marginRequire(status !in setOf("SUBMISSION_UNKNOWN", "AMBIGUOUS", "TRIGGERING", "TRIGGERED",
            "CANCEL_SUBMISSION_UNKNOWN", "RECONCILE_REQUIRED", ""), "LIABILITY_UNRESOLVED")
        if (status == "FILLED") {
            marginRequire(liability.opt("fill_confirmed") == true, "FILL_UNPROVEN")
            continue
        }
        if (status in setOf("CANCELED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED")) {
            marginRequire(liability.opt("terminal_confirmed") == true, "TERMINAL_UNPROVEN")
            continue
        }
        marginRequire(status in setOf("NEW", "PARTIALLY_FILLED"), "LIABILITY_STATUS")
        for ((index, observation) in listOf(first, second).withIndex()) {
            var actual = observation.orders[cid]
            if (actual == null && liability.optString("actual_order_id").isNotBlank()) {
                actual = observation.orders.values.singleOrNull { !it.reduction && !it.algo &&
                    it.orderId == liability.optString("actual_order_id") && it.symbol == liability.optString("symbol") }
                marginRequire(actual != null, "LIABILITY_CHILD_UNPROVEN")
            }
            marginRequire(actual != null && !actual.reduction, "LIABILITY_MISSING")
            if (index == 0) marginRequire(claimedOrders.add(Triple(actual!!.symbol, actual.algo, actual.orderId)),
                "LIABILITY_DUPLICATE_CLAIM")
            marginRequire(actual!!.symbol == liability.optString("symbol") && actual.side == liability.optString("side") &&
                actual.quantity.compareTo(marginNumber(liability.opt("quantity"))) == 0, "LIABILITY_MISMATCH")
            if (liability.has("executed")) marginRequire(actual.executed.compareTo(marginNumber(liability.opt("executed"))) == 0,
                "LIABILITY_FILL_CHANGED")
        }
    }
    val notional = quantity * boundPrice * BigDecimal("1.0015")
    val newMargin = notional.divide(expectedLeverage, marginMath)
    val costs = (maxOf(first.costNotional, second.costNotional) + notional) * BigDecimal("0.0035")
    val used = maxOf(first.used, second.used)
    val confirmed = maxOf(first.confirmed, second.confirmed)
    val limit = minOf(first.base, second.base).divide(BigDecimal(2), marginMath)
    val projected = used + newMargin + costs
    val allowed = projected <= limit && newMargin + costs <= minOf(first.available, second.available) * BigDecimal("0.95")
    return JSONObject().put("schema", "WILLIAMS_HALF_MARGIN_V1").put("allowed", allowed)
        .put("blocker", if (allowed) "" else "FUTURES_HALF_MARGIN_EXCEEDED")
        .put("limit_usdt", limit.toPlainString()).put("confirmed_usdt", confirmed.toPlainString())
        .put("residual_usdt", maxOf(marginZero, used - confirmed).toPlainString())
        .put("new_margin_usdt", newMargin.toPlainString()).put("cost_buffer_usdt", costs.toPlainString())
        .put("projected_usdt", projected.toPlainString()).put("elapsed_ms", elapsedMs)
}
