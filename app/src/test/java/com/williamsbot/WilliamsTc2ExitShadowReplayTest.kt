package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsTc2ExitShadowReplayTest {
    private val hour=3_600_000L
    private val at=10*hour+20_000L
    private fun regime(hold:Boolean)=JSONObject()
        .put("schema","WILLIAMS_TC2_SENIOR_EXIT_CONTEXT_V1")
        .put("mode","SHADOW_ONLY")
        .put("as_of_ms",at)
        .put("context_hold_hypothesis",hold)
        .put("regime",if(hold) "JUNIOR_CORRECTION_INSIDE_SENIOR_TREND_PLAUSIBLE"
            else "CONFLICTING_OR_AMBIGUOUS_STRUCTURE")
    private fun candidate(side:String="LONG",hold:Boolean=true)=Tc2ExitShadowCandidate(
        ownerId="EXCHANGE_PROVEN_1",signalId="H1_WM1_OPPOSITE",
        direction=side,decisionAtMs=at,
        entryPrice=100.0,observedContract=if(side=="LONG") 101.0 else 99.0,
        signedStop=if(side=="LONG") 95.0 else 105.0,
        signedQuantity=2.0,regime=regime(hold))
    private fun future(side:String,stopAt:Int?=null):List<WilliamsWaveBar> =
        (0 until 24).map { i ->
            val price=if(side=="LONG") 102.0+i*.5 else 98.0-i*.5
            val o=if(i==stopAt) (if(side=="LONG") 94.0 else 106.0) else price
            val hi=maxOf(o,price)+.4
            val lo=minOf(o,price)-.4
            WilliamsWaveBar((11+i)*hour,o,hi,lo,price,1.0)
        }

    @Test fun sixHourContextPaperResultCannotPeekOneHourAhead() {
        val rows=future("LONG")
        val begun=williamsTc2ExitShadowAdvance(null,"BTCUSDT",at,rows,candidate())
        val ev=begun.getJSONArray("events").getJSONObject(0)
        assertNull(ev.optJSONObject("outcome_6h"))
        val tooEarly=williamsTc2ExitShadowAdvance(begun,"BTCUSDT",17*hour-1,rows)
        assertNull(tooEarly.getJSONArray("events").getJSONObject(0).optJSONObject("outcome_6h"))
        val matured=williamsTc2ExitShadowAdvance(tooEarly,"BTCUSDT",17*hour,rows)
        val outcome=matured.getJSONArray("events").getJSONObject(0).getJSONObject("outcome_6h")
        assertEquals("MATURED",outcome.getString("status"))
        assertEquals(17*hour,outcome.getLong("as_of_closed_ms"))
        assertTrue(outcome.getDouble("context_minus_book_estimated_usdt")>0.0)
        assertTrue(outcome.getDouble("hold_max_intrabar_adverse_from_decision_usdt")>=0.0)
        assertEquals(1,matured.getJSONObject("summary").getJSONObject("h6").getInt("matured"))
        assertEquals(0,matured.getJSONObject("summary").getJSONObject("h24").getInt("matured"))
        assertFalse(matured.getBoolean("can_place_orders"))
        assertFalse(matured.getBoolean("can_close"))
    }

    @Test fun losingCounterfactualIsNotHiddenWhenStaticStopHitAndGapFillsWorse() {
        for(side in listOf("LONG","SHORT")) {
            val rows=future(side,stopAt=1)
            val begun=williamsTc2ExitShadowAdvance(null,"BTCUSDT",at,rows,candidate(side))
            val matured=williamsTc2ExitShadowAdvance(begun,"BTCUSDT",17*hour,rows)
            val out=matured.getJSONArray("events").getJSONObject(0).getJSONObject("outcome_6h")
            assertEquals("MATURED",out.getString("status"))
            assertEquals("GAP_BEYOND_STOP",out.getString("hold_outcome_reason"))
            assertTrue(out.getDouble("context_minus_book_estimated_usdt")<0.0)
            assertEquals(if(side=="LONG") 94.0 else 106.0,
                out.getDouble("hold_exit_price_reference"),0.0)
            assertFalse(out.getBoolean("funding_included"))
            assertFalse(out.getBoolean("actual_binance_fills"))
        }
    }

    @Test fun noSeniorHoldHypothesisUsesBookExitAndTracksBothPaperAlternatives() {
        val rows=future("SHORT")
        val begun=williamsTc2ExitShadowAdvance(null,"ETHUSDT",at,rows,candidate("SHORT",false))
        val result=williamsTc2ExitShadowAdvance(begun,"ETHUSDT",35*hour,rows)
        val e=result.getJSONArray("events").getJSONObject(0)
        val out=e.getJSONObject("outcome_24h")
        assertEquals("MATURED",out.getString("status"))
        assertEquals(0.0,out.getDouble("context_minus_book_estimated_usdt"),0.0)
        assertNotEquals(out.getDouble("book_estimated_net_usdt"),out.getDouble("hold_estimated_net_usdt"))
        assertEquals(1,result.getJSONObject("summary").getJSONObject("h24").getInt("matured"))
        assertEquals(0,result.getJSONObject("summary").getJSONObject("h24").getInt("context_hold_matured"))
    }

    @Test fun duplicateSignalAfterCrashRestartCannotDoubleCountOneTrade() {
        val first=williamsTc2ExitShadowAdvance(null,"DOGEUSDT",at,emptyList(),candidate())
        val second=williamsTc2ExitShadowAdvance(JSONObject(first.toString()),"DOGEUSDT",at+1000L,
            emptyList(),candidate())
        assertEquals(1,second.getJSONArray("events").length())
        assertEquals(0,second.getJSONObject("summary").getJSONObject("h6").getInt("matured"))
        assertFalse(second.getBoolean("can_modify_stop"))
    }

    @Test fun gappedForwardHistoryCannotProduceFabricatedOutcome() {
        val bars=future("LONG").filterNot { it.t==13*hour }
        val e=williamsTc2ExitShadowAdvance(null,"BTCUSDT",at,emptyList(),candidate())
        val after=williamsTc2ExitShadowAdvance(e,"BTCUSDT",35*hour,bars)
        assertEquals("INVALID_OR_MISSING_FORWARD_H1",
            after.getJSONArray("events").getJSONObject(0).getJSONObject("outcome_24h").getString("status"))
    }

    @Test fun seniorConfirmedSwingAndSimpleSeniorBiasAreSeparatePaperExperiments() {
        val confirmed=regime(true)
            .put("context_hold_strict_h4_d1_swings_shadow_only",true)
        val c=candidate().copy(regime=confirmed)
        val first=williamsTc2ExitShadowAdvance(null,"BTCUSDT",at,emptyList(),c)
        val entry=first.getJSONArray("events").getJSONObject(0)
        assertTrue(entry.getBoolean("paper_context_hold"))
        assertTrue(entry.getBoolean("paper_strict_h4_d1_hold"))
        val later=williamsTc2ExitShadowAdvance(first,"BTCUSDT",17*hour,
            future("LONG"))
        val outcome=later.getJSONArray("events").getJSONObject(0)
            .getJSONObject("outcome_6h")
        assertEquals(outcome.getDouble("context_minus_book_estimated_usdt"),
            outcome.getDouble("strict_h4_d1_context_minus_book_estimated_usdt"),0.0)
        val summary=later.getJSONObject("summary").getJSONObject("h6")
        assertEquals(1,summary.getInt("strict_h4_d1_swing_hold_matured"))
        assertEquals(1,summary.getInt("strict_h4_d1_swing_hold_better_than_book"))
        assertFalse(later.getBoolean("can_close"))
        assertFalse(later.getBoolean("can_modify_stop"))
        // An unverified senior structure can propose a BASELINE paper hold,
        // but must not be silently promoted to the stricter held variant.
        val unverified=williamsTc2ExitShadowAdvance(null,"ETHUSDT",at,
            emptyList(),candidate())
        val unverifiedEntry=unverified.getJSONArray("events").getJSONObject(0)
        assertTrue(unverifiedEntry.getBoolean("paper_context_hold"))
        assertFalse(unverifiedEntry.getBoolean("paper_strict_h4_d1_hold"))
    }

    private fun trailSeed(side:String):List<WilliamsWaveBar> =
        (6..10).map { hourAt ->
            val close=if(side=="LONG") 98.0+(hourAt-6)*0.6
                else 102.0-(hourAt-6)*0.6
            WilliamsWaveBar(hourAt*hour,close,close+0.5,close-0.5,close,1.0)
        }

    @Test fun dynamic3And5BarShadowStopsOnlyTightenAndNeverAuthorizeTrading() {
        for(side in listOf("LONG","SHORT")) {
            val input=trailSeed(side)+future(side)
            val first=williamsTc2ExitShadowAdvance(null,"BTCUSDT",at,input,candidate(side))
            val matured=williamsTc2ExitShadowAdvance(first,"BTCUSDT",17*hour,input)
            val e=matured.getJSONArray("events").getJSONObject(0)
            val static=e.getJSONObject("outcome_6h")
            assertEquals("MATURED",static.getString("status"))
            for(trail in listOf(3,5)) {
                val paper=e.getJSONObject("outcome_6h_trail${trail}")
                assertEquals("MATURED",paper.getString("status"))
                assertTrue(paper.getBoolean("includes_trailing_stop"))
                assertFalse(paper.getBoolean("actual_binance_fills"))
                assertTrue(paper.getBoolean("intrabar_adverse_is_unverified_upper_bound"))
                assertTrue(paper.getBoolean("paper_trailing_does_not_assert_exchange_order_mutation"))
                val start=paper.getDouble("original_exchange_stop")
                val end=paper.getDouble("paper_final_stop")
                assertTrue(if(side=="LONG") end>=start else end<=start)
                assertTrue(paper.getInt("paper_trailing_tightening_events")>=1)
                assertEquals(1,matured.getJSONObject("summary")
                    .getJSONObject("h6_trail${trail}").getInt("matured"))
                assertNull(e.optJSONObject("outcome_24h_trail${trail}"))
            }
            assertFalse(matured.getBoolean("can_modify_stop"))
            assertFalse(matured.getBoolean("can_close"))
        }
    }

    @Test fun dynamicTrailingGapExitsAtWorseOpeningWhileOriginalStaticStopSurvives() {
        for(side in listOf("LONG","SHORT")) {
            val bars=(trailSeed(side)+future(side)).toMutableList()
            val shockIndex=bars.indexOfFirst { it.t==15*hour }
            assertTrue(shockIndex>0)
            val open=if(side=="LONG") 99.0 else 101.0
            val close=open
            bars[shockIndex]=WilliamsWaveBar(15*hour,open,open+0.5,
                open-0.5,close,1.0)
            val first=williamsTc2ExitShadowAdvance(null,"ETHUSDT",at,bars,candidate(side))
            val matured=williamsTc2ExitShadowAdvance(first,"ETHUSDT",17*hour,bars)
            val e=matured.getJSONArray("events").getJSONObject(0)
            assertEquals("HORIZON_CLOSED_BAR_MARK",
                e.getJSONObject("outcome_6h").getString("hold_outcome_reason"))
            val trail=e.getJSONObject("outcome_6h_trail3")
            assertEquals("GAP_BEYOND_STOP",trail.getString("hold_outcome_reason"))
            assertEquals(open,trail.getDouble("hold_exit_price_reference"),0.0001)
            assertTrue(if(side=="LONG")
                open<trail.getDouble("paper_final_stop") else
                open>trail.getDouble("paper_final_stop"))
        }
    }

    @Test fun trailingShadowRejectsMissingPriorClosedH1RatherThanAssumingProtection() {
        val incomplete=future("LONG")
        val first=williamsTc2ExitShadowAdvance(null,"LTCUSDT",at,incomplete,candidate())
        val matured=williamsTc2ExitShadowAdvance(first,"LTCUSDT",17*hour,incomplete)
        val e=matured.getJSONArray("events").getJSONObject(0)
        assertEquals("MATURED",e.getJSONObject("outcome_6h").getString("status"))
        assertEquals("INVALID_OR_MISSING_TRAILING_H1",
            e.getJSONObject("outcome_6h_trail3").getString("status"))
        assertEquals("INVALID_OR_MISSING_TRAILING_H1",
            e.getJSONObject("outcome_6h_trail5").getString("status"))
    }

    @Test fun invalidStopBeforeOppositeSignalIsReportedAndDedupedAcrossRestart() {
        val candidate=candidate().copy(signedStop=120.0)
        val first=williamsTc2ExitShadowAdvance(null,"BTCUSDT",at,
            emptyList(),candidate)
        assertEquals(0,first.getJSONArray("events").length())
        assertEquals(1,first.getJSONArray("sample_rejections").length())
        assertEquals("STOP_ALREADY_BREACHED_BEFORE_PAPER_HOLD",
            first.getJSONArray("sample_rejections").getJSONObject(0).getString("reason"))
        assertEquals(1L,first.getLong("sample_rejections_total"))
        val afterRestart=williamsTc2ExitShadowAdvance(JSONObject(first.toString()),
            "BTCUSDT",at+1000L,emptyList(),candidate)
        assertEquals(1L,afterRestart.getLong("sample_rejections_total"))
        assertEquals(1,afterRestart.getJSONArray("sample_rejections").length())
        assertFalse(afterRestart.getBoolean("can_modify_stop"))
        assertFalse(afterRestart.getBoolean("can_close"))
    }

    @Test fun missingSourceAndInvalidSignedStopDoNotFakeSuccessfulResearchSample() {
        val c=candidate().copy(signedStop=Double.NaN)
        val out=williamsTc2ExitShadowAdvance(null,"ETHUSDT",at,
            emptyList(),c)
        assertEquals("PRICE_QUANTITY_OR_STOP_NOT_SIGNED",
            out.getJSONArray("sample_rejections").getJSONObject(0).getString("reason"))
        assertEquals(0,out.getJSONArray("events").length())
    }

    @Test fun malformedCandidateDoesNotGetAddedToResearchLedger() {
        val wrong=candidate().copy(signedStop=120.0)
        val e=williamsTc2ExitShadowAdvance(null,"BTCUSDT",at,emptyList(),wrong)
        assertEquals(0,e.getJSONArray("events").length())
    }
}
