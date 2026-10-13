package com.williamsbot

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BinanceFuturesApiException(
    message: String,
    val unknownExecution: Boolean,
    val statusCode: Int? = null
) : IOException(message)

/**
 * Separate USDⓈ-M Futures REST transport.
 *
 * This is not wired into StandaloneRuntime yet. Do not treat this transport
 * class alone as a Futures-ready trading engine: position reconciliation,
 * protective-stop lifecycle, risk sizing, and restart recovery must be
 * integrated before Futures trading can be enabled.
 */
class BinanceUsdMFuturesApi(
    private val apiKey: String,
    private val apiSecret: String,
    private val demo: Boolean = true,
    private val client: OkHttpClient = OkHttpClient()
) {
    val baseUrl: String = if (demo) {
        "https://demo-fapi.binance.com"
    } else {
        "https://fapi.binance.com"
    }

    init {
        require(apiKey.isNotBlank()) { "Futures API key is required" }
        require(apiSecret.isNotBlank()) { "Futures API secret is required" }
    }

    fun ping(): JSONObject = requestObject("GET", "/fapi/v1/ping")

    fun serverTime(): JSONObject = requestObject("GET", "/fapi/v1/time")

    fun exchangeInfo(symbol: String? = null): JSONObject {
        val params = if (symbol.isNullOrBlank()) emptyMap() else mapOf("symbol" to symbol.uppercase())
        return requestObject("GET", "/fapi/v1/exchangeInfo", params)
    }

    fun tickerPrice(symbol: String): JSONObject =
        requestObject("GET", "/fapi/v1/ticker/price", mapOf("symbol" to symbol.uppercase()))

    fun klines(symbol: String, interval: String, limit: Int = 200): JSONArray {
        require(limit in 1..1500) { "Futures kline limit must be in [1, 1500]" }
        return requestArray(
            "GET", "/fapi/v1/klines",
            mapOf("symbol" to symbol.uppercase(), "interval" to interval, "limit" to limit.toString())
        )
    }

    fun account(): JSONObject = requestObject("GET", "/fapi/v2/account", signed = true)

    fun positionMode(): JSONObject =
        requestObject("GET", "/fapi/v1/positionSide/dual", signed = true)

    fun changeLeverage(symbol: String, leverage: Int): JSONObject {
        require(leverage in 1..125) { "Futures leverage must be in [1, 125]" }
        return requestObject(
            "POST", "/fapi/v1/leverage",
            mapOf("symbol" to symbol.uppercase(), "leverage" to leverage.toString()),
            signed = true
        )
    }

    fun changeMarginType(symbol: String, marginType: String): JSONObject {
        val mode = marginType.uppercase()
        require(mode in setOf("ISOLATED", "CROSSED")) { "Margin type must be ISOLATED or CROSSED" }
        return requestObject(
            "POST", "/fapi/v1/marginType",
            mapOf("symbol" to symbol.uppercase(), "marginType" to mode),
            signed = true
        )
    }

    fun userTrades(symbol: String, orderId: Long? = null, limit: Int = 1000): JSONArray {
        require(limit in 1..1000) { "Futures user-trades limit must be in [1, 1000]" }
        val params = linkedMapOf("symbol" to symbol.uppercase(), "limit" to limit.toString())
        if (orderId != null) params["orderId"] = orderId.toString()
        return requestArray("GET", "/fapi/v1/userTrades", params, signed = true)
    }

    fun positionRisk(symbol: String? = null): JSONArray {
        val params = if (symbol.isNullOrBlank()) emptyMap() else mapOf("symbol" to symbol.uppercase())
        return requestArray("GET", "/fapi/v2/positionRisk", params, signed = true)
    }

    fun openOrders(symbol: String? = null): JSONArray {
        val params = if (symbol.isNullOrBlank()) emptyMap() else mapOf("symbol" to symbol.uppercase())
        return requestArray("GET", "/fapi/v1/openOrders", params, signed = true)
    }

    fun getOrder(symbol: String, clientOrderId: String): JSONObject {
        require(symbol.isNotBlank() && clientOrderId.isNotBlank())
        return requestObject(
            "GET",
            "/fapi/v1/order",
            mapOf("symbol" to symbol.uppercase(), "origClientOrderId" to clientOrderId),
            signed = true
        )
    }

    fun placeOrder(
        symbol: String,
        side: String,
        type: String,
        clientOrderId: String,
        quantity: String? = null,
        price: String? = null,
        stopPrice: String? = null,
        timeInForce: String? = null,
        positionSide: String = "BOTH",
        reduceOnly: Boolean? = null,
        closePosition: Boolean = false,
        workingType: String = "MARK_PRICE"
    ): JSONObject {
        require(symbol.isNotBlank()) { "Futures symbol is required" }
        require(side.uppercase() in setOf("BUY", "SELL")) { "Futures side must be BUY or SELL" }
        val normalizedType = type.uppercase()
        require(clientOrderId.matches(Regex("^[A-Za-z0-9_.:/-]{1,36}$"))) {
            "A stable Binance Futures client order ID is required"
        }
        val normalizedPositionSide = positionSide.uppercase()
        require(normalizedPositionSide in setOf("BOTH", "LONG", "SHORT")) {
            "positionSide must be BOTH, LONG or SHORT"
        }
        require(reduceOnly == null || normalizedPositionSide == "BOTH") {
            "reduceOnly must be omitted in Hedge Mode; select the correct positionSide and close side"
        }
        if (closePosition) {
            require(normalizedType in setOf("STOP_MARKET", "TAKE_PROFIT_MARKET")) {
                "closePosition is only valid for STOP_MARKET/TAKE_PROFIT_MARKET"
            }
            require(quantity == null && reduceOnly == null) {
                "closePosition cannot be combined with quantity or reduceOnly"
            }
            require(!stopPrice.isNullOrBlank()) { "closePosition protective order requires stopPrice" }
        }
        require(workingType.uppercase() in setOf("MARK_PRICE", "CONTRACT_PRICE")) {
            "workingType must be MARK_PRICE or CONTRACT_PRICE"
        }

        val params = linkedMapOf(
            "symbol" to symbol.uppercase(),
            "side" to side.uppercase(),
            "type" to normalizedType,
            "newClientOrderId" to clientOrderId,
            "positionSide" to normalizedPositionSide,
            "newOrderRespType" to "RESULT"
        )
        if (quantity != null) params["quantity"] = quantity
        if (price != null) params["price"] = price
        if (stopPrice != null) params["stopPrice"] = stopPrice
        if (timeInForce != null) params["timeInForce"] = timeInForce
        if (reduceOnly != null) params["reduceOnly"] = reduceOnly.toString()
        if (closePosition) params["closePosition"] = "true"
        if (normalizedType in setOf("STOP_MARKET", "TAKE_PROFIT_MARKET")) {
            params["workingType"] = workingType.uppercase()
        }
        return requestObject("POST", "/fapi/v1/order", params, signed = true)
    }

    /**
     * Submit once and reconcile ambiguous exchange outcomes by client order ID.
     * This method never blindly retries a Futures mutation.
     */
    fun placeOrderSafe(
        symbol: String,
        side: String,
        type: String,
        clientOrderId: String,
        quantity: String? = null,
        price: String? = null,
        stopPrice: String? = null,
        timeInForce: String? = null,
        positionSide: String = "BOTH",
        reduceOnly: Boolean? = null,
        closePosition: Boolean = false,
        workingType: String = "MARK_PRICE"
    ): JSONObject {
        val result = try {
            placeOrder(
                symbol = symbol,
                side = side,
                type = type,
                clientOrderId = clientOrderId,
                quantity = quantity,
                price = price,
                stopPrice = stopPrice,
                timeInForce = timeInForce,
                positionSide = positionSide,
                reduceOnly = reduceOnly,
                closePosition = closePosition,
                workingType = workingType
            )
        } catch (e: BinanceFuturesApiException) {
            if (!e.unknownExecution) throw e
            val existing = try {
                getOrder(symbol, clientOrderId)
            } catch (reconcileError: Exception) {
                throw BinanceFuturesApiException(
                    "Futures order outcome UNKNOWN for $symbol/$clientOrderId; reconciliation required",
                    unknownExecution = true,
                    statusCode = e.statusCode
                ).also { it.initCause(reconcileError) }
            }
            val observedStatus = existing.optString("status", "").uppercase()
            if (observedStatus in setOf("NEW", "PARTIALLY_FILLED", "FILLED", "PENDING_CANCEL", "PENDING_NEW")) {
                return existing
            }
            if (observedStatus in setOf("REJECTED", "EXPIRED", "CANCELED")) {
                throw BinanceFuturesApiException(
                    "Futures order confirmed terminal after ambiguous submission: $observedStatus",
                    unknownExecution = false,
                    statusCode = e.statusCode
                ).also { it.initCause(e) }
            }
            throw BinanceFuturesApiException(
                "Futures order state unresolved for $symbol/$clientOrderId; reconciliation required",
                unknownExecution = true,
                statusCode = e.statusCode
            ).also { it.initCause(e) }
        }
        val status = result.optString("status", "").uppercase()
        if (status in setOf("REJECTED", "EXPIRED", "CANCELED")) {
            throw BinanceFuturesApiException(
                "Futures order returned terminal status $status",
                unknownExecution = false
            )
        }
        if (status !in setOf("NEW", "PARTIALLY_FILLED", "FILLED", "PENDING_CANCEL", "PENDING_NEW")) {
            throw BinanceFuturesApiException(
                "Futures order response has unknown status ${status.ifBlank { "<missing>" }}; reconciliation required",
                unknownExecution = true
            )
        }
        return result
    }

    fun cancelOrder(symbol: String, clientOrderId: String): JSONObject {
        require(symbol.isNotBlank() && clientOrderId.isNotBlank())
        return requestObject(
            "DELETE",
            "/fapi/v1/order",
            mapOf("symbol" to symbol.uppercase(), "origClientOrderId" to clientOrderId),
            signed = true
        )
    }

    internal fun signedQuery(parameters: Map<String, String>, timestampMs: Long): String {
        val values = LinkedHashMap(parameters)
        values["recvWindow"] = "5000"
        values["timestamp"] = timestampMs.toString()
        val query = values.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(query.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$query&signature=$signature"
    }

    private fun requestObject(
        method: String,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        signed: Boolean = false
    ): JSONObject {
        val raw = execute(method, path, parameters, signed)
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw IOException("Binance Futures returned non-object response for $path", e)
        }
    }

    private fun requestArray(
        method: String,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        signed: Boolean = false
    ): JSONArray {
        val raw = execute(method, path, parameters, signed)
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            throw IOException("Binance Futures returned non-array response for $path", e)
        }
    }

    private fun execute(
        method: String,
        path: String,
        parameters: Map<String, String>,
        signed: Boolean
    ): String {
        val query = if (signed) {
            signedQuery(parameters, System.currentTimeMillis())
        } else {
            parameters.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        }
        val url = HttpUrl.get("$baseUrl$path").newBuilder().apply {
            if (query.isNotEmpty()) {
                query.split("&").forEach { pair ->
                    val separator = pair.indexOf('=')
                    if (separator > 0) {
                        addEncodedQueryParameter(pair.substring(0, separator), pair.substring(separator + 1))
                    }
                }
            }
        }.build()
        val builder = Request.Builder().url(url).header("X-MBX-APIKEY", apiKey)
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post("".toRequestBody(null))
            else -> throw IllegalArgumentException("Unsupported Futures HTTP method: $method")
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val ambiguous = method.uppercase() in setOf("POST", "PUT", "DELETE") &&
                        (response.code == 408 || response.code >= 500)
                    throw BinanceFuturesApiException(
                        "Binance Futures HTTP ${response.code} for $method $path; " +
                            (if (ambiguous) "mutation outcome may be unknown; reconcile before retry. " else "") +
                            "body=$body",
                        unknownExecution = ambiguous,
                        statusCode = response.code
                    )
                }
                return body
            }
        } catch (e: BinanceFuturesApiException) {
            throw e
        } catch (e: IOException) {
            throw BinanceFuturesApiException(
                "Binance Futures transport failure for $method $path; " +
                    "reconcile before retry if this was a mutation",
                unknownExecution = method.uppercase() in setOf("POST", "PUT", "DELETE")
            ).also { it.initCause(e) }
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
