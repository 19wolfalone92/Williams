package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * TC2 voluntary stop-TIGHTENING review, NOT a stop-placement / exit authority.
 *
 * This is deliberately conservative and includes additional engineering
 * caution (post-entry H1 evidence, H4 counter-move, closed-bar volatility).
 * These vetoes are NOT presented as quotations of TC2. The already accepted
 * exchange STOP remains unchanged whenever evidence is missing or conflicting.
 *
 * Hypothetical H1/H4 Elliott wave labels are logged only. Unverified wave
 * hypotheses must never alone loosen stops, close or open a trade.
 */
internal data class WilliamsStopTrailReview(
    val proposal: FuturesStopTrailProposal?,
    val blocker: String,
    val evidence: JSONObject
)

/**
 * Engineering veto, NOT a quotation from TC2: a freshly printed H1 extreme
 * at the very end of a large adverse impulse has no right-hand swing proof.
 * Tightening just beyond that extreme can place the exchange stop into
 * continuing ordinary pullback. Old signed STOP remains untouched.
 *
 * Thresholds are conservative hypotheses to validate out of sample; they
 * cannot establish forecast edge. Confirmed t+2 fractals remain a separate
 * structurally justified exit-protection rule.
 */
internal data class WilliamsH1ShockVeto(
    val block: Boolean, val freshCounterExtreme: Boolean,
    val rangeOverMedian: Double, val closeLocation: Double
)
internal fun williamsH1CounterImpulseVeto(
    direction:String, lastFive:List<WilliamsWaveBar>, lastTwelve:List<WilliamsWaveBar>
):WilliamsH1ShockVeto {
    require(direction in setOf("LONG","SHORT") && lastFive.size==5 && lastTwelve.size==12)
    val recent=lastFive.last()
    val previous=lastFive.dropLast(1)
    val ranges=lastTwelve.map { it.h-it.l }.sorted()
    val median=(ranges[5]+ranges[6])/2.0
    val range=recent.h-recent.l
    val closeLocation=if(range>0) ((recent.c-recent.l)/range).coerceIn(0.0,1.0) else 0.5
    val freshCounterExtreme=if(direction=="LONG") recent.l<previous.minOf { it.l }
        else recent.h>previous.maxOf { it.h }
    val ratio=if(median>0) range/median else 0.0
    return WilliamsH1ShockVeto(freshCounterExtreme && ratio>=1.5 &&
        (if(direction=="LONG") closeLocation<=0.25 else closeLocation>=0.75),
        freshCounterExtreme,ratio,closeLocation)
}

