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
    val observedAtMs: Long,
    val protectionSnapshotComplete: Boolean = true,
    val failedSymbols: Set<String> = emptySet()
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
    readOpenAlgosForSymbol: (String) -> JSONArray,
    contractPrice: (String) -> Double,
    trustedObservations: JSONArray,
    intents: JSONArray,
    clock: () -> Long,
    ttlMs: Long = 15_000L,
    supervisions: JSONArray = JSONArray(),
    readOpenOrders: (() -> JSONArray)? = null
): FuturesProtectionDisplaySnapshot {
    val startedAt = clock()
    check(startedAt > 0 && ttlMs > 0) { "Invalid signed display snapshot clock" }
    fun liveRows(rows: JSONArray): Map<String, JSONObject> {
        val live = linkedMapOf<String, JSONObject>()
        val identities = mutableSetOf<Pair<String,String>>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i)
                ?: throw IllegalStateException("Malformed signed Futures position row")
            val quantity = row.optString("positionAmt").toBigDecimalOrNull()
                ?: throw IllegalStateException("Malformed signed Futures position amount")
            if (quantity.signum() == 0) continue
            val symbol = row.optString("symbol").uppercase()
            val entry = row.optString("entryPrice").toBigDecimalOrNull()
            val side = row.optString("positionSide")
            check(symbol.isNotBlank() && side == "BOTH" &&
                identities.add(symbol to side) && entry != null && entry.signum() > 0) {
                "Unverified Futures display position identity/entry"
            }
            live[symbol] = row
        }
        return live
    }
    val before = liveRows(readPositions())
    // Read each live symbol independently. One failed symbol-scoped GET must
    // not hide signed protection evidence for the other open positions.
    val algos = JSONArray()
    val algoReadFailures = linkedMapOf<String,String>()
    for (symbol in before.keys) {
        try {
            val signed = readOpenAlgosForSymbol(symbol)
            check(CockpitDiagnostics.rows(signed).size == signed.length()) {
                "Malformed signed open algo-order snapshot for $symbol"
            }
            for (index in 0 until signed.length()) {
                val item = signed.getJSONObject(index)
                check(item.optString("symbol") == symbol) {
                    "Signed $symbol algo-order snapshot contains foreign/missing symbol"
                }
                algos.put(item)
            }
        } catch (failure: Exception) {
            algoReadFailures[symbol] = safeDiagnosticText(
                failure.message ?: failure.javaClass.simpleName).take(500)
        }
    }
    val activeSupervisions = CockpitDiagnostics.rows(supervisions).filter { it.optString("state") == "ACTIVE" }
    // An external fixed-quantity STOP is only a coverage observation while
    // NO resting standard order can change the verified position.
    val hasForeignContractStopCandidate=CockpitDiagnostics.rows(algos).any {
        it.optString("workingType")=="CONTRACT_PRICE" &&
            it.optString("orderType")=="STOP_MARKET" &&
            it.optString("reduceOnly").equals("true",ignoreCase=true)
    }
    val needsStandardOrders = activeSupervisions.isNotEmpty() ||
        hasForeignContractStopCandidate ||
        before.keys.any { it !in BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS }
    var standardOrders: JSONArray? = null
    var standardOrderReadFailure: String? = null
    if (needsStandardOrders) {
        try {
            val signedStandardOrders = readOpenOrders?.invoke()
                ?: throw IllegalStateException("Signed standard-order reader unavailable")
            check(CockpitDiagnostics.rows(signedStandardOrders).size == signedStandardOrders.length()) {
                "Malformed signed open standard-order snapshot"
            }
            standardOrders = signedStandardOrders
        } catch (failure: Exception) {
            standardOrderReadFailure = safeDiagnosticText(
                failure.message ?: failure.javaClass.simpleName).take(500)
        }
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
        val signedAlgoRows = CockpitDiagnostics.rows(algos).filter { it.optString("symbol") == symbol }
        val symbolStandardRows = standardOrders?.let { rows ->
            CockpitDiagnostics.rows(rows).filter { it.optString("symbol") == symbol }
        }.orEmpty()
        if (symbol !in BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS) {
            val observedStop = signedAlgoRows.singleOrNull { row ->
                val reference = if (row.optString("workingType") == "CONTRACT_PRICE") {
                    runCatching { contractPrice(symbol) }.getOrNull()
                } else mark
                reference?.let { isFullPositionFuturesStop(row,symbol,direction,amount.abs(),it) } == true
            }
            val orderSummary = JSONArray()
            for (row in signedAlgoRows) orderSummary.put(JSONObject()
                .put("scope","CONDITIONAL").put("algo_id",row.optString("algoId"))
                .put("client_id",row.optString("clientAlgoId"))
                .put("order_type",row.optString("orderType"))
                .put("status",row.optString("algoStatus")).put("side",row.optString("side"))
                .put("position_side",row.optString("positionSide"))
                .put("working_type",row.optString("workingType"))
                .put("trigger_price",row.optString("triggerPrice"))
                .put("quantity",row.optString("quantity"))
                .put("close_position",row.opt("closePosition") ?: JSONObject.NULL)
                .put("reduce_only",row.opt("reduceOnly") ?: JSONObject.NULL)
                .put("price_protect",row.opt("priceProtect") ?: JSONObject.NULL))
            for (row in symbolStandardRows) orderSummary.put(JSONObject()
                .put("scope","STANDARD").put("order_id",row.optString("orderId"))
                .put("client_id",row.optString("clientOrderId"))
                .put("order_type",row.optString("type")).put("status",row.optString("status"))
                .put("side",row.optString("side")).put("quantity",row.optString("origQty")))
            observations.put(JSONObject().put("symbol",symbol).put("direction",direction)
                .put("quantity",amount.toPlainString()).put("observed_at_ms",startedAt)
                .put("stop_proof","UNMANAGED_POSITION")
                .put("supervisor_state",FuturesProtectionSupervisorState.RECOVERY_REQUIRED.name)
                .put("stop",observedStop?.optString("triggerPrice")?.takeIf { it.isNotBlank() }
                    ?: observedStop?.optString("stopPrice")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("observed_stop",observedStop?.optString("triggerPrice")?.takeIf { it.isNotBlank() }
                    ?: observedStop?.optString("stopPrice")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("protection_client_id",observedStop?.optString("clientAlgoId") ?: JSONObject.NULL)
                .put("origin","UNVERIFIED_EXTERNAL_OR_LEGACY")
                .put("strategy_ownership_verified",false).put("automatic_trailing_authorized",false)
                .put("management_mode","OBSERVE_ONLY")
                .put("signed_algo_orders_seen",signedAlgoRows.size)
                .put("signed_algo_read_state",if(algoReadFailures.containsKey(symbol)) "FAILED" else "SIGNED")
                .put("symbol_algo_read_error",algoReadFailures[symbol] ?: JSONObject.NULL)
                .put("signed_standard_orders_seen",if(standardOrderReadFailure==null) symbolStandardRows.size else JSONObject.NULL)
                .put("signed_order_summaries",orderSummary)
                .put("standard_order_snapshot_error",standardOrderReadFailure ?: JSONObject.NULL)
                .put("scope","SIGNED_GET_ONLY_DISPLAY_NOT_EXECUTION_AUTHORITY"))
            continue
        }
        val readFailure = algoReadFailures[symbol]
        if (readFailure != null) {
            observations.put(JSONObject().put("symbol",symbol).put("direction",direction)
                .put("quantity",amount.toPlainString()).put("observed_at_ms",startedAt)
                .put("stop_proof","PROTECTION_UNKNOWN")
                .put("supervisor_state",FuturesProtectionSupervisorState.PROTECTION_UNKNOWN.name)
                .put("stop",JSONObject.NULL).put("protection_client_id",JSONObject.NULL)
                .put("strategy_ownership_verified",false).put("automatic_trailing_authorized",false)
                .put("management_mode","OBSERVE_ONLY")
                .put("stop_proof_rejection","SIGNED_SYMBOL_ALGO_READ_FAILED")
                .put("read_error",readFailure)
                .put("signed_standard_orders_seen",if(standardOrderReadFailure==null) symbolStandardRows.size else JSONObject.NULL)
                .put("standard_order_snapshot_error",standardOrderReadFailure ?: JSONObject.NULL)
                .put("scope","SIGNED_GET_ONLY_DISPLAY_NOT_EXECUTION_AUTHORITY"))
            continue
        }
        val stops = signedAlgoRows.filter { order ->
            if (order.optString("symbol") != symbol) return@filter false
            if (!order.optString("closePosition").equals("true", true) &&
                !order.optString("reduceOnly").equals("true", true)) return@filter false
            val reference = if (order.optString("workingType") == "CONTRACT_PRICE") {
                contract ?: contractPrice(symbol).also { contract = it }
            } else mark
            isFullPositionFuturesStop(order, symbol, direction, amount.abs(), reference)
        }
        // Never silently discard a signed external full-quantity
        // CONTRACT_PRICE reduce-only stop merely because the native
        // automated contract requires MARK_PRICE + closePosition.
        // This has NO authorization for ownership/auto-trail/new entries.
        val externalContractStop =
            if (stops.isEmpty() && signedAlgoRows.size == 1) {
                val candidate = signedAlgoRows.single()
                if (candidate.optString("workingType") != "CONTRACT_PRICE") null
                else candidate.takeIf {
                    isSignedExternalContractPriceFullQuantityStop(it,symbol,direction,
                        amount.abs(),contract ?: contractPrice(symbol).also { quote -> contract=quote })
                }
            } else null
        val standardBookSafe = standardOrders != null &&
            CockpitDiagnostics.rows(standardOrders).none { it.optString("symbol")==symbol }
        val certifiedExternalContractStop=externalContractStop?.takeIf {
            standardBookSafe
        }
        val observedPriceProtectedContractStop =
            if (certifiedExternalContractStop == null && stops.isEmpty() &&
                signedAlgoRows.size == 1 && standardBookSafe) {
                val candidate=signedAlgoRows.single()
                candidate.takeIf {
                    isSignedPriceProtectedExternalContractStopForObservation(
                        it,symbol,direction,amount.abs(),
                        contract ?: contractPrice(symbol).also { quote -> contract=quote })
                }
            } else null
        val nativeStop = stops.singleOrNull()
        val stop = nativeStop ?: certifiedExternalContractStop
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
        val owned = nativeStop != null && owner != null &&
            previous?.optString("stop_proof") == "OWNED_STOP_SEEN" &&
            previous.optBoolean("strategy_ownership_verified") &&
            previous.optString("management_mode") == "STRATEGY_OWNED"
        val supervision = activeSupervisions.singleOrNull { record ->
            record.optString("symbol") == symbol && record.optString("direction") == direction &&
                record.optString("mode") in setOf("OPERATOR_APPROVED_STOP_ONLY", "AUTO_VERIFIED_STOP_ONLY") &&
                !record.optBoolean("strategy_ownership_verified", true) &&
                !record.optBoolean("market_close_authorized", true) && !record.optBoolean("add_on_authorized", true) &&
                record.optString("quantity").toBigDecimalOrNull()?.compareTo(amount.abs()) == 0 &&
                record.optString("entry_price").toBigDecimalOrNull()
                    ?.compareTo(position.getString("entryPrice").toBigDecimal()) == 0 &&
                id.isNotBlank() && record.optString("protection_client_id") == id && price != null &&
                record.optString("stop").toBigDecimalOrNull()?.compareTo(price.toBigDecimal()) == 0
        }
        val supervised = nativeStop != null && !owned &&
            supervision != null && previous != null &&
            previous.optString("stop_proof") == "EXTERNAL_PROTECTED" &&
            previous.optBoolean("automatic_trailing_authorized") &&
            !previous.optBoolean("strategy_ownership_verified", true) &&
            previous.optString("management_mode") == supervision.optString("mode") &&
            CockpitDiagnostics.rows(algos).count { it.optString("symbol") == symbol } == 1 &&
            standardOrders != null && CockpitDiagnostics.rows(standardOrders).none { it.optString("symbol") == symbol }
        observations.put(JSONObject().put("symbol", symbol).put("direction", direction)
            .put("quantity", amount.toPlainString()).put("observed_at_ms", startedAt)
            .put("stop_proof", when {
                stops.size > 1 -> "MULTIPLE_PROTECTIVE_ORDERS_UNRESOLVED"
                stop == null -> "STOP_UNCONFIRMED"
                certifiedExternalContractStop != null -> "EXTERNAL_PROTECTED"
                owned -> "OWNED_STOP_SEEN"
                else -> "EXTERNAL_PROTECTED"
            }).put("supervisor_state",futuresProtectionSupervisorState(
                signedSnapshotComplete=mark.isFinite() && mark > 0.0,
                positionOpen=true,matchingFullStops=stops.size).name)
            .put("standard_order_snapshot_state",when {
                standardOrderReadFailure!=null -> "UNKNOWN"
                standardOrders!=null -> "SIGNED"
                else -> "NOT_REQUIRED"
            }).put("stop", price ?: JSONObject.NULL)
            .put("protection_client_id", id.ifBlank { null } ?: JSONObject.NULL)
            .put("strategy_ownership_verified", owned)
            .put("automatic_trailing_authorized", owned || supervised)
            .put("management_mode", if (owned) "STRATEGY_OWNED"
                else if (supervised) supervision!!.getString("mode") else "OBSERVE_ONLY")
            .put("signed_algo_orders_seen", signedAlgoRows.size)
            .put("signed_full_stops_seen", stops.size)
            .put("signed_external_contract_stops_seen",if(certifiedExternalContractStop != null) 1 else 0)
            // The conditional order EXISTS on exchange, but priceProtect=true
            // may defer triggering. Never put it into stop/protection_client_id.
            .put("observed_conditional_stop",
                observedPriceProtectedContractStop?.let {
                    signedPriceProtectedConditionalStopObservation(
                        it,"SIGNED_SYMBOL_SCOPED_DISPLAY_NOT_EXECUTION_AUTHORITY")
                } ?: JSONObject.NULL)
            .put("protection_contract",when {
                certifiedExternalContractStop != null -> "CONTRACT_PRICE_REDUCE_ONLY_EXACT_QTY"
                nativeStop != null -> "MARK_PRICE_PROTECTIVE_STOP"
                else -> JSONObject.NULL
            })
            // Diagnose a missing/invalid proof without treating the persisted
            // PROTECTED campaign status as evidence that Binance has a STOP.
            .put("stop_proof_rejection", when {
                stops.size > 1 -> "MULTIPLE_FULL_STOPS_NEED_OWNER_AND_COVERAGE_REVIEW"
                stop != null -> JSONObject.NULL
                signedAlgoRows.isEmpty() -> "NO_OPEN_ALGO_ORDERS_RETURNED_FOR_SYMBOL"
                observedPriceProtectedContractStop != null ->
                    "SIGNED_STOP_PRICE_PROTECT_TRUE_TRIGGER_CAN_BE_DELAYED"
                else -> "SIGNED_ALGOS_PRESENT_BUT_NOT_FULL_POSITION_PROTECTIVE_STOP"
            })
            .put("scope", "SIGNED_GET_ONLY_DISPLAY_NOT_EXECUTION_AUTHORITY"))
    }
    check(CockpitDiagnostics.fresh(startedAt, clock(), ttlMs)) {
        "Signed position/STOP read exceeded its freshness window; old evidence retained as unverified"
    }
    val failedSymbols = linkedSetOf<String>().apply {
        addAll(algoReadFailures.keys)
        if (standardOrderReadFailure != null) {
            addAll(activeSupervisions.map { it.optString("symbol") }.filter { it.isNotBlank() })
            if (hasForeignContractStopCandidate) {
                addAll(CockpitDiagnostics.rows(algos).filter {
                    it.optString("workingType")=="CONTRACT_PRICE" &&
                        it.optString("orderType")=="STOP_MARKET" &&
                        it.optString("reduceOnly").equals("true",ignoreCase=true)
                }.map { it.optString("symbol") })
            }
            addAll(before.keys.filter { it !in BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS })
        }
    }
    return FuturesProtectionDisplaySnapshot(
        afterRows,observations,startedAt,
        protectionSnapshotComplete=algoReadFailures.isEmpty() && standardOrderReadFailure==null,
        failedSymbols=failedSymbols
    )
}
