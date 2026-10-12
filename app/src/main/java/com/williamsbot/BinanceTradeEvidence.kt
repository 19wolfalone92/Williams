package com.williamsbot

import org.json.JSONArray
import java.math.BigDecimal

/**
 * Strict, read-only validation of Binance Spot myTrades evidence for one order.
 * A successful result proves only that the supplied response is internally
 * consistent with the queried order and executed quantity; it does not prove
 * pagination beyond the caller's conservative < 1000 record limit, account
 * balance, campaign exposure, or protective-order coverage.
 */
internal object BinanceTradeEvidence {
    data class Verified(
        val tradeCount: Int,
        val tradeIds: List<String>,
        val executedQty: BigDecimal
    )

    fun verify(
        expectedSymbol: String,
        expectedOrderId: String,
        expectedExecutedQty: BigDecimal,
        trades: JSONArray
    ): Verified {
        require(expectedSymbol.isNotBlank()) { "TRADE_EVIDENCE_SYMBOL_REQUIRED" }
        require(expectedOrderId.isNotBlank()) { "TRADE_EVIDENCE_ORDER_ID_REQUIRED" }
        require(expectedExecutedQty.signum() >= 0) { "TRADE_EVIDENCE_EXECUTED_QTY_INVALID" }

        val ids = LinkedHashSet<String>()
        var totalQty = BigDecimal.ZERO
        for (index in 0 until trades.length()) {
            val trade = trades.optJSONObject(index)
                ?: throw IllegalArgumentException("TRADE_EVIDENCE_ROW_INVALID")
            require(trade.optString("symbol").equals(expectedSymbol, ignoreCase = true)) {
                "TRADE_EVIDENCE_SYMBOL_MISMATCH"
            }
            require(trade.optString("orderId") == expectedOrderId) {
                "TRADE_EVIDENCE_ORDER_ID_MISMATCH"
            }
            val id = trade.optString("id")
            require(id.isNotBlank() && id != "null") { "TRADE_EVIDENCE_TRADE_ID_MISSING" }
            require(ids.add(id)) { "TRADE_EVIDENCE_DUPLICATE_TRADE_ID" }

            val qty = trade.optString("qty").toBigDecimalOrNull()
                ?: throw IllegalArgumentException("TRADE_EVIDENCE_QTY_INVALID")
            val price = trade.optString("price").toBigDecimalOrNull()
                ?: throw IllegalArgumentException("TRADE_EVIDENCE_PRICE_INVALID")
            val quoteQty = trade.optString("quoteQty").toBigDecimalOrNull()
                ?: throw IllegalArgumentException("TRADE_EVIDENCE_QUOTE_QTY_INVALID")
            val commission = trade.optString("commission").toBigDecimalOrNull()
                ?: throw IllegalArgumentException("TRADE_EVIDENCE_COMMISSION_INVALID")
            val commissionAsset = trade.optString("commissionAsset")
            val time = trade.optLong("time", -1L)
            require(qty.signum() > 0) { "TRADE_EVIDENCE_QTY_NON_POSITIVE" }
            require(price.signum() > 0 && quoteQty.signum() > 0) {
                "TRADE_EVIDENCE_PRICE_OR_QUOTE_QTY_NON_POSITIVE"
            }
            require(commission.signum() >= 0) { "TRADE_EVIDENCE_COMMISSION_NEGATIVE" }
            require(commission.signum() == 0 || commissionAsset.isNotBlank()) {
                "TRADE_EVIDENCE_COMMISSION_ASSET_MISSING"
            }
            require(time > 0L) { "TRADE_EVIDENCE_TIME_INVALID" }
            require(trade.opt("isBuyer") is Boolean && trade.opt("isMaker") is Boolean) {
                "TRADE_EVIDENCE_SIDE_FLAGS_INVALID"
            }
            // A quoteQty may be rounded to exchange precision; reject only an
            // impossible value that materially disagrees with price * quantity.
            val expectedQuote = price.multiply(qty)
            val difference = expectedQuote.subtract(quoteQty).abs()
            val tolerance = expectedQuote.abs().movePointLeft(7).max(BigDecimal("0.00000001"))
            require(difference <= tolerance) { "TRADE_EVIDENCE_QUOTE_QTY_CONFLICT" }
            totalQty = totalQty.add(qty)
        }
        require(totalQty.compareTo(expectedExecutedQty) == 0) {
            "TRADE_EVIDENCE_EXECUTED_QTY_MISMATCH"
        }
        return Verified(trades.length(), ids.toList(), totalQty)
    }
}
