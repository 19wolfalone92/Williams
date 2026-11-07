package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsTc1WavePriceGeometryTest {
    private val hour=3_600_000L
    private val now=120*hour

    private fun scenario(prices:List<Double>,direction:String="UP",
                         observed:Long=now,knownLast:Long?=null):JSONObject {
        val first=if(direction=="UP") "LOW" else "HIGH"
        val anchors=JSONArray()
        prices.forEachIndexed { i,price ->
            val at=(60+i*5L)*hour
            anchors.put(JSONObject()
                .put("kind",if(i%2==0) first else if(first=="LOW") "HIGH" else "LOW")
                .put("price",price).put("extreme_at",at)
                .put("known_at",if(i==prices.lastIndex && knownLast!=null) knownLast else at+2*hour))
        }
        return JSONObject().put("frame","1h").put("direction",direction)
            .put("status","PROVISIONAL").put("observed_at",observed)
            .put("scenario_id","SOURCE_TEST").put("anchors",anchors)
            .put("active_direction","DOWN").put("active_label","W5")
            .put("invalidation",90.0).put("scale_bars",110)
    }

    @Test fun tc1SourceRatiosAreNumericallyExactButDoNotActAsTradeGates() {
        val g=williamsTc1WavePriceGeometry(scenario(listOf(100.0,120.0,110.0,142.0,128.0,154.0)))
        assertEquals("BOUNDED_PRICE_EVIDENCE",g.getString("status"))
        assertEquals(.50,g.getJSONObject("w2_retracement").getDouble("fraction"),1e-12)
        assertEquals(1.60,g.getJSONObject("w3_extension").getDouble("fraction"),1e-12)
        assertEquals(.4375,g.getJSONObject("w4_retracement").getDouble("fraction"),1e-12)
        for(key in listOf("w2_retracement","w3_extension","w4_retracement"))
            assertEquals("WITHIN_TYPICAL_BOOK_BAND",g.getJSONObject(key).getString("status"))
        assertEquals(130.0,g.getJSONObject("w3_extension").getDouble("projected_low_from_w2"),1e-12)
        assertEquals(142.4,g.getJSONObject("w3_extension").getDouble("projected_high_from_w2"),1e-9)
        assertEquals(128.0,g.getJSONObject("w5_projection").getDouble("origin_price"),1e-9)
        assertTrue(g.getJSONObject("w5_projection").getBoolean("price_target_not_invented"))
        assertFalse(g.getBoolean("wave_degree_proven"))
        assertFalse(g.getBoolean("can_place_orders"))
        assertFalse(g.getBoolean("can_move_stop"))
        assertFalse(g.getBoolean("can_close"))
        assertFalse(g.getJSONObject("w2_retracement").getBoolean("hard_trade_veto"))
        assertTrue(g.getJSONObject("w4_retracement").getString("explanation_ru").contains("38–50"))
    }

    @Test fun mirrorAllBandValuesForShortWithoutNegativeRatioOrInventedTarget() {
        val up=listOf(100.0,120.0,110.0,142.0,128.0,154.0)
        val long=williamsTc1WavePriceGeometry(scenario(up))
        val short=williamsTc1WavePriceGeometry(scenario(up.map { 500.0-it },"DOWN"))
        for(name in listOf("w2_retracement","w3_extension","w4_retracement")) {
            assertEquals(long.getJSONObject(name).getDouble("fraction"),
                short.getJSONObject(name).getDouble("fraction"),1e-12)
        }
        assertEquals(372.0,short.getJSONObject("w5_projection").getDouble("origin_price"),1e-9)
        assertEquals(357.6,short.getJSONObject("w3_extension").getDouble("projected_high_from_w2"),1e-9)
        assertEquals("BOUNDED_PRICE_EVIDENCE",short.getString("status"))
        assertFalse(short.getBoolean("can_place_orders"))
    }

    @Test fun w5OriginIsUnavailableUntilFourthWavePivotIsConfirmed() {
        val immature=williamsTc1WavePriceGeometry(scenario(listOf(100.0,120.0,110.0,142.0)))
        assertEquals("W4_NOT_CONFIRMED",immature.getJSONObject("w5_projection").getString("status"))
        assertEquals("NOT_OBSERVED",immature.getJSONObject("w4_retracement").getString("status"))
        val formed=williamsTc1WavePriceGeometry(scenario(listOf(100.0,120.0,110.0,142.0,128.0)))
        assertEquals("W4_PIVOT_CONFIRMED_PROJECTION_BASE_ONLY",
            formed.getJSONObject("w5_projection").getString("status"))
    }

    @Test fun overlapHighlightsCompetingDiagonalNotCertainW4() {
        val g=williamsTc1WavePriceGeometry(scenario(listOf(100.0,120.0,110.0,145.0,117.0,153.0)))
        assertEquals("BOUNDED_PRICE_EVIDENCE",g.getString("status"))
        assertTrue(g.getJSONObject("w4_retracement").getBoolean("overlaps_w1_price_territory"))
        assertTrue(g.getJSONObject("w4_retracement").getBoolean("diagonal_or_alternative_count_requires_review"))
        assertFalse(g.getBoolean("wave_degree_proven"))
        assertFalse(g.getJSONObject("w4_retracement").getBoolean("hard_trade_veto"))
    }

    @Test fun bookTypicalBandsAreNotMandatoryEvenWhenFarOutside() {
        val g=williamsTc1WavePriceGeometry(scenario(listOf(100.0,120.0,104.0,150.0,125.0)))
        assertEquals("ABOVE_TYPICAL_BOOK_BAND",g.getJSONObject("w2_retracement").getString("status"))
        assertEquals("ABOVE_TYPICAL_BOOK_BAND",g.getJSONObject("w3_extension").getString("status"))
        assertEquals("ABOVE_TYPICAL_BOOK_BAND",g.getJSONObject("w4_retracement").getString("status"))
        assertFalse(g.getJSONObject("w4_retracement").getBoolean("hard_trade_veto"))
    }

    @Test fun futureOrInconsistentPivotCannotBecomeCausalMarketEvidence() {
        val prices=listOf(100.0,120.0,110.0,142.0,128.0)
        assertEquals("UNVERIFIED_WAVE_ANCHORS",
            williamsTc1WavePriceGeometry(scenario(prices,knownLast=now+hour)).getString("status"))
        val forged=scenario(prices)
        forged.getJSONArray("anchors").getJSONObject(2).put("kind","HIGH")
        assertEquals("UNVERIFIED_WAVE_ANCHORS",williamsTc1WavePriceGeometry(forged).getString("status"))
        val broken=scenario(prices)
        broken.getJSONArray("anchors").getJSONObject(3).put("extreme_at",1L)
        assertEquals("UNVERIFIED_WAVE_ANCHORS",williamsTc1WavePriceGeometry(broken).getString("status"))
        val noPrice=scenario(prices)
        noPrice.getJSONArray("anchors").getJSONObject(4).put("price",-1.0)
        assertEquals("UNVERIFIED_WAVE_ANCHORS",williamsTc1WavePriceGeometry(noPrice).getString("status"))
    }

    @Test fun tc1CorrectionBZoneNeverInventsWaveCEntryWithoutFractalSquatAndFiveThreeFive() {
        val up=listOf(100.0,120.0,110.0,142.0,128.0,154.0,134.0,145.0)
        for((dir,prices) in listOf("UP" to up,"DOWN" to up.map { 500.0-it })) {
            val analysis=williamsTc1WavePriceGeometry(scenario(prices,dir))
            val b=analysis.getJSONObject("abc_b_retracement")
            assertEquals("ZIGZAG_B_50_62_PRICE_ZONE_ONLY",b.getString("status"))
            assertEquals(.55,b.getDouble("b_over_a"),1e-12)
            assertFalse(b.getBoolean("zigzag_5_3_5_internal_degree_verified"))
            assertFalse(b.getBoolean("fractal_and_squat_confirmed_here"))
            assertFalse(b.getBoolean("can_enter_c_wave"))
            assertFalse(analysis.getBoolean("can_place_orders"))
        }
        val deep=williamsTc1WavePriceGeometry(scenario(
            listOf(100.0,120.0,110.0,142.0,128.0,154.0,134.0,153.0)))
        assertEquals("DEEP_B_MAY_BE_FLAT_OR_ALTERNATIVE_COUNT",
            deep.getJSONObject("abc_b_retracement").getString("status"))
        assertFalse(deep.getJSONObject("abc_b_retracement").getBoolean("can_enter_c_wave"))
    }

    @Test fun existingMarketPhaseCarriesBookEvidenceWithoutOpeningOrClosing() {
        val sc=scenario(listOf(100.0,120.0,110.0,142.0,128.0))
            .put("active_label","W5")
        val hierarchy=JSONObject().put("as_of",now)
            .put("scenarios",JSONArray().put(sc))
            .put("frame_errors",JSONObject())
            .put("frame_candle_counts",JSONObject().put("1h",140))
            .put("frame_readings",JSONArray().put(JSONObject()
                .put("frame","1h").put("state","OBSERVED")
                .put("as_of_close_ms",now).put("observed_bias","UP")
                .put("alligator",JSONObject().put("state","EATING"))))
        val phase=williamsMarketPhaseContext(hierarchy)
        val frames=phase.getJSONArray("frames")
        val h1=frames.getJSONObject(3)
        val studies=h1.getJSONArray("hypotheses")
        assertEquals(1,studies.length())
        assertEquals("BOUNDED_PRICE_EVIDENCE",
            studies.getJSONObject(0).getJSONObject("tc1_wave_price_geometry").getString("status"))
        val report=phase.getJSONObject("market_narrative").getJSONArray("frames").getJSONObject(3)
        assertEquals(1,report.getJSONArray("tc1_wave_price_evidence").length())
        assertTrue(report.getString("tc1_price_geometry_explanation_ru").contains("38–62"))
        assertFalse(report.getBoolean("tc1_ratios_can_veto_orders"))
        assertFalse(phase.getBoolean("can_place_orders"))
        assertFalse(phase.getBoolean("can_close"))
    }
}
