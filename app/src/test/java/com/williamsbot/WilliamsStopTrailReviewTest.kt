package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsStopTrailReviewTest {
    private val hour=3_600_000L
    private val now=180*hour
    private fun h1(dir:String):List<WilliamsWaveBar> = (0 until 180).map { i ->
        val low=100.0+0.03*i
        val v=WilliamsWaveBar(i*hour,low+1.0,low+3.0,low,low+2.0,1.0)
        if(dir=="LONG") v else WilliamsWaveBar(v.t,250-v.o,250-v.l,250-v.h,250-v.c,1.0)
    }
    private fun h4(dir:String):List<WilliamsWaveBar> = (0 until 45).map { i ->
        val low=95.0+0.2*i
        val v=WilliamsWaveBar(i*4*hour,low+1.0,low+5.0,low,low+3.0,2.0)
        if(dir=="LONG") v else WilliamsWaveBar(v.t,250-v.o,250-v.l,250-v.h,250-v.c,2.0)
    }
    private fun review(side:String,opened:Long=174*hour,
        h1Bars:List<WilliamsWaveBar> = h1(side),
        h4Bars:List<WilliamsWaveBar> = h4(side),
        mark:Double = if(side=="LONG")120.0 else 80.0):WilliamsStopTrailReview =
        reviewWilliamsTc2StopTightening(
            side,if(side=="LONG")90.0 else 160.0,
            if(side=="LONG")110.0 else 140.0,
            if(side=="LONG")90.0 else 160.0,
            mark,0.01,opened,now,h1Bars,h4Bars,0.0035
        )

    @Test fun bothSidesNeedAFullPostEntryFiveBarH1Sequence() {
        for(side in listOf("LONG","SHORT")) {
            val premature=review(side,opened=178*hour)
            assertNull(premature.proposal)
            assertEquals("FIVE_CLOSED_H1_BARS_AFTER_ENTRY_NOT_YET_AVAILABLE",premature.blocker)
            assertTrue(premature.evidence.getBoolean("existing_exchange_stop_retained_if_blocked"))
            val allowed=review(side)
            assertNotNull(allowed.proposal)
            assertTrue(allowed.proposal!!.reason.startsWith("TC2_LAST_5_CLOSED_BARS"))
            assertEquals("PROPOSE_TIGHTEN_ONLY",allowed.evidence.getString("decision"))
            assertTrue(allowed.evidence.getBoolean("five_full_h1_bars_after_entry"))
            assertFalse(allowed.evidence.getBoolean("wave_hypotheses_can_authorize_orders"))
            assertEquals(5,allowed.evidence.getJSONArray("h1_last_five_closed").length())
            assertEquals(2,allowed.evidence.getJSONArray("h4_last_two_closed").length())
            assertEquals(12,allowed.evidence.getJSONArray("h1_last_twelve_closed").length())
            assertEquals(12,allowed.evidence.getJSONArray("h4_last_twelve_closed").length())
            val h1Row=allowed.evidence.getJSONArray("h1_last_twelve_closed").getJSONObject(0)
            val h4Row=allowed.evidence.getJSONArray("h4_last_twelve_closed").getJSONObject(0)
            for(row in listOf(h1Row,h4Row)) {
                assertTrue(row.has("open_time_ms"))
                for(field in listOf("open","high","low","close","volume"))
                    assertTrue("Missing "+field,row.has(field))
            }
            assertTrue(allowed.evidence.getDouble("h4_price_retracement_fraction") in 0.0..1.0)
            assertFalse(allowed.evidence.getBoolean("h4_retracement_is_confirmed_elliott_correction"))
            assertTrue(allowed.evidence.has("h4_confirmed_fractal_pivots"))
            assertTrue(allowed.evidence.getDouble("incremental_risk_reduction_per_unit")>=0)
        }
    }

    @Test fun developingAdverseH4PriceBarsBlockNonfractalTighteningLongAndShort() {
        for(side in listOf("LONG","SHORT")) {
            val initial=h4(side)
            val last=initial.last()
            val adverse=if(side=="LONG") {
                WilliamsWaveBar(last.t,last.o-1,last.h-1,last.l-3,last.c-3)
            } else {
                WilliamsWaveBar(last.t,last.o+1,last.h+3,last.l+1,last.c+3)
            }
            val outcome=review(side,h4Bars=initial.dropLast(1)+adverse)
            assertNull(outcome.proposal)
            assertEquals("H4_CORRECTION_CAUTION_RETAIN_EXCHANGE_STOP",outcome.blocker)
            assertTrue(outcome.evidence.getBoolean("h4_adverse_price_structure"))
        }
    }

    @Test fun staleOrGappedH4IsNeverInventedAsConfirmedWaveContext() {
        for(side in listOf("LONG","SHORT")) {
            assertEquals("CLOSED_H4_HISTORY_NOT_VERIFIED",
                review(side,h4Bars=h4(side).dropLast(4)).blocker)
            val malformed=h4(side).mapIndexed { i,b ->
                if(i==20) b.copy(t=b.t+4*hour) else b
            }
            assertEquals("CLOSED_H4_HISTORY_NOT_VERIFIED",
                review(side,h4Bars=malformed).blocker)
        }
    }

    @Test fun nearbyStopInsideNormalH1NoiseIsNotSubmitted() {
        val series=h1("LONG")
        val minLow=series.takeLast(5).minOf { it.l }
        // The old 0.025% hard-coded gap accepted this proposed 5-bar stop.
        // The H1 closed range bounded noise filter now rejects it.
        val nearMark=minLow-0.01+0.06
        val outcome=review("LONG",mark=nearMark)
        assertEquals("CANDIDATE_STOP_INSIDE_H1_PRICE_NOISE",outcome.blocker)
        assertNull(outcome.proposal)
    }

    @Test fun signedPositionQuantityScalesIndicativeWholeCampaignRiskForBothSides() {
        for(side in listOf("LONG","SHORT")) {
            val original = review(side)
            assertNotNull(original.proposal)
            val qty=if(side=="LONG") 0.025 else 1449.0
            val outcome = reviewWilliamsTc2StopTightening(
                side,if(side=="LONG")90.0 else 160.0,
                if(side=="LONG")110.0 else 140.0,
                if(side=="LONG")90.0 else 160.0,
                if(side=="LONG")120.0 else 80.0,
                0.01,174*hour,now,h1(side),h4(side),0.0035,campaignQuantity=qty)
            assertNotNull(outcome.proposal)
            val evidence=outcome.evidence
            assertTrue(evidence.getBoolean("campaign_qty_exchange_verified"))
            assertEquals(qty,evidence.getDouble("campaign_signed_exchange_quantity_abs"),1e-12)
            assertEquals(evidence.getDouble("new_estimated_net_per_unit")*qty,
                evidence.getDouble("new_estimated_net_campaign_usdt"),1e-9)
            assertEquals(evidence.getDouble("incremental_risk_reduction_per_unit")*qty,
                evidence.getDouble("incremental_stop_risk_reduction_campaign_usdt"),1e-9)
            assertFalse(evidence.getBoolean("net_estimate_includes_historical_addition_fees"))
            assertFalse(evidence.getBoolean("net_estimate_includes_future_fill_impact"))
        }
    }

    @Test fun invalidQuantityNeverBecomesCertifiedCampaignEconomics() {
        val outcome=reviewWilliamsTc2StopTightening(
            "LONG",90.0,110.0,90.0,120.0,0.01,
            174*hour,now,h1("LONG"),h4("LONG"),campaignQuantity=Double.NaN)
        assertNull(outcome.proposal)
        assertEquals("INVALID_EXCHANGE_CAMPAIGN_QUANTITY",outcome.blocker)
    }

    @Test fun explicitEstimatedOutcomeIsNotMisrepresentedAsAssuredProfit() {
        val accepted=review("LONG")
        assertNotNull(accepted.proposal)
        assertFalse(accepted.evidence.getBoolean("net_estimate_includes_funding"))
        assertTrue(accepted.evidence.getDouble("new_estimated_net_per_unit") >
            accepted.evidence.getDouble("old_estimated_net_per_unit"))
        assertTrue(accepted.proposal!!.reason.contains("|H1="))
        assertTrue(accepted.proposal.reason.contains("|H4="))
    }
}
