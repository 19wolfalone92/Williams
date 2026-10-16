package com.williamsbot

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Single source of truth for the chart, market-data transport and execution universe. */
internal val APPROVED_FUTURES_SYMBOLS = listOf(
    "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
    "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
)
internal val APPROVED_FUTURES_KLINE_INTERVALS = listOf(
    "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h",
    "6h", "8h", "12h", "1d", "3d", "1w", "1M"
)

/**
 * Legacy runtime reads may use only explicitly approved public USD-M Futures routes.
 * No Spot-shaped route is translated: callers must be migrated to the canonical path.
 */
private val APPROVED_FUTURES_PUBLIC_PATHS = setOf(
    "/fapi/v1/time",
    "/fapi/v1/depth",
    "/fapi/v1/ticker/24hr",
    "/fapi/v1/ticker/bookTicker",
    "/fapi/v1/ticker/price",
    "/fapi/v1/avgPrice",
    "/fapi/v1/exchangeInfo"
)

internal fun normalizeFuturesPublicPath(path: String): String {
    require(path.isNotBlank() && path.startsWith("/") && !path.startsWith("//")) {
        "Invalid USD-M Futures public route"
    }
    val endpoint = path.substringBefore("?")
    require(endpoint in APPROVED_FUTURES_PUBLIC_PATHS) {
        "SECURITY_BLOCK: unsupported public route in USD-M Futures runtime: $endpoint"
    }
    require(!path.contains("#") && !path.contains('\n') && !path.contains('\r')) {
        "Invalid USD-M Futures public route"
    }
    return path
}


/**
 * Public USDⓈ-M Futures Testnet market-data transport.
 *
 * This class only reads the demo-fapi host. Signed account/order mutations are
 * owned by BinanceUsdMFuturesTestnetAdapter; Spot /api/v3 is never used here.
 */
