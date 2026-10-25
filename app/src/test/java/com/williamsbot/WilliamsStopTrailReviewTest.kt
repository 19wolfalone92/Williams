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
            assertEquals(180,allowed.evidence.getJSONArray("h1_full_closed_source").length())
            assertEquals(45,allowed.evidence.getJSONArray("h4_full_closed_source").length())
            assertTrue(allowed.evidence.getBoolean("full_source_is_exclusively_closed_at_asof"))
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

    /** Real closed Binance H1 high/low/close from the user's 25 Oct 06:50Z
     * diagnostics, last closed H1=05:00Z, BEFORE all three STOP replacements
     * and with NO later candles. Opens/volumes are unused by this pure guard.
     * These are regression cases, not independent out-of-sample validation. */
    @Test fun observedBtcSolDogeAdverseH1ImpulseIsNotPromotedToFreshStructuralStop() {
        val hlc=mapOf(
            "BTCUSDT" to listOf(
                Triple(80606.3,80220.3,80606.3),Triple(80606.8,80429.2,80488.1),
                Triple(80590.2,80488.1,80489.3),Triple(80492.1,80313.0,80383.5),
                Triple(80577.2,80381.9,80561.8),Triple(80873.0,80515.9,80860.0),
                Triple(80874.3,80763.9,80838.0),Triple(80860.1,80761.4,80859.9),
                Triple(81017.0,80784.6,80882.3),Triple(80995.4,80826.2,80826.2),
                Triple(80922.6,80818.0,80904.5),Triple(80922.6,80524.9,80564.4)
            ),
            "SOLUSDT" to listOf(
                Triple(101.32,100.42,101.27),Triple(101.33,101.01,101.12),
                Triple(101.62,101.12,101.52),Triple(101.54,101.09,101.32),
                Triple(101.68,101.32,101.56),Triple(102.5,101.53,102.33),
                Triple(102.55,102.21,102.44),Triple(102.52,102.15,102.44),
                Triple(102.59,102.27,102.4),Triple(102.67,102.32,102.38),
                Triple(102.71,102.34,102.59),Triple(102.6,101.72,101.93)
            ),
            "DOGEUSDT" to listOf(
                Triple(0.0772,0.07637,0.07707),Triple(0.07719,0.07694,0.07708),
                Triple(0.07746,0.07708,0.07726),Triple(0.07726,0.07695,0.07709),
                Triple(0.07738,0.07708,0.07732),Triple(0.07774,0.07717,0.07764),
                Triple(0.07801,0.07755,0.07786),Triple(0.07797,0.07767,0.07784),
                Triple(0.07797,0.0777,0.07771),Triple(0.07791,0.07765,0.07783),
                Triple(0.07797,0.07779,0.07789),Triple(0.0779,0.07688,0.07706)
            )
        )
        for((symbol,triples) in hlc) {
            val observed=triples.mapIndexed { i,(high,low,close) ->
                WilliamsWaveBar(1792864800000L+i*hour,close,high,low,close,1.0)
            }
            val result=williamsH1CounterImpulseVeto("LONG",observed.takeLast(5),observed)
            assertTrue("$symbol 05:00 UTC H1 was a fresh unconfirmed adverse impulse",result.block)
            assertTrue("$symbol missing new low",result.freshCounterExtreme)
            assertTrue("$symbol not a volatility shock",result.rangeOverMedian>=1.5)
            assertTrue("$symbol did not close in lower quartile",result.closeLocation<=0.25)
            // Mirrored SHORT must be treated symmetrically, not as a LONG.
            val mirrored=observed.map { b ->
                b.copy(o=250-b.o,h=250-b.l,l=250-b.h,c=250-b.c)
            }
            assertTrue("$symbol mirrored SHORT",williamsH1CounterImpulseVeto(
                "SHORT",mirrored.takeLast(5),mirrored).block)
            val upClose=observed.dropLast(1)+observed.last().copy(c=observed.last().h)
            assertFalse("$symbol favorable H1 close must not trigger this veto",
                williamsH1CounterImpulseVeto("LONG",upClose.takeLast(5),upClose).block)
        }
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
