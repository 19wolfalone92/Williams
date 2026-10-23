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
    @Test fun pointZeroNeverCertifiedFromFiveProxyBooleansWithoutMfiAndScale() {
        val missing=williamsTc1FiveBulletEvidence(completeProxy())
        assertFalse(missing.getBoolean("wave_scale_100_140_observed"))
        assertFalse(missing.getBoolean("last_w5_pivot_causally_known"))
        assertFalse(missing.getBoolean("point_zero_certified"))
        val pivots=org.json.JSONArray()
        for(i in 0..5) pivots.put(JSONObject().put("known_at",100L+i*10L))
        val scenario=completeProxy().put("scale_bars",120).put("observed_at",160L)
            .put("anchors",pivots)
        val observed=williamsTc1FiveBulletEvidence(scenario,true,"MANUALLY_VERIFIED_TC1_CHART")
        assertTrue(observed.getBoolean("wave_scale_100_140_observed"))
        assertTrue(observed.getBoolean("last_w5_pivot_causally_known"))
        assertEquals("FIVE_SIGNS_OBSERVED_UNCERTIFIED_WAVE_DEGREE",observed.getString("status"))
        assertFalse(observed.getBoolean("point_zero_certified"))
        scenario.put("observed_at",149L)
        val impossible=williamsTc1FiveBulletEvidence(scenario,true,"MANUALLY_VERIFIED_TC1_CHART")
        assertFalse(impossible.getBoolean("last_w5_pivot_causally_known"))
        assertFalse(impossible.getBoolean("can_close"))
    }

}
