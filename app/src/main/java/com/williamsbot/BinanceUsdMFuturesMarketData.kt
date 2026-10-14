package com.williamsbot

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

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

    val approvedSymbols: Set<String> = setOf(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
    )
    private val allowedIntervals = setOf(
        "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h",
        "6h", "8h", "12h", "1d", "3d", "1w", "1M"
    )

    fun klines(
        symbol: String,
        interval: String,
        limit: Int,
        startTime: Long? = null,
        endTime: Long? = null
    ): JSONArray {
        val normalizedSymbol = symbol.uppercase(Locale.US)
        require(normalizedSymbol in approvedSymbols) { "Symbol is outside the approved Futures universe" }
        require(interval in allowedIntervals) { "Unsupported Futures kline interval: $interval" }
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

    fun tickerPrices(): JSONArray = getArray("$base/fapi/v1/ticker/price")
    fun ticker24h(): JSONArray = getArray("$base/fapi/v1/ticker/24hr")
    fun bookTickers(): JSONArray = getArray("$base/fapi/v1/ticker/bookTicker")
    fun exchangeInfo(): JSONObject = getObject("$base/fapi/v1/exchangeInfo")

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
