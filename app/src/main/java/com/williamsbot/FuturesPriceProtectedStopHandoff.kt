package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest

/** At most one independently signed handoff per monitor tick; rotate to
 * prevent one permanently unresolved BTC STOP starving ETH or another pair. */
internal fun nextFuturesManualHandoffCandidate(
    symbols:List<String>,cursor:Int
):Pair<String?,Int> {
    val eligible=symbols.distinct().filter { it in APPROVED_FUTURES_SYMBOLS }
    if(eligible.isEmpty()) return null to 0
    val index=cursor.coerceAtLeast(0)%eligible.size
    return eligible[index] to ((index+1)%eligible.size)
}

/**
 * Exchange-only recovery of the user's manually placed priceProtect=true
 * CONTRACT_PRICE reduceOnly STOP on an otherwise bot-operated Demo position.
 *
 * No ENTRY is reconstructed from the operator's statement. The old SL is
 * NEVER deleted before a new, MARK_PRICE, priceProtect=false close-all SL is
 * confirmed by both signed Binance order books. Every mutation is journaled
 * beforehand; transport-unknown POST is never resent without a signed
 * terminal/non-triggered verdict. All operations use the Demo adapter.
 *
 * This handoff does NOT authorize an entry, reversal, trailing, or market close.
 */
