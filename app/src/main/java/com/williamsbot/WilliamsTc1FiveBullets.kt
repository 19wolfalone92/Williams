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
 * TC1 PDF59 explicitly permits 5/34 as an approximation to wave-average
 * MFI; PDF82-83 uses Profitunity oscillator divergence. Keep that alternative
 * visible without relabelling a price oscillator as canonical volume MFI.
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
    // TC1 PDF82-83 requires observing the five signs at the degree actually
    // counted (roughly 100-140 bars). A missing scale or a partial wave
    // is NOT evidence of a completed Point Zero or permission to reverse.
    val scaleBars=scenario.optInt("scale_bars",0)
    val scaleValid=scaleBars in 100..140
    val obs=scenario.optLong("observed_at",0L)
    val anchors=scenario.optJSONArray("anchors")
    val w5Known=if(anchors!=null && anchors.length()>=6)
        anchors.optJSONObject(5)?.optLong("known_at",0L) ?: 0L else 0L
    val chronological=w5Known>0L && obs>=w5Known
    return JSONObject()
        .put("source","TC1_CH7_PDF53_AND_CH9_PDF82_83")
        .put("status",when {
            !w5 -> "NO_PROVISIONAL_COMPLETED_FIVE_WAVE_PIVOT"
            !mfiVerified -> "MFI_DIVERGENCE_UNVERIFIED"
            !scaleValid -> "WAVE_SCALE_UNVERIFIED"
            !chronological -> "W5_PIVOT_NOT_CAUSALLY_CONFIRMED"
            observed<5 -> "TC1_FIVE_BULLETS_INCOMPLETE"
            else -> "FIVE_SIGNS_OBSERVED_UNCERTIFIED_WAVE_DEGREE"
        })
        .put("checks",checks).put("observed_or_proxy_count",observed)
        .put("required_count",5)
        .put("mfi_source",if(mfiVerified) mfiSource else JSONObject.NULL)
        .put("book_described_5_34_alternative",JSONObject()
            .put("source","TC1_CH7_PDF59_AND_CH9_PDF82_83")
            .put("status",if(w5 && scaleValid && chronological && scenario.optBoolean("divergence"))
                "OBSERVED_BOOK_5_34_APPROXIMATION" else "NOT_OBSERVED_OR_WAVE_SCALE_UNVERIFIED")
            .put("divergence_observed",w5 && scaleValid && chronological && scenario.optBoolean("divergence"))
            .put("canonical_mfi_verified",false).put("wave_degree_certified",false)
            .put("can_place_orders",false).put("can_close",false).put("can_reverse",false))
        .put("tc1_mfi_distinct_from_course_ao",true)
        .put("wave_scale_100_140_observed",scaleValid)
        .put("wave_scale_bars",scaleBars)
        .put("last_w5_pivot_causally_known",chronological)
        .put("w5_known_at",if(chronological) w5Known else JSONObject.NULL)
        .put("point_zero_certified",false)
        .put("book_bullets_vs_proxies","TC1_MFI_AND_PROFITUNITY_MACD_NOT_AUTO_EQUALS_AO_AND_BASE_VOLUME")
        .put("this_is_not_course_four_sign_check",true)
        .put("wave_degree_confirmed",false).put("can_place_orders",false)
        .put("can_close",false).put("can_reverse",false)
}
