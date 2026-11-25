package com.williamsbot

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal const val FUTURES_DAILY_RISK_MODEL_VERSION = 2
private val MODEL_B_ZERO = BigDecimal.ZERO
private val MODEL_B_HUNDRED = BigDecimal("100")
private val MODEL_B_ENTRY_LIMIT = BigDecimal("0.03")
private val MODEL_B_EMERGENCY_LIMIT = BigDecimal("0.05")
private val MODEL_B_CAPITAL_FLOW_TYPES = setOf(
    "TRANSFER", "INTERNAL_TRANSFER", "DEPOSIT", "WITHDRAW", "WELCOME_BONUS",
    "BONUS", "CONTEST_REWARD", "AUTO_EXCHANGE", "COIN_SWAP_DEPOSIT",
    "COIN_SWAP_WITHDRAW", "CROSS_COLLATERAL_TRANSFER",
    "PORTFOLIO_MARGIN_TRANSFER", "STRATEGY_UMFUTURES_TRANSFER"
)
private val MODEL_B_TRADING_INCOME_TYPES = setOf(
    "REALIZED_PNL", "FUNDING_FEE", "COMMISSION", "INSURANCE_CLEAR",
    "REFERRAL_KICKBACK", "COMMISSION_REBATE", "API_REBATE",
    "OPTIONS_PREMIUM_FEE", "OPTIONS_SETTLE_PROFIT", "DELIVERED_SETTELMENT",
    "POSITION_LIMIT_INCREASE_FEE", "FEE_RETURN", "BFUSD_REWARD"
)

internal data class FuturesDailyRiskCalculationB(
    val dailyPnlUsdt: BigDecimal,
    val dailyLossUsdt: BigDecimal,
    val lossPct: BigDecimal,
    val riskAdjustedEquityUsdt: BigDecimal,
    val entryBlocked: Boolean,
    val emergencyBreakerTripped: Boolean
)

internal data class FuturesDailyIncomeSummaryB(
    val totalIncomeUsdt: BigDecimal,
    val capitalFlowUsdt: BigDecimal,
    val tradingIncomeUsdt: BigDecimal,
    val rowCount: Int,
    val symbols: Set<String>
)

internal data class FuturesPositionAtMidnightB(
    val quantity: BigDecimal,
    val entryPrice: BigDecimal?
)

internal data class FuturesDailyRiskBaselineB(
    val walletBalanceUsdt: BigDecimal,
    val unrealizedPnlUsdt: BigDecimal,
    val equityUsdt: BigDecimal,
    val positionsAtMidnight: JSONArray
)

private fun modelBDecimal(raw: Any?): BigDecimal? {
    val value = when (raw) {
        is BigDecimal -> raw
        is Number -> raw.toString().toBigDecimalOrNull()
        is String -> raw.trim().toBigDecimalOrNull()
        else -> null
    } ?: return null
    return value.takeIf { it.toString().isNotBlank() }
}

internal fun calculateFuturesDailyRiskModelB(
    startEquityUsdt: BigDecimal?,
    currentEquityUsdt: BigDecimal?,
    netExternalFlowsUsdt: BigDecimal?
): FuturesDailyRiskCalculationB? {
    val start = startEquityUsdt ?: return null
    val current = currentEquityUsdt ?: return null
    val flows = netExternalFlowsUsdt ?: return null
    if (start <= MODEL_B_ZERO || current < MODEL_B_ZERO) return null
    val pnl = current.subtract(start).subtract(flows)
    val loss = if (pnl < MODEL_B_ZERO) pnl.negate() else MODEL_B_ZERO
    val lossPct = loss.multiply(MODEL_B_HUNDRED).divide(start, MathContext.DECIMAL128)
    return FuturesDailyRiskCalculationB(
        dailyPnlUsdt = pnl,
        dailyLossUsdt = loss,
        lossPct = lossPct,
        riskAdjustedEquityUsdt = current.subtract(flows),
        entryBlocked = loss.compareTo(start.multiply(MODEL_B_ENTRY_LIMIT)) >= 0,
        emergencyBreakerTripped = loss.compareTo(start.multiply(MODEL_B_EMERGENCY_LIMIT)) >= 0
    )
}

