package com.williamsbot

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Public USDⓈ-M Futures Testnet market-data transport.
 *
 * Intentionally has no credentials and no order-mutation methods. This keeps candle
 * history separate from Spot /api/v3 and from the signed execution adapter.
 */
internal class BinanceUsdMFuturesMarketData(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val base = "https://demo-fapi.binance.com"
    private val allowedSymbols = setOf(
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
        val normalizedSymbol = symbol.uppercase()
        require(normalizedSymbol in allowedSymbols) { "Symbol is outside the approved Futures universe" }
        require(interval in allowedIntervals) { "Unsupported Futures kline interval: $interval" }
        require(limit in 1..1500) { "Futures kline limit must be in 1..1500" }
        require(startTime == null || startTime >= 0L) { "startTime must be non-negative" }
        require(endTime == null || endTime >= 0L) { "endTime must be non-negative" }
        require(startTime == null || endTime == null || startTime <= endTime) {
            "startTime must not exceed endTime"
        }

        val builder = HttpUrl.parse("$base/fapi/v1/klines")!!.newBuilder()
            .addQueryParameter("symbol", normalizedSymbol)
            .addQueryParameter("interval", interval)
            .addQueryParameter("limit", limit.toString())
        startTime?.let { builder.addQueryParameter("startTime", it.toString()) }
        endTime?.let { builder.addQueryParameter("endTime", it.toString()) }

        val request = Request.Builder().url(builder.build()).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("USD-M Futures klines HTTP ${response.code()}: ${body.take(300)}")
            }
            val rows = JSONArray(body)
            if (rows.length() > limit) {
                throw IOException("USD-M Futures returned more candles than requested")
            }
            return rows
        }
    }
}
