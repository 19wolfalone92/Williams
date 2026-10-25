package com.williamsbot

import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal

/**
 * Retain a transient signed STOP replacement failure for forensics, but mark
 * it RESOLVED only after the NEW STOP was actually signed and confirmed.
 * A new campaign's success cannot rewrite an older unrelated failure.
 */
internal fun annotateNativeStopFailureResolution(
    owner:JSONObject, replacement:JSONObject, confirmedId:String, atMs:Long
) {
    val failure=owner.optJSONObject("last_native_stop_failure") ?: return
    val failureAt=failure.optLong("at_ms",0L)
    val created=replacement.optLong("created_at_ms",0L)
    if(replacement.optString("phase")!="COMPLETED" || confirmedId.isBlank() ||
        atMs<=0L || created<=0L || failureAt<created || failureAt>atMs ||
        failure.optString("resolution").isNotBlank()) return
    failure.put("resolution","EXCHANGE_CONFIRMED_NEW_STOP_ACTIVE")
        .put("resolution_at_ms",atMs)
        .put("resolution_protection_client_id",confirmedId)
}

/** A native campaign must finish with close-all protection, including future
 * pyramid fills. A fixed-quantity reduce-only stop bridges the close-all swap.
 * Every POST is journaled BEFORE transport; recovery never replays an unknown POST.
 */
