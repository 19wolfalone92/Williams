package com.williamsbot

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Native USDⓈ-M Futures Testnet transport.
 *
 * This deliberately has no live-host option. It is a transport adapter, not
 * permission to trade: callers must still pass execution/reconciliation/risk
 * gates before mutating orders. Never use Spot /api/v3 routes here.
 */
internal class BinanceUsdMFuturesTestnetAdapter(
    private val apiKey: String,
    apiSecret: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val secret = apiSecret.toByteArray(Charsets.UTF_8)
    private val base = "https://demo-fapi.binance.com"

    init {
        require(apiKey.isNotBlank()) { "Futures Testnet API key is missing" }
        require(apiSecret.isNotBlank()) { "Futures Testnet API secret is missing" }
    }

    fun serverTime(): JSONObject = request("GET", "/fapi/v1/time", emptyMap(), signed = false)

    fun exchangeInfo(): JSONObject =
        request("GET", "/fapi/v1/exchangeInfo", emptyMap(), signed = false)

    fun klines(symbol: String, interval: String = "1h", limit: Int = 300): JSONArray {
        requireSymbol(symbol)
        require(interval in setOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d"))
        require(limit in 1..1500)
        val url = HttpUrl.parse("$base/fapi/v1/klines")!!.newBuilder()
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("interval", interval)
            .addQueryParameter("limit", limit.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Futures klines HTTP ${response.code()}: ${body.take(300)}")
            return JSONArray(body)
        }
    }

    fun account(): JSONObject = request("GET", "/fapi/v2/account", emptyMap(), signed = true)

    fun positions(): JSONArray =
        JSONArray(requestRaw("GET", "/fapi/v2/positionRisk", emptyMap(), signed = true))

    fun openOrders(symbol: String): JSONArray {
        requireSymbol(symbol)
        return JSONArray(requestRaw("GET", "/fapi/v1/openOrders", mapOf("symbol" to symbol), signed = true))
    }

    /** New order is intentionally reduce-only capable; strategy must supply side and quantity. */
    fun newOrder(
        symbol: String,
        side: String,
        quantity: String,
        reduceOnly: Boolean,
        clientOrderId: String
    ): JSONObject {
        requireSymbol(symbol)
        require(side == "BUY" || side == "SELL") { "Order side must be BUY or SELL" }
        require(quantity.toBigDecimalOrNull()?.signum() == 1) { "Quantity must be positive" }
        require(clientOrderId.matches(Regex("[A-Za-z0-9_./:-]{1,36}"))) { "Invalid client order id" }
        val params = linkedMapOf(
            "symbol" to symbol,
            "side" to side,
            "type" to "MARKET",
            "quantity" to quantity,
            "newClientOrderId" to clientOrderId,
            "newOrderRespType" to "RESULT"
        )
        if (reduceOnly) params["reduceOnly"] = "true"
        return JSONObject(requestRaw("POST", "/fapi/v1/order", params, signed = true))
    }

    fun cancelOrder(symbol: String, clientOrderId: String): JSONObject {
        requireSymbol(symbol)
        require(clientOrderId.isNotBlank())
        return JSONObject(requestRaw("DELETE", "/fapi/v1/order", mapOf(
            "symbol" to symbol, "origClientOrderId" to clientOrderId
        ), signed = true))
    }

    private fun request(method: String, path: String, params: Map<String, String>, signed: Boolean): JSONObject =
        JSONObject(requestRaw(method, path, params, signed))

    private fun requestRaw(
        method: String,
        path: String,
        params: Map<String, String>,
        signed: Boolean
    ): String {
        require(path.startsWith("/fapi/")) { "Only USD-M Futures endpoints are allowed" }
        val all = LinkedHashMap(params)
        if (signed) {
            all["timestamp"] = System.currentTimeMillis().toString()
            all["recvWindow"] = "5000"
            all["signature"] = sign(encode(all))
        }
        val url = HttpUrl.parse(base + path)!!.newBuilder()
        val body: RequestBody?
        if (method == "GET" || method == "DELETE") {
            all.forEach { (key, value) -> url.addQueryParameter(key, value) }
            body = null
        } else {
            val form = FormBody.Builder()
            all.forEach { (key, value) -> form.add(key, value) }
            body = form.build()
        }
        val builder = Request.Builder().url(url.build()).header("Accept", "application/json")
        if (signed) builder.header("X-MBX-APIKEY", apiKey)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body!!)
            "DELETE" -> builder.delete(body)
            else -> throw IllegalArgumentException("Unsupported HTTP method")
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body()?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Binance USD-M Futures HTTP ${response.code()}: ${text.take(500)}")
            return text
        }
    }

    private fun encode(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun requireSymbol(symbol: String) {
        require(symbol.matches(Regex("[A-Z0-9]{5,20}")) && symbol.endsWith("USDT")) {
            "Only approved USDT-margined Futures symbols are supported"
        }
    }
}
