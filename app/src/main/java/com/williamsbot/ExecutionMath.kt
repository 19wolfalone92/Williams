package com.williamsbot

import java.math.BigDecimal
import java.math.RoundingMode

/** Execution arithmetic. Invalid filters or numbers must reject the order. */
object ExecutionMath {
    fun floorToStepDecimal(value: BigDecimal, step: BigDecimal): BigDecimal {
        require(value > BigDecimal.ZERO) { "Quantity/price must be positive" }
        require(step > BigDecimal.ZERO) { "Exchange step/tick must be positive" }
        val units = value.divide(step, 0, RoundingMode.DOWN)
        val result = units.multiply(step).stripTrailingZeros()
        require(result > BigDecimal.ZERO) { "Rounded quantity/price is zero" }
        return result
    }

    fun floorToStep(value: Double, step: Double): Double {
        require(value.isFinite() && value > 0.0 && step.isFinite() && step > 0.0) {
            "Quantity/price and step/tick must be finite and positive"
        }
        return floorToStepDecimal(BigDecimal.valueOf(value), BigDecimal.valueOf(step)).toDouble()
    }

    fun ceilToStep(value: Double, step: Double): Double {
        require(value.isFinite() && value > 0.0 && step.isFinite() && step > 0.0) {
            "Trigger price and tick must be finite and positive"
        }
        val v = BigDecimal.valueOf(value)
        val s = BigDecimal.valueOf(step)
        val units = v.divide(s, 0, RoundingMode.CEILING)
        return units.multiply(s).stripTrailingZeros().toDouble()
    }

    fun decimal(value: Double): BigDecimal {
        require(value.isFinite()) { "Decimal input must be finite" }
        return BigDecimal.valueOf(value)
    }

    fun decimal(value: String): BigDecimal =
        value.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Invalid decimal exchange value")

    fun plain(value: BigDecimal, scale: Int = 18): String =
        value.setScale(scale.coerceIn(0, 18), RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()

    fun floorToScale(value: Double, scale: Int): String {
        require(value.isFinite() && scale in 0..8) { "Invalid number or decimal scale" }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.DOWN).toPlainString()
    }

    fun priceToTick(value: Double, tick: Double): Double = floorToStep(value, tick)
    fun quantityToStep(value: Double, step: Double): Double = floorToStep(value, step)

    fun isMultiple(value: Double, step: Double): Boolean {
        if (!value.isFinite() || value <= 0.0 || !step.isFinite() || step <= 0.0) return false
        val v = BigDecimal.valueOf(value)
        val s = BigDecimal.valueOf(step)
        return v.remainder(s).compareTo(BigDecimal.ZERO) == 0
    }
}