internal fun summarizeFuturesDailyIncomeModelB(
    incomeRows: JSONArray,
    utcDayStartMs: Long,
    snapshotWindowStartMs: Long,
    snapshotWindowEndMs: Long
): FuturesDailyIncomeSummaryB? {
    if (utcDayStartMs < 0L || snapshotWindowStartMs < utcDayStartMs ||
        snapshotWindowEndMs < snapshotWindowStartMs) return null
    var total = MODEL_B_ZERO
    var capital = MODEL_B_ZERO
    var trading = MODEL_B_ZERO
    val seen = linkedMapOf<String, String>()
    val symbols = linkedSetOf<String>()
    for (index in 0 until incomeRows.length()) {
        val row = incomeRows.optJSONObject(index) ?: return null
        val type = row.optString("incomeType").trim().uppercase(Locale.US)
        val asset = row.optString("asset").trim().uppercase(Locale.US)
        if (type.isBlank() || asset != "USDT") return null
        val amount = modelBDecimal(row.opt("income")) ?: return null
        val stamp = row.optLong("time", Long.MIN_VALUE)
        if (stamp < utcDayStartMs || stamp > snapshotWindowEndMs) return null
        if (stamp in snapshotWindowStartMs..snapshotWindowEndMs) return null
        val tranId = row.optString("tranId").trim()
        if (tranId.isBlank()) return null
        val identity = "$type|$asset|$tranId"
        val signature = stamp.toString() + "|" + amount.toPlainString() + "|" +
            row.optString("symbol").uppercase(Locale.US) + "|" + row.optString("tradeId")
        val prior = seen[identity]
        if (prior != null) {
            if (prior != signature) return null
            continue
        }
        seen[identity] = signature
        when (type) {
            in MODEL_B_CAPITAL_FLOW_TYPES -> capital = capital.add(amount)
            in MODEL_B_TRADING_INCOME_TYPES -> trading = trading.add(amount)
            else -> return null
        }
        total = total.add(amount)
        val symbol = row.optString("symbol").trim().uppercase(Locale.US)
        if (symbol.isNotBlank()) symbols.add(symbol)
    }
    return FuturesDailyIncomeSummaryB(total, capital, trading, seen.size, symbols)
}

internal fun reverseFuturesPositionAtMidnightB(
    symbol: String,
    currentQuantity: BigDecimal,
    currentEntryPrice: BigDecimal?,
    trades: JSONArray,
    utcDayStartMs: Long,
    snapshotWindowStartMs: Long,
    snapshotWindowEndMs: Long
): FuturesPositionAtMidnightB? {
    var quantity = currentQuantity
    var entry = if (quantity.compareTo(MODEL_B_ZERO) == 0) null else currentEntryPrice
    if (quantity.compareTo(MODEL_B_ZERO) != 0 && (entry == null || entry <= MODEL_B_ZERO)) return null
    data class Trade(
        val time: Long, val id: Long, val delta: BigDecimal,
        val price: BigDecimal, val realizedPnl: BigDecimal?
    )
    val normalized = mutableListOf<Trade>()
    val seenIds = hashSetOf<Long>()
    for (index in 0 until trades.length()) {
        val row = trades.optJSONObject(index) ?: return null
        if (row.optString("symbol").uppercase(Locale.US) != symbol.uppercase(Locale.US)) return null
        if (!row.optString("positionSide", "BOTH").equals("BOTH", true)) return null
        val id = row.optString("id").toLongOrNull() ?: return null
        val stamp = row.optString("time").toLongOrNull() ?: return null
        if (id <= 0L || !seenIds.add(id) || stamp < utcDayStartMs || stamp > snapshotWindowEndMs) return null
        if (stamp in snapshotWindowStartMs..snapshotWindowEndMs) return null
        val side = row.optString("side").uppercase(Locale.US)
        val qty = modelBDecimal(row.opt("qty") ?: row.opt("executedQty")) ?: return null
        val price = modelBDecimal(row.opt("price")) ?: return null
        val realized = if (row.has("realizedPnl") && !row.isNull("realizedPnl"))
            modelBDecimal(row.opt("realizedPnl")) ?: return null else null
        if (qty <= MODEL_B_ZERO || price <= MODEL_B_ZERO) return null
        val direction = when (side) {
            "BUY" -> BigDecimal.ONE
            "SELL" -> BigDecimal.ONE.negate()
            else -> return null
        }
        normalized.add(Trade(stamp, id, qty.multiply(direction), price, realized))
    }
    normalized.sortWith(compareBy<Trade> { it.time }.thenBy { it.id })
    for (trade in normalized.asReversed()) {
        val before = quantity.subtract(trade.delta)
        val beforeEntry: BigDecimal? = when {
            before.compareTo(MODEL_B_ZERO) == 0 -> null
            quantity.compareTo(MODEL_B_ZERO) == 0 ||
                (before.signum() != quantity.signum()) -> {
                val closeQty = before.abs().min(trade.delta.abs())
                val realized = trade.realizedPnl ?: return null
                if (closeQty <= MODEL_B_ZERO) return null
                val signedCloseQty = closeQty.multiply(if (before.signum() > 0) BigDecimal.ONE else BigDecimal.ONE.negate())
                trade.price.subtract(realized.divide(signedCloseQty, MathContext.DECIMAL128))
            }
            trade.delta.signum() == quantity.signum() -> {
                val currentEntry = entry ?: return null
                val absBefore = before.abs()
                if (absBefore <= MODEL_B_ZERO) return null
                quantity.abs().multiply(currentEntry)
                    .subtract(trade.delta.abs().multiply(trade.price))
                    .divide(absBefore, MathContext.DECIMAL128)
            }
            else -> entry ?: return null
        }
        if (before.compareTo(MODEL_B_ZERO) != 0 && (beforeEntry == null || beforeEntry <= MODEL_B_ZERO)) return null
        quantity = before
        entry = beforeEntry
    }
    return FuturesPositionAtMidnightB(quantity, entry)
}

