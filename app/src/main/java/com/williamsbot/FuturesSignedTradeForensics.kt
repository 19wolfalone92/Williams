package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Read-only Binance userTrades evidence. It MUST NOT authorize position ownership,
 * STOP changes, or entries. A completed set of fills is not proof of a Williams
 * campaign when the original durable ENTRY/client order provenance is missing.
 */
internal object FuturesSignedTradeForensics {
    fun summarize(symbol: String, signedFills: List<JSONObject>?, nowMs: Long): JSONObject {
        val out = JSONObject().put("symbol", symbol)
            .put("source", "SIGNED_BINANCE_USDM_USER_TRADES_GET_ONLY")
            .put("ownership_verified", false)
            .put("can_change_stop", false)
            .put("can_submit_order", false)
            .put("closed_position_proven", false)
            .put("full_campaign_pnl_verified", false)
        if (signedFills == null) return out.put("status", "SIGNED_TRADE_READ_INCOMPLETE")
            .put("detail", "Signed trade request failed, saturated, or missing part of the time range.")
        // Prove strict identity/shape before counting a single reported fill.
        var buyQty=BigDecimal.ZERO
        var sellQty=BigDecimal.ZERO
        var realizedPnl=BigDecimal.ZERO
        var commissions=BigDecimal.ZERO
        var commissionKnown=true
        val orderIds=linkedSetOf<String>()
        val rows=JSONArray()
        for (fill in signedFills) {
            val qty=fill.optString("qty").toBigDecimalOrNull()
            val price=fill.optString("price").toBigDecimalOrNull()
            val id=fill.optString("orderId")
            val at=fill.optLong("time",0L)
            val side=fill.optString("side").uppercase()
            val realized=fill.optString("realizedPnl").toBigDecimalOrNull()
            val commission=fill.optString("commission").toBigDecimalOrNull()
            val currency=fill.optString("commissionAsset")
            if (fill.optString("symbol") != symbol ||
                id.toLongOrNull()?.let { it>0L } != true ||
                qty==null || qty.signum()<=0 ||
                price==null || price.signum()<=0 ||
                at<=0L || at>nowMs ||
                side !in setOf("BUY","SELL") || realized==null) {
                return out.put("status","SIGNED_TRADE_SHAPE_UNVERIFIED")
                    .put("detail","One or more signed trade rows failed strict identity/quantity/time/PnL checks. Do not infer closure.")
            }
            if (side=="BUY") buyQty+=qty else sellQty+=qty
            realizedPnl+=realized
            if (commission==null || commission.signum()<0 ||
                currency!="USDT") commissionKnown=false
            else commissions+=commission
            orderIds.add(id)
            rows.put(JSONObject().put("order_id",id).put("side",side)
                .put("qty",qty.stripTrailingZeros().toPlainString())
                .put("at_ms",at))
        }
        // Trade records can include prior entries, partial exits or manual trades.
        // Their count and PnL must never be treated as verified CLOSED campaigns.
        out.put("status",if(signedFills.isEmpty()) "NO_RECENT_SIGNED_FILLS"
            else "RECENT_SIGNED_FILLS_NO_CAMPAIGN_BINDING")
            .put("fill_count",signedFills.size)
            .put("exchange_order_count",orderIds.size)
            .put("buy_qty",buyQty.stripTrailingZeros().toPlainString())
            .put("sell_qty",sellQty.stripTrailingZeros().toPlainString())
            .put("window_realized_pnl_usdt",realizedPnl.stripTrailingZeros().toPlainString())
            .put("window_usdt_commission_known",commissionKnown)
            .put("window_usdt_commission",if(commissionKnown)
                commissions.stripTrailingZeros().toPlainString() else JSONObject.NULL)
            .put("trades_read_only",rows)
            .put("detail","Only signed executions inside the queried time window are reported. BUY after SHORT may be a close, but flat status and exact position/order provenance plus complete fees/funding are needed.")
        return out
    }
}
