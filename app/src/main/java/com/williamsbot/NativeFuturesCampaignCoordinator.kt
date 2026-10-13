package com.williamsbot

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Local, durable USDⓈ-M Futures Testnet execution boundary.
 *
 * Every exchange mutation has a committed local intent first. Ambiguous writes are
 * resolved by client id and exchange state; they are never blindly re-sent.
 */
internal class NativeFuturesCampaignCoordinator(
    private val prefs: SharedPreferences,
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
    private val activeStatuses = setOf(
        "NEW", "PENDING", "WORKING", "PARTIALLY_FILLED",
        "SUBMISSION_UNKNOWN", "ACCEPTED", "TRIGGERED", "CANCEL_SUBMISSION_UNKNOWN"
    )

    fun accountSnapshot(): JSONObject = adapterFactory().account()
    fun positionsSnapshot(): JSONArray = adapterFactory().positions()
    fun intentsSnapshot(): JSONArray = JSONArray(intents().toString())

    /** Must finish successfully before the scanner may submit a new entry. */
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
            val latestEntryIndexBySymbol = mutableMapOf<String, Int>()
            for (i in 0 until persisted.length()) {
                val item = persisted.optJSONObject(i)
                    ?: throw IllegalStateException("Persisted Futures intent is malformed")
                if (item.optString("intent_role", "ENTRY") == "ENTRY") {
                    latestEntryIndexBySymbol[item.optString("symbol").uppercase(Locale.US)] = i
                }
            }
            val activeEntryClientIds = mutableSetOf<String>()
            val activeProtectionClientIds = mutableSetOf<String>()
            for (i in 0 until persisted.length()) {
                val item = persisted.optJSONObject(i) ?: continue
                if (item.optString("intent_role", "ENTRY") != "ENTRY") continue
                val status = item.optString("exchange_status").uppercase(Locale.US)
                val entryId = item.optString("client_id")
                if (status in activeStatuses && entryId.isNotBlank()) activeEntryClientIds += entryId
                if (status in activeStatuses || status in setOf("OPEN_PROTECTED", "POSITION_OPEN")) {
                    item.optString("protection_client_id").takeIf { it.isNotBlank() }
                        ?.let { activeProtectionClientIds += it }
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
                if (symbol !in allowedSymbols || clientId !in activeEntryClientIds) {
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
                    (clientId !in activeEntryClientIds && clientId !in activeProtectionClientIds)
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
                val prior = intent.optString("exchange_status").uppercase(Locale.US)
                if (role == "EXIT") {
                    val order = runCatching { adapter.order(symbol, clientId) }.getOrNull()
                    if (order == null) {
                        if (position == null) {
                            intent.put("exchange_status", "EXIT_CONFIRMED")
                                .put("confirmed_flat_at_ms", System.currentTimeMillis())
                        } else {
                            throw IllegalStateException(
                                "Exit intent " + clientId + " is unresolved while " + symbol + " remains open; no retry permitted"
                            )
                        }
                    } else {
                        val exitStatus = order.optString("status").uppercase(Locale.US)
                        if (exitStatus == "FILLED") {
                            if (position != null) {
                                throw IllegalStateException(
                                    "Exit order " + clientId + " reports FILLED but Binance still reports an open " + symbol + " position"
                                )
                            }
                            intent.put("exchange_status", "EXIT_CONFIRMED")
                                .put("exchange_order_id", order.optString("orderId"))
                                .put("confirmed_flat_at_ms", System.currentTimeMillis())
                        } else if (exitStatus in setOf("CANCELED", "CANCELLED", "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH")) {
                            intent.put("exchange_status", if (position == null) "EXIT_CONFIRMED" else "EXIT_TERMINAL_POSITION_OPEN")
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
                if (position != null && isLatestCampaign && intent.optString("direction") == direction) {
                    intent.put("exchange_status", "POSITION_OPEN")
                    intent.put("last_reconciled_ms", System.currentTimeMillis())
                    continue
                }
                if (position != null && !isLatestCampaign && prior !in terminalStatuses) {
                    intent.put("exchange_status", "CLOSED_OR_STOPPED")
                        .put("closed_observed_at_ms", System.currentTimeMillis())
                    continue
                }
                if (position != null && isLatestCampaign && intent.optString("direction") != direction) {
                    throw IllegalStateException(
                        "Live " + symbol + " Futures position direction conflicts with persisted campaign"
                    )
                }
                if (prior in terminalStatuses || prior == "OPEN_PROTECTED" || prior == "POSITION_OPEN") {
                    if (prior == "OPEN_PROTECTED" || prior == "POSITION_OPEN") {
                        intent.put("exchange_status", "CLOSED_OR_STOPPED")
                        intent.put("closed_observed_at_ms", System.currentTimeMillis())
                    }
                    continue
                }
                val kind = intent.optString("kind")
                val order = when (kind) {
                    EntryKind.LIMIT.name -> runCatching { adapter.order(symbol, clientId) }.getOrNull()
                    EntryKind.STOP.name, EntryKind.TAKE_PROFIT.name ->
                        runCatching { adapter.queryAlgoOrder(symbol, clientId) }.getOrNull()
                    else -> null
                } ?: throw IllegalStateException(
                    "Cannot reconcile persisted entry $clientId; no exchange record, no resubmission allowed"
                )
                val exchangeStatus = order.optString("status", order.optString("algoStatus", "")).uppercase(Locale.US)
                if (exchangeStatus.isBlank()) {
                    throw IllegalStateException("Entry $clientId has unknown exchange status")
                }
                intent.put("exchange_status", exchangeStatus)
                    .put("last_reconciled_ms", System.currentTimeMillis())
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
                            it.optString("protective_stop").toDoubleOrNull()?.let { p -> p > 0.0 } == true
                    }
                var protective = findProtectiveStop(
                    algos, direction,
                    matchingIntent?.optString("protection_client_id"),
                    matchingIntent?.optString("protective_stop")
                )

                if (protective == null) {
                    if (matchingIntent == null) {
                        anyUnprotected = true
                        lastBlocker = "$symbol has an exchange position but no persisted structural stop; automatic trading blocked"
                        continue
                    }
                    val protectionStatus = matchingIntent.optString("protection_status", "NOT_SUBMITTED")
                        .uppercase(Locale.US)
                    var protectionClientId = matchingIntent.optString("protection_client_id")
                    if (protectionStatus == "SUBMISSION_UNKNOWN") {
                        if (protectionClientId.isBlank()) {
                            anyUnprotected = true
                            lastBlocker = "$symbol protective-stop submission is ambiguous and has no client id"
                            continue
                        }
                        // Query only. A missing order after an ambiguous submission is NOT
                        // permission to resubmit: the operator must resolve the uncertainty.
                        runCatching { adapter.queryAlgoOrder(symbol, protectionClientId) }
                        val refreshed = adapter.openAlgoOrders(symbol)
                        protective = findProtectiveStop(refreshed, direction, protectionClientId, matchingIntent.optString("protective_stop"))
                        if (protective == null) {
                            anyUnprotected = true
                            lastBlocker = "$symbol protective-stop submission is ambiguous; no retry performed"
                            continue
                        }
                    } else {
                        if (protectionClientId.isBlank()) {
                            protectionClientId = stableProtectionId(symbol, matchingIntent.optString("client_id"))
                            matchingIntent.put("protection_client_id", protectionClientId)
                        }
                        // Persist UNKNOWN before crossing the network. A process death at any
                        // point after this commit cannot cause a blind duplicate stop.
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
                            continue
                        }
                    }
                }

                if (matchingIntent != null) {
                    val confirmedStop = protective?.optString("triggerPrice")?.takeIf { it.isNotBlank() }
                        ?: protective?.optString("stopPrice")?.takeIf { it.isNotBlank() }
                        ?: matchingIntent.optString("protective_stop")
                    matchingIntent.put("exchange_status", "OPEN_PROTECTED")
                        .put("protection_status", "PROTECTED")
                        .put("protection_client_id", protective?.optString("clientAlgoId")
                            ?.takeIf { it.isNotBlank() } ?: matchingIntent.optString("protection_client_id"))
                        .put("protective_stop", confirmedStop)
                        .put("last_reconciled_ms", System.currentTimeMillis())
                }
            }

            // Once Binance reports the symbol flat, prior position intents are terminal.
            for (i in 0 until persisted.length()) {
                val intent = persisted.optJSONObject(i) ?: continue
                if (intent.optString("intent_role", "ENTRY") != "ENTRY") continue
                val symbol = intent.optString("symbol").uppercase(Locale.US)
                val status = intent.optString("exchange_status").uppercase(Locale.US)
                if (symbol !in openBySymbol && status in setOf("OPEN_PROTECTED", "POSITION_OPEN")) {
                    intent.put("exchange_status", "CLOSED_OR_STOPPED")
                        .put("closed_observed_at_ms", System.currentTimeMillis())
                }
            }
            saveIntents(persisted)

            val hasPendingEntry = (0 until persisted.length()).any { index ->
                val intent = persisted.optJSONObject(index) ?: return@any true
                val symbol = intent.optString("symbol").uppercase(Locale.US)
                intent.optString("intent_role", "ENTRY") == "ENTRY" &&
                    symbol !in openBySymbol &&
                    intent.optString("exchange_status").uppercase(Locale.US) in activeStatuses
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
        check((0 until currentIntents.length()).none { i ->
            val row = currentIntents.optJSONObject(i) ?: return@none false
            row.optString("symbol") == plan.symbol &&
                row.optString("exchange_status").uppercase(Locale.US) in activeStatuses
        }) { "An entry order is already pending for ${plan.symbol}" }

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
            intent.put("exchange_status", result.optString("status", result.optString("algoStatus", "NEW")).uppercase(Locale.US))
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
                openSymbols.add(row.optString("symbol"))
            }
        }
        try {
            val failures = mutableListOf<String>()
            for (i in 0 until all.length()) {
                val intent = all.optJSONObject(i) ?: continue
                if (intent.optString("intent_role", "ENTRY") != "ENTRY") continue
                val symbol = intent.optString("symbol")
                val status = intent.optString("exchange_status").uppercase(Locale.US)
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
                        intent.put("exchange_status", "CANCELED")
                            .put("cancel_reconciled_status", observedStatus)
                            .put("canceled_at_ms", System.currentTimeMillis())
                        saveIntents(all)
                        continue
                    }
                    throw IOException("Cancellation outcome for $clientId remains ambiguous; no repeated cancel sent")
                }
                intent.put("exchange_status", "CANCEL_SUBMISSION_UNKNOWN")
                    .put("cancel_reason", reason)
                    .put("cancel_attempt_at_ms", System.currentTimeMillis())
                saveIntents(all)
                val result = when (intent.optString("kind")) {
                    EntryKind.LIMIT.name -> adapter.cancelOrder(symbol, clientId)
                    EntryKind.STOP.name, EntryKind.TAKE_PROFIT.name -> adapter.cancelAlgoOrder(symbol, clientId)
                    else -> throw IllegalStateException("Unknown pending-entry type; cannot cancel safely")
                }
                intent.put("exchange_status", "CANCELED")
                    .put("cancel_result", result.toString())
                    .put("canceled_at_ms", System.currentTimeMillis())
                saveIntents(all)
                } catch (failure: Exception) {
                    val detail = "$symbol/$clientId: " + (failure.message ?: failure.javaClass.simpleName)
                    failures += detail
                    intent.put("exchange_status", "CANCEL_SUBMISSION_UNKNOWN")
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
                    it.optString("symbol") == symbol &&
                    it.optString("direction") == direction.name &&
                    it.optString("exchange_status") == "OPEN_PROTECTED"
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
        val previous = (0 until before.length()).mapNotNull { before.optJSONObject(it) }
            .filter { isProtectiveStop(it, direction.name) }
        intent.put("previous_protective_stop", oldStop.toPlainString())
            .put("protective_stop", proposed.stripTrailingZeros().toPlainString())
            .put("protection_client_id", clientAlgoId)
            .put("protection_status", "SUBMISSION_UNKNOWN")
            .put("trail_attempt_at_ms", System.currentTimeMillis())
        saveIntents(persisted)

        try {
            adapter.placeProtectiveStop(symbol, direction.name, proposed.toPlainString(), clientAlgoId)
            val afterCreate = adapter.openAlgoOrders(symbol)
            val confirmed = (0 until afterCreate.length()).mapNotNull { afterCreate.optJSONObject(it) }
                .firstOrNull { isProtectiveStop(it, direction.name) && it.optString("clientAlgoId") == clientAlgoId }
                ?: throw IOException("New trailing stop was not confirmed by exchange")
            for (old in previous) {
                if (old.optString("clientAlgoId") == clientAlgoId) continue
                val algoId = old.optString("algoId")
                val oldClientId = old.optString("clientAlgoId")
                if (algoId.isNotBlank()) adapter.cancelAlgoOrderById(symbol, algoId)
                else if (oldClientId.isNotBlank()) adapter.cancelAlgoOrder(symbol, oldClientId)
                else throw IOException("Cannot safely identify replaced protective stop")
            }
            val finalOrders = adapter.openAlgoOrders(symbol)
            val finalStop = (0 until finalOrders.length()).mapNotNull { finalOrders.optJSONObject(it) }
                .firstOrNull { isProtectiveStop(it, direction.name) && it.optString("clientAlgoId") == clientAlgoId }
                ?: throw IOException("Replacement stop is no longer open after old-stop cancellation")
            intent.put("protection_status", "PROTECTED")
                .put("protective_stop", finalStop.optString("triggerPrice", proposed.toPlainString()))
                .put("trailing_at_ms", System.currentTimeMillis())
            saveIntents(persisted)
            JSONObject().put("ok", true).put("state", state.name).put("stop", finalStop)
        } catch (failure: Exception) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = "ambiguous_trailing_stop_replacement_manual_reconciliation_required"
            intent.put("protection_status", "SUBMISSION_UNKNOWN")
                .put("trail_error", failure.message ?: failure.javaClass.simpleName)
            saveIntents(persisted)
            JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
                .put("error", failure.message ?: failure.javaClass.simpleName)
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
        val adapter = adapterFactory()
        val intents = intents()
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
        saveIntents(intents)
        state = State.EXIT_PENDING
        lastBlocker = "exit_submission_and_exchange_confirmation_pending"
        try {
            adapter.requireOneWayMode()
            val result = adapter.closeMarketPosition(symbol, direction.name, quantity, clientOrderId)
            exitIntent.put("exchange_order_id", result.optString("orderId", ""))
                .put("exchange_status", result.optString("status", "UNKNOWN").uppercase(Locale.US))
                .put("submitted_at_ms", System.currentTimeMillis())
            saveIntents(intents)
            val remaining = adapter.positions()
            val stillOpen = (0 until remaining.length()).mapNotNull { remaining.optJSONObject(it) }
                .any {
                    it.optString("symbol") == symbol &&
                        kotlin.math.abs(it.optString("positionAmt").toDoubleOrNull() ?: 0.0) > 0.0
                }
            if (stillOpen) {
                state = State.RECONCILE_REQUIRED
                lastBlocker = "$symbol exit order returned but exchange still reports a position"
                return@synchronized JSONObject().put("ok", false).put("state", state.name)
                    .put("blocker", lastBlocker).put("order", result)
            }
            exitIntent.put("exchange_status", "EXIT_CONFIRMED")
                .put("confirmed_flat_at_ms", System.currentTimeMillis())
            saveIntents(intents)
            // Cancel only close-position protective stops after the exchange is confirmed flat.
            val algos = adapter.openAlgoOrders(symbol)
            for (i in 0 until algos.length()) {
                val row = algos.optJSONObject(i) ?: continue
                if (!row.optString("type", row.optString("orderType")).equals("STOP_MARKET", true) ||
                    !row.optString("closePosition").equals("true", true)
                ) continue
                val algoId = row.optString("algoId")
                val cid = row.optString("clientAlgoId")
                if (algoId.isNotBlank()) adapter.cancelAlgoOrderById(symbol, algoId)
                else if (cid.isNotBlank()) adapter.cancelAlgoOrder(symbol, cid)
                else throw IOException("Cannot identify orphaned protective stop after exit")
            }
            state = State.READY
            lastBlocker = null
            JSONObject().put("ok", true).put("state", state.name).put("order", result)
                .put("flat_confirmed", true).put("reason", reason)
        } catch (failure: Exception) {
            exitIntent.put("exchange_status", "SUBMISSION_UNKNOWN")
                .put("error", failure.message ?: failure.javaClass.simpleName)
            saveIntents(intents)
            state = State.RECONCILE_REQUIRED
            lastBlocker = "ambiguous_exit_submission_reconcile_before_any_retry"
            JSONObject().put("ok", false).put("state", state.name).put("blocker", lastBlocker)
                .put("error", failure.message ?: failure.javaClass.simpleName)
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("engine", "USD_M_FUTURES_TESTNET")
        .put("state", state.name)
        .put("ready_for_new_entry", state == State.READY || state == State.OPEN_PROTECTED)
        .put("blocker", lastBlocker ?: JSONObject.NULL)
        .put("pending_intents", intents().length())
        .put("testnet_only", true)
        .put("position_sides", JSONArray().put("LONG").put("SHORT"))

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
        when (plan.direction) {
            Direction.LONG -> {
                require(stop < price) { "LONG protective stop must be below entry" }
            }
            Direction.SHORT -> {
                require(stop > price) { "SHORT protective stop must be above entry" }
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
        val candidates = (0 until orders.length()).mapNotNull { orders.optJSONObject(it) }
            .filter { isProtectiveStop(it, direction) }
        if (!expectedClientId.isNullOrBlank()) {
            return candidates.firstOrNull { it.optString("clientAlgoId") == expectedClientId }
        }
        val stop = expectedStop?.toBigDecimalOrNull() ?: return null
        return candidates.firstOrNull { row ->
            val trigger = row.optString("triggerPrice", row.optString("stopPrice")).toBigDecimalOrNull()
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

    private fun intents(): JSONArray = runCatching {
        JSONArray(prefs.getString(intentsKey, "[]"))
    }.getOrElse {
        throw IllegalStateException("Persisted Futures intents are unreadable; manual reconciliation required")
    }

    private fun saveIntents(array: JSONArray) {
        check(prefs.edit().putString(intentsKey, array.toString()).commit()) {
            "Unable to persist Futures order intent; execution must remain blocked"
        }
    }
}
