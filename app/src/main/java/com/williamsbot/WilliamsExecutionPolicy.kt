package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Independent source-owned lifecycles; Course remains observation-only. */
internal const val WILLIAMS_EXECUTION_POLICY = "TC2_NTD_OWNED_LIFECYCLES_V2"
internal const val williamsExecutionProfile = williamsTc2Profile
internal val williamsExecutionProfiles = listOf(williamsTc2Profile,williamsNtdProfile)
internal val williamsObserverProfiles = listOf(williamsCourseProfile)
/** Old releases persisted TC2 as a fixed, non-selectable value. It must not
 * silently disable NTD after the source lifecycle upgrade. Explicit selections
 * saved under this policy retain their meaning across process restarts. */
internal fun williamsRestoredExecutionSelection(stored:String?,policy:String?):String =
    if(policy==WILLIAMS_EXECUTION_POLICY && stored!=null &&
        (stored==williamsUnifiedProfile || stored in williamsExecutionProfiles)) stored else williamsUnifiedProfile
internal fun williamsNewExposureBlocker(source:String):String? =
    if(source in williamsExecutionProfiles) null else "WILLIAMS_SOURCE_OBSERVATION_ONLY"
internal fun williamsExecutionPolicySnapshot():JSONObject = JSONObject()
    .put("mode",WILLIAMS_EXECUTION_POLICY).put("execution_profile",williamsExecutionProfile)
    .put("execution_profiles",JSONArray(williamsExecutionProfiles))
    .put("observer_profiles",JSONArray(williamsObserverProfiles)).put("one_campaign_per_symbol",true)
    .put("tc2_reversal_policy",WILLIAMS_TC2_REVERSAL)
    .put("tc2_reversal_requires",JSONArray(listOf("OWNED_EXIT_FILLED","SIGNED_FLAT","OLD_ORDERS_CLEANED",
        "FRESH_H1_SIGNAL","NEW_RISK_BUDGET","NEW_PROTECTIVE_STOP")))
    .put("legacy_management","PINNED_SOURCE_VALIDATED_CYCLE_REQUIRED_FOR_ADDITIONS")

internal fun williamsStructuralFractal(type:String,source:String):Boolean =
    source in setOf(williamsTc2Profile,williamsNtdProfile) && type.removePrefix("SHORT_").removePrefix("LONG_")=="FRACTAL"

/** TC2 PDF149 / NTD PDF43: until trigger or replacement; zero is structural validity.
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

/** Trading Chaos 2nd ed., ch.11-12: a validated opposite Wise-Man STOP
 * breakout can close a campaign, alongside monotone protective stop trails.
 * A bare contrary hypothesis, wave label or arbitrary strategy request CANNOT.
 * Source-pinned NTD/Course retain their separate exits.
 * Reversal into opposite NEW exposure is never implicit: it requires confirmed
 * flat, a fresh entry candidate and all new-risk/exchange-protection gates.
 */
internal fun williamsStrategyCanMarketClose(ownerProfile:String):Boolean =
    ownerProfile in williamsSourceProfiles

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
    ownerProfile !in williamsExecutionProfiles


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
    if(source==williamsNtdProfile) return if(signalType.removePrefix("SHORT_").removePrefix("LONG_") in williamsNtdFamilies &&
        campaignSignalDirection(signalType)!=null) maxOf(prior,1) else 0
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
    source !in williamsExecutionProfiles

/** Reject discretionary TC2 closes that lack a book-authenticated opposite
 * H1 price trigger. The coordinator independently binds this reason to the
 * durable signal/exchange proof; a reason string alone is NOT authorization.
 */
internal fun williamsMarketExitBlocker(owner: String, reason: String): String? {
    if(reason=="MANUAL_EXIT" || reason=="KILL_SWITCH") return null
    return when(owner) {
        williamsTc2Profile -> if(reason in setOf(
            "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",
            "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION"
        )) null else "TC2_EXIT_REQUIRES_BOOK_OPPOSITE_TRIGGER"
        williamsNtdProfile -> if(reason in williamsNtdBookExitReasons) null else "NTD_EXIT_REQUIRES_OWN_BOOK_RULE"
        williamsCourseProfile -> null
        else -> "EXIT_STRATEGY_OWNER_UNVERIFIED"
    }
}

/** TC2 ch12 + NTD ch9: an opposing Wise-Man setup is NOT an obligation
 * to realize a loss. Opposing-signal discretionary exits are admitted only
 * while there is a conservative, exchange-confirmed positive mark-to-entry
 * buffer after estimated round-trip fees and slippage. The project uses 0.35%
 * (2 * 0.10% per-side fee reserve + 0.15% execution slippage); it is NOT a
 * statement that Binance can guarantee this fill or book-prescribed fee rate.
 *
 * This gate only affects strategy-initiated OPPOSITE exits. It NEVER disables
 * protective STOP_MARKET, operator risk reduction, or proven emergency exits.
 * There is deliberately no cap on how long a valid campaign may stay protected.
 */
internal const val WILLIAMS_TC2_OPPOSITE_EXIT_COST_BUFFER = 0.0035

