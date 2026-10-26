package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoRecoveredPositionPolicyTest {
    private val now=1_800_000_000_000L
    private fun position()=JSONObject().put("symbol","AVAXUSDT")
        .put("positionSide","BOTH").put("positionAmt","-20")
        .put("entryPrice","8.867")
    private fun signedStop()=JSONObject().put("symbol","AVAXUSDT")
        .put("direction","SHORT").put("quantity","-20.0")
        .put("stop_proof","EXTERNAL_PROTECTED")
        .put("management_mode","OBSERVE_ONLY")
        .put("strategy_ownership_verified",false)
        .put("observed_at_ms",now-1_000L)
        .put("protection_client_id","W2PS_AVAX_after_recovery")
        .put("stop","8.965")

    @Test fun signedUnownedShortMayBeReviewedButDoesNotProveStrategyOwnership() {
        assertNull(autonomousRecoveredStopCandidateBlocker(position(),signedStop(),now))
        assertEquals("STOP_NOT_EXCHANGE_PROTECTED_OR_ALREADY_MANAGED",
            autonomousRecoveredStopCandidateBlocker(position(),
                signedStop().put("strategy_ownership_verified",true),now))
        assertEquals("STOP_NOT_EXCHANGE_PROTECTED_OR_ALREADY_MANAGED",
            autonomousRecoveredStopCandidateBlocker(position(),
                signedStop().put("management_mode","AUTO_VERIFIED_STOP_ONLY"),now))
    }

    @Test fun staleWrongSideWrongSizeOrUnidentifiedStopMustNeverBeAdopted() {
        assertEquals("SIGNED_STOP_PROOF_STALE",
            autonomousRecoveredStopCandidateBlocker(position(),
                signedStop().put("observed_at_ms",now-25_000L),now))
        assertEquals("SIGNED_STOP_DIRECTION_MISMATCH",
            autonomousRecoveredStopCandidateBlocker(position(),
                signedStop().put("direction","LONG"),now))
        assertEquals("SIGNED_STOP_COVERAGE_MISMATCH",
            autonomousRecoveredStopCandidateBlocker(position(),
                signedStop().put("quantity","19"),now))
        assertEquals("SIGNED_STOP_IDENTITY_UNKNOWN",
            autonomousRecoveredStopCandidateBlocker(position(),
                signedStop().put("protection_client_id",""),now))
        assertEquals("SIGNED_HEDGE_MODE_NOT_SUPPORTED",
            autonomousRecoveredStopCandidateBlocker(position().put("positionSide","SHORT"),
                signedStop(),now))
        assertEquals("SIGNED_POSITION_FLAT",
            autonomousRecoveredStopCandidateBlocker(position().put("positionAmt","0"),
                signedStop(),now))
    }
}
