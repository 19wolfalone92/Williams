package com.williamsbot

/**
 * NTD price-bar color rule. This is deliberately independent of AO/AC momentum:
 * a bullish price bar closes above its own open.
 */
internal fun isBullishWilliamsPriceBar(open: Double, close: Double): Boolean =
    open.isFinite() && close.isFinite() && close > open
