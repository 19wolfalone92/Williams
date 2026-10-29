package com.williamsbot

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A rejected or exhausted exchange clock calibration is NOT a retryable signed GET. */
internal class FuturesClockSyncException(detail: String, cause: Throwable? = null) : IOException(detail, cause)

internal class FuturesHttpException(
    val statusCode: Int,
    val exchangeCode: Int?,
    detail: String
) : IOException("Binance USD-M Futures HTTP $statusCode: $detail")

/** Thrown only before entering the protective STOP POST transport. A failed
 * GET/preflight cannot be confused with a lost acknowledgement after POST. */
internal class FuturesProtectionNotSubmittedException(cause: Exception) :
    IOException("Protective STOP was not submitted: " + (cause.message ?: cause.javaClass.simpleName), cause)

/**
 * Native USDⓈ-M Futures Testnet transport.
 *
 * This deliberately has no live-host option. It is a transport adapter, not
 * permission to trade: callers must still pass execution/reconciliation/risk
 * gates before mutating orders. Never use Spot /api/v3 routes here.
 */
internal fun hasLiveFuturesPosition(rows: JSONArray): Boolean {
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index)
            ?: throw IllegalStateException("Malformed Binance Futures position row at index $index")
        val rawAmount = row.optString("positionAmt", "")
        val amount = rawAmount.toBigDecimalOrNull()
            ?: throw IllegalStateException("Malformed Binance Futures positionAmt at index $index")
        if (amount.abs().signum() > 0) return true
    }
    return false
}

internal fun validateFuturesPositionSnapshot(rows: JSONArray): JSONArray {
    val identities = mutableSetOf<Pair<String, String>>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: error("Malformed Futures position row")
        val symbol = row.optString("symbol")
        val side = row.optString("positionSide", "BOTH")
        check(symbol.isNotBlank() && side in setOf("BOTH", "LONG", "SHORT") &&
            row.optString("positionAmt").toBigDecimalOrNull() != null && identities.add(symbol to side)) {
            "Malformed or duplicate Futures position identity/amount"
        }
    }
    return rows
}

/**
 * Sync against Binance using a monotonic clock. Expire samples after 30s and
 * whenever the Android wall clock and elapsed time diverge (sleep/manual clock
 * adjustments). Neither a cached offset nor a wide recvWindow authorizes trades.
 */
/** 8s network RTT is tolerable only for signed read-only recovery with a 10s recvWindow.
 * Order-changing operations still require a <=3s clock sample and a 5s recvWindow.
 * A wall/monotonic drift, missing exchange time or slow (>8s) response is untrusted.
 */
internal fun futuresClockProbeUsableForSignedRead(
    sentMonoNs: Long, roundTripMs: Long, wallElapsedMs: Long, exchangeTimeMs: Long
): Boolean = sentMonoNs > 0L && exchangeTimeMs > 0L &&
    roundTripMs in 0L..8_000L &&
    kotlin.math.abs(wallElapsedMs - roundTripMs) <= 1_000L

internal fun futuresClockSyncNeeded(
    synchronized: Boolean, anchorWallMs: Long, anchorMonoNs: Long,
    wallNowMs: Long, monoNowNs: Long
): Boolean {
    if (!synchronized || anchorWallMs <= 0L || anchorMonoNs <= 0L ||
        monoNowNs < anchorMonoNs || wallNowMs < anchorWallMs) return true
    val elapsedMs = (monoNowNs - anchorMonoNs) / 1_000_000L
    val wallElapsedMs = wallNowMs - anchorWallMs
    return elapsedMs > 30_000L ||
        kotlin.math.abs(wallElapsedMs - elapsedMs) > 1_000L
}

