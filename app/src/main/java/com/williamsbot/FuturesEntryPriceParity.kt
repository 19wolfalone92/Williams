package com.williamsbot

import java.math.BigDecimal

/**
 * Binance positionRisk entryPrice and matching-engine order avgPrice can differ
 * by one PRICE_FILTER tick due to exchange-side price representation/rounding.
 *
 * Quantity, side, entry client ID and exact signed fill membership MUST be
 * verified separately first. This tolerance alone is NEVER ownership proof
 * and never authorizes new exposure, orphan adoption, manual position exits
 * or a STOP on a different account.
 */
internal fun futuresEntryPriceMatchesSignedFill(
    signedFillVwap: BigDecimal?,
    signedPositionEntry: BigDecimal?,
    exchangePriceTick: BigDecimal?
): Boolean {
    if (signedFillVwap == null || signedPositionEntry == null ||
        signedFillVwap.signum() <= 0 || signedPositionEntry.signum() <= 0) return false
    val difference = signedFillVwap.subtract(signedPositionEntry).abs()
    if (difference.signum() == 0) return true
    if (exchangePriceTick == null || exchangePriceTick.signum() <= 0) return false
    // The 0.05% cap prevents a coarse-tick symbol from masking a genuine
    // foreign manual fill or stale campaign. Reject unavailable filter data.
    return difference <= exchangePriceTick &&
        difference <= signedFillVwap.multiply(BigDecimal("0.0005"))
}
