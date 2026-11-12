package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsTc2ExitHierarchyLensTest {
    private val hour=WILLIAMS_H1_MS
    private val symbol=APPROVED_FUTURES_SYMBOLS.first()
    private val opened=2*hour+13*60_000L
    private val closed=11*hour+2*60_000L
    private val now=14*hour

    private fun candles(mutateFuture:Boolean=false):JSONArray=JSONArray().apply {
        for(i in 1..12) {
            val value=i.toDouble()
            val high=if(mutateFuture && i>=11) 1_000_000.0 else 150.0+value
            val low=if(mutateFuture && i>=11) 0.01 else 90.0+value
            put(JSONArray().put(i*hour).put(100.0+value)
                .put(high).put(low).put(100.0+value).put(1_000.0))
        }
    }
    private fun scanner(candles:JSONArray):JSONObject=JSONObject()
        .put("scanner_state","READY_BLOCKED")
        .put("symbols_scanned",APPROVED_FUTURES_SYMBOLS.size)
        .put("last_scan_at",13*hour)
        .put("candidates",JSONArray().put(JSONObject()
            .put("symbol",symbol)
            .put("decision_trace",JSONObject().put("candles",candles))))

    private fun exit(direction:String="LONG",openedAt:Long=opened):JSONObject=JSONObject()
        .put("recent_closed_trades",JSONArray().put(JSONObject()
            .put("symbol",symbol)
            .put("direction",direction)
            .put("opened_at_ms",openedAt)
            .put("closed_at_ms",closed)
            .put("recorded_exit_reason","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
            .put("exit_price",109.0)))

    @Test fun actualOppositeExitComparedToBookThreeAndFiveBarsWithoutProposingNewOrders() {
        val lens=WilliamsTc2ExitHierarchyLens.assess(scanner(candles()),exit(),now)
        val row=lens.getJSONArray("recent_closed_trades").getJSONObject(0)
        assertEquals("THREE_FOUR_FIVE_CLOSED_H1_EXTREMES_OBSERVED",row.getString("state"))
        assertEquals(8,row.getInt("post_entry_full_closed_h1_count"))
        assertEquals(98.0,row.getDouble("book_3_bar_boundary"),1e-8)
        assertEquals(97.0,row.getDouble("book_4_bar_boundary"),1e-8)
        assertEquals(96.0,row.getDouble("book_5_bar_boundary"),1e-8)
        assertEquals(1,lens.getInt("historical_3_4_5_observed"))
        assertFalse(lens.getBoolean("can_move_stop_or_override_tc2_exit"))
        assertFalse(lens.getBoolean("book_exit_outcome_optimality_verified"))
        assertFalse(row.getBoolean("price_extremes_are_executable_stops"))
        assertFalse(row.getBoolean("could_have_earned_more_by_holding_proven"))
    }

    @Test fun reverseDirectionAndBarsAfterActualExitHaveNoHindsightInfluence() {
        val baseline=WilliamsTc2ExitHierarchyLens.assess(scanner(candles()),exit("SHORT"),now)
            .getJSONArray("recent_closed_trades").getJSONObject(0)
        val future=WilliamsTc2ExitHierarchyLens.assess(scanner(candles(true)),exit("SHORT"),now)
            .getJSONArray("recent_closed_trades").getJSONObject(0)
        assertEquals(160.0,baseline.getDouble("book_3_bar_boundary"),1e-8)
        assertEquals(160.0,baseline.getDouble("book_4_bar_boundary"),1e-8)
        assertEquals(160.0,baseline.getDouble("book_5_bar_boundary"),1e-8)
        assertEquals(baseline.getDouble("book_3_bar_boundary"),
            future.getDouble("book_3_bar_boundary"),0.0)
        assertEquals(baseline.getDouble("book_4_bar_boundary"),
            future.getDouble("book_4_bar_boundary"),0.0)
        assertEquals(baseline.getDouble("book_5_bar_boundary"),
            future.getDouble("book_5_bar_boundary"),0.0)
    }

    @Test fun insufficientFullPostEntryBarsMustNotBorrowPreEntryBars() {
        val recent=WilliamsTc2ExitHierarchyLens.assess(
            scanner(candles()),exit(openedAt=8*hour+13*60_000L),now)
            .getJSONArray("recent_closed_trades").getJSONObject(0)
        assertEquals("COMPLETE_POST_ENTRY_H1_WINDOW_NOT_VERIFIED",recent.getString("state"))
        assertTrue(recent.isNull("book_3_bar_boundary"))
        assertTrue(recent.isNull("book_5_bar_boundary"))
    }

    @Test fun missingH1BarAndUnverifiedScannerNeverPassBookSource() {
        val gap=JSONArray().apply {
            val a=candles()
            for(i in 0 until a.length()) {
                val row=a.getJSONArray(i)
                if(row.getLong(0)!=7*hour) put(row)
            }
        }
        val bad=WilliamsTc2ExitHierarchyLens.assess(scanner(gap),exit(),now)
            .getJSONArray("recent_closed_trades").getJSONObject(0)
        assertEquals("UNIQUE_CONTIGUOUS_SOURCE_H1_NOT_VERIFIED",bad.getString("state"))
        val noScan=scanner(candles()).put("scanner_state","FAILED")
        val result=WilliamsTc2ExitHierarchyLens.assess(noScan,exit(),now)
        assertEquals("SCAN_HISTORY_NOT_PROVEN_AFTER_TRADE_EXIT",result
            .getJSONArray("recent_closed_trades").getJSONObject(0).getString("state"))
        assertEquals(1,result.getInt("historical_unverified"))
    }

    @Test fun threeAvailableButFiveUnavailableAreDistinctBookEvidence() {
        val data=WilliamsTc2ExitHierarchyLens.assess(
            scanner(candles()),exit(openedAt=6*hour+13*60_000L),now)
        val row=data.getJSONArray("recent_closed_trades").getJSONObject(0)
        assertEquals("THREE_FOUR_CLOSED_H1_EXTREMES_OBSERVED",row.getString("state"))
        assertEquals(4,row.getInt("post_entry_full_closed_h1_count"))
        assertEquals(98.0,row.getDouble("book_3_bar_boundary"),1e-8)
        assertEquals(97.0,row.getDouble("book_4_bar_boundary"),1e-8)
        assertTrue(row.isNull("book_5_bar_boundary"))
    }
}
