package com.williamsbot

import kotlin.math.abs

/**
 * Pure last-mile guard for the actual tick-normalized conditional-entry order.
 * The caller remains responsible for directional stop geometry and exchange
 * quantity/notional filters; this guard prevents rounding from exceeding risk.
 */
internal object FuturesEntryRiskGuard {
    fun violation(
        quantity: Double,
        triggerPrice: Double,
        stopPrice: Double,
        feeBufferPerSideFraction: Double,
        slippageBufferFraction: Double,
        riskBudget: Double,
        equity: Double,
        maxEquityFraction: Double = 0.20,
    ): String? {
        val values = listOf(
            quantity, triggerPrice, stopPrice, feeBufferPerSideFraction,
            slippageBufferFraction, riskBudget, equity, maxEquityFraction,
        )
        if (!values.all(Double::isFinite)) return "non-finite normalized risk input"
        if (quantity <= 0.0 || triggerPrice <= 0.0 || stopPrice <= 0.0 ||
            riskBudget <= 0.0 || equity <= 0.0 || maxEquityFraction <= 0.0 ||
            feeBufferPerSideFraction < 0.0 || slippageBufferFraction < 0.0
        ) return "invalid normalized risk bounds"

        val stopDistance = abs(triggerPrice - stopPrice)
        val costReserve = triggerPrice *
            (2.0 * feeBufferPerSideFraction + slippageBufferFraction)
        val risk = quantity * (stopDistance + costReserve)
        val notional = quantity * triggerPrice
        if (!stopDistance.isFinite() || stopDistance <= 0.0 ||
            !costReserve.isFinite() || costReserve < 0.0 ||
            !risk.isFinite() || risk <= 0.0 || risk > riskBudget * 1.000001
        ) return "normalized loss estimate exceeds risk budget"
        if (!notional.isFinite() || notional <= 0.0 ||
            notional > equity * maxEquityFraction * 1.000001
        ) return "normalized notional exceeds equity cap"
        return null
    }
}
