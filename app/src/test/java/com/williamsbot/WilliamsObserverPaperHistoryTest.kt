package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Deterministic source-isolated paper studies; never exchange fills. */
class WilliamsObserverPaperHistoryTest {
    private val h=3_600_000L

    private fun bars(n:Int)= (1..n).map { i ->
        WilliamsWaveBar(i*h,100.0,105.0,95.0,100.0,100.0)
    }

    private fun signal(
        source:String=williamsNtdProfile,side:String="LONG",
        role:String="ENTRY",semantic:String="STOP_ENTRY",id:String="book-1",
        signalTime:Long=45*h
    ):JSONArray = JSONArray().put(JSONObject()
        .put("strategy_profile",source).put("signal_id",id)
        .put("signal_bar_time_ms",signalTime)
        .put("direction",side).put("role",role)
        .put("type",if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL")
        .put("trigger",if(side=="LONG") 110.0 else 90.0)
        .put("protective_stop",if(side=="LONG") 90.0 else 110.0)
        .put("execution_semantics",semantic))

    private fun events(x:JSONObject)=x.getJSONArray("events").let {
        a->(0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test fun bothObserverBooksRemainIsolatedFromRealExecutionAndRecordEntryStop() {
        for(source in listOf(williamsNtdProfile,williamsCourseProfile))
            for(side in listOf("LONG","SHORT")) {
                val opened=williamsObserverPaperAdvance(null,"BTCUSDT",source,bars(45),
                    signal(source,side),46*h)
                assertEquals("PAPER_OBSERVING",opened.getString("status"))
                assertEquals("OBSERVE",opened.getString("execution_role"))
                assertFalse(opened.getBoolean("can_place_orders"))
                assertFalse(opened.getBoolean("can_close"))
                assertTrue(opened.getBoolean("hypothetical_only"))
                assertNotNull(opened.optJSONObject("pending"))
                assertNull(opened.optJSONObject("paper_position"))
                assertEquals("PAPER_PENDING_ARMED",events(opened).last().getString("event"))
                val triggering=if(side=="LONG")
                    WilliamsWaveBar(46*h,100.0,112.0,96.0,100.0,100.0)
                    else WilliamsWaveBar(46*h,100.0,104.0,88.0,100.0,100.0)
                val filled=williamsObserverPaperAdvance(opened,"BTCUSDT",source,
                    bars(45)+triggering,JSONArray(),47*h)
                val pos=filled.getJSONObject("paper_position")
                assertTrue(pos.getDouble("entry_price")>0)
                assertEquals(side,pos.getString("direction"))
                assertTrue(events(filled).any { it.optString("event")=="PAPER_ENTRY_FILLED_ESTIMATE" })
                assertNull(filled.optJSONObject("pending"))
                val stopBar=if(side=="LONG")
                    WilliamsWaveBar(47*h,100.0,105.0,89.0,100.0,100.0)
                    else WilliamsWaveBar(47*h,100.0,111.0,95.0,100.0,100.0)
                val closed=williamsObserverPaperAdvance(filled,"BTCUSDT",source,
                    bars(45)+triggering+stopBar,JSONArray(),48*h)
                assertNull(closed.optJSONObject("paper_position"))
                val exit=events(closed).last()
                assertEquals("PAPER_EXIT_INITIAL_STOP_ESTIMATE",exit.getString("event"))
                assertFalse(exit.getBoolean("exit_is_complete_book_rule"))
                assertFalse(exit.getBoolean("exchange_fees_or_funding_verified"))
                assertTrue(exit.getDouble("pnl_per_unit_before_funding")<0)
                val repeated=williamsObserverPaperAdvance(JSONObject(closed.toString()),
                    "BTCUSDT",source,bars(45)+triggering+stopBar,JSONArray(),48*h)
                assertEquals(events(closed).size,events(repeated).size)
            }
    }

    @Test fun ambiguousSameCandleEntryAndStopConservativelyStopsWithoutProfitClaim() {
        val first=williamsObserverPaperAdvance(null,"ETHUSDT",williamsNtdProfile,
            bars(45),signal(),46*h)
        val both=WilliamsWaveBar(46*h,100.0,112.0,89.0,100.0,150.0)
        val second=williamsObserverPaperAdvance(first,"ETHUSDT",williamsNtdProfile,
            bars(45)+both,JSONArray(),47*h)
        assertNull(second.optJSONObject("paper_position"))
        val exit=events(second).last()
        assertTrue(exit.getBoolean("entry_and_stop_same_h1_bar"))
        assertTrue(exit.getDouble("pnl_per_unit_before_funding")<0)
    }

    @Test fun nonEntrySignalsArePreservedWithoutInventingInitialPositions() {
        val noPosition=williamsObserverPaperAdvance(null,"ETHUSDT",williamsCourseProfile,
            bars(45),signal(williamsCourseProfile,role="ADD_ON"),46*h)
        assertNull(noPosition.optJSONObject("pending"))
        assertTrue(events(noPosition).any { it.optString("event")=="SOURCE_SIGNAL_OBSERVED_NOT_INITIAL_PAPER_ENTRY" })
        val atClose=williamsObserverPaperAdvance(null,"ETHUSDT",williamsCourseProfile,
            bars(45),signal(williamsCourseProfile,semantic="MARKET_ON_CLOSE"),46*h)
        assertNull(atClose.optJSONObject("pending"))
        assertEquals("SOURCE_SIGNAL_OBSERVED_NOT_INITIAL_PAPER_ENTRY",events(atClose).last().getString("event"))
        val wrongSource=williamsObserverPaperAdvance(null,"ETHUSDT",williamsCourseProfile,
            bars(45),signal(williamsNtdProfile),46*h)
        assertEquals(0,events(wrongSource).size)
    }

    @Test fun gapsAndForgedSourceCannotRewriteConfirmedPaperPosition() {
        val first=williamsObserverPaperAdvance(null,"XRPUSDT",williamsNtdProfile,
            bars(45),signal(),46*h)
        val forged=williamsObserverPaperAdvance(first,"XRPUSDT",williamsCourseProfile,
            bars(45),JSONArray(),46*h)
        assertEquals("SOURCE_IDENTITY_MISMATCH",forged.getString("status"))
        assertEquals(williamsNtdProfile,forged.getString("profile"))
        val afterGap=(200..245).map { i -> WilliamsWaveBar(i*h,100.0,105.0,95.0,100.0,100.0) }
        val blocked=williamsObserverPaperAdvance(first,"XRPUSDT",williamsNtdProfile,
            afterGap,JSONArray(),246*h)
        assertEquals("UNRECOVERABLE_REPLAY_GAP",blocked.getString("status"))
        assertEquals(45*h,blocked.getLong("last_processed_open_ms"))
        assertEquals("book-1",blocked.getJSONObject("pending").getString("signal_id"))
    }

    @Test fun stopInvalidationBeforeAnyBreakoutCancelsPaperPendingBothWays() {
        for(side in listOf("LONG","SHORT")) {
            val first=williamsObserverPaperAdvance(null,"ADAUSDT",williamsNtdProfile,
                bars(45),signal(side=side),46*h)
            val invalidation=if(side=="LONG")
                WilliamsWaveBar(46*h,100.0,105.0,89.0,99.0,100.0)
                else WilliamsWaveBar(46*h,100.0,111.0,95.0,101.0,100.0)
            val cancelled=williamsObserverPaperAdvance(first,"ADAUSDT",williamsNtdProfile,
                bars(45)+invalidation,JSONArray(),47*h)
            assertNull(cancelled.optJSONObject("pending"))
            assertNull(cancelled.optJSONObject("paper_position"))
            assertEquals("PAPER_SETUP_INVALIDATED_BEFORE_ENTRY",events(cancelled).last().getString("event"))
            // A later breakout cannot resurrect an already broken source setup.
            val later=if(side=="LONG") WilliamsWaveBar(47*h,100.0,115.0,98.0,103.0,100.0)
                else WilliamsWaveBar(47*h,100.0,102.0,85.0,97.0,100.0)
            val resumed=williamsObserverPaperAdvance(cancelled,"ADAUSDT",williamsNtdProfile,
                bars(45)+invalidation+later,JSONArray(),48*h)
            assertNull(resumed.optJSONObject("paper_position"))
            assertFalse(events(resumed).any { it.optString("event")=="PAPER_ENTRY_FILLED_ESTIMATE" })
        }
    }

    @Test fun previousCandleIsNotEligibleAsASameBarFutureFill() {
        val first=williamsObserverPaperAdvance(null,"SOLUSDT",williamsNtdProfile,
            bars(45).dropLast(1)+WilliamsWaveBar(45*h,100.0,112.0,95.0,100.0,100.0),
            signal(),46*h)
        assertNotNull(first.optJSONObject("pending"))
        assertNull(first.optJSONObject("paper_position"))
        assertEquals(1,events(first).size)
    }
}
