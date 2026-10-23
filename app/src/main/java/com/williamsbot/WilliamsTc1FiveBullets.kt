package com.williamsbot

import org.json.JSONObject

/**
 * Trading Chaos (1st edition), chapter 7, PDF p53: FIVE 'magic bullets'
 * for an ending wave-3 subdivision seen as a complete five-wave pattern
 * on a lower timeframe.
 *
 * Important edition distinction: TC1 lists a divergence in its MFI
 * oscillator AND a turn in Profitunity MACD; Course Level III PDF p77
 * lists FOUR separate signs, including W3/W5 5/34 oscillator divergence.
 * AO divergence is never silently substituted for TC1 MFI divergence.
 *
 * Our market input has Binance kline BASE VOLUME rather than canonical
 * MFI/tick series. Thus TC1 bullets remain explicitly unverified unless
 * an independent MFI divergence proof with data provenance is supplied.
 * This is a diagnostic, never an automatic entry or exit signal.
 */
internal fun williamsTc1FiveBulletEvidence(
    scenario:JSONObject,
    verifiedMfiDivergence:Boolean?=null,
    mfiSource:String?=null
):JSONObject {
    val w5=scenario.optString("label")=="W5" &&
        scenario.optString("status")=="PROVISIONAL"
    // Never treat a caller-provided true as proved without source metadata.
    val mfiVerified=verifiedMfiDivergence==true &&
        mfiSource in setOf("EXCHANGE_VERIFIED_CANONICAL_MFI","MANUALLY_VERIFIED_TC1_CHART")
    val checks=JSONObject()
        .put("mfi_divergence",if(mfiVerified) "VERIFIED_EXTERNAL_MFI" else "NOT_VERIFIED")
        .put("target_zone",if(scenario.optBoolean("target_zone")) "NUMERICAL_PROXY" else "NOT_OBSERVED")
        .put("terminal_fractal",if(scenario.optBoolean("terminal_fractal")) "CONFIRMED_PRICE_PIVOT" else "NOT_OBSERVED")
        .put("extreme_squat",if(scenario.optBoolean("squat")) "BASE_VOLUME_PROXY_ONLY" else "NOT_OBSERVED")
        .put("profitunity_macd_momentum_turn",if(scenario.optBoolean("momentum_shift"))
            "AO_SIGNAL_PROXY_ONLY" else "NOT_OBSERVED")
    val observed=listOf("target_zone","terminal_fractal","extreme_squat",
        "profitunity_macd_momentum_turn").count {
        checks.optString(it) !in listOf("NOT_OBSERVED","NOT_VERIFIED")
    } + if(mfiVerified) 1 else 0
    return JSONObject()
        .put("source","TC1_CH7_PDF53")
        .put("status",when {
            !w5 -> "NO_PROVISIONAL_COMPLETED_FIVE_WAVE_PIVOT"
            !mfiVerified -> "MFI_DIVERGENCE_UNVERIFIED"
            observed<5 -> "TC1_FIVE_BULLETS_INCOMPLETE"
            else -> "FIVE_SIGNS_OBSERVED_UNCERTIFIED_WAVE_DEGREE"
        })
        .put("checks",checks).put("observed_or_proxy_count",observed)
        .put("required_count",5)
        .put("mfi_source",if(mfiVerified) mfiSource else JSONObject.NULL)
        .put("tc1_mfi_distinct_from_course_ao",true)
        .put("this_is_not_course_four_sign_check",true)
        .put("wave_degree_confirmed",false).put("can_place_orders",false)
        .put("can_close",false).put("can_reverse",false)
}
