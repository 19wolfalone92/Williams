package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** New risk belongs to TC2; other editions remain independently observable.
 * Filled legacy campaigns keep their original management, without new adds. */
internal const val WILLIAMS_EXECUTION_POLICY = "TC2_LIVE_EDITIONS_OBSERVE_V1"
internal const val williamsExecutionProfile = williamsTc2Profile
internal val williamsObserverProfiles = listOf(williamsNtdProfile,williamsCourseProfile)
internal fun williamsNewExposureBlocker(source:String):String? =
    if(source==williamsExecutionProfile) null else "WILLIAMS_SOURCE_OBSERVATION_ONLY"
internal fun williamsExecutionPolicySnapshot():JSONObject = JSONObject()
    .put("mode",WILLIAMS_EXECUTION_POLICY).put("execution_profile",williamsExecutionProfile)
    .put("observer_profiles",JSONArray(williamsObserverProfiles)).put("one_campaign_per_symbol",true)
    .put("legacy_management","PINNED_SOURCE_NO_NEW_EXPOSURE")

internal fun williamsStructuralFractal(type:String,source:String):Boolean =
    source==williamsTc2Profile && type.removePrefix("SHORT_").removePrefix("LONG_")=="FRACTAL"

/** TC2 ch11 PDF149: until trigger or replacement; zero is structural validity.
 * Fresh closed-bar proof is still required at the Binance submission door. */
internal fun williamsH1SignalExpiry(at:Long,type:String,source:String):Long =
    if(williamsStructuralFractal(type,source)) 0L else at+5*WILLIAMS_H1_MS
internal fun williamsH1SignalTimeEligible(at:Long,type:String,source:String,now:Long):Boolean {
    if(at<=0L || at%WILLIAMS_H1_MS!=0L || at+WILLIAMS_H1_MS>now) return false
    val expiry=williamsH1SignalExpiry(at,type,source)
    return expiry==0L || now<expiry
}

internal fun williamsClosedH1HistoryBlocker(bars:List<WilliamsWaveBar>,now:Long):String? {
    if(bars.size<45 || bars.any { it.t<=0L || it.t+WILLIAMS_H1_MS>now }) return "CLOSED_H1_HISTORY_NOT_PROVEN"
    if(bars.zipWithNext().any { (a,b) -> b.t-a.t!=WILLIAMS_H1_MS }) return "H1_HISTORY_GAP"
    return if(now-(bars.last().t+WILLIAMS_H1_MS) in 0 until WILLIAMS_H1_MS) null else "H1_SNAPSHOT_STALE"
}

/** The active TC2 campaign exits by its exchange-protected stop, including
 * verified monotone stop trails. A fresh opposing H1 hypothesis is a diagnostic
 * warning, NOT a market-close instruction. This is an explicit project policy,
 * not a claim that every edition of Williams bans discretionary exits.
 * NTD/Course continue their independently pinned exit systems.
 */
internal fun williamsStrategyCanMarketClose(ownerProfile:String):Boolean =
    ownerProfile==williamsNtdProfile || ownerProfile==williamsCourseProfile

internal fun williamsOpposingSignalCanMarketClose(ownerProfile:String):Boolean =
    williamsStrategyCanMarketClose(ownerProfile)


/** TC2 ch10/12: WM2 and WM3 may add on the H1 signal *before* daily/H4
 * Alligator trend alignment. WM1 is explicitly countertrend. The older
 * cross-timeframe veto in the addition door accidentally suppressed these
 * textbook TC2 adds. Distinct NTD/Course management remains source-specific.
 * The Futures coordinator's reserved risk, stop protection, ownership and
 * account/reconciliation checks are ALWAYS mandatory.
 */
internal fun williamsAdditionRequiresHigherFrameTrendVeto(ownerProfile:String):Boolean =
    ownerProfile != williamsTc2Profile


/** Trading Chaos 2nd ed. chapter 12 (three possible FIRST presenting
 * Wise Men): a valid, independently confirmed H1 Super AO or WM3 fractal
 * does not require a bullish/bearish H4/D1 Alligator before the initial
 * entry. D1/H4 remain read-only market context and ranking evidence.
 *
 * This only adjusts the textbook signal-ranking gate. It NEVER overrides
 * history freshness, H1 trigger consumption, WM3 Teeth validity, funding,
 * exchange position ownership, risk budgets, protected stops, or Futures
 * reconciliation; those are checked independently before every Binance POST.
 */
internal fun williamsBookInitialContextRank(
    source:String,signalType:String,
    d1Bull:Boolean,d1Bear:Boolean,h4Bull:Boolean,h4Bear:Boolean,
    validatedCorrection:Boolean=false
):Int {
    val prior=directionalSignalContextRank(signalType,d1Bull,d1Bear,h4Bull,h4Bear)
    if(source!=williamsTc2Profile) return maxOf(prior,if(validatedCorrection) 2 else 0)
    val side=campaignSignalDirection(signalType) ?: return 0
    if(side !in setOf("LONG","SHORT")) return 0
    val type=signalType.removePrefix("SHORT_").removePrefix("LONG_")
    // TC2 chapter 12 supports exactly these THREE families as initial
    // presenting Wise Men. AO zero-line/saucer/twin-peaks and AC are
    // evidence or management in their respective contexts, never a
    // substitute independent initial TC2 entry.
    if(type !in setOf("REVERSAL","SUPER_AO","FRACTAL")) return 0
    return maxOf(prior,1,if(validatedCorrection) 2 else 0)
}


/** Source-specific initial H1 readiness. TC2 ch.12 needs complete H1
 * signal proof, not an arbitrary 45-bar D1/H4 history requirement.
 * Higher-timeframe analysis is retained as UNKNOWN when unavailable;
 * exchange account/mark freshness, risk and stop gates are unaffected.
 */
internal fun williamsInitialHigherFrameHistoryRequired(source:String):Boolean =
    source!=williamsTc2Profile
