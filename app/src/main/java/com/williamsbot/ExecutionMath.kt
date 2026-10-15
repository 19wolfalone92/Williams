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

    /** Number of decimal places required to represent an exchange tick/step exactly. */
    fun decimalPlacesForIncrement(increment: Double): Int {
        require(increment.isFinite() && increment > 0.0)
        return BigDecimal.valueOf(increment).stripTrailingZeros().scale().coerceAtLeast(0)
    }

    fun decimal(value: String): BigDecimal =
        value.toBigDecimalOrNull() ?: BigDecimal.ZERO

    fun plain(value: BigDecimal, scale: Int = 18): String =
        value.setScale(scale.coerceIn(0, 18), RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()

    fun floorToScale(value: Double, scale: Int): String =
        BigDecimal.valueOf(value)
            .setScale(scale.coerceIn(0, 18), RoundingMode.DOWN)
            .toPlainString()

    fun priceToTick(value: Double, tick: Double): Double =
        floorToStep(value, tick)

    fun quantityToStep(value: Double, step: Double): Double =
        floorToStep(value, step)

    /**
     * Smallest decimal quantity increment that is a multiple of both LOT_SIZE
     * and MARKET_LOT_SIZE steps. Choosing max(stepA, stepB) is not sufficient
     * when the steps are not integer multiples of each other.
     */
    fun commonQuantityStep(first: Double, second: Double): Double {
        require(first.isFinite() && first > 0.0)
        val secondDecimal = if (second.isFinite() && second > 0.0) BigDecimal.valueOf(second) else BigDecimal.ZERO
        return commonQuantityStep(BigDecimal.valueOf(first), secondDecimal).toDouble().also {
            require(it.isFinite() && it > 0.0)
        }
    }

    /** Decimal-exact least common multiple for arbitrary exchange step increments. */
    fun commonQuantityStep(first: BigDecimal, second: BigDecimal): BigDecimal {
        require(first.signum() > 0)
        if (second.signum() <= 0) return first.stripTrailingZeros()
        val a = first.stripTrailingZeros()
        val b = second.stripTrailingZeros()
        val scale = maxOf(0, a.scale(), b.scale())
        val ai = a.movePointRight(scale).toBigIntegerExact().abs()
        val bi = b.movePointRight(scale).toBigIntegerExact().abs()
        require(ai.signum() > 0 && bi.signum() > 0)
        val lcm = ai.divide(ai.gcd(bi)).multiply(bi)
        return BigDecimal(lcm, scale).stripTrailingZeros()
    }

    /**
     * Validate and serialize an entire live Futures position for a MARKET close.
     * Never floor a position quantity: doing so can leave dust exposure while the
     * exit state machine assumes the complete position is closing.
     *
     * Null/blank/Infinity maximum fields mean the exchange did not publish a finite
     * upper bound. A zero MARKET_LOT_SIZE step means that filter is disabled.
     */
    fun exactFullMarketCloseQuantity(
        positionAmount: String,
        lotMinQty: String,
        lotMaxQty: String?,
        lotStep: String,
        marketMinQty: String?,
        marketMaxQty: String?,
        marketStep: String?
    ): String {
        val quantity = positionAmount.toBigDecimalOrNull()?.abs()
            ?: throw IllegalArgumentException("Exchange position quantity is not a decimal")
        val lotMin = lotMinQty.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("LOT_SIZE minQty is invalid")
        val lotIncrement = lotStep.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("LOT_SIZE stepSize is invalid")
        val lotMax = lotMaxQty?.toBigDecimalOrNullIfFinite()
        require(quantity.signum() > 0 && lotMin.signum() > 0 && lotIncrement.signum() > 0) {
            "Position amount and LOT_SIZE limits must be positive"
        }
        require(quantity >= lotMin && (lotMax == null || quantity <= lotMax)) {
            "Full Futures exit quantity is outside LOT_SIZE bounds"
        }

        val marketIncrement = marketStep?.toBigDecimalOrNull()
        val marketLotEnabled = marketIncrement != null && marketIncrement.signum() > 0
        val step = if (marketLotEnabled) commonQuantityStep(lotIncrement, marketIncrement!!) else lotIncrement
        if (marketLotEnabled) {
            val marketMin = marketMinQty?.toBigDecimalOrNull()
                ?: throw IllegalArgumentException("MARKET_LOT_SIZE minQty is invalid")
            val marketMax = marketMaxQty?.toBigDecimalOrNullIfFinite()
            require(marketMin.signum() >= 0 && (marketMax == null || marketMax >= marketMin)) {
                "MARKET_LOT_SIZE limits are invalid"
            }
            require(quantity >= marketMin && (marketMax == null || quantity <= marketMax)) {
                "Full Futures exit quantity is outside MARKET_LOT_SIZE bounds"
            }
        }
        require(quantity.remainder(step).signum() == 0) {
            "Full Futures position quantity does not align to the combined market-exit step; refusing a rounded partial close"
        }
        return quantity.stripTrailingZeros().toPlainString()
    }

    private fun String.toBigDecimalOrNullIfFinite(): BigDecimal? {
        if (isBlank() || equals("Infinity", true) || equals("NaN", true)) return null
        return toBigDecimalOrNull()
    }

    fun isMultiple(value: Double, step: Double): Boolean {
        if (!value.isFinite() || !step.isFinite() || step <= 0.0) return false
        val v = BigDecimal.valueOf(value)
        val s = BigDecimal.valueOf(step)
        return v.remainder(s).compareTo(BigDecimal.ZERO) == 0
    }
}
