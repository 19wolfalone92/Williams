package com.williamsbot

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Testnet-only USDⓈ-M Futures REST client.
 *
 * This adapter intentionally has no mainnet URL and never retries a mutation
 * following an I/O failure or ambiguous server response. Callers must persist
 * an execution intent first and reconcile the same client ID before any retry.
 */
internal class BinanceUsdmFuturesClient(
    apiKey: String,
    apiSecret: String,
    private val http: OkHttpClient,
    private val beforeRequest: () -> Unit = {},
    private val recordRestCall: (String, String, Int, String?, String?) -> Unit = { _, _, _, _, _ -> },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val apiKey = apiKey.trim()
    private val apiSecret = apiSecret.trim()

    @Volatile
    private var timeOffsetMs = 0L

    @Volatile
    private var lastTimeSyncMs = 0L

    init {
        require(this.apiKey.isNotBlank()) { "Futures API key is required" }
        require(this.apiSecret.isNotBlank()) { "Futures API secret is required" }
    }

    fun syncTime(): JSONObject {
        val before = wallClockMs()
        val body = objectRequest("GET", "/fapi/v1/time", signed = false)
        val after = wallClockMs()
        val server = body.optLong("serverTime", 0L)
        require(server > 0L) { "Binance Futures server time unavailable" }
        timeOffsetMs = server - ((before + after) / 2L)
        lastTimeSyncMs = after
        return body
    }

    private fun signedTimestamp(): Long {
        if (wallClockMs() - lastTimeSyncMs > 15_000L) {
            runCatching { syncTime() }
        }
        return wallClockMs() + timeOffsetMs - 500L
    }

    fun ping(): JSONObject = objectRequest("GET", "/fapi/v1/ping", signed = false)

    fun exchangeInfo(symbol: String? = null): JSONObject {
        val params = linkedMapOf<String, String>()
        if (!symbol.isNullOrBlank()) params["symbol"] = symbol.uppercase(Locale.US)
        return objectRequest("GET", "/fapi/v1/exchangeInfo", params, signed = false)
    }

    fun symbolInfo(symbol: String): JSONObject {
        val wanted = symbol.uppercase(Locale.US)
        val rows = exchangeInfo(wanted).optJSONArray("symbols")
            ?: throw FuturesApiException("exchangeInfo omitted symbols")
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            if (item.optString("symbol").uppercase(Locale.US) == wanted) {
                if (item.optString("status") != "TRADING") {
                    throw FuturesApiException("$wanted Futures contract is not TRADING")
                }
                if (item.optString("contractType") != "PERPETUAL") {
                    throw FuturesApiException("$wanted is not a USDⓈ-M perpetual contract")
                }
                if (item.optString("marginAsset").uppercase(Locale.US) != "USDT") {
                    throw FuturesApiException("$wanted is not USDT-margined")
                }
                return item
            }
        }
        throw FuturesApiException("Unknown USDⓈ-M Futures symbol: $wanted")
    }

    fun symbolFilters(symbol: String): Map<String, JSONObject> {
        val info = symbolInfo(symbol)
        val filters = info.optJSONArray("filters")
            ?: throw FuturesApiException("$symbol exchangeInfo omitted filters")
        val result = linkedMapOf<String, JSONObject>()
        for (i in 0 until filters.length()) {
            val item = filters.optJSONObject(i) ?: continue
            result[item.optString("filterType").uppercase(Locale.US)] = item
        }
        return result
    }

    fun normalizeQuantity(symbol: String, quantity: Double, market: Boolean = true): String {
        require(quantity.isFinite() && quantity > 0.0) { "Quantity must be finite and positive" }
        val filters = symbolFilters(symbol)
        val filter = filters[if (market) "MARKET_LOT_SIZE" else "LOT_SIZE"]
            ?: filters["LOT_SIZE"]
            ?: throw FuturesApiException("$symbol has no LOT_SIZE filter")
        val step = filter.decimal("stepSize")
        val min = filter.decimal("minQty")
        val max = filter.decimal("maxQty")
        require(step.signum() > 0 && min.signum() >= 0 && max.signum() > 0) {
            "$symbol has invalid lot size filters"
        }
        val q = BigDecimal.valueOf(quantity)
        val rounded = q.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros()
        require(rounded.signum() > 0 && rounded >= min && rounded <= max) {
            "$symbol quantity $rounded is outside [$min, $max] after lot-step normalization"
        }
        return rounded.toPlainString()
    }

    fun normalizePrice(
        symbol: String,
        price: Double,
        direction: String,
        purpose: String
    ): String {
        require(price.isFinite() && price > 0.0) { "Price must be finite and positive" }
        val dir = direction.uppercase(Locale.US)
        val job = purpose.uppercase(Locale.US)
        require(dir == "LONG" || dir == "SHORT") { "Direction must be LONG or SHORT" }
        require(job == "ENTRY" || job == "STOP") { "Purpose must be ENTRY or STOP" }
        val filter = symbolFilters(symbol)["PRICE_FILTER"]
            ?: throw FuturesApiException("$symbol has no PRICE_FILTER")
        val tick = filter.decimal("tickSize")
        val min = filter.decimal("minPrice")
        val max = filter.decimal("maxPrice")
        require(tick.signum() > 0 && min.signum() >= 0 && max.signum() > 0) {
            "$symbol has invalid price filters"
        }
        val roundUp = (dir == "LONG" && job == "ENTRY") ||
            (dir == "SHORT" && job == "STOP")
        val mode = if (roundUp) RoundingMode.CEILING else RoundingMode.FLOOR
        val value = BigDecimal.valueOf(price)
            .divide(tick, 0, mode)
            .multiply(tick)
            .stripTrailingZeros()
        require(value >= min && value <= max) {
            "$symbol price $value is outside [$min, $max]"
        }
        return value.toPlainString()
    }

    fun tickerPrice(symbol: String): Double =
        objectRequest(
            "GET",
            "/fapi/v1/ticker/price",
            linkedMapOf("symbol" to symbol.uppercase(Locale.US)),
            signed = false
        ).optString("price").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: throw FuturesApiException("$symbol has no valid Futures ticker price")

    fun markPrice(symbol: String): Double =
        objectRequest(
            "GET",
            "/fapi/v1/premiumIndex",
            linkedMapOf("symbol" to symbol.uppercase(Locale.US)),
            signed = false
        ).optString("markPrice").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: throw FuturesApiException("$symbol has no valid Futures mark price")

    fun bookTicker(symbol: String): Pair<Double, Double> {
        val row = objectRequest(
            "GET",
            "/fapi/v1/ticker/bookTicker",
            linkedMapOf("symbol" to symbol.uppercase(Locale.US)),
            signed = false
        )
        val bid = row.optString("bidPrice").toDoubleOrNull() ?: 0.0
        val ask = row.optString("askPrice").toDoubleOrNull() ?: 0.0
        require(bid.isFinite() && ask.isFinite() && bid > 0.0 && ask >= bid) {
            "$symbol Futures book bid/ask is invalid"
        }
        return bid to ask
    }

    fun klines(symbol: String, interval: String, limit: Int = 180): JSONArray {
        require(limit in 50..1500) { "Kline limit must be in [50, 1500]" }
        val params = linkedMapOf(
            "symbol" to symbol.uppercase(Locale.US),
            "interval" to interval,
            "limit" to limit.toString()
        )
        return arrayRequest("GET", "/fapi/v1/klines", params, signed = false)
    }

    fun account(): JSONObject = objectRequest("GET", "/fapi/v3/account", signed = true)

    fun positionMode(): JSONObject =
        objectRequest("GET", "/fapi/v1/positionSide/dual", signed = true)

    fun requireOneWayMode(): JSONObject {
        val mode = positionMode()
        if (mode.optBoolean("dualSidePosition", true)) {
            throw FuturesApiException(
                "Hedge Mode is not supported. Switch manually to One-way Mode with no open positions/orders."
            )
        }
        return mode
    }

    fun positionRisk(symbol: String? = null): JSONArray {
        val params = linkedMapOf<String, String>()
        if (!symbol.isNullOrBlank()) params["symbol"] = symbol.uppercase(Locale.US)
        return arrayRequest("GET", "/fapi/v3/positionRisk", params, signed = true)
    }

    fun openOrders(symbol: String? = null): JSONArray {
        val params = linkedMapOf<String, String>()
        if (!symbol.isNullOrBlank()) params["symbol"] = symbol.uppercase(Locale.US)
        return arrayRequest("GET", "/fapi/v1/openOrders", params, signed = true)
    }

    fun openAlgoOrders(symbol: String? = null): JSONArray {
        val params = linkedMapOf<String, String>()
        if (!symbol.isNullOrBlank()) params["symbol"] = symbol.uppercase(Locale.US)
        return arrayRequest("GET", "/fapi/v1/openAlgoOrders", params, signed = true)
    }

    fun getOrder(symbol: String, clientOrderId: String? = null, orderId: String? = null): JSONObject {
        require(!clientOrderId.isNullOrBlank() || !orderId.isNullOrBlank()) {
            "Order lookup requires orderId or origClientOrderId"
        }
        val params = linkedMapOf("symbol" to symbol.uppercase(Locale.US))
        if (!clientOrderId.isNullOrBlank()) params["origClientOrderId"] = clientOrderId
        if (!orderId.isNullOrBlank()) params["orderId"] = orderId
        return objectRequest("GET", "/fapi/v1/order", params, signed = true)
    }

    fun getAlgoOrder(symbol: String, clientAlgoId: String? = null, algoId: String? = null): JSONObject {
        require(!clientAlgoId.isNullOrBlank() || !algoId.isNullOrBlank()) {
            "Algo order lookup requires algoId or clientAlgoId"
        }
        val params = linkedMapOf("symbol" to symbol.uppercase(Locale.US))
        if (!clientAlgoId.isNullOrBlank()) params["clientAlgoId"] = clientAlgoId
        if (!algoId.isNullOrBlank()) params["algoId"] = algoId
        return objectRequest("GET", "/fapi/v1/algoOrder", params, signed = true)
    }

    fun getUserTrades(symbol: String, orderId: String? = null): JSONArray {
        val params = linkedMapOf(
            "symbol" to symbol.uppercase(Locale.US),
            "limit" to "1000"
        )
        if (!orderId.isNullOrBlank()) params["orderId"] = orderId
        return arrayRequest("GET", "/fapi/v1/userTrades", params, signed = true)
    }

    fun prepareFlatSymbol(symbol: String) {
        requireOneWayMode()
        val positions = positionRisk(symbol)
        for (i in 0 until positions.length()) {
            val item = positions.optJSONObject(i) ?: continue
            if (item.optDouble("positionAmt", 0.0) != 0.0) {
                throw FuturesApiException("$symbol has live exposure; refusing margin/leverage changes")
            }
        }
        if (openOrders(symbol).length() != 0 || openAlgoOrders(symbol).length() != 0) {
            throw FuturesApiException("$symbol has open orders; refusing margin/leverage changes")
        }
        val margin = linkedMapOf(
            "symbol" to symbol.uppercase(Locale.US),
            "marginType" to "ISOLATED"
        )
        runCatching { objectRequest("POST", "/fapi/v1/marginType", margin, signed = true) }
            .onFailure { error ->
                val msg = error.message.orEmpty()
                if (!msg.contains("-4046")) throw error
            }
        objectRequest(
            "POST",
            "/fapi/v1/leverage",
            linkedMapOf("symbol" to symbol.uppercase(Locale.US), "leverage" to "1"),
            signed = true,
            mutation = true
        )
    }

    fun submitConditional(
        symbol: String,
        side: String,
        type: String,
        quantity: String?,
        triggerPrice: String,
        clientAlgoId: String,
        closePosition: Boolean = false,
        reduceOnly: Boolean = false,
        workingType: String = "MARK_PRICE"
    ): JSONObject {
        val normalizedSide = side.uppercase(Locale.US)
        val normalizedType = type.uppercase(Locale.US)
        require(normalizedSide in setOf("BUY", "SELL")) { "Side must be BUY or SELL" }
        require(normalizedType in setOf("STOP_MARKET", "TAKE_PROFIT_MARKET")) {
            "Only STOP_MARKET and TAKE_PROFIT_MARKET are enabled in this initial safety build"
        }
        require(clientAlgoId.isNotBlank() && clientAlgoId.length <= 36) {
            "clientAlgoId is required and must be <= 36 characters"
        }
        require(triggerPrice.toBigDecimalOrNull()?.signum() == 1) { "triggerPrice must be positive" }
        require(!(closePosition && quantity != null)) {
            "closePosition=true cannot be combined with quantity"
        }
        require(!(closePosition && reduceOnly)) {
            "closePosition=true cannot be combined with reduceOnly"
        }
        require(quantity != null || closePosition) { "quantity required unless closePosition=true" }

        val params = linkedMapOf(
            "algoType" to "CONDITIONAL",
            "symbol" to symbol.uppercase(Locale.US),
            "side" to normalizedSide,
            "type" to normalizedType,
            "triggerPrice" to triggerPrice,
            "workingType" to workingType.uppercase(Locale.US),
            "priceProtect" to "false",
            "clientAlgoId" to clientAlgoId,
            "newOrderRespType" to "RESULT"
        )
        if (quantity != null) params["quantity"] = quantity
        if (closePosition) params["closePosition"] = "true"
        if (reduceOnly) params["reduceOnly"] = "true"
        return mutationObject("POST", "/fapi/v1/algoOrder", params, clientAlgoId, algo = true)
    }

    fun submitMarket(
        symbol: String,
        side: String,
        quantity: String,
        clientOrderId: String,
        reduceOnly: Boolean
    ): JSONObject {
        require(side.uppercase(Locale.US) in setOf("BUY", "SELL")) { "Side must be BUY or SELL" }
        require(clientOrderId.isNotBlank() && clientOrderId.length <= 36) {
            "clientOrderId is required and must be <= 36 characters"
        }
        require(quantity.toBigDecimalOrNull()?.signum() == 1) { "Quantity must be positive" }
        val params = linkedMapOf(
            "symbol" to symbol.uppercase(Locale.US),
            "side" to side.uppercase(Locale.US),
            "type" to "MARKET",
            "quantity" to quantity,
            "newClientOrderId" to clientOrderId,
            "newOrderRespType" to "RESULT"
        )
        if (reduceOnly) params["reduceOnly"] = "true"
        return mutationObject("POST", "/fapi/v1/order", params, clientOrderId, algo = false)
    }

    fun cancelAlgo(symbol: String, clientAlgoId: String): JSONObject {
        require(clientAlgoId.isNotBlank()) { "clientAlgoId required" }
        val params = linkedMapOf(
            "symbol" to symbol.uppercase(Locale.US),
            "clientAlgoId" to clientAlgoId
        )
        try {
            return objectRequest("DELETE", "/fapi/v1/algoOrder", params, signed = true, mutation = true)
        } catch (x: FuturesApiException) {
            if (!x.outcomeUnknown) throw x
            val found = runCatching { getAlgoOrder(symbol, clientAlgoId = clientAlgoId) }.getOrNull()
            val status = found?.optString("algoStatus").orEmpty().uppercase(Locale.US)
            if (status in setOf("CANCELED", "EXPIRED", "FINISHED")) return found!!
            throw FuturesApiException(
                "Algo cancellation outcome UNKNOWN for $symbol/$clientAlgoId; reconcile before retry",
                outcomeUnknown = true,
                cause = x
            )
        }
    }

    private fun mutationObject(
        method: String,
        path: String,
        params: LinkedHashMap<String, String>,
        clientId: String,
        algo: Boolean
    ): JSONObject {
        try {
            val response = objectRequest(method, path, params, signed = true, mutation = true)
            val status = response.optString(if (algo) "algoStatus" else "status").uppercase(Locale.US)
            require(status.isNotBlank()) {
                "Exchange mutation response for $clientId lacks authoritative status"
            }
            return response
        } catch (x: FuturesApiException) {
            if (!x.outcomeUnknown) throw x
            val found = runCatching {
                if (algo) getAlgoOrder(params["symbol"]!!, clientAlgoId = clientId)
                else getOrder(params["symbol"]!!, clientOrderId = clientId)
            }.getOrNull()
            val status = if (algo) {
                found?.optString("algoStatus").orEmpty()
            } else {
                found?.optString("status").orEmpty()
            }.uppercase(Locale.US)
            if (found != null && status.isNotBlank()) return found
            throw FuturesApiException(
                "Mutation outcome UNKNOWN for $clientId; no authoritative matching order was found. Never blindly retry.",
                outcomeUnknown = true,
                cause = x
            )
        }
    }

    private fun objectRequest(
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        signed: Boolean,
        mutation: Boolean = false
    ): JSONObject {
        val raw = requestRaw(method, path, params, signed, mutation)
        return try {
            JSONObject(raw)
        } catch (x: Exception) {
            throw FuturesApiException(
                "Invalid JSON from Binance Futures for $method $path",
                outcomeUnknown = mutation,
                cause = x
            )
        }
    }

    private fun arrayRequest(
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        signed: Boolean
    ): JSONArray {
        val raw = requestRaw(method, path, params, signed, mutation = false)
        return try {
            JSONArray(raw)
        } catch (x: Exception) {
            throw FuturesApiException("Invalid JSON array from Binance Futures for $method $path", cause = x)
        }
    }

    private fun requestRaw(
        methodInput: String,
        path: String,
        paramsInput: Map<String, String>,
        signed: Boolean,
        mutation: Boolean
    ): String {
        val method = methodInput.uppercase(Locale.US)
        val params = LinkedHashMap(paramsInput)
        if (signed) {
            params["timestamp"] = signedTimestamp().toString()
            params["recvWindow"] = "5000"
        }
        val query = formEncode(params)
        val signature = if (signed) hmacSha256(query, apiSecret) else ""
        val encodedParams = if (signed) "$query&signature=$signature" else query
        val url = BASE_URL + path

        val body = when (method) {
            "POST" -> (encodedParams).toRequestBody(FORM_MEDIA_TYPE)
            "DELETE" -> null
            "GET" -> null
            else -> throw IllegalArgumentException("Unsupported HTTP method $method")
        }
        val builder = Request.Builder().url(
            if (method == "GET" || method == "DELETE") {
                if (encodedParams.isBlank()) url else "$url?$encodedParams"
            } else url
        )
        if (signed) builder.header("X-MBX-APIKEY", apiKey)
        if (method == "POST") builder.post(body!!)
        if (method == "DELETE") builder.delete()
        if (method == "GET") builder.get()

        beforeRequest()
        val requestSummary = if (mutation) params.filterKeys {
            it !in setOf("timestamp", "signature", "apiKey", "apiSecret")
        } else params
        try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty().ifBlank { "{}" }
                runCatching {
                    recordRestCall(method, path, response.code, JSONObject(requestSummary).toString(), text)
                }
                if (response.isSuccessful) return text

                val payload = runCatching { JSONObject(text) }.getOrNull()
                val code = payload?.optInt("code", 0) ?: 0
                val message = payload?.optString("msg").orEmpty().ifBlank { text }
                // -1021 is an explicit timestamp rejection before order admission.
                if (signed && code == -1021) {
                    syncTime()
                    if (mutation) {
                        // One retry is safe only because the exchange explicitly
                        // rejected the request as a timestamp error.
                        return requestRaw(method, path, paramsInput, signed = true, mutation = true)
                    }
                }
                val unknown = mutation && (
                    response.code >= 500 ||
                        response.code == 418 ||
                        response.code == 429
                    )
                throw FuturesApiException(
                    "Binance Futures HTTP ${response.code} ${if (code != 0) "($code)" else ""}: $message",
                    statusCode = response.code,
                    exchangeCode = code,
                    outcomeUnknown = unknown,
                    payload = text
                )
            }
        } catch (x: IOException) {
            throw FuturesApiException(
                "Binance Futures transport failure for $method $path; mutation must be reconciled",
                outcomeUnknown = mutation,
                cause = x
            )
        }
    }

    internal fun directionToEntrySide(direction: String): String =
        when (direction.uppercase(Locale.US)) {
            "LONG" -> "BUY"
            "SHORT" -> "SELL"
            else -> throw IllegalArgumentException("Direction must be LONG or SHORT")
        }

    internal fun directionToExitSide(direction: String): String =
        when (direction.uppercase(Locale.US)) {
            "LONG" -> "SELL"
            "SHORT" -> "BUY"
            else -> throw IllegalArgumentException("Direction must be LONG or SHORT")
        }

    internal fun directionToProtectiveSide(direction: String): String = directionToExitSide(direction)

    internal fun baseUrlForTest(): String = BASE_URL

    companion object {
        const val BASE_URL = "https://demo-fapi.binance.com"
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        internal fun formEncode(values: Map<String, String>): String =
            values.entries.joinToString("&") { (key, value) ->
                urlEncode(key) + "=" + urlEncode(value)
            }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        internal fun hmacSha256(payload: String, secret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

internal class FuturesApiException(
    message: String,
    val statusCode: Int = 0,
    val exchangeCode: Int = 0,
    val outcomeUnknown: Boolean = false,
    val payload: String? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

private fun JSONObject.decimal(name: String): BigDecimal =
    optString(name).toBigDecimalOrNull()
        ?: throw FuturesApiException("Exchange filter $name is missing or invalid")

