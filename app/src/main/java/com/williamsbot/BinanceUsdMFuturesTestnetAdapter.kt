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
    @Volatile private var serverOffsetMs: Long = 0L
    @Volatile private var timeSynced: Boolean = false

    init {
        require(apiKey.isNotBlank()) { "Futures Testnet API key is missing" }
        require(apiSecret.isNotBlank()) { "Futures Testnet API secret is missing" }
    }

    fun serverTime(): JSONObject {
        val before = System.currentTimeMillis()
        val response = request("GET", "/fapi/v1/time", emptyMap(), signed = false)
        val after = System.currentTimeMillis()
        val exchangeTime = response.optLong("serverTime", 0L)
        if (exchangeTime <= 0L) throw IOException("Futures server time response is invalid")
        // Midpoint estimation avoids bias from the request's one-way network latency.
        serverOffsetMs = exchangeTime - (before + (after - before) / 2L)
        timeSynced = true
        return response
    }

    fun exchangeInfo(): JSONObject =
        request("GET", "/fapi/v1/exchangeInfo", emptyMap(), signed = false)

    fun markPrice(symbol: String): Double {
        requireSymbol(symbol)
        val url = HttpUrl.parse("$base/fapi/v1/premiumIndex")!!.newBuilder()
            .addQueryParameter("symbol", symbol)
            .build()
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Futures mark price HTTP ${response.code()}: ${body.take(300)}")
            val value = JSONObject(body).optString("markPrice").toDoubleOrNull()
                ?: throw IOException("Futures mark price response is malformed")
            if (!value.isFinite() || value <= 0.0) throw IOException("Futures mark price is invalid")
            return value
        }
    }

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

    fun account(): JSONObject = request("GET", "/fapi/v3/account", emptyMap(), signed = true)

    /** Must be checked before trading; the adapter assumes one-way mode (BOTH). */
    fun positionMode(): JSONObject =
        request("GET", "/fapi/v1/positionSide/dual", emptyMap(), signed = true)

    fun setOneWayMode(): JSONObject =
        request("POST", "/fapi/v1/positionSide/dual", mapOf("dualSidePosition" to "false"), signed = true)

    /** Fail closed if the account is in Hedge Mode; order methods use BOTH semantics. */
    fun requireOneWayMode() {
        val dualSide = positionMode().optBoolean("dualSidePosition", true)
        check(!dualSide) {
            "Futures account is in Hedge Mode; expected One-way Mode (dualSidePosition=false)"
        }
    }

    fun setIsolatedMargin(symbol: String): JSONObject {
        requireSymbol(symbol)
        return request("POST", "/fapi/v1/marginType", mapOf("symbol" to symbol, "marginType" to "ISOLATED"), signed = true)
    }

    fun setLeverage(symbol: String, leverage: Int): JSONObject {
        requireSymbol(symbol)
        require(leverage in 1..2) { "Futures leverage is capped at 2x by this runtime" }
        return request("POST", "/fapi/v1/leverage", mapOf("symbol" to symbol, "leverage" to leverage.toString()), signed = true)
    }

    /** Idempotently enforce this bot's account policy before entry. */
    fun ensureIsolatedTwoX(symbol: String) {
        try {
            setIsolatedMargin(symbol)
        } catch (failure: IOException) {
            // Binance returns -4046 when the requested margin type is already active.
            if (!failure.message.orEmpty().contains("-4046")) throw failure
        }
        setLeverage(symbol, 2)
    }

    fun incomeHistory(startTime: Long, endTime: Long = System.currentTimeMillis()): JSONArray {
        require(startTime >= 0L && endTime >= startTime)
        return JSONArray(requestRaw("GET", "/fapi/v1/income", mapOf(
            "startTime" to startTime.toString(),
            "endTime" to endTime.toString(),
            "limit" to "1000"
        ), signed = true))
    }

    fun positions(): JSONArray =
        JSONArray(requestRaw("GET", "/fapi/v3/positionRisk", emptyMap(), signed = true))

    fun order(symbol: String, clientOrderId: String): JSONObject {
        requireSymbol(symbol)
        require(clientOrderId.isNotBlank())
        return request("GET", "/fapi/v1/order", mapOf("symbol" to symbol, "origClientOrderId" to clientOrderId), signed = true)
    }

    fun openOrders(symbol: String): JSONArray {
        requireSymbol(symbol)
        return JSONArray(requestRaw("GET", "/fapi/v1/openOrders", mapOf("symbol" to symbol), signed = true))
    }

    /**
     * Conditional orders use Binance's Algo Order API. Do not query/cancel these
     * through /fapi/v1/openOrders or /fapi/v1/order.
     */
    fun openAlgoOrders(symbol: String): JSONArray {
        requireSymbol(symbol)
        return JSONArray(requestRaw("GET", "/fapi/v1/openAlgoOrders", mapOf("symbol" to symbol), signed = true))
    }

    fun queryAlgoOrder(symbol: String, clientAlgoId: String): JSONObject {
        requireSymbol(symbol)
        require(clientAlgoId.isNotBlank())
        return request("GET", "/fapi/v1/algoOrder", mapOf(
            "symbol" to symbol, "clientAlgoId" to clientAlgoId
        ), signed = true)
    }

    fun cancelAlgoOrder(symbol: String, clientAlgoId: String): JSONObject {
        requireSymbol(symbol)
        require(clientAlgoId.isNotBlank())
        return JSONObject(requestRaw("DELETE", "/fapi/v1/algoOrder", mapOf(
            "symbol" to symbol, "clientAlgoId" to clientAlgoId
        ), signed = true))
    }

    fun cancelAlgoOrderById(symbol: String, algoId: String): JSONObject {
        requireSymbol(symbol)
        require(algoId.isNotBlank())
        return JSONObject(requestRaw("DELETE", "/fapi/v1/algoOrder", mapOf(
            "symbol" to symbol, "algoId" to algoId
        ), signed = true))
    }

    fun cancelAllAlgoOrders(symbol: String) {
        val orders = openAlgoOrders(symbol)
        for (i in 0 until orders.length()) {
            val row = orders.optJSONObject(i) ?: continue
            val id = row.optString("algoId", "")
            val clientId = row.optString("clientAlgoId", "")
            if (id.isNotBlank()) cancelAlgoOrderById(symbol, id)
            else if (clientId.isNotBlank()) cancelAlgoOrder(symbol, clientId)
            else throw IOException("Cannot safely cancel unidentified Futures algo order for $symbol")
        }
    }

    /** Explicit direction API prevents strategy code from accidentally mapping SHORT to BUY. */
    fun openMarketPosition(
        symbol: String,
        direction: String,
        quantity: String,
        clientOrderId: String
    ): JSONObject {
        require(direction == "LONG" || direction == "SHORT") { "Direction must be LONG or SHORT" }
        return newOrder(
            symbol = symbol,
            side = entrySide(direction),
            quantity = quantity,
            reduceOnly = false,
            clientOrderId = clientOrderId
        )
    }

    /** Close only by reducing the current one-way position; never reverse on exit. */
    fun closeMarketPosition(
        symbol: String,
        direction: String,
        quantity: String,
        clientOrderId: String
    ): JSONObject {
        require(direction == "LONG" || direction == "SHORT") { "Direction must be LONG or SHORT" }
        return newOrder(
            symbol = symbol,
            side = closeSide(direction),
            quantity = quantity,
            reduceOnly = true,
            clientOrderId = clientOrderId
        )
    }

    /** Immediate market order. Strategy must explicitly choose immediate versus pending entry. */
    fun newOrder(
        symbol: String,
        side: String,
        quantity: String,
        reduceOnly: Boolean,
        clientOrderId: String
    ): JSONObject = submitOrder(
        symbol = symbol,
        side = side,
        type = "MARKET",
        quantity = quantity,
        reduceOnly = reduceOnly,
        clientOrderId = clientOrderId
    )

    /** Native exchange-side pending LIMIT order; never silently converted to MARKET. */
    fun placeLimitOrder(
        symbol: String,
        side: String,
        quantity: String,
        limitPrice: String,
        reduceOnly: Boolean = false,
        postOnly: Boolean = false,
        clientOrderId: String
    ): JSONObject {
        val price = limitPrice.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Limit price must be numeric")
        require(price.signum() > 0) { "Limit price must be positive" }
        val params = linkedMapOf(
            "timeInForce" to if (postOnly) "GTX" else "GTC",
            "price" to price.stripTrailingZeros().toPlainString()
        )
        return submitOrder(
            symbol, side, "LIMIT", quantity, reduceOnly, clientOrderId, params
        )
    }

    /** Pending STOP/TAKE_PROFIT entry using Algo Order API. closePosition stays false for entries. */
    fun placeConditionalEntry(
        symbol: String,
        direction: String,
        triggerType: String,
        quantity: String,
        triggerPrice: String,
        clientOrderId: String
    ): JSONObject {
        requireSymbol(symbol)
        require(direction == "LONG" || direction == "SHORT")
        require(triggerType == "STOP" || triggerType == "TAKE_PROFIT")
        val qty = quantity.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Quantity must be numeric")
        val trigger = triggerPrice.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Trigger price must be numeric")
        require(qty.signum() > 0 && trigger.signum() > 0)
        require(clientOrderId.matches(Regex("[A-Za-z0-9_./:-]{1,36}")))
        requireOneWayMode()
        val mark = java.math.BigDecimal.valueOf(markPrice(symbol))
        val isBuy = direction == "LONG"
        val shouldTriggerAbove = (triggerType == "STOP" && isBuy) ||
            (triggerType == "TAKE_PROFIT" && !isBuy)
        require(if (shouldTriggerAbove) trigger > mark else trigger < mark) {
            "Conditional entry trigger is on the wrong side of the current mark price"
        }
        val exchangeType = when (triggerType) {
            "STOP" -> "STOP_MARKET"
            "TAKE_PROFIT" -> "TAKE_PROFIT_MARKET"
            else -> throw IllegalArgumentException("Unsupported Futures conditional entry type")
        }
        val params = linkedMapOf(
            "algoType" to "CONDITIONAL",
            "symbol" to symbol,
            "side" to entrySide(direction),
            "type" to exchangeType,
            "quantity" to qty.stripTrailingZeros().toPlainString(),
            "triggerPrice" to trigger.stripTrailingZeros().toPlainString(),
            "workingType" to "MARK_PRICE",
            "clientAlgoId" to clientOrderId
        )
        return JSONObject(requestRaw("POST", "/fapi/v1/algoOrder", params, signed = true))
    }

    private fun submitOrder(
        symbol: String,
        side: String,
        type: String,
        quantity: String,
        reduceOnly: Boolean,
        clientOrderId: String,
        extra: Map<String, String> = emptyMap()
    ): JSONObject {
        requireSymbol(symbol)
        require(side == "BUY" || side == "SELL") { "Order side must be BUY or SELL" }
        require(type == "MARKET" || type == "LIMIT") { "Unsupported immediate order type" }
        val qty = quantity.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Quantity must be numeric")
        require(qty.signum() > 0) { "Quantity must be positive" }
        require(clientOrderId.matches(Regex("[A-Za-z0-9_./:-]{1,36}"))) { "Invalid client order id" }
        requireOneWayMode()
        val params = linkedMapOf(
            "symbol" to symbol,
            "side" to side,
            "type" to type,
            "quantity" to qty.stripTrailingZeros().toPlainString(),
            "newClientOrderId" to clientOrderId,
            "newOrderRespType" to "RESULT"
        )
        params.putAll(extra)
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

    /**
     * Exchange-side protective stop. Uses STOP_MARKET + closePosition so a
     * partial fill/quantity rounding cannot leave an oversized exit quantity.
     * LONG protection is SELL below market; SHORT protection is BUY above market.
     */
    fun placeProtectiveStop(symbol: String, positionDirection: String, stopPrice: String, clientOrderId: String): JSONObject {
        requireSymbol(symbol)
        require(positionDirection == "LONG" || positionDirection == "SHORT")
        val stop = stopPrice.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Stop price must be numeric")
        require(stop.signum() > 0) { "Stop price must be positive" }
        require(clientOrderId.matches(Regex("[A-Za-z0-9_./:-]{1,36}"))) { "Invalid client algo id" }
        requireOneWayMode()

        // Validate trigger orientation against the current mark price. A stale or
        // unavailable mark price throws and therefore fails closed before mutation.
        val mark = java.math.BigDecimal.valueOf(markPrice(symbol))
        if (positionDirection == "LONG") {
            require(stop < mark) { "LONG protective stop must be below the current mark price" }
        } else {
            require(stop > mark) { "SHORT protective stop must be above the current mark price" }
        }

        val params = linkedMapOf(
            "algoType" to "CONDITIONAL",
            "symbol" to symbol,
            "side" to if (positionDirection == "LONG") "SELL" else "BUY",
            "type" to "STOP_MARKET",
            "triggerPrice" to stop.stripTrailingZeros().toPlainString(),
            "closePosition" to "true",
            "workingType" to "MARK_PRICE",
            "priceProtect" to "TRUE",
            "clientAlgoId" to clientOrderId
        )
        return JSONObject(requestRaw("POST", "/fapi/v1/algoOrder", params, signed = true))
    }

    private fun request(method: String, path: String, params: Map<String, String>, signed: Boolean): JSONObject =
        JSONObject(requestRaw(method, path, params, signed))

    private fun requestRaw(
        method: String,
        path: String,
        params: Map<String, String>,
        signed: Boolean
    ): String {
        return try {
            requestRawOnce(method, path, params, signed)
        } catch (error: IOException) {
            // Binance -1021 explicitly rejects a request whose timestamp is outside
            // recvWindow. Only this definite rejection permits a single resync/retry;
            // timeouts, connection resets and 5xx remain UNKNOWN and are never retried.
            if (!signed || !error.message.orEmpty().contains("-1021")) throw error
            timeSynced = false
            serverTime()
            requestRawOnce(method, path, params, signed = true)
        }
    }

    private fun requestRawOnce(
        method: String,
        path: String,
        params: Map<String, String>,
        signed: Boolean
    ): String {
        require(path.startsWith("/fapi/")) { "Only USD-M Futures endpoints are allowed" }
        val all = LinkedHashMap(params)
        if (signed) {
            if (!timeSynced) serverTime()
            all["timestamp"] = (System.currentTimeMillis() + serverOffsetMs).toString()
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
        require(symbol in APPROVED_SYMBOLS) {
            "Symbol is outside the fixed approved USD-M Futures universe: $symbol"
        }
    }

    companion object {
        internal fun entrySide(direction: String): String {
            require(direction == "LONG" || direction == "SHORT") { "Direction must be LONG or SHORT" }
            return if (direction == "LONG") "BUY" else "SELL"
        }

        internal fun closeSide(direction: String): String {
            require(direction == "LONG" || direction == "SHORT") { "Direction must be LONG or SHORT" }
            return if (direction == "LONG") "SELL" else "BUY"
        }

        val APPROVED_SYMBOLS: Set<String> = setOf(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
            "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
        )
    }
}
