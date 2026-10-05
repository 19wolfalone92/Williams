package com.williamsbot

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Execution-only arithmetic. Indicators may use Double; values sent to Binance
 * must pass through this class so tick/step arithmetic is decimal-exact.
 */
object ExecutionMath {
    fun floorToStep(value: Double, step: Double): Double {
        require(value.isFinite() && step.isFinite() && step > 0.0)
        val v = BigDecimal.valueOf(value)
        val s = BigDecimal.valueOf(step)
        val units = v.divide(s, 0, RoundingMode.DOWN)
        return units.multiply(s).stripTrailingZeros().toDouble()
    }

    fun floorToScale(value: Double, scale: Int): String =
        BigDecimal.valueOf(value)
            .setScale(scale.coerceIn(0, 8), RoundingMode.DOWN)
            .toPlainString()

    fun priceToTick(value: Double, tick: Double): Double =
        floorToStep(value, tick)

    fun quantityToStep(value: Double, step: Double): Double =
        floorToStep(value, step)

    fun isMultiple(value: Double, step: Double): Boolean {
        if (!value.isFinite() || !step.isFinite() || step <= 0.0) return false
        val v = BigDecimal.valueOf(value)
        val s = BigDecimal.valueOf(step)
        return v.remainder(s).compareTo(BigDecimal.ZERO) == 0
    }
}
