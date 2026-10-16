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
    @Volatile var orderCount10s: Long = 0L
        private set
    @Volatile var orderCount1m: Long = 0L
        private set
    @Volatile var requestWeightLimit1m: Long = 6000L
        private set
    @Volatile var orderLimit10s: Long = 50L
        private set
    @Volatile var orderLimit1m: Long = 1200L
        private set
    @Volatile var cooldownUntilMs: Long = 0L
        private set
    @Volatile var lastStatusCode: Int = 0
        private set
    @Volatile var lastResponseAtMs: Long = 0L
        private set
    @Volatile var observedResponseCount: Long = 0L
        private set
    @Volatile var rateLimitResponseCount: Long = 0L
        private set
    @Volatile var lastWaitMs: Long = 0L
        private set
    @Volatile var totalWaitMs: Long = 0L
        private set

    private val lock = Any()
    private var nextRequestAtMs = 0L
    private val minGapMs = 50L

    fun beforeRequest(isOrder: Boolean = false) {
        val waitStartedAtMs = System.currentTimeMillis()
        if (Thread.currentThread().isInterrupted) {
            throw IllegalStateException(
                "Binance REST request interrupted before rate-limit scheduling; request was not sent"
            )
        }
        // Compute the pressure delay once per request. Reapplying it on every
        // sleep-loop iteration would keep pushing the target forward forever
        // whenever the usage counter stayed above the threshold.
        var targetMs = synchronized(lock) {
            val now = System.currentTimeMillis()
            val pressure = when {
                isOrder &&
                    orderLimit10s > 0 &&
                    orderCount10s >= orderLimit10s * 0.96 -> 1000L
                isOrder &&
                    orderLimit10s > 0 &&
                    orderCount10s >= orderLimit10s * 0.90 -> 250L
                isOrder &&
                    orderLimit1m > 0 &&
                    orderCount1m >= orderLimit1m * 0.95 -> 1000L
                requestWeightLimit1m > 0 &&
                    usedWeight1m >= requestWeightLimit1m * 0.98 -> 1000L
                requestWeightLimit1m > 0 &&
                    usedWeight1m >= requestWeightLimit1m * 0.90 -> 250L
                else -> 0L
            }
            val candidate = max(
                now + pressure,
                max(nextRequestAtMs, cooldownUntilMs)
            )
            nextRequestAtMs = candidate + minGapMs
            candidate
        }

        while (true) {
            val now = System.currentTimeMillis()
            val scheduled = synchronized(lock) {
                // Another request may have received 418/429 while this caller
                // was waiting. Honor the extended cooldown without reapplying
                // pressure to this request's already-reserved target.
                if (cooldownUntilMs > targetMs) {
                    targetMs = cooldownUntilMs
                    nextRequestAtMs = max(nextRequestAtMs, targetMs + minGapMs)
                }
                targetMs
            }
            val waitMs = scheduled - now
            if (waitMs <= 0L) {
                if (Thread.currentThread().isInterrupted) {
                    throw IllegalStateException(
                        "Binance REST request interrupted while waiting for rate-limit governor; request was not sent"
                    )
                }
                val waited = (System.currentTimeMillis() - waitStartedAtMs).coerceAtLeast(0L)
                synchronized(lock) {
                    lastWaitMs = waited
                    totalWaitMs += waited
                }
                return
            }
            try {
                Thread.sleep(waitMs.coerceAtMost(5000L))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException(
                    "Interrupted while waiting for Binance REST rate-limit governor; request was not sent",
                    interrupted
                )
            }
        }
    }

    fun observe(headers: Headers, statusCode: Int, isOrder: Boolean = false) {
        synchronized(lock) {
            lastStatusCode = statusCode
            lastResponseAtMs = System.currentTimeMillis()
            observedResponseCount += 1L
            if (statusCode == 418 || statusCode == 429) rateLimitResponseCount += 1L
        }
        headers.names().forEach { name ->
            val normalized = name.lowercase(Locale.US)
            val value = headers[name]?.toLongOrNull() ?: return@forEach
            when {
                normalized == "x-mbx-used-weight-1m" -> {
                    usedWeight1m = value
                }
                normalized == "x-mbx-order-count-10s" -> {
                    orderCount10s = value
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
            when (row.optString("rateLimitType")) {
                "REQUEST_WEIGHT" -> {
                    if (
                        row.optString("interval") == "MINUTE" &&
                        row.optInt("intervalNum", 0) == 1
                    ) {
                        requestWeightLimit1m =
                            row.optLong("limit", requestWeightLimit1m)
                    }
                }
                "ORDERS" -> {
                    when {
                        row.optString("interval") == "SECOND" &&
                            row.optInt("intervalNum", 0) == 10 ->
                            orderLimit10s =
                                row.optLong("limit", orderLimit10s)
                        row.optString("interval") == "MINUTE" &&
                            row.optInt("intervalNum", 0) == 1 ->
                            orderLimit1m =
                                row.optLong("limit", orderLimit1m)
                    }
                }
            }
        }
    }

    fun snapshot(): JSONObject =
        JSONObject()
            .put("used_weight_1m", usedWeight1m)
            .put("weight_limit_1m", requestWeightLimit1m)
            .put("order_count_10s", orderCount10s)
            .put("order_limit_10s", orderLimit10s)
            .put("order_count_1m", orderCount1m)
            .put("order_limit_1m", orderLimit1m)
            .put("last_http_status", lastStatusCode)
            .put("last_response_at_ms", lastResponseAtMs)
            .put("observed_response_count", observedResponseCount)
            .put("rate_limit_response_count", rateLimitResponseCount)
            .put("last_wait_ms", lastWaitMs)
            .put("total_wait_ms", totalWaitMs)
            .put(
                "cooldown_ms",
                (cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            )
}
