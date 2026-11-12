package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * TC2 second edition, chapter 12, Russian PDF pages 156, 161, 163 and 165.
 *
 * The book calls the 3/5-bar protective exit the usual exit and the opposing
 * Wise Man signal the less common alternative. A trade closed on an opposite
 * signal must not be reported as a finished D1/H4 trend or as a proven early
 * exit. Show both book price structures side-by-side instead.
 *
 * HISTORICAL/READ-ONLY. Never generates an order, moves a STOP, or projects a
 * counterfactual trade PnL. Windows consist exclusively of complete H1 bars
 * opened AFTER the signed position-open timestamp and closed BY the recorded
 * exchange exit. This conservative choice can omit the entry's partial H1 bar.
 * Recorded candles may be missing: never replace unknown with a claim.
 *
 * The author specifies one or more ticks beyond the extreme. We deliberately
 * report RAW candle extrema, not an executable STOP price, because this API
 * has no independently verified symbol tick, STOP-owner or mark-price proof.
 */
internal object WilliamsTc2ExitHierarchyLens {
    private data class H1(val start:Long,val high:Double,val low:Double)

    private fun rows(a:JSONArray?):List<JSONObject> =
        if(a==null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun recordedH1(source:JSONArray?):List<H1>? {
        if(source==null || source.length()<1 || source.length()>160) return null
        val result=ArrayList<H1>(source.length())
        for(i in 0 until source.length()) {
            val candle=source.optJSONArray(i) ?: return null
            if(candle.length()<6) return null
            val t=candle.optLong(0,-1L)
            val o=candle.optDouble(1,Double.NaN)
            val hi=candle.optDouble(2,Double.NaN)
            val lo=candle.optDouble(3,Double.NaN)
            val c=candle.optDouble(4,Double.NaN)
            if(t<=0L || t%WILLIAMS_H1_MS!=0L ||
                hi<=0.0 || lo<=0.0 ||
                listOf(o,hi,lo,c).any { !it.isFinite() || it<=0 } ||
                hi<maxOf(o,lo,c) || lo>minOf(o,c) ||
                (result.isNotEmpty() && t-result.last().start!=WILLIAMS_H1_MS))
                return null
            result+=H1(t,hi,lo)
        }
        return result
    }

    private fun exitRow(trade:JSONObject):JSONObject =
        JSONObject().put("symbol",trade.optString("symbol").take(24))
            .put("direction",trade.optString("direction"))
            .put("recorded_exit_reason",trade.optString("recorded_exit_reason"))
            .put("closed_at_ms",trade.optLong("closed_at_ms",0L))
            .put("opened_at_ms",trade.optLong("opened_at_ms",0L))
            .put("state","NOT_VERIFIED")
            .put("price_extremes_are_executable_stops",false)
            .put("could_have_earned_more_by_holding_proven",false)
            .put("actual_fill_or_protection_proven_by_this_lens",false)
            .put("can_place_orders",false).put("can_change_stop_or_exit",false)

    fun assess(scanner:JSONObject, historicalExits:JSONObject, nowMs:Long):JSONObject {
        val all=rows(historicalExits.optJSONArray("recent_closed_trades")).take(10)
        val latestScan=scanner.optLong("last_scan_at",0L)
        val scannerValid=latestScan>0L && latestScan<=nowMs &&
            scanner.optString("scanner_state") in setOf("READY","READY_BLOCKED") &&
            scanner.optInt("symbols_scanned",-1)==APPROVED_FUTURES_SYMBOLS.size
        val candidates=if(scannerValid) rows(scanner.optJSONArray("candidates")) else emptyList()
        val results=JSONArray()
        var fullFive=0
        var partialThree=0
        var unknown=0
        for(trade in all) {
            val item=exitRow(trade)
            val symbol=item.optString("symbol")
            val direction=item.optString("direction")
            val opened=trade.optLong("opened_at_ms",0L)
            val closed=trade.optLong("closed_at_ms",0L)
            fun unverified(reason:String) {
                item.put("state",reason)
                item.put("book_3_bar_boundary",JSONObject.NULL)
                item.put("book_5_bar_boundary",JSONObject.NULL)
            }
            when {
                symbol !in APPROVED_FUTURES_SYMBOLS ||
                    direction !in setOf("LONG","SHORT") ||
                    opened<=0L || closed<=opened || closed>nowMs ->
                    unverified("TRADE_IDENTITY_OR_TIME_NOT_VERIFIED")
                !scannerValid || latestScan<closed ->
                    unverified("SCAN_HISTORY_NOT_PROVEN_AFTER_TRADE_EXIT")
                else -> {
                    val matching=candidates.filter { it.optString("symbol")==symbol }
                    val candles=if(matching.size==1)
                        recordedH1(matching.single().optJSONObject("decision_trace")?.optJSONArray("candles"))
                        else null
                    if(candles==null) {
                        unverified("UNIQUE_CONTIGUOUS_SOURCE_H1_NOT_VERIFIED")
                    } else {
                        // A full post-entry H1 bar cannot open before an
                        // intrabar fill. Partial opening bars do not count.
                        val first=(opened/WILLIAMS_H1_MS)*WILLIAMS_H1_MS+
                            (if(opened%WILLIAMS_H1_MS==0L) 0L else WILLIAMS_H1_MS)
                        // At an exact hour, previous bar HAS closed already.
                        val last=(closed/WILLIAMS_H1_MS)*WILLIAMS_H1_MS-WILLIAMS_H1_MS
                        val expected=if(last<first) 0L else (last-first)/WILLIAMS_H1_MS+1L
                        val observed=candles.filter { it.start>=first && it.start<=last &&
                            it.start<=Long.MAX_VALUE-WILLIAMS_H1_MS &&
                            it.start+WILLIAMS_H1_MS<=closed }
                        if(expected<3L || expected>160L || observed.size.toLong()!=expected ||
                            observed.firstOrNull()?.start!=first ||
                            observed.lastOrNull()?.start!=last ||
                            observed.zipWithNext().any { (a,b) ->
                                b.start-a.start!=WILLIAMS_H1_MS
                            }) {
                            unverified("COMPLETE_POST_ENTRY_H1_WINDOW_NOT_VERIFIED")
                        } else {
                            fun boundary(n:Int):Double=if(direction=="LONG")
                                observed.takeLast(n).minOf { it.low }
                                else observed.takeLast(n).maxOf { it.high }
                            item.put("book_3_bar_boundary",boundary(3))
                            item.put("book_5_bar_boundary",if(observed.size>=5)
                                boundary(5) else JSONObject.NULL)
                            item.put("state",if(observed.size>=5)
                                "THREE_AND_FIVE_CLOSED_H1_EXTREMES_OBSERVED"
                                else "ONLY_THREE_CLOSED_H1_EXTREMES_OBSERVED")
                            item.put("post_entry_full_closed_h1_count",observed.size)
                            item.put("first_full_h1_ms",first)
                            item.put("last_full_h1_ms",last)
                            item.put("book_reference_pdf_page",165)
                            item.put("source_note",
                                "TC2 p165 calls 3/5-bar structural STOP usual; opposing signal less common. Raw extrema only, tick/STOP not authenticated.")
                        }
                    }
                }
            }
            when(item.optString("state")) {
                "THREE_AND_FIVE_CLOSED_H1_EXTREMES_OBSERVED" -> fullFive++
                "ONLY_THREE_CLOSED_H1_EXTREMES_OBSERVED" -> partialThree++
                else -> unknown++
            }
            results.put(item)
        }
        return JSONObject()
            .put("schema","WILLIAMS_TC2_EXIT_HIERARCHY_BOOK_V1")
            .put("source","TC2_RUSSIAN_PDF_165")
            .put("book_common_exit","TRAILING_STOP_BEYOND_LOWEST_OR_HIGHEST_OF_LAST_3_OR_5_PRICE_BARS")
            .put("book_less_common_exit","OPPOSITE_WISE_MAN_SIGNAL")
            .put("book_partial_profit","OPTIONAL_HALF_POSITION_WITH_REMAINING_PROTECTED_NOT_MANDATORY")
            .put("source_is_closed_h1_only",true)
            .put("closed_h1_requires_full_bar_after_entry",true)
            .put("historical_3_and_5_observed",fullFive)
            .put("historical_only_3_observed",partialThree)
            .put("historical_unverified",unknown)
            .put("recent_closed_trades",results)
            .put("source_history_complete",false)
            .put("book_exit_outcome_optimality_verified",false)
            .put("trading_authority",false)
            .put("can_place_orders",false)
            .put("can_move_stop_or_override_tc2_exit",false)
            .put("interpretation","Three/five previous closed H1 raw extremes at recorded exit, NOT simulated historical stop orders. No intrabar path or profit comparison.")
    }
}
