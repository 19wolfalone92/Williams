package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Independent read-only snapshot JOIN for actual open USD-M positions and
 * authenticated Binance STOP proof observations. This is NOT an execution
 * gate, order placement, protective STOP replacement or recovery authority.
 *
 * The in-memory snapshots can be captured at different moments. The join
 * requires each observed time and the exact side/qty/stop but never calls a
 * second Binance endpoint. For actual order ID binding use the raw signed ZIP.
 */
internal object WilliamsSignedStopJoinAudit {
    private const val SIGNED_POSITION_STOP_TTL_MS=15_000L
    private fun dec(value: Any?):BigDecimal? =
        value?.toString()?.takeIf { it.isNotBlank() && it!="null" }
            ?.toBigDecimalOrNull()

    private fun fresh(at:Long,now:Long):Boolean =
        at>0L && now>=at && now-at<=SIGNED_POSITION_STOP_TTL_MS

    fun inspect(status:JSONObject,coordinator:JSONObject,observedAtMs:Long):JSONObject {
        val rows=status.optJSONArray("futures_positions") ?: JSONArray()
        val proofs=coordinator.optJSONArray("exchange_protection_observations") ?: JSONArray()
        val positionAt=status.optLong("positions_snapshot_at_ms",0L)
        val positionFresh=status.optBoolean("positions_snapshot_ok",false) &&
            fresh(positionAt,observedAtMs)
        val observed=JSONArray()
        var open=0
        var matched=0
        var missing=0
        var ambiguous=0
        for(i in 0 until rows.length()) {
            val position=rows.optJSONObject(i) ?: continue
            val qty=dec(position.opt("positionAmt"))
            if(qty==null || qty.signum()==0) continue
            open++
            val symbol=position.optString("symbol").uppercase()
            val direction=position.optString("direction").uppercase()
            val positionStop=dec(position.opt("stop"))
            val candidates=(0 until proofs.length()).mapNotNull { j ->
                val row=proofs.optJSONObject(j) ?: return@mapNotNull null
                val proofQty=dec(row.opt("quantity")) ?: return@mapNotNull null
                if(row.optString("symbol").uppercase()==symbol &&
                    row.optString("direction").uppercase()==direction &&
                    proofQty.compareTo(qty)==0) row else null
            }
            val proof=candidates.singleOrNull()
            val proofAt=proof?.optLong("observed_at_ms",0L) ?: 0L
            val signedScope=proof?.optString("scope")==
                "SIGNED_GET_ONLY_DISPLAY_NOT_EXECUTION_AUTHORITY"
            val stopValue=dec(proof?.opt("stop"))
            val stopsMatch=positionStop!=null && positionStop.signum()>0 &&
                stopValue!=null && stopValue.signum()>0 &&
                stopValue.compareTo(positionStop)==0
            val strategyOwned=proof?.optBoolean("strategy_ownership_verified")==true
            val active=proof?.optString("stop_proof").orEmpty() in
                setOf("OWNED_STOP_SEEN","NATIVE_REPLACEMENT_PROTECTION_SEEN")
            val uniqueStop=proof?.optInt("signed_full_stops_seen",0)==1
            val wellFormedProof=positionFresh && candidates.size==1 &&
                fresh(proofAt,observedAtMs) && signedScope &&
                stopsMatch && strategyOwned && active && uniqueStop
            val state=when {
                !positionFresh -> "NOT_VERIFIED_POSITION_SNAPSHOT_STALE"
                candidates.isEmpty() -> "WARNING_SIGNED_STOP_PROOF_MISSING_OR_WRONG_QTY"
                candidates.size>1 -> "WARNING_AMBIGUOUS_PROTECTION_PROOFS"
                !fresh(proofAt,observedAtMs) -> "NOT_VERIFIED_STOP_PROOF_STALE"
                !signedScope -> "NOT_VERIFIED_STOP_PROOF_SOURCE"
                !stopsMatch -> "WARNING_POSITION_STOP_PRICE_MISMATCH"
                !strategyOwned -> "NOT_VERIFIED_STRATEGY_OWNERSHIP"
                !active -> "WARNING_SIGNED_ACTIVE_OWNED_STOP_ABSENT"
                !uniqueStop -> "WARNING_NONUNIQUE_SIGNED_STOP"
                wellFormedProof -> "OBSERVED_FRESH_SIGNED_PROOF_MATCH"
                else -> "NOT_VERIFIED"
            }
            if(wellFormedProof) matched++
            else if(candidates.size>1) ambiguous++
            else missing++
            if(observed.length()<25) observed.put(JSONObject()
                .put("symbol",symbol).put("direction",direction)
                .put("position_snapshot_at_ms",positionAt)
                .put("signed_stop_observed_at_ms",if(proofAt>0L) proofAt else JSONObject.NULL)
                .put("proof_count_for_signed_qty",candidates.size)
                .put("matching_side_quantity_and_stop",wellFormedProof)
                .put("strategy_owned",strategyOwned)
                .put("stop_proof_state",state)
                .put("signed_stop_client_id_present",
                    !proof?.optString("protection_client_id").isNullOrBlank())
                .put("signed_but_not_execution_authority",true))
        }
        val severity=when {
            open==0 -> "NOT_VERIFIED"
            ambiguous>0 || missing>0 -> "WARNING"
            else -> "PASS"
        }
        return JSONObject().put("schema","WILLIAMS_SIGNED_STOP_POSITION_JOIN_V1")
            .put("observed_at_ms",observedAtMs)
            .put("position_snapshot_at_ms",
                if(positionAt>0L) positionAt else JSONObject.NULL)
            .put("position_snapshot_fresh",positionFresh)
            .put("required_fresh_ms",SIGNED_POSITION_STOP_TTL_MS)
            .put("open_position_count",open).put("signed_stop_matched_count",matched)
            .put("unresolved_position_count",missing)
            .put("ambiguous_proof_count",ambiguous)
            .put("severity",severity)
            .put("positions",observed)
            .put("can_trade_or_manage_stops",false)
            .put("execution_gate_not_derived_from_join",true)
            .put("e2e_post_fill_stop_sla_certified",false)
            .put("raw_client_ids_exported_here",false)
            .put("note","Exact signed STOP client IDs are available in separate ZIP proofs. " +
                "This cached observational join never replaces fresh Binance coordinator " +
                "reconciliation, establishes stop placement at fill time or grants new entries.")
    }
}
