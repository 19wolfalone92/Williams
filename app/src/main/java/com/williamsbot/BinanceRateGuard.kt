package com.williamsbot

import okhttp3.Headers
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/**
 * Global Binance REST governor.
 *
 * It records usage headers, applies backoff after 429/418, and prevents
 * concurrent callers from hammering the same IP rate bucket.
 */
class BinanceRateGuard {
    @Volatile var usedWeight1m: Long = 0L
        private set
    @Volatile var orderCount1m: Long = 0L
        private set
    @Volatile var requestWeightLimit1m: Long = 6000L
        private set
    @Volatile var orderLimit1m: Long = 1200L
        private set
    @Volatile var cooldownUntilMs: Long = 0L
        private set

    private val lock = Any()
    private var nextRequestAtMs = 0L
    private val minGapMs = 50L

    fun beforeRequest() {
        while (true) {
            val now = System.currentTimeMillis()
            val scheduled = synchronized(lock) {
                val pressure = when {
                    requestWeightLimit1m > 0 &&
                        usedWeight1m >= requestWeightLimit1m * 0.98 -> 1000L
                    requestWeightLimit1m > 0 &&
                        usedWeight1m >= requestWeightLimit1m * 0.90 -> 250L
                    else -> 0L
                }
                val candidate = max(
                    now,
                    max(nextRequestAtMs, cooldownUntilMs) + pressure
                )
                nextRequestAtMs = candidate + minGapMs
                candidate
            }

            val waitMs = scheduled - now
            if (waitMs <= 0L) return
            try {
                Thread.sleep(waitMs.coerceAtMost(5000L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    fun observe(headers: Headers, statusCode: Int) {
        headers.names().forEach { name ->
            val normalized = name.lowercase(Locale.US)
            val value = headers[name]?.toLongOrNull() ?: return@forEach
            when {
                normalized == "x-mbx-used-weight-1m" -> {
                    usedWeight1m = value
                }
                normalized == "x-mbx-order-count-1m" -> {
                    orderCount1m = value
                }
            }
        }

        if (statusCode == 429 || statusCode == 418) {
            val retryAfterSeconds =
                headers["Retry-After"]?.toLongOrNull() ?: 1L
            cooldownUntilMs = max(
                cooldownUntilMs,
                System.currentTimeMillis() +
                    retryAfterSeconds.coerceAtLeast(1L) * 1000L
            )
        }
    }

    fun updateFromExchangeInfo(info: JSONObject) {
        val limits = info.optJSONArray("rateLimits") ?: return
        for (i in 0 until limits.length()) {
            val row = limits.optJSONObject(i) ?: continue
            if (
                row.optString("interval") != "MINUTE" ||
                row.optInt("intervalNum", 0) != 1
            ) continue
            when (row.optString("rateLimitType")) {
                "REQUEST_WEIGHT" -> {
                    requestWeightLimit1m =
                        row.optLong("limit", requestWeightLimit1m)
                }
                "ORDERS" -> {
                    orderLimit1m =
                        row.optLong("limit", orderLimit1m)
                }
            }
        }
    }

    fun snapshot(): JSONObject =
        JSONObject()
            .put("used_weight_1m", usedWeight1m)
            .put("weight_limit_1m", requestWeightLimit1m)
            .put("order_count_1m", orderCount1m)
            .put("order_limit_1m", orderLimit1m)
            .put(
                "cooldown_ms",
                (cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            )
}