internal class FuturesNativeStopReplacement(
    private val adapter: BinanceUsdMFuturesTestnetAdapter,
    private val clock: () -> Long,
    private val validatePendingExposure: (JSONObject) -> Unit,
    private val persist: () -> Unit
) {
    fun resume(owner: JSONObject): JSONObject {
        val tx = owner.getJSONObject("native_stop_replacement")
        val symbol = owner.getString("symbol")
        val direction = owner.getString("direction")
        val oldId = tx.getString("old_id")
        val bridgeId = tx.getString("bridge_id")
        val newId = tx.getString("new_id")
        val oldPrice = tx.getString("old_stop")
        val price = tx.getString("new_stop")
        val quantity = tx.getString("quantity").toBigDecimal()
        try {
            check(owner.optBoolean("strategy_ownership_verified") &&
                direction in setOf("LONG", "SHORT") && quantity.signum() > 0 &&
                setOf(oldId, bridgeId, newId).size == 3 &&
                listOf(oldId, bridgeId, newId).all { it.matches(Regex("[A-Za-z0-9_./:-]{1,36}")) }) {
                "Invalid native replacement ownership or identities"
            }
            context(owner, tx)
            var old = query(symbol, oldId)
            var bridge = query(symbol, bridgeId)
            var replacement = query(symbol, newId)
            fun active(row: JSONObject?, id: String, stop: String, fixed: Boolean = false): Boolean =
                matches(row, symbol, direction, id, stop, if (fixed) quantity else null)

            if (!active(replacement, newId, price)) {
                check(replacement == null) { "Requested close-all stop is terminal, triggered or has conflicting attributes" }
                if (!active(bridge, bridgeId, price, true)) {
                    check(bridge == null) { "Temporary stop is terminal, triggered or has conflicting attributes" }
                    check(active(old, oldId, oldPrice)) { "Old close-all stop is not exchange-confirmed; no bridge POST allowed" }
                    val outcome = tx.optString("bridge_outcome", "NOT_SUBMITTED")
                    if (outcome in setOf("REJECTED", "NOT_SENT")) {
                        // A definite failed bridge POST left the old protection intact.
                        // Keep the error, release the transaction only after signed reads.
                        context(owner, tx)
                        requireOpen(symbol, direction, oldId, oldPrice, null)
                        check(query(symbol, bridgeId) == null && query(symbol, newId) == null)
                        tx.put("phase", "ABORTED_OLD_STOP_RETAINED").put("resolved_at_ms", clock())
                        owner.put("last_native_stop_replacement", JSONObject(tx.toString()))
                            .put("trail_retry_after_ms", clock() + 60_000L)
                            .put("protection_status", "PROTECTED")
                        owner.remove("native_stop_replacement")
                        owner.remove("native_replacement_protection")
                        persist()
                        return JSONObject().put("ok", false).put("resolved", true)
                            .put("blocker", "Temporary stop rejected; original close-all stop retained")
                    }
                    check(outcome == "NOT_SUBMITTED") { "Temporary STOP submission is uncertain; no POST replay allowed" }
                    context(owner, tx)
                    requireOpen(symbol, direction, oldId, oldPrice, null)
                    post(tx, "bridge") {
                        adapter.placeReplacementProtectiveStop(symbol, direction, price, bridgeId, quantity.toPlainString())
                    }
                    bridge = query(symbol, bridgeId)
                }
                check(active(bridge, bridgeId, price, true)) { "Temporary reduce-only stop is not exchange-confirmed at the exact quantity/trigger" }
                requireOpen(symbol, direction, bridgeId, price, quantity)
                if (active(old, oldId, oldPrice)) {
                    context(owner, tx)
                    requireOpen(symbol, direction, bridgeId, price, quantity)
                    // Persist this phase before DELETE. A lost DELETE reply is
                    // resolved by the exact parent query on the next invocation.
                    tx.put("phase", "CANCEL_OLD"); persist()
                    adapter.cancelAlgoOrder(symbol, oldId)
                    old = query(symbol, oldId)
                }
                check(matchesTerminalUntriggeredFuturesProtectiveStop(old, symbol, direction, oldId, oldPrice)) {
                    "Old close-all cancellation is not confirmed untriggered; temporary protection retained"
                }
                check(open(symbol).none { it.optString("clientAlgoId") == oldId }) {
                    "Old close-all stop remains open"
                }
                val outcome = tx.optString("final_outcome", "NOT_SUBMITTED")
                // A definite rejection is safe to retry only after a later signed
                // not-found read and cooldown. Timeouts and 5xx never grant this.
                check(outcome == "NOT_SUBMITTED" ||
                    outcome in setOf("REJECTED", "NOT_SENT") && clock() >= tx.optLong("final_retry_after_ms", Long.MAX_VALUE)) {
                    "New close-all STOP submission is uncertain or cooling down; temporary protection retained"
                }
                context(owner, tx)
                requireOpen(symbol, direction, bridgeId, price, quantity)
                check(query(symbol, newId) == null)
                post(tx, "final") { adapter.placeProtectiveStop(symbol, direction, price, newId) }
                replacement = query(symbol, newId)
            }
            check(active(replacement, newId, price)) { "New close-all stop is not exchange-confirmed at the exact trigger" }
            context(owner, tx)
            requireOpen(symbol, direction, newId, price, null)
            // Old can still exist if a previous implementation created two
            // close-all stops. Only its exact durable identity may be removed.
            if (active(old, oldId, oldPrice)) {
                tx.put("phase", "CANCEL_OLD"); persist()
                adapter.cancelAlgoOrder(symbol, oldId)
                old = query(symbol, oldId)
            }
            check(matchesTerminalUntriggeredFuturesProtectiveStop(old, symbol, direction, oldId, oldPrice)) {
                "Old stop terminal identity/child execution remains unresolved"
            }
            bridge = query(symbol, bridgeId)
            if (active(bridge, bridgeId, price, true)) {
                context(owner, tx)
                requireOpen(symbol, direction, newId, price, null)
                tx.put("phase", "CANCEL_BRIDGE"); persist()
                adapter.cancelAlgoOrder(symbol, bridgeId)
                bridge = query(symbol, bridgeId)
            }
            check(terminalBridge(bridge, symbol, direction, bridgeId, price, quantity)) {
                "Temporary stop cancellation or child execution is unresolved"
            }
            context(owner, tx)
            val finalStop = requireOpen(symbol, direction, newId, price, null)
            check(open(symbol).size == 1) { "Extra algo remains after native replacement" }
            tx.put("phase", "COMPLETED").put("resolved_at_ms", clock())
            annotateNativeStopFailureResolution(owner,tx,newId,clock())
            owner.put("protection_client_id", newId).put("protective_stop", price)
                .put("protection_status", "PROTECTED").put("trailing_at_ms", clock())
                .put("last_native_stop_replacement", JSONObject(tx.toString()))
                .put("last_confirmed_trail", JSONObject().put("reason", tx.optString("reason"))
                    .put("old_stop", oldPrice).put("new_stop", price).put("client_id", newId)
                    .put("strategy_profile", owner.optString("strategy_profile")).put("observed_at_ms", clock()))
            owner.remove("native_stop_replacement")
            owner.remove("native_replacement_protection")
            owner.remove("trail_error")
            persist()
            return JSONObject().put("ok", true).put("stop", finalStop)
        } catch (failure: Exception) {
            val evidence = JSONObject().put("at_ms", clock()).put("phase", tx.optString("phase"))
                .put("type", failure.javaClass.simpleName)
                .put("message", safeDiagnosticText(failure.message ?: failure.javaClass.simpleName).take(1000))
            if (failure is FuturesHttpException) evidence.put("http_status", failure.statusCode)
                .put("exchange_code", failure.exchangeCode ?: JSONObject.NULL)
            if (!tx.has("first_failure")) tx.put("first_failure", JSONObject(evidence.toString()))
            tx.put("last_failure", evidence).put("last_checked_at_ms", clock())
            owner.put("trail_error", evidence.getString("message"))
                .put("last_native_stop_failure", evidence)
            persist()
            return JSONObject().put("ok", false).put("blocker", evidence.getString("message"))
        }
    }

    /** Read-only proof for diagnostics while a transaction blocks ALL new risk. */
    fun protection(owner: JSONObject): JSONObject? = runCatching {
        val tx = owner.getJSONObject("native_stop_replacement")
        context(owner, tx)
        val symbol = owner.getString("symbol")
        val direction = owner.getString("direction")
        val qty = tx.getString("quantity").toBigDecimal()
        val mark = adapter.markPrice(symbol)
        open(symbol).firstOrNull { row ->
            listOf(Triple("old_id", "old_stop", false), Triple("new_id", "new_stop", false),
                Triple("bridge_id", "new_stop", true)).any { (id, price, fixed) ->
                matches(row, symbol, direction, tx.getString(id), tx.getString(price), if (fixed) qty else null) &&
                    isProtectiveStopOnCorrectSide(direction, mark.toString(), tx.getString(price))
            }
        }
    }.getOrNull()

    /** A flat snapshot alone cannot forget an uncertain POST identity. */
    fun settleFlat(owner: JSONObject): Boolean {
        val tx = owner.getJSONObject("native_stop_replacement")
        val symbol = owner.getString("symbol")
        fun requireFlat() {
            val positions = validateFuturesPositionSnapshot(adapter.positions())
            check((0 until positions.length()).map { positions.getJSONObject(it) }.none {
                it.optString("symbol") == symbol && it.getString("positionAmt").toBigDecimal().signum() != 0
            }) { "Position reopened during flat STOP cleanup" }
        }
        requireFlat()
        for ((key, stage) in listOf("old_id" to "old", "bridge_id" to "bridge", "new_id" to "final")) {
            val id = tx.getString(key)
            val observed = query(symbol, id)
            if (observed == null) {
                if (stage != "old" && tx.optString("${stage}_outcome", "NOT_SUBMITTED") in
                    setOf("NOT_SUBMITTED", "NOT_SENT", "REJECTED")) continue
                val first = tx.optLong("${stage}_flat_absent_at_ms", 0L)
                if (first <= 0L) {
                    tx.put("${stage}_flat_absent_at_ms", clock()); persist(); return false
                }
                if (clock() < first || clock() - first < 60_000L) return false
                requireFlat()
                tx.put("phase", "FLAT_CANCELLATION_FENCE"); persist()
                try { adapter.cancelAlgoOrder(symbol, id) }
                catch (failure: IOException) { if (!isDefinitiveBinanceOrderNotFound(failure)) throw failure }
                if (query(symbol, id) != null) return false
            } else {
                val price = tx.getString(if (stage == "old") "old_stop" else "new_stop")
                if (!matches(JSONObject(observed.toString()).put("algoStatus", "NEW"), symbol,
                        owner.getString("direction"), id, price,
                        if (stage == "bridge") tx.getString("quantity").toBigDecimal() else null) ||
                    observed.optString("algoStatus") !in setOf("CANCELED", "REJECTED", "EXPIRED", "FINISHED")) return false
                val child = observed.optString("actualOrderId")
                if (child.isNotBlank() && child != "0" && adapter.orderById(symbol, child)
                        .optString("status") !in ENTRY_ORDER_TERMINAL_STATUSES) return false
            }
        }
        requireFlat()
        if (open(symbol).any { it.optString("clientAlgoId") in
                setOf(tx.getString("old_id"), tx.getString("new_id"), tx.getString("bridge_id")) }) return false
        tx.put("phase", "FLAT_CLEANUP_COMPLETED").put("resolved_at_ms", clock())
        owner.put("last_native_stop_replacement", JSONObject(tx.toString()))
        owner.remove("native_stop_replacement")
        owner.remove("native_replacement_protection")
        persist()
        return true
    }

    private fun post(tx: JSONObject, stage: String, submit: () -> Unit) {
        tx.put("phase", if (stage == "bridge") "SUBMIT_BRIDGE" else "SUBMIT_FINAL")
            .put("${stage}_outcome", "SUBMISSION_UNKNOWN").put("${stage}_attempt_at_ms", clock())
        persist()
        try {
            submit()
            tx.put("${stage}_outcome", "ACKNOWLEDGED"); persist()
        } catch (failure: Exception) {
            val rejected = failure is FuturesHttpException && failure.statusCode in 400..499 &&
                failure.exchangeCode?.let { it in -1136..-1100 || it in setOf(-2014, -2015, -2019,
                    -2021, -2022, -2025, -4001, -4002, -4003, -4004, -4005, -4013, -4014, -4023,
                    -4130, -4137, -4142, -4164) } == true
            tx.put("${stage}_outcome", when {
                failure is FuturesProtectionNotSubmittedException -> "NOT_SENT"
                rejected -> "REJECTED"
                else -> "SUBMISSION_UNKNOWN"
            }).put("${stage}_retry_after_ms", clock() + 60_000L)
            persist()
            throw failure
        }
    }

    private fun context(owner: JSONObject, tx: JSONObject) {
        val symbol = owner.getString("symbol")
        validatePendingExposure(owner)
        adapter.requireOneWayMode()
        val rows = validateFuturesPositionSnapshot(adapter.positions())
        val live = (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
            it.optString("symbol") == symbol && it.getString("positionAmt").toBigDecimal().signum() != 0
        }
        check(live.size == 1) { "Native replacement position disappeared or became ambiguous" }
        val row = live.single()
        val amount = row.getString("positionAmt").toBigDecimal()
        val entry = row.optString("entryPrice").toDoubleOrNull()
        val expectedEntry = tx.getString("entry_price").toDouble()
        check(row.optString("positionSide", "BOTH") == "BOTH" &&
            (amount.signum() > 0) == (owner.getString("direction") == "LONG") &&
            amount.abs().compareTo(tx.getString("quantity").toBigDecimal()) == 0 &&
            entry != null && entry.isFinite() && expectedEntry.isFinite() && expectedEntry > 0 &&
            kotlin.math.abs(entry - expectedEntry) <= maxOf(1e-8, expectedEntry * 1e-8)) {
            "Native replacement exposure quantity/direction/entry changed"
        }
        check(adapter.openOrders(symbol).length() == 0) { "Pending ordinary orders block the native stop swap" }
        val ids = setOf(tx.getString("old_id"), tx.getString("new_id"), tx.getString("bridge_id"))
        val algos = open(symbol)
        check(algos.map { it.optString("clientAlgoId") }.distinct().size == algos.size &&
            algos.all { it.optString("symbol") == symbol && it.optString("clientAlgoId") in ids }) {
            "Pending addition, foreign or duplicate algo blocks the native stop swap"
        }
    }

    private fun query(symbol: String, id: String): JSONObject? = try {
        adapter.queryAlgoOrder(symbol, id)
    } catch (failure: IOException) {
        if (isDefinitiveBinanceOrderNotFound(failure)) null else throw failure
    }

    private fun open(symbol: String): List<JSONObject> = adapter.openAlgoOrders(symbol).let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    private fun requireOpen(symbol: String, direction: String, id: String, stop: String, qty: BigDecimal?): JSONObject {
        val rows = open(symbol).filter { it.optString("clientAlgoId") == id }
        check(rows.size == 1 && matches(rows.single(), symbol, direction, id, stop, qty) &&
            isProtectiveStopOnCorrectSide(direction, adapter.markPrice(symbol).toString(), stop)) {
            "Required STOP is not uniquely active at its exact identity/quantity/trigger"
        }
        return rows.single()
    }

    private fun matches(row: JSONObject?, symbol: String, direction: String, id: String,
        stop: String, quantity: BigDecimal?): Boolean = row != null &&
        row.optString("symbol") == symbol && row.optString("clientAlgoId") == id &&
        row.optString("positionSide", "BOTH") == "BOTH" && row.optString("workingType") == "MARK_PRICE" &&
        row.optString("orderType", row.optString("type")) == "STOP_MARKET" &&
        row.optString("side") == (if (direction == "LONG") "SELL" else "BUY") &&
        row.optString("algoId").toLongOrNull()?.let { it > 0L } == true &&
        row.optString("triggerPrice", row.optString("stopPrice")).toBigDecimalOrNull()?.compareTo(stop.toBigDecimal()) == 0 &&
        isActiveFuturesProtectionStatus(row.optString("algoStatus", row.optString("status"))) &&
        (if (quantity == null) row.optString("closePosition").equals("true", true)
         else !row.optString("closePosition").equals("true", true) && row.optString("reduceOnly").equals("true", true) &&
             row.optString("quantity").toBigDecimalOrNull()?.compareTo(quantity) == 0)

    private fun terminalBridge(row: JSONObject?, symbol: String, direction: String, id: String,
        stop: String, qty: BigDecimal): Boolean = row != null &&
        row.optString("algoStatus") in setOf("CANCELED", "EXPIRED", "REJECTED") &&
        row.has("actualOrderId") && !row.isNull("actualOrderId") && row.optString("actualOrderId") in setOf("", "0") &&
        matches(JSONObject(row.toString()).put("algoStatus", "NEW"), symbol, direction, id, stop, qty)
}

internal fun publicNativeStopReplacement(tx: JSONObject?): Any = tx?.let {
    JSONObject(it.toString()).apply { for (key in listOf("old_id", "new_id", "bridge_id")) remove(key) }
} ?: JSONObject.NULL
