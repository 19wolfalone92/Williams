package com.williamsbot

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Execution-only arithmetic. Indicators may use Double; values sent to Binance
 * must pass through this class so tick/step arithmetic is decimal-exact.
 */
object ExecutionMath {
    fun floorToStepDecimal(value: BigDecimal, step: BigDecimal): BigDecimal {
        require(step > BigDecimal.ZERO)
        val units = value.divide(step, 0, RoundingMode.DOWN)
        return units.multiply(step).stripTrailingZeros()
    }

    fun floorToStep(value: Double, step: Double): Double {
        require(value.isFinite() && step.isFinite() && step > 0.0)
        return floorToStepDecimal(BigDecimal.valueOf(value), BigDecimal.valueOf(step)).toDouble()
    }

    fun decimal(value: Double): BigDecimal = BigDecimal.valueOf(value)

    fun decimal(value: String): BigDecimal =
        value.toBigDecimalOrNull() ?: BigDecimal.ZERO

    fun plain(value: BigDecimal, scale: Int = 18): String =
        value.setScale(scale.coerceIn(0, 18), RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()

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