internal class BinanceUsdMFuturesMarketData(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val base = "https://demo-fapi.binance.com"
    @Volatile private var serverOffsetMs = 0L
    @Volatile private var lastServerSyncLocalMs = 0L

    /** Exchange clock is authoritative for candle closure and stale-cache decisions. */
    fun exchangeNowMs(): Long {
        val localNow = System.currentTimeMillis()
        if (localNow - lastServerSyncLocalMs > 30_000L) {
            synchronized(this) {
                val before = System.currentTimeMillis()
                if (before - lastServerSyncLocalMs > 30_000L) {
                    val remote = getObject("$base/fapi/v1/time").optLong("serverTime", 0L)
                    val after = System.currentTimeMillis()
                    if (remote <= 0L) throw IOException("USD-M Futures server time unavailable")
                    serverOffsetMs = remote - (before + (after - before) / 2L)
                    lastServerSyncLocalMs = after
                }
            }
        }
        return System.currentTimeMillis() + serverOffsetMs
    }

    val approvedSymbols: Set<String> = APPROVED_FUTURES_SYMBOLS.toSet()
    internal val supportedIntervals: Set<String> = APPROVED_FUTURES_KLINE_INTERVALS.toSet()

    fun klines(
        symbol: String,
        interval: String,
        limit: Int,
        startTime: Long? = null,
        endTime: Long? = null
    ): JSONArray {
        val normalizedSymbol = symbol.uppercase(Locale.US)
        require(normalizedSymbol in approvedSymbols) { "Symbol is outside the approved Futures universe" }
        require(interval in supportedIntervals) { "Unsupported Futures kline interval: $interval" }
        require(limit in 1..1500) { "Futures kline limit must be in 1..1500" }
        require(startTime == null || startTime >= 0L) { "startTime must be non-negative" }
        require(endTime == null || endTime >= 0L) { "endTime must be non-negative" }
        require(startTime == null || endTime == null || startTime <= endTime) {
            "startTime must not exceed endTime"
        }
        val builder = "$base/fapi/v1/klines".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", normalizedSymbol)
            .addQueryParameter("interval", interval)
            .addQueryParameter("limit", limit.toString())
        startTime?.let { builder.addQueryParameter("startTime", it.toString()) }
        endTime?.let { builder.addQueryParameter("endTime", it.toString()) }
        val rows = getArray(builder.build().toString())
        if (rows.length() > limit) throw IOException("USD-M Futures returned more candles than requested")
        return rows
    }

    /** True only after Binance's exchange clock has passed the candle close timestamp. */
    internal fun isCandleClosed(closeTimeMs: Long, exchangeNowMs: Long): Boolean =
        closeTimeMs >= 0L && exchangeNowMs > closeTimeMs

    /**
     * Compute the true close boundary for a native Binance interval.
     * Monthly klines are calendar months, not fixed 30-day windows; using
     * 30 days can admit a still-forming January candle or hide a closed February candle.
     */
    internal fun klineCloseTimeMs(openTimeMs: Long, interval: String): Long {
        require(openTimeMs >= 0L) { "Kline open time must be non-negative" }
        require(interval in supportedIntervals) { "Unsupported Futures kline interval: $interval" }
        val nextOpenTimeMs = if (interval == "1M") {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = openTimeMs
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, 1)
            }.timeInMillis
        } else {
            val seconds = when (interval) {
                "1m" -> 60L
                "3m" -> 180L
                "5m" -> 300L
                "15m" -> 900L
                "30m" -> 1_800L
                "1h" -> 3_600L
                "2h" -> 7_200L
                "4h" -> 14_400L
                "6h" -> 21_600L
                "8h" -> 28_800L
                "12h" -> 43_200L
                "1d" -> 86_400L
                "3d" -> 259_200L
                "1w" -> 604_800L
                else -> error("Unsupported fixed-duration Futures interval: $interval")
            }
            if (openTimeMs > Long.MAX_VALUE - seconds * 1_000L) {
                throw IllegalArgumentException("Kline close time overflows Long")
            }
            openTimeMs + seconds * 1_000L
        }
        require(nextOpenTimeMs > openTimeMs) { "Kline next-open boundary is invalid" }
        return nextOpenTimeMs - 1L
    }

    internal fun isKlineOpenTimeClosed(
        openTimeMs: Long,
        interval: String,
        exchangeNowMs: Long
    ): Boolean = openTimeMs >= 0L &&
        exchangeNowMs > klineCloseTimeMs(openTimeMs, interval)

    /**
     * A cached last-closed candle remains current until the following candle has
     * itself closed. Derive both boundaries from the native interval, including
     * calendar-month boundaries; fixed 30-day arithmetic drifts on 28/29/31-day months.
     */
    internal fun isLastClosedCandleCurrent(
        openTimeMs: Long,
        interval: String,
        exchangeNowMs: Long
    ): Boolean {
        if (openTimeMs <= 0L || exchangeNowMs <= 0L) return false
        val closeTimeMs = klineCloseTimeMs(openTimeMs, interval)
        if (closeTimeMs == Long.MAX_VALUE) return false
        val nextOpenTimeMs = closeTimeMs + 1L
        val nextCloseTimeMs = klineCloseTimeMs(nextOpenTimeMs, interval)
        return exchangeNowMs <= nextCloseTimeMs
    }

    /** CandleN stores open time; derive the inclusive close timestamp without admitting a forming bar. */
    internal fun isCandleOpenTimeClosed(
        openTimeMs: Long,
        candleDurationMs: Long,
        exchangeNowMs: Long
    ): Boolean {
        if (openTimeMs < 0L || candleDurationMs <= 0L) return false
        val closeTimeMs = openTimeMs + candleDurationMs - 1L
        if (closeTimeMs < openTimeMs) return false // overflow
        return isCandleClosed(closeTimeMs, exchangeNowMs)
    }

    fun tickerPrices(): JSONArray = getArray("$base/fapi/v1/ticker/price")
    fun ticker24h(): JSONArray = getArray("$base/fapi/v1/ticker/24hr")
    fun bookTickers(): JSONArray = getArray("$base/fapi/v1/ticker/bookTicker")
    fun exchangeInfo(): JSONObject = getObject("$base/fapi/v1/exchangeInfo")

    /** Fresh bid/ask for the final entry-spread check; cached scanner spread is not execution authority. */
    fun topOfBook(symbol: String): Pair<Double, Double> {
        requireSymbol(symbol)
        val url = "$base/fapi/v1/ticker/bookTicker".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", symbol.uppercase(Locale.US))
            .build()
            .toString()
        val row = getObject(url)
        val bid = row.optString("bidPrice").toDoubleOrNull()
            ?: throw IOException("Malformed Futures best bid for $symbol")
        val ask = row.optString("askPrice").toDoubleOrNull()
            ?: throw IOException("Malformed Futures best ask for $symbol")
        if (!bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid) {
            throw IOException("Invalid Futures top of book for $symbol")
        }
        return bid to ask
    }

    fun price(symbol: String): Double {
        requireSymbol(symbol)
        val url = "$base/fapi/v1/ticker/price".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", symbol.uppercase(Locale.US)).build().toString()
        val value = getObject(url).optString("price").toDoubleOrNull()
            ?: throw IOException("Malformed Futures ticker price for $symbol")
        if (!value.isFinite() || value <= 0.0) throw IOException("Invalid Futures ticker price for $symbol")
        return value
    }

    fun markPrice(symbol: String): Double {
        requireSymbol(symbol)
        val url = "$base/fapi/v1/premiumIndex".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", symbol.uppercase(Locale.US)).build().toString()
        val value = getObject(url).optString("markPrice").toDoubleOrNull()
            ?: throw IOException("Malformed Futures mark price for $symbol")
        if (!value.isFinite() || value <= 0.0) throw IOException("Invalid Futures mark price for $symbol")
        return value
    }

    private fun requireSymbol(symbol: String) {
        require(symbol.uppercase(Locale.US) in approvedSymbols) {
            "Symbol is outside the approved Futures universe: $symbol"
        }
    }

    private fun getArray(url: String): JSONArray {
        val text = execute(url)
        return JSONArray(text)
    }

    private fun getObject(url: String): JSONObject {
        val text = execute(url)
        return JSONObject(text)
    }

    private fun execute(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("USD-M Futures market data HTTP ${response.code}: ${body.take(300)}")
            }
            return body
        }
    }
}