internal fun williamsTc2OppositeExitProfitBlocker(
    side:String, liveEntry:Double, freshMark:Double,
    costBuffer:Double=WILLIAMS_TC2_OPPOSITE_EXIT_COST_BUFFER,
    tick:Double=0.0
):String? {
    if(side !in setOf("LONG","SHORT") || !liveEntry.isFinite() || liveEntry<=0 ||
        !freshMark.isFinite() || freshMark<=0 || !costBuffer.isFinite() ||
        costBuffer<0 || !tick.isFinite() || tick<0)
        return "TC2_OPPOSITE_EXIT_PROFIT_EVIDENCE_UNVERIFIED"
    val grossReturn=if(side=="LONG") (freshMark-liveEntry)/liveEntry
        else (liveEntry-freshMark)/liveEntry
    // At equality we do not have a positive expected net outcome. One tick
    // makes the classification conservative at a round-off boundary.
    return if(grossReturn>costBuffer+tick/liveEntry) null
        else "TC2_OPPOSITE_EXIT_NOT_PROFITABLE_AFTER_COST_RESERVE"
}

/** Durable exchange-mutation fence for the TC2 ch11-12 opposite Wise-Man exit.
 * A caller-supplied reason is not proof: require exactly the closed H1 signal
 * persisted by the causal scenario scanner after entry, recently observed, and
 * a fresh Binance CONTRACT price STILL beyond that signal's stop trigger.
 * No reverse entry is authorized by a close. Ambiguous history never passes.
 */
internal fun williamsTc2OppositeExitEvidenceBlocker(
    owner:JSONObject?, signedDecision:String?, symbol:String, currentDirection:String,
    reason:String, openedAt:Long, now:Long, contractPrice:Double
):String? {
    if(williamsMarketExitBlocker(williamsTc2Profile,reason)!=null)
        return "TC2_EXIT_REASON_NOT_BOOK_APPROVED"
    if(owner==null || owner.optString("symbol")!=symbol ||
        owner.optString("direction")!=currentDirection ||
        owner.optString("strategy_profile")!=williamsTc2Profile ||
        openedAt<=0L || now<=0L || !contractPrice.isFinite() || contractPrice<=0)
        return "TC2_OPPOSITE_EXIT_OWNER_OR_CONTRACT_UNVERIFIED"
    val decision=runCatching { JSONObject(signedDecision.orEmpty()) }.getOrNull()
        ?: return "TC2_OPPOSITE_EXIT_DECISION_NOT_DURABLE"
    val opposite=decision.optJSONObject("opposition")
        ?: return "TC2_OPPOSITE_EXIT_NO_CONFIRMED_TRIGGER"
    val event=opposite.optJSONObject("event")
        ?: return "TC2_OPPOSITE_EXIT_EVENT_UNVERIFIED"
    val uncertainty=decision.optJSONArray("uncertain")
        ?: return "TC2_OPPOSITE_EXIT_UNCERTAINTY_NOT_RECORDED"
    if(uncertainty.length()>0) return "TC2_OPPOSITE_EXIT_AMBIGUOUS_OR_INCOMPLETE_HISTORY"
    val examinedAt=decision.optLong("observed_at_ms")
    val signalAt=opposite.optLong("signal_bar_time_ms")
    val eventAt=event.optLong("known_at_ms")
    val level=opposite.optDouble("trigger",Double.NaN)
    val side=opposite.optString("direction")
    val signalId=opposite.optString("signal_id")
    if(decision.optString("trigger_price_basis")!="CONTRACT_PRICE" ||
        decision.optString("direction")!=currentDirection ||
        decision.optLong("scope_started_at_ms")!=openedAt ||
        opposite.optString("strategy_profile")!=williamsTc2Profile ||
        opposite.optString("execution_semantics")!="STOP_ENTRY" ||
        event.optString("state")!="TRIGGERED" ||
        signalId.isBlank() || signalAt<=0L ||
        signalAt>Long.MAX_VALUE-WILLIAMS_H1_MS ||
        eventAt<signalAt+WILLIAMS_H1_MS || eventAt<=openedAt ||
        eventAt>examinedAt || examinedAt>now ||
        now-examinedAt !in 0L..120_000L ||
        !level.isFinite() || level<=0 ||
        !(currentDirection=="LONG" && side=="SHORT" && contractPrice<=level ||
          currentDirection=="SHORT" && side=="LONG" && contractPrice>=level))
        return "TC2_OPPOSITE_EXIT_TRIGGER_NOT_CURRENT_AND_PROVEN"
    // This record is persisted by management before requesting a mutation,
    // and is bound to this exact durable signal (not a guessed wave count).
    val proof=owner.optJSONObject("edition_management")?.optJSONObject("exit_evidence")
        ?: return "TC2_OPPOSITE_EXIT_MANAGEMENT_PROOF_MISSING"
    if(proof.optString("reason")!=reason ||
        proof.optString("signal_id")!=signalId ||
        proof.optLong("observed_at_ms")!=examinedAt ||
        proof.optJSONObject("opposition_check")?.optJSONObject("opposition")
            ?.optString("signal_id")!=signalId)
        return "TC2_OPPOSITE_EXIT_MANAGEMENT_PROOF_MISMATCH"
    return null
}

internal fun williamsStopMoveLabel(reason: String): String = when(reason) {
    "TC2_LAST_5_CLOSED_BARS" -> "TC2: защита за последними 5 закрытыми H1-барами"
    "TC2_LAST_3_CLOSED_BARS" -> "TC2: защита за последними 3 закрытыми H1-барами"
    "CONFIRMED_H1_FRACTAL" -> "Подтверждённый H1-фрактал"
    "COST_BUFFERED_BREAKEVEN_1R" -> "Безубыток после 1R с учётом издержек · дополнительное правило проекта"
    "UNSPECIFIED_STRUCTURAL_TRAIL","" -> "Основание переноса не записано"
    else -> reason
}
