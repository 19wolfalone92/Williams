package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** One immutable display generation. Never an execution/admission snapshot. */
internal data class FuturesPositionDisplayEnvelope(
    val positionsJson: String,
    val observedAtMs: Long,
    val protectionJson: String
)

internal data class FuturesProtectionDisplaySnapshot(
    val positions: JSONArray,
    val observations: JSONArray,
    val observedAtMs: Long
)

/** Signed GETs only, independent of the slow campaign/history reconciliation.
 * Bracket the open-STOP read with position reads: an intervening fill, reversal,
 * reduction or new entry price must not inherit the earlier position's proof.
 * The timestamp is the START of the read, never the time formatting finishes.
 * Previous ownership is retained only for the same exact durable owner/STOP;
 * this reader cannot create ownership or authorize any exchange mutation.
 */
internal fun readFuturesProtectionDisplaySnapshot(
    readPositions: () -> JSONArray,
    readOpenAlgos: () -> JSONArray,
    contractPrice: (String) -> Double,
    trustedObservations: JSONArray,
    intents: JSONArray,
    clock: () -> Long,
    ttlMs: Long = 15_000L
): FuturesProtectionDisplaySnapshot {
    val startedAt = clock()
    check(startedAt > 0 && ttlMs > 0) { "Invalid signed display snapshot clock" }
    fun liveRows(rows: JSONArray): Map<String, JSONObject> {
        validateFuturesPositionSnapshot(rows)
        val live = linkedMapOf<String, JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val quantity = row.getString("positionAmt").toBigDecimal()
            if (quantity.signum() == 0) continue
            val symbol = row.getString("symbol")
            val entry = row.optString("entryPrice").toBigDecimalOrNull()
            check(symbol in BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS &&
                row.optString("positionSide", "BOTH") == "BOTH" &&
                entry != null && entry.signum() > 0 && live.put(symbol, row) == null) {
                "Unverified Futures display position identity/entry"
            }
        }
        return live
    }
    val before = liveRows(readPositions())
    val algos = readOpenAlgos()
    check(CockpitDiagnostics.rows(algos).size == algos.length()) {
        "Malformed signed open algo-order snapshot"
    }
    val afterRows = readPositions()
    val after = liveRows(afterRows)
    check(before.keys == after.keys && before.all { (symbol, old) ->
        val current = after.getValue(symbol)
        listOf("positionAmt", "entryPrice").all { field ->
            old.getString(field).toBigDecimal().compareTo(current.getString(field).toBigDecimal()) == 0
        }
    }) { "Futures exposure changed during signed position/STOP read; fresh reconciliation required" }
    val observations = JSONArray()
    for ((symbol, position) in after) {
        val amount = position.getString("positionAmt").toBigDecimal()
        val direction = if (amount.signum() > 0) "LONG" else "SHORT"
        val mark = position.optString("markPrice").toDoubleOrNull() ?: Double.NaN
        var contract: Double? = null
        val stops = CockpitDiagnostics.rows(algos).filter { order ->
            if (order.optString("symbol") != symbol) return@filter false
            val reference = if (order.optString("workingType") == "CONTRACT_PRICE") {
                contract ?: contractPrice(symbol).also { contract = it }
            } else mark
            isFullPositionFuturesStop(order, symbol, direction, amount.abs(), reference)
        }
        val stop = stops.singleOrNull()
        val id = stop?.optString("clientAlgoId").orEmpty()
        val price = stop?.optString("triggerPrice")?.ifBlank { stop.optString("stopPrice") }
        val owner = CockpitDiagnostics.rows(intents).singleOrNull { intent ->
            intent.optString("intent_role", "ENTRY") == "ENTRY" &&
                intent.optString("symbol") == symbol && intent.optString("direction") == direction &&
                campaignLifecycleStatus(intent) == "OPEN_PROTECTED" &&
                intent.optBoolean("strategy_ownership_verified") &&
                !intent.has("native_stop_replacement") &&
                intent.optString("previous_protection_client_id").isBlank() &&
                id.isNotBlank() && intent.optString("protection_client_id") == id &&
                intent.optString("confirmed_position_qty").toBigDecimalOrNull()?.compareTo(amount.abs()) == 0 &&
                intent.optString("actual_entry_price").toBigDecimalOrNull()
                    ?.compareTo(position.getString("entryPrice").toBigDecimal()) == 0
        }
        val previous = CockpitDiagnostics.rows(trustedObservations).singleOrNull { proof ->
            proof.optString("symbol") == symbol && proof.optString("direction") == direction &&
                proof.optString("quantity").toBigDecimalOrNull()?.compareTo(amount) == 0 &&
                id.isNotBlank() && proof.optString("protection_client_id") == id &&
                price != null && proof.optString("stop").toBigDecimalOrNull()
                    ?.compareTo(price.toBigDecimal()) == 0
        }
        val owned = owner != null && previous?.optString("stop_proof") == "OWNED_STOP_SEEN" &&
            previous.optBoolean("strategy_ownership_verified") &&
            previous.optString("management_mode") == "STRATEGY_OWNED"
        observations.put(JSONObject().put("symbol", symbol).put("direction", direction)
            .put("quantity", amount.toPlainString()).put("observed_at_ms", startedAt)
            .put("stop_proof", when {
                stops.size > 1 -> "MULTIPLE_PROTECTIVE_ORDERS_UNRESOLVED"
                stop == null -> "STOP_UNCONFIRMED"
                owned -> "OWNED_STOP_SEEN"
                else -> "EXTERNAL_PROTECTED"
            }).put("stop", price ?: JSONObject.NULL)
            .put("protection_client_id", id.ifBlank { null } ?: JSONObject.NULL)
            .put("strategy_ownership_verified", owned)
            .put("automatic_trailing_authorized", owned)
            .put("management_mode", if (owned) "STRATEGY_OWNED" else "OBSERVE_ONLY")
            .put("scope", "SIGNED_GET_ONLY_DISPLAY_NOT_EXECUTION_AUTHORITY"))
    }
    check(CockpitDiagnostics.fresh(startedAt, clock(), ttlMs)) {
        "Signed position/STOP read exceeded its freshness window; old evidence retained as unverified"
    }
    return FuturesProtectionDisplaySnapshot(afterRows, observations, startedAt)
}
