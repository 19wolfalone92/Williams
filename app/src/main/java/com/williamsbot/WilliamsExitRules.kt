package com.williamsbot

/**
 * NTD price-bar color rule. This is deliberately independent of AO/AC momentum:
 * a bullish price bar closes above its own open.
 */
internal fun isBullishWilliamsPriceBar(open: Double, close: Double): Boolean =
    open.isFinite() && close.isFinite() && close > open

/** Bill Williams' Alligator is calculated from median price (high + low) / 2. */
internal fun williamsMedianPrice(high: Double, low: Double): Double =
    (high + low) / 2.0