internal class FuturesPriceProtectedStopHandoff(
    private val adapter: BinanceUsdMFuturesTestnetAdapter,
    private val store: FuturesCampaignStateStore,
    private val clock: () -> Long,
    private val accountFingerprint: String
) {
    private data class Snapshot(
        val position: JSONObject,
        val quantity: BigDecimal,
        val direction: String,
        val entry: BigDecimal,
        val mark: Double,
        val contract: Double,
        val algos: List<JSONObject>
    )

    private fun status(value:String, mutation:Boolean=false) =
        JSONObject().put("status",value).put("mutation_attempted",mutation)
            .put("entry_authorized",false).put("market_close_authorized",false)
            .put("strategy_ownership_verified",false)

    private fun persist(key:String,record:JSONObject) {
        check(store.writeState(key,record.toString())) {
            "HANDOFF_SQLITE_WRITE_FAILED_BEFORE_FINANCIAL_MUTATION"
        }
    }

    private fun snapshot(symbol:String): Snapshot {
        adapter.requireOneWayMode()
        adapter.account() // signed authentication/identity at every attempt
        val first=validateFuturesPositionSnapshot(adapter.positions())
        val matching=(0 until first.length()).mapNotNull { first.optJSONObject(it) }
            .filter { it.optString("symbol")==symbol &&
                it.optString("positionAmt").toBigDecimalOrNull()?.signum()!=0 }
        check(matching.size==1) { "$symbol HANDOFF_SIGNED_LIVE_POSITION_NOT_UNIQUE" }
        val position=matching.single()
        val amount=position.optString("positionAmt").toBigDecimalOrNull()
            ?: error("$symbol HANDOFF_SIGNED_POSITION_QTY_INVALID")
        val entry=position.optString("entryPrice").toBigDecimalOrNull()
            ?: error("$symbol HANDOFF_SIGNED_ENTRY_INVALID")
        check(position.optString("positionSide")=="BOTH" &&
            entry.signum()>0 && amount.signum()!=0) {
            "$symbol HANDOFF_SIGNED_POSITION_MODE_OR_PRICE_INVALID"
        }
        val direction=if(amount.signum()>0) "LONG" else "SHORT"
        val mark=adapter.markPrice(symbol)
        val contract=adapter.contractPrice(symbol)
        check(mark.isFinite() && mark>0 && contract.isFinite() && contract>0) {
            "$symbol HANDOFF_MARK_CONTRACT_QUOTE_UNVERIFIED"
        }
        val scopedStandard=adapter.openOrders(symbol)
        val accountStandard=adapter.allOpenOrders()
        check(scopedStandard.length()==0) { "$symbol HANDOFF_OPEN_STANDARD_ORDER_CONFLICT" }
        for(i in 0 until accountStandard.length()) {
            val row=accountStandard.optJSONObject(i) ?: error("HANDOFF_MALFORMED_ACCOUNT_STANDARD_ORDER")
            check(row.optString("symbol").isNotBlank() &&
                row.optString("symbol")!=symbol) {
                "$symbol HANDOFF_ACCOUNT_STANDARD_ORDER_CONFLICT"
            }
        }
        val scopedAlgos=adapter.openAlgoOrders(symbol)
        val accountAlgos=adapter.allOpenAlgoOrders()
        val rows=(0 until scopedAlgos.length()).map { index ->
            scopedAlgos.optJSONObject(index)
                ?: error("$symbol HANDOFF_MALFORMED_SCOPED_ALGO")
        }
        check(rows.all { it.optString("symbol")==symbol }) {
            "$symbol HANDOFF_WRONG_SCOPED_ALGO_SYMBOL"
        }
        val accountRows=ArrayList<JSONObject>()
        for(i in 0 until accountAlgos.length()) {
            val row=accountAlgos.optJSONObject(i) ?: error("$symbol HANDOFF_MALFORMED_ACCOUNT_ALGO")
            check(row.optString("symbol").isNotBlank()) { "$symbol HANDOFF_ACCOUNT_ALGO_SYMBOL_MISSING" }
            if(row.optString("symbol")==symbol) accountRows.add(row)
        }
        check(rows.size==accountRows.size &&
            rows.map { signedExternalContractStopFingerprint(it) }.sorted() ==
                accountRows.map { signedExternalContractStopFingerprint(it) }.sorted()) {
            "$symbol HANDOFF_SIGNED_ALGO_BOOKS_DIFFER"
        }
        val second=validateFuturesPositionSnapshot(adapter.positions())
        check(futuresSignedExposureStable(first,second)) {
            "$symbol HANDOFF_SIGNED_EXPOSURE_CHANGED_DURING_READ"
        }
        return Snapshot(position,amount.abs(),direction,entry,mark,contract,rows)
    }

    private fun fingerprint(old:JSONObject)=signedExternalContractStopFingerprint(old)

    private fun newId(symbol:String,oldAlgoId:String,sequence:Int):String {
        check(sequence in 1..100_000)
        val value="$symbol|$oldAlgoId|manual-price-protected-stop-handoff|$sequence"
        val hash=MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).take(9)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "W2H_${symbol.take(10)}_$hash"
    }

    private fun manualStop(s:Snapshot):JSONObject? {
        if(s.algos.size!=1) return null
        val row=s.algos.single()
        return row.takeIf {
            // A STOP already spawning an actual matching-engine child MUST
            // never be treated as a resting manual STOP safe to cancel.
            it.has("actualOrderId") && !it.isNull("actualOrderId") &&
                it.optString("actualOrderId") in setOf("","0") &&
                isSignedPriceProtectedExternalContractStopForObservation(
                    it,s.position.getString("symbol"),s.direction,s.quantity,s.contract)
        }
    }

    private fun samePosition(s:Snapshot,tx:JSONObject):Boolean {
        val expectedQty=tx.optString("quantity").toBigDecimalOrNull() ?: return false
        val expectedEntry=tx.optString("entry").toBigDecimalOrNull() ?: return false
        return s.position.optString("symbol")==tx.optString("symbol") &&
            s.direction==tx.optString("direction") &&
            s.quantity.compareTo(expectedQty)==0 && s.entry.compareTo(expectedEntry)==0
    }

    private fun validNewStop(s:Snapshot,tx:JSONObject,order:JSONObject):Boolean {
        val expected=tx.optString("trigger").toBigDecimalOrNull() ?: return false
        return order.optString("clientAlgoId")==tx.optString("new_id") &&
            isFullPositionFuturesStop(order,tx.getString("symbol"),s.direction,s.quantity,s.mark) &&
            order.optString("triggerPrice").ifBlank { order.optString("stopPrice") }
                .toBigDecimalOrNull()?.compareTo(expected)==0
    }

    fun advance(symbol:String):JSONObject {
        require(symbol in APPROVED_FUTURES_SYMBOLS && accountFingerprint.isNotBlank())
            { "HANDOFF_ACCOUNT_OR_SYMBOL_NOT_PINNED" }
        val key="manual_price_protected_stop_handoff_v1_$symbol"
        val existing=store.readState(key)?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        if(existing!=null && existing.optString("account_fingerprint")!=accountFingerprint)
            return status("HANDOFF_ACCOUNT_IDENTITY_CHANGED_NO_MUTATION")
        val s=snapshot(symbol)
        val now=clock()
        check(now>0L) { "$symbol HANDOFF_CLOCK_INVALID" }
        var tx=existing
        if(existing?.optString("phase")=="DONE") {
            // DONE is a historical completed campaign, not a perpetual
            // lock on ETHUSDT. If Binance later shows a UNIQUE new manual
            // STOP on a different exposure, preserve the exact old DONE
            // record in a separate journal key BEFORE replacing the active
            // handoff journal. Never manufacture strategy ENTRY ownership.
            val stillProtected=samePosition(s,existing) && s.algos.size==1 &&
                validNewStop(s,existing,s.algos.single())
            if(stillProtected) return status("ALREADY_EXCHANGE_PROTECTED")
            val later=manualStop(s)
                ?: return status("HISTORICAL_HANDOFF_CHANGED")
            val laterId=later.optString("algoId")
            if(laterId.isBlank() || laterId==existing.optString("old_algo_id") ||
                laterId==existing.optString("new_algo_id"))
                return status("HISTORICAL_HANDOFF_IDENTITY_REUSED")
            val archiveKey="manual_price_protected_stop_handoff_archive_v1_${symbol}_${existing.optString("old_algo_id")}"
            val historical=existing.toString()
            val archived=store.readState(archiveKey)
            if(archived!=null && archived!=historical)
                return status("HISTORICAL_HANDOFF_ARCHIVE_CONFLICT")
            if(archived==null) persist(archiveKey,existing)
            // A crash before preparing the next journal retains DONE and
            // an idempotent archive. The next run can safely retry.
            tx=null
        }

        if(tx==null) {
            val old=manualStop(s) ?: return status("NOT_UNIQUE_SIGNED_MANUAL_PRICE_PROTECTED_STOP")
            val trigger=old.optString("triggerPrice").ifBlank { old.optString("stopPrice") }
            check(isProtectiveStopOnCorrectSide(s.direction,s.mark.toString(),trigger)) {
                "$symbol HANDOFF_MANUAL_TRIGGER_UNSAFE_ON_MARK"
            }
            tx=JSONObject()
                .put("account_fingerprint",accountFingerprint)
                .put("symbol",symbol).put("direction",s.direction)
                .put("quantity",s.quantity.stripTrailingZeros().toPlainString())
                .put("entry",s.entry.stripTrailingZeros().toPlainString())
                .put("old_algo_id",old.getString("algoId"))
                .put("old_client_id",old.getString("clientAlgoId"))
                .put("old_fingerprint",fingerprint(old))
                .put("trigger",trigger)
                .put("generation",1)
                .put("new_id",newId(symbol,old.getString("algoId"),1))
                .put("phase","PREPARED")
                .put("prepared_at_ms",now)
            persist(key,tx)
        }

        if(!samePosition(s,tx)) return status("SIGNED_POSITION_CHANGED_NO_MUTATION")
        val old=s.algos.singleOrNull {
            it.optString("algoId")==tx.optString("old_algo_id") &&
                it.optString("clientAlgoId")==tx.optString("old_client_id") &&
                fingerprint(it)==tx.optString("old_fingerprint")
        }
        val newest=s.algos.singleOrNull { it.optString("clientAlgoId")==tx.optString("new_id") }
        val validNew=newest?.let { validNewStop(s,tx,it) }==true
        val other=s.algos.filter { row ->
            row.optString("clientAlgoId") !in setOf(tx.optString("old_client_id"),tx.optString("new_id"))
        }
        if(other.isNotEmpty() || s.algos.size > 2) return status("UNEXPECTED_SIGNED_ALGO_NO_MUTATION")
        if(newest!=null && !validNew) return status("NEW_STOP_SIGNED_BUT_INVALID_NO_CANCEL")

        if(!validNew && tx.optString("phase") in setOf(
                "POST_OUTCOME_UNKNOWN","POST_ACKNOWLEDGED","NEW_STOP_CONFIRMED")) {
            // NEVER replay an uncertain financial POST. Only an exact signed
            // terminal and untriggered former NEW order permits a distinct ID.
            val last=runCatching { adapter.queryAlgoOrder(symbol,tx.getString("new_id")) }.getOrNull()
            val explicitTerminal=old!=null && last!=null &&
                matchesTerminalUntriggeredFuturesProtectiveStop(
                    last,symbol,s.direction,tx.getString("new_id"),tx.getString("trigger"))
            if(explicitTerminal) {
                val retries=tx.optInt("generation",0)
                if(retries>=100_000) return status("HANDOFF_RETRY_LIMIT")
                val prior=tx.optLong("post_started_at_ms",0L)
                if(prior<=0L || now<prior || now-prior<minOf(300_000L,
                        5_000L*(1L shl minOf(retries,6)))) return status("WAIT_EXACT_TERMINAL_RETRY_BACKOFF")
                tx.put("last_terminal_rejected_id",tx.getString("new_id"))
                    .put("last_terminal_status",last.optString("algoStatus"))
                    .put("generation",retries+1)
                    .put("new_id",newId(symbol,tx.getString("old_algo_id"),retries+1))
                    .put("phase","PREPARED")
                    .put("prepared_at_ms",now)
                persist(key,tx)
                return status("NEW_SIGNED_TERMINAL_RETRY_PREPARED")
            }
            return status("POST_OUTCOME_UNKNOWN_SIGNED_GET_RETRY_ONLY")
        }

        if(!validNew && tx.optString("phase")=="PREPARED") {
            if(old==null || s.algos.size!=1 ||
                manualStop(s)?.optString("algoId")!=tx.optString("old_algo_id") ||
                !isProtectiveStopOnCorrectSide(s.direction,s.mark.toString(),tx.optString("trigger")))
                return status("MANUAL_STOP_CHANGED_OR_TRIGGER_CROSSED_NO_POST")
            // Snapshot/journal preparation can be separated from POST by
            // network latency, process pauses, or a concurrent Binance fill.
            // Repeat two fresh account-wide signed position reads and BOTH
            // order books IMMEDIATELY before changing exchange state.
            val beforePost=snapshot(symbol)
            if(!samePosition(beforePost,tx) || beforePost.algos.size!=1 ||
                manualStop(beforePost)?.let {
                    it.optString("algoId")==tx.optString("old_algo_id") &&
                        it.optString("clientAlgoId")==tx.optString("old_client_id") &&
                        fingerprint(it)==tx.optString("old_fingerprint")
                }!=true ||
                !isProtectiveStopOnCorrectSide(
                    beforePost.direction,beforePost.mark.toString(),tx.optString("trigger"))) {
                return status("SIGNED_STOP_OR_POSITION_DRIFTED_BEFORE_POST")
            }
            // Snapshot, journal and PREPARED identity are independently
            // persisted before the irreversible POST. Never submit again
            // unless a genuinely unsubmitted preflight is re-observed.
            tx.put("phase","POST_OUTCOME_UNKNOWN")
                .put("post_started_at_ms",now)
            persist(key,tx)
            try {
                adapter.placeProtectiveStop(symbol,s.direction,
                    tx.getString("trigger"),tx.getString("new_id"))
                tx.put("phase","POST_ACKNOWLEDGED")
                persist(key,tx)
            } catch(failure:Exception) {
                if(failure is FuturesProtectionNotSubmittedException) {
                    // Adapter promises no POST was transmitted (local rejection).
                    tx.put("phase","PREPARED")
                        .put("last_preflight_blocker",safeDiagnosticText(
                            failure.message ?: failure.javaClass.simpleName))
                    persist(key,tx)
                    return status("STOP_PREFLIGHT_NOT_SUBMITTED")
                }
                return status("STOP_POST_UNCONFIRMED_MANUAL_STOP_PRESERVED",true)
            }
            // Validate the fresh read-back rather than trusting POST ACK.
            return finalizeIfReady(key,tx,symbol)
        }
        if(validNew) return finalizeIfReady(key,tx,symbol)
        return status("HANDOFF_PENDING_NO_NEW_PROTECTIVE_PROOF")
    }

    private fun finalizeIfReady(key:String,tx:JSONObject,symbol:String):JSONObject {
        val verified=snapshot(symbol)
        if(!samePosition(verified,tx)) return status("POSITION_CHANGED_AFTER_POST")
        val new=verified.algos.singleOrNull { it.optString("clientAlgoId")==tx.optString("new_id") &&
            validNewStop(verified,tx,it) }
            ?: return status("NEW_STOP_NOT_YET_SIGNED_CONFIRMED")
        val old=verified.algos.singleOrNull { it.optString("algoId")==tx.optString("old_algo_id") &&
            it.optString("clientAlgoId")==tx.optString("old_client_id") &&
            fingerprint(it)==tx.optString("old_fingerprint") }
        if(verified.algos.size != (if(old==null) 1 else 2))
            return status("FOREIGN_ORDER_CHANGED_AFTER_NEW_STOP")
        tx.put("phase","NEW_STOP_CONFIRMED")
            .put("new_algo_id",new.optString("algoId"))
            .put("new_signed_confirmed_at_ms",clock())
        persist(key,tx)
        if(old!=null) {
            // Only cancel the exact old manual order while a signed NEW
            // stop and the same signed position remain present.
            tx.put("old_cancel_requested_at_ms",clock())
            persist(key,tx)
            try { adapter.cancelAlgoOrderById(symbol,old.getString("algoId")) }
            catch(_:Exception) {
                return status("OLD_CANCEL_UNCERTAIN_RECHECK_BEFORE_RETRY",true)
            }
        }
        val final=snapshot(symbol)
        if(!samePosition(final,tx) || final.algos.size!=1 ||
            !validNewStop(final,tx,final.algos.single()))
            return status("POST_CANCEL_PROOF_INCOMPLETE_HOLD",old!=null)
        tx.put("phase","DONE").put("completed_at_ms",clock())
        persist(key,tx)
        return status("NEW_UNRESTRICTED_MARK_PRICE_STOP_SIGNED_CONFIRMED",old!=null)
            .put("stop",tx.getString("trigger"))
            .put("protection_client_id",tx.getString("new_id"))
            .put("signed_exchange_verified",true)
    }
}
