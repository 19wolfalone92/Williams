package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostics 25: signed external STOP and authorized stop-only supervision
 * must be differentiated from TC2 strategy-owned campaign management.
 */
class ObservedStopCanTrailTest {
    private fun proof(kind:String, mode:String, owned:Boolean, authorized:Boolean): JSONObject =
        JSONObject().put("stop_proof",kind)
            .put("management_mode",mode)
            .put("strategy_ownership_verified",owned)
            .put("automatic_trailing_authorized",authorized)

    @Test fun approvedExternalStopOnlySupervisionCanTrailWithoutCampaignOwnership() {
        val p=proof("EXTERNAL_PROTECTED","OPERATOR_APPROVED_STOP_ONLY",false,true)
        assertTrue(williamsObservedStopCanTrail(p))
        assertFalse(p.getBoolean("strategy_ownership_verified"))
    }
    @Test fun strategyOwnedSignedStopCanTrail() {
        assertTrue(williamsObservedStopCanTrail(
            proof("OWNED_STOP_SEEN","STRATEGY_OWNED",true,true)))
    }
    @Test fun oldUnknownOrUnapprovedStopsNeverGrantTrailing() {
        assertFalse(williamsObservedStopCanTrail(null))
        assertFalse(williamsObservedStopCanTrail(JSONObject()))
        assertFalse(williamsObservedStopCanTrail(
            proof("STOP_UNCONFIRMED","OPERATOR_APPROVED_STOP_ONLY",false,true)))
        assertFalse(williamsObservedStopCanTrail(
            proof("EXTERNAL_PROTECTED","OBSERVE_ONLY",false,true)))
        assertFalse(williamsObservedStopCanTrail(
            proof("EXTERNAL_PROTECTED","OPERATOR_APPROVED_STOP_ONLY",false,false)))
        assertFalse(williamsObservedStopCanTrail(
            proof("EXTERNAL_PROTECTED","OPERATOR_APPROVED_STOP_ONLY",true,true)))
        assertFalse(williamsObservedStopCanTrail(
            proof("OWNED_STOP_SEEN","STRATEGY_OWNED",false,true)))
    }
}
