package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class WilliamsTc2SeniorExitContextTest {
    private val h=3_600_000L
    private val d=86_400_000L
    private val asOf=120*d

    private fun series(frame:String, direction:Int, count:Int=105):List<WilliamsWaveBar> {
        val step=williamsWaveFrames.getValue(frame)
        val initial=asOf-count*step
        return (0 until count).map { i ->
            // A measured trend with multiple reversible fractal swings.
            // 0.6 slope and 3.2 wave amplitude: pivot lows/highs can exist
            // while the last series maintains a strong senior bias.
            val center=180.0+direction*(i*.6+3.2*sin(i*.65))
            WilliamsWaveBar(initial+i*step,center-.4,center+1.0,center-1.0,center,.8)
        }
    }

    @Test fun everyReadingUsesIndependentClosedH1H4D1NotM15NorFutureCandles() {
        val sources=mapOf("1d" to series("1d",1),
            "4h" to series("4h",1),
            "1h" to series("1h",-1))
        val result=williamsTc2SeniorExitContext("LONG",asOf,sources,"BOOK:WM1:SHORT")
        assertEquals("WILLIAMS_TC2_SENIOR_EXIT_CONTEXT_V1",result.getString("schema"))
        assertEquals("SHADOW_ONLY",result.getString("mode"))
        assertFalse(result.getBoolean("can_authorize_exit"))
        assertFalse(result.getBoolean("can_veto_book_exit"))
        assertFalse(result.getBoolean("can_modify_stop"))
        assertFalse(result.getBoolean("confidence_calibrated"))
        assertEquals(3,result.getJSONArray("frames").length())
        for(j in 0 until result.getJSONArray("frames").length()) {
            val frame=result.getJSONArray("frames").getJSONObject(j)
            assertEquals("CLOSED_OBSERVED",frame.getString("state"))
            val f=frame.getString("frame")
            assertTrue(frame.getLong("last_closed_at_ms")<=asOf)
            assertTrue(asOf-frame.getLong("last_closed_at_ms")<williamsWaveFrames.getValue(f))
            assertFalse(frame.getBoolean("can_close"))
        }
        assertTrue(result.getString("regime") in setOf(
            "JUNIOR_CORRECTION_INSIDE_SENIOR_TREND_PLAUSIBLE",
            "SENIOR_TREND_CONTINUATION_PLAUSIBLE",
            "CONFLICTING_OR_AMBIGUOUS_STRUCTURE"))
    }

    @Test fun unfinishedD1OrH4NeverReplacedWithFutureOrStaleValues() {
        val good=mapOf("1d" to series("1d",1),
            "4h" to series("4h",1),
            "1h" to series("1h",-1))
        val pending=good.toMutableMap()
        // This last D1 bar will close AFTER the decision; do not read it.
        pending["1d"]=good.getValue("1d").drop(1)+
            WilliamsWaveBar(asOf,300.0,302.0,299.0,301.0,100.0)
        val result=williamsTc2SeniorExitContext("LONG",asOf,pending,"BOOK:WM1:SHORT")
        val f=result.getJSONArray("frames").getJSONObject(0)
        // closedWilliamsWaveBars filters future/unfinished and rechecks freshness.
        assertTrue(f.getLong("last_closed_at_ms")<=asOf)
        assertFalse(result.getBoolean("can_veto_book_exit"))
        assertTrue(result.getBoolean("wave_hypotheses_only"))
    }

    @Test fun missingSeniorCandlesNeverBecomeAnInventedCorrrectionConfidence() {
        val result=williamsTc2SeniorExitContext("SHORT",asOf,
            mapOf("1h" to series("1h",1)),"BOOK:WM2:LONG")
        assertEquals("INSUFFICIENT_CAUSAL_H1_H4_D1",result.getString("regime"))
        assertFalse(result.getBoolean("context_hold_hypothesis"))
        assertFalse(result.getBoolean("wave_count_is_certain"))
        assertEquals(3,result.getJSONArray("frames").length())
    }

    @Test fun futureH1H4D1BarsCannotChangeAnAlreadyObservedRegimeOrSwingCount() {
        val original=mapOf("1d" to series("1d",1),
            "4h" to series("4h",1),
            "1h" to series("1h",-1))
        val first=williamsTc2SeniorExitContext("LONG",asOf,original,"WM3:SHORT")
        val mutated=original.mapValues { (frame,bars) ->
            // An impossible massive future candle may not alter any field
            // labelled AS-OF the older decision timestamp.
            val step=williamsWaveFrames.getValue(frame)
            bars+WilliamsWaveBar(asOf,9000.0,12000.0,1.0,4500.0,1000.0)
        }
        val second=williamsTc2SeniorExitContext("LONG",asOf,mutated,"WM3:SHORT")
        assertEquals(first.getString("regime"),second.getString("regime"))
        assertEquals(first.getString("h4_bias"),second.getString("h4_bias"))
        assertEquals(first.getString("d1_bias"),second.getString("d1_bias"))
        assertEquals(first.getJSONObject("closed_h1_h4_d1_swing_patterns").toString(),
            second.getJSONObject("closed_h1_h4_d1_swing_patterns").toString())
        assertEquals(first.getBoolean("context_hold_strict_h4_d1_swings_shadow_only"),
            second.getBoolean("context_hold_strict_h4_d1_swings_shadow_only"))
        assertFalse(second.getBoolean("can_authorize_exit"))
    }

    @Test fun strictContextPaperHoldCannotExistWithoutBaselineAndRealSwingEvidence() {
        for(side in listOf("LONG","SHORT")) {
            val sign=if(side=="LONG") 1 else -1
            val result=williamsTc2SeniorExitContext(side,asOf,mapOf(
                "1d" to series("1d",sign),
                "4h" to series("4h",sign),
                "1h" to series("1h",-sign)
            ),"WM2:OPPOSITE")
            val strict=result.getBoolean("context_hold_strict_h4_d1_swings_shadow_only")
            if(strict) {
                assertTrue(result.getBoolean("context_hold_hypothesis"))
                assertTrue(result.getBoolean("senior_swing_structure_matches_position"))
            }
            assertTrue(result.getBoolean("contextual_hold_not_approved_by_historical_oos"))
            assertFalse(result.getBoolean("can_modify_stop"))
            assertFalse(result.getBoolean("can_veto_book_exit"))
        }
    }

    @Test fun shortSideIsIndependentAndDoesNotUseAbsolutePositiveAoAsSide() {
        val result=williamsTc2SeniorExitContext("SHORT",asOf,mapOf(
            "1d" to series("1d",-1),"4h" to series("4h",-1),"1h" to series("1h",1)
        ),"BOOK:WM3:LONG")
        assertEquals("SHORT",result.getString("position_direction"))
        assertFalse(result.getBoolean("can_modify_stop"))
        assertFalse(result.getBoolean("can_authorize_exit"))
        assertEquals("SHADOW_ONLY",result.getString("mode"))
    }
}