internal class BinanceUsdMFuturesTestnetAdapter(
    private val apiKey: String,
    apiSecret: String,
    http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val rateGuard: BinanceRateGuard? = null,
    private val beforeExposureMutation: () -> Unit = {}
) {
    // Application-level retry rules must also govern OkHttp's transparent retries
    // and redirects. A replayed POST can create duplicate exposure after a lost reply.
    private val http = http.newBuilder()
        // Android runtime shares a 45s read timeout with long-lived WebSockets.
        // The financial REST adapter must NOT inherit an unbounded total call
        // deadline: a dead radio/DNS route previously stalled reconciliation and
        // Android's worker heartbeat. POST/DELETE timeouts remain ambiguous and
        // are never transparently retried.
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val secret = apiSecret.toByteArray(Charsets.UTF_8)
    private val base = "https://demo-fapi.binance.com"
    private val clockLock = Any()
    @Volatile private var timeSynced: Boolean = false
    @Volatile private var serverAnchorMs: Long = 0L
    @Volatile private var anchorMonoNs: Long = 0L
    @Volatile private var anchorWallMs: Long = 0L
    @Volatile private var lastClockProbeRttMs: Long = Long.MAX_VALUE

    private fun ensureFreshServerClock(readOnly: Boolean): Long = synchronized(clockLock) {
        val wall = System.currentTimeMillis()
        val mono = System.nanoTime()
        if (futuresClockSyncNeeded(timeSynced,anchorWallMs,anchorMonoNs,wall,mono)) {
            serverTime()
        }
        check(timeSynced && anchorMonoNs > 0L) {
            "Binance Futures server time is not confirmed; signed request blocked"
        }
        if (!readOnly && lastClockProbeRttMs > 3_000L) {
            // A slow read-only sample cannot sign a financial write. Retry ONLY
            // the unsigned GET /time preflight; a newly fast sample permits the
            // mutation, but failed/slow probes NEVER retry POST/DELETE.
            serverTime()
            if (lastClockProbeRttMs > 3_000L) {
                throw FuturesClockSyncException(
                    "Clock sample RTT $lastClockProbeRttMs ms exceeds 3000 ms mutation safety limit; financial write blocked"
                )
            }
        }
        val age = (System.nanoTime()-anchorMonoNs)/1_000_000L
        check(age in 0L..30_000L) { "Signed Futures timestamp is stale; request blocked" }
        serverAnchorMs + age
    }

    init {
        require(apiKey.isNotBlank()) { "Futures Testnet API key is missing" }
        require(apiSecret.isNotBlank()) { "Futures Testnet API secret is missing" }
    }

    fun serverTime(): JSONObject = synchronized(clockLock) {
        // A GET /time probe must measure only its own network trip. Neither
        // rate-limit waiting nor retry/backoff for earlier GETs may contribute
        // to the sample RTT used for signing financial requests.
        // Two bounded, independent read-only samples; no speculative mutation.
        timeSynced = false
        lastClockProbeRttMs = Long.MAX_VALUE
        var lastFailure: IOException? = null
        repeat(2) { attempt ->
            var sentMonoNs = 0L
            var sentWallMs = 0L
            val raw = try {
                requestRawOnce("GET", "/fapi/v1/time", emptyMap(), signed = false) {
                    sentMonoNs = System.nanoTime()
                    sentWallMs = System.currentTimeMillis()
                }
            } catch (failure: IOException) {
                // Explicit exchange rejection is preserved for callers and
                // must not be disguised as a transport-clock outage.
                if (failure is FuturesHttpException &&
                    !isRetryableSafeReadFailure(failure.message.orEmpty())) throw failure
                if (attempt == 1 || !isRetryableSafeReadFailure(failure.message.orEmpty())) {
                    throw FuturesClockSyncException(
                        "Futures server clock probe failed; signed request blocked", failure
                    )
                }
                lastFailure = failure
                return@repeat
            }
            val receivedMonoNs = System.nanoTime()
            val receivedWallMs = System.currentTimeMillis()
            val durationNs = receivedMonoNs - sentMonoNs
            val roundTripMs = durationNs / 1_000_000L
            val wallElapsedMs = receivedWallMs - sentWallMs
            val response = runCatching { JSONObject(raw) }.getOrNull()
            val exchangeTime = response?.optLong("serverTime", 0L) ?: 0L
            // A rejected sample NEVER establishes a new timestamp anchor.
            if (futuresClockProbeUsableForSignedRead(
                    sentMonoNs, roundTripMs, wallElapsedMs, exchangeTime
                )) {
                serverAnchorMs = exchangeTime
                // A slow read-only probe may be asymmetric. Using the midpoint
                // could put a signed timestamp >1000ms AHEAD of Binance time,
                // which Binance rejects even with a wide recvWindow. Anchor the
                // response at receipt for slow probes: bias behind the exchange,
                // bounded by the read-only 10s window. Never authorize writes
                // from such a sample.
                anchorMonoNs = if (roundTripMs > 3_000L) receivedMonoNs
                    else sentMonoNs + durationNs / 2L
                anchorWallMs = if (roundTripMs > 3_000L) receivedWallMs
                    else sentWallMs + wallElapsedMs / 2L
                lastClockProbeRttMs = roundTripMs
                timeSynced = true
                return@synchronized response!!
            }
            lastFailure = IOException(
                "Futures server time sample is invalid, delayed, or device clock changed " +
                    "(probe=${attempt + 1}, network_rtt_ms=$roundTripMs, wall_elapsed_ms=$wallElapsedMs)"
            )
        }
        throw FuturesClockSyncException(
            "Futures server clock could not be verified after bounded independent GET probes: " +
                (lastFailure?.message ?: "no valid sample"),
            lastFailure
        )
    }

    fun exchangeInfo(): JSONObject =
        request("GET", "/fapi/v1/exchangeInfo", emptyMap(), signed = false).also {
            rateGuard?.updateFromExchangeInfo(it)
        }

    fun markPrice(symbol: String): Double {
        requireSymbol(symbol)
        val body = requestRaw(
            "GET", "/fapi/v1/premiumIndex", mapOf("symbol" to symbol), signed = false
        )
        val value = JSONObject(body).optString("markPrice").toDoubleOrNull()
            ?: throw IOException("Futures mark price response is malformed")
        if (!value.isFinite() || value <= 0.0) throw IOException("Futures mark price is invalid")
        return value
    }

    /** Last traded price is the basis of ordinary Futures OHLC and book entry
     * breakouts. Never substitute the independent liquidation MARK index. */
    fun contractPrice(symbol: String): Double {
        requireSymbol(symbol)
        val row=JSONObject(requestRaw("GET", "/fapi/v2/ticker/price",mapOf("symbol" to symbol),signed=false))
        val value=row.optString("price").toDoubleOrNull()
        if(row.optString("symbol")!=symbol || value==null || !value.isFinite() || value<=0.0)
            throw IOException("Futures contract price response identity/value is invalid")
        return value
    }

    fun bestBidAsk(symbol:String):Pair<Double,Double> {
        requireSymbol(symbol)
        val row=JSONObject(requestRaw("GET","/fapi/v1/ticker/bookTicker",mapOf("symbol" to symbol),signed=false))
        val bid=row.optString("bidPrice").toDoubleOrNull()
        val ask=row.optString("askPrice").toDoubleOrNull()
        if(row.optString("symbol")!=symbol || bid==null || ask==null ||
            !bid.isFinite() || !ask.isFinite() || bid<=0 || ask<bid)
            throw IOException("Futures executable quote identity/value is invalid")
        return bid to ask
    }

    fun klines(symbol: String, interval: String = "1h", limit: Int = 300): JSONArray {
        requireSymbol(symbol)
        require(interval in APPROVED_FUTURES_KLINE_INTERVALS) { "Unsupported USD-M Futures kline interval: $interval" }
        require(limit in 1..1500)
        val body = requestRaw(
            "GET",
            "/fapi/v1/klines",
            mapOf("symbol" to symbol, "interval" to interval, "limit" to limit.toString()),
            signed = false
        )
        return JSONArray(body)
    }

    fun account(): JSONObject = request("GET", "/fapi/v3/account", emptyMap(), signed = true)

    /** Must be checked before trading; the adapter assumes one-way mode (BOTH). */
    fun positionMode(): JSONObject =
        request("GET", "/fapi/v1/positionSide/dual", emptyMap(), signed = true)

    fun setOneWayMode(): JSONObject =
        request("POST", "/fapi/v1/positionSide/dual", mapOf("dualSidePosition" to "false"), signed = true)

    /**
     * Enforce One-way mode only when safe. If an account is in Hedge Mode with
     * any live position, do not mutate account mode; require manual reconciliation.
     */
    fun requireOneWayMode() {
        if (!positionMode().optBoolean("dualSidePosition", true)) return
        val live = positions()
        // Never interpret malformed exchange data as a flat account. A malformed row
        // must block mode changes, because switching Hedge/One-way with live exposure
        // can alter the semantics of subsequent exits and protective orders.
        val hasPosition = hasLiveFuturesPosition(live)
        check(!hasPosition) {
            "Futures account is in Hedge Mode with live positions; flatten/reconcile manually before switching to One-way"
        }
        try {
            setOneWayMode()
        } catch (failure: IOException) {
            if (failure !is FuturesHttpException || failure.exchangeCode != -4059) throw failure
        }
        check(!positionMode().optBoolean("dualSidePosition", true)) {
            "Binance did not confirm One-way Mode after the safe mode-change request"
        }
    }

    fun setIsolatedMargin(symbol: String): JSONObject {
        requireSymbol(symbol)
        return request("POST", "/fapi/v1/marginType", mapOf("symbol" to symbol, "marginType" to "ISOLATED"), signed = true)
    }

    fun setLeverage(symbol: String, leverage: Int): JSONObject {
        requireSymbol(symbol)
        parseFuturesLeverage(leverage)
        val result = request("POST", "/fapi/v1/leverage", mapOf("symbol" to symbol, "leverage" to leverage.toString()), signed = true)
        val maximum = result.optString("maxNotionalValue").toDoubleOrNull()
        check(result.optString("symbol") == symbol && result.opt("leverage")?.toString() == leverage.toString() &&
            maximum != null && maximum.isFinite() && maximum > 0.0) {
            "Binance did not confirm requested Futures leverage"
        }
        return result
    }

    fun leverageBrackets(symbol: String): Any {
        requireSymbol(symbol)
        val body = requestRaw("GET", "/fapi/v1/leverageBracket", mapOf("symbol" to symbol), signed = true)
        return if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
    }

    fun symbolConfiguration(symbol: String): JSONObject {
        requireSymbol(symbol)
        val body = requestRaw("GET", "/fapi/v1/symbolConfig", mapOf("symbol" to symbol), signed = true)
        val rows = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
        val matches = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optString("symbol") == symbol }
        check(matches.size == 1) { "Missing or ambiguous Futures symbol configuration" }
        return matches.single()
    }

    /** Idempotently enforce this bot's account policy before entry. */
    fun ensureIsolatedLeverage(symbol: String, leverage: Int): JSONObject {
        try {
            setIsolatedMargin(symbol)
        } catch (failure: IOException) {
            // Binance returns -4046 when the requested margin type is already active.
            if (failure !is FuturesHttpException || failure.exchangeCode != -4046) throw failure
        }
        setLeverage(symbol, leverage)
        return requireIsolatedLeverage(symbol,leverage)
    }

    /** Live additions verify the pinned setting without changing account leverage. */
    fun requireIsolatedLeverage(symbol:String,leverage:Int):JSONObject {
        val confirmed = symbolConfiguration(symbol)
        check(confirmed.optString("marginType").equals("ISOLATED", ignoreCase = true) &&
            confirmed.opt("leverage")?.toString() == leverage.toString()) {
            "Binance symbol configuration does not match requested isolated leverage"
        }
        return confirmed
    }

    /**
     * Account-specific USD-M Futures fill history. Binance limits each time window
     * to at most seven days; callers must split longer campaign lifetimes.
     */
    fun userTrades(
        symbol: String,
        startTime: Long,
        endTime: Long,
        limit: Int = 1000
    ): JSONArray {
        requireSymbol(symbol)
        require(startTime >= 0L && endTime >= startTime) { "Invalid Futures user-trade time range" }
        require(endTime - startTime <= 7L * 24L * 60L * 60L * 1000L) {
            "USD-M Futures userTrades range cannot exceed seven days"
        }
        require(limit in 1..1000) { "USD-M Futures userTrades limit must be in 1..1000" }
        return JSONArray(requestRaw(
            "GET",
            "/fapi/v1/userTrades",
            mapOf(
                "symbol" to symbol,
                "startTime" to startTime.toString(),
                "endTime" to endTime.toString(),
                "limit" to limit.toString()
            ),
            signed = true
        ))
    }

    /** Exact child-order history; do not mix a later opposite campaign into a close. */
    fun userTradesByOrder(symbol:String,orderId:String):JSONArray {
        requireSymbol(symbol)
        require(orderId.toLongOrNull()?.let { it>0L }==true)
        return JSONArray(requestRaw("GET","/fapi/v1/userTrades",mapOf(
            "symbol" to symbol,"orderId" to orderId,"limit" to "1000"
        ),signed=true))
    }

    fun incomeHistory(startTime: Long, endTime: Long = System.currentTimeMillis()): JSONArray {
        require(startTime >= 0L && endTime >= startTime)
        val budget = IncomeHistoryReadBudget()
        val rows = readIncomeRange(startTime, endTime, depth = 0, budget = budget)
        // Time ranges are non-overlapping; sort defensively so reconstruction
        // and audit output are deterministic across recursive subdivisions.
        return JSONArray().apply {
            rows.sortedWith(
                compareBy<JSONObject> { it.optLong("time", Long.MIN_VALUE) }
                    .thenBy { it.optString("incomeType") }
                    .thenBy { it.optString("tranId") }
                    .thenBy { it.optString("tradeId") }
            ).forEach { put(it) }
        }
    }

    private data class IncomeHistoryReadBudget(
        var requests: Int = 0,
        var rows: Int = 0
    )

    /**
     * Binance's income endpoint caps each response at 1000 rows and has no fromId cursor.
     * Split saturated intervals at millisecond boundaries until each response is
     * demonstrably complete. Bound request count and row count to avoid a runaway
     * recursive read during pathological account activity; exceeding the budget fails
     * closed, and the risk gate does not accept a partial ledger.
     */
    private fun readIncomeRange(
        startTime: Long,
        endTime: Long,
        depth: Int,
        budget: IncomeHistoryReadBudget
    ): List<JSONObject> {
        require(depth <= 40) { "USD-M Futures income history pagination depth exceeded" }
        budget.requests++
        if (budget.requests > 64) {
            throw IOException("USD-M Futures income history exceeded the 64-request completeness budget")
        }
        val response = JSONArray(requestRaw("GET", "/fapi/v1/income", mapOf(
            "startTime" to startTime.toString(),
            "endTime" to endTime.toString(),
            "limit" to "1000"
        ), signed = true))
        if (response.length() < 1000) {
            budget.rows += response.length()
            if (budget.rows > 32_000) {
                throw IOException("USD-M Futures income history exceeded the 32000-row completeness budget")
            }
            return (0 until response.length()).map { index ->
                response.optJSONObject(index)
                    ?: throw IOException("Binance returned a malformed USD-M Futures income row at index $index")
            }
        }
        if (startTime >= endTime) {
            throw IOException("USD-M Futures income history has 1000 rows in a <=1ms interval; completeness is unknown")
        }
        val mid = startTime + (endTime - startTime) / 2L
        if (mid < startTime || mid >= endTime) {
            throw IOException("Cannot safely subdivide saturated USD-M Futures income history interval")
        }
        return readIncomeRange(startTime, mid, depth + 1, budget) +
            readIncomeRange(mid + 1L, endTime, depth + 1, budget)
    }

    fun positions(): JSONArray =
        validateFuturesPositionSnapshot(JSONArray(requestRaw("GET", "/fapi/v3/positionRisk", emptyMap(), signed = true)))

    fun order(symbol: String, clientOrderId: String): JSONObject {
        requireSymbol(symbol)
        require(clientOrderId.isNotBlank())
        return request("GET", "/fapi/v1/order", mapOf("symbol" to symbol, "origClientOrderId" to clientOrderId), signed = true)
    }

    fun orderById(symbol: String, orderId: String): JSONObject {
        requireSymbol(symbol)
        require(orderId.toLongOrNull()?.let { it > 0L } == true)
        return request("GET", "/fapi/v1/order", mapOf("symbol" to symbol, "orderId" to orderId), signed = true).also {
            check(it.optString("symbol") == symbol && it.optString("orderId") == orderId) {
                "Futures child order identity mismatch"
            }
        }
    }

    fun cancelOrderById(symbol: String, orderId: String): JSONObject {
        requireSymbol(symbol)
        require(orderId.toLongOrNull()?.let { it > 0L } == true)
        return request("DELETE", "/fapi/v1/order", mapOf("symbol" to symbol, "orderId" to orderId), signed = true)
    }

    fun openOrders(symbol: String): JSONArray {
        requireSymbol(symbol)
        return JSONArray(requestRaw("GET", "/fapi/v1/openOrders", mapOf("symbol" to symbol), signed = true))
    }

    fun allOpenOrders(): JSONArray =
        JSONArray(requestRaw("GET", "/fapi/v1/openOrders", emptyMap(), signed = true))

    /**
     * Conditional orders use Binance's Algo Order API. Do not query/cancel these
     * through /fapi/v1/openOrders or /fapi/v1/order.
     */
    fun openAlgoOrders(symbol: String): JSONArray {
        requireSymbol(symbol)
        return JSONArray(requestRaw("GET", "/fapi/v1/openAlgoOrders", mapOf("symbol" to symbol), signed = true))
    }

    fun allOpenAlgoOrders(): JSONArray =
        JSONArray(requestRaw("GET", "/fapi/v1/openAlgoOrders", emptyMap(), signed = true))

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
        val contract = java.math.BigDecimal.valueOf(contractPrice(symbol))
        val isBuy = direction == "LONG"
        val shouldTriggerAbove = (triggerType == "STOP" && isBuy) ||
            (triggerType == "TAKE_PROFIT" && !isBuy)
        require(if (shouldTriggerAbove) trigger > contract else trigger < contract) {
            "Conditional entry trigger is on the wrong side of the current contract price"
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
            "workingType" to "CONTRACT_PRICE",
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
    fun placeProtectiveStop(symbol: String, positionDirection: String, stopPrice: String, clientOrderId: String): JSONObject =
        submitProtectiveStop(symbol, positionDirection, stopPrice, clientOrderId, null)

    /** A fixed, freshly verified exposure can be protected before its old close-all
     * stop is canceled. Do not submit a second closePosition order for replacement.
     * reduceOnly prevents either overlapping stop from reversing the position.
     * Callers must recheck the exact quantity before removing the original stop. */
    fun placeReplacementProtectiveStop(
        symbol: String, positionDirection: String, stopPrice: String,
        clientOrderId: String, quantity: String
    ): JSONObject = submitProtectiveStop(symbol, positionDirection, stopPrice, clientOrderId, quantity)

    private fun submitProtectiveStop(
        symbol: String, positionDirection: String, stopPrice: String,
        clientOrderId: String, replacementQuantity: String?
    ): JSONObject {
        val params = try {
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
                "workingType" to "MARK_PRICE",
                // A protective stop must not be suppressed merely because MARK_PRICE
                // and CONTRACT_PRICE diverge during volatility. Trigger directly on the
                // chosen MARK_PRICE basis; priceProtect=true can delay the loss exit.
                "priceProtect" to "false",
                "clientAlgoId" to clientOrderId
            )
            if (replacementQuantity == null) {
                params["closePosition"] = "true"
            } else {
                val quantity = replacementQuantity.toBigDecimalOrNull()
                require(quantity != null && quantity.signum() > 0) { "Replacement quantity must be positive" }
                params["quantity"] = quantity.stripTrailingZeros().toPlainString()
                params["reduceOnly"] = "true"
            }
            params
        } catch (failure: Exception) {
            throw FuturesProtectionNotSubmittedException(failure)
        }
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
        var timestampResynced = false
        var safeReadAttempt = 0
        val safeReadDelaysMs = longArrayOf(250L, 500L, 1_000L)
        while (true) {
            try {
                return requestRawOnce(method, path, params, signed)
            } catch (error: IOException) {
                val message = error.message.orEmpty()
                // Binance -1021 explicitly rejects a stale timestamp. It is the only
                // condition permitting a single signed-mutation retry because the exchange
                // has definitively rejected that request before applying the mutation.
                if (signed && !timestampResynced &&
                    error is FuturesHttpException && error.exchangeCode == -1021
                ) {
                    timestampResynced = true
                    synchronized(clockLock) { timeSynced = false }
                    serverTime() // only an explicit rejection allows one mutation retry
                    continue
                }
                // Reads are idempotent. Retry transient transport/5xx failures only for GET;
                // never retry POST/DELETE on timeout or 5xx because exchange execution may
                // be ambiguous. 418/429 and all other 4xx are surfaced immediately.
                if (method.equals("GET", ignoreCase = true) &&
                    error !is FuturesClockSyncException &&
                    safeReadAttempt < safeReadDelaysMs.size &&
                    isRetryableSafeReadFailure(message)
                ) {
                    sleepBeforeSafeReadRetry(safeReadDelaysMs[safeReadAttempt])
                    safeReadAttempt++
                    continue
                }
                throw error
            }
        }
    }

    private fun isRetryableSafeReadFailure(message: String): Boolean {
        val status = Regex("HTTP (\\d{3})").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (status != null) return status in setOf(500, 502, 503, 504)
        return true // connection failure before an HTTP status exists
    }

    private fun sleepBeforeSafeReadRetry(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while retrying a safe USD-M Futures GET", interrupted)
        }
    }

    private fun requestRawOnce(
        method: String,
        path: String,
        params: Map<String, String>,
        signed: Boolean,
        beforeNetwork: (() -> Unit)? = null
    ): String {
        require(path.matches(Regex("/fapi/v[1-3]/[A-Za-z0-9]+(?:/[A-Za-z0-9]+)*"))) {
            "Only canonical USD-M Futures endpoints are allowed"
        }
        val orderMutation = isOrderMutation(method, path)
        // P0: the exposure-gate preflight used to run AFTER signing. If it
        // waited behind reconciliation the signed timestamp could expire
        // BEFORE the HTTP request left the device. Run it first.
        if (method == "POST" && path in setOf("/fapi/v1/order", "/fapi/v1/algoOrder") &&
            params["reduceOnly"] != "true" && params["closePosition"] != "true") {
            beforeExposureMutation()
        }
        // Rate limit admission may sleep: also finish it BEFORE signing.
        rateGuard?.beforeRequest(isOrder = orderMutation)
        val all = LinkedHashMap(params)
        if (signed) {
            all["timestamp"] = ensureFreshServerClock(readOnly = method == "GET").toString()
            // Reads may tolerate RTT jitter; financial mutations retain the
            // strict 5s exchange rejection window and never retry ambiguously.
            all["recvWindow"] = if (method == "GET") "10000" else "5000"
            all["signature"] = sign(encode(all))
        }
        val url = (base + path).toHttpUrl().newBuilder()
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
        beforeNetwork?.invoke()
        http.newCall(builder.build()).execute().use { response ->
            rateGuard?.observe(response.headers, response.code, isOrder = orderMutation)
            val text = response.body?.string().orEmpty()
            val exchangeCode = runCatching { JSONObject(text).optInt("code", 0) }.getOrNull()
            if (!response.isSuccessful || (exchangeCode != null && exchangeCode < 0)) {
                throw FuturesHttpException(response.code, exchangeCode, text.take(500))
            }
            // A 200 response with no body is not a valid market-history snapshot.
            // Surface it as a retryable *read-only* transport failure instead of
            // letting JSONArray/JSONObject throw an unhelpful "End of input".
            // No POST/DELETE is retried on this path.
            if (method == "GET" && text.isBlank()) {
                throw IOException("Empty USD-M Futures GET response: $path")
            }
            return text
        }
    }

    private fun isOrderMutation(method: String, path: String): Boolean =
        method.uppercase(Locale.US) in setOf("POST", "DELETE") &&
            path in setOf("/fapi/v1/order", "/fapi/v1/algoOrder", "/fapi/v1/batchOrders")

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

        val APPROVED_SYMBOLS: Set<String> = APPROVED_FUTURES_SYMBOLS.toSet()
    }
}
