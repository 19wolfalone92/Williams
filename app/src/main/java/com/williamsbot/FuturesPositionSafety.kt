package com.williamsbot

internal data class FuturesLiquidationProtectionCheck(
    val status: String,
    val blocker: String?,
    val liquidationPrice: Double? = null,
    val stopGapPct: Double? = null
)

/** Unknown exchange data blocks admission; it never authorizes an automatic exit. */
internal fun assessFuturesLiquidationProtection(
    direction: String,
    protectiveStop: String,
    rawLiquidationPrice: Any?,
    minimumBufferPct: Double = 0.01
): FuturesLiquidationProtectionCheck {
    val stop = protectiveStop.toDoubleOrNull()
    val liquidation = rawLiquidationPrice?.toString()?.toDoubleOrNull()
    if (direction !in setOf("LONG", "SHORT") ||
        stop == null || !stop.isFinite() || stop <= 0.0 ||
        liquidation == null || !liquidation.isFinite() || liquidation <= 0.0 ||
        !minimumBufferPct.isFinite() || minimumBufferPct < 0.0 || minimumBufferPct >= 1.0
    ) return FuturesLiquidationProtectionCheck(
        "UNKNOWN", "FUTURES_LIQUIDATION_NOT_VERIFIED: positive exchange liquidation price and valid structural stop are required"
    )
    val gap = if (direction == "LONG") (stop - liquidation) / stop else (liquidation - stop) / stop
    if (!gap.isFinite() || gap <= 0.0 || gap + 1e-12 < minimumBufferPct) {
        return FuturesLiquidationProtectionCheck(
            "UNSAFE", "FUTURES_LIQUIDATION_STOP_UNSAFE: structural stop is not safely before exchange liquidation",
            liquidation, gap
        )
    }
    return FuturesLiquidationProtectionCheck("PASS", null, liquidation, gap)
}
