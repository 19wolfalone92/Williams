package com.williamsbot

import okhttp3.Headers
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/** Add a nonnegative interval to a timestamp without wrapping Long.MAX_VALUE. */
internal fun binanceRateGuardSaturatingAdd(timestampMs: Long, intervalMs: Long): Long {
    require(timestampMs >= 0L && intervalMs >= 0L)
    return if (Long.MAX_VALUE - timestampMs < intervalMs) Long.MAX_VALUE
        else timestampMs + intervalMs
}

/** Malformed negative Retry-After never shortens a previous exchange cooldown. */
internal fun binanceRateGuardCooldownDeadline(nowMs: Long, header: String?): Long {
    val seconds = (header?.trim()?.toLongOrNull() ?: 1L).coerceAtLeast(1L)
    val interval = if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE
        else seconds * 1_000L
    return binanceRateGuardSaturatingAdd(nowMs, interval)
}

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
    @Volatile var boundedWaitRejectedCount: Long = 0L
        private set

    private val lock = Any()
    private val originNs = System.nanoTime()
    private fun monotonicMs(): Long = ((System.nanoTime() - originNs) / 1_000_000L).coerceAtLeast(0L)
    private var nextRequestAtMs = 0L
    private var monotonicCooldownUntilMs = 0L
    private val minGapMs = 50L

    fun beforeRequest(isOrder: Boolean = false, maximumWaitMs: Long = 5_000L) {
        require(maximumWaitMs in 1L..60_000L) { "Invalid Binance governor wait budget" }
        val started = monotonicMs()
        val deadline = binanceRateGuardSaturatingAdd(started, maximumWaitMs)
        // Pressure is calculated once; rejected requests never reserve future slots.
        val eligibleAt = synchronized(lock) {
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
            binanceRateGuardSaturatingAdd(started, pressure)
        }
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw IllegalStateException("Binance REST request interrupted; request was not sent")
            }
            val waitMs = synchronized(lock) {
                val now = monotonicMs()
                val target = max(eligibleAt, max(nextRequestAtMs, monotonicCooldownUntilMs))
                if (now > deadline || target > deadline) {
                    boundedWaitRejectedCount += 1L
                    throw IllegalStateException(
                        "Binance REST governor wait exceeds ${maximumWaitMs}ms budget; " +
                            "cooldown retained; request was not sent"
                    )
                }
                if (target <= now) {
                    // Commit only actual admission, under the same lock that observes
                    // 429/418. Waking contenders must acquire separate paced slots.
                    nextRequestAtMs = binanceRateGuardSaturatingAdd(now, minGapMs)
                    lastWaitMs = now - started
                    totalWaitMs = binanceRateGuardSaturatingAdd(totalWaitMs, lastWaitMs)
                    return
                }
                target - now
            }
            try {
                Thread.sleep(waitMs.coerceAtMost(50L))
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
            // A malformed negative counter/limit must never disable pressure
            // throttling by replacing a valid nonnegative exchange reading.
            if (value < 0L) return@forEach
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
             // All contenders, including the reservation worker, share one lock.
            // @Volatile alone would not make max(old,new) atomic, and untrusted
            // Retry-After can overflow Long and accidentally release the cooldown.
            val deadline = binanceRateGuardCooldownDeadline(
                System.currentTimeMillis(), headers["Retry-After"])
            synchronized(lock) {
                cooldownUntilMs = max(cooldownUntilMs, deadline)
                monotonicCooldownUntilMs = max(monotonicCooldownUntilMs,
                    binanceRateGuardCooldownDeadline(monotonicMs(), headers["Retry-After"]))
            }
        }
    }

    fun updateFromExchangeInfo(info: JSONObject) {
        val limits = info.optJSONArray("rateLimits") ?: return
        for (i in 0 until limits.length()) {
            val row = limits.optJSONObject(i) ?: continue
            // No zero/negative rate limit may disable the governor's ceiling.
            val newLimit = row.optLong("limit", -1L)
            if (newLimit <= 0L) continue
            when (row.optString("rateLimitType")) {
                "REQUEST_WEIGHT" -> {
                    if (
                        row.optString("interval") == "MINUTE" &&
                        row.optInt("intervalNum", 0) == 1
                    ) {
                        requestWeightLimit1m =
                            newLimit
                    }
                }
                "ORDERS" -> {
                    when {
                        row.optString("interval") == "SECOND" &&
                            row.optInt("intervalNum", 0) == 10 ->
                            orderLimit10s =
                                newLimit
                        row.optString("interval") == "MINUTE" &&
                            row.optInt("intervalNum", 0) == 1 ->
                            orderLimit1m =
                                newLimit
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
            .put("bounded_wait_rejected_count", boundedWaitRejectedCount)
            .put(
                "cooldown_ms",
                synchronized(lock) { (monotonicCooldownUntilMs - monotonicMs()).coerceAtLeast(0L) }
            )
}
