package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsTc1FiveBulletsTest {
    private fun completeProxy():JSONObject=JSONObject()
        .put("label","W5").put("status","PROVISIONAL")
        .put("target_zone",true).put("terminal_fractal",true)
        .put("squat",true).put("momentum_shift",true)
        .put("divergence",true) // Course AO: NOT automatically a TC1 MFI divergence

    @Test fun courseAoDivergenceDoesNotInventTc1MfiDivergence() {
        val result=williamsTc1FiveBulletEvidence(completeProxy())
        assertEquals("MFI_DIVERGENCE_UNVERIFIED",result.getString("status"))
        assertEquals(4,result.getInt("observed_or_proxy_count"))
        assertEquals(5,result.getInt("required_count"))
        assertEquals("NOT_VERIFIED",result.getJSONObject("checks").getString("mfi_divergence"))
        assertEquals("AO_SIGNAL_PROXY_ONLY",result.getJSONObject("checks")
            .getString("profitunity_macd_momentum_turn"))
        assertFalse(result.getBoolean("can_close"))
        assertFalse(result.getBoolean("can_reverse"))
        assertFalse(result.getBoolean("can_place_orders"))
    }
    @Test fun claimedMfiWithoutDataProvenanceIsRejected() {
        val e=completeProxy()
        assertEquals("MFI_DIVERGENCE_UNVERIFIED",
            williamsTc1FiveBulletEvidence(e,true,null).getString("status"))
        assertEquals("MFI_DIVERGENCE_UNVERIFIED",
            williamsTc1FiveBulletEvidence(e,true,"BINANCE_BASE_VOLUME_PROXY").getString("status"))
        assertEquals("FIVE_SIGNS_OBSERVED_UNCERTIFIED_WAVE_DEGREE",
            williamsTc1FiveBulletEvidence(e,true,"MANUALLY_VERIFIED_TC1_CHART")
                .getString("status"))
        assertFalse(williamsTc1FiveBulletEvidence(e,true,
            "MANUALLY_VERIFIED_TC1_CHART").getBoolean("wave_degree_confirmed"))
    }
    @Test fun fifthWaveMustBeProvisionalNotRejectedOrEarlierLeg() {
        assertEquals("NO_PROVISIONAL_COMPLETED_FIVE_WAVE_PIVOT",
            williamsTc1FiveBulletEvidence(completeProxy().put("label","W4"))
                .getString("status"))
        assertEquals("NO_PROVISIONAL_COMPLETED_FIVE_WAVE_PIVOT",
            williamsTc1FiveBulletEvidence(completeProxy().put("status","INVALIDATED"))
                .getString("status"))
    }
}
