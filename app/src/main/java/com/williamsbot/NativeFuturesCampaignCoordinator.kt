package com.williamsbot

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Fail-closed campaign execution boundary for USDⓈ-M Futures Testnet.
 *
 * Pending intents are persisted BEFORE any exchange mutation. This coordinator never
 * substitutes a market order for a pending Williams trigger and never retries an
 * ambiguous mutation. The caller must supply a fully validated Williams signal and
 * risk-sized quantity; this class enforces exchange/account/order lifecycle safety.
 */
internal class NativeFuturesCampaignCoordinator(
    private val prefs: SharedPreferences,
    private val adapterFactory: () -> BinanceUsdMFuturesTestnetAdapter
) {
    enum class Direction { LONG, SHORT }
    enum class EntryKind { STOP, TAKE_PROFIT, LIMIT }
    enum class State { UNINITIALIZED, RECONCILE_REQUIRED, READY, ENTRY_PENDING, POSITION_UNPROTECTED, OPEN_PROTECTED }

    data class EntryPlan(
        val symbol: String,
        val direction: Direction,
        val kind: EntryKind,
        val quantity: String,
        val entryPrice: String,
        val protectiveStop: String,
        val clientId: String
    )

    @Volatile var state: State = State.UNINITIALIZED
        private set
    @Volatile var lastBlocker: String? = "startup_reconciliation_required"
        private set

    private val lock = Any()
    private val intentsKey = "futures_campaign_intents_v1"
    private val allowedSymbols = setOf(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
    )

    /** Must succeed before any new order can be submitted. */
    fun reconcile(): JSONObject = synchronized(lock) {
        state = State.RECONCILE_REQUIRED
        lastBlocker = "exchange_reconciliation_in_progress"
        val adapter = adapterFactory()
        try {
            adapter.serverTime()
            adapter.requireOneWayMode()
            adapter.account() // credentials and signed endpoint contract check
            val positions = adapter.positions()
            val persisted = intents()
            var anyOpenPosition = false
            var anyUnprotectedPosition = false
            val openAlgoBySymbol = mutableMapOf<String, JSONArray>()
            for (i in 0 until positions.length()) {
                val row = positions.optJSONObject(i) ?: continue
                val symbol = row.optString("symbol").uppercase(Locale.US)
                if (symbol !in allowedSymbols) continue
                val amount = row.optString("positionAmt").toDoubleOrNull() ?: 0.0
                if (amount == 0.0) continue
                anyOpenPosition = true
                val algos = openAlgoBySymbol.getOrPut(symbol) { adapter.openAlgoOrders(symbol) }
                val hasProtectiveStop = (0 until algos.length()).any { index ->
                    val order = algos.optJSONObject(index) ?: return@any false
                    order.optString("type").equals("STOP_MARKET", true) &&
                        order.optString("closePosition").equals("true", true)
                }
                if (!hasProtectiveStop) anyUnprotectedPosition = true
            }

            // Reconcile every locally persisted pending intent with its actual exchange
            // record. Missing/unknown status is not interpreted as permission to resubmit.
            for (intent in persisted) {
                val symbol = intent.optString("symbol")
                val clientId = intent.optString("client_id")
                val kind = intent.optString("kind")
                if (symbol !in allowedSymbols || clientId.isBlank()) {
                    throw IllegalStateException("Persisted Futures intent is malformed")
                }
                val order = if (kind == EntryKind.LIMIT.name) {
                    runCatching { adapter.order(symbol, clientId) }.getOrNull()
                } else {
                    runCatching { adapter.queryAlgoOrder(symbol, clientId) }.getOrNull()
                }
                if (order == null) {
                    throw IllegalStateException("Cannot reconcile persisted order $clientId; no resubmission allowed")
                }
                val statusValue = order.optString("status", order.optString("algoStatus", "")).uppercase(Locale.US)
                if (statusValue.isBlank()) {
                    throw IllegalStateException("Order $clientId has unknown exchange status")
                }
                intent.put("exchange_status", statusValue)
                intent.put("last_reconciled_ms", System.currentTimeMillis())
            }
            saveIntents(persisted)
            val hasActivePendingIntent = (0 until persisted.length()).any { index ->
                val intent = persisted.optJSONObject(index) ?: return@any true
                intent.optString("exchange_status").uppercase(Locale.US) in setOf(
                    "NEW", "PENDING", "PARTIALLY_FILLED", "SUBMISSION_UNKNOWN", "ACCEPTED"
                )
            }
            if (anyUnprotectedPosition) {
                state = State.POSITION_UNPROTECTED
                lastBlocker = "open_futures_position_without_confirmed_close_position_stop"
            } else if (hasActivePendingIntent) {
                // A persisted live/ambiguous entry must remain visible as pending after
                // restart; reconciliation success is not permission to submit another entry.
                state = State.ENTRY_PENDING
                lastBlocker = "exchange_confirmed_pending_entry_or_fill_not_yet_resolved"
            } else {
                state = if (anyOpenPosition) State.OPEN_PROTECTED else State.READY
                lastBlocker = null
            }
            JSONObject()
                .put("ok", true)
                .put("state", state.name)
                .put("positions_checked", positions.length())
                .put("pending_intents_checked", persisted.length())
                .put("active_pending_intent", hasActivePendingIntent)
                .put("blocker", lastBlocker ?: JSONObject.NULL)
        } catch (failure: Exception) {
            state = State.RECONCILE_REQUIRED
            lastBlocker = failure.message ?: failure.javaClass.simpleName
            JSONObject()
                .put("ok", false)
                .put("state", state.name)
                .put("blocker", lastBlocker)
        }
    }

    /**
     * Submit only a pending order selected by strategy. Entry intent is durable first.
     * A timeout/error after the mutation call always transitions to reconciliation.
     */
    fun submitPendingEntry(plan: EntryPlan): JSONObject = synchronized(lock) {
        check(state == State.READY || state == State.OPEN_PROTECTED) {
            "Futures execution blocked: ${lastBlocker ?: state.name}"
        }
        validatePlan(plan)
        val currentIntents = intents()
        check((0 until currentIntents.length()).none { i ->
            val row = currentIntents.optJSONObject(i) ?: return@none false
            row.optString("exchange_status").uppercase(Locale.US) in
                setOf("NEW", "PENDING", "PARTIALLY_FILLED", "SUBMISSION_UNKNOWN")
        }) { "An entry order is already pending for ${plan.symbol}" }

        val adapter = adapterFactory()
        val intent = JSONObject()
            .put("symbol", plan.symbol)
            .put("direction", plan.direction.name)
            .put("kind", plan.kind.name)
            .put("quantity", plan.quantity)
            .put("entry_price", plan.entryPrice)
            .put("protective_stop", plan.protectiveStop)
            .put("client_id", plan.clientId)
            .put("exchange_status", "SUBMISSION_UNKNOWN")
            .put("created_at_ms", System.currentTimeMillis())
        currentIntents.put(intent)
        saveIntents(currentIntents)
        state = State.ENTRY_PENDING
        lastBlocker = "pending_entry_submission_in_progress"

        try {
            adapter.serverTime()
            adapter.requireOneWayMode()
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
            intent.put("exchange_status", result.optString("status", "NEW").uppercase(Locale.US))
            intent.put("exchange_order_id", result.optString("orderId", result.optString("algoId", "")))
            intent.put("submitted_at_ms", System.currentTimeMillis())
            saveIntents(currentIntents)
            lastBlocker = "entry_pending_exchange_fill_and_protective_stop_not_yet_confirmed"
            JSONObject()
                .put("accepted", true)
                .put("state", state.name)
                .put("direction", plan.direction.name)
                .put("entry_kind", plan.kind.name)
                .put("order", result)
                .put("protection", "NOT_YET_CONFIRMED")
        } catch (failure: Exception) {
            // Do not remove the intent and do not retry: the exchange may have accepted
            // the mutation before the transport failed. Recovery must query its client ID.
            intent.put("exchange_status", "SUBMISSION_UNKNOWN")
            intent.put("error", failure.message ?: failure.javaClass.simpleName)
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

    fun status(): JSONObject = JSONObject()
        .put("engine", "USD_M_FUTURES_TESTNET")
        .put("state", state.name)
        .put("ready_for_new_entry", state == State.READY || state == State.OPEN_PROTECTED)
        .put("blocker", lastBlocker ?: JSONObject.NULL)
        .put("pending_intents", intents().length())
        .put("testnet_only", true)

    private fun validatePlan(plan: EntryPlan) {
        val symbol = plan.symbol.uppercase(Locale.US)
        require(symbol in allowedSymbols) { "Symbol is outside the approved Futures universe" }
        require(plan.quantity.toBigDecimalOrNull()?.signum() == 1) { "Quantity must be positive" }
        val price = plan.entryPrice.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Entry price is invalid")
        val stop = plan.protectiveStop.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Protective stop is invalid")
        require(price.signum() > 0 && stop.signum() > 0) { "Entry and stop prices must be positive" }
        when (plan.direction) {
            Direction.LONG -> require(stop < price) { "LONG protective stop must be below entry" }
            Direction.SHORT -> require(stop > price) { "SHORT protective stop must be above entry" }
        }
        require(plan.clientId.matches(Regex("[A-Za-z0-9_./:-]{1,36}"))) { "Invalid client order ID" }
    }

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
