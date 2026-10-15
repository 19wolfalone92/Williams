package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.util.Locale


private val ENTRY_ORDER_ACTIVE_STATUSES = setOf(
    "NEW", "PENDING", "WORKING", "PARTIALLY_FILLED", "SUBMISSION_UNKNOWN",
    "ACCEPTED", "TRIGGERED", "CANCEL_SUBMISSION_UNKNOWN"
)
private val ENTRY_ORDER_TERMINAL_STATUSES = setOf(
    "FILLED", "FINISHED", "EXPIRED", "EXPIRED_IN_MATCH", "CANCELED", "CANCELLED", "REJECTED"
)
private val ACTIVE_CAMPAIGN_STATUSES = setOf(
    "ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "POSITION_OPEN", "OPEN_PROTECTED", "EXIT_PENDING"
)
private val TERMINAL_CAMPAIGN_STATUSES = setOf("CLOSED_OR_STOPPED")

/** Keeps exchange order completion separate from campaign lifecycle. */
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
    protectiveStop: String
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
    protectionClientId: String
): Boolean =
    status.uppercase(Locale.US) == "OPEN_PROTECTED" &&
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
    private val executionStore: FuturesExecutionStore,
    private val adapterFactory: () -> BinanceUsdMFuturesTestnetAdapter
) {
    enum class Direction { LONG, SHORT }
    enum class EntryKind { STOP, TAKE_PROFIT, LIMIT }
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
        val riskPct: Double
    )

    @Volatile var state: State = State.UNINITIALIZED
        private set
    @Volatile var lastBlocker: String? = "startup_reconciliation_required"
        private set

    private val lock = Any()
    private val intentsKey = "futures_campaign_intents_v2"
    private val allowedSymbols = BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS
    private val terminalStatuses = setOf(
        "FILLED", "FINISHED", "EXPIRED", "EXPIRED_IN_MATCH", "CANCELED",
        "CANCELLED", "REJECTED", "CLOSED_OR_STOPPED", "EXIT_CONFIRMED"
    )
    private val activeStatuses = ENTRY_ORDER_ACTIVE_STATUSES

    fun accountSnapshot(): JSONObject = adapterFactory().account()
    fun positionsSnapshot(): JSONArray = adapterFactory().positions()
    fun intentsSnapshot(): JSONArray = JSONArray(intents().toString())

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
            protectionClientId = latest.optString("protection_client_id")
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

    fun reconcile(): JSONObject = synchronized(lock) {
        state = State.RECONCILE_REQUIRED
        lastBlocker = "exchange_reconciliation_in_progress"
        val adapter = adapterFactory()
        try {
            adapter.serverTime()
            adapter.requireOneWayMode()
            adapter.account() // verifies authenticated Futures account access
            val exchangePositions = adapter.positions()
            val openBySymbol = linkedMapOf<String, JSONObject>()
            for (i in 0 until exchangePositions.length()) {
                val row = exchangePositions.optJSONObject(i) ?: continue
                val symbol = row.optString("symbol").uppercase(Locale.US)
                val amount = row.optString("positionAmt").toDoubleOrNull()
                    ?: throw IllegalStateException("Binance returned a malformed position amount for $symbol")
                if (amount == 0.0) continue
                if (symbol !in allowedSymbols) {
                    throw IllegalStateException(
                        "Unmanaged live Futures position $symbol detected outside the approved ten-symbol universe; manual reconciliation required"
                    )
                }
                openBySymbol[symbol] = row
            }

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
                if (role == "ENTRY" && orderStatus in activeStatuses &&
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
                }
            }
            // A manual or orphaned order can create exposure without any local intent.
            // Block startup if any account-wide open order cannot be tied to this bot's
            // durable intent journal; never silently adopt or duplicate it.
            val allOpenOrders = adapter.allOpenOrders()
            for (i in 0 until allOpenOrders.length()) {
                val order = allOpenOrders.optJSONObject(i)
                    ?: throw IllegalStateException("Malformed account-wide Futures open-order row")
                val symbol = order.optString("symbol").uppercase(Locale.US)
                val clientId = order.optString("clientOrderId")
                if (symbol !in allowedSymbols ||
                    (!isOwnedClientOrderForSymbol(symbol, clientId, activeEntryClientIdsBySymbol) &&
                        !isOwnedClientOrderForSymbol(symbol, clientId, activeExitClientIdsBySymbol))
                ) {
                    throw IllegalStateException(
                        "Unmanaged Futures open order detected ($symbol/$clientId); cancel/reconcile manually before trading"
                    )
                }
            }
            val allOpenAlgoOrders = adapter.allOpenAlgoOrders()
            for (i in 0 until allOpenAlgoOrders.length()) {
                val order = allOpenAlgoOrders.optJSONObject(i)
                    ?: throw IllegalStateException("Malformed account-wide Futures algo-order row")
                val symbol = order.optString("symbol").uppercase(Locale.US)
                val clientId = order.optString("clientAlgoId")
                if (symbol !in allowedSymbols ||
                    (!isOwnedClientOrderForSymbol(symbol, clientId, activeEntryClientIdsBySymbol) &&
                        !isOwnedClientOrderForSymbol(symbol, clientId, activeProtectionClientIdsBySymbol))
                ) {
                    throw IllegalStateException(
                        "Unmanaged Futures conditional/algo order detected ($symbol/$clientId); reconcile manually before trading"
                    )
                }
            }
            var hasPendingExit = false
            // Resolve persisted entry and exit intents against Binance. Older completed
            // campaigns on a symbol must not be mistaken for the campaign now in market.
            for (i in 0 until persisted.length()) {
                val intent = persisted.optJSONObject(i)
                    ?: throw IllegalStateException("Persisted Futures intent is malformed")
                val symbol = intent.optString("symbol").uppercase(Locale.US)
                val clientId = intent.optString("client_id")
                val role = intent.optString("intent_role", "ENTRY")
                if (symbol !in allowedSymbols || clientId.isBlank()) {
                    throw IllegalStateException("Persisted Futures intent is malformed")
                }
                val position = openBySymbol[symbol]
                val prior = campaignLifecycleStatus(intent)
                val priorOrderStatus = exchangeOrderStatus(intent)
                if (role == "EXIT") {
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
                                .put("confirmed_flat_at_ms", System.currentTimeMillis())
                        } else {
                            throw IllegalStateException(
                                "Exit intent " + clientId + " is not queryable while " + symbol +
                                    " still has a position or matching open order; no retry permitted"
                            )
                        }
                    } else {
                        val exitStatus = order.optString("status").uppercase(Locale.US)
                        if (exitStatus == "FILLED") {
                            if (position == null) {
                                intent.put("exchange_status", "EXIT_CONFIRMED")
                                    .put("order_status", "FILLED")
                                    .put("exchange_order_id", order.optString("orderId"))
                                    .put("confirmed_flat_at_ms", System.currentTimeMillis())
                            } else {
                                // The order is terminal, but the account still reports exposure.
                                // Never resend that client id; reconcile the remaining position
                                // and its protection as a separate campaign-state decision.
                                intent.put("exchange_status", "EXIT_TERMINAL_POSITION_OPEN")
                                    .put("order_status", "FILLED")
                                    .put("exchange_order_id", order.optString("orderId"))
                                    .put("last_reconciled_ms", System.currentTimeMillis())
                            }
                        } else if (exitStatus in setOf("CANCELED", "CANCELLED", "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH")) {
                            intent.put("exchange_status", if (position == null) "EXIT_CONFIRMED" else "EXIT_TERMINAL_POSITION_OPEN")
                                .put("order_status", exitStatus)
                                .put("exchange_order_id", order.optString("orderId"))
                                .put("last_reconciled_ms", System.currentTimeMillis())
                        } else {
                            hasPendingExit = true
                            intent.put("exchange_status", exitStatus)
                                .put("exchange_order_id", order.optString("orderId"))
                                .put("last_reconciled_ms", System.currentTimeMillis())
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
                            it.optString("clientOrderId") == clientId
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
                                        .put("cancel_remainder_requested_at_ms", System.currentTimeMillis())
                                    saveIntents(persisted)
                                    adapter.cancelOrder(symbol, clientId)
                                }
                                val confirmed = adapter.order(symbol, clientId)
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
                                        .put("cancel_remainder_requested_at_ms", System.currentTimeMillis())
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
                            .put("remainder_cancel_confirmed_at_ms", System.currentTimeMillis())
                            .put("last_reconciled_ms", System.currentTimeMillis())

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
                                .put("closed_observed_at_ms", System.currentTimeMillis())
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
                        .put("last_reconciled_ms", System.currentTimeMillis())
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
                        .put("closed_observed_at_ms", System.currentTimeMillis())
                    continue
                }
                val kind = intent.optString("kind")
                val order = when (kind) {
                    EntryKind.LIMIT.name -> standardOrderOrNull(adapter, symbol, clientId)
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
                            .put("closed_observed_at_ms", System.currentTimeMillis())
                            .put("last_reconciled_ms", System.currentTimeMillis())
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
                    .put("last_reconciled_ms", System.currentTimeMillis())
                when {
                    exchangeStatus in setOf("FILLED", "FINISHED") -> {
                        if (campaignLifecycleStatus(intent) != "FILL_CONFIRMATION_PENDING" ||
                            intent.optLong("fill_observed_at_ms", 0L) <= 0L
                        ) {
                            intent.put("fill_observed_at_ms", System.currentTimeMillis())
                        }
                        intent.put("campaign_status", "FILL_CONFIRMATION_PENDING")
                    }
                    exchangeStatus in setOf("CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED") ->
                        intent.put("campaign_status", "CLOSED_OR_STOPPED")
                            .put("closed_observed_at_ms", System.currentTimeMillis())
                    exchangeStatus in activeStatuses -> intent.put("campaign_status", "ENTRY_PENDING")
                    else -> throw IllegalStateException("Entry $clientId has unsupported exchange status $exchangeStatus")
                }
            }

            var anyUnprotected = false
            val protectiveOrdersBySymbol = mutableMapOf<String, JSONArray>()
            for ((symbol, position) in openBySymbol) {
                val amount = position.optString("positionAmt").toDoubleOrNull() ?: 0.0
                val direction = if (amount > 0.0) "LONG" else "SHORT"
                val algos = protectiveOrdersBySymbol.getOrPut(symbol) { adapter.openAlgoOrders(symbol) }
                val latestIntentIndex = latestEntryIndexBySymbol[symbol]
                val matchingIntent = latestIntentIndex?.let { persisted.optJSONObject(it) }
                    ?.takeIf {
                        it.optString("intent_role", "ENTRY") == "ENTRY" &&
                            it.optString("symbol").uppercase(Locale.US) == symbol &&
                            it.optString("direction") == direction &&
                            run {
                                val entry = it.optString("entry_price").toBigDecimalOrNull()
                                val stop = it.optString("protective_stop").toBigDecimalOrNull()
                                // Historical structural invalidation is entry-relative only at
                                // initial placement. A trailing profit stop may cross entry.
                                entry != null && stop != null && entry.signum() > 0 && stop.signum() > 0
                            }
                    }
                val currentProtectionId = matchingIntent?.optString("protection_client_id").orEmpty()
                val previousProtectionId = matchingIntent?.optString("previous_protection_client_id").orEmpty()
                val knownProtectionIds = setOf(currentProtectionId, previousProtectionId).filter { it.isNotBlank() }.toSet()
                val liveMark = if (matchingIntent != null) {
                    runCatching { adapter.markPrice(symbol) }.getOrNull()
                } else null
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
                    lastBlocker = "$symbol neither the current stop nor its tracked predecessor is confirmed on the protective side of Binance mark price; entries and automatic closure are blocked pending reconciliation"
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
                        matchingIntent?.optString("protective_stop")
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
                        algos, direction, previousProtectionId, previousStopPrice
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
                            .put("trail_recovery_at_ms", System.currentTimeMillis())
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
                        runCatching { adapter.queryAlgoOrder(symbol, protectionClientId) }
                        val refreshed = adapter.openAlgoOrders(symbol)
                        protective = findProtectiveStop(
                            refreshed, direction, protectionClientId,
                            matchingIntent.optString("protective_stop")
                        )
                        if (protective == null) {
                            anyUnprotected = true
                            lastBlocker = "$symbol protective-stop outcome is ambiguous; no retry performed"
                            matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")
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
                            .put("protection_attempt_at_ms", System.currentTimeMillis())
                        saveIntents(persisted)
                        try {
                            adapter.placeProtectiveStop(
                                symbol, direction,
                                matchingIntent.optString("protective_stop"),
                                protectionClientId
                            )
                        } catch (failure: Exception) {
                            anyUnprotected = true
                            lastBlocker = "$symbol protective-stop placement ambiguous: ${failure.message ?: failure.javaClass.simpleName}"
                            continue
                        }
                        val refreshed = adapter.openAlgoOrders(symbol)
                        protective = findProtectiveStop(
                            refreshed, direction, protectionClientId,
                            matchingIntent.optString("protective_stop")
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
                    matchingIntent.put("campaign_status", "OPEN_PROTECTED")
                        .put("protection_status", "PROTECTED")
                        .put("protection_client_id", confirmedClientId)
                        .put("protective_stop", confirmedStop)
                        .put("last_reconciled_ms", System.currentTimeMillis())

                    // A crash during stop replacement may leave both the new stop and
                    // its previously persisted predecessor open. Remove only the known
                    // predecessor after confirming the replacement; unknown extra stops
                    // require reconciliation instead of speculative cancellation.
                    val previousClientId = matchingIntent.optString("previous_protection_client_id")
                    var unmanagedExtraStop = false
                    val currentAlgos = adapter.openAlgoOrders(symbol)
                    for (j in 0 until currentAlgos.length()) {
                        val old = currentAlgos.optJSONObject(j) ?: continue
                        if (!isProtectiveStop(old, direction)) continue
                        val oldClientId = old.optString("clientAlgoId")
                        if (oldClientId == confirmedClientId) continue
                        if (previousClientId.isNotBlank() && oldClientId == previousClientId) {
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
                            afterCleanup, direction, confirmedClientId, confirmedStop
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
                        matchingIntent.remove("previous_protection_client_id")
                        matchingIntent.remove("previous_protective_stop")
                    }
                }
            }

            // Once Binance reports the symbol flat, prior position intents are terminal.
            for (i in 0 until persisted.length()) {
                val intent = persisted.optJSONObject(i) ?: continue
                if (intent.optString("intent_role", "ENTRY") != "ENTRY") continue
                val symbol = intent.optString("symbol").uppercase(Locale.US)
                val status = campaignLifecycleStatus(intent)
                val unresolvedExitForSymbol = (0 until persisted.length()).any { j ->
                    val exit = persisted.optJSONObject(j) ?: return@any true
                    exit.optString("intent_role") == "EXIT" &&
                        exit.optString("symbol").equals(symbol, true) &&
                        exit.optString("exchange_status").uppercase(Locale.US) != "EXIT_CONFIRMED"
                }
                val nowMs = System.currentTimeMillis()
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
                    // Commit terminal campaign ownership before the first network
                    // cleanup call. If the process dies or cancellation is ambiguous,
                    // recovery will never revive this old campaign as a live one.
                    saveIntents(persisted)
                    // A stop-triggered or manually closed position must not leave a
                    // closePosition algo armed to affect a future campaign on this symbol.
                    val knownProtectionIds = listOf(
                        intent.optString("protection_client_id"),
                        intent.optString("previous_protection_client_id")
                    ).filter { it.isNotBlank() }.toSet()
                    if (knownProtectionIds.isNotEmpty()) {
                        val algos = adapter.openAlgoOrders(symbol)
                        for (j in 0 until algos.length()) {
                            val order = algos.optJSONObject(j) ?: continue
                            val clientAlgoId = order.optString("clientAlgoId")
                            if (clientAlgoId !in knownProtectionIds) continue
                            val algoId = order.optString("algoId")
                            if (algoId.isNotBlank()) adapter.cancelAlgoOrderById(symbol, algoId)
                            else if (clientAlgoId.isNotBlank()) adapter.cancelAlgoOrder(symbol, clientAlgoId)
                            else throw IOException("Cannot identify orphaned protective Futures algo for $symbol")
                        }
                        val remaining = adapter.openAlgoOrders(symbol)
                        if ((0 until remaining.length()).any { j ->
                            remaining.optJSONObject(j)?.optString("clientAlgoId")?.let { it in knownProtectionIds } == true
                        }) {
                            throw IOException("Orphaned protective Futures algo remains open for flat $symbol")
                        }
                        intent.remove("previous_protection_client_id")
                        intent.remove("previous_protective_stop")
                    }
                }
            }
            saveIntents(persisted)

            val hasPendingEntry = (0 until persisted.length()).any { index ->
                val intent = persisted.optJSONObject(index) ?: return@any true
                val symbol = intent.optString("symbol").uppercase(Locale.US)
                intent.optString("intent_role", "ENTRY") == "ENTRY" &&
                    symbol !in openBySymbol &&
                    campaignLifecycleStatus(intent) in setOf("ENTRY_PENDING", "FILL_CONFIRMATION_PENDING")
            }
            state = when {
                anyUnprotected -> State.POSITION_UNPROTECTED
                hasPendingExit -> State.EXIT_PENDING
                hasPendingEntry -> State.ENTRY_PENDING
                openBySymbol.isNotEmpty() -> State.OPEN_PROTECTED
                else -> State.READY
            }
            if (!anyUnprotected) {
                lastBlocker = when (state) {
                    State.ENTRY_PENDING -> "exchange_confirmed_pending_entry_or_fill_not_yet_resolved"
                    State.EXIT_PENDING -> "exit_order_status_pending_exchange_confirmation"
                    else -> null
                }
            }
            JSONObject()
                .put("ok", !anyUnprotected && !hasPendingExit)
                .put("engine", "USD_M_FUTURES_TESTNET")
                .put("state", state.name)
                .put("positions_checked", exchangePositions.length())
                .put("open_positions", openBySymbol.size)
                .put("pending_intents_checked", persisted.length())
                .put("active_pending_entry", hasPendingEntry)
                .put("active_pending_exit", hasPendingExit)
                .put("blocker", lastBlocker ?: JSONObject.NULL)
        } catch (failure: Exception) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = failure.message ?: failure.javaClass.simpleName
            JSONObject()
                .put("ok", false)
                .put("engine", "USD_M_FUTURES_TESTNET")
                .put("state", state.name)
                .put("blocker", lastBlocker)
        }
    }

    /** Persist the entry request before any account/order mutation. */
    fun submitPendingEntry(plan: EntryPlan): JSONObject = synchronized(lock) {
        check(state == State.READY || state == State.OPEN_PROTECTED) {
            "Futures execution blocked: ${lastBlocker ?: state.name}"
        }
        validatePlan(plan)
        val currentIntents = intents()
        // This build permits one campaign across the entire Futures account, not one
        // pending entry per symbol. Unknown or protected intents reserve that slot.
        val reservedStatuses = activeStatuses + setOf(
            "OPEN_PROTECTED", "POSITION_OPEN", "SUBMISSION_UNKNOWN", "EXIT_PENDING"
        )
        check((0 until currentIntents.length()).none { i ->
            val row = currentIntents.optJSONObject(i)
                ?: throw IllegalStateException("Persisted Futures intent is malformed")
            isEntryCampaignReserved(row) ||
                row.optString("exchange_status").uppercase(Locale.US) in reservedStatuses ||
                row.optString("campaign_status").uppercase(Locale.US) in ACTIVE_CAMPAIGN_STATUSES
        }) { "A Futures campaign is already active or unresolved; one-campaign limit blocks a second entry" }

        // Also inspect exchange authority before writing an entry intent. Any live
        // position (including manual/unmanaged exposure) blocks a fresh campaign.
        val preflightAdapter = adapterFactory()
        val contractBlocker = futuresContractRuleBlocker(
            preflightAdapter.exchangeInfo(),
            plan.symbol,
            plan.quantity,
            plan.entryPrice,
            plan.protectiveStop
        )
        check(contractBlocker == null) {
            contractBlocker ?: "Binance USD-M contract rules rejected the entry plan"
        }
        val livePositions = preflightAdapter.positions()
        check((0 until livePositions.length()).none { i ->
            val row = livePositions.optJSONObject(i)
                ?: throw IllegalStateException("Binance returned a malformed position row")
            val amount = row.optString("positionAmt").toBigDecimalOrNull()
                ?: throw IllegalStateException("Binance returned a malformed position amount")
            amount.signum() != 0
        }) { "A live Futures position exists on Binance; reconcile/protect it before opening another campaign" }

        val intent = JSONObject()
            .put("intent_role", "ENTRY")
            .put("symbol", plan.symbol)
            .put("direction", plan.direction.name)
            .put("kind", plan.kind.name)
            .put("quantity", plan.quantity)
            .put("entry_price", plan.entryPrice)
            .put("protective_stop", plan.protectiveStop)
            .put("risk_pct", plan.riskPct)
            .put("client_id", plan.clientId)
            .put("exchange_status", "SUBMISSION_UNKNOWN")
            .put("order_status", "SUBMISSION_UNKNOWN")
            .put("campaign_status", "ENTRY_PENDING")
            .put("protection_status", "NOT_SUBMITTED")
            .put("created_at_ms", System.currentTimeMillis())
        currentIntents.put(intent)
        saveIntents(currentIntents)
        state = State.ENTRY_PENDING
        lastBlocker = "pending_entry_submission_in_progress"

        val adapter = adapterFactory()
        try {
            adapter.serverTime()
            adapter.requireOneWayMode()
            adapter.ensureIsolatedTwoX(plan.symbol)
            val result = when (plan.kind) {
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
                .put("submitted_at_ms", System.currentTimeMillis())
            saveIntents(currentIntents)
            lastBlocker = "entry pending exchange fill and confirmed protective stop"
            JSONObject()
                .put("accepted", true)
                .put("engine", "USD_M_FUTURES_TESTNET")
                .put("state", state.name)
                .put("direction", plan.direction.name)
                .put("entry_kind", plan.kind.name)
                .put("order", result)
                .put("protection", "PENDING_FILL")
        } catch (failure: Exception) {
            intent.put("exchange_status", "SUBMISSION_UNKNOWN")
                .put("order_status", "SUBMISSION_UNKNOWN")
                .put("campaign_status", "ENTRY_PENDING")
                .put("error", failure.message ?: failure.javaClass.simpleName)
                .put("failed_at_ms", System.currentTimeMillis())
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
    fun cancelPendingEntries(reason: String): JSONObject = synchronized(lock) {
        val adapter = adapterFactory()
        val all = intents()
        val openPositions = adapter.positions()
        val openSymbols = mutableSetOf<String>()
        for (i in 0 until openPositions.length()) {
            val row = openPositions.optJSONObject(i) ?: continue
            if (kotlin.math.abs(row.optString("positionAmt").toDoubleOrNull() ?: 0.0) > 0.0) {
                openSymbols.add(row.optString("symbol").uppercase(Locale.US))
            }
        }
        try {
            val failures = mutableListOf<String>()
            for (i in 0 until all.length()) {
                val intent = all.optJSONObject(i) ?: continue
                if (intent.optString("intent_role", "ENTRY") != "ENTRY") continue
                val symbol = intent.optString("symbol")
                val status = exchangeOrderStatus(intent)
                if (status !in activeStatuses) continue
                val clientId = intent.optString("client_id")
                try {
                if (status == "CANCEL_SUBMISSION_UNKNOWN") {
                    // A previous cancellation may have reached Binance before process death.
                    // Query the exchange; never issue the same cancel again blindly.
                    val observed = when (intent.optString("kind")) {
                        EntryKind.LIMIT.name -> runCatching { adapter.order(symbol, clientId) }.getOrNull()
                        EntryKind.STOP.name, EntryKind.TAKE_PROFIT.name ->
                            runCatching { adapter.queryAlgoOrder(symbol, clientId) }.getOrNull()
                        else -> throw IllegalStateException("Unknown pending-entry type; cannot reconcile cancellation")
                    }
                    val observedStatus = observed
                        ?.let { it.optString("status", it.optString("algoStatus", "")) }
                        ?.uppercase(Locale.US).orEmpty()
                    if (observedStatus in setOf("CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED", "FINISHED")) {
                        intent.put("exchange_status", observedStatus)
                            .put("order_status", observedStatus)
                            .put("campaign_status", if (symbol.uppercase(Locale.US) in openSymbols) "POSITION_OPEN" else "CLOSED_OR_STOPPED")
                            .put("cancel_reconciled_status", observedStatus)
                            .put("canceled_at_ms", System.currentTimeMillis())
                        saveIntents(all)
                        continue
                    }
                    throw IOException("Cancellation outcome for $clientId remains ambiguous; no repeated cancel sent")
                }
                intent.put("exchange_status", "CANCEL_SUBMISSION_UNKNOWN")
                    .put("order_status", "CANCEL_SUBMISSION_UNKNOWN")
                    .put("cancel_reason", reason)
                    .put("cancel_attempt_at_ms", System.currentTimeMillis())
                saveIntents(all)
                val result = when (intent.optString("kind")) {
                    EntryKind.LIMIT.name -> adapter.cancelOrder(symbol, clientId)
                    EntryKind.STOP.name, EntryKind.TAKE_PROFIT.name -> adapter.cancelAlgoOrder(symbol, clientId)
                    else -> throw IllegalStateException("Unknown pending-entry type; cannot cancel safely")
                }
                intent.put("exchange_status", "CANCELED")
                    .put("order_status", "CANCELED")
                    .put("campaign_status", if (symbol.uppercase(Locale.US) in openSymbols) "POSITION_OPEN" else "CLOSED_OR_STOPPED")
                    .put("cancel_result", result.toString())
                    .put("canceled_at_ms", System.currentTimeMillis())
                saveIntents(all)
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
        clientAlgoId: String
    ): JSONObject = synchronized(lock) {
        check(state == State.OPEN_PROTECTED || state == State.ENTRY_PENDING) {
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
        val before = adapter.openAlgoOrders(symbol)
        val currentProtectionId = intent.optString("protection_client_id")
        val confirmedOld = findProtectiveStop(before, direction.name, currentProtectionId, oldStop.toPlainString())
        val previousId = intent.optString("previous_protection_client_id")
        val previous = (0 until before.length()).mapNotNull { before.optJSONObject(it) }
            .filter { isProtectiveStop(it, direction.name) }
        val unknownStops = previous.filter {
            it.optString("clientAlgoId") != currentProtectionId &&
                (previousId.isBlank() || it.optString("clientAlgoId") != previousId)
        }
        if (confirmedOld == null || unknownStops.isNotEmpty()) {
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
        intent.put("previous_protective_stop", oldStop.toPlainString())
            .put("previous_protection_client_id", intent.optString("protection_client_id"))
            .put("protective_stop", proposed.stripTrailingZeros().toPlainString())
            .put("protection_client_id", clientAlgoId)
            .put("protection_status", "SUBMISSION_UNKNOWN")
            .put("trail_attempt_at_ms", System.currentTimeMillis())
        saveIntents(persisted)

        try {
            adapter.placeProtectiveStop(symbol, direction.name, proposed.toPlainString(), clientAlgoId)
            val afterCreate = adapter.openAlgoOrders(symbol)
            val confirmed = findProtectiveStop(afterCreate, direction.name, clientAlgoId, proposed.toPlainString())
                ?: throw IOException("New trailing stop id and exact trigger were not confirmed by exchange")
            for (old in previous) {
                if (old.optString("clientAlgoId") == clientAlgoId) continue
                val algoId = old.optString("algoId")
                val oldClientId = old.optString("clientAlgoId")
                if (algoId.isNotBlank()) adapter.cancelAlgoOrderById(symbol, algoId)
                else if (oldClientId.isNotBlank()) adapter.cancelAlgoOrder(symbol, oldClientId)
                else throw IOException("Cannot safely identify replaced protective stop")
            }
            val finalOrders = adapter.openAlgoOrders(symbol)
            val finalStop = findProtectiveStop(finalOrders, direction.name, clientAlgoId, proposed.toPlainString())
                ?: throw IOException("Replacement stop is no longer open at the requested trigger")
            val untrackedRemainder = (0 until finalOrders.length()).mapNotNull { finalOrders.optJSONObject(it) }
                .any { isProtectiveStop(it, direction.name) && it.optString("clientAlgoId") != clientAlgoId }
            if (untrackedRemainder) {
                throw IOException("A predecessor or unrecognized protective stop remains after trailing replacement")
            }
            intent.put("protection_status", "PROTECTED")
                .put("protective_stop", finalStop.optString("triggerPrice", proposed.toPlainString()))
                .put("trailing_at_ms", System.currentTimeMillis())
            intent.remove("previous_protection_client_id")
            intent.remove("previous_protective_stop")
            saveIntents(persisted)
            JSONObject().put("ok", true).put("state", state.name).put("stop", finalStop)
        } catch (failure: Exception) {
            val failureText = failure.message ?: failure.javaClass.simpleName
            val refreshed = runCatching { adapter.openAlgoOrders(symbol) }.getOrNull()
            val visibleStops = refreshed?.let { orders ->
                (0 until orders.length()).mapNotNull { orders.optJSONObject(it) }
                    .filter { isProtectiveStop(it, direction.name) }
            }.orEmpty()
            val oldStopStillConfirmed = refreshed?.let {
                findProtectiveStop(it, direction.name, currentProtectionId, oldStop.toPlainString())
            }
            val replacementConfirmed = refreshed?.let {
                findProtectiveStop(it, direction.name, clientAlgoId, proposed.toPlainString())
            }
            val noUnownedStop = visibleStops.all { it.optString("clientAlgoId") == currentProtectionId }
            if (oldStopStillConfirmed != null && replacementConfirmed == null && noUnownedStop) {
                // If the replacement was not found, keep the last confirmed stop as
                // the campaign's current protection. Track the uncertain new id as a
                // possible predecessor so a delayed Binance acknowledgement cannot
                // turn it into an unowned order on the next reconciliation.
                intent.put("protective_stop", oldStop.toPlainString())
                    .put("protection_client_id", currentProtectionId)
                    .put("previous_protective_stop", proposed.toPlainString())
                    .put("previous_protection_client_id", clientAlgoId)
                    .put("protection_status", "PROTECTED")
                    .put("campaign_status", "OPEN_PROTECTED")
                    .put("trail_error", failureText)
                saveIntents(persisted)
                state = State.RECONCILE_REQUIRED
                lastBlocker = "$symbol stop trail was not confirmed; prior stop is still exchange-confirmed and the uncertain replacement remains tracked pending reconciliation"
                return@synchronized JSONObject().put("ok", false).put("state", state.name)
                    .put("blocker", lastBlocker).put("retained_stop", oldStop.toPlainString())
            }
            state = State.RECONCILE_REQUIRED
            lastBlocker = "ambiguous_trailing_stop_replacement_manual_reconciliation_required"
            intent.put("protection_status", "SUBMISSION_UNKNOWN")
                .put("trail_error", failureText)
            saveIntents(persisted)
            JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
                .put("error", failureText)
        }
    }

    /** Exchange-confirmed reduce-only exit; it can never reverse the one-way position. */
    fun closePosition(
        symbol: String,
        direction: Direction,
        quantity: String,
        clientOrderId: String,
        reason: String
    ): JSONObject = synchronized(lock) {
        require(symbol in allowedSymbols)
        require(quantity.toBigDecimalOrNull()?.signum() == 1)
        require(clientOrderId.matches(Regex("[A-Za-z0-9_./:-]{1,36}")))
        val intents = intents()
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
        val latestIndex = latestEntryIntentIndex(intents, symbol)
        val managedEntry = latestIndex?.let { intents.optJSONObject(it) }
            ?.takeIf { row ->
                row.optString("intent_role", "ENTRY") == "ENTRY" &&
                    isManagedOpenPositionIntent(
                        status = campaignLifecycleStatus(row),
                        direction = row.optString("direction"),
                        expectedDirection = direction.name,
                        protectionStatus = row.optString("protection_status"),
                        protectionClientId = row.optString("protection_client_id")
                    ) &&
                    run {
                        val entry = row.optString("entry_price").toBigDecimalOrNull()
                        val stop = row.optString("protective_stop").toBigDecimalOrNull()
                        entry != null && stop != null && entry.signum() > 0 && stop.signum() > 0
                    }
            }
        if (managedEntry == null) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol has no confirmed active Williams campaign; refusing to close an unmanaged position"
            return@synchronized JSONObject().put("ok", false).put("state", state.name)
                .put("blocker", lastBlocker)
        }
        val adapterPreflight = adapterFactory()
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
            val liveDirection = if (liveAmount.signum() > 0) Direction.LONG else Direction.SHORT
            val requestedQty = quantity.toBigDecimal()
            if (liveDirection != direction || requestedQty.compareTo(liveAmount.abs()) != 0) {
                state = State.RECONCILE_REQUIRED
                lastBlocker = "$symbol exit direction/quantity does not exactly match the live Binance position; exit mutation blocked"
                return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
            }
            val expectedProtectionId = managedEntry.optString("protection_client_id")
            val expectedStop = managedEntry.optString("protective_stop")
            val openAlgos = adapterPreflight.openAlgoOrders(symbol)
            if (findProtectiveStop(openAlgos, direction.name, expectedProtectionId, expectedStop) == null) {
                state = State.RECONCILE_REQUIRED
                lastBlocker = "$symbol current campaign stop is not exchange-confirmed by exact client id and structural trigger; exit mutation blocked"
                managedEntry.put("protection_status", "SUBMISSION_UNKNOWN")
                saveIntents(intents)
                return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
            }
        } catch (failure: Exception) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "$symbol exit preflight could not confirm exchange position/protection: ${failure.message ?: failure.javaClass.simpleName}"
            return@synchronized JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
        }
        val adapter = adapterPreflight
        val currentProtectionId = managedEntry.optString("protection_client_id")
        val previousProtectionId = managedEntry.optString("previous_protection_client_id")
        val exitIntent = JSONObject()
            .put("intent_role", "EXIT")
            .put("symbol", symbol)
            .put("direction", direction.name)
            .put("kind", "MARKET_REDUCE_ONLY")
            .put("quantity", quantity)
            .put("client_id", clientOrderId)
            .put("reason", reason)
            .put("exchange_status", "SUBMISSION_UNKNOWN")
            .put("created_at_ms", System.currentTimeMillis())
        intents.put(exitIntent)
        managedEntry.put("campaign_status", "EXIT_PENDING")
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
                .put("submitted_at_ms", System.currentTimeMillis())
            saveIntents(intents)

            // A successful POST response is not proof of a completed exit. Query the
            // same client order id and require FILLED before treating the campaign flat.
            val confirmedOrder = adapter.order(symbol, clientOrderId)
            val finalOrderStatus = confirmedOrder.optString("status").uppercase(Locale.US)
            exitIntent.put("exchange_order_id", confirmedOrder.optString("orderId", result.optString("orderId", "")))
                .put("exchange_status", finalOrderStatus)
                .put("order_status", finalOrderStatus)
                .put("last_reconciled_ms", System.currentTimeMillis())
            saveIntents(intents)
            if (finalOrderStatus != "FILLED") {
                state = if (finalOrderStatus in activeStatuses) State.EXIT_PENDING else State.RECONCILE_REQUIRED
                lastBlocker = "$symbol exit is not exchange-confirmed FILLED (status=$finalOrderStatus); protection retained and retry blocked"
                return@synchronized JSONObject().put("ok", false).put("state", state.name)
                    .put("blocker", lastBlocker).put("order", confirmedOrder)
            }

            val remaining = adapter.positions()
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
            val confirmedFlatAt = System.currentTimeMillis()
            exitIntent.put("exchange_status", "EXIT_CONFIRMED")
                .put("order_status", "FILLED")
                .put("confirmed_flat_at_ms", confirmedFlatAt)
            // Terminal lifecycle is persisted before any stop-cleanup network request.
            managedEntry.put("campaign_status", "CLOSED_OR_STOPPED")
                .put("closed_observed_at_ms", confirmedFlatAt)
                .put("close_reason", reason)
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
            state = State.READY
            lastBlocker = null
            JSONObject().put("ok", true).put("state", state.name).put("order", result)
                .put("flat_confirmed", true).put("reason", reason)
        } catch (failure: Exception) {
            // Preserve the known flat/exit result if only protective-order cleanup
            // failed; reconciliation still blocks entry until the orphan is resolved.
            if (exitIntent.optString("exchange_status").uppercase(Locale.US) != "EXIT_CONFIRMED") {
                exitIntent.put("exchange_status", "SUBMISSION_UNKNOWN")
            }
            exitIntent.put("error", failure.message ?: failure.javaClass.simpleName)
            saveIntents(intents)
            state = State.RECONCILE_REQUIRED
            lastBlocker = "exit/protection cleanup unresolved; reconciliation before any retry"
            JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
                .put("error", failure.message ?: failure.javaClass.simpleName)
        }
    }

    fun status(): JSONObject {
        val persisted = intents()
        val pendingDetails = JSONArray()
        val now = System.currentTimeMillis()
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
            pendingDetails.put(
                JSONObject()
                    .put("symbol", item.optString("symbol"))
                    .put("direction", item.optString("direction"))
                    .put("role", role)
                    .put("kind", item.optString("kind"))
                    .put("client_id_suffix", item.optString("client_id").takeLast(8))
                    .put("order_status", orderStatus)
                    .put("campaign_status", lifecycle)
                    .put("created_at_ms", item.optLong("created_at_ms", 0L))
                    .put("last_reconciled_ms", item.optLong("last_reconciled_ms", 0L))
                    .put("age_ms", (now - item.optLong("created_at_ms", now)).coerceAtLeast(0L))
            )
        }
        return JSONObject()
            .put("engine", "USD_M_FUTURES_TESTNET")
            .put("state", state.name)
            .put("ready_for_new_entry", state == State.READY || state == State.OPEN_PROTECTED)
            .put("blocker", lastBlocker ?: JSONObject.NULL)
            .put("pending_intents", pendingDetails.length())
            .put("pending_intent_details", pendingDetails)
            .put("testnet_only", true)
            .put("position_sides", JSONArray().put("LONG").put("SHORT"))
    }

    private fun validatePlan(plan: EntryPlan) {
        require(plan.symbol.uppercase(Locale.US) in allowedSymbols) {
            "Symbol is outside the approved Futures universe"
        }
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

    private fun findProtectiveStop(
        orders: JSONArray,
        direction: String,
        expectedClientId: String? = null,
        expectedStop: String? = null
    ): JSONObject? {
        if (expectedClientId.isNullOrBlank()) return null
        val stop = expectedStop?.toBigDecimalOrNull() ?: return null
        val candidates = (0 until orders.length()).mapNotNull { orders.optJSONObject(it) }
            .filter { isProtectiveStop(it, direction) }
        return candidates.firstOrNull { row ->
            val trigger = row.optString("triggerPrice").ifBlank { row.optString("stopPrice") }.toBigDecimalOrNull()
            row.optString("clientAlgoId") == expectedClientId &&
                trigger != null && trigger.compareTo(stop) == 0
        }
    }

    private fun isProtectiveStop(order: JSONObject, direction: String): Boolean {
        val type = order.optString("type", order.optString("orderType")).uppercase(Locale.US)
        if (type != "STOP_MARKET") return false
        if (!order.optString("closePosition").equals("true", true)) return false
        val side = order.optString("side").uppercase(Locale.US)
        if (direction == "LONG" && side != "SELL") return false
        if (direction == "SHORT" && side != "BUY") return false
        val status = order.optString("algoStatus", order.optString("status", "NEW")).uppercase(Locale.US)
        return status !in terminalStatuses
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
    }
}