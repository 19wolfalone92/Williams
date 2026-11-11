package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsSignedStopJoinAuditTest {
    private val now=1_800_000_000_000L
    private fun position(qty:String="-0.506",stop:Double=232.95)=JSONObject()
        .put("symbol","BCHUSDT").put("direction","SHORT")
        .put("positionAmt",qty).put("stop",stop)
        .put("exchange_confirmed",true)
        .put("stop_verification_status","OWNED_STOP_SEEN")
        .put("strategy_ownership_verified",true)
    private fun proof(qty:String="-0.506",stop:String="232.95",at:Long=now-1_000)=JSONObject()
        .put("symbol","BCHUSDT").put("direction","SHORT")
        .put("quantity",qty).put("stop",stop)
        .put("observed_at_ms",at)
        .put("stop_proof","OWNED_STOP_SEEN")
        .put("strategy_ownership_verified",true)
        .put("protection_client_id","PRIVATE-ALGO-ID-NOT-FOR-REPORT")
        .put("management_mode","STRATEGY_OWNED")

    private fun status(row:JSONObject=position(),at:Long=now-2_000):JSONObject =
        JSONObject().put("futures_positions",JSONArray().put(row))
            .put("positions_snapshot_at_ms",at)
            .put("positions_snapshot_ok",true)

    @Test fun exactRealDeviceSignedBchShortStopMatchesIndependentSnapshots() {
        val out=WilliamsSignedStopJoinAudit.inspect(status(),JSONObject()
            .put("exchange_protection_observations",JSONArray().put(proof())),now)
        assertEquals("PASS",out.getString("severity"))
        assertEquals(1,out.getInt("open_position_count"))
        assertEquals(1,out.getInt("signed_stop_matched_count"))
        assertEquals(0,out.getInt("unresolved_position_count"))
        assertEquals("OBSERVED_FRESH_SIGNED_PROOF_MATCH",out.getJSONArray("positions")
            .getJSONObject(0).getString("stop_proof_state"))
        assertFalse(out.getBoolean("can_trade_or_manage_stops"))
        assertFalse(out.getBoolean("e2e_post_fill_stop_sla_certified"))
        assertFalse(out.toString().contains("PRIVATE-ALGO-ID-NOT-FOR-REPORT"))
    }

    @Test fun missingOrWrongQuantityStopCannotBeGreen() {
        val wrong=WilliamsSignedStopJoinAudit.inspect(status(),JSONObject()
            .put("exchange_protection_observations",
                JSONArray().put(proof(qty="-0.505"))),now)
        assertEquals("WARNING",wrong.getString("severity"))
        assertEquals(1,wrong.getInt("unresolved_position_count"))
        val stopPrice=WilliamsSignedStopJoinAudit.inspect(status(),JSONObject()
            .put("exchange_protection_observations",
                JSONArray().put(proof(stop="233.05"))),now)
        assertEquals("WARNING",stopPrice.getString("severity"))
        assertEquals("WARNING_POSITION_STOP_PRICE_MISMATCH",stopPrice.getJSONArray("positions")
            .getJSONObject(0).getString("stop_proof_state"))
    }

    @Test fun staleOrUnverifiedSignedPositionSnapshotNeverPasses() {
        val results=listOf(
            WilliamsSignedStopJoinAudit.inspect(status(at=now-20_000),JSONObject()
                .put("exchange_protection_observations",JSONArray().put(proof())),now),
            WilliamsSignedStopJoinAudit.inspect(status(),JSONObject()
                .put("exchange_protection_observations",
                    JSONArray().put(proof(at=now-20_000))),now)
        )
        assertTrue(results.all { it.getString("severity")=="WARNING" })
        assertTrue(results.all { it.getInt("signed_stop_matched_count")==0 })
    }

    @Test fun multipleSignedStopsOrUnknownStrategyOwnerMustNotAuthorizeAnything() {
        val pair=JSONArray().put(proof()).put(proof())
        val duplicate=WilliamsSignedStopJoinAudit.inspect(status(),JSONObject()
            .put("exchange_protection_observations",pair),now)
        assertEquals(1,duplicate.getInt("ambiguous_proof_count"))
        assertEquals("WARNING",duplicate.getString("severity"))
        val outsider=WilliamsSignedStopJoinAudit.inspect(status(),JSONObject()
            .put("exchange_protection_observations",JSONArray()
                .put(proof().put("strategy_ownership_verified",false))),now)
        assertEquals("WARNING",outsider.getString("severity"))
        assertEquals("NOT_VERIFIED_STRATEGY_OWNERSHIP",
            outsider.getJSONArray("positions").getJSONObject(0).getString("stop_proof_state"))
    }

    @Test fun zeroLiveExposureIsNotDemoExecutionCertification() {
        val out=WilliamsSignedStopJoinAudit.inspect(JSONObject()
            .put("futures_positions",JSONArray()),JSONObject(),now)
        assertEquals("NOT_VERIFIED",out.getString("severity"))
        assertEquals(0,out.getInt("open_position_count"))
        assertFalse(out.getBoolean("e2e_post_fill_stop_sla_certified"))
    }

    @Test fun productionSignedStopProofDoesNotContainInventedScopeOrSignedCountFields() {
        val actual=proof()
        assertFalse(actual.has("scope"))
        assertFalse(actual.has("signed_full_stops_seen"))
        val out=WilliamsSignedStopJoinAudit.inspect(status(),JSONObject()
            .put("state","OPEN_PROTECTED")
            .put("exchange_protection_observations",JSONArray().put(actual)),now)
        assertEquals("PASS",out.getString("severity"))
        assertEquals("NATIVE_COORDINATOR_SIGNED_GET_OPEN_ALGO_ORDERS",
            out.getString("proof_origin"))
    }

    @Test fun mixedCoordinatorStateOrMissingProtectionOwnerCannotPassJoin() {
        val signed=JSONObject().put("state","OPEN_PROTECTED")
            .put("reconciliation_running",true)
            .put("exchange_protection_observations",JSONArray().put(proof()))
        val out=WilliamsSignedStopJoinAudit.inspect(status(),signed,now)
        assertEquals("WARNING",out.getString("severity"))
        assertEquals("NOT_VERIFIED_COORDINATOR_RECONCILING",
            out.getJSONArray("positions").getJSONObject(0).getString("stop_proof_state"))
        signed.put("reconciliation_running",false)
        val display=status(position().put("strategy_ownership_verified",false))
        val mis=WilliamsSignedStopJoinAudit.inspect(display,signed,now)
        assertEquals("NOT_VERIFIED_DISPLAY_AND_PROOF_NOT_SAME_GENERATION",
            mis.getJSONArray("positions").getJSONObject(0).getString("stop_proof_state"))
    }
}