internal fun reconstructFuturesDailyRiskBaselineB(
    currentWalletUsdt: BigDecimal,
    income: FuturesDailyIncomeSummaryB,
    currentPositions: JSONArray,
    tradesBySymbol: Map<String, JSONArray>,
    markPrices: Map<String, BigDecimal>,
    symbols: Set<String>,
    utcDayStartMs: Long,
    snapshotWindowStartMs: Long,
    snapshotWindowEndMs: Long
): FuturesDailyRiskBaselineB? {
    if (currentWalletUsdt < MODEL_B_ZERO) return null
    val positionsBySymbol = linkedMapOf<String, JSONObject>()
    for (index in 0 until currentPositions.length()) {
        val row = currentPositions.optJSONObject(index) ?: return null
        val symbol = row.optString("symbol").trim().uppercase(Locale.US)
        if (symbol.isBlank() || positionsBySymbol.put(symbol, row) != null) return null
        if (!row.optString("positionSide", "BOTH").equals("BOTH", true)) return null
    }
    val allSymbols = linkedSetOf<String>().apply {
        addAll(symbols.map { it.uppercase(Locale.US) })
        addAll(positionsBySymbol.keys)
        addAll(income.symbols)
    }
    if (allSymbols.isEmpty()) return null
    var unrealized = MODEL_B_ZERO
    val positionRows = JSONArray()
    for (symbol in allSymbols.sorted()) {
        val row = positionsBySymbol[symbol]
        val quantity = modelBDecimal(row?.opt("positionAmt") ?: "0") ?: return null
        val entry = modelBDecimal(row?.opt("entryPrice") ?: "0") ?: return null
        val trades = tradesBySymbol[symbol] ?: return null
        val midnight = reverseFuturesPositionAtMidnightB(
            symbol, quantity, entry, trades, utcDayStartMs,
            snapshotWindowStartMs, snapshotWindowEndMs
        ) ?: return null
        val mark = if (midnight.quantity.compareTo(MODEL_B_ZERO) == 0) null else markPrices[symbol]
        if (midnight.quantity.compareTo(MODEL_B_ZERO) != 0 && (mark == null || mark <= MODEL_B_ZERO || midnight.entryPrice == null)) return null
        val upnl = if (mark == null) MODEL_B_ZERO
            else midnight.quantity.multiply(mark.subtract(checkNotNull(midnight.entryPrice)))
        unrealized = unrealized.add(upnl)
        positionRows.put(JSONObject()
            .put("symbol", symbol)
            .put("quantity", midnight.quantity.toPlainString())
            .put("entry_price", midnight.entryPrice?.toPlainString() ?: JSONObject.NULL)
            .put("mark_price", mark?.toPlainString() ?: JSONObject.NULL)
            .put("unrealized_pnl", upnl.toPlainString()))
    }
    val walletStart = currentWalletUsdt.subtract(income.totalIncomeUsdt)
    val equityStart = walletStart.add(unrealized)
    if (walletStart < MODEL_B_ZERO || equityStart <= MODEL_B_ZERO) return null
    return FuturesDailyRiskBaselineB(walletStart, unrealized, equityStart, positionRows)
}

internal fun futuresDailyRiskModelBSnapshotEstablished(
    raw: String?,
    accountFingerprint: String,
    utcDayStartMs: Long
): Boolean {
    if (raw.isNullOrBlank() || accountFingerprint.isBlank()) return false
    return runCatching {
        val row = JSONObject(raw)
        val wallet = modelBDecimal(row.opt("wallet_balance_at_midnight_usdt"))
        val unrealized = modelBDecimal(row.opt("unrealized_pnl_at_midnight_usdt"))
        val equity = modelBDecimal(row.opt("equity_at_midnight_usdt"))
        val windowStart = row.optLong("observed_window_start_ms", -1L)
        val windowEnd = row.optLong("observed_window_end_ms", -1L)
        row.optInt("schema_version", 0) == FUTURES_DAILY_RISK_MODEL_VERSION &&
            row.optLong("utc_day_start_ms", -1L) == utcDayStartMs &&
            row.optString("account_fingerprint") == accountFingerprint &&
            row.optBoolean("complete", false) &&
            row.optString("source") == "BINANCE_INCOME_USER_TRADES_MARK_PRICE_1M" &&
            wallet != null && wallet >= MODEL_B_ZERO &&
            unrealized != null && equity != null && equity > MODEL_B_ZERO &&
            windowStart >= utcDayStartMs && windowEnd >= windowStart &&
            row.optJSONArray("positions_at_midnight") != null
    }.getOrDefault(false)
}
