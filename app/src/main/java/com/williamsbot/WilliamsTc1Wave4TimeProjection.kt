package com.williamsbot

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Trading Chaos, first edition, PDF63-64, fig.7-21:
 *
 * (W3 peak time - W1 peak time) * [1.38,1.62], projected from
 * the W2 base time, gives a *research* window for a possible W4 END.
 * This is not a mathematical certainty; the author describes where
 * most W4s end, and separately requires zero-line/motive context.
 *
 * Use the SOURCE wave's time-stamped price extrema (not when the
 * fractal became known). A historical target never becomes an order.
 */
internal fun williamsTc1Wave4TimeProjection(
    scenario:JSONObject, asOfMs:Long
):JSONObject {
    val out=JSONObject()
        .put("source_book","TC1_PDF63_64_FIG7_21")
        .put("status","UNVERIFIED")
        .put("wave_degree_certified",false)
        .put("can_place_orders",false)
        .put("hypothetical_only",true)
        .put("author_ratios","1.38_TO_1.62")
    val anchors=scenario.optJSONArray("anchors") ?:return out
    if(asOfMs<=0 || scenario.optString("frame")!="1h" ||
        scenario.optString("status")!="PROVISIONAL" ||
        scenario.optInt("scale_bars") !in 100..140 ||
        scenario.optLong("observed_at") !in 1..asOfMs ||
        anchors.length()<5) return out
    val a=(0..4).map { anchors.optJSONObject(it) ?:return out }
    val xt=a.map { it.optLong("extreme_at") }
    val known=a.map { it.optLong("known_at") }
    if(xt.any { it<=0 } || xt.zipWithNext().any { (x,y)->x>=y } ||
        known.any { it<=0 || it>asOfMs } ||
        a.indices.any { known[it]<=xt[it] } ||
        // All hypothetical wave pivots have become knowable *in order*.
        known.zipWithNext().any { (x,y)->x>=y }) return out
    val w1=xt[1];val w2=xt[2];val w3=xt[3];val w4=xt[4]
    val delta=w3-w1
    if(delta<=0) return out
    val duration=BigDecimal.valueOf(delta)
    val base=BigDecimal.valueOf(w2)
    val earliest=base.add(duration.multiply(BigDecimal("1.38")))
        .setScale(0,RoundingMode.CEILING)
    val latest=base.add(duration.multiply(BigDecimal("1.62")))
        .setScale(0,RoundingMode.FLOOR)
    if(earliest<=BigDecimal.ZERO || latest<=earliest ||
        latest>BigDecimal.valueOf(Long.MAX_VALUE)) return out
    val earliestMs=earliest.longValueExact()
    val latestMs=latest.longValueExact()
    return out.put("status",if(w4 in earliestMs..latestMs)
            "TIME_WINDOW_PIVOT_INSIDE_UNCERTIFIED" else
            "TIME_WINDOW_PIVOT_OUTSIDE_UNCERTIFIED")
        .put("w1_peak_extreme_at_ms",w1)
        .put("w2_base_extreme_at_ms",w2)
        .put("w3_peak_extreme_at_ms",w3)
        .put("w4_terminal_pivot_extreme_at_ms",w4)
        .put("w4_pivot_known_at_ms",known[4])
        .put("projection_earliest_ms",earliestMs)
        .put("projection_latest_ms",latestMs)
        .put("inside_projection",w4 in earliestMs..latestMs)
        .put("not_book_confirmed_trade_entry",true)
        .put("not_authoritative_wave_degree",true)
}
