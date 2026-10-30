package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone


private val ENTRY_ORDER_ACTIVE_STATUSES = setOf(
    "NEW", "PENDING", "WORKING", "PARTIALLY_FILLED", "SUBMISSION_UNKNOWN",
    "ACCEPTED", "TRIGGERING", "TRIGGERED", "CANCEL_SUBMISSION_UNKNOWN"
)
internal val ENTRY_ORDER_TERMINAL_STATUSES = setOf(
    "FILLED", "FINISHED", "EXPIRED", "EXPIRED_IN_MATCH", "CANCELED", "CANCELLED", "REJECTED"
)
internal fun hasUnresolvedTriggeringEntry(rows:JSONArray):Boolean =
    (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.any {
        it.optString("intent_role","ENTRY") in setOf("ENTRY","ADD_ON") &&
            campaignLifecycleStatus(it)!="CLOSED_OR_STOPPED" &&
            exchangeOrderStatus(it) in setOf("TRIGGERING","TRIGGERED") &&
            campaignLifecycleStatus(it)!="OPEN_PROTECTED"
    }
private val ACTIVE_CAMPAIGN_STATUSES = setOf(
    "ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "POSITION_OPEN", "OPEN_PROTECTED", "EXIT_PENDING"
)
private val TERMINAL_CAMPAIGN_STATUSES = setOf("CLOSED_OR_STOPPED")
internal const val FUTURES_MAX_ENTRY_RISK_PCT = 0.0025
internal const val FUTURES_MAX_PORTFOLIO_RISK_PCT = 0.006

/** Missing/unknown algo status is never proof of an active exchange-side stop. */
internal fun isActiveFuturesProtectionStatus(status: String): Boolean =
    status.trim().uppercase(Locale.US) in setOf("NEW", "WORKING")


/** Exact exchange proof required before an open position can be called protected. */
internal fun matchesConfirmedFuturesProtectiveStop(
    order: JSONObject,
    direction: String,
    expectedClientId: String,
    expectedStop: String,
    expectedSymbol: String? = null
): Boolean {
    if (direction != "LONG" && direction != "SHORT") return false
    if (expectedClientId.isBlank()) return false
    // A stop on another Binance symbol or in hedge/contract-price mode cannot
    // prove protection of this one-way MARK_PRICE campaign. Absent optional
    // exchange fields remain tolerated for legacy test doubles; supplied fields
    // must never conflict with the signed contract.
    if (expectedSymbol != null &&
        (expectedSymbol.isBlank() || order.optString("symbol") != expectedSymbol)) return false
    val actualPositionSide = order.optString("positionSide").uppercase(Locale.US)
    if (actualPositionSide.isNotBlank() && actualPositionSide != "BOTH") return false
    val actualWorkingType = order.optString("workingType").uppercase(Locale.US)
    if (actualWorkingType.isNotBlank() && actualWorkingType != "MARK_PRICE") return false
    val expected = expectedStop.toBigDecimalOrNull() ?: return false
    val type = order.optString("type", order.optString("orderType")).uppercase(Locale.US)
    if (type != "STOP_MARKET") return false
    if (!order.optString("closePosition").equals("true", ignoreCase = true)) return false
    val side = order.optString("side").uppercase(Locale.US)
    if (direction == "LONG" && side != "SELL") return false
    if (direction == "SHORT" && side != "BUY") return false
    val status = order.optString("algoStatus").ifBlank { order.optString("status") }
    if (!isActiveFuturesProtectionStatus(status)) return false
    val clientId = order.optString("clientAlgoId")
    val actual = order.optString("triggerPrice").ifBlank { order.optString("stopPrice") }
        .toBigDecimalOrNull() ?: return false
    return clientId == expectedClientId && actual.compareTo(expected) == 0
}

/** A terminal parent is not proof of missing protection if it already spawned
 * a child order. Bind the entire former STOP identity before granting the
 * separately revalidated emergency reduction capability. */
internal fun matchesTerminalUntriggeredFuturesProtectiveStop(
    order: JSONObject?, symbol: String, direction: String,
    expectedClientId: String, expectedStop: String
): Boolean {
    if (order == null || symbol.isBlank() || expectedClientId.isBlank() ||
        direction !in setOf("LONG", "SHORT")) return false
    val expected = expectedStop.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return false
    val actual = order.optString("triggerPrice").ifBlank { order.optString("stopPrice") }
        .toBigDecimalOrNull() ?: return false
    return order.optString("symbol") == symbol &&
        order.optString("clientAlgoId") == expectedClientId &&
        order.optString("orderType").ifBlank { order.optString("type") } == "STOP_MARKET" &&
        order.optString("side") == (if (direction == "LONG") "SELL" else "BUY") &&
        order.optString("positionSide", "BOTH") == "BOTH" &&
        order.optString("closePosition").equals("true", true) &&
        order.optString("workingType") == "MARK_PRICE" && actual.compareTo(expected) == 0 &&
        order.optString("algoStatus").uppercase(Locale.US) in setOf("CANCELED", "REJECTED", "EXPIRED") &&
        order.has("actualOrderId") && !order.isNull("actualOrderId") &&
        order.optString("actualOrderId") in setOf("", "0")
}


/**
 * Independent pre-mutation risk assessment. Recomputes the quantity's stop risk
 * at the coordinator boundary instead of trusting the scanner's sizing calculation.
 */
internal data class FuturesEntryRiskCheck(
    val blocker: String?,
    val notionalUsdt: Double = 0.0,
    val grossStopLossUsdt: Double = 0.0,
    val estimatedFeesAndSlippageUsdt: Double = 0.0,
    val estimatedAllInLossUsdt: Double = 0.0,
    val estimatedAllInRiskPct: Double = 0.0,
    val initialMarginUsdt: Double = 0.0
)

internal fun assessFuturesEntryRisk(
    direction: String,
    quantity: String,
    entryPrice: String,
    protectiveStop: String,
    equityUsdt: Double,
    availableUsdt: Double,
    riskBudgetPct: Double,
    feeBufferPerSidePct: Double = 0.001,
    maxSlippagePct: Double = 0.0015,
    leverage: Double = 2.0,
    availableMarginBufferPct: Double = 0.95
): FuturesEntryRiskCheck {
    fun blocked(reason: String) = FuturesEntryRiskCheck(blocker = reason)
    if (direction != "LONG" && direction != "SHORT") {
        return blocked("Futures risk check requires LONG or SHORT direction")
    }
    val qty = quantity.toDoubleOrNull()
    val entry = entryPrice.toDoubleOrNull()
    val stop = protectiveStop.toDoubleOrNull()
    if (qty == null || entry == null || stop == null ||
        !qty.isFinite() || !entry.isFinite() || !stop.isFinite() ||
        qty <= 0.0 || entry <= 0.0 || stop <= 0.0
    ) return blocked("Futures risk check received invalid quantity/entry/structural stop")
    if (if (direction == "LONG") stop >= entry else stop <= entry) {
        return blocked("$direction structural stop is on the wrong side of entry")
    }
    if (!equityUsdt.isFinite() || equityUsdt <= 0.0 ||
        !availableUsdt.isFinite() || availableUsdt <= 0.0
    ) return blocked("Fresh Futures equity/available USDT is unknown or non-positive")
    if (!riskBudgetPct.isFinite() || riskBudgetPct <= 0.0 ||
        riskBudgetPct > FUTURES_MAX_ENTRY_RISK_PCT
    ) return blocked("Futures risk reservation exceeds the 0.25% per-entry cap or is invalid")
    if (!feeBufferPerSidePct.isFinite() || feeBufferPerSidePct < 0.0 ||
        !maxSlippagePct.isFinite() || maxSlippagePct < 0.0 ||
        !leverage.isFinite() || leverage < 1.0 || leverage > MAX_CONFIGURED_FUTURES_LEVERAGE || leverage % 1.0 != 0.0 ||
        !availableMarginBufferPct.isFinite() || availableMarginBufferPct <= 0.0 ||
        availableMarginBufferPct > 1.0
    ) return blocked("Futures fee/slippage/margin risk assumptions are invalid")

    val notional = qty * entry
    val grossStopLoss = qty * kotlin.math.abs(entry - stop)
    val feesAndSlippage = notional * (2.0 * feeBufferPerSidePct + maxSlippagePct)
    val allInLoss = grossStopLoss + feesAndSlippage
    val allInRiskPct = allInLoss / equityUsdt
    val initialMargin = notional / leverage
    if (listOf(notional, grossStopLoss, feesAndSlippage, allInLoss, allInRiskPct, initialMargin)
            .any { !it.isFinite() || it < 0.0 }
    ) return blocked("Futures risk arithmetic overflowed or became non-finite")

    val assessment = FuturesEntryRiskCheck(
        blocker = null,
        notionalUsdt = notional,
        grossStopLossUsdt = grossStopLoss,
        estimatedFeesAndSlippageUsdt = feesAndSlippage,
        estimatedAllInLossUsdt = allInLoss,
        estimatedAllInRiskPct = allInRiskPct,
        initialMarginUsdt = initialMargin
    )
    if (allInRiskPct > riskBudgetPct + 1e-12) {
        return assessment.copy(
            blocker = "FUTURES_RISK_BUDGET_EXCEEDED: estimated stop + fees + slippage " +
                "is ${allInRiskPct * 100.0}% of equity, above reserved ${riskBudgetPct * 100.0}%"
        )
    }
    if (initialMargin > availableUsdt * availableMarginBufferPct + 1e-9) {
        return assessment.copy(
            blocker = "FUTURES_MARGIN_BUDGET_EXCEEDED: estimated initial margin exceeds " +
                "the buffered available USDT balance"
        )
    }
    return assessment
}

internal data class FuturesActualPositionRiskCheck(
    val blocker: String?,
    val grossStopLossUsdt: Double = 0.0,
    val estimatedFeesAndSlippageUsdt: Double = 0.0,
    val estimatedAllInLossUsdt: Double = 0.0,
    val actualAllInRiskPct: Double = 0.0,
    val reservedRiskUsdt: Double = 0.0
)

/**
 * A confirmed fill may differ slightly from the planned entry because of exchange
 * execution. Reconcile the reservation to measured stop+cost risk only when the
 * per-entry and portfolio caps still hold and every other active reservation is known.
 */
internal fun actualRiskReservationAdjustmentBlocker(
    actualRiskPct: Double,
    otherActiveRiskPcts: Iterable<Double>,
    maxEntryRiskPct: Double = FUTURES_MAX_ENTRY_RISK_PCT,
    maxPortfolioRiskPct: Double = FUTURES_MAX_PORTFOLIO_RISK_PCT
): String? {
    if (!actualRiskPct.isFinite() || actualRiskPct <= 0.0) {
        return "Actual Futures risk is invalid; reservation cannot be reconciled"
    }
    if (!maxEntryRiskPct.isFinite() || maxEntryRiskPct <= 0.0 ||
        !maxPortfolioRiskPct.isFinite() || maxPortfolioRiskPct <= 0.0
    ) return "Futures risk limits are invalid; reservation cannot be reconciled"
    if (actualRiskPct > maxEntryRiskPct + 1e-9) {
        return "ACTUAL_FUTURES_RISK_EXCEEDS_ENTRY_CAP: actual all-in risk exceeds the 0.25% per-entry cap"
    }
    val otherRisks = otherActiveRiskPcts.toList()
    if (otherRisks.any { !it.isFinite() || it <= 0.0 || it > maxEntryRiskPct }) {
        return "ACTUAL_FUTURES_RISK_ADJUSTMENT_BLOCKED: another active campaign has an unknown or invalid risk reservation"
    }
    val portfolioRisk = otherRisks.sum() + actualRiskPct
    if (!portfolioRisk.isFinite() || portfolioRisk > maxPortfolioRiskPct + 1e-9) {
        return "ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP: measured risk cannot be reserved without exceeding the 0.60% portfolio cap"
    }
    return null
}

/**
 * Reconcile actual exchange position quantity/entry against the original structural
 * stop. This catches gap/slippage or later quantity changes that a preflight estimate
 * cannot prove. A missing original stop/reservation fails closed.
 */
internal fun assessActualFuturesPositionRisk(
    direction: String,
    quantity: Double,
    actualEntryPrice: Double,
    originalStructuralStop: Double,
    equityUsdt: Double,
    reservedRiskPct: Double,
    feeBufferPerSidePct: Double = 0.001,
    maxSlippagePct: Double = 0.0015
): FuturesActualPositionRiskCheck {
    fun blocked(reason: String) = FuturesActualPositionRiskCheck(blocker = reason)
    if (direction !in setOf("LONG", "SHORT")) return blocked("Actual Futures risk direction is invalid")
    if (!quantity.isFinite() || quantity <= 0.0 ||
        !actualEntryPrice.isFinite() || actualEntryPrice <= 0.0 ||
        !originalStructuralStop.isFinite() || originalStructuralStop <= 0.0
    ) return blocked("Actual Futures quantity/entry/original structural stop is invalid")
    if (!equityUsdt.isFinite() || equityUsdt <= 0.0) {
        return blocked("Actual Futures equity is unavailable; risk cannot be reconciled")
    }
    if (!reservedRiskPct.isFinite() || reservedRiskPct <= 0.0 ||
        reservedRiskPct > FUTURES_MAX_ENTRY_RISK_PCT
    ) return blocked("Persisted Futures risk reservation is missing, invalid, or exceeds the 0.25% cap")
    if (!feeBufferPerSidePct.isFinite() || feeBufferPerSidePct < 0.0 ||
        !maxSlippagePct.isFinite() || maxSlippagePct < 0.0
    ) return blocked("Actual Futures fee/slippage assumptions are invalid")
    if (direction == "LONG" && originalStructuralStop >= actualEntryPrice) {
        return blocked("LONG original structural stop is not below actual exchange entry")
    }
    if (direction == "SHORT" && originalStructuralStop <= actualEntryPrice) {
        return blocked("SHORT original structural stop is not above actual exchange entry")
    }

    val notional = quantity * actualEntryPrice
    val grossStopLoss = quantity * kotlin.math.abs(actualEntryPrice - originalStructuralStop)
    val feesAndSlippage = notional * (2.0 * feeBufferPerSidePct + maxSlippagePct)
    val allInLoss = grossStopLoss + feesAndSlippage
    val actualRiskPct = allInLoss / equityUsdt
    val reservedRiskUsdt = equityUsdt * reservedRiskPct
    if (listOf(notional, grossStopLoss, feesAndSlippage, allInLoss, actualRiskPct, reservedRiskUsdt)
            .any { !it.isFinite() || it < 0.0 }
    ) return blocked("Actual Futures risk arithmetic became invalid")
    val result = FuturesActualPositionRiskCheck(
        blocker = null,
        grossStopLossUsdt = grossStopLoss,
        estimatedFeesAndSlippageUsdt = feesAndSlippage,
        estimatedAllInLossUsdt = allInLoss,
        actualAllInRiskPct = actualRiskPct,
        reservedRiskUsdt = reservedRiskUsdt
    )
    if (allInLoss > reservedRiskUsdt + 1e-9) {
        return result.copy(
            blocker = "ACTUAL_FUTURES_RISK_EXCEEDS_RESERVATION: " +
                "actual stop+fee+slippage loss ${allInLoss} USDT exceeds reserved ${reservedRiskUsdt} USDT"
        )
    }
    return result
}

/**
 * Read-only evidence for continuing ONLY risk-reducing management when confirmed
 * live stop+execution costs breach the portfolio cap. This is not reconciliation
 * success and MUST NOT authorize any entry or add-on.
 */
internal fun verifiedProtectedRiskCapManagement(
    blocker: String?,
    positions: JSONArray,
    intents: JSONArray,
    nowMs: Long
): Boolean {
    if (blocker?.startsWith("ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP:") != true ||
        nowMs <= 0L || positions.length() == 0) return false
    val open = linkedMapOf<String, String>()
    for (i in 0 until positions.length()) {
        val row = positions.optJSONObject(i) ?: return false
        val symbol = row.optString("symbol").trim().uppercase(Locale.US)
        val qty = row.optString("positionAmt").toDoubleOrNull() ?: return false
        if (symbol.isBlank() || !qty.isFinite()) return false
        if (qty == 0.0) continue
        if (open.put(symbol, if (qty > 0.0) "LONG" else "SHORT") != null) return false
    }
    if (open.isEmpty()) return false
    val rows = (0 until intents.length()).map { intents.optJSONObject(it) ?: return false }
    // Ambiguous entries, exits and additions may race a protection replacement.
    if (rows.any {
        val lifecycle = campaignLifecycleStatus(it)
        (it.optString("intent_role", "ENTRY") == "ENTRY" &&
            lifecycle in setOf("ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "POSITION_OPEN", "EXIT_PENDING")) ||
        (it.optString("intent_role") == "EXIT" &&
            it.optString("exchange_status").uppercase(Locale.US) in ENTRY_ORDER_ACTIVE_STATUSES) ||
        (it.optString("intent_role") == "ADD_ON" &&
            exchangeOrderStatus(it) in ENTRY_ORDER_ACTIVE_STATUSES)
    }) return false
    val active = rows.filter {
        it.optString("intent_role", "ENTRY") == "ENTRY" &&
            campaignLifecycleStatus(it) == "OPEN_PROTECTED"
    }
    if (active.size != open.size || active.map { it.optString("symbol").uppercase(Locale.US) }.toSet() != open.keys)
        return false
    for (entry in active) {
        val symbol = entry.optString("symbol").uppercase(Locale.US)
        if (open[symbol] != entry.optString("direction") ||
            !entry.optBoolean("strategy_ownership_verified", false) ||
            entry.optString("protection_status") != "PROTECTED" ||
            entry.optString("protection_client_id").isBlank() ||
            entry.optString("previous_protection_client_id").isNotBlank() ||
            entry.optString("liquidation_guard_status") != "PASS") return false
        val stop = entry.optString("protective_stop").toDoubleOrNull()
        val risk = entry.optDouble("actual_all_in_risk_pct", Double.NaN)
        val auditedAt = entry.optLong("actual_risk_audited_at_ms", 0L)
        val status = entry.optString("actual_risk_audit_status")
        if (stop == null || !stop.isFinite() || stop <= 0.0 ||
            !risk.isFinite() || risk <= 0.0 ||
            auditedAt <= 0L || auditedAt > nowMs || nowMs - auditedAt > 30_000L ||
            status !in setOf("PASS", "PASS_RESERVATION_ADJUSTED", "BLOCKED")) return false
        if (status == "BLOCKED" &&
            !entry.optString("actual_risk_audit_blocker")
                .startsWith("ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP:")) return false
    }
    return true
}

/** Sum each admitted entry exactly once while its primary campaign remains active. */
internal fun futuresCampaignRiskComponents(owner:JSONObject,all:JSONArray):List<Double> {
    if(!isEntryCampaignReserved(owner)) return emptyList()
    val components=mutableListOf(conservativeRiskReservation(owner.optDouble("risk_pct",Double.NaN),.006))
    for(i in 0 until all.length()) {
        val child=all.optJSONObject(i) ?: error("Malformed Futures risk journal")
        if(child.optString("intent_role")!="ADD_ON" || child.optString("entry_client_id")!=owner.optString("client_id")) continue
        val status=exchangeOrderStatus(child)
        val executed=child.optString("executed_qty","0").toBigDecimalOrNull() ?: error("Malformed child fill reservation")
        if(status !in ENTRY_ORDER_TERMINAL_STATUSES || executed.signum()>0 || status=="FILLED")
            components+=conservativeRiskReservation(child.optDouble("risk_pct",Double.NaN),.006)
    }
    return components
}
internal fun futuresCampaignReservedRisk(owner:JSONObject,all:JSONArray):Double=futuresCampaignRiskComponents(owner,all).sum()

internal fun assessActualFuturesCampaignRisk(direction:String,quantity:Double,entry:Double,stop:Double,
    equity:Double,reserved:Double):FuturesActualPositionRiskCheck {
    if(direction !in setOf("LONG","SHORT") || listOf(quantity,entry,stop,equity,reserved).any { !it.isFinite() || it<=0 } || reserved>.006)
        return FuturesActualPositionRiskCheck(blocker="Invalid aggregate campaign risk inputs")
    val gross=quantity*maxOf(0.0,if(direction=="LONG") entry-stop else stop-entry)
    val fees=quantity*entry*.0035;val loss=gross+fees;val risk=loss/equity;val budget=equity*reserved
    return FuturesActualPositionRiskCheck(
        blocker=if(loss>budget+1e-9) "ACTUAL_FUTURES_CAMPAIGN_RISK_EXCEEDS_RESERVATION" else null,
        grossStopLossUsdt=gross,estimatedFeesAndSlippageUsdt=fees,estimatedAllInLossUsdt=loss,
        actualAllInRiskPct=risk,reservedRiskUsdt=budget)
}

/** Structural H1 recovery result for a known bot-owned position whose stop journal is missing. */
internal data class RecoveredStructuralStop(
    val stopPrice: String,
    val riskPct: Double
)

internal fun campaignLifecycleStatus(intent: JSONObject): String {
    val explicit = intent.optString("campaign_status").uppercase(Locale.US)
    if (explicit.isNotBlank()) return explicit
    return when (val legacy = intent.optString("exchange_status").uppercase(Locale.US)) {
        "ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "POSITION_OPEN",
        "OPEN_PROTECTED", "EXIT_PENDING", "CLOSED_OR_STOPPED" -> legacy
        in ENTRY_ORDER_ACTIVE_STATUSES -> "ENTRY_PENDING"
        else -> ""
    }
}

internal fun exchangeOrderStatus(intent: JSONObject): String {
    val explicit = intent.optString("order_status").uppercase(Locale.US)
    if (explicit.isNotBlank()) return explicit
    val legacy = intent.optString("exchange_status").uppercase(Locale.US)
    return if (legacy in setOf(
        "ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "POSITION_OPEN",
        "OPEN_PROTECTED", "EXIT_PENDING", "CLOSED_OR_STOPPED"
    )) "" else legacy
}

/**
 * Only a definitive Binance "order not found" API response may be treated as absent.
 * Timeouts, connection failures, rate limits and server errors are never converted
 * into a flat/terminal campaign.
 */
internal fun isDefinitiveBinanceOrderNotFound(failure: Throwable): Boolean {
    val text = generateSequence(failure) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
    val notFound2013 = Regex("""\"code\"\s*:\s*-2013""").containsMatchIn(text)
    val unknown2011 = Regex("""\"code\"\s*:\s*-2011""").containsMatchIn(text) &&
        listOf("unknown order", "order does not exist", "order not found")
            .any { text.contains(it, ignoreCase = true) }
    return notFound2013 || unknown2011
}

internal fun latestEntryIntentIndex(intents: JSONArray, symbol: String): Int? {
    var latest: Int? = null
    for (i in 0 until intents.length()) {
        val intent = intents.optJSONObject(i)
            ?: throw IllegalStateException("Persisted Futures intent is malformed")
        if (intent.optString("intent_role", "ENTRY") == "ENTRY" &&
            intent.optString("symbol").equals(symbol, ignoreCase = true)
        ) latest = i
    }
    return latest
}

internal fun isOwnedClientOrderForSymbol(
    symbol: String,
    clientId: String,
    ownerIdsBySymbol: Map<String, Set<String>>
): Boolean = clientId.isNotBlank() &&
    ownerIdsBySymbol[symbol.uppercase(Locale.US)]?.contains(clientId) == true

/**
 * Validate entry quantity and prices against the live USD-M exchangeInfo filters.
 * Returns a blocker message; null means every required filter was present and passed.
 * Missing/malformed filters fail closed instead of sending a guessed quantity.
 */
internal fun futuresContractRuleBlocker(
    exchangeInfo: JSONObject,
    symbol: String,
    quantity: String,
    entryPrice: String,
    protectiveStop: String,
    marketOrder: Boolean = false
): String? {
    val symbols = exchangeInfo.optJSONArray("symbols")
        ?: return "Binance exchangeInfo has no symbols array"
    val contract = (0 until symbols.length()).mapNotNull { symbols.optJSONObject(it) }
        .firstOrNull { it.optString("symbol").equals(symbol, ignoreCase = true) }
        ?: return "$symbol is missing from Binance USD-M exchangeInfo"
    if (!contract.optString("status").equals("TRADING", ignoreCase = true)) {
        return "$symbol is not TRADING on Binance USD-M"
    }
    val filters = contract.optJSONArray("filters")
        ?: return "$symbol exchangeInfo has no filters"
    fun filter(type: String): JSONObject? =
        (0 until filters.length()).mapNotNull { filters.optJSONObject(it) }
            .firstOrNull { it.optString("filterType") == type }
    val lot = filter("LOT_SIZE")
        ?: return "$symbol is missing LOT_SIZE filter"
    val priceFilter = filter("PRICE_FILTER")
        ?: return "$symbol is missing PRICE_FILTER"
    val qty = quantity.toBigDecimalOrNull()
        ?: return "$symbol quantity is not numeric"
    val entry = entryPrice.toBigDecimalOrNull()
        ?: return "$symbol entry price is not numeric"
    val stop = protectiveStop.toBigDecimalOrNull()
        ?: return "$symbol protective stop is not numeric"
    val minQty = lot.optString("minQty").toBigDecimalOrNull()
        ?: return "$symbol LOT_SIZE minQty is invalid"
    val maxQty = lot.optString("maxQty").toBigDecimalOrNull()
        ?: return "$symbol LOT_SIZE maxQty is invalid"
    val step = lot.optString("stepSize").toBigDecimalOrNull()
        ?: return "$symbol LOT_SIZE stepSize is invalid"
    if (qty.signum() <= 0 || minQty.signum() <= 0 || maxQty < minQty || step.signum() <= 0) {
        return "$symbol LOT_SIZE limits or requested quantity are invalid"
    }
    if (qty < minQty || qty > maxQty || qty.remainder(step).signum() != 0) {
        return "$symbol quantity violates LOT_SIZE min/max/stepSize"
    }
    if (marketOrder) {
        val marketLot = filter("MARKET_LOT_SIZE")
            ?: return "$symbol is missing MARKET_LOT_SIZE filter for market-triggered execution"
        val marketMin = marketLot.optString("minQty").toBigDecimalOrNull()
            ?: return "$symbol MARKET_LOT_SIZE minQty is invalid"
        val marketMax = marketLot.optString("maxQty").toBigDecimalOrNull()
            ?: return "$symbol MARKET_LOT_SIZE maxQty is invalid"
        val marketStep = marketLot.optString("stepSize").toBigDecimalOrNull()
            ?: return "$symbol MARKET_LOT_SIZE stepSize is invalid"
        // Binance can publish a zero market step to indicate that this filter
        // is disabled; in that case LOT_SIZE remains the quantity authority.
        if (marketStep.signum() > 0) {
            if (marketMin.signum() < 0 || marketMax.signum() <= 0 || marketMax < marketMin ||
                qty < marketMin || qty > marketMax || qty.remainder(marketStep).signum() != 0
            ) {
                return "$symbol quantity violates MARKET_LOT_SIZE min/max/stepSize"
            }
        }
    }
    val tick = priceFilter.optString("tickSize").toBigDecimalOrNull()
        ?: return "$symbol PRICE_FILTER tickSize is invalid"
    val minPrice = priceFilter.optString("minPrice").toBigDecimalOrNull()
        ?: return "$symbol PRICE_FILTER minPrice is invalid"
    val maxPrice = priceFilter.optString("maxPrice").toBigDecimalOrNull()
        ?: return "$symbol PRICE_FILTER maxPrice is invalid"
    if (tick.signum() <= 0 || entry < minPrice || entry > maxPrice || stop < minPrice || stop > maxPrice ||
        entry.remainder(tick).signum() != 0 || stop.remainder(tick).signum() != 0
    ) {
        return "$symbol entry/stop price violates PRICE_FILTER tickSize/range"
    }
    val minNotionalFilter = filter("MIN_NOTIONAL") ?: filter("NOTIONAL")
    if (minNotionalFilter != null) {
        val rawMinimum = minNotionalFilter.optString("notional")
            .ifBlank { minNotionalFilter.optString("minNotional") }
        val minimum = rawMinimum.toBigDecimalOrNull()
            ?: return "$symbol minimum notional filter is invalid"
        val notional = qty.multiply(entry)
        if (notional < minimum) {
            return "$symbol entry notional is below exchange minimum"
        }
        // Binance may publish an upper notional bound as well. If present, enforce
        // it rather than assuming the local sizing cap is sufficient.
        val rawMaximum = minNotionalFilter.optString("maxNotional")
        if (rawMaximum.isNotBlank()) {
            val maximum = rawMaximum.toBigDecimalOrNull()
                ?: return "$symbol maximum notional filter is invalid"
            if (maximum.signum() > 0 && notional > maximum) {
                return "$symbol entry notional exceeds exchange maximum"
            }
        }
    }
    return null
}

internal fun hasValidStructuralStop(direction: String, entryPrice: String, stopPrice: String): Boolean {
    val entry = entryPrice.toBigDecimalOrNull() ?: return false
    val stop = stopPrice.toBigDecimalOrNull() ?: return false
    if (entry.signum() <= 0 || stop.signum() <= 0) return false
    return when (direction.uppercase(Locale.US)) {
        "LONG" -> stop < entry
        "SHORT" -> stop > entry
        else -> false
    }
}

/**
 * An active/trailing stop is valid relative to the live mark, not necessarily the
 * original entry. Long stops can move above entry and short stops below entry
 * while locking profit; they must still remain on the protective side of mark.
 */
internal fun isProtectiveStopOnCorrectSide(
    direction: String,
    markPrice: String,
    stopPrice: String
): Boolean {
    val mark = markPrice.toBigDecimalOrNull() ?: return false
    val stop = stopPrice.toBigDecimalOrNull() ?: return false
    if (mark.signum() <= 0 || stop.signum() <= 0) return false
    return when (direction.uppercase(Locale.US)) {
        "LONG" -> stop < mark
        "SHORT" -> stop > mark
        else -> false
    }
}

internal fun isEntryCampaignReserved(intent: JSONObject): Boolean {
    if (intent.optString("intent_role", "ENTRY") != "ENTRY") {
        // Only explicit exit confirmation releases an EXIT intent. FILLED alone is
        // insufficient because Binance position flatness must also be confirmed.
        return intent.optString("exchange_status").uppercase(Locale.US) != "EXIT_CONFIRMED"
    }
    // Unknown, malformed or legacy state remains reserved. Only an explicit
    // terminal campaign can release the one-campaign slot, whatever order says.
    return campaignLifecycleStatus(intent) !in TERMINAL_CAMPAIGN_STATUSES
}

/**
 * A single pending conditional order must not freeze the whole ten-symbol scanner.
 * Concurrent entry admission is allowed only when every existing entry is either
 * a confirmed live pending order or an exchange-confirmed protected position.
 * Ambiguous orders, pending fills, and unresolved exits keep the account fail-closed.
 */
internal fun confirmedPendingEntriesAllowConcurrentAdmission(intents: JSONArray): Boolean {
    val confirmedLiveEntryStatuses = setOf(
        "NEW", "PENDING", "WORKING", "PARTIALLY_FILLED", "ACCEPTED", "TRIGGERED"
    )
    var foundPendingEntry = false
    for (i in 0 until intents.length()) {
        val intent = intents.optJSONObject(i) ?: return false
        val role = intent.optString("intent_role", "ENTRY")
        if (role == "EXIT") {
            if (intent.optString("exchange_status").uppercase(Locale.US) != "EXIT_CONFIRMED") {
                return false
            }
            continue
        }
        if (role != "ENTRY") return false
        val lifecycle = campaignLifecycleStatus(intent)
        if (lifecycle in TERMINAL_CAMPAIGN_STATUSES) continue
        val orderStatus = exchangeOrderStatus(intent).uppercase(Locale.US)
        if (orderStatus in setOf("SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN", "EXIT_PENDING", "")) {
            return false
        }
        when (lifecycle) {
            "ENTRY_PENDING" -> {
                if (orderStatus !in confirmedLiveEntryStatuses) return false
                foundPendingEntry = true
            }
            "POSITION_OPEN", "OPEN_PROTECTED" -> {
                if (orderStatus !in confirmedLiveEntryStatuses &&
                    orderStatus !in ENTRY_ORDER_TERMINAL_STATUSES
                ) return false
            }
            else -> return false
        }
    }
    return foundPendingEntry
}


/**
 * Count occupied account-wide slots without double-counting an exchange position
 * and its corresponding persisted campaign. Unknown non-terminal ENTRY intents
 * reserve a slot until reconciliation proves the campaign terminal.
 */
internal fun reservedFuturesPositionSymbols(
    openPositionSymbols: Iterable<String>,
    intents: JSONArray
): Set<String> {
    val occupied = linkedSetOf<String>()
    openPositionSymbols.forEach { symbol ->
        symbol.trim().takeIf { it.isNotEmpty() }?.let { occupied += it.uppercase(Locale.US) }
    }
    for (i in 0 until intents.length()) {
        val intent = intents.optJSONObject(i)
            ?: throw IllegalStateException("Persisted Futures intent is malformed")
        if (intent.optString("intent_role", "ENTRY") != "ENTRY") continue
        if (!isEntryCampaignReserved(intent)) continue
        intent.optString("symbol").trim().takeIf { it.isNotEmpty() }
            ?.let { occupied += it.uppercase(Locale.US) }
    }
    return occupied
}

/** Returns a blocker if the next entry would breach the aggregate risk reservation. */
internal fun futuresPortfolioRiskBlocker(
    proposedRiskPct: Double,
    reservedRiskPcts: Iterable<Double>,
    maxTotalRiskPct: Double = FUTURES_MAX_PORTFOLIO_RISK_PCT
): String? {
    if (!maxTotalRiskPct.isFinite() || maxTotalRiskPct <= 0.0) {
        return "Maximum aggregate Futures risk is not configured"
    }
    if (!proposedRiskPct.isFinite() || proposedRiskPct <= 0.0 ||
        proposedRiskPct > FUTURES_MAX_ENTRY_RISK_PCT
    ) {
        return "Proposed Futures entry risk is invalid or exceeds 0.25% equity"
    }
    val risks = reservedRiskPcts.toList()
    if (risks.any { !it.isFinite() || it <= 0.0 || it > FUTURES_MAX_ENTRY_RISK_PCT }) {
        return "An active Futures campaign has an unknown/invalid risk reservation; reconciliation required"
    }
    val reserved = risks.sum()
    if (!reserved.isFinite() || reserved + proposedRiskPct > maxTotalRiskPct + 1e-9) {
        return "Aggregate Futures campaign risk would exceed ${"%.2f".format(Locale.US, maxTotalRiskPct * 100.0)}% equity"
    }
    return null
}

/** Null means this symbol may claim a slot; every uncertain campaign blocks admission. */
internal fun futuresPositionSlotBlocker(
    symbol: String,
    openPositionSymbols: Iterable<String>,
    intents: JSONArray,
    maxPositions: Int
): String? {
    if (maxPositions <= 0) return "Maximum concurrent Futures positions is not configured"
    val normalized = symbol.trim().uppercase(Locale.US)
    if (normalized.isEmpty()) return "Futures symbol is blank"
    val occupied = reservedFuturesPositionSymbols(openPositionSymbols, intents)
    if (normalized in occupied) return "$normalized already has an open or unresolved campaign"
    if (occupied.size >= maxPositions) {
        return "Maximum concurrent Futures campaigns reached ($maxPositions)"
    }
    return null
}

/** Avoid retiring a filled entry while the exchange is still converging on position state. */
internal fun shouldFinalizeFilledFlatCampaign(
    lifecycleStatus: String,
    symbolIsFlat: Boolean,
    hasUnresolvedExit: Boolean,
    firstFlatObservedAtMs: Long,
    nowMs: Long,
    minimumStableFlatMs: Long = 3_000L
): Boolean =
    lifecycleStatus.equals("FILL_CONFIRMATION_PENDING", ignoreCase = true) &&
        symbolIsFlat &&
        !hasUnresolvedExit &&
        firstFlatObservedAtMs > 0L &&
        minimumStableFlatMs >= 0L &&
        nowMs >= firstFlatObservedAtMs &&
        nowMs - firstFlatObservedAtMs >= minimumStableFlatMs


/**
 * A protective order is bot-owned only when Binance clientAlgoId matches the
 * current or explicitly tracked predecessor id. Unknown orders are never
 * canceled just because they share a symbol or stop type.
 */
internal fun isOwnedProtectionClientId(
    candidateClientId: String,
    currentClientId: String,
    previousClientId: String
): Boolean = candidateClientId.isNotBlank() &&
    (candidateClientId == currentClientId || candidateClientId == previousClientId)

/**
 * Emergency close is permitted only for a confirmed active campaign with its
 * expected exchange-side protection. Stale terminal intents and unknown/manual
 * positions must be left untouched for explicit reconciliation.
 */
internal fun isManagedOpenPositionIntent(
    status: String,
    direction: String,
    expectedDirection: String,
    protectionStatus: String,
    protectionClientId: String,
    strategyOwnershipVerified: Boolean
): Boolean =
    strategyOwnershipVerified && status.uppercase(Locale.US) == "OPEN_PROTECTED" &&
        direction.equals(expectedDirection, ignoreCase = true) &&
        protectionStatus.equals("PROTECTED", ignoreCase = true) &&
        protectionClientId.isNotBlank()

/**
 * Local, durable USDⓈ-M Futures Testnet execution boundary.
 *
 * Every exchange mutation has a committed local intent first. Ambiguous writes are
 * resolved by client id and exchange state; they are never blindly re-sent.
 */
internal class NativeFuturesCampaignCoordinator(
    private val executionStore: FuturesCampaignStateStore,
    private val clock:()->Long=System::currentTimeMillis,
    private val adapterFactory: () -> BinanceUsdMFuturesTestnetAdapter
) {
    // Migration-compatible verifier flags. Only book-H1 is enabled in 4.36.
    var requireM15FractalForAutomatedEntries:Boolean = false
    var m15EntryVerifier:((EntryPlan)->Unit)? = null
    var requireBookH1ProofForAutomatedEntries:Boolean = false
    var bookH1EntryVerifier:((EntryPlan)->Unit)? = null
    var bookNtdExitVerifier:((JSONObject,String)->Unit)? = null
    var bookCourseTrimVerifier:((JSONObject,String)->Unit)? = null
    var bookCourseExitVerifier:((JSONObject,String)->Unit)? = null
    enum class Direction { LONG, SHORT }
    enum class EntryKind { STOP, TAKE_PROFIT, LIMIT, MARKET }
    enum class State {
        UNINITIALIZED, RECONCILE_REQUIRED, READY, ENTRY_PENDING,
        POSITION_UNPROTECTED, OPEN_PROTECTED, EXIT_PENDING
    }

    data class EntryPlan(
        val symbol: String,
        val direction: Direction,
        val kind: EntryKind,
        val quantity: String,
        val entryPrice: String,
        val protectiveStop: String,
        val clientId: String,
        val riskPct: Double,
        val signalType: String = "",
        val signalBarTimeMs: Long = 0L,
        val signalReason: String = "",
        val leverage: Int = 2,
        val tradeMode: String = "TREND",
        val waveContext: String = "",
        val strategyProfile: String = williamsTc2Profile,
        val entryClientId: String = "",
        val executionSemantics: String = "STOP_ENTRY",
        val referenceClose: Double = 0.0,
        val setupContext: String = "",
        val reentryOwnerId:String="",
        val signalId:String="",
        val reversalOwnerId:String=""
    )

    @Volatile var state: State = State.UNINITIALIZED
        private set
    @Volatile var lastBlocker: String? = "startup_reconciliation_required"
        private set
    @Volatile private var reconciliationRunning = false
    @Volatile private var reconciliationStartedAtMs = 0L
    @Volatile private var reconciliationFinishedAtMs = 0L
    // Read-only coarse phase telemetry; never used to grant entry/STOP authority.
    // Exposes which expensive signed operation a slow coordinator is waiting for.
    @Volatile private var reconciliationPhase = "NOT_STARTED"
    @Volatile private var reconciliationPhaseStartedAtMs = 0L
    private fun noteReconciliationPhase(phase: String) {
        reconciliationPhase = phase
        reconciliationPhaseStartedAtMs = clock()
    }
    @Volatile private var verifiedRiskCapManagementAtMs = 0L
    /** Signed exchange observations; never infer strategy ownership from an order prefix. */
    @Volatile private var externalOrderObservationsJson = "[]"
    @Volatile private var externalProtectionObservationsJson = "[]"
    /** Fresh read-only-scope proof; isolated risk-reducing stop moves only. */
    @Volatile private var externalStopManagementSymbolsJson = "[]"
    @Volatile private var externalStopManagementVerifiedAtMs = 0L

    private val lock = Any()
    @Volatile private var observationSink: ((JSONArray) -> Unit)? = null
    @Volatile private var entryPassportAuditSink:((JSONObject)->Long)?=null

    fun setEntryPassportAuditSink(sink:(JSONObject)->Long)=synchronized(lock) {
        check(state==State.UNINITIALIZED) { "Entry audit must be bound before reconciliation" }
        entryPassportAuditSink=sink
    }

    private fun appendEntryPassport(plan:EntryPlan,attempt:String,phase:String,outcome:JSONObject?=null):Long {
        val sink=entryPassportAuditSink?:return -1L // Offline fixtures can omit the production audit database.
        return runCatching { sink(williamsEntryPassportEvent(plan,attempt,phase,clock(),outcome)) }
            .getOrDefault(0L).coerceAtLeast(0L)
    }

    @Volatile private var editionSelectionProvider:(()->String)?=null
    fun setEditionSelectionProvider(provider:()->String)=synchronized(lock) {
        check(state==State.UNINITIALIZED) { "Edition selection must be bound before reconciliation" }
        editionSelectionProvider=provider
    }
    @Volatile private var entryTimePolicyProvider:()->WilliamsEntryTimePolicy={ WilliamsEntryTimePolicy() }
    fun setEntryTimePolicyProvider(provider:()->WilliamsEntryTimePolicy)=synchronized(lock) {
        check(state==State.UNINITIALIZED);entryTimePolicyProvider=provider
    }
    fun configureWhenFlat(save:()->Unit)=synchronized(lock) {
        check(state==State.READY && reservedFuturesPositionSymbols(emptyList(),intents()).isEmpty()) {
            "Смена книги доступна после сверки Binance, без позиций и ожидающих входов"
        }
        save()
    }

    fun setObservationSink(sink: (JSONArray) -> Unit) {
        observationSink = sink
    }
    @Volatile private var structuralStopRecoveryProvider:
        ((String, String, Double, Double, Double) -> RecoveredStructuralStop?)? = null
    private val intentsKey = "futures_campaign_intents_v2"

    /**
     * Configure H1 structural-stop recovery before the first reconciliation. This
     * provider may repair only a known active Williams campaign with missing stop
     * metadata; unknown/manual positions are never silently adopted.
     */
    fun setStructuralStopRecoveryProvider(
        provider: (String, String, Double, Double, Double) -> RecoveredStructuralStop?
    ) = synchronized(lock) {
        check(state == State.UNINITIALIZED) {
            "Structural-stop recovery provider must be configured before reconciliation"
        }
        structuralStopRecoveryProvider = provider
    }
    private val allowedSymbols = BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS
    private val terminalStatuses = setOf(
        "FILLED", "FINISHED", "EXPIRED", "EXPIRED_IN_MATCH", "CANCELED",
        "CANCELLED", "REJECTED", "CLOSED_OR_STOPPED", "EXIT_CONFIRMED"
    )
    private val activeStatuses = ENTRY_ORDER_ACTIVE_STATUSES

    fun accountSnapshot(): JSONObject = adapterFactory().account()
    fun positionsSnapshot(): JSONArray = adapterFactory().positions()
    /** Display/diagnostic GETs do not wait for or publish execution reconciliation. */
    fun protectionDisplaySnapshot(): FuturesProtectionDisplaySnapshot {
        val adapter = adapterFactory()
        return readFuturesProtectionDisplaySnapshot(
            readPositions = adapter::positions,
            readOpenAlgosForSymbol = adapter::openAlgoOrders,
            contractPrice = adapter::contractPrice,
            trustedObservations = JSONArray(externalProtectionObservationsJson),
            intents = intentsSnapshot(),
            clock = clock,
            supervisions = operatorSupervisions(),
            readOpenOrders = adapter::allOpenOrders
        )
    }
    fun userTrades(symbol: String, startTime: Long, endTime: Long, limit: Int = 1000): JSONArray =
        adapterFactory().userTrades(symbol, startTime, endTime, limit)
    fun intentsSnapshot(): JSONArray = JSONArray(intents().toString())

    /** Persist the outcome of read-only trade-stat reconstruction; never alters exchange state. */
    fun markTradeJournalStatus(clientId: String, status: String, detail: String? = null): Boolean =
        synchronized(lock) {
            if (clientId.isBlank() || status.isBlank()) return@synchronized false
            val rows = intents()
            val row = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .lastOrNull {
                    it.optString("intent_role", "ENTRY") == "ENTRY" &&
                        it.optString("client_id") == clientId
                } ?: return@synchronized false
            row.put("trade_journal_status", status)
                .put("trade_journal_updated_at_ms", clock())
            if (detail.isNullOrBlank()) row.remove("trade_journal_detail")
            else row.put("trade_journal_detail", detail.take(500))
            saveIntents(rows)
            true
        }

    fun recordVerifiedCampaignResult(clientId:String,netPnl:Double,feesKnown:Boolean,fillCount:Int):Boolean=synchronized(lock) {
        if(!netPnl.isFinite() || fillCount<2) return@synchronized false
        val all=intents()
        val owner=(0 until all.length()).mapNotNull { all.optJSONObject(it) }.singleOrNull {
            it.optString("client_id")==clientId && it.optString("intent_role","ENTRY")=="ENTRY" &&
                campaignLifecycleStatus(it)=="CLOSED_OR_STOPPED"
        } ?: return@synchronized false
        owner.put("verified_net_pnl_usdt",netPnl).put("net_result_fees_known",feesKnown)
            .put("net_result_source","BINANCE_USER_TRADES").put("net_result_fill_count",fillCount)
        saveIntents(all);true
    }

    /** True only for the latest durable campaign whose position and stop are exchange-confirmed. */
    fun isManagedOpenPosition(symbol: String, direction: Direction): Boolean = synchronized(lock) {
        if (symbol !in allowedSymbols) return@synchronized false
        val rows = intents()
        val latestIndex = latestEntryIntentIndex(rows, symbol) ?: return@synchronized false
        val latest = rows.optJSONObject(latestIndex) ?: return@synchronized false
        isManagedOpenPositionIntent(
            status = campaignLifecycleStatus(latest),
            direction = latest.optString("direction"),
            expectedDirection = direction.name,
            protectionStatus = latest.optString("protection_status"),
            protectionClientId = latest.optString("protection_client_id"),
            strategyOwnershipVerified = latest.optBoolean("strategy_ownership_verified", false)
        )
    }

    /** Read-only exchange income history used to prevent resetting an unknown daily-loss baseline. */
    fun incomeHistory(startTimeMs: Long, endTimeMs: Long): JSONArray =
        adapterFactory().incomeHistory(startTimeMs, endTimeMs)

    /** Must finish successfully before the scanner may submit a new entry. */
    private fun standardOrderOrNull(
        adapter: BinanceUsdMFuturesTestnetAdapter,
        symbol: String,
        clientId: String
    ): JSONObject? = try {
        adapter.order(symbol, clientId)
    } catch (failure: IOException) {
        if (isDefinitiveBinanceOrderNotFound(failure)) null else throw failure
    }

    private fun conditionalOrderOrNull(
        adapter: BinanceUsdMFuturesTestnetAdapter,
        symbol: String,
        clientId: String
    ): JSONObject? = try {
        adapter.queryAlgoOrder(symbol, clientId)
    } catch (failure: IOException) {
        if (isDefinitiveBinanceOrderNotFound(failure)) null else throw failure
    }

    /** Record failures that happen before reconcile() is entered (for example, a busy or unpersisted Execution Door lease). */
    fun recordReconciliationBlocker(reason: String): JSONObject = synchronized(lock) {
        val safeReason = reason.trim().take(500).ifBlank { "reconciliation_blocked_before_exchange_snapshot" }
        state = State.RECONCILE_REQUIRED
        lastBlocker = safeReason
        JSONObject()
            .put("ok", false)
            .put("engine", "USD_M_FUTURES_TESTNET")
            .put("state", state.name)
            .put("blocker", lastBlocker)
            .put("reconciliation_started", false)
    }

    fun reconcile(): JSONObject = synchronized(lock) {
        // A stale success cannot authorize a later stop mutation while the
        // account is being refreshed or when a REST call fails halfway.
        externalStopManagementSymbolsJson = "[]"
        externalStopManagementVerifiedAtMs = 0L
        reconciliationRunning = true
        reconciliationStartedAtMs = clock()
        noteReconciliationPhase("SIGNED_ACCOUNT_BASELINE")
        verifiedRiskCapManagementAtMs = 0L
        state = State.RECONCILE_REQUIRED
        lastBlocker = "exchange_reconciliation_in_progress"
        try {
            // Adapter construction can fail too (for example, credentials/storage
            // becoming unavailable during recovery). Keep it inside this guarded
            // region so the failure is recorded as the real blocker instead of
            // leaving the coordinator permanently reporting "in progress".
            val adapter = adapterFactory()
            adapter.serverTime()
            adapter.requireOneWayMode()
            adapter.account() // verifies authenticated Futures account access
            val exchangePositions = adapter.positions()
            noteReconciliationPhase("SIGNED_POSITIONS_RETURNED")
            val openBySymbol = linkedMapOf<String, JSONObject>()
            val seenPositionSymbols = mutableSetOf<String>()
            for (i in 0 until exchangePositions.length()) {
                val row = exchangePositions.optJSONObject(i)
                    ?: throw IllegalStateException("Binance returned a malformed position row at index $i during reconciliation")
                val symbol = row.optString("symbol").uppercase(Locale.US)
                check(symbol.isNotBlank() && seenPositionSymbols.add(symbol)) {
                    "Binance returned a blank or duplicate position symbol during reconciliation"
                }
                val amount = row.optString("positionAmt").toBigDecimalOrNull()
                    ?: throw IllegalStateException("Binance returned a malformed position amount for $symbol")
                if (amount.signum() == 0) continue
                check(row.optString("positionSide") == "BOTH") {
                    "Live Futures position does not prove the required one-way mode"
                }
                if (symbol !in allowedSymbols) {
                    throw IllegalStateException(
                        "Unmanaged live Futures position $symbol detected outside the approved ten-symbol universe; manual reconciliation required"
                    )
                }
                openBySymbol[symbol] = row
            }

            // A former LONG must not stay ACTIVE after it disappears, including
            // when a new, independently owned SHORT now exists on the same pair.
            val supervisionBefore=operatorSupervisions()
            val supervisionAfter=reconcileSupervisionHistory(supervisionBefore,exchangePositions,clock())
            if(supervisionBefore.toString()!=supervisionAfter.toString()) saveOperatorSupervisions(supervisionAfter)

            val persisted = intents()
            // Migrate legacy snapshots conservatively. Actual order state and campaign
            // lifecycle must be stored separately: FILLED does not mean a campaign is flat.
            for (i in 0 until persisted.length()) {
                val item = persisted.optJSONObject(i)
                    ?: throw IllegalStateException("Persisted Futures intent is malformed")
                if (item.optString("intent_role", "ENTRY") != "ENTRY") continue
                val legacy = item.optString("exchange_status").uppercase(Locale.US)
                if (item.optString("campaign_status").isBlank()) {
                    when {
                        legacy in setOf("OPEN_PROTECTED", "POSITION_OPEN", "CLOSED_OR_STOPPED") ->
                            item.put("campaign_status", legacy)
                        legacy in activeStatuses -> item.put("campaign_status", "ENTRY_PENDING")
                    }
                }
                if (item.optString("order_status").isBlank() && legacy !in setOf(
                    "OPEN_PROTECTED", "POSITION_OPEN", "CLOSED_OR_STOPPED",
                    "ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "EXIT_PENDING"
                ) && legacy.isNotBlank()) item.put("order_status", legacy)
            }
            val latestEntryIndexBySymbol = mutableMapOf<String, Int>()
            for (i in 0 until persisted.length()) {
                val item = persisted.optJSONObject(i)
                    ?: throw IllegalStateException("Persisted Futures intent is malformed")
                if (item.optString("intent_role", "ENTRY") == "ENTRY") {
                    latestEntryIndexBySymbol[item.optString("symbol").uppercase(Locale.US)] = i
                }
            }
            // P0 Diagnostics 18: never bind a new SHORT to an older LONG owner.
            // Detect before ANY add-on/exit intent reconciliation can mutate it.
            val reversedCampaignSymbols = linkedSetOf<String>()
            for ((symbol, position) in openBySymbol) {
                val latest = latestEntryIndexBySymbol[symbol]?.let { persisted.optJSONObject(it) }
                    ?: continue
                val signedAmount = position.optString("positionAmt").toBigDecimalOrNull()
                    ?: throw IllegalStateException("Invalid signed Futures position quantity for $symbol")
                if (signedAmount.signum() == 0) continue
                val direction = if (signedAmount.signum() > 0) "LONG" else "SHORT"
                if (latest.optString("direction") != direction) reversedCampaignSymbols += symbol
            }
            val activeEntryClientIdsBySymbol = mutableMapOf<String, MutableSet<String>>()
            val activeExitClientIdsBySymbol = mutableMapOf<String, MutableSet<String>>()
            val activeProtectionClientIdsBySymbol = mutableMapOf<String, MutableSet<String>>()
            fun addOwner(destination: MutableMap<String, MutableSet<String>>, symbol: String, clientId: String) {
                if (symbol.isNotBlank() && clientId.isNotBlank()) {
                    destination.getOrPut(symbol.uppercase(Locale.US)) { mutableSetOf() }.add(clientId)
                }
            }
            for (i in 0 until persisted.length()) {
                val item = persisted.optJSONObject(i) ?: continue
                val role = item.optString("intent_role", "ENTRY")
                val symbol = item.optString("symbol").uppercase(Locale.US)
                val orderStatus = if (role == "EXIT") item.optString("exchange_status").uppercase(Locale.US)
                    else exchangeOrderStatus(item)
                val lifecycle = campaignLifecycleStatus(item)
                val clientId = item.optString("client_id")
                if (role in setOf("ENTRY", "ADD_ON") && orderStatus in activeStatuses &&
                    lifecycle !in TERMINAL_CAMPAIGN_STATUSES
                ) addOwner(activeEntryClientIdsBySymbol, symbol, clientId)
                if (role == "EXIT" && orderStatus in (activeStatuses + "EXIT_PENDING")) {
                    addOwner(activeExitClientIdsBySymbol, symbol, clientId)
                }
                if (role != "ENTRY") continue
                val protectionStillNeedsOwnership =
                    lifecycle in ACTIVE_CAMPAIGN_STATUSES ||
                    (symbol !in openBySymbol && lifecycle in TERMINAL_CAMPAIGN_STATUSES)
                if (protectionStillNeedsOwnership) {
                    addOwner(activeProtectionClientIdsBySymbol, symbol, item.optString("protection_client_id"))
                    addOwner(activeProtectionClientIdsBySymbol, symbol, item.optString("previous_protection_client_id"))
                    item.optJSONObject("native_stop_replacement")?.let { tx ->
                        for (key in listOf("old_id", "new_id", "bridge_id"))
                            addOwner(activeProtectionClientIdsBySymbol, symbol, tx.optString(key))
                    }
                }
            }
            // A triggered conditional's matching-engine child has its own client id.
            // Bind it through the signed parent actualOrderId, never through a prefix.
            val entryExecutions = mutableMapOf<String, JSONObject>()
            for (i in 0 until persisted.length()) {
                val owner = persisted.optJSONObject(i) ?: continue
                val symbol = owner.optString("symbol")
                if (owner.optString("intent_role", "ENTRY") !in setOf("ENTRY", "ADD_ON") ||
                    (owner.optString("intent_role","ENTRY")=="ENTRY" && campaignLifecycleStatus(owner) in TERMINAL_CAMPAIGN_STATUSES) ||
                    (owner.optString("intent_role")=="ADD_ON" && (0 until persisted.length()).mapNotNull { persisted.optJSONObject(it) }
                        .none { it.optString("client_id")==owner.optString("entry_client_id") &&
                            campaignLifecycleStatus(it) !in TERMINAL_CAMPAIGN_STATUSES }) ||
                    !owner.optBoolean("strategy_ownership_verified", false)) continue
                val cid = owner.optString("client_id")
                val execution = if (owner.optString("kind") in setOf(EntryKind.LIMIT.name, EntryKind.MARKET.name)) {
                    standardOrderOrNull(adapter, symbol, cid)
                } else {
                    val parent = conditionalOrderOrNull(adapter, symbol, cid)
                    if(parent!=null && owner.optString("entry_trigger_basis")=="CONTRACT_PRICE") {
                        check(parent.optString("symbol")==symbol && parent.optString("clientAlgoId")==cid &&
                            parent.optString("workingType")=="CONTRACT_PRICE") {
                            "Conditional entry contract-price basis or identity mismatch"
                        }
                    }
                    val actualId = parent?.optString("actualOrderId").orEmpty()
                    if(parent!=null) owner.put("conditional_parent_status",parent.optString("algoStatus"))
                        .put("conditional_actual_order_id",actualId)
                        .put("conditional_untriggered_terminal",parent.optString("algoStatus") in setOf("CANCELED","REJECTED","EXPIRED") &&
                            parent.has("actualOrderId") && (actualId.isBlank() || actualId=="0"))
                    if (actualId.isNotBlank() && actualId != "0") {
                        check(parent != null && parent.optString("symbol") == symbol && parent.optString("clientAlgoId") == cid) {
                            "Conditional entry parent identity mismatch"
                        }
                        try { adapter.orderById(symbol, actualId) }
                        catch(failure:Exception) {
                            // Algo Service can publish a child id before the matching engine
                            // makes it queryable. Keep the reservation and audit other stops;
                            // never turn this into a fill, cancellation or retry submission.
                            if(parent.optString("algoStatus") in setOf("TRIGGERING","TRIGGERED") &&
                                isDefinitiveBinanceOrderNotFound(failure)) null else throw failure
                        }
                    } else null
                }
                if (execution != null) {
                    val expectedSide = if (owner.optString("direction") == "LONG") "BUY" else "SELL"
                    check(execution.optString("symbol") == symbol && execution.optString("side") == expectedSide &&
                        execution.optString("positionSide", "BOTH") == "BOTH" && execution.optString("clientOrderId").isNotBlank()) {
                        "Entry matching-engine order identity/direction mismatch"
                    }
                    val actualId = execution.optString("orderId")
                    check(owner.optString("actual_entry_order_id").let { it.isBlank() || it == actualId }) {
                        "Entry matching-engine order id changed"
                    }
                    val executed = execution.optString("executedQty").toBigDecimalOrNull()
                        ?: error("Malformed entry execution quantity")
                    val planned = owner.optString("quantity").toBigDecimalOrNull()
                        ?: error("Malformed entry planned quantity")
                    check(executed.signum() >= 0 && executed <= planned) { "Entry execution exceeds its own intent" }
                    check(entryExecutions.values.none { it.optString("symbol")==symbol && it.optString("orderId")==actualId }) {
                        "One exchange fill cannot belong to two entry intents"
                    }
                    owner.put("actual_entry_order_id", actualId)
                        .put("actual_entry_executed_qty",executed.toPlainString())
                    entryExecutions[cid] = execution
                }
            }
            // A manual or orphaned order can create exposure without a local intent.
            // Do NOT abort account-wide auditing on its first occurrence: doing
            // so previously hid protection of the OTHER live symbols as well.
            // Unknown orders still block new entries and are NEVER auto-adopted.
            val externalOrders = JSONArray()
            noteReconciliationPhase("READING_OPEN_STANDARD_ORDERS")
            val allOpenOrders = adapter.allOpenOrders()
            for (i in 0 until allOpenOrders.length()) {
                val order = allOpenOrders.optJSONObject(i)
                    ?: throw IllegalStateException("Malformed account-wide Futures open-order row")
                val symbol = order.optString("symbol").uppercase(Locale.US)
                val clientId = order.optString("clientOrderId")
                if (symbol !in allowedSymbols ||
                    (!entryExecutions.values.any { it.optString("symbol") == symbol &&
                        it.optString("orderId") == order.optString("orderId") && it.optString("clientOrderId") == clientId } &&
                        !isOwnedClientOrderForSymbol(symbol, clientId, activeEntryClientIdsBySymbol) &&
                        !isOwnedClientOrderForSymbol(symbol, clientId, activeExitClientIdsBySymbol))
                ) {
                    externalOrders.put(JSONObject().put("symbol",symbol)
                        .put("client_id",clientId).put("kind","EXTERNAL_OPEN_ORDER")
                        .put("exchange_order_id",order.optString("orderId")))
                
                }
            }
            noteReconciliationPhase("READING_OPEN_ALGO_ORDERS")
            val allOpenAlgoOrders = adapter.allOpenAlgoOrders()
            // Upgrade/restart recovery: a legacy W2PS stop can lose only its
            // local protection binding even though the ENTRY ledger survives.
            // Rebind ONLY a derived exact clientAlgoId to the same signed
            // exchange entry fill, volume and average; never infer ownership
            // from the W2PS prefix or an unrelated orphan Binance position.
            for ((symbol, position) in openBySymbol) {
                val owner = latestEntryIndexBySymbol[symbol]?.let { persisted.optJSONObject(it) }
                    ?: continue
                if (!owner.optBoolean("strategy_ownership_verified",false) ||
                    campaignLifecycleStatus(owner) !in ACTIVE_CAMPAIGN_STATUSES) continue
                val entryId=owner.optString("client_id")
                if (!williamsKnownDurableEntryIdentity(owner) ||
                    owner.optString("protection_client_id").isNotBlank()) continue
                // The simple 1-entry full-fill proof is intentionally strict:
                // an ADD_ON or partial reduction must be reconciled using its
                // full fill ledger before owner rebind is ever authorized.
                val hasOtherFills=(0 until persisted.length()).mapNotNull { persisted.optJSONObject(it) }
                    .any { it.optString("entry_client_id")==entryId &&
                        it.optString("intent_role") in setOf("ADD_ON","EXIT") }
                if(hasOtherFills) continue
                val fill=entryExecutions[entryId] ?: continue
                val amount=position.optString("positionAmt").toBigDecimalOrNull() ?: continue
                if(amount.signum()==0 || owner.optString("direction") !=
                    (if(amount.signum()>0) "LONG" else "SHORT") ||
                    fill.optString("executedQty").toBigDecimalOrNull()?.compareTo(amount.abs())!=0)
                    continue
                val entry=position.optString("entryPrice").toDoubleOrNull() ?: continue
                val average=fill.optString("avgPrice").toDoubleOrNull() ?: continue
                if(!entry.isFinite() || entry<=0 || !average.isFinite() ||
                    kotlin.math.abs(entry-average)>maxOf(1e-8,entry*1e-8)) continue
                val mark=runCatching { adapter.markPrice(symbol) }.getOrNull() ?: continue
                val stopId=stableProtectionId(symbol,entryId)
                val matches=(0 until allOpenAlgoOrders.length()).mapNotNull {
                    allOpenAlgoOrders.optJSONObject(it)
                }.filter { it.optString("clientAlgoId")==stopId }
                if(matches.size!=1 || !isFullPositionFuturesStop(matches.single(),symbol,
                    owner.optString("direction"),amount.abs(),mark)) continue
                val price=matches.single().optString("triggerPrice").ifBlank {
                    matches.single().optString("stopPrice")
                }.toDoubleOrNull() ?: continue
                val old=owner.optString("protective_stop").toDoubleOrNull()
                // Never silently widen a protective stop that survived in the
                // journal. A manually tightened stop can still be recognized.
                if(old!=null && old.isFinite() && old>0 &&
                    (if(amount.signum()>0) price+1e-8<old else price-1e-8>old))
                    continue
                owner.put("protection_client_id",stopId)
                    .put("protective_stop",price.toString())
                    .put("protection_status","PROTECTED")
                    .put("protection_rebound_from_exchange_at_ms",clock())
                    .put("protection_rebound_proof","EXACT_DERIVED_W2PS_AND_AUTHENTICATED_FILL")
                addOwner(activeProtectionClientIdsBySymbol,symbol,stopId)
                saveIntents(persisted)
            }
            for (i in 0 until allOpenAlgoOrders.length()) {
                val order = allOpenAlgoOrders.optJSONObject(i)
                    ?: throw IllegalStateException("Malformed account-wide Futures algo-order row")
                val symbol = order.optString("symbol").uppercase(Locale.US)
                val clientId = order.optString("clientAlgoId")
                if (symbol !in allowedSymbols ||
                    (!isOwnedClientOrderForSymbol(symbol, clientId, activeEntryClientIdsBySymbol) &&
                        !isOwnedClientOrderForSymbol(symbol, clientId, activeProtectionClientIdsBySymbol))
                ) {
                    externalOrders.put(JSONObject().put("symbol",symbol)
                        .put("client_id",clientId).put("kind","EXTERNAL_ALGO_ORDER")
                        .put("exchange_algo_id",order.optString("algoId")))
                
                }
            }
            externalOrderObservationsJson=externalOrders.toString()
            val protectionObservations=JSONArray()
            var hasPendingExit = false
            var hasPendingAddition = false
            var anyUnprotected = false
            val requiredSafetyExits = JSONArray()
            var actualRiskAuditBlocked = reversedCampaignSymbols.isNotEmpty()
            var actualRiskAuditBlocker: String? =
                if (reversedCampaignSymbols.isNotEmpty())
                    "Signed Futures exposure reversed relative to persisted campaign on " +
                        reversedCampaignSymbols.joinToString(",") +
                        "; no old-owner mutation or new entries authorized"
                else null
            // Resolve persisted entry and exit intents against Binance. Older completed
            // campaigns on a symbol must not be mistaken for the campaign now in market.
            val resolutionOrder=(0 until persisted.length()).sortedBy {
                when(persisted.optJSONObject(it)?.optString("intent_role")) { "ADD_ON" -> 0; "EXIT" -> 1; else -> 2 }
            }
            for (i in resolutionOrder) {
                val intent = persisted.optJSONObject(i)
                    ?: throw IllegalStateException("Persisted Futures intent is malformed")
                val symbol = intent.optString("symbol").uppercase(Locale.US)
                val clientId = intent.optString("client_id")
                val role = intent.optString("intent_role", "ENTRY")
                if (symbol !in allowedSymbols || clientId.isBlank()) {
                    throw IllegalStateException("Persisted Futures intent is malformed")
                }
                if (symbol in reversedCampaignSymbols) {
                    // Preserve all old campaign, add-on and exit intent bytes.
                    // Never cancel or transfer ownership across an opposite side.
                    continue
                }
                val position = openBySymbol[symbol]
                val prior = campaignLifecycleStatus(intent)
                val priorOrderStatus = exchangeOrderStatus(intent)
                if (role == "ADD_ON") {
                    val primary = (0 until persisted.length()).mapNotNull { persisted.optJSONObject(it) }
                        .singleOrNull { it.optString("intent_role","ENTRY")=="ENTRY" &&
                            it.optString("client_id")==intent.optString("entry_client_id") }
                        ?: error("Addition has no unique durable campaign owner")
                    check(primary.optString("symbol")==symbol &&
                        primary.optString("direction")==intent.optString("direction") &&
                        primary.optString("strategy_profile",williamsTc2Profile)==intent.optString("strategy_profile",williamsTc2Profile)) {
                        "Addition campaign/profile identity mismatch"
                    }
                    var order = entryExecutions[clientId]
                    var conditional = if (intent.optString("kind") in setOf("STOP","TAKE_PROFIT"))
                        conditionalOrderOrNull(adapter,symbol,clientId) else null
                    val conditionalStatus = conditional?.let { it.optString("algoStatus",it.optString("status")) }.orEmpty()
                    val executed = order?.optString("executedQty")?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                    val parentIsOpen = position != null && campaignLifecycleStatus(primary) !in TERMINAL_CAMPAIGN_STATUSES
                    val ageExpired = intent.optLong("signal_expires_at_ms",0L).let { it>0 && clock()>=it }
                    // Cancel a partially executed remainder, an expired formation, or
                    // any orphan addition before a completed campaign can be retired.
                    if (executed.signum()>0 || !parentIsOpen || ageExpired || williamsNewExposureBlocker(intent.optString("strategy_profile",williamsTc2Profile))!=null) {
                        if (conditional != null && conditionalStatus !in ENTRY_ORDER_TERMINAL_STATUSES) {
                            intent.put("order_status","CANCEL_SUBMISSION_UNKNOWN"); saveIntents(persisted)
                            adapter.cancelAlgoOrder(symbol,clientId)
                            conditional=adapter.queryAlgoOrder(symbol,clientId)
                            check(conditional.optString("algoStatus",conditional.optString("status")) in ENTRY_ORDER_TERMINAL_STATUSES) {
                                "Addition conditional cancellation is not confirmed"
                            }
                        }
                        if (order != null && order.optString("status") in activeStatuses) {
                            intent.put("order_status","CANCEL_SUBMISSION_UNKNOWN"); saveIntents(persisted)
                            adapter.cancelOrderById(symbol,order.getString("orderId"))
                            order=adapter.orderById(symbol,order.getString("orderId"))
                            check(order.optString("status") in ENTRY_ORDER_TERMINAL_STATUSES) { "Addition remainder remains active" }
                            entryExecutions[clientId]=order
                        }
                        val refreshed=adapter.positions()
                        openBySymbol.remove(symbol)
                        for (j in 0 until refreshed.length()) {
                            val row=refreshed.getJSONObject(j)
                            if (row.optString("symbol")==symbol && row.getString("positionAmt").toBigDecimal().signum()!=0)
                                openBySymbol[symbol]=row
                        }
                    }
                    val observed=order?.optString("status") ?: conditional?.let { it.optString("algoStatus",it.optString("status")) }
                    check(!observed.isNullOrBlank()) { "Addition submission remains unqueryable; no retry admitted" }
                    intent.put("order_status",observed).put("exchange_status",observed)
                        .put("executed_qty",order?.optString("executedQty","0") ?: "0")
                        .put("campaign_status",if (observed in ENTRY_ORDER_TERMINAL_STATUSES) "CLOSED_OR_STOPPED" else "ENTRY_PENDING")
                    if (observed !in ENTRY_ORDER_TERMINAL_STATUSES) hasPendingAddition=true
                    saveIntents(persisted)
                    continue
                }
                if (role == "EXIT") {
                    // A new BNB/LONG (or any later campaign) is not residual
                    // exposure from an already filled, signed-flat old exit.
                    // Preserve that exact historic proof; never advance its
                    // flat timestamp or claim that the NEW position is flat.
                    if (priorCampaignExitHasSignedFlatProof(intent,persisted,clock())) {
                        intent.put("exchange_status","EXIT_CONFIRMED")
                            .put("historical_flat_proof_preserved",true)
                        continue
                    }
                    val order = standardOrderOrNull(adapter, symbol, clientId)
                    if (order == null) {
                        val stillOpenStandard = (0 until allOpenOrders.length()).any { j ->
                            val row = allOpenOrders.optJSONObject(j) ?: return@any true
                            row.optString("symbol").equals(symbol, true) &&
                                row.optString("clientOrderId") == clientId
                        }
                        val stillOpenConditional = (0 until allOpenAlgoOrders.length()).any { j ->
                            val row = allOpenAlgoOrders.optJSONObject(j) ?: return@any true
                            row.optString("symbol").equals(symbol, true) &&
                                row.optString("clientAlgoId") == clientId
                        }
                        if (position == null && !stillOpenStandard && !stillOpenConditional) {
                            intent.put("exchange_status", "EXIT_CONFIRMED")
                                .put("order_status", "NOT_FOUND_FLAT_CONFIRMED")
                                .put("confirmed_flat_at_ms", clock())
                        } else {
                            throw IllegalStateException(
                                "Exit intent " + clientId + " is not queryable while " + symbol +
                                    " still has a position or matching open order; no retry permitted"
                            )
                        }
                    } else {
                        check(order.optString("symbol") == symbol && order.optString("clientOrderId") == clientId) {
                            "Exit exchange order identity mismatch"
                        }
                        val executed = order.optString("executedQty").toBigDecimalOrNull()
                        check(executed != null && executed.signum() >= 0 &&
                            executed <= intent.getString("quantity").toBigDecimal()) { "Exit execution quantity is invalid" }
                        intent.put("executed_qty", executed.toPlainString())
                        val exitStatus = order.optString("status").uppercase(Locale.US)
                        if (exitStatus == "FILLED") {
                            if(intent.optBoolean("partial_reduction",false) && executed.compareTo(intent.getString("quantity").toBigDecimal())==0) {
                                intent.put("exchange_status","EXIT_CONFIRMED").put("order_status","FILLED")
                                    .put("exchange_order_id",order.optString("orderId")).put("reduction_confirmed_at_ms",clock())
                            } else if (position == null) {
                                intent.put("exchange_status", "EXIT_CONFIRMED")
                                    .put("order_status", "FILLED")
                                    .put("exchange_order_id", order.optString("orderId"))
                                    .put("confirmed_flat_at_ms", clock())
                            } else {
                                // The order is terminal, but the account still reports exposure.
                                // Never resend that client id; reconcile the remaining position
                                // and its protection as a separate campaign-state decision.
                                intent.put("exchange_status", "EXIT_TERMINAL_POSITION_OPEN")
                                    .put("order_status", "FILLED")
                                    .put("exchange_order_id", order.optString("orderId"))
                                    .put("last_reconciled_ms", clock())
                            }
                        } else if (exitStatus in setOf("CANCELED", "CANCELLED", "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH")) {
                            intent.put("exchange_status", if (position == null) "EXIT_CONFIRMED" else "EXIT_TERMINAL_POSITION_OPEN")
                                .put("order_status", exitStatus)
                                .put("exchange_order_id", order.optString("orderId"))
                                .put("last_reconciled_ms", clock())
                        } else {
                            hasPendingExit = true
                            intent.put("exchange_status", exitStatus)
                                .put("exchange_order_id", order.optString("orderId"))
                                .put("last_reconciled_ms", clock())
                        }
                    }
                    continue
                }
                val direction = if ((position?.optString("positionAmt")?.toDoubleOrNull() ?: 0.0) > 0.0) "LONG" else "SHORT"
                val isLatestCampaign = latestEntryIndexBySymbol[symbol] == i
                if (position != null && isLatestCampaign) {
                    if (!intent.optString("direction").equals(direction, ignoreCase = true)) {
                        throw IllegalStateException(
                            "Live " + symbol + " Futures position direction conflicts with persisted campaign"
                        )
                    }
                    // A FILLED order can precede position visibility. Only an explicit
                    // terminal campaign may not own a live position; unknown legacy
                    // FILLED history is deliberately blocked instead of silently adopted.
                    if (prior in TERMINAL_CAMPAIGN_STATUSES || prior.isBlank()) {
                        throw IllegalStateException(
                            "Live " + symbol + " position conflicts with terminal or unclassified campaign " +
                                clientId + " (" + (prior.ifBlank { priorOrderStatus }) +
                                "); manual reconciliation required"
                        )
                    }
                    if (prior !in ACTIVE_CAMPAIGN_STATUSES) {
                        throw IllegalStateException(
                            "Live " + symbol + " position has an unknown campaign lifecycle " + prior +
                                "; manual reconciliation required"
                        )
                    }
                    // A partially-filled LIMIT entry can leave additional quantity live on Binance.
                    // Cancel its remainder through the same coordinator reconciliation, then
                    // confirm the terminal exchange status before sizing/confirming protection.
                    val matchingOpenOrder = (0 until allOpenOrders.length()).mapNotNull { j ->
                        allOpenOrders.optJSONObject(j)
                    }.firstOrNull {
                        it.optString("symbol").equals(symbol, true) &&
                            (it.optString("clientOrderId") == clientId ||
                                it.optString("orderId") == entryExecutions[clientId]?.optString("orderId"))
                    }
                    val matchingOpenAlgo = (0 until allOpenAlgoOrders.length()).mapNotNull { j ->
                        allOpenAlgoOrders.optJSONObject(j)
                    }.firstOrNull {
                        it.optString("symbol").equals(symbol, true) &&
                            it.optString("clientAlgoId") == clientId
                    }
                    val entryOrderStillOpen = matchingOpenOrder != null || matchingOpenAlgo != null
                    if (entryOrderStillOpen) {
                        val finalOrderStatus = when {
                            matchingOpenOrder != null -> {
                                val observed = matchingOpenOrder.optString("status").uppercase(Locale.US)
                                if (observed in activeStatuses) {
                                    // Persist the cancel mutation before network I/O. If DELETE
                                    // times out, the next reconcile resolves this same client id.
                                    intent.put("order_status", "CANCEL_SUBMISSION_UNKNOWN")
                                        .put("cancel_remainder_requested_at_ms", clock())
                                    saveIntents(persisted)
                                    if (matchingOpenOrder.optString("clientOrderId") == clientId) adapter.cancelOrder(symbol, clientId)
                                    else adapter.cancelOrderById(symbol, matchingOpenOrder.getString("orderId"))
                                }
                                val confirmed = if (matchingOpenOrder.optString("clientOrderId") == clientId) adapter.order(symbol, clientId)
                                    else adapter.orderById(symbol, matchingOpenOrder.getString("orderId"))
                                entryExecutions[clientId] = confirmed
                                val status = confirmed.optString("status").uppercase(Locale.US)
                                if (status !in ENTRY_ORDER_TERMINAL_STATUSES) {
                                    throw IllegalStateException(
                                        "Live $symbol entry remainder cancellation is not exchange-confirmed (status=$status)"
                                    )
                                }
                                status
                            }
                            matchingOpenAlgo != null -> {
                                val observed = matchingOpenAlgo.optString("algoStatus", matchingOpenAlgo.optString("status"))
                                    .uppercase(Locale.US)
                                if (observed !in ENTRY_ORDER_TERMINAL_STATUSES) {
                                    // Durable intent precedes the Algo Order DELETE just as it
                                    // does for standard orders; a timeout cannot permit a retry.
                                    intent.put("order_status", "CANCEL_SUBMISSION_UNKNOWN")
                                        .put("cancel_remainder_requested_at_ms", clock())
                                    saveIntents(persisted)
                                    adapter.cancelAlgoOrder(symbol, clientId)
                                }
                                val confirmed = adapter.queryAlgoOrder(symbol, clientId)
                                val status = confirmed.optString("algoStatus", confirmed.optString("status"))
                                    .uppercase(Locale.US)
                                if (status !in ENTRY_ORDER_TERMINAL_STATUSES) {
                                    throw IllegalStateException(
                                        "Live $symbol conditional-entry remainder cancellation is not exchange-confirmed (status=$status)"
                                    )
                                }
                                status
                            }
                            else -> throw IllegalStateException(
                                "Live $symbol entry-order ownership is ambiguous; refusing protection or a second entry"
                            )
                        }
                        intent.put("exchange_status", finalOrderStatus)
                            .put("order_status", finalOrderStatus)
                            .put("remainder_cancel_confirmed_at_ms", clock())
                            .put("last_reconciled_ms", clock())

                        // Re-fetch exchange positions after cancellation: the final slice may
                        // fill while DELETE is in flight, or the position may be flat already.
                        val afterCancelPositions = adapter.positions()
                        val confirmedPosition = (0 until afterCancelPositions.length())
                            .mapNotNull { j -> afterCancelPositions.optJSONObject(j) }
                            .firstOrNull { it.optString("symbol").equals(symbol, true) }
                        val confirmedAmount = confirmedPosition?.optString("positionAmt")?.toBigDecimalOrNull()
                            ?: if (confirmedPosition == null) java.math.BigDecimal.ZERO
                            else throw IllegalStateException("Malformed $symbol position after entry-remainder cancellation")
                        if (confirmedAmount.signum() == 0) {
                            openBySymbol.remove(symbol)
                            intent.put("campaign_status", "CLOSED_OR_STOPPED")
                                .put("closed_observed_at_ms", clock())
                            saveIntents(persisted)
                            continue
                        }
                        val confirmedDirection = if (confirmedAmount.signum() > 0) "LONG" else "SHORT"
                        if (!intent.optString("direction").equals(confirmedDirection, true)) {
                            throw IllegalStateException(
                                "$symbol position direction changed during entry-remainder cancellation; reconciliation required"
                            )
                        }
                        openBySymbol[symbol] = confirmedPosition
                            ?: throw IllegalStateException("Binance omitted $symbol after confirming a non-zero position amount")
                    }
                    intent.put("campaign_status", "POSITION_OPEN")
                        .put("last_reconciled_ms", clock())
                    continue
                }
                if (position != null && !isLatestCampaign) {
                    if (prior !in TERMINAL_CAMPAIGN_STATUSES) {
                        throw IllegalStateException(
                            "Live " + symbol + " position conflicts with non-latest campaign intent " +
                                clientId + "; refusing to retire historical state automatically"
                        )
                    }
                    continue
                }
                if (prior in TERMINAL_CAMPAIGN_STATUSES) continue
                if (prior in setOf("OPEN_PROTECTED", "POSITION_OPEN", "EXIT_PENDING")) {
                    intent.put("campaign_status", "CLOSED_OR_STOPPED")
                        .put("closed_observed_at_ms", clock())
                    continue
                }
                val kind = intent.optString("kind")
                val order = when (kind) {
                    EntryKind.LIMIT.name, EntryKind.MARKET.name -> standardOrderOrNull(adapter, symbol, clientId)
                    EntryKind.STOP.name, EntryKind.TAKE_PROFIT.name ->
                        conditionalOrderOrNull(adapter, symbol, clientId)
                    else -> throw IllegalStateException("Unknown entry kind for $clientId")
                }
                if (order == null) {
                    val stillOpenStandard = (0 until allOpenOrders.length()).any { j ->
                        val row = allOpenOrders.optJSONObject(j) ?: return@any true
                        row.optString("symbol").equals(symbol, true) &&
                            row.optString("clientOrderId") == clientId
                    }
                    val stillOpenConditional = (0 until allOpenAlgoOrders.length()).any { j ->
                        val row = allOpenAlgoOrders.optJSONObject(j) ?: return@any true
                        row.optString("symbol").equals(symbol, true) &&
                            row.optString("clientAlgoId") == clientId
                    }
                    if (position == null && !stillOpenStandard && !stillOpenConditional) {
                        // Binance definitively reports no historical order and its fresh
                        // position/open-order snapshots show no remaining exposure. Do not
                        // resend the client id; retire the stale local intent durably.
                        intent.put("exchange_status", "NOT_FOUND_FLAT_CONFIRMED")
                            .put("order_status", "NOT_FOUND")
                            .put("campaign_status", "CLOSED_OR_STOPPED")
                            .put("closed_observed_at_ms", clock())
                            .put("last_reconciled_ms", clock())
                        continue
                    }
                    throw IllegalStateException(
                        "Cannot reconcile persisted entry $clientId: order is not queryable while " +
                            "$symbol has a position or matching open order; resubmission is forbidden"
                    )
                }
                val exchangeStatus = order.optString("status").ifBlank {
                    order.optString("algoStatus")
                }.uppercase(Locale.US)
                if (exchangeStatus.isBlank()) {
                    throw IllegalStateException("Entry $clientId has unknown exchange status")
                }
                intent.put("exchange_status", exchangeStatus)
                    .put("order_status", exchangeStatus)
                    .put("last_reconciled_ms", clock())
                when {
                    exchangeStatus in setOf("FILLED", "FINISHED") -> {
                        if (campaignLifecycleStatus(intent) != "FILL_CONFIRMATION_PENDING" ||
                            intent.optLong("fill_observed_at_ms", 0L) <= 0L
                        ) {
                            intent.put("fill_observed_at_ms", clock())
                        }
                        intent.put("campaign_status", "FILL_CONFIRMATION_PENDING")
                    }
                    exchangeStatus in setOf("CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED") -> {
                        val executedQty = order.optString("executedQty").toBigDecimalOrNull()
                            ?: order.optString("cumQty").toBigDecimalOrNull()
                        val possiblePartialFill = prior == "FILL_CONFIRMATION_PENDING" ||
                            (executedQty?.signum() ?: 0) > 0 ||
                            intent.optString("cancel_prior_order_status").uppercase(Locale.US) == "PARTIALLY_FILLED"
                        if (possiblePartialFill) {
                            intent.put("campaign_status", "FILL_CONFIRMATION_PENDING")
                            if (intent.optLong("fill_observed_at_ms", 0L) <= 0L) {
                                intent.put("fill_observed_at_ms", clock())
                            }
                        } else {
                            intent.put("campaign_status", "CLOSED_OR_STOPPED")
                                .put("closed_observed_at_ms", clock())
                        }
                    }
                    exchangeStatus in activeStatuses -> intent.put("campaign_status", "ENTRY_PENDING")
                    else -> throw IllegalStateException("Entry $clientId has unsupported exchange status $exchangeStatus")
                }
            }

            val protectiveOrdersBySymbol = mutableMapOf<String, JSONArray>()
            val actualRiskAccount = if (openBySymbol.isNotEmpty()) {
                runCatching { parseFuturesAccountSnapshot(adapter.account()) }.getOrNull()
            } else null
            val supervisionAudit=operatorSupervisions()
            for ((symbol, position) in openBySymbol) {
                val amount = position.optString("positionAmt").toDoubleOrNull()
                    ?: throw IllegalStateException("Binance returned a malformed position amount for $symbol during protection audit")
                val direction = if (amount > 0.0) "LONG" else "SHORT"
                var algos = protectiveOrdersBySymbol.getOrPut(symbol) { adapter.openAlgoOrders(symbol) }
                var referenceMark = runCatching { adapter.markPrice(symbol) }.getOrNull()
                var signedFullStops=if(referenceMark!=null && referenceMark.isFinite() && referenceMark>0.0)
                    (0 until algos.length()).mapNotNull { algos.optJSONObject(it) }.filter {
                        isFullPositionFuturesStop(it,symbol,direction,
                            position.getString("positionAmt").toBigDecimal().abs(),referenceMark)
                    } else emptyList()
                if (symbol in reversedCampaignSymbols) {
                    // Verify protection for signed Binance SHORT, NOT old LONG.
                    // Exchange protection is informational; neither a new
                    // strategy owner nor an automatic order action is inferred.
                    val unique = signedFullStops.singleOrNull()
                    val proof = if (unique != null) "EXTERNAL_PROTECTED"
                        else if (signedFullStops.size > 1) "MULTIPLE_PROTECTIVE_ORDERS_UNRESOLVED"
                        else "STOP_UNCONFIRMED"
                    protectionObservations.put(JSONObject()
                        .put("symbol",symbol).put("direction",direction)
                        .put("quantity",position.optString("positionAmt"))
                        .put("observed_at_ms",clock()).put("stop_proof",proof)
                        .put("stop",unique?.optString("triggerPrice",
                            unique.optString("stopPrice")) ?: JSONObject.NULL)
                        .put("protection_client_id",unique?.optString("clientAlgoId") ?: JSONObject.NULL)
                        .put("strategy_ownership_verified",false)
                        .put("automatic_trailing_authorized",false)
                        .put("management_mode","REVERSED_CAMPAIGN_QUARANTINE"))
                    if (unique == null) anyUnprotected = true
                    actualRiskAuditBlocked = true
                    lastBlocker = "$symbol $direction conflicts with old campaign; " +
                        "a verified matching exchange stop must be checked manually. " +
                        "No automatic reversal/close/stop mutation."
                    continue
                }
                val latestIntentIndex = latestEntryIndexBySymbol[symbol]
                var matchingIntent = latestIntentIndex?.let { persisted.optJSONObject(it) }
                    ?.takeIf { intent ->
                        intent.optString("intent_role", "ENTRY") == "ENTRY" &&
                            intent.optString("symbol").uppercase(Locale.US) == symbol &&
                            intent.optString("direction") == direction &&
                            campaignLifecycleStatus(intent) in ACTIVE_CAMPAIGN_STATUSES &&
                            // Namespace alone is never ownership. Reversals also
                            // bind their deterministic id to parent and H1 signal;
                            // the signed fill ledger is verified immediately below.
                            williamsKnownDurableEntryIdentity(intent)
                    }
                var liveMark: Double? = null
                if (matchingIntent?.optBoolean("strategy_ownership_verified", false) == true) {
                    val verifiedOwner = checkNotNull(matchingIntent)
                    val fill = entryExecutions[verifiedOwner.optString("client_id")]
                        ?: throw IllegalStateException("$symbol live position has no exchange-confirmed campaign fill")
                    val components = mutableListOf(fill)
                    for (j in 0 until persisted.length()) {
                        val child=persisted.optJSONObject(j) ?: error("Malformed addition ledger")
                        if (child.optString("intent_role")!="ADD_ON" ||
                            child.optString("entry_client_id")!=verifiedOwner.optString("client_id")) continue
                        val confirmed=entryExecutions[child.optString("client_id")]
                        if (confirmed!=null && confirmed.optString("executedQty","0").toBigDecimal().signum()>0) components+=confirmed
                    }
                    val entered=components.fold(java.math.BigDecimal.ZERO) { total,row -> total+row.getString("executedQty").toBigDecimal() }
                    var remainingQty=java.math.BigDecimal.ZERO
                    var remainingCost=java.math.BigDecimal.ZERO
                    for(j in 0 until persisted.length()) {
                        val row=persisted.getJSONObject(j);val cid=row.optString("client_id")
                        val isEntry=cid==verifiedOwner.optString("client_id") ||
                            row.optString("intent_role")=="ADD_ON" && row.optString("entry_client_id")==verifiedOwner.optString("client_id")
                        if(isEntry) {
                            val actual=entryExecutions[cid] ?: continue
                            val q=actual.getString("executedQty").toBigDecimal()
                            if(q.signum()==0) continue
                            val price=actual.getString("avgPrice").toBigDecimal()
                            check(price.signum()>0) { "Non-positive entry execution price" }
                            remainingQty+=q;remainingCost+=q*price
                        } else if(row.optString("intent_role")=="EXIT" && row.optString("entry_client_id")==verifiedOwner.optString("client_id")) {
                            val q=row.optString("executed_qty","0").toBigDecimal()
                            check(q.signum()>=0 && q<=remainingQty) { "Reduction exceeds attributed campaign quantity" }
                            if(q.signum()>0) {
                                val average=remainingCost.divide(remainingQty,java.math.MathContext.DECIMAL128)
                                remainingCost-=average*q;remainingQty-=q
                            }
                        }
                    }
                    val exited = (0 until persisted.length()).mapNotNull { persisted.optJSONObject(it) }
                        .filter { it.optString("intent_role") == "EXIT" &&
                            it.optString("entry_client_id") == verifiedOwner.optString("client_id") }
                        .fold(java.math.BigDecimal.ZERO) { total, exit ->
                            total + (exit.optString("executed_qty", "0").toBigDecimalOrNull()
                                ?: throw IllegalStateException("$symbol exit fill quantity is malformed"))
                        }
                    val liveQuantity = position.getString("positionAmt").toBigDecimal().abs()
                    check(entered.signum() > 0 && (entered - exited).compareTo(liveQuantity) == 0 && remainingQty.compareTo(liveQuantity)==0) {
                        "$symbol exchange quantity cannot be attributed to campaign entry/exit fills"
                    }
                    val fillPrice = remainingCost.divide(remainingQty,java.math.MathContext.DECIMAL128).toDouble()
                    val positionPrice = position.optString("entryPrice").toDoubleOrNull()
                    check(fillPrice != null && fillPrice.isFinite() && fillPrice > 0 &&
                        positionPrice != null && positionPrice.isFinite() &&
                        kotlin.math.abs(fillPrice - positionPrice) <= maxOf(1e-8, fillPrice * 1e-8)) {
                        "$symbol position entry price conflicts with the campaign fill"
                    }
                    verifiedOwner.put("confirmed_position_qty", liveQuantity.toPlainString())
                        .put("actual_entry_price", positionPrice.toString())
                }
                if (matchingIntent?.has("native_stop_replacement") == true) {
                    val owner = checkNotNull(matchingIntent)
                    val engine = nativeStopReplacementEngine(adapter, persisted)
                    val recovered = engine.resume(owner)
                    if (owner.has("native_stop_replacement")) {
                        val proof = engine.protection(owner)
                        recordNativeReplacementProof(owner, proof)
                        saveIntents(persisted)
                        actualRiskAuditBlocked = true
                        actualRiskAuditBlocker = "$symbol native stop replacement pending: ${recovered.optString("blocker") }"
                        if (proof == null) { anyUnprotected = true; lastBlocker = actualRiskAuditBlocker }
                        protectionObservations.put(JSONObject().put("symbol", symbol).put("direction", direction)
                            .put("quantity", position.optString("positionAmt"))
                            .put("observed_at_ms", clock()).put("stop_proof", if (proof == null)
                                "STOP_UNCONFIRMED" else "NATIVE_REPLACEMENT_PROTECTION_SEEN")
                            .put("stop", proof?.optString("triggerPrice") ?: JSONObject.NULL)
                            .put("protection_client_id", proof?.optString("clientAlgoId") ?: JSONObject.NULL)
                            .put("automatic_trailing_authorized", false).put("strategy_ownership_verified", true)
                            .put("management_mode", "NATIVE_REPLACEMENT_PENDING"))
                        continue
                    }
                    algos = adapter.openAlgoOrders(symbol)
                    protectiveOrdersBySymbol[symbol] = algos
                    referenceMark = runCatching { adapter.markPrice(symbol) }.getOrNull()
                    signedFullStops = if (referenceMark != null && referenceMark.isFinite() && referenceMark > 0.0)
                        (0 until algos.length()).mapNotNull { algos.optJSONObject(it) }.filter {
                            isFullPositionFuturesStop(it, symbol, direction,
                                position.getString("positionAmt").toBigDecimal().abs(), referenceMark)
                        } else emptyList()
                }
                // A complete exchange stop can protect a manually held or
                // previous-install position while the bot cannot prove it OWNS
                // that campaign. Record it, do not replace/cancel/trail it.
                val observedOwnerId=matchingIntent?.optString("protection_client_id").orEmpty()
                val ownedProtection=signedFullStops.size==1 &&
                    matchingIntent?.optBoolean("strategy_ownership_verified",false)==true &&
                    observedOwnerId.isNotBlank() &&
                    signedFullStops.single().optString("clientAlgoId")==observedOwnerId
                val supervision=(0 until supervisionAudit.length()).mapNotNull {
                    supervisionAudit.optJSONObject(it)
                }.singleOrNull { it.optString("symbol")==symbol && it.optString("state")=="ACTIVE" }
                val liveEntry=position.optString("entryPrice").toBigDecimalOrNull()
                val approvedEntry=supervision?.optString("entry_price")?.toBigDecimalOrNull()
                val supervisedProtection=supervision!=null &&
                    supervision.optString("state")=="ACTIVE" &&
                    supervision.optString("mode") in setOf("OPERATOR_APPROVED_STOP_ONLY","AUTO_VERIFIED_STOP_ONLY") &&
                    !supervision.optBoolean("strategy_ownership_verified",false) &&
                    !supervision.optBoolean("market_close_authorized",true) &&
                    !supervision.optBoolean("add_on_authorized",true) &&
                    supervision.optString("direction")==direction &&
                    supervision.optString("quantity").toBigDecimalOrNull()?.compareTo(
                        position.getString("positionAmt").toBigDecimal().abs())==0 &&
                    liveEntry!=null && approvedEntry!=null && liveEntry.compareTo(approvedEntry)==0 &&
                    signedFullStops.size==1 && algos.length()==1 &&
                    signedFullStops.single().optString("clientAlgoId")==supervision.optString("protection_client_id") &&
                    signedFullStops.single().optString("triggerPrice").ifBlank {
                        signedFullStops.single().optString("stopPrice")
                    }.toBigDecimalOrNull()?.compareTo(supervision.optString("stop").toBigDecimal())==0 &&
                    (0 until allOpenOrders.length()).mapNotNull { allOpenOrders.optJSONObject(it) }
                        .none { it.optString("symbol")==symbol }
                protectionObservations.put(JSONObject().put("symbol",symbol)
                    .put("observed_at_ms",clock())
                    .put("direction",direction)
                    .put("quantity",position.optString("positionAmt"))
                    .put("stop_proof",when {
                        signedFullStops.size>1 -> "MULTIPLE_PROTECTIVE_ORDERS_UNRESOLVED"
                        signedFullStops.size==1 -> if(ownedProtection) "OWNED_STOP_SEEN" else "EXTERNAL_PROTECTED"
                        referenceMark==null -> "MARK_PRICE_UNAVAILABLE"
                        else -> "STOP_UNCONFIRMED"
                    })
                    .put("stop",if(signedFullStops.size==1)
                        signedFullStops.single().optString("triggerPrice",
                            signedFullStops.single().optString("stopPrice")) else JSONObject.NULL)
                    .put("protection_client_id",if(signedFullStops.size==1)
                        signedFullStops.single().optString("clientAlgoId") else JSONObject.NULL)
                    .put("strategy_ownership_verified",matchingIntent?.optBoolean(
                        "strategy_ownership_verified",false)==true)
                    .put("automatic_trailing_authorized",ownedProtection || supervisedProtection)
                    .put("management_mode",if(supervisedProtection) supervision?.optString("mode")
                        else if(ownedProtection) "STRATEGY_OWNED" else "OBSERVE_ONLY"))
                if (matchingIntent == null && signedFullStops.isNotEmpty()) {
                    // A valid exchange stop is better than unprotected exposure.
                    // Presence of W2PS_ alone does not prove which installation
                    // owns the position, so never manufacture ENTRY/FILL state.
                    actualRiskAuditBlocked=true
                    actualRiskAuditBlocker=if(supervisedProtection)
                        "$symbol has operator-approved stop-only supervision; strategy ownership remains unknown, new entries blocked"
                        else "$symbol has an exchange-protected position but no verified local campaign ownership; no automatic trailing until ownership is restored"
                    continue
                }
                if (matchingIntent == null && latestIntentIndex == null) {
                    // The exchange is authoritative, but an orphan position must not
                    // be mistaken for a strategy-generated signal. Reconstruct only a
                    // deterministic emergency structural stop from closed H1 bars;
                    // keep the recovered campaign explicitly tagged and block new
                    // entries until the exchange position and protection are reconciled.
                    val exchangeEntry = position.optString("entryPrice").toDoubleOrNull() ?: 0.0
                    liveMark = runCatching { adapter.markPrice(symbol) }.getOrNull()
                    val recovered = if (exchangeEntry.isFinite() && exchangeEntry > 0.0 && liveMark != null) {
                        runCatching {
                            structuralStopRecoveryProvider?.invoke(
                                symbol, direction, liveMark!!, kotlin.math.abs(amount), exchangeEntry
                            )
                        }.getOrNull()
                    } else null
                    if (recovered == null ||
                        recovered.stopPrice.toDoubleOrNull()?.let {
                            isProtectiveStopOnCorrectSide(direction, liveMark.toString(), it.toString())
                        } != true ||
                        !recovered.riskPct.isFinite() || recovered.riskPct <= 0.0
                    ) {
                        anyUnprotected = true
                        lastBlocker = "$symbol has an exchange position without a recoverable protective H1 stop; manual close remains available, new entries blocked"
                        continue
                    }
                    val recoveryClientId = "W2E_RECOVER_${symbol.take(6)}_${clock().toString(16).takeLast(8)}"
                    matchingIntent = JSONObject()
                        .put("intent_role", "ENTRY")
                        .put("symbol", symbol)
                        .put("direction", direction)
                        .put("kind", "RECOVERED_EXCHANGE_POSITION")
                        .put("client_id", recoveryClientId)
                        .put("exchange_status", "POSITION_OPEN")
                        .put("campaign_status", "POSITION_OPEN")
                        .put("order_status", "FILLED")
                        .put("entry_price", exchangeEntry.toString())
                        .put("protective_stop", recovered.stopPrice)
                        .put("initial_protective_stop", recovered.stopPrice)
                        .put("risk_pct", recovered.riskPct)
                        .put("protection_status", "NOT_SUBMITTED")
                        .put("recovery_source", "EXCHANGE_POSITION_PLUS_FIVE_CLOSED_H1_BARS")
                        .put("strategy_ownership_verified", false)
                        .put("recovered_position_at_ms", clock())
                    persisted.put(matchingIntent)
                    saveIntents(persisted)
                    anyUnprotected = true
                    lastBlocker = "$symbol orphan exchange position is being emergency-protected from closed H1 structure; strategy ownership remains unverified and new entries are blocked"
                }
                if (matchingIntent != null) {
                    val exchangeEntry = position.optString("entryPrice").toDoubleOrNull() ?: 0.0
                    if (!exchangeEntry.isFinite() || exchangeEntry <= 0.0) {
                        anyUnprotected = true
                        lastBlocker = "$symbol exchange entry price is invalid; cannot recover structural protection"
                        continue
                    }
                    val persistedEntry = matchingIntent.optString("entry_price").toDoubleOrNull() ?: 0.0
                    if (!persistedEntry.isFinite() || persistedEntry <= 0.0) {
                        matchingIntent.put("entry_price", exchangeEntry.toString())
                            .put("exchange_entry_recovered_at_ms", clock())
                    }
                    val persistedStop = matchingIntent.optString("protective_stop").toDoubleOrNull() ?: 0.0
                    if (!persistedStop.isFinite() || persistedStop <= 0.0) {
                        val protectionStatus = matchingIntent.optString("protection_status", "NOT_SUBMITTED")
                            .uppercase(Locale.US)
                        val protectionId = matchingIntent.optString("protection_client_id")
                        if (protectionId.isNotBlank() &&
                            protectionStatus in setOf("PROTECTED", "SUBMISSION_UNKNOWN")
                        ) {
                            // The trigger may exist on Binance even when the local
                            // structural-price field was lost. Adopt it only by the
                            // exact durable client id, correct STOP_MARKET/closePosition
                            // semantics, matching side, and a fresh protective-side mark.
                            runCatching { adapter.queryAlgoOrder(symbol, protectionId) }
                            liveMark = runCatching { adapter.markPrice(symbol) }.getOrNull()
                            val exchangeProtection = (0 until algos.length()).mapNotNull { j ->
                                algos.optJSONObject(j)
                            }.firstOrNull { row ->
                                row.optString("clientAlgoId") == protectionId &&
                                    isProtectiveStop(row, direction)
                            }
                            val exchangeStop = exchangeProtection?.optString("triggerPrice")
                                ?.takeIf { it.isNotBlank() }
                                ?: exchangeProtection?.optString("stopPrice")
                                    ?.takeIf { it.isNotBlank() }
                            val stopValue = exchangeStop?.toDoubleOrNull()
                            if (exchangeProtection == null || stopValue == null ||
                                liveMark == null ||
                                !isProtectiveStopOnCorrectSide(direction, liveMark.toString(), stopValue.toString())
                            ) {
                                anyUnprotected = true
                                lastBlocker = "$symbol stop journal is missing and the exact persisted protection id is not confirmed as a live protective Futures stop; no replacement submitted"
                                matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
                                saveIntents(persisted)
                                continue
                            }
                            val account = runCatching { parseFuturesAccountSnapshot(adapter.account()) }.getOrNull()
                            val riskPct = account?.let {
                                kotlin.math.abs(exchangeEntry - stopValue) * kotlin.math.abs(amount) / it.equityUsdt
                            }?.takeIf { it.isFinite() && it > 0.0 }
                            matchingIntent
                                .put("protective_stop", stopValue.toString())
                                .put("protection_status", "PROTECTED")
                                .put("protection_recovered_from_exchange_at_ms", clock())
                            if (riskPct != null) {
                                val priorRisk = matchingIntent.optDouble("risk_pct", 0.0)
                                    .takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                                matchingIntent.put("risk_pct", maxOf(priorRisk, riskPct))
                            }
                            saveIntents(persisted)
                        } else if (protectionStatus.isBlank() || protectionStatus == "NOT_SUBMITTED") {
                            if (protectionId.isNotBlank()) {
                                anyUnprotected = true
                                lastBlocker = "$symbol stop price is missing but a protection client id is already journaled; ambiguous mutation will not be repeated"
                                matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
                                saveIntents(persisted)
                                continue
                            }
                            liveMark = runCatching { adapter.markPrice(symbol) }.getOrNull()
                            val recovered = liveMark?.let { mark ->
                                runCatching {
                                    structuralStopRecoveryProvider?.invoke(
                                        symbol, direction, mark,
                                        kotlin.math.abs(amount), exchangeEntry
                                    )
                                }.getOrNull()
                            }
                            if (recovered == null ||
                                recovered.stopPrice.toDoubleOrNull()?.let {
                                    isProtectiveStopOnCorrectSide(direction, liveMark.toString(), it.toString())
                                } != true ||
                                !recovered.riskPct.isFinite() || recovered.riskPct <= 0.0
                            ) {
                                anyUnprotected = true
                                lastBlocker = "$symbol has a known active Williams campaign but its structural stop is missing; five closed H1 bars/account equity are unavailable or cannot produce a stop on the protective side of mark"
                                continue
                            }
                            matchingIntent
                                .put("protective_stop", recovered.stopPrice)
                                .put("risk_pct", recovered.riskPct)
                                .put("protection_status", "NOT_SUBMITTED")
                                .put("recovery_stop_source", "H1_LAST_FIVE_CLOSED_BARS")
                                .put("recovery_stop_at_ms", clock())
                            saveIntents(persisted)
                        } else {
                            anyUnprotected = true
                            lastBlocker = "$symbol structural stop is missing while protection lifecycle is $protectionStatus; manual reconciliation required"
                            continue
                        }
                    }
                    val verifiedEntry = matchingIntent.optString("entry_price").toBigDecimalOrNull()
                    val verifiedStop = matchingIntent.optString("protective_stop").toBigDecimalOrNull()
                    if (verifiedEntry == null || verifiedEntry.signum() <= 0 ||
                        verifiedStop == null || verifiedStop.signum() <= 0
                    ) {
                        anyUnprotected = true
                        lastBlocker = "$symbol persisted campaign could not recover a valid structural stop; automatic trading blocked"
                        continue
                    }
                }
                // A legacy lost POST reply could retain OLD as current and the
                // accepted, tighter NEW as previous. Never cancel the tighter
                // stop as a predecessor: commit its promotion before cleanup.
                if (matchingIntent?.optBoolean("strategy_ownership_verified", false) == true) {
                    val owner = checkNotNull(matchingIntent)
                    val currentId = owner.optString("protection_client_id")
                    val pendingId = owner.optString("previous_protection_client_id")
                    val currentPrice = owner.optString("protective_stop").toBigDecimalOrNull()
                    val pendingPrice = owner.optString("previous_protective_stop").toBigDecimalOrNull()
                    if (currentId.isNotBlank() && pendingId.isNotBlank() && currentId != pendingId &&
                        currentPrice != null && pendingPrice != null &&
                        (if (direction == "LONG") pendingPrice > currentPrice else pendingPrice < currentPrice) &&
                        findProtectiveStop(algos, direction, currentId, currentPrice.toPlainString(), expectedSymbol = symbol) != null &&
                        findProtectiveStop(algos, direction, pendingId, pendingPrice.toPlainString(), expectedSymbol = symbol) != null &&
                        referenceMark != null && isProtectiveStopOnCorrectSide(direction, referenceMark.toString(), pendingPrice.toPlainString())) {
                        verifyNativeTrailExposure(adapter, owner)
                        owner.put("protection_client_id", pendingId).put("protective_stop", pendingPrice.toPlainString())
                            .put("previous_protection_client_id", currentId).put("previous_protective_stop", currentPrice.toPlainString())
                            .put("protection_status", "PROTECTED").put("trail_recovery_at_ms", clock())
                            .put("trailing_at_ms", clock()).put("last_confirmed_trail", JSONObject()
                                .put("reason", owner.optString("trail_attempt_reason", "RECOVERED_CONFIRMED_TIGHTER_STOP"))
                                .put("old_stop", currentPrice.toPlainString()).put("new_stop", pendingPrice.toPlainString())
                                .put("client_id", pendingId).put("strategy_profile", owner.optString("strategy_profile"))
                                .put("observed_at_ms", clock()))
                        owner.remove("legacy_stop_absent_first_at_ms")
                        saveIntents(persisted)
                    }
                }
                val currentProtectionId = matchingIntent?.optString("protection_client_id").orEmpty()
                val previousProtectionId = matchingIntent?.optString("previous_protection_client_id").orEmpty()
                val knownProtectionIds = setOf(currentProtectionId, previousProtectionId).filter { it.isNotBlank() }.toSet()
                if (matchingIntent != null && liveMark == null) {
                    liveMark = runCatching { adapter.markPrice(symbol) }.getOrNull()
                }
                val currentStopSafe = matchingIntent != null && liveMark != null &&
                    isProtectiveStopOnCorrectSide(
                        direction, liveMark.toString(), matchingIntent.optString("protective_stop")
                    )
                val previousStopPrice = matchingIntent?.optString("previous_protective_stop").orEmpty()
                val previousStopSafe = matchingIntent != null && liveMark != null &&
                    previousProtectionId.isNotBlank() && previousStopPrice.isNotBlank() &&
                    isProtectiveStopOnCorrectSide(direction, liveMark.toString(), previousStopPrice)
                if (matchingIntent != null && !currentStopSafe && !previousStopSafe) {
                    anyUnprotected = true
                    // There has NEVER been a STOP POST for this campaign:
                    // the original persisted lifecycle is NOT_SUBMITTED and
                    // there is not even a durable protection client ID. Do
                    // not fabricate a new stop across the mark. A verified
                    // reduce-only risk exit is safer than silent exposure.
                    // This proof is distinct from an unknown POST outcome.
                    val neverSubmitted =
                        matchingIntent.optBoolean("strategy_ownership_verified", false) &&
                        (matchingIntent.optString("protection_status", "NOT_SUBMITTED") == "NOT_SUBMITTED" ||
                            (matchingIntent.optString("protection_status") == "NEVER_SUBMITTED_PROVEN" &&
                                matchingIntent.optString("unprotected_safety_guard_origin")=="NEVER_SUBMITTED_PROVEN" &&
                                matchingIntent.optLong("unprotected_safety_exit_required_at_ms")>0L)) &&
                        matchingIntent.optString("protection_client_id").isBlank() &&
                        previousProtectionId.isBlank() && algos.length() == 0 &&
                        matchingIntent.optString("confirmed_position_qty").toBigDecimalOrNull()
                            ?.compareTo(position.getString("positionAmt").toBigDecimal().abs()) == 0
                    if (neverSubmitted) {
                        val guard = JSONObject().put("symbol",symbol).put("direction",direction)
                            .put("entry_client_id",matchingIntent.optString("client_id"))
                            .put("quantity",matchingIntent.optString("confirmed_position_qty"))
                            .put("missing_protection_client_id","")
                            .put("guard_origin","NEVER_SUBMITTED_PROVEN")
                            .put("reason","UNPROTECTED_POSITION_GUARD")
                        requiredSafetyExits.put(guard)
                        matchingIntent.put("protection_status","NEVER_SUBMITTED_PROVEN")
                            .put("unprotected_safety_guard_origin","NEVER_SUBMITTED_PROVEN")
                            .put("unprotected_safety_exit_required_at_ms",
                                matchingIntent.optLong("unprotected_safety_exit_required_at_ms")
                                    .takeIf { it>0L } ?: clock())
                        saveIntents(persisted)
                        lastBlocker="$symbol initial protective stop was never submitted and is now invalid relative to the mark; verified emergency reduction required"
                    } else {
                        // The first missing-SL rescue was masked by this
                        // stale-price guard when the market had ALREADY crossed
                        // the former stop. Query the EXACT previously confirmed
                        // algo before deciding whether risk reduction is allowed.
                        val knownId=matchingIntent.optString("protection_client_id")
                        val status=matchingIntent.optString("protection_status")
                        val previouslyProtected=status=="PROTECTED" ||
                            (status=="SUBMISSION_UNKNOWN" &&
                                matchingIntent.optString("unprotected_safety_guard_origin")==
                                    "TERMINAL_PREVIOUSLY_CONFIRMED" &&
                                matchingIntent.optLong("unprotected_safety_exit_required_at_ms")>0L)
                        val terminalOrder=if(previouslyProtected && knownId.isNotBlank() &&
                            previousProtectionId.isBlank() && algos.length()==0) {
                            runCatching { adapter.queryAlgoOrder(symbol,knownId) }.getOrNull()
                        } else null
                        val terminal=terminalOrder?.optString("algoStatus")?.uppercase(Locale.US)
                        val verifiedTerminal=matchesTerminalUntriggeredFuturesProtectiveStop(
                            terminalOrder,symbol,direction,knownId,matchingIntent.optString("protective_stop")) &&
                            matchingIntent.optBoolean("strategy_ownership_verified",false) &&
                            matchingIntent.optString("confirmed_position_qty").toBigDecimalOrNull()
                                ?.compareTo(position.getString("positionAmt").toBigDecimal().abs())==0
                        if(verifiedTerminal) {
                            requiredSafetyExits.put(JSONObject()
                                .put("symbol",symbol).put("direction",direction)
                                .put("entry_client_id",matchingIntent.optString("client_id"))
                                .put("quantity",matchingIntent.optString("confirmed_position_qty"))
                                .put("missing_protection_client_id",knownId)
                                .put("last_stop_exchange_status",terminal)
                                .put("reason","UNPROTECTED_POSITION_GUARD"))
                            matchingIntent.put("protection_status","SUBMISSION_UNKNOWN")
                                .put("unprotected_safety_guard_origin","TERMINAL_PREVIOUSLY_CONFIRMED")
                                .put("unprotected_safety_exit_required_at_ms",
                                    matchingIntent.optLong("unprotected_safety_exit_required_at_ms")
                                        .takeIf { it>0L } ?: clock())
                            saveIntents(persisted)
                            lastBlocker="$symbol canceled protective stop ($terminal) is already past mark; verified emergency risk reduction required"
                        } else {
                            lastBlocker = "$symbol neither the current stop nor its tracked predecessor is confirmed on the protective side of Binance mark price; entries and automatic closure are blocked pending reconciliation"
                        }
                    }
                    continue
                }
                val unknownProtectiveStopExists = (0 until algos.length()).any { j ->
                    val row = algos.optJSONObject(j) ?: return@any true
                    isProtectiveStop(row, direction) && row.optString("clientAlgoId") !in knownProtectionIds
                }
                if (unknownProtectiveStopExists) {
                    anyUnprotected = true
                    lastBlocker = "$symbol has a protective stop whose client id is not owned by the latest persisted campaign; manual reconciliation required"
                    matchingIntent?.put("protection_status", "UNRESOLVED_EXTRA_ORDER")
                    continue
                }
                var protective = if (currentStopSafe) {
                    findProtectiveStop(
                        algos, direction,
                        matchingIntent?.optString("protection_client_id"),
                        matchingIntent?.optString("protective_stop"),
                        expectedSymbol = symbol
                    )
                } else null

                // Recover a crash between journaling a new trailing-stop id and
                // confirming that replacement. The exact previous stop is still
                // exchange-visible, so restore it as primary and track the attempted
                // replacement as the predecessor candidate for cleanup below.
                if (protective == null && matchingIntent != null &&
                    previousProtectionId.isNotBlank() && previousStopSafe
                ) {
                    val predecessor = findProtectiveStop(
                        algos, direction, previousProtectionId, previousStopPrice,
                        expectedSymbol = symbol
                    )
                    if (predecessor != null) {
                        val attemptedClientId = currentProtectionId
                        val attemptedStop = matchingIntent.optString("protective_stop")
                        if (attemptedClientId.isBlank() || attemptedClientId == previousProtectionId) {
                            anyUnprotected = true
                            lastBlocker = "$symbol trailing-stop replacement journal is inconsistent; manual reconciliation required"
                            continue
                        }
                        matchingIntent.put("protection_client_id", previousProtectionId)
                            .put("protective_stop", previousStopPrice)
                            .put("previous_protection_client_id", attemptedClientId)
                            .put("previous_protective_stop", attemptedStop)
                            .put("protection_status", "SUBMISSION_UNKNOWN")
                            .put("trail_recovery_at_ms", clock())
                        saveIntents(persisted)
                        protective = predecessor
                    }
                }

                if (protective == null) {
                    if (matchingIntent == null) {
                        anyUnprotected = true
                        lastBlocker = "$symbol has an exchange position but no persisted structural stop; automatic trading blocked"
                        continue
                    }
                    val protectionStatus = matchingIntent.optString("protection_status", "NOT_SUBMITTED")
                        .uppercase(Locale.US)
                    var protectionClientId = matchingIntent.optString("protection_client_id")
                    if (protectionStatus == "SUBMISSION_UNKNOWN" || protectionStatus == "PROTECTED") {
                        if (protectionClientId.isBlank()) {
                            anyUnprotected = true
                            lastBlocker = "$symbol protective-stop state is ambiguous and has no client id; no retry performed"
                            matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
                            continue
                        }
                        // Query only. A missing order after an ambiguous/protected
                        // state is not permission to resend the same client id.
                        val stopQuery = runCatching { adapter.queryAlgoOrder(symbol, protectionClientId) }.getOrNull()
                        val refreshed = adapter.openAlgoOrders(symbol)
                        protective = findProtectiveStop(
                            refreshed, direction, protectionClientId,
                            matchingIntent.optString("protective_stop"),
                            expectedSymbol = symbol
                        )
                        if (protective == null) {
                            anyUnprotected = true
                            // A known formerly confirmed stop is now explicitly
                            // terminal, the live exchange has no other algo for
                            // this symbol, and there is no replacement candidate.
                            // A 404/timeout is NOT proof of a terminal stop:
                            // it must NEVER authorize a second financial mutation.
                            val terminal = stopQuery?.optString("algoStatus", "")
                                ?.uppercase(Locale.US)
                            val definitelyMissing =
                                (protectionStatus == "PROTECTED" ||
                                    (protectionStatus == "SUBMISSION_UNKNOWN" &&
                                        matchingIntent.optString("unprotected_safety_guard_origin")=="TERMINAL_PREVIOUSLY_CONFIRMED" &&
                                        matchingIntent.optLong("unprotected_safety_exit_required_at_ms")>0L)) &&
                                matchesTerminalUntriggeredFuturesProtectiveStop(stopQuery,symbol,direction,
                                    protectionClientId,matchingIntent.optString("protective_stop")) &&
                                refreshed.length() == 0 &&
                                previousProtectionId.isBlank() &&
                                matchingIntent.optBoolean("strategy_ownership_verified", false) &&
                                matchingIntent.optString("confirmed_position_qty").toBigDecimalOrNull()
                                    ?.compareTo(position.getString("positionAmt").toBigDecimal().abs()) == 0
                            if (definitelyMissing) {
                                val emergency = JSONObject()
                                    .put("symbol", symbol).put("direction", direction)
                                    .put("entry_client_id", matchingIntent.optString("client_id"))
                                    .put("quantity", matchingIntent.optString("confirmed_position_qty"))
                                    .put("missing_protection_client_id", protectionClientId)
                                    .put("reason", "UNPROTECTED_POSITION_GUARD")
                                    .put("last_stop_exchange_status", terminal)
                                requiredSafetyExits.put(emergency)
                                lastBlocker = "$symbol formerly confirmed protective stop is terminal ($terminal); verified emergency reduce-only exit required"
                                matchingIntent.put("unprotected_safety_guard_origin","TERMINAL_PREVIOUSLY_CONFIRMED")
                                    .put("unprotected_safety_exit_required_at_ms",
                                        matchingIntent.optLong("unprotected_safety_exit_required_at_ms")
                                            .takeIf { it>0L } ?: clock())
                            } else {
                                lastBlocker = "$symbol protective-stop outcome is ambiguous; no retry performed"
                            }
                            matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
                            saveIntents(persisted)
                            continue
                        }
                    } else if (protectionStatus != "NOT_SUBMITTED" && protectionStatus.isNotBlank()) {
                        anyUnprotected = true
                        lastBlocker = "$symbol protective-stop lifecycle is unresolved ($protectionStatus); no new stop submitted"
                        matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
                        continue
                    } else {
                        if (protectionClientId.isBlank()) {
                            protectionClientId = stableProtectionId(symbol, matchingIntent.optString("client_id"))
                            matchingIntent.put("protection_client_id", protectionClientId)
                        }
                        matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
                            .put("protection_attempt_at_ms", clock())
                        matchingIntent.remove("protection_post_attempted")
                        saveIntents(persisted)
                        try {
                            adapter.placeProtectiveStop(
                                symbol, direction,
                                matchingIntent.optString("protective_stop"),
                                protectionClientId
                            )
                            matchingIntent.put("protection_post_attempted",true)
                        } catch (failure: Exception) {
                            anyUnprotected = true
                            if(failure is FuturesProtectionNotSubmittedException) {
                                // The adapter proves that its STOP POST was never
                                // entered. Restore a retryable lifecycle, rather
                                // than querying a phantom id forever. If mark has
                                // crossed the stop, the next reconciliation emits
                                // the existing NEVER_SUBMITTED safety capability.
                                matchingIntent.put("protection_status","NOT_SUBMITTED")
                                    .put("protection_preflight_rejected_at_ms",clock())
                                    .put("protection_post_attempted",false)
                                matchingIntent.remove("protection_client_id")
                                saveIntents(persisted)
                                lastBlocker="$symbol protective STOP was not submitted; protection will be re-evaluated without duplicating a POST"
                            } else {
                                lastBlocker = "$symbol protective-stop placement ambiguous: ${failure.message ?: failure.javaClass.simpleName}"
                            }
                            continue
                        }
                        val refreshed = adapter.openAlgoOrders(symbol)
                        protective = findProtectiveStop(
                            refreshed, direction, protectionClientId,
                            matchingIntent.optString("protective_stop"),
                            expectedSymbol = symbol
                        )
                        if (protective == null) {
                            anyUnprotected = true
                            lastBlocker = "$symbol protective stop was submitted but its exact client id/structural trigger is not confirmed in open algo orders"
                            matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
                            continue
                        }
                    }
                }

                if (matchingIntent != null) {
                    val confirmedClientId = protective?.optString("clientAlgoId")
                        ?.takeIf { it.isNotBlank() } ?: matchingIntent.optString("protection_client_id")
                    val confirmedStop = protective?.optString("triggerPrice")?.takeIf { it.isNotBlank() }
                        ?: protective?.optString("stopPrice")?.takeIf { it.isNotBlank() }
                        ?: matchingIntent.optString("protective_stop")
                    val positionEntry = position.optString("entryPrice").toDoubleOrNull()
                        ?: throw IllegalStateException("$symbol exchange position entry price is malformed")
                    val positionQty = kotlin.math.abs(
                        position.optString("positionAmt").toDoubleOrNull()
                            ?: throw IllegalStateException("$symbol exchange position quantity is malformed")
                    )
                    check(positionEntry.isFinite() && positionEntry > 0.0 &&
                        positionQty.isFinite() && positionQty > 0.0
                    ) { "$symbol exchange position lacks a valid entry price/quantity for audit" }
                    val confirmedAt = clock()
                    matchingIntent.put("campaign_status", "OPEN_PROTECTED")
                        .put("protection_status", "PROTECTED")
                        .put("protection_client_id", confirmedClientId)
                        .put("protective_stop", confirmedStop)
                        .put("actual_entry_price", positionEntry.toString())
                        .put("confirmed_position_qty", positionQty.toString())
                        .put("position_open_observed_at_ms",
                            matchingIntent.optLong("position_open_observed_at_ms", confirmedAt).takeIf { it > 0L } ?: confirmedAt)
                        .put("last_reconciled_ms", confirmedAt)

                    // A currently active stop is not proof that the filled quantity still
                    // fits the original risk allocation. Recompute against live exchange
                    // quantity/entry, original structural stop, and fresh Futures equity.
                    val originalStop = matchingIntent.optString("initial_protective_stop")
                        .toDoubleOrNull()
                    var positionRiskBlocker: String? = null
                    if (actualRiskAccount == null) {
                        actualRiskAuditBlocked = true
                        positionRiskBlocker =
                            "$symbol stop is exchange-confirmed but actual risk cannot be proven without a fresh Futures equity snapshot"
                        matchingIntent.put("actual_risk_audit_status", "UNKNOWN_ACCOUNT")
                    } else if (originalStop == null) {
                        actualRiskAuditBlocked = true
                        positionRiskBlocker =
                            "$symbol stop is exchange-confirmed but original structural stop is missing; manual risk reconciliation required"
                        matchingIntent.put("actual_risk_audit_status", "UNKNOWN_ORIGINAL_STOP")
                    } else {
                        val additions=(0 until persisted.length()).mapNotNull { persisted.optJSONObject(it) }
                            .filter { it.optString("intent_role")=="ADD_ON" && it.optString("entry_client_id")==matchingIntent.optString("client_id") }
                        val reservedPct = futuresCampaignReservedRisk(matchingIntent,persisted)
                        val riskAudit = if(additions.any { it.optString("executed_qty","0").toDoubleOrNull()?.let { q -> q>0 }==true })
                            assessActualFuturesCampaignRisk(direction,positionQty,positionEntry,
                                matchingIntent.getString("protective_stop").toDouble(),actualRiskAccount.equityUsdt,reservedPct)
                        else assessActualFuturesPositionRisk(
                            direction = direction,
                            quantity = positionQty,
                            actualEntryPrice = positionEntry,
                            originalStructuralStop = originalStop,
                            equityUsdt = actualRiskAccount.equityUsdt,
                            reservedRiskPct = reservedPct
                        )
                        matchingIntent
                            .put("actual_gross_stop_loss_usdt", riskAudit.grossStopLossUsdt)
                            .put("actual_fee_slippage_allowance_usdt", riskAudit.estimatedFeesAndSlippageUsdt)
                            .put("actual_all_in_stop_loss_usdt", riskAudit.estimatedAllInLossUsdt)
                            .put("actual_all_in_risk_pct", riskAudit.actualAllInRiskPct)
                            .put("actual_risk_equity_snapshot_usdt", actualRiskAccount.equityUsdt)
                            .put("actual_risk_audited_at_ms", confirmedAt)
                        if (riskAudit.blocker != null &&
                            riskAudit.blocker.startsWith("ACTUAL_FUTURES_RISK_EXCEEDS_RESERVATION:")
                        ) {
                            val currentClientIdForRiskAudit = matchingIntent.optString("client_id")
                            val otherActiveRisks = (0 until persisted.length()).flatMap { riskIndex ->
                                val other=persisted.getJSONObject(riskIndex)
                                if(other.optString("intent_role","ENTRY")=="ENTRY" && other.optString("client_id")!=currentClientIdForRiskAudit &&
                                    campaignLifecycleStatus(other) in ACTIVE_CAMPAIGN_STATUSES) futuresCampaignRiskComponents(other,persisted) else emptyList()
                            }
                            val adjustmentBlocker = actualRiskReservationAdjustmentBlocker(
                                actualRiskPct = riskAudit.actualAllInRiskPct,
                                otherActiveRiskPcts = otherActiveRisks
                            )
                            if (adjustmentBlocker == null) {
                                // Exchange-confirmed fill/price/costs are authoritative for
                                // measured risk. Reconcile the reservation upward only after
                                // proving both hard caps still hold; never move the stop or
                                // permit risk to exceed the approved per-entry/portfolio limits.
                                matchingIntent
                                    .put("risk_pct", riskAudit.actualAllInRiskPct)
                                    .put("risk_reservation_adjusted_at_ms", confirmedAt)
                                    .put("risk_reservation_adjustment_reason", "EXCHANGE_CONFIRMED_FILL_COST_RECONCILIATION")
                                    .put("actual_risk_audit_status", "PASS_RESERVATION_ADJUSTED")
                                    .put("actual_risk_reservation_increase_usdt",
                                        (riskAudit.estimatedAllInLossUsdt - riskAudit.reservedRiskUsdt).coerceAtLeast(0.0))
                                matchingIntent.remove("actual_risk_audit_blocker")
                            } else {
                                actualRiskAuditBlocked = true
                                positionRiskBlocker = adjustmentBlocker
                                matchingIntent.put("actual_risk_audit_status", "BLOCKED")
                                    .put("actual_risk_audit_blocker", adjustmentBlocker)
                            }
                        } else if (riskAudit.blocker != null) {
                            actualRiskAuditBlocked = true
                            positionRiskBlocker = riskAudit.blocker
                            matchingIntent.put("actual_risk_audit_status", "BLOCKED")
                        } else {
                            matchingIntent.put("actual_risk_audit_status", "PASS")
                            matchingIntent.remove("actual_risk_audit_blocker")
                        }
                    }
                    // Account-wide blockers (for example an unrelated reversed
                    // ETH campaign) must not contaminate this position's PASS.
                    if (positionRiskBlocker != null) {
                        actualRiskAuditBlocker = positionRiskBlocker
                        matchingIntent.put("actual_risk_audit_blocker", positionRiskBlocker)
                    } else matchingIntent.remove("actual_risk_audit_blocker")
                    for(j in 0 until persisted.length()) {
                        val child=persisted.optJSONObject(j) ?: continue
                        if(child.optString("intent_role")!="ADD_ON" || child.optString("entry_client_id")!=matchingIntent.optString("client_id")) continue
                        val execution=entryExecutions[child.optString("client_id")] ?: continue
                        val quantity=execution.optString("executedQty","0").toDoubleOrNull() ?: continue
                        if(quantity<=0) continue
                        val check=assessActualFuturesPositionRisk(direction,quantity,execution.getString("avgPrice").toDouble(),
                            child.getString("initial_protective_stop").toDouble(),child.optDouble("risk_equity_snapshot_usdt",Double.NaN),
                            child.optDouble("risk_pct",Double.NaN))
                        child.put("actual_risk_audit_status",if(check.blocker==null) "PASS" else "BLOCKED")
                            .put("actual_all_in_risk_pct",check.actualAllInRiskPct)
                        if(check.blocker!=null) {
                            actualRiskAuditBlocked=true;actualRiskAuditBlocker="ADDITION_FILL_RISK: "+check.blocker
                            child.put("actual_risk_audit_blocker",actualRiskAuditBlocker)
                        }
                    }
                    val liquidation = assessFuturesLiquidationProtection(
                        direction, confirmedStop, position.opt("liquidationPrice")
                    )
                    matchingIntent.put("liquidation_guard_status", liquidation.status)
                        .put("liquidation_guard_blocker", liquidation.blocker ?: JSONObject.NULL)
                        .put("exchange_liquidation_price", liquidation.liquidationPrice ?: JSONObject.NULL)
                        .put("liquidation_stop_gap_pct", liquidation.stopGapPct ?: JSONObject.NULL)
                        .put("liquidation_guard_audited_at_ms", confirmedAt)
                    if (liquidation.blocker != null) {
                        actualRiskAuditBlocked = true
                        actualRiskAuditBlocker = "$symbol ${liquidation.blocker}"
                        if (liquidation.status == "UNSAFE" &&
                            matchingIntent.optBoolean("strategy_ownership_verified", false)
                        ) {
                            requiredSafetyExits.put(JSONObject().put("symbol", symbol)
                                .put("direction", direction).put("quantity", position.getString("positionAmt").toBigDecimal().abs().toPlainString())
                                .put("entry_client_id", matchingIntent.optString("client_id"))
                                .put("reason", "LIQUIDATION_GUARD"))
                        }
                    }
                    if (!matchingIntent.optBoolean("strategy_ownership_verified", false)) {
                        // Emergency protection is not proof of a Williams entry signal.
                        // Keep the position protected and manually closable, but do not
                        // allow new entries until the operator resolves ownership.
                        anyUnprotected = true
                        lastBlocker = "$symbol is emergency-protected, but strategy ownership is unverified; new entries remain blocked"
                    }

                    // A crash during stop replacement may leave both the new stop and
                    // its previously persisted predecessor open. Remove only the known
                    // predecessor after confirming the replacement; unknown extra stops
                    // require reconciliation instead of speculative cancellation.
                    val previousClientId = matchingIntent.optString("previous_protection_client_id")
                    var unmanagedExtraStop = false
                    val currentAlgos = adapter.openAlgoOrders(symbol)
                    for (j in 0 until currentAlgos.length()) {
                        val old = currentAlgos.optJSONObject(j)
                            ?: throw IOException("Binance returned a malformed algo-order row during protection reconciliation for $symbol")
                        if (!isProtectiveStop(old, direction)) continue
                        val oldClientId = old.optString("clientAlgoId")
                        if (oldClientId == confirmedClientId) continue
                        if (previousClientId.isNotBlank() && oldClientId == previousClientId) {
                            check(matchesConfirmedFuturesProtectiveStop(old, direction, previousClientId,
                                matchingIntent.optString("previous_protective_stop"))) {
                                "Tracked predecessor attributes conflict with the durable stop identity"
                            }
                            verifyNativeTrailExposure(adapter, matchingIntent)
                            check(findProtectiveStop(adapter.openAlgoOrders(symbol), direction, confirmedClientId, confirmedStop, expectedSymbol = symbol) != null)
                            val oldAlgoId = old.optString("algoId")
                            if (oldAlgoId.isNotBlank()) adapter.cancelAlgoOrderById(symbol, oldAlgoId)
                            else if (oldClientId.isNotBlank()) adapter.cancelAlgoOrder(symbol, oldClientId)
                            else throw IOException("Cannot identify predecessor Futures protective stop")
                        } else {
                            unmanagedExtraStop = true
                        }
                    }
                    if (unmanagedExtraStop) {
                        anyUnprotected = true
                        lastBlocker = "$symbol has an additional unrecognized protective stop; manual reconciliation required"
                        matchingIntent.put("protection_status", "UNRESOLVED_EXTRA_ORDER")
                        continue
                    }
                    if (previousClientId.isNotBlank()) {
                        // Cancellation acknowledgement alone is insufficient. Verify
                        // the primary stop still exists at its exact trigger and the
                        // tracked predecessor is no longer present before forgetting its id.
                        val afterCleanup = adapter.openAlgoOrders(symbol)
                        val primaryStillConfirmed = findProtectiveStop(
                            afterCleanup, direction, confirmedClientId, confirmedStop,
                            expectedSymbol = symbol
                        ) != null
                        val trackedPredecessorStillOpen = (0 until afterCleanup.length()).any { k ->
                            val row = afterCleanup.optJSONObject(k) ?: return@any true
                            isProtectiveStop(row, direction) &&
                                row.optString("clientAlgoId") == previousClientId
                        }
                        if (!primaryStillConfirmed || trackedPredecessorStillOpen) {
                            anyUnprotected = true
                            lastBlocker = "$symbol protective-stop replacement/cancellation has not been exchange-confirmed; prior and attempted ids remain journaled"
                            matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
                            continue
                        }
                        if (!settleLegacyNativeStop(adapter, persisted, matchingIntent)) {
                            actualRiskAuditBlocked = true
                            actualRiskAuditBlocker = "$symbol uncertain legacy stop identity awaits signed history/cancellation fence; confirmed current stop retained"
                            saveIntents(persisted)
                            continue
                        }
                        matchingIntent.remove("previous_protection_client_id")
                        matchingIntent.remove("previous_protective_stop")
                        matchingIntent.remove("legacy_stop_absent_first_at_ms")
                        (0 until protectionObservations.length()).map { protectionObservations.getJSONObject(it) }
                            .singleOrNull { it.optString("symbol") == symbol &&
                                matchingIntent.optBoolean("strategy_ownership_verified", false) }?.apply {
                                put("stop_proof", "OWNED_STOP_SEEN"); put("stop", confirmedStop)
                                put("protection_client_id", confirmedClientId); put("observed_at_ms", clock())
                                put("automatic_trailing_authorized", matchingIntent.optBoolean("strategy_ownership_verified"))
                                put("management_mode", "STRATEGY_OWNED")
                            }
                    }
                    // The first observation preceded STOP recovery/placement.
                    // Publish the signed final proof after all replacement and
                    // predecessor checks, even when another symbol stays blocked.
                    (0 until protectionObservations.length()).map { protectionObservations.getJSONObject(it) }
                        .singleOrNull { it.optString("symbol") == symbol }?.apply {
                            val owned = matchingIntent.optBoolean("strategy_ownership_verified", false)
                            put("stop_proof", if (owned) "OWNED_STOP_SEEN" else "EXTERNAL_PROTECTED")
                            put("stop", confirmedStop)
                            put("protection_client_id", confirmedClientId)
                            put("observed_at_ms", clock())
                            put("strategy_ownership_verified", owned)
                            put("automatic_trailing_authorized", owned)
                            put("management_mode", if (owned) "STRATEGY_OWNED" else "OBSERVE_ONLY")
                        }
                }
            }

            // Historical terminal intents may still carry STOP identity for
            // attribution. Profile this replay separately: repeated signed
            // orphan-STOP lookups can dominate an otherwise flat sync.
            noteReconciliationPhase("SETTLING_HISTORICAL_FLAT_CAMPAIGNS")
            // All historical owners in this pass see the same durable EXIT
            // ledger. Index it ONCE (O(n)) rather than re-scanning every exit
            // for every closed campaign (O(n^2)). A malformed row still vetoes
            // finalization globally, exactly as the previous conservative rule.
            val unresolvedExitIndex = buildWilliamsUnresolvedExitIndex(persisted)
            // Once Binance reports the symbol flat, prior position intents are terminal.
            for (i in 0 until persisted.length()) {
                val intent = persisted.optJSONObject(i) ?: continue
                if (intent.optString("intent_role", "ENTRY") != "ENTRY") continue
                val symbol = intent.optString("symbol").uppercase(Locale.US)
                val status = campaignLifecycleStatus(intent)
                val unresolvedExitForSymbol = unresolvedExitIndex.mayHaveUnresolvedExit(symbol)
                val nowMs = clock()
                val firstFlatAfterFill = intent.optLong("flat_after_fill_first_seen_at_ms", 0L)
                val filledCampaignCanNowClose = shouldFinalizeFilledFlatCampaign(
                    lifecycleStatus = status,
                    symbolIsFlat = symbol !in openBySymbol,
                    hasUnresolvedExit = unresolvedExitForSymbol,
                    firstFlatObservedAtMs = firstFlatAfterFill,
                    nowMs = nowMs
                )
                if (symbol !in openBySymbol && status == "FILL_CONFIRMATION_PENDING" &&
                    firstFlatAfterFill <= 0L
                ) {
                    intent.put("flat_after_fill_first_seen_at_ms", nowMs)
                        .put("flat_after_fill_confirmation_count", 1)
                }
                if (
                    symbol !in openBySymbol &&
                    (status in TERMINAL_CAMPAIGN_STATUSES ||
                        status in setOf("OPEN_PROTECTED", "POSITION_OPEN", "EXIT_PENDING") ||
                        filledCampaignCanNowClose)
                ) {
                    if (status in setOf("OPEN_PROTECTED", "POSITION_OPEN", "EXIT_PENDING") ||
                        filledCampaignCanNowClose
                    ) {
                        intent.put("campaign_status", "CLOSED_OR_STOPPED")
                            .put("closed_observed_at_ms", nowMs)
                    }
                    // A closed historical campaign with NO remembered native STOP
                    // cannot trigger exchange cleanup. It must not rewrite the
                    // entire SQLite intent journal for every old row (quadratic
                    // serialized I/O and observer work). The final commit below
                    // persists benign migrations/metadata in one batch.
                    //
                    // Preserve the strict write-before-network rule for every
                    // newly finalized owner and any owner with a known STOP ID.
                    val tx = intent.optJSONObject("native_stop_replacement")
                    val knownProtectionIds = listOf(
                        intent.optString("protection_client_id"), intent.optString("previous_protection_client_id"),
                        tx?.optString("old_id").orEmpty(), tx?.optString("new_id").orEmpty(),
                        tx?.optString("bridge_id").orEmpty()
                    ).filter { it.isNotBlank() }.toSet()
                    if (status != "CLOSED_OR_STOPPED" || knownProtectionIds.isNotEmpty()) {
                        // Crash/ambiguous cancellation must never resurrect an
                        // owner; write terminal state BEFORE any signed cleanup.
                        saveIntents(persisted)
                    }
                    // A stop-triggered or manually closed position must not leave a
                    // closePosition algo armed to affect a future campaign on this symbol.
                    if (knownProtectionIds.isNotEmpty()) {
                        val algos = adapter.openAlgoOrders(symbol)
                        for (j in 0 until algos.length()) {
                            val order = algos.optJSONObject(j)
                                ?: throw IOException("Binance returned a malformed algo-order row during orphan-stop cleanup for $symbol")
                            val clientAlgoId = order.optString("clientAlgoId")
                            if (clientAlgoId !in knownProtectionIds) continue
                            // An earlier account-wide flat snapshot is NOT authority to
                            // cancel protection now: exposure may have reappeared between
                            // the snapshot and the per-symbol cleanup pass.
                            val freshPositions = validateFuturesPositionSnapshot(adapter.positions())
                            check((0 until freshPositions.length()).none { n ->
                                val fresh = freshPositions.getJSONObject(n)
                                fresh.optString("symbol") == symbol &&
                                    fresh.getString("positionAmt").toBigDecimal().signum() != 0
                            }) { "$symbol reopened before orphan STOP cleanup; do not cancel protection" }
                            val expectedStop = when (clientAlgoId) {
                                tx?.optString("old_id") -> tx?.optString("old_stop").orEmpty()
                                tx?.optString("bridge_id"), tx?.optString("new_id") -> tx?.optString("new_stop").orEmpty()
                                intent.optString("previous_protection_client_id") -> intent.optString("previous_protective_stop")
                                intent.optString("protection_client_id") -> intent.optString("protective_stop")
                                else -> ""
                            }.toBigDecimalOrNull()
                            val actualStop = order.optString("triggerPrice", order.optString("stopPrice"))
                                .toBigDecimalOrNull()
                            val isBridge = clientAlgoId == tx?.optString("bridge_id")
                            val bridgeQty = tx?.optString("quantity")?.toBigDecimalOrNull()
                            val correctCoverage = if (isBridge) {
                                bridgeQty != null &&
                                    !order.optString("closePosition").equals("true", true) &&
                                    order.optString("reduceOnly").equals("true", true) &&
                                    order.optString("quantity").toBigDecimalOrNull()?.compareTo(bridgeQty) == 0
                            } else order.optString("closePosition").equals("true", true)
                            check(expectedStop != null && actualStop?.compareTo(expectedStop) == 0 &&
                                order.optString("symbol") == symbol &&
                                order.optString("side") == (if (intent.optString("direction") == "LONG") "SELL" else "BUY") &&
                                order.optString("positionSide", "BOTH") == "BOTH" &&
                                order.optString("workingType") == "MARK_PRICE" &&
                                order.optString("orderType", order.optString("type")) == "STOP_MARKET" &&
                                isActiveFuturesProtectionStatus(order.optString("algoStatus")) &&
                                correctCoverage) {
                                "$symbol stored STOP identity/side/price/coverage mismatches Binance; manual reconciliation required"
                            }
                            val algoId = order.optString("algoId")
                            check(algoId.toLongOrNull()?.let { it > 0 } == true) {
                                "$symbol missing positive Binance algoId; cancellation blocked"
                            }
                            adapter.cancelAlgoOrderById(symbol, algoId)
                        }
                        val remaining = adapter.openAlgoOrders(symbol)
                        if ((0 until remaining.length()).any { j ->
                            remaining.optJSONObject(j)?.optString("clientAlgoId")?.let { it in knownProtectionIds } == true
                        }) {
                            throw IOException("Orphaned protective Futures algo remains open for flat $symbol")
                        }
                        intent.remove("previous_protection_client_id")
                        intent.remove("previous_protective_stop")
                        if (intent.has("native_stop_replacement") &&
                            !nativeStopReplacementEngine(adapter, persisted).settleFlat(intent)) {
                            actualRiskAuditBlocked = true
                            actualRiskAuditBlocker = "$symbol flat position has an unresolved native STOP submission identity"
                        }
                    }
                }
            }
            noteReconciliationPhase("COURSE_FILL_AND_FLAT_HANDOFF_REPLAY")
            recordCourseStageFills(persisted,entryExecutions,openBySymbol,adapter)
            recordCourseFlatHandoffs(persisted,adapter)
            saveIntents(persisted)
            externalProtectionObservationsJson=protectionObservations.toString()
            // Existing unknown orders block NEW risk, but must not freeze
            // stop-tightening for a DIFFERENT, completely verified campaign.
            val otherRiskBlock=actualRiskAuditBlocked
            if(externalOrders.length()>0) {
                actualRiskAuditBlocked=true
                if(actualRiskAuditBlocker==null)
                    actualRiskAuditBlocker="Some open Futures orders have no verified campaign journal; review per-symbol exchange protection before resuming entries"
            }

            val hasPendingEntry = hasPendingAddition || (0 until persisted.length()).any { index ->
                val intent = persisted.optJSONObject(index) ?: return@any true
                val symbol = intent.optString("symbol").uppercase(Locale.US)
                intent.optString("intent_role", "ENTRY") == "ENTRY" &&
                    symbol !in openBySymbol &&
                    campaignLifecycleStatus(intent) in setOf("ENTRY_PENDING", "FILL_CONFIRMATION_PENDING")
            }
            // A strictly verified over-cap portfolio may continue risk-REDUCING
            // management. Failed reconciliation remains failed and entries stay locked.
            // Allow only a narrowly scoped maintenance exception when the
            // SOLE global reconciliation blocker is a foreign order on an
            // unrelated symbol. A closed/read-only campaign gets no permission.
            val externalOrderOnlyScope=if(
                externalOrders.length()>0 && !otherRiskBlock &&
                !anyUnprotected && !hasPendingExit && !hasPendingAddition && !hasPendingEntry &&
                requiredSafetyExits.length()==0
            ) {
                verifiedExternalStopManagementScope(
                    protectionObservations,externalOrders,persisted
                )
            } else emptySet()
            val emergencySymbols=(0 until requiredSafetyExits.length()).map {
                requiredSafetyExits.getJSONObject(it).optString("symbol")
            }.toSet()
            val isolatedQuarantineScope=verifiedQuarantineStopManagementScope(
                protectionObservations,externalOrders,persisted,reversedCampaignSymbols,clock()
            ) - emergencySymbols
            val eligibleExternalStopManagement=externalOrderOnlyScope + isolatedQuarantineScope
            externalStopManagementSymbolsJson=JSONArray(eligibleExternalStopManagement.sorted()).toString()
            externalStopManagementVerifiedAtMs=if(eligibleExternalStopManagement.isNotEmpty()) clock() else 0L
            val verifiedOverCap = !anyUnprotected && !hasPendingExit && !hasPendingAddition &&
                !hasPendingEntry && requiredSafetyExits.length() == 0 &&
                actualRiskAuditBlocked && verifiedProtectedRiskCapManagement(
                    actualRiskAuditBlocker, exchangePositions, persisted, clock()
                )
            verifiedRiskCapManagementAtMs = if (verifiedOverCap) clock() else 0L
            state = when {
                anyUnprotected -> State.POSITION_UNPROTECTED
                actualRiskAuditBlocked -> State.RECONCILE_REQUIRED
                hasPendingExit -> State.EXIT_PENDING
                hasPendingEntry -> State.ENTRY_PENDING
                openBySymbol.isNotEmpty() -> State.OPEN_PROTECTED
                else -> State.READY
            }
            if (!anyUnprotected) {
                lastBlocker = when {
                    actualRiskAuditBlocked ->
                        actualRiskAuditBlocker ?: "Actual Futures position risk audit is unresolved"
                    state == State.ENTRY_PENDING -> "exchange_confirmed_pending_entry_or_fill_not_yet_resolved"
                    state == State.EXIT_PENDING -> "exit_order_status_pending_exchange_confirmation"
                    else -> null
                }
            }
            JSONObject()
                .put("ok", !anyUnprotected && !actualRiskAuditBlocked && !hasPendingExit)
                .put("exchange_snapshot_complete", true)
                .put("exchange_snapshot_started_at_ms", reconciliationStartedAtMs)
                .put("exchange_snapshot_completed_at_ms", clock())
                .put("external_open_orders",JSONArray(externalOrderObservationsJson))
                .put("exchange_protection_observations",JSONArray(externalProtectionObservationsJson))
                .put("verified_external_stop_management_symbols",
                    JSONArray(externalStopManagementSymbolsJson))
                .put("verified_external_stop_management_at_ms",
                    externalStopManagementVerifiedAtMs)
                .put("actual_risk_audit_blocked", actualRiskAuditBlocked)
                .put("protected_over_cap_management_allowed", verifiedOverCap)
                .put("actual_risk_audit_blocker", actualRiskAuditBlocker ?: JSONObject.NULL)
                .put("required_safety_exits", requiredSafetyExits)
                .put("engine", "USD_M_FUTURES_TESTNET")
                .put("state", state.name)
                .put("positions_checked", exchangePositions.length())
                .put("open_positions", openBySymbol.size)
                .put("pending_intents_checked", persisted.length())
                .put("active_pending_entry", hasPendingEntry)
                .put("active_pending_exit", hasPendingExit)
                .put("blocker", lastBlocker ?: JSONObject.NULL)
        } catch (failure: Exception) {
            noteReconciliationPhase("SIGNED_RECONCILIATION_FAILED")
            state = State.RECONCILE_REQUIRED
            lastBlocker = failure.message ?: failure.javaClass.simpleName
            JSONObject()
                .put("ok", false)
                .put("engine", "USD_M_FUTURES_TESTNET")
                .put("state", state.name)
                .put("blocker", lastBlocker)
        } finally {
            reconciliationFinishedAtMs = clock()
            reconciliationRunning = false
            if (reconciliationPhase != "SIGNED_RECONCILIATION_FAILED")
                noteReconciliationPhase("SIGNED_RECONCILIATION_FINISHED")
        }
    }

    /** Revalidate an observed liquidation breach before a distinct reduce-only exit. */
    fun executeRequiredSafetyExit(request: JSONObject): JSONObject = synchronized(lock) {
        val symbol = request.optString("symbol")
        val entryClientId = request.optString("entry_client_id")
        val rows = intents()
        val owner = latestEntryIntentIndex(rows, symbol)?.let { rows.optJSONObject(it) }
        val direction = runCatching { Direction.valueOf(request.optString("direction")) }.getOrNull()
        if (request.optString("reason") != "LIQUIDATION_GUARD" || direction == null ||
            symbol !in allowedSymbols || owner == null || entryClientId.isBlank() ||
            owner.optString("client_id") != entryClientId ||
            !isManagedOpenPositionIntent(campaignLifecycleStatus(owner), owner.optString("direction"),
                direction.name, owner.optString("protection_status"), owner.optString("protection_client_id"),
                owner.optBoolean("strategy_ownership_verified", false))
        ) return@synchronized JSONObject().put("ok", false).put("safe_rejection", true)
            .put("blocker", "Safety exit requires the same current, verified and protected campaign")

        val positions = adapterFactory().positions()
        val live = (0 until positions.length()).mapNotNull { positions.optJSONObject(it) }
            .filter { it.optString("symbol") == symbol && it.optString("positionAmt").toDoubleOrNull()?.let { q -> q.isFinite() && q != 0.0 } == true }
        if (live.size != 1) return@synchronized JSONObject().put("ok", false)
            .put("blocker", "Safety exit cannot establish one live Futures position")
        val amount = live.single().getString("positionAmt").toBigDecimal()
        if ((amount.signum() > 0) != (direction == Direction.LONG) ||
            request.optString("quantity").toBigDecimalOrNull()?.compareTo(amount.abs()) != 0
        ) return@synchronized JSONObject().put("ok", false).put("safe_rejection", true)
            .put("blocker", "Safety exit quantity/direction changed; fresh reconciliation required")
        val check = assessFuturesLiquidationProtection(direction.name, owner.optString("protective_stop"),
            live.single().opt("liquidationPrice"))
        if (check.status != "UNSAFE") return@synchronized JSONObject().put("ok", false)
            .put("safe_rejection", true).put("blocker", check.blocker ?: "Liquidation stop buffer recovered; exit not submitted")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((symbol + ":" + entryClientId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(24)
        closePositionInternal(symbol, direction, amount.abs().toPlainString(), "W2X_LIQ_$digest",
            "LIQUIDATION_GUARD", partialReduction=false, riskEmergencyVerified=true)
    }

    /**
     * Exchange-proven loss of an earlier CONFIRMED protective STOP_MARKET.
     * This capability cannot originate from a scanner, user-defined exit,
     * failed GET, unowned position, partial fill, or a merely missing local id.
     * It is an emergency risk reduction, NOT a TC2 strategy exit.
     */
    fun executeRequiredUnprotectedSafetyExit(request: JSONObject): JSONObject = synchronized(lock) {
        fun blocked(why: String) = JSONObject().put("ok", false)
            .put("safe_rejection", true).put("mutation_attempted", false)
            .put("blocker", why)
        if (request.optString("reason") != "UNPROTECTED_POSITION_GUARD")
            return@synchronized blocked("Wrong emergency exit reason")
        val symbol = request.optString("symbol")
        val direction = runCatching { Direction.valueOf(request.optString("direction")) }.getOrNull()
            ?: return@synchronized blocked("Unknown emergency exit direction")
        if (symbol !in allowedSymbols) return@synchronized blocked("Symbol not allowed")
        val persisted = intents()
        val owner = latestEntryIntentIndex(persisted, symbol)?.let { persisted.optJSONObject(it) }
            ?: return@synchronized blocked("Missing persisted campaign owner")
        val stopId = request.optString("missing_protection_client_id")
        val noPost = request.optString("guard_origin")=="NEVER_SUBMITTED_PROVEN"
        val ownerBlocker = when {
            request.optString("entry_client_id") != owner.optString("client_id") -> "EMERGENCY_OWNER_ID_MISMATCH"
            noPost && (owner.optString("protection_status")!="NEVER_SUBMITTED_PROVEN" ||
                stopId.isNotBlank() || owner.optString("protection_client_id").isNotBlank()) -> "EMERGENCY_NO_POST_PROOF_MISMATCH"
            !noPost && (stopId.isBlank() || stopId != owner.optString("protection_client_id")) -> "EMERGENCY_STOP_ID_MISMATCH"
            owner.optString("previous_protection_client_id").isNotBlank() -> "EMERGENCY_PREDECESSOR_STOP_AMBIGUOUS"
            !owner.optBoolean("strategy_ownership_verified", false) -> "EMERGENCY_OWNER_UNVERIFIED"
            campaignLifecycleStatus(owner) !in setOf("POSITION_OPEN","OPEN_PROTECTED") -> "EMERGENCY_CAMPAIGN_NOT_LIVE"
            owner.optString("direction") != direction.name -> "EMERGENCY_POSITION_SIDE_CHANGED"
            owner.optString("protection_status") !in
                (if(noPost) setOf("NEVER_SUBMITTED_PROVEN") else setOf("SUBMISSION_UNKNOWN")) ||
                owner.optString("unprotected_safety_guard_origin") !=
                    (if(noPost) "NEVER_SUBMITTED_PROVEN" else "TERMINAL_PREVIOUSLY_CONFIRMED") ->
                    "EMERGENCY_PROTECTION_STATE_CHANGED"
            owner.optLong("unprotected_safety_exit_required_at_ms") <= 0L -> "EMERGENCY_MISSING_DURABLE_SAFETY_PROOF"
            else -> null
        }
        if(ownerBlocker!=null) return@synchronized blocked(ownerBlocker)
        val adapter = adapterFactory()
        return@synchronized try {
            adapter.requireOneWayMode()
            // Require a fresh, explicit terminal Algo response; missing/HTTP
            // failure or FINISHED (possibly already triggered) is NOT enough.
            if (!noPost) {
                val detail = adapter.queryAlgoOrder(symbol, stopId)
                if (!matchesTerminalUntriggeredFuturesProtectiveStop(detail,symbol,direction.name,
                        stopId,owner.optString("protective_stop")))
                    return@synchronized blocked("Exact protective Algo is not proven terminal and untriggered")
            }
            if (adapter.openAlgoOrders(symbol).length() != 0 ||
                adapter.openOrders(symbol).length() != 0)
                return@synchronized blocked("Exchange has other live orders; reconcile before emergency exit")
            val positions = adapter.positions()
            val live = (0 until positions.length()).mapNotNull { positions.optJSONObject(it) }
                .filter { it.optString("symbol") == symbol &&
                    it.optString("positionAmt").toBigDecimalOrNull()?.signum() != 0 }
            if (live.size != 1 || live.single().optString("positionSide", "BOTH") != "BOTH")
                return@synchronized blocked("Exactly one One-Way position is required")
            val amount = live.single().optString("positionAmt").toBigDecimalOrNull()
                ?: return@synchronized blocked("Malformed live Futures position")
            val qty = amount.abs()
            if ((amount.signum() > 0) != (direction == Direction.LONG) ||
                qty.compareTo(owner.optString("confirmed_position_qty").toBigDecimalOrNull()
                    ?: return@synchronized blocked("Unknown campaign quantity")) != 0 ||
                qty.compareTo(request.optString("quantity").toBigDecimalOrNull()
                    ?: return@synchronized blocked("Unknown requested quantity")) != 0)
                return@synchronized blocked("Foreign quantity/side change after original reconciliation")
            val entry = live.single().optString("entryPrice").toDoubleOrNull()
                ?: return@synchronized blocked("Unknown live entry price")
            val recorded = owner.optString("actual_entry_price").toDoubleOrNull()
                ?: return@synchronized blocked("Unknown attributed entry price")
            if (!entry.isFinite() || !recorded.isFinite() || entry <= 0 ||
                kotlin.math.abs(entry-recorded)>maxOf(1e-8, recorded*1e-8))
                return@synchronized blocked("Exchange entry price no longer matches campaign")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((symbol + ":" + owner.optString("client_id") + ":" +
                    (if(noPost) "NEVER_SUBMITTED_PROVEN" else stopId))
                    .toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(20)
            closePositionInternal(symbol, direction, qty.toPlainString(),
                "W2X_NOSTOP_$digest", "UNPROTECTED_POSITION_GUARD",
                partialReduction=false, riskEmergencyVerified=true)
        } catch (e: Exception) {
            blocked("Emergency revalidation unavailable: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Definite pre-order rejection. No exchange mutation was attempted. */
    private fun safePreflightRejection(blocker: String): JSONObject =
        JSONObject()
            .put("accepted", false)
            .put("safe_rejection", true)
            .put("mutation_attempted", false)
            .put("state", state.name)
            .put("blocker", blocker)

    private fun tc2AdditionBookBlocker(plan:EntryPlan,owner:JSONObject,ledger:JSONArray,
        live:JSONObject,mark:Double,contractPrice:Double):String? {
        val amount=live.optString("positionAmt").toBigDecimalOrNull()
            ?: return "TC2_ADDITION_POSITION_UNPROVEN"
        if(live.optString("positionSide","BOTH")!="BOTH" || amount.signum()==0 ||
            (amount.signum()>0)!=(plan.direction==Direction.LONG)) return "TC2_ADDITION_DIRECTION_UNPROVEN"
        val entry=live.optString("entryPrice").toDoubleOrNull() ?: Double.NaN
        val recorded=owner.optString("actual_entry_price").toDoubleOrNull() ?: Double.NaN
        if(!recorded.isFinite() || !entry.isFinite() || recorded<=0 || entry<=0 ||
            kotlin.math.abs(entry-recorded)>maxOf(1e-8,recorded*1e-8)) return "TC2_ADDITION_ENTRY_OWNERSHIP_CHANGED"
        williamsTc2ProfitBlocker(plan.direction.name,entry,mark,plan.entryPrice.toDouble())?.let { return it }
        if(!contractPrice.isFinite() || contractPrice<=0) return "TC2_ADDITION_CONTRACT_PRICE_UNVERIFIED"
        if(plan.strategyProfile==williamsNtdProfile)
            williamsTc2ProfitBlocker(plan.direction.name,entry,contractPrice,plan.entryPrice.toDouble())?.let { return it }
        if(plan.kind!=EntryKind.MARKET &&
            (if(plan.direction==Direction.LONG) contractPrice>=plan.entryPrice.toDouble() else contractPrice<=plan.entryPrice.toDouble()))
            return "TC2_ADDITION_TRIGGER_CONSUMED_BEFORE_SUBMISSION"
        // Existing campaigns keep their persisted allocation semantics. Only
        // newly pinned campaigns may claim the book's exact quantity sequence.
        if(plan.strategyProfile==williamsTc2Profile && williamsTc2PyramidPolicy(owner)!=null) {
            val next=williamsTc2NextTranche(owner,ledger,plan.clientId)
            next.blocker?.let { return it }
            return williamsTc2TrancheQuantityBlocker(next.quantity!!,plan.quantity)
        }
        return null
    }

    private fun ntdCloseEntryDoor(plan:EntryPlan,adapter:BinanceUsdMFuturesTestnetAdapter):String? {
        val filters=adapter.exchangeInfo().getJSONArray("symbols")
        val symbol=(0 until filters.length()).map { filters.getJSONObject(it) }
            .singleOrNull { it.optString("symbol")==plan.symbol } ?: return "NTD_CLOSE_SYMBOL_UNVERIFIED"
        val rules=symbol.getJSONArray("filters")
        val tick=(0 until rules.length()).map { rules.getJSONObject(it) }
            .singleOrNull { it.optString("filterType")=="PRICE_FILTER" }?.optString("tickSize")?.toDoubleOrNull()
            ?: return "NTD_CLOSE_TICK_UNVERIFIED"
        val quote=adapter.bestBidAsk(plan.symbol)
        if(plan.entryClientId.isNotBlank()) {
            val rows=adapter.positions()
            val owner=(0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.singleOrNull {
                it.optString("symbol")==plan.symbol && it.optString("positionAmt").toDoubleOrNull()?.let { q -> q!=0.0 }==true
            } ?: return "NTD_CLOSE_POSITION_UNVERIFIED"
            williamsTc2ProfitBlocker(plan.direction.name,owner.optString("entryPrice").toDoubleOrNull() ?: Double.NaN,
                if(plan.direction==Direction.LONG) quote.second else quote.first,plan.entryPrice.toDouble())?.let { return it }
        }
        return williamsNtdCloseEntryBlocker(plan.direction.name,plan.signalBarTimeMs,plan.referenceClose,
            plan.entryPrice.toDouble(),clock(),adapter.contractPrice(plan.symbol),adapter.markPrice(plan.symbol),
            quote.first,quote.second,tick)
    }

    /** Persist the entry request before any account/order mutation. */
    fun submitPendingEntry(plan: EntryPlan): JSONObject {
        val attempt=java.util.UUID.randomUUID().toString()
        val preparedId=appendEntryPassport(plan,attempt,"PREPARED")
        if(preparedId==0L) {
            val rejection=safePreflightRejection("ENTRY_PASSPORT_AUDIT_UNAVAILABLE: order not attempted")
            runCatching { recordEntryDecision(plan,rejection,attempt,0L) }
            return rejection
        }
        val result=try { submitPendingEntryLocked(plan) } catch(failure:Exception) {
            // A lost reply cannot prove that Binance rejected or never saw the request.
            val unknown=JSONObject().put("accepted",false).put("outcome_unknown",true)
                .put("mutation_attempted",JSONObject.NULL)
                .put("blocker",safeDiagnosticText(failure.message?:failure.javaClass.simpleName))
            val eventId=appendEntryPassport(plan,attempt,"EXECUTION_EXCEPTION_UNVERIFIED",unknown)
            runCatching { recordEntryDecision(plan,unknown,attempt,maxOf(0L,eventId,preparedId),
                "EXECUTION_EXCEPTION_UNVERIFIED") }
            throw failure
        }
        // MARKET entries/additions fill immediately. Reconcile the SAME persisted id
        // and install/verify protection inside this execution-door pass,
        // including a lost POST reply, rather than waiting for another scan.
        if(plan.kind==EntryKind.MARKET && !result.optBoolean("safe_rejection",false)) {
            val posted=intentsSnapshot().let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .singleOrNull { it.optString("client_id")==plan.clientId &&
                    it.optBoolean("entry_submission_attempted",true) } }
            if(posted!=null) {
                val checked=runCatching { reconcile() }.getOrElse { failure ->
                    JSONObject().put("ok",false).put("blocker","MARKET_ENTRY_PROTECTION_RECONCILIATION_UNAVAILABLE: "+
                        safeDiagnosticText(failure.message ?: failure.javaClass.simpleName))
                }
                val confirmed=intentsSnapshot().let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .singleOrNull { it.optString("client_id")==plan.clientId } }
                val protectedOwner=if(plan.entryClientId.isBlank()) confirmed else intentsSnapshot().let { rows ->
                    (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.singleOrNull {
                        it.optString("client_id")==plan.entryClientId && it.optString("intent_role","ENTRY")=="ENTRY" } }
                val confirmedFill=confirmed?.optString(if(plan.entryClientId.isBlank())
                    "actual_entry_executed_qty" else "executed_qty")?.toBigDecimalOrNull()
                val protectionConfirmed=confirmedFill?.signum()==1 &&
                    protectedOwner!=null && campaignLifecycleStatus(protectedOwner)=="OPEN_PROTECTED" &&
                    protectedOwner.optString("protection_status")=="PROTECTED"
                result.put("protection_reconciliation",checked).put("state",state.name)
                    .put("protection",if(protectionConfirmed) "PROTECTED" else "RECONCILIATION_REQUIRED")
                if(protectionConfirmed) {
                    // The original transport failure remains in the durable
                    // intent/passport; it is no longer a CURRENT blocker.
                    result.remove("blocker");result.remove("error")
                    result.put("accepted",true).put("entry_confirmed_after_reconciliation",true)
                }
            }
        }
        val eventId=appendEntryPassport(plan,attempt,"COORDINATOR_RETURNED",result)
        if(eventId==0L) result.put("passport_audit_status","OUTCOME_WRITE_FAILED_RECONCILE_REQUIRED")
        // Diagnostic persistence failure after an accepted order NEVER retries that order.
        runCatching { recordEntryDecision(plan,result,attempt,maxOf(0L,eventId,preparedId)) }
        return result
    }
    private fun recordEntryDecision(
        plan:EntryPlan,result:JSONObject,attempt:String,auditEventId:Long,
        phase:String="COORDINATOR_RETURNED"
    ) {
        val trace=JSONObject().put("schema","WILLIAMS_ENTRY_DECISION_V2")
            .put("stage","EXECUTION_COORDINATOR").put("observed_at_ms",clock())
            .put("symbol",plan.symbol).put("attempt_id",attempt).put("audit_event_id",auditEventId).put("phase",phase)
            .put("signal_bar_time_ms",plan.signalBarTimeMs).put("client_id",plan.clientId).put("direction",plan.direction.name)
            .put("entry_client_id",plan.entryClientId).put("strategy_profile",plan.strategyProfile)
            .put("signal_id",plan.signalId).put("signal_reason",safeDiagnosticText(plan.signalReason))
            .put("setup_context",runCatching { JSONObject(plan.setupContext) }.getOrDefault(JSONObject()))
            .put("entry_time_policy",entryTimePolicyProvider().toJson()).put("signal_type",plan.signalType)
            .put("quantity",plan.quantity).put("entry_price",plan.entryPrice).put("protective_stop",plan.protectiveStop)
            .put("risk_pct",plan.riskPct).put("leverage",plan.leverage).put("outcome",result)
        executionStore.writeState("decision_execution_"+plan.symbol,trace.toString())
        WilliamsSignalJournal(executionStore).decision(plan.symbol,"EXECUTION_COORDINATOR",
            result.optString("blocker").ifBlank { result.optString("state","EXCHANGE_RESULT_RECORDED") }+" "+result.optString("error"),
            clock(),plan.signalId,result.optBoolean("accepted",false))
    }
    private fun submitPendingEntryLocked(plan: EntryPlan): JSONObject = synchronized(lock) {
        val currentIntents = intents()
        val isReversal=plan.reversalOwnerId.isNotBlank()
        if(isReversal && (plan.kind!=EntryKind.MARKET || plan.strategyProfile!=williamsTc2Profile ||
            plan.entryClientId.isNotBlank() || plan.reentryOwnerId.isNotBlank() ||
            plan.executionSemantics!=WILLIAMS_TC2_REVERSAL))
            return@synchronized safePreflightRejection("TC2_REVERSAL_PLAN_INVALID")
        if ((0 until currentIntents.length()).any { currentIntents.getJSONObject(it).has("native_stop_replacement") })
            return@synchronized safePreflightRejection("NATIVE_STOP_REPLACEMENT_PENDING: reconcile all STOP identities before new exposure")
        if(hasUnresolvedTriggeringEntry(currentIntents))
            return@synchronized safePreflightRejection("CONDITIONAL_TRIGGER_IN_PROGRESS: reconcile child order and position before new exposure")
        if(requireM15FractalForAutomatedEntries && !isReversal && plan.entryClientId.isBlank() &&
            plan.signalType!="OPERATOR_FORCED_DIRECTION") {
            val proof=williamsMtfProofBlocker(plan.setupContext,plan.direction.name,plan.signalId,
                plan.entryPrice.toDoubleOrNull() ?: Double.NaN,plan.protectiveStop.toDoubleOrNull() ?: Double.NaN,clock())
            if(proof!=null) return@synchronized safePreflightRejection(proof)
            if(williamsH1ParentAlreadyFilled(currentIntents,plan.signalId,plan.signalType,plan.signalBarTimeMs))
                return@synchronized safePreflightRejection("H1_PARENT_ALREADY_FILLED: no second initial campaign")
        }
        if(requireBookH1ProofForAutomatedEntries && !isReversal &&
            plan.signalType!="OPERATOR_FORCED_DIRECTION") {
            val bookProof=williamsBookH1ProofBlocker(plan.setupContext,plan.direction.name,
                plan.signalId,plan.signalType,plan.strategyProfile,
                plan.entryPrice.toDoubleOrNull() ?: Double.NaN,
                plan.protectiveStop.toDoubleOrNull() ?: Double.NaN,clock(),plan.entryClientId,
                plan.reentryOwnerId,plan.executionSemantics,plan.referenceClose)
            if(bookProof!=null) return@synchronized safePreflightRejection(bookProof)
            if(plan.entryClientId.isBlank() && williamsH1ParentAlreadyFilled(currentIntents,plan.signalId,plan.signalType,plan.signalBarTimeMs))
                return@synchronized safePreflightRejection("H1_PARENT_ALREADY_FILLED")
        }
        if(!entryTimePolicyProvider().allows(clock()))
            return@synchronized safePreflightRejection("ENTRY_TIME_POLICY_BLOCKED: "+entryTimePolicyProvider().toJson())
        val selected=editionSelectionProvider?.invoke()
        if(selected!=null && plan.signalType!="OPERATOR_FORCED_DIRECTION" &&
            !williamsProfileAllowsSource(selected,plan.strategyProfile))
            return@synchronized safePreflightRejection("Williams edition changed while entry waited for coordinator admission")
        val isAddition=plan.entryClientId.isNotBlank()
        val prior=latestEntryIntentIndex(currentIntents,plan.symbol)?.let { currentIntents.optJSONObject(it) }
        val reentry=plan.reentryOwnerId.isNotBlank() && plan.reentryOwnerId==prior?.optString("client_id") &&
            williamsNtdReentryEligible(prior,plan.symbol,plan.direction.name,plan.signalBarTimeMs)
        if(plan.reentryOwnerId.isNotBlank() && (!reentry || isAddition))
            return@synchronized safePreflightRejection("NTD reentry requires the latest profitable, unconsumed authenticated cycle")
        val primary=(0 until currentIntents.length()).mapNotNull { currentIntents.optJSONObject(it) }
            .singleOrNull { it.optString("intent_role","ENTRY")=="ENTRY" && it.optString("client_id")==plan.entryClientId }
        if ((0 until currentIntents.length()).any { currentIntents.optJSONObject(it)?.optString("client_id")==plan.clientId })
            return@synchronized safePreflightRejection("Formation client id already admitted; no duplicate submission")
        if (isAddition && (primary==null || campaignLifecycleStatus(primary)!="OPEN_PROTECTED" ||
            primary.optString("symbol")!=plan.symbol || primary.optString("direction")!=plan.direction.name ||
            primary.optString("strategy_profile",williamsTc2Profile)!=plan.strategyProfile ||
            plan.leverage!=primary.optInt("requested_leverage") ||
            plan.protectiveStop.toBigDecimalOrNull()!=primary.optString("protective_stop").toBigDecimalOrNull() ||
            !williamsAdditionTimeEligible(plan.strategyProfile,plan.signalBarTimeMs,plan.signalType,
                primary.optLong("signal_bar_time_ms",0L),primary.optString("signal_type"),primary.optLong("signal_bar_time_ms",0L)) ||
            (0 until currentIntents.length()).mapNotNull { currentIntents.optJSONObject(it) }.any {
                it.optString("intent_role")=="ADD_ON" && it.optString("entry_client_id")==plan.entryClientId &&
                    exchangeOrderStatus(it) !in ENTRY_ORDER_TERMINAL_STATUSES }))
            return@synchronized safePreflightRejection("Addition requires the same protected campaign, stop, leverage and a new formation")
        if(isAddition && plan.strategyProfile==williamsNtdProfile &&
            primary!!.optString("ntd_cycle_basis") !in setOf("FIRST_EXTERNAL_FRACTAL","AUTHENTICATED_PROFITABLE_REENTRY"))
            return@synchronized safePreflightRejection("NTD_OWNED_FRACTAL_OR_PROFITABLE_CYCLE_NOT_PROVEN")
        val coordinatorCanAdmit = state == State.READY || state == State.OPEN_PROTECTED ||
            (state == State.ENTRY_PENDING &&
                confirmedPendingEntriesAllowConcurrentAdmission(currentIntents))
        if (!coordinatorCanAdmit) {
            return@synchronized safePreflightRejection(
                "Futures execution blocked before order mutation: ${lastBlocker ?: state.name}"
            )
        }
        // The trader's configured leverage is preserved (1..125).
        // parseFuturesLeverage validates its range below. A NEW entry is
        // nevertheless forbidden until authenticated exchange leverage tiers,
        // structural stop + costs and isolated liquidation margin all pass
        // futuresLeverageBlocker() immediately before exchange mutation.
        // Existing positions and their protective/reduce-only exits are never
        // blocked by a UI leverage preference.
        try {
            validatePlan(plan)
            parseFuturesLeverage(plan.leverage)
        } catch (failure: Exception) {
            return@synchronized safePreflightRejection(
                "Invalid Futures entry plan rejected before order mutation: " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        }
        val preflightAdapter = adapterFactory()
        if(isReversal) {
            val blocker=runCatching { checkBookReversalDoor(plan,currentIntents,preflightAdapter,false) }
                .getOrElse { "TC2_REVERSAL_PREFLIGHT_UNAVAILABLE: "+safeDiagnosticText(it.message ?: it.javaClass.simpleName) }
            if(blocker!=null) return@synchronized safePreflightRejection(blocker)
        }
        val preflightAccount = runCatching {
            parseFuturesAccountSnapshot(preflightAdapter.account())
        }.getOrNull() ?: return@synchronized safePreflightRejection(
            "Fresh authenticated USD-M Futures account snapshot is unavailable; risk cannot be established"
        )
        if (!preflightAccount.equityUsdt.isFinite() || preflightAccount.equityUsdt <= 0.0 ||
            !preflightAccount.availableUsdt.isFinite() || preflightAccount.availableUsdt <= 0.0
        ) {
            return@synchronized safePreflightRejection(
                "Fresh Futures equity and available USDT must both be positive before entry"
            )
        }
        val exchangeInfo = preflightAdapter.exchangeInfo()
        val contractBlocker = futuresContractRuleBlocker(
            exchangeInfo,
            plan.symbol,
            plan.quantity,
            plan.entryPrice,
            plan.protectiveStop,
            marketOrder = plan.kind != EntryKind.LIMIT
        )
        if (contractBlocker != null) {
            return@synchronized safePreflightRejection(contractBlocker)
        }

        // Defense in depth: the coordinator re-calculates the actual plan risk using
        // quantity/trigger/structural stop and a fresh exchange equity snapshot. The
        // estimate includes the same 0.10% fee allowance per side and 0.15% slippage
        // buffer used by the scanner, plus a 5% available-margin reserve.
        val riskCheck = assessFuturesEntryRisk(
            direction = plan.direction.name,
            quantity = plan.quantity,
            entryPrice = plan.entryPrice,
            protectiveStop = plan.protectiveStop,
            equityUsdt = preflightAccount.equityUsdt,
            availableUsdt = preflightAccount.availableUsdt,
            riskBudgetPct = plan.riskPct,
            leverage = plan.leverage.toDouble()
        )
        if (riskCheck.blocker != null) {
            return@synchronized safePreflightRejection(riskCheck.blocker)
        }
        val leverageBlocker = try {
            futuresLeverageBlocker(
                preflightAdapter.leverageBrackets(plan.symbol), plan.symbol, plan.leverage,
                riskCheck.notionalUsdt+(if(isAddition) primary!!.getString("confirmed_position_qty").toDouble()*plan.entryPrice.toDouble() else 0.0), plan.entryPrice.toDouble(), plan.protectiveStop.toDouble()
            )
        } catch (failure: Exception) {
            "Futures leverage limits unavailable: " + (failure.message ?: failure.javaClass.simpleName)
        }
        if (leverageBlocker != null) return@synchronized safePreflightRejection(leverageBlocker)

        // Distinct approved symbols may hold concurrent campaigns, but each live
        // position must be owned by a durable campaign with exchange-confirmed stop.
        // Unknown/manual exposure remains fail-closed.
        val livePositions = preflightAdapter.positions()
        val openPositionRows = linkedMapOf<String, JSONObject>()
        for (i in 0 until livePositions.length()) {
            val row = livePositions.optJSONObject(i)
                ?: throw IllegalStateException("Binance returned a malformed position row")
            val symbol = row.optString("symbol").uppercase(Locale.US)
            val amount = row.optString("positionAmt").toBigDecimalOrNull()
                ?: throw IllegalStateException("Binance returned a malformed position amount for $symbol")
            if (amount.signum() == 0) continue
            check(symbol in allowedSymbols) {
                "Unmanaged live Futures position $symbol is outside the approved universe; no new entry allowed"
            }
            val ownerIndex = latestEntryIntentIndex(currentIntents, symbol)
                ?: throw IllegalStateException(
                    "Live Futures position $symbol has no persisted campaign/structural-stop ownership"
                )
            val owner = currentIntents.optJSONObject(ownerIndex)
                ?: throw IllegalStateException("Persisted campaign owner for $symbol is malformed")
            val direction = if (amount.signum() > 0) Direction.LONG.name else Direction.SHORT.name
            check(isManagedOpenPositionIntent(
                status = campaignLifecycleStatus(owner),
                direction = owner.optString("direction"),
                expectedDirection = direction,
                protectionStatus = owner.optString("protection_status"),
                protectionClientId = owner.optString("protection_client_id"),
                strategyOwnershipVerified = owner.optBoolean("strategy_ownership_verified", false)
            )) {
                "Live Futures position $symbol is not confirmed OPEN_PROTECTED; structural-stop reconciliation required"
            }
            // Local PROTECTED metadata alone is not sufficient for admission:
            // confirm the exact closePosition stop still exists on Binance and
            // remains on the protective side of the current mark before adding risk.
            check(!owner.has("native_stop_replacement")) { "Native STOP replacement blocks new risk" }
            val ownerStop = owner.optString("protective_stop")
            val ownerStopId = owner.optString("protection_client_id")
            val currentMark = preflightAdapter.markPrice(symbol)
            check(isProtectiveStopOnCorrectSide(direction, currentMark.toString(), ownerStop)) {
                "Live Futures position $symbol stop is not on the protective side of the current mark"
            }
            check(findProtectiveStop(
                preflightAdapter.openAlgoOrders(symbol), direction, ownerStopId, ownerStop,
                expectedSymbol = symbol
            ) != null) {
                "Live Futures position $symbol protection is not exchange-confirmed at the persisted client id/trigger"
            }
            openPositionRows[symbol] = row
        }

        val slotBlocker = futuresPositionSlotBlocker(
            symbol = plan.symbol,
            openPositionSymbols = openPositionRows.keys,
            intents = currentIntents,
            maxPositions = allowedSymbols.size
        )
        if (!isAddition && slotBlocker != null) return@synchronized safePreflightRejection(slotBlocker)
        if (isAddition) {
            val live=openPositionRows[plan.symbol] ?: return@synchronized safePreflightRejection("Addition requires a live owned position")
            if (live.optString("positionAmt").toBigDecimal().abs().compareTo(primary!!.getString("confirmed_position_qty").toBigDecimal())!=0)
                return@synchronized safePreflightRejection("Position changed since ownership reconciliation")
            if(plan.strategyProfile in williamsExecutionProfiles) {
                val blocker=tc2AdditionBookBlocker(plan,primary,currentIntents,live,
                    preflightAdapter.markPrice(plan.symbol),preflightAdapter.contractPrice(plan.symbol))
                if(blocker!=null) return@synchronized safePreflightRejection(blocker)
            }
        }

        if(isAddition) {
            val campaignBlocker=futuresPortfolioRiskBlocker(plan.riskPct,
                futuresCampaignRiskComponents(primary!!,currentIntents),maxTotalRiskPct=.006)
            if(campaignBlocker!=null) return@synchronized safePreflightRejection("CAMPAIGN_RISK_LIMIT: "+campaignBlocker)
        }
        // Sum active intent risk conservatively. Missing/invalid reservations cannot
        // silently free aggregate portfolio capacity.
        val reservedRiskPcts=(0 until currentIntents.length()).flatMap { i ->
            val row=currentIntents.optJSONObject(i) ?: error("Malformed Futures risk intent")
            if(row.optString("intent_role","ENTRY")=="ENTRY" && isEntryCampaignReserved(row)) futuresCampaignRiskComponents(row,currentIntents) else emptyList()
        }
        val riskBlocker = futuresPortfolioRiskBlocker(plan.riskPct, reservedRiskPcts)
        if (riskBlocker != null) return@synchronized safePreflightRejection(riskBlocker)

        if (plan.kind==EntryKind.MARKET && !isReversal) {
            val reason=ntdCloseEntryDoor(plan,preflightAdapter)
            if (reason!=null) return@synchronized safePreflightRejection(reason)
        }
        val intent = JSONObject()
            .put("intent_role", if (isAddition) "ADD_ON" else "ENTRY")
            .put("entry_client_id",plan.entryClientId).put("reentry_owner_id",plan.reentryOwnerId)
            .put("reversal_owner_id",plan.reversalOwnerId)
            .put("strategy_profile",plan.strategyProfile)
            .put("execution_semantics",plan.executionSemantics)
            .put("reference_close",plan.referenceClose)
            .put("setup_context",plan.setupContext)
            .put("strategy_ownership_verified", true)
            .put("symbol", plan.symbol)
            .put("direction", plan.direction.name)
            .put("kind", plan.kind.name)
            .put("entry_trigger_basis",if(plan.kind in setOf(EntryKind.STOP,EntryKind.TAKE_PROFIT))
                "CONTRACT_PRICE" else "ORDER_BOOK_EXECUTION")
            .put("quantity", plan.quantity)
            .put("entry_price", plan.entryPrice)
            .put("protective_stop", plan.protectiveStop)
            .put("initial_protective_stop", plan.protectiveStop)
            .put("risk_pct", plan.riskPct)
            .put("requested_leverage", plan.leverage)
            .put("signal_type", plan.signalType).put("signal_id",plan.signalId)
            .put("entry_time_policy",entryTimePolicyProvider().toJson())
            .put("signal_bar_time_ms", plan.signalBarTimeMs)
            .put("signal_expires_at_ms", if (plan.signalBarTimeMs>0) williamsH1SignalExpiry(plan.signalBarTimeMs,plan.signalType,plan.strategyProfile) else 0L)
            .put("signal_reason", safeDiagnosticText(plan.signalReason))
            .put("trade_mode", plan.tradeMode)
            .put("wave_context_at_entry", plan.waveContext)
            .put("risk_budget_usdt", preflightAccount.equityUsdt * plan.riskPct)
            .put("risk_equity_snapshot_usdt", preflightAccount.equityUsdt)
            .put("risk_available_snapshot_usdt", preflightAccount.availableUsdt)
            .put("risk_gross_stop_loss_usdt", riskCheck.grossStopLossUsdt)
            .put("risk_fee_slippage_allowance_usdt", riskCheck.estimatedFeesAndSlippageUsdt)
            .put("risk_estimated_all_in_loss_usdt", riskCheck.estimatedAllInLossUsdt)
            .put("risk_estimated_all_in_pct", riskCheck.estimatedAllInRiskPct)
            .put("risk_initial_margin_estimate_usdt", riskCheck.initialMarginUsdt)
            .put("risk_assessed_at_ms", clock())
            .put("client_id", plan.clientId)
            .put("exchange_status", "SUBMISSION_UNKNOWN")
            .put("order_status", "SUBMISSION_UNKNOWN")
            .put("campaign_status", "ENTRY_PENDING")
            .put("protection_status", "NOT_SUBMITTED")
            .put("created_at_ms", clock())
        if(!isAddition && plan.strategyProfile==williamsTc2Profile) {
            val policy=runCatching { JSONObject(plan.setupContext.ifBlank { "{}" }) }.getOrNull()
                ?.optJSONObject("tc2_reverse_pyramid")?.optString("policy") ?: "LEGACY_UNPINNED"
            intent.put("tc2_quantity_policy",policy)
        }
        if(!isAddition && plan.strategyProfile==williamsNtdProfile)
            intent.put("ntd_cycle_basis",if(reentry) "AUTHENTICATED_PROFITABLE_REENTRY" else "FIRST_EXTERNAL_FRACTAL")
        if(reentry) prior!!.put("ntd_reentry_consumed_by",plan.clientId)
        if(isAddition && plan.strategyProfile==williamsCourseProfile) {
            val setup=runCatching { JSONObject(plan.setupContext) }.getOrDefault(JSONObject())
            if(setup.has("course_target_stage") && setup.has("course_target_quantity")) {
                val management=primary!!.optJSONObject("edition_management") ?: JSONObject()
                management.put("course_pending_stage",setup.getString("course_target_stage"))
                    .put("course_pending_target",setup.getDouble("course_target_quantity"))
                    .put("course_pending_client_id",plan.clientId)
                    .put("course_pending_quantity",setup.optJSONObject("course_exact_quantity"))
                primary.put("edition_management",management)
            }
        }
        currentIntents.put(intent)
        saveIntents(currentIntents)
        state = State.ENTRY_PENDING
        lastBlocker = "pending_entry_submission_in_progress"

        val adapter = adapterFactory()
        var orderSubmissionAttempted = false
        var finalContextCheckStarted = false
        try {
            adapter.serverTime()
            adapter.requireOneWayMode()
            val leverageConfirmation = if (!isAddition) adapter.ensureIsolatedLeverage(plan.symbol, plan.leverage)
                else adapter.requireIsolatedLeverage(plan.symbol,plan.leverage)
            val confirmedNotionalCap = leverageConfirmation.getString("maxNotionalValue").toDouble()
            val plannedNotional=riskCheck.notionalUsdt+(if(isAddition) primary!!.getString("confirmed_position_qty").toDouble()*plan.entryPrice.toDouble() else 0.0)
            val worstNotional = plannedNotional * maxOf(1.0, plan.protectiveStop.toDouble() / plan.entryPrice.toDouble())
            check(confirmedNotionalCap.isFinite() && worstNotional < confirmedNotionalCap) {
                "FUTURES_LEVERAGE_NOTIONAL_CAP: confirmed exchange limit is below planned exposure"
            }
            intent.put("confirmed_leverage", leverageConfirmation.getInt("leverage"))
                .put("leverage_confirmed_at_ms", clock())
            saveIntents(currentIntents)
            finalContextCheckStarted = true
            check(entryTimePolicyProvider().allows(clock())) { "ENTRY_TIME_POLICY_BLOCKED_BEFORE_SUBMISSION" }
            if(requireM15FractalForAutomatedEntries && !isReversal && !isAddition && plan.signalType!="OPERATOR_FORCED_DIRECTION") {
                check(williamsMtfProofBlocker(plan.setupContext,plan.direction.name,plan.signalId,
                    plan.entryPrice.toDouble(),plan.protectiveStop.toDouble(),clock())==null) {
                    "M15_FRACTAL_PROOF_EXPIRED_BEFORE_SUBMISSION"
                }
                val verifier=m15EntryVerifier ?: error("M15_FINAL_VERIFIER_REQUIRED")
                verifier(plan)
            }
            if((requireBookH1ProofForAutomatedEntries || isReversal) && plan.signalType!="OPERATOR_FORCED_DIRECTION") {
                if(!isReversal) check(williamsBookH1ProofBlocker(plan.setupContext,plan.direction.name,plan.signalId,
                    plan.signalType,plan.strategyProfile,plan.entryPrice.toDouble(),
                    plan.protectiveStop.toDouble(),clock(),plan.entryClientId,plan.reentryOwnerId,
                    plan.executionSemantics,plan.referenceClose)==null) {
                    "H1_BOOK_PROOF_EXPIRED_BEFORE_SUBMISSION"
                }
                val verifier=bookH1EntryVerifier ?: error("H1_BOOK_FINAL_VERIFIER_REQUIRED")
                verifier(plan)
            }
            if(isReversal) checkBookReversalDoor(plan,currentIntents,adapter,true)?.let { error(it) }
            if(isAddition && plan.strategyProfile in williamsExecutionProfiles) {
                val positions=adapter.positions()
                val live=(0 until positions.length()).mapNotNull { positions.optJSONObject(it) }
                    .singleOrNull { it.optString("symbol")==plan.symbol &&
                        it.optString("positionAmt").toBigDecimalOrNull()?.signum()!=0 }
                    ?: error("TC2_PYRAMID_LIVE_POSITION_UNPROVEN")
                check(live.optString("positionAmt").toBigDecimal().abs()
                    .compareTo(primary!!.getString("confirmed_position_qty").toBigDecimal())==0) {
                    "TC2_PYRAMID_OWNED_QUANTITY_CHANGED_BEFORE_SUBMISSION"
                }
                tc2AdditionBookBlocker(plan,primary,currentIntents,live,
                    adapter.markPrice(plan.symbol),adapter.contractPrice(plan.symbol))?.let { error(it) }
            }
            if(plan.kind==EntryKind.MARKET && !isReversal)
                ntdCloseEntryDoor(plan,adapter)?.let { error(it) }
            orderSubmissionAttempted = true
            val result = when (plan.kind) {
                EntryKind.MARKET -> {
                    adapter.newOrder(plan.symbol,if (plan.direction==Direction.LONG) "BUY" else "SELL",plan.quantity,false,plan.clientId)
                }
                EntryKind.LIMIT -> adapter.placeLimitOrder(
                    symbol = plan.symbol,
                    side = if (plan.direction == Direction.LONG) "BUY" else "SELL",
                    quantity = plan.quantity,
                    limitPrice = plan.entryPrice,
                    reduceOnly = false,
                    postOnly = false,
                    clientOrderId = plan.clientId
                )
                EntryKind.STOP, EntryKind.TAKE_PROFIT -> adapter.placeConditionalEntry(
                    symbol = plan.symbol,
                    direction = plan.direction.name,
                    triggerType = plan.kind.name,
                    quantity = plan.quantity,
                    triggerPrice = plan.entryPrice,
                    clientOrderId = plan.clientId
                )
            }
            val orderStatus = result.optString("status").ifBlank {
                result.optString("algoStatus", "NEW")
            }.uppercase(Locale.US)
            intent.put("exchange_status", orderStatus)
                .put("order_status", orderStatus)
                .put("campaign_status", "ENTRY_PENDING")
                .put("exchange_order_id", result.optString("orderId", result.optString("algoId", "")))
                .put("submitted_at_ms", clock())
                .put("executed_qty",result.optString("executedQty","0"))
            saveIntents(currentIntents)
            lastBlocker = "entry pending exchange fill and confirmed protective stop"
            JSONObject()
                .put("accepted", true)
                .put("engine", "USD_M_FUTURES_TESTNET")
                .put("state", state.name)
                .put("direction", plan.direction.name)
                .put("entry_kind", plan.kind.name)
                .put("leverage", plan.leverage)
                .put("order", result)
                .put("protection", "PENDING_FILL")
                .put("risk_estimate", JSONObject()
                    .put("budget_usdt", preflightAccount.equityUsdt * plan.riskPct)
                    .put("gross_stop_loss_usdt", riskCheck.grossStopLossUsdt)
                    .put("fees_slippage_allowance_usdt", riskCheck.estimatedFeesAndSlippageUsdt)
                    .put("all_in_loss_usdt", riskCheck.estimatedAllInLossUsdt)
                    .put("all_in_risk_pct", riskCheck.estimatedAllInRiskPct)
                    .put("initial_margin_usdt", riskCheck.initialMarginUsdt))
        } catch (failure: Exception) {
            if (!orderSubmissionAttempted || failure is FuturesExposureNotSubmittedException) {
                // Account configuration can fail or time out, but no entry was
                // sent. Do not reserve a phantom SUBMISSION_UNKNOWN order.
                intent.put("exchange_status", "REJECTED")
                    .put("order_status", "REJECTED")
                    .put("entry_submission_attempted",false).put("executed_qty","0")
                    .put("campaign_status", "CLOSED_OR_STOPPED")
                    .put("error", failure.message ?: failure.javaClass.simpleName)
                saveIntents(currentIntents)
                state = State.RECONCILE_REQUIRED
                lastBlocker = if(finalContextCheckStarted) "FUTURES_ENTRY_CONTEXT_REJECTED: "+safeDiagnosticText(intent.optString("error"))
                    else "futures_account_configuration_failed_before_order"
                return@synchronized JSONObject().put("accepted", false)
                    .put("safe_rejection", true).put("mutation_attempted", true)
                    .put("order_mutation_attempted", false).put("state", state.name)
                    .put("blocker", lastBlocker).put("error", intent.optString("error"))
            }
            intent.put("exchange_status", "SUBMISSION_UNKNOWN")
                .put("order_status", "SUBMISSION_UNKNOWN")
                .put("campaign_status", "ENTRY_PENDING")
                .put("error", failure.message ?: failure.javaClass.simpleName)
                .put("failed_at_ms", clock())
            saveIntents(currentIntents)
            state = State.RECONCILE_REQUIRED
            lastBlocker = "ambiguous_entry_submission_reconcile_before_any_retry"
            JSONObject()
                .put("accepted", false)
                .put("state", state.name)
                .put("blocker", lastBlocker)
                .put("error", failure.message ?: failure.javaClass.simpleName)
        }
    }

    /** Cancel unfilled entries on STOP/KILL without retrying ambiguous mutations. */
    fun cancelPendingEntries(reason: String, onlySymbol: String? = null): JSONObject = synchronized(lock) {
        val adapter = adapterFactory()
        val all = intents()
        val openPositions = adapter.positions()
        val openSymbols = mutableSetOf<String>()
        for (i in 0 until openPositions.length()) {
            val row = openPositions.optJSONObject(i)
                ?: throw IllegalStateException("Binance returned a malformed position row at index $i during entry cancellation")
            val amount = row.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Binance returned a malformed position amount during entry cancellation")
            if (kotlin.math.abs(amount) > 0.0) {
                openSymbols.add(row.optString("symbol").uppercase(Locale.US))
            }
        }
        try {
            val failures = mutableListOf<String>()
            fun persistConfirmedCancellation(
                intent: JSONObject,
                symbol: String,
                clientId: String,
                status: String,
                resultJson: String? = null,
                confirmedOrder: JSONObject? = null
            ) {
                val exchangeStatus = status.uppercase(Locale.US)
                if (exchangeStatus !in ENTRY_ORDER_TERMINAL_STATUSES) {
                    throw IOException("Binance did not confirm a terminal status for $clientId (status=$exchangeStatus)")
                }
                // Refresh position state after the cancel/query, not from the stale
                // snapshot taken before the mutation. An order may fill while DELETE
                // is in flight, and marking that campaign CLOSED would strand exposure.
                val refreshedPositions = adapter.positions()
                val livePosition = (0 until refreshedPositions.length()).mapNotNull { j ->
                    val row = refreshedPositions.optJSONObject(j)
                        ?: throw IOException("Malformed position row after cancel for $symbol")
                    if (row.optString("symbol").equals(symbol, ignoreCase = true)) row else null
                }.firstOrNull { row ->
                    val amount = row.optString("positionAmt").toBigDecimalOrNull()
                        ?: throw IOException("Malformed $symbol position amount after cancel")
                    amount.signum() != 0
                }
                val liveAmount = livePosition?.optString("positionAmt")?.toBigDecimalOrNull()
                    ?: java.math.BigDecimal.ZERO
                if (liveAmount.signum() != 0) {
                    val exchangeDirection = if (liveAmount.signum() > 0) "LONG" else "SHORT"
                    if (!intent.optString("direction").equals(exchangeDirection, ignoreCase = true)) {
                        throw IOException("$symbol direction changed during entry cancellation; reconciliation required")
                    }
                    openSymbols.add(symbol.uppercase(Locale.US))
                } else {
                    openSymbols.remove(symbol.uppercase(Locale.US))
                }
                val reportedExecuted = listOf("executedQty", "cumQty", "cumBase", "filledQty")
                    .mapNotNull { key -> confirmedOrder?.optString(key)?.toBigDecimalOrNull() }
                    .any { it.signum() > 0 }
                val priorCancelStatus = intent.optString("cancel_prior_order_status").uppercase(Locale.US)
                val possiblePartialFill = exchangeStatus == "FINISHED" || reportedExecuted ||
                    priorCancelStatus == "PARTIALLY_FILLED" ||
                    campaignLifecycleStatus(intent) == "FILL_CONFIRMATION_PENDING"
                val campaignStatus = when {
                    liveAmount.signum() != 0 -> "POSITION_OPEN"
                    possiblePartialFill -> "FILL_CONFIRMATION_PENDING"
                    else -> "CLOSED_OR_STOPPED"
                }
                val nowMs = clock()
                intent.put("exchange_status", exchangeStatus)
                    .put("order_status", exchangeStatus)
                    .put("campaign_status", campaignStatus)
                    .put("cancel_reconciled_status", exchangeStatus)
                    .put("cancel_reconciled_at_ms", nowMs)
                    .put("position_observed_after_cancel", liveAmount.toPlainString())
                if (possiblePartialFill && intent.optLong("fill_observed_at_ms", 0L) <= 0L) {
                    intent.put("fill_observed_at_ms", nowMs)
                }
                if (campaignStatus == "CLOSED_OR_STOPPED") intent.put("closed_observed_at_ms", nowMs)
                if (resultJson != null) intent.put("cancel_result", resultJson)
                saveIntents(all)
            }
            for (i in 0 until all.length()) {
                val intent = all.optJSONObject(i) ?: continue
                if (intent.optString("intent_role", "ENTRY") !in setOf("ENTRY","ADD_ON")) continue
                val symbol = intent.optString("symbol")
                if (onlySymbol != null && !symbol.equals(onlySymbol, true)) continue
                val status = exchangeOrderStatus(intent)
                if (status !in activeStatuses) continue
                val clientId = intent.optString("client_id")
                var confirmedLiveOrderStatusForCancel: String? = null
                try {
                if (status == "CANCEL_SUBMISSION_UNKNOWN") {
                    // A previous cancellation may have reached Binance before process death.
                    // Query the exchange; never issue the same cancel again blindly.
                    val observed = when (intent.optString("kind")) {
                        EntryKind.LIMIT.name, EntryKind.MARKET.name -> runCatching { adapter.order(symbol, clientId) }.getOrNull()
                        EntryKind.STOP.name, EntryKind.TAKE_PROFIT.name ->
                            runCatching { adapter.queryAlgoOrder(symbol, clientId) }.getOrNull()
                        else -> throw IllegalStateException("Unknown pending-entry type; cannot reconcile cancellation")
                    }
                    val observedStatus = observed
                        ?.let { it.optString("status", it.optString("algoStatus", "")) }
                        ?.uppercase(Locale.US).orEmpty()
                    if (observedStatus in ENTRY_ORDER_TERMINAL_STATUSES) {
                        persistConfirmedCancellation(
                            intent, symbol, clientId, observedStatus, confirmedOrder = observed
                        )
                        continue
                    }
                    if (observedStatus !in setOf(
                            "NEW", "PENDING", "WORKING", "PARTIALLY_FILLED", "ACCEPTED", "TRIGGERED"
                        )
                    ) {
                        throw IOException("Cancellation outcome for $clientId remains ambiguous (observed status=$observedStatus); no cancel retry sent")
                    }
                    // A read-after-unknown result proves the order is still live on
                    // Binance; retrying the idempotent DELETE is now informed, not blind.
                    confirmedLiveOrderStatusForCancel = observedStatus
                }
                intent.put("cancel_prior_order_status", confirmedLiveOrderStatusForCancel ?: status)
                    .put("exchange_status", "CANCEL_SUBMISSION_UNKNOWN")
                    .put("order_status", "CANCEL_SUBMISSION_UNKNOWN")
                    .put("cancel_reason", reason)
                    .put("cancel_attempt_at_ms", clock())
                saveIntents(all)
                val result = when (intent.optString("kind")) {
                    EntryKind.LIMIT.name, EntryKind.MARKET.name -> adapter.cancelOrder(symbol, clientId)
                    EntryKind.STOP.name, EntryKind.TAKE_PROFIT.name -> adapter.cancelAlgoOrder(symbol, clientId)
                    else -> throw IllegalStateException("Unknown pending-entry type; cannot cancel safely")
                }
                // A successful DELETE response is not enough to declare cancellation:
                // read back the exact client id and require a terminal exchange status.
                val confirmed = when (intent.optString("kind")) {
                    EntryKind.LIMIT.name, EntryKind.MARKET.name -> adapter.order(symbol, clientId)
                    EntryKind.STOP.name, EntryKind.TAKE_PROFIT.name -> adapter.queryAlgoOrder(symbol, clientId)
                    else -> throw IllegalStateException("Unknown pending-entry type; cannot confirm cancellation")
                }
                val confirmedStatus = confirmed.optString("status").ifBlank {
                    confirmed.optString("algoStatus")
                }.uppercase(Locale.US)
                if (confirmedStatus !in ENTRY_ORDER_TERMINAL_STATUSES) {
                    throw IOException("Cancellation for $clientId is not exchange-confirmed terminal (status=$confirmedStatus)")
                }
                persistConfirmedCancellation(
                    intent, symbol, clientId, confirmedStatus, result.toString(), confirmed
                )
                } catch (failure: Exception) {
                    val detail = "$symbol/$clientId: " + (failure.message ?: failure.javaClass.simpleName)
                    failures += detail
                    intent.put("exchange_status", "CANCEL_SUBMISSION_UNKNOWN")
                        .put("order_status", "CANCEL_SUBMISSION_UNKNOWN")
                        .put("cancel_error", detail)
                    saveIntents(all)
                }
            }
            if (failures.isNotEmpty()) {
                state = State.RECONCILE_REQUIRED
                lastBlocker = "one_or_more_pending_Futures_entries_have_unresolved_cancellation"
                return@synchronized JSONObject().put("ok", false).put("state", state.name)
                    .put("reason", reason).put("blocker", lastBlocker)
                    .put("errors", JSONArray().apply { failures.forEach { put(it) } })
            }
            state = if (openSymbols.isEmpty()) State.READY else State.RECONCILE_REQUIRED
            lastBlocker = if (openSymbols.isEmpty()) null
                else "open_positions_require_protection_reconciliation_after_pending_entry_cancellation"
            JSONObject().put("ok", true).put("state", state.name).put("reason", reason)
                .put("positions_require_reconciliation", openSymbols.isNotEmpty())
                .put("blocker", lastBlocker ?: JSONObject.NULL)
        } catch (failure: Exception) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "ambiguous_pending_entry_cancel_reconcile_required"
            JSONObject().put("ok", false).put("state", state.name)
                .put("blocker", lastBlocker)
                .put("error", failure.message ?: failure.javaClass.simpleName)
        }
    }

    /** Structural trailing stop: the replacement is confirmed before old protection is canceled. */
    fun trailProtectiveStop(
        symbol: String,
        direction: Direction,
        newStopPrice: String,
        clientAlgoId: String,
        reason: String = "UNSPECIFIED_STRUCTURAL_TRAIL"
    ): JSONObject = synchronized(lock) {
        val foreignOrderRiskOnly = state in setOf(State.RECONCILE_REQUIRED, State.POSITION_UNPROTECTED) &&
            clientAlgoId.startsWith("W2T_") &&
            externalStopManagementVerifiedAtMs>0L &&
            clock()>=externalStopManagementVerifiedAtMs &&
            clock()-externalStopManagementVerifiedAtMs<=30_000L &&
            (0 until JSONArray(externalStopManagementSymbolsJson).length()).any {
                JSONArray(externalStopManagementSymbolsJson).optString(it)==symbol
            }
        val cappedRiskOnly = state == State.RECONCILE_REQUIRED &&
            lastBlocker?.startsWith("ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP:") == true &&
            verifiedRiskCapManagementAtMs > 0L && clock() >= verifiedRiskCapManagementAtMs &&
            clock() - verifiedRiskCapManagementAtMs <= 30_000L
        check(state == State.OPEN_PROTECTED || state == State.ENTRY_PENDING ||
            cappedRiskOnly || foreignOrderRiskOnly) {
            "Stop trail blocked while Futures state is $state"
        }
        require(symbol in allowedSymbols)
        require(clientAlgoId.matches(Regex("[A-Za-z0-9_./:-]{1,36}")))
        val persisted = intents()
        val intent = (0 until persisted.length()).mapNotNull { persisted.optJSONObject(it) }
            .lastOrNull {
                it.optString("intent_role", "ENTRY") == "ENTRY" &&
                    it.optString("symbol").equals(symbol, true) &&
                    it.optString("direction").equals(direction.name, true) &&
                    campaignLifecycleStatus(it) == "OPEN_PROTECTED"
            } ?: throw IllegalStateException("No persisted protected campaign for $symbol")
        val oldStop = intent.optString("protective_stop").toBigDecimalOrNull()
            ?: throw IllegalStateException("Persisted structural stop is invalid")
        val proposed = newStopPrice.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Proposed stop is invalid")
        require(if (direction == Direction.LONG) proposed > oldStop else proposed < oldStop) {
            "Trailing stop must only tighten risk"
        }

        val adapter = adapterFactory()
        try {
            check(intent.optBoolean("strategy_ownership_verified", false)) { "Trailing requires verified campaign ownership" }
            check(latestEntryIntentIndex(persisted, symbol)?.let { persisted.optJSONObject(it)?.optString("client_id") } == intent.optString("client_id")) {
                "Trailing cannot use a historical campaign"
            }
            adapter.requireOneWayMode()
            check((0 until persisted.length()).map { persisted.getJSONObject(it) }.none {
                it.optString("entry_client_id") == intent.optString("client_id") &&
                    (it.optString("intent_role") == "ADD_ON" && exchangeOrderStatus(it) !in ENTRY_ORDER_TERMINAL_STATUSES ||
                        it.optString("intent_role") == "EXIT" && it.optString("exchange_status") != "EXIT_CONFIRMED")
            }) { "Unresolved addition/exit blocks stop replacement" }
            check(adapter.openOrders(symbol).length() == 0) { "Pending ordinary order blocks stop replacement" }
            val snapshot = adapter.positions()
            val live = (0 until snapshot.length()).map { snapshot.getJSONObject(it) }
                .filter { it.optString("symbol") == symbol && it.getString("positionAmt").toBigDecimal().signum() != 0 }
            check(live.size == 1) { "Trailing requires exactly one live campaign position" }
            val amount = live.single().getString("positionAmt").toBigDecimal()
            val expectedQty = intent.optString("confirmed_position_qty").toBigDecimalOrNull()
            check(live.single().optString("positionSide", "BOTH") == "BOTH" &&
                (amount.signum() > 0) == (direction == Direction.LONG) &&
                expectedQty != null && expectedQty.signum() > 0 && amount.abs().compareTo(expectedQty) == 0) {
                "Trailing position quantity/direction changed since campaign confirmation"
            }
            val confirmedEntry = intent.optString("actual_entry_price").toDoubleOrNull()
            val liveEntry = live.single().optString("entryPrice").toDoubleOrNull()
            check(confirmedEntry != null && confirmedEntry.isFinite() && confirmedEntry > 0 &&
                liveEntry != null && liveEntry.isFinite() &&
                kotlin.math.abs(confirmedEntry - liveEntry) <= maxOf(1e-8, confirmedEntry * 1e-8)) {
                "Trailing position entry price changed since campaign confirmation"
            }
            check(isProtectiveStopOnCorrectSide(direction.name, adapter.markPrice(symbol).toString(), newStopPrice)) {
                "Trailing stop is on the wrong side of the current mark"
            }
        } catch (failure: Exception) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol trailing preflight blocked: ${failure.message ?: failure.javaClass.simpleName}"
            return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
        }
        val before = adapter.openAlgoOrders(symbol)
        val currentProtectionId = intent.optString("protection_client_id")
        val confirmedOld = findProtectiveStop(before, direction.name, currentProtectionId, oldStop.toPlainString(), expectedSymbol = symbol)
        val previousId = intent.optString("previous_protection_client_id")
        val previous = (0 until before.length()).mapNotNull { before.optJSONObject(it) }
            .filter { isProtectiveStop(it, direction.name) }
        val unknownStops = previous.filter {
            it.optString("clientAlgoId") != currentProtectionId &&
                (previousId.isBlank() || it.optString("clientAlgoId") != previousId)
        }
        if (confirmedOld == null || unknownStops.isNotEmpty() ||
            (0 until before.length()).any {
                before.getJSONObject(it).optString("clientAlgoId") !in setOf(currentProtectionId, previousId)
            }) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol current structural stop is not uniquely exchange-confirmed; trailing blocked"
            intent.put("protection_status", "SUBMISSION_UNKNOWN")
            saveIntents(persisted)
            return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
        }
        // Do not build an unbounded chain of uncertain stop ids. Reconciliation must
        // remove a tracked predecessor before a new replacement attempt is admitted.
        if (previousId.isNotBlank()) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol has a tracked predecessor or uncertain replacement id; reconcile it before trailing again"
            return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
        }
        if (intent.has("native_stop_replacement") || clock() < intent.optLong("trail_retry_after_ms", 0L)) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol native stop replacement is pending or cooling down"
            return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
        }
        check(clientAlgoId != currentProtectionId && (0 until persisted.length()).none {
            val row = persisted.getJSONObject(it)
            clientAlgoId in setOf(row.optString("client_id"), row.optString("protection_client_id"),
                row.optString("previous_protection_client_id"))
        }) { "Native replacement requires a new, distinct client id" }
        val bridgeHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(clientAlgoId.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
        val bridgeId = "W2B_${symbol}_${bridgeHash}".take(36)
        intent.put("native_stop_replacement", JSONObject()
            .put("phase", "PREPARED").put("old_id", currentProtectionId).put("old_stop", oldStop.toPlainString())
            .put("new_id", clientAlgoId).put("new_stop", proposed.stripTrailingZeros().toPlainString())
            .put("bridge_id", bridgeId).put("quantity", intent.getString("confirmed_position_qty"))
            .put("entry_price", intent.getString("actual_entry_price"))
            .put("bridge_outcome", "NOT_SUBMITTED").put("final_outcome", "NOT_SUBMITTED")
            .put("reason", safeDiagnosticText(reason).take(240)).put("created_at_ms", clock()))
            .put("trail_attempt_at_ms", clock()).put("trail_attempt_reason", safeDiagnosticText(reason).take(240))
        saveIntents(persisted)
        val engine = nativeStopReplacementEngine(adapter, persisted)
        val result = engine.resume(intent)
        if (result.optBoolean("ok")) {
            // The final close-all STOP was queried and validated by resume().
            // Publish THAT signed proof immediately; a status request must not
            // reuse the pre-swap STOP observation while the next whole-account
            // reconciliation is still running (Diagnostics 9 / BNB SHORT).
            // This is read-only evidence, NOT an entry or management permission.
            val confirmed = result.optJSONObject("stop")
            val confirmedQty = intent.optString("confirmed_position_qty").toBigDecimalOrNull()
            val confirmedPrice = confirmed?.optString("triggerPrice")
                ?.toBigDecimalOrNull()
            if (confirmed != null && confirmedQty != null && confirmedQty.signum() > 0 &&
                confirmed.optString("clientAlgoId") == intent.optString("protection_client_id") &&
                confirmedPrice != null && confirmedPrice.compareTo(proposed) == 0
            ) {
                val previous = JSONArray(externalProtectionObservationsJson)
                val updated = JSONArray()
                for (index in 0 until previous.length()) {
                    val observation = previous.optJSONObject(index) ?: continue
                    if (observation.optString("symbol") != symbol) updated.put(observation)
                }
                val signedQty = if (direction == Direction.SHORT) confirmedQty.negate() else confirmedQty
                updated.put(JSONObject()
                    .put("symbol", symbol).put("direction", direction.name)
                    .put("quantity", signedQty.toPlainString())
                    .put("observed_at_ms", clock()).put("stop_proof", "OWNED_STOP_SEEN")
                    .put("stop", confirmedPrice.toPlainString())
                    .put("protection_client_id", intent.optString("protection_client_id"))
                    .put("strategy_ownership_verified", true)
                    .put("automatic_trailing_authorized", true)
                    .put("management_mode", "STRATEGY_OWNED"))
                externalProtectionObservationsJson = updated.toString()
            }
        } else {
            if (intent.has("native_stop_replacement")) {
                recordNativeReplacementProof(intent, engine.protection(intent))
                saveIntents(persisted)
                // The old close-all order may already have been cancelled.
                // An earlier proof must NOT outlive an unresolved replacement.
                val previous = JSONArray(externalProtectionObservationsJson)
                val updated = JSONArray()
                for (index in 0 until previous.length()) {
                    val observation = previous.optJSONObject(index) ?: continue
                    if (observation.optString("symbol") != symbol) updated.put(observation)
                }
                externalProtectionObservationsJson = updated.toString()
            }
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol native stop replacement: ${result.optString("blocker") }"
        }
        result.put("state", state.name)
    }

    /** Accounting authority requires an exact attributed fill and verified protection. */
    private fun recordCourseStageFills(rows:JSONArray,executions:Map<String,JSONObject>,positions:Map<String,JSONObject>,
        adapter:BinanceUsdMFuturesTestnetAdapter) {
        val all=(0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        for(owner in all.filter { it.optString("intent_role","ENTRY")=="ENTRY" && it.optString("strategy_profile")==williamsCourseProfile &&
            campaignLifecycleStatus(it)=="OPEN_PROTECTED" && it.optString("protection_status")=="PROTECTED" }) {
            val management=owner.optJSONObject("edition_management") ?: JSONObject()
            owner.put("edition_management",management)
            val initial=!management.has("course_unit_quantity")
            val pendingId=management.optString("course_pending_client_id")
            if(!initial && pendingId.isBlank()) continue
            val id=owner.getString("client_id");val symbol=owner.getString("symbol")
            val setup=runCatching { JSONObject(owner.optString("setup_context")) }.getOrDefault(JSONObject())
            val proposal=(if(initial) setup.optJSONObject("course_exact_quantity") else management.optJSONObject("course_pending_quantity"))
            if(proposal==null) { management.put("course_quantity_blocker","COURSE_EXACT_QUANTITY_PROPOSAL_UNVERIFIED");continue }
            val fillId=if(initial) id else pendingId
            val fillIntent=all.singleOrNull { it.optString("client_id")==fillId && it.optString("symbol")==symbol &&
                (initial && it===owner || !initial && it.optString("entry_client_id")==id && it.optString("intent_role") in setOf("ADD_ON","EXIT")) }
            val position=positions[symbol] ?: continue
            runCatching {
                check(fillIntent!=null) { "COURSE_QUANTITY_FILL_OWNER_MISMATCH" }
                val reduce=proposal.optString("action")=="REDUCE"
                val actual=if(reduce) adapter.order(symbol,fillId) else executions[fillId]
                check(actual!=null) { "COURSE_SIGNED_STAGE_FILL_UNAVAILABLE" }
                val expectedId=if(reduce) fillIntent.optString("exchange_order_id") else fillIntent.optString("actual_entry_order_id")
                check(expectedId.isNotBlank()) { "COURSE_SIGNED_STAGE_FILL_IDENTITY_UNVERIFIED" }
                val blocker=williamsCourseFillBlocker(proposal,actual,position,symbol,owner.getString("direction"),
                    if(reduce) fillId else actual.optString("clientOrderId"),expectedId,true)
                check(blocker==null) { blocker!! }
                check(proposal.optString("from_stage")==if(initial) "" else management.optString("course_stage")) { "COURSE_QUANTITY_FILL_STAGE_CHANGED" }
                if(initial) check(setup.optLong("course_origin_at")>0 && setup.optString("course_initial_stage")==proposal.getString("stage")) {
                    "COURSE_INITIAL_PARENT_CHILD_DEGREE_UNPROVEN" }
                else check(management.optString("course_unit_quantity").toBigDecimalOrNull()?.compareTo(proposal.getString("unit_quantity").toBigDecimal())==0) {
                    "COURSE_QUANTITY_FILL_UNIT_CHANGED" }
                management.put("course_stage",proposal.getString("stage")).put("course_unit_quantity",proposal.getString("unit_quantity"))
                    .put("course_unit_basis","CONFIRMED_INITIAL_FILL").put("policy",WILLIAMS_COURSE_QUANTITY_POLICY)
                if(initial) management.put("course_origin_at",setup.getLong("course_origin_at"))
                if(proposal.has("w3_to_w1_ratio")) management.put("course_w3_to_w1_ratio",proposal.getDouble("w3_to_w1_ratio"))
                val receipts=management.optJSONArray("course_stage_fills") ?: JSONArray()
                if((0 until receipts.length()).none { receipts.getJSONObject(it).optString("client_id")==fillId })
                    receipts.put(JSONObject().put("source","SIGNED_EXACT_COURSE_STAGE_FILL").put("client_id",fillId)
                        .put("order_id",expectedId).put("stage",proposal.getString("stage")).put("quantity",proposal.getString("quantity"))
                        .put("target_quantity",proposal.getString("target_quantity")).put("unit_quantity",proposal.getString("unit_quantity")))
                management.put("course_stage_fills",receipts)
                for(key in listOf("course_quantity_blocker","course_pending_stage","course_pending_target","course_pending_client_id","course_pending_quantity")) management.remove(key)
            }.onFailure { failure -> management.put("course_quantity_blocker",safeDiagnosticText(failure.message ?: "COURSE_SIGNED_STAGE_FILL_UNAVAILABLE")) }
        }
    }

    private fun recordCourseFlatHandoffs(rows:JSONArray,adapter:BinanceUsdMFuturesTestnetAdapter) {
        val all=(0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        // Preserve the last durable candidate per (campaign,symbol), but avoid
        // scanning ALL EXIT intents separately for every historical owner.
        // Exact signed order, position and STOP proof below is unchanged.
        val terminalExits=indexWilliamsCourseTerminalExits(rows)
        for(owner in all.filter { it.optString("intent_role","ENTRY")=="ENTRY" && it.optString("strategy_profile")==williamsCourseProfile &&
            campaignLifecycleStatus(it)=="CLOSED_OR_STOPPED" && it.optJSONObject("edition_management")?.has("course_flat_handoff")!=true }) {
            runCatching {
                val id=owner.getString("client_id");val symbol=owner.getString("symbol")
                val exit=terminalExits[id to symbol] ?: return@runCatching
                val order=adapter.order(symbol,exit.getString("client_id"))
                val expected=exit.getString("quantity").toBigDecimal()
                check(order.optString("symbol")==symbol && order.optString("clientOrderId")==exit.getString("client_id") &&
                    order.optString("status")=="FILLED" && order.optString("reduceOnly")=="true" &&
                    order.optString("side")== (if(owner.getString("direction")=="LONG") "SELL" else "BUY"))
                check(expected.signum()>0 && order.optString("executedQty").toBigDecimalOrNull()?.compareTo(expected)==0 &&
                    order.optString("origQty").toBigDecimalOrNull()?.compareTo(expected)==0)
                val positions=adapter.positions()
                check((0 until positions.length()).map { positions.getJSONObject(it) }.none {
                    it.optString("symbol")==symbol && (it.optString("positionAmt").toBigDecimalOrNull() ?: error("Malformed flat evidence")).signum()!=0 })
                check(adapter.openOrders(symbol).length()==0 && adapter.openAlgoOrders(symbol).length()==0)
                val management=owner.optJSONObject("edition_management") ?: JSONObject()
                val proof=williamsCourseClosedMotiveHandoff(id,owner.getString("direction"),management.optLong("course_origin_at"),
                    exit.getString("reason"),clock(),true,true,true) ?: return@runCatching
                management.put("course_flat_handoff",proof.put("exit_client_id",exit.getString("client_id")))
                owner.put("edition_management",management).put("close_reason",exit.getString("reason"))
                owner.remove("course_handoff_pending")
            }.onFailure { owner.put("course_handoff_pending",true) }
        }
    }

    fun closePosition(
        symbol: String,
        direction: Direction,
        quantity: String,
        clientOrderId: String,
        reason: String,
        partialReduction:Boolean=false
    ): JSONObject = closePositionInternal(symbol,direction,quantity,clientOrderId,reason,
        partialReduction,riskEmergencyVerified=false)

    /** Only the liquidation-remediation method may pass the private emergency capability. */
    private fun closePositionInternal(
        symbol:String,direction:Direction,quantity:String,clientOrderId:String,reason:String,
        partialReduction:Boolean,riskEmergencyVerified:Boolean
    ): JSONObject = synchronized(lock) {
        require(symbol in allowedSymbols)
        require(quantity.toBigDecimalOrNull()?.signum() == 1)
        require(clientOrderId.matches(Regex("[A-Za-z0-9_./:-]{1,36}")))
        val intents = intents()
        // Enforce the pinned edition's exit policy BEFORE cancelling additions,
        // reading Binance, or writing an exit intent. A scanner bug cannot bypass it.
        val ownerIndex = latestEntryIntentIndex(intents, symbol)
        val exitOwner = ownerIndex?.let { intents.optJSONObject(it) }
        val emergencyWithoutStop = riskEmergencyVerified && reason == "UNPROTECTED_POSITION_GUARD"
        val policyBlocker = if(riskEmergencyVerified) null else
            williamsMarketExitBlocker(exitOwner?.optString("strategy_profile").orEmpty(), reason)
        if (policyBlocker != null) {
            return@synchronized JSONObject().put("ok",false).put("state",state.name)
                .put("blocker",policyBlocker).put("safe_rejection",true)
                .put("mutation_attempted",false).put("symbol",symbol).put("reason",reason)
                .put("strategy_profile",exitOwner?.optString("strategy_profile") ?: JSONObject.NULL)
        }
        val ntdBookExit=!riskEmergencyVerified && exitOwner?.optString("strategy_profile")==williamsNtdProfile &&
            reason in williamsNtdBookExitReasons
        val courseTrim=reason=="COURSE_STAGE_TRIM"
        val courseBookExit=!riskEmergencyVerified && exitOwner?.optString("strategy_profile")==williamsCourseProfile &&
            reason in williamsCourseBookExitReasons && !courseTrim
        if(courseBookExit) {
            val proof=runCatching { (bookCourseExitVerifier ?: error("COURSE_FINAL_EXIT_VERIFIER_REQUIRED"))(
                JSONObject(exitOwner!!.toString()),reason) }
            if(proof.isFailure) return@synchronized JSONObject().put("ok",false).put("safe_rejection",true)
                .put("mutation_attempted",false).put("blocker",safeDiagnosticText(proof.exceptionOrNull()?.message.orEmpty()))
        }
        if(courseTrim) {
            val proof=runCatching {
                check(partialReduction && exitOwner?.optString("strategy_profile")==williamsCourseProfile) { "COURSE_TRIM_OWNER_OR_ROLE_UNVERIFIED" }
                (bookCourseTrimVerifier ?: error("COURSE_FINAL_TRIM_VERIFIER_REQUIRED"))(JSONObject(exitOwner!!.toString()),quantity)
            }
            if(proof.isFailure) return@synchronized JSONObject().put("ok",false).put("safe_rejection",true)
                .put("mutation_attempted",false).put("blocker",safeDiagnosticText(proof.exceptionOrNull()?.message.orEmpty()))
        }
        if(ntdBookExit) {
            val proof=runCatching { (bookNtdExitVerifier ?: error("NTD_FINAL_EXIT_VERIFIER_REQUIRED"))(
                JSONObject(exitOwner!!.toString()),reason) }
            if(proof.isFailure) return@synchronized JSONObject().put("ok",false).put("safe_rejection",true)
                .put("mutation_attempted",false).put("blocker",safeDiagnosticText(proof.exceptionOrNull()?.message.orEmpty()))
        }
        if ((0 until intents.length()).mapNotNull { intents.optJSONObject(it) }.any {
                it.optString("intent_role")=="ADD_ON" && it.optString("symbol")==symbol && exchangeOrderStatus(it) in activeStatuses }) {
            // A contrary Wise-Man exit is OPTIONAL, unlike liquidation control.
            // Never cancel a valid addition merely because a contrary setup
            // appeared, especially while the protected campaign is losing.
            if(!riskEmergencyVerified && reason in setOf(
                "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED","OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION")) {
                return@synchronized JSONObject().put("ok",false).put("state",state.name)
                    .put("blocker","TC2_OPPOSITE_EXIT_PENDING_ADDITION_NOT_RECONCILED")
                    .put("safe_rejection",true).put("mutation_attempted",false)
            }
            val cancelled=cancelPendingEntries("POSITION_EXIT_CANCEL_ADDITIONS",symbol)
            state=State.RECONCILE_REQUIRED
            return@synchronized JSONObject().put("ok",false).put("blocker","Addition canceled before exit; reconcile possible racing fill")
                .put("cancellation",cancelled)
        }
        val unresolvedExit = (0 until intents.length()).mapNotNull { intents.optJSONObject(it) }
            .lastOrNull {
                it.optString("intent_role") == "EXIT" &&
                    it.optString("symbol").uppercase(Locale.US) == symbol.uppercase(Locale.US) &&
                    it.optString("exchange_status").uppercase(Locale.US) in (activeStatuses + "EXIT_PENDING")
            }
        if (unresolvedExit != null) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "an existing exit intent is active/ambiguous; reconcile before any new close request"
            return@synchronized JSONObject().put("ok", false).put("state", state.name)
                .put("blocker", lastBlocker)
                .put("existing_exit_client_id", unresolvedExit.optString("client_id"))
        }
        if ((0 until intents.length()).mapNotNull { intents.optJSONObject(it) }
                .any { it.optString("client_id") == clientOrderId }) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "client order id was already used by a persisted Futures intent; id reuse blocked"
            return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
        }
        val manualExit = reason == "MANUAL_EXIT" || reason == "KILL_SWITCH"
        val latestIndex = latestEntryIntentIndex(intents, symbol)
        val managedEntry = latestIndex?.let { intents.optJSONObject(it) }
            ?.takeIf { row ->
                row.optString("intent_role", "ENTRY") == "ENTRY" &&
                    if (manualExit) {
                        // An explicit user close must remain possible when protection
                        // metadata was lost. It still requires a persisted active campaign
                        // and exact agreement with the exchange-confirmed position direction.
                        campaignLifecycleStatus(row) in ACTIVE_CAMPAIGN_STATUSES &&
                            (reason != "KILL_SWITCH" || row.optBoolean("strategy_ownership_verified", false)) &&
                            row.optString("direction").equals(direction.name, ignoreCase = true)
                    } else if (emergencyWithoutStop) {
                        // The public closePosition() has NO access to this
                        // exception; only the separately exchange-verified
                        // unprotected-risk capability may pass it.
                        // The entry loop downgrades a previously protected live
                        // owner to POSITION_OPEN while its stop is missing.
                        campaignLifecycleStatus(row) in setOf("POSITION_OPEN","OPEN_PROTECTED") &&
                            row.optBoolean("strategy_ownership_verified", false) &&
                            row.optString("direction") == direction.name &&
                            row.optString("protection_status") in
                                setOf("SUBMISSION_UNKNOWN","NEVER_SUBMITTED_PROVEN") &&
                            row.optLong("unprotected_safety_exit_required_at_ms") > 0L &&
                            row.optString("unprotected_safety_guard_origin") in
                                setOf("NEVER_SUBMITTED_PROVEN","TERMINAL_PREVIOUSLY_CONFIRMED") &&
                            (row.optString("protection_client_id").isNotBlank() ||
                                row.optString("protection_status")=="NEVER_SUBMITTED_PROVEN") &&
                            row.optString("previous_protection_client_id").isBlank()
                    } else {
                        isManagedOpenPositionIntent(
                            status = campaignLifecycleStatus(row),
                            direction = row.optString("direction"),
                            expectedDirection = direction.name,
                            protectionStatus = row.optString("protection_status"),
                            protectionClientId = row.optString("protection_client_id"),
                            strategyOwnershipVerified = row.optBoolean("strategy_ownership_verified", false)
                        ) &&
                            run {
                                val entry = row.optString("entry_price").toBigDecimalOrNull()
                                val stop = row.optString("protective_stop").toBigDecimalOrNull()
                                entry != null && stop != null && entry.signum() > 0 && stop.signum() > 0
                            }
                    }
            }
        if (managedEntry == null && reason != "MANUAL_EXIT") {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol has no confirmed active Williams campaign; strategy-driven exit is blocked"
            return@synchronized JSONObject().put("ok", false).put("state", state.name)
                .put("blocker", lastBlocker)
        }
        // MANUAL_EXIT is the explicit operator's risk-reducing override. If the
        // campaign journal is missing, continue only to the live Binance position
        // and exact quantity/direction checks below; never use this path for entries.
        if(partialReduction && (reason!="COURSE_STAGE_TRIM" || managedEntry?.optString("strategy_profile")!=williamsCourseProfile))
            return@synchronized JSONObject().put("ok",false).put("blocker","Partial reductions require an owned Course stage")
        var positionBefore=java.math.BigDecimal.ZERO
        val adapterPreflight = adapterFactory()
        if(partialReduction) {
            val contract=futuresContractRuleBlocker(adapterPreflight.exchangeInfo(),symbol,quantity,
                managedEntry!!.getString("entry_price"),managedEntry.getString("protective_stop"),marketOrder=true)
            if(contract!=null) return@synchronized JSONObject().put("ok",false).put("blocker",contract)
        }
        try {
            adapterPreflight.requireOneWayMode()
            val livePositions = adapterPreflight.positions()
            val liveForSymbol = (0 until livePositions.length()).mapNotNull { i ->
                val row = livePositions.optJSONObject(i)
                    ?: throw IllegalStateException("Malformed position row during exit preflight")
                if (!row.optString("symbol").equals(symbol, true)) return@mapNotNull null
                val amount = row.optString("positionAmt").toBigDecimalOrNull()
                    ?: throw IllegalStateException("Malformed live position amount for $symbol")
                if (amount.signum() == 0) null else row to amount
            }
            if (liveForSymbol.size != 1) {
                state = State.RECONCILE_REQUIRED
                lastBlocker = "$symbol does not have exactly one exchange-confirmed live position; exit mutation blocked"
                return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
            }
            val liveAmount = liveForSymbol.single().second
            check(liveForSymbol.single().first.optString("positionSide", "BOTH") == "BOTH") {
                "Exit requires an exchange-confirmed one-way position"
            }
            val liveDirection = if (liveAmount.signum() > 0) Direction.LONG else Direction.SHORT
            val requestedQty = quantity.toBigDecimal()
            positionBefore=liveAmount.abs()
            val quantityMatches=if(partialReduction) requestedQty<positionBefore else requestedQty.compareTo(positionBefore)==0
            if (liveDirection != direction || !quantityMatches) {
                state = State.RECONCILE_REQUIRED
                lastBlocker = "$symbol exit direction/quantity does not exactly match the live Binance position; exit mutation blocked"
                return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
            }
            if (reason != "MANUAL_EXIT") {
                val confirmedQty = managedEntry?.optString("confirmed_position_qty")?.toBigDecimalOrNull()
                check(confirmedQty != null && confirmedQty.signum() > 0 && confirmedQty.compareTo(liveAmount.abs()) == 0) {
                    "$symbol automatic exit quantity is no longer attributable to the confirmed campaign"
                }
                val confirmedEntry = managedEntry?.optString("actual_entry_price")?.toDoubleOrNull()
                val liveEntry = liveForSymbol.single().first.optString("entryPrice").toDoubleOrNull()
                check(confirmedEntry != null && confirmedEntry.isFinite() && confirmedEntry > 0 &&
                    liveEntry != null && liveEntry.isFinite() &&
                    kotlin.math.abs(confirmedEntry - liveEntry) <= maxOf(1e-8, confirmedEntry * 1e-8)) {
                    "$symbol automatic exit entry price no longer matches the confirmed campaign"
                }
            }
            // Do not trust the caller's TC2 exit reason: bind a recently
            // durable closed-H1 opposite-signal proof to this exact owner,
            // symbol/direction, and the exchange's CURRENT contract price before POST.
            // Unknown or stale evidence keeps the exchange-side STOP alive.
            if(!riskEmergencyVerified && managedEntry?.optString("strategy_profile")==williamsNtdProfile &&
                reason in setOf("OPPOSITE_WILLIAMS_LEVEL_TRIGGERED","OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION",
                    "NTD_OPPOSITE_SIGNAL_TRIGGERED")) {
                val profit=williamsTc2OppositeExitProfitBlocker(direction.name,
                    liveForSymbol.single().first.optString("entryPrice").toDoubleOrNull() ?: Double.NaN,
                    adapterPreflight.markPrice(symbol))
                if(profit!=null) return@synchronized JSONObject().put("ok",false).put("safe_rejection",true)
                    .put("mutation_attempted",false).put("blocker",profit).put("symbol",symbol)
            }
            if(!riskEmergencyVerified &&
                managedEntry?.optString("strategy_profile")==williamsTc2Profile &&
                reason in setOf("OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",
                    "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION")) {
                val markNow=adapterPreflight.markPrice(symbol)
                val contractNow=adapterPreflight.contractPrice(symbol)
                val blocker=williamsTc2OppositeExitEvidenceBlocker(
                    managedEntry,executionStore.readState("decision_h1_opposition_"+symbol),
                    symbol,direction.name,reason,
                    managedEntry.optLong("position_open_observed_at_ms"),clock(),contractNow
                )
                if(blocker!=null)
                    return@synchronized JSONObject().put("ok",false).put("state",state.name)
                        .put("safe_rejection",true).put("mutation_attempted",false)
                        .put("blocker",blocker).put("symbol",symbol)
                val liveEntry=liveForSymbol.single().first.optString("entryPrice").toDoubleOrNull()
                    ?: Double.NaN
                val profitBlocker=williamsTc2OppositeExitProfitBlocker(
                    direction.name,liveEntry,markNow)
                if(profitBlocker!=null)
                    return@synchronized JSONObject().put("ok",false).put("state",state.name)
                        .put("safe_rejection",true).put("mutation_attempted",false)
                        .put("blocker",profitBlocker).put("symbol",symbol)
                // MARK is not the executable exit quote. A LONG closes at BID,
                // a SHORT closes at ASK. Preserve the exchange STOP and reject
                // optional profit-taking if the actual quote does not clear
                // the conservative fee/slippage reserve.
                val (bidNow,askNow)=adapterPreflight.bestBidAsk(symbol)
                val quoteBlocker=williamsOppositeExitBidAskBlocker(
                    direction.name,liveEntry,bidNow,askNow)
                if(quoteBlocker!=null)
                    return@synchronized JSONObject().put("ok",false).put("state",state.name)
                        .put("safe_rejection",true).put("mutation_attempted",false)
                        .put("blocker",quoteBlocker).put("symbol",symbol)
            }
            if(ntdBookExit) (bookNtdExitVerifier ?: error("NTD_FINAL_EXIT_VERIFIER_REQUIRED"))(
                JSONObject(managedEntry!!.toString()),reason)
            if(courseTrim) {
                val proof=runCatching { (bookCourseTrimVerifier ?: error("COURSE_FINAL_TRIM_VERIFIER_REQUIRED"))(
                    JSONObject(managedEntry!!.toString()),quantity) }
                if(proof.isFailure) return@synchronized JSONObject().put("ok",false).put("safe_rejection",true)
                    .put("mutation_attempted",false).put("blocker",safeDiagnosticText(proof.exceptionOrNull()?.message.orEmpty()))
            }
            if(courseBookExit) {
                val proof=runCatching { (bookCourseExitVerifier ?: error("COURSE_FINAL_EXIT_VERIFIER_REQUIRED"))(
                    JSONObject(managedEntry!!.toString()),reason) }
                if(proof.isFailure) return@synchronized JSONObject().put("ok",false).put("safe_rejection",true)
                    .put("mutation_attempted",false).put("blocker",safeDiagnosticText(proof.exceptionOrNull()?.message.orEmpty()))
            }
            if (emergencyWithoutStop) {
                // Second read as close to POST as possible, because the
                // previously missing STOP could have been replaced meanwhile.
                val stopId = managedEntry!!.optString("protection_client_id")
                val noPost = managedEntry!!.optString("protection_status")=="NEVER_SUBMITTED_PROVEN" &&
                    stopId.isBlank()
                val terminalConfirmed = noPost || matchesTerminalUntriggeredFuturesProtectiveStop(
                    adapterPreflight.queryAlgoOrder(symbol, stopId),symbol,direction.name,stopId,
                    managedEntry.optString("protective_stop"))
                if (!terminalConfirmed ||
                    adapterPreflight.openAlgoOrders(symbol).length() != 0 ||
                    adapterPreflight.openOrders(symbol).length() != 0) {
                    state = State.RECONCILE_REQUIRED
                    lastBlocker = "$symbol cannot prove no live exchange protection just before emergency close"
                    return@synchronized JSONObject().put("ok",false).put("state",state.name)
                        .put("safe_rejection",true).put("blocker",lastBlocker)
                }
            } else if (!manualExit) {
                val expectedProtectionId = managedEntry!!.optString("protection_client_id")
                val expectedStop = managedEntry!!.optString("protective_stop")
                val openAlgos = adapterPreflight.openAlgoOrders(symbol)
                if (findProtectiveStop(openAlgos, direction.name, expectedProtectionId, expectedStop, expectedSymbol = symbol) == null) {
                    state = State.RECONCILE_REQUIRED
                    lastBlocker = "$symbol current campaign stop is not exchange-confirmed by exact client id and structural trigger; exit mutation blocked"
                    managedEntry!!.put("protection_status", "SUBMISSION_UNKNOWN")
                    saveIntents(intents)
                    return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
                }
            }
        } catch (failure: Exception) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol exit preflight could not confirm exchange position/protection: ${failure.message ?: failure.javaClass.simpleName}"
            return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
        }
        val adapter = adapterPreflight
        val currentProtectionId = managedEntry?.optString("protection_client_id").orEmpty()
        val previousProtectionId = managedEntry?.optString("previous_protection_client_id").orEmpty()
        val exitIntent = JSONObject()
            .put("intent_role", "EXIT")
            .put("symbol", symbol)
            .put("direction", direction.name)
            .put("kind", "MARKET_REDUCE_ONLY")
            .put("quantity", quantity)
            .put("client_id", clientOrderId)
            .put("entry_client_id", managedEntry?.optString("client_id") ?: JSONObject.NULL)
            .put("reason", reason).put("partial_reduction",partialReduction)
            .put("strategy_profile",managedEntry?.optString("strategy_profile") ?: JSONObject.NULL)
            .put("exit_authority",if(riskEmergencyVerified) "VERIFIED_RISK_REDUCTION" else when(reason) {
                "MANUAL_EXIT" -> "OPERATOR_CONFIRMED"
                "KILL_SWITCH" -> "EMERGENCY_KILL"
                else -> "PINNED_EDITION_RULE"
            })
            .put("position_before_qty",positionBefore.toPlainString())
            .put("exchange_status", "SUBMISSION_UNKNOWN")
            .put("created_at_ms", clock())
        intents.put(exitIntent)
        managedEntry?.put("campaign_status", "EXIT_PENDING")
        saveIntents(intents)
        state = State.EXIT_PENDING
        lastBlocker = "exit_submission_and_exchange_confirmation_pending"
        try {
            adapter.requireOneWayMode()
            val result = adapter.closeMarketPosition(symbol, direction.name, quantity, clientOrderId)
            val responseStatus = result.optString("status", "UNKNOWN").uppercase(Locale.US)
            exitIntent.put("exchange_order_id", result.optString("orderId", ""))
                .put("exchange_status", responseStatus)
                .put("order_status", responseStatus)
                .put("submitted_at_ms", clock())
            saveIntents(intents)

            // A successful POST response is not proof of a completed exit. Query the
            // same client order id and require FILLED before treating the campaign flat.
            val confirmedOrder = adapter.order(symbol, clientOrderId)
            val finalOrderStatus = confirmedOrder.optString("status").uppercase(Locale.US)
            exitIntent.put("exchange_order_id", confirmedOrder.optString("orderId", result.optString("orderId", "")))
                .put("exchange_status", finalOrderStatus)
                .put("order_status", finalOrderStatus)
                .put("executed_qty",confirmedOrder.optString("executedQty"))
                .put("last_reconciled_ms", clock())
            saveIntents(intents)
            if (finalOrderStatus != "FILLED") {
                state = if (finalOrderStatus in activeStatuses) State.EXIT_PENDING else State.RECONCILE_REQUIRED
                lastBlocker = "$symbol exit is not exchange-confirmed FILLED (status=$finalOrderStatus); protection retained and retry blocked"
                return@synchronized JSONObject().put("ok", false).put("state", state.name)
                    .put("blocker", lastBlocker).put("order", confirmedOrder)
            }

            val remaining = adapter.positions()
            if(partialReduction) {
                val row=(0 until remaining.length()).mapNotNull { remaining.optJSONObject(it) }.singleOrNull { it.optString("symbol")==symbol }
                    ?: error("Course reduction unexpectedly left the campaign flat")
                val signed=row.getString("positionAmt").toBigDecimal()
                val expected=positionBefore-quantity.toBigDecimal()
                check(signed.abs().compareTo(expected)==0 && (signed.signum()>0)==(direction==Direction.LONG)) { "Course trim remainder does not match its confirmed fill" }
                check(confirmedOrder.getString("executedQty").toBigDecimal().compareTo(quantity.toBigDecimal())==0) { "Course trim fill is incomplete" }
                exitIntent.put("exchange_status","EXIT_CONFIRMED").put("order_status","FILLED")
                    .put("executed_qty",quantity).put("reduction_confirmed_at_ms",clock())
                managedEntry!!.put("campaign_status","OPEN_PROTECTED").put("confirmed_position_qty",expected.toPlainString())
                saveIntents(intents)
                state=State.RECONCILE_REQUIRED
                val verified=reconcile()
                return@synchronized JSONObject().put("ok",verified.optBoolean("ok",false)).put("partial_reduction",true)
                    .put("remaining_qty",expected.toPlainString()).put("state",state.name).put("reconciliation",verified)
            }
            val stillOpen = (0 until remaining.length()).any { i ->
                val row = remaining.optJSONObject(i)
                    ?: throw IOException("Malformed position row after exit for $symbol")
                if (!row.optString("symbol").equals(symbol, true)) return@any false
                val amount = row.optString("positionAmt").toBigDecimalOrNull()
                    ?: throw IOException("Malformed position amount after exit for $symbol")
                amount.signum() != 0
            }
            if (stillOpen) {
                state = State.RECONCILE_REQUIRED
                lastBlocker = "$symbol exit order reports FILLED but Binance still reports a position"
                return@synchronized JSONObject().put("ok", false).put("state", state.name)
                    .put("blocker", lastBlocker).put("order", confirmedOrder)
            }
            val confirmedFlatAt = clock()
            exitIntent.put("exchange_status", "EXIT_CONFIRMED")
                .put("order_status", "FILLED")
                .put("confirmed_flat_at_ms", confirmedFlatAt)
            // Terminal lifecycle is persisted before any stop-cleanup network request.
            managedEntry?.put("campaign_status", "CLOSED_OR_STOPPED")
                ?.put("closed_observed_at_ms", confirmedFlatAt)
                ?.put("close_reason", reason)
            saveIntents(intents)

            // Cancel only protective orders whose ids belong to this persisted campaign.
            // A manual/foreign stop is never canceled; it blocks release until reconciled.
            val algos = adapter.openAlgoOrders(symbol)
            for (i in 0 until algos.length()) {
                val row = algos.optJSONObject(i) ?: throw IOException("Malformed algo-order row after $symbol exit")
                if (!row.optString("type", row.optString("orderType")).equals("STOP_MARKET", true) ||
                    !row.optString("closePosition").equals("true", true)
                ) continue
                val cid = row.optString("clientAlgoId")
                if (!isOwnedProtectionClientId(cid, currentProtectionId, previousProtectionId)) {
                    throw IOException("Unmanaged close-position protective stop remains open after $symbol exit; manual reconciliation required")
                }
                val algoId = row.optString("algoId")
                if (algoId.isNotBlank()) adapter.cancelAlgoOrderById(symbol, algoId)
                else if (cid.isNotBlank()) adapter.cancelAlgoOrder(symbol, cid)
                else throw IOException("Cannot identify owned protective stop after $symbol exit")
            }
            val remainingAlgos = adapter.openAlgoOrders(symbol)
            val orphanedStopRemains = (0 until remainingAlgos.length()).any { i ->
                val row = remainingAlgos.optJSONObject(i) ?: return@any true
                row.optString("type", row.optString("orderType")).equals("STOP_MARKET", true) &&
                    row.optString("closePosition").equals("true", true)
            }
            if (orphanedStopRemains) {
                throw IOException("A close-position protective stop remains after $symbol was confirmed flat")
            }
            recordCourseFlatHandoffs(intents,adapter)
            saveIntents(intents)
            // Closing one symbol must not label the whole account READY_FLAT
            // when other concurrent campaigns remain open. Refresh and verify their
            // position/stop ownership before declaring the coordinator entry-ready.
            val accountPositionsAfterExit = adapter.positions()
            val latestIntentsAfterExit = intents()
            var remainingProtectedPositions = 0
            for (i in 0 until accountPositionsAfterExit.length()) {
                val row = accountPositionsAfterExit.optJSONObject(i)
                    ?: throw IOException("Malformed account position after closing $symbol")
                val otherSymbol = row.optString("symbol").uppercase(Locale.US)
                val otherAmount = row.optString("positionAmt").toBigDecimalOrNull()
                    ?: throw IOException("Malformed account position amount for $otherSymbol after close")
                if (otherAmount.signum() == 0) continue
                if (otherSymbol !in allowedSymbols || otherSymbol == symbol) {
                    throw IOException("Unexpected open Futures exposure remains for $otherSymbol after closing $symbol")
                }
                val otherDirection = if (otherAmount.signum() > 0) Direction.LONG.name else Direction.SHORT.name
                val ownerIndex = latestEntryIntentIndex(latestIntentsAfterExit, otherSymbol)
                    ?: throw IOException("$otherSymbol still has exposure but no persisted campaign owner after closing $symbol")
                val owner = latestIntentsAfterExit.optJSONObject(ownerIndex)
                    ?: throw IOException("Malformed campaign owner for $otherSymbol after close")
                val ownerStop = owner.optString("protective_stop")
                val ownerStopId = owner.optString("protection_client_id")
                val otherMark = adapter.markPrice(otherSymbol)
                val otherAlgos = adapter.openAlgoOrders(otherSymbol)
                if (!isManagedOpenPositionIntent(
                        campaignLifecycleStatus(owner), owner.optString("direction"), otherDirection,
                        owner.optString("protection_status"), ownerStopId,
                        owner.optBoolean("strategy_ownership_verified", false)
                    ) ||
                    !isProtectiveStopOnCorrectSide(otherDirection, otherMark.toString(), ownerStop) ||
                    findProtectiveStop(otherAlgos, otherDirection, ownerStopId, ownerStop, expectedSymbol = otherSymbol) == null
                ) {
                    throw IOException("$otherSymbol remaining exposure is not confirmed protected after closing $symbol")
                }
                remainingProtectedPositions++
            }
            state = if (remainingProtectedPositions > 0) State.OPEN_PROTECTED else State.READY
            lastBlocker = null
            JSONObject().put("ok", true).put("state", state.name).put("order", result)
                .put("flat_confirmed", true).put("open_positions_remaining", remainingProtectedPositions)
                .put("reason", reason)
        } catch (failure: Exception) {
            // Preserve the known flat/exit result if only protective-order cleanup
            // failed; reconciliation still blocks entry until the orphan is resolved.
            val flatConfirmed = exitIntent.optString("exchange_status").uppercase(Locale.US) == "EXIT_CONFIRMED"
            if (!flatConfirmed) exitIntent.put("exchange_status", "SUBMISSION_UNKNOWN")
            exitIntent.put("error", failure.message ?: failure.javaClass.simpleName)
            saveIntents(intents)
            state = State.RECONCILE_REQUIRED
            lastBlocker = if (flatConfirmed) {
                "position flat is exchange-confirmed, but protective-order cleanup remains unresolved"
            } else {
                "exit/protection cleanup unresolved; reconciliation before any retry"
            }
            JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
                .put("flat_confirmed", flatConfirmed)
                .put("protection_cleanup_pending", flatConfirmed)
                .put("reconcile_required", true)
                .put("error", failure.message ?: failure.javaClass.simpleName)
        }
    }

    fun status(): JSONObject {
        val reconciliationActive = reconciliationRunning
        val observedState = if (reconciliationActive) State.RECONCILE_REQUIRED else state
        val observedBlocker = if (reconciliationActive) "exchange_reconciliation_in_progress" else lastBlocker
        val persisted = intents()
        val pendingDetails = JSONArray()
        val now = clock()
        for (i in 0 until persisted.length()) {
            val item = persisted.optJSONObject(i) ?: continue
            val lifecycle = campaignLifecycleStatus(item)
            if (lifecycle in TERMINAL_CAMPAIGN_STATUSES) continue
            val role = item.optString("intent_role", "ENTRY")
            val orderStatus = if (role == "EXIT") {
                item.optString("exchange_status").uppercase(Locale.US)
            } else {
                exchangeOrderStatus(item)
            }
            // Terminal exits remain in the durable ledger/trade history, not
            // in the dashboard's list/count of pending exchange intentions.
            if (role == "EXIT" && orderStatus == "EXIT_CONFIRMED") continue
            pendingDetails.put(
                JSONObject()
                    .put("symbol", item.optString("symbol"))
                    .put("direction", item.optString("direction"))
                    // The owner was pinned at order admission and must not be
                    // inferred from whichever observer signal is newest now.
                    .put("strategy_profile", item.optString("strategy_profile", "UNKNOWN"))
                    .put("signal_id", item.optString("signal_id"))
                    .put("entry_client_id_suffix", item.optString("entry_client_id").takeLast(8))
                    .put("role", role)
                    .put("kind", item.optString("kind"))
                    .put("signal_type", item.optString("signal_type"))
                    .put("signal_bar_time_ms", item.optLong("signal_bar_time_ms"))
                    .put("signal_reason", item.optString("signal_reason"))
                    .put("trade_mode",item.optString("trade_mode","TREND"))
                    .put("wave_context_at_entry",item.optString("wave_context_at_entry"))
                    .put("entry_price", item.opt("entry_price") ?: JSONObject.NULL)
                    .put("quantity", item.opt("quantity") ?: JSONObject.NULL)
                    .put("risk_pct", item.opt("risk_pct") ?: JSONObject.NULL)
                    .put("requested_leverage", item.opt("requested_leverage") ?: JSONObject.NULL)
                    .put("confirmed_leverage", item.opt("confirmed_leverage") ?: JSONObject.NULL)
                    .put("liquidation_guard_status", item.optString("liquidation_guard_status", "NOT_VERIFIED"))
                    .put("liquidation_guard_blocker", item.opt("liquidation_guard_blocker") ?: JSONObject.NULL)
                    .put("exchange_liquidation_price", item.opt("exchange_liquidation_price") ?: JSONObject.NULL)
                    .put("liquidation_stop_gap_pct", item.opt("liquidation_stop_gap_pct") ?: JSONObject.NULL)
                    .put("liquidation_guard_audited_at_ms", item.opt("liquidation_guard_audited_at_ms") ?: JSONObject.NULL)
                    .put("executed_qty", item.opt("executed_qty") ?: JSONObject.NULL)
                    .put("confirmed_position_qty", item.opt("confirmed_position_qty") ?: JSONObject.NULL)
                    .put("actual_entry_price", item.opt("actual_entry_price") ?: JSONObject.NULL)
                    .put("client_id_suffix", item.optString("client_id").takeLast(8))
                    .put("order_status", orderStatus)
                    .put("exchange_order_status", item.optString("order_status"))
                    .put("campaign_status", lifecycle)
                    .put("created_at_ms", item.optLong("created_at_ms", 0L))
                    .put("last_reconciled_ms", item.optLong("last_reconciled_ms", 0L))
                    .put("reconciliation_age_ms", if (item.optLong("last_reconciled_ms", 0L) > 0L)
                        (now - item.optLong("last_reconciled_ms", now)).coerceAtLeast(0L) else JSONObject.NULL)
                    .put("age_ms", (now - item.optLong("created_at_ms", now)).coerceAtLeast(0L))
                    // Report journal protection fields separately from live exchange proof.
                    // Consumers must not interpret this persisted field alone as protection.
                    .put("protection_status", item.optString("protection_status", "UNKNOWN"))
                    .put("persisted_protective_stop", item.optString("protective_stop")
                        .toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: JSONObject.NULL)
                    .put("initial_protective_stop", item.optString("initial_protective_stop")
                        .toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: JSONObject.NULL)
                    .put("protection_client_id_suffix", item.optString("protection_client_id").takeLast(8))
                    .put("strategy_ownership_verified", item.optBoolean("strategy_ownership_verified", false))
                    .put("actual_risk_audit_status", item.optString("actual_risk_audit_status", "UNKNOWN"))
                    .put("actual_risk_audit_blocker", item.optString("actual_risk_audit_blocker").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    .put("actual_gross_stop_loss_usdt", item.opt("actual_gross_stop_loss_usdt") ?: JSONObject.NULL)
                    .put("actual_fee_slippage_allowance_usdt", item.opt("actual_fee_slippage_allowance_usdt") ?: JSONObject.NULL)
                    .put("actual_all_in_stop_loss_usdt", item.opt("actual_all_in_stop_loss_usdt") ?: JSONObject.NULL)
                    .put("actual_all_in_risk_pct", item.opt("actual_all_in_risk_pct") ?: JSONObject.NULL)
                    .put("actual_risk_audited_at_ms", item.opt("actual_risk_audited_at_ms") ?: JSONObject.NULL)
                    .put("trailing_at_ms", item.optLong("trailing_at_ms"))
                    .put("last_confirmed_trail",item.optJSONObject("last_confirmed_trail")?.let {
                        JSONObject(it.toString()).apply { remove("client_id") }
                    } ?: JSONObject.NULL)
                    .put("previous_protection_unresolved", item.optString("previous_protection_client_id").isNotBlank() ||
                        item.has("native_stop_replacement"))
                    .put("native_stop_replacement", publicNativeStopReplacement(item.optJSONObject("native_stop_replacement")))
                    .put("last_native_stop_replacement", publicNativeStopReplacement(item.optJSONObject("last_native_stop_replacement")))
                    .put("last_native_stop_failure", item.optJSONObject("last_native_stop_failure") ?: JSONObject.NULL)
                    .put("native_replacement_protection", item.optJSONObject("native_replacement_protection") ?: JSONObject.NULL)
                    .put("trail_error", safeDiagnosticText(item.optString("trail_error")).take(1000))
                    .put("risk_reservation_adjusted_at_ms", item.opt("risk_reservation_adjusted_at_ms") ?: JSONObject.NULL)
                    .put("risk_reservation_adjustment_reason", item.opt("risk_reservation_adjustment_reason") ?: JSONObject.NULL)
            )
        }
        val presentation = separateFuturesIntentPresentation(pendingDetails)
        val readyForNewEntry = !reconciliationActive &&
            (0 until persisted.length()).none { persisted.getJSONObject(it).has("native_stop_replacement") } &&
            !hasUnresolvedTriggeringEntry(persisted) && (observedState == State.READY || observedState == State.OPEN_PROTECTED ||
            (observedState == State.ENTRY_PENDING &&
                confirmedPendingEntriesAllowConcurrentAdmission(persisted)))
        return JSONObject()
            .put("engine", "USD_M_FUTURES_TESTNET")
            .put("state", observedState.name)
            .put("ready_for_new_entry", readyForNewEntry)
            .put("blocker", if (readyForNewEntry) JSONObject.NULL else (observedBlocker ?: JSONObject.NULL))
            .put("reconciliation_running", reconciliationActive)
            .put("reconciliation_started_at_ms", reconciliationStartedAtMs)
            .put("reconciliation_finished_at_ms", reconciliationFinishedAtMs)
            .put("reconciliation_phase",reconciliationPhase)
            .put("reconciliation_phase_started_at_ms",reconciliationPhaseStartedAtMs)
            .put("external_open_orders",JSONArray(externalOrderObservationsJson))
            .put("exchange_protection_observations",JSONArray(externalProtectionObservationsJson))
            .put("verified_external_stop_management_symbols",JSONArray(externalStopManagementSymbolsJson))
            .put("verified_external_stop_management_at_ms",externalStopManagementVerifiedAtMs)
            .put("operator_supervised_positions",operatorSupervisions())
            .put("pending_intents", presentation.active.length())
            .put("pending_intent_details", presentation.active)
            .put("exit_reconciliation_details", presentation.exitReconciliation)
            .put("testnet_only", true)
            .put("position_sides", JSONArray().put("LONG").put("SHORT"))
    }


    /**
     * Operator-authorized maintenance of an existing exchange position after loss
     * of the original ENTRY journal. This is NOT restored TC2 ownership. It has
     * no authority to enter, add, reverse or market-close a position.
     *
     * The operator confirms the exact signed snapshot displayed on the device.
     * A unique live Binance full-position stop, exact quantity and entry price
     * are mandatory; no algorithmic stop is claimed from its W2PS_ prefix.
     */
    private val operatorSupervisionKey = "futures_operator_stop_supervision_v1"

    private fun operatorSupervisions(): JSONArray =
        JSONArray(executionStore.readState(operatorSupervisionKey) ?: "[]")

    private fun saveOperatorSupervisions(rows: JSONArray) {
        check(executionStore.writeState(operatorSupervisionKey, rows.toString())) {
            "Cannot durably commit operator supervision; no Binance mutation authorized"
        }
    }

    // Read only the atomically committed SQLite bytes, just like intentsSnapshot.
    // Holding the mutation monitor here froze cockpit/trade-history reads for
    // the duration of every account-wide signed network reconciliation.
    fun operatorSupervisionsSnapshot(): JSONArray = JSONArray(operatorSupervisions().toString())

    /** GET-only closure backfill. Its evidence never grants trade ownership or
     * authorizes an exchange mutation. Unknown history remains visible as unknown. */
    fun refreshOperatorClosedTradeHistory() = synchronized(lock) {
        val rows=operatorSupervisions()
        if((0 until rows.length()).none { rows.getJSONObject(it).optString("state")=="CLOSED_PENDING_EVIDENCE" })
            return@synchronized
        val adapter=adapterFactory()
        for(i in 0 until rows.length()) {
            val record=rows.getJSONObject(i)
            if(record.optString("state")!="CLOSED_PENDING_EVIDENCE") continue
            val now=clock();val attempted=record.optLong("closure_history_checked_at_ms")
            if(attempted>0L && now>=attempted && now-attempted<60_000L) continue
            record.put("closure_history_checked_at_ms",now)
            try {
                val symbol=record.getString("symbol")
                val ids=listOf(record.optString("protection_client_id"),record.optString("pending_protection_client_id"))
                    .filter { it.isNotBlank() }.distinct()
                val proofs=mutableListOf<JSONObject>()
                for(id in ids) {
                    val algo=adapter.queryAlgoOrder(symbol,id)
                    if(algo.optString("algoStatus")!="FINISHED") continue
                    val childId=algo.optString("actualOrderId")
                    if(childId.toLongOrNull()?.let { it>0L }!=true) continue
                    val child=adapter.orderById(symbol,childId)
                    val fills=adapter.userTradesByOrder(symbol,childId)
                    verifiedSupervisedStopClosure(record,algo,child,fills)?.let { proofs+=it }
                }
                check(proofs.distinctBy { it.getString("exit_order_id") }.size==1) {
                    "Биржа ещё не подтвердила полное исполнение точного SL; цена, время и PnL закрытия неизвестны"
                }
                val proof=proofs.first()
                annotateVerifiedOperatorStopClosure(record,proof)
                record.put("confirmed_closure",proof).put("state","CLOSED_EXCHANGE_CONFIRMED")
                    .put("closure_history_detail","Закрытие подтверждено точным стопом и его биржевыми исполнениями")
                    .put("last_evaluation_reason",proof.getString("exit_reason"))
                appendSupervisionHistory(record,"STOP_FILLED",proof.getLong("closed_at"),
                    "Стоп исполнен; цена "+proof.getDouble("exit_price")+"; orderId="+proof.getString("exit_order_id"))
            } catch(failure:Exception) {
                record.put("closure_history_detail",safeDiagnosticText(failure.message ?: failure.javaClass.simpleName).take(700))
            }
            saveOperatorSupervisions(rows)
        }
    }

    fun recordOperatorSupervisionObservation(
        symbol:String, status:String, reason:String, closedBarAtMs:Long=0L, proposedStop:Double?=null
    ) = synchronized(lock) {
        require(status in setOf("WAITING_FOR_FIRST_CLOSED_H1","WAITING_FOR_STRUCTURE",
            "STOP_PROPOSED","TRAILED","RECOVERED","BLOCKED"))
        val rows=operatorSupervisions()
        val record=(0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .lastOrNull { it.optString("symbol")==symbol &&
                it.optString("state") in setOf("ACTIVE","REPLACEMENT_UNRESOLVED") } ?: error("Supervision journal absent")
        // Observation fields cannot authorize an order or alter the lifecycle.
        record.put("last_evaluated_at_ms",clock()).put("last_evaluation_status",status)
            .put("last_evaluation_reason",reason.take(1000))
        // Recovery/error observations have no new candle or proposal. Retain the
        // last evaluated H1 and requested trigger instead of replacing them by 0/null.
        if(closedBarAtMs>0L) record.put("last_evaluated_closed_bar_at_ms",closedBarAtMs)
        if(closedBarAtMs>0L || proposedStop!=null) record.put("last_proposed_stop",
            proposedStop?.takeIf { it.isFinite() && it>0 } ?: JSONObject.NULL)
        saveOperatorSupervisions(rows)
    }

    /**
     * Recovery by independently signed evidence, NOT reconstruction of missing
     * strategy ownership. This method does not submit/cancel an exchange order.
     * Two matching signed observations separated by >=30 seconds are mandatory
     * before enabling a tightly scoped H1/H4 STOP-only maintenance record.
     *
     * Caller must have validated closed H1/H4 candles and TC2 stop rules.
     * Every eventual STOP replacement rechecks the exchange and holds its own
     * durable Execution Door. Entries/add-ons/reversals/market-closes stay banned.
     */
    fun enrollAutonomousRecoveredStopOnly(
        symbol:String, direction:String, observedQuantity:String,
        observedEntry:String, observedStop:String
    ):JSONObject = synchronized(lock) {
        require(symbol in allowedSymbols && direction in setOf("LONG","SHORT")) {
            "AUTONOMOUS_RECOVERY_SYMBOL_OR_DIRECTION_REJECTED"
        }
        val rejected=JSONObject().put("symbol",symbol)
            .put("mode","AUTO_VERIFIED_STOP_ONLY")
            .put("strategy_ownership_verified",false)
            .put("exchange_mutation_attempted",false)
        val current=operatorSupervisions()
        val activeRows=(0 until current.length()).mapNotNull { current.optJSONObject(it) }
            .filter { it.optString("symbol")==symbol && it.optString("state") in
                setOf("ACTIVE","REPLACEMENT_UNRESOLVED") }
        if(activeRows.isNotEmpty()) return@synchronized rejected.put("status","ALREADY_SUPERVISED")
        val local=intents()
        val latest=latestEntryIntentIndex(local,symbol)?.let { local.optJSONObject(it) }
        // A historical LONG must never be mistaken for a current SHORT owner.
        // The opposite/uncertain campaign needs separate forensic resolution.
        if(latest?.optBoolean("strategy_ownership_verified",false)==true ||
            latest!=null && latest.optString("direction")!=direction) {
            return@synchronized rejected.put("status","CAMPAIGN_IDENTITY_CONFLICT")
        }
        val qty=observedQuantity.toBigDecimalOrNull()?.abs()?.stripTrailingZeros()
        val entry=observedEntry.toBigDecimalOrNull()?.stripTrailingZeros()
        val stop=observedStop.toBigDecimalOrNull()?.stripTrailingZeros()
        if(qty==null || qty.signum()<=0 || entry==null || entry.signum()<=0 ||
            stop==null || stop.signum()<=0) return@synchronized rejected.put("status","INVALID_POSITION_OR_STOP")
        val adapter=adapterFactory()
        adapter.requireOneWayMode()
        val exchangeRows=adapter.positions()
        val matching=(0 until exchangeRows.length()).mapNotNull { exchangeRows.optJSONObject(it) }
            .filter { it.optString("symbol")==symbol &&
                it.optString("positionAmt").toBigDecimalOrNull()?.signum()!=0 }
        if(matching.size!=1) return@synchronized rejected.put("status","SIGNED_EXPOSURE_NOT_UNIQUE")
        val p=matching.single()
        val signed=p.optString("positionAmt").toBigDecimalOrNull()
            ?: return@synchronized rejected.put("status","SIGNED_QUANTITY_INVALID")
        val signedEntry=p.optString("entryPrice").toBigDecimalOrNull()
            ?: return@synchronized rejected.put("status","SIGNED_ENTRY_INVALID")
        if(p.optString("positionSide","BOTH")!="BOTH" ||
            (signed.signum()>0)!=(direction=="LONG") ||
            signed.abs().compareTo(qty)!=0 || signedEntry.compareTo(entry)!=0) {
            return@synchronized rejected.put("status","SIGNED_EXPOSURE_CHANGED")
        }
        val mark=adapter.markPrice(symbol)
        if(!mark.isFinite() || mark<=0.0) return@synchronized rejected.put("status","MARK_UNVERIFIED")
        val algos=adapter.openAlgoOrders(symbol)
        val verified=(0 until algos.length()).mapNotNull { algos.optJSONObject(it) }
            .filter { isFullPositionFuturesStop(it,symbol,direction,qty,mark) }
        if(algos.length()!=1 || verified.size!=1)
            return@synchronized rejected.put("status","STOP_NOT_UNIQUE_AND_FULL_COVERAGE")
        val protection=verified.single()
        val signedStop=protection.optString("triggerPrice").ifBlank {
            protection.optString("stopPrice")
        }.toBigDecimalOrNull()
        val stopId=protection.optString("clientAlgoId")
        if(signedStop==null || signedStop.compareTo(stop)!=0 || stopId.isBlank() ||
            protection.optString("algoId").toLongOrNull()?.let { it>0L }!=true)
            return@synchronized rejected.put("status","EXACT_SIGNED_STOP_ID_OR_LEVEL_MISMATCH")
        val standard=adapter.allOpenOrders()
        if((0 until standard.length()).mapNotNull { standard.optJSONObject(it) }
            .any { it.optString("symbol")==symbol })
            return@synchronized rejected.put("status","OTHER_OPEN_EXCHANGE_ORDERS")
        val identity=listOf(symbol,direction,qty.toPlainString(),entry.toPlainString(),
            stop.toPlainString(),stopId,protection.optString("algoId")).joinToString("|")
        val key="autonomous_stop_candidate_v1_$symbol"
        val previous=runCatching {
            executionStore.readState(key)?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        }.getOrNull()
        val now=clock()
        val seenAt=previous?.optLong("first_seen_at_ms",0L) ?: 0L
        if(previous?.optString("identity")!=identity || seenAt<=0L ||
            now<seenAt || now-seenAt>300_000L) {
            check(executionStore.writeState(key,JSONObject().put("identity",identity)
                .put("first_seen_at_ms",now).toString())) {
                "Cannot durably persist pre-adoption signed proof"
            }
            return@synchronized rejected.put("status","WAIT_SECOND_SIGNED_SNAPSHOT")
                .put("first_seen_at_ms",now)
        }
        if(!autonomousStopSecondSignedProofEligible(previous,identity,now))
            return@synchronized rejected.put("status","WAIT_30_SECONDS_FOR_INDEPENDENT_SIGNED_PROOF")
        // All analysis happened BEFORE admission. Preserve the exact signed
        // identity as a separate supervision, without manufacturing Williams ENTRY.
        val row=JSONObject().put("symbol",symbol).put("direction",direction)
            .put("quantity",qty.toPlainString()).put("entry_price",entry.toPlainString())
            .put("stop",stop.toPlainString()).put("initial_stop",stop.toPlainString())
            .put("protection_client_id",stopId).put("approved_at_ms",now)
            .put("first_signed_observed_at_ms",seenAt).put("second_signed_observed_at_ms",now)
            .put("state","ACTIVE").put("mode","AUTO_VERIFIED_STOP_ONLY")
            .put("source","SIGNED_TWO_SNAPSHOTS_CLOSED_H1_H4_TC2_STOP_REVIEW")
            .put("strategy_ownership_verified",false)
            .put("market_close_authorized",false).put("add_on_authorized",false)
            .put("reverse_authorized",false)
        ensureSupervisionHistory(row)
        current.put(row)
        saveOperatorSupervisions(current)
        check(executionStore.writeState(key,"")) {
            "Supervision persisted, but candidate cleanup failed"
        }
        rejected.put("status","AUTONOMOUS_STOP_ONLY_ACTIVATED")
            .put("signed_stop_id",stopId)
            .put("first_signed_observed_at_ms",seenAt)
            .put("second_signed_observed_at_ms",now)
    }

    fun approveOperatorStopSupervision(
        symbol: String, quantity: String, entryPrice: String, stopPrice: String,
        confirmation: String
    ): JSONObject = synchronized(lock) {
        require(confirmation == "СОПРОВОЖДАТЬ ТОЛЬКО СТОП") {
            "Подтвердите режим сопровождения без новых входов и рыночных закрытий"
        }
        require(symbol in allowedSymbols) { "Unsupported Futures symbol" }
        val requestedQuantity = quantity.toBigDecimalOrNull()
        val requestedEntry = entryPrice.toDoubleOrNull()
        val requestedStop = stopPrice.toBigDecimalOrNull()
        require(requestedQuantity != null && requestedQuantity.signum() > 0 &&
            requestedEntry != null && requestedEntry.isFinite() && requestedEntry > 0 &&
            requestedStop != null && requestedStop.signum() > 0) { "Invalid operator snapshot" }
        val rows = operatorSupervisions()
        check((0 until rows.length()).none { rows.optJSONObject(it)?.let { row -> row.optString("symbol") == symbol &&
            row.optString("state") !in setOf("CLOSED_PENDING_EVIDENCE","CLOSED_EXCHANGE_CONFIRMED") }==true }) {
            "Supervision already exists; review its current state before retrying"
        }
        val formerEntry=latestEntryIntentIndex(intents(),symbol)?.let { intents().optJSONObject(it) }
        check(formerEntry==null ||
            campaignLifecycleStatus(formerEntry) in TERMINAL_CAMPAIGN_STATUSES ||
            (formerEntry.optString("kind")=="RECOVERED_EXCHANGE_POSITION" &&
                !formerEntry.optBoolean("strategy_ownership_verified",false))) {
            "An active strategy-owned campaign already exists; operator takeover is not allowed"
        }
        val adapter = adapterFactory()
        adapter.requireOneWayMode()
        val positions = adapter.positions()
        val live = (0 until positions.length()).mapNotNull { positions.optJSONObject(it) }
            .filter { it.optString("symbol") == symbol &&
                it.optString("positionAmt").toBigDecimalOrNull()?.signum() != 0 }
        check(live.size == 1) { "Exactly one live position is required" }
        val position = live.single()
        val amount = position.optString("positionAmt").toBigDecimalOrNull()
            ?: error("Binance position quantity is unavailable")
        val direction = if (amount.signum() > 0) "LONG" else "SHORT"
        val liveEntry = position.optString("entryPrice").toDoubleOrNull()
            ?: error("Binance entry price is unavailable")
        check(position.optString("positionSide", "BOTH") == "BOTH" &&
            amount.abs().compareTo(requestedQuantity) == 0 &&
            kotlin.math.abs(liveEntry - requestedEntry) <= maxOf(1e-8, liveEntry * 1e-8)) {
            "Position changed since operator confirmation; refresh positions"
        }
        val mark = adapter.markPrice(symbol)
        check(mark.isFinite() && mark > 0) { "Binance mark unavailable" }
        val algos = adapter.openAlgoOrders(symbol)
        val qualifying = (0 until algos.length()).mapNotNull { algos.optJSONObject(it) }
            .filter { isFullPositionFuturesStop(it, symbol, direction, amount.abs(), mark) }
        check(qualifying.size == 1 && algos.length() == 1) {
            "A unique, exchange-confirmed protective stop is required; no other algo orders allowed"
        }
        val openOrders = adapter.allOpenOrders()
        check((0 until openOrders.length()).mapNotNull { openOrders.optJSONObject(it) }
            .none { it.optString("symbol") == symbol }) {
            "Other open orders on this symbol must be reconciled first"
        }
        val stop = qualifying.single()
        val liveStop = stop.optString("triggerPrice").ifBlank { stop.optString("stopPrice") }
            .toBigDecimalOrNull() ?: error("Binance stop trigger is missing")
        check(liveStop.compareTo(requestedStop) == 0) {
            "Stop changed since operator confirmation; refresh positions"
        }
        val stopClientId = stop.optString("clientAlgoId")
        check(stopClientId.isNotBlank() && stop.optString("algoId").toLongOrNull()?.let { it > 0 } == true) {
            "Stop identity not confirmed"
        }
        // A missing entry journal is NOT retroactively constructed or labeled as
        // strategy-verified. Supervision can only tighten an existing stop.
        rows.put(JSONObject()
            .put("symbol",symbol).put("direction",direction)
            .put("quantity",amount.abs().stripTrailingZeros().toPlainString())
            .put("entry_price",liveEntry.toString())
            .put("stop",liveStop.stripTrailingZeros().toPlainString())
            .put("initial_stop",liveStop.stripTrailingZeros().toPlainString())
            .put("protection_client_id",stopClientId)
            .put("approved_at_ms",clock())
            .put("state","ACTIVE")
            .put("mode","OPERATOR_APPROVED_STOP_ONLY")
            .put("strategy_ownership_verified",false)
            .put("market_close_authorized",false)
            .put("add_on_authorized",false))
        ensureSupervisionHistory(rows.getJSONObject(rows.length()-1))
        saveOperatorSupervisions(rows)
        JSONObject().put("ok",true).put("symbol",symbol)
            .put("mode","OPERATOR_APPROVED_STOP_ONLY")
            .put("strategy_ownership_verified",false)
            .put("stop",liveStop.toPlainString())
    }

    /**
     * Dedicated stop-only executor, isolated from the strategy-owned campaign
     * execution path. Called under the account-wide durable Execution Door.
     * Unknown Binance acknowledgements remain journaled and are NEVER retried.
     */
    fun trailOperatorSupervisedStop(
        symbol:String, direction:Direction, proposedStop:String, newClientId:String
    ):JSONObject = synchronized(lock) {
        require(symbol in allowedSymbols && newClientId.startsWith("W2T_") &&
            newClientId.matches(Regex("[A-Za-z0-9_./:-]{1,36}")))
        val rows = operatorSupervisions()
        val record=(0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .singleOrNull { it.optString("symbol")==symbol && it.optString("state")=="ACTIVE" }
            ?: error("No operator-approved supervision for $symbol")
        check(record.optString("state")=="ACTIVE" &&
            record.optString("direction")==direction.name &&
            record.optString("mode") in setOf("OPERATOR_APPROVED_STOP_ONLY","AUTO_VERIFIED_STOP_ONLY") &&
            !record.optBoolean("market_close_authorized",true) &&
            !record.optBoolean("add_on_authorized",true) &&
            !record.optBoolean("strategy_ownership_verified",false)) {
            "Operator supervision is suspended or has invalid ownership flags"
        }
        if(record.optString("mode")=="AUTO_VERIFIED_STOP_ONLY") {
            val first=record.optLong("first_signed_observed_at_ms",0L)
            val second=record.optLong("second_signed_observed_at_ms",0L)
            check(record.optString("source")=="SIGNED_TWO_SNAPSHOTS_CLOSED_H1_H4_TC2_STOP_REVIEW" &&
                first>0L && second>=first && second-first>=30_000L &&
                record.optLong("approved_at_ms")>=second &&
                !record.optBoolean("reverse_authorized",true)) {
                "Autonomous STOP-only supervision lacks two durable signed proofs and book analysis"
            }
        }
        val oldPrice=record.optString("stop").toBigDecimalOrNull() ?: error("Missing protected stop")
        val next=proposedStop.toBigDecimalOrNull() ?: error("Malformed requested stop")
        require(next.signum()>0 && if(direction==Direction.LONG) next>oldPrice else next<oldPrice) {
            "Operator stop may only tighten"
        }
        val adapter=adapterFactory()
        adapter.requireOneWayMode()
        fun verifyLivePosition():java.math.BigDecimal {
            val livePositions=adapter.positions()
            val matched=(0 until livePositions.length()).mapNotNull { livePositions.optJSONObject(it) }
                .filter { it.optString("symbol")==symbol &&
                    it.optString("positionAmt").toBigDecimalOrNull()?.signum()!=0 }
            check(matched.size==1) { "Exchange position no longer uniquely identified" }
            val position=matched.single()
            val signed=position.optString("positionAmt").toBigDecimalOrNull() ?: error("Invalid position qty")
            val expected=record.optString("quantity").toBigDecimalOrNull() ?: error("Invalid saved qty")
            val entry=position.optString("entryPrice").toDoubleOrNull() ?: error("Invalid Binance entry")
            val approvedEntry=record.optString("entry_price").toDoubleOrNull() ?: error("Invalid saved entry")
            check(position.optString("positionSide","BOTH")=="BOTH" &&
                (signed.signum()>0)==(direction==Direction.LONG) &&
                signed.abs().compareTo(expected)==0 &&
                kotlin.math.abs(entry-approvedEntry)<=maxOf(1e-8,approvedEntry*1e-8)) {
                "Quantity, side or entry changed; operator supervision suspended"
            }
            return expected
        }
        val qty=verifyLivePosition()
        val mark=adapter.markPrice(symbol)
        check(isProtectiveStopOnCorrectSide(direction.name,mark.toString(),proposedStop)) {
            "New stop would already be triggered or lies on the wrong side of mark"
        }
        val before=adapter.openAlgoOrders(symbol)
        val prior=(0 until before.length()).mapNotNull { before.optJSONObject(it) }
            .filter { isFullPositionFuturesStop(it,symbol,direction.name,qty,mark) }
        val currentId=record.optString("protection_client_id")
        require(newClientId!=currentId) { "Replacement stop must have a distinct client ID" }
        check(prior.size==1 && prior.single().optString("clientAlgoId")==currentId &&
            before.length()==1 &&
            prior.single().optString("triggerPrice").ifBlank {
                prior.single().optString("stopPrice")
            }.toBigDecimalOrNull()?.compareTo(oldPrice)==0) {
            "Original protective stop not uniquely confirmed; no Binance mutation"
        }
        fun verifyNoStandardOrders() {
            val standard=adapter.allOpenOrders()
            check((0 until standard.length()).mapNotNull { standard.optJSONObject(it) }
                .none { it.optString("symbol")==symbol }) {
                "Other open orders on supervised symbol; no stop cancellation authorized"
            }
        }
        verifyNoStandardOrders()
        verifyLivePosition()
        // A journal failure occurs BEFORE the Binance POST.
        record.put("state","REPLACEMENT_UNRESOLVED")
            .put("previous_protection_client_id",currentId)
            .put("pending_protection_client_id",newClientId)
            .put("pending_stop",next.stripTrailingZeros().toPlainString())
            .put("pending_submission_started_at_ms",clock())
            .put("pending_submission_outcome","PREPARED")
        record.remove("pending_absent_since_ms")
        saveOperatorSupervisions(rows)
        var postAcknowledged=false
        try {
            adapter.placeReplacementProtectiveStop(symbol,direction.name,next.toPlainString(),newClientId,
                qty.toPlainString())
            postAcknowledged=true
            record.put("pending_submission_outcome","ACKNOWLEDGED")
            saveOperatorSupervisions(rows)
            val created=adapter.openAlgoOrders(symbol)
            val newStop=(0 until created.length()).mapNotNull { created.optJSONObject(it) }
                .singleOrNull { it.optString("clientAlgoId")==newClientId &&
                    isFullPositionFuturesStop(it,symbol,direction.name,qty,adapter.markPrice(symbol)) &&
                    it.optString("triggerPrice").ifBlank { it.optString("stopPrice") }
                        .toBigDecimalOrNull()?.compareTo(next)==0 }
                ?: error("Replacement stop not uniquely confirmed")
            check(created.length()==2 && (0 until created.length()).all {
                created.getJSONObject(it).optString("clientAlgoId") in setOf(currentId,newClientId)
            }) { "Unexpected algo appeared during replacement; original stop retained" }
            // No old-stop cancellation if exposure changed after placement.
            verifyNoStandardOrders()
            verifyLivePosition()
            val oldAlgoId=prior.single().optString("algoId")
            check(oldAlgoId.toLongOrNull()?.let { it>0 }==true) { "Old stop lacks exact algo ID" }
            adapter.cancelAlgoOrderById(symbol,oldAlgoId)
            val final=adapter.openAlgoOrders(symbol)
            check(final.length()==1 &&
                final.optJSONObject(0)?.optString("clientAlgoId")==newClientId &&
                isFullPositionFuturesStop(final.getJSONObject(0),symbol,direction.name,qty,adapter.markPrice(symbol)) &&
                final.getJSONObject(0).optString("triggerPrice").ifBlank {
                    final.getJSONObject(0).optString("stopPrice")
                }.toBigDecimalOrNull()?.compareTo(next)==0) {
                "Stop replacement requires manual reconciliation after exchange mutation"
            }
            record.put("state","ACTIVE")
                .put("stop",next.stripTrailingZeros().toPlainString())
                .put("protection_client_id",newClientId)
                .put("last_confirmed_trail_at_ms",clock())
            ensureSupervisionHistory(record)
            appendSupervisionHistory(record,"STOP_TRAILED",clock(),"Новый SL подтверждён Binance",next.toPlainString())
            clearPendingOperatorReplacement(record)
            saveOperatorSupervisions(rows)
            JSONObject().put("ok",true).put("symbol",symbol).put("stop",next.toPlainString())
                .put("protection_client_id",newClientId)
                .put("exchange_proof","ONE_SIGNED_ACTIVE_MATCHING_STOP_ID_PRICE_SIDE_QTY")
        } catch(failure:Exception) {
            // Only the POST phase may establish a definite rejection. A failed
            // read/DELETE after an acknowledged POST cannot undo its acceptance.
            val notSubmitted=!postAcknowledged && failure is FuturesProtectionNotSubmittedException
            val rejected=!postAcknowledged && failure is FuturesHttpException &&
                failure.statusCode in 400..499 && failure.exchangeCode?.let {
                    it in -1136..-1100 || it in setOf(-2014,-2015,-2019,-2021,-2022,-2025,
                        -4001,-4002,-4003,-4004,-4005,-4013,-4014,-4023,-4130,-4137,-4142,-4164)
                }==true
            record.put("pending_submission_outcome",when {
                notSubmitted -> "NOT_SUBMITTED"
                rejected -> "REJECTED"
                postAcknowledged -> "ACKNOWLEDGED"
                else -> "UNKNOWN"
            })
            val evidence=JSONObject().put("at_ms",clock()).put("client_id",newClientId)
                .put("proposed_stop",next.toPlainString())
                .put("phase",if(postAcknowledged) "AFTER_POST_ACK" else "PREFLIGHT_OR_POST")
                .put("outcome",record.getString("pending_submission_outcome"))
                .put("type",failure.javaClass.simpleName)
                .put("message",safeDiagnosticText(failure.message ?: failure.javaClass.simpleName).take(1000))
            if(failure is FuturesHttpException) evidence.put("http_status",failure.statusCode)
                .put("exchange_code",failure.exchangeCode ?: JSONObject.NULL)
            record.put("last_replacement_failure",evidence)
            saveOperatorSupervisions(rows)
            // Keep the exact pending identity even for a rejected POST until a
            // signed read confirms the original stop and unchanged exposure.
            JSONObject().put("ok",false).put("symbol",symbol)
                .put("state","REPLACEMENT_UNRESOLVED")
                .put("blocker",failure.message ?: failure.javaClass.simpleName)
        }
    }


    /** Reconcile the exact pending identity, including legacy 4.53.2 journals.
     * A missing open-order row alone never permits another POST. Query its history;
     * uncertain absence requires a later signed query and cancellation fence.
     * A confirmed new stop is always required before deleting the ORIGINAL stop.
     */
    fun recoverOperatorSupervisedStop(symbol:String):JSONObject = synchronized(lock) {
        val rows=operatorSupervisions()
        val record=(0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .singleOrNull { it.optString("symbol")==symbol && it.optString("state")=="REPLACEMENT_UNRESOLVED" }
            ?: error("Operator supervision journal absent")
        require(record.optString("state")=="REPLACEMENT_UNRESOLVED") {
            "No uncertain stop replacement to recover"
        }
        val direction=record.getString("direction")
        val oldId=record.getString("previous_protection_client_id")
        val newId=record.getString("pending_protection_client_id")
        val oldStop=record.getString("stop").toBigDecimal()
        val newStop=record.getString("pending_stop").toBigDecimal()
        check(direction in setOf("LONG","SHORT") && oldId.isNotBlank() && newId.isNotBlank() && oldId!=newId &&
            record.optString("protection_client_id")==oldId &&
            (if(direction=="LONG") newStop>oldStop else newStop<oldStop)) {
            "Uncertain stop journal is inconsistent"
        }
        val adapter=adapterFactory()
        adapter.requireOneWayMode()
        fun verifyLivePosition() {
            val open=adapter.positions()
            val matching=(0 until open.length()).mapNotNull { open.optJSONObject(it) }
                .filter { it.optString("symbol")==symbol &&
                    (it.optString("positionAmt").toBigDecimalOrNull()?.signum() ?: 0)!=0 }
            check(matching.size==1) { "Cannot recover uncertain stop without unique live exposure" }
            val p=matching.single()
            val qty=p.getString("positionAmt").toBigDecimal()
            val recordedQty=record.getString("quantity").toBigDecimal()
            val liveEntry=p.getString("entryPrice").toDouble()
            val priorEntry=record.getString("entry_price").toDouble()
            check(p.optString("positionSide","BOTH")=="BOTH" && qty.abs().compareTo(recordedQty)==0 &&
                (qty.signum()>0)==(direction=="LONG") &&
                kotlin.math.abs(liveEntry-priorEntry)<=maxOf(1e-8,priorEntry*1e-8)) {
                "Position changed during uncertain replacement; operator resolution required"
            }
        }
        verifyLivePosition()
        val recordedQty=record.getString("quantity").toBigDecimal()
        fun verifyNoStandardOrders() {
            val standard=adapter.allOpenOrders()
            check((0 until standard.length()).mapNotNull { standard.optJSONObject(it) }
                .none { it.optString("symbol")==symbol }) {
                "Other open orders on supervised symbol; uncertain replacement remains fenced"
            }
        }
        verifyNoStandardOrders()
        val mark=adapter.markPrice(symbol)
        val algos=adapter.openAlgoOrders(symbol)
        val list=(0 until algos.length()).mapNotNull { algos.optJSONObject(it) }
        check(list.isNotEmpty() && list.size<=2 && list.all {
            it.optString("clientAlgoId") in setOf(oldId,newId) &&
                isFullPositionFuturesStop(it,symbol,direction,recordedQty,mark)
        }) { "Unexpected or invalid Futures protection; no mutation allowed" }
        val old=list.singleOrNull { it.optString("clientAlgoId")==oldId }
        if(old!=null) check(old.optString("triggerPrice").ifBlank {
            old.optString("stopPrice")
        }.toBigDecimalOrNull()?.compareTo(oldStop)==0) { "Prior stop trigger mismatch" }
        val replacement=list.singleOrNull { it.optString("clientAlgoId")==newId }
        if(replacement==null) {
            check(old!=null && list.size==1) { "Original stop is not uniquely confirmed" }
            fun queryPending():JSONObject? = try {
                adapter.queryAlgoOrder(symbol,newId)
            } catch(failure:FuturesHttpException) {
                if(failure.statusCode==400 && failure.exchangeCode == -2013) null else throw failure
            }
            fun terminalWithoutExecution(row:JSONObject):Boolean =
                row.optString("clientAlgoId")==newId &&
                row.optString("algoStatus").uppercase(Locale.US) in setOf("REJECTED","CANCELED","EXPIRED") &&
                row.has("actualOrderId") && !row.isNull("actualOrderId") &&
                row.optString("actualOrderId") in setOf("","0") &&
                row.optString("actualQty","0").toBigDecimalOrNull()?.signum()==0 &&
                row.optString("triggerPrice").ifBlank { row.optString("stopPrice") }
                    .toBigDecimalOrNull()?.compareTo(newStop)==0 &&
                isFullPositionFuturesStop(JSONObject(row.toString()).put("algoStatus","NEW"),
                    symbol,direction,recordedQty,mark)
            fun retainOriginal(proof:String):JSONObject {
                verifyNoStandardOrders()
                verifyLivePosition()
                val current=adapter.openAlgoOrders(symbol)
                val currentMark=adapter.markPrice(symbol)
                check(current.length()==1 &&
                    current.getJSONObject(0).optString("clientAlgoId")==oldId &&
                    isFullPositionFuturesStop(current.getJSONObject(0),symbol,direction,recordedQty,currentMark) &&
                    current.getJSONObject(0).optString("triggerPrice").ifBlank {
                        current.getJSONObject(0).optString("stopPrice")
                    }.toBigDecimalOrNull()?.compareTo(oldStop)==0) {
                    "Original stop changed during replacement recovery"
                }
                verifyNoStandardOrders()
                verifyLivePosition()
                // Keep the resolved identity and proof for diagnostics. Do not
                // misreport this recovery as a successfully moved stop.
                record.put("last_replacement_resolution",JSONObject()
                    .put("at_ms",clock()).put("client_id",newId)
                    .put("proposed_stop",newStop.toPlainString()).put("proof",proof)
                    .put("result","ORIGINAL_RETAINED"))
                    .put("state","ACTIVE")
                clearPendingOperatorReplacement(record)
                saveOperatorSupervisions(rows)
                return JSONObject().put("ok",true).put("symbol",symbol)
                    .put("recovery_status","ORIGINAL_RETAINED").put("stop",oldStop.toPlainString())
                    .put("reason","Исходный SL подтверждён; несостоявшаяся замена сверена с биржей; сопровождение возобновлено")
            }
            val queried=queryPending()
            if(queried!=null) {
                check(terminalWithoutExecution(queried)) {
                    "Pending stop has not been proven terminal without execution; original retained"
                }
                return@synchronized retainOriginal("SIGNED_TERMINAL_UNTRIGGERED_QUERY")
            }
            if(record.optString("pending_submission_outcome") in setOf("REJECTED","NOT_SUBMITTED")) {
                return@synchronized retainOriginal("DEFINITE_POST_REJECTION_AND_SIGNED_NOT_FOUND")
            }
            val now=clock()
            val absentSince=record.optLong("pending_absent_since_ms",0L)
            if(absentSince<=0L || now<absentSince) {
                record.put("pending_absent_since_ms",now)
                saveOperatorSupervisions(rows)
            }
            if(absentSince<=0L || now<absentSince || now-absentSince<60_000L) {
                return@synchronized JSONObject().put("ok",false)
                    .put("blocker","Биржа не находит новый SL по точному ID; повторная сверка через 60 секунд; исходный SL сохранён")
            }
            // Never blindly replay an unknown POST (including pre-upgrade journals).
            // After two separated signed not-found reads, fence ONLY the pending
            // identity; never cancel the still-confirmed original protection.
            verifyNoStandardOrders()
            verifyLivePosition()
            try {
                adapter.cancelAlgoOrder(symbol,newId)
            } catch(failure:FuturesHttpException) {
                val absent=failure.statusCode==400 && failure.exchangeCode in setOf(-2011,-2013) &&
                    (failure.message.orEmpty().contains("does not exist",ignoreCase=true) ||
                        failure.message.orEmpty().contains("Unknown order",ignoreCase=true))
                if(!absent) throw failure
            }
            val afterFence=queryPending()
            check(afterFence==null || terminalWithoutExecution(afterFence)) {
                "Pending stop cancellation not confirmed; original retained"
            }
            return@synchronized retainOriginal("SEPARATED_SIGNED_NOT_FOUND_AND_EXACT_CANCEL_FENCE")
        }
        check(replacement.optString("triggerPrice").ifBlank {
            replacement.optString("stopPrice")
        }.toBigDecimalOrNull()?.compareTo(newStop)==0) { "Replacement stop price mismatch" }
        if(old!=null) {
            val oldAlgo=old.getString("algoId")
            check(oldAlgo.toLongOrNull()?.let { it>0L }==true) { "Old stop lacks exchange ID" }
            verifyNoStandardOrders()
            verifyLivePosition()
            adapter.cancelAlgoOrderById(symbol,oldAlgo)
        }
        val after=adapter.openAlgoOrders(symbol)
        check(after.length()==1 && after.getJSONObject(0).optString("clientAlgoId")==newId &&
            isFullPositionFuturesStop(after.getJSONObject(0),symbol,direction,recordedQty,adapter.markPrice(symbol)) &&
            after.getJSONObject(0).optString("triggerPrice").ifBlank {
                after.getJSONObject(0).optString("stopPrice")
            }.toBigDecimalOrNull()?.compareTo(newStop)==0) {
            "Replacement verification incomplete; unresolved journal preserved"
        }
        record.put("stop",newStop.stripTrailingZeros().toPlainString())
            .put("protection_client_id",newId)
            .put("state","ACTIVE")
            .put("last_confirmed_trail_at_ms",clock())
        ensureSupervisionHistory(record)
        appendSupervisionHistory(record,"STOP_RECOVERED",clock(),"Подтверждение переноса SL восстановлено",newStop.toPlainString())
        clearPendingOperatorReplacement(record)
        saveOperatorSupervisions(rows)
        JSONObject().put("ok",true).put("symbol",symbol)
            .put("mode","OPERATOR_APPROVED_STOP_ONLY")
            .put("stop",newStop.toPlainString())
    }

    private fun clearPendingOperatorReplacement(record:JSONObject) {
        for(key in listOf("previous_protection_client_id","pending_protection_client_id",
            "pending_stop","pending_submission_started_at_ms","pending_submission_outcome",
            "pending_absent_since_ms")) record.remove(key)
    }

    fun saveManagementState(symbol:String, clientId:String, state:JSONObject) = synchronized(lock) {
        val all=intents()
        val owner=(0 until all.length()).mapNotNull { all.optJSONObject(it) }.singleOrNull {
            it.optString("client_id")==clientId && it.optString("symbol")==symbol && it.optString("intent_role","ENTRY")=="ENTRY"
        } ?: error("Management state has no campaign owner")
        owner.put("edition_management",JSONObject(state.toString()))
        saveIntents(all)
    }

    private fun validatePlan(plan: EntryPlan) {
        require(williamsNewExposureBlocker(plan.strategyProfile)==null) { "WILLIAMS_SOURCE_OBSERVATION_ONLY" }
        require(plan.symbol.uppercase(Locale.US) in allowedSymbols) {
            "Symbol is outside the approved Futures universe"
        }
        require(plan.strategyProfile in williamsSourceProfiles) {
            "Futures exposure requires a pinned Williams source, never the unified selector itself"
        }
        val family=plan.signalType.removePrefix("SHORT_").removePrefix("LONG_")
        require(plan.strategyProfile!=williamsNtdProfile || family in setOf("FRACTAL","AO_SAUCER","AO_ZERO_LINE","AO_TWIN_PEAKS","AC_SIGNAL","ZONE_ADD_ON","BALANCE_LINE")) { "Signal does not belong to NTD" }
        require(plan.strategyProfile!=williamsCourseProfile || family in setOf("COURSE_HUMP","COURSE_THUMB","COURSE_SQUAT")) { "Signal does not belong to Course" }
        require(plan.entryClientId.isNotBlank() || plan.reentryOwnerId.isNotBlank() || plan.strategyProfile!=williamsNtdProfile ||
            plan.signalType.removePrefix("SHORT_")=="FRACTAL") { "NTD cycle must start with an external fractal" }
        if(plan.strategyProfile==williamsNtdProfile && plan.entryClientId.isBlank())
            require(williamsNtdFirstEntryBlocker(plan.setupContext,plan.signalType,plan.reentryOwnerId)==null) {
                "NTD_SLEEP_OR_FIRST_BREAKOUT_NOT_PROVEN"
            }
        val reversal=plan.reversalOwnerId.isNotBlank() && plan.strategyProfile==williamsTc2Profile &&
            plan.executionSemantics==WILLIAMS_TC2_REVERSAL && family in setOf("REVERSAL","SUPER_AO","FRACTAL")
        require(plan.kind!=EntryKind.MARKET || reversal || ((plan.entryClientId.isNotBlank() || plan.reentryOwnerId.isNotBlank()) &&
            plan.strategyProfile==williamsNtdProfile && plan.signalType.removePrefix("SHORT_")=="ZONE_ADD_ON" &&
            plan.executionSemantics=="MARKET_ON_CLOSE")) { "Market exposure requires an owned NTD close event or TC2 confirmed-flat reversal" }
        require(plan.quantity.toBigDecimalOrNull()?.signum() == 1) { "Quantity must be positive" }
        val price = plan.entryPrice.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Entry price is invalid")
        val stop = plan.protectiveStop.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Protective stop is invalid")
        require(price.signum() > 0 && stop.signum() > 0) { "Entry and stop prices must be positive" }
        require(plan.riskPct.isFinite() && plan.riskPct > 0.0 && plan.riskPct <= 0.0025) {
            "Per-entry risk must be positive and no greater than 0.25% equity"
        }
        require(hasValidStructuralStop(plan.direction.name, plan.entryPrice, plan.protectiveStop)) {
            if (plan.direction == Direction.LONG) {
                "LONG initial structural stop must be below entry"
            } else {
                "SHORT initial structural stop must be above entry"
            }
        }
        require(plan.clientId.matches(Regex("[A-Za-z0-9_./:-]{1,36}"))) { "Invalid client order ID" }
    }

    /** Signed flat/order cleanup and the original durable book signal are both
     * rechecked before configuration and immediately before the new order POST. */
    private fun checkBookReversalDoor(plan:EntryPlan,ledger:JSONArray,
        adapter:BinanceUsdMFuturesTestnetAdapter,allowSelf:Boolean):String? {
        val info=adapter.exchangeInfo().optJSONArray("symbols") ?: return "TC2_REVERSAL_TICK_UNVERIFIED"
        val row=(0 until info.length()).mapNotNull { info.optJSONObject(it) }.singleOrNull { it.optString("symbol")==plan.symbol }
            ?: return "TC2_REVERSAL_TICK_UNVERIFIED"
        val filters=row.optJSONArray("filters") ?: return "TC2_REVERSAL_TICK_UNVERIFIED"
        val tick=(0 until filters.length()).mapNotNull { filters.optJSONObject(it) }
            .singleOrNull { it.optString("filterType")=="PRICE_FILTER" }?.optString("tickSize")?.toDoubleOrNull()
            ?: return "TC2_REVERSAL_TICK_UNVERIFIED"
        val blocker=williamsTc2ReversalEntryBlocker(ledger,plan.reversalOwnerId,plan.symbol,plan.direction.name,
            plan.signalId,plan.signalType,plan.signalBarTimeMs,plan.clientId,plan.entryPrice.toDouble(),
            plan.protectiveStop.toDouble(),clock(),adapter.contractPrice(plan.symbol),adapter.markPrice(plan.symbol),tick,allowSelf)
        if(blocker!=null) return blocker
        val (bid,ask)=adapter.bestBidAsk(plan.symbol)
        if(if(plan.direction==Direction.LONG) ask>plan.entryPrice.toDouble() else bid<plan.entryPrice.toDouble())
            return "TC2_REVERSAL_EXECUTABLE_QUOTE_OUTSIDE_RISK_BOUND"
        val positions=adapter.positions()
        for(i in 0 until positions.length()) {
            val p=positions.optJSONObject(i) ?: return "TC2_REVERSAL_LIVE_FLAT_UNVERIFIED"
            val qty=p.optString("positionAmt").toBigDecimalOrNull() ?: return "TC2_REVERSAL_LIVE_FLAT_UNVERIFIED"
            if(p.optString("symbol")==plan.symbol && qty.signum()!=0) return "TC2_REVERSAL_LIVE_FLAT_UNVERIFIED"
        }
        if(adapter.openOrders(plan.symbol).length()!=0 || adapter.openAlgoOrders(plan.symbol).length()!=0)
            return "TC2_REVERSAL_OLD_ORDERS_NOT_CLEANED_UP"
        return null
    }

    private fun recordNativeReplacementProof(owner: JSONObject, proof: JSONObject?) {
        owner.put("protection_status", if (proof == null) "SUBMISSION_UNKNOWN" else "REPLACEMENT_PENDING_PROTECTED")
            .put("native_replacement_protection", JSONObject().put("observed_at_ms", clock())
                .put("confirmed", proof != null)
                .put("stop", proof?.optString("triggerPrice") ?: JSONObject.NULL)
                .put("mode", if (proof == null) "UNKNOWN" else if (proof.optString("closePosition").equals("true", true))
                    "CLOSE_ALL" else "FIXED_QUANTITY_REDUCE_ONLY"))
    }

    private fun verifyNativeTrailExposure(adapter: BinanceUsdMFuturesTestnetAdapter, owner: JSONObject) {
        val rows = validateFuturesPositionSnapshot(adapter.positions())
        val live = (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
            it.optString("symbol") == owner.optString("symbol") && it.getString("positionAmt").toBigDecimal().signum() != 0
        }
        check(live.size == 1)
        val p = live.single()
        val amount = p.getString("positionAmt").toBigDecimal()
        val entry = p.optString("entryPrice").toDoubleOrNull()
        val expectedEntry = owner.optString("actual_entry_price").toDoubleOrNull()
        check(p.optString("positionSide", "BOTH") == "BOTH" &&
            (amount.signum() > 0) == (owner.optString("direction") == "LONG") &&
            amount.abs().compareTo(owner.getString("confirmed_position_qty").toBigDecimal()) == 0 &&
            entry != null && entry.isFinite() && expectedEntry != null && expectedEntry.isFinite() &&
            expectedEntry > 0 && kotlin.math.abs(entry - expectedEntry) <= maxOf(1e-8, expectedEntry * 1e-8)) {
            "Native STOP cleanup exposure changed"
        }
    }

    /** Old builds did not export whether the failed POST was actually sent.
     * Open-order absence is insufficient: two signed reads a minute apart,
     * an exact cancellation fence and a final signed query are required.
     */
    private fun settleLegacyNativeStop(adapter: BinanceUsdMFuturesTestnetAdapter, rows: JSONArray, owner: JSONObject): Boolean {
        val symbol = owner.getString("symbol")
        val id = owner.getString("previous_protection_client_id")
        val stop = owner.getString("previous_protective_stop")
        val observed = conditionalOrderOrNull(adapter, symbol, id)
        if (observed != null) return matchesTerminalUntriggeredFuturesProtectiveStop(
            observed, symbol, owner.getString("direction"), id, stop)
        val first = owner.optLong("legacy_stop_absent_first_at_ms", 0L)
        if (first <= 0L) {
            owner.put("legacy_stop_absent_first_at_ms", clock())
            saveIntents(rows)
            return false
        }
        if (clock() < first || clock() - first < 60_000L) return false
        verifyNativeTrailExposure(adapter, owner)
        check(findProtectiveStop(adapter.openAlgoOrders(symbol), owner.getString("direction"),
            owner.getString("protection_client_id"), owner.getString("protective_stop"), expectedSymbol = symbol) != null)
        owner.put("legacy_stop_fence_attempt_at_ms", clock()); saveIntents(rows)
        try { adapter.cancelAlgoOrder(symbol, id) }
        catch (failure: IOException) { if (!isDefinitiveBinanceOrderNotFound(failure)) throw failure }
        val after = conditionalOrderOrNull(adapter, symbol, id)
        val settled = after == null || matchesTerminalUntriggeredFuturesProtectiveStop(
            after, symbol, owner.getString("direction"), id, stop)
        if (!settled) return false
        verifyNativeTrailExposure(adapter, owner)
        check(findProtectiveStop(adapter.openAlgoOrders(symbol), owner.getString("direction"),
            owner.getString("protection_client_id"), owner.getString("protective_stop"), expectedSymbol = symbol) != null)
        owner.put("last_legacy_stop_resolution", JSONObject().put("at_ms", clock())
            .put("proof", "TWO_SIGNED_ABSENCE_READS_60S_APART_AND_EXACT_CANCEL_FENCE"))
        owner.remove("legacy_stop_absent_first_at_ms")
        return true
    }

    private fun nativeStopReplacementEngine(adapter: BinanceUsdMFuturesTestnetAdapter, rows: JSONArray) =
        FuturesNativeStopReplacement(adapter, clock, { owner ->
            check((0 until rows.length()).map { rows.getJSONObject(it) }.none {
                it.optString("entry_client_id") == owner.optString("client_id") &&
                    (it.optString("intent_role") == "ADD_ON" && exchangeOrderStatus(it) !in ENTRY_ORDER_TERMINAL_STATUSES ||
                        it.optString("intent_role") == "EXIT" && it.optString("exchange_status") != "EXIT_CONFIRMED")
            }) { "Unresolved campaign addition/exit blocks native STOP replacement" }
        }) {
            saveIntents(rows)
        }

    private fun findProtectiveStop(
        orders: JSONArray,
        direction: String,
        expectedClientId: String? = null,
        expectedStop: String? = null,
        expectedSymbol: String? = null
    ): JSONObject? {
        if (expectedClientId.isNullOrBlank() || expectedStop.isNullOrBlank()) return null
        return (0 until orders.length()).mapNotNull { orders.optJSONObject(it) }
            .firstOrNull { row ->
                matchesConfirmedFuturesProtectiveStop(
                    order = row,
                    direction = direction,
                    expectedClientId = expectedClientId,
                    expectedStop = expectedStop,
                    expectedSymbol = expectedSymbol
                )
            }
    }

    private fun isProtectiveStop(order: JSONObject, direction: String): Boolean {
        val type = order.optString("type", order.optString("orderType")).uppercase(Locale.US)
        if (type != "STOP_MARKET") return false
        if (!order.optString("closePosition").equals("true", true)) return false
        val side = order.optString("side").uppercase(Locale.US)
        if (direction == "LONG" && side != "SELL") return false
        if (direction == "SHORT" && side != "BUY") return false
        // Do not default a missing status to NEW. Only an explicitly active
        // exchange status can prove that the stop is currently resting on Binance.
        val status = order.optString("algoStatus").ifBlank { order.optString("status") }
        return isActiveFuturesProtectionStatus(status)
    }

    private fun stableProtectionId(symbol: String, entryClientId: String): String =
        ("W2PS_" + symbol + "_" + entryClientId.takeLast(10))
            .take(36)

    private fun intents(): JSONArray {
        val raw = executionStore.readState(intentsKey) ?: "[]"
        return try {
            JSONArray(raw)
        } catch (failure: Exception) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "Persisted Futures intents are corrupt; reconciliation is mandatory"
            throw IllegalStateException(lastBlocker, failure)
        }
    }

    private fun saveIntents(array: JSONArray) {
        check(executionStore.writeState(intentsKey, array.toString())) {
            "SQLite commit failed while persisting Futures execution intents; order mutation is blocked"
        }
        // Observability must never turn a committed exchange intent into a retry.
        runCatching { observationSink?.invoke(JSONArray(array.toString())) }
    }
}