internal fun reviewWilliamsTc2StopTightening(
    direction: String, oldStop: Double, entry: Double, initialStop: Double?,
    mark: Double, tick: Double, openedAtMs: Long, asOfMs: Long,
    h1Input: List<WilliamsWaveBar>, h4Input: List<WilliamsWaveBar>,
    feeAndSlippagePct: Double = 0.0035,
    campaignQuantity: Double? = null
): WilliamsStopTrailReview {
    val audit = JSONObject().put("schema","WILLIAMS_TC2_STOP_REVIEW_V1")
        .put("as_of_ms",asOfMs).put("direction",direction)
        .put("entry",entry).put("old_stop",oldStop).put("mark_price",mark)
        .put("opened_at_ms",openedAtMs).put("wave_labels_are_hypotheses",true)
        .put("wave_hypotheses_can_authorize_orders",false)
        .put("existing_exchange_stop_retained_if_blocked",true)
    fun refuse(reason:String):WilliamsStopTrailReview =
        WilliamsStopTrailReview(null,reason,audit.put("decision","DEFER").put("blocker",reason))
    if(direction !in setOf("LONG","SHORT") || openedAtMs<=0L || asOfMs<=0L ||
        !listOf(oldStop,entry,mark,tick,feeAndSlippagePct).all { it.isFinite() } ||
        oldStop<=0.0 || entry<=0.0 || mark<=0.0 || tick<=0.0 ||
        feeAndSlippagePct<0.0 || feeAndSlippagePct>0.1) return refuse("INVALID_STOP_REVIEW_INPUT")
    if(campaignQuantity!=null && (!campaignQuantity.isFinite() || campaignQuantity<=0.0))
        return refuse("INVALID_EXCHANGE_CAMPAIGN_QUANTITY")

    // Both resolutions require genuine *closed* and recent exchange candles.
    // W4/H1 cannot be inferred from stale or still-forming H4 kline values.
    val h1=runCatching { closedWilliamsWaveBars(h1Input,"1h",asOfMs) }
        .getOrNull() ?: return refuse("CLOSED_H1_HISTORY_NOT_VERIFIED")
    val h4=runCatching { closedWilliamsWaveBars(h4Input,"4h",asOfMs) }
        .getOrNull() ?: return refuse("CLOSED_H4_HISTORY_NOT_VERIFIED")
    val candidate=planWilliamsCampaignStop(direction,oldStop,entry,initialStop,mark,tick,
        openedAtMs,h1.map { FuturesTrailBar(it.t,it.h,it.l) },feeAllowance=feeAndSlippagePct)
        ?: return refuse("NO_NEW_CONFIRMED_H1_PROTECTION_STRUCTURE")

    val lastFive=h1.takeLast(5)
    val lastH4=h4.takeLast(2)
    val last12H1=h1.takeLast(12)
    val last12H4=h4.takeLast(12)
    fun waveBarJson(bar:WilliamsWaveBar)=JSONObject()
        .put("open_time_ms",bar.t).put("open",bar.o).put("high",bar.h)
        .put("low",bar.l).put("close",bar.c).put("volume",bar.v)
    audit.put("source_rule",candidate.reason).put("candidate_stop",candidate.stop)
        .put("source_fractal_at_ms",candidate.fractalAtMs ?: JSONObject.NULL)
        .put("h1_last_five_closed",JSONArray(lastFive.map(::waveBarJson)))
        .put("h4_last_two_closed",JSONArray(lastH4.map(::waveBarJson)))
        // Required to reproduce BOTH noise-range and H4 range-depth filters.
        // A five/two-bar trace alone could not audit those 12-bar calculations.
        .put("h1_last_twelve_closed",JSONArray(last12H1.map(::waveBarJson)))
        .put("h4_last_twelve_closed",JSONArray(last12H4.map(::waveBarJson)))
    if(candidate.fractalAtMs!=null) {
        val pivotIndex=h1.indexOfFirst { it.t==candidate.fractalAtMs }
        if(pivotIndex>=0) {
            audit.put("source_fractal_h1_context",
                JSONArray(h1.subList((pivotIndex-12).coerceAtLeast(0),
                    (pivotIndex+3).coerceAtMost(h1.size)).map(::waveBarJson)))
                .put("source_fractal_last_right_bar_close_ms",
                    h1[(pivotIndex+2).coerceAtMost(h1.lastIndex)].t+WILLIAMS_H1_MS)
        }
    }
    val afterEntry=lastFive.all { it.t>=openedAtMs }
    audit.put("five_full_h1_bars_after_entry",afterEntry)
    // A 5-bar window that contains pre-entry candles is not proof that the
    // CURRENT campaign has developed a full, new H1 protective structure.
    if(candidate.reason.startsWith("TC2_LAST_") && !afterEntry)
        return refuse("FIVE_CLOSED_H1_BARS_AFTER_ENTRY_NOT_YET_AVAILABLE")

    // Price evidence, not an unverified Elliott degree: opposite H4 lower
    // lows/lower closes (LONG), or higher highs/higher closes (SHORT), is a
    // plausible developing correction; do not ratchet a discretionary 5-bar
    // STOP into it before fresh favorable structure is observed.
    val a=lastH4[0];val b=lastH4[1]
    val h4Adverse=if(direction=="LONG") b.l<a.l && b.c<a.c else b.h>a.h && b.c>a.c
    audit.put("h4_adverse_price_structure",h4Adverse)
    // The last 12 completed H4 bars describe where current price sits within
    // the *observed* H4 price range. This is a measured retracement proxy,
    // NOT proof that Elliott W4 or an ABC correction has completed.
    val h4Reference=last12H4
    val high12=h4Reference.maxOf { it.h }
    val low12=h4Reference.minOf { it.l }
    val h4Range=high12-low12
    val observedRetracement=if(h4Range>0) {
        (if(direction=="LONG") (high12-b.c)/h4Range else (b.c-low12)/h4Range)
            .coerceIn(0.0,1.0)
    } else Double.NaN
    audit.put("h4_12_bar_observed_range_high",high12)
        .put("h4_12_bar_observed_range_low",low12)
        .put("h4_price_retracement_fraction",
            if(observedRetracement.isFinite()) observedRetracement else JSONObject.NULL)
        .put("h4_retracement_is_confirmed_elliott_correction",false)
    val h4Pivots=confirmedWilliamsWaveAnchors(h4,williamsWaveFrames.getValue("4h"))
        .filter { it.knownAt<=asOfMs }.takeLast(3)
    audit.put("h4_confirmed_fractal_pivots",JSONArray(h4Pivots.map { it.toJson() }))
    if(h4Adverse && candidate.reason.startsWith("TC2_LAST_"))
        return refuse("H4_CORRECTION_CAUTION_RETAIN_EXCHANGE_STOP")

    // Do not place a voluntary stop directly inside the typical range of
    // an H1 candle. This is an explicit, bounded ENGINEERING noise filter,
    // not a textbook ATR rule; never claims to eliminate stop-outs.
    val ranges=last12H1.map { it.h-it.l }.sorted()
    val med=(ranges[5]+ranges[6])/2.0
    // A fixed 0.15%-of-mark CAP previously shrank the H1 noise guard to
    // almost nothing on highly volatile pairs. Use the observed closed-bar
    // median itself with a two-tick floor, for all symbols.
    val minDistance=max(2.0*tick,med*0.5)
    val distance=if(direction=="LONG") mark-candidate.stop else candidate.stop-mark
    audit.put("h1_median_closed_range",med).put("minimum_market_gap",minDistance)
        .put("candidate_mark_gap",distance)
    if(!med.isFinite() || med<=0.0 || distance<=minDistance)
        return refuse("CANDIDATE_STOP_INSIDE_H1_PRICE_NOISE")
    val h1Shock=williamsH1CounterImpulseVeto(direction,lastFive,last12H1)
    audit.put("h1_fresh_adverse_extreme",h1Shock.freshCounterExtreme)
        .put("h1_last_bar_range_to_median",h1Shock.rangeOverMedian)
        .put("h1_last_bar_close_location",h1Shock.closeLocation)
        .put("h1_shock_veto_engineering_not_book_rule",true)
    if(candidate.reason.startsWith("TC2_LAST_") && h1Shock.block)
        return refuse("UNCONFIRMED_H1_COUNTER_IMPULSE_RETAIN_SIGNED_STOP")

    // Proper multi-TF wave genealogy is not yet independently validated.
    // Preserve the actual contemporaneous alternatives as read-only audit.
    val wave=runCatching {
        buildWilliamsWaveHierarchy(mapOf("1h" to h1,"4h" to h4),asOfMs,listOf("4h","1h"))
    }.getOrNull()
    audit.put("wave_h1_candidates",JSONArray(wave?.scenarios?.filter { it.frame=="1h" }
        ?.take(5)?.map { it.activeLabel+":"+it.activeDirection+":"+it.status } ?: emptyList<String>()))
        .put("wave_h4_candidates",JSONArray(wave?.scenarios?.filter { it.frame=="4h" }
            ?.take(5)?.map { it.activeLabel+":"+it.activeDirection+":"+it.status } ?: emptyList<String>()))
        .put("wave_frame_errors",JSONObject(wave?.frameErrors ?: emptyMap<String,String>()))

    // Hypothetical closure economics for the currently CONFIRMED total
    // quantity can be completed by the caller. Not a certified realized PnL:
    // limit/slippage/funding may deviate. Do NOT require a profitable stop.
    val sign=if(direction=="LONG") 1.0 else -1.0
    val oldNetPerUnit=sign*(oldStop-entry)-entry*feeAndSlippagePct
    val newNetPerUnit=sign*(candidate.stop-entry)-entry*feeAndSlippagePct
    audit.put("old_estimated_net_per_unit",oldNetPerUnit)
        .put("new_estimated_net_per_unit",newNetPerUnit)
        .put("incremental_risk_reduction_per_unit",newNetPerUnit-oldNetPerUnit)
        .put("estimated_fee_slippage_fraction_of_entry",feeAndSlippagePct)
        .put("net_estimate_includes_funding",false)
        .put("net_estimate_includes_historical_addition_fees",false)
        .put("net_estimate_includes_future_fill_impact",false)
        .put("campaign_qty_exchange_verified",campaignQuantity != null)
    if(campaignQuantity != null) {
        // A single signed exchange position may aggregate fills and add-ons.
        // Its volume-weighted exchange entry is used for indicative campaign
        // PnL, never confused with independently verified fill-level net PnL.
        audit.put("campaign_signed_exchange_quantity_abs",campaignQuantity)
            .put("campaign_entry_price_basis","BINANCE_CURRENT_WEIGHTED_AVERAGE")
            .put("old_estimated_net_campaign_usdt",oldNetPerUnit*campaignQuantity)
            .put("new_estimated_net_campaign_usdt",newNetPerUnit*campaignQuantity)
            .put("incremental_stop_risk_reduction_campaign_usdt",
                (newNetPerUnit-oldNetPerUnit)*campaignQuantity)
    } else {
        audit.put("campaign_entry_price_basis","UNVERIFIED_SUPERVISION_SNAPSHOT")
            .put("old_estimated_net_campaign_usdt",JSONObject.NULL)
            .put("new_estimated_net_campaign_usdt",JSONObject.NULL)
    }
    if(newNetPerUnit+1e-12<oldNetPerUnit)
        return refuse("STOP_MUST_NEVER_WIDEN_TOTAL_CAMPAIGN_RISK")

    val brief="%s|H1=%d|H4=%d|H4C=%s|GAP=%.6f|NET=%.6f".format(Locale.US,
        candidate.reason,h1.last().t,h4.last().t,if(h4Adverse) "Y" else "N",
        distance,newNetPerUnit)
    // Replaying the exact approved choice requires all closed inputs passed
    // into both the post-entry/fractal detector and H1/H4 wave grammar.
    // Persist the full causal window only for executable proposals; repeated
    // routine deferrals keep their compact 12-bar diagnostic footprint.
    audit.put("h1_full_closed_source",JSONArray(h1.map(::waveBarJson)))
        .put("h4_full_closed_source",JSONArray(h4.map(::waveBarJson)))
        .put("full_source_is_exclusively_closed_at_asof",true)
        .put("decision","PROPOSE_TIGHTEN_ONLY").put("brief_reason",brief)
    return WilliamsStopTrailReview(candidate.copy(reason=brief), "",audit)
}
